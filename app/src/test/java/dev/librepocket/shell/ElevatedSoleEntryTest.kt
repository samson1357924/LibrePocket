package dev.librepocket.shell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * executeWithFrozen sole-entry pin（main 源集）。
 *
 * pin：executeWithFrozen 僅 [dev.librepocket.router.ElevatedWiring].execute 呼叫；
 * internal 可見性 + 本測試構成雙重防線；誤用會繞過 gates/re-verify/audit 語義。
 * - [soleEntryForExecuteWithFrozen]：main 源集 token 掃描，命中（`.executeWithFrozen(`
 *   與裸 `executeWithFrozen(`）僅允許 `dev/librepocket/router/ElevatedWiring.kt`；
 * - [elevatedDispatchKdocSoleEntryDeclared]：`ElevatedDispatch` 的 KDoc 須含
 *   sole-entry 聲明字樣（「ElevatedWiring」與「PRECONDITION」）。
 */
class ElevatedSoleEntryTest {

    // ---- sole-entry：產品源集僅 ElevatedWiring 得呼叫 executeWithFrozen ----

    @Test fun soleEntryForExecuteWithFrozen() {
        // 定位 main 源集（多路徑相容：module cwd／repo root／CI 差異；找不到即跳過）。
        val candidates = listOf(
            File("app/src/main/java"),
            File("src/main/java"),
            File("../app/src/main/java"),
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: return
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            // 僅掃產品源集（main），跳過宣明檔本身；註解/KDoc 命中不計，
            // 字串字面量內命中亦不計（僅計程式碼區實際調用 `executeWithFrozen(`）。
            if (f.relativeTo(root).path.replace('\\', '/').endsWith("dev/librepocket/router/ElevatedDispatch.kt")) return@forEach
            if (codeContainsExecuteWithFrozen(f.readText())) {
                hits.add(f.relativeTo(root).path.replace('\\', '/'))
            }
        }
        // 防護空轉：scanner 至少須找到預期的唯一合法呼叫方，否則 pin 形同虛設。
        assertTrue("main 源集應命中 dev/librepocket/router/ElevatedWiring.kt（唯一合法呼叫方）：$hits",
            hits.contains("dev/librepocket/router/ElevatedWiring.kt"))
        val unexpected = hits.filter { it != "dev/librepocket/router/ElevatedWiring.kt" }
        assertTrue("main 源集非 ElevatedWiring 呼叫 executeWithFrozen（繞 gates/re-verify/audit）：$unexpected",
            unexpected.isEmpty())
    }

    /**
     * 程式碼區調用探測（token 級，非正則 grep）：逐行掃描並追蹤區塊註解／
     * 行註解／字串（`"`、三引號、`'…'`）狀態，僅程式碼區的
     * `executeWithFrozen(` 才算命中，避免註解與字串字面量誤報。
     *
     * `executeWithFrozen(` 前綴檢查同時涵蓋 `.executeWithFrozen(`（具名接收者）
     * 與裸 `executeWithFrozen(`（無接收者直調）；`fooexecuteWithFrozen(` 這類
     * 識別字後綴不誤計（前一字元為識別字組成即跳過）。
     */
    private fun codeContainsExecuteWithFrozen(text: String): Boolean {
        var inBlock = false
        var inTriple = false
        for (rawLine in text.lines()) {
            var i = 0
            var inStr = false
            val code = StringBuilder()
            while (i < rawLine.length) {
                if (inTriple) {
                    val end = rawLine.indexOf("\"\"\"", i)
                    if (end < 0) break
                    i = end + 3
                    inTriple = false
                    continue
                }
                if (inBlock) {
                    val end = rawLine.indexOf("*/", i)
                    if (end < 0) break
                    i = end + 2
                    inBlock = false
                    continue
                }
                val c = rawLine[i]
                if (inStr) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') inStr = false
                    i++
                    continue
                }
                if (c == '"') {
                    if (rawLine.startsWith("\"\"\"", i)) {
                        inTriple = true
                        i += 3
                    } else {
                        inStr = true
                        i++
                    }
                    continue
                }
                if (c == '\'') {
                    // 字元字面量（如 `'/'`）：跳過至配對 `'`，避免其內 `/` 干擾註解判定。
                    i = if (i + 2 < rawLine.length && rawLine[i + 1] == '\\') {
                        if (i + 3 < rawLine.length) i + 4 else rawLine.length
                    } else {
                        val end = rawLine.indexOf('\'', i + 1)
                        if (end < 0) rawLine.length else end + 1
                    }
                    continue
                }
                if (c == '/' && i + 1 < rawLine.length) {
                    val n = rawLine[i + 1]
                    if (n == '/') break
                    if (n == '*') {
                        inBlock = true
                        i += 2
                        continue
                    }
                }
                code.append(c)
                i++
            }
            if (tokenAtBoundary(code, "executeWithFrozen(")) return true
        }
        return false
    }

    /** `token` 出現於 `code` 且前一字元非識別字組成（允許 `.`、空白、`(` 等）。 */
    private fun tokenAtBoundary(code: CharSequence, token: String): Boolean {
        var i = code.indexOf(token)
        while (i >= 0) {
            val before = if (i == 0) ' ' else code[i - 1]
            if (!(before.isLetterOrDigit() || before == '_' || before == '$')) return true
            i = code.indexOf(token, i + 1)
        }
        return false
    }

    // ---- sole-entry 聲明：ElevatedDispatch KDoc 須保留聲明字樣 ----

    @Test fun elevatedDispatchKdocSoleEntryDeclared() {
        val candidates = listOf(
            File("app/src/main/java"),
            File("src/main/java"),
            File("../app/src/main/java"),
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: return
        val file = File(root, "dev/librepocket/router/ElevatedDispatch.kt")
        assertTrue("ElevatedDispatch.kt not found under ${root.path}", file.isFile)
        val text = file.readText()
        assertTrue(
            "ElevatedDispatch.kt KDoc 須聲明 sole-entry 唯一合法呼叫方 ElevatedWiring",
            text.contains("ElevatedWiring"),
        )
        assertTrue(
            "ElevatedDispatch.kt KDoc 須保留 PRECONDITION 段（executeWithFrozen sole-entry 前提）",
            text.contains("PRECONDITION"),
        )
    }
}
