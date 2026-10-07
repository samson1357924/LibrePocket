package dev.librepocket.linux

import java.security.MessageDigest

/**
 * S4 機內 Linux（PRoot）環境常數與純函數（BACKLOG S4，矩陣「機內 Linux / 編譯 / 反編譯」行）。
 *
 * - 存放：`filesDir/linux/{bin,image,cache,containers/<name>/rootfs,tmp}`，
 *   全在 App 私有域（[dev.librepocket.files.FileScope] 判為 PRIVATE），
 *   不申請全存取權限。
 * - rootfs 絕不內嵌 APK：下載式（HTTPS + SHA256），OCI 參照優先且必須
 *   digest pinning（`@sha256:`），tarball 僅作相容路徑。
 * - proot 二進位來源優先序：已落盤 `bin/proot`（PRIVATE 0700）→
 *   flavor 內嵌（`src/foss|github` 的 jniLibs/assets，自裝風味限定，
 *   play 產物不得內嵌，見 [dev.librepocket.hardening.HardeningPolicy.checkPlayLinuxEntries]）
 *   → 下載；三者皆無即誠實失敗。
 * - `--get-proot-cmd` 可觀測：[prootCmd] 即實際 spawn 的 argv，
 *   呼叫方可先取後審再執行；簽名刻意沒有 bind 參數
 *   （禁 SAF 樹 `--bind` 進容器，見 [bindVeto]）。
 *
 * 本檔案零 Android 依賴，JVM 單測可直接斷言。
 */
object LinuxEnv {

    /** proot 落盤檔名（`bin/` 下，PRIVATE 域，mode 0700）。 */
    const val PROOT_BIN_NAME = "proot"

    /** proot 落盤權限：僅擁有者可讀寫執行。 */
    const val PROOT_BIN_MODE = "0700"

    /** 單容器配額 2 GiB。 */
    const val PER_CONTAINER_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** linux 總量配額 4 GiB。 */
    const val TOTAL_BYTES: Long = 4L * 1024 * 1024 * 1024

    /** SAF 進件箱上限 10 MiB（對齊 `FileAttachTools.MAX_SIZE_BYTES`）。 */
    const val INBOX_MAX_BYTES: Long = 10L * 1024 * 1024

    /** 待反編譯 APK 上限 500 MiB（超限分段進件，見 [apkSegmentsFor]）。 */
    const val APK_MAX_BYTES: Long = 500L * 1024 * 1024

    /** APK 分段進件的段大小（與上限同值：一段即完整 APK）。 */
    const val APK_SEGMENT_BYTES: Long = APK_MAX_BYTES

    fun root(filesDir: String): String = "$filesDir/linux"
    fun binDir(filesDir: String): String = "${root(filesDir)}/bin"
    fun imageDir(filesDir: String): String = "${root(filesDir)}/image"
    fun cacheDir(filesDir: String): String = "${root(filesDir)}/cache"
    fun containersDir(filesDir: String): String = "${root(filesDir)}/containers"
    fun containerDir(filesDir: String, name: String): String = "${containersDir(filesDir)}/$name"
    fun containerRootfs(filesDir: String, name: String): String = "${containerDir(filesDir, name)}/rootfs"
    fun tmpDir(filesDir: String): String = "${root(filesDir)}/tmp"
    fun inboxDir(filesDir: String): String = "${tmpDir(filesDir)}/inbox"
    fun prootBin(filesDir: String): String = "${binDir(filesDir)}/$PROOT_BIN_NAME"

