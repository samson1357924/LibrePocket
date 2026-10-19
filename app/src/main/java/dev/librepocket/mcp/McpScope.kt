package dev.librepocket.mcp

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolAnnotations
import dev.librepocket.tool.ToolDef

/**
 * D03 第三方工具範圍門（投影 + 確認），與內建快工具同鏈路
 *（見 ARCHITECTURE.md Target「capability state must distinguish…」與 BACKLOG.md D03）。
 *
 * 規則（原創，BACKLOG D03「再投影，預設 WRITE」）：
 * 1. 副作用一律按 [SideEffect.WRITE] 對待（單一真相＝「第三方工具一律需確認」）：
 *    **MCP 子集沒有 sideEffect 宣告**（[McpTool.sideEffect] 恆 WRITE），故 [check]／
 *    [canExecute]／[toToolDef] 皆**不收呼叫端 sideEffect 參數**——呼叫端無從以
 *    `READ` 降級跳過確認（比內建 WRITE 更嚴：內建 WRITE 在
 *    [dev.librepocket.router.PrivilegeGate] 可直接放行，第三方 WRITE 必須確認，
 *    見 [check]）。若未來子集納入 sideEffect 宣告，須改為**取自工具定義**，
 *    不得改回收呼叫端參數。
 * 2. 投影（[project] 純函數）：伺服器主開關關閉，或該工具逐項關閉
 *    （[McpServerConfig.enabledTools] 以 bare 名、[ProjectionContext.userSwitches]
 *    以 [switchKey] 的結構化鍵 `mcp.<len>|<serverId>|<tool>` 覆寫），
 *    一律 UNAVAILABLE + USER_DISABLED；
 *    開啟即 NATIVE（MCP 只需網路權限，無風味封鎖；開關預設開是為了讓
 *    確認閘（[check]）接管 WRITE 授權，而非預設放行執行——執行仍須確認）。
 * 3. 確認（[check]）：投影不可用一律 [McpGate.Denied]；可用但未確認一律
 *    [McpGate.NeedConfirm]（規則 1：恆 WRITE，無 READ 免確認路徑）。
 *    [McpGate.NeedConfirm] 攜**結構化授權識別** [McpGate.NeedConfirm.switchKey]；
 *    其 `toolName` 僅為展示字串（見該型別 KDoc，C-04 訊息邊界）。
 *    **單一投影真相（C-01）**：[check]／[canExecute] 不收呼叫端的 [Projection]——
 *    每次呼叫以**傳入的** [McpServerConfig] ＋ [ProjectionContext] 在內部重投影
 *    （委託 [project]）。呼叫方無法再把停用前的舊投影／他服的投影帶進來放行；
 *    內建 [dev.librepocket.router.PrivilegeGate.check] 仍收呼叫端投影，
 *    MCP 這條路刻意收得更嚴。
 * 4. toolName 形狀（C-02，[requireBareToolName]）：投影／確認／開關鍵一律只收
 *    **bare 遠端名**。已限定名（`mcp.…`，即 [ToolDef.name] 的形狀）、空白名、
 *    C0/DEL 控制字元、超 128 UTF-16 units（與 wire [McpWire.TOOL_NAME_MAX_CHARS]
 *    同源）一律 `IllegalArgumentException`（fail-closed）——否則
 *    `enabledTools` 以遠端 bare 名查鍵、缺鍵預設啟用，停用清單會被「誤傳
 *    已限定名」靜默繞過（全鏈綠燈、無 require 報錯）。
 *
 * 鍵形狀（C-04 已修）：**授權鍵**與**展示形狀**分離——
 * - 授權鍵＝[switchKey]（＝[ToolAnnotations.requiresSwitch]／
 *   [ProjectionContext.userSwitches] 的鍵）：長度前綴結構化形狀
 *   `mcp.<serverId 的 UTF-16 code units 數（String.length）>|<serverId>|<toolName>`
 *   （如 `mcp.2|s1|search`），
 *   對任意輸入**單射**——遠端 tool 名合法可含 `.`／`|`、serverId 不設形狀限制，
 *   舊字串拼接形狀 `mcp.<id>.<tool>` 的跨服碰撞
 *   （`switchKey("a.b","c") == switchKey("a","b.c")`）已不可能；
 * - 展示形狀＝[ToolDef.name]／[Projection.toolName]：維持
 *   `mcp.<serverId>.<toolName>`（例如 `mcp.files.search`）——**文件化契約**
 *   （wire 保留前綴 `mcp.` 兩端同政策），**不作授權用途**。誠實揭露：
 *   dotted serverId 下兩服的展示字串仍可能相同（如 `("a.b","c")` 與 `("a","b.c")`
 *   皆 `mcp.a.b.c`），接線時不得以展示字串反解授權對象，一律以
 *   `(config.id, toolName)` 二元組取用（現未接線，僅本包＋測試受影響）。
 * 命名保留：橋接別名（`LspTools` 約定 `mcp.<serverId>.lsp…`）不得與遠端
 * `tools/list` 回傳名機械式區分，碰撞時逃逸規則待定（現未接線，僅預留）。
 */
