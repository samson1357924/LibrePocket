package dev.librepocket.systema

/**
 * 日曆工具：Provider 直查 + INSERT 委託優先。
 *
 * - 建事件：優先 `ACTION_INSERT` 委託系統日曆 App（免 `WRITE_CALENDAR`，
 *   由系統 App 完成最後一步，符合 PRIVILEGED→系統接管語義）。
 * - 查事件：經 Calendar Provider，需 `READ_CALENDAR`（[checkQuery] 斷言）。
 * - 三風味皆允許；Play 需申報日曆權限 + 用途披露（矩陣 §1 列 7）。
 */
object CalendarTool : SystemTool<CalendarTool.EventParams> {

  data class EventParams(
    val title: String,
    val beginTimeMillis: Long,
    val endTimeMillis: Long,
    val location: String = "",
    val description: String = "",
  )

  data class QueryParams(
    val startMillis: Long,
    val endMillis: Long,
    val projection: List<String> = DEFAULT_PROJECTION,
  )

  /** CalendarContract.Events 常用欄位子集（字串常量，避免依賴 Android SDK）。 */
  val DEFAULT_PROJECTION: List<String> = listOf("_id", "title", "dtstart", "dtend", "eventLocation")

  const val EVENTS_CONTENT_URI = "content://com.android.calendar/events"
  const val EXTRA_BEGIN_TIME = "beginTime"
  const val EXTRA_END_TIME = "endTime"
  const val EXTRA_EVENT_TITLE = "title"
  const val EXTRA_EVENT_LOCATION = "eventLocation"
  const val EXTRA_EVENT_DESCRIPTION = "description"

  override val descriptor: ToolDescriptor
    get() = ToolRegistry.get(ToolRegistry.CALENDAR)!!

  /** 建事件檢查：有系統日曆即 NATIVE（委託路徑免權限）。 */
  override fun check(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉日曆快通道")
    }
    return if (env.hasCalendarApp) {
      CheckResult.native("INSERT 委託優先，免日曆寫權限")
    } else {
      CheckResult.unavailable(ReasonCodes.NO_HANDLER, "無系統日曆 App")
    }
  }

  /** 查事件檢查：Provider 直查需 READ_CALENDAR 已授權。 */
  fun checkQuery(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉日曆快通道")
    }
    return if (env.calendarReadGranted) {
      CheckResult.native("Provider 直查可用")
    } else {
      CheckResult.degraded(ReasonCodes.NEED_PERMISSION, "需 READ_CALENDAR 授權")
    }
  }

  fun buildInsertIntent(params: EventParams): IntentSpec {
    require(params.title.isNotBlank()) { "title must not be blank" }
    require(params.endTimeMillis > params.beginTimeMillis) { "endTime must be after beginTime" }
    val extras = mutableMapOf(
      EXTRA_BEGIN_TIME to params.beginTimeMillis.toString(),
      EXTRA_END_TIME to params.endTimeMillis.toString(),
      EXTRA_EVENT_TITLE to params.title,
    )
    if (params.location.isNotBlank()) extras[EXTRA_EVENT_LOCATION] = params.location
    if (params.description.isNotBlank()) extras[EXTRA_EVENT_DESCRIPTION] = params.description
    return IntentSpec(
      action = IntentActions.INSERT,
      dataUri = EVENTS_CONTENT_URI,
      extras = extras,
    )
  }

  /** Provider 查詢的 content URI + selection 摘要（純側；端側再拼 Cursor）。 */
  fun buildQueryDescriptor(params: QueryParams): Pair<String, String> {
    val uri = "$EVENTS_CONTENT_URI?start=${params.startMillis}&end=${params.endMillis}"
    val selection = "dtstart>=? AND dtend<=?"
    return uri to selection
  }

  override fun execute(params: EventParams, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到建日曆事件（${check.reasonCode}）")
    }
    val spec = buildInsertIntent(params)
    val ok = runCatching { launcher.launch(spec) }.getOrDefault(false)
    return if (ok) ToolResult(true, false, spec)
    else ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到建日曆事件（${ReasonCodes.NO_HANDLER}）")
  }

  override fun fallback(params: EventParams, env: SystemEnv): FallbackSpec = FallbackSpec(
    reasonCode = ReasonCodes.NO_HANDLER,
    userMessage = "做不到建立「${params.title}」（${ReasonCodes.NO_HANDLER}）" +
      "→ 可替代手動在系統日曆新建 → 需要你開啟日曆 App 填入時間",
  )
}
