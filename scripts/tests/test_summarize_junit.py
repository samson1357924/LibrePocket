"""Stdlib regression harness for the JUnit step-summary parser.

Covers the reporting-only parser in scripts/summarize_junit.py: corrupt
XML must never read as a pass, suite/case contradictions must surface
as INCOMPLETE with a warning, and multi-megabyte multi-byte output must
fit the 900 KiB UTF-8 budget with an exact omitted notice.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_summarize_junit.py
"""

from __future__ import annotations

import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from scripts import summarize_junit
from scripts.summarize_junit import (
    MAX_SUMMARY_LINE,
    SUMMARY_BUDGET_BYTES,
    parse_junit_xml,
    render_summary,
)


def _write(path: Path, content: str) -> str:
    path.write_text(content, encoding="utf-8")
    return str(path)


def _passing_suite(*, tests: int = 2) -> str:
    cases = "".join(
        '<testcase classname="com.example.Foo" name="test%d"/>' % index
        for index in range(tests)
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<testsuite name="com.example.Foo" tests="%d" failures="0" errors="0" skipped="0">'
        "%s</testsuite>" % (tests, cases)
    )


class SummarizeJunitHarness(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="librepocket-junit-summary-")
        self.addCleanup(self.temp.cleanup)
        self.tmp = Path(self.temp.name)

    def test_malformed_xml_is_partial_never_pass(self) -> None:
        good = _write(self.tmp / "TEST-good.xml", _passing_suite())
        bad = self.tmp / "TEST-broken.xml"
        bad.write_bytes(b"<testsuite><testcase><failure message=\"boom\">truncated")
        summary = parse_junit_xml([bad.as_posix(), good])
        self.assertEqual(summary.unreadable, 1)
        self.assertEqual(summary.tests, 2)
        self.assertNotEqual(summary.status, "PASS")
        self.assertEqual(summary.status, "PARTIAL")
        text = render_summary(summary, "play")
        self.assertIn("PARTIAL", text)
        self.assertNotIn("all executed tests passed", text)
        self.assertIn("Scanned 2 JUnit XML file(s), 1 unreadable.", text)

    def test_no_xml_is_no_results(self) -> None:
        summary = parse_junit_xml([])
        self.assertEqual(summary.status, "NO_RESULTS")
        text = render_summary(summary, "foss")
        self.assertIn("NO_RESULTS", text)
        self.assertNotIn("all executed tests passed", text)

    def test_suite_error_without_case_detail_is_incomplete(self) -> None:
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="com.example.Bar" tests="5" failures="0" errors="2" skipped="0">'
            '<testcase classname="com.example.Bar" name="test0"/>'
            '<testcase classname="com.example.Bar" name="test1"/>'
            "</testsuite>"
        )
        path = _write(self.tmp / "TEST-suite-error.xml", xml)
        summary = parse_junit_xml([path])
        self.assertEqual(summary.errors, 2)
        self.assertEqual(summary.failed, [])
        self.assertNotEqual(summary.status, "PASS")
        self.assertEqual(summary.status, "INCOMPLETE")
        self.assertTrue(
            any("no failed" in warning for warning in summary.warnings),
            "contradiction warning missing: %r" % (summary.warnings,),
        )
        text = render_summary(summary, "github")
        self.assertIn("INCOMPLETE", text)
        self.assertIn("Warnings:", text)
        self.assertNotIn("all executed tests passed", text)

    def test_case_failures_denied_by_suite_counters_are_incomplete(self) -> None:
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="com.example.Baz" tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="com.example.Baz" name="fails">'
            '<failure message="expected true">assertion failed</failure>'
            "</testcase></testsuite>"
        )
        path = _write(self.tmp / "TEST-denied.xml", xml)
        summary = parse_junit_xml([path])
        self.assertEqual(len(summary.failed), 1)
        self.assertEqual(summary.status, "INCOMPLETE")

    def test_bad_suite_counter_is_isolated_not_cleared(self) -> None:
        bad_suite = (
            '<testsuite name="bad" tests="oops" failures="NaN" errors="0" skipped="0">'
            '<testcase classname="bad.K" name="t"/></testsuite>'
        )
        good_suite = (
            '<testsuite name="good" tests="1" failures="1" errors="0" skipped="0">'
            '<testcase classname="good.K" name="fails">'
            '<failure message="kaboom">stack</failure>'
            "</testcase></testsuite>"
        )
        path = _write(
            self.tmp / "TEST-mixed.xml",
            '<?xml version="1.0"?>' "<testsuites>%s%s</testsuites>" % (bad_suite, good_suite),
        )
        summary = parse_junit_xml([path])
        # The good suite's results survive the neighbouring bad counters.
        self.assertEqual(summary.tests, 1)
        self.assertEqual(summary.failures, 1)
        self.assertEqual(len(summary.failed), 1)
        self.assertEqual(summary.status, "INCOMPLETE")
        self.assertTrue(
            any("invalid integer attribute" in warning for warning in summary.warnings)
        )
        # Raw XML validity itself is intact.
        ET.parse(path)

    def test_clean_results_pass(self) -> None:
        path = _write(self.tmp / "TEST-clean.xml", _passing_suite(tests=3))
        summary = parse_junit_xml([path])
        self.assertEqual(summary.status, "PASS")
        text = render_summary(summary, "play")
        self.assertIn("Status: PASS", text)
        self.assertIn("_none — all executed tests passed._", text)
        self.assertIn("| tests | 3 |", text)

    def test_oversized_multibyte_output_fits_utf8_budget(self) -> None:
        # ~2500 failures with a 300-char multi-byte message each: raw XML
        # well over 1 MiB, so the budget path must engage.
        message = "錯誤訊息斷言失敗" * 50  # 8 CJK chars * 50 = 400 chars
        self.assertGreater(len(message.encode("utf-8")), 1000)
        cases = "".join(
            '<testcase classname="com.example.Big" name="test%d">'
            '<failure message="%s">%s</failure></testcase>' % (index, message, message)
            for index in range(2500)
        )
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="com.example.Big" tests="2500" failures="2500" '
            'errors="0" skipped="0">%s</testsuite>' % cases
        )
        path = self.tmp / "TEST-big.xml"
        _write(path, xml)
        self.assertGreater(len(path.read_bytes()), 1024 * 1024)
        summary = parse_junit_xml([path.as_posix()])
        self.assertEqual(summary.status, "FAIL")
        text = render_summary(summary, "play")
        size = len(text.encode("utf-8"))
        self.assertLessEqual(size, SUMMARY_BUDGET_BYTES, "rendered bytes: %d" % size)
        self.assertIn("omitted ", text)
        self.assertIn("see XML artifact", text)
        omitted_failures = 2500 - text.count("com.example.Big.test")
        self.assertGreater(omitted_failures, 0)
        self.assertIn(
            "omitted %d failures / 0 skips; see XML artifact" % omitted_failures, text
        )

    def test_long_skipped_ident_is_truncated(self) -> None:
        long_name = "t" + "est_" * 200
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="com.example.Skip" tests="1" failures="0" errors="0" skipped="1">'
            '<testcase classname="com.example.Skip" name="%s">'
            "<skipped/></testcase></testsuite>" % long_name
        )
        path = _write(self.tmp / "TEST-skip.xml", xml)
        summary = parse_junit_xml([path])
        text = render_summary(summary, "play")
        self.assertEqual(summary.status, "PASS")
        for line in text.splitlines():
            if line.startswith("- `com.example.Skip."):
                ident = line[3:].split("`")[0]
                self.assertLessEqual(len(ident), MAX_SUMMARY_LINE + 1)
                self.assertIn("…", line)
                break
        else:
            self.fail("truncated skipped ident line missing")


if __name__ == "__main__":
    unittest.main(verbosity=2)
