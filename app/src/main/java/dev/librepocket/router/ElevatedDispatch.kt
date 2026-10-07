package dev.librepocket.router

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellExecTool
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ToolDef

/**
 * S3 提權執行分派（BACKLOG D09，矩陣 §3/§4）：產品路徑調用 [PrivilegeGate]
 * 的唯一入口。
 *
 * 順序：四參 args-aware [PrivilegeGate.check]（攜 reasonCode）
 * → 僅 [GateResult.Allowed] 才走審計版
 * [ShellExecTool.executeElevated]；門禁未過一律不建子進程，且同樣寫一筆
 * DENY 審計（全路徑留痕，無審計直行路徑不存在）。
 */
object ElevatedDispatch {

    /**
     * 門禁先行、審計必行的提權執行。
     *
     * @param tool 必須是 `shell.elevated` 的 [ToolDef]（斷言防誤用）。
     * @param confirmed 當輪二次確認。
     * @param projection 投影（`privilege_bridge` 未開即 UNAVAILABLE，先擋）。
     * @param request 提權請求（含 reasonCode，門禁與審計共用同一份）。
     */
    fun execute(
        tool: ToolDef,
        confirmed: Boolean,
        projection: Projection,
        request: ElevatedRequest,
        runner: ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        require(tool.name == PrivilegeGate.ELEVATED_TOOL) {
            "ElevatedDispatch only serves ${PrivilegeGate.ELEVATED_TOOL}, not ${tool.name}"
        }
        val gate = PrivilegeGate.check(
            tool,
            confirmed,
            projection,
            mapOf("reasonCode" to request.reasonCode),
        )
        if (gate !is GateResult.Allowed) {
            val denied = ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "privilege gate blocked: $gate",
            )
            audit.recordResult(request, denied)
            return denied
        }
        return ShellExecTool.executeElevated(request, runner, audit, timeoutMs)
    }
}
