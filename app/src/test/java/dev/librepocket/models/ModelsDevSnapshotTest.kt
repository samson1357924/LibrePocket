package dev.librepocket.models

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * models.dev snapshot tests (SPEC §1.2 / §10.3).
 *
 * - Offline fixture parses to the expected 3-model candidate list.
 * - Non-https URLs, oversize bodies, and fetcher failures all fall back to
 *   the bundled snapshot (the picker works fully offline).
 * - The directory read carries no key: a real HTTP fetch against a local
 *   [MockWebServer] (no external network) is recorded with no
 *   `Authorization` header.
 */
class ModelsDevSnapshotTest {

    private fun fixtureBody(): String =
        javaClass.classLoader!!.getResourceAsStream("models-snapshot-min.json")!!.readBytes()
            .toString(Charsets.UTF_8)

    private fun nestedDirectoryFixtureBody(): String =
        javaClass.classLoader!!.getResourceAsStream("models-dev-directory-2providers.json")!!.readBytes()
            .toString(Charsets.UTF_8)

    @Test
    fun fixtureParsesToThreeCandidates() {
        val snapshot = ModelsDevSnapshot.parse(fixtureBody(), nowMs = 1_700_000_000_000L)
        assertEquals(3, snapshot.models.size)
        assertEquals("openai", snapshot.models[0].providerId)
        assertEquals("gpt-4o-mini", snapshot.models[0].wireId)
        assertEquals(false, snapshot.models[0].reasoning)
        assertEquals(true, snapshot.models[0].toolCalls)
        assertEquals("anthropic", snapshot.models[1].providerId)
        assertEquals("claude-haiku-4-5", snapshot.models[1].wireId)
        assertEquals(true, snapshot.models[1].reasoning)
        assertEquals("google", snapshot.models[2].providerId)
        assertEquals("gemini-2-flash", snapshot.models[2].wireId)
        assertEquals(true, snapshot.models[2].toolCalls)
    }

    @Test
    fun nestedProviderModelDictionariesParseWithSingularToolCallCapabilities() {
        val snapshot = ModelsDevSnapshot.parse(nestedDirectoryFixtureBody(), nowMs = 1_700_000_000_000L)

        assertEquals(
            listOf(
                ModelsDevSnapshot.ModelEntry("openai", "wire-openai-a", reasoning = false, toolCalls = true),
                ModelsDevSnapshot.ModelEntry("openai", "wire-openai-b", reasoning = true, toolCalls = false),
                ModelsDevSnapshot.ModelEntry("anthropic", "wire-anthropic-a", reasoning = true, toolCalls = false),
                ModelsDevSnapshot.ModelEntry("anthropic", "wire-anthropic-b", reasoning = false, toolCalls = true),
            ),
            snapshot.models,
        )
    }

