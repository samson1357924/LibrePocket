package dev.librepocket.systema

/**
 * 音量工具：AudioManager 公開 API。
 *
 * Status: Target（純側契約，未接線未 E2E）。
 *
 * 純側只做 [VolumeOp] 建模與範圍校驗；端側由 [AndroidIntentLauncher]
 * 經 `AudioManager.setStreamVolume / adjustStreamVolume` 落地。
 * 降級：打不開 AudioManager 時跳系統音量面板（SOUND_SETTINGS；該 action 的
 * resolve 需主 manifest `<queries>` 宣告，見 manifest #14 註解）。
 *
 * 端側落地的已知條件（官方 AudioManager javadoc，接線 PR 須處理）：
 * - `setStreamVolume` / `adjustStreamVolume` 在觸發 Do-Not-Disturb 變更、
 *   且呼叫端未持有 Notification Policy Access 時丟 `SecurityException`；
 *   [AndroidIntentLauncher.applyVolume] 經 try/catch 收斂為 bare false
 *  （只收 `Exception`，`CancellationException` 重拋）。誠實聲明（拒絕 vs 系統
 *   錯誤的分型在現契約下不可實現）：Boolean 回傳目前**丟失原因分型**——
 *  「呼叫端被拒（SecurityException）」與「系統錯誤」在該函式回傳後不可區分；
 *   接線層若需區分二者，須另開拋出式落點（或在 applyVolume 內部自判後分型），
 *   不得依賴現有 Boolean 簽名取得原因碼。
 * - `isVolumeFixed` 裝置上 adjust 是靜默 no-op，仍會回 true——不得計為成功。
 *
 * 佔位錨定語義（current）：[execute] 的 `ok=true` 表示「操作已錨定」——
 * [VolumeOp] 型別化參數與 [volumePanelSpec] 降級 spec 已產出，**不表示音量
 * 已改變**；實際效果由端側攔截層接 [AndroidIntentLauncher.applyVolume]（該
 * 攔截層目前仍不存在，接線 PR 落地前任何呼叫方不得把 ok=true 當成已執行）。
 * [fallback] 的 NO_PRIVILEGE 話術保留給未來端層判定的「無法直調」分支，
 * 純 [execute] 途徑不產生它。
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
    // 音量走 AudioManager 直調，不經 Intent；本 execute 以 panel spec 佔位錨定
    // 超時語義，實際效果由端側攔截層接 [AndroidIntentLauncher.applyVolume]
    // （該層目前不存在，見類 KDoc 佔位錨定語義——ok=true 不是「已改變音量」）。
    // fail-closed：builder IAE（SET 缺 index／負值）收斂為 INTERNAL，不穿透生產迴圈
    //（收斂點 [guardedToolBuild]：話術由呼叫方提供，訊息與原 inline catch 逐字相同）。
    val internalFailure =
      ToolResult(false, false, null, ReasonCodes.INTERNAL, "做不到調音量（${ReasonCodes.INTERNAL}）")
    val op = when (val build = guardedToolBuild(internalFailure) { buildOp(params) }) {
      is Guarded.Internal -> return build.result
      is Guarded.Built -> build.value
    }
    // 話術純化：message 只保留 stream/dir 狀態回顯；「已錨定／端側 AudioManager
    // 未接線」屬工程註記，語義由類 KDoc「佔位錨定語義」與上註釋承載——呼叫方
    // 仍以 ok=true 契約（非「已改變音量」）為準，不靠話術夾帶狀態註記。
    return ToolResult(
      true, false, volumePanelSpec(), null,
      "stream=${op.streamSdkValue} dir=${op.direction}",
    )
  }

  override fun fallback(params: Params, env: SystemEnv): FallbackSpec = FallbackSpec(
    reasonCode = ReasonCodes.NO_PRIVILEGE,
    // alternative 為直接的常量構建（不經 [guardedFallbackAlternative] 收斂）：
    // [volumePanelSpec] 是純 IntentSpec 常量、無 builder 守衛可違反（不可拋），
    // 屬意圖守衛的常量豁免（見 [guardedFallbackAlternative] KDoc 豁免註記）。
    alternative = volumePanelSpec(),
    userMessage = "做不到直接調音量（${ReasonCodes.NO_PRIVILEGE}）" +
      "→ 可替代開啟系統音量面板 → 需要你手動拖動滑桿",
  )
}
