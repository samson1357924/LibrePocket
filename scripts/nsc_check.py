"""Structured XML validation for Android network security config files.

The NSC gate in ``scripts/play_policy_check.sh`` used to grep for
``cleartextTrafficPermitted="false"`` anywhere in a file: ANY occurrence of
"false" satisfied the deny-all pin, so the reviewer's counterexample
(``<base-config cleartextTrafficPermitted="false"/>`` next to
``<domain-config cleartextTrafficPermitted="true"><domain>example.com</domain>``)
passed while the whole config actually enabled cleartext for a domain. A raw
grep also cannot tell whether the "false" lives inside an XML comment.

This module parses the XML with the stdlib parser (comments are ignored by
construction) and enforces:

- the file↔root binding: the root MUST be ``<network-security-config>``
  (AOSP ``XmlConfigSource.parseNetworkSecurityConfig`` calls
  ``XmlUtils.beginDocument(parser, "network-security-config")`` and any other
  root makes the runtime throw at first use). The CLI always takes the policy
  via ``--mode`` (required, like ``backup_rules_check.py --expect-root``):
  a hint-free call is refused so every invocation surface states its rules;
- mode rules:
  * ``--mode release``: an explicit ``<base-config
    cleartextTrafficPermitted="false">`` must exist and NO element may carry
    ``cleartextTrafficPermitted="true"`` (base-config, domain-config, or
    debug-overrides) - deny-all, structural, not a comment survivor;
  * ``--mode debug``: the same base-config pin. ``cleartextTrafficPermitted="true"``
    is allowed ONLY on a ``<domain-config>`` whose ``<domain>`` entries are all
    in the loopback dev allowlist ``localhost / 127.0.0.1 / 10.0.2.2`` (the
    exact ``LlmProvider.validateBaseUrl`` http:// allowlist) and each such
    ``<domain>`` sets ``includeSubdomains="false"`` EXPLICITLY. AOSP's runtime
    default for includeSubdomains is ALREADY ``false`` (``parseDomain`` reads
    ``getAttributeBooleanValue(..., false)``), so an ABSENT attribute cannot
    widen anything at runtime - the gate demanding the explicit ``"false"``
    is stricter-than-runtime review bookkeeping (the whitelist claim must say
    so in text), not a widening guard; an explicit ``"true"`` DOES widen to
    the subdomain tree and fails. Domain names are normalized the way the
    runtime sees them: ``parseDomain`` does ``getText().trim().
    toLowerCase(Locale.US)`` where Java's ``String#trim`` drops ONLY
    characters <= U+0020 (NBSP / full-width spaces survive it - Python's
    ``str.strip()`` would over-normalize, see ``_java_trim``); names outside
    the allowlist after that exact normalization fail the gate;
  * nested ``<domain-config>`` cleartext inheritance: AOSP
    ``XmlConfigSource#parseConfigEntry`` recurses into an inner
    ``<domain-config>`` with the OUTER domain-config's ``Builder`` as parent
    ("config inheritence will handle the rest"), and
    ``NetworkSecurityConfig.Builder#getEffectiveCleartextTrafficPermitted()``
    walks that parent chain when a builder has no value of its own, while the
    final parse pass re-parents every top-level builder to base-config. So an
    entry without its own attribute INHERITS the enclosing chain's effective
    cleartext value; the gate carries that effective value down each nested
    chain and applies the exact same rules to an inherited ``"true"`` as to an
    explicit one - release refuses cleartext on every entry, debug requires
    every affected ``<domain>`` to hit the loopback allowlist with its
    explicit ``includeSubdomains="false"``. An inner entry's own ``"false"``
    (``Builder#setCleartextTrafficPermitted`` overrides the parent) legally
    narrows and stays valid;
- Android runtime strictness, mirrored from AOSP
  ``frameworks/base/core/java/android/security/net/config/XmlConfigSource.java``:
  * duplicates of the runtime throws at parse time are FAILs here too
    ("Only one base-config allowed", "Only one debug-overrides allowed",
    "Multiple trust-anchor elements not allowed", duplicate <domain> text
    ("has already been specified")) and ``<domain-config>`` without a
    ``<domain>`` child ("No domain elements in domain-config");
  * parse-time VALUE semantics are mirrored through the ``_VALUE_CHECKS``
    table (single landing point in ``_attr_errors``, so source and artifact
    faces cannot drift): ``<pin digest=...>`` must be
    ``"SHA-256".equalsIgnoreCase`` per ``Pin.isSupportedDigestAlgorithm``
    (missing attr mirrors parsePin seeing null; ``sha-256`` in any case is
    legal) and the TEXT must be Base64 with exactly 32 decoded bytes
    (``Base64.decode`` + ``Pin.getDigestLength("SHA-256") == 32``);
    ``<pin-set expiration>`` must be a real ``yyyy-MM-dd`` date
    (``SimpleDateFormat("yyyy-MM-dd")`` + ``setLenient(false)``: 2023-02-30
    throws, 2023-02-28 parses; ABSENT stays legal like the runtime's
    ``Long.MAX_VALUE``, and an EMPTY ``<pin-set/>`` is also runtime-legal on
    current main, so the gate accepts it in the same direction - not
    mirrored as a throw); ``<certificates src>`` is the AOSP
    ``parseCertificatesEntry`` allow set: ``system`` / ``user`` / ``wfa``
    (WfaCertificateSource - wfa IS runtime-accepted hard-evidence even
    though the runtime's diagnostic string omits it) plus resource
    references, which require a compiled ``TypedValue.TYPE_REFERENCE``
    (source ``"@..."`` spellings and the compiled typed payloads
    ``@0x…``/``@res/0x…``; "?attr"-typed values NEVER resolve per
    ``XmlBlock.Parser#getAttributeResourceValue`` and are refused). A
    file-path src throws.
    Documented stricter-than-runtime residuals: Android's
    ``Base64.decode(digest, 0)`` tolerates limited line breaks (the gate is
    stricter), Java's ``DateFormat.parse`` ignores trailing unparsed
    characters (``strptime`` refuses them), and a source-level ``"@..."``
    string cannot be distinguished from a real resource reference - that
    residual is covered by the compiled-artifact face, which sees the dump
    type; no file-path form is admitted on either face;
  * runtime visibility limits made explicit: unknown elements are SILENTLY
    skipped by the runtime (``XmlUtils.skipCurrentTag``) and unknown
    attributes are SILENTLY ignored by ``parseConfigEntry``'s attribute loop
    - with one nuance each: (a) ``hstsEnforced`` IS honored by the runtime
    (``builder.setHstsEnforced``), so the gate's refusal of it is a
    stricter-than-runtime fail-safe rather than a silent-ignore mirror (a
    future HSTS support change must whitelist it in the same edit), and
    (b) ``<certificateTransparency>`` inside ``debug-overrides`` is an
    EXPLICIT runtime throw ("certificateTransparency not allowed in
    debug-overrides"), mirrored with its own message instead of the generic
    skip wording. The gate deliberately FAILS CLOSED on the remaining
    unknown shapes instead of mirroring the silent skip: at source level a
    stray/typo'd attribute (e.g. ``includesubdomains``) would silently flip
    semantics or invite shape-drift escapes, so the stricter-than-runtime
    refusal is pinned here with this rationale rather than hidden;
  * nested ``<domain-config>`` walks are bounded by
    ``_MAX_NS_DOMAIN_NEST_DEPTH`` (mirroring backup_rules_check's
    ``_MAX_RULE_NEST_DEPTH``): pathological nesting surfaces as a clean
    structured FAIL, never an uncaught RecursionError traceback;
  * SOURCE files passing through ``validate`` are guarded BEFORE any parse:
    they must be regular files proven on the OPEN file's descriptor
    (read-only O_NONBLOCK open + ``os.fstat`` S_ISREG, symlinks followed on
    purpose - a symlink to a regular file is the existing legal path; a
    FIFO returns from the non-blocking open and is refused by the fstat
    regular-file proof instead of wedging the checker's own ``open()``) and
    must contain no DOCTYPE declaration (case-insensitive byte scan): expat
    resolves
    internal-subset ATTLIST defaults into the parsed ElementTree, so a
    hand-authored internal subset could fabricate the very attributes this
    gate pins. The compiled artifact face carries no DOCTYPE (``aapt dump
    xmltree`` prints elements/attributes only), so the compiled bytes stay
    the authority for typed values;
  * ``tools:``-namespace attributes never reach the runtime (aapt strips them)
    and are excluded from all attribute checks;
  * debug-overrides policies only apply to debuggable builds, but this gate
    does NOT trust that: a ``debug-overrides``/``<base-config>`` claim of
    ``cleartextTrafficPermitted="true"`` fails in BOTH modes (release pins
    deny-all; debug allows the escape only inside a whitelist-eligible
    ``<domain-config>``);
- the attr↔resource binding (``--check-manifest``[, ``--require-both``]):
  ``android:networkSecurityConfig`` on the merged-candidate <application> must
  pin ``@xml/network_security_config`` (allowLost styled like the backup
  attrs: the MAIN manifest fails closed on absence; overlay manifests fail
  only on a present-but-different value, since omission merges main's
  binding). Merger markers are handled by ``backup_rules_check.validate_manifest``
  with an attribute-set override so the marker semantics exist exactly once.

Exit codes: 0 = valid, 1 = violation/parse error, 2 = usage error.
Run with:

    python3 scripts/nsc_check.py --mode (release|debug) <config.xml> [...]
    python3 scripts/nsc_check.py --check-manifest [--require-both] <manifest.xml> [...]
"""

