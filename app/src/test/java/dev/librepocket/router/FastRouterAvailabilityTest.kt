package dev.librepocket.router

import dev.librepocket.tool.ProjectionContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #178 Stage 3 availability/CPU-budget pins. The extractor is pure but
 * regex-heavy; one [FastRouter.decide] must SHARE a single extraction between
 * `matchTemplate` (symbolArgs) and `missingQuestionFor` (hasSymbolQuery) —
 * the pre-#178 code ran it twice per symbol decision. These are JVM pins,
 * NOT device E2E.
 */
class FastRouterAvailabilityTest {

    private val ctx = ProjectionContext(userSwitches = mapOf("lsp" to true))

    @After
    fun resetCounter() {
        FastRouter.symbolExtractCallsForTest.set(0)
    }

    private fun queryOf(decision: RouteDecision): String =
        kotlinx.serialization.json.Json.parseToJsonElement(decision.toolCall!!.argumentsJson)
            .jsonObject["query"]!!.jsonPrimitive.content

    @Test
    fun oneDecisionExtractsSymbolQueryExactlyOnce() {
        FastRouter.symbolExtractCallsForTest.set(0)
        val d = FastRouter().decide("查 Foo 符號", ctx, "s1", 1)
        assertEquals(RouteKind.FAST, d.route)
        // Exact, not a range: the old (unmemoized) code also satisfied 1..2.
        assertEquals(1, FastRouter.symbolExtractCallsForTest.get())
    }

    @Test
    fun adversarial4096CharInput_staysWithinExtractionBudgetAndWallClockPin() {
        // 2 ("查 ") + 4090 a's + 3 (" 符號") = 4095 ≤ MAX_INTENT_CHARS.
        val adversarial = "查 " + "a".repeat(4090) + " 符號"
        assertEquals(4095, adversarial.length)
        FastRouter.symbolExtractCallsForTest.set(0)
        val start = System.nanoTime()
        repeat(20) { FastRouter().decide(adversarial, ctx, "s$it", it) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        // Exact: old code would be 40; a per-decision memo must give 20.
        assertEquals(20, FastRouter.symbolExtractCallsForTest.get())
        // Generous diagnostic ceiling against minute-scale pre-stage-8.2
        // behaviour; a slow CI host may exceed it without a correctness bug.
        assertTrue(
            "4096-char adversarial decisions must stay bounded (${elapsedMs}ms)",
            elapsedMs < 5_000L,
        )
    }

    @Test
    fun differentTextsAcrossDecisions_resetMemoAndNeverCrossContaminateQuery() {
        FastRouter.symbolExtractCallsForTest.set(0)
        val first = FastRouter().decide("查 Foo 符號", ctx, "s1", 1)
        assertEquals(1, FastRouter.symbolExtractCallsForTest.get())
        val second = FastRouter().decide("查 Bar 符號", ctx, "s1", 2)
        // The memo is scoped to ONE decide(): a second, different text must
        // run extraction again and must never reuse Foo's query.
        assertEquals(2, FastRouter.symbolExtractCallsForTest.get())
        assertEquals("Foo", queryOf(first))
        assertEquals("Bar", queryOf(second))
    }
}
