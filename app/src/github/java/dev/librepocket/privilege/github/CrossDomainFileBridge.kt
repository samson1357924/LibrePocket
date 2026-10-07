package dev.librepocket.privilege.github

import dev.librepocket.files.FileScope
import dev.librepocket.files.MissingSafBridge
import dev.librepocket.files.SafFileBridge
import dev.librepocket.tool.Flavor
import java.io.FileNotFoundException

/**
 * 跨域檔案分派（S3，BACKLOG D09，僅 github 風味）。
 *
 * SAF 優先話術（寫給模型與使用者的降級承諾）：
 * 私有域直行 → 已授權 SAF 直行（系統 picker 逐次授權，無需提權）→
 * 跨域且 [FileScope.decide] 回 needsBridge（橋接已授權）才走
 * [ShizukuFileBridge] → 其餘跨域誠實失敗
 * （`做不到 X（NO_PRIVILEGE）→ 可替代 SAF 授權 → 需要你做 系統 picker 點選`），
 * 禁止虛構成功。
 *
 * 私有域 IO 由呼叫方注入 [privateRead]/[privateWrite]（預設走
 * [java.io.File] 直行）；SAF 樹由 [safBridge]（預設 [MissingSafBridge]
 * 誠實失敗）；跨域由 [shizukuBridge]。
 */
class CrossDomainFileBridge(
    private val privateRoot: String,
    private val safRoots: List<String> = emptyList(),
    private val flavor: Flavor = Flavor.GITHUB,
    private val bridgeGranted: Boolean = false,
    private val safBridge: SafFileBridge = MissingSafBridge(),
    private val shizukuBridge: ShizukuFileBridge = ShizukuFileBridge(),
    private val privateRead: (String) -> ByteArray = { path ->
        java.io.File(path).takeIf { it.isFile }
            ?.readBytes() ?: throw FileNotFoundException(path)
    },
    private val privateWrite: (String, ByteArray) -> Unit = { path, bytes ->
        java.io.File(path).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
    },
    private val privateExists: (String) -> Boolean = { path -> java.io.File(path).exists() },
) {
    fun exists(absolutePath: String): Boolean {
        when (zoneOf(absolutePath)) {
            Zone.PRIVATE -> return privateExists(absolutePath)
            Zone.SAF -> return runCatching { safBridge.exists(absolutePath) }.getOrDefault(false)
            Zone.BRIDGE -> return shizukuBridge.exists(absolutePath, privateRoot, safRoots, flavor, bridgeGranted)
            Zone.DENIED -> return false
        }
    }

    @Throws(FileNotFoundException::class)
    fun read(absolutePath: String): ByteArray {
        when (zoneOf(absolutePath)) {
            Zone.PRIVATE -> return privateRead(absolutePath)
            Zone.SAF -> return safBridge.read(absolutePath)
            Zone.BRIDGE -> return shizukuBridge.read(absolutePath, privateRoot, safRoots, flavor, bridgeGranted)
            Zone.DENIED -> throw FileNotFoundException(
                "cross-domain denied (SAF優先：請先經系統 picker 授權該目錄，或開啟提權橋): $absolutePath",
            )
        }
    }

    @Throws(FileNotFoundException::class)
    fun write(absolutePath: String, bytes: ByteArray) {
        when (zoneOf(absolutePath)) {
            Zone.PRIVATE -> return privateWrite(absolutePath, bytes)
            Zone.SAF -> return safBridge.write(absolutePath, bytes)
            Zone.BRIDGE -> return shizukuBridge.write(absolutePath, bytes, privateRoot, safRoots, flavor, bridgeGranted)
            Zone.DENIED -> throw FileNotFoundException(
                "cross-domain denied (SAF優先：請先經系統 picker 授權該目錄，或開啟提權橋): $absolutePath",
            )
        }
    }

    private enum class Zone { PRIVATE, SAF, BRIDGE, DENIED }

    /**
     * 先 [FileScope.decide] 再分派：`allowed && !needsBridge` 看 zone
     * 走私有域/SAF 直行；`allowed && needsBridge` 才走提權橋；
     * 不允許一律 DENIED。
     */
    private fun zoneOf(path: String): Zone {
        val decision = FileScope.decide(path, privateRoot, safRoots, flavor, bridgeGranted)
        if (!decision.allowed) return Zone.DENIED
        if (decision.needsBridge) return Zone.BRIDGE
        return when (decision.zone) {
            dev.librepocket.files.FileZone.PRIVATE -> Zone.PRIVATE
            dev.librepocket.files.FileZone.SAF_GRANTED -> Zone.SAF
            dev.librepocket.files.FileZone.CROSS_DOMAIN -> Zone.DENIED
        }
    }
}
