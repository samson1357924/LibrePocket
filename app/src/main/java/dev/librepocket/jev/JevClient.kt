package dev.librepocket.jev

import dev.librepocket.keystore.KeyVault
import dev.librepocket.redact.Redactor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * D01 遠端小模型客戶端：POST `{baseUrl}/v1/systemone`，model pin [JevModel.MODEL_ID]。
 *
 * 線路約定（本包原創，問題 key 對齊草案 §4 欄位名）：
 * - 通用：`{"model":"jev-1.13.0","head":"choice|score|noul","question":{...}}`
 * - choice question：`{"taskText":"<已脫敏>","candidates":[{"id":"..","label":".."}],"topK":1}`
 * - score question：`{"action":"<已脫敏>","sideEffect":"READ|WRITE|PRIVILEGED","projection":"..","goal":"<已脫敏>"}`
 * - noul question：`{"action":"..","score":0.0..1,"reasonCodes":[..],"sideEffect":"..","projection":".."}`
 * - 回應 choice：`{"bestId":"..","confidences":{"..":0.9},"abstain":false}`
 * - 回應 score：`{"score":0.2,"reasonCodes":[".."]}`
 * - 回應 noul：`{"needConfirm":false,"confidence":0.9}`
 *
 * 隱私：
 * - 入口一律先過 [Redactor]；請求體永不含截圖像素鍵
 *  （`bytes/bitmap/pixels/image_base64/screenshot_png` 零出現，測試斷言）。
 * - Key 走 [KeyVault]（[keyId]，預設 `"jev"`），不硬編碼；用後即擦除 CharArray。
 * - 超時即視為 abstain/保守確認，不重試（重試留給上層 TurnController）。
 */
