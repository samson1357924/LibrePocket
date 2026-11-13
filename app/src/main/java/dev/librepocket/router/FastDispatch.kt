package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolDef
import dev.librepocket.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.coroutineContext

/**
 * B3 (#19) fast-lane dispatch binding: [RouteDecision] → executor.
 *
 * Pure logic, no Android dependencies. This layer decides NOTHING about
 * intent: [FastRouter] owns routing (FAST vs DENIED + clarification), and
 * this dispatcher only executes a FAST decision when every gate below holds.
 * Anything else is [FastDispatchOutcome.Skipped] with zero executor effects
 * (pinned by `FastDispatchTest`, including the clarification path which
 * must never execute even when an executor IS registered for that tool).
 *
 * Dispatch gates, in order (all fail-closed, zero execution on trip):
 * 1. the decision must be FAST with a non-null [ToolCall] (DENIED /
 *    NEED_CLARIFICATION / no-match intents never execute);
 * 2. the tool must resolve via [lookup] (unknown names never dispatch);
 * 3. a FRESH projection at dispatch time must not be UNAVAILABLE (a switch /
 *    grant flipped between decide and dispatch denies, mirroring the slice's
 *    latest-projection re-check);
 * 4. the tool must be `executionReady` (registry readiness; B3 flips no
 *    flags — today only `lsp.symbols` qualifies);
 * 5. the tool must be [SideEffect.READ] (defense in depth: WRITE/PRIVILEGED
 *    tools need the confirmation pipeline before any execution, which lives
 *    in a future wiring PR — this dispatcher refuses them even if ready);
 * 6. an executor must be registered for the tool name (fail-safe: a routed
 *    but unwired tool never executes).
 *
 * Explicitly NOT done here (future production wiring PR, per the capability
 * matrix activation prerequisites): fresh `tool.exec` policy evaluation,
 * immutable confirmation snapshots, Android executors, Room audit writes,
 * and device-verified E2E. There is deliberately NO production caller:
 * [dev.librepocket.chat.TurnController] and `ChatSessionFactory` stay
 * records-only (`toolSlice = null` pinned), so the matrix stays
 * wired=false/verified=false and `executionReady` keeps its registry-only
 * meaning.
 *
 * Device E2E: PENDING — no device in this environment; coverage is JVM-only
 * (`FastDispatchTest` with fake backends). The wired/verified flips wait for
 * the E2E PR.
 */
