package dev.librepocket.privilege.github

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * R2-A killTree 真子進程驗證（owner P1-1；JVM 已驗，裝置 E2E 為 B3）。
 *
 * 以真 `sh -c "sleep 30 & wait"` 拉起父子兩層 → [ElevatedProcessKiller.killTree]
 * → 斷言父已死＋子孫無存活。舊實現（實作類查表）在 OpenJDK 21+ 抛
 * `IllegalAccessException` 而只殺父，此測試在其下必紅（`sleep` 殘留）。
 *
 * 編譯期注意：單元測試編譯走 SDK 公開表面過濾（`api-versions.xml`），
 * `Process.toHandle/descendants/pid` 等在 android.jar 僅為樁、非公開 API，
 * 直接調用編譯不過；故取 handle 一步走反射（與主碼同式
 * `Process::class.java.getMethod("toHandle")`），取到
 * `ProcessHandle` 後其餘直調（`ProcessHandle` 本體不在 android.jar，
 * 取自 JDK 平台，不受過濾）。運行期為 Host JDK，語義完整。
 *
 * - 僅具 `sh` 的 host 執行；無 `sh`／`sh` 不可跑／子孫不可見即
 *   `assumeTrue` 跳過，不紅；
 * - spawn 後先有界輪詢等子孫現身，避免 kill 跑在 spawn 前使斷言空洞；
 * - 全部等待有界（單步 ≤5s，50ms 步進），不 flaky；
 * - 清理只碰本次收集的 pid（經 `ProcessHandle.of` 精準 `destroyForcibly`），
 *   不用全域掃射（誤殺／自殺風險）。
 */
class ElevatedProcessKillerTest {

    @Test fun killTreeKillsRealParentAndDescendants() {
        val probe = try {
            ProcessBuilder("sh", "-c", "exit 0").start()
        } catch (_: Exception) {
            assumeTrue("host has no sh; skip", false)
            return
        }
        val probeOk = try {
            probe.waitFor(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
        runCatching { probe.destroyForcibly() }
        assumeTrue("host sh cannot run; skip", probeOk)

        val root = ProcessBuilder("sh", "-c", "sleep 30 & wait").start()
        var doomed: List<Long> = emptyList()
        try {
            // 等 sleep 子孫現身（有界 5s）：descendants 不可見或子孫遲未現身
            // 即跳過，不把環境差異判紅。
            val seen = pollUntil(5_000L) { descendantPids(root).isNotEmpty() }
            assumeTrue("descendants unavailable or child never spawned; skip", seen)
            doomed = descendantPids(root)
            assertTrue("expected >=1 descendant, got $doomed", doomed.isNotEmpty())

            ElevatedProcessKiller.killTree(root)

            assertTrue("parent should exit after killTree", root.waitFor(5, TimeUnit.SECONDS))
            assertFalse("parent should not be alive", root.isAlive)
            val survivors = pollAlive(doomed, 5_000L)
            assertTrue("descendants should all be dead, survivors=$survivors", survivors.isEmpty())
        } finally {
            runCatching { root.destroyForcibly() }
            // 精準收尾：只處理本次子孫 pid，不碰無關進程。
            doomed.forEach { pid ->
                runCatching { ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
            }
        }
    }

    @Test fun killTreeDegradesToSingleKillWhenToHandleUnsupported() {
        // PocketGuard SUGGESTION：舊 Android 缺 toHandle/descendants 時退化單殺。
        // JVM 上真進程（ProcessImpl）必有 toHandle，無法自然觸發；以 stub 模擬
        // AOSP 行為：自訂 Process 子類不提供 handle（見 AospFallbackStub），
        // 反射 invoke 即抛（包裝為 InvocationTargetException，cause 為 UOE
        // "…toHandle() not supported"），killTree 不抛、父進程被 destroy、
        // 無異常外逃。主碼 catch (Exception) 收斂（見 killDescendantsReflective）。
        val stub = AospFallbackStub()
        // 前提取 stub 反射語義確為包裝抛（文件化此路徑前提；JDK 基類預設
        // 抛 UOE、AOSP 樁同為 UOE，訊息皆含 "toHandle() not supported"）。
        try {
            Process::class.java.getMethod("toHandle").invoke(stub)
            fail("stub toHandle must throw via reflection")
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            assertTrue(
                "cause must be UOE stub, got $cause",
                cause is UnsupportedOperationException &&
                    (cause.message ?: "").contains("toHandle() not supported"),
            )
        }
        ElevatedProcessKiller.killTree(stub) // 不抛即收斂；抛即此行紅。
        assertTrue("fallback must still destroy parent", stub.destroyed)
    }

    /**
     * 模擬 AOSP/舊 Android 缺 handle 語義（Host JDK 真進程無法自然觸發）。
     *
     * 刻意不覆寫 `toHandle`：JDK 基類預設即抛 UOE（訊息
     * `"class X.toHandle() not supported"`，含 `"toHandle() not supported"`；
     * AOSP 樁同為 UOE `"toHandle() not supported"`)，主碼經宣告類查表反射
     * invoke 即得 `InvocationTargetException`→收斂為關流＋單殺，與舊 Android
     * 缺 API／樁抛走同一 catch 路徑。顯式 `override fun toHandle()` 不可行：
     * AGP api-versions 編譯期隱藏該方法（Kotlin 報 "overrides nothing"，
     * javac 連 `ProcessHandle` 符號都不可見），故以純子類行等價路徑。
     */
    private class AospFallbackStub : Process() {
        var destroyed = false
        var forciblyDestroyed = false
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getInputStream(): java.io.InputStream =
            java.io.ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): java.io.InputStream =
            java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            destroyed = true
            return this
        }
    }

    /**
     * 經反射取 `toHandle`（編譯期 SDK 過濾見類 KDoc），其餘直調。
     * 反射失敗（非 Host JDK 語義）回空表，由呼叫方 `assumeTrue` 跳過。
     */
    private fun descendantPids(p: Process): List<Long> {
        return try {
            val handle = Process::class.java.getMethod("toHandle").invoke(p) as? ProcessHandle
                ?: return emptyList()
            handle.descendants().use { stream -> stream.map { it.pid() }.toList() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pollUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (cond()) return true
            } catch (_: Exception) {
                // 條件求值異常視為未命中，繼續輪詢至超時後跳過。
            }
            Thread.sleep(50)
        }
        return try {
            cond()
        } catch (_: Exception) {
            false
        }
    }

    private fun pollAlive(pids: List<Long>, timeoutMs: Long): List<Long> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var alive = pids
        while (System.currentTimeMillis() < deadline) {
            alive = alive.filter { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) }
            if (alive.isEmpty()) return emptyList()
            Thread.sleep(50)
        }
        return alive.filter { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) }
    }
}
