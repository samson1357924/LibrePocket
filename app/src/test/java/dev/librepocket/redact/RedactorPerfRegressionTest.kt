package dev.librepocket.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #86 Phase A regression pins (root fix: single-scan + literal
 * prechecks + possessive EMAIL).
 *
 * - Homogeneous 100k probes (`"x"`, `"a"`) are redaction-identical (no rule
 *   matches single-letter runs) with zero hits — the exact inputs that were
 *   O(n^2) through the old EMAIL scan.
 * - Single 100k `redact()` stays under 500ms (acceptance threshold); the
 *   200-row equivalent stays under 60s. Both would take orders of magnitude
 *   longer pre-fix (single 100k pass ~1e10 regex steps), so they fail without
 *   the fix and pass with it.
 * - Idempotence on every probe (re-redact is a fixed point).
 *
 * Budgets are deliberately loose (post-fix cost is ~15 O(n) indexOf scans +
 * one linear JSON pass, typically <50ms single / <10s for 200 rows on CI) so
 * loaded runners stay green while a quadratic regression still trips them.
 */
class RedactorPerfRegressionTest {

    private fun elapsedMs(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }

    @Test fun homogeneousLetterRuns_passThroughUnchangedWithZeroHits() {
        for (probe in listOf("x".repeat(100_000), "a".repeat(100_000))) {
            val result = Redactor.redact(probe)
            assertEquals(probe, result.text)
            assertTrue(
                "expected zero hits for homogeneous run, got ${result.hits}",
                result.hits.values.sum() == 0,
            )
            // Fixed point: re-redact changes nothing.
            assertEquals(result.text, Redactor.redact(result.text).text)
        }
    }

    @Test fun homogeneous100k_singleRedactUnder500ms() {
        val probes = listOf(
            "x".repeat(100_000),
            "a".repeat(100_000),
            "9".repeat(100_000),
            "0".repeat(100_000),
            ".".repeat(100_000),
        )
        for (probe in probes) {
            var out = ""
            val ms = elapsedMs { out = Redactor.redact(probe).text }
            assertTrue(
                "single 100k redact took ${"%.1f".format(ms)}ms (>500ms)",
                ms < 500,
            )
            assertEquals(out, Redactor.redact(out).text)
        }
    }

    @Test fun atBearingProbes_under500ms() {
        // Follow-up pin from Phase A review: `@`-bearing homogeneous runs must
        // not resurrect the quadratic (the `.tld`-dot precheck skips dotless
        // runs; real emails still redact in linear time).
        val dotless = "a@" + "a".repeat(50_000)
        var dotlessOut = ""
        val ms1 = elapsedMs { dotlessOut = Redactor.redact(dotless).text }
        assertTrue("dotless @-run took ${"%.1f".format(ms1)}ms (>500ms)", ms1 < 500)
        assertEquals(dotless, dotlessOut)

        val tailed = "user@example.com " + "x".repeat(20_000)
        var tailedOut = ""
        val ms2 = elapsedMs { tailedOut = Redactor.redact(tailed).text }
        assertTrue("tailed email took ${"%.1f".format(ms2)}ms (>500ms)", ms2 < 500)
        assertTrue(tailedOut.contains("⟦REDACTED:EMAIL⟧"))
        assertTrue(!tailedOut.contains("user@example.com"))
    }

    @Test fun emailLinearScan_matchesLegacyRegex() {
        // Differential pin: the linear @-anchored EMAIL scan must agree with
        // the legacy greedy regex replaceAll on every tricky shape (inputs use
        // letters only so no other rule fires).
        val legacy = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        val inputs = listOf(
            "mail user@example.com plz",
            "a first.last+tag@mail.example.co b",
            "x a_b-c@x.io y",
            "user@localhost",
            "not an email @ here",
            "a@b.co",
            "aa@bb.cc dd@ee.ff",
            "user@example.com " + "x".repeat(1000),
            "x".repeat(1000) + " user@example.com",
            "a@b.co c@d.ef",
            "test.@example.com",
            ".a@b.co",
            "a..b@c.de",
            "a@b.c",
            "a@b.co",
            "a@b.com.org",
            "@b.co",
            "a@",
            "a@b",
            "User@Example.COM",
            "a_b-c.d+e%f@gmail-test.co",
        )
        for (input in inputs) {
            val expected = legacy.replace(input, "⟦REDACTED:EMAIL⟧")
            assertEquals("mismatch for: $input", expected, Redactor.redact(input).text)
        }
    }

    @Test fun twoHundredHomogeneousRows_under60s() {
        val huge = "x".repeat(100_000)
        val ms = elapsedMs {
            repeat(200) { Redactor.redact(huge) }
        }
        assertTrue(
            "200 x 100k redacts took ${"%.1f".format(ms)}ms (>60s)",
            ms < 60_000,
        )
    }

    @Test fun emailStillRedactedAfterPossessiveFix() {
        // Guards the EMAIL `++` change: language must be unchanged.
        val out = Redactor.redact("mail user@example.com plz").text
        assertTrue(out.contains("⟦REDACTED:EMAIL⟧"))
        assertTrue(!out.contains("user@example.com"))
        val tagged = Redactor.redact("a first.last+tag@mail.example.co b").text
        assertTrue(!tagged.contains("first.last+tag@mail.example.co"))
    }
}
