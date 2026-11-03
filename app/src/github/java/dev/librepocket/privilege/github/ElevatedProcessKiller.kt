package dev.librepocket.privilege.github

/**
 * 提權子進程組清理（B2，Issue #136 B2，僅 github 風味）。
 *
 * 背景：舊 [ElevatedProcessIo.drain] 只殺 `su` 本體（`destroyForcibly`），
 * 其子孫（`su -c` 拉起的真正命令）殘留為 root 孤兒（Issue #136 §5）。
 * 本物件先殺子孫再殺本體，並關流以解除 gobbler 的 blocking I/O。
 *
 * - 子孫列舉經反射（`Process.toHandle/descendants`，JDK 9+；舊 Android 無此
 *   API 時 runCatching 收斂為僅殺本體，不崩）；
 * - 任何步驟異常一律收斂（fail-closed 方向是「已盡力殺」，呼叫方仍回
 *   TimedOut，不把清理異常升級為崩潰）；
 * - 單測以 `killer: (Process) -> Unit` 注入假實現斷言「超時/cancel 必經清理」，
 *   不依賴真實子孫。
 */
object ElevatedProcessKiller {
    fun killTree(process: Process) {
        runCatching { killDescendantsReflective(process) }
        // 關流先行：gobbler 若卡在 read，關流即抛而退出（見 drain 的 join 有界）。
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
    }

    private fun killDescendantsReflective(process: Process) {
        val handle = try {
            process.javaClass.getMethod("toHandle").invoke(process) ?: return
        } catch (_: Exception) {
            return
        }
        val descendants = try {
            handle.javaClass.getMethod("descendants").invoke(handle) as? java.util.stream.Stream<*>
                ?: return
        } catch (_: Exception) {
            return
        }
        try {
            descendants.forEach { h ->
                runCatching {
                    h?.javaClass?.getMethod("destroy")?.invoke(h)
                }
                runCatching {
                    h?.javaClass?.getMethod("destroyForcibly")?.invoke(h)
                }
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