    /** 容器名檢查：單段、`[A-Za-z0-9_-]+`（路徑拼接安全）。 */
    fun isSafeContainerName(name: String): Boolean {
        if (name.isEmpty() || name.length > 64) return false
        return name.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * 乾淨 guest 環境：固定最小集合，宿主環境變數一律不繼承
     * （阻斷 `LD_PRELOAD` / `PROOT_*` / 代理變數污染容器）。
     *
     * 執行不變量：[ProotExec.execute] 經 [dev.librepocket.shell.ProcessRunner]
     * 的 `env` 參數傳入本表；[dev.librepocket.shell.DefaultProcessRunner]
     * 遇非 null env 即 `clear()` 後全量替換，不繼承宿主 env
     * （見 `ProotExecTest.guestEnv_enforcedOnSpawn` 與
     * `ProotExecTest.defaultRunner_clearsHostEnv`）。
     */
    val GUEST_ENV: Map<String, String> = mapOf(
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "HOME" to "/root",
    )

    /**
     * 可觀測的實際 spawn argv（`--get-proot-cmd` 語義）：
     * `[prootBin, -r, rootfs, -w, /, -0] + guestArgv`。
     * 簽名刻意無 bind 參數：任何 `-b/--bind` 只能來自呼叫方手拼，
     * [bindVeto] 會先擋下（禁 SAF 樹 bind 進容器）。
     */
    fun prootCmd(prootBin: String, rootfs: String, guestArgv: List<String>): List<String> =
        listOf(prootBin, "-r", rootfs, "-w", "/", "-0") + guestArgv

    /**
     * bind 封堵：guest 包裝層 argv 內出現 `--bind` / `--bind=...` 即否決
     *（回非 null 細碼），呼叫方不得把 SAF 樹或任意宿主路徑 bind 進容器；
     * 進出容器一律走 inbox 複製。裸 `-b` 不在此列（它是 guest 二進位的
     * 普通旗標，如 `ls -b`；真正的 bind 只能由 [prootCmd] 產生，
     * 而它根本沒有 bind 參數）。
     */
    fun bindVeto(argv: List<String>): String? {
        for (arg in argv) {
            if (arg == "--bind" || arg.startsWith("--bind=")) {
                return "BIND_FORBIDDEN:$arg"
            }
        }
        return null
    }

    /**
     * 配額裁決（純函數）：`usedContainer + incoming` 不得超單容器 2G，
     * `usedTotal + incoming` 不得超總量 4G。呼叫方在下載/解包前先算，
     * 下載後必須再以實際位元組數算一次（防宣告大小造假，見 [LinuxBoot.download]）。
     *
     * 語義邊界（PR#1 review 收斂）：此處 `incoming` 是**壓縮檔位元組**
     * （`fetch` 回傳的 archive 大小），不是解包後 rootfs 實際佔用；
     * 高壓縮比 archive 仍可能撐爆儲存。生產 unpacker 落地時必須做
     * streaming extraction + per-entry 路徑安全 + expanded-byte 配額
     * 二次裁決（超限 abort + 清理 rootfs），見 [LinuxBoot.download]。
     */
    fun quotaVeto(usedTotalBytes: Long, usedContainerBytes: Long, incomingBytes: Long): String? {
        if (incomingBytes < 0) return "NEGATIVE_SIZE"
        if (usedContainerBytes + incomingBytes > PER_CONTAINER_BYTES) return "CONTAINER_QUOTA_2G"
        if (usedTotalBytes + incomingBytes > TOTAL_BYTES) return "TOTAL_QUOTA_4G"
        return null
    }

    /**
     * Inbox 進件守衛（一般檔通道，10 MiB 硬頂）。
     *
     * 一般檔一律走 inbox 複製（`tmp/inbox`，私有域內），大小不得超
     * [INBOX_MAX_BYTES]。null 表示放行，否則為拒絕細碼。
     *
     * 與 APK 大檔通道的區別：待反編譯 APK 不走此通道，改走獨立大檔通道
     *（上限 [APK_MAX_BYTES] 500 MiB，見 [DecompileAnalyze.inputVeto] 與
     * [apkSegmentsFor]；分段進件，每段獨立 checksum，全量合併後再驗
     * SHA256）。兩通道上限分開計算，不共用此守衛，避免 10M/500M 矛盾。
     */
    fun inboxVeto(sizeBytes: Long): String? {
        if (sizeBytes < 0) return "NEGATIVE_SIZE"
        if (sizeBytes > INBOX_MAX_BYTES) return "INBOX_TOO_LARGE"
        return null
    }

    /** 下載描述子：一律 HTTPS + SHA256（64 hex，缺一即否決）。 */
    data class DownloadSpec(val url: String, val sha256Hex: String, val sizeBytes: Long = -1L)

    /**
     * 下載守衛：null 表示放行，否則為拒絕細碼。
     *
     * - 僅 `https://`（明文一律 `NOT_HTTPS`）。
     * - 禁 userinfo（`https://user:pass@host` 回 `USERINFO_FORBIDDEN`，
     *   防憑證外洩與主機混淆）。
     * - 主機不得為空（`https:///path` 回 `BAD_HOST`）。
     * - SHA256 必為 64 hex。
     * - `sizeBytes` 僅接受 `>=0` 或 `-1`（未知）；`0` 與 `<-1` 回 `BAD_SIZE`。
     *   未知大小（`-1`）不得按 0 計配額，呼叫方必須在 fetch 後以實際位元組
     *   再做一次 [quotaVeto]。
     */
    fun downloadVeto(spec: DownloadSpec): String? {
        if (!spec.url.startsWith("https://")) return "NOT_HTTPS"
        val afterScheme = spec.url.removePrefix("https://")
        val authority = afterScheme.substringBefore('/', "")
        if ("@" in authority) return "USERINFO_FORBIDDEN"
        val host = authority.substringBefore(':')
        if (host.isBlank() || host.contains(' ') || host.contains('\t')) return "BAD_HOST"
        if (!spec.sha256Hex.matches(Regex("[0-9a-fA-F]{64}"))) return "SHA256_REQUIRED"
        if (spec.sizeBytes == 0L || spec.sizeBytes < -1L) return "BAD_SIZE"
        return null
    }

    /** SHA256 驗證（位元組比對，下載後必走）。 */
    fun sha256Ok(bytes: ByteArray, expectedHex: String): Boolean {
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex, ignoreCase = true)
    }

