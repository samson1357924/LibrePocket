package dev.librepocket.linux

import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor

/**
 * S4 `compile.build` 執行面（WRITE）：機內 recipe 編譯。
 *
 * - 支援 recipe：`make / cmake / ninja / gcc / g++ / clang / clang++ /
 *   pkg-config / python3`（以原始碼/腳本 recipe 構建，跑在 PRoot 容器內）。
 * - Gradle 機內明確不支援：`gradle / gradlew` 一律否決並回 [CI_GUIDANCE]
 *   （Android 構建請走 CI 指引，機內 JVM/Android SDK 不完整且耗資源）。
 * - 其餘二進位走 [ProotExec.GUEST_BINARIES] 聯集判定（`curl/wget/git`
 *   不在聯集故不可直抓——原始碼一律經 inbox 進件，見 [LinuxEnv.inboxVeto]）。
 * - 來源檔必須先經 [LinuxInboxStager.stageFile] 進件再用 `/inbox/...`
 *   guest 路徑組 argv（host inbox 路徑直傳會被 [ProotExec] 否決）。
 * - 降級回覆尾必附 [FALLBACK_HINT] 與 [LinuxTools.PERF_NOTICE]，單測斷言。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言 [recipeVeto] 與 [execute]。
 */
object CompileBuild {

    const val NAME = "compile.build"
    const val SWITCH = "compile"
    const val SWITCH_DEFAULT = false
    const val FALLBACK_HINT = "build on a desktop machine or in CI, then import the artifact via inbox"

    /** 支援的 recipe 二進位（[ProotExec.GUEST_BINARIES] 的子集）。 */
    val RECIPE_BINARIES: Set<String> = setOf(
        "make",
        "cmake",
        "ninja",
        "gcc",
        "g++",
        "clang",
        "clang++",
        "pkg-config",
        "python3",
        "python",
    )

    /** Gradle 機內不支援的二進位名。 */
    val GRADLE_BINARIES: Set<String> = setOf("gradle", "gradlew", "gradlew.bat")

    /**
     * 機內不支援 Gradle → CI 指引（否決訊息原文引用，呼叫方不得改寫為
     * 「稍後再試」類含糊話術）。
     */
    const val CI_GUIDANCE: String =
        "Gradle 機內不支援：Android 構建請推送到 CI（GitHub Actions：" +
            "用倉庫 workflow 跑 ./gradlew build），產物再經 inbox 取回機內。"

    /**
     * Recipe 守衛：null 表示放行（仍需走 [ProotExec.execute]
     * 的黑名單/檔案域/配額），否則為拒絕細碼；
     * Gradle 命中回 `GRADLE_UNSUPPORTED`（呼叫方附 [CI_GUIDANCE]）。
     */
    fun recipeVeto(argv: List<String>): String? {
        if (argv.isEmpty() || argv.all { it.isBlank() }) return "EMPTY_COMMAND"
        val base = argv[0].substringAfterLast('/').substringAfterLast('\\')
        if (base in GRADLE_BINARIES || base.endsWith("gradlew")) return "GRADLE_UNSUPPORTED"
        if (base !in RECIPE_BINARIES) return "RECIPE_DENIED:$base"
        return null
    }

    /**
     * 編排執行入口（M6）：風味 → 開關 → 容器 → recipe 守衛 → [ProotExec.execute]。
     *
     * - play 回 `FLAVOR_BLOCKED`，關開關回 `USER_DISABLED`，容器非法回
     *   `NO_PRIVILEGE`（訊息尾附 [FALLBACK_HINT] 與 [LinuxTools.PERF_NOTICE]）。
     * - Gradle 命中自動附 [CI_GUIDANCE] 原文。
     * - 落盤配額經 [LinuxEnv.quotaVeto] 先裁（超限不建子進程）。
     */
    fun execute(
        argv: List<String>,
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
                "compile.build blocked on play (FLAVOR_BLOCKED)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        if (!switchOn) {
            return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "compile switch off ($SWITCH=false)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        if (LinuxEnv.containerVeto(filesDir, container) != null) {
            return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "bad container or scope；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        when (val veto = recipeVeto(argv)) {
            "GRADLE_UNSUPPORTED" -> return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "gradle unsupported: $CI_GUIDANCE；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
            null -> Unit
            else -> return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "recipe veto: $veto；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        val diskVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, estimatedWriteBytes)
        if (diskVeto != null) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "disk quota veto: $diskVeto")
        }
        // 風味/開關/容器已裁，recipe 已過，餘下黑名單/檔案域/速率配額由 exec 繼承。
        return ProotExec.execute(
            guestArgv = argv,
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
