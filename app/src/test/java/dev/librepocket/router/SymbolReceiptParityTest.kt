package dev.librepocket.router

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ToolCall as ProviderToolCall
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Stage-4 (#19 B3) mechanical drift guard for the `lsp.symbols` success
 * receipt: the slice's `ok()` ([dev.librepocket.router.SafeToolSlice]) and the
 * fast-lane executor's `okResult()` ([LspSymbolsExecutor]) must emit the
 * byte-/semantic-same receipt for the same inputs. The helpers are now
 * single-sourced in [SafeToolSlice]'s companion object; the two receipt
 * PIPELINES (`ok()` / `okResult()`) are still hand-mirrored, so this test is the
 * guard that catches any future drift between them (a changed order of
 * redaction, a missing cap, an `omittedCount` off-by-one).
 *
 * DOCUMENTED, DELIBERATE ASYMMETRIES (the only ones the comparison tolerates):
 * 1. the slice Ok receipt carries `clockMillis`; the fast-lane receipt does
 *    NOT (the fast lane has no clock). The slice receipt is compared with
 *    `clockMillis` PARSED OUT and ignored — never byte-compared raw; that
 *    post-hoc strip is cosmetic for the comparison only. Both shrink loops
 *    ([boundaryShrinkLoopCutPointParity] pins it at the boundary) shrink
 *    against `MAX_RESULT_CHARS - RESULT_METADATA_RESERVE`: the slice's
 *    shrink candidate omits `clockMillis`, which the envelope appends only
 *    after the loop (paid for by the reservation), so both pipelines decide
 *    on the same effective budget and select identical symbol sets by
 *    construction regardless of clock value; the comparison's field strip
 *    stays cosmetic, and the FINAL backstop that rejects an over-
 *    MAX_RESULT_CHARS JSON stays unchanged on both paths.
 * 2. the slice's `tool` echoes `cappedTake(Redactor.redact(call.name))`; the
 *    fast-lane's `tool` is the constant [LspTools.SYMBOLS_NAME]. The corpus
 *    always calls with `name == "lsp.symbols"` (which both sides render as
 *    the same string) and the test additionally pins each side's own rule;
 *    the comparison therefore also holds for this corpus.
 * 3. key ORDER differs (the fast-lane places `denied` third) — comparison is
 *    done on parsed [JsonObject]s (order-insensitive map equality), never on
 *    the raw strings. The `symbols` ARRAY itself is order-sensitive and is
 *    additionally compared byte-wise (same key order, same escaping).
 * 4. DENIAL receipts are asymmetric by design: the fast-lane deny receipt has
 *    exactly `{denied, code, tool, callId}`; the slice deny receipt adds
 *    `detail` and `clockMillis`. Denial paths (backend crash / projection
 *    failure) are therefore compared only on the fast-lane key subset.
 *
 * Everything else — top-level key set (modulo the exclusions above),
 * `returnedCount`/`omittedCount`/`truncated`, `fromStub`, `callId` bounded
 * form, per-symbol name/kind/path redaction+caps, shrink-loop cut points
 * under [SafeToolSlice.MAX_RESULT_CHARS] minus the shared metadata reserve
 * ([SafeToolSlice.RESULT_METADATA_RESERVE]) — must be identical, and the corpus
 * below covers: 0/1/N symbols, redaction-triggering names, fields at the
 * [SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS] (2000) raw bound and the
 * [SafeToolSlice.MAX_STRING_CHARS] (500) echo cap (both the raw-omission
 * branch and the post-redaction-inflation branch), `omittedCount > 0`
 * (limit drop), the shrink-loop total-budget trip, astral-plane names whose
 * surrogate-safe cut lands mid-pair, empty/partial fields, a numbered-line
 * extreme, and oversized call ids (bounded `#`-SHA-256 form).
 *
 * Budget-consumption parity: the fast-lane and slice both shrink the chosen
 * symbols item-wise until the serialized receipt fits
 * `MAX_RESULT_CHARS - RESULT_METADATA_RESERVE` (the shared envelope budget
 * both shrink loops decide on; the emitted slice receipt then carries
 * `clockMillis` appended after the loop); the shrink case asserts identical
 * `returnedCount`/`omittedCount` on both sides, and [boundaryShrinkLoopCutPointParity]
 * pins the identical cut points at the boundary where the raw cap alone
 * would have let the fast lane keep one more symbol than the slice.
 * The single-symbol-over-budget EXEC_ERROR backstop is unreachable
 * with today's constants (one max-size symbol serializes far below
 * MAX_RESULT_CHARS — pinned below by [singleSymbolCeilingPin]); the backstop
 * is covered at its projection-boundary EXEC_ERROR arms
 * (SafeToolSliceTest.throwingBackendMapsToTypedExecError;
 * FastDispatchTest.okResultInternalBoundaryMapsIteratorThrowToExecError) plus
 * the ceiling pins (SafeToolSliceTest.resultBudgetSingleSymbolFloorStaysUnderTotalCap
 * — and [singleSymbolCeilingPin]
 * here); the literal single-symbol-over-MAX_RESULT_CHARS branch is unreachable
 * and NOT asserted.
 *
 * Determinism: fixed clock, no sleeps, no IO; same inputs ⇒ same receipts.
 */
