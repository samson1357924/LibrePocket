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
 * D2：typed extras / flags allowlist / resolve 失敗分型（Robolectric + 純 JVM）。
 *
 * - alarm hour/minute=Int、skipUi=Boolean；calendar begin/end=Long；
 *   music keyCode=Int；端側 `getIntExtra/getLongExtra/getBooleanExtra` 可讀回。
 * - flags 僅 allowlist 通過才加，預設仍只加 NEW_TASK。
 * - `resolveActivity == null` → NO_HANDLER；執行期拋錯 → INTERNAL，不再混同。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntentTypedD2Test {

  private val env = SystemEnv(
    hasMapApp = true,
    installedPackages = setOf("com.google.android.apps.maps"),
    hasAlarmApp = true,
    hasCalendarApp = true,
    hasMusicApp = true,
  )

  private fun appContext(): Context = ApplicationProvider.getApplicationContext()

  /** 讓指定 intent 可被 resolve（含合法 ActivityInfo，避免框架 NPE）。 */
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

  // ---- 1. alarm 型別 ----

  @Test fun alarm_extras_areTyped() {
    val spec = AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30, "起床", skipUi = true))
    assertEquals(IntentExtra.IntVal(7), spec.extras[IntentActions.EXTRA_ALARM_HOUR])
    assertEquals(IntentExtra.IntVal(30), spec.extras[IntentActions.EXTRA_ALARM_MINUTES])
    assertEquals(IntentExtra.Str("起床"), spec.extras[IntentActions.EXTRA_ALARM_MESSAGE])
    assertEquals(IntentExtra.Bool(true), spec.extras[IntentActions.EXTRA_ALARM_SKIP_UI])
  }

  @Test fun alarm_skipUiFalse_omitsKey() {
    val spec = AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30))
    assertNull(spec.extras[IntentActions.EXTRA_ALARM_SKIP_UI])
  }

  @Test fun alarm_hourMinute_readBackAsInt() {
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30, "起床")))
    assertEquals(7, intent.getIntExtra(IntentActions.EXTRA_ALARM_HOUR, -1))
    assertEquals(30, intent.getIntExtra(IntentActions.EXTRA_ALARM_MINUTES, -1))
  }

  @Test fun alarm_skipUi_readBackAsBoolean() {
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30, skipUi = true)))
    assertTrue(intent.getBooleanExtra(IntentActions.EXTRA_ALARM_SKIP_UI, false))
  }

  // ---- 2. calendar 型別 ----

  @Test fun calendar_insertExtras_areTyped() {
    val spec = CalendarTool.buildInsertIntent(
      CalendarTool.EventParams("例會", 1_700_000_000_000, 1_700_000_360_000, "會議室", "週會"),
    )
    assertEquals(IntentExtra.LongVal(1_700_000_000_000), spec.extras[CalendarTool.EXTRA_BEGIN_TIME])
    assertEquals(IntentExtra.LongVal(1_700_000_360_000), spec.extras[CalendarTool.EXTRA_END_TIME])
    assertEquals(IntentExtra.Str("例會"), spec.extras[CalendarTool.EXTRA_EVENT_TITLE])
    assertEquals(IntentExtra.Str("會議室"), spec.extras[CalendarTool.EXTRA_EVENT_LOCATION])
  }

  @Test fun calendar_editExtras_areTyped() {
    val spec = CalendarTool.buildEditIntent(
      CalendarTool.UpdateParams(eventId = "42", title = "新標題", beginTimeMillis = 1000L),
    )
    assertEquals(IntentExtra.Str("新標題"), spec.extras[CalendarTool.EXTRA_EVENT_TITLE])
    assertEquals(IntentExtra.LongVal(1000L), spec.extras[CalendarTool.EXTRA_BEGIN_TIME])
  }

  @Test fun calendar_beginEnd_readBackAsLong() {
    val launcher = AndroidIntentLauncher(appContext())
    val spec = CalendarTool.buildInsertIntent(
      CalendarTool.EventParams("例會", 1_700_000_000_000, 1_700_000_360_000),
    )
    val intent = launcher.toIntent(spec)
    assertEquals(1_700_000_000_000, intent.getLongExtra(CalendarTool.EXTRA_BEGIN_TIME, -1))
    assertEquals(1_700_000_360_000, intent.getLongExtra(CalendarTool.EXTRA_END_TIME, -1))
  }

  // ---- 3. music 型別 ----

  @Test fun music_keyCode_isInt() {
    val spec = MusicTool.buildControlIntent(MusicTool.Control.NEXT)
    assertEquals(IntentExtra.IntVal(87), spec.extras["keyCode"])
  }

  @Test fun music_keyCode_readBackAsInt() {
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(MusicTool.buildControlIntent(MusicTool.Control.NEXT))
    assertEquals(87, intent.getIntExtra("keyCode", -1))
  }

  // ---- 4. flags allowlist ----

  @Test fun flags_sanitize_keepsOnlyAllowed() {
    assertEquals(
      Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
      IntentFlags.sanitize(
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
          Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or
          IntentFlags.GRANT_READ_URI_PERMISSION,
      ),
    )
    assertEquals(0, IntentFlags.sanitize(Intent.FLAG_ACTIVITY_CLEAR_TASK))
    assertEquals(IntentFlags.ALLOWED_MASK, IntentFlags.sanitize(-1))
  }

  @Test fun flags_allowedFlag_appliedViaToIntent() {
    val launcher = AndroidIntentLauncher(appContext())
    val spec = IntentSpec(
      action = IntentActions.VIEW,
      dataUri = "https://example.com",
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
    )
    val intent = launcher.toIntent(spec)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
  }

  @Test fun flags_default_onlyNewTask() {
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(IntentSpec(action = IntentActions.VIEW))
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
  }

  @Test fun flags_disallowedFlag_dropped() {
    val launcher = AndroidIntentLauncher(appContext())
    val spec = IntentSpec(
      action = IntentActions.VIEW,
      flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY,
    )
    val intent = launcher.toIntent(spec)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
    // NEW_TASK 預設仍在。
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  // ---- 5. resolve 失敗分型 ----

  @Test fun launch_noHandler_returnsNoHandler() {
    val app = appContext()
    val r = AndroidIntentLauncher(app).launch(IntentSpec(action = "dev.librepocket.test.UNHANDLED_ACTION_XYZ"))
    assertEquals(LaunchResult.NoHandler, r)
  }

  @Test fun launch_resolved_returnsSuccess() {
    val app = appContext()
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    addResolve(app, probe)
    val r = AndroidIntentLauncher(app).launch(
      IntentSpec(action = Intent.ACTION_VIEW, dataUri = "https://example.com"),
    )
    assertEquals(LaunchResult.Success, r)
  }

  @Test fun launch_startActivityThrows_returnsInternal() {
    val app = appContext()
    addResolve(app, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
    val throwing = object : ContextWrapper(app) {
      override fun startActivity(intent: Intent) = throw RuntimeException("boom")
    }
    val r = AndroidIntentLauncher(throwing).launch(
      IntentSpec(action = Intent.ACTION_VIEW, dataUri = "https://example.com"),
    )
    assertTrue(r is LaunchResult.Internal)
  }

  @Test fun launch_startActivityNotFound_mapsToNoHandler() {
    val app = appContext()
    addResolve(app, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
    val gone = object : ContextWrapper(app) {
      override fun startActivity(intent: Intent): Unit =
        throw android.content.ActivityNotFoundException("handler gone")
    }
    val r = AndroidIntentLauncher(gone).launch(
      IntentSpec(action = Intent.ACTION_VIEW, dataUri = "https://example.com"),
    )
    assertEquals(LaunchResult.NoHandler, r)
  }

  @Test fun launch_startActivitySecurityException_mapsToInternal() {
    val app = appContext()
    addResolve(app, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
    val denied = object : ContextWrapper(app) {
      override fun startActivity(intent: Intent): Unit =
        throw SecurityException("deny")
    }
    val r = AndroidIntentLauncher(denied).launch(
      IntentSpec(action = Intent.ACTION_VIEW, dataUri = "https://example.com"),
    )
    assertTrue(r is LaunchResult.Internal)
  }

  @Test fun launch_fakeThrowingLauncher_convergesToInternal() {
    val throwingLauncher = IntentLauncher { throw RuntimeException("fake boom") }
    val r = AlarmTool.execute(AlarmTool.Params(7, 30), env, throwingLauncher)
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
  }

  @Test fun launchCatching_rethrowsCancellation() {
    val cancelling = IntentLauncher {
      throw kotlin.coroutines.cancellation.CancellationException("gone")
    }
    val spec = IntentSpec(action = Intent.ACTION_VIEW, dataUri = "https://example.com")
    try {
      cancelling.launchCatching(spec)
      fail("CancellationException must propagate, not converge to Internal")
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      // 預期：取消直接外洩。
    }
  }

  // ---- 6. tool 層原因碼分型 ----

  @Test fun execute_noHandler_mapsToNoHandlerCode() {
    val noHandler = IntentLauncher { LaunchResult.NoHandler }
    assertEquals(
      ReasonCodes.NO_HANDLER,
      AlarmTool.execute(AlarmTool.Params(7, 30), env, noHandler).reasonCode,
    )
    assertEquals(
      ReasonCodes.NO_HANDLER,
      CalendarTool.execute(CalendarTool.EventParams("面試", 1000, 2000), env, noHandler).reasonCode,
    )
    assertEquals(
      ReasonCodes.NO_HANDLER,
      MusicTool.execute(MusicTool.Params(control = MusicTool.Control.NEXT), env, noHandler).reasonCode,
    )
  }

  @Test fun execute_internal_mapsToInternalCode() {
    val internal = IntentLauncher { LaunchResult.Internal(RuntimeException("boom")) }
    val alarm = AlarmTool.execute(AlarmTool.Params(7, 30), env, internal)
    assertFalse(alarm.ok)
    assertEquals(ReasonCodes.INTERNAL, alarm.reasonCode)
    assertTrue("（${ReasonCodes.INTERNAL}）" in alarm.message)
    assertEquals(
      ReasonCodes.INTERNAL,
      CalendarTool.execute(CalendarTool.EventParams("面試", 1000, 2000), env, internal).reasonCode,
    )
    assertEquals(
      ReasonCodes.INTERNAL,
      MusicTool.execute(MusicTool.Params(control = MusicTool.Control.NEXT), env, internal).reasonCode,
    )
  }

  // ---- 7. Stage 3：typed getter ----

  @Test fun extras_requireExtra_roundTrip() {
    val extras: Map<String, IntentExtra> = mapOf(
      "s" to IntentExtra.Str("x"),
      "l" to IntentExtra.LongVal(9L),
    )
    assertEquals(IntentExtra.Str("x"), extras.requireExtra<IntentExtra.Str>("s"))
    assertEquals(IntentExtra.LongVal(9L), extras.requireExtra<IntentExtra.LongVal>("l"))
  }

  @Test fun extras_requireExtra_mismatchThrows() {
    val extras: Map<String, IntentExtra> = mapOf("k" to IntentExtra.LongVal(1L))
    try {
      extras.requireExtra<IntentExtra.Str>("k")
      fail("expected IllegalStateException for mistyped extra")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", "k" in (e.message ?: ""))
      assertTrue(e.message ?: "", "Str" in (e.message ?: ""))
    }
  }

  @Test fun extras_requireExtra_missingThrows() {
    try {
      emptyMap<String, IntentExtra>().requireExtra<IntentExtra.Str>("nope")
      fail("expected IllegalStateException for missing extra")
    } catch (e: IllegalStateException) {
      assertTrue(e.message ?: "", "nope" in (e.message ?: ""))
    }
  }

  // ---- 8. Stage 3：calendar edit 排序 + eventId 數字校驗 ----

  @Test(expected = IllegalArgumentException::class)
  fun calendar_edit_endBeforeBegin_throws() {
    CalendarTool.buildEditIntent(
      CalendarTool.UpdateParams(eventId = "42", beginTimeMillis = 2000L, endTimeMillis = 1000L),
    )
  }

  @Test fun calendar_edit_singleSidedTime_ok() {
    // 單端 null 表不改，不觸發排序校驗。
    val spec = CalendarTool.buildEditIntent(
      CalendarTool.UpdateParams(eventId = "42", beginTimeMillis = 2000L),
    )
    assertEquals(IntentExtra.LongVal(2000L), spec.extras[CalendarTool.EXTRA_BEGIN_TIME])
  }

  @Test fun calendar_eventId_nonNumeric_throws() {
    for (bad in listOf("1/2", "1?x=1", "../0", "abc", "12 34")) {
      try {
        CalendarTool.eventUri(bad)
        fail("expected IllegalArgumentException for eventId=$bad")
      } catch (e: IllegalArgumentException) {
        assertTrue("eventId=$bad 錯誤訊息須指明 numeric，實際=${e.message}", "numeric" in (e.message ?: ""))
      }
    }
  }

  @Test fun calendar_eventId_numeric_ok() {
    assertEquals("${CalendarTool.EVENTS_CONTENT_URI}/42", CalendarTool.eventUri("42"))
    assertEquals("${CalendarTool.EVENTS_CONTENT_URI}/007", CalendarTool.eventUri("  007  "))
  }

  @Test fun calendar_delete_nonNumeric_throws() {
    // 與 edit 共用 eventUri，同受益。
    try {
      CalendarTool.buildDeleteDescriptor(CalendarTool.DeleteParams("../0"))
      fail("expected IllegalArgumentException for delete eventId")
    } catch (e: IllegalArgumentException) {
      assertTrue("delete eventId 錯誤訊息須指明 numeric，實際=${e.message}", "numeric" in (e.message ?: ""))
    }
  }

  // ---- 9. Stage 3：Internal 訊息帶 cause 類名 ----

  @Test fun toolResult_internal_includesCauseName() {
    val spec = IntentSpec(action = IntentActions.VIEW)
    val sec = LaunchResult.Internal(SecurityException("deny")).toToolResult(spec, "開啟 App")
    val run = LaunchResult.Internal(RuntimeException("boom")).toToolResult(spec, "開啟 App")
    assertEquals(ReasonCodes.INTERNAL, sec.reasonCode)
    assertEquals(ReasonCodes.INTERNAL, run.reasonCode)
    assertTrue(sec.message, "SecurityException" in sec.message)
    assertTrue(run.message, "RuntimeException" in run.message)
    assertTrue(sec.message, "（${ReasonCodes.INTERNAL}）" in sec.message)
    assertFalse(sec.message == run.message)
  }

  @Test fun toolResult_internal_nullCause_showsUnknown() {
    val spec = IntentSpec(action = IntentActions.VIEW)
    val r = LaunchResult.Internal(null).toToolResult(spec, "開啟 App")
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
    assertTrue(r.message, "（${ReasonCodes.INTERNAL}）" in r.message)
    assertTrue(r.message, "[Unknown]" in r.message)
  }

  // ---- 10. Stage 3：flags mirror + 丟棄語義 ----

  @Test fun flags_mirrorMatchesFramework() {
    assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, IntentFlags.GRANT_READ_URI_PERMISSION)
    assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, IntentFlags.GRANT_WRITE_URI_PERMISSION)
    assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TASK, IntentFlags.ACTIVITY_CLEAR_TASK)
    assertEquals(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS, IntentFlags.ACTIVITY_EXCLUDE_FROM_RECENTS)
    assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, IntentFlags.ACTIVITY_CLEAR_TOP)
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, IntentFlags.ACTIVITY_NEW_TASK)
    assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, IntentFlags.ACTIVITY_SINGLE_TOP)
    assertEquals(Intent.FLAG_ACTIVITY_NO_HISTORY, IntentFlags.ACTIVITY_NO_HISTORY)
    assertEquals(
      Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
      IntentFlags.ALLOWED_MASK,
    )
  }

  @Test fun flags_extraDisallowed_droppedViaToIntent() {
    // PERSISTABLE/PREFIX 無純側常量，以框架實際值為準，經 toIntent 一律丟棄。
    val launcher = AndroidIntentLauncher(appContext())
    val spec = IntentSpec(
      action = IntentActions.VIEW,
      flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    )
    val intent = launcher.toIntent(spec)
    assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    assertEquals(0, intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    // NEW_TASK 預設仍在。
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test fun flags_minusOne_mapsToMaskViaToIntent() {
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(IntentSpec(action = IntentActions.VIEW, flags = -1))
    assertEquals(IntentFlags.ALLOWED_MASK, intent.flags)
  }

  // ---- 11. Stage 3：resolve 拋錯 ----

  @Test fun launch_resolveThrows_returnsInternal() {
    val app = appContext()
    val throwingPm = object : ContextWrapper(app) {
      override fun getPackageManager() = throw RuntimeException("pm boom")
    }
    val r = AndroidIntentLauncher(throwingPm).launch(
      IntentSpec(action = Intent.ACTION_VIEW, dataUri = "https://example.com"),
    )
    assertTrue(r is LaunchResult.Internal)
  }

  // ---- 12. Stage 3：工具覆蓋 ----

  @Test fun launchApp_single_noHandlerAndInternal_codes() {
    val singleEnv = SystemEnv(installedPackages = setOf("com.google.android.apps.maps"))
    val noHandler = IntentLauncher { LaunchResult.NoHandler }
    val r1 = LaunchAppTool.execute(LaunchAppTool.Params("com.google.android.apps.maps"), singleEnv, noHandler)
    assertFalse(r1.ok)
    assertFalse(r1.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, r1.reasonCode)
    val internal = IntentLauncher { LaunchResult.Internal(RuntimeException("boom")) }
    val r2 = LaunchAppTool.execute(LaunchAppTool.Params("com.google.android.apps.maps"), singleEnv, internal)
    assertFalse(r2.ok)
    assertEquals(ReasonCodes.INTERNAL, r2.reasonCode)
  }

  @Test fun navigation_degradedSuccess_locksFallbackSemantics() {
    val noMap = env.copy(hasMapApp = false)
    val r = NavigationTool.execute(
      NavigationTool.Params(query = "夜市"), noMap, IntentLauncher { LaunchResult.Success },
    )
    assertTrue(r.ok)
    assertTrue(r.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, r.reasonCode)
  }

  @Test fun music_degradedPlaySuccess_locksFallbackSemantics() {
    val noMusic = env.copy(hasMusicApp = false)
    val play = MusicTool.execute(
      MusicTool.Params(query = "周杰倫"), noMusic, IntentLauncher { LaunchResult.Success },
    )
    assertTrue(play.ok)
    assertTrue(play.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, play.reasonCode)
    // 控制路徑現語義：成功不帶 fallback/reason（成功分支 reasonCode 重構列 follow-up，不在此改）。
    val control = MusicTool.execute(
      MusicTool.Params(control = MusicTool.Control.NEXT), noMusic, IntentLauncher { LaunchResult.Success },
    )
    assertTrue(control.ok)
    assertFalse(control.usedFallback)
    assertNull(control.reasonCode)
  }

  // ---- 13. Stage 3：navigation geo ----

  @Test fun navigation_latLng_outOfRange_throws() {
    for (p in listOf(
      NavigationTool.Params(latitude = 91.0, longitude = 0.0),
      NavigationTool.Params(latitude = -91.0, longitude = 0.0),
      NavigationTool.Params(latitude = 0.0, longitude = 181.0),
      NavigationTool.Params(latitude = 0.0, longitude = -181.0),
    )) {
      try {
        NavigationTool.buildGeoIntent(p)
        fail("expected IllegalArgumentException for $p")
      } catch (e: IllegalArgumentException) {
        assertTrue("$p 錯誤訊息須指明 out of range，實際=${e.message}", "out of range" in (e.message ?: ""))
      }
    }
  }

  @Test fun navigation_latLng_nonFinite_throws() {
    for (p in listOf(
      NavigationTool.Params(latitude = Double.NaN, longitude = 0.0),
      NavigationTool.Params(latitude = 0.0, longitude = Double.POSITIVE_INFINITY),
      NavigationTool.Params(latitude = Double.NEGATIVE_INFINITY, longitude = 0.0),
      NavigationTool.Params(latitude = 0.0, longitude = Double.NaN),
    )) {
      try {
        NavigationTool.buildGeoIntent(p)
        fail("expected IllegalArgumentException for $p")
      } catch (e: IllegalArgumentException) {
        assertTrue("$p 錯誤訊息須指明 finite，實際=${e.message}", "finite" in (e.message ?: ""))
      }
    }
  }

  @Test fun navigation_web_latLng_outOfRange_throws() {
    // 與 geo 一致：web 分支同樣拒絕越界/非有限座標，禁經 OSM URL 外洩。
    for (p in listOf(
      NavigationTool.Params(latitude = 91.0, longitude = 0.0),
      NavigationTool.Params(latitude = 0.0, longitude = 181.0),
      NavigationTool.Params(latitude = Double.NaN, longitude = 0.0),
      NavigationTool.Params(latitude = 0.0, longitude = Double.POSITIVE_INFINITY),
    )) {
      try {
        NavigationTool.buildWebIntent(p)
        fail("expected IllegalArgumentException for web $p")
      } catch (e: IllegalArgumentException) {
        assertTrue("web $p 錯誤訊息須指明範圍（out of range/finite），實際=${e.message}", "out of range" in (e.message ?: "") || "finite" in (e.message ?: ""))
      }
    }
  }

  @Test fun navigation_latLng_boundary_ok() {
    val spec = NavigationTool.buildGeoIntent(NavigationTool.Params(latitude = 90.0, longitude = 180.0))
    assertTrue(spec.dataUri ?: "", (spec.dataUri ?: "").startsWith("geo:90.0,180.0"))
    val neg = NavigationTool.buildGeoIntent(NavigationTool.Params(latitude = -90.0, longitude = -180.0))
    assertTrue(neg.dataUri ?: "", (neg.dataUri ?: "").startsWith("geo:-90.0,-180.0"))
  }

  @Test fun navigation_singleSidedCoord_throws() {
    // 單側座標 fail-fast：禁靜默丟棄，geo 與 web 兩路徑皆拋。
    for (p in listOf(
      NavigationTool.Params(latitude = 25.0),
      NavigationTool.Params(longitude = 121.0),
    )) {
      try {
        NavigationTool.buildGeoIntent(p)
        fail("expected IllegalArgumentException for single-sided $p")
      } catch (e: IllegalArgumentException) {
        assertTrue("單側 $p 錯誤訊息須指明 paired（both null/both non-null），實際=${e.message}", "both" in (e.message ?: ""))
      }
      try {
        NavigationTool.buildWebIntent(p)
        fail("expected IllegalArgumentException for single-sided $p")
      } catch (e: IllegalArgumentException) {
        assertTrue("單側 $p（web）錯誤訊息須指明 paired（both null/both non-null），實際=${e.message}", "both" in (e.message ?: ""))
      }
    }
  }

  @Test fun navigation_labelEncoding_locksUrlEncoderPlus() {
    // 鎖定現行 URLEncoder 行為（空格→`+`），見 NavigationTool KDoc；不硬改為 Uri.encode。
    val spec = NavigationTool.buildGeoIntent(
      NavigationTool.Params(latitude = 25.0, longitude = 121.0, label = "a b"),
    )
    assertEquals("geo:25.0,121.0?q=a+b", spec.dataUri)
  }
}
