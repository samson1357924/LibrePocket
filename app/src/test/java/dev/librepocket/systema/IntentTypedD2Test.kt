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
import java.net.URLEncoder

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

  // 註：未知子類分支無法單測——實測編譯器拒絕測試源集新增 sealed 子類；
  // [AndroidIntentLauncher.toIntent] 以 sealed 窮舉 when（賦值迫使編譯期檢查）
  // 保證新增子類未補分支即編譯錯，無需 else-throw 的 runtime 保底。

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

  @Test fun launch_resolveThrowsCancellation_propagates() {
    // launch 本體三處 catch 不吞取消：PackageManager 拋取消必須外洩，而非 Internal。
    val app = appContext()
    val cancellingPm = object : ContextWrapper(app) {
      override fun getPackageManager() =
        throw kotlin.coroutines.cancellation.CancellationException("pm gone")
    }
    try {
      AndroidIntentLauncher(cancellingPm).launch(
        IntentSpec(action = Intent.ACTION_VIEW, dataUri = "https://example.com"),
      )
      fail("CancellationException must propagate from launch()")
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      // 預期。
    }
  }

  // ---- 12. Stage 3：工具覆蓋 ----

  @Test fun launchApp_single_noHandlerAndInternal_codes() {
    val singleEnv = SystemEnv(installedPackages = setOf("com.google.android.apps.maps"))
    val noHandler = IntentLauncher { LaunchResult.NoHandler }
    // F4b：Single→launcher NoHandler 與 Alarm/Calendar/Music/Navigation 同形——
    // usedFallback=true + [fallback] 完整三段話術（嘗試過的 spec 保留），不再用
    // generic toToolResult 的兩段話術；三段模板特徵句逐段鎖定。
    val r1 = LaunchAppTool.execute(LaunchAppTool.Params("com.google.android.apps.maps"), singleEnv, noHandler)
    assertFalse(r1.ok)
    assertTrue(r1.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, r1.reasonCode)
    assertEquals(
      "com.google.android.apps.maps", r1.spec?.targetPackage,
    )
    assertEquals(
      LaunchAppTool.fallback(LaunchAppTool.Params("com.google.android.apps.maps"), singleEnv).userMessage,
      r1.message,
    )
    assertTrue(
      r1.message,
      r1.message.startsWith("做不到開啟「com.google.android.apps.maps」（${ReasonCodes.NO_HANDLER}）"),
    )
    assertTrue(r1.message, "→ 可替代手動從桌面開啟" in r1.message)
    assertTrue(r1.message, "→ 需要你確認 App 名稱" in r1.message)
    val internal = IntentLauncher { LaunchResult.Internal(RuntimeException("boom")) }
    val r2 = LaunchAppTool.execute(LaunchAppTool.Params("com.google.android.apps.maps"), singleEnv, internal)
    assertFalse(r2.ok)
    assertFalse(r2.usedFallback)
    assertEquals(ReasonCodes.INTERNAL, r2.reasonCode)
  }

  @Test fun launchApp_single_success_okWithSpecAndEmptyMessage() {
    // LaunchResult 分型矩陣補齊（與上測 NoHandler/Internal 同風格）：別名命中 →
    // 發射成功 → ok=true、spec/package 保留（MAIN+LAUNCHER+setPackage，F1 契約）、
    // message 為空字串（Success 分支無 fallback 話術/原因碼）。
    var launched: IntentSpec? = null
    val r = LaunchAppTool.execute(
      LaunchAppTool.Params("地圖"), env, IntentLauncher { launched = it; LaunchResult.Success },
    )
    assertTrue(r.ok)
    assertFalse(r.usedFallback)
    assertNull(r.reasonCode)
    assertEquals("", r.message)
    assertEquals(IntentActions.MAIN, r.spec?.action)
    assertEquals("com.google.android.apps.maps", r.spec?.targetPackage)
    assertEquals("com.google.android.apps.maps", launched?.targetPackage)
  }

  @Test fun launchApp_blankQuery_needsClarification_bothEnvironments() {
    // F4a：空白 query 是意圖缺席（與 Music/Navigation 空白目標同契約）——
    // NEED_CLARIFICATION 而非 Unknown 的「做不到開啟「」」NO_HANDLER 話術，
    // 話術與 fallback 空白分支同模板；launcher 不得被調用（無意圖即無 intent）。
    // LaunchAppTool.check 無靜態 DEGRADED 分支（僅 USER_DISABLED/NATIVE），
    // 故以「預設可見集／空可見集」兩環境鎖定 env 無關性（同
    // navigation_execute_blankDestination_needsClarification 的正向+反向控制）。
    val visible = setOf("com.google.android.apps.maps", "com.spotify.music")
    for (e in listOf(SystemEnv(installedPackages = visible), SystemEnv(installedPackages = emptySet()))) {
      for (q in listOf("", "   ")) {
        var called = false
        val r = LaunchAppTool.execute(
          LaunchAppTool.Params(q), e, IntentLauncher { called = true; LaunchResult.Success },
        )
        assertFalse("query=「$q」應澄清", r.ok)
        assertFalse(r.usedFallback)
        assertEquals(ReasonCodes.NEED_CLARIFICATION, r.reasonCode)
        assertNull(r.spec)
        assertEquals(
          LaunchAppTool.fallback(LaunchAppTool.Params(q), e).userMessage,
          r.message,
        )
        assertTrue(r.message, r.message.startsWith("開啟哪個 App（${ReasonCodes.NEED_CLARIFICATION}）？"))
        assertTrue(r.message, "→ 可替代手動從桌面開啟" in r.message)
        assertTrue(r.message, "→ 需要你說出 App 名稱" in r.message)
        assertFalse("空白 query 不得回顯「」話術", r.message.contains("「"))
        assertFalse("空白 query 不得調用 launcher", called)
      }
    }
  }

  @Test fun launchApp_fuzzyClarification_messageMatchesFallback() {
    // F4c：execute 的候選澄清話術與 [fallback] NeedClarification 分支同一模板
    //（此前僅問句+候選、缺「→ 可替代 → 需要你做 Z」尾段，與 fallback 版本漂移）。
    val e = SystemEnv(installedPackages = setOf("com.google.android.apps.maps", "com.spotify.music"))
    val r = LaunchAppTool.execute(LaunchAppTool.Params("music"), e, IntentLauncher { LaunchResult.Success })
    assertFalse(r.ok)
    assertFalse(r.usedFallback)
    assertEquals(ReasonCodes.NEED_CLARIFICATION, r.reasonCode)
    assertEquals(LaunchAppTool.fallback(LaunchAppTool.Params("music"), e).userMessage, r.message)
    assertTrue(r.message, "→ 可替代候選：" in r.message)
    assertTrue(r.message, "→ 需要你選一個" in r.message)
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
    // 控制路徑與 play 同一降級真相（P3 修復釘選）：主前提是 launcher 語意
    //（MEDIA_BUTTON 無 activity handler，發射「成功」不可能是真實控制），
    // 播放器可見性（此 pin 的靜態無播放器環境）為次、同向成立——
    // usedFallbackOnSuccess + successReason 沿用 play 路徑同一參數，禁兩分支漂移。
    val control = MusicTool.execute(
      MusicTool.Params(control = MusicTool.Control.NEXT), noMusic, IntentLauncher { LaunchResult.Success },
    )
    assertTrue(control.ok)
    assertTrue(control.usedFallback)
    assertEquals(ReasonCodes.NO_HANDLER, control.reasonCode)
    // 反向控制：NATIVE（有播放器）下的控制成功不帶降級標記——參數為條件式，
    // 僅 DEGRADED 環境生效，generic 成功形狀不變。
    val nativeControl = MusicTool.execute(
      MusicTool.Params(control = MusicTool.Control.NEXT), env, IntentLauncher { LaunchResult.Success },
    )
    assertTrue(nativeControl.ok)
    assertFalse(nativeControl.usedFallback)
    assertNull(nativeControl.reasonCode)
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

  @Test fun navigation_geo_coordsBlankLabel_queryFillsQ() {
    // F5：lat/lng + 空白 label + 非空 query → q 來源補位為 query（query 已過
    // 長度校驗，靜默丟棄即縮減合法輸入；與 requirePairedCoords「禁靜默丟棄」
    // 同伴原則）。label 非空白時維持原優先序（q=label）；label/query 皆空白
    // 時回裸座標（禁空 q 查詢）。
    val queryFills = NavigationTool.buildGeoIntent(
      NavigationTool.Params(query = "night market", latitude = 25.0, longitude = 121.0, label = "   "),
    )
    assertEquals("geo:25.0,121.0?q=night+market", queryFills.dataUri)
    val labelWins = NavigationTool.buildGeoIntent(
      NavigationTool.Params(query = "night market", latitude = 25.0, longitude = 121.0, label = "abc"),
    )
    assertEquals("geo:25.0,121.0?q=abc", labelWins.dataUri)
    val bare = NavigationTool.buildGeoIntent(
      NavigationTool.Params(latitude = 25.0, longitude = 121.0),
    )
    assertEquals("geo:25.0,121.0", bare.dataUri)
  }

  @Test fun navigation_geo_labelOnlyNoCoords_qUsesLabel_notEmptyQ() {
    // P2 修復釘選：無座標分支照座標分支**同一 q 來源優先序**（label → query，
    // 見建構函式註解）。label-only 輸入（舊行為＝丟 label 發射空查詢
    // `geo:0,0?q=`——execute 空白守衛要求全 blank，攔不到只帶 label 的輸入）
    // 得出 q==label 的非空查詢。
    val geo = NavigationTool.buildGeoIntent(
      NavigationTool.Params(query = "", label = "夜市"),
    )
    assertEquals("geo:0,0?q=%E5%A4%9C%E5%B8%82", geo.dataUri)
  }

  @Test fun navigation_execute_labelOnlyNoCoords_launchesLabeledQuery() {
    // 執行面同釘選：label-only 經 execute（NATIVE 地圖）發射的 intent q=label，
    // 不再是「空 q」舊契約。
    var launched: IntentSpec? = null
    val r = NavigationTool.execute(
      NavigationTool.Params(label = "夜市"), env,
      IntentLauncher { launched = it; LaunchResult.Success },
    )
    assertTrue(r.ok)
    assertEquals("geo:0,0?q=%E5%A4%9C%E5%B8%82", launched?.dataUri)
  }

  @Test fun navigation_geo_noCoords_bothBlank_throws() {
    // 兩者皆空白＋無座標 → builder fail-fast IAE（禁造空 q）。呼叫方契約：
    // execute 的空白前置守衛先攔（NEED_CLARIFICATION），故直接呼叫 builder 的
    // INTERNAL 收斂（guardedToolBuild）不可達屬故意縱深——本案鎖 builder 本體
    // 的守衛訊息（deterministic，不回顯內容）。
    try {
      NavigationTool.buildGeoIntent(NavigationTool.Params())
      fail("expected IllegalArgumentException for all-blank no-coords params")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message ?: "", "blank" in (e.message ?: ""))
    }
  }

  @Test fun navigation_web_noCoords_bothBlank_throws() {
    // geo/web 對稱性：all-blank 無座標輸入在 web 分支同樣 fail-fast IAE
    //（禁靜默組空查詢 OSM URL）。呼叫方契約同 geo：execute 的空白前置守衛
    // 先攔（NEED_CLARIFICATION），直呼 builder 的 INTERNAL 收斂不可達屬故意
    // 縱深——守衛訊息與 geo 分支逐字相同（deterministic，不回顯內容）。
    try {
      NavigationTool.buildWebIntent(NavigationTool.Params())
      fail("expected IllegalArgumentException for all-blank no-coords web params")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message ?: "", "blank" in (e.message ?: ""))
    }
  }

  @Test fun navigation_labelOnly_geoAndWeb_sameNonEmptyQuery() {
    // geo/web 對同一 label-only 輸入**皆得出非空查詢**（相同搜尋詞）：
    // geo 無座標分支與 web 同以 label 補位，兩 URI 攜同一編碼詞（label 優先序
    // 一致；geo 是 `?q=`、web 是 `?query=`，前綴差異非搜尋詞差異）。
    val encoded = URLEncoder.encode("夜市", Charsets.UTF_8.name())
    val geo = NavigationTool.buildGeoIntent(NavigationTool.Params(label = "夜市")).dataUri ?: ""
    val web = NavigationTool.buildWebIntent(NavigationTool.Params(label = "夜市")).dataUri ?: ""
    assertTrue("geo 須攜非空 q：$geo", geo.startsWith("geo:0,0?q=") && geo.endsWith(encoded))
    assertTrue(
      "web 須攜同一非空查詢：$web",
      web.startsWith("https://www.openstreetmap.org/search?query=") && web.endsWith(encoded),
    )
  }

  // ---- 14. Stage 3：navigation blank 判斷的雙側座標契約 ----

  @Test fun navigation_execute_latitudeOnly_convergesInternal() {
    // 反向控制：latitude-only 不是「空白目的地」（雙側缺席才是）——續走 resolve
    // 撞 [NavigationTool.requirePairedCoords]（禁靜默丟棄）收斂 INTERNAL。
    val r = NavigationTool.execute(
      NavigationTool.Params(latitude = 25.0), env, IntentLauncher { LaunchResult.Success },
    )
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
  }

  @Test fun navigation_execute_longitudeOnly_convergesInternal() {
    // reviewer 回報的繞道面：單缺 longitude 時，舊空白判斷只看緯度側，會把
    // 配對破損偽裝成「缺目的地」回 NEED_CLARIFICATION，與 latitude-only 收斂
    // INTERNAL 的方向不一致。修正後單側經/緯同被 paired-coord 守衛攔下。
    val r = NavigationTool.execute(
      NavigationTool.Params(longitude = 121.0), env, IntentLauncher { LaunchResult.Success },
    )
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
  }

  @Test fun navigation_execute_blankDestination_needsClarification() {
    // 正向控制：query/label 空白且經緯**雙側**皆缺才是真正的空白目的地，
    // 澄清話術不因本修正意外收緊；且與地圖有無（NATIVE/DEGRADED）無關。
    val native = NavigationTool.execute(
      NavigationTool.Params(), env, IntentLauncher { LaunchResult.Success },
    )
    assertFalse(native.ok)
    assertEquals(ReasonCodes.NEED_CLARIFICATION, native.reasonCode)
    val degraded = NavigationTool.execute(
      NavigationTool.Params(), env.copy(hasMapApp = false), IntentLauncher { LaunchResult.Success },
    )
    assertFalse(degraded.ok)
    assertEquals(ReasonCodes.NEED_CLARIFICATION, degraded.reasonCode)
  }

  @Test fun navigation_execute_userDisabled_beatsClarification_forLongitudeOnly() {
    // 檢查優先序：USER_DISABLED 早於空白/座標判斷——longitude-only 參數
    // 不得掩蓋使用者已關閉快通道的事實。
    val disabled = env.copy(userEnabled = false)
    val r = NavigationTool.execute(
      NavigationTool.Params(longitude = 121.0), disabled, IntentLauncher { LaunchResult.Success },
    )
    assertFalse(r.ok)
    assertEquals(ReasonCodes.USER_DISABLED, r.reasonCode)
  }

  // ---- 15. F1：IntentSpec categories 映射（隱式 MAIN 啟動契約）----

  @Test fun launchApp_launchSpec_carriesLauncherCategory() {
    // 隱式 MAIN+setPackage 的 filter 契約：launcher activity 的 manifest filter
    // 通常僅 MAIN+CATEGORY_LAUNCHER（無 CATEGORY_DEFAULT）——spec 必攜
    // LAUNCHER category，否則隱式 intent filter 匹配不可達（IntentSpec.categories
    // KDoc 契約句；framework 常量字面值逐字鎖定）。
    assertEquals(
      listOf("android.intent.category.LAUNCHER"),
      LaunchAppTool.launchSpec("com.google.android.apps.maps").categories,
    )
  }

  @Test fun intentSpec_categories_addedOneByOneViaToIntent() {
    // 幾項 categories 逐項 addCategory（斷言端側 intent.getCategories() 集合）；
    // dataUri/extras 既有路徑不受影響。
    val launcher = AndroidIntentLauncher(appContext())
    val intent = launcher.toIntent(
      IntentSpec(
        action = IntentActions.MAIN,
        targetPackage = "com.example.test",
        categories = listOf("android.intent.category.LAUNCHER", "android.intent.category.DEFAULT"),
      ),
    )
    assertEquals(
      setOf("android.intent.category.LAUNCHER", "android.intent.category.DEFAULT"),
      intent.categories,
    )
    assertEquals("android.intent.action.MAIN", intent.action)
    assertEquals("com.example.test", intent.`package`)
  }

  @Test fun intentSpec_absentCategories_intentHasNone() {
    // 缺省 categories（既有 spec 形狀）產生無分類 intent——既有 pin 不因
    // 新欄位漂移（零破壞契約）。
    val launcher = AndroidIntentLauncher(appContext())
    assertNull(
      launcher.toIntent(
        IntentSpec(action = IntentActions.VIEW, dataUri = "https://example.com"),
      ).categories,
    )
  }
}
