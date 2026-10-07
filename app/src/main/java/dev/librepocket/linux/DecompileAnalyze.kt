package dev.librepocket.linux

import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor

/**
 * S4 `decompile.analyze` 執行面（WRITE）+ `decompile.repack`（PRIVILEGED）：
 * 容器內反編譯漸進分析。
 *
 * - 漸進四階：STRINGS（`strings/file` 靜態字串）→ SMALI
 *  （`baksmali` / `apktool d`）→ RESOURCES（`aapt2 dump` / `apktool`
 *   資源）→ JAVA（`jadx` 反編譯）。只能逐階解鎖
 *   （[nextAllowed]：已完成集合的下一階，且已完成集合必須前綴封閉），
 *   輸出全部經 [ProotExec.redactGuest] 脫敏。
 * - 工具版本釘選（容器映像清單）：apktool [APKTOOL_VERSION]、
 *   jadx [JADX_VERSION]；`baksmali` / `aapt2` 隨映像釘選
 *   （見 [PINNED_SUFFIX] 話術）。
 * - `repack`（重打包/APK 簽名）拆獨立 PRIVILEGED 工具
 *  （[REPACK_NAME]），每次執行都要 `confirmed=true`
 *  （[RepackOutcome.NeedConfirm]，語義同 `db.exec`），且只在
 *   foss/github + `decompile` 開關開啟時可見（[repackVeto]：play 回
 *   `FLAVOR_BLOCKED`，關開關回 `USER_DISABLED`，容器非法回 `NO_PRIVILEGE`）。
 * - 輸入 APK 走獨立大檔通道（上限 [LinuxEnv.APK_MAX_BYTES] 500 MiB，
 *   見 [inputVeto] 與 [LinuxEnv.apkSegmentsFor]；分段進件，每段獨立
 *   checksum，全量合併後再驗 SHA256），不走一般檔 inbox 通道
 *  （[LinuxEnv.inboxVeto] 10 MiB，兩通道分開計算）。
 * - 降級回覆尾必附 [FALLBACK_HINT] 與 [LinuxTools.PERF_NOTICE]，單測斷言。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言。
 */
object DecompileAnalyze {

    const val NAME = "decompile.analyze"
    const val REPACK_NAME = "decompile.repack"
    const val SWITCH = "decompile"
    const val SWITCH_DEFAULT = false
    const val FALLBACK_HINT = "analyze the APK on a desktop machine with jadx/apktool"

    /** 釘選版本：apktool 3.0.1。 */
    const val APKTOOL_VERSION = "3.0.1"

    /** 釘選版本：jadx 1.5.6。 */
    const val JADX_VERSION = "1.5.6"

    /** baksmali / aapt2 隨容器映像釘選（非獨立版本號）。 */
    const val PINNED_SUFFIX = " (image-pinned)"

    /** 漸進四階（固定順序，不可跳階）。 */
    enum class Stage { STRINGS, SMALI, RESOURCES, JAVA }

    /** 各階允許的 guest 二進位。 */
    val STAGE_BINARIES: Map<Stage, Set<String>> = mapOf(
        Stage.STRINGS to setOf("strings", "file"),
        Stage.SMALI to setOf("baksmali", "smali", "apktool"),
        Stage.RESOURCES to setOf("aapt2", "aapt", "apktool"),
        Stage.JAVA to setOf("jadx"),
    )

    /**
     * 漸進門禁：`want` 必須是 `done` 的下一階（或已完成階的重跑）。
     * 空集合只能從 STRINGS 開始。
     * `done` 必須前綴封閉（0..max 無缺口）；缺口存在時只能先補最早缺階。
     */
    fun nextAllowed(done: Set<Stage>, want: Stage): Boolean {
        if (want in done) return true
        val order = Stage.entries
        val expected = order.firstOrNull { it !in done } ?: return false
        return want == expected
    }

    /**
     * 階段守衛：null 表示放行（仍需走 [ProotExec.execute]），
     * 否則為拒絕細碼（`STAGE_SKIPPED` 附應先做的階段）。
     */
    fun stageVeto(done: Set<Stage>, want: Stage, binary: String): String? {
        val allowed = STAGE_BINARIES[want] ?: return "UNKNOWN_STAGE"
        val base = binary.substringAfterLast('/').substringAfterLast('\\')
        if (base !in allowed) return "TOOL_STAGE_MISMATCH:$base"
        if (!nextAllowed(done, want)) {
            val expected = Stage.entries.firstOrNull { it !in done } ?: Stage.JAVA
            return "STAGE_SKIPPED:do $expected first"
        }
        return null
    }

    /**
     * 分析輸入守衛：APK 大檔通道上限 500 MiB（[LinuxEnv.APK_MAX_BYTES]）。
     * 一般檔另走 [LinuxEnv.inboxVeto]（10 MiB），兩者不共用。
     */
    fun inputVeto(sizeBytes: Long): String? {
        if (sizeBytes < 0) return "NEGATIVE_SIZE"
        if (sizeBytes > LinuxEnv.APK_MAX_BYTES) return "APK_TOO_LARGE"
        return null
    }

    sealed interface RepackOutcome {
        data class Ok(val detail: String) : RepackOutcome
        data class NeedConfirm(val toolName: String) : RepackOutcome
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : RepackOutcome
    }

    /**
     * repack 門禁（風味 → 開關 → 容器）：null 表示放行（仍需確認），
     * 否則為拒絕碼。
     */
    fun repackVeto(flavor: Flavor, switchOn: Boolean, filesDir: String, container: String): DenyReason? {
        if (flavor == Flavor.PLAY) return DenyReason.FLAVOR_BLOCKED
        if (!switchOn) return DenyReason.USER_DISABLED
        if (LinuxEnv.containerVeto(filesDir, container) != null) return DenyReason.NO_PRIVILEGE
        return null
    }

