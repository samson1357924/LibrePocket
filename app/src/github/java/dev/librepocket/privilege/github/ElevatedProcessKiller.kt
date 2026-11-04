package dev.librepocket.privilege.github

import java.lang.reflect.InaccessibleObjectException

/**
 * 提權子進程清理（B2，Issue #136 B2，僅 github 風味；P1-B 誠實化措辭；R2-A 可驗證化；
 * S2-A 版本矩陣＋best-effort 宣告，不改行為）。
 *
 * 背景：舊 [ElevatedProcessIo.drain] 只殺 `su` 本體（`destroyForcibly`），
 * 其子孫（`su -c` 拉起的真正命令）殘留為 root 孤兒（Issue #136 §5）。
 * 本物件盡力整組 best-effort（JVM/新 Android 才真整組）：先殺子孫再殺本體，並關流以
 * 解除 gobbler 的 blocking I/O。
 *
 * S2-A 版本矩陣（owner P1-2 lifecycle＋P2-3 cancel，不改行為）：
 * - JVM Host JDK（單測）：JDK 9+ `ProcessHandle` 完整 → 真整組（先殺子孫再殺本體；
 *   `ElevatedProcessKillerTest` 真 `sh -c "sleep 30 & wait"` 子進程已驗，父死＋子孫無存活）；
 * - 新 Android（具 `descendants` API）：反射取到 handle＋`descendants` → 同上真整組；
 * - 舊 Android（缺 API 或 AOSP 樁抛錯）：`NoSuchMethodException`／
 *   `UnsupportedOperationException` 或 `ProcessHandle` 類缺席（`NoClassDefFoundError`）
 *   即收斂，實際退化為「關流 + 殺本體 best-effort（單殺 `destroyForcibly`）」，非真整組；
 * - AOSP libcore `Process.toHandle` 預設實作抛 `UnsupportedOperationException`
 *   （源碼見 https://cs.android.com/android/platform/superproject/main/+/main:libcore/ojluni/src/main/java/java/lang/Process.java
 *   ；android.jar 內即 `".toHandle() not supported"` 樁，非公開 API，見 `api-versions.xml`）；
 * - [ShizukuRemoteProcess] 未覆寫 `toHandle`，沿用 JDK 預設抛 `UnsupportedOperationException`，
 *   故遠端樹同為單殺（僅本體；子孫列舉一步即收斂）。
 *
 * S2-A best-effort 宣告（PocketGuard WARN 回應）：假設僅 JDK 9+／具 `ProcessHandle`＋
 * `descendants` 的新平台才真整組，舊平台一律 fallback 單殺 `destroyForcibly`
 * （即上表；版本漂移一律收斂，不崩）。best-effort 單殺，裝置缺口未解；
 * B2 runner 清理驗收未過；activation blocker 見 [PrivilegeActivation]
 * （[PrivilegeActivation.ELEVATED_RUNNERS_READY] 仍 `false`，B1 未完，產品不得接線 runner；
 * 子孫清理裝置 E2E 歸 B3）。
 *
 * 誠實化（P1-B，行為不變）：
 * - Android 上 `Process.toHandle`/`descendants` 若不可用（舊版本缺席，或
 *   SDK 樁直接抛 `UnsupportedOperationException`——android.jar 內即
 *   `".toHandle() not supported"` 樁，非公開 API，見 `api-versions.xml`），
 *   反射即收斂，實際退化為「關流 + 殺本體 best-effort」，非真整組；
 * - [ShizukuRemoteProcess] 未覆寫 `toHandle`，遠端樹同為單殺（僅本體）；
 *   真整組僅在 JVM 單測 / 具 descendants API 的新 Android 才成立。
 *
 * R2-A（owner P1-1）：子孫列舉改「宣告類查表」——舊碼經
 * `process.javaClass.getMethod("toHandle")` 在 OpenJDK 21+ 抛
 * `IllegalAccessException`（`ProcessImpl` 為模組內 package-private 類，
 * 以實作類查表取到的 `Method` 宣告類不可見，`invoke` 即拒；`setAccessible`
 * 又觸 `InaccessibleObjectException`），最終只殺父進程。現改
 * `Process::class.java.getMethod("toHandle")`（宣告類查表，`invoke` 時傳
 * process 實例）＋ `ProcessHandle` 宣告類查
 * `descendants/destroy/destroyForcibly`；`InaccessibleObjectException`
 * 明確收斂（另見內聯註）。JVM 真子進程單測已驗（父死＋子孫無存活，見
 * `ElevatedProcessKillerTest`）；裝置 SELinux/Root 語義差異 E2E 為 B3。
 *
 * - 子孫列舉經反射（`Process.toHandle/descendants`，JDK 9+；API 缺席時
 *   runCatching 收斂為僅殺本體，不崩）；
 * - 任何步驟異常一律收斂（fail-closed 方向是「已盡力殺」，呼叫方仍回
 *   TimedOut，不把清理異常升級為崩潰）；
 * - 單測以 `killer: (Process) -> Unit` 注入假實現斷言「超時/cancel 必經清理」，
 *   不依賴真實子孫（真樹驗證另見 `ElevatedProcessKillerTest`）。
 */
