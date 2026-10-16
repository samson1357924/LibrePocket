package dev.librepocket.systema

/**
 * 日曆工具：Provider 直查 + INSERT 委託優先。
 *
 * Status: Target（純側契約，未接線未 E2E；矩陣層 `executionReady=false` 概念，
 * 非 `ToolDescriptor` 欄位；目前尚無生產呼叫方，接線 PR 啟用；`SystemEnv` 無生產 producer，
 * `timeoutMs` 僅宣告未強制）。
 *
 * - 建事件：優先 `ACTION_INSERT` 委託系統日曆 App（免 `WRITE_CALENDAR`，
 *   由系統 App 完成最後一步，符合 PRIVILEGED→系統接管語義）。
 * - 查事件：經 Calendar Provider，需 `READ_CALENDAR`（[checkQuery] 斷言）。
 * - 改/刪事件（S1-C 補齊）：有系統日曆 App 時優先 `ACTION_EDIT` 委託
 *   （免 `WRITE_CALENDAR`）；無系統日曆 App 時純側僅回待寫描述
 *  （ok=false + usedFallback=true，NEED_PERMISSION），由端側持
 *   `WRITE_CALENDAR` 再調 ContentResolver 直寫（[checkUpdate]/[checkDelete] 斷言）。
 * - 三風味皆允許；Play 需申報日曆權限 + 用途披露。
 * - 可見性（#14）：主 manifest `<queries>` 已宣告 INSERT（dir/event）/EDIT
 *   （item/event）；API 30+ 無宣告時 `resolveActivity` 被過濾為 null。
 * - 與 [AlarmTool] 的刻意差異（#14/#15 對齊決策）：建事件靜態無 App 時本工具
 *   回 UNAVAILABLE（Alarm 回 DEGRADED），因建事件手動降級仍需一個日曆 App
 *   可開（見 [fallback] 話術），無 App 時連手動都做不到；Alarm 自有提醒不需
 *   外部 App，故可 DEGRADED。執行期 NoHandler（靜態有 App 但發射時無處理者：
 *   被移除/停用/不可見，#14 可見性過濾即此類）則與 Alarm 同語義走降級
 *   （ok=false + usedFallback=true + 手動話術，spec 保留），因 App 極可能
 *   仍在（僅對我方不可見），使用者仍可手動開啟；若 App 已確認移除/停用，
 *   不得提示手動開啟（話術為最佳努力，見測試釘選）。
 * - 降級僅是話術 + spec 保留，無真執行（無 AlarmManager/ContentResolver 直寫）；
 *   `usedFallback=true` 不得被計為成功。
 * - DELETE 待寫 spec（[executeDelete] 無 App 路徑）禁經 [AndroidIntentLauncher]
 *   發射，僅供展示/端側直寫用；未來 dispatcher 須以 action allowlist 拒絕
 *   `DELETE` 經 launcher。
 */
object CalendarTool : SystemTool<CalendarTool.EventParams> {

  /** Binder/傳輸防禦上限（build/parse 同守衛，不等到執行期才暴露）。 */
  const val MAX_TITLE_LEN = 500
  const val MAX_LOCATION_LEN = 500
  const val MAX_DESCRIPTION_LEN = 2000

