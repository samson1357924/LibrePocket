package dev.librepocket.preset

import dev.librepocket.models.ModelsDevSnapshot
import dev.librepocket.provider.AnthropicProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.validateBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 預設目錄測試：baseUrl https、Anthropic 頭版本、custom 保留、快照模型合併。
 * 純 JVM，不碰網路、不碰轉接器行為。
 */
class PresetCatalogTest {

    @Test
    fun baseUrlConstantsExact() {
        assertEquals("https://api.openai.com/v1", ProviderCatalog.OPENAI_BASE_URL)
        assertEquals("https://api.anthropic.com", ProviderCatalog.ANTHROPIC_BASE_URL)
        assertEquals("https://api.x.ai/v1", ProviderCatalog.XAI_BASE_URL)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            ProviderCatalog.GEMINI_OPENAI_BASE_URL,
        )
        assertEquals("https://api.deepseek.com/v1", ProviderCatalog.DEEPSEEK_BASE_URL)
        assertEquals("https://openrouter.ai/api/v1", ProviderCatalog.OPENROUTER_BASE_URL)
        assertEquals("https://api.siliconflow.cn/v1", ProviderCatalog.SILICONFLOW_BASE_URL)
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ProviderCatalog.ALIBABA_BASE_URL,
        )
        assertEquals("https://api.moonshot.cn/v1", ProviderCatalog.MOONSHOT_BASE_URL)
    }

    @Test
    fun allPresetBaseUrlsAreHttpsAndValid() {
        for (preset in ProviderCatalog.PRESETS) {
            if (preset.id == ProviderCatalog.CUSTOM_ID) continue
            assertTrue(
                "preset ${preset.id} must be https, was ${preset.baseUrl}",
                preset.baseUrl.startsWith("https://"),
            )
            // 複用轉接器同款校验：通過即合法。
            validateBaseUrl(preset.baseUrl)
        }
    }

    @Test
    fun presetProtocolsMatchAdapters() {
        assertEquals(
            ProviderProtocol.ANTHROPIC,
            ProviderCatalog.requirePreset(ProviderCatalog.ANTHROPIC_ID).protocol,
        )
        for (id in listOf(
            ProviderCatalog.OPENAI_ID,
            ProviderCatalog.XAI_ID,
            ProviderCatalog.GEMINI_ID,
            ProviderCatalog.DEEPSEEK_ID,
            ProviderCatalog.OPENROUTER_ID,
            ProviderCatalog.SILICONFLOW_ID,
            ProviderCatalog.ALIBABA_ID,
            ProviderCatalog.MOONSHOT_ID,
        )) {
            assertEquals(
                "preset $id should ride Chat Completions",
                ProviderProtocol.CHAT_COMPLETIONS,
                ProviderCatalog.requirePreset(id).protocol,
            )
        }
    }

    @Test
    fun anthropicPresetUsesVersionedApiKeyHeader() {
        val preset = ProviderCatalog.requirePreset(ProviderCatalog.ANTHROPIC_ID)
        assertEquals(ProviderCatalog.AUTH_X_API_KEY, preset.authHeaderName)
        assertNull("Anthropic 走 x-api-key，不加 Bearer 前綴", preset.authScheme)
        assertEquals("2023-06-01", preset.anthropicVersion)
        // 與轉接器同值：目錄與實際送出的頭版本不得分叉。
        assertEquals(AnthropicProvider.ANTHROPIC_VERSION, preset.anthropicVersion)
        assertEquals(ProviderCatalog.ANTHROPIC_VERSION, preset.anthropicVersion)
    }

    @Test
    fun chatCompletionsPresetsUseBearerAuth() {
        for (id in listOf(
            ProviderCatalog.OPENAI_ID,
            ProviderCatalog.XAI_ID,
            ProviderCatalog.GEMINI_ID,
            ProviderCatalog.DEEPSEEK_ID,
            ProviderCatalog.OPENROUTER_ID,
            ProviderCatalog.SILICONFLOW_ID,
            ProviderCatalog.ALIBABA_ID,
            ProviderCatalog.MOONSHOT_ID,
        )) {
            val preset = ProviderCatalog.requirePreset(id)
            assertEquals("preset $id", ProviderCatalog.AUTH_AUTHORIZATION, preset.authHeaderName)
            assertEquals("preset $id", ProviderCatalog.AUTH_BEARER, preset.authScheme)
            assertNull("preset $id 不該帶 anthropic-version", preset.anthropicVersion)
            assertTrue("preset $id 要有預設模型", preset.defaultModel.isNotBlank())
        }
    }

    @Test
    fun fromPresetBuildsConfigWithoutKeyMaterial() {
        val ref = "provider_key/00000000-0000-0000-0000-000000000001"
        val config = ProviderCatalog.fromPreset(ProviderCatalog.OPENAI_ID, ref)
        assertEquals("preset:openai", config.id)
        assertEquals("OpenAI", config.label)
        assertEquals(ProviderCatalog.OPENAI_BASE_URL, config.baseUrl)
        assertEquals(ProviderProtocol.CHAT_COMPLETIONS, config.protocol)
        // 只存別名，不存明文。
        assertEquals(ref, config.apiKeyRef)

        val anthropic = ProviderCatalog.fromPreset(ProviderCatalog.ANTHROPIC_ID, ref)
        assertEquals(ProviderCatalog.ANTHROPIC_BASE_URL, anthropic.baseUrl)
        assertEquals(ProviderProtocol.ANTHROPIC, anthropic.protocol)
    }

    @Test
    fun fromPresetRejectsUnknownIdBlankRefAndCustomPlaceholder() {
        try {
            ProviderCatalog.fromPreset("no-such", "provider_key/x")
            fail("unknown preset 應拒收")
        } catch (_: IllegalArgumentException) {
        }
        try {
            ProviderCatalog.fromPreset(ProviderCatalog.OPENAI_ID, "  ")
            fail("空白 apiKeyRef 應拒收")
        } catch (_: IllegalArgumentException) {
        }
        try {
            ProviderCatalog.fromPreset(ProviderCatalog.CUSTOM_ID, "provider_key/x")
            fail("custom 佔位應導向 fromCustom")
        } catch (e: IllegalArgumentException) {
            assertEquals("PRESET_CUSTOM_NEEDS_BASE_URL", e.message)
        }
    }

    @Test
    fun customBaseUrlPreservedVerbatim() {
        val raw = "https://my-gateway.example.com/v1/"
        val config = ProviderCatalog.fromCustom(
            baseUrl = raw,
            apiKeyRef = "provider_key/custom-1",
            label = "自家閘道",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        // 手填保留：原樣保留，不補不砍斜線。
        assertEquals(raw, config.baseUrl)
        assertEquals("自家閘道", config.label)
        assertEquals(ProviderProtocol.CHAT_COMPLETIONS, config.protocol)

        val anthropicLike = ProviderCatalog.fromCustom(
            baseUrl = "https://proxy.example.com/anthropic",
            apiKeyRef = "provider_key/custom-2",
            protocol = ProviderProtocol.ANTHROPIC,
        )
        assertEquals(ProviderProtocol.ANTHROPIC, anthropicLike.protocol)

        // 公網明文仍拒收（複用 validateBaseUrl）。
        try {
            ProviderCatalog.fromCustom("http://api.example.com/v1", "provider_key/x")
            fail("公網 http 應拒收")
        } catch (_: IllegalArgumentException) {
        }
        // loopback http 是開發例外，放行。
        val loopback = ProviderCatalog.fromCustom("http://127.0.0.1:8080/v1", "provider_key/x")
        assertEquals("http://127.0.0.1:8080/v1", loopback.baseUrl)
    }

    @Test
    fun snapshotMergeKeepsDefaultModelLiveFirst() {
        val snapshot = ModelsDevSnapshot.parse(
            """{"models":[
                |{"id":"openai/gpt-4o-mini","reasoning":false,"tools":true},
                |{"id":"anthropic/claude-haiku-4-5","reasoning":false,"tools":true},
                |{"id":"google/gemini-2-flash","reasoning":true,"tools":true}
                |]}""".trimMargin(),
            nowMs = 1_700_000_000_000L,
        )
        // openai 預設模型置首，live 其次，快照補齊且不重複。
        val merged = ProviderCatalog.listedModels(
            presetId = ProviderCatalog.OPENAI_ID,
            liveModelIds = listOf("my-live-model"),
            snapshot = snapshot,
        )
        assertEquals("gpt-4o-mini", merged[0])
        assertEquals("my-live-model", merged[1])
        assertTrue(merged.contains("openai/gpt-4o-mini"))
        assertTrue(merged.contains("anthropic/claude-haiku-4-5"))
        assertTrue(merged.contains("google/gemini-2-flash"))
        assertEquals(merged.size, merged.distinct().size)

        // live 已含預設模型時不重複置首。
        val merged2 = ProviderCatalog.listedModels(
            presetId = ProviderCatalog.OPENAI_ID,
            liveModelIds = listOf("gpt-4o-mini", "my-live-model"),
            snapshot = snapshot,
        )
        assertEquals(listOf("gpt-4o-mini", "my-live-model"), merged2.take(2))

        // custom 無預設模型：直接複用快照合併語義。
        val mergedCustom = ProviderCatalog.listedModels(
            presetId = ProviderCatalog.CUSTOM_ID,
            liveModelIds = listOf("custom/local"),
            snapshot = snapshot,
        )
        assertEquals(
            listOf(
                "custom/local",
                "openai/gpt-4o-mini",
                "anthropic/claude-haiku-4-5",
                "google/gemini-2-flash",
            ),
            mergedCustom,
        )
    }
}
