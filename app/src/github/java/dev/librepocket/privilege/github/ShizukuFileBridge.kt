package dev.librepocket.privilege.github

import dev.librepocket.files.FileScope
import dev.librepocket.tool.Flavor
import java.io.FileNotFoundException

/**
 * Shizuku 跨域檔案橋（S3，BACKLOG D09，矩陣「檔案（列/讀/寫自有域）」行，
 * 僅 github 風味）。
 *
 * SAF 優先：本橋只處理「私有域之外、已授權 SAF 之外」的跨域路徑，
 * 且必須先經 [FileScope.decide] 回 needsBridge（橋接已授權）才行；
 * 私有域 / 已授權 SAF 請直行（見 [CrossDomainFileBridge] 的分派），
 * 不要繞橋讀寫。條件不符一律抛 [FileNotFoundException]/[SecurityException]，
 * 絕不靜默降級讀別的路徑。
 *
 * 遠端 IO 經注入的 [remoteRead]/[remoteWrite]/[remoteExists]/[remoteList]
 *（產品接線走 Shizuku UserService/遠端進程；預設未接線，呼叫即誠實失敗，
 * 呼叫方回落「SAF 逐次授權」手動路徑）。binder 不可達（pingBinder
 * runCatching → false）同樣誠實失敗，不崩。
 */
class ShizukuFileBridge(
    private val pingBinder: () -> Boolean = { ShizukuProbe.ping() },
    private val remoteRead: (String) -> ByteArray = { path -> throw FileNotFoundException("shizuku file bridge not wired: $path") },
    private val remoteWrite: (String, ByteArray) -> Unit = { path, _ -> throw FileNotFoundException("shizuku file bridge not wired: $path") },
    private val remoteExists: (String) -> Boolean = { pingOrThrow(); false },
    private val remoteList: (String) -> List<String> = { pingOrThrow(); emptyList() },
) {
    fun exists(
        absolutePath: String,
        privateRoot: String,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.GITHUB,
        bridgeGranted: Boolean = false,
    ): Boolean {
        requireBridge(absolutePath, privateRoot, safRoots, flavor, bridgeGranted)
        return runCatching { remoteExists(absolutePath) }.getOrDefault(false)
    }

    @Throws(FileNotFoundException::class)
    fun read(
        absolutePath: String,
        privateRoot: String,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.GITHUB,
        bridgeGranted: Boolean = false,
    ): ByteArray {
        requireBridge(absolutePath, privateRoot, safRoots, flavor, bridgeGranted)
        return remoteRead(absolutePath)
    }

    @Throws(FileNotFoundException::class)
    fun write(
        absolutePath: String,
        bytes: ByteArray,
        privateRoot: String,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.GITHUB,
        bridgeGranted: Boolean = false,
    ) {
        requireBridge(absolutePath, privateRoot, safRoots, flavor, bridgeGranted)
        remoteWrite(absolutePath, bytes)
    }

    fun list(
        absolutePrefix: String,
        privateRoot: String,
        safRoots: List<String> = emptyList(),
        flavor: Flavor = Flavor.GITHUB,
        bridgeGranted: Boolean = false,
    ): List<String> {
        requireBridge(absolutePrefix, privateRoot, safRoots, flavor, bridgeGranted)
        return runCatching { remoteList(absolutePrefix) }.getOrDefault(emptyList())
    }

    /**
     * 先 [FileScope.decide]：僅 `allowed && needsBridge` 放行跨域；
     * 私有域/SAF_GRANTED（SAF 優先直行）與未授權跨域一律拒絕。
     */
    private fun requireBridge(
        path: String,
        privateRoot: String,
        safRoots: List<String>,
        flavor: Flavor,
        bridgeGranted: Boolean,
    ) {
        val decision = FileScope.decide(path, privateRoot, safRoots, flavor, bridgeGranted)
        if (!decision.allowed || !decision.needsBridge) {
            throw SecurityException(
                "cross-domain bridge denied (SAF優先：私有域/SAF請直行，跨域需提權橋授權): $path",
            )
        }
        if (!runCatching { pingBinder() }.getOrDefault(false)) {
            throw FileNotFoundException("shizuku binder unreachable: $path")
        }
        // binder 可達性已由 pingBinder 守衛；本類對 Shizuku 的依賴由
        // [ShizukuProbe]/[ShizukuShellRunner] 的真實 binder 呼叫承載，
        // 此處不再做無意義的顯式引用。
    }

    companion object {
        private fun pingOrThrow(): Nothing =
            throw FileNotFoundException("shizuku binder unreachable or bridge not wired")
    }
}
