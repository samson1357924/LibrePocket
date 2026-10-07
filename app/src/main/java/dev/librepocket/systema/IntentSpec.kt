package dev.librepocket.systema

/**
 * 平台無關的 Intent 描述（純 JVM）。Android 側由
 * [AndroidIntentLauncher] 轉成真正的 `android.content.Intent` 並執行
 * resolveActivity → startActivity；單元測試只斷言本層映射。
 */
data class IntentSpec(
  val action: String,
  val dataUri: String? = null,
  val mimeType: String? = null,
  val targetPackage: String? = null,
  val extras: Map<String, String> = emptyMap(),
  val flags: Int = 0,
)

/** 啟動器抽象：JVM 測試用假實現，端側用 [AndroidIntentLauncher]。 */
fun interface IntentLauncher {
  /** 回傳 true 表已交給系統處理（startActivity 未拋錯）。 */
  fun launch(spec: IntentSpec): Boolean
}

data class ToolResult(
  val ok: Boolean,
  val usedFallback: Boolean,
  val spec: IntentSpec? = null,
  val reasonCode: String? = null,
  val message: String = "",
)

/**
 * 降級分支描述。話術必須遵守矩陣 §5 模板：
 * `做不到 X（原因碼）→ 可替代 Y → 需要你做 Z`。
 */
data class FallbackSpec(
  val reasonCode: String,
  val alternative: IntentSpec? = null,
  val userMessage: String = "",
  /** 降級路徑所需的額外申報/授權提示（如下載級精準鬧鐘、通知權）。 */
  val requiresDeclaration: String = "",
)

object IntentActions {
  const val VIEW = "android.intent.action.VIEW"
  const val MAIN = "android.intent.action.MAIN"
  const val SEND = "android.intent.action.SEND"
  const val SET_ALARM = "android.intent.action.SET_ALARM"
  const val INSERT = "android.intent.action.INSERT"
  const val EDIT = "android.intent.action.EDIT"
  const val DELETE = "android.intent.action.DELETE"
  const val MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"
  const val SOUND_SETTINGS = "android.settings.SOUND_SETTINGS"

  const val EXTRA_ALARM_HOUR = "android.intent.extra.alarm.HOUR"
  const val EXTRA_ALARM_MINUTES = "android.intent.extra.alarm.MINUTES"
  const val EXTRA_ALARM_MESSAGE = "android.intent.extra.alarm.MESSAGE"
  const val EXTRA_ALARM_SKIP_UI = "android.intent.extra.alarm.SKIP_UI"
}
