import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  sendOpenAISingleTurnWithFallback,
  resolveRoleModel,
  type OpenAIEnvironment,
  type OpenAIModelRole,
} from './send_openai';
import { redactForModel } from './redact';
import type { ReviewCoverage } from './review_diff';
import type { ScanViolation } from './deterministic_scanner';

export type ReviewVerdict = 'APPROVE' | 'NEEDS_CHANGES' | 'INCONCLUSIVE';
export type FindingSeverity = 'BLOCK' | 'WARN' | 'SUGGESTION';

export interface ReviewFinding {
  severity: FindingSeverity;
  file?: string;
  line?: number;
  issue: string;
  suggestion?: string;
}

export interface RoleReview {
  role: OpenAIModelRole;
  modelUsed: string;
  verdict: ReviewVerdict;
  findings: ReviewFinding[];
  // S5 AI label schema (strict): raw model-suggested labels as a string
  // array. Only sanitized through the allowlist at publish time; unknown
  // entries are discarded there and force a non-APPROVE verdict. Decision
  // record: only issue_triage.md and architecture.md instruct label suggestions
  // (minimal prompt change); android_sec/android_code prompts stay untouched
  // and their responses default to [] when the field is absent.
  suggestedLabels: string[];
  summary?: string;
}

export interface OrchestratedReview {
  verdict: ReviewVerdict;
  roles: RoleReview[];
  coverage: ReviewCoverage;
  deterministicViolations: ScanViolation[];
}

export interface OrchestratorOptions {
  changedFiles: string[];
  diff: string;
  coverage: ReviewCoverage;
  deterministicViolations: ScanViolation[];
  env?: OpenAIEnvironment;
  promptDirectory?: string;
  allowedOrigins?: string[];
  // Phase 3 (P2 #3): per-wave timeout passthrough (fail-closed). When set,
  // forwarded to every fallback-chain role call so slow roles abort via
  // the transport timeout instead of hanging the wave. Omitted keeps
  // the send default (420s). Must be >0 when provided; invalid values throw
  // fail-closed (zero AI) via the send path.
  timeoutMs?: number;
}

const ROLES: readonly OpenAIModelRole[] = ['architecture', 'android_sec', 'android_code'];
const VERDICTS = new Set<ReviewVerdict>(['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE']);
const SEVERITIES = new Set<FindingSeverity>(['BLOCK', 'WARN', 'SUGGESTION']);
const MAX_TEXT_LENGTH = 2000;

function reviewTier(filePath: string): number {
  const normalized = filePath.replace(/\\/g, '/').replace(/^\.\//, '').toLowerCase();
  if (normalized.endsWith('.md') || normalized.startsWith('docs/') || normalized.includes('/docs/')) return 6;
  if (normalized.endsWith('androidmanifest.xml') || /permission/.test(normalized)) return 0;
  if (/(auth|crypto|key|token|credential|biometric)/.test(normalized)) return 1;
  if (
    normalized.endsWith('.gradle.kts') ||
    normalized.endsWith('settings.gradle.kts') ||
    normalized.endsWith('libs.versions.toml') ||
    normalized.includes('proguard') ||
    normalized.includes('network-security')
  ) return 2;
  if (normalized.startsWith('.github/workflows/') || normalized.includes('/.github/workflows/')) return 3;
  return 5;
}

function redactSensitiveText(value: string): string {
  return redactForModel(value)
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, ' ')
    .slice(0, MAX_TEXT_LENGTH);
}

function tryParseObject(text: string): Record<string, unknown> | undefined {
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : undefined;
  } catch {
    return undefined;
  }
}

export function parseJsonObject(text: string): Record<string, unknown> | undefined {
  if (typeof text !== 'string') return undefined;
  const trimmed = text.trim();
  if (!trimmed) return undefined;

  const direct = tryParseObject(trimmed);
  if (direct) return direct;

  const codeBlockMatch = trimmed.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
  if (codeBlockMatch && codeBlockMatch[1]) {
    const fromCodeBlock = tryParseObject(codeBlockMatch[1].trim());
    if (fromCodeBlock) return fromCodeBlock;
  }

  const firstBrace = trimmed.indexOf('{');
  const lastBrace = trimmed.lastIndexOf('}');
  if (firstBrace !== -1 && lastBrace > firstBrace) {
    const fromBraces = tryParseObject(trimmed.slice(firstBrace, lastBrace + 1));
    if (fromBraces) return fromBraces;
  }

  return undefined;
}

