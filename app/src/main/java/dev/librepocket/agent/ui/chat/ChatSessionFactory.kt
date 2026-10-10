package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatSessionImpl
import dev.librepocket.chat.HistoryWindowCap
import dev.librepocket.chat.NoOpTranscriptSink
import dev.librepocket.chat.TranscriptSink
import dev.librepocket.chat.TurnController
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.DataStorePolicyStore
import dev.librepocket.policy.PolicyStore
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.DefaultProviderFactory
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
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
private const val HISTORY_PAGE = 200

/**
 * Stored-events bound mirrored from the display replay: model context never
 * hydrates more rows than the UI would show.
 */
private const val HISTORY_LOAD_CAP = 2000

/**
 * Stage E: per-message char bound for model-context hydration. The write path
 * already caps a stored row at 100k chars ([RoomSessionStore.MAX_TEXT_CHARS]);
 * a single such row (~25k tokens at ~4 chars/token) must never reach the
 * provider unbounded. 20k chars (~5k tokens) keeps one row a bounded fraction
 * of any realistic context window while staying far above normal turns.
 * Over-long rows are truncated with a visible marker (fail-closed, never
 * silent). A measured token/byte budget is still TODO (see
 * [HistoryWindowCap]); until it lands this char bound is the interim cap.
 */
internal const val MODEL_HISTORY_MAX_CHARS_PER_MESSAGE = 20_000

/** Stage E: truncation marker appended to an over-long hydrated row. */
internal const val HISTORY_TRUNCATION_MARKER = "…[history truncated]"

/**
 * Stage E: raw-row window for the INTERRUPTED backfill scan. Dangling turns
 * are always recent (the first open after a kill marks them), so a bounded
 * newest-first window replaces the old unbounded full load (OOM-safe).
 * Best-effort: an ancient unmarked row outside the window stays for the next
 * open, same as any failed backfill before.
 */
private const val BACKFILL_SCAN_LIMIT = 2000

/**
 * Stage E: tail-loaded model prefix. [droppedCount] is the exact number of
 * older qualifying rows omitted by the cap — the observable counterpart to
 * truncation (never silent; surfaced via [CreatedSession.historyDroppedCount]
 * and [TurnController.droppedHistoryCount]).
 */
internal data class LoadedModelHistory(
    val messages: List<ChatMessage>,
    val droppedCount: Int,
)

/** Marker kind for RUNNING → INTERRUPTED backfill: hidden from chat replay, kept in export. */
internal const val INTERRUPTED_MARKER_KIND = "system"

/** Explicit kill marker bound to the stranded runId (idempotent: exact text skips re-marking). */
internal fun interruptedMarkerText(runId: String): String = "turn $runId interrupted"

private fun isLegacyFailureText(runId: String, text: String): Boolean =
    text.startsWith("turn $runId failed")

/**
 * Logical-family id for one transcript row (Stage C): attempt-bound rows
 * carry `parentRunId=logicalTurnId`, everything else (user/retry/legacy
 * rows) has null and falls back to its own `runId`. Pre-C rows all have
 * null, so their family is exactly the old single-id semantics.
 */
internal fun familyOf(event: TranscriptEvent): String = event.parentRunId ?: event.runId

/**
 * User-owned logical families with no terminal record: no assistant row of
 * any kind in the same family (completed, cancelled-partial, failed-with-
 * reason, or retried partial — Stage C persists every retryable failure's
 * fragment before its retry notice) and no legacy failure / already-marked
 * system row for that family. First-seen order; empty when nothing was
 * stranded. Pure (no I/O) so the backfill rule is directly unit-testable.
 *
 * Stage C (implemented): grouping is by logical family
 * (`parentRunId ?: runId`), not by raw `runId`. A retry→success turn leaves
 * `user(L) + assistant(A1 parent L, partial retried) + retry(L) +
 * assistant(A2 parent L, success)`: family L owns assistant rows, so it is
 * completed and never gains an INTERRUPTED marker. A terminal failure family
 * likewise owns its failed assistant row. Retry/tool/steer rows are never
 * terminals. Legacy single-id rows (parent null) keep the old behavior.
 */
internal fun findDanglingRunIds(events: List<TranscriptEvent>): List<String> {
    val terminalFamilies = HashSet<String>()
    val userFamilies = LinkedHashSet<String>()
    for (e in events) {
        val family = familyOf(e)
        when (e.kind) {
            "assistant" -> terminalFamilies.add(family)
            "system" -> {
                if (e.text == interruptedMarkerText(e.runId) || isLegacyFailureText(e.runId, e.text)) {
                    terminalFamilies.add(family)
                }
            }
            "user" -> userFamilies.add(family)
            else -> Unit
        }
    }
    return userFamilies.filter { it !in terminalFamilies }
}

