package dev.librepocket.router

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolDef
import dev.librepocket.tool.ToolRegistry
import java.security.MessageDigest
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
 * 4. a dispatch-time re-projection must be NATIVE (any non-NATIVE
 *    projection — UNAVAILABLE or DEGRADED — denies, mirroring the slice's
 *    NATIVE-only rule; DEGRADED tools never execute on the fast lane. The
 *    ready `lsp.symbols` projects NATIVE once its switch is on, so the
 *    pinned suite is unaffected). In WIRED mode ([dispatchFresh]) this
 *    projection runs on a FRESH [ProjectionContext] obtained from the
 *    constructor-injected `ctxSupplier` — never the decision's snapshot;
 *    in the LEGACY test entry ([dispatch]) the caller's snapshot is reused
 *    and is explicitly NOT a freshness signal;
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
 * WIRED MODE (issue #178 Stage 1–2, still INTERNAL / UNWIRED): constructed
 * with `ctxSupplier` + `policy` + `auditSink`, [dispatchFresh] re-projects a
 * FRESH context, evaluates fresh `tool.exec` policy (ALLOW only — ASK is
 * never treated as ALLOW), binds an immutable [FastConfirmationSnapshot],
 * re-verifies authorization immediately before side effects, and writes a
 * redacted Room-backed audit row before entering the executor (at most one
 * row per wired dispatch: the ALLOW path and the policy/confirmation/reverify
 * skips write it; earlier malformed/lookup/projection skips do not). Every
 * gate failure is a typed skip with zero backend side effects; an audit
 * write failure on the ALLOW path is a typed `AUDIT_ERROR` skip with zero
 * execution.
 * The legacy two-arg [dispatch] / [decideAndDispatch] remain TEST-ONLY
 * compatibility entries: they run the original gates WITHOUT fresh policy /
 * confirmation / audit and must never be called from production paths.
 *
 * Explicitly NOT done here (later stages / #19 B4): production callers,
 * Android executors, `executionReady` flips, device-verified E2E, and the
 * availability/CPU-budget hard gate. There is deliberately NO production
 * caller: [dev.librepocket.chat.TurnController] and `ChatSessionFactory` stay
 * records-only (`toolSlice = null` pinned), so the matrix stays
 * wired=false/verified=false and `executionReady` keeps its registry-only
 * meaning.
 *
 * Device E2E: PENDING — no device in this environment; coverage is JVM-only
 * (`FastDispatchTest` / `FastDispatchGatesTest` with fake backends). The
 * wired/verified flips wait for the E2E PR.
 *
 * INTERNAL / UNWIRED EXECUTION PATH — not reachable from production composition; wiring still requires device E2E and a separate security review (see docs/CAPABILITY_MATRIX.md §啟用前置). Do not add callers outside this file without a separate security review.
 */
internal class FastDispatcher(
    executors: Map<String, FastToolExecutor> = emptyMap(),
    private val lookup: (String) -> ToolDef? = { ToolRegistry.find(it) },
    /**
     * WIRED 模式三件套：每次 [dispatchFresh] 重新取得 [ProjectionContext]、
     * 每次重新 [PolicyStore.evaluateFresh]，且副作用前必須寫入一筆 audit。
     * 三者必須同時提供；只給一部分即 `require` fail-fast，避免半接線的
     * 「看起來有 policy 但沒有 fresh ctx／audit」狀態。全不給則是 legacy
     * TEST-ONLY 入口（既有 `FastDispatchTest` 相容性，見 [dispatch]）。
     */
    private val ctxSupplier: (() -> ProjectionContext)? = null,
    private val policy: PolicyStore? = null,
    private val auditSink: FastToolAuditSink? = null,
) {
    init {
        val wiredParts = listOf(ctxSupplier, policy, auditSink).count { it != null }
        require(wiredParts == 0 || wiredParts == 3) {
            "FastDispatcher requires ctxSupplier + policy + auditSink together (wired mode), got $wiredParts/3"
        }
    }

    /**
     * 建構期 snapshot（`toMap()` defensive copy）：呼叫端在建構後對傳入 map
     * 的任何 mutation（含移除 executor）都不影響本 dispatcher。
     */
    private val executors: Map<String, FastToolExecutor> = executors.toMap()

    /**
     * WIRED 模式：每次新鮮取樣。CE／VM fatal 重拋（與 SafeToolSlice 語義一致）；
     * 其餘故障回 null ⇒ typed skip，永不回音例外訊息。
     */
    private fun freshCtx(): ProjectionContext? = try {
        ctxSupplier?.invoke()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: ThreadDeath) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** 每次都是 fresh（DataStore/InMemory store 的 TOCTOU guard），ALLOW-only 由呼叫端判斷。 */
    private suspend fun freshVerdict(toolName: String): Verdict? = try {
        policy?.evaluateFresh(SafeToolSlice.POLICY_ACTION, toolName)?.verdict
    } catch (e: CancellationException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: ThreadDeath) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /**
     * 規則集的穩定指紋（FNV-1a 64 over sorted canonical rules）：作為 confirmation
     * snapshot 綁定的 policyRevision。任何規則新增／刪除／verdict／priority 變動
     * 都會推進；用規則內容而非時戳，避免同毫秒 flip 被當成同一版而沿用舊 verdict。
     * 讀取失敗 ⇒ null ⇒ fail-closed skip。
     *
     * FNV-1a **不是安全哈希、也不提供完整性保證**（碰撞不代表授權）：它只用來
     * 偵測「規則集是否變過」；真正的執行依據永遠是每次 [freshVerdict] 的
     * `evaluateFresh` ALLOW-only 重查。即使 revision 碰撞也不會放寬 gate。
     */
    private suspend fun policyRevision(): Long? = try {
        val rules = policy?.listRules() ?: return null
        val canonical = rules.asSequence()
            .map { "${it.pattern}\u0000${it.verdict.name}\u0000${it.priority}" }
            .sorted()
            .joinToString("\u0001")
        var hash = 14695981039346656037UL
        for (byte in canonical.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toULong() and 0xFFUL)) * 1099511628211UL
        }
        hash.toLong()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: ThreadDeath) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /**
     * SHA-256 hex of the canonical operation tuple, used ONLY for snapshot
     * binding (not a secret / not an authenticator). Locale.ROOT is mandatory
     * on `%02x` — see issue #175 — so hex formatting is identical on every
     * device locale (Turkish locale would otherwise break lowercase hex).
     */
    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private fun paramsHash(
        tool: String,
        identity: String,
        arguments: String,
        sessionId: String?,
        turnIndex: Int?,
        revision: Long,
    ): String = sha256Hex(
        listOf(tool, identity, arguments, sessionId ?: "", turnIndex?.toString() ?: "", revision.toString())
            .joinToString("\u0000"),
    )

    private fun validIdentity(identity: String): Boolean =
        identity.isNotBlank() && identity.length <= 256 && identity.none { it.isISOControl() }

    /** confirm 的內部結果：拒絕時帶 audit decision，供 one-call 入口留痕。 */
    private sealed interface ConfirmResult {
        data class Ok(val snapshot: FastConfirmationSnapshot) : ConfirmResult
        data class Denied(val auditDecision: String) : ConfirmResult
    }

    /**
     * 建立不可變確認快照（型別本身即 immutable data class；caller 無法 mutation）。
     * ASK／DENY／故障一律拒 —— ASK 永不默認為 ALLOW；本階段沒有 UI 確認
     * 管線可鑄造「已明確同意」代幣，故 ASK 直接不放行。[identity] 由呼叫端提供
     * 並綁入 snapshot／hash，**不是**身份認證機制；正式接線必須傳入已驗證的
     * session／user identity，本 PR 不新增任何 caller。
     */
    suspend fun confirm(decision: RouteDecision, identity: String): FastConfirmationSnapshot? =
        (confirmWithReason(decision, identity) as? ConfirmResult.Ok)?.snapshot

    private suspend fun confirmWithReason(
        decision: RouteDecision,
        identity: String,
    ): ConfirmResult {
        if (decision.route != RouteKind.FAST) return ConfirmResult.Denied("DENY:CONFIRMATION")
        val call = decision.toolCall ?: return ConfirmResult.Denied("DENY:CONFIRMATION")
        if (!validIdentity(identity)) return ConfirmResult.Denied("DENY:CONFIRMATION")
        val revision = policyRevision() ?: return ConfirmResult.Denied("DENY:CONFIRMATION")
        if (freshVerdict(call.toolName) != Verdict.ALLOW) return ConfirmResult.Denied("DENY:POLICY")
        return ConfirmResult.Ok(
            FastConfirmationSnapshot(
                tool = call.toolName,
                identity = identity,
                arguments = call.argumentsJson,
                paramsHash = paramsHash(
                    call.toolName, identity, call.argumentsJson,
                    decision.header.sessionId, decision.header.turnIndex, revision,
                ),
                policyRevision = revision,
                sessionId = decision.header.sessionId,
                turnIndex = decision.header.turnIndex,
            ),
        )
    }

    /**
     * one-call confirm 拒絕的 best-effort 留痕：sink 故障不改變已 fail-closed 的
     * skip；CE／fatal 由 [writeAudit] 原樣重拋。malformed／lookup 失敗仍不寫。
     */
    private suspend fun auditConfirmDenial(decision: RouteDecision, auditDecision: String) {
        val call = decision.toolCall ?: return
        val tool = try {
            lookup(call.toolName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        } ?: return
        if (tool.name != call.toolName) return
        writeAudit(decision, tool, call, auditDecision, -1L, "")
    }

    private fun verifySnapshot(
        snapshot: FastConfirmationSnapshot,
        identity: String,
        call: ToolCall,
        header: TranscriptHeader,
        revision: Long,
    ): Boolean {
        if (snapshot.tool != call.toolName || snapshot.arguments != call.argumentsJson) return false
        if (snapshot.identity != identity || !validIdentity(snapshot.identity)) return false
        if (snapshot.sessionId != header.sessionId || snapshot.turnIndex != header.turnIndex) return false
        if (snapshot.policyRevision != revision) return false
        return snapshot.paramsHash == paramsHash(
            snapshot.tool, snapshot.identity, snapshot.arguments,
            snapshot.sessionId, snapshot.turnIndex, snapshot.policyRevision,
        )
    }

    /** 每筆 wired dispatch 恰寫一筆（含 skip 決策）；失敗回 false，CE／fatal 重拋。 */
    private suspend fun writeAudit(
        decision: RouteDecision,
        tool: ToolDef,
        call: ToolCall,
        auditDecision: String,
        revision: Long,
        hash: String,
    ): Boolean {
        val sink = auditSink ?: return false
        val redacted = try {
            SafeToolSlice.cappedEcho(dev.librepocket.redact.Redactor.redact(call.argumentsJson).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: ThreadDeath) {
            throw e
        } catch (_: Throwable) {
            "[audit redaction failed]"
        }
        val record = FastToolAuditRecord(
            header = decision.header,
            tool = tool.name,
            decision = auditDecision,
            modelVisibleTools = decision.header.visibleTools,
            projectedTools = decision.header.projectedTools,
            redactedArguments = redacted,
            paramsHash = hash,
            policyRevision = revision,
            sessionId = decision.header.sessionId,
            turnIndex = decision.header.turnIndex,
        )
        return try {
            sink.record(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: ThreadDeath) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

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
     * LEGACY TEST-ONLY compatibility entry (existing `FastDispatchTest`).
     * Runs the original gates against the caller's [ctx] snapshot; it does
     * NOT consult fresh policy / confirmation / audit. Production paths must
     * use [dispatchFresh] on a WIRED-constructed dispatcher.
     *
     * Never throws except [CancellationException] (a cancelled dispatch
     * aborts before the executor runs; a timeout is NOT a cancel — it maps
     * to a typed `TIMEOUT` skip), and fatal [Error]s from misbehaving
     * executors also propagate; executor failures (non-cancel) become
     * [Skipped] with a fixed message — backend text never rides the outcome.
     */
    suspend fun dispatch(
        decision: RouteDecision,
        ctx: ProjectionContext,
    ): FastDispatchOutcome = dispatchCore(decision, ctx, null, wired = false, identity = null)

    /**
     * WIRED dispatch: fresh projection + fresh `tool.exec` ALLOW-only policy
     * + immutable [confirmation] + post-confirm re-verification + a single
     * audit write BEFORE the executor on the ALLOW path (and on policy /
     * confirmation / reverify skips). Every failure is a typed skip with
     * zero backend side effects. There is deliberately no caller-supplied
     * [ProjectionContext]: freshness comes from the constructor's `ctxSupplier`.
     */
    suspend fun dispatchFresh(
        decision: RouteDecision,
        confirmation: FastConfirmationSnapshot,
        identity: String,
    ): FastDispatchOutcome {
        val ctx = freshCtx()
            ?: return FastDispatchOutcome.Skipped(
                null,
                fixedFailureMessage(null),
                denyCode = "PROJECTION_ERROR",
            )
        return dispatchCore(decision, ctx, confirmation, wired = true, identity = identity)
    }

    private suspend fun dispatchCore(
        decision: RouteDecision,
        ctx: ProjectionContext,
        confirmation: FastConfirmationSnapshot?,
        wired: Boolean,
        identity: String?,
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
        if (wired) {
            // Stage 1: fresh policy（永不沿用 decision 期或上一 turn 的 verdict）。
            // DENY / ASK / 故障 / unknown 一律 typed skip、零 backend 副作用。
            val revision = policyRevision()
            if (revision == null || freshVerdict(tool.name) != Verdict.ALLOW) {
                val skip = FastDispatchOutcome.Skipped(
                    null, fixedFailureMessage(tool.name), denyCode = "POLICY_DENIED",
                )
                // best-effort audit on a skip: 寫入失敗不改變已 fail-closed 的結果。
                val hash = confirmation?.paramsHash ?: paramsHash(
                    tool.name, identity ?: "", call.argumentsJson,
                    decision.header.sessionId, decision.header.turnIndex, revision ?: -1L,
                )
                writeAudit(decision, tool, call, "DENY:POLICY", revision ?: -1L, hash)
                return skip
            }
            val snap = confirmation
            if (
                snap == null || identity == null ||
                !verifySnapshot(snap, identity, call, decision.header, revision)
            ) {
                val skip = FastDispatchOutcome.Skipped(
                    null, fixedFailureMessage(tool.name), denyCode = "CONFIRMATION_INVALID",
                )
                writeAudit(decision, tool, call, "DENY:CONFIRMATION", revision, snap?.paramsHash ?: "")
                return skip
            }
            // Stage 2 post-confirm reverify（Ref: ElevatedWiring）：在 confirm 之後、
            // 任何副作用之前重新取樣 projection + policy + revision。switch／permission／
            // policy 在兩 turn 之間翻轉，或 cancel／revoke 與 dispatch 競態時，舊
            // snapshot 一律作廢（typed skip、零 backend）。
            val ctx2 = freshCtx()
            val projection2 = if (ctx2 != null) try {
                tool.project(ctx2)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } else null
            val verdict2 = freshVerdict(tool.name)
            val revision2 = policyRevision()
            if (
                ctx2 == null || projection2?.level != CapabilityLevel.NATIVE ||
                verdict2 != Verdict.ALLOW || revision2 != revision ||
                !verifySnapshot(snap, identity, call, decision.header, revision2)
            ) {
                val skip = FastDispatchOutcome.Skipped(
                    null, fixedFailureMessage(tool.name), denyCode = "CONFIRMATION_STALE",
                )
                writeAudit(decision, tool, call, "DENY:REVERIFY", revision, snap.paramsHash)
                return skip
            }
            coroutineContext.ensureActive()
        }
        // 非正的 per-call budget（0/負值）直接 fail-closed 型別化拒絕：零 backend
        // 呼叫，與切片「非正 budget＝即刻型別化逾時」語義一致（絕不視為無上限）。
        val timeoutMs = tool.annotations.timeoutMs
        if (timeoutMs <= 0) {
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "TIMEOUT")
        }
        if (wired) {
            // Room audit 是執行的前置條件：寫入失敗 ⇒ typed skip、零 executor entry。
            // 每筆 wired dispatch 只寫一筆（skip 分支已各自寫過）。
            val snap = confirmation!!
            val recorded = writeAudit(
                decision, tool, call, "ALLOW", snap.policyRevision, snap.paramsHash,
            )
            if (!recorded) {
                return FastDispatchOutcome.Skipped(
                    null, fixedFailureMessage(tool.name), denyCode = "AUDIT_ERROR",
                )
            }
            coroutineContext.ensureActive()
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
            SafeToolSlice.boundedRedactedId(result.callId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return FastDispatchOutcome.Skipped(null, fixedFailureMessage(tool.name), denyCode = "EXEC_ERROR", attempted = true)
        }
        return FastDispatchOutcome.Executed(result.copy(callId = boundedId, denyCode = null))
    }

    /**
     * LEGACY TEST-ONLY one-call fast lane: route then [dispatch] with the
     * SAME [ctx] snapshot. The re-check inside [dispatch] is a same-snapshot
     * recheck, NOT a freshness signal. Production paths must use
     * [decideAndDispatchFresh] on a WIRED-constructed dispatcher.
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
     * WIRED one-call convenience: fresh context for BOTH routing and dispatch,
     * immutable confirmation, then [dispatchFresh]. Still INTERNAL / UNWIRED —
     * no production caller is added by issue #178.
     */
    suspend fun decideAndDispatchFresh(
        intentText: String,
        identity: String,
        sessionId: String? = null,
        turnIndex: Int? = null,
    ): FastLaneResult {
        check(ctxSupplier != null && policy != null && auditSink != null) {
            "decideAndDispatchFresh requires a WIRED-constructed FastDispatcher"
        }
        val ctx = freshCtx()
        val decision = FastRouter().decide(
            intentText, ctx ?: ProjectionContext(), sessionId, turnIndex,
        )
        if (ctx == null) {
            return FastLaneResult(
                decision,
                FastDispatchOutcome.Skipped(null, fixedFailureMessage(null), denyCode = "PROJECTION_ERROR"),
            )
        }
        // confirm 拒絕（ASK／DENY／evaluate 故障／revision 讀取失敗）也必須留痕：
        // 拒絕不是「沒發生」，audit 是 best-effort 且不改變 fail-closed skip。
        return when (val confirmed = confirmWithReason(decision, identity)) {
            is ConfirmResult.Ok ->
                FastLaneResult(decision, dispatchFresh(decision, confirmed.snapshot, identity))
            is ConfirmResult.Denied -> {
                auditConfirmDenial(decision, confirmed.auditDecision)
                FastLaneResult(
                    decision,
                    FastDispatchOutcome.Skipped(
                        null, fixedFailureMessage(null), denyCode = "CONFIRMATION_INVALID",
                    ),
                )
            }
        }
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
internal data class FastLaneResult(
    val decision: RouteDecision,
    val outcome: FastDispatchOutcome,
)

/**
 * Immutable confirmation snapshot (issue #178 Stage 2). All fields are
 * immutable `val`s; [paramsHash] is SHA-256 over
 * `(tool, identity, arguments, sessionId, turnIndex, policyRevision)`, so any
 * mutation of the operation — or a policy/identity/turn flip — invalidates it.
 * Produced only by [FastDispatcher.confirm] with a fresh ALLOW verdict; ASK
 * never yields a snapshot (no implicit promotion to ALLOW).
 *
 * NOT single-use: the same immutable snapshot may be rechecked on a later
 * dispatch while the bound operation + policy revision still hold; reverify
 * on EVERY dispatch is what enforces freshness, not consumption of this value.
 * It is NOT a WRITE/PRIVILEGED confirmation token — the fast lane rejects
 * non-READ tools before this point; WRITE confirmation belongs to the separate
 * high-risk pipeline. [identity] is caller-supplied and is NOT authentication:
 * production wiring must supply an already-verified session/user identity.
 */
internal data class FastConfirmationSnapshot(
    val tool: String,
    val identity: String,
    val arguments: String,
    val paramsHash: String,
    val policyRevision: Long,
    val sessionId: String?,
    val turnIndex: Int?,
)

/**
 * One redacted `tool.exec` audit row (not an authority: the transcript DB
 * remains the persistence path). [header] is the decision's canonical
 * [TranscriptHeader] projection snapshot; [modelVisibleTools] /
 * [projectedTools] are audit-only and never proof of what was on the wire
 * (see [TranscriptHeader] KDoc). [decision] is `ALLOW` or a typed deny code;
 * [redactedArguments] is redacted + capped before it can reach storage.
 */
internal data class FastToolAuditRecord(
    val header: TranscriptHeader,
    val tool: String,
    val decision: String,
    val modelVisibleTools: List<String>,
    val projectedTools: List<String>,
    val redactedArguments: String,
    val paramsHash: String,
    val policyRevision: Long,
    val sessionId: String?,
    val turnIndex: Int?,
) {
    /** Deterministic, bounded, single-line JSON for the transcript `text` column. */
    internal fun toAuditJson(): String = buildJsonObject {
        put("marker", "tool.exec")
        put("tool", tool)
        put("decision", decision)
        put("sessionId", sessionId ?: "")
        put("turnIndex", turnIndex ?: -1)
        put("route", header.route.name)
        put("reasonCode", header.reasonCode ?: "")
        put("visibleTools", buildJsonArray { modelVisibleTools.forEach { add(JsonPrimitive(it)) } })
        put("projectedTools", buildJsonArray { projectedTools.forEach { add(JsonPrimitive(it)) } })
        put("redactedArguments", redactedArguments)
        put("paramsHash", paramsHash)
        put("policyRevision", policyRevision)
    }.toString()
}

/** Audit sink contract: `true` ⇔ durable write succeeded; any failure = false. */
internal fun interface FastToolAuditSink {
    suspend fun record(record: FastToolAuditRecord): Boolean
}

/**
 * Room-backed sink over the EXISTING [SessionStore] transcript path — no new
 * DAO, entity, table or migration, and never a new authority. Rows use the
 * existing `tool` kind and are redacted again by the store's write path.
 * A session-less record cannot be persisted ⇒ false ⇒ fail-closed skip.
 * Cancellation is never swallowed.
 *
 * Wiring TODO (not done in #178): the `runId` is SYNTHETIC
 * (`fast-audit-<turnIndex>`), not the real turn/run id. Production wiring must
 * let the record carry the authoritative runId before activation; this sink
 * is not installed by any production caller in this PR.
 */
internal class FastToolRoomAuditSink(
    private val store: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : FastToolAuditSink {
    override suspend fun record(record: FastToolAuditRecord): Boolean {
        val sessionId = record.sessionId
        if (sessionId.isNullOrBlank()) return false
        return try {
            store.appendEvent(
                TranscriptEvent(
                    sessionId = sessionId,
                    runId = "fast-audit-${record.turnIndex ?: -1}",
                    kind = "tool",
                    text = record.toAuditJson(),
                    createdAt = clock(),
                ),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: ThreadDeath) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }
}

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
internal data class FastExecResult(
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
internal sealed interface FastDispatchOutcome {
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
internal interface FastToolExecutor {
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
 *   parsing via [SafeToolSlice.jsonNestingDepth] (the same shared pre-parse
 *   fan-close the slice applies);
 * - `query` ≤ [SafeToolSlice.MAX_INPUT_CHARS], no ISO control chars / NUL /
 *   Unicode FORMAT code points ([SafeToolSlice.containsFormatCodePoint]);
 * - `pathPrefix` ≤ [SafeToolSlice.MAX_INPUT_CHARS], must be NFKC-stable, and
 *   must pass the shared [SafeToolSlice.isSafeInput] predicate (no `..`, no leading `/`
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
 * [SafeToolSlice.MAX_STRING_CHARS] (surrogate-safe [SafeToolSlice.cappedTake]) while
 * `callId` uses [SafeToolSlice.boundedRedactedId] (redact → ≤cap pass through
 * → surrogate-safe cap + `#` + 32-hex SHA-256 of the full redacted string);
 * raw ids never ride the receipt. The
 * serialized result JSON is shrunk item-wise
 * to [SafeToolSlice.MAX_RESULT_CHARS] minus the shared metadata reserve
 * ([SafeToolSlice.RESULT_METADATA_RESERVE]) — the same effective budget the
 * slice shrinks against, so both pipelines cut identically regardless of
 * clock value (the slice's `clockMillis` is decided by the envelope after
 * the loop and absorbed by the reservation, not measured here) — dropping
 * the tail and bumping `omittedCount`; a single-symbol remainder still over
 * budget is a typed `EXEC_ERROR` denial — never over-budget output.
 * `omittedCount` counts both
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
 * Predicate/cap/id-bounding helpers are single-sourced in [SafeToolSlice]'s
 * companion object and CALLED here ([SafeToolSlice.jsonNestingDepth],
 * [SafeToolSlice.containsFormatCodePoint], [SafeToolSlice.isSafeInput],
 * [SafeToolSlice.boundedRawBackendField], [SafeToolSlice.cappedTake],
 * [SafeToolSlice.cappedEcho], [SafeToolSlice.boundedRedactedId],
 * [SafeToolSlice.effectiveLimitOf]) — there is exactly ONE implementation,
 * shared with the slice. Only the pipeline shapes themselves remain
 * hand-mirrored with the slice: [parseArgs] ↔ the slice's `parseArgs()` and
 * [okResult] ↔ the slice's `ok()` (same module, same constants). The Ok
 * receipts those two pipelines produce are pinned byte-/semantic-identical
 * (modulo the slice's extra `clockMillis`, the tool echo rule, and key
 * order) by `SymbolReceiptParityTest` — do not fork the shared helpers.
 */
internal class LspSymbolsExecutor(
    private val backend: LspTools.Backend = LspTools.StubBackend,
) : FastToolExecutor {
    override val toolName: String = LspTools.SYMBOLS_NAME

    private data class LspArgs(val query: String, val pathPrefix: String?, val limit: Int)

    private fun parseArgs(argumentsJson: String): LspArgs? {
        // 粗防線：與切片相同的 gross 上限與巢狀深度 pre-scan（在任何 parse 之前）。
        if (argumentsJson.length > SafeToolSlice.MAX_GROSS_ARGS_CHARS) return null
        if (SafeToolSlice.jsonNestingDepth(argumentsJson) > SafeToolSlice.MAX_NESTING_DEPTH) return null
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
            ?.takeIf { !SafeToolSlice.containsFormatCodePoint(it) } ?: return null
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
                if (!SafeToolSlice.isSafeInput(v)) return null
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
        val effectiveLimit = SafeToolSlice.effectiveLimitOf(requestedLimit)
        val available = outcome.symbols.take(effectiveLimit)
        val limitOmitted = (outcome.symbols.size - available.size).coerceAtLeast(0)
        val redactedQuery = dev.librepocket.redact.Redactor.redact(query).text
        // 截斷判定一律以 POST-redaction 長度為準（redaction 可能膨風）。
        var anyFieldTruncated = redactedQuery.length > SafeToolSlice.MAX_STRING_CHARS
        val redactedSymbols = available.map { s ->
            val rawOmitted = s.name.length > SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS ||
                s.kind.length > SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS ||
                s.path.length > SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS
            val rn = dev.librepocket.redact.Redactor.redact(SafeToolSlice.boundedRawBackendField(s.name)).text
            val rk = dev.librepocket.redact.Redactor.redact(SafeToolSlice.boundedRawBackendField(s.kind)).text
            val rp = dev.librepocket.redact.Redactor.redact(SafeToolSlice.boundedRawBackendField(s.path)).text
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
                put("name", SafeToolSlice.cappedEcho(rn))
                put("kind", SafeToolSlice.cappedEcho(rk))
                put("path", SafeToolSlice.cappedEcho(rp))
                put("line", s.line)
            }
        }
        val boundedId = SafeToolSlice.boundedRedactedId(call.callId)
        fun build(chosen: List<JsonObject>, omittedCount: Int, truncated: Boolean): JsonObject =
            buildJsonObject {
                put("tool", toolName)
                put("callId", boundedId)
                put("denied", false)
                put("query", SafeToolSlice.cappedEcho(redactedQuery))
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
            // 與切片相同的共用 envelope 預算判定：候選本就不含 clockMillis
            // （fast lane 無時鐘；切片亦改為 envelope 事後附加），
            // 兩邊皆以 MAX_RESULT_CHARS - RESULT_METADATA_RESERVE 判定切點。
            val candidate = build(chosen, omittedCount, truncated).toString()
            if (candidate.length <= SafeToolSlice.MAX_RESULT_CHARS - SafeToolSlice.RESULT_METADATA_RESERVE) break
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
                put("callId", SafeToolSlice.boundedRedactedId(call.callId))
            }.toString(),
            denied = true,
            denyCode = code,
        )
}
