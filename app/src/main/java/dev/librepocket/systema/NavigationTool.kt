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
 *   語意照收，保守起見保留現行形狀並以測試鎖定。兩分支（座標存在／無座標）
 *   的 q 來源優先序皆為 label → query（空白 label 時以非空 query 補位，理由
 *   見 [buildGeoIntent] 註解；禁靜默縮減合法輸入）。
 * - query/label 長度上限（[MAX_QUERY_LEN]=500，間隔註記見 IntentSpec KDoc 長度
 *   政策）為本層自訂 Binder 傳輸防禦（官方契約無此上限）：兩個 builder 端
 *   fail-closed 拒絕，越界經 [execute] 收斂 INTERNAL；上限以**編碼前欄位長度**
 *   計（`URLEncoder` 後 URI 可更長，與 [AlarmTool] 欄位級上限同政策）。
 * - 空白目的地（query/label 空白且經緯**雙側**皆缺）回 NEED_CLARIFICATION
 *   （[execute]）而非構建無意義的空搜尋 intent；與 [MusicTool] 的空白 query
 *   澄清同契約，即使靜態 `check` 已判 NATIVE/DEGRADED 亦然（目的缺席是使用者
 *   意圖問題，與環境能力無關）。單側經/緯不屬「空白目的地」：那是配對破損
 *   （[requirePairedCoords] 禁靜默丟棄），經 [resolve] 收斂 INTERNAL——與
 *   latitude-only 同方向，不以澄清話術偽裝成「缺目的地資訊」。
 */
object NavigationTool : SystemTool<NavigationTool.Params> {

  /** Binder 傳輸上限防禦：query/label 過長在 builder 即拒（本層自訂政策，
   *  非官方契約；與 [AlarmTool.MAX_MESSAGE_LEN] 同值同源，IntentSpec KDoc 同步註記）。 */
  const val MAX_QUERY_LEN = 500

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
    require(params.query.length <= MAX_QUERY_LEN) { "query too long: ${params.query.length} > $MAX_QUERY_LEN" }
    require(params.label.length <= MAX_QUERY_LEN) { "label too long: ${params.label.length} > $MAX_QUERY_LEN" }
    val data = if (lat != null && lng != null) {
      val base = "geo:$lat,$lng"
      // q 來源優先序（座標存在時）：label → query——兩者皆已過長度校驗，label
      // 空白而 query 非空時靜默丟棄 query 等於縮減使用者已完整提供的輸入（與
      // [requirePairedCoords] 的「單側座標 fail-fast：禁靜默丟棄」同伴原則同
      // 方向）；兩者皆空白才回裸座標（禁構建空 q 的無意義查詢）。
      val q = if (params.label.isNotBlank()) params.label else params.query
      if (q.isNotBlank()) "$base?q=${encode(q)}" else base
    } else {
      // q 來源優先序（無座標時）：label → query——與座標分支**同一優先序**
      //（label 非空即採用：label 是地圖搜尋的地名語意；與 [buildWebIntent]
      // 的 query 優先差異為刻意決策——web 是句子查詢，q 取整句；geo 的 q
      // 是地圖搜尋詞，label 即地名）。兩分支同一契約：禁靜默縮減合法輸入
      //（label-only 舊行為會丟 label 發射空 q 的無意義查詢）。
      // 兩者皆空白時不造空 q：fail-fast IAE，經 [guardedToolBuild] 收斂
      // INTERNAL——[execute] 的空白前置守衛先攔（NEED_CLARIFICATION），
      // 此 INTERNAL 分支不可達屬故意縱深（與座標分支「裸座標不造空 q」同向）。
      val q = params.label.ifBlank { params.query }
      require(q.isNotBlank()) { "query/label must not both be blank" }
      "geo:0,0?q=${encode(q)}"
    }
    return IntentSpec(action = IntentActions.VIEW, dataUri = data)
  }

  fun buildWebIntent(params: Params): IntentSpec {
    requirePairedCoords(params.latitude, params.longitude)
    val lat = params.latitude
    val lng = params.longitude
    // 與 geo 一致：web 分支同樣收斂座標守衛（有限值 + 範圍），禁越界座標經 OSM URL 外洩。
    if (lat != null && lng != null) requireLatLng(lat, lng)
    require(params.query.length <= MAX_QUERY_LEN) { "query too long: ${params.query.length} > $MAX_QUERY_LEN" }
    require(params.label.length <= MAX_QUERY_LEN) { "label too long: ${params.label.length} > $MAX_QUERY_LEN" }
    val q = when {
      params.query.isNotBlank() -> params.query
      params.label.isNotBlank() -> params.label
      params.latitude != null && params.longitude != null ->
        "${params.latitude},${params.longitude}"
      else -> ""
    }
    // 兩者皆空白且無座標時不造空查詢 URL（`?query=` 空搜尋對使用者毫無意義，
    // 禁靜默組空查詢）：fail-fast IAE，經 [guardedToolBuild] 收斂 INTERNAL——
    // [execute] 的空白前置守衛先攔（NEED_CLARIFICATION），此 INTERNAL 分支
    // 不可達屬故意縱深、直呼 builder 才及（與 [buildGeoIntent] 無座標分支的
    // all-blank require 同式樣同訊息）。
    require(q.isNotBlank()) { "query/label must not both be blank" }
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
    // 空白目的地禁猜測執行（#19，與 MusicTool 空白 query 同語義）：query/label/
    // 座標皆缺時會構建出 `geo:0,0?q=`（或空 OSM 搜尋），對使用者毫無意義——
    // 回 NEED_CLARIFICATION，不靜默開啟空搜尋的 intent。座標缺席以**雙側**為準
    //（與 [requirePairedCoords] 的配對契約同形）：單側經/緯（如 longitude-only）
    // 不是「空白目的地」而是配對破損——續走 [resolve] 被 paired-coord 守衛攔下
    // 收斂 INTERNAL，與 latitude-only 同方向，不以澄清話術偽裝「缺資訊」。
    if (params.query.isBlank() && params.label.isBlank() &&
      params.latitude == null && params.longitude == null
    ) {
      return ToolResult(
        false, false, null, ReasonCodes.NEED_CLARIFICATION,
        // 三段話術模板（FallbackSpec KDoc 形狀；比照 MusicTool 空白 query 的
        // 「→ 可替代 Y」中段——澄清不只問，也指到手動替代）。
        "導航去哪裡（${ReasonCodes.NEED_CLARIFICATION}）？→ 可替代在地圖 App 手動輸入目的地" +
          " → 需要你說出目的地（地名或座標）",
      )
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
    // 防拋收斂（[guardedFallbackAlternative]）：fallback 是「報告失敗」的路徑，
    // 自身不得反拋打穿呼叫方——[buildWebIntent] 的座標/長度守衛在非法參數下
    // 拋 IAE，此處降為 null：話術形狀不變，僅無預建 intent 載體。
    val web = guardedFallbackAlternative { buildWebIntent(params) }
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
