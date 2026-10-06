package dev.librepocket.models

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun fixtureParsesToThreeCandidates() {
        val snapshot = ModelsDevSnapshot.parse(fixtureBody(), nowMs = 1_700_000_000_000L)
        assertEquals(3, snapshot.models.size)
        assertEquals("openai/gpt-4o-mini", snapshot.models[0].id)
        assertEquals(false, snapshot.models[0].reasoning)
        assertEquals("anthropic/claude-haiku-4-5", snapshot.models[1].id)
        assertEquals(true, snapshot.models[1].reasoning)
        assertEquals("google/gemini-2-flash", snapshot.models[2].id)
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
    fun mergeUnionsLiveAndSnapshot() {
        val snapshot = ModelsDevSnapshot.parse(fixtureBody(), nowMs = 1_700_000_000_000L)
        val merged = ModelsDevSnapshot.merge(listOf("custom/local"), snapshot)
        assertEquals(
            listOf(
                "custom/local",
                "openai/gpt-4o-mini",
                "anthropic/claude-haiku-4-5",
                "google/gemini-2-flash",
            ),
            merged,
        )
    }
}
