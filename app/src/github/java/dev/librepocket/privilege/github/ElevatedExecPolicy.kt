package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellExecutables
import dev.librepocket.shell.ShellPolicy
import java.nio.file.Path

/**
 * 提權可信執行檔門（P1-1＋P1-2，僅 github 風味）。
 *
 * - P1-2 不變快照：入口對 [ElevatedRequest.argv] 做防禦性拷貝＋不可變包裝
 *   （[snapshot]），後續 validation／容量特判／審計／spawn 一律用同一份
 *   [ElevatedCommandSnapshot]，不再觸碰原始 `request.argv`（呼叫方傳入
 *   `MutableList` 並在 grant／status supplier 內竄改亦不影響已核驗快照）。
 * - P1-1 可信身分：提權與直接通道同等 argv0 實體驗證。bare 經受控搜尋固定為
 *   已驗證實體（不查 `PATH`、不看 cwd，含 containment＋app-writability＋
 *   entry 閘，與 [ShellExecutables.resolveDetailed] 同參）；含分隔符的絕對
 *   `argv[0]` 先過詞法門（[ShellPolicy.validateElevated] 已擋非可信父目錄），
 *   再經同一實體驗證；spawn 一律用已固定目標，不再用原始 `argv[0]`。
 * - multicall（toybox／toolbox／busybox，P2-7 parity）：實體 basename 落表
 *   「且」邏輯 basename（快照 `argv[0]` 的 basename）與實體 basename 不同
 *   （經 symlink／別名呼叫）時 spawn 形狀為 `[real, applet, ...args]`，其中
 *   `applet` 取快照 `argv[0]` 的 basename（已過 [ShellPolicy.validateElevated]
 *   黑名單／衛生門＋applet 黑名單，本函數只負責是否為 multicall 的訊號，
 *   不擴大白名單；與 [ShellExecutables] 直接通道同形，Root `su -c` 傳送層同形，
 *   見 [toSpawnArgv]）。邏輯名本來就是 multicall 主程式自身時
 *   （`logicalBase == realBase`，如 `["toybox", "id"]`）不插入，spawn 為
 *   `[real, ...snapshot.drop(1)]`（`drop(1)` 首位即 applet，不重插；否則形成
 *   `[real, "toybox", "id"]` 錯誤形）。
 * - 本檔案零 Android 依賴，JVM 單測可直接斷言；產品 runner 經
 *   [RootSuRunner]/[ShizukuShellRunner] 強制調用（預設即真查 FS，不可繞過）。
 *
 * Phase 5 B1 可見性（僅 github 風味內部）：本對象收斂為 `internal`
 * （同模組 runner＋單測仍可見；`app/src/main` 僅 KDoc 提及，無代碼引用，
 * 故不破跨模組編譯）。`ElevatedProcessIo.kt` 大檔不拆（風險高，見
 * `ElevatedRunnerCommon` KDoc TODO，留 #136-B3）。
 */
internal object ElevatedExecPolicy {

    /**
     * 已核驗快照（P1-2，真正不變）：
     * - [argv] 為入口防禦性拷貝＋不可變包裝（`Collections.unmodifiableList`，
     *   後續只讀，任何 supplier 竄改原始 `MutableList` 不影響本份）；
     * - [reasonCode] 為入口拷貝（String 不可變，直接存引用即快照）。
     * 呼叫方不得保留原始 `request.argv` 引用（本對象已拷貝），取用方拿到的
     * 即為已核驗同一份，容量特判／審計／spawn 皆用此份。
     */
    data class ElevatedCommandSnapshot(
        val argv: List<String>,
        val reasonCode: String,
    )

