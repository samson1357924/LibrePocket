package dev.librepocket.tool

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-B `web.fetch` / `web.search` 測試：https-only 策略、maxBytes 截斷、
 * 15s 超時可配、轉碼降級、開關預設（webfetch 開 / websearch 關）。
 * 全程 MockWebServer（本地迴環 http 允許），不連外網。
 */
class WebFetchTest {

    // ---- URL 策略（純函數，不觸碰網路） ----

    @Test fun httpsIsAllowed() {
        val check = WebFetch.checkUrl("https://example.com/a?q=1")
        assertTrue(check is WebFetch.UrlCheck.Allowed)
    }

    @Test fun nonLocalHttpIsBlockedBeforeAnyNetwork() {
        for (url in listOf("http://example.com/", "http://203.0.113.7:8080/x", "http://[2001:db8::1]/")) {
            val check = WebFetch.checkUrl(url)
            assertTrue(url, check is WebFetch.UrlCheck.Blocked)
            val blocked = check as WebFetch.UrlCheck.Blocked
            assertEquals(DenyReason.NO_PRIVILEGE, blocked.reason)
            assertEquals("CLEARTEXT_NON_LOCAL", blocked.detail)
        }
    }

    @Test fun blockedFetchReturnsUnavailableWithoutSending() {
        val server = MockWebServer()
        try {
            val port = server.url("/").port
            // 指向本地埠但用非本地主機名：策略短路，server 應收到 0 個請求。
            val outcome = WebFetch.fetch("http://example.com:$port/")
            assertTrue(outcome is WebFetch.FetchOutcome.Unavailable)
            val u = outcome as WebFetch.FetchOutcome.Unavailable
            assertEquals(DenyReason.NO_PRIVILEGE, u.reason)
            assertEquals("CLEARTEXT_NON_LOCAL", u.detail)
            assertTrue(u.message.contains("做不到") && u.message.contains("NO_PRIVILEGE"))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun redirectDowngradeToNonLocalHttpIsBlockedWithoutFollowing() {
        val target = MockWebServer()
        val redirector = MockWebServer()
        try {
            target.enqueue(MockResponse().setResponseCode(200).setBody("must-not-fetch"))
            // 302 到非本地明文：取 target 本地 URL 並把 host 換成 example.com（同埠）。
            // 逐跳 checkUrl 應擋下，絕不向目標發請求（target 零請求）。
            val evil = target.url("/secret").toString()
                .replace("localhost", "example.com")
                .replace("127.0.0.1", "example.com")
            redirector.enqueue(MockResponse().setResponseCode(302).setHeader("Location", evil))
            val outcome = WebFetch.fetch(redirector.url("/start").toString())
            assertTrue(outcome is WebFetch.FetchOutcome.Unavailable)
            val u = outcome as WebFetch.FetchOutcome.Unavailable
            assertEquals(DenyReason.NO_PRIVILEGE, u.reason)
            assertEquals("CLEARTEXT_NON_LOCAL", u.detail)
            assertEquals(1, redirector.requestCount)
            assertEquals(0, target.requestCount)
        } finally {
            redirector.shutdown()
            target.shutdown()
        }
    }

    @Test fun localSameSchemeRedirectIsFollowedManually() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("arrived"))
            val outcome = WebFetch.fetch(server.url("/start").toString())
            assertTrue(outcome is WebFetch.FetchOutcome.Ok)
            assertEquals("arrived", (outcome as WebFetch.FetchOutcome.Ok).text)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun loopbackHttpIsAllowed() {
        assertTrue(WebFetch.checkUrl("http://localhost:8080/x") is WebFetch.UrlCheck.Allowed)
        assertTrue(WebFetch.checkUrl("http://127.0.0.1:9/") is WebFetch.UrlCheck.Allowed)
        assertTrue(WebFetch.checkUrl("http://127.0.0.2/") is WebFetch.UrlCheck.Allowed)
        assertTrue(WebFetch.checkUrl("http://[::1]:8080/") is WebFetch.UrlCheck.Allowed)
    }

    @Test fun unsupportedSchemeAndBadUrlAreBlocked() {
        val ftp = WebFetch.checkUrl("ftp://example.com/f")
        assertTrue(ftp is WebFetch.UrlCheck.Blocked)
        assertEquals("UNSUPPORTED_SCHEME", (ftp as WebFetch.UrlCheck.Blocked).detail)
        val bad = WebFetch.checkUrl("not a url")
        assertTrue(bad is WebFetch.UrlCheck.Blocked)
        assertEquals("BAD_URL", (bad as WebFetch.UrlCheck.Blocked).detail)
    }

    // ---- 傳輸行為（MockWebServer = 本地 http，允許） ----

    @Test fun fetchOkAndMaxBytesTruncates() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789".repeat(100)))
            val url = server.url("/big").toString()
            val outcome = WebFetch.fetch(url, maxBytes = 100)
            assertTrue(outcome is WebFetch.FetchOutcome.Ok)
            val ok = outcome as WebFetch.FetchOutcome.Ok
            assertTrue(ok.truncated)
            assertTrue(ok.bytesKept <= 100)
            assertEquals(100, ok.text.toByteArray(Charsets.UTF_8).size)
        } finally {
            server.shutdown()
        }
    }

    @Test fun maxBytesIsClampedToAbsoluteMax() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("hi"))
            val url = server.url("/").toString()
            val outcome = WebFetch.fetch(url, maxBytes = Int.MAX_VALUE)
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
            val outcome = WebFetch.fetch(server.url("/").toString())
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
            val outcome = WebFetch.fetch(server.url("/slow").toString(), timeoutMs = 500L)
            assertTrue(outcome is WebFetch.FetchOutcome.Failed)
            assertEquals("TIMEOUT", (outcome as WebFetch.FetchOutcome.Failed).detail)
        } finally {
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
            val outcome = WebFetch.fetch(server.url("/bin").toString())
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
}
