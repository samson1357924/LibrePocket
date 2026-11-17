package dev.librepocket.agent.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.librepocket.shell.PrivilegeBridgeStore

/**
 * 「一鍵收回」核心 [executePrivilegeRevoke] 單測（Stage 2，Re-review #5 Finding 2）：
 * 撤權失敗（含存取層 CancellationException）必須回傳 true 讓呼叫端顯示
 * 「收回失敗」banner 並允許重試；只有真正的外部取消
 * （[isScopeActive]() == false 的 CE）與 VirtualMachineError 才上拋。
 *
 * Store 假件：純記憶體 DataStore（updateData 無 I/O），測試完全在
 * runBlocking 真實時鐘內確定性完成——**不**用 runTest 的理由：
 * [PrivilegeBridgeStore.clear] 內部固定
 * `withContext(Dispatchers.IO)`（真實執行緒 hop），而撤權各段
 * `withTimeoutOrNull` 在 runTest 虛擬時鐘下會被「scheduler 閒置即
 * 跳至下一個延遲任務」直接快轉觸發（實測：健康 Store 的 clear()
 * 來不及完成即被判 PRIVILEGE_REVOKE_STORE_TIMEOUT，且與失敗路徑
 * 勝敗不定、flaky-by-construction）。與 PrivilegeBridgeStateTest 的
 * revoke 系列測試同一決定：revoke 路徑一律 runBlocking。
 */
class PrivilegeRevokeTest {

    /** 純記憶體 DataStore：updateData 無 I/O，[data] 單次發射當前快照。
     * fake 僅供失敗注入使用；其 data flow 不具 DSL 語義、不用於併發／競態測試（該類測試請沿用 sibling 的 temp-file PreferenceDataStoreFactory idiom）。 */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        @Volatile
        private var prefs: Preferences = emptyPreferences()

