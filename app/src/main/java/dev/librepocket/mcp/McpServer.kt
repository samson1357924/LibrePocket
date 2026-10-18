package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.CancellationException

/**
 * D03 單伺服器客戶端（多伺服器 = 每個配置一個 [McpServer] 實例）。
 *
 * 最小子集警示（#11 Stage2 本 PR 選型，非 Accepted ADR）：只會 `tools/list`＋`tools/call`
 * 單頁 POST 子集，不送 `initialize` 交握、不接 SSE event-stream、不跟隨 `nextCursor` 分頁、
 * 不協商 `protocolVersion`（見 [McpWire] 修訂立場）；
 * 未接 production composition（`dev.librepocket.mcp` 外零 production import，
 * 僅 KDoc／description 前向提及，未接 dispatcher／副作用，見 CAPABILITY_MATRIX
 * MCP 行），啟用前須單獨驗 production dispatcher、取消與副作用邊界，不得批次開啟。
 *
 * - 地址使用者配置（[McpServerConfig.baseUrl]），Key 走 [KeyVault] 加密存取；
 * - 方法：[listTools]（tools/list）+ [callTool]（tools/call）；
 * - 熔斷：連續失敗即開路（[McpCircuitBreaker]），開路期間短路為 CIRCUIT_OPEN；
 *   工具業務錯（`isError=true`／error 包）為中性，不重置亦不累計；
 * - 超時：單次 15–30s（[McpTimeouts.clamp]），超時映射 TIMEOUT，不重試；
 * - 傳輸安全：僅放行 `https`，以及回環 `http`（測試／本機；見 [McpWire.requireSecureEndpoint]），
 *   其餘明文一律 PROTOCOL、不碰 vault、不發送、不計熔斷；
 *   傳輸層不自動跟隨 redirect（3xx 終端 → PROTOCOL + 計熔斷，
 *   參數不會重放到未驗證 origin；注入 client 的 redirect／連線重試／503 重送／
 *   authenticator／cookie／cache 政策同樣強制、POST body 為 one-shot，
 *   application interceptor 屬受信任呼叫方元件，見 [McpTransport] KDoc）；
 * - 隱私：Bearer 只放標頭；`CharArray` 用後即擦（`String`／OkHttp 拷貝為已知殘留，
 *   見 THREAT_MODEL，已最小化生命週期）；稽核只記 id/status/latency；
 * - 授權：本層不做 [McpScope.check]，接線 dispatcher 必須先經投影＋確認
 *  （WRITE 未確認不得調 [callTool]，見 [McpScope]）；
 * - 綁定：`keyId` 與 `baseUrl` 無自動綁定，換 URL 不得靜默沿用舊 key，
 *   接線前須補重綁確認（對照 Setup S4-C，見 THREAT_MODEL Target）。
 */
