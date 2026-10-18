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
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.Clock
import kotlin.coroutines.coroutineContext

/**
 * P2 safe vertical slice (#19): the single wired tool's closed loop.
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
 * 8. executor ([LspTools.symbols] on the injected backend — default
 *    [LspTools.StubBackend] is pure computation, no IO; injected backends
 *    must honor the same cancel contract and never return secrets, see
 *    [LspTools.Backend]).
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
 * state lives in the turn engine, not here. When a second tool wires, retire
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
        /** The single wired tool (the only `executionReady=true` entry). */
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
         * the marker reports the post-redaction length.
         */
        const val MAX_STRING_CHARS = 500
        /** Per-input cap for model-controlled `query` / `pathPrefix` (fail-closed). */
        const val MAX_INPUT_CHARS = 4096
        /** Total cap for serialized tool result JSON (fail-closed continuation budget). */
        const val MAX_RESULT_CHARS = 20_000
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
        // slice path produces NEED_CONFIRM today. EXEC_DENIED is likewise
        // reserved: blank queries (the only Unavailable source) are rejected
        // by schema validation first.
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
     * Empty (switch off / flavor-blocked / not-ready) means pure chat —
     * the request carries no tools and [execute] refuses everything.
     */
    fun offeredTools(): List<ToolSchema> =
        ToolRegistry.visibleTools(ctxSupplier())
            .filter { it.name == TOOL_NAME }
            .map { ToolSchema(it.name, it.description, it.jsonSchema) }

    /**
     * Execute one attempt's model tool calls in order. Never throws except
     * [CancellationException] (cancel aborts remaining calls; partial
     * results are discarded by the throw — zero further backend effects).
     * Backend exceptions (non-cancel) are mapped to typed `EXEC_ERROR`
     * denials to preserve call-id pairing.
     */
    suspend fun execute(calls: List<ToolCall>): List<ToolResult> {
        // Cancel first: a cancel racing the collect→execute handoff must not
        // build denials or touch the backend (zero further effects).
        coroutineContext.ensureActive()
        if (calls.size > maxCallsPerTurn) {
            return calls.map { deny(it, DenyCodes.BUDGET_EXCEEDED, "at most $maxCallsPerTurn tool calls per round") }
        }
        return calls.map { executeOne(it) }
    }

    /**
     * Wire-safe redacted echo of a tool call's arguments: parse-then-redact
     * (token-level inside each JSON string) and re-serialize a still-valid
     * JSON object. Provider adapters re-parse this echo before serializing
     * the continuation (`validateToolPairing`), so a raw-text redact or a
     * `take` would corrupt the structure (several redaction rules swallow
     * the terminating `"`/`}` of a value) and turn a working round into a
     * non-retryable `TOOL_ARGUMENTS_INVALID` failure.
     *
     * All failure modes emit the same fixed minimal object (fail-closed,
     * still a valid object): the input exceeds [parseArgs]'s gross size cap
     * ([MAX_INPUT_CHARS] × 4) or its nesting-depth fan-close (> 32 — see
     * [jsonNestingDepth]), parsing itself fails, or the re-serialized form
     * exceeds [MAX_INPUT_CHARS].
     *
     * The depth fan-close is the ONLY structural recursion bound here and it
     * is deliberately checked BEFORE parsing: this echo is parsed from the
     * model's raw argument text, so embedded JSON is not bounded by the
     * provider wire frame parser (MiniJson.MAX_DEPTH applies to the wire
     * frame, not to a JSON string carried inside it). Skipping the guard
     * would let a within-budget deeply nested argument reach the recursive
     * [redactJsonStrings] walk.
     */
    private fun redactedArgsEcho(argumentsJson: String): String {
        val fallback = buildJsonObject { put("echo", "args redacted/capped") }.toString()
        // Same fan-close values as [parseArgs] (gross cap + depth 32), applied
        // with a cheap char scan BEFORE any parse/recursion. String contents
        // are ignored by the scan, so it bounds structure only.
        if (argumentsJson.length > MAX_INPUT_CHARS * 4 || jsonNestingDepth(argumentsJson) > 32) {
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
     *  too). Recursion depth is bounded by the caller's pre-parse fan-close
     *  ([redactedArgsEcho] rejects depth > 32 via [jsonNestingDepth], the
     *  same bound [parseArgs] fans closed with) — never call this on
     *  unguarded input. */
    private fun redactJsonStrings(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive ->
            if (element.isString) {
                JsonPrimitive(dev.librepocket.redact.Redactor.redact(element.content).text)
            } else {
                element
            }
        is JsonObject -> JsonObject(
            element.entries.associate { (k, v) ->
                dev.librepocket.redact.Redactor.redact(k).text to redactJsonStrings(v)
            },
        )
        is JsonArray -> JsonArray(element.map { redactJsonStrings(it) })
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
            // Redact the model-controlled name before it rides the wire in a
            // detail (same rule as the `tool` field in [deny]).
            ?: return deny(call, DenyCodes.UNKNOWN_TOOL, "unknown tool ${dev.librepocket.redact.Redactor.redact(call.name).text}")
        if (call.name != TOOL_NAME || !tool.executionReady) {
            return deny(call, DenyCodes.NOT_READY, "${dev.librepocket.redact.Redactor.redact(call.name).text} is not wired this round")
        }
        // Defense-in-depth: the slice is READ-only by construction. Even if
        // the registry entry for TOOL_NAME were ever re-graded, never execute
        // WRITE/PRIVILEGED here (a second tool must go through its own
        // confirm pipeline, not this slice). Static check first: fail fast
        // before schema parsing and the policy IO below.
        if (tool.sideEffect != SideEffect.READ) {
            return deny(call, DenyCodes.SCOPE_DENIED, "sideEffect=${tool.sideEffect} not allowed in slice")
        }
        val projection = tool.project(ctxSupplier())
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
        val outcome = try {
            LspTools.symbols(args.query, args.pathPrefix, args.limit, backend)
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
            is LspTools.SymbolsOutcome.Ok -> ok(call, args.query, outcome)
            is LspTools.SymbolsOutcome.Unavailable ->
                deny(call, DenyCodes.EXEC_DENIED, outcome.detail)
        }
    }

    private data class ValidArgs(val query: String, val pathPrefix: String?, val limit: Int)

    /** Registry-schema mirror: object + non-blank string `query`, optional int `limit`, optional string `pathPrefix`. */
    private fun parseArgs(argumentsJson: String): ValidArgs? {
        if (argumentsJson.length > MAX_INPUT_CHARS * 4) return null
        // Cheap nesting-depth guard (ignores contents of JSON strings) against
        // deeply nested payloads.
        if (jsonNestingDepth(argumentsJson) > 32) return null
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
                // Scope traversal guard (raw-string layer): reject Unicode
                // normalization mismatches (homoglyphs), `..`, leading `/`
                // (after trimming), backslash, NUL, control chars, and
                // percent-escapes. Encoded forms (`%2e%2e`) are rejected here
                // fail-closed; the injected bridge must still canonicalize/
                // decode then re-validate and never treat this string as a
                // trusted filesystem scope.
                // `""` is allowed and normalizes downstream (StubBackend maps
                // blank to the default scope); it is "unspecified", not a
                // bypass (pinned by tests).
                if (v.length > MAX_INPUT_CHARS) return null
                // Reject any input that changes under NFKC normalization.
                if (Normalizer.normalize(v, Normalizer.Form.NFKC) != v) return null
                // `:` additionally rejects scheme/drive-absolute scope forms
                // (`file:/etc`, `c:/Users`) so a scope can never name another
                // root namespace.
                if (v.contains("..") || v.trimStart().startsWith("/") || v.contains("\\") || v.contains("\u0000") || v.contains("%") || v.contains(":")) {
                    return null
                }
                if (v.any { it.isISOControl() }) return null
                if (!isSafeInput(v)) return null
                v
            }
            else -> return null
        }
        return ValidArgs(query, pathPrefix, limit)
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

    /** Fail-closed input guard: no traversal markers, backslash, NUL, control, or percent-escape. */
    private fun isSafeInput(v: String): Boolean {
        if (v.contains("..")) return false
        if (v.contains("\\")) return false
        if (v.contains("\u0000")) return false
        if (v.contains("%")) return false
        if (v.any { it.isISOControl() }) return false
        return true
    }

    private fun deny(call: ToolCall, code: String, detail: String): ToolResult {
        val json = buildJsonObject {
            put("denied", true)
            put("code", code)
            // Model-controlled echoes are capped like Ok results; codes are
            // fixed constants.
            put("tool", dev.librepocket.redact.Redactor.redact(call.name).text.take(MAX_STRING_CHARS))
            put("callId", dev.librepocket.redact.Redactor.redact(call.id).text.take(MAX_STRING_CHARS))
            put("detail", detail.take(MAX_STRING_CHARS))
            put("clockMillis", clock.millis())
        }.toString()
        return ToolResult(call.id, call.name, json, denied = true, denyCode = code)
    }

    private fun cappedEcho(value: String): String =
        if (value.length > MAX_STRING_CHARS) {
            value.take(MAX_STRING_CHARS) + "…[truncated ${value.length}→$MAX_STRING_CHARS]"
        } else {
            value
        }

    private fun ok(call: ToolCall, query: String, outcome: LspTools.SymbolsOutcome.Ok): ToolResult {
        val redactedQuery: String
        val redactedSymbols: List<Triple<String, String, String>>
        val anyFieldTruncated: Boolean
        try {
            redactedQuery = dev.librepocket.redact.Redactor.redact(query).text
            val raw = outcome.symbols.take(LspTools.MAX_LIMIT)
            // Truncation is decided on the POST-redaction lengths: redaction
            // can inflate a field (e.g. `⟦REDACTED:EMAIL⟧`), so a ≤cap raw
            // string may exceed the cap after redaction — decide there.
            var truncatedFlag = false
            redactedSymbols = raw.map { s ->
                val rn = dev.librepocket.redact.Redactor.redact(s.name).text
                val rk = dev.librepocket.redact.Redactor.redact(s.kind).text
                val rp = dev.librepocket.redact.Redactor.redact(s.path).text
                if (rn.length > MAX_STRING_CHARS || rk.length > MAX_STRING_CHARS || rp.length > MAX_STRING_CHARS) {
                    truncatedFlag = true
                }
                Triple(rn, rk, rp)
            }
            anyFieldTruncated = truncatedFlag || redactedQuery.length > MAX_STRING_CHARS
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return deny(call, DenyCodes.EXEC_ERROR, "backend failed")
        }
        val rawSymbols = outcome.symbols.take(LspTools.MAX_LIMIT)
        val symbolJsons = redactedSymbols.mapIndexed { idx, (rn, rk, rp) ->
            val s = rawSymbols[idx]
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
                put("tool", dev.librepocket.redact.Redactor.redact(call.name).text.take(MAX_STRING_CHARS))
                put("callId", dev.librepocket.redact.Redactor.redact(call.id).text.take(MAX_STRING_CHARS))
                put("query", cappedEcho(redactedQuery))
                put("symbols", kotlinx.serialization.json.buildJsonArray { for (o in chosen) add(o) })
                put("returnedCount", chosen.size)
                put("omittedCount", omittedCount)
                put("truncated", truncated)
                put("fromStub", outcome.fromStub)
                put("clockMillis", clock.millis())
            }
        val chosen = ArrayList(symbolJsons)
        while (chosen.size > 1) {
            val omittedCount = symbolJsons.size - chosen.size
            val truncated = anyFieldTruncated || omittedCount > 0
            val candidate = build(chosen, omittedCount, truncated).toString()
            if (candidate.length <= MAX_RESULT_CHARS) break
            chosen.removeAt(chosen.lastIndex)
        }
        val omittedCount = symbolJsons.size - chosen.size
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
        // pipeline R1–R14).
        val normalizedCalls = calls.map {
            it.copy(
                id = dev.librepocket.redact.Redactor.redact(it.id).text.take(MAX_STRING_CHARS),
                name = dev.librepocket.redact.Redactor.redact(it.name).text.take(MAX_STRING_CHARS),
                argumentsJson = redactedArgsEcho(it.argumentsJson),
            )
        }
        out.add(ChatMessage(role = "assistant", text = "", toolCalls = normalizedCalls))
        for (r in results) {
            out.add(ChatMessage(role = "tool", text = r.resultJson, toolCallId = dev.librepocket.redact.Redactor.redact(r.callId).text.take(MAX_STRING_CHARS)))
        }
        return out
    }
}
