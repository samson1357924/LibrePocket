package dev.librepocket.chat

import java.time.Clock
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 誤報修正：[isTimezoneFallback] 只以 `ZoneId.of(trimmed)` 解析成功/失敗
 * 判定 fallback。合法但會被正規化的 id（`UTC+8` → `UTC+08:00` 等）解析成功，
 * 不得視為 fallback（舊 `resolveZone(id).id != requested` 比對會誤報）。
 */
class TurnControllerTimezoneFallbackTest {

  @Test fun normalizedButValidZonesAreNotFallback() {
    val valid = listOf(
      "UTC+8", "GMT+8", "UT+8", "UTC+0800", "+8",
      "UT", "GMT", "UTC", "Z",
      "+08", "+0800", "+08:00", "UTC+08", "GMT+08:00", "UT+08:00",
      "  UTC+8  ",
    )
    for (tz in valid) {
      assertFalse("expected no fallback for normalized-but-valid [$tz]", isTimezoneFallback(tz))
    }
  }

  @Test fun verbatimZonesAreNotFallback() {
    for (tz in listOf("Asia/Taipei", "UTC+08:00", "  Asia/Taipei  ")) {
      assertFalse("expected no fallback for verbatim [$tz]", isTimezoneFallback(tz))
    }
  }

  @Test fun blankRequestsAreNotFallback() {
    assertFalse(isTimezoneFallback(null))
    assertFalse(isTimezoneFallback(""))
    assertFalse(isTimezoneFallback("   "))
  }

  @Test fun unparsableZonesAreFallback() {
    val invalid = listOf(
      "Mars/Olympus",
      "Mars/Olympus_Mons",
      "UTC+8:00",
      "utc",
      "asia/taipei",
    )
    for (tz in invalid) {
      assertTrue("expected fallback for [$tz]", isTimezoneFallback(tz))
      // 沿用既有 invalidTimezone 語義：fallback 不 crash、不 render unknown。
      assertEquals(ZoneId.systemDefault(), resolveZone(tz))
      val text = buildRuntimeTimeContext(Clock.systemUTC(), userTimezone = tz)
      assertFalse(text.contains("(time unknown)"))
    }
  }
}
