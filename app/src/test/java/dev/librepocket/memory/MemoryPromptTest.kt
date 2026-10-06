package dev.librepocket.memory

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D02 三层组装测试（纯 JVM）：MEMORY.md 常驻预算（<2k tokens）+ daily
 *（当天/昨天）+ scratchpad（open tasks）+ 工作记忆 N 轮 + 启动 prompt 注入。
 */
class MemoryPromptTest {

    private lateinit var tmpDir: File

    private fun setUp(): ResidentMemory {
        tmpDir = Files.createTempDirectory("memory-prompt-test").toFile()
        return ResidentMemory(File(tmpDir, "memory")) { 1L }
    }

    @After
    fun tearDown() {
        if (this::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    @Test fun resident_budget_under2kTokens(): Unit = runBlocking {
        val mem = setUp()
        mem.saveResident("常识 ".repeat(7000)) // 21000 字符，远超预算
        val loaded = mem.loadResident()
        assertTrue(loaded.length <= ResidentMemory.MAX_RESIDENT_CHARS)
        assertTrue(ResidentMemory.approxTokens(loaded) <= ResidentMemory.MAX_TOKENS)
    }

    @Test fun daily_todayAndYesterdayOnly(): Unit = runBlocking {
        val mem = setUp()
        mem.appendDaily("2026-10-04", "三天前的旧事")
        mem.appendDaily("2026-10-05", "昨天的安排：买凤梨酥")
        mem.appendDaily("2026-10-06", "今天的安排：修记忆模块")

        assertTrue(mem.readDaily("2026-10-06").contains("修记忆模块"))
        assertTrue(mem.readDaily("2026-10-05").contains("买凤梨酥"))
        assertTrue(mem.readDaily("2026-10-03").isEmpty()) // 无文件=空，不抛错

        val prompt = mem.buildSystemPrompt("2026-10-06", "2026-10-05")
        assertTrue(prompt.contains("修记忆模块"))
        assertTrue(prompt.contains("买凤梨酥"))
        assertFalse(prompt.contains("三天前的旧事"))
    }

    @Test fun scratchpad_openTasksLifecycle(): Unit = runBlocking {
        val mem = setUp()
        mem.addTask("给牙医打电话")
        mem.addTask("买牛奶")
        assertEquals(listOf("给牙医打电话", "买牛奶"), mem.listOpenTasks())

        assertTrue(mem.completeTask("牙医"))
        assertEquals(listOf("买牛奶"), mem.listOpenTasks())
        assertFalse(mem.completeTask("不存在的任务"))

        val prompt = mem.buildSystemPrompt("2026-10-06", "2026-10-05")
        assertTrue(prompt.contains("买牛奶"))
        assertFalse(prompt.contains("给牙医打电话"))
    }

    @Test fun working_evictsBeyondMaxTurns(): Unit = runBlocking {
        val mem = setUp()
        val working = WorkingMemory(maxTurns = 3)
        repeat(5) { i -> working.addTurn(if (i % 2 == 0) "user" else "assistant", "第${i}轮消息") }
        assertEquals(3, working.size())
        assertEquals(listOf("第2轮消息", "第3轮消息", "第4轮消息"), working.recent().map { it.text })

        val prompt = mem.buildSystemPrompt("2026-10-06", "2026-10-05", working)
        assertTrue(prompt.contains("第4轮消息"))
        assertFalse(prompt.contains("第0轮消息")) // 已淘汰的不进 prompt
    }

    @Test fun prompt_sectionsAlwaysPresent(): Unit = runBlocking {
        val mem = setUp()
        val prompt = mem.buildSystemPrompt("2026-10-06", "2026-10-05")
        assertTrue(prompt.contains("# 常驻记忆"))
        assertTrue(prompt.contains("# 今日"))
        assertTrue(prompt.contains("# 昨日"))
        assertTrue(prompt.contains("# 待办"))
    }
}
