# PocketGuard GitHub runner

## Purpose and ownership

This directory implements deterministic change tagging, three-role Android review, and publication of review results. Repository maintainers own the workflow and configuration. The workflow file is the source of truth for event triggers, permissions, action pins, and artifact retention; update this guide when those settings change.

## Modes

Run from this directory after installing the committed lockfile with `npm ci --ignore-scripts`:

- `node_modules/.bin/ts-node src/github_runner.ts --mode=tag` classifies eligible comment commands and emits deterministic label/context JSON. It does not call OpenAI.
- `node_modules/.bin/ts-node src/github_runner.ts --mode=review` runs the deterministic scanner, then writes `review-output.json` (or JSON to stdout when `POCKETGUARD_OUTPUT` is unset). A deterministic `BLOCK` stops the review before any OpenAI request. If there is no deterministic `BLOCK`, the review makes one single-turn OpenAI request for each of the chief, Android security/policy, and Android code roles in parallel. For issue targets the same entry instead runs one chief single-turn over the issue title, body, and human comments (see `prompts/issue_triage.md`) and records verdict plus rules-only tags; issues never consume the PR review budget.
- `node_modules/.bin/ts-node src/github_runner.ts --mode=publish` reads the review output, updates only a marker comment authored by the authenticated bot (otherwise creates one), and adds allowlisted labels. It does not call OpenAI.

Supported comment commands are `/review`, `/triage`, `/explain`, `/fix`, and `/fix-ci` (ASCII `/` or fullwidth `／`). `/triage` is issue-only; commands requiring a diff (`/review`, `/explain`, `/fix`, `/fix-ci`) are pull-request-only. Explicit mention forms `@pocketguard review` and `@pocketguard /review` are accepted and equivalent to `/review` (the same verb rule applies to `triage`, `explain`, `fix`, and `fix-ci`). A bare `@pocketguard` mention with no verb is `unsupported` and never triggers a review. Unknown slash commands are ignored. Command classification does not itself perform code changes or issue triage. Triage applies only to automatic labeling on `issues opened`; a comment `/triage` on an issue is currently a legal no-op (no comment or label writes, no crash).

Known limitation: code-fence or quote stripping is not performed, so a command-looking string inside a fenced block or quotation still classifies as that command. Later stages treat only routed kinds as actionable, but authors should still use explicit standalone commands.

## Event routing (read-only, S3)

Every event is first classified by the pure `routeEvent()` function into one of `first-review`, `issue-update`, `manual-pr-review`, `owner-commit`, or `ignore`, with a human-readable `reason`. Tag mode emits `should_review`, `should_tag`, `reason`, a snapshot identity (`route_kind`, `actor`, `repo_owner`, `is_owner`, `event_name`, `event_action`), and the S4 execution-matrix outputs (`review_gate`, `reviews_used`) to both `GITHUB_OUTPUT` and the result JSON. Routing performs no review, labeling, counting, or sticky writes; S4/S5 consume these flags.

- `pull_request_target opened/reopened` → `first-review` (regardless of fork/same-repo or author permission; readability is decided by the `diff_safe` gate: same-repo SHAs or fork SHAs via the pinned PR-ref fetch). Human `issues opened` is also `first-review`, requesting both review (`should_review=true`) and tagging context.
- `pull_request_target synchronize` by the repository owner → `owner-commit` (`should_review=true`); a non-owner synchronize → `ignore` with an owner-only reason.
- `issues edited/reopened` by a human, or any human `issue_comment` on an issue (bot senders excluded) → `issue-update` (`should_review=true`, `should_tag=true`; per spec any human issue comment re-reviews).
- `issue_comment created/edited` on a pull request with an explicit diff-needing command (`review`, `explain`, `fix`, `fix-ci`, via slash or `@pocketguard` verb form) → `manual-pr-review`. Anything else on a PR (`unsupported`, ordinary chatter, `/triage`) → `ignore`.
- Bot-authored or bot-sent comments (account `type` `Bot`, case-insensitive, or a login ending in `[bot]`) → `ignore` (loop protection). Other unknown events or actions → `ignore`.

