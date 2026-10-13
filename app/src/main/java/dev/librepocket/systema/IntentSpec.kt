package dev.librepocket.systema

/**
 * 平台無關的 Intent 描述（純 JVM）。Android 側由
 * [AndroidIntentLauncher] 轉成真正的 `android.content.Intent` 並執行
 * resolveActivity → startActivity；單元測試只斷言本層映射。
 *
 * D2 型別化 extras：D1 的全 String 會讓端側 `getIntExtra /
 * getLongExtra / getBooleanExtra` 讀回預設值（系統鬧鐘/日曆 App 直接忽略），
 * 故改為密封型別，launcher 按型別 putExtra。
 */
sealed interface IntentExtra {
  data class Str(val value: String) : IntentExtra
  data class LongVal(val value: Long) : IntentExtra
  data class IntVal(val value: Int) : IntentExtra
  data class Bool(val value: Boolean) : IntentExtra
}

/**
 * Intent flags allowlist（純 JVM 常量，數值與 `android.content.Intent` 對齊，
 * 避免本層依賴 android.*）。
 *
 * 只允許安全子集（[ALLOWED_MASK]）：`NEW_TASK`（非 Activity context 啟動必需，
 * 維持 D1 預設行為）+ 無害的任務棧提示（`CLEAR_TOP` / `SINGLE_TOP`）。
 * 其餘一律 fail-closed 丟棄：`CLEAR_TASK` 會清空任務棧、`NO_HISTORY` /
 * `EXCLUDE_FROM_RECENTS` 改變歷史/最近頁語義、`GRANT_*` 擴大 URI 授權，
 * 皆不由工具層攜帶。
 */
object IntentFlags {
  const val GRANT_READ_URI_PERMISSION: Int = 0x00000001
  const val GRANT_WRITE_URI_PERMISSION: Int = 0x00000002
  const val ACTIVITY_CLEAR_TASK: Int = 0x00008000
  const val ACTIVITY_EXCLUDE_FROM_RECENTS: Int = 0x00800000
  const val ACTIVITY_CLEAR_TOP: Int = 0x04000000
  const val ACTIVITY_NEW_TASK: Int = 0x10000000
  const val ACTIVITY_SINGLE_TOP: Int = 0x20000000
  const val ACTIVITY_NO_HISTORY: Int = 0x40000000

  const val ALLOWED_MASK: Int = ACTIVITY_NEW_TASK or ACTIVITY_CLEAR_TOP or ACTIVITY_SINGLE_TOP

  /** fail-closed：只保留允許位。 */
  fun sanitize(flags: Int): Int = flags and ALLOWED_MASK
}

data class IntentSpec(
  val action: String,
  val dataUri: String? = null,
  val mimeType: String? = null,
  val targetPackage: String? = null,
  val extras: Map<String, IntentExtra> = emptyMap(),
  val flags: Int = 0,
)

/**
 * 啟動結果分型（D2）：`resolveActivity == null`（無處理者）與執行期拋錯
 * 不再混同為同一個 `false`。
 */
sealed interface LaunchResult {
  data object Success : LaunchResult
  data object NoHandler : LaunchResult
  data class Internal(val cause: Throwable? = null) : LaunchResult
}

/** 啟動器抽象：JVM 測試用假實現，端側用 [AndroidIntentLauncher]。 */
fun interface IntentLauncher {
  fun launch(spec: IntentSpec): LaunchResult
}

/** 防禦：假實現自身拋錯時一律收斂為 [LaunchResult.Internal]。 */
internal fun IntentLauncher.launchCatching(spec: IntentSpec): LaunchResult =
  runCatching { launch(spec) }.getOrElse { LaunchResult.Internal(it) }

/** 把啟動分型轉為工具結果（成功分支保留呼叫方指定的 fallback/原因語義）。 */
internal fun LaunchResult.toToolResult(
  spec: IntentSpec,
  verb: String,
  usedFallbackOnSuccess: Boolean = false,
  successReason: String? = null,
): ToolResult = when (this) {
  LaunchResult.Success -> ToolResult(true, usedFallbackOnSuccess, spec, successReason)
  LaunchResult.NoHandler ->
    ToolResult(false, false, spec, ReasonCodes.NO_HANDLER, "做不到$verb（${ReasonCodes.NO_HANDLER}）")
  is LaunchResult.Internal ->
    ToolResult(false, false, spec, ReasonCodes.INTERNAL, "做不到$verb（${ReasonCodes.INTERNAL}）")
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
