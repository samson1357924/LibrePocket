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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 全程 deadline 耗盡時由解析／清理迴圈拋出（CPU-bound 工作之硬界）。
 *
 * 刻意**不是** [CancellationException]（不與協程取消混淆）；刻意 unchecked。
 * 解析階段（parseHtml／HtmlExtract／cleanField 等）內只允許在迴圈頂端／每 512
 * code unit 檢查 [System.nanoTime] 後拋出此例外；任何不允許逸出的 catch-all
 * 皆須於其前重拋本例外；最終由 `WebExtractService` 的 deadline 映射 arm 收束為
 * `Failed(TIMEOUT)`（never cached）。
 */
internal class DeadlineExceededException : RuntimeException()

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
 *   放行之後依 [pipelineOf] 分派：HTML-family（`text/html`／`application/xhtml+xml`）
 *   走 HTML 管線（tag 剝除＋unescape）；其餘已放行型別走 raw-text 管線
 *   （不刪 tag、不 unescape，raw `&` 原樣保留）。
 * - Gzip：解壓同樣受位元組上限約束
 *  （bomb：小壓縮、大解壓 → `TOO_LARGE` fail-closed）；非 gzip 卻標 gzip
 *   或解壓失敗 → `BAD_RESPONSE`。
 * - Model 可見：預設 [DEFAULT_MODEL_CHARS]（20k），硬頂 [MAX_MODEL_CHARS]
 *  （50k）；超限截斷（UTF-16 surrogate 安全）＋`truncated=true`，非錯誤；
 *   `truncated` 時 envelope 內文為 `capped + "\n[...TRUNCATED AT $effModel CHARS]"`，
 *   且 [WebExtractOutcome.Ok] 仍攜帶 `truncated=true`。
 *   parser 輸入前綴上限 [PARSE_INPUT_CAP]（256k chars）與 model 預算截斷相互
 *   獨立；超過時（`inputCapped=true`，見 [ParseResult]）`truncated=true` 且
 *   envelope 內文為 `capped + "\n[...TRUNCATED INPUT AT $PARSE_INPUT_CAP CHARS]"`
 *   （input sentinel）；input cap 不隨 maxBytes 放大；任一發生 → truncated=true。
 * - 快取：命中（[WebExtractCache]，TTL [DEFAULT_TTL_MS] 60min；key 含 URL 去 fragment
 *   ＋`maxBytes`＋`maxModelChars`＋policy identity（`policy`＋`configRevision`）
 *   ＋**hop-0 經當次重驗解析並授權之位址集合**（去重＋排序之 sorted set，
 *   `round-robin` 返回答案順序不影響 key，length-prefix 附於 key 尾端），
 *   相同 policy/revision 但不同首跳解析答案不互撞）仍須 per-call 政策重驗
 *   （含 DNS preflight，fail-closed）；重驗通過才回 `cached=true` 之副本，HTTP upstream 不再出發。
 *   同 policy＋`configRevision` 不代表同一信任上下文——resolver／endpoint 為
 *   call-scoped；hop-0 位址集合綁定首跳決策結果，但**不含 redirect 逐跳之信任上下文**
 *   （已知殘留，見 D3 追蹤項 #1）；per-call 重驗仍是真正的權威；
 *   重驗失敗（denial／`Failed(TIMEOUT)`）不讀取快取，亦不觸 upstream。
 *   `Failed`／`Unavailable`／取消一律不寫。
 *   有界雙上限（正交）：筆數 [MAX_CACHE_ENTRIES]（200）＋位元組 [MAX_CACHE_BYTES]
 *   （4 MiB；near-weighted LRU；單筆超限不入快取）。
 *   重驗本身不 purge 快取；授權撤銷僅能經 `invalidate()`（drain 在途＋世代失效）
 *   ／bump `configRevision` 生效。
 *   rebind 時（identity＋revision 變更）呼叫方必須 `invalidate()`。
 *   跨 tier 內容永不共用。
 * - `inFlight` single-flight map：無界，但受 per-key timeout 有界（leader 受
 *   [TIMEOUT_MS] 端到端約束，逾時即完成並出 map；與 `WebSearchService` 同姿態）。
 *   key 同樣綁 hop-0 位址集合（與快取相同 identity；redirect 逐跳殘留見 D3 追蹤項 #1）。
 *   `invalidate()` bump 快取 generation epoch、清空快取，並 drain 所有在途 entry
 *   例外完成（`CancellationException`）→ follower 映射 `Unavailable(CANCELLED)`；
 *   invalidate 之前建立之條目／在途結果永遠無法重新出現在 cache 或 follower 路徑。
 * - `bytesKept`：`Ok.bytesKept`＝（若 gzip 解壓後之）實際位元組數；identity
 *   編碼時即 raw body 位元組數。
 * - 取消語義：外部取消（leader／呼叫方自身）原樣透傳 [CancellationException]，
 *   不映射、不寫快取、不 fallback（`fetchRawWithTimeout` 內五處 `ensureActive()` 守衛，與
 *   `WebHttp.fetchWithTimeout` 對稱）；leader 被取消而 follower 仍 active 者，
 *   follower 映射 typed `Unavailable(DETAIL_CANCELLED, null)`（never cached；
 *   follower 不得 out-fetch 已取消的 leader，不再進入單飛迴圈 refetch）；
 *   例外：leader 於取消前已完整產出 Ok（okForFollowers 路徑）者，follower
 *   收到該 Ok 而非 CANCELLED。
 * - 內容：`title/url/snippet/published/siteName` 正規化（HTML-family 見 [parseHtml]：
 *   tag 剝除＋entity unescape；非 HTML-family 已放行型別走 raw-text 管線，
 *   不刪 tag、不 unescape，raw `&` 原樣保留，見 [pipelineOf]），全經 [WebContentEnvelope.cleanField]（`nfkc → strip → sanitize` 定點）清理；模型可見體為
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
 *    ＋`maxBytes`＋`maxModelChars`＋`policy`＋`configRevision`
 *    ＋**hop-0 已核准位址集合**（[sortedDistinctAddressTokens]：位元組去重＋
 *    `(大小, 位元組序)` 排序＋`hostAddress` join；length-prefix 附於 key 尾端；
 *    round-robin 返回答案順序不影響 key）；不同首跳解析答案不互撞——
 *    resolver／endpoint 身份仍**刻意不入 key**，binding 的是當次決策之**結果**
 *    （已核准位址集合），per-call 政策重驗仍是真正的權威；同一 URL 以不同 resolver
 *    取用是允許的用法，呼叫方每次呼叫僅多付一次 policy preflight。
 *    **不含 port 維度之語義**（port 已內含於 URL 字串，無遺失，但 D3 須確認與政策側
 *    port 處理一致）。
 *    **已知殘留：redirect 逐跳之信任上下文尚未綁入 key**——僅 hop-0 綁定；
 *    跨 redirect 之 A/B 位址差異目前仍可能命中（redirect 鏈上的 hop 不會改變
 *    hop-0 的 key）；chain-recording 綁定（記錄逐跳 (url, 位址集合) 於 entry，
 *    命中時逐跳重驗）於本 PR（#138）Phase 1 後續 commit 處理。
 * 2. cache **不解析 `Cache-Control`（含 `no-store`）**：TTL-only，屬刻意
 *    設計（read 工具、無寫副作用）；D3 須決定 honor 或明文件化此行為。
 * 3. `cleanField` 已由共享 15s 全程端到端預算限界（parse→clean 管線逐層
 *    傳入單調 deadline，超過 → [DeadlineExceededException] → `Failed(TIMEOUT)`）；
 *    最壞 O(n²) 定點迭代之上界現由 wall budget 實際兜底（不僅靠 input cap）；
 *    D3 仍可加 marker-count 前掃（pre-scan）進一步壓縮最壞路徑。
 * 4. envelope 僅為標記籬笆，**非授權**；D3 須另行接 `#165` consent/授權
 *    流程，envelope 不擔任 authorization 角色。
 * 5. Follower `await()` 已由同一 `withTimeoutOrNull` 預算為界，逾時映射
 *    `Failed(TIMEOUT)`（audit 上與 `CANCELLED` 區分）；per-follower 獨立
 *    deadline 仍為可能的 D3 強化項。
 * 6. `timeoutMs <= 0` 會被 `coerceAtLeast(1)` 收斂為 1ms 預算 — 幾乎立即
 *    fail-closed `TIMEOUT`；屬保守方向，安全可接受。
 * 7. 位元組加權 LRU 快取上限已實作（[MAX_CACHE_BYTES] 4 MiB：near-weighted
 *    chars×2＋固定 overhead；單筆超限之 entry 永不入快取；筆數上限
 *    [MAX_CACHE_ENTRIES]（200）與之正交，兩者皆 enforced）。
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

    /** 快取上限筆數（LRU 有界）。與 [MAX_CACHE_BYTES] 正交：兩者皆 enforced。 */
    const val MAX_CACHE_ENTRIES = 200

    /**
     * 快取總位元組上限（4 MiB；near-weighted LRU：entry 權重＝
     * key／snippet／envelope／title／url／published／siteName 長度×2＋固定 overhead）。
     * 與 [MAX_CACHE_ENTRIES] 正交：兩者皆 enforced；單筆超限者不入快取。
     */
    const val MAX_CACHE_BYTES = 4L * 1024 * 1024

    /** 手動跟隨上限（超過即 `Failed(TOO_MANY_REDIRECTS)`）。 */
    const val MAX_REDIRECTS = 5

    /**
     * 全程超時預算（15s）。
     *
     * `extract()` 於入口即以單調時鐘（`System.nanoTime()`）計算全程 deadline：
     * per-call 政策重驗（含 DNS preflight）＋`extractUncached`（DNS preflight＋
     * 所有 redirect hops 之 HTTP 請求＋gzip 解壓＋parse／cleanField）共用此單一
     * 單調預算；
     * `extractUncached` 於解壓／解析／清理各迴圈內、以及返回 `Ok` 前檢查該 deadline
     *（逾時拋 [DeadlineExceededException]，映射 `Failed(TIMEOUT)`）；
     * follower `await()` 亦以同一預算為界（`withTimeoutOrNull`，逾時映射
     * `Failed(TIMEOUT)`）；協程取消時 transport 必須關閉 socket（見
     * [WebExtractHttpGet] 契約第 3 點），取消一律不寫快取；
     * decode 不計入 deadline，但受位元組／字元上限
     * 約束（cap-bounded）；逐跳以剩餘預算執行（見 `extractUncached`）。
     */
    const val TIMEOUT_MS = 15_000L

    /**
     * `truncated` 時 envelope 內文尾端之 sentinel 前綴（精確 `"[...TRUNCATED"`）；
     * 尾端完整形如 `[...TRUNCATED AT $N CHARS]`。Phase-2 測試可據此斷言。
     *
     * 亦為 input-cap sentinel（[TRUNCATED_INPUT_SENTINEL_MARK]，形如
     * `[...TRUNCATED INPUT AT $N CHARS]`）之共同前綴契約——兩個 sentinel 皆以
     * 此前綴開頭；本常數不得改名。
     */
    internal const val TRUNCATED_SENTINEL_MARK = "[...TRUNCATED"

    /**
     * parser 輸入前綴截斷（`html.length > PARSE_INPUT_CAP`，見 `parseHtml`）時
     * envelope sentinel 之前綴；尾端完整形如
     * `[...TRUNCATED INPUT AT $PARSE_INPUT_CAP CHARS]`（由 `parseHtml` 回報
     * `inputCapped=true`，見 [ParseResult]）。input cap 與 model 預算截斷相互獨立；
     * 任一發生即 `truncated=true`。與 [TRUNCATED_SENTINEL_MARK] 共享 `[...TRUNCATED`
     * 前綴契約。
     */
    internal const val TRUNCATED_INPUT_SENTINEL_MARK = "[...TRUNCATED INPUT AT "

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
        // 刻意不重拋 DeadlineExceededException：parse 階段尚未開始，本函式
        // 內部絕不產生該例外；fake transport 例外 → fail-closed TRANSPORT。
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TRANSPORT)
    }
    return RawFetchOut.Resp(response)
}

