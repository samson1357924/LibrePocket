package dev.librepocket.systema

/**
 * A 組快通道的能力投影原語（純 JVM，無 Android 依賴）。
 *
 * 對齊 CAPABILITY_MATRIX §1/§5：每個工具的 [SystemTool.check] 都回傳
 * [CheckResult]，攜帶 [Availability] + 原因碼，供模型生成降級話術：
 * `做不到 X（原因碼）→ 可替代 Y → 需要你做 Z`。
 */
enum class Flavor { PLAY, FULL }

enum class Availability { NATIVE, DEGRADED, UNAVAILABLE }

/** 副作用分級（ARCH §5.2）：READ 可重試；WRITE 需確認/冪等；PRIVILEGED 走系統接管。 */
enum class SideEffectLevel { READ, WRITE, PRIVILEGED }

/** 原因碼封閉集合（矩陣 §5 + 路由補充），禁止模型自由發揮。 */
object ReasonCodes {
  const val FLAVOR_BLOCKED = "FLAVOR_BLOCKED"
  const val NO_PRIVILEGE = "NO_PRIVILEGE"
  const val USER_DISABLED = "USER_DISABLED"
  const val NO_HANDLER = "NO_HANDLER"
  const val NEED_PERMISSION = "NEED_PERMISSION"
  const val NEED_CLARIFICATION = "NEED_CLARIFICATION"
}

/**
 * 執行期能力快照。純資料，便於單元測試窮舉投影分支。
 *
 * @param userEnabled 該類別的使用者開關（預設開）。
 * @param hasMapApp 是否有可處理 geo Intent 的地圖 App（resolveActivity 結果的純側映射）。
 * @param installedPackages 可見已安裝包（受 manifest `<queries>` 白名單限制的可見子集）。
 * @param hasAlarmApp 是否有處理 SET_ALARM 的系統鬧鐘 App。
 * @param hasCalendarApp 是否有處理日曆 INSERT 的系統日曆 App。
 * @param calendarReadGranted READ_CALENDAR 是否已授權（僅 Provider 直查需要）。
 * @param exactAlarmGranted 精準鬧鐘是否可用（自有提醒降級路徑）。
 * @param hasMusicApp 是否有可處理媒體 Intent 的播放器。
 */
data class SystemEnv(
  val flavor: Flavor = Flavor.PLAY,
  val userEnabled: Boolean = true,
  val hasMapApp: Boolean = true,
  val installedPackages: Set<String> = emptySet(),
  val hasAlarmApp: Boolean = true,
  val hasCalendarApp: Boolean = true,
  val calendarReadGranted: Boolean = false,
  val exactAlarmGranted: Boolean = false,
  val hasMusicApp: Boolean = true,
)

data class CheckResult(
  val availability: Availability,
  val reasonCode: String? = null,
  val detail: String = "",
) {
  val executable: Boolean get() = availability != Availability.UNAVAILABLE

  companion object {
    fun native(detail: String = "") = CheckResult(Availability.NATIVE, null, detail)
    fun degraded(reasonCode: String, detail: String = "") =
      CheckResult(Availability.DEGRADED, reasonCode, detail)
    fun unavailable(reasonCode: String, detail: String = "") =
      CheckResult(Availability.UNAVAILABLE, reasonCode, detail)
  }
}
