package dev.librepocket.systema

/**
 * 鬧鐘工具：`ALARM_CLOCK`（SET_ALARM）Intent 委託系統鬧鐘 App。
 *
 * 權限決策（#14：採「修正註解＋降級語義」，不加 manifest 宣告）：
 * SET_ALARM 發送端無需任何 `<uses-permission>`——AOSP `AlarmClock` 只要求
 * 接收端 activity 持有 `com.android.alarm.permission.SET_ALARM`（時鐘 App
 * 自行宣告），發送端走 `startActivity` 交由使用者選擇器，無發送端宣告要求
 * （`ACTION_SET_ALARM` 區塊無 `@RequiresPermission`；權限註記只出現在
 * DISMISS/TIMER 動作）。故不為發送端加宣告（加了也是死重且擴大 Play 權限面）。
 * 個別裝置時鐘若自行強制要求而觸發 `SecurityException`，[AndroidIntentLauncher]
 * 已收斂為 `INTERNAL`（fail-closed，不靜默退回猜測執行）。
 * 降級「自有提醒」才需要精準鬧鐘申報
 * （`SCHEDULE_EXACT_ALARM`，API 31+；或 `USE_EXACT_ALARM`，
 * Play 需在 Data Safety + 商店描述中披露用途，見 [fallback] 的
 * requiresDeclaration）。三風味皆允許。
 *
 * 可見性（#14）：主 manifest `<queries>` 已宣告 SET_ALARM；API 30+ 無宣告時
 * `resolveActivity` 被過濾為 null → 誤判 NO_HANDLER。靜態 [check] 通過但發射
 * 時仍無處理者（實現被移除/停用/不可見），一律走自有提醒降級（見 [execute]），
 * 與靜態 DEGRADED 同語義。
 */
object AlarmTool : SystemTool<AlarmTool.Params> {

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
    // D2 型別化：HOUR/MINUTES 用 Int（AlarmClock.EXTRA_* 為 int），
    // SKIP_UI 用 Boolean；系統鬧鐘 App 才能正確讀回（getIntExtra/getBooleanExtra）。
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
    return Params(hour, minute, message, skipUi)
  }

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到設定鬧鐘（${check.reasonCode}）")
    }
    if (check.availability == Availability.DEGRADED) {
      return ToolResult(
        false, true, null, check.reasonCode,
        "做不到呼叫系統鬧鐘（${check.reasonCode}）→ 可替代自有提醒 → 需要你授權精準鬧鐘",
      )
    }
    val spec = buildSetAlarmIntent(params)
    return when (val launched = launcher.launchCatching(spec)) {
      // 發現式缺席（靜態 check 通過但 resolve 為空：實現被移除/停用/不可見）：
      // 與靜態 DEGRADED 同語義（自有提醒降級），禁靜默吞掉或猜測執行。
      LaunchResult.NoHandler -> ToolResult(
        false, true, spec, ReasonCodes.NO_HANDLER,
        "做不到呼叫系統鬧鐘（${ReasonCodes.NO_HANDLER}）→ 可替代自有提醒 → 需要你授權精準鬧鐘",
      )
      // Success/Internal 走共用收斂。
      else -> launched.toToolResult(spec, "設定鬧鐘")
    }
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec {
    val label = if (params.message.isNotBlank()) "「${params.message}」" else "鬧鐘"
    return FallbackSpec(
      reasonCode = ReasonCodes.NO_HANDLER,
      requiresDeclaration = "自有提醒需申報 SCHEDULE_EXACT_ALARM（API 31+）或 USE_EXACT_ALARM，" +
        "並請求 POST_NOTIFICATIONS；触发時間 %02d:%02d".format(params.hour, params.minute),
      userMessage = "做不到呼叫系統鬧鐘（${ReasonCodes.NO_HANDLER}）" +
        "→ 可替代 App 內自有提醒$label → 需要你授權精準鬧鐘與通知",
    )
  }
}
