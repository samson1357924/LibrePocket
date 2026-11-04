package dev.librepocket.agent.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.R
import kotlinx.coroutines.delay

/**
 * Saveable holder for hoisted thinking expanded overrides, keyed by deterministic
 * occurrence-aware override id (see [thinkingOverrideId]): `"<contentHashHex16
 * (trimmedContent)>.<rank>"`, where `rank` is the 0-based occurrence index of the same
 * trimmed content within one parse.
 *
 * The saver persists the map as a `List<Pair<String, Boolean>>` (both components are
 * Bundle-saveable primitives) and restores it into a [SnapshotStateMap]. Content-hash
 * indexing keeps the override attached to the same reasoning text when the parser key
 * changes (`think_streaming_N` → `think_c_<hex>`) and Compose recreates the node via
 * `key(block.key)`. The occurrence rank disambiguates same-content duplicates within one
 * message, so toggling one card no longer toggles all cards with identical content.
 */
internal val ThinkingExpandedOverridesSaver:
    Saver<SnapshotStateMap<String, Boolean>, List<Pair<String, Boolean>>> = Saver(
    save = { state -> ArrayList(state.toList()) },
    restore = { saved ->
        mutableStateMapOf<String, Boolean>().apply { putAll(saved.toMap()) }
    },
)

/**
 * Total UTF-8 byte cap for the saveable previous-parse thinking contents
 * (see [ThinkingPrevContentsSaver]). Keeps rotation persistence bounded at chat sizes.
 */
internal const val ThinkingPrevContentsSaveBytesMax = 32 * 1024

/**
 * Saveable holder for the previous parse's thinking contents (trimmed, document order),
 * used for cross-parse override alignment (see [ChatMarkdownView]).
 *
 * Over-limit behaviour: when the summed UTF-8 bytes exceed
 * [ThinkingPrevContentsSaveBytesMax], an empty list is persisted instead. An empty (or
 * never-observed / `null`) previous list is treated as *unknown* by the alignment logic:
 * exact-id reads still apply, but no prefix migration and no stale-entry pruning happen
 * until the next parse is observed (pruning is then deferred by one composition).
 * This is safe because a legitimately empty previous list (no thinking cards) implies no
 * live entries to prune — the map was already emptied when the cards disappeared.
 */
internal val ThinkingPrevContentsSaver: Saver<MutableState<List<String>?>, List<String>> =
    Saver(
        save = { state ->
            val source = state.value ?: emptyList()
            var totalBytes = 0
            var fits = true
            for (entry in source) {
                totalBytes += entry.toByteArray(Charsets.UTF_8).size
                if (totalBytes > ThinkingPrevContentsSaveBytesMax) {
                    fits = false
                    break
                }
            }
            if (fits) ArrayList(source) else emptyList()
        },
        restore = { saved -> mutableStateOf(saved) },
    )

/**
 * Deterministic override id for one thinking card: `"<hex16(trimmedContent)>.<rank>"`.
 *
 * - `hex16` = [ChatMarkdownParser.contentHashHex16] over the trimmed content (same
 *   digest the parser uses for `think_c_<hex>` keys).
 * - `rank` = 0-based occurrence index of the same trimmed content within the current
 *   parse (first occurrence has rank 0). Counting is keyed by content hash, matching the
 *   parser's `_o2`/`_o3` disambiguation (document order, positional under same-content
 *   prepend — same documented limitation).
 *
 * Pure function of content: deterministic across rotations and process recreation, and
 * composed only of Bundle-safe primitives so it can be persisted as a map key.
 */
internal fun thinkingOverrideId(trimmedContent: String, rank: Int): String =
    "${ChatMarkdownParser.contentHashHex16(trimmedContent)}.$rank"

/**
 * Occurrence-aware override ids for one parse's thinking contents (trimmed, document
 * order). Ids are unique within the parse: same-content duplicates get distinct ranks.
 */
internal fun thinkingOverrideIds(trimmedContents: List<String>): List<String> {
    val ranks = HashMap<String, Int>(trimmedContents.size)
    return trimmedContents.map { content ->
        val hash = ChatMarkdownParser.contentHashHex16(content)
        val rank = ranks.getOrDefault(hash, 0)
        ranks[hash] = rank + 1
        "$hash.$rank"
    }
}

