package dev.librepocket.linux

import dev.librepocket.redact.Redactor
import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor

/**
 * S4 `linux.exec` 執行面（WRITE）：在指定容器內執行 guest 命令。
 *
 * - 白名單聯集：[GUEST_BINARIES] = [ShellPolicy.ALLOWED_BINARIES] ∪ 機內
 *   工具鏈/分析二進位；黑名單完整繼承（[ShellPolicy.validate]
 *   同一程式碼路徑：黑名單二進位 → 全文片段 → 參數衛生 → find 高危謂詞，
 *   見 `allowedBinaries` 參數）。`git` 不在聯集內：取源一律經 inbox 進件，
 *   容器內不得直抓（`curl/wget` 同理不在聯集）。
 * - 檔案域：guest 絕對路徑以整個 `filesDir/linux` 樹（PRIVATE）為作用域
 *   （容器 rootfs 與 inbox 同屬該樹；guest 越界由 PRoot `-r` 在執行時約束，
 *   argv 層只擋樹外路徑）；SAF 樹路徑即使橋接已授權亦拒絕（禁 `--bind`，
 *   進出走 inbox 複製）。`bridgeGranted` 固定 false（fail-closed）。
 * - 落盤配額：執行前先過 [LinuxEnv.quotaVeto]（`usedTotal/usedContainer +
 *   estimatedWrite`），超限不建子進程（與 [LinuxBoot.download] 同策）。
 * - proot 落盤權限：呼叫方傳入實際 mode，必須等於 [LinuxEnv.PROOT_BIN_MODE]
 *   （`0700`），否則 fail-closed（見 [LinuxEnv.prootBinModeVeto]）。
 * - 超時 / 配額 / 截斷沿用宿主 shell 同一套常數
 *   （[ShellPolicy.DEFAULT_TIMEOUT_MS] / [ShellQuota] /
 *   [ShellPolicy.truncate]），輸出再過 [Redactor.redact] 全量
 *   （R1–R14，含 R13 GEO 與 R14 路徑）。
 * - 風味：foss/github NATIVE；play FLAVOR_BLOCKED。門禁經 [denyReasonFor]
 *   統一裁決（執行入口不得另寫風味/開關分支）。
 * - 降級回覆尾必附 [FALLBACK_HINT] 與 [LinuxTools.PERF_NOTICE]，單測斷言。
 *
 * 本檔案零 Android 依賴；子進程縫沿用 [ProcessRunner]，單測可注入假實現。
 */
object ProotExec {

    const val NAME = "linux.exec"
    const val FALLBACK_HINT = "run the command in a terminal app or on a desktop machine"

    /**
     * Guest 白名單增量（聯集對象）：工具鏈（編譯）、套件/解包/分析二進位
     * 與容器內常用查詢類。破壞性/提權/直譯器仍由黑名單擋下
     * （`sh`/`bash`/`su`/`sudo`/`rm`/`dd` 照拒）。
     * `git` 刻意不在聯集：取源一律經 inbox 進件（見 [LinuxEnv.inboxVeto]）。
     */
    val GUEST_EXTRA_BINARIES: Set<String> = setOf(
        "python3",
        "python",
        "gcc",
        "g++",
        "clang",
        "clang++",
        "make",
        "cmake",
        "ninja",
        "pkg-config",
        "tar",
        "unzip",
        "xz",
        "file",
        "strings",
        "readelf",
        "objdump",
        "apt",
        "apt-get",
        "dpkg",
        "dnf",
        "yum",
        "apk",
        "apktool",
        "jadx",
        "baksmali",
        "smali",
        "aapt2",
        "aapt",
        "zipalign",
        "apksigner",
    )

    /** 白名單聯集（宿主白名單 ∪ guest 增量）。 */
    val GUEST_BINARIES: Set<String> = ShellPolicy.ALLOWED_BINARIES + GUEST_EXTRA_BINARIES

