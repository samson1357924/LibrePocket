package dev.librepocket.slow

/**
 * Platform-neutral UI node for the slow channel (ARCHITECTURE §9.2).
 *
 * This is an original, dependency-free description of one on-screen
 * element. It deliberately mirrors no Android framework type: the
 * flavor wiring (a separate workstream) translates platform
 * nodes into this shape before calling the core. Pixel data never
 * enters the core; screenshots are referenced by digest only.
 */
data class UiNode(
    /** Stable identifier within one observation round. */
    val id: String,
    /** Element kind, e.g. "button", "text", "input", "image", "container". */
    val kind: String,
    val text: String = "",
    val contentDescription: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val bounds: NodeBounds? = null,
)

/** Normalized pixel bounds with a derived tap center. */
data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * One screen snapshot handed to the slow loop: node list plus an
 * opaque screenshot digest (never raw pixels).
 */
data class SlowObservation(
    val appPackage: String = "",
    val activityName: String = "",
    val nodes: List<UiNode> = emptyList(),
    val screenshotDigest: String? = null,
)

/** Compressed screen semantics: one short line per kept node. */
data class CompressedScreen(
    val lines: List<String>,
    /** Nodes dropped as layout-only or over the cap. */
    val droppedNodes: Int,
    val truncated: Boolean,
)

/**
 * Semantic compression: keep actionable / text-bearing nodes first,
 * drop text-less layout containers, cap count and text length.
 * Pure function: same input always yields same output.
 */
fun SlowObservation.compress(
    maxNodes: Int = 40,
    maxTextLen: Int = 60,
): CompressedScreen {
    require(maxNodes > 0) { "maxNodes must be positive" }
    require(maxTextLen > 0) { "maxTextLen must be positive" }

    fun labelOf(node: UiNode): String {
        val raw = node.text.ifEmpty { node.contentDescription }
        return if (raw.length > maxTextLen) raw.take(maxTextLen) + "…" else raw
    }

    // Actionable or labelled nodes first (stable order otherwise).
    val ranked = nodes.sortedWith(
        compareByDescending<UiNode> { it.clickable || it.editable }
            .thenByDescending { it.text.isNotEmpty() || it.contentDescription.isNotEmpty() },
    )
    val kept = mutableListOf<String>()
    var dropped = 0
    for (node in ranked) {
        val meaningful = node.clickable || node.editable ||
            node.text.isNotEmpty() || node.contentDescription.isNotEmpty()
        if (!meaningful) {
            dropped++
            continue
        }
        if (kept.size >= maxNodes) {
            dropped++
            continue
        }
        val marker = when {
            node.editable -> "[input]"
            node.clickable -> "[tap]"
            else -> "[info]"
        }
        val center = node.bounds?.let { " @(${it.centerX},${it.centerY})" } ?: ""
        kept.add("$marker ${node.id} ${node.kind} \"${labelOf(node)}\"$center")
    }
    // Unranked tail (nodes never inspected because the cap was hit)
    // is already counted above; containers add no signal.
    return CompressedScreen(
        lines = kept,
        droppedNodes = dropped,
        truncated = dropped > 0,
    )
}
