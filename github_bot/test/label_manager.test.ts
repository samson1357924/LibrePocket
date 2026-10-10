import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  BOT_MENTION,
  BOT_TRANSITIONABLE_GROUPS,
  COMMENT_MARKERS,
  DEFAULT_PR_RECONCILE_SCOPE,
  MUTEX_LABEL_GROUPS,
  REPO_ALLOWED_LABELS,
  applyBotLabels,
  applyLabelsSafely,
  ensureNeedsDecision,
  extractLabelsFromTriageText,
  isBotOwnedTransitionRemoval,
  isManagedByBot,
  isMutexLabel,
  isPrAiSuggestibleLabel,
  normalizeLabelName,
  parseReviewReport,
  reconcileBotLabelsSafely,
  resolveAreaLabelsFromPaths,
  resolveLabelsFromTitle,
  resolveReviewLabels,
  resolveTimelineBotWrittenLabels,
  sanitizeLabels,
  sanitizePrAiSuggestions,
  warnOnMutexCoexistence,
  type GitHubLabelClient,
  type LabelTimelineEvent,
} from '../src/label_manager';

class StatefulMockLabelClient implements GitHubLabelClient {
  private readonly labelsByIssue = new Map<number, Set<string>>();
  readonly addedCalls: Array<{ issueNumber: number; labels: string[] }> = [];
  readonly removedCalls: Array<{ issueNumber: number; name: string }> = [];
  listCalls = 0;

  constructor(initialData: Record<number, string[]> = {}) {
    for (const [issue, labels] of Object.entries(initialData)) {
      this.labelsByIssue.set(Number(issue), new Set(labels));
    }
  }

  getLabels(issueNumber: number): string[] {
    return Array.from(this.labelsByIssue.get(issueNumber) ?? []).sort();
  }

  get rest() {
    return {
      issues: {
        listLabelsOnIssue: async (params: { issue_number: number }) => {
          this.listCalls += 1;
          return { data: Array.from(this.labelsByIssue.get(params.issue_number) ?? []).map((name) => ({ name })) };
        },
        addLabels: async (params: { issue_number: number; labels: string[] }) => {
          this.addedCalls.push({ issueNumber: params.issue_number, labels: [...params.labels] });
          let labels = this.labelsByIssue.get(params.issue_number);
          if (!labels) {
            labels = new Set<string>();
            this.labelsByIssue.set(params.issue_number, labels);
          }
          for (const label of params.labels) labels.add(label);
          return {};
        },
        removeLabel: async (params: { issue_number: number; name: string }) => {
          this.removedCalls.push({ issueNumber: params.issue_number, name: params.name });
          this.labelsByIssue.get(params.issue_number)?.delete(params.name);
          return {};
        },
      },
    };
  }
}

function reportWith(body: string, verdict = 'NEEDS_CHANGES'): string {
  return `
${COMMENT_MARKERS.review}
## 🎯 角色裁決矩陣 (Verdict Matrix)
| 角色 | Reviewer | Verdict | 摘要 |
|---|---|---|---|
| **R-BE/SEC** | \`reviewer\` | **\`${verdict}\`** | finding |
### R-BE/SEC (reviewer, Verdict: ${verdict})
${body}
---
**\`FINAL_VERDICT=${verdict}\`**
`;
}

interface CapturedConsoleCall {
  level: 'warn' | 'error' | 'log';
  args: unknown[];
}

async function captureConsoleCalls<T>(action: () => Promise<T>): Promise<{ result: T; calls: CapturedConsoleCall[] }> {
  const originalWarn = console.warn;
  const originalError = console.error;
  const originalLog = console.log;
  const calls: CapturedConsoleCall[] = [];
  console.warn = (...args: unknown[]) => calls.push({ level: 'warn', args });
  console.error = (...args: unknown[]) => calls.push({ level: 'error', args });
  console.log = (...args: unknown[]) => calls.push({ level: 'log', args });
  try {
    return { result: await action(), calls };
  } finally {
    console.warn = originalWarn;
    console.error = originalError;
    console.log = originalLog;
  }
}

function containsMarker(value: unknown, marker: string, seen = new Set<object>()): boolean {
  if (typeof value === 'string') return value.includes(marker);
  if (value instanceof Error) {
    return value.message.includes(marker) || (value.stack?.includes(marker) ?? false) || containsMarker(value.cause, marker, seen);
  }
  if (value === null || typeof value !== 'object' || seen.has(value)) return false;
  seen.add(value);
  return Object.entries(value).some(([key, child]) => key.includes(marker) || containsMarker(child, marker, seen));
}

function assertNoSensitiveConsoleOutput(calls: CapturedConsoleCall[], marker: string, context: string): void {
  const leaked = calls.some((call) => call.args.some((arg) => containsMarker(arg, marker)));
  assert.equal(leaked, false, `${context}: raw API error data must not be logged`);
}

