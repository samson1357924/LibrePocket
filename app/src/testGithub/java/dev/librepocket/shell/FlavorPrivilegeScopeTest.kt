package dev.librepocket.shell

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.privilege.github.ShizukuGrantPolicy
import dev.librepocket.privilege.github.ShizukuGrantStore
import dev.librepocket.privilege.github.ShizukuPermissionRequests
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlavorPrivilegeScopeTest {

    @Before
    fun setUp() {
        deleteSharedDataStores()
    }

    @After
    fun tearDown() {
        // hook 必須先清空：resetProcessForTest 會 detach 舊實例，殘留 hook 會污染後續測試。
        FlavorPrivilegeScope.grantStoreClearForTest = null
        deleteSharedDataStores()
        ShizukuPermissionRequests.resetProcessForTest()
    }

    /**
     * github withdraw 以 context-constructor 觸碰共用 DataStore
     * （`filesDir/datastore/librepocket_shizuku_grant.preferences_pb`）；
     * bridge 測試（src/test 共用）側另有 `librepocket_privilege_bridge.preferences_pb`。
     * 兩套授權 DataStore 測試間若殘留會跨案例／跨 class 污染斷言，setUp／tearDown 皆刪除。
     */
    private fun deleteSharedDataStores() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            "datastore/librepocket_privilege_bridge.preferences_pb",
            "datastore/librepocket_privilege_bridge.preferences_pb.tmp",
            "datastore/librepocket_shizuku_grant.preferences_pb",
            "datastore/librepocket_shizuku_grant.preferences_pb.tmp",
        ).forEach { rel ->
            runCatching {
                java.nio.file.Files.deleteIfExists(File(context.filesDir, rel).toPath())
            }
        }
    }

    @Test
    fun withdrawClearFailure_throwsClearError_andRemovesUpstreamListener() = runBlocking {
        // detach 成功（fake removeListener 計數）＋ clear 失敗（以 grantStoreClearForTest
        // 注入：filesDir 級 sabotage 對 JVM 內共享的 shizukuGrantDataStore delegate
        // 不可靠——delegate 綁定首位實際使用者的 filesDir，與測試類順序相關，故改走
        // 測試 hook；真實 clear 的持久語義由 withdrawDetachFailure_throwsDetachError_
        // andStoreIsCleared（真實 clear）與 ShizukuGrantStoreRobolectricTest 承證）→
        // withdraw 抛 clear 腿的例外（detach 無失敗可供附加，suppressed 空）；
        // detach 先行執行：removeListener 恰被呼叫一次且守衛 false。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clearBoom = IOException("simulated clear boom")
        var removeCount = 0
        val fake = ShizukuPermissionRequests(
            addListener = { },
            removeListener = { removeCount++ },
        )
        ShizukuPermissionRequests.setProcessForTest(fake)
        fake.attach()
        assertTrue("fake attach must succeed or this test is vacuous", fake.isAttached())
        FlavorPrivilegeScope.grantStoreClearForTest = { throw clearBoom }
        try {
            val error = assertThrows(IOException::class.java) {
                runBlocking { FlavorPrivilegeScope.withdraw(context) }
            }
            assertSame("clear 的失敗必為主失敗（同一實例）", clearBoom, error)
            assertEquals("removeListener 必被呼叫恰一次", 1, removeCount)
            assertFalse(fake.isAttached())
            assertEquals("detach 腿成功 → 主失敗即 clear 例外，無 suppressed 附加", 0, error.suppressed.size)
        } finally {
            FlavorPrivilegeScope.grantStoreClearForTest = null
        }
    }

    @Test
    fun withdrawDetachAndClearFailure_primaryIsDetachError_clearErrorSuppressed() = runBlocking {
        // 兩段皆失敗 → 主失敗為 detach 的 AssertionError（同一實例，經 grantStoreClearForTest
        // hook 注入 clear 腿 IOException），clear 的例外以 suppressed 附加——
        // detach 失敗後 clear 仍照常執行；順序 detach→clear 隱含釘住（若 clear 先執行
        // 或未入 suppressed，兩斷言擇一必紅）。
        // 註：detach 失敗以 AssertionError 觸發，理由同 withdrawDetachFailure 測試。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val boom = AssertionError("simulated detach boom")
        val clearBoom = IOException("simulated clear boom")
        var removeCalls = 0
        val fake = ShizukuPermissionRequests(
            addListener = { },
            removeListener = { removeCalls++; throw boom },
        )
        ShizukuPermissionRequests.setProcessForTest(fake)
        fake.attach()
        assertTrue("fake attach must succeed or this test is vacuous", fake.isAttached())
        FlavorPrivilegeScope.grantStoreClearForTest = { throw clearBoom }
        try {
            val error = assertThrows(AssertionError::class.java) {
                runBlocking { FlavorPrivilegeScope.withdraw(context) }
            }
            assertSame("detach 的失敗必為主失敗（同一實例）", boom, error)
            assertEquals(
                "suppressed 必恰含 clear 的 IOException（同一實例），不得多附",
                1,
                error.suppressed.size,
            )
            assertSame("suppressed 的該例外必為 clear boom（同一實例）", clearBoom, error.suppressed.single())
            assertFalse(fake.isAttached())
            assertEquals("detach 恰反註冊一次橋接 listener", 1, removeCalls)
        } finally {
            FlavorPrivilegeScope.grantStoreClearForTest = null
        }
    }

    @Test
    fun withdrawDetachFailure_clearVirtualMachineError_clearFatalPropagates_detachFailureSuppressed() = runBlocking {
        // detach 腿失敗（非 fatal）＋ clear 腿拋 VirtualMachineError：
        // fatal 不得淪為 suppressed——withdraw 拋 clear 腿的 OutOfMemoryError（同一實例），
        // detach 腿的失敗反向附加為其 suppressed。
        // 註：detach() 會吞 Exception 與 LinkageError（僅記錄 logcat），IOException 永遠逃不出
        // detach()，故非 fatal 的 detach 失敗假件以 AssertionError 觸發（同 withdrawDetachFailure_*
        // 諸測試的既有 idiom）；本測試釘的是「第二腿 fatal 不被既有非 fatal 主失敗掩蓋」。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val boom = AssertionError("simulated detach boom")
        val clearFatal = OutOfMemoryError("simulated clear boom")
        var removeCalls = 0
        val fake = ShizukuPermissionRequests(
            addListener = { },
            removeListener = { removeCalls++; throw boom },
        )
        ShizukuPermissionRequests.setProcessForTest(fake)
        fake.attach()
        assertTrue("fake attach must succeed or this test is vacuous", fake.isAttached())
        FlavorPrivilegeScope.grantStoreClearForTest = { throw clearFatal }
        try {
            val error = assertThrows(OutOfMemoryError::class.java) {
                runBlocking { FlavorPrivilegeScope.withdraw(context) }
            }
            assertSame("clear 腿的 fatal 必為主失敗（同一實例）", clearFatal, error)
            assertTrue(
                "suppressed 必含 detach 腿的 AssertionError（同一實例），實際 suppressed=${error.suppressed.toList()}",
                error.suppressed.any { it === boom },
            )
            assertEquals("detach 恰反註冊一次橋接 listener", 1, removeCalls)
        } finally {
            FlavorPrivilegeScope.grantStoreClearForTest = null
        }
    }

    @Test
    fun withdrawDetachVirtualMachineError_clearIoException_firstFatalPropagates_clearErrorSuppressed() = runBlocking {
        // detach 腿 fatal（OOM）＋ clear 腿 IOException：
        // first-fatal-wins——withdraw 拋 detach 腿的 OutOfMemoryError（同一實例），
        // clear 腿的 IOException 以 suppressed 附加。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val boom = OutOfMemoryError("simulated detach boom")
        val clearBoom = IOException("simulated clear boom")
        var removeCalls = 0
        val fake = ShizukuPermissionRequests(
            addListener = { },
            removeListener = { removeCalls++; throw boom },
        )
        ShizukuPermissionRequests.setProcessForTest(fake)
        fake.attach()
        assertTrue("fake attach must succeed or this test is vacuous", fake.isAttached())
        FlavorPrivilegeScope.grantStoreClearForTest = { throw clearBoom }
        try {
            val error = assertThrows(OutOfMemoryError::class.java) {
                runBlocking { FlavorPrivilegeScope.withdraw(context) }
            }
            assertSame("首個 fatal（detach 腿）必為主失敗（同一實例）", boom, error)
            assertTrue(
                "suppressed 必含 clear 腿的 IOException（同一實例），實際 suppressed=${error.suppressed.toList()}",
                error.suppressed.any { it === clearBoom },
            )
            assertFalse(fake.isAttached())
            assertEquals("detach 恰反註冊一次橋接 listener", 1, removeCalls)
        } finally {
            FlavorPrivilegeScope.grantStoreClearForTest = null
        }
    }

    @Test
    fun withdrawDetachAndClearVirtualMachineError_firstFatalWins_detachOomSuppressesClearOom() = runBlocking {
        // detach 腿 fatal ＋ clear 腿亦 fatal：first-fatal-wins——
        // withdraw 拋 detach 腿的 OutOfMemoryError（同一實例），clear 腿的 OOM 以 suppressed 附加。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val boom = OutOfMemoryError("simulated detach boom")
        val clearFatal = OutOfMemoryError("simulated clear boom")
        var removeCalls = 0
        val fake = ShizukuPermissionRequests(
            addListener = { },
            removeListener = { removeCalls++; throw boom },
        )
        ShizukuPermissionRequests.setProcessForTest(fake)
        fake.attach()
        assertTrue("fake attach must succeed or this test is vacuous", fake.isAttached())
        FlavorPrivilegeScope.grantStoreClearForTest = { throw clearFatal }
        try {
            val error = assertThrows(OutOfMemoryError::class.java) {
                runBlocking { FlavorPrivilegeScope.withdraw(context) }
            }
            assertSame("首個 fatal（detach 腿）必為主失敗（同一實例）", boom, error)
            assertTrue(
                "suppressed 必含 clear 腿的 OutOfMemoryError（同一實例），實際 suppressed=${error.suppressed.toList()}",
                error.suppressed.any { it === clearFatal },
            )
            assertFalse(fake.isAttached())
            assertEquals("remove calls must equal 1", 1, removeCalls)
        } finally {
            FlavorPrivilegeScope.grantStoreClearForTest = null
        }
    }

    @Test
    fun withdrawDetachFailure_throwsDetachError_andStoreIsCleared() = runBlocking {
        // detach 失敗＋clear 成功 → withdraw 抛 detach 的 AssertionError（同一實例）；
        // 持久 Store 仍被 clear（load()==null）——detach 失敗不得阻止跨域清庫。
        // 註：detach() 會吞 Exception 與 LinkageError（僅記錄 logcat）；能逃出 detach()
        // 的 Throwable 只剩「非 Exception 且非 LinkageError 的 Error 類」，故假件以
        // AssertionError 觸發 detach 失敗——本測試釘 withdraw() 的合成邏輯（detach 失敗
        // 不短路 clear），不代表 detach() 對 Exception 會上拋至 withdraw()。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val boom = AssertionError("simulated detach boom")
        val fake = ShizukuPermissionRequests(
            addListener = { },
            removeListener = { throw boom },
        )
        ShizukuPermissionRequests.setProcessForTest(fake)
        fake.attach()
        assertTrue("fake attach must succeed or this test is vacuous", fake.isAttached())
        val grantStore = ShizukuGrantStore(context)
        val grant = ShizukuGrantPolicy.grant(
            providerId = "withdraw-detach-failure",
            configRevision = 1L,
            serviceVersion = 13,
        )
        grantStore.save(grant)
        assertEquals("save 後 load 必回傳等值授權（non-vacuous pin）", grant, grantStore.load())

        val error = assertThrows(AssertionError::class.java) {
            runBlocking { FlavorPrivilegeScope.withdraw(context) }
        }
        assertSame("detach 的失敗必為主失敗（同一實例）", boom, error)
        assertNull("detach 失敗不得阻止 clear：持久授權必須已被清除", grantStore.load())
    }

    @Test
    fun withdraw_detachesUpstreamListener() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 重置 singleton，避免其他測試殘留狀態影響斷言
        ShizukuPermissionRequests.resetProcessForTest()
        val requests = ShizukuPermissionRequests.process()
        // Robolectric 環境下 Shizuku clinit 成功、attach 可成功置守衛；withdraw 應走 detach()
        // （反註冊上游 + 清表 + 守衛 false），不得殘留 attached=true。
        // 此測試在舊 invalidatePending 實作（只作廢/清表、不置守衛 false）上會紅，
        // 是 withdraw→detach 路徑的有效 pin。
        requests.attach()
        // 自我驗證：attach 在此環境下必須成功（否則本測試對 withdraw→detach 無鑑別力）。
        assertTrue("attach must succeed under Robolectric or this test is vacuous", requests.isAttached())
        FlavorPrivilegeScope.withdraw(context)
        assertFalse(ShizukuPermissionRequests.process().isAttached())
    }

    @Test
    fun withdrawClearsGrantStoreAndInvalidatesPendingRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val grantStore = ShizukuGrantStore(context)
        val grant = ShizukuGrantPolicy.grant(
            providerId = "test-provider",
            configRevision = 1L,
            serviceVersion = 13,
        )
        grantStore.save(grant)
        assertEquals("save 後 load 必回傳等值授權（non-vacuous pin）", grant, grantStore.load())

        val requests = ShizukuPermissionRequests.process()
        var callbackFired = false
        val oldEpoch = requests.currentEpoch()
        assertTrue("registration must succeed or this test is vacuous", requests.onResult(1001) { callbackFired = true })

        FlavorPrivilegeScope.withdraw(context)

        // Store is cleared
        assertNull(grantStore.load())
        // Pending callback under old epoch is invalidated and cleared
        requests.emitResult(1001, android.content.pm.PackageManager.PERMISSION_GRANTED, epoch = oldEpoch)
        assertFalse(callbackFired)
        // Requests process epoch has advanced
        assertTrue(requests.currentEpoch() > oldEpoch)
    }

    @Test
    fun grantStore_reusesSameStoreForSameApplicationContext_andRebindsOnIdentityChange() = runBlocking {
        // 行程單例快取契約（#136-B3 前置清理項）：同一 applicationContext 身分的
        // 連續 withdraw 復用同一 Store 實例；身分改變時換綁全新 Store（避免持有
        // 已過期 Context 寫入舊 filesDir 的 marker）。preferencesDataStore
        // delegate 本身行程內共享且不重綁（僅綁定首位 accessor 的 filesDir），
        // 換綁不改變 delegate 落盤路徑——僅對齊 marker／旗標 owner。
        // 反射讀取 cachedStore 以釘住身分語義（避免反射依賴 production 型別簽章）。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cachedField = FlavorPrivilegeScope::class.java.getDeclaredField("cachedStore")
            .apply { isAccessible = true }
        val contextField = FlavorPrivilegeScope::class.java.getDeclaredField("cachedStoreContext")
            .apply { isAccessible = true }
        try {
            FlavorPrivilegeScope.withdraw(context)
            val first = cachedField.get(FlavorPrivilegeScope)
            assertTrue("首次 withdraw 必建立並快取 Store", first != null)
            FlavorPrivilegeScope.withdraw(context)
            val second = cachedField.get(FlavorPrivilegeScope)
            assertSame("同一 applicationContext 身分的連續 withdraw 必復用快取 Store", first, second)

            // 身分改變：ApplicationContext 回傳自身的 wrapper → 觸發換綁
            val distinct = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
            }
            FlavorPrivilegeScope.withdraw(distinct)
            val third = cachedField.get(FlavorPrivilegeScope)
            assertTrue("換綁後必換成全新 Store 實例", third != null && third !== second)
        } finally {
            // 清快取避免跨案例污染（重置為 null＝下次 withdraw 重新建立）
            cachedField.set(FlavorPrivilegeScope, null)
            contextField.set(FlavorPrivilegeScope, null)
        }
    }
}
