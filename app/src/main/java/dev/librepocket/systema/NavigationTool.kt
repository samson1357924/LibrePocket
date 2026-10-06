package dev.librepocket.systema

import java.net.URLEncoder

/**
 * 導航工具：geo Intent 優先，無地圖 App 時降級走網頁地圖。
 *
 * 兩風味皆完整（矩陣 §1 列 1）。不申請位置權限：導航本身免權限，
 * 只有「定位」才需前台定位授權，本工具不做定位。
 */
object NavigationTool : SystemTool<NavigationTool.Params> {

  data class Params(
    val query: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val label: String = "",
  )

  override val descriptor: ToolDescriptor
    get() = ToolRegistry.get(ToolRegistry.NAVIGATE)!!

  override fun check(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉導航快通道")
    }
    return if (env.hasMapApp) {
      CheckResult.native("geo Intent 可用")
    } else {
      CheckResult.degraded(ReasonCodes.NO_HANDLER, "無地圖 App，降級走網頁地圖")
    }
  }

  /** 有地圖 App → geo；否則 → 網頁。呼叫方也可直接用 [fallback] 取網頁分支。 */
  fun resolve(params: Params, env: SystemEnv): IntentSpec =
    if (env.hasMapApp) buildGeoIntent(params) else buildWebIntent(params)

  fun buildGeoIntent(params: Params): IntentSpec {
    val data = if (params.latitude != null && params.longitude != null) {
      val base = "geo:${params.latitude},${params.longitude}"
      if (params.label.isNotBlank()) "$base?q=${encode(params.label)}" else base
    } else {
      "geo:0,0?q=${encode(params.query)}"
    }
    return IntentSpec(action = IntentActions.VIEW, dataUri = data)
  }

  fun buildWebIntent(params: Params): IntentSpec {
    val q = when {
      params.query.isNotBlank() -> params.query
      params.label.isNotBlank() -> params.label
      params.latitude != null && params.longitude != null ->
        "${params.latitude},${params.longitude}"
      else -> ""
    }
    return IntentSpec(
      action = IntentActions.VIEW,
      dataUri = "https://www.openstreetmap.org/search?query=${encode(q)}",
    )
  }

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到導航（${check.reasonCode}）")
    }
    val spec = resolve(params, env)
    val ok = runCatching { launcher.launch(spec) }.getOrDefault(false)
    return if (ok) {
      ToolResult(true, !env.hasMapApp, spec, check.reasonCode)
    } else {
      ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到導航（${ReasonCodes.NO_HANDLER}）")
    }
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec {
    val web = buildWebIntent(params)
    return FallbackSpec(
      reasonCode = ReasonCodes.NO_HANDLER,
      alternative = web,
      userMessage = "做不到開啟地圖 App（${ReasonCodes.NO_HANDLER}）" +
        "→ 可替代網頁地圖 → 需要你連網後在瀏覽器中查看路線",
    )
  }

  private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
}
