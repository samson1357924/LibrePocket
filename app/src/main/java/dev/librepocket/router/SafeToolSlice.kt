package dev.librepocket.router

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ToolCall
import dev.librepocket.provider.ToolSchema
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import kotlin.coroutines.coroutineContext

/**
 * P2 safe vertical slice (#19): the single `executionReady` tool's closed loop.
 * Opt-in by construction: production sessions are built without a slice
 * (`ChatSessionFactory.toolSlice=null`, records-only); tests attach this loop.
 *
 * Owns exactly one tool ([TOOL_NAME] = `lsp.symbols`, READ-only, deterministic
 * [LspTools.StubBackend] echo): request-tools offer → schema validation →
 * latest scope/policy re-check → executor → typed result → continuation
 * messages. All other 40 tools stay `executionReady=false` and are never
 * executed here (unknown names are typed denials, never dispatched).
 *
 * Stage order per model tool call (fresh every call — "latest" policy/scope):
 * 1. per-turn budget ([maxCallsPerTurn]; excess is [DenyCodes.BUDGET_EXCEEDED]);
 * 2. cancel check ([ensureActive]: cancel before execution aborts with zero
 *    further effects — already-paired results are discarded by the throw,
 *    the backend is never touched afterwards);
 * 3. registry lookup + `executionReady` + single-tool READ-only gate
 *    (re-graded entries deny `SCOPE_DENIED` with a side-effect detail;
 *    scope denials surface [DenyReason]);
 * 4. latest [ProjectionContext] scope re-check;
 * 5. arguments schema validation (JSON object, required non-blank `query`,
 *    optional `limit` int clamped 1..200, optional `pathPrefix` string
 *    without traversal: no `..`, no leading `/`, no backslash, no `:`
 *    (scheme/proot/drive-absolute roots), no percent-escapes, and no
 *    Unicode FORMAT code points (BMP U+200B/U+202E/U+FEFF and
 *    supplementary U+E0001/U+E0020 — the scan is by CODE POINT, so a
 *    surrogate-pair format character cannot slip through) — they would
 *    otherwise slip past the leading-`/` check and hide an absolute
 *    scope behind a zero-width/bidi mark);
 * 6. fresh `tool.exec` [PolicyStore.evaluateFresh] (ALLOW only; ASK/DENY/
 *    failure all deny);
 * 7. [PrivilegeGate] (READ passes without confirmation; anything else denies);
 * 8. executor ([LspTools.symbols] on the injected suspend backend — default
 *    [LspTools.StubBackend] is pure computation, no IO; injected backends
 *    must honor the same cancel contract and never return secrets, see
 *    [LspTools.Backend]). The call runs under the registry entry's
 *    `ToolAnnotations.timeoutMs`; a timeout is a typed `EXEC_DENIED`, never a
 *    turn cancellation. A NON-POSITIVE budget (registry annotation or the
 *    [backendTimeoutMillisForTest] seam) is an IMMEDIATE typed timeout:
 *    `withTimeout` with a non-positive deadline throws before the backend
 *    body is entered, so the outcome is the same typed `EXEC_DENIED`
 *    ("backend timed out") with ZERO backend invocations; a
 *    backend that fails with its OWN `TimeoutCancellationException` (its
 *    internal timer, not this slice's) maps to `EXEC_ERROR` — the slice never
 *    forges an `EXEC_DENIED` timeout it did not observe.
 *
 * A blank model call id denies the WHOLE round with `SCHEMA_INVALID`
 * (before budget/dedup, zero backend invocations): a blank id cannot carry
 * a provider pairing id, so executing it would produce an unpairable
 * continuation. The typed denial is still CONTINUABLE: the continuation
 * builder re-binds a blank call id to a deterministic synthetic wire id
 * ([blankWireCallId], `call_blank_slice_<index>`) so the denial rides the
 * wire as a normally paired `call`/`tool` message instead of being dropped.
 * A synthetic id that collides with a MODEL-supplied id in the same round
 * is re-bound deterministically (`_2`, `_3`, …) instead of failing the
 * round — the model id is never touched, and the wire ids stay unique.
 *
 * Denials are TYPED RESULTS (not throws, except [CancellationException]):
 * every model call gets a paired `tool` message (call-id pairing preserved
 * per the T1 provider contracts), so DENY/cancel/schema-error paths are
 * observable with backend invocation count zero (pinned by tests).
 * Backend exceptions (non-cancel) are mapped to typed `EXEC_ERROR`
 * denials, never leaked as turn failures.
 *
 * Pure JVM, no Android dependencies. The turn engine ([dev.librepocket.chat
 * .TurnController]) attaches this loop only when constructed with it
 * (default-off: production composition attaches none, staying records-only).
 *
 * Shareability: one instance is shared by every session a factory builds, so
 * construction-captured state (`ctxSupplier`, `policy`, `backend`, `clock`)
 * must be safe for concurrent turns — keep the slice stateless; per-turn
 * state lives in the turn engine, not here. Sanctioned exceptions
 * ([F4]): [beforeExecuteForTest] and [backendTimeoutMillisForTest] are
 * internal `@Volatile` TEST seams only — production composition never
 * installs them, and they are cross-thread-visible exactly so a cancel test
 * parking the loop from the test thread is seen by the turn dispatcher
 * thread (and a timeout test can shorten the annotation budget). When a
 * second tool wires, retire
 * this slice (or generalize it): [offeredTools] stays pinned to [TOOL_NAME]
 * and would silently hide the new tool otherwise.
 */
