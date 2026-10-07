# Security Policy

**Status:** Current disclosure of the repository's reporting capability as checked on 2026-10-07 at `b3d8a818f37f8455a254a0670b512286e4deb750`.

- **Scope:** This repository and its Android application. The project is alpha and is not a production security guarantee.
- **Owner role:** Project maintainers; no dedicated security team or private contact is published here.
- **Source of truth:** Repository configuration and this policy. A reporting channel is not configured unless this file is updated to identify one.
- **Update trigger:** Configure or change a genuinely private reporting channel, supported versions, or handling process.

## Reporting a vulnerability

GitHub private vulnerability reporting is currently disabled. There is no published private security email, team, or response-time commitment. A public issue is **not** a confidential reporting channel.

Do not post API keys, access tokens, private transcripts, personal data, or a complete exploit that exposes other users. If a finding requires private details, do not put those details in a public issue or pull request. The project has not yet provided a confidential channel; maintainers must configure one before claiming private reporting is available. This policy makes no promise of acknowledgment or remediation timing.

For non-sensitive defects, use the repository's ordinary public issue tracker. Include the affected commit or release identifier, flavor, Android API level, expected and observed behavior, and a minimal reproduction using synthetic data. Never include real credentials or private user content.

## Safe testing boundaries

Use a local checkout, fake endpoints, synthetic transcripts, and test-only keys. Do not test against other people's devices or accounts, production credentials, paid services, or signing secrets. Do not upload private transcripts when reproducing a defect.

## Release and support status

The application is alpha. No supported-version matrix or security-response SLA is established. The automated release workflow does not currently verify final artifact identity and signing properties as a fail-closed security gate; see [Release](docs/RELEASE.md).
