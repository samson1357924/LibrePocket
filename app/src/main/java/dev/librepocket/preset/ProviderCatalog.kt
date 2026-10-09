package dev.librepocket.preset

import dev.librepocket.models.ModelsDevSnapshot
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.validateBaseUrl

/**
 * Provider 預設目錄（純 JVM，不依賴 Android / OkHttp）。
 *
 * 只收錄「連線預設值」：顯示名、baseUrl、線路協議、鑑權頭、預設模型、
 * 備註。Key 一律不落地在此：呼叫方把 Key 寫入
 * [dev.librepocket.keystore.KeyVault]，再把 key 別名以 [apiKeyRef]
 * 傳入 [fromPreset]/[fromCustom]。轉接器（`provider` 包）一律不動，
 * 本包只組出 [ProviderConfig]，由 `DefaultProviderFactory` 做協議分派。
 */
object ProviderCatalog {
    // ---- baseUrl 常量（原創整理，逐字核對官方域名） ----
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"
    const val XAI_BASE_URL = "https://api.x.ai/v1"
    const val GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
    const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
    const val ALIBABA_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val MOONSHOT_BASE_URL = "https://api.moonshot.cn/v1"

    // ---- 鑑權頭常量 ----
    const val AUTH_AUTHORIZATION = "Authorization"
    const val AUTH_X_API_KEY = "x-api-key"
    const val AUTH_BEARER = "Bearer"

    /** Anthropic `anthropic-version` 頭版本（與轉接器同值，測試另行斷言）。 */
    const val ANTHROPIC_VERSION = "2023-06-01"

    // ---- 預設 id ----
    const val OPENAI_ID = "openai"
    const val ANTHROPIC_ID = "anthropic"
    const val XAI_ID = "xai"
    const val GEMINI_ID = "gemini"
    const val DEEPSEEK_ID = "deepseek"
    const val OPENROUTER_ID = "openrouter"
    const val SILICONFLOW_ID = "siliconflow"
    const val ALIBABA_ID = "alibaba"
    const val MOONSHOT_ID = "moonshot"
    const val CUSTOM_ID = "custom"

    /**
     * 單條預設。
     *
     * @param authHeaderName 鑑權頭名：`Authorization` 或 `x-api-key`。
     * @param authScheme `Authorization` 時為 `Bearer`，`x-api-key` 時為 null
     *  （key 直填頭值，不加前綴）。
     * @param anthropicVersion 僅 Anthropic 非空，其餘一律 null。
     * @param defaultModel 線路用模型 id（非 models.dev 目錄 id）；custom 為空。
     * @param modelsDevProviderId 僅填已確認的 models.dev provider identity。
     */
    data class ProviderPreset(
        val id: String,
        val label: String,
        val baseUrl: String,
        val protocol: ProviderProtocol,
        val authHeaderName: String,
        val authScheme: String?,
        val anthropicVersion: String?,
        val defaultModel: String,
        val notes: String,
        val modelsDevProviderId: String? = null,
    )