object ElevatedProcessKiller {
    fun killTree(process: Process) {
        runCatching { killDescendantsReflective(process) }
        // 關流先行：gobbler 若卡在 read，關流即抛而退出（見 drain 超時路徑的 join 有界）。
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
    }

    private fun killDescendantsReflective(process: Process) {
        // R2-A：宣告類查表，非實作類查表。實作類多為模組內 package-private
        //（如 JDK 的 ProcessImpl/ProcessHandleImpl，JDK 9+ 強封裝）：以其實
        // 例 `javaClass` 查表取到的 Method 宣告類不可見，invoke 即
        // IllegalAccessException（setAccessible 又觸 InaccessibleObjectException）。
        // 以宣告類（Process/ProcessHandle，public 且 exported）查表則直通。
        val handle = try {
            Process::class.java.getMethod("toHandle").invoke(process) ?: return
        } catch (_: NoSuchMethodException) {
            return // 舊 Android 無此 API：退化關流＋單殺（fail-closed）。
        } catch (_: InaccessibleObjectException) {
            return // JDK 強封裝拒絕：退化關流＋單殺（fail-closed）。
        } catch (_: Exception) {
            return
        }
        // ProcessHandle 類本身缺席（舊 Android）在連結期走 NoClassDefFoundError
        // 收斂，不外抛（外層 killTree 另有 runCatching 縱深）。
        val handleClass: Class<*> = try {
            ProcessHandle::class.java
        } catch (_: NoClassDefFoundError) {
            return
        } catch (_: Exception) {
            return
        }
        val descendants = try {
            handleClass.getMethod("descendants").invoke(handle) as? java.util.stream.Stream<*>
                ?: return
        } catch (_: NoSuchMethodException) {
            return
        } catch (_: InaccessibleObjectException) {
            return
        } catch (_: Exception) {
            return
        }
        // destroy/destroyForcibly 同以宣告類查表（各取一次，迴圈內直調）；
        // 兩者皆缺即無可殺，直接收斂。
        val destroy = runCatching { handleClass.getMethod("destroy") }.getOrNull()
        val destroyForcibly = runCatching { handleClass.getMethod("destroyForcibly") }.getOrNull()
        if (destroy == null && destroyForcibly == null) return
        try {
            descendants.forEach { h ->
                if (h == null) return@forEach
                runCatching { destroy?.invoke(h) }
                runCatching { destroyForcibly?.invoke(h) }
            }
        } catch (_: Exception) {
            // Stream.forEach 本體異常（列舉中途 IO/併發失敗）：單個 handle 已在內層
            // runCatching 逐個收斂，此處僅吞 forEach 本體異常，fail-closed 為
            // 「已盡力殺」，呼叫方仍回 TimedOut，不升級為崩潰；finally 仍關流。
        } finally {
            runCatching { descendants.close() }
        }
    }
}
