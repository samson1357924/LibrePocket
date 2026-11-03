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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * Collapsible reasoning card for displaying thinking processes.
 * Defaults to expanded during streaming with a static cursor indicator (no blinking animation),
 * and collapses by default once streaming completes.
 */
@Composable
internal fun ThinkingBlockView(
    block: ChatContentBlock.ThinkingBlock,
    modifier: Modifier = Modifier,
) {
    var userExpanded by rememberSaveable(block.key) { mutableStateOf<Boolean?>(null) }
    // Explicit block.key input: state is scoped to this content identity and resets when the
    // identity changes (e.g. streaming `think_streaming_0` → completed `think_c_<hex>`).
    // Prepend/reorder stability relies on the outer key(block.key) in ChatMarkdownView keeping
    // each card's node (and thus this rememberSaveable) attached to the same content.
    val isExpanded = userExpanded ?: block.isStreaming
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
                    .clickable(role = Role.Button) { userExpanded = !isExpanded }
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
                        ThinkingBlockView(block = block)
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
 * Single O(N) pass over the normalized text (one StringBuilder append per kept char).
 *
 * - Normalizes Windows CRLF to LF.
 * - Strips isolated CR characters to prevent terminal line overwrite attacks.
 * - Strips ASCII ESC to block ANSI terminal escape sequences
 *   (cursor movement, alternate screen, title setting) hidden in copied code.
 * - Strips Unicode BiDi formatting characters (LRE/RLE/PDF/LRO/RLO and
 *   LRI/RLI/FSI/PDI ranges).
 * - Strips zero-width / invisible characters that alter rendering or pasted bytes
 *   (ZWSP/ZWNJ/ZWJ, LRM/RLM, ZWNBSP/BOM, word joiner and invisible operators).
 * - Strips Unicode line/paragraph separators, which some terminals and editors
 *   treat as line breaks.
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
        if (ch in '\u200B'..'\u200D' || ch in '\u200E'..'\u200F' || ch == '\uFEFF'
            || ch in '\u2060'..'\u2064' || ch in '\u2028'..'\u2029'
        ) {
            continue
        }
        sb.append(ch)
    }
    return sb.toString()
}
