import * as fs from 'fs';
import { Octokit } from '@octokit/rest';

/**
 * GitHub Bot Label Management
 *
 * Implements deterministic label resolution, triage label extraction,
 * allowlist validation, and safe idempotent label application on Issues and PRs.
 */

export const COMMENT_MARKERS = Object.freeze({
  review: '<!-- PocketGuard REVIEW REPORT -->',
});
export const BOT_MENTION = '@pocketguard';

export const REPO_ALLOWED_LABELS = new Set<string>([
  'area:runtime',
  'area:platform',
  'area:policy',
  'area:delivery',
  'area:docs',
  'status:needs-decision',
  'security',
  'performance',
  'type:tracking',
  'priority:P1',
  'priority:P2',
  'gate:live',
  'gate:release',
  'gate:activation',
  'gate:docs',
  'status:verified-main',
  'status:partial',
  'status:latent',
  'bug',
  'enhancement',
  'documentation',
  'duplicate',
  'good first issue',
  'help wanted',
  'invalid',
  'question',
  'wontfix',
]);

export const LABEL_SYNONYMS: Readonly<Record<string, string>> = Object.freeze({
  policy: 'area:policy',
  manifest: 'area:delivery',
  workflow: 'area:delivery',
  perf: 'performance',
  sec: 'security',
  docs: 'area:docs',
  'needs-decision': 'status:needs-decision',
  decision: 'status:needs-decision',
});

/**
 * Normalizes a candidate label name to canonical repository label if valid.
 */
