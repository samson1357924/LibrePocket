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
  /**
   * F4 (review #77 P1-2): absolute wall-clock deadline (Date.now() ms)
   * forwarded to every fallback-chain call so the summed attempts stay
   * inside the job budget. Optional; omitted keeps legacy behavior.
   */
  deadlineMs?: number;
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
  // P6: default-branch file paths the model wants read for diagnosis.
  // Missing field defaults to [] (no second round); a present-but-invalid
  // field falls back to INCONCLUSIVE like any other schema violation.
  codeRefs: string[];
}

export interface TriageIssueOptions {
  input: IssueTriageInput;
  env?: OpenAIEnvironment;
  promptDirectory?: string;
  allowedOrigins?: string[];
  // Phase 3 (P2 #3): timeout passthrough (E2E uses short timeouts to force
  // transport aborts without waiting the 420s default). Omitted keeps
  // the send default.
  timeoutMs?: number;
  /**
   * F4 (review #77 P1-2): absolute wall-clock deadline (Date.now() ms)
   * forwarded to every fallback-chain call so the summed attempts stay
   * inside the job budget. Optional; omitted keeps legacy behavior.
   */
  deadlineMs?: number;
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
export function parseIssueCodeRefs(value: unknown): string[] | undefined {
  if (value === undefined) return [];
  if (!Array.isArray(value)) return undefined;
  const refs: string[] = [];
  for (const entry of value) {
    if (typeof entry !== 'string' || !entry.trim()) return undefined;
    refs.push(entry.trim());
  }
  return refs;
}

export async function triageIssue(options: TriageIssueOptions): Promise<IssueTriageResult> {
  const fallback: IssueTriageResult = { verdict: 'INCONCLUSIVE', summary: '', suggestedLabels: [], codeRefs: [] };
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
      ...(options.deadlineMs !== undefined ? { deadlineMs: options.deadlineMs } : {}),
    });
    const parsed = parseJsonObject(result.content);
    if (
      !parsed ||
      Object.keys(parsed).some((key) => !['verdict', 'summary', 'suggestedLabels', 'codeRefs'].includes(key)) ||
      !VERDICTS.has(parsed.verdict as ReviewVerdict) ||
      typeof parsed.summary !== 'string'
    ) return fallback;
    const suggestedLabels = parseSuggestedLabelsStrict(parsed.suggestedLabels);
    if (!suggestedLabels) return fallback;
    const codeRefs = parseIssueCodeRefs(parsed.codeRefs);
    if (!codeRefs) return fallback;
    return {
      verdict: parsed.verdict as ReviewVerdict,
      summary: redactSensitiveText(parsed.summary).slice(0, MAX_TEXT_LENGTH),
      suggestedLabels,
      codeRefs,
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
        ...(options.deadlineMs !== undefined ? { deadlineMs: options.deadlineMs } : {}),
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

// Second-round synthesis (P4): evidence-scoped confirmation of a first-round
// APPROVE before it is stored (F5: head sample + finding-anchored snippets,
// never a full re-review). Runs only when the first round already concluded
// APPROVE; any other first-round verdict is strict enough on its own and
// skips synthesis.
// The synthesis may only confirm APPROVE or tighten to NEEDS_CHANGES /
// INCONCLUSIVE — never loosen. Any transport/schema/timeout failure returns
// ok:false and the caller must fall back to INCONCLUSIVE (an unconfirmed
// APPROVE is never stored).
export const SYNTHESIS_DIFF_EXCERPT_CHARS = 12000;
// F5 (review #77 P2-5): the excerpt budget is split into a head sample plus
// evidence-anchored snippets so tail findings stay verifiable. Snippet text
// is always a verbatim substring of the redacted per-file diff (required:
// the BLOCK-evidence check is a literal substring match).
export const SYNTHESIS_HEAD_EXCERPT_CHARS = 6000;
export const SYNTHESIS_SNIPPET_CHARS_PER_FILE = 2000;
export const SYNTHESIS_SNIPPET_CONTEXT_LINES = 30;
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
  /**
   * F4 (review #77 P1-2): absolute wall-clock deadline (Date.now() ms)
   * forwarded to every fallback-chain call so the summed attempts stay
   * inside the job budget. Optional; omitted keeps legacy behavior.
   */
  deadlineMs?: number;
  /** F5: excerpt plan stats for the prompt header (no schema effect). */
  excerptPlan?: Pick<SynthesisExcerptPlan, 'headChars' | 'snippetCount' | 'anchorsTotal' | 'omittedAnchors'>;
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

// File scope for BLOCK-evidence verification (H2): quotes must be
// attributable to the finding's own file/line, not merely present anywhere.
export interface BlockEvidenceScope {
  /** Redacted-view source of truth: per-file diffs keyed by repo path. */
  fileDiffs: Map<string, string>;
  /** Review-eligible file set (G1): excluded files never support BLOCK. */
  allowedFiles: ReadonlySet<string> | readonly string[];
  /**
   * Structured deterministic violations (same-file fallback only). Only
   * ruleId/message/file(/line) are read, so callers pass a narrow shape.
   */
  violations: Array<{ ruleId: string; message: string; file?: string; line?: number }>;
}

// Selects the BLOCK findings whose evidence quote is literally supported by
// the reviewed input. H2: the quote must be attributable to the finding's
// own file — a quote that only appears in another file is unusable even if
// it is inside the excerpt. Diff quotes additionally require a valid line
// (when given) and an excerpt hit (the excerpt is budgeted); same-file
// deterministic violation quotes are fully rendered in the prompt, so they
// suffice without an excerpt hit.
// Unsupported, missing, or trivial evidence is unusable: a BLOCK that cannot
// be verified must never tighten the verdict on its own.
export function selectUsableBlockFindings(
  findings: ReviewFinding[],
  evidences: string[],
  diffExcerpt: string,
  scope: BlockEvidenceScope,
): ReviewFinding[] {
  const allowed = new Set([...scope.allowedFiles]);
  return findings.filter((finding, index) => {
    if (finding.severity !== 'BLOCK') return false;
    const evidence = (evidences[index] ?? '').trim();
    if (evidence.length < MIN_SYNTHESIS_EVIDENCE_LENGTH) return false;
    const file = typeof finding.file === 'string' ? finding.file.trim() : '';
    if (!file) return false;
    const key = resolveAnchorFile(scope.fileDiffs, file);
    if (!key || !allowed.has(key)) return false;
    const redactedFile = redactForModel(scope.fileDiffs.get(key) ?? '');
    const rawLine = finding.line;
    if (rawLine !== undefined && (typeof rawLine !== 'number' || !Number.isInteger(rawLine))) return false;
    const line = typeof rawLine === 'number' ? rawLine : undefined;
    if (line !== undefined) {
      const hunks = parseSynthesisHunks(redactedFile);
      if (!hunks.some((hunk) => line >= hunk.newStart && line < hunk.newStart + hunk.newCount)) return false;
    }
    // Diff quotes must also be model-visible (the excerpt is budgeted).
    if (redactedFile.includes(evidence) && diffExcerpt.includes(evidence)) return true;
    // Violation quotes are fully rendered in the prompt, so a same-file
    // violation carrying the quote suffices without an excerpt hit.
    return scope.violations.some((violation) => {
      if (typeof violation.file !== 'string' || !violation.file) return false;
      if (resolveAnchorFile(scope.fileDiffs, violation.file) !== key) return false;
      // Match rule text only: a bare file path (>=8 chars) must not count
      // as supporting evidence on its own.
      return `${violation.ruleId} ${violation.message}`.includes(evidence);
    });
  });
}

export interface SynthesisExcerptAnchor {
  file: string;
  line?: number;
}

export interface SynthesisExcerptPlan {
  excerpt: string;
  fullLength: number;
  headChars: number;
  snippetCount: number;
  anchorsTotal: number;
  omittedAnchors: string[];
}

function normalizeAnchorFile(file: string): string {
  return file.trim().replace(/\\/g, '/').replace(/^(a\/|b\/)/, '');
}

function resolveAnchorFile(fileDiffs: Map<string, string>, file: string): string | undefined {
  const norm = normalizeAnchorFile(file);
  if (!norm) return undefined;
  if (fileDiffs.has(norm)) return norm;
  const cands = [...fileDiffs.keys()].filter((key) => key === norm || key.endsWith(`/${norm}`));
  // Ambiguous short names resolve to nothing (fail-closed, counted omitted).
  return cands.length === 1 ? cands[0] : undefined;
}

interface SynthesisHunk {
  newStart: number;
  newCount: number;
  bodyLines: string[];
}

function parseSynthesisHunks(redactedFileDiff: string): SynthesisHunk[] {
  const hunks: SynthesisHunk[] = [];
  let cur: SynthesisHunk | undefined;
  for (const ln of redactedFileDiff.split('\n')) {
    const m = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/.exec(ln);
    if (m) {
      if (cur) hunks.push(cur);
      cur = { newStart: parseInt(m[1], 10), newCount: m[2] === undefined ? 1 : parseInt(m[2], 10), bodyLines: [ln] };
    } else if (cur) {
      cur.bodyLines.push(ln);
    }
  }
  if (cur) hunks.push(cur);
  return hunks;
}

// Window around a target new-file line inside one hunk. Hunk body lines
// starting with '+' or ' ' advance the new-file line counter ('-' and
// '\\' marker lines do not).
function windowHunkLines(hunk: SynthesisHunk, targetLine: number | undefined, contextLines: number): string[] {
  let targetIdx = -1;
  if (targetLine !== undefined) {
    let newLine = hunk.newStart;
    for (let i = 1; i < hunk.bodyLines.length; i += 1) {
      const ln = hunk.bodyLines[i];
      if (ln.startsWith('+') || ln.startsWith(' ')) {
        if (newLine === targetLine) {
          targetIdx = i;
          break;
        }
        newLine += 1;
      }
    }
  }
  const center = targetIdx >= 0 ? targetIdx : 1;
  const from = Math.max(0, center - contextLines);
  const to = Math.min(hunk.bodyLines.length, center + contextLines + 1);
  return [hunk.bodyLines[0], ...hunk.bodyLines.slice(from === 0 ? 1 : from, to)];
}

// F5: head sample + evidence-anchored snippets within the fixed total
// budget. Anchors that cannot be resolved, fall outside the review-eligible
// set (G1), or no longer fit are recorded in omittedAnchors (surfaced in the
// prompt plan) instead of silently dropped.
export function buildSynthesisExcerpt(input: {
  diff: string;
  fileDiffs: Map<string, string>;
  anchors: SynthesisExcerptAnchor[];
  /**
   * G1: review-eligible file set (required). A resolved anchor outside this
   * set is recorded omitted — deliberately excluded files (e.g.
   * local.properties) can never be re-fetched through a model-controlled
   * anchor.
   */
  allowedFiles: ReadonlySet<string> | readonly string[];
}): SynthesisExcerptPlan {
  const redactedFull = redactForModel(input.diff);
  const head = redactedFull.slice(0, SYNTHESIS_HEAD_EXCERPT_CHARS);
  const parts: string[] = [head];
  let budget = SYNTHESIS_DIFF_EXCERPT_CHARS - head.length;
  const seen = new Set<string>();
  const distinct = input.anchors.filter((a) => {
    if (typeof a.file !== 'string' || !a.file.trim()) return false;
    const key = `${normalizeAnchorFile(a.file)}:${typeof a.line === 'number' ? a.line : ''}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const omitted: string[] = [];
  let snippets = 0;
  const allowed = new Set([...input.allowedFiles]);
  const labelOf = (file: string, line: number | undefined): string => {
    // Omitted labels land in the prompt plan: keep them single-line and
    // bounded (anchors are model-controlled).
    const flat = file.replace(/[\r\n]+/g, ' ').trim().slice(0, 120) || '(empty)';
    return line === undefined ? flat : `${flat}#${line}`;
  };
  for (const anchorObj of distinct) {
    const label = labelOf(anchorObj.file, typeof anchorObj.line === 'number' ? anchorObj.line : undefined);
    if (budget <= 0) {
      omitted.push(label);
      continue;
    }
    const key = resolveAnchorFile(input.fileDiffs, anchorObj.file);
    // G1: excluded files stay excluded even when named by an anchor.
    if (!key || !allowed.has(key)) {
      omitted.push(label);
      continue;
    }
    const redacted = redactForModel(input.fileDiffs.get(key) ?? '');
    const hunks = parseSynthesisHunks(redacted);
    if (hunks.length === 0) {
      omitted.push(label);
      continue;
    }
    const line = typeof anchorObj.line === 'number' ? anchorObj.line : undefined;
    const covering = line === undefined
      ? []
      : hunks.filter((h) => line >= h.newStart && line < h.newStart + h.newCount);
    // A specified line with no covering hunk is recorded omitted (never a
    // misleading first-hunk fallback); a lineless anchor takes the head hunk.
    if (line !== undefined && covering.length === 0) {
      omitted.push(label);
      continue;
    }
    const hunksToUse = covering.length > 0 ? covering : hunks.slice(0, 1);
    let context = SYNTHESIS_SNIPPET_CONTEXT_LINES;
    let windowed: string[] = [];
    for (const hunk of hunksToUse) {
      windowed = windowHunkLines(hunk, line, context);
      while (windowed.join('\n').length > SYNTHESIS_SNIPPET_CHARS_PER_FILE && context > 3) {
        context = Math.floor(context / 2);
        windowed = windowHunkLines(hunk, line, context);
      }
      if (windowed.join('\n').length <= SYNTHESIS_SNIPPET_CHARS_PER_FILE) break;
    }
    const chunk = [`--- evidence snippet: ${key}${line !== undefined ? ` (around line ${line})` : ''} ---`, ...windowed].join('\n');
    if (chunk.length > budget || chunk.length > SYNTHESIS_SNIPPET_CHARS_PER_FILE) {
      omitted.push(label);
      continue;
    }
    parts.push(chunk);
    budget -= chunk.length;
    snippets += 1;
  }
  return {
    excerpt: parts.join('\n'),
    fullLength: redactedFull.length,
    headChars: head.length,
    snippetCount: snippets,
    anchorsTotal: distinct.length,
    omittedAnchors: omitted,
  };
}

function synthesisUserPrompt(
  firstRound: OrchestratedReview,
  diffExcerpt: string,
  diffTruncated: boolean,
  originalLength: number,
  plan?: Pick<SynthesisExcerptPlan, 'headChars' | 'snippetCount' | 'anchorsTotal' | 'omittedAnchors'>,
): string {
  const roles = firstRound.roles.map((role) => {
    const shown = role.findings.slice(0, SYNTHESIS_MAX_FINDINGS_PER_ROLE);
    return {
      role: role.role,
      modelUsed: role.modelUsed,
      verdict: role.verdict,
      // F5: silent findings truncation is surfaced, not hidden.
      findingsShown: `${shown.length}/${role.findings.length}`,
      findings: shown,
      summary: role.summary,
    };
  });
  // F5: the excerpt plan states exactly what was re-examined so an APPROVE
  // here is never misread as a full re-review of the whole diff.
  const planLine = plan
    ? `Excerpt plan: head ${plan.headChars} chars + ${plan.snippetCount} targeted snippet(s) for ${plan.anchorsTotal} anchored location(s)`
      + `${plan.omittedAnchors.length > 0 ? `; ${plan.omittedAnchors.length} anchor(s) omitted (unreviewed): ${plan.omittedAnchors.slice(0, 10).join(', ')}` : '; no anchors omitted'}.`
    : 'Excerpt plan: single head sample (legacy).';
  return [
    'First-round reviews (parallel, identical diff input):',
    JSON.stringify(roles),
    `Coverage: ${JSON.stringify(firstRound.coverage)}`,
    `Deterministic violations: ${JSON.stringify(firstRound.deterministicViolations)}`,
    `Diff excerpt (${diffExcerpt.length} of ${originalLength} chars${diffTruncated ? ', truncated' : ''}):`,
    planLine,
    'Only the content below was re-examined; anything not shown — including omitted anchors — is unreviewed. A BLOCK finding must cite evidence literally present below.',
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
      userPrompt: synthesisUserPrompt(options.firstRound, excerpt, options.diffTruncated, options.originalLength, options.excerptPlan),
      maxOutputTokens: SYNTHESIS_MAX_OUTPUT_TOKENS,
      allowedOrigins: options.allowedOrigins,
      env,
      ...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
      ...(options.deadlineMs !== undefined ? { deadlineMs: options.deadlineMs } : {}),
    });
    return parseSynthesisResponse(result.modelId, result.content) ?? { ok: false, modelUsed: redactSensitiveText(result.modelId), verdict: 'INCONCLUSIVE', findings: [], evidences: [], summary: '' };
  } catch {
    return { ok: false, modelUsed: redactSensitiveText(modelUsed), verdict: 'INCONCLUSIVE', findings: [], evidences: [], summary: '' };
  }
}

