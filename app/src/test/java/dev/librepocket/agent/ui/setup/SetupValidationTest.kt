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
    fun deriveEndpointIdKeepsPresetIdsForBuiltins() {
        assertEquals("preset:openai", SetupValidation.deriveEndpointId("openai", "https://api.openai.com/v1"))
        assertEquals("preset:anthropic", SetupValidation.deriveEndpointId("anthropic", "https://api.anthropic.com"))
    }

    @Test
    fun deriveEndpointIdIsolatesCustomUrlsStably() {
        val urlA = "http://127.0.0.1:8123/v1"
        val urlB = "http://127.0.0.1:8124/v1"
        val idA = SetupValidation.deriveEndpointId("custom", urlA)
        val idB = SetupValidation.deriveEndpointId("custom", urlB)
        // Stable: same input re-derives the same id (rotation/model-only
        // re-saves keep the entity).
        assertEquals(idA, SetupValidation.deriveEndpointId("custom", urlA))
        assertEquals(idA, SetupValidation.deriveEndpointId("custom", "  $urlA  "))
        // Isolated: distinct URLs never share the providerId namespace, and
        // neither collides with the legacy shared shape.
        assertTrue(idA.startsWith("custom:"))
        assertTrue(idB.startsWith("custom:"))
        assertTrue(idA != idB)
        assertTrue(idA != "preset:custom")
        assertTrue(idB != "preset:custom")
        // Vault/journal/provenance-safe charset: lowercase hex, fixed width.
        for (id in listOf(idA, idB)) {
            val hash = id.removePrefix("custom:")
            assertEquals(32, hash.length)
            assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun legacyEndpointIdDetectsOnlyPreStage1CustomRows() {
        // Exact old shape: shared preset:custom id.
        assertTrue(SetupValidation.isLegacyEndpointId("preset:custom", "custom", "http://127.0.0.1:8123/v1"))
        // Hand-written junk for custom is legacy too (fail closed).
        assertTrue(SetupValidation.isLegacyEndpointId("whatever", "custom", "http://127.0.0.1:8123/v1"))
        // Current shapes pass.
        val urlA = "http://127.0.0.1:8123/v1"
        val current = SetupValidation.deriveEndpointId("custom", urlA)
        assertTrue(!SetupValidation.isLegacyEndpointId(current, "custom", urlA))
        // A respelled URL is a distinct entity (fail-closed direction).
        assertTrue(SetupValidation.isLegacyEndpointId(current, "custom", "$urlA/"))
        // Built-ins are unaffected.
        assertTrue(!SetupValidation.isLegacyEndpointId("preset:openai", "openai", "https://api.openai.com/v1"))
    }

    @Test
    fun policyResourceStaysPresetScoped() {
        // Stage1 freezes authorization granularity: every endpoint under a
        // preset — including per-URL custom entities — evaluates policy
        // against the shared preset resource (Stage2 #13 revisits this).
        assertEquals("preset:custom", SetupValidation.policyResourceForPreset("custom"))
        assertEquals("preset:openai", SetupValidation.policyResourceForPreset("openai"))
    }

    @Test
    fun displayLabelResolvesNewAndOldShapes() {
        assertEquals("OpenAI", SetupValidation.displayLabelForEndpointId("preset:openai"))
        assertEquals("自訂", SetupValidation.displayLabelForEndpointId("preset:custom"))
        assertEquals(
            "自訂",
            SetupValidation.displayLabelForEndpointId(SetupValidation.deriveEndpointId("custom", "http://127.0.0.1:8123/v1")),
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
        assertNull(SetupValidation.validate("openai", "https://api.openai.com/v1", "unit-test-key-12345678"))
    }

    @Test
    fun unknownPresetRejected() {
        assertEquals(
            "SETUP_UNKNOWN_PRESET",
            SetupValidation.validate("nope", "https://api.openai.com/v1", "unit-test-key-12345678"),
        )
    }

    @Test
    fun badUrlsRejected() {
        assertEquals(
            "SETUP_BASE_URL_BLANK",
            SetupValidation.validate("openai", "  ", "unit-test-key-12345678"),
        )
        assertEquals(
            "PROVIDER_URL_MUST_BE_HTTPS",
            SetupValidation.validate("openai", "http://evil.example.com", "unit-test-key-12345678"),
        )
        // Loopback http is allowed for local dev (with release-time red warning).
        assertNull(SetupValidation.validate("custom", "http://127.0.0.1:11434/v1", "unit-test-key-12345678"))
    }

    @Test
    fun shortKeyRejected() {
        assertEquals("SETUP_KEY_TOO_SHORT", SetupValidation.validate("openai", "https://api.openai.com/v1", "  short "))
    }
}