  /** 單事件合理時長上限（5 年毫秒），防 Long.MAX_VALUE/數十年跨度猜測。 */
  const val MAX_DURATION_MILLIS = 5L * 365 * 24 * 60 * 60 * 1000

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
    require(params.title.length <= MAX_TITLE_LEN) { "title too long: ${params.title.length} > $MAX_TITLE_LEN" }
    require(params.location.length <= MAX_LOCATION_LEN) { "location too long" }
    require(params.description.length <= MAX_DESCRIPTION_LEN) { "description too long" }
    require(params.beginTimeMillis >= 0) { "beginTime must be >= 0" }
    require(params.endTimeMillis >= 0) { "endTime must be >= 0" }
    require(params.endTimeMillis > params.beginTimeMillis) { "endTime must be after beginTime" }
    require(params.endTimeMillis - params.beginTimeMillis <= MAX_DURATION_MILLIS) { "duration too long" }
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
    require(title.length <= MAX_TITLE_LEN) { "title too long" }
    require(location.length <= MAX_LOCATION_LEN) { "location too long" }
    require(description.length <= MAX_DESCRIPTION_LEN) { "description too long" }
    require(begin >= 0) { "beginTime must be >= 0" }
    require(end >= 0) { "endTime must be >= 0" }
    require(end > begin) { "endTime must be after beginTime" }
    require(end - begin <= MAX_DURATION_MILLIS) { "duration too long" }
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
    if (begin != null && end != null) {
      require(begin >= 0) { "beginTime must be >= 0" }
      require(end >= 0) { "endTime must be >= 0" }
      require(end > begin) { "endTime must be after beginTime" }
      require(end - begin <= MAX_DURATION_MILLIS) { "duration too long" }
    } else {
      if (begin != null) require(begin >= 0) { "beginTime must be >= 0" }
      if (end != null) require(end >= 0) { "endTime must be >= 0" }
    }
    params.title?.let { require(it.length <= MAX_TITLE_LEN) { "title too long" } }
    params.location?.let { require(it.length <= MAX_LOCATION_LEN) { "location too long" } }
    params.description?.let { require(it.length <= MAX_DESCRIPTION_LEN) { "description too long" } }
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
    // fail-closed：builder IAE/ISE 收斂為 INTERNAL，不穿透生產迴圈。
    val spec = try {
      buildInsertIntent(params)
    } catch (e: IllegalArgumentException) {
      return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到建日曆事件（${ReasonCodes.INTERNAL}）")
    } catch (e: IllegalStateException) {
      if (e is kotlin.coroutines.cancellation.CancellationException) throw e
      return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到建日曆事件（${ReasonCodes.INTERNAL}）")
    }
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
      // fail-closed：EDIT builder 非法參數收斂為 INTERNAL。
      val spec = try {
        buildEditIntent(params)
      } catch (e: IllegalArgumentException) {
        return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到更新日曆事件（${ReasonCodes.INTERNAL}）")
      } catch (e: IllegalStateException) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到更新日曆事件（${ReasonCodes.INTERNAL}）")
      }
      return when (val launched = launcher.launchCatching(spec)) {
        // 發現式缺席：與建事件同語義走手動降級（[fallbackUpdate] 話術，spec 保留）。
        LaunchResult.NoHandler ->
          ToolResult(false, true, spec, ReasonCodes.NO_HANDLER, fallbackUpdate(params, ReasonCodes.NO_HANDLER).userMessage)
        else -> launched.toToolResult(spec, "更新日曆事件")
      }
    }
    // 無系統日曆 App：純側僅回待寫描述，不算成功（spec 攜帶目標 URI + 變更欄位）。
    // fail-closed：pending builder 非法同樣收斂為 INTERNAL。
    val pending = try {
      buildEditIntent(params)
    } catch (e: IllegalArgumentException) {
      return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到更新日曆事件（${ReasonCodes.INTERNAL}）")
    } catch (e: IllegalStateException) {
      if (e is kotlin.coroutines.cancellation.CancellationException) throw e
      return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到更新日曆事件（${ReasonCodes.INTERNAL}）")
    }
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
      // fail-closed：非法 eventId 收斂為 INTERNAL，不穿透。
      val spec = try {
        IntentSpec(action = IntentActions.EDIT, dataUri = eventUri(params.eventId))
      } catch (e: IllegalArgumentException) {
        return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到刪除日曆事件（${ReasonCodes.INTERNAL}）")
      } catch (e: IllegalStateException) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到刪除日曆事件（${ReasonCodes.INTERNAL}）")
      }
      return when (val launched = launcher.launchCatching(spec)) {
        // 發現式缺席：與建事件同語義走手動降級（[fallbackDelete] 話術，spec 保留）。
        LaunchResult.NoHandler ->
          ToolResult(false, true, spec, ReasonCodes.NO_HANDLER, fallbackDelete(params, ReasonCodes.NO_HANDLER).userMessage)
        else -> launched.toToolResult(spec, "刪除日曆事件")
      }
    }
    // fail-closed：非法 eventId 收斂為 INTERNAL；DELETE 待寫 spec 禁經 launcher 發射。
    val uri = try {
      buildDeleteDescriptor(params).first
    } catch (e: IllegalArgumentException) {
      return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到刪除日曆事件（${ReasonCodes.INTERNAL}）")
    } catch (e: IllegalStateException) {
      if (e is kotlin.coroutines.cancellation.CancellationException) throw e
      return ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到刪除日曆事件（${ReasonCodes.INTERNAL}）")
    }
    return ToolResult(false, true, IntentSpec(action = IntentActions.DELETE, dataUri = uri), ReasonCodes.NEED_PERMISSION, "做不到刪除日曆事件（${ReasonCodes.NEED_PERMISSION}）")
  }

  /**
   * 建事件降級僅用於 NO_HANDLER（靜態無 App 不可執行，不經此函式；
   * 執行期 NoHandler 才走手動話術）。USER_DISABLED 等路徑由 [execute] 早期回傳。
   */

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
