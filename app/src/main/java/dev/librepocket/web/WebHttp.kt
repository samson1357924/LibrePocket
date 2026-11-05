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
 *   （OkHttp callTimeout／HTTP-2／Okio 寫入超時形態）→ `TIMEOUT`；但外層
 *   [withTimeout] 的取消同樣以 [TimeoutCancellationException] 送達，故 TCE
 *   catch 首行先 `ensureActive()` 區分：自家 timeout → 外層仍 active → guard
 *   通過 → 回 `TIMEOUT`；外層取消 → guard 拋 [CancellationException] 透傳
 *   （不映射、不快取、不 fallback）。catch 順序保持 TCE 在 CE 之前。
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
        // TCE 可能是自家 withTimeout，也可能是外層取消：ensureActive 區分。
        // 自家超時 → 外層仍 active → 通過 → TIMEOUT；外層取消 → 拋 CE 透傳。
        currentCoroutineContext().ensureActive()
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
 * - 401／403 → `AUTH`（不 fallback）；408／504 → `TIMEOUT`；429／5xx → `SUPPLIER_ERROR`。
 * - 400／404／405／410／422 → `PROTOCOL`（請求形狀或端點錯誤，不 fallback）。
 * - 其餘未知狀態（含 3xx：D3 OkHttp adapter 對 GET 會跟隨跳轉，理論上不應漏到此層；
 *   真漏出視為 `TRANSPORT`）→ `TRANSPORT`（保守允許非 pinned fallback）。
 * - 狀態碼保持字面量（不抽 const），以縮小 diff。
 */
internal fun failureForStatus(code: Int): WebFailureReason? = when (code) {
    // Keep literal status codes (no const extraction) to minimize diff.
    in 200..299 -> null
    401, 403 -> WebFailureReason.AUTH
    408, 504 -> WebFailureReason.TIMEOUT
    429 -> WebFailureReason.SUPPLIER_ERROR
    in 500..599 -> WebFailureReason.SUPPLIER_ERROR
    400, 404, 405, 410, 422 -> WebFailureReason.PROTOCOL
    else -> WebFailureReason.TRANSPORT
}

/**
 * URL host 萃取（`siteName` 用；失敗回 null，不拋異常）。
 *
 * Host 先 lowercase 再去 `www.` 前綴；IDN 以 unicode 原形回傳（不做 punycode 正規化，已知限制）。
 */
internal fun hostOf(url: String): String? = try {
    java.net.URI(url).host
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}