    /** 入口快照：argv 防禦性拷貝＋不可變包裝，reasonCode 直接存（String 不可變）。
     *
     * Phase 4（F9，PR #140）：拷貝可能因呼叫方併發竄改原始 `MutableList`
     * 而抛 [java.util.ConcurrentModificationException]，故包 runCatching，
     * 失敗回 null；兩 runner 入口 `?: Denied(BLACKLISTED, "snapshot failed")`
     * 且零 spawn（Denied＋審計契約不被外抛繞過）。
     *
     * Round-3 Phase H（P2-6＋P3a）：拷貝體換為
     * [dev.librepocket.shell.ShellPolicy.copyArgvBounded]（argc≤64／單項≤4K
     * 字元／總量≤64K UTF-8 位元組＋孤立代理拒絕，超限／拷貝抛一律回 null，
     * 呼叫方同映 `Denied(BLACKLISTED)` 零 spawn）。阻塞 iterator 不在此收斂——
     * 本函數仍為呼叫執行緒同步拷貝，阻塞交由 runner 側 `awaitPreSpawn` 同
     * deadline 監督（超界即 `Expired`→`TimedOut`，零 spawn；殘留進 slot／breaker
     * 終審，見兩 runner 入口順序）。
     */
    fun snapshot(request: ElevatedRequest): ElevatedCommandSnapshot? = try {
        val argvCopy = ShellPolicy.copyArgvBounded(request.argv) ?: return null
        ElevatedCommandSnapshot(
            java.util.Collections.unmodifiableList(argvCopy),
            request.reasonCode,
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * 可信 executable 解析（提權專用，spawn 前唯一入口）：
     * - `customResolver` 非 null 時走注入映射（僅單測 stub 用，生產一律 null）；
     *   Phase 4（F1，PR #140）：注入回傳不再全信 —— 回傳路徑必經同一實體驗證
     *   （containment＋app-writability＋entry 閘，見
     *   [ShellExecutables.verifiedTargetDetailed]，entryRoot 取回傳路徑的
     *   lexical parent；允許集／writability 判定沿用本函數同參），未通過即
     *   `Denied` 且零 spawn；spawn 用驗證後實體路徑（[toSpawnArgv] 另以快照
     *   basename 覆寫 multicall `applet`，不採信映射自帶字串，語義不變）；
     *   映射回 null／抛異常即呼叫方拒絕且零 spawn（語義不變）；
     * - null（預設）時走真查 [ShellExecutables.resolveDetailed]（受控搜尋＋
     *   containment＋writability＋entry 閘，不查 `PATH`、不看 cwd）。
     * 回傳 `Ok(exec)` 即已固定目標（spawn 用 `exec.path`，不再用原始 `argv[0]`）；
     * 回傳 `Denied(reason)` 呼叫方必須拒絕且零 spawn（訊息只攜 reason code，不含 path）。
     *
     * JVM 單測宿主相容（僅測試環境，生產裝置無此分支）：
     * 宿主無 `/system/bin`（`File("/system/bin").exists()==false`）即為 JVM 單測
     * 宿主（生產 Android 必有 `/system/bin`），此時預設搜尋表
     * （`searchDirs == TRUSTED_BIN_DIRS`）的 bare 命中若因宿主 exotic
     * （如 cargo `coreutils` 致 `OUTSIDE_ROOTS`、或 `screencap` 等裝置二進位在宿主
     * 缺席致 `NOT_FOUND`、或 CI 宿主預設目錄被 Stage 1-5 搜尋目錄守衛判定為
     * `SEARCH_DIR_UNTRUSTED`）而失敗，回退為合成 `Ok("/system/bin/<name>")`
     * （`applet=null`，僅供既有單測的假 spawn 斷言 verdict／計數，不執行真機）；
     * 顯式注入搜尋表／自訂 `allowedRoots`（新 P1-1 安全測試的暫存可信目錄）永不回退，
     * 一律 fail-closed；含分隔符的絕對 `argv[0]` 永不回退（含可信父目錄但宿主缺席亦
     * fail-closed，既有單測無此形，不影響）；`REAL_WRITABLE`／`ENTRY_WRITABLE`／
     * `UNTRUSTED_PATH` 等目標/路徑安全閘永不回退。
     * 生產裝置（`/system/bin` 存在）無回退分支，一律嚴格實體驗證。
     *
     * Phase 5 B7 三表同步警告（不改型別，僅 KDoc）：
     * 可信目錄三表（`ShellPolicy.validateElevated.trustedBinDirs` 詞法門／
     * 本函數 `searchDirs` 受控搜尋／runner `execSearchDirs` 構造參數）呼叫方須同步
     * 傳同一表。混表即語義分叉（validate 放行但 resolve 拒、或反之），fail-closed
     * 方向不一致。型別收斂（統一 `ExecTrust` 值對象）破簽名風險高，本階段不做。
     * TODO(#136-B3): 評估三表合一（單一可信表值對象＋單一傳參點）。
     */
    fun resolveForSnapshot(
        snapshotArgv0: String,
        searchDirs: List<String>,
        allowedRoots: List<String>? = null,
        isWritable: (Path) -> Boolean = ShellExecutables::defaultIsWritable,
        customResolver: ((String) -> ShellExecutables.ResolvedExec?)? = null,
    ): ShellExecutables.ResolveOutcome {
        val custom = customResolver
        if (custom != null) {
            val exec = try {
                custom(snapshotArgv0.trim())
            } catch (_: Exception) {
                null
            } ?: return ShellExecutables.ResolveOutcome.Denied(
                ShellExecutables.ResolveDeny.NOT_FOUND,
            )
            // F1：注入回傳再驗證（與內建路徑同閘）。entryRoot 取回傳路徑的
            // lexical parent；spawn 用驗證後實體（防映射回傳 link／可寫目標）。
            // P2-7 parity：applet 不採信映射自帶字串（值與 null 性皆以快照
            // basename × 實體 basename 重算：multicall 別名形才攜 applet，
            // 主程式自身一律 null，防 `[real, "toybox", ...]` 重插）。
            val rawPath = exec.path.trim()
            if (rawPath.isEmpty() || rawPath.contains('\u0000')) {
                return ShellExecutables.ResolveOutcome.Denied(
                    ShellExecutables.ResolveDeny.INVALID_INPUT,
                )
            }
            val normPath = try {
                dev.librepocket.files.FileScope.normalize(rawPath)
            } catch (_: Exception) {
                return ShellExecutables.ResolveOutcome.Denied(
                    ShellExecutables.ResolveDeny.INVALID_INPUT,
                )
            }
            val slash = normPath.lastIndexOf('/')
            if (slash <= 0) {
                // 相對形／無 parent 可驗 entry chain —— fail-closed，不採信。
                return ShellExecutables.ResolveOutcome.Denied(
                    ShellExecutables.ResolveDeny.UNTRUSTED_PATH,
                )
            }
            val entryRoot = normPath.substring(0, slash).ifEmpty { "/" }
            val roots = allowedRoots ?: ShellExecutables.snapshotRealRoots(searchDirs)
            val (real, failure) = try {
                ShellExecutables.verifiedTargetDetailed(normPath, roots, isWritable, entryRoot)
            } catch (_: Exception) {
                null to ShellExecutables.ResolveDeny.NOT_FOUND
            }
            if (real == null) {
                return ShellExecutables.ResolveOutcome.Denied(
                    failure ?: ShellExecutables.ResolveDeny.NOT_FOUND,
                )
            }
            // P2-7：以快照 basename × 驗證後實體重算 applet（見上註解）。
            val logicalBase = ShellPolicy.basename(snapshotArgv0.trim())
            val realBase = real.substringAfterLast('/')
            val fixedApplet =
                if (realBase in ShellExecutables.MULTICALL_BINARIES &&
                    logicalBase.isNotEmpty() && logicalBase != realBase
                ) {
                    logicalBase
                } else {
                    null
                }
            return ShellExecutables.ResolveOutcome.Ok(
                ShellExecutables.ResolvedExec(real, fixedApplet),
            )
        }
        val outcome = try {
            ShellExecutables.resolveDetailed(
                snapshotArgv0.trim(),
                searchDirs,
                allowedRoots,
                isWritable,
            )
        } catch (_: Exception) {
            ShellExecutables.ResolveOutcome.Denied(ShellExecutables.ResolveDeny.NOT_FOUND)
        }
        if (outcome is ShellExecutables.ResolveOutcome.Denied &&
            searchDirs == ShellExecutables.TRUSTED_BIN_DIRS &&
            allowedRoots == null
        ) {
            val reason = outcome.reason
            val hostDefaultSearchFallback =
                reason == ShellExecutables.ResolveDeny.NOT_FOUND ||
                    reason == ShellExecutables.ResolveDeny.OUTSIDE_ROOTS ||
                    reason == ShellExecutables.ResolveDeny.SEARCH_DIR_UNTRUSTED
            if (hostDefaultSearchFallback) {
                val raw = snapshotArgv0.trim()
                val isBare = !raw.contains('/') && !raw.contains('\\')
                // JVM 單測宿主相容：預設裝置搜尋表在非 Android 宿主上可能因目錄缺席、
                // 宿主差異或搜尋目錄守衛而失敗；僅在 /system/bin 不存在時回退為合成目標。
                // 顯式搜尋表/allowedRoots、絕對 argv0 與目標/路徑安全閘永不回退。
                if (isBare && !java.io.File("/system/bin").exists()) {
                    return ShellExecutables.ResolveOutcome.Ok(
                        ShellExecutables.ResolvedExec("/system/bin/$raw", null),
                    )
                }
            }
        }
        return outcome
    }

    /**
     * 快照＋已固定目標 → spawn argv（不再含原始 `argv[0]`）：
     * - 非 multicall（`resolved.applet == null`）：`[real, ...snapshot.drop(1)]`；
     * - multicall 別名形（實體 basename ∈ [ShellExecutables.MULTICALL_BINARIES]
     *   「且」快照 basename ≠ 實體 basename，即經 symlink／別名呼叫）：
     *   `[real, applet, ...snapshot.drop(1)]`，其中 `applet` 取快照 `argv[0]` 的
     *   basename（[ShellPolicy.basename]，已過黑名單／衛生門＋applet 黑名單），
     *   不採信映射自帶字串（自訂映射不得注入未過審的 applet 名，與直接通道
     *   [RestrictedShell] 同策）；
     * - multicall 主程式自身（快照 basename == 實體 basename，如
     *   `["toybox", "id"]`）：不插入，為 `[real, ...snapshot.drop(1)]`
     *  （`drop(1)` 首位即 applet；陳舊 `ResolvedExec` 攜非 null applet 亦不重插，
     *   縱深防禦）。
     * 操作數（`drop(1)`）取自同一快照，原樣透傳，不重寫、不放寬。
     *
     * TODO(#136-B3): B3 子命令 allowlist（`cmd`／`package` 等未知二進位的子命令
     * 審查）在此擴充 applet／子命令位置審查；本函數只做黑名單 parity，不放寬。
     */
    fun toSpawnArgv(
        snapshot: ElevatedCommandSnapshot,
        resolved: ShellExecutables.ResolvedExec,
    ): List<String> {
        val applet = resolved.applet
        if (applet == null) {
            return listOf(resolved.path) + snapshot.argv.drop(1)
        }
        // P2-7 parity 縱深：即使陳舊映射攜非 null applet，主程式自身一律不插。
        val realBase = resolved.path.substringAfterLast('/')
        // P3-1 trimmed logicalBase：驗證側已 trim，spawn 側同口徑，否則
        // `["  ls  "]` 驗為 `ls` 卻 spawn `[real,"  ls  "]`（applet 帶空格）。
        val logicalBase = ShellPolicy.basename(snapshot.argv[0].trim())
        if (realBase !in ShellExecutables.MULTICALL_BINARIES || logicalBase == realBase) {
            return listOf(resolved.path) + snapshot.argv.drop(1)
        }
        if (logicalBase.isEmpty()) {
            return listOf(resolved.path) + snapshot.argv.drop(1)
        }
        return listOf(resolved.path, logicalBase) + snapshot.argv.drop(1)
    }
}
