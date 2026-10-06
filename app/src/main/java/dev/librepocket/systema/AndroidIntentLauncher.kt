package dev.librepocket.systema

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri

/**
 * 端側執行適配：把純側 [IntentSpec] 轉成真正的 Intent 並走
 * resolveActivity → startActivity；音量走 AudioManager 直調。
 *
 * resolveActivity 為空一律回 false（不拋錯），呼叫方接 [FallbackSpec]。
 */
class AndroidIntentLauncher(private val context: Context) : IntentLauncher {

  override fun launch(spec: IntentSpec): Boolean {
    val intent = toIntent(spec)
    if (intent.resolveActivity(context.packageManager) == null) return false
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
      context.startActivity(intent)
      true
    }.getOrDefault(false)
  }

  /** 經 AudioManager 落地音量操作；回傳實際是否生效。 */
  fun applyVolume(params: VolumeTool.Params): Boolean {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      ?: return false
    val stream = params.stream.sdkValue
    return runCatching {
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
    }.getOrDefault(false)
  }

  fun toIntent(spec: IntentSpec): Intent {
    val intent = Intent(spec.action)
    spec.dataUri?.let { intent.data = Uri.parse(it) }
    spec.mimeType?.let { mime ->
      val data = spec.dataUri?.let(Uri::parse)
      intent.setDataAndType(data, mime)
    }
    spec.targetPackage?.let { intent.setPackage(it) }
    spec.extras.forEach { (k, v) -> intent.putExtra(k, v) }
    return intent
  }
}