object McpScope {

    /**
     * 保留前綴（`mcp.`）：**單一真相**——[ToolDef.name]／[Projection.toolName] 的
     * 展示形狀與 [switchKey] 授權鍵**都以此開頭**（鍵為
     * `mcp.<serverId 的 UTF-16 code units 數>|<id>|<tool>`，
     * 見 [switchKey]），兩種形狀同在保留命名空間內。wire 層入站政策直接引用本常數
     * （[McpWire.RESERVED_TOOL_PREFIX] 是本常數的別名，不另立魔數），兩端同政策：
     * 入站 `McpWire.parseListPage` 對此前綴名整頁拒、本包 [requireBareToolName] 拒用
     * ——故開關鍵永不可能是合法遠端 bare 名（誤當工具名用必被 fail-closed 拒，已測）。
     */
    const val SWITCH_PREFIX = "mcp."

    /**
     * 開關鍵＝**授權鍵**（C-04 已修）：長度前綴結構化形狀
     * `mcp.<serverId 的 UTF-16 code units 數（String.length）>|<serverId>|<toolName>`
     * （例如 `mcp.2|s1|search`）。
     *
     * **單射性（跨服不碰撞）**：前綴之後先寫十進位的 `String.length`（UTF-16 code
     * units 數，**非 code point 數**——emoji 等非 BMP 字元計 2 units）＋`|`，
     * 再接**恰該 units 數**的 serverId、`|`、最後是 toolName——給定鍵字串，
     * 數字段由 `|` 定界、id 段長度由數字唯一決定，(serverId, toolName) 可唯一解回，
     * 故 `switchKey("a.b","c") != switchKey("a","b.c")`
     * 與 `switchKey("a|b","c") != switchKey("a","b|c")`（皆已測）。
     * 刻意**不拒字元、不 sanitize、不設 serverId 形狀**：遠端 tool 名含 `.`／`|`
     * 是合法遠端形狀（拒了會「列出但不可用」），serverId 是既有配置值——
     * 結構化鍵對正常輸入零失敗。
     *
     * [toolName] 必須是 bare 名（[requireBareToolName] fail-closed）。
     *
     * **與展示形狀不同字串**：[ToolDef.name]／[Projection.toolName] 維持
     * `mcp.<id>.<tool>`（文件化契約，見類 KDoc）。`annotations.requiresSwitch` 與
     * [ProjectionContext.userSwitches] 一律用本函形狀，不得以 name 字面代替；
     * 反向亦然——本鍵字串不得當 bare tool 名送入任何入口（以 `mcp.` 開頭，
     * [requireBareToolName] 必拒，fail-closed 已測）。
     */
    fun switchKey(serverId: String, toolName: String): String {
        requireBareToolName(toolName)
        return "$SWITCH_PREFIX${serverId.length}|$serverId|$toolName"
    }

    /**
     * 展示形狀（[ToolDef.name]／[Projection.toolName]，文件化契約）：
     * `mcp.<serverId>.<toolName>`。只作展示／識別，**不作授權鍵**（授權一律經
     * [switchKey]）；dotted serverId 下可能跨服同字串（類 KDoc 誠實揭露）。
     */
    private fun qualifiedName(serverId: String, toolName: String): String =
        "$SWITCH_PREFIX$serverId.$toolName"

