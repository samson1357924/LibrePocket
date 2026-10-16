package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class SlowRefreshListProvider(
    val entered: CountDownLatch = CountDownLatch(1),
    val release: CountDownLatch = CountDownLatch(1),
    val slowDone: CountDownLatch = CountDownLatch(1),
    val calls: AtomicInteger = AtomicInteger(0),
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()

    override suspend fun listModels(): List<String> {
        val n = calls.incrementAndGet()
        if (n == 1) {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("slow refresh gate timeout")
            try {
                delay(300)
                return listOf("slow-r1", "slow-r2", "slow-r3", "slow-r4", "slow-r5")
            } finally {
                slowDone.countDown()
            }
        } else {
            return listOf("fast-r1", "fast-r2")
        }
    }
}

/**
 * Stage3 (#17) 收尾：編輯端點沿用 key 邊界＋非同步 revision barrier。
 *
 * 只增測試，不改生產語義：
 * - 身份比對維持 trim 全字串相等（fail-closed，嚴於 origin）：同端點（同
 *   preset＋同 effectiveBaseUrl 全字串）只改 model 免重貼；跨 origin（不同
 *   host／port）不沿用；同 origin 僅差尾斜線重拼亦不沿用（distinct entity，
 *   須重貼）。空白 key probe（testConnection／refreshModels）恆為 NEW——
 *   需重貼的摩擦語義，不把 vault 讀引入 probe（零 hasKey／getKey）。
 * - 8 字元下限硬編碼保留：7 字元在 NEW／EDIT_KEEP_EXISTING 皆拒，8 字元放行。
 * - 非同步 barrier：testConnection 慢 A→B 丟棄已由 SetupEditKeepExistingTest
 *   釘住；本檔補 refreshModels 慢 A→B 丟棄（generation＋entryRevision 雙閘，
 *   modelRefreshGeneration 獨立於 testConnectionGeneration）。對話框期間編輯
 *   （mint→dialog 隱藏、dialog 期間 probe silent-return）與 model-race 提交語義
 *   （dialogModelRaceCommitsLiveSnapshot、staged／keepExisting 回寫）已由
 *   SetupKeyWriteIntentTest／SetupKeyWriteDialogReentryTest 釘住，本檔不重複。
 * - 絕不回填明文：全程假 key，不讀 live 內容（僅 hasKey 存在性），不碰 SavedState。
 *
 * Compose／裝置端到端缺口（如實標註）：本檔 JVM 釘住 VM 層 generation／fence／
 * 零 vault-讀語義；SetupScreen 對話框期間 disabled（saving／confirmKeyWrite／
 * confirmRebind 三閘＋model 雙閘）與真機往返（dots 顯示、重貼摩擦、旋轉儲存）
 * 需手動驗證（gust 意圖），此處不虛構裝置結論。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupEditBoundaryAndBarrierTest {

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

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("setup-boundary-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "boundary.preferences_pb") },
            ),
        )
    }

    private fun newVm(
        store: EndpointStore,
        vault: KeyVault,
        policy: PolicyStore,
        buildProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider },
        fetchDirectory: (suspend (String) -> String)? = null,
    ): SetupViewModel = if (fetchDirectory == null) {
        SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = buildProvider,
        )
    } else {
        SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = buildProvider,
            fetchDirectory = fetchDirectory,
        )
    }

    private suspend fun awaitSaveIdle(vm: SetupViewModel) {
        withTimeout(10_000) { while (vm.form.value.saving) delay(5) }
    }

    private suspend fun awaitModelsIdle(vm: SetupViewModel) {
        withTimeout(10_000) { while (vm.form.value.modelsLoading) delay(5) }
    }

    private fun openAiConfig(model: String = "m-old") = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun customConfig(url: String, model: String = "local"): EndpointConfig {
        val id = SetupValidation.deriveEndpointId("custom", url)
        return EndpointConfig(
            providerId = id,
            presetId = "custom",
            label = "自訂",
            baseUrl = url,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = model,
            apiKeyRef = SetupValidation.deriveApiKeyRef(id),
        )
    }

    @Test
    fun customModelOnlyReuseKeepsPerEndpointId() = runBlocking {
        // 同端點（同 custom URL 全字串）只改 model：空白 key 免重貼，metadata-only。
        val url = "http://127.0.0.1:11441/v1"
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val expectedId = SetupValidation.deriveEndpointId("custom", url)
        vault.putKey(expectedId, "fake-boundary-custom-key-AAAAAAAA".toCharArray())
        store.save(customConfig(url))
        val putsBefore = vault.putKeys
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW))
        vm.prefillForEdit(customConfig(url))
        vm.onModelChange("local-new")
        vm.save()
        awaitSaveIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        val live = withTimeout(10_000) { store.snapshotStrict() }
        assertEquals(expectedId, live?.providerId)
        assertEquals(url, live?.baseUrl)
        assertEquals("local-new", live?.model)
        // live ref 保留（同一固定槽），零 key-material I/O（僅存在性 hasKey）。
        assertEquals(SetupValidation.deriveApiKeyRef(expectedId), live?.apiKeyRef)
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(1, vault.hasKeys)
    }

    @Test
    fun customCrossOriginBlankRequiresRepasteWithZeroVaultIo() = runBlocking {
        // 跨 origin（同 preset、不同 port 即不同 origin）不沿用：空白 key 回落 NEW，
        // 須重貼（SETUP_KEY_TOO_SHORT），零 vault 接觸。
        val urlA = "http://127.0.0.1:11442/v1"
        val urlB = "http://127.0.0.1:11443/v1"
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val idA = SetupValidation.deriveEndpointId("custom", urlA)
        vault.putKey(idA, "fake-boundary-origin-key-BBBBBBBB".toCharArray())
        store.save(customConfig(urlA))
        val putsBefore = vault.putKeys
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW))
        vm.prefillForEdit(customConfig(urlA))
        vm.onBaseUrlChange(urlB)
        vm.save()
        awaitSaveIdle(vm)
        // 空白 key＋身份已變：NEW 模式長度閘先擋（摩擦：請重貼），非 POLICY_DENIED。
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.hasKeys)
        assertTrue(store.hasMetadata())
        assertEquals("local", withTimeout(10_000) { store.snapshotStrict() }?.model)
    }

    @Test
    fun customTrailingSlashRespellingRequiresRepaste() = runBlocking {
        // 同 origin 僅差尾斜線重拼：全字串比對（嚴於 origin）視為 distinct entity，
        // 空白 key 不沿用，須重貼。證明身份比對非 origin 比較。
        val url = "http://127.0.0.1:11444/v1"
        val respelled = "http://127.0.0.1:11444/v1/"
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val id = SetupValidation.deriveEndpointId("custom", url)
        vault.putKey(id, "fake-boundary-slash-key-CCCCCCCC".toCharArray())
        store.save(customConfig(url))
        val putsBefore = vault.putKeys
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW))
        vm.prefillForEdit(customConfig(url))
        vm.onBaseUrlChange(respelled)
        vm.save()
        awaitSaveIdle(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.hasKeys)
        assertTrue(store.hasMetadata())
    }

    @Test
    fun blankProbeAlwaysNewWithZeroVaultIoAndFrictionCode() = runBlocking {
        // 空白 key probe 恆 NEW：編輯入口（prefill 後空白）按測試／更新皆報
        // SETUP_KEY_TOO_SHORT（摩擦：請重貼再測），不讀 vault（零 hasKey／getKey），
        // 不評估 policy、不構造 provider——即使 live key 存在亦然。
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        vault.putKey("preset:openai", "fake-boundary-probe-key-DDDDDDDD".toCharArray())
        store.save(openAiConfig())
        val hasBefore = vault.hasKeys
        val getBefore = vault.getKeys
        val putsBefore = vault.putKeys
        val policy = VerdictPolicy(Verdict.ALLOW)
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(
            store = store,
            vault = vault,
            policy = policy,
            buildProvider = { _, _ ->
                buildCalls.incrementAndGet()
                error("probe must not construct provider on blank key") as LlmProvider
            },
            fetchDirectory = { _: String ->
                dirCalls.incrementAndGet()
                """{"openai":{"models":{}}}"""
            },
        )
        vm.prefillForEdit(openAiConfig())
        assertEquals("", vm.form.value.apiKey)
        vm.testConnection()
        withTimeout(10_000) { while (vm.form.value.testing) delay(5) }
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertNull(vm.form.value.testModels)
        assertFalse(vm.form.value.testing)
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertFalse(vm.form.value.modelsLoading)
        // 全程零 I/O：policy 未評估、provider 未構造、目錄未抓、vault 未讀。
        assertEquals(0, policy.freshCalls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(hasBefore, vault.hasKeys)
        assertEquals(getBefore, vault.getKeys)
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
    }

    @Test
    fun eightCharFloorHoldsInBothModes() {
        // 8 字元下限硬編碼保留：7 字元在 NEW／EDIT_KEEP_EXISTING 皆拒；
        // 8 字元 NEW 放行；空白僅 EDIT_KEEP_EXISTING 放行（probe 恆 NEW 故仍拒）。
        val url = "https://api.openai.com/v1"
        assertEquals(
            "SETUP_KEY_TOO_SHORT",
            SetupValidation.validate("openai", url, "1234567", SetupValidation.KeyMode.NEW),
        )
        assertEquals(
            "SETUP_KEY_TOO_SHORT",
            SetupValidation.validate("openai", url, "1234567", SetupValidation.KeyMode.EDIT_KEEP_EXISTING),
        )
        assertNull(
            SetupValidation.validate("openai", url, "12345678", SetupValidation.KeyMode.NEW),
        )
        assertNull(
            SetupValidation.validate("openai", url, "", SetupValidation.KeyMode.EDIT_KEEP_EXISTING),
        )
        assertEquals(
            "SETUP_KEY_TOO_SHORT",
            SetupValidation.validate("openai", url, "", SetupValidation.KeyMode.NEW),
        )
    }

    @Test
    fun slowRefreshDoesNotOverwriteNewer() = runBlocking {
        // 非同步 revision barrier（refresh 側）：慢 A 完成晚於快 B，舊結果丟棄。
        // 與 SetupEditKeepExistingTest.slowTestConnectionDoesNotOverwriteNewer 對稱；
        // 雙閘（entryRevision＋modelRefreshGeneration）缺一即此測試轉紅。
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val fake = SlowRefreshListProvider()
        val vm = newVm(
            store = store,
            vault = vault,
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = { _, _ -> fake },
            fetchDirectory = { _: String -> """{"openai":{"models":{}}}""" },
        )
        vm.onApiKeyChange("fake-boundary-slow-key-EEEEEEEE")
        vm.refreshModels()
        assertTrue("slow refresh never entered", fake.entered.await(10, TimeUnit.SECONDS))
        // 慢嘗試阻塞中編輯 key＋重刷：新 generation B 必須成為終值。
        vm.onApiKeyChange("fake-boundary-fast-key-FFFFFFFF")
        vm.refreshModels()
        fake.release.countDown()
        assertTrue("slow refresh never finished", fake.slowDone.await(10, TimeUnit.SECONDS))
        awaitModelsIdle(vm)
        // 讓 stale 寫回窗口先落定（若有），再斷言終值——否則 pre-barrier 覆寫會誤判綠。
        delay(500)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        val options = vm.form.value.modelOptions
        assertTrue("fast result must be terminal, got $options", options.containsAll(listOf("fast-r1", "fast-r2")))
        assertTrue("slow result must be discarded, got $options", options.none { it.startsWith("slow-r") })
        assertEquals(2, fake.calls.get())
    }
}
