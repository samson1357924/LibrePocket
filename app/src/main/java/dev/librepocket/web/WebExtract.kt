package dev.librepocket.web

import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * D4 `web.extract` 第一版（單 URL；#138 D4；純 JVM，不接線）。
 *
 * - 抓取前（首跳＋每一跳 redirect）必經 [NetworkPolicy.evaluateUrl]
 *  （per-endpoint [EndpointNetworkOptIn] 參數化，預設 `HTTPS_ONLY`＝公網 https；私有／明文全拒
 *   [EndpointNetworkOptIn]）；拒絕即 `Unavailable`（detail＝denyCode 名），
 *   不送任何請求、不寫快取。
 * - Redirect 手動跟隨（上限 [MAX_REDIRECTS] 跳），逐跳重驗政策；
 *   `https → http` 降級永遠拒（fail-closed，含迴環在內，絕不發出明文請求），
 *   與 `tool/WebFetch.kt` 姿態對齊（只讀對齊，不修改 WebFetch）；
 *   跨 origin 跳轉之 opt-in 不可逆降級為預設 `HTTPS_ONLY`（公網 https 仍允許；私有／明文一律拒）（見下介紹，與 WebFetch 對齊）。
 *   跳轉碼分類單一真相為 [NetworkPolicy.isRedirectStatus]（300..303／307／308，含 300）；
 *   304／305／306 及其餘非跳轉碼落到終端 `HTTP_xxx` 分支以 `Failed` 收場
 *   （fail-closed，絕不跟隨）。
 * - Response 位元組上限：預設 [DEFAULT_MAX_BYTES]（256 KiB），呼叫方可調，
 *   硬頂 [ABSOLUTE_MAX_BYTES]（1 MiB）；超過即 `Failed(TOO_LARGE)`
 *   fail-closed（不存部分、不寫快取），與 malformed（`BAD_RESPONSE`）區分；
 *   無論 Content-Encoding 為何，未解壓即超限一律 `TOO_LARGE`（無 gzip 豁免）。
 * - Content-Encoding：僅 `gzip`／`x-gzip`（大小寫不敏感、trim）為合法值；
 *   未知值或多值（含逗號）→ `BAD_RESPONSE`（never 猜測編碼）。
 * - Content-Type（存在時）：僅 `text/` 開頭型別、`application/xhtml+xml`、
 *   `application/xml`、`application/json` 或 `+xml`／`+json` suffixed 型別；
 *   其餘 → `BAD_RESPONSE`；缺席該 header 放行（legacy）。
 * - Gzip：解壓同樣受位元組上限約束
 *  （bomb：小壓縮、大解壓 → `TOO_LARGE` fail-closed）；非 gzip 卻標 gzip
 *   或解壓失敗 → `BAD_RESPONSE`。
 * - Model 可見：預設 [DEFAULT_MODEL_CHARS]（20k），硬頂 [MAX_MODEL_CHARS]
 *  （50k）；超限截斷（UTF-16 surrogate 安全）＋`truncated=true`，非錯誤；
 *   `truncated` 時 envelope 內文為 `capped + "\n[...TRUNCATED AT $effModel CHARS]"`，
 *   且 [WebExtractOutcome.Ok] 仍攜帶 `truncated=true`。
 * - 快取：命中（[WebExtractCache]，TTL [DEFAULT_TTL_MS] 60min；key 含 URL 去 fragment
 *   ＋`maxBytes`＋`maxModelChars`＋policy identity（`policy`＋`configRevision`），
 *   不同 policy/revision 不互撞）零 upstream、`cached=true`；
 *   `Failed`／`Unavailable`／取消一律不寫。
 *   快取命中依構造（same policy＋revision ⇒ same 決策）旁路 per-call 政策評估；
 *   授權撤銷必須 bump `configRevision` 或呼叫 [WebExtractService.invalidate] 重綁。
 *   rebind 時（identity＋revision 變更）呼叫方必須 `invalidate()`。
 *   跨 tier 內容永不共用。
 * - `inFlight` single-flight map：無界，但受 per-key timeout 有界（leader 受
 *   [TIMEOUT_MS] 端到端約束，逾時即完成並出 map；與 `WebSearchService` 同姿態）。
 * - `bytesKept`：`Ok.bytesKept`＝（若 gzip 解壓後之）實際位元組數；identity
 *   編碼時即 raw body 位元組數。
 * - 取消語義：外部取消（leader／呼叫方自身）原樣透傳 [CancellationException]，
 *   不映射、不寫快取、不 fallback（`fetchRawWithTimeout` 內五處 `ensureActive()` 守衛，與
 *   `WebHttp.fetchWithTimeout` 對稱）；leader 被取消而 follower 仍 active 者，
 *   follower 映射 typed `Unavailable(DETAIL_CANCELLED, null)`（never cached；
 *   follower 不得 out-fetch 已取消的 leader，不再進入單飛迴圈 refetch）；
 *   例外：leader 於取消前已完整產出 Ok（okForFollowers 路徑）者，follower
 *   收到該 Ok 而非 CANCELLED。
 * - 內容：`title/url/snippet/published/siteName` 正規化（見 [parseHtml]），
 *   全經 [WebContentEnvelope.cleanField]（`nfkc → strip → sanitize` 定點）清理；模型可見體為
 *   [WebContentEnvelope.wrap] 單層 envelope；各欄位另受硬上限約束
 *  （title 500／siteName 253／url 2048／published 128，皆 surrogate 安全截斷）。
 * - 偽造 marker 定點清理：`title/snippet/published` 皆經
 *   [WebContentEnvelope.cleanField]（`nfkc → strip → sanitize（大小寫不敏感）` 迭代至精確定點，
 *   無輪數上限），擋 Cf 與 fullwidth／small-form 兼容性相似字第二通道（marker 內插 Cf
 *   或兼容字被 NFKC 摺回 ASCII 後再經 `strip`／`sanitize` 收束）。
 *   marker（[WebContentEnvelope.BEGIN]／[END]）剝除為大小寫不敏感（sentinel
 *   marker 皆為 ASCII，任何大小寫變體不得存活進模型可見體）；wrap 輸出恰含一組
 *   原始大小寫 marker，listeners 以精確 marker 比對，屬契約。
 * - fence 之範圍（契約）：僅 [WebContentEnvelope.wrap] 之輸出（＝清理後 capped 之
 *   `snippet`，truncated 時含 sentinel）為模型可見體、置於單層 envelope 之內；
 *   `content` 之 `url/title/siteName/published` 為 marker-free、Cf／Cc 已清理、
 *   硬上限截斷之 untrusted **structured values**，**不在 fence 之內**；D3 wiring
 *   必須僅將 `envelope` 呈現予模型，或於序列化 `content` 前自行 re-wrap／fence；
 *   不得把未 fence 欄位當作攜帶信任語意之內容。
 *
 * Blocking（production 接線前必解）：[WebExtractHttpGet] 介面現攜帶
 * `pinnedAddresses`＋`maxBytes` 參數（見該介面之契約 KDoc：建連必釘住、
 * raw read 必限上限＋1、取消必關 socket）；其餘 production 接線阻塞項為
 * D3 OkHttp adapter 確實遵守該 seam 契約，以及 production 呼叫方顯式傳入
 * pinning resolver（[dev.librepocket.policy.PinnedNetworkDns]）；
 * `SystemHostResolver` 僅供純 JVM fixture 測試，禁止以預設直連。
 *
 * 收斂點：#158 已落地；redirect／decode／guard 與 `tool/WebFetch.kt` 刻意
 * 複製（避免 web↔tool 套件循環），收斂至共享 helper 列為 Target 追蹤項
 * （#138/D5）；收斂時不得把 `TOO_LARGE` 改為截斷（extract 拒部分內容，避免
 * 模型見到截斷 HTML 誤判）。
 *
 * D3 追蹤項（production 接線前必解／必評估，#138/D3，否則不得 wiring）：
 * 1. cache key＝URL（去 fragment 後之修剪字串，**非正規化**：default port／
 *    case／percent-encoding 差異會產生重複 key——只是浪費，非正確性問題）
 *    ＋`maxBytes`＋`maxModelChars`＋`policy`＋`configRevision`；**不含 port
 *    維度之語義**（port 已內含於 URL 字串，無遺失，但 D3 須確認與政策側
 *    port 處理一致）；亦**不含 resolver identity**（兩個不同 DNS 快照對同
 *    key 不可區分；快取內容可能反映舊解析至多 60min — 重綁唯一手段為
 *    `configRevision` bump 或 `WebExtractService.invalidate()`）。
 * 2. cache **不解析 `Cache-Control`（含 `no-store`）**：TTL-only，屬刻意
 *    設計（read 工具、無寫副作用）；D3 須決定 honor 或明文件化此行為。
 * 3. `cleanField` 最壞 O(n²)（`snippet` 於 cap 前進入 cleanField，input 可達
 *    [PARSE_INPUT_CAP] 256k 字元；`effModel` 截斷發生於清理之後；病態深層嵌套
 *    marker 之 CPU 放大發生在 15s 預算之外）；url／siteName／title fallback 係
 *    cleanField 後才截斷 — D3 wiring 前須緩解（如 marker 計數前掃）或記錄 accept。
 * 4. envelope 僅為標記籬笆，**非授權**；D3 須另行接 `#165` consent/授權
 *    流程，envelope 不擔任 authorization 角色。
 * 5. Follower `await()` 未獨立掛 per-follower deadline（由 leader 預算＋cap-bounded
 *    parse 間接有界）；D3 wiring 時可另加 `withTimeoutOrNull`。
 * 6. `timeoutMs <= 0` 會被 `coerceAtLeast(1)` 收斂為 1ms 預算 — 幾乎立即
 *    fail-closed `TIMEOUT`；屬保守方向，安全可接受。
 *
 * 純 JVM fixture 可測：transport 經 [WebExtractHttpGet] 注入，DNS 經
 * [HostResolver] 注入，時鐘經 [WebExtractCache] 注入；零真網。
 */
