package dev.librepocket.shell

import dev.librepocket.redact.Redactor

/**
 * S1-C 受限版 `shell.exec` 執行面（BACKLOG D05 受限部分，矩陣「終端命令」行）。
 *
 * - argv 直達 [RestrictedShell]（經 `ProcessBuilder(argv)`，不經 `sh -c`，
 *   不拼字串）：白名單 / 黑名單 / 參數衛生 / find 高危謂詞 / 檔案域
 *   （[ShellPolicy.validate]）→ 配額（[ShellQuota]）→ 超時殺 → 輸出截斷，
 *   全由 [RestrictedShell] 執行，本對象只做轉交與命名約束。
 * - 開關 `shell`（見 [SWITCH]）預設關：投影層（ToolRegistry
 *   `requiresSwitch = "shell", switchDefault = false`）未開即 UNAVAILABLE；
 *   執行層 [checkSwitch] 再擋一次（fail-closed）。
 * - 提權版 `shell.elevated` 走 S3 D09 橋（見 [ELEVATED_NAME]）：
 *   直接 exec 遇跨域一律 [ShellResult.Denied]（[RestrictedShell] 經
 *   `FileScope.decide` 拒絕，foss/github 即使橋接已授權亦然，需改走 D09 橋）；
 *   提權執行一律經 [executeElevated]（審計版，必須傳 [PrivilegeAuditLog]）
 *   留痕，無審計直行路徑不存在。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言。
 */
object ShellExecTool {
    /** ToolRegistry 側的工具名（PRIVILEGED，開關見 [SWITCH]）。 */
    const val NAME = "shell.exec"

    /** 使用者開關 key；預設 false（見 ToolRegistry `switchDefault = false`）。 */
    const val SWITCH = "shell"

    /**
     * 提權版工具名（S3 D09，已註冊於 ToolRegistry，自裝風味 + 預設關）。
     * 任何以此名義的執行必須走審計版 [executeElevated]
     *（經 [dev.librepocket.router.ElevatedDispatch] 先門禁再執行）。
     */
    const val ELEVATED_NAME = "shell.elevated"

    /** 開關檢查：關閉即不可執行（投影 USER_DISABLED 的執行側對應）。 */
    fun checkSwitch(switchOn: Boolean): Boolean = switchOn