class JevClient(
    private val baseUrl: String,
    private val keyVault: KeyVault,
    private val keyId: String = "jev",
    private val http: OkHttpClient = defaultHttp(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) : JevChoice, JevScore, JevNoul {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_BODY_CHARS = 32_768

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        internal fun endpoint(baseUrl: String): String =
            baseUrl.trimEnd('/') + JevModel.ENDPOINT_PATH
    }

    // ---- Choice ----

    override suspend fun choose(
        taskText: String,
        candidates: List<JevCandidate>,
        topK: Int,
        timeoutMs: Long,
    ): ChoiceResult {
        val start = clockMs()
        // 先脫敏後送：原文不出端。
        val redacted = Redactor.redact(taskText).text.take(500)
        val safeCandidates = candidates.take(20).map {
            JevCandidate(
                id = it.id.take(64),
                // 候選描述同樣脫敏（只存短文本特徵，不含原文細節）。
                label = Redactor.redact(it.label).text.take(120),
                features = it.features.mapValues { (_, v) -> Redactor.redact(v).text.take(120) },
            )
        }
        val key = keyVault.getKey(keyId)
        if (key == null || baseUrl.isBlank()) {
            key?.fill('\u0000')
            return ChoiceResult(
                bestId = JevIntents.GUI_FALLBACK,
                confidences = emptyMap(),
                abstain = true,
                status = JevStatus.UNAVAILABLE,
                latencyMs = clockMs() - start,
            )
        }
        try {
            val body = JevWire.choiceBody(redacted, safeCandidates, topK)
            val raw = post(body, String(key), timeoutMs)
            key.fill('\u0000')
            if (raw == null) {
                return ChoiceResult(
                    bestId = JevIntents.GUI_FALLBACK,
                    confidences = emptyMap(),
                    abstain = true,
                    status = JevStatus.TIMEOUT,
                    latencyMs = clockMs() - start,
                )
            }
            val (code, text) = raw
            if (code !in 200..299 || text == null) {
                return ChoiceResult(
                    bestId = JevIntents.GUI_FALLBACK,
                    confidences = emptyMap(),
                    abstain = true,
                    status = JevStatus.ERROR,
                    latencyMs = clockMs() - start,
                )
            }
            return try {
                JevWire.parseChoice(text).copy(latencyMs = clockMs() - start)
            } catch (_: IllegalArgumentException) {
                ChoiceResult(
                    bestId = JevIntents.GUI_FALLBACK,
                    confidences = emptyMap(),
                    abstain = true,
                    status = JevStatus.ERROR,
                    latencyMs = clockMs() - start,
                )
            }
        } catch (e: TimeoutCancellationException) {
            try { key.fill('\u0000') } catch (_: Exception) { }
            throw e // 讓上層以 TIMEOUT 計；此處不吞超時（由呼叫點映射）。
        } catch (_: Exception) {
            try { key.fill('\u0000') } catch (_: Exception) { }
            return ChoiceResult(
                bestId = JevIntents.GUI_FALLBACK,
                confidences = emptyMap(),
                abstain = true,
                status = JevStatus.ERROR,
                latencyMs = clockMs() - start,
            )
        }
    }

    // ---- Score ----

    override suspend fun score(
        action: String,
        context: JevContext,
        timeoutMs: Long,
    ): ScoreResult {
        val start = clockMs()
        val redactedAction = Redactor.redact(action).text.take(500)
        val redactedGoal = Redactor.redact(context.redactedGoal).text.take(500)
        val projection = context.projection.take(500)
        val key = keyVault.getKey(keyId)
        if (key == null || baseUrl.isBlank()) {
            key?.fill('\u0000')
            return ScoreResult(
                score = conservativeScore(context.sideEffect),
                reasonCodes = listOf("JEV_UNAVAILABLE"),
                status = JevStatus.UNAVAILABLE,
                latencyMs = clockMs() - start,
            )
        }
        try {
            val body = JevWire.scoreBody(redactedAction, context.sideEffect, projection, redactedGoal)
            val raw = post(body, String(key), timeoutMs)
            key.fill('\u0000')
            if (raw == null) {
                return ScoreResult(
                    score = conservativeScore(context.sideEffect),
                    reasonCodes = listOf("TIMEOUT"),
                    status = JevStatus.TIMEOUT,
                    latencyMs = clockMs() - start,
                )
            }
            val (code, text) = raw
            if (code !in 200..299 || text == null) {
                return ScoreResult(
                    score = conservativeScore(context.sideEffect),
                    reasonCodes = listOf("HTTP_$code"),
                    status = JevStatus.ERROR,
                    latencyMs = clockMs() - start,
                )
            }
            return try {
                JevWire.parseScore(text).copy(latencyMs = clockMs() - start)
            } catch (_: IllegalArgumentException) {
                ScoreResult(
                    score = conservativeScore(context.sideEffect),
                    reasonCodes = listOf("BAD_RESPONSE"),
                    status = JevStatus.ERROR,
                    latencyMs = clockMs() - start,
                )
            }
        } catch (e: TimeoutCancellationException) {
            try { key.fill('\u0000') } catch (_: Exception) { }
            throw e
        } catch (_: Exception) {
            try { key.fill('\u0000') } catch (_: Exception) { }
            return ScoreResult(
                score = conservativeScore(context.sideEffect),
                reasonCodes = listOf("TRANSPORT"),
                status = JevStatus.ERROR,
                latencyMs = clockMs() - start,
            )
        }
    }

    // ---- Noul ----

    override suspend fun gate(
        action: String,
        score: ScoreResult?,
        context: JevContext,
        timeoutMs: Long,
    ): NoulResult {
        val start = clockMs()
        // PRIVILEGED 一律確認：規則勝出，不依賴模型（§3c）。
        if (context.sideEffect == SideEffect.PRIVILEGED) {
            return NoulResult(
                needConfirm = true,
                confidence = 1.0f,
                status = JevStatus.OK,
                latencyMs = clockMs() - start,
            )
        }
        val redactedAction = Redactor.redact(action).text.take(500)
        val key = keyVault.getKey(keyId)
        if (key == null || baseUrl.isBlank()) {
            key?.fill('\u0000')
            val s = score?.score ?: 0.6f
            return NoulResult(
                needConfirm = s >= 0.6f || context.sideEffect != SideEffect.READ,
                confidence = 0.5f,
                status = JevStatus.UNAVAILABLE,
                latencyMs = clockMs() - start,
            )
        }
        try {
            val body = JevWire.noulBody(
                redactedAction,
                score,
                context.sideEffect,
                context.projection.take(500),
            )
            val raw = post(body, String(key), timeoutMs)
            key.fill('\u0000')
            if (raw == null) {
                return NoulResult(
                    needConfirm = true,
                    confidence = 0.5f,
                    status = JevStatus.TIMEOUT,
                    latencyMs = clockMs() - start,
                )
            }
            val (code, text) = raw
            if (code !in 200..299 || text == null) {
                return NoulResult(
                    needConfirm = true,
                    confidence = 0.5f,
                    status = JevStatus.ERROR,
                    latencyMs = clockMs() - start,
                )
            }
            return try {
                JevWire.parseNoul(text).copy(latencyMs = clockMs() - start)
            } catch (_: IllegalArgumentException) {
                NoulResult(
                    needConfirm = true,
                    confidence = 0.5f,
                    status = JevStatus.ERROR,
                    latencyMs = clockMs() - start,
                )
            }
        } catch (e: TimeoutCancellationException) {
            try { key.fill('\u0000') } catch (_: Exception) { }
            throw e
        } catch (_: Exception) {
            try { key.fill('\u0000') } catch (_: Exception) { }
            return NoulResult(
                needConfirm = true,
                confidence = 0.5f,
                status = JevStatus.ERROR,
                latencyMs = clockMs() - start,
            )
        }
    }

    /**
     * POST 一次。回傳 null = 本步超時（呼叫方映射為 TIMEOUT，不重試）；
     * 非 2xx 連同 code 回傳，由各頭映射為 ERROR。
     */
    private suspend fun post(bodyJson: String, bearer: String, timeoutMs: Long): Pair<Int, String?>? {
        val url = endpoint(baseUrl)
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON))
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .build()
        val call = http.newCall(request)
        currentCoroutineContext().job?.invokeOnCompletion { if (call.isCanceled().not()) call.cancel() }
        try {
            return withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    call.execute().use { resp ->
                        val code = resp.code
                        val text = try {
                            resp.body?.string()?.take(MAX_BODY_CHARS)
                        } catch (_: IOException) {
                            null
                        }
                        code to text
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            try { call.cancel() } catch (_: Exception) { }
            return null
        }
    }

    private fun conservativeScore(sideEffect: SideEffect): Float = when (sideEffect) {
        SideEffect.READ -> 0.3f
        SideEffect.WRITE -> 0.6f
        SideEffect.PRIVILEGED -> 0.9f
    }
}

