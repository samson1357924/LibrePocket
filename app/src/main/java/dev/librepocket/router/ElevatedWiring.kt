package dev.librepocket.router

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.frozenOrNull
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * `shell.elevated` 接線形狀（#136-B3，JVM 可驗收部分；裝置 E2E 明確不做）。
 *
 * - executor 綁定：唯一產品入口經 [ElevatedDispatch]（門禁先行＋凍結＋審計必行）；
 *   本對象只做接線形狀（每次 call 重新投影＋fresh policy＋ASK 確認＋不可變快照＋
 *   確認後 spawn 前重驗），不自建子進程、不碰真 Binder／su；
 * - 每次 call 重新投影：[ctxSupplier] 每次新鮮求值（flavor＋開關＋授權），
 *   確認前後各投影一次（spawn 前重驗）；
 * - fresh policy：[policyStore] 每次 [PolicyStore.evaluateFresh]（`tool.exec`），
 *   僅 ALLOW 放行——ASK／DENY／異常一律拒（ASK 不默認為 ALLOW）；
 * - ASK 確認：[PrivilegeGate] 四參 args-aware（`reasonCode` 非空＋`confirmed`），
 *   `confirmed=false`／空白 `reasonCode` 一律 `NeedConfirm`→拒，絕不靜默放行；
 * - 不可變快照 [ElevatedSnapshot]：tool／argv（防禦拷貝＋不可變包裝）／
 *   reasonCode／flavor／投影開關（userSwitches 拷貝）／bridge＋scope grant；
 *   確認後 spawn 前重驗快照一致性（開關／flavor／grant／policy 任一翻轉、
 *   argv／reason 被竄改即作廢，零 spawn）；
 * - `ToolRegistry` 的 `shell.elevated` 維持 `executionReady=false`（B3 不翻旗——
 *   先接線形狀＋測試，翻旗是生產啟用決策，留待裝置 E2E 後；見接線測試 pin）。
 *
 * 零 Android 依賴，JVM 單測可直接斷言。產品 runner 由 github 源集注入
 * （`RootSuRunner`／`ShizukuShellRunner`，每次新鮮供給 `bridgeGranted`／
 * `grantCheck`，見各 runner KDoc）；本層只消費 supplier，不自建快取。
 *
 * 依賴 #166（B1）先合併：grant 持久真相源（`PrivilegeBridgeStore`＋
 * `ShizukuGrantStore`）由 B1 落定；本層以最小抽象 [ElevatedGrant] 佔位
 * （github 側 `GrantCheck.Valid／Invalid` 對映，rebase 後以 B1 持久版為準）。
 *
 * Rebase 註記（#166，寫進 PR 描述用）：
 * - B1 的 `PrivilegeBridgeStore`（DataStore）實現
 *   [dev.librepocket.shell.PrivilegeRevokeStore]（rebase 時加
 *   `: PrivilegeRevokeStore`），並刪除本分支的重複 overload／佔位
 *   `InMemoryPrivilegeRevokeStore`（改用 B1 持久版；見
 *   [dev.librepocket.shell.PrivilegeBridgeState] KDoc）；
 * - grant 有效性以 B1 `PrivilegeGrantCheck.check()`（現 B1 實作：
 *   `ShizukuGrantPolicy.check`／`ShizukuGrantStore.check`，rebase 時對齊命名）
 *   為準，本層 [ElevatedGrant.Valid] 僅為接線形狀的身份快照佔位。
 */
object ElevatedWiring {

    /** 接線工具名（與 [ToolRegistry] 註冊同名）。 */
    const val TOOL_NAME: String = PrivilegeGate.ELEVATED_TOOL

    /** 執行依據 policy 動作（與 `SafeToolSlice` 同動作；本層要求 ALLOW，ASK 即拒）。 */
    const val POLICY_ACTION: String = "tool.exec"

    /**
     * scope grant 最小抽象（#136-B3 接線形狀，依賴 #166）。
     *
     * - github 側 `GrantCheck.Valid`↔[Valid]、`GrantCheck.Invalid(reason)`↔[Invalid]；
     * - [Valid] 攜帶授權綁定身份（`providerId`＋`configRevision`，見
     *   `ShizukuGrant`）：快照時連同 [ElevatedSnapshot] 一起固定，重驗時比對
     *   一致性——endpoint 切換（providerId 翻轉）或任何設定寫入推進 revision
     *   後，舊授權即作廢（fail-closed，零 spawn），禁止沿用不相符的 credential；
     * - 空白 `providerId` 視為 malformed（接線側 fail-closed 拒，不靜默放行）；
     * - rebase #166 後以 B1 `PrivilegeGrantCheck.check()` 為準（現 B1 實作：
     *   `ShizukuGrantPolicy.check`／`ShizukuGrantStore.check`，rebase 時對齊命名）；
     *   產品 `grantCheck` 供給責任：每次新鮮求值
     *   `{ store.check(freshProviderId, freshRevision) }` 的 blocking 包裝，
     *   `Valid` 時同時嵌入同一 emission 的身份（禁止分兩次讀 provider／revision
     *   的撕裂混代），`Invalid` 透傳原因碼。
     */
    sealed interface ElevatedGrant {
        data class Valid(val providerId: String, val configRevision: Long) : ElevatedGrant
        data class Invalid(val reason: String) : ElevatedGrant
    }