    private fun repackDenied(reason: DenyReason, detail: String): RepackOutcome.Denied =
        RepackOutcome.Denied(
            reason = reason,
            detail = detail,
            message = "重打包：$detail（需自裝版 foss/github 並開啟「$SWITCH」開關；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}）",
        )

    /**
     * 重打包（PRIVILEGED）：先過 [repackVeto]（play/關開關/`../evil` 拒），
     * 再要求每次 `confirmed=true`，否則 [RepackOutcome.NeedConfirm]
     * （呼叫方不得快取上次確認）。
     */
    fun repack(
        confirmed: Boolean,
        flavor: Flavor,
        switchOn: Boolean,
        filesDir: String,
        container: String,
    ): RepackOutcome {
        val veto = repackVeto(flavor, switchOn, filesDir, container)
        if (veto != null) return repackDenied(veto, "repack blocked ($veto)")
        if (!confirmed) return RepackOutcome.NeedConfirm(REPACK_NAME)
        return RepackOutcome.Ok("repack confirmed (signing runs inside the container)")
    }

    /**
     * 相容舊單參簽名（PR#1 review T2 收斂：僅測試可見）。
     * 正式鏈路一律用五參版本（含門禁）；此 overload 只鎖確認語義，
     * 生產碼不得呼叫。
     */
    internal fun repack(confirmed: Boolean): RepackOutcome =
        if (!confirmed) RepackOutcome.NeedConfirm(REPACK_NAME)
        else RepackOutcome.Ok("repack confirmed (signing runs inside the container)")

    /**
     * 分析編排入口（M6）：風味 → 開關 → 容器 → 輸入/APK 守衛 → 階段守衛 →
     * [ProotExec.execute]。落盤配額經 [LinuxEnv.quotaVeto] 先裁。
     */
    fun executeAnalyze(
        done: Set<Stage>,
        want: Stage,
        binary: String,
        apkSizeBytes: Long,
        guestArgv: List<String>,
        filesDir: String,
        container: String,
        flavor: Flavor,
        switchOn: Boolean,
        runner: ProcessRunner,
        quota: ShellQuota = ShellQuota(),
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        usedTotalBytes: Long = 0L,
        usedContainerBytes: Long = 0L,
        estimatedWriteBytes: Long = 0L,
    ): ShellResult {
        if (flavor == Flavor.PLAY) {
            return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "decompile.analyze blocked on play (FLAVOR_BLOCKED)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        if (!switchOn) {
            return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "decompile switch off ($SWITCH=false)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        if (LinuxEnv.containerVeto(filesDir, container) != null) {
            return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "bad container or scope；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        val input = inputVeto(apkSizeBytes)
        if (input != null) {
            return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "apk input veto: $input；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        val stage = stageVeto(done, want, binary)
        if (stage != null) {
            return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "stage veto: $stage；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        val diskVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, estimatedWriteBytes)
        if (diskVeto != null) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "disk quota veto: $diskVeto")
        }
        return ProotExec.execute(
            guestArgv = guestArgv,
            filesDir = filesDir,
            container = container,
            flavor = flavor,
            switchOn = true,
            runner = runner,
            quota = quota,
            timeoutMs = timeoutMs,
            usedTotalBytes = usedTotalBytes,
            usedContainerBytes = usedContainerBytes,
            estimatedWriteBytes = estimatedWriteBytes,
        )
    }

    /**
     * repack 編排入口（M6，門禁併入）：風味 → 開關 → 容器 → 確認 →
     * [ProotExec.execute]。未確認回 [ShellResult.Denied]（`NEED_CONFIRM`，
     * 語義同 NeedConfirm，不得快取）。
     */
    fun executeRepack(
        confirmed: Boolean,
        guestArgv: List<String>,
        filesDir: String,
        container: String,
        flavor: Flavor,
        switchOn: Boolean,
        runner: ProcessRunner,
        quota: ShellQuota = ShellQuota(),
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        usedTotalBytes: Long = 0L,
        usedContainerBytes: Long = 0L,
        estimatedWriteBytes: Long = 0L,
    ): ShellResult {
        when (repackVeto(flavor, switchOn, filesDir, container)) {
            DenyReason.FLAVOR_BLOCKED -> return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "decompile.repack blocked on play (FLAVOR_BLOCKED)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
            DenyReason.USER_DISABLED -> return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "decompile switch off ($SWITCH=false)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
            DenyReason.NO_PRIVILEGE -> return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "bad container or scope；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
            null -> Unit
        }
        if (!confirmed) {
            return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "repack needs confirmation (NEED_CONFIRM:$REPACK_NAME)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        val diskVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, estimatedWriteBytes)
        if (diskVeto != null) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "disk quota veto: $diskVeto")
        }
        return ProotExec.execute(
            guestArgv = guestArgv,
            filesDir = filesDir,
            container = container,
            flavor = flavor,
            switchOn = true,
            runner = runner,
            quota = quota,
            timeoutMs = timeoutMs,
            usedTotalBytes = usedTotalBytes,
            usedContainerBytes = usedContainerBytes,
            estimatedWriteBytes = estimatedWriteBytes,
        )
    }

    /** 投影用拒絕碼（風味 → 開關）。 */
    fun denyReasonFor(flavor: Flavor, switchOn: Boolean): DenyReason? {
        if (flavor == Flavor.PLAY) return DenyReason.FLAVOR_BLOCKED
        if (!switchOn) return DenyReason.USER_DISABLED
        return null
    }
}