    @Test
    fun nestedWireIdIsTakenVerbatimFromDictionaryKey() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"openai":{"models":{"vendor/model":{"id":"ignored-metadata-id","tool_call":true}}}}""",
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(
            ModelsDevSnapshot.ModelEntry("openai", "vendor/model", toolCalls = true),
            snapshot.models.single(),
        )
    }

    @Test
    fun singularToolCallAliasSetsCapability() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"models":[{"id":"openai/singular-tool","reasoning":true,"tool_call":true}]}""",
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(
            ModelsDevSnapshot.ModelEntry("openai", "singular-tool", reasoning = true, toolCalls = true),
            snapshot.models.single(),
        )
    }

    @Test
    fun singularFalseTakesPrecedenceOverPluralToolCallAliases() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"models":[{"id":"openai/contradictory","tool_call":false,"tool_calls":true,"tools":true}]}""",
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(false, snapshot.models.single().toolCalls)
    }

    @Test
    fun legacyQualifiedIdsSplitOnlyAtFirstSlashAndUnqualifiedIdsStayUnscoped() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"models":[
                |{"id":"openrouter/vendor/model","tools":true},
                |{"id":"freeform-model","tool_calls":true}
                |]}""".trimMargin(),
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(
            listOf(
                ModelsDevSnapshot.ModelEntry("openrouter", "vendor/model", toolCalls = true),
                ModelsDevSnapshot.ModelEntry(null, "freeform-model", toolCalls = true),
            ),
            snapshot.models,
        )
        assertEquals(
            listOf("vendor/model"),
            ModelsDevSnapshot.mergeForProvider(emptyList(), snapshot, "openrouter"),
        )
    }

    @Test
    fun nonHttpsDirectoryUrlFallsBackToBundled() = runBlocking {
        val snapshot = ModelsDevSnapshot.fetchSnapshot(
            url = "http://api.example.com/models.json",
            nowMs = 1_700_000_000_000L,
            fetcher = { error("must not fetch non-https") },
        )
        assertEquals(ModelsDevSnapshot.bundledSnapshot(1_700_000_000_000L), snapshot)
    }

    @Test
    fun oversizeBodyFallsBackToBundled() = runBlocking {
        val big = "x".repeat(ModelsDevSnapshot.MAX_SNAPSHOT_BYTES + 1)
        val snapshot = ModelsDevSnapshot.fetchSnapshot(
            url = ModelsDevSnapshot.DEFAULT_DIRECTORY_URL,
            nowMs = 1_700_000_000_000L,
            fetcher = { big },
        )
        assertEquals(ModelsDevSnapshot.bundledSnapshot(1_700_000_000_000L), snapshot)
    }

    @Test
    fun fetcherFailureFallsBackToBundled() = runBlocking {
        val snapshot = ModelsDevSnapshot.fetchSnapshot(
            url = ModelsDevSnapshot.DEFAULT_DIRECTORY_URL,
            nowMs = 1_700_000_000_000L,
            fetcher = { throw java.io.IOException("offline") },
        )
        assertEquals(ModelsDevSnapshot.bundledSnapshot(1_700_000_000_000L), snapshot)
    }

    @Test
    fun malformedBodyFallsBackToBundled() = runBlocking {
        val snapshot = ModelsDevSnapshot.fetchSnapshot(
            url = ModelsDevSnapshot.DEFAULT_DIRECTORY_URL,
            nowMs = 1_700_000_000_000L,
            fetcher = { "not json at all" },
        )
        assertEquals(ModelsDevSnapshot.bundledSnapshot(1_700_000_000_000L), snapshot)
    }

    @Test
    fun directoryFetchSendsNoAuthorizationHeader() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(fixtureBody()))
            val client = OkHttpClient()
            val fetched = runBlocking {
                ModelsDevSnapshot.fetchSnapshot(
                    // Loopback http is the SPEC §7.2 dev exception; the point is
                    // the request carries no key material either way.
                    url = server.url("/api.json").toString(),
                    nowMs = 1_700_000_000_000L,
                    fetcher = { url ->
                        // Product call-site contract: plain GET, no auth headers.
                        val request = Request.Builder().url(url).get().build()
                        client.newCall(request).execute().use { resp ->
                            check(resp.isSuccessful) { "HTTP ${resp.code}" }
                            resp.body!!.string()
                        }
                    },
                )
            }
            assertEquals(3, fetched.models.size)

            val recorded = server.takeRequest()
            assertNull(
                "directory read must not carry a key",
                recorded.getHeader("Authorization"),
            )
            assertTrue(recorded.path!!.endsWith("/api.json"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mergeForProviderAddsOnlyMatchingWireIds() {
        val snapshot = ModelsDevSnapshot.parse(fixtureBody(), nowMs = 1_700_000_000_000L)
        val merged = ModelsDevSnapshot.mergeForProvider(listOf("live-model"), snapshot, "openai")
        assertEquals(
            listOf("live-model", "gpt-4o-mini"),
            merged,
        )
        assertEquals(
            listOf("live-model"),
            ModelsDevSnapshot.mergeForProvider(listOf("live-model"), snapshot, null),
        )
    }

    private fun assertSnapshotShape(body: String) {
        val e = assertThrows(ModelsDevSnapshot.SnapshotException::class.java) {
            ModelsDevSnapshot.parse(body, nowMs = 1_700_000_000_000L)
        }
        assertEquals("MODELS_SNAPSHOT_SHAPE", e.message)
    }

    @Test
    fun mistypedRootModelsKeyThrowsShapeInsteadOfProviderReinterpret() {
        assertSnapshotShape("""{"models":"x"}""")
        assertSnapshotShape("""{"models":42}""")
        assertSnapshotShape("""{"models":{"models":{"m":{}}}}""")
        assertSnapshotShape("""{"models":true}""")
        assertSnapshotShape("""{"models":null}""")
    }

    @Test
    fun mistypedRootModelsKeyWithSiblingDirectoryStillThrowsShape() {
        assertSnapshotShape("""{"models":"x","openai":{"models":{"m":{}}}}""")
    }

    @Test
    fun allInvalidProviderEntriesThrowShape() {
        // Provider value not an object.
        assertSnapshotShape("""{"openai":"x"}""")
        // Provider object without a models dictionary.
        assertSnapshotShape("""{"openai":{}}""")
        // Models value not an object.
        assertSnapshotShape("""{"openai":{"models":"x"}}""")
        assertSnapshotShape("""{"openai":{"models":[]}}""")
        // Every entry invalid in a different way.
        assertSnapshotShape("""{"a":"x","b":{},"c":{"models":"x"}}""")
    }

    @Test
    fun mixedValidAndInvalidProviderEntriesParsePartial() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"openai":{"models":{"good":{}}},"bad":"x","empty":{},"badmodels":{"models":[]}}""",
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(
            listOf(ModelsDevSnapshot.ModelEntry("openai", "good")),
            snapshot.models,
        )
    }

    @Test
    fun nonObjectModelValuesAreSkipped() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"openai":{"models":{"a":"x","b":null,"c":[],"d":{}}}}""",
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(
            listOf(ModelsDevSnapshot.ModelEntry("openai", "d")),
            snapshot.models,
        )
    }

    @Test
    fun emptyShapeSemantics() {
        assertSnapshotShape("{}")
        assertSnapshotShape("""{"openai":{}}""")
        assertEquals(
            0,
            ModelsDevSnapshot.parse(
                """{"openai":{"models":{}}}""",
                nowMs = 1_700_000_000_000L,
            ).models.size,
        )
        assertEquals(
            0,
            ModelsDevSnapshot.parse(
                """{"models":[]}""",
                nowMs = 1_700_000_000_000L,
            ).models.size,
        )
        assertEquals(
            0,
            ModelsDevSnapshot.parse("[]", nowMs = 1_700_000_000_000L).models.size,
        )
    }

    @Test
    fun nonObjectModelNumberAndBooleanValuesAreSkipped() {
        // Complements nonObjectModelValuesAreSkipped (string/null/array):
        // numbers and booleans are skipped the same way.
        val snapshot = ModelsDevSnapshot.parse(
            """{"openai":{"models":{"n":42,"b":true,"ok":{}}}}""",
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(
            listOf(ModelsDevSnapshot.ModelEntry("openai", "ok")),
            snapshot.models,
        )
    }

    @Test
    fun extraTopLevelKeyWithModelsObjectIsTreatedAsProvider() {
        // Known tolerance (characterization, not a contract): any top-level
        // object with a "models" dictionary is read as a provider directory,
        // so a non-provider metadata key with that shape is not ignored.
        val snapshot = ModelsDevSnapshot.parse(
            """{"openai":{"models":{"m":{}}},"meta":{"models":{"e":{}}}}""",
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(
            listOf(
                ModelsDevSnapshot.ModelEntry("openai", "m"),
                ModelsDevSnapshot.ModelEntry("meta", "e"),
            ),
            snapshot.models,
        )
    }

    @Test
    fun legacyModelsArrayTakesPrecedenceOverProviderDirectory() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"models":[{"id":"openai/legacy","tool_call":true}],""" +
                """"openai":{"models":{"nested":{"tool_call":true}}}}""",
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(
            listOf(ModelsDevSnapshot.ModelEntry("openai", "legacy", toolCalls = true)),
            snapshot.models,
        )
    }

    @Test
    fun nonBooleanCapabilityValuesAreReadAsFalse() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"openai":{"models":{""" +
                """"o":{"tool_call":{}},"s":{"tool_call":"yes"},"n":{"tool_call":1},""" +
                """"r":{"reasoning":"yes","tool_call":true}}}}""",
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(
            listOf(
                ModelsDevSnapshot.ModelEntry("openai", "o"),
                ModelsDevSnapshot.ModelEntry("openai", "s"),
                ModelsDevSnapshot.ModelEntry("openai", "n"),
                ModelsDevSnapshot.ModelEntry("openai", "r", toolCalls = true),
            ),
            snapshot.models,
        )
    }
}
