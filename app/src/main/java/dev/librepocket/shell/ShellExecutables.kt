package dev.librepocket.shell

import dev.librepocket.files.FileScope
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 可信 executable 解析（直接 shell 通道的 `PATH` 劫持封堵）。
 *
 * 背景：[ShellPolicy.validate] 的放行只認 basename，`argv[0]` 又豁免檔案域，
 * 而實際執行目標由 OS 經 `PATH`/cwd 解析 —— `/tmp/evil/ls` 憑 basename 即可
 * 通過白名單，bare `ls` 的落地目標則取決於宿主 `PATH`。本對象把「邏輯命令」
 * 變成「驗證後的絕對路徑」：
 * - 詞法門（[isTrustedAbsoluteArgv0]，純函數無 IO）：含分隔符的 `argv[0]`
 *   父目錄必須在 [TRUSTED_BIN_DIRS] 內；
 * - 實體系結（[resolve]，spawn 前）：bare 只在受控目錄按固定順序找，
 *   不查 `PATH`、不看 cwd；候選經 `toRealPath()` 固定後再以 NOFOLLOW
 *   驗正規檔 + 可執行，spawn 直接用固定後的實體路徑。
 * - symlink 取捨：link 本體必須位於可信位置（詞法門），實體解析後再驗。
 *   實體另加兩道閘（見 [verifiedTarget]，review finding 3 S3）：
 *   (1) containment：real 必須仍落在允許前綴集合內（預設由本次 [resolve] 的
 *   `searchDirs` real 化快照 + [SYSTEM_REAL_PREFIXES] 組成；單測可經 `allowedRoots`
 *   注入）。發行版合併（如 `/bin -> usr/bin`）與同集內 shim 因 real 仍在集內而
 *   放行；指到集外 app-writable 目標（App 私有域、`/data/local/tmp` 等）一律拒。
 *   (2) app-writability：real 本體及其 parent chain 對 App uid 必須不可寫
 *   （經 [defaultIsWritable]，即 `Files.isWritable`；任何異常視為可寫而拒，
 *   fail-closed；`getOwner`/POSIX 在部分 FS 會拋異常故不用）。
 *   dangling / 環 / 非正規檔 / 不可執行 / 逃逸 / 可寫一律拒。
 * - entry-side 閘（re-review Finding A，S5）：[verifiedTarget] 另查 lexical
 *   entry chain —— candidate 本體及其 lexical parent chain「向上至所屬 searchDir
 *  （含量 searchDir 本體）」對 App uid 必須不可寫（同一 [isWritable] 注入判定，
 *   判定拋異常視為可寫而拒，fail-closed，保持 hermetic 可測）。上界走到所屬
 *   searchDir 即停，不走到 FS 根（否則 host fixture 的 `/tmp` 可寫必殺一切）。
 *   可寫 searchDir 內放 `ls -> /system/bin/sh`（real 乾淨但非 allowlisted）即拒，
 *   阻斷「allowlisted `ls` 落地非 allowlisted `sh`」的 spawn。searchDir 本身若為
 *   symlink（如 `/bin -> usr/bin`），所屬比對與停止條件用 real 化後路徑
 *   （lexical 相等或 real 相等即停；既有 usrMergeShape 正例保持綠）。
 *   toybox 合法形（entry != real）在 entry chain 全乾淨時放行不受影響；
 *   絕對 `argv[0]` 形走同一 [verifiedTarget] 同一閘，不分叉。
 * - searchDir 守衛（S5＋Stage2 Finding B，部署前提的 runtime 守衛）：
 *   驗本體 real 化後路徑及其祖先至 FS 根（含本體）：任一可寫／判定異常即
 *   不可信。Linux rename 語義下本體 0555 不代表可寫 parent 不能整目錄替換，
 *   故只驗本體不足；本守衛把「searchDir 及其祖先不可被 App 替換」變成執行時
 *   檢查。bare 按固定順序逐項驗（Stage1 窄化）：可寫項跳過（永不執行其候選），
 *   命中乾淨項時若前序出現過可寫項即拒（防 shadowing 混淆）；命中後無關可寫
 *   不污染。絕對 `argv[0]` 只驗所屬 parent 及其祖先。
 *   生產系統目錄及其祖先對 App 恆不可寫；exotic ROM 降級為拒，須回報擴表、
 *   不得放寬。宿主 fixture 在 `/tmp` 下時祖先可寫，單測須注入 `systemOwned`
 *   模擬裝置不可寫（與 entry/real 閘同式），真查即 fail-closed 拒。
 * - multicall（toybox / toolbox / busybox，見 [MULTICALL_BINARIES]）：實體
 *   basename 落此顯式表時，spawn 形狀為 `[realPath, applet, ...args]`——
 *   applet 取自 `argv[0]` 的 basename（[ShellPolicy.basename]），其值已由
 *   [ShellPolicy.validate] 白名單放行（`Allowed.binary`），本函數只負責
 *   「是否為 multicall」的訊號，不擴大白名單。非表內（即使 link 名與實體名
 *   不同，如版本化 link）一律維持 `[realPath, ...args]`，不插入。
 *   判定只認實體 basename 的精確集合成員，不用「名不同即插」的籠統規則。
 *   toolbox 在部分舊版本／廠商改版上的 applet 傳參語義（`[real, applet, ...]`
 *   是否被接受）未經實機驗證，此處為保留說明，待 Android 實機覆蓋。
 * - 殘餘 TOCTOU（誠實揭露）：[resolve] 的實體驗證與 `ProcessBuilder.start()` 非原子，
 *   check-then-act 窗口仍在：驗證通過到 spawn 前的二進位替換 / link 置換仍可能發生。
 *   直接通道沒有可持的容器鎖（不像 scoped-file 可持 fd/lock 語義），spawn 前緊貼重驗
 *   最多縮小窗口、不能消除。舊註解「可信目錄 root-owned、App 不可寫故無 TOCTOU」
 *   只是部署假設而非驗證事實：「不可寫」現已改為執行時檢查（real 閘 + S5 entry 閘
 *   + searchDir 本體守衛），但擁有者語義在部分 FS 不可靠、窗口仍在，故仍列殘餘風險。
 *   S5 entry 閘範圍一併揭露：覆 lexical entry chain（含 candidate 本體至所屬
 *   searchDir）+ real chain（real 本體至 FS 根）；兩者之間的「中間層 link 置換」
 *   （entry 下的子目錄 symlink 在驗證與 spawn 間被換指，或 parent  component 在
 *   lexical 正規化後、real 固定前被置換）仍在窗口內，不能消除。
 * - 已知限制（fail-closed 可能誤殺）：real 前綴快照無法窮舉所有裝置的 overlay /
 *   APEX 實體路徑；落在快照 + [SYSTEM_REAL_PREFIXES] 之外的合法系統二進位會被拒
 *   （安全但可能誤殺 exotic ROM）。如遇此類裝置應回報並擴表，不得放寬為無 containment。
 * - 最小乾淨 env（[CLEAN_ENV]）：固定 `PATH` + `LANG`，其餘不繼承
 *   （`LD_PRELOAD` / `LD_LIBRARY_PATH` / `PROOT_*` / 代理變數等經 `clear()` 消除）。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言。
 * 提權通道（`validateElevated` / Root / Shizuku）與 Linux guest 通道
 * （`ProotExec` + `LinuxEnv.GUEST_ENV`）依本次範圍不動，只用本檔案的詞法門
 * （經 [ShellPolicy.validate] 共享路徑，僅加嚴不放寬）；guest 不調 [resolve]，
 * 故 containment / writability 閘不影響 guest（S1 行為保持）。
 */
