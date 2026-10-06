package dev.librepocket.systema

/**
 * 開 App 工具：launchIntent + `<queries>` 白名單 + 模糊名澄清。
 *
 * 端側 manifest 需在 `<queries>` 中宣告可見包（約束：本包不動 manifest，
 * 此處以 [QUERY_WHITELIST] 作為唯一真相來源，發版時由 manifest 側對齊）：
 * ```xml
 * <queries>
 *   <package android:name="com.google.android.apps.maps" />
 *   ...（見 QUERY_WHITELIST 全表）
 * </queries>
 * ```
 * 白名單外不可見 → [LaunchResolution.NeedClarification] 或 Unknown，
 * 禁止猜測包名（ARCH §9.1）。
 */
object LaunchAppTool : SystemTool<LaunchAppTool.Params> {

  data class Params(val query: String)

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

  /** 解析使用者輸入：精確包名/別名 → Single；多命中 → 澄清；零命中 → Unknown。 */
  fun resolve(query: String, visiblePackages: Set<String>): LaunchResolution {
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

  fun launchSpec(packageName: String): IntentSpec = IntentSpec(
    action = IntentActions.MAIN,
    targetPackage = packageName,
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
    return when (val r = resolve(params.query, env.installedPackages)) {
      is LaunchResolution.Single -> {
        val ok = runCatching { launcher.launch(r.spec) }.getOrDefault(false)
        if (ok) ToolResult(true, false, r.spec)
        else ToolResult(false, false, r.spec, ReasonCodes.NO_HANDLER, "做不到開啟 App（${ReasonCodes.NO_HANDLER}）")
      }
      is LaunchResolution.NeedClarification -> ToolResult(
        false, false, null, ReasonCodes.NEED_CLARIFICATION,
        "開啟哪個 App（${ReasonCodes.NEED_CLARIFICATION}）？候選：" + r.candidates.joinToString("、"),
      )
      LaunchResolution.Unknown -> ToolResult(
        false, false, null, ReasonCodes.NO_HANDLER,
        "做不到開啟「${params.query}」（${ReasonCodes.NO_HANDLER}）→ 可替代手動開啟 → 需要你告訴我是哪個 App",
      )
    }
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec =
    when (val r = resolve(params.query, env.installedPackages)) {
      is LaunchResolution.NeedClarification -> FallbackSpec(
        reasonCode = ReasonCodes.NEED_CLARIFICATION,
        userMessage = "開啟哪個 App（${ReasonCodes.NEED_CLARIFICATION}）" +
          "→ 可替代候選：" + r.candidates.joinToString("、") + " → 需要你選一個",
      )
      else -> FallbackSpec(
        reasonCode = ReasonCodes.NO_HANDLER,
        userMessage = "做不到開啟「${params.query}」（${ReasonCodes.NO_HANDLER}）" +
          "→ 可替代手動從桌面開啟 → 需要你確認 App 名稱",
      )
    }
}
