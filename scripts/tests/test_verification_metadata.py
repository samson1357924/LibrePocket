#!/usr/bin/env python3
"""Static regression tests for gradle/verification-metadata.xml (stdlib only).

The validator under test lives in scripts/verify_verification_metadata.py;
this file only imports it and holds the test cases plus mutation helpers.

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

import scripts.verify_verification_metadata as V
from scripts import osv_scan
from scripts.verify_verification_metadata import (
    NS,
    ROOT,
    VNS,
    load_metadata,
    validate_gradle_files,
    validate_tree,
)


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
        # (one warning per skipped entry) rather than silently dropped.
        # GAV pins come from scripts/osv_scan.parse_catalog (single parser,
        # no duplicated TOML logic here): a malformed versioned entry fails
        # the test via RuntimeError, exactly as the monitor fails closed.
        try:
            import tomllib  # noqa: F401
        except ImportError:
            self.skipTest("tomllib unavailable")
        packages, warnings, _skipped = osv_scan.parse_catalog(
            ROOT / "gradle/libs.versions.toml"
        )
        root = load_metadata()
        have = {
            (c.get("group"), c.get("name"), c.get("version"))
            for c in root.find("v:components", NS).findall("v:component", NS)
        }
        missing: list[str] = []
        for pkg in packages:
            if (pkg["group"], pkg["name"], pkg["version"]) not in have:
                missing.append(f"{pkg['group']}:{pkg['name']}:{pkg['version']}")
        skipped: list[str] = list(warnings)
        self.assertEqual(missing, [], f"catalog direct deps missing from verification: {missing}")
        # The BOM-managed skip set is part of the gate contract, pinned as
        # an exact allowlist: any drift (an 8th unversioned entry, or a
        # newly versioned compose alias) forces a conscious review of
        # EXPECTED_UNVERSIONED_KEYS in scripts/osv_scan.py.
        skipped_keys = {
            w.split("skip ", 1)[1].split(":")[0].strip() for w in skipped
        }
        self.assertEqual(
            skipped_keys, set(osv_scan.EXPECTED_UNVERSIONED_KEYS),
            f"BOM skip set drifted: got {sorted(skipped_keys)}, "
            f"want {sorted(osv_scan.EXPECTED_UNVERSIONED_KEYS)}",
        )
        self.assertEqual(
            len(skipped), len(osv_scan.EXPECTED_UNVERSIONED_KEYS),
            "one warning per skipped entry",
        )

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
        with tempfile.TemporaryDirectory() as td:
            bad = Path(td) / "verification-metadata.xml"
            bad.write_text("<verification-metadata><unclosed", encoding="utf-8")
            old, V.METADATA = V.METADATA, bad
            try:
                with self.assertRaises(ValueError):
                    load_metadata()
            finally:
                V.METADATA = old

    def test_gradle_properties_whitespace_variants_rejected(self):
        cases = {
            "key=off": "org.gradle.dependency.verification=off\n",
            "key = off": "org.gradle.dependency.verification = off\n",
            "key tab = tab lenient": "org.gradle.dependency.verification\t=\tlenient\n",
            "key:off": "org.gradle.dependency.verification:off\n",
            "key : lenient": "org.gradle.dependency.verification : lenient\n",
            "key = off inline comment": "org.gradle.dependency.verification = off # comment\n",
            "unicode-obfuscated key": "\\u006frg.gradle.dependency.verification=off\n",
        }
        for name, content in cases.items():
            with self.subTest(case=name):
                with tempfile.TemporaryDirectory() as td:
                    tmp = Path(td)
                    (tmp / "gradle.properties").write_text(content, encoding="utf-8")
                    with self.assertRaises(ValueError):
                        validate_gradle_files(tmp)

    def test_gradle_properties_safe_variants_pass(self):
        cases = {
            "commented out": "# org.gradle.dependency.verification=off\n",
            "bang commented out": "! org.gradle.dependency.verification=off\n",
            "strict no space": "org.gradle.dependency.verification=strict\n",
            "strict with spaces": "org.gradle.dependency.verification = strict\n",
        }
        for name, content in cases.items():
            with self.subTest(case=name):
                with tempfile.TemporaryDirectory() as td:
                    tmp = Path(td)
                    (tmp / "gradle.properties").write_text(content, encoding="utf-8")
                    validate_gradle_files(tmp)  # must not raise

    def test_groovy_build_gradle_scanned(self):
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            (tmp / "app").mkdir()
            groovy = tmp / "app" / "build.gradle"
            groovy.write_text(
                "configurations { x { resolutionStrategy { disableDependencyVerification() } } }\n",
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                validate_gradle_files(tmp)
            groovy.write_text("// clean\n", encoding="utf-8")
            validate_gradle_files(tmp)  # must not raise

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
