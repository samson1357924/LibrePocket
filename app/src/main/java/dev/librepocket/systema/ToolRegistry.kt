package dev.librepocket.systema

/**
 * 快通道工具契約：每個 A 組工具都實現 check / execute / fallback 三件套。
 *
 * @param P 該工具的參數型別（純資料）。
 */
interface SystemTool<P> {
  val descriptor: ToolDescriptor
  fun check(env: SystemEnv): CheckResult
  fun execute(params: P, env: SystemEnv, launcher: IntentLauncher): ToolResult
  fun fallback(params: P, env: SystemEnv): FallbackSpec
}

data class ToolDescriptor(
  val name: String,
  val level: SideEffectLevel,
  /** 單次執行的超時毫秒（ARCH §9.1：每個快通道工具宣告超時）。 */
  val timeoutMs: Long,
  val description: String = "",
)

/**
 * 工具註冊表。A 組 6 項在此註冊，FastRouter 按名取用。
 * 原創命名：`system.*` 前綴保留給快通道系統操作。
 */
object ToolRegistry {
  const val NAVIGATE = "system.navigate"
  const val LAUNCH_APP = "system.launch_app"
  const val SET_ALARM = "system.set_alarm"
  const val CALENDAR = "system.calendar"
  const val VOLUME = "system.volume"
  const val MUSIC = "system.music"

  private val descriptors = linkedMapOf<String, ToolDescriptor>()

  init {
    register(ToolDescriptor(NAVIGATE, SideEffectLevel.READ, 10_000, "導航：geo Intent，降級走網頁地圖"))
    register(ToolDescriptor(LAUNCH_APP, SideEffectLevel.READ, 10_000, "開 App：launchIntent，模糊名澄清"))
    register(ToolDescriptor(SET_ALARM, SideEffectLevel.WRITE, 10_000, "鬧鐘：ALARM_CLOCK Intent，降級自有提醒"))
    register(ToolDescriptor(CALENDAR, SideEffectLevel.WRITE, 15_000, "日曆：Provider 查 + INSERT 委託優先"))
    register(ToolDescriptor(VOLUME, SideEffectLevel.WRITE, 5_000, "音量：AudioManager 公開 API"))
    register(ToolDescriptor(MUSIC, SideEffectLevel.WRITE, 10_000, "音樂：媒體 Intent + MediaSession 前台"))
  }

  fun register(descriptor: ToolDescriptor) {
    descriptors[descriptor.name] = descriptor
  }

  fun get(name: String): ToolDescriptor? = descriptors[name]

  fun all(): List<ToolDescriptor> = descriptors.values.toList()

  fun names(): Set<String> = descriptors.keys.toSet()
}
