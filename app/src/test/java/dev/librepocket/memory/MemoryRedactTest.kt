package dev.librepocket.memory

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D02 脱敏测试（BACKLOG D02 验收方向）：写盘前不含敏感原文。
 *
 * 覆盖三条写路径——Room 正文列、常驻文件（MEMORY.md/daily/scratchpad）、
 * 启动 prompt 组装——断言落盘/出参只含占位符，不含原文；另覆盖授权面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryRedactTest {

    private lateinit var db: MemoryDb
    private lateinit var tmpDir: File

    private fun setUp() {
        db = MemoryDb.openInMemory(ApplicationProvider.getApplicationContext())
        tmpDir = Files.createTempDirectory("memory-redact-test").toFile()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (this::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    companion object {
        const val EMAIL = "user@example.com"
        const val PHONE = "0912345678"
        const val API_KEY = "sk-abcDEF1234567890"
        const val URL_SECRET = "https://api.example.com/v1?api_key=SECRETVALUE123&x=1"
    }

    private fun secretText(): String =
        "联系 $EMAIL 电话 $PHONE 密钥 $API_KEY 接口 $URL_SECRET 完毕"

    @Test fun save_redactsBeforePersist(): Unit = runBlocking {
        setUp()
        val store = MemoryStore(db) { 1L }
        val id = store.save(
            MemoryKind.EPISODIC,
            secretText(),
            sourceSessionId = "sess-1",
            sourceRunId = "run-1",
        )

        // 读模型已脱敏。
        val item = store.get(id)!!
        for (secret in listOf(EMAIL, PHONE, API_KEY, "SECRETVALUE123")) {
            assertFalse("leaked via model: $secret", secret in item.text)
        }
        assertTrue(item.text.contains("⟦REDACTED"))

        // 原始行同样干净：证明脱敏发生在写盘前，而非读取时。
        val raw = db.memoryDao().byId(id)!!.text
        for (secret in listOf(EMAIL, PHONE, API_KEY, "SECRETVALUE123")) {
            assertFalse("leaked at rest: $secret", secret in raw)
        }

        // 检索路径也不泄露。
        for (hit in store.search("联系", limit = 10)) {
            for (secret in listOf(EMAIL, PHONE, API_KEY)) {
                assertFalse("leaked via search: $secret", secret in hit.text)
            }
        }
    }

    @Test fun files_redactedOnDisk(): Unit = runBlocking {
        setUp()
        val mem = ResidentMemory(File(tmpDir, "memory")) { 1L }
        mem.saveResident("我是常用导航 $EMAIL，电话 $PHONE")
        mem.appendDaily("2026-10-06", "买了咖啡 $PHONE，密钥 $API_KEY")
        mem.addTask("回邮件给 $EMAIL")

        // 直接读裸文件字节：三处落盘均不得含原文。
        val files = tmpDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("expected persisted files, got none", files.isNotEmpty())
        val allRaw = files.joinToString("\n") { it.readText(Charsets.UTF_8) }
        for (secret in listOf(EMAIL, PHONE, API_KEY)) {
            assertFalse("leaked on disk: $secret", secret in allRaw)
        }
        assertTrue(allRaw.contains("⟦REDACTED"))
    }

    @Test fun prompt_containsNoSecrets(): Unit = runBlocking {
        setUp()
        val mem = ResidentMemory(File(tmpDir, "memory")) { 1L }
        mem.saveResident("偏好：$EMAIL")
        mem.appendDaily("2026-10-06", "电话 $PHONE")
        mem.addTask("处理 $API_KEY 相关事项")
        val working = WorkingMemory()
        working.addTurn("user", "明天提醒我买牛奶")

        val prompt = mem.buildSystemPrompt("2026-10-06", "2026-10-05", working)
        for (secret in listOf(EMAIL, PHONE, API_KEY, "SECRETVALUE123")) {
            assertFalse("leaked via prompt: $secret", secret in prompt)
        }
        // 文件侧来源的密钥必须已脱敏。
        assertTrue(prompt.contains("⟦REDACTED"))
    }

    @Test fun gate_deniesResidentOps(): Unit = runBlocking {
        setUp()
        val mem = ResidentMemory(File(tmpDir, "memory"), DenyMemoryGate()) { 1L }
        suspend fun assertDenied(block: suspend () -> Unit) {
            try {
                block()
                fail("expected MemoryDeniedException")
            } catch (_: MemoryDeniedException) {
            }
        }
        assertDenied { mem.saveResident("x") }
        assertDenied { mem.loadResident() }
        assertDenied { mem.appendDaily("2026-10-06", "x") }
        assertDenied { mem.readDaily("2026-10-06") }
        assertDenied { mem.addTask("x") }
        assertDenied { mem.completeTask("x") }
        assertDenied { mem.listOpenTasks() }
        assertDenied { mem.buildSystemPrompt("2026-10-06", "2026-10-05") }
    }
}
