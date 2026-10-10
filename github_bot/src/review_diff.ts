export const MAX_DIFF_LENGTH = 120000;

// Phase 4 (P2 #2): per-chunk model budget for PR patches, mirroring the issue
// chunk budget (MAX_ISSUE_CHUNK_LENGTH). Diffs whose full review length
// exceeds MAX_DIFF_LENGTH are partitioned into sequential chunks of at most
// this size so every character is reviewed with full coverage instead of
// truncated to INCONCLUSIVE.
export const MAX_PR_CHUNK_LENGTH = 8000;

// Local equivalent of the GitHub PR-files pagination concept (3000 files):
// beyond this the changed-path list is treated as incomplete (fail-closed).
export const MAX_CHANGED_FILES = 3000;

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

// Phase 4 (P2 #2): PR patch chunks. A chunk is a contiguous span over the
// concatenated per-file review diffs (review files in prioritizeFiles order,
// joined by '\n' — the same layout whose length is reported as
// coverage.originalLength). start/end are char offsets into that layout;
// coveredLength equals the span length (end - start), so summed spans prove
// full coverage without trusting offsets alone. files lists the distinct
// review files overlapping the span, in layout order. Small files are atomic
// (never split across chunks); oversized files are sliced at line boundaries
// via sliceDiffForChunk (see below). The full chunk carries the redacted
// model-bound diff text; only the minimal report (no diff text) is persisted.
export interface ReviewDiffChunk {
  index: number;
  total: number;
  start: number;
  end: number;
  complete: boolean;
  coveredLength: number;
  files: string[];
  diff: string;
}

// Per-chunk minimal retention for review-output (mirrors IssueChunkReport):
// redacted proof only — offsets, files, and the per-chunk orchestrated
// verdict — never chunk diff text.
export interface ReviewDiffChunkReport {
  index: number;
  total: number;
  start: number;
  end: number;
  complete: boolean;
  coveredLength: number;
  files: string[];
  verdict: 'APPROVE' | 'NEEDS_CHANGES' | 'INCONCLUSIVE';
}

// Slice one file's diff into pieces of at most maxLength chars without
// splitting lines. Breaks prefer hunk boundaries: when adding the next line
// would overflow, the cut moves back to the most recent '^@@ ' hunk header
// strictly inside the current piece (when that keeps the piece non-empty),
// so hunks stay whole unless a single hunk exceeds the budget. A single line
// longer than the budget becomes its own (oversized) piece so packing always
// makes progress. Deterministic: same input always yields the same pieces.
export function sliceDiffForChunk(text: string, maxLength: number): string[] {
  const src = typeof text === 'string' ? text : '';
  if (src.length === 0) return [];
  const max = Math.max(1, Math.floor(maxLength));
  const lines = src.split('\n');
  const lastLine = lines.length - 1;
  // Carried length: every non-final line keeps its newline so concatenated
  // pieces reproduce src exactly (no coverage gap at slice boundaries).
  const carried = (idx: number): number => lines[idx].length + (idx < lastLine ? 1 : 0);
  const pieceText = (from: number, to: number): string => {
    const text = lines.slice(from, to + 1).join('\n');
    return to < lastLine ? `${text}\n` : text;
  };
  const out: string[] = [];
  let from = 0;
  let used = 0;
  let lastHunk = -1;
  for (let i = 0; i < lines.length; i += 1) {
    if (used > 0 && used + carried(i) > max) {
      if (lastHunk > from) {
        out.push(pieceText(from, lastHunk - 1));
        used = 0;
        for (let k = lastHunk; k < i; k += 1) used += carried(k);
        from = lastHunk;
      } else {
        out.push(pieceText(from, i - 1));
        from = i;
        used = 0;
      }
      lastHunk = -1;
      for (let k = from; k < i; k += 1) {
        if (/^@@ /.test(lines[k])) lastHunk = k;
      }
    }
    used += carried(i);
    if (/^@@ /.test(lines[i])) lastHunk = i;
  }
  out.push(pieceText(from, lastLine));
  return out;
}

// Per-file diff collection shared by buildReviewDiff and the chunked path.
// Throws fail-closed when a review-eligible file yields no patch (same rule
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

