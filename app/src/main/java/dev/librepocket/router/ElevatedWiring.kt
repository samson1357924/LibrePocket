package dev.librepocket.router

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.boundedSupplierCall
import dev.librepocket.shell.frozenOrNull
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * `shell.elevated` 接線形狀（#136-B3，JVM 可驗收部分；裝置 E2E 明確不做）。
 *
 * - executor 綁定：執行一律經 [ElevatedDispatch]（門禁先行＋凍結＋審計必行——除
 *   CancellationException／Error 外恰一筆）；
 *   本對象只做接線形狀（每次 call 重新投影＋fresh policy＋ASK 確認＋不可變快照＋
 *   確認後 spawn 前重驗），不自建子進程、不碰真 Binder／su；
 * - 每次 call 重新投影：[ctxSupplier] 每次新鮮求值（flavor＋開關＋授權），
 *   確認前後各投影一次（spawn 前重驗）；
 * - fresh policy：[policyStore] 每次 [PolicyStore.evaluateFresh]（`tool.exec`，
 *   1s 配合作式逾時——掛起點觸發；對永不掛起之實作不保證上界，產品以
 *   DataStore 系（具暫停點）為準，in-memory 假實作本來就協作；逾時視同
 *   verdict=null → 拒），僅 ALLOW 放行——ASK／DENY／異常一律拒（ASK 不默認為 ALLOW）；
 * - ASK 確認：[PrivilegeGate] 四參 args-aware（`reasonCode` 非空＋`confirmed`），
 *   `confirmed=false`／空白 `reasonCode` 一律 `NeedConfirm`→拒，絕不靜默放行；
 * - 不可變快照 [ElevatedSnapshot]：argv（防禦拷貝＋不可變包裝）／
 *   reasonCode／flavor／投影開關（freeze 後的不可變有界拷貝）／grant 身份
 *   （grantProviderId／grantConfigRevision）；
 *   確認後 spawn 前重驗快照一致性（開關／flavor／已授權權限集合／grant 身份／policy 任一翻轉、
 *   argv 被竄改即作廢，零 spawn；reason 為不可變 String、入口凍結後只讀快照，不存在竄改面）。
 *   argv 受監督比對共兩個重驗點，任一點觀察到呼叫方原始 list 竄改即作廢（零 spawn）：
 *   （1）重驗入口（freeze ctx2 之後、ctx 一致性比對之前）；
 *   （2）grant2 最終有效性確認之後、dispatch 之前（最終防線，封閉「確認→改→spawn」缺口）。
 *   dispatch 之後的竄改不在此保證（也無需）：runner 只吃不可變快照 argv，審計綁定 snapshot；
 *   已授權權限集合比對兩份皆為我方
 *   unmodifiableSet，equals 安全有界，失敗→DENY；
 * - `ToolRegistry` 的 `shell.elevated` 維持 `executionReady=false`（B3 不翻旗——
 *   先接線形狀＋測試，翻旗是生產啟用決策，留待裝置 E2E 後；見接線測試 pin）。
 *
 * 零 Android 依賴，JVM 單測可直接斷言。產品 runner 由 github 源集注入
 * （`RootSuRunner`／`ShizukuShellRunner`，每次新鮮供給 `bridgeGranted`／
 * `grantCheck`，見各 runner KDoc）；本層只消費 supplier，不自建快取。
 *
 * 依賴 #166（B1）先合併：grant 持久真相源（`PrivilegeBridgeStore`＋
 * `ShizukuGrantStore`）由 B1 落定；本層以最小抽象 [ElevatedGrant] 佔位
 * （github 側 `GrantCheck.Valid／Invalid` 對映，rebase 後以 B1 持久版為準）。
 *
 * Rebase 註記（#166，寫進 PR 描述用）：
 * - B1 的 `PrivilegeBridgeStore`（DataStore）實現
 *   [dev.librepocket.shell.PrivilegeRevokeStore]（rebase 時加
 *   `: PrivilegeRevokeStore`），並刪除本分支的重複 overload／佔位
 *   `InMemoryPrivilegeRevokeStore`（改用 B1 持久版；見
 *   [dev.librepocket.shell.PrivilegeBridgeState] KDoc）；
 * - grant 有效性以 B1 `PrivilegeGrantCheck.check()`（現 B1 實作：
 *   `ShizukuGrantPolicy.check`／`ShizukuGrantStore.check`，rebase 時對齊命名）
 *   為準，本層 [ElevatedGrant.Valid] 僅為接線形狀的身份快照佔位。
 * - [ElevatedGrant.Invalid] 的 supplier reason 不回顯於 Denied message（可能夾帶綁定錯誤細節）：
 *   message 固定為 `grant invalid (fail-closed, no spawn)`，審計面本來就只有 hash。
 *
 * 殘餘 TOCTOU：重驗通過→spawn 之間仍有檢查→使用殘餘窗（revalidate 與 spawn
 * 非原子；無任何鎖可讓「重驗的 policy／bridge／grant／ctx」與「runner 實際
 * 可見狀態」在此窗口內保證一致）。本層不宣稱消除此窗；終門把關由 github runner
 * `awaitPreSpawn`（同 deadline 監督）＋ `ELEVATED_RUNNERS_READY` 旗把關，
 * 未就緒時 runner 一律拒絕 spawn。
 */
