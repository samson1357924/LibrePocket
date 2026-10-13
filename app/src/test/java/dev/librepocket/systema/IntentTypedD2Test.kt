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

  @Test fun launch_fakeThrowingLauncher_convergesToInternal() {
    val throwingLauncher = IntentLauncher { throw RuntimeException("fake boom") }
    val r = AlarmTool.execute(AlarmTool.Params(7, 30), env, throwingLauncher)
    assertFalse(r.ok)
    assertEquals(ReasonCodes.INTERNAL, r.reasonCode)
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
}
