package dev.librepocket.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Issue #177 triage 控制案例：用 subprocess 隔離 host charset，同一份 production
 * `dev.librepocket.files.FileScope`／[File] 邏輯在 UTF-8 與 POSIX locale 下應給出不同結果。
 *
 * 時序類失敗（ChatMarkdownParserTest / NetworkPolicyTest /
 * B2Phase1DispatcherGrantTest）由 #176 處理，本檔不重複（Refs #176）。
 * 注意 #176 的修正尚未合併進 main（`ff811434` 非 main 祖先），故 close #177 後
 * 時序三項在 main 仍存在，須由 #176 持續追蹤；#176 也動到 production parser/view，
 * 不限於測試。
 *
 * POSIX 案例若因 host 無法產生非 UTF-8 的 `sun.jnu.encoding` 而 skip，屬證據不足，
 * 不得據此 close #177（見 docs/TESTING.md）。每個 subprocess 有 30s watchdog；
 * 只使用暫存目錄與合成檔名。
 */
class CharsetLocaleControlTest {

    private val javaExecutable: String
        get() = File(System.getProperty("java.home"), "bin/java").absolutePath

    /** Gradle test worker 的 classpath 即 probe 所需的 production + test classes。 */
    private val classpath: String by lazy {
        val fromProperty = System.getProperty("java.class.path").orEmpty()
        val location = CharsetRoundTripProbe::class.java.protectionDomain?.codeSource?.location
        val fromCodeSource = location?.let { runCatching { File(it.toURI()).absolutePath }.getOrNull() }.orEmpty()
        listOf(fromProperty, fromCodeSource).filter { it.isNotBlank() }.distinct()
            .joinToString(File.pathSeparator)
    }

    private data class Result(val exitCode: Int, val output: String) {
        val jnu: String
            get() = output.substringAfter("sun.jnu.encoding=", "").substringBefore(" ")
    }

    private fun assumeUnix() {
        val os = System.getProperty("os.name").orEmpty()
        assumeTrue("probe 需要 POSIX locale / Unix path 語義", !os.startsWith("Windows"))
    }

    private fun runProbe(locale: String?, fileEncoding: String): Result {
        val temp = Files.createTempDirectory("lp177-charset-probe").toFile()
        try {
            val command = listOf(
                javaExecutable,
                "-Dfile.encoding=$fileEncoding",
                // 與 baseline/手動探針一致；這些 flag 不影響 sun.jnu.encoding。
                "-Duser.language=zh", "-Duser.country=TW",
                "-cp", classpath,
                CharsetRoundTripProbe::class.java.name,
                temp.absolutePath,
            )
            val builder = ProcessBuilder(command)
            if (locale != null) {
                builder.environment()["LC_ALL"] = locale
                builder.environment()["LANG"] = locale
            }
            val log = File(temp, "probe.log")
            builder.redirectErrorStream(true)
            builder.redirectOutput(log)
            val process = builder.start()
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    throw AssertionError("probe 超過 30s 未結束")
                }
                // Use the file so an unexpectedly noisy child cannot block on a pipe.
                return Result(process.exitValue(), log.readText(Charsets.UTF_8).trim())
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw AssertionError("probe 未在強制終止後 5s 內結束")
                    }
                }
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun utf8Locale_roundTrip_succeeds() {
        assumeUnix()
        val r = runProbe(locale = null, fileEncoding = "UTF-8")
        assertTrue("probe 沒有產出 RESULT：$r", r.output.contains("RESULT="))
        assertTrue(
            "CI 必須以 UTF-8 native locale 執行（sun.jnu.encoding=${r.jnu}）；見 docs/TESTING.md #177",
            r.jnu.contains("UTF-8", ignoreCase = true),
        )
        assertEquals("UTF-8 locale 下 sub-JVM 應成功\n$r", CharsetRoundTripProbe.EXIT_OK, r.exitCode)
        assertTrue(r.output.contains("RESULT=OK"))
        assertTrue(r.output.contains("listed=true"))
        assertTrue(r.output.contains("content=true"))
        assertTrue(r.output.contains("sanitizeRelative=中文.txt"))
    }

    @Test fun fileEncodingOverrideAlone_doesNotBreakRoundTrip() {
        assumeUnix()
        // 負向控制：JDK 25 實測 path 編碼由 native locale（sun.jnu.encoding）決定；
        // 只改 -Dfile.encoding 不會讓中文路徑失敗，避免把 #177 誤診為 file.encoding。
        val r = runProbe(locale = null, fileEncoding = "ISO-8859-1")
        assertTrue("負向控制也須是 UTF-8 native locale\n$r", r.jnu.contains("UTF-8", ignoreCase = true))
        assertEquals("只改 file.encoding 不應造成失敗\n$r", CharsetRoundTripProbe.EXIT_OK, r.exitCode)
        assertTrue(r.output.contains("RESULT=OK"))
        assertTrue(r.output.contains("listed=true"))
        assertTrue(r.output.contains("content=true"))
    }

    @Test fun posixLocale_reproducesIssue177_failure() {
        assumeUnix()
        // 先跑完兩輪再判定 skip：避免 assume 在第一輪失敗時丟棄第二輪的證據。
        val probes = listOf("UTF-8", "ISO-8859-1")
            .associateWith { runProbe(locale = "C", fileEncoding = it) }
        // skip 只代表本機不具備重現條件（證據不足），不得據此 close #177。
        assumeTrue(
            "host 無法製造非 UTF-8 的 sun.jnu.encoding=${probes.values.map { it.jnu }}；此環境不能重現 #177",
            probes.values.all { !it.jnu.contains("UTF-8", ignoreCase = true) },
        )
        for ((encoding, r) in probes) {
            assertEquals(
                "POSIX locale (file.encoding=$encoding) 應重現環境失敗\n$r",
                CharsetRoundTripProbe.EXIT_ENV_FAILURE,
                r.exitCode,
            )
            assertTrue(r.output.contains("RESULT=ENV_FAILURE"))
            // File.writeText/readText 走串流不拋 InvalidPathException；可觀測訊號是
            // 中文名被寫成 ??.txt（列出比對失敗）＋ production FileScope fail-closed。
            assertTrue("原名必須無法列出（被置換為 ?）\n$r", r.output.contains("listed=false"))
            assertTrue("production FileScope 必須 fail-closed 回 null\n$r", r.output.contains("sanitizeRelative=null"))
        }
    }
}