/** entry 權重之固定開銷（Object header／map node／bookkeeping 之近似估算）。 */
private const val ENTRY_OVERHEAD_BYTES = 256L

/**
 * 解析後位址集合之正規化 token 列表：先以位元組 `contentToString()` 去重
 *（同一 seeder 之 round-robin 多次答案不重複計入），再依
 * `(位元組大小, 位元組序)` 排序（大小順序容差：不同答案順序產生相同 token），
 * 最後以 `hostAddress` 呈現。cache key 綁定與（後續）鏈式驗證共用同一實作，
 * 避免兩套排序／去重邏輯漂移。
 */
internal fun sortedDistinctAddressTokens(addresses: List<InetAddress>): List<String> =
    addresses
        .distinctBy { it.address.contentToString() }
        .sortedWith(compareBy({ it.address.size }, { it.address.contentToString() }))
        .map { it.hostAddress }

/**
 * D4 抽取快取（TTL 60min；純 JVM）。
 *
 * - key＝去 fragment 之修剪字串（刻意**不做** URL 正規化，見 [stripFragment]
 *  與 header「D3 追蹤項」#1）＋`maxBytes`
 *  ＋`maxModelChars`＋policy identity（[EndpointNetworkOptIn.policy]＋
 *  [EndpointNetworkOptIn.configRevision]）
 *  ＋**hop-0 經當次重驗解析並授權之位址集合**（[addressToken]：先以位元組
 *  `contentToString()` 去重，再依 `(位元組大小, 位元組序)` 排序，以 `hostAddress`
 *  join 為 `,`；length-prefix 附於 key 尾端）；不同預算／不同 tier／
 *  不同 revision／不同首跳解析答案絕不互撞；`round-robin` 返回答案順序不影響 key；
 *  key 不含 resolver／endpoint 身份（per-call 重驗才是真正的權威），hop-0 位址
 *  集合僅綁定當次決策之結果；key 不含 redirect 逐跳之信任上下文（已知殘留，
 *  見 header「D3 追蹤項」#1）。
 * - 只存 `Ok`（`Failed`／`Unavailable`／取消不寫）；寫入時 `cached` 正規化
 *   為 `false`，讀回時正規化為 `true`（快取內一律以 `cached=false` 儲存，
 *   讀出一律以 `cached=true` 呈現；[WebExtractOutcome.Ok.cached] 預設
 *   `false` 不變）。
 * - 快取命中仍須 per-call 政策重驗（含 DNS preflight，fail-closed）：相同
 *  `policy`＋`configRevision` 但不同首跳解析答案（或不同 resolver／endpoint 安全上下文）
 *  不得重用結果——key 已綁 hop-0 位址集合，不同解析答案根本不會命中；
 *  重驗失敗（denial／TIMEOUT）不讀取快取；重驗本身不 purge
 *   快取；授權撤銷僅能經 `invalidate()`／bump `configRevision` 生效；跨 tier
 *  （不同 `policy`／`configRevision`）內容永不共用。
 * - 時鐘可注入，TTL-only（不解析 `Cache-Control`）：TTL 到期 lazy 淘汰＋`put` 順手掃；有界 LRU（access-order，`LinkedHashMap(accessOrder=true)`），雙上限正交：
 *  筆數上限 [WebExtract.MAX_CACHE_ENTRIES]（200）＋位元組上限
 *  [WebExtract.MAX_CACHE_BYTES]（4 MiB；建構子可注入小值測試）。
 *  entry 權重（near-weighted）＝key／snippet／envelope／title／url／published／
 *  siteName 之長度×2＋固定 [ENTRY_OVERHEAD_BYTES]；`put` 時先 sweep expired
 *  → 單筆超限直接不寫 → LRU（accessOrder）逐出直到放得下；`clear()` 歸零
 *  `currentBytes`；TTL sweep／覆寫同 key 皆即時調整 bytes 計。
 */
