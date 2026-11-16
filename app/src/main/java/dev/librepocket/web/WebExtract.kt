package dev.librepocket.web

import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkEvaluation
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
import java.util.Locale
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
 *
 * 協程取消不是本例外：synchronous 迴圈另有 `checking: () -> Unit = {}` 探針
 * （協程活性檢查，production 為 `ensureActive()`）於相同檢查點呼叫，探針拋出
 * [CancellationException] 時原樣上拋、與 DeadlineExceededException 正交；
 * 不允許逸出的 catch-all 亦須於其前重拋 CancellationException。
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
 *   （fail-closed，絕不跟隨）；
 *   206 Partial Content 例外地在該分支之前即 fail-closed——本抽取器不發 Range、
 *   不組裝分段（RFC 9110 §15.3.7），一律 `Failed(HTTP_206)`（絕不寫快取、絕不交付完整 Ok）。
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
 *   （不刪 tag、不 unescape，raw `&` 原樣保留；保留原文行結構與內部連續空白——
 *   僅 `\n`／`\t` 保留，`\r` 移除，其餘空白僅 NFKC 摺疊＋首尾 trim）。
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
 *   call-scoped；hop-0 位址集合綁定首跳決策結果，redirect 逐跳之信任上下文由
 *   entry 攜帶之 [HopBinding] 鏈（每跳 url＋已核准位址 sorted token）逐跳重驗把關：
 *   每跳 policy（含 DNS）必須 allowed、且當次解析之 token 必須與記錄一致；
 *   任一跳不符即視為 miss、重打 upstream（`putByKey` 覆寫），絕不回傳該 Ok——
 *   **成本：每次 cache hit 額外付 L×(DNS+policy) 驗證成本（L＝chain 長度）**；
 *   single-flight follower 於 Ok 交付前以自己的 opt-in／resolver／deadline 逐跳重驗
 *   leader 記錄之 chain（規則與 fetch 相同）；不符即視為 miss、follower 自行重選舉
 *   （自己以自己的 binding fetch），絕不交付該 Ok（見 D3 追蹤項 #1）；
 *   per-call 重驗仍是真正的權威；
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
 *   key 同樣綁 hop-0 位址集合（與快取相同 identity；redirect 逐跳由
 *   follower 之 Ok 交付前 chain-face 驗證把關——見 D3 追蹤項 #1）。
 *   `invalidate()` bump 快取 generation epoch、清空快取，並 drain 所有在途 entry
 *   例外完成（`CancellationException`）→ follower 映射 `Unavailable(CANCELLED)`；
 *   invalidate 完成之後，invalidate 之前建立之條目／在途結果不得重新出現在 cache 或 follower 路徑
 *   （個別 detached 交付殘留類見 invalidate() KDoc：(i) pre-drain Ok、(ii) cache arm re-check 窗口、
 *   (iii) follower chain-face re-check 窗口）。
 * - `bytesKept`：`Ok.bytesKept`＝（若 gzip 解壓後之）實際位元組數；identity
 *   編碼時即 raw body 位元組數。
 * - 取消語義：外部取消（leader／呼叫方自身）原樣透傳 [CancellationException]，
 *   不映射、不寫快取、不 fallback（`fetchRawWithTimeout` 內五處 `ensureActive()` 守衛，與
 *   `WebHttp.fetchWithTimeout` 對稱）；synchronous 解析／清理／解壓迴圈（parseHtml／
 *   HtmlExtract／cleanField／sanitize／gunzipCapped 等）另以 **雙閘** 約束：deadline
 *   （[System.nanoTime] → [DeadlineExceededException]）＋`checking: () -> Unit`
 *   協程活性探針（production 為捕獲之協程 context 上的 `CoroutineContext.ensureActive()`，
 *   非 suspend 版、語義同 `currentCoroutineContext().ensureActive()`，拋
 *   [CancellationException]），兩閘於同一檢查點觸發，CancellationException 原樣上拋、
 *   不映射 TIMEOUT／TRANSPORT、不寫快取、不 fallback；leader 被取消而 follower 仍 active 者，
 *   follower 映射 typed `Unavailable(DETAIL_CANCELLED, null)`（never cached；
 *   follower 不得 out-fetch 已取消的 leader，不再進入單飛迴圈 refetch）；
 *   例外：leader 於取消前已完整產出 Ok（okForFollowers 路徑）者，follower
 *   收到該 Ok 而非 CANCELLED——但 Ok 仍須先通過下方之 chain-face 驗證及其後之
 *   post-validation epoch re-check 才交付。
 *   單飛 follower 之 chain-face（leader 路徑產出 Ok 後交付 follower 前）契約：
 *   follower await 回 Ok 後，**leader 記錄之 chain 須以 follower 自己的 opt-in／
 *   resolver／deadline 逐跳重驗**（規則與 fetch 相同：跨 origin opt-in 不可逆降級、
 *   https→http 連續對拒、per-hop policyGate＋sorted distinct token 相等；
 *   以 follower 剩餘預算為界，逾時視為驗證失敗；無 chain 或空 chain（pathological，should never
 *   happen）皆 fail-closed）；
 *   驗證失敗即 miss 語義：follower 不取得該 Ok、不寫快取、不 purge 快取，
 *   以有界重選舉（`bindingRestarts` ≤ [MAX_STALE_RESTARTS]）以自己的 binding 重 fetch；
 *   leader 內容不得跨越不符之驗證邊界；
 *   驗證成功則 leader 之 Ok verbatim 交付（不修改內容）。
 * - 內容：`title/url/snippet/published/siteName` 正規化（HTML-family 見 [parseHtml]：
 *   tag 剝除＋entity unescape；非 HTML-family 已放行型別走 raw-text 管線，
 *   不刪 tag、不 unescape，raw `&` 原樣保留，見 [pipelineOf]；raw-text 保留原文
 *   行結構與內部連續空白——`\n`／`\t` 保留、`\r` 為 Cc 故移除，其餘空白字元僅經
 *   NFKC 摺疊與首尾 trim，不做 \s+ → " " 收斂），全經 [WebContentEnvelope.cleanField]（`nfkc → strip → sanitize` 定點）清理；模型可見體為
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
 *   必須僅將 `envelope` 呈現予模型、於序列化 `content` 前 re-fence **整個 payload**，
 *   或直接丟棄這些欄位；不得把未 fence 欄位當作攜帶信任語意之內容。
 *   此為 D3 pre-wire acceptance gate（見 THREAT_MODEL／CAPABILITY_MATRIX D3 追蹤項）。
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
 * 1. cache key＝URL（**service 路徑**：`stripFragment()`＝OkHttp parse＋rebuild，
 *    於建 key **之前**先正規化 host case（小寫）／default port（依 scheme 去除）／
 *    fragment——fragment **一律去除**（至於 key）；percent-encoding 差異**不**正規化，
 *    仍會產生重複 key——只是浪費，非正確性問題。
 *    **public [WebExtractCache.key] helper**：trim 後照原樣（raw string as given），
 *    **不**正規化、**不**去 fragment——注入方責任提供已正規化 URL（例如經 [stripFragment]；
 *    見 [WebExtractCache.key] KDoc）；
 *    equivalent-URL 測試補強見 Phase C（host case／default port／fragment 三組 fixture））
 *    ＋`maxBytes`＋`maxModelChars`＋`policy`＋`configRevision`
 *    ＋**hop-0 已核准位址集合**（[sortedDistinctAddressTokens]：位元組去重＋
 *    （位元組數, `contentToString()`）排序＋`hostAddress` join；length-prefix 附於 key 尾端；
 *    round-robin 返回答案順序不影響 key）；不同首跳解析答案不互撞——
 *    resolver／endpoint 身份仍**刻意不入 key**，binding 的是當次決策之**結果**
 *    （已核准位址集合），per-call 政策重驗仍是真正的權威；同一 URL 以不同 resolver
 *    取用是允許的用法，呼叫方每次呼叫僅多付一次 policy preflight。
 *    **不含 port 維度之語義**（port 已內含於 URL 字串，無遺失，但 D3 須確認與政策側
 *    port 處理一致）。
 *    **chain-recording 綁定（Option B）**：cache entry 記錄每一跳之
 *    （url, 已核准位址 sorted token）鏈（[HopBinding]）；命中時逐跳重驗——
 *    每跳 `evaluateUrl` 必須 allowed、且當次解析之 token 必須與記錄一致；
 *    opt-in 降級走與 fetch 相同之 `downgradedOptInForRedirect` walker；
 *    任一跳不符／失敗即視為 miss、重打 upstream（`putByKey` 覆寫 entry），
 *    絕不回傳該 Ok；代價為每次 hit 額外 L×(DNS+policy) 驗證成本（L＝chain 長度）。
 *    **follower 面已具 redirect 逐跳 re-validation（#138，round 2）**：
 *    single-flight follower 收到 leader 產出之 Ok 時，會先以**自己的** opt-in／
 *    resolver／deadline 逐跳重驗 leader 記錄之 chain（規則與 fetch 相同：跨 origin
 *    opt-in 不可逆降級、https→http 連續對拒、per-hop policyGate＋sorted distinct
 *    token 相等；以 follower 剩餘預算為界）——驗證失敗即 miss 語義（follower 不收
 *    到該 Ok；以自己的 binding 有界重選舉，`bindingRestarts` ≤ [MAX_STALE_RESTARTS]，
 *    超過 fail-closed `Failed(TRANSPORT)`）；leader 內容不得跨越不符之驗證邊界。
 *    殘餘（誠實聲明，**非**「完整 redirect 隔離」已達成）：(a) awaited Ok 內容仍由
 *    leader 於其 hop-0 等效信任之 binding 下產出——成功之 follower 信任 hop-0 key
 *    綁定＋其自身之逐跳 re-validation 結果（同 URL、同解析答案者信任成立）；
 *    (b) Ok 交付窗口含 follower 之鏈重驗（L×(DNS+policy)）時段；`validateChain` 通過後之 epoch re-check 後完成之撤權仍可能落在 re-check→delivery 之靜默窗口內（與 cache arm 同一脫管交付殘留類），絕不經 cache 寫回；follower 重驗不改變 leader Ok 之內容。
 *    (c) 解析答案與 chain 相符之 follower 須付 L×(DNS+policy) 驗證成本——與 cache
 *    hit 同一成本類別；(d) 驗證僅涵蓋 delivery 前之窗口，不承諾對之後撤權之追溯
 *    失效（同 detached 交付殘留類）。
 * 2. cache **不解析 `Cache-Control`（含 `no-store`）**：TTL-only，屬刻意
 *    設計（read 工具、無寫副作用）；D3 須決定 honor 或明文件化此行為。
 * 3. `cleanField` 已由共享 15s deadline 限界（parse→clean 管線逐層傳入單調
 *    deadline，超過 → [DeadlineExceededException] → `Failed(TIMEOUT)`）——
 *    逐輪／每 512 codepoints 皆有檢查，最壞 O(n²) 定點迭代之上界現由 deadline
 *    實際兜底（不僅靠 input cap）；decode 與 regex 替換階段不在 deadline 內
 *    （受位元組／長度上限約束）；D3 仍可加 marker-count 前掃（pre-scan）進一步壓縮最壞路徑。
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
     * 端到端 deadline（decode 與 regex 替換階段除外，受位元組／長度上限約束）。
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
     * epoch-namespaced election 之有界重試上限：入口捕獲之 epoch 於 policy preflight 後、
     * election 臨界區內被判定漂移（期間發生過 `invalidate()`）時，重捕新世代並重走完整
     * policy／DNS cycle；超過此上限（3）即 fail-closed `Failed(TRANSPORT)`。
     * 初始嘗試 + 3 次重試＝最多 4 次 gate 評估。
     */
    internal const val MAX_STALE_RESTARTS = 3

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

    /** `rawUrl` 與 redirect `Location` 長度硬上限（UTF-16 code units）：超過即 [DETAIL_URL_TOO_LONG]。 */
    const val MAX_URL_CHARS = 8192

    /**
     * `rawUrl`（及 redirect 之 `Location` 解析結果）長度硬上限（[MAX_URL_CHARS] code units）：
     * 超過即 `Unavailable(DETAIL_URL_TOO_LONG, null)`，
     * 於任何 policy 評估或 cache key 建構之前 fail-closed 返回（不送請求、不查 DNS、不建 key）。
     * redirect hop 同理：`Location` 標頭原文過長、或 `resolveRedirect` 解析後之絕對 URL
     * 過長，一律於下一跳 DNS/HTTP/chain 處理之前 fail-closed（見 `extractUncachedInner`）。
     * 單位為 UTF-16 code units（`String.length`），與 `rawUrl.isBlank()` 同層次；
     * 本檢查**先於** `isBlank()`（O(1)，不讓超限空白字串先付 O(n) 掃描）。
     */
    const val DETAIL_URL_TOO_LONG = "URL_TOO_LONG"

    /**
     * 打包給 follower 的 typed「leader 已取消」detail（never cached，不寫快取；
     * follower 不得在 leader 取消後另起爐灶 refetch）。
     *
     * 語義：leader 之 cancellation 完成（completeExceptionally）後，follower
     * `await()` 丟出 CancellationException；若 follower 自身未被取消，
     * 映射為 `Unavailable(CANCELLED, null)` 而非重新進入單飛迴圈；
     * 若 leader 於取消前已完整產出 Ok（`okForFollowers` 路徑，complete(ok)），
     * follower 收到該 Ok 而非 CANCELLED（仍須先通過 follower 自己之
     * chain-face 逐跳重驗；見 D3 追蹤項 #1）。
     */
    const val DETAIL_CANCELLED = "CANCELLED"
}

