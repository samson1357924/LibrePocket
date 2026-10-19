package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 階段2 VM：logout `logoutInFlight` 語義（Re-review #7 Finding 2）。
 *
 * - 進入持鎖前同步置 true（含排隊窗口），成功經整表重置、失敗經結尾復位、
 *   取消（含排隊取消）經外層 catch 復位（仿 saving try-catch outside withLock）。
 * - 失敗保留 errorCode + form（CLEAR_FAILED），成功重置；cancel 不調 onDone
 *   （既有 [SetupLogoutClearTest.logoutCancelBetweenDeleteAndClear] 覆蓋，
 *   此處鎖定 inFlight 部分）；[SetupViewModel.clearLogoutError] 只清 LOGOUT_*。
 *
 * 沿用同包既有樁（[CountingVault]、[MutablePolicy]、[DeleteGatingLogoutVault]、
 * [SuspendPolicy]、[GatedUpdateDataStore]、[LogoutClearFailingDataStore]），
 * 不動 KeyVault/schema。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupLogoutInFlightTest {
    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun rawStore(dirName: String): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val dir = Files.createTempDirectory(dirName).toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "logout-inflight.preferences_pb") },
        )
    }

    private val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
        { _, _ -> error("no provider needed") as LlmProvider }

    private fun inFlightPreseed(model: String = "m-old"): EndpointConfig = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    @Test
    fun inFlightSetBeforeLockAndResetOnClearFailureWithFormRetained() {
        // delete Parks（持鎖內）→ 可觀測 inFlight 已置 true；放行後 clear 連敗
        // 兩次 → CLEAR_FAILED：errorCode 保留、form 保留、inFlight 復位。
        val raw = rawStore("logout-inflight-clear-fail")
        val realStore = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            realStore.save(inFlightPreseed())
            assertEquals(1L, realStore.currentRevision())
        }
        val store = EndpointStore(LogoutClearFailingDataStore(raw, passThroughWrites = 1))
        val gating = DeleteGatingLogoutVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { gating },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val typedKey = "typed-inflight-form-key-abcdef"
        vm.onApiKeyChange(typedKey)
        assertFalse(vm.form.value.logoutInFlight)
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        // 入口同步置 true（持鎖前，排隊窗口亦然）。
        assertTrue(vm.form.value.logoutInFlight)
        runBlocking { withTimeout(10_000) { gating.deleteEntered.await() } }
        assertTrue("inFlight must stay true while the lock body runs", vm.form.value.logoutInFlight)
        gating.deleteRelease.complete(Unit)
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertFalse("clear failure must not report success", ok)
        assertEquals("LOGOUT_CLEAR_FAILED", vm.form.value.errorCode)
        assertFalse("failure must reset inFlight", vm.form.value.logoutInFlight)
        assertEquals("failure must preserve the form", typedKey, vm.form.value.apiKey)
    }

    @Test
    fun successResetsInFlightAndError() {
        val raw = rawStore("logout-inflight-success")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(inFlightPreseed())
        }
        // Gate the success path before asserting the in-flight flag: without a
        // gate the IO delete/clear can complete before the next assertion and
        // reset the flag, making the assertion order-dependent (observed once
        // in a combined run). The adjacent tests use the same gate.
        val gating = DeleteGatingLogoutVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { gating },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        assertTrue(vm.form.value.logoutInFlight)
        runBlocking { withTimeout(10_000) { gating.deleteEntered.await() } }
        assertTrue("inFlight must stay true while the lock body runs", vm.form.value.logoutInFlight)
        gating.deleteRelease.complete(Unit)
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertTrue(ok)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.logoutInFlight)
        assertEquals("", vm.form.value.apiKey)
        assertEquals(1, gating.deleteKeys)
    }

    @Test
    fun cancelResetsInFlightWithoutCodeOrDone() {
        // clear 前設閘 + cancel：重拋，無碼、無 onDone，inFlight 復位。
        val raw = rawStore("logout-inflight-cancel")
        val gated = GatedUpdateDataStore(raw)
        val store = EndpointStore(gated)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(inFlightPreseed())
        }
        gated.gateBeforeUpdate = true
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { CountingVault(delegate) },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val typedKey = "typed-inflight-cancel-key-abcdef"
        vm.onApiKeyChange(typedKey)
        var onDoneCalled = false
        vm.logout { onDoneCalled = true }
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        assertTrue(vm.form.value.logoutInFlight)
        val job = vm.lastLogoutJobForTest ?: error("no logout job")
        runBlocking {
            job.cancel()
            job.join()
        }
        assertTrue(job.isCancelled)
        assertFalse("cancel must reset inFlight", vm.form.value.logoutInFlight)
        assertNull("cancel must not stamp any errorCode", vm.form.value.errorCode)
        assertFalse("cancel must not call onDone", onDoneCalled)
        assertEquals(typedKey, vm.form.value.apiKey)
    }

    @Test
    fun queuedCancelResetsInFlight() {
        // 排隊取消：save 持鎖停在 V2 閘（R3 起 V2 為第 3 次調用），logout 排隊
        // 等鎖時被 cancel；外層 catch（withLock 之外）必須復位 inFlight。
        val raw = rawStore("logout-inflight-queued-cancel")
        val store = EndpointStore(raw)
        val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = 3)
        val vault = CountingVault()
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        runBlocking { withTimeout(10_000) { policy.entered.await() } }
        assertTrue(vm.form.value.saving)
        var logoutDoneCalled = false
        vm.logout { logoutDoneCalled = true }
        assertTrue("inFlight is set synchronously before the lock", vm.form.value.logoutInFlight)
        // 讓 logout 排上 saveMutex（holder 不放，必為排隊取消；仿 saving t6）。
        runBlocking { delay(250) }
        val logoutJob = vm.lastLogoutJobForTest ?: error("no logout job")
        logoutJob.cancel()
        runBlocking { logoutJob.join() }
        assertTrue(logoutJob.isCancelled)
        assertFalse("queued cancel must reset inFlight", vm.form.value.logoutInFlight)
        assertNull("queued cancel must not stamp any errorCode", vm.form.value.errorCode)
        assertFalse("queued cancel must not call onDone", logoutDoneCalled)
        // holder 不受影響：放行後 save 續跑，但 logout 入口的 formRevision bump
        // 會絆倒 save 的 trailing fence（既有 save/logout 互動語義：logout 進入
        // 即作廢同 VM 在途 save），故 save 以 POLICY_DENIED 收尾——此碼來自 save
        // 圍欄，而非 logout 蓋碼（logout 自身全程無 stamp、無 onDone）。
        assertTrue(vm.form.value.saving)
        policy.release.complete(Unit)
        runBlocking {
            withTimeout(10_000) {
                while (vm.form.value.saving) delay(5)
            }
        }
        assertFalse(vm.form.value.saving)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse("logout never ran its body", vm.form.value.logoutInFlight)
        assertFalse(logoutDoneCalled)
    }

    @Test
    fun secondLogoutWhileInFlightIsIgnored() {
        val raw = rawStore("logout-inflight-double")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(inFlightPreseed())
        }
        val gating = DeleteGatingLogoutVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { gating },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        var doneCount = 0
        var doneValue = false
        vm.logout {
            doneCount++
            doneValue = it
        }
        runBlocking { withTimeout(10_000) { gating.deleteEntered.await() } }
        val firstJob = vm.lastLogoutJobForTest ?: error("no logout job")
        var secondDoneCalled = false
        vm.logout { secondDoneCalled = true }
        // 執行中再調忽略：不覆寫 job、不調第二次 onDone。
        assertSame(firstJob, vm.lastLogoutJobForTest)
        gating.deleteRelease.complete(Unit)
        runBlocking {
            withTimeout(10_000) {
                while (doneCount == 0) delay(5)
            }
        }
        assertEquals(1, doneCount)
        assertTrue(doneValue)
        assertFalse(secondDoneCalled)
        assertFalse(vm.form.value.logoutInFlight)
        assertNull(vm.form.value.errorCode)
        assertEquals(1, gating.deleteKeys)
    }

    @Test
    fun clearLogoutErrorClearsOnlyLogoutCodes() {
        // LOGOUT 碼可清（journal 寫即拋＋未達 delete：中性 UNKNOWN，可重試）。
        val raw = rawStore("logout-inflight-clear-code")
        val realStore = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            realStore.save(inFlightPreseed())
        }
        val vm = SetupViewModel(
            store = EndpointStore(LogoutClearFailingDataStore(raw)),
            vaultSource = VaultSource { CountingVault(delegate) },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        runBlocking { withTimeout(10_000) { done.await() } }
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        vm.clearLogoutError()
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.logoutInFlight)
        // 非 LOGOUT 碼保留（save 驗證碼同步寫入，無需協程）。
        val vm2 = SetupViewModel(
            store = EndpointStore(rawStore("logout-inflight-nologout-code")),
            vaultSource = VaultSource { CountingVault() },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm2.onApiKeyChange("short")
        vm2.save()
        assertEquals("SETUP_KEY_TOO_SHORT", vm2.form.value.errorCode)
        vm2.clearLogoutError()
        assertEquals("SETUP_KEY_TOO_SHORT", vm2.form.value.errorCode)
    }
}