class WebExtractCache(
    val ttlMs: Long = WebExtract.DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = WebExtract.MAX_CACHE_BYTES,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be > 0" }
    }

    private data class Entry(val outcome: WebExtractOutcome.Ok, val expiresAt: Long, val weight: Long)

    /** 目前快取內 entry 權重之總和（bytes 近似；與 map.size 同鎖維護）。 */
    private var currentBytes = 0L

    private fun weightOf(k: String, o: WebExtractOutcome.Ok): Long {
        val c = o.content
        var w = ENTRY_OVERHEAD_BYTES
        w += k.length * 2L
        w += c.snippet.length * 2L
        w += o.envelope.length * 2L
        w += c.title.length * 2L
        w += c.url.length * 2L
        w += (c.published?.length ?: 0) * 2L
        w += (c.siteName?.length ?: 0) * 2L
        return w
    }

    private val map: MutableMap<String, Entry> =
        Collections.synchronizedMap(
            LinkedHashMap(64, 0.75f, true),
        )

    /**
     * 快取 generation epoch：每次 [clear]（含 `WebExtractService.invalidate()`）
     * 於 map 鎖內 bump 此值並清空 map（同一臨界區，原子）；寫入方／讀取方以
     * 進入時捕獲之 epoch 通過 [putByKey]／[getByKey] 的 guard，否則寫入被拒絕、
     * 讀取視同 miss（invalidate 之後陳舊條目不得重填快取、亦不得被讀回）。
     */
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    /** 目前 generation（供呼叫方捕獲 epoch 用）。 */
    internal val currentGeneration: Long get() = generation.get()

    /**
     * hop-0 位址集合之字串 token（去重＋排序後 `hostAddress` join 為 `,`；空集合 → 空字串）。
     * 僅 supply [key] 使用；token 本身不含分隔符 `\u0001`（hostAddress 只有 digits／`.`／`:`／`,`），
     * 且以 length-prefix 附於 key 尾端，杜絕與 URL／budget 三域互混。
     */
    private fun addressToken(addresses: List<InetAddress>): String =
        sortedDistinctAddressTokens(addresses).joinToString(",")

    fun key(
        url: String,
        maxBytes: Int,
        maxModelChars: Int,
        optIn: EndpointNetworkOptIn,
        addresses: List<InetAddress>,
    ): String {
        val u = url.trim()
        val token = addressToken(addresses)
        return "${u.length}:$u\u0001$maxBytes\u0001$maxModelChars\u0001${optIn.policy.name}\u0001${optIn.configRevision}\u0001${token.length}:$token"
    }

    fun get(
        url: String,
        maxBytes: Int,
        maxModelChars: Int,
        optIn: EndpointNetworkOptIn,
        addresses: List<InetAddress>,
    ): WebExtractOutcome.Ok? =
        getByKey(key(url, maxBytes, maxModelChars, optIn, addresses))

    // read-side epoch guard：呼叫方傳入進入時捕獲之 epoch（per-call 重驗後、
    // cache 存取點 a／b 皆傳 epochAtEntry）；不符即視同 miss——invalidate 之後
    // 陳舊條目不得被讀回。public get() 傳 null（語義不變：讀取當前有效條目）。
    internal fun getByKey(k: String, generationCaptured: Long? = null): WebExtractOutcome.Ok? {
        val entry: Entry?
        synchronized(map) {
            if (generationCaptured != null && generation.get() != generationCaptured) return null
            entry = map[k]
            if (entry != null && clockMs() >= entry.expiresAt) {
                map.remove(k)
                currentBytes -= entry.weight
                return null
            }
        }
        return entry?.outcome?.copy(cached = true, content = entry.outcome.content.copy())
    }

    fun put(
        url: String,
        maxBytes: Int,
        maxModelChars: Int,
        optIn: EndpointNetworkOptIn,
        addresses: List<InetAddress>,
        outcome: WebExtractOutcome.Ok,
    ) {
        // 公開 put() 以當下 generation 寫入（呼叫方對陳舊寫入的自辨屬其自身責任；
        // single-flight leader 路徑則傳入 extract() 入口捕獲之 epoch）。
        putByKey(key(url, maxBytes, maxModelChars, optIn, addresses), outcome, generation.get())
    }

    internal fun putByKey(k: String, outcome: WebExtractOutcome.Ok, generationCaptured: Long) {
        val now = clockMs()
        val expiresAt = now.coerceAtMost(Long.MAX_VALUE - ttlMs) + ttlMs
        val snapshot = outcome.copy(cached = false, content = outcome.content.copy())
        val weight = weightOf(k, snapshot)
        synchronized(map) {
            // epoch guard（置於 map 鎖內）：進入時捕獲之 generation 已變
            //（clear()/invalidate() 已 bump——bump 與清空同一臨界區），拒絕本次
            // 寫入——陳舊 leader 不得在 invalidate 之後重填快取。
            if (generation.get() != generationCaptured) return
            sweepLocked(now)
            if (weight > maxBytes) {
                // 單筆超限：寫入回 caller 但不入快取（approved D-8）；
                // 不驅逐其他 entry 為其騰位——合理之 miss。
                return
            }
            // 覆寫同 key：先扣除舊 entry 權重（避免雙計）。
            map.remove(k)?.let { currentBytes -= it.weight }
            // LRU（accessOrder LinkedHashMap）逐出直到放得下；筆數上限正交。
            while (map.isNotEmpty() &&
                (currentBytes + weight > maxBytes || map.size >= WebExtract.MAX_CACHE_ENTRIES)
            ) {
                val it = map.entries.iterator()
                val eldest = it.next()
                currentBytes -= eldest.value.weight
                it.remove()
            }
            map[k] = Entry(snapshot, expiresAt, weight)
            currentBytes += weight
        }
    }

    fun evictExpired() {
        synchronized(map) {
            sweepLocked(clockMs())
        }
    }

    fun clear() {
        // bump 與清空同一個 map 臨界區（原子）：任何捕獲舊 epoch 的寫入／讀取
        // 從此刻起一律被 [putByKey]／[getByKey] 拒絕（guard 以鎖內之 generation 判決）。
        synchronized(map) {
            generation.incrementAndGet()
            map.clear()
            currentBytes = 0L
        }
    }

    val size: Int get() = synchronized(map) { map.size }

    private fun sweepLocked(nowMs: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val next = it.next()
            if (nowMs >= next.value.expiresAt) {
                currentBytes -= next.value.weight
                it.remove()
            }
        }
    }
}

