"""Structured XML validation for backup / data-extraction rules.

The backup policy gate (``scripts/play_policy_check.sh``) must prove that the
five key-file excludes are present in the correct XML elements. A plain
``grep domain=... path=...`` matches raw text anywhere in the file, so it
cannot tell whether an ``<exclude>`` lives under ``<cloud-backup>`` or
``<device-transfer>``, nor whether it is inside an XML comment. That lets a
future edit silently drop a device-transfer exclude while the gate still
reports PASS.

This module parses the XML with the stdlib parser and enforces the exact
element structure, ignoring comments by construction. It is invoked by the
shell gate and is directly unit-tested against negative fixtures.

Run with: ``python3 scripts/backup_rules_check.py <file> [...]``
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


def _excludes(node: ET.Element) -> set[tuple[str, str]]:
    return {(exclude.get("domain"), exclude.get("path")) for exclude in node.findall("exclude")}


def validate(path: str) -> list[str]:
    """Return a list of human-readable violations (empty when valid)."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return [f"{path}: invalid XML: {exc}"]
    except OSError as exc:
        return [f"{path}: unreadable: {exc}"]

    errors: list[str] = []
    if root.tag == "full-backup-content":
        present = _excludes(root)
        for domain, rule_path in REQUIRED_EXCLUDES:
            if (domain, rule_path) not in present:
                errors.append(f"{path}: missing exclude domain={domain} path={rule_path}")
    elif root.tag == "data-extraction-rules":
        for branch in ("cloud-backup", "device-transfer"):
            node = root.find(branch)
            if node is None:
                errors.append(f"{path}: missing <{branch}>")
                continue
            present = _excludes(node)
            for domain, rule_path in REQUIRED_EXCLUDES:
                if (domain, rule_path) not in present:
                    errors.append(
                        f"{path}: <{branch}> missing exclude domain={domain} path={rule_path}"
                    )
    else:
        errors.append(f"{path}: unexpected root element <{root.tag}>")
    return errors


def main(argv: list[str]) -> int:
    errors: list[str] = []
    for path in argv[1:]:
        errors.extend(validate(path))
    if errors:
        for line in errors:
            print(f"FAIL: {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
