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
     */
    fun executeElevated(
        request: ElevatedRequest,
        runner: ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        val frozen = request.frozenOrNull() ?: run {
            val denied = ShellResult.Denied(ShellDeny.BLACKLISTED, "elevated snapshot failed (fail-closed, no spawn)")
            audit.record(emptyList(), request.reasonCode, PrivilegeAuditLog.verdictName(denied))
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
