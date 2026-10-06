package dev.librepocket.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.UiMessage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    padding: PaddingValues,
    viewModel: ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val visibleMessages = session.messages.filter { it.role == "user" || it.role == "assistant" }
    val streaming = session.status == ChatStatus.STREAMING

    LaunchedEffect(visibleMessages.size, visibleMessages.lastOrNull()?.text?.length, session.status) {
        if (visibleMessages.isNotEmpty()) {
            try {
                listState.scrollToItem(visibleMessages.size - 1)
            } catch (_: IllegalArgumentException) {
                // List not laid out yet; next recomposition will settle.
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding(),
    ) {
        if (visibleMessages.isEmpty()) {
            // Gemini/ChatGPT-style empty state.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "有什麼可以幫你的嗎？",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("幫我寫一段話", "解釋一個概念", "規劃今天", "翻譯一段文字").forEach { hint ->
                            AssistChip(
                                onClick = {
                                    viewModel.onInputChange(hint)
                                    viewModel.send()
                                },
                                label = { Text(hint) },
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleMessages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (streaming) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                                    contentDescription = "正在串流回覆"
                                },
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(2.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text("串流中…")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (session.pendingSteerCount > 0) {
            Text(
                text = "有 ${session.pendingSteerCount} 則指令排隊中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        session.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (viewModel.canRetry) {
                            Button(onClick = viewModel::retry) { Text("重試") }
                        }
                        TextButton(onClick = onOpenSettings) { Text("檢查設定") }
                    }
                }
            }
        }

        notice?.let {
            Text(
                text = when (it) {
                    "NO_ENDPOINT" -> "尚未設定端點，請先設定 API 金鑰。"
                    else -> it
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // Bottom input bar (ChatGPT/Gemini style).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TODO(P1): attach file/photo entry point; currently placeholder (disabled semantics).
            IconButton(onClick = { /* TODO(P1): attach placeholder */ }) {
                Icon(Icons.Filled.Add, contentDescription = "新增附件")
            }
            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("輸入訊息") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
            )
            if (streaming) {
                IconButton(onClick = viewModel::cancel) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                val canSend = input.isNotBlank()
                IconButton(
                    onClick = viewModel::send,
                    enabled = canSend,
                ) {
                    Icon(
                        imageVector = if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                        contentDescription = if (canSend) "傳送" else "語音輸入",
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        ) {
            Text(
                text = if (msg.isPartial) msg.text + " ▍" else msg.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
