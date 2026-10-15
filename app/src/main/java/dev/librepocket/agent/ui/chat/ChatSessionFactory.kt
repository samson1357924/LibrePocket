package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.SetupValidation
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
import dev.librepocket.policy.Verdict
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
 * Snapshot/history row budgets (centralizes the caps introduced by Stage E /
 * R6-5; new behavior relative to main — single home for the
 * three related caps). Dual budget for one durable fork-snapshot prefix,
 * newest-first: `min(1999 rows, 2M UTF-16 chars)` — [SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP]
 * rows and [SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS] chars
 * ([dev.librepocket.session.TranscriptEvent.text] `String.length` units, the
 * same unit as every other bound here). Marker + copy never exceeds
 * [SnapshotBudget.HISTORY_LOAD_CAP] (one slot reserved for the head-anchored
 * `model switched` marker, plus a second slot for the `snapshot incomplete`
 * marker when the copy is incomplete — R28-1 clamps the carried prefix to
 * 1998 rows in that case), which equals
 * [dev.librepocket.session.PrunePolicy.maxEventsPerSession], so a full
 * snapshot would otherwise lose its head marker at the next prune sweep.
 * 2M chars is ~1/10 of [RoomSessionStore.EXPORT_MAX_BYTES] nominal (chars
 * are UTF-16 code units vs UTF-8 bytes, so the ratio is approximate), ~20x
 * [TurnController.MAX_REQUEST_CHARS] and ~100 full
 * [MODEL_HISTORY_MAX_CHARS_PER_MESSAGE] rows, covering ~50-100 normal turns
 * so ordinary sessions never trip it. Without the char cap a full 1999-row
 * prefix of max-size rows ([RoomSessionStore.MAX_TEXT_CHARS] per-row bound)
 * materializes ~200M chars (~400MB UTF-16, plus a Redactor copy) in one read
 * and one store batch; an over-budget prefix is abandoned whole (zero rows +
 * incomplete marker, never a truncated half prefix — see
 * [snapshotSwitchHistory]).
 */
internal object SnapshotBudget {
    internal const val HISTORY_LOAD_CAP = 2000
    internal const val SWITCH_SNAPSHOT_COPY_CAP = HISTORY_LOAD_CAP - 1
    internal const val SNAPSHOT_MAX_TOTAL_CHARS = 2_000_000
}

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
 * Bounded best-effort: only rows inside the window are ever scanned — an
 * ancient unmarked row outside the window is never marked (no retry can
 * reach it while it stays outside); a failed scan inside the window leaves
 * the run dangling and a later open re-scans the same window, still
 * best-effort, never guaranteed.
 *
 * R9-3: internal (was private) so the ViewModel's deferred-backfill
 * promotion probe scans the same window the backfill marks — promote holds
 * exactly when a gated backfill would no-op.
 */
internal const val BACKFILL_SCAN_LIMIT = 2000

/**
 * Stage E: tail-loaded model prefix. [droppedCount] is the exact number of
 * older qualifying rows omitted by the cap — the observable counterpart to
 * truncation (never silent; surfaced via [CreatedSession.historyDroppedCount]
 * and [TurnController.droppedHistoryCount]). [reasoningIncomplete] is true
 * when the `reasoning`-marker scan may have missed older markers (Phase 4
 * fail-closed: exactly `droppedCount > 0`); callers must treat kept
 * assistant flags as suspect (see [loadModelHistory]).
 */
internal data class LoadedModelHistory(
    val messages: List<ChatMessage>,
    val droppedCount: Int,
    val reasoningIncomplete: Boolean = false,
)

/** Marker kind for RUNNING → INTERRUPTED backfill: hidden from chat replay, kept in export. */
internal const val INTERRUPTED_MARKER_KIND = "system"

/** Explicit kill marker bound to the stranded runId (idempotent: exact text skips re-marking). */
internal fun interruptedMarkerText(runId: String): String = "turn $runId interrupted"

/**
 * Stage F: explicit cancel mark bound to the logical family (idempotent:
 * exact text skips re-marking). Written by
 * [dev.librepocket.chat.TurnController] when a cancel lands on an
 * already-terminalized attempt (retry backoff gap) instead of a second
 * assistant row for the same attempt. Same exact-text pattern as the
 * INTERRUPTED marker: hidden from the chat replay (system kind), visible in
 * export, terminal for [findDanglingRunIds].
 */
internal fun cancelledMarkerText(runId: String): String = "turn $runId cancelled"

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
 * R26-1 family-aware head trim (pure, no I/O): drops leading user-less
 * assistant families so kept never starts with an orphan assistant. The kept
 * window (oldest-first) must start at a user boundary: while its first
 * user/assistant row is an assistant, the whole logical family
 * ([familyOf]) is removed (its user was cut by prune/tail-cap; marker-first
 * implies user-less as the user row is older than its marker). Single-pass
 * convergent (R27-2): the loop breaks when an iteration removes nothing
 * (`filtered.size == kept.size`); every non-breaking iteration strictly
 * shrinks `kept`, so termination needs no cap and a crafted ledger with any
 * N head orphan families converges in one pass (production cuts split at
 * most one family).
 * Crafted runId-reuse over-drop (disclosed, reuse pin is Target): rows sharing the head family id
 * are removed wherever they sit (production runIds are unique + write-ordered;
 * crafted exceptions over-drop conservatively). Predicate difference vs the DB fixup
 * (P2-1, disclosed): [SessionDao.deleteFamily] matches runId==fid OR parentRunId==fid
 * while this helper matches familyOf==fid — they diverge on a crafted row with runId==fid
 * but a different non-null parentRunId (production never takes that shape). Callers decide when to apply:
 * model history only with truncation/prune evidence (dropped>0 or suspect) to
 * preserve the R24-2/R25 precise flags; snapshots unconditionally (row-cap drops
 * are silent legacy, family drops stay silent too — the same convergent
 * helper runs here, so snapshots benefit identically).
 */
internal fun dropHeadOrphanAssistantFamilies(events: List<TranscriptEvent>): List<TranscriptEvent> {
    if (events.isEmpty()) return events
    var kept: List<TranscriptEvent> = events
    while (true) {
        val head = kept.firstOrNull { it.kind == "user" || it.kind == "assistant" } ?: break
        if (head.kind == "user") break
        val family = familyOf(head)
        if (family.isBlank()) break
        val filtered = kept.filterNot { familyOf(it) == family }
        if (filtered.size == kept.size) break
        kept = filtered
        if (kept.isEmpty()) break
    }
    return kept
}

/**
 * User-owned logical families with no terminal record: no final assistant
 * row in the same family and no cancel/interrupted/legacy-failure system mark
 * for that family. First-seen order; empty when nothing was stranded. Pure
 * (no I/O) so the backfill rule is directly unit-testable.
 *
 * Stage C (implemented): grouping is by logical family
 * (`parentRunId ?: runId`), not by raw `runId`. A retry→success turn leaves
 * `user(L) + assistant(A1 parent L, partial retried) + retry(L) +
 * assistant(A2 parent L, success)`: family L owns assistant rows, so it is
 * completed and never gains an INTERRUPTED marker. A terminal failure family
 * likewise owns its failed assistant row. Retry/tool/steer rows are never
 * terminals. Legacy single-id rows (parent null) keep the old behavior.
 *
 * Stage F (implemented): only a final assistant row (`isFinal=1`) closes a
 * family. A retryable intermediate partial (`isFinal=0`) is never terminal,
 * so `user + partial(isFinal=0) + retry` (kill during the backoff sleeper,
 * or kill between the partial write and its retry notice) still dangles and
 * gains exactly one INTERRUPTED marker on reopen; `retry→success` and
 * `retry→terminal-failure` families own a final row and never gain one. A
 * backoff-gap cancel family owns the system cancel mark
 * (`turn <familyId> cancelled`) and likewise never gains an INTERRUPTED
 * marker. Pre-F rows and JSONL lines without the key read back as final
 * (old single-terminal semantics). Marker writes stay idempotent: a marked
 * family owns its marker row and is skipped next time.
 *
 * S2 F3 (implemented): the system leg requires `isFinal=1` exactly like the
 * assistant leg — production system marks are always final (see
 * `SessionTranscriptSink`: cancel/interrupted/legacy-failure rows all write
 * `isFinal=1`, and the import F2 gate rejects `isFinal=false` on system
 * rows), so a crafted non-final system mark must not close its family.
 * Pre-F rows without the key still default to final and are unaffected.
 * Current (S2 F3 implemented): non-final system marks dangle; Target keeps
 * this rule unchanged —
 * `isSwitchSnapshotTerminalMark` intentionally stays text-only: its kept rows
 * still funnel through this same finality rule via the snapshot dangling
 * filter, so a copied non-final mark can never close a family either.
 */