object ElevatedWiring {

    /** 接線工具名（與 [ToolRegistry] 註冊同名）。 */
    const val TOOL_NAME: String = PrivilegeGate.ELEVATED_TOOL

    /** 執行依據 policy 動作（與 `SafeToolSlice` 同動作；本層要求 ALLOW，ASK 即拒）。 */
    const val POLICY_ACTION: String = "tool.exec"

    /**
     * scope grant 最小抽象（#136-B3 接線形狀，依賴 #166）。
     *
     * - github 側 `GrantCheck.Valid`↔[Valid]、`GrantCheck.Invalid(reason)`↔[Invalid]；
     * - [Valid] 攜帶授權綁定身份（`providerId`＋`configRevision`，見
     *   `ShizukuGrant`）：快照時連同 [ElevatedSnapshot] 一起固定，重驗時比對
     *   一致性——endpoint 切換（providerId 翻轉）或任何設定寫入推進 revision
     *   後，舊授權即作廢（fail-closed，零 spawn），禁止沿用不相符的 credential；
     * - 空白 `providerId` 視為 malformed（接線側 fail-closed 拒，不靜默放行）；
     * - rebase #166 後以 B1 `PrivilegeGrantCheck.check()` 為準（現 B1 實作：
     *   `ShizukuGrantPolicy.check`／`ShizukuGrantStore.check`，rebase 時對齊命名）；
     *   產品 `grantCheck` 供給責任：每次新鮮求值
     *   `{ store.check(freshProviderId, freshRevision) }` 的 blocking 包裝，
     *   `Valid` 時同時嵌入同一 emission 的身份（禁止分兩次讀 provider／revision
     *   的撕裂混代）；
     * - [Invalid] 的 supplier reason 不回顯於 Denied message（可能夾帶綁定錯誤細節；
     *   message 固定為 `grant invalid (fail-closed, no spawn)`，審計面本來就只有 hash）。
     */
    sealed interface ElevatedGrant {
        data class Valid(val providerId: String, val configRevision: Long) : ElevatedGrant
        data class Invalid(val reason: String) : ElevatedGrant
    }

    /**
     * 不可變接線快照（確認後 spawn 前重驗的比對基準）：
     * - [argv] 為入口防禦拷貝＋不可變包裝（呼叫方保留 `MutableList` 並在 grant／
     *   bridge supplier 內竄改不影響本份）；
     * - [userSwitches] 為 freeze 後的不可變投影開關拷貝（有界 64 筆／key ≤128
     *   UTF-8 byte，見 [freezeCtx]；與重驗側 frozen ctx2 比對，兩者皆為我方拷貝——
     *   快照直接存 unmodifiable 拷貝參考，非二次凍結）；
     * - [grantedPermissions] 為 freeze 後的不可變已授權權限集合拷貝（有界 64 筆／
     *   每字串 ≤128 UTF-8 byte；與重驗側 frozen ctx2 比對，兩者皆為我方
     *   unmodifiableSet，equals 安全有界；快照直接存 unmodifiable 拷貝參考，
     *   非二次凍結；失敗→DENY，零 spawn）；
     * - [grantProviderId]／[grantConfigRevision] 為准入時 grant 身份快照
     *   （取自新鮮 `grantCheck()` 的 `Valid` 身份；重驗時與新鮮 `grantCheck()`
     *   的身份比對，endpoint／revision 輪轉即作廢，零 spawn）。
     */
    data class ElevatedSnapshot(
        val argv: List<String>,
        val reasonCode: String,
        val flavor: Flavor,
        val userSwitches: Map<String, Boolean>,
        val grantedPermissions: Set<String>,
        val grantProviderId: String,
        val grantConfigRevision: Long,
    )

