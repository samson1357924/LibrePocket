package dev.librepocket.files

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.S1bFallback
import java.io.FileNotFoundException

/**
 * S1-A `file.edit` + `file.patch`（WRITE，開關 `files`，矩陣「檔案」行）。
 *
 * 域裁決（沿 [FileScope.decide]，零重複邏輯）：
 * - 私有域：[ScopedFileStore] 直行（冪等 + 原子寫入）。
 * - 已授權 SAF 樹：[SafFileBridge] 直行（使用者逐次授權範圍內）。
 * - 跨域：play 回 FLAVOR_BLOCKED + CROSS_DOMAIN；foss/github 未授權橋接
 *   回 NO_PRIVILEGE + NEEDS_BRIDGE；即使橋接已授權（`needsBridge`），
 *   main 源集無 D09 提權橋實現，執行器仍誠實拒絕（NO_PRIVILEGE）。
 * - patch 語義：`oldText` 定位（預設要求唯一命中）→ 先寫 `.bak` 備份
 *   → 原子覆寫；找不到/多命中/檔案過大一律不改原檔。
 *
 * 本檔零 Android 依賴；SAF 端側見 [AndroidSafFileBridge]。
 */
object FileEditTools {

    const val EDIT_NAME = "file.edit"
    const val PATCH_NAME = "file.patch"

    const val SWITCH = "files"
    const val SWITCH_DEFAULT = true

    const val FALLBACK_HINT =
        "edit the file manually in the system Files app, or grant a SAF tree and retry"

    /** patch 讀入上限：更大請改用全量覆寫（避免大檔進記憶體）。 */
    const val MAX_PATCH_BYTES = 512 * 1024

    sealed interface Target {
        data class Private(val relativePath: String) : Target
        data class Saf(val absolutePath: String) : Target
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : Target
        data class Invalid(val detail: String, val message: String) : Target
    }

    /**
     * 路徑裁決（純函數）：相對路徑視為私有域簡寫；絕對路徑經
     * [FileScope.decide]。[verb] 僅用於降級話術（"寫入"/"讀取"）。
     */
    fun resolve(
        path: String,
        privateRoot: String,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        enabled: Boolean = true,
        verb: String = "寫入",
    ): Target {
        if (!enabled) {
            return Target.Denied(
                DenyReason.USER_DISABLED,
                "SWITCH_OFF",
                S1bFallback.message(
                    what = "${verb}「$path」（SWITCH_OFF）",
                    reason = DenyReason.USER_DISABLED,
                    detail = "SWITCH_OFF",
                    alternative = FALLBACK_HINT,
                    needFromUser = "到設定開啟「檔案」開關後重試",
                ),
            )
        }
        if (path.isEmpty() || path.contains('\u0000')) {
            return Target.Invalid("BAD_PATH", "路徑非法：不可為空或包含 NUL")
        }
        return if (path.startsWith("/")) {
            resolveAbsolute(path, privateRoot, safRoots, flavor, bridgeGranted, verb)
        } else {
            val safe = FileScope.sanitizeRelative(path)
                ?: return Target.Invalid("BAD_PATH", "路徑非法或穿越私有域：「$path」，未修改")
            Target.Private(safe)
        }
    }

    private fun resolveAbsolute(
        path: String,
        privateRoot: String,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
        verb: String,
    ): Target {
        val decision = FileScope.decide(path, privateRoot, safRoots, flavor, bridgeGranted)
        if (!decision.allowed) {
            val reason = decision.denyReason ?: DenyReason.FLAVOR_BLOCKED
            val detail = decision.code ?: FileScope.CODE_CROSS_DOMAIN
            return Target.Denied(
                reason,
                detail,
                S1bFallback.message(
                    what = "${verb}「$path」（$detail）",
                    reason = reason,
                    detail = detail,
                    alternative = "把檔案搬進 App 私有域或經 SAF 授權後重試",
                    needFromUser = "手動搬檔或重新授權",
                ),
            )
        }
        if (decision.needsBridge) {
            return Target.Denied(
                DenyReason.NO_PRIVILEGE,
                FileScope.CODE_NEEDS_BRIDGE,
                S1bFallback.message(
                    what = "${verb}「$path」（${FileScope.CODE_NEEDS_BRIDGE}）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = FileScope.CODE_NEEDS_BRIDGE,
                    alternative = "把檔案搬進 App 私有域或經 SAF 授權後重試",
                    needFromUser = "手動搬檔、重新授權，或等待 D09 提權橋接通",
                ),
            )
        }
        return when (decision.zone) {
            FileZone.PRIVATE -> {
                val priv = FileScope.normalize(privateRoot)
                val norm = FileScope.normalize(path)
                if (norm == priv) {
                    Target.Invalid("IS_DIRECTORY", "路徑為目錄而非檔案：「$path」，未修改")
                } else {
                    Target.Private(norm.removePrefix("$priv/"))
                }
            }
            FileZone.SAF_GRANTED -> Target.Saf(FileScope.normalize(path))
            FileZone.CROSS_DOMAIN ->
                Target.Denied(
                    DenyReason.NO_PRIVILEGE,
                    FileScope.CODE_CROSS_DOMAIN,
                    S1bFallback.message(
                        what = "${verb}「$path」（${FileScope.CODE_CROSS_DOMAIN}）",
                        reason = DenyReason.NO_PRIVILEGE,
                        detail = FileScope.CODE_CROSS_DOMAIN,
                        alternative = FALLBACK_HINT,
                        needFromUser = "手動搬檔或重新授權",
                    ),
                )
        }
    }
}

