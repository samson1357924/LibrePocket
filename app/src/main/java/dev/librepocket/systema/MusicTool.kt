package dev.librepocket.systema

/**
 * 音樂工具：媒體 Intent + MediaSession 前台（三風味皆完整，矩陣 §1 列 10）。
 *
 * - 點播：VIEW Intent 帶曲目/搜尋 URI，交給系統播放器。
 * - 控制（暫停/下一首等）：MEDIA_BUTTON 語義的 [ControlSpec]，
 *   端側經 MediaSession 回調落地。
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
    extras = mapOf("keyCode" to CONTROL_KEY_CODES.getValue(control).toString()),
  )

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到播放音樂（${check.reasonCode}）")
    }
    if (params.control != null) {
      val spec = buildControlIntent(params.control)
      val ok = runCatching { launcher.launch(spec) }.getOrDefault(false)
      return if (ok) ToolResult(true, false, spec)
      else ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到控制播放（${ReasonCodes.NO_HANDLER}）")
    }
    if (params.query.isBlank()) {
      return ToolResult(
        false, false, null, ReasonCodes.NEED_CLARIFICATION,
        "放什麼音樂（${ReasonCodes.NEED_CLARIFICATION}）？→ 可替代預設歌單 → 需要你說出曲名或歌手",
      )
    }
    val spec = buildPlayIntent(params)
    val ok = runCatching { launcher.launch(spec) }.getOrDefault(false)
    return if (ok) ToolResult(true, !env.hasMusicApp, spec, check.reasonCode)
    else ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到播放音樂（${ReasonCodes.NO_HANDLER}）")
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec = FallbackSpec(
    reasonCode = ReasonCodes.NO_HANDLER,
    alternative = buildPlayIntent(params),
    userMessage = "做不到開啟播放器（${ReasonCodes.NO_HANDLER}）" +
      "→ 可替代網頁播放器 → 需要你手動點播放",
  )
}