    /**
     * toolName 形狀校驗（C-02，fail-closed；違反一律 `IllegalArgumentException`）：
     * - 空白名（[String.isNotBlank]，與 wire 出站 `callBody` 同政策）——Unicode 空白
     *   同樣算空白（NBSP `U+00A0`、全形空格 `U+3000` 等，[Char.isWhitespace] 語意，已測）；
     * - 已限定名：以 [SWITCH_PREFIX]（`mcp.`）開頭——那是 [ToolDef.name]（展示形狀）
     *   與 [switchKey]（授權鍵 `mcp.<len>|<id>|<tool>`）共用的保留前綴，不是遠端
     *   bare 名；拿它查 `enabledTools` 會因缺鍵預設啟用而繞過停用清單。
     *   **保留前綴兩端同政策**（單一真相 [McpWire.RESERVED_TOOL_PREFIX]＝[SWITCH_PREFIX]）：
     *   入站 `McpWire.parseListPage` 對此前綴的遠端名**整頁拒**（拋 [McpWire.McpProtocol]）、
     *   出站／授權側（本函）**拒用**——兩邊都做，才不會「列出但不可用」；
     * - C0 控制字元（`< 0x20`）與 DEL（`0x7F`）——與入站 `parseListPage` 同政策
     *   （控制字元干擾終端／以名為鍵的比較；**稽核列偽造**雙端皆已收斂：入站
     *   `parseListPage` 拒控制字元名使該類名無法自遠端列表流入，出站
     *   [McpWire.callBody] 同政策拒發送（Stage3 A-01 對稱防護），被拒名在
     *   [McpCallResult.auditMap]／`toString()` 只以 `\\uXXXX` 轉義形式出現
     *   （見 [McpCallResult.auditMap]）；
     * - 超 [McpWire.TOOL_NAME_MAX_CHARS]（128）UTF-16 code units——**直接引用 wire 常數**，
     *   不另立魔數（wire 層入站／出站已同此常數，三方同源）。
     *
     * **不做正規化（精確鍵語意）**：不 trim、大小寫敏感；[McpServerConfig.enabledTools]
     * 以遠端名**精確比對**（`getOrDefault(toolName, true)`），變體名（前後空白／大小寫）
     * 視為**不同工具**（`" search"` ≠ `"search"`，停用 `search` 不會連帶停用前者）。
     *
     * **已知不對稱（孤立 surrogate）**：本函不檢查孤立 surrogate（授權／投影路徑放行
     * `a\uD800` 類名），出站 [McpWire.callBody] 一律拒——交叉 pin：scope 放行、
     * `McpServer.callTool` 回 PROTOCOL（不發送）。理由：scope 只管「用哪個名查鍵／
     * 建開關鍵」，名不出網路；出站才需要 UTF-8 編碼一致性（孤立 surrogate 會變
     * U+FFFD、送達名與列表名不一致）。若要收斂此不對稱，須在 scope 加同檢查並
     * 明列於此。
     *
     * **Java interop（re-review F-S2-03）**：[toolName] 是 Kotlin 非空型別；Java 呼叫方
     * 傳 `null` 會在 `isNotBlank()` 的 receiver 上拋 NPE——仍 fail-closed（不發送、
     * 不碰 vault、不計熔斷），但不經 [McpServer.callTool] 的 `catch
     * (IllegalArgumentException)` 映射為狀態，例外直穿呼叫方。非 null 契約，
     * Kotlin 路徑不可達。
     *
     * 為何在 MCP 自己的授權入口擋（而非只擋在 wire）：[project]/[check]/[canExecute]/
     * [switchKey]/[toToolDef] 是授權路徑本身；`McpServer.callTool` 亦於發送前呼叫本函，
     * 例外映射為 PROTOCOL（不發送、不碰 vault、不計熔斷，見 [McpServer.callTool]）。
     */
    fun requireBareToolName(toolName: String) {
        require(toolName.isNotBlank()) { "mcp: blank tool name" }
        require(!toolName.startsWith(SWITCH_PREFIX)) {
            "mcp: tool name must be bare (no $SWITCH_PREFIX qualification)"
        }
        require(toolName.length <= McpWire.TOOL_NAME_MAX_CHARS) {
            "mcp: tool name exceeds ${McpWire.TOOL_NAME_MAX_CHARS} chars"
        }
        require(toolName.none { it.code < 0x20 || it.code == 0x7F }) {
            "mcp: tool name has control chars"
        }
    }

