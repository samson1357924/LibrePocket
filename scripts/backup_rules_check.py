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
- the file↔root binding: Android expects ``backup_rules.xml`` (legacy) to have
  a ``<full-backup-content>`` root and ``data_extraction_rules.xml`` a
  ``<data-extraction-rules>`` root (AOSP ``FullBackup.verifyTopLevelTag``
  throws for the wrong tag and backup is silently disabled). Callers pass the
  expected root via ``validate(path, expected_root)`` so a swapped or
  misattributed file cannot pass;
- Android's runtime strictness, mirroring ``FullBackup.validateInnerTagContents``:
  unknown child tags and over-attribute ``<exclude>``/``<include>`` elements
  throw at runtime (backup silently disabled), so the gate rejects them too.

It is invoked by the shell gate and is directly unit-tested against negative
fixtures. Run with:

    python3 scripts/backup_rules_check.py [--expect-root <tag>] <file> [...]
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
_ALLOWED_RULE_TAGS = frozenset({"include", "exclude"})
_MAX_EXCLUDE_ATTRS = 2
_MAX_INCLUDE_ATTRS = 3


def _excludes(node: ET.Element) -> set[tuple[str, str]]:
    return {(exclude.get("domain"), exclude.get("path")) for exclude in node.findall("exclude")}


def _where(branch_label: str) -> str:
    return branch_label + " " if branch_label else ""


def _validate_rule_children(
    path: str, node: ET.Element, branch_label: str = ""
) -> list[str]:
    """Reject tags/attribute shapes Android's runtime parser would throw on."""
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
        attrs = set(child.keys())
        if len(attrs) > limit:
            errors.append(
                f"{path}: {_where(branch_label)}<{tag}> has {len(attrs)} attributes; "
                f"Android allows at most {limit}"
            )
    return errors


def _missing_exclude_errors(
    path: str, branch_label: str, present: set[tuple[str, str]]
) -> list[str]:
    return [
        f"{path}: {branch_label} missing exclude domain={domain} path={rule_path}"
        for domain, rule_path in REQUIRED_EXCLUDES
        if (domain, rule_path) not in present
    ]


def validate(path: str, expected_root: str | None = None) -> list[str]:
    """Return a list of human-readable violations (empty when valid).

    When ``expected_root`` is given (the gate always passes it), a file whose
    root element differs is rejected outright: the wrong schema would be
    silently ignored or throw at Android's runtime parse.
    """
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return [f"{path}: invalid XML: {exc}"]
    except OSError as exc:
        return [f"{path}: unreadable: {exc}"]

    if expected_root is not None and root.tag != expected_root:
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
    if not paths:
        # Fail closed: an accidental empty invocation must not PASS an empty
        # harness run. Usage names the two expected roots.
        print(
            "usage: backup_rules_check.py [--expect-root <tag>] <rules.xml> [...] "
            "(--expect-root full-backup-content for backup_rules.xml; "
            "--expect-root data-extraction-rules for data_extraction_rules.xml)",
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
