package dev.librepocket.memory

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D02 FTS 召回测试（BACKLOG D02 验收方向）：
 * 50 条合成语料召回率基线 + 删单条索引同步消失（级联删除）+ 授权面。
 *
 * 在 JVM 经 Robolectric 跑内存 Room 库；[MemoryStore.search] 优先 FTS MATCH，
 * 驱动不支持/语法错误时自动降级 LIKE，断言只针对 [MemoryStore] 公共语义，
 * 因此两种检索引擎下均成立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryFtsTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: MemoryDb

    private fun store(gate: MemoryGate = AllowMemoryGate()): MemoryStore {
        db = MemoryDb.openInMemory(ApplicationProvider.getApplicationContext())
        return MemoryStore(db, gate) { now }
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun save(
        store: MemoryStore,
        text: String,
        kind: MemoryKind = MemoryKind.EPISODIC,
    ): Long = runBlocking {
        store.save(kind, text, sourceSessionId = "sess-1", sourceRunId = "run-1")
    }

    /** 50 条合成语料：5 组 × 10 条，各组 ASCII 关键词唯一；每 5 条植入“鳳梨酥”。 */
    private fun plantCorpus(store: MemoryStore) {
        val groups = listOf("kw-alpha", "kw-bravo", "kw-charlie", "kw-delta", "kw-epsilon")
        repeat(50) { i ->
            val kw = groups[i % groups.size]
            val cjk = if (i % 5 == 0) " 鳳梨酥伴手礼" else ""
            save(store, "note-%02d %s 日常记录 padding-%d%s".format(i, kw, i, cjk))
        }
    }

    @Test fun corpus50_recallBaseline(): Unit = runBlocking {
        val s = store()
        plantCorpus(s)
        assertEquals(50, s.count())

        // 组关键词召回：10/10，基线要求 ≥ 0.8。
        val hits = s.search("kw-alpha", limit = 50)
        assertEquals(10, hits.size)
        assertTrue(hits.all { "kw-alpha" in it.text })
        val recall = hits.size / 10.0
        assertTrue("recall=$recall", recall >= 0.8)

        // 唯一条目精确命中。
        val one = s.search("note-07")
        assertEquals(1, one.size)
        assertTrue("note-07" in one.single().text)
    }

    @Test fun corpus50_cjkRecall(): Unit = runBlocking {
        val s = store()
        plantCorpus(s)
        // 植入 10 条含“鳳梨酥”，宽松下界 ≥ 8（容忍分词器差异）。
        val hits = s.search("鳳梨酥", limit = 50)
        assertTrue("cjk hits=${hits.size}", hits.size >= 8)
    }

    @Test fun kindFilter_scopesSearch(): Unit = runBlocking {
        val s = store()
        plantCorpus(s)
        save(s, "kw-alpha 偏好设置：常用导航", MemoryKind.SEMANTIC)
        save(s, "kw-alpha 偏好设置：音乐服务", MemoryKind.SEMANTIC)

        val sem = s.search("kw-alpha", limit = 50, kind = MemoryKind.SEMANTIC)
        assertEquals(2, sem.size)
        assertTrue(sem.all { it.kind == MemoryKind.SEMANTIC })
    }

    @Test fun delete_removesFromIndex(): Unit = runBlocking {
        val s = store()
        plantCorpus(s)
        val id = save(s, "zz-unique-42 仅此一条的会面纪要")

        val before = s.search("zz-unique-42")
        assertEquals(1, before.size)
        assertEquals(id, before.single().id)

        assertTrue(s.delete(id))
        assertFalse(s.delete(id)) // 二次删除返回 false，不抛错

        // 公共语义：检索同步消失 + 点查同步消失 + 计数同步减少。
        assertTrue(s.search("zz-unique-42").isEmpty())
        assertNull(s.get(id))
        assertEquals(50, s.count())

        // 直查 FTS 表（若驱动支持 FTS）：索引行同样消失，证明级联删除。
        // 驱动不支持时抛异常则回退到 store 语义断言（上已覆盖）。
        val ftsRows = try {
            db.memoryDao().searchFts("\"zz-unique-42\"", 10)
        } catch (_: Exception) {
            null
        }
        if (ftsRows != null) assertTrue(ftsRows.none { it.rowId == id })
    }

    @Test fun search_blankAndLimit(): Unit = runBlocking {
        val s = store()
        plantCorpus(s)
        assertTrue(s.search("   ").isEmpty())
        assertTrue(s.search("").isEmpty())
        val page = s.search("note", limit = 5)
        assertEquals(5, page.size)
    }

    @Test fun search_survivesFtsSyntaxNoise(): Unit = runBlocking {
        val s = store()
        save(s, "普通记录：明天要买牛奶")
        // 引号/星号等 MATCH 保留字不应把检索打崩（降级 LIKE 后返回集合即可）。
        val hits = s.search("\"牛奶\" *")
        assertTrue(hits.all { "牛奶" in it.text })
    }

    @Test fun save_requiresSource(): Unit = runBlocking {
        val s = store()
        try {
            s.save(MemoryKind.EPISODIC, "无来源写入", null, null)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("source"))
        }
    }

    @Test fun gate_deniesAllOps(): Unit = runBlocking {
        val denied = store(DenyMemoryGate())
        fun assertDenied(block: suspend () -> Unit) {
            try {
                runBlocking { block() }
                fail("expected MemoryDeniedException")
            } catch (e: MemoryDeniedException) {
                // fail-closed：异常消息不得携带记忆内容。
                assertFalse(e.message!!.contains("kw-alpha"))
            }
        }
        val allowed = store()
        val id = save(allowed, "kw-alpha 种子条目")
        assertDenied { denied.save(MemoryKind.EPISODIC, "kw-alpha 写", "s", "r") }
        assertDenied { denied.search("kw-alpha") }
        assertDenied { denied.get(id) }
        assertDenied { denied.recent() }
        assertDenied { denied.delete(id) }
        assertDenied { denied.count() }
    }

    @Test fun gate_partialDeny_readOnly(): Unit = runBlocking {
        val s = store(DenyMemoryGate(setOf(MemoryAction.WRITE, MemoryAction.DELETE)))
        try {
            s.save(MemoryKind.EPISODIC, "写应被拒", "s", "r")
            fail("expected MemoryDeniedException")
        } catch (e: MemoryDeniedException) {
            assertEquals(MemoryAction.WRITE, e.action)
        }
        // 读侧放行。
        assertTrue(s.search("anything").isEmpty())
    }
}