    /**
     * 第三方工具轉內建 [ToolDef] 視圖（**恆 [SideEffect.WRITE]**），便於複用 Schema/轉錄鏈路。
     *
     * **無 sideEffect 參數（S2-R02）**：MCP 子集沒有 sideEffect 宣告（[McpTool.sideEffect]
     * 恆 WRITE），本函固定產出 [SideEffect.WRITE] 的 [ToolDef]——呼叫端無從以 `READ`
     * 降級（`ToolDef.sideEffect` 恆 WRITE）。若未來子集納入宣告，須改為**取自工具定義**
     *（遠端宣告欄位），不得改回收呼叫端參數。
     *
     * 主開關可見（C-01）：收 [McpServerConfig] 而非 bare serverId——
     * [ToolAnnotations.switchDefault] 取 [McpServerConfig.isToolEnabled]
     * （＝`config.enabled && enabledTools.getOrDefault(toolName, true)`：
     * **缺鍵預設啟用**，明確 `false` 才停用）。停用伺服器／停用工具在
     * **內建投影路徑**（[ToolDef.project]，鍵缺席的預設 [ProjectionContext]）
     * 投影 UNAVAILABLE + USER_DISABLED，不再一律預設開。
     *
     * 仍存限制（誠實揭露，見 CAPABILITY_MATRIX「MCP 啟用／接線 gate 清單」）：
     * 內建投影路徑只看 [ProjectionContext.userSwitches]、看不到 [McpServerConfig]——
     * 若呼叫端在 ctx 明寫 `userSwitches[switchKey(id, tool)]=true`（結構化鍵
     * `mcp.<len>|<id>|<tool>`，見 [switchKey]），停用伺服器的
     * ToolDef 會被翻回 NATIVE（本輪無法在包內表達「無條件主開關」：內建
     * annotations 沒有不可覆寫的停用位）。兩道既有兜底：① MCP 自己的授權路徑
     * （[project]／[check]／[canExecute]）一律以 config 重查、不吃此覆寫；
     * ② [executionReady] 恆 false——內建 `visibleTools`／FastRouter 雙閘，
     * 停用與否皆不可執行、模型不可見。
     *
     * @param inputSchema 遠端 `tools/list` 回傳的 JSON Schema 文本（[McpTool.inputSchema]），
     *   **原樣透傳、本地只做 JSON 形狀層保證（C-09 精確化，行為不變）**：
     *   - 遠端路徑：[McpWire.parseListPage] 已 fail-closed 保證為 **object-root**
     *     （根 `type` 恆字串 `"object"`）且 ≤ [McpWire.INPUT_SCHEMA_MAX_CHARS]（8192）
     *     字元的合法 JSON——到手即可安全透傳，不會帶畸形 JSON 進 [ToolDef.jsonSchema]；
     *   - **語義不在本地驗**：schema 是否為合法 JSON Schema 語義（如
     *     `{"type":"banana"}`、缺 `type`、空物件）與參數是否符合 schema，
     *     一律由 provider／遠端伺服器負責——provider 端僅再驗**同一層 JSON 形狀**
     *     （[dev.librepocket.provider.toolSchemaElement] 的 root-object 檢查，非 object／
     *     不可解析 → `ProviderFailure` fail-closed），**不做 meta-schema 校驗**；
     *     語義爛但形狀合法的 schema 會原樣送進請求（誠實揭露，非 fail-closed）；
     *   - 本地建構預設 `{"type":"object"}`（未傳參數的直呼叫／測試用）與遠端契約
     *     無關——wire 層遠端缺 inputSchema 一律 PROTOCOL（[McpWire.parseListPage]
     *     fail-closed），不會以本預設值充當遠端宣告。
     */
    fun toToolDef(
        config: McpServerConfig,
        toolName: String,
        inputSchema: String = """{"type":"object"}""",
    ): ToolDef {
        // 形狀校驗 fail-closed（C-02）：經 switchKey 統一入口，已限定名/空白/控制字元/超長皆拒。
        val key = switchKey(config.id, toolName)
        return ToolDef(
            // 展示形狀（文件化契約）≠ 授權鍵：name 走 qualifiedName、requiresSwitch 走 switchKey（C-04）。
            name = qualifiedName(config.id, toolName),
            description = "Third-party MCP tool $toolName on server ${config.id} (default WRITE).",
            jsonSchema = inputSchema,
            // MCP 子集無 sideEffect 宣告 → 恆 WRITE（S2-R02：呼叫端不得降級，見本函 KDoc）。
            sideEffect = SideEffect.WRITE,
            // 顯式雙閘：即使將來接線，模型仍不可見（須 wiring PR 逐項翻轉）。
            executionReady = false,
            annotations = ToolAnnotations(
                requiresSwitch = key,
                // 主開關（C-01）：停用即預設關——內建投影路徑對鍵缺席 context
                // 投影 UNAVAILABLE + USER_DISABLED（見本函 KDoc 的殘餘限制）。
                switchDefault = config.isToolEnabled(toolName),
                fallbackHint = "ask the user to enable or confirm the MCP tool manually",
            ),
        )
    }

