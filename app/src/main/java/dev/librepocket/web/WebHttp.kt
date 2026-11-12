package dev.librepocket.web

import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
        ?.lowercase(Locale.ROOT)
        ?.removePrefix("www.")
        ?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

internal const val MAX_WEB_BASE_URL_LENGTH = 2048

private val NUMERIC_IPV4_PART = Regex("""^(\d+|0[xX][0-9a-fA-F]+)$""")
private val IPV6_LITERAL_CHARS = Regex("""^[0-9a-fA-F:.]+$""")
private val DOT_SEGMENTS = setOf(".", "..")

internal fun containsControlFormatOrSurrogateChar(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        if (
            cp <= 0x1F ||
            cp == 0x7F ||
            cp in 0xD800..0xDFFF ||
            Character.getType(cp) == Character.CONTROL.toInt() ||
            Character.getType(cp) == Character.FORMAT.toInt()
        ) {
            return true
        }
        i += Character.charCount(cp)
    }
    return false
}

private fun isIpv4MappedBytes(bytes: ByteArray): Boolean =
    bytes.size == 16 &&
        bytes.take(10).all { it == 0.toByte() } &&
        (bytes[10].toInt() and 0xff) == 0xff &&
        (bytes[11].toInt() and 0xff) == 0xff

private fun isDeprecatedIpv4CompatBytes(bytes: ByteArray): Boolean =
    bytes.size == 16 &&
        bytes.take(12).all { it == 0.toByte() } &&
        !(bytes[12] == 0.toByte() && bytes[13] == 0.toByte() && bytes[14] == 0.toByte() &&
            (bytes[15] == 0.toByte() || bytes[15] == 1.toByte()))

private fun isAwsMetadataIpv6(bytes: ByteArray): Boolean =
    bytes.size == 16 &&
        (bytes[0].toInt() and 0xff) == 0xfd &&
        (bytes[1].toInt() and 0xff) == 0x00 &&
        (bytes[2].toInt() and 0xff) == 0x0e &&
        (bytes[3].toInt() and 0xff) == 0xc2 &&
        bytes.slice(4..13).all { it == 0.toByte() } &&
        (bytes[14].toInt() and 0xff) == 0x02 &&
        (bytes[15].toInt() and 0xff) == 0x54

internal fun parseHttpBaseUri(raw: String): URI? {
    val trimmed = raw.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
    if (trimmed.isEmpty() || trimmed.length > MAX_WEB_BASE_URL_LENGTH) return null
    if (containsControlFormatOrSurrogateChar(trimmed) || trimmed.any { it.isWhitespace() }) return null
    if ('\\' in trimmed) return null
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val authority = trimmed
        .substringAfter("://", "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (authority.isEmpty() || '@' in authority || authority.endsWith(':')) return null
    val base = trimmed.trimEnd('/')
    if (base.isEmpty()) return null
    val uri = try {
        URI(base)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host
    if (host.isNullOrBlank()) return null
    if (containsControlFormatOrSurrogateChar(host) || host.any { it.isWhitespace() }) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    if (
        uri.userInfo != null ||
        uri.rawUserInfo != null ||
        uri.query != null ||
        uri.rawQuery != null ||
        uri.fragment != null ||
        uri.rawFragment != null
    ) {
        return null
    }
    val rawPath = uri.rawPath.orEmpty()
    if (rawPath.split('/').any { seg ->
            seg == "." || seg == ".." || seg.replace("%2e", ".", ignoreCase = true) in DOT_SEGMENTS
        }
    ) {
        return null
    }
    val httpUrl = base.toHttpUrlOrNull() ?: return null
    if (httpUrl.scheme != scheme) return null
    if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return null
    if (httpUrl.query != null || httpUrl.fragment != null) return null
    if (uri.port != -1 && uri.port != httpUrl.port) return null

    if (host.startsWith("[")) {
        if (!host.endsWith("]") || '%' in host) return null
        val inner = host.substring(1, host.length - 1)
        if (':' !in inner || !IPV6_LITERAL_CHARS.matches(inner)) return null
        val addr = try {
            InetAddress.getByName(inner)
        } catch (_: Exception) {
            return null
        }
        if (addr !is Inet6Address) return null
        val bytes = addr.address
        if (isIpv4MappedBytes(bytes) || isDeprecatedIpv4CompatBytes(bytes) || isAwsMetadataIpv6(bytes)) return null
        if (addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) return null
    } else {
        if (']' in host || ':' in host || '%' in host) return null
        if (host.lowercase(Locale.ROOT) != httpUrl.host) return null
        val hostNoTrailDot = host.trimEnd('.')
        val labels = hostNoTrailDot.split('.')
        if (labels.size in 1..4 && labels.all { NUMERIC_IPV4_PART.matches(it) }) {
            if (host.endsWith('.')) return null
            if (labels.size != 4) return null
            if (labels.any { it.startsWith("0x", ignoreCase = true) }) return null
            if (labels.any { it.length > 1 && it.startsWith('0') }) return null
            val parts = labels.map { it.toIntOrNull() ?: return null }
            if (parts.any { it !in 0..255 }) return null
            val bytes = ByteArray(4) { idx -> parts[idx].toByte() }
            val addr = InetAddress.getByAddress(bytes)
            if (addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) return null
            val first = parts[0]
            if (first == 0 || first >= 240) return null
            if (parts == listOf(100, 100, 100, 200)) return null
        }
    }
    return uri
}

internal fun normalizeHttpOrigin(raw: String): String? {
    val uri = parseHttpBaseUri(raw) ?: return null
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    val host = uri.host.lowercase(Locale.ROOT)
    val port = if (uri.port != -1) ":${uri.port}" else ""
    val rawPath = uri.rawPath.orEmpty()
    return "$scheme://$host$port$rawPath"
}