        override val data: Flow<Preferences>
            get() = flow { emit(prefs) }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            prefs = transform(prefs)
            return prefs
        }
    }

    /** Store 失敗注入：updateData 一律拋 [failWith]（存取層失敗模擬）；null＝健康。 */
    private fun bridgeStore(failWith: Throwable? = null): PrivilegeBridgeStore {
        val base = InMemoryPreferencesDataStore()
        if (failWith == null) {
            return PrivilegeBridgeStore(base)
        }
        val failing = object : DataStore<Preferences> by base {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw failWith
            }
        }
        return PrivilegeBridgeStore(failing)
    }

    /** 預置 bridgeSwitchOn/bridgeGranted=true＋一筆 DENY 審計，便於驗撤權清空。 */
    private fun newState() = dev.librepocket.shell.PrivilegeBridgeState(
        bridgeSwitchOn = true,
        bridgeGranted = true,
    ).also { it.audit.record(listOf("id"), "B1-revoke", "DENY") }

    @Test fun success_returnsFalseAndCallsRevoke() = runBlocking {
        val store = bridgeStore()
        val state = newState()
        assertEquals(1, state.auditCount())

        var withdrawCalls = 0
        val result = executePrivilegeRevoke(
            privilegeState = state,
            privilegeStore = store,
            withdraw = { withdrawCalls += 1 },
            isScopeActive = { true },
        )

        assertFalse("撤權成功必回傳 false（呼叫端可隱藏 banner）", result)
        assertEquals("withdraw scope 必已執行恰一次", 1, withdrawCalls)
        assertEquals("撤權必清空審計表", 0, state.auditCount())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertNull("持久授權必已清除", store.load())
    }

    @Test fun withdrawThrows_returnsTrue() = runBlocking {
        // Store 健康、withdraw 分別拋 IOException／IllegalStateException → 皆回傳 true。
        val state1 = newState()
        val ioResult = executePrivilegeRevoke(
            privilegeState = state1,
            privilegeStore = bridgeStore(),
            withdraw = { throw java.io.IOException("simulated withdraw io failure") },
            isScopeActive = { true },
        )
        assertTrue("IOException 不得逃出 executePrivilegeRevoke", ioResult)
        assertFalse(state1.bridgeGranted)

        val state2 = newState()
        val iseResult = executePrivilegeRevoke(
            privilegeState = state2,
            privilegeStore = bridgeStore(),
            withdraw = { throw IllegalStateException("simulated withdraw boom") },
            isScopeActive = { true },
        )
        assertTrue("IllegalStateException 不得逃出 executePrivilegeRevoke", iseResult)
        assertFalse(state2.bridgeGranted)
    }

    @Test fun accessLayerCancellation_withActiveScope_returnsTrue() = runBlocking {
        // store.clear() 拋 CancellationException（DataStore teardown 等存取層 CE），
        // UI Job 仍 active ⇒ 不得上拋、必須回傳 true（banner＋重試）。
        val store = bridgeStore(CancellationException("simulated store cancellation"))
        val state = newState()

        val result = executePrivilegeRevoke(
            privilegeState = state,
            privilegeStore = store,
            withdraw = { /* 盡力撤銷仍應執行；此例無需關注 */ },
            isScopeActive = { true },
        )

        assertTrue("存取層 CE＋scope active ⇒ 回傳 true（否則 banner 被吞）", result)
        assertFalse("記憶體旗標仍必須落 false（fail-closed）", state.bridgeGranted)
    }

    @Test fun accessLayerCancellation_withInactiveScope_rethrowsCancellation() {
        // 同一存取層 CE，但 UI Job 已取消（isScopeActive() == false）⇒
        // 真正的外部取消，CE 原樣上拋（不 banner）。
        val store = bridgeStore(CancellationException("simulated store cancellation"))
        val state = newState()

        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                executePrivilegeRevoke(
                    privilegeState = state,
                    privilegeStore = store,
                    withdraw = {},
                    isScopeActive = { false },
                )
            }
        }

        assertEquals("simulated store cancellation", error.message)
    }

    @Test fun virtualMachineError_isRethrown() {
        // store.clear() 拋 OutOfMemoryError ⇒ VirtualMachineError 原樣上拋（fatal）。
        val store = bridgeStore(OutOfMemoryError("simulated oom"))
        val state = newState()

        val error = assertThrows(OutOfMemoryError::class.java) {
            runBlocking {
                executePrivilegeRevoke(
                    privilegeState = state,
                    privilegeStore = store,
                    withdraw = {},
                    isScopeActive = { true },
                )
            }
        }

        assertEquals("simulated oom", error.message)
    }

    @Test fun withdrawCancellation_withActiveScope_returnsTrue() = runBlocking {
        // withdraw scope 拋 CE（Shizuku 側 teardown 等），UI Job 仍 active ⇒
        // 回傳 true，不上拋。
        val store = bridgeStore()
        val state = newState()
        var withdrawCalled = false

        val result = executePrivilegeRevoke(
            privilegeState = state,
            privilegeStore = store,
            withdraw = {
                withdrawCalled = true
                throw CancellationException("simulated withdraw cancellation")
            },
            isScopeActive = { true },
        )

        assertTrue(result)
        assertTrue(withdrawCalled)
        assertFalse(state.bridgeGranted)
    }

    @Test fun withdrawCancellation_withInactiveScope_rethrowsCancellation() {
        // withdraw scope 拋 CE，且 UI Job 已取消（isScopeActive() == false）⇒
        // 真正的外部取消，CE 原樣上拋（不 banner）。
        val store = bridgeStore()
        val state = newState()

        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                executePrivilegeRevoke(
                    privilegeState = state,
                    privilegeStore = store,
                    withdraw = { throw CancellationException("simulated withdraw cancellation") },
                    isScopeActive = { false },
                )
            }
        }

        assertEquals("simulated withdraw cancellation", error.message)
    }

    @Test fun revokeFails_flagRemainsFalse() = runBlocking {
        // 撤權失敗後記憶體旗標仍落 false（fail-closed；banner 由呼叫端顯示）。
        val store = bridgeStore(IllegalStateException("simulated clear boom"))
        val state = newState()

        val result = executePrivilegeRevoke(
            privilegeState = state,
            privilegeStore = store,
            withdraw = {},
            isScopeActive = { true },
        )

        assertTrue(result)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun failure_withInactiveScope_nonCancellation_returnsTrue() = runBlocking {
        // 非 CE 失敗（store.clear 拋 IllegalStateException）＋ scope 已取消 ⇒ 仍回傳 true：
        // 「外部取消 ⇒ 上拋」分支只認 CE 型別（CE-type discriminator），非 CE 一律 banner。
        val store = bridgeStore(IllegalStateException("simulated store failure"))
        val state = newState()

        val result = executePrivilegeRevoke(
            privilegeState = state,
            privilegeStore = store,
            withdraw = {},
            isScopeActive = { false },
        )

        assertTrue("非 CE 失敗＋inactive scope ⇒ 回傳 true（banner＋重試）", result)
        assertFalse("記憶體旗標仍必須落 false（fail-closed）", state.bridgeGranted)
    }
}
