package dev.librepocket.chat

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm:ss", Locale.US)
private val offsetFormat = DateTimeFormatter.ofPattern("XXX", Locale.US)

private const val UNKNOWN_BLOCK = "Runtime time context: (time unknown)"

/**
 * User timezone resolution, fail-closed to the system default. Never throws.
 *
 * A blank/invalid [userTimezone] falls back to [ZoneId.systemDefault()]. If even the system
 * default lookup fails, UTC is used as the last-resort保底 so callers always get a usable zone.
 *
 * Observability: this helper stays JVM-pure (no `android.util.Log`) so it can be unit-tested on
 * the JVM. Fallback/unknown 時 caller（Phase 2 TurnController）必須 Log.w。Caller
 * detectability: this function is `internal` so Phase 2 TurnController (same module) can
 * detect fallback via `isTimezoneFallback(requested)` — true only when a non-blank id
 * fails `ZoneId.of(requested.trim())` (a successful parse, even a normalized one such as
 * `UTC+8` → `UTC+08:00`, is not a fallback); when fallback is true (or
 * the block renders as `(time unknown)`), TurnController must emit `Log.w` with the requested id
 * and the zone actually used.
 */
internal fun resolveZone(userTimezone: String?): ZoneId {
  if (!userTimezone.isNullOrBlank()) {
    try {
      return ZoneId.of(userTimezone.trim())
    } catch (_: Exception) {
      // Fall through to the system default.
    }
  }
  return try {
    ZoneId.systemDefault()
  } catch (_: Exception) {
    // 雙重失敗保底: systemDefault 本身都取不到時用 UTC，維持 fail-closed 且 never-throws。
    ZoneId.of("UTC")
  }
}

/**
 * Shared timestamp renderer. Current and Session lines reuse this exact logic so both carry
 * weekday (EEE) plus a numeric offset (`XXX` normalized: `Z` → `+00:00`), avoiding cross-DST
 * offset ambiguity.
 */
private fun formatTimestamp(local: ZonedDateTime, zone: ZoneId): String {
  val offset = try {
    local.format(offsetFormat).let { if (it == "Z") "+00:00" else it }
  } catch (_: Exception) {
    local.offset.id.let { if (it == "Z") "+00:00" else it }
  }
  return local.format(timestampFormat) + " " + zone.id + " (UTC" + offset + ")"
}

/**
 * Shared block assembler over an already-resolved [zone].
 *
 * Misleading-label guard: [now] is converted via `withZoneSameInstant(zone)`; if that conversion
 * fails the whole block degrades to `(time unknown)` instead of reusing the unconverted [now]
 * while claiming `zone.id`. A [sessionStart] conversion failure only omits the Session line; the
 * Current lines are kept.
 */
private fun buildWithZone(now: ZonedDateTime, zone: ZoneId, sessionStart: ZonedDateTime?): String {
  val local = try {
    now.withZoneSameInstant(zone)
  } catch (_: Exception) {
    return UNKNOWN_BLOCK
  }
  val lines = ArrayList<String>(4)
  lines.add("Runtime time context:")
  lines.add("- Current time: " + formatTimestamp(local, zone))
  lines.add("- User timezone: " + zone.id)
  if (sessionStart != null) {
    try {
      lines.add("- Session started: " + formatTimestamp(sessionStart.withZoneSameInstant(zone), zone))
    } catch (_: Exception) {
      // Omit the Session line rather than stamping a wrong zone/clock on it.
    }
  }
  return lines.joinToString("\n")
}

/**
 * Pure runtime clock block for the model. Second precision, no fractional seconds. Never throws:
 * any failure yields `"Runtime time context: (time unknown)"`.
 *
 * Observability: JVM-pure (no `android.util.Log`); fallback/unknown 時 caller（Phase 2
 * TurnController）必須 Log.w（detection方式見 [resolveZone] KDoc；unknown block 以
 * `"(time unknown)"` 子字串判斷）。
 *
 * Phase 2 契約建議（此處僅註明選項，不實作 TurnController）：当 block 為 `(time unknown)` 時，
 * TurnController 應照發（讓模型知道時間未知，而非靜默用舊時間）或省略，二選一由 Phase 2 定案。
 * 本 helper 明確 never-throws 且回傳字串可直接用。
 *
 * 時區新鮮度：未顯式指定 [userTimezone]（null/blank）時，每次呼叫都經由 [zoneSupplier]
 * 重新讀取系統時區，而非沿用 [now] 建構時快照的 zone。Caller 若傳入的 [now] 其 zone
 * 已是過期快照，仍會被 [zoneSupplier] 的 fresh zone 覆寫（[now] 只供 instant）。
 * [zoneSupplier] 抛異常時 fallback 到 [resolveZone]（null）再到 UTC 保底。
 */
fun buildRuntimeTimeContext(
  now: ZonedDateTime,
  userTimezone: String? = null,
  sessionStart: ZonedDateTime? = null,
  zoneSupplier: () -> ZoneId = ZoneId::systemDefault,
): String {
  return try {
    val zone = if (userTimezone.isNullOrBlank()) {
      try {
        zoneSupplier()
      } catch (_: Exception) {
        resolveZone(null)
      }
    } else {
      resolveZone(userTimezone)
    }
    buildWithZone(now, zone, sessionStart)
  } catch (_: Exception) {
    UNKNOWN_BLOCK
  }
}

/**
 * Clock-based overload. Any failure yields the unknown marker, never throws.
 *
 * 時區新鮮度：未顯式指定 [userTimezone]（null/blank）時，每 turn 都經由 [zoneSupplier]
 * 重新讀取 `ZoneId.systemDefault()`，而非沿用 [clock] 建構時快照的 `clock.zone`。
 * [clock] 僅供 instant（保留 `clock.withZone(zone)` tick）；[zoneSupplier] 抛異常時
 * fallback 到 [resolveZone]（null）再到 UTC 保底（沿用現有保底）。
 *
 * The already-resolved [ZoneId] is passed straight into the shared assembler — never
 * round-tripped through `zone.id` back into [resolveZone]/`withZoneSameInstant` — so fixed-offset
 * ids cannot re-resolve ambiguously.
 *
 * Observability 與 Phase 2 契約同 pure overload：JVM-pure；fallback/unknown 時 caller（Phase 2
 * TurnController）必須 Log.w；`(time unknown)` 照發或省略由 Phase 2 定案，此處僅保證回傳字串
 * 可直接用。
 */
fun buildRuntimeTimeContext(
  clock: Clock,
  userTimezone: String? = null,
  sessionStart: Instant? = null,
  zoneSupplier: () -> ZoneId = ZoneId::systemDefault,
): String {
  return try {
    val zone = if (userTimezone.isNullOrBlank()) {
      try {
        zoneSupplier()
      } catch (_: Exception) {
        resolveZone(null)
      }
    } else {
      resolveZone(userTimezone)
    }
    val ticked = try {
      clock.withZone(zone)
    } catch (_: Exception) {
      clock
    }
    val now = ZonedDateTime.now(ticked)
    val start = try {
      sessionStart?.atZone(zone)
    } catch (_: Exception) {
      null
    }
    buildWithZone(now, zone, start)
  } catch (_: Exception) {
    UNKNOWN_BLOCK
  }
}
