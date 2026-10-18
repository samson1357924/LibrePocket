package dev.librepocket.systema

/**
 * 平台無關的 Intent 描述（純 JVM）。Android 側由
 * [AndroidIntentLauncher] 轉成真正的 `android.content.Intent` 並執行
 * resolveActivity → startActivity；單元測試只斷言本層映射。
 *
 * 長度政策註記：本層字串長度上限（[AlarmTool.MAX_MESSAGE_LEN]、
 * [CalendarTool.MAX_TITLE_LEN]/[CalendarTool.MAX_LOCATION_LEN]/
 * [CalendarTool.MAX_DESCRIPTION_LEN]）皆為**本層自訂 fail-closed 政策**
 * （Binder 傳輸防禦），非官方契約；builder/parse 兩側同值，改動須三處同步。
 *
 * D2 型別化 extras：D1 的全 String 會讓端側 `getIntExtra /
 * getLongExtra / getBooleanExtra` 讀回預設值（系統鬧鐘/日曆 App 直接忽略），
 * 故改為密封型別，launcher 按型別 putExtra。
 *
 * 決策註記：float/double 需求出現時再加 DoubleVal + toIntent 分支
 * （不預先加型別；[AndroidIntentLauncher.toIntent] 以窮舉 `when` 表達式
 * （賦值迫使編譯器檢查）保證新增子類未補分支即編譯錯，fail-closed 於編譯期）。
 */
sealed interface IntentExtra {
  data class Str(val value: String) : IntentExtra
  data class LongVal(val value: Long) : IntentExtra
  data class IntVal(val value: Int) : IntentExtra
  data class Bool(val value: Boolean) : IntentExtra
}

/**
 * 型別化讀取：讀側必須用 `is`/`requireExtra`/`optionalExtra`，禁 unsafe cast。
 *
 * 錯型別或缺 key 一律拋 [IllegalStateException]（帶 key + 期望型別），
 * 避免 `ClassCastException` 訊息無 key 難定位。
 *
 * Status: Target（純側契約，未接線未 E2E；矩陣層 `executionReady=false` 概念）。
 * 生產採用（#14）：[AlarmTool.parseParams]、[CalendarTool.parseInsertParams]
 * 以本 helper 讀必填 extra（缺參/錯型明確拋錯，禁預設猜測執行，#19）。
 * 未來 dispatcher 必須在呼叫 parse 側包 try-catch（ISE/IAE），收斂為
 * `ToolResult(INTERNAL)`，不得讓異常穿透生產迴圈；builder 側同形收斂統一以
 * [guardedToolBuild] 實作（見各工具 execute）。
 */
inline fun <reified T : IntentExtra> Map<String, IntentExtra>.requireExtra(key: String): T {
  val v = this[key]
    ?: throw IllegalStateException("extra missing: key=$key expected=${T::class.simpleName}")
  if (v !is T) {
    throw IllegalStateException(
      "extra type mismatch: key=$key expected=${T::class.simpleName} actual=${v::class.simpleName}",
    )
  }
  return v
}

/**
 * 選填 extra 讀取（與 [requireExtra] 同錯誤形狀）：缺席回 null（呼叫方套用
 * 各工具 Params 記載的預設值）；出現但錯型仍拋 [IllegalStateException]，禁靜默忽略
 * 或退回預設（fail-closed，#19 猜測執行禁令）。
 */
inline fun <reified T : IntentExtra> Map<String, IntentExtra>.optionalExtra(key: String): T? {
  val v = this[key] ?: return null
  if (v !is T) {
    throw IllegalStateException(
      "extra type mismatch: key=$key expected=${T::class.simpleName} actual=${v::class.simpleName}",
    )
  }
  return v
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

/**
 * Status: Target（純側契約，未接線未 E2E）。`SystemEnv` 目前無生產 producer
 *（無 `PackageManager.resolveActivity → SystemEnv` 映射，見各工具 `check`
 * KDoc）；測試以假 `SystemEnv` + 假 `IntentLauncher` 直調驗證映射語義，
 * 不得當作端到端能力證據。
 */

/**
 * 純側 intent 描述：launcher 的唯一輸入形狀（KDoc 見檔案頂部契約說明；
 * 欄位語意：`dataUri` 與 `mimeType` 同設時由 launcher 併為
 * `setDataAndType`，`targetPackage` 僅契約白名單場景使用）。
 */
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

/** 防禦：假實現自身拋錯時收斂為 [LaunchResult.Internal]；取消與 VM fatal 直接外洩。 */
internal fun IntentLauncher.launchCatching(spec: IntentSpec): LaunchResult {
  try {
    return launch(spec)
  } catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
  } catch (e: Exception) {
    return LaunchResult.Internal(e)
  }
}

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
    ToolResult(
      false, false, spec, ReasonCodes.INTERNAL,
      // 可行動：附加 cause 類名（不含 PII），區分拒絕/崩潰；仍保留「（INTERNAL）」子串供既有斷言。
      "做不到$verb（${ReasonCodes.INTERNAL}）[${cause?.javaClass?.simpleName ?: "Unknown"}]",
    )
}

/**
 * fail-closed builder guard 的結果（[guardedToolBuild] 回傳）：builder 成功值
 * 或收斂好的 INTERNAL 失敗 [ToolResult]。
 */
internal sealed interface Guarded<out T> {
  /** builder 成功：呼叫方續行既有流程。 */
  data class Built<out T>(val value: T) : Guarded<T>

  /** builder 違規：攜帶收斂好的失敗結果，呼叫方直接回傳它。 */
  data class Internal(val result: ToolResult) : Guarded<Nothing>
}

/**
 * fail-closed builder guard（多個工具 execute 的 inline catch 收斂點）：
 * builder 拋 `IllegalArgumentException`／`IllegalStateException`（範圍/長度/
 * 形狀違規，非取消）時回 [Guarded.Internal]，不讓異常滲出生產迴圈；
 * `CancellationException` 照拋（取消語義不得吞）。各工具 inline catch 的
 * 重複與訊息漂移在此單一化；失敗 ToolResult 由呼叫方提供（保留各自話術），
 * 並附加 cause 類名（與 [LaunchResult.toToolResult] 的 launch 路徑對稱，
 * 讓分派層可區分「參數被本層政策拒絕」與「系統拒絕」；僅附類名，不附
 * 訊息體/參數值，防輸入回顯——`require` 訊息可能內嵌輸入形狀）。
 */
internal inline fun <T> guardedToolBuild(
  internalFailure: ToolResult,
  builder: () -> T,
): Guarded<T> =
  try {
    Guarded.Built(builder())
  } catch (e: IllegalArgumentException) {
    Guarded.Internal(internalFailure.tagInternalCause(e))
  } catch (e: IllegalStateException) {
    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
    Guarded.Internal(internalFailure.tagInternalCause(e))
  }

/** INTERNAL 失敗訊息附加 cause 類名（`做不到 X（INTERNAL）` → `…（INTERNAL）[IAE 類名]`）。 */
private fun ToolResult.tagInternalCause(cause: Throwable): ToolResult =
  copy(message = "$message[${cause.javaClass.simpleName}]")

data class ToolResult(
  val ok: Boolean,
  val usedFallback: Boolean,
  val spec: IntentSpec? = null,
  val reasonCode: String? = null,
  val message: String = "",
)

/**
 * 降級分支描述。話術必須遵守能力矩陣 Current evidence 模板：
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
