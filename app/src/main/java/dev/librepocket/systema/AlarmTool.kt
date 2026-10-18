package dev.librepocket.systema

/**
 * 鬧鐘工具：`ALARM_CLOCK`（SET_ALARM）Intent 委託系統鬧鐘 App。
 *
 * Status: Target（純側契約，未接線未 E2E；矩陣層 `executionReady=false` 概念，
 * 非 `ToolDescriptor` 欄位；目前尚無生產呼叫方，接線 PR 啟用；`SystemEnv` 無生產 producer，
 * `timeoutMs` 僅宣告未強制，見 [ToolRegistry]）。
 *
 * 本層守衛多為**本層自訂的 fail-closed 政策**，較官方契約嚴格：官方
 * Common Intents 對 HOUR/MINUTES 未定義數值範圍、對 MESSAGE 未定義長度
 * 上限（皆無）；本層 0-23/0-59/500 字元為 Binder 傳輸防禦（超界一律
 * INTERNAL 而非照送），未來 dispatcher 接線時模型端須知「官方合法但
 * 本層拒絕」的可能。
 *
 * 權限決策（#14：發送端需宣告 normal 級 `com.android.alarm.permission.SET_ALARM`）：
 * 官方 Common Intents 明確要求「要發送 ACTION_SET_ALARM，App 必須持有
 * `com.android.alarm.permission.SET_ALARM`」。接收端 activity（Google Clock /
 * AOSP DeskClock 的 SET_ALARM handler）以 `android:permission` 保護時，該屬性
 * 限制的是**呼叫端**：未持有此權限的發送 App 在 `startActivity` 會被系統以
 * `SecurityException` 拒絕。此為 normal 級權限（安裝即自動授權、非高風險，
 * Play 不需申報或 Data Safety 披露），故於 common manifest 宣告，三風味一致。
 * 個別時鐘 App 若未以該權限保護，亦不影響：持有 normal 權限者一律可發射。
 * 若未來時鐘 App 改變保護方式而觸發其他 `SecurityException`，[AndroidIntentLauncher]
 * 已收斂為 `INTERNAL`（fail-closed，不靜默退回猜測執行；僅附 cause 類名，
 * 不含 message/PII，亦無 audit——intent 層不審計，由未來 dispatcher 層統一審計）。
 * 降級「自有提醒」才需要精準鬧鐘申報
 * （`SCHEDULE_EXACT_ALARM`，API 31+；或 `USE_EXACT_ALARM`，
 * Play 需在 Data Safety + 商店描述中披露用途，見 [fallback] 的
 * requiresDeclaration）。三風味皆允許。
 *
 * 可見性（#14）：主 manifest `<queries>` 已宣告 SET_ALARM；API 30+ 無宣告時
 * `resolveActivity` 被過濾為 null → 誤判 NO_HANDLER。靜態 [check] 通過但發射
 * 時仍無處理者（實現被移除/停用/不可見），一律走自有提醒降級（見 [execute]），
 * 與靜態 DEGRADED 同語義。
 *
 * 確認門（ASK 不默許 ALLOW）：[Params.skipUi] 為 true 時僅是「請求免確認直設」，
 * 仍由系統鬧鐘 App 決定是否尊重；未來 dispatcher 若把模型參數透傳給
 * [parseParams]，必須在 `skipUi=true` 時要求 `confirmed + 不可變操作快照
 * （hour/minute/message + requestId + generation）` 全等，否則回 `NeedConfirm`
 *（接線 PR 需先補 requestId/generation/confirmed/NeedConfirm 型別，現僅 KDoc 約束）。
 * 本層不代行確認，不靜默直設.
 */
object AlarmTool : SystemTool<AlarmTool.Params> {

  /** Binder 傳輸上限防禦：訊息過長在 builder/parse 即拒，不等到 TransactionTooLarge。 */
  const val MAX_MESSAGE_LEN = 500

  data class Params(
    val hour: Int,
    val minute: Int,
    val message: String = "",
    /** true = 免確認直設（需系統鬧鐘 App 支援；否則退回顯示 UI）。 */
    val skipUi: Boolean = false,
  )

  override val descriptor: ToolDescriptor
    get() = ToolRegistry.get(ToolRegistry.SET_ALARM)!!