export function parseFindings(value: unknown): ReviewFinding[] | undefined {
  if (value === undefined) return [];
  if (!Array.isArray(value)) return undefined;
  const result: ReviewFinding[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
    const finding = raw as Record<string, unknown>;
    if (
      !SEVERITIES.has(finding.severity as FindingSeverity) ||
      typeof finding.issue !== 'string' ||
      !finding.issue.trim() ||
      (finding.file !== undefined && typeof finding.file !== 'string') ||
      (finding.line !== undefined && (!Number.isInteger(finding.line) || Number(finding.line) < 1)) ||
      (finding.suggestion !== undefined && typeof finding.suggestion !== 'string')
    ) return undefined;

    result.push({
      severity: finding.severity as FindingSeverity,
      ...(typeof finding.file === 'string' ? { file: redactSensitiveText(finding.file) } : {}),
      ...(typeof finding.line === 'number' ? { line: finding.line } : {}),
      issue: redactSensitiveText(finding.issue),
      ...(typeof finding.suggestion === 'string' ? { suggestion: redactSensitiveText(finding.suggestion) } : {}),
    });
  }
  return result;
}

export function parseSuggestedLabelsStrict(value: unknown): string[] | undefined {
  if (value === undefined) return [];
  if (!Array.isArray(value)) return undefined;
  for (const entry of value) {
    if (typeof entry !== 'string') return undefined;
  }
  return [...(value as string[])];
}

export function parseRoleResponse(role: OpenAIModelRole, modelUsed: string, content: string): RoleReview {
  const sanitizedModel = redactSensitiveText(modelUsed);
  const fallback: RoleReview = { role, modelUsed: sanitizedModel, verdict: 'INCONCLUSIVE', findings: [], suggestedLabels: [], summary: '' };
  const parsed = parseJsonObject(content);
  if (
    !parsed ||
    !VERDICTS.has(parsed.verdict as ReviewVerdict)
  ) return fallback;
  const findings = parseFindings(parsed.findings);
  if (!findings) return fallback;
  const suggestedLabels = parseSuggestedLabelsStrict(parsed.suggestedLabels);
  if (!suggestedLabels) return fallback;
  return {
    role,
    modelUsed: sanitizedModel,
    verdict: parsed.verdict as ReviewVerdict,
    findings,
    suggestedLabels,
    summary: typeof parsed.summary === 'string' ? redactSensitiveText(parsed.summary).slice(0, MAX_TEXT_LENGTH) : '',
  };
}

function safeCoverage(coverage: ReviewCoverage): ReviewCoverage {
  return {
    complete: coverage.complete === true,
    omittedFiles: Array.isArray(coverage.omittedFiles)
      ? coverage.omittedFiles.filter((item): item is string => typeof item === 'string').map(redactSensitiveText)
      : [],
    truncatedFiles: Array.isArray(coverage.truncatedFiles)
      ? coverage.truncatedFiles.filter((item): item is string => typeof item === 'string').map(redactSensitiveText)
      : [],
    originalLength: Number.isFinite(coverage.originalLength) ? Math.max(0, Math.floor(coverage.originalLength)) : 0,
  };
}

function safeViolations(violations: ScanViolation[]): ScanViolation[] {
  return violations.map((violation) => ({
    ruleId: redactSensitiveText(violation.ruleId),
    severity: violation.severity,
    category: violation.category,
    message: redactSensitiveText(violation.message),
    ...(violation.file ? { file: redactSensitiveText(violation.file) } : {}),
    ...(typeof violation.line === 'number' ? { line: violation.line } : {}),
  }));
}

