package dev.librepocket.agent.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

/**
 * Activity 重建（recreation）時，ChatScreen 登記於 ActivityResultRegistry 的 launched key
 * （"stt-<screenId>-slotN-<resetEpoch>"，screenId／resetEpoch 自複合快照 [SttLaunchState] 內推導）與 pending result 由 registry
 * 存在 saved bundle 中、重建後重新投遞給新組合的 callback——因此「重建前 launch 的 recognizer，其
 * 結果可能在重建後的 composition 回來」是合法路徑，而不是錯誤。此時 slot/arbiter 的記憶體狀態（`remember{}`）會是全新的，無法僅憑
 * 新值判斷結果歸屬；本檔將每個 slot 的 launch 身分（slotBusy/slotGen）、arbiter 的
 * generation 水位與 per-entry identity（screenId/resetEpoch）釘進同一枚 `rememberSaveable`
 * 複合快照，重建後還原，使重建前 launch 的結果仍能被精確歸屬。
 *
 * 複合快照還原 fail-closed（atomic）：任何欄位驗證失敗一律整枚回退為新的 fresh composite
 * （含**新 UUID**、slots 全閒、水位 0、epoch 0）——slot 身分與 launcher registry key identity
 * 是同一枚 entry，不可能一半還原成功、一半回退；重建前 outstanding 結果在 registry 邊界
 * 永不可投遞，也不會以半信半疑的狀態重建，更不會 NPE（見 [SttLaunchStateSaver] KDoc）。
 *
 * 所有類別維持 `internal`；不暴露為 public API。
 *
 * Saved-state 的存活範圍 ⊆ 單次 boot：rememberSaveable／Bundle 不跨 reboot 保存，重建還原只在
 * 同一次開機內的 activity recreation 之間成立——`elapsedRealtimeNanos` 之所以能跨還原被比較，
 * 正因為快照不可能跨 boot 存活（clock 位元組在 reboot 後會重置）。
 */

/**
 * 單一 slot 的 launch 歸屬快照（跨 recreation 還原用）。
 *
 * - [slotBusy] / [slotGen]：與 ChatScreen 的兩枚回呼 slot 一一對應（各 size 2）；
 *   [slotGen] 登記該 slot 對應的 admit generation（launch 時自 `currentGeneration` 讀取）。
 * - [admitGeneration]：arbiter 的 generation 水位（單調、不重置），保證重建後 decide 出的
 *   generation 嚴格大於任何已發出的 launch 的 generation。
 * - [inFlightName] / [admittedAtNanos]：in-flight arm（[VoiceSttArmKind] 名稱）與其 admit 時間
 *   （與 [admitGeneration] 同一枚 admit 的配對值；clock 來源由 `LocalVoiceSttArbiterClock` 提供，
 *   production 為 elapsedRealtimeNanos，monotonic 含 deep sleep、跨 process 一致）。
 * - [screenId]：per-entry 隨機 UUID（launcher registry key 推導："stt-<screenId>-slotN-<resetEpoch>"）；
 *   同一 entry recreation 還原同一 UUID，新 entry 必得新 UUID。
 * - [resetEpoch]：使用者硬重設的世代（單調遞增；重設後重設前 launcher 於重組時同步 unregister，
 *   其晚到結果在 registry 邊界永不可投遞）。
 *
 * [screenId]／[resetEpoch] 與 slot/watermark 同屬一枚複合快照：任何欄位驗證失敗一律整枚
 * fresh（新 UUID），金鑰不可匹配任何既有 outstanding rc→key 綁定（atomic fail-closed）。
 */
internal data class SttLaunchState(
    val slotBusy: List<Boolean>,
    val slotGen: List<Long>,
    val admitGeneration: Long,
    val inFlightName: String?,
    val admittedAtNanos: Long,
    val screenId: String,
    val resetEpoch: Int,
) {
    internal companion object {
        /** 全新（無 launch 紀錄）的快照：slot 全閒、generation 水位 0、無 in-flight、新 UUID、epoch 0。 */
        internal fun fresh(
            screenId: String = UUID.randomUUID().toString(),
            resetEpoch: Int = 0,
        ): SttLaunchState =
            SttLaunchState(
                slotBusy = listOf(false, false),
                slotGen = listOf(0L, 0L),
                admitGeneration = 0L,
                inFlightName = null,
                admittedAtNanos = 0L,
                screenId = screenId,
                resetEpoch = resetEpoch,
            )
    }
}

