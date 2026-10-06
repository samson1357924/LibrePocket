package dev.librepocket.shell

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
     */
    fun executeElevated(
        request: ElevatedRequest,
        runner: ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        val result = runner.run(request, timeoutMs)
        audit.recordResult(request, result)
        return result
    }
}
