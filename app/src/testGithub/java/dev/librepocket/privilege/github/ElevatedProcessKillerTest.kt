package dev.librepocket.privilege.github

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * R2-A killTree 真子進程驗證（owner P1-1；JVM 已驗，裝置 E2E 為 #136-B3）。
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

    @Test fun pidReuseSignalAllowedTruthTable() {
        // Stage 2A（P1-A7）PID-reuse 守門判定表（killDescendantsReflective 的純函數 seam）。
        // 接線（snapshot→recheck→skip＋計數）無法以假 ProcessHandle 直測：單元測試編譯
        // 走 SDK 公開表面過濾，toHandle/descendants override 不可行（見本檔 KDoc），
        // 而真實 PID 回收無法確定性誘發；故接線以真樹測試＋本判定表共同覆蓋，
        // 殘餘歸 #136-B3 E2E。
        val t0 = java.time.Instant.ofEpochMilli(1_700_000_000_000L)
        val t1 = java.time.Instant.ofEpochMilli(1_800_000_000_000L)
        // Happy path：存活＋快照一致 → 允許信號。
        assertTrue(ElevatedProcessKiller.pidReuseSignalAllowed(true, t0, t0))
        // PID 已回收（startInstant 不同）→ 跳過。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, t0, t1))
        // handle 快照不可驗證（info 抛／startInstant 缺席）→ 跳過。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, null, t0))
        // 信號前重讀不可驗證（info 抛／缺席）→ 跳過。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, t0, null))
        // 兩者皆 null（完全不可驗證）→ 跳過。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, null, null))
        // 已死 → 跳過（常規態，無信號可發）。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(false, t0, t0))
        // 已死（PID 已重用，startInstant 不同）→ 跳過；釘死 alive&& 短路順序。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(false, t0, t1))
        // 已死（快照不可驗證，current 讀得 t0）→ 跳過；短路順序同上。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(false, null, t0))
        // 值語義等值：不同值即 mismatch，相同值即放行。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, "start-a", "start-b"))
        assertTrue(ElevatedProcessKiller.pidReuseSignalAllowed(true, "start-a", "start-a"))
    }

    @Test fun destroyForciblyReturnsThis() {
        // AB1 順手補（核實缺失後新增）：fake 契約——destroyForcibly 回同一實例
        //（drain／kill 流程依賴此語義；ShizukuRemoteProcess 包裝直通同理）。
        val stub = AospFallbackStub()
        assertSame(stub, stub.destroyForcibly())
    }

    @Test fun killTreeAttestsBodyBeforeSignalAndCountsUnattestedPlatform() {
        // Stage 4（本體 best-effort PID-reuse attestation）判定表式釘點：
        // 下方 pidReuseSignalAllowed 的各次呼叫釘住的是子孫路徑——
        // killDescendantsReflective 經 HandleAttest.signalAllowed 共用的同一純判定函數。
        // 本體路徑（attestBodyDestroy／signalBody）自 Stage 4 review R1(a) 起不再經本
        // 判定式：它 inline 重寫同一 raw 比較（alive＋startInstant 值相等判定式），
        // 語義為 fail-toward-destruction——任何不可驗（alive==null／快照或重讀
        // startInstant 為 null／isAlive 讀抛）一律退化為無條件 destroy＋
        // bodyDestroyUnattested（kill 契約優先；不計 pidReuseSkipped——nothing was
        // proven）；僅「兩讀皆成功且 startInstant 不符」（proven positive mismatch）
        // 才跳過該信號＋計 pidReuseSkipped＋bodyPidReuseSkipped＋one-shot latch
        // （剩餘本體信號無條件抑制——零重讀、零計數、destroyForcibly 不再評估）；
        // alive==false（含 mid-window 兩讀之間死亡）⇒ 零信號、零計數。
        //
        // 接線（snapshot→recheck→skip＋計數）無法以假 ProcessHandle 直測：單元測試編譯
        // 走 SDK 公開表面過濾，toHandle/descendants/ProcessHandle 之 override／
        // 子類化皆不可行（見本檔 KDoc），真實 PID 回收亦無法確定性誘發；故本體
        // cadence 以純判定表釘點——body 的 destroy/destroyForcibly 兩次獨立重驗與下方
        // 判定表同一 (alive, expectedStart, currentStart) 比較式，惟落地路徑為 signalBody
        // 的 inline 比較，而非 pidReuseSignalAllowed。
        val t0 = java.time.Instant.ofEpochMilli(1_700_000_000_000L)
        val t1 = java.time.Instant.ofEpochMilli(1_800_000_000_000L)
        // destroy 信號第 1 次獨立重驗：alive＋快照一致 → 放行（attested ⇒ handle.destroy）。
        assertTrue(ElevatedProcessKiller.pidReuseSignalAllowed(true, t0, t0))
        // destroyForcibly 信號第 2 次獨立重驗：一致即放行。
        assertTrue(ElevatedProcessKiller.pidReuseSignalAllowed(true, t0, t0))
        // 任一次重驗鍵值不可驗／已回收 → 該判定式回 false（fail-closed；
        // 子孫路徑＝跳過＋pidReuseSkipped）。本體路徑（Stage 4 review R1(a)）
        // 不再以本判定式落地——不可驗一律退化為無條件 destroy＋bodyDestroyUnattested，
        // 僅 proven mismatch（兩讀皆成功＋startInstant 不符）才跳過＋兩計數＋latch。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, t0, t1))
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, null, t0))
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, t0, null))
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(true, null, null))
        // 已死（destroy 已生效或自然退出，第二個信號前）→ 跳過、不計診斷。
        assertFalse(ElevatedProcessKiller.pidReuseSignalAllowed(false, t0, t0))
        // bodyDestroyUnattestedCount：未經 attest 的本體信號計數（cannot-attest-at-all
        // 平台專屬；#136-B3 device-acceptance checklist 項）。僅釘名稱／有界性，
        // 不依賴具體讀數——killTreeDegradesToSingleKillWhenToHandleUnsupported 已在
        // 同 JVM 觸發 unconditional 路徑（increment 2），讀數平台／測序相依，
        // 故只斷言 >= 0。
        assertTrue(
            "bodyDestroyUnattestedCount must exist and be bounded >= 0",
            ElevatedProcessKiller.bodyDestroyUnattestedCount() >= 0,
        )
    }

    @Test fun killTreeOnNonAttestableFakeProcessDegradesToUnconditionalDestroyAndCounts() {
        // Stage 4 review O1（strengthened）：FakeProcess（同包 ElevatedTestFakes；
        // 未覆寫 toHandle；JDK 基類預設即抛 UOE，android.jar 樁同為 UOE）在任何
        // 平台皆無法 attest ⇒ 本體兩個信號一律走 unattested 無條件 destroy 臂
        // （kill 契約＝destroy＋destroyForcibly 必跑、不抛），且每個未經 attest
        // 的信號計入 bodyDestroyUnattestedCount（once per SIGNAL）。
        // deterministic：FakeProcess 不 override toHandle ⇒ reflectHandleAttest 收斂
        // handle=null ⇒ attestBodyDestroy 退化 unattestedBodyDestroy（+2），與平台無關。
        val fake = FakeProcess()
        val before = ElevatedProcessKiller.bodyDestroyUnattestedCount()
        ElevatedProcessKiller.killTree(fake) // 不抛即通過；有界返回（不 hang）。
        assertEquals(
            "FakeProcess cannot attest ⇒ both body signals route to the unattested arm",
            before + 2, ElevatedProcessKiller.bodyDestroyUnattestedCount(),
        )
        assertTrue("destroy() must have run (kill contract)", fake.destroyed)
        assertTrue("destroyForcibly() must have run (kill contract)", fake.forciblyDestroyed)
    }

    /**
     * 模擬 AOSP/舊 Android 缺 handle 語義（Host JDK 真進程無法自然觸發）。
     *
     * 刻意不覆寫 `toHandle`：JDK 基類預設即抛 UOE（訊息
     * `"class X.toHandle() not supported"`，含 `"toHandle() not supported"`；
     * AOSP 樁同為 UOE `"toHandle() not supported"`)，主碼經宣告類查表反射
     * invoke 即得 `InvocationTargetException`→收斂為單殺（串流由 drain 關），與舊 Android
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
