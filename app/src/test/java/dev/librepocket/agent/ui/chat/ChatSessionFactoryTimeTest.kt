package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FactoryFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seenRequests: MutableList<ChatRequest> = java.util.Collections.synchronizedList(mutableListOf())

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        seenRequests.add(request)
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/** Manually advanceable clock; withZone shares the same instant reference. */
private class ManualClock(initial: Instant, private val zone: ZoneId = ZoneId.of("UTC")) : Clock() {
    @Volatile var now: Instant = initial
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock {
        val parent = this
        return object : Clock() {
            override fun getZone(): ZoneId = zone
            override fun withZone(z: ZoneId): Clock = parent.withZone(z)
            override fun instant(): Instant = parent.now
        }
    }
    override fun instant(): Instant = now
    fun advanceBy(d: Duration) {
        now = now.plus(d)
    }
}

class ChatSessionFactoryTimeTest {
    private fun sampleEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun ok(handler: (ChatRequest) -> Flow<StreamEvent> = {
        flow {
            emit(StreamEvent.TextDelta(0, 0, "done"))
            emit(StreamEvent.Done("stop"))
        }
    }) = FactoryFakeProvider(handler)

    private fun testVaultSource(): VaultSource = VaultSource {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "test-key-12345678".toCharArray())
        vault
    }

    private fun lastUserText(req: ChatRequest): String? =
        req.messages.lastOrNull { it.role == "user" }?.text

    private fun sessionLine(text: String?): String? =
        text?.lines()?.firstOrNull { it.startsWith("- Session started:") }

    private fun currentLine(text: String?): String? =
        text?.lines()?.firstOrNull { it.startsWith("- Current time:") }

    private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    @Test fun createUsesRoomCreatedAtForSessionStarted() = runBlocking {
        val roomCreatedAt = Instant.parse("2026-01-14T12:00:00Z").toEpochMilli()
        val transcripts = FakeSessionStore().apply { now = roomCreatedAt }
        val fixedClock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC"))
        val fake = ok()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = fixedClock,
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        val created = factory.create(sampleEndpoint(), "hi")
        created.session.send("hi")

        val sent = lastUserText(fake.seenRequests.single())!!
        // Session started renders the Room createdAt wall time, not the wall clock.
        val session = sessionLine(sent)
        val current = currentLine(sent)
        assertTrue(sent.contains("Runtime time context:"))
        assertTrue(session != null && session.contains("2026-01-14"))
        assertTrue(session!!.contains("12:00:00"))
        assertTrue(session.contains("UTC"))
        assertTrue(current != null && current.contains("2026-01-15"))
        // Full wall-time rendering (weekday + offset) pins the correct instant.
        assertTrue(session.contains("2026-01-14 Wed 12:00:00 UTC (UTC+00:00)"))
        assertTrue(current!!.contains("2026-01-15 Thu 12:00:00 UTC (UTC+00:00)"))
    }

    @Test fun openReusesRoomCreatedAtNotNow() = runBlocking {
        val roomCreatedAt = Instant.parse("2026-01-14T12:00:00Z").toEpochMilli()
        val transcripts = FakeSessionStore().apply { now = roomCreatedAt }
        val clock = ManualClock(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC"))
        val fake = ok()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = clock,
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        val created = factory.create(sampleEndpoint(), "hi")
        val sid = created.sessionId!!
        created.session.send("first")
        val firstSession = sessionLine(lastUserText(fake.seenRequests.single()))

        // Advance the wall clock 5 days; resume must keep the original Room time.
        clock.advanceBy(Duration.ofDays(5))
        val opened = factory.open(sampleEndpoint(), sid)
        opened.session.send("second")

        assertEquals(2, fake.seenRequests.size)
        val secondSent = lastUserText(fake.seenRequests[1])!!
        val secondSession = sessionLine(secondSent)
        val secondCurrent = currentLine(secondSent)
        assertEquals(firstSession, secondSession)
        assertTrue(secondSession!!.contains("2026-01-14"))
        // 2026-01-15 + 5 days = 2026-01-20.
        assertTrue(secondCurrent!!.contains("2026-01-20"))
        assertFalse(secondSession!!.contains("2026-01-20"))
    }

    @Test fun storelessOmitsSessionStarted() = runBlocking {
        val fake = ok()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = null,
            clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        val created = factory.create(sampleEndpoint(), "hi")
        assertNull(created.sessionId)
        created.session.send("hi")

        val sent = lastUserText(fake.seenRequests.single())!!
        assertTrue(sent.contains("Runtime time context:"))
        assertTrue(currentLine(sent)!!.contains("2026-01-15"))
        assertFalse(sent.contains("Session started:"))
        assertNull(sessionLine(sent))
    }

    @Test fun invalidCreatedAtOmitsButStillSends() = runBlocking {
        for (bad in listOf(0L, -1000L)) {
            val transcripts = FakeSessionStore().apply { now = bad }
            val fake = ok()
            val factory = ChatSessionFactory(
                policy = InMemoryPolicyStore(),
                vaultSource = testVaultSource(),
                buildProvider = { _, _ -> fake },
                sessionStores = object : SessionStoreSource {
                    override suspend fun store() = transcripts
                },
                clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
                userTimezone = "UTC",
                systemZone = { ZoneId.of("UTC") },
            )
            val created = factory.create(sampleEndpoint(), "hi")
            // Must not throw; the turn still sends.
            created.session.send("hi")
            val sent = lastUserText(fake.seenRequests.single())!!
            assertFalse("createdAt=$bad must omit Session started", sent.contains("Session started:"))
            assertTrue(sent.contains("Runtime time context:"))
        }
    }

    @Test fun openWithInvalidCreatedAtOmitsButStillSends() = runBlocking {
        val transcripts = FakeSessionStore()
        val fake = ok()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        val created = factory.create(sampleEndpoint(), "hi")
        val sid = created.sessionId!!
        // Corrupt the stored header after creation; resume must omit, not throw.
        val old = transcripts.metas[sid]!!
        transcripts.metas[sid] = old.copy(createdAt = 0L)
        val opened = factory.open(sampleEndpoint(), sid)
        opened.session.send("hi")
        val sent = lastUserText(fake.seenRequests.single())!!
        assertFalse(sent.contains("Session started:"))
        assertTrue(sent.contains("Runtime time context:"))
    }

    @Test fun createWithThrowingGetSessionOmitsButStillSends() = runBlocking {
        val backing = FakeSessionStore()
        val throwing = object : FakeSessionStore() {
            override suspend fun getSession(sessionId: String) = error("db down")
        }
        // createSession still works; only the sessionStart lookup throws.
        val fake = ok()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                // Delegate creation to the throwing store's own map so ids stay consistent.
                override suspend fun store() = throwing
            },
            clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        // Must not throw (fail-closed to omit); the turn still sends.
        val created = factory.create(sampleEndpoint(), "hi")
        created.session.send("hi")
        val sent = lastUserText(fake.seenRequests.single())!!
        assertFalse(sent.contains("Session started:"))
        assertTrue(sent.contains("Runtime time context:"))
        assertTrue(backing.metas.isEmpty())
    }

    @Test fun openUnknownStillThrowsUnknownSession() = runBlocking {
        val transcripts = FakeSessionStore()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> ok() },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        try {
            factory.open(sampleEndpoint(), "no-such-id")
            fail("expected UNKNOWN_SESSION")
        } catch (e: IllegalArgumentException) {
            assertEquals("UNKNOWN_SESSION", e.message)
        }

        val throwing = object : FakeSessionStore() {
            override suspend fun getSession(sessionId: String): dev.librepocket.session.SessionMeta? =
                error("db down")
        }
        val throwingFactory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> ok() },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = throwing
            },
            clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        try {
            throwingFactory.open(sampleEndpoint(), "any-id")
            fail("expected UNKNOWN_SESSION for throwing getSession")
        } catch (e: IllegalArgumentException) {
            assertEquals("UNKNOWN_SESSION", e.message)
        }
    }

    @Test fun advancingClockUpdatesCurrentButNotSessionStarted() = runBlocking {
        val roomCreatedAt = Instant.parse("2026-01-14T12:00:00Z").toEpochMilli()
        val transcripts = FakeSessionStore().apply { now = roomCreatedAt }
        val clock = ManualClock(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC"))
        val fake = ok()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = clock,
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        // Same live session reused (no re-stamp, mirrors ChatViewModel.ensureSession).
        val created = factory.create(sampleEndpoint(), "hi")
        created.session.send("one")
        clock.advanceBy(Duration.ofHours(1))
        created.session.send("two")

        assertEquals(2, fake.seenRequests.size)
        val first = lastUserText(fake.seenRequests[0])!!
        val second = lastUserText(fake.seenRequests[1])!!
        assertTrue(first.startsWith("one\n\nRuntime time context:"))
        assertTrue(second.startsWith("two\n\nRuntime time context:"))
        // Current timestamps move with the clock…
        assertTrue(currentLine(first)!!.contains("12:00:00"))
        assertTrue(currentLine(second)!!.contains("13:00:00"))
        // …while Session started stays pinned to the Room createdAt.
        assertEquals(sessionLine(first), sessionLine(second))
        assertTrue(sessionLine(second)!!.contains("2026-01-14"))
    }

    @Test fun retryPicksUpAdvancedClock() = runBlocking {
        val roomCreatedAt = Instant.parse("2026-01-14T12:00:00Z").toEpochMilli()
        val transcripts = FakeSessionStore().apply { now = roomCreatedAt }
        val clock = ManualClock(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC"))
        var calls = 0
        val fake = FactoryFakeProvider { _ ->
            flow {
                if (calls++ == 0) {
                    // Advance between attempts: the retry request must render the new time.
                    clock.advanceBy(Duration.ofHours(1))
                    emit(StreamEvent.Failed("boom", retryable = true))
                } else {
                    emit(StreamEvent.TextDelta(0, 0, "recovered"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = clock,
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        val created = factory.create(sampleEndpoint(), "hi")
        // Default retry budget sleeps 2s before the retry; wait generously.
        withTimeout(15000) {
            created.session.send("hi")
        }

        assertEquals(2, fake.seenRequests.size)
        val first = lastUserText(fake.seenRequests[0])!!
        val second = lastUserText(fake.seenRequests[1])!!
        assertTrue(currentLine(first)!!.contains("12:00:00"))
        assertTrue(currentLine(second)!!.contains("13:00:00"))
        assertEquals(sessionLine(first), sessionLine(second))
        assertTrue(sessionLine(second)!!.contains("2026-01-14"))
    }

    @Test fun steerFollowUpPicksUpAdvancedClock() = runBlocking {
        val roomCreatedAt = Instant.parse("2026-01-14T12:00:00Z").toEpochMilli()
        val transcripts = FakeSessionStore().apply { now = roomCreatedAt }
        val clock = ManualClock(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fake = FactoryFakeProvider { input ->
            flow {
                val raw = lastUserText(input)?.substringBefore("\n\n")
                if (raw == "first") {
                    emit(StreamEvent.TextDelta(0, 0, "A"))
                    gate.await()
                    emit(StreamEvent.Done("stop"))
                } else {
                    emit(StreamEvent.TextDelta(0, 0, "B-$raw"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = testVaultSource(),
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = clock,
            userTimezone = "UTC",
            systemZone = { ZoneId.of("UTC") },
        )
        val created = factory.create(sampleEndpoint(), "hi")
        val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val first = outer.async { created.session.send("first") }
            awaitTrue { fake.seenRequests.size == 1 }
            created.session.steer("follow-1")
            // Flip the clock before the queued follow-up builds its request.
            clock.advanceBy(Duration.ofHours(2))
            gate.complete(Unit)
            withTimeout(10000) { first.join() }
            awaitTrue { fake.seenRequests.size == 2 }

            val firstReq = lastUserText(fake.seenRequests[0])!!
            val followReq = lastUserText(fake.seenRequests[1])!!
            assertTrue(firstReq.startsWith("first\n\nRuntime time context:"))
            assertTrue(followReq.startsWith("follow-1\n\nRuntime time context:"))
            assertTrue(currentLine(firstReq)!!.contains("12:00:00"))
            assertTrue(currentLine(followReq)!!.contains("14:00:00"))
            assertEquals(sessionLine(firstReq), sessionLine(followReq))
            assertTrue(sessionLine(followReq)!!.contains("2026-01-14"))
        } finally {
            outer.cancel()
        }
    }
}
