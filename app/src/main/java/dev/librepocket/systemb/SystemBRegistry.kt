package dev.librepocket.systemb

/**
 * B 組工具註冊表（原創）。FastRouter 在裝配提示前查詢此表；
 * 能力投影按風味 + 使用者開關过滤後再暴露給模型。
 *
 * S1-C：全量聯繫人三工具（search/list/get，foss/github 限定）與
 * 既有委託選人（pick，免權限）共存；play 下全量一律 FLAVOR_BLOCKED。
 */
object SystemBRegistry {
  private val tools: Map<String, SystemBTool> = listOf(
    EmailTool,
    DialTool,
    SmsTool,
    NotificationTool,
    ScreenshotTool,
    ContactTool,
    ContactSearchTool,
    ContactListTool,
    ContactGetTool,
    LocationTool,
  ).associateBy { it.name }

  /** 全部已註冊工具名（排序後穩定輸出，便於快照審計）。 */
  fun names(): List<String> = tools.keys.sorted()

  fun get(name: String): SystemBTool? = tools[name]

  /** 本輪可見工具子集：通知預設關等由各工具 check() 的 env 快照決定。 */
  fun visibleTools(env: SystemBEnv, args: Map<String, Map<String, String>> = emptyMap()): Map<String, CheckResult> =
    tools.mapValues { (name, tool) -> tool.check(env, args[name] ?: emptyMap()) }
}
