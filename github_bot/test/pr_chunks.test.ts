import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  buildReviewDiffChunks,
  collectFileDiffs,
  filterReviewDiffFiles,
  MAX_PR_CHUNK_LENGTH,
  sliceDiffForChunk,
  validateReviewDiffChunk,
  validateReviewDiffChunkReport,
  validateReviewDiffChunkReports,
  validateReviewDiffChunks,
  verifyReviewDiffChunkCoverage,
} from '../src/review_diff';
import { synthesizeChunkedReview, type OrchestratedReview } from '../src/orchestrator';
import {
  parseReviewCountMarker,
  reviewComment,
  runClaimMode,
  runPublishMode,
  runReviewMode,
  type RunnerContext,
} from '../src/github_runner';

const TEST_BASE_URL = ['https:', '', 'pocketguard-openai.test', 'v1'].join('/');
const TEST_ORIGIN = new URL(TEST_BASE_URL).origin;
const BASE_SHA = 'a'.repeat(40);
const HEAD_SHA = 'b'.repeat(40);
const REPO = 'sample/repository';

function openAiEnv(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  return {
    GITHUB_REPOSITORY: REPO,
    GITHUB_TOKEN: 'fake-token',
    POCKETGUARD_SAFE_REVIEW: 'true',
    OPENAI_BASE_URL: TEST_BASE_URL,
    OPENAI_API_KEY: 'fake-openai-key',
    POCKETGUARD_OPENAI_ORIGIN: TEST_ORIGIN,
    POCKETGUARD_MODEL_CHIEF: 'fake-chief-model',
    POCKETGUARD_MODEL_ANDROID_SEC: 'fake-sec-model',
    POCKETGUARD_MODEL_ANDROID_CODE: 'fake-code-model',
    POCKETGUARD_MODEL_PROFILES: '{"fake-chief-model":"chat","fake-sec-model":"chat","fake-code-model":"chat"}',
    ...extra,
  } as NodeJS.ProcessEnv;
}

