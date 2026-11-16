package dev.librepocket.agent.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import dev.librepocket.shell.FlavorPrivilegeScope
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.PrivilegeBridgeStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「一鍵收回」UI 狀態機（Re-review #7 Finding 3 / P2 UX·lifecycle，#136-B1）。
 *
 * 背景：舊實作把 inFlight／error 只放 composition `remember`（`MainScreen`），
 * 配置變更即重置，而撤權 Job 是 `rememberCoroutineScope` 掛的、在途中可繼續以
 * `withContext(NonCancellable)` 執行，且結果只寫回已死掉的 composition state——
 * 重建後的 UI 看不到進度／舊失敗，使用者還能重複進入撤權（inFlight 守衛是
 * per-composition）。此狀態機由 [PrivilegeRevokeViewModel]（`viewModelScope`，
 * 存活過配置變更）持有，重建後的 UI 收集到同一真相。
 *
 * 狀態語義：成功折疊回 [Idle]（banner 只為失敗存在，對齊既有 UX：成功無提示、
 * 失敗才顯示「收回失敗」）；[InFlight] 期間按鈕停用；[Error] 直到下一次嘗試
 * （Idle→InFlight 清除舊錯誤）或成功收尾。
 */
internal sealed interface PrivilegeRevokeUiState {
    /** 無在途撤權、無失敗（或已成功收回）。 */
    data object Idle : PrivilegeRevokeUiState

    /** 撤權執行中（失敗 banner 隱藏；新一次嘗試已開始）。 */
    data object InFlight : PrivilegeRevokeUiState

    /** 撤權未能完整落實／成敗未知（失敗 banner 顯示，直到下一次嘗試或成功）。 */
    data object Error : PrivilegeRevokeUiState
}

/**
 * 「一鍵收回」VM（Re-review #7 Finding 3 / P2，#136-B1）。
 *
 * - 執行於 `viewModelScope`（存活配置變更；刻意不用 `rememberCoroutineScope`）。
 * - [revoke] 雙擊守衛是 VM 級（非 per-composition）：[PrivilegeRevokeUiState.InFlight]
 *   直接返回，executor 恰執行一次。
 * - 產品路徑直接沿用 [executePrivilegeRevoke]（true = 撤權失敗 ⇒ [PrivilegeRevokeUiState.Error]／
 *   false ⇒ [PrivilegeRevokeUiState.Idle]）；旗標極性與 helper 一致，不反轉。
 *   `isScopeActive` 接 `viewModelScope.isActive`（配置變更後 VM 仍存活 ⇒ true，
 *   CE-with-active-scope 折為 banner=true——正確；真正的行程死亡則 scope／VM 皆滅）。
 * - 撤權時間點才組合 `PrivilegeBridgeState.process()`（行程單例）與
 *   `PrivilegeBridgeStore(app)`（與舊 `remember` 路徑同一來源）。
 * - 收尾恆解析（`finally`）：執行中不會停留在 [PrivilegeRevokeUiState.InFlight]；
 *   executor 拋 Throwable 時先落 [PrivilegeRevokeUiState.Error] 再原樣上拋——
 *   VirtualMachineError／外部取消等 fatal 路徑與舊 catch→rethrow 行為一致
 *   （`viewModelScope` 為 SupervisorJob：CE 被吞、非 CE 經未捕獲異常處理器崩潰，
 *   與舊 `rememberCoroutineScope` 相同）。
 * @param executor 與 [executePrivilegeRevoke] 同極性：true = 撤權未能完整落實（banner），
 *   false = 撤權目標已落實。
 */
internal class PrivilegeRevokeViewModel(
    private val app: Application,
    private val withdraw: suspend () -> Unit,
    private val executor: (suspend (PrivilegeBridgeState, PrivilegeBridgeStore) -> Boolean)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PrivilegeRevokeUiState>(PrivilegeRevokeUiState.Idle)

    /** 撤權 UI 真相（StateFlow，重建後的新收集者即見當前值）。 */
    val uiState: StateFlow<PrivilegeRevokeUiState> = _uiState.asStateFlow()

    fun revoke() {
        if (_uiState.value is PrivilegeRevokeUiState.InFlight) return
        _uiState.value = PrivilegeRevokeUiState.InFlight
        viewModelScope.launch {
            try {
                // 行程單例（與舊 remember 路徑相同）；Store 每次撤權組合一次。
                val state = PrivilegeBridgeState.process()
                val store = PrivilegeBridgeStore(app)
                _uiState.value = if (runExecutor(state, store)) {
                    PrivilegeRevokeUiState.Error
                } else {
                    PrivilegeRevokeUiState.Idle
                }
            } catch (t: Throwable) {
                // 執行器逃出 Throwable（含 VirtualMachineError／真正的外部取消的 CE）：
                // 先落 Error（絕不讓 UI 停在 InFlight），再原樣上拋，維持與舊路徑相同的
                // fatal 語義（見類別 KDoc）。
                _uiState.value = PrivilegeRevokeUiState.Error
                throw t
            } finally {
                // 舊 finally{ inFlight=false } 的對應：任何路徑都不殘留 InFlight。
                if (_uiState.value is PrivilegeRevokeUiState.InFlight) {
                    _uiState.value = PrivilegeRevokeUiState.Error
                }
            }
        }
    }

    private suspend fun runExecutor(
        state: PrivilegeBridgeState,
        store: PrivilegeBridgeStore,
    ): Boolean {
        val injected = executor
        if (injected != null) return injected(state, store)
        // 產品路徑直接沿用 [executePrivilegeRevoke]（true = 撤權失敗 ⇒ Error／false ⇒ Idle）；
        // 旗標極性與 helper 一致，不反轉（含 CE 判別、isScopeActive 接法）。
        return executePrivilegeRevoke(
            privilegeState = state,
            privilegeStore = store,
            withdraw = withdraw,
            isScopeActive = { viewModelScope.isActive },
        )
    }
}

