package dev.librepocket.voice

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * OpenAI TTS 門禁單元測試（純 JVM）：
 * - `OpenAiSpeechGate`：全 flavor 支援、雙開關要求、無 key 拒絕原因；
 * - `ToolRegistry` 靜態定義與投影：全風味支援、雙開關語義、預設關閉且不進模型工具名單；
 * - `VoiceTools` 開關 helpers 與常數釘選。
 */
class OpenAiSpeechGateTest {

    // ---- ToolDef 靜態定義 ----

    @Test
    fun openAiSpeak_isWriteAllFlavorsDefaultOff() {
        val tool = ToolRegistry.find(VoiceTools.OPENAI_SPEAK_NAME)!!
        assertEquals(SideEffect.WRITE, tool.sideEffect)
        assertEquals(setOf(Flavor.PLAY, Flavor.FOSS, Flavor.GITHUB), tool.supportedFlavors)
        assertEquals(VoiceTools.SWITCH_OPENAI, tool.annotations.requiresSwitch)
        assertEquals(false, tool.annotations.switchDefault)
        assertEquals(VoiceTools.SWITCH_OPENAI_DEFAULT, false)

        // 雙開關：第二開關 voice_output（預設開）
        assertEquals(VoiceTools.SWITCH_OUTPUT, tool.annotations.requiresSwitch2)
        assertEquals(true, tool.annotations.switchDefault2)
        assertEquals(VoiceTools.SWITCH_OUTPUT_DEFAULT, true)

        // 安全不變量：未接線前不得進模型名單
        assertFalse(tool.executionReady)
    }

    // ---- 投影：全風味支援，雙開關皆開才可見 ----

    @Test
    fun openAi_hiddenByDefaultOnAllFlavors() {
        for (flavor in Flavor.entries) {
            val ctx = ProjectionContext(flavor = flavor)
            val projected = ToolRegistry.projectAll(ctx)[VoiceTools.OPENAI_SPEAK_NAME]!!
            assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
            assertEquals(DenyReason.USER_DISABLED, projected.reason)
            assertFalse(
                ToolRegistry.projectedTools(ctx).any { it.name == VoiceTools.OPENAI_SPEAK_NAME },
            )
        }
    }

    @Test
    fun openAi_visibleOnAllFlavorsWhenBothSwitchesOn() {
        for (flavor in Flavor.entries) {
            val ctx = ProjectionContext(
                flavor = flavor,
                userSwitches = mapOf(
                    VoiceTools.SWITCH_OPENAI to true,
                    VoiceTools.SWITCH_OUTPUT to true,
                ),
            )
            val projected = ToolRegistry.projectAll(ctx)[VoiceTools.OPENAI_SPEAK_NAME]!!
            assertEquals(CapabilityLevel.NATIVE, projected.level)
            assertTrue(
                ToolRegistry.projectedTools(ctx).any { it.name == VoiceTools.OPENAI_SPEAK_NAME },
            )
        }
    }

    @Test
    fun openAi_voiceOutputOffIsUserDisabledEvenIfOpenAiSwitchOn() {
        for (flavor in Flavor.entries) {
            val ctx = ProjectionContext(
                flavor = flavor,
                userSwitches = mapOf(
                    VoiceTools.SWITCH_OPENAI to true,
                    VoiceTools.SWITCH_OUTPUT to false,
                ),
            )
            val projected = ToolRegistry.projectAll(ctx)[VoiceTools.OPENAI_SPEAK_NAME]!!
            assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
            assertEquals(DenyReason.USER_DISABLED, projected.reason)
            assertFalse(
                ToolRegistry.projectedTools(ctx).any { it.name == VoiceTools.OPENAI_SPEAK_NAME },
            )
        }
    }

    // ---- 門禁邏輯：OpenAiSpeechGate ----

