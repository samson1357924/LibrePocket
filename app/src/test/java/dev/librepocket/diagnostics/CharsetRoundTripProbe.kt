package dev.librepocket.diagnostics

import dev.librepocket.files.FileScope
import java.io.File
import java.nio.charset.Charset
import kotlin.system.exitProcess

/**
 * Issue #177 控制案例的 sub-JVM probe（不是 JUnit test；由 [CharsetLocaleControlTest] 啟動）。
 *
 * 刻意使用 **production** [FileScope.sanitizeRelative] 與 [File] 的 path 編碼路徑：
 * host native locale（`sun.jnu.encoding`）為 ASCII/POSIX 時，`FileOutputStream`
 * 會把中文檔名寫成 `??.txt`（`dir.list()` 看不到原名 ⇒ `listed=false`），而
 * production `FileScope` 內的 `Paths.get` 拋 `InvalidPathException` 後被 catch、
 * fail-closed 回 `null`；UTF-8 locale 則寫入→列出→讀取→比對全部成功。
 * 注意 `File.writeText/readText` 走串流、本身不拋 `InvalidPathException`，
 * 故控制案例只 pin 真正可觀測的 `listed=false` + `sanitizeRelative=null`。
 * 只使用合成檔名與暫存目錄（AGENTS §7）。
 *
 * 輸出為空白分隔的 `KEY=VALUE`（末段 `RESULT=...`），供父測試做字串 pin。
 * exit 0 = RESULT=OK；exit 3 = RESULT=ENV_FAILURE。
 */
object CharsetRoundTripProbe {
    const val EXIT_OK = 0
    const val EXIT_ENV_FAILURE = 3

    private const val NAME = "中文.txt"
    private const val MARKER = "issue-177-probe"

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val fields = mutableListOf(
            "file.encoding=${System.getProperty("file.encoding")}",
            "sun.jnu.encoding=${System.getProperty("sun.jnu.encoding")}",
            "defaultCharset=${Charset.defaultCharset()}",
        )
        var result = EXIT_OK
        try {
            val file = File(dir, NAME)
            file.writeText(MARKER, Charsets.UTF_8)
            val listed = dir.list()?.toList().orEmpty()
            val listedOk = NAME in listed
            val contentOk = file.readText(Charsets.UTF_8) == MARKER
            val sanitized = FileScope.sanitizeRelative(NAME)
            fields += "listed=$listedOk"
            fields += "content=$contentOk"
            fields += "sanitizeRelative=$sanitized"
            if (!listedOk || !contentOk || sanitized != NAME) result = EXIT_ENV_FAILURE
        } catch (t: Throwable) {
            // 非預期例外（POSIX locale 本身走不到這裡：串流不拋 InvalidPathException）。
            result = EXIT_ENV_FAILURE
            fields += "exception=${t.javaClass.simpleName}: ${t.message ?: ""}"
            // 同一份 production 邏輯在壞環境下必須 fail-closed（Issue #177 的觀測表徵）。
            fields += "sanitizeRelative=${FileScope.sanitizeRelative(NAME)}"
        }
        fields += if (result == EXIT_OK) "RESULT=OK" else "RESULT=ENV_FAILURE"
        println(fields.joinToString(" "))
        System.out.flush()
        exitProcess(result)
    }
}
