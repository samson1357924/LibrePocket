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
 * - 可見性（#14）：主 manifest `<queries>` 已宣告 INSERT（dir/event）/EDIT
 *   （item/event）；API 30+ 無宣告時 `resolveActivity` 被過濾為 null。
 * - 與 [AlarmTool] 的刻意差異（#14/#15 對齊決策）：建事件靜態無 App 時本工具
 *   回 UNAVAILABLE（Alarm 回 DEGRADED），因建事件手動降級仍需一個日曆 App
 *   可開（見 [fallback] 話術），無 App 時連手動都做不到；Alarm 自有提醒不需
 *   外部 App，故可 DEGRADED。執行期 NoHandler（靜態有 App 但發射時無處理者：
 *   被移除/停用/不可見，#14 可見性過濾即此類）則與 Alarm 同語義走降級
 *   （ok=false + usedFallback=true + 手動話術，spec 保留），因 App 極可能
 *   仍在（僅對我方不可見），使用者仍可手動開啟。
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
   * 更新參數：僅攜帶要改的欄位（S1-C）。`eventId` 為 Events 表數字行 id
   *（[eventUri] 做嚴格數字校驗）；時間欄位 null 表不改，兩者皆非 null 時
   * [buildEditIntent] 要求 `endTimeMillis > beginTimeMillis`（與 insert 一致）。
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

  /** Events `_id` 數字校驗（避免每次呼叫重配 Regex）。 */
  private val EVENT_ID_PATTERN = Regex("[0-9]+")

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
    // D2 型別化：begin/end 保持 Long（CalendarContract.EXTRA_* 為 long）。
    val extras = mutableMapOf<String, IntentExtra>(
      EXTRA_BEGIN_TIME to IntentExtra.LongVal(params.beginTimeMillis),
      EXTRA_END_TIME to IntentExtra.LongVal(params.endTimeMillis),
      EXTRA_EVENT_TITLE to IntentExtra.Str(params.title),
    )
    if (params.location.isNotBlank()) extras[EXTRA_EVENT_LOCATION] = IntentExtra.Str(params.location)
    if (params.description.isNotBlank()) extras[EXTRA_EVENT_DESCRIPTION] = IntentExtra.Str(params.description)
    return IntentSpec(
      action = IntentActions.INSERT,
      dataUri = EVENTS_CONTENT_URI,
      extras = extras,
    )
  }

  /**
   * 從 extras 還原 [EventParams]（#14 生產讀側採用 [requireExtra]，#19 猜測執行禁令）。
   *
   * title/begin/end 必填（缺參/錯型拋 `IllegalStateException`，禁空標題或零時間
   * 猜測建事件）；location/description 選填（缺席=空，錯型仍拋錯）。
   * 排序守衛與 [buildInsertIntent] 同訊息。未來 dispatcher 唯一入口；目前尚無
   * 生產呼叫方（接線 PR 啟用）。
   */
  fun parseInsertParams(extras: Map<String, IntentExtra>): EventParams {
    val title = extras.requireExtra<IntentExtra.Str>(EXTRA_EVENT_TITLE).value
    val begin = extras.requireExtra<IntentExtra.LongVal>(EXTRA_BEGIN_TIME).value
    val end = extras.requireExtra<IntentExtra.LongVal>(EXTRA_END_TIME).value
    val location = extras.optionalExtra<IntentExtra.Str>(EXTRA_EVENT_LOCATION)?.value.orEmpty()
    val description = extras.optionalExtra<IntentExtra.Str>(EXTRA_EVENT_DESCRIPTION)?.value.orEmpty()
    require(title.isNotBlank()) { "title must not be blank" }
    require(end > begin) { "endTime must be after beginTime" }
    return EventParams(title, begin, end, location, description)
  }

  /** Provider 查詢的 content URI + selection 摘要（純側；端側再拼 Cursor）。 */
  fun buildQueryDescriptor(params: QueryParams): Pair<String, String> {
    val uri = "$EVENTS_CONTENT_URI?start=${params.startMillis}&end=${params.endMillis}"
    val selection = "dtstart>=? AND dtend<=?"
    return uri to selection
  }

  /** 單事件 URI（EDIT 委託與 Provider 直接寫共用）。 */
  fun eventUri(eventId: String): String {
    val id = eventId.trim()
    require(id.isNotBlank()) { "eventId must not be blank" }
    // Events _id 本來數字行 id：嚴格數字校驗，擋 `1/2`/`1?x=1`/`../0` 等路徑/查詢注入。
    require(id.matches(EVENT_ID_PATTERN)) { "eventId must be numeric" }
    return "$EVENTS_CONTENT_URI/$id"
  }

  /**
   * 更新的 EDIT 委託 Intent（S1-C）：有系統日曆 App 時優先走此路徑，
   * 免 WRITE_CALENDAR，由系統 App 完成最後一步。僅攜帶非空變更欄位。
   */
  fun buildEditIntent(params: UpdateParams): IntentSpec {
    require(params.eventId.trim().isNotBlank()) { "eventId must not be blank" }
    val begin = params.beginTimeMillis
    val end = params.endTimeMillis
    // 與 insert 一致：兩端皆給時要求排序（單端 null 表不改，不校驗）。
    if (begin != null && end != null) require(end > begin) { "endTime must be after beginTime" }
    val extras = mutableMapOf<String, IntentExtra>()
    params.title?.takeIf { it.isNotBlank() }?.let { extras[EXTRA_EVENT_TITLE] = IntentExtra.Str(it) }
    params.beginTimeMillis?.let { extras[EXTRA_BEGIN_TIME] = IntentExtra.LongVal(it) }
    params.endTimeMillis?.let { extras[EXTRA_END_TIME] = IntentExtra.LongVal(it) }
    params.location?.takeIf { it.isNotBlank() }?.let { extras[EXTRA_EVENT_LOCATION] = IntentExtra.Str(it) }
    params.description?.takeIf { it.isNotBlank() }?.let { extras[EXTRA_EVENT_DESCRIPTION] = IntentExtra.Str(it) }
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
    return when (val launched = launcher.launchCatching(spec)) {
      // 發現式缺席（靜態 check 通過但 resolve 為空：實現被移除/停用/不可見）：
      // 與 AlarmTool 同語義走手動降級（ok=false + usedFallback=true，話術與
      // [fallback] 一致，嘗試過的 spec 保留），禁靜默吞掉或猜測執行。
      LaunchResult.NoHandler -> ToolResult(false, true, spec, ReasonCodes.NO_HANDLER, fallback(params, env).userMessage)
      // Success/Internal 走共用收斂。
      else -> launched.toToolResult(spec, "建日曆事件")
    }
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
      return when (val launched = launcher.launchCatching(spec)) {
        // 發現式缺席：與建事件同語義走手動降級（[fallbackUpdate] 話術，spec 保留）。
        LaunchResult.NoHandler ->
          ToolResult(false, true, spec, ReasonCodes.NO_HANDLER, fallbackUpdate(params, ReasonCodes.NO_HANDLER).userMessage)
        else -> launched.toToolResult(spec, "更新日曆事件")
      }
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
      return when (val launched = launcher.launchCatching(spec)) {
        // 發現式缺席：與建事件同語義走手動降級（[fallbackDelete] 話術，spec 保留）。
        LaunchResult.NoHandler ->
          ToolResult(false, true, spec, ReasonCodes.NO_HANDLER, fallbackDelete(params, ReasonCodes.NO_HANDLER).userMessage)
        else -> launched.toToolResult(spec, "刪除日曆事件")
      }
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
