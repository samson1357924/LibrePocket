package dev.librepocket.files

import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.Validation
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.S1bFallback
import java.io.FileNotFoundException

/**
 * S1-A `file.search`（READ，開關 `files`，矩陣「檔案」行）。
 *
 * 範圍：私有域全文檢索 + 已授權 SAF 樹內檢索。
 * 路徑門：絕對路徑 roots 一律先過 [ShellPolicy.validate]
 * （`grep -r <root>` 形狀：白名單/黑名單/特殊字元/檔案域同一道門；
 * query 字串不進 argv——內容掃描在行程內完成，不建子進程，
 * 故不受 `mkfs` 類全文黑名單片段誤傷）。
 * 跨域映射：BLACKLIST（跨域）→ play 記 FLAVOR_BLOCKED，
 * foss/github 記 NO_PRIVILEGE（與 [FileEditTools] 同口徑）。
 *
 * 本檔零 Android 依賴。
 */
object FileSearchTools {

    const val SEARCH_NAME = "file.search"
    const val SWITCH = FileEditTools.SWITCH
    const val SWITCH_DEFAULT = FileEditTools.SWITCH_DEFAULT

    const val FALLBACK_HINT = "search manually in the system Files app"

    /** 單檔掃描上限：更大/二進位檔跳過並計入 [SearchResult.filesSkipped]。 */
    const val MAX_FILE_BYTES = 64 * 1024

    /** 命中上限硬頂（呼叫方要求再大也鉗制於此）。 */
    const val MAX_HITS = 200

    const val SNIPPET_CHARS = 160

    data class SearchHit(
        /** 私有域為相對路徑；SAF 為絕對路徑。 */
        val path: String,
        /** 命中行號（1-based）；檔名命中時為 null。 */
        val lineNumber: Int?,
        val snippet: String,
    )

    data class SearchResult(
        val ok: Boolean,
        val hits: List<SearchHit> = emptyList(),
        val filesSearched: Int = 0,
        val filesSkipped: Int = 0,
        val truncated: Boolean = false,
        val reason: DenyReason? = null,
        val detail: String? = null,
        val message: String = "",
    )

    sealed interface Root {
        data class Private(val prefix: String) : Root
        data class Saf(val prefix: String) : Root
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : Root
        data class Invalid(val detail: String, val message: String) : Root
    }

    /** query 衛生：行程內字面掃描，只擋空查詢與 NUL/換行（破壞行號語義）。 */
    fun validateQuery(query: String): String? {
        if (query.isEmpty()) return "查詢不可為空"
        if (query.contains('\u0000')) return "查詢不可包含 NUL"
        if (query.contains('\n') || query.contains('\r')) return "查詢不可包含換行（僅支援單行字面比對）"
        return null
    }

