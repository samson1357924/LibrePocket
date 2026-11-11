package dev.librepocket.shell

import dev.librepocket.files.FileScope
import dev.librepocket.tool.Flavor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * 受限 shell 策略（BACKLOG D05，矩陣「終端命令」行）。
 *
 * - 預設無提權：只放行唯讀性質的白名單二進位，不經 shell 解析
 *  （argv 直接交 [ProcessRunner]，不拼字串、不走 `sh -c`）。
 * - 黑名單先於白名單判定：`rm -rf /` 類必須回 [ShellDeny.BLACKLISTED]，
 *   而不是普通的「不在白名單」。
 * - 操作數實體解析門（B1，#9 有界切片）：絕對路徑先過 [FileScope.decide]
 *   詞法門，通過後再走實體門（逐段 NOFOLLOW 三態拒絕＋未知即拒 +
 *   存在即 `toRealPath()` 重裁＋real 身分獨立；任何失敗 fail-closed 拒絕）。
 *   直接／提權／guest host-tree 同門，不共享詞法 allow 就放行。
 *   Java 無 `O_NOFOLLOW`／`openat`：逐段 lstat 與 `toRealPath` 之間、
 *   驗證與實際 open/spawn 之間仍有併發 `rename`／`symlink` 替換窗口，
 *   本門只能縮窗、不能消除（殘餘 TOCTOU，見 [checkPhysicalOperandGate]）。
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

    /**
     * Round-3 Phase H（P2-6）：提權 argv 入站上限（入站 schema，提權／直接共用）。
     *
     * 上限表（超限一律 fail-closed `Denied(BLACKLISTED)` 且零 spawn，見
     * [copyArgvBounded]；凍結／快照層執行，validate 之前）：
     * - `MAX_ELEVATED_ARGC = 64`：單次提權 argv 項數上限。提權命令皆為短 argv
     *  （`id`／`getprop`／`screencap -p` 量級），64 已留足餘量；同時封頂
     *   `validate` 內 `joinToString` 與 spawn argv 掃描成本（最壞 64×4K＝256K
     *   字元，有界）。
     * - `MAX_ELEVATED_ARG_CHARS = 4K`：單項 UTF-16 長度上限。4K 字元至多
     *   12K UTF-8 位元組，逐項物化（`toByteArray`）恆安全，不會因單個巨參數 OOM。
     * - `MAX_ELEVATED_ARGV_BYTES = 64K`：全向量 UTF-8 總量上限，與
     *   [MAX_OUTPUT_BYTES] 64K 通道上限對齊（同一容量體系：提權通道任何方向的
     *   單次物化皆以 64K 為界，512K 硬讀另為輸出側專用，見
     *   [HARD_READ_CAP_BYTES]）。
     *
     * 前提：[validate]／[validateElevated] 假設輸入已為有界快照（呼叫方須先經
     * [copyArgvBounded] 或等效凍結；直接傳非受信任 List 即把阻塞／OOM 風險帶入
     * 純函數，產品路徑一律傳快照，見 [dev.librepocket.shell.frozenOrNull] 與
     * github 源集 `ElevatedExecPolicy.snapshot`）。
     */
    const val MAX_ELEVATED_ARGC: Int = 64

    /** 單項提權參數 UTF-16 長度上限（見上限表）。 */
    const val MAX_ELEVATED_ARG_CHARS: Int = 4 * 1024

    /** 提權 argv 全向量 UTF-8 總量上限（見上限表，與 64K 通道上限對齊）。 */
    const val MAX_ELEVATED_ARGV_BYTES: Int = 64 * 1024

    /**
     * 有界 argv 拷貝（Round-3 Phase H，P2-6 入站 schema 唯一實現）：
     * 呼叫執行緒內同步拷貝（不另起 worker；阻塞交由呼叫方監督——main 側
     * [dev.librepocket.shell.frozenOrNull] 的快照專用有界門、github 側 runner 內
     * `awaitPreSpawn` 監督，本函數只保證「有限輸入→有限時間／記憶體」）。
     *
     * fail-closed 回 null（呼叫方一律映 `Denied(BLACKLISTED)` 且零 spawn）：
     * - 拷貝抛（併發竄改 CME／自訂 List 抛）；
     * - `size` 與實際產出不一致（謊報 size 或併發增減，防 `ArrayList` 預分配繞行）；
     * - 超上限（argc／單項字元／總 UTF-8 位元組，見上限表）；
     * - 含孤立代理（lone surrogate，P3 選項 a：`encode` 前拒，見
     *   [PrivilegeAuditLog.encodeArgv] 誠實注記；雜湊單射只對良構字串成立，
     *   入站即拒故產品路徑不可達替換分支）；
     * - 含 null 元素（`List<String>` 契約破壞，原始型別擦除混入 null）。
     *
     * 記憶體上界：單項先驗字元數（≤4K 才物化位元組，≤12K），總量累積超 64K 即停，
     * 故峰值暫存 ≤64K＋12K，有界。
     */
    fun copyArgvBounded(src: List<String>): List<String>? {
        val n: Int = try {
            src.size
        } catch (_: Throwable) {
            return null
        }
        if (n < 0 || n > MAX_ELEVATED_ARGC) return null
        val out = ArrayList<String>(n)
        val it: Iterator<String> = try {
            src.iterator()
        } catch (_: Throwable) {
            return null
        }
        var totalBytes = 0
        var count = 0
        try {
            while (it.hasNext()) {
                // size 謊報偏小（實際產出更多）即拒，不信任預分配。
                if (count >= MAX_ELEVATED_ARGC) return null
                val s: String = try {
                    it.next()
                } catch (_: Throwable) {
                    return null
                } ?: return null
                if (s.length > MAX_ELEVATED_ARG_CHARS) return null
                // P3(a)：孤立代理入站即拒（fail-closed），不進雜湊替換分支。
                if (hasLoneSurrogate(s)) return null
                val bytes: ByteArray = try {
                    s.toByteArray(Charsets.UTF_8)
                } catch (_: Throwable) {
                    return null
                }
                totalBytes += bytes.size
                if (totalBytes > MAX_ELEVATED_ARGV_BYTES) return null
                out.add(s)
                count++
            }
        } catch (_: Throwable) {
            return null
        }
        // size 謊報偏大或併發縮減（產出與 size 不一致）即拒。
        if (count != n) return null
        return out
    }

    /**
     * 孤立代理檢查（P3 選項 a，僅入站門用）：
     * 高代理後無低代理、低代理前無高代理即孤立；配對代理（正常增補字元）放行。
     * 純掃描，不抛。
     */
    internal fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            } else if (c.isLowSurrogate()) {
                return true
            } else {
                i++
            }
        }
        return false
    }

    /** 取 argv[0] 的 basename（相容 `/bin/ls` 這類絕對路徑寫法）。 */
    fun basename(argv0: String): String =
        argv0.substringAfterLast('/').substringAfterLast('\\')

    /**
     * P2-7 multicall applet 黑名單（直接／提權 parity，僅黑名單不放寬）。
     *
     * dispatcher（toybox／toolbox／busybox）經主程式自身呼叫時 `argv[1]` 為
     * applet 名（如 `["toybox", "sh"]`），其語義等同 `argv[0]` 為該二進位，
     * 故同樣受 [DENIED_BINARIES] 約束，否則 `sh`／`rm`／`su` 可繞過 `argv[0]`
     * 黑名單。命中即 `BLACKLISTED`（直接通道 `["toybox", "sh"]` 即使無此門亦
     * 會以 `NOT_WHITELISTED` 拒，但此處顯式 `BLACKLISTED` 以與提權一致，且防
     * 未來白名單放寬 `toybox` 時回退；提權通道無白名單，此門為唯一 applet 閘）。
     * 只查 `DENIED_BINARIES`（全文片段／特殊字元已由全 argv 掃描覆蓋，不在此重檢）。
     * `argv.size < 2`（如 `["toybox"]` 無 applet）回 null（交後續門處理；空
     * applet 形僅印 help，無提權／破壞語義）。
     *
     * TODO(#136-B3): B3 子命令 allowlist（`cmd`／`package`／`uninstall` 類未知
     * 二進位的子命令審查）在此擴充 applet／子命令位置審查；本 PR 只做黑名單
     * parity，不做 allowlist、不放寬。
     */
    private fun multicallAppletDenial(argv: List<String>): Validation.Denied? {
        if (argv.size < 2) return null
        val base = basename(argv[0].trim())
        if (base !in ShellExecutables.MULTICALL_BINARIES) return null
        val applet = basename(argv[1].trim())
        if (applet.isEmpty()) return null
        if (applet in DENIED_BINARIES) {
            return Validation.Denied(ShellDeny.BLACKLISTED, "applet denied: $applet")
        }
        return null
    }

    /**
     * Round-4 Phase J（P1-1）：multicall 有效命令規範化（直接／提權同規則）。
     *
     * dispatcher（toybox／toolbox／busybox）經主程式自身呼叫時 `argv = [dispatcher,
     * applet, ...args]` 的實際執行語義等同 `[applet, ...args]`（resolved spawn 實為
     * `[realToybox, applet, ...args]`，見 [ShellExecutables]／提權 `toSpawnArgv`），
     * 故驗證必須以有效形 `[applet, ...args]` 套用完整拒絕規則，而非僅查第一層
     * applet 黑名單（見 [multicallAppletDenial] 的 Phase I 缺口）：
     * - 有效基 `applet = basename(argv[1].trim())`，有效操作數 `args = argv.drop(2)`；
     * - `argv.size < 2`（如 `["toybox"]`）或 `applet` 為空回 null（交呼叫方走常规
     *   dispatcher 本體門；空形僅印 help，無提權／破壞語義）；
     * - 旗標形 `argv[1]`（如 `"-h"`）亦視為 applet 候選（fail-closed：不在白名單即
     *   `NOT_WHITELISTED` 拒，不當 help 放行）；
     * - 含 `/`／`\` 的 applet 寫法經 [basename] 取尾段（如 `"a/sh"` → `"sh"`，
     *   仍 `BLACKLISTED`），與 dispatcher 黑名單同口徑；
     * - 呼叫方須以有效形完整套用：applet 黑名單（`BLACKLISTED`）→ 未知 applet
     *   明示拒（`NOT_WHITELISTED`，TODO #136-B3，不 blanket allow）→ dispatcher
     *   `argv[0]` 可信路徑詞法門 → find 高危謂詞（僅 `applet == "find"` 時掃
     *   `args`）→ 檔案域（[checkPathArgs] 以 `base = applet, args = args`，
     *   含 FILE_OPERAND／relative／realpath scope／implicit cwd／取值旗標值槽／
     *   有限 argv 同門；`--bind` 縱深同門）。
     * 未知 applet 維持拒絕（fail-closed）：有效基不在白名單即 `NOT_WHITELISTED`
     * （直接通道用 `allowedBinaries` 參數，提權用 [ALLOWED_BINARIES]；B3
     * `cmd`／`am` 等子命令 allowlist 在此擴充，本 PR 只做現有拒絕規則 parity＋TODO，
     * 不放寬）。
     */
    private data class MulticallEffective(val applet: String, val args: List<String>)

    private fun multicallEffective(argv: List<String>, base: String): MulticallEffective? {
        if (base !in ShellExecutables.MULTICALL_BINARIES) return null
        if (argv.size < 2) return null
        val raw: String = try {
            argv[1].trim()
        } catch (_: Exception) {
            return null
        }
        if (raw.isEmpty()) return null
        val applet = basename(raw)
        if (applet.isEmpty()) return null
        val args: List<String> = try {
            argv.drop(2)
        } catch (_: Exception) {
            return null
        }
        return MulticallEffective(applet, args)
    }

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

    /**
     * Stage D（P1-2 recursive symlink-follow）：guest 容器內豁免判定。
     *
     * `isGuest && privateRoot != null` 時 traversal 仍在容器命名空間內
     * （`proot -r rootfs` 無 bind 約束，宿主樹外絕對另由 [isContainerInternalPath]
     * 短路；宿主 symlink 語義不適用容器命名空間，邊界由 `proot -r`＋執行入口
     * host 樹封堵＋同容器鎖覆蓋）。與既有 dash／bare 放行同設計
     * （見 [checkPathArgs] Guest 通道抉擇）；guest＋null 仍 fail-closed。
     */
    private fun isGuestTraversalExempt(isGuest: Boolean, privateRoot: String?): Boolean =
        isGuest && privateRoot != null

    /**
     * Stage D（P1-2）：symlink-follow 旗標形態判定（純形態，不判位置）。
     *
     * 位置由呼叫方 [checkPathArgs] 判定（grep 僅選項位、find/du/ls 全位置
     * fail-closed，見該函數）；guest 豁免亦由呼叫方先判，此處只判形態。
     * 大小寫敏感（`R`/`L` 大寫才跟隨，`-r`/`-l` 不跟隨）：
     * - grep：`-R`（含 `-rR`/`-iR` 類單橫組合，`R` 即跟隨）與
     *   `--dereference-recursive`（含 `=value` 附著形）；小寫 `-r`／`--recursive`
     *   不跟隨，維持放行。`-eX` 附著（`X` 為 pattern，`ROOT` 含 `R` 不得誤殺）與
     *   `-fX` 附著（Stage A/C 已由相對路徑門拒，見 [checkPathArgs] 附著分支，
     *   此處跳過以保留相對訊息）一律跳過；`--regexp=`／`--file=` 等長 `=` 值
     *   非單橫故天然跳過。`-reROOT` 類中段 `-e` 附著（非 `-e` 起頭）刻意不展
     *   開，fail-closed 誤殺（罕見寫法，安全優先）。
     * - find：`-L` 與 `-follow`（BusyBox  legacy；另覆 `--follow`／`=value`
     *   附著形，fail-closed）；單橫組合含大寫 `L`（如 `-HL`）即拒。合法謂詞
     *   `-lname`／`-ilname`／`-links`／`-perm` 皆小寫 `l`，不受影響（大小寫敏感）。
     *   `-H`（僅跟隨命令列參數，操作數已由實體門拒 symlink）與 `-P`（預設不跟隨）
     *   維持放行。
     * - du：`-L`／`--dereference`（含 `=value` 附著形與 `--dereference*`
     *   非 `-args` 前綴，fail-closed）；單橫組合含 `L` 即拒。`-D`／
     *   `--dereference-args` 僅跟隨命令列參數：操作數 symlink 已由
     *   [checkPhysicalOperandGate] 拒（`LINK` 即拒），跟隨命令列即無新增逃逸；
     *   域內真目錄＋`-D` 不跟隨 interior，故維持放行（見下抉擇表）。
     * - ls：`-L`／`--dereference*`（含 `=value` 附著形）；單橫組合含 `L` 即拒。
     *   `-R` 單獨維持放行（僅列名，不讀目標內容；`-R`＋`-L` 同現時 `L` 門已拒）。
     *
     * 決策表（host 通道；guest＋scope 一律豁免，見上）：
     * | binary | flag | verdict | rationale |
     * | grep `-R`／`--dereference-recursive`／含 `R` 單橫 | DENY | 跟隨 interior symlink 讀域外 |
     * | grep `-r`／`--recursive` | ALLOW | 不跟隨 |
     * | find `-L`／`-follow`／含 `L` 單橫 | DENY | 跟隨 interior（含 `-follow`） |
     * | find `-H`／`-P` | ALLOW | `-H` 僅命令列（操作數門已拒 symlink），`-P` 不跟隨 |
     * | du `-L`／`--dereference`／含 `L` 單橫 | DENY | 跟隨 interior |
     * | du `-D`／`--dereference-args` | ALLOW | 僅命令列（操作數門已拒 symlink），不跟隨 interior |
     * | ls `-L`／`--dereference*`／含 `L` 單橫 | DENY | 跟隨（`ls -L` 讀目標 stat／內容） |
     * | ls `-R` 單獨 | ALLOW | 僅列名，不讀目標內容 |
     *
     * 長期受控 traversal（工作區無 symlink 不變量／遞迴白名單）超出本次有界切片，
     * 見 [checkPhysicalOperandGate] 遞迴後代揭露 follow-up。
     */
    private fun isFollowFlag(base: String, arg: String): Boolean {
        when (base) {
            "grep" -> {
                if (arg == "--dereference-recursive") return true
                if (arg.startsWith("--dereference-recursive=")) return true
                if (arg.startsWith("-") && !arg.startsWith("--") && arg != "-" && 'R' in arg) {
                    if (arg.startsWith("-e") && arg.length > 2) return false
                    if (arg.startsWith("-f") && arg.length > 2) return false
                    return true
                }
                return false
            }
            "find" -> {
                if (arg == "-L" || arg == "-follow") return true
                if (arg == "--follow") return true
                if (arg.startsWith("-follow=") || arg.startsWith("--follow=")) return true
                if (arg.startsWith("-") && !arg.startsWith("--") && arg != "-" && 'L' in arg) return true
                return false
            }
            "du" -> {
                if (arg.startsWith("--dereference") && !arg.startsWith("--dereference-args")) return true
                if (arg.startsWith("-") && !arg.startsWith("--") && arg != "-" && 'L' in arg) return true
                return false
            }
            "ls" -> {
                if (arg.startsWith("--dereference")) return true
                if (arg.startsWith("-") && !arg.startsWith("--") && arg != "-" && 'L' in arg) return true
                return false
            }
            else -> return false
        }
    }

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
     * 校驗：依次判空 → 黑名單（二進位/全文片段/特殊字元）→ multicall 有效形
     * （Round-4 Phase J P1-1：`argv[0]` 為 toybox／toolbox／busybox 主程式自身時
     * 按有效形 `[applet, ...args]` 套用完整拒絕門，見 [multicallEffective]；
     * P2-7 黑名單見 [multicallAppletDenial]，主程式形已由有效門先行覆蓋）→ 白名單
     * → argv[0] 可信路徑門（同名不同路徑封堵，詞法；僅直接通道，
     *   guest 跳過——guest argv[0] 是容器命名空間路徑）→ find 高危謂詞
     * → 檔案域（絕對路徑經 [FileScope.decide] 詞法門 + [checkPhysicalOperandGate]
     *   實體門；B1）。
     * 呼叫方（[RestrictedShell]）必須先調此函數，拒絕時不得建子進程；
     * 放行後仍須經 [ShellExecutables.resolve] 把 `argv[0]` 換成驗證後絕對路徑
     * 再 spawn（argv[0] 落地實體由 resolve 側驗；操作數實體由本函數實體門驗，
     * 驗→用窗口殘餘見 [checkPhysicalOperandGate]）。
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
     *
     * 測試接縫：[preRealPathHook] 僅供單測在步驟 1 後置換路徑段（見
     * [checkPhysicalOperandGate]）；生產一律經公開 [validate]（無注入點，
     * 內轉調本函數傳 null）。
     */
    fun validateInternal(
        argv: List<String>,
        privateRoot: String? = null,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        allowedBinaries: Set<String> = ALLOWED_BINARIES,
        isGuest: Boolean = false,
        trustedBinDirs: List<String> = ShellExecutables.TRUSTED_BIN_DIRS,
        preRealPathHook: (() -> Unit)? = null,
    ): Validation {
        // P3-3 bound-first（fail-closed，零 join 物化）：argc 超限即拒，
        // 不做 `joinToString` 全文扫描（64K×4K 直接调 validate 即 OOM 于拒前）。
        val argcDirect: Int = try {
            argv.size
        } catch (_: Exception) {
            return Validation.Denied(ShellDeny.BLACKLISTED, "argc bound")
        }
        if (argcDirect > MAX_ELEVATED_ARGC) {
            return Validation.Denied(ShellDeny.BLACKLISTED, "argc bound")
        }
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
        // Round-4 Phase J（P1-1）：multicall 主程式形有效命令完整 parity。
        // `[toybox, applet, ...args]` 按有效形 `[applet, ...args]` 套用全部拒絕門
        // （見 [multicallEffective]）：applet 黑名單 → 未知 applet 明示拒（B3 TODO，
        // 不 blanket allow）→ dispatcher 可信路徑門 → find 謂詞（有效基）→ 檔案域
        // （有效基／有效操作數，含 FILE_OPERAND／relative／realpath／implicit cwd／
        // 取值旗標值槽）。有效門全過後仍回 NOT_WHITELISTED（dispatcher 本體不在白名單，
        // 維持 Phase I 直接拒；提權同形放行，差異僅白名單門，見 validateElevatedInternal）。
        val eff = multicallEffective(argv, base)
        if (eff != null) {
            if (eff.applet in DENIED_BINARIES) {
                return Validation.Denied(ShellDeny.BLACKLISTED, "applet denied: ${eff.applet}")
            }
            if (eff.applet !in allowedBinaries) {
                return Validation.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "applet not allowed (needs B3 allowlist, fail-closed): ${eff.applet}",
                )
            }
            val rawEffArgv0 = argv[0].trim()
            if (!isGuest && (rawEffArgv0.contains('/') || rawEffArgv0.contains('\\'))) {
                if (!ShellExecutables.isTrustedAbsoluteArgv0(rawEffArgv0, trustedBinDirs)) {
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "untrusted executable path: $rawEffArgv0",
                    )
                }
            }
            if (eff.applet == "find") {
                for (arg in eff.args) {
                    if (arg in FIND_DENIED_PREDICATES) {
                        return Validation.Denied(
                            ShellDeny.BLACKLISTED,
                            "find predicate denied: $arg",
                        )
                    }
                }
            }
            val effDenial = checkPathArgs(
                base = eff.applet,
                args = eff.args,
                privateRoot = privateRoot,
                safRoots = safRoots,
                flavor = flavor,
                bridgeGranted = bridgeGranted,
                isElevated = false,
                allowedBinaries = allowedBinaries,
                isGuest = isGuest,
                preRealPathHook = preRealPathHook,
            )
            if (effDenial != null) return effDenial
            return Validation.Denied(
                ShellDeny.NOT_WHITELISTED,
                "dispatcher not whitelisted (multicall main, effective ${eff.applet} passed gates): $base",
            )
        }
        // P2-7 multicall applet 黑名單（直接通道 parity：BLACKLISTED 優先於
        // NOT_WHITELISTED，見 [multicallAppletDenial]；主程式形已由上有效門處理，
        // 此處僅覆非有效形〈非 dispatcher／無 applet〉，恆回 null）。
        multicallAppletDenial(argv)?.let { return it }
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
            preRealPathHook = preRealPathHook,
        )
        if (pathDenial != null) return pathDenial
        return Validation.Allowed(base)
    }

    /**
     * 公開直接通道校驗（無注入點；生產呼叫方一律走此函數）。
     * 本體單行委派 [validateInternal]（`preRealPathHook = null`）。
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
    ): Validation = validateInternal(argv, privateRoot, safRoots, flavor, bridgeGranted, allowedBinaries, isGuest, trustedBinDirs, null)

    /**
     * 提權通道校驗（S3，BACKLOG D09，矩陣 §3）。
     *
     * 與 [validate] 的差異只有兩處，其餘（空命令 → 黑名單二進位/全文片段 →
     * 參數衛生 → multicall 有效形（Round-4 Phase J P1-1，直接／提權同序同策，
     * 見 [multicallEffective]；P2-7 黑名單為其子集）→
     * argv[0] 可信路徑詞法門 → find 高危謂詞 → 檔案域）完全同序：
     * - 跳過白名單門禁：白名單外的二進位僅允許走提權通道（Shizuku/Root），
     *   直接 exec 仍拒（[validate] 維持原判）；
     * - 檔案域改走提權語義：私有域 / 已授權 SAF 直行（呼叫方應優先走
     *   SAF/私有域，見提權橋「SAF 優先」話術）；跨域且
     *   [FileScope.decide] 回 needsBridge（橋接已授權）才放行，其餘跨域仍拒。
     * 黑名單（`rm -rf /` 類）一律拒，不因提權放行。
     * - argv[0] 可信路徑詞法門（P1-1，與直接通道同等，host-only）：
     *   含 `/`/`\` 的寫法父目錄必須在 [trustedBinDirs] 內（預設
     *   [ShellExecutables.TRUSTED_BIN_DIRS]），否則 `/data/local/tmp/cat` 類
     *   憑 basename 通過；bare 由提權專用 resolve 經受控搜尋固定為已驗證實體
     *   （見 github 源集提權 wrapper，spawn 用固定目標，不再用原始 argv[0]）。
     *
     * 預設 [flavor] 為 PLAY（fail-closed：未指明風味時跨域一律拒；
     * 自裝風味呼叫方必須顯式傳 FOSS/GITHUB + bridgeGranted）。
     *
     * 測試接縫：[preRealPathHook] 僅供單測在步驟 1 後置換路徑段；生產一律經公開
     * [validateElevated]（無注入點，內轉調本函數傳 null）。
     */
    fun validateElevatedInternal(
        argv: List<String>,
        privateRoot: String? = null,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        isGuest: Boolean = false,
        trustedBinDirs: List<String> = ShellExecutables.TRUSTED_BIN_DIRS,
        preRealPathHook: (() -> Unit)? = null,
    ): Validation {
        // P3-3 bound-first（fail-closed，零 join 物化）：与 validateInternal 同门，
        // argc 超限即拒，不做 `joinToString` 全文扫描。
        val argcElevated: Int = try {
            argv.size
        } catch (_: Exception) {
            return Validation.Denied(ShellDeny.BLACKLISTED, "argc bound")
        }
        if (argcElevated > MAX_ELEVATED_ARGC) {
            return Validation.Denied(ShellDeny.BLACKLISTED, "argc bound")
        }
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
        // Round-4 Phase J（P1-1）：multicall 主程式形有效命令完整 parity（與直接同序同策）。
        // `[toybox, applet, ...args]` 按有效形 `[applet, ...args]` 套用全部拒絕門：
        // applet 黑名單 → 未知 applet 明示拒（B3 TODO，不 blanket allow；提權無白名單，
        // 此門為未知 applet 唯一閘，直接通道對應門見 validateInternal 有效分支）→
        // dispatcher 可信路徑門 → find 謂詞（有效基）→ 檔案域（有效基／有效操作數，
        // 提權語義）。有效門全過即 Allowed(有效基)（與 `[applet, ...]` 等效）。
        val effElevated = multicallEffective(argv, base)
        if (effElevated != null) {
            if (effElevated.applet in DENIED_BINARIES) {
                return Validation.Denied(ShellDeny.BLACKLISTED, "applet denied: ${effElevated.applet}")
            }
            if (effElevated.applet !in ALLOWED_BINARIES) {
                return Validation.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "applet not allowed (needs B3 allowlist, fail-closed): ${effElevated.applet}",
                )
            }
            val rawEffElevatedArgv0 = argv[0].trim()
            if (!isGuest && (rawEffElevatedArgv0.contains('/') || rawEffElevatedArgv0.contains('\\'))) {
                if (!ShellExecutables.isTrustedAbsoluteArgv0(rawEffElevatedArgv0, trustedBinDirs)) {
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "untrusted executable path: $rawEffElevatedArgv0",
                    )
                }
            }
            if (effElevated.applet == "find") {
                for (arg in effElevated.args) {
                    if (arg in FIND_DENIED_PREDICATES) {
                        return Validation.Denied(
                            ShellDeny.BLACKLISTED,
                            "find predicate denied: $arg",
                        )
                    }
                }
            }
            val effElevatedDenial = checkPathArgs(
                base = effElevated.applet,
                args = effElevated.args,
                privateRoot = privateRoot,
                safRoots = safRoots,
                flavor = flavor,
                bridgeGranted = bridgeGranted,
                isElevated = true,
                allowedBinaries = ALLOWED_BINARIES,
                isGuest = isGuest,
                preRealPathHook = preRealPathHook,
            )
            if (effElevatedDenial != null) return effElevatedDenial
            return Validation.Allowed(effElevated.applet)
        }
        // P2-7 multicall applet 黑名單（提權通道：無白名單，此門為唯一 applet 閘；
        // 與直接通道同序同策，見 [multicallAppletDenial]；主程式形已由上有效門處理，
        // 此處僅覆非有效形，恆回 null）。
        multicallAppletDenial(argv)?.let { return it }
        // argv[0] 可信路徑門（P1-1，提權與直接同等詞法門，host-only）：
        // 含 `/`/`\` 的寫法父目錄必須在可信系統目錄內，否則同名不同路徑的假
        // 二進位（`/data/local/tmp/cat`）可憑 basename 通過白名單（提權無白名單
        // 更需此門）；bare 由提權專用 resolve 經受控搜尋固定（見 github 源集
        // [ElevatedExecPolicy]），此處只做詞法門，不做實體 IO。
        // Guest 通道（isGuest=true）跳過此門（與直接通道同策，容器命名空間路徑）。
        val rawElevatedArgv0 = argv[0].trim()
        if (!isGuest && (rawElevatedArgv0.contains('/') || rawElevatedArgv0.contains('\\'))) {
            if (!ShellExecutables.isTrustedAbsoluteArgv0(rawElevatedArgv0, trustedBinDirs)) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "untrusted executable path: $rawElevatedArgv0",
                )
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
            preRealPathHook = preRealPathHook,
        )
        if (pathDenialElevated != null) return pathDenialElevated
        return Validation.Allowed(base)
    }

    /**
     * 公開提權通道校驗（無注入點；生產呼叫方一律走此函數）。
     * 本體單行委派 [validateElevatedInternal]（`preRealPathHook = null`）。
     */
    fun validateElevated(
        argv: List<String>,
        privateRoot: String? = null,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        isGuest: Boolean = false,
        trustedBinDirs: List<String> = ShellExecutables.TRUSTED_BIN_DIRS,
    ): Validation = validateElevatedInternal(argv, privateRoot, safRoots, flavor, bridgeGranted, isGuest, trustedBinDirs, null)

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

    /**
     * 取參數中的絕對路徑候選（Stage C，P1-1 attached file-options）。
     *
     * - 裸 `/…`：程式收到的操作數即該字串，驗用一致，可抽候選。
     * - 長選項 `--opt=/…`：GNU 長選項 `=` 語義把 `=` 後字面傳給程式
     *   （use==validated），故以後綴 `/…` 走 FileScope＋實體門是 sound 的；
     *   未知長鍵 `--unknown=/abs` 同理保留信任（長 `=` 無短橫歧義）。
     * - 單橫 `-k…=/…`（含 `-f=/abs`、`-file=/abs` 類）：getopt 短選項語義把
     *   `=` 起的整個 rest（`=/abs`）當相對檔名，OS 在 cwd 下打開
     *   `<cwd>/=/abs`（PoC：scope 預植 `=` symlink → outside 即穿出），
     *   與後綴 `/abs` 驗用分叉，故永不抽候選（fail-closed，改由附著分支拒）。
     */
    private fun absoluteCandidates(arg: String): List<String> {
        if (arg.startsWith("/")) return listOf(arg)
        if (!arg.startsWith("--")) return emptyList()
        val eq = arg.indexOf('=')
        if (eq >= 0 && eq + 1 < arg.length && arg[eq + 1] == '/') {
            return listOf(arg.substring(eq + 1))
        }
        return emptyList()
    }

    /**
     * 原始操作數是否含 `..` 段（F1 fail-closed，不先做詞法 normalize 折疊）。
     *
     * spawn 用原始操作數（[RestrictedShell] 不重寫操作數），詞法折疊後再驗會把
     * `/scope/sub/../outside`（`sub` 為指域外 symlink）誤判為域內，故驗側必須
     * 嚴於用側：原始字串以 `/` 切段含 `..` 即拒（與 [FileScope] 詞法門一致的
     * 拒絕語義）。
     */
    private fun hasDotDotSegment(rawCandidate: String): Boolean =
        rawCandidate.split('/').any { it == ".." }

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
     * Stage A（P1 dash bypass）：短橫形態操作數判定（`--` 後／`-f` 值槽用）。
     * 以 `-` 開頭、不是 `-` stdin 哨兵、不含 `=` 即短橫形態（如 `-secret`、`--` 自身）。
     * 含 `=` 的形態（`--opt=v`）另由操作數 `=` 門（[checkPathArgs] deny-all，
     * `--` 後／`-f` 槽內）／旗標位 [checkBareFileOperand] 鍵值分支處理，
     * 不在此判定（`--color=always` 等合法 `--opt=value` 不受影響）。
     */
    private fun isDashOperand(arg: String): Boolean =
        arg.startsWith("-") && arg != "-" && arg.indexOf('=') < 0

    /**
     * Stage C（P1-1 attached file-options）：取值即檔名的長選項鍵
     * （`--key=value` 單 token 形態用）。
     * 其 `=value` 恆為檔名（flag 位與 `--` 後操作數位皆然），短橫／相對值
     * fail-closed（見 [checkBareFileOperand]）；絕對值已先由 [absoluteCandidates]
     * 走 FileScope＋實體門（僅長選項 `--` 前綴才抽候選，見該函數）。
     *
     * grep 打開檔案的選項清單（audit 完備性）：
     * - `--file`：pattern file（`--file F` 分離形經 REQUIRED-FILENAME 槽，
     *   `--file=F` 附著形經本鍵）；
     * - `--exclude-from`：exclude-patterns file（GNU grep；分離形
     *   `--exclude-from F` 經槽、附著形 `--exclude-from=F` 經本鍵，
     *   與 `--file` 同門）；
     * - 短 `-f`：分離形（槽）＋附著形 `-fX`／`-f=X`（附著分支，grep-only，
     *   單橫 `=` rest 是相對檔名故一律拒，見 [checkPathArgs] 附著分支）。
     * 刻意未列（非檔案開啟，無需檔名審查）：
     * - `-e`／`--regexp`：pattern 字串（值槽／NON_PATH，不觸檔案）；
     * - `--include`／`--exclude`／`--exclude-dir`：glob（NON_PATH）；
     * - `-d`／`--max-depth`／`--max-count` 等：列舉／數字（VALUE_TAKING_FLAGS／NON_PATH）。
     * `--exclude-from` 刻意不計入 grep pattern 預掃（[checkPathArgs] 首的
     * `hasExplicit`）：它不提供搜尋 pattern，首個 positional 仍是 pattern 槽。
     */
    private val FILE_VALUE_OPT_KEYS: Set<String> = setOf(
        "--file",
        "--exclude-from",
    )

    /**
     * argv 參數是否為相對路徑（P0 fail-closed）。
     * - 絕對路徑（`/...`、長選項 `--opt=/...`，見 [absoluteCandidates]
     *   Stage C：單橫 `-k=/...` 永不視為絕對）由 [absoluteCandidates] 處理，
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
     * 共用路徑審查（P0 re-review fix→review 循環後 + PR#1 P0 value-shaped 封堵 +
     * Stage A P1 dash bypass 封堵）：
     * 絕對路徑先過 FileScope → 相對含 `/` 擋 → grep `--` 終止符與 pattern 槽 →
     * 取值旗標值豁免 → Stage A 短橫操作數封堵（`--` 後／`-f` 值槽的 `-secret`
     * 即相對檔名，fail-closed）→ binary-aware bare 封堵。
     * 直接/提權共用，避免雙寫漂移。
     *
     * Stage A＋C 選項解析器狀態（P1 scope bypass，`cat -- -secret`／`grep -f -patterns`
     * 類）：顯式追蹤 `endOfOptions`（`--` 終止符）＋ REQUIRED-FILENAME 槽
     * （grep `-f`／`--file`／`--exclude-from` 精確 token 的下一位；
     * `ls -f` 是排序旗標故槽只限 grep；
     * `--` 後的 `-f` 是操作數而非旗標，不設槽；`-f` 附著值 `-fX`／`-f=X`
     * 恒為檔名 X（Stage C：`-f=` 的值是 `=/…` 相對檔名），
     * 另由附著分支 fail-closed）。槽內／`--` 後的短橫形態（[isDashOperand]，
     * `-` 哨兵除外）即相對檔名操作數，一律 `Denied(BLACKLISTED)`（與相對路徑
     * 拒絕同 verdict 家族），不再經由短橫放行洞穿 FileScope 實體門。
     *
     * Guest 通道抉擇（設計對齊既有 [checkBareFileOperand] L1084 容器內 bare 放行）：
     * `isGuest && privateRoot != null` 時短橫操作數視為容器內相對路徑，放行
     * （cwd 已釘 `-w /`＋`-r rootfs`，宿主樹外絕對路徑另由
     * [isContainerInternalPath] 短路；宿主 symlink 語義不適用容器命名空間，
     * 邊界由 `proot -r` 無 bind＋執行入口 host 樹封堵＋同容器鎖覆蓋）。
     * 宿主通道（直接＋提權 Root/Shizuku，cwd 釘死到 privateRoot 但其內容可含
     * 攻擊者預植的 `-secret -> /outside` symlink，OS 打開即穿出）一律
     * fail-closed；guest＋null 作用域同樣 fail-closed（與 bare 同策）。
     *
     * 豁免維持：VALUE_TAKING_FLAGS 值、grep pattern 槽（首個 positional，含
     * `--` 後短橫形如 `grep -- -r`，故 pattern 槽先於短橫封堵消費；
     * `grep -- pattern -secret` 中 pattern 取首位、`-secret` 仍拒）、
     * NO_FILE_BINARIES（`echo -- -secret` 只是字串，無檔案語義）、`-` 哨兵、
     * `--opt=value` 非路徑鍵（`--color=always` 旗標位維持放行；但 `--` 後／
     * `-f` 槽內的 `--file=` 值仍按檔名審查）。旗標位的 `=` 空值（`--opt=`）
     * 維持既有放行（用法錯誤，無檔案觸及），尾 `=` 殘餘見測試檔注記；
     * `--` 後／`-f` 槽內的 `--opt=`（含 `--file=`）一律 `Denied(BLACKLISTED)`，
     * 見操作數門。
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
        preRealPathHook: (() -> Unit)? = null,
    ): Validation.Denied? {
        // grep 顯式 pattern 旗標預掃：-e/--regexp/-f 存在時無 positional pattern 槽。
        // Stage C：`--exclude-from` 刻意不計入（它不提供搜尋 pattern，只提供排除表）。
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
        // Stage A＋C：grep `-f`／`--file`／`--exclude-from` 精確 token 的下一位是
        // REQUIRED-FILENAME 槽（檔名操作數，短橫形亦拒，見下；只限 grep，
        // `ls -f` 是排序旗標）。`--exclude-from` 不計入 pattern 預掃
        // （見 FILE_VALUE_OPT_KEYS：它不提供搜尋 pattern）。
        var pendingFileValue = false
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
            // Stage D（P1-2）：find/du/ls symlink-follow 全位置 fail-closed。
            // find `-L`/`-follow`（全局，可出現在路徑前後）、du `-L`/
            // `--dereference`、ls `-L`/`--dereference` 在 spawn 後跟隨 interior
            // symlink 讀域外，而操作數門只驗 CLI 操作數本鏈（見
            // [checkPhysicalOperandGate] 遞迴後代揭露），故必須拒旗標本身。
            // 全位置（選項位／值槽／`--` 後皆拒，fail-closed，僅 guest 容器內
            // 豁免，見 [isGuestTraversalExempt]）；`--` 後短橫在宿主本就由短橫門
            // 拒，訊息以 traversal 為準不影響 verdict 家族（同 BLACKLISTED）。
            if (!isGuestTraversalExempt(isGuest, privateRoot) &&
                (base == "find" || base == "du" || base == "ls") &&
                isFollowFlag(base, arg)
            ) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "traversal flag denied (fail-closed, symlink-follow): $arg",
                )
            }
            for (candidate in absoluteCandidates(arg)) {
                // F1：原始操作數含 `..` 段即 fail-closed 拒絕（不先做詞法 normalize
                // 折疊；拒絕訊息帶原因碼 DOTDOT）。spawn 用原始操作數，故驗側嚴於
                // 用側；guest 容器內路徑亦同門先判，不因 `continue` 放行。
                // belt-and-suspenders：內層 checkFileScope／checkFileScopeElevated／
                // checkPhysicalOperandGate 重檢同條件，刻意冗餘，刪任一層需同步更新測試。
                if (hasDotDotSegment(candidate)) {
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "dotdot segment denied (reason=DOTDOT): $candidate",
                    )
                }
                // Guest 容器內路徑（PR#1 blocker 2）：isGuest 且作用域已知時，
                // 宿主樹外的絕對路徑是容器內路徑，直接放行（proot -r 約束；
                // host inbox 前綴另由執行入口擋，外掛點由下式保留拒絕）。
                // B1 揭露：容器內路徑是 guest namespace，宿主實體門無法同門驗證
                // （宿主 FS 上無對應實體，無法 `toRealPath` 重裁）；其邊界是
                // `proot -r rootfs` 無 bind 約束 + 執行入口的 host 樹封堵
                // （`LinuxInboxStager.hostLinuxAbsoluteRef`／`unstagedInboxRef`）
                // + spawn 持同容器鎖（見 `ProotExec.execute`），宿主 symlink 語義
                // 不適用。容器內 symlink／TOCTOU 殘餘見 `ProotExec` KDoc。
                if (isGuest && privateRoot != null && isContainerInternalPath(candidate, privateRoot)) {
                    continue
                }
                val denial = if (isElevated) {
                    checkFileScopeElevated(candidate, privateRoot, safRoots, flavor, bridgeGranted, preRealPathHook)
                } else {
                    checkFileScope(candidate, privateRoot, safRoots, flavor, bridgeGranted, preRealPathHook)
                }
                if (denial != null) return denial
            }
            // Stage A：REQUIRED-FILENAME 槽先消費（`grep -f --` 的 `--` 是檔名，
            // 不是終止符；`-` 是 stdin 哨兵，維持放行）。
            val inFileSlot = pendingFileValue
            pendingFileValue = false
            if (inFileSlot) {
                if (arg == "-") {
                    prevArg = arg
                    continue
                }
                if (arg == "--") {
                    if (isGuest && privateRoot != null) {
                        prevArg = arg
                        continue
                    }
                    return Validation.Denied(
                        ShellDeny.BLACKLISTED,
                        "relative path denied (fail-closed, use absolute path): $arg",
                    )
                }
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
            // Stage D（P1-2）：grep symlink-follow 僅選項位 fail-closed。
            // `-R`（含 `-rR`/`-iR` 類單橫組合）與 `--dereference-recursive`
            // 跟隨 interior dir symlink 讀域外（PoC：scope/subtree 內 `escape`
            // → outside，`grep -R TOKEN /scope/subtree` 印 outside/hidden.txt）。
            // 僅選項位（`!endOfOptions && !inFileSlot && prevArg !in
            // VALUE_TAKING_FLAGS`，`--` 後短橫／`-f` 槽／`-e` 值另由短橫／附著門
            // 拒，訊息保留相對家族；`grep -- -r` 首位仍是 pattern，已由上槽消費；
            // `-e -R` 的 `-R` 是 pattern 值（`prevArg == "-e"`），跳過）；
            // 小寫 `-r`／`--recursive` 不跟隨，維持放行；`-eX` 附著 pattern
            //（含 `R` 如 `ROOT`）與 `-fX` 附著（另由附著門拒）由 [isFollowFlag]
            // 內跳過。guest 容器內豁免（見 [isGuestTraversalExempt]）。
            if (base == "grep" && !isGuestTraversalExempt(isGuest, privateRoot) &&
                !endOfOptions && !inFileSlot && prevArg !in VALUE_TAKING_FLAGS &&
                isFollowFlag(base, arg)
            ) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "traversal flag denied (fail-closed, symlink-follow): $arg",
                )
            }
            // Stage A 短橫操作數封堵：`--` 後／REQUIRED-FILENAME 槽內的短橫形態
            // （`-` 哨兵除外，見 isDashOperand）即相對檔名，不再放行。
            // 排序在 pattern 槽／取值豁免之後：`grep -- -r` 的 -r 仍是 pattern
            // （既有豁免），`grep -- pattern -secret` 的 -secret 仍拒。
            // 豁免僅兩處：guest 容器內（`isGuest && privateRoot != null`，見上抉擇）
            // 與 NO_FILE 二進位（`echo -- -secret` 只是字串）；未知提權 binary＋scope
            // 的非短橫 bare 仍靠 cwd 釘死放行（子命令／包名兼顧），但短橫形在檔位
            // 即檔名（旗標應在 `--` 之前），一律 fail-closed。
            if ((endOfOptions || inFileSlot) && isDashOperand(arg) &&
                !(isGuest && privateRoot != null) && base !in NO_FILE_BINARIES
            ) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
            // Stage A＋C grep `-f` 附著值：`-f` 取必填參數，故 `-fX`
            // 的附著尾 X 恒為檔名；相對即 fail-closed（宿主通道）。
            // Stage C：`-f=/x` 的值是 `=/x` 整 rest（短選項附著語義，相對檔名），
            // 故 `=` 形亦拒（不豁免；單橫 `=` 永不走 absoluteCandidates，見該函數）。
            // 選項位限定（`--` 後的 `-fX` 是檔名，已由上式覆蓋）；`-F` 大寫另旗，
            // 不在此列；含 `/` 者（`-f/a` 類）已由 isRelativePathArg 擋。
            if (base == "grep" && !endOfOptions && !inFileSlot &&
                arg.startsWith("-f") && arg.length > 2 && !arg.startsWith("--") &&
                !(isGuest && privateRoot != null)
            ) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
            // Stage A `=` operand deny-all: `--` 后／REQUIRED-FILENAME 槽内的 `--opt=v`
            // 无旗标语义，即文件名（`cat -- --file=-` 打开字面文件 `--file=-` 而非
            // stdin，`cat -- --file=/abs` 打开 `<cwd>/--file=/abs` 而非 `/abs`；
            // absoluteCandidates 验后缀 `/abs` 而 spawn 打开不同路径＝验证／使用
            // 不一致）。操作数位一律 Denied(BLACKLISTED)，仅 guest 容器内与
            // NO_FILE 二进制豁免（不打开宿主文件）；`--file=` 落穿豁免已删除。
            // 旗标位的 `--file=/abs`／`--exclude-from=/abs`
            // 仍经 absoluteCandidates＋checkBareFileOperand。
            if ((endOfOptions || inFileSlot) && arg.startsWith("-") && arg.indexOf('=') >= 0 &&
                !(isGuest && privateRoot != null) && base !in NO_FILE_BINARIES
            ) {
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
            // LOW accepted residual: 旗标位未知 binary 的 `--opt=-dash` 值仍靠 cwd 钉死放行；cwd 可含预植 symlink，宿主语义见上 Guest 抉择。
            val bareDenial = checkBareFileOperand(
                base = base,
                arg = arg,
                privateRoot = privateRoot,
                isElevated = isElevated,
                allowedBinaries = allowedBinaries,
                isGuest = isGuest,
            )
            if (bareDenial != null) return bareDenial
            // Stage A＋C 設槽：grep `-f`／`--file`／`--exclude-from` 精確 token
            // （選項位）使下一位為 REQUIRED-FILENAME 槽。`--` 後的操作數位不設槽；
            // 他 binary 的 `-f`（如 `ls -f` 排序旗）不設槽。
            if (base == "grep" && !endOfOptions &&
                (arg == "-f" || arg == "--file" || arg == "--exclude-from")
            ) {
                pendingFileValue = true
            }
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
     * - Stage A＋C `--file=bare`／`--exclude-from=bare`：鍵在 [FILE_VALUE_OPT_KEYS] 即檔名，短橫形／相對值
     *   全 binary fail-closed（未知提權＋scope 亦拒，cwd 釘死不豁免檔名鍵；
     *   `-` stdin 哨兵／空值／絕對值維持放行，絕對值已先走實體門）；
     *   [NO_FILE_BINARIES] 無檔案語義，維持放行；
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
            // Stage A＋C：`--file=`／`--exclude-from=` 值恒為檔名
            // （旗標位與 `--` 後操作數位皆然）：
            // 短橫形（`-patterns`）／相對值 fail-closed；`-` 是 stdin 哨兵，
            // 維持放行（既有關閉：修前 `--file=-` 誤拒，stdin 無檔案觸及），
            // 絕對值已先由 absoluteCandidates 走 FileScope＋實體門（上式）。
            // NO_FILE 二進位無檔案語義，維持放行；guest 容器內（無 `/`）已由
            // 上方早返放行，此處只剩 guest＋null（fail-closed）與宿主通道。
            if (key in FILE_VALUE_OPT_KEYS) {
                if (v == "-") return null
                if (base in NO_FILE_BINARIES) return null
                return Validation.Denied(
                    ShellDeny.BLACKLISTED,
                    "relative path denied (fail-closed, use absolute path): $arg",
                )
            }
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

    /** 絕對路徑經 [FileScope.decide]；跨域 / 需橋接 / 無作用域一律拒絕。
     * 詞法通過後再走實體解析門（見 [checkPhysicalOperandGate]；B1）。
     * 原始含 `..` 段先 fail-closed 拒（見 [hasDotDotSegment]），不依賴折疊後詞法。 */
    private fun checkFileScope(
        path: String,
        privateRoot: String?,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
        preRealPathHook: (() -> Unit)? = null,
    ): Validation.Denied? {
        // 刻意冗餘：外層 checkPathArgs 已先拒同條件（見 checkPathArgs 內 hasDotDotSegment 外層檢查）；保留作縱深。
        if (hasDotDotSegment(path)) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "dotdot segment denied (reason=DOTDOT): $path",
            )
        }
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
        return checkPhysicalOperandGate(
            candidate = path,
            privateRoot = privateRoot,
            safRoots = safRoots,
            flavor = flavor,
            bridgeGranted = bridgeGranted,
            isElevated = false,
            preRealPathHook = preRealPathHook,
        )
    }

    /** lstat 三態：LINK（是連結）／PRESENT（存在非連結）／ABSENT（確定缺席）／UNKNOWN（不可判定，fail-closed）。 */
    private enum class LstatState { LINK, PRESENT, ABSENT, UNKNOWN }

    /**
     * NOFOLLOW 讀屬性三態（fail-closed：不可判定即 UNKNOWN，不當缺席）。
     * [Files.isSymbolicLink] 不可判定回 false、exists 步 EACCES 回 false 皆會把
     * unknown 當 nonexistent 而降級，提權通道（權限高於檢查者）可繞行，故此處
     * 以 `readAttributes(NOFOLLOW)` 區分：[NoSuchFileException] 才為 ABSENT，
     * 含 `AccessDeniedException`／`SecurityException`／I/O 未知一律 UNKNOWN。
     */
    private fun lstatNoFollow(path: java.nio.file.Path): LstatState {
        return try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink) LstatState.LINK else LstatState.PRESENT
        } catch (_: java.nio.file.NoSuchFileException) {
            LstatState.ABSENT
        } catch (_: Exception) {
            LstatState.UNKNOWN
        }
    }

    /**
     * 實體解析門（B1，#9 有界切片；直接／提權／guest host-tree 同門）。
     *
     * 前提：呼叫方已過 [FileScope.decide] 詞法門（本函數不放寬詞法拒絕）。
     * `..` 拒絕政策：原始操作數含 `..` 段一律 fail-closed 拒絕，不先做詞法
     * normalize 折疊（spawn 用原始操作數，故驗側嚴於用側；與 [FileScope] 詞法門
     * 一致的拒絕語義；拒絕訊息帶原因碼 `DOTDOT`）。
     * 步驟（fail-closed，任一步失敗即拒）：
     * 0. `..` 段檢查（見上）：原始字串以 `/` 切段含 `..` 即拒；含反斜線 `\` 即拒（見 F-backslash，Linux 語義）。
     *    例外：guest 容器內路徑在外層先 `continue` 跳過本門（含 backslash 門），
     *    由容器邊界（proot -r 無 bind＋host 樹封堵＋同容器鎖）覆蓋，宿主無逃逸。
     * 1. 逐段 symlink 拒絕（三態＋未知即拒）：候選全鏈（`/` 至本體）與 [privateRoot]
     *    全鏈逐段 [lstatNoFollow]（NOFOLLOW，不跟隨）；LINK 即拒（含懸空連結），
     *    UNKNOWN 即拒（`AccessDeniedException`／`SecurityException`／I/O 未知
     *    （含 NotDirectory／瞬時 I/O 錯），關閉 EACCES 混同為缺席的繞行；ABSENT 等同舊 false 語義繼續，PRESENT 繼續；
     *    超深仍拒）。提權注記：app UID 不可判定者雙通道皆拒，需提權側預檢者走
     *    Refs #10 follow-up（刻意不在本 PR 碰 github source set）。
     *    B4 跨域提醒（owner P2-9）：app UID 對 Root/fb0 等跨域目標的 lstat
     *    可能因 EACCES 而 UNKNOWN，本門 fail-closed 拒（雙通道皆拒，不降級，
     *    不改 UNKNOWN fail-closed）。真機跨域成功路徑（root/Shizuku 提權後
     *    讀取）需 #136-B3 E2E 驗收；特權側預檢如需放行須另設可信有界門
     *    （特權行程內 lstat＋結果有界回傳），不得把 UNKNOWN 一律放行
     *    （TODO #136-B3）。
     * 2. 存在即 `toRealPath()` 全解析固定，再驗 real 身分獨立條件＋同語義重裁
     *    [FileScope.decide]（[isElevated] 為 true 時沿提權語義
     *    `allowed==true` 即放行，否則要求 `allowed && !needsBridge`）；
     *    兩條件獨立且皆須通過（不得經連結 AND real 授權）：`real != normCandidate`
     *    即拒（未經連結則 real 恆等於正規候選；提權橋已授權亦不放寬 symlink
     *    operand），real 逃出詞法域即拒；解析抛異常即拒。存在判定三態
     *    （[lstatNoFollow]；懸空視為 LINK 早在步驟1即拒，此僅縱深）。
     *    併發 delete（消失）降級為允許但 spawn 將 ENOENT（無逃逸）；併發 swap
     *    （仍存在但指別處）經 real 身分條件拒。
     * 3. 確定缺席才降級為第 1 步（父鏈 symlink Walk）：ABSENT 才回 null，
     *    不以 `NoSuchFileException` 誤殺合法未建檔查詢（如 `ls` 未建檔名、
     *    單測假根）；LINK 即拒，UNKNOWN 即拒（未知即拒，不降級）；
     *    其殘餘（檢查後建鏈、父目錄被換）見下。
     *    hardlink／mount 不改字串故仍走舊語義（列 out-of-scope，與 ScopedFileStore 同）。
     *
     * 誠實揭露（Java 無 `O_NOFOLLOW`／`openat`，TOCTOU 只能縮窗不能消除）：
     * 逐段 lstat → `toRealPath` → 實際 open／spawn 非原子，驗證與使用之間
     * 攻擊者仍可能以 `rename`／`symlink` 替换路徑段（單次替換多數轉為下次
     * 調用拒絕，雙重替換〈驗→換入連結→用→換回〉仍可能穿出）；併發替換不在
     * 本門消除承諾內。訊息只攜詞法候選，不內插實體路徑（不洩敏）。
     *
     * hardlink 揭露：本門不解 hardlink；同 FS 域內 hardlink 指到域外（硬連結
     * 指向域外 inode）在承諾外，不在消除保證內。
     * 遞迴後代揭露（Refs #9；Stage D 已收斂跟隨旗標，殘餘見下）：
     * 本門只驗操作數本鏈，不驗遞迴走訪的後代（find/du/ls -R、grep -r 在 spawn 後
     * 走訪後代，後代內 interior symlink + 跟隨旗標〈find -L、grep -R（含
     * --dereference-recursive）等，ls/stat/du -L（含 --dereference）等〉可讀域外；
     * 註：find -H 僅跟隨命令列參數、不跟隨 interior 後代，不在此列；find 預設 -P
     * 不跟隨。Android 實為 toybox applet 語義，旗標可用性未經裝置驗證）。
     * interior link 需併發植入或帶外植入；直接通道 `ln` 不在允許命令內
     * （ALLOWED_BINARIES 無 ln），直接通道模型無法直接建鏈，但 guest 通道
     * python3/tar/unzip 可在容器內建鏈（見 ProotExec KDoc 容器內殘餘）。
     * Stage D 收斂（P1-2）：跟隨旗標本身已由 [checkPathArgs] fail-closed 拒絕
     * （grep `-R`／`--dereference-recursive`／含 `R` 單橫、find `-L`／`-follow`／
     * 含 `L` 單橫、du `-L`／`--dereference`／含 `L` 單橫、ls `-L`／
     * `--dereference*`／含 `L` 單橫；小寫 `-r`／`--recursive`、find `-H`／`-P`、
     * du `-D`／`--dereference-args`、ls `-R` 單獨維持放行，見 [isFollowFlag]
     * 決策表；multicall 有效基同門；guest＋scope 容器內豁免）。長期受控
     * traversal（工作區無 symlink 不變量／遞迴白名單）仍為 follow-up（Refs #9）。
     * 提權（Root/Shizuku，見 app/src/github）走同一門
     * （isElevated=true），同殘餘適用，其 Failed 去路徑化等收斂見 Refs #10
     * follow-up（刻意不在本 PR 碰 github source set）。
     * F-NORMWALK：步驟 1 walk 的對象為 [FileScope.normalize] 後鏈，原始 `..`
     * 由步驟 0 先拒，順序不可調換（先 normalize 再驗 `..` 會把
     * `/scope/sub/../outside` 誤判為域內，見 F1）。
     * 段間競態點名：逐段 lstat 非原子，中間段跟隨檢查、前段驗過仍可被
     * `rename` 換掉；段間安全網僅為終態 `toRealPath()`＋重裁，僅反映 toRealPath() 呼叫瞬間終態，不與逐段 lstat 原子一致，中間段在兩次檢查之間仍可被 rename 換掉。
     * mount／bind 不解（與 hardlink 同為 out-of-scope：`toRealPath()` 不解
     * mount，bind 到域外仍顯示域內）。
     *
     * 測試接縫：[preRealPathHook] 在步驟 1 迴圈後、exists 步前調用（預設 null，
     * 生產行為零變；測試用來在 lstat 後、toRealPath 前置換路徑段）。
     */
    private fun checkPhysicalOperandGate(
        candidate: String,
        privateRoot: String,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
        isElevated: Boolean,
        preRealPathHook: (() -> Unit)? = null,
    ): Validation.Denied? {
        fun deny(reason: String): Validation.Denied =
            Validation.Denied(ShellDeny.BLACKLISTED, reason)
        // F-backslash：候選含 `\` 即拒。FileScope.normalize 會把 `\` 轉 `/`（Windows 相容口徑），
        // 但 Linux/Android 上 `\` 是合法檔名字元、非分隔符；門若驗 normalize 後路徑、
        // spawn 用原始操作數，即驗用分叉。Linux 檔名含 `\` 極罕見，fail-closed 誤殺可接受。
        if (candidate.contains('\\')) {
            return deny("backslash denied (fail-closed, no normalization): $candidate")
        }
        // 0. F1：原始含 `..` 即拒，不先做詞法 normalize 折疊。
        // 刻意冗餘：外層 checkPathArgs 已先拒同條件（見 checkPathArgs 內 hasDotDotSegment 外層檢查）；保留作縱深。
        if (hasDotDotSegment(candidate)) {
            return deny("dotdot segment denied (reason=DOTDOT): $candidate")
        }
        // 1. 逐段 NOFOLLOW 三態：候選鏈 + 私有根鏈（防別名根讓詞法前綴失效）。
        // LINK→deny(symlink denied)；UNKNOWN→deny(symlink check failed)；
        // ABSENT/PRESENT→繼續（ABSENT 等同舊 false 語義；PRESENT 繼續；超深仍拒）。
        for (target in listOf(candidate, privateRoot)) {
            val norm = try {
                FileScope.normalize(target)
            } catch (_: Exception) {
                return deny("symlink check failed: $candidate")
            }
            if (!norm.startsWith("/")) return deny("symlink check failed: $candidate")
            val parts = norm.split("/").filter { it.isNotEmpty() }
            var cur = "/"
            // 根自身先驗（罕見但 fail-closed；三態）。
            when (lstatNoFollow(Paths.get(cur))) {
                LstatState.LINK -> return deny("symlink denied: $candidate")
                LstatState.UNKNOWN -> return deny("symlink check failed: $candidate")
                LstatState.ABSENT, LstatState.PRESENT -> Unit
            }
            for (seg in parts) {
                cur = if (cur == "/") "/$seg" else "$cur/$seg"
                when (lstatNoFollow(Paths.get(cur))) {
                    LstatState.LINK -> return deny("symlink denied: $candidate")
                    LstatState.UNKNOWN -> return deny("symlink check failed: $candidate")
                    LstatState.ABSENT, LstatState.PRESENT -> Unit
                }
                // 步數上限外的超深路徑 fail-closed（不應發生，防病態輸入）。
                if (cur.length > 4096) return deny("symlink check failed: $candidate")
            }
        }
        // 測試 seam：步驟 1 迴圈後、exists 步前調用（lstat 後置換路徑段用）。
        preRealPathHook?.invoke()
        // 2./3. 三態 exists＋實體固定 + 同語義重裁；確定缺席才降級（見 KDoc）。
        val normCandidate = try {
            FileScope.normalize(candidate)
        } catch (_: Exception) {
            return deny("symlink check failed: $candidate")
        }
        when (lstatNoFollow(Paths.get(normCandidate))) {
            LstatState.ABSENT -> return null
            LstatState.LINK -> return deny("symlink denied: $candidate")
            LstatState.UNKNOWN -> return deny("symlink check failed: $candidate")
            LstatState.PRESENT -> Unit
        }
        val real = try {
            Paths.get(normCandidate).toRealPath().toString()
        } catch (_: Exception) {
            return deny("symlink check failed: $candidate")
        }
        // F3/WARN1 elevated real 身分獨立條件（雙通道）：未經連結則 real 恆等於
        // 正規候選；經連結（lstat 後換入跨域 symlink）即使橋接已授權亦拒。
        // hardlink/mount 不改字串故仍走舊語義；併發 rename 到他處轉為拒絕。
        if (real != normCandidate) return deny("real identity mismatch (symlink/rename, fail-closed): $candidate")
        val realDecision = try {
            FileScope.decide(real, privateRoot, safRoots, flavor, bridgeGranted)
        } catch (_: Exception) {
            return deny("cross-domain denied: $candidate")
        }
        val realOk = if (isElevated) realDecision.allowed else (realDecision.allowed && !realDecision.needsBridge)
        if (!realOk) return deny("cross-domain denied: $candidate")
        return null
    }

    /**
     * 提權語義的檔案域檢查（S3，BACKLOG D09）。
     *
     * 先走 [FileScope.decide]：私有域 / 已授權 SAF 直行；跨域僅當回
     * needsBridge 且已授權（[FileScope.decide] 內已按風味與
     * bridgeGranted 裁決，`allowed == true` 即放行條件）才放行，
     * 其餘跨域仍拒。無作用域（privateRoot == null）fail-closed。
     * 詞法通過後再走同一實體解析門（見 [checkPhysicalOperandGate]；
     * B1：提權不共享詞法 allow 就放行，real 同樣按提權語義重裁）。
     */
    private fun checkFileScopeElevated(
        path: String,
        privateRoot: String?,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
        preRealPathHook: (() -> Unit)? = null,
    ): Validation.Denied? {
        // 刻意冗餘：外層 checkPathArgs 已先拒同條件；保留作縱深（與 checkFileScope/checkPhysicalOperandGate 同）。
        if (hasDotDotSegment(path)) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "dotdot segment denied (reason=DOTDOT): $path",
            )
        }
        if (privateRoot == null) {
            return Validation.Denied(
                ShellDeny.BLACKLISTED,
                "absolute path denied without scope: $path",
            )
        }
        val decision = FileScope.decide(path, privateRoot, safRoots, flavor, bridgeGranted)
        if (decision.allowed) return checkPhysicalOperandGate(
            candidate = path,
            privateRoot = privateRoot,
            safRoots = safRoots,
            flavor = flavor,
            bridgeGranted = bridgeGranted,
            isElevated = true,
            preRealPathHook = preRealPathHook,
        )
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

/** 拒絕原因碼（對應 D05「白名單/黑名單/配額」三類門禁 + 參數衛生 + 未驗證 quarantine 閂）。 */
enum class ShellDeny {
    EMPTY_COMMAND,
    NOT_WHITELISTED,
    BLACKLISTED,
    BAD_ARGUMENT,
    QUOTA_EXCEEDED,
    /**
     * Round-3 Phase E（P1-1，未驗證死亡 process 持久 quarantine）：
     * quarantine 非空期間一切新 privileged spawn 一律 fail-closed 拒絕。
     * 獨立原因碼，不與 [QUOTA_EXCEEDED] 的 backlog／breaker／併發三因混碼
     * （verdict 仍 `DENY`，見提權 verdict 坍縮表；僅 github 風味提權通道使用）。
     */
    QUARANTINE,
}

/** [ShellPolicy.validate] 的回執：放行攜正規化後的二進位名，拒絕攜原因。 */
sealed interface Validation {
    data class Allowed(val binary: String) : Validation
    data class Denied(val reason: ShellDeny, val message: String) : Validation
}

/** 截斷結果：[truncated] 為 true 表示原文更長，已丟棄尾部。 */
data class Truncated(val bytes: ByteArray, val truncated: Boolean)