/**
 * 正規化抽取內容（`title/url/snippet/published/siteName`，全已 sanitize）。
 *
 * ⚠ **`title`／`url`／`siteName`／`published` 不在 [WebContentEnvelope.BEGIN]／[END]
 * envelope fence 之內。**fence 僅涵蓋 [WebExtractOutcome.Ok.envelope]（即清理後 capped 之
 * `snippet`，truncated 時含 sentinel）。這些欄位只是 marker-free、Cf／Cc 已清理、
 * 硬上限截斷之 untrusted **structured values**——不攜帶任何信任語意。
 *
 * **D3 wiring 之強制規則**：模型可見的 projection 必須 re-fence **整個序列化 payload**
 * （把 `content` 全欄位置入 envelope 內），或直接丟棄 `title`／`url`／`siteName`／
 * `published`；**不得**將這些欄位呈現為 in-fence 內容，亦不得把未 fence 之欄位當作
 * 攜帶信任語意之內容送模型。此規則同時是 **D3 pre-wire acceptance gate**
 * （見 THREAT_MODEL／CAPABILITY_MATRIX D3 追蹤項）：未滿足本投影規則之 wiring 不得上線。
 */
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
         * `snippet`；`content` 之其餘欄位不在 fence 之內——D3 wiring 不得把它們
         * 呈現為 in-fence 內容；模型可見投影必須 re-fence 整個序列化 payload
         * 或丟棄這些欄位（見 [WebExtractContent] KDoc 與 header「fence 之範圍」）。
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
 *    `Map<String,String>` 不可表示重複，且本服務之 `headerLookup` 對重複鍵僅回傳
 *    **首個命中**（first-match-wins，見該函式 KDoc）——因此 **D3 OkHttp adapter 是
 *    重複 header 的唯一 backstop**，必須對具語義之 headers 採 fail-closed：
 *    `content-encoding`／`content-type`／`location`／`transfer-encoding` 任一出現
 *    重複（以 `getAll` 觀察）即拒絕整個 response；或 `getAll` 串接後視為多值並由
 *    呼叫方之多值規則裁決（`content-encoding` 多值已由 `extractUncached` 判
 *    `BAD_RESPONSE`；`location`／`content-type` 亦必須拒絕或拒絕該跳）。
 *    不得取首值放行。
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

/** 逐跳政策閘之結果（fail-closed）：允許（含 eval）或拒絕（outcome 已映射）。 */
private sealed interface GateResult {
    data class Allowed(val eval: NetworkEvaluation) : GateResult

    data class Denied(val outcome: WebExtractOutcome) : GateResult
}

/**
 * 逐跳政策閘（單一真相；extract() 首跳重驗、`extractUncachedInner` 逐跳、
 * cache hit chain-recording 逐跳重驗三處共用，避免三份邏輯漂移）。
 *
 * - DNS 需查則經有界 preflight（[NetworkPolicy.runPreflightSuspend]，
 *   與全程 deadline 共用預算）；純 pre-DNS 決策直接評估、不阻塞。
 * - 映射與既有行為一致：TimedOut→`Failed(TIMEOUT)`；OverloadRejected／任何
 *   例外→`Failed(TRANSPORT)`；denial→`Unavailable(denyCode name)`——逐跳政策
 *   拒絕（含 redirect hop）刻意以內層 denyCode 映射（保留內層碼供稽核），
 *   偏離 WebFetch 之 `REDIRECT_BLOCKED` 正規化；收斂列為 #138/D5 追蹤項。
 * - 政策評估（pre-parse）不產生 DeadlineExceededException；CancellationException 原樣透傳。
 */
private suspend fun policyGate(
    url: String,
    optIn: EndpointNetworkOptIn,
    resolver: HostResolver,
    deadlineNs: Long,
): GateResult {
    val gate = try {
        if (NetworkPolicy.requiresDnsLookup(url, optIn)) {
            when (
                val pre = NetworkPolicy.runPreflightSuspend(deadlineNs) {
                    NetworkPolicy.evaluateUrl(url, optIn, resolver)
                }
            ) {
                is NetworkPolicy.PreflightOutcome.Completed -> pre.value
                NetworkPolicy.PreflightOutcome.TimedOut ->
                    return GateResult.Denied(WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT))
                NetworkPolicy.PreflightOutcome.OverloadRejected ->
                    return GateResult.Denied(WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT))
            }
        } else {
            // 純 pre-DNS 決策（literal IP 或不需查 DNS）：直接評估，不阻塞。
            NetworkPolicy.evaluateUrl(url, optIn, resolver)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 政策評估（pre-parse）不產生 DeadlineExceededException；
        // evaluateUrl／resolver 之例外一律 fail-closed TRANSPORT。
        return GateResult.Denied(WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT))
    }
    if (!gate.allowed) {
        return GateResult.Denied(
            WebExtractOutcome.Unavailable(
                gate.denyCode?.name ?: NetworkDenyCode.INVALID_HOST.name,
                gate.denyCode,
            ),
        )
    }
    return GateResult.Allowed(gate)
}

/** entry 權重之固定開銷（Object header／map node／bookkeeping 之近似估算）。 */
private const val ENTRY_OVERHEAD_BYTES = 256L

/**
 * 鏈式信任上下文之單跳記錄（chain-recorded cache binding，#138 PR Phase 1 / Option B）：
 * 記錄該跳**實際被評估並建連**之絕對 URL（fragment 已去除）及當次解析並授權之
 * 位址集合（[sortedDistinctAddressTokens]：去重＋排序之 `hostAddress` 列表）。
 * 逐跳資訊使 cache 命中可逐跳重驗，關閉（僅對 cache 面）redirect 逐跳綁定殘留。
 * token 為 `hostAddress` 字串：PUBLIC／LOOPBACK／PRIVATE 答案之字元集僅 `0-9 a-f . :`（IPv4 dotted／IPv6 十六進位；無 `%`/`-`，見下）
 *（IPv4 dotted／IPv6 冒號 hex；`hostAddress` 本身不含 `-`——IPv6 scope id 之介面名（如 `eth0`）可能含字母／`-`，但 link-local scoped 答案一律不會通過政策閘）；含 zone id（scoped，如 `fe80::1%eth0`）之位址會攜帶 `%`，
 * 但 link-local scoped（區域連結）答案一律不會通過政策閘；縱使出現 `%`，
 * length-prefix 編碼不受影響。
 * 任何 token 皆不含 `\u0001`／控制字元，可安全 join 與 length-prefix。
 */
internal data class HopBinding(
    val url: String,
    val addresses: List<String>,
)

/** cache 命中之內部檢視（outcome 已正規化 `cached=true`；chain 供逐跳重驗）。 */
internal data class CacheHit(
    val outcome: WebExtractOutcome.Ok,
    val chain: List<HopBinding>,
)

