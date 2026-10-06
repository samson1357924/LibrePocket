package dev.librepocket.jev

/**
 * D01 Jev 最小可用整合：共用模型（JEV_INTEGRATION_DRAFT §4）。
 *
 * 全部為純 JVM 型別，無 Android 依賴，可在普通 JUnit 下測試。
 * 轉錄/審計只允許 id / conf / reasonCodes / latency / cached，
 * 結果物件刻意不攜帶任何原文或截圖位元組。
 */

/** 遠端小模型 pin 版與端點（D01 只走遠端小模型驗證加速比）。 */
object JevModel {
    const val MODEL_ID = "jev-1.13.0"
    const val ENDPOINT_PATH = "/v1/systemone"
    const val HEAD_CHOICE = "choice"
    const val HEAD_SCORE = "score"
    const val HEAD_NOUL = "noul"
}

/** 延遲預算（JEV_INTEGRATION_DRAFT §5 / §6）：Choice 300 / Score 200 / Noul 150，單步合計 800ms。 */
object JevBudgets {
    const val CHOICE_TIMEOUT_MS: Long = 300
    const val SCORE_TIMEOUT_MS: Long = 200
    const val NOUL_TIMEOUT_MS: Long = 150
    const val STEP_TOTAL_MS: Long = 800
}

data class JevThresholds(
    val routeMinConf: Float = 0.75f,
    val groundMinConf: Float = 0.65f,
    val dangerConfirm: Float = 0.60f,
    val autoExecMaxDanger: Float = 0.35f,
)

enum class JevStatus { OK, ABSTAIN, TIMEOUT, UNAVAILABLE, ERROR }

data class ChoiceResult(
    val bestId: String,
    val confidences: Map<String, Float>,
    val abstain: Boolean,
    val status: JevStatus,
    val latencyMs: Long,
    val cached: Boolean = false,
) {
    /** 轉錄只記 id/conf/latency/cached，不記原文。 */
    fun auditMap(): Map<String, Any?> = mapOf(
        "bestId" to bestId,
        "confidence" to (confidences[bestId] ?: 0f),
        "abstain" to abstain,
        "status" to status.name,
        "latencyMs" to latencyMs,
        "cached" to cached,
    )
}

data class ScoreResult(
    val score: Float,
    val reasonCodes: List<String>,
    val status: JevStatus,
    val latencyMs: Long,
    val cached: Boolean = false,
) {
    fun auditMap(): Map<String, Any?> = mapOf(
        "score" to score,
        "reasonCodes" to reasonCodes.toList(),
        "status" to status.name,
        "latencyMs" to latencyMs,
        "cached" to cached,
    )
}

data class NoulResult(
    val needConfirm: Boolean,
    val confidence: Float,
    val status: JevStatus,
    val latencyMs: Long,
    val cached: Boolean = false,
) {
    fun auditMap(): Map<String, Any?> = mapOf(
        "needConfirm" to needConfirm,
        "confidence" to confidence,
        "status" to status.name,
        "latencyMs" to latencyMs,
        "cached" to cached,
    )
}

data class JevCandidate(
    val id: String,
    val label: String,
    val features: Map<String, String> = emptyMap(),
)

/** 副作用等級（草案中文佔位定稿：實作一律用英文 enum）。 */
enum class SideEffect { READ, WRITE, PRIVILEGED }

data class JevContext(
    val sideEffect: SideEffect,
    val projection: String,
    val redactedGoal: String,
)

/**
 * D01 開關：預設關。`enabled=false` 時路由一律回退（shadow 只對賬不執行，
 * [executed][JevRouteDecision.executed] 恆為 false，不觸發任何副作用）。
 */
data class JevConfig(
    val enabled: Boolean = false,
    val shadow: Boolean = true,
    val keyId: String = "jev",
    val baseUrl: String = "",
) {
    companion object {
        fun defaultDisabled(): JevConfig = JevConfig(enabled = false, shadow = true)
        fun enabledForTest(baseUrl: String, shadow: Boolean = false): JevConfig =
            JevConfig(enabled = true, shadow = shadow, baseUrl = baseUrl)
    }
}

/** 三頭傳輸介面（傳輸無關；D01 實作為遠端小模型 [JevClient]）。 */
interface JevChoice {
    suspend fun choose(
        taskText: String,
        candidates: List<JevCandidate>,
        topK: Int = 1,
        timeoutMs: Long = JevBudgets.CHOICE_TIMEOUT_MS,
    ): ChoiceResult
}

interface JevScore {
    suspend fun score(
        action: String,
        context: JevContext,
        timeoutMs: Long = JevBudgets.SCORE_TIMEOUT_MS,
    ): ScoreResult
}

interface JevNoul {
    suspend fun gate(
        action: String,
        score: ScoreResult?,
        context: JevContext,
        timeoutMs: Long = JevBudgets.NOUL_TIMEOUT_MS,
    ): NoulResult
}

/**
 * 意圖路由 12 封閉候選（JEV_INTEGRATION_DRAFT §3a）：
 * 11 快通道 + GUI_FALLBACK 兜底。另設 other 逃生：模型回傳未知 id
 * 一律視為無把握，走大模型/澄清，不硬猜。
 */
object JevIntents {
    const val NAVIGATE = "NAVIGATE"
    const val OPEN_APP = "OPEN_APP"
    const val MAIL_DRAFT = "MAIL_DRAFT"
    const val ALARM = "ALARM"
    const val DIAL = "DIAL"
    const val SMS_PREFILL = "SMS_PREFILL"
    const val CALENDAR = "CALENDAR"
    const val MUSIC = "MUSIC"
    const val VOLUME = "VOLUME"
    const val NOTIFICATION_READ = "NOTIFICATION_READ"
    const val SCREENSHOT = "SCREENSHOT"
    const val GUI_FALLBACK = "GUI_FALLBACK"

    /** 封閉集的穩定順序（快取鍵與預設候選共用）。 */
    val ORDER: List<String> = listOf(
        NAVIGATE, OPEN_APP, MAIL_DRAFT, ALARM, DIAL, SMS_PREFILL,
        CALENDAR, MUSIC, VOLUME, NOTIFICATION_READ, SCREENSHOT, GUI_FALLBACK,
    )

    /** 特權動作：即使高置信仍走系統接管，Jev 無權跳過（§3a / §6 風控）。 */
    val PRIVILEGED_IDS: Set<String> = setOf(DIAL, SMS_PREFILL, SCREENSHOT, NOTIFICATION_READ)

    fun isKnown(id: String): Boolean = id in ORDER

    fun needsSystemHandoff(id: String): Boolean = id in PRIVILEGED_IDS

    /** 預設 12 候選（label 為脫敏後短文本，不含任何使用者原文）。 */
    fun defaultCandidates(): List<JevCandidate> = ORDER.map { id ->
        JevCandidate(id = id, label = id.lowercase().replace('_', ' '))
    }
}