object WebExtract {
    const val TOOL_NAME = "web.extract"
    const val SWITCH = "webextract"
    const val SWITCH_DEFAULT = false

    const val FALLBACK_HINT = "open the URL manually in a browser"

    /** 預設 response 位元組上限 256 KiB。 */
    const val DEFAULT_MAX_BYTES = 262_144

    /** Response 位元組硬頂 1 MiB（呼叫方要求再大也箝制於此）。 */
    const val ABSOLUTE_MAX_BYTES = 1_048_576

    /** Model 可見預設 20k chars。 */
    const val DEFAULT_MODEL_CHARS = 20_000

    /** Model 可見硬頂 50k chars。 */
    const val MAX_MODEL_CHARS = 50_000

    /** 快取 TTL 60min。 */
    const val DEFAULT_TTL_MS = 60 * 60 * 1_000L

    /** 快取上限筆數（LRU 有界）。 */
    const val MAX_CACHE_ENTRIES = 200

    /** 手動跟隨上限（超過即 `Failed(TOO_MANY_REDIRECTS)`）。 */
    const val MAX_REDIRECTS = 5

    /**
     * 全程超時預算（15s）。
     *
     * `extractUncached` 於起始即以單調時鐘（`System.nanoTime()`）計算全程 deadline：
     * DNS preflight＋各 hop 之 HTTP 請求＋解壓共用此單一單調預算；
     * decode／parse 不計入 deadline，但受位元組／字元上限約束（cap-bounded）；
     * 逐跳以剩餘預算執行（見 `extractUncached`）。
     */
    const val TIMEOUT_MS = 15_000L

    /**
     * `truncated` 時 envelope 內文尾端之 sentinel 前綴（精確 `"[...TRUNCATED"`）；
     * 尾端完整形如 `[...TRUNCATED AT $N CHARS]`。Phase-2 測試可據此斷言。
     */
    internal const val TRUNCATED_SENTINEL_MARK = "[...TRUNCATED"

    // ---- typed details（穩定字串，供 transcript／測試斷言） ----

