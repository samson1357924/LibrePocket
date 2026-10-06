package dev.librepocket.provider

/**
 * Image input fallback policy (SPEC §4 decision table). Pure function,
 * zero Android dependency: callers pass already-known byte sizes / bounds
 * (no Bitmap decoding in here).
 *
 * Order:
 * 1. Single image > 10 MiB or edge > 8192px -> [Decision.Reject] (never
 *    transcode/scale; over-limit is refused, not degraded).
 * 2. `preserveOriginal=true` (default) -> original bytes + original MIME.
 *    Provider caps are enforced by the server; over-limit then also rejects.
 * 3. `preserveOriginal=false` and the provider supports images ->
 *    caller may transcode to JPEG q85 / longest edge 2048 on an IO dispatcher.
 * 4. Provider without image support -> strip images, text still sent.
 * 5. More than 4 images -> only the first 4 are sent.
 *
 * Image bytes never reach Room/JSONL/logcat; the plan only carries
 * [Decision.omitted] counts plus the stable omission note.
 */
object ImageFallbackPolicy {
    const val MAX_SINGLE_IMAGE_BYTES = 10L * 1024 * 1024
    const val MAX_EDGE_PX = 8192
    const val TRANSCODE_EDGE_PX = 2048
    const val MAX_IMAGES_PER_REQUEST = 4
    const val OMISSION_NOTE = "image omitted: not sent to provider"

    data class ImageInfo(
        val bytesSize: Long,
        val mimeType: String,
        val preserveOriginal: Boolean = true,
        val widthPx: Int? = null,
        val heightPx: Int? = null,
    )

    sealed interface Decision {
        data class Reject(val reason: String) : Decision
        data class Send(val indices: List<Int>, val transcode: List<Int>, val omitted: Int) : Decision
        data class StripAll(val omitted: Int) : Decision
    }

    fun decide(images: List<ImageInfo>, supportsImages: Boolean): Decision {
        if (images.isEmpty()) return Decision.Send(emptyList(), emptyList(), 0)
        for (img in images) {
            if (img.bytesSize > MAX_SINGLE_IMAGE_BYTES) {
                return Decision.Reject("IMAGE_TOO_LARGE")
            }
            val w = img.widthPx
            val h = img.heightPx
            if ((w != null && w > MAX_EDGE_PX) || (h != null && h > MAX_EDGE_PX)) {
                return Decision.Reject("IMAGE_TOO_LARGE")
            }
        }
        if (!supportsImages) return Decision.StripAll(omitted = images.size)
        val sendable = images.indices.take(MAX_IMAGES_PER_REQUEST)
        val omitted = images.size - sendable.size
        val transcode = sendable.filter { !images[it].preserveOriginal }
        return Decision.Send(indices = sendable, transcode = transcode, omitted = omitted)
    }

    fun fromChatImage(image: ChatImage): ImageInfo = ImageInfo(
        bytesSize = image.bytes.size.toLong(),
        mimeType = image.mimeType,
        preserveOriginal = image.preserveOriginal,
        widthPx = image.widthPx,
        heightPx = image.heightPx,
    )
}