/**
 * D4 抽取服務（cache＋single-flight；取消不寫 cache）。
 *
 * 與 [WebSearchService] 同併發姿態：
 * - 命中快取 → `cached=true`（per-call 重驗通過後，[WebExtractCache.getByKey]
 *   回傳副本即正規化 `cached=true`；HTTP upstream 不再出發，但當次政策重驗
 *   （含 DNS preflight）仍執行）；快取 key 含 policy identity（`policy`＋`configRevision`）
 *   ＋hop-0 已核准位址集合；
 *   快取 key 刻意不含 resolver／endpoint 身份；重驗失敗不讀取快取；
 *   命中前仍須 per-call 政策重驗（含 DNS preflight），不同 resolver／endpoint
 *   同 policy＋revision 不得重用結果；重驗本身不 purge 快取；
 *   授權撤銷／政策變更後必須 `invalidate()`（或 bump `configRevision`）重綁，
 *   否則舊決策內容仍會命中。
 * - 同 key 併發合流（leader 打、follower `await()`；follower 取消不影響 leader）。
 * - `inFlight` map：無界，但受 per-key timeout 有界——leader 受全程 deadline
 *   （[WebExtract.TIMEOUT_MS]）約束，完成／失敗即出 map；與 `WebSearchService`
 *   同姿態（不為 inFlight 另設容量上限）；`invalidate()` 會 drain 所有在途
 *   entries（以 `CancellationException` 例外完成，follower 映射 `Unavailable(CANCELLED)`）。
 * - 取消／`Failed`／`Unavailable` 一律不寫快取。
 */
