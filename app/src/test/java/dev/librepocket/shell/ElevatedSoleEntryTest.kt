package dev.librepocket.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * executeWithFrozen sole-entry pin（產品源集：main＋foss＋github＋play）。
 *
 * 本測試為 **best-effort static tripwire**，非完整邊界——`internal` 可見性（單一
 * module 內同樣可被引用）＋ 本 pin 構成雙重防線；誤用會繞過 gates/re-verify/audit
 * 語義。已知無法覆蓋的誤用形（接受殘餘、須 code review 把關）：
 * - 反射／註解處理器生成的程式碼（`build/` 目錄不掃描）；fresh clone 不會出現；
 * - 同一 `package dev.librepocket.router` 內裸 `execute(`（無 qualifier）：
 *   Wiring 本身即此 package——現況無此形誤用，新檔須 code review 把關；
 * - （已封閉）object 被 `import …ElevatedDispatch` 後 `with(ElevatedDispatch) { execute(…) }`：
 *   該 import 現已零容忍攔截；
 * - 產物 `.kt`／`.java` 之外的生成來源（如 BuildConfig、Room 生成）不含 dispatcher 呼叫。
 * pin 目標是讓「回歸人人可見的誤用」在 CI 直接 fail，而非證明不可能誤用。
 * - [soleEntryForExecuteWithFrozen]：產品源集 token 掃描（main 必備；
 *   foss／play／github 存在即掃、缺失即略過——play 源集目前不存在，僅
 *   manifest／res）。掃描對象為註解／字串剝離後的整段文字（非逐行）：
 *   block 註解（支援巢套、跨行）與 line 註解、字串字面量（`"…"`、raw
 *   `"""…"""`、字元 `'…'`）整段剝離；`executeWithFrozen\s*\(`
 *   whitespace/newline 容忍、token 前一字元非識別字組成
 *   （`.executeWithFrozen(`／裸 `executeWithFrozen(` 皆涵蓋）；
 *   callable-reference（`::executeWithFrozen`，token 結尾邊界非識別字組成）亦涵蓋；
 *   命中僅允許 `dev/librepocket/router/ElevatedWiring.kt`；
 * - [legacyElevatedDispatchExecuteZeroAllowance]：零容忍掃描
 *   `ElevatedDispatch.execute(` ／ `ElevatedDispatch::execute` ／
 *   member／star／object import（`import (static )?…ElevatedDispatch.execute[as …]`、
 *   `import (static )?…ElevatedDispatch.*`、`import (static )?…ElevatedDispatch`）
 *   （whitespace 容忍、識別字邊界；樣板 `${…}` 本體視為 code；
 *   `.java` 來源亦掃（Java 無樣板／反引號；涵蓋 `ElevatedDispatch.INSTANCE.execute(`
 *   互通形）——token 正則同樣適用）
 *   ——任何產品源集出現任一形命中即 fail（宣明檔除外）；
 *   ElevatedWiring.kt 僅走 executeWithFrozen，現今零命中為預期；此 pin
 *   釘死 legacy inline 閘已關閉；
 * - 兩掃描對象為註解／字串剝離後的整段文字（`.kt`＋`.java`）；
 * - [unknownFlavorSourcesWithKotlinFailPin]：`app/src 下各 flavor 的 java 目錄` 出現不在已知
 *   清單（main／foss／play／github；測試／benchmark 源集另計）內的 flavor
 *   且含 `.kt`／`.java` → fail（防未來 flavor 靜默逃逸本 pin）；
 * - 兩掃描皆跳過宣明檔 dev/librepocket/router/ElevatedDispatch.kt；
 * - [elevatedDispatchKdocSoleEntryDeclared]：`ElevatedDispatch` 的 KDoc 須含
 *   sole-entry 聲明字樣（「ElevatedWiring」與「PRECONDITION」）。
 *   KDoc 宣明 pin 不變，仍僅掃 main 源集宣明檔。
 *
 * 註：`${…}` 樣板本體視為 code 重新剝離（其內字串／註解再剝）；`$name` 純名稱插值
 * 視為字串內容。畸形樣板（未配對 `}`）視為延伸至字串尾端（寧可多剝不可漏報）。
 */
