package dev.librepocket.shell

import dev.librepocket.files.FileScope
import java.nio.file.Files
import java.nio.file.LinkOption
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
 *   發行版 shim（如 `/bin/echo -> .../coreutils/echo`）屬部署事實而非攻擊者
 *   可控（可信目錄 root-owned，App 不可寫；攻擊者能寫可信目錄即已等同替換
 *   二進位本體），故跟隨後驗實體；dangling / 環 / 非正規檔 / 不可執行一律拒。
 * - multicall（toybox / toolbox / busybox，見 [MULTICALL_BINARIES]）：實體
 *   basename 落此顯式表時，spawn 形狀為 `[realPath, applet, ...args]`——
 *   applet 取自 `argv[0]` 的 basename（[ShellPolicy.basename]），其值已由
 *   [ShellPolicy.validate] 白名單放行（`Allowed.binary`），本函數只負責
 *   「是否為 multicall」的訊號，不擴大白名單。非表內（即使 link 名與實體名
 *   不同，如版本化 link）一律維持 `[realPath, ...args]`，不插入。
 *   判定只認實體 basename 的精確集合成員，不用「名不同即插」的籠統規則。
 *   toolbox 在部分舊版本／廠商改版上的 applet 傳參語義（`[real, applet, ...]`
 *   是否被接受）未經實機驗證，此處為保留說明，待 Android 實機覆蓋。
 * - 殘餘 TOCTOU：[resolve] 的實體驗證與 `ProcessBuilder.start()` 非原子，
 *   二進位替換 / link 置換競態窗口仍然存在（可信目錄 root-owned、App 不可寫，
 *   故不在本威脅模型內；參 scoped-file 分支的 check-then-act 揭露）。
 * - 最小乾淨 env（[CLEAN_ENV]）：固定 `PATH` + `LANG`，其餘不繼承
 *   （`LD_PRELOAD` / `LD_LIBRARY_PATH` / `PROOT_*` / 代理變數等經 `clear()` 消除）。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言。
 * 提權通道（`validateElevated` / Root / Shizuku）與 Linux guest 通道
 * （`ProotExec` + `LinuxEnv.GUEST_ENV`）依本次範圍不動，只用本檔案的詞法門
 * （經 [ShellPolicy.validate] 共享路徑，僅加嚴不放寬）。
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
     * 實體驗證：存在 → `toRealPath()` 全解析固定 → NOFOLLOW 驗正規檔 + 可執行，
     * 回傳固定後的實體路徑（spawn 直接用此路徑，不再經 link）。
     * dangling / 環 / 指向非正規檔 / 不可執行 / 任何異常一律 null。
     */
    fun verifiedTarget(candidate: String): String? {
        return try {
            val p = Paths.get(candidate)
            if (!Files.exists(p)) return null
            val real = p.toRealPath()
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null
            if (!Files.isExecutable(real)) return null
            real.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `argv[0]` → 已驗證 executable（spawn 前唯一解析入口，不查 `PATH`、不看 cwd）。
     *
     * - 含 `/`/`\`：父目錄必須先過詞法可信門（預設 [TRUSTED_BIN_DIRS]，
     *   經 [searchDirs] 覆寫，見下），再經 [verifiedTarget] 實體驗證；
     *   相對含 `/`（`./ls`、`chat/ls`）一律 null。
     * - bare：只在 [searchDirs]（預設 [TRUSTED_BIN_DIRS]，固定順序）內找；
     *   候選必須仍落在該 dir 下。呼叫方不得傳入非受控目錄（產品碼一律用預設值；
     *   單測可注入暫存目錄；絕對 `argv[0]` 的詞法門同表覆寫，使絕對路徑的
     *   全鏈（validate → resolve → spawn）可在暫存 fixture 下受測，
     *   而不必寫入系統目錄）。
     * - multicall：實體 basename ∈ [MULTICALL_BINARIES] 時回 [ResolvedExec]
     *   攜 `applet = argv[0]` 的 basename（呼叫方須先經 [ShellPolicy.validate]
     *   白名單放行，本函數不管白名單）；其餘回 `applet = null`。
     * - 本函數不管白名單（由 [ShellPolicy.validate] 先判）；回 null 呼叫方必須
     *   拒絕且不建子進程。
     */
    fun resolve(argv0: String, searchDirs: List<String> = TRUSTED_BIN_DIRS): ResolvedExec? {
        val raw = argv0.trim()
        if (raw.isEmpty() || raw.contains('\u0000')) return null
        if (raw.contains('/') || raw.contains('\\')) {
            if (!isTrustedAbsoluteArgv0(raw, searchDirs)) return null
            val real = verifiedTarget(FileScope.normalize(raw)) ?: return null
            return toResolved(raw, real)
        }
        for (dir in searchDirs) {
            val dirNorm = dir.trim().let {
                if (it.isEmpty() || it.contains('\u0000')) return@let null
                FileScope.normalize(it)
            } ?: continue
            val candidate = FileScope.normalize("$dirNorm/$raw")
            if (candidate == dirNorm || !candidate.startsWith("$dirNorm/")) continue
            val hit = verifiedTarget(candidate) ?: continue
            return toResolved(raw, hit)
        }
        return null
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
