import * as fs from 'node:fs';
import * as path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Octokit } from '@octokit/rest';
import {
  classifyCommentCommand,
  commentCommandNeedsGitDiff,
  isCommentCommandAllowed,
  type CommentCommand,
  type CommentTarget,
} from './comment_command';
import { DeterministicScanner, type ScanViolation } from './deterministic_scanner';
import {
  DEFAULT_PR_RECONCILE_SCOPE,
  reconcileBotLabelsSafely,
  resolveLabelsFromTitle,
  resolveReviewLabels,
  sanitizeLabels,
  type GitHubLabelClient,
} from './label_manager';
import { MAX_DIFF_LENGTH, prioritizeFiles, truncateDiff, type ReviewCoverage } from './review_diff';

const REVIEW_MARKER = '<!-- PocketGuard-review -->';
const ROLE_NAMES = ['chief', 'android_sec', 'android_code'] as const;
const SAFE_MESSAGE = '自動審查未執行；請由維護者檢視變更。';

type ReviewRoleName = typeof ROLE_NAMES[number];
type RunnerVerdict = 'APPROVE' | 'NEEDS_CHANGES' | 'INCONCLUSIVE';

interface EventRepository {
  full_name?: string;
  default_branch?: string;
}

interface PullRequestPayload {
  number?: number;
  title?: string;
  base?: { sha?: string; ref?: string };
  head?: { sha?: string; ref?: string; repo?: { full_name?: string | null } | null };
}

interface GithubEvent {
  action?: string;
  repository?: EventRepository;
  pull_request?: PullRequestPayload;
  issue?: { number?: number; title?: string; pull_request?: unknown };
  comment?: { body?: string };
}

interface PullRequestData {
  number: number;
  base: { sha: string };
  head: { sha: string; repo?: { full_name?: string | null } | null };
}

interface GithubComment {
  id: number;
  body?: string | null;
  user?: { login?: string | null; type?: string | null } | null;
}

interface RunnerGitHubClient {
  rest: {
    pulls: { get(params: { owner: string; repo: string; pull_number: number }): Promise<{ data: PullRequestData }> };
    issues: {
      listComments(params: { owner: string; repo: string; issue_number: number; per_page: number; page: number }): Promise<{ data: GithubComment[] }>;
      createComment(params: { owner: string; repo: string; issue_number: number; body: string }): Promise<unknown>;
      updateComment(params: { owner: string; repo: string; comment_id: number; body: string }): Promise<unknown>;
      addLabels(params: { owner: string; repo: string; issue_number: number; labels: string[] }): Promise<unknown>;
      listLabelsOnIssue?: GitHubLabelClient['rest']['issues']['listLabelsOnIssue'];
      removeLabel?: GitHubLabelClient['rest']['issues']['removeLabel'];
    };
    users: { getAuthenticated(): Promise<{ data: { login: string } }> };
  };
}

export interface RunnerContext {
  env?: NodeJS.ProcessEnv;
  event?: GithubEvent;
  githubClient?: RunnerGitHubClient;
  writeStdout?: (text: string) => void;
  runGit?: (args: string[]) => string;
}

interface ReviewTarget {
  target: CommentTarget | 'none';
  command: CommentCommand;
  needsDiff: boolean;
  safeReview: boolean;
  issueNumber?: number;
  pullRequest?: PullRequestData;
  title: string;
}

export interface TagResult {
  labels: string[];
  target: CommentTarget | 'none';
  command: CommentCommand;
  needsDiff: boolean;
  safeReview: boolean;
  issueNumber?: number;
}

export interface RunnerReviewOutput {
  verdict: RunnerVerdict;
  roles: Array<{
    role: ReviewRoleName;
    modelUsed: string;
    verdict: RunnerVerdict;
    findings: Array<{
      severity: 'BLOCK' | 'WARN' | 'SUGGESTION';
      file?: string;
      line?: number;
      issue: string;
      suggestion?: string;
    }>;
  }>;
  coverage: ReviewCoverage;
  deterministicViolations: ScanViolation[];
}