class ElevatedSoleEntryTest {

    // ---- 產品源集定位（main 必備；flavor 缺失即略過） ----

    private data class ProductRoot(val name: String, val dir: File)

    /** 多路徑相容候選（module cwd／repo root／CI 差異）。 */
    private fun productRootCandidates(name: String): List<File> = listOf(
        File("app/src/$name/java"),
        File("src/$name/java"),
        File("../app/src/$name/java"),
    )

    /**
     * 定位全部產品源集。main 必備（找不到即 fail，不靜默通過）；
     * foss／play／github 缺失即略過（play 源集目前不存在）。
     */
    private fun locateProductRoots(): List<ProductRoot> {
        val mainCandidates = productRootCandidates("main")
        val main = mainCandidates.firstOrNull { it.isDirectory }
        assertTrue("main 源集未定位，sole-entry pin 失效：$mainCandidates", main != null)
        val roots = mutableListOf(ProductRoot("main", main!!))
        for (flavor in listOf("foss", "play", "github")) {
            val root = productRootCandidates(flavor).firstOrNull { it.isDirectory }
            if (root != null) roots.add(ProductRoot(flavor, root))
        }
        return roots
    }

    /** 宣明檔路徑（相對源集根，`/` 分隔）；兩掃描皆跳過。 */
    private fun isDispatchDeclaration(relativeSlashPath: String): Boolean =
        relativeSlashPath.endsWith("dev/librepocket/router/ElevatedDispatch.kt")

    /** 掃描產品源集中全部 `.kt`（跳過宣明檔）；回 display 路徑（`name:relative`）配對。 */
    private fun productSourceFiles(roots: List<ProductRoot>): List<Pair<String, File>> {
        val files = mutableListOf<Pair<String, File>>()
        for (root in roots) {
            root.dir.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.forEach { f ->
                val rel = f.relativeTo(root.dir).path.replace('\\', '/')
                if (isDispatchDeclaration(rel)) return@forEach
                files.add("${root.name}:$rel" to f)
            }
        }
        return files
    }

    // ---- sole-entry：產品源集僅 ElevatedWiring 得呼叫 executeWithFrozen ----

    @Test fun soleEntryForExecuteWithFrozen() {
        val roots = locateProductRoots()
        val hits = mutableListOf<String>()
        val files = productSourceFiles(roots)
        for ((display, f) in files) {
            if (codeContainsExecuteWithFrozen(stripCommentsAndStrings(f.readText(), isJava = f.name.endsWith(".java")))) {
                hits.add(display)
            }
        }
        // 防護空轉：scanner 至少須掃到檔案且命中預期的唯一合法呼叫方，否則 pin 形同虛設。
        assertTrue("sole-entry pin 掃描零檔案（pin 失效）", files.isNotEmpty())
        assertTrue("產品源集應命中 main:dev/librepocket/router/ElevatedWiring.kt（唯一合法呼叫方）：$hits",
            hits.contains("main:dev/librepocket/router/ElevatedWiring.kt"))
        val unexpected = hits.filter { it.substringAfter(':') != "dev/librepocket/router/ElevatedWiring.kt" }
        assertTrue("產品源集非 ElevatedWiring 呼叫 executeWithFrozen（繞 gates/re-verify/audit）：$unexpected",
            unexpected.isEmpty())
    }

    // ---- 零容忍：產品源集不得出現 legacy ElevatedDispatch.execute 調用 ----

    @Test fun legacyElevatedDispatchExecuteZeroAllowance() {
        val roots = locateProductRoots()
        val hits = mutableListOf<String>()
        val files = productSourceFiles(roots)
        for ((display, f) in files) {
            if (codeContainsLegacyElevatedDispatchExecute(stripCommentsAndStrings(f.readText(), isJava = f.name.endsWith(".java")))) {
                hits.add(display)
            }
        }
        // 防護空轉：至少須掃到檔案，否則 pin 形同虛設。
        assertTrue("sole-entry pin 掃描零檔案（pin 失效）", files.isNotEmpty())
        assertTrue(
            "產品源集出現 legacy ElevatedDispatch.execute 調用（一律零容忍；ElevatedWiring 僅走 executeWithFrozen）：$hits",
            hits.isEmpty(),
        )
    }

