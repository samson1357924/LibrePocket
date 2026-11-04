#!/usr/bin/env python3
"""Static regression tests for gradle/verification-metadata.xml (stdlib only).

Validates the strict checksum-only gate without running Gradle:
  * file exists and is well-formed XML with the expected root/schema;
  * <verify-metadata>true</verify-metadata> and <verify-signatures>false</verify-signatures>
    (checksum-only; signatures/keyrings are a separate future slice);
  * no global bypass: no <verify-metadata>false</verify-metadata>, no
    <trusted-artifacts> allowlist, no ignored-keys, no key-servers disabled;
  * every <component> carries at least one <sha256> with a 64-hex value and
    no weak-only (md5/sha1) entries;
  * no per-configuration bypass in Gradle files
    (disableDependencyVerification must not appear).

Negative controls mutate in-memory XML / temp Gradle snippets and assert the
validator rejects them. This test does NOT prove resolved bytes were verified;
the proof is a clean-cache Gradle build with strict verification (see docs).

shape-only: version drift is caught by Gradle builds, not by this test.
"""

import copy
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / "gradle/verification-metadata.xml"
NS = {"v": "https://schema.gradle.org/dependency-verification"}
HEX64 = frozenset("0123456789abcdefABCDEF")
VNS = "https://schema.gradle.org/dependency-verification"


def _text(element) -> str:
    return (element.text or "").strip() if element is not None else ""


def validate_tree(root: ET.Element) -> None:
    if root.tag != "{https://schema.gradle.org/dependency-verification}verification-metadata":
        raise ValueError(f"unexpected root tag: {root.tag}")
    config = root.find("v:configuration", NS)
    if config is None:
        raise ValueError("missing <configuration>")
    verify_metadata = _text(config.find("v:verify-metadata", NS))
    if verify_metadata != "true":
        raise ValueError(f"<verify-metadata> must be 'true', got {verify_metadata!r}")
    verify_signatures = _text(config.find("v:verify-signatures", NS))
    if verify_signatures != "false":
        raise ValueError(
            f"<verify-signatures> must be 'false' for the checksum-only slice, got {verify_signatures!r}"
        )
    if config.find("v:trusted-artifacts", NS) is not None:
        raise ValueError("<trusted-artifacts> allowlist is forbidden (broad trust bypass)")
    if config.find("v:ignored-keys", NS) is not None:
        raise ValueError("<ignored-keys> is forbidden in the checksum-only slice")
    key_servers = config.find("v:key-servers", NS)
    if key_servers is not None and key_servers.get("enabled") == "false":
        raise ValueError("<key-servers enabled='false'/> weakens future signature bootstrapping")
    components = root.find("v:components", NS)
    if components is None:
        raise ValueError("missing <components>")
    entries = components.findall("v:component", NS)
    if not entries:
        raise ValueError("no <component> entries (empty verification is a bypass)")
    for comp in entries:
        group, name, version = comp.get("group"), comp.get("name"), comp.get("version")
        if not group or not name or not version:
            raise ValueError(f"component missing group/name/version: {ET.tostring(comp)[:120]!r}")
        artifacts = comp.findall("v:artifact", NS)
        if not artifacts:
            raise ValueError(f"component {group}:{name}:{version} has no artifacts")
        for artifact in artifacts:
            aname = artifact.get("name")
            if not aname:
                raise ValueError(f"artifact missing name in {group}:{name}:{version}")
            sha = artifact.find("v:sha256", NS)
            if sha is None:
                raise ValueError(f"artifact {aname} missing <sha256> (weak-only or unsigned)")
            value = (sha.get("value") or "").strip()
            if len(value) != 64 or any(c not in HEX64 for c in value):
                raise ValueError(f"artifact {aname} has invalid sha256: {value!r}")
            for weak in ("md5", "sha1"):
                if artifact.find(f"v:{weak}", NS) is not None:
                    raise ValueError(f"artifact {aname} uses weak hash <{weak}> (use sha256+)")


