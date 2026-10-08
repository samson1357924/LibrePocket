package dev.librepocket.chat

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FailingClock : Clock() {
  private val zone = ZoneId.of("Asia/Taipei")
  override fun getZone(): ZoneId = zone
  override fun withZone(zone: ZoneId): Clock = this
  override fun instant(): Instant = throw IllegalStateException("clock boom")
}

class RuntimeTimeContextTest {

  private fun fixedTaipei(): Clock =
    Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("Asia/Taipei"))

  @Test fun fixedClockRendersTaipeiWallTime() {
    val text = buildRuntimeTimeContext(fixedTaipei(), userTimezone = "Asia/Taipei")
    assertTrue(text.startsWith("Runtime time context:"))
    // 2026-10-09 is a Friday; weekday is hardcoded, never derived from a formatter.
    assertTrue(text.contains("- Current time: 2026-10-09 Fri 09:41:22 Asia/Taipei (UTC+08:00)"))
    assertTrue(text.contains("- User timezone: Asia/Taipei"))
  }

  @Test fun secondPrecisionTruncatesNanos() {
    val now = ZonedDateTime.of(2026, 10, 9, 9, 41, 22, 123456789, ZoneId.of("Asia/Taipei"))
    val text = buildRuntimeTimeContext(now, userTimezone = "Asia/Taipei")
    assertTrue(text.contains("09:41:22 "))
    assertFalse(text.contains("09:41:22."))
  }

  @Test fun utcZeroOffsetNormalizesToNumeric() {
    val now = ZonedDateTime.of(2026, 10, 9, 1, 41, 22, 0, ZoneId.of("UTC"))
    val text = buildRuntimeTimeContext(now, userTimezone = "UTC")
    assertTrue(text.contains("- Current time: 2026-10-09 Fri 01:41:22 UTC (UTC+00:00)"))
    assertTrue(text.contains("+00:00"))
    assertFalse(text.contains(" Z"))
    assertFalse(text.contains("(UTCZ)"))
  }

  @Test fun resolveZoneBlankAndInvalidFallBack() {
    assertEquals(ZoneId.systemDefault(), resolveZone(null))
    assertEquals(ZoneId.systemDefault(), resolveZone("  "))
    assertEquals(ZoneId.systemDefault(), resolveZone("Mars/Olympus"))
    assertEquals(ZoneId.of("Asia/Taipei"), resolveZone("Asia/Taipei"))
  }

  @Test fun emptyStringTimezoneFallsBackToClockZone() {
    val text = buildRuntimeTimeContext(fixedTaipei(), userTimezone = "")
    assertTrue(text.contains("Asia/Taipei (UTC+08:00)"))
    assertFalse(text.contains("(time unknown)"))
  }

  @Test fun paddedTimezoneIsTrimmed() {
    assertEquals(ZoneId.of("Asia/Taipei"), resolveZone("  Asia/Taipei  "))
    val text = buildRuntimeTimeContext(fixedTaipei(), userTimezone = "  Asia/Taipei  ")
    assertTrue(text.contains("- Current time: 2026-10-09 Fri 09:41:22 Asia/Taipei (UTC+08:00)"))
    assertTrue(text.contains("- User timezone: Asia/Taipei"))
  }

  @Test fun invalidTimezoneFallsBackWithoutThrowing() {
    val text = buildRuntimeTimeContext(fixedTaipei(), userTimezone = "Mars/Olympus")
    assertTrue(text.contains(ZoneId.systemDefault().id))
    assertTrue(text.contains("- Current time: "))
    assertFalse(text.contains("(time unknown)"))
  }

  @Test fun newYorkDstBoundaryShiftsOffset() {
    val zone = "America/New_York"
    val before = buildRuntimeTimeContext(
      Clock.fixed(Instant.parse("2026-03-08T06:59:59Z"), ZoneId.of(zone)),
      userTimezone = zone,
    )
    val after = buildRuntimeTimeContext(
      Clock.fixed(Instant.parse("2026-03-08T07:00:00Z"), ZoneId.of(zone)),
      userTimezone = zone,
    )
    assertTrue(before.contains(zone))
    assertTrue(after.contains(zone))
    assertTrue(before.contains("-05:00"))
    assertTrue(after.contains("-04:00"))
  }

  @Test fun failingClockYieldsUnknownMarker() {
    assertEquals("Runtime time context: (time unknown)", buildRuntimeTimeContext(FailingClock()))
  }

  @Test fun nullSessionStartOmitsSessionLine() {
    val now = ZonedDateTime.of(2026, 10, 9, 9, 41, 22, 0, ZoneId.of("Asia/Taipei"))
    val text = buildRuntimeTimeContext(now, userTimezone = "Asia/Taipei", sessionStart = null)
    assertFalse(text.contains("Session started"))
  }

  @Test fun clockOverloadNullSessionStartOmitsSessionLine() {
    val text = buildRuntimeTimeContext(
      fixedTaipei(),
      userTimezone = "Asia/Taipei",
      sessionStart = null,
    )
    assertTrue(text.contains("- Current time: "))
    assertFalse(text.contains("Session started"))
  }

  @Test fun sessionStartRendersWhenPresent() {
    val now = ZonedDateTime.of(2026, 10, 9, 9, 41, 22, 0, ZoneId.of("Asia/Taipei"))
    val start = ZonedDateTime.of(2026, 10, 9, 8, 0, 2, 0, ZoneId.of("Asia/Taipei"))
    val text = buildRuntimeTimeContext(now, userTimezone = "Asia/Taipei", sessionStart = start)
    // Session line reuses the Current-line format: weekday + numeric offset.
    assertTrue(text.contains("- Session started: 2026-10-09 Fri 08:00:02 Asia/Taipei (UTC+08:00)"))
  }

  @Test fun sessionStartCrossZoneConvertsToUserZone() {
    val now = ZonedDateTime.of(2026, 10, 9, 9, 41, 22, 0, ZoneId.of("Asia/Taipei"))
    val startUtc = ZonedDateTime.of(2026, 10, 9, 0, 0, 2, 0, ZoneId.of("UTC"))
    // Sanity: the UTC instant maps to the Taipei wall clock via withZoneSameInstant.
    assertEquals(8, startUtc.withZoneSameInstant(ZoneId.of("Asia/Taipei")).hour)
    val text = buildRuntimeTimeContext(now, userTimezone = "Asia/Taipei", sessionStart = startUtc)
    assertTrue(text.contains("- Session started: 2026-10-09 Fri 08:00:02 Asia/Taipei (UTC+08:00)"))
  }

  @Test fun clockOverloadCarriesSessionInstant() {
    val text = buildRuntimeTimeContext(
      fixedTaipei(),
      userTimezone = "Asia/Taipei",
      sessionStart = Instant.parse("2026-10-09T00:00:02Z"),
    )
    assertTrue(text.contains("- Session started: 2026-10-09 Fri 08:00:02 Asia/Taipei (UTC+08:00)"))
  }

  @Test fun userTimezoneLineIsExactAndOffsetConsistent() {
    val text = buildRuntimeTimeContext(fixedTaipei(), userTimezone = "Asia/Taipei")
    val lines = text.split("\n")
    assertTrue(lines.contains("- User timezone: Asia/Taipei"))
    assertTrue(
      lines.any { it.startsWith("- Current time: ") && it.contains("Asia/Taipei (UTC+08:00)") },
    )
  }
}
