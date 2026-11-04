package dev.librepocket.privilege.github

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 提權 runner JVM 單測共用假件（S3，PocketGuard SUGGESTION 抽檔，行為零變更）：
 * 原散落於 `ElevatedRunnerB2Test`／`ElevatedRunnerGrantTest`／
 * `ScreenshotCapacityTest`／`ElevatedRunnerScreenshotCapacityTest`／
 * `ElevatedRunnerTest` 的私嵌 `FakeProcess`／`FakeRemote`／`SpawnCounter`
 * 收斂至此（同包頂層 internal，免 import 即用）。
 *
 * - [FakeProcess] 取各檔並集語義（參數化 hang/exit/stdout＋stderr）：
 *   非 hang 即時完成（`waitFor(timeout)` 直回 true）；hang 時切片睡眠，
 *   被 killer `destroy()` 即解除（`destroyed` 為 true 則回 true），
 *   覆蓋 B2 超時/cancel 斷言；`destroyed`／`forciblyDestroyed` 供殺驗證，
 *   不使用的測試忽略即可；
 * - [FakeRemote] 同名方法但非 `Process` 子類：直 cast 必 CCE，
 *   供 [ShizukuRemoteProcess] 包裝斷言（源自 B2Test）；
 * - [SpawnCounter] 計數＋末次 argv＋可配 stdout：`spawn::spawn` 直接注入
 *   runner `spawn` 參數，覆蓋 Grant 計數與 Elevated 传送層 argv 斷言。
 */
internal class FakeProcess(
    private val stdout: ByteArray = "ok".toByteArray(),
    private val stderr: ByteArray = ByteArray(0),
    private val exit: Int = 0,
    private val hang: Boolean = false,
) : Process() {
    var destroyed = false
    var forciblyDestroyed = false
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(stdout)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(stderr)
    override fun waitFor(): Int = exit
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (destroyed) return true
        if (hang) {
            try {
                Thread.sleep(unit.toMillis(timeout))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return destroyed
        }
        return true
    }

    override fun exitValue(): Int = exit
    override fun destroy() {
        destroyed = true
    }

    override fun destroyForcibly(): Process {
        forciblyDestroyed = true
        destroyed = true
        return this
    }
}

/** 非 Process 的遠端假物件：同名方法，直 cast 必 CCE（源自 B2Test）。 */
internal class FakeRemote(
    private val stdout: ByteArray,
    private val stderr: ByteArray = ByteArray(0),
    private val exit: Int = 0,
) {
    var destroyed = false
    fun getInputStream(): InputStream = ByteArrayInputStream(stdout)
    fun getErrorStream(): InputStream = ByteArrayInputStream(stderr)
    fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    fun waitFor(): Int = exit
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
    fun exitValue(): Int = exit
    fun destroy() {
        destroyed = true
    }
}

/** 計數 spawn 替身：`spawn::spawn` 注入 runner，記 calls＋末次 argv。 */
internal class SpawnCounter(
    private val stdout: ByteArray = "ok".toByteArray(),
) {
    var calls = 0
    var lastArgv: List<String> = emptyList()
    fun spawn(argv: List<String>): Process {
        calls++
        lastArgv = argv
        return FakeProcess(stdout)
    }
}
