package dev.librepocket.systema

/**
 * 開 App 工具：launchIntent + `<queries>` 白名單 + 模糊名澄清。
 *
 * 端側 manifest 已補最小 <queries>（SET_ALARM/日曆/geo/https，見主 manifest #14
 * 註解）；開 App 的 launcher 清單查詢仍刻意不宣告——以 [QUERY_WHITELIST] 作
 * 唯一真相來源，manifest 註解反向引用本檔的缺口註記：
 * ```xml
 * <queries>
 *   <package android:name="com.google.android.apps.maps" />
 *   ...（見 QUERY_WHITELIST 全表）
 * </queries>
 * ```
 * 白名單外不可見 → [LaunchResolution.NeedClarification] 或 Unknown，
 * 禁止猜測包名。空白 query 是**意圖缺席**，橫向與 Music/Navigation 統一：
 * [execute]/[fallback] 先收斂 `NEED_CLARIFICATION`（不走 Unknown 的
 * `NO_HANDLER`「做不到開啟「」」空回顯話術）。
 *
 * Spec 形狀（F1 修正）：[launchSpec] 為 ACTION_MAIN+setPackage 並攜
 * `android.intent.category.LAUNCHER`——launcher activity 的 manifest filter
 * 通常僅 MAIN+LAUNCHER，隱式 intent 缺 category 則 filter 匹配不可達
 * （與 geo/https 已修的同形真機恆 NoHandler 矛盾；契約句見
 * [IntentSpec.categories] KDoc）。
 *
 * Target 缺口（刻意保留）：開 App launcher 清單查詢未加入 <queries>；API 30+
 * 包可見性下 [QUERY_WHITELIST] 的包真機 resolve 恐 NoHandler——防包名猜測
 * 優先，需裝置驗證後再決定是否補宣告。launchSpec 已補 LAUNCHER category，
 * 但 resolve 作為（MATCH_DEFAULT_ONLY 對 launcher-only filter 的限制）與
 * 顯式 component 派生仍屬接線 PR 的 Target 缺口，本層 fail-closed 收斂
 * （resolve null → [LaunchResult.NoHandler] 話術路徑）不變。
 */
object LaunchAppTool : SystemTool<LaunchAppTool.Params> {

  data class Params(val query: String)

  /** Unknown 分支話術的 query 回顯上限（話術防回顯，非參數校驗：[resolve]
   *  對 query 本身無上限；spec 與比對邏輯不受此限）。澄清候選的單一候選回顯
   *  上限另見 [MAX_CANDIDATE_ECHO_LEN]（同屬防回顯政策）。 */
  const val MAX_QUERY_ECHO_LEN = 32

  /** 澄清候選（NeedClarification 話術）的單一候選回顯上限（與
   *  [MAX_QUERY_ECHO_LEN]/[CalendarTool.MAX_EVENT_ID_ECHO_LEN] 同款防回顯政策，
   *  非參數校驗；候選可能為可見集內的超長包名，截斷話術回顯，spec/比對邏輯
   *  仍以完整值運行）。 */
  const val MAX_CANDIDATE_ECHO_LEN = 64

  /**
   * queries 白名單：別名 → 包名。只收錄常見公開 App，收斂規劃空間。
   * key 全小寫，匹配時大小寫無關。
   */
  val QUERY_WHITELIST: Map<String, String> = mapOf(
    "地圖" to "com.google.android.apps.maps",
    "maps" to "com.google.android.apps.maps",
    "gmail" to "com.google.android.gm",
    "郵件" to "com.google.android.gm",
    "日曆" to "com.google.android.calendar",
    "calendar" to "com.google.android.calendar",
    "相機" to "com.google.android.GoogleCamera",
    "camera" to "com.google.android.GoogleCamera",
    "時鐘" to "com.google.android.deskclock",
    "clock" to "com.google.android.deskclock",
    "電話" to "com.google.android.dialer",
    "dialer" to "com.google.android.dialer",
    "簡訊" to "com.google.android.apps.messaging",
    "messages" to "com.google.android.apps.messaging",
    "youtube" to "com.google.android.youtube",
    "相簿" to "com.google.android.apps.photos",
    "photos" to "com.google.android.apps.photos",
    "瀏覽器" to "com.android.chrome",
    "chrome" to "com.android.chrome",
    "音樂" to "com.google.android.apps.youtube.music",
    "spotify" to "com.spotify.music",
  )

