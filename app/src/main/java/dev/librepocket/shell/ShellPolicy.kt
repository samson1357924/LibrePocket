package dev.librepocket.shell

import dev.librepocket.files.FileScope
import dev.librepocket.tool.Flavor

/**
 * 受限 shell 策略（BACKLOG D05，矩陣「終端命令」行）。
 *
 * - 預設無提權：只放行唯讀性質的白名單二進位，不經 shell 解析
 *  （argv 直接交 [ProcessRunner]，不拼字串、不走 `sh -c`）。
 * - 黑名單先於白名單判定：`rm -rf /` 類必須回 [ShellDeny.BLACKLISTED]，
 *   而不是普通的「不在白名單」。
 * - 本檔案零 Android 依賴，JVM 單測可直接斷言 [validate]。
 */
object ShellPolicy {

    /** 允許執行的二進位（basename 比對，均為無副作用的查詢/列目類）。 */
    val ALLOWED_BINARIES: Set<String> = setOf(
        "ls",
        "echo",
        "pwd",
        "cat",
        "head",
        "tail",
        "wc",
        "grep",
        "find",
        "stat",
        "du",
        "df",
        "uname",
        "id",
        "date",
        "whoami",
        "printenv",
        "sleep",
        "getprop",
    )

    /** 無論參數為何一律拒絕的二進位（破壞性 / 提權 / 可繞白名單的直譯器）。 */
    val DENIED_BINARIES: Set<String> = setOf(
        "rm",
        "dd",
        "mkfs",
        "mke2fs",
        "reboot",
        "shutdown",
        "poweroff",
        "halt",
        "su",
        "sudo",
        "sh",
        "bash",
        "dash",
    )

    /** 單個參數內不允許出現的字元：阻斷串接、重定向、子命令展開。 */
    private val DENIED_ARG_CHARS: Set<Char> = setOf(';', '|', '&', '$', '`', '>', '<', '\n', '\r')

    /**
     * find 高危謂詞：命中任一即拒絕（沙箱逃逸封堵）。
     *
     * - `-exec` / `-execdir` / `-ok` / `-okdir` 藉 find 執行任意二進位
     *  （`find / -exec rm {} +` 類），白名單形同虛設。
     * - `-delete` 直接刪除任意路徑；`-fls` / `-fprint` / `-fprintf`
     *   任意寫檔。同族一併封堵。
     * - 精確匹配即可（find 謂詞大小寫敏感）；argv[0] 不計入。
     */
    private val FIND_DENIED_PREDICATES: Set<String> = setOf(
        "-exec",
        "-execdir",
        "-ok",
        "-okdir",
        "-delete",
        "-fls",
        "-fprint",
        "-fprintf",
    )

    /** 連接後全文掃描的黑名單片段（大小寫敏感即可，攻擊字面本就固定）。 */
    private val DENIED_TEXT_SNIPPETS: List<String> = listOf(
        "rm -rf /",
        "rm -fr /",
        "rm --recursive --force /",
        ":(){",
        "mkfs",
        "/dev/kmem",
        "/dev/mem",
    )

    /** 單流（stdout/stderr 各自）輸出上限，超出即截斷並標記。 */
    const val MAX_OUTPUT_BYTES: Int = 64 * 1024

    /** 進程啟動後硬上限讀取量（防 OOM；截斷標記仍以 [MAX_OUTPUT_BYTES] 為準）。 */
    const val HARD_READ_CAP_BYTES: Int = 512 * 1024

    /** 預設單次執行超時。 */
    const val DEFAULT_TIMEOUT_MS: Long = 10_000L

    /** 預設配額：每分鐘最多執行次數。 */
    const val DEFAULT_MAX_CALLS: Int = 30
    const val DEFAULT_WINDOW_MS: Long = 60_000L

    /** 取 argv[0] 的 basename（相容 `/bin/ls` 這類絕對路徑寫法）。 */
    fun basename(argv0: String): String =
        argv0.substringAfterLast('/').substringAfterLast('\\')

