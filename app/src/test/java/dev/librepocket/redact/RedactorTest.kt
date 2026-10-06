package dev.librepocket.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 unit tests (spec §10.3 / §11.3): R1–R12, ≥3 positives + ≥2 negatives each,
 * plus redactError, hit counts, and the Luhn gate for R10.
 *
 * Pure JVM: [Redactor] has zero Android dependencies.
 */
class RedactorTest {

    private fun assertRedacted(input: String, vararg absent: String) {
        val out = Redactor.redact(input).text
        assertTrue("expected a redaction marker in: $out", "⟦REDACTED" in out)
        for (secret in absent) {
            assertTrue("secret leaked in: $out", secret !in out)
        }
    }

    private fun assertUnchanged(input: String) {
        assertEquals(input, Redactor.redact(input).text)
    }

    // ---- R1 API_KEY_VALUE ----

    @Test fun r1_skPrefix() {
        assertRedacted("key is sk-abcDEF1234567890 done", "sk-abcDEF1234567890")
    }

    @Test fun r1_slackToken() {
        assertRedacted("token xoxb-1234567890abcdefghij here", "xoxb-1234567890abcdefghij")
    }

    @Test fun r1_githubAndGoogle() {
        assertRedacted("a ghp_abcdefgh1234567890 b", "ghp_abcdefgh1234567890")
        assertRedacted("a AIzaSyDabcdefgh1234567890 b", "AIzaSyDabcdefgh1234567890")
        assertRedacted("a gsk_abcdefgh1234567890 b", "gsk_abcdefgh1234567890")
    }

    @Test fun r1_tooShortUnchanged() {
        assertUnchanged("sk-short")
        assertUnchanged("the task is pending")
    }

    // ---- R2 BEARER_TOKEN ----

    @Test fun r2_basic() {
        assertRedacted("Authorization: Bearer abcdef123456 rest", "abcdef123456")
    }

    @Test fun r2_jwtDots() {
        assertRedacted(
            "bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SflKxwRJ",
            "eyJhbGciOiJIUzI1NiJ9",
        )
    }

    @Test fun r2_padding() {
        assertRedacted("Bearer ABC.DEF_GHI-JKL+MN/OP== end", "ABC.DEF_GHI-JKL+MN/OP==")
    }

    @Test fun r2_negatives() {
        assertUnchanged("bearer")
        assertUnchanged("bearers are coming")
    }

    // ---- R3 JSON_KEY_FIELD ----

    @Test fun r3_jsonApiKey() {
        val out = Redactor.redact("\"api_key\": \"supersecretvalue\"").text
        assertTrue(out, out.contains("\"api_key\": \"⟦REDACTED⟧"))
        assertTrue(out, !out.contains("supersecretvalue"))
    }

    @Test fun r3_passwordEquals() {
        val out = Redactor.redact("password= hunter2hunter2").text
        assertTrue(out, out.contains("password= ⟦REDACTED⟧"))
    }

    @Test fun r3_compactJson() {
        val out = Redactor.redact("{\"token\":\"abc12345\"}").text
        assertTrue(out, out.contains("{\"token\":\"⟦REDACTED⟧\"}"))
    }

    @Test fun r3_negatives() {
        assertUnchanged("\"api_key\": \"ab\"")
        assertUnchanged("the monkey ate a banana")
    }

    // ---- R4 URL_CREDENTIAL ----

    @Test fun r4_basic() {
        val out = Redactor.redact("go https://alice:s3cr3t@example.com/path now").text
        assertTrue(out, out.contains("https://⟦REDACTED⟧@example.com/path"))
    }

    @Test fun r4_httpToo() {
        assertRedacted("http://bob:pw123456@host.tld/x", "bob:pw123456@")
    }

    @Test fun r4_withPort() {
        assertRedacted("https://u:p@host.tld:8443/a?b=c", "u:p@")
    }

    @Test fun r4_negatives() {
        assertUnchanged("https://example.com/no-auth")
        assertUnchanged("see https://example.com/@handle here")
    }

    // ---- R5 URL_TOKEN_PARAM ----

    @Test fun r5_apiKeyParam() {
        val out = Redactor.redact("https://api.example.com/v1?api_key=SECRET123&x=1").text
        assertEquals("https://api.example.com/v1?api_key=⟦REDACTED⟧&x=1", out)
    }

    @Test fun r5_tokenParam() {
        val out = Redactor.redact("https://h.test/p?token=abcDEF123").text
        assertEquals("https://h.test/p?token=⟦REDACTED⟧", out)
    }

    @Test fun r5_accessTokenParam() {
        val out = Redactor.redact("https://h.test/p?a=1&access_token=tok99zz").text
        assertEquals("https://h.test/p?a=1&access_token=⟦REDACTED⟧", out)
    }

    @Test fun r5_negatives() {
        assertUnchanged("https://api.example.com/v1?x=1&y=2")
        assertUnchanged("talking about tokens in prose")
    }

    // ---- R6 EMAIL ----

    @Test fun r6_basic() {
        assertRedacted("mail user@example.com plz", "user@example.com")
    }

    @Test fun r6_plusTag() {
        assertRedacted("a first.last+tag@mail.example.co b", "first.last+tag@mail.example.co")
    }

    @Test fun r6_shortDomain() {
        assertRedacted("x a_b-c@x.io y", "a_b-c@x.io")
    }

    @Test fun r6_negatives() {
        assertUnchanged("user@localhost")
        assertUnchanged("not an email @ here")
    }

    // ---- R7 PHONE_GENERIC (TW mobile) ----

    @Test fun r7_plain() {
        assertRedacted("call 0912345678 now", "0912345678")
    }