/**
 * [PrivilegeRevokeViewModel] 的 [ViewModelProvider.Factory]（與既有 factory 同風格：
 * 直接覆寫標準 `create(modelClass)`、只認本類別）。產品路徑經
 * `viewModel(factory = PrivilegeRevokeViewModelFactory(app), viewModelStoreOwner = …)` 取得
 * （owner 解析見 [PrivilegeRevokeWiring]／[findComponentActivity]，Re-review #9）；
 * 接線測試注入假 executor。
 */
internal class PrivilegeRevokeViewModelFactory(
    private val app: Application,
    private val withdraw: suspend () -> Unit = { FlavorPrivilegeScope.withdraw(app) },
    private val executor: (suspend (PrivilegeBridgeState, PrivilegeBridgeStore) -> Boolean)? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PrivilegeRevokeViewModel::class.java)) {
            return PrivilegeRevokeViewModel(app, withdraw, executor) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

/**
 * 從 Compose 的 [LocalContext] 沿 [ContextWrapper] 鏈向上解析宿主 [ComponentActivity]。
 *
 * Re-review #9：`LocalViewModelStoreOwner.current`（或 `viewModel(...)` 的預設
 * owner）會落在 composition 最近的 ViewModelStoreOwner——Settings destination 的
 * NavBackStackEntry（production：`StartupGateHost` 的 NavHost { composable(SETTINGS) }），
 * 不是 Activity。導航 pop 掉 SETTINGS 時該 entry 的 ViewModelStore 會被清空，revoke VM
 * 連帶銷毀，而 revoke() 的 `withContext(NonCancellable)` 仍在跑——重進 Settings 只能
 * 拿到全新（Idle）VM，在途狀態／失敗 banner 全失。此處刻意以 context 鏈解析 Activity
 * owner，與 composition 深度（NavHost 目的地條目）無關。
 *
 * 非 Activity 終端（如 application context）回傳 null，呼叫方負責 fail-closed。
 */
internal tailrec fun Context.findComponentActivity(): ComponentActivity? =
    this as? ComponentActivity
        ?: (this as? ContextWrapper)?.baseContext?.findComponentActivity()

/**
 * 「一鍵收回」接線單一來源（Re-review #7 Finding 3；owner 解析見 Re-review #9）。
 *
 * `MainScreen` 與接線測試共用此 composable：`viewModel` 取得、`uiState` 收集
 * （`collectAsStateWithLifecycle`）、`(onRevokePrivilege, inFlight, error)` 三元
 * 組 content——禁止測試內重抄 `viewModel(...)`／收集邏輯，否則生產接線與測試分叉。
 * `content` 由呼叫方決定（生產傳 `SettingsScreen` 的三參接線；測試傳探針 UI），
 * SettingsScreen API 不變。
 *
 * viewModel owner 預設為宿主 [ComponentActivity]（[LocalContext] 經
 * [findComponentActivity] 沿 context 鏈解析；Re-review #9 修復）——與 composition
 * 深度無關：NavHost destination 的 [androidx.navigation.NavBackStackEntry]
 * （如 SETTINGS 目的地）不是 owner，不會被諮詢。顯式傳入 [viewModelStoreOwner]
 * 可覆寫（例如未來穩定的 root navigation-graph owner）。
 *
 * 保證（同一保證的兩個釘測面）：pop 掉 Settings（正常 CHAT→SETTINGS 使用者路徑中的
 * 抽屜「新對話」導航）不會銷毀本 VM——owner 是 Activity 而非 SETTINGS 條目，撤權在途
 * 狀態／失敗 banner 跨 pop 存活（與配置變更無關；範圍為 Activity／畫面生命週期）。
 * 配置變更（重建）保留由 `PrivilegeRevokeWiringTest` 釘住，Settings-pop 保留由
 * nav-pop 整合測試（`PrivilegeRevokeNavPopTest`）釘住。Re-review #9。
 *
 * 注意：owner 解析失敗（Compose context 無 Activity，例如非 MainActivity 宿主）會
 * 直接 `error(...)` fail-closed，絕不退回 NavBackStackEntry。
 */
@Composable
internal fun PrivilegeRevokeWiring(
    app: Application,
    withdraw: suspend () -> Unit,
    executor: (suspend (PrivilegeBridgeState, PrivilegeBridgeStore) -> Boolean)? = null,
    viewModelStoreOwner: ViewModelStoreOwner? = null,
    content: @Composable (onRevokePrivilege: () -> Unit, inFlight: Boolean, error: Boolean) -> Unit,
) {
    val owner = viewModelStoreOwner
        ?: LocalContext.current.findComponentActivity()
        ?: error(
            "PrivilegeRevokeWiring: no Activity available in Compose context " +
                "(expected MainActivity-hosted MainScreen); refusing to bind the revoke " +
                "ViewModel to the Settings NavBackStackEntry (Re-review #9)",
        )
    val vm: PrivilegeRevokeViewModel = viewModel(
        factory = PrivilegeRevokeViewModelFactory(app, withdraw, executor),
        viewModelStoreOwner = owner,
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    content(
        vm::revoke,
        uiState is PrivilegeRevokeUiState.InFlight,
        uiState is PrivilegeRevokeUiState.Error,
    )
}