function stdout(context: RunnerContext, text: string): void {
  (context.writeStdout ?? ((value) => process.stdout.write(value)))(text);
}

function eventFrom(context: RunnerContext): GithubEvent {
  if (context.event) return context.event;
  const env = context.env ?? process.env;
  const override = env.POCKETGUARD_EVENT_JSON;
  if (override) {
    const parsed: unknown = JSON.parse(override);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as GithubEvent;
  }
  const eventPath = env.GITHUB_EVENT_PATH;
  if (!eventPath) throw new Error('event unavailable');
  const parsed: unknown = JSON.parse(fs.readFileSync(eventPath, 'utf8'));
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('event invalid');
  return parsed as GithubEvent;
}

function repositoryParts(env: NodeJS.ProcessEnv, event: GithubEvent): { owner: string; repo: string; fullName: string } | undefined {
  const fullName = (env.GITHUB_REPOSITORY ?? event.repository?.full_name ?? '').trim();
  const match = /^([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/.exec(fullName);
  return match ? { owner: match[1], repo: match[2], fullName } : undefined;
}

function apiClient(context: RunnerContext, token: string): RunnerGitHubClient | undefined {
  if (context.githubClient) return context.githubClient;
  if (!token) return undefined;
  return new Octokit({ auth: token }) as unknown as RunnerGitHubClient;
}

function sameRepository(left: string | null | undefined, right: string): boolean {
  return typeof left === 'string' && left.toLowerCase() === right.toLowerCase();
}

function safeSha(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{40}$/i.test(value);
}

async function inspectTarget(context: RunnerContext): Promise<ReviewTarget> {
  const env = context.env ?? process.env;
  const event = eventFrom(context);
  const eventName = env.GITHUB_EVENT_NAME ?? '';
  const repository = repositoryParts(env, event);

  if (eventName === 'pull_request_target' || event.pull_request) {
    const pull = event.pull_request;
    if (!pull) return { target: 'none', command: 'unsupported', needsDiff: false, safeReview: false, title: '', };
    const headRepository = pull.head?.repo?.full_name;
    const baseSha = pull.base?.sha ?? '';
    const headSha = pull.head?.sha ?? '';
    const safeReview = Boolean(
      repository && sameRepository(headRepository, repository.fullName) && safeSha(baseSha) && safeSha(headSha),
    );
    const number = Number(pull.number ?? event.issue?.number);
    return {
      target: 'pull-request',
      command: 'review',
      needsDiff: true,
      safeReview,
      ...(Number.isSafeInteger(number) && number > 0 ? { issueNumber: number } : {}),
      ...(safeReview ? {
        pullRequest: {
          number,
          base: { sha: baseSha },
          head: { sha: headSha, repo: { full_name: headRepository } },
        },
      } : {}),
      title: typeof pull.title === 'string' ? pull.title : '',
    };
  }

  if (eventName === 'issue_comment' || event.comment) {
    const issueNumber = Number(event.issue?.number);
    const command = classifyCommentCommand(event.comment?.body ?? '');
    const isPullRequest = Boolean(event.issue?.pull_request);
    let pullRequest: PullRequestData | undefined;
    if (isPullRequest && repository && Number.isSafeInteger(issueNumber) && issueNumber > 0) {
      const client = apiClient(context, env.GITHUB_TOKEN ?? '');
      if (client) {
        try {
          const response = await client.rest.pulls.get({
            owner: repository.owner,
            repo: repository.repo,
            pull_number: issueNumber,
          });
          pullRequest = response.data;
        } catch {
          pullRequest = undefined;
        }
      }
    }
    const safeReview = Boolean(
      pullRequest && repository &&
      sameRepository(pullRequest.head.repo?.full_name, repository.fullName) &&
      safeSha(pullRequest.base.sha) && safeSha(pullRequest.head.sha),
    );
    const target: CommentTarget = isPullRequest ? 'pull-request' : 'issue';
    return {
      target,
      command,
      needsDiff: commentCommandNeedsGitDiff(command),
      safeReview: safeReview && isCommentCommandAllowed(command, target),
      ...(Number.isSafeInteger(issueNumber) && issueNumber > 0 ? { issueNumber } : {}),
      ...(safeReview ? { pullRequest } : {}),
      title: typeof event.issue?.title === 'string' ? event.issue.title : '',
    };
  }

  if (eventName === 'issues' || event.issue) {
    return {
      target: 'issue',
      command: 'unsupported',
      needsDiff: false,
      safeReview: false,
      ...(Number.isSafeInteger(Number(event.issue?.number)) && Number(event.issue?.number) > 0
        ? { issueNumber: Number(event.issue?.number) }
        : {}),
      title: typeof event.issue?.title === 'string' ? event.issue.title : '',
    };
  }

  return { target: 'none', command: 'unsupported', needsDiff: false, safeReview: false, title: '' };
}

function appendWorkflowOutputs(env: NodeJS.ProcessEnv, values: Record<string, string>): void {
  const outputPath = env.GITHUB_OUTPUT;
  if (!outputPath) return;
  const lines = Object.entries(values).map(([key, value]) => `${key}=${value}`);
  fs.appendFileSync(outputPath, `${lines.join('\n')}\n`, { encoding: 'utf8', mode: 0o600 });
}

export async function runTagMode(context: RunnerContext = {}): Promise<TagResult> {
  const env = context.env ?? process.env;
  const target = await inspectTarget(context);
  const labels = sanitizeLabels(resolveLabelsFromTitle(target.title));
  const result: TagResult = {
    labels,
    target: target.target,
    command: target.command,
    needsDiff: target.needsDiff,
    safeReview: target.safeReview,
    ...(target.issueNumber ? { issueNumber: target.issueNumber } : {}),
  };
  appendWorkflowOutputs(env, {
    labels: JSON.stringify(result.labels),
    target: result.target,
    command: result.command,
    needs_diff: String(result.needsDiff),
    safe_review: String(result.safeReview),
    ...(result.issueNumber ? { issue_number: String(result.issueNumber) } : {}),
  });
  stdout(context, `${JSON.stringify(result)}\n`);
  return result;
}

function defaultGit(args: string[]): string {
  return execFileSync('git', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
    maxBuffer: 64 * 1024 * 1024,
  });
}

function getChangedPaths(context: RunnerContext, baseSha: string, headSha: string): string[] {
  const runGit = context.runGit ?? defaultGit;
  return runGit(['diff', '--name-only', '-z', baseSha, headSha])
    .split('\0')
    .filter(Boolean);
}

function buildReviewDiff(
  context: RunnerContext,
  baseSha: string,
  headSha: string,
  changedFiles: string[],
): { diff: string; coverage: ReviewCoverage; fullDiff: string } {
  const runGit = context.runGit ?? defaultGit;
  const diffs = new Map<string, string>();
  let originalLength = 0;
  for (const file of changedFiles) {
    const fileDiff = runGit(['diff', '--no-ext-diff', '--no-color', '--unified=3', baseSha, headSha, '--', file]);
    diffs.set(file, fileDiff);
    originalLength += fileDiff.length;
  }

  const sortedFiles = prioritizeFiles(changedFiles);
  const visible: string[] = [];
  const omittedFiles: string[] = [];
  const truncatedFiles: string[] = [];
  let visibleLength = 0;
  for (const file of sortedFiles) {
    const fileDiff = diffs.get(file) ?? '';
    if (!fileDiff) continue;
    const remaining = Math.max(0, MAX_DIFF_LENGTH - visibleLength);
    if (fileDiff.length <= remaining) {
      visible.push(fileDiff);
      visibleLength += fileDiff.length;
      continue;
    }
    if (remaining > 0) {
      visible.push(truncateDiff(fileDiff, remaining).diff.slice(0, remaining));
      visibleLength = MAX_DIFF_LENGTH;
      truncatedFiles.push(file);
    } else {
      omittedFiles.push(file);
    }
  }

  const fullDiff = Array.from(diffs.values()).join('\n');
  return {
    diff: visible.join('\n'),
    fullDiff,
    coverage: {
      complete: omittedFiles.length === 0 && truncatedFiles.length === 0,
      omittedFiles,
      truncatedFiles,
      originalLength,
    },
  };
}

function redactForModel(value: string): string {
  return value
    .replace(/-----BEGIN\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----[\s\S]*?-----END\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----/gi, '[REDACTED PRIVATE KEY]')
    .replace(/\bAIZA[A-Z0-9_-]{35}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgh[pousr]_[A-Z0-9]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgithub_pat_[A-Z0-9_]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bsk-(?:live|test)-[A-Z0-9_-]{8,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bAKIA[0-9A-Z]{16}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bxox(?:[aboprs]|b)-[A-Z0-9-]{10,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\b(Bearer|Basic)\s+[A-Z0-9._~+/-]+=*/gi, '$1 [REDACTED CREDENTIAL]')
    .replace(/\b(api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[:=]\s*["']?[^\s,;"'`]+/gi, '$1=[REDACTED CREDENTIAL]');
}

function genericReviewOutput(): RunnerReviewOutput {
  return {
    verdict: 'INCONCLUSIVE',
    roles: ROLE_NAMES.map((role) => ({ role, modelUsed: 'not-run', verdict: 'INCONCLUSIVE', findings: [] })),
    coverage: { complete: false, omittedFiles: [], truncatedFiles: [], originalLength: 0 },
    deterministicViolations: [],
  };
}

function saveReviewOutput(output: RunnerReviewOutput, context: RunnerContext): void {
  const env = context.env ?? process.env;
  const serialized = `${JSON.stringify(output, null, 2)}\n`;
  const outputPath = env.POCKETGUARD_OUTPUT;
  if (!outputPath) {
    stdout(context, serialized);
    return;
  }
  const resolved = path.resolve(outputPath);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  fs.writeFileSync(resolved, serialized, { encoding: 'utf8', mode: 0o600 });
}

function parseAllowedOrigins(env: NodeJS.ProcessEnv): string[] {
  return (env.POCKETGUARD_CPA_ORIGIN ?? '')
    .split(/[\s,]+/)
    .map((value) => value.trim())
    .filter((value) => {
      if (!value) return false;
      try {
        const parsed = new URL(value);
        return parsed.protocol === 'https:' && parsed.origin === value && !parsed.username && !parsed.password;
      } catch {
        return false;
      }
    });
}

function installCpaStub(env: NodeJS.ProcessEnv): () => void {
  if (env.POCKETGUARD_CPA_STUB !== '1') return () => undefined;
  const previousFetch = globalThis.fetch;
  const responseText = JSON.stringify({ verdict: 'APPROVE', summary: '測試審查完成。', findings: [] });
  globalThis.fetch = (async () => new Response(JSON.stringify({
    output: [{ content: [{ type: 'output_text', text: responseText }] }],
  }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch;
  return () => { globalThis.fetch = previousFetch; };
}

export async function runReviewMode(context: RunnerContext = {}): Promise<RunnerReviewOutput> {
  const env = context.env ?? process.env;
  let output = genericReviewOutput();
  try {
    const target = await inspectTarget(context);
    const forcedUnsafe = env.POCKETGUARD_SAFE_REVIEW === 'false';
    if (
      target.target !== 'pull-request' || !target.safeReview || forcedUnsafe || !target.pullRequest ||
      !target.issueNumber || !isCommentCommandAllowed(target.command, 'pull-request')
    ) {
      saveReviewOutput(output, context);
      return output;
    }

    const baseSha = target.pullRequest.base.sha;
    const headSha = target.pullRequest.head.sha;
    const runGit = context.runGit ?? defaultGit;
    try {
      runGit(['fetch', '--no-tags', '--depth=1', 'origin', baseSha, headSha]);
      const changedFiles = getChangedPaths(context, baseSha, headSha);
      const reviewDiff = buildReviewDiff(context, baseSha, headSha, changedFiles);
      const scan = DeterministicScanner.scan(changedFiles, reviewDiff.fullDiff);
      const { orchestrateReview } = await import('./orchestrator');
      const restoreFetch = installCpaStub(env);
      try {
        output = await orchestrateReview({
          changedFiles,
          diff: redactForModel(reviewDiff.diff),
          coverage: reviewDiff.coverage,
          deterministicViolations: scan.violations,
          env,
          allowedOrigins: parseAllowedOrigins(env),
        });
      } finally {
        restoreFetch();
      }
    } catch {
      output = genericReviewOutput();
    }
  } catch {
    output = genericReviewOutput();
  }
  saveReviewOutput(output, context);
  return output;
}

function safeString(value: unknown, maxLength = 2000): string {
  if (typeof value !== 'string') return '';
  return value
    .replace(/-----BEGIN\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----[\s\S]*?-----END\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----/gi, '[REDACTED PRIVATE KEY]')
    .replace(/\bAIZA[A-Z0-9_-]{35}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgh[pousr]_[A-Z0-9]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgithub_pat_[A-Z0-9_]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bsk-(?:live|test)-[A-Z0-9_-]{8,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bAKIA[0-9A-Z]{16}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bxox(?:[aboprs]|b)-[A-Z0-9-]{10,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\b(Bearer|Basic)\s+[A-Z0-9._~+/-]+=*/gi, '$1 [REDACTED CREDENTIAL]')
    .replace(/\b(api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[:=]\s*["']?[^\s,;"'`]+/gi, '$1=[REDACTED CREDENTIAL]')
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .slice(0, maxLength);
}

function validateReviewOutput(value: unknown): RunnerReviewOutput | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  const verdicts = new Set(['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE']);
  if (
    Object.keys(raw).some((key) => !['verdict', 'roles', 'coverage', 'deterministicViolations'].includes(key)) ||
    !verdicts.has(String(raw.verdict)) || !Array.isArray(raw.roles) || raw.roles.length !== ROLE_NAMES.length ||
    !Array.isArray(raw.deterministicViolations)
  ) return undefined;
  const rawCoverage = raw.coverage;
  if (!rawCoverage || typeof rawCoverage !== 'object' || Array.isArray(rawCoverage)) return undefined;
  const coverage = rawCoverage as Record<string, unknown>;
  if (
    Object.keys(coverage).some((key) => !['complete', 'omittedFiles', 'truncatedFiles', 'originalLength'].includes(key)) ||
    typeof coverage.complete !== 'boolean' || !Array.isArray(coverage.omittedFiles) ||
    !coverage.omittedFiles.every((file) => typeof file === 'string') || !Array.isArray(coverage.truncatedFiles) ||
    !coverage.truncatedFiles.every((file) => typeof file === 'string') ||
    !Number.isSafeInteger(coverage.originalLength) || Number(coverage.originalLength) < 0
  ) return undefined;

  const roles: RunnerReviewOutput['roles'] = [];
  for (const rawRole of raw.roles) {
    if (!rawRole || typeof rawRole !== 'object' || Array.isArray(rawRole)) return undefined;
    const role = rawRole as Record<string, unknown>;
    if (
      Object.keys(role).some((key) => !['role', 'modelUsed', 'verdict', 'findings'].includes(key)) ||
      !ROLE_NAMES.includes(role.role as ReviewRoleName) || typeof role.modelUsed !== 'string' || !role.modelUsed.trim() ||
      !verdicts.has(String(role.verdict)) || !Array.isArray(role.findings)
    ) return undefined;
    const findings: RunnerReviewOutput['roles'][number]['findings'] = [];
    for (const rawFinding of role.findings) {
      if (!rawFinding || typeof rawFinding !== 'object' || Array.isArray(rawFinding)) return undefined;
      const finding = rawFinding as Record<string, unknown>;
      if (Object.keys(finding).some((key) => !['severity', 'file', 'line', 'issue', 'suggestion'].includes(key))) return undefined;
      if (
        !['BLOCK', 'WARN', 'SUGGESTION'].includes(String(finding.severity)) ||
        typeof finding.issue !== 'string' || !finding.issue.trim() ||
        (finding.file !== undefined && typeof finding.file !== 'string') ||
        (finding.line !== undefined && (!Number.isInteger(finding.line) || Number(finding.line) < 1)) ||
        (finding.suggestion !== undefined && typeof finding.suggestion !== 'string')
      ) return undefined;
      findings.push({
        severity: finding.severity as 'BLOCK' | 'WARN' | 'SUGGESTION',
        ...(typeof finding.file === 'string' ? { file: safeString(finding.file) } : {}),
        ...(typeof finding.line === 'number' ? { line: finding.line } : {}),
        issue: safeString(finding.issue),
        ...(typeof finding.suggestion === 'string' ? { suggestion: safeString(finding.suggestion) } : {}),
      });
    }
    roles.push({
      role: role.role as ReviewRoleName,
      modelUsed: safeString(role.modelUsed, 200),
      verdict: role.verdict as RunnerVerdict,
      findings,
    });
  }

  const deterministicViolations: ScanViolation[] = [];
  for (const rawViolation of raw.deterministicViolations) {
    if (!rawViolation || typeof rawViolation !== 'object' || Array.isArray(rawViolation)) return undefined;
    const violation = rawViolation as Record<string, unknown>;
    if (
      Object.keys(violation).some((key) => !['ruleId', 'severity', 'category', 'message', 'file', 'line'].includes(key)) ||
      !['BLOCK', 'WARN'].includes(String(violation.severity)) ||
      !['security', 'reliability'].includes(String(violation.category)) ||
      typeof violation.ruleId !== 'string' || typeof violation.message !== 'string' ||
      (violation.file !== undefined && typeof violation.file !== 'string') ||
      (violation.line !== undefined && (!Number.isSafeInteger(violation.line) || Number(violation.line) < 1))
    ) return undefined;
    deterministicViolations.push({
      ruleId: safeString(violation.ruleId, 200),
      severity: violation.severity as 'BLOCK' | 'WARN',
      category: violation.category as 'security' | 'reliability',
      message: safeString(violation.message),
      ...(typeof violation.file === 'string' ? { file: safeString(violation.file) } : {}),
      ...(typeof violation.line === 'number' ? { line: violation.line } : {}),
    });
  }

  if (new Set(roles.map((role) => role.role)).size !== ROLE_NAMES.length) return undefined;
  const hasBlockingFinding = roles.some((role) =>
    role.verdict === 'NEEDS_CHANGES' || role.findings.some((finding) => finding.severity === 'BLOCK'));
  const hasDeterministicBlock = deterministicViolations.some((violation) => violation.severity === 'BLOCK');
  const hasCriticalOmission = (coverage.omittedFiles as string[]).some((file) => {
    const normalized = file.replace(/\\/g, '/').replace(/^\.\//, '').toLowerCase();
    if (normalized.endsWith('.md') || normalized.startsWith('docs/') || normalized.includes('/docs/')) return false;
    if (normalized.endsWith('androidmanifest.xml') || /permission/.test(normalized)) return true;
    if (/(auth|crypto|key|token|credential|biometric)/.test(normalized)) return true;
    if (
      normalized.endsWith('.gradle.kts') || normalized.endsWith('settings.gradle.kts') ||
      normalized.endsWith('libs.versions.toml') || normalized.includes('proguard') ||
      normalized.includes('network-security')
    ) return true;
    return normalized.startsWith('.github/workflows/') || normalized.includes('/.github/workflows/');
  });
  const unanimousApproval = roles.length === ROLE_NAMES.length && roles.every((role) => role.verdict === 'APPROVE');
  const calculatedVerdict: RunnerVerdict = hasBlockingFinding || hasDeterministicBlock
    ? 'NEEDS_CHANGES'
    : unanimousApproval && coverage.complete && (coverage.truncatedFiles as string[]).length === 0 && !hasCriticalOmission
      ? 'APPROVE'
      : 'INCONCLUSIVE';
  if (raw.verdict !== calculatedVerdict) return undefined;

  return {
    verdict: calculatedVerdict,
    roles,
    coverage: {
      complete: coverage.complete,
      omittedFiles: coverage.omittedFiles.filter((file): file is string => typeof file === 'string').map((file) => safeString(file)),
      truncatedFiles: coverage.truncatedFiles.filter((file): file is string => typeof file === 'string').map((file) => safeString(file)),
      originalLength: Number.isSafeInteger(coverage.originalLength) ? Number(coverage.originalLength) : 0,
    },
    deterministicViolations,
  };
}

function escapeMarkdown(value: string): string {
  return safeString(value).replace(/[\\`*_{}\[\]()#+\-.!|<>]/g, '\\$&');
}

function reviewComment(output: RunnerReviewOutput): string {
  if (output.roles.length > 0 && output.roles.every((role) => role.modelUsed === 'not-run')) {
    return `${REVIEW_MARKER}\n\n## PocketGuard 審查\n\n${SAFE_MESSAGE}\n`;
  }
  const lines = [
    REVIEW_MARKER,
    '',
    '## PocketGuard 審查',
    '',
    `**判定：${output.verdict}**`,
    `覆蓋：${output.coverage.complete ? '完整' : '不完整'}；省略 ${output.coverage.omittedFiles.length} 個檔案；截斷 ${output.coverage.truncatedFiles.length} 個檔案。`,
    '',
  ];
  for (const role of output.roles) {
    lines.push(`### ${role.role} — ${role.verdict}`);
    if (role.findings.length === 0) {
      lines.push('- 無符合回報條件的具體發現。');
    }
    for (const finding of role.findings) {
      const location = finding.file
        ? ` (${escapeMarkdown(finding.file)}${finding.line ? `:${finding.line}` : ''})`
        : '';
      lines.push(`- **${finding.severity}**${location}: ${escapeMarkdown(finding.issue)}`);
      if (finding.suggestion) lines.push(`  - 建議：${escapeMarkdown(finding.suggestion)}`);
    }
    lines.push('');
  }
  for (const violation of output.deterministicViolations) {
    lines.push(`- **${violation.severity} ${escapeMarkdown(violation.ruleId)}**: ${escapeMarkdown(violation.message)}`);
  }
  return `${lines.join('\n')}\n`;
}

function eventCommentAllowed(target: ReviewTarget, eventName: string): boolean {
  if (eventName === 'pull_request_target') return target.target === 'pull-request';
  return target.target === 'pull-request' && isCommentCommandAllowed(target.command, 'pull-request');
}

function configuredTagLabels(env: NodeJS.ProcessEnv): string[] {
  try {
    const parsed: unknown = JSON.parse(env.POCKETGUARD_TAG_LABELS ?? '[]');
    return Array.isArray(parsed)
      ? sanitizeLabels(parsed.filter((label): label is string => typeof label === 'string'))
      : [];
  } catch {
    return [];
  }
}

export async function runPublishMode(context: RunnerContext = {}): Promise<void> {
  const env = context.env ?? process.env;
  let event: GithubEvent;
  try {
    event = eventFrom(context);
  } catch {
    return;
  }
  const repository = repositoryParts(env, event);
  if (!repository) return;
  const token = env.GITHUB_TOKEN ?? '';
  const client = apiClient(context, token);
  if (!client) return;

  const target = await inspectTarget(context);
  const eventName = env.GITHUB_EVENT_NAME ?? '';
  if (!target.issueNumber) return;

  if (eventName === 'issues' && target.target === 'issue') {
    const labels = sanitizeLabels([
      ...configuredTagLabels(env),
      ...resolveLabelsFromTitle(target.title),
    ]);
    if (labels.length > 0) {
      try {
        await client.rest.issues.addLabels({
          owner: repository.owner,
          repo: repository.repo,
          issue_number: target.issueNumber,
          labels,
        });
      } catch {
        return;
      }
    }
    return;
  }

  if (!eventCommentAllowed(target, eventName)) return;
  const outputPath = env.POCKETGUARD_OUTPUT ?? 'review-output.json';
  let output: RunnerReviewOutput | undefined;
  try {
    output = validateReviewOutput(JSON.parse(fs.readFileSync(outputPath, 'utf8')) as unknown);
  } catch {
    output = undefined;
  }
  if (!output) return;

  const body = reviewComment(output);
  try {
    const identity = await client.rest.users.getAuthenticated();
    let existing: GithubComment | undefined;
    for (let page = 1; ; page += 1) {
      const comments = await client.rest.issues.listComments({
        owner: repository.owner,
        repo: repository.repo,
        issue_number: target.issueNumber,
        per_page: 100,
        page,
      });
      existing = comments.data.find((comment) =>
        comment.user?.login === identity.data.login && typeof comment.body === 'string' && comment.body.includes(REVIEW_MARKER));
      if (existing || comments.data.length < 100) break;
    }
    if (existing) {
      await client.rest.issues.updateComment({
        owner: repository.owner,
        repo: repository.repo,
        comment_id: existing.id,
        body,
      });
    } else {
      await client.rest.issues.createComment({
        owner: repository.owner,
        repo: repository.repo,
        issue_number: target.issueNumber,
        body,
      });
    }

    const hasSecurityBlock = output.deterministicViolations.some((violation) =>
      violation.severity === 'BLOCK' && (violation.category === 'security' || violation.ruleId.startsWith('SEC-'))) ||
      output.roles.some((role) => role.role === 'android_sec' && role.findings.some((finding) => finding.severity === 'BLOCK'));
    const labels = sanitizeLabels([
      ...configuredTagLabels(env),
      ...resolveReviewLabels({
        verdict: output.verdict,
        hasSecurityFinding: hasSecurityBlock,
      }),
    ]);
    await reconcileBotLabelsSafely({
      client,
      owner: repository.owner,
      repo: repository.repo,
      issueNumber: target.issueNumber,
      desiredLabels: labels,
      scope: DEFAULT_PR_RECONCILE_SCOPE,
      coverageComplete: output.coverage.complete,
    });
  } catch {
    return;
  }
}

function modeFromArgs(args: string[]): 'tag' | 'review' | 'publish' | undefined {
  const inline = args.find((arg) => arg.startsWith('--mode='))?.slice('--mode='.length);
  const index = args.indexOf('--mode');
  const mode = inline ?? (index >= 0 ? args[index + 1] : undefined);
  return mode === 'tag' || mode === 'review' || mode === 'publish' ? mode : undefined;
}

async function main(): Promise<void> {
  const mode = modeFromArgs(process.argv.slice(2));
  if (!mode) {
    process.stderr.write('PocketGuard: specify --mode=tag, --mode=review, or --mode=publish.\n');
    process.exitCode = 2;
    return;
  }
  try {
    if (mode === 'tag') await runTagMode();
    if (mode === 'review') await runReviewMode();
    if (mode === 'publish') await runPublishMode();
  } catch {
    process.stderr.write('PocketGuard: operation failed.\n');
    process.exitCode = 1;
  }
}

if (require.main === module) void main();