    const val DETAIL_EMPTY_URL = "EMPTY_URL"
    const val DETAIL_TOO_LARGE = "TOO_LARGE"
    const val DETAIL_BAD_RESPONSE = "BAD_RESPONSE"
    const val DETAIL_TOO_MANY_REDIRECTS = "TOO_MANY_REDIRECTS"
    const val DETAIL_BAD_REDIRECT = "BAD_REDIRECT"
    const val DETAIL_TIMEOUT = "TIMEOUT"
    const val DETAIL_TRANSPORT = "TRANSPORT"
    const val DETAIL_CLEARTEXT_DOWNGRADE = "CLEARTEXT_DOWNGRADE"

    /**
     * 打包給 follower 的 typed「leader 已取消」detail（never cached，不寫快取；
     * follower 不得在 leader 取消後另起爐灶 refetch）。
     *
     * 語義：leader 之 cancellation 完成（completeExceptionally）後，follower
     * `await()` 丟出 CancellationException；若 follower 自身未被取消，
     * 映射為 `Unavailable(CANCELLED, null)` 而非重新進入單飛迴圈；
     * 若 leader 於取消前已完整產出 Ok（`okForFollowers` 路徑，complete(ok)），
     * follower 收到該 Ok 而非 CANCELLED。
     */
    const val DETAIL_CANCELLED = "CANCELLED"
}

/** 正規化抽取內容（`title/url/snippet/published/siteName`，全已 sanitize）。 */
data class WebExtractContent(
    val title: String,
    val url: String,
    val snippet: String,
    val published: String? = null,
    val siteName: String? = null,
)

/** D4 單 URL 抽取結果。 */
sealed interface WebExtractOutcome {
    data class Ok(
        val content: WebExtractContent,
        /**
         * 單層 envelope：非 truncated 時為 [WebContentEnvelope.wrap] 於 `capped`；
         * truncated 時為 wrap 於 `capped + "\n[...TRUNCATED AT $N CHARS]"`
         * （見 [WebExtract.TRUNCATED_SENTINEL_MARK]）；模型可見體。fence 僅涵蓋
         * `snippet`；`content` 之其餘欄位不在 fence 之內（見 header「fence 之範圍」）。
         */
        val envelope: String,
        val truncated: Boolean = false,
        val cached: Boolean = false,
        val bytesKept: Int = 0,
    ) : WebExtractOutcome

    data class Unavailable(
        val detail: String,
        val denyCode: NetworkDenyCode? = null,
    ) : WebExtractOutcome

    data class Failed(val detail: String) : WebExtractOutcome
}

/** 位元組級原始回應（fake 可注入 gzip／大 body／跳轉）。 */
data class WebExtractRawResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebExtractRawResponse) return false
        return code == other.code && headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var r = code
        r = 31 * r + body.contentHashCode()
        r = 31 * r + headers.hashCode()
        return r
    }
}

/**
 * 單次 GET（D4 extract 專用位元組通道；production OkHttp adapter 待 D3 之後）。
 *
 * 契約（production adapter 與 fixture fake 皆必須遵守）：
 *
 * 1. 建連必須釘住 [pinnedAddresses]（policy 評估所解析並授權之答案），
 *    不得對該 host 獨立重新解析——關閉 policy→connect 之間的 TOCTOU
 *   （先評估再建連期間 DNS rebind 繞過授權）。每個存取之 host 皆須以
 *    當次 policy 決策所釘住之答案集合建連，adapter 不得自行放寬或替換。
 * 2. 必須把 raw read 截斷於 `maxBytes + 1` 並中止（在 wire 層強制
 *    `TOO_LARGE`）：[WebExtractRawResponse.body] 長度超過 `maxBytes`
 *    即為契約違反，呼叫方一律以 fail-closed（`TOO_LARGE`）處理。
 * 3. 必須可取消：協程取消時 socket 必須即時關閉，不得繼續讀取；
 *    取消訊號以 [kotlinx.coroutines.CancellationException] 透傳。
 * 4. 僅允許 `Accept` header（無 credentials、無 cookie、無自訂標頭）。
 * 5. 重複 response headers（如兩個 `Content-Encoding` 行）：fixtures 以
 *    `Map<String,String>` 不可表示重複；D3 OkHttp adapter 必須對具語義之
 *    headers（`content-encoding`／`content-type`／`transfer-encoding`）採
 *    fail-closed（重複即拒絕）或以 `getAll` 串接後視為多值（`content-encoding`
 *    多值已由 `extractUncached` 判 `BAD_RESPONSE`）；不得取首值放行。
 */
fun interface WebExtractHttpGet {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        pinnedAddresses: List<InetAddress>,
        maxBytes: Int,
    ): WebExtractRawResponse
}

internal sealed interface RawFetchOut {
    data class Resp(val response: WebExtractRawResponse) : RawFetchOut
    data class Fail(val detail: String) : RawFetchOut
}

/**
 * 帶可注入 timeout 的抓取（與 `WebHttp.fetchWithTimeout` 同取消語義）。
 *
 * - 自家 timeout → `Fail(TIMEOUT)`；外部取消 → `ensureActive()` 拋 CE 透傳。
 * - `SocketTimeoutException`／`InterruptedIOException` → `TIMEOUT`；
 *   其他 `IOException`／未知 `Exception` → `TRANSPORT`（fail-closed）。
 * - `pinnedAddresses`／`maxBytes` 原樣透傳給 [WebExtractHttpGet.get]，
 *   由 transport 實作遵守 [WebExtractHttpGet] 之契約（釘住建連、raw read 上限）。
 */
internal suspend fun fetchRawWithTimeout(
    httpGet: WebExtractHttpGet,
    url: String,
    headers: Map<String, String>,
    pinnedAddresses: List<InetAddress>,
    maxBytes: Int,
    timeoutMs: Long,
): RawFetchOut {
    val response = try {
        withTimeout(timeoutMs.coerceAtLeast(1)) { httpGet.get(url, headers, pinnedAddresses, maxBytes) }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TIMEOUT)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TIMEOUT)
    } catch (_: InterruptedIOException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TIMEOUT)
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TRANSPORT)
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TRANSPORT)
    }
    return RawFetchOut.Resp(response)
}

