package dev.librepocket.policy

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S1 (Issue #135 A1): [NetworkPolicy] 核心測試。
 *
 * 全部使用合成位址與 fake DNS（[HostResolver] 注入），零真實連線、零真實
 * DNS：`203.0.113.0/24`（TEST-NET-3）扮演公網，RFC1918/迴環/特殊位址扮演
 * 私有與永遠阻擋類。Fail-closed：任何未預期的解析結果都必須是拒絕。
 */
class NetworkPolicyTest {

  private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

  /** Fake DNS seam：精確表查詢，其餘一律拋 [UnknownHostException]（如 NXDOMAIN）。 */
  private fun fakeDns(vararg entries: Pair<String, List<String>>): HostResolver {
    val table = entries.toMap()
    return HostResolver { host ->
      table[host]?.map(::addr) ?: throw UnknownHostException(host)
    }
  }

  /** 此 resolver 被呼叫即代表政策洩漏（onion 不得查 DNS）。 */
  private val neverResolve = HostResolver { host ->
    fail("DNS must not be consulted for host: $host")
    throw UnknownHostException(host)
  }

  private fun assertDenied(code: NetworkDenyCode, eval: NetworkEvaluation) {
    assertFalse("expected deny, got allow: $eval", eval.allowed)
    assertEquals(code, eval.denyCode)
  }

  /**
   * 單執行緒執行器：在 `command.run()` 返回當下（同一 worker 執行緒、`ThreadPoolExecutor.runWorker()`
   * 做任何後續處理之前）立即記錄 `Thread.currentThread().isInterrupted` 至 [lastPostRunInterrupted]，
   * 隨後補清中斷旗標以確保測試斷言具備非空虛（non-vacuous）驗證力。
   */
  private class InterruptRecordingExecutor(threadName: String) : AbstractExecutorService() {
    val lastPostRunInterrupted = AtomicBoolean(true)
    @Volatile private var nextPostRunLatch = CountDownLatch(0)
    private val delegate = ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.SECONDS,
      ArrayBlockingQueue(4),
      ThreadFactory { r -> Thread(r, threadName).apply { isDaemon = true } },
    )

    fun resetPostRunLatch(): CountDownLatch {
      val latch = CountDownLatch(1)
      nextPostRunLatch = latch
      return latch
    }

    override fun shutdown() = delegate.shutdown()
    override fun shutdownNow(): List<Runnable> = delegate.shutdownNow()
    override fun isShutdown() = delegate.isShutdown
    override fun isTerminated() = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
    override fun execute(command: Runnable) {
      val latch = nextPostRunLatch
      delegate.execute {
        try {
          command.run()
        } finally {
          lastPostRunInterrupted.set(Thread.currentThread().isInterrupted)
          Thread.interrupted()
          latch.countDown()
        }
      }
    }
  }

  // —— 預設 deny-all ——

  @Test fun optInDefaultsToHttpsOnly() {
    val optIn = EndpointNetworkOptIn()
    assertEquals(NetworkEgressPolicy.HTTPS_ONLY, optIn.policy)
    assertFalse(optIn.allowsPrivateAddresses)
    assertFalse(optIn.allowsCleartext)
  }

  @Test fun publicHttpsAllowedByDefault() {
    val eval = NetworkPolicy.evaluateUrl(
      "https://api.example.com/v1",
      resolver = fakeDns("api.example.com" to listOf("203.0.113.10")),
    )
    assertTrue("expected allow: $eval", eval.allowed)
    assertNull(eval.denyCode)
    assertEquals(AddressClass.PUBLIC, eval.addressClass)
  }

  @Test fun privateLiteralDeniedByDefault() {
    assertDenied(
      NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("https://192.168.1.20:8080/", resolver = neverResolve),
    )
    assertDenied(
      NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("https://10.0.0.5/", resolver = neverResolve),
    )
  }

  // —— Issue #135 驗收：10.evil.com 誤放行必須修 ——
  //
  // 舊 `validateBaseUrl` 以 `host.startsWith("10.")` 判定，把 DNS 名
  // `10.evil.com` 誤判為 10/8 而放行 http。NetworkPolicy 永遠以解析後
  // 位元組分類，不做字串前綴比對。

  @Test fun tenEvilComIsNotPrivate() {
    val dns = fakeDns("10.evil.com" to listOf("203.0.113.7"))
    // 公網位址走 http：永遠拒（opt-in 也打不開 public http，見 non-goal）。
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://10.evil.com/v1", resolver = dns),
    )
    // 同一 host 走 https：公網 https 預設放行，證明它沒被當成 10/8。
    val https = NetworkPolicy.evaluateUrl("https://10.evil.com/v1", resolver = dns)
    assertTrue("expected allow: $https", https.allowed)
    assertEquals(AddressClass.PUBLIC, https.addressClass)
  }

  @Test fun dottedEvilSiblingIsNotPrivate() {
    val dns = fakeDns(
      "192.168.evil.com" to listOf("203.0.113.8"),
      "172.16.evil.com" to listOf("203.0.113.9"),
    )
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://192.168.evil.com/", resolver = dns),
    )
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://172.16.evil.com/", resolver = dns),
    )
  }

  // —— 字面位址分類 ——

  @Test fun loopbackClassification() {
    assertEquals(AddressClass.LOOPBACK, NetworkPolicy.classifyAddress(addr("127.0.0.1")))
    assertEquals(AddressClass.LOOPBACK, NetworkPolicy.classifyAddress(addr("127.0.0.2")))
    assertEquals(AddressClass.LOOPBACK, NetworkPolicy.classifyAddress(addr("::1")))
    // 迴環預設拒；開 opt-in 後 http 可達（S2 才接線，這裡只驗決策）。
    assertDenied(
      NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("https://127.0.0.1:8080/", resolver = neverResolve),
    )
    val allowed = NetworkPolicy.evaluateUrl(
      "http://127.0.0.1:8080/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
      neverResolve,
    )
    assertTrue("expected allow: $allowed", allowed.allowed)
  }

  @Test fun localhostNameResolvesThenClassified() {
    val dns = fakeDns("localhost" to listOf("127.0.0.1"))
    assertDenied(
      NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("https://localhost:8080/", resolver = dns),
    )
    val allowed = NetworkPolicy.evaluateUrl(
      "http://localhost:8080/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
      dns,
    )
    assertTrue("expected allow: $allowed", allowed.allowed)
    assertEquals(AddressClass.LOOPBACK, allowed.addressClass)
  }

  @Test fun rfc1918Boundaries() {
    val private = listOf("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255")
    for (ip in private) assertEquals(ip, AddressClass.PRIVATE, NetworkPolicy.classifyAddress(addr(ip)))
    val public = listOf("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "8.8.8.8")
    for (ip in public) assertEquals(ip, AddressClass.PUBLIC, NetworkPolicy.classifyAddress(addr(ip)))
  }

  @Test fun ulaIsPrivate() {
    assertEquals(AddressClass.PRIVATE, NetworkPolicy.classifyAddress(addr("fd00::1")))
    assertEquals(AddressClass.PRIVATE, NetworkPolicy.classifyAddress(addr("fc00::1234")))
  }

  // —— 永遠阻擋（opt-in 也打不開） ——

  @Test fun metadataAlwaysBlocked() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    for (ip in listOf("169.254.169.254", "169.254.169.253", "100.100.100.200")) {
      assertEquals(ip, AddressClass.METADATA, NetworkPolicy.classifyAddress(addr(ip)))
      assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://$ip/", optIn, neverResolve))
      assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://$ip/", optIn, neverResolve))
    }
  }

  @Test fun linkLocalAlwaysBlocked() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    assertEquals(AddressClass.LINK_LOCAL, NetworkPolicy.classifyAddress(addr("169.254.10.20")))
    assertEquals(AddressClass.LINK_LOCAL, NetworkPolicy.classifyAddress(addr("fe80::1")))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://169.254.10.20/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[fe80::1]/", optIn, neverResolve))
  }

  @Test fun cgnatAlwaysBlocked() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    assertEquals(AddressClass.CGNAT, NetworkPolicy.classifyAddress(addr("100.64.0.1")))
    assertEquals(AddressClass.CGNAT, NetworkPolicy.classifyAddress(addr("100.127.255.255")))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://100.64.0.1/", optIn, neverResolve))
    // 範圍邊界外是公網。
    assertEquals(AddressClass.PUBLIC, NetworkPolicy.classifyAddress(addr("100.63.255.255")))
    assertEquals(AddressClass.PUBLIC, NetworkPolicy.classifyAddress(addr("100.128.0.1")))
  }

  @Test fun ipv4MappedAlwaysBlocked() {
    // classic bypass 企圖：::ffff:127.0.0.1 / ::ffff:10.x 企圖繞過字串檢查。
    // 已驗證平台事實（2026-11-04）：`HttpUrl.host`、`InetAddress.getByName`、
    // `InetAddress.getByAddress` 都會把 mapped 形正規化成內嵌 v4（`[::ffff:10.0.0.1]`
    // → host `10.0.0.1`），所以 URL 路徑看到的永遠是真實位元組，不可能把 mapped
    // 私有誤判成 PUBLIC。16-byte 形仍可經 `Inet6Address.getByAddress` 抵達
    // （如自訂 Dns 回傳），位元組分類器對其回 IPV4_MAPPED，永遠阻擋。
    for (tail in listOf(byteArrayOf(10, 0, 0, 1), byteArrayOf(127, 0, 0, 1), byteArrayOf(8, 8, 8, 8))) {
      val raw = ByteArray(10) + byteArrayOf(0xff.toByte(), 0xff.toByte()) + tail
      val mapped = java.net.Inet6Address.getByAddress(null, raw, 0)
      assertEquals(AddressClass.IPV4_MAPPED, NetworkPolicy.classifyAddress(mapped))
    }
    // URL 記法經平台正規化後等價於內嵌 v4：私有預設拒、公網放行。
    for (ip in listOf("10.0.0.1", "127.0.0.1")) {
      val v6 = "0:0:0:0:0:ffff:$ip"
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[$v6]/", resolver = neverResolve))
    }
    // mapped 記法與其 unwrap 後的字面決策等價（同拒、同放），無繞過亦無誤殺。
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://10.0.0.1/", resolver = neverResolve))
    val mappedPrivate = NetworkPolicy.evaluateUrl("https://[::ffff:10.0.0.1]/", resolver = neverResolve)
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, mappedPrivate)
    assertEquals(AddressClass.PRIVATE, mappedPrivate.addressClass)
    val mappedPublic = NetworkPolicy.evaluateUrl("https://[::ffff:8.8.8.8]/", resolver = neverResolve)
    assertTrue("expected allow: $mappedPublic", mappedPublic.allowed)
    assertEquals(AddressClass.PUBLIC, mappedPublic.addressClass)
    // opt-in 下 mapped 私有與字面私有同等放行（皆為同一主機）。
    val mappedOptIn = NetworkPolicy.evaluateUrl(
      "https://[::ffff:10.0.0.1]/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
      neverResolve,
    )
    assertTrue("expected allow: $mappedOptIn", mappedOptIn.allowed)
  }

  @Test fun multicastAndUnspecifiedBlocked() {
    assertEquals(AddressClass.MULTICAST, NetworkPolicy.classifyAddress(addr("224.0.0.1")))
    assertEquals(AddressClass.MULTICAST, NetworkPolicy.classifyAddress(addr("ff02::1")))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("0.0.0.0")))
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://224.0.0.1/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://0.0.0.0/", optIn, neverResolve))
  }

  @Test fun userinfoAlwaysBlocked() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    assertDenied(NetworkDenyCode.USERINFO_BLOCKED, NetworkPolicy.evaluateUrl("http://user:pass@127.0.0.1:8080/", optIn, neverResolve))
    assertDenied(
      NetworkDenyCode.USERINFO_BLOCKED,
      NetworkPolicy.evaluateUrl("https://user@example.com/", resolver = fakeDns("example.com" to listOf("203.0.113.1"))),
    )
  }

  @Test fun onionRejectedWithoutDns() {
    // onion 不得查 DNS（neverResolve 被呼叫即測試失敗），直接 INVALID_HOST。
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("http://secret.onion/", resolver = neverResolve))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://secret.onion/", resolver = neverResolve))
  }

  @Test fun invalidHostsFailClosed() {
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("not a url", resolver = neverResolve))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://", resolver = neverResolve))
  }

  @Test fun unsupportedSchemeHasOwnCode() {
    assertDenied(
      NetworkDenyCode.UNSUPPORTED_SCHEME,
      NetworkPolicy.evaluateUrl("ftp://example.com/file", resolver = neverResolve),
    )
  }

  @Test fun unresolvableFailsClosed() {
    val dns = fakeDns() // 空表：任何查詢都 NXDOMAIN
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://nonexistent.invalid/", resolver = dns))
  }

  // —— fake DNS：公網名解析到 10.x ——

  @Test fun publicNameResolvingToPrivateNeedsOptIn() {
    val dns = fakeDns("example.com" to listOf("10.1.2.3"))
    // 未開 opt-in：拒，零連線（純決策，resolver 之外不碰網路）。
    val denied = NetworkPolicy.evaluateUrl("https://example.com/", resolver = dns)
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, denied)
    assertEquals(AddressClass.PRIVATE, denied.addressClass)
    // 開 ALLOW_PRIVATE_DNS：https 可達。
    val allowed = NetworkPolicy.evaluateUrl(
      "https://example.com/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
      dns,
    )
    assertTrue("expected allow: $allowed", allowed.allowed)
    // 同一情境走 http：仍需 cleartext tier。
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://example.com/", EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS), dns),
    )
    val clearAllowed = NetworkPolicy.evaluateUrl(
      "http://example.com/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
      dns,
    )
    assertTrue("expected allow: $clearAllowed", clearAllowed.allowed)
  }

  @Test fun mixedDnsAnswersFailClosedByDefault() {
    // 公私混答（如 DNS rebind）：預設拒；opt-in 下才放行（真正 pinning 見 S2）。
    val dns = fakeDns("flaky.example" to listOf("203.0.113.5", "10.9.9.9"))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://flaky.example/", resolver = dns))
    val allowed = NetworkPolicy.evaluateUrl(
      "https://flaky.example/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
      dns,
    )
    assertTrue("expected allow: $allowed", allowed.allowed)
    assertEquals(2, allowed.resolvedAddresses.size)
  }

  @Test fun httpMixedPublicLoopbackDeniedBothOrders() {
    // Phase A P1 BLOCK：混合 DNS（PUBLIC+LOOPBACK）經 merge 得 LOOPBACK，
    // http + ALLOW_CLEARTEXT_PRIVATE 不得放行——逐 IP 檢查，整組拒（順序無關）。
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    for (answers in listOf(
      listOf("203.0.113.5", "127.0.0.1"),
      listOf("127.0.0.1", "203.0.113.5"),
    )) {
      val dns = fakeDns("localhost" to answers)
      val eval = NetworkPolicy.evaluateUrl("http://localhost:8080/v1", optIn, dns)
      assertDenied(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, eval)
      assertEquals(2, eval.resolvedAddresses.size)
    }
    // PUBLIC+PRIVATE 混答同樣整組拒。
    val mixedPrivate = fakeDns("flaky.example" to listOf("203.0.113.5", "10.9.9.9"))
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://flaky.example:8080/v1", optIn, mixedPrivate),
    )
    // 對照組：全 LOOPBACK http + 同 opt-in 仍放行（防過度收緊）。
    val loopbackOnly = fakeDns("localhost" to listOf("127.0.0.1"))
    val allowed = NetworkPolicy.evaluateUrl("http://localhost:8080/v1", optIn, loopbackOnly)
    assertTrue("expected allow: $allowed", allowed.allowed)
    assertEquals(AddressClass.LOOPBACK, allowed.addressClass)
  }

  @Test fun publicHttpNeverOpensEvenWithOptIn() {
    val dns = fakeDns("api.example.com" to listOf("203.0.113.10"))
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    assertDenied(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, NetworkPolicy.evaluateUrl("http://api.example.com/", optIn, dns))
  }

  @Test fun redirectCodeReserved() {
    // REDIRECT_BLOCKED 為錯誤分類保留（執行層 0-redirect 見 S2/A4）；此處只驗枚舉存在。
    assertEquals("REDIRECT_BLOCKED", NetworkDenyCode.REDIRECT_BLOCKED.name)
  }

  // —— P0-1：前導零八進位／非正規字面一律拒 ——

  @Test fun leadingZeroOctalRejected() {
    // 前導零疑似八進位，parseLiteralIp 直接拒，不猜測基底。
    assertNull(NetworkPolicy.parseLiteralIp("0177.0.0.1"))
    assertNull(NetworkPolicy.parseLiteralIp("010.10.10.10"))
    assertNull(NetworkPolicy.parseLiteralIp("01.02.03.04"))
    // evaluateUrl 走 fail-closed（空 fake DNS，字面或 DNS 路徑皆拒）。
    val dns = fakeDns()
    for (url in listOf("https://0177.0.0.1/", "https://010.10.10.10/", "http://0177.0.0.1/")) {
      assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl(url, resolver = dns))
    }
  }

  @Test fun hexAndSingleNumberRejected() {
    assertNull(NetworkPolicy.parseLiteralIp("0x7f.0.0.1"))
    assertNull(NetworkPolicy.parseLiteralIp("2130706433"))
    val dns = fakeDns()
    for (url in listOf("https://0x7f.0.0.1/", "https://2130706433/")) {
      assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl(url, resolver = dns))
    }
  }

  // —— P0-2：resolver 任何例外一律 fail-closed ——

  @Test fun resolverRuntimeExceptionFailsClosed() {
    val boom: HostResolver = HostResolver { throw IllegalStateException("boom") }
    assertNull(NetworkPolicy.resolveHost("example.com", boom))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://example.com/", resolver = boom))
    val io: HostResolver = HostResolver { throw java.io.IOException("dns down") }
    assertNull(NetworkPolicy.resolveHost("example.com", io))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://example.com/", resolver = io))
  }

  // —— P1-1：非字面 IPv6 不觸 getByName／DNS ——

  @Test fun nonLiteralV6DoesNotTouchDns() {
    // 非字面含冒號（如 foo:bar）經預檢直接拒，不呼叫 getByName、不查 DNS。
    assertNull(NetworkPolicy.parseLiteralIp("foo:bar"))
    // neverResolve 被呼叫即失敗：證明零 DNS 洩漏。
    assertNull(NetworkPolicy.resolveHost("foo:bar", neverResolve))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://[foo:bar]/", resolver = neverResolve))
  }

  // —— P1-3：authority 先剝 fragment ——

  @Test fun userinfoEmptyAndFragment() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    // 空 userinfo 仍擋。
    assertDenied(NetworkDenyCode.USERINFO_BLOCKED, NetworkPolicy.evaluateUrl("http://@127.0.0.1/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.USERINFO_BLOCKED, NetworkPolicy.evaluateUrl("http://@example.com/", optIn, neverResolve))
    // @ 僅在 fragment：不誤殺，公網字面 https 正常放行。
    val frag = NetworkPolicy.evaluateUrl("https://203.0.113.10#@evil", resolver = neverResolve)
    assertTrue("expected allow: $frag", frag.allowed)
  }

  // —— 追加：metadata 擴充／UNSPECIFIED／混答／矩陣 ——

  @Test fun metadataExtendedAndUnspecifiedV6() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    // 169.254.169.123（GCE）與 fd00:ec2::254（AWS IPv6）皆 METADATA，opt-in 亦拒。
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(addr("169.254.169.123")))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://169.254.169.123/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://169.254.169.123/", optIn, neverResolve))
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(addr("fd00:ec2::254")))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[fd00:ec2::254]/", optIn, neverResolve))
    // :: 與 0.0.0.0 同屬 UNSPECIFIED，opt-in 亦拒。
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("::")))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[::]/", optIn, neverResolve))
  }

  @Test fun metadataMixedAnswersAlwaysBlocked() {
    // METADATA 混答 opt-in 亦拒（最嚴格者勝出）。
    val dns = fakeDns("meta.example" to listOf("203.0.113.5", "169.254.169.254"))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://meta.example/", resolver = dns))
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://meta.example/", optIn, dns))
  }

  @Test fun allowPrivateDnsLiteralHttpsMatrix() {
    // ALLOW_PRIVATE_DNS × 字面私有 https：預設拒、opt-in 放行；http 仍須 cleartext tier。
    val literals = listOf("https://10.0.0.5/", "https://192.168.1.20/", "https://172.16.5.4/", "https://127.0.0.1/", "https://[fd00::1]/")
    for (url in literals) {
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl(url, resolver = neverResolve))
      val allowed = NetworkPolicy.evaluateUrl(url, EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS), neverResolve)
      assertTrue("expected allow for $url: $allowed", allowed.allowed)
    }
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://192.168.1.20/", EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS), neverResolve),
    )
  }

  // —— 補釘：保留段／內嵌 v4／括號正規化（review MAJOR-2） ——

  @Test fun reservedV4IsUnspecifiedAlwaysBlocked() {
    // 0/8、240/4、broadcast：isAnyLocalAddress 漏判，此處位元組精確補擋，opt-in 也打不開。
    for (ip in listOf("0.1.2.3", "240.0.0.1", "255.255.255.255")) {
      assertEquals(ip, AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr(ip)))
    }
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    for (ip in listOf("0.1.2.3", "240.0.0.1", "255.255.255.255")) {
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://$ip/", resolver = neverResolve))
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://$ip/", optIn, neverResolve))
    }
  }

  @Test fun nat64EmbeddedPrivateDecision() {
    // 64:ff9b::/96 為 IPv4-embedded 轉換位址：內嵌 v4 不得授權為 LOOPBACK/PRIVATE/PUBLIC。
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("64:ff9b::c0a8:101")))
    assertDenied(
      NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("https://[64:ff9b::c0a8:101]/", resolver = neverResolve),
    )
    val deniedWithOptIn = NetworkPolicy.evaluateUrl(
      "https://[64:ff9b::c0a8:101]/",
      EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
      neverResolve,
    )
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, deniedWithOptIn)
    assertEquals(AddressClass.UNSPECIFIED, deniedWithOptIn.addressClass)
  }

  @Test fun ipv4MappedLoopbackStaysMapped() {
    // 16-byte ::ffff:127.0.0.1 經位元組分類仍為 IPV4_MAPPED（永遠阻擋），不因內嵌 v4 是迴環而降級為 LOOPBACK。
    val raw = ByteArray(10) + byteArrayOf(0xff.toByte(), 0xff.toByte()) + byteArrayOf(127, 0, 0, 1)
    val mapped = java.net.Inet6Address.getByAddress(null, raw, 0)
    assertEquals(AddressClass.IPV4_MAPPED, NetworkPolicy.classifyAddress(mapped))
  }

  // —— Phase4：IDN/大小寫/尾點/形變 host（確定性、零真網） ——

  @Test fun onionUppercaseNeverResolves() {
    // 大小寫形變仍是 onion：lowercase 後擋下，neverResolve 被呼叫即失敗。
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("http://SECRET.ONION/", resolver = neverResolve))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://SECRET.ONION/", resolver = neverResolve))
  }

  @Test fun onionTrailingDotNeverResolves() {
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("http://secret.onion./", resolver = neverResolve))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://secret.onion./", resolver = neverResolve))
  }

  @Test fun uppercasePublicNameAllows() {
    // HttpUrl 視角 host 已小寫；resolveHost 另做 lowercase，fake 表只記小寫即可。
    val dns = fakeDns("example.com" to listOf("203.0.113.21"))
    val eval = NetworkPolicy.evaluateUrl("https://EXAMPLE.COM/v1", resolver = dns)
    assertTrue("expected allow: $eval", eval.allowed)
    assertEquals(AddressClass.PUBLIC, eval.addressClass)
  }

  @Test fun trailingDotPublicNameAllows() {
    // 政策層 resolveHost 去尾點（與 opt-in 綁定層保留尾點不同，見 NetworkOptInStore）。
    val dns = fakeDns("example.com" to listOf("203.0.113.22"))
    val eval = NetworkPolicy.evaluateUrl("https://example.com./v1", resolver = dns)
    assertTrue("expected allow: $eval", eval.allowed)
    assertEquals(AddressClass.PUBLIC, eval.addressClass)
  }

  @Test fun underscoreLabelAllows() {
    // isValidDnsName 允底線（如 _dmarc）：fake DNS 回公網即放行。
    val dns = fakeDns("_dmarc.example.com" to listOf("203.0.113.23"))
    val eval = NetworkPolicy.evaluateUrl("https://_dmarc.example.com/", resolver = dns)
    assertTrue("expected allow: $eval", eval.allowed)
    assertEquals(AddressClass.PUBLIC, eval.addressClass)
  }

  @Test fun overlongLabelFailsClosedWithoutDns() {
    // 單 label 64 字元（>63）非法，查 DNS 前就拒。
    val longLabel = "a".repeat(64)
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://$longLabel.example/", resolver = neverResolve))
  }

  @Test fun userinfoWithCredentialsBlocked() {
    assertDenied(NetworkDenyCode.USERINFO_BLOCKED, NetworkPolicy.evaluateUrl("https://user:pass@example.com/", resolver = neverResolve))
  }

  @Test fun ftpSchemeUnsupported() {
    assertDenied(NetworkDenyCode.UNSUPPORTED_SCHEME, NetworkPolicy.evaluateUrl("ftp://example.com/file", resolver = neverResolve))
  }

  @Test fun httpUnderNonCleartextOptInDoesNotResolveDns() {
    // WARN-05：預設及 ALLOW_PRIVATE_DNS（皆不開明文）下，http 請求在 DNS 前即短路拒絕（零 DNS 洩漏）。
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://example.com/", optIn = EndpointNetworkOptIn(), resolver = neverResolve),
    )
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl(
        "http://example.com/",
        optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
        resolver = neverResolve,
      ),
    )
  }

  @Test fun invalidIpv4LiteralDoesNotTouchDns() {
    // IPv4 形狀但數值超範圍／前導零字面：resolveHost 直接拒絕（含 http），不當 DNS 查詢洩漏。
    for (bad in listOf("https://999.1.1.1/", "http://999.1.1.1/", "https://0177.0.0.1/", "http://0177.0.0.1/")) {
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl(bad, resolver = neverResolve),
      )
    }
  }

  @Test fun backslashAndMalformedSchemeDelimitersBlockedWithoutDns() {
    // 反斜線 host 混淆與單/三斜線 scheme 分隔符繞過一律在 DNS 前 fail-closed。
    assertDenied(
      NetworkDenyCode.USERINFO_BLOCKED,
      NetworkPolicy.evaluateUrl("https://evil.example\\@trusted.example/", resolver = neverResolve),
    )
    assertDenied(
      NetworkDenyCode.USERINFO_BLOCKED,
      NetworkPolicy.evaluateUrl("http://evil.example\\trusted.example/", resolver = neverResolve),
    )
    for (malformed in listOf(
      "https:/evil.example\\@trusted.example/",
      "https:///evil.example\\@trusted.example/",
      "https:/@trusted.example/",
      "https:/evil.example\\@trusted.example?x=://good.com",
    )) {
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl(malformed, resolver = neverResolve),
      )
    }
  }

  // —— Phase 1 硬化：IPv6 字面不漏 OS DNS / 非正規 IPv4 / 空白 authority / IPv4vsIPv6 對稱 / 特殊段 / preflight / 脫敏 ——

  @Test fun malformedColonIpv6LiteralsDoNotTouchDns() {
    val malformed = listOf(
      "1:2",
      "dead:beef",
      ":::",
      "1::2::3",
      "1:2:3:4:5:6:7::8",
      "1:2:3:4:5:6:7:8:9",
      ":1:2",
      "1:2:",
      "12345::1",
      "::0177.0.0.1",
      "::999.0.0.1",
      "::ffff:1.2.3.4:5",
      "1:2:3:4:5:6:7:1.2.3.4",
      ":1::2",
      "1::2:",
      "1.2.3.4::1",
      "1:2:3:4:5:6::1.2.3.4",
      "::1.2.3",
      "::1.2.3.4.5",
      "[::1]",
      "fe80::1%eth0",
      "0000:0000:0000:0000:0000:ffff:192.168.100.2280",
      "2001:" + "0000:".repeat(10) + "1",
    )
    for (bad in malformed) {
      assertNull("parseLiteralIp($bad) must be null", NetworkPolicy.parseLiteralIp(bad))
      assertNull("resolveHost($bad) must not touch DNS", NetworkPolicy.resolveHost(bad, neverResolve))
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl("https://[$bad]/", resolver = neverResolve),
      )
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl("http://[$bad]/", resolver = neverResolve),
      )
    }
  }

  @Test fun pureKotlinIpv6LiteralParserAcceptsValidFormsWithoutDns() {
    val validCases = listOf(
      "::" to byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
      "::1" to byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
      "fe80::" to byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
      "2001:db8::1" to byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
      "2001:DB8::A:B" to byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x0a, 0, 0x0b),
      "1:2:3:4:5:6::8" to byteArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 0, 0, 8),
      "1:2:3:4:5:6:7::" to byteArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 0),
      "2001:0db8:0000:0000:0000:ff00:0042:8329" to byteArrayOf(
        0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0, 0, 0x42, 0x83.toByte(), 0x29,
      ),
      "0:0:0:0:0:0:0:1" to byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
      "64:ff9b::192.0.2.1" to byteArrayOf(
        0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0xc0.toByte(), 0, 2, 1,
      ),
      "::192.0.2.1" to byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xc0.toByte(), 0, 2, 1),
      "1:2:3:4:5:6:192.0.2.1" to byteArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0xc0.toByte(), 0, 2, 1),
      "1:2:3:4::5:192.0.2.1" to byteArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 0, 0, 5, 0xc0.toByte(), 0, 2, 1),
    )
    for ((literal, expectedBytes) in validCases) {
      val parsed = NetworkPolicy.parseLiteralIp(literal)
      assertNotNull("parseLiteralIp($literal) must succeed", parsed)
      assertArrayEquals("bytes mismatch for $literal", expectedBytes, parsed!!.address)
      assertEquals(listOf(parsed), NetworkPolicy.resolveHost(literal, neverResolve))
    }

    // ::ffff:w.x.y.z 經 InetAddress.getByAddress 正規化為 Inet4Address，與 getByName 一致且零 DNS
    val mapped = NetworkPolicy.parseLiteralIp("::ffff:192.0.2.128")
    assertTrue(mapped is Inet4Address)
    assertArrayEquals(byteArrayOf(0xc0.toByte(), 0, 2, 0x80.toByte()), mapped!!.address)
    val mappedPriv = NetworkPolicy.parseLiteralIp("::ffff:10.0.0.1")
    assertTrue(mappedPriv is Inet4Address)
    assertEquals(AddressClass.PRIVATE, NetworkPolicy.classifyAddress(mappedPriv!!))
  }

  @Test fun nonCanonicalIpv4RejectedPreDnsWithoutTouchingResolver() {
    val nonCanonical = listOf(
      "2130706433",
      "0x7f.0.0.1",
      "0x7f000001",
      "127.1",
      "0177.1",
      "0177.0.0.1",
      "127.0x1",
      "example.123",
      "example.0x7f",
    )
    for (host in nonCanonical) {
      assertNull("parseLiteralIp($host) must be null", NetworkPolicy.parseLiteralIp(host))
      assertNull("resolveHost($host) must not touch DNS", NetworkPolicy.resolveHost(host, neverResolve))
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl("https://$host/", resolver = neverResolve),
      )
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl(
          "http://$host/",
          optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
          resolver = neverResolve,
        ),
      )
    }
    // 正常含數字子網域與合法單段 localhost 不得誤殺。
    val dns = fakeDns(
      "10.evil.com" to listOf("203.0.113.10"),
      "192.168.evil.com" to listOf("203.0.113.11"),
      "_dmarc.example.com" to listOf("203.0.113.12"),
      "123.example.com" to listOf("203.0.113.13"),
      "0x7f.example.com" to listOf("203.0.113.14"),
      "localhost" to listOf("127.0.0.1"),
    )
    for (host in listOf("10.evil.com", "192.168.evil.com", "_dmarc.example.com", "123.example.com", "0x7f.example.com")) {
      val eval = NetworkPolicy.evaluateUrl("https://$host/", resolver = dns)
      assertTrue("normal DNS host $host must still work: $eval", eval.allowed)
    }
    val localAllowed = NetworkPolicy.evaluateUrl(
      "http://localhost/",
      optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
      resolver = dns,
    )
    assertTrue("localhost cleartext must still work: $localAllowed", localAllowed.allowed)
  }

  @Test fun emptyResolverListFailsClosedAsInvalidHost() {
    val emptyResolver = HostResolver { emptyList() }
    assertNull(NetworkPolicy.resolveHost("example.com", emptyResolver))
    assertDenied(
      NetworkDenyCode.INVALID_HOST,
      NetworkPolicy.evaluateUrl("https://example.com/", resolver = emptyResolver),
    )
  }

  @Test fun ipv4AndIpv6LiteralHttpDenyCodesAreSymmetricWithoutDns() {
    val defaultOptIn = EndpointNetworkOptIn()
    assertDenied(
      NetworkDenyCode.METADATA_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("http://169.254.169.254/", defaultOptIn, neverResolve),
    )
    assertDenied(
      NetworkDenyCode.METADATA_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("http://[fd00:ec2::254]/", defaultOptIn, neverResolve),
    )
    assertDenied(
      NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("http://169.254.10.20/", defaultOptIn, neverResolve),
    )
    assertDenied(
      NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("http://[fe80::1]/", defaultOptIn, neverResolve),
    )
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://127.0.0.1/", defaultOptIn, neverResolve),
    )
    assertDenied(
      NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
      NetworkPolicy.evaluateUrl("http://[::1]/", defaultOptIn, neverResolve),
    )
  }

  @Test fun whitespaceAndControlCharsInAuthorityRejectedWithoutDns() {
    for (bad in listOf(
      "https://ex\tample.com/",
      "https://ex\nample.com/",
      "https://ex\rample.com/",
      "https://ex\u0000ample.com/",
      "https://ex ample.com/",
      "https://ex\u00a0ample.com/",
      "https://\u00a0example.com/",
      "https://example.com:80\t80/",
      "ht\ttp://example.com/",
      "https://example.com/a\tb",
      "https://example.com/a\nb",
      "https://example.com/a\rb",
      "https://example.com/?q=1\t2",
      "https://example.com/#frag\nmore",
      "https://example.com/\u0000path",
      "https://example.com/\u007fpath",
      "https://example.com/\u0085path",
      "\u0000https://example.com/",
      "https://example.com/\u0007",
      "\u0007ftp://example.com/",
      "\thttps://example.com/",
      "https://example.com/\t",
      "\nhttps://example.com/",
      "https://example.com/\r",
      "\u007fhttps://example.com/",
      "https://example.com/\u0085",
    )) {
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl(bad, resolver = neverResolve),
      )
      assertFalse("requiresDnsLookup must be false for $bad", NetworkPolicy.requiresDnsLookup(bad))
    }
    // 首尾普通空白 ' ' 仍正常修剪放行（公網 DNS）或回 UNSUPPORTED_SCHEME（ftp）。
    val dns = fakeDns("example.com" to listOf("203.0.113.10"))
    val paddedAllowed = NetworkPolicy.evaluateUrl("  https://example.com/  ", resolver = dns)
    assertTrue("leading/trailing ASCII space must still trim cleanly: $paddedAllowed", paddedAllowed.allowed)
    assertTrue(NetworkPolicy.requiresDnsLookup("  https://example.com/  "))
    assertDenied(
      NetworkDenyCode.UNSUPPORTED_SCHEME,
      NetworkPolicy.evaluateUrl("  ftp://example.com/  ", resolver = neverResolve),
    )
  }

  @Test fun malformedAuthorityBracketsRejectedWithoutDns() {
    val malformedBracketUrls = listOf(
      "https://[::1/",
      "https://::1]/",
      "https://[]/",
      "https://[::1]abc/",
      "https://[::1][::2]/",
      "https://[127.0.0.1]/",
    )
    for (bad in malformedBracketUrls) {
      assertDenied(
        NetworkDenyCode.INVALID_HOST,
        NetworkPolicy.evaluateUrl(bad, resolver = neverResolve),
      )
      assertFalse("requiresDnsLookup must be false for $bad", NetworkPolicy.requiresDnsLookup(bad))
    }
  }

  @Test fun dnsReturning16ByteIpv4MappedBlockedEvenWithOptIn() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    for (tail in listOf(byteArrayOf(127, 0, 0, 1), byteArrayOf(8, 8, 8, 8))) {
      val raw = ByteArray(10) + byteArrayOf(0xff.toByte(), 0xff.toByte()) + tail
      val v6Mapped = Inet6Address.getByAddress(null, raw, 0)
      val dns = HostResolver { listOf(v6Mapped) }
      val eval = NetworkPolicy.evaluateUrl("https://mapped.example/", optIn, dns)
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, eval)
      assertEquals(AddressClass.IPV4_MAPPED, eval.addressClass)
    }
  }

  @Test fun embeddedV4HardeningSiitNat64Local6to4TeredoCompat() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)

    // SIIT IPv4-translated ::ffff:0:0:0/96
    // NAT64 well-known 64:ff9b::/96
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(addr("64:ff9b::169.254.169.254")))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("64:ff9b::127.0.0.1")))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("64:ff9b::10.0.0.1")))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[64:ff9b::169.254.169.254]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[64:ff9b::127.0.0.1]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[64:ff9b::10.0.0.1]/", optIn, neverResolve))
    // public-embedded tunnel 位址同屬轉換/隧道目標，不得授權為 PUBLIC。
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("64:ff9b::8.8.8.8")))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("2002:0808:0808::1")))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[64:ff9b::8.8.8.8]/", EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS), neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[2002:0808:0808::1]/", EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS), neverResolve))

    val siitMeta = Inet6Address.getByName("::ffff:0:169.254.169.254")
    val siitLoopback = Inet6Address.getByName("::ffff:0:127.0.0.1")
    val siitPrivate = Inet6Address.getByName("::ffff:0:10.0.0.1")
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(siitMeta))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(siitLoopback))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(siitPrivate))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[::ffff:0:169.254.169.254]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[::ffff:0:127.0.0.1]/", EndpointNetworkOptIn(), neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[::ffff:0:127.0.0.1]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[::ffff:0:10.0.0.1]/", optIn, neverResolve))

    // RFC 8215 NAT64 Local-Use 64:ff9b:1::/48（Global=False：metadata 優先，其餘一律 UNSPECIFIED 永擋）
    val nat64LocalMeta = Inet6Address.getByName("64:ff9b:1::169.254.169.254")
    val nat64LocalLoop = Inet6Address.getByName("64:ff9b:1::127.0.0.1")
    val nat64LocalSubPriv = Inet6Address.getByName("64:ff9b:1:cafe::10.0.0.1")
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(nat64LocalMeta))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(nat64LocalLoop))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(nat64LocalSubPriv))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[64:ff9b:1::169.254.169.254]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[64:ff9b:1::127.0.0.1]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[64:ff9b:1:cafe::10.0.0.1]/", optIn, neverResolve))

    // 6to4 2002::/16
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(addr("2002:a9fe:a9fe::1")))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("2002:7f00:1::1")))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("2002:0a00:1::1")))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[2002:a9fe:a9fe::1]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[2002:0a00:1::1]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[2002:7f00:1::1]/", optIn, neverResolve))

    // Teredo 2001::/32（含 XOR-obfuscated 169.254.169.254 與 127.0.0.1）
    val teredoMeta = addr("2001:0000:c633:6401:0000:ffff:5601:5601")
    val teredoLoop = addr("2001:0000:c633:6401:0000:ffff:80ff:fffe")
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(teredoMeta))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(teredoLoop))
    assertDenied(
      NetworkDenyCode.METADATA_ADDRESS_BLOCKED,
      NetworkPolicy.evaluateUrl("https://[2001:0000:c633:6401:0000:ffff:5601:5601]/", optIn, neverResolve),
    )
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[2001:0000:c633:6401:0000:ffff:80ff:fffe]/", optIn, neverResolve))

    // compat ::/96（16-byte 及字串形式）
    val compatPrivRaw = ByteArray(12) + byteArrayOf(10, 0, 0, 1)
    val compatMetaRaw = ByteArray(12) + byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(Inet6Address.getByAddress(null, compatPrivRaw, 0)))
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(Inet6Address.getByAddress(null, compatMetaRaw, 0)))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("::10.0.0.1")))
    assertEquals(AddressClass.METADATA, NetworkPolicy.classifyAddress(addr("::169.254.169.254")))
    assertEquals(AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr("::127.0.0.1")))
    assertDenied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[::169.254.169.254]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[::10.0.0.1]/", optIn, neverResolve))
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[::127.0.0.1]/", optIn, neverResolve))
  }

  @Test fun nat64LocalUseRfc8215MetadataOffsetsAndAlwaysBlocked() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)

    // RFC 8215 64:ff9b:1::/48（Global=False）：RFC 6052 全部偏移的 metadata 皆回 METADATA
    val metadataCases = listOf(
      // /48 (b[8]==0): b[6], b[7], b[9], b[10] = 169.254.169.254, tail = 8.8.8.8 / 127.0.0.1
      "64:ff9b:1:a9fe:00a9:fe00:0808:0808",
      "64:ff9b:1:a9fe:00a9:fe00:7f00:0001",
      // /48: 100.100.100.200, tail = 8.8.8.8
      "64:ff9b:1:6464:0064:c800:0808:0808",
      // /56 (b[8]==0): b[7], b[9], b[10], b[11] = 169.254.169.254, tail = 8.8.8.8 / 127.0.0.1
      "64:ff9b:1:00a9:00fe:a9fe:0808:0808",
      "64:ff9b:1:caa9:00fe:a9fe:7f00:0001",
      // /64 (b[8]==0): b[9], b[10], b[11], b[12] = 169.254.169.254 / 100.100.100.200
      "64:ff9b:1:cafe:00a9:fea9:fe00:0001",
      "64:ff9b:1:cafe:00a9:fea9:fe08:0808",
      "64:ff9b:1:cafe:0064:6464:c808:0808",
      // /96: b[12], b[13], b[14], b[15] = 169.254.169.254（含 b[8] != 0）
      "64:ff9b:1::169.254.169.254",
      "64:ff9b:1:cafe:1234:5678:169.254.169.254",
    )
    for (lit in metadataCases) {
      assertEquals(lit, AddressClass.METADATA, NetworkPolicy.classifyAddress(addr(lit)))
      assertDenied(
        NetworkDenyCode.METADATA_ADDRESS_BLOCKED,
        NetworkPolicy.evaluateUrl("https://[$lit]/", optIn, neverResolve),
      )
      assertDenied(
        NetworkDenyCode.METADATA_ADDRESS_BLOCKED,
        NetworkPolicy.evaluateUrl("http://[$lit]/", optIn, neverResolve),
      )
    }

    // 非 metadata 的 64:ff9b:1::/48 一律歸為 UNSPECIFIED（永擋，ALLOW_CLEARTEXT_PRIVATE 也打不開）
    val blockedNonMetadataCases = listOf(
      "64:ff9b:1::127.0.0.1",
      "64:ff9b:1:cafe::10.0.0.1",
      "64:ff9b:1::8.8.8.8",
      "64:ff9b:1:7f00:1::8.8.8.8",
      // b[8] != 0 使 /48、/56、/64 u-bits 不符，仍不得放行
      "64:ff9b:1:a9fe:01a9:fe00:0808:0808",
      "64:ff9b:1:00a9:01fe:a9fe:0808:0808",
      "64:ff9b:1:cafe:01a9:fea9:fe00:0001",
    )
    for (lit in blockedNonMetadataCases) {
      assertEquals(lit, AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr(lit)))
      assertDenied(
        NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
        NetworkPolicy.evaluateUrl("https://[$lit]/", optIn, neverResolve),
      )
      assertDenied(
        NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED,
        NetworkPolicy.evaluateUrl("http://[$lit]/", optIn, neverResolve),
      )
    }
    // 64:ff9b:2::1 不屬 64:ff9b::/96 或 64:ff9b:1::/48，維持 PUBLIC；isReservedV6 邊界外亦維持 PUBLIC。
    for (pub in listOf("64:ff9b:2::1", "100:0:0:1::1", "2001:3::1", "3fff:1000::1")) {
      assertEquals(pub, AddressClass.PUBLIC, NetworkPolicy.classifyAddress(addr(pub)))
    }
  }

  @Test fun specialPurposeReservedAddressesAlwaysBlockedWhileTestNetsStayPublic() {
    val optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
    val reservedV4 = listOf("192.0.0.1", "192.88.99.1", "198.18.0.1", "198.19.255.254")
    for (ip in reservedV4) {
      assertEquals(ip, AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr(ip)))
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://$ip/", optIn, neverResolve))
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://$ip/", optIn, neverResolve))
    }
    val reservedV6 = listOf("100::1", "2001:2::1", "2001:10::1", "2001:20::1", "3fff::1")
    for (ip in reservedV6) {
      assertEquals(ip, AddressClass.UNSPECIFIED, NetworkPolicy.classifyAddress(addr(ip)))
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("https://[$ip]/", optIn, neverResolve))
      assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, NetworkPolicy.evaluateUrl("http://[$ip]/", optIn, neverResolve))
    }
    // 測試佔位合成公網與 isReservedV6 邊界外位址必須維持 PUBLIC。
    for (pub in listOf("192.0.2.1", "198.51.100.1", "203.0.113.1", "2001:db8::1", "100:0:0:1::1", "2001:3::1", "3fff:1000::1")) {
      assertEquals(pub, AddressClass.PUBLIC, NetworkPolicy.classifyAddress(addr(pub)))
    }
  }

  @Test fun evaluationToStringRedactsUrlsAndAddresses() {
    val dns = fakeDns("secret-internal.example" to listOf("10.1.2.3"), "public.example" to listOf("203.0.113.77"))
    val denied = NetworkPolicy.evaluateUrl("https://secret-internal.example/v1?token=secret", resolver = dns)
    val deniedStr = denied.toString()
    assertFalse("toString must not leak IP: $deniedStr", deniedStr.contains("10.1.2.3"))
    assertFalse("toString must not leak host/url: $deniedStr", deniedStr.contains("secret-internal.example") || deniedStr.contains("token=secret"))
    assertTrue("toString keeps summary fields: $deniedStr", deniedStr.contains("addressCount=1"))

    val allowed = NetworkPolicy.evaluateUrl("https://public.example/v1?api_key=abc", resolver = dns)
    val allowedStr = allowed.toString()
    assertFalse("toString must not leak IP: $allowedStr", allowedStr.contains("203.0.113.77"))
    assertFalse("toString must not leak url/query: $allowedStr", allowedStr.contains("public.example") || allowedStr.contains("api_key=abc"))
    assertTrue("toString keeps summary fields: $allowedStr", allowedStr.contains("allowed=true") && allowedStr.contains("addressCount=1"))
  }

  @Test fun requiresDnsLookupMatchesPreDnsGatesAndSkipsExecutor() {
    val defaultOptIn = EndpointNetworkOptIn()
    val cleartextOptIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)

    for (noDns in listOf(
      "ftp://example.com/",
      "not a url",
      "https://",
      "https://user@example.com/",
      "http://example.com/",
      "https://127.0.0.1/",
      "https://[::1]/",
      "https://secret.onion/",
      "https://2130706433/",
      "https://0x7f.0.0.1/",
      "https://127.1/",
      "https://[1:2]/",
      "https://ex\tample.com/",
      "https://bad..example/",
    )) {
      assertFalse("expected zero DNS for $noDns", NetworkPolicy.requiresDnsLookup(noDns, defaultOptIn))
    }
    assertFalse(
      "literal http under ALLOW_CLEARTEXT_PRIVATE needs no DNS",
      NetworkPolicy.requiresDnsLookup("http://127.0.0.1/", cleartextOptIn),
    )
    assertFalse(
      "http under ALLOW_PRIVATE_DNS needs no DNS",
      NetworkPolicy.requiresDnsLookup("http://example.com/", EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS)),
    )
    assertTrue(NetworkPolicy.requiresDnsLookup("https://example.com/", defaultOptIn))
    assertTrue(NetworkPolicy.requiresDnsLookup("https://example.com./", defaultOptIn))
    assertTrue(NetworkPolicy.requiresDnsLookup("http://localhost/", cleartextOptIn))
    assertTrue(NetworkPolicy.requiresDnsLookup("http://example.com/", cleartextOptIn))
  }

  @Test fun preflightClearsWorkerInterruptFlagAfterTimeout() = runBlocking {
    val pool = InterruptRecordingExecutor("test-interrupt-clean")
    try {
      // 預熱唯一 worker 執行緒，避免首次建執行緒排程波動使超時在進 block 前觸發。
      pool.submit {}.get(2, TimeUnit.SECONDS)

      // 1) Blocking preflight timeout + sticky interrupt simulation inside block
      val caughtInterrupt1 = AtomicBoolean(false)
      val workerDone1 = CountDownLatch(1)
      val postRun1 = pool.resetPostRunLatch()
      val out1 = NetworkPolicy.runPreflightBlocking(
        deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250),
        executor = pool,
      ) {
        try {
          Thread.sleep(10_000)
        } catch (_: InterruptedException) {
          caughtInterrupt1.set(true)
          Thread.currentThread().interrupt() // 模擬未清旗標或再中斷
        } finally {
          workerDone1.countDown()
        }
        "late"
      }
      assertEquals(NetworkPolicy.PreflightOutcome.TimedOut, out1)
      assertTrue(workerDone1.await(2, TimeUnit.SECONDS))
      assertTrue("blocking timeout test must actually catch InterruptedException", caughtInterrupt1.get())
      assertTrue(postRun1.await(2, TimeUnit.SECONDS))
      assertFalse(
        "worker interrupt flag must be cleared immediately after command.run() returns",
        pool.lastPostRunInterrupted.get(),
      )

      val flagAfterBlocking = AtomicBoolean(true)
      val out2 = NetworkPolicy.runPreflightBlocking(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
        executor = pool,
      ) {
        flagAfterBlocking.set(Thread.currentThread().isInterrupted)
        NetworkPolicy.evaluateUrl("https://missing.invalid/", resolver = fakeDns())
      }
      assertFalse("worker interrupt flag must be cleared after timeout", flagAfterBlocking.get())
      val completed2 = out2 as NetworkPolicy.PreflightOutcome.Completed
      assertDenied(NetworkDenyCode.INVALID_HOST, completed2.value)

      // 2) Suspend preflight timeout + sticky interrupt simulation inside block
      val caughtInterrupt2 = AtomicBoolean(false)
      val workerDone2 = CountDownLatch(1)
      val postRun3 = pool.resetPostRunLatch()
      val out3 = NetworkPolicy.runPreflightSuspend(
        deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250),
        executor = pool,
      ) {
        try {
          Thread.sleep(10_000)
        } catch (_: InterruptedException) {
          caughtInterrupt2.set(true)
          Thread.currentThread().interrupt()
        } finally {
          workerDone2.countDown()
        }
        "late"
      }
      assertEquals(NetworkPolicy.PreflightOutcome.TimedOut, out3)
      assertTrue(workerDone2.await(2, TimeUnit.SECONDS))
      assertTrue("suspend timeout test must actually catch InterruptedException", caughtInterrupt2.get())
      assertTrue(postRun3.await(2, TimeUnit.SECONDS))
      assertFalse(
        "worker interrupt flag must be cleared immediately after suspend command.run() returns",
        pool.lastPostRunInterrupted.get(),
      )

      val flagAfterSuspend = AtomicBoolean(true)
      val out4 = NetworkPolicy.runPreflightSuspend(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
        executor = pool,
      ) {
        flagAfterSuspend.set(Thread.currentThread().isInterrupted)
        NetworkPolicy.evaluateUrl("https://missing.invalid/", resolver = fakeDns())
      }
      assertFalse("worker interrupt flag must be cleared after suspend timeout", flagAfterSuspend.get())
      val completed4 = out4 as NetworkPolicy.PreflightOutcome.Completed
      assertDenied(NetworkDenyCode.INVALID_HOST, completed4.value)
    } finally {
      pool.shutdownNow()
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun preflightQueuedOrDelayedExpiredTaskSkipsResolver() = runBlocking {
    val backing = ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.SECONDS,
      ArrayBlockingQueue(4),
      ThreadFactory { r -> Thread(r, "test-expired-queue").apply { isDaemon = true } },
    )
    try {
      val t0 = Long.MAX_VALUE - 500_000_000L
      val deadlineNs = t0 + 1_000_000_000L // 跨 Long.MAX_VALUE 回繞成負值
      val now = AtomicLong(t0)
      val resolverCalls = AtomicInteger(0)
      val countingDns = HostResolver { host ->
        resolverCalls.incrementAndGet()
        throw UnknownHostException(host)
      }

      // 1) Blocking：submit 後且 caller 進入 future.get 前（讀完 waitNs），於執行 command.run() 前推進假時鐘至超時
      val blockingClockCalls = AtomicInteger(0)
      val waitNsRead = CountDownLatch(1)
      val blockingTaskEntered = AtomicBoolean(false)
      val delayedBlockingExecutor: ExecutorService = object : AbstractExecutorService() {
        override fun shutdown() = backing.shutdown()
        override fun shutdownNow() = backing.shutdownNow()
        override fun isShutdown() = backing.isShutdown
        override fun isTerminated() = backing.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = backing.awaitTermination(timeout, unit)
        override fun execute(command: Runnable) {
          backing.execute {
            assertTrue(waitNsRead.await(2, TimeUnit.SECONDS))
            now.set(deadlineNs + 1_000_000L)
            blockingTaskEntered.set(true)
            command.run()
          }
        }
      }
      val blockingClock = {
        val call = blockingClockCalls.incrementAndGet()
        val value = now.get()
        if (call == 2) waitNsRead.countDown()
        value
      }
      val blockingRes = NetworkPolicy.runPreflightBlocking(
        deadlineNs = deadlineNs,
        executor = delayedBlockingExecutor,
        nanoClock = blockingClock,
      ) {
        NetworkPolicy.evaluateUrl("https://example.com/", resolver = countingDns)
      }
      assertEquals(NetworkPolicy.PreflightOutcome.TimedOut, blockingRes)
      assertTrue("blocking delayed wrapper must enter command.run()", blockingTaskEntered.get())
      assertEquals("expired queued blocking task must skip resolver", 0, resolverCalls.get())

      // 2) Suspend：submit 後於 command.run() 前推進假時鐘至超時（同樣跨 Long.MAX_VALUE 回繞）
      now.set(t0)
      val suspendTaskEntered = AtomicBoolean(false)
      val delayedSuspendExecutor: ExecutorService = object : AbstractExecutorService() {
        override fun shutdown() = backing.shutdown()
        override fun shutdownNow() = backing.shutdownNow()
        override fun isShutdown() = backing.isShutdown
        override fun isTerminated() = backing.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = backing.awaitTermination(timeout, unit)
        override fun execute(command: Runnable) {
          backing.execute {
            now.set(deadlineNs + 1_000_000L)
            suspendTaskEntered.set(true)
            command.run()
          }
        }
      }
      val suspendRes = NetworkPolicy.runPreflightSuspend(
        deadlineNs = deadlineNs,
        executor = delayedSuspendExecutor,
        nanoClock = { now.get() },
      ) {
        NetworkPolicy.evaluateUrl("https://example.com/", resolver = countingDns)
      }
      assertEquals(NetworkPolicy.PreflightOutcome.TimedOut, suspendRes)
      assertTrue("suspend delayed wrapper must enter command.run()", suspendTaskEntered.get())
      assertEquals("expired queued suspend task must skip resolver", 0, resolverCalls.get())
    } finally {
      backing.shutdownNow()
      assertTrue(backing.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun preflightOverloadRejectedWithoutOomHangingOrCancellingCaller() = runBlocking {
    val maxPool = 2
    val pool = ThreadPoolExecutor(
      0,
      maxPool,
      30L,
      TimeUnit.SECONDS,
      SynchronousQueue(),
      ThreadFactory { r -> Thread(r, "test-overload").apply { isDaemon = true } },
      ThreadPoolExecutor.AbortPolicy(),
    )
    val release = AtomicBoolean(false)
    val started = CountDownLatch(maxPool)
    val finished = CountDownLatch(maxPool)
    try {
      repeat(maxPool) {
        pool.submit {
          started.countDown()
          while (!release.get()) {
            try {
              Thread.sleep(10)
            } catch (_: InterruptedException) {
              // 模擬不可中斷的原生 getaddrinfo() 阻塞
            }
          }
          finished.countDown()
        }
      }
      assertTrue(started.await(2, TimeUnit.SECONDS))

      val blockingOverload = NetworkPolicy.runPreflightBlocking(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
        executor = pool,
      ) {
        fail("saturated pool must not execute block")
      }
      assertEquals(NetworkPolicy.PreflightOutcome.OverloadRejected, blockingOverload)

      val suspendOverload = NetworkPolicy.runPreflightSuspend(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
        executor = pool,
      ) {
        fail("saturated pool must not execute suspend block")
      }
      assertEquals(NetworkPolicy.PreflightOutcome.OverloadRejected, suspendOverload)
      assertTrue("caller coroutine must stay active after OverloadRejected", coroutineContext.isActive)
    } finally {
      release.set(true)
      finished.await(2, TimeUnit.SECONDS)
      pool.shutdownNow()
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun preflightSuspendParentCancelInterruptsWorkerAndReturnsImmediately() = runBlocking {
    val pool = InterruptRecordingExecutor("test-cancel-immediate")
    val started = CompletableDeferred<Unit>()
    val workerInterrupted = CountDownLatch(1)
    val release = AtomicBoolean(false)
    val workerExited = CountDownLatch(1)
    var caught: Throwable? = null
    try {
      pool.submit {}.get(2, TimeUnit.SECONDS)
      val postRun = pool.resetPostRunLatch()
      val job = launch {
        try {
          NetworkPolicy.runPreflightSuspend(
            deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(30),
            executor = pool,
          ) {
            started.complete(Unit)
            var sawInterrupt = false
            while (!release.get()) {
              if (Thread.currentThread().isInterrupted) {
                sawInterrupt = true
                workerInterrupted.countDown()
              }
              try {
                Thread.sleep(5)
              } catch (_: InterruptedException) {
                sawInterrupt = true
                workerInterrupted.countDown()
              }
            }
            if (sawInterrupt) {
              Thread.currentThread().interrupt()
            }
            workerExited.countDown()
            "done"
          }
        } catch (t: Throwable) {
          caught = t
          throw t
        }
      }
      withTimeout(2_000) {
        started.await()
      }
      withTimeout(1_000) {
        job.cancelAndJoin()
      }
      assertTrue("parent cancel must propagate CancellationException", caught is CancellationException)
      assertTrue("parent cancel must interrupt worker", workerInterrupted.await(2, TimeUnit.SECONDS))

      release.set(true)
      assertTrue(workerExited.await(2, TimeUnit.SECONDS))
      assertTrue(postRun.await(2, TimeUnit.SECONDS))
      assertFalse(
        "worker interrupt flag must be cleared immediately after command.run() returns",
        pool.lastPostRunInterrupted.get(),
      )

      val nextFlag = NetworkPolicy.runPreflightSuspend(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
        executor = pool,
      ) {
        Thread.currentThread().isInterrupted
      }
      assertEquals(NetworkPolicy.PreflightOutcome.Completed(false), nextFlag)
    } finally {
      release.set(true)
      workerExited.await(2, TimeUnit.SECONDS)
      pool.shutdownNow()
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun preflightDefaultExecutorCompletesAllowedAndDeniedEvaluations() = runBlocking {
    val dns = fakeDns("example.com" to listOf("203.0.113.42"), "private.example" to listOf("10.0.0.1"))
    val blockingOut = NetworkPolicy.runPreflightBlocking(
      deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
    ) {
      NetworkPolicy.evaluateUrl("https://example.com/v1", resolver = dns)
    }
    val blockingCompleted = blockingOut as NetworkPolicy.PreflightOutcome.Completed
    assertTrue(blockingCompleted.value.allowed)
    assertEquals(AddressClass.PUBLIC, blockingCompleted.value.addressClass)

    val suspendOut = NetworkPolicy.runPreflightSuspend(
      deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
    ) {
      NetworkPolicy.evaluateUrl("https://private.example/v1", resolver = dns)
    }
    val suspendCompleted = suspendOut as NetworkPolicy.PreflightOutcome.Completed
    assertDenied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, suspendCompleted.value)
  }

  @Test fun preflightBlockingPreInterruptedAndInterruptedDuringGet() {
    val neverSubmitExecutor: ExecutorService = object : AbstractExecutorService() {
      override fun shutdown() {}
      override fun shutdownNow(): List<Runnable> = emptyList()
      override fun isShutdown() = false
      override fun isTerminated() = false
      override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
      override fun execute(command: Runnable) {
        fail("pre-interrupted caller must not submit to executor")
      }
    }
    // 1) Pre-interrupted caller thread
    try {
      Thread.currentThread().interrupt()
      try {
        NetworkPolicy.runPreflightBlocking(
          deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
          executor = neverSubmitExecutor,
        ) {
          fail("pre-interrupted caller must not run block")
        }
        fail("pre-interrupted runPreflightBlocking must throw CancellationException")
      } catch (_: CancellationException) {
      }
      assertTrue("caller interrupt flag must stay set", Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }

    // 2) Caller thread interrupted while waiting in future.get
    val pool = ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.SECONDS,
      ArrayBlockingQueue(4),
      ThreadFactory { r -> Thread(r, "test-get-interrupt").apply { isDaemon = true } },
    )
    val callerThread = Thread.currentThread()
    val blockStarted = CountDownLatch(1)
    val workerInterrupted = CountDownLatch(1)
    val workerExited = CountDownLatch(1)
    val interrupter = Thread {
      if (blockStarted.await(2, TimeUnit.SECONDS)) {
        callerThread.interrupt()
      }
    }
    try {
      interrupter.start()
      try {
        NetworkPolicy.runPreflightBlocking(
          deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
          executor = pool,
        ) {
          blockStarted.countDown()
          try {
            Thread.sleep(10_000)
          } catch (_: InterruptedException) {
            workerInterrupted.countDown()
          } finally {
            workerExited.countDown()
          }
          "late"
        }
        fail("interrupted future.get must throw CancellationException")
      } catch (_: CancellationException) {
      }
      assertTrue("caller interrupt flag must be restored after InterruptedException", Thread.currentThread().isInterrupted)
      Thread.interrupted()
      assertTrue("worker must receive cancel(true) interrupt", workerInterrupted.await(2, TimeUnit.SECONDS))
      assertTrue(workerExited.await(2, TimeUnit.SECONDS))
    } finally {
      Thread.interrupted()
      interrupter.join(2_000)
      assertFalse("interrupter thread must exit", interrupter.isAlive)
      pool.shutdownNow()
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun preflightAlreadyExpiredDeadlineNeverSubmitsToExecutor() = runBlocking {
    val neverSubmitExecutor: ExecutorService = object : AbstractExecutorService() {
      override fun shutdown() {}
      override fun shutdownNow(): List<Runnable> = emptyList()
      override fun isShutdown() = false
      override fun isTerminated() = false
      override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
      override fun execute(command: Runnable) {
        fail("already-expired deadline must not submit to executor")
      }
    }
    val clock = { 1_000L }
    for (deadline in listOf(1_000L, 999L)) {
      assertEquals(
        NetworkPolicy.PreflightOutcome.TimedOut,
        NetworkPolicy.runPreflightBlocking(deadlineNs = deadline, executor = neverSubmitExecutor, nanoClock = clock) {
          fail("must not run")
        },
      )
      assertEquals(
        NetworkPolicy.PreflightOutcome.TimedOut,
        NetworkPolicy.runPreflightSuspend(deadlineNs = deadline, executor = neverSubmitExecutor, nanoClock = clock) {
          fail("must not run")
        },
      )
    }
  }

  @Test fun preflightNanoTimeWrapAroundAcrossLongMaxValueCompletes() = runBlocking {
    val t0 = Long.MAX_VALUE - 500_000_000L
    val deadlineNs = t0 + 1_000_000_000L // Long.MIN_VALUE + 499_999_999L
    val now = AtomicLong(t0)

    val blockingOut = NetworkPolicy.runPreflightBlocking(
      deadlineNs = deadlineNs,
      nanoClock = {
        val current = now.get()
        // 第二次及之後模擬跨越 Long.MAX_VALUE 但仍未逾時
        now.compareAndSet(t0, Long.MIN_VALUE + 100_000_000L)
        current
      },
    ) {
      "wrapped-blocking-ok"
    }
    assertEquals(NetworkPolicy.PreflightOutcome.Completed("wrapped-blocking-ok"), blockingOut)

    now.set(t0)
    val suspendOut = NetworkPolicy.runPreflightSuspend(
      deadlineNs = deadlineNs,
      nanoClock = {
        val current = now.get()
        now.compareAndSet(t0, Long.MIN_VALUE + 100_000_000L)
        current
      },
    ) {
      "wrapped-suspend-ok"
    }
    assertEquals(NetworkPolicy.PreflightOutcome.Completed("wrapped-suspend-ok"), suspendOut)
  }

  @Test fun preflightSuspendMaxRemainingNsDoesNotOverflowTo1ms() = runBlocking {
    val outcome = NetworkPolicy.runPreflightSuspend(
      deadlineNs = Long.MAX_VALUE,
      nanoClock = { 0L },
    ) {
      Thread.sleep(25)
      "no-overflow"
    }
    assertEquals(NetworkPolicy.PreflightOutcome.Completed("no-overflow"), outcome)
  }

  @Test fun preflightPropagatesBlockExceptions() = runBlocking {
    try {
      NetworkPolicy.runPreflightBlocking(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
      ) {
        throw IllegalStateException("blocking-boom")
      }
      fail("blocking exception must propagate")
    } catch (e: IllegalStateException) {
      assertEquals("blocking-boom", e.message)
    }

    try {
      NetworkPolicy.runPreflightSuspend(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
      ) {
        throw IllegalStateException("suspend-boom")
      }
      fail("suspend exception must propagate")
    } catch (e: IllegalStateException) {
      assertEquals("suspend-boom", e.message)
    }

    // worker 內第二通 nanoClock() 拋例外時亦須透過 Result.failure 乾淨傳回，不可掛死協程
    val clockCalls = AtomicInteger(0)
    try {
      withTimeout(2_000) {
        NetworkPolicy.runPreflightSuspend(
          deadlineNs = 1_000_000L,
          nanoClock = {
            if (clockCalls.incrementAndGet() == 1) 0L else throw IllegalStateException("clock-boom")
          },
        ) {
          "never"
        }
      }
      fail("worker nanoClock exception must propagate")
    } catch (e: IllegalStateException) {
      assertEquals("clock-boom", e.message)
    }
  }

  @Test fun preflightSuspendEarlyCancelOrExpiredCleanupClearsWorkerInterruptFlag() = runBlocking {
    val pool = InterruptRecordingExecutor("test-early-cancel-clean")
    val insideTask = CompletableDeferred<Unit>()
    val releaseClock = CountDownLatch(1)
    val clockCalls = AtomicInteger(0)
    val blockRan = AtomicBoolean(false)
    val deadlineNs = 1_000_000_000L
    try {
      pool.submit {}.get(2, TimeUnit.SECONDS)
      val postRun = pool.resetPostRunLatch()
      val job = launch {
        NetworkPolicy.runPreflightSuspend(
          deadlineNs = deadlineNs,
          executor = pool,
          nanoClock = {
            if (clockCalls.incrementAndGet() == 1) {
              0L
            } else {
              insideTask.complete(Unit)
              try {
                releaseClock.await(2, TimeUnit.SECONDS)
              } catch (_: InterruptedException) {
                // 模擬執行 early-return 前 worker 已帶中斷旗標
                Thread.currentThread().interrupt()
              }
              deadlineNs
            }
          },
        ) {
          blockRan.set(true)
          "should-not-run"
        }
      }
      withTimeout(2_000) {
        insideTask.await()
      }
      withTimeout(1_000) {
        job.cancelAndJoin()
      }
      assertFalse("cancelled/expired task must skip block", blockRan.get())
      assertTrue(postRun.await(2, TimeUnit.SECONDS))
      assertFalse(
        "worker interrupt flag must be cleared immediately after command.run() returns",
        pool.lastPostRunInterrupted.get(),
      )

      val nextFlag = NetworkPolicy.runPreflightSuspend(
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
        executor = pool,
      ) {
        Thread.currentThread().isInterrupted
      }
      assertEquals(NetworkPolicy.PreflightOutcome.Completed(false), nextFlag)
    } finally {
      releaseClock.countDown()
      pool.shutdownNow()
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun preflightSuspendAlreadyCancelledCoroutineNeverSubmitsToExecutor() = runBlocking {
    val neverSubmitExecutor: ExecutorService = object : AbstractExecutorService() {
      override fun shutdown() {}
      override fun shutdownNow(): List<Runnable> = emptyList()
      override fun isShutdown() = false
      override fun isTerminated() = false
      override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
      override fun execute(command: Runnable) {
        fail("already-cancelled coroutine must not submit to executor")
      }
    }
    var entered = false
    var caught: Throwable? = null
    val job = launch {
      cancel()
      entered = true
      try {
        NetworkPolicy.runPreflightSuspend(
          deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
          executor = neverSubmitExecutor,
        ) {
          fail("already-cancelled coroutine must not run block")
        }
      } catch (e: CancellationException) {
        caught = e
        throw e
      }
    }
    job.join()
    assertTrue("test must invoke runPreflightSuspend from cancelled coroutine", entered)
    assertTrue("already-cancelled coroutine must throw CancellationException", caught is CancellationException)
  }

  @Test fun preflightBlockingPostSubmitExpiredBeforeGetCancelsAndReturnsTimedOut() {
    // 1) submit 後、future.get 前 waitNs == 0L（尚在排隊未開始執行即取消）
    val pool1 = InterruptRecordingExecutor("test-post-submit-queued")
    val allowRun = CountDownLatch(1)
    val gatedExecutor: ExecutorService = object : AbstractExecutorService() {
      override fun shutdown() = pool1.shutdown()
      override fun shutdownNow() = pool1.shutdownNow()
      override fun isShutdown() = pool1.isShutdown
      override fun isTerminated() = pool1.isTerminated
      override fun awaitTermination(timeout: Long, unit: TimeUnit) = pool1.awaitTermination(timeout, unit)
      override fun execute(command: Runnable) {
        pool1.execute {
          assertTrue(allowRun.await(2, TimeUnit.SECONDS))
          command.run()
        }
      }
    }
    try {
      pool1.submit {}.get(2, TimeUnit.SECONDS)
      val postRun1 = pool1.resetPostRunLatch()
      val blockRan1 = AtomicBoolean(false)
      val calls1 = AtomicInteger(0)
      val deadline1 = 1_000_000L
      val out1 = NetworkPolicy.runPreflightBlocking(
        deadlineNs = deadline1,
        executor = gatedExecutor,
        nanoClock = {
          when (calls1.incrementAndGet()) {
            1 -> 0L
            else -> deadline1
          }
        },
      ) {
        blockRan1.set(true)
        "should-not-run"
      }
      assertEquals(NetworkPolicy.PreflightOutcome.TimedOut, out1)
      allowRun.countDown()
      assertTrue(postRun1.await(2, TimeUnit.SECONDS))
      assertFalse("queued task cancelled before get must not execute block", blockRan1.get())
      assertFalse(pool1.lastPostRunInterrupted.get())
    } finally {
      allowRun.countDown()
      pool1.shutdownNow()
      assertTrue(pool1.awaitTermination(2, TimeUnit.SECONDS))
    }

    // 2) submit 後 worker 已進入 block()，caller 進 waitNs 檢查時 waitNs < 0L -> 中斷執行中 worker 並回 TimedOut
    val pool2 = InterruptRecordingExecutor("test-post-submit-running")
    val callerThread = Thread.currentThread()
    val workerEntered = CountDownLatch(1)
    val workerInterrupted = AtomicBoolean(false)
    val workerDone = CountDownLatch(1)
    try {
      pool2.submit {}.get(2, TimeUnit.SECONDS)
      val postRun2 = pool2.resetPostRunLatch()
      val callerCalls = AtomicInteger(0)
      val deadline2 = 1_000_000L
      val out2 = NetworkPolicy.runPreflightBlocking(
        deadlineNs = deadline2,
        executor = pool2,
        nanoClock = {
          if (Thread.currentThread() === callerThread) {
            when (callerCalls.incrementAndGet()) {
              1 -> 0L
              else -> {
                assertTrue(workerEntered.await(2, TimeUnit.SECONDS))
                deadline2 + 1L
              }
            }
          } else {
            0L
          }
        },
      ) {
        workerEntered.countDown()
        try {
          Thread.sleep(10_000)
        } catch (_: InterruptedException) {
          workerInterrupted.set(true)
          Thread.currentThread().interrupt()
        } finally {
          workerDone.countDown()
        }
        "late"
      }
      assertEquals(NetworkPolicy.PreflightOutcome.TimedOut, out2)
      assertTrue(workerDone.await(2, TimeUnit.SECONDS))
      assertTrue("running worker must be interrupted on post-submit waitNs <= 0L", workerInterrupted.get())
      assertTrue(postRun2.await(2, TimeUnit.SECONDS))
      assertFalse(pool2.lastPostRunInterrupted.get())
    } finally {
      pool2.shutdownNow()
      assertTrue(pool2.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun resolveValidatedDnsHostMultiHopInterruptAndCyclicCause() {
    val multiHopResolver = HostResolver {
      val root = InterruptedException("root-interrupt")
      val mid = IOException("mid-io").apply { initCause(root) }
      throw UnknownHostException("outer-host").apply { initCause(mid) }
    }
    try {
      NetworkPolicy.evaluateUrl("https://example.com/", resolver = multiHopResolver)
      fail("multi-hop InterruptedException cause must throw CancellationException")
    } catch (_: CancellationException) {
    }
    try {
      NetworkPolicy.resolveHost("example.com", multiHopResolver)
      fail("multi-hop InterruptedException cause must throw CancellationException in resolveHost")
    } catch (_: CancellationException) {
    }

    // 直接拋 CancellationException 與 plain UnknownHostException 搭配 Thread.isInterrupted 皆必須透傳 CancellationException
    val cancelResolver = HostResolver { throw CancellationException("direct-cancel") }
    try {
      NetworkPolicy.evaluateUrl("https://example.com/", resolver = cancelResolver)
      fail("CancellationException must propagate from evaluateUrl")
    } catch (_: CancellationException) {
    }
    try {
      NetworkPolicy.resolveHost("example.com", cancelResolver)
      fail("CancellationException must propagate from resolveHost")
    } catch (_: CancellationException) {
    }

    val plainInterruptResolver = HostResolver { host ->
      Thread.currentThread().interrupt()
      throw UnknownHostException(host)
    }
    try {
      try {
        NetworkPolicy.evaluateUrl("https://example.com/", resolver = plainInterruptResolver)
        fail("plain UnknownHostException while interrupted must throw CancellationException in evaluateUrl")
      } catch (_: CancellationException) {
      }
      assertTrue(Thread.interrupted())
      try {
        NetworkPolicy.resolveHost("example.com", plainInterruptResolver)
        fail("plain UnknownHostException while interrupted must throw CancellationException in resolveHost")
      } catch (_: CancellationException) {
      }
      assertTrue(Thread.interrupted())
    } finally {
      Thread.interrupted()
    }

    // 循環 cause 鏈不可無限迴圈或 StackOverflow，必須 fail-closed
    class CyclicException(msg: String) : RuntimeException(msg) {
      var loopCause: Throwable? = null
      override val cause: Throwable? get() = loopCause
    }
    val cyclicResolver = HostResolver {
      val e1 = CyclicException("c1")
      val e2 = CyclicException("c2")
      e1.loopCause = e2
      e2.loopCause = e1
      throw e1
    }
    assertNull(NetworkPolicy.resolveHost("example.com", cyclicResolver))
    assertDenied(NetworkDenyCode.INVALID_HOST, NetworkPolicy.evaluateUrl("https://example.com/", resolver = cyclicResolver))
  }
}