/**
 * Stored resume-provenance provider segment (Stage D): `SessionMeta.model`
 * is persisted as `"providerId/modelId"` (see `create`), so everything
 * before the first `/` is the origin providerId. Returns null for legacy
 * rows that predate provenance (bare model id, no `/`).
 *
 * The first-`/` split is load bearing: model ids themselves may contain `/`
 * (e.g. `"preset:openrouter/openrouter/auto"`), while providerIds
 * (`"preset:<presetId>"`) never do.
 */
internal fun storedProviderOf(storedModel: String): String? =
    if ("/" in storedModel) storedModel.substringBefore("/") else null

/**
 * Cross-provider resume gate (Stage D): true when the stored history may be
 * hydrated into the current endpoint's model context.
 *
 * - Provenance present and provider differs (including an empty provider
 *   segment) → false: fail closed, the old history is never forwarded to the
 *   new provider.
 * - Same provider, different model → true: the credential and the recipient
 *   are unchanged (KeyVault keys off providerId), so no new party receives
 *   the history; this also matches the live model-switch behavior, which
 *   keeps the transcript visible across models of one provider.
 * - Provenance absent (legacy bare model id, no `/`) → true (compat): rows
 *   written before provenance cannot be attributed to any provider, and
 *   withholding them would drop resume context for every pre-existing
 *   session. Every session created after provenance carries the qualified
 *   form, so the gate is effective going forward.
 *
 * SessionMeta records no historical baseUrl/origin, so only providerId can
 * be compared here. Same-providerId endpoint URL changes stay inside one
 * key trust domain (the vault alias is the providerId); recording the origin
 * URL for a stricter check is a schema change, deliberately out of scope.
 */