/**
 * D4 抽取快取（TTL 60min；純 JVM）。
 *
 * - key＝去 fragment 之修剪字串（刻意**不做** URL 正規化，見 [stripFragment]
 *  與 header「D3 追蹤項」#1）＋`maxBytes`
 *  ＋`maxModelChars`＋policy identity（[EndpointNetworkOptIn.policy]＋
 *  [EndpointNetworkOptIn.configRevision]）；不同預算／不同 tier／
 *  不同 revision 絕不互撞。
 * - 只存 `Ok`（`Failed`／`Unavailable`／取消不寫）；寫入時 `cached` 正規化
 *   為 `false`，讀回時正規化為 `true`（快取內一律以 `cached=false` 儲存，
 *   讀出一律以 `cached=true` 呈現；[WebExtractOutcome.Ok.cached] 預設
 *   `false` 不變）。
 * - 快取命中**依構造旁路當次 policy 評估**（same policy＋revision ⇒ same
 *   決策），與 per-call `[NetworkPolicy.evaluateUrl]` 無關；授權撤銷或政策
 *   變更必須 bump `configRevision` 或呼叫 `invalidate()` 才生效；跨 tier
 *  （不同 `policy`／`configRevision`）內容永不共用。
 * - 時鐘可注入，TTL 到期 lazy 淘汰＋`put` 順手掃；有界 LRU
 *  （[WebExtract.MAX_CACHE_ENTRIES]）。
 */
class WebExtractCache(
    val ttlMs: Long = WebExtract.DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be > 0" }
    }

    private data class Entry(val outcome: WebExtractOutcome.Ok, val expiresAt: Long)

    private val map: MutableMap<String, Entry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
                override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Entry>): Boolean =
                    size > WebExtract.MAX_CACHE_ENTRIES
            },
        )

    fun key(url: String, maxBytes: Int, maxModelChars: Int, optIn: EndpointNetworkOptIn): String {
        val u = url.trim()
        return "${u.length}:$u\u0001$maxBytes\u0001$maxModelChars\u0001${optIn.policy.name}\u0001${optIn.configRevision}"
    }

    fun get(url: String, maxBytes: Int, maxModelChars: Int, optIn: EndpointNetworkOptIn): WebExtractOutcome.Ok? =
        getByKey(key(url, maxBytes, maxModelChars, optIn))

    internal fun getByKey(k: String): WebExtractOutcome.Ok? {
        val entry: Entry?
        synchronized(map) {
            entry = map[k]
            if (entry != null && clockMs() >= entry.expiresAt) {
                map.remove(k)
                return null
            }
        }
        return entry?.outcome?.copy(cached = true, content = entry.outcome.content.copy())
    }

    fun put(url: String, maxBytes: Int, maxModelChars: Int, optIn: EndpointNetworkOptIn, outcome: WebExtractOutcome.Ok) {
        putByKey(key(url, maxBytes, maxModelChars, optIn), outcome)
    }

    internal fun putByKey(k: String, outcome: WebExtractOutcome.Ok) {
        val now = clockMs()
        val expiresAt = now.coerceAtMost(Long.MAX_VALUE - ttlMs) + ttlMs
        val snapshot = outcome.copy(cached = false, content = outcome.content.copy())
        synchronized(map) {
            sweepLocked(now)
            map[k] = Entry(snapshot, expiresAt)
        }
    }

    fun evictExpired() {
        synchronized(map) {
            sweepLocked(clockMs())
        }
    }

    fun clear() {
        map.clear()
    }

    val size: Int get() = synchronized(map) { map.size }

    private fun sweepLocked(nowMs: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (nowMs >= it.next().value.expiresAt) it.remove()
        }
    }
}

/**
 * D4 抽取服務（cache＋single-flight；取消不寫 cache）。
 *
 * 與 [WebSearchService] 同併發姿態：
 * - 命中快取 → `cached=true` 零 upstream（[WebExtractCache.getByKey] 回傳副本即
 *   正規化 `cached=true`）；快取 key 含 policy identity（`policy`＋`configRevision`），
 *   命中依構造旁路 per-call policy 評估（same policy＋revision ⇒ same 決策）；
 *   授權撤銷／政策變更後必須 `invalidate()`（或 bump `configRevision`）重綁，
 *   否則舊決策內容仍會命中。
 * - 同 key 併發合流（leader 打、follower `await()`；follower 取消不影響 leader）。
 * - `inFlight` map：無界，但受 per-key timeout 有界——leader 受全程 deadline
 *   （[WebExtract.TIMEOUT_MS]）約束，完成／失敗即出 map；與 `WebSearchService`
 *   同姿態（不為 inFlight 另設容量上限）。
 * - 取消／`Failed`／`Unavailable` 一律不寫快取。
 */
