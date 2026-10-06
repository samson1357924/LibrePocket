package dev.librepocket.jev

import java.security.MessageDigest

/**
 * D01 三級意圖/重排/風控快取（JEV_INTEGRATION_DRAFT §5）。
 *
 * - 只存脫敏後鍵：呼叫方必須先過 `Redactor`，本類再對歸一化鍵做 SHA-256，
 *   記憶體中只保留 hex 摘要，永不保留原文、不持久化、不跨會話落盤。
 * - 風控快取僅收 `READ 且 score < 0.3` 的低風險項，高風險永不快取（防誤放行）。
 * - LRU：意圖 500 / 重排 200 / 風控 500；程序重啟即消失。
 */
class JevCache(
    routeCapacity: Int = 500,
    rerankCapacity: Int = 200,
    riskCapacity: Int = 500,
) {
    private val routeCache = lruMap<String, ChoiceResult>(routeCapacity)
    private val rerankCache = lruMap<String, ChoiceResult>(rerankCapacity)
    private val riskCache = lruMap<String, ScoreResult>(riskCapacity)

    var routeHits: Long = 0; private set
    var routeMisses: Long = 0; private set
    var rerankHits: Long = 0; private set
    var rerankMisses: Long = 0; private set
    var riskHits: Long = 0; private set
    var riskMisses: Long = 0; private set

    // ---- 意圖路由快取 ----

    /** 鍵：脫敏文本歸一化 + 投影工具 id 集合 + flavor（投影變化天然失效）。 */
    fun routeKey(redactedText: String, projectionTools: String, flavor: String): String =
        sha256Hex(normalize(redactedText) + "|" + projectionTools.trim() + "|" + flavor.trim())

    @Synchronized
    fun getRoute(key: String): ChoiceResult? {
        val hit = routeCache[key]
        if (hit != null) routeHits++ else routeMisses++
        return hit?.copy(cached = true)
    }

    @Synchronized
    fun putRoute(key: String, value: ChoiceResult) {
        // 只快取模型成功回應（含 abstain/低置信，避免重複付費）；傳輸失敗不快取。
        if (value.status == JevStatus.OK || value.status == JevStatus.ABSTAIN) {
            routeCache[key] = value.copy(cached = false)
        }
    }

    // ---- Grounding 重排快取 ----

    /** 鍵：脫敏 goal + 頁面簽名（node id + 文字 + bounds 雜湊，頁面變化即失效）。 */
    fun rerankKey(redactedGoal: String, pageSignature: String): String =
        sha256Hex(normalize(redactedGoal) + "|" + pageSignature.trim())

    @Synchronized
    fun getRerank(key: String): ChoiceResult? {
        val hit = rerankCache[key]
        if (hit != null) rerankHits++ else rerankMisses++
        return hit?.copy(cached = true)
    }

    @Synchronized
    fun putRerank(key: String, value: ChoiceResult) {
        if (value.status == JevStatus.OK || value.status == JevStatus.ABSTAIN) {
            rerankCache[key] = value.copy(cached = false)
        }
    }

    // ---- 風控快取 ----

    /** 鍵：動作名 + 參數形狀（去值）+ 副作用等級。 */
    fun riskKey(actionName: String, paramShape: String, sideEffect: SideEffect): String =
        sha256Hex(actionName.trim() + "|" + paramShape.trim() + "|" + sideEffect.name)

    companion object {
        /** 僅 READ 低風險可快取；高風險永不快取。 */
        fun isRiskCacheable(score: ScoreResult, sideEffect: SideEffect): Boolean =
            sideEffect == SideEffect.READ && score.status == JevStatus.OK && score.score < 0.3f
    }

    @Synchronized
    fun getRisk(key: String): ScoreResult? {
        val hit = riskCache[key]
        if (hit != null) riskHits++ else riskMisses++
        return hit?.copy(cached = true)
    }

    /**
     * 高風險/非 READ/失敗一律拒收（回傳 false，不寫入）。
     * 呼叫方仍以本次模型結果為準，只是下次不再命中。
     */
    @Synchronized
    fun putRisk(key: String, value: ScoreResult, sideEffect: SideEffect): Boolean {
        if (!isRiskCacheable(value, sideEffect)) return false
        riskCache[key] = value.copy(cached = false)
        return true
    }

    @Synchronized
    fun clear() {
        routeCache.clear()
        rerankCache.clear()
        riskCache.clear()
    }

    @Synchronized
    fun sizes(): Triple<Int, Int, Int> = Triple(routeCache.size, rerankCache.size, riskCache.size)

    private fun normalize(redacted: String): String =
        redacted.trim().replace(Regex("\\s+"), " ").take(500)

    private fun <K, V> lruMap(capacity: Int): LinkedHashMap<K, V> {
        val cap = capacity.coerceAtLeast(1)
        return object : LinkedHashMap<K, V>(cap, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > cap
        }
    }
}

/** SHA-256 hex（純 JVM，無新依賴）。 */
internal fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) sb.append(String.format("%02x", b))
    return sb.toString()
}
