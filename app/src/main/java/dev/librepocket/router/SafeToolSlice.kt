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
 *    (scheme/proot/drive-absolute roots), no percent-escapes);
 * 6. fresh `tool.exec` [PolicyStore.evaluateFresh] (ALLOW only; ASK/DENY/
 *    failure all deny);
 * 7. [PrivilegeGate] (READ passes without confirmation; anything else denies);
 * 8. executor ([LspTools.symbols] on the injected suspend backend — default
 *    [LspTools.StubBackend] is pure computation, no IO; injected backends
 *    must honor the same cancel contract and never return secrets, see
 *    [LspTools.Backend]). The call runs under the registry entry's
 *    `ToolAnnotations.timeoutMs`; a timeout is a typed `EXEC_DENIED`, never a
 *    turn cancellation.
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
         * through raw — degraded to U+FFFD at UTF-8 encode. It steps back
         * over the trailing high-surrogate run at the cut: for well-formed
         * UTF-16 (a high surrogate's low partner is always the very next
         * unit) this is exactly ONE unit, so the capped length is
         * `[cap]-1` on a genuine split and exactly `[cap]` otherwise;
         * malformed pre-existing lone-surrogate input can only extend the
         * walk back from the cut, never split a pair that was not already
         * malformed. Work is bounded by `[cap]` (≤ `cap+1` in the split
         * case counting the marker afterwards).
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
         * plus a deterministic `#` + 8-hex SHA-256 suffix of the FULL
         * redacted id: two long ids that share the cap-length prefix (or
         * redact to a shared prefix) stay distinct instead of collapsing into
         * one pairing key (which would silently drop the second execution).
         * The result never exceeds [MAX_STRING_CHARS].
         */
        internal fun boundedRedactedId(callId: String): String {
            val redacted = dev.librepocket.redact.Redactor.redact(callId).text
            if (redacted.length <= MAX_STRING_CHARS) return redacted
            val digest = MessageDigest.getInstance("SHA-256").digest(redacted.toByteArray(Charsets.UTF_8))
            val hex = digest.take(4).joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            val suffix = "#$hex"
            return cappedTake(redacted, MAX_STRING_CHARS - suffix.length) + suffix
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
         *  non-secret-keyed values). Numbers/booleans under a secret key are
         *  never fabricated — a non-string primitive carries no secret shape.
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
                        SECRET_KEY_FAMILY.containsMatchIn(k) -> secretValueReceipt(v)
                        else -> redactJsonStrings(v)
                    }
                },
            )
            is JsonArray -> JsonArray(element.map { redactJsonStrings(it) })
        }

        /** Secret-keyed member value: any string or structured value becomes
         *  the single fixed receipt; non-string primitives carry no secret
         *  shape and pass untouched. */
        private fun secretValueReceipt(v: JsonElement): JsonElement = when (v) {
            is JsonPrimitive -> if (v.isString) JsonPrimitive(SECRET_VALUE_RECEIPT) else v
            is JsonArray, is JsonObject -> JsonPrimitive(SECRET_VALUE_RECEIPT)
        }

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
     * before the wrapped call; positive values only. Volatile for the same
     * cross-thread install reason as [beforeExecuteForTest].
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
        val outcome = try {
            withTimeout(timeoutMillis) {
                LspTools.symbols(args.query, args.pathPrefix, args.limit, backend)
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
                // backslash, NUL, control chars, percent-escapes, and `:` —
                // scheme/drive-absolute scope forms such as `file:/etc` and
                // `c:/Users` must never name another root namespace).
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
     * namespace. Filesystem-shaped scope input only — the model-controlled
     * `query` deliberately keeps its own narrower check (a search string may
     * contain `..`/`%`/`:` and stays allowed).
     */
    private fun isSafeInput(v: String): Boolean {
        if (v.contains("..")) return false
        if (v.trimStart().startsWith("/")) return false
        if (v.contains("\\")) return false
        if (v.contains("\u0000")) return false
        if (v.contains("%")) return false
        if (v.contains(":")) return false
        if (v.any { it.isISOControl() }) return false
        return true
    }

    private fun deny(call: ToolCall, code: String, detail: String): ToolResult {
        val json = buildJsonObject {
            put("denied", true)
            put("code", code)
            // Model-controlled echoes are redacted THEN capped like Ok
            // results (redaction can inflate a string); codes are fixed
            // constants. The detail can embed a model/backend-controlled
            // string, so it takes the same pipeline rather than a bare cap.
            put("tool", cappedTake(dev.librepocket.redact.Redactor.redact(call.name).text, MAX_STRING_CHARS))
            put("callId", boundedRedactedId(call.id))
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

    private fun ok(call: ToolCall, query: String, outcome: LspTools.SymbolsOutcome.Ok): ToolResult {
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
     */
    fun continuationMessages(calls: List<ToolCall>, results: List<ToolResult>): List<ChatMessage> {
        require(calls.size == results.size) { "calls/results size mismatch" }
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
        val normalizedCalls = calls.map {
            it.copy(
                id = boundedRedactedId(it.id),
                name = cappedTake(dev.librepocket.redact.Redactor.redact(it.name).text, MAX_STRING_CHARS),
                argumentsJson = redactedArgsEcho(it.argumentsJson),
            )
        }
        require(normalizedCalls.map { it.id }.toSet().size == normalizedCalls.size) {
            "duplicate normalized call id"
        }
        out.add(ChatMessage(role = "assistant", text = "", toolCalls = normalizedCalls))
        for (r in results) {
            out.add(ChatMessage(role = "tool", text = r.resultJson, toolCallId = boundedRedactedId(r.callId)))
        }
        return out
    }
}
