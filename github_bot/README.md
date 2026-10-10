# PocketGuard GitHub runner

## Purpose and ownership

This directory implements deterministic change tagging, three-role Android review, and publication of review results. Repository maintainers own the workflow and configuration. The workflow file is the source of truth for event triggers, permissions, action pins, and artifact retention; update this guide when those settings change.

## Modes

Run from this directory after installing the committed lockfile with `npm ci --ignore-scripts`:

- `node_modules/.bin/ts-node src/github_runner.ts --mode=tag` classifies eligible comment commands and emits deterministic label/context JSON. It does not call OpenAI.
- `node_modules/.bin/ts-node src/github_runner.ts --mode=claim` pre-occupies one per-SHA review slot after `prepare-tag` and before `review-send` (one `+1` per `(repo, PR, full head SHA)` plus a per-run `PocketGuard-review-claim:<sha>:<runId>:<attempt>` marker). Scheduling matches `review-send`: an open PR gate (`auto`, or `manual` with `authorized`) or the explicit issue-auto route (`target == 'issue'` with `should_review` and `route_kind` `first-review`/`issue-update`); issues are a no-op success that is never counted so issue-auto review still runs. Routing/authorization/quota/diff-unavailable/reopened-completed blocks claim nothing and count zero; a reserved slot whose later transport/artifact/publish fails is never refunded. The job holds minimal write (`issues: write`, no OpenAI secrets, no fork checkout) and pins the tag `quota_head_sha` (`POCKETGUARD_QUOTA_SHA`) against a fresh `pulls.get` head — any mismatch or fetch failure fails closed with no write. Phase 3 pre-flight verifies the diff before occupying (read-only `fetch` plus `merge-base` plus `diff --name-only` with fork ref plus SHA pin, within `MAX_CHANGED_FILES`); any failure claims nothing with zero writes so transient git failures never consume.
- `node_modules/.bin/ts-node src/github_runner.ts --mode=review` runs the deterministic scanner, then writes `review-output.json` plus `review-report.md`/`review-report.json` (or JSON to stdout when `POCKETGUARD_OUTPUT` is unset; reports are file-mode only). A deterministic `BLOCK` stops the review before any OpenAI request. If there is no deterministic `BLOCK`, the review makes one single-turn OpenAI request for each of the chief, Android security/policy, and Android code roles in parallel. For issue targets the same entry instead runs one chief single-turn over the fresh issue title, body, and human comments (see `prompts/issue_triage.md`) and records verdict plus deterministic rules tags merged with allowlisted AI suggestions; super-long or multi-comment content that is transport-complete but truncated (`!commentsComplete && fetchComplete` with fresh `issues.get` verification and `title` within cap) is split into sequential `MAX_ISSUE_CHUNK_LENGTH` (8000) chunks (`IssueChunk` with index/total/start/end/complete/coveredLength, title carried excluded from budget, per-segment validated) triaged per chunk then synthesized — covers body over 8000, single comment over 2000, multi-comment budget total over 20000, and zero-body comment-only shapes (boundary: total 20000 single-turn / 20001 chunked; body 8000 single-turn / 8001 chunked); fetch-incomplete, title-over-cap, fresh-unverified, or legacy-no-get stays fail-closed rules-only `INCONCLUSIVE` with zero AI (deterministic `BLOCK` may still `NEEDS_CHANGES`); multi-chunk/multi-role single rounds still count one claim-slot (issues never counted; claim semantics untouched). Reports carry findings/roles/coverage/fingerprint/time via `redactForModel`; the sticky writes back the artifact/run link on success and `報告：不可用` with `INCONCLUSIVE` and no fake link on failure, always updating the single comment ID in place via `buildStampedBody`. Issues never consume the PR review budget.
- `node_modules/.bin/ts-node src/github_runner.ts --mode=publish` reads the review output, updates only a marker comment authored by the authenticated bot (otherwise creates one), and adds allowlisted labels. It does not call OpenAI.

Supported comment commands are `/review`, `/triage`, `/explain`, `/fix`, and `/fix-ci` (ASCII `/` or fullwidth `／`). `/triage` is issue-only; only `/review` is actionable on pull requests. `/explain`, `/fix`, and `/fix-ci` classify (and still report `needs_diff`) but have no independent implementation yet: on pull requests they are legal no-ops (routed to `ignore`, zero OpenAI calls, zero quota, zero sticky/label writes). Explicit mention forms `@pocketguard review` and `@pocketguard /review` are accepted and equivalent to `/review` (the same verb rule applies to `triage`; `explain`, `fix`, and `fix-ci` mention forms likewise classify but remain no-ops). A bare `@pocketguard` mention with no verb is `unsupported` and never triggers a review. Unknown slash commands are ignored. Command classification does not itself perform code changes or issue triage. Triage applies only to automatic labeling on `issues opened`; a comment `/triage` on an issue is currently a legal no-op (no comment or label writes, no crash).