from __future__ import annotations

import base64
import datetime
import importlib.util
import os
import stat
import sys
import types
from pathlib import Path
import xml.etree.ElementTree as ET
from typing import Callable

NSC_ROOT = "network-security-config"
MODES = ("release", "debug")

# Debug-mode loopback cleartext allowlist: exactly the domains
# LlmProvider.validateBaseUrl accepts for http:// in dev builds. Nothing else
# may enable cleartext, and never for subdomains (LAN hosts go via adb reverse).
DEBUG_CLEARTEXT_DOMAINS = frozenset(("localhost", "127.0.0.1", "10.0.2.2"))

# Source↔runtime manifest binding: the attr must reference exactly this
# resource (the source-level mirror of the artifact authority in
# apk_policy_inspect.py).
EXPECTED_MANIFEST_RESOURCE = "@xml/network_security_config"

# tools:* attributes (both spellings ElementTree may surface) are stripped by
# aapt before the runtime sees the resource; all other attribute names count.
_TOOLS_ATTR_NAMESPACES = (
    "tools:",  # non-namespaced literal (defensive)
    "{http://schemas.android.com/tools}",  # ElementTree-qualified tools:*
)
_TOOLS_NS_PREFIX = "tools:"
_TOOLS_NS_URI = "{http://schemas.android.com/tools}"