class WebExtractService(
    private val httpGet: WebExtractHttpGet,
    private val cache: WebExtractCache = WebExtractCache(),
    private val timeoutMs: Long = WebExtract.TIMEOUT_MS,
) {
    /**
     * 清空快取。
     *
     * resolver 變更後必須 `invalidate()`（或 bump `configRevision`），
     * 否則快取保留舊解析之內容；`invalidate()` 不會阻擋已在飛行之 leader，
     * 其完成後可能重新寫入快取（與 WebSearchService 同姿態）。
     * 不影響 inFlight／follower——已 detach 的 follower 之單飛結果不變。
     */
    fun invalidate() = cache.clear()

    private val mutex = Mutex()
    private val inFlight: MutableMap<String, CompletableDeferred<WebExtractOutcome>> = mutableMapOf()

    /**
     * 單 URL 抽取（cache＋single-flight＋逐跳政策）。
     *
     * Blocking（production 接線前必解）：`resolver` 無預設值；production
     * 呼叫方必須顯式注入 pinning resolver（[dev.librepocket.policy.PinnedNetworkDns]），
     * 禁止直連；`SystemHostResolver` 僅供純 JVM fixture 測試。
     * OkHttp adapter 須讓 `resolvedAddresses` 釘住建連，rebind 視新決策重驗
     * （S2 契約）。
     */
    suspend fun extract(
        rawUrl: String,
        optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
        resolver: HostResolver,
        maxBytes: Int = WebExtract.DEFAULT_MAX_BYTES,
        maxModelChars: Int = WebExtract.DEFAULT_MODEL_CHARS,
    ): WebExtractOutcome {
        if (rawUrl.isBlank()) {
            return WebExtractOutcome.Unavailable(WebExtract.DETAIL_EMPTY_URL, null)
        }
        val effBytes = maxBytes.coerceIn(1, WebExtract.ABSOLUTE_MAX_BYTES)
        val effModel = maxModelChars.coerceIn(1, WebExtract.MAX_MODEL_CHARS)
        val k = cache.key(stripFragment(rawUrl.trim()), effBytes, effModel, optIn)
        while (true) {
            cache.getByKey(k)?.let { return it }

            var lateHit: WebExtractOutcome.Ok? = null
            var mine: CompletableDeferred<WebExtractOutcome>? = null
            var leader = false
            mutex.withLock {
                lateHit = cache.getByKey(k)
                if (lateHit == null) {
                    val existing = inFlight[k]
                    if (existing != null) {
                        mine = existing
                    } else {
                        val fresh = CompletableDeferred<WebExtractOutcome>()
                        mine = fresh
                        inFlight[k] = fresh
                        leader = true
                    }
                }
            }
            lateHit?.let { return it }
            val m = mine ?: continue
            if (!leader) {
                try {
                    return m.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // 自身被取消：ensureActive 拋出（self-cancellation）。
                    // 否則為 leader 取消：follower 映射 typed Unavailable(CANCELLED)
                    // 並返回，不再進入單飛迴圈（never cached，不得 out-fetch a
                    // cancelled leader）。
                    // 例外：若 leader 於取消前已完整產出 Ok（okForFollowers 路徑，
                    // complete(ok) 而非 completeExceptionally），follower 收到該 Ok
                    // 而非 CANCELLED——`await()` 會直接回傳已落定的結果。
                    return WebExtractOutcome.Unavailable(WebExtract.DETAIL_CANCELLED, null)
                } catch (e: Error) {
                    // Error（OOM/SOE 等致命錯誤）原樣上拋，不映射為 TRANSPORT、不吞噬；
                    // 與 leader 路徑（catch Throwable → completeExceptionally(e) → 上拋）對稱。
                    throw e
                } catch (_: Throwable) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                }
            }

            var okForFollowers: WebExtractOutcome.Ok? = null
            try {
                val outcome = extractUncached(rawUrl, optIn, resolver, effBytes, effModel)
                if (outcome is WebExtractOutcome.Ok) okForFollowers = outcome
                currentCoroutineContext().ensureActive()
                if (outcome is WebExtractOutcome.Ok) {
                    cache.putByKey(k, outcome)
                }
                m.complete(outcome)
                return outcome
            } catch (e: CancellationException) {
                val ok = okForFollowers
                if (ok != null) {
                    m.complete(ok)
                } else {
                    m.completeExceptionally(e)
                }
                throw e
            } catch (_: Exception) {
                try {
                    currentCoroutineContext().ensureActive()
                } catch (ce: CancellationException) {
                    m.completeExceptionally(ce)
                    throw ce
                }
                val failed = WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                m.complete(failed)
                return failed
            } catch (e: Throwable) {
                m.completeExceptionally(e)
                throw e
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight[k] === m) inFlight.remove(k)
                    }
                }
            }
        }
    }

    private suspend fun extractUncached(
        rawUrl: String,
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        effBytes: Int,
        effModel: Int,
    ): WebExtractOutcome {
        // 全程單一單調 deadline：DNS preflight＋所有 redirect hop＋解壓共用。
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        var current = stripFragment(rawUrl.trim())
        // 有效 opt-in：跨 origin 跳轉即不可逆降級為預設 `HTTPS_ONLY` 姿態
        //（見 downgradedOptInForRedirect）。
        var effectiveOptIn = optIn
        var fromSchemeHttps = false
        var first = true
        var hops = 0
        while (true) {
            // 逐跳剩餘預算；耗盡即 TIMEOUT，不再發請求。
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT)
            }
            // 建連前必經政策（含首跳；redirect 逐跳重驗；DNS 需查則經有界 preflight）。
            val eval = try {
                if (NetworkPolicy.requiresDnsLookup(current, effectiveOptIn)) {
                    when (
                        val pre = NetworkPolicy.runPreflightSuspend(deadlineNs) {
                            NetworkPolicy.evaluateUrl(current, effectiveOptIn, resolver)
                        }
                    ) {
                        is NetworkPolicy.PreflightOutcome.Completed -> pre.value
                        NetworkPolicy.PreflightOutcome.TimedOut ->
                            return WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT)
                        NetworkPolicy.PreflightOutcome.OverloadRejected ->
                            return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                    }
                } else {
                    // 純 pre-DNS 決策（literal IP 或不需查 DNS）：直接評估，不阻塞。
                    NetworkPolicy.evaluateUrl(current, effectiveOptIn, resolver)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
            }
            if (!eval.allowed) {
                // 逐跳 policy 拒絕（含 redirect hop）亦以內層 denyCode 映射為 Unavailable——
                // 刻意偏離 WebFetch 之 `REDIRECT_BLOCKED` 正規化：web.extract 保留內層碼供稽核；
                // 收斂列為 #138/D5 追蹤項。
                return WebExtractOutcome.Unavailable(
                    eval.denyCode?.name ?: NetworkDenyCode.INVALID_HOST.name,
                    eval.denyCode,
                )
            }
            // 政策評估可能涉及掛起的 DNS preflight；取消訊號必須在此處即生效，
            // 且不得觸及 normalized 計算與任何建連。
            currentCoroutineContext().ensureActive()
            val normalized = stripFragment(
                eval.normalizedUrl ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT),
            )
            // https → http 降級永遠拒（含迴環，fail-closed），絕不發出明文請求。
            val curScheme = try {
                normalized.toHttpUrlOrNull()?.scheme?.lowercase()
            } catch (_: Exception) {
                null
            }
            if (!first && fromSchemeHttps && curScheme == "http") {
                return WebExtractOutcome.Unavailable(
                    WebExtract.DETAIL_CLEARTEXT_DOWNGRADE,
                    NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
                )
            }
            fromSchemeHttps = curScheme == "https"
            first = false

            // 政策評估可能消耗時間，重算剩餘預算：fetch 亦受全程 deadline 約束。
            val fetchRemainingMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (fetchRemainingMs <= 0) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT)
            }
            val fetched = fetchRawWithTimeout(
                httpGet,
                normalized,
                mapOf("Accept" to "text/html, text/plain;q=0.9, */*;q=0.1"),
                eval.resolvedAddresses,
                effBytes,
                fetchRemainingMs.coerceAtLeast(1),
            )
            val response = when (fetched) {
                is RawFetchOut.Fail -> return WebExtractOutcome.Failed(fetched.detail)
                is RawFetchOut.Resp -> fetched.response
            }

            if (NetworkPolicy.isRedirectStatus(response.code)) {
                // Redirect body 同樣受位元組上限約束（transport 契約「body > maxBytes →
                // TOO_LARGE」在此分支原被靜默繞過——body 雖不據此消費內容，契約不得有
                // 豁免缺口；重定向回應攜帶超限 body 一律 fail-closed 中止）。
                if (response.body.size > effBytes) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_LARGE)
                }
                if (hops >= WebExtract.MAX_REDIRECTS) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_MANY_REDIRECTS)
                }
                val location = headerLookup(response.headers, "location")
                    ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_REDIRECT)
                val resolved = resolveRedirect(normalized, location)
                    ?.let { stripFragment(it) }
                    ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_REDIRECT)
                // 跨 origin 跳轉：opt-in 不可逆降級為預設 `HTTPS_ONLY` 姿態（公網 https 仍允許；
                // 私有／明文一律拒）；下一跳政策以降級後
                // opt-in 評估（不得恢復已降級權限；同 origin 維持目前有效 opt-in）。
                effectiveOptIn = downgradedOptInForRedirect(normalized, resolved, effectiveOptIn)
                // 預檢 downgrade（未發請求前擋；下一圈政策再驗一次，雙保險）。
                val fromHttps = normalized.toHttpUrlOrNull()?.scheme?.lowercase() == "https"
                val toHttp = resolved.toHttpUrlOrNull()?.scheme?.lowercase() == "http"
                if (fromHttps && toHttp) {
                    return WebExtractOutcome.Unavailable(
                        WebExtract.DETAIL_CLEARTEXT_DOWNGRADE,
                        NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
                    )
                }
                current = resolved
                hops++
                continue
            }

            if (response.code !in 200..299) {
                return WebExtractOutcome.Failed("HTTP_${response.code}")
            }

            // Content-Encoding 閘：存在時僅 `gzip`／`x-gzip`（大小寫不敏感、trim）；
            // 多值（含逗號）或未知值 → BAD_RESPONSE（絕不猜測編碼）。缺席即 identity。
            val encoding = headerLookup(response.headers, "content-encoding")?.trim()?.lowercase()
            if (encoding != null &&
                (',' in encoding || (encoding != "gzip" && encoding != "x-gzip"))
            ) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
            }

            // Content-Type 閘（存在時）：僅允許 text/*、application/xhtml+xml、
            // application/xml、application/json 或 +xml／+json 後綴型別；
            // 缺席該 header 放行（legacy）。
            val contentType = headerLookup(response.headers, "content-type")
            if (contentType != null && !isAllowedContentType(contentType)) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
            }

            // 位元組上限（未解壓即超限 → TOO_LARGE fail-closed；不論 Content-Encoding，
            // 無 gzip 豁免）。
            val rawBytes = response.body
            if (rawBytes.size > effBytes) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_LARGE)
            }
            val bytes: ByteArray = if (encoding == "gzip" || encoding == "x-gzip") {
                when (val dec = gunzipCapped(rawBytes, effBytes)) {
                    is GunzipOut.Ok -> dec.data
                    is GunzipOut.TooLarge -> return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_LARGE)
                    is GunzipOut.Corrupt -> return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
                }
            } else {
                rawBytes
            }
            if (bytes.size > effBytes) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_LARGE)
            }
            if (bytes.isEmpty()) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
            }

            val charsetName = charsetOf(headerLookup(response.headers, "content-type"))
            val decoded = decodeDowngraded(bytes, charsetName)
            if (decoded.isBlank()) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
            }
            val parsed = parseHtml(decoded, normalized)
                ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)

            // Model 可見截斷（定點清理後、截斷後、envelope 最外；見 cleanField）。
            val cleanSnippet = WebContentEnvelope.cleanField(parsed.snippet)
            val cleanTitle = WebContentEnvelope.cleanField(parsed.title).takeExtractPrefix(500)
            val cleanUrl = WebContentEnvelope.cleanField(parsed.url).takeExtractPrefix(2048)
            val cleanSiteName = parsed.siteName?.let {
                WebContentEnvelope.cleanField(it).takeExtractPrefix(253)
            }
            val cleanPublished = parsed.published?.let {
                WebContentEnvelope.cleanField(it).takeExtractPrefix(128)
            }
            val capped = cleanSnippet.takeExtractPrefix(effModel)
            val truncated = capped.length < cleanSnippet.length
            val content = WebExtractContent(
                title = cleanTitle,
                url = cleanUrl,
                snippet = capped,
                published = cleanPublished,
                siteName = cleanSiteName,
            )
            val envelope = if (truncated) {
                WebContentEnvelope.wrap(capped + "\n${WebExtract.TRUNCATED_SENTINEL_MARK} AT $effModel CHARS]")
            } else {
                WebContentEnvelope.wrap(capped)
            }
            return WebExtractOutcome.Ok(
                content = content,
                envelope = envelope,
                truncated = truncated,
                cached = false,
                bytesKept = bytes.size,
            )
        }
    }
}

