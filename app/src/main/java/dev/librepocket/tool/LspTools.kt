package dev.librepocket.tool

/**
 * S1-B `lsp.symbols` + `lsp.diagnostics` (READ)：程式碼符號與診斷查詢。
 *
 * - 先經本地樁（[StubBackend]，確定性假資料，零外部依賴）；
 * - 可接 MCP：呼叫方傳入 MCP 橋接的 [Backend] 實作（約定橋接工具名形如
 *   `mcp.<serverId>.lsp`，由上層把 MCP 伺服器回應轉為 [Symbol]/[Diagnostic]），
 *   未傳入時一律走本地樁；
 * - 開關 `lsp` 預設 false（投影未開啟時為 UNAVAILABLE + USER_DISABLED）。
 *
 * 純 JVM（無 Android API），可在單元測試直接執行。
 */
object LspTools {

    const val SYMBOLS_NAME = "lsp.symbols"
    const val DIAGNOSTICS_NAME = "lsp.diagnostics"
    const val SWITCH = "lsp"
    const val SWITCH_DEFAULT = false

    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    const val FALLBACK_HINT = "use the IDE's own symbol search or problems view"

    data class Symbol(val name: String, val kind: String, val path: String, val line: Int)

    data class Diagnostic(val path: String, val line: Int, val severity: String, val message: String)

    /**
     * 後端縫：[StubBackend] 為預設本地樁；MCP 橋接實作此介面即可替換
     * （僅替換資料來源，不改變開關/投影語義）。
     */
    interface Backend {
        fun symbols(query: String, pathPrefix: String?, limit: Int): List<Symbol>
        fun diagnostics(path: String): List<Diagnostic>
    }

    /** 本地樁：確定性輸出（同輸入恆得同輸出），無網路、無外部進程。 */
    object StubBackend : Backend {
        override fun symbols(query: String, pathPrefix: String?, limit: Int): List<Symbol> {
            if (query.isBlank()) return emptyList()
            val base = pathPrefix?.trimEnd('/')?.ifBlank { null } ?: "app/src/main"
            return listOf(Symbol("$query:example", "function", "$base/Example.kt", 1))
                .take(limit.coerceIn(1, MAX_LIMIT))
        }

        override fun diagnostics(path: String): List<Diagnostic> = emptyList()
    }

    sealed interface SymbolsOutcome {
        data class Ok(val symbols: List<Symbol>, val fromStub: Boolean) : SymbolsOutcome
        data class Unavailable(val reason: DenyReason, val detail: String, val message: String) : SymbolsOutcome
    }

    sealed interface DiagnosticsOutcome {
        data class Ok(val diagnostics: List<Diagnostic>, val fromStub: Boolean) : DiagnosticsOutcome
        data class Unavailable(val reason: DenyReason, val detail: String, val message: String) : DiagnosticsOutcome
    }

    fun symbols(
        query: String,
        pathPrefix: String? = null,
        limit: Int = DEFAULT_LIMIT,
        backend: Backend = StubBackend,
    ): SymbolsOutcome {
        if (query.isBlank()) {
            return SymbolsOutcome.Unavailable(
                reason = DenyReason.NO_PRIVILEGE,
                detail = "EMPTY_QUERY",
                message = S1bFallback.message(
                    what = "查符號（EMPTY_QUERY）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = "EMPTY_QUERY",
                    alternative = FALLBACK_HINT,
                    needFromUser = "提供要查的符號名",
                ),
            )
        }
        return SymbolsOutcome.Ok(
            symbols = backend.symbols(query, pathPrefix, limit.coerceIn(1, MAX_LIMIT)),
            fromStub = backend === StubBackend,
        )
    }

    fun diagnostics(
        path: String,
        backend: Backend = StubBackend,
    ): DiagnosticsOutcome {
        if (path.isBlank()) {
            return DiagnosticsOutcome.Unavailable(
                reason = DenyReason.NO_PRIVILEGE,
                detail = "EMPTY_PATH",
                message = S1bFallback.message(
                    what = "查診斷（EMPTY_PATH）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = "EMPTY_PATH",
                    alternative = FALLBACK_HINT,
                    needFromUser = "提供要查的檔案路徑",
                ),
            )
        }
        return DiagnosticsOutcome.Ok(
            diagnostics = backend.diagnostics(path),
            fromStub = backend === StubBackend,
        )
    }
}