    /**
     * OCI 參照守衛（優先路徑）：必須 digest pinning（`name@sha256:<64hex>`）。
     * 未 pin（僅 tag 如 `:latest`）一律否決，避免浮動標籤竄改。
     */
    fun ociVeto(ref: String): String? {
        if (ref.isBlank()) return "EMPTY_REF"
        val at = ref.indexOf('@')
        if (at < 0) return "DIGEST_PIN_REQUIRED"
        val digest = ref.substring(at + 1)
        if (!digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) return "DIGEST_PIN_REQUIRED"
        if (ref.substring(0, at).isBlank()) return "EMPTY_REF"
        return null
    }

    /** Tarball 相容路徑：僅接受 `.tar.gz / .tgz / .tar.xz`。 */
    fun tarballVeto(fileName: String): String? {
        val lower = fileName.lowercase()
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar.xz")) {
            return null
        }
        return "TARBALL_SUFFIX_REQUIRED"
    }

    /**
     * proot 落盤權限守衛：實際 mode 必須等於 [PROOT_BIN_MODE]（`0700`）。
     * null 表示放行，否則為拒絕細碼（呼叫方 fail-closed，不得 spawn）。
     */
    fun prootBinModeVeto(actualMode: String): String? {
        if (actualMode != PROOT_BIN_MODE) return "PROOT_MODE_MISMATCH:$actualMode"
        return null
    }

    /**
     * APK 分段數（500 MiB 一段）。
     *
     * - `0` 位元組回 `0`（空檔無段）。
     * - 負數回 `-1`（哨兵：非法/未知大小，呼叫方先走 [DecompileAnalyze.inputVeto]
     *   拒絕，不得按 0 段計）。
     */
    fun apkSegmentsFor(sizeBytes: Long): Long {
        if (sizeBytes < 0L) return -1L
        if (sizeBytes == 0L) return 0L
        return (sizeBytes + APK_SEGMENT_BYTES - 1) / APK_SEGMENT_BYTES
    }

    /** 容器名 + 路徑合法性一次裁決（路徑拼接前必走）。 */
    fun containerVeto(filesDir: String, name: String): String? {
        if (filesDir.isEmpty()) return "NO_SCOPE"
        if (!isSafeContainerName(name)) return "BAD_CONTAINER_NAME"
        return null
    }
}