/** 不分大小寫的 header 查找（首個命中）。 */
internal fun headerLookup(headers: Map<String, String>, name: String): String? {
    for ((k, v) in headers) {
        if (k.equals(name, ignoreCase = true)) return v
    }
    return null
}

/**
 * 去 URL fragment（保留 authority／path／query）；無法解析者原樣回傳。
 *
 * 用於 fetch 目標與 cache key：fragment 不參與伺服器端決策，
 * 帶入只會稀釋 cache（不同 fragment 同頁面實為同內容）且會洩漏至 audit。
 */
internal fun stripFragment(url: String): String =
    url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url

/** 以目前 URL 為基準解析 `Location`（支援相對路徑；非法回 null）。 */
internal fun resolveRedirect(currentUrl: String, location: String): String? =
    try {
        currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString()
    } catch (_: Exception) {
        null
    }

/**
 * 比較兩個 URL 的 origin（scheme + host + port）是否相同。
 *
 * 比對為 scheme＋host＋port 精確相等（預設埠經 OkHttp 正規化）；
 * 子網域、trailing dot 皆視為跨 origin（即降級 opt-in）。
 */
private fun sameOrigin(a: String, b: String): Boolean {
    val ua = a.toHttpUrlOrNull() ?: return false
    val ub = b.toHttpUrlOrNull() ?: return false
    return ua.scheme == ub.scheme && ua.host == ub.host && ua.port == ub.port
}

