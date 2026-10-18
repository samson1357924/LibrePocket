package dev.librepocket.systema

/**
 * 音樂工具：媒體 Intent + MediaSession 前台。
 *
 * Status: Target（純側契約，未接線未 E2E）。
 *
 * - 點播契約為**刻意近似**：官定 `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`
 *   （EXTRA_MEDIA_FOCUS + query extra）需要播放器逐一實作 focus 型別，
 *   非通用路徑；本層以 VIEW 到 YT Music 網頁搜尋近似（真實可用、只依瀏覽器，
 *   非官方契約直譯——近似屬明寫決策，不冒充官方契約）。
 * - 可見性（#14）：主 manifest `<queries>` 已宣告 VIEW geo/https scheme-only，
 *   targetSdk 36 下 resolve-first launcher 不再被過濾成假 NO_HANDLER；
 *   真機 handler E2E（瀏覽器/播放器實際接起）仍 UNVERIFIED。
 * - 控制（暫停/下一首等）：MEDIA_BUTTON 是 broadcast 語義、無 activity handler
 *   可 resolve——launcher 必回 NoHandler，屬已記錄的端側未接線缺口
 *   （MediaController 路徑，見下）；NoHandler 話術走 [fallback] 控制分支的
 *   完整三段計畫（alternative 為 null：控制語意下「播放 search」亦非替代）。
 *   單元測試僅鎖 keyCode 映射與話術形狀，未驗證端側 MediaSession 回調落地。
 * - 前台要求：實際播放必須跑在前台服務（`FOREGROUND_SERVICE_MEDIA_PLAYBACK`
 *   + 常駐通知），否則後台會被系統回收；本層僅建模，端側強制。
 * - 模糊目標（如「放點輕音樂」）不猜包名：先澄清或走預設播放器。
 */
object MusicTool : SystemTool<MusicTool.Params> {

  enum class Control { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS }

  data class Params(
    val query: String = "",
    val control: Control? = null,
  )

  /** MEDIA_BUTTON 對應鍵碼（與 KeyEvent.KEYCODE_* 一致，純側常量）。 */
  val CONTROL_KEY_CODES: Map<Control, Int> = mapOf(
    Control.PLAY to 126, // KEYCODE_MEDIA_PLAY
    Control.PAUSE to 127, // KEYCODE_MEDIA_PAUSE
    Control.TOGGLE to 85, // KEYCODE_MEDIA_PLAY_PAUSE
    Control.NEXT to 87, // KEYCODE_MEDIA_NEXT
    Control.PREVIOUS to 88, // KEYCODE_MEDIA_PREVIOUS
  )

  data class ControlSpec(val control: Control, val keyCode: Int)

  override val descriptor: ToolDescriptor
    get() = ToolRegistry.get(ToolRegistry.MUSIC)!!

  override fun check(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉音樂快通道")
    }
    return if (env.hasMusicApp) {
      CheckResult.native("媒體 Intent 可用（前台 MediaSession）")
    } else {
      CheckResult.degraded(ReasonCodes.NO_HANDLER, "無播放器，降級開啟預設播放器頁")
    }
  }

  fun buildPlayIntent(params: Params, defaultPlayerPackage: String? = null): IntentSpec =
    IntentSpec(
      action = IntentActions.VIEW,
      dataUri = if (params.query.isBlank()) null
      else "https://music.youtube.com/search?q=${java.net.URLEncoder.encode(params.query, Charsets.UTF_8.name())}",
      targetPackage = defaultPlayerPackage,
    )

  fun buildControl(control: Control): ControlSpec =
    ControlSpec(control, CONTROL_KEY_CODES.getValue(control))

  fun buildControlIntent(control: Control): IntentSpec = IntentSpec(
    action = IntentActions.MEDIA_BUTTON,
    // D2 型別化：keyCode 用 Int，端側 MediaSession 回調按 int 讀回。
    extras = mapOf("keyCode" to IntentExtra.IntVal(CONTROL_KEY_CODES.getValue(control))),
  )

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到播放音樂（${check.reasonCode}）")
    }
    if (params.control != null) {
      val spec = buildControlIntent(params.control)
      return when (val launched = launcher.launchCatching(spec)) {
        // MEDIA_BUTTON 為 broadcast 語義，resolve-first launcher 恆回 NoHandler：
        // 話術取 [fallback] 控制分支（完整三段計畫；generic toToolResult 的
        // 無計畫尾段話術不滿足話術模板，見 FallbackSpec KDoc）。
        LaunchResult.NoHandler -> ToolResult(
          false, true, spec, ReasonCodes.NO_HANDLER, fallback(params, env).userMessage,
        )
        else -> launched.toToolResult(spec, "控制播放")
      }
    }
    if (params.query.isBlank()) {
      return ToolResult(
        false, false, null, ReasonCodes.NEED_CLARIFICATION,
        "放什麼音樂（${ReasonCodes.NEED_CLARIFICATION}）？→ 可替代預設歌單 → 需要你說出曲名或歌手",
      )
    }
    val spec = buildPlayIntent(params)
    return when (val launched = launcher.launchCatching(spec)) {
      // 發現式缺席（靜態有播放器但發射無處理者：被移除/停用/不可見）與靜態
      // DEGRADED 同語義：走 [fallback] 話術（可替代網頁播放器），spec 保留。
      LaunchResult.NoHandler -> ToolResult(
        false, true, spec, ReasonCodes.NO_HANDLER, fallback(params, env).userMessage,
      )
      // Success/Internal 走共用收斂（Internal 收斂為 INTERNAL）。
      else -> launched.toToolResult(
        spec, "播放音樂",
        usedFallbackOnSuccess = !env.hasMusicApp, successReason = check.reasonCode,
      )
    }
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec {
    if (params.control != null) {
      // 控制路徑沒有 intent 可替代（「播放 search」不是暫停/下一首的替代）：
      // alternative 留 null，話術指向使用者可立即達成的手動替代。
      return FallbackSpec(
        reasonCode = ReasonCodes.NO_HANDLER,
        alternative = null,
        userMessage = "做不到控制播放（${ReasonCodes.NO_HANDLER}）" +
          "→ 可替代螢幕/通知欄的播放控制 → 需要你手動按暫停或下一首",
      )
    }
    return FallbackSpec(
      reasonCode = ReasonCodes.NO_HANDLER,
      alternative = buildPlayIntent(params),
      userMessage = "做不到開啟播放器（${ReasonCodes.NO_HANDLER}）" +
        "→ 可替代網頁播放器 → 需要你手動點播放",
    )
  }
}