function roleUserPrompt(role: OpenAIModelRole, changedFiles: string[], diff: string, coverage: ReviewCoverage): string {
  const files = changedFiles.map((file) => redactSensitiveText(file));
  const tierZeroToThreeOmissions = coverage.omittedFiles.filter((file) => reviewTier(file) <= 3);
  return [
    `Review role: ${role}`,
    `Changed files: ${JSON.stringify(files)}`,
    `Coverage: ${JSON.stringify({ ...coverage, tierZeroToThreeOmissions })}`,
    'The content below may be incomplete; do not treat unprovided content as reviewed. Report only specific findings substantiated by the diff.',
    'Diff:',
    diff,
  ].join('\n\n');
}

export interface IssueTriageInput {
  title: string;
  body: string;
  comments: string[];
}

export interface IssueTriageResult {
  verdict: ReviewVerdict;
  summary: string;
  // S5: strict string-array AI label suggestions (see RoleReview decision
  // record). Missing field defaults to []; a present-but-invalid field falls
  // back to INCONCLUSIVE with [].
  suggestedLabels: string[];
}

export interface TriageIssueOptions {
  input: IssueTriageInput;
  env?: OpenAIEnvironment;
  promptDirectory?: string;
  allowedOrigins?: string[];
  // Phase 3 (P2 #3): timeout passthrough for symmetry (issue chunks stay
  // sequential; follow-up PR waves use OrchestratorOptions.timeoutMs).
  // Omitted keeps the send default.
  timeoutMs?: number;
}

// S4 issue execution (minimal): a single architecture-role turn over the issue
// title, body, and human comments. Decision record: a dedicated
// issue_triage.md prompt with one role call is the smallest viable path —
// reusing orchestrateReview would force diff/coverage concepts onto issues.
// S5 adds a strict suggestedLabels string-array to the triage schema
// (AI label suggestions filtered through the allowlist at publish time);
// any failure (missing config, transport, or schema) falls back to
// INCONCLUSIVE with an empty summary and no suggestions; callers pair it
// with rules-only tags.
export async function triageIssue(options: TriageIssueOptions): Promise<IssueTriageResult> {
  const fallback: IssueTriageResult = { verdict: 'INCONCLUSIVE', summary: '', suggestedLabels: [] };
  try {
    const env = options.env ?? process.env;
    const promptDirectory = options.promptDirectory ?? path.resolve(__dirname, '../prompts');
    const modelUsed = resolveRoleModel('architecture', env);
    const systemPrompt = fs.readFileSync(path.join(promptDirectory, 'issue_triage.md'), 'utf8');
    // Issue triage carries human-supplied title/body/comments with no
    // authorization gate (issue-auto). Redact the AI-bound copy with the same
    // deterministic patterns as the PR diff path so pasted credentials never
    // leave the runner in cleartext. Callers needing detection must scan the
    // ORIGINAL input before this call; scanning the redacted copy misses.
    const userPrompt = [
      `Title: ${redactForModel(options.input.title)}`,
      `Body: ${redactForModel(options.input.body)}`,
      ...options.input.comments.map((comment) => `Comment: ${redactForModel(comment)}`),
    ].join('\n\n');
    const result = await sendOpenAISingleTurnWithFallback({
      modelId: modelUsed,
      modelRole: 'architecture',
      systemPrompt,
      userPrompt,
      maxOutputTokens: 1024,
      allowedOrigins: options.allowedOrigins,
      env,
      ...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
    });
    const parsed = parseJsonObject(result.content);
    if (
      !parsed ||
      Object.keys(parsed).some((key) => !['verdict', 'summary', 'suggestedLabels'].includes(key)) ||
      !VERDICTS.has(parsed.verdict as ReviewVerdict) ||
      typeof parsed.summary !== 'string'
    ) return fallback;
    const suggestedLabels = parseSuggestedLabelsStrict(parsed.suggestedLabels);
    if (!suggestedLabels) return fallback;
    return {
      verdict: parsed.verdict as ReviewVerdict,
      summary: redactSensitiveText(parsed.summary).slice(0, MAX_TEXT_LENGTH),
      suggestedLabels,
    };
  } catch {
    return fallback;
  }
}

