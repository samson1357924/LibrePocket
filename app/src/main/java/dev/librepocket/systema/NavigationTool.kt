package dev.librepocket.systema

import java.net.URLEncoder

/**
 * 導航工具：geo Intent 優先，無地圖 App 時降級走網頁地圖。
 *
 * 三風味皆完整（矩陣 §1 列 1）。不申請位置權限：導航本身免權限，
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
    val lat = params.latitude
    val lng = params.longitude
    requirePairedCoords(lat, lng)
    if (lat != null && lng != null) requireLatLng(lat, lng)
    val data = if (lat != null && lng != null) {
      val base = "geo:$lat,$lng"
      if (params.label.isNotBlank()) "$base?q=${encode(params.label)}" else base
    } else {
      "geo:0,0?q=${encode(params.query)}"
    }
    return IntentSpec(action = IntentActions.VIEW, dataUri = data)
  }

  fun buildWebIntent(params: Params): IntentSpec {
    requirePairedCoords(params.latitude, params.longitude)
    val lat = params.latitude
    val lng = params.longitude
    // 與 geo 一致：web 分支同樣收斂座標守衛（有限值 + 範圍），禁越界座標經 OSM URL 外洩。
    if (lat != null && lng != null) requireLatLng(lat, lng)
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
    return launcher.launchCatching(spec).toToolResult(
      spec, "導航",
      usedFallbackOnSuccess = !env.hasMapApp, successReason = check.reasonCode,
    )
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

  /**
   * 編碼偏差（已鎖定現行，不硬改）：此處用 `URLEncoder`（空格→`+`），
   * 與 `Uri.encode`（空格→`%20`）不同；改動牽涉 Android 層且影響純 JVM 可測性，
   * 故僅加註 + 測試鎖定現行為（見 `navigation_labelEncoding_locksUrlEncoderPlus`）。
   */
  private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

  /** 單側座標 fail-fast：經緯度須同時為空或同時非空，禁靜默丟棄。 */
  private fun requirePairedCoords(lat: Double?, lng: Double?) {
    require((lat == null) == (lng == null)) { "latitude/longitude must both be null or both non-null" }
  }

  /** geo 座標守衛：有限值 + 緯度 -90..90 + 經度 -180..180。 */
  private fun requireLatLng(lat: Double, lng: Double) {
    require(lat.isFinite() && lng.isFinite()) { "lat/lng must be finite" }
    require(lat in -90.0..90.0) { "latitude out of range: $lat" }
    require(lng in -180.0..180.0) { "longitude out of range: $lng" }
  }
}
