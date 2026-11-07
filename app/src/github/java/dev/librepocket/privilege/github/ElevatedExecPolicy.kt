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
 * - multicall（toybox／toolbox／busybox）：實體 basename 落表時 spawn 形狀為
 *   `[real, applet, ...args]`，其中 `applet` 取快照 `argv[0]` 的 basename
 *   （已過 [ShellPolicy.validateElevated] 黑名單／衛生門，本函數只負責是否為
 *   multicall 的訊號，不擴大白名單；與 [ShellExecutables] 直接通道同形，
 *   Root `su -c` 傳送層同形，見 [toSpawnArgv]）。
 * - 本檔案零 Android 依賴，JVM 單測可直接斷言；產品 runner 經
 *   [RootSuRunner]/[ShizukuShellRunner] 強制調用（預設即真查 FS，不可繞過）。
 */
object ElevatedExecPolicy {

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
     */
    fun snapshot(request: ElevatedRequest): ElevatedCommandSnapshot? = runCatching {
        val argvCopy: List<String> =
            java.util.Collections.unmodifiableList(ArrayList(request.argv))
        ElevatedCommandSnapshot(argvCopy, request.reasonCode)
    }.getOrNull()

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
     * 缺席致 `NOT_FOUND`）而失敗，回退為合成 `Ok("/system/bin/<name>")`
     * （`applet=null`，僅供既有單測的假 spawn 斷言 verdict／計數，不執行真機）；
     * 顯式注入搜尋表（新 P1-1 安全測試的暫存可信目錄）永不回退，一律 fail-closed；
     * 含分隔符的絕對 `argv[0]` 永不回退（含可信父目錄但宿主缺席亦 fail-closed，
     * 既有單測無此形，不影響）；`SEARCH_DIR_UNTRUSTED`／`REAL_WRITABLE`／
     * `ENTRY_WRITABLE`／`UNTRUSTED_PATH` 等安全閘永不回退。
     * 生產裝置（`/system/bin` 存在）無回退分支，一律嚴格實體驗證。
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
            return ShellExecutables.ResolveOutcome.Ok(exec.copy(path = real))
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
            allowedRoots == null &&
            (outcome.reason == ShellExecutables.ResolveDeny.NOT_FOUND ||
                outcome.reason == ShellExecutables.ResolveDeny.OUTSIDE_ROOTS)
        ) {
            val raw = snapshotArgv0.trim()
            val isBare = !raw.contains('/') && !raw.contains('\\')
            if (isBare && !java.io.File("/system/bin").exists()) {
                return ShellExecutables.ResolveOutcome.Ok(
                    ShellExecutables.ResolvedExec("/system/bin/$raw", null),
                )
            }
        }
        return outcome
    }

    /**
     * 快照＋已固定目標 → spawn argv（不再含原始 `argv[0]`）：
     * - 非 multicall（`resolved.applet == null`）：`[real, ...snapshot.drop(1)]`；
     * - multicall（實體 basename ∈ [ShellExecutables.MULTICALL_BINARIES]）：
     *   `[real, applet, ...snapshot.drop(1)]`，其中 `applet` 取快照 `argv[0]` 的
     *   basename（[ShellPolicy.basename]，已過黑名單／衛生門），不採信映射自帶
     *   字串（自訂映射不得注入未過審的 applet 名，與直接通道 [RestrictedShell]
     *   同策）。
     * 操作數（`drop(1)`）取自同一快照，原樣透傳，不重寫、不放寬。
     */
    fun toSpawnArgv(
        snapshot: ElevatedCommandSnapshot,
        resolved: ShellExecutables.ResolvedExec,
    ): List<String> {
        val applet = resolved.applet
        return if (applet != null) {
            val validatedApplet = ShellPolicy.basename(snapshot.argv[0])
            listOf(resolved.path, validatedApplet) + snapshot.argv.drop(1)
        } else {
            listOf(resolved.path) + snapshot.argv.drop(1)
        }
    }
}
