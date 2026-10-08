package dev.librepocket.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 unit tests (spec §10.3 / §11.3): R1–R13, ≥3 positives + ≥2 negatives each,
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
        // Short unquoted values retain the legacy minimum-length behavior.
        // Quoted JSON credentials are covered separately and redact at any length.
        assertUnchanged("api_key=ab")
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

    @Test fun r4_userOnlyUserinfo() {
        val out = Redactor.redact("go https://alice@example.com/path now").text
        assertTrue(out, out.contains("https://⟦REDACTED⟧@example.com/path"))
        assertTrue(out, !out.contains("alice@"))
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

    // ---- R13 GEO_COORD (B3: NavigationTool geo URIs + bare lat,lng pairs) ----

    @Test fun r13_geoUri() {
        assertRedacted("nav geo:25.0478,121.5170 go", "25.0478,121.5170")
    }

    @Test fun r13_geoUriWithQuery() {
        // systema/NavigationTool shape: coords + ?q= label must vanish as a whole.
        val out = Redactor.redact("open geo:25.0478,121.5170?q=北車 now").text
        assertTrue(out, out.contains("⟦REDACTED:GEO⟧"))
        assertTrue(out, !out.contains("25.0478"))
        assertTrue(out, !out.contains("121.5170"))
        assertTrue(out, !out.contains("北車"))
    }

    @Test fun r13_geoZeroPlaceholder() {
        val out = Redactor.redact("pin geo:0,0?q=台北車站 ok").text
        assertTrue(out, out.contains("⟦REDACTED:GEO⟧"))
        assertTrue(out, !out.contains("台北車站"))
    }

    @Test fun r13_barePair() {
        assertRedacted("meet at 25.0478,121.517 tomorrow", "25.0478,121.517")
    }

    @Test fun r13_barePairWithSpace() {
        assertRedacted("at 25.0478, 121.5170 sharp", "25.0478", "121.5170")
    }

    @Test fun r13_labeledPair() {
        assertRedacted("座標：25.0478,121.5170集合", "25.0478", "121.5170")
    }

    @Test fun r13_splitLatLngParams() {
        // Web-fallback style "?lat=..&lng=..": each axis redacted on its own.
        assertRedacted("go ?lat=25.0478&lng=121.517 end", "25.0478", "121.517")
    }

    @Test fun r13_insideMapUrl() {
        // OSM web fallback (NavigationTool.buildWebIntent) must not leak coords.
        val out = Redactor.redact(
            "see https://www.openstreetmap.org/search?query=25.0478,121.517 ok",
        ).text
        assertEquals(
            "see https://www.openstreetmap.org/search?query=⟦REDACTED:GEO⟧ ok",
            out,
        )
    }

    @Test fun r13_negatives() {
        assertUnchanged("see geo maps here")
        assertUnchanged("version 1.2, 3.4 released")
        assertUnchanged("location unknown")
        assertUnchanged("date 2024-02-29 note")
        assertUnchanged("order 12345")
        // B3 lookbehind: words ending in lat/lon/lng must not be truncated.
        assertUnchanged("flat: 0")
        assertUnchanged("colon: 5")
        assertUnchanged("semicolon: 12")
        assertUnchanged("slat: 3")
        assertUnchanged("plant: 2")
        assertUnchanged("plate: 2")
        assertUnchanged("relocation: 1,2")
        // geo: boundary + bare-pair prefix guard.
        assertUnchanged("paleogeo:1,2")
        assertUnchanged("v1.23, 4.56")
    }

    @Test fun r13_geoTrailingPunctuationPreserved() {
        assertEquals(
            "go ⟦REDACTED:GEO⟧, see docs",
            Redactor.redact("go geo:25.0478,121.5170, see docs").text,
        )
    }

    @Test fun r13_errorRedactsGeo() {
        val out = Redactor.redactError("failed to open geo:25.0478,121.5170?q=xx")
        assertTrue(out, !out.contains("25.0478"))
        assertTrue(out, !out.contains("121.5170"))
        assertTrue(out, out.contains("⟦REDACTED:GEO⟧"))
    }

    // ---- redact-cases.txt corpus (spec §10.3: 每行 RULEID || input || expected) ----

    @Test fun corpus_redactCases() {
        val stream = javaClass.getResourceAsStream("/redact-cases.txt")
            ?: throw AssertionError("redact-cases.txt missing from test resources")
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
        var count = 0
        for ((index, raw) in lines.withIndex()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val parts = raw.split("||", limit = 3)
            assertEquals("bad corpus line ${index + 1}: $raw", 3, parts.size)
            val ruleId = parts[0].trim()
            val input = parts[1].trim()
            val expected = parts[2].trim()
            assertEquals("corpus line ${index + 1} [$ruleId]", expected, Redactor.redact(input).text)
            count++
        }
        assertTrue("corpus must not be empty", count > 0)
    }

    // ---- hits ----

    @Test fun hits_countPerRule() {
        val result = Redactor.redact("mail a@b.co card 4539 1488 0343 6467 ok")
        assertEquals(1, result.hits["EMAIL"])
        assertEquals(1, result.hits["CARD_16"])
        assertEquals(0, result.hits["PHONE_GENERIC"])
        assertEquals(14, result.hits.size)
    }

    @Test fun hits_tableOrder() {
        val result = Redactor.redact("nothing here")
        assertEquals(
            listOf(
                "API_KEY_VALUE", "BEARER_TOKEN", "JSON_KEY_FIELD", "URL_CREDENTIAL",
                "URL_TOKEN_PARAM", "EMAIL", "PHONE_GENERIC", "PHONE_INTL", "ID_TW",
                "CARD_16", "IPV4_PRIVATE", "ANDROID_ID_LIKE", "GEO_COORD", "ANDROID_PATH",
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

    // ---- R14 ANDROID_PATH (S4) ----

    @Test fun r14_privateAndSharedPaths() {
        assertRedacted(
            "open /data/data/dev.librepocket.agent/files/linux/x failed",
            "/data/data/dev.librepocket.agent/files/linux/x",
        )
        assertRedacted("at /data/user/0/dev.librepocket.agent/y", "/data/user/0/dev.librepocket.agent/y")
        assertRedacted("save to /sdcard/Download/a.apk done", "/sdcard/Download/a.apk")
        assertRedacted("save to /storage/emulated/0/Download/a.apk done", "/storage/emulated/0/Download")
    }

    @Test fun r14_relativeAndWebPathsUnchanged() {
        assertUnchanged("run ./gradlew build")
        assertUnchanged("open https://example.com/a/b")
    }

    @Test fun r14_barePrefixUnchanged() {
        // \S+ 要求段內容：裸前綴不得誤殺。
        assertUnchanged("save to /sdcard")
        assertUnchanged("save to /storage/emulated/")
    }

    @Test fun r14_errorSubsetStripsPaths() {
        val out = Redactor.redactError("failed at /data/data/dev.librepocket.agent/files/x code 1")
        assertTrue(out, !out.contains("/data/data/"))
    }
}