/**
 * Snapshot → bundle 欄位的轉換邏輯（[SttLaunchStateSaver] 的核心；抽成 internal top-level
 * 供 JVM 單元測試直接驗證，行為與 saver 完全一致）。
 */
internal fun saveSttLaunchState(state: SttLaunchState): List<Any?> =
    listOf(
        state.slotBusy,
        state.slotGen,
        state.admitGeneration,
        state.inFlightName,
        state.admittedAtNanos,
        state.screenId,
        state.resetEpoch,
    )

/**
 * bundle 欄位 → snapshot 的還原邏輯；fail-closed 驗證（見 [SttLaunchStateSaver] KDoc）。
 * slotBusy size==2 且全為 Boolean、slotGen size==2 且全為 Long、admitGeneration/admittedAtNanos
 * 皆 >= 0、inFlightName == null 或為 [VoiceSttArmKind] 的列舉名、screenId 為 36 字元且可
 * `UUID.fromString` 解析的 String、resetEpoch 為非負 Int；跨欄位語意不合一律回傳 null：
 *
 * 1. [slotBusy][i] == true 但 slotGen[i] == 0L（楔住的 slot 永遠無法歸屬）；
 * 2. [slotBusy][i] == false 但 slotGen[i] != 0L（production 一律在 release 時把 gen 歸零）；
 * 3. inFlightName != null 但 admitGeneration == 0L（production 的每枚 admit 都會遞增到 ≥ 1）；
 * 4. inFlightName != null 但 admittedAtNanos == 0L（production 的每枚 admit 都寫入 clock）；
 * 5. admitGeneration >= Long.MAX_VALUE（下一枚 admit 會 wrap 成負世代，污染水位單調性）；
 * 6. slotGen[i] > admitGeneration（水位不可能低於任何已發出的世代）。
 *
 * 頂部尺寸須為 7（舊 5 元素 bundle → null，由 [SttLaunchStateSaver] 外層攔截回 fresh composite）；
 * identity 軸：saved[5] 必須是 36 字元且可 `UUID.fromString` 解析的 String；saved[6] 必須是非負
 * Int。任何不合一律回傳 null（使 [SttLaunchStateSaver] 外層回 fresh composite；pure function
 * 本身回 null，JVM 單元測試直接釘住此 fail-closed 形狀）。
 */
internal fun restoreSttLaunchState(saved: List<Any?>): SttLaunchState? {
    if (saved.size != 7) return null
    val busy = saved[0] as? List<*> ?: return null
    if (busy.size != 2 || busy.any { it !is Boolean }) return null
    val gens = saved[1] as? List<*> ?: return null
    if (gens.size != 2 || gens.any { it !is Long }) return null
    val generation = saved[2] as? Long ?: return null
    if (generation < 0L) return null
    val name = saved[3]
    if (name != null) {
        if (name !is String) return null
        if (VoiceSttArmKind.entries.none { it.name == name }) return null
    }
    val admitted = saved[4] as? Long ?: return null
    if (admitted < 0L) return null
    val busyList = busy.map { it as Boolean }
    val genList = gens.map { it as Long }
    // 跨欄位語意不變量（production 一律維持；bundle 被竄改／半信半疑的快照一律退回 fresh）：
    // slotBusy[i]==true ⇒ slotGen[i] 非 0；slotBusy[i]==false ⇒ slotGen[i] == 0L。
    for (i in 0 until 2) {
        val gen = genList[i]
        if (busyList[i] && gen == 0L) return null
        if (!busyList[i] && gen != 0L) return null
    }
    // in-flight 與 generation 水位成對：admit 一律 mint ≥ 1 的世代並寫入 clock。
    if (name != null) {
        if (generation == 0L) return null
        if (admitted == 0L) return null
        // A restored in-flight arm MUST hold at least one wedged/reserved slot — production
        // invariant (launch reserves its slot atomically with the admit). All-slots-free with
        // a named in-flight arm cannot be a production snapshot (crafted/half-written bundle).
        if (busyList.none { it }) return null
    }
    // 水位達 Long.MAX_VALUE 時下一枚 admit 會 wrap，污染單調性；拒絕。
    if (generation >= Long.MAX_VALUE) return null
    // 水位不可能低於任何已發出的世代。
    if (genList.any { it > generation }) return null
    // 複合快照的 identity 軸（fail-closed；任一軸不合一律整枚退回 null）。
    val screenId = saved[5] as? String ?: return null
    if (screenId.length != 36 || runCatching { UUID.fromString(screenId) }.isFailure) return null
    val resetEpoch = saved[6] as? Int ?: return null
    if (resetEpoch < 0) return null
    return SttLaunchState(
        slotBusy = busyList,
        slotGen = genList,
        admitGeneration = generation,
        inFlightName = name as String?,
        admittedAtNanos = admitted,
        screenId = screenId,
        resetEpoch = resetEpoch,
    )
}