Command matching ignores fenced code blocks (``` ... ``` and ~~~ ... ~~~), inline code spans (`` `...` ``), and quoted lines (a line whose first non-space character is `>`), so a command-looking string inside a fence or quotation never triggers. Authors should still use explicit standalone commands outside fences and quotes.

## Event routing (read-only, S3)

Every event is first classified by the pure `routeEvent()` function into one of `first-review`, `issue-update`, `manual-pr-review`, `owner-commit`, or `ignore`, with a human-readable `reason`. Tag mode emits `should_review`, `should_tag`, `reason`, a snapshot identity (`route_kind`, `actor`, `repo_owner`, `is_owner`, `event_name`, `event_action`), and the S4 execution-matrix outputs (`review_gate`, `reviews_used`) to both `GITHUB_OUTPUT` and the result JSON. Routing performs no review, labeling, counting, or sticky writes; S4/S5 consume these flags.

- `pull_request_target opened/reopened` → `first-review` (regardless of fork/same-repo or author permission; readability is decided by the `diff_safe` gate: same-repo SHAs or fork SHAs via the pinned PR-ref fetch). Human `issues opened` is also `first-review`, requesting both review (`should_review=true`) and tagging context.
- `pull_request_target synchronize` by the repository owner → `owner-commit` (`should_review=true`); a non-owner synchronize → `ignore` with an owner-only reason.
- `issues edited/reopened` by a human, or any human `issue_comment` on an issue (bot senders excluded) → `issue-update` (`should_review=true`, `should_tag=true`; per spec any human issue comment re-reviews).
- `issue_comment created/edited` on a pull request with an explicit `/review` command (slash or `@pocketguard review` / `@pocketguard /review` form) → `manual-pr-review`. Anything else on a PR (`unsupported`, ordinary chatter, `/triage`, and the no-op `/explain`, `/fix`, `/fix-ci`) → `ignore`.
- Bot-authored or bot-sent comments (account `type` `Bot`, case-insensitive, or a login ending in `[bot]`) → `ignore` (loop protection). Other unknown events or actions → `ignore`.

Flag summary: `should_tag` is true for every non-`ignore` kind; `should_review` is true for pull-request `first-review`, `manual-pr-review`, and `owner-commit`, and for issue `first-review` and `issue-update` (human issue opens and human issue comments re-review per spec); only `ignore` requests neither. `should_tag` is a deterministic publish signal, never a review-job scheduling reason.

## Owner identity

Owner means the repository owner (`github.repository_owner`, passed as `POCKETGUARD_REPO_OWNER`), compared case-insensitively against the actor (`GITHUB_ACTOR`, falling back to `event.sender.login`). Commit-author strings are never used. Either side missing or blank means non-owner (fail-closed toward `ignore`).

## Execution matrix and per-SHA budget (S4)

The workflow schedules the secrets-bearing review job from routing, not from raw event fields: a pull-request review runs only with `should_review` plus an open `review_gate` (`auto` for first reviews and owner commits, which need no commenter gate; `manual` for explicit `/review` commands, which additionally require `authorized`), each additionally gated on `target == 'pull-request'`. Issue execution runs only by the explicit issue-auto route (`target == 'issue'` with `should_review` and `route_kind` `first-review`/`issue-update`). A quota-exhausted or quota-unknown run reports `review_gate none`, schedules nothing, and publishes nothing — the sticky comment and labels keep the previous review untouched. The trusted review step admits `(diff_safe && authorized)` plus the intentional issue-auto route, so issue AI receives OpenAI secrets in prod; the generic complement keeps no secrets. `diff_safe` means the diff is safely readable (same-repo or fork via the pinned PR-ref fetch, see below); `safe_review` stays same-repo-only so runs can distinguish the two. This issue-auto secrets path is intentional by owner decision: issue auto-review carries no auth/quota gate in this phase (no budget/rate limit; only bot exclusion in routing plus per-issue concurrency serialization).