/**
 * Collapsible reasoning card for displaying thinking processes.
 * Defaults to expanded during streaming with a static cursor indicator (no blinking animation),
 * and collapses by default once streaming completes.
 *
 * Expanded-override resolution (two sources):
 * - Hoisted mode: when [onUserExpandedChange] != null (used by [ChatMarkdownView]), the
 *   effective override is [userExpanded] (null = no explicit override, fall back to
 *   [ChatContentBlock.ThinkingBlock.isStreaming]). Clicks are forwarded to
 *   [onUserExpandedChange] with the occurrence-aware id (`"<hex16>.<rank>"`) written
 *   by the caller, so streaming→
 *   completed transitions (same trimmed content and rank) retain the user's choice even
 *   though `block.key` changes and the node is recreated.
 * - Fallback mode: when [onUserExpandedChange] == null (direct rendering, e.g. unit tests
 *   rendering this composable alone), [userExpanded] is ignored and an internal
 *   `rememberSaveable(block.key)` holds the override. This preserves the existing direct-
 *   rendering behaviour; it is no longer the sole state source when hosted in
 *   [ChatMarkdownView] (hoisted content-hash map wins).
 */
@Composable
internal fun ThinkingBlockView(
    block: ChatContentBlock.ThinkingBlock,
    modifier: Modifier = Modifier,
    userExpanded: Boolean? = null,
    onUserExpandedChange: ((Boolean) -> Unit)? = null,
) {
    var fallbackExpanded by rememberSaveable(block.key) { mutableStateOf<Boolean?>(null) }
    // Explicit block.key input: fallback state is scoped to this content identity and resets
    // when the identity changes (e.g. streaming `think_streaming_0` → completed `think_c_<hex>`).
    // Prepend/reorder stability relies on the outer key(block.key) in ChatMarkdownView keeping
    // each card's node (and thus this fallback) attached to the same content.
    // In hoisted mode (ChatMarkdownView) this fallback is unused: the caller passes the
    // migrated content-hash override via [userExpanded] instead.
    val effectiveOverride = if (onUserExpandedChange != null) userExpanded else fallbackExpanded
    val isExpanded = effectiveOverride ?: block.isStreaming
    // Accessibility: the header is a Button whose expanded/collapsed state is exposed
    // via stateDescription (TalkBack announces it alongside the title). The chevron
    // Icon keeps its expand/collapse description as a redundant affordance cue.
    val stateDesc = stringResource(
        if (isExpanded) R.string.chat_thinking_state_expanded else R.string.chat_thinking_state_collapsed
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        val animationModifier = if (!block.isStreaming) Modifier.animateContentSize() else Modifier
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(animationModifier),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { stateDescription = stateDesc }
                    .clickable(role = Role.Button) {
                        val next = !isExpanded
                        if (onUserExpandedChange != null) {
                            onUserExpandedChange(next)
                        } else {
                            fallbackExpanded = next
                        }
                    }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_thinking_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (block.isStreaming) {
                        Text(
                            text = stringResource(R.string.chat_thinking_in_progress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(if (isExpanded) R.string.chat_thinking_collapse else R.string.chat_thinking_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (isExpanded) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                )
                // The streaming cursor "▍" is visual only: expose the plain content as the
                // contentDescription so TalkBack reads the reasoning text without the
                // cursor glyph, while the visible Text keeps the cursor.
                Text(
                    text = if (block.isStreaming) block.content + " ▍" else block.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { contentDescription = block.content },
                )
            }
        }
    }
}

/**
 * Dedicated code block card with monospace formatting, horizontal scrolling,
 * language badge, and a copy button with brief success feedback.
 */
@Composable
internal fun CodeBlockView(
    block: ChatContentBlock.CodeBlock,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    // Bind feedback to content identity (defense-in-depth alongside outer key(block.key)):
    // even on a (negligible-probability) key hash collision, Copied follows block.code.
    var copied by remember(block.key, block.code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val langDisplay = block.language?.ifBlank { null } ?: stringResource(R.string.chat_code_default_language)
                Text(
                    text = langDisplay,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val copyDescription = stringResource(
                    if (copied) R.string.chat_code_copied else R.string.chat_code_copy_description
                )
                val copyEnabled = !block.isStreaming
                val copyColor = when {
                    !copyEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    copied -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        // Copy/Copied feedback is text-only visually, so mark the button as a
                        // polite live region: TalkBack announces the Copied confirmation.
                        .semantics {
                            contentDescription = copyDescription
                            liveRegion = LiveRegionMode.Polite
                        }
                        .clickable(
                            enabled = copyEnabled,
                            role = Role.Button,
                        ) {
                            try {
                                // Residual risk: the clipboard is system-globally readable and
                                // has no auto-clear; the copied snippet stays there until
                                // overwritten. Users should avoid copying secrets.
                                clipboardManager.setText(AnnotatedString(sanitizeCodeForClipboard(block.code)))
                                copied = true
                            } catch (_: Exception) {
                                // Ignore clipboard failures gracefully (e.g. MDM policies, window focus loss, payload too large)
                            }
                        }
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = null,
                        tint = copyColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(if (copied) R.string.chat_code_copied else R.string.chat_code_copy),
                        style = MaterialTheme.typography.labelSmall,
                        color = copyColor,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // P3 limitation: streaming→completed changes block.key
                    // (code_streaming_N → code_fenced_*/code_indented_*), so the outer
                    // key(block.key) recreates this node and horizontal scroll resets.
                    // Hoisting ScrollState above the keyed node keyed by ordinal would
                    // misplace offsets on prepend; accepted tradeoff, pinned by test.
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                val displayCode = if (block.isStreaming) block.code + " ▍" else block.code
                // Streaming cursor "▍" is visual only (see ThinkingBlockView): announce the
                // plain code so TalkBack skips the cursor glyph.
                Text(
                    text = displayCode,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                    modifier = Modifier.semantics { contentDescription = block.code },
                )
            }
        }
    }
}

/**
 * Renders structured markdown and thinking blocks within a message bubble.
 *
 * Streaming contract: [ChatMarkdownView] re-parses the full [text] prefix on every chunk
 * via `remember(text, isStreaming) { parse(text, ...) }`. Each call is O(N) in the current
 * prefix length (see [ChatMarkdownParser] cost model), so M growth steps with chunk size c
 * cost Theta(N^2 / c) cumulatively. Incremental or tail-segment re-parsing (only re-scan
 * the changed suffix) is explicitly future work and is not implemented here; the current
 * full re-parse keeps key stability and fail-closed guarantees simple at chat sizes.
 *
 * Thinking expanded-override hoisting (streaming→completed retention):
 * - Overrides live here in a `rememberSaveable` `mutableStateMapOf<overrideId, Boolean>`
 *   ([ThinkingExpandedOverridesSaver], persisted as `List<Pair<String, Boolean>>`), where
 *   `overrideId` is `"<contentHashHex16(trimmedContent)>.<rank>"`
 *   (see [thinkingOverrideId]; `rank` = occurrence index of the same trimmed content in
 *   this parse, so same-content duplicates get distinct entries). Each thinking card
 *   reads `overrides[id]` (after align/prune below) and writes its exact occurrence-aware
 *   id on click; the default when absent is `block.isStreaming`.
 * - Every recomposition aligns synchronously before reading, against the previous
 *   parse's trimmed contents (`prevContents`, also `rememberSaveable` via
 *   [ThinkingPrevContentsSaver]; `null`/empty = unknown, e.g. not yet observed or
 *   over the [ThinkingPrevContentsSaveBytesMax] rotation cap):
 *   - Exact: a current id already present in the previous parse's id set is the same
 *     logical card — its override is retained as-is. This covers the
 *     streaming→completed transition (`think_streaming_N` → `think_c_<hex>`, same
 *     trimmed text, same rank): a manual expand during streaming survives appending
 *     `</think>` even while message-level [isStreaming] is still true. Untouched cards
 *     still auto-collapse on close per spec.
 *   - Migration (current id is new): only when the previous and current thinking card
 *     counts are equal, and the previous parse's content at the same position is a
 *     strict prefix of the current content (empty→non-empty included, so collapsing
 *     `<think>` survives growth to `<think>P…`), the value stored under the previous
 *     position's id is inherited. An existing explicit entry on the target wins.
 *     Card counts differing (prepend/delete of a thinking card) disables migration —
 *     only exact-id matches apply — so a prepended different-content card falls back
 *     to `block.isStreaming` instead of inheriting an unrelated card's choice.
 *   - Otherwise the entry defaults; stale ids (neither exact-retained nor migration
 *     targets) are pruned so a future different-content card never inherits them.
 *     When `prevContents` is unknown, pruning is skipped (deferred to the next
 *     observed parse); exact-id reads still apply.
 * - Limitation (documented): same-content prepend shifts occurrence ranks positionally
 *   (first card's id moves to the prepended card), consistent with the parser's `_o2`
 *   key disambiguation for same-content duplicates. Prepending *different* content does
 *   not misapply overrides (card counts differ → exact-id matching only).
 */
@Composable
internal fun ChatMarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    textColor: Color = Color.Unspecified,
) {
    val blocks = remember(text, isStreaming) {
        ChatMarkdownParser.parse(text, isStreaming = isStreaming)
    }

    val thinkingOverrides = rememberSaveable(saver = ThinkingExpandedOverridesSaver) {
        mutableStateMapOf<String, Boolean>()
    }
    // Previous parse's thinking contents (trimmed, document order) for cross-parse
    // alignment. Saveable: rotation-safe (bounded by ThinkingPrevContentsSaveBytesMax);
    // null/empty = unknown (not yet observed) → exact-id reads only, no prune/migrate.
    var prevThinkingContents by rememberSaveable(saver = ThinkingPrevContentsSaver) {
        mutableStateOf<List<String>?>(null)
    }

    val currentThinkingContents = remember(blocks) {
        blocks.filterIsInstance<ChatContentBlock.ThinkingBlock>().map { it.content.trim() }
    }
    val currentOverrideIds = remember(currentThinkingContents) {
        thinkingOverrideIds(currentThinkingContents)
    }
    val currentIdSet = remember(currentOverrideIds) { currentOverrideIds.toSet() }
    val thinkingIdByKey = remember(blocks, currentOverrideIds) {
        blocks.filterIsInstance<ChatContentBlock.ThinkingBlock>()
            .map { it.key }
            .zip(currentOverrideIds)
            .toMap()
    }
    val prevKnownContents = prevThinkingContents?.takeIf { it.isNotEmpty() }
    val prevOverrideIds = remember(prevKnownContents) {
        prevKnownContents?.let { thinkingOverrideIds(it) }
    }

    // Synchronously align before reads so this composition already observes the
    // retained/migrated values. Idempotent: a mutating pass schedules one follow-up
    // recomposition that becomes a no-op.
    if (prevKnownContents != null && prevOverrideIds != null) {
        val prevIdSet = prevOverrideIds.toSet()
        if (prevKnownContents.size == currentThinkingContents.size) {
            for (i in currentThinkingContents.indices) {
                val currId = currentOverrideIds[i]
                // Exact: same logical card, retain as-is.
                if (currId in prevIdSet) continue
                // Migration: same position, strict prefix growth (empty→non-empty
                // included). Card-count equality guards against prepend/delete shifts.
                val prevContent = prevKnownContents[i]
                val currContent = currentThinkingContents[i]
                if (prevContent != currContent && currContent.startsWith(prevContent)) {
                    val migratedValue = thinkingOverrides[prevOverrideIds[i]]
                    if (migratedValue != null && !thinkingOverrides.containsKey(currId)) {
                        thinkingOverrides[currId] = migratedValue
                    }
                }
            }
        }
        // Prune stale ids (disappeared cards); retained/migrated targets survive.
        val staleKeys = thinkingOverrides.keys.filter { it !in currentIdSet }
        for (stale in staleKeys) {
            thinkingOverrides.remove(stale)
        }
    }
    if (prevThinkingContents != currentThinkingContents) {
        prevThinkingContents = currentThinkingContents
    }

    if (blocks.isEmpty() && isStreaming) {
        // Decorative streaming cursor: visual only, announced via the streaming label
        // so TalkBack does not read the "▍" glyph.
        val cursorDescription = stringResource(R.string.chat_streaming_label)
        Text(
            text = "▍",
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            modifier = modifier.semantics {
                contentDescription = cursorDescription
            },
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            key(block.key) {
                when (block) {
                    is ChatContentBlock.ThinkingBlock -> {
                        val overrideId = thinkingIdByKey[block.key]
                            ?: thinkingOverrideId(block.content.trim(), 0)
                        val hoistedOverride = thinkingOverrides[overrideId]
                        ThinkingBlockView(
                            block = block,
                            userExpanded = hoistedOverride,
                            onUserExpandedChange = { next ->
                                // Async write: exact occurrence-aware id for the clicked
                                // card. Reads above already observe the retained value.
                                thinkingOverrides[overrideId] = next
                            },
                        )
                    }
                    is ChatContentBlock.CodeBlock -> {
                        CodeBlockView(block = block)
                    }
                    is ChatContentBlock.ParagraphBlock -> {
                        val isLast = index == blocks.size - 1
                        val content = if (isStreaming && isLast) {
                            buildAnnotatedString {
                                append(block.annotatedText)
                                append(" ▍")
                            }
                        } else {
                            block.annotatedText
                        }
                        // Streaming cursor "▍" is visual only: announce the paragraph text
                        // without the cursor glyph.
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            modifier = Modifier.semantics {
                                contentDescription = block.annotatedText.text
                            },
                        )
                    }
                }
            }
        }

        if (isStreaming) {
            val lastBlock = blocks.lastOrNull()
            val lastBlockIsStreaming = when (lastBlock) {
                is ChatContentBlock.ThinkingBlock -> lastBlock.isStreaming
                is ChatContentBlock.CodeBlock -> lastBlock.isStreaming
                is ChatContentBlock.ParagraphBlock -> true
                null -> false
            }
            if (!lastBlockIsStreaming) {
                // Decorative trailing cursor (see empty-streaming case above).
                val trailingCursorDescription = stringResource(R.string.chat_streaming_label)
                Text(
                    text = "▍",
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                    modifier = Modifier.semantics {
                        contentDescription = trailingCursorDescription
                    },
                )
            }
        }
    }
}