    /** 純函數投影：相同輸入必得相同輸出。投影只看開關/啟用——MCP 子集無 sideEffect 宣告，本物件不收任何 sideEffect 參數（見類 KDoc 規則 1）。 */
    fun project(
        config: McpServerConfig,
        toolName: String,
        ctx: ProjectionContext = ProjectionContext(),
    ): Projection {
        requireBareToolName(toolName)
        val qualified = qualifiedName(config.id, toolName)
        if (!config.enabled || !config.isToolEnabled(toolName)) {
            return Projection(qualified, CapabilityLevel.UNAVAILABLE, DenyReason.USER_DISABLED)
        }
        if (!ctx.switchOn(switchKey(config.id, toolName), true)) {
            return Projection(qualified, CapabilityLevel.UNAVAILABLE, DenyReason.USER_DISABLED)
        }
        return Projection(qualified, CapabilityLevel.NATIVE, null)
    }

    /**
     * 確認閘（C-01 單一投影真相）：**不收呼叫端 [Projection]**——此處以傳入的
     * [config] ＋ [ctx] 重投影（[project]），停用前的舊投影／他服的舊投影無法再被
     * 帶入放行（舊 API 收呼叫端投影：`check(offCfg, "t", true, project(onCfg, "t"))`
     * 會 Allowed——伺服器已停用卻放行；現為 `Denied`）。
     *
     * **不收 sideEffect 參數（S2-R02）**：MCP 子集無 sideEffect 宣告
     * （[McpTool.sideEffect] 恆 WRITE），一律按 [SideEffect.WRITE] 對待——
     * 未確認一律 [McpGate.NeedConfirm]（第三方工具一律需確認；呼叫端不得以
     * `READ` 降級跳過確認）。若未來子集納入宣告，須改為取自工具定義，
     * 不得改回收呼叫端參數。
     *
     * 未確認回 [McpGate.NeedConfirm]，其 [McpGate.NeedConfirm.switchKey] 是**授權識別**
     * （結構化 [switchKey]，對 `(config.id, toolName)` 單射）；
     * [McpGate.NeedConfirm.toolName] 只是展示字串，接線回送不得以它反解授權對象
     * （C-04 訊息邊界，見 [McpGate.NeedConfirm] KDoc）。
     *
     * [toolName] 形狀 fail-closed（C-02，經 [project]→[requireBareToolName]）。
     */
    fun check(
        config: McpServerConfig,
        toolName: String,
        confirmed: Boolean,
        ctx: ProjectionContext = ProjectionContext(),
    ): McpGate {
        val projection = project(config, toolName, ctx)
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            return McpGate.Denied(projection.reason ?: DenyReason.USER_DISABLED)
        }
        // 第三方收緊：恆 WRITE、未確認即需確認（內建 WRITE 在 PrivilegeGate 可直行；
        // MCP 無 sideEffect 宣告，呼叫端無從降級——見本函 KDoc）。
        if (!confirmed) {
            return McpGate.NeedConfirm(
                toolName = projection.toolName,
                // 授權識別一律用結構化 switchKey，絕不用展示字串（C-04）。
                switchKey = switchKey(config.id, toolName),
            )
        }
        return McpGate.Allowed
    }

    /** 同 [check]（C-01 內部重投影、C-02 形狀 fail-closed、恆 WRITE 不收 sideEffect）：只有 [McpGate.Allowed] 為 true。 */
    fun canExecute(
        config: McpServerConfig,
        toolName: String,
        confirmed: Boolean,
        ctx: ProjectionContext = ProjectionContext(),
    ): Boolean = check(config, toolName, confirmed, ctx) is McpGate.Allowed
}

/** MCP 授權結果（鏡像 PrivilegeGate 形狀；MCP 恆 WRITE——未確認一律 [NeedConfirm]）。 */
sealed interface McpGate {
    data object Allowed : McpGate

    /**
     * 需使用者確認（MCP 恆 WRITE）。
     *
     * **[switchKey] 是授權識別；[toolName] 只是展示字串**（[ToolDef.name] 形狀
     * `mcp.<id>.<tool>`）。識別／回送**一律用 [switchKey]**（結構化、對
     * `(serverId, toolName)` 單射，見 [McpScope.switchKey]），**不得用 [toolName]**：
     * dotted serverId 下兩服可投影出相同展示字串（`("a.b","c")` 與 `("a","b.c")`
     * 皆 `mcp.a.b.c`），以展示字串回送 `callTool`／查開關會錯位到另一服
     * （C-04 在訊息邊界的殘餘）。
     */
    data class NeedConfirm(val toolName: String, val switchKey: String) : McpGate

    data class Denied(val reason: DenyReason) : McpGate
}