    /**
     * Guest 執行入口。
     *
     * 門禁順序（固定）：風味/開關（[denyReasonFor]）→ 容器/作用域 →
     * proot 權限（[LinuxEnv.prootBinModeVeto]）→ 落盤配額
     * （[LinuxEnv.quotaVeto]）→ bind 封堵 → 白名單/黑名單/檔案域
     * （[ShellPolicy.validate]）→ 速率配額 → spawn。
     *
     * @param filesDir App 私有域根（作用域為 `filesDir/linux` 整樹，
     *   容器 rootfs 與 inbox 皆在其中；SAF 等樹外路徑一律拒絕）。
     * @param container 容器名（[LinuxEnv.isSafeContainerName]）。
     * @param quota 滑動窗口配額（沿用 [ShellQuota]，呼叫方可與宿主 shell 共用或獨立）。
     * @param usedTotalBytes linux 總量已用（落盤配額用）。
     * @param usedContainerBytes 單容器已用（落盤配額用）。
     * @param estimatedWriteBytes 本次預估落盤（未知按 0 計，但輸出截斷仍受
     *   [ShellPolicy.MAX_OUTPUT_BYTES] 約束）。
     * @param prootBinMode proot 落盤實際 mode（須等於 [LinuxEnv.PROOT_BIN_MODE]）。
     */
    fun execute(
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
        prootBinMode: String = LinuxEnv.PROOT_BIN_MODE,
    ): ShellResult {
        when (denyReasonFor(flavor, switchOn)) {
            DenyReason.FLAVOR_BLOCKED ->
                return ShellResult.Denied(
                    ShellDeny.BLACKLISTED,
                    "linux.exec blocked on play (FLAVOR_BLOCKED)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
                )
            DenyReason.USER_DISABLED ->
                return ShellResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "linux switch off (${LinuxBoot.SWITCH}=false)；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
                )
            DenyReason.NO_PRIVILEGE ->
                return ShellResult.Denied(
                    ShellDeny.BLACKLISTED,
                    "linux.exec privilege denied；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
                )
            null -> Unit
        }
        if (LinuxEnv.containerVeto(filesDir, container) != null) {
            return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "bad container or scope；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}",
            )
        }
        val modeVeto = LinuxEnv.prootBinModeVeto(prootBinMode)
        if (modeVeto != null) {
            return ShellResult.Denied(ShellDeny.BLACKLISTED, "proot mode veto: $modeVeto")
        }
        val diskVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, estimatedWriteBytes)
        if (diskVeto != null) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "disk quota veto: $diskVeto")
        }
        if (LinuxEnv.bindVeto(guestArgv) != null) {
            return ShellResult.Denied(ShellDeny.BLACKLISTED, "bind forbidden inside guest argv")
        }
        val rootfs = LinuxEnv.containerRootfs(filesDir, container)
        when (
            val v = ShellPolicy.validate(
                argv = guestArgv,
                privateRoot = LinuxEnv.root(filesDir),
                safRoots = emptyList(),
                flavor = flavor,
                bridgeGranted = false,
                allowedBinaries = GUEST_BINARIES,
                isGuest = true,
            )
        ) {
            is Validation.Denied -> return ShellResult.Denied(v.reason, v.message)
            is Validation.Allowed -> Unit
        }
        if (!quota.tryAcquire()) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "linux.exec quota exceeded")
        }
        val raw = try {
            runner.run(LinuxEnv.prootCmd(LinuxEnv.prootBin(filesDir), rootfs, guestArgv), timeoutMs)
        } catch (e: Exception) {
            return ShellResult.Failed("spawn failed: ${e.message}")
        }
        val out = ShellPolicy.truncate(raw.stdout)
        val err = ShellPolicy.truncate(raw.stderr)
        val truncated = out.truncated || err.truncated
        if (raw.timedOut) {
            return ShellResult.TimedOut(
                partialStdout = redactGuest(out.bytes.toUtf8(), filesDir),
                partialStderr = redactGuest(err.bytes.toUtf8(), filesDir),
                timeoutMs = timeoutMs,
                truncated = truncated,
            )
        }
        return ShellResult.Ok(
            stdout = redactGuest(out.bytes.toUtf8(), filesDir),
            stderr = redactGuest(err.bytes.toUtf8(), filesDir),
            exitCode = raw.exitCode,
            truncated = truncated,
        )
    }

    /**
     * Guest 輸出脫敏：先把本次 [filesDir] 絕對前綴折成 `⟦PRIVATE⟧`
     *（容器路徑回顯不外洩），再過 [Redactor.redact] 全量
     * （R1–R14：金鑰/個資/座標/絕對路徑）。
     */
    fun redactGuest(text: String, filesDir: String): String {
        val folded = if (filesDir.isNotEmpty()) text.replace(filesDir, "⟦PRIVATE⟧") else text
        return Redactor.redact(folded).text
    }

    /** DenyReason 投影（矩陣 §5 話術用）：play 阻擋，其餘缺權/未開關。 */
    fun denyReasonFor(flavor: Flavor, switchOn: Boolean): DenyReason? {
        if (flavor == Flavor.PLAY) return DenyReason.FLAVOR_BLOCKED
        if (!switchOn) return DenyReason.USER_DISABLED
        return null
    }

    private fun ByteArray.toUtf8(): String = String(this, Charsets.UTF_8)
}