class SymbolReceiptParityTest {

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("UTC"))
    private val fixedMillis: Long = fixedClock.millis()
    private val ctx: ProjectionContext = ProjectionContext(userSwitches = mapOf("lsp" to true))

    /** Backend replying with a FIXED symbol list; `fromStub=false` on both paths. */
    private class FixedBackend(private val reply: List<LspTools.Symbol>) : LspTools.Backend {
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> = reply
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    /** Backend whose `symbols` throws — drives the deny-shape parity case. */
    private object CrashingBackend : LspTools.Backend {
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
            throw RuntimeException("backend-marker-should-never-ride-the-wire")
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    /** Backend whose invocation count is observable — drives the rejected-before-executor parity case. */
    private class CountingBackend : LspTools.Backend {
        var invocations: Int = 0
            private set

        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations += 1
            return emptyList()
        }

        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    /** Backend whose symbols list throws on `size` — projection-boundary crash, both pipelines. */
    private object IterationCrashingBackend : LspTools.Backend {
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
            object : AbstractList<LspTools.Symbol>() {
                override val size: Int get() = throw RuntimeException("size-marker-should-never-ride-the-wire")
                override fun get(index: Int): LspTools.Symbol = throw RuntimeException("get-marker")
            }
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    private fun allowPolicy(): InMemoryPolicyStore =
        InMemoryPolicyStore(
            listOf(
                PolicyRule("chat.send:*", Verdict.ALLOW, 10),
                PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 10),
            ),
        )

    private fun slice(backend: LspTools.Backend): SafeToolSlice =
        SafeToolSlice(
            ctxSupplier = { ctx },
            policy = allowPolicy(),
            backend = backend,
            clock = fixedClock,
        )

    private fun sliceReceipt(args: String, id: String, backend: LspTools.Backend) =
        runBlocking { slice(backend).execute(listOf(ProviderToolCall(id = id, name = "lsp.symbols", argumentsJson = args))).single() }

    private fun fastReceipt(args: String, id: String, backend: LspTools.Backend) =
        runBlocking { LspSymbolsExecutor(backend).execute(ToolCall(toolName = "lsp.symbols", argumentsJson = args, callId = id), ctx) }

    /** Strip the one documented non-injectable field (`clockMillis`); everything else must match. */
    private fun normalizeSliceOk(resultJson: String): JsonObject =
        JsonObject(Json.parseToJsonElement(resultJson).jsonObject - "clockMillis")

    private fun parse(resultJson: String): JsonObject = Json.parseToJsonElement(resultJson).jsonObject

    private fun assertOkParity(name: String, args: String, id: String, backend: LspTools.Backend) {
        val sliceResult = sliceReceipt(args, id, backend)
        val fastResult = fastReceipt(args, id, backend)

        assertFalse("$name: slice result must be Ok (error if denied: ${sliceResult.denyCode})", sliceResult.denied)
        assertFalse("$name: fast-lane result must be Ok (error if denied: ${fastResult.denyCode})", fastResult.denied)
        assertNull(sliceResult.denyCode)
        assertNull(fastResult.denyCode)

        val sliceJson = normalizeSliceOk(sliceResult.resultJson)
        val fastJson = parse(fastResult.resultJson)

        assertEquals(
            "$name: both paths must carry clockMillis-free identical key sets",
            fastJson.keys,
            sliceJson.keys,
        )
        assertEquals(
            "$name: documented tools echo rules — slice echoes capped-redacted call.name, fast lane uses the constant",
            "lsp.symbols",
            fastJson["tool"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "$name: slice tool echo",
            SafeToolSlice.cappedTake(dev.librepocket.redact.Redactor.redact("lsp.symbols").text, SafeToolSlice.MAX_STRING_CHARS),
            sliceJson["tool"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "$name: slice receipt must omit clockMillis after normalization",
            false,
            sliceJson.containsKey("clockMillis"),
        )
        // Semantic equality of everything except the two documented echo rules,
        // plus byte-equality of the order-sensitive symbols array.
        assertEquals("$name: symbols array (order-sensitive, byte compare)", fastJson["symbols"].toString(), sliceJson["symbols"].toString())
        assertEquals("$name: returnedCount", fastJson["returnedCount"], sliceJson["returnedCount"])
        assertEquals("$name: omittedCount", fastJson["omittedCount"], sliceJson["omittedCount"])
        assertEquals("$name: truncated", fastJson["truncated"], sliceJson["truncated"])
        assertEquals("$name: fromStub", fastJson["fromStub"], sliceJson["fromStub"])
        assertEquals("$name: query", fastJson["query"], sliceJson["query"])
        assertEquals("$name: callId (bounded redaction)", fastJson["callId"], sliceJson["callId"])
        assertEquals("$name: denied", fastJson["denied"], sliceJson["denied"])
        assertEquals("$name: whole receipt after documented normalization", fastJson, sliceJson)
    }

    /** Deny receipts are asymmetric by design; compare only the shared fast-lane key subset. */
    private fun assertDeniedParity(
        name: String,
        args: String,
        id: String,
        backend: LspTools.Backend,
        expectedCode: String,
    ) {
        val sliceResult = sliceReceipt(args, id, backend)
        val fastResult = fastReceipt(args, id, backend)
        assertTrue("$name: slice result must be denied", sliceResult.denied)
        assertTrue("$name: fast-lane result must be denied", fastResult.denied)
        assertEquals("$name: slice deny code", expectedCode, sliceResult.denyCode)
        assertEquals("$name: fast-lane deny code", expectedCode, fastResult.denyCode)

        val fastJson = parse(fastResult.resultJson)
        val sliceJson = parse(sliceResult.resultJson)
        assertEquals("$name: fast-lane deny key set is fixed", setOf("denied", "code", "tool", "callId"), fastJson.keys)
        assertTrue("$name: slice deny must carry the same shared subset", sliceJson.keys.containsAll(fastJson.keys))
        for (marker in listOf("backend-marker", "size-marker")) {
            assertEquals("$name: fixed backend messages never ride the wire", false, marker in fastResult.resultJson)
            assertEquals("$name: fixed backend messages never ride the wire", false, marker in sliceResult.resultJson)
        }
        for (k in fastJson.keys) {
            assertEquals("$name: deny key '$k' must agree", fastJson[k], sliceJson[k])
        }
        assertTrue("$name: clockMillis only on the slice", sliceJson.containsKey("clockMillis"))

        assertEquals("$name: fixed clock is exactly the injected one", fixedMillis.toString(), sliceJson["clockMillis"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- corpus cases

    private fun benign(name: String, kind: String = "function", path: String = "app/src/main/Example.kt", line: Int = 1) =
        LspTools.Symbol(name, kind, path, line)

    private data class ParityCase(val name: String, val args: String, val id: String, val symbols: List<LspTools.Symbol>)

    private fun parityCases(): List<ParityCase> {
        val args = """{"query":"Foo"}"""
        val argsLimit3 = """{"query":"Foo","limit":3}"""
        val seven = (1..7).map { benign("Symbol$it", "kind$it", "app/src/main/S$it.kt", it * 3) }
        val twoEmails = "\u0078".repeat(452) + "u@a.co".repeat(4) // raw ≤ 500; redacts to > 500 (EMAIL inflation)
        val fiveHundredByteName = "n".repeat(499) + "\uD834\uDF1E" // 501 units; astral char straddles the 500-unit cut
        return listOf(
            ParityCase("0 symbols", args, "call-empty", emptyList()),
            ParityCase("1 symbol", args, "call-one", listOf(benign("Foo", "function", "app/src/main/Foo.kt", 12))),
            ParityCase("N symbols (7, mixed kinds)", args, "call-seven", seven),
            ParityCase("limit drop: returnedCount=3 omittedCount=2", argsLimit3, "call-limit", (1..5).map { benign("Item$it") }),
            ParityCase("limit larger than symbols: all returned", """{"query":"Foo","limit":50}""", "call-limit-big", (1..3).map { benign("Big$it") }),
            ParityCase("redaction triggers in name (API key)", args, "call-apikey", listOf(benign("sk-LiveDeadBeef1234"))),
            ParityCase("redaction triggers in path (email)", args, "call-email", listOf(benign("Foo", path = "src/user@example.com/File.kt"))),
            ParityCase("post-redaction inflation over the echo cap (raw <= 500)", args, "call-inflated", listOf(benign(twoEmails))),
            ParityCase("field at RAW_BACKEND_FIELD_MAX_CHARS=2000 (pre-cap passes)", args, "call-2000", listOf(benign("n".repeat(SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS)))),
            ParityCase("field over RAW_BACKEND_FIELD_MAX_CHARS+1 (2001 → fixed omission receipt)", args, "call-2001", listOf(benign("n".repeat(SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS + 1)))),
            ParityCase("over-bound path (2001 → fixed omission receipt)", args, "call-path-2001", listOf(benign("OverPath", path = "p".repeat(SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS + 1)))),
            ParityCase("astral-plane name straddling the 500-unit cut", args, "call-astral", listOf(benign(fiveHundredByteName))),
            ParityCase("astral-plane name, astral-only tail", args, "call-astral2", listOf(benign("𝄞𝄞𝄞𝄞𝄞𝄞Foo𝄞"))),
            ParityCase("empty/partial fields", args, "call-partial", listOf(LspTools.Symbol("", "", "", 0), benign("OnlyName", "", "", Int.MAX_VALUE))),
            ParityCase("line extreme Int.MAX_VALUE", args, "call-maxline", listOf(benign("Max", line = Int.MAX_VALUE))),
            ParityCase("format code point inside backend name (echo, not parse gate)", args, "call-zwsp", listOf(benign("a\u200Bb"))),
            ParityCase("oversized call id (>500, bounded #-SHA-256 both sides)", args, "x".repeat(600), listOf(benign("LongId"))),
            ParityCase("oversized call id straddling the surrogate-safe cut", args, "y".repeat(499) + "\uD834\uDF1E" + "z".repeat(120), listOf(benign("AstralId"))),
            ParityCase("query longer than the echo cap (600 → capped + marker)", """{"query":"${"q".repeat(600)}"}""", "call-longquery", listOf(benign("LongQuery"))),
            ParityCase("budget shrink loop (60 max-field symbols, limit 200)", """{"query":"Foo","limit":200}""", "call-shrink",
                (1..60).map { benign("n".repeat(500) + it, "k".repeat(500), "p".repeat(490) + it) }),
            ParityCase("NFKC-unstable query text echo is stable for both", """{"query":"ｓｒｃ"}""", "call-nfkc", listOf(benign("Nfkc"))),
            ParityCase("explicit pathPrefix accepted", """{"query":"Foo","pathPrefix":"src/main"}""", "call-prefix", listOf(benign("Prefix"))),
        )
    }

    @Test fun okReceiptsAgreeAcrossBothPipelines() {
        for (c in parityCases()) {
            assertOkParity(c.name, c.args, c.id, FixedBackend(c.symbols))
        }
    }

    @Test fun boundedRawBackendFieldBoundaryFlipsAreIdentical() {
        // The 2000→2001 raw-bound flip must move BOTH pipelines from the
        // echoed-capped shape to the fixed omission receipt together, and
        // the echo-cap flip at 500 must agree too.
        val twoThousand = "n".repeat(SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS)
        val twoThousandAndOne = "n".repeat(SafeToolSlice.RAW_BACKEND_FIELD_MAX_CHARS + 1)
        for (field in listOf(LspTools.Symbol(twoThousand, "function", "app/Main.kt", 1), LspTools.Symbol(twoThousandAndOne, "function", "app/Main.kt", 1))) {
            val sliceJson = normalizeSliceOk(sliceReceipt("""{"query":"Foo"}""", "call-flip", FixedBackend(listOf(field))).resultJson)
            val fastJson = parse(fastReceipt("""{"query":"Foo"}""", "call-flip", FixedBackend(listOf(field))).resultJson)
            assertEquals("raw-bound flip must agree on truncated", sliceJson["truncated"], fastJson["truncated"])
            assertEquals("raw-bound flip must agree on the symbols array", fastJson["symbols"].toString(), sliceJson["symbols"].toString())
        }
        val atBound = normalizeSliceOk(sliceReceipt("""{"query":"Foo"}""", "call-at", FixedBackend(listOf(LspTools.Symbol(twoThousand, "function", "app/Main.kt", 1)))).resultJson)
        val overBound = normalizeSliceOk(sliceReceipt("""{"query":"Foo"}""", "call-over", FixedBackend(listOf(LspTools.Symbol(twoThousandAndOne, "function", "app/Main.kt", 1)))).resultJson)
        val atName = atBound["symbols"]!!.let { s -> (s as kotlinx.serialization.json.JsonArray)[0].jsonObject["name"]!!.jsonPrimitive.content }
        val overName = overBound["symbols"]!!.let { s -> (s as kotlinx.serialization.json.JsonArray)[0].jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("at-bound field must be echoed with the cap marker, was: ${atName.take(40)}", atName.contains("…[truncated"))
        assertEquals("[backend field omitted]", overName)
        assertTrue(atBound["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(overBound["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun omitCountExceedsReportingCapIdentical() {
        // 5 symbols, limit 3 → 2 omitted on BOTH sides, truncated forced true.
        val args = """{"query":"Foo","limit":3}"""
        val symbols = (1..5).map { LspTools.Symbol("Item$it", "function", "app/Item$it.kt", it) }
        val sliceJson = normalizeSliceOk(sliceReceipt(args, "call-om", FixedBackend(symbols)).resultJson)
        val fastJson = parse(fastReceipt(args, "call-om", FixedBackend(symbols)).resultJson)
        assertEquals(3, sliceJson["returnedCount"]!!.jsonPrimitive.intOrNull)
        assertEquals(3, fastJson["returnedCount"]!!.jsonPrimitive.intOrNull)
        assertEquals(2, sliceJson["omittedCount"]!!.jsonPrimitive.intOrNull)
        assertEquals(2, fastJson["omittedCount"]!!.jsonPrimitive.intOrNull)
        assertTrue(sliceJson["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(fastJson["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun budgetShrinkLoopConsumesIdenticalBudget() {
        // 60 symbols × ~1.5 KB each far exceeds MAX_RESULT_CHARS (20_000):
        // the shrink loop must drop the identical tail on both paths.
        val symbols = (1..60).map { LspTools.Symbol("n".repeat(500) + it, "k".repeat(500), "p".repeat(490) + it, it) }
        val args = """{"query":"Foo","limit":200}"""
        val sliceResult = sliceReceipt(args, "call-shrink", FixedBackend(symbols))
        val fastResult = fastReceipt(args, "call-shrink", FixedBackend(symbols))
        val sliceJson = normalizeSliceOk(sliceResult.resultJson)
        val fastJson = parse(fastResult.resultJson)
        assertTrue(
            "shrunk slice receipt must fit the budget",
            sliceResult.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS,
        )
        assertTrue(
            "shrunk fast-lane receipt must fit the budget",
            fastResult.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS,
        )
        assertEquals("shrink cut points must agree", fastJson["returnedCount"], sliceJson["returnedCount"])
        assertEquals("shrink omittedCount must agree", fastJson["omittedCount"], sliceJson["omittedCount"])
        assertEquals(60 - sliceJson["returnedCount"]!!.jsonPrimitive.intOrNull!!, sliceJson["omittedCount"]!!.jsonPrimitive.intOrNull!!)
        assertTrue(sliceJson["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(fastJson["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("symbols array must agree byte-wise", fastJson["symbols"].toString(), sliceJson["symbols"].toString())
    }

    @Test fun boundaryShrinkLoopCutPointParity() {
        // Boundary corpus pinning the shrink cut points AT the edge where the
        // two mirrored paths used to diverge. Deterministic and ASCII-only
        // (fixed clock ⇒ fixed 13-digit clockMillis, fixed field lengths;
        // no machine/JDK-dependent serialization variance).
        //
        // Calibration (measured, see stage-6.4 review notes): 60 symbols ×
        // name="N".repeat(388). The receipt grows ≈460 bytes per symbol:
        //   base(42) = 19_521  ≤ reserve-adjusted budget (20_000 − 64 = 19_936)
        //   base(43) = 19_982  > reserve-adjusted budget, and
        //               base(43) + 28 (,"clockMillis": + 13-digit millis) = 20_010
        //             > raw MAX_RESULT_CHARS — the old fast lane kept 43 while
        //               the old slice dropped to 42.
        // Both paths must now select EXACTLY 42 symbols and agree
        // item-wise on the retained tail.
        val symbols = (1..60).map { benign("N".repeat(388), "function", "app/src/main/Boundary.kt", 7) }
        val args = """{"query":"Foo","limit":60}"""
        val sliceResult = sliceReceipt(args, "call-boundary", FixedBackend(symbols))
        val fastResult = fastReceipt(args, "call-boundary", FixedBackend(symbols))
        val sliceJson = normalizeSliceOk(sliceResult.resultJson)
        val fastJson = parse(fastResult.resultJson)
        assertFalse("boundary corpus must not trip the backstop (slice)", sliceResult.denied)
        assertFalse("boundary corpus must not trip the backstop (fast lane)", fastResult.denied)
        assertTrue(
            "shrunk slice receipt must fit the absolute cap",
            sliceResult.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS,
        )
        assertTrue(
            "shrunk fast-lane receipt must fit the absolute cap",
            fastResult.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS,
        )
        assertEquals("boundary cut point must agree (slice)", 42, sliceJson["returnedCount"]!!.jsonPrimitive.intOrNull)
        assertEquals("boundary cut point must agree (fast lane)", 42, fastJson["returnedCount"]!!.jsonPrimitive.intOrNull)
        assertEquals("boundary cut points must select identically", fastJson["returnedCount"], sliceJson["returnedCount"])
        assertEquals("boundary omittedCount must agree", fastJson["omittedCount"], sliceJson["omittedCount"])
        assertEquals(60 - 42, sliceJson["omittedCount"]!!.jsonPrimitive.intOrNull)
        assertEquals("symbols array must agree byte-wise at the boundary", fastJson["symbols"].toString(), sliceJson["symbols"].toString())
        assertTrue(sliceJson["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(fastJson["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun resultMetadataReserveExceedsClockMillisOverhead() {
        // Shared envelope budget only works if the reserve strictly covers the
        // field the envelope appends after the shrink decision:
        // `,"clockMillis":` (15 units) + a 13-digit epoch-millis value (13) = 28.
        assertTrue(
            "RESULT_METADATA_RESERVE must exceed the clockMillis wrapper + 13 digits",
            SafeToolSlice.RESULT_METADATA_RESERVE > ",\"clockMillis\":".length + 13,
        )
    }

    @Test fun singleSymbolCeilingPin() {
        // MAX_RESULT_CHARS backstop is intentionally unreachable with today's
        // constants: one max-size symbol (three 500-unit fields + markers +
        // escape doubling) stays far below 20_000. Pin that ceiling so a
        // cross-constant regression (MAX_STRING_CHARS vs MAX_RESULT_CHARS)
        // that breaks it fires here before any pipeline can EXEC_ERROR.
        val maxed = LspTools.Symbol("e\"n\t;\\".repeat(120).take(500), "k".repeat(500), "p".repeat(500), 1)
        val args = """{"query":"Foo"}"""
        val slice = sliceReceipt(args, "call-ceil", FixedBackend(listOf(maxed)))
        val fast = fastReceipt(args, "call-ceil", FixedBackend(listOf(maxed)))
        assertFalse("single max-size symbol must not trip the budget backstop (slice)", slice.denied)
        assertFalse("single max-size symbol must not trip the budget backstop (fast lane)", fast.denied)
        assertTrue(slice.resultJson.length < SafeToolSlice.MAX_RESULT_CHARS)
        assertTrue(fast.resultJson.length < SafeToolSlice.MAX_RESULT_CHARS)
        assertOkParity("single max-size symbol", args, "call-ceil2", FixedBackend(listOf(maxed)))
    }

    @Test fun blankQueryParityIsTheSharedDenialSubset() {
        // A whitespace-only `query` fails parseArgs on BOTH pipelines before
        // policy/backend: slice -> deny(SCHEMA_INVALID); fast lane ->
        // deniedResult("SCHEMA_INVALID"). Zero backend invocations both ways.
        val backend = CountingBackend()
        assertDeniedParity("blank query rejected before executor on both paths (SCHEMA_INVALID)", """{"query":"   "}""", "call-blank-query", backend, "SCHEMA_INVALID")
        assertEquals("blank query must be rejected before any backend invocation (both paths)", 0, backend.invocations)
    }

    @Test fun backendCrashParityIsTheSharedDenialSubset() {
        assertDeniedParity("backend throws (EXEC_ERROR both)", """{"query":"Foo"}""", "call-crash", CrashingBackend, "EXEC_ERROR")
        assertDeniedParity(
            "backend list projection throws (EXEC_ERROR both)",
            """{"query":"Foo"}""",
            "call-itercrash",
            IterationCrashingBackend,
            "EXEC_ERROR",
        )
    }

    @Test fun oversizedCallIdParity() {
        // A >500-unit call id takes the bounded path with the #-SHA-256
        // suffix on BOTH pipelines (the former FD port and the
        // slice's boundedRedactedId are the same single source now).
        for (id in listOf("x".repeat(600), "y".repeat(499) + "\uD834\uDF1E" + "z".repeat(200))) {
            val sliceJson = normalizeSliceOk(sliceReceipt("""{"query":"Foo"}""", id, FixedBackend(listOf(benign("LongId")))).resultJson)
            val fastJson = parse(fastReceipt("""{"query":"Foo"}""", id, FixedBackend(listOf(benign("LongId")))).resultJson)
            val sc = sliceJson["callId"]!!.jsonPrimitive.content
            val fc = fastJson["callId"]!!.jsonPrimitive.content
            assertEquals("bounded callId must agree", fc, sc)
            assertTrue("bounded callId must respect the cap", sc.length <= SafeToolSlice.MAX_STRING_CHARS)
            assertTrue("bounded callId must carry the #-suffix", Regex("#[0-9a-f]{32}$").containsMatchIn(sc))
        }
    }

    // ------------------------------------------------ adversarial argument corpus
    // Both parse sites (slice `parseArgs` ↔ fast-lane `parseArgs`) are still
    // hand-mirrored; this corpus asserts they AGREE — either both accept or both
    // deny with the same code — for adversarial inputs. The depth/format/NFKC
    // predicates they consult are the single shared implementations pinned
    // below in [sharedPredicateTable].

    private data class ArgCase(val name: String, val args: String, val accept: Boolean)

    private fun adversarialArgs(): List<ArgCase> {
        val deep32 = "{\"a\":" + "[".repeat(31) + "1" + "]".repeat(31) + ",\"query\":\"x\"}"
        val deep33 = "{\"a\":" + "[".repeat(32) + "1" + "]".repeat(32) + ",\"query\":\"x\"}"
        return listOf(
            ArgCase("benign object", """{"query":"Foo"}""", true),
            ArgCase("benign with limit", """{"query":"Foo","limit":7}""", true),
            ArgCase("benign with limit 200 (absolute cap)", """{"query":"Foo","limit":200}""", true),
            ArgCase("limit 201 clamps to 200", """{"query":"Foo","limit":201}""", true),
            ArgCase("limit 0 clamps to 1", """{"query":"Foo","limit":0}""", true),
            ArgCase("limit 1.5 is not an integer (reject)", """{"query":"Foo","limit":1.5}""", false),
            ArgCase("limit as string (reject)", """{"query":"Foo","limit":"5"}""", false),
            ArgCase("limit absurdly large clamps", """{"query":"Foo","limit":999999999}""", true),
            ArgCase("missing query (reject)", """{"limit":3}""", false),
            ArgCase("blank query (reject)", """{"query":"   "}""", false),
            ArgCase("empty query (reject)", """{"query":""}""", false),
            ArgCase("non-object root array (reject)", """[1,2,3]""", false),
            ArgCase("non-object root scalar (reject)", """42""", false),
            ArgCase("not JSON (reject)", "not-json", false),
            ArgCase("empty string (reject)", "", false),
            ArgCase("unterminated string (reject)", """{"query":"x"""", false),
            ArgCase("depth exactly 32 (accept)", deep32, true),
            ArgCase("depth exactly 33 (reject)", deep33, false),
            ArgCase("string contents do not count toward depth", """{"query":"[[[[[[[[[[[["}""", true),
            ArgCase("escaped quotes inside query string", """{"query":"a\"[[[b"}""", true),
            ArgCase("query with FORMAT code point U+200B (reject)", """{"query":"a\u200Bb"}""", false),
            ArgCase("query with control char (reject)", """{"query":"a\u0001b"}""", false),
            ArgCase("query with lone high surrogate (accept)", """{"query":"\uD800x"}""", true),
            ArgCase("query at MAX_INPUT_CHARS exactly (accept)", """{"query":"${"q".repeat(SafeToolSlice.MAX_INPUT_CHARS)}"}""", true),
            ArgCase("query at MAX_INPUT_CHARS + 1 (reject)", """{"query":"${"q".repeat(SafeToolSlice.MAX_INPUT_CHARS + 1)}"}""", false),
            ArgCase("pathPrefix normal relative (accept)", """{"query":"x","pathPrefix":"a/b"}""", true),
            ArgCase("pathPrefix empty string (accept)", """{"query":"x","pathPrefix":""}""", true),
            ArgCase("pathPrefix leading slash (reject)", """{"query":"x","pathPrefix":"/etc"}""", false),
            ArgCase("pathPrefix dotdot (reject)", """{"query":"x","pathPrefix":".."}""", false),
            ArgCase("pathPrefix embedded dotdot (reject)", """{"query":"x","pathPrefix":"a/../b"}""", false),
            ArgCase("pathPrefix backslash (reject)", """{"query":"x","pathPrefix":"a\\b"}""", false),
            ArgCase("pathPrefix percent-escape (reject)", """{"query":"x","pathPrefix":"%2e%2e"}""", false),
            ArgCase("pathPrefix colon scheme (reject)", """{"query":"x","pathPrefix":"file:/etc"}""", false),
            ArgCase("pathPrefix drive letter (reject)", """{"query":"x","pathPrefix":"c:/Users"}""", false),
            ArgCase("pathPrefix FORMAT U+200B (reject)", """{"query":"x","pathPrefix":"a\u200Bb"}""", false),
            ArgCase("pathPrefix supplementary FORMAT U+E0001 (reject)", """{"query":"x","pathPrefix":"a\uDB40\uDC01b"}""", false),
            ArgCase("pathPrefix NFKC-unstable fullwidth (reject)", """{"query":"x","pathPrefix":"ｓrc"}""", false),
            ArgCase("pathPrefix NFKC-stable lookalike (accept)", """{"query":"x","pathPrefix":"src/аctive"}""" /* Cyrillic small letter a U+0430: NFKC-stable */, true),
            ArgCase("NUL in pathPrefix (reject)", """{"query":"x","pathPrefix":"a\u0000b"}""", false),
            ArgCase("gross args over MAX_GROSS_ARGS_CHARS (reject before parse)", """{"query":"${"q".repeat(SafeToolSlice.MAX_GROSS_ARGS_CHARS)}"}""", false),
            ArgCase("duplicate query keys (last wins, both accept)", """{"query":"a","query":"b"}""", true),
            ArgCase("unicode escape in value (accept)", """{"query":"a\u0041b"}""", true),
        )
    }

    @Test fun adversarialArgumentsBothParseSitesAgree() {
        for (c in adversarialArgs()) {
            val sliceResult = sliceReceipt(c.args, "arg-${c.name.hashCode()}", FixedBackend(listOf(benign("ParseParity"))))
            val fastResult = fastReceipt(c.args, "arg-${c.name.hashCode()}", FixedBackend(listOf(benign("ParseParity"))))
            val sliceDenied = sliceResult.denied
            val fastDenied = fastResult.denied
            assertEquals(
                "${c.name}: slice and fast-lane parse sites must agree on accept/deny (slice denied=$sliceDenied, fast denied=$fastDenied)",
                sliceDenied,
                fastDenied,
            )
            assertEquals("${c.name}: expected accept=${c.accept}", c.accept, !sliceDenied)
            if (sliceDenied) {
                assertEquals("${c.name}: both must deny SCHEMA_INVALID", "SCHEMA_INVALID", sliceResult.denyCode)
                assertEquals("${c.name}: both must deny SCHEMA_INVALID", "SCHEMA_INVALID", fastResult.denyCode)
            } else {
                // Accepted: both Ok receipts must be byte-/semantic-identical too.
                val sliceJson = normalizeSliceOk(sliceResult.resultJson)
                val fastJson = parse(fastResult.resultJson)
                assertEquals("${c.name}: receipts must agree after normalization", fastJson, sliceJson)
                assertEquals(
                    "${c.name}: symbols array must agree byte-wise",
                    fastJson["symbols"].toString(),
                    sliceJson["symbols"].toString(),
                )
            }
        }
    }

    // --------------------------------------- shared predicate regression table
    // jsonNestingDepth / containsFormatCodePoint / isSafeInput are now ONE
    // implementation shared by both call sites; this table pins the boundary
    // behavior as a regression guard (and documents the parity contract).

    private data class PredicateCase(
        val input: String,
        val expectedDepth: Int? = null,
        val expectedHasFormat: Boolean? = null,
        val expectedSafeInput: Boolean? = null,
    )

    @Test fun sharedPredicateTable() {
        val cases = listOf(
            PredicateCase("", expectedDepth = 0, expectedHasFormat = false, expectedSafeInput = true),
            PredicateCase("normal text", expectedDepth = 0, expectedHasFormat = false, expectedSafeInput = true),
            PredicateCase("/absolute", expectedSafeInput = false),
            PredicateCase("a\u200Bb", expectedHasFormat = true, expectedSafeInput = false), // ZWSP
            PredicateCase("a\u202Eb", expectedHasFormat = true, expectedSafeInput = false), // RLO
            PredicateCase("a\uFEFFb", expectedHasFormat = true, expectedSafeInput = false), // BOM
            PredicateCase("a\uDB40\uDC01b", expectedHasFormat = true, expectedSafeInput = false), // U+E0001 language tag (supplementary FORMAT)
            PredicateCase("a\uDB40\uDC20b", expectedHasFormat = true, expectedSafeInput = false), // U+E0020 tag space (supplementary FORMAT)
            PredicateCase("a\uDB40\uDC7Fb", expectedHasFormat = true, expectedSafeInput = false), // U+E007F cancel tag
            PredicateCase("a..b", expectedSafeInput = false),
            PredicateCase("a\\b", expectedSafeInput = false),
            PredicateCase("a%2fb", expectedSafeInput = false),
            PredicateCase("file:/etc", expectedSafeInput = false),
            PredicateCase("c:/Users", expectedSafeInput = false),
            PredicateCase("a\u0000b", expectedSafeInput = false),
            PredicateCase("\uD800lone high surrogate", expectedDepth = 0, expectedHasFormat = false),
            PredicateCase("\uDC00lone low surrogate", expectedDepth = 0, expectedHasFormat = false),
            PredicateCase("a\uD800b\uDC00c", expectedHasFormat = false, expectedSafeInput = true), // unpaired halves: not FORMAT
            PredicateCase("[".repeat(31), expectedDepth = 31, expectedHasFormat = false),
            PredicateCase("[".repeat(32), expectedDepth = 32, expectedHasFormat = false),
            PredicateCase("[".repeat(33), expectedDepth = 33, expectedHasFormat = false),
            PredicateCase("[".repeat(32) + "\"x\"" + "]".repeat(32), expectedDepth = 32),
            PredicateCase("{\"a\":[[[1]]],\"b\":\"escaped \\\" { \\\"\"}", expectedDepth = 4),
            PredicateCase("{", expectedDepth = 1),
            PredicateCase("[[[", expectedDepth = 3),
            PredicateCase("[]]]", expectedDepth = 1),
            PredicateCase("ａb\u200B", expectedHasFormat = true), // NFKC-changing + FORMAT: format gate fires first
            PredicateCase("ａb", expectedSafeInput = true),      // NFKC stability is NOT checked by isSafeInput (parse layer owns it)
        )
        for (c in cases) {
            c.expectedDepth?.let { assertEquals("depth of ${c.input.take(20)}…", it, SafeToolSlice.jsonNestingDepth(c.input)) }
            c.expectedHasFormat?.let { assertEquals("format of ${c.input.take(20)}…", it, SafeToolSlice.containsFormatCodePoint(c.input)) }
            c.expectedSafeInput?.let { assertEquals("safeInput of ${c.input.take(20)}…", it, SafeToolSlice.isSafeInput(c.input)) }
        }
    }
}