# Legal element shapes (Android network-security-config schema). Any tag or
# attribute outside these tables FAILS (see the runtime-skip rationale above).
_CONFIG_TAGS = ("base-config", "domain-config", "debug-overrides")
_ROOT_CHILDREN = frozenset(_CONFIG_TAGS)
_BASE_CHILDREN = ("trust-anchors", "certificateTransparency")
_DOMAIN_CHILDREN = ("domain", "trust-anchors", "pin-set", "domain-config", "certificateTransparency")
_DEBUG_CHILDREN = ("trust-anchors",)

_ALLOWED_ATTRS: dict[str, frozenset[str]] = {
    NSC_ROOT: frozenset(),
    "base-config": frozenset(("cleartextTrafficPermitted",)),
    "domain-config": frozenset(("cleartextTrafficPermitted",)),
    "debug-overrides": frozenset(("cleartextTrafficPermitted",)),
    "trust-anchors": frozenset(),
    "certificates": frozenset(("src", "overridePins")),
    "pin-set": frozenset(("expiration",)),
    "pin": frozenset(("digest",)),
    "certificateTransparency": frozenset(("enabled",)),
    "domain": frozenset(("includeSubdomains",)),
}
_ALLOWED_CHILDREN: dict[str, frozenset[str]] = {
    NSC_ROOT: _ROOT_CHILDREN,
    "base-config": frozenset(_BASE_CHILDREN),
    "domain-config": frozenset(_DOMAIN_CHILDREN),
    "debug-overrides": frozenset(_DEBUG_CHILDREN),
    "trust-anchors": frozenset(("certificates",)),
    "certificates": frozenset(),
    "pin-set": frozenset(("pin",)),
    "pin": frozenset(),
    "certificateTransparency": frozenset(),
    "domain": frozenset(),
}
# Runtime throws when `src` is missing ("certificates element missing src
# attribute") and when `digest` is missing (Unsupported pin digest algorithm
# null) - the gate mirrors both as required attributes.
_REQUIRED_ATTRS: dict[str, frozenset[str]] = {
    "certificates": frozenset(("src",)),
    "pin": frozenset(("digest",)),
}
_BOOL_ATTRS = frozenset(("cleartextTrafficPermitted", "includeSubdomains", "overridePins", "enabled"))

# Elements whose attribute loop reads hstsEnforced (parseConfigEntry covers
# the three config-entry tags only); used to shape the refusal rationale.
_HSTS_HONORING_TAGS = frozenset(("base-config", "domain-config", "debug-overrides"))

# Nesting guard for the nested <domain-config> walk (AOSP parseConfigEntry
# recurses once per nesting level): bounded mirrors backup_rules_check's
# _MAX_RULE_NEST_DEPTH so a pathological suffix-of-nested-configs fixture
# surfaces as a clean structured FAIL line instead of an uncaught
# RecursionError traceback.
_MAX_NS_DOMAIN_NEST_DEPTH = 64

# -- Parse-time VALUE semantics, mirrored from AOSP (frameworks/base main) --
#
# Each entry keys a ``(tag, attr)`` pair; ``attr=None`` denotes the element's
# own TEXT. A None return means "runtime-legal at parse time"; anything else
# is the mirrored parser-exception wording. Routing the checks through
# ``_attr_errors`` is the single landing point: the source-file gate and the
# compiled-artifact scanner (``apk_policy_inspect.py``, which rebuilds the
# same ElementTree shape from ``aapt dump xmltree`` and calls
# ``validate_tree``) then enforce the identical value semantics, and the
# trust-anchors / pin-set / config-entry walkers that already delegate there
# cover all three nsconfig families with this one table.
#
# Hard AOSP evidence (fetched from frameworks/base main):
# - ``XmlConfigSource.parsePin``: ``Pin.isSupportedDigestAlgorithm(digest)``
#   -> ``Pin.java`` `"SHA-256".equalsIgnoreCase(algorithm)` ("Currently only
#   SHA-256 is supported. SHA-512 if/once Chromium networking stack supports
#   it."); a MISSING attr is null there and hits the same throw, so the
#   digest-algorithm check also covers the missing-attr case (on top of the
#   _REQUIRED_ATTRS mirror). Then ``Base64.decode(getText().trim(), 0)``
#   ("Invalid pin digest") and
#   ``digest length N does not match expected length for X of M`` with
#   ``Pin.getDigestLength("SHA-256") == 32``.
# - ``XmlConfigSource.parsePinSet``: ``expiration`` is OPTIONAL (absent ->
#   ``Long.MAX_VALUE``, no throw) and validated with
#   ``SimpleDateFormat("yyyy-MM-dd")`` + ``setLenient(false)`` ->
#   "Invalid expiration date in pin-set". An EMPTY ``<pin-set/>`` is also
#   runtime-legal (current main builds ``PinSet(pins=[])`` without throwing),
#   so the gate likewise accepts one in the same direction - the empty shape
#   is NOT mirrored as a throw.
# - ``XmlConfigSource.parseCertificatesEntry``: after the required-attr mirror,
#   ``getAttributeResourceValue(null, "src", -1) != -1`` (which requires a
#   compiled TypedValue.TYPE_REFERENCE - XmlBlock.Parser#getAttributeResource
#   Value converts no other types, so "?attr"-typed values never become
#   ResourceCertificateSource) or ``"system"`` / ``"user"`` / ``"wfa"``
#   (WfaCertificateSource - wfa IS runtime-accepted even though the runtime's
#   own diagnostic string
#   "Unknown certificates src. Should be one of system|user|@resourceVal"
#   omits it). Any file-path src throws.
#
# One documented stricter-than-runtime residual per family:
# - pin text: android.util.Base64.decode(digest, 0) skips limited ASCII line
#   breaks regardless of flags; Python's ``validate=True`` does not. The gate
#   refuses whitespace-formatted pins (fail-closed, never less) - the runtime
#   would tolerate them.
# - pin text trimming mirrors Java's ``String#trim`` via ``_java_trim`` (NBSP
#   and friends SURVIVE trimming, and base64 then refuses them exactly like
#   the runtime decode).
# - an element with NO text at all reaches the runtime's separate
#   "Missing pin digest" branch (parsePin's ``parser.next() != TEXT``);
#   ElementTree cannot distinguish `<pin/>` from `<pin>  </pin>` faithfully
#   at this surface, so the gate reports the length wording (0) for both -
#   both shapes fail either way.
# - expiration: Java's ``DateFormat.parse`` ignores un-parsed TRAILING
#   characters, so the runtime would accept a "yyyy-MM-dd" prefix followed by
#   junk; ``strptime`` refuses the whole-string form (stricter, fail-closed).
# - certificates src at SOURCE level: the literal "@name" spellings and the
#   compiled typed REFERENCE payloads ("@0x…", "@res/0x…" as printed by aapt
#   and passed through verbatim by the artifact rebuild) are both accepted by
#   the "@" prefix. Source XML cannot distinguish a literal "@foo" STRING from
#   a resource reference - that residual is covered by the compiled-artifact
#   surface, whose dump shows the type; "?attr"-typed values NEVER resolve
#   (TYPE_REFERENCE only, see the predicate KDoc) and are refused on both
#   faces alongside file-path forms.


