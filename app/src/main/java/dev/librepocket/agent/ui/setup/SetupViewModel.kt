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

    /**
     * Serializes the save critical section ([executeKeyWrite] vault backup,
     * put, re-verification, metadata CAS) against privileged [logout]
     * teardown (vault delete + metadata clear) within this VM.
     *
     * Single-process only: this [Mutex] cannot fence a second process, a
     * second ViewModel/store instance in tests, or any out-of-band vault
     * writer. Cross-writer safety rests on the [EndpointStore.saveIfRevision]
     * CAS plus the revision-gated [rollbackVaultIfOurs] (CAS loss leaves a
     * same-provider save winner untouched; other aborts compare-before-
     * restore/delete, never an unconditional delete). Never present this
     * mutex as multi-writer safety.
     */
    private val saveMutex = Mutex()

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
                    withContext(Dispatchers.IO) { vault().hasKey(config.providerId) }
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
     * Every side effect below ([KeyVault.putKey] + [EndpointStore.saveIfRevision])
     * is preceded by a fresh policy evaluation; DENY always wins, even when a
     * consent was minted earlier. Stage2 adds a form fence ([formRevision]) and
     * a second post-write policy re-evaluation. Stage3 runs the whole
     * [executeKeyWrite] critical section under [saveMutex] (same-VM save/logout
     * serialization) and reconciles the vault on post-put aborts via the
     * revision-gated [rollbackVaultIfOurs]: a pre-existing key is restored,
     * a self-written orphan with no predecessor is deleted, and a foreign key
     * observed in place is never touched. A trailing-CAS loss (`saveIfRevision`
     * returns false) proves a concurrent winner advanced the revision, so a
     * same-provider save winner is left untouched with no vault delete/restore
     * (same-content bytes are indistinguishable by content alone); only a
     * clear/unrelated winner falls through to a gated orphan delete. No abort
     * path performs an unconditional vault delete.
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
        viewModelScope.launch(Dispatchers.IO) {
            // Same-VM serialization with privileged logout teardown. Held only
            // for the duration of one save attempt: dialog-pending paths return
            // (releasing the mutex) and the confirm tap re-acquires it.
            saveMutex.withLock {
                try {
                    executeKeyWrite(cur, effectiveBaseUrl, consent, entryRevision)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    _form.value = _form.value.copy(saving = false, errorCode = e.message ?: "SETUP_SAVE_FAILED")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _form.value = _form.value.copy(saving = false, errorCode = "SETUP_SAVE_FAILED")
                }
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
        // Fresh ALLOW direct-write path: snapshot the raw revision now for the
        // trailing CAS. Consent path reuses consent.configRevision instead.
        // Snapshot-then-fence: a revision-read failure or an edit racing the
        // read fails closed before any vault side effect.
        val expectedRevision: Long? = if (consent != null) {
            consent.configRevision
        } else {
            val rev0 = try {
                store.currentRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (entryRevision != formRevision.get()) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            rev0
        }
        // 4. Fence again immediately before the vault write.
        if (entryRevision != formRevision.get()) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 5. Vault write with a pre-put backup (stage3, fixed-alias short-term
        // scheme). Uses the entry snapshot (not live): key/identity edits
        // bump formRevision and are fenced above, so reaching here means the
        // entry key is still current. Model may be stale (onModelChange does not
        // bump per #17 scope) but model is not consent identity and does not
        // affect key.write fencing; endpointConfigOf below shares this note.
        //
        // Trade-offs, stated honestly:
        // - The backup extends the previous key's in-memory lifetime to the end
        //   of this function; it is wiped in the `finally` below on every path
        //   (success, abort, exception), never logged, and never placed in
        //   SavedState or any persisted structure.
        // - [EncryptedPrefsVault.putKey] wipes the array it is given, so the
        //   comparison copy ([newKeyCopy]) is taken BEFORE the put; the put
        //   input ([keyCopy]) is wiped by the vault with a defensive re-wipe
        //   here.
        // - Lost-update remains possible: an out-of-band write landing between
        //   the backup read and our put is silently overwritten (unrecoverable
        //   under a fixed alias), and one landing between the rollback re-read
        //   and the rollback write can be clobbered. Same-VM saves are fenced
        //   by [saveMutex] and metadata races by the trailing CAS, but
        //   out-of-band writers require single-writer discipline by
        //   documentation. The long-term fix is a staged/versioned vault alias
        //   (write new key aside, CAS metadata, then retire the old alias),
        //   tracked as a follow-up, not claimed here.
        val keyCopy = form.apiKey.trim().toCharArray()
        val newKeyCopy = keyCopy.copyOf()
        // Pre-existing key material for ownership-checked restore; null when
        // this provider had no key. A read failure aborts fail-closed BEFORE
        // any put: with unknown vault state we must not overwrite.
        val backup: CharArray?
        val hadKey: Boolean
        try {
            backup = vault().getKey(providerId)
            hadKey = backup != null
        } catch (e: CancellationException) {
            keyCopy.fill('\u0000')
            newKeyCopy.fill('\u0000')
            throw e
        } catch (_: Exception) {
            keyCopy.fill('\u0000')
            newKeyCopy.fill('\u0000')
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        try {
            try {
                try {
                    vault().putKey(providerId, keyCopy)
                } finally {
                    keyCopy.fill('\u0000')
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The put itself failed: the vault may still hold the old key
                // (write rejected before storing) or, in a pathological
                // backend, our bytes. The ownership check inside
                // [rollbackVaultIfOurs] distinguishes the cases; on unknown
                // vault state it leaves everything untouched.
                rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 6. Second fresh evaluation AFTER the vault write, BEFORE metadata.
            // A policy flip during putKey blocks the metadata write; the
            // just-written key is reconciled by [rollbackVaultIfOurs] (restore
            // the predecessor, or delete our own orphan when there was none).
            val v2 = freshVerdictOrDeny("key.write", providerId)
            if (v2 == Verdict.DENY) {
                rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 7. Generation fence after the write: an edit racing putKey aborts
            // the metadata write and reconciles the just-written key.
            if (entryRevision != formRevision.get()) {
                rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
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
                    rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                if (!consentMatches(consent, live2, liveBaseUrl2)) {
                    rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
            }
            // 9. Trailing entry fence before the metadata CAS.
            if (entryRevision != formRevision.get()) {
                rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 10. ALLOW->ASK flip during putKey (fresh path only): demand an
            // explicit confirmation instead of persisting metadata. The
            // just-written key is restored to its pre-put state first, so no
            // orphan is left pending while the dialog is shown; the confirm tap
            // performs a clean backup+put of its own.
            if (v2 == Verdict.ASK && consent == null) {
                val live3 = _form.value
                val liveBaseUrl3 = try {
                    effectiveBaseUrlOf(live3)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                if (!mintKeyWriteConsentGuarded(providerId, liveBaseUrl3, entryRevision)) {
                    rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
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
                    rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                // Do NOT save metadata and do NOT set saved=true here: restore
                // the pre-put vault state, then ask for confirmation.
                rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                _form.value = _form.value.copy(saving = false, confirmKeyWrite = true)
                // Post-show fence: hide a stale dialog revived by an edit racing
                // the dialog set (see initial-ASK path). The vault is already
                // restored, so only the dialog state needs hiding here.
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
            val config = endpointConfigOf(form, effectiveBaseUrl, providerId)
            val wrote = try {
                store.saveIfRevision(config, expectedRevision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                throw e
            } catch (_: Exception) {
                rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (!wrote) {
                // CAS mismatch: a concurrent winner advanced the revision. A
                // same-content winner key is indistinguishable by content alone,
                // so never blindly delete/restore here. A same-provider save
                // winner may already hold our bytes in the vault: touch nothing
                // and fail closed. Only a clear (or unrelated-provider) winner
                // leaves our slot without a winner key, in which case the gated
                // rollback below cleans our own orphan (never a restore).
                // Store read failures fail closed to no-touch (preserve a
                // possible winner).
                val winnerProtectsSlot = try {
                    val meta = store.observe().first()
                    meta != null && meta.providerId == providerId
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    true
                }
                if (!winnerProtectsSlot) {
                    rollbackVaultIfOurs(providerId, newKeyCopy, backup, hadKey, expectedRevision)
                }
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
        } finally {
            // Success path needs no rollback; every secret copy is wiped here.
            newKeyCopy.fill('\u0000')
            backup?.fill('\u0000')
        }
    }

    /**
     * Ownership-checked vault reconciliation for post-put aborts (stage3).
     *
     * Re-reads the live vault entry and compares it against [newKeyCopy] (the
     * pre-put copy of the key this attempt wrote; the put input itself was
     * wiped by [KeyVault.putKey]):
     * - live entry still equals what we wrote (still ours): restore [backup]
     *   when [hadKey] (a predecessor existed), otherwise [KeyVault.deleteKey]
     *   our own orphan;
     * - live entry differs (an out-of-band writer already overwrote us), is
     *   absent (someone deleted), or cannot be read (unknown vault state):
     *   touch nothing, fail-closed. In particular this function NEVER issues an
     *   unconditional `deleteKey(providerId)`, NEVER overwrites a foreign key,
     *   and NEVER logs key material.
     *
     * P1 same-bytes hardening: content alone cannot distinguish a loser orphan
     * from a winner same-content key, so every write is gated by
     * [expectedRevision] (the [EndpointStore.currentRevision] snapshot taken
     * before our put):
     * - `currentRevision() == expectedRevision` (no winner): reconcile as above
     *   (fresh start deletes the own orphan, rotation restores the predecessor);
     * - revision advanced + metadata present for our [providerId] (save winner,
     *   possibly same bytes): return BEFORE any vault read/write, leaving the
     *   winner untouched. Same-content `orphan == winner` leaving is correct;
     * - revision advanced + metadata absent or for a different provider (clear
     *   winner or unrelated single-endpoint overwrite): no winner key occupies
     *   our slot, so fall through to an ownership-checked DELETE (never a
     *   restore — restoring would resurrect a key with no active endpoint).
     * Revision/metadata read failures fail closed (no vault touch).
     * CAS-false callers additionally skip this function entirely when a
     * same-provider winner holds metadata (see the trailing-CAS branch); this
     * gate is the defense-in-depth for V2 DENY / fence / exception aborts that
     * have not yet proven a winner via CAS.
     *
     * Short-term fixed-alias scheme: the vault has a single slot per
     * providerId, so restore is `putKey(backup)` and cleanup is a checked
     * delete. Residual risks (no multi-writer safety is claimed):
     * - an out-of-band write between our backup read and our put is already
     *   overwritten before this runs (unrecoverable under a fixed alias);
     * - an out-of-band write between the re-read below and the restore/delete
     *   can still be clobbered (check-then-act race inside this function).
     * Same-VM saves are serialized by [saveMutex]; anything out-of-band needs
     * single-writer discipline by documentation. The durable fix is a
     * staged/versioned vault alias (write aside, CAS metadata, retire old),
     * tracked as a follow-up issue.
     *
     * Best-effort: vault failures are swallowed so the original abort reason
     * (policy DENY, fence mismatch, CAS loss) stays the reported error.
     * [CancellationException] is always rethrown, never swallowed.
     *
     * @param backup the pre-put key copy; NOT consumed here (the caller wipes
     * it in `finally`). A defensive copy is passed to [KeyVault.putKey] because
     * put wipes its input.
     * @param expectedRevision the [EndpointStore.currentRevision] snapshot taken
     * before our put (`null` = fresh). Compared with strict `==` so fresh `null`
     * never conflates with dirty `0L`.
     */
    private suspend fun rollbackVaultIfOurs(
        providerId: String,
        newKeyCopy: CharArray,
        backup: CharArray?,
        hadKey: Boolean,
        expectedRevision: Long?,
    ) {
        try {
            val liveRev: Long? = try {
                store.currentRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unknown store state: leave everything untouched (fail-closed).
                return
            }
            var clearWinner = false
            if (liveRev != expectedRevision) {
                val meta = try {
                    store.observe().first()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Unknown store state: leave everything untouched (fail-closed).
                    return
                }
                if (meta != null && meta.providerId == providerId) {
                    // Save winner for our slot may hold a same-content key:
                    // leave it untouched without even reading the vault.
                    return
                }
                // Clear winner (no metadata) or unrelated single-endpoint
                // overwrite: our slot holds no winner key, so an
                // ownership-checked delete below is safe. Never restore here.
                clearWinner = true
            }
            val current = try {
                vault().getKey(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unknown vault state: leave everything untouched (fail-closed).
                return
            }
            if (current == null) {
                // Absent: nothing of ours to restore or clean (our put failed
                // before storing, or someone already deleted). Never recreate.
                return
            }
            try {
                if (!current.contentEquals(newKeyCopy)) {
                    // Foreign key already in place: never overwrite, never delete.
                    return
                }
                // Still ours: restore the predecessor or clean our own orphan.
                if (hadKey) {
                    if (clearWinner) {
                        // No active endpoint for our provider: delete the own
                        // orphan instead of resurrecting the predecessor with
                        // no metadata (which would leak a key).
                        try {
                            vault().deleteKey(providerId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Best-effort: the abort error below stays authoritative.
                        }
                        return
                    }
                    val prev = backup ?: return
                    try {
                        vault().putKey(providerId, prev.copyOf())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Best-effort: the abort error below stays authoritative.
                    }
                } else {
                    try {
                        vault().deleteKey(providerId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Best-effort: the abort error below stays authoritative.
                    }
                }
            } finally {
                current.fill('\u0000')
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort reconciliation: never mask the abort reason.
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
     * Logout: delete the vault key, then clear metadata (order matters).
     *
     * Privileged teardown: unconditional delete+clear, serialized against
     * in-flight saves via [saveMutex] so a save's backup/put/rollback window
     * cannot interleave with teardown in this VM. Cross-process writers are
     * still fenced only by the metadata CAS and ownership checks, not by this
     * mutex (see [saveMutex]).
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
                    if (config != null) vault().deleteKey(config.providerId)
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

    private fun endpointConfigOf(form: SetupUiState, effectiveBaseUrl: String, providerId: String): EndpointConfig {
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
            apiKeyRef = SetupValidation.deriveApiKeyRef(providerId),
        )
    }

    private fun providerConfigOf(form: SetupUiState, effectiveBaseUrl: String): ProviderConfig {
        val providerId = SetupValidation.deriveProviderId(form.presetId)
        return endpointConfigOf(form, effectiveBaseUrl, providerId).toProviderConfig()
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
