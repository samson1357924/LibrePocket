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
  * duplicates the runtime throws on at parse time are FAILs here too
    ("Only one base-config allowed", "Only one debug-overrides allowed",
    "Multiple trust-anchor elements not allowed", duplicate <domain> text
    ("has already been specified")) and ``<domain-config>`` without a
    ``<domain>`` child ("No domain elements in domain-config");
  * runtime visibility limits made explicit: unknown elements are SILENTLY
    skipped by the runtime (``XmlUtils.skipCurrentTag``) and unknown attributes
    are SILENTLY ignored (the attr loop in ``parseConfigEntry`` has no
    else-throw). The gate deliberately FAILS CLOSED on both shapes instead of
    mirroring the silent skip: at source level a stray/typo'd attribute (e.g.
    ``includesubdomains``) would silently flip semantics or invite shape-drift
    escapes, so the stricter-than-runtime refusal is pinned here with this
    rationale rather than hidden;
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

import sys
import xml.etree.ElementTree as ET

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
            _validate_config_entry(
                path,
                child,
                mode,
                allowed_children,
                allow_domains=True,
                seen_domains=seen_domains,
                errors=errors,
                parent_effective_cleartext=effective,
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


def validate(path: str, mode: str) -> list[str]:
    """Return human-readable violations (empty when valid).

    Both arguments are REQUIRED: the file↔root binding is structural and the
    policy mode must be named by every call surface, so an accidental
    positionally-shifted call cannot validate with the wrong rule set.
    """
    if mode not in MODES:
        raise ValueError(f"unknown mode {mode!r} (expected one of {MODES})")
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return [f"{path}: invalid XML: {exc}"]
    except OSError as exc:
        return [f"{path}: unreadable: {exc}"]
    return validate_tree(root, mode, path)


def _import_backup_rules_check():
    """Import the sibling ``backup_rules_check`` regardless of the caller's sys.path.

    The shell gate runs this file by absolute path (sys.path[0] = scripts/),
    while tests import it as ``scripts.nsc_check`` with the repo root on
    sys.path; inserting this file's own directory keeps the marker-semantics
    delegation working on both surfaces.
    """
    from pathlib import Path

    scripts_dir = str(Path(__file__).resolve().parent)
    if scripts_dir not in sys.path:
        sys.path.insert(0, scripts_dir)
    import backup_rules_check

    return backup_rules_check


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
