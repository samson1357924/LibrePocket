package dev.librepocket.tool

import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkEgressPolicy
import dev.librepocket.policy.NetworkPolicy
import dev.librepocket.policy.PinnedNetworkDns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S1-B `web.fetch` / `web.search` 測試＋Issue #135 A4 接線測試。
 *
 * A4 後 URL 檢查走 [NetworkPolicy.evaluateUrl]（per-endpoint opt-in，預設
 * deny-all）：迴環 http 須 `ALLOW_CLEARTEXT_PRIVATE` 才放行；redirect 逐跳重驗，
 * 跳轉失敗一律 typed `REDIRECT_BLOCKED`；建連走 per-fetch DNS pin＋直連。
 * 全程 MockWebServer（本地迴環，配 opt-in）／fake [HostResolver]，
 * 禁真網路、禁真 DNS。
 */
class WebFetchTest {

    private val cleartextPrivate = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)

    /**
     * Fake DNS：精確表查詢；字面 IP 走 [NetworkPolicy.parseLiteralIp]
     *（純解析，不觸網）；`localhost` 固定回 `127.0.0.1`；其餘一律拋
     * [UnknownHostException]（如 NXDOMAIN，fail-closed）。
     */
    private fun fakeDns(vararg entries: Pair<String, List<String>>): HostResolver {
        val table = entries.toMap()
        return HostResolver { host ->
            table[host]?.map { InetAddress.getByName(it) }
                ?: NetworkPolicy.parseLiteralIp(host)?.let { listOf(it) }
                ?: if (host == "localhost") listOf(InetAddress.getByName("127.0.0.1"))
                else throw UnknownHostException(host)
        }
    }

    private fun loopbackDns(): HostResolver = fakeDns("localhost" to listOf("127.0.0.1"))

    private fun assertBlocked(code: String, check: WebFetch.UrlCheck) {
        assertTrue("expected Blocked, got: $check", check is WebFetch.UrlCheck.Blocked)
        val blocked = check as WebFetch.UrlCheck.Blocked
        assertEquals(DenyReason.NO_PRIVILEGE, blocked.reason)
        assertEquals(code, blocked.detail)
    }

    private fun assertUnavailable(code: String, outcome: WebFetch.FetchOutcome) {
        assertTrue("expected Unavailable, got: $outcome", outcome is WebFetch.FetchOutcome.Unavailable)
        val u = outcome as WebFetch.FetchOutcome.Unavailable
        assertEquals(DenyReason.NO_PRIVILEGE, u.reason)
        assertEquals(code, u.detail)
        assertTrue(u.message.contains("做不到") && u.message.contains("NO_PRIVILEGE"))
    }

    // ---- URL 策略（走 NetworkPolicy；一律注 fake Dns） ----

    @Test fun httpsIsAllowed() {
        // 正向：公網 https fixture 在預設 opt-in 下 PASS。
        val dns = fakeDns("example.com" to listOf("203.0.113.7"))
        val check = WebFetch.checkUrl("https://example.com/a?q=1", resolver = dns)
        assertTrue("expected Allowed, got: $check", check is WebFetch.UrlCheck.Allowed)
    }

    @Test fun nonLocalHttpIsBlockedBeforeAnyNetwork() {
        val dns = fakeDns("example.com" to listOf("203.0.113.7"))
        for (url in listOf("http://example.com/", "http://203.0.113.7:8080/x", "http://[2001:db8::1]/")) {
            val check = WebFetch.checkUrl(url, resolver = dns)
            assertBlocked(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, check)
        }
    }

    @Test fun tenEvilComHttpIsRejectedByBytesNotPrefix() {
        // `10.evil.com` 是 DNS 名（解析後公網），絕不能被當成 10/8 放行 http。
        val dns = fakeDns("10.evil.com" to listOf("203.0.113.7"))
        assertBlocked(
            NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name,
            WebFetch.checkUrl("http://10.evil.com/", resolver = dns),
        )
        // 同一 host 走 https 放行，證明它沒被當成私有。
        val https = WebFetch.checkUrl("https://10.evil.com/v1", resolver = dns)
        assertTrue("expected Allowed, got: $https", https is WebFetch.UrlCheck.Allowed)
        // fetch 層同樣 fail-closed（不起 server：政策短路，零建連）。
        assertUnavailable(
            NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name,
            WebFetch.fetch("http://10.evil.com/", resolver = dns),
        )
    }

    @Test fun loopbackHttpNeedsCleartextOptIn() {
        // 正向：迴環 http 在 opt-in 下 PASS；預設 deny-all 先拒。
        val dns = loopbackDns()
        for (url in listOf(
            "http://localhost:8080/x",
            "http://127.0.0.1:9/",
            "http://127.0.0.2/",
            "http://[::1]:8080/",
        )) {
            assertBlocked(
                NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name,
                WebFetch.checkUrl(url, resolver = dns),
            )
            val allowed = WebFetch.checkUrl(url, optIn = cleartextPrivate, resolver = dns)
            assertTrue("$url expected Allowed, got: $allowed", allowed is WebFetch.UrlCheck.Allowed)
        }
    }

    @Test fun loopbackHttpFetchSucceedsWithOptIn() {
        // 正向：迴環 http＋opt-in 真實抓取（MockWebServer＋fake DNS＋pin＋直連）。
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello-loopback"))
            val url = server.url("/").toString()
            val outcome = WebFetch.fetch(url, optIn = cleartextPrivate, resolver = loopbackDns())
            assertTrue("expected Ok, got: $outcome", outcome is WebFetch.FetchOutcome.Ok)
            assertEquals("hello-loopback", (outcome as WebFetch.FetchOutcome.Ok).text)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun alwaysBlockedClassesStayBlockedWithOptIn() {
        // metadata／link-local／multicast／unspecified／CGNAT：最強 opt-in 也打不開。
        val dns = loopbackDns()
        assertBlocked(
            NetworkDenyCode.METADATA_ADDRESS_BLOCKED.name,
            WebFetch.checkUrl(
                "http://169.254.169.254/latest/meta-data/",
                optIn = cleartextPrivate,
                resolver = dns,
            ),
        )
        // metadata 走 https＋較弱 opt-in 同樣永遠阻擋。
        assertBlocked(
            NetworkDenyCode.METADATA_ADDRESS_BLOCKED.name,
            WebFetch.checkUrl(
                "https://169.254.169.254/",
                optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
                resolver = dns,
            ),
        )
        for (url in listOf(
            "http://169.254.10.20/", // link-local（非 metadata 段）
            "http://224.0.0.1/", // multicast
            "http://0.0.0.0/", // unspecified／保留段
            "http://100.64.0.1/", // CGNAT（不可信，opt-in 打不開）
        )) {
            assertBlocked(
                NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name,
                WebFetch.checkUrl(url, optIn = cleartextPrivate, resolver = dns),
            )
        }
        // fetch 層同樣 fail-closed（字面 IP 不查 DNS，不起 server，零建連）。
        assertUnavailable(
            NetworkDenyCode.METADATA_ADDRESS_BLOCKED.name,
            WebFetch.fetch(
                "http://169.254.169.254/latest/meta-data/",
                optIn = cleartextPrivate,
                resolver = dns,
            ),
        )
    }

    @Test fun unsupportedSchemeAndBadUrlAreBlocked() {
        val dns = loopbackDns()
        val ftp = WebFetch.checkUrl("ftp://example.com/f", resolver = dns)
        assertBlocked(NetworkDenyCode.UNSUPPORTED_SCHEME.name, ftp)
        val bad = WebFetch.checkUrl("not a url", resolver = dns)
        assertBlocked(NetworkDenyCode.INVALID_HOST.name, bad)
    }

    // ---- 逐跳判定（純函數，不建連） ----

    @Test fun redirectDowngradeIsAlwaysBlocked() {
        // https → http 降級永遠拒：即使目標（迴環＋opt-in）本身可放行，照擋，
        // 且先於 DNS 判定（攻擊者可控 Location 不觸發解析）。
        val dns = fakeDns("api.example.com" to listOf("203.0.113.10"))
        val eval = WebFetch.evaluateRedirectTarget(
            "https://api.example.com/a",
            "http://127.0.0.1:9/b",
            cleartextPrivate,
            dns,
        )
        assertFalse("expected deny: $eval", eval.allowed)
        assertEquals(NetworkDenyCode.REDIRECT_BLOCKED, eval.denyCode)
    }

    @Test fun redirectToLoopbackUnderDefaultOptInIsBlocked() {
        // https 起跳 → https 迴環：預設 opt-in 下私有不放行，正規化為 REDIRECT_BLOCKED。
        val dns = fakeDns("api.example.com" to listOf("203.0.113.10"))
        val eval = WebFetch.evaluateRedirectTarget(
            "https://api.example.com/a",
            "https://127.0.0.1:9/b",
            EndpointNetworkOptIn(),
            dns,
        )
        assertFalse("expected deny: $eval", eval.allowed)
        assertEquals(NetworkDenyCode.REDIRECT_BLOCKED, eval.denyCode)
    }

    @Test fun redirectToMetadataIsBlockedEvenWithOptIn() {
        val dns = loopbackDns()
        val eval = WebFetch.evaluateRedirectTarget(
            "http://localhost:1/a",
            "http://169.254.169.254/x",
            cleartextPrivate,
            dns,
        )
        assertFalse("expected deny: $eval", eval.allowed)
        assertEquals(NetworkDenyCode.REDIRECT_BLOCKED, eval.denyCode)
    }

    @Test fun redirectToPublicHttpIsBlockedEvenWithOptIn() {
        // 公網 http 永不開放：opt-in 也打不開，正規化為 REDIRECT_BLOCKED。
        val dns = fakeDns("10.evil.com" to listOf("203.0.113.7"))
        val eval = WebFetch.evaluateRedirectTarget(
            "http://localhost:1/a",
            "http://10.evil.com/",
            cleartextPrivate,
            dns,
        )
        assertFalse("expected deny: $eval", eval.allowed)
        assertEquals(NetworkDenyCode.REDIRECT_BLOCKED, eval.denyCode)
    }

    @Test fun redirectSameHostLoopbackWithOptInIsFollowable() {
        val dns = loopbackDns()
        val eval = WebFetch.evaluateRedirectTarget(
            "http://localhost:1/a",
            "http://localhost:1/b",
            cleartextPrivate,
            dns,
        )
        assertTrue("expected allow: $eval", eval.allowed)
    }

    // ---- 傳輸行為（MockWebServer = 本地 http，配 opt-in＋fake DNS） ----

    @Test fun blockedFetchReturnsUnavailableWithoutSending() {
        val server = MockWebServer()
        try {
            val port = server.url("/").port
            // 指向本地埠但用公網主機名：政策短路，server 應收到 0 個請求。
            val dns = fakeDns("example.com" to listOf("203.0.113.7"))
            val outcome = WebFetch.fetch("http://example.com:$port/", resolver = dns)
            assertUnavailable(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, outcome)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun redirectToDeniedTargetIsBlockedWithoutFollowing() {
        val redirector = MockWebServer()
        try {
            val dns = loopbackDns()
            // 302 到 metadata（opt-in 也打不開）：逐跳判定擋下，絕不建第二連線。
            redirector.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/x"))
            val outcome = WebFetch.fetch(
                redirector.url("/start").toString(),
                optIn = cleartextPrivate,
                resolver = dns,
            )
            assertUnavailable(NetworkDenyCode.REDIRECT_BLOCKED.name, outcome)
            assertEquals(1, redirector.requestCount)
        } finally {
            redirector.shutdown()
        }
    }

    @Test fun redirectDeniedTargetMakesNoSecondConnection() {
        // N1 誠實註記：本 repo 無 TLS test infra，不硬造 `https→http` 真 E2E。
        // 降級判定（`https→http` 永遠拒、先於 DNS）由純函數測試
        // `redirectDowngradeIsAlwaysBlocked` 釘住（零建連）；此 E2E 以
        // `http→metadata（被拒目標）` 類比，釘住同一執行面不變式：被拒跳轉
        // 絕不建第二連線（witness 全程閒置 requestCount==0，origin 恰一發）。
        val origin = MockWebServer()
        val witness = MockWebServer()
        try {
            // witness 保持監聽但從不被指向：任何第二連線洩漏都會落到它身上。
            witness.url("/idle")
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/x"))
            val outcome = WebFetch.fetch(
                origin.url("/start").toString(),
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertUnavailable(NetworkDenyCode.REDIRECT_BLOCKED.name, outcome)
            assertEquals(1, origin.requestCount)
            assertEquals("denied redirect must not open a second connection", 0, witness.requestCount)
        } finally {
            origin.shutdown()
            witness.shutdown()
        }
    }

    @Test fun redirectWithoutLocationIsBlocked() {
        // 缺 Location：跳轉失敗一律 typed REDIRECT_BLOCKED，不建第二連線。
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(302))
            val outcome = WebFetch.fetch(
                server.url("/start").toString(),
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertUnavailable(NetworkDenyCode.REDIRECT_BLOCKED.name, outcome)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun redirectWithBadLocationIsBlocked() {
        // 壞 Location（非 http(s) scheme）：解析成功但政策拒收，正規化為
        // REDIRECT_BLOCKED，不建第二連線。
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "ftp://example.com/x"))
            val outcome = WebFetch.fetch(
                server.url("/start").toString(),
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertUnavailable(NetworkDenyCode.REDIRECT_BLOCKED.name, outcome)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun localSameSchemeRedirectIsFollowedManually() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("arrived"))
            val outcome = WebFetch.fetch(
                server.url("/start").toString(),
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Ok)
            assertEquals("arrived", (outcome as WebFetch.FetchOutcome.Ok).text)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun tooManyRedirectsIsRedirectBlocked() {
        val server = MockWebServer()
        try {
            repeat(WebFetch.MAX_REDIRECTS + 1) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop"))
            }
            val outcome = WebFetch.fetch(
                server.url("/start").toString(),
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertUnavailable(NetworkDenyCode.REDIRECT_BLOCKED.name, outcome)
            assertEquals(WebFetch.MAX_REDIRECTS + 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun fetchOkAndMaxBytesTruncates() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789".repeat(100)))
            val url = server.url("/big").toString()
            val outcome = WebFetch.fetch(url, maxBytes = 100, optIn = cleartextPrivate, resolver = loopbackDns())
            assertTrue(outcome is WebFetch.FetchOutcome.Ok)
            val ok = outcome as WebFetch.FetchOutcome.Ok
            assertTrue(ok.truncated)
            assertTrue(ok.bytesKept <= 100)
            assertEquals(100, ok.text.toByteArray(Charsets.UTF_8).size)
        } finally {
            server.shutdown()
        }
    }

    @Test fun oversizedBodyIsTruncatedAtAbsoluteCap() {
        // 大 body fail-closed：超過 1 MiB 硬頂只保留硬頂並標記，不無界緩衝。
        val server = MockWebServer()
        try {
            val big = "0123456789".repeat((WebFetch.ABSOLUTE_MAX_BYTES / 10) + 200)
            server.enqueue(MockResponse().setResponseCode(200).setBody(big))
            val url = server.url("/big").toString()
            val outcome = WebFetch.fetch(
                url,
                maxBytes = Int.MAX_VALUE,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue("expected Ok, got: $outcome", outcome is WebFetch.FetchOutcome.Ok)
            val ok = outcome as WebFetch.FetchOutcome.Ok
            assertTrue(ok.truncated)
            assertEquals(WebFetch.ABSOLUTE_MAX_BYTES, ok.bytesKept)
        } finally {
            server.shutdown()
        }
    }

    @Test fun maxBytesIsClampedToAbsoluteMax() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("hi"))
            val url = server.url("/").toString()
            val outcome = WebFetch.fetch(
                url,
                maxBytes = Int.MAX_VALUE,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Ok)
            assertFalse((outcome as WebFetch.FetchOutcome.Ok).truncated)
        } finally {
            server.shutdown()
        }
    }

    @Test fun httpErrorIsFailedWithMatrixTemplate() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
            val outcome = WebFetch.fetch(
                server.url("/").toString(),
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Failed)
            val failed = outcome as WebFetch.FetchOutcome.Failed
            assertEquals("HTTP_503", failed.detail)
            assertTrue(failed.message.contains("做不到") && failed.message.contains("→"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun slowBodyHitsInjectableTimeout() {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("late").setBodyDelay(3, TimeUnit.SECONDS),
            )
            val outcome = WebFetch.fetch(
                server.url("/slow").toString(),
                timeoutMs = 500L,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Failed)
            assertEquals("TIMEOUT", (outcome as WebFetch.FetchOutcome.Failed).detail)
        } finally {
            server.shutdown()
        }
    }

    @Test fun timeoutZeroClampsToOneMillisecondAndTimesOut() {
        // timeoutMs = 0 不得關閉 OkHttp 超時（OkHttp 0 表「不超時」），鉗制至 1ms 即時超時。
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("late").setBodyDelay(3, TimeUnit.SECONDS),
            )
            val outcome = WebFetch.fetch(
                server.url("/slow").toString(),
                timeoutMs = 0L,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Failed)
            assertEquals("TIMEOUT", (outcome as WebFetch.FetchOutcome.Failed).detail)
        } finally {
            server.shutdown()
        }
    }

    @Test fun customClientWithZeroTimeoutIsOverriddenBySafeTimeout() {
        // 呼叫端傳入 callTimeout=0 的 client，safeTimeout 仍強制覆寫，不得沿用無界超時。
        val server = MockWebServer()
        try {
            val unboundedClient = OkHttpClient.Builder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("late").setBodyDelay(3, TimeUnit.SECONDS),
            )
            val outcome = WebFetch.fetch(
                server.url("/slow").toString(),
                client = unboundedClient,
                timeoutMs = 500L,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Failed)
            assertEquals("TIMEOUT", (outcome as WebFetch.FetchOutcome.Failed).detail)
        } finally {
            server.shutdown()
        }
    }

    @Test fun customClientWithZeroTimeoutAtDefaultTimeoutMsIsOverridden() {
        // 傳入 timeoutMs == TIMEOUT_MS（15s）與 callTimeout=0/connectTimeout=0 的 client：
        // 仍必須重建並覆寫四個超時，不得因與 TIMEOUT_MS 相等而沿用無界 client。
        val server = MockWebServer()
        try {
            val observedConnectMs = AtomicInteger(-1)
            val observedReadMs = AtomicInteger(-1)
            val unboundedClient = OkHttpClient.Builder()
                .connectTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .addNetworkInterceptor { chain ->
                    observedConnectMs.set(chain.connectTimeoutMillis())
                    observedReadMs.set(chain.readTimeoutMillis())
                    chain.proceed(chain.request())
                }
                .build()
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val outcome = WebFetch.fetch(
                server.url("/ok").toString(),
                client = unboundedClient,
                timeoutMs = WebFetch.TIMEOUT_MS,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Ok)
            assertTrue(
                "connectTimeout 必須被覆寫為 > 0 且 <= TIMEOUT_MS，實際 ${observedConnectMs.get()}",
                observedConnectMs.get() in 1..WebFetch.TIMEOUT_MS.toInt(),
            )
            assertTrue(
                "readTimeout 必須被覆寫為 > 0 且 <= TIMEOUT_MS，實際 ${observedReadMs.get()}",
                observedReadMs.get() in 1..WebFetch.TIMEOUT_MS.toInt(),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test fun negativeTimeoutClampsToOneMillisecondAndTimesOut() {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("late").setBodyDelay(3, TimeUnit.SECONDS),
            )
            val outcome = WebFetch.fetch(
                server.url("/slow").toString(),
                timeoutMs = -5L,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Failed)
            assertEquals("TIMEOUT", (outcome as WebFetch.FetchOutcome.Failed).detail)
        } finally {
            server.shutdown()
        }
    }

    @Test fun slowDnsPreflightHitsTimeoutPromptly() {
        // WARN-04：WebFetch DNS preflight 納入全程端到端 deadline，阻塞 resolver 必須即時超時中斷。
        val interrupted = AtomicBoolean(false)
        val hangingDns = HostResolver {
            try {
                Thread.sleep(5_000)
            } catch (e: InterruptedException) {
                interrupted.set(true)
                throw e
            }
            listOf(InetAddress.getByName("127.0.0.1"))
        }
        val start = System.currentTimeMillis()
        val outcome = WebFetch.fetch(
            "https://slow-dns.example.com/",
            timeoutMs = 200L,
            resolver = hangingDns,
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue(outcome is WebFetch.FetchOutcome.Failed)
        assertEquals("TIMEOUT", (outcome as WebFetch.FetchOutcome.Failed).detail)
        assertTrue("DNS preflight timeout 應在 2s 內返回，實際 ${elapsed}ms", elapsed < 2_000L)
        val deadline = System.currentTimeMillis() + 1_000L
        while (!interrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("超時必須中斷阻塞的 DNS 解析執行緒", interrupted.get())
    }

    @Test fun aggregateTimeoutAcrossRedirects() {
        // 多跳 redirect 必須共用單一全程 deadline，不得每跳重置超時預算。
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .addHeader("Location", "/hop2")
                    .setHeadersDelay(350, TimeUnit.MILLISECONDS),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("too-late")
                    .setBodyDelay(350, TimeUnit.MILLISECONDS),
            )
            val outcome = WebFetch.fetch(
                server.url("/hop1").toString(),
                timeoutMs = 500L,
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue("expected Failed, got: $outcome", outcome is WebFetch.FetchOutcome.Failed)
            assertEquals("TIMEOUT", (outcome as WebFetch.FetchOutcome.Failed).detail)
        } finally {
            server.shutdown()
        }
    }

    @Test fun cancelledFetchFailsWithoutPartialOk() {
        // 取消語義：測試執行緒 cancel 在途 Call → Failed（絕不回傳部分 Ok；
        // WebFetch 無 cache／無第二傳輸可寫——`tapping` 本就無 cache 屬空斷言，
        // 真正保證是下方「Failed 型別＋單次請求」：部分位元組不得包裝成 Ok）。
        val server = MockWebServer()
        val executor = Executors.newSingleThreadExecutor()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("late-body").setBodyDelay(5, TimeUnit.SECONDS),
            )
            val captured = AtomicReference<Call>()
            val tapping = OkHttpClient.Builder()
                .addNetworkInterceptor { chain ->
                    captured.set(chain.call())
                    chain.proceed(chain.request())
                }
                .build()
            val url = server.url("/slow").toString()
            val future = executor.submit(Callable {
                WebFetch.fetch(
                    url,
                    client = tapping,
                    timeoutMs = 60_000L,
                    optIn = cleartextPrivate,
                    resolver = loopbackDns(),
                )
            })
            // 等請求抵達 server（已在途），再取消在途 Call。
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            var inFlight: Call? = null
            val deadline = System.currentTimeMillis() + 5_000L
            while (inFlight == null && System.currentTimeMillis() < deadline) {
                inFlight = captured.get()
                if (inFlight == null) Thread.sleep(10)
            }
            assertNotNull("expected to capture the in-flight call", inFlight)
            inFlight!!.cancel()
            val outcome = future.get(10, TimeUnit.SECONDS)
            assertTrue("expected Failed after cancel, got: $outcome", outcome is WebFetch.FetchOutcome.Failed)
            // 有意義斷言（取代空斷言 `tapping.cache == null`）：取消後不得有部分 Ok，
            // 且只發過一次請求（無重送／無第二傳輸）；detail 僅能是傳輸類失敗。
            val failed = outcome as WebFetch.FetchOutcome.Failed
            assertTrue(
                "cancel detail must be TIMEOUT/TRANSPORT, got: ${failed.detail}",
                failed.detail == "TIMEOUT" || failed.detail == "TRANSPORT",
            )
            assertTrue(failed.message.contains("做不到"))
            assertEquals("cancel must not retry", 1, server.requestCount)
        } finally {
            executor.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun brokenCharsetDowngradesInsteadOfThrowing() {
        val server = MockWebServer()
        try {
            val raw = Buffer().write(byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xFF.toByte(), 0x41.toByte()))
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setBody(raw),
            )
            val outcome = WebFetch.fetch(
                server.url("/bin").toString(),
                optIn = cleartextPrivate,
                resolver = loopbackDns(),
            )
            assertTrue(outcome is WebFetch.FetchOutcome.Ok)
            val text = (outcome as WebFetch.FetchOutcome.Ok).text
            assertTrue(text.contains("�"))
            assertTrue(text.endsWith("A"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun unknownCharsetDowngradesToUtf8() {
        val decoded = WebFetch.decodeDowngraded("héllo".toByteArray(Charsets.UTF_8), "x-unknown-charset")
        assertEquals("héllo", decoded)
    }

    // ---- 能力投影（開關預設） ----

    @Test fun webfetchIsOnByDefault_websearchIsOff() {
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        val fetch = ToolRegistry.projectAll(ctx)[WebFetch.TOOL_NAME]!!
        assertEquals(CapabilityLevel.NATIVE, fetch.level)
        val search = ToolRegistry.projectAll(ctx)[WebSearchLocal.TOOL_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, search.level)
        assertEquals(DenyReason.USER_DISABLED, search.reason)
    }

    @Test fun websearchOptInBecomesNative() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(WebSearchLocal.SWITCH to true),
        )
        assertEquals(CapabilityLevel.NATIVE, ToolRegistry.projectAll(ctx)[WebSearchLocal.TOOL_NAME]!!.level)
    }

    @Test fun localSearchNameDiffersFromServerPassthrough() {
        assertFalse(WebSearchLocal.TOOL_NAME == WebSearchLocal.SERVER_SIDE_NAME)
        assertEquals("web_search", WebSearchLocal.SERVER_SIDE_NAME)
        assertEquals("web.search", WebSearchLocal.TOOL_NAME)
    }

    @Test fun localSearchNeedsSupplierAndClampsCount() {
        val none = WebSearchLocal.search("kotlin", 5, null)
        assertTrue(none is WebSearchLocal.SearchOutcome.Unavailable)
        assertEquals("NO_SUPPLIER", (none as WebSearchLocal.SearchOutcome.Unavailable).detail)
        assertEquals(1, WebSearchLocal.countOf(0))
        assertEquals(WebSearchLocal.MAX_COUNT, WebSearchLocal.countOf(999))
        assertEquals(WebSearchLocal.DEFAULT_COUNT, WebSearchLocal.countOf(null))
        val ok = WebSearchLocal.search(
            "kotlin",
            3,
            WebSearchLocal.Supplier { q, c ->
                List(c) { WebSearchLocal.Result("$q#$it", "https://example.com/$it", "s$it") }
            },
        )
        assertTrue(ok is WebSearchLocal.SearchOutcome.Ok)
        assertEquals(3, (ok as WebSearchLocal.SearchOutcome.Ok).results.size)
    }

    @Test fun registryFindsBothWebTools() {
        assertEquals(SideEffect.READ, ToolRegistry.find(WebFetch.TOOL_NAME)!!.sideEffect)
        assertEquals(SideEffect.READ, ToolRegistry.find(WebSearchLocal.TOOL_NAME)!!.sideEffect)
    }

    // ---- A4 負向補強（#135）：IPv6 永阻段／userinfo／DNS rebinding ----

    @Test fun ipv6LinkLocalMulticastUnspecifiedStayBlocked() {
        // link-local（fe80::1）／multicast（ff02::1）／unspecified（::）：
        // 最強 opt-in 也打不開（字面 IP 不查 DNS）。
        val dns = loopbackDns()
        for (literal in listOf("fe80::1", "ff02::1", "::")) {
            assertBlocked(
                NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name,
                WebFetch.checkUrl("http://[$literal]/", optIn = cleartextPrivate, resolver = dns),
            )
            assertBlocked(
                NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name,
                WebFetch.checkUrl(
                    "https://[$literal]/",
                    optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
                    resolver = dns,
                ),
            )
        }
        // fetch 層同樣 fail-closed（字面 IP 不查 DNS，不起 server，零建連）。
        assertUnavailable(
            NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name,
            WebFetch.fetch("http://[fe80::1]/", optIn = cleartextPrivate, resolver = dns),
        )
    }

    @Test fun userinfoIsBlockedWithoutDnsLeak() {
        // userinfo 永遠阻擋（先於 DNS）：必敗 resolver 證零解析。
        val mustNotResolve: HostResolver = HostResolver {
            throw UnknownHostException("userinfo must not trigger DNS")
        }
        for (url in listOf(
            "http://user@localhost/",
            "http://user:pass@127.0.0.1:9/x",
            "https://user@example.com/mcp",
        )) {
            assertBlocked(
                NetworkDenyCode.USERINFO_BLOCKED.name,
                WebFetch.checkUrl(url, optIn = cleartextPrivate, resolver = mustNotResolve),
            )
            assertUnavailable(
                NetworkDenyCode.USERINFO_BLOCKED.name,
                WebFetch.fetch(url, optIn = cleartextPrivate, resolver = mustNotResolve),
            )
        }
    }

    @Test fun dnsRebindPinnedDnsUnit() {
        // 純 [PinnedNetworkDns] 單元（未經 fetch 接線；單跳接線見
        // `fetchResolvesOncePerRequest`）：fake resolver 首回公網、次回私有：
        // pin 釘住首回（建連不受污染），hasRebind 偵測到變化（呼叫方須重決策），
        // 未釘住 host 嚴格拒收。
        val first = listOf(InetAddress.getByName("203.0.113.7"))
        val rebound = listOf(InetAddress.getByName("10.0.0.1"))
        var current = first
        val mutableDelegate = HostResolver { _: String -> current }
        val pinned = PinnedNetworkDns.forHost("example.com", first, mutableDelegate)
        current = rebound
        assertEquals("pin 必須釘住首回答案", first, pinned.lookup("example.com"))
        assertEquals(true, pinned.hasRebind("example.com"))
        try {
            pinned.lookup("evil.example")
            fail("unpinned host must throw")
        } catch (e: UnknownHostException) {
            assertTrue(e.message.orEmpty().contains("DNS_PIN_MISS"))
        }
    }

    @Test fun fetchResolvesOncePerRequest() {
        // evaluate 與 connect 之間無二次解析：計數 resolver 在單跳 200 抓取中
        // 恰被調用一次（首跳判定），建連走釘住值，不再回查 delegate。
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            var resolves = 0
            val counting: HostResolver = HostResolver { _: String ->
                resolves++
                listOf(InetAddress.getByName("127.0.0.1"))
            }
            val port = server.url("/").port
            val outcome = WebFetch.fetch(
                "http://fetch.test:$port/",
                optIn = cleartextPrivate,
                resolver = counting,
            )
            assertTrue("expected Ok, got: $outcome", outcome is WebFetch.FetchOutcome.Ok)
            assertEquals("evaluate 後建連不得二次解析", 1, resolves)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
