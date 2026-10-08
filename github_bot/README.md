# PocketGuard GitHub runner

## Purpose and ownership

This directory implements deterministic change tagging, three-role Android review, and publication of review results. Repository maintainers own the workflow and configuration. The workflow file is the source of truth for event triggers, permissions, action pins, and artifact retention; update this guide when those settings change.

## Modes

Run from this directory after installing the committed lockfile with `npm ci --ignore-scripts`:

- `node_modules/.bin/ts-node src/github_runner.ts --mode=tag` classifies eligible comment commands and emits deterministic label/context JSON. It does not call CPA.
- `node_modules/.bin/ts-node src/github_runner.ts --mode=review` runs the deterministic scanner, then makes one single-turn CPA request for each of the chief, Android security/policy, and Android code roles in parallel. It writes `review-output.json`, or writes JSON to stdout when `POCKETGUARD_OUTPUT` is unset.
- `node_modules/.bin/ts-node src/github_runner.ts --mode=publish` reads the review output, updates only a marker comment authored by the authenticated bot (otherwise creates one), and adds allowlisted labels. It does not call CPA.

Supported comment commands are `/review`, `/triage`, `/explain`, `/fix`, and `/fix-ci`. `/triage` is issue-only; commands requiring a diff are pull-request-only. A bare `@pocketguard` mention falls back to review. Unknown slash commands are ignored. Command classification does not itself perform code changes or issue triage.

## Configuration

Configure the following GitHub Actions secrets:

- `CPA_BASE_URL`
- `CPA_API_KEY`

Configure the following GitHub Actions variables:

- `POCKETGUARD_MODEL_CHIEF`
- `POCKETGUARD_MODEL_ANDROID_SEC`
- `POCKETGUARD_MODEL_ANDROID_CODE`
- `POCKETGUARD_CPA_ORIGIN`

`POCKETGUARD_CPA_ORIGIN` is the exact-origin allowlist for `CPA_BASE_URL`; it is not a wildcard or a path prefix. Model and endpoint configuration are supplied only through environment variables. The CPA credential is used as an authorization header and is not included in review prompts, comments, or generated artifacts.

## Security and coverage constraints

- Tagging receives no CPA configuration. The publish job receives only `GITHUB_TOKEN`; its permissions are limited to comment and issue/PR label publication. The review job has read-only repository permissions and receives CPA configuration only for a trusted same-repository pull request.
- The workflow checks out only the repository default branch. It never checks out a pull-request head. It fetches immutable base/head commit IDs only when the head repository matches the current repository. Fork pull requests receive a generic not-reviewed result and are not sent to CPA.
- Review output is single-turn per role; there is no model-controlled tool execution or retry loop.
- Diff truncation and omitted files are recorded in coverage. An incomplete review, or an omitted tier 0–3 file, cannot be approved. A truncated key file also prevents complete coverage.
- Deterministic secret and forbidden-manifest checks run against the full available diff. Secret-like text is redacted before model submission and generated output is sanitized before publication.
- Review artifacts are retained for seven days. The workflow's actions are pinned to full commit SHAs; update pins intentionally and keep the adjacent version comments accurate.

## Labels

- The bot allowlist includes `accessibility` and `run-instrumented` only to preserve existing repository labels; they are human-only and the bot never emits or manages them.
- The bot may emit `security`, `performance`, and `status:needs-decision`. Maintainers must create these labels before go-live; allowlisting them does not create them in the repository.
- `area:*` labels are derived from the full changed-path list. If that list is unavailable, publication preserves existing area labels and only reconciles `status:needs-decision`.

## Local verification

Use `npm test` and `npm run build`. The runner tests use fake CPA configuration and a fetch stub; they do not call external services. Never use production credentials or private review content in local tests.
