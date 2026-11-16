package dev.librepocket.agent.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.PrivilegeBridgeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「一鍵收回」VM 狀態機單測（Re-review #7 Finding 3 / P2 UX·lifecycle，#136-B1）。
 *
 * setMain/resetMain 慣例鏡像 sibling VM 測試（viewModelScope 走 Main；
 * UnconfinedTestDispatcher 讓 `revoke()` 內的 launch 同步推進到第一個 suspension
 * 點——INFLIGHT 在 `revoke()` 返回時即確定落位，gate 由 executor 自帶
 * [CompletableDeferred] 控制，全程不需要真實時鐘等待）。
 *
 * 注意：VM 於 `revoke()` 時間點才組合 `PrivilegeBridgeState.process()` 與
 * `PrivilegeBridgeStore(app)`（spec：撤權時間點組合、行程單例），故需要
 * Robolectric 提供 Application 與 Context.filesDir——普通 JUnit4（android.jar
 * stub）無法實例化 Application，此為與純 JVM sibling 測試唯一差異。
 * executor 一律為測試內手寫假件（不觸及 DataStore／Shizuku）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeRevokeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun newViewModel(
        executor: suspend (PrivilegeBridgeState, PrivilegeBridgeStore) -> Boolean,
    ): PrivilegeRevokeViewModel = PrivilegeRevokeViewModel(app(), withdraw = {}, executor = executor)

    // executor 極性（與 executePrivilegeRevoke 一致）：true = 撤權失敗 ⇒ Error，false = 撤權成功 ⇒ Idle。

    @Test
    fun success_endsIdle() = runBlocking {
        val vm = newViewModel { _, _ -> false }
        vm.revoke()
        assertEquals(PrivilegeRevokeUiState.Idle, vm.uiState.value)
    }

    @Test
    fun executorReportsFailure_endsError() = runBlocking {
        val vm = newViewModel { _, _ -> true }
        vm.revoke()
        assertEquals(PrivilegeRevokeUiState.Error, vm.uiState.value)
    }

    @Test
    fun executorTrueMeansFailure_setsError() = runBlocking {
        // 釘死旗標極性：executor 回 true（撤權失敗）⇒ Error；回 false（撤權成功）⇒ Idle。
        val failing = newViewModel { _, _ -> true }
        failing.revoke()
        assertEquals("executor 回 true 必落 Error（極性不得再反轉）", PrivilegeRevokeUiState.Error, failing.uiState.value)
        val succeeding = newViewModel { _, _ -> false }
        succeeding.revoke()
        assertEquals("executor 回 false 必落 Idle（極性不得再反轉）", PrivilegeRevokeUiState.Idle, succeeding.uiState.value)
    }

    @Test
    fun doubleInvokeWhileInFlight_executorCalledExactlyOnce() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        var calls = 0
        val vm = newViewModel { _, _ -> calls += 1; gate.await() }
        vm.revoke()
        assertEquals(PrivilegeRevokeUiState.InFlight, vm.uiState.value)
        vm.revoke() // VM 級守衛：InFlight 直接返回，executor 不重入
        vm.revoke()
        gate.complete(false) // false = 撤權成功 ⇒ Idle
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.uiState.value is PrivilegeRevokeUiState.InFlight && System.currentTimeMillis() < deadline) {
            yield()
        }
        assertEquals("InFlight 重入的第二次 revoke 不得再調 executor", 1, calls)
        assertEquals(PrivilegeRevokeUiState.Idle, vm.uiState.value)
    }

    /**
     * Error 完成後不會被 reset 且 VM 提供 replay≥1 的 StateFlow；
     * 跨 Activity 重建的 retained-VM pinning 由 WiringTest 負責，本測試不以其為宣稱。
     */
    @Test
    fun errorPersistsAcrossFreshCollector() = runBlocking {
        val vm = newViewModel { _, _ -> true }
        vm.revoke()
        assertEquals(PrivilegeRevokeUiState.Error, vm.uiState.value)
        // 「新 UI」= 同一 VM 的全新收集者：StateFlow 重放 Error，banner 不遺失。
        val collected = mutableListOf<PrivilegeRevokeUiState>()
        withTimeout(5_000) {
            vm.uiState.take(1).collect { collected += it }
        }
        assertEquals(listOf(PrivilegeRevokeUiState.Error), collected)
    }

    @Test
    fun executorDefaultsNull_buildsProductionPath() {
        // 不呼叫 revoke：生產組合（isScopeActive = viewModelScope.isActive）的
        // executePrivilegeRevoke 本體由 PrivilegeRevokeTest 在 helper 層級釘住，
        // 刻意不在此重測。
        val vm = PrivilegeRevokeViewModel(app(), withdraw = { })
        assertEquals(PrivilegeRevokeUiState.Idle, vm.uiState.value)
    }

    @Test
    fun afterError_retryCallsExecutorAgain_andEndsIdleOnSuccess() = runBlocking {
        val outcomes = ArrayDeque(listOf(true, false))
        var calls = 0
        val vm = newViewModel { _, _ -> calls += 1; outcomes.removeFirst() }
        vm.revoke()
        assertEquals(PrivilegeRevokeUiState.Error, vm.uiState.value)
        assertEquals(1, calls)
        // 失敗後重試：再次進入 executor，成功收尾、錯誤清除。
        vm.revoke()
        assertEquals(2, calls)
        assertEquals(PrivilegeRevokeUiState.Idle, vm.uiState.value)
    }
}
