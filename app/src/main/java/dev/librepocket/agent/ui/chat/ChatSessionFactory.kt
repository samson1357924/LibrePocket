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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TRANSCRIPT_CLEANUP_TIMEOUT_MS = 5_000L
private const val TRANSCRIPT_CREATE_TIMEOUT_MS = 5_000L

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

/** Injectable session-construction boundary used by the chat lifecycle tests. */
interface ChatSessionProvider {
    suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        keyIsCurrent: suspend () -> Boolean = { true },
    ): CreatedSession

    suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean = { true },
    ): CreatedSession

    suspend fun storeOrNull(): SessionStore?

    /** Release a result that was built but could not be attached to the live UI. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun discardUnattached(created: CreatedSession, deleteTranscriptRow: Boolean) {
        created.session.close()
    }

    fun modelFor(endpoint: EndpointConfig): String
}

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
) : ChatSessionProvider {
    override suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
        val config = endpoint.toProviderConfig()
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        val model = modelFor(endpoint)
        val credential = captureCredential(endpoint)
        var storeForCleanup: SessionStore? = null
        var createdSessionId: String? = null
        var session: ChatSession? = null
        try {
            requireCurrentBinding(keyIsCurrent)
            val store = sessionStores?.store()
            storeForCleanup = store
            requireCurrentBinding(keyIsCurrent)
            if (store != null) {
                // Keep the bounded insert non-cancellable so a durable row's id
                // is captured for rollback if the parent is cancelled mid-insert.
                withContext(NonCancellable + Dispatchers.IO) {
                    createdSessionId = withTimeout(TRANSCRIPT_CREATE_TIMEOUT_MS) {
                        store.createSession(title.take(30), model)
                    }
                }
            }
            val sessionId = createdSessionId
            requireCurrentBinding(keyIsCurrent)
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
            val createdSession = buildSession(
                config,
                model,
                transcript,
                credential,
                keyIsCurrent,
                clock,
                userTimezone,
                systemZone,
                sessionStart,
            )
            session = createdSession
            requireCurrentBinding(keyIsCurrent)
            return CreatedSession(
                sessionId,
                createdSession,
                endpoint.providerId,
                model,
            )
        } catch (failure: Throwable) {
            try {
                session?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            credential.close()
            val unusedStore = storeForCleanup
            val unusedSessionId = createdSessionId
            if (unusedStore != null && unusedSessionId != null) {
                try {
                    deleteUnusedTranscriptSession(unusedStore, unusedSessionId)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    /**
     * Binds a live session to an existing transcript session (resume, no re-create).
     *
     * Phase 3 Target (cross-restart recovery; NOT implemented in Phase 2 —
     * resume hydrate stays untouched): after loading [meta] and before
     * attaching, backfill any turn the previous process left RUNNING (a
     * dangling partial row with no terminal record for its runId) with an
     * explicit INTERRUPTED marker row. This needs the Phase-3 persisted
     * partial/status bits (see TranscriptEvent / TranscriptEventEntity KDoc);
     * without a schema change no stored row can reliably distinguish a
     * crashed partial from a completed assistant row, so Phase 2 replays rows
     * as-is and records interruption only in memory
     * (TurnController.interruptedTranscriptRunIds).
     */
    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
        val config = endpoint.toProviderConfig()
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        requireCurrentBinding(keyIsCurrent)
        val store = requireNotNull(sessionStores?.store()) { "UNKNOWN_SESSION" }
        requireCurrentBinding(keyIsCurrent)
        // Retain the meta so resume reuses the original createdAt (never re-stamps now).
        // getSession failure → null → UNKNOWN_SESSION (never masks unknown with now).
        val meta = try {
            store.getSession(sessionId)
        } catch (_: Exception) {
            null
        }
        require(meta != null) { "UNKNOWN_SESSION" }
        requireCurrentBinding(keyIsCurrent)
        val model = modelFor(endpoint)
        val credential = captureCredential(endpoint)
        var session: ChatSession? = null
        try {
            requireCurrentBinding(keyIsCurrent)
            val sessionStart = meta.createdAt.toInstantOrNull()
            val openedSession = buildSession(
                config,
                model,
                SessionTranscriptSink(store, sessionId),
                credential,
                keyIsCurrent,
                clock,
                userTimezone,
                systemZone,
                sessionStart,
            )
            session = openedSession
            requireCurrentBinding(keyIsCurrent)
            return CreatedSession(
                sessionId,
                openedSession,
                endpoint.providerId,
                model,
            )
        } catch (failure: Throwable) {
            try {
                session?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            credential.close()
            throw failure
        }
    }

    override suspend fun discardUnattached(
        created: CreatedSession,
        deleteTranscriptRow: Boolean,
    ) {
        var failure: Throwable? = null
        try {
            created.session.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }

        val sessionId = created.sessionId
        val storeSource = sessionStores
        if (deleteTranscriptRow && sessionId != null && storeSource != null) {
            try {
                // Cancellation must not strand an already-created row; keep this
                // cleanup bounded so teardown cannot wait forever on Room.
                withContext(NonCancellable + Dispatchers.IO) {
                    withTimeout(TRANSCRIPT_CLEANUP_TIMEOUT_MS) {
                        storeSource.store().deleteSession(sessionId)
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val originalFailure = failure
                if (originalFailure == null) failure = cleanupFailure else originalFailure.addSuppressed(cleanupFailure)
            }
        }
        failure?.let { throw it }
    }

    override suspend fun storeOrNull(): SessionStore? = try {
        sessionStores?.store()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Effective model id for an endpoint (user text, preset default, or "default"). */
    override fun modelFor(endpoint: EndpointConfig): String = endpoint.model.ifBlank {
        if (endpoint.presetId == ProviderCatalog.CUSTOM_ID) {
            TurnController.DEFAULT_MODEL
        } else {
            ProviderCatalog.defaultModelFor(endpoint.presetId)
        }
    }

    private suspend fun deleteUnusedTranscriptSession(store: SessionStore, sessionId: String) {
        // A generation can be cancelled after Room inserted the row but before
        // provider/session construction completes. Roll back that new row with
        // a bounded non-cancellable cleanup, then rethrow the original failure.
        withContext(NonCancellable + Dispatchers.IO) {
            withTimeout(TRANSCRIPT_CLEANUP_TIMEOUT_MS) {
                store.deleteSession(sessionId)
            }
        }
    }

    private suspend fun requireCurrentBinding(keyIsCurrent: suspend () -> Boolean) {
        if (!keyIsCurrent()) throw CancellationException("ENDPOINT_BINDING_STALE")
    }

    private suspend fun captureCredential(endpoint: EndpointConfig): SessionCredential {
        val alias = endpoint.apiKeyRef.removePrefix("provider_key/")
        require(alias.isNotBlank() && alias != endpoint.apiKeyRef) { "PROVIDER_KEY_REF_MALFORMED" }
        val vault = vaultSource.vault()
        val key = vault.getKey(alias) ?: throw IllegalStateException("ENDPOINT_KEY_MISSING")
        return SessionCredential(endpoint.apiKeyRef, key)
    }

    private fun buildSession(
        config: ProviderConfig,
        model: String,
        transcript: TranscriptSink,
        credential: SessionCredential,
        keyIsCurrent: suspend () -> Boolean,
        clock: Clock = this.clock,
        userTimezone: String? = this.userTimezone,
        systemZone: () -> ZoneId = this.systemZone,
        sessionStart: Instant? = null,
    ): ChatSession {
        val keys = KeyProvider { ref ->
            if (keyIsCurrent()) credential.copyFor(ref) else null
        }
        val provider = buildProvider(config, keys)
        val session = ChatSessionImpl(
            provider,
            policy,
            transcript,
            model = model,
            clock = clock,
            userTimezone = userTimezone,
            sessionStart = sessionStart,
            systemZone = systemZone,
        )
        return CredentialBoundChatSession(session, credential)
    }

    /** The live provider never rereads a mutable alias after this snapshot. */
    private class SessionCredential(
        private val apiKeyRef: String,
        private val secret: CharArray,
    ) {
        private var closed = false

        @Synchronized
        fun copyFor(requestedRef: String): CharArray? =
            if (closed || requestedRef != apiKeyRef) null else secret.copyOf()

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            secret.fill('\u0000')
        }
    }

    private class CredentialBoundChatSession(
        private val delegate: ChatSession,
        private val credential: SessionCredential,
    ) : ChatSession by delegate {
        override fun close() {
            try {
                delegate.close()
            } finally {
                credential.close()
            }
        }
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
