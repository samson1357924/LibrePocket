package dev.librepocket.linux

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor

/**
 * S4 `linux.boot` 執行面（WRITE）：容器映像下載 / 啟動 / 停止。
 *
 * - 狀態機：NOT_INSTALLED → DOWNLOADING → READY → RUNNING ⇄ STOPPED；
 *   `download` 需先過 [LinuxEnv.downloadVeto]（HTTPS + SHA256，無 userinfo、
 *   主機非空）與 [LinuxEnv.quotaVeto]（單容器 2G / 總量 4G，宣告大小預檢），
 *   fetch 後再以實際位元組做一次 [LinuxEnv.quotaVeto]（防宣告造假，超限
 *   Failed 不 unpack）。未知大小（`sizeBytes == -1`）不得按 0 計配額：
 *   預檢跳過， fetch 後強制按實際大小裁決。
 * - 來源分流：OCI 參照先過 [LinuxEnv.ociVeto]（未 digest pin 如 `:latest`
 *   一律拒）；tarball 檔名先過 [LinuxEnv.tarballVeto]（僅
 *   `.tar.gz/.tgz/.tar.xz`）。兩者皆為 download 入口前置守衛。
 * - 落盤後必 [LinuxEnv.sha256Ok] 才轉 READY。
 * - 風味：foss/github NATIVE；play 一律 [DenyReason.FLAVOR_BLOCKED]
 *   （ToolRegistry 側 `supportedFlavors` 亦隱藏，雙層）。
 * - 開關 `linux` 預設關（見 [SWITCH]/[SWITCH_DEFAULT]）。
 * - 降級回覆尾必附 [LinuxTools.PERF_NOTICE]（與 [FALLBACK_HINT] 並列），
 *   單測斷言。
 *
 * 本檔案零 Android 依賴；實際下載/解包 IO 由呼叫方（Android 層）注入
 * [Downloader] 縫，單測用假實現。
 */
object LinuxBoot {

    const val NAME = "linux.boot"
    const val SWITCH = "linux"
    const val SWITCH_DEFAULT = false
    const val FALLBACK_HINT = "install a terminal app with PRoot support, or use a desktop machine"

    enum class Action { DOWNLOAD, START, STOP }

    enum class BootState { NOT_INSTALLED, DOWNLOADING, READY, RUNNING, STOPPED }

    /** 最小下載/解包縫：呼叫方注入，函式內仍以實際位元組強制配額（見 [download]）。 */
    interface Downloader {
        fun fetch(spec: LinuxEnv.DownloadSpec): ByteArray
        fun unpack(archive: ByteArray, destRootfs: String)
    }

    sealed interface BootOutcome {
        data class Ok(val state: BootState) : BootOutcome
        data class NeedDownload(val toolName: String) : BootOutcome
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : BootOutcome
        data class Failed(val detail: String) : BootOutcome
    }

    /** 請求守衛：風味 → 開關 → 容器名 → 動作/狀態一致性。 */
    fun bootVeto(
        flavor: Flavor,
        switchOn: Boolean,
        action: Action,
        state: BootState,
        filesDir: String,
        container: String,
    ): DenyReason? {
        if (flavor == Flavor.PLAY) return DenyReason.FLAVOR_BLOCKED
        if (!switchOn) return DenyReason.USER_DISABLED
        if (LinuxEnv.containerVeto(filesDir, container) != null) return DenyReason.NO_PRIVILEGE
        return when (action) {
            Action.DOWNLOAD -> {
                if (state == BootState.DOWNLOADING || state == BootState.RUNNING) {
                    DenyReason.NO_PRIVILEGE
                } else {
                    null
                }
            }
            Action.START -> {
                if (state != BootState.READY && state != BootState.STOPPED) {
                    DenyReason.NO_PRIVILEGE
                } else {
                    null
                }
            }
            Action.STOP -> {
                if (state != BootState.RUNNING) DenyReason.NO_PRIVILEGE else null
            }
        }
    }

    fun download(
        spec: LinuxEnv.DownloadSpec,
        flavor: Flavor,
        switchOn: Boolean,
        state: BootState,
        filesDir: String,
        container: String,
        usedTotalBytes: Long,
        usedContainerBytes: Long,
        downloader: Downloader,
        ociRef: String? = null,
        tarballFileName: String? = null,
    ): BootOutcome {
        val veto = bootVeto(flavor, switchOn, Action.DOWNLOAD, state, filesDir, container)
        if (veto != null) return denied("下載容器映像", veto, "download blocked ($veto)")
        if (ociRef != null) {
            val ociVeto = LinuxEnv.ociVeto(ociRef)
            if (ociVeto != null) return BootOutcome.Failed("oci veto: $ociVeto")
        }
        if (tarballFileName != null) {
            val tarVeto = LinuxEnv.tarballVeto(tarballFileName)
            if (tarVeto != null) return BootOutcome.Failed("tarball veto: $tarVeto")
        }
        val specVeto = LinuxEnv.downloadVeto(spec)
        if (specVeto != null) return BootOutcome.Failed("download veto: $specVeto")
        // 宣告大小預檢：未知大小（-1）跳過，不得按 0 計；實際裁決在 fetch 後。
        if (spec.sizeBytes >= 0L) {
            val quotaVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, spec.sizeBytes)
            if (quotaVeto != null) return BootOutcome.Failed("quota veto: $quotaVeto")
        }
        val bytes: ByteArray = try {
            downloader.fetch(spec)
        } catch (e: Exception) {
            return BootOutcome.Failed("fetch failed: ${e.message}")
        }
        // B1 fail-closed：以實際位元組再裁決一次，超限不 unpack。
        val actualVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, bytes.size.toLong())
        if (actualVeto != null) return BootOutcome.Failed("quota veto (actual ${bytes.size} bytes): $actualVeto")
        if (!LinuxEnv.sha256Ok(bytes, spec.sha256Hex)) {
            return BootOutcome.Failed("sha256 mismatch (image rejected, not installed)")
        }
        return try {
            downloader.unpack(bytes, LinuxEnv.containerRootfs(filesDir, container))
            BootOutcome.Ok(BootState.READY)
        } catch (e: Exception) {
            BootOutcome.Failed("unpack failed: ${e.message}")
        }
    }

    fun start(
        flavor: Flavor,
        switchOn: Boolean,
        state: BootState,
        filesDir: String,
        container: String,
    ): BootOutcome {
        val veto = bootVeto(flavor, switchOn, Action.START, state, filesDir, container)
        if (veto != null) {
            if (state == BootState.NOT_INSTALLED || state == BootState.DOWNLOADING) {
                return BootOutcome.NeedDownload(NAME)
            }
            return denied("啟動容器", veto, "start blocked ($veto)")
        }
        return BootOutcome.Ok(BootState.RUNNING)
    }

    fun stop(
        flavor: Flavor,
        switchOn: Boolean,
        state: BootState,
        filesDir: String,
        container: String,
    ): BootOutcome {
        val veto = bootVeto(flavor, switchOn, Action.STOP, state, filesDir, container)
        if (veto != null) return denied("停止容器", veto, "stop blocked ($veto)")
        return BootOutcome.Ok(BootState.STOPPED)
    }

    private fun denied(what: String, reason: DenyReason, detail: String): BootOutcome.Denied =
        BootOutcome.Denied(
            reason = reason,
            detail = detail,
            message = "$what：$detail（需自裝版 foss/github 並開啟「$SWITCH」開關；$FALLBACK_HINT；${LinuxTools.PERF_NOTICE}）",
        )
}
