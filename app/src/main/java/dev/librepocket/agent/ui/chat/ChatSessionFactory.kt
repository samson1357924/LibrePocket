package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatSessionImpl
import dev.librepocket.chat.NoOpTranscriptSink
import dev.librepocket.chat.TurnController
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.DataStorePolicyStore
import dev.librepocket.policy.PolicyStore
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.DefaultProviderFactory
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lazily provides the product vault (Keystore I/O must stay off the main thread). */
fun interface VaultSource {
    suspend fun vault(): KeyVault
}

/**
 * Builds a [ChatSession] from the persisted endpoint. Transcript stays NoOp
 * until G3 wires the Room sink; policy/transcript/session-list follow there.
 */
class ChatSessionFactory(
    private val policy: PolicyStore,
    private val vaultSource: VaultSource,
    private val buildProvider: (ProviderConfig, KeyProvider) -> LlmProvider =
        { cfg, keys -> DefaultProviderFactory(keys).create(cfg) },
) {
    suspend fun create(endpoint: EndpointConfig): ChatSession {
        val config = endpoint.toProviderConfig()
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        val vault = vaultSource.vault()
        val keys = KeyProvider { ref -> vault.getKey(ref.removePrefix("provider_key/")) }
        val provider = buildProvider(config, keys)
        val model = endpoint.model.ifBlank {
            if (endpoint.presetId == ProviderCatalog.CUSTOM_ID) {
                TurnController.DEFAULT_MODEL
            } else {
                ProviderCatalog.defaultModelFor(endpoint.presetId)
            }
        }
        return ChatSessionImpl(provider, policy, NoOpTranscriptSink(), model = model)
    }
}

/** Production wiring for [ChatViewModel] (shares the process-singleton endpoint store). */
class ChatViewModelFactory(app: Application) : ViewModelProvider.Factory {
    private val store = EndpointStore(app)
    private val policy: PolicyStore = DataStorePolicyStore(app)

    @Volatile
    private var vault: KeyVault? = null
    private val vaultSource = VaultSource {
        vault ?: withContext(Dispatchers.IO) {
            vault ?: EncryptedPrefsVault(app).also { vault = it }
        }
    }
    private val sessions = ChatSessionFactory(policy, vaultSource)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(store, sessions) as T
    }
}
