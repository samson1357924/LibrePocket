package dev.librepocket.agent.ui.setup

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.toProviderConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.KeyVault
import dev.librepocket.models.ModelsDevSnapshot
import dev.librepocket.policy.DataStorePolicyStore
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.DefaultProviderFactory
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One-shot key.write consent minted when [PolicyStore.evaluateFresh] returns ASK.
 *
 * In-memory only: never persisted, never placed in SavedState, and carries no
 * key material (identity snapshot + nonce only). Bound to the immutable
 * endpoint snapshot observed at mint time; consumed (nulled) on the first
 * confirm or abandon tap, so it can never be replayed or reused. Any
 * endpoint-identity edit discards it before use.
 */
private data class KeyWriteConsent(
    val action: String,
    val resource: String,
    val providerId: String,
    val baseUrl: String,
    val configRevision: Long?,
    val formRevision: Long,
    val nonce: Long,
)

/** Gate state for startup navigation. Loading and NoEndpoint are distinct (no FOUC). */
sealed interface EndpointGate {
    data object Loading : EndpointGate
    data object NoEndpoint : EndpointGate
    data class Ready(val config: EndpointConfig) : EndpointGate
}

sealed interface ModelDirectoryStatus {
    data object NotLoaded : ModelDirectoryStatus
    data object Remote : ModelDirectoryStatus
    data class Bundled(val reason: ModelsDevSnapshot.SnapshotFallbackReason) : ModelDirectoryStatus
}

data class SetupUiState(
    val presetId: String = ProviderCatalog.OPENAI_ID,
    val baseUrl: String = ProviderCatalog.OPENAI_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val showKey: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val testModels: Int? = null,
    val modelOptions: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelDirectoryStatus: ModelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
    // Transient model-refresh CAS epoch only; not endpoint/configuration revision.
    internal val modelRefreshRevision: Long = 0L,
    val confirmKeyWrite: Boolean = false,
    val errorCode: String? = null,
    val saved: Boolean = false,
)

/**
 * Owns the endpoint gate flow plus the setup form state.
 *
 * Key handling: the [apiKey] String in [SetupUiState] cannot be wiped (String is
 * immutable); it is cleared (set to "") immediately after [save] consumes it,
 * converted to a CharArray copy for [KeyVault.putKey] (which wipes that copy),
 * and never logged, never placed in SavedState. Display of existing keys uses
 * [KeyVault.hasKey] only (dots, never echo). [testConnection] uses the typed
 * key in memory only and never persists it.
 */
