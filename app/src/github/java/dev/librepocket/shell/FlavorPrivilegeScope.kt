package dev.librepocket.shell

import android.content.Context
import androidx.annotation.VisibleForTesting
import dev.librepocket.privilege.github.ShizukuGrantStore
import dev.librepocket.privilege.github.ShizukuPermissionRequests

/**
 * github flavor 的跨域撤權接線（#136-B1）：detach 上游 listener → 清 Shizuku 授權 Store。
 *
 * Store 實例以 application context 生命週期緩存（行程單例 idiom，[@Volatile] +
 * 身分檢查）：底層 `preferencesDataStore` delegate 行程內共享且永遠綁定
 * 「首位 accessor」的 filesDir（行程生命週期內不重綁）；換綁不影響 delegate
 * 落盤路徑，僅重新對齊 marker 檔路徑與撤權旗標的 owner——避免持有已過期
 * Context 寫入舊 filesDir 的 marker。撤權語義不變（每次呼叫仍完整執行
 * marker → journal → 擦除序列；復用實例的記憶體 pending 旗標與全新實例的
 * marker 檔分支皆 fail-closed）。傳入不同 applicationContext（如測試環境多個
 * Application 實例）時，以身分檢查換綁全新 Store。
 *
 * 失敗合成（fail-closed）：detach 與 clear 各自隔離 try/catch；clear 無論
 * detach 是否失敗皆執行；兩者皆失敗時 detach 的失敗為主失敗、clear 的失敗
 * 以 suppressed 附加（見 withdraw()）。
 */
object FlavorPrivilegeScope {
    @Volatile
    private var cachedStore: ShizukuGrantStore? = null

    /** 緩存 Store 綁定的 applicationContext（身分比較；不緩存錯誤綁定）。 */
    @Volatile
    private var cachedStoreContext: Context? = null

    /**
     * #136-B1 測試專用：撤權時以此取代 cross域 clear 呼叫（production 恆 null）。
     * 供 withdraw() 合成邏輯單測強制 clear 失敗——真實 [ShizukuGrantStore.clear]
     * 的持久語義（journal/marker/自癒）由 ShizukuGrantStoreRobolectricTest 與
     * `withdrawDetachFailure_throwsDetachError_andStoreIsCleared`（真實 clear）
     * 承證；本 hook 只隔離出「clear 失敗時合成契約」，因 `preferencesDataStore`
     * delegate 之 JVM 內共享綁定（同 fork 首位 store 使用者綁定其 filesDir），
     * 檔案級 sabotage 在任意測試類順序下皆不可靠。
     */
    @Volatile
    @VisibleForTesting
    internal var grantStoreClearForTest: (suspend (Context) -> Unit)? = null

    private fun grantStore(context: Context): ShizukuGrantStore {
        val appContext = context.applicationContext ?: context
        cachedStore?.let { existing ->
            // applicationContext 身分不變 → 復用；否則換綁（spent/新 Application）。
            if (cachedStoreContext === appContext) return existing
        }
        // 競態：兩個並發 withdraw 可能各建一個 Store——皆共享進程內同一
        // preferencesDataStore，最差情況多一次建構，無正確性影響；後者覆蓋。
        return ShizukuGrantStore(appContext).also {
            cachedStore = it
            cachedStoreContext = appContext
        }
    }

    suspend fun withdraw(context: Context) {
        var failure: Throwable? = null
        try {
            ShizukuPermissionRequests.process().detach()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            val clearForTest = grantStoreClearForTest
            if (clearForTest != null) {
                clearForTest(context)
            } else {
                grantStore(context).clear()
            }
        } catch (t: Throwable) {
            if (failure != null) {
                failure.addSuppressed(t)
            } else {
                failure = t
            }
        }
        if (failure != null) throw failure
    }
}
