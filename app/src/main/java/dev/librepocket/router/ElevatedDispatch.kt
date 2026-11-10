package dev.librepocket.router

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellExecTool
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.frozenOrNull
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
     * Phase 4（P2-1，PR #140）：入口先凍結 [request]（見
     * [dev.librepocket.shell.frozenOrNull]），同一凍結物件用於門禁
     * reasonCode、執行與審計；凍結失敗即 `Denied(BLACKLISTED)` 且零
     * spawn（審計以空 argv 記一筆 DENY）。
     *
     * Round-3 Phase H（P2-5）：runner throw 審計由下游客
     * [ShellExecTool.executeElevated] 保證（非致命 throw 映 `Failed`＋一筆
     * FAILED 審計，致命重抛前亦已盡力審計），本函數不再另包 try/catch——
     * 重複捕捉即重複審計（`audit.size()==2` 違反單筆契約），故此處刻意只做
     * 單一委派；「所有執行必有一筆審計」由工具層單點保證（直調工具層與經
     * dispatch 同契約，見工具層 KDoc）。
     *
     * Round-4 Phase N（P2-1 gate-first＋SLA 統一＋P2-2 reason 上限）：
     * - gate-first：cheap 門（tool 名／projection／confirmed／reasonCode 空與
     *   長度——皆只讀不可變 `String`／對象引用，不碰不可信 `argv`）一律在快照
     *   之前。`confirmed=false`／projection blocked／reason 缺失或超限即零
     *   snapshot worker（不解析 argv）；門禁未過仍寫一筆 DENY 審計（全路徑留痕），
     *   但以空 argv＋有界 reason 記（超限 reason 不哈希，記空串標記；其餘記
     *   原 reason——已验长度，有界）。
     * - reasonCode 超限（[PrivilegeAuditLog.MAX_REASON_CODE_BYTES] UTF-8 位元組）
     *   即 `Denied(BLACKLISTED)` 且零 spawn（gate 前先行，見
     *   [PrivilegeGate.isElevatedRequest] 同門）。
     * - 單次凍結（SLA 統一）：門禁通過後快照一次，得 frozen 即以
     *   [ShellExecTool.executeElevatedFrozen]（同模組 internal，傳 gate 後同一
     *   frozen）傳遞同一份，工具層不再二次凍結
     *   （舊雙重凍結最壞 2×(1s+grace)；現每 caller 至多一 snapshot worker，
     *   且池化有界見 [dev.librepocket.shell.frozenOrNull]）。
     * - SLA：`timeoutMs` 原值透傳 runner（准入後執行預算，不扣快照耗時；
     *   快照為准入成本，有界 1s＋grace；呼叫方總耗時上界 ≈ 快照門＋`timeoutMs`）。
     *
     * @param tool 必須是 `shell.elevated` 的 [ToolDef]（斷言防誤用）。
     * @param confirmed 當輪二次確認。
     * @param projection 投影（`privilege_bridge` 未開即 UNAVAILABLE，先擋）。
     * @param request 提權請求（含 reasonCode，門禁與審計共用同一份凍結）。
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
        // P2-2 先行：reason 只读（String 不可变，零 worker），超限即拒，
        // 不碰 argv、不哈希无界输入。
        if (PrivilegeAuditLog.isReasonCodeTooLong(request.reasonCode)) {
            val tooLong = ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "reasonCode too long (fail-closed, no spawn)",
            )
            audit.record(emptyList(), "", PrivilegeAuditLog.verdictName(tooLong))
            return tooLong
        }
        // P2-1 gate-first：cheap 门先行（只读 reason，不碰 argv）。
        // 未过即 DENY＋一笔审计（空 argv＋有界 reason），零 snapshot worker。
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
            audit.record(emptyList(), request.reasonCode, PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // 门禁通过才解析不可信 argv：快照一次，同一 frozen 传递工具层（单次冻结）。
        val frozen = request.frozenOrNull() ?: run {
            val snapshotDenied = ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "elevated snapshot failed (fail-closed, no spawn)",
            )
            audit.record(emptyList(), request.reasonCode, PrivilegeAuditLog.verdictName(snapshotDenied))
            return snapshotDenied
        }
        return ShellExecTool.executeElevatedFrozen(frozen, runner, audit, timeoutMs)
    }
}
