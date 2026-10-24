# Testing and Verification

**Status:** Current test inventory and repeatable command guide.

- **Scope:** Repository CI, local JVM/Robolectric tests, static policy gates, and separately scheduled Android device tests.
- **Owner role:** CI/build maintainer; no individual is assigned here.
- **Source of truth:** `.github/workflows/pr-check.yml`, `.github/workflows/codeql.yml`, `.github/workflows/security-audit.yml`, `scripts/docs_claim_check.sh`, `scripts/test_docs_claim_check.sh`, `scripts/mcp_scan.py`, `scripts/requirements-docs.txt`, `scripts/apk_policy_inspect.py`, `scripts/play_policy_check.sh`, `scripts/backup_rules_check.py`, `scripts/summarize_junit.py`, `scripts/tests/test_apk_policy_check.py`, `scripts/tests/test_summarize_junit.py`, `scripts/tests/test_gradle_wrapper_checksum.py`, test source sets, Gradle wrapper/catalog, and the command results from the exact commit being assessed.
- **Update trigger:** CI, test source-set, Android SDK/JDK, policy-script, or merge-gate changes.

## Current CI scope

`pr-check.yml` is path-gated. A `changes` job classifies each pull request
with `dorny/paths-filter` into `code` (`app/src/**`,
`app/build.gradle.kts`, `app/lint.xml`, `gradle/**`,
`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
`.github/workflows/pr-check.yml`) and `policy`
(`scripts/play_policy_check.sh`, `scripts/apk_policy_inspect.py`,
`scripts/backup_rules_check.py`,
`scripts/nsc_check.py`,
`scripts/summarize_junit.py`, `scripts/check_test_budgets.py`,
`scripts/check_junit_durations.py`,
`scripts/tests/test_apk_policy_check.py`,
`scripts/tests/test_summarize_junit.py`,
`scripts/tests/test_check_test_budgets.py`,
`scripts/tests/test_check_junit_durations.py`,
`scripts/build_release.sh`, `scripts/verify_release_apk.py`,
`scripts/release_artifact_stage.py`,
`scripts/check_release_pins.py`,
`scripts/check_release_tag_version.py`,
`scripts/fake_keystore_rehearsal.py`,
`scripts/release_signer_pins.example.json`,
`scripts/tests/test_release_artifact_verifier.py`,
`scripts/tests/test_build_release_identity.py`,
`scripts/tests/test_gradle_wrapper_checksum.py`,
`.github/workflows/pr-check.yml`) outputs. The `policy` list ends with a
trailing `- 'scripts/**'` catch-all: the explicit entries above mirror the
full filter in `pr-check.yml` (no silent drift between docs and workflow),
they exist for readability/history, and the catch-all defaults any new
`scripts/` harness code (checker or script test) into the gate with no
filter edit (`PolicyCiGateControl` pins the catch-all in the parsed filter
list and never parses this document).
`docs-guard` always runs the
fixture self-test `scripts/test_docs_claim_check.sh` (synthetic repos for each
enumerated zero-wiring bypass surface — each wiring vector and each discovery
fail-closed path —, CWD-independent) followed by the static grep + manifest XML
parse + gradle-injection scan guard `scripts/docs_claim_check.sh` (no Gradle
execution, no emulator; requires `python3` — stdlib `xml.etree` for the C2
manifest/XML scan plus PyYAML for workflow YAML validation; CI installs the
pinned, hash-checked `PyYAML==6.0.3` from `scripts/requirements-docs.txt`
(with `--require-hashes`) rather than relying on the runner image; the C2b
gradle-injection scan decodes Unicode `\uXXXX` escapes, then reads every
`*.gradle`/`*.gradle.kts`/`gradle.properties` found by `find` — a superset of
tracked files, so untracked local injections also fail closed — and matches
`manifestPlaceholders` case-insensitively (covering
`add`/`set`/`getManifestPlaceholders`, including getter `.put`), setter
`setNamespace`/`setApplicationId`, any `srcFile`/`setSrcFile` token
(receiver-less/alias/`.apply {}` forms included; AGP's real API is
`manifest.srcFile(...)`), and namespace/applicationId assignments, including
a value on the following line, triple-quoted/slashy strings, Gradle Property
`.set`/`.convention`/`.value` forms, and comments/space around dots. Any
standalone MCP literal in a scanned gradle file is itself fail-closed because
it can feed a dynamic property; `gradle.properties` logical lines are joined
across `\` continuations before decoding. C4 uses a string-aware comment
normaliser, so a `//` inside a URL/string cannot truncate a later FQN.
`pr-gate` (`always()`, needs all prior jobs) resolves a skipped Gradle
stage as pass only when the corresponding `changes` output is explicitly
`false`, and fails closed otherwise.

The docs guard and its fixture self-test share one stdlib-only scanner,
`scripts/mcp_scan.py` (subcommands `discover`, `module-roots`, `types`,
`outbound`). `discover` derives module roots from `settings.gradle[.kts]` and
enumerates production Kotlin/Java sources (excluding real Gradle test source
sets, including files outside `src/`); `types` extracts declared type names from
the comment/string-stripped, Java-Unicode-decoded source view; `outbound`
parses each in-package import independently and checks it against the exact
allowlist plus the surrounding outbound `dev.librepocket.*` references. The
fixture self-test copies that same script into its synthetic repositories, so
the real gate and every fixture exercise the same parser instead of two drifting
implementations. It is a zero-wiring gate helper, not a general-purpose
security scanner.

The zero-wiring gate is fail-closed, so it deliberately over-blocks in a
few layouts rather than risk a missed wiring. A module whose `projectDir`
sits below another module's or the root project's `build/` output tree
keeps that ancestor tree traversable, so the nested module is never
pruned and the generated files still in that tree are scanned instead of
skipped — an MCP literal in one of them turns the gate red (accepted
over-block). A `srcDir`/`srcDirs` call names a source set only when the
gate can prove one: a receiver-less `java.srcDir(...)` is resolved through
the enclosing source-set block (`sourceSets.getByName("main") { … }`,
`with(sourceSets.getByName("main")) { … }`), and wrappers
(`with`/`let`/`run`/`apply`/`also`), `?.`/`!!` chains, grouped or cast
receivers and same-file aliases are resolved when they name a known source
set. Only a receiver the gate cannot resolve to a known source set is
recorded as unresolved and fails closed, never assumed to be a test source
set: that covers an unknown bare receiver, an opaque call argument, a
chain naming several source sets, and a trailing lambda whose result is
unknown. A source-set name that a same-file declaration shadows
(`val test = …`) is resolved through that alias resolver instead of
selecting the built-in test source set, and a `var` that is reassigned
stays unresolved. These are over-blocks (false reds), not misses; the
fixture self-test pins each of these shapes.

When `code == true`, the workflow uses JDK 25 and runs these Gradle stages:

1. `:app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest` (including Robolectric tests available in those source sets). The unit-tests job fans out over a flavor matrix with fail-fast disabled, so each flavor runs on its own runner and wall time is the slowest shard rather than the sum. Test report artifacts now include both HTML reports and structured JUnit XML (`app/build/test-results/`), retained for 14 days; results are parsed in real time into GitHub Actions Step Summary (`$GITHUB_STEP_SUMMARY`) to surface failure counts, skipped tests, and assertion summaries directly without requiring artifact downloads. The parser (`scripts/summarize_junit.py`, covered by `scripts/tests/test_summarize_junit.py`) is reporting-only and never a gate: it emits an explicit `Status:` line that reads `PASS` only when every XML file is readable, `tests > 0`, `failures == 0`, `errors == 0`, and every suite's counters exactly equal its direct `<testcase>` detail (`tests` vs case count, `failures+errors` vs failed cases, `skipped` vs skipped cases, either direction) — otherwise `FAIL`, `PARTIAL` (unreadable files), `INCOMPLETE` (empty or contradictory totals, including any per-suite mismatch), or `NO_RESULTS` (no XML). Only outermost suites are counted (a `<testsuite>` root counts itself; a `<testsuites>` root counts its direct `<testsuite>` children; nested suites are ignored for counting with a warning and force `INCOMPLETE`, since outer counters alone cannot prove completeness; Gradle never emits nested suites, so flat real-world results are unaffected). Rendered output is capped at 900 KiB of UTF-8 bytes (idents, messages, every warning, and the flavor title each truncated to 300 characters); overflow drops tail entries and records `omitted N failures / M skips; see XML artifact` (plus `omitted K warning(s); see XML artifact` when warnings themselves overflow), never raising.
2. `:app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug`.
3. `:app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug :app:assemblePlayRelease :app:assembleFossRelease :app:assembleGithubRelease` (the release variants are assembled UNSIGNED — this job runs without `-PreleaseSigning`, so no signing config is assigned per `app/build.gradle.kts`), then `scripts/play_policy_check.sh` for Play and `scripts/play_policy_check.sh --foss` for Foss on the debug APKs, plus the github-mode artifact binding authority on the assembled github debug APK in the same stage (`apk_policy_inspect.py --mode github`, alongside the common five-token manifest source scan), and the SAME-tier policy on the three release APK outputs as well (`Run {Play,Foss} Release Policy Check` through the shell gate over `app/build/outputs/apk/{play,foss}/release/*.apk`, `Run Github Release Artifact Binding Check` over `app/build/outputs/apk/github/release/*.apk`) — Debug APKs never contain the release source set, so a rogue release/variant manifest only becomes an artifact fact on this tier. This stage also runs when only `policy == true`.

Robolectric SDK pin (Current): JVM unit tests run with `sdk=33` pinned in
`app/src/test/resources/robolectric.properties`, while `targetSdk` is 36
(see [Build Environment](ENV.md)). A green run on API 33 does **not** imply
API 34–36 behavior is verified; version-sensitive Android API behavior needs
a separate higher-API smoke pass (Proposed, not a current CI gate). The pin
itself and any `@Config` SDK matrix expansion are out of scope for the
current deflake work — do not change them here.

Issue #86 byte-cap PR/nightly split (Current, Phase B stop-gap, zero prod change):
PR runs slimmed byte-cap versions (11-turn / 22-row / 2.2M-char over-budget
prefix plus the 12-turn cleanFence; same 20-fit / 21st-overflow arithmetic,
same all-or-nothing + marker + clean-reopen assertions); the original 100-turn
/ 200-row / 20M-char full stress runs only in the nightly `nightly-unit-stress`
job (`NIGHTLY_STRESS=1`, Foss flavor only — shaping is flavor-independent,
independent 35-minute timeout, JUnit XML retained 14 days, red requires filing
an issue manually and never blocks merge — not in `pr-check.yml` `pr-gate`).
Slim tests use `FakeSessionStore.appendPreRedactedForBudgetTest`
(pre-redacted `"x"` fixture, bytes identical, skips the O(n²) EMAIL scan;
`appendEvent` 本體不動，F8 parity unaffected); nightly stress uses the true
`appendEvent` shaping.

| PR slim (fast, gate) | Nightly full (`NIGHTLY_STRESS=1`, non-gate) |
|---|---|
| `ChatSnapshotByteCapTest.overBudgetPrefix_loadBoundedAndSnapshotAbandonsWholeWithMarker` (11 turns, 20 fit / 2 dropped) | `ChatSnapshotByteCapFullStressTest.overBudgetPrefix_full200Rows_abandonsWholeWithMarker` (100 turns, 20 fit / 180 dropped) |
| `SnapshotFiveDimPhase4Test.byteCapOverBudget_fiveDim` (11 turns) | `ChatSnapshotByteCapFullStressTest.byteCapOverBudget_fiveDim_full200Rows` (100 turns) |
| `ChatSnapshotByteCapTest.cleanFenceOverBudgetFork_surfacesIncompleteNoticeImmediately` (12 turns, pre-redacted fast path) | (same ledger shape covered by the two full tests above; no separate nightly) |

Issue #86 Phase A root fix (Current, production change — `Redactor` only):
single-scan per rule (`replaceAndCount`, replacing the
`findAll().count()`+`replace()` double scan; `$1` semantics unchanged),
fail-closed literal prechecks per rule (miss-then-skip only on necessary
conditions: `@`+`.tld`-dot, `bearer`, `://`+`@`, `?`/`&`+`=`, `09`, `+`,
`1`/`2`, key words, path prefixes, digit presence), a linear `@`-anchored
EMAIL scan (equivalent to the regex `replaceAll` — maximal local run +
anchored domain per `@`, disjoint runs keep it O(n); possessive local part
kept as defense in depth), and a digit precheck in `applyCardRule`. Output
text and per-rule hit counts are bit-identical (pinned by `RedactorTest` 71
tests + corpus + `SecretValueRedactionRegressionTest` 15 tests + new
`RedactorPerfRegressionTest` 6 tests: homogeneous 100k passthrough with zero
hits, single 100k <500ms, `@`-bearing probes <500ms with dotless passthrough,
21-shape EMAIL differential vs the legacy greedy regex, 200-row equivalent
<60s). Measured (Foss, post-fix, `testFossDebugUnitTest` JUnit XML):
`RedactorPerfRegressionTest` 6/6 in ~1.2s (incl. 200-row equivalent ~1.0s,
`@`-probes ~0.01s); PR-slim `ChatSnapshotByteCapTest` 4/4 in ~0.5s;
nightly-only `ChatSnapshotByteCapFullStressTest` 2/2 in ~1.4s with
`NIGHTLY_STRESS=1` (true shaping). The Phase B slim/nightly split and Phase C
gates are retained (defense in depth, still reject wasteful huge loops).

What runs by edit type:

- Docs-only (neither `code` nor `policy`, e.g. `docs/**` or `*.md` edits): unit tests, lint, and build/policy are skipped; `docs-guard` and `pr-gate` still run.
- Policy-script-only (any `policy` path edit, `code == false`): build/policy and the stdlib Python policy harness run; unit tests and lint are skipped and resolved as pass by `pr-gate`. The harness runs both the Python unit tests AND the repo-wide `python3 scripts/check_test_budgets.py` scan (PR #87 P2-4: script-only diffs skip the `unit-tests` job that otherwise hosts that gate, so the scan is wired into `policy-harness` too — pinned by `PolicyCiGateControl.test_policy_harness_runs_repo_budget_guard`). A scanner-only diff therefore cannot pass with all policy validation skipped.
- Workflow-only edit to `pr-check.yml`: counts as both `code` and `policy`, so all Gradle stages plus the Python policy harness run. The harness pin tests therefore execute exactly when the wiring they validate changes.
- Mixed docs + code/policy edits: full Gradle stages plus `docs-guard` run.
- Stacked PRs (base is not `main`, e.g. a fix branch targeting a feature branch): `pr-check.yml` only triggers on `main`, so no PR Check runs attach to the stacked PR itself — verification rests on local runs (base SHA, exact commands, exit status recorded) plus `workflow_dispatch` evidence; merging the stack into the feature branch re-enters that branch's own `main`-targeted gate, which is where the three-flavor green must be observed before closing the related issue.

Static analysis lives outside `pr-check.yml`: CodeQL runs in
`.github/workflows/codeql.yml`, which skips docs-only push/PR events via
`paths-ignore` (`docs/**`, `**/*.md`, `LICENSE*`, `TRADEMARKS.md`,
`NOTICE*`) while the weekly cron and `workflow_dispatch` runs stay
unfiltered. Secret scanning (`gitleaks`) and `dependency-review` in
`.github/workflows/security-audit.yml` still run on every pull request,
including docs-only ones.

`main` configures branch protection and lists `PR Gate` as a required status
check (with `Dependency Vulnerability Review` and `Secret Leak Detection
(Gitleaks)`); `pr-gate` fails when `docs-guard` is not success, so the
checks above — the docs gate in particular — block merge. `enforce_admins`
is not enabled, repository rulesets are 0, required approving reviews are
0, and CODEOWNERS is not configured, so the required status checks are the
enforcement (not review or admin enforcement). Re-check the current
configuration with `gh api repos/<owner>/<repo>/branches/main/protection`.

The workflows do **not** run an Android emulator/device matrix on every pull request. The Python APK policy and release-identity harnesses (standard library only) run in CI as the `policy-harness` job whenever `policy == true`, and `pr-gate` requires its success in that case. The command is `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_apk_policy_check.py scripts/tests/test_release_artifact_verifier.py scripts/tests/test_build_release_identity.py scripts/tests/test_summarize_junit.py scripts/tests/test_check_test_budgets.py scripts/tests/test_check_junit_durations.py scripts/tests/test_gradle_wrapper_checksum.py`. These tests use synthetic APK-like files and fake Gradle/SDK/policy commands; they do not prove real APK or SDK behavior. A green unit/lint/policy workflow is not proof of runtime safety, provider compatibility, signing identity, or physical-device behavior.

Issue #86 Phase C guardrails (Current, gates — only reject/alert, never
silently downgrade redaction or writes):
- `scripts/check_test_budgets.py` (covered by
  `scripts/tests/test_check_test_budgets.py`) runs BEFORE the unit-test suite in
  each flavor job over all test source sets (`app/src/test`, `app/src/testFoss`,
  `app/src/testGithub`, `app/src/androidTest`): H1 rejects `repeat(N>=10)` loops writing huge fixtures
  (`100_000`/`100_001`/`huge`/`big`) through the true `.appendEvent(` shaping
  (use `appendPreRedactedForBudgetTest` or move to nightly; only
  `ChatSnapshotByteCapFullStressTest.kt` is allowlisted); H2 rejects
  `repeat(N>=2000)` with `.appendEvent(`. Local: `python3
  scripts/check_test_budgets.py`.
- `scripts/check_junit_durations.py` (covered by
  `scripts/tests/test_check_junit_durations.py`) runs AFTER the suite in each
  flavor job and fails when any single test class exceeds 120s (same JUnit XML
  glob as `summarize_junit.py`, which stays reporting-only). Durations are
  evaluated with two-level aggregation: intra-flavor suites/shards of the same
  class are accumulated (summed) against the 120s threshold, while cross-flavor
  runs evaluate the peak (maximum) duration per class to avoid false-positive
  timeouts in multi-flavor local runs (`./gradlew test`). No XML skips green
  (Gradle governs that case). Local: `python3 scripts/check_junit_durations.py`
  (needs `app/build/test-results/**/TEST-*.xml` from a prior Gradle run).
- Known slow classes (budgets, Current): PR-slim byte-cap
  (`ChatSnapshotByteCapTest`, 11-turn + 12-turn + 2000 small rows) and
  `SnapshotFiveDimPhase4Test.byteCapOverBudget_fiveDim` must stay well under
  120s per class; nightly-only `ChatSnapshotByteCapFullStressTest` (200 rows,
  true shaping) is exempt from the PR gate by construction (skips without
  `NIGHTLY_STRESS=1`) and has its own 35-minute nightly timeout.
- Per-class Gradle sharding with independent per-class timeouts is Proposed
  (not wired): today the per-flavor job timeout (`timeout-minutes: 20`) plus
  the two gates above are the enforcement. Do not claim sharded execution until
  it is wired and run.

## Current policy gates, tool-contract pins, and honest skips (P2 activation)

### Artifact-level backup binding gate (Current, policy gate)

The backup boundary has two tiers, and only the artifact tier proves the
shipped package. Source level: `scripts/backup_rules_check.py` validates the
five key-file excludes (`REQUIRED_EXCLUDES` — sharedpref `librepocket_keys.xml`,
database `librepocket_vault`/`-wal`/`-shm`, file `librepocket_keysets`) inside
the correct `<cloud-backup>` / `<device-transfer>` branches, with the file↔root
binding required at every call surface (`--expect-root full-backup-content` /
`--expect-root data-extraction-rules`; a swapped file is silently ignored by
the Android runtime parser). `--check-manifest` pins the manifest
`android:fullBackupContent` / `android:dataExtractionRules` attributes to
`@xml/backup_rules` / `@xml/data_extraction_rules` (`EXPECTED_MANIFEST_ATTRS`;
`--require-both` on the main manifest, overlays fail closed only on a
present-but-different value or on a `tools:` marker that strips the binding).
The covering tests (`scripts/tests/test_apk_policy_check.py`, classes
`BackupRulesValidator` and `ApkPolicyHarness`) run inside the harness command
above (no Gradle). The operative claim is: source-level PASS alone never proves
the artifact — the merged APK binding bytes are asserted independently by the
artifact-level scanner (`test_merged_backup_bindings_stripped_rejected_both_modes`,
`test_merged_backup_binding_outside_application_rejected`,
`test_merged_backup_bindings_missing_resource_table_rejected`), because an XML
comment can hide a deleted attribute at source level
(`test_manifest_comment_survivor_no_longer_fools_the_check`) and a merger
marker can strip the binding at merge time. The merged-manifest value itself is
also proven, per attribute, through `aapt dump resources` (one shared dump per
scan): an `@0x…` id must resolve to the pinned xml name, a reference-NAME
literal (older aapt's print of a typed reference) must match the pin over the
WHOLE decoded value and the name must be carried by the same table
(`test_merged_reference_bindings_resolve_through_table_both_modes`,
`test_backup_binding_string_branch_full_value_equality`,
`test_merged_nsc_unresolvable_id_and_literal_name_proven_through_table`), and
any other payload — e.g. `@xml/backup_rules;junk` — is a raw string the runtime
would never dereference, so it fails closed
(`test_merged_backup_binding_literal_with_trailing_junk_rejected`). The two
binding authorities share that single resources dump
(`test_e2e_resources_dump_runs_once_per_scan`); the harness's synthetic
APK-like fixtures prove scanner logic only; for a real artifact the artifact
tier is the post-assembly `scripts/play_policy_check.sh` stage (CI assembly
step above), and no synthetic fixture run substitutes for it.

### Compiled rule bytes, Github mode, and the Play permission blacklist (Current, policy gate)

Three review-driven additions to the same tier. First, the artifact scanner
(`scripts/apk_policy_inspect.py`) asserts the COMPILED backup rule resources
(`res/xml/backup_rules.xml` / `res/xml/data_extraction_rules.xml`, dumped via
`aapt dump xmltree`, identical member forms as the NSC scan) with the very
same engine as the file-level gate (`backup_rules_check.validate_tree`), in
exact-set mode: the compiled exclude set must equal `REQUIRED_EXCLUDES`
EXACTLY — a missing entry fails as usual AND an unexpected extra is a set
difference, order-free — because a resources overlay can rewrite the
handlers' XML while the manifest binding and source files all stay faithful
(`test_compiled_backup_rules_missing_exclude_rejected`,
`test_compiled_backup_rules_dump_failures_fail_closed`,
`test_compiled_backup_rules_evaluate_via_shared_engine`). Second, the Github
debug flavor is not a play or foss artifact, so its compiled bytes must not
escape the gate either: pr-check.yml's github step runs the scanner's
`--mode github` right after the play/foss policy gate - the scanner mode
itself executes only the mode-independent artifact authorities (merged
backup bindings, compiled rules bytes, compiled NSC), and the SAME step
additionally runs the common five-token source scan (see P2-1 below) -
with no flavor-DEX class-table policy, matching the scanner's own
docstring (that flavor ships proprietary components by design)
(`ApkPolicyHarness.test_github_mode_passes_mode_independent_authorities`;
the wiring is pinned by `PolicyCiGateControl`). Third, the Play source
permission blacklist is enforced by `backup_rules_check.py`'s
`validate_permission_removals` (CLI: `--check-permission-removals
<perm,perm,...>`; the shell gate passes the play lane's six tokens, the
foss/github lanes the common five-token subset - see the P2-1 note below). It
requires
every `<uses-permission>` / `<uses-permission-sdk-23>` element whose
android:name matches a blacklisted token to carry `tools:node="remove"` ON
THE ELEMENT (the merger marker that provably deletes a whole declared
element). This replaces the retired per-line `grep -vE "tools:node=..."`
filter, which false-FAILED the legal multi-line spelling (android:name and
the marker on different lines) and could not separate real elements from XML
comments
(`PermissionRemovalValidator.test_multiline_marked_removal_is_accepted`,
`BackupRulesGateEndToEnd.test_multiline_marked_permission_removal_no_longer_false_fails`,
`BackupRulesGateEndToEnd.test_unmarked_blacklisted_permission_fails_at_source_stage`).

P2-1 round 2 closes the flavor asymmetry left around that third point. The
locked common manifest decision (NO SMS, NO MANAGE_EXTERNAL_STORAGE, NO VPN in
ANY flavor) is enforced ON the artifact face for foss and github too:
`_check_common_permissions` runs in every mode BEFORE the play/foss/github
split, so a github-mode APK carrying a common-token uses-permission (or a bare
`<service>` / intent-filter spelling out a VpnService subclass in the merged
xmltree) fails even though github mode has no permission scan of its own
(`ApkPolicyHarness.test_common_forbidden_permissions_rejected_in_every_mode`,
`ApkPolicyHarness.test_github_mode_fails_on_common_permission_only`,
`ApkPolicyHarness.test_vpn_service_reference_rejected_by_common_check`). In
play mode a common-token finding intentionally surfaces with the shared
`_check_common_permissions` wording ("common flavor policy: fail-closed in
every mode") ahead of the play-only wording, and the retired play-mode
vpnservice regex is subsumed by that common reference scan — reporting text
only, the fail-closed behavior is unchanged. The
`BIND_ACCESSIBILITY_SERVICE` token deliberately stays a play-only extra: the
foss and github overlays legally declare accessibility services, so that token
must never join the common set
(`ApkPolicyHarness.test_accessibility_token_stays_play_only`,
`ApkPolicyHarness.test_common_permissions_function_accepts_accessibility_surface`).
At source level the shell gate picks a per-mode token set (play = full
six-token CSV, foss/github = the common five tokens) fed through the same
structured CLI over the PER-LANE universe (P2 round 3, replacing the
retired "main + flavor overlay" two fixed paths): main and the mode's own
flavor overlay are checked by fixed path (missing file = FAIL named,
never skipped), then every glob-encountered `app/src/*/AndroidManifest.xml`
is case-filtered into the lane universe — KEPT when it is a buildType
overlay (`app/src/debug` / `app/src/release`, applicable to every flavor)
or the lane's own overlay / `<flavor><BuildType>` variant overlay (exact
name or flavor-name prefix match: `playRelease`, `fossDebug`,
`githubRelease`, ...) — and SKIPPED otherwise, because other flavors'
overlays (the play overlay must never trip a foss-lane GMS needle) and the
test source sets (`test*` / `androidTest`) never merge into an APK of that
lane. The foss proprietary-string needle scan follows the same
case-filtered universe, not a sweep over every manifest. This closes the
reviewer escape where a rogue `app/src/release/AndroidManifest.xml`
(merging into every flavor's release variants but shipped by no Debug
APK) stayed green through all three lanes
(`BackupRulesGateWiring.test_gate_enforces_play_blacklist_via_structured_scan`,
`BackupRulesGateEndToEnd.test_release_manifest_rogue_permission_fails_every_lane`,
`BackupRulesGateEndToEnd.test_flavor_variant_manifest_rogue_permission_fails_its_lane_only`,
`BackupRulesGateEndToEnd.test_play_overlay_rogue_permission_fails_play_not_foss`,
`BackupRulesGateEndToEnd.test_main_manifest_rogue_permission_fails_every_lane`,
`BackupRulesGateEndToEnd.test_clean_release_overlay_before_play_needle_sweeps_nothing`).
The pr-check github assembly step runs the common five-token source scan
over the same per-lane case-filtered universe in the same step as the
artifact scan
(`BackupRulesGateWiring.test_gate_enforces_common_blacklist_in_every_mode`,
which also cross-checks the shell common set against the scanner's
`COMMON_FORBIDDEN_PERMISSIONS` tuple;
`BackupRulesGateEndToEnd.test_github_lane_rejects_unmarked_common_permission`,
`BackupRulesGateEndToEnd.test_github_lane_accepts_marked_removal_and_clean_overlay`).

### Release-variant artifact authority (Current, policy gate)

The Build & Policy Gates stage additionally assembles all three release
variants (`:app:assemblePlayRelease :app:assembleFossRelease
:app:assembleGithubRelease` together with the debug tasks) and applies the
debug-tier artifact policy to each lane's release APK in the same job:
the shell gate for Play and Foss (`scripts/play_policy_check.sh` /
`--foss` over `app/build/outputs/apk/{play,foss}/release/*.apk`) and the
mode-independent artifact authorities (`apk_policy_inspect.py --mode
github`) on the github release output. The release APKs are UNSIGNED (the
job runs without `-PreleaseSigning`, so `app/build.gradle.kts` assigns no
signing config and AGP emits `app-<flavor>-release-unsigned.apk`); the
output-dir glob is naming-drift-proof and fails closed when no APK was
produced. This tier is the artifact face of the same P2 escape: a rogue
`app/src/release/AndroidManifest.xml` previously merged into an APK that
PR CI never scanned until release staging
(`PolicyCiGateControl.test_release_variants_assembled_and_policy_applied`).
The upload step keeps both faces (`Upload Debug & Release APKs`, artifact
`assembled-apks`).

Release staging adds a same-ruler binding over the staged snapshot bytes: the
local `build_release.sh` → `release_artifact_stage.py` delegate forwards the
verifier's caller-validated `aapt` through the gate's `--aapt` override, so
`verify_release_apk.py` and `play_policy_check.sh` read one staged set of
bytes with one aapt binary rather than two independently discovered tools;
the CI gate above keeps automatic aapt discovery.

Two adjacent shell findings were made fail-closed in the same pass. A missing
flavor/overlay manifest no longer makes the whole structured permission stage
silently absent (the retired `[ -f "$m" ] || continue` skip): every lane now
fails naming the path
(`BackupRulesGateWiring.test_gate_missing_manifest_fails_closed_not_skipped`,
`BackupRulesGateEndToEnd.test_missing_flavor_manifest_fails_named_not_skipped`).
And the raw cleartext scan observes POSIX grep marking (0 = violation, 1 =
clean, anything else = filesystem error, so the retired `2>/dev/null`
pipeline could swallow rc 2 and silently PASS an unreadable subtree) and
accepts every legal spelling: single-quoted values and whitespace around "="
inclusive
(`BackupRulesGateWiring.test_gate_cleartext_scan_is_quote_and_rc_honest`,
`BackupRulesGateEndToEnd.test_cleartext_scan_matches_all_legal_spellings`).
The semantic-shape deep scans widen their NAME universes only: a rules
resource may be spelled with dashes (data-extraction-rules.xml) and the NSC
deep scan covers `*network*security*.xml` (network-security.xml without the
config segment included) - a name widening can only ADD files to the scan,
because each found file is still validated fail-closed by its own root/shape
checker
(`BackupRulesGateWiring.test_gate_deep_scans_alias_rule_names`,
`BackupRulesGateEndToEnd.test_dash_alias_rules_files_are_deep_scanned`,
`BackupRulesGateEndToEnd.test_nsc_alias_without_config_segment_is_deep_scanned`).

Two engine additions harden the rule language itself: any rule `path`
containing `..` is refused outright (Android's rules schema resolves paths
INSIDE the rule domain; a parent-directory reference is not something a
file-level gate can verify, so failure is closed)
(`test_rule_path_containing_parent_dir_is_refused`), and Android 16 QPR2
(API 36.1) added `<cross-platform-transfer>` as a THIRD platform-supported
section - the gate treats it as an undecided transfer policy and refuses the
element outright rather than silently validating or skipping it
(`test_cross_platform_transfer_section_is_refused`).

### Manifest pin semantics: tools: markers and quote normalization (Current, policy-harness tests)

`backup_rules_check.py` parses with stdlib ElementTree, so manifest claims are
structurally checked, not grepped. Pinned semantics (all in
`scripts/tests/test_apk_policy_check.py`, class `BackupRulesValidator`):

- Quote normalization: plain double-quoted attributes AND single-quoted
  (equally valid XML) parse to the same bound values
  (`test_manifest_structured_binding_passes`) — the old byte-grep gate failed
  the single-quoted spelling; structured parsing ends that false-FAIL.
- Comment-slap resistance: attributes deleted while the literals survive only
  inside an XML comment must fail closed, both `--require-both` (main) and as
  legal-absence inheritance (overlay)
  (`test_manifest_comment_survivor_no_longer_fools_the_check`).
- `tools:` merger-marker semantics: `tools:remove` naming
  `fullBackupContent`/`dataExtractionRules` while absent, or a
  `tools:node="replace"` / `tools:node="remove"` on `<application>` without
  re-declaring both pinned values, deletes the merged binding and fails closed
  (`test_overlay_tools_remove_backup_attrs_fails_closed`,
  `test_overlay_tools_node_replace_without_attrs_fails_closed`,
  `test_overlay_tools_node_remove_application_fails_closed`); a root-level
  marker naming the attributes is an undefined shape and is refused
  (`test_root_manifest_level_marker_naming_backup_attrs_rejected`). Benign
  markers stay legal: `tools:ignore`/`tools:insert` never strip the binding
  (`test_overlay_benign_tools_markers_still_pass`) and each pin kept as its
  own re-declaration passes (`test_overlay_marker_with_own_pinned_values_passes`).
- `tools:*` attributes are excluded from runtime-attribute counts because aapt
  strips them before the runtime parses the rules (companion negative checks
  in the same class); at artifact level an aapt2-spelling merged binding that
  resolves through the resource table is accepted
  (`test_merged_backup_bindings_aapt2_spelling_and_literal_raw_pass`) and a
  misbound/rebound one is rejected
  (`test_merged_backup_binding_attr_with_wrong_printed_id_rejected`,
  `test_merged_backup_binding_literal_rebound_name_rejected`).

### Validation before capability branches; USER_DISABLED-first; bounded echoes (Current, tool contract)

The systema tools pin three contracts. (1) Parameter validation runs before
any capability branch: every tool resolves its `IntentSpec` through the shared
`guardedToolBuild` helper (defined in `IntentSpec.kt`), so an out-of-range
parameter converges to `ReasonCodes.INTERNAL` on every executable branch
(`IntentAdapterStage1Test.guardedToolBuild_illegalArgument_mapsCauseTaggedInternal`
pins the fail-closed INTERNAL mapping), and a degraded/no-app capability state
must not skip validation. (2) USER_DISABLED-first: a disabled capability
short-circuits ahead of parameter questions —
`IntentAdapterStage1Test.execute_userDisabled_returnsDisabledNotNoHandler`
pins that disabled users get `ReasonCodes.USER_DISABLED` on AlarmTool,
VolumeTool, MusicTool, LaunchAppTool and all CalendarTool execute forms with
`usedFallback=false`, `spec=null`, and the
launcher never invoked (never reinterpreted through fallback as NO_HANDLER
copy), and that disabled + invalid parameters still answer
`ReasonCodes.USER_DISABLED`, not INTERNAL (capability question precedes the
parameter question, exactly one exception to contract (1));
`CalendarFullTest.updateDisabledIsUnavailable` pins the check-side denial.
(3) Bounded echoes: failure copy echoes at most `LaunchAppTool.MAX_QUERY_ECHO_LEN`
(32) / `CalendarTool.MAX_EVENT_ID_ECHO_LEN` (64) characters —
`IntentAdapterStage1Test.launchApp_unknown_echoCappedInCopy` and
`IntentAdapterStage1Test.calendar_eventIdEcho_cappedInFallbackCopy` assert
the cap-length substring present and cap+1 absent in both `execute` and
`fallback` copy, while spec/URI construction keeps the full value (the cap is
anti-echo for message text, deliberately not a parameter-validation cap);
LaunchApp clarification copy additionally truncates each candidate to
`LaunchAppTool.MAX_CANDIDATE_ECHO_LEN` (64, same anti-echo policy) pinned by
`IntentAdapterStage1Test.launchApp_clarification_candidates_echoCapped`.

### Skill subsystem fail-closed evidence (Current, #15)

The skill installer/registry fail closed on shape doubts, with exact reasons —
not OR-converged copies — pinned per test in `SkillInstallTest`:
`pathTraversalRejected` (`SkillInstaller.Failure.TRAVERSAL`),
`absolutePathRejected` (`ABSOLUTE_PATH`, exact — an absolute entry hits the
per-entry name gate before the summary gates, so it never reaches the
multiple-roots lane; that lane is pinned separately in
`multiRootPackage_rejected_exactReason`),
`missingSkillMdRejected` (`MISSING_SKILL_MD`),
`badFrontmatterRejected_missingName` (`BAD_FRONTMATTER`),
`nameMismatchRejected` (`NAME_MISMATCH`), and
`filesAndZipShareSameValidation_unknownToolRejected` (`UNKNOWN_TOOL`; the same
payload is driven through both the files lane and the compressed-zip lane, so
the shared-validation claim is evidenced rather than inferred — a rejected
ref-name rejects both). The
fail-closed destRoot probe group is additionally pinned in
`SkillStartupRecoveryTest`: the destRoot probe
`isSymlinkOrUnknown` treats undecidable attributes as unsafe
(install into a nonexistent destRoot still succeeds; a cleanup on a
symlink/unknown destRoot is a no-op — `cleanup_symlinkDestRoot_isNoop` — and
`listResiduals_symlinkDestRoot_isEmpty` lists nothing), symlink classification
runs before the age gate so fresh link-shaped staging is unlinked in the same
run (`cleanup_freshLinkStagingShape_unlinkedRegardlessOfAge`, unlink-only,
external contents survive), and a symlinked or unreadable SKILL.md never
restores or reports a version
(`installedVersion_symlinkSkillMd_returnsNull`,
`installedVersion_symlinkSkillDir_returnsNull`). The restore content proof
group is pinned in `SkillAtomicCommitTest`: a target-less single `.bak`
restores only when `bakManifestName` proves a real, non-symlink,
`MAX_SINGLE_FILE_BYTES`-bounded, parseable SKILL.md whose manifest name equals
the bak id — otherwise it converges to KEPT (still listed by
`listResiduals`), with its four cases pinned separately as
`staleBakWithoutTarget_manifestNameMismatch_keptNotRestored`,
`staleBakWithoutTarget_missingOrCorruptSkillMd_keptNotRestored`,
`staleBakWithoutTarget_oversizedSkillMd_keptNotRestored`, and
`staleBakWithoutTarget_symlinkSkillMd_keptNotRestored`. Cleanup and restore
work are budgeted per run and the caps bind **attempts**, not successes: the
deletion budget `MAX_CLEANUP_ENTRIES_PER_RUN` (64) and the same-source
independent restore budget `MAX_RESTORES_PER_RUN` (16) each count a work
attempt consumed at the lane entry **before the lane's first I/O** (unlink /
`deleteTreeSafe` / the restore lane's post-ambiguity content proof and rename;
"lane I/O" = paid workload — tree walk / unlink / rename / SKILL.md read+parse —
while per-entry enumeration and the NOFOLLOW classification probes are fixed
O(1)-per-entry (O(N) per run) costs outside the budget, paid even past the cap),
so failures consume the budget too (wrong-name or corrupt baks
that fully read+parse SKILL.md, denied renames, partially failing delete
walks); past the cap a candidate is KEPT with zero lane I/O this run (no
tree walk/unlink/rename or SKILL.md read+parse — enumeration and
classification probes remain O(N)) and the same
startup budget runs converge on later startups
(`cleanup_restoreBudget_cappedPerRun` for the all-succeed shape;
`SkillAtomicCommitTest.restoreAttempts_cappedOnFailures_goodBakWaitsNextRun`,
`SkillAtomicCommitTest.proofAttempts_stopAtCap_onExpensiveCorruptBaks`, and
`SkillAtomicCommitTest.deleteAttempts_hookSkipFail_cappedExactly` pin the
failure-heavy shapes via the `cleanupAttemptsForTest` seam and the
skip-fail `deleteHook`; consuming the independent restore budget while the
deletion budget is exhausted mid-run is pinned by
`cleanupBudget_midRun_restoreExempt`). The ambiguity lane deliberately stays
free of restore attempts (ambiguous baks never converge and would otherwise
starve later legal crash-baks), and the returned count remains the success
count (restores + deletes), not the attempt tally.
`SkillAtomicCommitTest` pins the replace path to
all-or-nothing: staging-rename/backup/rollback failures keep the old bytes
intact with no half-product and no leftovers, reporting `IO_ERROR` with the
backup location when rollback itself fails. The install commit re-verifies the
target slot with the same NOFOLLOW three-state probe immediately before the
rename (the
probe→commit swap window is fail-closed: a link — dangling or pointing outside
— or undecidable attributes converge to `IO_ERROR` "target must not be
symlink" with staging cleared; a real non-link entrant under `!replace`
converges to `ALREADY_EXISTS` with the incumbent untouched). The race shape
itself has no injection seam in the JVM test suite (same honesty tier as the
destRoot unreadable-attrs case) — the contract is code + KDoc consistency,
verified only by manual audit; the pinned link-refusal tests
(`installOntoSymlinkTarget_isRefused_ioError`,
`installOntoDanglingLinkTarget_isRefused_ioError_withControls`) cover the
probe-time refusal that precedes it.

### Honest Assume-skip semantics (Current, skip≠pass)

Environment-dependent tests skip with JUnit `Assume` instead of faking a pass
or polluting the run with unrelated reds: `ChatSnapshotByteCapFullStressTest`
requires `NIGHTLY_STRESS == "1"` via `requireNightly()` (`assumeTrue`), so the
full 100-turn/200-row stress skips on PR runs and only executes in the
nightly job; `LinuxSmokeTest` skips the host-`proot` comparison behind
`Assume.assumeTrue("no host proot; guest-path smoke above is the CI signal",
proot)`; `AndroidEncryptedPrefsTest` uses
`Assume.assumeNoException("Android Keystore unavailable in this sandbox", e)`;
`ShellPhysicalGateTest` skips Windows and permission-unknown cases (chmod
unattributable to root detection), and `ScopedFileStoreB2Test` skips
Windows/non-POSIX symlink cases. A skip is never counted as a pass:
`scripts/summarize_junit.py` emits its `Status:` line as `PASS` only when
every XML file is readable, `tests > 0`, `failures == 0`, `errors == 0`, and
every suite's counters exactly equal its direct `<testcase>` detail, counting
`skipped` cases separately and surfacing them (with per-suite mismatches,
empty or contradictory totals, or unreadable files forcing `INCOMPLETE` /
`PARTIAL` / `NO_RESULTS` instead). `PASS` therefore means "no failures or
errors among the executed tests"; it does not require zero skips. Those
skips are disclosed rather than laundered: the report carries a separate
`skipped` metric row, lists every skipped case by ident under a
`**Skipped tests (N):**` heading (a truncated tail is replaced by an
explicit "omitted N failures / M skips; see XML artifact" notice —
never a silent drop), and the suite's own
`test_summarize_junit.py:test_long_skipped_ident_is_truncated` pins a run
with 1 skipped still rendering `PASS`, while a suite whose `skipped`
counter contradicts its skipped `<testcase>` detail is `INCOMPLETE`. Whether
the skip count should additionally block an overall green claim is a
reviewer-side judgment on the report, not an assertion the reporter makes.

## Local commands

Use one Gradle execution at a time on constrained machines. The repo's maintenance guidance requires `--max-workers=1 --no-daemon`; do not run a parallel flavor matrix after an OOM.

```sh
# Synthetic APK/DEX policy and final-artifact identity harnesses (Python stdlib only)
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  scripts/tests/test_apk_policy_check.py \
  scripts/tests/test_release_artifact_verifier.py \
  scripts/tests/test_build_release_identity.py \
  scripts/tests/test_summarize_junit.py \
  scripts/tests/test_check_test_budgets.py \
  scripts/tests/test_check_junit_durations.py \
  scripts/tests/test_gradle_wrapper_checksum.py

# Issue #86 Phase C guardrails (static, no Gradle)
python3 scripts/check_test_budgets.py
python3 scripts/check_junit_durations.py

# Docs/claims guard and its fixture self-test (static, no Gradle)
bash scripts/docs_claim_check.sh
bash scripts/test_docs_claim_check.sh

# Flavor unit/Robolectric tests
./gradlew :app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest --max-workers=1 --no-daemon

# Flavor lint
./gradlew :app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug --max-workers=1 --no-daemon

# Debug assembly
./gradlew :app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug --max-workers=1 --no-daemon

# Debug + unsigned-release assembly (release is unsigned without
# -PreleaseSigning; a direct assemble/bundle is exactly what CI policy runs)
./gradlew :app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug :app:assemblePlayRelease :app:assembleFossRelease :app:assembleGithubRelease --max-workers=1 --no-daemon

# APK-only artifact policy checks after successful assembly; AAB is unsupported
# and rejected before credentials/build by the local release builder.
scripts/play_policy_check.sh app/build/outputs/apk/play/debug/app-play-debug.apk
scripts/play_policy_check.sh --foss app/build/outputs/apk/foss/debug/app-foss-debug.apk
scripts/play_policy_check.sh --github app/build/outputs/apk/github/debug/app-github-debug.apk
```

Release-acceptance helpers (Issue #12, local-only, no secrets, no publishing;
the public release workflow stays frozen — see [Release](RELEASE.md)):

```sh
# Signer-pin registry shape check (production pins live outside the repo;
# the checked-in example is placeholder-only and must be rejected).
# NOTE: the first command is a negative control — expect exit 1.
python3 scripts/check_release_pins.py --pins scripts/release_signer_pins.example.json # expect FAIL
python3 scripts/check_release_pins.py --pins /path/to/release_signer_pins.json \
  --require-flavors 'play foss github'

# Tag <-> Gradle version consistency (tag supplied by the trusted caller;
# tag must equal the current versionName — replace v0.1.0 when it bumps).
python3 scripts/check_release_tag_version.py \
  --tag v0.1.0 --app-build-gradle app/build.gradle.kts

# Fake-keystore rehearsal: real verifier gate, synthetic bytes + fake tools,
# 10-case pass/reject matrix incl. blank-runner same-SHA re-verification.
python3 scripts/fake_keystore_rehearsal.py
```

The new `SignerPinRegistryTest` / `ReleaseTagVersionTest` classes live in the
already-wired `scripts/tests/test_build_release_identity.py` and
`FakeKeystoreRehearsalTest` in `scripts/tests/test_release_artifact_verifier.py`.
The `policy-harness` paths-filter includes the three helpers plus the example
JSON, so the harness runs them on helper-only diffs. The synthetic fixtures
prove the gate's fail-closed matrix only; they are not real-APK/SDK evidence,
not a signing/build rehearsal, and not a basis for closing Issue #12.

The local APK release integration is `scripts/build_release.sh`; it requires `--apk`, explicit per-flavor trusted identity values, explicit SDK parser paths, and an existing output parent before it reaches credential lookup or build. It remains a local staging/verification path only, not a publishing workflow or release-readiness claim. Its helpers `scripts/release_artifact_stage.py` and `scripts/verify_release_apk.py` (covered by `scripts/tests/test_release_artifact_verifier.py` and `scripts/tests/test_build_release_identity.py`) stage and verify the release artifact for that local path; `pr-check.yml` lists them only as `policy` path triggers (not as invoked CI commands), and `scripts/docs_claim_check.sh` Section A requires every script the workflow references to be documented here.

The synthetic Python fixtures and fake `aapt`/`dexdump`/`apksigner`/Gradle/policy commands test bounded parser, policy, identity, transaction, failure-cleanup, and process-cancellation behavior; they do not represent a real APK or prove Android SDK native-tool compatibility. A release/build maintainer should run the APK policy/native-parser gates against fresh debug APKs for all three flavors and record their paths, SHA-256 values, SDK build-tools version, command output, and tested commit. The separate signed-release verifier requires fresh controlled test fixtures/native-tool evidence and explicit trusted identity values; its synthetic harness alone does not establish that validation. Do not infer a pass from historical APK inventory. These are instructions, not a claim that they were run for this documentation change. Version sources are listed in [Build Environment](ENV.md).

The shared setup tests include local synthetic MockWebServer coverage for the model-directory transport and outcome mapping: keyless requests, declared-length and chunked wire-body limits, UTF-8 decoded-byte limits, gzip expansion, truncation, non-2xx/redirect handling, unsupported encoding, empty bodies, total timeout, and prompt caller cancellation. They also cover remote-versus-bundled setup status and stale refresh isolation. These cases do not contact models.dev; test source presence alone is not evidence that a test task passed.

## Android instrumented tests

Current scheduler/matrix truth (`.github/workflows/nightly-instrumented.yml`):
the workflow runs on its nightly schedule and `workflow_dispatch`; on
pull requests (types `labeled`, `synchronize`, `opened`, `reopened`) the
`instrumented-test` job executes only when the PR carries the
`run-instrumented` label (the job `if:` requires that label for PR
events, so an unlabeled PR shows as Skipped/neutral — not a pass — and
does not enter `pr-gate` merge evaluation). The `nightly-unit-stress` job
(Issue #86 Phase B, Foss `ChatSnapshotByteCapFullStressTest` with
`NIGHTLY_STRESS=1`) runs on schedule / `workflow_dispatch` only
(`if: github.event_name != 'pull_request'`), never on PR; JUnit XML retained
14 days, non-gating (not in `pr-check.yml` `pr-gate`). The emulator matrix is API
levels 33 and 34 only, across the Play/Foss/Github flavors — while
`minSdk` is 33, `compileSdk` is 37 and `targetSdk` is 36
(`app/build.gradle.kts`), so there is currently no API 35, 36, or 37
emulator coverage in CI. This paragraph states Current coverage only;
any matrix expansion is Proposed until it is wired and run.

Instrumented tests are a separate device-dependent tier. The existing [P1 androidTest runbook](specs/P1_ANDROIDTEST_RUNBOOK.md) lists test classes and sample commands, but its device matrix and prior status notes are not current CI evidence. Before treating a device matrix as a merge/release gate, maintainers must name the required API/flavor/device coverage, run it, and retain results tied to the tested SHA. Do not report a device pass when no device was used.

## Security-sensitive test expectations

For changes involving credentials, policy, persistence, session lifecycle, cancellation, or tools:

- Add a regression test that fails before the fix and passes after it, plus a negative control when applicable.
- Use fake keys, temporary databases/files, synthetic transcript text, and local fake HTTP servers only. Do not use live credentials, production signing keys, paid endpoints, or private transcripts.
- Test fail-closed behavior and stale/late callbacks, not only the successful path.
- Keep tool states distinct: declared, implemented, wired, and verified. A test-only fake executor does not verify production wiring.
- Test execution contract: all asynchronous and coroutine waits must enforce a bounded timeout; teardown procedures, socket closures, and synthetic HTTP server (such as MockWebServer) requests must never block indefinitely. On a join timeout, run the collection in a detached scope, cancel it, release the peer latch/body/server (then client pools) with each close on a per-test daemon executor under its own wall-clock deadline via Future.get (on timeout the worker is cancelled and the remaining steps still run; Throwable diagnostics recorded, never silently swallowed) before the bounded observable re-join, so the parent scope cannot hang on a non-cooperative blocking read. Residual risk: a close that ignores interruption still pins its worker thread past its deadline; the backstop is the JUnit wall-clock timeout plus the CI job timeout (daemon threads only guarantee the stray worker never holds the JVM open).

Shell timeout boundary (Current): `ShellWhitelistTest.timeoutKillsSleep`
uses a hermetic `sleep` fixture (temp-dir `#!/bin/sh` infinite loop resolved
through `ShellExecutables` with a simulated device-unwritable search dir, so
the assertion does not depend on host `/bin/sleep` placement or host search-dir
writability) with `DefaultProcessRunner` and asserts `TimedOut` plus
`elapsedMs >= 400ms` (proves a real timeout wait, not an instant fake) and
`elapsedMs < 10s`. The 120s sleep argument is deliberately far above the kill
ceiling so a kill regression (falling through to natural exit) cannot pass
(the fixture loop ignores its argument); the 10s
cap is roughly 10x the normal ~0.5–1s kill path (`waitFor` 500ms +
`destroyForcibly` + per-stream gobbler `join` 5s×2), loose enough for loaded CI but tight
enough to catch a fully-degraded kill (~15.5s when every grace period fills:
0.5s wait + 5s destroy wait + 2×5s gobbler joins).
The passing-path cost stays ~1s: the long sleep only materializes as
extra wait when the kill itself regresses. The `writableFixture_*` negative
control proves the same fixture is denied with zero spawn when the search dir
is writable.

## Reporting evidence

Record the base SHA, exact commands, exit status, relevant test/report artifact, JDK/SDK/API/device where applicable, and checks not run. A planned command, an old runbook checkbox, or a successful build alone is not a security conclusion.

Flaky test resolution and debugging principles:

- Do not rely on silent retries to mask regressions or timing bugs.
- When test failures occur, investigate and capture forensic evidence via the Step Summary and console FULL stack trace (`--stacktrace`) captured in the workflow logs.
- Differential triage relies on the 14-day retention of test report artifacts (HTML reports and structured JUnit XML under `app/build/test-results/`) across attempts and runs.