    // ---- scanner：whole-text 註解／字串剝離 ----

    /**
     * 註解／字串剝離（whole-text，跨行安全）：狀態機走訪整段文字，
     * 註解（block `/* … */`——支援巢套、跨行；line `// … 行尾`）與
     * 字串字面量（`"…"`——`\` escape；raw `"""…"""`——跨行、無 escape；
     * 字元 `'…'`——`\` escape）整段以單一空白替換，僅回傳程式碼區文字。
     *
     * 樣板（`"${…}"`）內的本體是**可執行程式碼**而非字串內容：掃描
     * 字串時對 `${…}` 配對花括號，將本體遞迴剝離（其內註解／字串再剝）後
     * 輸出，token 正則即可命中樣板內的呼叫。`findTemplateBodyEnd` 對巢套
     * 字串／字元／註解做局部不透明處理；樣板內樣板可遞迴處理。
     *
     * 剝離以空白保留原 token 邊界：`foo`＋剝離區＋`executeWithFrozen(`
     * 仍為 `foo executeWithFrozen(`，邊界判斷不被遮蔽。
     *
     * 反引號識別字（`` `executeWithFrozen`( ``）內容原樣輸出（不含反引號）。
     */
    private fun stripCommentsAndStrings(text: String, isJava: Boolean = false): String {
        val out = StringBuilder(text.length)
        var i = 0
        var blockDepth = 0
        while (i < text.length) {
            if (blockDepth > 0) {
                if (text.startsWith("/*", i)) { blockDepth++; i += 2; continue }
                if (text.startsWith("*/", i)) {
                    blockDepth--
                    if (blockDepth == 0) out.append(' ')
                    i += 2
                    continue
                }
                i++
                continue
            }
            val c = text[i]
            when {
                // line comment：跳過至換行為止（換行保留，由 else 分支輸出——token 邊界不被遮蔽）。
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    i += 2
                    while (i < text.length && text[i] != '\n') i++
                }
                // block comment：巢套支援；跨行。
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    blockDepth = 1; i += 2
                }
                // raw／triple-quoted 字串：`${…}` 樣板本體遞迴輸出（可執行程式碼）；其餘視為字串內容、以空白替換（跨行）。
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    i = consumeString(text, i, isRaw = true, isJava = isJava, out = out)
                }
                // 字串字面量：`\` escape 跳過；`${…}` 樣板本體遞迴輸出（可執行程式碼）；未閉合以換行終止（防呆）。
                c == '"' -> {
                    i = consumeString(text, i, isRaw = false, isJava = isJava, out = out)
                }
                // 字元字面量：`\` escape 跳過；未閉合以換行終止（防呆）。
                c == '\'' -> {
                    i++
                    while (i < text.length) {
                        val sc = text[i]
                        if (sc == '\\') { i += 2; continue }
                        if (sc == '\'') { i++; break }
                        if (sc == '\n') break
                        i++
                    }
                    out.append(' ')
                }
                // 反引號識別字（`executeWithFrozen`( 等）：反引號不輸出、內容原樣輸出。
                c == '`' -> {
                    i++
                    while (i < text.length && text[i] != '`' && text[i] != '\n') {
                        out.append(text[i]); i++
                    }
                    if (i < text.length && text[i] == '`') i++
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        if (blockDepth > 0) out.append(' ') // 未閉合 block comment：尾端視為剝離
        return out.toString()
    }

    /**
     * 消費一個字串字面量（[i] 指向開引號）：非樣板段以單一空白佔位；
     * `${…}` 樣板本體遞迴 [stripCommentsAndStrings]（本體是 code）後輸出——
     * 本體內含的巢套字串再次進入本函數，樣板內嵌字串可正確配對。
     * [isJava] 為 true（`.java` 來源）時 `$` 一律視為字串內容（Java 無樣板）；
     * [isRaw]=false 時 \n 提前終止（防呆：未閉合字串）；raw 無 escape、配對 `"""`；
     * 一般字串 \` escape（含 `\$`——escape 吃掉 `$` 後不可能誤入樣板）。
     * 回傳關閉引號之後的索引（raw 未閉合 → 文字尾端；一般字串未閉合 → `\n` 所在索引，\n 由外層迴圈原樣輸出作為 token 邊界；EOF 則文字尾端）。
     */
    private fun consumeString(text: String, i: Int, isRaw: Boolean, isJava: Boolean, out: StringBuilder): Int {
        out.append(' ')
        var j = i + if (isRaw) 3 else 1
        while (j < text.length) {
            val c = text[j]
            if (!isRaw && c == '\\') { j += 2; continue }
            if (isRaw) {
                if (text.startsWith("\"\"\"", j)) { out.append(' '); return j + 3 }
            } else {
                if (c == '"') { out.append(' '); return j + 1 }
                if (c == '\n') { out.append(' '); return j } // 防呆：未閉合
            }
            if (!isJava && c == '$' && j + 1 < text.length && text[j + 1] == '{') {
                val bodyEnd = findTemplateBodyEnd(text, j + 2)
                out.append(stripCommentsAndStrings(text.substring(j + 2, minOf(bodyEnd, text.length)), isJava = isJava))
                j = minOf(bodyEnd + 1, text.length)
                continue
            }
            j++
        }
        out.append(' ')
        return text.length
    }

    /**
     * 從 `${` 之後（[from]）找配對 `}`：巢套 `{` 計數；巢套字串（`"""`／`"`／字元，
     * 含 `\` escape）與註解（`//…`／`/*…*/`）視為局部不透明（其內 `}` 不計）。
     * 未找到配對（畸形樣板）→ 視為延伸至尾端（寧可多剝不可少報）。
     */
    private fun findTemplateBodyEnd(text: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val e = text.indexOf("\"\"\"", i + 3)
                    i = if (e < 0) text.length else e + 3
                }
                c == '"' -> {
                    i++
                    while (i < text.length) {
                        val sc = text[i]
                        if (sc == '\\') { i += 2; continue }
                        if (sc == '"') { i++; break }
                        i++
                    }
                }
                c == '\'' -> {
                    i++
                    while (i < text.length) {
                        val sc = text[i]
                        if (sc == '\\') { i += 2; continue }
                        if (sc == '\'') { i++; break }
                        i++
                    }
                }
                c == '/' && text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i + 2)
                    i = if (nl < 0) text.length else nl
                }
                // 註：block comment 以 indexOf("*/") 匹配（不支援巢套——寧可多剝不可漏報，僅影響 >0 方向，不影響 token 偵測）。
                c == '/' && text.startsWith("/*", i) -> {
                    val e = text.indexOf("*/", i + 2)
                    i = if (e < 0) text.length else e + 2
                }
                c == '{' -> { depth++; i++ }
                c == '}' -> { depth--; if (depth == 0) return i; i++ }
                else -> i++
            }
        }
        return text.length
    }

    // ---- token 樣式（正則＋識別字邊界） ----

    /**
     * `executeWithFrozen\s*\(`（whitespace/newline 容忍）：token 前一字元非
     * 識別字組成（lookbehind），`.executeWithFrozen(`／裸 `executeWithFrozen(`
     * 皆命中；`fooexecuteWithFrozen(` 不誤計。
     */
    private val executeWithFrozenCall = Regex("(?<![A-Za-z0-9_$])executeWithFrozen\\s*\\(")

    /**
     * `::executeWithFrozen` callable reference（無呼叫括號）：token 結尾後續字元
     * 非識別字組成（`::executeWithFrozenX` 不命中；`::executeWithFrozen` 命中）。
     * `::` 前綴的接收者識別字（`Foo::executeWithFrozen`）合法，故 token 起始
     * 不做前界檢查（`':'` 前為識別字屬正常限定形）。
     */
    private val callableReferenceToken = Regex("::\\s*executeWithFrozen(?![A-Za-z0-9_$])")

    /**
     * 零容忍 legacy 入口：`ElevatedDispatch.execute(`（`.` 兩側 whitespace 容忍）
     * 或 `ElevatedDispatch::execute`（callable reference；結尾邊界非識別字組成）。
     * `ElevatedDispatch.executeWithFrozen(` 不命中（`execute` 後非
     * `\s*\(`／結尾邊界）。
     */
    private val legacyDispatchExecuteCall =
        Regex("(?<![A-Za-z0-9_$])ElevatedDispatch\\s*\\.\\s*(INSTANCE\\s*\\.\\s*)?\\s*execute\\s*\\(")
    private val legacyDispatchExecuteRef =
        Regex("(?<![A-Za-z0-9_$])ElevatedDispatch\\s*(\\.\\s*INSTANCE)?\\s*::\\s*execute(?![A-Za-z0-9_$])")

    /** member import（含 alias 與 Java `import static`）——零容忍。 */
    private val importedDispatchMemberExecute =
        Regex("import\\s+(static\\s+)?dev\\.librepocket\\.router\\.ElevatedDispatch\\s*\\.\\s*execute(?![A-Za-z0-9_$])")

    /** star import（`import (static )?…ElevatedDispatch.*`）——零容忍。 */
    private val importedDispatchStar =
        Regex("import\\s+(static\\s+)?dev\\.librepocket\\.router\\.ElevatedDispatch\\s*\\.\\s*\\*")

    /** object 直接 import（無 member 後綴）——零容忍（可達成 `with(ElevatedDispatch) { execute(…) }`；product 現無此 import，直接封死）。 */
    private val importedDispatchObject =
        Regex("import\\s+(static\\s+)?dev\\.librepocket\\.router\\.ElevatedDispatch(?![A-Za-z0-9_$.])")

    private fun codeContainsExecuteWithFrozen(code: String): Boolean =
        executeWithFrozenCall.containsMatchIn(code) || callableReferenceToken.containsMatchIn(code)

    private fun codeContainsLegacyElevatedDispatchExecute(code: String): Boolean =
        legacyDispatchExecuteCall.containsMatchIn(code) ||
            legacyDispatchExecuteRef.containsMatchIn(code) ||
            importedDispatchMemberExecute.containsMatchIn(code) ||
            importedDispatchStar.containsMatchIn(code) ||
            importedDispatchObject.containsMatchIn(code)

    // ---- 未來 flavor 防護：未知 src/*/java 含 Kotlin/Java 來源即 fail ----

    @Test fun unknownFlavorSourcesWithKotlinFailPin() {
        val baseCandidates = listOf(
            File("app/src"),
            File("src"),
            File("../app/src"),
        )
        val base = baseCandidates.firstOrNull { it.isDirectory }
        assertTrue("app/src 未定位，unknown-flavor pin 失效：$baseCandidates", base != null)
        val known = setOf(
            "main", "androidTest", "debug", "foss", "github", "play",
            "test", "testFoss", "testGithub", "testPlay",
            "benchmark", "androidBenchmark", "androidBenchmarkTest",
        )
        val offenders = base!!.listFiles()
            ?.filter { it.isDirectory && it.name !in known }
            ?.filter { dir ->
                val javaDir = File(dir, "java")
                javaDir.isDirectory &&
                    javaDir.walkTopDown().any { f ->
                        f.isFile && (f.name.endsWith(".kt") || f.name.endsWith(".java"))
                    }
            }
            ?.map { it.name }
            ?: emptyList()
        assertTrue(
            "未知 flavor 源集含 Kotlin/Java 來源、未入 sole-entry pin 掃描清單：$offenders（請更新 locateProductRoots 與本測試的 known 清單）",
            offenders.isEmpty(),
        )
    }

    // ---- sole-entry 聲明：ElevatedDispatch KDoc 須保留聲明字樣 ----

    @Test fun elevatedDispatchKdocSoleEntryDeclared() {
        // 定位 main 源集（多路徑相容：module cwd／repo root／CI 差異；找不到即 fail，不靜默通過）。
        val candidates = productRootCandidates("main")
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("main 源集未定位，sole-entry pin 失效：$candidates", root != null)
        val locatedRoot = root!!
        val file = File(locatedRoot, "dev/librepocket/router/ElevatedDispatch.kt")
        assertEquals("ElevatedDispatch.kt 未找到（pin 失效）", true, file.isFile)
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
