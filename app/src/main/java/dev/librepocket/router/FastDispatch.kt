package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolDef
import dev.librepocket.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * B3 (#19) fast-lane dispatch binding: [RouteDecision] → executor.
 *
 * Pure logic, no Android dependencies. This layer decides NOTHING about
 * intent: [FastRouter] owns routing (FAST vs DENIED + clarification), and
 * this dispatcher only executes a FAST decision when every gate below holds.
 * Anything else is [FastDispatchOutcome.Skipped]. A skip with
 * [FastDispatchOutcome.Skipped.attempted] == false means genuinely zero
 * executor effects (all resolving happened before `executor.execute(...)`
 * was entered); a skip with `attempted` == true means the executor was
 * entered and may have effects (e.g. the backend was called) — pinned by
 * `FastDispatchTest`, including the clarification path which must never
 * execute even when an executor IS registered for that tool.
 *
 * Dispatch gates, in order (fail-closed; gates 1–7 and gate 8's non-positive
 * `timeoutMs` branch trip before any executor entry; the rest — including
 * gate 8's timeout branch and gates 9–11 — trip at or after it; see
 * `attempted`):
 * 1. the decision must be FAST with a non-null [ToolCall] (DENIED /
 *    NEED_CLARIFICATION / no-match intents never execute). A hand-built
 *    FAST decision with a null [ToolCall] is malformed and fails closed
 *    with the fixed non-echoing no-match message
 *    ([FallbackMessages.forNoMatch] with an empty intent — never a
 *    message-less skip, never the hand-built decision's own message);
 * 2. the tool must resolve via [lookup] — a lookup that throws (non-cancel)
 *    maps to [FastDispatchOutcome.Skipped] with denyCode `LOOKUP_ERROR`;
 *    unknown names fail closed with `UNKNOWN_TOOL`;
 * 3. the resolved tool's identity must equal the call's tool name
 *    (`tool.name == call.toolName`) — checked BEFORE any projection or
 *    execution. An injected [lookup] that returns a DIFFERENT [ToolDef]
 *    than requested (e.g. a ready, READ-only tool for an unready WRITE
 *    call) fails closed with denyCode `LOOKUP_IDENTITY_MISMATCH`; the
 *    returned tool's name is never interpolated (it may be
 *    attacker-controlled content) — the fixed non-echoing message applies,
 *    like the lookup-failure paths;
 * 4. a dispatch-time re-projection (SAME snapshot the decision saw — NOT a
 *    freshness signal; [decideAndDispatch] re-validates the same
 *    snapshot) must be NATIVE (any non-NATIVE
 *    projection — UNAVAILABLE or DEGRADED — denies, mirroring the slice's
 *    NATIVE-only rule; DEGRADED tools never execute on the fast lane. The
 *    ready `lsp.symbols` projects NATIVE once its switch is on, so the
 *    pinned suite is unaffected);
 * 5. the tool must be `executionReady` (registry readiness; B3 flips no
 *    flags — today only `lsp.symbols` qualifies);
 * 6. the tool must be [SideEffect.READ] (defense in depth: WRITE/PRIVILEGED
 *    tools need the confirmation pipeline before any execution, which lives
 *    in a future wiring PR — this dispatcher refuses them even if ready);
 * 7. an executor must be registered for the tool name, and the executor's
 *    [FastToolExecutor.toolName] must equal the call's tool name — checked
 *    BEFORE any execution (a mismatched executor never runs; map keys and
 *    executor self-descriptions are never trusted);
 * 8. the executor call runs under `tool.annotations.timeoutMs`
 *    (`withTimeout`); a non-positive budget is a fail-closed typed
 *    `TIMEOUT` denial with zero backend invocations (`attempted=false` —
 *    executor never entered), and a `withTimeout`
 *    expiry maps to [FastDispatchOutcome.Skipped] with denyCode `TIMEOUT`
 *    — a typed failure, never a propagated cancel (`attempted=true` —
 *    executor was entered);
 * 9. AFTER the executor returns, its [FastExecResult.toolName] and
 *    [FastExecResult.callId] must equal the call's, else
 *    [FastDispatchOutcome.Skipped] with denyCode `RESULT_IDENTITY_MISMATCH`
 *    — executor was entered (`attempted=true`); zero propagation: a forged
 *    result never rides the outcome;
 * 10. executor denials (typed [FastExecResult.denied]) — executor was
 *    entered (`attempted=true`) — never reach the caller as executions:
 *    `SCHEMA_INVALID` → [DenyReason.NEED_CLARIFICATION],
 *    anything else (including `UNAVAILABLE`) → [DenyReason.USER_DISABLED].
 *    The executor's `denyCode` rides through on
 *    [FastDispatchOutcome.Skipped.denyCode] only when it matches the strict
 *    allowlist `^[A-Z0-9_]{1,64}$`; anything else maps to `EXEC_ERROR` —
 *    raw executor/backend text never does; `fallbackMessage` is always the
 *    fixed failure string (deny codes preserved on the outcome:
 *    `SCHEMA_INVALID` / `UNAVAILABLE` / `TIMEOUT` / `LOOKUP_ERROR` /
 *    `UNKNOWN_TOOL` / `LOOKUP_IDENTITY_MISMATCH` / `PROJECTION_ERROR` /
 *    `EXECUTOR_IDENTITY_MISMATCH` / `RESULT_IDENTITY_MISMATCH` / `EXEC_ERROR`);
 * 11. defense in depth on the executed path: a result whose serialized
 *    `resultJson` exceeds [SafeToolSlice.MAX_RESULT_CHARS] OR fails a
 *    structural check (`Json.parseToJsonElement` must succeed and the root
 *    must be a `JsonObject`) never rides the outcome —
 *    [FastDispatchOutcome.Executed] is withheld and the call maps
 *    to [FastDispatchOutcome.Skipped] with denyCode `EXEC_ERROR`
 *    (`attempted=true` — executor was entered;
 *    the built-in executor is already bounded; this is the trust-boundary
 *    backstop for misbehaving or future executors). The
 *    [FastDispatchOutcome.Executed] outcome also carries the SAME bounded
 *    callId form as the receipt channel (redact → ≤[SafeToolSlice.MAX_STRING_CHARS]
 *    pass-through → cap + `#`-SHA-256 suffix), never the raw [ToolCall.callId];
 *    Executed carries bounded callId and NO executor denyCode;
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
    executors: Map<String, FastToolExecutor> = emptyMap(),
    private val lookup: (String) -> ToolDef? = { ToolRegistry.find(it) },
) {
    /**
     * 建構期 snapshot（`toMap()` defensive copy）：呼叫端在建構後對傳入 map
     * 的任何 mutation（含移除 executor）都不影響本 dispatcher。
     */
    private val executors: Map<String, FastToolExecutor> = executors.toMap()

    /**
     * 固定失敗話術：prefix 一致（`執行` + 可信工具名或 `工具` 占位 + `失敗，請重試或手動操作`）；
     * 工具名只來自 registry 解析結果（`tool.name`）或 null 占位——不含任何
     * model 可影響字串（`call.toolName` 不可插值進話術）；無 registry 解析時
     * 用不回音的固定占位（lookup 系列失敗不放行 call.toolName）。
     */
    private fun fixedFailureMessage(toolName: String?): String =
        "執行${toolName ?: "工具"}失敗，請重試或手動操作"

    /**
     * executor 提供的 denyCode allowlist：只保留 `^[A-Z0-9_]{1,64}$`
     * （涵蓋本切片已知型別碼如 `SCHEMA_INVALID` / `UNAVAILABLE` /
     * `EXEC_ERROR`）；不符者一律映射為 `EXEC_ERROR`（fail-closed）。
     * dispatcher 內部產生的碼（皆 `[A-Z_]{≤32}`）不經此函數，原樣放行。
     */
    private fun boundedDenyCode(code: String?): String =
        if (code != null && DENY_CODE_ALLOWLIST.matches(code)) code else "EXEC_ERROR"

    /**
     * Dispatches one [RouteDecision]. Never throws except
     * [CancellationException] (a cancelled dispatch aborts before the
     * executor runs; a timeout is NOT a cancel — it maps to a typed
     * `TIMEOUT` skip), and fatal [Error]s from misbehaving executors also
     * propagate; executor failures (non-cancel) become [Skipped] with a
     * fixed message — backend text never rides the outcome.
     */
    suspend fun dispatch(
        decision: RouteDecision,
        ctx: ProjectionContext,
    ): FastDispatchOutcome {
        val call = decision.toolCall
        if (decision.route != RouteKind.FAST || call == null) {
            if (decision.route == RouteKind.FAST && call == null) {
                // Malformed hand-built FAST decision: fail-closed with the
                // fixed non-echoing no-match message — never a message-less
                // skip, and never a hand-built decision's own message text.
                // attempted=false（executor 不會被進入）；reasonCode 原樣攜帶。
                return FastDispatchOutcome.Skipped(decision.reasonCode, FallbackMessages.forNoMatch(""))
            }
            return FastDispatchOutcome.Skipped(decision.reasonCode, decision.fallbackMessage)
        }
        coroutineContext.ensureActive()
        val tool = try {
            lookup(call.toolName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 查詢自身爆掉（非取消）：fail-closed，永不拋，永不回音例外訊息；
            // call.toolName 是 model 可影響字串，無 registry 解析時不回音。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(null), denyCode = "LOOKUP_ERROR")
        }
            ?: return FastDispatchOutcome.Skipped(null, fixedFailureMessage(null), denyCode = "UNKNOWN_TOOL")
        if (tool.name != call.toolName) {
            // lookup 回傳了「非所要求」的工具定義：fail-closed。回傳 ToolDef 的
            // name 來自注入的 lookup callback（可能含攻擊者構造內容），話術一律
            // 用不回音的固定字串——與 LOOKUP_ERROR / UNKNOWN_TOOL 同一模子。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(null), denyCode = "LOOKUP_IDENTITY_MISMATCH")
        }
        val projection = try {
            tool.project(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 投影自身爆掉（非取消）→ typed skip，永不拋。此路徑為防禦縱深：
            // `ToolDef.project()` 目前是 final+pure，今日不可測試；待未來工具加入
            // throwing 行為才會觸發——刻意保留，不算測試缺口。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "PROJECTION_ERROR")
        }
        if (projection.level != CapabilityLevel.NATIVE) {
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
        if (executor.toolName != call.toolName) {
            // 先於任何效應：執行器自述身分與 call 不符即 fail-closed（map key 與
            // executor 皆不可信）。工具名來自 registry 解析結果（tool.name，
            // 較 call.toolName 更可信），可安全插值。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "EXECUTOR_IDENTITY_MISMATCH")
        }
        coroutineContext.ensureActive()
        // 非正的 per-call budget（0/負值）直接 fail-closed 型別化拒絕：零 backend
        // 呼叫，與切片「非正 budget＝即刻型別化逾時」語義一致（絕不視為無上限）。
        val timeoutMs = tool.annotations.timeoutMs
        if (timeoutMs <= 0) {
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "TIMEOUT")
        }
        val result = try {
            withTimeout(timeoutMs) { executor.execute(call, ctx) }
        } catch (e: TimeoutCancellationException) {
            // 逾時是型別化失敗（不等於呼叫端取消）：typed skip，不外拋。
            // 注意此 catch 必須排在 generic CancellationException 之前——
            // TimeoutCancellationException 是 CancellationException 子類別。
            // 刻意映射：misbehaving executor 自拋 TimeoutCancellationException 一律
            // 映射為 TIMEOUT（fail-closed、typed），不外拋。
            // 若呼叫端恰在此刻並發取消，外部取消優先：ensureActive() 重拋
            // CancellationException；否則維持 typed TIMEOUT skip。
            coroutineContext.ensureActive()
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "TIMEOUT", attempted = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fixed receipt: never interpolate executor/backend messages (they
            // may carry paths or secrets, and this string is user-visible).
            // executor 已被進入：attempted=true。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "EXEC_ERROR", attempted = true)
        }
        coroutineContext.ensureActive()
        if (result.toolName != call.toolName || result.callId != call.callId) {
            // 零傳播：偽造的 result（錯誤 toolName 或 callId）永遠不放行。
            // 工具名來自 registry 解析結果（tool.name），可安全插值。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "RESULT_IDENTITY_MISMATCH", attempted = true)
        }
        if (result.denied) {
            // 型別化映射：SCHEMA_INVALID → NEED_CLARIFICATION（補參）；
            // 其餘（含 UNAVAILABLE）→ USER_DISABLED。resultJson 永不放行。
            // executor 提供的 denyCode 先過 allowlist（boundedDenyCode）；
            // 不符者一律映射為 EXEC_ERROR。
            val code = boundedDenyCode(result.denyCode)
            val reason = if (code == "SCHEMA_INVALID") {
                DenyReason.NEED_CLARIFICATION
            } else {
                DenyReason.USER_DISABLED
            }
            return FastDispatchOutcome.Skipped(reason, fixedFailureMessage(tool.name), denyCode = code, attempted = true)
        }
        if (result.resultJson.length > SafeToolSlice.MAX_RESULT_CHARS) {
            // 縱深防禦：misbehaving／未來 executor 不得騎超預算輸出（內建 executor
            // 已自限；此為 trust-boundary backstop）——超預算一律型別化 EXEC_ERROR。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "EXEC_ERROR", attempted = true)
        }
        if (!isJsonObjectDocument(result.resultJson)) {
            // 結構檢查（gate 11）：resultJson 必須可解析且 root 為 JsonObject；
            // 否則視同超預算，mapping 至同一條 EXEC_ERROR backstop（denied）。
            // 非法 JSON／array／primitive 一律不放行；backend 話術永不騎 outcome。
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "EXEC_ERROR", attempted = true)
        }
        // Executed outcome 攜帶有界 callId（與 receipt channel 一致）：gate 9 的
        // identity 比對已於 outcome 之前以 raw call.callId 對 raw 完成，outcome
        // 不再做任何 identity 比對，故此處以有界形態放行——raw callId 永不騎
        // Executed outcome；executor 提供的 denyCode 一律不騎（denyCode = null）。
        // 有界化自身爆掉（非取消、非 fatal Error）→ 型別化 EXEC_ERROR skip，
        // 維持 contract「never throws except CancellationException / fatal Error」。
        val boundedId = try {
            boundedCallId(result.callId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "EXEC_ERROR", attempted = true)
        }
        return FastDispatchOutcome.Executed(result.copy(callId = boundedId, denyCode = null))
    }

    /**
     * One-call fast lane: route the intent text, then dispatch the decision.
     * Convenience for tests and future callers; carries no extra semantics
     * beyond [FastRouter.decide] + [dispatch] with the same [ctx].
     *
     * The SAME [ctx] snapshot is passed to both decide and dispatch, so the
     * gate-4 (dispatch-time re-projection) re-check inside [dispatch] re-validates the same
     * snapshot the router saw — it is a same-snapshot recheck, NOT a
     * freshness signal. Freshness still requires the caller to re-read
     * [ProjectionContext] per call; a future wiring PR owns the
     * `ctxSupplier` shape (mirroring the slice's constructor-injected
     * supplier). The API stays unchanged.
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

    /**
     * Structural validity of an executed-result payload (gate 11): the
     * [resultJson] must parse as JSON and its root must be a [JsonObject].
     * Anything else — unparseable text, an array, a primitive — maps to the
     * same over-budget EXEC_ERROR backstop.
     */
    private fun isJsonObjectDocument(resultJson: String): Boolean = try {
        Json.parseToJsonElement(resultJson) is JsonObject
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        /**
         * executor-provided denyCode allowlist（原 instance val；無 instance
         * 依賴，hoist 至 companion）：只保留 `^[A-Z0-9_]{1,64}$`。
         */
        private val DENY_CODE_ALLOWLIST = Regex("^[A-Z0-9_]{1,64}$")
    }
}

/** One fast-lane attempt: the routing decision plus its dispatch outcome. */
data class FastLaneResult(
    val decision: RouteDecision,
    val outcome: FastDispatchOutcome,
)

/**
 * Typed execution result from a [FastToolExecutor].
 *
 * Note: [callId] as produced by an executor may be raw; [FastDispatcher]
 * binds the [FastDispatchOutcome.Executed] outcome to the SAME bounded
 * callId form used by the receipt channel (redact → ≤[SafeToolSlice.MAX_STRING_CHARS]
 * pass-through → surrogate-safe cap + `#` + 32-hex SHA-256 of the full
 * redacted string) before the outcome rides — the raw [ToolCall.callId]
 * never rides [FastDispatchOutcome.Executed].
 * Executed carries bounded callId and NO executor denyCode.
 */
data class FastExecResult(
    val toolName: String,
    val callId: String,
    val resultJson: String,
    val denied: Boolean = false,
    val denyCode: String? = null,
)

/**
 * Dispatch outcome: executed exactly once, or skipped. A skip is NOT
 * necessarily effect-free — see [FastDispatchOutcome.Skipped.attempted].
 */
sealed interface FastDispatchOutcome {
    /**
     * Executed exactly once. The carried [FastExecResult.callId] is the
     * SAME bounded form used by the receipt channel (redact → ≤cap
     * pass-through → cap + `#`-SHA-256 suffix), NOT the raw
     * [ToolCall.callId]; the raw-vs-raw identity comparison happens inside
     * [FastDispatcher.dispatch] (gate 9) before this outcome is produced,
     * and the outcome path performs no identity comparison.
     * Executed carries bounded callId and NO executor denyCode.
     */
    data class Executed(val result: FastExecResult) : FastDispatchOutcome

    /**
     * Skipped.
     *
     * [attempted] is false ⇔ the dispatcher resolved everything BEFORE
     * entering `executor.execute(...)` (all gates 1–7, plus the non-positive
     * timeout budget check) — genuinely zero executor effects.
     * [attempted] is true ⇔ `executor.execute(...)` was entered (timeout
     * mid-flight, executor exception, denied result, post-execute identity /
     * size checks) — every construction site sets it only on a post-entry
     * path; the flag reflects the executor/backend having been entered and
     * able to have partially executed (e.g. the backend was called once
     * before an exception).
     *
     * [denyCode] carries a typed code for audit: either an executor-provided
     * upstream code passed through after the `^[A-Z0-9_]{1,64}$` shape
     * allowlist (`SCHEMA_INVALID` / `UNAVAILABLE`-class codes pass through
     * verbatim; only shape-violating codes map to `EXEC_ERROR`) or a
     * dispatcher-internal code (`UNKNOWN_TOOL` / `LOOKUP_ERROR` /
     * `LOOKUP_IDENTITY_MISMATCH` / `PROJECTION_ERROR` /
     * `EXECUTOR_IDENTITY_MISMATCH` / `RESULT_IDENTITY_MISMATCH` /
     * `TIMEOUT` / `EXEC_ERROR`). Null when a code is
     * not applicable (e.g. a non-FAST decision).
     *
     * [fallbackMessage] remains a fixed, curated, non-interpolated string —
     * never executor/backend text.
     */
    data class Skipped(val reasonCode: DenyReason?, val fallbackMessage: String?, val denyCode: String? = null, val attempted: Boolean = false) : FastDispatchOutcome
}

/**
 * One named tool executor for the fast lane.
 *
 * Implementations must be pure-JVM-callable, honor coroutine cancellation
 * promptly, and never return secrets in [FastExecResult.resultJson] (results
 * may ride provider continuations). Expected denials (e.g. blank query)
 * are typed ([FastExecResult.denied]); unexpected failures may throw and are
 * converted to [FastDispatchOutcome.Skipped] with a fixed message by
 * [FastDispatcher] — a caller-side [CancellationException] propagates; a
 * `withTimeout` expiry maps to a `TIMEOUT` skip, not a cancel.
 */
interface FastToolExecutor {
    val toolName: String
    suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult
}

/**
 * `lsp.symbols` fast-lane executor over an injected [LspTools.Backend]
 * (default [LspTools.StubBackend]: pure computation, no IO).
 *
 * Argument shape mirrors the [FastRouter] template and the slice's
 * [dev.librepocket.router.SafeToolSlice] schema: a JSON object with a
 * non-blank string `query`; optional `limit` (strict integer clamped
 * 1..200, JSON strings rejected), optional `pathPrefix` string.
 *
 * Input caps (all fail-closed to a typed `SCHEMA_INVALID` denial with zero
 * backend invocations):
 * - gross arguments ≤ [SafeToolSlice.MAX_GROSS_ARGS_CHARS] (16 KiB) and
 *   nesting depth ≤ [SafeToolSlice.MAX_NESTING_DEPTH] (32), checked before
 *   parsing (mirrors the slice's pre-parse fan-close);
 * - `query` ≤ [SafeToolSlice.MAX_INPUT_CHARS], no ISO control chars / NUL /
 *   Unicode FORMAT code points;
 * - `pathPrefix` ≤ [SafeToolSlice.MAX_INPUT_CHARS], must be NFKC-stable, and
 *   must pass the shared [isSafeInput] predicate (no `..`, no leading `/`
 *   after trimming, no backslash, no NUL, no ISO control chars, no
 *   percent-escapes, no `:`, no FORMAT code points).
 *
 * Output caps (Ok branch): at most the requested limit is itemized —
 * `args.limit` (defaulted to [LspTools.DEFAULT_LIMIT] when absent), a strict
 * integer clamped into `1..MAX_LIMIT`; [LspTools.MAX_LIMIT] is the absolute
 * cap — the requested limit is stricter. A backend returning more
 * symbols than the effective limit violates the backend contract; the
 * excess is reported as `omittedCount` and flags `truncated`. Every
 * symbol field (`name` / `kind` / `path`) is pre-bounded to
 * [SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS] (over-bound values replaced
 * whole by a fixed receipt — a truncated prefix could defeat suffix-anchored
 * redaction rules), then redacted, then a >[SafeToolSlice.MAX_STRING_CHARS]
 * post-redaction length flags truncation; `line` passes through as Int;
 * `query` and `callId` echoes are redacted; `query` is capped to
 * [SafeToolSlice.MAX_STRING_CHARS] (surrogate-safe [cappedTake]) while
 * `callId` uses [boundedCallId] (slice `boundedRedactedId` semantics:
 * redact → ≤cap pass through → surrogate-safe cap + `#` + 32-hex SHA-256
 * of the full redacted string); raw ids never ride the receipt. The
 * serialized result JSON is shrunk item-wise
 * to [SafeToolSlice.MAX_RESULT_CHARS] (dropping the tail, bumping
 * `omittedCount`); a single-symbol remainder still over budget is a typed
 * `EXEC_ERROR` denial — never over-budget output. `omittedCount` counts both
 * the item-cap drop and shrink-loop drops; `truncated` is true when any
 * field was truncated/raw-omitted/over-cap or `omittedCount > 0`.
 *
 * Denials are typed results (not throws, except [CancellationException]);
 * [LspTools.SymbolsOutcome.Unavailable] maps to a fixed minimal JSON with
 * denyCode `UNAVAILABLE` — the backend's message never rides the receipt.
 * An `Exception` thrown by the backend inside [LspTools.symbols] itself
 * maps to a fixed minimal JSON with denyCode `EXEC_ERROR` — the backend's
 * message never rides the receipt either.
 *
 * [okResult] carries its own internal boundary (defense in depth): a
 * projection-time `Exception` (snapshot/iterator/reduce/redaction) maps to
 * the same fixed minimal JSON with denyCode `EXEC_ERROR` instead of
 * reaching the call-site catch in [execute] — same outcome type, same
 * fixed message.
 *
 * Kept in sync with [SafeToolSlice] by hand (same module, same constants):
 * the copied predicates ([jsonNestingDepth], [containsFormatCodePoint],
 * [isSafeInput], [boundedRawBackendField], [cappedTake], [cappedEcho]), the
 * copied [PAIRING_SUFFIX_CHARS] constant, the [boundedCallId] port, and the
 * [okResult]/[LspSymbolsExecutor.parseArgs] pair (this file's Ok-receipt
 * pipeline mirrors the slice's `ok()`/`parseArgs()` item-cap clamp, effective
 * limit, `omittedCount`/`truncated` computation — edit together with their
 * slice counterparts).
 */
class LspSymbolsExecutor(
    private val backend: LspTools.Backend = LspTools.StubBackend,
) : FastToolExecutor {
    override val toolName: String = LspTools.SYMBOLS_NAME

    private data class LspArgs(val query: String, val pathPrefix: String?, val limit: Int)

    private fun parseArgs(argumentsJson: String): LspArgs? {
        // 粗防線：與切片相同的 gross 上限與巢狀深度 pre-scan（在任何 parse 之前）。
        if (argumentsJson.length > SafeToolSlice.MAX_GROSS_ARGS_CHARS) return null
        if (jsonNestingDepth(argumentsJson) > SafeToolSlice.MAX_NESTING_DEPTH) return null
        val root = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: Exception) {
            return null
        }
        if (root !is JsonObject) return null
        val query = (root["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it.length <= SafeToolSlice.MAX_INPUT_CHARS }
            ?.takeIf { it.none { c -> c.isISOControl() || c == '\u0000' } }
            ?.takeIf { !containsFormatCodePoint(it) } ?: return null
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
            is JsonPrimitive -> {
                if (!raw.isString) return null
                val v = raw.content
                if (v.length > SafeToolSlice.MAX_INPUT_CHARS) return null
                // Reject any input that changes under NFKC normalization.
                if (Normalizer.normalize(v, Normalizer.Form.NFKC) != v) return null
                if (!isSafeInput(v)) return null
                v
            }
            else -> return null
        }
        return LspArgs(query, pathPrefix, limit)
    }

    override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
        val args = parseArgs(call.argumentsJson)
            ?: return deniedResult(call, "SCHEMA_INVALID")
        // 後端在 symbols() 內部爆掉（非取消）→ typed denial（EXEC_ERROR），非 turn 失敗。
        val outcome = try {
            LspTools.symbols(args.query, args.pathPrefix, args.limit, backend)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // backend message 永不放行：固定最小 receipt。
            return deniedResult(call, "EXEC_ERROR")
        }
        return when (outcome) {
            is LspTools.SymbolsOutcome.Ok -> try {
                okResult(call, args.query, outcome, args.limit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // now redundant belt-and-braces; internal okResult boundary
                // handles Exception（okResult 內部邊界先轉為 typed EXEC_ERROR）。
                // 保留此 catch（無害）；backend 話術永不放行；非 turn 失敗。
                deniedResult(call, "EXEC_ERROR")
            }
            is LspTools.SymbolsOutcome.Unavailable ->
                // 固定最小 receipt：後端 message 永不放行。
                deniedResult(call, "UNAVAILABLE")
        }
    }

    private fun okResult(
        call: ToolCall,
        query: String,
        outcome: LspTools.SymbolsOutcome.Ok,
        requestedLimit: Int,
    ): FastExecResult {
        // 內部邊界（縱深防禦）：snapshot／iterator／reduce 例外 → typed denial
        // （EXEC_ERROR），與 call-site catch 同 outcome 型別、同固定話術；
        // backend 話術永不放行；dispatch 端 attempted=true。
        // CancellationException 不跨邊界——原樣外拋。
        return try {
            projectOkResult(call, query, outcome, requestedLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            deniedResult(call, "EXEC_ERROR")
        }
    }

    private fun projectOkResult(
        call: ToolCall,
        query: String,
        outcome: LspTools.SymbolsOutcome.Ok,
        requestedLimit: Int,
    ): FastExecResult {
        // 一次快照：以「請求的 limit」（預設 DEFAULT_LIMIT、coerceIn 保險）做上限
        // 擷取；MAX_LIMIT 是絕對上限，請求上限更嚴格。後端超給屬 backend
        // contract violation，只數一次 → limitOmitted。
        val effectiveLimit = requestedLimit.coerceIn(1, LspTools.MAX_LIMIT)
        val available = outcome.symbols.take(effectiveLimit)
        val limitOmitted = (outcome.symbols.size - available.size).coerceAtLeast(0)
        val redactedQuery = dev.librepocket.redact.Redactor.redact(query).text
        // 截斷判定一律以 POST-redaction 長度為準（redaction 可能膨風）。
        var anyFieldTruncated = redactedQuery.length > SafeToolSlice.MAX_STRING_CHARS
        val redactedSymbols = available.map { s ->
            val rawOmitted = s.name.length > SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS ||
                s.kind.length > SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS ||
                s.path.length > SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS
            val rn = dev.librepocket.redact.Redactor.redact(boundedRawBackendField(s.name)).text
            val rk = dev.librepocket.redact.Redactor.redact(boundedRawBackendField(s.kind)).text
            val rp = dev.librepocket.redact.Redactor.redact(boundedRawBackendField(s.path)).text
            if (rawOmitted || rn.length > SafeToolSlice.MAX_STRING_CHARS ||
                rk.length > SafeToolSlice.MAX_STRING_CHARS ||
                rp.length > SafeToolSlice.MAX_STRING_CHARS
            ) {
                anyFieldTruncated = true
            }
            Triple(rn, rk, rp)
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
        val boundedId = boundedCallId(call.callId)
        fun build(chosen: List<JsonObject>, omittedCount: Int, truncated: Boolean): JsonObject =
            buildJsonObject {
                put("tool", toolName)
                put("callId", boundedId)
                put("denied", false)
                put("query", cappedEcho(redactedQuery))
                put("fromStub", outcome.fromStub)
                put("symbols", buildJsonArray { for (o in chosen) add(o) })
                put("returnedCount", chosen.size)
                put("omittedCount", omittedCount)
                put("truncated", truncated)
            }
        val chosen = ArrayList(symbolJsons)
        // 逐步縮減到總預算：最壞情況為 O(n²) re-serialize，但輸入已被
        // effective item cap（≤ MAX_LIMIT）與 per-field cap 限界，屬可接受成本。
        while (chosen.size > 1) {
            val omittedCount = limitOmitted + (symbolJsons.size - chosen.size)
            val truncated = anyFieldTruncated || omittedCount > 0
            val candidate = build(chosen, omittedCount, truncated).toString()
            if (candidate.length <= SafeToolSlice.MAX_RESULT_CHARS) break
            chosen.removeAt(chosen.lastIndex)
        }
        val omittedCount = limitOmitted + (symbolJsons.size - chosen.size)
        val truncated = anyFieldTruncated || omittedCount > 0
        val json = build(chosen, omittedCount, truncated).toString()
        if (json.length > SafeToolSlice.MAX_RESULT_CHARS) {
            // 單一 symbol 仍超預算：typed denial，絕不放行超預算輸出。
            return deniedResult(call, "EXEC_ERROR")
        }
        return FastExecResult(
            toolName = toolName,
            callId = call.callId,
            resultJson = json,
        )
    }

    /** 固定最小 deny receipt：`tool` + `code` + `denied` + 有界 `callId`。 */
    private fun deniedResult(call: ToolCall, code: String): FastExecResult =
        FastExecResult(
            toolName = toolName,
            callId = call.callId,
            resultJson = buildJsonObject {
                put("denied", true)
                put("code", code)
                put("tool", toolName)
                put("callId", boundedCallId(call.callId))
            }.toString(),
            denied = true,
            denyCode = code,
        )

    /**
     * Must stay in sync with SafeToolSlice.cappedEcho — identical logic and
     * marker format; only the reference constants differ (this file quotes
     * SafeToolSlice's MAX_STRING_CHARS directly).
     */
    private fun cappedEcho(value: String): String =
        if (value.length > SafeToolSlice.MAX_STRING_CHARS) {
            cappedTake(value, SafeToolSlice.MAX_STRING_CHARS) +
                "…[truncated ${value.length}→${SafeToolSlice.MAX_STRING_CHARS}]"
        } else {
            value
        }

    /**
     * Pre-redaction bound for backend-owned strings (mirrors the slice's
     * [SafeToolSlice.boundedRawBackendField]).
     *
     * must stay in sync with SafeToolSlice.boundedRawBackendField
     */
    private fun boundedRawBackendField(value: String): String =
        if (value.length <= SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS) value
        else "[backend field omitted]"

    /**
     * Computes max JSON nesting depth while ignoring string contents.
     *
     * must stay in sync with SafeToolSlice.jsonNestingDepth; the pinned
     * kotlinx-serialization 1.12.0 also enforces its own 400-layer limit,
     * this mirror keeps behavior aligned.
     */
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

    /**
     * Returns true if [s] contains any Unicode FORMAT (Cf) code points.
     * Scans by code point so supplementary-plane format characters are
     * properly recognized.
     *
     * must stay in sync with SafeToolSlice.containsFormatCodePoint
     */
    private fun containsFormatCodePoint(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val codePoint = s.codePointAt(i)
            if (Character.getType(codePoint) == Character.FORMAT.toInt()) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * Fail-closed scope guard (mirrors the slice's [SafeToolSlice.isSafeInput]):
     * no `..`, no leading `/` after trimming, no backslash, NUL, ISO control
     * chars, percent-escapes, `:`, or FORMAT code points.
     *
     * must stay in sync with SafeToolSlice.isSafeInput
     */
    private fun isSafeInput(v: String): Boolean {
        if (v.contains("..")) return false
        if (containsFormatCodePoint(v)) return false
        if (v.trimStart().startsWith("/")) return false
        if (v.contains("\\")) return false
        if (v.contains("\u0000")) return false
        if (v.contains("%")) return false
        if (v.contains(":")) return false
        if (v.any { it.isISOControl() }) return false
        return true
    }
}

/**
 * Over-cap redacted id 的確定性配對後綴長度：`#` + 32-hex（128 位元
 * SHA-256 前綴）＝ `1 + 16 * 2` UTF-16 units。
 *
 * must stay in sync with SafeToolSlice.PAIRING_SUFFIX_CHARS
 */
private const val PAIRING_SUFFIX_CHARS = 1 + 16 * 2

/**
 * Surrogate-safe cap slice.
 *
 * must stay in sync with SafeToolSlice.cappedTake
 */
private fun cappedTake(value: String, cap: Int): String {
    if (value.length <= cap) return value
    var end = cap
    while (end > 0 && value[end - 1].code in 0xD800..0xDBFF) {
        end--
    }
    return value.substring(0, end)
}

/**
 * Bounded, collision-disambiguated redacted id（對 slice
 * `boundedRedactedId` 語義的忠實 port）：先 redact（可能膨風），redacted
 * 長度 ≤ [SafeToolSlice.MAX_STRING_CHARS] 原樣放行；否則 surrogate-safe
 * [cappedTake] 到 `MAX_STRING_CHARS - PAIRING_SUFFIX_CHARS` 前綴，接上
 * `#` + 對「完整 redacted 字串」的 SHA-256 前 16 bytes 摘要（小寫 32-hex）。
 * 結果長度永不超過 [SafeToolSlice.MAX_STRING_CHARS]。
 *
 * must stay in sync with SafeToolSlice.boundedRedactedId
 *
 * File-private so [FastDispatcher] can bind its [FastDispatchOutcome.Executed]
 * outcome to the same bounded form used by the receipt channel.
 */
private fun boundedCallId(callId: String): String {
    val redacted = dev.librepocket.redact.Redactor.redact(callId).text
    if (redacted.length <= SafeToolSlice.MAX_STRING_CHARS) return redacted
    val digest = MessageDigest.getInstance("SHA-256").digest(redacted.toByteArray(Charsets.UTF_8))
    val hex = digest.take(16).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
    val suffix = "#$hex"
    return cappedTake(redacted, SafeToolSlice.MAX_STRING_CHARS - PAIRING_SUFFIX_CHARS) + suffix
}
