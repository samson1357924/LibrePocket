package dev.librepocket.agent.ui.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.librepocket.session.TranscriptEvent
import dev.librepocket.redact.Redactor
import kotlinx.coroutines.CancellationException

/** Displays stored rows only; no chat session, provider, or provenance is modified. */
@Composable
internal fun TranscriptViewer(
    sessionId: String,
    loadPage: suspend (String, Long) -> List<TranscriptEvent>,
    onClose: () -> Unit,
) {
    var cursor by remember(sessionId) { mutableLongStateOf(0L) }
    var previousCursors by remember(sessionId) { mutableStateOf(emptyList<Long>()) }
    var rows by remember(sessionId) { mutableStateOf<List<TranscriptEvent>?>(null) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(sessionId, cursor) {
        rows = null
        failed = false
        listState.scrollToItem(0)
        try {
            rows = loadPage(sessionId, cursor)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("唯讀 transcript") },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp).testTag("transcriptPages")) {
                item { Text("內容僅供檢視，來源與真實性未經驗證；不會加入對話或傳送給模型。") }
                item { Text("舊備份可能未記錄截斷狀態；未標示截斷不代表內容完整。") }
                if (previousCursors.isNotEmpty()) item {
                    Button(onClick = {
                        cursor = previousCursors.last()
                        previousCursors = previousCursors.dropLast(1)
                    }) { Text("上一頁") }
                }
                if (failed) item { Text("無法讀取對話，請關閉後重試。") }
                else if (rows == null) item { Text("載入中…") }
                else {
                    val page = rows.orEmpty()
                    if (page.isEmpty()) item { Text("沒有更多事件。") }
                    items(page, key = { it.seq }) { event ->
                        if (event.isTruncated) Text("此事件內容已截斷。")
                        Text("${event.kind}${if (event.isPartial) "（部分）" else ""}: ${Redactor.redact(event.text).text}")
                        event.failureReason?.let { Text("失敗原因：${Redactor.redact(it).text}") }
                        if (event.imagesOmitted > 0) Text("已省略圖片：${event.imagesOmitted}")
                    }
                    if (page.size == 100 && page.last().seq < Long.MAX_VALUE) item {
                        Button(onClick = {
                            previousCursors = previousCursors + cursor
                            cursor = page.last().seq
                        }) { Text("下一頁") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("關閉") } },
    )
}
