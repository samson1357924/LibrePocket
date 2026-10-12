export const MAX_DIFF_LENGTH = 1000000;

// Local equivalent of the GitHub PR-files pagination concept (3000 files):
// beyond this the changed-path list is treated as incomplete (fail-closed).
export const MAX_CHANGED_FILES = 3000;

// H1: secret/key material is hard-denied BEFORE the must-include rules
// below, so `tests/debug.keystore` or `app/src/test/google-services.json`
// can never ride a test/permission/proguard exception back into the
// model-visible diff. MUST NOT exclude AndroidManifest.xml, permissions,
// *.gradle.kts, ProGuard files, .github/workflows, or tests — except through
// this hard-deny list.
export const HARD_DENY_REVIEW_PATTERNS = [
  '**/.env*',
  '**/local.properties*',
  '**/google-services.json*',
  '**/googleservice-info.plist*',
  '**/keystore.properties*',
  '**/credentials.json*',
  '**/secrets.json*',
  '**/service-account*',
  '**/*.keystore*',
  '**/*.jks*',
  '**/*.key*',
  '**/*.pem*',
  '**/*.p12*',
  '**/*.pfx*',
  '**/*.asc*',
  '**/*.ppk*',
  '**/id_rsa*',
  '**/id_ed25519*',
] as const;
export const DEFAULT_GIT_DIFF_EXCLUDES = [
  '*.lock',
  '**/gradle.lockfile',
  '**/build/**',
  '**/.gradle/**',
  '**/*.apk',
  '**/*.aab',
  '**/*.map',
  '**/*.min.*',
  '**/*.png',
  '**/*.jpg',
  '**/*.webp',
  '**/*.mp4',
  '**/screenshots*/**',
  '.idea/**',
  '**/local.properties',
  '**/*.keystore',
  '**/*.jks',
  '**/google-services.json',
] as const;

export interface TruncatedDiff {
  diff: string;
  truncated: boolean;
  originalLength: number;
}

export function truncateDiff(raw: string, maxLen: number): TruncatedDiff {
  const budget = Math.max(0, Math.floor(Number.isFinite(maxLen) ? maxLen : 0));
  if (raw.length <= budget) return { diff: raw, truncated: false, originalLength: raw.length };

  const fullNotice = `\n... [PocketGuard diff truncated: total ${raw.length} chars exceeded budget ${budget}.] ...`;
  const compactNotice = '\n...[truncated]...';
  const notice = fullNotice.length <= budget
    ? fullNotice
    : compactNotice.length <= budget
      ? compactNotice
      : compactNotice.slice(0, budget);
  const visibleBudget = Math.max(0, budget - notice.length);
  const diff = `${raw.slice(0, visibleBudget)}${notice}`;
  if (diff.length > budget) throw new Error('truncated diff exceeded budget');
  return { diff, truncated: true, originalLength: raw.length };
}

