package dev.librepocket.privilege.github

import androidx.annotation.VisibleForTesting
import java.lang.reflect.InaccessibleObjectException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 提權子進程清理（B2，Issue #136 B2，僅 github 風味；P1-B 誠實化措辭；R2-A 可驗證化；
 * S2-A 版本矩陣＋best-effort 宣告，不改行為）。
 *
 * 背景：舊 [ElevatedProcessIo.drain] 只殺 `su` 本體（`destroyForcibly`），
 * 其子孫（`su -c` 拉起的真正命令）殘留為 root 孤兒（Issue #136 §5）。
 * 本物件盡力整組 best-effort（JVM/新 Android 才真整組）：先殺子孫再殺本體。
 * 串流關閉不屬本物件職責：gobbler 的 blocking I/O 由 [ElevatedProcessIo.drain]
 * 經已取引用關閉（kill 路徑不再重調 `process.inputStream/errorStream` getter，
 * 根除 kill 路徑 getter 阻塞；見 drain）；本物件只管 destroy/destroyForcibly，
 * 全 runCatching（Y1，owner 新 P1 兩入口）。
 *
 * S2-A 版本矩陣（owner P1-2 lifecycle＋P2-3 cancel，不改行為）：
 * - JVM Host JDK（單測）：JDK 9+ `ProcessHandle` 完整 → 真整組（先殺子孫再殺本體；
 *   `ElevatedProcessKillerTest` 真 `sh -c "sleep 30 & wait"` 子進程已驗，父死＋子孫無存活）；
 * - 新 Android（具 `descendants` API）：反射取到 handle＋`descendants` → 同上真整組；
 * - 舊 Android（缺 API 或 AOSP 樁抛錯）：`NoSuchMethodException`／
 *   `UnsupportedOperationException` 或 `ProcessHandle` 類缺席（`NoClassDefFoundError`）
 *   即收斂，實際退化為單殺本體 best-effort（僅 `destroy/destroyForcibly`；
 *   串流由 drain 經已取引用關閉，killer 不觸 getter），非真整組；
 * - AOSP libcore `Process.toHandle` 預設實作抛 `UnsupportedOperationException`
 *   （源碼見 https://cs.android.com/android/platform/superproject/main/+/main:libcore/ojluni/src/main/java/java/lang/Process.java
 *   ；android.jar 內即 `".toHandle() not supported"` 樁，非公開 API，見 `api-versions.xml`）；
 * - [ShizukuRemoteProcess] 未覆寫 `toHandle`，沿用 JDK 預設抛 `UnsupportedOperationException`，
 *   故遠端樹同為單殺（僅本體；子孫列舉一步即收斂）。
 *
 * S2-A best-effort 宣告（PocketGuard WARN 回應）：假設僅 JDK 9+／具 `ProcessHandle`＋
 * `descendants` 的新平台才真整組，舊平台一律 fallback 單殺 `destroyForcibly`
 * （即上表；版本漂移一律收斂，不崩）。best-effort 單殺，裝置缺口未解；
 * B2 runner 清理驗收未過；activation blocker 見 [PrivilegeActivation]
 * （[PrivilegeActivation.ELEVATED_RUNNERS_READY] 仍 `false`，#136-B1 未完，產品不得接線 runner；
 * 子孫清理裝置 E2E 歸 #136-B3）。
 *
 * 誠實化（P1-B，行為不變；Y1 去串流關閉，契約見下）：
 * - Android 上 `Process.toHandle`/`descendants` 若不可用（舊版本缺席，或
 *   SDK 樁直接抛 `UnsupportedOperationException`——android.jar 內即
 *   `".toHandle() not supported"` 樁，非公開 API，見 `api-versions.xml`），
 *   反射即收斂，實際退化為單殺本體 best-effort，非真整組（串流由 drain 關）；
 * - [ShizukuRemoteProcess] 未覆寫 `toHandle`，遠端樹同為單殺（僅本體）；
 *   真整組僅在 JVM 單測 / 具 descendants API 的新 Android 才成立。
 *
 * R2-A（owner P1-1）：子孫列舉改「宣告類查表」——舊碼經
 * `process.javaClass.getMethod("toHandle")` 在 OpenJDK 21+ 抛
 * `IllegalAccessException`（`ProcessImpl` 為模組內 package-private 類，
 * 以實作類查表取到的 `Method` 宣告類不可見，`invoke` 即拒；`setAccessible`
 * 又觸 `InaccessibleObjectException`），最終只殺父進程。現改
 * `Process::class.java.getMethod("toHandle")`（宣告類查表，`invoke` 時傳
 * process 實例）＋ `ProcessHandle` 宣告類查
 * `descendants/destroy/destroyForcibly`；`InaccessibleObjectException`
 * 明確收斂（另見內聯註）。JVM 真子進程單測已驗（父死＋子孫無存活，見
 * `ElevatedProcessKillerTest`）；裝置 SELinux/Root 語義差異 E2E 為 #136-B3。
 *
 * - 子孫列舉經反射（`Process.toHandle/descendants`，JDK 9+；API 缺席時
 *   runCatching 收斂為僅殺本體，不崩）；
 * - Y1 契約（owner 新 P1 兩入口）：本物件只管 destroy（子孫＋本體，全
 *   runCatching），絕不觸 `process.inputStream/errorStream/outputStream`
 *   getter（含關閉）。getter 可阻塞（Shizuku 同步 Binder IPC），kill 路徑重調
 *   即呼叫執行緒卡死；串流關閉是 [ElevatedProcessIo.drain] 的職責（經已取引用，
 *   見其 kill 路徑），呼叫點僅 drain 預設值＋單測；
 * - 任何步驟異常一律收斂（fail-closed 方向是「已盡力殺」，呼叫方仍回
 *   TimedOut，不把清理異常升級為崩潰）；
 * - 單測以 `killer: (Process) -> Unit` 注入假實現斷言「超時/cancel 必經清理」，
 *   不依賴真實子孫（真樹驗證另見 `ElevatedProcessKillerTest`）。
 *
 * Z1 監督清理（owner 新 P1，僅 KDoc，行為不變）：本物件自身仍同步阻塞
 * （`destroy/destroyForcibly` 在 Shizuku 遠端可為同步 Binder IPC，stall 即呼叫
 * 執行緒卡死）。有界性由 [ElevatedProcessIo.drain] 經
 * `runCleanupBounded(min(max(remaining,500),3000))` 承擔（daemon worker＋
 * interrupt＋500ms grace；永不跳過 killer 嘗試），未完成經
 * [ElevatedProcessIo.Drained.cleanupIncomplete] 帶出（runner verdict 映射不變）。
 *
 * Stage 2A（P1-A7，PID-reuse 守門，security-auditor；Stage 4 擴及本體）：
 * 子孫（descendants）與**本體** `destroy/destroyForcibly` 前一律重驗——Stage 4
 * 起兩條路徑共用同一反射 attestation seam（[HandleAttest]，語義逐項一致，
 * R2-A 宣告類查表紀律不變）：於流出當下之後、信號之前取 `info().startInstant()`
 * 快照（僅存活 handle 取快照；已死 handle 直接跳過不讀 info()），每次信號前重讀
 * `startInstant()` 與 `isAlive()`：
 * - 兩次 startInstant 不符 ⇒ PID 已被回收重用 ⇒ **跳過信號**
 *   （不得對一顆可能已非原 process 的 PID 發信號；子孫清理 best-effort 語義不變）；
 * - `info()` 抛／`startInstant` 缺席／`isAlive()==false`／不可驗證 ⇒ 同跳過；
 *   其中「不可驗證／PID 回收」（死亡後的常規跳過除外）計入
 *   [pidReuseSkippedCount] 診斷計數（不抛、不崩，fail-closed 方向是不誤殺）；
 * - **本體（Stage 4）**：[killTree] 不再無條件 `process.destroy()/destroyForcibly()`。
 *   平台可 attest（`toHandle` 可 invoke、`ProcessHandle` 類在、`isAlive`／`info`
 *   Method 在、且本次 `startInstant` 探測可讀）時，兩個信號（destroy 先、
 *   destroyForcibly 後）各自獨立重驗（與子孫逐信號重驗一致），信號經 **handle**
 *   的 `destroy/destroyForcibly` Method 發出（handle 即 attested 身份），不經
 *   `process.destroy()`；身份不可驗／PID 回收 ⇒ 跳過＋計 [pidReuseSkippedCount]；
 * - 平台**完全無 attestation toolkit**（`toHandle` 缺席／`ProcessHandle` 類缺席／
 *   `isAlive` Method 缺席／`info` Method 缺席／本次 `startInstant` 探測不可讀——
 *   即整組工具缺席，而非單次瞬時讀失敗）時，本體退化為今日之**無條件**
 *   `process.destroy()/destroyForcibly()`（保 kill 契約不破壞），且每個未經
 *   attest 的信號計入 [bodyDestroyUnattestedCount]（once per SIGNAL，一次
 *   killTree 最多 2）。fade to 無條件 destroy 僅發生於此（toolkit 全缺；
 *   探測失敗無法區分結構性缺席與瞬時讀失敗，一律保守歸入 unattested，close
 *   toward destruction）——平台**有能力** attest 而身份不可驗（瞬時讀失敗／
 *   PID 回收）者不退化，跳過＋[pidReuseSkippedCount]（與子孫同款 fail-closed）。
 * 樹殺語義**維持 best-effort**：本守門只縮小「流出→信號」窗口，
 * 不保證 handle 快照當下 PID 仍真值原子配對（TOCTOU 無法根除，裝置 SELinux／
 * Root 差異 E2E 仍歸 #136-B3）。本 KDoc 只描述 JVM／Host 測試環境已驗的
 * attested-NOW 語義（Host 真子進程單測見 `ElevatedProcessKillerTest`）；JVM 與
 * 裝置端的差異（AOSP 樁、Shizuku 遠端、實機 startInstant 可讀性）為 #136-B3
 * 領域，本 KDoc 不宣稱裝置 E2E 已驗證。
 * 平台 `info()`／`startInstant()` 完全不可讀時，巢狀樹殺退化為僅對本體發信號；
 * 本體信號為 unattested 無條件 destroy 並計入 [bodyDestroyUnattestedCount]
 * ——產品必須於啟用前以真機查驗 [pidReuseSkippedCount] 與
 * [bodyDestroyUnattestedCount]（#136-B3 device-acceptance gate）。
 *
 * B2 P1-3 誠實化（owner 新 P1-3，行為見 drain）：本物件全 runCatching（destroy／
 * destroyForcibly 抛即吞，遠端 destroy 失敗亦吞），`killTree` 正常返回≠進程真死
 * （no-op／抛吞／streams EOF 皆可返回）。真死由 [ElevatedProcessIo.drain] 經
 * post-kill 驗證（`waitFor` 回傳／liveness，binder 不可達視 unknown）確認，
 * 失敗或不可驗證即 `cleanupIncomplete=true` 並進資源／breaker 終審，不因 killer
 * 未抛而宣稱 cleaned。子孫全滅仍為真機 blocker（JVM 真樹已驗，裝置 SELinux／
 * Root 語義差異 E2E 歸 #136-B3，見上）。
 */
