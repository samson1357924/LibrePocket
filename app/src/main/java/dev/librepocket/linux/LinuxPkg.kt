package dev.librepocket.linux

import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor

/**
 * S4 `linux.pkg` 執行面（WRITE）：容器內套件管理子集。
 *
 * - 支援管理員：`apt` / `apt-get`（Debian 系）、`dnf` / `yum`（RPM 系）、
 *   `apk`（Alpine 系）；其餘管理員一律否決。
 * - 子命令子集：`update / install / remove / list / search / show`；
 *   `dist-upgrade / full-upgrade / autoremove / purge` 等高危/破壞性
 *   子命令不在子集（upgrade 語義請拆成 `update` + 逐包 `install`）。
 * - 包名衛生：`[A-Za-z0-9.+_:-]+` 且不得以 `-` 開頭（防選項注入），
 *   不得含路徑分隔符；參數衛生（metachar）由 [ProotExec] 層
 *   [dev.librepocket.shell.ShellPolicy.validate] 繼承檢查。
 * - 開關共用 [LinuxBoot.SWITCH]（`linux`，預設關）。
 * - 降級回覆尾必附 [ProotExec.FALLBACK_HINT] 與 [LinuxTools.PERF_NOTICE]。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言 [pkgVeto] 與 [execute]。
 */
object LinuxPkg {

    const val NAME = "linux.pkg"

    /** 允許的管理員二進位（basename）。 */
    val MANAGERS: Set<String> = setOf("apt", "apt-get", "dnf", "yum", "apk")

    /** 允許的子命令子集。 */
    val SUBCOMMANDS: Set<String> = setOf("update", "install", "remove", "list", "search", "show")

    private val packageName = Regex("[A-Za-z0-9][A-Za-z0-9.+_:-]*")

    /**
     * 套件請求守衛：null 表示放行（仍需走 [ProotExec.execute]
     * 的白名單/黑名單/檔案域/配額），否則為拒絕細碼。
     *
     * @param argv 形如 `[manager, subcommand, pkg...]`（guest 視角）。
     */
    fun pkgVeto(argv: List<String>): String? {
        if (argv.isEmpty() || argv.all { it.isBlank() }) return "EMPTY_COMMAND"
        val manager = argv[0].substringAfterLast('/').substringAfterLast('\\')
        if (manager !in MANAGERS) return "MANAGER_DENIED:$manager"
        if (argv.size < 2) return "SUBCOMMAND_REQUIRED"
        val sub = argv[1]
        if (sub !in SUBCOMMANDS) return "SUBCOMMAND_DENIED:$sub"
        for (pkg in argv.drop(2)) {
            if (pkg.startsWith("-")) {
                // 僅放行 `-y/--yes/--no-install-recommends` 類無害旗標？不：
                // fail-closed，一律拒絕選項形參數（呼叫方不得拼旗標）。
                return "OPTION_FORBIDDEN:$pkg"
            }
            if (!packageName.matches(pkg)) return "BAD_PACKAGE:$pkg"
        }
        return null
    }

    /**
     * 編排執行入口（M6）：風味 → 開關（[LinuxBoot.SWITCH]）→ 容器 →
     * [pkgVeto] → [ProotExec.execute]。
     *
     * 落盤配額經 [LinuxEnv.quotaVeto] 先裁（超限不建子進程）。
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
                "linux.pkg blocked on play (FLAVOR_BLOCKED)；${ProotExec.FALLBACK_HINT}；${LinuxTools.PERF_NOTICE}",
            )
        }
        if (!switchOn) {
            return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "linux switch off (${LinuxBoot.SWITCH}=false)；${ProotExec.FALLBACK_HINT}；${LinuxTools.PERF_NOTICE}",
            )
        }
        if (LinuxEnv.containerVeto(filesDir, container) != null) {
            return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "bad container or scope；${ProotExec.FALLBACK_HINT}；${LinuxTools.PERF_NOTICE}",
            )
        }
        val veto = pkgVeto(argv)
        if (veto != null) {
            return ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "pkg veto: $veto；${ProotExec.FALLBACK_HINT}；${LinuxTools.PERF_NOTICE}",
            )
        }
        val diskVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, estimatedWriteBytes)
        if (diskVeto != null) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "disk quota veto: $diskVeto")
        }
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

    /** 投影用拒絕碼（風味 → 開關，共用 linux 開關）。 */
    fun denyReasonFor(flavor: Flavor, switchOn: Boolean): DenyReason? {
        if (flavor == Flavor.PLAY) return DenyReason.FLAVOR_BLOCKED
        if (!switchOn) return DenyReason.USER_DISABLED
        return null
    }
}