object ShellExecutables {

    /** 受控搜尋目錄（固定順序；Android 系統目錄在前，Linux 發行版目錄在後）。 */
    val TRUSTED_BIN_DIRS: List<String> = listOf(
        "/system/bin",
        "/system/xbin",
        "/vendor/bin",
        "/odm/bin",
        "/bin",
        "/usr/bin",
        "/usr/local/bin",
    )

    /**
     * 系統實體前綴（containment 允許集的第二部分，見 [snapshotRealRoots]）：
     * 正常系統 symlink 的 real 落點若逃出 bin 目錄樹（如 APEX 下的
     * `/apex/.../bin/...`、system overlay 合併路徑），靠此表放行，避免把部屬
     * 事實誤殺。表內全為系統分區（App 正常不可寫），真正的可寫逃逸仍由
     * writability 閘擋下；不在快照亦不在此表的 real 落點一律 fail-closed 拒
     * （exotic ROM 誤殺風險見檔案 KDoc，須回報擴表、不得放寬）。
     */
    val SYSTEM_REAL_PREFIXES: List<String> = listOf(
        "/system",
        "/vendor",
        "/odm",
        "/oem",
        "/product",
        "/system_ext",
        "/apex",
    )

    /** 直接通道固定 `PATH`（[TRUSTED_BIN_DIRS] 串接，不取宿主 `PATH`）。 */
    val CLEAN_PATH: String = TRUSTED_BIN_DIRS.joinToString(":")

