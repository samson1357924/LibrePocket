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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    val fastDone: CountDownLatch = CountDownLatch(1),
    val calls: AtomicInteger = AtomicInteger(0),
    /** A's ViewModel-coroutine Job, captured on the slow path inside listModels. */
    val aJob: CompletableDeferred<Job> = CompletableDeferred(),
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()

    /**
     * Deterministic slow/fast race: call order is fixed by the gate, not by
     * thread scheduling. Slow attempt A blocks in listModels until the test
     * releases it; fast attempt B returns immediately.
     *
     * Ordering is B-published-before-A-released: the test waits for B's
     * ViewModel-terminal value (options contain fast && !modelsLoading)
     * BEFORE counting down `release`, so A's stale writeback always lands
     * last. Pre-barrier this surfaces as a stale slow-r write (RED);
     * post-barrier it is discarded and the terminal stays fast-only (GREEN).
     * [calls] proves both ran.
     *
     * Prod structure (verified): `refreshModels()` launches ONE
     * `viewModelScope.launch(Dispatchers.IO)` coroutine per attempt with no
     * child launch and no `withContext` Job-hop, so `listModels()` executes
     * INSIDE A's coroutine — `currentCoroutineContext().job` captured here
     * IS the same Job that performs the guard check plus the possible
     * `_form.update` writeback. Joining it therefore proves writeback
     * completion, which no provider-return latch can: [fastDone]/`slowDone`
     * fire at provider return (listModels finally), i.e. BEFORE the
     * ViewModel's `_form.update`. A >2s IO stall between provider return
     * and writeback would let a `slowDone.await` + cancel-collector +
     * fast-assert sequence pass BEFORE a regressed writeback lands
     * (derivable false-green). The test joins [aJob] instead.
     *
     * [fastDone] fires at provider return, i.e. BEFORE the ViewModel's
     * `_form.update` publishes B — it stays only as a deadlock guard for
     * A's gate, never as an ordering signal. The stale-never-written pin is
     * the live history collector plus the [aJob] join in the test.
     * Residual: the collector observes StateFlow emissions and can still
     * conflate under pathological same-instant writes (accepted).
     */

    override suspend fun listModels(): List<String> {
        val n = calls.incrementAndGet()
        if (n == 1) {
            // Capture A's Job: complete() never suspends and returns false
            // (no-op) if already completed — safe at the head of the slow path.
            aJob.complete(currentCoroutineContext().job)
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("slow refresh gate timeout")
            // Deadlock guard only (not an ordering signal): wait until B's
            // provider call has returned so A's slow return always lands
            // last. B-published-before-A-released is pinned by the test's
            // ViewModel-terminal wait, not by this latch.
            if (!fastDone.await(10, TimeUnit.SECONDS)) error("fast completion gate timeout")
            return listOf("slow-r1", "slow-r2", "slow-r3", "slow-r4", "slow-r5")
        } else {
            try {
                return listOf("fast-r1", "fast-r2")
            } finally {
                fastDone.countDown()
            }
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
        // 組合 barrier（entryRevision＋modelRefreshGeneration＋state-revision 三重比對，
        // 見各 _form.update 守衛）：單獨移除任一維度此測試仍綠（另一維度照樣丟棄 A；
        // 另有 job-cancel＋fetchSnapshotOutcome 內 ensureActive 獨立抑制——僅中和
        // 寫回守衛而不停 cancel 時 A 仍死於 ensureActive）。僅兩維度同時移除（且
        // cancel 停用，否則 ensureActive 先殺 A）才轉紅為 stale slow-r 終值。
        // 以上皆經暫時性 src/main 中和實驗驗證（已 revert），非臆測。
        // 排序銷定：先等 B 的 ViewModel 終值（modelOptions 含 fast 且
        // !modelsLoading）再放行 A；B-published-before-A-released。fastDone 只
        // 是防死鎖，不作排序信號。stale-never-written 由放行前啟動的 live
        // collector（全程 emission 歷史）＋下方 aJob join（A 寫回已完成證明）
        // 共同釘住（終值後再訂閱的 first 會因 StateFlow conflation 漏看
        // 瞬態慢寫；同拍合併仍為已知殘留）。
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
        // FIRST wait for B's ViewModel-terminal publish (provider return is
        // NOT enough: fastDone fires in listModels' finally, before the
        // ViewModel's _form.update). Releasing A only after B's terminal value
        // is published pins B-published-before-A-released, so a regressed
        // barrier must surface as a stale slow write (RED) instead of an
        // A-then-B lucky overwrite (false green).
        val fast = listOf("fast-r1", "fast-r2")
        withTimeout(10_000) { vm.form.first { it.modelOptions.containsAll(fast) && !it.modelsLoading } }
        // History collector on an explicit scope started BEFORE releasing A:
        // StateFlow is conflated, so a post-hoc first{slow} subscribed after
        // idle would miss a transient slow overwritten by B; the live collector
        // observes every emission from B-terminal onward. The explicit histScope
        // (detached from the test's runBlocking scope) makes cancellation
        // explicit: even a handshake-timeout throw inside try still runs the
        // finally that cancels/joins the scope. (Residual: same-instant
        // conflated writes could still merge upstream — accepted.)
        val seen = Collections.synchronizedList(mutableListOf<Any>())
        val histScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        histScope.launch { vm.form.collect { seen.add(it.modelOptions.toList()) } }
        // Release slow AFTER B published so slow always finishes last:
        // pre-barrier overwrites with slow-r (RED), post-barrier discards
        // and the terminal stays fast-only (GREEN).
        // Completion proof is the [SlowRefreshListProvider.aJob] join below:
        // a provider-return latch (`slowDone`, now removed) fires BEFORE A's
        // `_form.update` writeback, so a >2s IO stall between provider return
        // and writeback would let latch-await + collector-cancel + fast-assert
        // pass before a regressed writeback lands (false-green). Joining A's
        // Job covers the guard check plus the possible writeback itself.
        // B-published-before-A-released makes a regressed stale write
        // terminal (nothing overwrites after A), so the post-join terminal
        // and collector asserts cannot miss it — the bounded 2s post-release
        // `first{slow}` watch is dropped as unneeded (it proved only absence
        // within a window, never writeback completion).
        try {
            // Handshake（try 內第一句，超時亦走下方 finally）：等 collector 已觀測到
            // B 終值才放行 A；否則放行＋A 整段寫回可能搶在 collector 首次發射前完成（空洞釘）。
            // seen 為 plain synchronized list（collector 推入）故須 poll；bounded 10s，B-terminal 後首個發射即時到達。
            withTimeout(10_000) { while (seen.isEmpty()) delay(5) }
            fake.release.countDown()
            val aCoroutine = withTimeout(10_000) { fake.aJob.await() }
            // join() never throws, even on cancelled completion — the 15s
            // bound is failure-only (a stuck A fails instead of hanging).
            withTimeout(15_000) { aCoroutine.join() }
        } finally {
            histScope.cancel()
            withTimeoutOrNull(5_000) { histScope.coroutineContext.job.join() }
        }
        // The stale slow-r result must NEVER be written (not even transiently):
        // A has fully completed above, so the terminal and the full collector
        // history are conclusive.
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        val options = vm.form.value.modelOptions
        assertTrue("fast result must be terminal, got $options", options.containsAll(fast))
        assertTrue("slow result must be discarded, got $options", options.none { it.startsWith("slow-r") })
        assertTrue(
            "slow result must be discarded (collector saw stale): $seen",
            seen.none { opts -> (opts as List<*>).any { o -> (o as String).startsWith("slow-r") } },
        )
        assertEquals(2, fake.calls.get())
    }
}
