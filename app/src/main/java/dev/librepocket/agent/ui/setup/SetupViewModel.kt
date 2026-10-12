package dev.librepocket.agent.ui.setup

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.toProviderConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.KeyVault
import dev.librepocket.models.ModelsDevSnapshot
import dev.librepocket.policy.DataStorePolicyStore
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.DefaultProviderFactory
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One-shot key.write consent minted when [PolicyStore.evaluateFresh] returns ASK.
 *
 * In-memory only: never persisted, never placed in SavedState, and carries no
 * key material (identity snapshot + nonce only). Bound to the immutable
 * endpoint snapshot observed at mint time; consumed (nulled) on the first
 * confirm or abandon tap, so it can never be replayed or reused. Any
 * endpoint-identity edit discards it before use.
 */
private data class KeyWriteConsent(
    val action: String,
    val resource: String,
    val providerId: String,
    val baseUrl: String,
    val configRevision: Long?,
    val formRevision: Long,
    val nonce: Long,
)

/** Gate state for startup navigation. Loading and NoEndpoint are distinct (no FOUC). */
sealed interface EndpointGate {
    data object Loading : EndpointGate
    data object NoEndpoint : EndpointGate
    data class Ready(val config: EndpointConfig) : EndpointGate
}

sealed interface ModelDirectoryStatus {
    data object NotLoaded : ModelDirectoryStatus
    data object Remote : ModelDirectoryStatus
    data class Bundled(val reason: ModelsDevSnapshot.SnapshotFallbackReason) : ModelDirectoryStatus
}

data class SetupUiState(
    val presetId: String = ProviderCatalog.OPENAI_ID,
    val baseUrl: String = ProviderCatalog.OPENAI_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val showKey: Boolean = false,
    val saving: Boolean = false,
    /**
     * 登出執行中（Settings UI 可見，Re-review #7 Finding 2 階段2）。
     *
     * 由 [logout] 在進入持鎖前同步置 true（仿 [saving] 語義），成功經整表重置、
     * 失敗經結尾復位、取消經外層 catch 復位；任何路徑皆不留 true。
     * 與 [saving] 獨立：save/logout 以 [saveMutex] 序列化，兩者可同時為
     * 「對方持鎖、己方排隊」。
     */
    val logoutInFlight: Boolean = false,
    val testing: Boolean = false,
    val testModels: Int? = null,
    val modelOptions: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelDirectoryStatus: ModelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
    // Transient model-refresh CAS epoch only; not endpoint/configuration revision.
    internal val modelRefreshRevision: Long = 0L,
    val confirmKeyWrite: Boolean = false,
    val errorCode: String? = null,
    /**
     * `LOGOUT_*` 橫幅的 journal 身份標籤（Stage C C2）。
     *
     * 由 [stampLogoutError] 與落碼的 `errorCode` 同步寫入：值為落碼當時
     * replay／teardown 所持 journal 的 [PendingDelete.format] 原字串；無
     * journal（`NO_OP`／read-path `UNKNOWN`／未確認路徑）時為 null——
     * clear-phase `UNKNOWN`（clear 拋後 probe 重讀亦失敗）攜帶當時的 active
     * pending，故標籤非 null（同啟動 replay 核心的 read-path／clear-phase
     * 區分）。`COMMITTED`
     * replay 僅當此標籤與其攜帶 journal 相同才清碼（併發 teardown 的碼不
     * 被誤清）；[clearLogoutError]、logout 入口 fresh 化與 logout 成功重置
     * 連碼一起清。其他 `errorCode = null` 站點不碰此欄（殘留 tag 無害：
     * 清除要求 `errorCode` 為 `LOGOUT_*` 且標籤非 null 相等）。
     */
    val logoutErrorSourceJournal: String? = null,
    val saved: Boolean = false,
)

/**
 * Owns the endpoint gate flow plus the setup form state.
 *
 * Key handling: the [apiKey] String in [SetupUiState] cannot be wiped (String is
 * immutable); it is cleared (set to "") immediately after [save] consumes it,
 * converted to a CharArray copy for [KeyVault.putKey] (which wipes that copy),
 * and never logged, never placed in SavedState. Display of existing keys uses
 * [KeyVault.hasKey] only (dots, never echo). [testConnection] uses the typed
 * key in memory only and never persists it.
 */