// P6 second-layer issue diagnosis: runs only when the first-round triage
// names readable default-branch files. The diagnosis sees the same issue
// text plus the fetched code; its verdict is combined stricter-wins with the
// first round by the caller. Any failure (missing config, transport, or
// schema) falls back to INCONCLUSIVE so the caller keeps the first-round
// result; a diagnosis never loosens on its own.
export interface IssueCodeSnippet {
  path: string;
  content: string;
  truncated: boolean;
  totalChars: number;
}

export interface IssueDiagnosisInput extends IssueTriageInput {
  firstRoundVerdict: ReviewVerdict;
  firstRoundSummary: string;
  codeRefs: IssueCodeSnippet[];
}

export interface IssueDiagnosis {
  ok: boolean;
  verdict: ReviewVerdict;
  summary: string;
  suggestedLabels: string[];
}

export interface DiagnoseIssueOptions {
  input: IssueDiagnosisInput;
  env?: OpenAIEnvironment;
  promptDirectory?: string;
  allowedOrigins?: string[];
  timeoutMs?: number;
  /**
   * F4 (review #77 P1-2): absolute wall-clock deadline (Date.now() ms)
   * forwarded to every fallback-chain call so the summed attempts stay
   * inside the job budget. Optional; omitted keeps legacy behavior.
   */
  deadlineMs?: number;
}