    /**
     * 檢索根裁決（純函數）：空/相對根視為私有域前綴；絕對根走
     * [ShellPolicy.validate] 路徑門後再按域分流。
     */
    fun resolveRoot(
        root: String,
        privateRoot: String,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        enabled: Boolean = true,
    ): Root {
        if (!enabled) {
            return Root.Denied(
                DenyReason.USER_DISABLED,
                "SWITCH_OFF",
                S1bFallback.message(
                    what = "搜尋檔案（SWITCH_OFF）",
                    reason = DenyReason.USER_DISABLED,
                    detail = "SWITCH_OFF",
                    alternative = FALLBACK_HINT,
                    needFromUser = "到設定開啟「檔案」開關後重試",
                ),
            )
        }
        if (root.isEmpty()) return Root.Private("")
        if (root.contains('\u0000')) {
            return Root.Invalid("BAD_PATH", "檢索根非法：不可包含 NUL")
        }
        if (!root.startsWith("/")) {
            val safe = FileScope.sanitizeRelative(root)
                ?: return Root.Invalid("BAD_PATH", "檢索根非法或穿越私有域：「$root」")
            return Root.Private(safe)
        }
        val norm = FileScope.normalize(root)
        when (val v = ShellPolicy.validate(listOf("grep", "-r", norm), privateRoot, safRoots, flavor, bridgeGranted)) {
            is Validation.Denied -> return mapShellDenial(v, norm, flavor)
            is Validation.Allowed -> Unit
        }
        val decision = FileScope.decide(norm, privateRoot, safRoots, flavor, bridgeGranted)
        if (!decision.allowed || decision.needsBridge) {
            val reason =
                if (flavor == Flavor.PLAY) DenyReason.FLAVOR_BLOCKED else DenyReason.NO_PRIVILEGE
            val detail = decision.code ?: FileScope.CODE_CROSS_DOMAIN
            return Root.Denied(
                reason,
                detail,
                S1bFallback.message(
                    what = "搜尋「$norm」（$detail）",
                    reason = reason,
                    detail = detail,
                    alternative = FALLBACK_HINT,
                    needFromUser = "改搜 App 私有域，或先經 SAF 授權該目錄",
                ),
            )
        }
        return when (decision.zone) {
            FileZone.PRIVATE -> {
                val priv = FileScope.normalize(privateRoot)
                Root.Private(if (norm == priv) "" else norm.removePrefix("$priv/"))
            }
            FileZone.SAF_GRANTED -> Root.Saf(norm)
            FileZone.CROSS_DOMAIN -> Root.Denied(
                DenyReason.FLAVOR_BLOCKED,
                FileScope.CODE_CROSS_DOMAIN,
                S1bFallback.message(
                    what = "搜尋「$norm」（${FileScope.CODE_CROSS_DOMAIN}）",
                    reason = DenyReason.FLAVOR_BLOCKED,
                    detail = FileScope.CODE_CROSS_DOMAIN,
                    alternative = FALLBACK_HINT,
                    needFromUser = "改搜 App 私有域，或先經 SAF 授權該目錄",
                ),
            )
        }
    }

    private fun mapShellDenial(v: Validation.Denied, norm: String, flavor: Flavor): Root {
        return when (v.reason) {
            ShellDeny.BLACKLISTED -> {
                // 跨域/黑名單：風味口徑與 file.edit 對齊。
                val reason =
                    if (flavor == Flavor.PLAY) DenyReason.FLAVOR_BLOCKED else DenyReason.NO_PRIVILEGE
                Root.Denied(
                    reason,
                    FileScope.CODE_CROSS_DOMAIN,
                    S1bFallback.message(
                        what = "搜尋「$norm」（${FileScope.CODE_CROSS_DOMAIN}）",
                        reason = reason,
                        detail = FileScope.CODE_CROSS_DOMAIN,
                        alternative = FALLBACK_HINT,
                        needFromUser = "改搜 App 私有域，或先經 SAF 授權該目錄",
                    ),
                )
            }
            else ->
                Root.Invalid(
                    v.reason.name,
                    "檢索根被拒（${v.reason}）：${v.message}",
                )
        }
    }
}

/**
 * `file.search` 執行器（S1-A 骨架）。
 *
 * 檔名命中（路徑含 query）與內容命中（行含 query）都會回傳；
 * 檔名命中的 [FileSearchTools.SearchHit.lineNumber] 為 null。
 */