object ElevatedProcessKiller {

    /**
     * Stage 2A（P1-A7，PID-reuse 守門；Stage 4 起含本體信號；診斷用，產品不讀取）：
     * 子孫與本體信號被跳過之次數——僅計「不可驗證或已回收（unverifiable or recycled）」，
     * 即我們無法 attest 該 handle 之身份（we could not attest identity）者：
     * `isAlive` API 缺席（method null）、`isAlive` 讀抛／非常規回傳、
     * `info()`／`startInstant()` 不可讀（handle 快照或信號前重讀）、
     * PID 已回收（startInstant 不符）。只有 `isAlive()==false` 的常規確認死亡
     * （dead-but-alive-previously handles）不計。平台完全無 attestation toolkit
     * 時，本體信號走 unattested 無條件路徑，計入 [bodyDestroyUnattestedCount]，
     * 不計此計數。
     *
     * 刻意非接線（deliberate NON-wiring）進 verdict／breaker／admission：
     * 平台若對任何一顆子孫完全無法 attest（無 isAlive／info／startInstant），
     * tree kill 會對每個 handle 都 fail-close——等於把整個提權子孫清理
     * 靜默關掉。那是產品決策（product decision），不是 killer 內部可定的
     * 行為，必須由 #136-B3 以裝置端證據決定——pidReuseSkipped 必須列入
     * #136-B3 裝置驗收清單（device-acceptance checklist）：啟用提權 runner
     * 前，須先以真機量測本計數確認平台 attest 能力。
     * 上界：per handle per killTree 最多 2 次（destroy＋destroyForcibly 各重驗一次；
     * 本體兩個信號亦同 2）；完全不可 attest 平台本體信號≈0（unattested 路徑計
     * [bodyDestroyUnattestedCount]）；must feed #136-B3 device-acceptance.
     */
    private val pidReuseSkipped = AtomicInteger(0)

