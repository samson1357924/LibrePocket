package dev.librepocket.systemb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 組 Intent 映射測試：委託 / 預填 / 授權降級 + 拒絕零請求斷言。
 *
 * 拒絕零請求：凡 [CheckResult.availability] 為 UNAVAILABLE 的路徑，
 * [SystemBTool.execute] 必須返回 started=false + intent=null，
 * 且權限記錄器全程零請求。
 */
class SystemBMappingTest {

  private class RecordingSink : PermissionSink {
    val requests = mutableListOf<String>()
    override fun request(permission: String) {
      requests += permission
    }
  }

  private fun env(sink: RecordingSink, block: SystemBEnv.() -> SystemBEnv = { this }): SystemBEnv =
    SystemBEnv(permissionSink = sink).block()

  // ---- 郵件：Intent 預填，經系統 App ----

  @Test
  fun email_prefillsViaSystemApp() {
    val sink = RecordingSink()
    val args = mapOf("to" to "a@example.com", "subject" to "hi", "body" to "hello")
    val c = EmailTool.check(env(sink), args)
    assertEquals(Availability.NATIVE, c.availability)
    val e = EmailTool.execute(env(sink), args)
    assertTrue(e.started)
    assertTrue(e.viaSystemApp)
    assertNotNull(e.intent)
    assertEquals("android.intent.action.SENDTO", e.intent!!.action)
    assertTrue(e.intent!!.dataUri!!.startsWith("mailto:"))
    assertEquals("hi", e.intent!!.extras["subject"])
    assertEquals("hello", e.intent!!.extras["body"])
    assertTrue(sink.requests.isEmpty())
  }

  @Test
  fun email_missingArgs_deniedWithZeroRequest() {
    val sink = RecordingSink()
    val c = EmailTool.check(env(sink), emptyMap())
    assertEquals(Availability.UNAVAILABLE, c.availability)
    assertEquals(ReasonCode.MISSING_ARG, c.reason)
    val e = EmailTool.execute(env(sink), emptyMap())
    assertFalse(e.started)
    assertNull(e.intent)
    assertTrue(sink.requests.isEmpty())
    val f = EmailTool.fallback(c.reason, emptyMap())
    assertTrue(f.manualSteps.isNotEmpty())
  }

  // ---- 電話：僅 ACTION_DIAL ----

  @Test
  fun dial_usesActionDialOnly() {
    val sink = RecordingSink()
    val e = DialTool.execute(env(sink), mapOf("number" to "+886 912-345-678"))
    assertTrue(e.started)
    assertEquals("android.intent.action.DIAL", e.intent!!.action)
    assertEquals("tel:+886912345678", e.intent!!.dataUri)
    assertFalse((e.intent!!.action.contains("CALL") && e.intent!!.action.endsWith("_CALL")))
    assertTrue(sink.requests.isEmpty())
  }

  @Test
  fun dial_directCallUnsupported_deniedWithZeroRequest() {
    val sink = RecordingSink()
    val args = mapOf("number" to "0912345678", "directCall" to "true")
    val c = DialTool.check(env(sink), args)
    assertEquals(Availability.UNAVAILABLE, c.availability)
    assertEquals(ReasonCode.UNSUPPORTED, c.reason)
    val e = DialTool.execute(env(sink), args)
    assertFalse(e.started)
    assertNull(e.intent)
    assertTrue(sink.requests.isEmpty())
  }

  // ---- 簡訊：僅 SENDTO 預填，零 SMS 權限 ----

  @Test
  fun sms_prefillsEditor_neverRequestsSmsPermission() {
    val sink = RecordingSink()
    val e = SmsTool.execute(env(sink), mapOf("number" to "0912345678", "body" to "hi"))
    assertTrue(e.started)
    assertTrue(e.viaSystemApp)
    assertEquals("android.intent.action.SENDTO", e.intent!!.action)
    assertEquals("smsto:0912345678", e.intent!!.dataUri)
    assertEquals("hi", e.intent!!.extras["sms_body"])
    assertTrue(sink.requests.isEmpty())
    assertTrue(sink.requests.none { it in PlayCompliance.FORBIDDEN_PERMISSIONS })
  }

  @Test
  fun sms_missingNumber_deniedWithZeroRequest() {
    val sink = RecordingSink()
    val c = SmsTool.check(env(sink), emptyMap())
    assertEquals(ReasonCode.MISSING_ARG, c.reason)
    val e = SmsTool.execute(env(sink), emptyMap())
    assertFalse(e.started)
    assertNull(e.intent)
    assertTrue(sink.requests.isEmpty())
  }

  // ---- 通知：預設關，標題優先 ----

  @Test
  fun notification_defaultOff_degradedTitleVisible() {
    // MAJOR-1：缺 listener 即 DEGRADED/NO_PRIVILEGE（標題可見），與
    // ToolRegistry `notification.read` 投影一致；不再是 UNAVAILABLE。
    val sink = RecordingSink()
    val c = NotificationTool.check(env(sink), emptyMap())
    assertEquals(Availability.DEGRADED, c.availability)
    assertEquals(ReasonCode.NO_PRIVILEGE, c.reason)
    val e = NotificationTool.execute(env(sink), emptyMap())
    assertTrue(e.started)
    assertEquals("title_only", e.intent!!.extras["mode"])
    assertTrue(sink.requests.isEmpty())
    val f = NotificationTool.fallback(c.reason, emptyMap())
    assertTrue(f.userMessage.contains("NO_PRIVILEGE"))
    assertTrue(f.manualSteps.isNotEmpty())
  }