class SetupViewModel(
    private val store: EndpointStore,
    private val vaultSource: VaultSource,
    private val policy: PolicyStore,
    private val buildProvider: (ProviderConfig, KeyProvider) -> LlmProvider =
        { cfg, keys -> DefaultProviderFactory(keys).create(cfg) },
    private val fetchDirectory: (suspend (String) -> String)? = null,
    private val stagedAliasProvider: (String) -> String = SetupValidation::newStagedAlias,
) : ViewModel() {

    private val directoryClient by lazy {
        dev.librepocket.provider.defaultOkHttpClient(
            dev.librepocket.provider.ProviderHttpConfig(),
        )
    }

    private suspend fun vault(): KeyVault = vaultSource.vault()

    private val _form = MutableStateFlow(SetupUiState(model = ProviderCatalog.defaultModelFor(ProviderCatalog.OPENAI_ID)))
    val form: StateFlow<SetupUiState> = _form
    private val modelRefreshGeneration = AtomicLong(0L)
    private var modelRefreshJob: Job? = null
    // Stage2 form fence: identity/key intent epoch. Bumped only by
    // selectPreset/onBaseUrlChange/onApiKeyChange/prefillForEdit/logout.
    // onModelChange/toggleShowKey deliberately do NOT bump (model edits are
    // out of scope, see #17). All save-path TOCTOU checks compare the entry
    // snapshot against this; any mismatch fails closed with POLICY_DENIED.
    private val formRevision = AtomicLong(0L)

    // One-shot key.write consent (ASK path). Main-thread edits and the IO save
    // coroutine touch it from different threads; all access goes through
    // [consentLock]. Never persisted, never leaves this ViewModel.
    private val consentLock = Any()
    private var pendingConsent: KeyWriteConsent? = null
    private val consentNonces = AtomicLong(0L)

    companion object {
        /**
         * Bounded staged-alias mint attempts (initial mint + redraws).
         * Covers the snapshot-inequality guard and the existence-only
         * `hasKey` orphan guard; persistent collision fails closed with
         * POLICY_DENIED without touching the vault.
         */
        internal const val MAX_STAGED_MINT_ATTEMPTS = 5
    }

    /**
     * Serializes the save critical section ([executeKeyWrite] staged put,
     * re-verification, metadata CAS, old-alias retire) against privileged
     * [logout] teardown (vault delete + metadata clear) within this VM.
     *
     * Single-process only: this [Mutex] cannot fence a second process, a
     * second ViewModel/store instance in tests, or any out-of-band vault
     * writer. Cross-writer safety rests on the [EndpointStore.saveIfRevision]
     * CAS plus the restart-unique staged alias scheme (a CAS loss leaves the
     * winner's staged live key untouched; the loser only deletes its own
     * per-operation restart-unique staged alias, never a live slot). Staged
     * alias uniqueness itself holds across restarts and processes via the
     * full-UUID 32-hex suffix (see [SetupValidation.newStagedAlias]) plus a
     * bounded mint-time inequality + existence guard; only the metadata
     * CAS remains single-process (documented on EndpointStore). Never present
     * this mutex as multi-writer safety.
     */
    private val saveMutex = Mutex()

    /**
     * Test seam: the most recent save coroutine. Cancellation tests cancel this
     * job to inject [CancellationException] at suspending gates (V1/V2/CAS).
     * Production code never touches it; it is overwritten on every [save].
     */
    internal var lastSaveJobForTest: Job? = null

    /** Test seam for racing an edit between mint success and dialog display. */
    internal var afterMintBeforeDialogForTest: (() -> Unit)? = null

    val gate: StateFlow<EndpointGate> = store.observe()
        .map { config ->
            if (config == null) {
                EndpointGate.NoEndpoint
            } else {
                val okUrl = try {
                    dev.librepocket.provider.validateBaseUrl(config.baseUrl)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
                val hasKey = try {
                    // Ref-driven: the live alias comes from metadata, never from
                    // providerId (staged commits point at a per-operation alias).
                    // Malformed refs fail closed to NoEndpoint without vault I/O.
                    if (!config.apiKeyRef.startsWith("provider_key/")) {
                        false
                    } else {
                        val alias = SetupValidation.aliasFromRef(config.apiKeyRef)
                        if (alias.isBlank()) false else withContext(Dispatchers.IO) { vault().hasKey(alias) }
                    }
                } catch (_: Exception) {
                    false
                }
                if (okUrl && hasKey) EndpointGate.Ready(config) else EndpointGate.NoEndpoint
            }
        }
        .catch { emit(EndpointGate.NoEndpoint) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EndpointGate.Loading)

    fun selectPreset(presetId: String) {
        val preset = ProviderCatalog.preset(presetId) ?: return
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        _form.value = _form.value.copy(
            presetId = presetId,
            modelRefreshRevision = revision,
            baseUrl = preset.baseUrl,
            model = ProviderCatalog.defaultModelFor(presetId),
            errorCode = null,
            testModels = null,
            modelOptions = emptyList(),
            modelsLoading = false,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
        )
    }

    fun onBaseUrlChange(v: String) {
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        _form.value = _form.value.copy(
            baseUrl = v,
            modelRefreshRevision = revision,
            errorCode = null,
            testModels = null,
            modelOptions = emptyList(),
            modelsLoading = false,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
        )
    }

    fun onModelChange(v: String) {
        _form.value = _form.value.copy(model = v, errorCode = null, testModels = null)
    }

    fun onApiKeyChange(v: String) {
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        _form.value = _form.value.copy(
            apiKey = v,
            modelRefreshRevision = revision,
            errorCode = null,
            testModels = null,
            modelOptions = emptyList(),
            modelsLoading = false,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
        )
    }

    fun toggleShowKey() {
        _form.value = _form.value.copy(showKey = !_form.value.showKey)
    }

    /**
     * Prefill the form from the live endpoint for "edit" entries. The key
     * always stays blank (dots display "already set"; retype to rotate).
     * Unknown presetIds fall back to custom with the stored URL editable.
     */
    fun prefillForEdit(config: EndpointConfig) {
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        val preset = ProviderCatalog.preset(config.presetId)
        if (preset == null || config.presetId == ProviderCatalog.CUSTOM_ID) {
            _form.value = SetupUiState(
                presetId = ProviderCatalog.CUSTOM_ID,
                baseUrl = config.baseUrl,
                model = config.model,
                modelRefreshRevision = revision,
            )
        } else {
            _form.value = SetupUiState(
                presetId = preset.id,
                baseUrl = preset.baseUrl,
                model = config.model.ifBlank { preset.defaultModel },
                modelRefreshRevision = revision,
            )
        }
    }

    fun consumeSaved() {
        _form.value = _form.value.copy(saved = false)
    }

    /** One-shot connectivity check (P1_SPEC listModels); persists nothing. */
    fun testConnection() {
        val cur = _form.value
        if (cur.testing || cur.saving || cur.modelsLoading) return
        val effectiveBaseUrl = effectiveBaseUrlOf(cur)
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        _form.value = cur.copy(testing = true, errorCode = null, testModels = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = providerConfigOf(cur, effectiveBaseUrl)
                val typed = cur.apiKey.trim()
                val keys = KeyProvider { typed.toCharArray() }
                val count = buildProvider(config, keys).listModels().size
                _form.value = _form.value.copy(testing = false, testModels = count)
            } catch (e: ProviderFailure) {
                _form.value = _form.value.copy(testing = false, errorCode = classifyTestError(e))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(testing = false, errorCode = "TEST_FAILED")
            }
        }
    }

    /** Refresh the model candidates: live listing first, snapshot fills the rest. */
    fun refreshModels() {
        val cur = _form.value
        if (cur.modelsLoading || cur.testing || cur.saving) return
        val effectiveBaseUrl = effectiveBaseUrlOf(cur)
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val generation = modelRefreshGeneration.incrementAndGet()
        _form.value = cur.copy(
            modelsLoading = true,
            errorCode = null,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
            modelRefreshRevision = generation,
        )
        modelRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = providerConfigOf(cur, effectiveBaseUrl)
                val typed = cur.apiKey.trim()
                val keys = KeyProvider { typed.toCharArray() }
                val live = try {
                    buildProvider(config, keys).listModels()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                val fetcher = fetchDirectory ?: { url: String ->
                    ModelDirectory.fetchBody(directoryClient, url)
                }
                val outcome = ModelsDevSnapshot.fetchSnapshotOutcome(fetcher = fetcher)
                val options = ProviderCatalog.listedModels(cur.presetId, live, outcome.snapshot)
                _form.update { current ->
                    if (generation == modelRefreshGeneration.get() && current.modelRefreshRevision == generation) {
                        current.copy(
                            modelsLoading = false,
                            modelOptions = options,
                            modelDirectoryStatus = when (outcome.source) {
                                ModelsDevSnapshot.SnapshotSource.REMOTE_DIRECTORY -> ModelDirectoryStatus.Remote
                                ModelsDevSnapshot.SnapshotSource.BUNDLED -> ModelDirectoryStatus.Bundled(
                                    checkNotNull(outcome.fallbackReason),
                                )
                            },
                        )
                    } else {
                        current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Validation passed but mapping failed (unknown preset race):
                // keep the free-text model, clear the spinner.
                _form.update { current ->
                    if (generation == modelRefreshGeneration.get() && current.modelRefreshRevision == generation) {
                        current.copy(modelsLoading = false)
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun save() {
        saveWithConsent(null)
    }

    /**
     * Shared save path. [consent] is non-null only for the dialog confirm tap,
     * which carries the one-shot consent minted by the earlier ASK evaluation.
     * Every side effect below (staged [KeyVault.putKey] + [EndpointStore.saveIfRevision])
     * is preceded by a fresh policy evaluation; DENY always wins, even when a
     * consent was minted earlier. Stage2 adds a form fence ([formRevision]) and
     * a second post-write policy re-evaluation. The whole [executeKeyWrite]
     * critical section runs under [saveMutex] (same-VM save/logout
     * serialization). The vault commit uses a per-operation restart-unique
     * staged alias: the new key is written aside under
     * `<providerId>#staged-<uuid32>` (see [SetupValidation.newStagedAlias];
     * the suffix is a full 32-hex UUID per attempt, never a process-local
     * counter, so a relaunch cannot re-mint an alias that collides with an
     * already committed live alias — residual ~2^-122 risk is additionally
     * fenced by a bounded mint-time inequality + existence guard that fails
     * closed), the metadata CAS swings `apiKeyRef` to
     * it, and only then is the previous live alias retired best-effort. A CAS
     * loss or any earlier abort deletes only the attempt's own staged alias
     * (known per-operation name, safe) and never touches a live slot, never
     * restores a predecessor, and never reads a live key. No abort path
     * performs an unconditional live delete.
     */
    private fun saveWithConsent(consent: KeyWriteConsent?) {
        val entryRevision = formRevision.get()
        if (consent != null && consent.formRevision != entryRevision) {
            // Stale consent replay (identity/key edited between mint and confirm
            // tap, or cross-tap reuse): fail closed without touching saving or
            // evaluating policy.
            _form.value = _form.value.copy(errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        val cur = _form.value
        if (cur.saving || cur.testing || cur.modelsLoading) return
        val effectiveBaseUrl = effectiveBaseUrlOf(cur)
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        _form.value = cur.copy(saving = true, errorCode = null)
        lastSaveJobForTest = viewModelScope.launch(Dispatchers.IO) {
            // Same-VM serialization with privileged logout teardown. Held only
            // for the duration of one save attempt: dialog-pending paths return
            // (releasing the mutex) and the confirm tap re-acquires it.
            // saveMutex.withLock releases on cancellation (verified). The
            // try-catch sits OUTSIDE withLock so a cancel while QUEUED for the
            // mutex (lambda never entered) still unsticks `saving`; an
            // inner-only catch would leave saving=true in that window.
            try {
                saveMutex.withLock {
                    executeKeyWrite(cur, effectiveBaseUrl, consent, entryRevision)
                }
            } catch (e: CancellationException) {
                // Any cancel path (VM alive) must unstick saving and must
                // NOT mask the cancel with POLICY_DENIED. StateFlow copy is
                // non-suspending and safe under cancellation. Committed
                // cancels are already swallowed to saved=true inside
                // executeKeyWrite; only uncommitted/unknown cancels reach
                // here, including withLock-wait cancels. Awaiting callers
                // observe saving==false + rethrow.
                // (Dedicated SAVE_CANCELLED code is a UX follow-up; at
                // minimum never leave saving=true.)
                _form.value = _form.value.copy(saving = false)
                throw e
            } catch (e: IllegalArgumentException) {
                _form.value = _form.value.copy(saving = false, errorCode = e.message ?: "SETUP_SAVE_FAILED")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _form.value = _form.value.copy(saving = false, errorCode = "SETUP_SAVE_FAILED")
            }
        }
    }

    private suspend fun executeKeyWrite(
        form: SetupUiState,
        effectiveBaseUrl: String,
        consent: KeyWriteConsent?,
        entryRevision: Long,
    ) {
        val providerId = try {
            SetupValidation.deriveProviderId(form.presetId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 1. Fresh evaluation immediately before ANY side effect (TOCTOU guard).
        // evaluateFresh failure fails closed to DENY.
        val v1 = freshVerdictOrDeny("key.write", providerId)
        if (v1 == Verdict.DENY) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 2. Form fence before mint: an edit racing the first evaluation aborts.
        if (entryRevision != formRevision.get()) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 3. Consent path re-validates against the LATEST form (not the entry
        // snapshot) so a key/URL edit racing the confirm tap is observed.
        if (consent != null) {
            val live = _form.value
            val liveBaseUrl = try {
                effectiveBaseUrlOf(live)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (!consentMatches(consent, live, liveBaseUrl)) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
        } else if (v1 == Verdict.ASK) {
            // key.write defaults to ASK: one explicit confirmation per key.
            if (!mintKeyWriteConsentGuarded(providerId, effectiveBaseUrl, entryRevision)) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // Test seam: pause between mint success and dialog display so tests
            // can race an edit into the mint->dialog window (Gated skeleton).
            afterMintBeforeDialogForTest?.invoke()
            // Mint->dialog window fence: an edit racing the mint must not
            // revive the dialog. Re-verify before showing; on mismatch discard
            // the just-minted consent and fail closed (no deleteKey).
            if (entryRevision != formRevision.get()) {
                takePendingConsent()
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            _form.value = _form.value.copy(saving = false, confirmKeyWrite = true)
            // Post-show fence: an edit racing the dialog set must not leave a
            // stale dialog. invalidateKeyWriteConsent() hides a shown dialog,
            // but an edit landing between the pre-show check and this set would
            // otherwise revive it; re-verify and hide again.
            if (entryRevision != formRevision.get()) {
                takePendingConsent()
                _form.value = _form.value.copy(saving = false, confirmKeyWrite = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            return
        }
        // Snapshot the revision AND the previous live alias in a single emission
        // for the trailing CAS and the post-commit retire. Consent path reuses
        // consent.configRevision but still requires the live revision to match
        // it here (single-emission authoritative check). Snapshot-then-fence: a
        // read failure or an edit racing the read fails closed before any vault
        // side effect.
        val snapshot = try {
            store.snapshotWithRevisionStrict()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        val oldConfig = snapshot.first
        val liveRev = snapshot.second
        val expectedRevision: Long? = if (consent != null) {
            if (liveRev != consent.configRevision) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            consent.configRevision
        } else {
            liveRev
        }
        // Previous live alias for post-commit retire (null on fresh start).
        // Malformed refs resolve to null and are never retired.
        val oldAlias: String? = oldConfig?.apiKeyRef
            ?.takeIf { it.startsWith("provider_key/") }
            ?.let { SetupValidation.aliasFromRef(it) }
            ?.takeIf { it.isNotBlank() }
        if (entryRevision != formRevision.get()) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 4. Fence again immediately before the vault write.
        if (entryRevision != formRevision.get()) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 5. Staged vault write (versioned-alias scheme). The new key is written
        // aside under a per-operation restart-unique staged alias and the live
        // slot is NEVER overwritten here, so a concurrent writer's live key
        // cannot be clobbered by our put, and no backup/restore of live key
        // material is needed: there is no live read anywhere on this path,
        // hence no key.read evaluation either (the backup scheme and its gate
        // are gone by construction). The alias comes from the injected
        // [stagedAliasProvider] (default [SetupValidation.newStagedAlias],
        // full-UUID 32-hex suffix, fresh per attempt):
        // a process-local counter is deliberately NOT used because it
        // restarts at zero after a relaunch and would re-mint the previous
        // round's live alias, overwriting it in place (with a later abort or
        // retire then deleting live bytes = loss).
        //
        // Trade-offs, stated honestly:
        // - Between the staged put and the metadata CAS, two keys briefly
        //   coexist at rest (staged + previous live). On abort only the staged
        //   alias is deleted; on success only the previous live alias is
        //   retired. A crash between put and CAS (or between CAS and retire)
        //   orphans a staged (or previous-live) key at rest: without a vault
        //   list API it cannot be enumerated for GC, so it lingers encrypted
        //   until a GC follow-up (list API) lands. At-rest leak is strictly
        //   better than the fixed-alias loss it replaces.
        // - Same-VM saves are fenced by [saveMutex] and metadata races by the
        //   trailing CAS, but out-of-band writers still need single-writer
        //   discipline for endpoint identity: two writers still race on the
        //   single metadata slot (the loser fails closed), they just can no
        //   longer destroy each other's key bytes. Staged alias uniqueness
        //   holds across restarts and processes via the full-UUID 32-hex
        //   suffix (122-bit) plus the bounded collision guard below; only the
        //   DataStore metadata CAS itself is single-process only (documented
        //   on EndpointStore).
        // - [EncryptedPrefsVault.putKey] wipes the array it is given, so the
        //   put input ([keyCopy]) gets a defensive re-wipe here.
        // - Uses the entry snapshot (not live): key/identity edits bump
        //   formRevision and are fenced above, so reaching here means the entry
        //   key is still current. Model may be stale (onModelChange does not
        //   bump per #17 scope) but model is not consent identity and does not
        //   affect key.write fencing; endpointConfigOf below shares this note.
        //
        // Collision guard (Re-review #5 Finding 2): the freshly minted alias
        // is compared against the snapshot [oldAlias] with zero vault I/O;
        // on equality it is redrawn via [stagedAliasProvider], bounded to
        // [MAX_STAGED_MINT_ATTEMPTS] total mints. Persistent collision fails
        // closed with POLICY_DENIED without touching the vault (no put, no
        // delete, no hasKey). A surviving candidate then gets an
        // existence-only `hasKey` check (never `getKey`, never reads content)
        // covering a crash-orphan placeholder still occupying the alias;
        // on existence it is redrawn (re-checking inequality) within the same
        // bound, else it fails closed the same way. Any `hasKey` host failure
        // (except cancellation, rethrown) also fails closed before any put.
        var stagedAlias: String = try {
            stagedAliasProvider(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        var mintAttempts = 1
        while (stagedAlias == oldAlias && mintAttempts < MAX_STAGED_MINT_ATTEMPTS) {
            if (entryRevision != formRevision.get()) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            try {
                stagedAlias = stagedAliasProvider(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            mintAttempts++
        }
        if (stagedAlias == oldAlias) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // Existence-only orphan guard (no content read). Bounded by the same
        // total-mint budget; each redraw re-checks the snapshot inequality
        // synchronously before the next existence probe.
        while (true) {
            if (entryRevision != formRevision.get()) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            val orphanExists: Boolean = try {
                vault().hasKey(stagedAlias)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (!orphanExists) break
            if (mintAttempts >= MAX_STAGED_MINT_ATTEMPTS) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            try {
                stagedAlias = stagedAliasProvider(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            mintAttempts++
            while (stagedAlias == oldAlias && mintAttempts < MAX_STAGED_MINT_ATTEMPTS) {
                if (entryRevision != formRevision.get()) {
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                try {
                    stagedAlias = stagedAliasProvider(providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                mintAttempts++
            }
            if (stagedAlias == oldAlias) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
        }
        val stagedRef = "provider_key/$stagedAlias"
        val keyCopy = form.apiKey.trim().toCharArray()
        // Explicit commit-state for cancellation compensation (P1-1 hardening):
        // - vaultDirty: staged putKey succeeded (own staged bytes exist).
        // - metadataCommitted: saveIfRevision returned true (staged is live).
        // The Cancellation handler below compensates strictly by this state,
        // verified against strict metadata when ambiguous (CAS threw Cancel
        // after possibly committing, including TimeoutCancellation from
        // withTimeout — also a CancellationException, never misjudged into a
        // live rollback).
        var vaultDirty = false
        var metadataCommitted = false
        try {
            try {
                vault().putKey(stagedAlias, keyCopy)
                vaultDirty = true
            } finally {
                keyCopy.fill('\u0000')
            }
        } catch (e: CancellationException) {
            // The staged put raced cancellation: the staged slot may or may not
            // hold our bytes. The alias is ours alone (per-operation
            // restart-unique UUID alias) and was never published in metadata,
            // so a NonCancellable best-effort delete is safe; live slots are
            // never touched here. Bounded: single delete, no retry.
            try {
                withContext(NonCancellable) { vault().deleteKey(stagedAlias) }
            } catch (_: Exception) {
            }
            // VM alive: unstick saving, never mask cancel with POLICY_DENIED.
            _form.value = _form.value.copy(saving = false)
            throw e
        } catch (_: Exception) {
            // The put itself failed: best-effort delete the (possibly partial)
            // staged entry, then abort. Live slots are never touched.
            deleteStagedBestEffort(stagedAlias)
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        try {
            // 6. Second fresh evaluation AFTER the staged write, BEFORE metadata.
            // A policy flip during putKey blocks the metadata write; the
            // just-written staged key is deleted (own alias, safe).
            val v2 = freshVerdictOrDeny("key.write", providerId)
            if (v2 == Verdict.DENY) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 7. Generation fence after the write: an edit racing putKey aborts
            // the metadata write and deletes the just-written staged key.
            if (entryRevision != formRevision.get()) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 8. Consent re-verification against live state (a stale/invalidated
            // consent still blocks even when the second verdict is ALLOW).
            if (consent != null) {
                val live2 = _form.value
                val liveBaseUrl2 = try {
                    effectiveBaseUrlOf(live2)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                if (!consentMatches(consent, live2, liveBaseUrl2)) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
            }
            // 9. Trailing entry fence before the metadata CAS.
            if (entryRevision != formRevision.get()) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 10. ALLOW->ASK flip during putKey (fresh path only): demand an
            // explicit confirmation instead of persisting metadata. The
            // just-written staged key is deleted first, so no orphan is left
            // pending while the dialog is shown; the confirm tap performs a
            // clean staged put of its own.
            if (v2 == Verdict.ASK && consent == null) {
                val live3 = _form.value
                val liveBaseUrl3 = try {
                    effectiveBaseUrlOf(live3)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                if (!mintKeyWriteConsentGuarded(providerId, liveBaseUrl3, entryRevision)) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                // Test seam: same mint->dialog window as the initial-ASK path.
                afterMintBeforeDialogForTest?.invoke()
                // Mint->dialog window fence (ALLOW->ASK flip path): an edit racing
                // the mint must not revive the dialog. Re-verify before showing; on
                // mismatch discard the just-minted consent and fail closed.
                if (entryRevision != formRevision.get()) {
                    takePendingConsent()
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                // Do NOT save metadata and do NOT set saved=true here: delete
                // the staged key, then ask for confirmation.
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, confirmKeyWrite = true)
                // Post-show fence: hide a stale dialog revived by an edit racing
                // the dialog set (see initial-ASK path). The staged key is already
                // deleted, so only the dialog state needs hiding here.
                if (entryRevision != formRevision.get()) {
                    takePendingConsent()
                    _form.value = _form.value.copy(saving = false, confirmKeyWrite = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                return
            }
            // V2==ASK with a valid matching consent proceeds; V2==ALLOW proceeds
            // (an invalidated consent above already blocked).
            // Uses the entry snapshot: model may be stale (onModelChange does not
            // bump formRevision per #17 scope) but model is not consent identity
            // (consent binds providerId/baseUrl only), so staleness is out of scope
            // and intentionally not fenced here. See keyCopy note at step 5.
            // The metadata swings apiKeyRef to OUR staged alias (per-operation
            // restart-unique); the fixed deriveApiKeyRef form is kept only for
            // backward-compatible reads of old metadata and in-memory
            // (non-persisted) mapping.
            val config = endpointConfigOf(form, effectiveBaseUrl, providerId, stagedRef)
            val wrote = try {
                store.saveIfRevision(config, expectedRevision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                deleteStagedBestEffort(stagedAlias)
                throw e
            } catch (_: Exception) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (!wrote) {
                // CAS mismatch: a concurrent winner advanced the revision. Our
                // staged alias is ours alone (per-operation restart-unique UUID)
                // and was never published, so deleting it cannot harm the
                // winner, whose metadata points at its own staged alias. No
                // vault read, no content comparison, no live touch —
                // different-key and same-bytes losers alike cannot corrupt the
                // winner.
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            metadataCommitted = true
            // Post-commit retire of the previous live alias (best-effort, never
            // fails the save). Guarded by inequality with the just-committed
            // staged alias, so the new live key is never deleted here. The
            // guard is sound across restarts because staged aliases are
            // restart-unique (full-UUID 32-hex, 122-bit): the fresh alias
            // collides with the previous live alias only at ~2^-122 per pair,
            // and the mint-time bounded inequality + existence guard above
            // already fails closed on such a collision before any put. Runs in
            // the cancellable context (deliberately outside NonCancellable): if
            // cancellation lands here the previous alias may leak at rest
            // (documented orphan class, strictly better than loss); the new
            // live key always survives. Retire-cancel leak is at-rest only and
            // documented; saveMutex still releases on cancel (verified).
            if (oldAlias != null && oldAlias != stagedAlias) {
                try {
                    vault().deleteKey(oldAlias)
                } catch (e: CancellationException) {
                    // Commit already won: swallow to report success and never
                    // fall through to anything that deletes the new live key.
                    // The old alias may leak at rest (no vault list API for GC).
                } catch (_: Exception) {
                    // Best-effort: the save below stays authoritative.
                }
            }
            _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
        } catch (e: CancellationException) {
            if (!metadataCommitted) {
                // Uncommitted or ambiguous (put-after / V2 / fence / CAS-threw-
                // Cancel): the CAS may have committed and then thrown (including
                // TimeoutCancellation), so verify against strict metadata FIRST
                // in bounded NonCancellable context; unknown store state fails
                // closed to no-touch (at-rest leak, never loss). Bounded: one
                // strict read + at most one delete, no retry; read failure
                // stops cleanup. A winner's staged live key can never equal our
                // per-operation restart-unique alias, so `false` (absent or
                // pointing elsewhere) authorizes deleting ONLY our own staged
                // alias — live is never touched. `true` means the commit won
                // despite the cancel: own staged is now live, must NOT delete;
                // treat as success (swallow, report saved=true READY).
                val livePointsAtStaged: Boolean? = try {
                    withContext(NonCancellable) { store.snapshotStrict()?.apiKeyRef == stagedRef }
                } catch (_: CancellationException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (livePointsAtStaged == true) {
                    _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
                    return
                }
                if (livePointsAtStaged == false && vaultDirty) {
                    try {
                        withContext(NonCancellable) { vault().deleteKey(stagedAlias) }
                    } catch (_: Exception) {
                    }
                }
                // Confirmed-uncommitted (staged cleaned) or unknown (no-touch
                // leak): unstick saving, never POLICY_DENIED-mask the cancel.
                _form.value = _form.value.copy(saving = false)
            } else {
                // Committed (CAS true, then retire/success-mark cancelled):
                // staged is live — NEVER delete. Re-confirm the commit is still
                // in place (defensive: logout could have raced); only then
                // swallow and report success. Read failure -> no-touch leak,
                // unstick saving, rethrow (never rollback the new live key).
                val stillCommitted: Boolean? = try {
                    withContext(NonCancellable) { store.snapshotStrict()?.apiKeyRef == stagedRef }
                } catch (_: CancellationException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (stillCommitted == true) {
                    _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
                    return
                }
                _form.value = _form.value.copy(saving = false)
            }
            throw e
        }
    }

    /**
     * Best-effort delete of an attempt-owned staged alias.
     *
     * Safe by construction: staged aliases are per-operation restart-unique
     * (full-UUID 32-hex suffix, see [SetupValidation.newStagedAlias]) and never published
     * before the metadata CAS, so no winner or live reader can reference them
     * — including across process restarts, where a counter-based alias would
     * be re-minted and collide with a committed live key. Vault failures are
     * swallowed so the original abort reason stays the reported error.
     * [CancellationException] is always rethrown, never swallowed
     * (cancellation cleanup uses the NonCancellable guarded paths in
     * [executeKeyWrite] instead).
     */
    private suspend fun deleteStagedBestEffort(stagedAlias: String) {
        try {
            vault().deleteKey(stagedAlias)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort: the abort error below stays authoritative.
        }
    }

    /** Second tap of the key.write confirmation dialog: single-use, snapshot-bound. */
    fun confirmKeyWriteSave() {
        if (!_form.value.confirmKeyWrite) return
        // Consume-once: this tap burns the consent even if the write below is refused.
        val consent = takePendingConsent()
        _form.value = _form.value.copy(confirmKeyWrite = false)
        if (consent == null) {
            // Stale or replayed confirm (identity edited mid-dialog, double tap
            // racing the save, or programmatic misuse): fail closed, touch nothing.
            _form.value = _form.value.copy(errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        saveWithConsent(consent)
    }

    fun dismissKeyWriteConfirm() {
        // Abandon the minted consent: a later save() must re-evaluate fresh.
        takePendingConsent()
        _form.value = _form.value.copy(confirmKeyWrite = false)
    }

    /** Discard any unconsumed consent and hide the confirm dialog. */
    private fun invalidateKeyWriteConsent() {
        takePendingConsent()
        if (_form.value.confirmKeyWrite) {
            _form.value = _form.value.copy(confirmKeyWrite = false)
        }
    }

    /** Consume-once take: the returned consent (if any) can never be used again. */
    private fun takePendingConsent(): KeyWriteConsent? =
        synchronized(consentLock) {
            val consent = pendingConsent
            pendingConsent = null
            consent
        }

    /** Fail-closed fresh evaluation: any read failure (except cancellation) is DENY. */
    private suspend fun freshVerdictOrDeny(action: String, resource: String): Verdict {
        try {
            return policy.evaluateFresh(action, resource).verdict
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Verdict.DENY
        }
    }

    /** Mint guarded by the form fence with TOCTOU-safe lock handoff. Read failures deny. */
    private suspend fun mintKeyWriteConsentGuarded(providerId: String, baseUrl: String, entryRevision: Long): Boolean {
        try {
            if (entryRevision != formRevision.get()) return false
            val rev: Long? = try {
                store.currentRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            if (entryRevision != formRevision.get()) return false
            val consent = KeyWriteConsent(
                action = "key.write",
                resource = providerId,
                providerId = providerId,
                baseUrl = baseUrl,
                configRevision = rev,
                formRevision = entryRevision,
                nonce = consentNonces.incrementAndGet(),
            )
            synchronized(consentLock) {
                // Re-verify inside the lock: an edit racing the revision read
                // must not mint a consent bound to a stale generation.
                if (entryRevision != formRevision.get()) return false
                pendingConsent = consent
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun consentMatches(
        consent: KeyWriteConsent,
        liveForm: SetupUiState,
        liveBaseUrl: String,
    ): Boolean {
        try {
            if (consent.action != "key.write") return false
            if (consent.resource != consent.providerId) return false
            val liveProviderId = try {
                SetupValidation.deriveProviderId(liveForm.presetId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            if (consent.providerId != liveProviderId) return false
            if (consent.baseUrl != liveBaseUrl) return false
            // Front formRevision clamp: no edit before the revision read.
            if (consent.formRevision != formRevision.get()) return false
            val currentRev: Long? = try {
                // Raw nullable revision: fresh null != dirty 0L; failures deny.
                store.currentRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            // Back clamp: no edit during the revision read.
            if (consent.formRevision != formRevision.get()) return false
            return consent.configRevision == currentRev
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Logout: delete the live vault key addressed by metadata, then clear
     * metadata (order matters).
     *
     * Privileged teardown: unconditional delete+clear, serialized against
     * in-flight saves via [saveMutex] so a save's staged-put/CAS/retire window
     * cannot interleave with teardown in this VM. Cross-process writers are
     * still fenced only by the metadata CAS, not by this mutex (see [saveMutex]).
     *
     * Ref-driven: the deleted alias is resolved from the snapshot's `apiKeyRef`
     * (the staged live alias after a staged commit), never re-derived from
     * `providerId`. Staged aliases from crashed saves (put-but-never-committed,
     * or committed-but-never-retired, or cancelled mid-flight) are unknown here
     * — without a vault list API they cannot be enumerated, so they linger
     * encrypted at rest until a GC follow-up (list API) lands. Documented
     * at-rest leak, strictly better than loss; in-flight aborts and cancels
     * already delete their own staged alias, so the steady state has no residue.
     */
    fun logout(onDone: () -> Unit = {}) {
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        modelRefreshGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            saveMutex.withLock {
                val config = try {
                    store.observe().first()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                try {
                    val alias = config?.apiKeyRef
                        ?.takeIf { it.startsWith("provider_key/") }
                        ?.let { SetupValidation.aliasFromRef(it) }
                        ?.takeIf { it.isNotBlank() }
                    if (alias != null) vault().deleteKey(alias)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                try {
                    store.clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            _form.value = SetupUiState(model = ProviderCatalog.defaultModelFor(ProviderCatalog.OPENAI_ID))
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private fun effectiveBaseUrlOf(form: SetupUiState): String =
        if (form.presetId == ProviderCatalog.CUSTOM_ID) {
            form.baseUrl.trim()
        } else {
            ProviderCatalog.requirePreset(form.presetId).baseUrl
        }

    private fun endpointConfigOf(
        form: SetupUiState,
        effectiveBaseUrl: String,
        providerId: String,
        apiKeyRef: String,
    ): EndpointConfig {
        val preset = ProviderCatalog.requirePreset(form.presetId)
        val protocol = if (form.presetId == ProviderCatalog.CUSTOM_ID) {
            dev.librepocket.provider.ProviderProtocol.CHAT_COMPLETIONS
        } else {
            preset.protocol
        }
        return EndpointConfig(
            providerId = providerId,
            presetId = form.presetId,
            label = preset.label,
            baseUrl = effectiveBaseUrl,
            protocol = protocol,
            model = form.model.trim().ifEmpty { preset.defaultModel },
            apiKeyRef = apiKeyRef,
        )
    }

    private fun providerConfigOf(form: SetupUiState, effectiveBaseUrl: String): ProviderConfig {
        val providerId = SetupValidation.deriveProviderId(form.presetId)
        // In-memory transport mapping only (never persisted): the fixed ref is
        // a placeholder — the key itself travels via the KeyProvider closure.
        return endpointConfigOf(form, effectiveBaseUrl, providerId, SetupValidation.deriveApiKeyRef(providerId))
            .toProviderConfig()
    }

    private fun classifyTestError(e: ProviderFailure): String {
        if (e.code == ProviderFailureCode.TOO_LARGE) return "TEST_TOO_LARGE"
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        if (!e.retryable &&
            ("401" in msg || "403" in msg || "missing_api_key" in msg || "unauthorized" in lower)
        ) {
            return "TEST_UNAUTHORIZED"
        }
        if (e.retryable) return "TEST_RETRYABLE"
        return "TEST_FAILED"
    }

    override fun onCleared() {
        modelRefreshJob?.cancel()
        super.onCleared()
    }
}

/** Production wiring (vault construction stays off the main thread). */
class SetupViewModelFactory(app: Application) : ViewModelProvider.Factory {
    private val store = EndpointStore(app)
    private val policy: PolicyStore = DataStorePolicyStore(app)

    @Volatile
    private var vault: KeyVault? = null
    private val vaultSource = VaultSource {
        vault ?: withContext(Dispatchers.IO) {
            vault ?: EncryptedPrefsVault(app.applicationContext).also { vault = it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SetupViewModel(store, vaultSource, policy) as T
    }
}
