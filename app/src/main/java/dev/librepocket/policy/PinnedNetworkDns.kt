package dev.librepocket.policy

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import okhttp3.Dns

/**
 * S2 (Issue #135 A2): pinning `Dns`——首跳答案釘住建連，rebind 視為新決策。
 *
 * 契約：
 * - 呼叫方先經 [NetworkPolicy.evaluateUrl] 拿首跳 [dev.librepocket.policy.NetworkEvaluation.resolvedAddresses]，
 *   再以 [pinnedFor]／建構參數把答案釘住，建連全程用釘住地址，不做二次解析（否則 TOCTOU rebind）；
 * - 同一 host 的後續解析一律回釘住值，不再諮詢 [delegate]；
 *   [hasRebind] 可主動重查 [delegate] 比對，發現不同即視為新決策（呼叫方重走
 *   `evaluateUrl`，不得沿用舊決策放行）；
 * - `strict = true`（provider 預設）：未釘住 host 一律 `UnknownHostException`
 *   fail-closed，redirect target 等未預期連線不可能經此建連（0-follow 另由
 *   transport 的 REDIRECT_BLOCKED 擋下，此為縱深）；
 * - `strict = false` 僅供測試／過渡：未釘住時走 [delegate] 並順手釘住。
 *
 * 純 JVM，可單測；production 由 provider transport 按次建構（per-request），
 * 不做全域單例（避免跨 endpoint 污染）。
 *
 * 直連視角：proxy 環境下實際建連地址由代理決定，本 pin 不生效，
 * 代理鏈政策由 A4 處理（此處行為不變，僅聲明假設）。
 */
class PinnedNetworkDns(
  initialPinned: Map<String, List<InetAddress>> = emptyMap(),
  private val delegate: HostResolver = SystemHostResolver,
  private val strict: Boolean = true,
) : Dns, HostResolver {

  private val pinned = ConcurrentHashMap<String, List<InetAddress>>()

  init {
    for ((host, addrs) in initialPinned) {
      val key = normalize(host)
      require(key.isNotBlank()) { "PINNED_DNS_HOST_BLANK" }
      require(addrs.isNotEmpty()) { "PINNED_DNS_EMPTY" }
      require(!this.pinned.containsKey(key)) { "PINNED_DNS_DUPLICATE_HOST" }
      this.pinned[key] = addrs.toList()
    }
  }

  /** OkHttp 建連路徑：只認釘住值。未釘住訊息為靜態 `DNS_PIN_MISS`
   *（不帶 hostname；host 只進 audit，不進錯誤字串／UI／transcript）。 */
  override fun lookup(hostname: String): List<InetAddress> {
    val key = normalize(hostname)
    if (key.isBlank()) throw UnknownHostException("DNS_PIN_MISS")
    pinned[key]?.let { return it.toList() }
    if (strict) throw UnknownHostException("DNS_PIN_MISS")
    val fresh = delegate.resolve(key)
    if (fresh.isEmpty()) throw UnknownHostException(key)
    pinned[key] = fresh.toList()
    return fresh.toList()
  }

  /** [HostResolver] 視角：與 [lookup] 同一釘住語義，供 `evaluateUrl` 重驗。 */
  override fun resolve(host: String): List<InetAddress> = lookup(host)

  /** 釘住一組答案（覆寫舊值；呼叫方僅對已 `evaluateUrl` 放行的 host 使用）。 */
  fun pin(host: String, addresses: List<InetAddress>) {
    val key = normalize(host)
    require(key.isNotBlank()) { "PINNED_DNS_HOST_BLANK" }
    require(addresses.isNotEmpty()) { "PINNED_DNS_EMPTY" }
    pinned[key] = addresses.toList()
  }

  /** 已釘住答案（防禦性拷貝），未釘住回 null。
   * 測試／audit helper：transport per-request 重決策建新 pin，mid-stream 不重查。 */
  fun pinnedFor(host: String): List<InetAddress>? = pinned[normalize(host)]?.toList()

  /**
   * 主動偵測 rebind：用 [delegate] 重查一次，與釘住值比對（順序無關）。
   * 回傳 null 表示該 host 尚未釘住（無從比對）；true = 發生 rebind，
   * 呼叫方必須視為新決策重走 `evaluateUrl`，不得沿用舊決策。
   * 測試／audit helper：transport per-request 重決策，mid-stream 不重查。
   */
  fun hasRebind(host: String): Boolean? {
    val key = normalize(host)
    if (key.isBlank()) return null
    val old = pinned[key] ?: return null
    val fresh = try {
      delegate.resolve(key)
    } catch (e: Exception) {
      // 重查失敗 fail-closed：視為可疑，呼叫方重驗（回 true 比吞掉更安全）；
      // 唯協程取消與中斷必須透傳，不可吞沒。
      if (e is CancellationException) throw e
      if (NetworkPolicy.hasInterruptedCause(e) || Thread.currentThread().isInterrupted) {
        throw CancellationException("rebind check interrupted", e)
      }
      return true
    }
    if (fresh.isEmpty()) return true
    return fresh.toSet() != old.toSet()
  }

  companion object {
    /**
     * 與 OkHttp `HttpUrl.host` 正規化對齊：去首尾空白／IPv6 方括號／尾點後小寫
     * （`toHttpUrl().host` 無括號，但 lookup 側可能帶括號如 `[::1]`，須同一鍵）。
     * 循環剝（先括號再尾點，反覆至穩定）：`[::1].`、`[::1.]` 皆歸一為 `::1`。
     */
    internal fun normalize(host: String): String {
      var s = host.trim()
      while (true) {
        if (s.length >= 2 && s.startsWith('[') && s.endsWith(']')) {
          s = s.substring(1, s.length - 1).trim()
          continue
        }
        if (s.endsWith('.')) {
          s = s.dropLast(1).trim()
          continue
        }
        break
      }
      return s.lowercase(Locale.ROOT)
    }

    /** 由一次放行決策建構 per-request pinning DNS（只釘該 host）。 */
    fun forHost(host: String, addresses: List<InetAddress>, delegate: HostResolver = SystemHostResolver): PinnedNetworkDns {
      require(addresses.isNotEmpty()) { "PINNED_DNS_EMPTY" }
      return PinnedNetworkDns(mapOf(host to addresses), delegate, strict = true)
    }
  }
}