    @Test
    fun gate_supportsAllFlavorsWhenBothSwitchesOn() {
        for (flavor in Flavor.entries) {
            assertTrue(OpenAiSpeechGate.canUse(flavor, voiceOutputOn = true, openAiTtsOn = true))
        }
        assertTrue(OpenAiSpeechGate.canUse(voiceOutputOn = true, openAiTtsOn = true))
    }

    @Test
    fun gate_deniesWhenEitherSwitchOff() {
        assertFalse(OpenAiSpeechGate.canUse(voiceOutputOn = false, openAiTtsOn = true))
        assertFalse(OpenAiSpeechGate.canUse(voiceOutputOn = true, openAiTtsOn = false))
        assertFalse(OpenAiSpeechGate.canUse(voiceOutputOn = false, openAiTtsOn = false))

        for (flavor in Flavor.entries) {
            assertFalse(OpenAiSpeechGate.canUse(flavor, voiceOutputOn = false, openAiTtsOn = true))
            assertFalse(OpenAiSpeechGate.canUse(flavor, voiceOutputOn = true, openAiTtsOn = false))
        }
    }

    @Test
    fun gate_reasons() {
        for (flavor in Flavor.entries) {
            // 開關任一關閉 -> USER_DISABLED
            assertEquals(
                DenyReason.USER_DISABLED,
                OpenAiSpeechGate.denyReason(flavor, voiceOutputOn = false, openAiTtsOn = true, hasKey = true),
            )
            assertEquals(
                DenyReason.USER_DISABLED,
                OpenAiSpeechGate.denyReason(flavor, voiceOutputOn = true, openAiTtsOn = false, hasKey = true),
            )
            assertEquals(
                DenyReason.USER_DISABLED,
                OpenAiSpeechGate.denyReason(flavor, voiceOutputOn = false, openAiTtsOn = false, hasKey = true),
            )

            // 開關皆開但無 key -> NO_PRIVILEGE
            assertEquals(
                DenyReason.NO_PRIVILEGE,
                OpenAiSpeechGate.denyReason(flavor, voiceOutputOn = true, openAiTtsOn = true, hasKey = false),
            )

            // 條件皆滿足 -> 放行 (null)
            assertNull(
                OpenAiSpeechGate.denyReason(flavor, voiceOutputOn = true, openAiTtsOn = true, hasKey = true),
            )
        }

        // 不帶 flavor 的過載
        assertEquals(
            DenyReason.USER_DISABLED,
            OpenAiSpeechGate.denyReason(voiceOutputOn = false, openAiTtsOn = true, hasKey = true),
        )
        assertEquals(
            DenyReason.USER_DISABLED,
            OpenAiSpeechGate.denyReason(voiceOutputOn = true, openAiTtsOn = false, hasKey = true),
        )
        assertEquals(
            DenyReason.NO_PRIVILEGE,
            OpenAiSpeechGate.denyReason(voiceOutputOn = true, openAiTtsOn = true, hasKey = false),
        )
        assertNull(
            OpenAiSpeechGate.denyReason(voiceOutputOn = true, openAiTtsOn = true, hasKey = true),
        )
    }

    // ---- VoiceTools 常數與 Helpers ----

    @Test
    fun openAiKeyRef_isPinned() {
        assertEquals("openai_tts", VoiceTools.OPENAI_KEY_REF)
        assertEquals("openai_tts", VoiceTools.SWITCH_OPENAI)
        assertEquals(false, VoiceTools.SWITCH_OPENAI_DEFAULT)
        assertEquals("voice.speak.openai", VoiceTools.OPENAI_SPEAK_NAME)
    }

