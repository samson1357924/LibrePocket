package dev.librepocket.linux

import dev.librepocket.files.FileScope
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * S4 inbox 進出容器複製（PR#1 re-review head 4e3a6c6 blocker 2 收斂）。
 *
 * 背景：`LinuxEnv.prootCmd` 刻意無 bind（`proot -r <rootfs> -w / -0`），
 * guest 只能看到 rootfs 內部；而 host inbox（`filesDir/linux/tmp/inbox`）是
 * rootfs 的 sibling。直接把 host inbox 絕對路徑當 guest argv 傳入，
 * 在真實 PRoot 下只會 `ENOENT`（FakeRunner 測不出來）。
 *
 * 本物件是唯一的進出通道：
 * - 進：[stageFile] 把 host inbox 檔複製到
 *   `containers/<name>/rootfs/inbox/<id>-<safeName>`，回 guest 路徑
 *   `/inbox/<id>-<safeName>`；呼叫方一律用 guest 路徑組 guest argv。
 * - 出：[collectFile] 把 guest `/outbox/...` 產物反向 bounded copy 到
 *   host `linux/tmp/outbox/`（上限由呼叫方指定，預設 [LinuxEnv.INBOX_MAX_BYTES]）。
 * - [ProotExec.execute] 對未 stage 的 host inbox 路徑 fail-closed
 *  （見 [unstagedInboxRef]），逼呼叫方先走本通道。
 *
 * 本檔案零 Android 依賴，JVM 單測可用真實暫存目錄斷言。
 */
object LinuxInboxStager {

    /**
     * Per-container 互斥（PR#1 comment 6039436820 P1 TOCTOU 收斂）。
     *
     * `stageFile` / `collectFile` 的 canonical 檢查 → `copyTo` 之間曾是
     * check-then-use window：guest 可在 window 內把 `rootfs/inbox`、
     * `rootfs/outbox/<name>` 或 inbox 源換成 symlink，`copyTo` 重解路徑時
     * 跟隨到 rootfs 外。本鎖與 [ProotExec.execute] 的 spawn 臨界共用同一 key
     *（`filesDir + NUL + container`），確保 host staging/collect 時沒有
     * guest 進程能併發修改同一容器的 rootfs 路徑。
     *
     * 另見下方的 no-follow 縱深（symlink 源直拒、dest 預植先刪、bounded
     * streaming copy），鎖是主防護，no-follow 是即使鎖外仍 fail-closed。
     */
    private val containerLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** 同容器共用同一 [ReentrantLock]（不同容器 / 不同 filesDir 互不干擾）。 */
    fun lockFor(filesDir: String, container: String): ReentrantLock {
        val key = filesDir + "\u0000" + container
        return containerLocks.computeIfAbsent(key) { ReentrantLock() }
    }