export function normalizeLabelName(candidate: string): string | undefined {
  if (!candidate || typeof candidate !== 'string') return undefined;
  const trimmed = candidate.trim().replace(/^[`"']+|[`"']+$/g, '').toLowerCase();
  if (!trimmed) return undefined;
  const synonym = Object.hasOwn(LABEL_SYNONYMS, trimmed) ? LABEL_SYNONYMS[trimmed] : undefined;
  if (synonym && REPO_ALLOWED_LABELS.has(synonym)) {
    return synonym;
  }
  const canonical = Array.from(REPO_ALLOWED_LABELS).find((label) => label.toLowerCase() === trimmed);
  if (canonical) {
    return canonical;
  }
  return undefined;
}

/**
 * Filter an arbitrary list of candidate label names through the allowlist and synonym dictionary.
 */
export function sanitizeLabels(candidates: string[]): string[] {
  const result = new Set<string>();
  for (const c of candidates) {
    const normalized = normalizeLabelName(c);
    if (normalized) {
      result.add(normalized);
    }
  }
  return Array.from(result).sort();
}

/**
 * Resolves areas from the complete `git diff --name-only` path list. Callers MUST
 * provide the full list: a partial list can leave stale area labels on the PR.
 */
const MAIN_PACKAGE_ROOT = 'app/src/main/java/dev/librepocket/';

const PACKAGE_AREA_PREFIXES: ReadonlyArray<readonly [string, string]> = [
  ['chat', 'area:runtime'],
  ['session', 'area:runtime'],
  ['provider', 'area:runtime'],
  ['entry', 'area:runtime'],
  ['router', 'area:runtime'],
  ['jev', 'area:runtime'],
  ['memory', 'area:runtime'],
  ['models', 'area:runtime'],
  ['preset', 'area:runtime'],
  ['automation', 'area:platform'],
  ['files', 'area:platform'],
  ['shell', 'area:platform'],
  ['linux', 'area:platform'],
  ['privilege', 'area:platform'],
  ['systema', 'area:platform'],
  ['systemb', 'area:platform'],
  ['mcp', 'area:platform'],
  ['tool', 'area:platform'],
  ['skill', 'area:platform'],
  ['vision', 'area:platform'],
  ['voice', 'area:platform'],
  ['clipboard', 'area:platform'],
  ['agent/foss', 'area:platform'],
  ['agent/github', 'area:platform'],
  ['slow', 'area:platform'],
  ['policy', 'area:policy'],
  ['guard', 'area:policy'],
  ['hardening', 'area:policy'],
  ['redact', 'area:policy'],
  ['keystore', 'area:policy'],
  ['backup', 'area:policy'],
];

function resolvePackageArea(packagePath: string): string | undefined {
  for (const [packagePrefix, label] of PACKAGE_AREA_PREFIXES) {
    if (packagePath.startsWith(`${packagePrefix}/`)) return label;
  }
  return undefined;
}

function resolveMainPathArea(normalizedPath: string): string | undefined {
  if (normalizedPath.startsWith(MAIN_PACKAGE_ROOT)) {
    const packagePath = normalizedPath.slice(MAIN_PACKAGE_ROOT.length);
    const packageArea = resolvePackageArea(packagePath);
    if (packageArea) return packageArea;
  }

  if (
    normalizedPath.startsWith('app/src/play/') ||
    normalizedPath.startsWith('app/src/foss/') ||
    normalizedPath.startsWith('app/src/github/') ||
    normalizedPath.startsWith('app/src/main/res/xml/') ||
    normalizedPath.startsWith('gradle/') ||
    normalizedPath.startsWith('scripts/') ||
    normalizedPath === 'app/src/main/AndroidManifest.xml' ||
    normalizedPath === 'app/build.gradle.kts' ||
    normalizedPath === 'settings.gradle.kts' ||
    normalizedPath === 'build.gradle.kts' ||
    normalizedPath === 'gradle.properties' ||
    normalizedPath === '.github/workflows/release.yml'
  ) {
    return 'area:delivery';
  }

  const basename = normalizedPath.slice(normalizedPath.lastIndexOf('/') + 1);
  if (
    normalizedPath.startsWith('docs/') ||
    normalizedPath === 'app/lint.xml' ||
    basename === 'SECURITY.md' ||
    basename.startsWith('LICENSE') ||
    basename.startsWith('NOTICE') ||
    basename === 'TRADEMARKS.md' ||
    normalizedPath.toLowerCase().endsWith('.md')
  ) {
    return 'area:docs';
  }

  return undefined;
}

export function resolveAreaLabelsFromPaths(changedFiles: string[], coverageComplete = true): string[] {
  const areas = new Set<string>();

  for (const rawPath of changedFiles) {
    if (!rawPath || typeof rawPath !== 'string') continue;
    const normalized = rawPath.replace(/\\/g, '/').replace(/^(\.\/|\/)/, '').trim();

    const mirrorMatch = normalized.match(/^app\/src\/(?:test|androidTest|debug)\/(.*)$/);
    if (mirrorMatch) {
      const mirroredPath = mirrorMatch[1].replace(/^java\//, '');
      const packageMatch = mirroredPath.match(/^dev\/librepocket\/(.+)$/);
      const mirroredArea = packageMatch ? resolvePackageArea(packageMatch[1]) : undefined;
      areas.add(mirroredArea || 'area:runtime');
      if (!mirroredArea) areas.add('status:needs-decision');
      continue;
    }

    const area = resolveMainPathArea(normalized);
    if (area) areas.add(area);
  }

  if (!coverageComplete) areas.add('status:needs-decision');
  return Array.from(areas).sort();
}

/**
 * Resolves only explicitly requested security, performance, and tracking labels.
 * Bug, enhancement, and documentation labels are owned by repository triage.
 */
export function resolveLabelsFromTitle(title: string): string[] {
  if (!title || typeof title !== 'string') return [];
  const labels = new Set<string>();
  const trimmed = title.trim();

  // Keep the original title parsing stages: conventional prefix, bracket tags,
  // then a conservative semantic fallback when no explicit supported tag exists.
  const conventionalMatch = trimmed.match(/^([a-zA-Z]+)(?:\([^\)]+\))?!?:/);
  if (conventionalMatch) {
    const prefix = conventionalMatch[1].toLowerCase();
    if (prefix === 'perf') labels.add('performance');
    if (prefix === 'sec' || prefix === 'security') labels.add('security');
    if (prefix === 'tracking') labels.add('type:tracking');
  }

  const bracketMatches = Array.from(trimmed.matchAll(/\[([^\]]+)\]/g));
  for (const match of bracketMatches) {
    const inner = match[1].toLowerCase();
    const tokens = inner.split(/[\/\s:_-]+/).filter(Boolean);
    if (tokens.some((token) => token === 'sec' || token === 'security')) labels.add('security');
    if (tokens.some((token) => token === 'perf' || token === 'performance')) labels.add('performance');
    if (tokens.some((token) => token === 'tracking')) labels.add('type:tracking');
  }

  if (!labels.has('security') && /\b(?:security|sec)\b/i.test(trimmed)) labels.add('security');
  if (!labels.has('performance') && /\b(?:performance|perf)\b/i.test(trimmed)) labels.add('performance');
  if (!labels.has('type:tracking') && /\btype:tracking\b/i.test(trimmed)) labels.add('type:tracking');

  return Array.from(labels).sort();
}

export type FinalVerdict = 'APPROVE' | 'NEEDS_CHANGES' | 'INCONCLUSIVE';
export type RoleVerdict = 'APPROVE' | 'NEEDS_CHANGES' | 'COMMENT';

export interface ParsedReviewReport {
  verdict: FinalVerdict;
  roleVerdicts: Record<string, RoleVerdict>;
  hasSecurityFinding: boolean;
  hasPerformanceFinding: boolean;
  deterministicViolations: Array<{ severity: 'BLOCK' | 'WARN'; ruleId: string }>;
  parseConfidence: 'exact_marker' | 'table_anchor' | 'matrix_aggregated' | 'fallback_closed';
}

/** 1. Final verdict trailer generated by Orchestrator (highest authority, sanitized against spoofing) */
const FINAL_VERDICT_TRAILER_REGEX = /(?:^|\n)\*\*`FINAL_VERDICT=(APPROVE|NEEDS_CHANGES|INCONCLUSIVE)`\*\*(?:\r?\n|$)/m;

/** 2. Metadata table verdict row */
const TABLE_VERDICT_REGEX = /\|\s*\*\*判定結果\*\*\s*\|\s*\*\*`?(APPROVE|NEEDS_CHANGES|INCONCLUSIVE)`?\*\*\s*\|/;

/** 3. Role verdict matrix table row and section */
const MATRIX_SECTION_REGEX = /## 🎯 角色裁決矩陣 \(Verdict Matrix\)([\s\S]*?)(?:\n---|\n## |$)/;
const ROLE_ROW_REGEX = /\|\s*\*\*([A-Za-z0-9_\-\/]+)\*\*\s*\|\s*`[^`]+`\s*\|\s*\*\*`?(APPROVE|NEEDS_CHANGES|COMMENT)`?\*\*\s*\|\s*([^|\r\n]*)\|/g;

/** 4. Deterministic scanner violation section */
const DETERMINISTIC_SECTION_REGEX = /## 🚨 前置確定性掃描檢驗違規 \(Deterministic Violations\)([\s\S]*?)(?:\n---|\n## |$)/;

/** 5. Deterministic item format */
const DETERMINISTIC_ITEM_REGEX = /-\s*\*\*\[(BLOCK|WARN)\]\s+([A-Z0-9_\-]+)\*\*/g;

/** 6. R-BE/SEC section */
const BE_SEC_SECTION_REGEX = /###\s+R-BE\/SEC\b[^\n]*\n([\s\S]*?)(?:\n###|\n---\s*\n\*\*`FINAL_VERDICT|$)/;

