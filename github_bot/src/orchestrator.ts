import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  sendCpaSingleTurn,
  resolveRoleModel,
  type CpaEnvironment,
  type CpaModelRole,
} from './send_cpa';
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
  role: CpaModelRole;
  modelUsed: string;
  verdict: ReviewVerdict;
  findings: ReviewFinding[];
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
  env?: CpaEnvironment;
  promptDirectory?: string;
  allowedOrigins?: string[];
}

const ROLES: readonly CpaModelRole[] = ['chief', 'android_sec', 'android_code'];
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
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, ' ')
    .slice(0, MAX_TEXT_LENGTH);
}

function parseJsonObject(text: string): Record<string, unknown> | undefined {
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : undefined;
  } catch {
    return undefined;
  }
}

function parseFindings(value: unknown): ReviewFinding[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const result: ReviewFinding[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
    const finding = raw as Record<string, unknown>;
    if (Object.keys(finding).some((key) => !['severity', 'file', 'line', 'issue', 'suggestion'].includes(key))) {
      return undefined;
    }
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

function parseRoleResponse(role: CpaModelRole, modelUsed: string, content: string): RoleReview {
  const sanitizedModel = redactSensitiveText(modelUsed);
  const fallback: RoleReview = { role, modelUsed: sanitizedModel, verdict: 'INCONCLUSIVE', findings: [] };
  const parsed = parseJsonObject(content);
  if (!parsed || !VERDICTS.has(parsed.verdict as ReviewVerdict)) return fallback;
  const findings = parseFindings(parsed.findings);
  if (!findings) return fallback;
  return { role, modelUsed: sanitizedModel, verdict: parsed.verdict as ReviewVerdict, findings };
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

function roleUserPrompt(role: CpaModelRole, changedFiles: string[], diff: string, coverage: ReviewCoverage): string {
  const files = changedFiles.map((file) => redactSensitiveText(file));
  const tierZeroToThreeOmissions = coverage.omittedFiles.filter((file) => reviewTier(file) <= 3);
  return [
    `審查角色：${role}`,
    `變更檔案：${JSON.stringify(files)}`,
    `覆蓋資訊：${JSON.stringify({ ...coverage, tierZeroToThreeOmissions })}`,
    '以下內容可能不完整；不得將未提供內容視為已審查。請只回報具體且可由差異證實的發現。',
    '變更差異：',
    diff,
  ].join('\n\n');
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
      const result = await sendCpaSingleTurn({
        modelId: modelUsed,
        systemPrompt,
        userPrompt: roleUserPrompt(role, options.changedFiles, options.diff, coverage),
        allowedOrigins: options.allowedOrigins,
        env,
      });
      return parseRoleResponse(role, result.modelId, result.content);
    } catch {
      return { role, modelUsed: redactSensitiveText(modelUsed), verdict: 'INCONCLUSIVE', findings: [] };
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