    /** PID-reuse 守門跳過的信號數快照（僅測試／診斷用）。 */
    internal fun pidReuseSkippedCount(): Int = pidReuseSkipped.get()

    /**
     * Stage 4（body unattested destroy 診斷計數；非接線，產品不讀取）：
     * 平台完全無 attestation toolkit（toHandle 缺席／`ProcessHandle` 類缺席／
     * `isAlive`／`info` Method 缺席／本次 `startInstant` 探測不可讀）時，
     * [attestBodyDestroy] 退化為無條件 `process.destroy()/destroyForcibly()`
     * （保 kill 契約不破壞），**每個未經 attest 的信號計 1**（once per SIGNAL；
     * 一次 killTree 最多 2）。平台可 attest 而身份不可驗者不進此計數
     * （計 [pidReuseSkippedCount]）；`isAlive()==false` 的常規確認死亡不計。
     * 必須列入 #136-B3 裝置驗收清單（device-acceptance checklist）：啟用提權
     * runner 前，須先以真機量測本計數確認平台是否走 unattested 退化
     * （must feed #136-B3 device-acceptance）。
     */
    private val bodyDestroyUnattested = AtomicInteger(0)

    /** Stage 4 未經 attest 的本體信號數快照（僅測試／診斷用；#136-B3 device-acceptance）。 */
    @VisibleForTesting
    internal fun bodyDestroyUnattestedCount(): Int = bodyDestroyUnattested.get()