def _pin_digest_algorithm_error(value: str | None) -> str | None:
    # Mirror Pin.isSupportedDigestAlgorithm: "SHA-256".equalsIgnoreCase(value).
    # None (missing attribute) mirrors the runtime string for null - already
    # covered by _REQUIRED_ATTRS too, but the value semantics stay complete.
    if value is not None and value.lower() == "sha-256":
        return None
    return (
        f"Unsupported pin digest algorithm: {value if value is not None else 'null'} "
        "(AOSP parsePin throws via Pin.isSupportedDigestAlgorithm - "
        '"SHA-256".equalsIgnoreCase is the only supported form; sha-256 spelled '
        "in any case stays legal, SHA-512/MD5/absent throw)"
    )


def _pin_digest_value_error(text: str | None) -> str | None:
    # Mirror parsePin: Base64.decode(getText().trim(), 0) then length == 32.
    digest_text = _java_trim(text or "")
    try:
        decoded = base64.b64decode(digest_text, validate=True)
    except ValueError:
        return 'Invalid pin digest (AOSP parsePin: Base64.decode failure)'
    if len(decoded) != 32:
        return (
            f"digest length {len(decoded)} does not match expected length for "
            f"SHA-256 of 32 (AOSP parsePin wording; Pin.getDigestLength)"
        )
    return None


def _pin_set_expiration_error(value: str | None) -> str | None:
    # Mirror parsePinSet: the attribute is OPTIONAL; a present value must be a
    # real yyyy-MM-dd date (SimpleDateFormat("yyyy-MM-dd") + setLenient(false)).
    if value is None:
        return None
    try:
        datetime.datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        return (
            f"Invalid expiration date in pin-set (AOSP parsePinSet: "
            'SimpleDateFormat("yyyy-MM-dd") + setLenient(false); '
            f"got {value!r})"
        )
    return None


def _certificates_src_error(value: str | None) -> str | None:
    # Mirror parseCertificatesEntry. None (missing) is already reported by the
    # _REQUIRED_ATTRS mirror ("certificates element missing src attribute");
    # do not double-report it here.
    if value is None:
        return None
    if value in ("system", "user", "wfa"):
        return None
    if value.startswith("@"):
        # Resource-reference form. Evidence for the non-@ branches: compiled
        # typed values only enter the ResourceCertificateSource branch when
        # their type is TypedValue.TYPE_REFERENCE
        # (XmlBlock.Parser#getAttributeResourceValue: "don't attempt to
        # convert any other types" - TYPE_ATTRIBUTE i.e. "?attr/..." never
        # resolves to an id, so the runtime then compares the STRING "src"
        # value and throws). The SOURCE face shows literal "@name" spellings;
        # the COMPILED artifact face prints the typed REFERENCE payload
        # ("@0x…", "@res/0x…"), which apk_policy_inspect passes through
        # verbatim. A source-level literal "@foo" STRING cannot be
        # distinguished from a resource reference at THIS surface - that
        # residual is covered by the compiled-artifact face, which sees the
        # dump type; no file-path form is admitted on either surface.
        return None
    return (
        "Unknown certificates src. Should be one of system|user|@resourceVal "
        f"(AOSP parseCertificatesEntry wording; got {value!r} - system, user, "
        "wfa and @resource references are the runtime-accepted forms, "
        "file-path srcs throw)"
    )


