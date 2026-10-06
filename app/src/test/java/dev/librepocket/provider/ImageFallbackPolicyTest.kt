package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Image input fallback decision-table tests (SPEC §4).
 *
 * Rule order: ① single image > 10 MiB or edge > 8192px refuses
 * (`IMAGE_TOO_LARGE`, never transcodes); ② `preserveOriginal=true` sends
 * original bytes as-is; ③ `preserveOriginal=false` marks transcode
 * candidates; ④ image-less providers strip everything but still send text;
 * ⑤ more than 4 images keeps the first 4.
 */
class ImageFallbackPolicyTest {

    private fun info(
        bytes: Long = 1024,
        preserveOriginal: Boolean = true,
        widthPx: Int? = null,
        heightPx: Int? = null,
    ) = ImageFallbackPolicy.ImageInfo(
        bytesSize = bytes,
        mimeType = "image/png",
        preserveOriginal = preserveOriginal,
        widthPx = widthPx,
        heightPx = heightPx,
    )

    @Test
    fun oversizeBytesRefusedNeverDegraded() {
        val big = ImageFallbackPolicy.MAX_SINGLE_IMAGE_BYTES + 1
        val decision = ImageFallbackPolicy.decide(listOf(info(bytes = big)), supportsImages = true)
        when (decision) {
            is ImageFallbackPolicy.Decision.Reject -> assertEquals("IMAGE_TOO_LARGE", decision.reason)
            else -> fail("expected Reject, got $decision")
        }
        // Even a transcode-willing image is refused, not degraded.
        val willing = ImageFallbackPolicy.decide(
            listOf(info(bytes = big, preserveOriginal = false)),
            supportsImages = true,
        )
        when (willing) {
            is ImageFallbackPolicy.Decision.Reject -> assertEquals("IMAGE_TOO_LARGE", willing.reason)
            else -> fail("expected Reject, got $willing")
        }
    }

    @Test
    fun oversizeEdgeRefused() {
        val wide = ImageFallbackPolicy.decide(
            listOf(info(widthPx = ImageFallbackPolicy.MAX_EDGE_PX + 1, heightPx = 100)),
            supportsImages = true,
        )
        when (wide) {
            is ImageFallbackPolicy.Decision.Reject -> assertEquals("IMAGE_TOO_LARGE", wide.reason)
            else -> fail("expected Reject, got $wide")
        }
        val tall = ImageFallbackPolicy.decide(
            listOf(info(heightPx = ImageFallbackPolicy.MAX_EDGE_PX + 1)),
            supportsImages = true,
        )
        when (tall) {
            is ImageFallbackPolicy.Decision.Reject -> assertEquals("IMAGE_TOO_LARGE", tall.reason)
            else -> fail("expected Reject, got $tall")
        }
    }

    @Test
    fun preserveOriginalSentAsIsWithoutTranscode() {
        val decision = ImageFallbackPolicy.decide(listOf(info(), info()), supportsImages = true)
        when (decision) {
            is ImageFallbackPolicy.Decision.Send -> {
                assertEquals(listOf(0, 1), decision.indices)
                assertTrue(decision.transcode.isEmpty())
                assertEquals(0, decision.omitted)
            }
            else -> fail("expected Send, got $decision")
        }
    }

    @Test
    fun transcodeWillingImagesMarkedForJpegDownscale() {
        val decision = ImageFallbackPolicy.decide(
            listOf(info(preserveOriginal = false), info()),
            supportsImages = true,
        )
        when (decision) {
            is ImageFallbackPolicy.Decision.Send -> {
                assertEquals(listOf(0, 1), decision.indices)
                assertEquals(listOf(0), decision.transcode)
                assertEquals(0, decision.omitted)
            }
            else -> fail("expected Send, got $decision")
        }
    }

    @Test
    fun unsupportedProviderStripsImagesButKeepsText() {
        val decision = ImageFallbackPolicy.decide(listOf(info(), info()), supportsImages = false)
        when (decision) {
            is ImageFallbackPolicy.Decision.StripAll -> assertEquals(2, decision.omitted)
            else -> fail("expected StripAll, got $decision")
        }
    }

    @Test
    fun moreThanFourImagesKeepsFirstFour() {
        val decision = ImageFallbackPolicy.decide(List(6) { info() }, supportsImages = true)
        when (decision) {
            is ImageFallbackPolicy.Decision.Send -> {
                assertEquals(listOf(0, 1, 2, 3), decision.indices)
                assertEquals(2, decision.omitted)
            }
            else -> fail("expected Send, got $decision")
        }
    }

    @Test
    fun emptyInputSendsNothing() {
        val decision = ImageFallbackPolicy.decide(emptyList(), supportsImages = true)
        when (decision) {
            is ImageFallbackPolicy.Decision.Send -> {
                assertTrue(decision.indices.isEmpty())
                assertEquals(0, decision.omitted)
            }
            else -> fail("expected Send, got $decision")
        }
    }

    @Test
    fun omissionNoteIsStable() {
        assertEquals("image omitted: not sent to provider", ImageFallbackPolicy.OMISSION_NOTE)
    }
}