function normalizePath(path: string): string {
  return path.replace(/\\/g, '/').replace(/^\.\//, '');
}

function globToRegExp(glob: string): RegExp {
  let expression = glob.includes('/') ? '^' : '(?:^|.*/)';
  for (let index = 0; index < glob.length; index += 1) {
    const character = glob[index];
    if (character === '*') {
      if (glob[index + 1] === '*') {
        index += 1;
        if (glob[index + 1] === '/') {
          index += 1;
          expression += '(?:.*/)?';
        } else {
          expression += '.*';
        }
      } else {
        expression += '[^/]*';
      }
    } else if (character === '?') {
      expression += '[^/]';
    } else {
      expression += character.replace(/[|\\{}()[\]^$+?.]/g, '\\$&');
    }
  }
  return new RegExp(`${expression}$`, 'i');
}

const EXCLUDE_PATTERNS = DEFAULT_GIT_DIFF_EXCLUDES.map(globToRegExp);
const HARD_DENY_REVIEW_COMPILED = HARD_DENY_REVIEW_PATTERNS.map(globToRegExp);

/** Non-overridable: secret/key files are never model-visible, must-include or not. */
export function isHardDeniedReviewFile(rawPath: string): boolean {
  if (!rawPath || typeof rawPath !== 'string') return false;
  return HARD_DENY_REVIEW_COMPILED.some((pattern) => pattern.test(normalizePath(rawPath)));
}

function isMustIncludeReviewFile(file: string): boolean {
  const normalized = normalizePath(file).toLowerCase();
  const basename = normalized.slice(normalized.lastIndexOf('/') + 1);
  return basename === 'androidmanifest.xml' ||
    normalized.includes('permission') ||
    normalized.endsWith('.gradle.kts') ||
    normalized.includes('proguard') ||
    normalized.startsWith('.github/workflows/') ||
    /(^|\/)(?:test|tests|androidtest)(\/|$)/.test(normalized) ||
    /(?:^|\/)[^/]*(?:test|tests|spec)\.(?:kt|java)$/.test(normalized);
}

/** Filter only model-visible diff files; callers must retain all paths for scanning and area mapping. */
export function filterReviewDiffFiles(changedFiles: string[]): string[] {
  return changedFiles.filter((rawPath) => {
    if (!rawPath || typeof rawPath !== 'string') return false;
    const file = normalizePath(rawPath);
    if (isHardDeniedReviewFile(file)) return false;
    if (isMustIncludeReviewFile(file)) return true;
    return !EXCLUDE_PATTERNS.some((pattern) => pattern.test(file));
  });
}

function reviewTier(path: string): number {
  const normalized = normalizePath(path);
  const lower = normalized.toLowerCase();
  if (lower.endsWith('.md') || lower.startsWith('docs/') || lower.includes('/docs/')) return 6;
  if (lower.endsWith('androidmanifest.xml') || /permission/.test(lower)) return 0;
  if (/(auth|crypto|key|token|credential|biometric)/.test(lower)) return 1;
  if (
    lower.endsWith('.gradle.kts') ||
    lower.endsWith('settings.gradle.kts') ||
    lower.endsWith('libs.versions.toml') ||
    lower.includes('proguard') ||
    lower.includes('network-security')
  ) return 2;
  if (lower.startsWith('.github/workflows/') || lower.includes('/.github/workflows/')) return 3;

  const isTest =
    /(^|\/)(test|tests|androidtest)(\/|$)/.test(lower) ||
    /(?:test|tests|spec)\.(?:kt|java)$/.test(lower);
  if (isTest) return 5;
  if (lower.endsWith('.kt') || lower.endsWith('.java')) return 4;
  return 5;
}

export function prioritizeFiles(files: string[]): string[] {
  return files
    .map((file, index) => ({ file, index, tier: reviewTier(file) }))
    .sort((left, right) => left.tier - right.tier || left.index - right.index)
    .map(({ file }) => file);
}

export interface ReviewCoverage {
  complete: boolean;
  omittedFiles: string[];
  truncatedFiles: string[];
  originalLength: number;
}

export function coverageSummary(coverage: ReviewCoverage): string {
  const omittedCount = Array.isArray(coverage.omittedFiles) ? coverage.omittedFiles.length : 0;
  const truncatedCount = Array.isArray(coverage.truncatedFiles) ? coverage.truncatedFiles.length : 0;
  const originalLength = Number.isFinite(coverage.originalLength) ? Math.max(0, Math.floor(coverage.originalLength)) : 0;
  return `Review coverage: ${coverage.complete ? 'complete' : 'incomplete'}; ${omittedCount} omitted file(s); ${truncatedCount} truncated file(s); ${originalLength} original diff chars.`;
}

// Per-file diff collection shared by buildReviewDiff. Throws fail-closed when a review-eligible file yields no patch (same rule
// as buildReviewDiff); callers must pass the resolveMergeBase() result, never
// the raw PR base SHA.
export function collectFileDiffs(
  runGit: (args: string[]) => string,
  compareBaseSha: string,
  headSha: string,
  changedFiles: string[],
): Map<string, string> {
  const diffs = new Map<string, string>();
  const reviewFiles = new Set(filterReviewDiffFiles(changedFiles));
  for (const file of changedFiles) {
    // PR-controlled filenames may contain Git pathspec magic even after `--`;
    // force literal interpretation so a name cannot exclude itself from review.
    const fileDiff = runGit([
      '--literal-pathspecs',
      'diff',
      '--no-ext-diff',
      '--no-color',
      '--unified=3',
      compareBaseSha,
      headSha,
      '--',
      file,
    ]);
    if (reviewFiles.has(file) && !fileDiff) {
      throw new Error('Git returned no diff for a changed review file.');
    }
    diffs.set(file, fileDiff);
  }
  return diffs;
}