/**
 * Sanitizes code snippets before copying to clipboard.
 *
 * Single O(N) pass over the normalized text (one StringBuilder append per kept char;
 * U+2028/U+2029 append a single '\n').
 *
 * - Normalizes Windows CRLF to LF.
 * - Strips isolated CR characters to prevent terminal line overwrite attacks.
 * - Strips ASCII ESC to block ANSI terminal escape sequences
 *   (cursor movement, alternate screen, title setting) hidden in copied code.
 * - Strips Unicode BiDi override/embedding/isolate controls (LRE/RLE/PDF/LRO/RLO and
 *   LRI/RLI/FSI/PDI ranges: U+202A..U+202E, U+2066..U+2069), which reorder
 *   displayed text versus pasted bytes.
 * - Strips invisible characters with no legitimate text content (ZWSP U+200B,
 *   ZWNBSP/BOM U+FEFF, word joiner and invisible operators U+2060..U+2064).
 * - Preserves legitimate text constituents: ZWNJ U+200C / ZWJ U+200D (emoji ZWJ
 *   sequences such as U+1F469 U+200D U+1F4BB, Indic/Persian joining behaviour)
 *   and LRM U+200E / RLM U+200F (non-isolate directional marks with no override
 *   capability, needed for mixed-direction text). These are kept verbatim so
 *   visible content survives copy round-trip; that is why the copy button shows
 *   a plain Copied confirmation without extra disclosure.
 * - Maps Unicode line/paragraph separators (U+2028/U+2029, genuine separators)
 *   to LF, preserving the line-break semantics instead of silently joining the
 *   two sides into one line.
 *
 * Distinguishing principle: strip dangerous terminal controls and invisible
 * characters that alter rendering or pasted bytes; keep legitimate text
 * constituents even when invisible. Newline semantics (LF, including converted
 * U+2028/U+2029) are preserved.
 *
 * Residual risk (documented, not eliminated): newlines themselves are preserved, so
 * pasting multi-line output into a terminal still executes it line by line. Review
 * pasted code before running it. The clipboard is also system-globally readable with
 * no auto-clear: the sanitized snippet stays there until overwritten, so avoid
 * copying secrets.
 */
internal fun sanitizeCodeForClipboard(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace("\r", "")
    val sb = StringBuilder(normalized.length)
    for (i in 0 until normalized.length) {
        val ch = normalized[i]
        if (ch == '\u001B') {
            continue
        }
        if (ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069') {
            continue
        }
        if (ch == '\u200B' || ch == '\uFEFF' || ch in '\u2060'..'\u2064') {
            continue
        }
        if (ch == '\u2028' || ch == '\u2029') {
            sb.append('\n')
            continue
        }
        sb.append(ch)
    }
    return sb.toString()
}