def validate_gradle_files(root: Path = ROOT) -> None:
    # Scan Gradle scripts under root; skip generated output dirs (build/)
    # and hidden dirs so future build outputs can never false-positive.
    def _scannable(path: Path) -> bool:
        try:
            rel = path.relative_to(root)
        except ValueError:
            return True
        return not any(part == "build" or part.startswith(".") for part in rel.parts[:-1])

    targets = sorted(p for p in root.rglob("*.gradle.kts") if _scannable(p))
    props = root / "gradle.properties"
    if props.is_file():
        targets.append(props)
    for path in targets:
        if not path.is_file():
            continue
        try:
            label = str(path.relative_to(root))
        except ValueError:
            label = str(path)
        text = path.read_text(encoding="utf-8")
        if "disableDependencyVerification" in text:
            raise ValueError(f"{label} disables dependency verification (bypass forbidden)")
        if "org.gradle.dependency.verification=off" in text or "org.gradle.dependency.verification=lenient" in text:
            raise ValueError(f"{label} weakens verification mode (strict required)")


def load_metadata() -> ET.Element:
    if not METADATA.is_file():
        raise ValueError(f"missing {METADATA} (run --write-verification-metadata)")
    try:
        return ET.parse(str(METADATA)).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"invalid XML in {METADATA}: {exc}") from exc


def _first_comp_artifact(tree: ET.Element) -> tuple[ET.Element, ET.Element]:
    comp = tree.find("v:components", NS).find("v:component", NS)
    return comp, comp.find("v:artifact", NS)


def _strip_hashes(art: ET.Element) -> None:
    for child in list(art):
        art.remove(child)


