package dev.librepocket.jev

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D01 Jev 最小可用整合測試（JEV_INTEGRATION_DRAFT §8 精簡）：
 * 路由 / 降級 / 快取 / 超時 / 熔斷 / 隱私斷言，全程 MockWebServer，不連外網。
 */
class JevRouterTest {

    // ---- fakes ----

    private class FakeVault(keys: Map<String, String> = mapOf("jev" to "k-jeopardy-test")) : KeyVault {
        private val map = keys.mapValues { it.value.toCharArray() }.toMutableMap()
        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            map[providerId] = apiKey.copyOf()
        }
        override suspend fun getKey(providerId: String): CharArray? = map[providerId]?.copyOf()
        override suspend fun deleteKey(providerId: String) {
            map.remove(providerId)
        }
        override suspend fun hasKey(providerId: String): Boolean = map.containsKey(providerId)
    }

    private class FakeChoice(
        var result: ChoiceResult = ChoiceResult("ALARM", mapOf("ALARM" to 0.9f), false, JevStatus.OK, 5),
        var delayMs: Long = 0,
    ) : JevChoice {
        var calls = 0
        var lastTask: String? = null
        var lastCandidates: List<JevCandidate>? = null
        override suspend fun choose(taskText: String, candidates: List<JevCandidate>, topK: Int, timeoutMs: Long): ChoiceResult {
            calls++
            lastTask = taskText
            lastCandidates = candidates
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private class FakeScore(
        var result: ScoreResult = ScoreResult(0.2f, listOf("LOW"), JevStatus.OK, 5),
        var delayMs: Long = 0,
    ) : JevScore {
        var calls = 0
        override suspend fun score(action: String, context: JevContext, timeoutMs: Long): ScoreResult {
            calls++
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private class FakeNoul(
        var result: NoulResult = NoulResult(false, 0.9f, JevStatus.OK, 5),
    ) : JevNoul {
        var calls = 0
        override suspend fun gate(action: String, score: ScoreResult?, context: JevContext, timeoutMs: Long): NoulResult {
            calls++
            return result
        }
    }

    private fun enabledRouter(
        choice: FakeChoice = FakeChoice(),
        score: FakeScore = FakeScore(),
        noul: FakeNoul = FakeNoul(),
        cache: JevCache = JevCache(),
        breaker: JevCircuitBreaker = JevCircuitBreaker(),
        shadow: Boolean = false,
    ): JevRouterImpl = JevRouterImpl(
        choice = choice,
        score = score,
        noul = noul,
        cache = cache,
        breaker = breaker,
        jevConfig = JevConfig(enabled = true, shadow = shadow),
    )

    private fun readCtx(side: SideEffect = SideEffect.READ) =
        JevContext(sideEffect = side, projection = "navigate,open_app", redactedGoal = "test goal")

    // ---- 預算與閾值常量 ----

    @Test fun budgetsMatchSpec() {
        assertEquals(300L, JevBudgets.CHOICE_TIMEOUT_MS)
        assertEquals(200L, JevBudgets.SCORE_TIMEOUT_MS)
        assertEquals(150L, JevBudgets.NOUL_TIMEOUT_MS)
        assertEquals(800L, JevBudgets.STEP_TOTAL_MS)
        val t = JevThresholds()
        assertEquals(0.75f, t.routeMinConf)
        assertEquals(0.65f, t.groundMinConf)
        assertEquals(0.60f, t.dangerConfirm)
        assertEquals(0.35f, t.autoExecMaxDanger)
    }

    @Test fun twelveCandidatesClosedSet() {
        val ids = JevIntents.defaultCandidates().map { it.id }
        assertEquals(12, ids.distinct().size)
        assertTrue(ids.containsAll(listOf("NAVIGATE", "OPEN_APP", "GUI_FALLBACK")))
        assertEquals(12, JevIntents.ORDER.size)
    }

    // ---- 路由 ----

    @Test fun routeHighConfGoesFast() = runBlocking {
        val router = enabledRouter(FakeChoice(ChoiceResult("ALARM", mapOf("ALARM" to 0.91f), false, JevStatus.OK, 5)))
        val d = router.routeIntent("明天早上七點半叫我起床設鬧鐘")
        assertTrue(d is JevFast)
        d as JevFast
        assertEquals("ALARM", d.bestId)
        assertFalse(d.needsSystemHandoff)
        assertTrue(d.executed)
        assertFalse(d.shadow)
    }

    @Test fun routePrivilegedHighConfStillHandoff() = runBlocking {
        val router = enabledRouter(FakeChoice(ChoiceResult("DIAL", mapOf("DIAL" to 0.95f), false, JevStatus.OK, 5)))
        val d = router.routeIntent("打電話給媽媽") as JevFast
        assertTrue(d.needsSystemHandoff)
        assertTrue(d.executed)
    }

    @Test fun routeLowConfFallsBackToLlm() = runBlocking {
        // 非模糊文本 + 中等置信 → 大模型，不澄清。
        val router = enabledRouter(FakeChoice(ChoiceResult("ALARM", mapOf("ALARM" to 0.5f), false, JevStatus.OK, 5)))
        val d = router.routeIntent("明天早上七點半設鬧鐘")
        assertTrue("expected LlmFallback but was $d", d is JevLlmFallback)
        assertEquals(JevFallbackCause.LOW_CONFIDENCE, (d as JevLlmFallback).cause)
        assertFalse(d.executed)
    }

    @Test fun routeFuzzyLowConfAsksClarify() = runBlocking {
        val router = enabledRouter(FakeChoice(ChoiceResult("OPEN_APP", mapOf("OPEN_APP" to 0.5f), false, JevStatus.OK, 5)))
        val d = router.routeIntent("打開那個App是哪個？")
        assertTrue("expected Clarify but was $d", d is JevClarify)
        assertTrue((d as JevClarify).hint.isNotBlank())
    }

    @Test fun routeAbstainFallsBack() = runBlocking {
        val router = enabledRouter(FakeChoice(ChoiceResult("GUI_FALLBACK", emptyMap(), true, JevStatus.ABSTAIN, 5)))
        val d = router.routeIntent("隨便弄一下")
        assertTrue(d is JevLlmFallback)
        assertEquals(JevFallbackCause.ABSTAIN, (d as JevLlmFallback).cause)
    }

    @Test fun routeGuiFallbackToLlm() = runBlocking {
        val router = enabledRouter(
            FakeChoice(ChoiceResult("GUI_FALLBACK", mapOf("GUI_FALLBACK" to 0.99f), false, JevStatus.OK, 5)),
        )
        val d = router.routeIntent("幫我自動操作螢幕跨App流程")
        assertTrue(d is JevLlmFallback)
        assertEquals(JevFallbackCause.GUI_FALLBACK, (d as JevLlmFallback).cause)
    }

    @Test fun routeUnknownIdOtherEscape() = runBlocking {
        val router = enabledRouter(FakeChoice(ChoiceResult("OTHER", mapOf("OTHER" to 0.99f), false, JevStatus.OK, 5)))
        val d = router.routeIntent("做點別的")
        assertTrue(d is JevLlmFallback)
        assertEquals(JevFallbackCause.UNKNOWN_ID, (d as JevLlmFallback).cause)
    }

    // ---- 降級：預設關 / NoOp 等價 ----

    @Test fun defaultConfigIsDisabledShadow() {
        val c = JevConfig.defaultDisabled()
        assertFalse(c.enabled)
        assertTrue(c.shadow)
        val router = JevRouterImpl(FakeChoice(), FakeScore(), FakeNoul())
        assertFalse(router.config().enabled)
    }

    @Test fun disabledRouterMakesZeroCallsAndNeverExecutes() = runBlocking {
        val choice = FakeChoice()
        val router = JevRouterImpl(choice, FakeScore(), FakeNoul(), jevConfig = JevConfig.defaultDisabled())
        val d = router.routeIntent("明天七點設鬧鐘")
        assertEquals(0, choice.calls)
        assertTrue(d is JevLlmFallback)
        assertEquals(JevFallbackCause.DISABLED, (d as JevLlmFallback).cause)
        assertTrue(d.shadow)
        assertFalse(d.executed)
        val risk = router.checkRisk("alarm.create", readCtx(SideEffect.WRITE))
        assertTrue(risk.shadow)
        assertFalse(risk.autoExecute)
        assertTrue(risk.needConfirm)
    }

    @Test fun shadowModeRecordsButNeverExecutes() = runBlocking {
        val router = enabledRouter(
            FakeChoice(ChoiceResult("ALARM", mapOf("ALARM" to 0.95f), false, JevStatus.OK, 5)),
            shadow = true,
        )
        val d = router.routeIntent("明天早上七點設鬧鐘")
        assertTrue(d is JevFast)
        assertTrue(d.shadow)
        assertFalse((d as JevFast).executed)
    }

    @Test fun noopRouterParityConservative() = runBlocking {
        val noop = NoOpJevRouter()
        val d = noop.routeIntent("hi")
        assertTrue(d is JevLlmFallback)
        val write = noop.checkRisk("mail.compose", readCtx(SideEffect.WRITE))
        assertEquals(0.6f, write.score.score)
        assertTrue(write.needConfirm)
        assertFalse(write.autoExecute)
        val priv = noop.checkRisk("phone.dial", readCtx(SideEffect.PRIVILEGED))
        assertTrue(priv.needConfirm)
        assertFalse(priv.autoExecute)
        val read = noop.checkRisk("navigate", readCtx(SideEffect.READ))
        assertFalse(read.needConfirm)
    }

    // ---- 快取 ----

    @Test fun routeCacheHitMakesZeroSecondNetworkCall() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"bestId":"ALARM","confidences":{"ALARM":0.91},"abstain":false}"""),
            )
            val base = server.url("/").toString().trimEnd('/')
            val client = JevClient(base, FakeVault(), http = OkHttpClient())
            val router = JevRouterImpl(client, client, client, jevConfig = JevConfig(enabled = true, shadow = false))
            runBlocking {
                val a = router.routeIntent("明天早上七點半設鬧鐘")
                assertTrue(a is JevFast)
                assertFalse(a.result.cached)
                val b = router.routeIntent("明天早上七點半設鬧鐘")
                assertTrue(b is JevFast)
                assertTrue(b.result.cached)
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun highRiskNeverCached() = runBlocking {
        val score = FakeScore(ScoreResult(0.85f, listOf("SEND"), JevStatus.OK, 5))
        val router = enabledRouter(score = score)
        val ctx = readCtx(SideEffect.WRITE)
        router.checkRisk("sms.compose", ctx)
        router.checkRisk("sms.compose", ctx)
        assertEquals(2, score.calls)
    }

    @Test fun lowRiskReadIsCached() = runBlocking {
        val score = FakeScore(ScoreResult(0.2f, listOf("LOW"), JevStatus.OK, 5))
        val router = enabledRouter(score = score)
        val ctx = readCtx(SideEffect.READ)
        router.checkRisk("navigate", ctx)
        router.checkRisk("navigate", ctx)
        assertEquals(1, score.calls)
    }

    // ---- 超時 ----

    @Test fun choiceTimeoutMapsToFallbackNoRetry() = runBlocking {
        val slow = FakeChoice(delayMs = 600)
        val router = enabledRouter(choice = slow)
        val d = router.routeIntent("明天七點設鬧鐘")
        assertTrue(d is JevLlmFallback)
        assertEquals(JevFallbackCause.TIMEOUT, (d as JevLlmFallback).cause)
        assertEquals(1, slow.calls)
    }

    @Test fun clientTimeoutAgainstSlowServer() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(600)
                return MockResponse().setResponseCode(200)
                    .setBody("""{"bestId":"ALARM","confidences":{"ALARM":0.9},"abstain":false}""")
            }
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val client = JevClient(base, FakeVault(), http = OkHttpClient())
            val res = runBlocking {
                client.choose("明天七點設鬧鐘", JevIntents.defaultCandidates(), timeoutMs = 300)
            }
            assertEquals(JevStatus.TIMEOUT, res.status)
            assertTrue(res.abstain)
        } finally {
            server.shutdown()
        }
    }

    // ---- 熔斷 ----

    @Test fun breakerOpensAfterFiveFailures() = runBlocking {
        var now = 0L
        val breaker = JevCircuitBreaker(clockMs = { now })
        val failing = FakeChoice(ChoiceResult("GUI_FALLBACK", emptyMap(), true, JevStatus.ERROR, 0))
        val router = enabledRouter(choice = failing, breaker = breaker)
        repeat(5) { i ->
            val d = router.routeIntent("文本$i 設鬧鐘")
            assertTrue(d is JevLlmFallback)
        }
        assertEquals(5, failing.calls)
        assertTrue(breaker.isOpen())
        val d = router.routeIntent("另一文本 設鬧鐘")
        assertTrue(d is JevLlmFallback)
        assertEquals(JevFallbackCause.CIRCUIT_OPEN, (d as JevLlmFallback).cause)
        assertEquals(5, failing.calls)
        // 60s 後半開恢復。
        now += 61_000L
        val d2 = router.routeIntent("恢復後文本 設鬧鐘")
        assertEquals(6, failing.calls)
        assertTrue((d2 as JevLlmFallback).cause != JevFallbackCause.CIRCUIT_OPEN)
    }

    @Test fun abstainDoesNotTripBreaker() = runBlocking {
        val breaker = JevCircuitBreaker()
        val abstaining = FakeChoice(ChoiceResult("GUI_FALLBACK", emptyMap(), true, JevStatus.ABSTAIN, 0))
        val router = enabledRouter(choice = abstaining, breaker = breaker)
        repeat(6) { router.routeIntent("文本$it") }
        assertFalse(breaker.isOpen())
        assertEquals(0, breaker.failureCount())
    }

    // ---- 風控規則先行 ----

    @Test fun riskRuleFloorForPaymentDelete() = runBlocking {
        val score = FakeScore(ScoreResult(0.1f, listOf("LOW"), JevStatus.OK, 5))
        val router = enabledRouter(score = score)
        val d = router.checkRisk("轉帳 10000 元給陌生帳戶", readCtx(SideEffect.WRITE))
        assertTrue(d.score.score >= 0.65f)
        assertTrue(d.score.reasonCodes.contains("RULE_FLOOR"))
        assertTrue(d.needConfirm)
        assertFalse(d.autoExecute)
    }

    @Test fun privilegedAlwaysConfirmsWithoutModel() = runBlocking {
        val score = FakeScore()
        val noul = FakeNoul()
        val router = enabledRouter(score = score, noul = noul)
        val d = router.checkRisk("phone.dial", readCtx(SideEffect.PRIVILEGED))
        assertTrue(d.needConfirm)
        assertFalse(d.autoExecute)
        assertEquals(0, score.calls)
    }

    // ---- 隱私 / 線路 ----

    @Test fun requestPinsModelEndpointAndQuestionKeys() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"bestId":"ALARM","confidences":{"ALARM":0.9},"abstain":false}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"score":0.2,"reasonCodes":["LOW"]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"needConfirm":false,"confidence":0.9}"""))
            val base = server.url("/").toString().trimEnd('/')
            val client = JevClient(base, FakeVault(), http = OkHttpClient())
            runBlocking {
                client.choose("設鬧鐘", JevIntents.defaultCandidates())
                client.score("alarm.create", readCtx(SideEffect.WRITE))
                client.gate("alarm.create", ScoreResult(0.2f, listOf("LOW"), JevStatus.OK, 1), readCtx(SideEffect.WRITE))
            }
            repeat(3) {
                val req = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/v1/systemone", req.path)
                val body = req.body.readUtf8()
                assertTrue("model pin missing: $body", body.contains("jev-1.13.0"))
                assertTrue("head/question keys missing: $body", body.contains("\"head\"") && body.contains("\"question\""))
                // 截圖像素鍵永不出現。
                for (k in listOf("bytes", "bitmap", "pixels", "image_base64", "screenshot_png")) {
                    assertFalse("pixel key leak: $k in $body", body.contains("\"$k\""))
                }
                // Key 走 Authorization，不在 body。
                assertFalse(body.contains("k-jeopardy-test"))
            }
            val choiceBody = server.takeRequest(100, TimeUnit.MILLISECONDS)
            assertNull(choiceBody)
        } finally {
            server.shutdown()
        }
    }

    @Test fun choiceQuestionKeysAligned() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"bestId":"ALARM","confidences":{"ALARM":0.9},"abstain":false}"""))
            val base = server.url("/").toString().trimEnd('/')
            runBlocking {
                JevClient(base, FakeVault(), http = OkHttpClient())
                    .choose("text", JevIntents.defaultCandidates())
            }
            val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(body.contains("taskText"))
            assertTrue(body.contains("candidates"))
            assertTrue(body.contains("topK"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun sensitiveTextNeverLeavesTerminal() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"bestId":"ALARM","confidences":{"ALARM":0.9},"abstain":false}"""))
            val base = server.url("/").toString().trimEnd('/')
            val raw = "寄郵件給 test@example.com 打 0912345678 密鑰 sk-abc123XYZ4567890"
            val res = runBlocking {
                JevClient(base, FakeVault(), http = OkHttpClient()).choose(raw, JevIntents.defaultCandidates())
            }
            val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertFalse(body.contains("test@example.com"))
            assertFalse(body.contains("0912345678"))
            assertFalse(body.contains("sk-abc123XYZ4567890"))
            // 轉錄只記 id/conf/reasonCodes：審計映射不含原文。
            val audit = res.auditMap().toString()
            assertFalse(audit.contains("test@example.com"))
            assertFalse(audit.contains("0912345678"))
            val risk = ScoreResult(0.8f, listOf("SEND"), JevStatus.OK, 3).auditMap().toString()
            assertFalse(risk.contains("test@example.com"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun missingKeyDegradesWithoutNetwork() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val emptyVault = FakeVault(emptyMap())
            val res = runBlocking {
                JevClient(base, emptyVault, http = OkHttpClient())
                    .choose("設鬧鐘", JevIntents.defaultCandidates(), timeoutMs = 300)
            }
            assertEquals(JevStatus.UNAVAILABLE, res.status)
            assertTrue(res.abstain)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun rerankTruncatesToTwentyAndCaches() = runBlocking {
        val fake = FakeChoice(ChoiceResult("node#3", mapOf("node#3" to 0.9f), false, JevStatus.OK, 5))
        val router = enabledRouter(choice = fake)
        val nodes = (1..25).map { JevCandidate("node#$it", "按鈕 $it") }
        val d = router.rerankNodes("點確定", nodes, pageSignature = "p1")
        assertTrue(d is JevRerankHit)
        assertEquals(20, fake.lastCandidates!!.size)
        val d2 = router.rerankNodes("點確定", nodes, pageSignature = "p1")
        assertTrue((d2 as JevRerankHit).result.cached)
        assertEquals(1, fake.calls)
    }

    @Test fun rerankLowConfUpgradesToVlm() = runBlocking {
        val fake = FakeChoice(ChoiceResult("node#1", mapOf("node#1" to 0.4f), false, JevStatus.OK, 5))
        val router = enabledRouter(choice = fake)
        val nodes = listOf(JevCandidate("node#1", "確定"), JevCandidate("node#2", "取消"))
        val d = router.rerankNodes("點確定", nodes, pageSignature = "p9")
        assertTrue(d is JevRerankFallback)
        assertEquals(JevFallbackCause.NEED_CONFIRM_UPGRADE, (d as JevRerankFallback).cause)
    }
}
