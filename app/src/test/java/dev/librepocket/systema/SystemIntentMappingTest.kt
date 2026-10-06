package dev.librepocket.systema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 組快通道 Intent 映射測試（純 JVM，不碰 Android 框架）。
 *
 * 覆蓋 6 操作映射 + 降級分支 + 無地圖走網頁。
 */
class SystemIntentMappingTest {

  private val okLauncher = IntentLauncher { true }
  private val fullEnv = SystemEnv(
    hasMapApp = true,
    installedPackages = setOf("com.google.android.apps.maps", "com.spotify.music"),
    hasAlarmApp = true,
    hasCalendarApp = true,
    calendarReadGranted = true,
    hasMusicApp = true,
  )

  // ---- 註冊表 ----

  @Test fun registry_holdsSixFastTools() {
    assertEquals(
      setOf(
        "system.navigate", "system.launch_app", "system.set_alarm",
        "system.calendar", "system.volume", "system.music",
      ),
      ToolRegistry.names(),
    )
    assertEquals(6, ToolRegistry.all().size)
  }

  // ---- 1. 導航 ----

  @Test fun navigate_placeQuery_mapsToGeoIntent() {
    val spec = NavigationTool.buildGeoIntent(NavigationTool.Params(query = "台北車站"))
    assertEquals("android.intent.action.VIEW", spec.action)
    assertTrue((spec.dataUri ?: ""), (spec.dataUri ?: "").startsWith("geo:0,0?q="))
    assertTrue((spec.dataUri ?: ""), "台北" !in (spec.dataUri ?: "") || "%" in (spec.dataUri ?: ""))
  }

  @Test fun navigate_latLng_mapsToGeoIntent() {
    val spec = NavigationTool.buildGeoIntent(
      NavigationTool.Params(latitude = 25.0478, longitude = 121.5170, label = "北車"),
    )
    assertTrue((spec.dataUri ?: ""), (spec.dataUri ?: "").startsWith("geo:25.0478,121.517"))
  }

  @Test fun navigate_noMapApp_fallsBackToWeb() {
    val env = fullEnv.copy(hasMapApp = false)
    val check = NavigationTool.check(env)
    assertEquals(Availability.DEGRADED, check.availability)
    assertEquals(ReasonCodes.NO_HANDLER, check.reasonCode)
    val spec = NavigationTool.resolve(NavigationTool.Params(query = "台北車站"), env)
    assertTrue((spec.dataUri ?: ""), (spec.dataUri ?: "").startsWith("https://www.openstreetmap.org/search?query="))
    val fb = NavigationTool.fallback(NavigationTool.Params(query = "台北車站"), env)
    assertEquals(ReasonCodes.NO_HANDLER, fb.reasonCode)
    assertTrue((fb.alternative?.dataUri ?: ""), (fb.alternative?.dataUri ?: "").startsWith("https://"))
    assertTrue(fb.userMessage, "（${ReasonCodes.NO_HANDLER}）" in fb.userMessage)
  }

  @Test fun navigate_execute_usesWebWhenNoMap() {
    val env = fullEnv.copy(hasMapApp = false)
    var launched: IntentSpec? = null
    val r = NavigationTool.execute(
      NavigationTool.Params(query = "夜市"), env, IntentLauncher { launched = it; true },
    )
    assertTrue(r.ok)
    assertTrue(r.usedFallback)
    assertTrue((launched?.dataUri ?: ""), (launched?.dataUri ?: "").startsWith("https://"))
  }

  // ---- 2. 開 App ----

  @Test fun launchApp_aliasResolvesToPackage() {
    val r = LaunchAppTool.resolve("地圖", fullEnv.installedPackages)
    assertTrue(r is LaunchAppTool.LaunchResolution.Single)
    val single = r as LaunchAppTool.LaunchResolution.Single
    assertEquals("com.google.android.apps.maps", single.packageName)
    assertEquals("android.intent.action.MAIN", single.spec.action)
  }

  @Test fun launchApp_exactPackageResolves() {
    val r = LaunchAppTool.resolve("com.spotify.music", fullEnv.installedPackages)
    assertTrue(r is LaunchAppTool.LaunchResolution.Single)
  }

  @Test fun launchApp_fuzzyName_needsClarification() {
    // "music" 同時命中 youtube.music 別名與 spotify 包名 → 澄清。
    val r = LaunchAppTool.resolve("music", fullEnv.installedPackages)
    assertTrue(r is LaunchAppTool.LaunchResolution.NeedClarification)
    val c = (r as LaunchAppTool.LaunchResolution.NeedClarification).candidates
    assertTrue(c.size >= 2)
    val exec = LaunchAppTool.execute(
      LaunchAppTool.Params("music"), fullEnv, okLauncher,
    )
    assertFalse(exec.ok)
    assertEquals(ReasonCodes.NEED_CLARIFICATION, exec.reasonCode)
  }

  @Test fun launchApp_unknown_fallsBack() {
    val r = LaunchAppTool.resolve("不存在的AppXYZ", fullEnv.installedPackages)
    assertTrue(r is LaunchAppTool.LaunchResolution.Unknown)
    val fb = LaunchAppTool.fallback(LaunchAppTool.Params("不存在的AppXYZ"), fullEnv)
    assertEquals(ReasonCodes.NO_HANDLER, fb.reasonCode)
  }

  @Test fun launchApp_queriesWhitelist_nonEmpty() {
    assertTrue(LaunchAppTool.QUERY_WHITELIST.isNotEmpty())
    assertTrue("com.google.android.apps.maps" in LaunchAppTool.QUERY_WHITELIST.values)
  }

