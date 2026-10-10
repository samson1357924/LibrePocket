import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { MAX_PR_CHUNK_LENGTH } from '../src/review_diff';
import {
  computePrChunkCoverageComplete,
  DEFAULT_REVIEW_DEADLINE_MS,
  estimateChunkedReviewMs,
  MAX_PER_CHUNK_TIMEOUT_MS,
  MIN_VIABLE_PER_CHUNK_TIMEOUT_MS,
  PR_CHUNK_CONCURRENCY,
  parseReviewCountMarker,
  resolvePerChunkTimeoutMs,
  resolveReviewDeadlineMs,
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

function prEvent(): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: REPO },
    pull_request: {
      number: 41,
      title: 'phase3 chunks',
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

function chunkedFiles(): Record<string, string> {
  return {
    'app/src/main/AndroidManifest.xml': fileDiff('app/src/main/AndroidManifest.xml', ['+<manifest />']),
    'app/src/main/java/demo/Safe.kt': fileDiff('app/src/main/java/demo/Safe.kt', ['+class Safe']),
    'app/src/main/java/demo/Big.kt': bigFileDiff('app/src/main/java/demo/Big.kt', 2500),
  };
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

function installImmediateApprove(counter: { count: number }): () => void {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => {
    counter.count += 1;
    return new Response(JSON.stringify({
      output: [{ content: [{ type: 'output_text', text: JSON.stringify({ verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] }) }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  return () => { globalThis.fetch = previousFetch; };
}

// Fake fetch with per-role delays plus abort penetration: a role whose delay
// exceeds the transport timeoutMs aborts (rejects on the signal) so the send
// fails closed to INCONCLUSIVE instead of hanging the wave.
function installRoleDelayStub(
  counter: { count: number },
  opts: { chiefDelayMs: number; otherDelayMs?: number },
): () => void {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async (_input: unknown, init?: { signal?: AbortSignal; body?: unknown }) => {
    counter.count += 1;
    let model = '';
    try {
      const rawBody = typeof init?.body === 'string' ? init.body : '';
      if (rawBody) model = String((JSON.parse(rawBody) as { model?: unknown }).model ?? '');
    } catch {
      model = '';
    }
    const isChief = model.includes('chief');
    const delay = isChief ? opts.chiefDelayMs : (opts.otherDelayMs ?? 0);
    const signal = init?.signal;
    if (delay > 0) {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(resolve, delay);
        if (signal) {
          if (signal.aborted) {
            clearTimeout(timer);
            reject(new Error('OpenAI request failed'));
          } else {
            signal.addEventListener('abort', () => {
              clearTimeout(timer);
              reject(new Error('OpenAI request failed'));
            }, { once: true });
          }
        }
      });
    }
    if (signal?.aborted) throw new Error('OpenAI request failed');
    return new Response(JSON.stringify({
      output: [{ content: [{ type: 'output_text', text: JSON.stringify({ verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] }) }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  return () => { globalThis.fetch = previousFetch; };
}

export async function runPrPhase3Tests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  const realNow = Date.now;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    // Unit: deadline/timeout estimation helpers (fake clock values, no I/O).
    {
      assert.equal(DEFAULT_REVIEW_DEADLINE_MS, 20 * 60 * 1000 - 60_000, 'default deadline is 20min minus margin');
      assert.equal(PR_CHUNK_CONCURRENCY, 2, 'fixed concurrency 2');
      assert.equal(MAX_PER_CHUNK_TIMEOUT_MS, 420_000, 'per-chunk ceiling mirrors send default 420s');
      assert.equal(resolveReviewDeadlineMs({} as NodeJS.ProcessEnv), DEFAULT_REVIEW_DEADLINE_MS, 'deadline defaults without env');
      assert.equal(resolveReviewDeadlineMs({ POCKETGUARD_REVIEW_DEADLINE_MS: '5000' } as NodeJS.ProcessEnv), 5000, 'deadline honors env');
      assert.equal(resolveReviewDeadlineMs({ POCKETGUARD_REVIEW_DEADLINE_MS: 'nope' } as NodeJS.ProcessEnv), DEFAULT_REVIEW_DEADLINE_MS, 'deadline rejects non-numeric fail-closed to default');
      // timeoutMs passthrough min(420s, remaining/waves).
      assert.equal(resolvePerChunkTimeoutMs({} as NodeJS.ProcessEnv, 1_140_000, 9), 126666, 'timeout shares remaining over waves');
      assert.equal(resolvePerChunkTimeoutMs({} as NodeJS.ProcessEnv, 100_000, 1), 100_000, 'single wave takes remaining when below ceiling');
      assert.equal(resolvePerChunkTimeoutMs({} as NodeJS.ProcessEnv, 5_000_000, 1), MAX_PER_CHUNK_TIMEOUT_MS, 'timeout never exceeds 420s ceiling');
      assert.equal(
        resolvePerChunkTimeoutMs({ POCKETGUARD_CHUNK_TIMEOUT_MS: '150' } as NodeJS.ProcessEnv, 1_140_000, 9),
        150,
        'explicit chunk timeout override passes through under the ceiling',
      );
      assert.equal(estimateChunkedReviewMs(18, 126666, 2), 9 * 126666, 'estimate is waves x timeout under concurrency');
      assert.equal(estimateChunkedReviewMs(1, 420_000, 2), 420_000, 'single chunk needs one wave');
      // Computed coverage: any INCONCLUSIVE (including transport) forces false.
      assert.equal(
        computePrChunkCoverageComplete([
          { index: 0, total: 2, start: 0, end: 5, complete: true, coveredLength: 5, files: ['a'], verdict: 'APPROVE' },
          { index: 1, total: 2, start: 5, end: 10, complete: true, coveredLength: 5, files: ['a'], verdict: 'INCONCLUSIVE' },
        ], { totalLength: 10, files: ['a'], maxLength: MAX_PR_CHUNK_LENGTH }),
        false,
        'transport INCONCLUSIVE forces coverage false',
      );
      assert.equal(
        computePrChunkCoverageComplete([
          { index: 0, total: 1, start: 0, end: 5, complete: true, coveredLength: 5, files: ['a'], verdict: 'NEEDS_CHANGES' },
        ], { totalLength: 5, files: ['a'], maxLength: MAX_PR_CHUNK_LENGTH }),
        true,
        'conclusive NEEDS_CHANGES keeps coverage true',
      );
      assert.equal(computePrChunkCoverageComplete([], { totalLength: 0, files: [] }), false, 'empty set never covers');
    }

    // E2E: slow chief role times out via timeoutMs passthrough (fake fetch
    // delay + abort), verdict INCONCLUSIVE with computed coverage false.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-phase3-slow-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installRoleDelayStub(counter, { chiefDelayMs: 1000 });
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_CHUNK_TIMEOUT_MS: '150',
            } as NodeJS.ProcessEnv,
            githubClient: prClient({ comments: [], created: 0, updated: 0 }),
            writeStdout: () => undefined,
            runGit: prGitStub(chunkedFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'slow role timeout forces INCONCLUSIVE');
        assert.equal(reviewed.chunkCoverageComplete, false, 'slow transport never writes true');
        assert.ok(reviewed.chunks && reviewed.chunks.length >= 3, 'slow run keeps explicit per-chunk reports (no silent omission)');
        assert.ok((reviewed.chunks ?? []).some((c) => c.verdict === 'INCONCLUSIVE'), 'timed-out chunks readable as INCONCLUSIVE');
        assert.ok(counter.count >= 9, `slow roles still attempted transport (got ${counter.count})`);
        // Checkpoint artifact: deadline/timeout exit saves INCONCLUSIVE+false.
        const artifact = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
        assert.equal(artifact.verdict, 'INCONCLUSIVE', 'timeout artifact checkpoints INCONCLUSIVE');
        assert.equal(artifact.chunkCoverageComplete, false, 'timeout artifact checkpoints coverage false');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: deadline early exit with a fake clock (frozen Date.now plus a tiny
    // budget) saves INCONCLUSIVE + false with placeholders, never true.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-phase3-deadline-'));
      const frozenNow = 1_000_000;
      Date.now = () => frozenNow;
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installImmediateApprove(counter);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_DEADLINE_MS: '5',
              POCKETGUARD_CHUNK_TIMEOUT_MS: '1000',
            } as NodeJS.ProcessEnv,
            githubClient: prClient({ comments: [], created: 0, updated: 0 }),
            writeStdout: () => undefined,
            runGit: prGitStub(chunkedFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'deadline exit forces INCONCLUSIVE');
        assert.equal(reviewed.chunkCoverageComplete, false, 'deadline exit never writes true');
        assert.ok(reviewed.chunks && reviewed.chunks.length >= 3, 'deadline exit keeps placeholders (no silent omission)');
        assert.equal(reviewed.chunkCount, reviewed.chunks?.length, 'deadline chunkCount matches reports');
        assert.equal(counter.count, 0, 'deadline exit starts zero chunk turns when remaining < need');
        const artifact = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
        assert.equal(artifact.verdict, 'INCONCLUSIVE', 'deadline exit checkpoints the artifact');
        assert.equal(artifact.chunkCoverageComplete, false, 'deadline artifact checkpoints coverage false');
      } finally {
        Date.now = realNow;
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: fixed-concurrency aggregation is index-sorted and deterministic
    // across repeated runs (same stubs, same verdict/roles/chunks).
    {
      const runOnce = async (artifactPath: string): Promise<Awaited<ReturnType<typeof runReviewMode>>> => {
        const counter = { count: 0 };
        const restore = installImmediateApprove(counter);
        try {
          return await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: prClient({ comments: [], created: 0, updated: 0 }),
            writeStdout: () => undefined,
            runGit: prGitStub(chunkedFiles()),
          });
        } finally {
          restore();
        }
      };
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-phase3-concur-'));
      try {
        const first = await runOnce(path.join(tempDir, 'first-output.json'));
        const second = await runOnce(path.join(tempDir, 'second-output.json'));
        assert.equal(first.verdict, 'APPROVE', 'concurrent full coverage may APPROVE');
        assert.equal(second.verdict, first.verdict, 'repeated concurrent runs agree on verdict');
        assert.deepEqual(second.chunks, first.chunks, 'repeated concurrent runs agree on index-sorted reports');
        assert.deepEqual(
          (second.chunks ?? []).map((c) => c.index),
          (second.chunks ?? []).map((_, i) => i),
          'reports stay index-sorted (verifiable aggregation)',
        );
        assert.equal(second.chunkCoverageComplete, true, 'conclusive concurrent run computes true');
        assert.deepEqual(second.roles, first.roles, 'repeated runs agree on merged roles');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: a granted claim whose review later times out still holds its slot
    // (started-but-failed never refunds); publish falls back on the same sticky.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-phase3-slot-'));
      try {
        const shared: { comments: StickyComment[]; created: number; updated: number } = { comments: [], created: 0, updated: 0 };
        const claimed = await runClaimMode({
          event: prEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'phase3-timeout-run',
          } as NodeJS.ProcessEnv,
          githubClient: prClient(shared),
          writeStdout: () => undefined,
          runGit: prGitStub(chunkedFiles()),
        });
        assert.equal(claimed.claimed, true, 'timeout run still claims one slot before AI');
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installRoleDelayStub(counter, { chiefDelayMs: 1000 });
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_RUN_ID: 'phase3-timeout-run',
              POCKETGUARD_CHUNK_TIMEOUT_MS: '150',
            } as NodeJS.ProcessEnv,
            githubClient: prClient(shared),
            writeStdout: () => undefined,
            runGit: prGitStub(chunkedFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'timed-out review stays INCONCLUSIVE');
        assert.equal(reviewed.chunkCoverageComplete, false, 'timed-out review never writes true');
        await runPublishMode({
          event: prEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'phase3-timeout-run',
          } as NodeJS.ProcessEnv,
          githubClient: prClient(shared),
        });
        assert.equal(parseReviewCountMarker(shared.comments[0]?.body ?? '', HEAD_SHA), 1, 'timed-out claim still holds one slot (never refunded)');
        assert.ok(shared.comments[0]?.body.includes('判定：INCONCLUSIVE'), 'timed-out publish falls back on the same sticky');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: oversized budget pre-check claims nothing with zero writes (fake
    // git diff plus a tiny fake-clock budget).
    {
      const state: { comments: StickyComment[]; created: number; updated: number } = { comments: [], created: 0, updated: 0 };
      const denied = await runClaimMode({
        event: prEvent(),
        env: {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_RUN_ID: 'phase3-huge-run',
          POCKETGUARD_REVIEW_DEADLINE_MS: '1000',
        } as NodeJS.ProcessEnv,
        githubClient: prClient(state),
        writeStdout: () => undefined,
        runGit: prGitStub(chunkedFiles()),
      });
      assert.equal(denied.claimed, false, 'over-budget pre-check claims nothing');
      assert.match(denied.reason, /budget-exceeded/, 'over-budget reason is explicit');
      assert.equal(denied.reviewsUsed, 0, 'over-budget consumes zero slots');
      assert.equal(state.created, 0, 'over-budget performs zero sticky creates');
      assert.equal(state.updated, 0, 'over-budget performs zero sticky updates');
      assert.equal(state.comments.length, 0, 'over-budget leaves no placeholder (zero writes)');
      void MIN_VIABLE_PER_CHUNK_TIMEOUT_MS;
    }
  } finally {
    globalThis.fetch = previousFetch;
    Date.now = realNow;
  }
  console.log('[PocketGuard phase3 tests] All tests passed.');
}