function installQueuedOpenAI(counter: { count: number }, payloads: unknown[]): () => void {
  const previousFetch = globalThis.fetch;
  let idx = 0;
  globalThis.fetch = (async () => {
    counter.count += 1;
    const payload = idx < payloads.length ? payloads[idx] : payloads[payloads.length - 1];
    idx += 1;
    return new Response(JSON.stringify({
      output: [{ content: [{ type: 'output_text', text: JSON.stringify(payload) }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  return () => { globalThis.fetch = previousFetch; };
}

function prEvent(): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: REPO },
    pull_request: {
      number: 41,
      title: 'chunked review',
      base: { sha: BASE_SHA, ref: 'main' },
      head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: REPO } },
    },
  };
}

function fileDiff(name: string, bodyLines: string[]): string {
  return [
    `diff --git a/${name} b/${name}`,
    `--- a/${name}`,
    `+++ b/${name}`,
    '@@ -1,1 +1,1 @@',
    ...bodyLines,
  ].join('\n');
}

function bigFileDiff(name: string, lines: number): string {
  const body = Array.from({ length: lines }, (_, i) => `+line ${i} ${'y'.repeat(40)}`);
  return fileDiff(name, body);
}

function prGitStub(files: Record<string, string>): (args: string[]) => string {
  return (args: string[]) => {
    if (args[0] === 'fetch') return '';
    if (args[0] === 'merge-base') return BASE_SHA;
    if (args[0] === 'diff' && args[1] === '--name-only') {
      return `${Object.keys(files).join('\0')}\0`;
    }
    return files[args[args.length - 1]] ?? '';
  };
}

type StickyComment = { id: number; body: string; user: { login: string; type: string } };

function prClient(state: { comments: StickyComment[]; created: number; updated: number }): NonNullable<RunnerContext['githubClient']> {
  return {
    rest: {
      pulls: {
        get: async () => ({
          data: {
            number: 41,
            base: { sha: BASE_SHA },
            head: { sha: HEAD_SHA, repo: { full_name: REPO } },
          },
        }),
      },
      users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
      issues: {
        listComments: async () => ({ data: state.comments }),
        createComment: async (params: { body: string }) => {
          state.created += 1;
          const id = 100 + state.created;
          state.comments.push({ id, body: params.body, user: { login: 'pocketguard[bot]', type: 'Bot' } });
          return {};
        },
        updateComment: async (params: { comment_id: number; body: string }) => {
          state.updated += 1;
          const found = state.comments.find((c) => c.id === params.comment_id);
          if (found) found.body = params.body;
          return {};
        },
        addLabels: async () => ({}),
        listLabelsOnIssue: async () => ({ data: [] }),
        removeLabel: async () => ({}),
      },
    },
  } as unknown as NonNullable<RunnerContext['githubClient']>;
}

function chunkedFiles(): Record<string, string> {
  return {
    'app/src/main/AndroidManifest.xml': fileDiff('app/src/main/AndroidManifest.xml', ['+<manifest />']),
    'app/src/main/java/demo/Safe.kt': fileDiff('app/src/main/java/demo/Safe.kt', ['+class Safe']),
    'app/src/main/java/demo/Big.kt': bigFileDiff('app/src/main/java/demo/Big.kt', 2500),
  };
}

function fakeOrchestrated(verdict: OrchestratedReview['verdict'], roles?: OrchestratedReview['roles']): OrchestratedReview {
  const mkRole = (role: 'chief' | 'android_sec' | 'android_code'): OrchestratedReview['roles'][number] => ({
    role,
    modelUsed: 'm',
    verdict: verdict === 'APPROVE' ? 'APPROVE' : verdict,
    findings: [],
    suggestedLabels: [],
  });
  return {
    verdict,
    roles: roles ?? [mkRole('chief'), mkRole('android_sec'), mkRole('android_code')],
    coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 10 },
    deterministicViolations: [],
  };
}

export async function runPrChunksTests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    assert.equal(MAX_PR_CHUNK_LENGTH, 8000, 'PR chunk budget mirrors the issue chunk budget');

    // Unit: multi-file packing keeps small files atomic in priority order.
    {
      const diffs = new Map<string, string>([
        ['docs/guide.md', fileDiff('docs/guide.md', ['+guide'])],
        ['app/src/main/AndroidManifest.xml', fileDiff('app/src/main/AndroidManifest.xml', ['+<manifest />'])],
        ['app/src/main/java/demo/Safe.kt', fileDiff('app/src/main/java/demo/Safe.kt', ['+class Safe'])],
      ]);
      const chunks = buildReviewDiffChunks(diffs, [...diffs.keys()]);
      assert.ok(chunks.length >= 1, 'small files pack');
      assert.equal(chunks[0].files[0], 'app/src/main/AndroidManifest.xml', 'prioritizeFiles order (manifest tier 0 first)');
      for (const chunk of chunks) {
        assert.ok(validateReviewDiffChunk(chunk), 'each chunk validates');
        assert.equal(chunk.complete, true);
        assert.equal(chunk.coveredLength, chunk.end - chunk.start);
      }
      assert.ok(validateReviewDiffChunks(chunks, { fileDiffs: diffs, changedFiles: [...diffs.keys()] }), 'rebuild validates with full');
      assert.ok(verifyReviewDiffChunkCoverage(chunks, {
        totalLength: chunks[chunks.length - 1].end,
        files: filterReviewDiffFiles([...diffs.keys()]),
      }), 'coverage helper confirms packing');
      // Per-file atomicity: each small file appears whole in exactly one chunk.
      for (const file of diffs.keys()) {
        const holders = chunks.filter((c) => c.files.includes(file));
        assert.equal(holders.length, 1, `${file} held by exactly one chunk`);
        assert.ok(holders[0].diff.includes(diffs.get(file) as string), `${file} kept whole`);
      }
    }

    // Unit: giant patch slices at line boundaries with hunks whole.
    {
      const body = [
        '+header-a',
        '@@ -10,3 +10,5 @@',
        '+hunk-one-a',
        '+hunk-one-b',
        ...Array.from({ length: 2000 }, (_, i) => `+bulk line ${i} ${'z'.repeat(30)}`),
        '@@ -99,3 +101,4 @@',
        '+hunk-two-a',
      ];
      const diffs = new Map<string, string>([['app/src/main/java/demo/Big.kt', fileDiff('app/src/main/java/demo/Big.kt', body)]]);
      const chunks = buildReviewDiffChunks(diffs, ['app/src/main/java/demo/Big.kt']);
      assert.ok(chunks.length >= 2, `giant patch splits (got ${chunks.length})`);
      for (const chunk of chunks) {
        // Line integrity: slices retain their newline separators, so a chunk
        // ending mid-file may end with '\n'; no chunk ever starts with one
        // and no line is split across chunks.
        assert.ok(!chunk.diff.startsWith('\n'), 'no leading separator');
        assert.ok(chunk.diff.length > 0, 'non-empty chunk text');
      }
      assert.ok(verifyReviewDiffChunkCoverage(chunks, {
        totalLength: chunks[chunks.length - 1].end,
        files: ['app/src/main/java/demo/Big.kt'],
      }), 'giant slices cover fully');
      assert.ok(verifyReviewDiffChunkCoverage(chunks, {
        totalLength: chunks[chunks.length - 1].end,
        files: ['app/src/main/java/demo/Big.kt'],
        maxLength: MAX_PR_CHUNK_LENGTH,
      }), 'giant slices respect the per-chunk budget');
      for (const chunk of chunks) {
        assert.ok(chunk.diff.length <= MAX_PR_CHUNK_LENGTH, `chunk diff respects budget (got ${chunk.diff.length})`);
      }
      // Hunk integrity: no chunk starts mid-hunk except the slice carrying it.
      const slices = sliceDiffForChunk(fileDiff('f', body), MAX_PR_CHUNK_LENGTH);
      assert.ok(slices.length >= 2, 'slicer splits the giant file');
      for (const slice of slices) {
        assert.ok(slice.length <= MAX_PR_CHUNK_LENGTH, `strict budget holds (got ${slice.length})`);
      }
      assert.equal(slices.join(''), fileDiff('f', body), 'slices reassemble exactly');
      assert.ok(slices.some((s) => s.includes('@@ -99,3 +101,4 @@')), 'second hunk header survives slicing');
    }

    // Unit: 8001-char single line splits inside the line with strict budget.
    {
      const single8001 = `+${'a'.repeat(8000)}`;
      assert.equal(single8001.length, 8001, 'fixture is one 8001-char line');
      const slices = sliceDiffForChunk(single8001, MAX_PR_CHUNK_LENGTH);
      assert.ok(slices.length >= 2, `8001-char line splits (got ${slices.length})`);
      for (const slice of slices) {
        assert.ok(slice.length <= MAX_PR_CHUNK_LENGTH, `8001 slice respects budget (got ${slice.length})`);
      }
      assert.equal(slices.join(''), single8001, '8001 slices reassemble exactly');
      const src = fileDiff('app/src/main/java/demo/Single.kt', [single8001]);
      const fileSlices = sliceDiffForChunk(src, MAX_PR_CHUNK_LENGTH);
      assert.ok(fileSlices.every((s) => s.length <= MAX_PR_CHUNK_LENGTH), 'file with 8001 line respects budget');
      assert.equal(fileSlices.join(''), src, 'file slices reassemble exactly');
      const diffs = new Map<string, string>([['app/src/main/java/demo/Single.kt', src]]);
      const chunks = buildReviewDiffChunks(diffs, ['app/src/main/java/demo/Single.kt']);
      assert.ok(chunks.length >= 2, `8001 file chunks (got ${chunks.length})`);
      for (const chunk of chunks) {
        assert.ok(validateReviewDiffChunk(chunk), 'each 8001 chunk validates');
        assert.ok(chunk.diff.length <= MAX_PR_CHUNK_LENGTH, '8001 chunk diff respects budget');
      }
      assert.equal(chunks.map((c) => c.diff).join(''), src, '8001 chunk diffs reassemble to the file diff');
      assert.ok(verifyReviewDiffChunkCoverage(chunks, {
        totalLength: chunks[chunks.length - 1].end,
        files: ['app/src/main/java/demo/Single.kt'],
        maxLength: MAX_PR_CHUNK_LENGTH,
      }), '8001 coverage verifies with budget');
      assert.deepEqual(chunks.flatMap((c) => c.files).filter((f, i, a) => a.indexOf(f) === i), ['app/src/main/java/demo/Single.kt'], '8001 preserves file attribution');
    }

    // Unit: 120001-char single minified line splits fully and can APPROVE.
    {
      const hugeLine = `+${Array.from({ length: 120000 }, (_, i) => (i % 10 === 0 ? ';' : 'x')).join('')}`;
      assert.equal(hugeLine.length, 120001, 'fixture is one 120001-char line');
      const slices = sliceDiffForChunk(hugeLine, MAX_PR_CHUNK_LENGTH);
      assert.ok(slices.length >= 15, `120001-char line splits (got ${slices.length})`);
      for (const slice of slices) {
        assert.ok(slice.length <= MAX_PR_CHUNK_LENGTH, `120001 slice respects budget (got ${slice.length})`);
      }
      assert.equal(slices.join(''), hugeLine, '120001 slices reassemble exactly');
      // Safe-context preference: a ';' near the fixed offset ends a fragment.
      assert.ok(slices.slice(0, -1).some((s) => s.endsWith(';')), 'safe boundary preferred over hard cut');
      const src = fileDiff('app/src/main/java/demo/Huge.kt', [hugeLine]);
      const diffs = new Map<string, string>([['app/src/main/java/demo/Huge.kt', src]]);
      const chunks = buildReviewDiffChunks(diffs, ['app/src/main/java/demo/Huge.kt']);
      assert.ok(chunks.length >= 2, `120001 file chunks (got ${chunks.length})`);
      for (const chunk of chunks) {
        assert.ok(validateReviewDiffChunk(chunk), 'each 120001 chunk validates');
        assert.ok(chunk.diff.length <= MAX_PR_CHUNK_LENGTH, '120001 chunk diff respects budget');
        assert.deepEqual(chunk.files, ['app/src/main/java/demo/Huge.kt'], '120001 preserves file attribution');
      }
      assert.equal(chunks.map((c) => c.diff).join(''), src, '120001 chunk diffs reassemble to the file diff');
      assert.ok(verifyReviewDiffChunkCoverage(chunks, {
        totalLength: chunks[chunks.length - 1].end,
        files: ['app/src/main/java/demo/Huge.kt'],
        maxLength: MAX_PR_CHUNK_LENGTH,
      }), '120001 coverage verifies with budget');
      assert.ok(validateReviewDiffChunks(chunks, { fileDiffs: diffs, changedFiles: ['app/src/main/java/demo/Huge.kt'] }), '120001 rebuild validates');
      // Full coverage with unanimous APPROVE synthesizes APPROVE (never INCONCLUSIVE by truncation).
      const perChunk = chunks.map(() => fakeOrchestrated('APPROVE'));
      assert.equal(synthesizeChunkedReview(perChunk, {
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: src.length },
        deterministicViolations: [],
      }).verdict, 'APPROVE', '120001 full chunk coverage may APPROVE');
    }

    // Unit: giant minified file preserves file/hunk attribution with intra-line cuts.
    {
      const minLine = `+${'a'.repeat(7890)};${'b'.repeat(5000)}`;
      const body = ['+header-a', '@@ -10,3 +10,5 @@', minLine, '+tail-b'];
      const src = fileDiff('app/src/main/java/demo/Min.kt', body);
      const slices = sliceDiffForChunk(src, MAX_PR_CHUNK_LENGTH);
      for (const slice of slices) {
        assert.ok(slice.length <= MAX_PR_CHUNK_LENGTH, `minified slice respects budget (got ${slice.length})`);
      }
      assert.equal(slices.join(''), src, 'minified slices reassemble exactly');
      assert.ok(slices.some((s) => s.includes('@@ -10,3 +10,5 @@')), 'minified hunk header survives slicing');
      assert.ok(slices.some((s) => s.endsWith(';')), 'minified safe boundary preferred');
      const diffs = new Map<string, string>([['app/src/main/java/demo/Min.kt', src]]);
      const chunks = buildReviewDiffChunks(diffs, ['app/src/main/java/demo/Min.kt']);
      for (const chunk of chunks) {
        assert.ok(chunk.diff.length <= MAX_PR_CHUNK_LENGTH, 'minified chunk diff respects budget');
        assert.deepEqual(chunk.files, ['app/src/main/java/demo/Min.kt'], 'minified preserves file attribution');
        assert.ok(!chunk.diff.startsWith('\n'), 'no leading separator');
      }
      assert.equal(chunks.map((c) => c.diff).join(''), src, 'minified chunk diffs reassemble');
      assert.ok(verifyReviewDiffChunkCoverage(chunks, {
        totalLength: chunks[chunks.length - 1].end,
        files: ['app/src/main/java/demo/Min.kt'],
        maxLength: MAX_PR_CHUNK_LENGTH,
      }), 'minified coverage verifies with budget');
      // Overlong hunk header is cut the same way with continuations attributed
      // to the original line (only the first fragment carries '^@@ ').
      const hunkLong = `@@ ${'x'.repeat(9000)} @@`;
      const hunkSrc = `a\n${hunkLong}\nb`;
      const hunkSlices = sliceDiffForChunk(hunkSrc, MAX_PR_CHUNK_LENGTH);
      assert.ok(hunkSlices.every((s) => s.length <= MAX_PR_CHUNK_LENGTH), 'overlong hunk respects budget');
      assert.equal(hunkSlices.join(''), hunkSrc, 'overlong hunk reassembles exactly');
      assert.equal(hunkSlices.filter((s) => /^@@ /.test(s)).length, 1, 'only first fragment carries the hunk header');
    }

    // Unit: per-chunk input upper bound fails closed.
    {
      const diffs = new Map<string, string>([['app/src/main/java/demo/Single.kt', fileDiff('app/src/main/java/demo/Single.kt', ['+' + 'q'.repeat(9000)])]]);
      const chunks = buildReviewDiffChunks(diffs, ['app/src/main/java/demo/Single.kt']);
      assert.ok(chunks.length >= 1, 'fixture chunks');
      const good = chunks[0];
      assert.ok(validateReviewDiffChunk(good), 'good chunk validates');
      const oversizedDiff = `${'x'.repeat(MAX_PR_CHUNK_LENGTH + 1)}`;
      const tampered = { ...good, files: [...good.files], diff: oversizedDiff, coveredLength: oversizedDiff.length, end: good.start + oversizedDiff.length };
      assert.equal(validateReviewDiffChunk(tampered), undefined, 'oversized diff rejected fail-closed');
      assert.equal(validateReviewDiffChunks([tampered]), undefined, 'oversized chunk list rejected');
      assert.equal(verifyReviewDiffChunkCoverage([tampered], { maxLength: MAX_PR_CHUNK_LENGTH }), false, 'verify rejects oversized diff with budget');
      assert.equal(verifyReviewDiffChunkCoverage(chunks, { maxLength: MAX_PR_CHUNK_LENGTH }), true, 'verify accepts budgeted chunks');
      assert.equal(verifyReviewDiffChunkCoverage(chunks, { maxLength: 0 }), false, 'verify rejects invalid budget fail-closed');
      assert.equal(verifyReviewDiffChunkCoverage(chunks, { maxLength: Number.NaN }), false, 'verify rejects NaN budget fail-closed');
      assert.throws(() => buildReviewDiffChunks(new Map(), ['app/src/main/java/demo/Single.kt']), /No review diffs/, 'empty input fails closed');
    }

    // Unit: tampered coverage is rejected.
    {
      const diffs = new Map<string, string>([
        ['app/src/main/java/demo/A.kt', bigFileDiff('app/src/main/java/demo/A.kt', 1500)],
        ['app/src/main/java/demo/B.kt', bigFileDiff('app/src/main/java/demo/B.kt', 1500)],
      ]);
      const chunks = buildReviewDiffChunks(diffs, [...diffs.keys()]);
      assert.ok(chunks.length >= 3, 'fixture spans several chunks');
      assert.equal(validateReviewDiffChunks(chunks.slice(1), {
        fileDiffs: diffs, changedFiles: [...diffs.keys()],
      }), undefined, 'omitted chunk rejected');
      const overlapped = chunks.map((c) => ({ ...c, files: [...c.files], diff: c.diff }));
      if (overlapped.length >= 2) {
        overlapped[1] = { ...overlapped[1], start: overlapped[0].start };
        assert.equal(validateReviewDiffChunks(overlapped), undefined, 'overlapping span rejected');
      }
      const reports = chunks.map((c) => ({
        index: c.index, total: c.total, start: c.start, end: c.end, complete: true,
        coveredLength: c.coveredLength, files: [...c.files], verdict: 'APPROVE' as const,
      }));
      assert.ok(validateReviewDiffChunkReports(reports), 'reports validate');
      assert.ok(reports.every((r) => validateReviewDiffChunkReport(r)), 'each report validates');
      assert.equal(validateReviewDiffChunkReports(reports.slice(1)), undefined, 'missing report rejected');
    }

    // Unit: collectFileDiffs uses literal pathspecs and fails closed on empty review patches.
    {
      const seen: string[][] = [];
      const diffs = collectFileDiffs(
        (args: string[]) => { seen.push(args); return 'patch'; },
        BASE_SHA, HEAD_SHA, ['weird:(exclude)**'],
      );
      assert.equal(diffs.get('weird:(exclude)**'), 'patch');
      assert.ok(seen.every((args) => args[0] === '--literal-pathspecs'), 'literal pathspecs forced');
      assert.throws(() => collectFileDiffs(() => '', BASE_SHA, HEAD_SHA, ['app/src/main/java/demo/Safe.kt']),
        /no diff for a changed review file/, 'empty review patch fails closed');
    }

    // Unit: synthesis rules (BLOCK/NEEDS first, then INCONCLUSIVE, then APPROVE).
    {
      const detBlock = [{ ruleId: 'SEC-X', severity: 'BLOCK' as const, category: 'security' as const, message: 'm' }];
      const allApprove = [fakeOrchestrated('APPROVE'), fakeOrchestrated('APPROVE')];
      assert.equal(synthesizeChunkedReview(allApprove, {
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 130000 },
        deterministicViolations: [],
      }).verdict, 'APPROVE', 'unanimous chunk APPROVE approves');
      assert.equal(synthesizeChunkedReview(allApprove, {
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 130000 },
        deterministicViolations: detBlock,
      }).verdict, 'NEEDS_CHANGES', 'deterministic BLOCK forces NEEDS_CHANGES first');
      const needs = [fakeOrchestrated('APPROVE'), fakeOrchestrated('NEEDS_CHANGES')];
      assert.equal(synthesizeChunkedReview(needs, {
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 130000 },
        deterministicViolations: [],
      }).verdict, 'NEEDS_CHANGES', 'any chunk NEEDS_CHANGES forces NEEDS_CHANGES');
      const blockFinding = fakeOrchestrated('APPROVE');
      blockFinding.roles[0] = { ...blockFinding.roles[0], findings: [{ severity: 'BLOCK', issue: 'critical' }] };
      assert.equal(synthesizeChunkedReview([blockFinding], {
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 130000 },
        deterministicViolations: [],
      }).verdict, 'NEEDS_CHANGES', 'any BLOCK finding forces NEEDS_CHANGES');
      const inconclusive = [fakeOrchestrated('APPROVE'), fakeOrchestrated('INCONCLUSIVE')];
      assert.equal(synthesizeChunkedReview(inconclusive, {
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 130000 },
        deterministicViolations: [],
      }).verdict, 'INCONCLUSIVE', 'any chunk INCONCLUSIVE forces INCONCLUSIVE');
      assert.equal(synthesizeChunkedReview([], {
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 130000 },
        deterministicViolations: [],
      }).verdict, 'INCONCLUSIVE', 'empty chunk set never approves');
      const omitted = [fakeOrchestrated('APPROVE')];
      assert.equal(synthesizeChunkedReview(omitted, {
        coverage: { complete: false, omittedFiles: ['app/src/main/AndroidManifest.xml'], truncatedFiles: [], originalLength: 130000 },
        deterministicViolations: [],
      }).verdict, 'INCONCLUSIVE', 'incomplete coverage never approves');
    }

    // E2E: >120k multi-file diff gets full chunk coverage and may APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-chunks-approve-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'chunk ok', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const files = chunkedFiles();
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'APPROVE', '>120k full chunk coverage may APPROVE');
        assert.ok(reviewed.chunks && reviewed.chunks.length >= 3, `splits into chunks (got ${reviewed.chunks?.length})`);
        assert.equal(reviewed.chunkCount, reviewed.chunks?.length);
        assert.equal(reviewed.chunkCoverageComplete, true);
        assert.equal(reviewed.coverage.complete, true);
        assert.deepEqual(reviewed.coverage.omittedFiles, []);
        assert.deepEqual(reviewed.coverage.truncatedFiles, []);
        assert.ok(counter.count >= 3, `per-chunk orchestration ran (got ${counter.count})`);
        for (const report of reviewed.chunks ?? []) {
          assert.ok(validateReviewDiffChunkReport(report), 'each chunk report validates');
          assert.equal(report.complete, true);
          assert.equal(report.verdict, 'APPROVE');
          assert.ok(!('diff' in (report as unknown as Record<string, unknown>)), 'minimal retention: no chunk diff text');
        }
        // Reports carry per-chunk segments; sticky carries the chunk line.
        const reportJson = JSON.parse(fs.readFileSync(path.join(tempDir, 'review-report.json'), 'utf8')) as Record<string, unknown>;
        assert.ok(Array.isArray(reportJson.segments) && (reportJson.segments as unknown[]).length === reviewed.chunks?.length, 'report carries segments');
        const reportMd = fs.readFileSync(path.join(tempDir, 'review-report.md'), 'utf8');
        assert.ok(reportMd.includes('分段結果'), 'markdown shows per-chunk results');
        const sticky = reviewComment(reviewed, [], '報告行');
        assert.ok(sticky.includes('分段：'), 'sticky carries chunk line');
        assert.ok(sticky.includes('判定：APPROVE'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: deterministic BLOCK over the full diff short-circuits before any AI.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-chunks-block-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const secretParts = ['const fakeSecret = "ghp_', `${'A'.repeat(20)}";`];
        const files = {
          ...chunkedFiles(),
          'app/src/main/java/demo/Secret.kt': fileDiff('app/src/main/java/demo/Secret.kt', [
            `+${secretParts[0]}`,
            `+${secretParts[1]}`,
          ]),
        };
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'must not run', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'full-diff BLOCK short-circuits');
        assert.equal(counter.count, 0, 'BLOCK makes zero OpenAI calls');
        assert.equal(reviewed.chunks, undefined, 'BLOCK path stores no chunks');
        assert.ok(reviewed.deterministicViolations.some((v) => v.ruleId === 'SEC-PRIVATE-KEY' && v.severity === 'BLOCK'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: any chunk NEEDS_CHANGES synthesizes NEEDS_CHANGES.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-chunks-needs-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'NEEDS_CHANGES', summary: 'blocking issue', findings: [{ severity: 'WARN', issue: 'needs work' }], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(chunkedFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'one NEEDS chunk forces NEEDS_CHANGES');
        assert.ok(reviewed.chunks && reviewed.chunks.length >= 2, 'still chunked');
        assert.ok((reviewed.chunks ?? []).some((c) => c.verdict === 'NEEDS_CHANGES'), 'per-chunk verdict readable');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: any chunk INCONCLUSIVE (schema failure) synthesizes INCONCLUSIVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-chunks-incon-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'bad', findings: [], suggestedLabels: 'not-an-array' },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(chunkedFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'one failed chunk forces INCONCLUSIVE');
        assert.ok(reviewed.chunks && reviewed.chunks.length >= 2, 'still chunked');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: publish triple gate — missing chunks and incomplete coverage fall back on the same sticky.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-chunks-pub-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] }]);
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(chunkedFiles()),
          });
        } finally {
          restore();
        }
        const good = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
        // Gate A: chunks dropped but flags kept → schema invalid → INCONCLUSIVE.
        {
          const tampered = { ...good };
          delete tampered.chunks;
          fs.writeFileSync(artifactPath, JSON.stringify(tampered));
          const state = {
            comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
            created: 0,
            updated: 0,
          };
          await runPublishMode({
            event: prEvent(),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: prClient(state),
          });
          assert.equal(state.created, 0, 'missing-chunk publish creates no second comment');
          assert.equal(state.updated, 1, 'missing-chunk publish updates the same sticky');
          assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'missing chunks fall back');
          assert.ok(!state.comments[0].body.includes('判定：APPROVE'));
        }
        // Gate B: chunkCoverageComplete false → INCONCLUSIVE on the same sticky.
        {
          const incomplete = JSON.parse(JSON.stringify(good)) as Record<string, unknown>;
          (incomplete as { verdict: string }).verdict = 'NEEDS_CHANGES';
          const roles = (incomplete.roles as Array<Record<string, unknown>>).map((r) => ({ ...r, verdict: 'NEEDS_CHANGES' }));
          incomplete.roles = roles;
          incomplete.chunkCoverageComplete = false;
          fs.writeFileSync(artifactPath, JSON.stringify(incomplete));
          const state = {
            comments: [{ id: 9, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
            created: 0,
            updated: 0,
          };
          await runPublishMode({
            event: prEvent(),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: prClient(state),
          });
          assert.equal(state.updated, 1, 'incomplete-coverage publish updates the same sticky');
          assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'incomplete chunk coverage falls back');
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: multi-chunk rounds consume one slot each; re-review budget stays 2 per SHA (claim untouched).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-chunks-slot-'));
      try {
        const runReviewPublish = async (runId: string, artifactPath: string): Promise<void> => {
          const counter = { count: 0 };
          const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] }]);
          try {
            const claimed = await runClaimMode({
              event: prEvent(),
              env: {
                GITHUB_EVENT_NAME: 'pull_request_target',
                GITHUB_REPOSITORY: REPO,
                GITHUB_TOKEN: 'fake-token',
                POCKETGUARD_RUN_ID: runId,
              } as NodeJS.ProcessEnv,
              githubClient: prClient(shared),
              writeStdout: () => undefined,
              runGit: prGitStub(chunkedFiles()),
            });
            assert.equal(claimed.claimed, true, `${runId} claims one slot`);
            const client = prClient(shared);
            const reviewed = await runReviewMode({
              event: prEvent(),
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath, POCKETGUARD_RUN_ID: runId } as NodeJS.ProcessEnv,
              githubClient: client,
              writeStdout: () => undefined,
              runGit: prGitStub(chunkedFiles()),
            });
            assert.ok((reviewed.chunkCount ?? 0) >= 3, `${runId} reviews many chunks`);
            assert.ok(counter.count >= 9, `${runId} runs per-chunk orchestration`);
            await runPublishMode({
              event: prEvent(),
              env: {
                GITHUB_EVENT_NAME: 'pull_request_target',
                GITHUB_REPOSITORY: REPO,
                GITHUB_TOKEN: 'fake-token',
                POCKETGUARD_OUTPUT: artifactPath,
                POCKETGUARD_REVIEW_JOB_RESULT: 'success',
                POCKETGUARD_TAG_LABELS: '[]',
                POCKETGUARD_RUN_ID: runId,
              } as NodeJS.ProcessEnv,
              githubClient: prClient(shared),
            });
          } finally {
            restore();
          }
        };
        const shared: { comments: StickyComment[]; created: number; updated: number } = { comments: [], created: 0, updated: 0 };
        await runReviewPublish('run-a', path.join(tempDir, 'a-output.json'));
        assert.equal(parseReviewCountMarker(shared.comments[0]?.body ?? '', HEAD_SHA), 1, 'first multi-chunk round holds 1 slot');
        const stickyId = shared.comments[0]?.id;
        await runReviewPublish('run-b', path.join(tempDir, 'b-output.json'));
        assert.equal(parseReviewCountMarker(shared.comments[0]?.body ?? '', HEAD_SHA), 2, 're-review holds the 2nd slot');
        assert.equal(shared.comments.length, 1, 'single sticky across rounds');
        assert.equal(shared.comments[0]?.id, stickyId, 'same sticky ID updated in place');
        assert.ok(shared.comments[0]?.body.includes('判定：APPROVE'), 'chunked APPROVE publishes');
        assert.ok(shared.comments[0]?.body.includes('分段：'), 'published sticky carries the chunk line');
        // Third round is quota-exhausted: claim refuses, budget stays 2.
        const denied = await runClaimMode({
          event: prEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-c',
          } as NodeJS.ProcessEnv,
          githubClient: prClient(shared),
          writeStdout: () => undefined,
          runGit: prGitStub(chunkedFiles()),
        });
        assert.equal(denied.claimed, false, 'third round claims nothing (budget 2)');
        assert.equal(parseReviewCountMarker(shared.comments[0]?.body ?? '', HEAD_SHA), 2, 'budget stays 2');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard pr-chunks tests] All tests passed.');
}