  sealed interface LaunchResolution {
    data class Single(val packageName: String, val spec: IntentSpec) : LaunchResolution
    data class NeedClarification(val candidates: List<String>) : LaunchResolution
    data object Unknown : LaunchResolution
  }

  override val descriptor: ToolDescriptor
    get() = ToolRegistry.get(ToolRegistry.LAUNCH_APP)!!

  /**
   * 解析使用者輸入：精確包名/別名 → Single；多命中 → 澄清；零命中 → Unknown。
   *
   * 精確包名須在可見集內才直達；別名經靜態表 [QUERY_WHITELIST] 映射。
   *
   * @param visiblePackages 唯一合法來源：PackageManager 可見集；
   *   禁透傳 LLM/MCP 輸出的包名（防包名猜測/劫持）。
   */
  fun resolve(query: String, visiblePackages: Set<String>): LaunchResolution {
    // 空白 query 的 NEED_CLARIFICATION 收斂在 [execute]/[fallback] 上游先行
    // 攔截（與 Music/Navigation 空白目標統一）；此處仍回 Unknown 作直接呼叫
    // resolve 的安全網。
    val q = query.trim().lowercase()
    if (q.isEmpty()) return LaunchResolution.Unknown
    // 精確包名直達（需在可見集合內）。
    if (q in visiblePackages) {
      return LaunchResolution.Single(q, launchSpec(q))
    }
    // 別名精確命中。
    QUERY_WHITELIST[q]?.let { pkg ->
      return LaunchResolution.Single(pkg, launchSpec(pkg))
    }
    // 模糊：別名或包名包含查詢子串 → 候選澄清（上限 5 個，排序穩定）。
    val candidates = (QUERY_WHITELIST.entries
      .filter { (alias, pkg) -> alias.contains(q) || pkg.lowercase().contains(q) }
      .map { it.value } +
      visiblePackages.filter { it.lowercase().contains(q) })
      .distinct().take(5)
    return when {
      candidates.size == 1 ->
        LaunchResolution.Single(candidates.single(), launchSpec(candidates.single()))
      candidates.isNotEmpty() -> LaunchResolution.NeedClarification(candidates)
      else -> LaunchResolution.Unknown
    }
  }

  /**
   * ACTION_MAIN + setPackage 的隱式啟動 spec。category 契約（F1）：官方隱式
   * intent 啟動需 filter 匹配，launcher activity filter 通常僅
   * `MAIN` + `CATEGORY_LAUNCHER`（無 `CATEGORY_DEFAULT`）——隱式 intent 缺
   * LAUNCHER category 必不匹配（`resolveActivity` 恆 null → 真機話術恆
   * NoHandler），故攜 framework 常量字面值（`IntentSpec.categories` 契約：
   * 目前唯一使用點）。真機 resolve 仍受 MATCH_DEFAULT_ONLY / Target 缺口限制
   *（見 object KDoc），本層不改 launcher 的 resolve 語義（fail-closed 收斂
   * resolve null → NoHandler 不變）。
   */
  fun launchSpec(packageName: String): IntentSpec = IntentSpec(
    action = IntentActions.MAIN,
    targetPackage = packageName,
    categories = listOf("android.intent.category.LAUNCHER"),
  )

