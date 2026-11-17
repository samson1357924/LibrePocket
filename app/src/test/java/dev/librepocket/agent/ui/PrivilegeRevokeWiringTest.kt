package dev.librepocket.agent.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.PrivilegeBridgeStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「一鍵收回」接線單測（Re-review #7 Finding 3 / P2 UX·lifecycle，#136-B1）。
 *
 * 測的是接線 composable 的 `content` 三元組（onRevokePrivilege／inFlight／error），
 * 不實例化 `SettingsScreen`——SettingsScreen 啞鈴件的 Booleans 契約由
 * SettingsScreenTest 另行釘住。生產內容（`MainScreen` 的 `SettingsScreen`
 * 接線）與本測試共用同一 [PrivilegeRevokeWiring] 單一來源。
 *
 * 重建（recreate）語義：`Activity.recreate()` 走完 finish→重建
 * （等價配置變更）。重建後 Activity 的 content 已隨舊實例銷毀（default
 * ComponentActivity 的 `onCreate` 為空），故 content 必須再裝一次——
 * 與 MainActivity 生產路徑等價：新 `onCreate` → `setContent` 同一份內容。
 * 關鍵斷言是 ViewModelStore 跨重建保留、`viewModel(factory=...)` 解析回
 * **同一個** [PrivilegeRevokeViewModel]：新 composition 收集到同一 StateFlow，
 * 在途撤權不因重建而回到 Idle／遺失 error banner。
 *
 * executor 為 latch-controlled 假件：`release.await(10s)` 駐在 IO 執行緒
 * （刻意不駐 Main——viewModelScope 走 Main.immediate，駐 Main 會堵死
 * 按鈕重組與 Activity 重建），10 秒上限僅防測試死鎖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeRevokeWiringTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val harness = Harness()

    @After
    fun tearDown() {
        harness.release.countDown() // 防 executor 殘留駐擋（無害：executor 已收尾）
    }

    @Test
    fun failure_inFlightSurvivesActivityRecreate_thenErrorAfterRelease() {
        harness.result = true // true = 撤權失敗 ⇒ ERROR banner
        installContent()
        composeRule.onNodeWithText("revokeState=IDLE").assertIsDisplayed()

        composeRule.onNodeWithTag("revoke").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()
        // 雙擊（recreation 前）：VM 級守衛，executor 不重入。
        composeRule.onNodeWithTag("revoke").performClick()

        // 配置變更式重建：舊 Activity 銷毀 → 新 Activity；content 由測試重裝
        // 同一份（= MainActivity 生產路徑：新 onCreate → setContent）。
        val before = composeRule.activity
        composeRule.activity.recreate()
        composeRule.waitForIdle()
        installContent()
        assertNotSame("recreate 必須真正銷毀舊 Activity（若 recreate 淪為 no-op，此斷言 loud 失敗）", before, composeRule.activity)
        composeRule.waitForIdle()
        // 同一 VM 存活：新 composition 直接見到在途狀態，而非回到 Idle。
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()
        // 重建後再點：仍 InFlight，VM 級守衛對新 composition 依然生效。
        composeRule.onNodeWithTag("revoke").performClick()
        assertEquals("recreation 前後的重複點擊都不得重入 executor", 1, harness.executorCalls.get())

        // 放行 executor（回 true = 失敗）→ ERROR banner 對新 composition 可見。
        harness.release.countDown()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("revokeState=ERROR").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("revokeState=ERROR").assertIsDisplayed()
        assertEquals(1, harness.executorCalls.get())
    }

    @Test
    fun success_inFlightSurvivesActivityRecreate_thenIdleAfterRelease() {
        harness.result = false // false = 撤權成功 ⇒ IDLE、無 banner
        installContent()
        composeRule.onNodeWithText("revokeState=IDLE").assertIsDisplayed()

        composeRule.onNodeWithTag("revoke").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()

        // 配置變更式重建（鏡像失敗 variant）：舊 Activity 銷毀 → 新 Activity，
        // content 由測試重裝同一份。
        val before = composeRule.activity
        composeRule.activity.recreate()
        composeRule.waitForIdle()
        installContent()
        assertNotSame("recreate 必須真正銷毀舊 Activity（若 recreate 淪為 no-op，此斷言 loud 失敗）", before, composeRule.activity)
        composeRule.waitForIdle()
        // 同一 VM 存活：新 composition 直接見到在途狀態，而非回到 Idle。
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()
        // 重建後再點：仍 InFlight，VM 級守衛對新 composition 依然生效。
        composeRule.onNodeWithTag("revoke").performClick()
        assertEquals("重建後的重複點擊仍不得重入 executor", 1, harness.executorCalls.get())

        harness.release.countDown()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("revokeState=IDLE").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "成功不得出現失敗 banner",
            composeRule.onAllNodesWithText("revokeState=ERROR").fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(1, harness.executorCalls.get())
    }

    private fun installContent() {
        composeRule.setContent { RecreateContent(harness) }
    }

    private class Harness {
        val release = CountDownLatch(1)

        /** executor 回傳值（與 executePrivilegeRevoke 同極性）：true = 撤權失敗（ERROR banner），false = 撤權成功（IDLE）。 */
        @Volatile
        var result: Boolean = false

        val executorCalls = AtomicInteger(0)

        val executor: suspend (PrivilegeBridgeState, PrivilegeBridgeStore) -> Boolean =
            { _, _ ->
                executorCalls.incrementAndGet()
                // latch-controlled 假收回；駐 IO（不駐 Main，見類別 KDoc）。
                withContext(Dispatchers.IO) { release.await(10, TimeUnit.SECONDS) }
                result
            }
    }

    @Composable
    private fun RecreateContent(h: Harness) {
        val app = LocalContext.current.applicationContext as Application
        PrivilegeRevokeWiring(
            app = app,
            withdraw = {},
            executor = { state, store -> h.executor(state, store) },
        ) { onRevokePrivilege, inFlight, error ->
            Column(modifier = Modifier.padding(16.dp)) {
                Text("revokeState=" + if (inFlight) "INFLIGHT" else if (error) "ERROR" else "IDLE")
                Button(onClick = onRevokePrivilege, modifier = Modifier.testTag("revoke")) {
                    Text("revoke")
                }
            }
        }
    }
}