# (tag, attr) -> predicate. attr=None keys the element's own TEXT (`pin`).
_VALUE_CHECKS: dict[tuple[str, str | None], Callable[[str | None], str | None]] = {
    ("pin", "digest"): _pin_digest_algorithm_error,
    ("pin", None): _pin_digest_value_error,
    ("pin-set", "expiration"): _pin_set_expiration_error,
    ("certificates", "src"): _certificates_src_error,
}


def _is_tools_attribute(key: str) -> bool:
    return key.startswith(_TOOLS_NS_URI) or key.startswith(_TOOLS_NS_PREFIX)


def _attr_errors(path: str, where: str, element: ET.Element, errors: list[str]) -> None:
    allowed = _ALLOWED_ATTRS[element.tag]
    required = _REQUIRED_ATTRS.get(element.tag, frozenset())
    for key in element.keys():
        if _is_tools_attribute(key):
            continue
        if key in allowed:
            if key in _BOOL_ATTRS:
                value = element.get(key)
                if value not in ("true", "false"):
                    errors.append(
                        f"{path}: {where} {key} must be exactly \"true\" or \"false\" "
                        f"(got {value!r})"
                    )
        elif key == "hstsEnforced" and element.tag in _HSTS_HONORING_TAGS:
            # Not in the gate's schema table, BUT the runtime DOES read it on
            # config entries: AOSP XmlConfigSource.parseConfigEntry's
            # attribute loop calls ``builder.setHstsEnforced(...)`` for this
            # attr. So this refusal is a stricter-than-runtime fail-safe,
            # NOT a mirror of a runtime that "silently ignores" it - a
            # future HSTS support change must whitelist the attribute in the
            # SAME edit (never leave the gate refusing an attr the runtime
            # honors once a pin depends on it). On OTHER elements the attr
            # loop never runs, so the generic drift message below applies.
            errors.append(
                f"{path}: {where} unknown attribute {key!r} "
                "(the Android runtime DOES honor hstsEnforced on config "
                "entries - XmlConfigSource.parseConfigEntry calls "
                "Builder#setHstsEnforced - so the gate refusing it is a "
                "stricter-than-runtime fail-safe, not a silent-ignore "
                "mirror; a future HSTS support change must whitelist it in "
                "that same edit)"
            )
        else:
            errors.append(
                f"{path}: {where} unknown attribute {key!r} "
                "(Android's parser silently ignores unknown attributes; "
                "the gate fail-closes on schema drift)"
            )
    for required_attr in sorted(required):
        if element.get(required_attr) is None:
            errors.append(
                f"{path}: {where} missing required attribute {required_attr} "
                "(Android's parser throws at runtime without it)"
            )
    # Parse-time VALUE semantics (see _VALUE_CHECKS): one landing point for
    # every (tag, attr) family, applied wherever this helper already runs -
    # trust-anchors, pin-set, config entries - so source and artifact faces
    # cannot drift apart. ``attr=None`` predicates read the element's own TEXT
    # (the <pin> payload).
    for (tag, attr), check in _VALUE_CHECKS.items():
        if tag != element.tag:
            continue
        value: str | None = element.text if attr is None else element.get(attr)
        detail = check(value)
        if detail is not None:
            errors.append(f"{path}: {where} {detail}")
            # Mirror parsePin's THROW-AT-FIRST-FAILURE chain: once one check
            # of this element failed, the runtime never reached the next step
            # (a refused pin BODY is never decoded after the digest
            # algorithm was already refused). The `pin` family is the only
            # one with more than one check, so stopping here reproduces the
            # runtime's single-cause report.
            break


def _validate_trust_anchors(
    path: str, element: ET.Element, errors: list[str]
) -> None:
    _attr_errors(path, "<trust-anchors>", element, errors)
    for child in element:
        if child.tag not in _ALLOWED_CHILDREN["trust-anchors"]:
            errors.append(
                f"{path}: <trust-anchors> unknown element <{child.tag}> "
                "(runtime skips unknown tags; the gate fail-closes on schema drift)"
            )
            continue
        _attr_errors(path, f"<trust-anchors>/<{child.tag}>", child, errors)


def _validate_pin_set(path: str, pin_set: ET.Element, errors: list[str]) -> None:
    _attr_errors(path, "<pin-set>", pin_set, errors)
    for child in pin_set:
        if child.tag not in _ALLOWED_CHILDREN["pin-set"]:
            errors.append(
                f"{path}: <pin-set> unknown element <{child.tag}> "
                "(runtime skips unknown tags; the gate fail-closes on schema drift)"
            )
            continue
        _attr_errors(path, f"<pin-set>/<{child.tag}>", child, errors)


def _java_trim(value: str) -> str:
    """Java ``String#trim`` semantics, implemented char-wise.

    Java drops only leading/trailing characters with ``val[st] <= ' '`` (i.e.
    U+0020); Python's ``str.strip()`` removes the entire Unicode whitespace
    set (U+0085, U+00A0 NBSP, U+3000 IDEOGRAPHIC SPACE, ...), so it would trim
    MORE than the runtime. Domain normalization must be byte-identical to
    AOSP ``XmlConfigSource#parseDomain`` (``getText().trim().
    toLowerCase(Locale.US)``) or the gate's allowlist compares against a
    different string than the runtime stored.
    """
    start = 0
    end = len(value)
    while start < end and value[start] <= " ":
        start += 1
    while start < end and value[end - 1] <= " ":
        end -= 1
    return value[start:end]