/**
 * [SttLaunchState] 的 `rememberSaveable` saver。
 *
 * 複合快照契約（atomic fail-closed）：slotBusy/slotGen/admitGeneration/inFlightName/
 * admittedAtNanos/screenId/resetEpoch 是**同一枚** entry（ChatScreen 的 `sttLaunchSnapshot`），
 * 任何欄位驗證失敗（見 `restoreSttLaunchState` KDoc）一律**整枚**回退為新的
 * [SttLaunchState.fresh]（新 UUID、slots 全閒、水位 0、epoch 0），而不是帶著半信半疑的狀態
 * 重建、也不是沿用舊 identity 發新 slots——因此重建前 outstanding launch 的 rc→key 綁定在
 * 新 composition 永遠不可匹配，結果在 registry 邊界不可投遞（gen==0L fail-closed 亦丟棄），
 * 重建不會 NPE。
 * 完全自洽的 crafted bundle（全部欄位驗證皆通過）仍按原樣還原——saved-state 屬 framework 信任域，本 saver 不是 anti-tamper／replay 邊界；其安全性建立在「違規即整枚 fresh」而非「通過即可信」上。
 *
 * `restore` 永不回傳 null：`rememberSaveable` 的 `restored ?: init()` 只在「沒有保存值」時
 * 執行 init；若 saver 回傳 null，`mutableStateSaver` 外層仍會提供一個非 null 的
 * `MutableState<SttLaunchState?>`、內容卻是 null，重建當下的首讀即 NPE。bundle 內的數值經
 * Bundle 往返後型別必為 Boolean／Long／Int／String／List，型別檢查依此進行。
 * Saved-state 的存活範圍 ⊆ 單次 boot（rememberSaveable／Bundle 不跨 reboot 存活），`elapsedRealtimeNanos`
 * 之所以能跨還原被比較，也正因為此存活範圍限制。
 */
internal val SttLaunchStateSaver = listSaver(
    save = { state -> saveSttLaunchState(state) },
    restore = { saved -> restoreSttLaunchState(saved) ?: SttLaunchState.fresh() },
)

/**
 * Test-only injection seam：預置一個還原用的 [SttLaunchState]（模擬「activity 重建後
 * `rememberSaveable` 還原」的路徑），生產環境永遠為 `null`——生產的還原來源只有
 * saved bundle（經 [SttLaunchStateSaver]），不經此 composition local。
 */
internal val LocalSttLaunchStateBootstrap = staticCompositionLocalOf<SttLaunchState?> { null }

/**
 * 結果歸屬的 fail-closed 判定：generation == 0L 代表「這枚結果沒有任何可歸屬的 launch」。
 *
 * registry 可能把重建前的結果重新投遞給重建後的 composition，而重建後的 slot 表是全新的
 * （`slotGen` 全 0L）；每枚 production launch 都會登記非 0 的 generation（admit 時由
 * `currentGeneration` 讀取，最小為 1），所以 0L 一定不可歸屬。不可歸屬的結果必須被丟棄：
 * 不 prefill、不動 slot、不結算閘——任何簿記都會汙染重建後的新狀態。
 */
internal fun isUnattributedVoiceResult(gen: Long): Boolean = gen == 0L