  // ---- 3. 鬧鐘 ----

  @Test fun alarm_mapsToSetAlarmIntent() {
    val spec = AlarmTool.buildSetAlarmIntent(AlarmTool.Params(7, 30, "起床"))
    assertEquals("android.intent.action.SET_ALARM", spec.action)
    assertEquals("7", spec.extras["android.intent.extra.alarm.HOUR"])
    assertEquals("30", spec.extras["android.intent.extra.alarm.MINUTES"])
    assertEquals("起床", spec.extras["android.intent.extra.alarm.MESSAGE"])
  }

  @Test fun alarm_noAlarmApp_degradesToOwnReminder() {
    val env = fullEnv.copy(hasAlarmApp = false)
    val check = AlarmTool.check(env)
    assertEquals(Availability.DEGRADED, check.availability)
    val fb = AlarmTool.fallback(AlarmTool.Params(7, 30), env)
    assertEquals(ReasonCodes.NO_HANDLER, fb.reasonCode)
    assertTrue(fb.requiresDeclaration, "SCHEDULE_EXACT_ALARM" in fb.requiresDeclaration)
  }

  @Test(expected = IllegalArgumentException::class)
  fun alarm_rejectsBadHour() {
    AlarmTool.buildSetAlarmIntent(AlarmTool.Params(25, 0))
  }

  // ---- 4. 日曆 ----

  @Test fun calendar_insertDelegation_needsNoPermission() {
    // 未授權 READ_CALENDAR，建事件仍 NATIVE（INSERT 委託優先）。
    val env = fullEnv.copy(calendarReadGranted = false)
    assertEquals(Availability.NATIVE, CalendarTool.check(env).availability)
    val spec = CalendarTool.buildInsertIntent(
      CalendarTool.EventParams("例會", 1_700_000_000_000, 1_700_000_360_000, "會議室"),
    )
    assertEquals("android.intent.action.INSERT", spec.action)
    assertEquals("content://com.android.calendar/events", spec.dataUri)
    assertEquals("例會", spec.extras["title"])
  }

  @Test fun calendar_queryWithoutGrant_needsPermission() {
    val env = fullEnv.copy(calendarReadGranted = false)
    val check = CalendarTool.checkQuery(env)
    assertEquals(Availability.DEGRADED, check.availability)
    assertEquals(ReasonCodes.NEED_PERMISSION, check.reasonCode)
    assertEquals(Availability.NATIVE, CalendarTool.checkQuery(fullEnv).availability)
  }

  @Test fun calendar_execute_insertsViaLauncher() {
    var launched: IntentSpec? = null
    val r = CalendarTool.execute(
      CalendarTool.EventParams("面試", 1000, 2000),
      fullEnv.copy(calendarReadGranted = false),
      IntentLauncher { launched = it; true },
    )
    assertTrue(r.ok)
    assertEquals("android.intent.action.INSERT", (launched?.action ?: ""))
  }

  // ---- 5. 音量 ----

  @Test fun volume_musicRaise_opMapping() {
    val op = VolumeTool.buildOp(VolumeTool.Params(VolumeTool.AudioStream.MUSIC, VolumeTool.Direction.RAISE))
    assertEquals(3, op.streamSdkValue)
    assertEquals(VolumeTool.Direction.RAISE, op.direction)
    assertEquals(Availability.NATIVE, VolumeTool.check(fullEnv).availability)
  }

  @Test fun volume_set_clampsToMax() {
    val op = VolumeTool.buildOp(
      VolumeTool.Params(VolumeTool.AudioStream.MUSIC, VolumeTool.Direction.SET, index = 99),
      maxVolume = 15,
    )
    assertEquals(15, op.clampedIndex)
  }

  @Test fun volume_fallback_opensPanel() {
    val fb = VolumeTool.fallback(
      VolumeTool.Params(VolumeTool.AudioStream.ALARM, VolumeTool.Direction.MUTE), fullEnv,
    )
    assertEquals("android.settings.SOUND_SETTINGS", (fb.alternative?.action ?: ""))
  }

  // ---- 6. 音樂 ----

  @Test fun music_control_mapsToMediaButton() {
    val spec = MusicTool.buildControlIntent(MusicTool.Control.NEXT)
    assertEquals("android.intent.action.MEDIA_BUTTON", spec.action)
    assertEquals("87", spec.extras["keyCode"])
    assertEquals(127, MusicTool.buildControl(MusicTool.Control.PAUSE).keyCode)
  }

  @Test fun music_play_mapsToViewIntent() {
    val spec = MusicTool.buildPlayIntent(MusicTool.Params(query = "周杰倫"))
    assertEquals("android.intent.action.VIEW", spec.action)
    assertTrue((spec.dataUri ?: ""), (spec.dataUri ?: "").startsWith("https://"))
  }

  @Test fun music_blankQuery_needsClarification() {
    val r = MusicTool.execute(MusicTool.Params(query = ""), fullEnv, okLauncher)
    assertFalse(r.ok)
    assertEquals(ReasonCodes.NEED_CLARIFICATION, r.reasonCode)
  }

  @Test fun music_noPlayer_degrades() {
    val env = fullEnv.copy(hasMusicApp = false)
    assertEquals(Availability.DEGRADED, MusicTool.check(env).availability)
    val fb = MusicTool.fallback(MusicTool.Params(query = "放鬆"), env)
    assertEquals(ReasonCodes.NO_HANDLER, fb.reasonCode)
  }
}