  override fun check(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉開 App 快通道")
    }
    return CheckResult.native("launchIntent 可用（以 queries 可見性為準）")
  }

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val gate = check(env)
    if (!gate.executable) {
      return ToolResult(false, false, null, gate.reasonCode, "做不到開啟 App（${gate.reasonCode}）")
    }
    // 空白 query：使用者意圖缺席（F4a，與 Music/Navigation 空白目標同契約），
    // 與環境能力無關（即使靜態 NATIVE 亦然）——回 NEED_CLARIFICATION，不產生
    // 「做不到開啟「」」的空回顯 NO_HANDLER 話術；話術取 [fallback] 空白分支
    // 保證兩處同模板，且不調用 launcher（無意圖即無可發射的 intent）。
    if (params.query.isBlank()) {
      return ToolResult(
        false, false, null, ReasonCodes.NEED_CLARIFICATION, fallback(params, env).userMessage,
      )
    }
    return when (val r = resolve(params.query, env.installedPackages)) {
      is LaunchResolution.Single -> {
        when (val launched = launcher.launchCatching(r.spec)) {
          // 發現式缺席（白名單命中但發射無處理者：被移除/停用/不可見）與姊妹
          // 工具（Alarm/Calendar/Music/Navigation）同形（F4b）：話術取
          // [fallback] 的完整三段計畫（generic toToolResult 兩段話術不滿足
          // FallbackSpec KDoc 模板），usedFallback=true、嘗試過的 spec 保留。
          LaunchResult.NoHandler -> ToolResult(
            false, true, r.spec, ReasonCodes.NO_HANDLER, fallback(params, env).userMessage,
          )
          // Success/Internal 走共用收斂（Internal 收斂為 INTERNAL+cause 類名）。
          else -> launched.toToolResult(r.spec, "開啟 App")
        }
      }
      is LaunchResolution.NeedClarification ->
        // 澄清話術與 [fallback] NeedClarification 分支同一模板（F4c）：
        // 補齊「→ 可替代候選… → 需要你選一個」尾段，統一話術模板（此前僅
        // 問句+候選、缺尾段，與 fallback 版本漂移）。
        ToolResult(
          false, false, null, ReasonCodes.NEED_CLARIFICATION, fallback(params, env).userMessage,
        )
      LaunchResolution.Unknown ->
        // 話術取 [fallback] else 分支，字面單一來源（同 blank 澄清分支做法，
        // 避免與 fallback 模板漂移）；防回顯上限 [MAX_QUERY_ECHO_LEN] 仍由
        // 該分支自保（話術只回顧前 32 字）。
        ToolResult(
          false, false, null, ReasonCodes.NO_HANDLER, fallback(params, env).userMessage,
        )
    }
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec =
    if (params.query.isBlank()) {
      // F4a：空白 query 是意圖缺席，非「找不到 App」——澄清優先（與
      // Music/Navigation 空白目標收斂點統一），禁把空白字串回顯成 NO_HANDLER
      // 的「做不到開啟「」」話術。三段形狀對齊 FallbackSpec KDoc 模板。
      FallbackSpec(
        reasonCode = ReasonCodes.NEED_CLARIFICATION,
        userMessage = "開啟哪個 App（${ReasonCodes.NEED_CLARIFICATION}）？" +
          "→ 可替代手動從桌面開啟 → 需要你說出 App 名稱",
      )
    } else {
      when (val r = resolve(params.query, env.installedPackages)) {
        is LaunchResolution.NeedClarification -> FallbackSpec(
          reasonCode = ReasonCodes.NEED_CLARIFICATION,
          // 候選回顯經 [MAX_CANDIDATE_ECHO_LEN] 截斷（防回顯上限，同
          // [MAX_QUERY_ECHO_LEN] 政策；候選數上限 5 仍由 [resolve] 把關）。
          userMessage = "開啟哪個 App（${ReasonCodes.NEED_CLARIFICATION}）" +
            "→ 可替代候選：" +
            r.candidates.joinToString("、") { it.take(MAX_CANDIDATE_ECHO_LEN) } +
            " → 需要你選一個",
        )
        else -> FallbackSpec(
          reasonCode = ReasonCodes.NO_HANDLER,
          // 同 [MAX_QUERY_ECHO_LEN]（[CalendarTool.MAX_EVENT_ID_ECHO_LEN] 的防回顯契約）：話術只回顧前 32 字。
          userMessage = "做不到開啟「${params.query.take(MAX_QUERY_ECHO_LEN)}」（${ReasonCodes.NO_HANDLER}）" +
            "→ 可替代手動從桌面開啟 → 需要你確認 App 名稱",
        )
      }
    }
}
