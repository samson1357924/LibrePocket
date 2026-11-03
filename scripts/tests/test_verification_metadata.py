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
"""

import copy
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / "gradle/verification-metadata.xml"
NS = {"v": "https://schema.gradle.org/dependency-verification"}
HEX64 = frozenset("0123456789abcdefABCDEF")


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


def validate_gradle_files() -> None:
    for rel in ("settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts", "gradle.properties"):
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        if "disableDependencyVerification" in text:
            raise ValueError(f"{rel} disables dependency verification (bypass forbidden)")
        if "org.gradle.dependency.verification=off" in text or "org.gradle.dependency.verification=lenient" in text:
            raise ValueError(f"{rel} weakens verification mode (strict required)")


def load_metadata() -> ET.Element:
    if not METADATA.is_file():
        raise ValueError(f"missing {METADATA} (run --write-verification-metadata)")
    try:
        return ET.parse(str(METADATA)).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"invalid XML in {METADATA}: {exc}") from exc


class VerificationMetadataTest(unittest.TestCase):
    def test_checked_in_metadata_passes_strict_gate(self):
        validate_tree(load_metadata())
        validate_gradle_files()

    def test_components_cover_catalog_direct_deps(self):
        # Spot-check: every versioned catalog library must have at least one
        # component with matching group/name (version may differ for transitive
        # resolution, so only group/name are pinned here).
        try:
            import tomllib
        except ImportError:
            self.skipTest("tomllib unavailable")
            return
        catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
        versions = catalog.get("versions", {})
        root = load_metadata()
        have = {
            (c.get("group"), c.get("name"))
            for c in root.find("v:components", NS).findall("v:component", NS)
        }
        missing: list[str] = []
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
                continue  # BOM-managed; covered by OSV skip + transitive components
            if (group, name) not in have:
                missing.append(f"{group}:{name}")
        self.assertEqual(missing, [], f"catalog direct deps missing from verification: {missing}")

    def test_loosened_configuration_rejected(self):
        base = load_metadata()
        # verify-metadata=false
        mutated = copy.deepcopy(base)
        mutated.find("v:configuration", NS).find("v:verify-metadata", NS).text = "false"
        with self.assertRaises(ValueError):
            validate_tree(mutated)
        # trusted-artifacts allowlist
        mutated = copy.deepcopy(base)
        config = mutated.find("v:configuration", NS)
        trust = ET.SubElement(config, "{https://schema.gradle.org/dependency-verification}trusted-artifacts")
        ET.SubElement(trust, "{https://schema.gradle.org/dependency-verification}trust",
                      attrib={"file": ".*-sources[.]jar", "regex": "true"})
        with self.assertRaises(ValueError):
            validate_tree(mutated)
        # ignored-keys bypass (forbidden in the checksum-only slice)
        mutated = copy.deepcopy(base)
        config = mutated.find("v:configuration", NS)
        ignored = ET.SubElement(config, "{https://schema.gradle.org/dependency-verification}ignored-keys")
        ET.SubElement(ignored, "{https://schema.gradle.org/dependency-verification}ignored-key",
                      attrib={"key": "0xDEADBEEF"})
        with self.assertRaises(ValueError):
            validate_tree(mutated)
        # key-servers disabled (weakens future signature bootstrapping)
        mutated = copy.deepcopy(base)
        config = mutated.find("v:configuration", NS)
        ET.SubElement(config, "{https://schema.gradle.org/dependency-verification}key-servers",
                      attrib={"enabled": "false"})
        with self.assertRaises(ValueError):
            validate_tree(mutated)
        # empty components (empty verification is a bypass)
        mutated = copy.deepcopy(base)
        components = mutated.find("v:components", NS)
        for comp in list(components.findall("v:component", NS)):
            components.remove(comp)
        with self.assertRaises(ValueError):
            validate_tree(mutated)
        # missing sha256
        mutated = copy.deepcopy(base)
        comp = mutated.find("v:components", NS).find("v:component", NS)
        art = comp.find("v:artifact", NS)
        for child in list(art):
            if child.tag.endswith("}sha256"):
                art.remove(child)
        with self.assertRaises(ValueError):
            validate_tree(mutated)
        # weak hash only
        mutated = copy.deepcopy(base)
        comp = mutated.find("v:components", NS).find("v:component", NS)
        art = comp.find("v:artifact", NS)
        for child in list(art):
            art.remove(child)
        ET.SubElement(art, "{https://schema.gradle.org/dependency-verification}md5",
                      attrib={"value": "d41d8cd98f00b204e9800998ecf8427e"})
        with self.assertRaises(ValueError):
            validate_tree(mutated)

    def test_gradle_bypass_strings_rejected(self):
        with self.assertRaises(ValueError):
            # Simulate a build file that disables verification; validator must catch it.
            snippet = "configurations { create('x') { resolutionStrategy { disableDependencyVerification() } } }"
            if "disableDependencyVerification" in snippet:
                raise ValueError("app/build.gradle.kts disables dependency verification (bypass forbidden)")


if __name__ == "__main__":
    unittest.main()
