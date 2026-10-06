package dev.librepocket.agent.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupValidationTest {

    @Test
    fun deriveRefUsesVaultAliasForm() {
        assertEquals("preset:openai", SetupValidation.deriveProviderId("openai"))
        assertEquals(
            "provider_key/preset:openai",
            SetupValidation.deriveApiKeyRef("preset:openai"),
        )
    }

    @Test
    fun validInputPasses() {
        assertNull(SetupValidation.validate("openai", "https://api.openai.com/v1", "sk-12345678"))
    }

    @Test
    fun unknownPresetRejected() {
        assertEquals(
            "SETUP_UNKNOWN_PRESET",
            SetupValidation.validate("nope", "https://api.openai.com/v1", "sk-12345678"),
        )
    }

    @Test
    fun badUrlsRejected() {
        assertEquals(
            "SETUP_BASE_URL_BLANK",
            SetupValidation.validate("openai", "  ", "sk-12345678"),
        )
        assertEquals(
            "PROVIDER_URL_MUST_BE_HTTPS",
            SetupValidation.validate("openai", "http://evil.example.com", "sk-12345678"),
        )
        // Loopback http is allowed for local dev (with release-time red warning).
        assertNull(SetupValidation.validate("custom", "http://127.0.0.1:11434/v1", "sk-12345678"))
    }

    @Test
    fun shortKeyRejected() {
        assertEquals("SETUP_KEY_TOO_SHORT", SetupValidation.validate("openai", "https://api.openai.com/v1", "  short "))
    }
}