    /**
     * Stage 2A（P1-A7）信號判定（純函數 seam，供 JVM 單測直斷；
     * [killDescendantsReflective] 子孫與 [attestBodyDestroy] 本體經 runtime
     * reflection 餵入實際值，兩條路徑共用同一 seam 語義）：
     * 僅當 `isAlive()==true`、handle 快照 startInstant 可取得且當前重讀一致時
     * 允許信號；任一不成立即跳過。`info()` 抛／`startInstant` 缺席以
     * `currentStart=null` 表達；handle 快照不可驗證以 `expectedStart=null` 表達
     * （兩者皆跳過）。PID 已回收（startInstant 不同）即 mismatch→跳過。
     *
     * Stage 2A（T7）：M2/M3 重構僅改呼叫點（不可驗證計數拆分／快照僅 alive
     * 才取／死亡短路），本判定式一字不動（`alive && expectedStart != null &&
     * expectedStart == currentStart`）；真值表（見 ElevatedProcessKillerTest
     * `pidReuseSignalAllowedTruthTable`）同步維持。
     */
    internal fun pidReuseSignalAllowed(
        alive: Boolean,
        expectedStart: Any?,
        currentStart: Any?,
    ): Boolean = alive && expectedStart != null && expectedStart == currentStart

    fun killTree(process: Process) {
        runCatching { killDescendantsReflective(process) }
        // Y1：只管 destroy，不關流（串流由 drain 經已取引用關；此處重調 getter
        // 可阻塞呼叫執行緒，見上 KDoc 契約）。
        // Stage 4（本體 best-effort PID-reuse attestation）：本體兩個信號與子孫
        // 逐信號重驗一致（destroy 先、destroyForcibly 後，各獨立重驗）。
        runCatching { attestBodyDestroy(process) }
    }