- Budget: at most 2 AI reviews per PR plus head SHA (first review plus one re-review); a new head SHA restarts the budget. The budget is pre-occupied by the `claim-slot` job before any AI work (one `+1` per `(repo, PR, full head SHA)` plus a per-run claim marker); a started slot — including a run whose later transport/artifact/publish fails — is never refunded. `reopened` shares the `opened` budget and only supplements an incomplete first review: `reopened` routes to `first-review` but never resets the ledger — `reopened` with `used>=1` closes to `none` with `reopened-completed` (only `used==0` may `auto`; `opened` keeps `used=1` auto), `used>=2` stays `none` (`quota-exhausted` for `opened`, `reopened-completed` for `reopened`), and an unreadable ledger closes to `none` with `quota-unknown`. A PR with an incomplete changed list closes to `none` with `diff-unavailable` (claim pre-flight likewise claims nothing with zero writes, so transient git failures never consume). Issues are never counted, and routing/authorization/quota/diff denials never count (unstarted runs write nothing and count zero) — only a claimed slot counts, including `INCONCLUSIVE` and failed-review fallbacks. A retry that finds its own `(sha, run_id, attempt)` claim marker never `+1` again (idempotent); a new run with a new key consumes normally. An unreadable sticky counter is fail-closed (unknown): tag closes the gate with a `quota-unknown` reason, review returns generic `INCONCLUSIVE` with zero OpenAI calls, and publish writes nothing and counts nothing. A successful read with no marker is `0` (first review proceeds).

- Storage: the sticky bot comment carries a multi-marker ledger (one `<!-- PocketGuard-reviews:<sha>:<n> -->` line per head SHA, merged with `max()` so A→B→A never loses history) plus per-run `<!-- PocketGuard-review-claim:<sha>:<runId>:<attempt> -->` markers, alongside the review body. Tag mode reads the ledger into `reviews_used`; the claim job re-reads the full ledger immediately before writing and pre-occupies the slot (`+1` plus its own claim marker); review mode requires its own claim marker immediately before any git or OpenAI work (defense in depth against concurrent runs that both passed tagging; without its own claim it returns generic `INCONCLUSIVE` with zero OpenAI calls); publish mode only reconciles content and never increments — every publish write preserves the ledger and claim set unchanged. The per-PR workflow `concurrency` group serializes runs as the primary mutex; GitHub offers no compare-and-swap on comments, so a residual race remains if overlapping runs ever escape the group.
- Authorization for manual reviews: maintainer `write`+ as before, plus the PR author reviewing their own PR (comment login matching the fresh `pulls.get` author login, case-insensitive; an unreadable author identity denies). Other users stay denied. Fork authors self-review through the same path, under the same budget: the fork diff is read via the pinned PR-ref fetch and counts like any other review.
- Issue execution (minimal): `issues opened/edited/reopened` and human issue comments each build a truncated model copy (single-field caps title 2000 / body 8000 / comment 2000 plus budget `MAX_ISSUE_CONTEXT_LENGTH`=20000; any cut sets `commentsComplete=false`) plus full redacted copies for chunking and full raw copies for fingerprint/scan; the complete shape takes one chief single-turn with the dedicated `prompts/issue_triage.md` prompt, while any truncated-but-transport-complete shape with `fetchComplete` plus fresh verification plus title-in-cap takes N chunked chief single-turns (each at most 8000 body+comment chars, title carried) then synthesized. Any single-field/budget truncation sets `commentsComplete=false` on the single-turn path and downgrades to rules-only `INCONCLUSIVE` before any OpenAI call (never `APPROVE`; deterministic `BLOCK` may still `NEEDS_CHANGES`). The chunked path re-establishes completeness only with full chunk coverage plus fingerprint equality plus publish re-verification for `APPROVE`, otherwise `INCONCLUSIVE`; fetch-incomplete, title-over-cap, fresh-unverified, legacy-no-get, or validation failure never chunks and stays rules-only `INCONCLUSIVE`. Decision record: a dedicated prompt plus one role call is the smallest viable path — reusing the diff-oriented orchestrator would force diff/coverage concepts onto issues. Tags merge deterministic title-derived rules with allowlisted AI suggestions (`bug`, `enhancement`, and `documentation` included); unknown AI labels are discarded and force `INCONCLUSIVE`. Any AI failure (including any single chunk failure) degrades to rules-only `INCONCLUSIVE` on the same sticky (deterministic `BLOCK` may still `NEEDS_CHANGES`). A fresh-read failure is also fail-closed `INCONCLUSIVE`. Issue publishes verify the artifact fingerprint against the freshly re-read issue content (plus chunk coverage for chunked artifacts) before any sticky write, and maintain exactly one sticky comment per issue (single comment ID edited in place) plus label reconciliation, writing back the `pocketguard-review-report` artifact/run link on success and `報告：不可用` with no fake link on failure.
- Zero-AI locks: non-owner synchronize, ordinary chatter, the no-op `/explain`/`/fix`/`/fix-ci` commands, PR title/body edits, label changes, unrouted actions, and bot events are routed `ignore`, make zero OpenAI calls, consume zero quota, and publish nothing.