export async function runLabelManagerTests(): Promise<void> {
  // The allowlist is the exact repository taxonomy; case-insensitive input returns canonical spelling.
  assert.deepEqual(Array.from(REPO_ALLOWED_LABELS), [
    'area:runtime', 'area:platform', 'area:policy', 'area:delivery', 'area:docs',
    'status:needs-decision', 'security', 'performance', 'type:tracking', 'priority:P1', 'priority:P2',
    'gate:live', 'gate:release', 'gate:activation', 'gate:docs', 'status:verified-main',
    'status:partial', 'status:latent', 'accessibility', 'run-instrumented',
    'bug', 'enhancement', 'documentation', 'duplicate',
    'good first issue', 'help wanted', 'invalid', 'question', 'wontfix',
  ]);
  assert.equal(normalizeLabelName(' POLICY '), 'area:policy');
  assert.equal(normalizeLabelName('manifest'), 'area:delivery');
  assert.equal(normalizeLabelName('workflow'), 'area:delivery');
  assert.equal(normalizeLabelName('perf'), 'performance');
  assert.equal(normalizeLabelName('sec'), 'security');
  assert.equal(normalizeLabelName('docs'), 'area:docs');
  assert.equal(normalizeLabelName('needs-decision'), 'status:needs-decision');
  assert.equal(normalizeLabelName('priority:p1'), 'priority:P1');
  assert.equal(normalizeLabelName('TYPE:BUG'), undefined);
  assert.equal(normalizeLabelName('accessibility'), undefined);
  assert.equal(normalizeLabelName('run-instrumented'), undefined);
  assert.equal(normalizeLabelName('unknown-label'), undefined);
  assert.deepEqual(sanitizeLabels(['sec', 'security', 'policy', 'alien']), ['area:policy', 'security']);
  assert.deepEqual(sanitizeLabels(['accessibility', 'run-instrumented']), []);
  assert.equal(BOT_MENTION, '@pocketguard');
  assert.ok(Object.values(COMMENT_MARKERS).every((marker) => marker.includes('PocketGuard')));

  // Specific package mappings precede delivery/docs fallbacks; unknown paths stay unmapped.
  assert.deepEqual(resolveAreaLabelsFromPaths([]), []);
  assert.deepEqual(resolveAreaLabelsFromPaths(['random/file.kt']), []);
  assert.deepEqual(resolveAreaLabelsFromPaths(['src/unknown/package/File.kt']), []);
  assert.deepEqual(resolveAreaLabelsFromPaths([
    'app/src/main/java/dev/librepocket/chat/Chat.kt',
    'app/src/main/java/dev/librepocket/agent/github/Runner.kt',
    'app/src/main/java/dev/librepocket/agent/ui/AgentUi.kt',
    'app/src/main/java/dev/librepocket/ui/Screen.kt',
    'app/src/main/java/dev/librepocket/guard/Guard.kt',
  ]), ['area:platform', 'area:policy', 'area:runtime']);
  assert.deepEqual(resolveAreaLabelsFromPaths([
    'app/src/play/java/dev/librepocket/Flavor.kt',
    'app/src/foss/java/dev/librepocket/Flavor.kt',
    'app/src/github/java/dev/librepocket/Flavor.kt',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/res/xml/backup_rules.xml',
    'app/build.gradle.kts',
    'gradle/libs.versions.toml',
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'scripts/check.sh',
    '.github/workflows/release.yml',
    '.github/workflows/ci.yml',
    '.github/workflows/reusable/checks.yml',
  ]), ['area:delivery']);
  assert.deepEqual(resolveAreaLabelsFromPaths([
    'docs/specs/design.md',
    'SECURITY.md',
    'LICENSE',
    'NOTICE',
    'TRADEMARKS.md',
    'app/lint.xml',
  ]), ['area:docs']);
  assert.deepEqual(resolveAreaLabelsFromPaths([
    'app/src/test/java/dev/librepocket/provider/ProviderTest.kt',
    'app/src/androidTest/java/dev/librepocket/agent/foss/AgentTest.kt',
    'app/src/debug/java/dev/librepocket/backup/BackupMirror.kt',
  ]), ['area:platform', 'area:policy', 'area:runtime']);
  assert.deepEqual(resolveAreaLabelsFromPaths(['app/src/test/java/dev/librepocket/unmapped/Test.kt']), [
    'area:runtime', 'status:needs-decision',
  ]);
  assert.deepEqual(resolveAreaLabelsFromPaths(['app/src/test/java/dev/librepocket/unmapped/Notes.md']), [
    'area:runtime', 'status:needs-decision',
  ]);
  assert.deepEqual(resolveAreaLabelsFromPaths(['app/src/main/java/dev/librepocket/session/Session.kt'], false), [
    'area:runtime', 'status:needs-decision',
  ]);
  assert.deepEqual(resolveAreaLabelsFromPaths(['app\\src\\main\\java\\dev\\librepocket\\policy\\Guard.kt']), [
    'area:policy',
  ]);

  // Title labels are add-only and intentionally never synthesize issue type labels.
  assert.deepEqual(resolveLabelsFromTitle('fix: repair crash'), []);
  assert.deepEqual(resolveLabelsFromTitle('feat: add support'), []);
  assert.deepEqual(resolveLabelsFromTitle('docs: update guide'), []);
  assert.deepEqual(resolveLabelsFromTitle('perf(runtime): reduce work'), ['performance']);
  assert.deepEqual(resolveLabelsFromTitle('sec(policy): validate input'), ['security']);
  assert.deepEqual(resolveLabelsFromTitle('[Security/Perf] review the path'), ['performance', 'security']);
  assert.deepEqual(resolveLabelsFromTitle('[Type:Tracking] add issue metadata'), ['type:tracking']);
  assert.deepEqual(resolveLabelsFromTitle('tracking: backlog item'), ['type:tracking']);
  assert.deepEqual(resolveLabelsFromTitle('routine issue tracking'), []);
  assert.deepEqual(resolveLabelsFromTitle('[Section] update metadata'), []);

  // Review parser cascade, negation handling, and fail-closed human-decision labels.
  const approvedSecText = parseReviewReport(reportWith('No SQL injection vulnerability was found.', 'APPROVE'));
  assert.equal(approvedSecText.verdict, 'APPROVE');
  assert.equal(approvedSecText.hasSecurityFinding, false);
  assert.deepEqual(resolveReviewLabels({ verdict: 'APPROVE', hasSecurityFinding: false }), []);
  const securityBlock = parseReviewReport(`
## 🚨 前置確定性掃描檢驗違規 (Deterministic Violations)
- **[BLOCK] SEC-001**: Unsafe credential handling
${reportWith('A security flaw remains.')}`);
  assert.equal(securityBlock.verdict, 'NEEDS_CHANGES');
  assert.equal(securityBlock.hasSecurityFinding, true);
  assert.deepEqual(resolveReviewLabels({ verdict: 'NEEDS_CHANGES', hasSecurityFinding: true }), [
    'security', 'status:needs-decision',
  ]);
  const perfNegation = parseReviewReport(reportWith('No memory leak and no performance bottleneck were detected.'));
  assert.equal(perfNegation.hasPerformanceFinding, false);
  const perfFinding = parseReviewReport(reportWith('A significant performance bottleneck and memory leak exist.'));
  assert.equal(perfFinding.hasPerformanceFinding, true);
  const tableFallback = parseReviewReport('| **判定結果** | **`INCONCLUSIVE`** |');
  assert.equal(tableFallback.parseConfidence, 'table_anchor');
  assert.equal(tableFallback.verdict, 'INCONCLUSIVE');
  const matrixFallback = parseReviewReport(`
## 🎯 角色裁決矩陣 (Verdict Matrix)
| **R-ARCH/GOV** | \`reviewer\` | **\`APPROVE\`** | ok |
| **R-BE/SEC** | \`reviewer\` | **\`APPROVE\`** | ok |
| **R-PLATFORM** | \`reviewer\` | **\`APPROVE\`** | ok |
| **R-POLICY** | \`reviewer\` | **\`APPROVE\`** | ok |
`);
  assert.equal(matrixFallback.verdict, 'APPROVE');
  assert.equal(matrixFallback.parseConfidence, 'matrix_aggregated');
  assert.equal(parseReviewReport('unstructured').verdict, 'INCONCLUSIVE');
  assert.deepEqual(resolveReviewLabels({ verdict: 'INCONCLUSIVE' }), ['status:needs-decision']);

  assert.deepEqual(extractLabelsFromTriageText('Labels: policy, docs, sec, priority:P1, unsupported-tag'), [
    'area:docs', 'area:policy', 'priority:P1', 'security',
  ]);
  assert.deepEqual(extractLabelsFromTriageText('```json\n{ "labels": ["area:runtime", "alien"] }\n```'), [
    'area:runtime',
  ]);

  // Default reconciliation owns only area:* and status:needs-decision. All other taxonomy/human labels survive.
  assert.deepEqual(DEFAULT_PR_RECONCILE_SCOPE.managedPrefixes, ['area:']);
  assert.deepEqual(DEFAULT_PR_RECONCILE_SCOPE.managedExactLabels, ['status:needs-decision']);
  for (const protectedLabel of [
    'security', 'performance', 'type:tracking', 'priority:P1', 'gate:release', 'status:verified-main',
    'status:partial', 'status:latent', 'accessibility', 'run-instrumented',
    'bug', 'enhancement', 'documentation', 'duplicate',
    'good first issue', 'help wanted', 'invalid', 'question', 'wontfix',
  ]) {
    assert.equal(isManagedByBot(protectedLabel, DEFAULT_PR_RECONCILE_SCOPE), false, protectedLabel);
  }
  const managedMock = new StatefulMockLabelClient({
    1: [
      'area:runtime', 'area:docs', 'status:needs-decision', 'security', 'performance', 'type:tracking',
      'priority:P1', 'gate:release', 'status:verified-main', 'status:partial', 'status:latent', 'good first issue',
    ],
  });
  const managedResult = await reconcileBotLabelsSafely({
    client: managedMock,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 1,
    desiredLabels: ['area:policy'],
    scope: DEFAULT_PR_RECONCILE_SCOPE,
  });
  assert.deepEqual(managedResult.added, ['area:policy']);
  assert.deepEqual(managedResult.removed.sort(), ['area:docs', 'area:runtime', 'status:needs-decision']);
  assert.ok(managedMock.getLabels(1).includes('security'));
  assert.ok(managedMock.getLabels(1).includes('good first issue'));
  assert.ok(managedMock.getLabels(1).includes('priority:P1'));

  // P2 #4 mutex groups: priority, gate:*, verified statuses, bug/enhancement/documentation.
  // Owner decision (Phase 4, Owner B): bug/enhancement/documentation are
  // restored to PR AI; priority/gate/verified stay issue-triage-only.
  assert.ok(MUTEX_LABEL_GROUPS.some((group) => group.includes('priority:P1') && group.includes('priority:P2')));
  assert.ok(MUTEX_LABEL_GROUPS.some((group) => group.includes('gate:live') && group.includes('gate:release')));
  assert.ok(MUTEX_LABEL_GROUPS.some((group) => group.includes('status:verified-main')));
  assert.ok(MUTEX_LABEL_GROUPS.some((group) => group.includes('bug') && group.includes('enhancement')));
  for (const mutex of ['priority:P1', 'priority:P2', 'gate:live', 'gate:release', 'status:verified-main', 'status:partial', 'status:latent', 'bug', 'enhancement', 'documentation']) {
    assert.equal(isMutexLabel(mutex), true, mutex);
    assert.equal(isManagedByBot(mutex, DEFAULT_PR_RECONCILE_SCOPE), false, `${mutex} never blanket-managed`);
  }
  for (const stillIssueOnly of ['priority:P1', 'priority:P2', 'gate:live', 'gate:release', 'status:verified-main', 'status:partial', 'status:latent']) {
    assert.equal(isPrAiSuggestibleLabel(stillIssueOnly), false, `${stillIssueOnly} stays issue-triage-only`);
  }
  for (const prAllowed of ['area:runtime', 'area:docs', 'security', 'performance', 'status:needs-decision', 'bug', 'enhancement', 'documentation']) {
    assert.equal(isPrAiSuggestibleLabel(prAllowed), true, prAllowed);
  }
  for (const prAllowedNonMutex of ['area:runtime', 'area:docs', 'security', 'performance', 'status:needs-decision']) {
    assert.equal(isMutexLabel(prAllowedNonMutex), false, prAllowedNonMutex);
  }
  // PR convergence: priority/gate stay discarded (issue-triage-only) while
  // restored bug/enhancement/documentation are kept.
  {
    const prFiltered = sanitizePrAiSuggestions(['area:runtime', 'security', 'priority:P1', 'gate:release', 'bug', 'alien-label']);
    assert.deepEqual(prFiltered.kept, ['area:runtime', 'bug', 'security']);
    assert.equal(prFiltered.discardedCount, 3, 'priority/gate/alien discarded for PR; bug kept');
  }
  // P1+P2 coexistence: both survive, only a warning, never auto-removal.
  {
    const coexistenceMock = new StatefulMockLabelClient({ 20: ['priority:P1', 'priority:P2', 'area:runtime'] });
    const { result, calls } = await captureConsoleCalls(() => reconcileBotLabelsSafely({
      client: coexistenceMock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 20,
      desiredLabels: ['area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
    }));
    assert.deepEqual(result.removed, [], 'P1+P2 coexistence never auto-removes');
    assert.ok(coexistenceMock.getLabels(20).includes('priority:P1'));
    assert.ok(coexistenceMock.getLabels(20).includes('priority:P2'));
    assert.ok(calls.some((call) => call.level === 'warn'), 'mutex coexistence warns');
    // Direct helper also warns without removing.
    const direct = await captureConsoleCalls(async () => { warnOnMutexCoexistence(['priority:P1', 'priority:P2']); });
    assert.ok(direct.calls.some((call) => call.level === 'warn'));
  }
  // Bot never writes priority/gate on PRs: PR AI suggestions are filtered
  // through the convergence allowlist before reconcile, so a PR desired
  // never carries priority/gate (issue triage is the only writer of
  // priority/gate; bug/enhancement/documentation are writable from both issue
  // triage and PR AI per the Owner B restore).
  {
    const rawAi = ['priority:P1', 'gate:release', 'area:runtime'];
    const filtered = sanitizePrAiSuggestions(rawAi);
    assert.deepEqual(filtered.kept, ['area:runtime']);
    assert.equal(filtered.discardedCount, 2);
    const prMock = new StatefulMockLabelClient({ 21: [] });
    const prResult = await reconcileBotLabelsSafely({
      client: prMock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 21,
      desiredLabels: filtered.kept,
      scope: DEFAULT_PR_RECONCILE_SCOPE,
    });
    assert.ok(!prMock.getLabels(21).includes('priority:P1'), 'PR never writes priority');
    assert.ok(!prMock.getLabels(21).includes('gate:release'), 'PR never writes gate');
    assert.ok(prMock.getLabels(21).includes('area:runtime'));
    assert.ok(!prResult.removed.includes('priority:P1'));
    assert.ok(!prResult.removed.includes('gate:release'));
    assert.equal(isManagedByBot('priority:P1', DEFAULT_PR_RECONCILE_SCOPE), false);
    assert.equal(isManagedByBot('gate:release', DEFAULT_PR_RECONCILE_SCOPE), false);
  }
  // P2 #4 bot-owned vs human-owned + P1 #3 provenance: a structural
  // transition (desired carries a different peer of the same transitionable
  // group) removes the superseded label ONLY when provenance proves
  // bot authorship. Unknown/absent/failed provenance is warn-only preserve;
  // a human label with no desired peer is warn-only kept.
  {
    assert.ok(BOT_TRANSITIONABLE_GROUPS.some((group) => group.includes('priority:P1') && group.includes('priority:P2')));
    assert.ok(BOT_TRANSITIONABLE_GROUPS.some((group) => group.includes('bug') && group.includes('enhancement')));
    // Proven transitions (caller-verified bot-written set, case-insensitive).
    assert.equal(isBotOwnedTransitionRemoval('priority:P1', ['priority:P2'], { botWrittenLabels: ['priority:P1'] }), true, 'proven P1→P2 is a bot transition');
    assert.equal(isBotOwnedTransitionRemoval('bug', ['enhancement'], { botWrittenLabels: new Set(['bug']) }), true, 'proven bug→enhancement is a bot transition');
    assert.equal(isBotOwnedTransitionRemoval('priority:P1', ['priority:P2'], { botWrittenLabels: ['PRIORITY:p1'] }), true, 'provenance compare is case-insensitive');
    // Unknown provenance (opts absent) → false + warn, never delete.
    const unknownP1 = await captureConsoleCalls(async () => {
      assert.equal(isBotOwnedTransitionRemoval('priority:P1', ['priority:P2']), false, 'unproven P1→P2 stays');
    });
    assert.ok(unknownP1.calls.some((call) => call.level === 'warn'), 'unknown provenance warns');
    const unknownBug = await captureConsoleCalls(async () => {
      assert.equal(isBotOwnedTransitionRemoval('bug', ['enhancement']), false, 'unproven bug→enhancement stays');
    });
    assert.ok(unknownBug.calls.some((call) => call.level === 'warn'), 'unknown provenance warns');
    // Present-but-unproven set (existing label absent) → false + warn.
    const unprovenP1 = await captureConsoleCalls(async () => {
      assert.equal(isBotOwnedTransitionRemoval('priority:P1', ['priority:P2'], { botWrittenLabels: ['area:runtime'] }), false, 'P1 absent from proven set stays');
    });
    assert.ok(unprovenP1.calls.some((call) => call.level === 'warn'), 'unproven set warns');
    const unprovenBug = await captureConsoleCalls(async () => {
      assert.equal(isBotOwnedTransitionRemoval('bug', ['enhancement'], { botWrittenLabels: ['priority:P1'] }), false, 'bug absent from proven set stays');
    });
    assert.ok(unprovenBug.calls.some((call) => call.level === 'warn'), 'unproven set warns');
    // Non-transitions stay false regardless of provenance.
    assert.equal(isBotOwnedTransitionRemoval('priority:P1', ['area:runtime'], { botWrittenLabels: ['priority:P1'] }), false, 'human P1 without desired peer stays');
    assert.equal(isBotOwnedTransitionRemoval('bug', ['area:runtime'], { botWrittenLabels: ['bug'] }), false, 'human bug without desired peer stays');
    assert.equal(isBotOwnedTransitionRemoval('gate:release', ['gate:live'], { botWrittenLabels: ['gate:release'] }), false, 'gate never transitions');
    assert.equal(isBotOwnedTransitionRemoval('area:runtime', ['area:docs'], { botWrittenLabels: ['area:runtime'] }), false, 'area is scope-managed, not transition-managed');

    // Issue P1→P2 proven: desired P2 adds P2 and removes superseded P1.
    const p1ToP2Mock = new StatefulMockLabelClient({ 30: ['priority:P1', 'area:runtime'] });
    const p1ToP2 = await reconcileBotLabelsSafely({
      client: p1ToP2Mock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 30,
      desiredLabels: ['priority:P2', 'area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
      botWrittenLabels: ['priority:P1'],
    });
    assert.ok(p1ToP2.added.includes('priority:P2'), 'proven bot-owned P2 is added');
    assert.ok(p1ToP2.removed.includes('priority:P1'), 'proven superseded P1 is removed');
    assert.ok(!p1ToP2Mock.getLabels(30).includes('priority:P1'));
    assert.ok(p1ToP2Mock.getLabels(30).includes('priority:P2'));

    // Issue human-owned P1: desired without a priority peer preserves it.
    const humanP1Mock = new StatefulMockLabelClient({ 31: ['priority:P1', 'area:runtime'] });
    const humanP1 = await reconcileBotLabelsSafely({
      client: humanP1Mock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 31,
      desiredLabels: ['area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
    });
    assert.deepEqual(humanP1.removed, [], 'human-owned P1 is never auto-removed');
    assert.ok(humanP1Mock.getLabels(31).includes('priority:P1'), 'human-owned P1 preserved');

    // Human P1 + AI P2 without provenance: P2 is added, P1 is retained
    // warn-only (conservative; unproven transitions never delete).
    const humanP1vsP2Mock = new StatefulMockLabelClient({ 34: ['priority:P1', 'area:runtime'] });
    const humanP1vsP2 = await captureConsoleCalls(() => reconcileBotLabelsSafely({
      client: humanP1vsP2Mock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 34,
      desiredLabels: ['priority:P2', 'area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
    }));
    assert.ok(humanP1vsP2.result.added.includes('priority:P2'), 'AI P2 is still added');
    assert.deepEqual(humanP1vsP2.result.removed, [], 'unproven P1 is never auto-removed');
    assert.ok(humanP1vsP2Mock.getLabels(34).includes('priority:P1'), 'human P1 retained');
    assert.ok(humanP1vsP2Mock.getLabels(34).includes('priority:P2'), 'AI P2 present alongside');
    assert.ok(humanP1vsP2.calls.some((call) => call.level === 'warn'), 'unproven transition warns');

    // Issue bug→enhancement proven: desired enhancement swaps the type.
    const bugToEnhMock = new StatefulMockLabelClient({ 32: ['bug', 'area:runtime'] });
    const bugToEnh = await reconcileBotLabelsSafely({
      client: bugToEnhMock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 32,
      desiredLabels: ['enhancement', 'area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
      botWrittenLabels: new Set(['bug']),
    });
    assert.ok(bugToEnh.added.includes('enhancement'), 'proven bot-owned enhancement is added');
    assert.ok(bugToEnh.removed.includes('bug'), 'proven superseded bug is removed');
    assert.ok(!bugToEnhMock.getLabels(32).includes('bug'));
    assert.ok(bugToEnhMock.getLabels(32).includes('enhancement'));

    // Issue human-owned bug: desired without a type peer preserves it.
    const humanBugMock = new StatefulMockLabelClient({ 33: ['bug', 'area:runtime'] });
    const humanBug = await reconcileBotLabelsSafely({
      client: humanBugMock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 33,
      desiredLabels: ['area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
    });
    assert.deepEqual(humanBug.removed, [], 'human-owned bug is never auto-removed');
    assert.ok(humanBugMock.getLabels(33).includes('bug'), 'human-owned bug preserved');

    // Human bug + AI enhancement without provenance: enhancement is added,
    // bug is retained warn-only.
    const humanBugVsEnhMock = new StatefulMockLabelClient({ 35: ['bug', 'area:runtime'] });
    const humanBugVsEnh = await captureConsoleCalls(() => reconcileBotLabelsSafely({
      client: humanBugVsEnhMock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 35,
      desiredLabels: ['enhancement', 'area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
    }));
    assert.ok(humanBugVsEnh.result.added.includes('enhancement'), 'AI enhancement is still added');
    assert.deepEqual(humanBugVsEnh.result.removed, [], 'unproven bug is never auto-removed');
    assert.ok(humanBugVsEnhMock.getLabels(35).includes('bug'), 'human bug retained');
    assert.ok(humanBugVsEnhMock.getLabels(35).includes('enhancement'), 'AI enhancement present alongside');
    assert.ok(humanBugVsEnh.calls.some((call) => call.level === 'warn'), 'unproven transition warns');

    // Timeline fallback (actor == botLogin): a bot-labeled event proves the
    // transition with no explicit caller set.
    const botTimelineClient = {
      rest: { issues: {
        listLabelsOnIssue: async () => ({ data: [{ name: 'priority:P1' }, { name: 'area:runtime' }] }),
        addLabels: async () => ({}),
        removeLabel: async () => ({}),
        listEventsForTimeline: async () => ({ data: [
          { event: 'labeled', actor: { login: 'pocketguard[bot]' }, label: { name: 'priority:P1' } },
        ] }),
      } },
    } as unknown as GitHubLabelClient;
    const botTimeline = await reconcileBotLabelsSafely({
      client: botTimelineClient,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 40,
      desiredLabels: ['priority:P2', 'area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
      botLogin: 'pocketguard[bot]',
    });
    assert.ok(botTimeline.removed.includes('priority:P1'), 'timeline-proven P1 is removed');

    // Timeline with a human actor: retained.
    const humanTimelineClient = {
      rest: { issues: {
        listLabelsOnIssue: async () => ({ data: [{ name: 'priority:P1' }, { name: 'area:runtime' }] }),
        addLabels: async () => ({}),
        removeLabel: async () => ({}),
        listEventsForTimeline: async () => ({ data: [
          { event: 'labeled', actor: { login: 'human-maintainer' }, label: { name: 'priority:P1' } },
        ] }),
      } },
    } as unknown as GitHubLabelClient;
    const humanTimeline = await captureConsoleCalls(() => reconcileBotLabelsSafely({
      client: humanTimelineClient,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 41,
      desiredLabels: ['priority:P2', 'area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
      botLogin: 'pocketguard[bot]',
    }));
    assert.deepEqual(humanTimeline.result.removed, [], 'human-actor timeline retains P1');
    assert.ok(humanTimeline.calls.some((call) => call.level === 'warn'), 'human-actor timeline warns');

    // Timeline failure / unknown provenance: retained (fail-closed).
    const failingTimelineClient = {
      rest: { issues: {
        listLabelsOnIssue: async () => ({ data: [{ name: 'bug' }, { name: 'area:runtime' }] }),
        addLabels: async () => ({}),
        removeLabel: async () => ({}),
        listEventsForTimeline: async () => { throw new Error('synthetic timeline failure'); },
      } },
    } as unknown as GitHubLabelClient;
    const failingTimeline = await captureConsoleCalls(() => reconcileBotLabelsSafely({
      client: failingTimelineClient,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 42,
      desiredLabels: ['enhancement', 'area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
      botLogin: 'pocketguard[bot]',
    }));
    assert.deepEqual(failingTimeline.result.removed, [], 'timeline failure retains bug');
    assert.ok(failingTimeline.calls.some((call) => call.level === 'warn'), 'timeline failure warns');

    // Timeline helper directly: malformed payload and missing reader are unknown.
    assert.equal(await resolveTimelineBotWrittenLabels({
      client: { rest: { issues: { addLabels: async () => ({}) } } } as unknown as GitHubLabelClient,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 43,
      botLogin: 'pocketguard[bot]',
    }), undefined, 'missing timeline reader is unknown');
    assert.equal(await resolveTimelineBotWrittenLabels({
      client: {
        rest: { issues: {
          addLabels: async () => ({}),
          listEventsForTimeline: async () => ({ data: 'not-an-array' }),
        } },
      } as unknown as GitHubLabelClient,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 44,
      botLogin: 'pocketguard[bot]',
    }), undefined, 'malformed timeline payload is unknown');
  }
  // Human area: retention — incomplete coverage preserves a human-added area
  // label (human lock priority) instead of replacing it.
  {
    const humanAreaMock = new StatefulMockLabelClient({ 22: ['area:delivery'] });
    const humanAreaResult = await reconcileBotLabelsSafely({
      client: humanAreaMock,
      owner: 'owner',
      repo: 'repo',
      issueNumber: 22,
      desiredLabels: ['area:runtime'],
      scope: DEFAULT_PR_RECONCILE_SCOPE,
      coverageComplete: false,
    });
    assert.deepEqual(humanAreaResult.removed, [], 'incomplete coverage never removes human area');
    assert.ok(humanAreaMock.getLabels(22).includes('area:delivery'), 'human area:delivery retained');
    assert.ok(humanAreaMock.getLabels(22).includes('area:runtime'));
    assert.ok(humanAreaMock.getLabels(22).includes('status:needs-decision'));
  }

  // Incomplete path coverage always asks for a decision and never removes existing area labels.
  const incompleteMock = new StatefulMockLabelClient({ 2: ['area:platform', 'area:runtime'] });
  const incompleteResult = await reconcileBotLabelsSafely({
    client: incompleteMock,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 2,
    desiredLabels: ['area:docs'],
    scope: DEFAULT_PR_RECONCILE_SCOPE,
    coverageComplete: false,
  });
  assert.deepEqual(incompleteResult.removed, []);
  assert.deepEqual(incompleteResult.added.sort(), ['area:docs', 'status:needs-decision']);
  assert.deepEqual(incompleteMock.getLabels(2), ['area:docs', 'area:platform', 'area:runtime', 'status:needs-decision']);

  const missingRemoveClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => ({ data: [{ name: 'area:docs' }, { name: 'area:runtime' }] }),
      addLabels: async () => ({}),
    } },
  } as unknown as GitHubLabelClient;
  const missingRemove = await captureConsoleCalls(() => reconcileBotLabelsSafely({
    client: missingRemoveClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 13,
    desiredLabels: [],
    scope: DEFAULT_PR_RECONCILE_SCOPE,
  }));
  assert.deepEqual(missingRemove.result.failedRemovals, ['area:docs', 'area:runtime']);
  assert.deepEqual(missingRemove.result.removed, []);
  assert.ok(missingRemove.calls.some((call) => call.level === 'warn'));

  const notFoundRemoveClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => ({ data: [{ name: 'area:docs' }] }),
      addLabels: async () => ({}),
      removeLabel: async () => { throw Object.assign(new Error('Not Found'), { status: 404 }); },
    } },
  } as unknown as GitHubLabelClient;
  const notFoundRemove = await reconcileBotLabelsSafely({
    client: notFoundRemoveClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 14,
    desiredLabels: [],
    scope: DEFAULT_PR_RECONCILE_SCOPE,
  });
  assert.deepEqual(notFoundRemove.removed, ['area:docs'], 'a 404 removal is idempotent success');
  assert.deepEqual(notFoundRemove.failedRemovals, []);

  const apiErrorMarker = 'synthetic raw label API error marker';
  const listFailureClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => { throw new Error(apiErrorMarker); },
      addLabels: async () => ({}),
      removeLabel: async () => ({}),
    } },
  } as unknown as GitHubLabelClient;
  const listFailure = await captureConsoleCalls(() => reconcileBotLabelsSafely({
    client: listFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 5,
    desiredLabels: ['status:needs-decision'],
    scope: { managedExactLabels: ['status:needs-decision'] },
    coverageComplete: false,
  }));
  assert.equal(listFailure.result.failedToList, true);
  assert.deepEqual(listFailure.result.added, ['status:needs-decision'],
    'an unavailable listing still permits the append-only decision-label fallback');
  assert.ok(listFailure.calls.some((call) => call.level === 'warn'));
  assertNoSensitiveConsoleOutput(listFailure.calls, apiErrorMarker, 'list failure');

  const fallbackAddFailureClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => { throw new Error(apiErrorMarker); },
      addLabels: async () => { throw { detail: apiErrorMarker }; },
      removeLabel: async () => ({}),
    } },
  } as unknown as GitHubLabelClient;
  const fallbackAddFailure = await captureConsoleCalls(() => reconcileBotLabelsSafely({
    client: fallbackAddFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 10,
    desiredLabels: ['status:needs-decision'],
    scope: { managedExactLabels: ['status:needs-decision'] },
    coverageComplete: false,
  }));
  assert.equal(fallbackAddFailure.result.failedToList, true);
  assert.deepEqual(fallbackAddFailure.result.added, [], 'a failed fallback add is not reported as successful');
  assertNoSensitiveConsoleOutput(fallbackAddFailure.calls, apiErrorMarker, 'fallback add failure');

  const unavailableListCalls: string[][] = [];
  const unavailableListClient = {
    rest: { issues: {
      addLabels: async (params: { labels: string[] }) => { unavailableListCalls.push(params.labels); return {}; },
    } },
  } as unknown as GitHubLabelClient;
  const unavailableList = await reconcileBotLabelsSafely({
    client: unavailableListClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 6,
    desiredLabels: ['status:needs-decision'],
    scope: DEFAULT_PR_RECONCILE_SCOPE,
  });
  assert.equal(unavailableList.failedToList, true, 'a missing listing operation is not complete reconciliation');
  assert.deepEqual(unavailableListCalls, [['status:needs-decision']]);

  const addFailureClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => ({ data: [] }),
      addLabels: async () => { throw new Error(apiErrorMarker); },
      removeLabel: async () => ({}),
    } },
  } as unknown as GitHubLabelClient;
  const addFailure = await captureConsoleCalls(() => reconcileBotLabelsSafely({
    client: addFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 7,
    desiredLabels: ['area:policy'],
  }));
  assert.deepEqual(addFailure.result.added, []);
  assertNoSensitiveConsoleOutput(addFailure.calls, apiErrorMarker, 'add failure');

  const removeFailureClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => ({ data: [{ name: 'area:docs' }] }),
      addLabels: async () => ({}),
      removeLabel: async () => { throw { status: 500, detail: apiErrorMarker }; },
    } },
  } as unknown as GitHubLabelClient;
  const removeFailure = await captureConsoleCalls(() => reconcileBotLabelsSafely({
    client: removeFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 8,
    desiredLabels: [],
    scope: DEFAULT_PR_RECONCILE_SCOPE,
  }));
  assert.deepEqual(removeFailure.result.failedRemovals, ['area:docs']);
  assertNoSensitiveConsoleOutput(removeFailure.calls, apiErrorMarker, 'remove failure');

  const scopedListFailure = await applyLabelsSafely({
    client: listFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 15,
    labels: ['status:needs-decision'],
    scope: { managedExactLabels: ['status:needs-decision'] },
  });
  assert.equal(scopedListFailure.failedToList, true, 'scoped applyLabelsSafely preserves list failure metadata');
  assert.deepEqual(scopedListFailure.failedRemovals, []);

  const scopedRemoveFailure = await applyLabelsSafely({
    client: removeFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 16,
    labels: [],
    scope: DEFAULT_PR_RECONCILE_SCOPE,
  });
  assert.equal(scopedRemoveFailure.failedToList, false);
  assert.deepEqual(scopedRemoveFailure.failedRemovals, ['area:docs'],
    'scoped applyLabelsSafely preserves removal failure metadata');

  const originalEventPath = process.env.GITHUB_EVENT_PATH;
  const originalRepository = process.env.GITHUB_REPOSITORY;
  const temporaryEventDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-label-test-'));
  const temporaryEventPath = path.join(temporaryEventDirectory, 'event.json');
  try {
    fs.writeFileSync(temporaryEventPath, JSON.stringify({ pull_request: { number: 17 } }));
    process.env.GITHUB_EVENT_PATH = temporaryEventPath;
    process.env.GITHUB_REPOSITORY = 'owner/repo';

    const botListFailure = await captureConsoleCalls(() => applyBotLabels(
      ['status:needs-decision'],
      { customClient: listFailureClient, scope: { managedExactLabels: ['status:needs-decision'] } },
    ));
    assert.equal(botListFailure.result?.failedToList, true,
      'scoped applyBotLabels preserves list failure metadata');
    assert.deepEqual(botListFailure.result?.failedRemovals, []);
    assertNoSensitiveConsoleOutput(botListFailure.calls, apiErrorMarker, 'scoped applyBotLabels list failure');

    const botRemoveFailure = await captureConsoleCalls(() => applyBotLabels(
      [],
      { customClient: removeFailureClient, scope: DEFAULT_PR_RECONCILE_SCOPE },
    ));
    assert.equal(botRemoveFailure.result?.failedToList, false);
    assert.deepEqual(botRemoveFailure.result?.failedRemovals, ['area:docs'],
      'scoped applyBotLabels preserves removal failure metadata');
    assertNoSensitiveConsoleOutput(botRemoveFailure.calls, apiErrorMarker, 'scoped applyBotLabels remove failure');
  } finally {
    if (originalEventPath === undefined) delete process.env.GITHUB_EVENT_PATH;
    else process.env.GITHUB_EVENT_PATH = originalEventPath;
    if (originalRepository === undefined) delete process.env.GITHUB_REPOSITORY;
    else process.env.GITHUB_REPOSITORY = originalRepository;
    fs.rmSync(temporaryEventDirectory, { recursive: true, force: true });
  }

  const ensureFailure = await captureConsoleCalls(() => ensureNeedsDecision({
    client: {
      rest: { issues: {
        addLabels: async () => { throw new Error(apiErrorMarker); },
      } },
    } as unknown as GitHubLabelClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 9,
  }));
  assert.equal(ensureFailure.result, false);
  assertNoSensitiveConsoleOutput(ensureFailure.calls, apiErrorMarker, 'decision-label add failure');

  const appendOnlyListFailureClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => { throw new Error(apiErrorMarker); },
      addLabels: async () => ({}),
    } },
  } as unknown as GitHubLabelClient;
  const appendOnlyListFailure = await captureConsoleCalls(() => applyLabelsSafely({
    client: appendOnlyListFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 11,
    labels: ['area:runtime'],
  }));
  assert.deepEqual(appendOnlyListFailure.result.added, ['area:runtime']);
  assertNoSensitiveConsoleOutput(appendOnlyListFailure.calls, apiErrorMarker, 'append-only list failure');

  const appendOnlyAddFailureClient = {
    rest: { issues: {
      listLabelsOnIssue: async () => ({ data: [] }),
      addLabels: async () => { throw new Error(apiErrorMarker); },
    } },
  } as unknown as GitHubLabelClient;
  const appendOnlyAddFailure = await captureConsoleCalls(() => applyLabelsSafely({
    client: appendOnlyAddFailureClient,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 12,
    labels: ['area:runtime'],
  }));
  assert.deepEqual(appendOnlyAddFailure.result.added, []);
  assertNoSensitiveConsoleOutput(appendOnlyAddFailure.calls, apiErrorMarker, 'append-only add failure');

  // ensureNeedsDecision uses only the add-labels endpoint; it never lists or removes labels.
  const ensureMock = new StatefulMockLabelClient();
  assert.equal(await ensureNeedsDecision({
    client: ensureMock,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 3,
  }), true);
  assert.deepEqual(ensureMock.addedCalls, [{ issueNumber: 3, labels: ['status:needs-decision'] }]);
  assert.equal(ensureMock.listCalls, 0);
  assert.deepEqual(ensureMock.removedCalls, []);

  const appendOnlyMock = new StatefulMockLabelClient({ 4: ['area:runtime'] });
  const applied = await applyLabelsSafely({
    client: appendOnlyMock,
    owner: 'owner',
    repo: 'repo',
    issueNumber: 4,
    labels: [],
    coverageComplete: false,
  });
  assert.deepEqual(applied.added, ['status:needs-decision']);
  assert.deepEqual(appendOnlyMock.getLabels(4), ['area:runtime', 'status:needs-decision']);

  // Phase 1 (eighth P1 #1): timeline pagination + last-writer-wins over
  // created_at with unlabeled participation. All cases go through
  // reconcileBotLabelsSafely and assert a human label is never removed
  // unless the sorted timeline proves bot-last-labeled.
  {
    const BOT_LOGIN = 'pocketguard[bot]';
    const HUMAN_LOGIN = 'human-maintainer';
    // Compile-time guard: the timeline event carries ordering/identity fields.
    const fieldGuard: LabelTimelineEvent = {
      event: 'labeled',
      actor: { login: BOT_LOGIN },
      label: { name: 'priority:P1' },
      created_at: '2024-01-01T00:00:00.000Z',
      id: 1,
      commit_id: null,
    };
    assert.equal(fieldGuard.created_at, '2024-01-01T00:00:00.000Z');

    const tlLabeled = (label: string, actor: string, createdAt: string, id?: number) => ({
      event: 'labeled',
      actor: { login: actor },
      label: { name: label },
      created_at: createdAt,
      ...(id !== undefined ? { id } : {}),
    });
    const tlUnlabeled = (label: string, actor: string, createdAt: string, id?: number) => ({
      event: 'unlabeled',
      actor: { login: actor },
      label: { name: label },
      created_at: createdAt,
      ...(id !== undefined ? { id } : {}),
    });
    const tlCommented = (createdAt: string, id?: number) => ({
      event: 'commented',
      actor: { login: 'someone-else' },
      created_at: createdAt,
      ...(id !== undefined ? { id } : {}),
    });
    const isoMinute = (base: number) => `2024-01-01T00:${String(base).padStart(2, '0')}:00.000Z`;

    interface PageDef {
      data?: unknown;
      link?: string;
      throwMarker?: string;
    }
    function makePagedClient(existingLabels: string[], pages: PageDef[], pageCalls: { count: number }) {
      const labels = new Set<string>(existingLabels);
      return {
        getLabels: () => Array.from(labels).sort(),
        rest: {
          issues: {
            listLabelsOnIssue: async () => ({ data: Array.from(labels).map((name) => ({ name })) }),
            addLabels: async (params: { labels: string[] }) => {
              for (const label of params.labels) labels.add(label);
              return {};
            },
            removeLabel: async (params: { name: string }) => {
              labels.delete(params.name);
              return {};
            },
            listEventsForTimeline: async (params: { page?: number; per_page?: number }) => {
              pageCalls.count += 1;
              assert.equal(params.per_page, 100, 'timeline pagination uses per_page=100');
              const page = params.page ?? 1;
              const def = pages[page - 1];
              if (!def) return { data: [] };
              if (def.throwMarker) throw new Error(def.throwMarker);
              const headers = def.link !== undefined ? { link: def.link } : undefined;
              return headers ? { data: def.data as never[], headers } : { data: def.data as never[] };
            },
          },
        },
      } as unknown as GitHubLabelClient & { getLabels: () => string[] };
    }

    // >100 events: page 1 full (bot P1 + 99 filler), page 2 human re-adds P1 later.
    {
      const filler = Array.from({ length: 99 }, (_, i) => tlCommented(isoMinute(0), 1000 + i));
      const page1 = [...filler, tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T01:00:00.000Z', 2000)];
      const page2 = [tlLabeled('priority:P1', HUMAN_LOGIN, '2024-01-01T02:00:00.000Z', 3000)];
      const calls = { count: 0 };
      const client = makePagedClient(['priority:P1', 'area:runtime'], [{ data: page1 }, { data: page2 }], calls);
      const result = await reconcileBotLabelsSafely({
        client, owner: 'owner', repo: 'repo', issueNumber: 100,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.ok(calls.count >= 2, '>100 events must fetch page 2');
      assert.deepEqual(result.removed, [], 'later-page human re-add is never removed');
      assert.ok(client.getLabels().includes('priority:P1'), 'human P1 preserved across pages');
      const proven = await resolveTimelineBotWrittenLabels({
        client, owner: 'owner', repo: 'repo', issueNumber: 100, botLogin: BOT_LOGIN,
      });
      assert.ok(proven !== undefined && !proven.has('priority:p1'), 'human-last across pages is not proven');
    }

    // Mixed labeled/unlabeled: bot labels, human removes → terminal unlabeled is not proven.
    {
      const calls = { count: 0 };
      const client = makePagedClient(['priority:P1', 'area:runtime'], [{
        data: [
          tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T01:00:00.000Z', 1),
          tlUnlabeled('priority:P1', HUMAN_LOGIN, '2024-01-01T02:00:00.000Z', 2),
        ],
      }], calls);
      const result = await reconcileBotLabelsSafely({
        client, owner: 'owner', repo: 'repo', issueNumber: 101,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.deepEqual(result.removed, [], 'terminal unlabeled is never proven bot-written');
      assert.ok(client.getLabels().includes('priority:P1'));
      // Bot re-labels after the human removal → bot-last-labeled is proven again.
      const calls2 = { count: 0 };
      const client2 = makePagedClient(['bug', 'area:runtime'], [{
        data: [
          tlLabeled('bug', BOT_LOGIN, '2024-01-01T01:00:00.000Z', 1),
          tlUnlabeled('bug', HUMAN_LOGIN, '2024-01-01T02:00:00.000Z', 2),
          tlLabeled('bug', BOT_LOGIN, '2024-01-01T03:00:00.000Z', 3),
        ],
      }], calls2);
      const proven2 = await resolveTimelineBotWrittenLabels({
        client: client2, owner: 'owner', repo: 'repo', issueNumber: 102, botLogin: BOT_LOGIN,
      });
      assert.ok(proven2 !== undefined && proven2.has('bug'), 'bot re-label after unlabeled is proven');
    }

    // 100-event boundary: page 1 exactly 100, page 2 empty → determinable (bot proven).
    {
      const filler = Array.from({ length: 99 }, (_, i) => tlCommented(isoMinute(0), 5000 + i));
      const page1 = [...filler, tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T01:00:00.000Z', 9000)];
      const calls = { count: 0 };
      const client = makePagedClient(['priority:P1', 'area:runtime'], [{ data: page1 }, { data: [] }], calls);
      const result = await reconcileBotLabelsSafely({
        client, owner: 'owner', repo: 'repo', issueNumber: 103,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.ok(calls.count >= 2, 'full page must probe the next page before concluding');
      assert.ok(result.removed.includes('priority:P1'), '100-boundary bot write stays removable');
      assert.ok(!client.getLabels().includes('priority:P1'));
    }

    // Second page throws → unknown (fail-closed), never throws, never logs raw.
    {
      const marker = 'synthetic second-page timeline marker';
      const filler = Array.from({ length: 99 }, (_, i) => tlCommented(isoMinute(0), 7000 + i));
      const page1 = [...filler, tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T01:00:00.000Z', 9500)];
      const calls = { count: 0 };
      const client = makePagedClient(['priority:P1', 'area:runtime'], [{ data: page1 }, { throwMarker: marker }], calls);
      const { result, calls: consoleCalls } = await captureConsoleCalls(() => reconcileBotLabelsSafely({
        client, owner: 'owner', repo: 'repo', issueNumber: 104,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      }));
      assert.deepEqual(result.removed, [], 'second-page failure retains the human-visible label');
      assert.ok(client.getLabels().includes('priority:P1'));
      assertNoSensitiveConsoleOutput(consoleCalls, marker, 'second-page timeline failure');
      const direct = await resolveTimelineBotWrittenLabels({
        client, owner: 'owner', repo: 'repo', issueNumber: 104, botLogin: BOT_LOGIN,
      });
      assert.equal(direct, undefined, 'second-page throw is unknown provenance');
    }

    // Out-of-order: array order disagrees with created_at; sorted order wins.
    {
      // Array ends with bot (old), but human is chronologically last → retain.
      const callsA = { count: 0 };
      const clientA = makePagedClient(['priority:P1', 'area:runtime'], [{
        data: [
          tlLabeled('priority:P1', HUMAN_LOGIN, '2024-01-01T03:00:00.000Z', 2),
          tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T01:00:00.000Z', 1),
        ],
      }], callsA);
      const resultA = await reconcileBotLabelsSafely({
        client: clientA, owner: 'owner', repo: 'repo', issueNumber: 105,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.deepEqual(resultA.removed, [], 'unsorted array must not mask a human-last write');
      assert.ok(clientA.getLabels().includes('priority:P1'));
      // Array ends with human (old), but bot is chronologically last → removable.
      const callsB = { count: 0 };
      const clientB = makePagedClient(['priority:P1', 'area:runtime'], [{
        data: [
          tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T03:00:00.000Z', 2),
          tlLabeled('priority:P1', HUMAN_LOGIN, '2024-01-01T01:00:00.000Z', 1),
        ],
      }], callsB);
      const resultB = await reconcileBotLabelsSafely({
        client: clientB, owner: 'owner', repo: 'repo', issueNumber: 106,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.ok(resultB.removed.includes('priority:P1'), 'sorted bot-last write is removable');
    }

    // Bot-latest positive (in-order): human then bot → proven transition removes.
    {
      const calls = { count: 0 };
      const client = makePagedClient(['priority:P1', 'area:runtime'], [{
        data: [
          tlLabeled('priority:P1', HUMAN_LOGIN, '2024-01-01T01:00:00.000Z', 1),
          tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T02:00:00.000Z', 2),
        ],
      }], calls);
      const result = await reconcileBotLabelsSafely({
        client, owner: 'owner', repo: 'repo', issueNumber: 107,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.ok(result.removed.includes('priority:P1'), 'bot-latest proven write is removable');
      assert.ok(client.getLabels().includes('priority:P2'));
    }

    // Unknown fail-closed: missing actor, missing label, ambiguous timestamps.
    {
      const missingActor = { count: 0 };
      const clientActor = makePagedClient(['priority:P1', 'area:runtime'], [{
        data: [{ event: 'labeled', label: { name: 'priority:P1' }, created_at: '2024-01-01T01:00:00.000Z' }],
      }], missingActor);
      const resActor = await reconcileBotLabelsSafely({
        client: clientActor, owner: 'owner', repo: 'repo', issueNumber: 108,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.deepEqual(resActor.removed, [], 'labeled event without actor is unknown');
      assert.ok(clientActor.getLabels().includes('priority:P1'));

      const missingLabel = { count: 0 };
      const clientLabel = makePagedClient(['priority:P1', 'area:runtime'], [{
        data: [{ event: 'labeled', actor: { login: BOT_LOGIN }, created_at: '2024-01-01T01:00:00.000Z' }],
      }], missingLabel);
      const resLabel = await reconcileBotLabelsSafely({
        client: clientLabel, owner: 'owner', repo: 'repo', issueNumber: 109,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.deepEqual(resLabel.removed, [], 'labeled event without label is unknown');

      const ambiguousTime = { count: 0 };
      const clientTime = makePagedClient(['priority:P1', 'area:runtime'], [{
        data: [
          { event: 'labeled', actor: { login: BOT_LOGIN }, label: { name: 'priority:P1' } },
          tlLabeled('priority:P1', HUMAN_LOGIN, '2024-01-01T02:00:00.000Z', 2),
        ],
      }], ambiguousTime);
      const resTime = await reconcileBotLabelsSafely({
        client: clientTime, owner: 'owner', repo: 'repo', issueNumber: 110,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.deepEqual(resTime.removed, [], 'multi-write without ordering timestamps is unknown');
      assert.ok(clientTime.getLabels().includes('priority:P1'));
    }

    // Link header: a full page that confirms no next page needs no extra fetch.
    {
      const filler = Array.from({ length: 99 }, (_, i) => tlCommented(isoMinute(0), 11000 + i));
      const page1 = [...filler, tlLabeled('priority:P1', BOT_LOGIN, '2024-01-01T01:00:00.000Z', 12000)];
      const calls = { count: 0 };
      const client = makePagedClient(
        ['priority:P1', 'area:runtime'],
        [{ data: page1, link: '<https://api.github.com/issues/1/timeline?page=1>; rel="last"' }],
        calls,
      );
      const result = await reconcileBotLabelsSafely({
        client, owner: 'owner', repo: 'repo', issueNumber: 111,
        desiredLabels: ['priority:P2', 'area:runtime'],
        scope: DEFAULT_PR_RECONCILE_SCOPE, botLogin: BOT_LOGIN,
      });
      assert.equal(calls.count, 1, 'Link without rel="next" confirms the end of a full page');
      assert.ok(result.removed.includes('priority:P1'), 'Link-confirmed bot write stays removable');
    }
  }

  console.log('[PocketGuard label tests] All tests passed.');
}