    /**
     * Stage 4：本體 destroy 路徑（Y1 契約見上：只管 destroy，不關流）。
     * 取 [HandleAttest]（與子孫路徑同一 seam）；兩分支：
     *
     * - **平台完全無 attestation toolkit**（toHandle 缺席／`ProcessHandle` 類缺席／
     *   `isAlive`／`info` Method 缺席／本次 `startInstant` 探測不可讀——整組工具
     *   缺席，而非單次瞬時讀失敗）：保 kill 契約，降級為今日之**無條件**
     *   `process.destroy()/destroyForcibly()`，且每個未經 attest 的信號計一次
     *   [bodyDestroyUnattested]（once per SIGNAL；一次 killTree 最多 2）。
     *   探測失敗無法區分結構性缺席與瞬時讀失敗；一律保守歸入 unattested
     *   （fail toward destruction，kill 契約優先）。平台**有能力** attest 者不走
     *   此分支——身份不可驗／瞬時讀失敗 ⇒ 跳過＋[pidReuseSkipped]
     *   （與子孫同款 fail-closed，不誤殺）。
     * - **平台可 attest**：兩個信號各自獨立重驗（destroy 先、destroyForcibly 後，
     *   每次信號前 `isAlive()`＋`startInstant()` 重讀，與子孫逐信號重驗一致）；
     *   attested ⇒ 信號經 **handle** 的 `destroy/destroyForcibly` Method 發出
     *   （handle 即 attested 身份），不經 `process.destroy()`；mismatch／
     *   不可驗 ⇒ 跳過＋[pidReuseSkipped]（[HandleAttest.signalAllowed] 計數）。
     *   本體 handle 未曾存活（`isAlive()==false`）⇒ 兩個信號皆跳過（已死，無
     *   PID 重用窗口；不計診斷）。fail-closed 方向恆成立：本體信號要嘛至多兩發
     *   attested，要嘛若干次跳過——無第三結果。
     */
    private fun attestBodyDestroy(process: Process) {
        val attest = reflectHandleAttest(process)
        val h = attest.handle
        // 整組 toolkit 探測（僅一次，本體共用；子孫路徑不經本函數）。
        if (h == null ||
            !attest.toolkitPresent ||
            attest.startInstantOf(h) == null
        ) {
            unattestedBodyDestroy(process)
            return
        }
        signalBody(attest, h, attest.destroy, process, forcible = false)
        signalBody(attest, h, attest.destroyForcibly, process, forcible = true)
    }

    /**
     * 平台完全無 attestation toolkit：今日之無條件 `process.destroy()/destroyForcibly()`
     * （kill 契約不破壞），每個未經 attest 的信號計一次 [bodyDestroyUnattested]
     * （once per SIGNAL；一次 killTree 最多 2）。
     */
    private fun unattestedBodyDestroy(process: Process) {
        runCatching { process.destroy() }
        bodyDestroyUnattested.incrementAndGet()
        runCatching { process.destroyForcibly() }
        bodyDestroyUnattested.incrementAndGet()
    }