## Triggers

- `pull_request_target`: `opened`, `reopened`, `synchronize`.
- `issues`: `opened`, `edited`, `reopened`.
- `issue_comment`: `created`, `edited` (the authorization gate accepts `created` and `edited`; `deleted` and missing actions deny).

## Configuration

Configure non-secret model IDs as GitHub Actions variables (`vars`), with
repository secrets kept as a compatibility fallback (`vars.X || secrets.X` in
`pocketguard.yml`). Only `OPENAI_API_KEY` must stay a secret.

- `OPENAI_API_KEY` (secret, required)
- `POCKETGUARD_MODEL_CHIEF`, `POCKETGUARD_MODEL_ANDROID_SEC`,
  `POCKETGUARD_MODEL_ANDROID_CODE` (vars preferred, secrets fallback)
- `OPENAI_BASE_URL` (optional; defaults to `https://api.openai.com/v1`)
- `POCKETGUARD_OPENAI_ORIGIN` (optional exact-origin allowlist; defaults to
  `https://api.openai.com`)
- `POCKETGUARD_MODEL_PROFILES` (optional JSON map from model ID or ID prefix
  to `reasoning`/`chat`, e.g. `{"gpt-5.2": "reasoning:high", "gpt-4o": "chat"}`)
- `POCKETGUARD_MODEL_PROFILE` (optional global fallback when
  `POCKETGUARD_MODEL_PROFILES` is unset, e.g. `"reasoning:high"` or `"chat"`)
- `POCKETGUARD_MODEL_<ROLE>_PROFILE` (optional per-role override, e.g.
  `POCKETGUARD_MODEL_CHIEF_PROFILE: "reasoning:high"` or `"chat"`)
- `POCKETGUARD_REASONING_EFFORT` or `POCKETGUARD_MODEL_<ROLE>_REASONING_EFFORT`
  (optional effort override, default `high`). `POCKETGUARD_DEFAULT_REASONING_EFFORT`
  is an alias for the global default. The resolved default backs every
  `reasoning` profile without an explicit effort (explicit `reasoning:high`,
  JSON maps, and builtin `gpt-5*` defaults).

`POCKETGUARD_OPENAI_ORIGIN` is the exact-origin allowlist for `OPENAI_BASE_URL`; it is not a wildcard or a path prefix. `OPENAI_BASE_URL` must use
`https:` with no credentials, query, or fragment, and requests use
`redirect: 'error'` so authorization headers are never forwarded.
"OpenAI-compatible" here means the endpoint must support the OpenAI
Responses API (`POST {base}/responses` with `model`/`input`/
`max_output_tokens`/`stream:false`); providers without that API are not
supported. The OpenAI credential is used as an authorization header and is not included in review prompts, comments, or generated artifacts.

Request bodies are built per model profile with a minimal default
(`model` + `input` + `max_output_tokens` + `stream`, no `temperature`/`top_p`/
`reasoning` unless the profile requires it): reasoning models with
`effort != none` send `reasoning: { effort }` and omit `temperature`/`top_p`
(GPT-5-class reasoning rejects `temperature` with 400); reasoning with
`effort == none` explicitly sends `reasoning: { effort: 'none' }` on models
with none support (e.g. `gpt-5*` including `gpt-5.5`; see
https://developers.openai.com/api/docs/guides/reasoning — omission selects
the model default such as `medium`, not `none`). Models without none support
never send an omitted-key fallback: `resolveModelProfile` returns `undefined`
for model+`none` when unsupported and the send fails closed with zero fetch;
non-reasoning models never send `reasoning`, and `temperature`/`top_p` are
sent only when the caller explicitly sets them (reasoning, including explicit
none, is mutually exclusive with `temperature`/`top_p` and throws
fail-closed). Builtin defaults cover `gpt-5*` as reasoning and
`gpt-4o*`/`gpt-4.1*` as chat; any other model without an explicit profile
fails closed (`model profile not configured`, no request sent) and the
orchestrator converges to `INCONCLUSIVE` without retry, downgrade, or labels.
A `400` for an unsupported parameter surfaces with its status code and follows
the same fail-closed path. Fork quickstart: set the three model vars (and
optionally `POCKETGUARD_MODEL_PROFILES`) on your fork, keep only
`OPENAI_API_KEY` as a secret, and leave the URL/origin unset to use the
official OpenAI defaults.

