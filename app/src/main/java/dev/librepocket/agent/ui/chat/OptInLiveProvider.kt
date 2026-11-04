package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.NetworkOptInStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * S2 Phase B：per-turn live opt-in wrapper（活躍 session 撤銷語義）。
 *
 * 動機：[ChatSessionFactory.create]/`open` 在建 session 時把
 * `store.optInFor(...)` 取成 immutable [ProviderConfig.networkOptIn] 快照；
 * `store.invalidate()` 或 `confirm(HTTPS_ONLY)` 後，活躍 session 的後續 turn
 * 仍用舊 ALLOW tier 建連送 credential/body。既有
 * `confirmDowngradeAndInvalidate` 只測 store 查詢，未覆蓋活躍物件。
 *
 * 語義：每次 [stream]（flow collect 時，即真正建連前）／[listModels] 入口先算
 * `fresh = store.optInFor(endpoint.providerId, endpoint.baseUrl, endpoint.configRevision)`；
 * 若 `fresh != baseConfig.networkOptIn`（policy 或 revision 變了），以後者為準建
 * `freshConfig = baseConfig.copy(networkOptIn = fresh)` 並建新 delegate 再委派，
 * 否則用 base delegate（快取一個，fresh 命中時重用）。
 *
 * 撤銷後同一物件再送：transport 閘用 fresh HTTPS_ONLY 擋下，typed
 * `NETWORK_POLICY_BLOCKED`＋0 request（閘在讀 key 前，見
 * `ProviderTransport.requireNetworkPolicyAllowed`）。wrapper 本身不送
 * credential：key 的 fill／zeroize 由底層 provider 處理，wrapper 只轉調不碰 key
 *（[keys] 原樣傳給每次重建的 delegate）。
 *
 * 快取：單槽（`cachedOptIn`＋`cachedDelegate`）。fresh 命中重用；flip 即重建。
 * 來回翻轉每次重建（bounded：每 turn 至多一次 build，無後台輪詢）。
 * 執行緒安全：`synchronized(lock)` 保護快取槽；[store] 為純記憶體查詢，
 * [build] 為非 suspend 建構，故 resolve 可在非 suspend 的 `protocol` getter
 * 與 flow collect 內同路徑調用。
 *
 * 接線：[ChatSessionFactory.buildSession] 在 `networkOptInStore != null` 時包此層
 *（create／open／openForked 三路徑）；null store 仍快照 HTTPS_ONLY 語義。
 * 直接持 [ProviderConfig] 快照的調用方（Setup probe 無 store）仍快照語義，不經此層。
 */
internal class OptInResolvingProvider(
    private val endpoint: EndpointConfig,
    private val store: NetworkOptInStore,
    private val baseConfig: ProviderConfig,
    private val keys: KeyProvider,
    private val build: (ProviderConfig, KeyProvider) -> LlmProvider,
) : LlmProvider {
    private val lock = Any()
    private var cachedOptIn: EndpointNetworkOptIn = baseConfig.networkOptIn
    private var cachedDelegate: LlmProvider? = null

    override val protocol: ProviderProtocol
        // opt-in 不改變 protocol：直接回 base，不經 resolveDelegate 避免每次讀都建構。
        get() = baseConfig.protocol

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val delegate = resolveDelegate()
        delegate.stream(request).collect { emit(it) }
    }

    override suspend fun listModels(): List<String> = resolveDelegate().listModels()

    private fun resolveDelegate(): LlmProvider {
        val fresh = store.optInFor(endpoint.providerId, endpoint.baseUrl, endpoint.configRevision)
        synchronized(lock) {
            val cached = cachedDelegate
            if (cached != null && fresh == cachedOptIn) return cached
            val freshConfig =
                if (fresh == baseConfig.networkOptIn) baseConfig else baseConfig.copy(networkOptIn = fresh)
            val delegate = build(freshConfig, keys)
            cachedDelegate = delegate
            cachedOptIn = fresh
            return delegate
        }
    }
}
