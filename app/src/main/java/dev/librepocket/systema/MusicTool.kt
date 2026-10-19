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
 *   query 長度上限（[MAX_QUERY_LEN]=500）為本層 Binder 傳輸防禦（官方契約
 *   無此上限），builder fail-closed 拒絕，越界經 [execute] 收斂 INTERNAL。
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
 * - [buildPlayIntent] 的 `targetPackage` 參數為**測試建模**用途（純映射方便
 *   斷言 extras/flags）；生產**不傳**（OEM/播放器包名各異，本層不代選 app，
 *   與 manifest 「直啟不採用 setPackage」註記一致）——新呼叫方禁無腦透傳。
 * - 與姊妹工具同契約：execute 內 builder 一律經 [guardedToolBuild] 收斂
 *   （控制映射與 play 編碼的拋例不再穿透生產迴圈）。
 */
object MusicTool : SystemTool<MusicTool.Params> {

  /** Binder 傳輸上限防禦：query 過長在 builder 即拒（本層自訂政策，非官方
   *  契約；與 [AlarmTool.MAX_MESSAGE_LEN]/[NavigationTool.MAX_QUERY_LEN] 同值同源）。 */
  const val MAX_QUERY_LEN = 500

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

  fun buildPlayIntent(params: Params, defaultPlayerPackage: String? = null): IntentSpec {
    // Binder 傳輸上限防禦：URLEncoder 前以欄位長度計（見 IntentSpec KDoc 長度政策）。
    require(params.query.length <= MAX_QUERY_LEN) { "query too long: ${params.query.length} > $MAX_QUERY_LEN" }
    return IntentSpec(
      action = IntentActions.VIEW,
      dataUri = if (params.query.isBlank()) null
      else "https://music.youtube.com/search?q=${java.net.URLEncoder.encode(params.query, Charsets.UTF_8.name())}",
      targetPackage = defaultPlayerPackage,
    )
  }

  fun buildControl(control: Control): ControlSpec {
    // 鍵碼查表缺項以 ISE 快速失敗（可被 [guardedToolBuild] 收斂為 INTERNAL），
    // 而非讓穿透性的 NoSuchElementException（guard 不可捕）漏出生產迴圈；
    // 新增 Control 未補鍵碼時層面即收斂、話術保持一致（Phase2 收斂點真實性）。
    val keyCode = CONTROL_KEY_CODES[control]
      ?: throw IllegalStateException("no keyCode mapping for Control.${control.name}")
    return ControlSpec(control, keyCode)
  }

  fun buildControlIntent(control: Control): IntentSpec {
    // 同 [buildControl]：缺鍵碼 ISE（非 NoSuchElementException），確保
    // guardedToolBuild 真實收斂；D2 型別化：keyCode 用 Int，端側 MediaSession
    // 回調按 int 讀回。
    val keyCode = CONTROL_KEY_CODES[control]
      ?: throw IllegalStateException("no keyCode mapping for Control.${control.name}")
    return IntentSpec(
      action = IntentActions.MEDIA_BUTTON,
      extras = mapOf("keyCode" to IntentExtra.IntVal(keyCode)),
    )
  }

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到播放音樂（${check.reasonCode}）")
    }
    if (params.control != null) {
      // fail-closed：MEDIA_BUTTON 映射（keyCode 查表缺項拋 ISE）一律收斂為
      // INTERNAL，不穿透生產迴圈（收斂點 [guardedToolBuild]，話術形狀同姊妹工具）。
      val controlFailure =
        ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到控制播放（${ReasonCodes.INTERNAL}）")
      val spec = when (val build = guardedToolBuild(controlFailure) { buildControlIntent(params.control) }) {
        is Guarded.Internal -> return build.result
        is Guarded.Built -> build.value
      }
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
    // fail-closed：play builder（URLEncoder 若拋）同樣收斂為 INTERNAL
    //（收斂點 [guardedToolBuild]）。
    val playFailure =
      ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到播放音樂（${ReasonCodes.INTERNAL}）")
    val spec = when (val build = guardedToolBuild(playFailure) { buildPlayIntent(params) }) {
      is Guarded.Internal -> return build.result
      is Guarded.Built -> build.value
    }
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
      // 防拋收斂（[guardedFallbackAlternative]，同 [NavigationTool.fallback]）：
      // query 守衛違規時降為 null，話術形狀不變；fallback 不得反拋打穿呼叫方。
      alternative = guardedFallbackAlternative { buildPlayIntent(params) },
      userMessage = "做不到開啟播放器（${ReasonCodes.NO_HANDLER}）" +
        "→ 可替代網頁播放器 → 需要你手動點播放",
    )
  }
}