class SafeToolSlice(
    private val ctxSupplier: () -> ProjectionContext = { ProjectionContext() },
    private val policy: PolicyStore,
    private val backend: LspTools.Backend = LspTools.StubBackend,
    private val clock: Clock = Clock.systemDefaultZone(),
    val maxCallsPerTurn: Int = 1,
) {
    init {
        require(maxCallsPerTurn in 1..8) { "maxCallsPerTurn must be 1..8, was $maxCallsPerTurn" }
    }
    companion object {
        /** The single slice tool (registry `executionReady=true`; loop is opt-in/test-attached). */
        const val TOOL_NAME: String = "lsp.symbols"

        /** Execution-basis policy action (known action; default ASK → slice needs ALLOW). */
        const val POLICY_ACTION = "tool.exec"

        /**
         * Per-string cap for result JSON: model-controlled `query` and
         * backend-controlled symbol fields are truncated (per-string only,
         * not a total bound), never passed through unbounded into the
         * transcript/provider continuation. Ok echoes carry a
         * `…[truncated N→cap]` marker so the model can tell echo from input;
         * deny echoes are silently capped (codes are fixed constants).
         * Lengths are measured on the POST-redaction string: redaction can
         * inflate a field (e.g. `⟦REDACTED:EMAIL⟧`), so a ≤cap raw string may
         * exceed the cap after redaction — truncation is decided there, and
         * the marker reports the post-redaction length. Truncation goes
         * through [cappedTake] (surrogate-safe): a plain `take` at the cap
         * could split a UTF-16 surrogate pair and emit a lone surrogate.
         */
        const val MAX_STRING_CHARS = 500

        /**
         * Raw backend-owned field bound applied BEFORE `Redactor.redact`.
         * Redaction makes multiple full passes/allocations; an unbounded
         * backend string (future MCP bridge) could otherwise burn CPU/heap
         * before the 500-char echo cap. Over-bound values are omitted WHOLE —
         * never truncated to a prefix — because suffix-anchored Redactor rules
         * (URL credentials, emails) would miss a suffix beyond the cut and the
         * retained prefix could carry the secret. The omitted suffix never
         * reaches the wire; omission is reflected in `truncated`.
         */
        const val RAW_BACKEND_FIELD_MAX_CHARS = MAX_STRING_CHARS * 4
        /** Per-input cap for model-controlled `query` / `pathPrefix` (fail-closed). */
        const val MAX_INPUT_CHARS = 4096
        /** Total cap for serialized tool result JSON (fail-closed continuation budget). */
        const val MAX_RESULT_CHARS = 20_000

        /**
         * Length of the deterministic pairing suffix appended to an
         * over-cap redacted id: the `#` separator plus a 32-hex (128-bit)
         * SHA-256 digest prefix — `1 + 16 * 2` UTF-16 units. Exposed so the
         * bounded-id shape (and the surrogate cut it induces) is pinned by
         * ONE constant instead of a hard-coded `1 + 32` in the tests.
         */
        internal const val PAIRING_SUFFIX_CHARS = 1 + 16 * 2

        /**
         * Deterministic synthetic wire id for a BLANK model call id at
         * `index` within the round: `call_blank_slice_<index>`. A blank id
         * cannot ride the wire (a provider pairing validator rejects an
         * empty `tool_call_id`), but the round must still be continuable —
         * the typed denial is paired with this id instead of being dropped.
         *
         * The `slice` infix keeps this synthetic namespace DISJOINT from the
         * turn engine's own synthetic ids (`call_blank_<n>` /
         * `call_blank_pending_<n>`): both layers can synthesize an id for
         * the same round, and a shared namespace would let one layer's
         * synthetic id alias the other layer's model-visible id.
         *
         * The index makes the id deterministic (same round → same id) and
         * keeps distinct blank calls distinct. A model-supplied id that
         * happens to equal a synthetic one does NOT fail the round: the
         * wire-id builder (`wireCallIds`) re-binds the synthetic id
         * deterministically (`_2`, `_3`, …) so the wire ids stay unique and
         * the model-supplied id is never touched.
         */
        internal fun blankWireCallId(index: Int): String = "call_blank_slice_$index"

        /**
         * Gross args pre-parse bound for the model's raw argument text
         * ([MAX_INPUT_CHARS] × 4): the single source for the identical
         * fan-close in [parseArgs] and [redactedArgsEcho].
         */
        const val MAX_GROSS_ARGS_CHARS = MAX_INPUT_CHARS * 4
        /** Nesting-depth fan-close bound shared by [parseArgs]/[redactedArgsEcho] (see [jsonNestingDepth]). */
        const val MAX_NESTING_DEPTH = 32

        /**
         * Surrogate-safe cap slice, the single replacement for every
         * `.take([MAX_STRING_CHARS])` string-cap in this file (result-JSON
         * fields, echoes, call ids, continuation re-binds): a plain `take`
         * at the cap can cut BETWEEN the two halves of a well-formed
         * UTF-16 surrogate pair (the cap-1 unit a high surrogate) and emit
         * a lone surrogate into JSON that the escapers/serializers pass
         * through raw — at UTF-8 encode OkHttp/okio replaces such a lone
         * surrogate with `?` (0x3F), NOT U+FFFD, so the wire value would
         * differ from the parsed value. It steps back over the trailing
         * high-surrogate run at the cut: for well-formed UTF-16 (a high
         * surrogate's low partner is always the very next unit) this is
         * exactly ONE unit, so the capped length is `[cap]-1` on a genuine
         * split and exactly `[cap]` otherwise.
         *
         * SCOPE (deliberately narrow): this only prevents SPLITTING a
         * WELL-FORMED pair at the cut. It does NOT sanitize a
         * PRE-EXISTING lone surrogate in the input — that one still rides
         * the wire and is still encoded as `?` (0x3F) at UTF-8 encode; the
         * walk back can only extend it, never split a pair that was not
         * already malformed. Work is bounded by `[cap]` (≤ `cap+1` in the
         * split case counting the marker afterwards).
         */
        internal fun cappedTake(value: String, cap: Int): String {
            if (value.length <= cap) return value
            var end = cap
            while (end > 0 && value[end - 1].code in 0xD800..0xDBFF) {
                end--
            }
            return value.substring(0, end)
        }

        /**
         * Execution-dedup pairing key for the turn engine: the EXACT bounded
         * redaction that [continuationMessages] applies to call ids (the form
         * every provider pairing validator and each provider-side
         * `tool_call_id` sees on the wire). The turn engine routes its
         * dedup through this seam instead of importing the redactor — two
         * distinct raw ids that redact to one string must never both
         * execute, or the re-bound continuation would carry a duplicate
         * paired id and fail closed with the non-retryable
         * `TOOL_CALL_DUPLICATE_ID` AFTER both calls already ran. The
         * durable audit record keeps raw ids; only the execution
         * candidate/set key is redacted.
         */
        internal fun redactedPairingId(callId: String): String = boundedRedactedId(callId)

        /**
         * Bounded, collision-disambiguated redacted id, the single shape for
         * every wire/pairing call id. Redaction runs first (it can inflate a
         * string), then short ids pass through unchanged. A redacted id
         * longer than [MAX_STRING_CHARS] keeps a surrogate-safe capped prefix
         * plus a deterministic `#` + 32-hex (128-bit) SHA-256 suffix of the
         * FULL redacted id (suffix length: [PAIRING_SUFFIX_CHARS]): two long
         * ids that share the cap-length prefix (or
         * redact to a shared prefix) stay distinct instead of collapsing into
         * one pairing key (which would silently drop the second execution).
         * The 128-bit suffix keeps collision probability negligible even for
         * adversarial inputs. The result never exceeds [MAX_STRING_CHARS].
         */
        internal fun boundedRedactedId(callId: String): String {
            val redacted = dev.librepocket.redact.Redactor.redact(callId).text
            if (redacted.length <= MAX_STRING_CHARS) return redacted
            val digest = MessageDigest.getInstance("SHA-256").digest(redacted.toByteArray(Charsets.UTF_8))
            val hex = digest.take(16).joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            val suffix = "#$hex"
            return cappedTake(redacted, MAX_STRING_CHARS - PAIRING_SUFFIX_CHARS) + suffix
        }

        /**
         * Exact receipt the secret-keyed structural walk emits. Must stay
         * byte-equal to the value receipt Redactor's R3 (JSON_KEY_FIELD)
         * substitutes ("⟦REDACTED⟧") so the structural echo agrees with the
         * raw-text scanner on the shape; a local copy is unavoidable because
         * Redactor keeps the constant private.
         */
        private const val SECRET_VALUE_RECEIPT = "⟦REDACTED⟧"

        /**
         * Secret-bearing KEY families: the same vocabulary Redactor's R3
         * scanner supports (`secret|token|password|passwd|auth|api[_-]?key`,
         * case-insensitive). Unanchored on purpose: the structural walk sees
         * bare key names that can never satisfy the raw-text rules (which
         * need a `:`/`=`), so over-matching helper keys (`apiKeyHint`,
         * `oauthAuth`) is fail-safe over-redaction of an echo, never a leak;
         * contains-style matching is deliberate collateral — benign keys
         * merely containing a family word (`author`, `tokenizer`,
         * `secretary`) are over-redacted on purpose too. Any value under a
         * secret-family key — including an object or array — collapses to the
         * one fixed receipt: recursing into a secret-keyed object would echo
         * inner values under non-secret keys (`{"password":{"z":"<value>"}}`),
         * which is a structural bypass. Over-redaction is intentional.
         */
        private val SECRET_KEY_FAMILY =
            Regex("(?i)(secret|token|password|passwd|auth|api[_-]?key)")

        /**
         * Wire-safe redacted echo of a tool call's arguments: parse-then-redact
         * (token-level inside each JSON string, and KEY-AWARE: any member
         * value under a secret-family key ([SECRET_KEY_FAMILY]) collapses to
         * the fixed [SECRET_VALUE_RECEIPT], because bare key names never match
         * the secret rules and plain values carry no secret shape —
         * isolation-only redaction leaks `{"password":"…"}` verbatim) and
         * re-serialize a still-valid JSON object. Provider adapters re-parse this echo before
         * serializing the continuation (`validateToolPairing`), so a raw-text
         * redact or a `take` would corrupt the structure (several redaction
         * rules swallow the terminating `"`/`}` of a value) and turn a working
         * round into a non-retryable `TOOL_ARGUMENTS_INVALID` failure.
         *
         * All failure modes emit the same fixed minimal object (fail-closed,
         * still a valid object): the input exceeds [parseArgs]'s gross size cap
         * ([MAX_GROSS_ARGS_CHARS] = [MAX_INPUT_CHARS] × 4) or its nesting-depth
         * fan-close (> [MAX_NESTING_DEPTH] — see [jsonNestingDepth]), parsing
         * itself fails, or the re-serialized form exceeds [MAX_INPUT_CHARS].
         *
         * The depth fan-close is the ONLY structural recursion bound here and it
         * is deliberately checked BEFORE parsing: this echo is parsed from the
         * model's raw argument text, so embedded JSON is not bounded by the
         * provider wire frame parser (MiniJson.MAX_DEPTH applies to the wire
         * frame, not to a JSON string carried inside it). Skipping the guard
         * would let a within-budget deeply nested argument reach the recursive
         * [redactJsonStrings] walk.
         *
         * internal (never public) so the turn engine can reuse the identical
         * redaction posture for its provider-visible inline tool marker — the
         * transcript audit record there stays RAW by design.
         */
        internal fun redactedArgsEcho(argumentsJson: String): String {
            val fallback = buildJsonObject { put("echo", "args redacted/capped") }.toString()
            // Same fan-close as [parseArgs] ([MAX_GROSS_ARGS_CHARS] +
            // [MAX_NESTING_DEPTH]), applied with a cheap char scan BEFORE any
            // parse/recursion. String contents are ignored by the scan, so it
            // bounds structure only.
            if (argumentsJson.length > MAX_GROSS_ARGS_CHARS ||
                jsonNestingDepth(argumentsJson) > MAX_NESTING_DEPTH
            ) {
                return fallback
            }
            val root = try {
                Json.parseToJsonElement(argumentsJson)
            } catch (_: Exception) {
                return fallback
            }
            val redacted = redactJsonStrings(root).toString()
            return if (redacted.length <= MAX_INPUT_CHARS) redacted else fallback
        }

        /** Deep-walks a JSON value, redacting object keys and every string
         *  primitive's content (keys are model-controlled and ride the echo
         *  too), KEY-AWARE: a member under a secret-family key
         *  ([SECRET_KEY_FAMILY]) collapses the ENTIRE value — string, array,
         *  or object — to the fixed [SECRET_VALUE_RECEIPT] (isolated value
         *  redaction alone cannot catch secret-keyed plain values, and
         *  recursing into a secret-keyed object would leak its inner
         *  non-secret-keyed values). ANY value under a secret key — including
         *  number/boolean/null primitives — collapses to the fixed receipt, so
         *  no secret-shaped value can echo.
         *  Recursion depth is bounded by the caller's pre-parse fan-close
         *  ([redactedArgsEcho] rejects depth > [MAX_NESTING_DEPTH] via
         *  [jsonNestingDepth], the same bound [parseArgs] fans closed with) —
         *  never call this on unguarded input. */
        private fun redactJsonStrings(element: JsonElement): JsonElement = when (element) {
            is JsonPrimitive ->
                if (element.isString) {
                    JsonPrimitive(dev.librepocket.redact.Redactor.redact(element.content).text)
                } else {
                    element
                }
            is JsonObject -> JsonObject(
                // Post-parse keys are already unique (duplicate raw keys
                // collapse at parse); DISTINCT raw keys can still redact to
                // identical text, so `associate` collapses them last-wins,
                // silently dropping one already-redacted echo member — lossy
                // yet safe (over-redaction only): the echo stays valid JSON.
                element.entries.associate { (k, v) ->
                    dev.librepocket.redact.Redactor.redact(k).text to when {
                        // Pre-redact key decides: the receipt replaces the
                        // whole value, whatever its JSON shape.
                        SECRET_KEY_FAMILY.containsMatchIn(k) -> secretValueReceipt()
                        else -> redactJsonStrings(v)
                    }
                },
            )
            is JsonArray -> JsonArray(element.map { redactJsonStrings(it) })
        }

        /** Secret-keyed member value: ANY value (string, number, boolean,
         *  null, array, object) becomes the single fixed receipt. Even a
         *  primitive number/bool/null under a secret-family key is collapsed
         *  so it cannot echo a secret-shaped value. The replaced value is not
         *  even inspected (its shape must not influence the output). */
        private fun secretValueReceipt(): JsonElement = JsonPrimitive(SECRET_VALUE_RECEIPT)

        /** Computes max JSON nesting depth while ignoring string contents. */
        private fun jsonNestingDepth(s: String): Int {
            var depth = 0
            var max = 0
            var inStr = false
            var esc = false
            for (c in s) {
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{', '[' -> { depth++; if (depth > max) max = depth }
                        '}', ']' -> { if (depth > 0) depth-- }
                    }
                }
            }
            return max
        }
    }

    /**
     * Test seam (internal: production composition leaves null) run before
     * EACH execution (after cancel check, before any gate): lets cancel
     * tests park the loop in the deny/execute window and then cancel,
     * proving zero backend invocations. Read once into a local before
     * invocation so a concurrent re-install cannot swap the hook mid-call;
     * volatile so a mid-turn install from the test thread is visible to the
     * turn dispatcher thread.
     */
    @Volatile
    internal var beforeExecuteForTest: (suspend () -> Unit)? = null

    /**
     * Test seam (internal: production composition leaves null) shortening the
     * per-call backend budget below the registry annotation so timeout tests
     * do not wait the production `ToolAnnotations.timeoutMs` (10 s). Read once
     * before the wrapped call; non-positive values (and a non-positive
     * `ToolAnnotations.timeoutMs`) are an IMMEDIATE typed timeout — the
     * `withTimeout` deadline expires before the backend body runs, so the
     * outcome is EXEC_DENIED "backend timed out" with zero invocations.
     * Volatile for the same cross-thread install reason as
     * [beforeExecuteForTest].
     */
    @Volatile
    internal var backendTimeoutMillisForTest: Long? = null

    /** Typed deny codes (audit-stable, user-visible in the paired denial). */
    object DenyCodes {
        const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
        const val NOT_READY = "NOT_READY"
        const val SCOPE_DENIED = "SCOPE_DENIED"
        const val SCHEMA_INVALID = "SCHEMA_INVALID"
        const val POLICY_DENIED = "POLICY_DENIED"
        const val NEED_CONFIRM = "NEED_CONFIRM"
        // Reserved for future WRITE/PRIVILEGED bridges: the current slice
        // rejects non-READ entries with SCOPE_DENIED before the gate, so no
        // slice path produces NEED_CONFIRM today. EXEC_DENIED is reachable
        // only for a backend timeout (blank-query Unavailable is rejected by
        // schema validation first).
        const val EXEC_DENIED = "EXEC_DENIED"
        const val EXEC_ERROR = "EXEC_ERROR"
        const val BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    }

    /**
     * One paired outcome: executed value ([denied] = false) or typed denial
     * ([denied] = true, [denyCode] set). [resultJson] always rides the `tool`
     * message so call-id pairing survives denials too.
     *
     * [callId] is the RAW id of the call this result belongs to — the
     * internal pairing record, kept verbatim for the audit trail. It is
     * BLANK only for the blank-call-id denial (the whole round denies with
     * `SCHEMA_INVALID`); the continuation builder re-binds such a blank id
     * to a deterministic synthetic wire id ([blankWireCallId]) so no blank
     * id ever lands on the wire.
     */
    data class ToolResult(
        val callId: String,
        val name: String,
        val resultJson: String,
        val denied: Boolean = false,
        val denyCode: String? = null,
    )

    /**
     * Schemas the model may call this round: `visibleTools ∩ {slice}`.
     * Empty (switch off / flavor-blocked / not-ready / a throwing
     * [ctxSupplier]) means pure chat — the request carries no tools and
     * [execute] refuses everything. A projection-supplier failure is treated
     * as "no projection" rather than a turn failure.
     */
    fun offeredTools(): List<ToolSchema> {
        val ctx = try {
            ctxSupplier()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return ToolRegistry.visibleTools(ctx)
            .filter { it.name == TOOL_NAME }
            .map { ToolSchema(it.name, it.description, it.jsonSchema) }
    }

    /**
     * Execute one attempt's model tool calls in order. Never throws except
     * [CancellationException] (cancel aborts remaining calls; partial
     * results are discarded by the throw — zero further backend effects).
     * Backend exceptions (non-cancel) are mapped to typed `EXEC_ERROR`
     * denials to preserve call-id pairing; a backend call exceeding the
     * registry `ToolAnnotations.timeoutMs` is a typed `EXEC_DENIED`.
     *
     * A blank call id denies the whole round with `SCHEMA_INVALID` (zero
     * backend invocations, before budget/dedup): the provider pairing
     * validator cannot pair a blank `tool_call_id`, so such a call must
     * never reach the backend. The typed denial is still CONTINUABLE —
     * [continuationMessages] re-binds the blank id to a deterministic
     * synthetic wire id ([blankWireCallId]) on both sides of the pair (and
     * re-binds it again, `_2`, `_3`, …, if it collides with a model-supplied
     * id in the same round).
     *
     * Durability: the exec-once guarantee is turn-layer, not process-level.
     * The engine marks the round executed before calling [execute], so a
     * same-turn retry never re-executes, and no tool round resumes on reopen.
     * There is no durable structured tool-result event: reopened model
     * history sees only the inline marker text (when the flush below landed).
     * Result markers are appended to the attempt partial through the normal
     * bounded transcript flush, which is best-effort: a process kill before
     * the next flush can lose the marker suffix (or a kill during [execute]
     * can leave none). Reopen backfill then stamps one `INTERRUPTED` terminal
     * when the family still dangles (`findDanglingRunIds` in
     * ChatSessionFactory, itself best-effort). A returned result whose receipt
     * was lost is therefore the read-only conservative outcome: nothing
     * re-executes.
     */
    suspend fun execute(calls: List<ToolCall>): List<ToolResult> {
        // Cancel first: a cancel racing the collect→execute handoff must not
        // build denials or touch the backend (zero further effects).
        coroutineContext.ensureActive()
        if (calls.any { it.id.isBlank() }) {
            // The whole round denies (a blank id cannot carry a provider
            // pairing id), but the denial stays CONTINUABLE: the receipt
            // and the continuation carry the deterministic synthetic wire
            // id ([blankWireCallId], re-bound on collision) instead of the
            // blank raw id, so no blank id ever lands on the wire.
            //
            // The wire ids are computed ONCE here and are the SAME ids
            // [continuationMessages] computes for the same call list (the
            // helper is a pure function of the list), so the receipt's
            // `callId` and the wire pair always agree.
            val wireIds = wireCallIds(calls)
            return calls.mapIndexed { i, c ->
                deny(
                    c,
                    DenyCodes.SCHEMA_INVALID,
                    // Per-call detail: the BLANK call names the problem; a
                    // NON-BLANK sibling names the round-level denial (its
                    // own id is fine — the round is denied because of a
                    // sibling). Both are FIXED constants: no raw id ever
                    // rides the wire in the detail.
                    if (c.id.isBlank()) "blank call id" else "round denied: sibling call id is blank",
                    wireId = wireIds[i],
                )
            }
        }
        if (calls.size > maxCallsPerTurn) {
            return calls.map { deny(it, DenyCodes.BUDGET_EXCEEDED, "at most $maxCallsPerTurn tool calls per round") }
        }
        // Direct-API defense in depth: the turn engine dedups by redacted id
        // before calling, but a future caller must not execute two calls that
        // re-bind to one wire pairing id (both would execute, then the
        // continuation would fail non-retryably with a duplicate pair).
        val pairingIds = calls.map { boundedRedactedId(it.id) }
        if (pairingIds.size != pairingIds.toSet().size) {
            return calls.map { deny(it, DenyCodes.SCHEMA_INVALID, "duplicate call id in one round") }
        }
        return calls.map { executeOne(it) }
    }

    /**
     * The wire ids of one round: one id per call, in call order — the exact
     * ids that ride the wire (assistant `toolCalls` + `tool` messages).
     *
     * A NON-BLANK model id is used verbatim. A BLANK model id is re-bound to
     * its deterministic synthetic id ([blankWireCallId]) — but only while
     * that id is still free: the synthetic id can collide with a
     * MODEL-SUPPLIED id in the same round (or with another synthetic id),
     * and a duplicate `call`/`tool` pair fails the provider pairing
     * validator NON-RETRYABLY after the calls already ran. So on a
     * collision the synthetic id is re-bound deterministically (`_2`, `_3`,
     * …) until it is unique; the model-supplied id is never touched.
     *
     * The result is a pure function of the call list (same round → same
     * wire ids), which is what lets [execute] (the denial receipt) and
     * [continuationMessages] (the wire pair) agree on the id.
     */
    private fun wireCallIds(calls: List<ToolCall>): List<String> {
        // Model-supplied ids occupy their wire slot FIRST: a synthetic id
        // must never steal a model id's slot (the model id is the one the
        // provider pairing validator saw in the request).
        val used = calls.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id.isNotBlank() } }
        return calls.mapIndexed { i, c ->
            if (c.id.isNotBlank()) {
                c.id
            } else {
                val base = blankWireCallId(i)
                var candidate = base
                var suffix = 2
                // `used.add` returns false when the candidate is taken.
                while (!used.add(candidate)) {
                    candidate = "${base}_$suffix"
                    suffix++
                }
                candidate
            }
        }
    }

    private suspend fun executeOne(call: ToolCall): ToolResult {
        coroutineContext.ensureActive()
        val hook = beforeExecuteForTest
        try {
            hook?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fixed receipt: hook messages never reach the wire (same rule
            // as backend exceptions below).
            return deny(call, DenyCodes.EXEC_ERROR, "pre-execution gate failed")
        }
        coroutineContext.ensureActive()
        val tool = ToolRegistry.find(call.name)
            // The raw model-controlled name is redacted centrally in [deny]
            // (detail + tool echo) before it can reach the wire.
            ?: return deny(call, DenyCodes.UNKNOWN_TOOL, "unknown tool ${call.name}")
        if (call.name != TOOL_NAME || !tool.executionReady) {
            return deny(call, DenyCodes.NOT_READY, "${call.name} is not wired this round")
        }
        // Defense-in-depth: the slice is READ-only by construction. Even if
        // the registry entry for TOOL_NAME were ever re-graded, never execute
        // WRITE/PRIVILEGED here (a second tool must go through its own
        // confirm pipeline, not this slice). Static check first: fail fast
        // before schema parsing and the policy IO below.
        if (tool.sideEffect != SideEffect.READ) {
            return deny(call, DenyCodes.SCOPE_DENIED, "sideEffect=${tool.sideEffect} not allowed in slice")
        }
        // A throwing ctxSupplier (or projection predicate) is a scope failure,
        // not a turn crash: deny typed with a fixed detail so no exception
        // message rides the wire, and never touch policy/backend.
        val projection = try {
            tool.project(ctxSupplier())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return deny(call, DenyCodes.SCOPE_DENIED, "projection unavailable")
        }
        if (projection.level != CapabilityLevel.NATIVE) {
            val reason = projection.reason ?: DenyReason.USER_DISABLED
            return deny(call, DenyCodes.SCOPE_DENIED, reason.name)
        }
        val args = parseArgs(call.argumentsJson)
            ?: return deny(call, DenyCodes.SCHEMA_INVALID, "arguments must be a JSON object with a non-blank string query")
        val verdict = try {
            policy.evaluateFresh(POLICY_ACTION, tool.name).verdict
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (verdict != Verdict.ALLOW) {
            return deny(call, DenyCodes.POLICY_DENIED, verdict?.name ?: "ERROR")
        }
        when (val gate = PrivilegeGate.check(tool, confirmed = false, projection)) {
            is GateResult.Allowed -> Unit
            is GateResult.NeedConfirm ->
                return deny(call, DenyCodes.NEED_CONFIRM, gate.toolName)
            is GateResult.Denied ->
                return deny(call, DenyCodes.SCOPE_DENIED, gate.reason.name)
        }
        coroutineContext.ensureActive()
        // Read once: a concurrent test install cannot change the budget
        // mid-call. The production budget is the registry annotation.
        val timeoutMillis = backendTimeoutMillisForTest ?: tool.annotations.timeoutMs
        // A non-positive budget is an IMMEDIATE typed timeout: `withTimeout`
        // throws `TimeoutCancellationException` before the body runs, so the
        // backend is never invoked and the catch below maps it to the same
        // typed EXEC_DENIED ("backend timed out"). No separate guard is
        // needed (and none would change the observable outcome).
        val outcome = try {
            withTimeout(timeoutMillis) {
                try {
                    LspTools.symbols(args.query, args.pathPrefix, args.limit, backend)
                } catch (e: TimeoutCancellationException) {
                    // Backend's own timer fired (not our withTimeout): surface as
                    // EXEC_ERROR, not EXEC_DENIED, unless we are actually cancelled.
                    if (coroutineContext.isActive) {
                        throw IllegalStateException("backend timeout", e)
                    }
                    throw e
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Our timer fired; an external cancel that races it still wins.
            coroutineContext.ensureActive()
            return deny(call, DenyCodes.EXEC_DENIED, "backend timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fixed receipt: never interpolate backend messages (an MCP
            // bridge message could carry paths/secrets, and this JSON rides
            // the provider continuation). Debug detail stays out of the wire.
            return deny(call, DenyCodes.EXEC_ERROR, "backend failed")
        }
        coroutineContext.ensureActive()
        return when (outcome) {
            is LspTools.SymbolsOutcome.Ok -> try {
                ok(call, args.query, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Defense in depth for backend List implementations whose
                // lazy reads throw outside ok()'s own typed boundary.
                deny(call, DenyCodes.EXEC_ERROR, "backend failed")
            }
            is LspTools.SymbolsOutcome.Unavailable ->
                deny(call, DenyCodes.EXEC_DENIED, outcome.detail)
        }
    }

    private data class ValidArgs(val query: String, val pathPrefix: String?, val limit: Int)

    /** Registry-schema mirror: object + non-blank string `query`, optional int `limit`, optional string `pathPrefix`. */
    private fun parseArgs(argumentsJson: String): ValidArgs? {
        if (argumentsJson.length > MAX_GROSS_ARGS_CHARS) return null
        // Cheap nesting-depth guard (ignores contents of JSON strings) against
        // deeply nested payloads.
        if (jsonNestingDepth(argumentsJson) > MAX_NESTING_DEPTH) return null
        val root = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: Exception) {
            return null
        }
        if (root !is JsonObject) return null
        val query = (root["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it.length <= MAX_INPUT_CHARS }
            ?.takeIf { it.none { c -> c.isISOControl() || c == '\u0000' } } ?: return null
        val limit = when (val raw = root["limit"]) {
            null -> LspTools.DEFAULT_LIMIT
            // Strict integer: a JSON string (even "5") is not an integer.
            is JsonPrimitive -> if (raw.isString) {
                return null
            } else {
                raw.intOrNull?.coerceIn(1, LspTools.MAX_LIMIT) ?: return null
            }
            else -> return null
        }
        val pathPrefix = when (val raw = root["pathPrefix"]) {
            null -> null
            is JsonPrimitive -> {
                if (!raw.isString) return null
                val v = raw.content
                // Scope traversal guard (raw-string layer): Unicode
                // normalization mismatches (NFKC-unstable homoglyphs) are
                // rejected here — NFKC-stable lookalike code points pass,
                // so the rejection is "changed under NFKC", not the bare
                // presence of a lookalike; every traversal/marker denial
                // comes from the single shared
                // predicate [isSafeInput] (`..`, leading `/` after trimming,
                // backslash, NUL, control chars, percent-escapes, `:`, and
                // Unicode FORMAT code points — scheme/drive-absolute scope
                // forms such as `file:/etc` and `c:/Users` must never name
                // another root namespace).
                // Encoded forms (`%2e%2e`) are rejected here fail-closed; the
                // injected bridge must still canonicalize/decode then
                // re-validate and never treat this string as a trusted
                // filesystem scope.
                // `""` is allowed and normalizes downstream (StubBackend maps
                // blank to the default scope); it is "unspecified", not a
                // bypass (pinned by tests).
                if (v.length > MAX_INPUT_CHARS) return null
                // Reject any input that changes under NFKC normalization.
                if (Normalizer.normalize(v, Normalizer.Form.NFKC) != v) return null
                if (!isSafeInput(v)) return null
                v
            }
            else -> return null
        }
        return ValidArgs(query, pathPrefix, limit)
    }

    /**
     * Fail-closed scope guard, single shared predicate (no duplicated inline
     * re-checks): no traversal markers (`..`), no absolute scope form (leading
     * `/` after trimming), no backslash, NUL, ISO-control chars, or
     * percent-escapes; `:` additionally rejects scheme/drive-absolute scope
     * forms (`file:/etc`, `c:/Users`) so a scope can never name another root
     * namespace.
     *
     * Unicode FORMAT code points (U+200B ZWSP, U+202E RLO, U+FEFF BOM/BOM,
     * U+200E/200F LRM/RLM, …) are rejected outright: they are invisible
     * (or reorder the visible text) and `trimStart()` does NOT remove them,
     * so `\u200B/etc/passwd` would otherwise pass the leading-`/` check and
     * name an absolute scope while looking like a relative one. The check is
     * on the code point's general category (`Character.FORMAT`), so it also
     * covers format code points that are not whitespace at all. Iteration is
     * BY CODE POINT (not by UTF-16 char): a supplementary-plane format
     * character (U+E0001 LANGUAGE TAG, U+E0020 TAG SPACE — a surrogate
     * PAIR) reports `SURROGATE` for each half, so a char-level scan would
     * let it slip through and hide the same absolute scope behind an
     * invisible tag. This is deliberately wider than the bypass needs
     * (over-rejection of a scope that merely contains a zero-width mark is
     * the safe direction).
     *
     * Filesystem-shaped scope input only — the model-controlled
     * `query` deliberately keeps its own narrower check (a search string may
     * contain `..`/`%`/`:` and stays allowed).
     */
    private fun isSafeInput(v: String): Boolean {
        if (v.contains("..")) return false
        // Reject any Unicode FORMAT code point outright so it cannot bypass
        // the leading-`/` check (U+200B/U+202E/U+FEFF etc.). Code-point
        // iteration: a supplementary-plane format character is a surrogate
        // pair whose halves report SURROGATE, never FORMAT.
        var i = 0
        while (i < v.length) {
            val codePoint = v.codePointAt(i)
            if (Character.getType(codePoint) == Character.FORMAT.toInt()) return false
            i += Character.charCount(codePoint)
        }
        if (v.trimStart().startsWith("/")) return false
        if (v.contains("\\")) return false
        if (v.contains("\u0000")) return false
        if (v.contains("%")) return false
        if (v.contains(":")) return false
        if (v.any { it.isISOControl() }) return false
        return true
    }

    /**
     * Typed denial receipt for [call].
     *
     * [wireId] is the id that rides the wire (the bounded redacted form of
     * it lands in the receipt body). It differs from the raw call id only
     * for a BLANK call id, where the deterministic synthetic id
     * ([blankWireCallId], re-bound on collision with a model-supplied id)
     * is used: a blank id must never land on the wire (neither as a
     * pairing id nor as the receipt's `callId` echo). The returned
     * [ToolResult.callId] keeps the RAW id — the internal pairing
     * record — and the continuation builder re-binds it.
     */
    private fun deny(
        call: ToolCall,
        code: String,
        detail: String,
        wireId: String = call.id,
    ): ToolResult {
        val json = buildJsonObject {
            put("denied", true)
            put("code", code)
            // Model-controlled echoes are redacted THEN capped like Ok
            // results (redaction can inflate a string); codes are fixed
            // constants. The detail can embed a model/backend-controlled
            // string, so it takes the same pipeline rather than a bare cap.
            put("tool", cappedTake(dev.librepocket.redact.Redactor.redact(call.name).text, MAX_STRING_CHARS))
            put("callId", boundedRedactedId(wireId))
            put(
                "detail",
                cappedTake(
                    dev.librepocket.redact.Redactor.redact(boundedRawBackendField(detail)).text,
                    MAX_STRING_CHARS,
                ),
            )
            put("clockMillis", clock.millis())
        }.toString()
        return ToolResult(call.id, call.name, json, denied = true, denyCode = code)
    }

    private fun cappedEcho(value: String): String =
        if (value.length > MAX_STRING_CHARS) {
            cappedTake(value, MAX_STRING_CHARS) + "…[truncated ${value.length}→$MAX_STRING_CHARS]"
        } else {
            value
        }

    /** Pre-redaction bound for backend-owned strings (see [RAW_BACKEND_FIELD_MAX_CHARS]).
     *  Over-bound values are replaced whole by a fixed receipt: a truncated
     *  prefix could defeat suffix-anchored redaction rules. */
    private fun boundedRawBackendField(value: String): String =
        if (value.length <= RAW_BACKEND_FIELD_MAX_CHARS) value
        else "[backend field omitted: ${value.length} chars]"

    private fun ok(
        call: ToolCall,
        query: String,
        outcome: LspTools.SymbolsOutcome.Ok,
    ): ToolResult {
        // Snapshot the bounded projection ONCE inside the typed-error
        // boundary: a backend List with a throwing size/iterator/get is a
        // backend failure (EXEC_ERROR), never an escaped turn crash. Both the
        // redaction pass and the raw-field pass share this snapshot, and a
        // backend returning more than MAX_LIMIT (contract violation) is not
        // iterated twice.
        val available: List<LspTools.Symbol>
        val limitOmitted: Int
        val redactedQuery: String
        val redactedSymbols: List<Triple<String, String, String>>
        val anyFieldTruncated: Boolean
        try {
            available = outcome.symbols.take(LspTools.MAX_LIMIT)
            limitOmitted = (outcome.symbols.size - available.size).coerceAtLeast(0)
            redactedQuery = dev.librepocket.redact.Redactor.redact(query).text
            // Truncation is decided on the POST-redaction lengths: redaction
            // can inflate a field (e.g. `⟦REDACTED:EMAIL⟧`), so a ≤cap raw
            // string may exceed the cap after redaction — decide there.
            var truncatedFlag = false
            var rawOmitted = false
            redactedSymbols = available.map { s ->
                rawOmitted = rawOmitted || s.name.length > RAW_BACKEND_FIELD_MAX_CHARS ||
                    s.kind.length > RAW_BACKEND_FIELD_MAX_CHARS ||
                    s.path.length > RAW_BACKEND_FIELD_MAX_CHARS
                val rn = dev.librepocket.redact.Redactor.redact(boundedRawBackendField(s.name)).text
                val rk = dev.librepocket.redact.Redactor.redact(boundedRawBackendField(s.kind)).text
                val rp = dev.librepocket.redact.Redactor.redact(boundedRawBackendField(s.path)).text
                if (rn.length > MAX_STRING_CHARS || rk.length > MAX_STRING_CHARS || rp.length > MAX_STRING_CHARS) {
                    truncatedFlag = true
                }
                Triple(rn, rk, rp)
            }
            anyFieldTruncated = truncatedFlag || rawOmitted || redactedQuery.length > MAX_STRING_CHARS
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return deny(call, DenyCodes.EXEC_ERROR, "backend failed")
        }
        val symbolJsons = redactedSymbols.mapIndexed { idx, (rn, rk, rp) ->
            val s = available[idx]
            buildJsonObject {
                put("name", cappedEcho(rn))
                put("kind", cappedEcho(rk))
                put("path", cappedEcho(rp))
                put("line", s.line)
            }
        }
        fun build(chosen: List<kotlinx.serialization.json.JsonObject>, omittedCount: Int, truncated: Boolean): kotlinx.serialization.json.JsonObject =
            buildJsonObject {
                put("denied", false)
                put("tool", cappedTake(dev.librepocket.redact.Redactor.redact(call.name).text, MAX_STRING_CHARS))
                put("callId", boundedRedactedId(call.id))
                put("query", cappedEcho(redactedQuery))
                put("symbols", kotlinx.serialization.json.buildJsonArray { for (o in chosen) add(o) })
                put("returnedCount", chosen.size)
                put("omittedCount", omittedCount)
                put("truncated", truncated)
                put("fromStub", outcome.fromStub)
                put("clockMillis", clock.millis())
            }
        val chosen = ArrayList(symbolJsons)
        // Worst case accepted deliberately: the scan-down shrink re-serializes
        // per dropped symbol (O(n²) in symbol count), but the input is
        // pre-bounded by the LspTools.MAX_LIMIT item cap and the per-field
        // MAX_STRING_CHARS cap, so the quadratic component is constant-bounded
        // — perf, not correctness.
        while (chosen.size > 1) {
            val omittedCount = limitOmitted + (symbolJsons.size - chosen.size)
            val truncated = anyFieldTruncated || omittedCount > 0
            val candidate = build(chosen, omittedCount, truncated).toString()
            if (candidate.length <= MAX_RESULT_CHARS) break
            chosen.removeAt(chosen.lastIndex)
        }
        // A backend returning more than MAX_LIMIT is over the item cap even
        // when the serialized result fits: report both cap drops and
        // shrink-loop drops in omittedCount, and mark truncated.
        val omittedCount = limitOmitted + (symbolJsons.size - chosen.size)
        val truncated = anyFieldTruncated || omittedCount > 0
        val json = build(chosen, omittedCount, truncated).toString()
        if (json.length > MAX_RESULT_CHARS) {
            // Floor reached and still over the total cap — a cross-constant
            // regression between MAX_STRING_CHARS and MAX_RESULT_CHARS.
            // Fail closed with a typed denial instead of shipping an
            // oversized continuation. Unreachable today with ≈3× margin:
            // a single-symbol build is ≤ ~6.5 K even with the three 500-char
            // fields at cap, truncation markers, and JSON-escape doubling
            // of fully-escaped content (vs the 20 K total); the floor pin
            // test guards the coupling.
            return deny(call, DenyCodes.EXEC_ERROR, "result over budget")
        }
        return ToolResult(call.id, call.name, json, denied = false)
    }

    /**
     * Continuation messages appended after the tool-call assistant block:
     * one assistant `toolCalls` entry plus one `tool` message per result
     * (executed or denied — pairing is total within one executed round; a
     * call re-emitted on a retried attempt is recorded without a result and
     * never reaches this builder — the retried request re-attaches the same
     * frozen executed pair, not the live accumulation list). Wire shape mirrors
     * the T1 mock round-trip histories so all three adapters serialize it.
     *
     * WIRE-ID NORMALIZATION (the pairing contract): a BLANK call id cannot
     * ride the wire (a provider pairing validator rejects an empty
     * `tool_call_id`), yet the round it belongs to must still be
     * continuable — a typed denial is a result, not a reason to drop the
     * pairing. So a blank call id is re-bound to its deterministic synthetic
     * id ([blankWireCallId], `call_blank_slice_<index>`) on BOTH sides of
     * the pair: the assistant `toolCalls` entry and the `tool` message's
     * `toolCallId`.
     *
     * A collision between a synthetic id and a MODEL-SUPPLIED id in the
     * same round is NOT fatal: the synthetic id is re-bound deterministically
     * (`_2`, `_3`, …) until the wire ids are unique again (the model id is
     * never touched). Failing the round instead would throw away a
     * perfectly continuable typed denial over a naming coincidence, while
     * shipping the duplicate would fail the provider pairing validator
     * non-retryably AFTER the calls already ran.
     *
     * The pairing stays INDEX-EXACT: a result may carry a blank id only
     * when the call at the same index is blank too (then it inherits that
     * call's synthetic id); every other mismatch — a swapped pair, a blank
     * result against a non-blank call, a non-blank result against a blank
     * call — is an [IllegalArgumentException], because an orphan pair
     * would fail the provider pairing validator non-retryably AFTER the
     * calls already ran.
     */
    fun continuationMessages(calls: List<ToolCall>, results: List<ToolResult>): List<ChatMessage> {
        require(calls.size == results.size) { "calls/results size mismatch" }
        // Wire ids: a blank call id is replaced by its deterministic
        // synthetic id (re-bound again on a collision with a model-supplied
        // id — deterministically, `_2`, `_3`, … — so a collision is never
        // fatal); a non-blank id is kept as is.
        val callIds = wireCallIds(calls)
        // Result-side ids: a blank result id is only meaningful against a
        // blank call id (it then inherits that call's synthetic wire id); a
        // non-blank result id is used verbatim and must match its call.
        val resultWireIds = results.mapIndexed { i, r ->
            if (r.callId.isBlank()) {
                require(calls[i].id.isBlank()) { "blank result call id" }
                callIds[i]
            } else {
                r.callId
            }
        }
        // The wire ids must be distinct (a duplicate `call`/`tool` pair fails
        // the provider pairing validator non-retryably). A blank id can no
        // longer reach the wire — `wireCallIds` guarantees a non-blank id
        // for every call — so the only remaining collision source is a
        // DUPLICATE NON-BLANK model id (the same model id twice in one
        // round), which stays a hard failure.
        require(callIds.toSet().size == callIds.size) { "duplicate wire call id" }
        require(calls.indices.all { callIds[it] == resultWireIds[it] }) { "calls/results pairing mismatch" }
        val out = ArrayList<ChatMessage>(1 + results.size)
        // Name and arguments echo the model's own strings into the provider
        // continuation/transcript exactly like `tool`/`callId` above — they
        // get redaction + a bounded echo (no raw echo past the redaction
        // pipeline R1–R14, with secret-keyed member values collapsing to
        // the fixed receipt — see [redactedArgsEcho]).
        // Call ids ARE re-bound on this path — never verbatim: each call/id
        // here and each result's `toolCallId` below is replaced with its
        // bounded REDACTED form ([boundedRedactedId], deterministic hash
        // suffix past the cap), which is why the turn engine dedups execution
        // candidates on the same key ([redactedPairingId]). Pairing then
        // stays unique where the wire is unique: two distinct raw ids that
        // redact to one string cannot both execute — the colliding second
        // call stays records-only (execution dropped, marker + audit kept),
        // never a duplicate `call`/`tool` pair that would fail the provider
        // pairing validator with a non-retryable `TOOL_CALL_DUPLICATE_ID`
        // after execution. Two distinct long ids sharing the capped prefix no
        // longer collide: the suffix keeps them distinct and both execute.
        val normalizedCalls = calls.mapIndexed { i, c ->
            c.copy(
                id = boundedRedactedId(callIds[i]),
                name = cappedTake(dev.librepocket.redact.Redactor.redact(c.name).text, MAX_STRING_CHARS),
                argumentsJson = redactedArgsEcho(c.argumentsJson),
            )
        }
        require(normalizedCalls.map { it.id }.toSet().size == normalizedCalls.size) {
            "duplicate normalized call id"
        }
        out.add(ChatMessage(role = "assistant", text = "", toolCalls = normalizedCalls))
        for ((i, r) in results.withIndex()) {
            out.add(
                ChatMessage(
                    role = "tool",
                    text = r.resultJson,
                    toolCallId = boundedRedactedId(resultWireIds[i]),
                ),
            )
        }
        return out
    }
}
