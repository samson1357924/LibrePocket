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
}