    /** Bounded best-effort timeout for sync supplier checks (F4). */
    private const val SUPPLIER_TIMEOUT_MS: Long = 1_000L

    /** fresh policy（前驗／重驗）的 1s 配合作式逾時（掛起點觸發；對永不掛起之實作不保證上界；超限視同 verdict=null → 非 ALLOW 拒）。 */
    private const val POLICY_TIMEOUT_MS: Long = 1_000L

    /** [POLICY_TIMEOUT_MS] 的 monotonic deadline 對應值（System.nanoTime 口徑，見兩處 policy 評估的超限門）。 */
    private const val POLICY_TIMEOUT_NANOS: Long = POLICY_TIMEOUT_MS * 1_000_000L

    /** freezeCtx 上限：userSwitches 筆數（超限即 fail-closed null）。 */
    private const val MAX_FROZEN_SWITCHES: Int = 64

    /** freezeCtx 上限：grantedPermissions 筆數（超限即 fail-closed null）。 */
    private const val MAX_FROZEN_PERMISSIONS: Int = 64

    /** freezeCtx 上限：單一字串 UTF-8 byte 數（超限即 fail-closed null）。 */
    private const val MAX_FROZEN_STRING_BYTES: Int = 128

    /**
     * Off-caller-thread bounded supplier eval; timeout/failure => null (fail-closed), CE rethrown.
     * Supplier 走有界專用池（[ElevatedSupplierPool]），caller 恆有界（get ≤ ~1.2s：get 1s＋grace 200ms）；
     * 池滿／起線程失敗即時回 null；忽略 interrupt 的區塊殘留有界（全域 ≤2）。
     */
    private suspend fun <T> evalBounded(block: () -> T): T? {
        return try {
            withContext(Dispatchers.IO) {
                try {
                    boundedSupplierCall(block, SUPPLIER_TIMEOUT_MS)
                } finally {
                    // 清除池 worker（Dispatchers.IO 共享線程）上可能殘留的 interrupt flag：
                    // 殘旗會污染下一個共用 worker 任務（github runner `isInterrupted` 契約可能被誤觸）。
                    // 註：會一併清除外層 runInterruptible 的中斷信號——該次取消退化為 fail-closed
                    // Denied（零 spawn、一筆 DENY）；coroutine 取消仍於下一掛起點由 job 生效。
                    // 不改 boundedSupplierCall 本身的復位邏輯（caller-owned 線程語義保留）。
                    Thread.interrupted()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Bounded projection context fetch (F4). */
    private suspend fun fetchCtx(
        ctxSupplier: () -> ProjectionContext,
    ): ProjectionContext? = evalBounded(ctxSupplier)

    /**
     * 有界投影上下文凍結（F4/F9）：在有界專用池內拷貝 [ctx] 的不可信容器為我方
     * 不可變物件；拷貝體（迭代/取 byte 數）於池 worker 執行，caller 恆有界。
     *
     * - [ProjectionContext.userSwitches]：迭代 entries，至多 [MAX_FROZEN_SWITCHES] 筆、
     *   key ≤[MAX_FROZEN_STRING_BYTES] UTF-8 byte、value 必須 Boolean，否則整體 null；
     *   保序（LinkedHashMap）；
     * - [ProjectionContext.grantedPermissions]：迭代，至多 [MAX_FROZEN_PERMISSIONS] 筆、
     *   每字串 ≤[MAX_FROZEN_STRING_BYTES] UTF-8 byte，否則 null；保序（LinkedHashSet）；
     * - 回傳 `ctx.copy(...)` 以 [Collections.unmodifiableMap]／[Collections.unmodifiableSet]
     *   包裝：安全、無 untrusted 代碼再執行（此後所有 switchOn／迭代皆讀我方拷貝）。
     *
     * 任何失敗（迭代抛 [java.util.ConcurrentModificationException]、類型不符、超限、
     * 池滿／逾時）→ null：呼叫方 fail-closed 拒（零 spawn、一筆 DENY 審計）。
     * CE 一律重拋（不轉 null、不寫 DENY 審計）。
     */
    private suspend fun freezeCtx(ctx: ProjectionContext): ProjectionContext? = try {
        withContext(Dispatchers.IO) {
            try {
                boundedSupplierCall({ doFreeze(ctx) })
            } finally {
                // 清除池 worker（Dispatchers.IO 共享線程）上可能殘留的 interrupt flag：
                // 殘旗會污染下一個共用 worker 任務（github runner `isInterrupted` 契約可能被誤觸）。
                // 同 evalBounded：會一併清除外層 runInterruptible 中斷信號（退化為 fail-closed Denied）。
                Thread.interrupted()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** [freezeCtx] 的拷貝體（於有界池 worker 執行；pure 結構操作，不呼叫 untrusted 代碼）。 */
    private fun doFreeze(ctx: ProjectionContext): ProjectionContext? {
        return try {
            val switchMap = LinkedHashMap<String, Boolean>()
            var switchCount = 0
            @Suppress("UNCHECKED_CAST")
            val rawSwitches = ctx.userSwitches as Map<Any?, Any?>
            for ((rawKey, rawValue) in rawSwitches) {
                switchCount++
                if (switchCount > MAX_FROZEN_SWITCHES) return null
                val key = rawKey as? String ?: return null
                if (key.length > MAX_FROZEN_STRING_BYTES) return null
                if (key.toByteArray(Charsets.UTF_8).size > MAX_FROZEN_STRING_BYTES) return null
                val value = rawValue as? Boolean ?: return null
                switchMap[key] = value
            }
            val permissionSet = LinkedHashSet<String>()
            var permissionCount = 0
            @Suppress("UNCHECKED_CAST")
            val rawPermissions = ctx.grantedPermissions as Set<Any?>
            for (rawElement in rawPermissions) {
                permissionCount++
                if (permissionCount > MAX_FROZEN_PERMISSIONS) return null
                val element = rawElement as? String ?: return null
                if (element.length > MAX_FROZEN_STRING_BYTES) return null
                if (element.toByteArray(Charsets.UTF_8).size > MAX_FROZEN_STRING_BYTES) return null
                permissionSet.add(element)
            }
            ctx.copy(
                userSwitches = Collections.unmodifiableMap(switchMap),
                grantedPermissions = Collections.unmodifiableSet(permissionSet),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Safe bounded recheck of untrusted argv against snapshot (F3). */
    private fun argvMatchesSnapshot(current: List<String>, snapshotArgv: List<String>): Boolean {
        val recheck = try {
            ElevatedRequest(current, "").frozenOrNull()
        } catch (_: Throwable) {
            // frozenOrNull 內不丟 CancellationException（全 catch Throwable→null；
            // timeout/interrupt 皆已收斂），CE 於此路徑不可達；外層 `: Throwable → false`
            // 即 fail-closed，hostile list 的 CE 亦在 copyArgvBounded 內收斂為 null。
            null
        } ?: return false
        return recheck.argv == snapshotArgv
    }

    /**
     * 接線執行（suspend；門禁／supplier 具取消協作與有界 best-effort，runner 於 IO dispatch）。
     *
     * F6 cheap-gate-first：argv 相關有界凍結僅在 cheap gate 通過且需建不可變快照時執行一次；
     * 早期 deny 路徑不觸碰不可信 argv 迭代（審計以空 argv＋有界 reason 記錄）。
     * F3：重驗參數竄改以有界重凍結比對，不直接對不可信 list 呼叫未受控 equals/iterator。
     * F4：ctx/bridge/grant supplier 走有界專用池（ElevatedSupplierPool，POOL_SIZE=2、零佇列、
     *   daemon；與快照池分離），caller 恆有界（get ≤ ~1.2s；get 1s＋grace 200ms；池滿即 null）；池滿或忽略 interrupt
     *   的區塊殘留有界 2（全域上界，caller 不等殘留結束）；CE 重拋（supplier 面；argv 重凍結面見「審計留痕」取消語義分述）。projection context
     *   （userSwitches／grantedPermissions）亦經同池有界凍結（freezeCtx），此後所有投影／
     *   比對皆讀我方不可變拷貝。
     * F7：重驗通過後以單一 frozen snapshot 委派 executeWithFrozen（避免二次凍結）；
     *   argvMatchesSnapshot 於重驗入口與 grant2 後最終防線共兩次池拷貝（有界），用於不受信 list 的逐次比對——
     *   「single frozen snapshot」指執行用快照本體不二次凍結。
     * F8：快照已存在且未觸不可信 argv 的 deny，審計記錄 snapshot argv＋有界 reason 以保留關聯。
     * F5：最終阻塞 runner 執行於 Dispatchers.IO，並經 runInterruptible 轉為 worker 執行緒
     *   interrupt；runner 具 interrupt 感知即停止；runner 不感知時殘留至其 timeoutMs，
     *   與撤權/離開頁面無保證中止——KDoc 如實記載。
     * F5b：`timeoutMs <= 0`（零／負預算）即 fail-closed 拒絕——回
     *   `TimedOut`（empty partial、`cleanupIncomplete=false`，對齊 github runner 入口
     *   `safeTimeoutMs<=0 → preSpawnExpired` 語義），零 spawn、恰一筆 `TIMEOUT` 審計，
     *   不觸碰 argv 迭代／快照／投影／policy／gate／runner；僅 `timeoutMs` 為正數且超過上界
     *   才夾至 [ShellPolicy.MAX_ELEVATED_TIMEOUT_MS]（fail-safe clamp，不拒絕——拒絕超過
     *   上界的誤用須另立需求）。runner 永遠收到有界正預算。
     *
     * 殘餘 TOCTOU：重驗通過→spawn 之間仍有檢查→使用殘餘窗（revalidate 與 spawn 非原子）；
     * 由 github runner `awaitPreSpawn`＋`ELEVATED_RUNNERS_READY` 終門把關；本層不宣稱消除。
     *
     * 審計留痕：除 CancellationException 與 Error 外，所有路徑恰一筆審計（門禁未過亦寫一筆
     * DENY 審計；任何拒絕一律零 spawn）。取消語義如實分述：
     * - supplier／policy／freeze／projection 面：[CancellationException] 一律重拋
     *   （不轉 `Denied`、不寫 DENY 審計；測試 pin：`cancellationPropagatesWithoutDenyAudit`）；
     * - argv 重凍結面：hostile list 的 CE 於 `copyArgvBounded` 內即收斂為 null → Denied
     *   （fail-closed、恰一筆 DENY），見 `reverifyArgvRecheckCancellationDeniedZeroSpawnPin`；
     *   `frozenOrNull` 的 blocking get 不觀察呼叫方取消，取消最遲於本函式下一掛起點生效；
     * - 上列「恰一筆審計」僅保證非 CancellationException、非 Error 路徑；Error 路徑之受控
     *   掛起點（projection／policy／gate）已收斂為 Denied＋一筆 DENY，其餘 Error 不承諾審計。
     */
    suspend fun execute(
        argv: List<String>,
        reasonCode: String,
        confirmed: Boolean,
        ctxSupplier: () -> ProjectionContext,
        policyStore: PolicyStore,
        runner: ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        grantCheck: () -> ElevatedGrant,
        bridgeGranted: () -> Boolean,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult {
        coroutineContext.ensureActive()
        // fail-closed（零預算＝零 spawn，對齊 github runner 入口 `safeTimeoutMs<=0 →
        // preSpawnExpired`）：timeoutMs<=0 一律拒絕——TimedOut（empty partial、未 spawn、
        // cleanupIncomplete=false），零 spawn、恰一筆 TIMEOUT 審計；不觸碰 argv 迭代／
        // 快照／投影／policy／gate／runner。舊 coerceIn(1L, …) 會把 0/負值靜默提升為
        // 1ms 正預算進入 spawn 路徑，違反零預算契約，已移除。
        if (timeoutMs <= 0L) {
            val timedOut = ShellResult.TimedOut(
                partialStdout = "",
                partialStderr = "",
                timeoutMs = timeoutMs,
                truncated = false,
                cleanupIncomplete = false,
            )
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(timedOut))
            return timedOut
        }
        // fail-safe clamp（僅正數超上界，不拒絕——拒絕超過上界的誤用須另立需求）：
        // runner 永遠收到有界正預算。
        val effectiveTimeoutMs = timeoutMs.coerceAtMost(ShellPolicy.MAX_ELEVATED_TIMEOUT_MS)
        // --- Cheap gates first (F6): no argv iteration/snapshot before these pass ---
        val tool = ToolRegistry.find(TOOL_NAME)
            ?: run {
                audit.record(emptyList(), "", PrivilegeAuditLog.verdictName(
                    ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "unknown tool $TOOL_NAME"),
                ))
                return ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "unknown tool $TOOL_NAME")
            }
        val reasonTooLong = PrivilegeAuditLog.isReasonCodeTooLong(reasonCode)
        val reasonBlank = !reasonTooLong && reasonCode.isBlank()
        if (reasonTooLong) {
            val denied = ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "reasonCode too long (fail-closed, no spawn)",
            )
            // too-long reason -> empty reason in audit (no unbounded hash)
            audit.record(emptyList(), "", PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (reasonBlank) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege gate blocked: reasonCode blank")
            audit.record(emptyList(), "", PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // confirmed=false: deny early without argv snapshot (F6), but classify using real bounded
        // projection (projection-unavailable takes precedence over NeedConfirm in PrivilegeGate).
        if (!confirmed) {
            val ctx0 = fetchCtx(ctxSupplier) ?: run {
                val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection unavailable (fail-closed, no spawn)")
                audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
                return denied
            }
            // F4/F9：投影前先有界凍結 ctx0（tool.project 會讀 userSwitches）。
            val frozenCtx0 = freezeCtx(ctx0) ?: run {
                val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection context not freezable (fail-closed, no spawn)")
                audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
                return denied
            }
            val projection0 = try {
                tool.project(frozenCtx0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection unavailable (fail-closed, no spawn)")
                audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
                return denied
            }
            // check 本身不得令 Throwable 逃逸：CE 重拋；其餘 Throwable（含 require(toolName) IAE）
            // fail-closed → Denied＋一筆 DENY（fail-closed，no spawn）。
            val gate = try {
                PrivilegeGate.check(tool, false, projection0, mapOf("reasonCode" to reasonCode))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege gate evaluation failed (fail-closed, no spawn)")
                audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
                return denied
            }
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege gate blocked: $gate")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }

        // Precheck projection/context (F4 bounded) without argv snapshot
        val ctx1: ProjectionContext = fetchCtx(ctxSupplier) ?: run {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection unavailable (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // F4/F9：投影前先有界凍結 ctx1（tool.project 會讀 userSwitches，且快照存此拷貝）。
        val frozenCtx1 = freezeCtx(ctx1) ?: run {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection context not freezable (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val projection1 = try {
            tool.project(frozenCtx1)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection unavailable (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (projection1.level == CapabilityLevel.UNAVAILABLE) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege projection blocked: ${projection1.reason}")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // fresh policy（前驗）：僅 ALLOW 放行；ASK／DENY／異常／逾時一律拒（ASK 不變 ALLOW；CE 重拋）。
        // withTimeoutOrNull 是配合作式（掛起點觸發）：對永不掛起之實作不保證上界（產品以 DataStore 系為準）。
        // withTimeoutOrNull 回 null 且 block 未返回＝逾時 → verdict=TIMEOUT；block 返回 null（Java-null verdict）或任何例外／Error → verdict=ERROR。
        // 永不掛起的實作仍可能在預算之後才回傳 ALLOW——故以 System.nanoTime() 量測的
        // monotonic deadline 才是權威超限門：
        // block 即使已 RETURN 且 verdict==ALLOW，elapsed 嚴格大於 POLICY_TIMEOUT_MS 一律
        // 視為 TIMEOUT（阻塞中的工作可能殘留，但絕不可授權；等於預算仍視為在限內）。
        // CE 語義不變（重拋、不寫審計）；Throwable→null 語義不變。
        var verdict1Timeout = false
        val policyEvalStartNanos = System.nanoTime()
        val verdict1 = try {
            var blockReturned = false
            withTimeoutOrNull(POLICY_TIMEOUT_MS) {
                policyStore.evaluateFresh(POLICY_ACTION, tool.name).verdict.also { blockReturned = true }
            } ?: run {
                verdict1Timeout = !blockReturned
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val policyEvalElapsedNanos = System.nanoTime() - policyEvalStartNanos
        // overdue-ALLOW：預算超限即使 block 已返回 ALLOW 亦視同逾時（monotonic，僅嚴格大於才算）。
        if (verdict1 == Verdict.ALLOW && policyEvalElapsedNanos > POLICY_TIMEOUT_NANOS) {
            verdict1Timeout = true
        }
        if (verdict1 != Verdict.ALLOW || verdict1Timeout) {
            val verdict1Label = when {
                verdict1Timeout -> "TIMEOUT"
                verdict1 != null -> verdict1.name
                else -> "ERROR"
            }
            val denied = ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "policy denied (verdict=$verdict1Label, fail-closed, no spawn)",
            )
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // ASK 確認（前驗）：四參 gate；NeedConfirm／Denied 一律拒（confirmed 已 cheap-gated，但保留完整 gate）。
        // check 本身不得令 Throwable 逃逸：CE 重拋；其餘 Throwable（含 require(toolName) IAE）
        // fail-closed → Denied＋一筆 DENY。
        val gate1 = try {
            PrivilegeGate.check(tool, confirmed, projection1, mapOf("reasonCode" to reasonCode))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege gate evaluation failed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (gate1 !is GateResult.Allowed) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege gate blocked: $gate1")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // bridge＋grant 前驗（每次新鮮求值；有界 best-effort；抛/逾時即 fail-closed 拒，CE 重拋）。
        val bridge1 = evalBounded(bridgeGranted)
        if (bridge1 == null) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge check failed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (!bridge1) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge off (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val grant1 = evalBounded(grantCheck)
        if (grant1 == null) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant check failed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant1 is ElevatedGrant.Invalid) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant invalid (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant1 is ElevatedGrant.Valid && grant1.providerId.isBlank()) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant identity malformed (fail-closed, no spawn)")
            audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val validGrant1 = grant1 as ElevatedGrant.Valid

        // Prechecks passed: perform single bounded freeze to build immutable snapshot (F6,F7).
        val frozenEntry = ElevatedRequest(argv, reasonCode).frozenOrNull()
            ?: run {
                val denied = ShellResult.Denied(ShellDeny.BLACKLISTED, "elevated snapshot failed (fail-closed, no spawn)")
                audit.record(emptyList(), safeReason(reasonCode), PrivilegeAuditLog.verdictName(denied))
                return denied
            }
        val argvCopy: List<String> = frozenEntry.argv
        val reasonCopy: String = frozenEntry.reasonCode
        val snapshot = ElevatedSnapshot(
            argv = argvCopy,
            reasonCode = reasonCopy,
            flavor = frozenCtx1.flavor,
            userSwitches = frozenCtx1.userSwitches,
            grantedPermissions = frozenCtx1.grantedPermissions,
            grantProviderId = validGrant1.providerId,
            grantConfigRevision = validGrant1.configRevision,
        )
        coroutineContext.ensureActive()
        // 確認後 spawn 前重驗：重新投影＋fresh policy＋重求 bridge／grant＋
        // 快照一致性；snapshot 已存在且不觸不可信 argv 的 deny 用 snapshot argv＋有界 reason 記錄（F8）。
        val ctx2: ProjectionContext = fetchCtx(ctxSupplier) ?: run {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "reverify projection unavailable (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // F4/F9：重驗側亦有界凍結；此後 flavor/userSwitches/grantedPermissions 比對與 tool.project 皆讀我方拷貝。
        val frozenCtx2 = freezeCtx(ctx2) ?: run {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "projection context not freezable (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // F3: bounded/safe recheck of untrusted argv (no uncontrolled equals/iterator on adversarial list)
        // 註：argvMatchesSnapshot 為非掛起函式且 frozenOrNull 全收斂 Throwable→null，此處 CE catch 為
        // 防禦縱深（現況下不可達——frozenOrNull 全收斂 Throwable→null）；hostile list 的 CE 已於
        // copyArgvBounded 內收斂為 null → argvMatchesSnapshot 回 false → Denied，見 reverifyArgvRecheckCancellationDeniedZeroSpawnPin。
        val argvOk = try {
            argvMatchesSnapshot(argv, snapshot.argv)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        if (!argvOk) {
            val denied = ShellResult.Denied(ShellDeny.BLACKLISTED, "elevated args changed after confirm (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (frozenCtx2.flavor != snapshot.flavor ||
            frozenCtx2.userSwitches != snapshot.userSwitches ||
            frozenCtx2.grantedPermissions != snapshot.grantedPermissions
        ) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "elevated context changed after confirm (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val projection2 = try {
            tool.project(frozenCtx2)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "reverify projection unavailable (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (projection2.level == CapabilityLevel.UNAVAILABLE) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege projection blocked on reverify: ${projection2.reason}")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // fresh policy（重驗）：僅 ALLOW 放行；異常／逾時一律拒（CE 重拋）。
        // withTimeoutOrNull 是配合作式（掛起點觸發）：對永不掛起之實作不保證上界（產品以 DataStore 系為準）。
        // withTimeoutOrNull 回 null 且 block 未返回＝逾時 → verdict=TIMEOUT；block 返回 null（Java-null verdict）或任何例外／Error → verdict=ERROR。
        // monotonic deadline 同前驗：block 即使已返回 ALLOW，elapsed 嚴格大於
        // POLICY_TIMEOUT_MS 一律視為 TIMEOUT（阻塞中的工作可能殘留，但絕不可授權）；
        // CE 語義不變（重拋、不寫審計）；Throwable→null 語義不變。
        var verdict2Timeout = false
        val policyEval2StartNanos = System.nanoTime()
        val verdict2 = try {
            var blockReturned = false
            withTimeoutOrNull(POLICY_TIMEOUT_MS) {
                policyStore.evaluateFresh(POLICY_ACTION, tool.name).verdict.also { blockReturned = true }
            } ?: run {
                verdict2Timeout = !blockReturned
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val policyEval2ElapsedNanos = System.nanoTime() - policyEval2StartNanos
        // overdue-ALLOW：預算超限即使 block 已返回 ALLOW 亦視同逾時（monotonic，僅嚴格大於才算）。
        if (verdict2 == Verdict.ALLOW && policyEval2ElapsedNanos > POLICY_TIMEOUT_NANOS) {
            verdict2Timeout = true
        }
        if (verdict2 != Verdict.ALLOW || verdict2Timeout) {
            val verdict2Label = when {
                verdict2Timeout -> "TIMEOUT"
                verdict2 != null -> verdict2.name
                else -> "ERROR"
            }
            val denied = ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "policy denied on reverify (verdict=$verdict2Label, fail-closed, no spawn)",
            )
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val bridge2 = evalBounded(bridgeGranted)
        if (bridge2 == null) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge recheck failed (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (!bridge2) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "privilege bridge off on reverify (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val grant2 = evalBounded(grantCheck)
        if (grant2 == null) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant recheck failed (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant2 is ElevatedGrant.Invalid) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant invalid (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        if (grant2 is ElevatedGrant.Valid && grant2.providerId.isBlank()) {
            val denied = ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "grant identity malformed on reverify (fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        val validGrant2 = grant2 as ElevatedGrant.Valid
        if (validGrant2.providerId != snapshot.grantProviderId ||
            validGrant2.configRevision != snapshot.grantConfigRevision
        ) {
            val denied = ShellResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "elevated grant identity changed after confirm (fail-closed, no spawn)",
            )
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // 最終 argv 防線（grant2 最終有效性確認之後、dispatch 之前）：
        // grantCheck／bridgeGranted supplier 可能於 grant2 評估期間竄改 caller 原始 argv——
        // 再做一次受監督比對，封閉「確認→改→spawn」語義缺口。與重驗入口同形：CE 重拋；
        // 其餘 Throwable→false（fail-closed，零 spawn）。runner 只吃不可變快照
        // [ElevatedSnapshot.argv]，此比對針對呼叫方原始 list 的竄改面（見檔頂 KDoc 兩重驗點）。
        val argvOkFinal = try {
            argvMatchesSnapshot(argv, snapshot.argv)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        if (!argvOkFinal) {
            val denied = ShellResult.Denied(ShellDeny.BLACKLISTED, "elevated args changed after confirm (final check, fail-closed, no spawn)")
            audit.record(snapshot.argv, safeReason(snapshot.reasonCode), PrivilegeAuditLog.verdictName(denied))
            return denied
        }
        // 重驗通過：以已凍結快照委派（F7 單次凍結，無二次 frozenOrNull）；
        // runner 於 IO 執行，並經 runInterruptible 轉為 worker interrupt（F5）。
        coroutineContext.ensureActive()
        return withContext(Dispatchers.IO) {
            runInterruptible {
                ElevatedDispatch.executeWithFrozen(
                    tool = tool,
                    frozen = ElevatedRequest(snapshot.argv, snapshot.reasonCode),
                    runner = runner,
                    audit = audit,
                    timeoutMs = effectiveTimeoutMs,
                )
            }
        }
    }

    /** 有界 reason（超限即空串標記，不哈希無界輸入；與 Dispatch 同門）。 */
    private fun safeReason(reasonCode: String): String =
        if (PrivilegeAuditLog.isReasonCodeTooLong(reasonCode)) "" else reasonCode
}