    /** 同容器臨界區 helper（ProotExec spawn 亦用 [lockFor] 直接加鎖）。 */
    fun <T> withContainerLock(filesDir: String, container: String, block: () -> T): T {
        val lock = lockFor(filesDir, container)
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Guest 可見的進件目錄（rootfs 內絕對路徑）。 */
    const val GUEST_INBOX_DIR = "/inbox"

    /** Guest 可見的產物目錄（rootfs 內絕對路徑，guest 寫產物一律寫此處）。 */
    const val GUEST_OUTBOX_DIR = "/outbox"

    /** 檔名安全字元集（不含 `/`，故不可能跳出目標目錄）。 */
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

    /** stage id 安全字元集（呼叫方自給 id 時檢查用）。 */
    private val SAFE_ID = Regex("[A-Za-z0-9_-]+")

    /** 安全檔名上限（避免超長檔名 + 保證 `<id>-<name>` 可讀）。 */
    const val MAX_NAME_CHARS = 128

    /** Host 側 staged 目錄：`containers/<name>/rootfs/inbox`。 */
    fun stagedDir(filesDir: String, container: String): File =
        File(LinuxEnv.containerRootfs(filesDir, container) + GUEST_INBOX_DIR)

    /** Host 側 guest outbox 目錄：`containers/<name>/rootfs/outbox`。 */
    fun guestOutboxDir(filesDir: String, container: String): File =
        File(LinuxEnv.containerRootfs(filesDir, container) + GUEST_OUTBOX_DIR)

    /** Host 側取回目錄：`linux/tmp/outbox`。 */
    fun hostOutboxDir(filesDir: String): File =
        File(LinuxEnv.tmpDir(filesDir) + "/outbox")

    /**
     * Guest 路徑是否為已 stage 的可見路徑（`/inbox/...`）。
     * [ProotExec] 放行此形狀；host inbox 前綴另由 [unstagedInboxRef] 擋下。
     */
    fun isStagedGuestPath(path: String): Boolean =
        path == GUEST_INBOX_DIR || path.startsWith("$GUEST_INBOX_DIR/")

    /**
     * argv 是否引用未 stage 的 host inbox 路徑（fail-closed 守衛，
     * [ProotExec.execute] 在 validate 之前呼叫）。
     * 命中回否決訊息，否則 null。
     *
     * 比對前先做詞法正規化（`tmp//inbox/x`、`/x/./y` 歸一）並抽 `--opt=/...`
     * 值（與 ShellPolicy 絕對路徑候選同口徑）；漏網變體至多造成真機 ENOENT，
     * 不產生宿主越界（proot `-r` 約束 + PRIVATE 域內）。
     */
    fun unstagedInboxRef(argv: List<String>, filesDir: String): String? {
        if (filesDir.isEmpty()) return null
        val inbox = FileScope.normalize(LinuxEnv.inboxDir(filesDir))
        for (arg in argv) {
            val candidates = mutableListOf(arg)
            val eq = arg.indexOf('=')
            if (eq >= 0 && eq + 1 < arg.length && arg[eq + 1] == '/') {
                candidates.add(arg.substring(eq + 1))
            }
            for (candidate in candidates) {
                val norm = FileScope.normalize(candidate)
                if (norm == inbox || norm.startsWith("$inbox/")) {
                    return "unstaged host inbox path denied (stage via LinuxInboxStager first): $arg"
                }
            }
        }
        return null
    }

    /**
     * Guest argv 是否引用任何 host 側 Linux 樹絕對路徑（fail-closed 守衛，
     * [ProotExec.execute] 在 validate 之前呼叫）。
     *
     * 背景：真實 PRoot 用 `proot -r <container/rootfs> -w /` 且無 bind，
     * guest 命名空間只有 `/inbox/...`、`/outbox/...`、`/etc` 等容器內路徑；
     * 任何指向 `filesDir/linux` 整樹的 host 絕對路徑在 guest 內都只會
     * `ENOENT`（`unstagedInboxRef` 只擋其中 `tmp/inbox` 一支，同一
     * namespace confusion 仍存在於 `containers/.../rootfs/...`、
     * `cache/...`、`tmp/outbox/...`、`bin/proot` 等）。
     *
     * 故 guest argv 一律用 guest namespace：host↔guest 資料只經
     * [stageFile]/[collectFile] 顯式映射；命中回否決訊息，否則 null。
     * 比對口徑與 [unstagedInboxRef] 一致（含 `--opt=/...` 值抽取）。
     */
    fun hostLinuxAbsoluteRef(argv: List<String>, filesDir: String): String? {
        if (filesDir.isEmpty()) return null
        val root = FileScope.normalize(LinuxEnv.root(filesDir))
        for (arg in argv) {
            val candidates = mutableListOf(arg)
            val eq = arg.indexOf('=')
            if (eq >= 0 && eq + 1 < arg.length && arg[eq + 1] == '/') {
                candidates.add(arg.substring(eq + 1))
            }
            for (candidate in candidates) {
                val norm = FileScope.normalize(candidate)
                if (norm == root || norm.startsWith("$root/")) {
                    return "host linux path denied in guest argv (use /inbox/... guest paths via LinuxInboxStager): $arg"
                }
            }
        }
        return null
    }

    /** 檔名清洗：非法字元逐字換 `_`，空結果回 `file`，超長截斷。 */
    fun safeName(raw: String): String {
        val cleaned = raw.map { if (SAFE_NAME.matches(it.toString())) it else '_' }.joinToString("")
            .trim('.', '_')
        val nonEmpty = cleaned.ifEmpty { "file" }
        return if (nonEmpty.length > MAX_NAME_CHARS) nonEmpty.take(MAX_NAME_CHARS) else nonEmpty
    }

    sealed interface StageOutcome {
        /** 成功：[guestPath] 即 guest argv 該用的路徑（如 `/inbox/<id>-<name>`）。 */
        data class Staged(val guestPath: String, val sizeBytes: Long, val hostFile: File) : StageOutcome

        /** 拒絕：[code] 為 `LinuxEnv.inboxVeto/quotaVeto` 細碼或本檔案定義碼。 */
        data class Denied(val code: String) : StageOutcome
    }

    sealed interface CollectOutcome {
        data class Collected(val hostFile: File, val sizeBytes: Long) : CollectOutcome
        data class Denied(val code: String) : CollectOutcome
    }

    /**
     * No-follow 縱深 helper（PR#1 comment 6039436820 P1）。
     *
     * - 源 symlink 直拒（`Files.isSymbolicLink` 預檢）：host inbox 源 / guest
     *   outbox 產物若本身是 symlink，一律 `NOT_A_FILE`，不跟隨讀取目標。
     * - 目標預植 symlink 先刪（`File.delete` 刪的是 link 本身，不跟隨）；
     *   刪後仍存在即失敗，不跟隨覆寫外部。
     * - Bounded streaming copy：超 `maxBytes` 即 abort（呼叫方刪檔），避免
     *   `length()` 取樣後膨脹撐爆磁碟；回傳實際拷貝位元組，呼叫方再與取樣
     *   `size` 比對（`SIZE_MISMATCH`）。
     */
    private fun isSymlink(f: File): Boolean =
        try {
            Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) {
            false
        }

    private fun copyBoundedNoFollow(src: File, dest: File, maxBytes: Long): Long? {
        try {
            if (isSymlink(src)) return null
            if (isSymlink(dest)) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                    return null
                }
                if (dest.exists() || isSymlink(dest)) return null
            }
            Files.newInputStream(src.toPath()).use { input ->
                Files.newOutputStream(
                    dest.toPath(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE,
                ).use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return null
                        output.write(buf, 0, n)
                    }
                    return total
                }
            }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 進件：host inbox 檔 → rootfs 內 inbox，回 guest 可見路徑。
     *
     * 門禁順序：容器/作用域 → 僅 inbox 樹內來源（canonical containment，
     * 非 inbox 來源一律 `NOT_INBOX_SOURCE`）→ 檔存在且為普通檔 →
     * [LinuxEnv.inboxVeto]（10 MiB）→ [LinuxEnv.quotaVeto]
     * （已用 + 本次，按壓縮/位元組計；解包膨脹另由 unpacker 二次裁決）→
     * 複製 → 位元組數覆核（`SIZE_MISMATCH`）。
     *
     * 併發安全（PR#1 comment 6039436820 P1）：全程持有同容器
     * [lockFor]（與 [ProotExec.execute] spawn 臨界同鎖），check→copy→覆核
     * 不可被同容器 guest 執行穿插；另加 symlink 源直拒 + dest 預植先刪 +
     * bounded copy 縱深。
     *
     * @param id 檔名前綴（預設隨機 8 hex；測試可固定以便斷言）。
     */
    fun stageFile(
        filesDir: String,
        container: String,
        source: File,
        id: String = UUID.randomUUID().toString().take(8),
        usedTotalBytes: Long = 0L,
        usedContainerBytes: Long = 0L,
    ): StageOutcome {
        val lock = lockFor(filesDir, container)
        lock.lock()
        try {
            if (LinuxEnv.containerVeto(filesDir, container) != null) return StageOutcome.Denied("BAD_CONTAINER_OR_SCOPE")
            if (!SAFE_ID.matches(id)) return StageOutcome.Denied("BAD_STAGE_ID")
            // Symlink 源直拒（不跟隨）：合法 inbox 源應為 regular file。
            if (isSymlink(source)) return StageOutcome.Denied("NOT_A_FILE")
            val inboxRoot = try {
                File(LinuxEnv.inboxDir(filesDir)).canonicalFile
            } catch (_: Exception) {
                return StageOutcome.Denied("BAD_CONTAINER_OR_SCOPE")
            }
            val src = try {
                source.canonicalFile
            } catch (_: Exception) {
                return StageOutcome.Denied("NOT_INBOX_SOURCE")
            }
            if (src != inboxRoot && !src.path.startsWith(inboxRoot.path + File.separator)) {
                return StageOutcome.Denied("NOT_INBOX_SOURCE")
            }
            if (isSymlink(src)) return StageOutcome.Denied("NOT_A_FILE")
            if (!src.isFile) return StageOutcome.Denied("NOT_A_FILE")
            val size = src.length()
            val inboxVeto = LinuxEnv.inboxVeto(size)
            if (inboxVeto != null) return StageOutcome.Denied(inboxVeto)
            val quotaVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, size)
            if (quotaVeto != null) return StageOutcome.Denied(quotaVeto)
            val destDir = stagedDir(filesDir, container)
            if (!destDir.isDirectory && !destDir.mkdirs()) return StageOutcome.Denied("STAGE_MKDIRS_FAILED")
            // 縱深：staged 目錄 canonical 必須仍在 rootfs 內（防 container 名拼接異常）。
            val rootfs = try {
                File(LinuxEnv.containerRootfs(filesDir, container)).canonicalFile
            } catch (_: Exception) {
                return StageOutcome.Denied("BAD_CONTAINER_OR_SCOPE")
            }
            val destDirCanonical = try {
                destDir.canonicalFile
            } catch (_: Exception) {
                return StageOutcome.Denied("STAGE_MKDIRS_FAILED")
            }
            if (destDirCanonical != rootfs && !destDirCanonical.path.startsWith(rootfs.path + File.separator)) {
                return StageOutcome.Denied("STAGE_ESCAPES_ROOTFS")
            }
            // dest 若被預植成 symlink：先刪 link 本身（不跟隨），刪不掉即 fail-closed。
            val dest = File(destDirCanonical, "$id-${safeName(src.name)}")
            if (isSymlink(dest)) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                    return StageOutcome.Denied("STAGE_COPY_FAILED")
                }
                if (dest.exists() || isSymlink(dest)) return StageOutcome.Denied("STAGE_COPY_FAILED")
            }
            val copied = copyBoundedNoFollow(src, dest, LinuxEnv.INBOX_MAX_BYTES)
            if (copied == null) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                }
                // 超 inbox 上限的膨脹與 symlink 跟隨一律 fail-closed。
                return StageOutcome.Denied("STAGE_COPY_FAILED")
            }
            if (!dest.isFile || dest.length() != size || copied != size) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                }
                return StageOutcome.Denied("SIZE_MISMATCH")
            }
            return StageOutcome.Staged(
                guestPath = "$GUEST_INBOX_DIR/${dest.name}",
                sizeBytes = size,
                hostFile = dest,
            )
        } finally {
            lock.unlock()
        }
    }

    /**
     * 取回：guest `/outbox/<name>` → host `linux/tmp/outbox/<name>`
     * （bounded copy，上限 [maxBytes]，預設 10 MiB）。
     *
     * 門禁順序：容器/作用域 → guest 路徑必須是 `/outbox/` 下單段檔名
     * （`GUEST_PATH_REJECTED`，含 `/inbox` 輸入、巢狀、`..` 一律拒）→
     * outbox 根 canonical 必須嚴格等於 `rootfs/outbox`
     * （`OUTBOX_ESCAPES_ROOTFS`，防 rootfs 內 `outbox -> <rootfs 外>` 目錄
     * symlink 讓 canonical 塌縮後 `parent == outbox` 恆成立）→
     * 來源存在且為普通檔 → 大小上限（`OUTPUT_TOO_LARGE`）→
     * [LinuxEnv.quotaVeto] 總量/容器配額（`usedTotalBytes`/
     * `usedContainerBytes`，collect 是複製故須計入，與 [stageFile] 同策）→
     * 複製 → 覆核。
     *
     * 併發安全（PR#1 comment 6039436820 P1）：全程持有同容器 [lockFor]
     *（與 [ProotExec.execute] spawn 臨界同鎖），check→copy→覆核不可被
     * 同容器 guest 寫入穿插；guest 產物 symlink 直拒 + bounded copy。
     */
    fun collectFile(
        filesDir: String,
        container: String,
        guestPath: String,
        maxBytes: Long = LinuxEnv.INBOX_MAX_BYTES,
        usedTotalBytes: Long = 0L,
        usedContainerBytes: Long = 0L,
    ): CollectOutcome {
        val lock = lockFor(filesDir, container)
        lock.lock()
        try {
            if (LinuxEnv.containerVeto(filesDir, container) != null) return CollectOutcome.Denied("BAD_CONTAINER_OR_SCOPE")
            if (maxBytes < 0) return CollectOutcome.Denied("NEGATIVE_SIZE")
            val prefix = "$GUEST_OUTBOX_DIR/"
            if (!guestPath.startsWith(prefix)) return CollectOutcome.Denied("GUEST_PATH_REJECTED")
            val name = guestPath.removePrefix(prefix)
            if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") {
                return CollectOutcome.Denied("GUEST_PATH_REJECTED")
            }
            if (!SAFE_NAME.matches(name)) return CollectOutcome.Denied("GUEST_PATH_REJECTED")
            // 縱深：先把 rootfs canonical 化，再要求 outbox 根 canonical 嚴格等於
            // `rootfs/outbox`（字串全等）。若 rootfs/outbox 本身是 symlink（指向
            // rootfs 外或 rootfs 內他處），canonical 會塌縮到他處，此處即 fail-closed，
            // 不會走到後續 `src.parent == outbox` 的恆真比較。
            val rootfs = try {
                File(LinuxEnv.containerRootfs(filesDir, container)).canonicalFile
            } catch (_: Exception) {
                return CollectOutcome.Denied("BAD_CONTAINER_OR_SCOPE")
            }
            val src = File(guestOutboxDir(filesDir, container), name)
            // Guest 產物 symlink 直拒（不跟隨到 /etc/passwd 等宿主檔）。
            if (isSymlink(src)) return CollectOutcome.Denied("NOT_A_FILE")
            val srcCanonical = try {
                src.canonicalFile
            } catch (_: Exception) {
                return CollectOutcome.Denied("NOT_A_FILE")
            }
            val outboxRoot = try {
                guestOutboxDir(filesDir, container).canonicalFile
            } catch (_: Exception) {
                return CollectOutcome.Denied("BAD_CONTAINER_OR_SCOPE")
            }
            val expectedOutbox = rootfs.path + File.separator + "outbox"
            if (outboxRoot.path != expectedOutbox) return CollectOutcome.Denied("OUTBOX_ESCAPES_ROOTFS")
            if (srcCanonical.parent != outboxRoot.path) return CollectOutcome.Denied("GUEST_PATH_REJECTED")
            if (isSymlink(srcCanonical)) return CollectOutcome.Denied("NOT_A_FILE")
            if (!srcCanonical.isFile) return CollectOutcome.Denied("NOT_A_FILE")
            val size = srcCanonical.length()
            if (size > maxBytes) return CollectOutcome.Denied("OUTPUT_TOO_LARGE")
            val quotaVeto = LinuxEnv.quotaVeto(usedTotalBytes, usedContainerBytes, size)
            if (quotaVeto != null) return CollectOutcome.Denied(quotaVeto)
            val destDir = hostOutboxDir(filesDir)
            if (!destDir.isDirectory && !destDir.mkdirs()) return CollectOutcome.Denied("COLLECT_MKDIRS_FAILED")
            val dest = File(destDir, safeName(name))
            if (isSymlink(dest)) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                    return CollectOutcome.Denied("COLLECT_COPY_FAILED")
                }
                if (dest.exists() || isSymlink(dest)) return CollectOutcome.Denied("COLLECT_COPY_FAILED")
            }
            // 持鎖臨界內 bounded copy：超 maxBytes 即 abort，不跟隨 symlink。
            val copied = copyBoundedNoFollow(srcCanonical, dest, maxBytes)
            if (copied == null) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                }
                return CollectOutcome.Denied("COLLECT_COPY_FAILED")
            }
            if (!dest.isFile || dest.length() != size || copied != size) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                }
                return CollectOutcome.Denied("SIZE_MISMATCH")
            }
            return CollectOutcome.Collected(hostFile = dest, sizeBytes = size)
        } finally {
            lock.unlock()
        }
    }
}
