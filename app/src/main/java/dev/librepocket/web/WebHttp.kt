package dev.librepocket.web

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * D2 最小 HTTP 抽象（#138 D2；純 JVM，不接線）。
 *
 * - Production 的 OkHttp adapter 是 D3 之後的事；D2 只用 fake 實作，零真網。
 * - Timeout 由各 provider 以 [withTimeout] 包住 [WebHttpGet.get] 強制執行
 *  （`timeoutMs` 建構子可注入）；fetcher 實作必須是 cancellable
 *  （suspend 點透傳 [CancellationException]），取消才會真的中斷 I/O。
 */
data class WebHttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 單次 GET（SearXNG JSON／DDG HTML 皆為 GET；D4 的 extract 另行定義）。 */
fun interface WebHttpGet {
    suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse
}

object WebHttpTimeouts {
    const val DEFAULT_TIMEOUT_MS = 10_000L

    /** 搜尋回應體上限（超過 → `BAD_RESPONSE`，fail-closed；gzip bomb 類比照 D4 精神）。 */
    const val MAX_BODY_CHARS = 256 * 1024
}

internal sealed interface FetchOut {
    data class Resp(val response: WebHttpResponse) : FetchOut
    data class Fail(val reason: WebFailureReason) : FetchOut
}

/**
 * 帶可注入 timeout 的抓取＋typed error 映射（兩 provider 共用）。
 *
 * - [TimeoutCancellationException]／[SocketTimeoutException]／[InterruptedIOException]
 *   （OkHttp callTimeout／HTTP-2／Okio 寫入超時形態）→ `TIMEOUT`。
 * - 外部取消（[CancellationException]，非超時）原樣透傳：不映射、不快取、不 fallback。
 * - 其他 [IOException] → `TRANSPORT`；fetcher 拋出的其他異常 → `TRANSPORT`
 *  （fail-closed 且允許非 pinned fallback；見 [WebSearchSelector.mayFallback]）。
 */
internal suspend fun fetchWithTimeout(
    httpGet: WebHttpGet,
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long,
): FetchOut {
    val response = try {
        withTimeout(timeoutMs.coerceAtLeast(1)) { httpGet.get(url, headers) }
    } catch (e: TimeoutCancellationException) {
        return FetchOut.Fail(WebFailureReason.TIMEOUT)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        return FetchOut.Fail(WebFailureReason.TIMEOUT)
    } catch (_: InterruptedIOException) {
        currentCoroutineContext().ensureActive()
        return FetchOut.Fail(WebFailureReason.TIMEOUT)
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        return FetchOut.Fail(WebFailureReason.TRANSPORT)
    } catch (_: Exception) {
        // Fail-closed：fetcher 拋出的未知異常（程式錯誤）仍映射 TRANSPORT，保持
        // provider contract no-throw；Service 另有中央映射，見 WebSearchService。
        currentCoroutineContext().ensureActive()
        return FetchOut.Fail(WebFailureReason.TRANSPORT)
    }
    return FetchOut.Resp(response)
}

/**
 * HTTP 狀態 → typed failure（兩 provider 共用；成功回 null）。
 *
 * - 401／403 → `AUTH`（不 fallback）；408 → `TIMEOUT`；429／5xx → `SUPPLIER_ERROR`。
 * - 400／404／405／410／422 → `PROTOCOL`（請求形狀或端點錯誤，不 fallback）。
 * - 其餘未知狀態 → `TRANSPORT`（保守允許非 pinned fallback）。
 */
internal fun failureForStatus(code: Int): WebFailureReason? = when (code) {
    in 200..299 -> null
    401, 403 -> WebFailureReason.AUTH
    408 -> WebFailureReason.TIMEOUT
    429 -> WebFailureReason.SUPPLIER_ERROR
    in 500..599 -> WebFailureReason.SUPPLIER_ERROR
    400, 404, 405, 410, 422 -> WebFailureReason.PROTOCOL
    else -> WebFailureReason.TRANSPORT
}

/** URL host 萃取（`siteName` 用；失敗回 null，不拋異常）。 */
internal fun hostOf(url: String): String? = try {
    java.net.URI(url).host
        ?.takeIf { it.isNotBlank() }
        ?.removePrefix("www.")
        ?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}
