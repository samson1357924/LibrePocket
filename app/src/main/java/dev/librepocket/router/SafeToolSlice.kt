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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
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
 *    (re-graded entries deny `SCOPE_DENIED`/`NEED_CONFIRM`; scope denials
 *    surface [DenyReason]);
 * 4. latest [ProjectionContext] scope re-check;
 * 5. arguments schema validation (JSON object, required non-blank `query`,
 *    optional `limit` int clamped 1..200, optional `pathPrefix` string
 *    without traversal: no `..`, no leading `/`, no backslash);
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
         */
        const val MAX_STRING_CHARS = 500
    }

    /**
     * Test seam run before EACH execution (after cancel check, before any
     * gate): lets cancel tests park the loop in the deny/execute window and
     * then cancel, proving zero backend invocations. Production leaves null.
     * Volatile so a mid-turn install from the test thread is visible to the
     * turn dispatcher thread.
     */
    @Volatile
    var beforeExecuteForTest: (suspend () -> Unit)? = null

    /** Typed deny codes (audit-stable, user-visible in the paired denial). */
    object DenyCodes {
        const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
        const val NOT_READY = "NOT_READY"
        const val SCOPE_DENIED = "SCOPE_DENIED"
        const val SCHEMA_INVALID = "SCHEMA_INVALID"
        const val POLICY_DENIED = "POLICY_DENIED"
        const val NEED_CONFIRM = "NEED_CONFIRM"
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
            return calls.map { deny(it, DenyCodes.BUDGET_EXCEEDED, "at most $maxCallsPerTurn tool round per turn") }
        }
        return calls.map { executeOne(it) }
    }

    private suspend fun executeOne(call: ToolCall): ToolResult {
        coroutineContext.ensureActive()
        try {
            beforeExecuteForTest?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return deny(call, DenyCodes.EXEC_ERROR, "pre-execution gate failed: ${e.message}")
        }
        coroutineContext.ensureActive()
        val tool = ToolRegistry.find(call.name)
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
            return deny(call, DenyCodes.SCOPE_DENIED, DenyCodes.NEED_CONFIRM)
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
        return when (outcome) {
            is LspTools.SymbolsOutcome.Ok -> ok(call, args.query, outcome)
            is LspTools.SymbolsOutcome.Unavailable ->
                deny(call, DenyCodes.EXEC_DENIED, outcome.detail)
        }
    }

    private data class ValidArgs(val query: String, val pathPrefix: String?, val limit: Int)

    /** Registry-schema mirror: object + non-blank string `query`, optional int `limit`, optional string `pathPrefix`. */
    private fun parseArgs(argumentsJson: String): ValidArgs? {
        val root = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: Exception) {
            return null
        }
        if (root !is JsonObject) return null
        val query = (root["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return null
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
                // Scope traversal guard (raw-string layer): `..`, leading
                // `/`, backslash, NUL. Encoded forms (`%2e%2e`, unicode
                // normalization) pass this layer by design — the injected
                // bridge must canonicalize/decode then re-validate and
                // never treat this string as a trusted filesystem scope.
                // `""` is allowed and normalizes downstream (StubBackend maps
                // blank to the default scope); it is "unspecified", not a
                // bypass (pinned by tests).
                if (v.contains("..") || v.startsWith("/") || v.contains("\\") || v.contains("\u0000")) {
                    return null
                }
                v
            }
            else -> return null
        }
        return ValidArgs(query, pathPrefix, limit)
    }

    private fun deny(call: ToolCall, code: String, detail: String): ToolResult {
        val json = buildJsonObject {
            put("denied", true)
            put("code", code)
            // Model-controlled echoes are capped like Ok results; codes are
            // fixed constants.
            put("tool", call.name.take(MAX_STRING_CHARS))
            put("callId", call.id.take(MAX_STRING_CHARS))
            put("detail", detail.take(MAX_STRING_CHARS))
            put("clockMillis", clock.millis())
        }.toString()
        return ToolResult(call.id, call.name, json, denied = true, denyCode = code)
    }

    private fun cappedEcho(value: String, originalLength: Int): String =
        if (originalLength > MAX_STRING_CHARS) {
            value.take(MAX_STRING_CHARS) + "…[truncated $originalLength→$MAX_STRING_CHARS]"
        } else {
            value
        }

    private fun ok(call: ToolCall, query: String, outcome: LspTools.SymbolsOutcome.Ok): ToolResult {
        val json = buildJsonObject {
            put("denied", false)
            put("tool", call.name)
            put("callId", call.id)
            put("query", cappedEcho(query, query.length))
            put(
                "symbols",
                kotlinx.serialization.json.buildJsonArray {
                    for (s in outcome.symbols) {
                        add(
                            buildJsonObject {
                                put("name", cappedEcho(s.name, s.name.length))
                                put("kind", cappedEcho(s.kind, s.kind.length))
                                put("path", cappedEcho(s.path, s.path.length))
                                put("line", s.line)
                            },
                        )
                    }
                },
            )
            put("fromStub", outcome.fromStub)
            put("clockMillis", clock.millis())
        }.toString()
        return ToolResult(call.id, call.name, json, denied = false)
    }

    /**
     * Continuation messages appended after the tool-call assistant block:
     * one assistant `toolCalls` entry plus one `tool` message per result
     * (executed or denied — pairing is total within one executed round; a
     * call re-emitted on a retried attempt is recorded without a result and
     * never reaches here). Wire shape mirrors
     * the T1 mock round-trip histories so all three adapters serialize it.
     */
    fun continuationMessages(calls: List<ToolCall>, results: List<ToolResult>): List<ChatMessage> {
        require(calls.size == results.size) { "calls/results size mismatch" }
        val out = ArrayList<ChatMessage>(1 + results.size)
        out.add(ChatMessage(role = "assistant", text = "", toolCalls = calls))
        for (r in results) {
            out.add(ChatMessage(role = "tool", text = r.resultJson, toolCallId = r.callId))
        }
        return out
    }
}