// Partition per-file review diffs into sequential model-budget chunks.
// fileDiffs maps file path to its full patch (see collectFileDiffs);
// changedFiles selects and orders the review subset via filterReviewDiffFiles
// + prioritizeFiles. Small files pack greedily (whole, atomic); a file larger
// than the budget is sliced with sliceDiffForChunk and its slices pack
// across consecutive chunks. Chunk spans are contiguous over
// reviewDiffs.join('\n') (end includes the separator up to the next piece, as
// with issue chunks), so start[0] === 0, start[i] === end[i-1], and
// end[last] === totalLength where totalLength equals the single-turn
// originalLength over the same file set.
export function buildReviewDiffChunks(
  fileDiffs: Map<string, string> | Record<string, string>,
  changedFiles: string[],
  maxLength: number = MAX_PR_CHUNK_LENGTH,
): ReviewDiffChunk[] {
  const max = Math.max(1, Math.floor(maxLength));
  const lookup = (file: string): string => {
    if (fileDiffs instanceof Map) return fileDiffs.get(file) ?? '';
    const entry = (fileDiffs as Record<string, string>)[file];
    return typeof entry === 'string' ? entry : '';
  };
  const list = Array.isArray(changedFiles) ? changedFiles : [];
  const reviewSet = new Set(filterReviewDiffFiles(list));
  const reviewFiles = prioritizeFiles(list.filter((file) =>
    typeof file === 'string' && reviewSet.has(file) && lookup(file).length > 0));
  type Piece = { file: string; text: string; offset: number };
  const pieces: Piece[] = [];
  {
    let cursor = 0;
    reviewFiles.forEach((file, fileIdx) => {
      if (fileIdx > 0) cursor += 1; // file separator: files join by '\n'
      const full = lookup(file);
      const slices = full.length <= max ? [full] : sliceDiffForChunk(full, max);
      for (const slice of slices) {
        pieces.push({ file, text: slice, offset: cursor });
        cursor += slice.length;
      }
    });
  }
  if (pieces.length === 0) throw new Error('No review diffs to chunk.');
  const totalLength = pieces[pieces.length - 1].offset + pieces[pieces.length - 1].text.length;
  // Greedy pack: whole pieces only (a file within budget is a single piece,
  // hence atomic). Separators exist only between files (slices of one file
  // concatenate directly), so the joined layout length always equals the
  // single-turn originalLength over the same file set. A piece larger than
  // the budget (single long line) still occupies its own chunk alone.
  const groups: Piece[][] = [];
  let current: Piece[] = [];
  let used = 0;
  for (const piece of pieces) {
    const separator = current.length === 0 || current[current.length - 1].file === piece.file ? 0 : 1;
    if (current.length > 0 && used + separator + piece.text.length > max) {
      groups.push(current);
      current = [];
      used = 0;
    }
    const lead = current.length === 0 || current[current.length - 1].file === piece.file ? 0 : 1;
    current.push(piece);
    used += lead + piece.text.length;
  }
  if (current.length > 0) groups.push(current);
  const chunkText = (group: Piece[]): string => {
    let text = '';
    group.forEach((piece, idx) => {
      if (idx > 0 && group[idx - 1].file !== piece.file) text += '\n';
      text += piece.text;
    });
    return text;
  };
  return groups.map((group, index) => {
    const first = group[0];
    const last = group[group.length - 1];
    const isLast = index + 1 === groups.length;
    const start = index === 0 ? 0 : first.offset;
    // The span includes the file separator up to the next piece (honest
    // contiguous partition, as with issue chunks); mid-file splits carry no
    // separator so the next chunk chains exactly.
    const nextPiece = pieces[pieces.indexOf(last) + 1];
    const end = isLast
      ? totalLength
      : last.offset + last.text.length + (nextPiece && nextPiece.file !== last.file ? 1 : 0);
    const files: string[] = [];
    for (const piece of group) {
      if (!files.includes(piece.file)) files.push(piece.file);
    }
    return {
      index,
      total: groups.length,
      start,
      end,
      complete: true,
      coveredLength: end - start,
      files,
      diff: chunkText(group),
    };
  });
}

function validateChunkFiles(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const files: string[] = [];
  for (const entry of value) {
    if (typeof entry !== 'string' || !entry) return undefined;
    files.push(entry);
  }
  return files;
}

function validateChunkSkeleton(value: unknown, keys: readonly string[]): {
  index: number; total: number; start: number; end: number; complete: boolean; coveredLength: number; files: string[];
} | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (Object.keys(raw).some((key) => !(keys as readonly string[]).includes(key))) return undefined;
  if (
    !Number.isSafeInteger(raw.index) || Number(raw.index) < 0 ||
    !Number.isSafeInteger(raw.total) || Number(raw.total) < 1 ||
    !Number.isSafeInteger(raw.start) || Number(raw.start) < 0 ||
    !Number.isSafeInteger(raw.end) || Number(raw.end) <= Number(raw.start) ||
    typeof raw.complete !== 'boolean' ||
    !Number.isSafeInteger(raw.coveredLength) || Number(raw.coveredLength) < 0
  ) return undefined;
  if (Number(raw.index) >= Number(raw.total)) return undefined;
  if (Number(raw.coveredLength) !== Number(raw.end) - Number(raw.start)) return undefined;
  const files = validateChunkFiles(raw.files);
  if (!files) return undefined;
  return {
    index: Number(raw.index),
    total: Number(raw.total),
    start: Number(raw.start),
    end: Number(raw.end),
    complete: raw.complete === true,
    coveredLength: Number(raw.coveredLength),
    files,
  };
}

export function validateReviewDiffChunk(value: unknown): ReviewDiffChunk | undefined {
  const skeleton = validateChunkSkeleton(
    value, ['index', 'total', 'start', 'end', 'complete', 'coveredLength', 'files', 'diff'] as const);
  if (!skeleton) return undefined;
  const raw = value as Record<string, unknown>;
  if (typeof raw.diff !== 'string' || (raw.diff as string).length === 0) return undefined;
  if ((raw.diff as string).length > skeleton.coveredLength) return undefined;
  return { ...skeleton, diff: raw.diff as string };
}