/** 7. Negation patterns to avoid false-positives like "Zero SQL injection", "未發現漏洞", "No Secret / API Key", "無記憶體洩漏", "無效能瓶頸" */
const NEGATION_PATTERNS = [
  /\b(?:no|zero|none|clean|free\s+of|not\s+(?:found|detected|observed))\b[^.,;\n]*(?:vulnerability|leak|injection|bypass|secret|key|issue|risk|flaw|bottleneck|N\+1(?:\s+queries|\s+query)?|memory\s+leak|overhead)/gi,
  /(?:未發現|無發現|查無|無任何|零|不存在|未檢出|未見|未出現|未有|無(?![法疑論需須關]))[^。，；、\n但]*(?:漏洞|洩漏|洩露|注入|密鑰|風險|問題|疑慮|弱點|瓶頸|暴增|開銷|負擔|延遲|N\+1|vulnerability|leak|injection|bypass|secret|key|flaw|bottleneck|memory\s*leak)/gi,
];

function containsActualViolation(sectionText: string, keywords: RegExp[]): boolean {
  if (!sectionText) return false;
  const lines = sectionText.split(/\r?\n/);
  for (const line of lines) {
    let trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;

    // Erase negation segments within the clause rather than dropping the whole line
    for (const neg of NEGATION_PATTERNS) {
      trimmed = trimmed.replace(new RegExp(neg.source, neg.flags), '');
    }

    // Check if any keyword matches the remaining content of the line
    for (const kw of keywords) {
      if (kw.test(trimmed)) {
        return true;
      }
    }
  }
  return false;
}

