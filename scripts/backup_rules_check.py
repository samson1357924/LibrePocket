"""Structured XML validation for backup / data-extraction rules.

The backup policy gate (``scripts/play_policy_check.sh``) must prove that the
five key-file excludes are present in the correct XML elements *and* in the
correct resource file. A plain ``grep domain=... path=...`` matches raw text
anywhere in any file, so it cannot tell whether an ``<exclude>`` lives under
``<cloud-backup>`` or ``<device-transfer>``, nor whether it is inside an XML
comment. That lets a future edit silently drop a device-transfer exclude while
the gate still reports PASS.

This module parses the XML with the stdlib parser and enforces:

- the exact element structure, ignoring comments by construction;
- the file↔root binding, enforced at EVERY call surface (REQUIRED argument):
  Android expects ``backup_rules.xml`` (legacy) to have a
  ``<full-backup-content>`` root and ``data_extraction_rules.xml`` a
  ``<data-extraction-rules>`` root (AOSP ``FullBackup.verifyTopLevelTag``
  throws for the wrong tag and backup is silently disabled). The shell gate
  passes it via ``--expect-root``; hint-free validation is refused (the
  swapped-file false-PASS repro stays closed outside the shell too);
- Android's runtime strictness, mirroring ``FullBackup.validateInnerTagContents``:
  unknown child tags (at ANY depth - the runtime parser is depth-agnostic
  inside a section) and over-attribute ``<exclude>``/``<include>`` elements
  throw at runtime (backup silently disabled), so the gate rejects them too.
  ``tools:``-namespace attributes never reach the runtime (aapt strips them)
  and are excluded from the attribute counts.

It is invoked by the shell gate and is directly unit-tested against negative
fixtures. Run with:

    python3 scripts/backup_rules_check.py --expect-root <tag> <file> [...]
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

# BackupPolicy.KEY_FILES_EXCLUDED: the five key/vault artifacts that must never
# be backed up. Each entry is (domain, path).
REQUIRED_EXCLUDES: tuple[tuple[str, str], ...] = (
    ("sharedpref", "librepocket_keys.xml"),
    ("database", "librepocket_vault"),
    ("database", "librepocket_vault-wal"),
    ("database", "librepocket_vault-shm"),
    ("file", "librepocket_keysets"),
)

# File↔root binding (Android's own expectation; a swapped file is silently
# ignored by the runtime parser - see module docstring).
EXPECTED_ROOT_BACKUP_RULES = "full-backup-content"
EXPECTED_ROOT_DATA_EXTRACTION = "data-extraction-rules"

# Mirror FullBackup.validateInnerTagContents: only <include>/<exclude> are
# recognized inside the rule containers, <exclude> takes at most 2 attributes
# (domain, path) and <include> at most 3 (domain, path, flags) - a stray extra
# attribute makes the Android parser throw (backup disabled).
#
# `tools:`-namespace attributes are excluded from the count (aapt strips them
# at compile time, so the runtime never sees them); everything else counts as-is.
_ALLOWED_RULE_TAGS = frozenset({"include", "exclude"})
_MAX_EXCLUDE_ATTRS = 2
_MAX_INCLUDE_ATTRS = 3
# Nesting guard: include/exclude never nest in Android's schema; the walk-ALL
# recursion is bounded so a pathological suffix-of-70-nested-includes fixture
# surfaces as a clean FAIL: line instead of an uncaught RecursionError (which
# still exits non-zero, but without an actionable message).
_MAX_RULE_NEST_DEPTH = 64
_TOOLS_ATTR_NAMESPACES = (
    "tools:",  # non-namespaced literal (defensive)
    "{http://schemas.android.com/tools}",  # ElementTree-qualified tools:*
)


def _excludes(node: ET.Element) -> set[tuple[str, str]]:
    return {(exclude.get("domain"), exclude.get("path")) for exclude in node.findall("exclude")}


def _where(branch_label: str) -> str:
    return branch_label + " " if branch_label else ""


def _validate_rule_children(
    path: str, node: ET.Element, branch_label: str = "", depth: int = 0
) -> list[str]:
    """Reject tags/attribute shapes Android's runtime parser would throw on.

    AOSP ``FullBackup.parseRules`` visits EVERY START_TAG at any depth inside a
    rule section (the walk is depth-agnostic up to the section's END_TAG), and
    ``validateInnerTagContents`` throws on any tag other than include/exclude.
    So this walk recurses into include/exclude sub-trees too: a legal rule
    element wrapping an unknown child would throw at runtime and must FAIL
    here instead of passing the gate.
    """
    errors: list[str] = []
    for child in node:
        tag = child.tag
        if tag not in _ALLOWED_RULE_TAGS:
            errors.append(
                f"{path}: {_where(branch_label)}unknown element <{tag}> "
                "(Android rejects unrecognized tags at parse time)"
            )
            continue
        limit = _MAX_EXCLUDE_ATTRS if tag == "exclude" else _MAX_INCLUDE_ATTRS
        # tools:* attrs are stripped by aapt before the Android runtime sees
        # the compiled resource - counting them would reject runtime-valid
        # files (false-FAIL). All other attributes count as-is.
        attrs = {
            key
            for key in child.keys()
            if not any(key.startswith(ns) for ns in _TOOLS_ATTR_NAMESPACES)
        }
        if len(attrs) > limit:
            errors.append(
                f"{path}: {_where(branch_label)}<{tag}> has {len(attrs)} attributes; "
                f"Android allows at most {limit}"
            )
        # Any-depth nesting: recurse even into legal include/exclude children,
        # bounded by _MAX_RULE_NEST_DEPTH (well beyond any real rules file -
        # deeper shapes fall out as a clean depth FAIL, not a RecursionError).
        if depth >= _MAX_RULE_NEST_DEPTH:
            errors.append(
                f"{path}: {_where(branch_label)}<{tag}> exceeds the maximum "
                f"rule nesting depth {_MAX_RULE_NEST_DEPTH}"
            )
            continue
        errors.extend(_validate_rule_children(path, child, branch_label, depth + 1))
    return errors


def _missing_exclude_errors(
    path: str, branch_label: str, present: set[tuple[str, str]]
) -> list[str]:
    return [
        f"{path}: {_where(branch_label)}missing exclude domain={domain} path={rule_path}"
        for domain, rule_path in REQUIRED_EXCLUDES
        if (domain, rule_path) not in present
    ]


def validate(path: str, expected_root: str) -> list[str]:
    """Return a list of human-readable violations (empty when valid).

    ``expected_root`` is REQUIRED: the file↔root binding is the whole point of
    this validator (Android's ``verifyTopLevelTag`` throws for a wrong root and
    backup/restore is silently disabled). A hint-free call cannot prove which
    schema the caller intended, so every invocation surface must pass the
    binding explicitly - the swapped-file false-PASS (reviewer repro) stays
    closed even outside the shell gate.
    """
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return [f"{path}: invalid XML: {exc}"]
    except OSError as exc:
        return [f"{path}: unreadable: {exc}"]

    if root.tag != expected_root:
        return [f"{path}: expected root <{expected_root}>, found <{root.tag}>"]

    errors: list[str] = []
    if root.tag == "full-backup-content":
        # Android's validator rejects unknown tags / over-attribute rules here;
        # a file that would fail at runtime must fail the gate, not PASS.
        errors.extend(_validate_rule_children(path, root))
        present = _excludes(root)
        errors.extend(_missing_exclude_errors(path, "", present))
    elif root.tag == "data-extraction-rules":
        # Android's runtime parser visits EVERY matching section occurrence
        # (duplicates included), so the gate validates all of them too;
        # required excludes must be present in every occurrence (stricter
        # than the runtime union, fail-closed for split-rule edits).
        seen: set[str] = set()
        for child in root:
            if child.tag not in ("cloud-backup", "device-transfer"):
                # Runtime silently skips unknown root-level children; mirror
                # that and let the two required branches carry the policy.
                continue
            seen.add(child.tag)
            errors.extend(_validate_rule_children(path, child, f"<{child.tag}>"))
            present = _excludes(child)
            errors.extend(_missing_exclude_errors(path, f"<{child.tag}>", present))
        for branch in ("cloud-backup", "device-transfer"):
            if branch not in seen:
                errors.append(f"{path}: missing <{branch}>")
    else:
        errors.append(f"{path}: unexpected root element <{root.tag}>")
    return errors


def main(argv: list[str]) -> int:
    args = argv[1:]
    expected_root: str | None = None
    paths: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--expect-root":
            if i + 1 >= len(args):
                print("FAIL: --expect-root requires a value", file=sys.stderr)
                return 2
            expected_root = args[i + 1]
            i += 2
        elif args[i].startswith("--"):
            print(f"FAIL: unknown option {args[i]}", file=sys.stderr)
            return 2
        else:
            paths.append(args[i])
            i += 1
    if not paths or expected_root is None:
        # Fail closed: an empty invocation OR a missing expected-root hint
        # must never PASS (the hint-free swapped-file false-PASS was the
        # reviewer's original repro; the gate and every other caller must
        # pass the file↔root binding explicitly).
        print(
            "usage: backup_rules_check.py --expect-root <tag> <rules.xml> [...] "
            "(--expect-root full-backup-content for full-backup-content files; "
            "--expect-root data-extraction-rules for data-extraction-rules files)",
            file=sys.stderr,
        )
        return 2
    errors: list[str] = []
    for path in paths:
        errors.extend(validate(path, expected_root))
    if errors:
        for line in errors:
            print(f"FAIL: {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
