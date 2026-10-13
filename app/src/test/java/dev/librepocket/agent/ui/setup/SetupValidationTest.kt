package dev.librepocket.agent.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun stagedAliasIsPerOperationAndResolvesFromRef() {
        val a = SetupValidation.stagedAlias("preset:openai", "abc123de")
        val b = SetupValidation.stagedAlias("preset:openai", "f00dbabe")
        assertTrue(a != b)
        assertTrue(a.startsWith("preset:openai#staged-"))
        assertEquals("provider_key/$a", SetupValidation.stagedApiKeyRef("preset:openai", "abc123de"))
        assertEquals(a, SetupValidation.aliasFromRef("provider_key/$a"))
        assertEquals("preset:openai", SetupValidation.aliasFromRef("provider_key/preset:openai"))
    }

    @Test
    fun newStagedAliasIsRestartUnique() {
        // Full-UUID 32-hex suffix: every mint is distinct, even back-to-back
        // in one process — a relaunch can therefore never re-mint a committed
        // live alias (the counter-restart collision this replaces). Residual
        // ~2^-122 risk is additionally fenced by the ViewModel mint-time
        // inequality + existence guard (fail-closed).
        val seen = (1..32).map { SetupValidation.newStagedAlias("preset:openai") }.toSet()
        assertEquals(32, seen.size)
        for (alias in seen) {
            assertTrue(alias.startsWith("preset:openai#staged-"))
            val suffix = alias.removePrefix("preset:openai#staged-")
            assertEquals(32, suffix.length)
            assertTrue(suffix.all { it in '0'..'9' || it in 'a'..'f' })
            // Vault-safe: no blank/NUL/newline (passes requireValidProviderId).
            assertTrue(suffix.isNotBlank())
            assertTrue('\u0000' !in suffix && '\n' !in suffix && '\r' !in suffix)
        }
    }

    @Test
    fun newStagedSuffixIsFullUuid32Hex() {
        val suffix = SetupValidation.newStagedSuffix()
        assertEquals(32, suffix.length)
        assertTrue(suffix.all { it in '0'..'9' || it in 'a'..'f' })
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
