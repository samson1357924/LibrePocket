package dev.librepocket.systema

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * #14 Intent adapter 修碼測試（Stage1，Play scoped）。
 *
 * 三決策對應：
 * - a) SET_ALARM 發送端需 `<uses-permission>`（官方 Common Intents 契約；見 [AlarmTool] KDoc）：manifest
 *   必須宣告（pinned by [manifest_declaresSetAlarmSenderPermission])，接收端保護仍在時
 *   `SecurityException` → INTERNAL（pinned by [alarm_deniedSender_mapsToInternal])；
 *   發射時無處理者走自有提醒降級（pinned by [alarm_launchNoHandler_degradesToOwnReminder])。
 * - b) package visibility 最小 queries（SET_ALARM + 日曆 INSERT/EDIT，見主
 *   manifest #14 註解；直啟 setPackage 不採用：OEM 包名各異=猜測，#19）：
 *   pinned by [manifest_main_declaresAlarmCalendarQueries] +
 *   [manifest_flavorOverlays_doNotTouchAlarmCalendar]；不可見即 NoHandler
 *   （pinned by [alarm_unresolvedHandler_mapsToNoHandler]）。
 * - c) 生產讀側採用 [requireExtra]/[optionalExtra]（[AlarmTool.parseParams]、
 *   [CalendarTool.parseInsertParams]）：缺參/錯型明確拋錯，禁預設猜測執行（#19）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntentAdapterStage1Test {

  private val env = SystemEnv(hasAlarmApp = true, hasCalendarApp = true)

  private fun appContext(): Context = ApplicationProvider.getApplicationContext()

  private fun addResolve(app: Context, intent: Intent) {
    val appInfo = ApplicationInfo().apply { packageName = app.packageName }
    val activityInfo = ActivityInfo().apply {
      packageName = app.packageName
      name = "FakeActivity"
      applicationInfo = appInfo
      exported = true
    }
    Shadows.shadowOf(app.packageManager)
      .addResolveInfoForIntent(intent, ResolveInfo().apply { this.activityInfo = activityInfo })
  }

  /** 單測工作目錄（模組 dir）出發，向上走最多 5 層找原始碼/清單檔（同 VoiceSttTest）。 */
  private fun findSource(vararg relPaths: String): java.io.File {
    val bases = generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }.take(6).toList()
    for (base in bases) {
      for (rel in relPaths) {
        val f = java.io.File(base, rel)
        if (f.isFile) return f
      }
    }
    error("not found: ${relPaths.toList()} under ${System.getProperty("user.dir")}")
  }

  private fun mainManifest(): String = findSource(
    "src/main/AndroidManifest.xml",
    "app/src/main/AndroidManifest.xml",
  ).readText()

  private fun flavorManifest(flavor: String): String = findSource(
    "src/$flavor/AndroidManifest.xml",
    "app/src/$flavor/AndroidManifest.xml",
  ).readText()

  /**
   * 寬容 manifest 正向 pin：單屬性元素以語義 regex 鎖定屬性值（逐字鎖值，
   * 經 [Regex.escape]），僅容忍引號型別、`=` 前後空白、自閉斜線位置與元素尾端
   * 的**額外屬性**（如 play overlay 的 `tools:node="remove"`）等排版差異
   * （AGP/lint 對 manifest 排版的正規化不應使語義不變的 pin 假紅）；
   * 負向 pin 仍用 `contains`（嚴格保守）。
   *
   * 已知界限（誠實記載）：pinned 屬性須為元素的**第一位屬性**（現行全部
   * pin 目標皆如此、AGP 不重排既有屬性順序）；額外屬性群組經負查排除第二個
   * 同名 pinned 屬性（重複屬性本為無效 manifest，aapt2 建置即擋）；
   * 檢查為**原文級**（不解析 XML 語義樹，註解內的同形元素也會命中——現行
   * 目標檔經實測無註解誤觸，改動 manifest 時留意）。
   */
  private fun assertManifestHasSingleAttr(manifest: String, tag: String, attrName: String, attrValue: String) {
    val value = Regex.escape(attrValue)
    val re = Regex(
      """<$tag\s+android:$attrName\s*=\s*(?:"$value"|'$value')""" +
        """(?:\s+(?!android:$attrName\s*=)[\w:.\-]+\s*=\s*(?:"[^"]*"|'[^']*'))*\s*/?>""",
    )
    assertTrue(
      "manifest 應宣告 <$tag android:$attrName=\"$attrValue\">",
      re.containsMatchIn(manifest),
    )
  }

  // ---- a/b. manifest 宣告 ----

  @Test fun manifest_main_declaresAlarmCalendarQueries() {
    val manifest = mainManifest()
    // 寬容 regex pin（見 helper KDoc）：語義鎖值，不再逐字比對整個元素排版。
    assertManifestHasSingleAttr(manifest, "action", "name", "android.intent.action.SET_ALARM")
    assertManifestHasSingleAttr(manifest, "action", "name", "android.intent.action.INSERT")
    assertManifestHasSingleAttr(manifest, "data", "mimeType", "vnd.android.cursor.dir/event")
    assertManifestHasSingleAttr(manifest, "action", "name", "android.intent.action.EDIT")
    assertManifestHasSingleAttr(manifest, "data", "mimeType", "vnd.android.cursor.item/event")
    // 導航/音樂 VIEW（scheme-only）：targetSdk 36 下未宣告時 resolve 恆 null＝
    // 必然假 NO_HANDLER；geo 給 NavigationTool、https 給 MusicTool 網頁搜尋與
    // NavigationTool 網頁地圖（見 manifest #14 註解與各工具 KDoc）。
    assertManifestHasSingleAttr(manifest, "data", "scheme", "geo")
    assertManifestHasSingleAttr(manifest, "data", "scheme", "https")
  }

  @Test fun manifest_declaresSetAlarmSenderPermission() {
    // 決策 a)：官方契約要求發送端持有 normal 級 com.android.alarm.permission.SET_ALARM，
    // 故 common manifest 必須宣告（缺權會在真機被 SecurityException 拒絕）。
    // 正向 pin 亦改寬容 regex（見 helper KDoc）；負向與 overlay 檢查嚴格保守。
    val manifest = mainManifest()
    assertManifestHasSingleAttr(manifest, "uses-permission", "name", "com.android.alarm.permission.SET_ALARM")
    // 不可寫成 framework 命名空間（android.permission.SET_ALARM 不存在）。
    assertFalse(manifest.contains("android.permission.SET_ALARM"))
    // 三 flavor 疊加層不得各自偷加（權限只在 common 宣告一次）。
    for (flavor in listOf("play", "foss", "github")) {
      assertFalse(
        "$flavor overlay 不得重複宣告 SET_ALARM 權限",
        flavorManifest(flavor).contains("com.android.alarm.permission.SET_ALARM"),
      )
    }
  }

  @Test fun manifest_flavorOverlays_doNotTouchAlarmCalendar() {
    // 三 flavor 疊加層不得動 #14 可見性（亦不得偷加宣告；見上一個測試）。
    for (flavor in listOf("play", "foss", "github")) {
      val overlay = flavorManifest(flavor)
      assertFalse("$flavor overlay 不得出現 SET_ALARM", overlay.contains("SET_ALARM"))
      assertFalse("$flavor overlay 不得出現 calendar mimeType", overlay.contains("vnd.android.cursor"))
    }
    // 防疊加層被整塊改寫：Play 高風險 uses-permission 宣告仍在（寬容 regex pin，
    // 見 helper KDoc；該元素帶 tools:node="remove" 額外屬性，helper 的額外屬性
    // 寬容得以**容納**它——此為排版容忍、非鎖定該屬性，tools:node 被移除不會
    // 假紅；「移除後不進 play 產物」的語義由 policy gate 於 artifact 層把關）。
    assertManifestHasSingleAttr(flavorManifest("play"), "uses-permission", "name", "android.permission.SEND_SMS")
  }

  // ---- c. 生產讀側：alarm parse ----

  @Test fun alarm_parse_roundTrip() {
    val params = AlarmTool.Params(7, 30, "起床", skipUi = true)
    assertEquals(params, AlarmTool.parseParams(AlarmTool.buildSetAlarmIntent(params).extras))
  }

  @Test fun alarm_parse_missingHour_throwsExplicit() {
    val extras = mapOf(IntentActions.EXTRA_ALARM_MINUTES to IntentExtra.IntVal(30))
    try {
      AlarmTool.parseParams(extras)
      fail("expected IllegalStateException for missing HOUR")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", IntentActions.EXTRA_ALARM_HOUR in (e.message ?: ""))
    }
  }

  @Test fun alarm_parse_wrongType_throwsExplicit() {
    // D1 全 String 殘留：HOUR 誤為 Str 時禁讀回預設，須明確錯。
    val extras = mapOf(
      IntentActions.EXTRA_ALARM_HOUR to IntentExtra.Str("7"),
      IntentActions.EXTRA_ALARM_MINUTES to IntentExtra.IntVal(30),
    )
    try {
      AlarmTool.parseParams(extras)
      fail("expected IllegalStateException for mistyped HOUR")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", "IntVal" in (e.message ?: ""))
      assertTrue(e.message ?: "", "Str" in (e.message ?: ""))
    }
  }

  @Test fun alarm_parse_optionalKeys_defaultOnlyWhenAbsent() {
    // 選填鍵缺席才回預設（MESSAGE=空、SKIP_UI=false）；出現但錯型仍拋錯。
    val parsed = AlarmTool.parseParams(AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30)).extras)
    assertEquals("", parsed.message)
    assertFalse(parsed.skipUi)
    try {
      AlarmTool.parseParams(
        mapOf(
          IntentActions.EXTRA_ALARM_HOUR to IntentExtra.IntVal(7),
          IntentActions.EXTRA_ALARM_MINUTES to IntentExtra.IntVal(30),
          IntentActions.EXTRA_ALARM_SKIP_UI to IntentExtra.Str("true"),
        ),
      )
      fail("expected IllegalStateException for mistyped SKIP_UI")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", IntentActions.EXTRA_ALARM_SKIP_UI in (e.message ?: ""))
    }
  }

  @Test fun alarm_parse_badRange_rejected() {
    try {
      AlarmTool.parseParams(
        mapOf(
          IntentActions.EXTRA_ALARM_HOUR to IntentExtra.IntVal(25),
          IntentActions.EXTRA_ALARM_MINUTES to IntentExtra.IntVal(0),
        ),
      )
      fail("expected IllegalArgumentException for hour=25")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message ?: "", "out of range" in (e.message ?: ""))
    }
  }

  // ---- c. 生產讀側：calendar parse + helper ----

  @Test fun calendar_parseInsert_roundTrip() {
    val params = CalendarTool.EventParams("例會", 1_700_000_000_000, 1_700_000_360_000, "會議室", "週會")
    assertEquals(params, CalendarTool.parseInsertParams(CalendarTool.buildInsertIntent(params).extras))
  }

  @Test fun calendar_parseInsert_missingTitle_throwsExplicit() {
    val extras = mapOf(
      CalendarTool.EXTRA_BEGIN_TIME to IntentExtra.LongVal(1000L),
      CalendarTool.EXTRA_END_TIME to IntentExtra.LongVal(2000L),
    )
    try {
      CalendarTool.parseInsertParams(extras)
      fail("expected IllegalStateException for missing title")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", CalendarTool.EXTRA_EVENT_TITLE in (e.message ?: ""))
    }
  }

  @Test fun calendar_parseInsert_wrongTypeBegin_throwsExplicit() {
    val extras = mapOf(
      CalendarTool.EXTRA_EVENT_TITLE to IntentExtra.Str("例會"),
      CalendarTool.EXTRA_BEGIN_TIME to IntentExtra.IntVal(1000),
      CalendarTool.EXTRA_END_TIME to IntentExtra.LongVal(2000L),
    )
    try {
      CalendarTool.parseInsertParams(extras)
      fail("expected IllegalStateException for mistyped beginTime")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", "LongVal" in (e.message ?: ""))
    }
  }

  @Test fun optionalExtra_absentNullButMistypedThrows() {
    assertNull(emptyMap<String, IntentExtra>().optionalExtra<IntentExtra.Str>("k"))
    assertEquals(
      IntentExtra.Str("x"),
      mapOf("k" to IntentExtra.Str("x")).optionalExtra<IntentExtra.Str>("k"),
    )
    try {
      mapOf("k" to IntentExtra.LongVal(1L)).optionalExtra<IntentExtra.Str>("k")
      fail("expected IllegalStateException for mistyped optional extra")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", "k" in (e.message ?: ""))
    }
  }

  // ---- a/b. 發射分型：不可見/缺權/降級 ----

  @Test fun alarm_launchNoHandler_degradesToOwnReminder() {
    // 發現式缺席（靜態 check 通過但發射無處理者）與靜態 DEGRADED 同語義：
    // ok=false + usedFallback=true + NO_HANDLER + 自有提醒話術（嘗試過的 spec 保留）。
    val r = AlarmTool.execute(AlarmTool.Params(7, 30), env, IntentLauncher { LaunchResult.NoHandler })
    assertFalse(r.ok)
    assertTrue(r.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, r.reasonCode)
    assertTrue(r.message, "可替代 App 內自有提醒" in r.message)
    assertEquals(IntentActions.SET_ALARM, r.spec?.action)
  }

  @Test fun alarm_unresolvedHandler_mapsToNoHandler() {
    // package-visibility 過濾或無實現：resolve null → NoHandler（fail-closed，不崩）。
    val app = appContext()
    val spec = AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30))
    assertEquals(LaunchResult.NoHandler, AndroidIntentLauncher(app).launch(spec))
  }

  @Test fun alarm_deniedSender_mapsToInternal() {
    // 缺權分型：接收端保護仍在而我方未持有 → SecurityException → INTERNAL（非 NO_HANDLER）。
    // 此路徑為 fail-closed 防禦；正常情況發送端已宣告 normal 級 SET_ALARM 權限（見上）。
    val app = appContext()
    addResolve(app, Intent(IntentActions.SET_ALARM))
    val denied = object : ContextWrapper(app) {
      override fun startActivity(intent: Intent): Unit = throw SecurityException("deny-secret")
    }
    val r = AndroidIntentLauncher(denied).launch(AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30)))
    assertTrue(r is LaunchResult.Internal)
    // 只附 cause 類名，不得回顯例外 message（PII/秘密不外洩）。
    val msg = r.toToolResult(
      AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30)),
      "設定鬧鐘",
    ).message
    assertTrue("INTERNAL 訊息應含例外類名：$msg", msg.contains("SecurityException"))
    assertFalse("INTERNAL 訊息不得回顯例外 message：$msg", msg.contains("deny-secret"))
  }

  @Test fun flags_grantReadAndClearTask_droppedViaToIntent() {
    // GRANT_*（URI 授權擴大）與 CLEAR_TASK（清空任務棧）一律丟棄；
    // 允許位保留，NEW_TASK 預設仍在；EXCLUDE_FROM_RECENTS/NO_HISTORY
    //（任務棧語義最危險兩位）同樣丟棄。
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(
      IntentSpec(
        action = IntentActions.SET_ALARM,
        flags = IntentFlags.GRANT_READ_URI_PERMISSION or
          IntentFlags.GRANT_WRITE_URI_PERMISSION or
          IntentFlags.ACTIVITY_CLEAR_TASK or
          IntentFlags.ACTIVITY_EXCLUDE_FROM_RECENTS or
          IntentFlags.ACTIVITY_NO_HISTORY or
          IntentFlags.ACTIVITY_CLEAR_TOP or
          IntentFlags.ACTIVITY_SINGLE_TOP,
      ),
    )
    assertEquals(0, intent.flags and IntentFlags.GRANT_READ_URI_PERMISSION)
    assertEquals(0, intent.flags and IntentFlags.GRANT_WRITE_URI_PERMISSION)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  // ---- d. #15 對齊：日曆執行期 NoHandler 走手動降級（與鬧鐘自有提醒同形） ----

  @Test fun calendar_executeNoHandler_degradesToManual() {
    // 發現式缺席（靜態 check 通過但發射無處理者）與鬧鐘同語義：
    // ok=false + usedFallback=true + NO_HANDLER + 手動話術（嘗試過的 spec 保留，
    // INSERT 帶 content URI）。
    val r = CalendarTool.execute(
      CalendarTool.EventParams("例會", 1000, 2000),
      env,
      IntentLauncher { LaunchResult.NoHandler },
    )
    assertFalse(r.ok)
    assertTrue(r.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, r.reasonCode)
    assertTrue(r.message, "手動" in r.message)
    assertTrue(r.message, "（${ReasonCodes.NO_HANDLER}）" in r.message)
    assertEquals(IntentActions.INSERT, r.spec?.action)
    assertEquals(CalendarTool.EVENTS_CONTENT_URI, r.spec?.dataUri)
  }

  @Test fun calendar_executeUpdateDelete_NoHandler_degradesToManual() {
    // EDIT 委託路徑同理：靜態有 App 但發射無處理者 → 手動降級，不靜默失敗。
    val noHandler = IntentLauncher { LaunchResult.NoHandler }
    val u = CalendarTool.executeUpdate(CalendarTool.UpdateParams(eventId = "7", title = "例會"), env, noHandler)
    assertFalse(u.ok)
    assertTrue(u.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, u.reasonCode)
    assertEquals(IntentActions.EDIT, u.spec?.action)
    assertTrue(u.message, "手動" in u.message)
    val d = CalendarTool.executeDelete(CalendarTool.DeleteParams("9"), env, noHandler)
    assertFalse(d.ok)
    assertTrue(d.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, d.reasonCode)
    assertEquals(IntentActions.EDIT, d.spec?.action)
    assertTrue(d.message, "手動" in d.message)
  }

  @Test fun calendar_staticNoApp_staysUnavailableWhileAlarmDegrades() {
    // 刻意差異釘選（見 CalendarTool KDoc）：建事件靜態無 App 時日曆為
    // UNAVAILABLE（手動降級仍需日曆 App 可開），鬧鐘為 DEGRADED（自有提醒不需外部 App）。
    val noApp = env.copy(hasAlarmApp = false, hasCalendarApp = false)
    val cal = CalendarTool.check(noApp)
    assertEquals(Availability.UNAVAILABLE, cal.availability)
    assertEquals(ReasonCodes.NO_HANDLER, cal.reasonCode)
    val alarm = AlarmTool.check(noApp)
    assertEquals(Availability.DEGRADED, alarm.availability)
    assertEquals(ReasonCodes.NO_HANDLER, alarm.reasonCode)
  }

  // ---- e. #15 缺口：calendar end<=begin 負向 ----

  @Test fun calendar_buildInsert_endNotAfterBegin_rejected() {
    // 相等與倒置皆拒（build 側；排序守衛與 parse 側同訊息）。
    for ((begin, end) in listOf(2000L to 2000L, 2000L to 1000L)) {
      try {
        CalendarTool.buildInsertIntent(CalendarTool.EventParams("例會", begin, end))
        fail("expected IllegalArgumentException for begin=$begin end=$end")
      } catch (e: IllegalArgumentException) {
        assertTrue(e.message ?: "", "endTime must be after beginTime" in (e.message ?: ""))
      }
    }
  }

  @Test fun calendar_parseInsert_endNotAfterBegin_rejected() {
    // 相等與倒置皆拒（parse 側；禁零時長或倒置猜測建事件）。
    for ((begin, end) in listOf(2000L to 2000L, 2000L to 1000L)) {
      try {
        CalendarTool.parseInsertParams(
          mapOf(
            CalendarTool.EXTRA_EVENT_TITLE to IntentExtra.Str("例會"),
            CalendarTool.EXTRA_BEGIN_TIME to IntentExtra.LongVal(begin),
            CalendarTool.EXTRA_END_TIME to IntentExtra.LongVal(end),
          ),
        )
        fail("expected IllegalArgumentException for begin=$begin end=$end")
      } catch (e: IllegalArgumentException) {
        assertTrue(e.message ?: "", "endTime must be after beginTime" in (e.message ?: ""))
      }
    }
  }

  @Test fun calendar_buildEdit_endEqualBegin_rejected() {
    // edit 雙端皆給時相等亦拒（倒置已由 IntentTypedD2Test 鎖定，此處補相等）。
    try {
      CalendarTool.buildEditIntent(
        CalendarTool.UpdateParams(eventId = "42", beginTimeMillis = 2000L, endTimeMillis = 2000L),
      )
      fail("expected IllegalArgumentException for end==begin")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message ?: "", "endTime must be after beginTime" in (e.message ?: ""))
    }
  }

  // ---- f. Phase1 fail-closed：builder 非法經 execute 收斂為 INTERNAL，不穿透 ----

  @Test fun alarm_execute_invalidParams_convergesToInternal() {
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val longMsg = "x".repeat(AlarmTool.MAX_MESSAGE_LEN + 1)
    for (p in listOf(
      AlarmTool.Params(25, 0),
      AlarmTool.Params(7, 60),
      AlarmTool.Params(7, 30, longMsg),
    )) {
      called = false
      val r = AlarmTool.execute(p, env, launcher)
      assertFalse(r.ok)
      assertFalse(r.usedFallback)
      assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
      assertNull(r.spec)
      assertTrue(r.message.contains(ReasonCodes.INTERNAL))
      assertFalse("build 失敗不得回顯超長原文", r.message.contains(longMsg))
      assertFalse("builder 非法不得調用 launcher", called)
    }
  }

  @Test fun calendar_execute_invalidParams_convergesToInternal() {
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val longTitle = "t".repeat(CalendarTool.MAX_TITLE_LEN + 1)
    for (p in listOf(
      CalendarTool.EventParams("", 1000, 2000),
      CalendarTool.EventParams("例會", -1, 2000),
      CalendarTool.EventParams("例會", 0, Long.MAX_VALUE),
      CalendarTool.EventParams("例會", 2000, 2000),
      CalendarTool.EventParams(longTitle, 1000, 2000),
    )) {
      called = false
      val r = CalendarTool.execute(p, env, launcher)
      assertFalse(r.ok)
      assertFalse(r.usedFallback)
      assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
      assertNull(r.spec)
      assertTrue(r.message.contains(ReasonCodes.INTERNAL))
      assertFalse("build 失敗不得調用 launcher", called)
    }
    // 超長標題不得回顯原文（PII/超大回顯防禦）。
    val rLong = CalendarTool.execute(CalendarTool.EventParams(longTitle, 1000, 2000), env, launcher)
    assertFalse(rLong.message.contains(longTitle))
  }

  @Test fun calendar_executeUpdateDelete_invalid_convergesToInternal() {
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val badUpdate = CalendarTool.executeUpdate(
      CalendarTool.UpdateParams(eventId = "7", beginTimeMillis = 2000L, endTimeMillis = 2000L),
      env, launcher,
    )
    assertFalse(badUpdate.ok)
    assertFalse(badUpdate.usedFallback)
    assertEquals(ReasonCodes.INTERNAL, badUpdate.reasonCode)
    assertNull(badUpdate.spec)
    assertTrue(badUpdate.message.contains(ReasonCodes.INTERNAL))
    for (badId in listOf("", "1/2", "../0", "abc")) {
      for ((label, r) in listOf(
        "update" to CalendarTool.executeUpdate(CalendarTool.UpdateParams(eventId = badId), env, launcher),
        "delete" to CalendarTool.executeDelete(CalendarTool.DeleteParams(badId), env, launcher),
      )) {
        assertFalse("$label badId=$badId", r.ok)
        assertFalse("$label badId=$badId", r.usedFallback)
        assertEquals("$label badId=$badId", ReasonCodes.INTERNAL, r.reasonCode)
        assertNull("$label badId=$badId", r.spec)
        assertTrue("$label badId=$badId", r.message.contains(ReasonCodes.INTERNAL))
      }
      assertFalse("非法 eventId 不得調用 launcher：$badId", called)
    }
    // 無 App pending 路徑非法 eventId 同樣收斂為 INTERNAL（程式已修，補釘選）。
    val noApp = env.copy(hasCalendarApp = false)
    for (badId in listOf("", "1/2", "abc")) {
      val u = CalendarTool.executeUpdate(CalendarTool.UpdateParams(eventId = badId), noApp, launcher)
      assertFalse(u.ok)
      assertEquals(ReasonCodes.INTERNAL, u.reasonCode)
      assertNull(u.spec)
      val d = CalendarTool.executeDelete(CalendarTool.DeleteParams(badId), noApp, launcher)
      assertFalse(d.ok)
      assertEquals(ReasonCodes.INTERNAL, d.reasonCode)
      assertNull(d.spec)
    }
  }

  @Test fun alarm_parse_messageTooLong_rejected() {
    try {
      AlarmTool.parseParams(
        mapOf(
          IntentActions.EXTRA_ALARM_HOUR to IntentExtra.IntVal(7),
          IntentActions.EXTRA_ALARM_MINUTES to IntentExtra.IntVal(30),
          IntentActions.EXTRA_ALARM_MESSAGE to IntentExtra.Str("x".repeat(AlarmTool.MAX_MESSAGE_LEN + 1)),
        ),
      )
      fail("expected IllegalArgumentException for too-long message")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message ?: "", "too long" in (e.message ?: ""))
    }
  }

  @Test fun calendar_parseInsert_bounds_rejected() {
    // 負值 / 超長 / 超時長一律拒；各案斷言各自守衛子串，不用萬能非空斷言。
    val cases = listOf(
      mapOf(
        CalendarTool.EXTRA_EVENT_TITLE to IntentExtra.Str("例會"),
        CalendarTool.EXTRA_BEGIN_TIME to IntentExtra.LongVal(-1L),
        CalendarTool.EXTRA_END_TIME to IntentExtra.LongVal(2000L),
      ) to "beginTime must be >= 0",
      mapOf(
        CalendarTool.EXTRA_EVENT_TITLE to IntentExtra.Str("t".repeat(CalendarTool.MAX_TITLE_LEN + 1)),
        CalendarTool.EXTRA_BEGIN_TIME to IntentExtra.LongVal(1000L),
        CalendarTool.EXTRA_END_TIME to IntentExtra.LongVal(2000L),
      ) to "title too long",
      mapOf(
        CalendarTool.EXTRA_EVENT_TITLE to IntentExtra.Str("例會"),
        CalendarTool.EXTRA_BEGIN_TIME to IntentExtra.LongVal(0L),
        CalendarTool.EXTRA_END_TIME to IntentExtra.LongVal(Long.MAX_VALUE),
      ) to "duration too long",
    )
    for ((extras, expected) in cases) {
      try {
        CalendarTool.parseInsertParams(extras)
        fail("expected IllegalArgumentException for $extras")
      } catch (e: IllegalArgumentException) {
        assertTrue(e.message ?: "", expected in (e.message ?: ""))
      }
    }
  }

  @Test fun alarm_parse_optionalMistypeMatrix_throwsExplicit() {
    // 選填鍵錯型矩陣：MESSAGE IntVal / SKIP_UI 已測，此處補 MESSAGE 錯型。
    try {
      AlarmTool.parseParams(
        mapOf(
          IntentActions.EXTRA_ALARM_HOUR to IntentExtra.IntVal(7),
          IntentActions.EXTRA_ALARM_MINUTES to IntentExtra.IntVal(30),
          IntentActions.EXTRA_ALARM_MESSAGE to IntentExtra.IntVal(1),
        ),
      )
      fail("expected IllegalStateException for mistyped MESSAGE")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", IntentActions.EXTRA_ALARM_MESSAGE in (e.message ?: ""))
    }
    try {
      CalendarTool.parseInsertParams(
        mapOf(
          CalendarTool.EXTRA_EVENT_TITLE to IntentExtra.LongVal(1L),
          CalendarTool.EXTRA_BEGIN_TIME to IntentExtra.LongVal(1000L),
          CalendarTool.EXTRA_END_TIME to IntentExtra.LongVal(2000L),
        ),
      )
      fail("expected IllegalStateException for mistyped title")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", CalendarTool.EXTRA_EVENT_TITLE in (e.message ?: ""))
    }
  }

  @Test fun alarm_blankMessage_normalizesToEmpty() {
    // build 丟棄全空白（isNotBlank），parse 缺席回 ""：刻意正規化，釘選行為。
    val params = AlarmTool.Params(7, 30, "   ")
    val roundTripped = AlarmTool.parseParams(AlarmTool.buildSetAlarmIntent(params).extras)
    assertEquals("", roundTripped.message)
    // edit 側同理：空白 title 不攜帶（takeIf isNotBlank），長度守衛一致。
    val editSpec = CalendarTool.buildEditIntent(CalendarTool.UpdateParams(eventId = "7", title = "   "))
    assertFalse(editSpec.extras.containsKey(CalendarTool.EXTRA_EVENT_TITLE))
  }

  @Test fun launchCatching_cancellation_reraises() {
    val cancelling = IntentLauncher { throw kotlin.coroutines.cancellation.CancellationException("cancel") }
    try {
      cancelling.launchCatching(IntentSpec(action = IntentActions.SET_ALARM))
      fail("expected CancellationException to propagate")
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      // 預期：取消直接外洩，不收斂為 Internal。
    }
  }

  @Test fun launchInternal_doesNotLeakCauseMessage() {
    // INTERNAL 僅附 cause 類名，不含 message/PII（假 key 不得外洩）。
    val secret = "sk-fake-secret-12345 /data/data/secret"
    val r = IntentLauncher { throw SecurityException(secret) }
      .launchCatching(IntentSpec(action = IntentActions.SET_ALARM))
      .toToolResult(IntentSpec(action = IntentActions.SET_ALARM), "設定鬧鐘")
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
    assertFalse(r.message.contains(secret))
    assertFalse(r.message.contains("sk-fake-secret"))
    assertTrue(r.message.contains("SecurityException"))
  }

  @Test fun calendar_deletePendingSpec_neverLaunched() {
    // 無 App 時 DELETE 待寫 spec 禁經 launcher：launcher 若被調用即失敗。
    var called = false
    val noApp = env.copy(hasCalendarApp = false)
    val r = CalendarTool.executeDelete(
      CalendarTool.DeleteParams("9"), noApp,
      IntentLauncher { called = true; LaunchResult.Success },
    )
    assertFalse(called)
    assertFalse(r.ok)
    assertTrue(r.usedFallback)
    assertEquals(ReasonCodes.NEED_PERMISSION, r.reasonCode)
    assertEquals(IntentActions.DELETE, r.spec?.action)
    assertEquals("${CalendarTool.EVENTS_CONTENT_URI}/9", r.spec?.dataUri)
  }

  @Test fun calendar_executeUpdate_noApp_returnsPendingSpec() {
    // 無 App 時更新走待寫路徑：ok=false + usedFallback=true + NEED_PERMISSION，
    // spec 攜帶 EDIT + 目標 URI + 變更欄位，且不調用 launcher。
    var called = false
    val noApp = env.copy(hasCalendarApp = false)
    val r = CalendarTool.executeUpdate(
      CalendarTool.UpdateParams(eventId = "7", title = "例會"), noApp,
      IntentLauncher { called = true; LaunchResult.Success },
    )
    assertFalse(called)
    assertFalse(r.ok)
    assertTrue(r.usedFallback)
    assertEquals(ReasonCodes.NEED_PERMISSION, r.reasonCode)
    assertEquals(IntentActions.EDIT, r.spec?.action)
    assertEquals("${CalendarTool.EVENTS_CONTENT_URI}/7", r.spec?.dataUri)
  }

  @Test fun execute_staticDegradedUnavailable_returnsNullSpec() {
    // 靜態 DEGRADED（鬧鐘無 App）/ UNAVAILABLE（日曆無 App/USER_DISABLED）
    // 經 execute 收斂為 spec=null，不攜帶嘗試過的 spec。
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val noApp = env.copy(hasAlarmApp = false, hasCalendarApp = false)
    val a = AlarmTool.execute(AlarmTool.Params(7, 30), noApp, launcher)
    assertFalse(a.ok)
    assertTrue(a.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, a.reasonCode)
    assertNull(a.spec)
    val c = CalendarTool.execute(CalendarTool.EventParams("例會", 1000, 2000), noApp, launcher)
    assertFalse(c.ok)
    assertFalse(c.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, c.reasonCode)
    assertNull(c.spec)
    assertFalse("靜態不可執行不得調用 launcher", called)
  }

  // ---- g. #15 對齊：Volume/Navigation builder 非法經 execute 收斂為 INTERNAL ----

  @Test fun volume_execute_success_pinsAnchoredMessage() {
    // 成功路徑為佔位錨定語義（見 VolumeTool KDoc）：ok=true 表示操作已錨定，
    // 訊息必須逐字明載「未接線」，呼叫方不得誤把 ok=true 當成音量已改變。
    val r = VolumeTool.execute(VolumeTool.Params(), env, IntentLauncher { LaunchResult.Success })
    assertTrue(r.ok)
    assertFalse(r.usedFallback)
    assertEquals(IntentActions.SOUND_SETTINGS, r.spec?.action)
    assertEquals(
      "stream=${VolumeTool.AudioStream.MUSIC.sdkValue} " +
        "dir=${VolumeTool.Direction.RAISE}（已錨定，端側 AudioManager 未接線）",
      r.message,
    )
  }

  @Test fun volume_execute_invalidParams_convergesToInternal() {
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    for (p in listOf(
      VolumeTool.Params(direction = VolumeTool.Direction.SET), // index 缺席
      VolumeTool.Params(direction = VolumeTool.Direction.SET, index = -1),
    )) {
      called = false
      val r = VolumeTool.execute(p, env, launcher)
      assertFalse(r.ok)
      assertFalse(r.usedFallback)
      assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
      assertNull(r.spec)
      assertTrue(r.message.contains(ReasonCodes.INTERNAL))
      assertFalse("builder 非法不得調用 launcher", called)
    }
  }

  @Test fun navigation_execute_invalidParams_convergesToInternal() {
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    for (p in listOf(
      NavigationTool.Params(latitude = 91.0, longitude = 0.0), // 緯度越界
      NavigationTool.Params(latitude = 1.0, longitude = null), // 單側座標
      NavigationTool.Params(latitude = Double.NaN, longitude = 1.0), // 非有限值
    )) {
      called = false
      val r = NavigationTool.execute(p, env, launcher)
      assertFalse(r.ok)
      assertFalse(r.usedFallback)
      assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
      assertNull(r.spec)
      assertTrue(r.message.contains(ReasonCodes.INTERNAL))
      assertFalse("builder 非法不得調用 launcher", called)
    }
  }

  // ---- h. #15 對齊：Music NoHandler 走降級話術（與 Alarm/Calendar 同形） ----

  @Test fun music_executeNoHandler_degradesToWebPlayer() {
    // 靜態 DEGRADED（無播放器）與發現式缺席（有播放器但發射無處理者）同語義：
    // ok=false + usedFallback=true + NO_HANDLER + 網頁播放器話術（spec 保留）。
    val noHandler = IntentLauncher { LaunchResult.NoHandler }
    val degraded = MusicTool.execute(MusicTool.Params("輕音樂"), env.copy(hasMusicApp = false), noHandler)
    assertFalse(degraded.ok)
    assertTrue(degraded.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, degraded.reasonCode)
    assertTrue(degraded.message, "網頁播放器" in degraded.message)
    assertEquals(IntentActions.VIEW, degraded.spec?.action)

    val discovered = MusicTool.execute(MusicTool.Params("輕音樂"), env, noHandler)
    assertFalse(discovered.ok)
    assertTrue(discovered.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, discovered.reasonCode)
    assertTrue(discovered.message, "網頁播放器" in discovered.message)
  }

  @Test fun music_controlNoHandler_usesFallbackControlBranch() {
    // MEDIA_BUTTON 為 broadcast 語義、resolve-first launcher 恆回 NoHandler（已記錄的
    // 端側未接線缺口）：話術必須取 [fallback] 控制分支的**完整三段計畫**，不得走
    // generic toToolResult 的無計畫尾段話術；控制語意下「播放 search」非替代，
    // FallbackSpec.alternative 留 null，spec（MEDIA_BUTTON + keyCode）保留。
    val r = MusicTool.execute(
      MusicTool.Params(control = MusicTool.Control.PAUSE), env, IntentLauncher { LaunchResult.NoHandler },
    )
    assertFalse(r.ok)
    assertTrue(r.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, r.reasonCode)
    assertEquals(IntentActions.MEDIA_BUTTON, r.spec?.action)
    // spec（MEDIA_BUTTON + keyCode）保留：extras 按型別攜帶控制鍵碼。
    assertEquals(
      IntentExtra.IntVal(MusicTool.CONTROL_KEY_CODES.getValue(MusicTool.Control.PAUSE)),
      r.spec?.extras?.get("keyCode"),
    )
    // 話術三段形狀（FallbackSpec 模板：做不到 X → 可替代 Y → 需要你做 Z）。
    assertTrue(r.message, r.message.startsWith("做不到控制播放（${ReasonCodes.NO_HANDLER}）"))
    assertTrue(r.message, "→ 可替代螢幕/通知欄的播放控制" in r.message)
    assertTrue(r.message, "→ 需要你手動按暫停或下一首" in r.message)
    // fallback spec 本體：控制分支 alternative=null（話術來源即此分支，非 dev 途徑）。
    assertNull(MusicTool.fallback(MusicTool.Params(control = MusicTool.Control.PAUSE), env).alternative)
  }

  @Test fun navigation_executeNoHandler_degradesToWeb() {
    // 發現式缺席（靜態有地圖但發射無處理者）與靜態 DEGRADED 同語義：
    // ok=false + usedFallback=true + NO_HANDLER + 網頁地圖話術（spec 保留）。
    val noHandler = IntentLauncher { LaunchResult.NoHandler }
    val discovered = NavigationTool.execute(NavigationTool.Params(query = "夜市"), env, noHandler)
    assertFalse(discovered.ok)
    assertTrue(discovered.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, discovered.reasonCode)
    assertTrue(discovered.message, "網頁地圖" in discovered.message)
    assertEquals(IntentActions.VIEW, discovered.spec?.action)

    val degraded = NavigationTool.execute(
      NavigationTool.Params(query = "夜市"), env.copy(hasMapApp = false), noHandler,
    )
    assertFalse(degraded.ok)
    assertTrue(degraded.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, degraded.reasonCode)
    assertTrue(degraded.message, "網頁地圖" in degraded.message)
  }

  // ---- i. #15 對齊：Calendar Provider 門禁分支涵蓋 ----

  @Test fun calendar_checkQuery_readGrantedMatrix() {
    val granted = CalendarTool.checkQuery(env.copy(calendarReadGranted = true))
    assertEquals(Availability.NATIVE, granted.availability)
    val denied = CalendarTool.checkQuery(env.copy(calendarReadGranted = false))
    assertEquals(Availability.DEGRADED, denied.availability)
    assertEquals(ReasonCodes.NEED_PERMISSION, denied.reasonCode)
  }

  @Test fun calendar_checkUpdate_noApp_writeGrantedMatrix() {
    val noApp = env.copy(hasCalendarApp = false)
    val writeGranted = CalendarTool.checkUpdate(noApp.copy(calendarWriteGranted = true))
    assertEquals(Availability.DEGRADED, writeGranted.availability)
    assertEquals(ReasonCodes.NEED_PERMISSION, writeGranted.reasonCode)
    val writeDenied = CalendarTool.checkUpdate(noApp.copy(calendarWriteGranted = false))
    assertEquals(Availability.DEGRADED, writeDenied.availability)
    assertEquals(ReasonCodes.NEED_PERMISSION, writeDenied.reasonCode)
  }

  @Test fun calendar_noApp_needPermissionMessage_matchesFallback() {
    // 無 App 的 NEED_PERMISSION 話術應與 NoHandler 用手動降級話術一致，
    // 且必須含「手動」與原因碼（直接斷言，避免只與 fallback 相對比較）。
    val noApp = env.copy(hasCalendarApp = false)
    val u = CalendarTool.executeUpdate(
      CalendarTool.UpdateParams(eventId = "7", title = "例會"), noApp,
      IntentLauncher { LaunchResult.Success },
    )
    assertEquals(
      CalendarTool.fallbackUpdate(CalendarTool.UpdateParams(eventId = "7", title = "例會"), ReasonCodes.NEED_PERMISSION).userMessage,
      u.message,
    )
    assertTrue(u.message, "手動" in u.message)
    assertTrue(u.message, "（${ReasonCodes.NEED_PERMISSION}）" in u.message)
    val d = CalendarTool.executeDelete(CalendarTool.DeleteParams("9"), noApp, IntentLauncher { LaunchResult.Success })
    assertEquals(
      CalendarTool.fallbackDelete(CalendarTool.DeleteParams("9"), ReasonCodes.NEED_PERMISSION).userMessage,
      d.message,
    )
    assertTrue(d.message, "手動" in d.message)
    assertTrue(d.message, "（${ReasonCodes.NEED_PERMISSION}）" in d.message)
  }

  @Test fun calendar_checkDelete_delegatesToCheckUpdate() {
    val noApp = env.copy(hasCalendarApp = false)
    assertEquals(CalendarTool.checkUpdate(noApp), CalendarTool.checkDelete(noApp))
    assertEquals(CalendarTool.checkUpdate(env), CalendarTool.checkDelete(env))
  }

  @Test fun execute_userDisabled_returnsDisabledNotNoHandler() {
    // USER_DISABLED 由 execute 早期回傳，不得經 fallback 取 NO_HANDLER 話術；
    // launcher 不得被調用，spec 為 null。
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val disabled = env.copy(userEnabled = false)
    val a = AlarmTool.execute(AlarmTool.Params(7, 30), disabled, launcher)
    assertFalse(a.ok)
    assertFalse(a.usedFallback)
    assertNull(a.spec)
    assertEquals(ReasonCodes.USER_DISABLED, a.reasonCode)
    assertTrue(a.message.contains(ReasonCodes.USER_DISABLED))
    val c = CalendarTool.execute(CalendarTool.EventParams("例會", 1000, 2000), disabled, launcher)
    assertFalse(c.ok)
    assertFalse(c.usedFallback)
    assertNull(c.spec)
    assertEquals(ReasonCodes.USER_DISABLED, c.reasonCode)
    val u = CalendarTool.executeUpdate(CalendarTool.UpdateParams(eventId = "7"), disabled, launcher)
    assertFalse(u.ok)
    assertEquals(ReasonCodes.USER_DISABLED, u.reasonCode)
    assertNull(u.spec)
    val d = CalendarTool.executeDelete(CalendarTool.DeleteParams("7"), disabled, launcher)
    assertFalse(d.ok)
    assertEquals(ReasonCodes.USER_DISABLED, d.reasonCode)
    assertNull(d.spec)
    // USER_DISABLED 先於參數校驗（P2 修補契約）：禁用時非法參數亦回 USER_DISABLED
    // 而非 INTERNAL（能力問題先於參數問題）。
    val aBad = AlarmTool.execute(AlarmTool.Params(25, 0), disabled, launcher)
    assertEquals(ReasonCodes.USER_DISABLED, aBad.reasonCode)
    assertNull(aBad.spec)
    val cBad = CalendarTool.execute(CalendarTool.EventParams("", 1000, 2000), disabled, launcher)
    assertEquals(ReasonCodes.USER_DISABLED, cBad.reasonCode)
    assertNull(cBad.spec)
    val nBad = NavigationTool.execute(NavigationTool.Params(latitude = 91.0, longitude = 0.0), disabled, launcher)
    assertEquals(ReasonCodes.USER_DISABLED, nBad.reasonCode)
    assertNull(nBad.spec)
    assertFalse("USER_DISABLED 不得調用 launcher", called)
  }

  // ---- i. Phase2 收斂與契約釘選（cause 後綴、registry 拒絕、MIME、空白澄清、
  //        DELETE 發射 allowlist、長度邊界）----

  @Test fun guardedToolBuild_illegalArgument_mapsCauseTaggedInternal() {
    val failure = ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到測試（INTERNAL）")
    val bad = "secret-input"
    val guarded = guardedToolBuild(failure) {
      throw IllegalArgumentException("bad value: $bad")
    }
    val r = (guarded as Guarded.Internal).result
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
    // 與 launch 路徑對稱：追加 cause 類名，不得回顯 builder 訊息體。
    assertEquals("做不到測試（INTERNAL）[IllegalArgumentException]", r.message)
    assertFalse("builder 訊息不得回顯", r.message.contains("secret-input"))
  }

  @Test fun guardedToolBuild_illegalState_mapsCauseTaggedInternal() {
    // ISE 分支（非取消）同樣收斂為 INTERNAL + 類名後綴——guard KDoc 承諾的
    // 契約此前無直接釘選。
    val failure = ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到測試（INTERNAL）")
    val guarded = guardedToolBuild(failure) { throw IllegalStateException("state boom") }
    val r = (guarded as Guarded.Internal).result
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
    assertEquals("做不到測試（INTERNAL）[IllegalStateException]", r.message)
  }

  @Test fun guardedToolBuild_cancellation_reraises() {
    val failure = ToolResult(false, false, null, ReasonCodes.INTERNAL, "x（INTERNAL）")
    try {
      guardedToolBuild(failure) { throw kotlin.coroutines.cancellation.CancellationException("cancel") }
      fail("取消語義不得吞成 INTERNAL")
    } catch (_: kotlin.coroutines.cancellation.CancellationException) {
      // 照拋即契約。
    }
  }

  @Test fun calendar_specMime_matchesQueriesEntries() {
    // 顯式 MIME 與 manifest <queries> 的 mimeType 條目精確對應（P2 fix：
    // typeless content URI 依賴 PM 查詢時的 provider-resolve，顯式者確定）。
    assertEquals(
      CalendarTool.MIME_EVENT_DIR,
      CalendarTool.buildInsertIntent(CalendarTool.EventParams("例會", 1000, 2000)).mimeType,
    )
    assertEquals(
      CalendarTool.MIME_EVENT_ITEM,
      CalendarTool.buildEditIntent(CalendarTool.UpdateParams(eventId = "7", title = "t")).mimeType,
    )
    assertEquals(
      CalendarTool.MIME_EVENT_ITEM,
      CalendarTool.executeDelete(
        CalendarTool.DeleteParams("7"), env, IntentLauncher { LaunchResult.Success },
      ).spec?.mimeType,
    )
    // 無 App pending 路徑的 EDIT spec 同帶 item MIME（契約一致）。
    assertEquals(
      CalendarTool.MIME_EVENT_ITEM,
      CalendarTool.executeUpdate(
        CalendarTool.UpdateParams(eventId = "7", title = "t"), env.copy(hasCalendarApp = false),
        IntentLauncher { LaunchResult.Success },
      ).spec?.mimeType,
    )
  }

  @Test fun calendar_locationDescription_lengthGuards_rejected() {
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val longLoc = "l".repeat(CalendarTool.MAX_LOCATION_LEN + 1)
    val longDesc = "d".repeat(CalendarTool.MAX_DESCRIPTION_LEN + 1)
    for (p in listOf(
      CalendarTool.EventParams("例會", 1000, 2000, location = longLoc),
      CalendarTool.EventParams("例會", 1000, 2000, description = longDesc),
    )) {
      called = false
      val r = CalendarTool.execute(p, env, launcher)
      assertFalse(r.ok)
      assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
      assertNull(r.spec)
      assertFalse("location/description 長度違規不得調用 launcher", called)
    }
  }

  @Test fun calendar_lengths_atBoundary_inclusive() {
    val p = CalendarTool.EventParams(
      "t".repeat(CalendarTool.MAX_TITLE_LEN), 1000, 2000,
      location = "l".repeat(CalendarTool.MAX_LOCATION_LEN),
      description = "d".repeat(CalendarTool.MAX_DESCRIPTION_LEN),
    )
    val spec = CalendarTool.buildInsertIntent(p)
    assertEquals(IntentActions.INSERT, spec.action)
    val back = CalendarTool.parseInsertParams(spec.extras)
    assertEquals(CalendarTool.MAX_TITLE_LEN, back.title.length)
    assertEquals(CalendarTool.MAX_LOCATION_LEN, back.location.length)
    assertEquals(CalendarTool.MAX_DESCRIPTION_LEN, back.description.length)
  }

  @Test fun alarm_messageLen_atBoundary_inclusive() {
    val msg = "m".repeat(AlarmTool.MAX_MESSAGE_LEN)
    val spec = AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30, msg))
    val parsed = AlarmTool.parseParams(spec.extras)
    assertEquals(AlarmTool.MAX_MESSAGE_LEN, parsed.message.length)
    assertEquals(msg.length, parsed.message.length)
  }

  @Test fun navigation_allBlankParams_needsClarification_notLaunched() {
    // 空白目的地（query/label/座標皆缺）回 NEED_CLARIFICATION，不構建
    // `geo:0,0?q=` 無意義 intent（#19 猜測執行禁令；與 MusicTool 同語義）。
    var called = false
    val r = NavigationTool.execute(
      NavigationTool.Params(), env, IntentLauncher { called = true; LaunchResult.Success },
    )
    assertFalse(r.ok)
    assertFalse(r.usedFallback)
    assertEquals(ReasonCodes.NEED_CLARIFICATION, r.reasonCode)
    assertNull(r.spec)
    assertFalse("空白目的地不得發射 intent", called)
  }

  @Test fun launcher_deleteSpec_rejectsWithInternal_evenWhenResolved() {
    // DELETE 待寫 spec 禁經 launcher 發射（CalendarTool/manifest 契約機械化）：
    // 即使 handler 已 resolve，也在 resolve 之前以 Internal fail-closed 拒絕。
    val app = appContext()
    addResolve(
      app,
      Intent(IntentActions.DELETE).setDataAndType(
        Uri.parse("content://com.android.calendar/events/7"),
        CalendarTool.MIME_EVENT_ITEM,
      ),
    )
    val r = AndroidIntentLauncher(app).launch(
      IntentSpec(
        action = IntentActions.DELETE,
        dataUri = "content://com.android.calendar/events/7",
        mimeType = CalendarTool.MIME_EVENT_ITEM,
      ),
    )
    // 斷言 allowlist 的標記訊息（而非只斷言任何 Internal），否則 guard 移除後
    // 仍可能碰巧由 toIntent/resolve 拋錯路徑給出 Internal 而假綠。
    assertTrue("DELETE spec 禁發射：回 Internal", r is LaunchResult.Internal)
    assertTrue(
      "Internal 應來自進入 resolve 前的 DELETE allowlist guard",
      (r as LaunchResult.Internal).cause?.message?.contains("DELETE spec must not be launched") == true,
    )
  }

  // ---- j. fail-closed hoist：驗證先於能力分支（P2 修復釘選） ----

  @Test fun alarm_execute_degraded_stillValidates_failClosed() {
    // 靜態 DEGRADED（無系統鬧鐘 App）亦先過 builder fail-closed 校驗：
    // hour=25/minute=60/超長 message → INTERNAL，不取 fallback 話術（不回顯
    // 超長原文），launcher 不被呼叫。
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val noApp = env.copy(hasAlarmApp = false)
    val longMsg = "x".repeat(AlarmTool.MAX_MESSAGE_LEN + 1)
    for (p in listOf(
      AlarmTool.Params(25, 0),
      AlarmTool.Params(7, 60),
      AlarmTool.Params(7, 30, longMsg),
    )) {
      called = false
      val r = AlarmTool.execute(p, noApp, launcher)
      assertFalse(r.ok)
      assertFalse(r.usedFallback)
      assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
      assertNull(r.spec)
      assertTrue(r.message.contains(ReasonCodes.INTERNAL))
      assertFalse("超長 message 不得回顯", r.message.contains(longMsg))
      assertFalse("DEGRADED 校驗失敗不得調用 launcher", called)
    }
  }

  @Test fun alarm_execute_degraded_validKeepsFallbackCopyWithFullLabel() {
    // 合法 500 字 message 在 DEGRADED 路徑完整入話術（刻意不做 take 截斷），
    // fallback 形狀（spec=null、usedFallback=true、NO_HANDLER）不因 hoist 漂移。
    val msg = "m".repeat(AlarmTool.MAX_MESSAGE_LEN)
    val r = AlarmTool.execute(
      AlarmTool.Params(7, 30, msg), env.copy(hasAlarmApp = false),
      IntentLauncher { LaunchResult.Success },
    )
    assertFalse(r.ok)
    assertTrue(r.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, r.reasonCode)
    assertNull(r.spec)
    assertTrue(r.message, "「$msg」" in r.message)
  }

  @Test fun calendar_execute_noApp_invalidParams_convergesToInternal() {
    // 同形修補釘選：靜態 no-app（UNAVAILABLE）亦先過 builder 校驗——非法
    // title/location → INTERNAL（不是 NO_HANDLER 早期回傳），launcher 不被呼叫。
    var called = false
    val launcher = IntentLauncher { called = true; LaunchResult.Success }
    val noApp = env.copy(hasCalendarApp = false)
    val longTitle = "t".repeat(CalendarTool.MAX_TITLE_LEN + 1)
    val longLoc = "l".repeat(CalendarTool.MAX_LOCATION_LEN + 1)
    for (p in listOf(
      CalendarTool.EventParams("", 1000, 2000),
      CalendarTool.EventParams(longTitle, 1000, 2000),
      CalendarTool.EventParams("例會", 1000, 2000, location = longLoc),
    )) {
      called = false
      val r = CalendarTool.execute(p, noApp, launcher)
      assertFalse(r.ok)
      assertFalse(r.usedFallback)
      assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
      assertNull(r.spec)
      assertTrue(r.message.contains(ReasonCodes.INTERNAL))
      assertFalse("超長 title 不得回顯", r.message.contains(longTitle))
      assertFalse("校驗失敗不得調用 launcher", called)
    }
  }

  @Test fun calendar_eventIdEcho_cappedInFallbackCopy() {
    // eventId 缺席守衛是數字形狀（無長度上限）：話術回顯經 MAX_EVENT_ID_ECHO_LEN
    // 截斷（spec/URI 守衛仍用完整值——DELETE 待寫 URI 以完整 id 鎖定）。
    val big = "9".repeat(1024)
    val cap = CalendarTool.MAX_EVENT_ID_ECHO_LEN
    val u = CalendarTool.executeUpdate(
      CalendarTool.UpdateParams(eventId = big, title = "例會"), env,
      IntentLauncher { LaunchResult.NoHandler },
    )
    assertTrue(u.message, "「${big.take(cap)}」" in u.message)
    assertFalse(u.message, big.take(cap + 1) in u.message)
    val noApp = env.copy(hasCalendarApp = false)
    val d = CalendarTool.executeDelete(
      CalendarTool.DeleteParams(big), noApp, IntentLauncher { LaunchResult.Success },
    )
    assertTrue(d.message, "「${big.take(cap)}」" in d.message)
    assertFalse(d.message, big.take(cap + 1) in d.message)
    assertEquals(IntentActions.DELETE, d.spec?.action)
    assertEquals("${CalendarTool.EVENTS_CONTENT_URI}/$big", d.spec?.dataUri)
  }

  @Test fun navigation_queryLabelLength_bounds() {
    // 500 合法（builder 成功入 URI）；501 → builder IAE（geo/web 兩側同守衛）；
    // 經 execute 一律 INTERNAL 且不觸 launcher。
    val ok = NavigationTool.buildGeoIntent(
      NavigationTool.Params(query = "q".repeat(NavigationTool.MAX_QUERY_LEN)),
    )
    assertTrue(ok.dataUri!!.startsWith("geo:0,0?q="))
    val okLabel = NavigationTool.buildGeoIntent(
      NavigationTool.Params(latitude = 1.0, longitude = 2.0, label = "l".repeat(NavigationTool.MAX_QUERY_LEN)),
    )
    assertTrue(okLabel.dataUri!!.startsWith("geo:1.0,2.0?q="))
    val okWeb = NavigationTool.buildWebIntent(
      NavigationTool.Params(query = "q".repeat(NavigationTool.MAX_QUERY_LEN)),
    )
    assertTrue(okWeb.dataUri!!.startsWith("https://www.openstreetmap.org/search?query="))
    val overQuery = "q".repeat(NavigationTool.MAX_QUERY_LEN + 1)
    val overLabel = "l".repeat(NavigationTool.MAX_QUERY_LEN + 1)
    for ((shape, p) in listOf(
      "query" to NavigationTool.Params(query = overQuery),
      "label" to NavigationTool.Params(latitude = 1.0, longitude = 2.0, label = overLabel),
    )) {
      for ((side, build) in listOf(
        "geo" to { NavigationTool.buildGeoIntent(p) },
        "web" to { NavigationTool.buildWebIntent(p) },
      )) {
        try {
          build()
          fail("expected IllegalArgumentException for over-long $shape ($side)")
        } catch (e: IllegalArgumentException) {
          assertTrue(e.message ?: "", "too long" in (e.message ?: ""))
        }
      }
    }
    var called = false
    val r = NavigationTool.execute(
      NavigationTool.Params(query = overQuery), env,
      IntentLauncher { called = true; LaunchResult.Success },
    )
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
    assertNull(r.spec)
    assertFalse("超長 query 不得調用 launcher", called)
    assertFalse("超長 query 不得回顯", r.message.contains("qqqq"))
  }

  @Test fun music_queryLength_bounds() {
    // 500 合法；501 builder IAE；經 execute 一律 INTERNAL 且不觸 launcher。
    val ok = MusicTool.buildPlayIntent(MusicTool.Params("q".repeat(MusicTool.MAX_QUERY_LEN)))
    assertTrue(ok.dataUri!!.startsWith("https://music.youtube.com/search?q="))
    try {
      MusicTool.buildPlayIntent(MusicTool.Params("q".repeat(MusicTool.MAX_QUERY_LEN + 1)))
      fail("expected IllegalArgumentException for over-long query")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message ?: "", "too long" in (e.message ?: ""))
    }
    var called = false
    val r = MusicTool.execute(
      MusicTool.Params(query = "q".repeat(MusicTool.MAX_QUERY_LEN + 1)), env,
      IntentLauncher { called = true; LaunchResult.Success },
    )
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
    assertNull(r.spec)
    assertFalse("超長 query 不得調用 launcher", called)
    assertFalse("超長 query 不得回顯", r.message.contains("qqqq"))
  }

  // ---- k. P3 增補釘選：fallback 防拋收斂、launcher Internal 分型、防回顯截斷 ----

  @Test fun navigation_fallback_invalidParams_alternativeNull_noThrow() {
    // fallback 是「報告失敗」的路徑，不得反拋打穿呼叫方（此前 buildWebIntent
    // 守衛會穿透）：非法座標 → alternative=null，話術形狀不變，不拋 IAE。
    val f = NavigationTool.fallback(
      NavigationTool.Params(latitude = 91.0, longitude = 0.0), env,
    )
    assertNull(f.alternative)
    assertEquals(ReasonCodes.NO_HANDLER, f.reasonCode)
    // 話術首段釘選（與 music_controlNoHandler 的完整模板 pin 同形）。
    assertTrue(f.userMessage, f.userMessage.startsWith("做不到開啟地圖 App（${ReasonCodes.NO_HANDLER}）"))
    assertTrue(f.userMessage, "網頁地圖" in f.userMessage)
  }

  @Test fun music_fallback_overLongQuery_alternativeNull_noThrow() {
    // 同上：play 分支 alternative 構建失敗（query 超長）降為 null，不反拋。
    val f = MusicTool.fallback(
      MusicTool.Params(query = "q".repeat(MusicTool.MAX_QUERY_LEN + 1)), env,
    )
    assertNull(f.alternative)
    assertEquals(ReasonCodes.NO_HANDLER, f.reasonCode)
    // 話術首段釘選（與 nav fallback 同形；有別於 generic toToolResult 無計畫話術）。
    assertTrue(f.userMessage, f.userMessage.startsWith("做不到開啟播放器（${ReasonCodes.NO_HANDLER}）"))
    assertTrue(f.userMessage, "網頁播放器" in f.userMessage)
  }

  @Test fun guardedFallbackAlternative_convergesOrNull_cancellationReraises() {
    // IAE/ISE（非取消）→ null；CancellationException 照拋（取消語義不得吞）。
    assertNull(
      guardedFallbackAlternative<IntentSpec> {
        throw IllegalArgumentException("builder secret-input")
      },
    )
    assertNull(
      guardedFallbackAlternative<IntentSpec> { throw IllegalStateException("state boom") },
    )
    // 非 IAE/ISE 的 RuntimeException 不得被吞（白名單性質 pin）：穿透即契約。
    try {
      guardedFallbackAlternative<IntentSpec> { throw NoSuchElementException("missing-key") }
      fail("非守衛白名單的 RuntimeException 不得吞成 null")
    } catch (_: NoSuchElementException) {
      // 照拋即契約（白名單僅 IAE/ISE；取消另見下案）。
    }
    try {
      guardedFallbackAlternative<IntentSpec> {
        throw kotlin.coroutines.cancellation.CancellationException("cancel")
      }
      fail("取消語義不得吞成 null")
    } catch (_: kotlin.coroutines.cancellation.CancellationException) {
      // 照拋即契約。
    }
  }

  @Test fun execute_launcherInternal_typingKeepsSpecAndCauseName() {
    // launcher 失敗分型補測：各工具 execute 的 Internal 分支統一形狀——
    // INTERNAL + cause 類名後綴（不回顯例外訊息）、spec 保留（嘗試過的 intent
    // 不靜默丟失）、usedFallback=false（Internal 非降級分支）。
    val secret = "launcher-secret-payload"
    val launcher = IntentLauncher { LaunchResult.Internal(IllegalStateException(secret)) }
    val cases = listOf(
      "alarm" to AlarmTool.execute(AlarmTool.Params(7, 30), env, launcher),
      "calendar-insert" to CalendarTool.execute(CalendarTool.EventParams("例會", 1000, 2000), env, launcher),
      "calendar-update" to CalendarTool.executeUpdate(CalendarTool.UpdateParams(eventId = "7"), env, launcher),
      "music-control" to MusicTool.execute(MusicTool.Params(control = MusicTool.Control.NEXT), env, launcher),
      "music-play" to MusicTool.execute(MusicTool.Params(query = "輕音樂"), env, launcher),
      "navigation" to NavigationTool.execute(NavigationTool.Params(query = "夜市"), env, launcher),
      // launchapp 走 Single→launcher 分支（alias 命中即單包 spec）；Volume 不納入：
      // 其 execute 不經 launcher（錨定語義），無 Internal-launcher 分支。
      "launchapp" to LaunchAppTool.execute(LaunchAppTool.Params("地圖"), env, launcher),
    )
    for ((label, r) in cases) {
      assertFalse(label, r.ok)
      assertFalse(label, r.usedFallback)
      assertEquals(label, ReasonCodes.INTERNAL, r.reasonCode)
      assertTrue("$label spec 應保留", r.spec != null)
      assertTrue("$label 訊息應含 INTERNAL", r.message.contains(ReasonCodes.INTERNAL))
      assertTrue("$label 訊息應含 cause 類名：${r.message}", r.message.contains("[IllegalStateException]"))
      assertFalse("$label 不得回顯例外訊息", r.message.contains(secret))
    }
  }

  @Test fun launchApp_unknown_echoCappedInCopy() {
    // Unknown 分支話術僅回顯前 MAX_QUERY_ECHO_LEN 字元（話術防回顯，非參數
    // 校驗；execute 與 fallback else 分支同形）。
    val long = "x".repeat(1024)
    val cap = LaunchAppTool.MAX_QUERY_ECHO_LEN
    val r = LaunchAppTool.execute(LaunchAppTool.Params(long), env, IntentLauncher { LaunchResult.Success })
    assertEquals(ReasonCodes.NO_HANDLER, r.reasonCode)
    assertTrue(r.message, "「${long.take(cap)}」" in r.message)
    assertFalse(r.message, long.take(cap + 1) in r.message)
    val fb = LaunchAppTool.fallback(LaunchAppTool.Params(long), env)
    assertEquals(ReasonCodes.NO_HANDLER, fb.reasonCode)
    assertTrue(fb.userMessage, "「${long.take(cap)}」" in fb.userMessage)
    assertFalse(fb.userMessage, long.take(cap + 1) in fb.userMessage)
  }
}
