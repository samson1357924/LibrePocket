package dev.librepocket.policy

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
 */
class PinnedNetworkDns(
  pinned: Map<String, List<InetAddress>> = emptyMap(),
  private val delegate: HostResolver = SystemHostResolver,
  private val strict: Boolean = true,
) : Dns, HostResolver {

  private val pinned = ConcurrentHashMap<String, List<InetAddress>>()

  init {
    for ((host, addrs) in pinned) {
      this.pinned[normalize(host)] = addrs.toList()
    }
  }

  /** OkHttp 建連路徑：只認釘住值。 */
  override fun lookup(hostname: String): List<InetAddress> {
    val key = normalize(hostname)
    pinned[key]?.let { return it.toList() }
    if (strict) throw UnknownHostException("DNS_PIN_MISS:$hostname")
    val fresh = delegate.resolve(hostname)
    if (fresh.isEmpty()) throw UnknownHostException(hostname)
    pinned[key] = fresh.toList()
    return fresh.toList()
  }

  /** [HostResolver] 視角：與 [lookup] 同一釘住語義，供 `evaluateUrl` 重驗。 */
  override fun resolve(host: String): List<InetAddress> = lookup(host)

  /** 釘住一組答案（覆寫舊值；呼叫方僅對已 `evaluateUrl` 放行的 host 使用）。 */
  fun pin(host: String, addresses: List<InetAddress>) {
    require(addresses.isNotEmpty()) { "PINNED_DNS_EMPTY" }
    pinned[normalize(host)] = addresses.toList()
  }

  /** 已釘住答案（防禦性拷貝），未釘住回 null。 */
  fun pinnedFor(host: String): List<InetAddress>? = pinned[normalize(host)]?.toList()

  /**
   * 主動偵測 rebind：用 [delegate] 重查一次，與釘住值比對（順序無關）。
   * 回傳 null 表示該 host 尚未釘住（無從比對）；true = 發生 rebind，
   * 呼叫方必須視為新決策重走 `evaluateUrl`，不得沿用舊決策。
   */
  fun hasRebind(host: String): Boolean? {
    val key = normalize(host)
    val old = pinned[key] ?: return null
    val fresh = try {
      delegate.resolve(host)
    } catch (_: Exception) {
      // 重查失敗 fail-closed：視為可疑，呼叫方重驗（回 true 比吞掉更安全）。
      return true
    }
    if (fresh.isEmpty()) return true
    return fresh.toSet() != old.toSet()
  }

  companion object {
    internal fun normalize(host: String): String =
      host.trim().trimEnd('.').lowercase(Locale.ROOT)

    /** 由一次放行決策建構 per-request pinning DNS（只釘該 host）。 */
    fun forHost(host: String, addresses: List<InetAddress>, delegate: HostResolver = SystemHostResolver): PinnedNetworkDns {
      require(addresses.isNotEmpty()) { "PINNED_DNS_EMPTY" }
      return PinnedNetworkDns(mapOf(host to addresses), delegate, strict = true)
    }
  }
}