class SetupViewModel(
    private val store: EndpointStore,
    private val vaultSource: VaultSource,
    private val policy: PolicyStore,
    private val buildProvider: (ProviderConfig, KeyProvider) -> LlmProvider =
        { cfg, keys -> DefaultProviderFactory(keys).create(cfg) },
    private val fetchDirectory: (suspend (String) -> String)? = null,
    private val stagedAliasProvider: (String) -> String = SetupValidation::newStagedAlias,
) : ViewModel() {

    private val directoryClient by lazy {
        dev.librepocket.provider.defaultOkHttpClient(
            dev.librepocket.provider.ProviderHttpConfig(),
        )
    }

    private suspend fun vault(): KeyVault = vaultSource.vault()

    private val _form = MutableStateFlow(SetupUiState(model = ProviderCatalog.defaultModelFor(ProviderCatalog.OPENAI_ID)))
    val form: StateFlow<SetupUiState> = _form

    /**
     * 啟動 replay 落定閘（Stage B：R10 Finding 2 P2/UX）。
     *
     * 初值 `true`：[launchPendingDeleteReconcile] 的 `onDone`（成功／失敗皆含，
     * 無 journal 的 `NO_OP` 亦落定）與取消落定後設 `false`（`finally`，避免
     * spinner 永留）。`MainScreen` 以此視同 loading（見
     * [dev.librepocket.agent.ui.startupEffectiveLoading]）：落定前兩導航守衛
     * 不決策、全屏 spinner 覆蓋，避免 replay 慢時先跳 Setup 的 FOUC。
     */
    private val _startupReconcilePending = MutableStateFlow(true)
    val startupReconcilePending: StateFlow<Boolean> = _startupReconcilePending
    /**
     * 啟動 replay 單飛鎖（Stage C C1：解 premature unlock + fence 缺口）。
     *
     * 以下三者全程以此鎖保護（`LaunchedEffect` 在 Main、測試可併發）：
     * - `startupWorker`：進行中的唯一 reconcile worker（無則 null；完成後
     *   殘留 dead job，以 `isActive` 判定存活，不另清 null）；
     * - `startupCallbacks`：排隊等待同一輪結果的 `onDone`（含首發者）；
     * - `startupSettled`：已落定輪的 [StartupSettled] 快照——寫入先於
     *   Main fan-out；重 launch 先經廉價 strict 快照比對 generation（見
     *   [launchPendingDeleteReconcile] Stage D2 作用域化），相同才回放（無
     *   I/O、不碰 pending），不同則失效後新鮮 worker；再查 worker（重疊者入列
     *   拿 join handle）。logout 入口與 save 提交經 [invalidateStartupSettled]
     *   使其失效（logout 終端再行斷言；否則 rotation 重播過期結果）。
     */
    private val startupLock = Any()
    private var startupWorker: Job? = null
    private val startupCallbacks = ArrayList<(PendingDeleteOutcome, PendingDelete?) -> Unit>()
    private var startupSettled: StartupSettled? = null
    private val modelRefreshGeneration = AtomicLong(0L)
    private var modelRefreshJob: Job? = null
    // Stage2 form fence: identity/key intent epoch. Bumped only by
    // selectPreset/onBaseUrlChange/onApiKeyChange/prefillForEdit/logout.
    // onModelChange/toggleShowKey deliberately do NOT bump (model edits are
    // out of scope, see #17). All save-path TOCTOU checks compare the entry
    // snapshot against this; any mismatch fails closed with POLICY_DENIED.
    private val formRevision = AtomicLong(0L)

    // One-shot key.write consent (ASK path). Main-thread edits and the IO save
    // coroutine touch it from different threads; all access goes through
    // [consentLock]. Never persisted, never leaves this ViewModel.
    //
    // Locking note: the guarded section is non-suspending (plain take/assign,
    // callable from Main-thread edits/dialog taps as well as the IO save
    // coroutine), so a plain `synchronized` block is used — a coroutine `Mutex`
    // would need suspend and cannot serve the Main-thread callers. [saveMutex]
    // stays a coroutine `Mutex` as the save/logout serialization fence.
    private val consentLock = Any()
    private var pendingConsent: KeyWriteConsent? = null
    private val consentNonces = AtomicLong(0L)

    companion object {
        /**
         * Bounded staged-alias mint attempts (initial mint + redraws).
         * Covers the snapshot-inequality guard and the existence-only
         * `hasKey` orphan guard; persistent collision fails closed with
         * POLICY_DENIED without touching the vault.
         */
        internal const val MAX_STAGED_MINT_ATTEMPTS = 5
        /**
         * Stage D1 中性碼：啟動 replay read-path `UNKNOWN`（journal=null）專用。
         *
         * `LOGOUT_` 前綴以相容既有 `takeIf { startsWith("LOGOUT_") }`（MainScreen／
         * StartupGateHost）與 NavGuard `startsWith` 守衛；文案見 [setupErrorText]，
         * 禁寫「已刪除」／「殘留待清理」。與 `LOGOUT_READ_FAILED`（logout 路徑讀失敗，
         * retained、重試清除）嚴格區分：此碼為非破壞性重試讀取（[retryStartupReconcile]），
         * 絕不調 `logout()`。
         */
        const val LOGOUT_READ_RETRY = "LOGOUT_READ_RETRY"
    }

    /**
     * Serializes the save critical section ([executeKeyWrite] staged put,
     * re-verification, metadata CAS, old-alias retire) against privileged
     * [logout] teardown (vault delete + metadata clear) within this VM.
     *
     * Single-process only: this [Mutex] cannot fence a second process, a
     * second ViewModel/store instance in tests, or any out-of-band vault
     * writer. Cross-writer safety rests on the [EndpointStore.saveIfRevision]
     * CAS plus the restart-unique staged alias scheme (a CAS loss leaves the
     * winner's staged live key untouched; the loser only deletes its own
     * per-operation restart-unique staged alias, never a live slot). Staged
     * alias uniqueness itself holds across restarts and processes via the
     * full-UUID 32-hex suffix (see [SetupValidation.newStagedAlias]) plus a
     * bounded mint-time inequality + existence guard; only the metadata
     * CAS remains single-process (documented on EndpointStore). Never present
     * this mutex as multi-writer safety.
     */
    private val saveMutex = Mutex()

    /**
     * Stage D2 settled generation 作用域（R12 Finding 2 (c)）。
     *
     * 由同一 `snapshotDeleteStateStrict` emission 取得的 `(revision,
     * journal.format() raw)`：`revision` 為該 emission 的
     * `ENDPOINT_CONFIG_REVISION_V1`（fresh 為 null），`journalRaw` 為該 emission
     * journal 的 `format()`（無 journal 為 null）。跨 VM 寫入（journal 出現／
     * 消失、revision 前進）即改變 generation，rotation 重 launch 據此失效舊
     * 輪（新鮮 worker，同一 `runPendingDeleteLocked` 核心兜底），相同才回放。
     * `null` 整體表示未知（strict 讀失敗或 journal 毀損，無可用 emission）——
     * 永不視為相同（fail-closed 走 fresh，見 [startupGenerationsMatch] D1 保留
     * 例外）。
     */
    private data class StartupGeneration(
        val revision: Long?,
        val journalRaw: String?,
    )

    /**
     * 已落定輪快照（含 generation）。
     *
     * `outcome`＋`journal` 為回放內容（`journal` 為 null 僅限 `NO_OP` 與
     * read-path `UNKNOWN`，語義不變）；`generation` 為該輪 reconcile 起點
     * snapshot 的 generation（read-path `UNKNOWN` 無 emission 時為 null）。
     */
    private data class StartupSettled(
        val outcome: PendingDeleteOutcome,
        val journal: PendingDelete?,
        val generation: StartupGeneration?,
    )

    /**
     * Test seam: the most recent save coroutine. Cancellation tests cancel this
     * job to inject [CancellationException] at suspending gates (V1/V2/CAS).
     * Production code never touches it; it is overwritten on every [save].
     */
    internal var lastSaveJobForTest: Job? = null

    /**
     * Test seam: the most recent logout coroutine. Cancellation tests cancel
     * this job to inject [CancellationException] at the clear gate
     * (delete-then-clear window). Production code never touches it; it is
     * overwritten on every [logout].
     */
    internal var lastLogoutJobForTest: Job? = null

    /** Test seam for racing an edit between mint success and dialog display. */
    internal var afterMintBeforeDialogForTest: (() -> Unit)? = null

    val gate: StateFlow<EndpointGate> = store.observe()
        .map { config ->
            if (config == null) {
                EndpointGate.NoEndpoint
            } else {
                val okUrl = try {
                    dev.librepocket.provider.validateBaseUrl(config.baseUrl)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
                val hasKey = try {
                    // Ref-driven: the live alias comes from metadata, never from
                    // providerId (staged commits point at a per-operation alias).
                    // Malformed refs fail closed to NoEndpoint without vault I/O.
                    if (!config.apiKeyRef.startsWith("provider_key/")) {
                        false
                    } else {
                        val alias = SetupValidation.aliasFromRef(config.apiKeyRef)
                        if (alias.isBlank()) false else withContext(Dispatchers.IO) { vault().hasKey(alias) }
                    }
                } catch (_: Exception) {
                    false
                }
                if (okUrl && hasKey) EndpointGate.Ready(config) else EndpointGate.NoEndpoint
            }
        }
        .catch { emit(EndpointGate.NoEndpoint) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EndpointGate.Loading)

    /**
     * Stage E pending-domain re-read tick (R13 Finding P2/BLOCK, 方案 B).
     *
     * [pendingTeardown] 的內層 [EndpointStore.observePendingDelete] 在 `catch`
     * 發射後即完成（transient 失敗黏住）；每次 tick 遞增經 `flatMapLatest` 重建
     * 內層收集、重讀 store——store 保持唯一真相源，無手動回寫（禁方案 A：手動
     * 回寫會以 stale 蓋掉 concurrent 新 journal）。Bump 點與
     * [invalidateStartupSettled] 對齊（retry／logout 入口／save 提交／logout
     * 終端／probe generation-changed，見各呼叫處）：皆為 journal 存否可能變化
     * 之處；settled 回放不 bump（零多讀）。成功 strict 讀不需額外回寫（tick
     * 重建即重讀）。內層 `catch` 完成後需下次 tick 才恢復；漏 bump 只留
     * phantom present（fail-closed：重試讀取，不靜默 fresh）。
     */
    private val pendingTick = MutableStateFlow(0L)

    /**
     * Stage E 四態 teardown domain（R13 P2/BLOCK；D2 `PendingDelete?` 升格）。
     *
     * 由 [EndpointStore.observePendingDelete] 經 [pendingTick] `flatMapLatest`
     * 派生（零 vault I/O，僅一 Preferences key 讀）：第二 VM 在本 VM `NO_OP`
     * 落定後寫入的 journal 在此發射為 [PendingTeardownState.KnownPending]，
     * 即使本 VM `form.errorCode` 仍為 null；transient 讀失敗發射為
     * [PendingTeardownState.UnknownReadFailure]（非 [PendingTeardownState.Absent]，
     * 絕不誤報 fresh），毀損 journal 發射為
     * [PendingTeardownState.MalformedPending]。`MainScreen` 收集後傳入
     * [dev.librepocket.agent.ui.StartupGateHost] 派生恢復態（
     * [dev.librepocket.agent.ui.effectiveLogoutGuardError]：confirmed 走
     * retained 重試清除，unconfirmed 走重試讀取）；safe reconcile 複用既有
     * [logout] resume 分支（同一 `runPendingDeleteLocked` 核心），不新增 vault
     * 路徑。`WhileSubscribed(5000)` 仿 [gate] 接線。
     */
    val pendingTeardown: StateFlow<PendingTeardownState> = pendingTick
        .flatMapLatest { store.observePendingDelete() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PendingTeardownState.Absent)

    fun selectPreset(presetId: String) {
        val preset = ProviderCatalog.preset(presetId) ?: return
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        _form.value = _form.value.copy(
            presetId = presetId,
            modelRefreshRevision = revision,
            baseUrl = preset.baseUrl,
            model = ProviderCatalog.defaultModelFor(presetId),
            errorCode = null,
            testModels = null,
            modelOptions = emptyList(),
            modelsLoading = false,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
        )
    }

    fun onBaseUrlChange(v: String) {
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        _form.value = _form.value.copy(
            baseUrl = v,
            modelRefreshRevision = revision,
            errorCode = null,
            testModels = null,
            modelOptions = emptyList(),
            modelsLoading = false,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
        )
    }

    fun onModelChange(v: String) {
        _form.value = _form.value.copy(model = v, errorCode = null, testModels = null)
    }

    fun onApiKeyChange(v: String) {
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        _form.value = _form.value.copy(
            apiKey = v,
            modelRefreshRevision = revision,
            errorCode = null,
            testModels = null,
            modelOptions = emptyList(),
            modelsLoading = false,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
        )
    }

    fun toggleShowKey() {
        _form.value = _form.value.copy(showKey = !_form.value.showKey)
    }

    /**
     * Prefill the form from the live endpoint for "edit" entries. The key
     * always stays blank (dots display "already set"; retype to rotate).
     * Unknown presetIds fall back to custom with the stored URL editable.
     */
    fun prefillForEdit(config: EndpointConfig) {
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val revision = modelRefreshGeneration.incrementAndGet()
        val preset = ProviderCatalog.preset(config.presetId)
        if (preset == null || config.presetId == ProviderCatalog.CUSTOM_ID) {
            _form.value = SetupUiState(
                presetId = ProviderCatalog.CUSTOM_ID,
                baseUrl = config.baseUrl,
                model = config.model,
                modelRefreshRevision = revision,
            )
        } else {
            _form.value = SetupUiState(
                presetId = preset.id,
                baseUrl = preset.baseUrl,
                model = config.model.ifBlank { preset.defaultModel },
                modelRefreshRevision = revision,
            )
        }
    }

    fun consumeSaved() {
        _form.value = _form.value.copy(saved = false)
    }

    /** One-shot connectivity check (P1_SPEC listModels); persists nothing. */
    fun testConnection() {
        val cur = _form.value
        if (cur.testing || cur.saving || cur.modelsLoading) return
        val effectiveBaseUrl = effectiveBaseUrlOf(cur)
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        _form.value = cur.copy(testing = true, errorCode = null, testModels = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = providerConfigOf(cur, effectiveBaseUrl)
                val typed = cur.apiKey.trim()
                val keys = KeyProvider { typed.toCharArray() }
                val count = buildProvider(config, keys).listModels().size
                _form.value = _form.value.copy(testing = false, testModels = count)
            } catch (e: ProviderFailure) {
                _form.value = _form.value.copy(testing = false, errorCode = classifyTestError(e))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(testing = false, errorCode = "TEST_FAILED")
            }
        }
    }

    /** Refresh the model candidates: live listing first, snapshot fills the rest. */
    fun refreshModels() {
        val cur = _form.value
        if (cur.modelsLoading || cur.testing || cur.saving) return
        val effectiveBaseUrl = effectiveBaseUrlOf(cur)
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        val generation = modelRefreshGeneration.incrementAndGet()
        _form.value = cur.copy(
            modelsLoading = true,
            errorCode = null,
            modelDirectoryStatus = ModelDirectoryStatus.NotLoaded,
            modelRefreshRevision = generation,
        )
        modelRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = providerConfigOf(cur, effectiveBaseUrl)
                val typed = cur.apiKey.trim()
                val keys = KeyProvider { typed.toCharArray() }
                val live = try {
                    buildProvider(config, keys).listModels()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                val fetcher = fetchDirectory ?: { url: String ->
                    ModelDirectory.fetchBody(directoryClient, url)
                }
                val outcome = ModelsDevSnapshot.fetchSnapshotOutcome(fetcher = fetcher)
                val options = ProviderCatalog.listedModels(cur.presetId, live, outcome.snapshot)
                _form.update { current ->
                    if (generation == modelRefreshGeneration.get() && current.modelRefreshRevision == generation) {
                        current.copy(
                            modelsLoading = false,
                            modelOptions = options,
                            modelDirectoryStatus = when (outcome.source) {
                                ModelsDevSnapshot.SnapshotSource.REMOTE_DIRECTORY -> ModelDirectoryStatus.Remote
                                ModelsDevSnapshot.SnapshotSource.BUNDLED -> ModelDirectoryStatus.Bundled(
                                    checkNotNull(outcome.fallbackReason),
                                )
                            },
                        )
                    } else {
                        current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Validation passed but mapping failed (unknown preset race):
                // keep the free-text model, clear the spinner.
                _form.update { current ->
                    if (generation == modelRefreshGeneration.get() && current.modelRefreshRevision == generation) {
                        current.copy(modelsLoading = false)
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun save() {
        saveWithConsent(null)
    }

    /**
     * Shared save path. [consent] is non-null only for the dialog confirm tap,
     * which carries the one-shot consent minted by the earlier ASK evaluation.
     * Every side effect below (staged [KeyVault.putKey] + [EndpointStore.saveIfRevision])
     * is preceded by a fresh policy evaluation; DENY always wins, even when a
     * consent was minted earlier. Stage2 adds a form fence ([formRevision]) and
     * a second post-write policy re-evaluation. The whole [executeKeyWrite]
     * critical section runs under [saveMutex] (same-VM save/logout
     * serialization). The vault commit uses a per-operation restart-unique
     * staged alias: the new key is written aside under
     * `<providerId>#staged-<uuid32>` (see [SetupValidation.newStagedAlias];
     * the suffix is a full 32-hex UUID per attempt, never a process-local
     * counter, so a relaunch cannot re-mint an alias that collides with an
     * already committed live alias — residual ~2^-122 risk is additionally
     * fenced by a bounded mint-time inequality + existence guard that fails
     * closed), the metadata CAS swings `apiKeyRef` to
     * it, and only then is the previous live alias retired best-effort. A CAS
     * loss or any earlier abort deletes only the attempt's own staged alias
     * (known per-operation name, safe) and never touches a live slot, never
     * restores a predecessor, and never reads a live key. No abort path
     * performs an unconditional live delete.
     */
    private fun saveWithConsent(consent: KeyWriteConsent?) {
        val entryRevision = formRevision.get()
        if (consent != null && consent.formRevision != entryRevision) {
            // Stale consent replay (identity/key edited between mint and confirm
            // tap, or cross-tap reuse): fail closed without touching saving or
            // evaluating policy.
            _form.value = _form.value.copy(errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        val cur = _form.value
        if (cur.saving || cur.testing || cur.modelsLoading) return
        val effectiveBaseUrl = effectiveBaseUrlOf(cur)
        val err = SetupValidation.validate(cur.presetId, effectiveBaseUrl, cur.apiKey)
        if (err != null) {
            _form.value = cur.copy(errorCode = err)
            return
        }
        _form.value = cur.copy(saving = true, errorCode = null)
        lastSaveJobForTest = viewModelScope.launch(Dispatchers.IO) {
            // Same-VM serialization with privileged logout teardown. Held only
            // for the duration of one save attempt: dialog-pending paths return
            // (releasing the mutex) and the confirm tap re-acquires it.
            // saveMutex.withLock releases on cancellation (verified). The
            // try-catch sits OUTSIDE withLock so a cancel while QUEUED for the
            // mutex (lambda never entered) still unsticks `saving`; an
            // inner-only catch would leave saving=true in that window.
            try {
                saveMutex.withLock {
                    executeKeyWrite(cur, effectiveBaseUrl, consent, entryRevision)
                }
            } catch (e: CancellationException) {
                // Any cancel path (VM alive) must unstick saving and must
                // NOT mask the cancel with POLICY_DENIED. StateFlow copy is
                // non-suspending and safe under cancellation. Committed
                // cancels are already swallowed to saved=true inside
                // executeKeyWrite; only uncommitted/unknown cancels reach
                // here, including withLock-wait cancels. Awaiting callers
                // observe saving==false + rethrow.
                // (Dedicated SAVE_CANCELLED code is a UX follow-up; at
                // minimum never leave saving=true.)
                _form.value = _form.value.copy(saving = false)
                throw e
            } catch (e: IllegalArgumentException) {
                _form.value = _form.value.copy(saving = false, errorCode = e.message ?: "SETUP_SAVE_FAILED")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _form.value = _form.value.copy(saving = false, errorCode = "SETUP_SAVE_FAILED")
            }
        }
    }

    private suspend fun executeKeyWrite(
        form: SetupUiState,
        effectiveBaseUrl: String,
        consent: KeyWriteConsent?,
        entryRevision: Long,
    ) {
        val providerId = try {
            SetupValidation.deriveProviderId(form.presetId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 1. Fresh evaluation immediately before ANY side effect (TOCTOU guard).
        // evaluateFresh failure fails closed to DENY.
        val v1 = freshVerdictOrDeny("key.write", providerId)
        if (v1 == Verdict.DENY) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 2. Form fence before mint: an edit racing the first evaluation aborts.
        if (entryRevision != formRevision.get()) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 3. Consent path re-validates against the LATEST form (not the entry
        // snapshot) so a key/URL edit racing the confirm tap is observed.
        if (consent != null) {
            val live = _form.value
            val liveBaseUrl = try {
                effectiveBaseUrlOf(live)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (!consentMatches(consent, live, liveBaseUrl)) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
        } else if (v1 == Verdict.ASK) {
            // key.write defaults to ASK: one explicit confirmation per key.
            if (!mintKeyWriteConsentGuarded(providerId, effectiveBaseUrl, entryRevision)) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // Test seam: pause between mint success and dialog display so tests
            // can race an edit into the mint->dialog window (Gated skeleton).
            afterMintBeforeDialogForTest?.invoke()
            // Mint->dialog window fence: an edit racing the mint must not
            // revive the dialog. Re-verify before showing; on mismatch discard
            // the just-minted consent and fail closed (no deleteKey).
            if (entryRevision != formRevision.get()) {
                takePendingConsent()
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            _form.value = _form.value.copy(saving = false, confirmKeyWrite = true)
            // Post-show fence: an edit racing the dialog set must not leave a
            // stale dialog. invalidateKeyWriteConsent() hides a shown dialog,
            // but an edit landing between the pre-show check and this set would
            // otherwise revive it; re-verify and hide again.
            if (entryRevision != formRevision.get()) {
                takePendingConsent()
                _form.value = _form.value.copy(saving = false, confirmKeyWrite = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            return
        }
        // Snapshot the revision AND the previous live alias in a single emission
        // for the trailing CAS and the post-commit retire. Consent path reuses
        // consent.configRevision but still requires the live revision to match
        // it here (single-emission authoritative check). Snapshot-then-fence: a
        // read failure or an edit racing the read fails closed before any vault
        // side effect. The teardown journal rides the SAME emission (Stage 2
        // P2): a present journal blocks the save fail-closed here — before any
        // vault write — and the trailing CAS re-fences atomically, so a journal
        // landing between this snapshot and the CAS still fails closed below.
        val snapshot = try {
            store.snapshotDeleteStateStrict()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Malformed teardown journal: fail closed as teardown-blocked (no
            // vault write, no metadata write, journal kept for reconcile).
            _form.value = _form.value.copy(saving = false, errorCode = "SAVE_BLOCKED_BY_TEARDOWN")
            return
        } catch (_: Exception) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        if (snapshot.first != null) {
            // A logout teardown owns the endpoint until its journal is
            // reconciled: no vault write, no metadata write, revision and
            // journal untouched. The caller reconciles then retries.
            _form.value = _form.value.copy(saving = false, errorCode = "SAVE_BLOCKED_BY_TEARDOWN")
            return
        }
        val oldConfig = snapshot.second
        val liveRev = snapshot.third
        val expectedRevision: Long? = if (consent != null) {
            if (liveRev != consent.configRevision) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            consent.configRevision
        } else {
            liveRev
        }
        // Previous live alias for post-commit retire (null on fresh start).
        // Malformed refs resolve to null and are never retired.
        val oldAlias: String? = oldConfig?.apiKeyRef
            ?.takeIf { it.startsWith("provider_key/") }
            ?.let { SetupValidation.aliasFromRef(it) }
            ?.takeIf { it.isNotBlank() }
        // 4. Fence after the snapshot/oldAlias read, immediately before the
        // vault write. Single check: no suspend lies between the snapshot read
        // and here, so a second identical check would be redundant.
        if (entryRevision != formRevision.get()) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // 5. Staged vault write (versioned-alias scheme). The new key is written
        // aside under a per-operation restart-unique staged alias and the live
        // slot is NEVER overwritten here, so a concurrent writer's live key
        // cannot be clobbered by our put, and no backup/restore of live key
        // material is needed: there is no live read anywhere on this path,
        // hence no key.read evaluation either (the backup scheme and its gate
        // are gone by construction). The alias comes from the injected
        // [stagedAliasProvider] (default [SetupValidation.newStagedAlias],
        // full-UUID 32-hex suffix, fresh per attempt):
        // a process-local counter is deliberately NOT used because it
        // restarts at zero after a relaunch and would re-mint the previous
        // round's live alias, overwriting it in place (with a later abort or
        // retire then deleting live bytes = loss).
        //
        // Trade-offs, stated honestly:
        // - Between the staged put and the metadata CAS, two keys briefly
        //   coexist at rest (staged + previous live). On abort only the staged
        //   alias is deleted; on success only the previous live alias is
        //   retired. A crash between put and CAS (or between CAS and retire)
        //   orphans a staged (or previous-live) key at rest: without a vault
        //   list API it cannot be enumerated for GC, so it lingers encrypted
        //   until a GC follow-up (list API) lands. At-rest leak is strictly
        //   better than the fixed-alias loss it replaces.
        // - Same-VM saves are fenced by [saveMutex] and metadata races by the
        //   trailing CAS, but out-of-band writers still need single-writer
        //   discipline for endpoint identity: two writers still race on the
        //   single metadata slot (the loser fails closed), they just can no
        //   longer destroy each other's key bytes. Staged alias uniqueness
        //   holds across restarts and processes via the full-UUID 32-hex
        //   suffix (122-bit) plus the bounded collision guard below; only the
        //   DataStore metadata CAS itself is single-process only (documented
        //   on EndpointStore).
        // - [EncryptedPrefsVault.putKey] wipes the array it is given, so the
        //   put input ([keyCopy]) gets a defensive re-wipe here.
        // - Uses the entry snapshot (not live): key/identity edits bump
        //   formRevision and are fenced above, so reaching here means the entry
        //   key is still current. Model may be stale (onModelChange does not
        //   bump per #17 scope) but model is not consent identity and does not
        //   affect key.write fencing; endpointConfigOf below shares this note.
        //
        // Collision guard (Re-review #5 Finding 2): the freshly minted alias
        // is compared against the snapshot [oldAlias] with zero vault I/O;
        // on equality it is redrawn via [stagedAliasProvider], bounded to
        // [MAX_STAGED_MINT_ATTEMPTS] total mints. Persistent collision fails
        // closed with POLICY_DENIED without touching the vault (no put, no
        // delete, no hasKey). A surviving candidate then gets an
        // existence-only `hasKey` check (never `getKey`, never reads content)
        // covering a crash-orphan placeholder still occupying the alias;
        // on existence it is redrawn (re-checking inequality) within the same
        // bound, else it fails closed the same way. Any `hasKey` host failure
        // (except cancellation, rethrown) also fails closed before any put.
        var stagedAlias: String = try {
            stagedAliasProvider(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        var mintAttempts = 1
        while (stagedAlias == oldAlias && mintAttempts < MAX_STAGED_MINT_ATTEMPTS) {
            if (entryRevision != formRevision.get()) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            try {
                stagedAlias = stagedAliasProvider(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            mintAttempts++
        }
        if (stagedAlias == oldAlias) {
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        // Existence-only orphan guard (no content read). Bounded by the same
        // total-mint budget; each redraw re-checks the snapshot inequality
        // synchronously before the next existence probe.
        while (true) {
            if (entryRevision != formRevision.get()) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            val orphanExists: Boolean = try {
                vault().hasKey(stagedAlias)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (!orphanExists) break
            if (mintAttempts >= MAX_STAGED_MINT_ATTEMPTS) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            try {
                stagedAlias = stagedAliasProvider(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            mintAttempts++
            while (stagedAlias == oldAlias && mintAttempts < MAX_STAGED_MINT_ATTEMPTS) {
                if (entryRevision != formRevision.get()) {
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                try {
                    stagedAlias = stagedAliasProvider(providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                mintAttempts++
            }
            if (stagedAlias == oldAlias) {
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
        }
        val stagedRef = "provider_key/$stagedAlias"
        val keyCopy = form.apiKey.trim().toCharArray()
        // Explicit commit-state for cancellation compensation (P1-1 hardening):
        // - vaultDirty: staged putKey succeeded (own staged bytes exist).
        // - metadataCommitted: saveIfRevision returned true (staged is live).
        // The Cancellation handler below compensates strictly by this state,
        // verified against strict metadata when ambiguous (CAS threw Cancel
        // after possibly committing, including TimeoutCancellation from
        // withTimeout — also a CancellationException, never misjudged into a
        // live rollback).
        var vaultDirty = false
        var metadataCommitted = false
        try {
            try {
                vault().putKey(stagedAlias, keyCopy)
                vaultDirty = true
            } finally {
                keyCopy.fill('\u0000')
            }
        } catch (e: CancellationException) {
            // The staged put raced cancellation: the staged slot may or may not
            // hold our bytes. The alias is ours alone (per-operation
            // restart-unique UUID alias) and was never published in metadata,
            // so a NonCancellable best-effort delete is safe; live slots are
            // never touched here. Bounded: single delete, no retry.
            try {
                withContext(NonCancellable) { vault().deleteKey(stagedAlias) }
            } catch (_: Exception) {
            }
            // VM alive: unstick saving, never mask cancel with POLICY_DENIED.
            _form.value = _form.value.copy(saving = false)
            throw e
        } catch (_: Exception) {
            // The put itself failed: best-effort delete the (possibly partial)
            // staged entry, then abort. Live slots are never touched.
            deleteStagedBestEffort(stagedAlias)
            _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        try {
            // 6. Second fresh evaluation AFTER the staged write, BEFORE metadata.
            // A policy flip during putKey blocks the metadata write; the
            // just-written staged key is deleted (own alias, safe).
            val v2 = freshVerdictOrDeny("key.write", providerId)
            if (v2 == Verdict.DENY) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 7. Generation fence after the write: an edit racing putKey aborts
            // the metadata write and deletes the just-written staged key.
            if (entryRevision != formRevision.get()) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 8. Consent re-verification against live state (a stale/invalidated
            // consent still blocks even when the second verdict is ALLOW).
            if (consent != null) {
                val live2 = _form.value
                val liveBaseUrl2 = try {
                    effectiveBaseUrlOf(live2)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                if (!consentMatches(consent, live2, liveBaseUrl2)) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
            }
            // 9. Trailing entry fence before the metadata CAS.
            if (entryRevision != formRevision.get()) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            // 10. ALLOW->ASK flip during putKey (fresh path only): demand an
            // explicit confirmation instead of persisting metadata. The
            // just-written staged key is deleted first, so no orphan is left
            // pending while the dialog is shown; the confirm tap performs a
            // clean staged put of its own.
            if (v2 == Verdict.ASK && consent == null) {
                val live3 = _form.value
                val liveBaseUrl3 = try {
                    effectiveBaseUrlOf(live3)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                if (!mintKeyWriteConsentGuarded(providerId, liveBaseUrl3, entryRevision)) {
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                // Test seam: same mint->dialog window as the initial-ASK path.
                afterMintBeforeDialogForTest?.invoke()
                // Mint->dialog window fence (ALLOW->ASK flip path): an edit racing
                // the mint must not revive the dialog. Re-verify before showing; on
                // mismatch discard the just-minted consent and fail closed.
                if (entryRevision != formRevision.get()) {
                    takePendingConsent()
                    deleteStagedBestEffort(stagedAlias)
                    _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                // Do NOT save metadata and do NOT set saved=true here: delete
                // the staged key, then ask for confirmation.
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, confirmKeyWrite = true)
                // Post-show fence: hide a stale dialog revived by an edit racing
                // the dialog set (see initial-ASK path). The staged key is already
                // deleted, so only the dialog state needs hiding here.
                if (entryRevision != formRevision.get()) {
                    takePendingConsent()
                    _form.value = _form.value.copy(saving = false, confirmKeyWrite = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                    return
                }
                return
            }
            // V2==ASK with a valid matching consent proceeds; V2==ALLOW proceeds
            // (an invalidated consent above already blocked).
            // Uses the entry snapshot: model may be stale (onModelChange does not
            // bump formRevision per #17 scope) but model is not consent identity
            // (consent binds providerId/baseUrl only), so staleness is out of scope
            // and intentionally not fenced here. See keyCopy note at step 5.
            // The metadata swings apiKeyRef to OUR staged alias (per-operation
            // restart-unique); the fixed deriveApiKeyRef form is kept only for
            // backward-compatible reads of old metadata and in-memory
            // (non-persisted) mapping.
            val config = endpointConfigOf(form, effectiveBaseUrl, providerId, stagedRef)
            val wrote = try {
                store.saveIfRevision(config, expectedRevision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                deleteStagedBestEffort(stagedAlias)
                throw e
            } catch (_: Exception) {
                deleteStagedBestEffort(stagedAlias)
                _form.value = _form.value.copy(saving = false, errorCode = "POLICY_DENIED_KEY_WRITE")
                return
            }
            if (!wrote) {
                // CAS mismatch: a concurrent winner advanced the revision, OR a
                // teardown journal landed between our snapshot and the CAS (the
                // store-level journal fence turns that into a loss — no
                // save-then-mask window). Distinguish via a journal re-read: a
                // present-or-malformed journal reports the retryable
                // teardown-blocked code (journal kept, reconcile-then-retry);
                // pure revision contention keeps the existing denial. Either
                // way only the attempt's own staged alias is deleted — the
                // winner (whose metadata points at its own staged alias) and
                // the journal are untouched.
                deleteStagedBestEffort(stagedAlias)
                val blockedByTeardown: Boolean = try {
                    store.pendingDelete() != null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Malformed journal or unreadable store: fail closed
                    // toward the teardown-blocked code (retryable, no writes).
                    true
                }
                _form.value = _form.value.copy(
                    saving = false,
                    errorCode = if (blockedByTeardown) "SAVE_BLOCKED_BY_TEARDOWN" else "POLICY_DENIED_KEY_WRITE",
                )
                return
            }
            metadataCommitted = true
            // Post-commit retire of the previous live alias (best-effort, never
            // fails the save). Guarded by inequality with the just-committed
            // staged alias, so the new live key is never deleted here. The
            // guard is sound across restarts because staged aliases are
            // restart-unique (full-UUID 32-hex, 122-bit): the fresh alias
            // collides with the previous live alias only at ~2^-122 per pair,
            // and the mint-time bounded inequality + existence guard above
            // already fails closed on such a collision before any put. Runs in
            // the cancellable context (deliberately outside NonCancellable): if
            // cancellation lands here the previous alias may leak at rest
            // (documented orphan class, strictly better than loss); the new
            // live key always survives. Retire-cancel leak is at-rest only and
            // documented; saveMutex still releases on cancel (verified).
            if (oldAlias != null && oldAlias != stagedAlias) {
                try {
                    vault().deleteKey(oldAlias)
                } catch (e: CancellationException) {
                    // Commit already won: swallow to report success and never
                    // fall through to anything that deletes the new live key.
                    // The old alias may leak at rest (no vault list API for GC).
                } catch (_: Exception) {
                    // Best-effort: the save below stays authoritative.
                }
            }
            // Stage C C1: the metadata generation moved (staged is live) — drop
            // any cached startup-reconcile round so a rotation relaunch runs
            // fresh I/O instead of replaying a stale outcome over the new state.
            invalidateStartupSettled()
            _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
        } catch (e: CancellationException) {
            if (!metadataCommitted) {
                // Uncommitted or ambiguous (put-after / V2 / fence / CAS-threw-
                // Cancel): the CAS may have committed and then thrown (including
                // TimeoutCancellation), so verify against strict metadata FIRST
                // in bounded NonCancellable context; unknown store state fails
                // closed to no-touch (at-rest leak, never loss). Bounded: one
                // strict read + at most one delete, no retry; read failure
                // stops cleanup. A winner's staged live key can never equal our
                // per-operation restart-unique alias, so `false` (absent or
                // pointing elsewhere) authorizes deleting ONLY our own staged
                // alias — live is never touched. `true` means the commit won
                // despite the cancel: own staged is now live, must NOT delete;
                // treat as success (swallow, report saved=true READY).
                // Confirmed-uncommitted (staged cleaned) or unknown (no-touch
                // leak): unstick saving, never POLICY_DENIED-mask the cancel.
                // Journal-aware: a journal landing over the commit refuses
                // success (the teardown owns the outcome now).
                val livePointsAtStaged: Boolean? = try {
                    withContext(NonCancellable) { probeStagedCommit(stagedRef) }
                } catch (_: CancellationException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (livePointsAtStaged == true) {
                    invalidateStartupSettled()
                    _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
                    return
                }
                if (livePointsAtStaged == false && vaultDirty) {
                    try {
                        withContext(NonCancellable) { vault().deleteKey(stagedAlias) }
                    } catch (_: Exception) {
                    }
                }
                // Confirmed-uncommitted (staged cleaned) or unknown (no-touch
                // leak): unstick saving, never POLICY_DENIED-mask the cancel.
                _form.value = _form.value.copy(saving = false)
            } else {
                // Committed (CAS true, then retire/success-mark cancelled):
                // staged is live — NEVER delete. Re-confirm the commit is still
                // in place (defensive: logout could have raced); only then
                // swallow and report success. Journal-aware: a teardown journal
                // in flight refuses success even when the ref still matches
                // (the live key may already be deleted). Read failure ->
                // no-touch leak, unstick saving, rethrow (never rollback the
                // new live key).
                val stillCommitted: Boolean? = try {
                    withContext(NonCancellable) { probeStagedCommit(stagedRef) }
                } catch (_: CancellationException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (stillCommitted == true) {
                    invalidateStartupSettled()
                    _form.value = _form.value.copy(saving = false, apiKey = "", saved = true)
                    return
                }
                _form.value = _form.value.copy(saving = false)
            }
            throw e
        }
    }

    /**
     * Best-effort delete of an attempt-owned staged alias.
     *
     * Safe by construction: staged aliases are per-operation restart-unique
     * (full-UUID 32-hex suffix, see [SetupValidation.newStagedAlias]) and never published
     * before the metadata CAS, so no winner or live reader can reference them
     * — including across process restarts, where a counter-based alias would
     * be re-minted and collide with a committed live key. Vault failures are
     * swallowed so the original abort reason stays the reported error.
     * [CancellationException] is always rethrown, never swallowed
     * (cancellation cleanup uses the NonCancellable guarded paths in
     * [executeKeyWrite] instead).
     */
    private suspend fun deleteStagedBestEffort(stagedAlias: String) {
        try {
            vault().deleteKey(stagedAlias)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort: the abort error below stays authoritative.
        }
    }

    /**
     * Journal-aware commit probe for the cancellation compensation below.
     *
     * Must be called inside `withContext(NonCancellable)` (callers own that).
     * Returns `true` only when the metadata still points at [stagedRef] AND no
     * teardown journal is in flight; `false` when the metadata provably points
     * elsewhere (our staged alias was never published — safe to clean up);
     * `null` when the outcome is unknown (read failure, malformed journal, or
     * a journal in flight OVER our commit — the teardown may already have
     * deleted the live key, so no-touch: never swallow to success, never
     * delete).
     *
     * A plain `snapshotStrict()?.apiKeyRef == stagedRef` check would swallow a
     * committed-then-journaled cancel to `saved=true` while lenient readers
     * fold to unavailable (key deleted, clear pending) — this probe refuses
     * success whenever a journal is present.
     */
    private suspend fun probeStagedCommit(stagedRef: String): Boolean? {
        try {
            val state = store.snapshotDeleteStateStrict()
            if (state.first != null) {
                // Journal in flight: a ref mismatch still proves our staged
                // alias was never published (safe cleanup); a match is
                // ambiguous (our commit may be torn down) → no-touch.
                return if (state.second?.apiKeyRef == stagedRef) null else false
            }
            return state.second?.apiKeyRef == stagedRef
        } catch (_: CancellationException) {
            return null
        } catch (_: Exception) {
            // Includes the malformed-journal throw: no-touch.
            return null
        }
    }

    /** Second tap of the key.write confirmation dialog: single-use, snapshot-bound. */
    fun confirmKeyWriteSave() {
        if (!_form.value.confirmKeyWrite) return
        // Consume-once: this tap burns the consent even if the write below is refused.
        val consent = takePendingConsent()
        _form.value = _form.value.copy(confirmKeyWrite = false)
        if (consent == null) {
            // Stale or replayed confirm (identity edited mid-dialog, double tap
            // racing the save, or programmatic misuse): fail closed, touch nothing.
            _form.value = _form.value.copy(errorCode = "POLICY_DENIED_KEY_WRITE")
            return
        }
        saveWithConsent(consent)
    }

    fun dismissKeyWriteConfirm() {
        // Abandon the minted consent: a later save() must re-evaluate fresh.
        takePendingConsent()
        _form.value = _form.value.copy(confirmKeyWrite = false)
    }

    /** Discard any unconsumed consent and hide the confirm dialog. */
    private fun invalidateKeyWriteConsent() {
        takePendingConsent()
        if (_form.value.confirmKeyWrite) {
            _form.value = _form.value.copy(confirmKeyWrite = false)
        }
    }

    /** Consume-once take: the returned consent (if any) can never be used again. */
    private fun takePendingConsent(): KeyWriteConsent? =
        synchronized(consentLock) {
            val consent = pendingConsent
            pendingConsent = null
            consent
        }

    /** Fail-closed fresh evaluation: any read failure (except cancellation) is DENY. */
    private suspend fun freshVerdictOrDeny(action: String, resource: String): Verdict {
        try {
            return policy.evaluateFresh(action, resource).verdict
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Verdict.DENY
        }
    }

    /** Mint guarded by the form fence with TOCTOU-safe lock handoff. Read failures deny. */
    private suspend fun mintKeyWriteConsentGuarded(providerId: String, baseUrl: String, entryRevision: Long): Boolean {
        try {
            if (entryRevision != formRevision.get()) return false
            val rev: Long? = try {
                store.currentRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            if (entryRevision != formRevision.get()) return false
            val consent = KeyWriteConsent(
                action = "key.write",
                resource = providerId,
                providerId = providerId,
                baseUrl = baseUrl,
                configRevision = rev,
                formRevision = entryRevision,
                nonce = consentNonces.incrementAndGet(),
            )
            synchronized(consentLock) {
                // Re-verify inside the lock: an edit racing the revision read
                // must not mint a consent bound to a stale generation.
                if (entryRevision != formRevision.get()) return false
                pendingConsent = consent
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun consentMatches(
        consent: KeyWriteConsent,
        liveForm: SetupUiState,
        liveBaseUrl: String,
    ): Boolean {
        try {
            if (consent.action != "key.write") return false
            if (consent.resource != consent.providerId) return false
            val liveProviderId = try {
                SetupValidation.deriveProviderId(liveForm.presetId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            if (consent.providerId != liveProviderId) return false
            if (consent.baseUrl != liveBaseUrl) return false
            // Front formRevision clamp: no edit before the revision read.
            if (consent.formRevision != formRevision.get()) return false
            val currentRev: Long? = try {
                // Raw nullable revision: fresh null != dirty 0L; failures deny.
                store.currentRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            // Back clamp: no edit during the revision read.
            if (consent.formRevision != formRevision.get()) return false
            return consent.configRevision == currentRev
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Outcome of a pending-delete journal replay ([reconcilePendingDelete]).
     *
     * - `NO_OP`: no journal present; nothing to do (no vault I/O, no clear).
     * - `COMMITTED`: the vault delete won and the fenced clear committed (the
     *   journal was removed atomically with the clear).
     * - `DELETE_FAILED`: the vault delete threw (Stage 3 P2: split from
     *   `FAILED`) — no clear attempted, journal KEPT, and the key may still
     *   be in the vault. Must never surface "already deleted" wording.
     * - `FAILED`: delete provably won but the clear is known-not-committed
     *   (the initial clear threw, the probe still sees metadata at the same
     *   revision, and the bounded same-rev retry still fails). Journal KEPT.
     *   Only a `FAILED` with a proven delete win justifies "already deleted" wording: the resume replay core here, and the fresh logout clear path below (probe confirmed metadata-present-at-same-rev + same-rev retry still fails — same taxonomy, same `LOGOUT_CLEAR_FAILED`). Any unconfirmed path (delete not yet attempted, or probe `UNKNOWN`) must use neutral `LOGOUT_UNKNOWN`.
     * - `STALE`: a concurrent winner advanced the revision; the superseded
     *   journal was discarded (best-effort, metadata untouched) and the winner
     *   survives.
     * - `UNKNOWN`: the strict re-read itself failed (or the journal is
     *   malformed): no-touch, journal kept, zero vault I/O. Unconfirmed —
     *   must never surface "already deleted" wording.
     */
    enum class PendingDeleteOutcome {
        NO_OP,
        COMMITTED,
        DELETE_FAILED,
        FAILED,
        STALE,
        UNKNOWN,
    }

    /**
     * Replays a crash/cancel-orphaned logout teardown journal (phase 1,
     * Re-review #8 Finding 1 P1). Shared by the startup path
     * ([launchPendingDeleteReconcile], called once from `MainScreen`) and the
     * logout resume path below.
     *
     * Serialized against in-flight saves/logouts via [saveMutex] (same-VM,
     * single-process only — same limits as [saveMutex] document). Strict read
     * of (journal, config, revision) first: no journal → [PendingDeleteOutcome.NO_OP]
     * (no vault I/O, no clear); malformed journal or read failure →
     * [PendingDeleteOutcome.UNKNOWN] with no side effects (fail-closed,
     * journal kept). Otherwise the idempotent `deleteKey(journal.alias)` runs
     * (a delete failure reports [PendingDeleteOutcome.DELETE_FAILED] with the
     * journal kept and no clear attempted), then the fenced
     * `clearIfRevision(journal.revision)` — which removes the journal in the
     * SAME atomic edit on a win — with the same bounded probe + single same-rev
     * retry discipline as the logout clear phase below.
     *
     * Cancellation: [PendingDeleteOutcome.COMMITTED] swallows the cancel to
     * success (the teardown won; mirrors save committed-then-cancel); every
     * other outcome rethrows with the journal kept and no `errorCode` stamped
     * (callers map outcomes to UI codes; this function itself never touches
     * [_form]).
     *
     * Bounded: one strict read + at most one retry clear; no loop, no storm.
     */
    /**
     * [reconcilePendingDelete] 的 journal-identity 攜帶版（Stage C C2）。
     *
     * 與 [reconcilePendingDelete] 同一核心，另回傳 replay 當時快照的 journal：
     * 有 journal 的輪（`COMMITTED`／`DELETE_FAILED`／`FAILED`／`STALE`／
     * clear-phase `UNKNOWN`）攜帶該 [PendingDelete]（供 [onStartupReconcile]
     * 落碼／清碼的身份比對）；僅 `NO_OP` 與 read-path `UNKNOWN`（strict 重讀
     * 失敗或 journal 毀損，身份不可確認）攜帶 null——clear-phase `UNKNOWN`
     *（clear 拋後 probe 重讀亦失敗，見 [runPendingDeleteLocked]）攜帶當時的
     * active pending。取消語義與 [reconcilePendingDelete] 相同（`COMMITTED`
     * 吞取消，其餘重拋）。
     */
    internal suspend fun reconcilePendingDeleteWithJournal(): Pair<PendingDeleteOutcome, PendingDelete?> {
        val triple = reconcilePendingDeleteWithJournalAndGeneration()
        return triple.first to triple.second
    }

    /**
     * Stage D2 generation 攜帶版（內部，[launchPendingDeleteReconcile] settled
     * 作用域化用）。
     *
     * 與 [reconcilePendingDeleteWithJournal] 同一核心（同一 `saveMutex`、
     * 同一 `snapshotDeleteStateStrict` 同 emission、同一
     * `runPendingDeleteLocked`，取消語義不變），另回傳該 emission 的
     * [StartupGeneration]（`revision + journal.format() raw`；read-path
     * `UNKNOWN` 無可用 emission 時為 null）。既有 Pair 入口委託至此（丟棄
     * generation），D1 映射／取消語義不變。
     */
    private suspend fun reconcilePendingDeleteWithJournalAndGeneration(): Triple<PendingDeleteOutcome, PendingDelete?, StartupGeneration?> {
        return try {
            saveMutex.withLock {
                val snapshot = try {
                    store.snapshotDeleteStateStrict()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Read failure OR malformed journal: no-touch, keep journal.
                    return@withLock Triple(PendingDeleteOutcome.UNKNOWN, null, null)
                }
                val pending = snapshot.first
                val generation = StartupGeneration(snapshot.third, pending?.format())
                if (pending == null) {
                    return@withLock Triple(PendingDeleteOutcome.NO_OP, null, generation)
                }
                Triple(runPendingDeleteLocked(pending), pending, generation)
            }
        } catch (e: CancellationException) {
            // runPendingDeleteLocked already swallowed the COMMITTED cancel to
            // success inside; anything reaching here is uncommitted (or queued
            // before the lock): rethrow with the journal kept.
            throw e
        }
    }

    /**
     * Stage D2 rotation 廉價 probe（[launchPendingDeleteReconcile] 用）。
     *
     * 單 `dataStore.data.first()`（經 `snapshotDeleteStateStrict` 同 emission
     * 取 `revision + journal.format()`），零 vault I/O。`suspend` 故須在
     * `startupLock` 外呼叫（見 launch 內鎖外 probe、鎖內比對）；probe→worker
     * 窗口由 worker 內 `saveMutex` 下 strict 重讀兜底（同一核心）。
     * Malformed／讀失敗回 null（未知）：呼叫方視為 generation-changed，
     * fail-closed 走 fresh reconcile（見 [startupGenerationsMatch]）。
     */
    private suspend fun probeStartupGeneration(): StartupGeneration? {
        try {
            val snapshot = store.snapshotDeleteStateStrict()
            return StartupGeneration(snapshot.third, snapshot.first?.format())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Stage D2 generation 比對（純函數，鎖內呼叫）。
     *
     * - `current == null`（probe malformed／讀失敗）→ false（changed，走 fresh）；
     * - `cached.generation == null`（前輪 read-path `UNKNOWN`，無 emission）→
     *   D1 保留例外：僅當該輪為 `UNKNOWN`＋null 且 probe 見無 journal
     *  （`journalRaw == null`）時視為相同（回放中性碼，不在 rotation 自動清除；
     *   外部 journal 出現仍為不同，走 fresh 升級 retained）；其餘一律不同；
     * - 否則 `revision`＋`journalRaw` 皆等才相同（回放），任一不等即不同
     *  （失效後新鮮 worker）。
     */
    private fun startupGenerationsMatch(
        cached: StartupSettled,
        current: StartupGeneration?,
    ): Boolean {
        if (current == null) return false
        val cachedGen = cached.generation
        if (cachedGen == null) {
            return cached.outcome == PendingDeleteOutcome.UNKNOWN &&
                cached.journal == null &&
                current.journalRaw == null
        }
        return cachedGen.revision == current.revision && cachedGen.journalRaw == current.journalRaw
    }

    suspend fun reconcilePendingDelete(): PendingDeleteOutcome =
        reconcilePendingDeleteWithJournal().first

    /**
     * Startup replay entry point: single-flight [reconcilePendingDeleteWithJournal].
     *
     * 語義（Stage C C1，全程 [startupLock] 保護；Stage D2 generation 作用域化）：
     * 1. 未落定（[startupSettled] 為 null）：不做 probe，直走新鮮 worker／入列
     *    （下述 3）；
     * 2. 已落定：先在鎖外做一次廉價 strict 快照（[probeStartupGeneration]，單
     *    `dataStore.data.first()`，零 vault I/O），再在鎖內比對 generation
     *    （見 [startupGenerationsMatch]）：相同 → 經 [Dispatchers.Main] 回放
     *    快照 `(outcome, journal)`（`STALE` 的 gateReady 仍由 [onStartupReconcile]
     *    讀 live gate，保持新鮮；pending 恆 false，vault 零 I/O，只多一讀）；
     *    不同（外部寫入／revision 前進／probe malformed／讀失敗）→ 鎖內失效後
     *    新鮮 worker（probe→worker 窗口由 worker 內 `saveMutex` 下 strict 重讀
     *    兜底，同一核心）。D1 保留：前輪 read-path `UNKNOWN`＋null 且 probe 見
     *    無 journal 時視為相同（回放中性碼，不在 rotation 自動清除）。
     * 3. worker 進行中：呼叫方入列 `onDone`，回傳 `viewModelScope.launch {
     *    worker.join() }` join handle——cancel 只停止等待，不殺 worker
     *    （fence 保持到 worker 落定）；
     * 4. 否則建唯一 worker：`_startupReconcilePending = true`（首發本已
     *    true，重斷言無害）→ 跑 reconcile（含 generation 攜帶，見
     *    [reconcilePendingDeleteWithJournalAndGeneration]）→ 快照＋先寫
     *    `startupSettled` 再 Main fan-out 全部 callbacks → `finally` 單點
     *    `pending = false`（所有 `onDone` 之後，保留先落碼後落定順序）。
     *
     * Never throws out of scope (an escaping failure is reported as
     * [PendingDeleteOutcome.UNKNOWN] with a null journal via [onDone] on
     * [Dispatchers.Main] and keeps the journal for the next logout retry —
     * only read-path `UNKNOWN` carries null; a reconcile-returned clear-phase
     * `UNKNOWN` carries the active pending, see
     * [reconcilePendingDeleteWithJournal]); a cancelled scope rethrows without
     * calling [onDone].
     * Called once at composition from `MainScreen`, which MUST pass a non-empty
     * [onDone] (Stage 3 P2: [onStartupReconcile]) — a discarded outcome would
     * leave a retained journal (gate `NoEndpoint`) with no `errorCode` and no
     * recovery entry. Cancellation semantics unchanged (COMMITTED swallows,
     * everything else rethrows with no `onDone`).
     *
     * Stage B: [startupReconcilePending] stays `true` until [onDone] runs (all
     * outcomes, including `NO_OP`) or the worker/scope is cancelled — the reset
     * is in `finally` so a scope cancel never leaves the startup spinner stuck.
     * Cancelling the returned join handle alone does NOT reset (the worker
     * continues for other joiners). The returned [Job] is only a join handle,
     * never the worker itself: production callers ignore it. Worker-path join
     * (`viewModelScope.launch { worker.join() }`): cancelling it only stops
     * waiting without killing the in-flight worker, and the caller's callback
     * still participates in the Main fan-out (at-least-once; cancelling a
     * waiter must not silently drop the outcome for others). Settled-replay
     * dispatch (`viewModelScope.launch(Dispatchers.Main) { onDone(...) }`):
     * the returned [Job] IS that dispatch itself (no fan-out exists on this
     * path), so cancelling it discards this caller's own `onDone` delivery.
     * Stage D2 probe-path: probe 在鎖外（`suspend` 不可持 `synchronized`），
     * 回放分支同 settled-replay 可取消語義（取消丟棄自身 `onDone`）；新鮮分支
     * 經遞迴 [launchPendingDeleteReconcile] 入列共享 worker（取消僅停止等待，
     * 回調仍經 fan-out at-least-once）。
     */
    fun launchPendingDeleteReconcile(
        onDone: (PendingDeleteOutcome, PendingDelete?) -> Unit,
    ): Job {
        val worker: Job
        synchronized(startupLock) {
            // Settled check BEFORE the worker check: a finished round replays
            // (after D2 generation validation below) without vault I/O even if
            // its (dead) job reference lingers.
            val settled = startupSettled
            if (settled == null) {
                startupCallbacks.add(onDone)
                val active = startupWorker?.takeIf { it.isActive }
                if (active != null) {
                    worker = active
                } else {
                    worker = newStartupWorkerLocked()
                }
            } else {
                // D2: settled exists — async generation validation (probe outside
                // the lock; suspend must not hold synchronized). Pending stays
                // false during the probe; replay keeps it false with only one
                // DataStore read and zero vault I/O.
                return viewModelScope.launch {
                    val current: StartupGeneration? = try {
                        probeStartupGeneration()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    var replay: StartupSettled? = null
                    var needFresh = false
                    synchronized(startupLock) {
                        val latest = startupSettled
                        if (latest == null) {
                            // Invalidated during the probe (logout/save entry):
                            // fall through to a fresh round.
                            needFresh = true
                        } else if (startupGenerationsMatch(latest, current)) {
                            replay = latest
                        } else {
                            startupSettled = null
                            // Stage E probe-changed：generation 變化與 settled 失效對齊，
                            // 同步 bump pending tick（新鮮 worker 的 strict 重讀兜底
                            // outcome，tick 重建兜底 domain flow——內層 `catch` 完成
                            // 後不重建即黏住 phantom present）。
                            bumpPendingTick()
                            needFresh = true
                        }
                    }
                    if (replay != null) {
                        withContext(Dispatchers.Main) {
                            onDone(replay.outcome, replay.journal)
                        }
                        return@launch
                    }
                    if (needFresh) {
                        // Reuse the single-flight fresh path (adds onDone to the
                        // shared callbacks and joins the shared worker). The
                        // probe→worker window is covered by the worker's
                        // saveMutex-guarded strict re-read (same core).
                        launchPendingDeleteReconcile(onDone).join()
                    }
                }
            }
        }
        // Join handle (first and reentrant callers alike): cancelling this
        // stops waiting without touching the shared worker.
        return viewModelScope.launch { worker.join() }
    }

    /**
     * Creates the single-flight startup worker. Callers MUST hold [startupLock]
     * (reads/writes `startupWorker`/`startupCallbacks` under it); the worker
     * body leaves the lock before any suspend (probe→worker 窗口由其內
     * `saveMutex` 下 strict 重讀兜底).
     */
    private fun newStartupWorkerLocked(): Job {
        _startupReconcilePending.value = true
        val fresh = viewModelScope.launch(Dispatchers.IO) {
            try {
                val triple = try {
                    reconcilePendingDeleteWithJournalAndGeneration()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Triple<PendingDeleteOutcome, PendingDelete?, StartupGeneration?>(
                        PendingDeleteOutcome.UNKNOWN,
                        null,
                        null,
                    )
                }
                val result = StartupSettled(triple.first, triple.second, triple.third)
                // Settled write BEFORE the Main fan-out, so any launch
                // landing after this point validates (D2 probe) instead of
                // fanning a second round of I/O.
                val fanOut: List<(PendingDeleteOutcome, PendingDelete?) -> Unit> =
                    synchronized(startupLock) {
                        startupSettled = result
                        val copy = startupCallbacks.toList()
                        startupCallbacks.clear()
                        copy
                    }
                withContext(Dispatchers.Main) {
                    fanOut.forEach { cb -> cb(result.outcome, result.journal) }
                }
            } finally {
                _startupReconcilePending.value = false
            }
        }
        startupWorker = fresh
        return fresh
    }

    /** Drops the cached startup-reconcile outcome (logout exit / save commit). */
    private fun invalidateStartupSettled() {
        synchronized(startupLock) { startupSettled = null }
        // Stage E: journal 存否可能變化處同步重讀 pending domain（bump 點與
        // settled 失效對齊；settled 回放不經此處，故不 bump、零多讀）。
        bumpPendingTick()
    }

    /**
     * Stage E pending-domain re-read tick bump（[pendingTick] 用）。
     *
     * 非 suspend、執行緒安全（[update] 原子遞增）：IO／Main 皆可呼叫。僅重建
     * [pendingTeardown] 內層收集（一次 DataStore 重讀，零 vault I/O）；冪等，
     * 多 bump 一次只多一讀。
     */
    private fun bumpPendingTick() {
        pendingTick.update { it + 1 }
    }

    /**
     * Journal replay core. Callers MUST hold [saveMutex].
     *
     * Idempotent vault delete first ([KeyVault.deleteKey] is a no-op remove
     * for missing keys, so replaying an already-applied delete is safe), then
     * the fenced clear which removes the journal atomically on a win. Cancel
     * semantics: a cancel that still committed swallows to
     * [PendingDeleteOutcome.COMMITTED]; any other cancel rethrows with the
     * journal kept. Never touches [_form] (callers map outcomes).
     */
    private suspend fun runPendingDeleteLocked(pending: PendingDelete): PendingDeleteOutcome {
        try {
            vault().deleteKey(pending.alias)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Delete failed: no clear attempted, journal kept for replay. The
            // key may still be in the vault — Stage 3 DELETE_FAILED (never
            // "already deleted").
            return PendingDeleteOutcome.DELETE_FAILED
        }
        try {
            if (store.clearIfRevision(pending.revision)) {
                return PendingDeleteOutcome.COMMITTED
            }
            // CAS loss: a concurrent winner advanced the revision. The journal
            // is superseded — discard it without touching winner metadata.
            discardSupersededJournal(pending)
            return PendingDeleteOutcome.STALE
        } catch (e: CancellationException) {
            val probe = withContext(NonCancellable) { probeLogoutClear(pending.revision) }
            if (probe == LogoutClearProbe.COMMITTED) {
                return PendingDeleteOutcome.COMMITTED
            }
            throw e
        } catch (_: Exception) {
            val probe: LogoutClearProbe = try {
                withContext(NonCancellable) { probeLogoutClear(pending.revision) }
            } catch (_: CancellationException) {
                LogoutClearProbe.UNKNOWN
            } catch (_: Exception) {
                LogoutClearProbe.UNKNOWN
            }
            return when (probe) {
                LogoutClearProbe.COMMITTED -> PendingDeleteOutcome.COMMITTED
                LogoutClearProbe.STALE -> {
                    discardSupersededJournal(pending)
                    PendingDeleteOutcome.STALE
                }
                LogoutClearProbe.UNKNOWN -> PendingDeleteOutcome.UNKNOWN
                LogoutClearProbe.FAILED -> {
                    val retried: Boolean? = try {
                        withContext(NonCancellable) { store.clearIfRevision(pending.revision) }
                    } catch (_: CancellationException) {
                        null
                    } catch (_: Exception) {
                        null
                    }
                    when (retried) {
                        true -> PendingDeleteOutcome.COMMITTED
                        false -> {
                            discardSupersededJournal(pending)
                            PendingDeleteOutcome.STALE
                        }
                        // Retry transport still broken, but the probe already
                        // established metadata-present-at-same-rev:
                        // known-not-committed, journal kept.
                        null -> PendingDeleteOutcome.FAILED
                    }
                }
            }
        }
    }

    /**
     * Best-effort conditional discard of a superseded journal (STALE path only).
     *
     * Removes ONLY the journal key via
     * [EndpointStore.removePendingDeleteIfMatches]: the discard is a single-edit
     * compare-and-delete against [expected], so a journal recreated for a newer
     * generation between the STALE classification and this discard is kept
     * (mismatch writes nothing — no ABA delete of the replacement) while the
     * STALE report below still holds either way. Winner metadata and the
     * revision survive byte-for-byte on a match. A transport failure keeps the
     * journal (the next replay re-classifies STALE and retries the discard);
     * cancellation rethrows.
     */
    private suspend fun discardSupersededJournal(expected: PendingDelete) {
        try {
            store.removePendingDeleteIfMatches(expected)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Retained for the next replay; the STALE report below still holds
            // (the winner survived regardless of this best-effort cleanup).
        }
    }

    /**
     * Maps a resume-path reconcile outcome to (committed, errorCode).
     *
     * Stage 3 P2 error taxonomy (never claim "already deleted" without a
     * proven delete win):
     * - `DELETE_FAILED` (deleteKey threw: key may still be in the vault) →
     *   `LOGOUT_DELETE_FAILED`;
     * - `FAILED` (delete won + clear known-not-committed: probe saw metadata
     *   at the same rev and the bounded retry still failed) → the only outcome
     *   that may use `LOGOUT_CLEAR_FAILED` ("already deleted" wording);
     * - `UNKNOWN` (strict re-read failed or malformed journal, zero vault I/O,
     *   unconfirmed) → neutral `LOGOUT_UNKNOWN`, never `LOGOUT_CLEAR_FAILED`;
     * - `NO_OP` (dead-mapping on the resume path: the caller only resumes a
     *   present journal, so the replay core never returns it here) → no error,
     *   never `LOGOUT_CLEAR_FAILED`.
     */
    private fun reconcileOutcomeToLogout(outcome: PendingDeleteOutcome): Pair<Boolean, String?> =
        when (outcome) {
            PendingDeleteOutcome.COMMITTED -> true to null
            PendingDeleteOutcome.NO_OP -> true to null
            PendingDeleteOutcome.STALE -> false to "LOGOUT_STALE"
            PendingDeleteOutcome.DELETE_FAILED -> false to "LOGOUT_DELETE_FAILED"
            PendingDeleteOutcome.FAILED -> false to "LOGOUT_CLEAR_FAILED"
            PendingDeleteOutcome.UNKNOWN -> false to "LOGOUT_UNKNOWN"
        }

    /**
     * Single choke point for every `LOGOUT_*` stamp (startup replay via
     * [onStartupReconcile] and all logout paths below).
     *
     * Records the code AND the journal identity ([PendingDelete.format] raw
     * string; null when the stamping context holds no journal) into
     * `form.errorCode` + [SetupUiState.logoutErrorSourceJournal] in one
     * emission, so a later `COMMITTED` replay can identity-clear only the
     * banner sourced from its own journal (see [onStartupReconcile]).
     * Overwrites unconditionally (failure/stale stamps always win); pure state
     * copy, no I/O, safe from any dispatcher.
     */
    private fun stampLogoutError(code: String, journal: PendingDelete?) {
        _form.value = _form.value.copy(
            errorCode = code,
            logoutErrorSourceJournal = journal?.format(),
        )
    }

    /**
     * Startup replay result handler (Stage 3 P2): stamps a
     * [reconcilePendingDelete] outcome into `form.errorCode` so a retained
     * journal (gate `NoEndpoint`) stays recoverable from Settings instead of
     * invisible.
     *
     * Runs on [Dispatchers.Main] as the `MainScreen` `onDone` (direct calls in
     * tests are equivalent — a plain state copy, no I/O):
     * - `COMMITTED`: journal-identity clear (Stage C C2) — clears a
     *   pre-existing `LOGOUT_*` ONLY when [SetupUiState.logoutErrorSourceJournal]
     *   equals this replay's journal ([PendingDelete.format]); a banner sourced
     *   from a concurrent teardown's journal, a journal-less stamp, or any
     *   non-`LOGOUT_*` code is kept. Never stamps. Stage D1 例外：現行碼為
     *   [LOGOUT_READ_RETRY]（sourceJournal==null 的中性碼）時，任何 `COMMITTED`
     *  （同源或 null-tag）皆顯式清除該中性碼（重試證明 teardown 已完成或根本無
     *   journal）；其他 `LOGOUT_*` 仍走同源比對，絕不誤清；
     * - `NO_OP`: silent — writes nothing and never clears (no journal, no identity).
     *   Stage D1 例外：現行碼為 [LOGOUT_READ_RETRY] 時顯式清除（重試確認真無
     *   journal）；其他 `LOGOUT_*` 仍永不清；
     * - `STALE`: explicit decision — silent when [gate] is already `Ready`
     *   (the concurrent winner is live, nothing to dismiss), otherwise stamps
     *   `LOGOUT_STALE` for the dismiss card (overwrites + records the
     *   superseded journal tag, including overwriting [LOGOUT_READ_RETRY]);
     * - `DELETE_FAILED`/`FAILED`: stamps per [reconcileOutcomeToLogout]
     *   (`LOGOUT_DELETE_FAILED` / `LOGOUT_CLEAR_FAILED`), which the Settings
     *   `NoEndpoint` recovery card offers as retry (STALE excepted: dismiss only).
     *   Overwrites + records the tag (including upgrading [LOGOUT_READ_RETRY]);
     * - `UNKNOWN`: Stage D1 分流 — journal==null（read-path：strict 重讀失敗或
     *   journal 毀損，身份不可確認）落中性 [LOGOUT_READ_RETRY]（tag null，未刪
     *   未清，僅重試讀取）；journal!=null（clear-phase：clear 拋後 probe 重讀亦
     *   失敗，攜 active pending）維持中性 retained `LOGOUT_UNKNOWN`（tag 非 null）。
     *   兩者文案皆禁寫「已刪除」；前者禁寫「殘留待清理」。
     *
     * Never touches `logoutInFlight` (startup replay is not a logout attempt);
     * cancellation never reaches here (rethrows with no `onDone`).
     */
    fun onStartupReconcile(outcome: PendingDeleteOutcome, journal: PendingDelete?) {
        onStartupReconcile(outcome, journal, gateReady = gate.value is EndpointGate.Ready)
    }

    /**
     * Test seam for the STALE gate decision above: the production entry reads
     * the live [gate], tests pass the decision input directly (collecting the
     * `WhileSubscribed` gate flow in a unit test races teardown cancellation).
     * [journal] is the replay's journal snapshot (null for `NO_OP` and for
     * read-path `UNKNOWN` whose journal could not be confirmed; clear-phase
     * `UNKNOWN` carries the active `pending`, so a later `COMMITTED` of the
     * same journal may clear its banner).
     *
     * Stage D1: read-path `UNKNOWN` (journal==null) maps to [LOGOUT_READ_RETRY],
     * clear-phase `UNKNOWN` (journal!=null) keeps `LOGOUT_UNKNOWN`. `NO_OP` and
     * `COMMITTED` gain a neutral-only clear path (current code is
     * [LOGOUT_READ_RETRY] → clear; other `LOGOUT_*` untouched).
     */
    internal fun onStartupReconcile(
        outcome: PendingDeleteOutcome,
        journal: PendingDelete?,
        gateReady: Boolean,
    ) {
        if (outcome == PendingDeleteOutcome.COMMITTED) {
            val tag = journal?.format()
            val cur = _form.value
            if (cur.errorCode == LOGOUT_READ_RETRY) {
                // Stage D1: retry proved the teardown done (or truly no journal) —
                // clear the neutral code regardless of the COMMITTED tag identity.
                // Other LOGOUT_* codes still require same-tag match below.
                _form.value = cur.copy(errorCode = null, logoutErrorSourceJournal = null)
                return
            }
            if (tag != null) {
                if (cur.errorCode?.startsWith("LOGOUT_") == true && cur.logoutErrorSourceJournal == tag) {
                    _form.value = cur.copy(errorCode = null, logoutErrorSourceJournal = null)
                }
            }
            return
        }
        if (outcome == PendingDeleteOutcome.NO_OP) {
            // Stage D1: NO_OP proves truly no journal — clear the neutral code only.
            // All other LOGOUT_* (retained or stale) stay silent as before.
            val cur = _form.value
            if (cur.errorCode == LOGOUT_READ_RETRY) {
                _form.value = cur.copy(errorCode = null, logoutErrorSourceJournal = null)
            }
            return
        }
        val code: String? = when (outcome) {
            PendingDeleteOutcome.COMMITTED -> null // Unreachable (handled above).
            PendingDeleteOutcome.NO_OP -> null // Unreachable (handled above).
            PendingDeleteOutcome.STALE ->
                if (gateReady) null else "LOGOUT_STALE"
            PendingDeleteOutcome.DELETE_FAILED -> "LOGOUT_DELETE_FAILED"
            PendingDeleteOutcome.FAILED -> "LOGOUT_CLEAR_FAILED"
            PendingDeleteOutcome.UNKNOWN -> if (journal == null) LOGOUT_READ_RETRY else "LOGOUT_UNKNOWN"
        }
        if (code != null) {
            stampLogoutError(code, journal)
        }
    }

    /**
     * Stage D1 非破壞性重試讀取（中性碼 `LOGOUT_READ_RETRY` 專用恢復入口）。
     *
     * 語義：先經 [invalidateStartupSettled] 丟棄已落定輪（含 `UNKNOWN`+null 的
     * 中性快照；Stage E 起同函數內同步 bump [pendingTick]，重建 domain flow 重讀），
     * 再以 [launchPendingDeleteReconcile] 起唯一新鮮 worker；落定前
     * 的 rotation 重 launch 走既有 single-flight 入列拿 join handle（不跑第二份
     * I/O），落定後重 launch 回放該輪快照。預設 `onDone` 即 [onStartupReconcile]
     *（生產入口讀 live gate；測試可傳自訂回調以斷言 outcome／journal）。
     *
     * 非破壞性：只跑 reconcile（strict 讀＋必要時 idempotent delete＋fenced clear），
     * 絕不調 [logout]（不寫新 journal、不刪 snapshot 外的 key、不碰 [saveMutex]
     * 外的狀態）；不碰 `logoutInFlight`；重試結果由 [onStartupReconcile] 處理：
     * 有 journal（`DELETE_FAILED`／`FAILED`／`UNKNOWN`+J）升級 retained（帶 tag），
     * `NO_OP` 或 `COMMITTED` 顯式清除中性碼（他碼不動），`STALE` 走既有映射。
     *
     * UI 綁定：呼叫方以 [startupReconcilePending] 驅動按鍵 enabled（pending 中
     * disabled＋spinner），不得綁 `logoutInFlight`（重試讀取非登出執行）。
     */
    fun retryStartupReconcile(
        onDone: (PendingDeleteOutcome, PendingDelete?) -> Unit = { outcome, journal ->
            onStartupReconcile(outcome, journal)
        },
    ): Job {
        invalidateStartupSettled()
        return launchPendingDeleteReconcile(onDone)
    }

    /**
     * Clear-phase probe outcome for the logout hardening below.
     *
     * Derived from a single strict re-read
     * ([EndpointStore.snapshotWithRevisionStrict]) after the initial
     * `clearIfRevision` threw: `COMMITTED` means the clear won despite the
     * throw (ack-loss: metadata empty + revision advanced); `FAILED` means the
     * clear did not happen (metadata still present + revision unchanged, or
     * fresh tombstone still at the same revision); `STALE` means a concurrent
     * winner advanced the revision with metadata present (must not retry);
     * `UNKNOWN` means the re-read itself failed (no-touch).
     */
    private enum class LogoutClearProbe {
        COMMITTED,
        FAILED,
        STALE,
        UNKNOWN,
    }

    /**
     * Strict single-emission classification of the clear outcome.
     *
     * Never throws: any read failure (including [CancellationException]) maps
     * to [LogoutClearProbe.UNKNOWN]. Must be called inside
     * `withContext(NonCancellable)` after a clear throw/cancel so the probe
     * itself is not cancelled. Single `dataStore.data.first()` emission, no
     * retry, no vault I/O.
     */
    private suspend fun probeLogoutClear(expectedRevision: Long?): LogoutClearProbe {
        val after: Pair<EndpointConfig?, Long?> = try {
            store.snapshotWithRevisionStrict()
        } catch (_: CancellationException) {
            return LogoutClearProbe.UNKNOWN
        } catch (_: Exception) {
            return LogoutClearProbe.UNKNOWN
        }
        val config = after.first
        val revision = after.second
        return if (config == null) {
            if (revision != expectedRevision) LogoutClearProbe.COMMITTED else LogoutClearProbe.FAILED
        } else {
            if (revision == expectedRevision) LogoutClearProbe.FAILED else LogoutClearProbe.STALE
        }
    }

    /**
     * Logout: journal the teardown, delete the live vault key addressed by
     * metadata, then fenced-clear metadata via [EndpointStore.clearIfRevision]
     * (journal → delete → CAS order matters).
     *
     * Strict cleanup contract:
     * - The (journal, config, revision) triple comes from a single
     *   [EndpointStore.snapshotDeleteStateStrict] emission. A read failure
     *   (or a malformed journal) aborts before any side effect: no journal
     *   write, no vault delete, no metadata clear, no form reset; stamps
     *   `errorCode = LOGOUT_READ_FAILED` and reports `onDone(false)`. A
     *   PRESENT journal resumes instead of starting over: the journal wins and
     *   is never overwritten — the startup-shared [reconcilePendingDelete] core
     *   replays it (idempotent delete + fenced clear). A healthy `null`
     *   (read OK, no metadata, no journal) is strictly distinguished from a
     *   read failure: the delete is skipped but
     *   [EndpointStore.clearIfRevision] still runs to void outstanding
     *   snapshots/consents, and success is reported on a CAS win.
     * - The journal write itself is fenced (Stage 2 P2): it lands only when
     *   the stored generation still matches the snapshot (revision AND raw
     *   `apiKeyRef`). A loss therefore means either a concurrent teardown
     *   journaled first (generation still ours → resume its journal, never
     *   overwrite) or a winner advanced the metadata after our snapshot
     *   (generation moved → `LOGOUT_STALE` with NO vault delete: the snapshot
     *   alias is a predecessor and the winner must never be masked).
     * - Only an alias addressed by a well-formed `provider_key/` ref is
     *   deleted (resolved via [SetupValidation.aliasFromRef], never re-derived
     *   from `providerId`); malformed refs fail closed to skip-delete (still
     *   CAS, no journal — there is no key to resume). An alias that cannot be
     *   journaled (dirty `|`-bearing value) fails closed with
     *   `LOGOUT_DELETE_FAILED` before any side effect.
     *   [dev.librepocket.keystore.KeyVault.deleteKey] is idempotent
     *   (missing key is a no-op remove), so retrying an already-applied delete
     *   is safe; a delete failure aborts before the clear (metadata, key
     *   bytes, and revision untouched) with `LOGOUT_DELETE_FAILED` — but the
     *   journal is KEPT, so readers stay folded to `NO_KEY` and the next
     *   startup/logout replays the teardown (the logout intent stands).
     * - The clear is fenced: `clearIfRevision(expectedRev)` with the snapshot
     *   revision, AFTER the delete, removing the journal in the SAME atomic
     *   edit on a win. A save committing between the delete and
     *   the CAS leaves the winner intact: the CAS returns false (only the
     *   stale predecessor was deleted — harmless), the superseded journal is
     *   discarded WITHOUT touching winner metadata, and logout reports
     *   `LOGOUT_STALE`, never success.
     * - Clear-phase hardening (mirrors the save-side NonCancellable
     *   strict-verify): when the initial `clearIfRevision` throws, the outcome
     *   is classified by a single strict re-read in `NonCancellable` context
     *   ([probeLogoutClear], one `dataStore.data.first()` emission, no vault
     *   I/O):
     *   - `COMMITTED` (metadata empty + revision advanced): the clear won
     *     despite the throw (ack-loss: `apply` flushed but the ack was lost).
     *     Report success — reset the form and call `onDone(true)`, never
     *     `LOGOUT_CLEAR_FAILED`. Mirrors save committed-then-cancel.
     *   - `FAILED` (metadata still present + revision unchanged): the clear
     *     did not happen. Retry once with the SAME revision
     *     (`clearIfRevision(expectedRev)` in `NonCancellable`, bounded single
     *     attempt, no storm): `true` commits to success; `false` means a
     *     winner slipped in — report `LOGOUT_STALE`; throw means the transport
     *     is still broken — report `LOGOUT_CLEAR_FAILED` (retryable: the
     *     metadata still points at the already-deleted alias, so a later
     *     logout retry re-attempts the same fenced clear).
     *   - `STALE` (metadata present + revision moved): a concurrent winner
     *     committed; never retry (a retry would delete the winner's
     *     metadata). Report `LOGOUT_STALE`.
     *   - `UNKNOWN` (re-read failed): no-touch — no second delete, no second
     *     clear, never report success. Report `LOGOUT_UNKNOWN` with
     *     `onDone(false)` (retryable once the store recovers; the journal is
     *     kept — the delete win is unproven because ack-loss + probe failure
     *     is the counterexample, so `LOGOUT_CLEAR_FAILED` is forbidden here).
     *   Bounded: exactly one strict read + at most one retry clear; no loop,
     *   no retry storm. Only a CAS win (initial, retry, or ack-loss
     *   reclassification) resets the form and reports `onDone(true)`; every
     *   abort preserves the form and only stamps `errorCode`.
     * - Cancellation: a cancel landing inside `clearIfRevision` gets the same
     *   strict classification in `NonCancellable`, but with cancel semantics:
     *   `COMMITTED` swallows the cancel to success (reset form +
     *   `onDone(true)`, mirroring save committed-then-cancel); `FAILED`,
     *   `STALE`, and `UNKNOWN` rethrow without stamping any `errorCode` and
     *   without calling [onDone] (no retry on the cancel path — a retry would
     *   commit despite the cancel). A cancel between the delete and the clear
     *   (or between the journal write and the delete) likewise rethrows with
     *   the journal KEPT and no side-effect stamp: the teardown is resumable
     *   by the next startup ([reconcilePendingDelete]) or logout, never
     *   silently stranded. A cancel before any side effect (read/journal
     *   window, or queued for [saveMutex]) likewise rethrows with no stamp.
     *
     * Memory-vs-durable note: the vault delete and the metadata clear are not
     * a distributed transaction (see [dev.librepocket.keystore.EncryptedPrefsVault]
     * durability docs). The same-file journal closes the crash window BETWEEN
     * the vault delete and the metadata clear: a kill landing there restarts
     * into a folded (`NO_KEY`/`NoEndpoint`) state whose journal the startup
     * replay ([launchPendingDeleteReconcile]) completes — the revision still
     * fences stale sessions throughout. Residual (long term, not this phase):
     * save-side staged orphans (put-but-never-committed, committed-but-never-
     * retired) still need a vault list API for GC; cross-process DataStore
     * fencing is still single-process-only.
     *
     * Privileged teardown, serialized against in-flight saves via [saveMutex]
     * so a save's staged-put/CAS/retire window cannot interleave with teardown
     * in this VM. Single-process only: this [Mutex] cannot fence a second
     * process, a second ViewModel/store instance, or any out-of-band vault
     * writer — cross-writer safety rests on the metadata CAS plus the
     * restart-unique staged alias scheme (a stale logout only ever deletes its
     * snapshot alias, never the winner's key). The DataStore CAS itself is
     * single-process (documented on [EndpointStore]); never present this
     * mutex as multi-writer safety.
     *
     * Ref-driven: the deleted alias is resolved from the snapshot's `apiKeyRef`
     * (the staged live alias after a staged commit), never re-derived from
     * `providerId`. Staged aliases from crashed saves (put-but-never-committed,
     * or committed-but-never-retired, or cancelled mid-flight) are unknown here
     * — without a vault list API they cannot be enumerated, so they linger
     * encrypted at rest until a GC follow-up (list API) lands. Documented
     * at-rest leak, strictly better than loss; in-flight aborts and cancels
     * already delete their own staged alias, so the steady state has no residue.
     *
     * Cancellation ([CancellationException]) from the read/delete window — and
     * from a non-committed clear — is always rethrown, like the save
     * path: no `errorCode` is stamped and [onDone] is not called.
     *
     * @param onDone called on [Dispatchers.Main] with `true` only when the
     * teardown committed (delete + fenced clear, including ack-loss
     * reclassification and committed-then-cancel swallow); `false` with
     * `errorCode` set otherwise. Never called when the logout coroutine itself
     * is cancelled (except the committed-then-cancel swallow, which completes
     * normally and reports success).
     *
     * UI 可見性（Re-review #7 Finding 2 階段2）：入口同步置
     * `form.logoutInFlight = true`（持鎖前，仿 saving 語義；執行中再調忽略），
     * 成功經整表重置、失敗經結尾復位、取消（含排隊取消）經外層 catch 復位；
     * 失敗保留 `errorCode` 由 Settings 顯示（[clearLogoutError] 關閉），成功重置。
     */
    fun logout(onDone: (Boolean) -> Unit = {}) {
        // VM 層防重點：執行中再調直接忽略（UI 層另以 disabled + spinner 防連點）。
        if (_form.value.logoutInFlight) return
        // 持鎖前同步置 inFlight（仿 saving 入口語義）：排隊等鎖時被取消也能被
        // 外層 catch 復位。前次失敗碼在此清為 fresh attempt；失敗保留、成功
        // 重置、取消不蓋碼（見結尾與外層 catch）。
        _form.value = _form.value.copy(logoutInFlight = true, errorCode = null, logoutErrorSourceJournal = null)
        // 入口即失效 settled：journal 寫入後被取消時走不到終端失效點，殘留快照
        // 會使 rotation 重 launch 回放過期輪；入口在 Main、worker 寫 settled 在
        // IO，兩種到達順序皆 benign（失效後 worker 寫新鮮值；寫後失效則本輪
        // logout 自己產生終端 outcome）。
        invalidateStartupSettled()
        formRevision.incrementAndGet()
        invalidateKeyWriteConsent()
        modelRefreshJob?.cancel()
        modelRefreshJob = null
        modelRefreshGeneration.incrementAndGet()
        val job = viewModelScope.launch(Dispatchers.IO) {
            // try-catch 在 withLock 之外（仿 save）：排隊等鎖時被取消（lambda
            // 未進入）仍能復位 inFlight；僅內層 catch 會漏此窗口。
            try {
                var committed = false
                var logoutError: String? = null
                var logoutErrorJournal: PendingDelete? = null
                saveMutex.withLock {
                    // Single-emission strict read: pending journal + config +
                    // revision from the same snapshot (no TOCTOU between them).
                    val state = try {
                        store.snapshotDeleteStateStrict()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        stampLogoutError("LOGOUT_READ_FAILED", null)
                        return@withLock
                    }
                    val resumePending = state.first
                    if (resumePending != null) {
                        // An earlier teardown journaled but never finished
                        // (crash/cancel, or a concurrent logout journaled
                        // first): the journal wins — never overwrite it, resume
                        // it via the startup-shared replay core.
                        val (resumed, resumeError) =
                            reconcileOutcomeToLogout(runPendingDeleteLocked(resumePending))
                        committed = resumed
                        logoutError = resumeError
                        logoutErrorJournal = resumePending
                        if (logoutError != null) {
                            stampLogoutError(logoutError, resumePending)
                        }
                        return@withLock
                    }
                    val expectedRevision = state.third
                    val alias = state.second?.apiKeyRef
                        ?.takeIf { it.startsWith("provider_key/") }
                        ?.let { SetupValidation.aliasFromRef(it) }
                        ?.takeIf { it.isNotBlank() }
                    var writtenJournal: PendingDelete? = null
                    if (alias != null) {
                        // 1. Journal BEFORE the vault delete (same-file atomic
                        // edit, never overwrites): a crash/cancel in the
                        // delete→clear window below stays resumable instead of
                        // silently stranded. Construction validates (dirty
                        // unjournalable aliases fail closed here, before any
                        // side effect).
                        val journal = try {
                            PendingDelete(alias, expectedRevision, System.currentTimeMillis())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            stampLogoutError("LOGOUT_DELETE_FAILED", null)
                            return@withLock
                        }
                        val wroteJournal = try {
                            // Fenced (Stage 2 P2): the write lands only when
                            // the generation still matches our snapshot, so a
                            // stale logout can neither journal over nor mask a
                            // winner that committed after we snapshotted.
                            store.writePendingDeleteIfAbsent(
                                journal,
                                expectedRevision,
                                state.second?.apiKeyRef,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Transport failure on the journal write: the
                            // commit may still have won (ack-loss). Proceed
                            // only when OUR journal is provably present;
                            // otherwise abort with everything untouched (no
                            // delete was attempted yet).
                            val journalWon = try {
                                withContext(NonCancellable) {
                                    val current = store.pendingDelete()
                                    current == journal
                                }
                            } catch (_: CancellationException) {
                                false
                            } catch (_: Exception) {
                                false
                            }
                            if (!journalWon) {
                                // Unconfirmed (journal write may or may not have
                                // won, delete not yet attempted): neutral
                                // retryable code, never CLEAR_FAILED ("already
                                // deleted" requires a proven delete win).
                                stampLogoutError("LOGOUT_UNKNOWN", null)
                                return@withLock
                            }
                            // Ack-loss with OUR journal provably present: the
                            // teardown below owns this identity — track it so
                            // failure stamps carry the tag (and a later
                            // same-journal COMMITTED replay may clear them).
                            writtenJournal = journal
                            true
                        }
                        if (!wroteJournal) {
                            // Fenced journal loss (Stage 2 P2): either a
                            // concurrent teardown journaled first (generation
                            // still ours → resume its journal, never
                            // overwrite), or a winner advanced the metadata
                            // after our snapshot (generation moved → STALE: no
                            // vault delete — our snapshot alias is a
                            // predecessor and the winner must never be masked
                            // or touched — and report LOGOUT_STALE). If the
                            // journal is already gone but the generation is
                            // still ours, fall through to the fenced clear
                            // below, which fences stale as usual.
                            val current = try {
                                store.snapshotDeleteStateStrict()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Fenced-loss re-read failed before any delete:
                                // unconfirmed, neutral retryable code (never
                                // CLEAR_FAILED).
                                stampLogoutError("LOGOUT_UNKNOWN", null)
                                return@withLock
                            }
                            val snapshotRef = state.second?.apiKeyRef
                            val generationMoved = current.third != expectedRevision ||
                                (snapshotRef != null && current.second?.apiKeyRef != snapshotRef)
                            if (generationMoved) {
                                logoutError = "LOGOUT_STALE"
                                logoutErrorJournal = null
                                stampLogoutError(logoutError, null)
                                return@withLock
                            }
                            val presentJournal = current.first
                            if (presentJournal != null) {
                                val (resumed, resumeError) =
                                    reconcileOutcomeToLogout(runPendingDeleteLocked(presentJournal))
                                committed = resumed
                                logoutError = resumeError
                                logoutErrorJournal = presentJournal
                                if (logoutError != null) {
                                    stampLogoutError(logoutError, presentJournal)
                                }
                                return@withLock
                            }
                        } else {
                            writtenJournal = journal
                        }
                        // 2. Idempotent vault delete. A failure aborts before
                        // the clear with the journal KEPT: readers stay folded
                        // to NO_KEY and the next startup/logout replays the
                        // teardown (the logout intent stands).
                        try {
                            vault().deleteKey(alias)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            stampLogoutError("LOGOUT_DELETE_FAILED", writtenJournal)
                            return@withLock
                        }
                    }
                    try {
                        val cleared = store.clearIfRevision(expectedRevision)
                        if (cleared) {
                            // Win removes metadata AND the journal atomically.
                            committed = true
                        } else {
                            // CAS loss: a concurrent winner advanced the
                            // revision. Our journal (if any) is superseded —
                            // discard it without touching winner metadata.
                            writtenJournal?.let { discardSupersededJournal(it) }
                            logoutError = "LOGOUT_STALE"
                            logoutErrorJournal = writtenJournal
                        }
                    } catch (e: CancellationException) {
                        // Cancel during the clear: same strict probe, cancel
                        // semantics — COMMITTED swallows to success, everything
                        // else rethrows with no code and no onDone. No retry on
                        // the cancel path (a retry would commit despite cancel).
                        // Bounded: single NonCancellable read, no second clear.
                        // COMMITTED implies the journal left atomically with
                        // the clear; otherwise it stays for replay.
                        val probe = withContext(NonCancellable) { probeLogoutClear(expectedRevision) }
                        if (probe == LogoutClearProbe.COMMITTED) {
                            committed = true
                        } else {
                            throw e
                        }
                    } catch (_: Exception) {
                        // Transport failure: strict probe in NonCancellable,
                        // then at most one same-rev retry (bounded, no storm).
                        val probe: LogoutClearProbe = try {
                            withContext(NonCancellable) { probeLogoutClear(expectedRevision) }
                        } catch (_: CancellationException) {
                            LogoutClearProbe.UNKNOWN
                        } catch (_: Exception) {
                            LogoutClearProbe.UNKNOWN
                        }
                        when (probe) {
                            LogoutClearProbe.COMMITTED -> {
                                // Ack-loss: the clear won but the ack was lost.
                                // Success, never CLEAR_FAILED.
                                committed = true
                            }
                            LogoutClearProbe.STALE -> {
                                // Winner advanced: our journal (if any) is
                                // superseded — discard without touching winner.
                                writtenJournal?.let { discardSupersededJournal(it) }
                                logoutError = "LOGOUT_STALE"
                                logoutErrorJournal = writtenJournal
                            }
                            LogoutClearProbe.UNKNOWN -> {
                                // Re-read failed: the clear may still have won
                                // (ack-loss + probe failure is the
                                // counterexample), so the delete win is
                                // unproven — neutral retryable code with the
                                // journal kept, never CLEAR_FAILED.
                                logoutError = "LOGOUT_UNKNOWN"
                                logoutErrorJournal = writtenJournal
                            }
                            LogoutClearProbe.FAILED -> {
                                val retried: Boolean? = try {
                                    withContext(NonCancellable) { store.clearIfRevision(expectedRevision) }
                                } catch (_: CancellationException) {
                                    null
                                } catch (_: Exception) {
                                    null
                                }
                                when (retried) {
                                    true -> committed = true
                                    false -> {
                                        // A winner slipped in between the probe
                                        // and the retry: superseded journal
                                        // goes, winner metadata stays.
                                        writtenJournal?.let { discardSupersededJournal(it) }
                                        logoutError = "LOGOUT_STALE"
                                        logoutErrorJournal = writtenJournal
                                    }
                                    null -> {
                                        logoutError = "LOGOUT_CLEAR_FAILED"
                                        logoutErrorJournal = writtenJournal
                                    }
                                }
                            }
                        }
                    }
                    if (logoutError != null) {
                        stampLogoutError(logoutError, logoutErrorJournal)
                    }
                }
                // Stage C C1: any logout terminal (success or failure) drops the
                // cached startup-reconcile outcome — the metadata generation may
                // have moved (commit, supersede, or discard), so a rotation
                // relaunch must run fresh I/O instead of replaying a stale round.
                // (Cancel rethrows before reaching here: the journal is kept, and
                // the entry-point invalidation already dropped the cached round,
                // so a rotation relaunch runs fresh I/O and rediscovers it.)
                invalidateStartupSettled()
                if (!committed) {
                    // logoutError is already stamped inside the lock when set;
                    // when the lock body returned early via the read/delete
                    // aborts above, the stamp is likewise already applied. Only
                    // stamp here if a code was chosen but not yet applied (all
                    // paths above stamp eagerly, so this is a no-op guard).
                    // 結尾復位 inFlight（保留 errorCode 供 Settings 顯示）。
                    _form.value = _form.value.copy(logoutInFlight = false)
                    withContext(Dispatchers.Main) { onDone(false) }
                    return@launch
                }
                _form.value = SetupUiState(model = ProviderCatalog.defaultModelFor(ProviderCatalog.OPENAI_ID))
                // Committed-then-cancel swallow must still report success: the
                // job was cancelled, so a plain Main dispatch would rethrow
                // Cancel before onDone runs. NonCancellable preserves the
                // save-side mirror (committed cancels report success).
                withContext(NonCancellable + Dispatchers.Main) { onDone(true) }
            } catch (e: Exception) {
                // 取消（含排隊等鎖時被取消、lambda 未進入）與任何未預期異常皆
                // 先復位 inFlight 再重拋：取消不蓋 errorCode、不調 onDone（既有
                // 語義）；未預期異常亦不發明新碼，只保證 spinner 不卡死。
                _form.value = _form.value.copy(logoutInFlight = false)
                throw e
            }
        }
        lastLogoutJobForTest = job
    }

    /**
     * 關閉登出失敗卡（Settings 取消語義：不調 logout）。
     *
     * 僅清除 `LOGOUT_*` 碼，其他碼（如 save 驗證碼）保留；inFlight 中調用只清
     * 碼、不影響執行（執行中取消鍵本就 disabled，此為防禦性語義）。
     */
    fun clearLogoutError() {
        val cur = _form.value
        if (cur.errorCode?.startsWith("LOGOUT_") == true) {
            _form.value = cur.copy(errorCode = null, logoutErrorSourceJournal = null)
        }
    }

    private fun effectiveBaseUrlOf(form: SetupUiState): String =
        if (form.presetId == ProviderCatalog.CUSTOM_ID) {
            form.baseUrl.trim()
        } else {
            ProviderCatalog.requirePreset(form.presetId).baseUrl
        }

    private fun endpointConfigOf(
        form: SetupUiState,
        effectiveBaseUrl: String,
        providerId: String,
        apiKeyRef: String,
    ): EndpointConfig {
        val preset = ProviderCatalog.requirePreset(form.presetId)
        val protocol = if (form.presetId == ProviderCatalog.CUSTOM_ID) {
            dev.librepocket.provider.ProviderProtocol.CHAT_COMPLETIONS
        } else {
            preset.protocol
        }
        return EndpointConfig(
            providerId = providerId,
            presetId = form.presetId,
            label = preset.label,
            baseUrl = effectiveBaseUrl,
            protocol = protocol,
            model = form.model.trim().ifEmpty { preset.defaultModel },
            apiKeyRef = apiKeyRef,
        )
    }

    private fun providerConfigOf(form: SetupUiState, effectiveBaseUrl: String): ProviderConfig {
        val providerId = SetupValidation.deriveProviderId(form.presetId)
        // In-memory transport mapping only (never persisted): the fixed ref is
        // a placeholder — the key itself travels via the KeyProvider closure.
        return endpointConfigOf(form, effectiveBaseUrl, providerId, SetupValidation.deriveApiKeyRef(providerId))
            .toProviderConfig()
    }

    private fun classifyTestError(e: ProviderFailure): String {
        if (e.code == ProviderFailureCode.TOO_LARGE) return "TEST_TOO_LARGE"
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        if (!e.retryable &&
            ("401" in msg || "403" in msg || "missing_api_key" in msg || "unauthorized" in lower)
        ) {
            return "TEST_UNAUTHORIZED"
        }
        if (e.retryable) return "TEST_RETRYABLE"
        return "TEST_FAILED"
    }

    override fun onCleared() {
        modelRefreshJob?.cancel()
        super.onCleared()
    }
}

/** Production wiring (vault construction stays off the main thread). */
class SetupViewModelFactory(app: Application) : ViewModelProvider.Factory {
    private val store = EndpointStore(app)
    private val policy: PolicyStore = DataStorePolicyStore(app)

    @Volatile
    private var vault: KeyVault? = null
    private val vaultSource = VaultSource {
        vault ?: withContext(Dispatchers.IO) {
            vault ?: EncryptedPrefsVault(app.applicationContext).also { vault = it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SetupViewModel(store, vaultSource, policy) as T
    }
}