  override fun check(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉鬧鐘快通道")
    }
    return if (env.hasAlarmApp) {
      CheckResult.native("系統鬧鐘 App 可用")
    } else {
      CheckResult.degraded(ReasonCodes.NO_HANDLER, "無系統鬧鐘 App，降級自有提醒")
    }
  }

  fun buildSetAlarmIntent(params: Params): IntentSpec {
    require(params.hour in 0..23) { "hour out of range: ${params.hour}" }
    require(params.minute in 0..59) { "minute out of range: ${params.minute}" }
    require(params.message.length <= MAX_MESSAGE_LEN) { "message too long: ${params.message.length} > $MAX_MESSAGE_LEN" }
    // D2 型別化：HOUR/MINUTES 用 Int（AlarmClock.EXTRA_* 為 int），
    // SKIP_UI 用 Boolean；系統鬧鐘 App 才能正確讀回（getIntExtra/getBooleanExtra）。
    // 空白正規化：build 丟棄全空白 message（isNotBlank），parse 缺席回 ""；
    // 故 Params(7,30," ") round-trip 後為 ""，屬刻意正規化（見測試釘選）。
    val extras = mutableMapOf<String, IntentExtra>(
      IntentActions.EXTRA_ALARM_HOUR to IntentExtra.IntVal(params.hour),
      IntentActions.EXTRA_ALARM_MINUTES to IntentExtra.IntVal(params.minute),
    )
    if (params.message.isNotBlank()) {
      extras[IntentActions.EXTRA_ALARM_MESSAGE] = IntentExtra.Str(params.message)
    }
    if (params.skipUi) {
      extras[IntentActions.EXTRA_ALARM_SKIP_UI] = IntentExtra.Bool(true)
    }
    return IntentSpec(action = IntentActions.SET_ALARM, extras = extras)
  }

  /**
   * 從 extras 還原 [Params]（#14 生產讀側採用 [requireExtra]，#19 猜測執行禁令）。
   *
   * HOUR/MINUTES 必填 Int（缺參/錯型拋 `IllegalStateException`，帶 key + 期望型別，
   * 禁 `getIntExtra(key, 0)` 式預設猜測——缺 hour 就設 00:00 是猜測執行）；
   * MESSAGE 選填（缺席=空）；SKIP_UI 選填（缺席=false，見 [Params.skipUi]）。
   * 範圍守衛與 [buildSetAlarmIntent] 同訊息，dispatcher 不得繞過 builder 校驗。
   * 未來 dispatcher 將模型參數轉 Params 的唯一入口；目前尚無生產呼叫方（接線 PR 啟用）。
   */
  fun parseParams(extras: Map<String, IntentExtra>): Params {
    val hour = extras.requireExtra<IntentExtra.IntVal>(IntentActions.EXTRA_ALARM_HOUR).value
    val minute = extras.requireExtra<IntentExtra.IntVal>(IntentActions.EXTRA_ALARM_MINUTES).value
    val message = extras.optionalExtra<IntentExtra.Str>(IntentActions.EXTRA_ALARM_MESSAGE)?.value.orEmpty()
    val skipUi = extras.optionalExtra<IntentExtra.Bool>(IntentActions.EXTRA_ALARM_SKIP_UI)?.value ?: false
    require(hour in 0..23) { "hour out of range: $hour" }
    require(minute in 0..59) { "minute out of range: $minute" }
    require(message.length <= MAX_MESSAGE_LEN) { "message too long: ${message.length} > $MAX_MESSAGE_LEN" }
    return Params(hour, minute, message, skipUi)
  }

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到設定鬧鐘（${check.reasonCode}）")
    }
    if (check.availability == Availability.DEGRADED) {
      // 靜態無系統鬧鐘 App：與執行期 NoHandler 同語義（自有提醒降級），
      // 話術統一取 [fallback]（含 label 與通知授權提示，與姊妹工具一致）。
      return ToolResult(false, true, null, check.reasonCode, fallback(params, env).userMessage)
    }
    // fail-closed：builder IAE/ISE（範圍/長度）收斂為 INTERNAL，不穿透生產迴圈
    //（收斂點 [guardedToolBuild]：話術由呼叫方提供，訊息與原 inline catch 逐字相同）。
    val internalFailure =
      ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到設定鬧鐘（${ReasonCodes.INTERNAL}）")
    val spec = when (val build = guardedToolBuild(internalFailure) { buildSetAlarmIntent(params) }) {
      is Guarded.Internal -> return build.result
      is Guarded.Built -> build.value
    }
    return when (val launched = launcher.launchCatching(spec)) {
      // 發現式缺席（靜態 check 通過但 resolve 為空：實現被移除/停用/不可見）：
      // 與靜態 DEGRADED 同語義（自有提醒降級），話術統一取 [fallback]，
      // 禁靜默吞掉或猜測執行。
      LaunchResult.NoHandler -> ToolResult(
        false, true, spec, ReasonCodes.NO_HANDLER, fallback(params, env).userMessage,
      )
      // Success/Internal 走共用收斂。
      else -> launched.toToolResult(spec, "設定鬧鐘")
    }
  }

  /**
   * 降級分支僅用於 NO_HANDLER（靜態無 App 或執行期發現式缺席）。
   * USER_DISABLED 等不可執行路徑由 [execute] 早期回傳，不經此函式；
   * 未來呼叫方不得以 USER_DISABLED 調用本函式取 NO_HANDLER 話術。
   */
  override fun fallback(params: Params, env: SystemEnv): FallbackSpec {
    val label = if (params.message.isNotBlank()) "「${params.message}」" else "鬧鐘"
    return FallbackSpec(
      reasonCode = ReasonCodes.NO_HANDLER,
      requiresDeclaration = "自有提醒需申報 SCHEDULE_EXACT_ALARM（API 31+）或 USE_EXACT_ALARM，" +
        "並請求 POST_NOTIFICATIONS；觸發時間 " +
        // Locale.ROOT：時間用拉丁數字，與話術的 zh-Hant 一致（禁依裝置 locale 變體數字）。
        String.format(java.util.Locale.ROOT, "%02d:%02d", params.hour, params.minute),
      userMessage = "做不到呼叫系統鬧鐘（${ReasonCodes.NO_HANDLER}）" +
        "→ 可替代 App 內自有提醒$label → 需要你授權精準鬧鐘與通知",
    )
  }
}
