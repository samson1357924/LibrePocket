"""Summarize JUnit XML results into GitHub Actions Step Summary markdown.

Stdlib only. Reporting only — never a merge gate: :func:`main` always
exits 0 unless the parser itself crashes (syntax error, missing
interpreter), in which case the workflow's
``summary_parser_crashed_fallback`` writes the fallback notice instead.

Status contract (never misreport as pass):

- ``PASS`` — only when every file is readable, ``tests > 0``,
  ``failures == 0``, ``errors == 0``, and the per-case detail agrees
  with the suite counters (per-suite equality: ``tests`` vs direct
  ``<testcase>`` count, ``failures+errors`` vs failed detail count,
  ``skipped`` vs skipped detail count, in either direction).
- ``FAIL`` — complete readable results with at least one failure/error.
- ``PARTIAL`` — at least one XML file is unreadable; counts cover the
  readable subset only.
- ``INCOMPLETE`` — files exist but the totals cannot be trusted:
  ``tests == 0``, a suite counter is not an integer, any nested
  ``<testsuite>`` (outer counters alone cannot prove completeness, so
  any nesting forces ``INCOMPLETE``), or any suite
  counter contradicts its per-case detail (including ``skipped``
  mismatches). Only outermost suites are counted: a ``<testsuite>``
  root counts itself, a ``<testsuites>`` root counts its direct
  ``<testsuite>`` children; nested ``<testsuite>`` elements are ignored
  with a warning.
- ``NO_RESULTS`` — no ``TEST-*.xml`` files found at all.

Size contract: GitHub discards a step summary larger than 1 MiB, so the
rendered markdown is capped at 900 KiB of UTF-8 bytes. Per-line text is
additionally truncated to :data:`MAX_SUMMARY_LINE` characters (failure
idents/messages, skipped idents, every warning, and the flavor title).
When the failure/skip lists do not fit, entries are dropped from the
tail and a single ``omitted N failures / M skips; see XML artifact``
line records the exact counts; when the warning list itself does not
fit, tail warnings are dropped with an
``omitted K warning(s); see XML artifact`` count. Rendering never raises
for oversized input.

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
            truncate_line(
                "invalid integer attribute %s=%r in %s; treated as 0"
                % (attr, raw, path)
            )
        )
        return 0


def parse_junit_xml(paths: list) -> JUnitSummary:
    """Parse JUnit XML files into aggregate counts and case detail.

    Corrupt files and bad counters are isolated: an unreadable file only
    increments ``unreadable`` (status ``PARTIAL`` or worse) and a bad
    counter only zeroes that counter (status ``INCOMPLETE`` with a
    warning). Neither ever clears results already collected.

    Only outermost suites are counted (a ``<testsuite>`` root counts
    itself; a ``<testsuites>`` root counts its direct ``<testsuite>``
    children) so nested suites are never double-counted. Any nested
    suite forces ``INCOMPLETE`` (outer counters alone cannot prove
    completeness). Every outer
    suite is checked for exact equality between its counters and its
    direct ``<testcase>`` detail; any mismatch is ``INCOMPLETE``.
    """
    summary = JUnitSummary(total_files=len(paths))
    if not paths:
        summary.status = "NO_RESULTS"
        return summary

    def _warn(message: str) -> None:
        summary.warnings.append(truncate_line(message))

    bad_attr = False
    suites_seen = 0
    inconsistent = False
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except Exception as exc:  # noqa: BLE001 — reporting must never fail
            summary.unreadable += 1
            _warn("unreadable %s: %s" % (path, exc))
            continue
        if root.tag == "testsuite":
            outer_suites = [root]
        elif root.tag == "testsuites":
            outer_suites = list(root.findall("testsuite"))
        else:
            outer_suites = list(root.findall("testsuite"))
        for suite in outer_suites:
            suites_seen += 1
            suite_name = suite.get("name", "?") or "?"
            if suite.findall(".//testsuite"):
                _warn(
                    "nested <testsuite> elements under suite %r in %s ignored; "
                    "outermost-only counting applied" % (suite_name, path)
                )
                inconsistent = True
            exp_tests = exp_failures = exp_errors = exp_skipped = 0
            for attr in ("tests", "failures", "errors", "skipped"):
                before = len(summary.warnings)
                value = _safe_int(
                    suite.get(attr, 0), attr=attr, path=path, warnings=summary.warnings
                )
                if len(summary.warnings) != before:
                    bad_attr = True
                    # _safe_int already truncates its own warning.
                    summary.warnings[before] = truncate_line(summary.warnings[before])
                if attr == "tests":
                    exp_tests = value
                    summary.tests += value
                elif attr == "failures":
                    exp_failures = value
                    summary.failures += value
                elif attr == "errors":
                    exp_errors = value
                    summary.errors += value
                else:
                    exp_skipped = value
                    summary.skipped += value
            actual_total = 0
            actual_failed = 0
            actual_skipped = 0
            for case in suite.findall("testcase"):
                actual_total += 1
                ident = "%s.%s" % (case.get("classname", "?"), case.get("name", "?"))
                bad = case.find("failure")
                if bad is None:
                    bad = case.find("error")
                if bad is not None:
                    actual_failed += 1
                    detail = first_line(bad.get("message")) or first_line(bad.text)
                    summary.failed.append((ident, detail or "(no message)"))
                elif case.find("skipped") is not None:
                    actual_skipped += 1
                    summary.skipped_cases.append(ident)
            if exp_tests != actual_total:
                _warn(
                    "inconsistent %s suite %r: tests=%d but found %d "
                    "<testcase> detail(s); counts cannot be trusted"
                    % (path, suite_name, exp_tests, actual_total)
                )
                inconsistent = True
            if exp_failures + exp_errors != actual_failed:
                _warn(
                    "inconsistent %s suite %r: failures+errors=%d but found %d "
                    "failed <testcase> detail(s); counts cannot be trusted"
                    % (path, suite_name, exp_failures + exp_errors, actual_failed)
                )
                inconsistent = True
            if exp_skipped != actual_skipped:
                _warn(
                    "inconsistent %s suite %r: skipped=%d but found %d skipped "
                    "<testcase> detail(s); counts cannot be trusted"
                    % (path, suite_name, exp_skipped, actual_skipped)
                )
                inconsistent = True

    # The aggregate guards below are kept so the historical warning text
    # (and the strictness it provides) never regresses.
    suite_bad = summary.failures + summary.errors
    if suites_seen == 0:
        _warn("no <testsuite> elements found; no tests recorded")
        inconsistent = True
    if suite_bad > 0 and not summary.failed:
        _warn(
            "suite totals report %d failure(s)/%d error(s) but no failed "
            "<testcase> detail was found; counts cannot be trusted"
            % (summary.failures, summary.errors)
        )
        inconsistent = True
    if suite_bad == 0 and summary.failed:
        _warn(
            "%d failed <testcase> detail(s) found while suite totals report "
            "zero failures/errors; counts cannot be trusted" % len(summary.failed)
        )
        inconsistent = True
    if summary.skipped > 0 and not summary.skipped_cases:
        _warn(
            "suite totals report %d skipped but no skipped <testcase> detail "
            "was found" % summary.skipped
        )
        inconsistent = True
    if summary.tests == 0:
        _warn("files present but tests==0; no executed tests recorded")

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
    flavor_safe = truncate_line(str(flavor))
    warnings_safe = [truncate_line(warning) for warning in summary.warnings]
    warn_items_full = ["- %s" % warning for warning in warnings_safe]
    prefix = [
        "### Unit test summary — %s flavor" % flavor_safe,
        "",
        _status_line(summary),
        "",
    ]
    if summary.status == "NO_RESULTS":
        lines = list(prefix)
        lines.append(
            "No `app/build/test-results/**/TEST-*.xml` found — likely the "
            "setup/compile stage failed before tests ran. Summary skipped "
            "(reporting only, not a gate)."
        )
        return "\n".join(lines) + "\n"
    metrics = [
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

    failed_lines = [
        "- `%s` — %s" % (truncate_line(ident), detail)
        for ident, detail in summary.failed
    ]
    skipped_lines = ["- `%s`" % truncate_line(ident) for ident in summary.skipped_cases]

    shown_failed = list(failed_lines)
    shown_skipped = list(skipped_lines)
    shown_warnings = list(warn_items_full)
    omitted_warnings = 0

    def _assemble(
        sel_warnings: list,
        sel_failed: list,
        sel_skipped: list,
        omitted_fs: str | None,
        omitted_w: int,
    ) -> list:
        parts = list(prefix)
        if warn_items_full:
            parts.append("**Warnings:**")
            parts.extend(sel_warnings)
            if omitted_w:
                parts.append(
                    "- omitted %d warning(s); see XML artifact" % omitted_w
                )
            parts.append("")
        parts.extend(metrics)
        parts.extend(sel_failed)
        if omitted_fs is None and not sel_failed:
            if summary.status == "PASS":
                parts.append("_none — all executed tests passed._")
            else:
                parts.append("_none listed — see Status above (not a clean pass)._")
        parts += ["", "**Skipped tests (%d):**" % len(summary.skipped_cases)]
        parts.extend(sel_skipped)
        if not sel_skipped:
            parts.append("_none._")
        if omitted_fs is not None:
            parts += ["", omitted_fs]
        return parts

    def _size(
        sel_warnings: list,
        sel_failed: list,
        sel_skipped: list,
        omitted_fs: str | None,
        omitted_w: int,
    ) -> int:
        return len(
            ("\n".join(_assemble(sel_warnings, sel_failed, sel_skipped, omitted_fs, omitted_w)) + "\n").encode(
                "utf-8"
            )
        )

    def _omitted_notice(shown_f: list, shown_s: list) -> str:
        return "omitted %d failures / %d skips; see XML artifact" % (
            len(failed_lines) - len(shown_f),
            len(skipped_lines) - len(shown_s),
        )

    omitted: str | None = None
    if _size(shown_warnings, shown_failed, shown_skipped, None, 0) > SUMMARY_BUDGET_BYTES:
        # Drop entries from the tail of the larger byte contributor until
        # the whole document (including the omitted notice) fits.
        failed_bytes = sum(len(line.encode("utf-8")) + 1 for line in shown_failed)
        skipped_bytes = sum(len(line.encode("utf-8")) + 1 for line in shown_skipped)
        while shown_failed or shown_skipped:
            candidate = _omitted_notice(shown_failed, shown_skipped)
            if (
                _size(shown_warnings, shown_failed, shown_skipped, candidate, 0)
                <= SUMMARY_BUDGET_BYTES
            ):
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

    if _size(shown_warnings, shown_failed, shown_skipped, omitted, omitted_warnings) > SUMMARY_BUDGET_BYTES:
        # The head itself (warnings) does not fit: drop tail warnings and
        # count them, keeping the failure/skip omission already decided.
        while shown_warnings:
            shown_warnings.pop()
            omitted_warnings += 1
            if (
                _size(shown_warnings, shown_failed, shown_skipped, omitted, omitted_warnings)
                <= SUMMARY_BUDGET_BYTES
            ):
                break
        else:
            omitted_warnings = len(warn_items_full)

    parts = _assemble(shown_warnings, shown_failed, shown_skipped, omitted, omitted_warnings)
    text = "\n".join(parts) + "\n"
    if len(text.encode("utf-8")) > SUMMARY_BUDGET_BYTES:
        # Defensive byte-cap (reporting only): never raise, never mislead
        # with an over-budget summary the platform would drop.
        text = text.encode("utf-8")[:SUMMARY_BUDGET_BYTES].decode("utf-8", errors="ignore")
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
