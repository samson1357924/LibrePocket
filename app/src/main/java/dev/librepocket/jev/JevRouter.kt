package dev.librepocket.jev

import dev.librepocket.redact.Redactor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** 路由回退原因（寫轉錄，不含原文）。 */
enum class JevFallbackCause {
    DISABLED,
    CIRCUIT_OPEN,
    UNAVAILABLE,
    TIMEOUT,
    ERROR,
    ABSTAIN,
    LOW_CONFIDENCE,
    GUI_FALLBACK,
    UNKNOWN_ID,
    NEED_CONFIRM_UPGRADE,
}

/** 意圖路由決策：Fast 直達 / 回大模型 / 澄清。shadow 下一律 executed=false（只對賬不執行）。 */
sealed interface JevRouteDecision {
    val result: ChoiceResult
    val shadow: Boolean
    val executed: Boolean
}

data class JevFast(
    val bestId: String,
    val confidence: Float,
    /** 特權動作即使高置信仍需系統接管（Jev 無權跳過）。 */
    val needsSystemHandoff: Boolean,
    override val result: ChoiceResult,
    override val shadow: Boolean,
    override val executed: Boolean,
) : JevRouteDecision

data class JevLlmFallback(
    val cause: JevFallbackCause,
    override val result: ChoiceResult,
    override val shadow: Boolean,
    override val executed: Boolean = false,
) : JevRouteDecision

data class JevClarify(
    val hint: String,
    val cause: JevFallbackCause = JevFallbackCause.LOW_CONFIDENCE,
    override val result: ChoiceResult,
    override val shadow: Boolean,
    override val executed: Boolean = false,
) : JevRouteDecision

/** Grounding 重排決策：命中 top1 / 回退 VLM 看一次。 */
sealed interface JevRerankDecision {
    val result: ChoiceResult
    val shadow: Boolean
}

data class JevRerankHit(
    val nodeId: String,
    val confidence: Float,
    override val result: ChoiceResult,
    override val shadow: Boolean,
) : JevRerankDecision

data class JevRerankFallback(
    val cause: JevFallbackCause,
    override val result: ChoiceResult,
    override val shadow: Boolean,
) : JevRerankDecision

/** 風控決策：Score + Noul 合成，規則優先（PRIVILEGED 一律確認；Jev 只收緊不放寬）。 */
data class JevRiskDecision(
    val score: ScoreResult,
    val noul: NoulResult,
    val needConfirm: Boolean,
    /** 本步是否允許自動執行（shadow 下恆 false）。 */
    val autoExecute: Boolean,
    val shadow: Boolean,
)

interface JevRouter {
    suspend fun routeIntent(
        rawText: String,
        projectionTools: String = "",
        flavor: String = "play",
        candidates: List<JevCandidate> = JevIntents.defaultCandidates(),
    ): JevRouteDecision

    suspend fun rerankNodes(
        rawGoal: String,
        nodes: List<JevCandidate>,
        pageSignature: String = "",
    ): JevRerankDecision

    suspend fun checkRisk(rawAction: String, ctx: JevContext): JevRiskDecision

    fun thresholds(): JevThresholds
    fun config(): JevConfig
}

/**
 * D01 編排門面（JEV_INTEGRATION_DRAFT §3a/§5）：
 * 閾值判斷 + 超時轉 abstain + 熔斷 + 快取 + 轉錄欄位，預設關（shadow 只對賬不執行）。
 */