/**
 * Robust parser for Multi-Agent Review reports:
 * - Anchors on tamper-proof final verdict trailers or metadata tables.
 * - Extracts role verdicts accurately within the scoped Verdict Matrix section.
 * - Eliminates false-positive security/performance findings caused by negation phrases or unrelated role changes.
 * - Gracefully falls back when markers are missing.
 */
export function parseReviewReport(report: string): ParsedReviewReport {
  if (!report || typeof report !== 'string') {
    return {
      verdict: 'INCONCLUSIVE',
      roleVerdicts: {},
      hasSecurityFinding: false,
      hasPerformanceFinding: false,
      deterministicViolations: [],
      parseConfidence: 'fallback_closed',
    };
  }

  // A. Extract Role Verdict Matrix (scoped strictly within Verdict Matrix section, first match wins)
  const roleVerdicts: Record<string, RoleVerdict> = {};
  const matrixSectionMatch = report.match(MATRIX_SECTION_REGEX);
  if (matrixSectionMatch) {
    let roleMatch: RegExpExecArray | null;
    const roleRegex = new RegExp(ROLE_ROW_REGEX.source, ROLE_ROW_REGEX.flags);
    while ((roleMatch = roleRegex.exec(matrixSectionMatch[1])) !== null) {
      const roleId = roleMatch[1].trim();
      if (!Object.hasOwn(roleVerdicts, roleId)) {
        roleVerdicts[roleId] = roleMatch[2].trim() as RoleVerdict;
      }
    }
  }

  // B. Extract Deterministic Violations
  const deterministicViolations: Array<{ severity: 'BLOCK' | 'WARN'; ruleId: string }> = [];
  const detSectionMatch = report.match(DETERMINISTIC_SECTION_REGEX);
  if (detSectionMatch) {
    let itemMatch: RegExpExecArray | null;
    const itemRegex = new RegExp(DETERMINISTIC_ITEM_REGEX.source, DETERMINISTIC_ITEM_REGEX.flags);
    while ((itemMatch = itemRegex.exec(detSectionMatch[1])) !== null) {
      deterministicViolations.push({
        severity: itemMatch[1] as 'BLOCK' | 'WARN',
        ruleId: itemMatch[2],
      });
    }
  }

  // C. Resolve Verdict with Graceful Cascading
  let verdict: FinalVerdict = 'INCONCLUSIVE';
  let parseConfidence: ParsedReviewReport['parseConfidence'] = 'fallback_closed';

  const trailerMatch = report.match(FINAL_VERDICT_TRAILER_REGEX);
  if (trailerMatch) {
    verdict = trailerMatch[1] as FinalVerdict;
    parseConfidence = 'exact_marker';
  } else {
    const tableMatch = report.match(TABLE_VERDICT_REGEX);
    if (tableMatch) {
      verdict = tableMatch[1] as FinalVerdict;
      parseConfidence = 'table_anchor';
    } else if (Object.keys(roleVerdicts).length > 0) {
      const hasNeedsChanges = Object.values(roleVerdicts).includes('NEEDS_CHANGES');
      const allApprove =
        Object.values(roleVerdicts).length >= 4 && Object.values(roleVerdicts).every((v) => v === 'APPROVE');
      const hasBlocker = deterministicViolations.some((v) => v.severity === 'BLOCK');

      if (hasBlocker || hasNeedsChanges) {
        verdict = 'NEEDS_CHANGES';
      } else if (allApprove && deterministicViolations.length === 0) {
        verdict = 'APPROVE';
      } else {
        verdict = 'INCONCLUSIVE';
      }
      parseConfidence = 'matrix_aggregated';
    } else {
      verdict = 'INCONCLUSIVE';
      parseConfidence = 'fallback_closed';
    }
  }

  // D. Security Finding
  const hasDeterministicSecurity = deterministicViolations.some(
    (v) => (v.ruleId.startsWith('SEC-') || v.ruleId.startsWith('ARCH-FORBIDDEN-PKG')) && v.severity === 'BLOCK'
  );

  let hasBeSecBlocker = false;
  if (roleVerdicts['R-BE/SEC'] === 'NEEDS_CHANGES') {
    const beSecSectionMatch = report.match(BE_SEC_SECTION_REGEX);
    if (beSecSectionMatch) {
      const beSecText = beSecSectionMatch[1];
      const securityKeywords = [
        /SQL\s*Injection/i,
        /AUTH_BYPASS/i,
        /Secret\s*\/\s*API\s*Key/i,
        /Vulnerability/i,
        /\b(?:XSS|SSRF|CSRF|RCE|IDOR)\b/i,
        /明文金鑰/,
        /權限繞過/,
        /資安違規/,
        /(?:安全)?漏洞/,
        /越權/,
        /注入攻擊/,
        /憑證洩漏/,
        /密鑰洩漏/,
        /\[(?:BLOCK|WARN)\]\s*(?:security|資安)/i,
        /axis["':\s]+security/i,
      ];
      hasBeSecBlocker = containsActualViolation(beSecText, securityKeywords);
    } else {
      hasBeSecBlocker = true;
    }
  }

  const hasSecurityFinding = verdict === 'NEEDS_CHANGES' && (hasDeterministicSecurity || hasBeSecBlocker);

  // E. Performance Finding
  let hasPerformanceFinding = false;
  if (verdict === 'NEEDS_CHANGES') {
    const perfKeywords = [
      /\bN\+1\s+(?:queries|query)\b/i,
      /\bmemory\s+leak\b/i,
      /記憶體(?:洩漏|洩露|暴增)/,
      /(?:嚴重|顯著|存在|發現)?(?:效能|性能)瓶頸/,
      /\[(?:BLOCK|WARN)\]\s*(?:performance|reliability|效能|性能)/i,
      /axis["':\s]+(?:performance|reliability)/i,
    ];
    hasPerformanceFinding = containsActualViolation(report, perfKeywords);
  }

  return {
    verdict,
    roleVerdicts,
    hasSecurityFinding,
    hasPerformanceFinding,
    deterministicViolations,
    parseConfidence,
  };
}

/**
 * Resolves labels from review verdict and scanner findings.
 */
export function resolveReviewLabels(options: {
  verdict?: string;
  hasSecurityFinding?: boolean;
  hasPerformanceFinding?: boolean;
}): string[] {
  const labels = new Set<string>();

  if (options.hasSecurityFinding) {
    labels.add('security');
  }

  if (options.hasPerformanceFinding) {
    labels.add('performance');
  }

  // Only explicit 'APPROVE' clears human decision/review requirement.
  // Both 'NEEDS_CHANGES' (blocking findings) and 'INCONCLUSIVE' (fail-closed/non-unanimous review)
  // require human decision/review, hence labeling with 'status:needs-decision'.
  if (options.verdict && options.verdict !== 'APPROVE') {
    labels.add('status:needs-decision');
  }

  return Array.from(labels).sort();
}

export interface EnsureNeedsDecisionOptions {
  client: GitHubLabelClient;
  owner: string;
  repo: string;
  issueNumber: number;
}

/** Add the human-review label using only GitHub's add-labels endpoint (POST). */
export async function ensureNeedsDecision(options: EnsureNeedsDecisionOptions): Promise<boolean> {
  try {
    await options.client.rest.issues.addLabels({
      owner: options.owner,
      repo: options.repo,
      issue_number: options.issueNumber,
      labels: ['status:needs-decision'],
    });
    return true;
  } catch (err: any) {
    console.warn(`[PocketGuard Warning] Could not add status:needs-decision to #${options.issueNumber}: ${err?.message || err}`);
    return false;
  }
}

/**
 * Extracts proposed labels from unstructured or structured AI Triage Agent output.
 */
export function extractLabelsFromTriageText(text: string): string[] {
  if (!text || typeof text !== 'string') return [];
  const proposed = new Set<string>();

  // 1. Check for JSON block containing { "labels": [...] }
  const jsonBlocks = text.matchAll(/```(?:json)?\s*(\{[\s\S]*?\})\s*```/g);
  for (const match of jsonBlocks) {
    try {
      const parsed = JSON.parse(match[1]);
      if (Array.isArray(parsed.labels)) {
        for (const item of parsed.labels) {
          if (typeof item === 'string') proposed.add(item);
        }
      }
    } catch (parseErr: any) {
      if (process.env.NODE_ENV === 'development' || process.env.DEBUG) {
        console.debug(`[LabelManager Debug] Ignored non-JSON markdown code block: ${parseErr?.message || parseErr}`);
      }
    }
  }

  // 2. Direct inline JSON { "labels": [...] }
  const inlineJsonMatch = text.match(/\{[\s\r\n]*"labels"[\s\r\n]*:[\s\r\n]*\[([^\]]*)\][\s\r\n]*\}/);
  if (inlineJsonMatch) {
    const rawItems = inlineJsonMatch[1].split(',');
    for (const raw of rawItems) {
      const clean = raw.trim().replace(/^["']|["']$/g, '');
      if (clean) proposed.add(clean);
    }
  }

  // 3. Key-Value bullet points (e.g. `Labels: area:runtime, security`)
  const bulletMatch = text.match(/(?:labels?|標籤)\s*[:：]\s*\[?([^\]\r\n]+)\]?/i);
  if (bulletMatch) {
    const items = bulletMatch[1].split(/[,，、\s]+/);
    for (const item of items) {
      const clean = item.trim().replace(/^[`"']|[`"']$/g, '');
      if (clean) proposed.add(clean);
    }
  }

  return sanitizeLabels(Array.from(proposed));
}

export interface GitHubLabelClient {
  rest: {
    issues: {
      listLabelsOnIssue?: (params: {
        owner: string;
        repo: string;
        issue_number: number;
        per_page?: number;
      }) => Promise<{ data: Array<{ name: string }> }>;
      addLabels: (params: {
        owner: string;
        repo: string;
        issue_number: number;
        labels: string[];
      }) => Promise<unknown>;
      removeLabel?: (params: {
        owner: string;
        repo: string;
        issue_number: number;
        name: string;
      }) => Promise<unknown>;
    };
  };
  [key: string]: any;
}

export interface ReconcileScope {
  /** Managed namespace prefixes, e.g. ['area:'] */
  readonly managedPrefixes?: readonly string[];
  /** Managed exact label names, e.g. ['status:needs-decision'] */
  readonly managedExactLabels?: readonly string[];
}

export const DEFAULT_PR_RECONCILE_SCOPE: Readonly<ReconcileScope> = Object.freeze({
  managedPrefixes: Object.freeze(['area:']) as readonly string[],
  managedExactLabels: Object.freeze(['status:needs-decision']) as readonly string[],
});

export interface ReconcileLabelsOptions {
  client: GitHubLabelClient;
  owner: string;
  repo: string;
  issueNumber: number;
  desiredLabels: string[];
  scope?: ReconcileScope;
  coverageComplete?: boolean;
}

export interface ReconcileResult {
  added: string[];
  removed: string[];
  skipped: string[];
  failedRemovals?: string[];
}

/**
 * Checks whether a given label name belongs to the bot-managed scope.
 */
export function isManagedByBot(labelName: string, scope?: ReconcileScope): boolean {
  if (!scope || !labelName || typeof labelName !== 'string') return false;
  const lower = labelName.trim().toLowerCase();
  if (
    lower === 'security' ||
    lower === 'performance' ||
    lower === 'type:tracking' ||
    lower.startsWith('priority:') ||
    lower.startsWith('gate:') ||
    ['status:verified-main', 'status:partial', 'status:latent'].includes(lower)
  ) {
    return false;
  }
  if (scope.managedPrefixes?.some((prefix) => lower.startsWith(prefix.toLowerCase()))) {
    return true;
  }
  if (scope.managedExactLabels?.some((exact) => lower === exact.toLowerCase())) {
    return true;
  }
  return false;
}

/**
 * Reconciles bot-managed labels on an Issue or PR:
 * - Computes delta between existing labels and desiredLabels within scope.
 * - Adds missing desired labels.
 * - Surgically removes obsolete bot-managed labels (e.g. stale status:needs-decision or obsolete area:* labels).
 * - NEVER deletes unmanaged/human labels (e.g. good first issue, help wanted, custom tags).
 * - Fails safely on any API failure without throwing exceptions.
 */
export async function reconcileBotLabelsSafely(
  options: ReconcileLabelsOptions
): Promise<ReconcileResult> {
  const { client, owner, repo, issueNumber, desiredLabels, scope } = options;
  const coverageComplete = options.coverageComplete !== false;
  const requestedLabels = coverageComplete ? desiredLabels : [...desiredLabels, 'status:needs-decision'];
  const validDesired = sanitizeLabels(requestedLabels);

  try {
    let existingLabels: string[] = [];

    if (typeof client.rest.issues.listLabelsOnIssue === 'function') {
      try {
        const response = await client.rest.issues.listLabelsOnIssue({
          owner,
          repo,
          issue_number: issueNumber,
          per_page: 100,
        });
        existingLabels = response.data.map((l) => l.name);
      } catch (listErr: any) {
        console.warn(
          `[LabelManager Warning] Could not list existing labels for #${issueNumber}: ${listErr.message}`
        );
        // Fail-safe: if we cannot list labels, DO NOT attempt to remove anything to avoid accidental data loss.
        if (validDesired.length > 0) {
          try {
            await client.rest.issues.addLabels({
              owner,
              repo,
              issue_number: issueNumber,
              labels: validDesired,
            });
            return { added: validDesired, removed: [], skipped: [] };
          } catch (addErr: any) {
            console.warn(`[LabelManager Warning] Fallback addLabels failed for #${issueNumber}: ${addErr.message}`);
          }
        }
        return { added: [], removed: [], skipped: [] };
      }
    }

    const existingLowerMap = new Map<string, string>();
    for (const name of existingLabels) {
      existingLowerMap.set(name.toLowerCase(), name);
    }

    const validDesiredLower = new Set(validDesired.map((l) => l.toLowerCase()));

    const toAdd = validDesired.filter((l) => !existingLowerMap.has(l.toLowerCase()));
    const skipped = validDesired.filter((l) => existingLowerMap.has(l.toLowerCase()));

    // Compute removals strictly within the managed scope
    const toRemove: string[] = [];
    if (scope) {
      for (const [lowerName, originalName] of existingLowerMap.entries()) {
        const isIncompleteCoverageArea = !coverageComplete && lowerName.startsWith('area:');
        if (isManagedByBot(originalName, scope) && !validDesiredLower.has(lowerName) && !isIncompleteCoverageArea) {
          toRemove.push(originalName);
        }
      }
    }

    // 1. Remove stale managed labels
    const removed: string[] = [];
    const failedRemovals: string[] = [];

    if (toRemove.length > 0 && typeof client.rest.issues.removeLabel === 'function') {
      for (const labelName of toRemove) {
        try {
          await client.rest.issues.removeLabel({
            owner,
            repo,
            issue_number: issueNumber,
            name: labelName,
          });
          removed.push(labelName);
          console.log(`[LabelManager] Removed stale label '${labelName}' from ${owner}/${repo} #${issueNumber}.`);
        } catch (removeErr: any) {
          // Status 404 or message "Not Found" is idempotent success (already removed)
          if (removeErr?.status === 404 || /not\s*found/i.test(removeErr?.message || '')) {
            removed.push(labelName);
          } else {
            console.warn(
              `[LabelManager Warning] Failed to remove label '${labelName}' from #${issueNumber}: ${removeErr?.message || removeErr}`
            );
            failedRemovals.push(labelName);
          }
        }
      }
    }

    // 2. Add newly desired labels
    const added: string[] = [];
    if (toAdd.length > 0) {
      try {
        await client.rest.issues.addLabels({
          owner,
          repo,
          issue_number: issueNumber,
          labels: toAdd,
        });
        added.push(...toAdd);
        console.log(
          `[LabelManager] Successfully added labels to ${owner}/${repo} #${issueNumber}: [${toAdd.join(', ')}]`
        );
      } catch (addErr: any) {
        console.warn(`[LabelManager Warning] Failed to add labels to #${issueNumber}: ${addErr.message}`);
      }
    }

    return { added, removed, skipped, failedRemovals };
  } catch (err: any) {
    console.warn(`[LabelManager Warning] Reconciliation error on #${issueNumber}: ${err.message}`);
    return { added: [], removed: [], skipped: [] };
  }
}

export interface ApplyLabelsOptions {
  client: GitHubLabelClient;
  owner: string;
  repo: string;
  issueNumber: number;
  labels: string[];
  scope?: ReconcileScope;
  coverageComplete?: boolean;
}

/**
 * Safely and idempotently applies labels to an Issue or PR.
 * - Filters against allowlist.
 * - If scope is provided, performs full reconciliation (adding missing, removing stale in scope).
 * - If scope is undefined, maintains backward-compatible append-only behavior.
 * - Never throws on API errors (fails safely with warning log).
 */
export async function applyLabelsSafely(
  options: ApplyLabelsOptions
): Promise<{ added: string[]; skipped: string[]; removed?: string[] }> {
  if (options.scope) {
    const res = await reconcileBotLabelsSafely({
      client: options.client,
      owner: options.owner,
      repo: options.repo,
      issueNumber: options.issueNumber,
      desiredLabels: options.labels,
      scope: options.scope,
      coverageComplete: options.coverageComplete,
    });
    return { added: res.added, skipped: res.skipped, removed: res.removed };
  }

  const { client, owner, repo, issueNumber, labels } = options;
  const requestedLabels = options.coverageComplete === false ? [...labels, 'status:needs-decision'] : labels;
  const validLabels = sanitizeLabels(requestedLabels);

  if (validLabels.length === 0) {
    return { added: [], skipped: [], removed: [] };
  }

  try {
    let existingLabels = new Set<string>();

    if (typeof client.rest.issues.listLabelsOnIssue === 'function') {
      try {
        const response = await client.rest.issues.listLabelsOnIssue({
          owner,
          repo,
          issue_number: issueNumber,
          per_page: 100,
        });
        existingLabels = new Set(response.data.map((l) => l.name.toLowerCase()));
      } catch (listErr: any) {
        console.warn(`[LabelManager Warning] Could not list existing labels for #${issueNumber}: ${listErr.message}`);
      }
    }

    const toAdd = validLabels.filter((l) => !existingLabels.has(l.toLowerCase()));
    const skipped = validLabels.filter((l) => existingLabels.has(l.toLowerCase()));

    if (toAdd.length === 0) {
      console.log(`[LabelManager] All ${validLabels.length} labels already present on #${issueNumber}; skipping.`);
      return { added: [], skipped, removed: [] };
    }

    await client.rest.issues.addLabels({
      owner,
      repo,
      issue_number: issueNumber,
      labels: toAdd,
    });

    console.log(`[LabelManager] Successfully added labels to ${owner}/${repo} #${issueNumber}: [${toAdd.join(', ')}]`);
    return { added: toAdd, skipped, removed: [] };
  } catch (err: any) {
    console.warn(`[LabelManager Warning] Failed to apply labels to #${issueNumber}: ${err.message}`);
    return { added: [], skipped: [], removed: [] };
  }
}

export interface ApplyBotLabelsOptions {
  scope?: ReconcileScope;
  customClient?: GitHubLabelClient;
  coverageComplete?: boolean;
}

/**
 * High-level helper to apply labels safely using environment variables and GitHub context.
 * In local/offline runs, it logs candidates and returns safely without network calls.
 */
export async function applyBotLabels(
  labels: string[],
  optionsOrClient?: GitHubLabelClient | ApplyBotLabelsOptions
): Promise<{ added: string[]; removed: string[]; skipped: string[] } | void> {
  const token = process.env.GITHUB_TOKEN;
  const eventPath = process.env.GITHUB_EVENT_PATH;
  const repository = process.env.GITHUB_REPOSITORY;

  let customClient: GitHubLabelClient | undefined;
  let scope: ReconcileScope | undefined;
  let coverageComplete: boolean | undefined;

  if (optionsOrClient) {
    if ('rest' in optionsOrClient) {
      customClient = optionsOrClient as GitHubLabelClient;
    } else {
      const opts = optionsOrClient as ApplyBotLabelsOptions;
      customClient = opts.customClient;
      scope = opts.scope;
      coverageComplete = opts.coverageComplete;
    }
  }

  if (!customClient && (!token || !eventPath || !fs.existsSync(eventPath) || !repository)) {
    console.log(`[PocketGuard] Skipping label application (local/offline execution). Candidate labels: [${labels.join(', ')}]`);
    return;
  }

  try {
    const eventData = eventPath && fs.existsSync(eventPath) ? JSON.parse(fs.readFileSync(eventPath, 'utf-8')) : {};
    const issueNumber = eventData.pull_request?.number || eventData.issue?.number;
    const [owner, repo] = (repository || '').split('/');

    if (!issueNumber || !owner || !repo) {
      console.warn('[PocketGuard Warning] Missing issueNumber or repository for label application.');
      return;
    }

    const client: GitHubLabelClient = customClient || new Octokit({ auth: token });
    if (scope) {
      return await reconcileBotLabelsSafely({
        client,
        owner,
        repo,
        issueNumber,
        desiredLabels: labels,
        scope,
        coverageComplete,
      });
    }

    const res = await applyLabelsSafely({
      client,
      owner,
      repo,
      issueNumber,
      labels,
      coverageComplete,
    });
    return { added: res.added, removed: res.removed || [], skipped: res.skipped };
  } catch (err: any) {
    console.warn(`[PocketGuard Warning] Label application error: ${err.message}`);
  }
}