Flag summary: `should_tag` is true for every non-`ignore` kind; `should_review` is true for pull-request `first-review`, `manual-pr-review`, and `owner-commit`, and for issue `first-review` and `issue-update` (human issue opens and human issue comments re-review per spec); only `ignore` requests neither. `should_tag` is a deterministic publish signal, never a review-job scheduling reason.

## Owner identity

Owner means the repository owner (`github.repository_owner`, passed as `POCKETGUARD_REPO_OWNER`), compared case-insensitively against the actor (`GITHUB_ACTOR`, falling back to `event.sender.login`). Commit-author strings are never used. Either side missing or blank means non-owner (fail-closed toward `ignore`).

## Execution matrix and per-SHA budget (S4)

The workflow schedules the secrets-bearing review job from routing, not from raw event fields: a pull-request review runs only with `should_review` plus an open `review_gate` (`auto` for first reviews and owner commits, which need no commenter gate; `manual` for explicit review commands, which additionally require `authorized`), each additionally gated on `target == 'pull-request'`. Issue execution runs only by the explicit issue-auto route (`target == 'issue'` with `should_review` and `route_kind` `first-review`/`issue-update`). A quota-exhausted or quota-unknown run reports `review_gate none`, schedules nothing, and publishes nothing — the sticky comment and labels keep the previous review untouched. The trusted review step admits `(diff_safe && authorized)` plus the intentional issue-auto route, so issue AI receives OpenAI secrets in prod; the generic complement keeps no secrets. `diff_safe` means the diff is safely readable (same-repo or fork via the pinned PR-ref fetch, see below); `safe_review` stays same-repo-only so runs can distinguish the two. This issue-auto secrets path is intentional by owner decision: issue auto-review carries no auth/quota gate in this phase (no budget/rate limit; only bot exclusion in routing plus per-issue concurrency serialization).

- Budget: at most 2 AI reviews per PR plus head SHA (first review plus one re-review); a new head SHA restarts the budget. Issues are never counted, and routing/authorization denials never count — only a started review counts, including `INCONCLUSIVE` and failed-review fallbacks. An unreadable sticky counter is fail-closed (unknown): tag closes the gate with a `quota-unknown` reason, review returns generic `INCONCLUSIVE` with zero OpenAI calls, and publish writes nothing and counts nothing. A successful read with no marker is `0` (first review proceeds).

- Storage: the sticky bot comment carries `<!-- PocketGuard-reviews:<sha>:<n> -->` alongside the review body. Tag mode reads it into `reviews_used`; review mode re-reads it immediately before any git or OpenAI work (defense in depth against concurrent runs that both passed tagging); publish mode stamps the incremented marker on every sticky write of the run. The per-PR workflow `concurrency` group serializes runs as the primary mutex; GitHub offers no compare-and-swap on comments, so a residual race remains if overlapping runs ever escape the group.
- Authorization for manual reviews: maintainer `write`+ as before, plus the PR author reviewing their own PR (comment login matching the fresh `pulls.get` author login, case-insensitive; an unreadable author identity denies). Other users stay denied. Fork authors self-review through the same path, under the same budget: the fork diff is read via the pinned PR-ref fetch and counts like any other review.
- Issue execution (minimal): `issues opened/edited/reopened` and human issue comments each build one context (title, body, human comments with bot authors excluded, truncated to `MAX_ISSUE_CONTEXT_LENGTH`) and take exactly one chief single-turn with the dedicated `prompts/issue_triage.md` prompt. Decision record: a dedicated prompt plus one role call is the smallest viable path — reusing the diff-oriented orchestrator would force diff/coverage concepts onto issues, and AI-emitted labels stay out of scope until S5, so tags here are rules-only (title-derived). Any AI failure degrades to rules-only `INCONCLUSIVE`. Issue publishes apply labels only and never write comments.
- Zero-AI locks: non-owner synchronize, ordinary chatter, PR title/body edits, label changes, unrouted actions, and bot events are routed `ignore`, make zero OpenAI calls, and publish nothing.

## Triggers

