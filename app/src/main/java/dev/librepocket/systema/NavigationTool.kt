package dev.librepocket.systema

import java.net.URLEncoder

/**
 * 導航工具：geo Intent 優先，無地圖 App 時降級走網頁地圖。
 *
 * Status: Target（純側契約，未接線未 E2E）。
 * 不申報位置權限：導航本身免權限，
 * 只有「定位」才需前台定位授權，本工具不做定位。
 *
 * - 可見性（#14）：主 manifest `<queries>` 已宣告 VIEW geo/https scheme-only
 *   （targetSdk 36 下 resolve-first launcher 不被過濾成假 NO_HANDLER）；
 *   真機 handler E2E 仍 UNVERIFIED。
 * - query 形狀為刻意近似：`geo:lat,lng?q=label` 非官式合成
 *   `geo:0,0?q=lat,lng(label)`（括號需轉義）；多數地圖 App 以「座標＋搜尋詞」
 *   語意照收，保守起見保留現行形狀並以測試鎖定。
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
    // fail-closed：resolve 的座標守衛 IAE（單側座標/越界）收斂為 INTERNAL，不穿透
    //（收斂點 [guardedToolBuild]：話術由呼叫方提供，訊息與原 inline catch 逐字相同）。
    val internalFailure =
      ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到導航（${ReasonCodes.INTERNAL}）")
    val spec = when (val build = guardedToolBuild(internalFailure) { resolve(params, env) }) {
      is Guarded.Internal -> return build.result
      is Guarded.Built -> build.value
    }
    return when (val launched = launcher.launchCatching(spec)) {
      // 發現式缺席（靜態有地圖但發射無處理者：被移除/停用/不可見）與靜態
      // DEGRADED 同語義：走 [fallback] 網頁地圖話術（usedFallback=true，spec 保留）。
      LaunchResult.NoHandler -> ToolResult(
        false, true, spec, ReasonCodes.NO_HANDLER, fallback(params, env).userMessage,
      )
      // Success/Internal 走共用收斂（成功時以 usedFallbackOnSuccess 標記靜態 DEGRADED）。
      else -> launched.toToolResult(
        spec, "導航",
        usedFallbackOnSuccess = !env.hasMapApp, successReason = check.reasonCode,
      )
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

  /**
   * 編碼鎖定（刻意選擇，非偏差）：`URLEncoder`（空格→`+`）——官方 geo URI
   * 範例明載 `+` 為允許的空格編碼；`&`、`#`、`?` 亦一併跳脫，查詢串安全。
   * 改用 `Uri.encode`（空格→`%20`）牽涉 Android 層且影響純 JVM 可測性，
   * 故加註 + 測試鎖定現行為（見 `navigation_labelEncoding_locksUrlEncoderPlus`）。
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