class McpServer(
    private val config: McpServerConfig,
    private val keyVault: KeyVault,
    private val transport: McpTransport = McpTransport(),
    private val breaker: McpCircuitBreaker = McpCircuitBreaker(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun config(): McpServerConfig = config
    fun breaker(): McpCircuitBreaker = breaker

    suspend fun listTools(timeoutMs: Long = config.effectiveTimeoutMs()): McpListResult {
        val start = clockMs()
        if (!config.enabled) {
            return McpListResult(config.id, emptyList(), McpStatus.DISABLED, clockMs() - start)
        }
        if (!breaker.canCall()) {
            return McpListResult(config.id, emptyList(), McpStatus.CIRCUIT_OPEN, clockMs() - start)
        }
        if (config.baseUrl.isBlank()) {
            // 空地址：配置錯誤，不碰 vault、不計熔斷（沿用 UNAUTHORIZED 語義，相容既有測試）。
            return McpListResult(config.id, emptyList(), McpStatus.UNAUTHORIZED, clockMs() - start)
        }
        try {
            McpWire.requireSecureEndpoint(config.baseUrl)
        } catch (_: IllegalArgumentException) {
            // 明文非回環／畸形地址：fail-closed，不碰 vault、不發送、不計熔斷。
            return McpListResult(config.id, emptyList(), McpStatus.PROTOCOL, clockMs() - start)
        }
        val key = keyVault.getKey(config.keyId)
        if (key == null) {
            // 無 key 不計熔斷（配置問題，非傳輸失敗）。
            return McpListResult(config.id, emptyList(), McpStatus.UNAUTHORIZED, clockMs() - start)
        }
        val bearer: String
        try {
            bearer = String(key)
        } finally {
            try {
                key.fill('\u0000')
            } catch (_: Exception) {
            }
        }
        try {
            val raw = transport.postJson(config.baseUrl, bearer, McpWire.listBody(), timeoutMs)
            // bearer 僅棧上短生命週期，不存欄位、不寫 log。
            if (raw == null) {
                breaker.onFailure()
                return McpListResult(config.id, emptyList(), McpStatus.TIMEOUT, clockMs() - start)
            }
            val (code, text) = raw
            if (code == 401 || code == 403) {
                breaker.onFailure()
                return McpListResult(config.id, emptyList(), McpStatus.UNAUTHORIZED, clockMs() - start)
            }
            if (code in 300..399) {
                // 傳輸層不跟隨 redirect（見 McpTransport.transportClient）：3xx 是終端回應。
                // 伺服器/代理配置與本子集不合 → PROTOCOL + 計熔斷；body 不讀、
                // 參數不重放（第二次端點從未收到請求，見 McpTransportSecurityTest）。
                breaker.onFailure()
                return McpListResult(config.id, emptyList(), McpStatus.PROTOCOL, clockMs() - start)
            }
            if (code !in 200..299 || text.isNullOrBlank()) {
                breaker.onFailure()
                return McpListResult(config.id, emptyList(), McpStatus.TRANSPORT, clockMs() - start)
            }
            return try {
                val tools = McpWire.parseList(config.id, text)
                breaker.onSuccess()
                McpListResult(config.id, tools, McpStatus.OK, clockMs() - start)
            } catch (_: IllegalArgumentException) {
                breaker.onFailure()
                McpListResult(config.id, emptyList(), McpStatus.PROTOCOL, clockMs() - start)
            }
        } catch (e: CancellationException) {
            // 外部取消/外部 deadline 一律透傳，不計熔斷、不映射 TRANSPORT/TIMEOUT。
            // 傳輸層內部總 deadline 已轉為 null（TIMEOUT），不會以例外到達此處。
            throw e
        } catch (_: McpResponseTooLarge) {
            // 成功 body 超量（位元組/字元上限）：顯式 PROTOCOL + 計熔斷，不當 OK。
            breaker.onFailure()
            return McpListResult(config.id, emptyList(), McpStatus.PROTOCOL, clockMs() - start)
        } catch (_: McpWire.McpProtocol) {
            // 傳輸層非 JSON 早擋（如 event-stream）：PROTOCOL + 計熔斷（同超量路徑）。
            breaker.onFailure()
            return McpListResult(config.id, emptyList(), McpStatus.PROTOCOL, clockMs() - start)
        } catch (_: Exception) {
            breaker.onFailure()
            return McpListResult(config.id, emptyList(), McpStatus.TRANSPORT, clockMs() - start)
        }
    }

    /**
     * tools/call。
     *
     * @param argumentsJson 必須是合法 JSON object 文本（空字串視為 `{}`）；
     *   非法（空白 tool 名／非 object／畸形）即回 PROTOCOL，不發送、不碰 vault、
     *   不計熔斷。只駐記憶體，不寫盤、不進稽核（稽核見 [McpCallResult.auditMap]）。
     */
    suspend fun callTool(
        toolName: String,
        argumentsJson: String = "{}",
        timeoutMs: Long = config.effectiveTimeoutMs(),
    ): McpCallResult {
        val start = clockMs()
        if (!config.enabled || !config.isToolEnabled(toolName)) {
            return McpCallResult(config.id, toolName, McpStatus.DISABLED, clockMs() - start)
        }
        if (!breaker.canCall()) {
            return McpCallResult(config.id, toolName, McpStatus.CIRCUIT_OPEN, clockMs() - start)
        }
        val body: String
        try {
            body = McpWire.callBody(toolName, argumentsJson)
        } catch (_: IllegalArgumentException) {
            // 客戶端參數錯誤（空白名／非 object／畸形）：fail-closed，不發送、
            // 不碰 vault、不計熔斷（與傳輸失敗區分）。
            return McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
        }
        if (config.baseUrl.isBlank()) {
            return McpCallResult(config.id, toolName, McpStatus.UNAUTHORIZED, clockMs() - start)
        }
        try {
            McpWire.requireSecureEndpoint(config.baseUrl)
        } catch (_: IllegalArgumentException) {
            // 明文非回環／畸形地址：fail-closed，不碰 vault、不發送、不計熔斷。
            return McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
        }
        val key = keyVault.getKey(config.keyId)
        if (key == null) {
            return McpCallResult(config.id, toolName, McpStatus.UNAUTHORIZED, clockMs() - start)
        }
        val bearer: String
        try {
            bearer = String(key)
        } finally {
            try {
                key.fill('\u0000')
            } catch (_: Exception) {
            }
        }
        try {
            val raw = transport.postJson(config.baseUrl, bearer, body, timeoutMs)
            if (raw == null) {
                breaker.onFailure()
                return McpCallResult(config.id, toolName, McpStatus.TIMEOUT, clockMs() - start)
            }
            val (code, text) = raw
            if (code == 401 || code == 403) {
                breaker.onFailure()
                return McpCallResult(config.id, toolName, McpStatus.UNAUTHORIZED, clockMs() - start)
            }
            if (code in 300..399) {
                // 同 listTools：不跟隨 redirect，3xx 終端 → PROTOCOL + 計熔斷。
                breaker.onFailure()
                return McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
            }
            if (code !in 200..299 || text.isNullOrBlank()) {
                breaker.onFailure()
                return McpCallResult(config.id, toolName, McpStatus.TRANSPORT, clockMs() - start)
            }
            return try {
                val (resultJson, isError) = McpWire.parseCall(text)
                if (isError) {
                    // 工具業務錯：中性，不重置亦不累計熔斷（防交錯壓住不斷路）。
                    breaker.onNeutral()
                } else {
                    breaker.onSuccess()
                }
                val status = if (isError) McpStatus.TOOL_ERROR else McpStatus.OK
                McpCallResult(config.id, toolName, status, clockMs() - start, resultJson, isError)
            } catch (e: McpWire.McpToolFailure) {
                // 伺服器回 error 包：工具級錯誤，中性（會話不中斷，不碰熔斷計數）。
                breaker.onNeutral()
                McpCallResult(config.id, toolName, McpStatus.TOOL_ERROR, clockMs() - start, null, true)
            } catch (_: IllegalArgumentException) {
                breaker.onFailure()
                McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
            }
        } catch (e: CancellationException) {
            // 外部取消/外部 deadline 一律透傳，不計熔斷、不映射 TRANSPORT/TIMEOUT。
            // 傳輸層內部總 deadline 已轉為 null（TIMEOUT），不會以例外到達此處。
            throw e
        } catch (_: McpResponseTooLarge) {
            // 成功 body 超量（位元組/字元上限）：顯式 PROTOCOL + 計熔斷，不當 OK。
            breaker.onFailure()
            return McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
        } catch (_: McpWire.McpProtocol) {
            // 傳輸層非 JSON 早擋（如 event-stream）：PROTOCOL + 計熔斷（同超量路徑）。
            breaker.onFailure()
            return McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
        } catch (_: Exception) {
            breaker.onFailure()
            return McpCallResult(config.id, toolName, McpStatus.TRANSPORT, clockMs() - start)
        }
    }
}

/**
 * 多伺服器聚合：薄封裝，逐服獨立熔斷（同進程多 [McpServer] 實例）。
 *
 * `timeoutMs` 為逐服上限（非總量）：N 服最壞 N×timeout，呼叫方需另包總超時；
 * 順序執行，取消時丟棄已得結果（見 Stage2 review W5）。
 */
class McpRegistry(
    private val servers: Map<String, McpServer>,
) {
    fun ids(): Set<String> = servers.keys

    fun get(serverId: String): McpServer? = servers[serverId]

    suspend fun listAll(timeoutMs: Long = McpTimeouts.DEFAULT_MS): Map<String, McpListResult> =
        servers.mapValues { (_, s) -> s.listTools(timeoutMs) }
}