class JevRouterImpl(
    private val choice: JevChoice,
    private val score: JevScore,
    private val noul: JevNoul,
    private val cache: JevCache = JevCache(),
    private val breaker: JevCircuitBreaker = JevCircuitBreaker(),
    private val jevConfig: JevConfig = JevConfig.defaultDisabled(),
    private val jevThresholds: JevThresholds = JevThresholds(),
) : JevRouter {

    override fun thresholds(): JevThresholds = jevThresholds
    override fun config(): JevConfig = jevConfig

    // ---- 意圖路由 ----

    override suspend fun routeIntent(
        rawText: String,
        projectionTools: String,
        flavor: String,
        candidates: List<JevCandidate>,
    ): JevRouteDecision {
        val shadow = jevConfig.shadow || !jevConfig.enabled
        if (!jevConfig.enabled) {
            // 預設關：零模型調用，直接回退（等價無 Jev），只標 shadow 對賬。
            return JevLlmFallback(
                cause = JevFallbackCause.DISABLED,
                result = ChoiceResult(
                    bestId = JevIntents.GUI_FALLBACK,
                    confidences = emptyMap(),
                    abstain = true,
                    status = JevStatus.UNAVAILABLE,
                    latencyMs = 0,
                ),
                shadow = true,
                executed = false,
            )
        }
        // 輸入先脫敏：快取鍵與模型輸入一律用脫敏文本。
        val redacted = Redactor.redact(rawText).text
        val safeCandidates = if (candidates.isEmpty()) JevIntents.defaultCandidates() else candidates.take(20)
        val key = cache.routeKey(redacted, projectionTools, flavor)
        cache.getRoute(key)?.let { hit ->
            return decideRoute(hit, redacted, shadow)
        }
        if (!breaker.canCall()) {
            return JevLlmFallback(
                cause = JevFallbackCause.CIRCUIT_OPEN,
                result = ChoiceResult(
                    bestId = JevIntents.GUI_FALLBACK,
                    confidences = emptyMap(),
                    abstain = true,
                    status = JevStatus.UNAVAILABLE,
                    latencyMs = 0,
                ),
                shadow = shadow,
            )
        }
        val result = try {
            withTimeout(JevBudgets.CHOICE_TIMEOUT_MS) {
                choice.choose(redacted, safeCandidates, topK = 1, timeoutMs = JevBudgets.CHOICE_TIMEOUT_MS)
            }
        } catch (_: TimeoutCancellationException) {
            ChoiceResult(
                bestId = JevIntents.GUI_FALLBACK,
                confidences = emptyMap(),
                abstain = true,
                status = JevStatus.TIMEOUT,
                latencyMs = JevBudgets.CHOICE_TIMEOUT_MS,
            )
        } catch (_: Exception) {
            ChoiceResult(
                bestId = JevIntents.GUI_FALLBACK,
                confidences = emptyMap(),
                abstain = true,
                status = JevStatus.ERROR,
                latencyMs = 0,
            )
        }
        breaker.onResult(result.status)
        if (result.status == JevStatus.OK || result.status == JevStatus.ABSTAIN) {
            cache.putRoute(key, result)
        }
        return decideRoute(result, redacted, shadow)
    }

    private fun decideRoute(result: ChoiceResult, redactedText: String, shadow: Boolean): JevRouteDecision {
        val executable = !shadow
        // 傳輸/熔斷層：非 OK/ABSTAIN 一律回退。
        if (result.status == JevStatus.TIMEOUT) {
            return JevLlmFallback(JevFallbackCause.TIMEOUT, result, shadow)
        }
        if (result.status == JevStatus.ERROR || result.status == JevStatus.UNAVAILABLE) {
            val cause = if (result.status == JevStatus.UNAVAILABLE) JevFallbackCause.UNAVAILABLE else JevFallbackCause.ERROR
            return JevLlmFallback(cause, result, shadow)
        }
        if (result.abstain) return JevLlmFallback(JevFallbackCause.ABSTAIN, result, shadow)
        val best = result.bestId
        // other 逃生：未知 id 不硬猜。
        if (!JevIntents.isKnown(best)) {
            return JevLlmFallback(JevFallbackCause.UNKNOWN_ID, result, shadow)
        }
        if (best == JevIntents.GUI_FALLBACK) {
            return JevLlmFallback(JevFallbackCause.GUI_FALLBACK, result, shadow)
        }
        val conf = result.confidences[best] ?: 0f
        if (conf < jevThresholds.routeMinConf) {
            // 低置信轉大模型；模糊表述轉澄清（不猜包名/地點/時間）。
            if (isFuzzyText(redactedText) || looksAmbiguousHint(best, conf)) {
                return JevClarify(
                    hint = clarifyHintFor(best),
                    result = result,
                    shadow = shadow,
                )
            }
            return JevLlmFallback(JevFallbackCause.LOW_CONFIDENCE, result, shadow)
        }
        return JevFast(
            bestId = best,
            confidence = conf,
            needsSystemHandoff = JevIntents.needsSystemHandoff(best),
            result = result,
            shadow = shadow,
            executed = executable,
        )
    }

    // ---- Grounding 重排 ----

    override suspend fun rerankNodes(
        rawGoal: String,
        nodes: List<JevCandidate>,
        pageSignature: String,
    ): JevRerankDecision {
        val shadow = jevConfig.shadow || !jevConfig.enabled
        if (!jevConfig.enabled) {
            return JevRerankFallback(
                cause = JevFallbackCause.DISABLED,
                result = ChoiceResult(
                    bestId = "",
                    confidences = emptyMap(),
                    abstain = true,
                    status = JevStatus.UNAVAILABLE,
                    latencyMs = 0,
                ),
                shadow = true,
            )
        }
        // N≤20 截斷：候選生成輕量，不走大模型。
        val safeNodes = nodes.take(20)
        if (safeNodes.isEmpty()) {
            return JevRerankFallback(
                cause = JevFallbackCause.ABSTAIN,
                result = ChoiceResult("", emptyMap(), true, JevStatus.ABSTAIN, 0),
                shadow = shadow,
            )
        }
        val redactedGoal = Redactor.redact(rawGoal).text
        val key = cache.rerankKey(redactedGoal, pageSignature)
        cache.getRerank(key)?.let { hit -> return decideRerank(hit, shadow) }
        if (!breaker.canCall()) {
            return JevRerankFallback(
                cause = JevFallbackCause.CIRCUIT_OPEN,
                result = ChoiceResult("", emptyMap(), true, JevStatus.UNAVAILABLE, 0),
                shadow = shadow,
            )
        }
        val result = try {
            withTimeout(JevBudgets.CHOICE_TIMEOUT_MS) {
                choice.choose(redactedGoal, safeNodes, topK = 1, timeoutMs = JevBudgets.CHOICE_TIMEOUT_MS)
            }
        } catch (_: TimeoutCancellationException) {
            ChoiceResult("", emptyMap(), true, JevStatus.TIMEOUT, JevBudgets.CHOICE_TIMEOUT_MS)
        } catch (_: Exception) {
            ChoiceResult("", emptyMap(), true, JevStatus.ERROR, 0)
        }
        breaker.onResult(result.status)
        if (result.status == JevStatus.OK || result.status == JevStatus.ABSTAIN) {
            cache.putRerank(key, result)
        }
        return decideRerank(result, shadow)
    }

    private fun decideRerank(result: ChoiceResult, shadow: Boolean): JevRerankDecision {
        if (result.status != JevStatus.OK || result.abstain) {
            val cause = when (result.status) {
                JevStatus.TIMEOUT -> JevFallbackCause.TIMEOUT
                JevStatus.ERROR -> JevFallbackCause.ERROR
                JevStatus.UNAVAILABLE -> JevFallbackCause.UNAVAILABLE
                else -> JevFallbackCause.ABSTAIN
            }
            return JevRerankFallback(cause, result, shadow)
        }
        val conf = result.confidences[result.bestId] ?: 0f
        // 低置信升級 VLM（呼叫方據此計數 mock=1）；此處只回決策，不直接調 VLM。
        if (conf < jevThresholds.groundMinConf) {
            return JevRerankFallback(JevFallbackCause.NEED_CONFIRM_UPGRADE, result, shadow)
        }
        return JevRerankHit(nodeId = result.bestId, confidence = conf, result = result, shadow = shadow)
    }

    // ---- 仲裁/風控 ----

    override suspend fun checkRisk(rawAction: String, ctx: JevContext): JevRiskDecision {
        val shadow = jevConfig.shadow || !jevConfig.enabled
        // PRIVILEGED 一律確認：規則勝出（即使 Jev 關閉也不放行）。
        if (ctx.sideEffect == SideEffect.PRIVILEGED) {
            val s = ScoreResult(0.9f, listOf("PRIVILEGED"), JevStatus.OK, 0)
            val n = NoulResult(true, 1.0f, JevStatus.OK, 0)
            return JevRiskDecision(s, n, needConfirm = true, autoExecute = false, shadow = shadow)
        }
        if (!jevConfig.enabled) {
            // 等價無 Jev：WRITE 以上保守 ASK（NoOp 語義）。
            val s = ScoreResult(
                score = if (ctx.sideEffect == SideEffect.READ) 0.3f else 0.6f,
                reasonCodes = listOf("JEV_DISABLED"),
                status = JevStatus.UNAVAILABLE,
                latencyMs = 0,
            )
            val n = NoulResult(
                needConfirm = ctx.sideEffect != SideEffect.READ,
                confidence = 0.5f,
                status = JevStatus.UNAVAILABLE,
                latencyMs = 0,
            )
            return JevRiskDecision(s, n, needConfirm = n.needConfirm, autoExecute = false, shadow = true)
        }
        // 脫敏後查快取：只命中 READ 低風險。
        val redactedAction = Redactor.redact(rawAction).text
        val redactedGoal = Redactor.redact(ctx.redactedGoal).text
        val safeCtx = ctx.copy(redactedGoal = redactedGoal, projection = ctx.projection.take(500))
        val actionName = actionNameOf(redactedAction)
        val paramShape = paramShapeOf(redactedAction)
        val rkey = cache.riskKey(actionName, paramShape, safeCtx.sideEffect)
        cache.getRisk(rkey)?.let { hit ->
            val floored = applyRuleFloor(redactedAction, hit)
            val need = needConfirmFor(floored, safeCtx)
            return JevRiskDecision(
                score = floored,
                noul = NoulResult(need, 0.8f, floored.status, 0, cached = true),
                needConfirm = need,
                autoExecute = !shadow && !need && floored.score < jevThresholds.autoExecMaxDanger,
                shadow = shadow,
            )
        }
        if (!breaker.canCall()) {
            val s = ScoreResult(0.6f, listOf("CIRCUIT_OPEN"), JevStatus.UNAVAILABLE, 0)
            val n = NoulResult(true, 0.5f, JevStatus.UNAVAILABLE, 0)
            return JevRiskDecision(s, n, needConfirm = true, autoExecute = false, shadow = shadow)
        }
        // Score+Noul 並行取 max，單步合計 800ms 上限；超限轉保守確認。
        val pair: Pair<ScoreResult, NoulResult> = try {
            withTimeout(JevBudgets.STEP_TOTAL_MS) {
                supervisorScope {
                    val s = async {
                        try {
                            withTimeout(JevBudgets.SCORE_TIMEOUT_MS) {
                                score.score(redactedAction, safeCtx, JevBudgets.SCORE_TIMEOUT_MS)
                            }
                        } catch (_: TimeoutCancellationException) {
                            ScoreResult(0.6f, listOf("TIMEOUT"), JevStatus.TIMEOUT, JevBudgets.SCORE_TIMEOUT_MS)
                        } catch (_: Exception) {
                            ScoreResult(0.6f, listOf("TRANSPORT"), JevStatus.ERROR, 0)
                        }
                    }
                    val scoreRes = s.await()
                    val n = try {
                        withTimeout(JevBudgets.NOUL_TIMEOUT_MS) {
                            noul.gate(redactedAction, scoreRes, safeCtx, JevBudgets.NOUL_TIMEOUT_MS)
                        }
                    } catch (_: TimeoutCancellationException) {
                        NoulResult(true, 0.5f, JevStatus.TIMEOUT, JevBudgets.NOUL_TIMEOUT_MS)
                    } catch (_: Exception) {
                        NoulResult(true, 0.5f, JevStatus.ERROR, 0)
                    }
                    scoreRes to n
                }
            }
        } catch (_: TimeoutCancellationException) {
            ScoreResult(0.6f, listOf("STEP_TIMEOUT"), JevStatus.TIMEOUT, JevBudgets.STEP_TOTAL_MS) to
                NoulResult(true, 0.5f, JevStatus.TIMEOUT, JevBudgets.STEP_TOTAL_MS)
        }
        var (sRes, nRes) = pair
        breaker.onResult(sRes.status)
        if (sRes.status != JevStatus.OK) breaker.onResult(nRes.status)
        // 規則先行：支付/刪除/發送/授權類直接打高分，不依賴模型。
        sRes = applyRuleFloor(redactedAction, sRes)
        // 風控快取只收低風險。
        cache.putRisk(rkey, sRes, safeCtx.sideEffect)
        // 單向收緊：Jev 永不放寬 policy DENY，只能收緊（此處只體現為 needConfirm 合取）。
        val need = needConfirmFor(sRes, safeCtx) || nRes.needConfirm
        val auto = !shadow && !need && sRes.score < jevThresholds.autoExecMaxDanger &&
            sRes.status == JevStatus.OK && nRes.status == JevStatus.OK
        return JevRiskDecision(sRes, nRes.copy(needConfirm = need), need, auto, shadow)
    }

    private fun needConfirmFor(s: ScoreResult, ctx: JevContext): Boolean {
        if (ctx.sideEffect == SideEffect.PRIVILEGED) return true
        if (ctx.sideEffect == SideEffect.WRITE && s.score >= jevThresholds.dangerConfirm) return true
        return false
    }

    internal fun applyRuleFloor(redactedAction: String, s: ScoreResult): ScoreResult {
        val hit = HIGH_RISK_HINTS.any { redactedAction.contains(it, ignoreCase = true) } ||
            s.reasonCodes.any { rc -> HIGH_RISK_HINTS.any { rc.equals(it, ignoreCase = true) } }
        if (!hit) return s
        if (s.score >= RULE_FLOOR) return s
        return s.copy(score = RULE_FLOOR, reasonCodes = (s.reasonCodes + "RULE_FLOOR").distinct().take(8))
    }

    private fun actionNameOf(redactedAction: String): String {
        // 動作名取首 token（去值後的形狀由 paramShape 承擔）。
        val head = redactedAction.trim().substringBefore(' ').substringBefore('{').trim()
        return head.take(64).ifEmpty { "action" }
    }

    private fun paramShapeOf(redactedAction: String): String {
        // 參數形狀：只保留鍵名/型別痕跡，去值（數字/字串內容折疊為 #）。
        return redactedAction.replace(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+"), "#")
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\"[^\"]*\""), "\"#\"")
            .take(200)
    }

    companion object {
        internal const val RULE_FLOOR = 0.65f
        internal val HIGH_RISK_HINTS: List<String> = listOf(
            "PAYMENT", "DELETE", "SEND", "PRIV_CMD", "AUTH_STATE",
            "支付", "轉帳", "转账", "刪除", "删除", "發送", "发送", "提權", "提权",
            "payment", "transfer", "delete", "send",
        )
        private val FUZZY_TOKENS: List<String> = listOf(
            "哪", "嗎", "吗", "?", "？", "隨便", "随便", "都可以", "那個", "那个",
            "這個", "这个", "幫我弄", "帮我弄",
        )

        /** 模糊包名/地點/時間不猜：低置信 + 模糊表述 → 澄清。 */
        internal fun isFuzzyText(redactedText: String): Boolean =
            FUZZY_TOKENS.any { redactedText.contains(it) }

        internal fun looksAmbiguousHint(bestId: String, conf: Float): Boolean {
            // bestId 為候選 id（無原文），此處僅作佔位啟發式：
            // 真正的模糊判斷由呼叫方以原文另行觸發；為可測，conf 極低視為需澄清。
            return conf < 0.4f || bestId == JevIntents.OPEN_APP
        }

        internal fun clarifyHintFor(bestId: String): String = when (bestId) {
            JevIntents.OPEN_APP -> "請指名要開的 App（模糊包名不猜）"
            JevIntents.NAVIGATE -> "請補充地點（模糊地點不猜）"
            JevIntents.ALARM -> "請補充時間（模糊時間不猜）"
            else -> "請補充細節或換個說法"
        }
    }
}

