package dev.librepocket.policy

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * S1 (Issue #135 A1) + S2 (A2 接線)：出站網路政策核心——cleartext／私有 IP 決策的單一真相。
 *
 * 純 JVM（無 Android API）。S2 已接線 provider transport（見
 * `ProviderTransport.requireNetworkPolicyAllowed`）：建連前先調
 * [evaluateUrl]，僅當 [NetworkEvaluation.allowed] 為 true 才可連線；
 * 拒絕即 typed fatal（不送 credential／body），redirect 0-follow
 *（[NetworkDenyCode.REDIRECT_BLOCKED] 由執行層擋下）。
 * 仍不碰 `AndroidManifest`／NSC（見 S2 殘留：NSC／DNS pinning 裝置部分待 A3/A4）。
 *
 * Fail-closed 貫穿全程：
 * - 私有判定一律以解析後位元組（[classifyAddress]）為準，永不做字串前綴比對
 *   （舊 `validateBaseUrl` 的 `host.startsWith("10.")` 會把 `10.evil.com`
 *   誤放行，此處結構上不可能重犯）；
 * - onion／非法 host／解析失敗一律拒絕，且 onion 在查 DNS 前就擋下（不洩漏）；
 * - 預設 [EndpointNetworkOptIn] 即 [NetworkEgressPolicy.HTTPS_ONLY]，
 *   私有與明文預設全拒（deny-all），opt-in 只能 per-endpoint 持有，
 *   沒有全域一鍵開放；
 * - `public http://` 永不開放（non-goal），metadata／link-local／CGNAT／
 *   multicast／16-byte IPv4-mapped／userinfo 即使 opt-in 也永遠阻擋；
 *   字面 `::ffff:a.b.c.d` 經平台正規化後等價內嵌 v4，按 v4 決策（見測試）。
 */
object NetworkPolicy {

  /**
   * 首跳 DNS 決策；呼叫方（S2 的 pinning `Dns`）必須拿
   * [NetworkEvaluation.resolvedAddresses] 把答案釘住建連，不得二次解析
   * （否則 TOCTOU rebind；rebind 即視為新決策重驗）。
   *
   * S2：[resolver] 無預設值，呼叫方必須顯式注入（production 經
   * `PinnedNetworkDns` 釘住後的 resolver，測試用 fake）；舊預設
   * `SystemHostResolver` 僅保留給 [resolveHost] 的純工具場景。
   */
  fun evaluateUrl(
    rawUrl: String,
    optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
    resolver: HostResolver,
  ): NetworkEvaluation {
    // HttpUrl 只解析 http(s)：先顯式識別其餘 scheme（與 WebFetch.checkUrl
    // 姿態對稱），保留 UNSUPPORTED_SCHEME 細分碼。
    val schemePrefix = SCHEME_PREFIX.find(rawUrl)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    if (schemePrefix != null && schemePrefix != "http" && schemePrefix != "https") {
      return NetworkEvaluation.denied(NetworkDenyCode.UNSUPPORTED_SCHEME, null)
    }
    val url = rawUrl.toHttpUrlOrNull()
      ?: return NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null)
    // Userinfo 永遠阻擋：解析後欄位＋原始 authority 雙重檢查（含空 userinfo
    // 的 `http://@host/`，與 McpWire F-01 姿態對稱；先剝 fragment `#` 免誤殺）。
    val authority = rawUrl.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
    if (url.username.isNotEmpty() || !url.password.isNullOrEmpty() || '@' in authority) {
      return NetworkEvaluation.denied(NetworkDenyCode.USERINFO_BLOCKED, null)
    }
    val resolved = resolveHost(url.host, resolver)
      ?: return NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null)
    val classes = resolved.map(::classifyAddress).toSet()
    val merged = mergeClass(classes)
    if (merged == AddressClass.METADATA) {
      return NetworkEvaluation.denied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, merged, resolved)
    }
    if (merged != AddressClass.PUBLIC && merged != AddressClass.LOOPBACK && merged != AddressClass.PRIVATE) {
      // LINK_LOCAL / CGNAT / MULTICAST / IPV4_MAPPED / UNSPECIFIED：opt-in 也打不開。
      return NetworkEvaluation.denied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, merged, resolved)
    }
    if (url.scheme == "http") {
      // 明文只對迴環／私有開放，且須最高 tier；public http 永不開放。
      if (merged == AddressClass.PUBLIC || !optIn.allowsCleartext) {
        return NetworkEvaluation.denied(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, merged, resolved)
      }
    }
    if ((merged == AddressClass.LOOPBACK || merged == AddressClass.PRIVATE) && !optIn.allowsPrivateAddresses) {
      return NetworkEvaluation.denied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, merged, resolved)
    }
    return NetworkEvaluation.allowed(merged, resolved, url.toString())
  }

  /**
   * 解析 host 但不做政策決策；回傳 null 表示該 host 永不得撥號（onion、
   * 非法字面、非法 DNS 名、解析失敗）。Onion 在碰 [resolver] 前就回 null，
   * 保證測試可用必敗 resolver 驗證零 DNS 洩漏。
   */
  fun resolveHost(host: String, resolver: HostResolver = SystemHostResolver): List<InetAddress>? {
    val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
    if (h.isEmpty() || h.length > 253) return null
    if (h == "onion" || h.endsWith(".onion")) return null
    if ('@' in h || '/' in h || '\\' in h || h.any { it.isWhitespace() || it < ' ' }) return null
    parseLiteralIp(h)?.let { return listOf(it) }
    if (':' in h) return null // 含冒號卻不是合法字面 IPv6。
    if (!isValidDnsName(h)) return null
    return try {
      val addrs = resolver.resolve(h)
      if (addrs.isEmpty()) null else addrs
    } catch (_: Exception) {
      // resolver 任何例外（含 RuntimeException／IO）一律 fail-closed。
      null
    }
  }

  /**
   * 純字面解析，不觸碰網路。字面 IPv4 走嚴格十進位四段解析，前導零
   * （如 `0177` 疑似八進位）一律回 null；含冒號者先經字面預檢再走
   * [InetAddress.getByName]（字面 IPv6 不查 DNS）。
   * 十六進位／八進位／超長段等非正規寫法一律回 null，交由 DNS 路徑
   * fail-closed（未知 → 拒），不猜測、不正規化放行。
   */
  fun parseLiteralIp(host: String): InetAddress? {
    val v4 = IPV4_LITERAL.matchEntire(host) ?: return parseLiteralIpv6(host)
    val rawParts = v4.groupValues.drop(1)
    // 前導零一律拒（如 0177 可能是八進位）：fail-closed，不猜測基底。
    if (rawParts.any { it.length > 1 && it.startsWith('0') }) return null
    val parts = rawParts.map { it.toInt() }
    if (parts.any { it !in 0..255 }) return null
    return InetAddress.getByAddress(parts.map { it.toByte() }.toByteArray())
  }

  private fun parseLiteralIpv6(host: String): InetAddress? {
    if (':' !in host) return null
    // 字面預檢：僅 hex／:／.（含內嵌 v4），非字面直接拒，不呼叫 getByName。
    if (!IPV6_LITERAL_CHARS.matches(host)) return null
    return try {
      val addr = InetAddress.getByName(host)
      val b = addr.address
      if (b.size == 16) {
        addr
      } else {
        // JVM 會把 `::ffff:a.b.c.d` 等 unwrap 成 Inet4Address；包回 16-byte
        // mapped 形，保證分類為 IPV4_MAPPED（永遠阻擋），不因平台解包而溜號。
        InetAddress.getByAddress(ByteArray(10) + byteArrayOf(0xff.toByte(), 0xff.toByte()) + b)
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun isValidDnsName(host: String): Boolean {
    val labels = host.split('.')
    if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return false
    return labels.all { label ->
      label.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' } &&
        !label.startsWith('-') && !label.endsWith('-')
    }
  }

  /** 最嚴格者勝出：METADATA > 永遠阻擋類 > 需 opt-in 類 > PUBLIC。 */
  internal fun mergeClass(classes: Set<AddressClass>): AddressClass {
    if (AddressClass.METADATA in classes) return AddressClass.METADATA
    for (c in ALWAYS_BLOCKED) if (c in classes) return c
    if (AddressClass.LOOPBACK in classes) return AddressClass.LOOPBACK
    if (AddressClass.PRIVATE in classes) return AddressClass.PRIVATE
    return AddressClass.PUBLIC
  }

  /**
   * S2 redirect 執行面：provider／MCP 0-follow，任何 3xx 皆終端拒絕。
   * WebFetch 逐跳重驗待 A4；此處僅分類，實際阻擋由 transport 執行。
   */
  fun isRedirectStatus(code: Int): Boolean = code in 300..399

  private val ALWAYS_BLOCKED = setOf(
    AddressClass.LINK_LOCAL,
    AddressClass.CGNAT,
    AddressClass.MULTICAST,
    AddressClass.IPV4_MAPPED,
    AddressClass.UNSPECIFIED,
  )

  private val IPV4_LITERAL = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

  private val IPV6_LITERAL_CHARS = Regex("""^[0-9a-fA-F:.]+$""")

  private val SCHEME_PREFIX = Regex("""^\s*([A-Za-z][A-Za-z0-9+.-]*):""")

  /**
   * 純位元組分類，不觸碰網路。檢查順序具語義：metadata 先於 link-local
   * （169.254.169.254 ⊂ 169.254/16），IPv4-mapped 先於其內嵌 v4 的分類
   * （::ffff:127.0.0.1 不得被當成 LOOPBACK）。
   */
  fun classifyAddress(address: InetAddress): AddressClass {
    val b = address.address
    if (isMetadataEndpoint(b)) return AddressClass.METADATA
    if (isIpv4Mapped(b)) return AddressClass.IPV4_MAPPED
    if (address.isMulticastAddress) return AddressClass.MULTICAST
    if (address.isAnyLocalAddress) return AddressClass.UNSPECIFIED
    if (address.isLoopbackAddress) return AddressClass.LOOPBACK
    if (address.isLinkLocalAddress) return AddressClass.LINK_LOCAL
    if (isCgnat(b)) return AddressClass.CGNAT
    if (address.isSiteLocalAddress || isUla(b)) return AddressClass.PRIVATE
    return AddressClass.PUBLIC
  }
}

/**
 * 每跳 DNS 查詢的可注入 seam。Production 用 [SystemHostResolver]；
 * 測試注入 fake（見 `NetworkPolicyTest.fakeDns`）；S2 的 pinning `Dns`
 * 實作亦經此介面拿首跳答案。
 */
fun interface HostResolver {
  @Throws(UnknownHostException::class)
  fun resolve(host: String): List<InetAddress>
}

/** 系統預設解析（`InetAddress.getAllByName`，多答案全回由政策取最嚴格）。 */
object SystemHostResolver : HostResolver {
  override fun resolve(host: String): List<InetAddress> =
    InetAddress.getAllByName(host).toList()
}

/**
 * Per-endpoint 出站 tier（S1 先 data model，不接 UI、不寫入既有 config）。
 * 單調遞增：每個 tier 包含前者全部授權，S2 綁定 endpoint identity＋
 * configRevision，變更即重綁（沿用 S4-C 顯式重綁，不與 chat key 混用）。
 */
enum class NetworkEgressPolicy {
  /** 預設：公網 https-only；一切明文與非公網位址全拒（Play 姿態停在此 tier）。 */
  HTTPS_ONLY,
  /** 另允許解析到私有／迴環的 DNS 名走 https（foss／github LAN pilot）；明文仍拒。 */
  ALLOW_PRIVATE_DNS,
  /** 另允許迴環／RFC1918／ULA（字面或 DNS 解析到）走 http；公網 http 永不開放。 */
  ALLOW_CLEARTEXT_PRIVATE,
}

/**
 * Per-endpoint opt-in（S1 data model；S2 綁定 identity＋revision＋one-shot 確認）。
 * 預設建構即 deny-all 姿態。
 */
data class EndpointNetworkOptIn(
  val policy: NetworkEgressPolicy = NetworkEgressPolicy.HTTPS_ONLY,
  val configRevision: Long = 0L,
) {
  val allowsPrivateAddresses: Boolean
    get() = policy == NetworkEgressPolicy.ALLOW_PRIVATE_DNS ||
      policy == NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE
  val allowsCleartext: Boolean
    get() = policy == NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE
}

/** 位元組層位址分類（[classifyAddress] 的輸出，可單獨單測）。 */
enum class AddressClass {
  /** 127/8、`::1`——可 opt-in。 */
  LOOPBACK,
  /** RFC1918、ULA `fc00::/7`——可 opt-in。 */
  PRIVATE,
  /** 169.254/16、`fe80::/10`——永遠阻擋。 */
  LINK_LOCAL,
  /** CGNAT 100.64/10——永遠阻擋。 */
  CGNAT,
  /** 雲 metadata 端點（169.254.169.254 等）——永遠阻擋，獨立錯誤碼。 */
  METADATA,
  /** Multicast——永遠阻擋。 */
  MULTICAST,
  /** `::ffff/96` 16-byte IPv4-mapped——永遠阻擋；字面經正規化等價內嵌 v4 者按 v4 決策。 */
  IPV4_MAPPED,
  /** 0.0.0.0／`::`——永遠阻擋。 */
  UNSPECIFIED,
  /** 其餘可路由位址（含 TEST-NET 等文件段，僅供測試佔位）。 */
  PUBLIC,
}

/**
 * 拒絕碼（typed errors 的 S1 核心側；S2 映射為 provider fatal 不重試、
 * `CLEARTEXT_NOT_PERMITTED` fatal、`REDIRECT_BLOCKED` 執行層）。
 * `REDIRECT_BLOCKED` 屬分類保留：provider／MCP 0-redirect 與 WebFetch
 * 逐跳重驗的執行面在 S2/A4 落地。
 */
enum class NetworkDenyCode {
  CLEARTEXT_NOT_PERMITTED,
  PRIVATE_ADDRESS_BLOCKED,
  METADATA_ADDRESS_BLOCKED,
  REDIRECT_BLOCKED,
  USERINFO_BLOCKED,
  UNSUPPORTED_SCHEME,
  INVALID_HOST,
}

/** 一次 URL 決策。被拒時 [resolvedAddresses] 仍回傳已解析答案以供稽核。 */
data class NetworkEvaluation(
  val allowed: Boolean,
  val denyCode: NetworkDenyCode?,
  val addressClass: AddressClass?,
  val resolvedAddresses: List<InetAddress> = emptyList(),
  val normalizedUrl: String? = null,
) {
  companion object {
    fun allowed(
      addressClass: AddressClass,
      resolvedAddresses: List<InetAddress>,
      normalizedUrl: String,
    ) = NetworkEvaluation(true, null, addressClass, resolvedAddresses, normalizedUrl)

    fun denied(
      denyCode: NetworkDenyCode,
      addressClass: AddressClass?,
      resolvedAddresses: List<InetAddress> = emptyList(),
    ) = NetworkEvaluation(false, denyCode, addressClass, resolvedAddresses, null)
  }
}

/** 雲 metadata 端點比對（位元組精確比對）。 */
private fun isMetadataEndpoint(b: ByteArray): Boolean {
  if (b.size == 4) {
    val x = b.map { it.toInt() and 0xff }
    return x == listOf(169, 254, 169, 254) ||
      x == listOf(169, 254, 169, 253) ||
      x == listOf(169, 254, 169, 123) ||
      x == listOf(100, 100, 100, 200)
  }
  if (b.size == 16) {
    // fd00:ec2::254（AWS IPv6 metadata）。
    val want = intArrayOf(
      0xfd, 0x00, 0x0e, 0xc2,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x02, 0x54,
    )
    if (b.size == want.size && want.indices.all { (b[it].toInt() and 0xff) == want[it] }) return true
  }
  return false
}

private fun isIpv4Mapped(b: ByteArray): Boolean =
  b.size == 16 && b.take(10).all { it == 0.toByte() } &&
    (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff

private fun isCgnat(b: ByteArray): Boolean =
  b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xff) in 64..127

private fun isUla(b: ByteArray): Boolean =
  b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