function diagnosisUserPrompt(input: IssueDiagnosisInput): string {
  const code = input.codeRefs.map((ref) =>
    [
      `File: ${ref.path} (${ref.content.length}/${ref.totalChars} chars${ref.truncated ? ', truncated' : ''})`,
      ref.content,
    ].join('\n'),
  );
  return [
    `Title: ${redactForModel(input.title)}`,
    `Body: ${redactForModel(input.body)}`,
    ...input.comments.map((comment) => `Comment: ${redactForModel(comment)}`),
    `First-round verdict: ${input.firstRoundVerdict}`,
    `First-round summary: ${redactForModel(input.firstRoundSummary)}`,
    'Code excerpts (read-only, may be truncated; unprovided content is unreviewed):',
    ...code,
  ].join('\n\n');
}

export async function diagnoseIssue(options: DiagnoseIssueOptions): Promise<IssueDiagnosis> {
  const fallback: IssueDiagnosis = { ok: false, verdict: 'INCONCLUSIVE', summary: '', suggestedLabels: [] };
  try {
    const env = options.env ?? process.env;
    const promptDirectory = options.promptDirectory ?? path.resolve(__dirname, '../prompts');
    const modelUsed = resolveRoleModel('architecture', env);
    const systemPrompt = fs.readFileSync(path.join(promptDirectory, 'issue_diagnose.md'), 'utf8');
    const result = await sendOpenAISingleTurnWithFallback({
      modelId: modelUsed,
      modelRole: 'architecture',
      systemPrompt,
      userPrompt: diagnosisUserPrompt(options.input),
      maxOutputTokens: 1024,
      allowedOrigins: options.allowedOrigins,
      env,
      ...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
      ...(options.deadlineMs !== undefined ? { deadlineMs: options.deadlineMs } : {}),
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
      ok: true,
      verdict: parsed.verdict as ReviewVerdict,
      summary: redactSensitiveText(parsed.summary).slice(0, MAX_TEXT_LENGTH),
      suggestedLabels,
    };
  } catch {
    return fallback;
  }
}