    /**
     * 直接通道最小乾淨 env：固定 [CLEAN_PATH] + `LANG`，其餘一律不繼承。
     * 真實 runner 遇 null 即回落此表（見 [DefaultProcessRunner]）；
     * [RestrictedShell] 顯式傳此表，不再傳 null。
     */
    val CLEAN_ENV: Map<String, String> = mapOf(
        "PATH" to CLEAN_PATH,
        "LANG" to "C.UTF-8",
    )

    /** 目錄是否為受控可信目錄（詞法比對，不碰 FS）。 */
    fun isTrustedBinDir(dir: String): Boolean {
        val trimmed = dir.trim()
        if (trimmed.isEmpty() || trimmed.contains('\u0000')) return false
        return FileScope.normalize(trimmed) in TRUSTED_BIN_DIRS
    }

    /**
     * 明確 multicall 表面（精確集合，拒絕模糊判定）：
     * 只有實體 basename 落在此表時，spawn 才需還原 applet（`[real, applet, ...]`）。
     * 版本化／改名二進位（如 `toybox-arm`）不在此表，一律不插入。
     */
    val MULTICALL_BINARIES: Set<String> = setOf("toybox", "toolbox", "busybox")

    /**
     * 已解析 executable：[path] 為驗證後實體絕對路徑（spawn 的 `argv[0]`）；
     * [applet] 僅 multicall（實體 basename ∈ [MULTICALL_BINARIES]）非 null，
     * 取自原始 `argv[0]` 的 basename（見 [resolve]），呼叫方 spawn 時置於
     * `argv[1]` 還原被 link 吞掉的子命令名；非 multicall 為 null，spawn 不插入。
     */
    data class ResolvedExec(val path: String, val applet: String?)

    /**
     * 解析拒絕細項（可觀測但不洩敏：僅 reason code，不含任意 filesystem path；
     * 呼叫方訊息只可攜帶原始 `argv[0]` 與本 code，不得內插搜尋目錄／實體路徑）。
     */
    enum class ResolveDeny {
        INVALID_INPUT,
        UNTRUSTED_PATH,
        SEARCH_DIR_UNTRUSTED,
        OUTSIDE_ROOTS,
        REAL_WRITABLE,
        ENTRY_WRITABLE,
        NOT_FOUND,
    }

    /** [resolveDetailed] 回執：成功攜 [ResolvedExec]，失敗攜 [ResolveDeny]。 */
    sealed interface ResolveOutcome {
        data class Ok(val exec: ResolvedExec) : ResolveOutcome
        data class Denied(val reason: ResolveDeny) : ResolveOutcome
    }

    /**
     * `argv[0]` 是否為可信絕對路徑（詞法，不碰 FS）：
     * 以 `/` 起頭、無 `\`、父目錄在可信目錄內、檔名非空。
     * `..` 經 [FileScope.normalize] 折疊後再判（`/bin/../tmp/x` 即現形）。
     * bare 名（無 `/`）回 false（由 [resolve] 走受控搜尋，不在此判）。
     *
     * @param trustedDirs 可信目錄表（預設 [TRUSTED_BIN_DIRS]；產品碼一律用預設值，
     *   單測可注入暫存目錄以覆蓋絕對 `argv[0]` 的全鏈路徑）。
     */
    fun isTrustedAbsoluteArgv0(argv0: String, trustedDirs: List<String> = TRUSTED_BIN_DIRS): Boolean {
        val raw = argv0.trim()
        if (raw.isEmpty() || raw.contains('\u0000') || raw.contains('\\')) return false
        if (!raw.startsWith("/")) return false
        val norm = FileScope.normalize(raw)
        if (!norm.startsWith("/")) return false
        val slash = norm.lastIndexOf('/')
        if (slash < 0) return false
        val parent = norm.substring(0, slash).ifEmpty { "/" }
        val name = norm.substring(slash + 1)
        if (name.isEmpty() || name == "." || name == "..") return false
        return parent in trustedDirs.map { FileScope.normalize(it.trim()) }
    }