- `pull_request_target`: `opened`, `reopened`, `synchronize`.
- `issues`: `opened`, `edited`, `reopened`.
- `issue_comment`: `created`, `edited` (the authorization gate accepts `created` and `edited`; `deleted` and missing actions deny).

## Configuration

Configure the following GitHub Actions secrets:

- `OPENAI_BASE_URL`
- `OPENAI_API_KEY`
- `POCKETGUARD_MODEL_CHIEF`
- `POCKETGUARD_MODEL_ANDROID_SEC`
- `POCKETGUARD_MODEL_ANDROID_CODE`
- `POCKETGUARD_OPENAI_ORIGIN`

`POCKETGUARD_OPENAI_ORIGIN` is the exact-origin allowlist for `OPENAI_BASE_URL`; it is not a wildcard or a path prefix. Model and endpoint configuration are supplied only through environment variables. The OpenAI credential is used as an authorization header and is not included in review prompts, comments, or generated artifacts.

## Security and coverage constraints

- Tagging receives no OpenAI configuration. The publish job receives only `GITHUB_TOKEN`; the workflow grants it `issues: write` and `pull-requests: write` scopes so it can publish comments and labels. These are GitHub resource scopes, not per-endpoint restrictions. The review job has read-only repository permissions and receives OpenAI configuration only for a pull request whose diff is safely readable (`diff_safe`) with an explicit authorization verdict.
- The workflow checks out only the repository default branch. It never checks out a pull-request head. Same-repo reviews fetch immutable base/head commit IDs directly; fork reviews fetch the immutable base SHA plus exactly `+refs/pull/<N>/head` for the PR under review, then verify the fetched head resolves to the expected head SHA (SHA-pin against the webhook/`pulls.get` value). Any pin mismatch, fetch failure, or merge-base failure is fail-closed (`INCONCLUSIVE`, no OpenAI call). Fork code is never checked out, executed, or given credentials — it is only read as a diff.
- Changed paths and per-file patches are computed from `git merge-base <base> <head>` to the head, never by comparing base and head trees directly: a PR that lags the default branch must not absorb main-only changes. The workflow checks out full history (`fetch-depth: 0`) so the base-side ancestry is present, and the runner issues an explicit `git fetch` (same-repo: `git fetch origin <base> <head>`; fork: `git fetch origin <base>` plus `git fetch origin +refs/pull/<N>/head`, no checkout of fork code) to obtain head objects. Any fetch or merge-base failure is fail-closed (`INCONCLUSIVE`, no OpenAI call) and never falls back to a direct base..head comparison. A changed-file list longer than `MAX_CHANGED_FILES` (3000, the local equivalent of the PR-files listing concept) is likewise incomplete and fail-closed.
- Review output is single-turn per role; there is no model-controlled tool execution or retry loop.
- Diff truncation and omitted files are recorded in coverage. An incomplete review, or an omitted tier 0–3 file, cannot be approved. A truncated key file also prevents complete coverage.
- Deterministic checks inspect added content in the available diff; this is not a repository-wide or comprehensive secret scan. Secret detection is a bounded heuristic: it looks for exact supported key/token patterns on individual added lines and across at most three contiguous added lines from the same file and diff hunk. Multi-line candidates must be contiguous in the new file; context-separated additions are not joined, and surrounding source context is not reconstructed. Removed lines are not scanned, though they do not necessarily separate additions that are adjacent in the new file. Obfuscation, nonadjacent fragments, constructions spanning more than three lines, encoding, and semantic constructions can be missed. A miss must not be treated as evidence that a change contains no secret.
- A deterministic `BLOCK` prevents the OpenAI review path from running; it does not make the heuristic a comprehensive secret scanner or establish that non-matching changes are safe. Secret-like text that is detected is redacted before model submission; generated output is sanitized before publication. Redaction is defense in depth, not a substitute for detection or a guarantee that an undetected value cannot be submitted.
- Review artifacts are retained for seven days. The workflow's actions are pinned to full commit SHAs; update pins intentionally and keep the adjacent version comments accurate.

## Labels