export function validateReviewDiffChunkReport(value: unknown): ReviewDiffChunkReport | undefined {
  const skeleton = validateChunkSkeleton(
    value, ['index', 'total', 'start', 'end', 'complete', 'coveredLength', 'files', 'verdict'] as const);
  if (!skeleton) return undefined;
  const raw = value as Record<string, unknown>;
  if (!['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE'].includes(String(raw.verdict))) return undefined;
  return { ...skeleton, verdict: raw.verdict as ReviewDiffChunkReport['verdict'] };
}

function checkChunkContinuity<T extends { index: number; total: number; start: number; end: number; complete: boolean }>(
  sorted: T[],
): boolean {
  const total = sorted[0].total;
  if (sorted.length !== total) return false;
  for (let i = 0; i < sorted.length; i += 1) {
    if (sorted[i].index !== i || sorted[i].total !== total || sorted[i].complete !== true) return false;
    if (i > 0 && sorted[i].start !== sorted[i - 1].end) return false;
  }
  return sorted[0].start === 0;
}

export function validateReviewDiffChunks(
  value: unknown,
  full?: { fileDiffs: Map<string, string> | Record<string, string>; changedFiles: string[]; maxLength?: number },
): ReviewDiffChunk[] | undefined {
  if (!Array.isArray(value) || value.length < 1) return undefined;
  const chunks: ReviewDiffChunk[] = [];
  for (const entry of value) {
    const chunk = validateReviewDiffChunk(entry);
    if (!chunk) return undefined;
    chunks.push(chunk);
  }
  const sorted = [...chunks].sort((a, b) => a.index - b.index);
  if (!checkChunkContinuity(sorted)) return undefined;
  if (full) {
    const expected = buildReviewDiffChunks(full.fileDiffs, full.changedFiles, full.maxLength ?? MAX_PR_CHUNK_LENGTH);
    if (expected.length !== sorted.length) return undefined;
    for (let i = 0; i < sorted.length; i += 1) {
      const got = sorted[i];
      const want = expected[i];
      if (
        got.index !== want.index || got.total !== want.total ||
        got.start !== want.start || got.end !== want.end ||
        got.coveredLength !== want.coveredLength || got.diff !== want.diff ||
        got.files.length !== want.files.length || !got.files.every((f, j) => f === want.files[j])
      ) return undefined;
    }
  }
  return sorted;
}

export function validateReviewDiffChunkReports(
  value: unknown,
  full?: { fileDiffs: Map<string, string> | Record<string, string>; changedFiles: string[]; maxLength?: number },
): ReviewDiffChunkReport[] | undefined {
  if (!Array.isArray(value) || value.length < 1) return undefined;
  const reports: ReviewDiffChunkReport[] = [];
  for (const entry of value) {
    const report = validateReviewDiffChunkReport(entry);
    if (!report) return undefined;
    reports.push(report);
  }
  const sorted = [...reports].sort((a, b) => a.index - b.index);
  if (!checkChunkContinuity(sorted)) return undefined;
  if (full) {
    const expected = buildReviewDiffChunks(full.fileDiffs, full.changedFiles, full.maxLength ?? MAX_PR_CHUNK_LENGTH);
    if (expected.length !== sorted.length) return undefined;
    for (let i = 0; i < sorted.length; i += 1) {
      const got = sorted[i];
      const want = expected[i];
      if (
        got.index !== want.index || got.total !== want.total ||
        got.start !== want.start || got.end !== want.end ||
        got.coveredLength !== want.coveredLength ||
        got.files.length !== want.files.length || !got.files.every((f, j) => f === want.files[j])
      ) return undefined;
    }
  }
  return sorted;
}

// Source coverage proof for PR chunks (mirrors verifyIssueChunkCoverage):
// continuity plus span/total plus file-set equality. totalLength is the
// concatenated review-diff length (coverage.originalLength); files is the
// review-eligible file set (filterReviewDiffFiles output). Without opts only
// internal continuity is checked.
export function verifyReviewDiffChunkCoverage(
  chunks: Array<ReviewDiffChunk | ReviewDiffChunkReport>,
  opts?: { totalLength?: number; files?: string[] },
): boolean {
  if (!Array.isArray(chunks) || chunks.length < 1) return false;
  const sorted = [...chunks].sort((a, b) => a.index - b.index);
  if (!checkChunkContinuity(sorted)) return false;
  if (opts?.totalLength !== undefined) {
    if (!Number.isSafeInteger(opts.totalLength) || opts.totalLength < 0) return false;
    if (sorted[sorted.length - 1].end !== opts.totalLength) return false;
    const spanSum = sorted.reduce((sum, c) => sum + c.coveredLength, 0);
    if (spanSum !== opts.totalLength) return false;
  }
  if (opts?.files !== undefined) {
    const expected = [...new Set(opts.files.filter((f): f is string => typeof f === 'string'))].sort();
    const covered = [...new Set(sorted.flatMap((c) => c.files))].sort();
    if (covered.length !== expected.length || !covered.every((f, i) => f === expected[i])) return false;
  }
  return true;
}