    /**
     * NOFOLLOW 可執行驗證（嚴格：終端元件為 symlink 即 false，不跟隨）。
     * dangling / 非正規檔 / 目錄 / 不可執行一律 false；任何異常 fail-closed。
     */
    fun isExecutableNoFollow(candidate: String): Boolean {
        return try {
            val p = Paths.get(candidate)
            !Files.isSymbolicLink(p) &&
                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) &&
                Files.isExecutable(p)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * App-uid 可寫判定（writability 閘的預設實現）：
     * 回 true 表示「App 可寫 → 呼叫方必須拒」。`Files.isWritable` 抛異常時
     * 回 true（fail-closed；部分 FS 的屬主/POSIX 語義不可靠，不另取 owner）。
     * 單測可注入假判定以模擬「系統自帶不可寫」（宿主暫存檔屬主可寫，直接用
     * 預設值會全拒；見 `ShellTrustedExecTest` 的注入註解）。
     */
    fun defaultIsWritable(p: Path): Boolean {
        return try {
            Files.isWritable(p)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 允許前綴快照：把 [dirs] 逐一 real 化（存在即 `toRealPath` 全解析，覆蓋
     * `/bin -> usr/bin` 類合併；不存在/異常即退回詞法正規化，不抛），再併入
     * [SYSTEM_REAL_PREFIXES] 去重。best-effort：快照時與驗證時之間的 FS 變化
     * 仍屬 TOCTOU 窗口（見檔案 KDoc），呼叫方不得快照一次長期重用。
     */
    fun snapshotRealRoots(dirs: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (d in dirs) {
            val t = d.trim()
            if (t.isEmpty() || t.contains('\u0000')) continue
            val real = try {
                val p = Paths.get(FileScope.normalize(t))
                if (Files.exists(p)) p.toRealPath().toString() else FileScope.normalize(t)
            } catch (_: Exception) {
                FileScope.normalize(t)
            }
            out.add(real)
        }
        for (s in SYSTEM_REAL_PREFIXES) out.add(s)
        return out.toList()
    }

    /**
     * real 是否落在允許前綴內（邊界感知：`==` 或 `root/…` 前綴；純詞法比對，
     * 呼叫方保證傳入者皆為已 real 化路徑）。任何異常回 false（fail-closed）。
     */
    fun isUnderRoots(realPath: String, roots: List<String>): Boolean {
        return try {
            val r = FileScope.normalize(realPath)
            roots.any { root ->
                val n = FileScope.normalize(root.trim())
                if (n.isEmpty()) false
                else r == n || r.startsWith(n.trimEnd('/') + "/")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * real 本體及其 parent chain 是否全不可寫（writability 閘本體）：
     * 任一層 [isWritable] 回 true 即回 false（拒）；判定抛異常視為可寫
     * （fail-closed）。走到 FS 根為止；空鏈視為可寫（fail-closed，不應發生）。
     */
    private fun chainNonWritable(real: Path, isWritable: (Path) -> Boolean): Boolean {
        return try {
            var cur: Path? = real
            var seen = false
            while (cur != null) {
                seen = true
                val w = try {
                    isWritable(cur)
                } catch (_: Exception) {
                    true
                }
                if (w) return false
                cur = cur.parent
            }
            seen
        } catch (_: Exception) {
            false
        }
    }

    /**
     * S5 entry-side 閘本體：lexical candidate 本體及其 parent chain「向上至所屬
     * searchDir（含本體）」是否全不可寫。任一層 [isWritable] 回 true 即回 false
     * （拒）；判定抛異常視為可寫（fail-closed）。上界走到 [entryRootNorm] 即停
     * （含本體），不走到 FS 根；candidate 非所屬（lexical 非 `==`／`root/…` 且
     * real 回退亦非所屬）即回 false（fail-closed）。
     *
     * searchDir 本身若為 symlink（如 `/bin -> usr/bin`）：停止條件用 real 化後
     * 比對（lexical 相等或任一層 real 等於 entry real 即停），既有 usrMergeShape
     * 正例保持綠。檢查本身走 lexical 路徑（[isWritable] 預設真查跟隨 link，
     * 與 real 檢查等效；注入判定保持 hermetic）。
     */
    private fun entryChainNonWritable(
        candidateNorm: String,
        entryRootNorm: String,
        isWritable: (Path) -> Boolean,
    ): Boolean {
        return try {
            if (candidateNorm.isEmpty() || candidateNorm.contains('\u0000')) return false
            if (entryRootNorm.isEmpty() || entryRootNorm.contains('\u0000')) return false
            if (!candidateNorm.startsWith("/")) return false
            if (!entryRootNorm.startsWith("/")) return false
            val entryTrimmed = entryRootNorm.trimEnd('/')
            val entryKey = entryTrimmed.ifEmpty { "/" }
            // 所屬預檢（fail-closed）：lexical 含 real 回退。
            val lexicalOwned =
                candidateNorm == entryRootNorm || candidateNorm.startsWith("$entryKey/")
            var realOwned = false
            val entryRealNorm: String? = try {
                val erp = Paths.get(entryRootNorm)
                if (Files.exists(erp)) erp.toRealPath().toString() else null
            } catch (_: Exception) {
                null
            }
            if (!lexicalOwned && entryRealNorm != null) {
                val parentLex = try {
                    Paths.get(candidateNorm).parent?.toString()
                } catch (_: Exception) {
                    null
                }
                if (parentLex != null) {
                    val parentReal: String? = try {
                        val pp = Paths.get(FileScope.normalize(parentLex))
                        if (Files.exists(pp)) pp.toRealPath().toString() else null
                    } catch (_: Exception) {
                        null
                    }
                    if (parentReal != null) {
                        val entryRealKey = entryRealNorm.trimEnd('/').ifEmpty { "/" }
                        realOwned =
                            parentReal == entryRealNorm || parentReal.startsWith("$entryRealKey/")
                    }
                }
            }
            if (!lexicalOwned && !realOwned) return false
            // 由 candidate 向上逐層檢查，至 entryRoot（含）即停。
            var curNorm: String? = candidateNorm
            var steps = 0
            while (curNorm != null) {
                if (steps++ > 256) return false
                val w = try {
                    isWritable(Paths.get(curNorm))
                } catch (_: Exception) {
                    true
                }
                if (w) return false
                if (curNorm == entryRootNorm) return true
                if (entryRealNorm != null) {
                    val curReal: String? = try {
                        val cp = Paths.get(curNorm)
                        if (Files.exists(cp)) cp.toRealPath().toString() else null
                    } catch (_: Exception) {
                        null
                    }
                    if (curReal != null && curReal == entryRealNorm) return true
                }
                val parent = try {
                    Paths.get(curNorm).parent?.toString()
                } catch (_: Exception) {
                    null
                }
                curNorm = if (parent == null) null else FileScope.normalize(parent)
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 實體驗證：存在 → `toRealPath()` 全解析固定 → NOFOLLOW 驗正規檔 + 可執行 →
     * containment（real 落在 [allowedRoots] 內）→ real writability（real 本體及
     * parent chain 對 App uid 不可寫）→ S5 entry 閘（[entryRoot] 非 null 時，
     * lexical candidate 本體及 parent chain 向上至所屬 searchDir 含本體不可寫），
     * 回傳固定後的實體路徑（spawn 直接用此路徑，不再經 link）。dangling / 環 /
     * 指向非正規檔 / 不可執行 / 逃逸 / 可寫（任一閘）/ 任何異常一律 null。
     *
     * @param allowedRoots containment 前綴；null 表跳過 containment（僅既有直接
     *   呼叫相容用；[resolve] 一律傳非 null，生產路徑必查）。單測可注入暫存
     *   目錄快照；產品碼經 [resolve] 預設即快照（見 [snapshotRealRoots]）。
     * @param isWritable app-uid 可寫判定（預設 [defaultIsWritable] 真查 FS；
     *   單測可注入以模擬系統自帶不可寫）。
     * @param entryRoot 所屬 searchDir（lexical 正規化形；[resolve] 的 bare 傳當輪
     *   `dirNorm`，絕對 `argv[0]` 傳其 lexical parent）。非 null 即加驗 entry chain
     *   （含 searchDir 本體，上界即停，不走 FS 根）；null 表跳過 entry 閘（僅既有
     *   直接呼叫相容用；生產一律經 [resolve] 傳非 null，必查）。bare 與絕對同閘，
     *   不分叉。
     */
    fun verifiedTarget(
        candidate: String,
        allowedRoots: List<String>? = null,
        isWritable: (Path) -> Boolean = ::defaultIsWritable,
        entryRoot: String? = null,
    ): String? {
        return verifiedTargetDetailed(candidate, allowedRoots, isWritable, entryRoot).first
    }

    /**
     * [verifiedTarget] 的細項版：成功回 `Pair(real, null)`，失敗回 `Pair(null, reason)`。
     * - `NOT_FOUND`：不存在／解析失敗／非正規檔／不可執行／非法輸入（含非所屬 entryRoot）。
     * - `OUTSIDE_ROOTS`：containment 不通過。
     * - `REAL_WRITABLE`：real 本體或 parent chain 可寫／判定異常。
     * - `ENTRY_WRITABLE`：lexical entry chain 可寫／判定異常。
     */
    fun verifiedTargetDetailed(
        candidate: String,
        allowedRoots: List<String>? = null,
        isWritable: (Path) -> Boolean = ::defaultIsWritable,
        entryRoot: String? = null,
    ): Pair<String?, ResolveDeny?> {
        return try {
            val p = Paths.get(candidate)
            if (!Files.exists(p)) return null to ResolveDeny.NOT_FOUND
            val real = try {
                p.toRealPath()
            } catch (_: Exception) {
                return null to ResolveDeny.NOT_FOUND
            }
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null to ResolveDeny.NOT_FOUND
            if (!Files.isExecutable(real)) return null to ResolveDeny.NOT_FOUND
            if (allowedRoots != null && !isUnderRoots(real.toString(), allowedRoots)) {
                return null to ResolveDeny.OUTSIDE_ROOTS
            }
            if (!chainNonWritable(real, isWritable)) return null to ResolveDeny.REAL_WRITABLE
            if (entryRoot != null) {
                val entryNorm = try {
                    FileScope.normalize(entryRoot.trim())
                } catch (_: Exception) {
                    return null to ResolveDeny.NOT_FOUND
                }
                if (entryNorm.isEmpty() || entryNorm.contains('\u0000')) return null to ResolveDeny.NOT_FOUND
                if (!entryChainNonWritable(FileScope.normalize(candidate), entryNorm, isWritable)) {
                    return null to ResolveDeny.ENTRY_WRITABLE
                }
            }
            real.toString() to null
        } catch (_: Exception) {
            null to ResolveDeny.NOT_FOUND
        }
    }

    /**
     * 搜尋目錄是否不可信（本體或祖先可寫／判定異常／非法即 true，fail-closed）。
     * 本體經 real 化後驗；祖先沿 realDir parent 逐層至 FS 根（含本體，見 Stage2
     * Finding B：防整目錄 rename/replace——本體 0555 不代表可寫 parent 不能替換
     * 整個 entry dir）。任一層 [isWritable] 回 true 即不可信；判定拋異常視為
     * 可寫。步數上限 64，超限即不可信。
     * 宿主 fixture 在 `/tmp` 下時祖先可寫，須經 [isWritable] 注入
     * `systemOwned` 模擬裝置不可寫（與 entry/real 閘同式）；真查即 fail-closed 拒。
     */
    fun isSearchDirUntrusted(dirNorm: String, isWritable: (Path) -> Boolean): Boolean {
        return try {
            if (dirNorm.isEmpty() || dirNorm.contains('\u0000')) return true
            val realDir: Path = try {
                val p = Paths.get(dirNorm)
                if (Files.exists(p)) p.toRealPath() else p
            } catch (_: Exception) {
                return true
            }
            var cur: Path? = realDir
            var steps = 0
            var seen = false
            while (cur != null) {
                if (steps++ > 64) return true
                seen = true
                val w = try {
                    isWritable(cur)
                } catch (_: Exception) {
                    true
                }
                if (w) return true
                cur = cur.parent
            }
            !seen
        } catch (_: Exception) {
            true
        }
    }

    /**
     * `argv[0]` → 已驗證 executable（spawn 前唯一解析入口，不查 `PATH`、不看 cwd）。
     *
     * - 含 `/`/`\`：父目錄必須先過詞法可信門（預設 [TRUSTED_BIN_DIRS]，
     *   經 [searchDirs] 覆寫，見下），再經 [verifiedTarget] 實體驗證
     *   （含 containment + real writability + S5 entry 閘，允許集見下，
     *   entryRoot 取 lexical parent；bare 與絕對同閘不分叉）；
     *   相對含 `/`（`./ls`、`chat/ls`）一律 null。
     *   絕對形只驗其所屬 parent 及其祖先（[isSearchDirUntrusted]），不受其他
     *   無關搜尋項污染；其他搜尋項可寫不影響乾淨絕對路徑（fail-closed 仍禁
     *   執行可寫候選，見下）。entry 閘上界止於所屬 searchDir，其上祖先由本
     *   searchDir 祖先檢查補足，故不擴 entry 上界至根。
     * - bare：只在 [searchDirs]（預設 [TRUSTED_BIN_DIRS]，固定順序）內找；
     *   候選必須仍落在該 dir 下（當輪 `dirNorm` 即 entryRoot）。呼叫方不得傳入
     *   非受控目錄（產品碼一律用預設值；單測可注入暫存目錄；絕對 `argv[0]` 的
     *   詞法門同表覆寫，使絕對路徑的全鏈（validate → resolve → spawn）可在暫存
     *   fixture 下受測，而不必寫入系統目錄）。
     * - searchDir 守衛（S5＋Stage2，部署前提的 runtime 守衛，Stage1 窄化）：
     *   不再入口整表預檢。bare 按固定順序逐項驗本體＋祖先至根：可寫／判定異常項直接
     *   跳過（永不執行其候選），命中乾淨項時若其前序出現過可寫項即整體拒
     *   （防 shadowing 混淆：可寫前序可植同名檔，雖本次未執行但搜尋順序已受
     *   污染）；命中項之後的無關可寫項不污染本次命中。絕對形只驗所屬 parent
     *   及其祖先。仍不退回環境 `PATH`、不執行任何可寫候選。非法表項跳過（不執行），
     *   不再整表拒。拒絕細項經 [ResolveDeny] 回報（僅 reason code，不含 path）。
     *   生產系統目錄及其祖先對 App 恆不可寫；exotic ROM 降級為拒，須回報擴表、不得放寬。
     * - containment 允許集：[allowedRoots] 非 null 即用（單測注入）；
     *   null 時由 [snapshotRealRoots]（[searchDirs] real 化 + [SYSTEM_REAL_PREFIXES]）
     *   現場快照。bare 與絕對 `argv[0]` 同表（S2「放行即能解析」不變）。
     * - writability 判定：[isWritable]（預設 [defaultIsWritable]；單測可注入
     *   以模擬系統自帶不可寫，宿主暫存檔屬主可寫故直接用預設值會全拒）。
     * - multicall：實體 basename ∈ [MULTICALL_BINARIES] 時回 [ResolvedExec]
     *   攜 `applet = argv[0]` 的 basename（呼叫方須先經 [ShellPolicy.validate]
     *   白名單放行，本函數不管白名單；multicall 不豁免 containment/writability/
     *   entry 閘，逃逸或 entry 可寫照拒；entry 全乾淨時合法形放行不受影響）；
     *   其餘回 `applet = null`。
     * - 本函數不管白名單（由 [ShellPolicy.validate] 先判）；回 null 呼叫方必須
     *   拒絕且不建子進程。需定位時用 [resolveDetailed] 取 [ResolveDeny]。
     */
    fun resolve(
        argv0: String,
        searchDirs: List<String> = TRUSTED_BIN_DIRS,
        allowedRoots: List<String>? = null,
        isWritable: (Path) -> Boolean = ::defaultIsWritable,
    ): ResolvedExec? {
        return when (val o = resolveDetailed(argv0, searchDirs, allowedRoots, isWritable)) {
            is ResolveOutcome.Ok -> o.exec
            is ResolveOutcome.Denied -> null
        }
    }

    /**
     * [resolve] 的細項版：成功回 `Ok`，失敗回 `Denied(reason)`（見 [ResolveDeny]，
     * 不含任意 filesystem path，呼叫方可安全寫入拒絕訊息）。
     */
    fun resolveDetailed(
        argv0: String,
        searchDirs: List<String> = TRUSTED_BIN_DIRS,
        allowedRoots: List<String>? = null,
        isWritable: (Path) -> Boolean = ::defaultIsWritable,
    ): ResolveOutcome {
        val raw = argv0.trim()
        if (raw.isEmpty() || raw.contains('\u0000')) return ResolveOutcome.Denied(ResolveDeny.INVALID_INPUT)
        val roots = allowedRoots ?: snapshotRealRoots(searchDirs)
        if (raw.contains('/') || raw.contains('\\')) {
            if (!isTrustedAbsoluteArgv0(raw, searchDirs)) {
                return ResolveOutcome.Denied(ResolveDeny.UNTRUSTED_PATH)
            }
            val normRaw = try {
                FileScope.normalize(raw)
            } catch (_: Exception) {
                return ResolveOutcome.Denied(ResolveDeny.INVALID_INPUT)
            }
            val slash = normRaw.lastIndexOf('/')
            if (slash < 0) return ResolveOutcome.Denied(ResolveDeny.INVALID_INPUT)
            val parentNorm = normRaw.substring(0, slash).ifEmpty { "/" }
            if (isSearchDirUntrusted(parentNorm, isWritable)) {
                return ResolveOutcome.Denied(ResolveDeny.SEARCH_DIR_UNTRUSTED)
            }
            val (real, failure) = verifiedTargetDetailed(normRaw, roots, isWritable, parentNorm)
            if (real == null) return ResolveOutcome.Denied(failure ?: ResolveDeny.NOT_FOUND)
            return toResolved(raw, real)?.let { ResolveOutcome.Ok(it) }
                ?: ResolveOutcome.Denied(ResolveDeny.INVALID_INPUT)
        }
        var taintedBeforeHit = false
        var lastFailure: ResolveDeny? = null
        for (dir in searchDirs) {
            val dirNorm = try {
                val t = dir.trim()
                if (t.isEmpty() || t.contains('\u0000')) continue
                FileScope.normalize(t)
            } catch (_: Exception) {
                continue
            }
            if (isSearchDirUntrusted(dirNorm, isWritable)) {
                taintedBeforeHit = true
                continue
            }
            val candidate = try {
                FileScope.normalize("$dirNorm/$raw")
            } catch (_: Exception) {
                continue
            }
            if (candidate == dirNorm || !candidate.startsWith("$dirNorm/")) continue
            val (hit, failure) = verifiedTargetDetailed(candidate, roots, isWritable, dirNorm)
            if (hit != null) {
                if (taintedBeforeHit) return ResolveOutcome.Denied(ResolveDeny.SEARCH_DIR_UNTRUSTED)
                return toResolved(raw, hit)?.let { ResolveOutcome.Ok(it) }
                    ?: ResolveOutcome.Denied(ResolveDeny.INVALID_INPUT)
            } else if (failure != null && failure != ResolveDeny.NOT_FOUND) {
                if (lastFailure == null) lastFailure = failure
            }
        }
        if (taintedBeforeHit) return ResolveOutcome.Denied(ResolveDeny.SEARCH_DIR_UNTRUSTED)
        return ResolveOutcome.Denied(lastFailure ?: ResolveDeny.NOT_FOUND)
    }

    /**
     * 實體路徑 → [ResolvedExec]：僅實體 basename 精確命中 [MULTICALL_BINARIES]
     * 才攜 applet（取自原始 `argv[0]` 的 basename；呼叫方保證該值已過白名單），
     * 其餘（版本化 link、改名二進位、同名實體）一律 `applet = null`。
     * multicall 且 basename 為空的畸形輸入 fail-closed 回 null。
     */
    private fun toResolved(argv0: String, realPath: String): ResolvedExec? {
        val realBase = realPath.substringAfterLast('/')
        if (realBase !in MULTICALL_BINARIES) return ResolvedExec(realPath, null)
        val applet = ShellPolicy.basename(argv0)
        if (applet.isEmpty()) return null
        return ResolvedExec(realPath, applet)
    }
}
