package dev.librepocket.policy

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