internal fun isSameProviderOrigin(storedModel: String, currentProviderId: String): Boolean {
    val stored = storedProviderOf(storedModel)
    if (stored == null) return true
    return stored == currentProviderId
}

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
    /**
     * Stage D: true when resume crossed providers and the stored model prefix
     * was withheld (the live session starts with an empty model context).
     * The local display replay is unaffected; callers surface a visible
     * notice instead of resuming silently blank-context.
     */
    val historyWithheld: Boolean = false,
    /**
     * Stage E: exact number of older history rows omitted by the tail cap.
     * 0 when everything fit. Observable truncation: callers surface
     * "已省略N則" instead of resuming silently short-context.
     */
    val historyDroppedCount: Int = 0,
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
                        // Stage D resume provenance: persist the origin as
                        // "providerId/modelId" so open() can refuse to forward
                        // another provider's history to this endpoint.
                        store.createSession(title.take(30), endpoint.providerId + "/" + model)
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
     * Phase 3 (implemented) cross-restart recovery: after loading [meta] and
     * before attaching, [backfillInterrupted] marks any turn the previous
     * process left RUNNING (a logical-turn family with no assistant terminal
     * for its family id) with an explicit `system` INTERRUPTED marker row, and the
     * restored user/assistant prefix (partial rows excluded from model
     * context, mirroring the live `!isPartial` request filter) is hydrated
     * into the new controller so the first request after resume already sees
     * prior context. The marker kind is `system`, which the chat replay
     * already hides, so it never pollutes normal history; it stays visible in
     * export for debugging.
     *
     * Stage D (implemented) cross-provider fail-closed: the prefix is only
     * hydrated when [isSameProviderOrigin] attributes the stored rows to the
     * current endpoint's provider. A different provider resumes with an empty
     * model context ([CreatedSession.historyWithheld] = true) so the old
     * provider's history is never forwarded to the new one; the local display
     * replay and the INTERRUPTED backfill above stay provider-agnostic.
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
        backfillInterrupted(store, sessionId)
        // Stage D: cross-provider resume hydrates nothing. The local
        // INTERRUPTED backfill above still runs (provider-agnostic transcript
        // bookkeeping), but the stored rows only enter the new provider's
        // model context when their provenance matches this endpoint.
        val sameOrigin = isSameProviderOrigin(meta.model, endpoint.providerId)
        // Stage E: tail load (newest kept) with an exact dropped count; the
        // cap also travels as MaxMessages so TurnController.droppedHistoryCount
        // observes the same truncation (defense in depth: pre-trimmed prefix
        // makes the controller trim a no-op).
        val loaded = if (sameOrigin) loadModelHistory(store, sessionId) else LoadedModelHistory(emptyList(), 0)
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
                loaded.messages,
                HistoryWindowCap.MaxMessages(HISTORY_LOAD_CAP),
            )
            session = openedSession
            requireCurrentBinding(keyIsCurrent)
            return CreatedSession(
                sessionId,
                openedSession,
                endpoint.providerId,
                model,
                historyWithheld = !sameOrigin,
                historyDroppedCount = loaded.droppedCount,
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
        history: List<ChatMessage> = emptyList(),
        historyCap: HistoryWindowCap = HistoryWindowCap.Unbounded,
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
            initialHistory = history,
            historyCap = historyCap,
        )
        return CredentialBoundChatSession(session, credential)
    }

    /**
     * Cross-restart RUNNING → INTERRUPTED backfill: every user-owned logical
     * family with no assistant row in the same family (completed,
     * cancelled-partial, failed-with-reason, or Stage C retried partial) and
     * no legacy failure / already-marked system row is a turn the previous
     * process killed before any terminal record, so it gets one explicit
     * `system` marker row bound to the same family id.
     *
     * Stage C (implemented): retry→success and retry→terminal-failure
     * families already own attempt assistant rows (bound via
     * `parentRunId`), so they are completed and never gain a marker — this
     * is the Finding #3 fix. Deliberately NOT marked: intentional-cancel
     * partials already own an assistant row (`isPartial=1`), so they replay
     * as partial and never gain a marker; completed and failed turns
     * likewise own theirs. Prune cannot cause false positives (it drops the
     * oldest rows first, and an assistant row always has a larger seq than
     * its user row, so prune can orphan assistants but never users).
     * Re-running is idempotent: a marked family owns its marker row and
     * [findDanglingRunIds] skips it next time.
     *
     * Best-effort (never blocks resume): marker writes go through the same
     * guarded path as every other transcript write. A failed backfill just
     * leaves the run dangling for the next open to retry.
     */
    private suspend fun backfillInterrupted(store: SessionStore, sessionId: String) {
        try {
            // Stage E: bounded newest-first window instead of the old unbounded
            // full load (OOM-safe). Dangling turns are recent by construction:
            // the first open after a kill marks them, later opens are no-ops.
            val tail = store.loadTailEvents(sessionId, BACKFILL_SCAN_LIMIT)
            val dangling = findDanglingRunIds(tail)
            for (runId in dangling) {
                try {
                    store.appendEvent(
                        TranscriptEvent(
                            sessionId = sessionId,
                            runId = runId,
                            kind = INTERRUPTED_MARKER_KIND,
                            text = interruptedMarkerText(runId),
                            createdAt = clock.millis(),
                        ),
                    )
                } catch (_: Exception) {
                    // Best effort per marker; the next open retries.
                }
            }
        } catch (_: Exception) {
            // Best effort overall; resume proceeds without markers.
        }
    }

    /**
     * Restored model-context prefix, Stage E tail semantics: the newest
     * qualifying rows (user/assistant, non-partial) up to [HISTORY_LOAD_CAP],
     * oldest-first, via backward paging (memory O(cap), never O(session);
     * sparse seqs safe). Partial rows are excluded from model context (they
     * replay in the UI flagged, but must never read as completed answers —
     * mirroring the live `!isPartial` request filter); `system` markers stay
     * export/debug-only via the same filter.
     *
     * Truncation is observable: [LoadedModelHistory.droppedCount] carries the
     * exact omitted-row total (filtered COUNT query, O(1) memory). A giant
     * single row is truncated to [MODEL_HISTORY_MAX_CHARS_PER_MESSAGE] with a
     * visible marker — fail-closed, never a silent unbounded payload (a
     * measured token budget is still TODO; the char bound is interim).
     *
     * Unlike the best-effort backfill, a history-read failure propagates:
     * resuming blind (without the context the model needs) fails closed
     * through the caller's UNKNOWN/NO_ENDPOINT path instead.
     */
    internal suspend fun loadModelHistory(store: SessionStore, sessionId: String): LoadedModelHistory {
        val keptNewestFirst = ArrayList<ChatMessage>(HISTORY_LOAD_CAP)
        var beforeSeq = Long.MAX_VALUE
        outer@ while (keptNewestFirst.size < HISTORY_LOAD_CAP) {
            val page = store.loadEventsBefore(sessionId, beforeSeq, HISTORY_PAGE)
            if (page.isEmpty()) break
            for (event in page) {
                if ((event.kind == "user" || event.kind == "assistant") && !event.isPartial) {
                    if (keptNewestFirst.size >= HISTORY_LOAD_CAP) break@outer
                    keptNewestFirst.add(ChatMessage(role = event.kind, text = cappedHistoryText(event.text)))
                }
            }
            // Pages are newest-first with distinct seqs; the next window ends
            // strictly below this page's minimum. A non-advancing store would
            // spin forever, so fail closed instead.
            val pageMin = page.minOf { it.seq }
            if (pageMin >= beforeSeq) break
            beforeSeq = pageMin
            if (page.size < HISTORY_PAGE) break
        }
        val total = store.countHistoryEvents(sessionId, includePartial = false)
        val dropped = (total - keptNewestFirst.size).coerceAtLeast(0)
        return LoadedModelHistory(keptNewestFirst.asReversed(), dropped)
    }

    /**
     * Stage E: fail-closed single-row bound. Rows longer than
     * [MODEL_HISTORY_MAX_CHARS_PER_MESSAGE] are cut with a visible marker so
     * the omission is observable in the payload itself, never silent.
     */
    internal fun cappedHistoryText(text: String): String {
        if (text.length <= MODEL_HISTORY_MAX_CHARS_PER_MESSAGE) return text
        return text.take(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE) + HISTORY_TRUNCATION_MARKER
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
