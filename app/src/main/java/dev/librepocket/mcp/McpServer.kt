package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.TimeoutCancellationException

/**
 * D03 單伺服器客戶端（多伺服器 = 每個配置一個 [McpServer] 實例）。
 *
 * - 地址使用者配置（[McpServerConfig.baseUrl]），Key 走 [KeyVault] 加密存取；
 * - 方法：[listTools]（tools/list）+ [callTool]（tools/call）；
 * - 熔斷：連續失敗即開路（[McpCircuitBreaker]），開路期間短路為 CIRCUIT_OPEN；
 * - 超時：單次 15–30s（[McpTimeouts.clamp]），超時映射 TIMEOUT，不重試；
 * - 隱私：Bearer 只放標頭；key 用後即擦；稽核只記 id/status/latency。
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
        val key = keyVault.getKey(config.keyId)
        if (key == null || config.baseUrl.isBlank()) {
            key?.fill('\u0000')
            // 無 key/無地址不計熔斷（配置問題，非傳輸失敗），直接回 UNAUTHORIZED/DISABLED 語義。
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
        } catch (e: TimeoutCancellationException) {
            breaker.onFailure()
            throw e
        } catch (_: Exception) {
            breaker.onFailure()
            return McpListResult(config.id, emptyList(), McpStatus.TRANSPORT, clockMs() - start)
        }
    }

    /**
     * tools/call。
     *
     * @param argumentsJson 已過 Schema 校驗的參數 JSON object 文本；只駐記憶體，
     *   不寫盤、不進稽核（稽核見 [McpCallResult.auditMap]）。
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
        val key = keyVault.getKey(config.keyId)
        if (key == null || config.baseUrl.isBlank()) {
            key?.fill('\u0000')
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
            val body = McpWire.callBody(toolName, argumentsJson)
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
            if (code !in 200..299 || text.isNullOrBlank()) {
                breaker.onFailure()
                return McpCallResult(config.id, toolName, McpStatus.TRANSPORT, clockMs() - start)
            }
            return try {
                val (resultJson, isError) = McpWire.parseCall(text)
                // 傳輸成功：不斷熔斷計數（工具業務錯 isError 不計熔斷，只透狀態）。
                breaker.onSuccess()
                val status = if (isError) McpStatus.TOOL_ERROR else McpStatus.OK
                McpCallResult(config.id, toolName, status, clockMs() - start, resultJson, isError)
            } catch (e: McpWire.McpToolFailure) {
                // 伺服器回 error 包：工具級錯誤，不計熔斷（會話不中斷）。
                breaker.onSuccess()
                McpCallResult(config.id, toolName, McpStatus.TOOL_ERROR, clockMs() - start, null, true)
            } catch (_: IllegalArgumentException) {
                breaker.onFailure()
                McpCallResult(config.id, toolName, McpStatus.PROTOCOL, clockMs() - start)
            }
        } catch (e: TimeoutCancellationException) {
            breaker.onFailure()
            throw e
        } catch (_: Exception) {
            breaker.onFailure()
            return McpCallResult(config.id, toolName, McpStatus.TRANSPORT, clockMs() - start)
        }
    }
}

/** 多伺服器聚合：薄封裝，逐服獨立熔斷（同進程多 [McpServer] 實例）。 */
class McpRegistry(
    private val servers: Map<String, McpServer>,
) {
    fun ids(): Set<String> = servers.keys

    fun get(serverId: String): McpServer? = servers[serverId]

    suspend fun listAll(timeoutMs: Long = McpTimeouts.DEFAULT_MS): Map<String, McpListResult> =
        servers.mapValues { (_, s) -> s.listTools(timeoutMs) }
}