class FastDispatcher(
    private val executors: Map<String, FastToolExecutor> = emptyMap(),
    private val lookup: (String) -> ToolDef? = { ToolRegistry.find(it) },
) {
    /**
     * Dispatches one [RouteDecision]. Never throws except
     * [CancellationException] (a cancelled dispatch aborts before the
     * executor runs); executor failures (non-cancel) become [Skipped] with a
     * fixed message — backend text never rides the outcome.
     */
    suspend fun dispatch(
        decision: RouteDecision,
        ctx: ProjectionContext,
    ): FastDispatchOutcome {
        val call = decision.toolCall
        if (decision.route != RouteKind.FAST || call == null) {
            return FastDispatchOutcome.Skipped(decision.reasonCode, decision.fallbackMessage)
        }
        coroutineContext.ensureActive()
        val tool = lookup(call.toolName)
            ?: return FastDispatchOutcome.Skipped(null, FallbackMessages.forNoMatch(call.toolName))
        val projection = tool.project(ctx)
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            val reason = projection.reason ?: DenyReason.USER_DISABLED
            return FastDispatchOutcome.Skipped(
                reason,
                FallbackMessages.forTool(tool.name, reason, tool.annotations.fallbackHint),
            )
        }
        if (!tool.executionReady) {
            return FastDispatchOutcome.Skipped(
                DenyReason.USER_DISABLED,
                FallbackMessages.forTool(tool.name, DenyReason.USER_DISABLED, tool.annotations.fallbackHint),
            )
        }
        if (tool.sideEffect != SideEffect.READ) {
            return FastDispatchOutcome.Skipped(
                DenyReason.USER_DISABLED,
                FallbackMessages.forTool(tool.name, DenyReason.USER_DISABLED, tool.annotations.fallbackHint),
            )
        }
        val executor = executors[call.toolName]
            ?: return FastDispatchOutcome.Skipped(
                DenyReason.USER_DISABLED,
                FallbackMessages.forTool(tool.name, DenyReason.USER_DISABLED, tool.annotations.fallbackHint),
            )
        coroutineContext.ensureActive()
        val result = try {
            executor.execute(call, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fixed receipt: never interpolate executor/backend messages (they
            // may carry paths or secrets, and this string is user-visible).
            return FastDispatchOutcome.Skipped(null, "執行${tool.name}失敗，請重試或手動操作")
        }
        if (result.denied) {
            return FastDispatchOutcome.Skipped(DenyReason.NEED_CLARIFICATION, result.resultJson)
        }
        return FastDispatchOutcome.Executed(result)
    }

    /**
     * One-call fast lane: route the intent text, then dispatch the decision.
     * Convenience for tests and future callers; carries no extra semantics
     * beyond [FastRouter.decide] + [dispatch] with the same [ctx].
     */
    suspend fun decideAndDispatch(
        intentText: String,
        ctx: ProjectionContext,
        sessionId: String? = null,
        turnIndex: Int? = null,
    ): FastLaneResult {
        val decision = FastRouter().decide(intentText, ctx, sessionId, turnIndex)
        return FastLaneResult(decision, dispatch(decision, ctx))
    }
}

/** One fast-lane attempt: the routing decision plus its dispatch outcome. */
data class FastLaneResult(
    val decision: RouteDecision,
    val outcome: FastDispatchOutcome,
)

/** Typed execution result from a [FastToolExecutor]. */
data class FastExecResult(
    val toolName: String,
    val callId: String,
    val resultJson: String,
    val denied: Boolean = false,
    val denyCode: String? = null,
)

/** Dispatch outcome: executed exactly once, or skipped with zero effects. */
sealed interface FastDispatchOutcome {
    data class Executed(val result: FastExecResult) : FastDispatchOutcome
    data class Skipped(val reasonCode: DenyReason?, val fallbackMessage: String?) : FastDispatchOutcome
}

/**
 * One named tool executor for the fast lane.
 *
 * Implementations must be pure-JVM-callable, honor coroutine cancellation
 * promptly, and never return secrets in [FastExecResult.resultJson] (results
 * may ride provider continuations). Expected denials (e.g. blank query)
 * are typed ([FastExecResult.denied]); unexpected failures may throw and are
 * converted to [FastDispatchOutcome.Skipped] with a fixed message by
 * [FastDispatcher] — except [CancellationException], which propagates.
 */
interface FastToolExecutor {
    val toolName: String
    suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult
}

/**
 * `lsp.symbols` fast-lane executor over an injected [LspTools.Backend]
 * (default [LspTools.StubBackend]: pure computation, no IO).
 *
 * Argument shape mirrors the [FastRouter] template (`query` required
 * non-blank, optional `pathPrefix`, optional `limit` clamped 1..200 with the
 * same strict-integer rule as the slice): a blank/non-object payload is a
 * typed denial with zero backend invocations, never an execution.
 */
class LspSymbolsExecutor(
    private val backend: LspTools.Backend = LspTools.StubBackend,
) : FastToolExecutor {
    override val toolName: String = LspTools.SYMBOLS_NAME

    private data class LspArgs(val query: String, val pathPrefix: String?, val limit: Int)

    private fun parseArgs(argumentsJson: String): LspArgs? {
        val root = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: Exception) {
            return null
        }
        if (root !is JsonObject) return null
        val query = (root["query"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return null
        val limit = when (val raw = root["limit"]) {
            null -> LspTools.DEFAULT_LIMIT
            is JsonPrimitive -> if (raw.isString) {
                return null
            } else {
                raw.intOrNull?.coerceIn(1, LspTools.MAX_LIMIT) ?: return null
            }
            else -> return null
        }
        val pathPrefix = when (val raw = root["pathPrefix"]) {
            null -> null
            is JsonPrimitive -> if (!raw.isString) return null else raw.content
            else -> return null
        }
        return LspArgs(query, pathPrefix, limit)
    }

    override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
        val args = parseArgs(call.argumentsJson)
            ?: return FastExecResult(
                toolName = toolName,
                callId = call.callId,
                resultJson = buildJsonObject {
                    put("tool", toolName)
                    put("callId", call.callId)
                    put("denied", true)
                    put("code", "SCHEMA_INVALID")
                }.toString(),
                denied = true,
                denyCode = "SCHEMA_INVALID",
            )
        return when (val outcome = LspTools.symbols(args.query, args.pathPrefix, args.limit, backend)) {
            is LspTools.SymbolsOutcome.Ok -> FastExecResult(
                toolName = toolName,
                callId = call.callId,
                resultJson = buildJsonObject {
                    put("tool", toolName)
                    put("callId", call.callId)
                    put("denied", false)
                    put("fromStub", outcome.fromStub)
                    putJsonArray("symbols") {
                        for (s in outcome.symbols) {
                            addJsonObject {
                                put("name", s.name)
                                put("kind", s.kind)
                                put("path", s.path)
                                put("line", s.line)
                            }
                        }
                    }
                }.toString(),
            )
            is LspTools.SymbolsOutcome.Unavailable -> FastExecResult(
                toolName = toolName,
                callId = call.callId,
                resultJson = outcome.message,
                denied = true,
                denyCode = "UNAVAILABLE",
            )
        }
    }
}
