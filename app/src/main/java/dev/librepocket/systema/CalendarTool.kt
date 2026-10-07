package dev.librepocket.systema

/**
 * 日曆工具：Provider 直查 + INSERT 委託優先。
 *
 * - 建事件：優先 `ACTION_INSERT` 委託系統日曆 App（免 `WRITE_CALENDAR`，
 *   由系統 App 完成最後一步，符合 PRIVILEGED→系統接管語義）。
 * - 查事件：經 Calendar Provider，需 `READ_CALENDAR`（[checkQuery] 斷言）。
 * - 改/刪事件（S1-C 補齊）：有系統日曆 App 時優先 `ACTION_EDIT` 委託
 *   （免 `WRITE_CALENDAR`）；無系統日曆 App 時純側僅回待寫描述
 *  （ok=false + usedFallback=true，NEED_PERMISSION），由端側持
 *   `WRITE_CALENDAR` 再調 ContentResolver 直寫（[checkUpdate]/[checkDelete] 斷言）。
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

  /**
   * 更新參數：僅攜帶要改的欄位（S1-C）。`eventId` 為 Events 表行 id；
   * 時間欄位 null 表不改，非 null 時 `endTimeMillis > beginTimeMillis`
   * 由呼叫方保證（委託 Intent 不校驗，Provider 端再斷言）。
   */
  data class UpdateParams(
    val eventId: String,
    val title: String? = null,
    val beginTimeMillis: Long? = null,
    val endTimeMillis: Long? = null,
    val location: String? = null,
    val description: String? = null,
  )

  /** 刪除參數：僅事件行 id。 */
  data class DeleteParams(val eventId: String)

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

  /**
   * 改事件檢查（S1-C）：有系統日曆 App 即 NATIVE（EDIT 委託優先，
   * 免 WRITE_CALENDAR）；無 App 時純側不直寫 Provider（需端側
   * ContentResolver），一律 DEGRADED + NEED_PERMISSION，待寫描述由
   * [executeUpdate]/[executeDelete] 以 ok=false + usedFallback=true 回傳。
   */
  fun checkUpdate(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉日曆快通道")
    }
    if (env.hasCalendarApp) {
      return CheckResult.native("EDIT 委託優先，免日曆寫權限")
    }
    return if (env.calendarWriteGranted) {
      CheckResult.degraded(ReasonCodes.NEED_PERMISSION, "無系統日曆 App，Provider 待寫需端側直寫")
    } else {
      CheckResult.degraded(ReasonCodes.NEED_PERMISSION, "需 WRITE_CALENDAR 授權")
    }
  }

  /**
   * 刪事件檢查（S1-C）：與 [checkUpdate] 同一門禁（委託優先，否則需寫權限）。
   */
  fun checkDelete(env: SystemEnv): CheckResult = checkUpdate(env)

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

  /** 單事件 URI（EDIT 委託與 Provider 直接寫共用）。 */
  fun eventUri(eventId: String): String {
    require(eventId.isNotBlank()) { "eventId must not be blank" }
    return "$EVENTS_CONTENT_URI/${eventId.trim()}"
  }

  /**
   * 更新的 EDIT 委託 Intent（S1-C）：有系統日曆 App 時優先走此路徑，
   * 免 WRITE_CALENDAR，由系統 App 完成最後一步。僅攜帶非空變更欄位。
   */
  fun buildEditIntent(params: UpdateParams): IntentSpec {
    require(params.eventId.isNotBlank()) { "eventId must not be blank" }
    val extras = mutableMapOf<String, String>()
    params.title?.takeIf { it.isNotBlank() }?.let { extras[EXTRA_EVENT_TITLE] = it }
    params.beginTimeMillis?.let { extras[EXTRA_BEGIN_TIME] = it.toString() }
    params.endTimeMillis?.let { extras[EXTRA_END_TIME] = it.toString() }
    params.location?.takeIf { it.isNotBlank() }?.let { extras[EXTRA_EVENT_LOCATION] = it }
    params.description?.takeIf { it.isNotBlank() }?.let { extras[EXTRA_EVENT_DESCRIPTION] = it }
    return IntentSpec(
      action = IntentActions.EDIT,
      dataUri = eventUri(params.eventId),
      extras = extras,
    )
  }

  /** Provider 直接刪除的 URI + selection 摘要（純側；端側再調 ContentResolver）。 */
  fun buildDeleteDescriptor(params: DeleteParams): Pair<String, String> {
    return eventUri(params.eventId) to "_id=?"
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

  /**
   * 改事件執行（S1-C）：有系統日曆 App 走 EDIT 委託；無 App 時純側不直寫
   * Provider（未調 launcher/ContentResolver），回待寫描述
   * `ToolResult(ok=false, usedFallback=true, NEED_PERMISSION)`，
   * 端側持 WRITE_CALENDAR 時再調 ContentResolver 直寫。
   */
  fun executeUpdate(params: UpdateParams, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = checkUpdate(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到更新日曆事件（${check.reasonCode}）")
    }
    if (env.hasCalendarApp) {
      val spec = buildEditIntent(params)
      val ok = runCatching { launcher.launch(spec) }.getOrDefault(false)
      return if (ok) ToolResult(true, false, spec)
      else ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到更新日曆事件（${ReasonCodes.NO_HANDLER}）")
    }
    // 無系統日曆 App：純側僅回待寫描述，不算成功（spec 攜帶目標 URI + 變更欄位）。
    val pending = buildEditIntent(params)
    return ToolResult(false, true, pending, ReasonCodes.NEED_PERMISSION, "做不到更新日曆事件（${ReasonCodes.NEED_PERMISSION}）")
  }

  /**
   * 刪事件執行（S1-C）：有系統日曆 App 走 EDIT 委託（由使用者在系統 App
   * 內確認刪除）；無 App 時同更新：僅回待寫描述
   * `ToolResult(ok=false, usedFallback=true, NEED_PERMISSION)`，端側再調
   * ContentResolver。
   */
  fun executeDelete(params: DeleteParams, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = checkDelete(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到刪除日曆事件（${check.reasonCode}）")
    }
    if (env.hasCalendarApp) {
      val spec = IntentSpec(action = IntentActions.EDIT, dataUri = eventUri(params.eventId))
      val ok = runCatching { launcher.launch(spec) }.getOrDefault(false)
      return if (ok) ToolResult(true, false, spec)
      else ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到刪除日曆事件（${ReasonCodes.NO_HANDLER}）")
    }
    val (uri, _) = buildDeleteDescriptor(params)
    return ToolResult(false, true, IntentSpec(action = IntentActions.DELETE, dataUri = uri), ReasonCodes.NEED_PERMISSION, "做不到刪除日曆事件（${ReasonCodes.NEED_PERMISSION}）")
  }

  override fun fallback(params: EventParams, env: SystemEnv): FallbackSpec = FallbackSpec(
    reasonCode = ReasonCodes.NO_HANDLER,
    userMessage = "做不到建立「${params.title}」（${ReasonCodes.NO_HANDLER}）" +
      "→ 可替代手動在系統日曆新建 → 需要你開啟日曆 App 填入時間",
  )

  /** 改事件降級（S1-C）：一律手動替代，引用原因碼。 */
  fun fallbackUpdate(params: UpdateParams, reasonCode: String): FallbackSpec = FallbackSpec(
    reasonCode = reasonCode,
    userMessage = "做不到更新事件「${params.eventId}」（$reasonCode）" +
      "→ 可替代手動在系統日曆編輯 → 需要你開啟日曆 App 修改後儲存",
  )

  /** 刪事件降級（S1-C）：一律手動替代，引用原因碼。 */
  fun fallbackDelete(params: DeleteParams, reasonCode: String): FallbackSpec = FallbackSpec(
    reasonCode = reasonCode,
    userMessage = "做不到刪除事件「${params.eventId}」（$reasonCode）" +
      "→ 可替代手動在系統日曆刪除 → 需要你開啟日曆 App 長按刪除",
  )
}