class FileSearchExecutor(
    private val store: ScopedFileStore,
    private val privateRoot: String,
    private val safRoots: List<String> = emptyList(),
    private val flavor: Flavor = Flavor.PLAY,
    private val bridgeGranted: Boolean = false,
    private val safBridge: SafFileBridge = MissingSafBridge(),
    private val filesEnabled: Boolean = true,
    private val maxFileBytes: Int = FileSearchTools.MAX_FILE_BYTES,
    private val snippetChars: Int = FileSearchTools.SNIPPET_CHARS,
) {
    fun search(query: String, root: String = "", maxHits: Int = 50): FileSearchTools.SearchResult {
        FileSearchTools.validateQuery(query)?.let {
            return FileSearchTools.SearchResult(false, detail = "BAD_QUERY", message = it)
        }
        val cap = maxHits.coerceIn(1, FileSearchTools.MAX_HITS)
        return when (
            val r = FileSearchTools.resolveRoot(root, privateRoot, safRoots, flavor, bridgeGranted, filesEnabled)
        ) {
            is FileSearchTools.Root.Denied ->
                FileSearchTools.SearchResult(false, reason = r.reason, detail = r.detail, message = r.message)
            is FileSearchTools.Root.Invalid ->
                FileSearchTools.SearchResult(false, detail = r.detail, message = r.message)
            is FileSearchTools.Root.Private -> searchPrivate(query, r.prefix, cap)
            is FileSearchTools.Root.Saf -> searchSaf(query, r.prefix, cap)
        }
    }

    private fun searchPrivate(query: String, prefix: String, cap: Int): FileSearchTools.SearchResult {
        val paths = try {
            store.list(prefix)
        } catch (e: FileBudgetException) {
            // 列舉預算耗盡：加法細碼回傳（呼叫形狀不變），不靜默給部分結果。
            return FileSearchTools.SearchResult(
                false,
                detail = e.budget.detail,
                message = "列舉受限（${e.budget.detail}）：${e.message}",
            )
        } catch (_: IllegalArgumentException) {
            return FileSearchTools.SearchResult(false, detail = "BAD_PATH", message = "檢索根非法：「$prefix」")
        }
        return scan(
            query = query,
            cap = cap,
            paths = paths,
            read = { rel ->
                try {
                    store.read(rel)
                } catch (_: FileNotFoundException) {
                    null
                } catch (_: FileBudgetException) {
                    // 單檔超讀預算：沿既有「過大跳過」語義計入 filesSkipped。
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            },
        )
    }

    private fun searchSaf(query: String, prefix: String, cap: Int): FileSearchTools.SearchResult {
        val paths = try {
            safBridge.list(prefix)
        } catch (e: Exception) {
            return FileSearchTools.SearchResult(
                false,
                detail = "SAF_FAILED",
                message = "列出已授權目錄失敗「$prefix」：${e.message}",
            )
        }
        return scan(
            query = query,
            cap = cap,
            paths = paths,
            read = { abs ->
                try {
                    safBridge.read(abs)
                } catch (_: FileNotFoundException) {
                    null
                }
            },
        )
    }

    private fun scan(
        query: String,
        cap: Int,
        paths: List<String>,
        read: (String) -> ByteArray?,
    ): FileSearchTools.SearchResult {
        val hits = mutableListOf<FileSearchTools.SearchHit>()
        var searched = 0
        var skipped = 0
        var truncated = false
        for (path in paths) {
            if (hits.size >= cap) {
                truncated = true
                break
            }
            if (query in path) {
                hits.add(FileSearchTools.SearchHit(path, null, path.take(snippetChars)))
                if (hits.size >= cap) {
                    truncated = true
                    break
                }
            }
            val bytes = read(path) ?: run {
                skipped++
                continue
            }
            if (bytes.size > maxFileBytes || bytes.any { it == 0.toByte() }) {
                skipped++
                continue
            }
            searched++
            val text = String(bytes, Charsets.UTF_8)
            for ((index, line) in text.split("\n").withIndex()) {
                if (query in line) {
                    hits.add(
                        FileSearchTools.SearchHit(
                            path = path,
                            lineNumber = index + 1,
                            snippet = line.trim().take(snippetChars),
                        ),
                    )
                    if (hits.size >= cap) {
                        truncated = true
                        break
                    }
                }
            }
        }
        return FileSearchTools.SearchResult(
            ok = true,
            hits = hits,
            filesSearched = searched,
            filesSkipped = skipped,
            truncated = truncated,
            message = if (hits.isEmpty()) "找不到「$query」" else "找到 ${hits.size} 處",
        )
    }
}
