package dev.librepocket.policy

import java.security.MessageDigest
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * S2 (Issue #135 A2): per-endpoint 網路 opt-in 的綁定與 one-shot 確認。
 *
 * 純 JVM、可單測；不碰 Android／DataStore（持久化沿用 [EndpointStore] 的
 * metadata 槽位或後續 DataStore 擴充前，先以記憶體持有本次確認；見底部署 TODO）。
 *
 * 綁定（變更即失效，沿用 S4-C 顯式重綁姿態，不與 chat key 共用 namespace）：
 * - `endpointId`：`preset:<presetId>`（內建）或 `custom:<sha256-32hex>`（自訂，
 *   與 `SetupValidation.deriveEndpointId` 同形；此處只驗形狀，不重算）；
 *   A3 另接受 `mcp:<serverId>`（MCP 伺服器專用命名空間，與 chat key 槽位
 *   `preset:`／`custom:` 嚴格區分；MCP 側以獨立 store 實例持有確認，
 *   不與 chat 共用確認）；
 * - `baseUrl` HttpUrl 正規化後字串（scheme／host 小寫、預設埠歸一、去
 *   fragment；尾點保留（視為不同身份，fail-closed，需重確認）；寫入與查詢兩側
 *   同經 boundOf 歸一；任何重寫即新身份）；
 * - `scheme`＋有效埠（`HttpUrl` 視角，預設埠正規化）；
 * - `policy` tier＋`configRevision`（`EndpointStore` 的單調 revision）。
 *
 * One-shot 確認：呼叫方（設定頁）必須在明示明文警告後調 [confirm]  mint 一次
 * 確認；任何綁定欄位變動使舊確認經 [confirmedPolicyFor] 自動失效為
 * [NetworkEgressPolicy.HTTPS_ONLY]（fail-closed），必須重新明示確認。
 *
 * Play 姿態：play 維持 deny-all（HTTPS-only）。預設建構（`allowTierUpgrade
 * = false`，即 play 姿態）拒收一切非 [NetworkEgressPolicy.HTTPS_ONLY] 的
 * [confirm]（`NETWORK_OPTIN_TIER_NOT_ALLOWED`）；foss／github pilot 另以
 * `allowTierUpgrade = true` 顯式建構才可經確認升級。真正的 flavor／sourceSet
 * gate（BuildConfig／flavor source set 內建構）待後續 UI 接線 followup，
 * 在此之前以此顯式旗標 fail-closed（見 `confirm`）。
 * 測試另行釘死（見 `NetworkPolicyS2Test.playStaysHttpsOnly`）。
 *
 * Phase B live 語義：[confirm] 降級（HTTPS_ONLY）／[invalidate] 後查詢即失效
 *（[confirmedPolicyFor]/[optInFor] 回 HTTPS_ONLY），且經
 * `OptInResolvingProvider` 路徑的活躍 session 每 turn 重解，生即生效——同一
 * session 物件再送即被 transport 閘擋下。直接持 `ProviderConfig` 快照的調用方
 *（Setup probe 無 store，建時 `toProviderConfig()` 一次）仍為快照語義：
 * 建後降級不影響已建出的 config，需重建才生效。
 */
class NetworkOptInStore(
  /**
   * 是否允許經 [confirm] 升級到 HTTPS_ONLY 之外的 tier。
   * 預設 false（play 姿態，deny-all）；foss／github pilot 接線必須顯式傳
   * true。Flavor 未傳遞到本層前，以此旗標承擔 gate，預設拒絕為 fail-closed。
   */
  private val allowTierUpgrade: Boolean = false,
) {

  /** 已確認綁定（單一真相；`endpointId` 為 key，一 endpoint 一條）。 */
  data class ConfirmedOptIn(
    val endpointId: String,
    val baseUrl: String,
    val scheme: String,
    val port: Int,
    val policy: NetworkEgressPolicy,
    val configRevision: Long,
  )

  private val lock = Any()
  private val confirmed = mutableMapOf<String, ConfirmedOptIn>()

  /**
   * 明示確認（one-shot）：呼叫前 UI 必須已明示明文警告。
   * 同一 endpoint 重複確認覆寫（新綁定即新確認）；`policy == HTTPS_ONLY`
   * 視為降級回預設，一併記錄（使舊高 tier 立即失效）。
   *
   * Play gate：本 store 未以 `allowTierUpgrade = true` 顯式建構時（預設即
   * play 姿態），非 HTTPS_ONLY 的確認一律拒收（fail-closed，不記錄）。
   * Flavor 尚未傳遞到本層，真正的 flavor／sourceSet gate 待後續 UI 接線
   * followup 誠實補上；在那之前 play production 只允許預設建構。
   */
  fun confirm(
    endpointId: String,
    baseUrl: String,
    policy: NetworkEgressPolicy,
    configRevision: Long,
  ): ConfirmedOptIn {
    require(checkPlayTierAllows(policy) || allowTierUpgrade) {
      "NETWORK_OPTIN_TIER_NOT_ALLOWED"
    }
    val bound = boundOf(endpointId, baseUrl, policy, configRevision)
    synchronized(lock) { confirmed[endpointId] = bound }
    return bound
  }

  /**
   * 查詢有效 tier：綁定全等才回已確認 tier；任何欄位不符（URL／scheme／port／
   * revision 變動）一律回 [NetworkEgressPolicy.HTTPS_ONLY]（fail-closed，
   * 舊確認自動失效，需重新 [confirm]）。查詢期 baseUrl 形變（malformed／
   * 非 http(s)）不拋 [IllegalArgumentException]，一律 fail-closed 回
   * HTTPS_ONLY（確認寫入期 [confirm] 仍嚴格拒收 malformed）。
   * 查詢期吞 [IllegalArgumentException] 回 HTTPS_ONLY 為稽核可見的
   * fail-closed（呼叫方可由回傳 tier 觀測到拒絕），不拋例外、不寫 Android Log。
   */
  fun confirmedPolicyFor(endpointId: String, baseUrl: String, configRevision: Long): NetworkEgressPolicy {
    val cur = synchronized(lock) { confirmed[endpointId] } ?: return NetworkEgressPolicy.HTTPS_ONLY
    val want = try {
      boundOf(endpointId, baseUrl, cur.policy, configRevision)
    } catch (_: IllegalArgumentException) {
      return NetworkEgressPolicy.HTTPS_ONLY
    }
    // 比對除 policy 外的綁定欄位＋revision；policy 取已存值（tier 本身是確認內容）。
    if (cur.baseUrl != want.baseUrl || cur.scheme != want.scheme || cur.port != want.port ||
      cur.configRevision != configRevision
    ) {
      return NetworkEgressPolicy.HTTPS_ONLY
    }
    return cur.policy
  }

  /** 轉 [EndpointNetworkOptIn]（供 transport 接線；預設即 deny-all）。 */
  fun optInFor(endpointId: String, baseUrl: String, configRevision: Long): EndpointNetworkOptIn {
    val policy = confirmedPolicyFor(endpointId, baseUrl, configRevision)
    return EndpointNetworkOptIn(policy = policy, configRevision = configRevision)
  }

  /** 單條失效（輪換／登出時調用；不存在亦成功）。 */
  fun invalidate(endpointId: String) {
    synchronized(lock) { confirmed.remove(endpointId) }
  }

  fun snapshot(endpointId: String): ConfirmedOptIn? = synchronized(lock) { confirmed[endpointId] }

  companion object {
    /**
     * 確認 token：綁 `endpointId + baseUrl + scheme + port + policy + revision`
     * 的 SHA-256（32hex，與 endpoint id 同形，便於稽核比對）。
     * Token 本身不是授權（授權以 [ConfirmedOptIn] 綁定全等為準），僅供
     * UI／audit 顯示這次 one-shot 確認的身份；僅顯示／稽核，不得用於除錯日誌外比較。
     */
    fun confirmationTokenFor(
      endpointId: String,
      baseUrl: String,
      policy: NetworkEgressPolicy,
      configRevision: Long,
    ): String {
      val bound = boundOf(endpointId, baseUrl, policy, configRevision)
      val material = "${bound.endpointId}|${bound.baseUrl}|${bound.scheme}|${bound.port}|${bound.policy.name}|${bound.configRevision}"
      val digest = try {
        MessageDigest.getInstance("SHA-256")
      } catch (e: java.security.NoSuchAlgorithmException) {
        throw IllegalStateException("NETWORK_OPTIN_HASH_UNAVAILABLE", e)
      }
      val bytes = digest.digest(material.toByteArray(Charsets.UTF_8))
      val hex = buildString(bytes.size * 2) {
        for (b in bytes) {
          val v = b.toInt() and 0xFF
          append("0123456789abcdef"[v ushr 4])
          append("0123456789abcdef"[v and 0x0F])
        }
      }
      return hex.take(32)
    }

    /**
     * Play tier gate：play 僅接受 [NetworkEgressPolicy.HTTPS_ONLY]。
     * foss／github pilot 的升級另由 [confirm]＋UI 明示確認承載（且該 store
     * 須以 `allowTierUpgrade = true` 顯式建構，預設建構的 `confirm` 直接拒收
     * 升級），play 永不調 [confirm] 升級（註解＋測試釘死）。
     */
    fun checkPlayTierAllows(policy: NetworkEgressPolicy): Boolean =
      policy == NetworkEgressPolicy.HTTPS_ONLY

    internal fun boundOf(
      endpointId: String,
      baseUrl: String,
      policy: NetworkEgressPolicy,
      configRevision: Long,
    ): ConfirmedOptIn {
      require(endpointId.isNotBlank()) { "NETWORK_OPTIN_ENDPOINT_BLANK" }
      // `mcp:` 為 MCP 伺服器專用命名空間（A3；見類 KDoc）：MCP 確認以獨立
      // store 實例持有，不與 chat 的 `preset:`／`custom:` 確認混用。
      require(endpointId.startsWith("preset:") || endpointId.startsWith("custom:") || endpointId.startsWith("mcp:")) {
        "NETWORK_OPTIN_ENDPOINT_MALFORMED"
      }
      val trimmed = baseUrl.trim()
      require(trimmed.isNotEmpty()) { "NETWORK_OPTIN_URL_BLANK" }
      val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("NETWORK_OPTIN_URL_MALFORMED")
      // userinfo 永不納入身份：寫入期直接拒收（確認成功後每次請求才擋
      // USERINFO_BLOCKED 會造成困惑；此處 fail-closed 使含 userinfo 的 URL 連確認都拿不到）。
      require(url.username.isEmpty() && url.password.isNullOrEmpty()) {
        "NETWORK_OPTIN_USERINFO_BLOCKED"
      }
      val scheme = url.scheme.lowercase(Locale.ROOT)
      require(scheme == "http" || scheme == "https") { "NETWORK_OPTIN_SCHEME_UNSUPPORTED" }
      // 身份正規化：HttpUrl 已做 scheme 小寫、host 小寫、預設埠正規化；此處再去
      // fragment。尾點保留不歸一（HttpUrl 保留尾點，視為不同身份，fail-closed，
      // 需重確認）；大小寫／顯式預設埠／fragment 形變歸一同一身份。
      // endpointId 仍只驗前綴形狀，不重算 derive（保持 fail-closed）。
      // HttpUrl.port 已含預設埠正規化（http 80／https 443）。
      val normalized = url.newBuilder().fragment(null).build().toString()
      return ConfirmedOptIn(
        endpointId = endpointId,
        baseUrl = normalized,
        scheme = scheme,
        port = url.port,
        policy = policy,
        configRevision = configRevision,
      )
    }
  }
}
