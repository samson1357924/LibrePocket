package dev.librepocket.policy

import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkEgressPolicy
import dev.librepocket.policy.NetworkOptInStore
import dev.librepocket.policy.NetworkPolicy
import dev.librepocket.policy.PinnedNetworkDns
import dev.librepocket.provider.ChatCompletionsProvider
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.FailureKind
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderErrorClassifier
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.networkPolicyFailure
import dev.librepocket.provider.pinnedTransportFor
import dev.librepocket.provider.redirectBlockedFailure
import dev.librepocket.provider.requireNetworkPolicyAllowed
import dev.librepocket.provider.streamingFlow
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S2 (Issue #135 A2): provider 接線＋per-endpoint opt-in 綁定＋pinning。
 *
 * 全 fake DNS／合成位址，零真實連線：`203.0.113.0/24` 扮公網，RFC1918／迴環
 * 扮私有。沿用 `NetworkPolicyTest` 風格，不重構無關檔。
 */
class NetworkPolicyS2Test {

  private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

  private fun fakeDns(vararg entries: Pair<String, List<String>>): HostResolver {
    val table = entries.toMap()
    return HostResolver { host ->
      table[host]?.map(::addr) ?: throw UnknownHostException(host)
    }
  }

  // —— 10.evil.com 端到端拒（不送 credential／body，typed fatal 不重試） ——

  @Test fun tenEvilComHttpEndToEndDeniedWithoutCredential() = runBlocking {
    // `10.evil.com` 是公網名（S1 已釘死非 10/8）：走 http 永遠拒。
    val dns = fakeDns("10.evil.com" to listOf("203.0.113.7"))
    val keyCalls = AtomicInteger(0)
    val cfg = ProviderConfig(
      id = "preset:openai",
      label = "t",
      baseUrl = "http://10.evil.com/v1",
      protocol = ProviderProtocol.CHAT_COMPLETIONS,
      apiKeyRef = "fake-ref",
      networkResolver = dns,
    )
    val provider = ChatCompletionsProvider(cfg, { keyCalls.incrementAndGet(); "K".toCharArray() }, OkHttpClient())
    val events = provider.stream(ChatRequest("m", listOf(ChatMessage("user", "hi")))).toList()
    val failed = events.filterIsInstance<StreamEvent.Failed>().single()
    assertFalse("policy deny must not retry", failed.retryable)
    assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED.name, failed.code)
    assertTrue("message carries deny code, got: ${failed.message}", failed.message.contains("CLEARTEXT_NOT_PERMITTED"))
    assertEquals("credential must not be fetched on deny", 0, keyCalls.get())
  }

  @Test fun privateNameNeedsOptInEndToEnd() = runBlocking {
    val dns = fakeDns("example.com" to listOf("10.1.2.3"))
    // 預設 deny-all：https 解析到私有即拒，不取 key。
    val keyCalls = AtomicInteger(0)
    val deniedCfg = ProviderConfig(
      id = "preset:openai",
      label = "t",
      baseUrl = "https://example.com/v1",
      protocol = ProviderProtocol.CHAT_COMPLETIONS,
      apiKeyRef = "fake-ref",
      networkResolver = dns,
    )
    val denied = ChatCompletionsProvider(deniedCfg, { keyCalls.incrementAndGet(); "K".toCharArray() }, OkHttpClient())
      .stream(ChatRequest("m", listOf(ChatMessage("user", "hi")))).toList()
      .filterIsInstance<StreamEvent.Failed>().single()
    assertFalse(denied.retryable)
    assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED.name, denied.code)
    assertTrue(denied.message.contains("PRIVATE_ADDRESS_BLOCKED"))
    assertEquals(0, keyCalls.get())
    // 開 ALLOW_PRIVATE_DNS 後決策放行（此處只驗閘放行，不建連——無 server，故 transport 會連線失敗；
    // 為避免真連線，僅驗 `requireNetworkPolicyAllowed` 放行）。
    val allowed = requireNetworkPolicyAllowed(
      "https://example.com/v1",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
      dns,
    )
    assertTrue(allowed.allowed)
  }

  @Test fun typedPolicyErrorNeverRetries() {
    val dns = fakeDns("example.com" to listOf("10.1.2.3"))
    val eval = NetworkPolicy.evaluateUrl("https://example.com/", resolver = dns)
    assertFalse(eval.allowed)
    val failure = networkPolicyFailure(eval)
    assertFalse(failure.retryable)
    assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED, failure.code)
    assertTrue(failure.message.orEmpty().contains("PRIVATE_ADDRESS_BLOCKED"))
    // 縱深：即使誤走 classifier，訊息仍鎖 FATAL。
    assertEquals(FailureKind.FATAL, ProviderErrorClassifier.classify(null, failure, failure.message))
    assertEquals(FailureKind.FATAL, ProviderErrorClassifier.classify(null, null, "NETWORK_POLICY_BLOCKED:METADATA_ADDRESS_BLOCKED"))
    assertEquals(FailureKind.FATAL, ProviderErrorClassifier.classify(null, null, "cleartext_not_permitted"))
    // listModels 路徑同樣 typed（直接拋，不經 flow）。
    val cfg = ProviderConfig(
      id = "preset:openai",
      label = "t",
      baseUrl = "https://example.com/v1",
      protocol = ProviderProtocol.CHAT_COMPLETIONS,
      apiKeyRef = "fake-ref",
      networkResolver = dns,
    )
    try {
      runBlocking {
        ChatCompletionsProvider(cfg, { "K".toCharArray() }, OkHttpClient()).listModels()
      }
      fail("listModels on denied endpoint must throw")
    } catch (e: ProviderFailure) {
      assertFalse(e.retryable)
      assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED, e.code)
    }
  }

  @Test fun redirectBlockedIsTerminalFatal() {
    assertTrue(NetworkPolicy.isRedirectStatus(301))
    assertTrue(NetworkPolicy.isRedirectStatus(302))
    assertTrue(NetworkPolicy.isRedirectStatus(307))
    assertTrue(NetworkPolicy.isRedirectStatus(308))
    assertFalse(NetworkPolicy.isRedirectStatus(200))
    val failure = redirectBlockedFailure(302)
    assertFalse(failure.retryable)
    assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED, failure.code)
    // 相容舊斷言：訊息同時帶 REDIRECT_BLOCKED 與 HTTP code。
    assertTrue(failure.message.orEmpty().contains("REDIRECT_BLOCKED"))
    assertTrue(failure.message.orEmpty().contains("HTTP 302"))
    assertEquals(FailureKind.FATAL, ProviderErrorClassifier.classify(null, null, failure.message))
  }

  // —— opt-in 變更需重綁 ——

  @Test fun optInChangeNeedsRebind() {
    // 升級路徑測試：顯式 allowTierUpgrade = true 模擬 foss／github pilot 接線；
    // play production 只用預設建構（見 defaultStoreRejectsTierUpgrade）。
    val store = NetworkOptInStore(allowTierUpgrade = true)
    val endpointId = "preset:openai"
    val baseUrl = "https://api.openai.com/v1"
    // 未確認前預設 deny-all。
    assertEquals(NetworkEgressPolicy.HTTPS_ONLY, store.confirmedPolicyFor(endpointId, baseUrl, 1L))
    // 明示確認升級。
    store.confirm(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    assertEquals(NetworkEgressPolicy.ALLOW_PRIVATE_DNS, store.confirmedPolicyFor(endpointId, baseUrl, 1L))
    // baseUrl 變動 → 舊確認失效。
    assertEquals(
      NetworkEgressPolicy.HTTPS_ONLY,
      store.confirmedPolicyFor(endpointId, "https://api.openai.com/v2", 1L),
    )
    // revision 變動 → 舊確認失效（需重新 one-shot 確認）。
    assertEquals(NetworkEgressPolicy.HTTPS_ONLY, store.confirmedPolicyFor(endpointId, baseUrl, 2L))
    // token 綁定全部欄位：任一變動即不同 token。
    val t1 = NetworkOptInStore.confirmationTokenFor(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    assertEquals(t1, NetworkOptInStore.confirmationTokenFor(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L))
    assertNotEquals(t1, NetworkOptInStore.confirmationTokenFor(endpointId, "https://api.openai.com/v2", NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L))
    assertNotEquals(t1, NetworkOptInStore.confirmationTokenFor(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 2L))
    assertNotEquals(t1, NetworkOptInStore.confirmationTokenFor(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 1L))
  }

  @Test fun customEndpointIdentityBinding() {
    // 同上：升級確認需顯式 pilot 接線旗標。
    val store = NetworkOptInStore(allowTierUpgrade = true)
    // custom 身份形如 custom:<hash>；此處僅驗形狀綁定（重算由 SetupValidation 擁有）。
    val customId = "custom:0123456789abcdef0123456789abcdef"
    val baseUrl = "http://192.168.1.20:8080/v1"
    store.confirm(customId, baseUrl, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 7L)
    assertEquals(
      NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE,
      store.confirmedPolicyFor(customId, baseUrl, 7L),
    )
    // scheme 變動（http→https）即新身份，舊確認失效。
    assertEquals(
      NetworkEgressPolicy.HTTPS_ONLY,
      store.confirmedPolicyFor(customId, "https://192.168.1.20:8080/v1", 7L),
    )
    // 惡意形狀拒收。
    try {
      store.confirm("evil-id", baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
      fail("malformed endpoint id must throw")
    } catch (_: IllegalArgumentException) {
    }
  }

  @Test fun playStaysHttpsOnly() {
    // Play 決議釘死：play 維持 deny-all（HTTPS-only），永不經 opt-in 升級。
    assertTrue(NetworkOptInStore.checkPlayTierAllows(NetworkEgressPolicy.HTTPS_ONLY))
    assertFalse(NetworkOptInStore.checkPlayTierAllows(NetworkEgressPolicy.ALLOW_PRIVATE_DNS))
    assertFalse(NetworkOptInStore.checkPlayTierAllows(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE))
    assertEquals(NetworkEgressPolicy.HTTPS_ONLY, EndpointNetworkOptIn().policy)
  }

  @Test fun defaultStoreRejectsTierUpgrade() {
    // 預設建構即 play 姿態：任何非 HTTPS_ONLY 的 confirm 直接拒收（fail-closed，
    // 真正的 flavor／sourceSet gate 待 followup 前以此顯式旗標承擔）。
    val playStore = NetworkOptInStore()
    for (tier in listOf(NetworkEgressPolicy.ALLOW_PRIVATE_DNS, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)) {
      try {
        playStore.confirm("preset:openai", "https://api.openai.com/v1", tier, 1L)
        fail("default (play) store must reject $tier")
      } catch (e: IllegalArgumentException) {
        assertTrue(e.message.orEmpty().contains("NETWORK_OPTIN_TIER_NOT_ALLOWED"))
      }
    }
    // 拒收不留痕：查詢仍是預設 deny-all。
    assertEquals(
      NetworkEgressPolicy.HTTPS_ONLY,
      playStore.confirmedPolicyFor("preset:openai", "https://api.openai.com/v1", 1L),
    )
    // HTTPS_ONLY 確認（降級／預設記錄）在預設 store 仍可。
    playStore.confirm("preset:openai", "https://api.openai.com/v1", NetworkEgressPolicy.HTTPS_ONLY, 1L)
    assertEquals(
      NetworkEgressPolicy.HTTPS_ONLY,
      playStore.confirmedPolicyFor("preset:openai", "https://api.openai.com/v1", 1L),
    )
    // Pilot 接線（顯式旗標）才可升級。
    val pilotStore = NetworkOptInStore(allowTierUpgrade = true)
    pilotStore.confirm("preset:openai", "https://api.openai.com/v1", NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    assertEquals(
      NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
      pilotStore.confirmedPolicyFor("preset:openai", "https://api.openai.com/v1", 1L),
    )
  }

  @Test fun malformedQueryUrlFailsClosed() {
    // 查詢路徑 fail-closed：已確認後以形變 baseUrl 查詢不拋 IAE，一律回 HTTPS_ONLY。
    val store = NetworkOptInStore(allowTierUpgrade = true)
    val endpointId = "preset:openai"
    val baseUrl = "https://api.openai.com/v1"
    store.confirm(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    for (badUrl in listOf("", "   ", "::::", "not a url", "ftp://api.openai.com/v1", "http://[::1")) {
      assertEquals(
        "malformed query must fail closed, got allow for: $badUrl",
        NetworkEgressPolicy.HTTPS_ONLY,
        store.confirmedPolicyFor(endpointId, badUrl, 1L),
      )
    }
    // 形變查詢不污染已存確認：正常查詢仍放行。
    assertEquals(
      NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
      store.confirmedPolicyFor(endpointId, baseUrl, 1L),
    )
    // optInFor 同走查詢路徑：形變 URL 亦 fail-closed。
    assertEquals(
      NetworkEgressPolicy.HTTPS_ONLY,
      store.optInFor(endpointId, "::::", 1L).policy,
    )
  }

  // —— pinning：rebind 視為新決策 ——

  @Test fun pinnedDnsIgnoresRebindAndForcesNewDecision() {
    val first = listOf(addr("10.1.2.3"))
    val rebond = listOf(addr("10.9.9.9"))
    var current = first
    val mutableDelegate = HostResolver { _ -> current }
    val pinned = PinnedNetworkDns.forHost("example.com", first, mutableDelegate)
    // delegate 已換答案，釘住值不動（連線不受 rebind 污染）。
    current = rebond
    assertEquals(first, pinned.lookup("example.com"))
    assertEquals(first, pinned.resolve("example.com"))
    // 主動偵測到 rebind → 呼叫方必須重走 evaluateUrl（新決策）。
    assertEquals(true, pinned.hasRebind("example.com"))
    val freshEval = NetworkPolicy.evaluateUrl(
      "https://example.com/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
      HostResolver { _ -> rebond },
    )
    assertTrue(freshEval.allowed)
    assertEquals(rebond, freshEval.resolvedAddresses)
    assertNotEquals(first.toSet(), freshEval.resolvedAddresses.toSet())
  }

  @Test fun pinnedDnsStrictMissFailsClosed() {
    val pinned = PinnedNetworkDns.forHost("example.com", listOf(addr("10.1.2.3")))
    // 未釘住 host（redirect target 等）一律 fail-closed。
    try {
      pinned.lookup("evil.example")
      fail("unpinned host must throw")
    } catch (e: UnknownHostException) {
      assertTrue(e.message.orEmpty().contains("DNS_PIN_MISS"))
    }
  }

  @Test fun pinnedTransportGatesBeforeConnect() {
    // 未放行不得建 pinning client（fail-closed，不建連）。
    val dns = fakeDns("example.com" to listOf("10.1.2.3"))
    try {
      pinnedTransportFor(
        OkHttpClient(),
        "https://example.com/v1",
        EndpointNetworkOptIn(),
        dns,
      )
      fail("denied URL must not build transport")
    } catch (e: ProviderFailure) {
      assertFalse(e.retryable)
      assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED, e.code)
    }
    // 放行才建 client，且 resolver 即釘住值。
    val (client, pinned) = pinnedTransportFor(
      OkHttpClient(),
      "https://example.com/v1",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
      dns,
    )
    assertNotNull(client)
    assertEquals(listOf(addr("10.1.2.3")), pinned.pinnedFor("example.com"))
  }

  @Test fun redirectStatusCodesOnlyTrueRedirects() {
    // 301/302/303/307/308 為真跳轉；304/305/306 無 Location 跳轉語義，明確排除。
    for (code in listOf(300, 301, 302, 303, 307, 308)) {
      assertTrue("$code must be redirect", NetworkPolicy.isRedirectStatus(code))
    }
    for (code in listOf(200, 304, 305, 306)) {
      assertFalse("$code must not be redirect", NetworkPolicy.isRedirectStatus(code))
    }
  }

  @Test fun pinnedNormalizeStripsBracketsAndTrailingDot() {
    assertEquals("::1", PinnedNetworkDns.normalize("[::1]"))
    assertEquals("::1", PinnedNetworkDns.normalize("[::1]."))
    assertEquals("example.com", PinnedNetworkDns.normalize("Example.COM."))
    assertEquals("", PinnedNetworkDns.normalize("[   ]"))
    assertEquals("", PinnedNetworkDns.normalize("[ . ]"))
    // pin 與 lookup 同一鍵：括號形 pin 無括號形查。
    val pinned = PinnedNetworkDns.forHost("[::1]", listOf(addr("::1")))
    assertEquals(listOf(addr("::1")), pinned.lookup("::1"))
    assertEquals(listOf(addr("::1")), pinned.pinnedFor("[::1]."))
  }

  // —— Phase4：confirm 降級／invalidate ——

  @Test fun confirmDowngradeAndInvalidate() {
    val store = NetworkOptInStore(allowTierUpgrade = true)
    val endpointId = "preset:openai"
    val baseUrl = "https://api.openai.com/v1"
    // 升級確認。
    store.confirm(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    assertEquals(NetworkEgressPolicy.ALLOW_PRIVATE_DNS, store.confirmedPolicyFor(endpointId, baseUrl, 1L))
    // 降級確認（HTTPS_ONLY）：舊高 tier 立即失效。
    store.confirm(endpointId, baseUrl, NetworkEgressPolicy.HTTPS_ONLY, 1L)
    assertEquals(NetworkEgressPolicy.HTTPS_ONLY, store.confirmedPolicyFor(endpointId, baseUrl, 1L))
    // 再升級後 invalidate → 回 HTTPS_ONLY。
    store.confirm(endpointId, baseUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    assertEquals(NetworkEgressPolicy.ALLOW_PRIVATE_DNS, store.confirmedPolicyFor(endpointId, baseUrl, 1L))
    store.invalidate(endpointId)
    assertEquals(NetworkEgressPolicy.HTTPS_ONLY, store.confirmedPolicyFor(endpointId, baseUrl, 1L))
    // 不存在亦成功（冪等）。
    store.invalidate(endpointId)
    assertEquals(NetworkEgressPolicy.HTTPS_ONLY, store.confirmedPolicyFor(endpointId, baseUrl, 1L))
  }

  // —— Phase4：opt-in 變更端到端（MockWebServer 明文迴環，零真網） ——

  private fun sseOk(): MockResponse = MockResponse()
    .setHeader("Content-Type", "text/event-stream")
    .setBody(
      "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
        "data: [DONE]\n\n",
    )

  @Test fun cleartextDeniedWithoutConfirmSendsNothing() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      val base = "http://127.0.0.1:${server.port}/v1"
      val endpointId = "preset:openai"
      val store = NetworkOptInStore(allowTierUpgrade = true)
      // 未確認：預設 deny-all。
      val optIn = store.optInFor(endpointId, base, 1L)
      assertEquals(NetworkEgressPolicy.HTTPS_ONLY, optIn.policy)
      val keyCalls = AtomicInteger(0)
      val cfg = ProviderConfig(
        id = endpointId,
        label = "t",
        baseUrl = base,
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "fake-ref",
        networkOptIn = optIn,
        // 字面迴環不觸 DNS：exploding resolver 被呼叫即失敗。
        networkResolver = HostResolver { throw UnknownHostException("must not resolve literals") },
      )
      val provider = ChatCompletionsProvider(cfg, { keyCalls.incrementAndGet(); "K".toCharArray() }, OkHttpClient())
      val events = withTimeout(5_000) { provider.stream(ChatRequest("m", listOf(ChatMessage("user", "hi")))).toList() }
      val failed = events.filterIsInstance<StreamEvent.Failed>().single()
      assertFalse(failed.retryable)
      assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED.name, failed.code)
      assertTrue("deny carries code, got: ${failed.message}", failed.message.contains("CLEARTEXT_NOT_PERMITTED"))
      assertEquals("credential must not be fetched on deny", 0, keyCalls.get())
      assertEquals("denied stream sends nothing", 0, server.requestCount)
      assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    } finally {
      server.shutdown()
    }
  }

  @Test fun cleartextAllowedAfterConfirmStreamsOnce() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      val base = "http://127.0.0.1:${server.port}/v1"
      val endpointId = "preset:openai"
      val store = NetworkOptInStore(allowTierUpgrade = true)
      store.confirm(endpointId, base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 1L)
      val optIn = store.optInFor(endpointId, base, 1L)
      assertEquals(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, optIn.policy)
      server.enqueue(sseOk())
      val keyCalls = AtomicInteger(0)
      val cfg = ProviderConfig(
        id = endpointId,
        label = "t",
        baseUrl = base,
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "fake-ref",
        networkOptIn = optIn,
        networkResolver = HostResolver { throw UnknownHostException("must not resolve literals") },
      )
      val provider = ChatCompletionsProvider(cfg, { keyCalls.incrementAndGet(); "K".toCharArray() }, OkHttpClient())
      val events = withTimeout(5_000) { provider.stream(ChatRequest("m", listOf(ChatMessage("user", "hi")))).toList() }
      assertTrue("expected Done, got: $events", events.last() is StreamEvent.Done)
      assertTrue(events.none { it is StreamEvent.Failed })
      assertEquals(1, keyCalls.get())
      assertEquals(1, server.requestCount)
      val recorded = server.takeRequest(2, TimeUnit.SECONDS)
      assertTrue(recorded?.path.orEmpty().endsWith("/chat/completions"))
    } finally {
      server.shutdown()
    }
  }

  // —— Phase4：provider 層不重試（輕量，不起 TurnController） ——

  @Test fun networkPolicyBlockedNeverRetriesAtProviderLayer() = runBlocking {
    // 單一 attempt 即 typed fatal：retryable=false＋classifier FATAL（TurnController 層無需重試）。
    val dns = fakeDns("example.com" to listOf("10.1.2.3"))
    val eval = NetworkPolicy.evaluateUrl("https://example.com/", resolver = dns)
    assertFalse(eval.allowed)
    val failure = networkPolicyFailure(eval)
    assertFalse(failure.retryable)
    assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED, failure.code)
    assertEquals(FailureKind.FATAL, ProviderErrorClassifier.classify(null, failure, failure.message))
    // 經 streamingFlow 仍是單一非重試終端（calls==1 由單 attempt 結構保證）。
    val events = withTimeout(5_000) { streamingFlow { throw failure }.toList() }
    val failed = events.filterIsInstance<StreamEvent.Failed>().single()
    assertFalse(failed.retryable)
    assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED.name, failed.code)
  }

  // —— Phase4：失敗訊息不洩漏位址／host 明文 ——

  @Test fun policyFailureMessageLeaksNoAddressOrHost() {
    val dns = fakeDns("example.com" to listOf("10.1.2.3"))
    for (url in listOf("https://example.com/", "http://10.1.2.3/", "https://192.168.1.20/")) {
      val eval = NetworkPolicy.evaluateUrl(url, resolver = dns)
      assertFalse("expected deny for $url", eval.allowed)
      val msg = networkPolicyFailure(eval).message.orEmpty()
      assertFalse("policy message must not leak address/host: $msg", msg.contains("10.1.2.3") || msg.contains("192.168") || msg.contains("example.com"))
    }
    // redirect typed fatal 亦不帶 Location 明文（僅 REDIRECT_BLOCKED＋HTTP code）。
    val redir = redirectBlockedFailure(307)
    assertTrue(redir.message.orEmpty().contains("REDIRECT_BLOCKED"))
    assertFalse(redir.message.orEmpty().contains("10.1.2.3"))
  }

  // —— Phase 1 硬化：NetworkOptInStore.boundOf / token 防碰撞 / 脫敏 ——

  @Test fun boundOfRejectsAuthorityConfusionMalformedSchemeAndBlankIdSuffix() {
    val store = NetworkOptInStore(allowTierUpgrade = true)
    for (badUrl in listOf(
      "http://@localhost/mcp",
      "http://a\\@localhost/mcp",
      "http://evil\\localhost/mcp",
      "https:/127.0.0.1/mcp",
      "https://ex\tample.com/mcp",
      "https://ex ample.com/mcp",
      "https://ex\u00a0ample.com/mcp",
      "https://\u00a0example.com/mcp",
      "ht\ttp://example.com/mcp",
      "https://example.com:80\t80/mcp",
      "https:///mcp",
      "https://[1:2:3:4:5:6:7:8:9]/mcp",
      "https://[1:2:3:4:5:6:7:1.2.3.4]/mcp",
      "https://[::1/",
      "https://::1]/",
      "https://[]/",
      "https://[::1]abc/",
      "https://[::1][::2]/",
      "https://[127.0.0.1]/",
    )) {
      try {
        NetworkOptInStore.boundOf("mcp:srv", badUrl, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 1L)
        fail("boundOf must reject $badUrl")
      } catch (_: IllegalArgumentException) {
      }
      try {
        store.confirm("mcp:srv", badUrl, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 1L)
        fail("confirm must reject $badUrl")
      } catch (_: IllegalArgumentException) {
      }
      assertEquals(
        "malformed query must fail closed to HTTPS_ONLY: $badUrl",
        NetworkEgressPolicy.HTTPS_ONLY,
        store.confirmedPolicyFor("mcp:srv", badUrl, 1L),
      )
    }
    try {
      NetworkOptInStore.boundOf("mcp:srv", "   ", NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
      fail("spaces-only url must throw NETWORK_OPTIN_URL_BLANK")
    } catch (e: IllegalArgumentException) {
      assertEquals("NETWORK_OPTIN_URL_BLANK", e.message)
    }
    for (ctrlOnly in listOf("\u0000", "\t", "  \u0007  ")) {
      try {
        NetworkOptInStore.boundOf("mcp:srv", ctrlOnly, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
        fail("control-char-only url must throw NETWORK_OPTIN_URL_MALFORMED: '$ctrlOnly'")
      } catch (e: IllegalArgumentException) {
        assertEquals("NETWORK_OPTIN_URL_MALFORMED", e.message)
      }
    }
    for (badId in listOf("mcp:", "preset:", "custom:", "mcp:   ", "preset:\t")) {
      try {
        store.confirm(badId, "https://api.example.com/v1", NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
        fail("boundOf must reject blank suffix id: '$badId'")
      } catch (_: IllegalArgumentException) {
      }
    }
  }

  @Test fun boundOfNormalizationAndTrailingDotMismatch() {
    val store = NetworkOptInStore(allowTierUpgrade = true)
    val endpointId = "preset:openai"
    store.confirm(endpointId, "https://API.OPENAI.COM:443/v1#frag", NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    assertEquals(
      NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
      store.confirmedPolicyFor(endpointId, "https://api.openai.com/v1", 1L),
    )
    // 尾點視為不同身份（fail-closed）
    assertEquals(
      NetworkEgressPolicy.HTTPS_ONLY,
      store.confirmedPolicyFor(endpointId, "https://api.openai.com./v1", 1L),
    )

    val clearId = "mcp:local"
    store.confirm(clearId, "http://127.0.0.1:80/mcp#section", NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 2L)
    assertEquals(
      NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE,
      store.confirmedPolicyFor(clearId, "http://127.0.0.1/mcp", 2L),
    )
  }

  @Test fun confirmationTokenLengthPrefixedResistsDelimiterCollision() {
    val t1 = NetworkOptInStore.confirmationTokenFor(
      "mcp:a",
      "https://example.com/b|c",
      NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
      1L,
    )
    val t2 = NetworkOptInStore.confirmationTokenFor(
      "mcp:a|https://example.com/b",
      "https://example.com/c",
      NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
      1L,
    )
    assertNotEquals(t1, t2)

    val t3 = NetworkOptInStore.confirmationTokenFor(
      "mcp:srv",
      "http://127.0.0.1/mcp?x=1|http|80",
      NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE,
      1L,
    )
    val t4 = NetworkOptInStore.confirmationTokenFor(
      "mcp:srv|http://127.0.0.1/mcp?x=1",
      "http://127.0.0.1/mcp",
      NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE,
      1L,
    )
    assertNotEquals(t3, t4)
  }

  @Test fun confirmedOptInToStringOmitsBaseUrl() {
    val store = NetworkOptInStore(allowTierUpgrade = true)
    val confirmed = store.confirm(
      "mcp:srv",
      "https://secret-host.example/path?token=supersecret",
      NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
      3L,
    )
    val str = confirmed.toString()
    assertFalse("ConfirmedOptIn.toString must redact baseUrl: $str", str.contains("secret-host.example") || str.contains("supersecret"))
    assertTrue("ConfirmedOptIn.toString keeps non-sensitive fields: $str", str.contains("endpointId=mcp:srv") && str.contains("configRevision=3"))
  }

  // —— Phase 1 硬化：PinnedNetworkDns 單元行為 ——

  @Test fun pinnedDnsStrictFalseCachesAndEmptyThrows() {
    val calls = AtomicInteger(0)
    val delegate = HostResolver { host ->
      calls.incrementAndGet()
      if (host == "empty.example") emptyList() else listOf(addr("203.0.113.50"))
    }
    val nonStrict = PinnedNetworkDns(delegate = delegate, strict = false)
    assertEquals(listOf(addr("203.0.113.50")), nonStrict.lookup("a.example"))
    assertEquals(listOf(addr("203.0.113.50")), nonStrict.lookup("a.example"))
    assertEquals("second lookup must use cached pin", 1, calls.get())

    // 跨寫法正規化快取命中：先查大寫加尾點，再查小寫無尾點必須命中同一 pin 不再碰 delegate。
    calls.set(0)
    assertEquals(listOf(addr("203.0.113.50")), nonStrict.lookup("EXAMPLE.COM."))
    assertEquals(listOf(addr("203.0.113.50")), nonStrict.lookup("example.com"))
    assertEquals("cross-variant lookup must hit cached normalized pin", 1, calls.get())

    try {
      nonStrict.lookup("empty.example")
      fail("empty delegate must throw UnknownHostException")
    } catch (_: UnknownHostException) {
    }
  }

  @Test fun pinnedDnsRejectsEmptyEntriesAcrossInitPinAndForHost() {
    try {
      PinnedNetworkDns(initialPinned = mapOf("example.com" to emptyList()))
      fail("init with empty address list must throw")
    } catch (e: IllegalArgumentException) {
      assertEquals("PINNED_DNS_EMPTY", e.message)
    }

    val dns = PinnedNetworkDns()
    try {
      dns.pin("example.com", emptyList())
      fail("pin with empty address list must throw")
    } catch (e: IllegalArgumentException) {
      assertEquals("PINNED_DNS_EMPTY", e.message)
    }

    try {
      PinnedNetworkDns.forHost("example.com", emptyList())
      fail("forHost with empty address list must throw")
    } catch (e: IllegalArgumentException) {
      assertEquals("PINNED_DNS_EMPTY", e.message)
    }
  }

  @Test fun pinnedDnsUnpinnedHasRebindOrderInsensitiveFailClosedAndDefensiveCopies() {
    val delegateCalls = AtomicInteger(0)
    var nextAnswers = listOf(addr("203.0.113.2"), addr("203.0.113.1"))
    var mode = "normal"
    val delegate = HostResolver { _ ->
      delegateCalls.incrementAndGet()
      when (mode) {
        "throw_unknown" -> throw UnknownHostException("down")
        "throw_runtime" -> throw IllegalStateException("boom")
        "empty" -> emptyList()
        "cancel" -> throw kotlinx.coroutines.CancellationException("cancelled")
        "interrupt_cause" -> throw UnknownHostException("interrupted").apply { initCause(InterruptedException("stop")) }
        else -> nextAnswers
      }
    }

    val inputList = mutableListOf(addr("203.0.113.1"), addr("203.0.113.2"))
    val pinned = PinnedNetworkDns.forHost("example.com", inputList, delegate)
    // 防禦性拷貝：修改原傳入 list 不影響 pin。
    inputList.clear()
    assertEquals(listOf(addr("203.0.113.1"), addr("203.0.113.2")), pinned.pinnedFor("example.com"))

    // 修改回傳 list 不影響內部 pin。
    val out = pinned.lookup("example.com")
    if (out is MutableList<InetAddress>) {
      out.clear()
    }
    assertEquals(listOf(addr("203.0.113.1"), addr("203.0.113.2")), pinned.pinnedFor("example.com"))

    // 未釘住 host
    assertNull(pinned.pinnedFor("unpinned.example"))
    assertNull(pinned.hasRebind("unpinned.example"))
    assertEquals("unpinned hasRebind must not consult delegate", 0, delegateCalls.get())

    // 順序無關（[2, 1] == [1, 2]）
    assertEquals(false, pinned.hasRebind("example.com"))

    // 子集合與超集合皆屬答案變更 → true
    nextAnswers = listOf(addr("203.0.113.1"))
    assertEquals(true, pinned.hasRebind("example.com"))
    nextAnswers = listOf(addr("203.0.113.1"), addr("203.0.113.2"), addr("203.0.113.3"))
    assertEquals(true, pinned.hasRebind("example.com"))

    // 變更答案 → true
    nextAnswers = listOf(addr("203.0.113.3"))
    assertEquals(true, pinned.hasRebind("example.com"))

    // fail-closed：exception / empty → true
    mode = "throw_unknown"
    assertEquals(true, pinned.hasRebind("example.com"))
    mode = "throw_runtime"
    assertEquals(true, pinned.hasRebind("example.com"))
    mode = "empty"
    assertEquals(true, pinned.hasRebind("example.com"))

    // CancellationException / InterruptedException 透傳
    mode = "cancel"
    try {
      pinned.hasRebind("example.com")
      fail("CancellationException must propagate from hasRebind")
    } catch (_: kotlinx.coroutines.CancellationException) {
    }

    mode = "interrupt_cause"
    try {
      pinned.hasRebind("example.com")
      fail("InterruptedException cause must propagate as CancellationException")
    } catch (_: kotlinx.coroutines.CancellationException) {
    }
  }

  @Test fun boundOfRejectsControlCharsInPathQueryAndFragment() {
    val store = NetworkOptInStore(allowTierUpgrade = true)
    val cleanUrl = "https://api.example.com/v1"
    store.confirm("mcp:srv", cleanUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
    val controlUrls = listOf(
      "https://api.example.com/v1\tmore",
      "https://api.example.com/v\t1",
      "https://api.example.com/v1\t",
      "https://api.example.com/v1\u0007",
      "https://api.example.com/v1\nmore",
      "https://api.example.com/v1\rmore",
      "https://api.example.com/v1?q=a\tb",
      "https://api.example.com/v1#frag\nmore",
      "https://api.example.com/\u0000v1",
      "https://api.example.com/\u007fv1",
      "https://api.example.com/\u0085v1",
      "\u0000https://example.com/",
      "https://example.com/\u0007",
      "\u0007ftp://example.com/",
      "\u0000https://api.example.com/v1",
      "https://api.example.com/v1\u0000",
    )
    for (badUrl in controlUrls) {
      try {
        NetworkOptInStore.boundOf("mcp:srv2", badUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
        fail("boundOf must reject control char URL: $badUrl")
      } catch (e: IllegalArgumentException) {
        assertEquals("NETWORK_OPTIN_URL_MALFORMED", e.message)
      }
      try {
        store.confirm("mcp:srv2", badUrl, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 1L)
        fail("confirm must reject control char URL: $badUrl")
      } catch (e: IllegalArgumentException) {
        assertEquals("NETWORK_OPTIN_URL_MALFORMED", e.message)
      }
      assertEquals(
        "malformed control char query must fail closed to HTTPS_ONLY",
        NetworkEgressPolicy.HTTPS_ONLY,
        store.confirmedPolicyFor("mcp:srv", badUrl, 1L),
      )
    }
    // 前後普通空白 ' ' 仍正常修剪匹配已確認 URL。
    assertEquals(
      NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
      store.confirmedPolicyFor("mcp:srv", "  https://api.example.com/v1  ", 1L),
    )
  }

  @Test fun pinnedDnsRejectsBlankHostsAndDuplicateNormalizedKeys() {
    val addrs = listOf(addr("203.0.113.10"))
    for (blankHost in listOf("", "   ", ".", "[]", "[.]", "[   ]", "[ . ]")) {
      try {
        PinnedNetworkDns(initialPinned = mapOf(blankHost to addrs))
        fail("init must reject blank normalized host: '$blankHost'")
      } catch (e: IllegalArgumentException) {
        assertEquals("PINNED_DNS_HOST_BLANK", e.message)
      }
      try {
        PinnedNetworkDns.forHost(blankHost, addrs)
        fail("forHost must reject blank normalized host: '$blankHost'")
      } catch (e: IllegalArgumentException) {
        assertEquals("PINNED_DNS_HOST_BLANK", e.message)
      }
      val dns = PinnedNetworkDns()
      try {
        dns.pin(blankHost, addrs)
        fail("pin must reject blank normalized host: '$blankHost'")
      } catch (e: IllegalArgumentException) {
        assertEquals("PINNED_DNS_HOST_BLANK", e.message)
      }
    }

    // 大小寫／括號／尾點碰撞正規化鍵在 initialPinned 必須拒收
    try {
      PinnedNetworkDns(
        initialPinned = mapOf(
          "EXAMPLE.COM" to listOf(addr("203.0.113.1")),
          "example.com." to listOf(addr("203.0.113.2")),
        ),
      )
      fail("init must reject colliding normalized keys")
    } catch (e: IllegalArgumentException) {
      assertEquals("PINNED_DNS_DUPLICATE_HOST", e.message)
    }
    try {
      PinnedNetworkDns(
        initialPinned = mapOf(
          "[::1]" to listOf(addr("::1")),
          "::1." to listOf(addr("::1")),
        ),
      )
      fail("init must reject colliding IPv6 normalized keys")
    } catch (e: IllegalArgumentException) {
      assertEquals("PINNED_DNS_DUPLICATE_HOST", e.message)
    }

    // non-strict lookup 空正規化 host 直接拋 DNS_PIN_MISS 且不觸 delegate
    val delegateCalls = AtomicInteger(0)
    val nonStrict = PinnedNetworkDns(
      delegate = HostResolver {
        delegateCalls.incrementAndGet()
        addrs
      },
      strict = false,
    )
    for (blankHost in listOf("", "   ", ".", "[]", "[.]", "[   ]", "[ . ]")) {
      try {
        nonStrict.lookup(blankHost)
        fail("non-strict lookup must reject blank normalized host: '$blankHost'")
      } catch (e: UnknownHostException) {
        assertEquals("DNS_PIN_MISS", e.message)
      }
    }
    assertEquals(0, delegateCalls.get())
  }

  @Test fun pinnedDnsPinNormalizesOverwritesCopiesAndBlankHasRebindSkipsDelegate() {
    val delegateCalls = AtomicInteger(0)
    val pinned = PinnedNetworkDns(
      delegate = HostResolver {
        delegateCalls.incrementAndGet()
        listOf(addr("203.0.113.99"))
      },
    )
    // 1) pin 正規化、防禦性拷貝與覆寫既有 pin
    val callerList1 = mutableListOf(addr("203.0.113.10"))
    pinned.pin("Example.COM.", callerList1)
    callerList1.clear()
    assertEquals(listOf(addr("203.0.113.10")), pinned.pinnedFor("example.com"))
    assertEquals(listOf(addr("203.0.113.10")), pinned.lookup("EXAMPLE.COM"))

    val callerList2 = mutableListOf(addr("203.0.113.20"), addr("203.0.113.21"))
    pinned.pin("example.com", callerList2)
    callerList2[0] = addr("203.0.113.250")
    assertEquals(listOf(addr("203.0.113.20"), addr("203.0.113.21")), pinned.pinnedFor("Example.COM."))
    assertEquals(listOf(addr("203.0.113.20"), addr("203.0.113.21")), pinned.lookup("example.com."))

    // 2) blank / "[   ]" / "[.]" / "[ . ]" 的 hasRebind 回 null 且絕不觸碰 delegate
    for (blankHost in listOf("", "   ", ".", "[]", "[.]", "[   ]", "[ . ]")) {
      assertNull("hasRebind('$blankHost') must be null", pinned.hasRebind(blankHost))
    }
    assertEquals("blank hasRebind must not consult delegate", 0, delegateCalls.get())
  }

  @Test fun pinnedDnsNormalizesHostPassedToDelegateInNonStrictLookupAndHasRebind() {
    val seenHosts = mutableListOf<String>()
    val delegate = HostResolver { host ->
      seenHosts += host
      if (host == "::1") listOf(addr("::1")) else listOf(addr("203.0.113.44"))
    }
    val nonStrict = PinnedNetworkDns(delegate = delegate, strict = false)
    assertEquals(listOf(addr("203.0.113.44")), nonStrict.lookup("EXAMPLE.COM."))
    assertEquals(listOf(addr("::1")), nonStrict.lookup("[::1]."))
    assertEquals(listOf("example.com", "::1"), seenHosts)

    seenHosts.clear()
    val strict = PinnedNetworkDns(
      initialPinned = mapOf(
        "example.com" to listOf(addr("203.0.113.44")),
        "::1" to listOf(addr("::1")),
      ),
      delegate = delegate,
      strict = true,
    )
    assertEquals(false, strict.hasRebind("EXAMPLE.COM."))
    assertEquals(false, strict.hasRebind("[::1]."))
    assertEquals(listOf("example.com", "::1"), seenHosts)
  }

  @Test fun pinnedDnsHasRebindMultiHopInterruptAndCyclicCause() {
    val multiHopPinned = PinnedNetworkDns.forHost(
      "example.com",
      listOf(addr("203.0.113.10")),
      delegate = HostResolver {
        val root = InterruptedException("root")
        val mid = IOException("mid").apply { initCause(root) }
        throw UnknownHostException("outer").apply { initCause(mid) }
      },
    )
    try {
      multiHopPinned.hasRebind("example.com")
      fail("multi-hop InterruptedException cause must propagate CancellationException")
    } catch (_: kotlinx.coroutines.CancellationException) {
    }

    class CyclicException(msg: String) : RuntimeException(msg) {
      var loopCause: Throwable? = null
      override val cause: Throwable? get() = loopCause
    }
    val cyclicPinned = PinnedNetworkDns.forHost(
      "example.com",
      listOf(addr("203.0.113.10")),
      delegate = HostResolver {
        val e1 = CyclicException("c1")
        val e2 = CyclicException("c2")
        e1.loopCause = e2
        e2.loopCause = e1
        throw e1
      },
    )
    assertEquals(true, cyclicPinned.hasRebind("example.com"))

    // delegate 拋純 UnknownHostException（無 InterruptedException cause）但執行緒已帶中斷旗標：仍須透傳 CancellationException
    val plainInterruptPinned = PinnedNetworkDns.forHost(
      "example.com",
      listOf(addr("203.0.113.10")),
      delegate = HostResolver { host ->
        Thread.currentThread().interrupt()
        throw UnknownHostException(host)
      },
    )
    try {
      try {
        plainInterruptPinned.hasRebind("example.com")
        fail("plain UnknownHostException while interrupted must propagate CancellationException from hasRebind")
      } catch (_: kotlinx.coroutines.CancellationException) {
      }
      assertTrue(Thread.interrupted())
    } finally {
      Thread.interrupted()
    }
  }
}