enum class FileWriteOutcome {
    Created,
    Updated,
    Unchanged,
    SafWritten,
}

data class FileWriteResult(
    val ok: Boolean,
    val outcome: FileWriteOutcome? = null,
    val backupPath: String? = null,
    val sha256Hex: String? = null,
    val reason: DenyReason? = null,
    val detail: String? = null,
    val message: String = "",
)

/**
 * `file.edit` / `file.patch` 執行器（S1-A 骨架）。
 *
 * [privateRoot] 為 App 私有域根的絕對路徑（例
 * `context.filesDir.absolutePath`），必須與 [store] 的根一致；
 * 不一致時私有域相對化可能錯位，建構時即校验。
 */
class FileEditExecutor(
    private val store: ScopedFileStore,
    private val privateRoot: String,
    private val safRoots: List<String> = emptyList(),
    private val flavor: Flavor = Flavor.PLAY,
    private val bridgeGranted: Boolean = false,
    private val safBridge: SafFileBridge = MissingSafBridge(),
    private val filesEnabled: Boolean = true,
) {
    init {
        require(privateRoot.isNotEmpty() && !privateRoot.contains('\u0000')) {
            "privateRoot must be a non-empty path"
        }
    }

    private fun resolve(path: String, verb: String): FileEditTools.Target =
        FileEditTools.resolve(path, privateRoot, safRoots, flavor, bridgeGranted, filesEnabled, verb)

    private fun denied(t: FileEditTools.Target.Denied): FileWriteResult =
        FileWriteResult(false, reason = t.reason, detail = t.detail, message = t.message)

    private fun invalid(t: FileEditTools.Target.Invalid): FileWriteResult =
        FileWriteResult(false, detail = t.detail, message = t.message)

    private fun failed(detail: String, message: String): FileWriteResult =
        FileWriteResult(false, detail = detail, message = message)

    /** 全量覆寫（冪等：內容一致即 Unchanged 且不改 mtime）。 */
    fun edit(path: String, bytes: ByteArray): FileWriteResult {
        return when (val t = resolve(path, "寫入")) {
            is FileEditTools.Target.Denied -> denied(t)
            is FileEditTools.Target.Invalid -> invalid(t)
            is FileEditTools.Target.Private -> {
                try {
                    val outcome = when (store.write(t.relativePath, bytes)) {
                        ScopedFileStore.WriteOutcome.Created -> FileWriteOutcome.Created
                        ScopedFileStore.WriteOutcome.Updated -> FileWriteOutcome.Updated
                        ScopedFileStore.WriteOutcome.Unchanged -> FileWriteOutcome.Unchanged
                    }
                    FileWriteResult(
                        ok = true,
                        outcome = outcome,
                        sha256Hex = ScopedFileStore.sha256Hex(bytes),
                        message = "已寫入「$path」（$outcome）",
                    )
                } catch (e: IllegalArgumentException) {
                    failed("BAD_PATH", "路徑非法：「$path」（${e.message})，未修改")
                } catch (e: Exception) {
                    failed("WRITE_FAILED", "寫入失敗「$path」：${e.message}")
                }
            }
            is FileEditTools.Target.Saf -> {
                try {
                    safBridge.write(t.absolutePath, bytes)
                    FileWriteResult(
                        ok = true,
                        outcome = FileWriteOutcome.SafWritten,
                        sha256Hex = ScopedFileStore.sha256Hex(bytes),
                        message = "已寫入已授權文件「$path」",
                    )
                } catch (e: FileNotFoundException) {
                    failed("SAF_GONE", "寫入失敗：文件不存在或授權已失效「$path」")
                } catch (e: Exception) {
                    failed("WRITE_FAILED", "寫入失敗「$path」：${e.message}")
                }
            }
        }
    }

    fun editText(path: String, content: String): FileWriteResult =
        edit(path, content.toByteArray(Charsets.UTF_8))

    /**
     * 局部替換：先備份（`path + ".bak"`，同域），再原子覆寫。
     * 任何前置失敗（找不到/多命中/過大/備份失敗）都不碰原檔。
     */
    fun patch(path: String, oldText: String, newText: String, singleMatch: Boolean = true): FileWriteResult {
        if (oldText.isEmpty()) {
            return failed("EMPTY_MATCH", "比對文字不可為空，未修改「$path」")
        }
        val target = resolve(path, "寫入")
        if (target is FileEditTools.Target.Denied) return denied(target)
        if (target is FileEditTools.Target.Invalid) return invalid(target)
        val current = try {
            readBytes(target)
        } catch (e: FileNotFoundException) {
            return failed("NOT_FOUND", "檔案不存在，先用 file.edit 建立「$path」")
        } catch (e: Exception) {
            return failed("READ_FAILED", "讀取失敗「$path」：${e.message}")
        }
        if (current.size > FileEditTools.MAX_PATCH_BYTES) {
            return failed(
                "FILE_TOO_LARGE",
                "檔案過大（${current.size} 位元組，上限 ${FileEditTools.MAX_PATCH_BYTES}），改用 file.edit 全量覆寫「$path」",
            )
        }
        val text = String(current, Charsets.UTF_8)
        val count = text.split(oldText).size - 1
        if (count == 0) {
            return failed("NO_MATCH", "找不到相符文字（共 0 處），未修改「$path」")
        }
        if (singleMatch && count != 1) {
            return failed("MULTI_MATCH", "有多處相符（共 $count 處），需指明唯一文段，未修改「$path」")
        }
        val patched = text.replace(oldText, newText).toByteArray(Charsets.UTF_8)
        if (patched.contentEquals(current)) {
            return FileWriteResult(
                ok = true,
                outcome = FileWriteOutcome.Unchanged,
                sha256Hex = ScopedFileStore.sha256Hex(current),
                message = "內容一致，未修改「$path」",
            )
        }
        val backupPath = backupName(target)
        try {
            writeBytes(backupPath, current)
        } catch (e: Exception) {
            return failed("BACKUP_FAILED", "備份失敗，未修改「$path」：${e.message}")
        }
        return try {
            val outcome = writeOutcome(backupPath, patched, target)
            FileWriteResult(
                ok = true,
                outcome = outcome,
                backupPath = backupDisplayName(backupPath),
                sha256Hex = ScopedFileStore.sha256Hex(patched),
                message = "已修補「$path」（備份：${backupDisplayName(backupPath)}）",
            )
        } catch (e: Exception) {
            failed(
                "WRITE_FAILED",
                "寫入失敗「$path」，可用備份「${backupDisplayName(backupPath)}」還原：${e.message}",
            )
        }
    }

    private fun readBytes(target: FileEditTools.Target): ByteArray =
        when (target) {
            is FileEditTools.Target.Private -> store.read(target.relativePath)
            is FileEditTools.Target.Saf -> safBridge.read(target.absolutePath)
            is FileEditTools.Target.Denied -> throw IllegalStateException("unreachable")
            is FileEditTools.Target.Invalid -> throw IllegalStateException("unreachable")
        }

    private fun backupName(target: FileEditTools.Target): FileEditTools.Target =
        when (target) {
            is FileEditTools.Target.Private -> FileEditTools.Target.Private(target.relativePath + ".bak")
            is FileEditTools.Target.Saf -> FileEditTools.Target.Saf(target.absolutePath + ".bak")
            is FileEditTools.Target.Denied -> throw IllegalStateException("unreachable")
            is FileEditTools.Target.Invalid -> throw IllegalStateException("unreachable")
        }

    private fun backupDisplayName(backup: FileEditTools.Target): String =
        when (backup) {
            is FileEditTools.Target.Private -> backup.relativePath
            is FileEditTools.Target.Saf -> backup.absolutePath
            is FileEditTools.Target.Denied -> throw IllegalStateException("unreachable")
            is FileEditTools.Target.Invalid -> throw IllegalStateException("unreachable")
        }

    private fun writeBytes(backup: FileEditTools.Target, bytes: ByteArray) {
        when (backup) {
            is FileEditTools.Target.Private -> store.write(backup.relativePath, bytes)
            is FileEditTools.Target.Saf -> safBridge.write(backup.absolutePath, bytes)
            is FileEditTools.Target.Denied -> throw IllegalStateException("unreachable")
            is FileEditTools.Target.Invalid -> throw IllegalStateException("unreachable")
        }
    }

    private fun writeOutcome(
        backup: FileEditTools.Target,
        bytes: ByteArray,
        target: FileEditTools.Target,
    ): FileWriteOutcome {
        return when (target) {
            is FileEditTools.Target.Private -> {
                when (store.write(target.relativePath, bytes)) {
                    ScopedFileStore.WriteOutcome.Created -> FileWriteOutcome.Created
                    ScopedFileStore.WriteOutcome.Updated -> FileWriteOutcome.Updated
                    ScopedFileStore.WriteOutcome.Unchanged -> FileWriteOutcome.Unchanged
                }
            }
            is FileEditTools.Target.Saf -> {
                safBridge.write(target.absolutePath, bytes)
                FileWriteOutcome.SafWritten
            }
            is FileEditTools.Target.Denied -> throw IllegalStateException("unreachable")
            is FileEditTools.Target.Invalid -> throw IllegalStateException("unreachable")
        }
    }
}
