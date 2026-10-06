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
 * - 提權版 `shell.elevated` 留 S3（見 [ELEVATED_NAME]，本階段不註冊）：
 *   直接 exec 遇跨域一律 [ShellResult.Denied]（[RestrictedShell] 經
 *   `FileScope.decide` 拒絕，foss/github 即使橋接已授權亦然，需改走 D09 橋）；
 *   任何提權執行一律經 [DenyingElevatedRunner] 拒絕且不建子進程。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言。
 */
object ShellExecTool {
    /** ToolRegistry 側的工具名（PRIVILEGED，開關見 [SWITCH]）。 */
    const val NAME = "shell.exec"

    /** 使用者開關 key；預設 false（見 ToolRegistry `switchDefault = false`）。 */
    const val SWITCH = "shell"

    /**
     * 提權版工具名（S3 預留，本階段不註冊、不實作）。
     * 任何以此名義的執行必須走 [executeElevated] 並被拒絕。
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
     * 提權執行佔位：本階段一律拒絕（沿 [DenyingElevatedRunner]），不建子進程。
     * S3 在 foss/github 風味源集提供真實現前，呼叫方必須走此路徑。
     */
    fun executeElevated(
        request: ElevatedRequest,
        runner: ElevatedShellRunner = DenyingElevatedRunner(),
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult = runner.run(request, timeoutMs)
}