/** 線路編解碼（本包原創小工具；只支援 D01 三頭形狀，惡形輸入拋 IllegalArgumentException）。 */
internal object JevWire {

    fun choiceBody(taskText: String, candidates: List<JevCandidate>, topK: Int): String {
        val sb = StringBuilder()
        sb.append("{\"model\":")
        sb.append(q(JevModel.MODEL_ID))
        sb.append(",\"head\":")
        sb.append(q(JevModel.HEAD_CHOICE))
        sb.append(",\"question\":{\"taskText\":")
        sb.append(q(taskText))
        sb.append(",\"candidates\":[")
        candidates.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(q(c.id)).append(",\"label\":").append(q(c.label)).append('}')
        }
        sb.append("],\"topK\":").append(topK.coerceIn(1, 5)).append("}}")
        return sb.toString()
    }

    fun scoreBody(action: String, sideEffect: SideEffect, projection: String, goal: String): String {
        return "{\"model\":" + q(JevModel.MODEL_ID) +
            ",\"head\":" + q(JevModel.HEAD_SCORE) +
            ",\"question\":{\"action\":" + q(action) +
            ",\"sideEffect\":" + q(sideEffect.name) +
            ",\"projection\":" + q(projection) +
            ",\"goal\":" + q(goal) + "}}"
    }

    fun noulBody(
        action: String,
        score: ScoreResult?,
        sideEffect: SideEffect,
        projection: String,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(q(JevModel.MODEL_ID))
        sb.append(",\"head\":").append(q(JevModel.HEAD_NOUL))
        sb.append(",\"question\":{\"action\":").append(q(action))
        sb.append(",\"score\":").append(score?.score ?: 0.5f)
        sb.append(",\"reasonCodes\":[")
        (score?.reasonCodes ?: emptyList()).forEachIndexed { i, rc ->
            if (i > 0) sb.append(',')
            sb.append(q(rc.take(32)))
        }
        sb.append("],\"sideEffect\":").append(q(sideEffect.name))
        sb.append(",\"projection\":").append(q(projection)).append("}}")
        return sb.toString()
    }

    fun parseChoice(json: String): ChoiceResult {
        val bestId = stringField(json, "bestId") ?: throw IllegalArgumentException("choice: missing bestId")
        val abstain = boolField(json, "abstain") ?: false
        val conf = confidences(json)
        val status = if (abstain) JevStatus.ABSTAIN else JevStatus.OK
        return ChoiceResult(
            bestId = bestId.take(64),
            confidences = conf,
            abstain = abstain,
            status = status,
            latencyMs = 0,
        )
    }

    fun parseScore(json: String): ScoreResult {
        val v = doubleField(json, "score") ?: throw IllegalArgumentException("score: missing score")
        val codes = stringArray(json, "reasonCodes")
        return ScoreResult(
            score = v.toFloat().coerceIn(0f, 1f),
            reasonCodes = codes.map { it.take(32) }.distinct().take(8),
            status = JevStatus.OK,
            latencyMs = 0,
        )
    }

    fun parseNoul(json: String): NoulResult {
        val need = boolField(json, "needConfirm") ?: throw IllegalArgumentException("noul: missing needConfirm")
        val conf = (doubleField(json, "confidence") ?: 0.5).toFloat().coerceIn(0f, 1f)
        return NoulResult(needConfirm = need, confidence = conf, status = JevStatus.OK, latencyMs = 0)
    }

    internal fun q(raw: String): String = "\"" + jsonEscape(raw) + "\""

    internal fun jsonEscape(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    internal fun jsonUnescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c); i++; continue
            }
            when (val e = raw[i + 1]) {
                '"', '\\', '/' -> { sb.append(e); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    if (i + 5 >= raw.length) throw IllegalArgumentException("bad unicode escape")
                    val hex = raw.substring(i + 2, i + 6)
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("bad unicode escape"))
                    i += 6
                }
                else -> throw IllegalArgumentException("bad escape '\\$e'")
            }
        }
        return sb.toString()
    }

    private fun stringField(json: String, key: String): String? {
        val p = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*?)\"").find(json)
            ?: return null
        return jsonUnescape(p.groupValues[1])
    }

    private fun doubleField(json: String, key: String): Double? {
        val p = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)").find(json)
            ?: return null
        return p.groupValues[1].toDoubleOrNull()
    }

    private fun boolField(json: String, key: String): Boolean? {
        val p = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)").find(json)
            ?: return null
        return p.groupValues[1] == "true"
    }

    private fun stringArray(json: String, key: String): List<String> {
        val p = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(json)
            ?: return emptyList()
        val inner = p.groupValues[1]
        return Regex("\"((?:\\\\.|[^\"\\\\])*?)\"").findAll(inner).map { jsonUnescape(it.groupValues[1]) }.toList()
    }

    private fun confidences(json: String): Map<String, Float> {
        val p = Regex("\"confidences\"\\s*:\\s*\\{([^}]*)}").find(json) ?: return emptyMap()
        val inner = p.groupValues[1]
        val out = LinkedHashMap<String, Float>()
        for (m in Regex("\"((?:\\\\.|[^\"\\\\])*?)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)").findAll(inner)) {
            val k = jsonUnescape(m.groupValues[1]).take(64)
            val v = m.groupValues[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
            out[k] = v
        }
        return out
    }
}
