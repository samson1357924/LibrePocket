package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatSessionImpl
import dev.librepocket.chat.NoOpTranscriptSink
import dev.librepocket.chat.TranscriptSink
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
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lazily provides the product vault (Keystore I/O must stay off the main thread). */
fun interface VaultSource {
    suspend fun vault(): KeyVault
}

/** Lazily provides the transcript store (Room open must stay off the main thread). */
fun interface SessionStoreSource {
    suspend fun store(): SessionStore
}

/** Process-singleton Room database (one instance per file; never open twice). */
object SessionDbHolder {
    @Volatile
    private var db: LibrePocketDb? = null

    fun get(app: Application): LibrePocketDb =
        db ?: synchronized(this) {
            db ?: LibrePocketDb.open(app.applicationContext).also { db = it }
        }
}

/** A live chat session bound to its persisted transcript session (null when storeless). */
data class CreatedSession(
    val sessionId: String?,
    val session: ChatSession,
    val endpointId: String,
    val model: String,
)

/** Session creation epoch millis → Instant; invalid (<=0 or out-of-range) omits. */
private fun Long.toInstantOrNull(): Instant? =
    if (this > 0) runCatching { Instant.ofEpochMilli(this) }.getOrNull() else null

/**
 * Builds a [ChatSession] from the persisted endpoint.
 *
 * With a [SessionStoreSource], each created session gets its own transcript
 * session (title = first message, first 30 chars) and a [SessionTranscriptSink];
 * without one (tests), the transcript stays NoOp. Policy/transcript/session-list
 * follow-ups (steer UI, export) land in later phases.
 */
class ChatSessionFactory(
    private val policy: PolicyStore,
    private val vaultSource: VaultSource,
    private val buildProvider: (ProviderConfig, KeyProvider) -> LlmProvider =
        { cfg, keys -> DefaultProviderFactory(keys).create(cfg) },
    private val sessionStores: SessionStoreSource? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val userTimezone: String? = null,
    private val systemZone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun create(endpoint: EndpointConfig, title: String): CreatedSession {
        val config = endpoint.toProviderConfig()
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        val store = sessionStores?.store()
        val model = modelFor(endpoint)
        val sessionId = store?.createSession(title.take(30), model)
        val transcript: TranscriptSink =
            if (store != null && sessionId != null) {
                SessionTranscriptSink(store, sessionId)
            } else {
                NoOpTranscriptSink()
            }
        // Room SessionMeta.createdAt is the source of truth for `Session started`.
        // Storeless/sessionId null/unknown/invalid/exception → null (omit, never fake now).
        val sessionStart: Instant? = try {
            if (store != null && sessionId != null) {
                store.getSession(sessionId)?.let { it.createdAt.toInstantOrNull() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        return CreatedSession(
            sessionId,
            buildSession(endpoint, config, transcript, clock, userTimezone, systemZone, sessionStart),
            endpoint.providerId,
            model,
        )
    }

    /** Binds a live session to an existing transcript session (resume, no re-create). */
    suspend fun open(endpoint: EndpointConfig, sessionId: String): CreatedSession {
        val config = endpoint.toProviderConfig()
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        val store = sessionStores?.store()
        // Retain the meta so resume reuses the original createdAt (never re-stamps now).
        // getSession failure → null → UNKNOWN_SESSION (never masks unknown with now).
        val meta = try {
            store?.getSession(sessionId)
        } catch (_: Exception) {
            null
        }
        require(store != null && meta != null) { "UNKNOWN_SESSION" }
        val model = modelFor(endpoint)
        val sessionStart = meta.createdAt.toInstantOrNull()
        return CreatedSession(
            sessionId,
            buildSession(endpoint, config, SessionTranscriptSink(store, sessionId), clock, userTimezone, systemZone, sessionStart),
            endpoint.providerId,
            model,
        )
    }

    suspend fun storeOrNull(): SessionStore? = try {
        sessionStores?.store()
    } catch (_: Exception) {
        null
    }

    /** Effective model id for an endpoint (user text, preset default, or "default"). */
    fun modelFor(endpoint: EndpointConfig): String = endpoint.model.ifBlank {
        if (endpoint.presetId == ProviderCatalog.CUSTOM_ID) {
            TurnController.DEFAULT_MODEL
        } else {
            ProviderCatalog.defaultModelFor(endpoint.presetId)
        }
    }

    private suspend fun buildSession(
        endpoint: EndpointConfig,
        config: ProviderConfig,
        transcript: TranscriptSink,
        clock: Clock = this.clock,
        userTimezone: String? = this.userTimezone,
        systemZone: () -> ZoneId = this.systemZone,
        sessionStart: Instant? = null,
    ): ChatSession {
        val vault = vaultSource.vault()
        val keys = KeyProvider { ref -> vault.getKey(ref.removePrefix("provider_key/")) }
        val provider = buildProvider(config, keys)
        return ChatSessionImpl(
            provider,
            policy,
            transcript,
            model = modelFor(endpoint),
            clock = clock,
            userTimezone = userTimezone,
            sessionStart = sessionStart,
            systemZone = systemZone,
        )
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
    private val sessionStores = SessionStoreSource {
        RoomSessionStore(SessionDbHolder.get(app))
    }
    private val sessions = ChatSessionFactory(policy, vaultSource, sessionStores = sessionStores)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(store, sessions, policy) as T
    }
}
