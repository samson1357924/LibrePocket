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
     * 會把 positional 參數當檔案路徑的二進位（P0 binary-aware，PR#1 re-review）。
     * 這些二進位的非旗標 bare word 即相對路徑，必須 fail-closed（要求絕對路徑）。
     */
    private val FILE_OPERAND_BINARIES: Set<String> = setOf(
        "ls",
        "cat",
        "head",
        "tail",
        "wc",
        "find",
        "stat",
        "du",
        "df",
        "grep",
    )

    /**
     * 明確不把 bare word 當路徑的二進位（無檔案操作數）：
     * echo 印字、pwd/uname/id/date/whoami/printenv 查系統、sleep 等時長、
     * getprop 取屬性名（含點但非路徑）。這些 bare 不進檔案域；
     * 但 pwd（及 ls/du/df/find）的零路徑隱式 cwd 語義另由尾守衛在
     * privateRoot==null 時 fail-closed（見 checkPathArgs）。
     */
    private val NO_FILE_BINARIES: Set<String> = setOf(
        "echo",
        "pwd",
        "uname",
        "id",
        "date",
        "whoami",
        "printenv",
        "sleep",
        "getprop",
    )

    /**
     * 取值旗標（下一個 positional 是值而非路徑，P0 誤殺修正）：
     * head/tail 計數、stat 格式、find 過濾值、grep 顯式 pattern、du 深度。
     * `-f/--file`（grep 檔名）刻意不在此列：其值是路徑，必須走絕對路徑審查。
     */
    private val VALUE_TAKING_FLAGS: Set<String> = setOf(
        "-n", "--lines", "--bytes",
        "-c", "--format", "--printf",
        "-e", "--regexp",
        "-d", "--max-depth",
        "-name", "-iname", "-wholename", "-iwholename",
        "-type", "-maxdepth", "-mindepth", "-mmin", "-mtime",
        "-size", "-perm", "-user", "-group",
    )

    /**
     * 非路徑 `--opt` 鍵（值為列舉/格式/glob/數字，非檔案路徑）：
     * `--color/--sort/--format` 等放行，`--db/--file/--output` 等仍按路徑擋。
     */
    private val NON_PATH_OPT_KEYS: Set<String> = setOf(
        "--color", "--sort", "--format", "--printf", "--block-size",
        "--time-style", "--lines", "--bytes", "--max-count", "--max-depth",
        "--regexp", "--include", "--exclude", "--exclude-dir",
    )

    /**
     * 會隱式讀取 cwd 的二進位（PR#1 re-review head 4e3a6c6 blocker 1）：
     * 無明確路徑參數時語義即操作 `.`（ls/du 無 operand 列 `.`、
     * find 無 path 預設 `.`、df 無 operand 洩露掛載表、pwd 直接洩露 cwd）。
     * privateRoot==null 且無明確絕對路徑時必須 fail-closed（見 checkPathArgs 尾守衛）。
     */
    private val IMPLICIT_CWD_BINARIES: Set<String> = setOf(
        "ls",
        "du",
        "df",
        "find",
        "pwd",
    )

    /** grep 遞迴旗標：有此旗標且無檔案參數時語義為遞迴 `.`。 */
    private fun isGrepRecursiveFlag(arg: String): Boolean =
        arg == "-r" || arg == "-R" || arg == "--recursive" ||
            (arg.startsWith("-") && !arg.startsWith("--") && 'r' in arg.drop(1) && !arg.contains('='))

    /** 純值形態保留（文件用）：數字/布林/stdin/格式/glob 僅在值槽
     *（VALUE_TAKING_FLAGS 下一位 / grep pattern 槽 / NON_PATH_OPT_KEYS）
     * 才豁免；FILE_OPERAND 普通 positional 不得以形態豁免（見上）。 */
    @Suppress("unused")
    private fun isBenignValue(v: String): Boolean {
        if (v == "-") return true
        if (v.equals("true", ignoreCase = true) || v.equals("false", ignoreCase = true)) return true
        if (v.matches(Regex("^[+-]?[0-9]+$"))) return true
        if (v.startsWith("%")) return true
        if (v.contains('*') || v.contains('?') || v.contains('[')) return true
        return false
    }

    /**
     * 純函數校驗：依次判空 → 黑名單（二進位/全文片段/特殊字元）→ 白名單
     * → argv[0] 可信路徑門（同名不同路徑封堵，詞法；僅直接通道，
     *   guest 跳過——guest argv[0] 是容器命名空間路徑）→ find 高危謂詞
     * → 檔案域（絕對路徑經 [FileScope.decide]）。
     * 呼叫方（[RestrictedShell]）必須先調此函數，拒絕時不得建子進程；
     * 放行後仍須經 [ShellExecutables.resolve] 把 `argv[0]` 換成驗證後絕對路徑
     * 再 spawn（本函數不管落地實體，純詞法）。
     *
     * @param allowedBinaries 白名單集合（預設 [ALLOWED_BINARIES]；S4
     *   `linux.exec` 傳聯集，黑名單/參數衛生/find 封堵/檔案域邏輯完全繼承，
     *   僅白名單放寬）。
     * @param isGuest Guest 容器通道：true 時跳過 argv[0] 可信路徑門。
     *   Guest argv[0] 是容器命名空間路徑（`proot -r rootfs` 下解析），
     *   宿主可信目錄表（[ShellExecutables.TRUSTED_BIN_DIRS]）不適用
     *   （如容器內 `/opt/jadx/bin/jadx`）；host-絕對路徑混淆另由執行入口
     *   （`ProotExec` 經 `LinuxInboxStager.hostLinuxAbsoluteRef` /
     *   `unstagedInboxRef`）在 validate 之前 fail-closed，本函數不代勞。
     * @param privateRoot App 私有域根；null 表示未配置作用域，
     *   此時任何絕對路徑參數一律拒絕，且隱式讀 cwd 的命令（ls/du/df/find/pwd
     *   無明確路徑時，grep -r 無檔案參數時）亦一律拒絕（fail-closed）。
     * @param safRoots 已授權 SAF 樹前綴。
     * @param flavor 風味：play 跨域一律拒絕；foss/github 跨域即使橋接已授權，
     *   直接 exec 仍拒絕（需改走 D09 橋，[FileScope.decide] 回 needsBridge）。
     * @param trustedBinDirs `argv[0]` 詞法可信門的目錄表（預設
     *   [ShellExecutables.TRUSTED_BIN_DIRS]；產品碼一律用預設值，單測可注入
     *   暫存目錄以覆蓋絕對 `argv[0]` 的全鏈路徑，呼叫方須同步把同表傳給
     *   [ShellExecutables.resolve] 的 `searchDirs`）。
     */
    fun validate(
        argv: List<String>,
        privateRoot: String? = null,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        allowedBinaries: Set<String> = ALLOWED_BINARIES,
        isGuest: Boolean = false,
        trustedBinDirs: List<String> = ShellExecutables.TRUSTED_BIN_DIRS,
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
        // argv[0] 可信路徑門（直接通道 PATH 劫持封堵，詞法無 IO，host-only）：
        // 含 `/`/`\` 的寫法父目錄必須在可信系統目錄內，否則同名不同路徑的假
        // 二進位（`/tmp/evil/ls`）可憑 basename 通過白名單；bare 由執行層經
        // [ShellExecutables.resolve] 受控解析（不查 PATH、不看 cwd）。
        // Guest 通道（isGuest=true）跳過此門：guest argv[0] 是容器命名空間
        // 路徑（`proot -r rootfs` 下解析），宿主可信目錄表不適用
        // （如 `/opt/jadx/bin/jadx`、`/opt/toolchain/bin/clang`）；
        // host-絕對路徑混淆另由執行入口守衛 fail-closed，不在此判。
        // 提權通道（validateElevated）依本次範圍維持原判，不在此改。
        val rawArgv0 = argv[0].trim()
        if (!isGuest && (rawArgv0.contains('/') || rawArgv0.contains('\\'))) {
            if (!ShellExecutables.isTrustedAbsoluteArgv0(rawArgv0, trustedBinDirs)) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "untrusted executable path: $rawArgv0",
                )
            }
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
        // P0 re-review：FILE_OPERAND 二進位的 bare word（無 `/`）同樣是相對
        // 路徑（如 `cat init.rc`、`stat build.prop`、`ls chat`），一併拒；
        // grep 首個 positional bare 視為 pattern 豁免（除非 -e/-f 已給 pattern），
        // 取值旗標（-n/-c/-name 等）下一位視為值豁免，純值（數字/格式/glob）豁免；
        // S4 擴展白名單中新 binary（不在 FILE/NO_FILE）按 FILE fail-closed。
        val pathDenial = checkPathArgs(
            base = base,
            args = argv.drop(1),
            privateRoot = privateRoot,
            safRoots = safRoots,
            flavor = flavor,
            bridgeGranted = bridgeGranted,
            isElevated = false,
            allowedBinaries = allowedBinaries,
            isGuest = isGuest,
        )
        if (pathDenial != null) return pathDenial
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
        isGuest: Boolean = false,
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
        // 提權 cwd 已釘死到 privateRoot（見 RootSuRunner/ShizukuShellRunner），
        // 但政策層仍要求 FILE_OPERAND 用絕對路徑；未知二進位 bare 在
        // privateRoot==null 時一律拒（fail-closed），非 null 時靠 cwd 釘死保證域內。
        val pathDenialElevated = checkPathArgs(
            base = base,
            args = argv.drop(1),
            privateRoot = privateRoot,
            safRoots = safRoots,
            flavor = flavor,
            bridgeGranted = bridgeGranted,
            isElevated = true,
            allowedBinaries = ALLOWED_BINARIES,
            isGuest = isGuest,
        )
        if (pathDenialElevated != null) return pathDenialElevated
        return Validation.Allowed(base)
    }

    /**
     * 宿主外掛點前綴（guest 容器內路徑豁免的例外，縱深保留拒絕）：
     * 即使容器內存在同名路徑，`/sdcard` 等在宿主語義下是外部儲存，
     * 為避免語義混淆與 proot 呼叫形態漂移時的逃逸，guest 內仍一律拒絕。
     */
    private val GUEST_EXTERNAL_PREFIXES: Set<String> = setOf(
        "/sdcard",
        "/storage",
        "/mnt",
        "/external_sd",
        "/Removable",
        "/otg",
    )

    /**
     * Guest 容器內路徑判定（PR#1 re-review head 4e3a6c6 blocker 2）：
     * isGuest 且作用域已知時，宿主樹外的絕對路徑視為容器內路徑
     * （`proot -r rootfs` 將其約束在 rootfs 內，無 bind 即無法觸及宿主；
     * host inbox 前綴另由 [dev.librepocket.linux.LinuxInboxStager.unstagedInboxRef]
     * 在執行入口擋下）。
     * 宿主樹內路徑回 false（仍走 FileScope）；宿主外掛點回 false（縱深拒絕）。
     */
    private fun isContainerInternalPath(candidate: String, privateRoot: String): Boolean {
        val norm = FileScope.normalize(candidate)
        if (!norm.startsWith("/")) return false
        val priv = FileScope.normalize(privateRoot)
        if (norm == priv || norm.startsWith("$priv/")) return false
        for (prefix in GUEST_EXTERNAL_PREFIXES) {
            if (norm == prefix || norm.startsWith("$prefix/")) return false
        }
        return true
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
     * - 非旗標 bare word 含 `/` 或為 `.`/`..` 一律擋（見 [checkBareFileOperand]
     *   補 bare filename 封堵）；不含 `/` 的 bare 是否擋由 binary-aware 判定，
     *   避免 `grep -r password` 誤殺同時封住 `cat init.rc` 類繞過。
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

    /**
     * 共用路徑審查（P0 re-review fix→review 循環後 + PR#1 P0 value-shaped 封堵）：
     * 絕對路徑先過 FileScope → 相對含 `/` 擋 → grep `--` 終止符與 pattern 槽 →
     * 取值旗標值豁免 → binary-aware bare 封堵。
     * 直接/提權共用，避免雙寫漂移。
     *
     * 純值豁免僅限值槽（VALUE_TAKING_FLAGS 下一位 / grep pattern 槽 /
     * NON_PATH_OPT_KEYS 的 --opt 值），FILE_OPERAND 的普通 positional 一律
     * 當路徑（`cat 123` / `ls 123` / `stat %s` 亦拒）。Guest 模式（proot 容器
     * 內，cwd 已釘到 `-w /` + `-r rootfs`）的無 `/` bare 視為容器內子命令/
     * 相對路徑，放行；宿主樹外的 guest 絕對路徑（如 stage 後的 `/inbox/...`、
     * 容器內 `/etc`）同樣視為容器內路徑放行（[isContainerInternalPath]，
     * 宿主樹內仍走 FileScope，null 作用域一律拒，外掛點保留拒絕；
     * 含 `/` 相對仍擋）。
     */
    private fun checkPathArgs(
        base: String,
        args: List<String>,
        privateRoot: String?,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
        isElevated: Boolean,
        allowedBinaries: Set<String>,
        isGuest: Boolean = false,
    ): Validation.Denied? {
        // grep 顯式 pattern 旗標預掃：-e/--regexp/-f 存在時無 positional pattern 槽。
        var grepPatternSeen = (base != "grep")
        if (base == "grep") {
            var hasExplicit = false
            for (a in args) {
                if (a == "-e" || a == "--regexp" || a.startsWith("--regexp=") ||
                    a == "-f" || a == "--file" || a.startsWith("--file=") ||
                    (a.startsWith("-e") && a.length > 2 && !a.contains('/'))
                ) {
                    hasExplicit = true
                    break
                }
            }
            if (hasExplicit) grepPatternSeen = true
        }
        var endOfOptions = false
        var prevArg: String? = null
        // grep 遞迴旗標是否出現在真正的選項位（-- 之後 / 取值旗標值 / pattern 槽不算，
        // 如 `grep -- -r` 的 -r 是 pattern 而非遞迴）。
        var sawGrepRecursiveFlag = false
        for (arg in args) {
            // bind 封堵縱深（與 LinuxEnv.bindVeto 雙層）：guest 包裝層 argv 內
            // 出現 --bind 即否決，不因 guest 放行（黑名單不因通道放行）。
            if (arg == "--bind" || arg.startsWith("--bind=")) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "bind forbidden inside guest argv",
                )
            }
            for (candidate in absoluteCandidates(arg)) {
                // Guest 容器內路徑（PR#1 blocker 2）：isGuest 且作用域已知時，
                // 宿主樹外的絕對路徑是容器內路徑，直接放行（proot -r 約束；
                // host inbox 前綴另由執行入口擋，外掛點由下式保留拒絕）。
                if (isGuest && privateRoot != null && isContainerInternalPath(candidate, privateRoot)) {
                    continue
                }
                val denial = if (isElevated) {
                    checkFileScopeElevated(candidate, privateRoot, safRoots, flavor, bridgeGranted)
                } else {
                    checkFileScope(candidate, privateRoot, safRoots, flavor, bridgeGranted)
                }
                if (denial != null) return denial
            }
            if (!endOfOptions && arg == "--") {
                endOfOptions = true
                prevArg = arg
                continue
            }
            if (isRelativePathArg(arg)) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
            val isPositional = endOfOptions || !arg.startsWith("-") || arg == "-"
            // grep 遞迴旗標只認選項位的（見 sawGrepRecursiveFlag 註解）。
            if (base == "grep" && !endOfOptions && prevArg !in VALUE_TAKING_FLAGS &&
                isGrepRecursiveFlag(arg)
            ) {
                sawGrepRecursiveFlag = true
            }
            // 取值旗標的下一位是值而非路徑（如 -n 20、-c %s、-name *.db、-e pattern）。
            if (isPositional && prevArg != null && prevArg in VALUE_TAKING_FLAGS) {
                prevArg = arg
                // -f/--file 除外已不在 VALUE_TAKING_FLAGS，其值仍按路徑審查；
                // 此處僅豁免已列旗標的值。
                continue
            }
            // grep pattern 槽：首個 positional（-- 後含 `-` 開頭亦算）視為搜尋字串。
            if (base == "grep" && !grepPatternSeen && isPositional && !arg.startsWith("/")) {
                grepPatternSeen = true
                prevArg = arg
                continue
            }
            val bareDenial = checkBareFileOperand(
                base = base,
                arg = arg,
                privateRoot = privateRoot,
                isElevated = isElevated,
                allowedBinaries = allowedBinaries,
                isGuest = isGuest,
            )
            if (bareDenial != null) return bareDenial
            prevArg = arg
        }
        // Implicit-cwd fail-closed（PR#1 re-review head 4e3a6c6 blocker 1）：
        // ls/du/df/find/pwd 無明確絕對路徑時語義即隱式操作 cwd（ls/du 列 `.`、
        // find 預設 `.`、df 洩露掛載表、pwd 洩露 cwd 字串），grep -r 無檔案參數時
        // 遞迴 `.`。privateRoot==null 時 ambient cwd 不可控，必須 Denied
        // （guest 通道同理：生產 guest 恆帶非 null 私有域 + proot -r/-w 釘死，
        // 故此守衛不影響生產 guest，只封 guest+null 的 API 級旁路）。
        // privateRoot!=null 時 cwd 已釘死（RestrictedShell/Root/Shizuku），維持放行。
        if (privateRoot == null) {
            val hasExplicitAbsolute = args.any { absoluteCandidates(it).isNotEmpty() }
            if (!hasExplicitAbsolute) {
                if (base in IMPLICIT_CWD_BINARIES) {
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "implicit cwd denied without scope (fail-closed): $base",
                    )
                }
                if (base == "grep" && sawGrepRecursiveFlag) {
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "implicit cwd denied without scope (fail-closed): $base",
                    )
                }
            }
        }
        return null
    }

    /**
     * Bare filename 封堵（P0 re-review，binary-aware fail-closed +
     * PR#1 P0 value-shaped 封堵）。
     *
     * - 絕對路徑與含 `/`/`.`/`..` 已由 [isRelativePathArg] 擋，此處只處理
     *   不含 `/` 的 bare word（如 `init.rc`、`chat`、`secret.db`、`123`、`%s`、`*`）；
     * - [FILE_OPERAND_BINARIES] 或 S4 擴展白名單新 binary：一律拒（呼叫方已豁免
     *   pattern 槽/取值旗標值，此處不再豁免純值形態 —— `cat 123` / `ls 123` /
     *   `stat %s` 亦視為相對路徑）；
     * - `--opt=bare`：鍵在 [NON_PATH_OPT_KEYS]（color/sort/format 等）放行；
     *   否則 FILE_OPERAND 即使值像數字/格式/glob 仍拒（`--db=123` 視為潛在路徑）；
     * - [NO_FILE_BINARIES]：bare 永遠放行；
     * - Guest 模式（proot 容器內）：無 `/` bare 一律放行（子命令如 `version` /
     *   `update` / `sign` 與容器內相對路徑如 `x.apk`；cwd 已釘 `-w /` + `-r`，
     *   不會逃逸宿主；含 `/` 相對已由上層擋，宿主絕對路徑仍走 FileScope）；
     * - 未知二進位（提權通道如 dumpsys）：privateRoot==null 時任何 positional
     *   bare 一律拒（fail-closed）；非 null 時靠 cwd 釘死保證域內，放行無 `/`
     *   bare（`activity` 子命令與 `com.example.pkg` 包名兼顧），含 `/` 已由上層擋。
     */
    private fun checkBareFileOperand(
        base: String,
        arg: String,
        privateRoot: String?,
        isElevated: Boolean,
        allowedBinaries: Set<String>,
        isGuest: Boolean = false,
    ): Validation.Denied? {
        // Guest 容器內：privateRoot 非 null 且無 `/` bare 不視為宿主相對路徑
        // 逃逸，放行。含 `/` 的相對已由 isRelativePathArg 擋，宿主絕對路徑仍
        // 走 FileScope；privateRoot==null 時落回 fail-closed（與 direct 同策）。
        if (isGuest && privateRoot != null && !arg.contains('/')) {
            return null
        }
        if (arg.startsWith("-")) {
            val eq = arg.indexOf('=')
            if (eq < 0) return null
            val key = arg.substring(0, eq)
            val v = arg.substring(eq + 1)
            if (v.isEmpty() || v.startsWith("/")) return null
            if (key in NON_PATH_OPT_KEYS) return null
            if (base in NO_FILE_BINARIES) return null
            val isFileOperand = base in FILE_OPERAND_BINARIES ||
                (!isElevated && base in allowedBinaries && base !in NO_FILE_BINARIES)
            if (isFileOperand) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
            // 未知二進位 --opt：null 作用域 fail-closed；非 null 靠 cwd 釘死放行。
            if (isElevated && privateRoot == null) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
            return null
        }
        if (arg.startsWith("/")) return null
        if (arg.isEmpty()) return null
        if (arg == "-") return null
        if (base in NO_FILE_BINARIES) return null
        val isFileOperand = base in FILE_OPERAND_BINARIES ||
            (!isElevated && base in allowedBinaries && base !in NO_FILE_BINARIES)
        // 空白檔名（" " 等）對 FILE_OPERAND 視為相對路徑，一併拒；
        // 非 FILE_OPERAND（echo 等）維持放行（無檔案語義）。
        if (arg.isBlank()) {
            if (isFileOperand) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
            return null
        }
        if (isFileOperand) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "relative path denied (fail-closed, use absolute path): $arg",
            )
        }
        // 未知二進位 positional：null 作用域一律拒；非 null 靠 cwd 釘死放行。
        if (isElevated && privateRoot == null) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "relative path denied (fail-closed, use absolute path): $arg",
            )
        }
        return null
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
