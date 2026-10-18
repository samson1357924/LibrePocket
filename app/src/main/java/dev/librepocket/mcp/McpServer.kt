package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.net.SocketTimeoutException
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
 * - 方法：[listTools]（tools/list）+ [callTool]（tools/call）；回應須
 *   `jsonrpc=="2.0"`＋id 數值相符（固定 list=1／call=2）＋`result` XOR `error`，
 *   error 形狀須含整數 `code` 與字串 `message`（缺／型別錯 → PROTOCOL＋計熔斷），
 *   error 依 code 分類：協議/伺服器碼（-32700/-32600/-32601/-32603，及 JSON-RPC
 *   保留 server 範圍 −32099..−32000）→ PROTOCOL＋計熔斷，
 *   `-32602`/範圍外未知碼 → TOOL_ERROR 中性（message 透傳 [McpCallResult.errorMessage]，
 *   不進稽核）；非空 `nextCursor` → PARTIAL（不回 OK 假完整）；
 *   HTTP 400/404/405 → PROTOCOL＋計熔斷（非暫態配置錯；429/408 等仍 TRANSPORT）；
 * - 熔斷：連續失敗即開路（[McpCircuitBreaker]），開路期間短路為 CIRCUIT_OPEN；
 *   工具業務錯（`isError=true` 且具 content/structuredContent、`-32602`/範圍外未知碼
 *   error 包）為中性，不重置亦不累計；協議碼與保留 server 範圍 error 包計熔斷
 *   （見上方法段）；result 兩鍵皆無屬形狀不完整 → 一律 PROTOCOL＋計熔斷
 *   （含 `isError=true` 但無內容的變體，見 [McpWire.parseCall]）；
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
            // 空地址：配置錯誤，不碰 vault、不計熔斷（UNAUTHORIZED，與 vault-miss 同待遇）。
            return McpListResult(config.id, emptyList(), McpStatus.UNAUTHORIZED, clockMs() - start)
        }
        try {
            McpWire.requireSecureEndpoint(config.baseUrl)
        } catch (_: IllegalArgumentException) {
            // 明文非回環／畸形地址：fail-closed，不碰 vault、不發送、不計熔斷。
            return McpListResult(config.id, emptyList(), McpStatus.PROTOCOL, clockMs() - start)
        }
        val key = try {
            keyVault.getKey(config.keyId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // vault 存取例外（keystore 錯誤／裝置鎖定等）：與無 key 同待遇（UNAUTHORIZED、
            // 不計熔斷——本地 keystore 問題非傳輸失敗），絕不外拋崩潰呼叫方。
            return McpListResult(config.id, emptyList(), McpStatus.UNAUTHORIZED, clockMs() - start)
        }
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
        // Bearer 預檢（fail-closed）：只放行可列印 ASCII（0x20–0x7E）。key 含控制字元／
        // 非 ASCII（含孤立 surrogate）即 UNAUTHORIZED、不發送、不計熔斷——這類值非合法
        // token（多為 keystore 損壞或注入企圖），放行會把 CR/LF 等塞進標頭造成 header injection。
        if (!isPrintableAscii(bearer)) {
            return McpListResult(config.id, emptyList(), McpStatus.UNAUTHORIZED, clockMs() - start)
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
            if (code == 400 || code == 404 || code == 405) {
                // 端點/請求形狀配置錯（非暫態）：PROTOCOL + 計熔斷（同 3xx 待遇）；
                // 429/408 等其餘 4xx/5xx 屬暫態走 TRANSPORT——所有非 2xx 分支皆計熔斷，
                // 分類只差 status 標籤（診斷用），不差開路行為。
                breaker.onFailure()
                return McpListResult(config.id, emptyList(), McpStatus.PROTOCOL, clockMs() - start)
            }
            // 其餘 4xx/5xx → TRANSPORT（含 406/415：本子集只送 Accept: application/json、
            // 不協商 text/event-stream，合規 server 若回 406 屬互通差異——歸暫態並計熔斷，
            // 3 連敗即開路，不無限重試。有意決策，見 THREAT_MODEL MCP 行）。
            if (code !in 200..299 || text.isNullOrBlank()) {
                breaker.onFailure()
                return McpListResult(config.id, emptyList(), McpStatus.TRANSPORT, clockMs() - start)
            }
            return try {
                // expectedId=LIST_ID：回應 id 必須數值相符（錯 id 回應 fail-closed，
                // 不採納也不重置熔斷，見 McpWire.requireResponseEnvelope）。
                val page = McpWire.parseListPage(config.id, text, McpWire.LIST_ID)
                breaker.onSuccess()
                // 非空 nextCursor → PARTIAL（本子集不跟隨分頁，不回 OK 假完整，
                // 外部 review #3）；本頁工具保留供呼叫方辨識。傳輸/解析成功仍計成功。
                val status = if (page.hasMorePages) McpStatus.PARTIAL else McpStatus.OK
                McpListResult(config.id, page.tools, status, clockMs() - start)
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
        } catch (_: SocketTimeoutException) {
            // 讀/連線超時（非本層 deadline）：與本層 deadline 同待遇 → TIMEOUT + 計熔斷
            //（非 TRANSPORT——超時是暫態、可重試語義，分類應為 TIMEOUT）。
            breaker.onFailure()
            return McpListResult(config.id, emptyList(), McpStatus.TIMEOUT, clockMs() - start)
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
            // 空地址：配置錯誤，不碰 vault、不計熔斷（沿用 UNAUTHORIZED 語義）。
            return McpCallResult(config.id, toolName, McpStatus.UNAUTHORIZED, clockMs() - start)
        }
        try {
            McpWire.requireSecureEndpoint(config.baseUrl)
        } catch (_: IllegalArgumentException) {
            // 明文非回環／畸形地址：fail-closed，不碰 vault、不發送、不計熔斷。
            return McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
        }
        val key = try {
            keyVault.getKey(config.keyId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // vault 存取例外（keystore 錯誤／裝置鎖定等）：與無 key 同待遇（UNAUTHORIZED、
            // 不計熔斷——本地 keystore 問題非傳輸失敗），絕不外拋崩潰呼叫方。
            return McpCallResult(config.id, toolName, McpStatus.UNAUTHORIZED, clockMs() - start)
        }
        if (key == null) {
            // 無 key 不計熔斷（配置問題，非傳輸失敗）。
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
        // Bearer 預檢（fail-closed，同 listTools）：只放行可列印 ASCII，非此即 UNAUTHORIZED、
        // 不發送、不計熔斷（防 header injection／keystore 損壞值流入標頭）。
        if (!isPrintableAscii(bearer)) {
            return McpCallResult(config.id, toolName, McpStatus.UNAUTHORIZED, clockMs() - start)
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
            if (code == 400 || code == 404 || code == 405) {
                // 同 listTools：端點/請求形狀配置錯（非暫態）→ PROTOCOL + 計熔斷
                // （其餘 4xx/5xx 走 TRANSPORT，但熔斷待遇相同——皆 onFailure）。
                breaker.onFailure()
                return McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
            }
            if (code !in 200..299 || text.isNullOrBlank()) {
                breaker.onFailure()
                return McpCallResult(config.id, toolName, McpStatus.TRANSPORT, clockMs() - start)
            }
            return try {
                // expectedId=CALL_ID：回應 id 必須數值相符（同 listTools）。
                val (resultJson, isError) = McpWire.parseCall(text, McpWire.CALL_ID)
                if (isError) {
                    // 工具業務錯：中性，不重置亦不累計熔斷（防交錯壓住不斷路）。
                    breaker.onNeutral()
                } else {
                    breaker.onSuccess()
                }
                val status = if (isError) McpStatus.TOOL_ERROR else McpStatus.OK
                McpCallResult(config.id, toolName, status, clockMs() - start, resultJson, isError)
            } catch (e: McpWire.McpToolFailure) {
                // 僅 -32602/範圍外未知碼 error 包會到此（協議碼與保留 server 範圍
                // −32099..−32000 於 parseCall 拋 McpProtocol → 下方 IAE catch
                // → PROTOCOL + 計熔斷）；工具級錯誤中性，不碰熔斷計數。
                // errorMessage 透傳伺服器 message（wire 層上限 200 UTF-16 code units；不進 auditMap）。
                breaker.onNeutral()
                McpCallResult(
                    config.id,
                    toolName,
                    McpStatus.TOOL_ERROR,
                    clockMs() - start,
                    null,
                    true,
                    errorMessage = e.message?.let { McpWire.truncateUnits(it, 200) },
                )
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
        } catch (_: SocketTimeoutException) {
            // 讀/連線超時（非本層 deadline）：與本層 deadline 同待遇 → TIMEOUT + 計熔斷。
            breaker.onFailure()
            return McpCallResult(config.id, toolName, McpStatus.TIMEOUT, clockMs() - start)
        } catch (_: Exception) {
            breaker.onFailure()
            return McpCallResult(config.id, toolName, McpStatus.TRANSPORT, clockMs() - start)
        }
    }
}

/**
 * Bearer token 可列印 ASCII 預檢（fail-closed）：值須非空、長度不超過 [BEARER_MAX_CHARS]，
 * 且每個字元皆在 0x20–0x7E。
 *
 * 拒絕：空字串、控制字元（含 CR/LF——header injection 向量）、非 ASCII（含孤立
 * surrogate，UTF-8 編碼會變 U+FFFD 使 token 失真）、超長（損壞 keystore 回傳巨量值的
 * 縱深上限，避免數 MB token 直接流進標頭與 String 複製記憶體放大）。合法 Bearer token
 * （RFC 6750 b64token／Bearer 65–90 基本拉丁字元）必在此範圍內；範圍外者多為 keystore
 * 損壞或注入企圖，一律不發送。
 */
internal fun isPrintableAscii(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value.length > BEARER_MAX_CHARS) return false
    return value.all { it.code in 0x20..0x7E }
}

/** Bearer token 預檢長度上限（縱深；遠大於任何合法 token，僅擋損壞 keystore 的巨量值）。 */
private const val BEARER_MAX_CHARS = 8_192

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