/**
 * Redirect opt-in 狀態轉移：跨 origin 不可逆降級為預設 `HTTPS_ONLY`
 *（公網 https 仍允許；私有／明文一律拒），
 * 同 origin 則維持目前有效 opt-in（不得恢復已降級權限）。
 *
 * 邏輯與 `WebFetch.downgradedOptInForRedirect` 相同（web↔tool 套件循環，
 * 刻意複製以避免依賴；收斂至共享 helper 列為 Target 追蹤項 #138/D5）。
 * 注意：降級後的 `EndpointNetworkOptIn()` 即預設 `HTTPS_ONLY` 姿態：公網 https 仍允許；私有／明文一律拒。
 */
private fun downgradedOptInForRedirect(
    currentUrl: String,
    resolvedUrl: String,
    effectiveOptIn: EndpointNetworkOptIn,
): EndpointNetworkOptIn =
    if (sameOrigin(currentUrl, resolvedUrl)) effectiveOptIn else EndpointNetworkOptIn()

/**
 * Content-Type 閘：回傳 true 當型別可接受（缺 charset 無妨）。
 *
 * 允許：`text/` 開頭型別、`application/xhtml+xml`、`application/xml`、`application/json`、
 * 以及主型別字根（`/` 後）以 `+xml` 或 `+json` 結尾者（如 `application/atom+xml`）；
 * 其餘一律 false（包含任何 `image/`／`audio/` 等非後綴型別）。
 * 型別比對大小寫不敏感、`;` 之後之參數（如 `; charset=...`）忽略。
 */
internal fun isAllowedContentType(contentType: String): Boolean {
    val ct = contentType.substringBefore(';').trim().lowercase()
    if (ct.isEmpty()) return false
    if (ct.startsWith("text/")) return true
    when (ct) {
        "application/xhtml+xml", "application/xml", "application/json" -> return true
    }
    return ct.endsWith("+xml") || ct.endsWith("+json")
}

internal sealed interface GunzipOut {
    data class Ok(val data: ByteArray) : GunzipOut
    data object TooLarge : GunzipOut
    data object Corrupt : GunzipOut
}

/**
 * 受上限的 gzip 解壓（bomb fail-closed）。
 *
 * - 解壓超過 [cap] 即回 [GunzipOut.TooLarge]（不回部分資料）；
 * - 非 gzip／損壞即回 [GunzipOut.Corrupt]（→ `BAD_RESPONSE`，與超限區分）。
 */
internal fun gunzipCapped(data: ByteArray, cap: Int): GunzipOut {
    return try {
        GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
            val out = ByteArrayOutputStream(minOf(cap.coerceAtLeast(1), 8192))
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = gz.read(buf)
                if (n == -1) break
                if (total + n > cap) return GunzipOut.TooLarge
                out.write(buf, 0, n)
                total += n
            }
            GunzipOut.Ok(out.toByteArray())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        GunzipOut.Corrupt
    }
}

internal fun charsetOf(contentType: String?): String? {
    if (contentType.isNullOrBlank()) return null
    // `text/html; charset=gbk` 形狀；大小寫不敏感、引號可選。
    val m = Regex("""charset\s*=\s*"?([^";\s]+)"?""", RegexOption.IGNORE_CASE).find(contentType)
    return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

/** 轉碼降級：未知 charset／損壞位元組一律 UTF-8 replacement 解碼，永不拋異常。 */
internal fun decodeDowngraded(data: ByteArray, charsetName: String?): String {
    val charset: Charset = try {
        if (charsetName.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(charsetName)
    } catch (_: Exception) {
        Charsets.UTF_8
    }
    return try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(data))
            .toString()
    } catch (_: Exception) {
        String(data, Charsets.UTF_8)
    }
}

/** 取前 n 個 UTF-16 code units，不切斷 surrogate pair（D4 extract 內部版）。 */
internal fun String.takeExtractPrefix(n: Int): String {
    if (n >= length) return this
    if (n <= 0) return ""
    val s = take(n)
    return if (s.isNotEmpty() && s.last().isHighSurrogate()) s.dropLast(1) else s
}

/** parseHtml 輸入上限（chars）；超過者僅取前 [PARSE_INPUT_CAP] 字元解析。 */
private const val PARSE_INPUT_CAP = 256 * 1024