## Security and coverage constraints

- Tagging receives no OpenAI configuration. The publish job receives only `GITHUB_TOKEN`; the workflow grants it `issues: write` and `pull-requests: write` scopes so it can publish comments and labels. These are GitHub resource scopes, not per-endpoint restrictions. The review job has read-only repository permissions and receives OpenAI configuration only for a pull request whose diff is safely readable (`diff_safe`) with an explicit authorization verdict.
- The workflow checks out only the repository default branch. It never checks out a pull-request head. Same-repo reviews fetch immutable base/head commit IDs directly; fork reviews fetch the immutable base SHA plus exactly `+refs/pull/<N>/head` for the PR under review, then verify the fetched head resolves to the expected head SHA (SHA-pin against the webhook/`pulls.get` value). Any pin mismatch, fetch failure, or merge-base failure is fail-closed (`INCONCLUSIVE`, no OpenAI call). Fork code is never checked out, executed, or given credentials — it is only read as a diff.
- Changed paths and per-file patches are computed from `git merge-base <base> <head>` to the head, never by comparing base and head trees directly: a PR that lags the default branch must not absorb main-only changes. The workflow checks out full history (`fetch-depth: 0`) so the base-side ancestry is present, and the runner issues an explicit `git fetch` (same-repo: `git fetch origin <base> <head>`; fork: `git fetch origin <base>` plus `git fetch origin +refs/pull/<N>/head`, no checkout of fork code) to obtain head objects. Any fetch or merge-base failure is fail-closed (`INCONCLUSIVE`, no OpenAI call) and never falls back to a direct base..head comparison. A changed-file list longer than `MAX_CHANGED_FILES` (3000, the local equivalent of the PR-files listing concept) is likewise incomplete and fail-closed.
- Review output is single-turn per role; there is no model-controlled tool execution or retry loop.
- Diff truncation and omitted files are recorded in coverage. An incomplete review, or an omitted tier 0–3 file, cannot be approved. A truncated key file also prevents complete coverage.
- Deterministic checks inspect added content in the available diff; this is not a repository-wide or comprehensive secret scan. Secret detection is a bounded heuristic: it looks for exact supported key/token patterns on individual added lines and across at most three contiguous added lines from the same file and diff hunk. Multi-line candidates must be contiguous in the new file; context-separated additions are not joined, and surrounding source context is not reconstructed. Removed lines are not scanned, though they do not necessarily separate additions that are adjacent in the new file. Obfuscation, nonadjacent fragments, constructions spanning more than three lines, encoding, and semantic constructions can be missed. A miss must not be treated as evidence that a change contains no secret.
- A deterministic `BLOCK` prevents the OpenAI review path from running; it does not make the heuristic a comprehensive secret scanner or establish that non-matching changes are safe. Secret-like text that is detected is redacted before model submission; generated output is sanitized before publication. Redaction is defense in depth, not a substitute for detection or a guarantee that an undetected value cannot be submitted.
- Review artifacts (`pocketguard-review-report` with `review-output.json` + `review-report.md`/`review-report.json`) are retained for 30 days; the download step uses the same name/path (`github_bot`). The workflow's actions are pinned to full commit SHAs; update pins intentionally and keep the adjacent version comments accurate.

## Labels

