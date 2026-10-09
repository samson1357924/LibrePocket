package dev.librepocket.models

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsDevSnapshotFetchOutcomeTest {
    @Test
    fun validDirectoryReportsRemoteSourceWithoutFallbackReason(): Unit {
        runBlocking {
            val outcome = ModelsDevSnapshot.fetchSnapshotOutcome(
                nowMs = NOW,
                fetcher = { DIRECTORY_BODY },
            )

            assertEquals(ModelsDevSnapshot.SnapshotSource.REMOTE_DIRECTORY, outcome.source)
            assertNull(outcome.fallbackReason)
            assertNull(outcome.httpStatusCode)
            assertEquals("wire-model", outcome.snapshot.models.single().wireId)
        }
    }

    @Test
    fun typedHttpFailureReportsBundledSourceAndOnlyStatusMetadata(): Unit {
        runBlocking {
            val outcome = ModelsDevSnapshot.fetchSnapshotOutcome(
                nowMs = NOW,
                fetcher = {
                    throw ModelsDevSnapshot.FetchException(
                        ModelsDevSnapshot.SnapshotFallbackReason.HTTP_STATUS,
                        httpStatusCode = 503,
                    )
                },
            )

            assertEquals(ModelsDevSnapshot.SnapshotSource.BUNDLED, outcome.source)
            assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.HTTP_STATUS, outcome.fallbackReason)
            assertEquals(503, outcome.httpStatusCode)
            assertEquals(ModelsDevSnapshot.bundledSnapshot(NOW), outcome.snapshot)
            assertFalse(outcome.toString().contains("https://"))
        }
    }

    @Test
    fun invalidUrlDoesNotInvokeFetcherAndHasStableReason(): Unit {
        runBlocking {
            var fetched = false
            val outcome = ModelsDevSnapshot.fetchSnapshotOutcome(
                url = "http://outside.example/models.json",
                nowMs = NOW,
                fetcher = { fetched = true; DIRECTORY_BODY },
            )

            assertFalse(fetched)
            assertEquals(ModelsDevSnapshot.SnapshotSource.BUNDLED, outcome.source)
            assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.INVALID_URL, outcome.fallbackReason)
            assertNull(outcome.httpStatusCode)
        }
    }

    @Test
    fun malformedJsonShapeAndEmptyDirectoryRemainDistinct(): Unit {
        runBlocking {
            val malformed = ModelsDevSnapshot.fetchSnapshotOutcome(nowMs = NOW, fetcher = { "not json" })
            val invalidShape = ModelsDevSnapshot.fetchSnapshotOutcome(nowMs = NOW, fetcher = { """{"models":{}}""" })
            val emptyDirectory = ModelsDevSnapshot.fetchSnapshotOutcome(nowMs = NOW, fetcher = { """{"models":[]}""" })
            val emptyBody = ModelsDevSnapshot.fetchSnapshotOutcome(nowMs = NOW, fetcher = { "" })

            assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.INVALID_JSON, malformed.fallbackReason)
            assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.INVALID_SHAPE, invalidShape.fallbackReason)
            assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.EMPTY_DIRECTORY, emptyDirectory.fallbackReason)
            assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.EMPTY_BODY, emptyBody.fallbackReason)
        }
    }

    @Test
    fun genericFailureMapsToSafeNetworkReasonWithoutPreservingExceptionText(): Unit {
        runBlocking {
            val sentinel = "secret-url-or-server-body-sentinel"
            val outcome = ModelsDevSnapshot.fetchSnapshotOutcome(
                nowMs = NOW,
                fetcher = { throw IOException(sentinel) },
            )

            assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.NETWORK_ERROR, outcome.fallbackReason)
            assertFalse(outcome.toString().contains(sentinel))
        }
    }

    @Test
    fun callerCancellationPropagatesInsteadOfBecomingBundledFallback(): Unit {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                ModelsDevSnapshot.fetchSnapshotOutcome(
                    nowMs = NOW,
                    fetcher = { throw CancellationException("synthetic cancellation") },
                )
            }
        }
    }

    @Test
    fun constructorEnforcesSourceReasonAndStatusInvariants(): Unit {
        assertThrows(IllegalArgumentException::class.java) {
            ModelsDevSnapshot.SnapshotFetchOutcome(
                snapshot = ModelsDevSnapshot.bundledSnapshot(NOW),
                source = ModelsDevSnapshot.SnapshotSource.BUNDLED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelsDevSnapshot.SnapshotFetchOutcome(
                snapshot = ModelsDevSnapshot.bundledSnapshot(NOW),
                source = ModelsDevSnapshot.SnapshotSource.BUNDLED,
                fallbackReason = ModelsDevSnapshot.SnapshotFallbackReason.HTTP_STATUS,
            )
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DIRECTORY_BODY = """{"openai":{"models":{"wire-model":{"tool_call":true}}}}"""
    }
}
