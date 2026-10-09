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
    val configRevision: Long,
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

    // One-shot key.write consent (ASK path). Main-thread edits and the IO save
    // coroutine touch it from different threads; all access goes through
    // [consentLock]. Never persisted, never leaves this ViewModel.
    private val consentLock = Any()
    private var pendingConsent: KeyWriteConsent? = null
    private val consentNonces = AtomicLong(0L)

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
     * Every side effect below ([KeyVault.putKey] + [EndpointStore.save]) is
     * preceded by a fresh policy evaluation; DENY always wins, even when a
     * consent was minted earlier.
     */
    private fun saveWithConsent(consent: KeyWriteConsent?) {
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
            try {
                executeKeyWrite(cur, effectiveBaseUrl, consent)
            } catch (e: IllegalArgumentException) {
                _form.value = _form.value.copy(saving = false, errorCode = e.message ?: "SETUP_SAVE_FAILED")
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "SETUP_SAVE_FAILED")
            }
        }
    }

    private suspend fun executeKeyWrite(
        form: SetupUiState,
        effectiveBaseUrl: String,
        consent: KeyWriteConsent?,
    ) {
        val providerId = SetupValidation.deriveProviderId(form.presetId)
        // Fresh evaluation immediately before ANY side effect (TOCTOU guard).
        val verdict = policy.evaluateFresh("key.write", providerId).verdict
        if (verdict == Verdict.DENY) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        if (consent != null) {
            // The carried consent covers exactly one ASK. It must still match
            // the endpoint snapshot; a changed identity fails closed.
            if (!consentMatchesSnapshot(consent, form, effectiveBaseUrl)) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
        } else if (verdict == Verdict.ASK) {
            // key.write defaults to ASK: one explicit confirmation per key.
            mintKeyWriteConsent(providerId, effectiveBaseUrl)
            _form.value = _form.value.copy(saving = false, confirmKeyWrite = true)
            return
        }
        val keyCopy = form.apiKey.trim().toCharArray()
        try {
            vault().putKey(providerId, keyCopy)
        } finally {
            keyCopy.fill('\u0000')
        }
        store.save(endpointConfigOf(form, effectiveBaseUrl, providerId))
        _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
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

    /** Mint a consent bound to the endpoint identity + stored revision observed now. */
    private suspend fun mintKeyWriteConsent(providerId: String, baseUrl: String) {
        val consent = KeyWriteConsent(
            action = "key.write",
            resource = providerId,
            providerId = providerId,
            baseUrl = baseUrl,
            configRevision = readConfigRevision(),
            nonce = consentNonces.incrementAndGet(),
        )
        synchronized(consentLock) { pendingConsent = consent }
    }

    private suspend fun consentMatchesSnapshot(
        consent: KeyWriteConsent,
        form: SetupUiState,
        effectiveBaseUrl: String,
    ): Boolean {
        if (consent.action != "key.write") return false
        if (consent.resource != consent.providerId) return false
        if (consent.providerId != SetupValidation.deriveProviderId(form.presetId)) return false
        if (consent.baseUrl != effectiveBaseUrl) return false
        return consent.configRevision == readConfigRevision()
    }

    private suspend fun readConfigRevision(): Long =
        try {
            store.observe().first()?.configRevision ?: 0L
        } catch (_: Exception) {
            -1L
        }

    /** Logout: delete the vault key, then clear metadata (order matters). */
    fun logout(onDone: () -> Unit = {}) {
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        modelRefreshGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val config = try {
                store.observe().first()
            } catch (_: Exception) {
                null
            }
            try {
                if (config != null) vault().deleteKey(config.providerId)
            } catch (_: Exception) {
            }
            try {
                store.clear()
            } catch (_: Exception) {
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