  @Test
  fun notification_enabled_titleOnlyByDefault() {
    val sink = RecordingSink()
    val on = env(sink) { copy(notificationListenerEnabled = true) }
    val c = NotificationTool.check(on, emptyMap())
    assertEquals(Availability.DEGRADED, c.availability)
    val titleOnly = NotificationTool.execute(on, emptyMap())
    assertTrue(titleOnly.started)
    assertEquals("title_only", titleOnly.intent!!.extras["mode"])
    // 未二次同意時即使要求全文也降為標題。
    val asked = NotificationTool.execute(on, mapOf("fullText" to "true"))
    assertEquals("title_only", asked.intent!!.extras["mode"])
    val consented = env(sink) { copy(notificationListenerEnabled = true, notificationFullTextConsented = true) }
    val full = NotificationTool.execute(consented, mapOf("fullText" to "true"))
    assertEquals("title_and_body", full.intent!!.extras["mode"])
    assertTrue(sink.requests.isEmpty())
  }

  // ---- 截圖：每次授權 ----

  @Test
  fun screenshot_withoutFreshGrant_deniedWithZeroRequest() {
    val sink = RecordingSink()
    val c = ScreenshotTool.check(env(sink), emptyMap())
    assertEquals(ReasonCode.NEEDS_FRESH_AUTH, c.reason)
    val e = ScreenshotTool.execute(env(sink), emptyMap())
    assertFalse(e.started)
    assertNull(e.intent)
    assertTrue(sink.requests.isEmpty())
  }

  @Test
  fun screenshot_withFreshGrant_singleShot() {
    val sink = RecordingSink()
    val granted = env(sink) { copy(screenshotFreshGrant = true) }
    val e = ScreenshotTool.execute(granted, emptyMap())
    assertTrue(e.started)
    assertNotNull(e.intent)
    assertEquals("true", e.intent!!.extras["singleShot"])
    assertTrue(sink.requests.isEmpty())
  }

  // ---- 聯繫人：僅委託選人 ----

  @Test
  fun contact_delegatesPicker_withoutReadContacts() {
    val sink = RecordingSink()
    val c = ContactTool.check(env(sink), emptyMap())
    assertEquals(Availability.NATIVE, c.availability)
    val e = ContactTool.execute(env(sink), emptyMap())
    assertTrue(e.started)
    assertEquals("android.intent.action.PICK", e.intent!!.action)
    assertTrue(sink.requests.isEmpty())
    assertTrue(sink.requests.none { it.contains("READ_CONTACTS") })
  }

  // ---- 位置：僅前台 ----

  @Test
  fun location_foregroundOnly() {
    val sink = RecordingSink()
    val e = LocationTool.execute(env(sink), emptyMap())
    assertTrue(e.started)
    assertEquals("foreground_single", e.intent!!.extras["mode"])
    assertTrue(sink.requests.none { it == PlayCompliance.ACCESS_BACKGROUND_LOCATION })
  }

  @Test
  fun location_background_blockedWithZeroRequest() {
    val sink = RecordingSink()
    val args = mapOf("background" to "true")
    val c = LocationTool.check(env(sink), args)
    assertEquals(Availability.UNAVAILABLE, c.availability)
    assertEquals(ReasonCode.FLAVOR_BLOCKED, c.reason)
    val e = LocationTool.execute(env(sink), args)
    assertFalse(e.started)
    assertNull(e.intent)
    assertTrue(sink.requests.isEmpty())
    val f = LocationTool.fallback(c.reason, args)
    assertTrue(f.userMessage.contains("FLAVOR_BLOCKED"))
  }

  // ---- 註冊表 + 全組拒絕零請求 ----

  @Test
  fun registry_containsAllTenTools() {
    val names = SystemBRegistry.names()
    assertEquals(
      listOf(
        "systemb.contact.get",
        "systemb.contact.list",
        "systemb.contact.pick",
        "systemb.contact.search",
        "systemb.email.compose",
        "systemb.location.foreground",
        "systemb.notification.titles",
        "systemb.phone.dial",
        "systemb.screenshot.capture",
        "systemb.sms.prefill",
      ),
      names,
    )
    names.forEach { assertNotNull(SystemBRegistry.get(it)) }
  }

  @Test
  fun allDeniedPaths_requestZeroPermissions() {
    val sink = RecordingSink()
    val e = env(sink)
    val deniedArgs: Map<String, Map<String, String>> = mapOf(
      "systemb.email.compose" to emptyMap(),
      "systemb.phone.dial" to mapOf("directCall" to "true", "number" to "0912345678"),
      "systemb.sms.prefill" to emptyMap(),
      // MAJOR-1：通知缺 listener 為 DEGRADED（標題可見），不在拒絕集合內，另測。
      "systemb.screenshot.capture" to emptyMap(),
      "systemb.location.foreground" to mapOf("background" to "true"),
    )
    deniedArgs.forEach { (name, args) ->
      val tool = SystemBRegistry.get(name)!!
      val c = tool.check(e, args)
      assertEquals("$name 應為 UNAVAILABLE", Availability.UNAVAILABLE, c.availability)
      val r = tool.execute(e, args)
      assertFalse("$name 拒絕時不得啟動", r.started)
      assertNull("$name 拒絕時不得產出 Intent", r.intent)
      val f = tool.fallback(c.reason, args)
      assertTrue("$name 降級需含手動步驟", f.manualSteps.isNotEmpty())
      assertTrue("$name 降級需引用原因碼", f.userMessage.contains(c.reason.name))
    }
    // 聯繫人委託路徑永遠可用，同樣零權限請求。
    val ce = SystemBRegistry.get("systemb.contact.pick")!!.execute(e, emptyMap())
    assertTrue(ce.started)
    assertTrue(sink.requests.isEmpty())
    assertTrue(sink.requests.none { it in PlayCompliance.FORBIDDEN_PERMISSIONS })
  }
}
