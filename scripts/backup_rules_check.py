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
  and are excluded from the attribute counts;
- SOURCE files passing through ``validate`` / ``validate_manifest`` /
  ``validate_permission_removals`` are guarded BEFORE any parse: they must be
  regular files (``os.stat`` S_ISREG with symlinks followed on purpose - a
  symlink to a regular file is the existing legal path; a FIFO would make the
  checker's own ``open()`` block until a writer appears) and must contain no
  DOCTYPE declaration (case-insensitive byte scan; the scan also catches
  UTF-16-shaped NUL-interleaved payloads). The DOCTYPE refusal exists because
  expat resolves internal-subset ATTLIST defaults into the parsed
  ElementTree, so a hand-authored internal subset could fabricate rule data
  (e.g. default a ``<exclude>``'s ``domain`` attribute). The compiled
  artifact face carries no DOCTYPE (``aapt dump xmltree`` prints
  elements/attributes only), so the compiled bytes stay the authority for
  schema types there;
- rule ``path``-attribute traversal guard: a path whose value contains
  ``..`` is refused outright. Android's rules schema resolves paths INSIDE
  the rule's domain (there is no parent-directory reference), and the
  runtime behavior of such a path is not something this file-level gate can
  verify, so the stricter-than-runtime refusal is intentional (fail closed);
- the platform-supported `<cross-platform-transfer>` section (Android 16
  QPR2, API 36.1 — iOS cross-platform transfer) is REFUSED on appearance:
  its policy is undecided, and a gate that neither validates nor rejects a
  known runtime section would silently inherit undefined backup semantics;
- the attr↔resource runtime binding (``--check-manifest``): the manifest's
  ``android:fullBackupContent`` / ``android:dataExtractionRules`` attributes
  must name exactly the validated resources. Structured (ElementTree) on
  purpose - a bare-text grep is escapable by the literal surviving in an XML
  comment while the attributes are gone, and a single-quoted attribute value
  would false-FAIL. The MAIN manifest requires ``--require-both`` (absence is
  fatal); overlay manifests fail only on a present-but-different value
  (omitting merges main's value, which is legal). Overlay-level manifest
  merger markers are parsed too: absence is only "legal inheritance" when no
  marker strips main's attribute - a ``tools:remove`` naming these attributes,
  or ``tools:node="replace"/"remove"`` on ``<application>``, deletes the
  merged binding and must FAIL (the APK-level scanner then asserts the merged
  bytes independently; source level stays defense in depth);
- the Play permission blacklist (``--check-permission-removals``): every
  ``<uses-permission>`` / ``<uses-permission-sdk-23>`` element whose
  ``android:name`` contains a blacklisted token must carry
  ``tools:node="remove"`` ON THE ELEMENT (the only merger marker that
  provably deletes a whole lower-priority element; ``tools:remove`` targets
  attributes, ``replace``/``merge`` keep the declaration merged). This is
  the element-aware replacement of the retired per-line ``grep -vE
  "tools:node=..."`` filter, which false-FAILED a legal multi-line element
  (its ``android:name`` line stayed unmarked) and could not separate real
  elements from XML comments.

The artifact-level scanner (``scripts/apk_policy_inspect.py``) reuses the
SAME engine for the compiled bytes: ``ARTIFACT_RULE_MEMBERS`` names the
canonical resource members (``res/xml/backup_rules.xml``,
``res/xml/data_extraction_rules.xml``); the scanner resolves each to the
artifact's actual compiled path by NAME (release obfuscation renames the
entry), dumps it via ``aapt dump xmltree``, rebuilds the tree and calls
``validate_tree`` with ``exact_excludes=True`` - the compiled exclude set
must equal ``REQUIRED_EXCLUDES`` EXACTLY (missing AND unexpected entries are
failures, order-free). Source-level presence-only validation cannot see such
a rewrite.

It is invoked by the shell gate and is directly unit-tested against negative
fixtures. Run with:

    python3 scripts/backup_rules_check.py --expect-root <tag> <file> [...]
    python3 scripts/backup_rules_check.py --check-manifest [--require-both] <manifest.xml> [...]
    python3 scripts/backup_rules_check.py --check-permission-removals <perm,perm,...> <manifest.xml> [...]
"""

from __future__ import annotations

import os
import stat
import sys
import xml.etree.ElementTree as ET
from typing import Iterable

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

# Artifact-level scan targets (consumed by ``apk_policy_inspect.py``): the
# COMPILED rule resources are pinned by their canonical res/<type>/<name>.xml
# member, each paired with the root the runtime expects for that filename. The
# scanner resolves that canonical member to the ACTUAL compiled path by
# resource NAME through aapt2 before dumping it (an AGP release build
# obfuscates entry names, e.g. res/xml/backup_rules.xml -> res/Qq.xml); the
# canonical spelling is the stable label and the fixed-path fallback when no
# aapt2 can be derived. The catalog pins the filenames↔roots here so the
# artifact scanner and the gate can never drift apart.
ARTIFACT_RULE_MEMBERS: tuple[tuple[str, str], ...] = (
    ("res/xml/backup_rules.xml", EXPECTED_ROOT_BACKUP_RULES),
    ("res/xml/data_extraction_rules.xml", EXPECTED_ROOT_DATA_EXTRACTION),
)

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
            # An unknown wrapper does not exempt its content: the runtime
            # validates every START_TAG, so descend and report nested
            # unknown tags too (message completeness, still fail-closed).
            if depth < _MAX_RULE_NEST_DEPTH:
                errors.extend(
                    _validate_rule_children(path, child, branch_label, depth + 1)
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
        # Path traversal guard (module docstring): a "path" value containing
        # ".." cannot be proven to resolve inside the rule's domain. Android's
        # documented rules semantics have no parent-directory references and
        # the gate cannot verify the runtime behavior of such a value, so any
        # occurrence refuses the file (stricter than runtime, fail-closed).
        rule_path = child.get("path")
        if rule_path is not None and ".." in rule_path:
            errors.append(
                f"{path}: {_where(branch_label)}<{tag}> path {rule_path!r} "
                'contains "..": rule paths must stay inside their domain '
                "(Android's backup rules have no parent-directory references)"
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


def _unexpected_exclude_errors(
    path: str, branch_label: str, present: set[tuple[str, str]]
) -> list[str]:
    """Report excludes BEYOND the required five (exact-set mode only).

    Artifact-level authority only: the compiled rule bytes must carry exactly
    ``REQUIRED_EXCLUDES`` (BackupPolicy.KEY_FILES_EXCLUDED) so any
    resources-overlay / merger rewrite of the compiled XML - even one that
    only ADDS entries - fails as the set difference it is (order-free).
    Source-level third parties may over-shield (extra rules never key-leak),
    so ``validate`` keeps the presence-only semantics by default.
    """
    extra = sorted(present - set(REQUIRED_EXCLUDES))
    return [
        f"{path}: {_where(branch_label)}unexpected exclude domain={domain} "
        f"path={rule_path} - the compiled backup rules must carry exactly "
        "the KEY_FILES_EXCLUDED set "
        f"({len(REQUIRED_EXCLUDES)} entries), nothing else"
        for domain, rule_path in extra
    ]


_ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
# Manifest-merger marker namespace (both the plain-E spelling parities dev/
# tools overlays use and the ElementTree-qualified form are matched).
_TOOLS_NS_PREFIX = "tools:"
_TOOLS_NS_URI = "{http://schemas.android.com/tools}"
# ``tools:node`` values that cannot prove the merged manifest keeps an
# attribute the higher-priority document did not re-declare:
# ``remove`` strips the whole element, ``replace`` replaces every inherited
# attribute with this document's value.
_MERGE_STRIP_NODE_VALUES = frozenset({"remove", "replace"})

# Runtime attr↔resource binding: Auto Backup reads these two attributes off
# the merged <application> element, and the named resources are exactly the
# files validate() checks. Values are PINNED (an attr pointing elsewhere gets
# runtime-ignored or throws silently disabling backup).
EXPECTED_MANIFEST_ATTRS: tuple[tuple[str, str], ...] = (
    ("fullBackupContent", "@xml/backup_rules"),
    ("dataExtractionRules", "@xml/data_extraction_rules"),
)


# Root-level rule sections. Both are platform-supported schema sections the
# gate must cover; see the corrections below (the old comment called the
# anything-else branch "runtime silently skips unknown root-level children"
# - true ONLY for genuinely unknown tags, never for PLATFORM-supported ones).
_GATE_SECTIONS = ("cloud-backup", "device-transfer")
# Android 16 QPR2 (API 36.1) adds a third platform section for iOS
# cross-platform transfer: <cross-platform-transfer>. It is no longer an
# "unknown element" the runtime would skip, and no transfer policy has settled
# the gate for it - so the gate refuses its appearance outright instead of
# neither validating nor rejecting a REAL runtime section (undefined backup
# semantics would otherwise pass silently).
_UNDECIDED_SECTIONS: frozenset[str] = frozenset({"cross-platform-transfer"})


def validate_tree(
    root: ET.Element,
    path: str,
    expected_root: str,
    *,
    exact_excludes: bool = False,
) -> list[str]:
    """Structured rule validation on an ALREADY-PARSED tree.

    Shared by ``validate`` (the file loader) and the artifact-level scanner
    (``apk_policy_inspect.py``), which rebuilds an ElementTree from the
    ``aapt dump xmltree`` output of the COMPILED resource: both surfaces run
    the exact same rules so a compiled-XML rewrite cannot diverge from source.
    ``exact_excludes=True`` (artifact level only) additionally refuses
    excludes beyond ``REQUIRED_EXCLUDES`` - see ``_unexpected_exclude_errors``.
    """
    if root.tag != expected_root:
        return [f"{path}: expected root <{expected_root}>, found <{root.tag}>"]

    errors: list[str] = []
    if root.tag == "full-backup-content":
        # Android's validator rejects unknown tags / over-attribute rules here;
        # a file that would fail at runtime must fail the gate, not PASS.
        errors.extend(_validate_rule_children(path, root))
        present = _excludes(root)
        errors.extend(_missing_exclude_errors(path, "", present))
        if exact_excludes:
            errors.extend(_unexpected_exclude_errors(path, "", present))
    elif root.tag == "data-extraction-rules":
        # Android's runtime parser visits EVERY matching section occurrence
        # (duplicates included), so the gate validates all of them too;
        # required excludes must be present in every occurrence (stricter
        # than the runtime union, fail-closed for split-rule edits).
        # NOTE (comment-corrected per review): the "runtime silently skips
        # unknown root-level children" premise below holds ONLY for
        # genuinely unknown tags. Every PLATFORM-supported section name must
        # be covered by this gate (validated pair above, refused as undecided
        # in _UNDECIDED_SECTIONS); hardcoding the walk against
        # supported names keeps a future platform section from sneaking past
        # as a "skip".
        seen: set[str] = set()
        for child in root:
            if child.tag in _UNDECIDED_SECTIONS:
                errors.append(
                    f"{path}: <{child.tag}> is a platform-supported root "
                    "section (Android 16 QPR2 / API 36.1, iOS cross-platform "
                    "transfer); the iOS transfer policy is UNDECIDED - the "
                    "gate must neither validate nor silently accept it, so "
                    "(fail closed) it refuses the element outright"
                )
                continue
            if child.tag not in _GATE_SECTIONS:
                # Runtime silently skips TRULY unknown root-level children;
                # mirror that and let the two required branches carry the
                # policy (platform-named sections never reach this branch).
                continue
            seen.add(child.tag)
            errors.extend(_validate_rule_children(path, child, f"<{child.tag}>"))
            present = _excludes(child)
            errors.extend(_missing_exclude_errors(path, f"<{child.tag}>", present))
            if exact_excludes:
                errors.extend(
                    _unexpected_exclude_errors(path, f"<{child.tag}>", present)
                )
        for branch in _GATE_SECTIONS:
            if branch not in seen:
                errors.append(f"{path}: missing <{branch}>")
    else:
        errors.append(f"{path}: unexpected root element <{root.tag}>")
    return errors


def validate(path: str, expected_root: str) -> list[str]:
    """Return a list of human-readable violations (empty when valid).

    ``expected_root`` is REQUIRED: the file↔root binding is the whole point of
    this validator (Android's ``verifyTopLevelTag`` throws for a wrong root and
    backup/restore is silently disabled). A hint-free call cannot prove which
    schema the caller intended, so every invocation surface must pass the
    binding explicitly - the swapped-file false-PASS (reviewer repro) stays
    closed even outside the shell gate.
    """
    root, load_errors = _read_source_document(path)
    if root is None:
        return load_errors

    return validate_tree(root, path, expected_root)


def _read_source_document(path: str) -> tuple[ET.Element | None, list[str]]:
    """Fail-closed source loader used before ANY file open that could block.

    Guards, in order:

    - open-first-then-``fstat``: the path is opened read-only (O_NONBLOCK
      where the platform provides it; FOLLOWING symlinks on purpose: a
      symlink pointing at a regular file is the existing legal path for
      every caller of these checkers) and ``os.fstat`` on the OPEN FILE's
      own descriptor proves ``S_ISREG`` against ONE pinned inode - a FIFO,
      directory or device file refuses immediately (``ET.parse`` would block
      on a FIFO until a writer appears, so the checker itself must not stall
      on one), and the old stat-then-open window can no longer swap a FIFO
      past the regular-file proof: open/fstat/read all share one fd, so an
      mkfifo-in-between race is structurally impossible post-fix. A
      non-regular path refuses from whichever layer exposes it first (CPython
      rejects ``os.fdopen`` on a directory fd outright; that and the
      ``os.open`` refusal lane surface the same structured message);
    - a case-insensitive DOCTYPE scan over the raw bytes BEFORE
      ``ET.fromstring``: expat materializes internal-subset ATTLIST DEFAULTS
      into the parsed tree, so a hand-authored internal subset could fabricate
      rule data this gate pins (measured:
      ``<!ATTLIST exclude domain CDATA "sharedpref">`` injects a ``domain``
      value into the parsed Element that the source text never carries).
      DOCTYPE declarations are refused outright at this SOURCE surface; the
      compiled artifact face carries none (``aapt dump xmltree`` prints
      elements/attributes only), so the compiled bytes stay the authority
      for schema types there;
    - then the ordinary ElementTree parse with the historical messages
      ("unreadable" / "invalid XML") preserved for existing surfaces.

    The scan lowercases the raw bytes (plain ASCII encodings) AND a
    NUL-stripped copy of them, so UTF-16-shaped NUL-interleaved sources hit
    the same refusal.
    """
    non_regular_refusal = (
        f"{path}: not a regular file - refused fail-closed "
        "(FIFO/directory/device would make ET.parse's open() block the checker)"
    )
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NONBLOCK", 0))
    except IsADirectoryError:
        # Same structured outcome as the fdopen/fstat lanes below: whichever
        # layer exposes the non-regular path first refuses with one message
        # and opens nothing that could block a later ET.parse.
        return None, [non_regular_refusal]
    except OSError as exc:
        return None, [f"{path}: unreadable: {exc}"]
    try:
        try:
            handle = os.fdopen(fd, "rb")
        except IsADirectoryError:
            # CPython refuses to build a stream on a directory fd outright;
            # the fd is NOT owned by any stream yet, so close it here.
            os.close(fd)
            return None, [non_regular_refusal]
        except OSError:
            # Any other fdopen failure likewise leaves the fd unowned; close
            # it defensively and let the outer OSError lane report unreadable.
            os.close(fd)
            raise
        # fdopen succeeded: the stream owns the fd and the context manager
        # closes it on every exit path below.
        with handle:
            # S_ISREG is proven on the OPEN FILE's descriptor: one fd pins one
            # inode across fstat and read. A FIFO-named path never wedges the
            # checker - O_NONBLOCK made open() return immediately, and the
            # fstat refusal fires before any read.
            stat_result = os.fstat(handle.fileno())
            if not stat.S_ISREG(stat_result.st_mode):
                return None, [non_regular_refusal]
            raw = handle.read()
    except OSError as exc:
        return None, [f"{path}: unreadable: {exc}"]
    if b"<!doctype" in raw.lower() or b"<!doctype" in raw.replace(b"\x00", b"").lower():
        return None, [
            f"{path}: DOCTYPE declarations are rejected: internal "
            "ATTLIST/defaulted attributes would fabricate rule data "
            "(expat resolves internal-subset ATTLIST defaults into the parsed "
            "ElementTree; the compiled artifact surface carries no DOCTYPE and "
            "stays governed by the compiled bytes)"
        ]
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as exc:
        return None, [f"{path}: invalid XML: {exc}"]
    return root, []


def _tools_marker_entries(element: ET.Element) -> tuple[tuple[str, str], ...]:
    """Return ``(local-name, value)`` pairs of tools: merger marker attributes.

    Both the namespaced spelling (``xmlns:tools`` bound to the Android tools
    namespace, parsed by ElementTree as ``{uri}name``) and the non-namespaced
    literal ``tools:`` spelling are matched; the literal form is defensive for
    hand-written fixtures where the parser keeps the prefix in the name.
    """
    entries: list[tuple[str, str]] = []
    for key, value in element.attrib.items():
        if key.startswith(_TOOLS_NS_URI):
            entries.append((key[len(_TOOLS_NS_URI) :], value))
        elif key.startswith(_TOOLS_NS_PREFIX):
            entries.append((key[len(_TOOLS_NS_PREFIX) :], value))
    return tuple(entries)


def validate_manifest(
    path: str,
    require_both: bool = False,
    expected_attrs: tuple[tuple[str, str], ...] | None = None,
    binding_kind: str = "backup",
) -> list[str]:
    """Bind the manifest's backup attributes to the validated resources.

    ``expected_attrs`` overrides the (attr, pinned resource) set - the NSC
    gate (``nsc_check.py``) reuses this exact marker machinery for
    ``android:networkSecurityConfig`` so the merger-marker semantics exist
    only here. ``binding_kind`` only names the binding family in messages.

    Structured (ElementTree) on purpose: a bare-text grep is escapable by the
    literal surviving inside an XML comment while the attributes are gone -
    the gate would PASS while runtime Auto Backup runs unprotected (key leak).
    ``require_both`` (the MAIN manifest) fails closed on an ABSENT attribute.
    Overlay manifests (default) fail only on a PRESENT-but-different value:
    omitting an attribute is legal - merger inherits main's value - and an
    overlay might not even carry an <application> (whole-tree inheritance,
    e.g. a permissions-only overlay), which is equally legal there.

    Merger markers change the absence semantics (fail-closed): an absent
    attribute is only a legal inheritance when no marker strips main's value.
    ``tools:remove`` naming one of the pinned attributes, or
    ``tools:node="remove"`` / ``tools:node="replace"`` on ``<application>``
    without re-declaring every pinned value, deletes the merged binding -
    the merged manifest would run Auto Backup unprotected on the key files,
    so the source-level gate refuses it. The APK-level scanner asserts the
    merged bytes independently; this source check is defense in depth.
    """
    bindings = tuple(EXPECTED_MANIFEST_ATTRS if expected_attrs is None else expected_attrs)
    root, load_errors = _read_source_document(path)
    if root is None:
        return load_errors
    if root.tag != "manifest":
        return [
            f"{path}: manifest check needs a <manifest> root, found <{root.tag}>"
        ]
    errors: list[str] = []
    application = root.find("application")
    expected_values = dict(bindings)

    def _own(name: str) -> str | None:
        return None if application is None else application.get(f"{_ANDROID_NS}{name}")

    # Merger markers on <manifest> root that name a pinned binding: the
    # marker surface for these attributes is <application>/target nodes, so a
    # root-level marker naming them is an undefined shape - refuse to prove
    # anything about the merged outcome from this document alone.
    for local, value in _tools_marker_entries(root):
        if local in ("remove", "node") and any(
            name in value for name in expected_values
        ):
            errors.append(
                f"{path}: tools:{local} marker on <manifest> names a {binding_kind} "
                f"binding attribute ({value.strip()!r}) - merger outcome for "
                f"{tuple(expected_values)} is unverifiable here"
            )
    if application is None:
        if errors:
            return errors
        if require_both:
            return [f"{path}: manifest check needs an <application> element"]
        # Overlay without <application>: whole-tree inheritance is legal.
        return []
    for local, value in _tools_marker_entries(application):
        mentions = [name for name in expected_values if name in value]
        if local == "remove":
            for name in mentions:
                own = _own(name)
                if own is None:
                    errors.append(
                        f"{path}: tools:remove strips android:{name} while the "
                        f"attribute is absent - the merged manifest would lose "
                        f"{expected_values[name]}"
                    )
                # own != expected is reported by the generic loop below; own
                # == expected from THIS document survives the marker legally.
        elif local == "node" and value.strip() in _MERGE_STRIP_NODE_VALUES:
            if value.strip() == "remove":
                lost = " AND ".join(f"android:{name}" for name, _ in bindings)
                errors.append(
                    f"{path}: tools:node=\"remove\" on <application> strips the "
                    f"whole element - the merged manifest would lose {lost}"
                )
            else:
                for name, expected in bindings:
                    own = _own(name)
                    if own is None:
                        errors.append(
                            f"{path}: tools:node=\"replace\" on <application> does "
                            f"not re-declare android:{name} - the merged manifest "
                            f"would lose {expected}"
                        )
    for name, expected in bindings:
        value = application.get(f"{_ANDROID_NS}{name}")
        if value is None:
            if require_both:
                errors.append(
                    f"{path}: android:{name} must reference {expected} "
                    "(attribute absent)"
                )
            continue
        if value != expected:
            errors.append(
                f"{path}: android:{name} must reference {expected} (got {value!r})"
            )
    return errors


# Ecosystem permission tags the removal scan covers. <uses-permission-sdk-23>
# exists precisely to gate API 23+; scanning only <uses-permission> would
# shrink the old raw grep's catch-universe.
_PERMISSION_ELEMENTS: tuple[str, ...] = ("uses-permission", "uses-permission-sdk-23")


def validate_permission_removals(path: str, blacklisted: Iterable[str]) -> list[str]:
    """Prove every blacklisted permission element carries ``tools:node="remove"``.

    The Play source blacklist used to be a per-line ``grep -vE
    "tools:node=..."`` filter: a LEGAL multi-line element whose
    ``android:name`` and ``tools:node="remove"`` sit on different lines left
    the name line un-filtered and false-FAILED the gate, while a
    whole-file grep could also match text inside XML comments. This
    structured scan (same marker machinery as ``validate_manifest``) reports
    a violation only for a REAL, comment-proof element whose android:name
    contains a blacklisted token and which carries no merger-removal marker
    on ITSELF: ``tools:node="remove"`` is the only marker that deletes a
    whole element from a lower-priority document - ``tools:remove`` names
    attributes, and ``replace``/``merge`` keep the declaration merged.

    Both ecosystem permission tags are scanned (``uses-permission-sdk-23``
    declares the same permission on API 23+ and would otherwise shrink the
    old grep's catch-universe).
    """
    tokens = tuple(blacklisted)
    root, load_errors = _read_source_document(path)
    if root is None:
        return load_errors
    if root.tag != "manifest":
        return [f"{path}: manifest check needs a <manifest> root, found <{root.tag}>"]
    errors: list[str] = []
    for tag in _PERMISSION_ELEMENTS:
        for element in root.iter(tag):
            name = element.get(f"{_ANDROID_NS}name")
            if not name:
                # A permission element whose name cannot be resolved (no
                # xmlns:android binding) is an unprovable shape while the
                # grep it replaces never tolerated the literal either way.
                errors.append(
                    f"{path}: <{tag}> carries no android:name "
                    "(uncharged unknown permission element)"
                )
                continue
            for token in tokens:
                if token not in name:
                    continue
                markers = dict(_tools_marker_entries(element))
                if markers.get("node", "").strip() != "remove":
                    errors.append(
                        f"{path}: <{tag} android:name={name!r}> declares "
                        f"blacklisted permission {token} without "
                        "tools:node=\"remove\" on the element - the merged "
                        "manifest keeps the permission"
                    )
    return errors


def main(argv: list[str]) -> int:
    args = argv[1:]
    expected_root: str | None = None
    check_manifest = False
    require_both = False
    permission_tokens: tuple[str, ...] | None = None
    paths: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--expect-root":
            if i + 1 >= len(args):
                print("FAIL: --expect-root requires a value", file=sys.stderr)
                return 2
            if expected_root is not None:
                print("FAIL: duplicate --expect-root", file=sys.stderr)
                return 2
            expected_root = args[i + 1]
            i += 2
        elif args[i] == "--check-manifest":
            check_manifest = True
            i += 1
        elif args[i] == "--check-permission-removals":
            if i + 1 >= len(args):
                print(
                    "FAIL: --check-permission-removals requires a "
                    "comma-separated permission list",
                    file=sys.stderr,
                )
                return 2
            if permission_tokens is not None:
                print("FAIL: duplicate --check-permission-removals", file=sys.stderr)
                return 2
            # Tokens are SUBSTRINGS of android:name, and callers routinely
            # pass whitespace CSVs ("SEND_SMS, RECEIVE_SMS"): storing the
            # unstripped " RECEIVE_SMS" never matches anything and the
            # gate silently fails open. Store the stripped form instead;
            # strip-to-empty entries still drop out, so an all-empty
            # blacklist keeps exiting 2 below (fail-closed on no tokens).
            tokens = tuple(t.strip() for t in args[i + 1].split(",") if t.strip())
            if not tokens:
                print(
                    "FAIL: --check-permission-removals blacklist is empty",
                    file=sys.stderr,
                )
                return 2
            permission_tokens = tokens
            i += 2
        elif args[i] == "--require-both":
            require_both = True
            i += 1
        elif args[i].startswith("--"):
            print(f"FAIL: unknown option {args[i]}", file=sys.stderr)
            return 2
        else:
            paths.append(args[i])
            i += 1
    if not paths:
        # Fail closed: an empty invocation must never PASS an empty run
        # (the hint-free swapped-file false-PASS was the reviewer's original
        # repro; the gate and every other caller pass bindings explicitly).
        print(
            "usage: backup_rules_check.py (--expect-root <tag> <rules.xml> [...] | "
            "--check-manifest [--require-both] <manifest.xml> [...] | "
            "--check-permission-removals <perm,perm,...> <manifest.xml> [...]) "
            "(--expect-root full-backup-content for full-backup-content files; "
            "--expect-root data-extraction-rules for data-extraction-rules files)",
            file=sys.stderr,
        )
        return 2
    if expected_root is None and not check_manifest and permission_tokens is None:
        # A rules-file path without any binding hint cannot prove the schema.
        print(
            "usage: backup_rules_check.py --expect-root <tag> <rules.xml> [...] | "
            "--check-manifest [--require-both] <manifest.xml> [...] | "
            "--check-permission-removals <perm,perm,...> <manifest.xml> [...]",
            file=sys.stderr,
        )
        return 2
    if (expected_root is not None) + check_manifest + (permission_tokens is not None) > 1:
        # Exactly ONE command mode at a time; all combinations are refused
        # (each mode has its own path contract).
        print(
            "FAIL: --check-manifest, --expect-root and "
            "--check-permission-removals are mutually exclusive",
            file=sys.stderr,
        )
        return 2
    if require_both and not check_manifest:
        print("FAIL: --require-both requires --check-manifest", file=sys.stderr)
        return 2
    errors: list[str] = []
    for path in paths:
        if check_manifest:
            errors.extend(validate_manifest(path, require_both))
        elif permission_tokens is not None:
            errors.extend(validate_permission_removals(path, permission_tokens))
        else:
            errors.extend(validate(path, expected_root))
    if errors:
        for line in errors:
            print(f"FAIL: {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