    @Test fun r7_dashed() {
        assertRedacted("call 0912-345-678 now", "0912-345-678")
    }

    @Test fun r7_countryCode() {
        assertRedacted("call +886912345678 now", "+886912345678")
    }

    @Test fun r7_negatives() {
        assertUnchanged("call 02-1234-5678")
        assertUnchanged("number 1234567")
        assertUnchanged("091234567")
    }

    // ---- R8 PHONE_INTL ----

    @Test fun r8_us() {
        assertRedacted("tel +14155552671 ok", "+14155552671")
    }

    @Test fun r8_uk() {
        assertRedacted("tel +442079460958 ok", "+442079460958")
    }

    @Test fun r8_jp() {
        assertRedacted("tel +81312345678 ok", "+81312345678")
    }

    @Test fun r8_negatives() {
        assertUnchanged("tel 14155552671 ok")
        assertUnchanged("plus +12 ok")
    }

    // ---- R9 ID_TW ----

    @Test fun r9_a() {
        assertRedacted("id A123456789 end", "A123456789")
    }

    @Test fun r9_b() {
        assertRedacted("id B212345678 end", "B212345678")
    }

    @Test fun r9_x() {
        assertRedacted("id X198765432 end", "X198765432")
    }

    @Test fun r9_negatives() {
        assertUnchanged("id a123456789 end")
        assertUnchanged("id A323456789 end")
        assertUnchanged("id Z1234567 end")
    }

    // ---- R10 CARD_16 (regex + Luhn) ----

    @Test fun r10_visaSpaced() {
        assertRedacted("card 4539 1488 0343 6467 end", "4539 1488 0343 6467")
    }

    @Test fun r10_amexPlain() {
        assertRedacted("card 378282246310005 end", "378282246310005")
    }

    @Test fun r10_discoverDashed() {
        assertRedacted("card 6011-1111-1111-1117 end", "6011-1111-1111-1117")
    }

    @Test fun r10_luhnRejectsNonCard() {
        // 16 digits but fails Luhn: must survive untouched.
        assertUnchanged("ref 1234 5678 9012 3456 end")
        assertUnchanged("order 12345")
        assertUnchanged("date 2024-02-29 note")
    }

    // ---- R11 IPV4_PRIVATE ----

    @Test fun r11_classA() {
        assertRedacted("host 10.0.0.5 down", "10.0.0.5")
    }

    @Test fun r11_classC() {
        assertRedacted("host 192.168.1.100 down", "192.168.1.100")
    }

    @Test fun r11_classB() {
        assertRedacted("host 172.16.0.1 down", "172.16.0.1")
        assertRedacted("host 172.31.255.255 down", "172.31.255.255")
    }

    @Test fun r11_negatives() {
        assertUnchanged("dns 8.8.8.8 ok")
        assertUnchanged("weird 999.999.1.1 ok")
    }

    // ---- R12 ANDROID_ID_LIKE ----

    @Test fun r12_androidId() {
        val out = Redactor.redact("android_id: abc123XYZ end").text
        assertTrue(out, out.contains("android_id=⟦REDACTED⟧"))
        assertTrue(out, !out.contains("abc123XYZ"))
    }

    @Test fun r12_imei() {
        val out = Redactor.redact("IMEI=123456789012345 end").text
        assertTrue(out, out.contains("IMEI=⟦REDACTED⟧"))
    }

    @Test fun r12_serial() {
        val out = Redactor.redact("serial: HT123456 end").text
        assertTrue(out, out.contains("serial=⟦REDACTED⟧"))
    }

    @Test fun r12_negatives() {
        assertUnchanged("the serial killer arrived")
        assertUnchanged("device is ready")
    }

    // ---- hits ----

    @Test fun hits_countPerRule() {
        val result = Redactor.redact("mail a@b.co card 4539 1488 0343 6467 ok")
        assertEquals(1, result.hits["EMAIL"])
        assertEquals(1, result.hits["CARD_16"])
        assertEquals(0, result.hits["PHONE_GENERIC"])
        assertEquals(12, result.hits.size)
    }

    @Test fun hits_tableOrder() {
        val result = Redactor.redact("nothing here")
        assertEquals(
            listOf(
                "API_KEY_VALUE", "BEARER_TOKEN", "JSON_KEY_FIELD", "URL_CREDENTIAL",
                "URL_TOKEN_PARAM", "EMAIL", "PHONE_GENERIC", "PHONE_INTL", "ID_TW",
                "CARD_16", "IPV4_PRIVATE", "ANDROID_ID_LIKE",
            ),
            result.hits.keys.toList(),
        )
    }

    // ---- redactError ----

    @Test fun redactError_stripsUrlToken() {
        val out = Redactor.redactError(
            "request failed: https://api.example.com/v1?token=SECRETVALUE123&x=1",
        )
        assertTrue(out, !out.contains("SECRETVALUE123"))
        assertTrue(out, out.contains("token=⟦REDACTED⟧"))
    }

    @Test fun redactError_truncatesTo500() {
        val out = Redactor.redactError("x".repeat(600))
        assertEquals(500, out.length)
    }

    @Test fun redactError_keepsNonKeyContent() {
        // R6 (email) is out of the error subset by design: only key/token/URL/IP scope.
        val out = Redactor.redactError("notify user@example.com about HTTP 429 rate_limited")
        assertTrue(out, out.contains("user@example.com"))
        assertTrue(out, out.contains("HTTP 429"))
    }

    @Test fun redactError_stripsBearerAndPrivateIp() {
        val out = Redactor.redactError("Bearer abcdef123456 from 192.168.0.7 denied")
        assertTrue(out, !out.contains("abcdef123456"))
        assertTrue(out, !out.contains("192.168.0.7"))
    }
}
