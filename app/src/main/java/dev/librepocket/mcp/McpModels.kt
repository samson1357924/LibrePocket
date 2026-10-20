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
 * - 原參數（tools/call argumentsJson）、結果原文與 errorMessage 不落盤：
 *   稽核只記 serverId / tool / status / latencyMs / isError
 *   （[McpCallResult.auditMap]）與 serverId / toolCount / tools / status /
 *   latencyMs（[McpListResult.auditMap]）；audit 欄位**刻意不含**
 *   arguments／result／error（errorMessage）。
 */
object McpTimeouts {
    const val MIN_MS: Long = 15_000L
    const val MAX_MS: Long = 30_000L
    const val DEFAULT_MS: Long = 20_000L

    fun clamp(timeoutMs: Long): Long = timeoutMs.coerceIn(MIN_MS, MAX_MS)
}

/** 單一 MCP 伺服器配置：地址由使用者配置，Key 只存 vault（此處只記 keyId）。 */
data class McpServerConfig(
    /**
     * 穩定 id（授權／投影身份）。**身份用原文、顯示用轉義**：`switchKey`／
     * `enabledTools` 比對與授權一律用本值原文（不 sanitize、不改字串，保持單射）；
     * 模型可見的展示面（[McpScope.toToolDef] 的 `name`／`description`、
     * `Projection.toolName`）與稽核／日誌面經 `McpWire.escapeControlUnits`
     * 顯示消毒（C0/DEL、C1、LS/PS、bidi、U+200B–200D／U+2060／U+FEFF →
     * `\uXXXX`）；遠端 `toolName` 同政策（R2-02／S2-02）。正常 id no-op。
     * 其餘 Cf（U+00AD、U+180E、U+2061–2064、U+206A–206F、U+FFF9–FFFB、tag
     * U+E0000–E007F 等）仍原樣，屬名混淆殘餘而非換行/重排（S2-03）。
     */
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

/**
 * 遠端工具描述（tools/list 回傳）。第三方工具預設副作用為 WRITE（見 [McpScope]）。
 *
 * `sideEffect` 恆為 [SideEffect.WRITE]：**MCP 子集沒有遠端 sideEffect 宣告**，
 * 本欄位只是模型預設值，**無任何授權路徑讀取**——[McpScope.check]／[McpScope.canExecute]
 * 不收 [McpTool]、亦不收呼叫端 sideEffect 參數，一律按 WRITE 對待（傳 READ 無從
 * 降級確認，S2-R02）。若未來子集納入 sideEffect 宣告，須改為「取自工具定義」並
 * 讓 `McpScope.check` 讀取該值——不得改回收呼叫端參數。
 */
data class McpTool(
    val serverId: String,
    val name: String,
    val description: String = "",
    /**
     * JSON Schema 文本（原樣透傳給 provider／模型；本地**只做 JSON 形狀層**保證
     * ——object-root、可解析、長度上限——**不做 meta-schema 或參數校驗**，
     * 見 [McpScope.toToolDef] 的 C-09 說明與 [McpWire.parseListPage]）。
     * 遠端來源必為 object-root schema（[McpWire.parseListPage] fail-closed：
     * 缺／非 object／根 type 非 "object" 一律 PROTOCOL）；此預設值僅供
     * **本地建構**（未傳參數的直呼叫／測試），不會出現在任何解析結果。
     */
    val inputSchema: String = """{"type":"object"}""",
    val sideEffect: SideEffect = SideEffect.WRITE,
) {
    /**
     * 隱私覆寫（Stage3 C-05）：data class 自動 [toString] 會把 [description]
     * （伺服器控制、可含經 `\\uXXXX` 解碼的 ESC 等控制字元、上限 500 units）與
     * [inputSchema]（伺服器控制、上限 [McpWire.INPUT_SCHEMA_MAX_CHARS] 字元的
     * JSON 文本）整串帶進日誌/crash report——兩者非稽核欄位。此處以 `<N chars>`
     * 佔位取代明細，比照 [McpCallResult.toString]／[McpListResult.toString] 的
     * 既有風格；[serverId]（本地配置）亦經 [McpWire.escapeControlUnits] 輸出供判錯
     *（CR/LF 等不得偽造日誌列）。[name]（入站已拒 C0/DEL
     * 的識別欄位，可合法含空白/非 ASCII）經 [McpWire.escapeControlUnits] 輸出
     *（Stage3 S3-D04：與 [McpCallResult] 稽核面同政策——合法名 no-op 原樣，
     * 本地建構/被拒場景帶入的 C0/DEL、C1、LS/PS、bidi、U+200B–200D／U+2060／
     * U+FEFF 或孤立 surrogate 轉成可見 `\\uXXXX`，日誌面不裸露上述字元、
     * 不外洩孤立 surrogate 損壞編碼；其餘 Cf 仍原樣，屬名混淆殘餘 S2-03）。
     * 不動 `equals`/`copy`
     * （data class 語意不變，僅日誌面收窄）。稽核走 [McpListResult.auditMap]
     * （只記名與計數）。
     */
    override fun toString(): String =
        "McpTool(serverId=${McpWire.escapeControlUnits(serverId)}, " +
            "name=${McpWire.escapeControlUnits(name)}, " +
            "description=<${description.length} chars>, " +
            "inputSchema=<${inputSchema.length} chars>, sideEffect=$sideEffect)"
}

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
    /**
     * 稽核面。[serverId] 是本地配置值、tool 名可來自 wire 或本地建構，兩者都可能
     * 含 CR/LF 等 C0/DEL、C1、bidi 控制、零寬、LS/PS（偽造稽核列／視覺偽裝）——
     * 一律經 [McpWire.escapeControlUnits] 轉成可見 `\\uXXXX`，一般字元原樣，
     * 與 [McpCallResult.auditMap] 的 `tool` 欄同政策。wire 來源的名雖已 fail-closed
     * 拒 C0/DEL 與孤立 surrogate，C1/LS/PS/bidi/零寬仍可自遠端流入，故 `tools`
     * 清單逐名轉義（PR #93 F-03/RT-03）；本地直呼建構子的呼叫端亦受同一保護。
     */
    fun auditMap(): Map<String, Any?> = mapOf(
        "serverId" to McpWire.escapeControlUnits(serverId),
        "toolCount" to tools.size,
        "tools" to tools.map { McpWire.escapeControlUnits(it.name) },
        "status" to status.name,
        "latencyMs" to latencyMs,
    )

    /**
     * 隱私覆寫：data class 自動 [toString] 會把 [tools] 內每支工具的 name/description/
     * inputSchema（伺服器控制、可極大、可含控制/格式字元）整串帶進日誌/crash report——
     * description/inputSchema 非稽核欄位（[auditMap] 只記名與計數）。此處以計數佔位取代
     * 明細，與 [McpCallResult.toString] 同策略；[serverId]（本地配置）經
     * [McpWire.escapeControlUnits] 輸出，CR/LF 不偽造日誌列。稽核一律走 [auditMap]，
     * 不走 [toString]。
     */
    override fun toString(): String =
        "McpListResult(serverId=${McpWire.escapeControlUnits(serverId)}, " +
            "tools=<${tools.size} tools>, " +
            "status=$status, latencyMs=$latencyMs)"
}