    /**
     * 受限執行：argv 直達 [RestrictedShell]，不經 shell 解析。
     * 開關關閉時直接回 [ShellResult.Denied] 且不建子進程。
     * `timeoutMs` 語義沿 [RestrictedShell.execute]（整體 request budget，直傳）。
     */
    fun execute(
        argv: List<String>,
        shell: RestrictedShell,
        switchOn: Boolean = true,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        if (!switchOn) {
            return ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "shell switch off ($SWITCH=false)")
        }
        return shell.execute(argv, timeoutMs)
    }

    /**
     * 提權執行（S3 D09，唯一產品路徑）：執行後把「種類名 + 雜湊」寫入 [audit]，
     * 輸出明文不進表。真實現（Shizuku/Root）由風味源集注入 [runner]。
     *
     * 注意：無 audit 的過載已刪除 —— 任何提權執行都必須留痕，
     * 呼叫方一律傳 [PrivilegeAuditLog]（見 [dev.librepocket.router.ElevatedDispatch]，
     * 先四參門禁再執行，全路徑審計）。
     *
     * Phase 4（P2-1，PR #140）：入口先 [frozenOrNull] 凍結 argv＋reason，
     * 同一凍結物件用於 gate（上游 [dev.librepocket.router.ElevatedDispatch]
     * 已凍結，此處再凍一次冪等）、[runner.run][ElevatedShellRunner.run]、
     * 審計 [PrivilegeAuditLog.recordSnapshot]；凍結失敗（併發竄改致拷貝抛，
     * 見 F9）即 `Denied(BLACKLISTED)` 且零 spawn，審計以空 argv＋原始
     * reasonCode 記一筆 DENY（argv 讀取本身不可信，故不雜湊原文）。
     *
     * Round-3 Phase H（P2-5）：審計契約升級為「所有執行必有一筆審計」——
     * [runner.run][ElevatedShellRunner.run] 非致命 throw（注入 fake 抛
     * `IllegalStateException`、真 runner 在 thread 建立／IO 邊界抛未預期例外）
     * 不再外抛：映 `Failed`（訊息經 [Redactor.redactError] 收斂，攜例外類名，
     * 不洩明文）＋以凍結快照記一筆 FAILED 審計後返回（已 spawn 後才抛的情境，
     * runner 側補償清理沿既有 drain／killer 路徑，本層只保證審計不漏）。
     * 審計自身失敗不掩蓋原始異常（原始 throw 優先：審計抛即 `addSuppressed`
     * 後仍按原路徑返回／重抛，不另記雙筆——欄位不變，見 Phase D 約束）。
     * 致命（[VirtualMachineError]／`ThreadDeath`）仍重抛（審計已盡力記一筆），
     * 不吞 VM 失敗；[InterruptedException] 恢復中斷位後同樣映 `Failed`
     * （runner 契約以 [ShellResult] 返值，不抛中斷；coroutine 取消請用
     * `isCancelled` 鉤，見 runner R3-A 段）。
     *
     * Round-4 Phase N（P2-1 SLA 統一＋P2-2 reason 上限）：
     * - reasonCode 先行（P2-2）：超限（[PrivilegeAuditLog.MAX_REASON_CODE_BYTES]
     *   UTF-8 位元組）即 `Denied(BLACKLISTED)` 且零 spawn、零 snapshot worker；
     *   審計以空 argv＋空 reason 記一筆 DENY（不哈希無界輸入，見
     *   [PrivilegeAuditLog.record] fail-closed 門）。
     * - 單次凍結（P2-1）：本函數快照一次，得 frozen 即委派
     *   [executeElevatedFrozen]；上游 [dev.librepocket.router.ElevatedDispatch]
     *   已凍結時直調 frozen 版，不再二次凍結（舊雙重凍結最坏 2×(1s+grace)，
     *   現每 caller 至多一 snapshot worker，且池化有界見 [frozenOrNull]）。
     */
    fun executeElevated(
        request: ElevatedRequest,
        runner: ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        if (PrivilegeAuditLog.isReasonCodeTooLong(request.reasonCode)) {
            val denied = ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "reasonCode too long (fail-closed, no spawn)",
            )
            audit.record(emptyList(), "", PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val frozen = request.frozenOrNull() ?: run {
            val denied = ShellResult.Denied(ShellDeny.BLACKLISTED, "elevated snapshot failed (fail-closed, no spawn)")
            // reason 已先行验长度（见上），此处哈希有界；argv 读取本身不可信，
            // 故以空 argv 记 DENY（不哈希原文）。
            audit.record(emptyList(), request.reasonCode, PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        return executeElevatedFrozen(frozen, runner, audit, timeoutMs)
    }

    /**
     * 已凍結版提權執行（Round-4 Phase N，P2-1 SLA 統一唯一入口）：
     * [frozen] 須為入口快照（[frozenOrNull] 產物：有界拷貝＋不可變包裝），
     * 本函數不再觸碰原始 `request`、不再二次凍結；門禁／執行／審計一律用同一份。
     *
     * SLA：`timeoutMs` 原值透傳 [runner]（准入後執行預算，不扣快照耗時；
     * 快照為准入成本，有界 1s＋grace，見 [frozenOrNull]；呼叫方總耗時上界 ≈
     * 快照門＋`timeoutMs`）。直调本函数传超限 frozen 同样防御性复检
     * （String 只读，零 worker）fail-closed。
     *
     * Round-5 Phase Q（P2-5 frozen API 契約）：本函數收斂為 internal——
     * [frozen] 只接受 gate 已產生的凍結物件（[frozenOrNull] 產物，執行期不可變，
     * 見 Phase Q 迴歸）；同模組新 caller 禁止直傳未凍結 `ElevatedRequest`
     * （尤其 `MutableList` 背板：runner／審計讀到活引用即舊 snapshot TOCTOU
     * 重現）。公開入口一律走 [executeElevated]（入口凍結＋單次凍結 SLA）；
     * 同模組唯一產品調用為 [dev.librepocket.router.ElevatedDispatch]
     * （傳 gate 後同一 frozen），跨模組產品調用不存在。
     * 本函數刻意不做二次凍結：傳未凍結物件的行為未定義（TOCTOU），由可見性
     * （internal）＋本契約防範，不另加執行期重驗。
     */
    internal fun executeElevatedFrozen(
        frozen: ElevatedRequest,
        runner: ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        if (PrivilegeAuditLog.isReasonCodeTooLong(frozen.reasonCode)) {
            val denied = ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "reasonCode too long (fail-closed, no spawn)",
            )
            audit.record(emptyList(), "", PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val result = try {
            runner.run(frozen, timeoutMs)
        } catch (t: Throwable) {
            val failed = runCatching {
                ShellResult.Failed(
                    "elevated runner failed (${t.javaClass.simpleName}): " +
                        Redactor.redactError(t.message ?: t.javaClass.simpleName),
                )
            }.getOrDefault(ShellResult.Failed("elevated runner failed"))
            try {
                audit.recordSnapshot(frozen.argv, frozen.reasonCode, failed)
            } catch (auditErr: Throwable) {
                // 審計失敗不掩蓋原始異常：原始 throw 優先，審計異常只作 suppressed。
                runCatching { t.addSuppressed(auditErr) }
            }
            if (t is InterruptedException) Thread.currentThread().interrupt()
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            return failed
        }
        audit.recordSnapshot(frozen.argv, frozen.reasonCode, result)
        return result
    }
}
