package dev.librepocket.systema

/**
 * 鬧鐘工具：`ALARM_CLOCK`（SET_ALARM）Intent 委託系統鬧鐘 App。
 *
 * 主路徑無需任何權限；降級「自有提醒」才需要精準鬧鐘申報
 * （`SCHEDULE_EXACT_ALARM`，API 31+；或 `USE_EXACT_ALARM`，
 * Play 需在 Data Safety + 商店描述中披露用途，見 [fallback] 的
 * requiresDeclaration）。兩風味皆允許。
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
    val extras = mutableMapOf(
      IntentActions.EXTRA_ALARM_HOUR to params.hour.toString(),
      IntentActions.EXTRA_ALARM_MINUTES to params.minute.toString(),
    )
    if (params.message.isNotBlank()) {
      extras[IntentActions.EXTRA_ALARM_MESSAGE] = params.message
    }
    if (params.skipUi) {
      extras[IntentActions.EXTRA_ALARM_SKIP_UI] = "true"
    }
    return IntentSpec(action = IntentActions.SET_ALARM, extras = extras)
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
    val ok = runCatching { launcher.launch(spec) }.getOrDefault(false)
    return if (ok) ToolResult(true, false, spec)
    else ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到設定鬧鐘（${ReasonCodes.NO_HANDLER}）")
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