/**
 * 最小 HTML→內容解析（無依賴、正規化 schema）。
 *
 * 所有工作皆在 `html.take(PARSE_INPUT_CAP)` 上進行；title 提取與
 * `<script>/<style>` 剝除皆為 index-based 線性掃描（不使用 lazy backtracking
 * regex），避免病態輸入觸發災難性回溯。
 *
 * - `title`：首個 `<title>` 文本（去 tag＋unescape＋trim）；缺或空白 → 回退 URL。
 * - `snippet`：剔除 `<script>/<style>` 後去 tag＋unescape＋空白收斂；空白 → 回退
 *   title（仍空白即整體空白，由上層判 `BAD_RESPONSE`）。
 * - `published`：`article:published_time`／`datePublished`／`publish_date`／
 *   `<time datetime>` 首個非空；無 → null；空白收斂後截斷至 128 chars。
 * - `siteName`：[hostOf]（失敗回 null，不拋）。
 * - 全欄位不拋異常；解析期任何異常 → 回 null（→ `BAD_RESPONSE`）。
 */
internal fun parseHtml(html: String, finalUrl: String): WebExtractContent? {
    return try {
        val input = html.take(PARSE_INPUT_CAP)
        // ASCII 摺疊（長度不變）：所有被搜的 needle（`<title`／`<script`／`<style`／
        // `</title>`／`</script>`／`</style>`）皆 ASCII，索引必然不錯位
        //（ICU lowercase 可能變長度，故不用）。
        val lower = buildString(input.length) {
            for (c in input) append(if (c in 'A'..'Z') c + 32 else c)
        }
        // 先剝 script/style，title／published 皆於剝除後的 body 上提取
        //（不得在 script/style 內容中命中 <title> 或 meta published）。
        val body = HtmlExtract.stripScriptAndStyle(input, lower)
        val bodyLower = buildString(body.length) {
            for (c in body) append(if (c in 'A'..'Z') c + 32 else c)
        }
        val titleRaw = HtmlExtract.titleOf(body, bodyLower)
        val titleText = HtmlExtract.stripText(titleRaw).trim().takeIf { it.isNotEmpty() } ?: finalUrl
        val text = HtmlExtract.stripText(body).trim().let { HtmlExtract.Spaces.replace(it, " ") }
        val snippet = text.takeIf { it.isNotEmpty() } ?: titleText
        val published = HtmlExtract.publishedOf(body)
            ?.let { HtmlExtract.Spaces.replace(it, " ") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeExtractPrefix(128)
        WebExtractContent(
            title = titleText,
            url = finalUrl,
            snippet = snippet,
            published = published,
            siteName = hostOf(finalUrl),
        )
    } catch (_: Exception) {
        null
    }
}

private object HtmlExtract {
    val Spaces = Regex("\\s+")
    val META_PUBLISHED = Regex(
        """<meta[^>]+(?:property|name)\s*=\s*["']?(?:article:published_time|datePublished|publish_date|pubdate|DC\.date)["']?[^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    val META_PUBLISHED_REV = Regex(
        """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']?(?:article:published_time|datePublished|publish_date|pubdate|DC\.date)["']?""",
        RegexOption.IGNORE_CASE,
    )
    val TIME_DATETIME = Regex(
        """<time[^>]+datetime\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    val NUMERIC_ENTITY = Regex("&#(\\d+);")
    val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

    /**
     * 取首個 `<title>...</title>` 文本（index-based，case-insensitive）；
     * 任一環節缺失即回空字串（由上層回退 URL）。
     */
    fun titleOf(input: String, lower: String): String {
        val tOpen = lower.indexOf("<title")
        if (tOpen < 0) return ""
        val openEnd = lower.indexOf('>', tOpen)
        if (openEnd < 0) return ""
        val tClose = lower.indexOf("</title>", openEnd + 1)
        if (tClose < 0) return ""
        return input.substring(openEnd + 1, tClose)
    }

    /**
     * 線性剝除 `<script>...</script>`／`<style>...</style>`（index-based，
     * case-insensitive）；無對應關閉標籤者，自開始標籤剝至輸入結尾。
     * 以 StringBuilder 逐段拼接，不使用 backtracking regex。
     */
    fun stripScriptAndStyle(input: String, lower: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val sOpen = lower.indexOf("<script", i)
            val tOpen = lower.indexOf("<style", i)
            val open: Int
            val tag: String
            if (sOpen < 0 && tOpen < 0) {
                sb.append(input, i, input.length)
                break
            } else if (sOpen >= 0 && (tOpen < 0 || sOpen <= tOpen)) {
                open = sOpen
                tag = "script"
            } else {
                open = tOpen
                tag = "style"
            }
            sb.append(input, i, open)
            val closeMark = "</$tag>"
            val close = lower.indexOf(closeMark, open)
            i = if (close < 0) input.length else close + closeMark.length
        }
        return sb.toString()
    }

    fun publishedOf(html: String): String? {
        META_PUBLISHED.find(html)?.groupValues?.get(1)?.let { return unescapeHtml(it) }
        META_PUBLISHED_REV.find(html)?.groupValues?.get(1)?.let { return unescapeHtml(it) }
        TIME_DATETIME.find(html)?.groupValues?.get(1)?.let { return unescapeHtml(it) }
        return null
    }

    /** 線性去 tag（無 regex）：`<` 至下一個 `>`（含）視為一個 tag，輸出單一空白；若無 `>` 則捨棄其餘字串。 */
    private fun stripTags(html: String): String {
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                sb.append(html, i, html.length)
                break
            }
            sb.append(html, i, lt)
            val gt = html.indexOf('>', lt)
            if (gt < 0) break
            sb.append(' ')
            i = gt + 1
        }
        return sb.toString()
    }

    fun stripText(html: String): String =
        unescapeHtml(stripTags(html)).let { Spaces.replace(it, " ").trim() }

    private fun decodeCodePoint(cp: Int?): String? =
        if (cp != null && cp in 0..0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) String(Character.toChars(cp)) else null

    fun unescapeHtml(raw: String): String {
        var s = raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = NUMERIC_ENTITY.replace(s) { m ->
            decodeCodePoint(m.groupValues[1].toIntOrNull()) ?: m.value
        }
        s = HEX_ENTITY.replace(s) { m ->
            decodeCodePoint(m.groupValues[1].toIntOrNull(16)) ?: m.value
        }
        s = s.replace("&amp;", "&")
        return s
    }
}
