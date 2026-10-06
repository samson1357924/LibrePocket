package dev.librepocket.systema

/**
 * 音量工具：AudioManager 公開 API（兩風味皆完整，矩陣 §1 列 11）。
 *
 * 純側只做 [VolumeOp] 建模與範圍校驗；端側由 [AndroidIntentLauncher]
 * 經 `AudioManager.setStreamVolume / adjustStreamVolume` 落地。
 * 降級：打不開 AudioManager 時跳系統音量面板（SOUND_SETTINGS）。
 */
object VolumeTool : SystemTool<VolumeTool.Params> {

  /** 對齊 AudioManager Stream 型別（整數值與 SDK 一致，純側常量）。 */
  enum class AudioStream(val sdkValue: Int) {
    VOICE_CALL(0),
    SYSTEM(1),
    RING(2),
    MUSIC(3),
    ALARM(4),
    NOTIFICATION(5),
  }

  enum class Direction { RAISE, LOWER, SET, MUTE, UNMUTE }

  data class Params(
    val stream: AudioStream = AudioStream.MUSIC,
    val direction: Direction = Direction.RAISE,
    /** direction == SET 時的索引值（端側以 getStreamMaxVolume 夾取）。 */
    val index: Int? = null,
  )

  data class VolumeOp(
    val streamSdkValue: Int,
    val direction: Direction,
    val index: Int?,
    /** 端側夾取後的最終索引（純側以 maxVolume 試算）。 */
    val clampedIndex: Int? = null,
  )

  override val descriptor: ToolDescriptor
    get() = ToolRegistry.get(ToolRegistry.VOLUME)!!

  override fun check(env: SystemEnv): CheckResult {
    if (!env.userEnabled) {
      return CheckResult.unavailable(ReasonCodes.USER_DISABLED, "使用者已關閉音量快通道")
    }
    return CheckResult.native("AudioManager 公開 API，無需特殊權限")
  }

  fun buildOp(params: Params, maxVolume: Int = 15): VolumeOp {
    if (params.direction == Direction.SET) {
      requireNotNull(params.index) { "SET requires index" }
      require(params.index >= 0) { "index must be >= 0" }
    }
    val clamped = if (params.direction == Direction.SET) {
      params.index!!.coerceIn(0, maxVolume)
    } else {
      null
    }
    return VolumeOp(params.stream.sdkValue, params.direction, params.index, clamped)
  }

  fun volumePanelSpec(): IntentSpec =
    IntentSpec(action = IntentActions.SOUND_SETTINGS)

  override fun execute(params: Params, env: SystemEnv, launcher: IntentLauncher): ToolResult {
    val check = check(env)
    if (!check.executable) {
      return ToolResult(false, false, null, check.reasonCode, "做不到調音量（${check.reasonCode}）")
    }
    // 音量走 AudioManager 直調，不經 Intent；此處以 panel spec 佔位錨定超時語義，
    // 端側攔截本結果後改走 AudioManager（見 AndroidIntentLauncher.applyVolume）。
    val op = buildOp(params)
    return ToolResult(true, false, volumePanelSpec(), null, "stream=${op.streamSdkValue} dir=${op.direction}")
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec = FallbackSpec(
    reasonCode = ReasonCodes.NO_PRIVILEGE,
    alternative = volumePanelSpec(),
    userMessage = "做不到直接調音量（${ReasonCodes.NO_PRIVILEGE}）" +
      "→ 可替代開啟系統音量面板 → 需要你手動拖動滑桿",
  )
}
