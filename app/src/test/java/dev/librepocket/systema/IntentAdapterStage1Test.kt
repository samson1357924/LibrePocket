package dev.librepocket.systema

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
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
 * - a) SET_ALARM 發送端免 `<uses-permission>`（見 [AlarmTool] KDoc）：manifest
 *   不加宣告（pinned by [manifest_noSetAlarmUsesPermission])，接收端強制要求則
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

  // ---- a/b. manifest 宣告 ----

  @Test fun manifest_main_declaresAlarmCalendarQueries() {
    val manifest = mainManifest()
    assertTrue(manifest.contains("<action android:name=\"android.intent.action.SET_ALARM\" />"))
    assertTrue(manifest.contains("<action android:name=\"android.intent.action.INSERT\" />"))
    assertTrue(manifest.contains("<data android:mimeType=\"vnd.android.cursor.dir/event\" />"))
    assertTrue(manifest.contains("<action android:name=\"android.intent.action.EDIT\" />"))
    assertTrue(manifest.contains("<data android:mimeType=\"vnd.android.cursor.item/event\" />"))
  }

  @Test fun manifest_noSetAlarmUsesPermission() {
    // 決策 a)：SET_ALARM 發送端無需宣告；任何 flavor 加了就是權限面擴大。
    for (text in listOf(mainManifest()) + listOf("play", "foss", "github").map(::flavorManifest)) {
      assertFalse(text.contains("com.android.alarm.permission.SET_ALARM"))
      assertFalse(text.contains("android.permission.SET_ALARM"))
    }
  }

  @Test fun manifest_flavorOverlays_doNotTouchAlarmCalendar() {
    // 三 flavor 疊加層不得動 #14 可見性（亦不得偷加宣告；見上一個測試）。
    for (flavor in listOf("play", "foss", "github")) {
      val overlay = flavorManifest(flavor)
      assertFalse("$flavor overlay 不得出現 SET_ALARM", overlay.contains("SET_ALARM"))
      assertFalse("$flavor overlay 不得出現 calendar mimeType", overlay.contains("vnd.android.cursor"))
    }
    // 防疊加層被整塊改寫：Play 高風險移除錨仍在。
    assertTrue(flavorManifest("play").contains("<uses-permission android:name=\"android.permission.SEND_SMS\""))
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
    assertTrue(r.message, "可替代自有提醒" in r.message)
    assertEquals(IntentActions.SET_ALARM, r.spec?.action)
  }

  @Test fun alarm_unresolvedHandler_mapsToNoHandler() {
    // package-visibility 過濾或無實現：resolve null → NoHandler（fail-closed，不崩）。
    val app = appContext()
    val spec = AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30))
    assertEquals(LaunchResult.NoHandler, AndroidIntentLauncher(app).launch(spec))
  }

  @Test fun alarm_deniedSender_mapsToInternal() {
    // 缺權分型：接收端強制要求而我方未持有 → SecurityException → INTERNAL（非 NO_HANDLER）。
    val app = appContext()
    addResolve(app, Intent(IntentActions.SET_ALARM))
    val denied = object : ContextWrapper(app) {
      override fun startActivity(intent: Intent): Unit = throw SecurityException("deny")
    }
    val r = AndroidIntentLauncher(denied).launch(AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30)))
    assertTrue(r is LaunchResult.Internal)
  }

  @Test fun flags_grantReadAndClearTask_droppedViaToIntent() {
    // GRANT_*（URI 授權擴大）與 CLEAR_TASK（清空任務棧）一律丟棄；
    // 允許位保留，NEW_TASK 預設仍在。
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(
      IntentSpec(
        action = IntentActions.SET_ALARM,
        flags = IntentFlags.GRANT_READ_URI_PERMISSION or
          IntentFlags.GRANT_WRITE_URI_PERMISSION or
          IntentFlags.ACTIVITY_CLEAR_TASK or
          IntentFlags.ACTIVITY_CLEAR_TOP or
          IntentFlags.ACTIVITY_SINGLE_TOP,
      ),
    )
    assertEquals(0, intent.flags and IntentFlags.GRANT_READ_URI_PERMISSION)
    assertEquals(0, intent.flags and IntentFlags.GRANT_WRITE_URI_PERMISSION)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  // ---- d. #15 對齊：日曆執行期 NoHandler 走手動降級（與鬧鐘自有提醒同形） ----

  @Test fun calendar_executeNoHandler_degradesToManual() {
    // 發現式缺席（靜態 check 通過但發射無處理者）與鬧鐘同語義：
    // ok=false + usedFallback=true + NO_HANDLER + 手動話術（嘗試過的 spec 保留）。
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
}
