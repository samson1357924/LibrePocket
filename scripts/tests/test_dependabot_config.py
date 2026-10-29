#!/usr/bin/env python3
"""Check the intended Dependabot policy and docs dependency declarations.

Run after installing scripts/requirements-docs.txt (PyYAML is required).
These static checks do not verify artifact provenance or workflow execution.
"""

import copy
import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


def validate_config(config: object) -> None:
    if not isinstance(config, dict) or config.get("version") != 2:
        raise ValueError("Dependabot config must use version 2")
    if set(config) != {"version", "updates"}:
        raise ValueError("Unexpected top-level Dependabot settings")
    updates = config.get("updates")
    if not isinstance(updates, list):
        raise ValueError("Dependabot updates must be a list")
    expected = {
        ("github-actions", "/"): "chore(ci)",
        ("gradle", "/"): "chore(deps)",
        ("npm", "/github_bot"): "chore(bot)",
        ("pip", "/scripts"): "chore(deps)",
    }
    if len(updates) != len(expected) or any(not isinstance(entry, dict) for entry in updates):
        raise ValueError("Dependabot updates must contain exactly the expected entries")
    for (ecosystem, directory), prefix in expected.items():
        matches = [entry for entry in updates if isinstance(entry, dict)
                   and entry.get("package-ecosystem") == ecosystem
                   and entry.get("directory") == directory]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one {ecosystem} update for {directory}")
        entry = matches[0]
        if entry != {
            "package-ecosystem": ecosystem,
            "directory": directory,
            "schedule": {"interval": "weekly"},
            "open-pull-requests-limit": 10,
            "commit-message": {"prefix": prefix},
        }:
            raise ValueError(f"Unexpected {ecosystem} update policy")


def validate_docs_pin(requirements: str, workflow: str) -> None:
    # Accept only one exact requirement and its attached SHA-256 options. This
    # validates syntax, not whether hashes correspond to upstream artifacts.
    lines = [line.strip() for line in requirements.splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    if not lines:
        raise ValueError("Missing PyYAML requirement")
    match = re.fullmatch(r"PyYAML==([0-9]+(?:\.[0-9]+)+)\s*\\", lines[0], re.I)
    if not match or len(lines) < 2:
        raise ValueError("Expected exact PyYAML pin with hashes")
    for index, line in enumerate(lines[1:]):
        suffix = r"\s*\\" if index < len(lines) - 2 else ""
        if not re.fullmatch(r"--hash=sha256:[0-9a-f]{64}" + suffix, line):
            raise ValueError("Invalid PyYAML hash line")

    parsed = yaml.safe_load(workflow)
    try:
        steps = parsed["jobs"]["docs-guard"]["steps"]
        installs = [step for step in steps if step.get("name") == "Install pinned PyYAML"]
        run = installs[0]["run"] if len(installs) == 1 else ""
    except (KeyError, TypeError, AttributeError):
        raise ValueError("Missing docs dependency install step") from None
    # Match whole command lines, not comments, other jobs, or string fragments.
    commands = {line.strip() for line in run.splitlines()}
    install = "python3 -m pip install --user --disable-pip-version-check --ignore-installed --require-hashes -r scripts/requirements-docs.txt"
    assertion = f'''python3 -c 'import yaml; assert yaml.__version__ == "{match.group(1)}", yaml.__version__' '''.strip()
    if install not in commands or assertion not in commands:
        raise ValueError("Docs dependency install or version assertion differs")


class DependabotConfigTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text())
        self.requirements = (ROOT / "scripts/requirements-docs.txt").read_text()
        self.workflow = (ROOT / ".github/workflows/pr-check.yml").read_text()

    def test_configured_ecosystems(self) -> None:
        validate_config(self.config)

    def test_missing_or_wrong_pip_configuration_is_rejected(self) -> None:
        for field, value in (("directory", "/"), ("schedule", {"interval": "monthly"})):
            with self.subTest(field=field):
                config = copy.deepcopy(self.config)
                pip = next(entry for entry in config["updates"] if entry["package-ecosystem"] == "pip")
                pip[field] = value
                with self.assertRaises(ValueError):
                    validate_config(config)
        self.config["updates"].append(copy.deepcopy(self.config["updates"][-1]))
        with self.assertRaises(ValueError):
            validate_config(self.config)

    def test_extra_update_settings_are_rejected_for_every_expected_ecosystem(self) -> None:
        for index in range(len(self.config["updates"])):
            for field, value in (("ignore", [{"dependency-name": "*"}]),
                                 ("target-branch", "other"),
                                 ("allow", [{"dependency-name": "only-one"}]),
                                 ("exclude-paths", ["*"])):
                with self.subTest(index=index, field=field):
                    config = copy.deepcopy(self.config)
                    config["updates"][index][field] = value
                    with self.assertRaises(ValueError):
                        validate_config(config)

    def test_extra_and_non_dict_updates_are_rejected(self) -> None:
        for entry in ({"package-ecosystem": "docker", "directory": "/"}, None, "invalid"):
            with self.subTest(entry=entry):
                config = copy.deepcopy(self.config)
                config["updates"].append(entry)
                with self.assertRaises(ValueError):
                    validate_config(config)
        config = copy.deepcopy(self.config)
        config["updates"][0] = None
        with self.assertRaises(ValueError):
            validate_config(config)

    def test_docs_requirement_and_workflow_pin_agree(self) -> None:
        validate_docs_pin(self.requirements, self.workflow)

    def test_pin_checks_reject_misleading_text(self) -> None:
        install = "python3 -m pip install --user --disable-pip-version-check --ignore-installed --require-hashes -r scripts/requirements-docs.txt"
        match = re.search(r"(?im)^\s*PyYAML==([0-9]+(?:\.[0-9]+)+)\s*\\", self.requirements)
        self.assertIsNotNone(match, "Missing PyYAML pin")
        version = match.group(1)
        assertion = f'''python3 -c 'import yaml; assert yaml.__version__ == "{version}", yaml.__version__' '''.strip()
        for old, new in ((install, "# " + install), (assertion, "# " + assertion),
                         (assertion, assertion.replace(f'"{version}"', f'"{version}.1"'))):
            with self.subTest(old=old, new=new):
                self.assertIn(old, self.workflow)
                modified = self.workflow.replace(old, new)
                self.assertNotEqual(modified, self.workflow)
                with self.assertRaises(ValueError):
                    validate_docs_pin(self.requirements, modified)
        for requirements in (f"PyYAML=={version}\n# --hash=sha256:" + "a" * 64,
                             self.requirements.replace("--hash=sha256:", "--hash=sha256:x", 1),
                             self.requirements + "\nother==1.0 --hash=sha256:" + "a" * 64):
            with self.subTest(requirements=requirements), self.assertRaises(ValueError):
                validate_docs_pin(requirements, self.workflow)


if __name__ == "__main__":
    unittest.main()
