package dev.librepocket.agent.ui.setup

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.toProviderConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.KeyVault
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.DefaultProviderFactory
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gate state for startup navigation. Loading and NoEndpoint are distinct (no FOUC). */
sealed interface EndpointGate {
    data object Loading : EndpointGate
    data object NoEndpoint : EndpointGate
    data class Ready(val config: EndpointConfig) : EndpointGate
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
    private val buildProvider: (ProviderConfig, KeyProvider) -> LlmProvider =
        { cfg, keys -> DefaultProviderFactory(keys).create(cfg) },
) : ViewModel() {

    private suspend fun vault(): KeyVault = vaultSource.vault()

    private val _form = MutableStateFlow(SetupUiState(model = ProviderCatalog.defaultModelFor(ProviderCatalog.OPENAI_ID)))
    val form: StateFlow<SetupUiState> = _form

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
        _form.value = _form.value.copy(
            presetId = presetId,
            baseUrl = preset.baseUrl,
            model = ProviderCatalog.defaultModelFor(presetId),
            errorCode = null,
            testModels = null,
        )
    }

    fun onBaseUrlChange(v: String) {
        _form.value = _form.value.copy(baseUrl = v, errorCode = null, testModels = null)
    }

    fun onModelChange(v: String) {
        _form.value = _form.value.copy(model = v, errorCode = null, testModels = null)
    }

    fun onApiKeyChange(v: String) {
        _form.value = _form.value.copy(apiKey = v, errorCode = null, testModels = null)
    }

    fun toggleShowKey() {
        _form.value = _form.value.copy(showKey = !_form.value.showKey)
    }

    fun consumeSaved() {
        _form.value = _form.value.copy(saved = false)
    }

    /** One-shot connectivity check (P1_SPEC listModels); persists nothing. */
    fun testConnection() {
        val cur = _form.value
        if (cur.testing || cur.saving) return
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

    fun save() {
        val cur = _form.value
        if (cur.saving || cur.testing) return
        val effectiveBaseUrl = effectiveBaseUrlOf(cur)
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        _form.value = cur.copy(saving = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val providerId = SetupValidation.deriveProviderId(cur.presetId)
                val keyCopy = cur.apiKey.trim().toCharArray()
                try {
                    vault().putKey(providerId, keyCopy)
                } finally {
                    keyCopy.fill('\u0000')
                }
                store.save(endpointConfigOf(cur, effectiveBaseUrl, providerId))
                _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
            } catch (e: IllegalArgumentException) {
                _form.value = _form.value.copy(saving = false, errorCode = e.message ?: "SETUP_SAVE_FAILED")
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "SETUP_SAVE_FAILED")
            }
        }
    }

    /** Logout: delete the vault key, then clear metadata (order matters). */
    fun logout(onDone: () -> Unit = {}) {
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
}

/** Production wiring (vault construction stays off the main thread). */
class SetupViewModelFactory(app: Application) : ViewModelProvider.Factory {
    private val store = EndpointStore(app)

    @Volatile
    private var vault: KeyVault? = null
    private val vaultSource = VaultSource {
        vault ?: withContext(Dispatchers.IO) {
            vault ?: EncryptedPrefsVault(app.applicationContext).also { vault = it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SetupViewModel(store, vaultSource) as T
    }
}