/**
 * Jev 不可用時的全降級（JEV_INTEGRATION_DRAFT §5）：
 * Choice 永遠 abstain；Score 對 WRITE 以上給 0.6（觸發確認）；
 * Noul 對 PRIVILEGED 永遠 needConfirm。方向只收緊。
 */
class NoOpJevRouter(
    private val jevThresholds: JevThresholds = JevThresholds(),
) : JevRouter {
    override fun thresholds(): JevThresholds = jevThresholds
    override fun config(): JevConfig = JevConfig.defaultDisabled()

    override suspend fun routeIntent(
        rawText: String,
        projectionTools: String,
        flavor: String,
        candidates: List<JevCandidate>,
    ): JevRouteDecision = JevLlmFallback(
        cause = JevFallbackCause.UNAVAILABLE,
        result = ChoiceResult(
            bestId = JevIntents.GUI_FALLBACK,
            confidences = emptyMap(),
            abstain = true,
            status = JevStatus.UNAVAILABLE,
            latencyMs = 0,
        ),
        shadow = false,
    )

    override suspend fun rerankNodes(
        rawGoal: String,
        nodes: List<JevCandidate>,
        pageSignature: String,
    ): JevRerankDecision = JevRerankFallback(
        cause = JevFallbackCause.UNAVAILABLE,
        result = ChoiceResult("", emptyMap(), true, JevStatus.UNAVAILABLE, 0),
        shadow = false,
    )

    override suspend fun checkRisk(rawAction: String, ctx: JevContext): JevRiskDecision {
        val s = ScoreResult(
            score = when (ctx.sideEffect) {
                SideEffect.READ -> 0.3f
                SideEffect.WRITE -> 0.6f
                SideEffect.PRIVILEGED -> 0.9f
            },
            reasonCodes = listOf("NO_JEV_CONSERVATIVE"),
            status = JevStatus.UNAVAILABLE,
            latencyMs = 0,
        )
        val need = ctx.sideEffect != SideEffect.READ || s.score >= jevThresholds.dangerConfirm ||
            ctx.sideEffect == SideEffect.PRIVILEGED
        val n = NoulResult(needConfirm = need, confidence = 0.5f, status = JevStatus.UNAVAILABLE, latencyMs = 0)
        return JevRiskDecision(s, n, needConfirm = need, autoExecute = false, shadow = false)
    }
}