def _normalize_domain(element: ET.Element) -> str:
    # Mirrors runtime parseDomain: getText().trim().toLowerCase(Locale.US),
    # with Java String#trim semantics (_java_trim), NOT Python str.strip().
    return _java_trim(element.text or "").lower()


def _validate_domain(
    path: str, domain: ET.Element, seen_domains: set[str], errors: list[str]
) -> tuple[str | None, str | None]:
    """Validate one <domain>; return (normalized name, includeSubdomains)."""
    _attr_errors(path, "<domain>", domain, errors)
    for child in domain:
        errors.append(
            f"{path}: <domain> unknown element <{child.tag}> "
            "(a domain carries only its name text; runtime skips unknown tags, "
            "the gate fail-closes on schema drift)"
        )
    name = _normalize_domain(domain)
    if not name:
        errors.append(
            f"{path}: <domain> element missing its name (Android throws \"Domain name missing\")"
        )
    elif name in seen_domains:
        errors.append(
            f"{path}: <domain>{name}</domain> duplicated "
            "(Android throws \"has already been specified\")"
        )
        seen_domains.add(name)
    else:
        seen_domains.add(name)
    include_subdomains = domain.get("includeSubdomains")
    return (name or None, include_subdomains)


def _validate_config_entry(
    path: str,
    element: ET.Element,
    mode: str,
    allowed_children: frozenset[str],
    allow_domains: bool,
    seen_domains: set[str],
    errors: list[str],
    parent_effective_cleartext: str | None = None,
    depth: int = 0,
) -> str | None:
    """Validate a base/domain/debug-overrides config entry.

    Returns the entry's OWN cleartextTrafficPermitted value ("true"/"false")
    when it parses; invalid values are reported and degrade to None so the
    policy checks operate only on runtime-meaningful values.

    AOSP semantics pinned here (frameworks/base
    ``android/security/net/config/XmlConfigSource.java``):
    ``parseConfigEntry`` calls ``builder.setParent(parentBuilder)`` BEFORE the
    element loop and recurses into a nested ``<domain-config>`` with the OUTER
    domain-config's builder as ``parentBuilder``; the attribute is stored only
    "when present, config inheritence will handle the rest"; and
    ``NetworkSecurityConfig.Builder#build()`` resolves unset values through
    ``getEffectiveCleartextTrafficPermitted()``, which walks the parent chain
    recursively, while the final parse pass re-parents any top-level builder
    lacking a parent to the base-config builder. ``parent_effective_cleartext``
    carries that effective value down the entry chain, and the policy rules
    below act on the EFFECTIVE value (explicit own attr ?? inherited parent
    value): a nested entry inheriting "true" must fail exactly like an
    explicit "true" - a runtime probe reproduced cleartext being enabled for
    such an inner entry, so a gate that skips attribute-less entries would
    pass a real bypass.
    """
    where = f"<{element.tag}>"
    _attr_errors(path, where, element, errors)
    cleartext = element.get("cleartextTrafficPermitted")
    if cleartext not in ("true", "false"):
        cleartext = None
    # Effective cleartext = explicit own attribute ?? inherited parent value
    # (AOSP Builder#getEffectiveCleartextTrafficPermitted recursion). An own
    # "false" overrides the parent and legally narrows.
    effective = cleartext if cleartext is not None else parent_effective_cleartext

    domain_entries: list[tuple[str | None, str | None]] = []
    trust_anchor_count = 0
    pin_set_count = 0
    for child in element:
        if child.tag not in allowed_children:
            if element.tag == "debug-overrides" and child.tag == "certificateTransparency":
                # Not a silent skip: the runtime EXPLICITLY throws for this
                # parent/child pair ("certificateTransparency not allowed in
                # debug-overrides", XmlConfigSource.parseConfigEntry). The
                # message mirrors the throw instead of the generic
                # unknown-tag skip wording (accuracy of the rationale).
                errors.append(
                    f"{path}: {where} <certificateTransparency> is refused "
                    "(Android throws \"certificateTransparency not allowed "
                    "in debug-overrides\" at parse time, not a runtime-"
                    "skipped unknown tag)"
                )
                continue
            errors.append(
                f"{path}: {where} unknown element <{child.tag}> "
                "(runtime skips unknown tags; the gate fail-closes on schema drift)"
            )
            continue
        if child.tag == "domain":
            domain_entries.append(_validate_domain(path, child, seen_domains, errors))
        elif child.tag == "trust-anchors":
            trust_anchor_count += 1
            _validate_trust_anchors(path, child, errors)
        elif child.tag == "pin-set":
            pin_set_count += 1
            _validate_pin_set(path, child, errors)
        elif child.tag == "certificateTransparency":
            _attr_errors(path, "/".join((where, "<certificateTransparency>")), child, errors)
        elif child.tag == "domain-config":
            # Nested domain-config (runtime allows it only inside domain-config;
            # reachability is already guaranteed by allowed_children). AOSP
            # parseConfigEntry recurses with THIS builder as the child's
            # parent, so the child inherits our effective cleartext value.
            # The recursion is bounded by _MAX_NS_DOMAIN_NEST_DEPTH: a
            # pathological suffix of nested configs surfaces as a clean
            # structured FAIL, not an uncaught RecursionError traceback.
            if depth >= _MAX_NS_DOMAIN_NEST_DEPTH:
                errors.append(
                    f"{path}: {where} nested <domain-config> exceeds the maximum "
                    f"domain-config nesting depth {_MAX_NS_DOMAIN_NEST_DEPTH} "
                    "(the walk is bounded so pathological nesting surfaces as "
                    "a clean structured FAIL, never an uncaught RecursionError "
                    "traceback)"
                )
                continue
            _validate_config_entry(
                path,
                child,
                mode,
                allowed_children,
                allow_domains=True,
                seen_domains=seen_domains,
                errors=errors,
                parent_effective_cleartext=effective,
                depth=depth + 1,
            )
    if trust_anchor_count > 1:
        errors.append(
            f"{path}: {where} has {trust_anchor_count} <trust-anchors> elements "
            "(Android throws \"Multiple trust-anchor elements not allowed\")"
        )
    if pin_set_count > 1:
        errors.append(
            f"{path}: {where} has {pin_set_count} <pin-set> elements "
            "(Android throws \"Multiple pin-set elements not allowed\")"
        )
    if element.tag == "domain-config" and not domain_entries:
        errors.append(
            f"{path}: {where} contains no <domain> element "
            "(Android throws \"No domain elements in domain-config\")"
        )

    # Policy rules act on the EFFECTIVE value: an inherited "true" obeys the
    # same rules as an explicit "true" (see the docstring for the AOSP parent
    # chain); an own "false" always overrides the parent and narrows legally.
    if effective == "true":
        if not allow_domains:
            errors.append(
                f"{path}: {where} sets cleartextTrafficPermitted=\"true\" - "
                "only a debug-mode loopback <domain-config> may enable cleartext"
            )
        elif mode == "release":
            errors.append(
                f"{path}: {where} sets cleartextTrafficPermitted=\"true\" - "
                "release pin denies cleartext on every element"
            )
        else:
            for name, include_subdomains in domain_entries:
                if name is None or name not in DEBUG_CLEARTEXT_DOMAINS:
                    errors.append(
                        f"{path}: {where} sets cleartextTrafficPermitted=\"true\" for domain "
                        f"{name!r} outside the debug loopback allowlist "
                        "(localhost / 127.0.0.1 / 10.0.2.2)"
                    )
                elif include_subdomains != "false":
                    errors.append(
                        f"{path}: {where} <domain>{name}</domain> must set includeSubdomains=\"false\" "
                        f"when cleartextTrafficPermitted=\"true\" is claimed "
                        f"(got {include_subdomains!r})"
                    )
    return cleartext