    /**
     * 不可變接線快照（確認後 spawn 前重驗的比對基準）：
     * - [argv] 為入口防禦拷貝＋不可變包裝（呼叫方保留 `MutableList` 並在 grant／
     *   bridge supplier 內竄改不影響本份）；
     * - [userSwitches] 為投影開關拷貝（同 call 內一致性比對用）；
     * - [bridgeGranted]／[grant] 為准入時新鮮求值（spawn 前重求比對，翻轉即作廢）；
     * - [grantProviderId]／[grantConfigRevision] 為准入時 grant 身份快照
     *   （取自 [grant] 的 `Valid` 身份；重驗時與新鮮 `grantCheck()` 的身份比對，
     *   endpoint／revision 輪轉即作廢，零 spawn）。
     */
    data class ElevatedSnapshot(
        val toolName: String,
        val argv: List<String>,
        val reasonCode: String,
        val flavor: Flavor,
        val userSwitches: Map<String, Boolean>,
        val bridgeGranted: Boolean,
        val grant: ElevatedGrant,
        val grantProviderId: String,
        val grantConfigRevision: Long,
    )

    /**
     * 接線執行（suspend 只因 [PolicyStore.evaluateFresh] 為 suspend；其餘皆同步門）。
     *
     * 全路徑留痕：門禁未過亦寫一筆 DENY 審計（經 [ElevatedDispatch] 或本層直記，
     * 與 dispatch 同契約）；任何拒絕一律零 spawn（由呼叫方的 spawn 計數斷言）。
     *
     * 取消語義：[CancellationException] 一律重拋（不轉 `Denied`、不寫 DENY 審計）；
     * 各 supplier／projection／`evaluateFresh` 的 `catch` 皆先重拋 CE，
     * 僅非取消異常才 fail-closed 拒（見取消傳播測試）。
     *
     * @param argv 提權 argv 向量（呼叫方可能傳 `MutableList`；本函數入口即快照，
     * 確認後重驗一致性，竄改即作廢）。
     * @param reasonCode 跨權限邊界原因碼（空白即拒，ASK 不默許）。
     * @param confirmed 當輪二次確認（false 即拒，ASK 不默認為 ALLOW；重驗通過後
     * 原值透傳 [ElevatedDispatch]，不再硬編 `true`）。
     * @param ctxSupplier 每次新鮮供給投影上下文（flavor＋開關；確認前後各求值一次）。
     * @param grantCheck 每次新鮮求值 scope grant（產品傳
     * `{ store.check(freshProviderId, freshRevision) }` 的 blocking 包裝，並嵌入
     * 同一 emission 的身份為 [ElevatedGrant.Valid]；抛異常即 fail-closed 拒，
     * [CancellationException] 重拋）。
     * @param bridgeGranted 每次新鮮求值橋開關（產品傳 `{ state.bridgeGranted }`
     * 經 `refresh` 同步後的值；抛異常即 fail-closed 拒，[CancellationException] 重拋）。
     */
    suspend fun execute(
        argv: List<String>,
        reasonCode: String,
        confirmed: Boolean,
        ctxSupplier: () -> ProjectionContext,
        policyStore: PolicyStore,
        runner: ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        grantCheck: () -> ElevatedGrant,
        bridgeGranted: () -> Boolean,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        coroutineContext.ensureActive()
        val tool = ToolRegistry.find(TOOL_NAME)
            ?: run {
                audit.record(emptyList(), "", PrivilegeAuditLog.verdictName(
                    ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "unknown tool $TOOL_NAME"),
                ))
                return ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "unknown tool $TOOL_NAME")
            }
        // 入口快照（有界凍結：argv 拷貝經快照專用有界門，阻塞／超限／竄改抛
        // 即 fail-closed 拒；與 Dispatch 同門，不另起無界拷貝）。
        val frozenEntry = ElevatedRequest(argv, reasonCode).frozenOrNull()
            ?: run {
                val denied = ShellResult.Denied(ShellDeny.BLACKLISTED, "elevated snapshot failed (fail-closed, no spawn)")
                audit.record(
                    emptyList(),
                    if (PrivilegeAuditLog.isReasonCodeTooLong(reasonCode)) "" else reasonCode,
                    PrivilegeAuditLog.verdictName(denied),
                )
                return denied
            }
        val argvCopy: List<String> = frozenEntry.argv
        val reasonCopy: String = frozenEntry.reasonCode
        // 每次 call 重新投影（前驗）：supplier 抛即 fail-closed 拒（CE 重拋）。
        val ctx1: ProjectionContext = try {
            ctxSupplier()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection unavailable (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val projection1 = try {
            tool.project(ctx1)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection unavailable (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (projection1.level == CapabilityLevel.UNAVAILABLE) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege projection blocked: ${projection1.reason}")
            // 全路徑留痕：以空 argv＋有界 reason 記 DENY（不哈希不可信 argv）。
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // fresh policy（前驗）：僅 ALLOW 放行；ASK／DENY／異常一律拒（ASK 不變 ALLOW；CE 重拋）。
        val verdict1 = try {
            policyStore.evaluateFresh(POLICY_ACTION, tool.name).verdict
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (verdict1 != Verdict.ALLOW) {
            val denied = ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "policy denied (verdict=${verdict1?.name ?: "ERROR"}, fail-closed, no spawn)",
            )
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // ASK 確認（前驗）：四參 gate；NeedConfirm／Denied 一律拒（不默認 ALLOW）。
        // 注意：gate 用凍結後 reason（String 不可變，與入口同值；超限門由 Dispatch 再驗）。
        val gate1 = PrivilegeGate.check(tool, confirmed, projection1, mapOf("reasonCode" to reasonCopy))
        if (gate1 !is GateResult.Allowed) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege gate blocked: $gate1")
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // bridge＋grant 前驗（每次新鮮求值；抛異常即 fail-closed 拒，CE 重拋）。
        val bridge1 = try {
            bridgeGranted()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge check failed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (!bridge1) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge off (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val grant1 = try {
            grantCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant check failed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant1 is ElevatedGrant.Invalid) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, grant1.reason)
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant1 is ElevatedGrant.Valid && grant1.providerId.isBlank()) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant identity malformed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCopy), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val validGrant1 = grant1 as ElevatedGrant.Valid
        // 不可變快照（確認後 spawn 前重驗的基準；argv／reason 皆取凍結份；grant 身份同快）。
        val snapshot = ElevatedSnapshot(
            toolName = tool.name,
            argv = argvCopy,
            reasonCode = reasonCopy,
            flavor = ctx1.flavor,
            userSwitches = ctx1.userSwitches.toMap(),
            bridgeGranted = bridge1,
            grant = grant1,
            grantProviderId = validGrant1.providerId,
            grantConfigRevision = validGrant1.configRevision,
        )
        coroutineContext.ensureActive()
        // 確認後 spawn 前重驗：重新投影＋fresh policy＋重求 bridge／grant＋
        // 快照一致性（argv／reason／flavor／開關任一變更即作廢，零 spawn）。
        val ctx2: ProjectionContext = try {
            ctxSupplier()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "reverify projection unavailable (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // 確認後參數變更作廢：呼叫方在確認後竄改原始 `argv`（同引用）即與快照不等。
        if (argv != snapshot.argv) {
            val denied = ShellResult.Denied(ShellDeny.BLACKLISTED, "elevated args changed after confirm (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (ctx2.flavor != snapshot.flavor || ctx2.userSwitches != snapshot.userSwitches) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "elevated context changed after confirm (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val projection2 = try {
            tool.project(ctx2)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "reverify projection unavailable (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (projection2.level == CapabilityLevel.UNAVAILABLE) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege projection blocked on reverify: ${projection2.reason}")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val verdict2 = try {
            policyStore.evaluateFresh(POLICY_ACTION, tool.name).verdict
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (verdict2 != Verdict.ALLOW) {
            val denied = ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "policy denied on reverify (verdict=${verdict2?.name ?: "ERROR"}, fail-closed, no spawn)",
            )
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val bridge2 = try {
            bridgeGranted()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge recheck failed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (!bridge2) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge off on reverify (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val grant2 = try {
            grantCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant recheck failed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant2 is ElevatedGrant.Invalid) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, grant2.reason)
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant2 is ElevatedGrant.Valid && grant2.providerId.isBlank()) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant identity malformed on reverify (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val validGrant2 = grant2 as ElevatedGrant.Valid
        if (validGrant2.providerId != snapshot.grantProviderId ||
            validGrant2.configRevision != snapshot.grantConfigRevision
        ) {
            val denied = ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "elevated grant identity changed after confirm (fail-closed, no spawn)",
            )
            audit.record(emptyList(), safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // 重驗通過：以快照（不可變）委派唯一產品入口；確認後不再觸碰原始 argv。
        // `confirmed` 原值透傳（前驗 gate 已要求 true 才可達此處；Dispatch 再驗）。
        coroutineContext.ensureActive()
        return ElevatedDispatch.execute(
            tool = tool,
            confirmed = confirmed,
            projection = projection2,
            request = ElevatedRequest(snapshot.argv, snapshot.reasonCode),
            runner = runner,
            audit = audit,
            timeoutMs = timeoutMs,
        )
    }

    /** 有界 reason（超限即空串標記，不哈希無界輸入；與 Dispatch 同門）。 */
    private fun safeReason(reasonCode: String): String =
        if (PrivilegeAuditLog.isReasonCodeTooLong(reasonCode)) "" else reasonCode
}