class VerificationMetadataTest(unittest.TestCase):
    def test_checked_in_metadata_passes_strict_gate(self):
        validate_tree(load_metadata())
        validate_gradle_files()

    def test_components_cover_catalog_direct_deps(self):
        # shape-only: version drift is caught by Gradle builds, not by this
        # test. Every versioned catalog library must have an exact
        # (group, name, version) component hit. BOM-managed entries carry no
        # pinned version in the catalog (e.g. compose-ui via compose-bom) and
        # are skipped here; they are covered by osv_scan's WARN skip plus
        # transitive components, so they are recorded in `skipped`
        # (group/name only) rather than silently dropped.
        try:
            import tomllib
        except ImportError:
            self.skipTest("tomllib unavailable")
        catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
        versions = catalog.get("versions", {})
        root = load_metadata()
        have = {
            (c.get("group"), c.get("name"), c.get("version"))
            for c in root.find("v:components", NS).findall("v:component", NS)
        }
        missing: list[str] = []
        skipped: list[str] = []
        for key, entry in sorted(catalog.get("libraries", {}).items()):
            if not isinstance(entry, dict):
                continue
            group, name = entry.get("group"), entry.get("name")
            raw = entry.get("version")
            version = None
            if isinstance(raw, str):
                version = raw
            elif isinstance(raw, dict) and isinstance(raw.get("ref"), str):
                version = versions.get(raw["ref"])
            if not group or not name or not version:
                # BOM-managed (no pinned version): osv_scan skips these with a
                # WARN; verification still covers them transitively.
                skipped.append(f"{key} -> {group}:{name} (no pinned version, BOM-managed)")
                continue
            if (group, name, version) not in have:
                missing.append(f"{group}:{name}:{version}")
        self.assertEqual(missing, [], f"catalog direct deps missing from verification: {missing}")
        # The BOM-managed skip set is part of the gate contract: if it ever
        # becomes empty (all libs versioned) or grows unexpectedly, this
        # assertion forces a conscious review of the skip list.
        self.assertGreater(len(skipped), 0, "expected at least one BOM-managed skip")

    def test_loosened_configuration_rejected(self):
        base = load_metadata()

        def drop_verify_metadata(t):
            t.find("v:configuration", NS).find("v:verify-metadata", NS).text = "false"

        def add_trusted_artifacts(t):
            config = t.find("v:configuration", NS)
            trust = ET.SubElement(config, f"{{{VNS}}}trusted-artifacts")
            ET.SubElement(trust, f"{{{VNS}}}trust",
                          attrib={"file": ".*-sources[.]jar", "regex": "true"})

        def add_ignored_keys(t):
            config = t.find("v:configuration", NS)
            ignored = ET.SubElement(config, f"{{{VNS}}}ignored-keys")
            ET.SubElement(ignored, f"{{{VNS}}}ignored-key",
                          attrib={"key": "0xDEADBEEF"})

        def disable_key_servers(t):
            config = t.find("v:configuration", NS)
            ET.SubElement(config, f"{{{VNS}}}key-servers", attrib={"enabled": "false"})

        def empty_components(t):
            components = t.find("v:components", NS)
            for comp in list(components.findall("v:component", NS)):
                components.remove(comp)

        def drop_sha256(t):
            _comp, art = _first_comp_artifact(t)
            for child in list(art):
                if child.tag.endswith("}sha256"):
                    art.remove(child)

        def md5_only(t):
            _comp, art = _first_comp_artifact(t)
            _strip_hashes(art)
            ET.SubElement(art, f"{{{VNS}}}md5",
                          attrib={"value": "d41d8cd98f00b204e9800998ecf8427e"})

        def sha1_only(t):
            _comp, art = _first_comp_artifact(t)
            _strip_hashes(art)
            ET.SubElement(art, f"{{{VNS}}}sha1",
                          attrib={"value": "da39a3ee5e6b4b0d3255bfef95601890afd80709"})

        def sha256_plus_weak_md5(t):
            # sha256 present but a weak hash coexists: still rejected.
            _comp, art = _first_comp_artifact(t)
            ET.SubElement(art, f"{{{VNS}}}md5",
                          attrib={"value": "d41d8cd98f00b204e9800998ecf8427e"})

        def sha256_short(t):
            _comp, art = _first_comp_artifact(t)
            art.find("v:sha256", NS).set("value", "abc123")

        def sha256_nonhex(t):
            _comp, art = _first_comp_artifact(t)
            art.find("v:sha256", NS).set("value", "z" * 64)

        def component_missing_version(t):
            t.find("v:components", NS).find("v:component", NS).attrib.pop("version")

        def component_missing_group(t):
            t.find("v:components", NS).find("v:component", NS).attrib.pop("group")

        def component_missing_name(t):
            t.find("v:components", NS).find("v:component", NS).attrib.pop("name")

        def artifact_missing_name(t):
            _comp, art = _first_comp_artifact(t)
            art.attrib.pop("name")

        def drop_configuration(t):
            config = t.find("v:configuration", NS)
            t.remove(config)

        def enable_signatures(t):
            # checksum-only lock: the validator currently requires
            # <verify-signatures>false</verify-signatures>; flipping it to
            # true must be rejected until a signatures slice lands.
            t.find("v:configuration", NS).find("v:verify-signatures", NS).text = "true"

        cases = {
            "verify-metadata=false": drop_verify_metadata,
            "trusted-artifacts allowlist": add_trusted_artifacts,
            "ignored-keys bypass": add_ignored_keys,
            "key-servers disabled": disable_key_servers,
            "empty components": empty_components,
            "missing sha256": drop_sha256,
            "weak md5-only": md5_only,
            "weak sha1-only": sha1_only,
            "sha256 coexisting with weak md5": sha256_plus_weak_md5,
            "sha256 short value": sha256_short,
            "sha256 non-hex value": sha256_nonhex,
            "component missing version": component_missing_version,
            "component missing group": component_missing_group,
            "component missing name": component_missing_name,
            "artifact missing name": artifact_missing_name,
            "missing configuration": drop_configuration,
            "verify-signatures=true rejected (checksum-only lock)": enable_signatures,
        }
        for name, mutate in cases.items():
            with self.subTest(case=name):
                mutated = copy.deepcopy(base)
                mutate(mutated)
                with self.assertRaises(ValueError):
                    validate_tree(mutated)

    def test_corrupt_xml_rejected(self):
        global METADATA
        with tempfile.TemporaryDirectory() as td:
            bad = Path(td) / "verification-metadata.xml"
            bad.write_text("<verification-metadata><unclosed", encoding="utf-8")
            old, METADATA = METADATA, bad
            try:
                with self.assertRaises(ValueError):
                    load_metadata()
            finally:
                METADATA = old

    def test_gradle_bypass_strings_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            (tmp / "app").mkdir()
            gradle_file = tmp / "app" / "build.gradle.kts"
            gradle_file.write_text(
                "configurations { create('x') "
                "{ resolutionStrategy { disableDependencyVerification() } } }\n",
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                validate_gradle_files(tmp)
            gradle_file.write_text("// clean\n", encoding="utf-8")
            props = tmp / "gradle.properties"
            props.write_text("org.gradle.dependency.verification=off\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                validate_gradle_files(tmp)
            props.write_text("# clean\n", encoding="utf-8")
            validate_gradle_files(tmp)  # must not raise


if __name__ == "__main__":
    unittest.main()