- The bot allowlist includes `accessibility` and `run-instrumented` only to preserve existing repository labels; they are human-only and the bot never emits or manages them.
- The bot may emit `security`, `performance`, `type:tracking`, and `status:needs-decision`. Maintainers must create these labels before go-live; allowlisting them does not create them in the repository.
- `area:*` labels are derived from the full changed-path list. If that list is unavailable, publication preserves existing area labels and only reconciles `status:needs-decision`.
- Mutual exclusion (P2 #4): `priority:{P1,P2}`, `gate:*`, `status:verified-main/partial/latent`, and `bug/enhancement/documentation` are mutex groups. Human-only members (`gate:*`, verified statuses) are never written or auto-removed by the bot. `priority` stays issue-triage-only. PR AI restores semantic `bug`/`enhancement`/`documentation` per Owner B; bot-owned transitions (`priority:P1->P2`, `bug->enhancement` when desired carries a same-group peer) may reconcile, otherwise coexistence only warns for a maintainer to resolve. Provenance: the bot only deletes inside `area:*` + `status:needs-decision` plus bot-owned transitions, and incomplete coverage preserves existing `area:*` (human lock priority).
- PR convergence: PR publish AI suggestions are limited to `area:*`/`security`/`performance`/`status:needs-decision` (+ `type:tracking` + semantic `bug`/`enhancement`/`documentation`); `priority:*`/`gate:*` (and verified statuses) are issue-triage-only. A PR AI suggestion carrying an issue-only or unknown label is discarded and forces `INCONCLUSIVE` (never `APPROVE`, never written).

## Maintainer go-live gate — NOT YET LOCALLY OR HOSTED-VERIFIED

Repository maintainers own this external gate. This guide describes the checks; the workflow at `.github/workflows/pocketguard.yml`, GitHub repository Actions settings, configured secrets, and actual hosted run evidence are the sources of truth. Local tests or a successful workflow syntax check do not complete this gate. Re-run it after changes to workflow triggers, permissions, actions/artifacts, OpenAI configuration, labels, or repository Actions settings. Do not record it as passed until the hosted checks below have evidence.

- [ ] Confirm the workflow file is present on the repository's actual default branch, enabled, and selected for the expected PR/comment events. Verify the run checks out the default branch rather than pull-request code.
- [ ] In hosted Actions, exercise a same-repository PR and a fork PR. Confirm an eligible same-repository PR and an eligible fork PR (first review, or authorized maintainer/author `/review`) each enter the trusted review path with OpenAI configuration via the pinned diff fetch; an unauthorized or SHA-pin-failed fork receives the generic not-reviewed result, its head is never checked out or executed, and its diff is not sent to OpenAI. Inspect the run's job steps and logs, not only the published comment.
- [ ] Confirm the review artifact (`pocketguard-review-report` with `review-output.json` + `review-report.md`/`review-report.json`, retention 30 days) is uploaded and downloaded under the matching name/path. Exercise failed review-job and missing/unreadable artifact cases: publishing must give an `INCONCLUSIVE` fallback with `報告：不可用` and no fake run/artifact link, never approval. Treat a missing, malformed, or otherwise invalid artifact as inconclusive evidence as well.
- [ ] Use a staging OpenAI endpoint and a nonproduction key for a real hosted request. Verify the expected request reaches staging, then verify a redirect is rejected and no authorization header is forwarded to the redirect destination. Do not use production credentials; retain only non-sensitive test evidence.
- [ ] Audit effective workflow/job permissions and repository Actions/fork settings. Confirm tagging and review use the intended read scopes, publishing receives the configured `issues: write` and `pull-requests: write` resource scopes, and OpenAI secrets are exposed only to the trusted review step. Do not treat these scopes as endpoint-level restrictions.
- [ ] Create every label the bot can emit before enabling publication, including `security`, `performance`, `type:tracking`, and `status:needs-decision`, plus any applicable `area:*` labels. Human-only labels do not need bot permissions.
- [ ] In hosted Actions, contrast an outsider `issue_comment` against a maintainer `write`+ comment: the outsider run skips publish without touching the sticky comment or labels, while the eligible maintainer comment enters the trusted path. Keep both run URLs/IDs.
- [ ] In hosted Actions, exercise a missing OpenAI secret and a missing/uncreated label: both must fall back to `INCONCLUSIVE`, never approval, with the label case reconciling only `status:needs-decision`. Keep run URLs/IDs.
- [ ] Keep GitHub Actions run URLs/IDs and concise results for the default-branch, same-repository, fork, artifact-fallback, permission, staging-OpenAI, comment-auth, missing-secret, and missing-label checks with the maintainer's go-live record. Do not include keys, authorization headers, or private review content. Revisit the evidence when any listed source of truth changes.

Operation notes: `issue_comment` requires maintainer `write`+ authorization or the PR author reviewing their own PR; unauthorized comments skip publish without touching the sticky comment or labels. Each PR plus head SHA is budgeted at most 2 AI reviews; `review_gate none` runs schedule and publish nothing. Workflow `concurrency` serializes runs per PR/issue but provides no compare-and-swap on comments. Operators must monitor GitHub API quota and Actions minutes for comment-triggered runs.

## Local verification

Use `npm test` and `npm run build`. The runner tests use fake OpenAI configuration and a fetch stub; they do not call external services. Never use production credentials or private review content in local tests.
