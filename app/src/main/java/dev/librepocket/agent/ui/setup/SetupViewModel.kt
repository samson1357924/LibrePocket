package dev.librepocket.agent.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.KeyVault
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ProviderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * [KeyVault.hasKey] only (dots, never echo).
 */
class SetupViewModel(
    application: Application,
    private val store: EndpointStore = EndpointStore(application),
) : AndroidViewModel(application) {

    // Vault construction (MasterKey + EncryptedSharedPreferences) does Keystore
    // I/O; keep it off the main thread.
    private val vaultDeferred = viewModelScope.async(Dispatchers.IO) {
        EncryptedPrefsVault(application)
    }
    private suspend fun vault(): KeyVault = vaultDeferred.await()

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
        )
    }

    fun onBaseUrlChange(v: String) {
        _form.value = _form.value.copy(baseUrl = v, errorCode = null)
    }

    fun onModelChange(v: String) {
        _form.value = _form.value.copy(model = v, errorCode = null)
    }

    fun onApiKeyChange(v: String) {
        _form.value = _form.value.copy(apiKey = v, errorCode = null)
    }

    fun toggleShowKey() {
        _form.value = _form.value.copy(showKey = !_form.value.showKey)
    }

    fun consumeSaved() {
        _form.value = _form.value.copy(saved = false)
    }

    fun save() {
        val cur = _form.value
        if (cur.saving) return
        val effectiveBaseUrl = if (cur.presetId == ProviderCatalog.CUSTOM_ID) {
            cur.baseUrl.trim()
        } else {
            ProviderCatalog.requirePreset(cur.presetId).baseUrl
        }
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        _form.value = cur.copy(saving = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val providerId = SetupValidation.deriveProviderId(cur.presetId)
                val preset = ProviderCatalog.requirePreset(cur.presetId)
                val protocol: ProviderProtocol = if (cur.presetId == ProviderCatalog.CUSTOM_ID) {
                    ProviderProtocol.CHAT_COMPLETIONS
                } else {
                    preset.protocol
                }
                val keyCopy = cur.apiKey.trim().toCharArray()
                try {
                    vault().putKey(providerId, keyCopy)
                } finally {
                    keyCopy.fill('\u0000')
                }
                store.save(
                    EndpointConfig(
                        providerId = providerId,
                        presetId = cur.presetId,
                        label = preset.label,
                        baseUrl = effectiveBaseUrl,
                        protocol = protocol,
                        model = cur.model.trim().ifEmpty { preset.defaultModel },
                        apiKeyRef = SetupValidation.deriveApiKeyRef(providerId),
                    ),
                )
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
}
