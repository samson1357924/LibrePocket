# Contributing

**Status:** Current contributor workflow for this alpha repository, checked 2026-10-07 at `b3d8a818f37f8455a254a0670b512286e4deb750`.

- **Scope:** Changes to this repository, including code, tests, and documentation.
- **Owner role:** Project maintainers; individual ownership is not assigned by this document.
- **Source of truth:** The checked-in build configuration, [Testing](docs/TESTING.md), and pull-request review for the proposed change.
- **Update trigger:** Changes to branch/review expectations, build commands, security handling, or CI gates.

## Before opening a change

1. Confirm the base branch and inspect `git status`; do not overwrite another contributor's changes.
2. Keep each change focused. For product changes, add or update tests and update documentation that describes the affected current behavior.
3. Separate **Current** implementation from **Target** design and **Proposed** decisions. Do not turn a scaffold, planned test, or unverified device behavior into a current claim.
4. For security-sensitive changes, use synthetic data and local fake services. Never commit credentials, keystores, tokens, private transcripts, or signing material.

## Build and test

Use the checked-in Gradle wrapper and versions. See [Build Environment](docs/ENV.md) and [Testing](docs/TESTING.md) for commands, CI scope, and device-test limitations. Run only the checks relevant to the change, record exactly what ran and its result, and do not describe unrun checks as passing. On constrained machines, serialize Gradle/test execution and use `--max-workers=1 --no-daemon`.

## Review expectations

- Explain the behavior change, flavor impact, data flow, and known limitations in the pull request.
- Preserve flavor boundaries and default-off behavior for high-risk capabilities. Declaring or projecting a tool does not make it executable.
- A reviewer should inspect the complete diff, including generated or staged files. Do not commit local agent instructions or secrets.
- Documentation-only changes should receive a static link and claim check; they do not require a Gradle run unless the change affects build inputs.

## Security findings

See [Security Policy](SECURITY.md). Private vulnerability reporting is not currently available; public issues are not confidential. Do not publish sensitive details or credentials.
