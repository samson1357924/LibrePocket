export const MAX_DIFF_LENGTH = 120000;

// MUST NOT exclude AndroidManifest.xml, permissions, *.gradle.kts, ProGuard files,
// .github/workflows, or tests.
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

  const visible = raw.slice(0, budget);
  const notice = `... [PocketGuard diff truncated: total ${raw.length} chars exceeded budget ${budget}. Showing first ${visible.length} chars.] ...`;
  return { diff: `${visible}\n${notice}`, truncated: true, originalLength: raw.length };
}

function normalizePath(path: string): string {
  return path.replace(/\\/g, '/').replace(/^\.\//, '');
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
