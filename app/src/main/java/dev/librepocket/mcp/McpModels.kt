package dev.librepocket.mcp

import dev.librepocket.tool.SideEffect

/**
 * D03 MCP 客戶端最小版模型（BACKLOG D03；架構位置見 ARCHITECTURE.md Target
 *「capability state must distinguish…」）。
 *
 * 原創小模型，只走 Streamable HTTP（POST `endpoint(baseUrl)`，使用者自訂完整 URL）+ Bearer；
 * 舊 SSE transport 不做。
 *
 * 隱私不變式（測試強制）：
 * - Token 只放 HTTP `Authorization` 標頭，不進 JSON-RPC body，不寫 log/audit；
 * - Key 一律走 [dev.librepocket.keystore.KeyVault]（[McpServerConfig.keyId]），用後即擦；
 * - 原參數（tools/call argumentsJson）與結果原文不落盤：稽核只記
 *   serverId / tool / status / latencyMs（見 [McpCallResult.auditMap]）。
 */
object McpTimeouts {
    const val MIN_MS: Long = 15_000L
    const val MAX_MS: Long = 30_000L
    const val DEFAULT_MS: Long = 20_000L

    fun clamp(timeoutMs: Long): Long = timeoutMs.coerceIn(MIN_MS, MAX_MS)
}

/** 單一 MCP 伺服器配置：地址由使用者配置，Key 只存 vault（此處只記 keyId）。 */
data class McpServerConfig(
    /** 穩定 id，轉錄/稽核只記此 id，不記地址明文以外的東西（地址本身是使用者配置，可記）。 */
    val id: String,
    /** 使用者配置的伺服器完整 URL，例如 `https://mcp.example.com/mcp`（自訂 path 原樣保留：尾斜線 `.../mcp/` 與 `.../mcp` 對某些 server 不等價，不做正規化）。相容：舊只寫 bare-origin（如 `https://mcp.example.com`）的配置會自動補 `/mcp`（見 `McpWire.endpoint`），建議手動補完整 path。 */
    val baseUrl: String,
    /** [dev.librepocket.keystore.KeyVault] 內的 key 別名，預設 `mcp:<id>`。 */
    val keyId: String = "mcp:$id",
    /** 主開關：false 時一律短路，不發任何 HTTP。 */
    val enabled: Boolean = true,
    /**
     * 逐項啟用表：tool 名 -> 是否啟用。缺席視為啟用（預設開），
     * 明確設 false 即關閉（投影為 UNAVAILABLE + USER_DISABLED）。
     */
    val enabledTools: Map<String, Boolean> = emptyMap(),
    /** 單次呼叫超時，鉗制在 [McpTimeouts.MIN_MS]..[McpTimeouts.MAX_MS]。 */
    val timeoutMs: Long = McpTimeouts.DEFAULT_MS,
) {
    fun effectiveTimeoutMs(): Long = McpTimeouts.clamp(timeoutMs)

    fun isToolEnabled(toolName: String): Boolean =
        enabled && enabledTools.getOrDefault(toolName, true)

    companion object {
        fun disabled(id: String, baseUrl: String): McpServerConfig =
            McpServerConfig(id = id, baseUrl = baseUrl, enabled = false)
    }
}

/** 遠端工具描述（tools/list 回傳）。第三方工具預設副作用為 WRITE（見 [McpScope]）。 */
data class McpTool(
    val serverId: String,
    val name: String,
    val description: String = "",
    /** JSON Schema 文本（原樣透傳給模型做 Schema 校驗，不在此解析）。 */
    val inputSchema: String = """{"type":"object"}""",
    val sideEffect: SideEffect = SideEffect.WRITE,
)

/** 傳輸/邏輯狀態：OK 以外一律視為工具級錯誤，不中斷會話（見 BACKLOG.md D03）。 */
enum class McpStatus {
    OK,
    UNAUTHORIZED,
    TIMEOUT,
    TRANSPORT,
    PROTOCOL,
    TOOL_ERROR,
    DISABLED,
    CIRCUIT_OPEN,

    /**
     * tools/list 本頁解析成功、但伺服器回非空 `nextCursor`（還有下一頁）。
     * 本子集不跟隨分頁（見 [McpWire] 修訂立場）：不回 OK 假完整
     * （外部 review #3），[McpListResult.tools] 保留本頁工具供呼叫方辨識。
     */
    PARTIAL,
}

/** tools/list 結果：只記工具名/描述形狀，不記任何使用者原文。 */
data class McpListResult(
    val serverId: String,
    val tools: List<McpTool>,
    val status: McpStatus,
    val latencyMs: Long,
) {
    fun auditMap(): Map<String, Any?> = mapOf(
        "serverId" to serverId,
        "toolCount" to tools.size,
        "tools" to tools.map { it.name },
        "status" to status.name,
        "latencyMs" to latencyMs,
    )
}

/**
 * tools/call 結果。
 *
 * @param resultJson 僅駐記憶體的原始結果（呼叫方消費後丟棄，永不寫盤）；
 *   [auditMap] 刻意不含 arguments 與 result。
 * @param errorMessage 伺服器 error 包的 message（上限 200 UTF-16 code units
 *   ＝surrogate-safe 截斷，見 [McpWire.truncateUnits]；
 *   僅 [McpStatus.TOOL_ERROR] 時非 null——`message` 是 JSON-RPC 2.0 必備字串欄位，
 *   缺／非字串屬形狀不完整，一律 PROTOCOL＋計熔斷（不會以預設字串充當，
 *   外部 review P3-1）；`isError=true` 結果路徑的 TOOL_ERROR
 *   為 null——該路徑無伺服器 message，詳情在 [resultJson]）。
 *   隱私不變式：不進 [auditMap]。
 */
data class McpCallResult(
    val serverId: String,
    val tool: String,
    val status: McpStatus,
    val latencyMs: Long,
    val resultJson: String? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null,
) {
    fun auditMap(): Map<String, Any?> = mapOf(
        "serverId" to serverId,
        "tool" to tool,
        "status" to status.name,
        "latencyMs" to latencyMs,
        "isError" to isError,
    )
}