def validate_tree(root: ET.Element, mode: str, path: str) -> list[str]:
    """Structured NSC policy check on an already-parsed tree.

    Used by the file loader below AND by the artifact-level scanner
    (``apk_policy_inspect.py``), which rebuilds an ElementTree from the
    ``aapt dump xmltree`` output of the compiled resource - both surfaces run
    the exact same rules so a compiled-XML escape cannot differ from source.
    """
    if mode not in MODES:
        raise ValueError(f"unknown mode {mode!r} (expected one of {MODES})")
    if root.tag != NSC_ROOT:
        return [f"{path}: expected root <{NSC_ROOT}>, found <{root.tag}>"]

    errors: list[str] = []
    for key in root.keys():
        if _is_tools_attribute(key):
            continue
        errors.append(
            f"{path}: unexpected root attribute {key!r} "
            "(Android's parser ignores it; the gate fail-closes on schema drift)"
        )

    seen_domains: set[str] = set()
    seen_base = False
    seen_debug = False
    base_cleartext: str | None = None
    for child in root:
        tag = child.tag
        if tag == "base-config":
            if seen_base:
                errors.append(
                    f"{path}: only one <base-config> is allowed "
                    "(Android throws \"Only one base-config allowed\")"
                )
            seen_base = True
            # AOSP roots the base-config builder at the platform default
            # (NetworkSecurityConfig.getDefaultBuilder), whose cleartext value
            # is targetSdk-dependent - the gate deliberately never trusts it.
            # The structural pin below REQUIRES an explicit "false" here, so
            # no inherited value can matter: pass no parent value down.
            value = _validate_config_entry(
                path, child, mode, frozenset(_BASE_CHILDREN), allow_domains=False,
                seen_domains=seen_domains, errors=errors,
            )
            if base_cleartext is None:
                base_cleartext = value
        elif tag == "domain-config":
            # AOSP couples every top-level domain-config to the base builder
            # (the final parse pass comments "Set the parent of configs that
            # do not have a parent to the base-config ... if the base-config
            # comes after a domain-config in the file"), and this gate's
            # structural pin anchors base-config at an explicit "false", so
            # the inherited chain value a top-level entry can ever observe is
            # "false" - semantics that are compatible with the release
            # deny-all pin (an own "true" below is handled by its rules).
            _validate_config_entry(
                path, child, mode, frozenset(_DOMAIN_CHILDREN), allow_domains=True,
                seen_domains=seen_domains, errors=errors,
                parent_effective_cleartext="false",
            )
        elif tag == "debug-overrides":
            if seen_debug:
                errors.append(
                    f"{path}: only one <debug-overrides> is allowed "
                    "(Android throws \"Only one debug-overrides allowed\")"
                )
            seen_debug = True
            # AOSP parses debug-overrides with a null parent and merges only
            # its certificate refs into other builders afterwards - its
            # cleartext value is never observable at runtime, so there is
            # nothing to inherit here. The documented fail-closed stance
            # stands: an explicit "true" is refused in BOTH modes (the gate
            # does not bet on a debuggable-only application).
            _validate_config_entry(
                path, child, mode, frozenset(_DEBUG_CHILDREN), allow_domains=False,
                seen_domains=seen_domains, errors=errors,
            )
        else:
            errors.append(
                f"{path}: unknown element <{tag}> on root "
                "(Android silently skips unknown tags; the gate fail-closes on schema drift)"
            )
    if not seen_base:
        errors.append(
            f"{path}: missing <base-config> - the deny-all pin must exist "
            "structurally (a comment, or nothing, never satisfies it)"
        )
    elif base_cleartext != "false":
        errors.append(
            f"{path}: <base-config> must set cleartextTrafficPermitted=\"false\" explicitly"
        )
    return errors


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
      the very attributes this gate pins (measured:
      ``<!ATTLIST base-config cleartextTrafficPermitted CDATA "false">``
      injects the deny-all pin's value into the parsed Element without the
      source carrying it). DOCTYPE declarations are refused outright at this
      SOURCE surface; the compiled artifact carries none (aapt dump xmltree
      emits elements/attributes only), so the compiled bytes' authority stems
      from the artifact face regardless;
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


