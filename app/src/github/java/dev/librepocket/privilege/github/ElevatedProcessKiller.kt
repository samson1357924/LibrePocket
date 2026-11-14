package dev.librepocket.privilege.github

import java.lang.reflect.InaccessibleObjectException
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
 * Stage 2A（P1-A7，PID-reuse 守門，security-auditor；不改呼叫方契約）：
 * 巢狀（descendants）`destroy/destroyForcibly` 前一律重驗——[killDescendantsReflective]
 * 對每個 handle 於其逐 handle 流出當下之後、信號之前取 `info().startInstant()` 快照
 * （僅存活 handle 取快照；已死 handle 直接跳過不讀 info()），每次信號前重讀
 * `startInstant()` 與 `isAlive()`：
 * - 兩次 startInstant 不符 ⇒ PID 已被回收重用 ⇒ **跳過信號**
 *   （不得對一顆可能已非原 process 的 PID 發信號；子孫清理 best-effort 語義不變）；
 * - `info()` 抛／`startInstant` 缺席／`isAlive()==false`／不可驗證 ⇒ 同跳過；
 *   其中「不可驗證／PID 回收」（死亡後的常規跳過除外）計入
 *   [pidReuseSkippedCount] 診斷計數（不抛、不崩，fail-closed 方向是不誤殺）；
 * - 本體 killTree 的 `process.destroy()/destroyForcibly()` 不走此守門
 *   （本體 handle 由 caller 持有，語義與現況一致）。
 * 子孫樹殺語義**維持 best-effort**：本守門只縮小「流出→信號」窗口，
 * 不保證 handle 快照當下 PID 仍真值原子配對（TOCTOU 無法根除，裝置 SELinux／
 * Root 差異 E2E 仍歸 #136-B3）。
 * 平台 `info()`／`startInstant()` 完全不可讀時，巢狀樹殺退化為僅殺本體
 * （body-only）——產品必須於啟用前以真機查驗 [pidReuseSkippedCount]（#136-B3 gate）。
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
     * Stage 2A（P1-A7，PID-reuse 守門；診斷用，產品不讀取；正常路徑恆為 0）：
     * 巢狀信號被跳過之次數——僅計「不可驗證或已回收（unverifiable or recycled）」，
     * 即我們無法 attest 該 handle 之身份（we could not attest identity）者：
     * `isAlive` API 缺席（method null）、`isAlive` 讀抛／非常規回傳、
     * `info()`／`startInstant()` 不可讀（handle 快照或信號前重讀）、
     * PID 已回收（startInstant 不符）。只有 `isAlive()==false` 的常規確認死亡
     * （dead-but-alive-previously handles）不計。
     *
     * 刻意非接線（deliberate NON-wiring）進 verdict／breaker／admission：
     * 平台若對任何一顆子孫完全無法 attest（無 isAlive／info／startInstant），
     * tree kill 會對每個 handle 都 fail-close——等於把整個提權子孫清理
     * 靜默關掉。那是產品決策（product decision），不是 killer 內部可定的
     * 行為，必須由 #136-B3 以裝置端證據決定——pidReuseSkipped 必須列入
     * #136-B3 裝置驗收清單（device-acceptance checklist）：啟用提權 runner
     * 前，須先以真機量測本計數確認平台 attest 能力。
     */
    private val pidReuseSkipped = AtomicInteger(0)

    /** PID-reuse 守門跳過的信號數快照（僅測試／診斷用）。 */
    internal fun pidReuseSkippedCount(): Int = pidReuseSkipped.get()

    /**
     * Stage 2A（P1-A7）巢狀信號判定（純函數 seam，供 JVM 單測直斷；
     * [killDescendantsReflective] 經 runtime reflection 餵入實際值）：
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
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
    }

    private fun killDescendantsReflective(process: Process) {
        // R2-A：宣告類查表，非實作類查表。實作類多為模組內 package-private
        //（如 JDK 的 ProcessImpl/ProcessHandleImpl，JDK 9+ 強封裝）：以其實
        // 例 `javaClass` 查表取到的 Method 宣告類不可見，invoke 即
        // IllegalAccessException（setAccessible 又觸 InaccessibleObjectException）。
        // 以宣告類（Process/ProcessHandle，public 且 exported）查表則直通。
        val handle = try {
            Process::class.java.getMethod("toHandle").invoke(process) ?: return
        } catch (_: NoSuchMethodException) {
            return // 舊 Android 無此 API：退化單殺（fail-closed；串流由 drain 關）。
        } catch (_: InaccessibleObjectException) {
            return // JDK 強封裝拒絕：退化單殺（fail-closed；串流由 drain 關）。
        } catch (_: Exception) {
            return
        }
        // ProcessHandle 類本身缺席（舊 Android）在連結期走 NoClassDefFoundError
        // 收斂，不外抛（外層 killTree 另有 runCatching 縱深）。
        val handleClass: Class<*> = try {
            ProcessHandle::class.java
        } catch (_: NoClassDefFoundError) {
            return
        } catch (_: Exception) {
            return
        }
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
        val destroy = runCatching { handleClass.getMethod("destroy") }.getOrNull()
        val destroyForcibly = runCatching { handleClass.getMethod("destroyForcibly") }.getOrNull()
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
        //   常規確認死亡不計；不抛不崩；本體單殺不受此守門約束）；
        // - 子孫樹殺語義維持 best-effort（本守門只縮「流出→信號」窗口，
        //   TOCTOU 無法根除，見 KDoc）。
        val infoMethod = runCatching { handleClass.getMethod("info") }.getOrNull()
        val isAliveMethod = runCatching { handleClass.getMethod("isAlive") }.getOrNull()
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
                // 故以 runtime 宣告類的公開介面（ProcessHandle$Info）查表，不繫結
                // 任何編譯期型別（舊 Android 缺席亦自然收斂為 null＝不可驗證）。
                val infoIface = info.javaClass.interfaces.firstOrNull { cls ->
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
            if (!pidReuseSignalAllowed(alive, expectedStart, current)) {
                // 不可驗證（expected/current 任一 null ⇒ info 抛或 startInstant 缺席，
                // 含 handle 快照不可讀）或 PID 已回收（startInstant 不符）：跳過＋計診斷。
                pidReuseSkipped.incrementAndGet()
                return false
            }
            return true
        }
        try {
            descendants.forEach { h ->
                if (h == null) return@forEach
                // Stage 2A（M3）：快照僅取存活 handle——已死 handle 直接跳過，
                // 不讀 info()（dead handle 無信號可發；行為不變，僅省一次 info()）；
                // 不可驗證（null）同樣不取快照（signalAllowed 走 pidReuseSkipped 計數）。
                val alive = isAliveOf(h)
                if (alive == false) return@forEach
                val expectedStart: Any? = if (alive == true) startInstantOf(h) else null
                if (signalAllowed(h, expectedStart)) {
                    runCatching { destroy?.invoke(h) }
                }
                if (signalAllowed(h, expectedStart)) {
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
}