export async function orchestrateReview(options: OrchestratorOptions): Promise<OrchestratedReview> {
  const coverage = safeCoverage(options.coverage);
  const deterministicViolations = safeViolations(options.deterministicViolations);
  const env = options.env ?? process.env;
  const promptDirectory = options.promptDirectory ?? path.resolve(__dirname, '../prompts');

  const roleReviews = await Promise.all(ROLES.map(async (role): Promise<RoleReview> => {
    let modelUsed = 'unavailable';
    try {
      modelUsed = resolveRoleModel(role, env);
      const systemPrompt = fs.readFileSync(path.join(promptDirectory, `${role}.md`), 'utf8');
      const result = await sendOpenAISingleTurnWithFallback({
        modelId: modelUsed,
        modelRole: role,
        systemPrompt,
        userPrompt: roleUserPrompt(role, options.changedFiles, options.diff, coverage),
        allowedOrigins: options.allowedOrigins,
        env,
        ...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
      });
      // modelUsed follows the model that actually served the turn, so a fallback
      // is attributed truthfully in reports.
      return parseRoleResponse(role, result.modelId, result.content);
    } catch {
      return { role, modelUsed: redactSensitiveText(modelUsed), verdict: 'INCONCLUSIVE', findings: [], suggestedLabels: [] };
    }
  }));

  const hasBlockingFinding = roleReviews.some((role) =>
    role.verdict === 'NEEDS_CHANGES' || role.findings.some((finding) => finding.severity === 'BLOCK'));
  const hasDeterministicBlock = deterministicViolations.some((violation) => violation.severity === 'BLOCK');
  const hasCriticalOmission = coverage.omittedFiles.some((file) => reviewTier(file) <= 3);
  const unanimousApproval = roleReviews.length === ROLES.length && roleReviews.every((role) => role.verdict === 'APPROVE');
  const verdict: ReviewVerdict = hasBlockingFinding || hasDeterministicBlock
    ? 'NEEDS_CHANGES'
    : unanimousApproval && coverage.complete && !hasCriticalOmission
      ? 'APPROVE'
      : 'INCONCLUSIVE';

  return { verdict, roles: roleReviews, coverage, deterministicViolations };
}

// Second-round synthesis (P4): verifies a first-round APPROVE before it is
// stored. Runs only when the first round already concluded APPROVE; any other
// first-round verdict is strict enough on its own and skips synthesis.
// The synthesis may only confirm APPROVE or tighten to NEEDS_CHANGES /
// INCONCLUSIVE — never loosen. Any transport/schema/timeout failure returns
// ok:false and the caller must fall back to INCONCLUSIVE (an unconfirmed
// APPROVE is never stored).
export const SYNTHESIS_DIFF_EXCERPT_CHARS = 12000;
export const SYNTHESIS_MAX_FINDINGS_PER_ROLE = 20;
export const SYNTHESIS_MAX_OUTPUT_TOKENS = 2048;
export const SYNTHESIS_MAX_APPENDED_FINDINGS = 20;

export interface SynthesisResult {
  ok: boolean;
  modelUsed: string;
  verdict: ReviewVerdict;
  findings: ReviewFinding[];
  // Raw evidence quotes aligned with findings by index (unredacted shape is
  // validated, not trusted: the runner checks literal support before use).
  evidences: string[];
  summary: string;
}

export interface SynthesizeReviewOptions {
  firstRound: OrchestratedReview;
  diffExcerpt: string;
  diffTruncated: boolean;
  originalLength: number;
  env?: OpenAIEnvironment;
  promptDirectory?: string;
  allowedOrigins?: string[];
  timeoutMs?: number;
}

export function parseSynthesisResponse(modelUsed: string, content: string): SynthesisResult | undefined {
  const sanitizedModel = redactSensitiveText(modelUsed);
  const parsed = parseJsonObject(content);
  if (
    !parsed ||
    Object.keys(parsed).some((key) => !['verdict', 'summary', 'findings', 'suggestedLabels'].includes(key)) ||
    !VERDICTS.has(parsed.verdict as ReviewVerdict)
  ) return undefined;
  const findings = parseFindings(parsed.findings);
  if (!findings) return undefined;
  return {
    ok: true,
    modelUsed: sanitizedModel,
    verdict: parsed.verdict as ReviewVerdict,
    findings,
    evidences: extractSynthesisEvidence(content) ?? [],
    summary: typeof parsed.summary === 'string' ? redactSensitiveText(parsed.summary).slice(0, MAX_TEXT_LENGTH) : '',
  };
}

