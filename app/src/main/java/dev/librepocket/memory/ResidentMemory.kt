package dev.librepocket.memory

import dev.librepocket.redact.Redactor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * D02 常驻三件套（文件侧，与 Room 侧 [MemoryStore] 互补）：
 *
 * - `MEMORY.md`：常驻长期记忆，预算 < 2000 tokens（按 [approxTokens] 估算，
 *   上限 [MAX_RESIDENT_CHARS] 字符，写入截断、读取兜底截断）；
 * - `daily/YYYY-MM-DD.md`：每日流水，只读当天+昨天进 prompt；
 * - `scratchpad.md`：开放任务（`- [ ]` 为未完成），供启动 prompt 注入。
 *
 * 所有写入先过 [MemoryGate.WRITE] 再经 [Redactor] 脱敏；[buildSystemPrompt]
 * 过 READ。文件 IO 统一约束到 `Dispatchers.IO`。
 */
class ResidentMemory(
    private val root: File,
    private val gate: MemoryGate = AllowMemoryGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val residentFile: File get() = File(root, RESIDENT_NAME)
    private val scratchFile: File get() = File(root, SCRATCH_NAME)
    private fun dailyFile(date: String): File {
        require(DATE_PATTERN.matches(date)) { "bad date (want YYYY-MM-DD): $date" }
        return File(File(root, DAILY_DIR), "$date.md")
    }

    // ---- MEMORY.md ----

    suspend fun saveResident(text: String) {
        gate.require(MemoryAction.WRITE)
        require(text.isNotBlank()) { "resident text must not be blank" }
        val redacted = Redactor.redact(text).text
        val capped = if (redacted.length > MAX_RESIDENT_CHARS) redacted.take(MAX_RESIDENT_CHARS) else redacted
        withContext(Dispatchers.IO) {
            root.mkdirs()
            residentFile.writeText(capped, Charsets.UTF_8)
        }
    }

    suspend fun loadResident(): String {
        gate.require(MemoryAction.READ)
        return withContext(Dispatchers.IO) {
            val raw = if (residentFile.isFile) residentFile.readText(Charsets.UTF_8) else ""
            if (raw.length > MAX_RESIDENT_CHARS) raw.take(MAX_RESIDENT_CHARS) else raw
        }
    }

    // ---- daily log ----

    suspend fun appendDaily(date: String, text: String) {
        gate.require(MemoryAction.WRITE)
        require(text.isNotBlank()) { "daily text must not be blank" }
        val redacted = Redactor.redact(text).text
        withContext(Dispatchers.IO) {
            val f = dailyFile(date)
            f.parentFile?.mkdirs()
            f.appendText("- ${clock()} $redacted\n", Charsets.UTF_8)
        }
    }

    suspend fun readDaily(date: String): String {
        gate.require(MemoryAction.READ)
        return withContext(Dispatchers.IO) {
            val f = dailyFile(date)
            if (!f.isFile) "" else f.readText(Charsets.UTF_8).take(DAILY_READ_CHARS)
        }
    }

    // ---- scratchpad（open tasks） ----

    suspend fun addTask(title: String) {
        gate.require(MemoryAction.WRITE)
        require(title.isNotBlank()) { "task title must not be blank" }
        val redacted = Redactor.redact(title).text.replace("\n", " ").take(TASK_TITLE_CHARS)
        withContext(Dispatchers.IO) {
            root.mkdirs()
            scratchFile.appendText("- [ ] $redacted\n", Charsets.UTF_8)
        }
    }

    /** 将首个包含 [matcher] 的未完成任务标为完成；无匹配返回 false。 */
    suspend fun completeTask(matcher: String): Boolean {
        gate.require(MemoryAction.WRITE)
        require(matcher.isNotBlank()) { "matcher must not be blank" }
        return withContext(Dispatchers.IO) {
            if (!scratchFile.isFile) return@withContext false
            val lines = scratchFile.readLines(Charsets.UTF_8).toMutableList()
            val idx = lines.indexOfFirst { it.startsWith(OPEN_PREFIX) && it.contains(matcher) }
            if (idx < 0) return@withContext false
            lines[idx] = DONE_PREFIX + lines[idx].removePrefix(OPEN_PREFIX)
            scratchFile.writeText(lines.joinToString("\n").let { if (it.isEmpty()) it else it + "\n" }, Charsets.UTF_8)
            true
        }
    }

    suspend fun listOpenTasks(): List<String> {
        gate.require(MemoryAction.READ)
        return withContext(Dispatchers.IO) {
            if (!scratchFile.isFile) return@withContext emptyList()
            scratchFile.readLines(Charsets.UTF_8)
                .filter { it.startsWith(OPEN_PREFIX) }
                .map { it.removePrefix(OPEN_PREFIX).trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_TASKS_IN_PROMPT)
        }
    }

    // ---- 启动注入 ----

    /**
     * 组装启动 system prompt：常驻 + 当天/昨天 daily + 开放任务 + 可选工作记忆。
     * 各段独立截断，保证总量有界；返回文本已是脱敏后内容（文件与工作记忆
     * 入库/入盘时已脱敏，此处不再二次改写，只做长度裁剪）。
     */
    suspend fun buildSystemPrompt(
        today: String,
        yesterday: String,
        working: WorkingMemory? = null,
        workingTurns: Int = PROMPT_WORKING_TURNS,
    ): String {
        gate.require(MemoryAction.READ)
        val resident = loadResident()
        val todayLog = readDaily(today)
        val yesterdayLog = readDaily(yesterday)
        val tasks = listOpenTasks()
        val workingLines = working?.recent(workingTurns)
            ?.joinToString("\n") { "${it.role}: ${it.text}" }
            ?.take(PROMPT_WORKING_CHARS).orEmpty()
        return buildString {
            appendLine("# 常驻记忆")
            appendLine(if (resident.isBlank()) "(空)" else resident)
            appendLine()
            appendLine("# 今日 ($today)")
            appendLine(if (todayLog.isBlank()) "(空)" else todayLog)
            appendLine()
            appendLine("# 昨日 ($yesterday)")
            appendLine(if (yesterdayLog.isBlank()) "(空)" else yesterdayLog)
            appendLine()
            appendLine("# 待办")
            if (tasks.isEmpty()) appendLine("(空)") else tasks.forEach { appendLine("- [ ] $it") }
            if (workingLines.isNotBlank()) {
                appendLine()
                appendLine("# 本会话近况")
                appendLine(workingLines)
            }
        }
    }

    companion object {
        const val RESIDENT_NAME = "MEMORY.md"
        const val SCRATCH_NAME = "scratchpad.md"
        const val DAILY_DIR = "daily"

        /** <2k tokens 预算：按 chars/3 估算 token，6000 字符≈2000 tokens。 */
        const val MAX_TOKENS = 2000
        const val MAX_RESIDENT_CHARS = 6000
        const val DAILY_READ_CHARS = 4000
        const val TASK_TITLE_CHARS = 300
        const val MAX_TASKS_IN_PROMPT = 20
        const val PROMPT_WORKING_TURNS = 6
        const val PROMPT_WORKING_CHARS = 3000

        const val OPEN_PREFIX = "- [ ] "
        const val DONE_PREFIX = "- [x] "

        private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

        /** 保守 token 估算（CJK 按字计，英文按 ~3 字符计）。 */
        fun approxTokens(text: String): Int = (text.length + 2) / 3
    }
}