    /** Stage 4 單一本體信號（destroy 或 destroyForcibly）：獨立重驗→attested 即發。 */
    private fun signalBody(
        attest: HandleAttest,
        h: Any,
        signalMethod: Method?,
        process: Process,
        forcible: Boolean,
    ) {
        if (signalMethod == null) {
            // handle 缺該信號 Method（ProcessHandle 介面宣告 destroy/destroyForcibly，
            // 實務上不可達；防禦性退化：保 kill 契約＋計 unattested）。
            if (forcible) runCatching { process.destroyForcibly() }
            else runCatching { process.destroy() }
            bodyDestroyUnattested.incrementAndGet()
            return
        }
        val alive = attest.isAliveOf(h)
        if (alive == false) return // 已死：無信號可發（常規態，不計）
        val expectedStart: Any? = if (alive == true) attest.startInstantOf(h) else null
        if (attest.signalAllowed(h, expectedStart)) {
            runCatching { signalMethod.invoke(h) }
        }
    }

    private fun killDescendantsReflective(process: Process) {
        // R2-A：宣告類查表，非實作類查表。實作類多為模組內 package-private
        //（如 JDK 的 ProcessImpl/ProcessHandleImpl，JDK 9+ 強封裝）：以其實
        // 例 `javaClass` 查表取到的 Method 宣告類不可見，invoke 即
        // IllegalAccessException（setAccessible 又觸 InaccessibleObjectException）。
        // 以宣告類（Process/ProcessHandle，public 且 exported）查表則直通。
        val attest = reflectHandleAttest(process)
        // toHandle 缺席／抛／回傳 null（舊 Android 無此 API）：退化單殺
        // （fail-closed；串流由 drain 關）。
        val handle: Any = attest.handle ?: return
        // ProcessHandle 類本身缺席（舊 Android）在 reflectHandleAttest 內收斂
        // （NoClassDefFoundError），不外抛（外層 killTree 另有 runCatching 縱深）。
        val handleClass: Class<*> = attest.handleClass ?: return
        val descendants = try {
            handleClass.getMethod("descendants").invoke(handle) as? java.util.stream.Stream<*>
                ?: return
        } catch (_: NoSuchMethodException) {
            return
        } catch (_: InaccessibleObjectException) {
            return
        } catch (_: Exception) {
            return
        }
        // destroy/destroyForcibly 同以宣告類查表（各取一次，迴圈內直調）；
        // 兩者皆缺即無可殺，直接收斂。
        val destroy = attest.destroy
        val destroyForcibly = attest.destroyForcibly
        if (destroy == null && destroyForcibly == null) return
        // Stage 2A（P1-A7）PID-reuse 守門：handle 流出後、信號前一律重驗。
        // - 每 handle 於其流出（forEach 取到該 handle）當下之後、信號之前，僅對
        //   存活 handle 取 `info().startInstant()` 快照（同一 process 判定基準；
        //   已死 handle 直接跳過不讀 info()，Stage 2A M3）；不可讀（info 抛／
        //   startInstant 缺席）即該 handle 全程不信號；
        // - 每次 destroy／destroyForcibly 前重讀 `isAlive()`＋`info().startInstant()`：
        //   PID 已回收（startInstant 不同）／info 抛／isAlive=false 即跳過該次信號
        //   （無法驗證＝不得信號可能已非原 process 的 PID）；
        // - 兩次信號各自重驗（destroy 與 destroyForcibly 之間 PID 亦可能被回收）；
        // - 跳過計 [pidReuseSkipped]（僅不可驗證／已回收；`isAlive()==false` 的
        //   常規確認死亡不計；不抛不崩；本體 attestation 見 [attestBodyDestroy]，
        //   同計同一 [pidReuseSkipped]）；
        // - 子孫樹殺語義維持 best-effort（本守門只縮「流出→信號」窗口，
        //   TOCTOU 無法根除，見 KDoc）。
        // Stage 4：本路徑與 attestBodyDestroy 共用 [HandleAttest] seam，
        // isAliveOf／startInstantOf／signalAllowed 語義與舊 local 函數一致。
        try {
            descendants.forEach { h ->
                if (h == null) return@forEach
                // Stage 2A（M3）：快照僅取存活 handle——已死 handle 直接跳過，
                // 不讀 info()（dead handle 無信號可發；行為不變，僅省一次 info()）；
                // 不可驗證（null）同樣不取快照（signalAllowed 走 pidReuseSkipped 計數）。
                val alive = attest.isAliveOf(h)
                if (alive == false) return@forEach
                val expectedStart: Any? = if (alive == true) attest.startInstantOf(h) else null
                if (attest.signalAllowed(h, expectedStart)) {
                    runCatching { destroy?.invoke(h) }
                }
                if (attest.signalAllowed(h, expectedStart)) {
                    runCatching { destroyForcibly?.invoke(h) }
                }
            }
        } catch (_: Exception) {
            // Stream.forEach 本體異常（列舉中途 IO/併發失敗）：單個 handle 已在內層
            // runCatching 逐個收斂，此處僅吞 forEach 本體異常，fail-closed 為
            // 「已盡力殺」，呼叫方仍回 TimedOut，不升級為崩潰；finally 仍關
            // descendants 列舉流（`java.util.stream.Stream` 自身，非進程串流）。
        } finally {
            runCatching { descendants.close() }
        }
    }