- The bot allowlist includes `accessibility` and `run-instrumented` only to preserve existing repository labels; they are human-only and the bot never emits or manages them.
- The bot may emit `security`, `performance`, `type:tracking`, and `status:needs-decision`. Maintainers must create these labels before go-live; allowlisting them does not create them in the repository.
- `area:*` labels are derived from the full changed-path list. If that list is unavailable, publication preserves existing area labels and only reconciles `status:needs-decision`.

## Maintainer go-live gate — NOT YET LOCALLY OR HOSTED-VERIFIED

Repository maintainers own this external gate. This guide describes the checks; the workflow at `.github/workflows/pocketguard.yml`, GitHub repository Actions settings, configured secrets, and actual hosted run evidence are the sources of truth. Local tests or a successful workflow syntax check do not complete this gate. Re-run it after changes to workflow triggers, permissions, actions/artifacts, OpenAI configuration, labels, or repository Actions settings. Do not record it as passed until the hosted checks below have evidence.

- [ ] Confirm the workflow file is present on the repository's actual default branch, enabled, and selected for the expected PR/comment events. Verify the run checks out the default branch rather than pull-request code.
- [ ] In hosted Actions, exercise a same-repository PR and a fork PR. Confirm an eligible same-repository PR and an eligible fork PR (first review, or authorized maintainer/author `/review`) each enter the trusted review path with OpenAI configuration via the pinned diff fetch; an unauthorized or SHA-pin-failed fork receives the generic not-reviewed result, its head is never checked out or executed, and its diff is not sent to OpenAI. Inspect the run's job steps and logs, not only the published comment.
- [ ] Confirm the review artifact is uploaded and downloaded under the matching name/path. Exercise failed review-job and missing/unreadable artifact cases: publishing must give an `INCONCLUSIVE` fallback, never approval. Treat a missing, malformed, or otherwise invalid artifact as inconclusive evidence as well.
- [ ] Use a staging OpenAI endpoint and a nonproduction key for a real hosted request. Verify the expected request reaches staging, then verify a redirect is rejected and no authorization header is forwarded to the redirect destination. Do not use production credentials; retain only non-sensitive test evidence.
- [ ] Audit effective workflow/job permissions and repository Actions/fork settings. Confirm tagging and review use the intended read scopes, publishing receives the configured `issues: write` and `pull-requests: write` resource scopes, and OpenAI secrets are exposed only to the trusted review step. Do not treat these scopes as endpoint-level restrictions.
- [ ] Create every label the bot can emit before enabling publication, including `security`, `performance`, `type:tracking`, and `status:needs-decision`, plus any applicable `area:*` labels. Human-only labels do not need bot permissions.
- [ ] In hosted Actions, contrast an outsider `issue_comment` against a maintainer `write`+ comment: the outsider run skips publish without touching the sticky comment or labels, while the eligible maintainer comment enters the trusted path. Keep both run URLs/IDs.
- [ ] In hosted Actions, exercise a missing OpenAI secret and a missing/uncreated label: both must fall back to `INCONCLUSIVE`, never approval, with the label case reconciling only `status:needs-decision`. Keep run URLs/IDs.
- [ ] Keep GitHub Actions run URLs/IDs and concise results for the default-branch, same-repository, fork, artifact-fallback, permission, staging-OpenAI, comment-auth, missing-secret, and missing-label checks with the maintainer's go-live record. Do not include keys, authorization headers, or private review content. Revisit the evidence when any listed source of truth changes.

Operation notes: `issue_comment` requires maintainer `write`+ authorization or the PR author reviewing their own PR; unauthorized comments skip publish without touching the sticky comment or labels. Each PR plus head SHA is budgeted at most 2 AI reviews; `review_gate none` runs schedule and publish nothing. Workflow `concurrency` serializes runs per PR/issue but provides no compare-and-swap on comments. Operators must monitor GitHub API quota and Actions minutes for comment-triggered runs.

## Local verification

Use `npm test` and `npm run build`. The runner tests use fake OpenAI configuration and a fetch stub; they do not call external services. Never use production credentials or private review content in local tests.
