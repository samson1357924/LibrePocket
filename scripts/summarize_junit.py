"""Summarize JUnit XML results into GitHub Actions Step Summary markdown.

Stdlib only. Reporting only — never a merge gate: :func:`main` always
exits 0 unless the parser itself crashes (syntax error, missing
interpreter), in which case the workflow's
``summary_parser_crashed_fallback`` writes the fallback notice instead.

Status contract (never misreport as pass):

- ``PASS`` — only when every file is readable, ``tests > 0``,
  ``failures == 0``, ``errors == 0``, and the per-case detail agrees
  with the suite counters.
- ``FAIL`` — complete readable results with at least one failure/error.
- ``PARTIAL`` — at least one XML file is unreadable; counts cover the
  readable subset only.
- ``INCOMPLETE`` — files exist but the totals cannot be trusted:
  ``tests == 0``, a suite counter is not an integer, or the suite
  counters contradict the per-case detail (e.g. suite ``errors > 0``
  with no failed ``<testcase>`` detail, or failed cases while the
  suite counters claim zero).
- ``NO_RESULTS`` — no ``TEST-*.xml`` files found at all.

Size contract: GitHub discards a step summary larger than 1 MiB, so the
rendered markdown is capped at 900 KiB of UTF-8 bytes. Per-line text is
additionally truncated to :data:`MAX_SUMMARY_LINE` characters. When the
failure/skip lists do not fit, entries are dropped from the tail and a
single ``omitted N failures / M skips; see XML artifact`` line records
the exact counts.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 scripts/summarize_junit.py
Test with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_summarize_junit.py
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field


MAX_SUMMARY_LINE = 300

# GitHub discards a step summary larger than 1 MiB; stay comfortably
# below it so the whole summary is never silently dropped.
SUMMARY_BUDGET_BYTES = 900 * 1024

_JUNIT_GLOB = "app/build/test-results/**/TEST-*.xml"


@dataclass
class JUnitSummary:
    """Parsed aggregate of a set of JUnit XML files."""

    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    failed: list = field(default_factory=list)
    skipped_cases: list = field(default_factory=list)
    unreadable: int = 0
    total_files: int = 0
    warnings: list = field(default_factory=list)
    status: str = "NO_RESULTS"


def truncate_line(text: str, limit: int = MAX_SUMMARY_LINE) -> str:
    """Truncate a single line to ``limit`` characters (ident/message)."""
    if len(text) > limit:
        return text[:limit] + "…"
    return text


def first_line(text: str | None) -> str:
    """Return the first non-blank line, truncated to one summary line."""
    for line in (text or "").splitlines():
        line = line.strip()
        if line:
            return truncate_line(line)
    return ""


def _safe_int(raw: object, *, attr: str, path: str, warnings: list) -> int:
    """Parse one suite counter without letting it poison the totals."""
    try:
        return int(raw or 0)
    except (TypeError, ValueError):
        warnings.append(
            "invalid integer attribute %s=%r in %s; treated as 0" % (attr, raw, path)
        )
        return 0


def parse_junit_xml(paths: list) -> JUnitSummary:
    """Parse JUnit XML files into aggregate counts and case detail.

    Corrupt files and bad counters are isolated: an unreadable file only
    increments ``unreadable`` (status ``PARTIAL`` or worse) and a bad
    counter only zeroes that counter (status ``INCOMPLETE`` with a
    warning). Neither ever clears results already collected.
    """
    summary = JUnitSummary(total_files=len(paths))
    if not paths:
        summary.status = "NO_RESULTS"
        return summary

    bad_attr = False
    suites_seen = 0
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except Exception as exc:  # noqa: BLE001 — reporting must never fail
            summary.unreadable += 1
            summary.warnings.append("unreadable %s: %s" % (path, exc))
            continue
        for suite in root.iter("testsuite"):
            suites_seen += 1
            for attr in ("tests", "failures", "errors", "skipped"):
                before = len(summary.warnings)
                value = _safe_int(
                    suite.get(attr, 0), attr=attr, path=path, warnings=summary.warnings
                )
                if len(summary.warnings) != before:
                    bad_attr = True
                if attr == "tests":
                    summary.tests += value
                elif attr == "failures":
                    summary.failures += value
                elif attr == "errors":
                    summary.errors += value
                else:
                    summary.skipped += value
            for case in suite.findall("testcase"):
                ident = "%s.%s" % (case.get("classname", "?"), case.get("name", "?"))
                bad = case.find("failure")
                if bad is None:
                    bad = case.find("error")
                if bad is not None:
                    detail = first_line(bad.get("message")) or first_line(bad.text)
                    summary.failed.append((ident, detail or "(no message)"))
                elif case.find("skipped") is not None:
                    summary.skipped_cases.append(ident)

    inconsistent = False
    suite_bad = summary.failures + summary.errors
    if suites_seen == 0:
        summary.warnings.append("no <testsuite> elements found; no tests recorded")
        inconsistent = True
    if suite_bad > 0 and not summary.failed:
        summary.warnings.append(
            "suite totals report %d failure(s)/%d error(s) but no failed "
            "<testcase> detail was found; counts cannot be trusted"
            % (summary.failures, summary.errors)
        )
        inconsistent = True
    if suite_bad == 0 and summary.failed:
        summary.warnings.append(
            "%d failed <testcase> detail(s) found while suite totals report "
            "zero failures/errors; counts cannot be trusted" % len(summary.failed)
        )
        inconsistent = True
    if summary.skipped > 0 and not summary.skipped_cases:
        summary.warnings.append(
            "suite totals report %d skipped but no skipped <testcase> detail "
            "was found" % summary.skipped
        )
    if summary.tests == 0:
        summary.warnings.append("files present but tests==0; no executed tests recorded")

    if summary.tests == 0 or bad_attr or inconsistent:
        summary.status = "INCOMPLETE"
    elif summary.unreadable > 0:
        summary.status = "PARTIAL"
    elif summary.failures > 0 or summary.errors > 0 or summary.failed:
        summary.status = "FAIL"
    else:
        summary.status = "PASS"
    return summary


def _status_line(summary: JUnitSummary) -> str:
    if summary.status == "PASS":
        return "> **Status: PASS — all executed tests passed.**"
    if summary.status == "FAIL":
        return (
            "> **Status: FAIL — %d failure(s) / %d error(s) observed "
            "(reporting only, not a gate).**" % (summary.failures, summary.errors)
        )
    if summary.status == "PARTIAL":
        return (
            "> **Status: PARTIAL — %d file(s) unreadable; counts cover the "
            "readable subset only (reporting only, not a gate).**"
            % summary.unreadable
        )
    if summary.status == "NO_RESULTS":
        return "> **Status: NO_RESULTS — no JUnit XML found.**"
    return (
        "> **Status: INCOMPLETE — totals are contradictory or empty; do not "
        "treat as a pass (reporting only, not a gate).**"
    )


def render_summary(summary: JUnitSummary, flavor: str = "?") -> str:
    """Render a parsed summary as step-summary markdown within budget."""
    lines = [
        "### Unit test summary — %s flavor" % flavor,
        "",
        _status_line(summary),
        "",
    ]
    if summary.status == "NO_RESULTS":
        lines.append(
            "No `app/build/test-results/**/TEST-*.xml` found — likely the "
            "setup/compile stage failed before tests ran. Summary skipped "
            "(reporting only, not a gate)."
        )
        return "\n".join(lines) + "\n"
    if summary.warnings:
        lines.append("**Warnings:**")
        lines.extend("- %s" % warning for warning in summary.warnings)
        lines.append("")
    lines.extend(
        [
            "| metric | count |",
            "| --- | ---: |",
            "| tests | %d |" % summary.tests,
            "| failures | %d |" % summary.failures,
            "| errors | %d |" % summary.errors,
            "| skipped | %d |" % summary.skipped,
            "",
            "Scanned %d JUnit XML file(s)%s."
            % (
                summary.total_files,
                ", %d unreadable" % summary.unreadable if summary.unreadable else "",
            ),
            "",
            "**Failed tests (%d):**" % len(summary.failed),
        ]
    )
    head = lines

    failed_lines = [
        "- `%s` — %s" % (truncate_line(ident), detail)
        for ident, detail in summary.failed
    ]
    skipped_lines = ["- `%s`" % truncate_line(ident) for ident in summary.skipped_cases]

    shown_failed = list(failed_lines)
    shown_skipped = list(skipped_lines)

    def _size(extra_failed: list, extra_skipped: list, omitted: str | None) -> int:
        parts = list(head) + extra_failed
        if omitted is None and not extra_failed:
            parts.append("_none — all executed tests passed._")
        parts += ["", "**Skipped tests (%d):**" % len(summary.skipped_cases)]
        parts += extra_skipped
        if not extra_skipped:
            parts.append("_none._")
        if omitted is not None:
            parts += ["", omitted]
        return len(("\n".join(parts) + "\n").encode("utf-8"))

    def _omitted_notice(shown_f: list, shown_s: list) -> str:
        return "omitted %d failures / %d skips; see XML artifact" % (
            len(failed_lines) - len(shown_f),
            len(skipped_lines) - len(shown_s),
        )

    omitted: str | None = None
    if _size(shown_failed, shown_skipped, None) > SUMMARY_BUDGET_BYTES:
        # Drop entries from the tail of the larger byte contributor until
        # the whole document (including the omitted notice) fits.
        failed_bytes = sum(len(line.encode("utf-8")) + 1 for line in shown_failed)
        skipped_bytes = sum(len(line.encode("utf-8")) + 1 for line in shown_skipped)
        while shown_failed or shown_skipped:
            candidate = _omitted_notice(shown_failed, shown_skipped)
            if _size(shown_failed, shown_skipped, candidate) <= SUMMARY_BUDGET_BYTES:
                omitted = candidate
                break
            if shown_skipped and skipped_bytes >= failed_bytes:
                skipped_bytes -= len(shown_skipped.pop().encode("utf-8")) + 1
            elif shown_failed:
                failed_bytes -= len(shown_failed.pop().encode("utf-8")) + 1
            else:
                skipped_bytes -= len(shown_skipped.pop().encode("utf-8")) + 1
        else:
            omitted = _omitted_notice([], [])

    parts = list(head) + shown_failed
    if omitted is None and not shown_failed:
        if summary.status == "PASS":
            parts.append("_none — all executed tests passed._")
        else:
            parts.append("_none listed — see Status above (not a clean pass)._")
    parts += ["", "**Skipped tests (%d):**" % len(summary.skipped_cases)]
    parts += shown_skipped
    if not shown_skipped:
        parts.append("_none._")
    if omitted is not None:
        parts += ["", omitted]
    text = "\n".join(parts) + "\n"
    if len(text.encode("utf-8")) > SUMMARY_BUDGET_BYTES:  # defensive; never mislead
        raise ValueError(
            "rendered summary exceeds %d UTF-8 bytes" % SUMMARY_BUDGET_BYTES
        )
    return text


def summarize(paths: list, flavor: str = "?") -> str:
    """Parse ``paths`` and render the markdown (convenience wrapper)."""
    return render_summary(parse_junit_xml(paths), flavor)


def main() -> int:
    paths = sorted(glob.glob(_JUNIT_GLOB, recursive=True))
    flavor = os.environ.get("FLAVOR", "?")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY", "")
    text = summarize(paths, flavor)
    # Echo to the job log and mirror into the step summary. A summary
    # write failure stays in the log; it must not fail this step.
    sys.stdout.write(text)
    if summary_path:
        try:
            with open(summary_path, "a", encoding="utf-8") as handle:
                handle.write(text)
        except OSError as exc:
            print("warning: cannot append GITHUB_STEP_SUMMARY: %s" % exc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
