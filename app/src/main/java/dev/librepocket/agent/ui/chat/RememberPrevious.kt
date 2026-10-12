package dev.librepocket.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * 上一幀值（R5-3，純邏輯說明）。
 *
 * 語義：首幀回 null（尚無前值，以 [hasPrev] 區分「首幀」與「前值為 null」，
 * 避免 nullable 值 null→A 轉換被誤判為首幀），其後每次重組回傳入前一次的 [current]。
 * 實作：composition 期間讀 holder 內舊值回傳，composition 提交後以 [SideEffect]
 * 把當代 [current] 寫入 holder；不用 mutableState 持有前值，避免寫入觸發額外重組。
 * 呼叫點以 `hasPrev && current != prev` 判定「非首幀且發生變化」，bump
 *（世代遞增、token 失效、pending 清除等）副作用一律留呼叫點的 LaunchedEffect 內。
 */
@Composable
fun <T> rememberPrevious(current: T): Pair<T?, Boolean> {
    val holder = remember { object { var prev: T? = null; var hasPrev: Boolean = false } }
    val result = holder.prev to holder.hasPrev
    SideEffect {
        holder.prev = current
        holder.hasPrev = true
    }
    return result
}
