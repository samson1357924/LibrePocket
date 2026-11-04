package dev.librepocket.policy

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
 *   multicast／16-byte IPv4-mapped／IPv4 保留段（0/8、240/4、broadcast）／
 *   NAT64·compat·6to4·Teredo 內嵌非公網 v4／userinfo 即使 opt-in 也永遠阻擋；
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
    } catch (e: Exception) {
      // resolver 任何例外（含 RuntimeException／IO）一律 fail-closed；
      // 唯協程取消必須透傳，不可吞沒（否則結構化併發失效）。
      if (e is CancellationException) throw e
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
    } catch (e: Exception) {
      // 字面非法 fail-closed；協程取消仍透傳，不可吞沒。
      if (e is CancellationException) throw e
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

  /** 最嚴格者勝出：METADATA > 永遠阻擋類 > 需 opt-in 類 > PUBLIC。
   * 永遠阻擋類（[ALWAYS_BLOCKED]）已含 [AddressClass.UNSPECIFIED]，
   * 故保留段（0/8、240/4、broadcast）與拆解失敗的內嵌 v4 一律 opt-in 也打不開。 */
  internal fun mergeClass(classes: Set<AddressClass>): AddressClass {
    if (AddressClass.METADATA in classes) return AddressClass.METADATA
    for (c in ALWAYS_BLOCKED) if (c in classes) return c
    if (AddressClass.LOOPBACK in classes) return AddressClass.LOOPBACK
    if (AddressClass.PRIVATE in classes) return AddressClass.PRIVATE
    return AddressClass.PUBLIC
  }

  /**
   * S2 redirect 執行面：provider／MCP 0-follow，真正的跳轉碼皆終端拒絕。
   * 僅做分類（[NetworkDenyCode.REDIRECT_BLOCKED] 保留碼），實際阻擋由 transport
   * 執行層拋出；[evaluateUrl] 只驗首跳，不跟隨跳轉。
   * 304 (Not Modified)／305 (Use Proxy)／306 均無 Location 跳轉語義，
   * 明確排除，避免把快取回應誤判為跳轉阻擋。
   */
  fun isRedirectStatus(code: Int): Boolean = code in 300..303 || code == 307 || code == 308

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
   * （::ffff:127.0.0.1 不得被當成 LOOPBACK）；其餘 IPv4-embedded 前綴
   * （NAT64 `64:ff9b::/96`、compat `::/96`、6to4 `2002::/16`、
   * Teredo `2001::/32`）緊隨其後，取出內嵌 v4 按 v4 規則重走
   * （metadata 優先，拆解失敗即 UNSPECIFIED），封裝旁路無所遁形；
   * 保留段（0/8、240/4、broadcast）以位元組精確拒——isAnyLocalAddress
   * 只認精確 0.0.0.0／::，0.1.2.3 等會漏判 PUBLIC，此處補擋；
   * IPv6 其餘保留段（如文件／特殊用途前綴）暫不單列、維持 PUBLIC，
   * 收緊待未來前綴表（fail-closed 由呼叫方重驗兜底）。
   */
  fun classifyAddress(address: InetAddress): AddressClass {
    val b = address.address
    if (isMetadataEndpoint(b)) return AddressClass.METADATA
    if (isIpv4Mapped(b)) return AddressClass.IPV4_MAPPED
    if (isEmbeddedV4(b)) {
      // 內嵌 v4 拆解失敗 fail-closed：寧擋一個怪地址，不放行。
      val v4 = try {
        extractEmbeddedV4(b)
      } catch (_: Exception) {
        null
      }
      return if (v4 != null) classifyEmbeddedV4(v4) else AddressClass.UNSPECIFIED
    }
    if (isReservedV4(b)) return AddressClass.UNSPECIFIED
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
  /** 0.0.0.0／`::`、IPv4 保留段（0/8、240/4、broadcast）、拆解失敗的內嵌 v4——永遠阻擋。 */
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

/**
 * IPv4 保留段位元組精確拒：0.0.0.0/8、240.0.0.0/4、255.255.255.255。
 * isAnyLocalAddress 只認精確全零字面，0.1.2.3／240.0.0.1 等會漏判 PUBLIC，
 * 此處補擋 fail-closed（回 UNSPECIFIED，opt-in 打不開）。
 */
private fun isReservedV4(b: ByteArray): Boolean {
  if (b.size != 4) return false
  val first = b[0].toInt() and 0xff
  if (first == 0 || first >= 240) return true
  return b.all { (it.toInt() and 0xff) == 0xff }
}

/**
 * IPv4-embedded IPv6 前綴偵測：NAT64 `64:ff9b::/96`、compat `::/96`、
 * 6to4 `2002::/16`、Teredo `2001::/32`。`::ffff/96` 不在此列
 * （另判 IPV4_MAPPED 永擋，語義不變）；`::`／`::1` 排除於 compat 之外，
 * 維持既有 UNSPECIFIED／LOOPBACK 語義。
 */
private fun isEmbeddedV4(b: ByteArray): Boolean {
  if (b.size != 16) return false
  // NAT64 64:ff9b::/96（RFC 6052 well-known prefix，/96 後 64 位須為零，即位元組 4..11）。
  if ((b[0].toInt() and 0xff) == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
    (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b &&
    b.slice(4..11).all { it == 0.toByte() }
  ) return true
  // 6to4 2002::/16：位元組 2..5 即內嵌 v4。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x02) return true
  // Teredo 2001::/32（2001:0000::/32；2001:db8:: 等文件段位元組 2 非零，不誤判）。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x01 &&
    b[2] == 0.toByte() && b[3] == 0.toByte()
  ) return true
  // compat ::/96（已廢棄）：前 96 位全零但排除 :: 與 ::1。
  if (b.slice(0..11).all { it == 0.toByte() }) {
    val tail = b.slice(12..15)
    if (!tail.all { it == 0.toByte() } && tail != listOf(0.toByte(), 0.toByte(), 0.toByte(), 1.toByte())) return true
  }
  return false
}

/** 取出內嵌 v4（4 bytes）；前綴不符即拋，呼叫方 fail-closed 回 UNSPECIFIED。 */
private fun extractEmbeddedV4(b: ByteArray): ByteArray {
  require(b.size == 16) { "NOT_EMBEDDED_V4" }
  // NAT64：末 32 位即內嵌 v4（前 96 位須為 64:ff9b::/96，位元組 4..11 全零）。
  if ((b[0].toInt() and 0xff) == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
    (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b &&
    b.slice(4..11).all { it == 0.toByte() }
  ) return b.sliceArray(12..15)
  // 6to4：位元組 2..5。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x02) return b.sliceArray(2..5)
  // Teredo：末 32 位經 ones-complement 混淆，取反還原——否則攻擊者可以混淆後
  // 位元組繞過 metadata／私有檢查（如混淆後的 169.254.169.254 看似公網）。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x01 &&
    b[2] == 0.toByte() && b[3] == 0.toByte()
  ) return ByteArray(4) { i -> (b[12 + i].toInt() xor 0xff).toByte() }
  // compat ::/96。
  if (b.slice(0..11).all { it == 0.toByte() }) {
    val tail = b.sliceArray(12..15)
    require(!tail.all { it == 0.toByte() }) { "NOT_EMBEDDED_V4" }
    require(!(tail[0] == 0.toByte() && tail[1] == 0.toByte() && tail[2] == 0.toByte() && tail[3] == 1.toByte())) {
      "NOT_EMBEDDED_V4"
    }
    return tail
  }
  throw IllegalArgumentException("NOT_EMBEDDED_V4")
}

/**
 * 內嵌 v4 按 v4 規則重走：metadata 優先，其次保留段→迴環→link-local→
 * CGNAT→multicast→私有→公網，與 [NetworkPolicy.classifyAddress] 的 v4 側一致。
 */
private fun classifyEmbeddedV4(v4: ByteArray): AddressClass {
  require(v4.size == 4) { "NOT_IPV4" }
  if (isMetadataEndpoint(v4)) return AddressClass.METADATA
  if (isReservedV4(v4)) return AddressClass.UNSPECIFIED
  val f0 = v4[0].toInt() and 0xff
  val f1 = v4[1].toInt() and 0xff
  if (f0 == 127) return AddressClass.LOOPBACK
  if (f0 == 169 && f1 == 254) return AddressClass.LINK_LOCAL
  if (isCgnat(v4)) return AddressClass.CGNAT
  if (f0 in 224..239) return AddressClass.MULTICAST
  if (f0 == 10 || (f0 == 172 && f1 in 16..31) || (f0 == 192 && f1 == 168)) return AddressClass.PRIVATE
  return AddressClass.PUBLIC
}

private fun isUla(b: ByteArray): Boolean =
  b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