    /**
     * Stage 4：[HandleAttest] 工廠（子孫＋本體共用的反射 attestation seam）。
     * R2-A「宣告類查表」紀律——toHandle 以 `Process::class.java` 查表、handle 方法
     * 以 handleClass（`ProcessHandle::class.java`）查表；`ProcessHandle` 類缺席
     * （舊 Android）在連結期走 [NoClassDefFoundError] 收斂為 null。
     * 不 invoke 任何信號方法——僅 Method 存在性與 toHandle 一次 invoke；
     * 讀取成敗由 [HandleAttest.isAliveOf]／[HandleAttest.startInstantOf] 表達。
     */
    private fun reflectHandleAttest(process: Process): HandleAttest {
        // R2-A：宣告類查表（實作類多為模組內 package-private，以其 javaClass 查表
        // 取到的 Method 宣告類不可見，invoke 即 IllegalAccessException）。
        val handle = try {
            Process::class.java.getMethod("toHandle").invoke(process)
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: InaccessibleObjectException) {
            null
        } catch (_: Exception) {
            null
        }
        // ProcessHandle 類本身缺席（舊 Android）在連結期走 NoClassDefFoundError
        // 收斂，不外抛（外層 killTree 另有 runCatching 縱深）。
        val handleClass: Class<*>? = try {
            ProcessHandle::class.java
        } catch (_: NoClassDefFoundError) {
            null
        } catch (_: Exception) {
            null
        }
        // destroy/destroyForcibly/info/isAlive 同以宣告類查表（各取一次；不 invoke）。
        val destroy = handleClass?.let { runCatching { it.getMethod("destroy") }.getOrNull() }
        val destroyForcibly =
            handleClass?.let { runCatching { it.getMethod("destroyForcibly") }.getOrNull() }
        val infoMethod = handleClass?.let { runCatching { it.getMethod("info") }.getOrNull() }
        val isAliveMethod = handleClass?.let { runCatching { it.getMethod("isAlive") }.getOrNull() }
        return HandleAttest(handle, handleClass, destroy, destroyForcibly, infoMethod, isAliveMethod)
    }