    /** 全部內建預設（含 `custom` 佔位，順序即設定頁展示順序）。 */
    val PRESETS: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = OPENAI_ID,
            label = "OpenAI",
            baseUrl = OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "gpt-4o-mini",
            notes = "OpenAI 原廠 OpenAI 兼容口；Key 以 Bearer 隨 Authorization 送出，存放於 KeyVault。",
            modelsDevProviderId = "openai",
        ),
        ProviderPreset(
            id = ANTHROPIC_ID,
            label = "Anthropic",
            baseUrl = ANTHROPIC_BASE_URL,
            protocol = ProviderProtocol.ANTHROPIC,
            authHeaderName = AUTH_X_API_KEY,
            authScheme = null,
            anthropicVersion = ANTHROPIC_VERSION,
            defaultModel = "claude-haiku-4-5",
            notes = "Anthropic Messages 原生協議；以 x-api-key 送 key，另帶 anthropic-version 頭；baseUrl 不含 /v1，由轉接器補 /v1/messages。",
            modelsDevProviderId = "anthropic",
        ),
        ProviderPreset(
            id = XAI_ID,
            label = "xAI Grok",
            baseUrl = XAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "grok-3-mini",
            notes = "xAI Grok 採 OpenAI 兼容口；Bearer 驗證；另有 grok-4 系列可於模型欄手填。",
        ),
        ProviderPreset(
            id = GEMINI_ID,
            label = "Gemini（OpenAI 兼容）",
            baseUrl = GEMINI_OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "gemini-2.0-flash",
            notes = "Gemini 經 OpenAI 兼容口接入；baseUrl 保留末尾 /，轉接器拼接 /chat/completions；以 API Key 作 Bearer。",
            modelsDevProviderId = "google",
        ),
        ProviderPreset(
            id = DEEPSEEK_ID,
            label = "DeepSeek",
            baseUrl = DEEPSEEK_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "deepseek-chat",
            notes = "DeepSeek 原廠 OpenAI 兼容口；Bearer；推理需求改填 deepseek-reasoner。",
        ),
        ProviderPreset(
            id = OPENROUTER_ID,
            label = "OpenRouter",
            baseUrl = OPENROUTER_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "openrouter/auto",
            notes = "OpenRouter 統一路由（OpenAI 兼容）；Bearer；模型 id 形如 vendor/model；附加 Referer/Title 由上層需要時再加，本層不存。",
        ),
        ProviderPreset(
            id = SILICONFLOW_ID,
            label = "硅基流動 SiliconFlow",
            baseUrl = SILICONFLOW_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "deepseek-ai/DeepSeek-V3",
            notes = "硅基流動 OpenAI 兼容口；Bearer；模型 id 形如 vendor/Model，可換 Qwen3 系列。",
        ),
        ProviderPreset(
            id = ALIBABA_ID,
            label = "阿里 DashScope",
            baseUrl = ALIBABA_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "qwen-plus",
            notes = "阿里 DashScope OpenAI 兼容口（compatible-mode）；Bearer；預設 qwen-plus，可換 qwen-max/qwen-turbo。",
        ),
        ProviderPreset(
            id = MOONSHOT_ID,
            label = "月之暗面 Moonshot",
            baseUrl = MOONSHOT_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "moonshot-v1-8k",
            notes = "月之暗面 OpenAI 兼容口；Bearer；預設國內站 cn，國際站改用 https://api.moonshot.ai/v1；另有 kimi-k2 系列可手填。",
        ),
        ProviderPreset(
            id = CUSTOM_ID,
            label = "自訂",
            baseUrl = "",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            authHeaderName = AUTH_AUTHORIZATION,
            authScheme = AUTH_BEARER,
            anthropicVersion = null,
            defaultModel = "",
            notes = "手填保留：baseUrl/protocol 由使用者提供，原樣保留，僅做 https 校验；請走 fromCustom，不走 fromPreset。",
        ),
    )

    /** 依 id 取預設，無則 null（`custom` 佔位亦可取到）。 */
    fun preset(presetId: String): ProviderPreset? = PRESETS.firstOrNull { it.id == presetId }

    /** 依 id 取預設，無則拋 `IllegalArgumentException(PRESET_UNKNOWN)`。 */
    fun requirePreset(presetId: String): ProviderPreset =
        preset(presetId) ?: throw IllegalArgumentException("PRESET_UNKNOWN")

    /**
     * 由預設組出 [ProviderConfig]。
     *
     * - 複用 `provider.validateBaseUrl` 做 https 校验（loopback 例外同轉接器）。
     * - [apiKeyRef] 只是 KeyVault 別名（形如 `provider_key/<uuid>`），絕不帶明文 key。
     * - `custom` 請走 [fromCustom]；誤傳時拋 `PRESET_CUSTOM_NEEDS_BASE_URL`。
     */
    fun fromPreset(
        presetId: String,
        apiKeyRef: String,
        providerId: String? = null,
    ): ProviderConfig {
        val preset = requirePreset(presetId)
        require(preset.id != CUSTOM_ID) { "PRESET_CUSTOM_NEEDS_BASE_URL" }
        require(apiKeyRef.isNotBlank()) { "PROVIDER_KEY_REF_BLANK" }
        validateBaseUrl(preset.baseUrl)
        return ProviderConfig(
            id = providerId ?: "preset:${preset.id}",
            label = preset.label,
            baseUrl = preset.baseUrl,
            protocol = preset.protocol,
            apiKeyRef = apiKeyRef,
        )
    }

    /**
     * 手填自訂 provider：[baseUrl] 原樣保留（不做正規化、不補斜線），
     * 僅做 [validateBaseUrl] 校验；[apiKeyRef] 同 [fromPreset] 為別名。
     */
    fun fromCustom(
        baseUrl: String,
        apiKeyRef: String,
        label: String = "自訂",
        protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS,
        providerId: String? = null,
    ): ProviderConfig {
        require(apiKeyRef.isNotBlank()) { "PROVIDER_KEY_REF_BLANK" }
        validateBaseUrl(baseUrl)
        return ProviderConfig(
            id = providerId ?: "preset:$CUSTOM_ID",
            label = label,
            baseUrl = baseUrl,
            protocol = protocol,
            apiKeyRef = apiKeyRef,
        )
    }

    /** 取某預設的預設模型（custom 回空字串，表示無預設）。 */
    fun defaultModelFor(presetId: String): String = requirePreset(presetId).defaultModel

    /**
     * 模型候選：預設模型置首（custom 略過），再併 `live` 與該 preset
     * 明確對應的 models.dev provider。未映射 preset/custom 僅保留 default/live。
     */
    fun listedModels(
        presetId: String,
        liveModelIds: List<String>,
        snapshot: ModelsDevSnapshot.Snapshot,
    ): List<String> {
        val preset = requirePreset(presetId)
        val liveWithDefault = if (preset.defaultModel.isEmpty() || liveModelIds.contains(preset.defaultModel)) {
            liveModelIds
        } else {
            listOf(preset.defaultModel) + liveModelIds
        }
        return ModelsDevSnapshot.mergeForProvider(
            liveModelIds = liveWithDefault,
            snapshot = snapshot,
            providerId = preset.modelsDevProviderId,
        )
    }
}