/**
 * 解析後位址集合之正規化 token 列表：先以位元組 `contentToString()` 去重
 *（同一 seeder 之 round-robin 多次答案不重複計入），再依
 * （位元組數, `contentToString()`）排序（決定性全序；與解析順序無關），
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
 * - key＝service 路徑之 URL（`stripFragment()`＝OkHttp parse＋rebuild，於建 key 前
 *  已正規化 host case（小寫）／default port（依 scheme 去除）／fragment——fragment
 *  **一律去除**（至於 key）；percent-encoding 差異**不**正規化，仍會產生重複 key——
 *  只是浪費，非正確性問題）；**public [key] helper 語義不同**：trim 後照原樣
 *  （raw string as given），**不**正規化、**不**去 fragment——注入方責任提供已正規化
 *  URL（例如經 [stripFragment]；見 [key] KDoc 與 header「D3 追蹤項」#1））
 *  ＋`maxBytes`
 *  ＋`maxModelChars`＋policy identity（[EndpointNetworkOptIn.policy]＋
 *  [EndpointNetworkOptIn.configRevision]）
 *  ＋**hop-0 經當次重驗解析並授權之位址集合**（[addressToken]：先以位元組
 *  `contentToString()` 去重，再依（位元組數, `contentToString()`）排序，以 `hostAddress`
 *  join 為 `,`；length-prefix 附於 key 尾端）；不同預算／不同 tier／
 *  不同 revision／不同首跳解析答案絕不互撞；`round-robin` 返回答案順序不影響 key；
 *  key 不含 resolver／endpoint 身份（per-call 重驗才是真正的權威），hop-0 位址
 *  集合僅綁定當次決策之結果；redirect 逐跳之信任上下文由 entry 之
 *  [HopBinding] 鏈記錄並於命中時逐跳重驗（不符即 miss）——key 仍只綁 hop-0，
 *  chain 為 hit-path 之信任把關（見 header「D3 追蹤項」#1）。
 * - 只存 `Ok`（`Failed`／`Unavailable`／取消不寫）；寫入時 `cached` 正規化
 *   為 `false`，讀回時正規化為 `true`（快取內一律以 `cached=false` 儲存，
 *   讀出一律以 `cached=true` 呈現；[WebExtractOutcome.Ok.cached] 預設
 *   `false` 不變）。
 * - entry 攜帶**逐跳信任上下文鏈**（[HopBinding]：url＋該跳已核准位址集合之
 *   sorted token；空鏈＝internal scaffold `put()` 之條目，無從重驗、沿用舊命中語義）。
 *   命中時（[getHitByKey]）呼叫方（`WebExtractService`）必須逐跳重驗（攜帶 chain 之條目；
 *   空鏈 scaffold `put()` 條目無從重驗——point (a) 對之沿用舊命中語義，鎖內直接命中、零 DNS／policy）：
 *   每跳 `evaluateUrl` 必須 allowed、且當次解析之 token 必須與記錄一致；
 *   任一跳不符即視為 miss、重打 upstream 並覆寫 entry——**不得回傳該 Ok**。
 *   代價：每次 hit 額外 L×(DNS+policy) 驗證成本（L＝chain 長度）；見
 *   header「快取」與 header「D3 追蹤項」#1。
 * - 快取命中仍須 per-call 政策重驗（含 DNS preflight，fail-closed）：相同
 *  `policy`＋`configRevision` 但不同首跳解析答案不得重用結果——key 已綁 hop-0 位址集合，不同解析答案根本不會命中；
 *  redirect 逐跳之信任上下文由 entry 之 [HopBinding] 鏈逐跳重驗把關（不符即 miss），
 *  重驗失敗（denial／TIMEOUT）不讀取快取；重驗本身不 purge
 *   快取；授權撤銷僅能經 `invalidate()`／bump `configRevision` 生效；跨 tier
 *  （不同 `policy`／`configRevision`）內容永不共用。
 * - 時鐘可注入，TTL-only（不解析 `Cache-Control`）：TTL 到期 lazy 淘汰＋`put` 順手掃；有界 LRU（access-order，`LinkedHashMap(accessOrder=true)`），雙上限正交：
 *  筆數上限 [WebExtract.MAX_CACHE_ENTRIES]（200）＋位元組上限
 *  [WebExtract.MAX_CACHE_BYTES]（4 MiB；建構子可注入小值測試）。
 *  entry 權重（near-weighted）＝key／snippet／envelope／title／url／published／
 *  siteName 之長度×2＋**chain 各跳**（`url`＋`addresses` 各 token 長度）×2
 *  ＋固定 [ENTRY_OVERHEAD_BYTES]；`put` 時先 sweep expired
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

    private data class Entry(
        val outcome: WebExtractOutcome.Ok,
        val expiresAt: Long,
        val weight: Long,
        /** 逐跳信任上下文（url＋已核准位址 token）；空鏈＝internal scaffold `put()` 條目。 */
        val chain: List<HopBinding>,
    )

    /** 目前快取內 entry 權重之總和（bytes 近似；與 map.size 同鎖維護）。 */
    private var currentBytes = 0L

    private fun weightOf(k: String, o: WebExtractOutcome.Ok, chain: List<HopBinding>): Long {
        val c = o.content
        var w = ENTRY_OVERHEAD_BYTES
        w += k.length * 2L
        w += c.snippet.length * 2L
        w += o.envelope.length * 2L
        w += c.title.length * 2L
        w += c.url.length * 2L
        w += (c.published?.length ?: 0) * 2L
        w += (c.siteName?.length ?: 0) * 2L
        // chain 視為額外字元計入位元組預算（near-weighted LRU 記帳；保守膨脹）。
        for (hop in chain) {
            w += (hop.url.length + hop.addresses.sumOf { it.length }) * 2L
        }
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
     * 僅 supply [key] 使用；token 本身不含分隔符 `\u0001`（hostAddress 字元集僅 `0-9 a-f . :`，無 `\u0001` 及控制字元；`,` 為 join 分隔符，非 hostAddress 之字元），
     * 且以 length-prefix 附於 key 尾端，杜絕與 URL／budget 三域互混。
     */
    private fun addressToken(addresses: List<InetAddress>): String =
        sortedDistinctAddressTokens(addresses).joinToString(",")

    /**
     * cache key 組裝：URL 僅 trim、其餘照原樣（raw string as given）——**不**正規化、
     * **不**去 fragment；host case／default port／fragment 等等價差異會產生重複 key
     * （只是浪費，非正確性問題）。注入方責任提供已正規化 URL：service 路徑經
     * [stripFragment]（OkHttp parse＋rebuild）先正規化 host case（小寫）／default port
     * （依 scheme 去除）／去 fragment，再呼叫本函式——**fragment 一律去除**（至於 key）；
     * percent-encoding 差異**不**正規化；equivalent-URL 測試補強見 Phase C
     * （host case／default port／fragment 三組 fixture）。
     */
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

    internal fun get(
        url: String,
        maxBytes: Int,
        maxModelChars: Int,
        optIn: EndpointNetworkOptIn,
        addresses: List<InetAddress>,
    ): WebExtractOutcome.Ok? =
        getByKey(key(url, maxBytes, maxModelChars, optIn, addresses))

    // read-side epoch guard（共用之 lookup）：呼叫方傳入進入時捕獲之 epoch（per-call
    // 重驗後、cache 存取點 a／b 皆傳 epochAtEntry）；不符即視同 miss——invalidate 之後
    // 陳舊條目不得被讀回。internal get() 傳 null（語義不變：讀取當前有效條目）。
    private fun lookupEntry(k: String, generationCaptured: Long?): Entry? {
        synchronized(map) {
            if (generationCaptured != null && generation.get() != generationCaptured) return null
            val entry = map[k] ?: return null
            if (clockMs() >= entry.expiresAt) {
                map.remove(k)
                currentBytes -= entry.weight
                return null
            }
            return entry
        }
    }

    private fun outcomeOf(entry: Entry): WebExtractOutcome.Ok =
        entry.outcome.copy(cached = true, content = entry.outcome.content.copy())

    internal fun getByKey(k: String, generationCaptured: Long? = null): WebExtractOutcome.Ok? =
        lookupEntry(k, generationCaptured)?.let(::outcomeOf)

    /** 單一讀取路徑（避免 double-read 競態）：outcome＋chain 一次回傳。 */
    internal fun getHitByKey(k: String, generationCaptured: Long? = null): CacheHit? =
        lookupEntry(k, generationCaptured)?.let { CacheHit(outcomeOf(it), it.chain) }

    internal fun put(
        url: String,
        maxBytes: Int,
        maxModelChars: Int,
        optIn: EndpointNetworkOptIn,
        addresses: List<InetAddress>,
        outcome: WebExtractOutcome.Ok,
    ) {
        // internal scaffold put()（無 production caller）：以當下 generation 寫入
        // （呼叫方對陳舊寫入的自辨屬其自身責任；single-flight leader 路徑則傳入
        // extract() 入口捕獲之 epoch）。
        putByKey(key(url, maxBytes, maxModelChars, optIn, addresses), outcome, generation.get())
    }

    internal fun putByKey(
        k: String,
        outcome: WebExtractOutcome.Ok,
        generationCaptured: Long,
        chain: List<HopBinding> = emptyList(),
    ) {
        val now = clockMs()
        val expiresAt = now.coerceAtMost(Long.MAX_VALUE - ttlMs) + ttlMs
        val snapshot = outcome.copy(cached = false, content = outcome.content.copy())
        val weight = weightOf(k, snapshot, chain)
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
            map[k] = Entry(snapshot, expiresAt, weight, chain)
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
 *   ＋hop-0 已核准位址集合；redirect 逐跳之信任上下文由 entry 之
 *   [HopBinding] 鏈逐跳重驗（不符即 miss）；follower 於 Ok 交付前亦以自己的
 *   opt-in／resolver／deadline 逐跳重驗 leader 記錄之 chain（見 D3 追蹤項 #1）；
 *   快取 key 刻意不含 resolver／endpoint 身份；重驗失敗不讀取快取；
 *   命中前仍須 per-call 政策重驗（含 DNS preflight），不同首跳解析答案
 *   （同 policy＋revision 亦然）不得重用結果；重驗本身不 purge 快取；
 *   授權撤銷／政策變更後必須 `invalidate()`（或 bump `configRevision`）重綁，
 *   否則舊決策內容仍會命中。
 * - 同 key 併發合流（leader 打、follower `await()`；follower 取消不影響 leader）。
 * - `inFlight` map：無界，但受 per-key timeout 有界——leader 受全程 deadline
 *   （[WebExtract.TIMEOUT_MS]）約束，完成／失敗即出 map；與 `WebSearchService`
 *   同姿態（不為 inFlight 另設容量上限）；`invalidate()` 會 drain 所有在途
 *   entries（以 `CancellationException` 例外完成，follower 映射 `Unavailable(CANCELLED)`）。
 *   in-flight 條目以**世代（epoch）命名空間化**：entry 攜帶建立時之 cache
 *   generation；入口於 policy preflight **之後**、election 臨界區內重檢 generation，
 *   世代已變即不建立／不加入陳舊條目、改為有界重試（見 `extract()` KDoc 之
 *   epoch-namespaced election）；invalidate 之後的呼叫不可能加入 invalidate 之前
 *   建立的 entry。
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
     *   invalidate() 完成後，invalidate 前建立之條目任何 lookup 皆 miss（map 已清空＋epoch 已 bump；其後由新 leader Ok 寫入之條目為合法新命中）。
     * - 排空當前 in-flight entries：`inFlight` 內所有 pending leader 之
     *   `CompletableDeferred` 以 `CancellationException("web.extract invalidated")`
     *   完成（exceptionally）。已 detach 之 follower 經 `await()` 收到
     *   CancellationException → 映射為 `Unavailable(DETAIL_CANCELLED, null)`
     *   （never cached）；follower 自身的取消仍以 `ensureActive()` 優先透傳。
     * - invalidate() 之後之新呼叫走完整 policy／DNS cycle（含 cache 重驗），
     *   不得重用 invalidate 前之 cache 結果（read-side 世代守衛：陳舊條目
     *   直接 miss 並重打 upstream）；
     *   此外，[verifiedCachedHit] 之逐跳重驗完成、回傳 Ok 之前會再做一次
     *   generation epoch re-check——僅當 `invalidate()` 於該 re-check 執行之前
     *   已完成，hit 才被抑制；除下述 re-check→Ok 交付窗口外，
     *   validation 於 revoke 之前開始之 hit
     *   亦不得於撤權後藉 THIS 路徑落地（視為 miss，走 caller
     *   之 stale/restart 或 refetch 路徑）。惟 re-check 並非絕對：
     *   若撤權完成於 re-check 之後、`Ok` 交付 caller 之前那段
     *   無懸掛點（suspension-free）之窗口內，呼叫端仍可能收到
     *   post-revoke 之 Ok——此與 pre-drain Ok completion 同屬
     *   by-design detached 交付殘留類，除非將交付整段串行化入
     *   cache 監視（monitor）臨界區，否則無法完全關閉。
     * - 撤權後之 detached 交付殘留（by design；同一殘留類）：
     *   (i) 已 join 之 follower 於 drain 之前收到 leader 已完成之 Ok（pre-drain Ok completion）；
     *   (ii) [verifiedCachedHit] 之 post-validation epoch re-check 已通過、revocation
     *   於該 re-check 與交付 caller 之間之窗口內完成之 post-revoke Ok（見上條）；
     *   (iii) single-flight follower 之 chain-face 驗證：[validateChain] 與其後之 post-validation
     *   epoch re-check 皆已通過、revocation 於該 re-check 與交付 caller 之間之窗口內完成之
     *   post-revoke Ok（follower 交付路徑之同一窗口類，見上方 `InFlightEntry` KDoc／D3 追蹤項 #1；
     *   驗證時段含 L×(DNS+policy)，期間完成之撤權由該 re-check 攔截為 miss；僅 re-check→delivery
     *   為無懸掛點窗口）。
     *   三者皆發生於交付呼叫端之前的無懸掛點窗口，不經 cache 寫回、不再導致二次落地。
     *   in-flight 路徑依 detached 語義處理：已 join 之 follower 於 drain 前
     *   收到 leader 已完成之 Ok（leader 自身 caller 同理）；drain 時仍 pending
     *   者收到 `CancellationException("web.extract invalidated")` → 映射
     *   `Unavailable(DETAIL_CANCELLED, null)`（never cached）。
     * - leader 於 drain 之後完成者：其 `Ok` 仍正常返回給它自己的 caller，
     *   但 cache 寫入被 epoch guard 抑制；follower 因 `m.complete(outcome)`
     *   對已例外完成的 deferred 無效而只見 CancellationException。
     * - 附註（round 2 起）：detached Ok 交付給 follower 者，仍須先通過 follower
     *   自己之 chain-face 逐跳重驗及其後之 post-validation epoch re-check（見 `InFlightEntry` KDoc／D3 追蹤項 #1）；
     *   驗證失敗或世代漂移即 miss 語義，follower 不取得該 detached Ok。
     * - NonCancellable：整個撤銷流程（clear＋drain）包在 `withContext(NonCancellable)`
     *   內——呼叫端自身被取消不得中斷撤銷（半撤銷狀態是不可接受的）；
     *   drain 對所有 pending entries 例外完成，follower 映射 `Unavailable(CANCELLED)`。
     *
     * resolver／政策變更後必須 `invalidate()`（或 bump `configRevision`）重綁，
     * 否則舊解析之內容仍會命中（重驗無法區分 resolver/endpoint 身份）。
     */
    suspend fun invalidate() {
        // 撤銷不得被呼叫端之取消中斷：cache.clear()＋mutex drain 必須完整落地。
        withContext(NonCancellable) {
            cache.clear()
            mutex.withLock {
                val pending = inFlight.values.toList()
                inFlight.clear()
                pending.forEach { entry ->
                    entry.deferred.completeExceptionally(CancellationException("web.extract invalidated"))
                }
            }
        }
    }

    /**
     * in-flight 條目：以建立時之 cache generation（epoch）命名空間化。
     * election 臨界區內以 `entry.epoch == epochAtEntry` 判 join；世代不符之
     * 條目不得被 join，而是就地插入新世代條目（舊 leader 仍持有其 deferred
     * 完成給自己的 caller；僅 map 中的入口改掛新世代）。
     *
     * 單飛 follower 之 chain-face 契約：follower await 回 **Ok** 後，不得直接交付；
     * leader 記錄之 chain 須先以 follower 自己的 opt-in／resolver／deadline 逐跳重驗
     * （規則與 fetch 相同；以 follower 剩餘預算為界）；驗證失敗即 miss 語義——
     * follower 不取得該 Ok、不寫快取、不 purge 快取，以有界重選舉
     * （`bindingRestarts` ≤ [MAX_STALE_RESTARTS]）以自己的 binding 重 fetch；
     * 驗證成功則 leader 之 Ok verbatim 交付（不修改內容）——但交付前另有一道
     * post-validation epoch re-check（鏡射 [verifiedCachedHit]；撤權於 re-check 之前
     * 完成者抑制交付，視為 miss）。leader 內容不得跨越不符之驗證邊界。
     * mismatch 時 election 不得 join 該 deferred 之 `inFlight` 條目（`skipJoinForDeferred`），
     * 而由 caller 自己當 leader（自己的 binding 自行 fetch）。
     *
     * deferred 攜帶之值為 [LeaderOutcome]（結果＋leader 記錄之 chain）——chain 經由
     * deferred completion edge 跨協程傳遞（happens-before 由 `CompletableDeferred.complete`
     * 保證）：follower await 回 Ok 後即見 leader 發布之 chain，並以自己的 binding 重驗。
     */
    private data class InFlightEntry(val epoch: Long, val deferred: CompletableDeferred<LeaderOutcome>)

    /**
     * leader 交付給 follower 之載體：outcome ＋ leader 記錄之逐跳
     * 信任上下文 chain。經 deferred completion edge 同步給 follower；follower 於 Ok
     * 交付前執行 [validateChain]（規則與 fetch 相同；以 follower 自己的 opt-in／resolver／
     * deadline 為準）。僅 Ok 路徑可能非空；非 Ok 為 emptyList()，follower 不讀取（僅 is Ok 分支驗證）。
     */
    private data class LeaderOutcome(val outcome: WebExtractOutcome, val chain: List<HopBinding>?)

    // 載體語義：null＝follower 判定之 pathology（Ok 無 chain）→ follower 端 fail-closed（TRANSPORT）；
    // 空 list＝非 Ok 載體，或 scaffold 語義（僅 internal put() 產生，本 arm 不交付）；follower Ok arm 兩者皆 fail-closed。

    private val mutex = Mutex()
    private val inFlight: MutableMap<String, InFlightEntry> = mutableMapOf()

    /**
     * 單 URL 抽取（cache＋single-flight＋逐跳政策）。
     *
     * Blocking（production 接線前必解）：`resolver` 無預設值；production
     * 呼叫方必須顯式注入 pinning resolver（[dev.librepocket.policy.PinnedNetworkDns]），
     * 禁止直連；`SystemHostResolver` 僅供純 JVM fixture 測試。
     * OkHttp adapter 須讓 `resolvedAddresses` 釘住建連，rebind 視新決策重驗
     * （S2 契約）。
     *
     * Epoch-namespaced election（#138 Phase 1 / #2 revocation race 修復）：
     * election（`mutex` 臨界區）之**第一**動作是以入口捕獲之 `epochAtEntry`
     * 重讀 cache 世代；世代已變（期間發生過 `invalidate()`）即不建立／不加入
     * 條目，改為**有界重試**——`staleRestarts` 每次重試 +1，超過 3 次即
     * fail-closed `Failed(TRANSPORT)`（絕不 fallback 為陳舊內容、絕不無界重試）；
     * 重試時以當下世代重捕 `epochAtEntry` 並重新走完整 policy／DNS cycle
     * （含 key 重算）。入口（epochAtEntry 捕獲點）至 election 之間被
     * invalidate 的請求因此**不會**在 invalidate 之後建立陳舊世代的 inFlight 條目。
     * follower 映射語義不變：leader drain 時 pending 者見
     * `Unavailable(CANCELLED)`；leader 於 drain 前已完整產出 Ok 者 follower 照收
     * （但 Ok 仍須先通過 follower 自己之 chain-face 逐跳重驗才交付——見上方
     * `InFlightEntry` KDoc 與 D3 追蹤項 #1）。
     * election 另有一個 follower 面例外：`skipJoinForDeferred === 該條目之 deferred` 時不 join 該
     * in-flight 條目（先前之 Ok 未通過本 follower 之 chain-face 驗證），呼叫方就地
     * 插入新世代條目並自己當 leader；與世代漂移之有界重試正交（後者另計
     * `staleRestarts`）。
     * follower Ok 交付前亦有一道 post-validation epoch re-check（鏡射 [verifiedCachedHit]）：
     * 重驗通過後、交付前若世代已漂移，視為 miss（同驗證失敗之有界重選舉），不交付。
     */
    suspend fun extract(
        rawUrl: String,
        optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
        resolver: HostResolver,
        maxBytes: Int = WebExtract.DEFAULT_MAX_BYTES,
        maxModelChars: Int = WebExtract.DEFAULT_MODEL_CHARS,
    ): WebExtractOutcome {
        // URL 長度硬上限先於 blank 檢查：長度為 O(1)，超限即 fail-closed
        //（不做任何 policy 評估／key 建構／DNS），並避免超長全空白字串先被 isBlank() 全掃。
        if (rawUrl.length > WebExtract.MAX_URL_CHARS) {
            return WebExtractOutcome.Unavailable(WebExtract.DETAIL_URL_TOO_LONG, null)
        }
        if (rawUrl.isBlank()) {
            return WebExtractOutcome.Unavailable(WebExtract.DETAIL_EMPTY_URL, null)
        }
        val effBytes = maxBytes.coerceIn(1, WebExtract.ABSOLUTE_MAX_BYTES)
        val effModel = maxModelChars.coerceIn(1, WebExtract.MAX_MODEL_CHARS)
        // 全程單一單調 deadline：per-call 政策重驗（含 DNS preflight）＋
        // `extractUncached`（DNS preflight＋逐跳 HTTP＋解壓）共用此單一單調預算。
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        // Cooperative cancellation：synchronous 解析／清理／解壓階段的迴圈以同一探針
        // 檢查本協程活性（`CoroutineContext.ensureActive()`，非 suspend 版，與檔內
        // `currentCoroutineContext().ensureActive()` 語義一致）；呼叫方取消即拋
        // CancellationException（原樣上拋，不映射 TIMEOUT／TRANSPORT、不寫快取）。
        // 全程僅建立一次，傳給屬本協程的所有下游階段（extractUncached → parseHtml／gunzip／cleanField／wrap 等）。
        val callingContext = currentCoroutineContext()
        val checking: () -> Unit = { callingContext.ensureActive() }
        // 入口捕獲 cache generation epoch：leader 完成時若 epoch 已變
        //（期間發生過 invalidate()/clear()），抑制本次 cache 寫入
        //（caller 自身仍正常拿到 outcome；僅 cache write 被抑制）。
        // 有界重試：epoch-namespaced election 偵測到世代漂移時重捕重試；
        // 超過 MAX_STALE_RESTARTS（3）次即 fail-closed Failed(TRANSPORT)。
        var epochAtEntry = cache.currentGeneration
        var staleRestarts = 0
        var bindingRestarts = 0
        // A2：follower 驗證失敗／epoch-drift 後 skipJoin 之對象——election 見此 deferred
        // 即不 join 該 in-flight 條目（僅 identity 相等時），改為就地插入新條目
        //（自己當 leader）。以 deferred identity 為範圍而非 key，同 key 之後續 leader 不受誤傷。
        var skipJoinForDeferred: CompletableDeferred<LeaderOutcome>? = null
        val hop0 = stripFragment(rawUrl.trim())
        while (true) {
            // per-call 政策重驗（含 DNS preflight），fail-closed；必須在 cache／
            // inFlight 存取之前完成——point (a)（鎖外）與 follower join 兩個
            // 返回點皆位於此檢查之後；通過後才觸 cache。鎖內（election 臨界區）
            // 不再評估 policy／DNS（見 verifiedCachedHit 之 KDoc），僅做廉價的
            // epoch-checked getHitByKey 讀取與 stale/restart election。
            // 本閘之決策會沿 [extractUncached] 傳入（`hop0Eval`）並於
            // `extractUncachedInner` 首跳直接沿用（不二次 resolve；key 綁定之決策與
            // 建連所用之決策為同一份——保證 key binding == fetched binding）；
            // redirect 逐跳仍全量過閘。
            val hop0Gate = when (val g = policyGate(hop0, optIn, resolver, deadlineNs)) {
                is GateResult.Allowed -> g
                is GateResult.Denied -> return g.outcome
            }
            val gate = hop0Gate.eval
            // hop-0 綁定：key 納入當次重驗所解析並授權之位址集合（與閘共用同一份
            // eval，不二次查 DNS）；sorted set 語義（去重＋排序），round-robin
            // 返回答案順序不影響 key identity。
            val k = cache.key(hop0, effBytes, effModel, optIn, gate.resolvedAddresses)
            verifiedCachedHit(k, epochAtEntry, optIn, resolver, deadlineNs)?.let { return it }

            var lateHit: WebExtractOutcome.Ok? = null
            var mine: CompletableDeferred<LeaderOutcome>? = null
            var leader = false
            var stale = false
            mutex.withLock {
                // epoch-namespaced election：先以入口捕獲之 epoch 重讀 cache 世代。
                // 世代已變 → 不建立／不加入任何條目（留在鎖內、零寫入），
                // 由鎖外以新 epoch 有界重試。此檢查與 entry 插入同一臨界區，
                // invalidate() 的 clear()＋drain 亦在同一世代命名空間下原子生效。
                //
                // 鎖內只做廉價的 epoch-checked [WebExtractCache.getHitByKey]
                //（無 DNS／policy，不掛起、不佔住全域 mutex）：
                //   - generation 漂移 → stale（有界重試）；
                //   - point (a)（鎖外）已對攜帶 chain 之條目完成逐跳重驗——
                //     失敗（absent／token mismatch／policy denial／TIMEOUT）者
                //     視為不可信，直接走 leader／join election（refetch 覆寫
                //     entry），**絕不於鎖內重跑 DNS／policy**；
                //   - 僅 internal scaffold `put()` 之空鏈條目可於鎖內直接命中
                //     （與 point (a) 對空鏈條目的語義一致）。
                if (cache.currentGeneration != epochAtEntry) {
                    stale = true
                } else {
                    lateHit = cache.getHitByKey(k, epochAtEntry)
                        ?.takeIf { it.chain.isEmpty() }
                        ?.outcome
                    // post-read epoch re-check：getHitByKey 與此處之間無懸掛點，但
                    // 防禦性地鏡像 verifiedCachedHit 之 re-check——世代已漂移即視為 miss（stale 路徑）。
                    if (lateHit != null && cache.currentGeneration != epochAtEntry) {
                        lateHit = null
                    }
                    if (lateHit == null) {
                        // follower chain-face 驗證失敗後：skipJoinForDeferred === 該條目之 deferred
                        // 表示該 leader（內容）已被本 caller 判定不可信——不得 join，
                        // 改為就地插入新世代條目，由本 caller 以自己的 binding 當 leader
                        //（其 fetch 將自行逐跳過閘，通過即交付自己的結果）。
                        val entry = inFlight[k]
                        val existing = if (entry != null && entry.deferred === skipJoinForDeferred) null else entry
                        if (existing != null && existing.epoch == epochAtEntry) {
                            mine = existing.deferred
                        } else {
                            // 無條目，或條目世代不符（例如共享 cache 被其他 service
                            // clear 而本 service 未 drain）→ 插入當前世代的新條目；
                            // 陳舊世代條目不得被 join。
                            val fresh = CompletableDeferred<LeaderOutcome>()
                            mine = fresh
                            inFlight[k] = InFlightEntry(epochAtEntry, fresh)
                            leader = true
                        }
                    }
                }
            }
            if (stale) {
                // election 被 invalidate 競態打敗：放棄本輪（不寫 cache、不建立
                // 條目），以新世代重走完整 policy／DNS cycle；超過上限即
                // fail-closed TRANSPORT。
                staleRestarts += 1
                if (staleRestarts > WebExtract.MAX_STALE_RESTARTS) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                }
                epochAtEntry = cache.currentGeneration
                // 世代已漂移＝全新 election 域（invalidate 已 drain inFlight）；
                // skipJoinForDeferred 之針對性失效，清除以免誤傷新世代 leader 之 join。
                skipJoinForDeferred = null
                continue
            }
            lateHit?.let { return it }
            val m = mine ?: error("extract() invariant violated: no in-flight entry elected")
            if (!leader) {
                // per-follower deadline：以整體預算為界包住 await；
                // 逾時映射 Failed(TIMEOUT)（與 CANCELLED 的 audit 語義區分）；
                // follower 不得在 leader 卡於 parse／gunzip 時無界等待。
                val waitMs = ((deadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                val awaited = try {
                    withTimeoutOrNull(waitMs) { m.await() }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // 自身被取消：ensureActive 拋出（self-cancellation）。
                    // 否則為 leader 取消：follower 映射 typed Unavailable(CANCELLED)
                    // 並返回，不再進入單飛迴圈（never cached，不得 out-fetch a
                    // cancelled leader）。
                    // 例外：若 leader 於取消前已完整產出 Ok（okForFollowers 路徑，
                    // complete(ok) 而非 completeExceptionally），follower 收到該 Ok
                    // 而非 CANCELLED——`await()` 會直接回傳已落定的結果（仍須通過下方
                    // 之 chain 驗證才交付）。
                    return WebExtractOutcome.Unavailable(WebExtract.DETAIL_CANCELLED, null)
                } catch (e: Error) {
                    // Error（OOM/SOE 等致命錯誤）原樣上拋，不映射為 TRANSPORT、不吞噬；
                    // 與 leader 路徑（catch Throwable → completeExceptionally(e) → 上拋）對稱。
                    throw e
                } catch (_: Throwable) {
                    // m.await() 不執行解析階段，DeadlineExceededException 不會由此逸出；
                    // leader 異常貫穿時 fail-closed TRANSPORT。
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                } ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_TIMEOUT)
                // awaited 為 LeaderOutcome（經 deferred completion edge 同步之載體）。
                if (awaited.outcome !is WebExtractOutcome.Ok) {
                    return awaited.outcome
                }
                // Ok 之 chain-face 驗證：leader 記錄之 chain 須以 **follower 自己的**
                // opt-in/resolver/deadline 逐跳重驗（規則與 fetch 相同：跨 origin opt-in
                // 不可逆降級、https→http 連續對拒、per-hop policyGate＋sorted distinct token
                // 相等）。以 follower 之剩餘預算（waitMs 語義）為界；逾時＝驗證失敗。
                // 驗證失敗絕不交付 leader 之 Ok；leader 內容不得跨越不符之驗證邊界。
                val leaderChain = awaited.chain
                    // Pathological：Ok 卻無 chain（或 leader 僅發布 scaffold 空 chain）→ fail-closed。
                    ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                // Follower Ok 之 chain 必須非空——real fetch 之 leader Ok 一律攜帶 ≥1 HopBinding；
                // 空 chain 僅 internal scaffold `put()` 產生且本 arm 不得借路交付未驗證之內容。
                if (leaderChain.isEmpty()) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                }
                val validateWaitMs = ((deadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                val validated = try {
                    withTimeoutOrNull(validateWaitMs) {
                        validateChain(leaderChain, optIn, resolver, deadlineNs)
                    } == true
                } catch (_: DeadlineExceededException) {
                    // Defense-in-depth：policyGate 從不產生此例外（此例外僅源自
                    // gunzip／parse／cleanup 之 deadline 耗盡），此臂為 belt-and-braces——
                    // 保留不得刪除：一旦未來出現新的 throw site，刪除此臂會使驗證失敗
                    // 坍縮為 Error 穿透路徑，喪失 miss 語義（fail-closed 於 TRANSPORT）。
                    false
                }
                if (validated) {
                    // Post-validation epoch re-check（鏡射 verifiedCachedHit 之 post-validation re-check）：
                    // 撤權（invalidate）若於本 re-check 執行之前完成，則該撤權抑制交付——
                    // Ok 不得落地（revoke completing before the re-check suppresses delivery）。
                    // 世代漂移＝miss 語義（同驗證失敗之有界重選舉）。re-check→delivery 之間
                    // 無懸掛點，期間完成之撤權無法攔截（與 cache arm 同一脫管交付殘留類，
                    // 見 invalidate() KDoc）。
                    if (cache.currentGeneration != epochAtEntry) {
                        // 註：此 re-check→`continue` 後的下一圈 stale arm 會重捕世代並清 skipJoinForDeferred，
                        // 故本臂之 skipJoinForDeferred 於漂移輪倖免於誤傷、於下一圈被清——刻意如此（兩臂耦合說明）。
                        bindingRestarts += 1
                        if (bindingRestarts > WebExtract.MAX_STALE_RESTARTS) {
                            return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                        }
                        skipJoinForDeferred = m
                        continue
                    }
                    // 交付 leader 之 Ok verbatim（不修改內容、不寫快取）。
                    return awaited.outcome
                }
                // 驗證失敗 → miss 語義（與 cache 路徑一致）：有界重選舉——
                // `bindingRestarts` 超限即 fail-closed TRANSPORT；否則設 skipJoinForDeferred
                // 使 caller 不再 join 該 deferred 之（遭竄改／不符之）leader，改為自己 fetch。
                bindingRestarts += 1
                if (bindingRestarts > WebExtract.MAX_STALE_RESTARTS) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                }
                skipJoinForDeferred = m
                continue
            }

            var okForFollowers: WebExtractOutcome.Ok? = null
            // Option B：extractUncached 以 collector 交回逐跳信任上下文（非 invasive：
            // Ok 仍為公開契約，chain 經內部 channel 傳回）。
            var chain = emptyList<HopBinding>()
            try {
                val outcome = extractUncached(rawUrl, optIn, resolver, effBytes, effModel, deadlineNs, checking, hop0Eval = hop0Gate) { chain = it }
                if (outcome is WebExtractOutcome.Ok) okForFollowers = outcome
                currentCoroutineContext().ensureActive()
                if (outcome is WebExtractOutcome.Ok && cache.currentGeneration == epochAtEntry) {
                    // epoch guard：extract() 入口後發生過 invalidate()/clear()，
                    // 陳舊結果不得寫回快取（m.isCompleted 情形亦由本檢查涵蓋——
                    // drain 會 bump generation；leader 仍正常返回 outcome 予其 caller）。
                    // pre-cache-write gate：預算已耗盡則跳過寫入
                    //（caller 仍正常拿到 outcome；僅 cache 寫入被抑制）。
                    if (System.nanoTime() < deadlineNs) {
                        cache.putByKey(k, outcome, epochAtEntry, chain)
                    }
                }
                // follower 之 chain-face 驗證所需：leader 完成前（Ok cache-write 區塊
                // 之後、無論是否寫入）發布本跳之 chain——以 LeaderOutcome 載體經 deferred
                // completion edge 跨協程傳遞（happens-before 保證）。
                m.complete(LeaderOutcome(outcome, chain))
                return outcome
            } catch (e: CancellationException) {
                val ok = okForFollowers
                if (ok != null) {
                    // Ok 已完整產出（chainSink 已執行）——發布 chain 供 follower 之
                    // chain-face 驗證（同 m.complete(outcome) 路徑）。
                    m.complete(LeaderOutcome(ok, chain))
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
                m.complete(LeaderOutcome(failed, emptyList()))
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
                m.complete(LeaderOutcome(failed, emptyList()))
                return failed
            } catch (e: Throwable) {
                // DeadlineExceededException 屬 Exception，已於上方 arm 攔截
                //（不會到達此 arm）。
                m.completeExceptionally(e)
                throw e
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight[k]?.deferred === m) inFlight.remove(k)
                    }
                }
            }
        }
    }

    /**
     * chain 逐跳驗證之單跳核心：以 [policyGate]（含有界 DNS preflight，以全程 deadline
     * 為界）重評估該跳；必須 allowed，且當次解析之 sorted distinct hostAddress token
     * 必須與 [HopBinding.addresses] 相等。任一不符 → false（fail-closed）。
     *
     * 本函式與 [validateChain] 為 cache hit chain arm（[verifiedCachedHit]）與
     * single-flight follower 之 Ok 交付前 chain-face 驗證**共用之同一份實作**——
     * 兩處不得各自 fork 第二份邏輯。
     */
    private suspend fun validateChainStep(
        hop: HopBinding,
        effectiveOptIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        deadlineNs: Long,
    ): Boolean {
        val eval = when (val g = policyGate(hop.url, effectiveOptIn, resolver, deadlineNs)) {
            is GateResult.Allowed -> g.eval
            is GateResult.Denied -> return false
        }
        return sortedDistinctAddressTokens(eval.resolvedAddresses) == hop.addresses
    }

    /**
     * chain 逐跳重驗之共用行走核心（[verifiedCachedHit] 之 chain arm 與 single-flight
     * follower 之 Ok 交付前 chain-face 驗證共用；不得 fork 第二份）。依序走每一跳：
     * 1. 連續 https→http 對拒（defense-in-depth；記錄鏈即便遭竄改／注入此類降級對，
     *    一律視為不符——fetch 路徑永遠不會發出該明文請求）；
     * 2. 以與 fetch 相同之不可逆降級規則（[downgradedOptInForRedirect]；跨 origin →
     *    預設 `HTTPS_ONLY` 姿態）重走 opt-in 降級；scheme 一律以
     *    `lowercase(Locale.ROOT)` 比對（與 fetch 逐跳判定一致）；
     * 3. 每跳 [validateChainStep]（policyGate 必須 allowed＋token 相等）。
     * 任一跳不符／失敗 → false；全數通過 → true。空 chain → true（無從驗證，沿用
     * 舊命中語義）。重驗本身不寫快取、不 purge 快取。
     * binding-restart 輪次會重付 L×(DNS+policy) 之重驗成本（相同位址集合——含答案順序位移——經
     * 排序去重 token 恆等（gate 仍 allowed 前提下）恆通過；集合變更才重驗失敗，故不消除；以 bindingRestarts 上界兜底）。
     */
    private suspend fun validateChain(
        chain: List<HopBinding>,
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        deadlineNs: Long,
    ): Boolean {
        // 空 chain arm 僅為 internal scaffold `put()` 之防禦——來自真實 fetch 之 Ok 之
        // chain 必為非空（chainSink 於 Ok 路徑必設值）；空鏈條目無從驗證，沿用舊命中語義
        //（defense note，security review）。
        var effectiveOptIn = optIn
        for ((index, hop) in chain.withIndex()) {
            if (index > 0) {
                val prevScheme = chain[index - 1].url.toHttpUrlOrNull()?.scheme?.lowercase(Locale.ROOT)
                val curScheme = hop.url.toHttpUrlOrNull()?.scheme?.lowercase(Locale.ROOT)
                if (prevScheme == "https" && curScheme == "http") return false
                effectiveOptIn = downgradedOptInForRedirect(chain[index - 1].url, hop.url, effectiveOptIn)
            }
            if (!validateChainStep(hop, effectiveOptIn, resolver, deadlineNs)) return false
        }
        return true
    }

    /**
     * Cache hit 之 chain-recording 逐跳重驗（Option B；關閉 cache 面之 redirect 逐跳綁定殘留）。
     *
     * entry 攜帶 chain 時，依序逐跳重驗：
     * 1. 以與 fetch 相同之 downgradedOptInForRedirect 規則重走 opt-in 降級（跨 origin 不可逆）；
     * 2. 每跳 policyGate（含有界 DNS preflight，以全程 deadline 為界）必須 allowed；
     * 3. 當次解析之 sorted distinct hostAddress token 必須與記錄一致。
     * 任一跳不符／失敗 → 視為 cache miss（呼叫方重打 upstream；`putByKey` 覆寫 entry），
     * **絕不回傳該 Ok**。空 chain 之條目（internal scaffold `put()`）無從驗證、沿用舊命中語義。
     *
     * 代價：每次 hit 額外付 L×(DNS+policy) 驗證成本（L＝chain 長度）——與 fetch 相同量級之
     * per-call 重驗合流執行；重驗本身不寫快取、不 purge 快取。本函式**僅於 point (a)
     *（鎖外）呼叫**——鎖內（election 臨界區）不得評估 policy／DNS，僅做廉價的
     * epoch-checked `getHitByKey` 讀取（空鏈條目直接命中；point (a) 已判失敗之攜帶 chain
     * 條目一律走 leader／join election，fetch 結果覆寫 entry（Ok 且世代未漂移時））。重驗完成、回傳 `Ok` 之前會
     * 再做一次 generation epoch re-check——僅當 `invalidate()` 於該 re-check 執行之前
     * 已完成，本次 hit 才視為 miss（由 caller 之 stale/restart 或 refetch 路徑處理，
     * 同 [WebExtractCache.putByKey] 之內部 guard 模式）。此為必要而非充分條件：
     * 若撤權完成於 re-check 之後、`Ok` 交付 caller 之前那段無懸掛點（suspension-free）
     * 之窗口內，呼叫端仍可能收到 post-revoke 之 Ok——與 pre-drain Ok completion
     * 同屬刻意保留之 detached 交付殘留類，除非將交付整段串行化入 cache 監視（monitor）
     * 臨界區，否則無法完全關閉。
     */
    private suspend fun verifiedCachedHit(
        k: String,
        epochAtEntry: Long,
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        deadlineNs: Long,
    ): WebExtractOutcome.Ok? {
        val hit = cache.getHitByKey(k, epochAtEntry) ?: return null
        val chain = hit.chain
        if (chain.isEmpty()) {
            return if (cache.currentGeneration == epochAtEntry) hit.outcome else null
        }
        if (!validateChain(chain, optIn, resolver, deadlineNs)) return null
        // Post-validation epoch re-check：重驗（DNS／policy）期間發生、且已於本
        // re-check 執行之前完成之 invalidate()（clear 與 bump 世代同一臨界區）時，
        // 本次 hit 不得交付——視為 miss，由 caller 之 stale/restart election 或
        // refetch 處理（與 [WebExtractCache.putByKey] 之內部 guard 同一模式）。
        // 注意：re-check 與交付 caller 之間無懸掛點，期間完成之撤權無法被本檢查
        // 攔截——post-revoke Ok 可至 caller（by-design detached 交付殘留，見 invalidate() KDoc）。
        if (cache.currentGeneration != epochAtEntry) return null
        return hit.outcome
    }

    private suspend fun extractUncached(
        rawUrl: String,
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        effBytes: Int,
        effModel: Int,
        deadlineNs: Long,
        checking: () -> Unit = {},
        hop0Eval: GateResult.Allowed? = null,
        chainSink: ((List<HopBinding>) -> Unit)? = null,
    ): WebExtractOutcome =
        // 任何 DeadlineExceededException（gunzip／parse／cleanup 之 deadline 耗盡）
        // → 與預算耗盡同 detail：Failed(TIMEOUT)；never Ok、never cached。
        // CancellationException（協程取消，含 checking 探針拋出）刻意不攔截：原樣上拋。
        try {
            extractUncachedInner(rawUrl, optIn, resolver, effBytes, effModel, deadlineNs, checking, hop0Eval, chainSink)
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
        checking: () -> Unit = {},
        hop0Eval: GateResult.Allowed? = null,
        chainSink: ((List<HopBinding>) -> Unit)? = null,
    ): WebExtractOutcome {
        // 全程單一單調 deadline（由 extract() 入口計算並傳入）：DNS preflight
        // ＋所有 redirect hop＋解壓共用；與 per-call 政策重驗共享同一預算。
        var current = stripFragment(rawUrl.trim())
        // 鏈式信任上下文記錄（Option B：僅 cache 面綁定 redirect 逐跳）：
        // 每一跳（含首跳與 redirect hops）policy 放行後記錄 (url, sorted distinct token)；
        // Ok 時隨 chainSink 回傳給 extract()；非 Ok 路徑不觸 chainSink。
        val chain = mutableListOf<HopBinding>()
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
            // 首跳：沿用 extract() 傳入之 hop-0 決策（`hop0Eval`；key 建構與本處 fetch 共用
            // 同一份已授權之決策——key binding == fetched binding）；不再二次 resolve。
            // redirect 逐跳永遠全量過閘。`first` 於下方設為 false 後不再沿用 hop0Eval。
            val eval = if (first && hop0Eval != null) {
                hop0Eval.eval
            } else when (val g = policyGate(current, effectiveOptIn, resolver, deadlineNs)) {
                is GateResult.Allowed -> g.eval
                is GateResult.Denied -> return g.outcome
            }
            // 政策評估可能涉及掛起的 DNS preflight；取消訊號必須在此處即生效，
            // 且不得觸及 normalized 計算與任何建連。
            currentCoroutineContext().ensureActive()
            val normalized = stripFragment(
                eval.normalizedUrl ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT),
            )
            // Option B：記錄本跳之信任上下文。HopBinding.url 必須為該跳**實際建連**之
            // URL（eval.normalizedUrl 去 fragment＝`normalized`），而非 pre-normalization 之 `current`。
            chain.add(HopBinding(normalized, sortedDistinctAddressTokens(eval.resolvedAddresses)))
            // https → http 降級永遠拒（含迴環，fail-closed），絕不發出明文請求。
            val curScheme = try {
                normalized.toHttpUrlOrNull()?.scheme?.lowercase(Locale.ROOT)
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
                // Location 之 URL 長度硬上限（與入口 `rawUrl` 相同上限）：
                // `Location` 原文或解析後之絕對 URL 過長，一律於下一跳 DNS／HTTP／chain
                // 處理之前 fail-closed（不查 DNS、不建 HopBinding、不寫快取），
                // 與入口 `MAX_URL_CHARS` 檢查同一 fail-closed 姿態。
                if (location.length > WebExtract.MAX_URL_CHARS) {
                    return WebExtractOutcome.Unavailable(WebExtract.DETAIL_URL_TOO_LONG, null)
                }
                val resolved = resolveRedirect(normalized, location)
                    ?.let { stripFragment(it) }
                    ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_REDIRECT)
                if (resolved.length > WebExtract.MAX_URL_CHARS) {
                    return WebExtractOutcome.Unavailable(WebExtract.DETAIL_URL_TOO_LONG, null)
                }
                // 跨 origin 跳轉：opt-in 不可逆降級為預設 `HTTPS_ONLY` 姿態（公網 https 仍允許；
                // 私有／明文一律拒）；下一跳政策以降級後
                // opt-in 評估（不得恢復已降級權限；同 origin 維持目前有效 opt-in）。
                effectiveOptIn = downgradedOptInForRedirect(normalized, resolved, effectiveOptIn)
                // 預檢 downgrade（未發請求前擋；下一圈政策再驗一次，雙保險）。
                val fromHttps = normalized.toHttpUrlOrNull()?.scheme?.lowercase(Locale.ROOT) == "https"
                val toHttp = resolved.toHttpUrlOrNull()?.scheme?.lowercase(Locale.ROOT) == "http"
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

            if (response.code == 206) {
                // RFC 9110 §15.3.7：206 Partial Content 為分段表示；本抽取器不發 Range、
                // 不組裝分段——一律 fail-closed（Failed 絕不寫快取、絕不交付完整 Ok）。
                return WebExtractOutcome.Failed("HTTP_206")
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
                when (val dec = gunzipCapped(rawBytes, effBytes, deadlineNs, checking)) {
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
            // raw-text snippet 保留原文之行結構與內部連續空白：僅經
            // [WebContentEnvelope.cleanField]（去 Cf／（\n／\t 以外之）Cc／EXTRA_STRIP，
            // `\r` 為 Cc 故移除；NFKC 摺疊）再首尾 trim——不做 \s+ → " " 收斂。
            val parsed = when (pipelineOf(contentType)) {
                Pipeline.Html -> parseHtml(decoded, normalized, deadlineNs, checking)
                Pipeline.RawText -> {
                    val snippet = WebContentEnvelope.cleanField(decoded, deadlineNs, checking).trim()
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
            val cleanSnippet = WebContentEnvelope.cleanField(parsed.content.snippet, deadlineNs, checking)
            val cleanTitle = WebContentEnvelope.cleanField(parsed.content.title, deadlineNs, checking).takeExtractPrefix(500)
            val cleanUrl = WebContentEnvelope.cleanField(parsed.content.url, deadlineNs, checking).takeExtractPrefix(2048)
            val cleanSiteName = parsed.content.siteName?.let {
                WebContentEnvelope.cleanField(it, deadlineNs, checking).takeExtractPrefix(253)
            }
            val cleanPublished = parsed.content.published?.let {
                WebContentEnvelope.cleanField(it, deadlineNs, checking).takeExtractPrefix(128)
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
            // pre-Ok gate：預算已耗盡／呼叫方已取消則絕不建構／返回逾越預算之 Ok
            //（parse／gunzip 內部雙閘檢查之外的最後一道守門）。
            checking()
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val envelope = if (!truncated) {
                WebContentEnvelope.wrap(capped, deadlineNs, checking)
            } else if (modelTruncated) {
                WebContentEnvelope.wrap(capped + "\n${WebExtract.TRUNCATED_SENTINEL_MARK} AT $effModel CHARS]", deadlineNs, checking)
            } else {
                // truncated 僅由 parser 輸入前綴截斷觸發（model 預算未截）：input sentinel。
                WebContentEnvelope.wrap(capped + "\n${WebExtract.TRUNCATED_INPUT_SENTINEL_MARK}$PARSE_INPUT_CAP CHARS]", deadlineNs, checking)
            }
            // Option B：成功路徑——把鏈式信任上下文交回 extract()（供 cache 寫入）。
            chainSink?.invoke(chain.toList())
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

/**
 * 不分大小寫的 header 查找（首個命中）：對重複鍵（fixtures 以不可表示的
 * `Map<String,String>` 無法表達重複）**first-match-wins**。
 *
 * 為何如此：production 之重複 header 偵測屬於 transport 層之職責——D3 OkHttp
 * adapter 為唯一 backstop，必須以 fail-closed（重複即拒絕）或 `getAll` 多值
 * 裁決兜底（見 [WebExtractHttpGet] 契約第 5 點）；本函式刻意不嘗試偵測重複，
 * 線性掃描、語義穩定即可。
 */
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
 * （`decoded → cleanField → trim`；不刪 tag、不 unescape，raw `&` 原樣保留；
 * 保留原文行結構與內部連續空白——`\n`／`\t` 保留、`\r` 為 Cc 故移除，其餘空白
 * 僅經 NFKC 摺疊與首尾 trim）。
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
 * 受上限的 gzip 解壓（bomb fail-closed；deadline＋協程活性雙有界）。
 *
 * - 解壓超過 [cap] 即回 [GunzipOut.TooLarge]（不回部分資料）；
 * - 非 gzip／損壞即回 [GunzipOut.Corrupt]（→ `BAD_RESPONSE`，與超限區分）；
 * - 每次 `gz.read` 前、以及回傳前各做一次雙閘（[checking] 先、[deadlineNs] 後）：
 *   呼叫方協程已取消 → [kotlinx.coroutines.CancellationException] 原樣上拋；
 *   逾時拋 [DeadlineExceededException]（→ `Failed(TIMEOUT)`；絕不回部分資料）。
 *   預設 `Long.MAX_VALUE`／`{}`（直接呼叫語義不變）。
 */
internal fun gunzipCapped(data: ByteArray, cap: Int, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): GunzipOut {
    return try {
        GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
            val out = ByteArrayOutputStream(minOf(cap.coerceAtLeast(1), 8192))
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                checking()
                if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
                val n = gz.read(buf)
                if (n == -1) break
                if (total + n > cap) return GunzipOut.TooLarge
                out.write(buf, 0, n)
                total += n
            }
            // 回傳前再確認一次預算與活性：膨脹工作不得逾越預算後才交出位元組。
            checking()
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

/** 轉碼降級：未知 charset／損壞位元組一律 UTF-8 replacement 解碼，永不拋異常。
 *
 * 未 weave `checking`／deadline：本函式無自有迴圈（解碼由 native ByteBuffer decode 完成，
 * 無插入點），且 decode 依設計不計入 deadline（見 header「decode 不計入 deadline」）；
 * 工作受 bytes 上限（≤ [WebExtract.ABSOLUTE_MAX_BYTES]）約束。兩個入口 catch-all 皆不可能
 * 看到 checking 拋出的 CancellationException（checking 未穿入本函式）；deadline arm 保留。
 */
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
 * parseHtml 輸入上限（chars）；超過者僅取前綴（至多 [PARSE_INPUT_CAP] code units，surrogate 安全截斷）解析。
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
 * 所有工作皆在 `html.takeExtractPrefix(PARSE_INPUT_CAP)` 上進行（surrogate 安全，不切斷 pair、
 * 亦不留下 lone high surrogate）；`html.length > PARSE_INPUT_CAP`
 * 時回傳之 [ParseResult.inputCapped] 為 true（input 前綴截斷，與 model 預算截斷
 * 相互獨立；不隨 maxBytes 放大）。title 提取與
 * `<script>/<style>` 剝除皆為 index-based 線性掃描（不使用 lazy backtracking
 * regex），避免病態輸入觸發災難性回溯。metadata（published）改為單趟、
 * 前向游標的屬性解析器（無 regex），整體 O(n)。
 * 所有迴圈受 [deadlineNs] **與** `checking` 雙閘約束：逾時拋 [DeadlineExceededException]；
 * 呼叫方協程已取消時由 `checking` 拋 [kotlinx.coroutines.CancellationException]——
 * 兩者皆不被 catch-all 吞沒（CancellationException 有專屬 rethrow arm），原樣上拋給呼叫方
 * （DeadlineExceededException → `Failed(TIMEOUT)`；CancellationException → 協程取消契約）。
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
internal fun parseHtml(html: String, finalUrl: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): ParseResult? {
    return try {
        checking()
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        val inputCapped = html.length > PARSE_INPUT_CAP
        val input = html.takeExtractPrefix(PARSE_INPUT_CAP)
        // ASCII 摺疊（長度不變）：所有被搜的 needle（`<title`／`<script`／`<style`／
        // `</title>`／`</script>`／`</style>`）皆 ASCII，索引必然不錯位
        //（ICU lowercase 可能變長度，故不用）。
        val lower = HtmlExtract.foldAsciiLower(input, deadlineNs, checking)
        // 先剝 script/style，title／published 皆於剝除後的 body 上提取
        //（不得在 script/style 內容中命中 <title> 或 meta published）。
        val body = HtmlExtract.stripScriptAndStyle(input, lower, deadlineNs, checking)
        checking()
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        val bodyLower = HtmlExtract.foldAsciiLower(body, deadlineNs, checking)
        checking()
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        val titleRaw = HtmlExtract.titleOf(body, bodyLower, deadlineNs, checking)
        val titleText = HtmlExtract.stripText(titleRaw, deadlineNs, checking).trim().takeIf { it.isNotEmpty() } ?: finalUrl
        val text = HtmlExtract.stripText(body, deadlineNs, checking).trim().let { HtmlExtract.Spaces.replace(it, " ") }
        val snippet = text.takeIf { it.isNotEmpty() } ?: titleText
        val published = HtmlExtract.publishedOf(body, deadlineNs, checking)
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
    } catch (e: CancellationException) {
        // checking 探針（協程活性）拋出之取消：原樣上拋，絕不映射為 null／BAD_RESPONSE。
        throw e
    } catch (_: Exception) {
        null
    }
}

// 內部 HTML 掃描器（internal：測試與 parseHtml 共用；外部不得依賴）。
internal object HtmlExtract {
    // Spaces Regex 為線性掃描且不可 weave（regex 無逐步插入點）：其工作量受 input cap 約束，接受此剩餘，
    // 不在其上加 checking；其餘所有單趟掃描迴圈皆雙閘（checking 先、nanoTime 後）。
    val Spaces = Regex("\\s+")
    val NUMERIC_ENTITY = Regex("&#(\\d+);")
    val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

    /**
     * ASCII 摺疊（長度不變）：所有被搜的 needle（`<title`／`<script`／`<style`／
     * `</title>`／`<meta`／`<time`）皆 ASCII，索引必然不錯位（ICU lowercase 可能變長度，
     * 故不用）。每 512 code units 雙閘（checking 先、[deadlineNs] 後）。
     */
    fun foldAsciiLower(input: String, deadlineNs: Long, checking: () -> Unit = {}): String {
        var scan = 0
        return buildString(input.length) {
            for (c in input) {
                if (++scan and 511 == 0) {
                    checking()
                    if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
                }
                append(if (c in 'A'..'Z') c + 32 else c)
            }
        }
    }

    /** tag-name 邊界：緊接 needle 之 byte 必須為 ASCII 空白、`/`、`>` 或 EOF。 */
    private fun isTagNameBoundary(lower: String, p: Int): Boolean {
        if (p >= lower.length) return true // EOF
        val c = lower[p]
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' ||
            c == '\u000C' || c == '\r' || c == '/' || c == '>'
    }

    /**
     * tag-open 判定（text context 掃描用）：`<`（開標籤）或 `</`（閉標籤）
     * 後接 ASCII 字母，且 tag-name（ASCII 字母／數字／`-`／`_`／`:`）結束處
     * 為 tag-name 邊界（見 [isTagNameBoundary]）或 EOF 即為 tag-open。
     * `<!--`（comment）由 [nextTagStartInText] 先行攔截；`<!DOCTYPE`、`<?xml`、
     * `<1`、`< s` 等皆非 tag-open。皆 ASCII 比對（`lower` 為 ASCII 摺疊，索引不錯位）。
     * tag-name 掃描迴圈每 512 code units 雙閘（checking 先、[deadlineNs] 後）。
     */
    private fun isTagOpen(lower: String, i: Int, deadlineNs: Long, checking: () -> Unit): Boolean {
        if (i < 0 || i >= lower.length || lower[i] != '<') return false
        var p = i + 1
        if (p < lower.length && lower[p] == '/') p++ // 閉標籤
        if (p >= lower.length) return false
        val c0 = lower[p]
        if (!(c0 in 'a'..'z' || c0 in 'A'..'Z')) return false
        var j = p
        var scanned = 0
        while (j < lower.length) {
            if (++scanned and 511 == 0) {
                checking()
                if (System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
            }
            val c = lower[j]
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == ':'
            ) {
                j++
            } else {
                break
            }
        }
        return isTagNameBoundary(lower, j)
    }

    /**
     * text context 掃描核心：自 `from` 起，前進並回傳下一個 tag-open `<` 之
     * 索引；若無則回 -1。comment（`<!--` 至首個 `-->`，或 `-->` 缺席時至 EOF）
     * 整體跳過——comment 內容原樣保留在文本流中，但不構成任何 tag-open。
     * 已開啟之 tag 之內部（含引號包裹之子字串）**不由本掃描器觀察**——
     * 呼叫端應以 [findTagEnd]（引號感知）消耗整個 tag，再自其後繼續。
     * 每 512 code units 雙閘（checking 先、[deadlineNs] 後）；逾時拋
     * [DeadlineExceededException]；呼叫方協程已取消時 [checking] 拋
     * [kotlinx.coroutines.CancellationException]。
     */
    private fun nextTagStartInText(lower: String, from: Int, deadlineNs: Long, checking: () -> Unit): Int {
        var i = maxOf(from, 0)
        var scanned = 0
        while (i < lower.length) {
            if (++scanned and 511 == 0) {
                checking()
                if (System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
            }
            if (lower.startsWith("<!--", i)) {
                // indexOf 為原生掃描、不可 weave；線性且受 input cap 約束，接受此剩餘。
                val close = lower.indexOf("-->", i + 4)
                i = if (close < 0) lower.length else close + 3
                continue
            }
            if (lower[i] == '<' && isTagOpen(lower, i, deadlineNs, checking)) return i
            i++
        }
        return -1
    }

    /**
     * text context 中下一個 needle（如 `<title`／`<meta`）之 tag-open 索引；
     * needle 之後須為 tag-name 邊界（見 [isTagNameBoundary]）才回候選。
     * comment（`<!--`…`-->`／EOF）與非候選 tag 之內部（含引號屬性值，
     * 單引號／雙引號）皆不產生候選；非候選 tag 以 [findTagEnd]（引號感知）
     * 整體跳過。無候選回 -1。每個 tag／comment 至多掃過一次，整體 O(n)。
     * 每 512 個迭代雙閘（checking 先、[deadlineNs] 後）；逾時拋
     * [DeadlineExceededException]；呼叫方協程已取消時 [checking] 拋
     * [kotlinx.coroutines.CancellationException]。
     */
    private fun findTagStart(lower: String, from: Int, needle: String, deadlineNs: Long, checking: () -> Unit): Int {
        var cursor = maxOf(from, 0)
        var scanned = 0
        while (true) {
            if (++scanned and 511 == 0) {
                checking()
                if (System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
            }
            val p = nextTagStartInText(lower, cursor, deadlineNs, checking)
            if (p < 0) return -1
            if (lower.startsWith(needle, p) && isTagNameBoundary(lower, p + needle.length)) {
                return p
            }
            // 非候選 tag：引號感知整體跳過；未閉合 tag 之後不可能再有候選——收束至 EOF。
            val end = findTagEnd(lower, p, deadlineNs, checking)
            cursor = if (end < 0) lower.length else end + 1
        }
    }

    /**
     * 取首個 text-context `<title>...</title>` 文本（case-insensitive；
     * tag-name 邊界檢查；comment／引號屬性值內之偽 `<title>` 不成候選）；
     * 開標籤以 [findTagEnd]（引號感知）取得收尾；任一環節缺失即回空字串
     * （由上層回退 URL）；`lower.indexOf("</title>")` 為原生掃描、不可 weave（線性、受 input cap 約束）。
     * 入口與各子掃描雙閘（checking 先、[deadlineNs] 後）。
     */
    fun titleOf(input: String, lower: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String {
        checking()
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        val tOpen = findTagStart(lower, 0, "<title", deadlineNs, checking)
        if (tOpen < 0) return ""
        val openEnd = findTagEnd(lower, tOpen, deadlineNs, checking)
        if (openEnd < 0) return "" // 未閉合開標籤：無 title
        // indexOf 為原生掃描、不可 weave；線性且受 input cap 約束，接受此剩餘。
        val tClose = lower.indexOf("</title>", openEnd + 1)
        if (tClose < 0) return ""
        return input.substring(openEnd + 1, tClose)
    }

    /**
     * 單趟、前向游標剝除 `<script>...</script>`／`<style>...</style>`
     * （case-insensitive，tag-name 邊界檢查；**text context**：comment（`<!--`…`-->`／EOF）
     * 與引號屬性值（單／雙引號）內之偽標籤不構成候選；非候選 tag 以
     * [findTagEnd]（引號感知）整體跳過）。無對應關閉標籤者，
     * 原則上自開始標籤剝至輸入結尾（`findTagEnd == -1` 之未閉合開始標籤**一律剝至輸入結尾，
     * 內嵌之 `</script …>`／`</style …>` 不構成停點，刻意 fail-closed；見候選分支註解）；
     * end-tag 辨識容忍 tag-name 與 `>` 之間之 ASCII 空白及單一可選 `/`，見 [findEndTagClose]）。
     * comment 本身原樣保留在文本流中（含 `<!--`…EOF
     * 的未閉合 comment——其內容一併原樣保留至輸入結尾）。
     * 以 StringBuilder 分段拼接（純文字／comment／非候選 tag 為「待取段」，遇結構性
     * 邊界一次 append），不使用 backtracking regex。
     * 實作為**單一前向掃描**（游標單調不減、comment 狀態隨掃描推進）：`<` 處先判
     * comment（整塊原樣輸出並跳過，不掃描其內容），再判 tag-open，再判是否
     * script／style（tag-name 邊界）候選；非候選 tag 經 [findTagEnd]（引號感知）
     * 整體跳過且原樣輸出。各子操作（[findTagEnd]、[findEndTagClose]）掃過的
     * 區段至多常數倍重疊（≤2×；各候選之 WS run 與 indexOf 區間皆不相交）且只隨游標前進，
     * 故整體 O(n)（input cap 256k 下無二次方項）。`<` 結構性邊界（含候選／非候選分支）每迭代雙閘
     * （[checking] 先、[deadlineNs] 後，同舊版）；純文字路徑以 512 code unit 節流雙閘，
     * 並於段落 flush 點（待取段落入 sb 之前）雙閘；子掃描（[findTagEnd]／[findEndTagClose]）同樣雙閘。
     * 內部 [findEndTagClose] 以 `"</tag"` 候選前進辨識 end-tag（tag-name 邊界＋
     * ASCII 空白＋可選 `/`），各候選 indexOf 區間幾乎不相交、WS 跳掃各 run 不相交，
     * 故 one-pass 仍為線性（見 [findEndTagClose] KDoc）；`lower.indexOf("-->")` 為
     * 原生掃描、不可 weave（線性、受 input cap 約束）。
     */
    fun stripScriptAndStyle(input: String, lower: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String {
        val sb = StringBuilder(input.length)
        val n = input.length
        var i = 0
        var segStart = 0 // 待取段起點：純文字／comment／非候選 tag 皆以原樣輸出
        var scanned = 0 // 自掃描起點之純文字 code unit 計數（512 為節流粒度）
        fun gate() {
            checking()
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        }
        fun flush() {
            gate() // 段落 flush 點：待取段落入 sb 之前先雙閘
            if (segStart < i) sb.append(input, segStart, i)
        }
        while (i < n) {
            if (lower[i] == '<') {
                // `<` 結構性邊界（含候選分支）：每迭代雙閘，與舊版及 lib 內 stripTags 一致。
                gate()
                // comment：整塊原樣輸出並跳過——comment 內容不構成候選（與 findTagStart 之 comment 跳過語義一致）。
                if (lower.startsWith("<!--", i)) {
                    // indexOf 為原生掃描、不可 weave（線性、受 input cap 約束），接受此剩餘。
                    val close = lower.indexOf("-->", i + 4)
                    flush()
                    val end = if (close < 0) n else close + 3
                    sb.append(input, i, end)
                    i = end
                    segStart = i
                    continue
                }
                if (isTagOpen(lower, i, deadlineNs, checking)) {
                    val tag = when {
                        lower.startsWith("<script", i) && isTagNameBoundary(lower, i + "<script".length) -> "script"
                        lower.startsWith("<style", i) && isTagNameBoundary(lower, i + "<style".length) -> "style"
                        else -> null
                    }
                    if (tag != null) {
                        // 候選：先原樣輸出其前之待取段，再整體剝除（不輸出任何此元素之內容）。
                        flush()
                        // 先以引號感知 findTagEnd 算出「開始標籤的結尾」，end-tag 搜尋必須從
                        // open 標籤的 `>` 之後開始——引號屬性值內的 `</script …>`/`</style …>` 不得算收尾
                        //（否則屬性內假 end-tag 會把實際標籤 JS/CSS 本文剩餘漏進模型可見體）。
                        // findTagEnd == -1（開始標籤未閉合）時 searchFrom 退回 i（開始標籤之 `<`），
                        // 且 `i = if (openEnd < 0 || close < 0) n else close`——開始標籤未閉合時
                        // **一律剝至輸入結尾**；內嵌之 `</script …>`／`</style …>` 不構成停點（刻意
                        // fail-closed；不更動 searchFrom 邏輯）。僅當開始標籤閉合且全文找得到 end-tag
                        // 時，strip 才停於該 end-tag 之後。
                        val openEnd = findTagEnd(lower, i, deadlineNs, checking)
                        val searchFrom = if (openEnd < 0) i else openEnd + 1
                        // WHATWG 端標籤語義：容忍 tag-name 與 `>` 之間之 ASCII 空白及單一可選 `/`
                        //（如 `</script >`、`</style\t/>`）；找不到／邊界不符回 -1 → 剝至輸入結尾。
                        val close = findEndTagClose(lower, tag, searchFrom, deadlineNs, checking)
                        i = if (openEnd < 0 || close < 0) n else close
                        segStart = i
                        continue
                    }
                    // 非候選 tag：整體跳過（原樣輸出，經待取段機制）。
                    val end = findTagEnd(lower, i, deadlineNs, checking)
                    i = if (end < 0) n else end + 1
                    continue
                }
            }
            // 純文字路徑：每 512 code unit 雙閘（與 lib 內多處掃描同一節流粒度）；
            // 段落 flush 點（flush()）亦雙閘。
            i++
            if (++scanned and 511 == 0) gate()
        }
        flush() // 尾端 flush：函式收尾之段落落點同樣雙閘（文本已被掃盡，gate 僅作取消探針）
        return sb.toString()
    }

    /**
     * 閉合 end-tag 辨識（WHATWG 端標籤語義）：`</`＋ASCII 大小寫不敏感 tag 名，其後可
     * 含任意 ASCII 空白（[isAsciiWs]），再為單一可選 `/` 與任意 ASCII 空白，最後為 `>`。
     * 回傳 `>` 之後一位（整個閉合標籤之後）；找不到／邊界不符一律 -1（呼叫方剝至輸入結尾）。
     * 候選以 `lower.indexOf("</tag", i)` 單調前進：每次失敗僅前進 2；各候選之 indexOf 掃描
     * 區間幾乎不相交（累計 ≤ n），尾隨空白跳掃總量亦 ≤ n（各 run 不相交、受 input cap 約束）。
     * checking／deadline 雙閘：每個候選一次、WS 跳掃每 512 code units 一次。
     * 刻意偏離 WHATWG tokenizer 的 error-recovery：end-tag 上出現屬性（`</script x>`）或
     * 重複 `/`（`</script//>`）一律不視為閉合（over-strip＝內容損失、絕非洩漏；fail-closed）；
     * 僅接受 WHATWG 語法層之 `WS*` `/`? `WS*` `>` 形。NBSP 等非 ASCII 空白不接受（WHATWG 限 ASCII）。
     * raw-text body 不做引號／comment 過濾：JS 字串內之 `</script …>` 即為閉合（WHATWG 忠實語義）。
     */
    private fun findEndTagClose(lower: String, tag: String, from: Int, deadlineNs: Long, checking: () -> Unit): Int {
        var i = from
        while (true) {
            checking()
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val p = lower.indexOf("</$tag", i)
            if (p < 0) return -1
            var j = p + tag.length + 2
            // 拒 `</scripts>`、`</scriptx>`：tag-name 之後須為 tag-name 邊界（EOF 亦可）。
            if (!isTagNameBoundary(lower, j)) {
                i = p + 2
                continue
            }
            // 跳過 tag-name 與 `/`／`>` 之間任意數量之 ASCII 空白（每 512 code units 雙閘）。
            var scanned = 0
            while (j < lower.length && isAsciiWs(lower[j])) {
                if (++scanned and 511 == 0) {
                    checking()
                    if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
                }
                j++
            }
            // 單一可選 `/`（self-closing 形態之 end-tag，如 `</script/>`），其後可再含 ASCII 空白。
            if (j < lower.length && lower[j] == '/') {
                j++
                var scanned2 = 0
                while (j < lower.length && isAsciiWs(lower[j])) {
                    if (++scanned2 and 511 == 0) {
                        checking()
                        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
                    }
                    j++
                }
            }
            if (j < lower.length && lower[j] == '>') return j + 1
            i = p + 2
        }
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
     * 各掃描迴圈每 512 code units 雙閘（checking 先、[deadlineNs] 後）。
     */
    private fun parseTagAttrs(tag: String, deadlineNs: Long, checking: () -> Unit): MetaAttrs {
        var propertyName: String? = null
        var content: String? = null
        var datetime: String? = null
        var pos = 0
        var scanned = 0
        val n = tag.length
        // 跳過前導 `<meta`／`<time`。
        while (pos < n && !isAsciiWs(tag[pos]) && tag[pos] != '>') {
            if (++scanned and 511 == 0) {
                checking()
                if (System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
            }
            pos++
        }
        while (pos < n) {
            if (++scanned and 511 == 0) {
                checking()
                if (System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
            }
            val c = tag[pos]
            if (c == '>') break
            if (isAsciiWs(c) || c == '/') {
                pos++
                continue
            }
            val nameStart = pos
            while (pos < n && isNameChar(tag[pos])) {
                if (++scanned and 511 == 0) {
                    checking()
                    if (System.nanoTime() >= deadlineNs) {
                        throw DeadlineExceededException()
                    }
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
                if (++scanned and 511 == 0) {
                    checking()
                    if (System.nanoTime() >= deadlineNs) {
                        throw DeadlineExceededException()
                    }
                }
                pos++
            }
            if (pos < n && tag[pos] == '=') {
                pos++
                while (pos < n && isAsciiWs(tag[pos])) {
                    if (++scanned and 511 == 0) {
                        checking()
                        if (System.nanoTime() >= deadlineNs) {
                            throw DeadlineExceededException()
                        }
                    }
                    pos++
                }
                if (pos < n && (tag[pos] == '"' || tag[pos] == '\'')) {
                    val quote = tag[pos]
                    pos++
                    val valStart = pos
                    while (pos < n && tag[pos] != quote) {
                        if (++scanned and 511 == 0) {
                            checking()
                            if (System.nanoTime() >= deadlineNs) {
                                throw DeadlineExceededException()
                            }
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
                        if (++scanned and 511 == 0) {
                            checking()
                            if (System.nanoTime() >= deadlineNs) {
                                throw DeadlineExceededException()
                            }
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
     * 本函式同時供 [nextTagStartInText] 之呼叫端消耗整個 tag（非候選 tag
     * 整體跳過），以及 titleOf／publishedOf 取得候選 tag 之收尾。
     * 每 512 code units 雙閘（[checking] 先、[deadlineNs] 後）。
     */
    private fun findTagEnd(lower: String, from: Int, deadlineNs: Long, checking: () -> Unit): Int {
        var inQuote: Char? = null
        var j = from
        var scanned = 0
        while (j < lower.length) {
            if (++scanned and 511 == 0) {
                checking()
                if (System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
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
     * 掃描僅於 **text context** 進行（[findTagStart]）：comment（`<!--`…`-->`／EOF）
     * 與引號屬性值（單／雙引號，經 [findTagEnd] 取得 tag 收尾）內之偽 `<meta`／
     * `<time>` 皆不成候選。先掃 `<meta` 候選（依文件順序），每候選標籤以
     * 引號感知的掃描取得 `>` 收尾（`content="a>>>b"` 內的 `>` 不算收尾），
     * 再切出 `property`／`name`＋`content` 屬性對，`property`／`name` 值（大小寫不
     * 敏感）與五個 published alias 比對（article:published_time／datePublished／
     * publish_date／pubdate／dc.date），命中即回 `unescapeHtml(content)`；
     * 否則續掃。無匹配時再掃 `<time datetime>` 候選同法解析。
     *
     * 未閉合候選 tag 之處理（stop vs skip，依候選型別區分）——
     * meta／time：候選**作廢並中止本趟掃描**（前向證明：findTagEnd==-1
     * 代表此候選之後已無任何 `>`（引號感知），之後不可能出現可閉合的
     * meta／time 候選 → 兩迴圈一律 break，整趟 O(n)）；
     * script／style（見 [stripScriptAndStyle]）：原則上自開始標籤剝至輸入結尾
     * （`findTagEnd == -1` 之未閉合開始標籤一律剝至輸入結尾，內嵌之 end-tag 不停點）。
     *
     * 對抗性 `<meta a` 鏈（無 `>`）：首個候選作廢（-1）後 break；
     * metaPublishedLinearFixture 對 44,800 字元 < 10 秒上限穩定通過。
     *
     * 入口、兩趟候選迴圈、fold 與子掃描皆雙閘（[checking] 先、[deadlineNs] 後）；
     * `unescapeHtml` 之 regex 替換不可 weave（線性、受 input cap 約束）。
     */
    fun publishedOf(html: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String? {
        checking()
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        // ASCII 摺疊（長度不變）：所有 needle 皆 ASCII。
        val lower = foldAsciiLower(html, deadlineNs, checking)
        var cursor = 0
        while (true) {
            checking()
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val p = findTagStart(lower, cursor, "<meta", deadlineNs, checking)
            if (p < 0) break
            val tagEnd = findTagEnd(lower, p, deadlineNs, checking)
            if (tagEnd < 0) {
                // 未閉合 meta tag：前向證明此後不存在 `>`，之後所有候選皆不可閉合 → break。
                break
            }
            val attrs = parseTagAttrs(html.substring(p, tagEnd + 1), deadlineNs, checking)
            val name = attrs.propertyName?.lowercase()
            if (name != null && name in PUBLISHED_ALIASES && !attrs.content.isNullOrBlank()) {
                return unescapeHtml(attrs.content)
            }
            cursor = tagEnd + 1
        }
        cursor = 0
        while (true) {
            checking()
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val p = findTagStart(lower, cursor, "<time", deadlineNs, checking)
            if (p < 0) break
            val tagEnd = findTagEnd(lower, p, deadlineNs, checking)
            if (tagEnd < 0) {
                // 未閉合 time tag：前向證明此後不存在 `>`，之後所有候選皆不可閉合 → break。
                break
            }
            val attrs = parseTagAttrs(html.substring(p, tagEnd + 1), deadlineNs, checking)
            if (!attrs.datetime.isNullOrBlank()) {
                return unescapeHtml(attrs.datetime)
            }
            cursor = tagEnd + 1
        }
        return null
    }

    /**
     * 線性去 tag（無 regex）：與 [parseHtml] 之 structural scanner（[isTagOpen]／[findTagEnd]／
     * [foldAsciiLower]）一致之可辨識 tag token 判定——
     * 1. `<!--` → 整個 comment（至首個 `-->`，或 EOF）**不輸出任何內容**
     *    （comment 為非可見文本；`HELLO</p><!-- note > SECRET --> WORLD` → comment 內文不進 snippet）；
     * 2. 可辨識 tag token（[isTagOpen] 之 `<`＋字母／`</`＋字母，以及 `<!`／`<?` 前綴，
     *    如 `<!DOCTYPE`／`<?xml`）→ 引號感知（single／double quote）消耗至真正閉合之 `>`，
     *    輸出單一空白（`<div data-test="a > b">` 之 `>` 於引號內，不算閉合）；
     *    若可辨識卻未閉合（`findTagEnd` == -1，即其後再無 `>`），捨棄其餘字串
     *    （未閉合 tag 之後全屬 tag 內容，無可見文本）；
     * 3. 其餘無法辨識之 `<`（如 `Price < 10 > 3`、`<1`、`< s`）→ 原樣輸出 `<` 並前進 1。
     *
     * 每輪雙閘（[checking] 先、[deadlineNs] 後）；子掃描（[foldAsciiLower]／[isTagOpen]／
     * [findTagEnd]）各自雙閘；內部 `indexOf('<'／'-->')` 為原生掃描、不可 weave
     * （線性、受 input cap 約束）。`lower` 為 ASCII 摺疊（長度不變），索引與 `html` 一一對齊。
     */
    private fun stripTags(html: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String {
        val lower = foldAsciiLower(html, deadlineNs, checking)
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            checking()
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                sb.append(html, i, html.length)
                break
            }
            sb.append(html, i, lt)
            if (lower.startsWith("<!--", lt)) {
                // comment：吃到首個 `-->`（或 EOF），不輸出任何內容。
                // indexOf 為原生掃描、不可 weave（線性、受 input cap 約束），接受此剩餘。
                val close = html.indexOf("-->", lt + 4)
                i = if (close < 0) html.length else close + 3
                continue
            }
            val recognizable = isTagOpen(lower, lt, deadlineNs, checking) ||
                (lt + 1 < html.length && (lower[lt + 1] == '!' || lower[lt + 1] == '?'))
            if (recognizable) {
                // 可辨識 tag token：引號感知消耗至真正閉合之 `>`，輸出單一空白。
                val end = findTagEnd(lower, lt, deadlineNs, checking)
                if (end < 0) break // 未閉合 tag：捨棄其餘字串。
                sb.append(' ')
                i = end + 1
                continue
            }
            // 無法辨識之 `<`：原樣輸出並前進 1。
            sb.append('<')
            i = lt + 1
        }
        return sb.toString()
    }

    /**
     * 去 tag＋unescape＋收斂空白。兩段入口雙閘由下游承擔（stripTags 每輪、[checking] 透傳）；
     * `unescapeHtml` 之 regex 替換與 [Spaces] Regex 替換皆不可 weave（線性、受 input cap 約束）。
     */
    fun stripText(html: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String =
        unescapeHtml(stripTags(html, deadlineNs, checking)).let { Spaces.replace(it, " ").trim() }

    private fun decodeCodePoint(cp: Int?): String? =
        if (cp != null && cp in 0..0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) String(Character.toChars(cp)) else null

    /**
     * entity unescape：`String.replace`／Regex（NUMERIC_ENTITY／HEX_ENTITY）全為線性操作且不可 weave
     * （regex／native 無逐步插入點），受 input cap 約束，接受此剩餘；呼叫方之 deadline／checking
     * 仍於進入本函式前與後續工作點生效。
     */
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