def validate(path: str, mode: str) -> list[str]:
    """Return human-readable violations (empty when valid).

    Both arguments are REQUIRED: the file↔root binding is structural and the
    policy mode must be named by every call surface, so an accidental
    positionally-shifted call cannot validate with the wrong rule set.
    """
    if mode not in MODES:
        raise ValueError(f"unknown mode {mode!r} (expected one of {MODES})")
    root, load_errors = _read_source_document(path)
    if root is None:
        return load_errors
    return validate_tree(root, mode, path)


def _load_sibling_module(name: str) -> types.ModuleType:
    pkg = __name__.rpartition(".")[0]
    scoped_name = f"{pkg}.{name}" if pkg else name
    existing = sys.modules.get(scoped_name) or sys.modules.get(name)
    if existing is not None:
        return existing
    path = Path(__file__).resolve().with_name(f"{name}.py")
    if not path.is_file():
        raise FileNotFoundError(f"cannot find sibling module: {path}")
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load sibling module: {name} from {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    if scoped_name != name:
        sys.modules[scoped_name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(name, None)
        if scoped_name != name:
            sys.modules.pop(scoped_name, None)
        raise
    return module


def _import_backup_rules_check() -> types.ModuleType:
    """Load the sibling ``backup_rules_check`` by file path.

    Adding ``scripts/`` to ``sys.path`` would let a checked-in stdlib shadow
    module change the delegated marker semantics. Load the exact file instead.
    """
    return _load_sibling_module("backup_rules_check")


def validate_manifest(path: str, require_both: bool = False) -> list[str]:
    """Bind ``android:networkSecurityConfig`` on a manifest to the pinned resource.

    Delegates to ``backup_rules_check.validate_manifest`` with the NSC attr as
    the expected binding set, so the Motorola merger-marker semantics
    (``tools:remove`` / ``tools:node="replace"/"remove"`` on <application>) are
    implemented exactly once across both gates. ``require_both`` (the MAIN
    manifest) fails closed on an ABSENT attribute; overlays fail only on a
    present-but-different value (omitting merges main's value, which is legal).
    """
    backup_rules_check = _import_backup_rules_check()
    return backup_rules_check.validate_manifest(
        path,
        require_both=require_both,
        expected_attrs=(("networkSecurityConfig", EXPECTED_MANIFEST_RESOURCE),),
        binding_kind="networkSecurityConfig",
    )


def main(argv: list[str]) -> int:
    args = argv[1:]
    mode: str | None = None
    check_manifest = False
    require_both = False
    paths: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--mode":
            if i + 1 >= len(args):
                print("FAIL: --mode requires a value (release|debug)", file=sys.stderr)
                return 2
            if mode is not None:
                print("FAIL: duplicate --mode", file=sys.stderr)
                return 2
            mode = args[i + 1]
            if mode not in MODES:
                print(
                    f"FAIL: unknown --mode {mode!r} (expected release or debug)",
                    file=sys.stderr,
                )
                return 2
            i += 2
        elif args[i] == "--check-manifest":
            check_manifest = True
            i += 1
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
        print(
            "usage: nsc_check.py (--mode (release|debug) <network_security_config.xml> [...] | "
            "--check-manifest [--require-both] <AndroidManifest.xml> [...])",
            file=sys.stderr,
        )
        return 2
    if mode is None and not check_manifest:
        # Fail closed: an NSC path without a policy hint cannot prove which
        # rule set applies (the single--"false"-grep false-PASS was the
        # reviewer's original repro; callers must state the mode).
        print(
            "usage: nsc_check.py --mode (release|debug) <network_security_config.xml> [...] | "
            "--check-manifest [--require-both] <AndroidManifest.xml> [...]",
            file=sys.stderr,
        )
        return 2
    if mode is not None and check_manifest:
        print("FAIL: --check-manifest and --mode are mutually exclusive", file=sys.stderr)
        return 2
    if require_both and not check_manifest:
        print("FAIL: --require-both requires --check-manifest", file=sys.stderr)
        return 2
    errors: list[str] = []
    for path in paths:
        if check_manifest:
            errors.extend(validate_manifest(path, require_both))
        else:
            errors.extend(validate(path, mode))
    if errors:
        for line in errors:
            print(f"FAIL: {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