/**
 * tools/call 結果。
 *
 * @param resultJson 僅駐記憶體的原始結果（呼叫方消費後丟棄，永不寫盤）；
 *   [auditMap] 刻意不含 arguments 與 result。
 * @param errorMessage 伺服器 error 包的 message（wire 層消毒後上限
 *   [McpWire.ERROR_MESSAGE_MAX_UNITS]＝200 UTF-16 code units，C0/DEL、C1、LS/PS、
 *   bidi 控制與零寬（U+200B–200D／U+2060／U+FEFF）已換成可見 `\\uXXXX` 轉義、
 *   轉義/surrogate 邊界截斷（其餘 Cf 仍原樣，屬名混淆殘餘 S2-03），
 *   見 [McpWire.escapeControlUnits]；
 *   僅 [McpStatus.TOOL_ERROR] 時非 null——`message` 是 JSON-RPC 2.0 必備字串欄位，
 *   缺／非字串屬形狀不完整，一律 PROTOCOL＋計熔斷（不會以預設字串充當，
 *   外部 review P3-1；**空／純空白** message 則在 wire 層填靜態
 *   `jsonrpc error <code>`——B-03，伺服器不得以空 message 讓錯誤不可辨識）；
 *   `isError=true` 結果路徑的 TOOL_ERROR
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
    /**
     * 稽核面 [tool] 欄（Stage3 A-01）：形狀閘**被拒**的 toolName（呼叫端自備字串，
     * 可含 CR/LF/ESC）仍會進本欄供診斷，但只以 [McpWire.escapeControlUnits] 的
     * 可見 `\\uXXXX` 轉義形式出現——C0/DEL、C1、LS/PS、bidi、U+200B–200D／U+2060／
     * U+FEFF 的偽造稽核列／視覺偽裝通道關閉（其餘 Cf 仍原樣，屬名混淆殘餘 S2-03）。
     * 合法名（入站已拒 C0/DEL、出站形狀閘同政策）原樣保留，既有稽核語意不變。
     * [serverId] 同政策（本地配置值可含控制/格式字元）：C0/DEL、C1、LS/PS、bidi、
     * U+200B–200D／U+2060／U+FEFF 與孤立 surrogate 轉義，一般字元原樣；其餘 Cf
     * 仍原樣（S2-03）。
     */
    fun auditMap(): Map<String, Any?> = mapOf(
        "serverId" to McpWire.escapeControlUnits(serverId),
        "tool" to McpWire.escapeControlUnits(tool),
        "status" to status.name,
        "latencyMs" to latencyMs,
        "isError" to isError,
    )

    /**
     * 隱私覆寫：data class 自動 [toString] 會把 [resultJson]（結果原文）與
     * [errorMessage]（伺服器訊息）一併帶進日誌/crash report——違反「原文不落盤」
     * 不變式（[auditMap] 已刻意排除兩者）。此處以佔位符取代原文，僅保留判錯所需
     * 的狀態欄位；[tool] 同 [auditMap] 以轉義形式輸出（被拒名的裸 CR/LF 不得
     * 經日誌面外顯，Stage3 A-01）；[serverId]（本地配置）同經
     * [McpWire.escapeControlUnits] 轉義。稽核一律走 [auditMap]，不走 [toString]。
     */
    override fun toString(): String =
        "McpCallResult(serverId=${McpWire.escapeControlUnits(serverId)}, " +
            "tool=${McpWire.escapeControlUnits(tool)}, status=$status, " +
            "latencyMs=$latencyMs, isError=$isError, " +
            "resultJson=<${resultJson?.length ?: 0} chars>, " +
            "errorMessage=<${errorMessage?.length ?: 0} chars>)"
}