    /**
     * 純函數校驗：依次判空 → 黑名單（二進位/全文片段/特殊字元）→ 白名單
     * → find 高危謂詞 → 檔案域（絕對路徑經 [FileScope.decide]）。
     * 呼叫方（[RestrictedShell]）必須先調此函數，拒絕時不得建子進程。
     *
     * @param allowedBinaries 白名單集合（預設 [ALLOWED_BINARIES]；S4
     *   `linux.exec` 傳聯集，黑名單/參數衛生/find 封堵/檔案域邏輯完全繼承，
     *   僅白名單放寬）。
     * @param privateRoot App 私有域根；null 表示未配置作用域，
     *   此時任何絕對路徑參數一律拒絕（fail-closed）。
     * @param safRoots 已授權 SAF 樹前綴。
     * @param flavor 風味：play 跨域一律拒絕；foss/github 跨域即使橋接已授權，
     *   直接 exec 仍拒絕（需改走 D09 橋，[FileScope.decide] 回 needsBridge）。
     */
    fun validate(
        argv: List<String>,
        privateRoot: String? = null,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        allowedBinaries: Set<String> = ALLOWED_BINARIES,
    ): Validation {
        if (argv.isEmpty() || argv.all { it.isBlank() }) {
            return Validation.Denied(ShellDeny.EMPTY_COMMAND, "empty command")
        }
        val base = basename(argv[0].trim())
        if (base.isEmpty()) {
            return Validation.Denied(ShellDeny.EMPTY_COMMAND, "empty command")
        }
        if (base in DENIED_BINARIES) {
            return Validation.Denied(ShellDeny.BLACKLISTED, "binary denied: $base")
        }
        val joined = argv.joinToString(" ")
        for (snippet in DENIED_TEXT_SNIPPETS) {
            if (joined.contains(snippet)) {
                return Validation.Denied(ShellDeny.BLACKLISTED, "blocked pattern: $snippet")
            }
        }
        for (arg in argv) {
            for (ch in arg) {
                if (ch in DENIED_ARG_CHARS) {
                    return Validation.Denied(
                        ShellDeny.BAD_ARGUMENT,
                        "metachar denied: ${ch.code}",
                    )
                }
            }
            if (arg.contains('\u0000')) {
                return Validation.Denied(ShellDeny.BAD_ARGUMENT, "NUL byte denied")
            }
        }
        if (base !in allowedBinaries) {
            return Validation.Denied(ShellDeny.NOT_WHITELISTED, "not whitelisted: $base")
        }
        // find 沙箱逃逸封堵：高危謂詞命中任一即拒絕（不建子進程）。
        if (base == "find") {
            for (arg in argv.drop(1)) {
                if (arg in FIND_DENIED_PREDICATES) {
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "find predicate denied: $arg",
                    )
                }
            }
        }
        // 白名單二進位讀任意路徑封堵：檔案參數凡絕對路徑先過 FileScope.decide。
        // argv[0] 是二進位本身（/bin/ls 類豁免），只查 drop(1)。
        // P0 fail-closed：相對路徑（`../`、`..`、含 `/` 者）不進 FileScope
        // 即一律拒，呼叫方需先轉絕對路徑（見 isRelativePathArg）。
        for (arg in argv.drop(1)) {
            for (candidate in absoluteCandidates(arg)) {
                val denial = checkFileScope(
                    candidate,
                    privateRoot,
                    safRoots,
                    flavor,
                    bridgeGranted,
                )
                if (denial != null) return denial
            }
            if (isRelativePathArg(arg)) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
        }
        return Validation.Allowed(base)
    }

    /**
     * 提權通道校驗（S3，BACKLOG D09，矩陣 §3）。
     *
     * 與 [validate] 的差異只有兩處，其餘（空命令 → 黑名單二進位/全文片段 →
     * 參數衛生 → find 高危謂詞 → 檔案域）完全同序：
     * - 跳過白名單門禁：白名單外的二進位僅允許走提權通道（Shizuku/Root），
     *   直接 exec 仍拒（[validate] 維持原判）；
     * - 檔案域改走提權語義：私有域 / 已授權 SAF 直行（呼叫方應優先走
     *   SAF/私有域，見提權橋「SAF 優先」話術）；跨域且
     *   [FileScope.decide] 回 needsBridge（橋接已授權）才放行，其餘跨域仍拒。
     * 黑名單（`rm -rf /` 類）一律拒，不因提權放行。
     *
     * 預設 [flavor] 為 PLAY（fail-closed：未指明風味時跨域一律拒；
     * 自裝風味呼叫方必須顯式傳 FOSS/GITHUB + bridgeGranted）。
     */
    fun validateElevated(
        argv: List<String>,
        privateRoot: String? = null,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
    ): Validation {
        if (argv.isEmpty() || argv.all { it.isBlank() }) {
            return Validation.Denied(ShellDeny.EMPTY_COMMAND, "empty command")
        }
        val base = basename(argv[0].trim())
        if (base.isEmpty()) {
            return Validation.Denied(ShellDeny.EMPTY_COMMAND, "empty command")
        }
        if (base in DENIED_BINARIES) {
            return Validation.Denied(ShellDeny.BLACKLISTED, "binary denied: $base")
        }
        val joined = argv.joinToString(" ")
        for (snippet in DENIED_TEXT_SNIPPETS) {
            if (joined.contains(snippet)) {
                return Validation.Denied(ShellDeny.BLACKLISTED, "blocked pattern: $snippet")
            }
        }
        for (arg in argv) {
            for (ch in arg) {
                if (ch in DENIED_ARG_CHARS) {
                    return Validation.Denied(
                        ShellDeny.BAD_ARGUMENT,
                        "metachar denied: ${ch.code}",
                    )
                }
            }
            if (arg.contains('\u0000')) {
                return Validation.Denied(ShellDeny.BAD_ARGUMENT, "NUL byte denied")
            }
        }
        // find 沙箱逃逸封堵：提權通道同樣拒絕（與 validate 同策）。
        if (base == "find") {
            for (arg in argv.drop(1)) {
                if (arg in FIND_DENIED_PREDICATES) {
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "find predicate denied: $arg",
                    )
                }
            }
        }
        // 檔案域（提權語義）：needsBridge 且橋接已授權才放行跨域。
        // P0 fail-closed：相對路徑同直接通道一律拒（見 isRelativePathArg），
        // 提權 cwd 未釘死前不得放行任何相對路徑。
        for (arg in argv.drop(1)) {
            for (candidate in absoluteCandidates(arg)) {
                val denial = checkFileScopeElevated(
                    candidate,
                    privateRoot,
                    safRoots,
                    flavor,
                    bridgeGranted,
                )
                if (denial != null) return denial
            }
            if (isRelativePathArg(arg)) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
        }
        return Validation.Allowed(base)
    }

    /** 取參數中的絕對路徑候選：`/...` 或 `--opt=/...`（`=` 後綴）。 */
    private fun absoluteCandidates(arg: String): List<String> {
        if (arg.startsWith("/")) return listOf(arg)
        val eq = arg.indexOf('=')
        if (eq >= 0 && eq + 1 < arg.length && arg[eq + 1] == '/') {
            return listOf(arg.substring(eq + 1))
        }
        return emptyList()
    }

    /**
     * 相對路徑值是否像路徑（P0 fail-closed，PR#1 review blocker）：
     * 含 `/` 或為 `.`/`..` 即視為路徑。
     */
    private fun isRelativePathValue(value: String): Boolean {
        if (value == "." || value == "..") return true
        if (value.contains('/')) return true
        return false
    }

    /**
     * argv 參數是否為相對路徑（P0 fail-closed）。
     * - 絕對路徑（`/...`、`--opt=/...`）由 [absoluteCandidates] 處理，
     *   此處回 false（其中 key 含 `/` 的畸形參數仍擋）；
     * - `-flag` 純旗標：不含 `/` 即放行，含 `/`（如 `-foo/bar`）一律擋
     *   （合法 flag 不含斜線）；
     * - 非旗標 bare word 沒有 `/` 且不是 `.`/`..` 視為 pattern/檔名不擋
     *  （避免 `grep -r password` 誤殺）；其餘含 `/` 或為 `.`/`..` 一律擋，
     *   呼叫方需先轉絕對路徑再送 FileScope 裁決。
     */
    private fun isRelativePathArg(arg: String): Boolean {
        if (arg.startsWith("-")) {
            val eq = arg.indexOf('=')
            if (eq < 0) return arg.contains('/')
            val key = arg.substring(0, eq)
            val v = arg.substring(eq + 1)
            if (key.contains('/')) return true
            if (v.startsWith("/")) return false
            return isRelativePathValue(v)
        }
        if (arg.startsWith("/")) return false
        return isRelativePathValue(arg)
    }

    /** 絕對路徑經 [FileScope.decide]；跨域 / 需橋接 / 無作用域一律拒絕。 */
    private fun checkFileScope(
        path: String,
        privateRoot: String?,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
    ): Validation.Denied? {
        if (privateRoot == null) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "absolute path denied without scope: $path",
            )
        }
        val decision = FileScope.decide(path, privateRoot, safRoots, flavor, bridgeGranted)
        if (!decision.allowed || decision.needsBridge) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "cross-domain denied: $path",
            )
        }
        return null
    }

    /**
     * 提權語義的檔案域檢查（S3，BACKLOG D09）。
     *
     * 先走 [FileScope.decide]：私有域 / 已授權 SAF 直行；跨域僅當回
     * needsBridge 且已授權（[FileScope.decide] 內已按風味與
     * bridgeGranted 裁決，`allowed == true` 即放行條件）才放行，
     * 其餘跨域仍拒。無作用域（privateRoot == null）fail-closed。
     */
    private fun checkFileScopeElevated(
        path: String,
        privateRoot: String?,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
    ): Validation.Denied? {
        if (privateRoot == null) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "absolute path denied without scope: $path",
            )
        }
        val decision = FileScope.decide(path, privateRoot, safRoots, flavor, bridgeGranted)
        if (decision.allowed) return null
        return Validation.Denied(
            ShellDeny.BLACKLISTED,
            "cross-domain denied: $path",
        )
    }

    /** 對輸出做位元組級截斷（UTF-8 邊界安全由呼叫方解碼時處理）。 */
    fun truncate(bytes: ByteArray, cap: Int = MAX_OUTPUT_BYTES): Truncated {
        if (bytes.size <= cap) return Truncated(bytes, false)
        return Truncated(bytes.copyOf(cap), true)
    }
}

/** 拒絕原因碼（對應 D05「白名單/黑名單/配額」三類門禁 + 參數衛生）。 */
enum class ShellDeny {
    EMPTY_COMMAND,
    NOT_WHITELISTED,
    BLACKLISTED,
    BAD_ARGUMENT,
    QUOTA_EXCEEDED,
}

/** [ShellPolicy.validate] 的回執：放行攜正規化後的二進位名，拒絕攜原因。 */
sealed interface Validation {
    data class Allowed(val binary: String) : Validation
    data class Denied(val reason: ShellDeny, val message: String) : Validation
}

/** 截斷結果：[truncated] 為 true 表示原文更長，已丟棄尾部。 */
data class Truncated(val bytes: ByteArray, val truncated: Boolean)
