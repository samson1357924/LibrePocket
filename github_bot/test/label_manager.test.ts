import assert from 'node:assert/strict';
import {
  BOT_MENTION,
  COMMENT_MARKERS,
  DEFAULT_PR_RECONCILE_SCOPE,
  REPO_ALLOWED_LABELS,
  applyLabelsSafely,
  ensureNeedsDecision,
  extractLabelsFromTriageText,
  isManagedByBot,
  normalizeLabelName,
  parseReviewReport,
  reconcileBotLabelsSafely,
  resolveAreaLabelsFromPaths,
  resolveLabelsFromTitle,
  resolveReviewLabels,
  sanitizeLabels,
  type GitHubLabelClient,
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

  console.log('[PocketGuard label tests] All tests passed.');
}
