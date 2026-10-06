package dev.librepocket.systemb

/**
 * P2 快通道 B 組共用模型（原創）。
 *
 * 設計約束（Play 合規）：
 * - 零 SMS 權限：本包任何路徑不得請求 SEND_SMS / RECEIVE_SMS / READ_SMS。
 * - 零後台位置：位置僅前台，background=true 直接 [ReasonCode.FLAVOR_BLOCKED]。
 * - 通知最小化：預設關，開啟後僅標題（全文需二次同意）。
 * - 本包為純 Kotlin，不依賴 android.* 類，呼叫方在邊緣層把 [IntentSpec]
 *   翻譯為真正的 android.content.Intent（便於 JVM 單測）。
 */

/** 能力投影等級：與 CAPABILITY_MATRIX §1 的 N / D / — 對應。 */
enum class Availability { NATIVE, DEGRADED, UNAVAILABLE }

/** 降級理由碼：來自投影快照，模型降級話術必須原樣引用。 */
enum class ReasonCode {
  OK,
  MISSING_ARG,
  NO_PRIVILEGE,
  USER_DISABLED,
  FLAVOR_BLOCKED,
  NEEDS_FRESH_AUTH,
  UNSUPPORTED,
}

/** 副作用三級（ARCHITECTURE §5.2）。 */
enum class SideEffectLevel { READ, WRITE, PRIVILEGED }

/**
 * 純資料 Intent 描述。呼叫方在 Android 邊緣層按 [action]/[dataUri]/[extras]
 * 組裝真實 Intent，本包永不直接啟動 Activity / 發送廣播。
 */
data class IntentSpec(
  val action: String,
  val dataUri: String? = null,
  val extras: Map<String, String> = emptyMap(),
  val mimeType: String? = null,
)

/** 執行前檢查結果（純函數，無副作用）。 */
data class CheckResult(
  val availability: Availability,
  val reason: ReasonCode,
  val message: String,
)

/**
 * 執行結果。合規要點：被拒絕時 [started] 必為 false 且 [intent] 必為 null，
 * 即「拒絕零請求」——不觸發 Intent、不申請權限。
 */
data class ExecResult(
  val started: Boolean,
  val viaSystemApp: Boolean,
  val intent: IntentSpec?,
  val reason: ReasonCode,
  val note: String,
)

/** 降級回覆：`做不到 X（原因碼）→ 可替代 Y → 需要你做 Z`（矩陣 §5）。 */
data class Fallback(
  val reason: ReasonCode,
  val userMessage: String,
  val manualSteps: List<String>,
)

/** 權限申請出口。B 組生產實作永遠不應被呼叫；單測用假實作斷言零請求。 */
fun interface PermissionSink {
  fun request(permission: String)
}

/** B 組執行環境（呼叫方裝配，均為呼叫時快照）。 */
data class SystemBEnv(
  val flavorPlay: Boolean = true,
  val notificationListenerEnabled: Boolean = false,
  val notificationFullTextConsented: Boolean = false,
  /** MediaProjection 每次授權：僅當次有效，呼叫方負責在調起系統授權頁後置 true。 */
  val screenshotFreshGrant: Boolean = false,
  val permissionSink: PermissionSink = PermissionSink { _ -> },
)

/** B 組工具契約：檢查 / 執行 / 降級。 */
interface SystemBTool {
  val name: String
  val sideEffect: SideEffectLevel
  fun check(env: SystemBEnv, args: Map<String, String> = emptyMap()): CheckResult
  fun execute(env: SystemBEnv, args: Map<String, String> = emptyMap()): ExecResult
  fun fallback(reason: ReasonCode, args: Map<String, String> = emptyMap()): Fallback
}

/** Play 合規審計常量：雙風味皆不可申請。 */
object PlayCompliance {
  const val SEND_SMS = "android.permission.SEND_SMS"
  const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
  const val READ_SMS = "android.permission.READ_SMS"
  const val ACCESS_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
  const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"

  val FORBIDDEN_PERMISSIONS: Set<String> = setOf(
    SEND_SMS,
    RECEIVE_SMS,
    READ_SMS,
    ACCESS_BACKGROUND_LOCATION,
    MANAGE_EXTERNAL_STORAGE,
  )
}
