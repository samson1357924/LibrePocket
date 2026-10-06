package dev.librepocket.files

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * 檔案域裁決（BACKLOG D05，矩陣「檔案（列/讀/寫自有域）」行）。
 *
 * - 自有域（App 私有目錄）：讀寫直行，READ/WRITE 授權面由呼叫方確認。
 * - 已授權 SAF 樹（使用者經系統 picker 授予）：以前綴白名單建模，
 *   實際 IO 由呼叫方經系統文件介面完成，本類只做路徑裁決（零 Android 依賴）。
 * - 跨域（私有域之外、SAF 授權之外）：play 一律拒絕；full 標記需提權橋
 *   （Shizuku/Root，D09），未授權橋接前同樣拒絕。
 * - 全店合規：本包不申請、不引用全存取權限，跨域只能走使用者逐次授權的 SAF。
 */
enum class FileZone {
    PRIVATE,
    SAF_GRANTED,
    CROSS_DOMAIN,
}

/** 使用者授予的 SAF 樹：以正規化前綴表示，命中即視為授權範圍內。 */
data class SafGrant(val treePrefix: String) {
    fun covers(normalizedAbsolutePath: String): Boolean {
        val root = FileScope.normalize(treePrefix)
        return normalizedAbsolutePath == root ||
            normalizedAbsolutePath.startsWith(root + "/")
    }
}

data class ScopeDecision(
    val allowed: Boolean,
    val zone: FileZone,
    /** 矩陣 §5 投影理由碼：跨域在 play 記風味阻擋，full 未授權橋接記缺權。 */
    val denyReason: DenyReason?,
    /** 機器可讀細碼：CROSS_DOMAIN / NEEDS_BRIDGE，寫審計用。 */
    val code: String?,
    /** full 跨域且橋接已授權時為 true（呼叫方改走 D09 橋，仍需審計）。 */
    val needsBridge: Boolean,
)

object FileScope {
    const val CODE_CROSS_DOMAIN = "CROSS_DOMAIN"
    const val CODE_NEEDS_BRIDGE = "NEEDS_BRIDGE"

    /**
     * 路徑域裁決（純函數，無 IO）。
     *
     * @param path 待存取的絕對路徑（相對路徑視為非法，一律拒絕）。
     * @param privateRoot App 私有域根（絕對路徑）。
     * @param safRoots 已授權 SAF 樹前綴。
     * @param bridgeGranted full 風味下使用者是否已授權提權橋（D09 開關）。
     */
    fun decide(
        path: String,
        privateRoot: String,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
    ): ScopeDecision {
        val norm = normalizeOrNull(path)
            ?: return deny(CODE_CROSS_DOMAIN, DenyReason.FLAVOR_BLOCKED)
        if (!norm.startsWith("/")) {
            return deny(CODE_CROSS_DOMAIN, DenyReason.FLAVOR_BLOCKED)
        }
        val priv = normalizeOrNull(privateRoot)
        if (priv != null && (norm == priv || norm.startsWith(priv + "/"))) {
            return ScopeDecision(true, FileZone.PRIVATE, null, null, false)
        }
        val grants = safRoots.map { SafGrant(it) }
        if (grants.any { it.covers(norm) }) {
            return ScopeDecision(true, FileZone.SAF_GRANTED, null, null, false)
        }
        if (flavor == Flavor.PLAY) {
            return deny(CODE_CROSS_DOMAIN, DenyReason.FLAVOR_BLOCKED)
        }
        if (bridgeGranted) {
            return ScopeDecision(true, FileZone.CROSS_DOMAIN, null, CODE_NEEDS_BRIDGE, true)
        }
        return deny(CODE_NEEDS_BRIDGE, DenyReason.NO_PRIVILEGE)
    }

    private fun deny(code: String, reason: DenyReason): ScopeDecision =
        ScopeDecision(false, FileZone.CROSS_DOMAIN, reason, code, false)

    /**
     * 單段檔名檢查：允許中文/空格/點分隔附檔名；拒絕分隔符、空名、`..`、
     * 控制字元與 NUL。長度以 UTF-8 位元組計，上限 255（常見 FS 單名上限）。
     */
    fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) return false
        for (ch in name) {
            if (ch.isISOControl()) return false
        }
        if (name.toByteArray(Charsets.UTF_8).size > 255) return false
        if (name.trim().isEmpty()) return false
        return true
    }

    /**
     * 相對路徑正規化：拒絕絕對路徑、空路徑、穿越（`..` 逃出根）。
     * 回傳正規化後的相對路徑（`/` 分隔），非法回 null。
     * 中文/空格/深層嵌套一律保留原樣放行。
     */
    fun sanitizeRelative(relativePath: String): String? {
        if (relativePath.isEmpty() || relativePath.contains('\u0000')) return null
        val normalized: String = try {
            Paths.get(relativePath).normalize().toString().replace('\\', '/')
        } catch (_: InvalidPathException) {
            return null
        } catch (_: Exception) {
            return null
        }
        if (normalized.isEmpty() || normalized == "." || normalized.startsWith("/")) return null
        val segments = normalized.split("/")
        if (segments.any { it.isEmpty() || it == "." || it == ".." || !isSafeName(it) }) return null
        return normalized
    }

    /** 絕對路徑詞法正規化（不碰檔案系統，無 IO）。 */
    fun normalize(path: String): String =
        normalizeOrNull(path) ?: path

    private fun normalizeOrNull(path: String): String? {
        if (path.isEmpty() || path.contains('\u0000')) return null
        return try {
            Paths.get(path).normalize().toString().replace('\\', '/')
        } catch (_: InvalidPathException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