    /**
     * Stage 4（Stage 2A 子孫守門同款 seam；本體＋子孫共用，語義逐項一致）：
     * 持有 reflect 後的 handle 與 handleClass 宣告類查表結果——
     * - [handle]：`Process.toHandle()` 之回傳；null ⇒ toHandle 缺席／抛／回傳 null
     *   （平台無 attestation toolkit；子孫路徑退化單殺、本體路徑退化 unattested
     *   無條件 destroy）。
     * - [handleClass]：`ProcessHandle::class.java`；null ⇒ 類缺席
     *   （舊 Android，NoClassDefFoundError）。
     * - [destroy]／[destroyForcibly]：handle 信號 Method（不 invoke；本體路徑在
     *   attested 後才 invoke；子孫路徑迴圈內 invoke）。
     * - info/isAlive Method 原文保留於 [HandleAttest]；Method 存在性由
     *   [toolkitPresent] 表達；讀取成敗由 [isAliveOf]／[startInstantOf] 以
     *   null（不可驗證）表達。
     * pidReuseSkipped 為本體／子孫共用的診斷計數（僅不可驗證／已回收計）。
     */
    private class HandleAttest(
        val handle: Any?,
        val handleClass: Class<*>?,
        val destroy: Method?,
        val destroyForcibly: Method?,
        private val infoMethod: Method?,
        private val isAliveMethod: Method?,
    ) {
        /** toolkit 完整性（僅 Method 存在性；`startInstant` 可讀性由呼叫方以 live probe 判定）。 */
        val toolkitPresent: Boolean
            get() = handleClass != null && isAliveMethod != null && infoMethod != null

        fun isAliveOf(h: Any): Boolean? {
            if (isAliveMethod == null) return null // 平台無 isAlive API：不可驗證
            return try {
                isAliveMethod.invoke(h) as? Boolean
            } catch (_: Throwable) {
                null // 讀不可驗證
            }
        }

        fun startInstantOf(h: Any): Any? {
            if (infoMethod == null) return null
            return try {
                val info = infoMethod.invoke(h) ?: return null
                // 注意（R2-A 同款陷阱）：info 的執行時類（如 `ProcessHandleImpl$Info`）
                // 常為模組內 package-private，`info.javaClass.getMethod("startInstant")`
                // 取到的 Method 宣告類不可見 → invoke 即 IllegalAccessException。
                // 故以 runtime 宣告類的公開介面查表，不繫結任何編譯期型別
                // （舊 Android 缺席亦自然收斂為 null＝不可驗證）。
                // 走整個 superclass 鏈（generateSequence … superclass）逐層取其直接
                // interfaces（不遞迴超介面——各層 `it.interfaces` 非遞迴直取），覆蓋
                // 「startInstant 宣告於父類實作之介面」的平台實作。
                val infoIface = generateSequence(info.javaClass as Class<*>) { it.superclass }
                    .flatMap { it.interfaces.asSequence() }
                    .firstOrNull { cls ->
                        cls.methods.any { it.name == "startInstant" && it.parameterCount == 0 }
                    } ?: return null
                val opt = infoIface.getMethod("startInstant").invoke(info) ?: return null
                val present =
                    opt.javaClass.getMethod("isPresent").invoke(opt) as? Boolean ?: false
                if (present) opt.javaClass.getMethod("get").invoke(opt) else null
            } catch (_: Throwable) {
                null // info/startInstant 抛或缺席＝不可驗證（以 null 表達，呼叫方跳過）
            }
        }

        fun signalAllowed(h: Any, expectedStart: Any?): Boolean {
            val alive = isAliveOf(h)
            if (alive == null) {
                // isAlive API 缺席／讀抛／非常規回傳＝不可驗證：不信號＋計診斷。
                pidReuseSkipped.incrementAndGet()
                return false
            }
            if (!alive) {
                // 已死（destroy 已生效／自然退出）：無信號可發——常規態，不計診斷
                // （此路徑不得污染 pidReuseSkipped 的「無法 attest 身份」語義）。
                return false
            }
            val current = startInstantOf(h)
            if (!ElevatedProcessKiller.pidReuseSignalAllowed(alive, expectedStart, current)) {
                // 不可驗證（expected/current 任一 null ⇒ info 抛或 startInstant 缺席，
                // 含 handle 快照不可讀）或 PID 已回收（startInstant 不符）：跳過＋計診斷。
                pidReuseSkipped.incrementAndGet()
                return false
            }
            return true
        }
    }
}