    @Test
    fun openAiTtsEnabled_helpers() {
        // Map 判定
        assertFalse(VoiceTools.openAiTtsEnabled(emptyMap()))
        assertFalse(VoiceTools.openAiTtsEnabled(mapOf(VoiceTools.SWITCH_OPENAI to false)))
        assertTrue(VoiceTools.openAiTtsEnabled(mapOf(VoiceTools.SWITCH_OPENAI to true)))

        // Snapshot 判定
        assertFalse(VoiceTools.openAiTtsEnabled(VoiceSwitchSnapshot.LOADING))
        val failed = VoiceSwitchSnapshot(
            switches = mapOf(VoiceTools.SWITCH_OPENAI to true),
            loaded = true,
            loadFailed = true,
        )
        assertFalse(VoiceTools.openAiTtsEnabled(failed))

        val loadedOff = VoiceSwitchSnapshot(
            switches = mapOf(VoiceTools.SWITCH_OPENAI to false),
            loaded = true,
            loadFailed = false,
        )
        assertFalse(VoiceTools.openAiTtsEnabled(loadedOff))

        val loadedOn = VoiceSwitchSnapshot(
            switches = mapOf(VoiceTools.SWITCH_OPENAI to true),
            loaded = true,
            loadFailed = false,
        )
        assertTrue(VoiceTools.openAiTtsEnabled(loadedOn))
    }

    @Test
    fun openAiSpeak_hasNoPermissionLiterals() {
        val tool = ToolRegistry.find(VoiceTools.OPENAI_SPEAK_NAME)!!
        val blob = tool.name + tool.description + tool.jsonSchema +
            (tool.annotations.requiresPermission ?: "")
        assertFalse(blob.contains("RECORD_AUDIO"))
        assertFalse(blob.contains("SEND_SMS"))
    }

    // ---- B2: 請求邊界通過釘（與 OpenAiTtsClientTest 的拒絕釘對稱，不衝突） ----

    @Test
    fun openAiRequest_exact4096Chars_passesValidation() {
        // 上限恰好 4096 通過（>4096 才拒，見 OpenAiTtsClientTest 回絕側）。
        val req = OpenAiTtsRequest(input = "A".repeat(OpenAiTtsRequest.MAX_INPUT_LENGTH))
        assertEquals(OpenAiTtsRequest.MAX_INPUT_LENGTH, req.input.length)
        assertEquals(OpenAiTtsModels.MAX_INPUT_LENGTH, OpenAiTtsRequest.MAX_INPUT_LENGTH)
        assertEquals(4096, OpenAiTtsRequest.MAX_INPUT_LENGTH)
    }

    @Test
    fun openAiRequest_speedEndpoints_passValidation() {
        // speed 端點 0.25 / 4.0 通過（區間外才拒，見 OpenAiTtsClientTest 回絕側）。
        for (speed in listOf(OpenAiTtsRequest.MIN_SPEED, OpenAiTtsRequest.MAX_SPEED)) {
            val req = OpenAiTtsRequest(input = "Hello", speed = speed)
            assertEquals(speed, req.speed)
        }
        assertEquals(0.25f, OpenAiTtsRequest.MIN_SPEED)
        assertEquals(4.0f, OpenAiTtsRequest.MAX_SPEED)
    }

    // ---- B3: redirect 不跟隨釘（302+Location 單發即停） ----
    //
    // 非 JSON 200 截斷側已有 OpenAiTtsClientTest 覆蓋
    //（errorHandling_502HtmlError：HTML 錯誤體截斷；
    // errorHandling_jsonMessageWithControlChars_isSingleLineAndBounded：200 字元截斷），
    // 此處不再重複。

    @Test
    fun openAiClient_doesNotFollowRedirect_302WithLocation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://evil.example.invalid/v1/audio/speech"),
            )
            val client = OpenAiTtsClient(
                baseUrl = server.url("/").toString(),
                allowTestLoopback = true,
                apiKey = { "test-key".toCharArray() },
            )
            try {
                client.synthesize(OpenAiTtsRequest(input = "Hello"))
                fail("Expected OpenAiTtsException for 302 redirect")
            } catch (e: OpenAiTtsException) {
                assertEquals(302, e.statusCode)
                // 不跟隨跳轉：恰一發，無第二發外送憑證。
                assertEquals(1, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }
}