internal fun findDanglingRunIds(events: List<TranscriptEvent>): List<String> {
    val terminalFamilies = HashSet<String>()
    val userFamilies = LinkedHashSet<String>()
    for (e in events) {
        val family = familyOf(e)
        when (e.kind) {
            "assistant" -> if (e.isFinal) terminalFamilies.add(family)
            "system" -> {
                if (e.isFinal && (e.text == interruptedMarkerText(e.runId) ||
                    e.text == cancelledMarkerText(e.runId) ||
                    isLegacyFailureText(e.runId, e.text))
                ) {
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
 * Stored resume-provenance provider segment (Stage G / R2-3): `SessionMeta.model`
 * is persisted as `"${providerId}@${origin}/${modelId}"` (see `create`), so
 * everything before `@` is the origin providerId (or before the first `/` for
 * old-style provenance without `@origin`). Returns null for legacy rows that
 * predate provenance (bare model id, no `/`) or malformed strings without a
 * model separator.
 *
 * The first-`/` split remains load bearing for old-style rows: model ids
 * themselves may contain `/` (e.g. `"preset:openrouter/openrouter/auto"`),
 * while providerIds (`"preset:<presetId>"`, Stage1 `"custom:<hash>"`) never do.
 */
internal fun storedProviderOf(storedModel: String): String? {
    if ("/" !in storedModel) return null
    val firstSlash = storedModel.indexOf('/')
    val atIndex = storedModel.indexOf('@')
    return if (atIndex in 0 until firstSlash) {
        storedModel.substring(0, atIndex)
    } else {
        storedModel.substring(0, firstSlash)
    }
}

/**
 * Stored resume-provenance origin segment (Stage G / R2-3): returns the origin
 * string between `@` and `/` if present, or null if no `@` origin segment was recorded.
 */
internal fun storedOriginOf(storedModel: String): String? {
    if ("/" !in storedModel) return null
    val firstSlash = storedModel.indexOf('/')
    val atIndex = storedModel.indexOf('@')
    if (atIndex !in 0 until firstSlash) return null
    val afterAt = storedModel.substring(atIndex + 1)
    val schemeEnd = afterAt.indexOf("://")
    return if (schemeEnd >= 0) {
        val slashIndex = afterAt.indexOf('/', startIndex = schemeEnd + 3)
        if (slashIndex >= 0) {
            afterAt.substring(0, slashIndex)
        } else {
            afterAt
        }
    } else {
        afterAt.substringBefore('/')
    }
}

/**
 * Cross-provider and cross-origin resume gate (Stage G / R2-3, Phase 4
 * fail-closed): returns true when the stored history may be hydrated into
 * the current endpoint's model context.
 *
 * - Stored format with "@origin": matches both providerId and normalized origin
 *   (scheme://host[:port], path discarded per RFC 6454). Any mismatch
 *   (including custom endpoint host change or foreign provider) fails closed.
 * - Old format without "@origin" (e.g. "preset:openai/gpt-4o-mini"): ALWAYS
 *   fails closed (quarantine via fork, never hydrate) — Phase 4 fix. These
 *   headers predate R4-1 fork isolation, so one id may already mix two
 *   recipients' rows; hydrating by header alone would forward B's rows to A.
 *   The old canonical-origin allowance is removed.
 * - Legacy bare model id without "/" (e.g. "gpt-4o-mini", "m", ""): fails closed (withhold).
 * - Malformed / foreign / imported rows: fail closed (withhold).
 *
 * Guarantee scope (Phase 4): same-@origin hydration trusts the header and
 * therefore holds only for R4-1+ new/fork ledgers (fresh create() or
 * openForked() provenance, single-recipient by construction). A pre-R4-1
 * @origin ledger that already mixes rows under one id is NOT auto-detected
 * here (rows carry no per-row origin) — suspect ids must be quarantined
 * manually (start a new chat; do not rely on auto withhold).
 */
internal fun isSameProviderOrigin(
    storedModel: String,
    currentProviderId: String,
    currentBaseUrl: String,
): Boolean {
    val storedProvider = storedProviderOf(storedModel) ?: return false
    if (storedProvider.isEmpty() || currentProviderId.isEmpty()) return false
    if (storedProvider != currentProviderId) return false

    val currentOrigin = normalizeOrigin(currentBaseUrl)
    if (currentOrigin.isEmpty()) return false

    val storedOrigin = storedOriginOf(storedModel) ?: return false
    if (storedOrigin.isEmpty()) return false
    return normalizeOrigin(storedOrigin) == currentOrigin
}

/**
 * Single same-origin decision choke point for resume (D3): both the
 * same-ID path ([ChatSessionFactory.open]) and the endpoint-switch fork
 * path ([dev.librepocket.agent.ui.chat.ChatViewModel.settleDetachedLedger])
 * decide through this function, so the two can never drift apart (same
 * stored header + same endpoint ⇒ same verdict). Derives the effective
 * baseUrl from [endpoint] exactly like `create()` stamps it
 * (`"${providerId}@${normalizeOrigin(baseUrl)}/${model}"`), so the stored
 * side ([storedOriginOf], already normalized at write time, re-normalized
 * here idempotently) and the live side ([normalizeOrigin]) always meet in
 * the same normalized space. [isSameProviderOrigin] compares providerId
 * first, then normalized origin: custom endpoint ids are URL-string-exact
 * (see [SetupValidation.deriveEndpointId]), so a respelling (path, default
 * port, case) forks via id mismatch even when the origin matches
 * (fail-closed). Pinned by `ChatSessionFactoryProvenanceTest.sameOriginDecision_*`.
 */
internal fun isSameOriginForResume(storedModel: String, endpoint: EndpointConfig): Boolean {
    val effectiveBaseUrl = try {
        endpoint.toProviderConfig().baseUrl
    } catch (_: Exception) {
        ""
    }
    return isSameProviderOrigin(storedModel, endpoint.providerId, effectiveBaseUrl)
}

/**
 * Phase 4: true when [storedModel] predates R2-3 provenance (no `@origin`
 * segment). Such ids predate R4-1 fork isolation and may already mix two
 * recipients' rows — callers must quarantine (fork, never hydrate).
 */
internal fun isLegacyProvenance(storedModel: String): Boolean = storedOriginOf(storedModel) == null

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

class CaptureDeniedException(message: String = "ENDPOINT_BINDING_STALE") : SecurityException(message)

/** Injectable session-construction boundary used by the chat lifecycle tests.
 *
 * Shape names (fixed vocabulary; KDoc below uses them):
 * - `create-policy-entry` — 5-arg [create] (`allowAsk`).
 * - `create-canonical` — 4-arg [create] (policy-agnostic core).
 * - `open-allowAsk` — 4-arg [open] (`allowAsk`, concrete shim).
 * - `open-backfill-canonical` — 4-arg [open] (`backfillInterrupted`, abstract).
 * - `open-full-union` — 5-arg [open] (`allowAsk` + `backfillInterrupted`, abstract).
 *
 * Delegation graph (concrete → abstract only; the reverse never happens):
 * - 3-arg [create] → `create-canonical` (carries `initialHistory = emptyList()`).
 * - `create-policy-entry` → `create-canonical` (drops `allowAsk`: fail-closed —
 *   the proof-less canonical denies ASK at T1; production overrides the entry
 *   to thread the proof).
 * - 3-arg [open] → `open-allowAsk` with `allowAsk = false` (fail-closed).
 * - `open-allowAsk` → `open-backfill-canonical` with `backfillInterrupted = true`
 *   (drops `allowAsk`: fail-closed, same T1 reason as above).
 * - `open-full-union` has NO default body: every implementor overrides it with
 *   an explicit one-line route — production threads both params into `openImpl`,
 *   backfill-modeling fakes route to `open-backfill-canonical` (honor the fence,
 *   explicitly discard `allowAsk` as a policy-agnostic double), policy-modeling
 *   fakes route to `open-allowAsk` (keep the proof, explicitly discard the gate
 *   they have no store for). P0-1 fix: the old default body dropped
 *   `backfillInterrupted` via `open-allowAsk`, silently upgrading `false` to
 *   `true` — a fail-OPEN fence bypass. No default body means no silent drop:
 *   leaving the union unimplemented is a compile error, never a runtime
 *   StackOverflowError or a quiet param loss.
 *
 * Call-shape rule: production ViewModel calls `open-full-union` with full
 * named args (`allowAsk =`, `keyIsCurrent =`, `backfillInterrupted =`); tests
 * call the narrower forms positionally.
 */
interface ChatSessionProvider {
    /**
     * Policy entry: canonical create plus the key.read gate ([allowAsk]).
     * Production caller ChatViewModel.ensureSession uses it. Concrete —
     * delegates to the 4-arg canonical below; production overrides this to
     * thread the proof. [initialHistory] is the fork-carried model prefix
     * the new live turn already sends on; a storeless/provider-loop-only
     * fake that never hydrates documents the intentional ignore on its
     * override (see ChatWiringTest scope note).
     */
    suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        initialHistory: List<ChatMessage> = emptyList(),
        allowAsk: Boolean = false,
        keyIsCurrent: suspend () -> Boolean = { true },
    ): CreatedSession = create(endpoint, title, initialHistory, keyIsCurrent)

    /**
     * Compat create: forwards to the canonical form with an empty prefix
     * (carried, never dropped). Implementors override the canonical form
     * instead; overriding only this shim breaks fork hydration.
     */
    suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession = create(endpoint, title, emptyList(), false, keyIsCurrent)

    /**
     * Canonical create (4-arg core, policy-agnostic): abstract —
     * implementors must override it. The policy entry above delegates here
     * with an explicit fail-closed `allowAsk = false` when the caller
     * carries no proof; fakes that model hydration override this form
     * (see ChatSessionProviderShimTest).
     */
    suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        initialHistory: List<ChatMessage>,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession

    /**
     * Compat open: forwards to the canonical form with the backfill gate open
     * (`backfillInterrupted = true`, symmetric with the 3-arg create shim).
     * Implementors override the canonical form instead; overriding only this
     * shim drops the R8-3 gate.
     */
    suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        allowAsk: Boolean = false,
        keyIsCurrent: suspend () -> Boolean = { true },
    ): CreatedSession = open(endpoint, sessionId, keyIsCurrent, true)

    /**
     * 3-arg compat: pre-R8-3 callers/fakes without the fence. Delegates
     * fail-closed (allowAsk=false) with backfill on, matching the
     * pre-fence default.
     */
    suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession = open(endpoint, sessionId, false, keyIsCurrent)

    /**
     * R8-3 same-ID reopen fence: [backfillInterrupted] gates ONLY the
     * same-origin resume backfill (dangling → INTERRUPTED marker). False
     * skips this open's backfill (the next open retries, see
     * backfillInterrupted best-effort); hydration still proceeds. The
     * cross-origin fork path always backfills its fresh ledger and is never
     * gated. Canonical form — implementors must override it; fakes without a
     * store/backfill document the intentional gate no-op on their override.
     *
     * Notice cause scope: a `false` defer and a failed fork snapshot surface
     * the SAME `HISTORY_SNAPSHOT_INCOMPLETE` notice by design (the notice is
     * cause-agnostic — deferred vs failed are not distinguished in the UI
     * string). Subsequent behavior differs and is pinned separately: a defer
     * retries the backfill on the next open (see
     * `ChatSameIdReopenFenceTest.sameIdReopenWhenFlushFails_defersBackfillAndWarns`
     * gate pin), while a failed snapshot persists its incomplete marker and
     * re-warns on restart without ever re-copying (see
     * `ChatSnapshotAtomicityTest.ensureSessionSnapshotFailure_marksIncompleteNoticesAndStaysFailOpen`).
     */
    suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession

    /**
     * `open-full-union`: key.read policy gate ([allowAsk]) AND the R8-3 backfill
     * fence ([backfillInterrupted]) in one call. Production ViewModel open
     * paths call this shape (full named args); it is abstract — every
     * implementor routes it explicitly with one line. Production threads both
     * params into `openImpl`; fakes route to exactly one canonical (see the
     * interface preamble). There is deliberately no default body: the deleted
     * P0-1 default dropped [backfillInterrupted] via `open-allowAsk`, which
     * re-pinned it to `true` — a silent fail-open fence bypass (a deferred
     * `false` backfilled anyway and stamped a false INTERRUPTED).
     */
    suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        allowAsk: Boolean,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
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
    // S1: no defaults on overrides (Kotlin forbids defaults on overriding
    // functions): the fail-closed default allowAsk=false lives on the
    // interface only. Concrete-type callers must pass an explicit
    // allowAsk=false; only the ViewModel threads true (T0 ASK proof).
    override suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        initialHistory: List<ChatMessage>,
        allowAsk: Boolean,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
        // Stage1 compat (see requireCurrentEndpointId): legacy rows never
        // reach the vault read below.
        requireCurrentEndpointId(endpoint)
        val config = endpoint.toProviderConfig()
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        val model = modelFor(endpoint)
        var credential: SessionCredential? = null
        var storeForCleanup: SessionStore? = null
        var createdSessionId: String? = null
        var session: ChatSession? = null
        try {
            requireCurrentBinding(keyIsCurrent)
            // S6 capture-boundary T1 recheck (see requireKeyReadAllowed):
            // DENY/failure always deny; ASK needs the T0 one-shot proof
            // (allowAsk); ALLOW passes without proof.
            requireKeyReadAllowed(SetupValidation.policyResourceForPreset(endpoint.presetId), allowAsk)
            val captured = captureCredential(endpoint)
            credential = captured
            val store = sessionStores?.store()
            storeForCleanup = store
            requireCurrentBinding(keyIsCurrent)
            if (store != null) {
                // Keep the bounded insert non-cancellable so a durable row's id
                // is captured for rollback if the parent is cancelled mid-insert.
                withContext(NonCancellable + Dispatchers.IO) {
                    createdSessionId = withTimeout(TRANSCRIPT_CREATE_TIMEOUT_MS) {
                        // Stage G (R2-3) resume provenance: persist
                        // "${endpoint.providerId}@${origin}/${model}" so open()
                        // can refuse to forward history across providers or hosts.
                        store.createSession(
                            title.take(30),
                            "${endpoint.providerId}@${normalizeOrigin(config.baseUrl)}/$model",
                        )
                    }
                }
            }
            val sessionId = createdSessionId
            requireCurrentBinding(keyIsCurrent)
            val transcript: TranscriptSink =
                if (store != null && sessionId != null) {
                    SessionTranscriptSink(store, sessionId)
                } else {
                    // Storeless (tests): provider-loop only, never durability
                    // evidence — a fake `flushDurable == true` proves nothing
                    // (see [ChatSession.flushDurable]); durability is proven
                    // by TurnControllerLedgerTest / CancelReceiptCloseBarrierTest
                    // / FlushDurableObligationBarrierTest + Room tests, not here.
                    NoOpTranscriptSink()
                }
            // Room SessionMeta.createdAt is the source of truth for `Session started`.
            // Storeless/sessionId null/unknown/invalid/exception → null (omit, never fake now).
            // Cancellation passes through (never swallowed as null).
            val sessionStart: Instant? = try {
                if (store != null && sessionId != null) {
                    store.getSession(sessionId)?.let { it.createdAt.toInstantOrNull() }
                } else {
                    null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val createdSession = buildSession(
                config,
                model,
                transcript,
                captured,
                keyIsCurrent,
                clock,
                userTimezone,
                systemZone,
                sessionStart,
                history = initialHistory,
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
            credential?.close()
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

    // Canonical 4-arg core: explicit fail-closed policy default; production
    // callers use the 5-arg policy entry above.
    override suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        initialHistory: List<ChatMessage>,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession = create(endpoint, title, initialHistory, false, keyIsCurrent)

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
     * Stage G (R2-3) cross-provider/origin fail-closed: the prefix is only
     * hydrated when [isSameProviderOrigin] attributes the stored rows to the
     * current endpoint's provider and origin. A different provider or host
     * resumes
     * with an empty model context ([CreatedSession.historyWithheld] = true) so
     * the old history is never forwarded to the new destination; the local display
     * replay and the INTERRUPTED backfill above stay provider-agnostic.
     *
     * Stage 5 (R4-1) cross-provider/origin fork: a mismatched resume never
     * reuses the old transcript session. Instead it creates a fresh
     * provider-owned session (same [create] provenance format
     * `"${providerId}@${origin}/${model}"`, title inherited from the archived
     * header), persists only the [snapshotSwitchHistory] withheld marker into
     * it (old rows are never copied), backfills and binds the sink to the new
     * id, and returns the new id with [CreatedSession.historyWithheld] = true.
     * The old session is never written (read-only archive), so a forked
     * ledger never mixes two recipients' rows going forward.
     *
     * Phase 4 fail-closed (P1-1): legacy headers without `@origin`
     * ([isLegacyProvenance]) always take the fork path above — never the
     * same-id hydrate path — because pre-R4-1 ids may already mix two
     * recipients' rows under one id (rows carry no per-row origin, so a
     * header match cannot prove row-level same-origin). Same-`@origin`
     * hydration therefore holds only for R4-1+ new/fork ledgers (fresh
     * create()/openForked() provenance, single-recipient by construction). A
     * pre-R4-1 `@origin` ledger that already mixes rows is NOT auto-detected
     * here: suspect ids must be quarantined manually (start a new chat).
     * Same-origin resumes keep the same-id path below unchanged, except for
     * the R8-3 [backfillInterrupted] gate (same-ID reopen defers the backfill
     * when its pre-read settle failed).
     */
    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        allowAsk: Boolean,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession = openImpl(endpoint, sessionId, allowAsk, keyIsCurrent, backfillInterrupted = true)

    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession = openImpl(endpoint, sessionId, allowAsk = false, keyIsCurrent, backfillInterrupted)

    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        allowAsk: Boolean,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession = openImpl(endpoint, sessionId, allowAsk, keyIsCurrent, backfillInterrupted)

    private suspend fun openImpl(
        endpoint: EndpointConfig,
        sessionId: String,
        allowAsk: Boolean,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession {
        // Stage1 compat (see requireCurrentEndpointId): legacy rows never
        // reach the vault read below.
        requireCurrentEndpointId(endpoint)
        val config = endpoint.toProviderConfig()
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        requireCurrentBinding(keyIsCurrent)
        val store = requireNotNull(sessionStores?.store()) { "UNKNOWN_SESSION" }
        requireCurrentBinding(keyIsCurrent)
        // Retain the meta so resume reuses the original createdAt (never re-stamps now).
        // getSession failure → null → UNKNOWN_SESSION (never masks unknown with now).
        // Cancellation passes through (never swallowed as null).
        val meta = try {
            store.getSession(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        require(meta != null) { "UNKNOWN_SESSION" }
        requireCurrentBinding(keyIsCurrent)
        // P0-2: S6 capture-boundary T1 recheck BEFORE the same-origin fork
        // branch, so both paths share it. The pre-fix order branched into
        // openForked() first, which read the vault via captureCredential
        // without any key.read recheck (compat-shim allowAsk=false never
        // denied on the fork path). Read-only recheck (evaluateFresh only;
        // it never consumes the one-shot consent — takePendingKeyReadConsent
        // runs at T0 authorize/burn time only), so evaluating twice is safe;
        // the second check below stays as defense in depth.
        requireKeyReadAllowed(SetupValidation.policyResourceForPreset(endpoint.presetId), allowAsk)
        // Stage G (R2-3): cross-provider / cross-origin resume hydrates nothing.
        // Same-origin resumes run the local INTERRUPTED backfill below
        // (provider-agnostic bookkeeping) and hydrate only on provenance
        // match; cross-origin resumes return early to openForked(), which
        // backfills only the NEW ledger while the old session stays a
        // read-only archive. Decided through [isSameOriginForResume], the
        // same choke point the VM fork path uses — the two never disagree
        // on one ledger.
        val sameOrigin = isSameOriginForResume(meta.model, endpoint)
        if (!sameOrigin) {
            return openForked(store, endpoint, config, sessionId, meta, keyIsCurrent)
        }
        // R8-3: gated by the caller's same-ID settle outcome. False skips
        // this backfill (a still-unsettled live turn must not gain a false
        // INTERRUPTED); the next open retries (best-effort). Hydration below
        // still proceeds. The fork path above always backfills its fresh
        // ledger and is never gated.
        if (backfillInterrupted) {
            backfillInterrupted(store, sessionId)
        }
        // Stage E: tail load (newest kept) with an exact dropped count; the
        // cap also travels as MaxMessages so TurnController.droppedHistoryCount
        // observes the same truncation (defense in depth: the prefix already
        // saturates the cap, so each new live turn evicts the oldest prefix
        // row to hold the combined request total at cap).
        val loaded = loadModelHistory(store, sessionId)
        requireCurrentBinding(keyIsCurrent)
        val model = modelFor(endpoint)
        // S6 capture-boundary T1 recheck (see requireKeyReadAllowed):
        // DENY/failure always deny; ASK needs the T0 one-shot proof
        // (allowAsk); ALLOW passes without proof. Second evaluation as
        // defense in depth: the pre-branch check above already gated the
        // fork path, and this recheck narrows the TOCTOU window before the
        // vault read on the same-origin path. Safe to run twice — the
        // recheck is read-only (no consent slot consumed).
        requireKeyReadAllowed(SetupValidation.policyResourceForPreset(endpoint.presetId), allowAsk)
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
                HistoryWindowCap.MaxMessages(SnapshotBudget.HISTORY_LOAD_CAP),
            )
            session = openedSession
            requireCurrentBinding(keyIsCurrent)
            return CreatedSession(
                sessionId,
                openedSession,
                endpoint.providerId,
                model,
                historyWithheld = false,
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

    /**
     * Stage 5 (R4-1) fork body for a cross-provider/origin [open] — Phase 4
     * also the legacy quarantine path: the old transcript session stays a
     * read-only archive (no backfill, no sink, no model-history read), and
     * every new row lands in a fresh provider-owned session. Mirrors the
     * [dev.librepocket.agent.ui.chat.ChatViewModel.ensureSession]
     * cross-origin path (marker-only [snapshotSwitchHistory], never a copy).
     * This path never splits, migrates, or backfills the archived session:
     * already-mixed pre-R4-1 ids stay archived as-is (quarantined, never
     * re-hydrated whole-batch into a live provider context).
     *
     * Key.read gate: covered by the pre-branch T1 recheck in [openImpl]
     * (single call site, directly above the branch) — the compat-shim
     * `allowAsk = false` denies here too (default key.read is ASK), so a
     * 3-arg open on a cross-origin ledger fails closed before any vault read.
     */
    private suspend fun openForked(
        store: SessionStore,
        endpoint: EndpointConfig,
        config: ProviderConfig,
        oldSessionId: String,
        oldMeta: dev.librepocket.session.SessionMeta,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
        val model = modelFor(endpoint)
        val credential = captureCredential(endpoint)
        var session: ChatSession? = null
        var forkedSessionId: String? = null
        try {
            requireCurrentBinding(keyIsCurrent)
            // Fresh provider-owned ledger: title inherited, provenance stamped
            // exactly like create() so the fork reopens same-origin later.
            val newSessionId: String = withContext(NonCancellable + Dispatchers.IO) {
                withTimeout(TRANSCRIPT_CREATE_TIMEOUT_MS) {
                    store.createSession(
                        oldMeta.title.take(30),
                        "${endpoint.providerId}@${normalizeOrigin(config.baseUrl)}/$model",
                    )
                }
            }
            forkedSessionId = newSessionId
            requireCurrentBinding(keyIsCurrent)
            // Durable fork marker only (withheld, never a copy): lets a later
            // openSession(newId) re-surface the withhold notice after restart.
            // Best-effort — a bookkeeping failure must not fail the resume.
            try {
                val oldModelShort = oldMeta.model.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: "unknown"
                snapshotSwitchHistory(
                    store,
                    oldSessionId,
                    newSessionId,
                    oldModelShort,
                    model,
                    historyWithheld = true,
                    nowMillis = clock.millis(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Degrades to an unmarked empty fork: isolation still holds,
                // only the restart notice is lost.
            }
            // Backfill the NEW ledger only; the old session is never written.
            backfillInterrupted(store, newSessionId)
            requireCurrentBinding(keyIsCurrent)
            val forkMeta = try {
                store.getSession(newSessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val sessionStart = forkMeta?.createdAt?.toInstantOrNull()
            requireCurrentBinding(keyIsCurrent)
            val openedSession = buildSession(
                config,
                model,
                SessionTranscriptSink(store, newSessionId),
                credential,
                keyIsCurrent,
                clock,
                userTimezone,
                systemZone,
                sessionStart,
                emptyList(),
                HistoryWindowCap.MaxMessages(SnapshotBudget.HISTORY_LOAD_CAP),
            )
            session = openedSession
            requireCurrentBinding(keyIsCurrent)
            return CreatedSession(
                newSessionId,
                openedSession,
                endpoint.providerId,
                model,
                historyWithheld = true,
                historyDroppedCount = 0,
            )
        } catch (failure: Throwable) {
            try {
                session?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            credential.close()
            val orphan = forkedSessionId
            if (orphan != null) {
                try {
                    deleteUnusedTranscriptSession(store, orphan)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
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

    internal fun cappedHistoryText(text: String): String = dev.librepocket.agent.ui.chat.cappedHistoryText(text)

    internal suspend fun loadModelHistory(store: SessionStore, sessionId: String): LoadedModelHistory =
        dev.librepocket.agent.ui.chat.loadModelHistory(store, sessionId)

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

    /**
     * Stage1 compat: a legacy custom row (stored entity id predates
     * per-endpoint ids, e.g. `preset:custom`) is never given a live session.
     * Fails closed as [CaptureDeniedException] before any vault read — the
     * pre-staged fixed slot may hold another URL's key, so the row's
     * URL/key binding is unprovable. The ViewModel already withholds the
     * binding ([bindingOf] returns null); this is defense in depth for
     * direct factory callers. Pure check, zero vault I/O on deny.
     */
    private fun requireCurrentEndpointId(endpoint: EndpointConfig) {
        if (SetupValidation.isLegacyEndpoint(endpoint)) throw CaptureDeniedException("ENDPOINT_ID_LEGACY")
    }

    private suspend fun requireCurrentBinding(keyIsCurrent: suspend () -> Boolean) {
        if (!keyIsCurrent()) throw CancellationException("ENDPOINT_BINDING_STALE")
    }

    /**
     * S6 capture-boundary T1 recheck: a second `key.read` fresh evaluation
     * immediately before the vault read (after the first binding gate).
     *
     * Stage1 entity/policy split: [policyResource] is the preset-shared
     * authorization scope (see [SetupValidation.policyResourceForPreset],
     * granularity frozen for Stage2 #13) — never the per-endpoint transport
     * identity, which is fenced separately by the binding gates and
     * [requireCurrentEndpointId].
     *
     * DENY or an evaluation failure aborts fail-closed as
     * [CaptureDeniedException] (no vault read, no session built). ALLOW
     * passes without proof. ASK passes only with the T0 one-shot proof
     * ([allowAsk]: the ViewModel's T0 gate already consumed a bound
     * consent for this same operation); a still-ASK verdict at T1 without
     * that proof denies (an ALLOW→ASK flip between T0 and T1 must not
     * pass through). [allowAsk] is true only when T0 observed ASK plus a
     * binding- and generation-matched consent; every other T0 outcome
     * passes false.
     *
     * Residual window (documented): a flip landing after this recheck
     * but before `getKey` returns still passes. T1→`vault()`→`getKey`
     * contains suspend/IO (dispatcher hop plus Keystore/disk IO); no
     * gate is held across it. A DENY flip after the recheck has no
     * later gate to catch it; a binding/generation flip blocks attach
     * via the later `keyIsCurrent` gates but not the vault read that
     * already happened (only `close` wipes the captured secret).
     */
    private suspend fun requireKeyReadAllowed(policyResource: String, allowAsk: Boolean) {
        val verdict = try {
            policy.evaluateFresh("key.read", policyResource).verdict
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw CaptureDeniedException()
        }
        if (verdict == Verdict.DENY) throw CaptureDeniedException()
        if (verdict == Verdict.ASK && !allowAsk) throw CaptureDeniedException()
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
     * family with no final assistant row in the same family (completed,
     * cancelled-partial, failed-with-reason; Stage F retried partials are
     * non-final and never count) and no cancel/interrupted/legacy-failure
     * system mark is a turn the previous process killed before any terminal
     * record, so it gets one explicit `system` marker row bound to the same
     * family id.
     *
     * Stage C (implemented): retry→success and retry→terminal-failure
     * families already own attempt assistant rows (bound via
     * `parentRunId`), so they are completed and never gain a marker — this
     * is the Finding #3 fix. Stage F: only final rows count — a lone
     * non-final retried partial still dangles (kill during the backoff
     * sleeper, or between its partial write and its retry notice); a
     * backoff-gap cancel family owns the system cancel mark and never gains
     * an INTERRUPTED marker. Deliberately NOT marked: intentional-cancel
     * partials already own a final assistant row (`isPartial=1`), so they
     * replay as partial and never gain a marker; completed and failed turns
     * likewise own theirs.
     *
     * R5-3 scope note: the "never gains a marker" guarantee above holds for
     * the ledger that owns the terminal row. Before R5-3 the same-origin
     * fork snapshot copied only the model projection (user/assistant,
     * `!isPartial`), so a forked ledger lost its partials and terminal
     * system marks and the reopen backfill mis-read the orphaned user row as
     * stranded (false INTERRUPTED). R5-3 snapshots the UI replay/audit
     * projection instead (see [loadSwitchSnapshotEvents]), restoring the
     * guarantee for forked ledgers as well.
     *
     * Prune cannot cause false positives (it drops the
     * oldest rows first, and an assistant row always has a larger seq than
     * its user row, so prune can orphan assistants but never users).
     * Re-running is idempotent: a marked family owns its marker row and
     * [findDanglingRunIds] skips it next time.
     *
     * Best-effort (never blocks resume): marker writes go through the same
     * guarded path as every other transcript write. A failed backfill just
     * leaves the run dangling; a later open re-scans the same bounded window
     * (best-effort retry, never guaranteed). Cancellation always propagates
     * so a lifecycle cancel / stale binding never turns into markers.
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best effort per marker; the next open retries.
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort overall; resume proceeds without markers.
        }
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

/**
 * Restored model-context prefix, Stage E tail semantics: the newest
 * qualifying rows (user/assistant, non-partial) up to [SnapshotBudget.HISTORY_LOAD_CAP],
 * oldest-first, via backward paging (memory O(cap), never O(session);
 * sparse seqs safe). Partial rows are excluded from model context (they
 * replay in the UI flagged, but must never read as completed answers —
 * mirroring the live `!isPartial` request filter); `system` markers stay
 * export/debug-only via the same filter.
 *
 * R8-4: payload-free `reasoning` markers ride the same scan (exact-`runId`)
 * into [ChatMessage.hasReasoning] on their assistant row (`isFinal` /
 * `isPartial` ignored); the marker rows themselves never enter model
 * context and never affect [LoadedModelHistory.droppedCount].
 *
 * Phase 4 fail-closed (P1-2): when the tail cap omits older rows
 * (`droppedCount > 0`, i.e. [LoadedModelHistory.reasoningIncomplete]), the
 * marker scan may have missed an older `reasoning` marker whose assistant
 * row is still kept. Every kept assistant is then conservatively resumed
 * with `hasReasoning=true`, so a tool-dependent / thinking-mode follow-up
 * fails closed via `requireNoReasoningHistory`
 * (`THINKING_ROUND_TRIP_NOT_SUPPORTED`) instead of mis-passing as
 * plain-text. Ordinary no-tools follow-ups continue unchanged (the gate only
 * refuses tool-dependent / thinking requests), so pure-chat resume is
 * unaffected. This over-flags plain truncated ledgers by design (safe
 * direction); the omission stays observable via `droppedCount`.
 *
 * S4-F4(b) + R26-1/R27 family-aware (Current): the LATEST prune sweep's
 * outcome ([SessionStore.isPruneSuspect], pre-published before the DELETE to
 * close the F4 race, per-sweep NOT sticky — a later clean sweep
 * (`deleted==0` AND user/empty post-fixup head) clears it, delete clears
 * it, appends never clear it) forces the same conservative all-flag even
 * when `droppedCount==0`. R26-1/R27 prune deletes the cut head family
 * (user boundary; head-only, convergent — unbounded, any N head orphans in
 * one sweep, unconditionally in the same tx with no `deletes>0` gate), so
 * new prunes leave no user-less assistant behind (no crash-mid-fixup
 * window: raw cut and fixup run in a single Room transaction per session —
 * a kill between them rolls back, see `RoomSessionStore` KDoc and
 * P1_SPEC/THREAT_MODEL);
 * readers additionally drop head orphan families when dropped>0 or suspect
 * (far-only preserves the R25 precise flag), so no orphan reaches model
 * context. Pre-R26/interior orphans below the head survive the fixup and,
 * once suspect is cleared (clean sweep) or dropped (restart), resume compat
 * without evidence (known limitation, disclosed in P1_SPEC/THREAT_MODEL).
 * Test-fake divergence: `SuspectFakeStore.simulatePruneCut` stays R26-only
 * (`drop>0` gate + 10-cap + unconditional clear); R27 proven by Room tests.
 *
 * R26-1 F4 scan-then-count (Current): the filtered COUNT is taken before and
 * after the backward scan and the max is used, so a concurrent prune/append
 * between scan and count cannot under-flag (max is conservative; any
 * difference already forces dropped>0). Pre-published suspect covers
 * non-qualifying deletes with unchanged counts. The DELETE-commit →
 * reconcile window itself is closed by pre-publish (latch-pinned by the
 * single after-delete-hook test: a load interleaved between DELETE commit
 * and flag reconcile/fixup observes suspect==true; post-publish code would
 * report false-clean there).
 *
 * R25-1 far-marker guard (P2, fail-closed, bounded): when the kept window is
 * full (`kept == HISTORY_LOAD_CAP`) yet the filtered COUNT reports
 * `dropped == 0`, a `reasoning` marker more than one backward page below the
 * window (e.g. seq=1 marker + 200 non-model notices + 2000 qualifying rows)
 * would otherwise resume its kept assistant as plain text. The scan's bounded
 * existence probe ([ModelHistoryScan.hasFarReasoning], single COUNT strictly
 * below the extra-page floor, no unbounded scan) forces the same conservative
 * all-flag when any older `reasoning` row exists there; a probe failure also
 * forces suspect (fail-closed). Adjacent markers collected by the extra page
 * do not set it (R24-2 precise flag preserved). Over-flags by design when the
 * far marker's assistant is no longer kept (plain filler flags too);
 * family-aware head trim stays conditional (far-only never drops, preserving
 * R25).
 *
 * Truncation is observable: [LoadedModelHistory.droppedCount] carries the
 * exact omitted-row total (filtered COUNT query, O(1) memory; head-orphan
 * drops increase it honestly). A giant
 * single row is truncated to [MODEL_HISTORY_MAX_CHARS_PER_MESSAGE] with a
 * visible marker — fail-closed, never a silent unbounded payload (a
 * measured token budget is still TODO; the char bound is interim).
 *
 * Unlike the best-effort backfill, a history-read failure propagates:
 * resuming blind (without the context the model needs) fails closed
 * through the caller's UNKNOWN/NO_ENDPOINT path instead.
 */
internal suspend fun loadModelHistory(store: SessionStore, sessionId: String): LoadedModelHistory {
    // R26-1 F4: snapshot the filtered total before AND after the scan; the max
    // is conservative (concurrent prune shrinks, append grows – max never
    // under-flags; any difference already forces dropped>0).
    val totalBefore = store.countHistoryEvents(sessionId, includePartial = false)
    val scan = loadModelHistoryScan(store, sessionId)
    val totalAfter = store.countHistoryEvents(sessionId, includePartial = false)
    val totalMax = maxOf(totalBefore, totalAfter)
    val prelimDropped = (totalMax - scan.events.size).coerceAtLeast(0)
    // S4-F4(b): prune-suspect (the LATEST sweep deleted rows — per-sweep,
    // not sticky) forces conservative all-flag, matching dropped>0
    // fail-closed.
    // S4-R2: fail-closed on read failure (exception => suspect true) so a
    // wedged store cannot green-light a tool-dependent follow-up.
    val pruneSuspect = try {
        store.isPruneSuspect(sessionId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }
    // R25-1 far-marker guard: exact-cap with an uncollected marker more than
    // one page out (scan.hasFarReasoning, bounded COUNT below the extra-page
    // floor) forces the same conservative all-flag as dropped > 0. Adjacent
    // markers collected by the extra page do not set it (R24-2 preserved).
    // Over-flags plain filler by design (family-aware head trim stays
    // conditional and never drops on far-only, preserving R25).
    val farMarkerSuspect = prelimDropped == 0 && !pruneSuspect &&
        scan.events.size == SnapshotBudget.HISTORY_LOAD_CAP && scan.hasFarReasoning
    // R26-1 family-aware head trim: with truncation/prune evidence, exclude
    // head user-less families so no orphan assistant enters model context.
    val filtered = if (prelimDropped > 0 || pruneSuspect) {
        dropHeadOrphanAssistantFamilies(scan.events)
    } else {
        scan.events
    }
    val dropped = (totalMax - filtered.size).coerceAtLeast(0)
    val incomplete = dropped > 0 || pruneSuspect || farMarkerSuspect
    return LoadedModelHistory(
        filtered.map {
            ChatMessage(
                role = it.kind,
                text = cappedHistoryText(it.text),
                hasReasoning = it.kind == "assistant" &&
                    (it.runId in scan.reasoningRunIds || incomplete),
            )
        },
        dropped,
        incomplete,
    )
}

/**
 * R8-4: shared backward scan backing [loadModelHistory] /
 * [loadModelHistoryEvents]. Collects the payload-free `reasoning` marker
 * runIds (exact-`runId` pairing, never [familyOf]; `isFinal` / `isPartial`
 * ignored) alongside the newest qualifying model rows, so reopened
 * reasoning-origin turns keep their `hasReasoning` flag in
 * `cappedHistory` → `buildRequest` (fail-closed on tool-dependent /
 * thinking-mode follow-ups; ordinary no-tools follow-ups continue).
 *
 * Phase 4 fail-closed (P1-2): [reasoningIncomplete] is true when the cap
 * filled (`kept == HISTORY_LOAD_CAP`) and older rows — possibly `reasoning`
 * markers — may exist outside the window. [loadModelHistory] refines this
 * with the exact COUNT (`dropped > 0`) and conservatively flags every kept
 * assistant; direct [ModelHistoryScan] callers must treat a true flag the
 * same way (suspect, fail closed on tool-dependent follow-ups, never silent
 * `hasReasoning=false`).
 *
 * R24-2 boundary: the kept scan stops once 2000 qualifying rows are held,
 * so a marker-first production pair (`reasoning` at seq N, `assistant` at
 * seq N+1) whose assistant is the oldest kept row leaves its marker one row
 * below the window. One extra marker-only page plus same-page remainder
 * collection closes that adjacent gap; a marker more than one page out stays
 * uncollected (R25-1: covered by the COUNT fail-closed when `dropped > 0`,
 * and by the bounded [SessionStore.hasReasoningBefore] probe when
 * `dropped == 0` with a full window — [loadModelHistory] then over-flags all
 * kept assistants by design; pair-aware narrowing stays Target).
 */
internal data class ModelHistoryScan(
    /**
     * Newest qualifying model rows, oldest-first: pure user/assistant
     * projection (`!isPartial` only; `reasoning` markers and `system` rows
     * never appear here).
     */
    val events: List<TranscriptEvent>,
    /**
     * Exact-`runId` `reasoning`-marker owners (`isFinal` / `isPartial`
     * ignored, never [familyOf]). R24-2 covers the adjacent boundary marker
     * (oldest kept assistant's marker-first row, one extra page + the fill
     * page remainder); a marker more than one page out stays uncollected
     * (R25-1: [loadModelHistory] probes it boundedly and over-flags; see the
     * class note).
     */
    val reasoningRunIds: Set<String>,
    /**
     * Phase 4: true when the scan hit the row cap and older markers may have
     * been missed (conservative: true even when the ledger holds exactly CAP
     * rows; [loadModelHistory] narrows it with the exact dropped count).
     */
    val reasoningIncomplete: Boolean = false,
    /**
     * R25-1: true when a `reasoning` row exists strictly below the R24-2
     * extra-page floor (i.e. more than one backward page below the kept
     * window and therefore uncollected). Bounded single COUNT probe, never an
     * unbounded scan; a probe failure reports true (fail-closed). Adjacent
     * boundary markers collected by the extra page do NOT set this (R24-2
     * keeps its precise flag with `dropped == 0`); only truly far markers do,
     * and then [loadModelHistory] over-flags all kept assistants by design
     * (Target: pair-aware narrowing).
     */
    val hasFarReasoning: Boolean = false,
)

internal suspend fun loadModelHistoryScan(store: SessionStore, sessionId: String): ModelHistoryScan {
    val keptNewestFirst = ArrayList<TranscriptEvent>(SnapshotBudget.HISTORY_LOAD_CAP)
    val reasoningRunIds = HashSet<String>()
    var beforeSeq = Long.MAX_VALUE
    var capHit = false
    while (keptNewestFirst.size < SnapshotBudget.HISTORY_LOAD_CAP) {
        val page = store.loadEventsBefore(sessionId, beforeSeq, HISTORY_PAGE)
        if (page.isEmpty()) break
        for (event in page) {
            if (event.kind == "reasoning") {
                // Flag-only marker (empty text): never model context itself,
                // only the exact-runId flag for its assistant row.
                reasoningRunIds.add(event.runId)
                continue
            }
            if ((event.kind == "user" || event.kind == "assistant") && !event.isPartial) {
                if (keptNewestFirst.size >= SnapshotBudget.HISTORY_LOAD_CAP) {
                    // R24-2: cap already full mid-page — skip the kept row
                    // but keep collecting markers in this page's remainder
                    // (the oldest kept assistant's marker may sit below the
                    // fill point in the same 200-row page).
                    capHit = true
                    continue
                }
                keptNewestFirst.add(event)
                if (keptNewestFirst.size >= SnapshotBudget.HISTORY_LOAD_CAP) capHit = true
            }
        }
        // Pages are newest-first with distinct seqs; the next window ends
        // strictly below this page's minimum. A non-advancing store would
        // spin forever, so fail closed instead.
        val pageMin = page.minOf { it.seq }
        if (pageMin >= beforeSeq) break
        beforeSeq = pageMin
        if (page.size < HISTORY_PAGE) break
        // Cap filled: the kept window is done; the marker boundary scan
        // below collects the adjacent row. No further kept pages.
        if (keptNewestFirst.size >= SnapshotBudget.HISTORY_LOAD_CAP) break
    }
    if (!capHit && keptNewestFirst.size >= SnapshotBudget.HISTORY_LOAD_CAP) capHit = true
    if (capHit) {
        // R24-2 boundary marker scan (scan-side small fix, chosen over
        // import pairing/order validation): production writes marker-first
        // (`reasoning` seq N, `assistant` seq N+1, adjacent), so when the
        // oldest kept assistant is reasoning-origin its marker sits exactly
        // one row below the kept window — just outside the last backward
        // page when the cap fills on a page boundary (e.g. seq=1 marker +
        // seq=2 assistant + seq=3..2001 filler: DESC pages fill 2000 kept at
        // seq=2 and stop before seq=1). One extra marker-only page restores
        // that flag with `dropped == 0` (no over-flagging of plain turns).
        // A marker more than one page out stays missed by design; when older
        // qualifying rows exist (`dropped > 0`) the COUNT fail-closed flags
        // every kept assistant anyway. Room JSONL import validates per-row
        // only (no pairing/order rule), so this marker-first ordering is a
        // legal import shape (no import UI yet, tools unenabled: narrow P2).
        val extra = store.loadEventsBefore(sessionId, beforeSeq, HISTORY_PAGE)
        for (event in extra) {
            if (event.kind == "reasoning") reasoningRunIds.add(event.runId)
        }
        // R25-1 far-marker probe (bounded, fail-closed): rows strictly below
        // the extra-page floor were never scanned (neither kept nor extra).
        // A single COUNT probe there catches a marker more than one page out
        // (e.g. seq=1 marker + 200 notices + 2000 qualifying: extra covers
        // 201..2, floor=2, probe seq<2 hits seq=1). An adjacent marker
        // collected by the extra page sits at/above the floor and never sets
        // this (R24-2 precise flag preserved). Probe failure is suspect.
        // Cost note: this COUNT runs on every capHit even though
        // [loadModelHistory] only consumes it when `dropped == 0` (`dropped > 0`
        // already flags) — a single bounded COUNT is accepted over deferring,
        // which would still need the COUNT first to decide (same cost).
        val extraFloor = extra.minOfOrNull { it.seq } ?: beforeSeq
        val farSuspect = try {
            store.hasReasoningBefore(sessionId, extraFloor)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        return ModelHistoryScan(keptNewestFirst.asReversed(), reasoningRunIds, capHit, farSuspect)
    }
    return ModelHistoryScan(keptNewestFirst.asReversed(), reasoningRunIds, capHit)
}

/**
 * Raw-event form of [loadModelHistory]: the newest qualifying rows
 * (user/assistant, non-partial) up to [SnapshotBudget.HISTORY_LOAD_CAP], oldest-first, via
 * the same backward paging. Source of truth for the in-memory model-context
 * hydration only (see `open` / `ensureSession`): the R4-2 durable fork
 * snapshot below deliberately does NOT reuse this set anymore (R5-3: it uses
 * [loadSwitchSnapshotEvents], the UI replay/audit projection, so partials
 * and terminal system marks survive a same-origin switch and the reopen
 * backfill never mistakes a copied turn for a stranded one).
 *
 * Unlike [loadModelHistory], texts are NOT truncated here: truncation stays a
 * read-path concern ([cappedHistoryText] on hydrate), so a snapshot copy
 * preserves the stored bytes verbatim and the reopened session re-applies the
 * identical bound (idempotent). Family linkage (`runId` / `parentRunId` /
 * `attemptIndex` / `isFinal` / `createdAt`) is preserved verbatim so the
 * reopen backfill never mistakes a copied turn for a stranded one — R5-3
 * scope note: that linkage claim holds for this model projection only when
 * the reader also owns the partial/mark rows; the durable snapshot carries
 * them via [loadSwitchSnapshotEvents] instead.
 */
internal suspend fun loadModelHistoryEvents(store: SessionStore, sessionId: String): List<TranscriptEvent> {
    // R8-4: same backward scan as [loadModelHistory] (reasoning markers are
    // collected there for the hasReasoning flag but never returned here —
    // this stays the pure user/assistant model projection).
    return loadModelHistoryScan(store, sessionId).events
}

/**
 * R5-3: terminal `system` marks that close a logical family for
 * [findDanglingRunIds] (exact-text match against the row's own `runId`,
 * same rule as the backfill scan): `turn <runId> interrupted`,
 * `turn <runId> cancelled`, and legacy `turn <runId> failed…` rows. Only
 * these cross a same-origin fork — usage accounting rows
 * (`usage input=… output=…`), `model switched …` fork markers, and
 * `retry`/`tool`/`steer` notices never do (usage stays export/debug-only;
 * each fork owns exactly one switch marker, never a copy; notices are
 * neither replay nor terminal).
 */
internal fun isSwitchSnapshotTerminalMark(event: TranscriptEvent): Boolean {
    if (event.kind != INTERRUPTED_MARKER_KIND) return false
    return event.text == interruptedMarkerText(event.runId) ||
        event.text == cancelledMarkerText(event.runId) ||
        isLegacyFailureText(event.runId, event.text)
}

/**
 * R6-5: bounded fork-snapshot load result. [events] is the newest-kept
 * qualifying prefix, oldest-first; [droppedByBytes] is the exact number of
 * older qualifying rows excluded by [SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS] (0 when the
 * prefix fit — row-cap drops stay silent as before and are NOT counted
 * here). Counted in the same backward scan (transient pages only, bounded by
 * the prune ceiling), never a second query.
 */
internal data class SwitchSnapshotLoad(
    val events: List<TranscriptEvent>,
    val droppedByBytes: Int,
)

/**
 * R5-3 durable fork-snapshot projection (UI replay/audit): the newest kept
 * rows up to [SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP] AND [SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS] total
 * chars (R6-5 dual budget), oldest-first, via the same backward paging. Keeps
 * every `user`/`assistant` row INCLUDING partials (verbatim: `isPartial` /
 * `failureReason` / `parentRunId` / `attemptIndex` / `isFinal` /
 * `createdAt` preserved, `seq` reassigned on copy, texts never truncated —
 * truncation stays a read-path concern via [cappedHistoryText] on hydrate)
 * plus the terminal `system` marks above ([isSwitchSnapshotTerminalMark])
 * plus every payload-free `reasoning` marker (R8-4: same `runId`, empty text,
 * verbatim copy — never a [findDanglingRunIds] terminal, so an orphan marker
 * is a harmless pending entry; the marker rides with its assistant under the
 * same [familyOf] in the R6-3 dangling filter).
 *
 * Hydration is intentionally split after R5-3: [loadModelHistoryEvents]
 * stays the model-context projection (`!isPartial`, used by `open` /
 * `ensureSession` and mirrored by `TurnController.buildRequest`'s live
 * `!isPartial` filter, so the provider payload never sees a partial), while
 * this function is the durable-snapshot projection (used only by
 * [snapshotSwitchHistory]). Pre-restart model contexts still agree because
 * reopen re-applies the same `!isPartial` filter to the copied rows.
 *
 * Counting basis: the row budget covers the combined UI/audit set
 * (`countHistoryEvents(includePartial=true)` user/assistant rows plus the
 * terminal marks, which that COUNT excludes as `system` rows), newest-first,
 * so a large ledger drops the oldest rows first (silent, as before). At a
 * full 1999-row prefix each `reasoning`/terminal marker displaces one
 * oldest row; bounded (one marker slot) and acceptable. The
 * char budget accumulates the same newest-first order and stops the prefix
 * at the first row that would overflow [SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS] — that row
 * and every older qualifying row is reported via [SwitchSnapshotLoad.droppedByBytes]
 * (counted in the same scan, transient pages only) so the caller abandons
 * the whole prefix instead of copying a half (see [snapshotSwitchHistory]).
 * Marker + copy never exceeds [SnapshotBudget.HISTORY_LOAD_CAP]: one slot is
 * reserved for the head-anchored `model switched` marker, and a second slot
 * for the `snapshot incomplete` marker when the copy is incomplete
 * (R28-1 write-time clamp in [snapshotSwitchHistory]),
 * matching the prune ceiling.
 */
internal suspend fun loadSwitchSnapshotEvents(store: SessionStore, sessionId: String): SwitchSnapshotLoad {
    val keptNewestFirst = ArrayList<TranscriptEvent>(SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP)
    var totalChars = 0L
    var droppedByBytes = 0
    var byteCapped = false
    var beforeSeq = Long.MAX_VALUE
    outer@ while (true) {
        val page = store.loadEventsBefore(sessionId, beforeSeq, HISTORY_PAGE)
        if (page.isEmpty()) break
        for (event in page) {
            // R8-4: reasoning markers ride verbatim (flag-only, empty text).
            // Deliberately NOT a terminal mark (see
            // [isSwitchSnapshotTerminalMark]): an orphan marker must never
            // close a family for [findDanglingRunIds].
            val keep = (event.kind == "user" || event.kind == "assistant" || event.kind == "reasoning") ||
                isSwitchSnapshotTerminalMark(event)
            if (!keep) continue
            if (byteCapped) {
                // Budget already blown: count only (transient page row, never
                // retained) so the caller can abandon honestly.
                droppedByBytes++
                continue
            }
            if (keptNewestFirst.size >= SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP) break@outer
            if (totalChars + event.text.length > SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS) {
                // Newest-first accumulate, over budget stops here: this row
                // and every older qualifying row drops (counted below).
                byteCapped = true
                droppedByBytes++
                continue
            }
            keptNewestFirst.add(event)
            totalChars += event.text.length
        }
        // Pages are newest-first with distinct seqs; the next window ends
        // strictly below this page's minimum. A non-advancing store would
        // spin forever, so fail closed instead.
        val pageMin = page.minOf { it.seq }
        if (pageMin >= beforeSeq) break
        beforeSeq = pageMin
        if (page.size < HISTORY_PAGE) break
    }
    return SwitchSnapshotLoad(
        // R26-1 family-aware (Current, R27-2 convergent): never copy a head
        // user-less assistant family (prune/tail-cap cut). Unconditional here
        // (row-cap drops are silent legacy; family drops stay silent too);
        // the convergent helper drops any N head orphans in one pass. The
        // existing dangling filter in snapshotSwitchHistory still drops
        // user-without-terminal families separately.
        dropHeadOrphanAssistantFamilies(keptNewestFirst.asReversed()),
        droppedByBytes,
    )
}

/** Marker kind for an R4-2 model-switch fork: hidden from chat replay, kept in export. */
internal const val MODEL_SWITCH_MARKER_KIND = "system"

/** Head-scan budget for [hasWithheldSwitchMarker]: the marker is row 1 of a forked session. */
private const val MODEL_SWITCH_MARKER_SCAN_LIMIT = 16

/**
 * Audit marker for one endpoint-switch fork (`oldModel -> newModel` are the
 * short model ids). Appended when [withheld] is true, the suffix records that
 * the old prefix was deliberately NOT carried (cross provider/origin), so a
 * later [dev.librepocket.agent.ui.chat.ChatViewModel.openSession] can
 * re-surface the withhold notice after a restart.
 */
internal fun modelSwitchMarkerText(oldModel: String, newModel: String, historyWithheld: Boolean): String {
    val base = "model switched $oldModel -> $newModel"
    return if (historyWithheld) "$base [history withheld: cross-provider-origin]" else base
}

internal fun isModelSwitchMarker(text: String): Boolean = text.startsWith("model switched ")

internal fun modelSwitchMarkerWithheld(text: String): Boolean =
    isModelSwitchMarker(text) && text.contains("[history withheld: cross-provider-origin]")

/** Marker kind for an R5-4 incomplete fork snapshot: hidden from chat replay, kept in export. */
internal const val SNAPSHOT_INCOMPLETE_MARKER_KIND = "system"

/** Head/tail scan budget for [hasIncompleteSnapshotMarker] (see below). */
private const val SNAPSHOT_INCOMPLETE_SCAN_LIMIT = 16

/**
 * R5-4 audit marker for a failed fork snapshot (`oldModel -> newModel` are
 * the short model ids). Best-effort appended by
 * [dev.librepocket.agent.ui.chat.ChatViewModel.ensureSession] to the fork
 * target when the atomic [snapshotSwitchHistory] batch throws, so a later
 * [dev.librepocket.agent.ui.chat.ChatViewModel.openSession] can re-surface
 * HISTORY_SNAPSHOT_INCOMPLETE after a restart. Same `system`-kind
 * hidden-from-replay / visible-in-export shape as the switch marker, with a
 * distinct `snapshot incomplete ` prefix so the two scans never confuse
 * each other.
 *
 * R6-3 cause tag ([reason]): the scan ([hasIncompleteSnapshotMarker]) stays
 * deliberately prefix-based and cause-agnostic — every cause re-warns the
 * same HISTORY_SNAPSHOT_INCOMPLETE notice — but the suffix names the cause
 * honestly instead of always claiming the copy failed:
 * - `"copy failed"` (default): the atomic batch itself threw, so the fork
 *   holds zero snapshot rows (appended best-effort by the caller's catch).
 * - `"ledger unsettled"`: the copy succeeded but the pre-copy fence failed
 *   (old ledger may still miss its terminal), so the marker rode inside the
 *   same atomic batch ([snapshotSwitchHistory] with `markIncomplete`).
 * - `"dangling excluded"`: the copy succeeded minus dropped dangling
 *   families ([snapshotSwitchHistory] auto-marks when a family is skipped),
 *   so "copy failed" would mislead — the rows landed, one family didn't.
 * - `"byte budget exceeded"` (R6-5): the prefix blew
 *   [SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS], so the copy carries zero rows (all-or-nothing
 *   abandon, never a truncated half prefix) plus this marker in the same
 *   atomic batch.
 */
internal fun snapshotIncompleteMarkerText(oldModel: String, newModel: String, reason: String = "copy failed"): String =
    "snapshot incomplete $oldModel -> $newModel [history incomplete: $reason]"

internal fun isSnapshotIncompleteMarker(text: String): Boolean =
    text.startsWith("snapshot incomplete ")

/**
 * Bounded scan for an R5-4 incomplete-snapshot marker (see
 * [snapshotSwitchHistory]). True when a fork snapshot failed and the
 * best-effort marker landed. Scans the head AND the tail 16: atomic stores
 * (Room / fake) roll the failed batch back to zero rows, so the marker is
 * row 1 (head); a non-atomic fallback store (default
 * [dev.librepocket.session.SessionStore.appendEvents]) may leave a partial
 * prefix with the marker trailing it (tail). Best-effort callers swallow
 * failures as "no marker" (only the notice is at stake). The scan matches
 * only the `snapshot incomplete ` prefix, so every [reason] cause
 * (`copy failed` / `ledger unsettled` / `dangling excluded` /
 * `byte budget exceeded`, see [snapshotIncompleteMarkerText]) re-warns the
 * same notice — causes are not distinguished here by design.
 */
internal suspend fun hasIncompleteSnapshotMarker(store: SessionStore, sessionId: String): Boolean {
    val head = store.loadEvents(sessionId, 0, SNAPSHOT_INCOMPLETE_SCAN_LIMIT)
    if (head.any { it.kind == SNAPSHOT_INCOMPLETE_MARKER_KIND && isSnapshotIncompleteMarker(it.text) }) return true
    val tail = store.loadTailEvents(sessionId, SNAPSHOT_INCOMPLETE_SCAN_LIMIT)
    return tail.any { it.kind == SNAPSHOT_INCOMPLETE_MARKER_KIND && isSnapshotIncompleteMarker(it.text) }
}

/**
 * R4-2 (Stage 4) durable fork snapshot, R5-3 projection: after a same-origin
 * model switch creates a fresh transcript session, persist the carried prefix
 * INTO the new session so a restart / `openSession(newId)` still sees the old
 * context plus the new turns. Cross-provider/origin forks persist only the
 * marker (the old rows are never forwarded to the new destination).
 *
 * Layout: the audit marker is row 1 (head-anchored so reopen rediscovers it
 * with one tiny bounded head scan after later turns), followed by the
 * [loadSwitchSnapshotEvents] UI replay/audit prefix oldest-first (every
 * `user`/`assistant` row INCLUDING partials, verbatim `isPartial` /
 * `failureReason` / `parentRunId` / `attemptIndex` / `isFinal` / `createdAt`,
 * plus terminal `system` interrupted/cancelled/legacy-failure marks, plus
 * every payload-free `reasoning` marker verbatim — R8-4).
 * Copied rows keep their family linkage and `createdAt` verbatim; `seq` is
 * reassigned by the store; texts are stored verbatim (truncation stays a
 * read-path concern via [cappedHistoryText] on hydrate). The copy is bounded
 * newest-first to [SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP] rows AND
 * [SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS] total chars (R6-5: an over-budget prefix is
 * abandoned whole — zero rows + incomplete marker, never a half prefix, see
 * below), so marker + copy never exceeds
 * [SnapshotBudget.HISTORY_LOAD_CAP] (R28-1: a full 1999-row prefix with an
 * incomplete marker is clamped to 1998 carried rows + 2 markers before the
 * atomic write, so the same-batch auto-prune never drops the head switch
 * marker); pre- and post-restart model contexts still agree
 * because reopen re-applies the same `!isPartial` filter to the copied rows
 * (provider payload unchanged).
 *
 * R5-3 fix: before R5-3 this copied [loadModelHistoryEvents] (the model
 * projection, `!isPartial`), so a cancelled partial (`user` + terminal
 * `assistant isPartial=1/isFinal=1`), a terminal failure (plus
 * `failureReason`), and a backoff-gap cancel (`partial isFinal=0` + `retry`
 * + `system cancelled`) all lost their terminals/marks; the reopen backfill
 * then mis-read the orphaned user row as stranded and wrote a false
 * INTERRUPTED marker. Copying the UI/audit projection preserves those rows,
 * so [findDanglingRunIds] sees the same terminals in the fork as in the
 * source. Deliberately NOT copied: usage accounting rows (`usage …`),
 * `model switched …` markers (each fork owns exactly one, never a copy),
 * and `retry`/`tool`/`steer` notices (neither replay nor terminal).
 *
 * R5-4 atomicity (implemented): the marker and every copied row persist as
 * ONE all-or-nothing batch ([SessionStore.appendEvents]) — the prefix is
 * fully read ([loadSwitchSnapshotEvents]) before anything is written, then
 * marker + copies commit in a single store transaction (Room) or under a
 * snapshot-rollback batch (fake). A mid-copy store failure therefore throws
 * with ZERO snapshot rows in the fork: a half snapshot (marker + N-1 rows,
 * which the reopen backfill would misread as a stranded turn and stamp with
 * a false INTERRUPTED) is structurally impossible on atomic stores. Callers
 * treat the throw as bookkeeping: the live turn already carries the prefix
 * in memory, so
 * [dev.librepocket.agent.ui.chat.ChatViewModel.ensureSession] best-effort
 * appends one system incomplete marker ([snapshotIncompleteMarkerText]) and
 * surfaces HISTORY_SNAPSHOT_INCOMPLETE (fail-open; a restart then degrades
 * to the new rows only, i.e. pre-R4-2 behavior, with the warning
 * re-surfaced by [hasIncompleteSnapshotMarker]).
 *
 * Known limitation: prune is keep-newest
 * (`dev.librepocket.session.SessionDao.deleteEventsBeyondLimit` drops the
 * oldest rows first), so the head marker goes first; the marker + copy cap
 * now equals [dev.librepocket.session.PrunePolicy.maxEventsPerSession]
 * (2000), so a full snapshot only loses its marker when a LATER prune sweep
 * drops the head (same as before, but one slot safer). R28-1: the
 * incomplete two-marker batch is clamped pre-write (1998 carried + 2
 * markers), so the loss can no longer happen in the same batch's auto-prune.
 * [hasWithheldSwitchMarker] only scans the head 16. A pruned marker only
 * drops the restart notice — the withhold itself is structural (rows never
 * copied), so isolation is unchanged; an `openSession` scan failure stays
 * "no marker", as before.
 *
 * Failure semantics: THROWS on store failure with zero snapshot rows
 * persisted (all-or-nothing on atomic stores — Room and the in-memory fake;
 * a non-atomic [SessionStore.appendEvents] fallback may leave a partial
 * prefix, still flagged by the caller's incomplete marker). The caller
 * ([dev.librepocket.agent.ui.chat.ChatViewModel.ensureSession]) treats the
 * copy as best-effort (the live turn already carries the prefix in memory, so
 * a bookkeeping failure must not fail the send; restart then degrades to the
 * new rows only, i.e. pre-R4-2 behavior, plus the incomplete marker +
 * HISTORY_SNAPSHOT_INCOMPLETE notice). Cancellation propagates before any
 * write (read phase) or aborts the batch (write phase) — never a partial.
 *
 * Concurrency: callers hold the single-flight session mutex, and every fork
 * targets a brand-new session id, so overlapping switches cannot interleave
 * rows. Rollback rides on the existing `discardUnattached(deleteTranscriptRow
 * = true)` path (`deleteSession` cascades the copied events per the
 * [SessionStore] interface contract, enforced in Room by the FK CASCADE —
 * the in-memory test fake mirrors the cascade; no orphan rows survive under
 * the deleted id).
 *
 * Redaction: [RoomSessionStore] re-runs the redactor on every write; the
 * copied texts are already redacted and replacements carry no secret shapes,
 * so the second pass is a no-op. The 100k per-row store bound applies as on
 * any other write.
 *
 * @return number of history rows copied (0 when withheld or byte-abandoned).
 *
 * R6-5 aggregate byte cap (no schema change): the prefix load now enforces
 * `min(1999 rows, [SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS] chars)` newest-first. When the
 * char budget excludes any row (`droppedByBytes > 0`) the copy carries ZERO
 * rows and rides one system incomplete row (reason `"byte budget exceeded"`)
 * in the same atomic batch — a truncated half prefix risks a family split
 * (orphan-head user row that the reopen backfill would mis-stamp as
 * INTERRUPTED), so the over-budget fork degrades to
 * pre-R4-2 (new rows only) with the same HISTORY_SNAPSHOT_INCOMPLETE
 * re-warning as every other incomplete cause (no new notice kind;
 * [hasIncompleteSnapshotMarker] is prefix-based and cause-agnostic).
 * Row-cap drops stay silent as before (legacy contract, frozen); the new
 * char cap adopts fail-loud instead.
 *
 * R6-3 streaming-switch hardening (no schema change):
 * - Dangling families ([findDanglingRunIds] on the loaded prefix) are ALWAYS
 *   dropped before the copy (Phase2 fail-closed: unconditional pure-memory
 *   filter, no I/O — no longer gated on [excludeDanglingFamilies]), so a
 *   fork taken while the old live turn had not settled yet never copies an
 *   orphan `user(L)` that the reopen backfill would mis-stamp as INTERRUPTED
 *   (degrades to pre-R4-2: new rows only for that family, with the warning
 *   below). No-op when the prefix has no dangling families.
 *   [excludeDanglingFamilies] is retained for call-site compat but no longer
 *   gates the filter (passing false with a dangling prefix still drops + warns).
 * - [markIncomplete]: when true, one system incomplete row
 *   ([snapshotIncompleteMarkerText] with reason `"ledger unsettled"`) rides
 *   inside the SAME atomic batch right after the switch marker, so a later
 *   `openSession` re-surfaces HISTORY_SNAPSHOT_INCOMPLETE even though the
 *   copy itself succeeded. The incomplete marker is deliberately NOT a
 *   [findDanglingRunIds] terminal, so callers must combine it with
 *   [excludeDanglingFamilies] (otherwise the orphan copy would still gain a
 *   false INTERRUPTED on reopen).
 *   A dropped-dangling copy also forces the incomplete row even when
 *   [markIncomplete] is false (reason `"dangling excluded"`, since the copy
 *   itself landed): losing a family silently must still warn.
 */
internal suspend fun snapshotSwitchHistory(
    store: SessionStore,
    fromSessionId: String,
    toSessionId: String,
    oldModel: String,
    newModel: String,
    historyWithheld: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
    excludeDanglingFamilies: Boolean = false,
    markIncomplete: Boolean = false,
): Int {
    require(fromSessionId != toSessionId) { "snapshot source and destination must differ" }
    // R5-4: read the whole prefix BEFORE writing anything, then persist
    // marker + copies as one atomic batch (single transaction on Room,
    // snapshot-rollback on the fake). The marker rides inside the same batch
    // so a marker-write failure also leaves zero rows — never a marker-only
    // or half-copied ledger.
    val snapshot = if (historyWithheld) {
        SwitchSnapshotLoad(emptyList(), 0)
    } else {
        loadSwitchSnapshotEvents(store, fromSessionId)
    }
    // R6-5 byte-budget abandon (all-or-nothing, never a half prefix): when
    // the char budget excluded any row, the copy carries ZERO rows — a
    // truncated half prefix risks a family split (orphan-head user row the
    // reopen backfill would mis-stamp as INTERRUPTED), so the over-budget
    // fork degrades to pre-R4-2 (new rows only) with one incomplete marker,
    // exactly like a failed copy. Withheld forks already carry zero rows.
    val byteAbandoned = snapshot.droppedByBytes > 0
    val carried: List<TranscriptEvent> = if (byteAbandoned) emptyList() else snapshot.events
    // R6-3: drop orphan families before the copy (see above). Withheld and
    // byte-abandoned forks already copy zero rows, so this is a no-op there.
    // Phase2 fail-closed: the filter runs UNCONDITIONALLY (no longer gated on
    // excludeDanglingFamilies) — a pure in-memory findDanglingRunIds pass, no
    // I/O. A clean-fence fork whose prefix still dangles (settled-live race,
    // or a genuine stranded turn) degrades the same way as a streaming fork:
    // the orphan user row never copies (it would gain a false INTERRUPTED on
    // reopen) and the batch rides the "dangling excluded" incomplete marker.
    // No-op when the prefix has no dangling families. The
    // excludeDanglingFamilies flag is retained for call-site compat but no
    // longer gates the filter.
    val prefix: List<TranscriptEvent> = if (carried.isNotEmpty()) {
        val dangling = findDanglingRunIds(carried).toSet()
        if (dangling.isEmpty()) carried else carried.filterNot { familyOf(it) in dangling }
    } else {
        carried
    }
    val droppedDangling = carried.size - prefix.size
    val incomplete = markIncomplete || droppedDangling > 0 || byteAbandoned
    // R28-1 write-time clamp (Current): the batch is switch marker + optional
    // incomplete marker + carried prefix. A full 1999-row prefix with
    // markIncomplete=true would otherwise build a 2001-row batch, and the
    // same-batch auto-prune (keep 2000) would delete the head switch audit
    // marker. Clamp the carried prefix to the marker budget first, then
    // re-run the head-orphan trim so the clamp cut never strands a user-less
    // assistant at the head (the trim may shrink below the cap —
    // conservative direction). Withheld/byte-abandoned forks carry zero rows
    // and never clamp.
    val markerCount = if (incomplete) 2 else 1
    val maxPrefix = SnapshotBudget.HISTORY_LOAD_CAP - markerCount
    val clampedPrefix: List<TranscriptEvent> = if (prefix.size > maxPrefix) {
        dropHeadOrphanAssistantFamilies(prefix.drop(prefix.size - maxPrefix))
    } else {
        prefix
    }
    // R6-3 cause tag (see snapshotIncompleteMarkerText): a fence failure
    // outranks a drop — when both hold, the ledger may miss its terminal.
    // A byte abandon outranks a dangling drop (the two are mutually exclusive
    // anyway: an abandoned prefix never reaches the family filter).
    val incompleteReason =
        if (markIncomplete) {
            "ledger unsettled"
        } else if (byteAbandoned) {
            "byte budget exceeded"
        } else {
            "dangling excluded"
        }
    val batch = ArrayList<TranscriptEvent>(clampedPrefix.size + 2)
    batch.add(
        TranscriptEvent(
            sessionId = toSessionId,
            runId = "switch-${java.util.UUID.randomUUID()}",
            kind = MODEL_SWITCH_MARKER_KIND,
            text = modelSwitchMarkerText(oldModel, newModel, historyWithheld),
            createdAt = nowMillis,
        ),
    )
    if (incomplete) {
        batch.add(
            TranscriptEvent(
                sessionId = toSessionId,
                runId = "snapshot-incomplete-${java.util.UUID.randomUUID()}",
                kind = SNAPSHOT_INCOMPLETE_MARKER_KIND,
                text = snapshotIncompleteMarkerText(oldModel, newModel, incompleteReason),
                createdAt = nowMillis,
            ),
        )
    }
    for (event in clampedPrefix) {
        batch.add(event.copy(seq = 0, sessionId = toSessionId))
    }
    store.appendEvents(batch)
    return clampedPrefix.size
}

/**
 * Bounded head scan for a cross-provider fork marker (see
 * [snapshotSwitchHistory]). True when this session was forked across a
 * provider/origin boundary with its old prefix withheld. Best-effort callers
 * swallow failures as "no marker" (the withhold itself is structural — the
 * rows were never copied — so only the notice is at stake).
 */
internal suspend fun hasWithheldSwitchMarker(store: SessionStore, sessionId: String): Boolean {
    val head = store.loadEvents(sessionId, 0, MODEL_SWITCH_MARKER_SCAN_LIMIT)
    return head.any { it.kind == MODEL_SWITCH_MARKER_KIND && modelSwitchMarkerWithheld(it.text) }
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