// Raw evidence quotes from a synthesis response, aligned with the validated
// findings by index. Returns undefined when the content is not shaped like a
// synthesis response. Callers must check literal support (see
// selectUsableBlockFindings) before acting on any entry.
export function extractSynthesisEvidence(content: string): string[] | undefined {
  const parsed = parseJsonObject(content);
  if (!parsed || !Array.isArray(parsed.findings)) return undefined;
  const out: string[] = [];
  for (const raw of parsed.findings) {
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
      const evidence = (raw as Record<string, unknown>).evidence;
      out.push(typeof evidence === 'string' ? evidence : '');
    } else {
      out.push('');
    }
  }
  return out;
}

// Minimum evidence length: trivial quotes match spuriously, so short
// evidence never supports a BLOCK finding.
export const MIN_SYNTHESIS_EVIDENCE_LENGTH = 8;

// Selects the BLOCK findings whose evidence quote is literally supported by
// the reviewed input (diff excerpt or deterministic violation text).
// Unsupported, missing, or trivial evidence is unusable: a BLOCK that cannot
// be verified must never tighten the verdict on its own.
export function selectUsableBlockFindings(
  findings: ReviewFinding[],
  evidences: string[],
  diffExcerpt: string,
  violationTexts: string[],
): ReviewFinding[] {
  return findings.filter((finding, index) => {
    if (finding.severity !== 'BLOCK') return false;
    const evidence = (evidences[index] ?? '').trim();
    if (evidence.length < MIN_SYNTHESIS_EVIDENCE_LENGTH) return false;
    if (diffExcerpt.includes(evidence)) return true;
    return violationTexts.some((text) => typeof text === 'string' && text.includes(evidence));
  });
}

function synthesisUserPrompt(firstRound: OrchestratedReview, diffExcerpt: string, diffTruncated: boolean, originalLength: number): string {
  const roles = firstRound.roles.map((role) => ({
    role: role.role,
    modelUsed: role.modelUsed,
    verdict: role.verdict,
    findings: role.findings.slice(0, SYNTHESIS_MAX_FINDINGS_PER_ROLE),
    summary: role.summary,
  }));
  return [
    'First-round reviews (parallel, identical diff input):',
    JSON.stringify(roles),
    `Coverage: ${JSON.stringify(firstRound.coverage)}`,
    `Deterministic violations: ${JSON.stringify(firstRound.deterministicViolations)}`,
    `Diff excerpt (${diffExcerpt.length} of ${originalLength} chars${diffTruncated ? ', truncated' : ''}):`,
    'The content below may be incomplete; do not treat unprovided content as reviewed. A BLOCK finding must cite evidence literally present below.',
    diffExcerpt,
  ].join('\n\n');
}

export async function synthesizeReview(options: SynthesizeReviewOptions): Promise<SynthesisResult> {
  let modelUsed = 'unavailable';
  try {
    const env = options.env ?? process.env;
    const promptDirectory = options.promptDirectory ?? path.resolve(__dirname, '../prompts');
    modelUsed = resolveRoleModel('architecture', env);
    const systemPrompt = fs.readFileSync(path.join(promptDirectory, 'synthesizer.md'), 'utf8');
    // Defense in depth: re-redact the excerpt so a future caller passing raw
    // content cannot leak credentials into the model request.
    const excerpt = redactForModel(options.diffExcerpt);
    const result = await sendOpenAISingleTurnWithFallback({
      modelId: modelUsed,
      modelRole: 'architecture',
      systemPrompt,
      userPrompt: synthesisUserPrompt(options.firstRound, excerpt, options.diffTruncated, options.originalLength),
      maxOutputTokens: SYNTHESIS_MAX_OUTPUT_TOKENS,
      allowedOrigins: options.allowedOrigins,
      env,
      ...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
    });
    return parseSynthesisResponse(result.modelId, result.content) ?? { ok: false, modelUsed: redactSensitiveText(result.modelId), verdict: 'INCONCLUSIVE', findings: [], evidences: [], summary: '' };
  } catch {
    return { ok: false, modelUsed: redactSensitiveText(modelUsed), verdict: 'INCONCLUSIVE', findings: [], evidences: [], summary: '' };
  }
}