class WebExtractService(
    private val httpGet: WebExtractHttpGet,
    private val cache: WebExtractCache = WebExtractCache(),
    private val timeoutMs: Long = WebExtract.TIMEOUT_MS,
) {
    /**
     * 撤銷原語：revocation primitive。
     *
     * - `cache.clear()`（bump generation epoch 與清空 map 同一 map 臨界區）：
     *   invalidate() 之前建立之快取條目永遠無法再被回傳。
     * - 排空當前 in-flight entries：`inFlight` 內所有 pending leader 之
     *   `CompletableDeferred` 以 `CancellationException("web.extract invalidated")`
     *   完成（exceptionally）。已 detach 之 follower 經 `await()` 收到
     *   CancellationException → 映射為 `Unavailable(DETAIL_CANCELLED, null)`
     *   （never cached）；follower 自身的取消仍以 `ensureActive()` 優先透傳。
     * - invalidate() 之後之新呼叫走完整 policy／DNS cycle（含 cache 重驗），
     *   不得重用 invalidate 前之 cache 結果（read-side 世代守衛：陳舊條目
     *   直接 miss 並重打 upstream）；cache 路徑不可能洩漏撤權限內容。
     *   in-flight 路徑依 detached 語義處理：已 join 之 follower 於 drain 前
     *   收到 leader 已完成之 Ok（leader 自身 caller 同理）；drain 時仍 pending
     *   者收到 `CancellationException("web.extract invalidated")` → 映射
     *   `Unavailable(DETAIL_CANCELLED, null)`（never cached）。
     * - leader 於 drain 之後完成者：其 `Ok` 仍正常返回給它自己的 caller，
     *   但 cache 寫入被 epoch guard 抑制；follower 因 `m.complete(outcome)`
     *   對已例外完成的 deferred 無效而只見 CancellationException。
     *
     * resolver／政策變更後必須 `invalidate()`（或 bump `configRevision`）重綁，
     * 否則舊解析之內容仍會命中（重驗無法區分 resolver/endpoint 身份）。
     */
    suspend fun invalidate() {
        cache.clear()
        mutex.withLock {
            val pending = inFlight.values.toList()
            inFlight.clear()
            pending.forEach {
                it.completeExceptionally(CancellationException("web.extract invalidated"))
            }
        }
    }

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
        // 全程單一單調 deadline：per-call 政策重驗（含 DNS preflight）＋
        // `extractUncached`（DNS preflight＋逐跳 HTTP＋解壓）共用此單一單調預算。
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        // 入口捕獲 cache generation epoch：leader 完成時若 epoch 已變
        //（期間發生過 invalidate()/clear()），抑制本次 cache 寫入
        //（caller 自身仍正常拿到 outcome；僅 cache write 被抑制）。
        val epochAtEntry = cache.currentGeneration
        val hop0 = stripFragment(rawUrl.trim())
        while (true) {
            // per-call 政策重驗（含 DNS preflight），fail-closed；必須在 cache／
            // inFlight 存取之前完成——initial peek、lateHit（mutex 內）、follower join
            // 三個返回點皆位於此檢查之後，單次重驗覆蓋全部三者；通過後才觸 cache。
            // cache miss 的 hop 0 會再評估一次（revalidate 與 hop-0 評估各一次 DNS；取代：不 memoize，避免讓 gate 變可變狀態）
            val gate = try {
                if (NetworkPolicy.requiresDnsLookup(hop0, optIn)) {
                    when (
                        val pre = NetworkPolicy.runPreflightSuspend(deadlineNs) {
                            NetworkPolicy.evaluateUrl(hop0, optIn, resolver)
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
                    NetworkPolicy.evaluateUrl(hop0, optIn, resolver)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 政策評估（pre-parse）不產生 DeadlineExceededException；
                // evaluateUrl／resolver 之例外一律 fail-closed TRANSPORT。
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
            }
            if (!gate.allowed) {
                // 與跳０ policy 拒絕同一映射；fail-closed、不觸 network、不寫快取。
                return WebExtractOutcome.Unavailable(
                    gate.denyCode?.name ?: NetworkDenyCode.INVALID_HOST.name,
                    gate.denyCode,
                )
            }
            // hop-0 綁定：key 納入當次重驗所解析並授權之位址集合（與閘共用同一份
            // eval，不二次查 DNS）；sorted set 語義（去重＋排序），round-robin
            // 返回答案順序不影響 key identity。
            val k = cache.key(hop0, effBytes, effModel, optIn, gate.resolvedAddresses)
            cache.getByKey(k, epochAtEntry)?.let { return it }

            var lateHit: WebExtractOutcome.Ok? = null
            var mine: CompletableDeferred<WebExtractOutcome>? = null
            var leader = false
            mutex.withLock {
                lateHit = cache.getByKey(k, epochAtEntry)
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
            val m = mine ?: error("extract() invariant violated: no in-flight entry elected")
            if (!leader) {
                try {
                    // per-follower deadline：以整體預算為界包住 await；
                    // 逾時映射 Failed(TIMEOUT)（與 CANCELLED 的 audit 語義區分）；
                    // follower 不得在 leader 卡於 parse／gunzip 時無界等待。
                    val waitMs = ((deadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                    return withTimeoutOrNull(waitMs) { m.await() }
                        ?: WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT)
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
                    // m.await() 不執行解析階段，DeadlineExceededException 不會由此逸出；
                    // leader 異常貫穿時 fail-closed TRANSPORT。
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                }
            }

            var okForFollowers: WebExtractOutcome.Ok? = null
            try {
                val outcome = extractUncached(rawUrl, optIn, resolver, effBytes, effModel, deadlineNs)
                if (outcome is WebExtractOutcome.Ok) okForFollowers = outcome
                currentCoroutineContext().ensureActive()
                if (outcome is WebExtractOutcome.Ok && cache.currentGeneration == epochAtEntry) {
                    // epoch guard：extract() 入口後發生過 invalidate()/clear()，
                    // 陳舊結果不得寫回快取（m.isCompleted 情形亦由本檢查涵蓋——
                    // drain 會 bump generation；leader 仍正常返回 outcome 予其 caller）。
                    // pre-cache-write gate：預算已耗盡則跳過寫入
                    //（caller 仍正常拿到 outcome；僅 cache 寫入被抑制）。
                    if (System.nanoTime() < deadlineNs) {
                        cache.putByKey(k, outcome, epochAtEntry)
                    }
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
            } catch (e: DeadlineExceededException) {
                // parse／gunzip 內部 deadline 耗盡逸出（本 commit 起由
                // extractUncached 的 wrapper 先接為 Failed(TIMEOUT)，此 arm 為
                // extract() 側 backstop）：m 必須 settle 為 Failed(TIMEOUT)，
                // follower 映射同 detail；never Ok、never cached。
                val failed = WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT)
                m.complete(failed)
                return failed
            } catch (_: Exception) {
                // 剩餘例外（非 DeadlineExceededException）一律 fail-closed
                // TRANSPORT（m 必須 settle；follower 不得 hang）。
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
                // DeadlineExceededException 屬 Exception，已於上方 arm 攔截
                //（不會到達此 arm）。
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
        deadlineNs: Long,
    ): WebExtractOutcome =
        // 任何 DeadlineExceededException（gunzip／parse／cleanup 之 deadline 耗盡）
        // → 與預算耗盡同 detail：Failed(TIMEOUT)；never Ok、never cached。
        try {
            extractUncachedInner(rawUrl, optIn, resolver, effBytes, effModel, deadlineNs)
        } catch (e: DeadlineExceededException) {
            WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT)
        }

    private suspend fun extractUncachedInner(
        rawUrl: String,
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        effBytes: Int,
        effModel: Int,
        deadlineNs: Long,
    ): WebExtractOutcome {
        // 全程單一單調 deadline（由 extract() 入口計算並傳入）：DNS preflight
        // ＋所有 redirect hop＋解壓共用；與 per-call 政策重驗共享同一預算。
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
                // 逐跳政策評估（pre-fetch／pre-parse）不產生 DeadlineExceededException；
                // evaluateUrl／resolver 之例外一律 fail-closed TRANSPORT。
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
                // 純 URL 解析，不產生 DeadlineExceededException；失敗 → null（fail-closed 後續檢查）。
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
            // 缺席該 header 放行（legacy）。分派至管線（[pipelineOf]）在閘之後進行。
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
                when (val dec = gunzipCapped(rawBytes, effBytes, deadlineNs)) {
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
            // 管線分派：HTML-family 走 parseHtml；其餘已放行 MIME 走 raw-text
            // 管線（不刪 tag、不 unescape；raw `&` 原樣保留）。
            // RawText 不使用 parser 輸入前綴 cap（inputCapped=false）；
            // 其工作量由 bytes 上限與 deadline 約束。
            val parsed = when (pipelineOf(contentType)) {
                Pipeline.Html -> parseHtml(decoded, normalized, deadlineNs)
                Pipeline.RawText -> {
                    val snippet = HtmlExtract.Spaces.replace(
                        WebContentEnvelope.cleanField(decoded, deadlineNs),
                        " ",
                    ).trim()
                    if (snippet.isEmpty()) {
                        // snippet 清成空白 → BAD_RESPONSE（鏡照 HTML 管線之契約）。
                        null
                    } else {
                        ParseResult(
                            WebExtractContent(
                                title = normalized,
                                url = normalized,
                                snippet = snippet,
                                published = null,
                                siteName = hostOf(normalized),
                            ),
                            inputCapped = false,
                        )
                    }
                }
            } ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)

            // Model 可見截斷（定點清理後、截斷後、envelope 最外；見 cleanField）。
            val cleanSnippet = WebContentEnvelope.cleanField(parsed.content.snippet, deadlineNs)
            val cleanTitle = WebContentEnvelope.cleanField(parsed.content.title, deadlineNs).takeExtractPrefix(500)
            val cleanUrl = WebContentEnvelope.cleanField(parsed.content.url, deadlineNs).takeExtractPrefix(2048)
            val cleanSiteName = parsed.content.siteName?.let {
                WebContentEnvelope.cleanField(it, deadlineNs).takeExtractPrefix(253)
            }
            val cleanPublished = parsed.content.published?.let {
                WebContentEnvelope.cleanField(it, deadlineNs).takeExtractPrefix(128)
            }
            val capped = cleanSnippet.takeExtractPrefix(effModel)
            val modelTruncated = capped.length < cleanSnippet.length
            // input 前綴截斷（parser 輸入 > PARSE_INPUT_CAP）與 model 預算截斷相互獨立；
            // 任一發生 → truncated=true。
            val truncated = modelTruncated || parsed.inputCapped
            val content = WebExtractContent(
                title = cleanTitle,
                url = cleanUrl,
                snippet = capped,
                published = cleanPublished,
                siteName = cleanSiteName,
            )
            // pre-Ok gate：預算已耗盡則絕不建構／返回逾越預算之 Ok
            //（parse／gunzip 內部 deadline 檢查之外的最後一道守門）。
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val envelope = if (!truncated) {
                WebContentEnvelope.wrap(capped, deadlineNs)
            } else if (modelTruncated) {
                WebContentEnvelope.wrap(capped + "\n${WebExtract.TRUNCATED_SENTINEL_MARK} AT $effModel CHARS]", deadlineNs)
            } else {
                // truncated 僅由 parser 輸入前綴截斷觸發（model 預算未截）：input sentinel。
                WebContentEnvelope.wrap(capped + "\n${WebExtract.TRUNCATED_INPUT_SENTINEL_MARK}$PARSE_INPUT_CAP CHARS]", deadlineNs)
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
        // 純 URL 解析，不產生 DeadlineExceededException；無法解析 → BAD_REDIRECT（fail-closed）。
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
 * MIME 分派管線：HTML-family 走 HTML 管線（[parseHtml]：stripScriptAndStyle →
 * stripText → 空白收斂）；其餘已放行型別走 raw-text 管線
 * （`decoded → cleanField → Spaces.replace → trim`；不刪 tag、不 unescape，
 * raw `&` 原樣保留）。
 */
internal enum class Pipeline { Html, RawText }

/**
 * 依 Content-Type 分派抽取管線。
 *
 * HTML family：`text/html`、`application/xhtml+xml` → [Pipeline.Html]；
 * 其餘已由 [isAllowedContentType] 放行的型別（`text/` 開頭非 html、`application/json`、
 * `application/xml`、`+xml`／`+json` suffixed）→ [Pipeline.RawText]；
 * header 缺席 → [Pipeline.Html]（legacy 行為）。型別比對大小寫不敏感、`;` 後參數忽略。
 *
 * [isAllowedContentType] 仍為位元組／型別閘（byte/type gate），不在此調整；
 * 管線分派為下游關注點。
 * Content-Type 如 `text/html x`（無 `;`）fallthrough 到 RawText，envelope 仍單層密封；malformed 但放行即為 legacy 行為。
 */
internal fun pipelineOf(contentType: String?): Pipeline {
    if (contentType == null) return Pipeline.Html
    val ct = contentType.substringBefore(';').trim().lowercase()
    if (ct.isEmpty()) return Pipeline.Html
    return if (ct == "text/html" || ct == "application/xhtml+xml") Pipeline.Html else Pipeline.RawText
}

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
 * 受上限的 gzip 解壓（bomb fail-closed；deadline 有界）。
 *
 * - 解壓超過 [cap] 即回 [GunzipOut.TooLarge]（不回部分資料）；
 * - 非 gzip／損壞即回 [GunzipOut.Corrupt]（→ `BAD_RESPONSE`，與超限區分）；
 * - 每次 `gz.read` 前、以及回傳前各檢查一次 [deadlineNs]：逾時拋
 *   [DeadlineExceededException]（→ `Failed(TIMEOUT)`；絕不回部分資料）。
 *   預設 `Long.MAX_VALUE`（直接呼叫語義不變）。
 */
internal fun gunzipCapped(data: ByteArray, cap: Int, deadlineNs: Long = Long.MAX_VALUE): GunzipOut {
    return try {
        GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
            val out = ByteArrayOutputStream(minOf(cap.coerceAtLeast(1), 8192))
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
                val n = gz.read(buf)
                if (n == -1) break
                if (total + n > cap) return GunzipOut.TooLarge
                out.write(buf, 0, n)
                total += n
            }
            // 回傳前再確認一次預算：膨脹工作不得逾越預算後才交出位元組。
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            GunzipOut.Ok(out.toByteArray())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeadlineExceededException) {
        // deadline 耗盡不得被吞沒：never partial output。
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
        // 純 charset 名解析，不產生 DeadlineExceededException；未知 charset → UTF-8 降級。
        Charsets.UTF_8
    }
    return try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(data))
            .toString()
    } catch (e: DeadlineExceededException) {
        // DeadlineExceededException 不得被吞沒（防禦：decode 本身不產生它，
        // 此 arm 僅保險接住於 parse 階段內逸出的該例外並原樣上拋）。
        throw e
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

/**
 * parseHtml 輸入上限（chars）；超過者僅取前 [PARSE_INPUT_CAP] 字元解析。
 *
 * 此為獨立之 CPU／記憶體硬界，與 model 預算截斷（[WebExtract.MAX_MODEL_CHARS]）
 * 相互獨立，且不隨 `maxBytes` 放大（即使呼叫方放行最大 1 MiB，本值不變）。
 * 超過時 [parseHtml] 於 [ParseResult.inputCapped] 回報 true → 上層 `truncated=true`
 * 並於 envelope 內嵌 input sentinel（見 [WebExtract.TRUNCATED_INPUT_SENTINEL_MARK]）。
 */
private const val PARSE_INPUT_CAP = 256 * 1024

/**
 * parseHtml 之結果：正規化後之 [content]，以及 [inputCapped]
 * （`html.length > PARSE_INPUT_CAP`，即 parser 僅見到 `html` 之前綴）。
 */
internal data class ParseResult(val content: WebExtractContent, val inputCapped: Boolean)

/**
 * 最小 HTML→內容解析（無依賴、正規化 schema）。
 *
 * 所有工作皆在 `html.take(PARSE_INPUT_CAP)` 上進行；`html.length > PARSE_INPUT_CAP`
 * 時回傳之 [ParseResult.inputCapped] 為 true（input 前綴截斷，與 model 預算截斷
 * 相互獨立；不隨 maxBytes 放大）。title 提取與
 * `<script>/<style>` 剝除皆為 index-based 線性掃描（不使用 lazy backtracking
 * regex），避免病態輸入觸發災難性回溯。metadata（published）改為單趟、
 * 前向游標的屬性解析器（無 regex），整體 O(n)。
 * 所有迴圈受 [deadlineNs] 約束：逾時拋 [DeadlineExceededException]
 *（不被 catch-all 吞沒，原樣上拋給呼叫方映射為 `Failed(TIMEOUT)`）。
 *
 * - `title`：首個 `<title>` 文本（去 tag＋unescape＋trim）；缺或空白 → 回退 URL。
 * - `snippet`：剔除 `<script>/<style>` 後去 tag＋unescape＋空白收斂；空白 → 回退
 *   title（仍空白即整體空白，由上層判 `BAD_RESPONSE`）。
 * - `published`：`article:published_time`／`datePublished`／`publish_date`／`pubdate`／
 *   `dc.date`（`property`／`name` 屬性，大小寫不敏感），或 `<time datetime>`
 *   首個非空；無 → null；空白收斂後截斷至 128 chars。
 * - `siteName`：[hostOf]（失敗回 null，不拋）。
 * - 任何異常 → 回 null（→ `BAD_RESPONSE`）；[DeadlineExceededException] 例外，
 *   原樣上拋。
 * - 回傳 [ParseResult]：[ParseResult.content] 為正規化內容；
 *   [ParseResult.inputCapped] 為 `html.length > PARSE_INPUT_CAP`
 *   （true → 上層 `truncated=true`；若 model 預算未截，envelope 內嵌
 *   `[...TRUNCATED INPUT AT $PARSE_INPUT_CAP CHARS]` input sentinel）。
 *
 * `<script`／`<style`／`<title` 僅在緊接的字元為 ASCII 空白、`/`、`>` 或 EOF
 * 時才被視為標籤（tag-name 邊界）；如 `<scripture>`／`<titlex>` 不再誤判。
 */
internal fun parseHtml(html: String, finalUrl: String, deadlineNs: Long = Long.MAX_VALUE): ParseResult? {
    return try {
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        val inputCapped = html.length > PARSE_INPUT_CAP
        val input = html.take(PARSE_INPUT_CAP)
        // ASCII 摺疊（長度不變）：所有被搜的 needle（`<title`／`<script`／`<style`／
        // `</title>`／`</script>`／`</style>`）皆 ASCII，索引必然不錯位
        //（ICU lowercase 可能變長度，故不用）。
        val lower = buildString(input.length) {
            for (c in input) append(if (c in 'A'..'Z') c + 32 else c)
        }
        // 先剝 script/style，title／published 皆於剝除後的 body 上提取
        //（不得在 script/style 內容中命中 <title> 或 meta published）。
        val body = HtmlExtract.stripScriptAndStyle(input, lower, deadlineNs)
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        val bodyLower = buildString(body.length) {
            for (c in body) append(if (c in 'A'..'Z') c + 32 else c)
        }
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        val titleRaw = HtmlExtract.titleOf(body, bodyLower, deadlineNs)
        val titleText = HtmlExtract.stripText(titleRaw, deadlineNs).trim().takeIf { it.isNotEmpty() } ?: finalUrl
        val text = HtmlExtract.stripText(body, deadlineNs).trim().let { HtmlExtract.Spaces.replace(it, " ") }
        val snippet = text.takeIf { it.isNotEmpty() } ?: titleText
        val published = HtmlExtract.publishedOf(body, deadlineNs)
            ?.let { HtmlExtract.Spaces.replace(it, " ") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeExtractPrefix(128)
        ParseResult(
            WebExtractContent(
                title = titleText,
                url = finalUrl,
                snippet = snippet,
                published = published,
                siteName = hostOf(finalUrl),
            ),
            inputCapped,
        )
    } catch (e: DeadlineExceededException) {
        // deadline 耗盡不得被吞沒：原樣上拋給呼叫方（service 映射 Failed(TIMEOUT)）。
        throw e
    } catch (_: Exception) {
        null
    }
}

private object HtmlExtract {
    val Spaces = Regex("\\s+")
    val NUMERIC_ENTITY = Regex("&#(\\d+);")
    val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

    /** tag-name 邊界：緊接 needle 之 byte 必須為 ASCII 空白、`/`、`>` 或 EOF。 */
    private fun isTagNameBoundary(lower: String, p: Int): Boolean {
        if (p >= lower.length) return true // EOF
        val c = lower[p]
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' ||
            c == '\u000C' || c == '\r' || c == '/' || c == '>'
    }

    /**
     * 取首個 `<title>...</title>` 文本（index-based，case-insensitive；
     * tag-name 邊界檢查）；任一環節缺失即回空字串（由上層回退 URL）。
     */
    fun titleOf(input: String, lower: String, deadlineNs: Long = Long.MAX_VALUE): String {
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        var tOpen = -1
        var cursor = 0
        var scanned = 0
        while (true) {
            if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val p = lower.indexOf("<title", cursor)
            if (p < 0) break
            if (isTagNameBoundary(lower, p + "<title".length)) {
                tOpen = p
                break
            }
            // 非標籤（如 `<titlex>`）：游標前進一個 past-offending-`<` 之位置續掃。
            cursor = p + 1
        }
        if (tOpen < 0) return ""
        val openEnd = lower.indexOf('>', tOpen)
        if (openEnd < 0) return ""
        val tClose = lower.indexOf("</title>", openEnd + 1)
        if (tClose < 0) return ""
        return input.substring(openEnd + 1, tClose)
    }

    /**
     * 線性剝除 `<script>...</script>`／`<style>...</style>`（index-based，
     * case-insensitive，tag-name 邊界檢查；無對應關閉標籤者，
     * 自開始標籤剝至輸入結尾）。
     * 以 StringBuilder 逐段拼接，不使用 backtracking regex。
     * 雙游標（scriptCandidate／styleCandidate）皆單調前進，總工作量 O(n)。
     */
    fun stripScriptAndStyle(input: String, lower: String, deadlineNs: Long = Long.MAX_VALUE): String {
        val sb = StringBuilder(input.length)
        var i = 0
        var scriptCandidate = 0
        var styleCandidate = 0
        while (i < input.length) {
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            var sOpen = -1
            var scannedCandidates = 0
            while (true) {
                if (++scannedCandidates and 511 == 0 && System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
                val p = lower.indexOf("<script", scriptCandidate)
                if (p < 0) {
                    scriptCandidate = lower.length
                    break
                }
                if (isTagNameBoundary(lower, p + "<script".length)) {
                    sOpen = p
                    scriptCandidate = p
                    break
                }
                scriptCandidate = p + 1
            }
            var tOpen = -1
            scannedCandidates = 0
            while (true) {
                if (++scannedCandidates and 511 == 0 && System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
                val p = lower.indexOf("<style", styleCandidate)
                if (p < 0) {
                    styleCandidate = lower.length
                    break
                }
                if (isTagNameBoundary(lower, p + "<style".length)) {
                    tOpen = p
                    styleCandidate = p
                    break
                }
                styleCandidate = p + 1
            }
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
            val end = if (close < 0) input.length else close + closeMark.length
            // 被挪走的區段內不得再命中：兩游標皆不倒退。
            scriptCandidate = maxOf(scriptCandidate, end)
            styleCandidate = maxOf(styleCandidate, end)
            i = end
        }
        return sb.toString()
    }

    // ---- published metadata（單趟、前向游標；無 regex） ----

    private val PUBLISHED_ALIASES = setOf(
        "article:published_time",
        "datepublished",
        "publish_date",
        "pubdate",
        "dc.date",
    )

    private fun isAsciiWs(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == ':'

    private data class MetaAttrs(val propertyName: String?, val content: String?, val datetime: String?)

    /**
     * 自 tag 起始處，抽取 `property|name`／`content`／`datetime` 屬性
     *（key 大小寫不敏感；值以單／雙引號或裸字元表示）。
     */
    private fun parseTagAttrs(tag: String, deadlineNs: Long): MetaAttrs {
        var propertyName: String? = null
        var content: String? = null
        var datetime: String? = null
        var pos = 0
        var scanned = 0
        val n = tag.length
        // 跳過前導 `<meta`／`<time`。
        while (pos < n && !isAsciiWs(tag[pos]) && tag[pos] != '>') {
            if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                throw DeadlineExceededException()
            }
            pos++
        }
        while (pos < n) {
            if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                throw DeadlineExceededException()
            }
            val c = tag[pos]
            if (c == '>') break
            if (isAsciiWs(c) || c == '/') {
                pos++
                continue
            }
            val nameStart = pos
            while (pos < n && isNameChar(tag[pos])) {
                if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
                pos++
            }
            if (nameStart == pos) {
                // 無法作為屬性名首字元：前進一格再同步。
                pos++
                continue
            }
            val attrName = tag.substring(nameStart, pos).lowercase()
            while (pos < n && isAsciiWs(tag[pos])) {
                if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
                pos++
            }
            if (pos < n && tag[pos] == '=') {
                pos++
                while (pos < n && isAsciiWs(tag[pos])) {
                    if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                        throw DeadlineExceededException()
                    }
                    pos++
                }
                if (pos < n && (tag[pos] == '"' || tag[pos] == '\'')) {
                    val quote = tag[pos]
                    pos++
                    val valStart = pos
                    while (pos < n && tag[pos] != quote) {
                        if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                            throw DeadlineExceededException()
                        }
                        pos++
                    }
                    val value = tag.substring(valStart, pos)
                    if (pos < n) pos++ // 消耗收尾引號
                    when (attrName) {
                        "property", "name" -> propertyName = value
                        "content" -> content = value
                        "datetime" -> datetime = value
                    }
                } else {
                    val valStart = pos
                    while (pos < n && !isAsciiWs(tag[pos]) && tag[pos] != '>') {
                        if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                            throw DeadlineExceededException()
                        }
                        pos++
                    }
                    val value = tag.substring(valStart, pos)
                    when (attrName) {
                        "property", "name" -> propertyName = value
                        "content" -> content = value
                        "datetime" -> datetime = value
                    }
                }
            }
            // 無值屬性（如 `<meta disabled>`）：略過值解析，繼續。
        }
        return MetaAttrs(propertyName, content, datetime)
    }

    /**
     * 自 `from` 起尋找 tag 收尾 `>`：引號內的 `>` 不算收尾
     *（`content="a>>>b"` 是合法 HTML；tag 只在未引號態的 `>` 處結束）。
     * 無法找到即回 -1（未閉合 tag）；全文掃描 O(n)。
     */
    private fun findTagEnd(lower: String, from: Int, deadlineNs: Long): Int {
        var inQuote: Char? = null
        var j = from
        var scanned = 0
        while (j < lower.length) {
            if (++scanned and 511 == 0 && System.nanoTime() >= deadlineNs) {
                throw DeadlineExceededException()
            }
            val c = lower[j]
            val q = inQuote
            if (q != null) {
                if (c == q) inQuote = null
            } else if (c == '"' || c == '\'') {
                inQuote = c
            } else if (c == '>') {
                return j
            }
            j++
        }
        return -1
    }

    /**
     * 單趟、前向游標的 published 抽取器（O(n)；取代先前 META_PUBLISHED／
     * META_PUBLISHED_REV／TIME_DATETIME 三 regex 的病態回溯風險）。
     * alias 清單比對先於 NFKC（fullwidth alias 不符；fail-closed，no published）。
     *
     * 先掃 `<meta` 候選（依文件順序），每候選標籤以引號感知的掃描取得 `>`
     * 收尾（`content="a>>>b"` 內的 `>` 不算收尾），再切出
     * `property`／`name`＋`content` 屬性對，`property`／`name` 值（大小寫不
     * 敏感）與五個 published alias 比對（article:published_time／datePublished／
     * publish_date／pubdate／dc.date），命中即回 `unescapeHtml(content)`；
     * 否則續掃。無匹配時再掃 `<time datetime>` 候選同法解析。
     * 對抗性 `<meta a` 鏈（無 `>`）：首個候選即整段掃描收尾，之後無後續
     * 候選——整體線性。
     */
    fun publishedOf(html: String, deadlineNs: Long = Long.MAX_VALUE): String? {
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        // ASCII 摺疊（長度不變）：所有 needle 皆 ASCII。
        val lower = buildString(html.length) {
            for (c in html) append(if (c in 'A'..'Z') c + 32 else c)
        }
        var cursor = 0
        while (true) {
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val p = lower.indexOf("<meta", cursor)
            if (p < 0) break
            if (!isTagNameBoundary(lower, p + "<meta".length)) {
                cursor = p + 1
                continue
            }
            val tagEnd = findTagEnd(lower, p, deadlineNs)
            if (tagEnd < 0) break // 未閉合 tag：無更多可解析候選
            val attrs = parseTagAttrs(html.substring(p, tagEnd + 1), deadlineNs)
            val name = attrs.propertyName?.lowercase()
            if (name != null && name in PUBLISHED_ALIASES && !attrs.content.isNullOrBlank()) {
                return unescapeHtml(attrs.content)
            }
            cursor = tagEnd + 1
        }
        cursor = 0
        while (true) {
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val p = lower.indexOf("<time", cursor)
            if (p < 0) break
            if (!isTagNameBoundary(lower, p + "<time".length)) {
                cursor = p + 1
                continue
            }
            val tagEnd = findTagEnd(lower, p, deadlineNs)
            if (tagEnd < 0) break
            val attrs = parseTagAttrs(html.substring(p, tagEnd + 1), deadlineNs)
            if (!attrs.datetime.isNullOrBlank()) {
                return unescapeHtml(attrs.datetime)
            }
            cursor = tagEnd + 1
        }
        return null
    }

    /** 線性去 tag（無 regex）：`<` 至下一個 `>`（含）視為一個 tag，輸出單一空白；若無 `>` 則捨棄其餘字串。 */
    private fun stripTags(html: String, deadlineNs: Long = Long.MAX_VALUE): String {
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
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

    fun stripText(html: String, deadlineNs: Long = Long.MAX_VALUE): String =
        unescapeHtml(stripTags(html, deadlineNs)).let { Spaces.replace(it, " ").trim() }

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
