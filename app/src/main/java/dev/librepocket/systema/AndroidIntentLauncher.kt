package dev.librepocket.systema

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException

/**
 * 端側執行適配：把純側 [IntentSpec] 轉成真正的 Intent 並走
 * resolveActivity → startActivity；音量走 AudioManager 直調。
 *
 * 失敗分型（D2）：resolve 無處理者 → [LaunchResult.NoHandler]；
 * `startActivity` 拋 [ActivityNotFoundException]（resolve 後 handler 被移除/
 * 停用）同樣視為無處理者 → [LaunchResult.NoHandler]（舊 boolean false 語義）；
 * toIntent / resolve / start 其餘拋錯 → [LaunchResult.Internal]
 * （含 `resolveActivity` 自身拋錯，D1 此處在 runCatching 之外會直接外洩）。
 * 呼叫方接 [FallbackSpec]。
 *
 * 例外收斂說明：本類的 catch 皆只收 `Exception`（`OutOfMemoryError` 等 VM fatal
 * 直接外洩）；`CancellationException` 在 [launch] 三段與 [applyVolume] 皆明確
 * 重拋，structured concurrency 由呼叫方維持。
 *
 * 發射 allowlist：[IntentActions.DELETE] 待寫 spec 不經本層發射（[CalendarTool]
 * 契約），見 [launch]，企圖發射 fail-closed 回 `Internal`。
 *
 * 已接受風險（文件化，不改行為）：隱式 Intent handler 劫持——系統依預設
 * App 選擇處理者，本層不代選；高風險操作由呼叫方經使用者確認後才發起，
 * 不靜默轉發。
 */
class AndroidIntentLauncher(private val context: Context) : IntentLauncher {

  override fun launch(spec: IntentSpec): LaunchResult {
    // 防禦性 allowlist：DELETE（待寫 spec，僅供展示/端側直寫用，見 CalendarTool
    // KDoc 與 manifest 文檔契約）禁經 launcher 發射——企圖發射視為層錯誤，
    // fail-closed 回 Internal（不猜測轉發）。
    if (spec.action == IntentActions.DELETE) {
      return LaunchResult.Internal(
        IllegalStateException("DELETE spec must not be launched (end-side direct write only)"),
      )
    }
    val intent = try {
      toIntent(spec)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return LaunchResult.Internal(e)
    }
    val resolved = try {
      intent.resolveActivity(context.packageManager)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return LaunchResult.Internal(e)
    }
    if (resolved == null) return LaunchResult.NoHandler
    return try {
      context.startActivity(intent)
      LaunchResult.Success
    } catch (e: ActivityNotFoundException) {
      LaunchResult.NoHandler
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LaunchResult.Internal(e)
    }
  }

  /**
   * 經 AudioManager 落地音量操作；回傳實際是否生效。
   *
   * 例外收斂與 [launch] 同契約：只收 `Exception`（回 false；`OutOfMemoryError`
   * 等 VM fatal 直接外洩），`CancellationException` 明確重拋（structured
   * concurrency 由呼叫方維持；本函式不得把取消吞成 false）。
   */
  fun applyVolume(params: VolumeTool.Params): Boolean {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      ?: return false
    val stream = params.stream.sdkValue
    return try {
      when (params.direction) {
        VolumeTool.Direction.RAISE ->
          audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
        VolumeTool.Direction.LOWER ->
          audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
        VolumeTool.Direction.MUTE ->
          audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
        VolumeTool.Direction.UNMUTE ->
          audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        VolumeTool.Direction.SET -> {
          val max = audio.getStreamMaxVolume(stream)
          val op = VolumeTool.buildOp(params, max)
          audio.setStreamVolume(stream, op.clampedIndex ?: 0, 0)
        }
      }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      false
    }
  }

  /**
   * 純映射（便於 Robolectric 斷言 extras/flags）。生產路徑必須走 [launch]，
   * 不得繞過 resolveActivity/try-catch 直調 `startActivity`。
   *
   * toIntent 拋錯分支目前近乎 dead code：現有映射（action/data/package/
   * extras allowlist flags）對合法 [IntentSpec] 不拋；保留作 fail-closed 防禦，
   * 故無針對性拋錯測試，僅由 resolve/start 拋錯測試覆蓋同層收斂語義。
   */
  fun toIntent(spec: IntentSpec): Intent {
    val intent = Intent(spec.action)
    spec.dataUri?.let { intent.data = Uri.parse(it) }
    spec.mimeType?.let { mime ->
      val data = spec.dataUri?.let(Uri::parse)
      intent.setDataAndType(data, mime)
    }
    spec.targetPackage?.let { intent.setPackage(it) }
    spec.extras.forEach { (k, v) ->
      // 窮舉表達式（賦值迫使編譯器檢查）：新增 IntentExtra 子類未補分支即編譯錯，
      // 不依賴執行期 `else -> throw`；保留 fail-closed 語義於編譯期。
      @Suppress("UNUSED_VARIABLE")
      val unused = when (v) {
        is IntentExtra.Str -> intent.putExtra(k, v.value)
        is IntentExtra.LongVal -> intent.putExtra(k, v.value)
        is IntentExtra.IntVal -> intent.putExtra(k, v.value)
        is IntentExtra.Bool -> intent.putExtra(k, v.value)
      }
    }
    // 預設恆加 NEW_TASK（維持 D1 行為）；spec.flags 僅套用 allowlist。
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val allowed = IntentFlags.sanitize(spec.flags)
    if (allowed != 0) intent.addFlags(allowed)
    return intent
  }
}
