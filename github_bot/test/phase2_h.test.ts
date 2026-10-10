import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  areReviewReportsAvailable,
  buildIssueChunks,
  enforceGithubCommentLimit,
  formatReportLine,
  getReportRunUrl,
  issueContentFingerprint,
  issueReviewComment,
  rawSliceHash,
  reviewComment,
  runIssueReviewMode,
  runPublishMode,
  runClaimMode,
  sliceTextForChunk,
  stickyField,
  truncateStickyText,
  validateIssueChunk,
  validateIssueChunkReport,
  validateIssueChunkReports,
  validateIssueChunks,
  validateIssueOutput,
  verifyIssueChunkCoverage,
  writeReviewReports,
  REVIEW_REPORT_WRITE_ERROR,
  GITHUB_COMMENT_HARD_LIMIT,
  MAX_ISSUE_CHUNK_LENGTH,
  MAX_ISSUE_CHUNK_SUMMARY_LENGTH,
  MAX_STICKY_FILE_LIST_SHOWN,
  MAX_STICKY_ITEM_LENGTH,
  MAX_STICKY_TOTAL_LENGTH,
  type RunnerContext,
} from '../src/github_runner';

const TEST_BASE_URL = ['https:', '', 'pocketguard-openai.test', 'v1'].join('/');
const TEST_ORIGIN = new URL(TEST_BASE_URL).origin;
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

function issueClient(state: {
  comments: Array<{ id: number; body: string; user: { login: string; type: string } }>;
  created: number;
  updated: number;
}, opts?: {
  getTitle?: string;
  getBody?: string;
  listCommentsData?: Array<{ id: number; body: string; user: { login: string; type: string } }>;
}): NonNullable<RunnerContext['githubClient']> {
  return {
    rest: {
      users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
      issues: {
        get: async () => ({ data: { number: 7, title: opts?.getTitle ?? 't', body: opts?.getBody ?? 'b' } }),
        listComments: async () => ({ data: opts?.listCommentsData ?? [] }),
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

export async function runPhase2HTests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    // H-1: dual report files exist and are redacted (via redactForModel).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-report-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        // AI summary echoes a credential; reports must redact it.
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'found api_key: supersecret123 in text', suggestedLabels: [] },
        ]);
        try {
          const state = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const client = issueClient(state, { getTitle: 't', getBody: 'short body' });
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'short body' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE');
          const jsonPath = path.join(tempDir, 'review-report.json');
          const mdPath = path.join(tempDir, 'review-report.md');
          assert.equal(fs.existsSync(jsonPath), true, 'review-report.json exists');
          assert.equal(fs.existsSync(mdPath), true, 'review-report.md exists');
          const jsonText = fs.readFileSync(jsonPath, 'utf8');
          const mdText = fs.readFileSync(mdPath, 'utf8');
          assert.ok(!jsonText.includes('supersecret123'), 'JSON report redacts credential');
          assert.ok(!mdText.includes('supersecret123'), 'Markdown report redacts credential');
          assert.ok(jsonText.includes('[REDACTED'), 'JSON report carries redaction marker');
          const parsed = JSON.parse(jsonText) as Record<string, unknown>;
          for (const key of ['findings', 'roles', 'coverage', 'fingerprint', 'time']) {
            assert.ok(key in parsed, `report JSON carries ${key}`);
          }
          assert.equal(areReviewReportsAvailable({ POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv), true);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-2: link success vs failure branches (no fake link on failure).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-link-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] }]);
        let stickyId = 0;
        try {
          const reviewState = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const reviewClient = issueClient(reviewState, { getTitle: 'link-title', getBody: 'link-body' });
          await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'link-title', body: 'link-body' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
        } finally {
          restore();
        }
        // Success: reports exist + run identity present => sticky carries link.
        {
          const state = {
            comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
            created: 0,
            updated: 0,
          };
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'link-title', body: 'link-body' } }),
                listComments: async (params: { page: number }) => {
                  // First call is the publish fingerprint re-read (needs full
                  // comments, none here); subsequent calls are sticky lookup.
                  void params;
                  return { data: state.comments };
                },
                createComment: async () => { state.created += 1; return {}; },
                updateComment: async (params: { comment_id: number; body: string }) => {
                  state.updated += 1;
                  const c = state.comments.find((x) => x.id === params.comment_id);
                  if (c) c.body = params.body;
                  return {};
                },
                addLabels: async () => ({}),
                listLabelsOnIssue: async () => ({ data: [] }),
                removeLabel: async () => ({}),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          await runPublishMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'link-title', body: 'link-body' } },
            env: {
              GITHUB_EVENT_NAME: 'issues',
              GITHUB_REPOSITORY: REPO,
              GITHUB_SERVER_URL: 'https://github.com',
              GITHUB_RUN_ID: '123456',
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as unknown as NodeJS.ProcessEnv,
            githubClient: client,
          });
          assert.equal(state.created, 0, 'success reuses single sticky');
          assert.equal(state.updated, 1);
          stickyId = state.comments[0].id;
          assert.ok(state.comments[0].body.includes('pocketguard-review-report'), 'success sticky names artifact');
          assert.ok(state.comments[0].body.includes('actions/runs/123456'), 'success sticky links run');
          assert.ok(!state.comments[0].body.includes('報告：不可用'), 'success does not show unavailable');
        }
        // Failure: review job failed => INCONCLUSIVE + unavailable, no fake link.
        {
          const state = {
            comments: [{ id: 9, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
            created: 0,
            updated: 0,
          };
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'link-title', body: 'link-body' } }),
                listComments: async () => ({ data: state.comments }),
                createComment: async () => { state.created += 1; return {}; },
                updateComment: async (params: { comment_id: number; body: string }) => {
                  state.updated += 1;
                  const c = state.comments.find((x) => x.id === params.comment_id);
                  if (c) c.body = params.body;
                  return {};
                },
                addLabels: async () => ({}),
                listLabelsOnIssue: async () => ({ data: [] }),
                removeLabel: async () => ({}),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          await runPublishMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'link-title', body: 'link-body' } },
            env: {
              GITHUB_EVENT_NAME: 'issues',
              GITHUB_REPOSITORY: REPO,
              GITHUB_SERVER_URL: 'https://github.com',
              GITHUB_RUN_ID: '123456',
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'failure',
              POCKETGUARD_TAG_LABELS: '[]',
            } as unknown as NodeJS.ProcessEnv,
            githubClient: client,
          });
          assert.equal(state.updated, 1);
          assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'failure is INCONCLUSIVE');
          assert.ok(state.comments[0].body.includes('報告：不可用'), 'failure shows unavailable');
          assert.ok(!state.comments[0].body.includes('actions/runs/'), 'failure gives no fake link');
          void stickyId;
        }
        // formatReportLine unit: missing identity => unavailable, no fake URL.
        assert.equal(getReportRunUrl({} as NodeJS.ProcessEnv), undefined);
        assert.ok(formatReportLine({} as NodeJS.ProcessEnv, true).includes('不可用'));
        assert.ok((getReportRunUrl({ GITHUB_REPOSITORY: REPO, GITHUB_RUN_ID: '999' } as unknown as NodeJS.ProcessEnv) ?? '').includes('actions/runs/999'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-3: 20001-char body chunking with full coverage may APPROVE (minimal retention).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-chunk20001-'));
      try {
        const longBody = 'x'.repeat(20001);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'chunk ok', suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'long', body: longBody } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'long', body: longBody } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', '20001-char chunked full coverage may APPROVE');
          assert.equal(reviewed.commentsComplete, true);
          assert.ok(reviewed.chunks && reviewed.chunks.length >= 3, `20001 body splits into >=3 chunks (got ${reviewed.chunks?.length})`);
          assert.equal(reviewed.chunkCount, reviewed.chunks?.length);
          assert.equal(reviewed.chunkCoverageComplete, true);
          // Minimal retention: redacted proofs only, never full text.
          const validated = validateIssueChunkReports(reviewed.chunks);
          assert.ok(validated, 'chunk reports pass per-segment schema');
          for (const report of reviewed.chunks ?? []) {
            assert.ok(validateIssueChunkReport(report), 'each chunk report validates');
            assert.ok(report.start < report.end, 'start<end recorded');
            assert.equal(report.complete, true, 'complete recorded');
            assert.equal(report.verdict, 'APPROVE', 'per-segment verdict readable');
            assert.ok(report.summary.length <= MAX_ISSUE_CHUNK_SUMMARY_LENGTH, 'per-segment summary truncated');
            assert.ok(!('body' in (report as unknown as Record<string, unknown>)), 'no full chunk body in artifact');
            assert.ok(!('comments' in (report as unknown as Record<string, unknown>)), 'no full chunk comments in artifact');
          }
          assert.ok(counter.count >= 3, `per-chunk triage ran (got ${counter.count})`);
          // Fingerprint covers the full body (no tail collision).
          const expectedFp = issueContentFingerprint('long', longBody, []);
          // Note: runIssueReviewMode uses cleanForModel(title/body); plain
          // x-strings survive redaction unchanged, so the hash matches.
          assert.equal(reviewed.fingerprint, expectedFp, 'full fingerprint verified');
          assert.ok(MAX_ISSUE_CHUNK_LENGTH === 8000, 'chunk budget constant');
          // Artifact file carries the same minimal proofs.
          const persisted = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
          assert.ok(Array.isArray(persisted.chunks) && (persisted.chunks as unknown[]).length >= 3, 'artifact carries chunk reports');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-4: any single chunk failure => INCONCLUSIVE on the same sticky (same ID).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-chunkfail-'));
      try {
        const longBody = 'y'.repeat(20001);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        // Chunk 2 returns an invalid schema (fallback INCONCLUSIVE).
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'c0 ok', suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'c1 bad', suggestedLabels: 'not-an-array' },
          { verdict: 'APPROVE', summary: 'c2 ok', suggestedLabels: [] },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runIssueReviewMode>>;
        try {
          const reviewClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'long', body: longBody } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'long', body: longBody } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'any chunk failure forces INCONCLUSIVE');
        assert.ok(counter.count >= 3, 'all chunks attempted');
        // Publish reuses the same sticky ID (no second comment).
        const state = {
          comments: [{ id: 21, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const publishClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 'long', body: longBody } }),
              listComments: async () => ({ data: state.comments }),
              createComment: async () => { state.created += 1; return {}; },
              updateComment: async (params: { comment_id: number; body: string }) => {
                state.updated += 1;
                const c = state.comments.find((x) => x.id === params.comment_id);
                if (c) c.body = params.body;
                return {};
              },
              addLabels: async () => ({}),
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'long', body: longBody } },
          env: {
            GITHUB_EVENT_NAME: 'issues',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: publishClient,
        });
        assert.equal(state.created, 0, 'chunk-failure publish creates no second comment');
        assert.equal(state.updated, 1, 'chunk-failure publish updates same sticky');
        assert.equal(state.comments[0].id, 21, 'same comment ID updated in place');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'same sticky shows INCONCLUSIVE');
        assert.ok(!state.comments[0].body.includes('判定：APPROVE'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-5: multi-chunk single slot (claim semantics untouched; single sticky).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-slot-'));
      try {
        const longBody = 'z'.repeat(20001);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] }]);
        try {
          const reviewClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'long', body: longBody } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'long', body: longBody } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
          assert.ok((reviewed.chunkCount ?? 0) >= 3, 'multi chunks in one round');
          assert.ok(counter.count >= 3, 'multi OpenAI turns in one round');
          // Issues never consume the PR review budget: claim stays no-op.
          const claimClient = {
            rest: {
              pulls: { get: async () => { throw new Error('unreachable for issues'); } },
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                listComments: async () => ({ data: [] }),
                createComment: async () => ({}),
                updateComment: async () => ({}),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const claimed = await runClaimMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'long', body: longBody } },
            env: { GITHUB_EVENT_NAME: 'issues', GITHUB_REPOSITORY: REPO, GITHUB_TOKEN: 'fake-token' } as NodeJS.ProcessEnv,
            githubClient: claimClient,
            writeStdout: () => undefined,
          });
          assert.equal(claimed.claimed, false, 'multi-chunk issue still counts zero slots');
          assert.equal(claimed.reviewsUsed, 0);
          // Publish keeps a single sticky (same ID) with chunk coverage noted.
          const state = {
            comments: [{ id: 33, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
            created: 0,
            updated: 0,
          };
          const publishClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'long', body: longBody } }),
                listComments: async () => ({ data: state.comments }),
                createComment: async () => { state.created += 1; return {}; },
                updateComment: async (params: { comment_id: number; body: string }) => {
                  state.updated += 1;
                  const c = state.comments.find((x) => x.id === params.comment_id);
                  if (c) c.body = params.body;
                  return {};
                },
                addLabels: async () => ({}),
                listLabelsOnIssue: async () => ({ data: [] }),
                removeLabel: async () => ({}),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          await runPublishMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'long', body: longBody } },
            env: {
              GITHUB_EVENT_NAME: 'issues',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: publishClient,
          });
          assert.equal(state.comments.length, 1, 'single sticky throughout');
          assert.equal(state.comments[0].id, 33, 'same ID');
          assert.ok(state.comments[0].body.includes('分段：'), 'sticky records chunk start/end');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-6: chunk builder unit (start/end/complete/coveredLength, full coverage).
    {
      const fullBody = 'x'.repeat(20001);
      const chunks = buildIssueChunks('t', fullBody, []);
      assert.ok(chunks.length >= 3, 'builder splits 20001 body');
      assert.ok(validateIssueChunks(chunks, { title: 't', body: fullBody, comments: [] }), 'builder output validates with full coverage');
      assert.ok(verifyIssueChunkCoverage(chunks, { title: 't', body: fullBody, comments: [] }), 'coverage helper confirms no overlap/no omission');
      const totalSpan = chunks[chunks.length - 1].end - chunks[0].start;
      assert.ok(totalSpan > 20001, 'offsets cover title+body');
      for (let i = 1; i < chunks.length; i += 1) {
        assert.equal(chunks[i].start, chunks[i - 1].end, 'no gaps between chunks');
      }
      for (const chunk of chunks) {
        assert.ok(validateIssueChunk(chunk), 'each chunk validates per-segment caps');
        assert.equal(chunk.body.length <= MAX_ISSUE_CHUNK_LENGTH, true, 'per-segment body cap');
        assert.ok(chunk.coveredLength === chunk.body.length + chunk.comments.reduce((s, c) => s + c.length, 0), 'real coveredLength proof');
      }
      // Shared slice helper: body/comment共用.
      assert.deepEqual(sliceTextForChunk('abc', 2), ['ab', 'c']);
      assert.deepEqual(sliceTextForChunk('', 8000), []);
    }

    // T1: body 8001/15000 enters chunked path with full AI coverage.
    for (const [label, bodyLen, minChunks] of [['8001', 8001, 2], ['15000', 15000, 2]] as const) {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `pocketguard-h-t1-${label}-`));
      try {
        const body = 'x'.repeat(bodyLen);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: `t1 ${label} ok`, suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't1', body } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't1', body } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', `T1 body ${label} chunked may APPROVE`);
          assert.equal(reviewed.commentsComplete, true, `T1 body ${label} fully covered`);
          assert.ok((reviewed.chunkCount ?? 0) >= minChunks, `T1 body ${label} splits into >=${minChunks} chunks`);
          assert.ok(counter.count >= minChunks, `T1 body ${label} ran per-chunk triage`);
          assert.ok(validateIssueChunkReports(reviewed.chunks), `T1 body ${label} reports validate`);
          assert.ok(verifyIssueChunkCoverage(reviewed.chunks ?? [], { title: 't1', body, comments: [] }), `T1 body ${label} no overlap/no omission`);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
    // T1 fetchComplete=false stays fail-closed with zero AI even when chunkable.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-t1-fetchfail-'));
      try {
        const body = 'x'.repeat(15000);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'should not run', suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't1', body } }),
                listComments: async () => { throw new Error('transport down'); },
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't1', body } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'T1 fetch failure stays INCONCLUSIVE');
          assert.equal(counter.count, 0, 'T1 fetch failure makes zero AI calls');
          assert.equal(reviewed.chunks, undefined, 'T1 fetch failure stores no chunks');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T2: single comment 2001 enters chunk; 9001 fine-cuts into 2 segments each <=8000.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-t2-2001-'));
      try {
        const commentBody = 'c'.repeat(2001);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 't2 2001 ok', suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't2', body: 'short' } }),
                listComments: async () => ({ data: [{ id: 1, body: commentBody, user: { login: 'human', type: 'User' } }] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't2', body: 'short' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'T2 single 2001 comment chunked may APPROVE');
          assert.ok((reviewed.chunkCount ?? 0) >= 1, 'T2 single 2001 enters chunked path');
          assert.ok(counter.count >= 1, 'T2 single 2001 ran AI with full coverage');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
    {
      // Builder-level fine cut: 0 body + single 9001 comment => 2 segments each <=8000.
      const single = 'c'.repeat(9001);
      const chunks = buildIssueChunks('', single, []);
      // Note: buildIssueChunks(title, body, comments); comment-only uses comments array.
      const commentChunks = buildIssueChunks('t', '', [`human：${single}`]);
      assert.equal(commentChunks.length, 2, 'T2 9001 comment-only splits into 2 segments');
      for (const chunk of commentChunks) {
        assert.ok(validateIssueChunk(chunk), 'T2 each fine-cut segment validates');
        for (const c of chunk.comments) {
          assert.ok(c.length <= MAX_ISSUE_CHUNK_LENGTH, 'T2 each comment slice <=8000');
        }
        const joined = chunk.body.length + (chunk.comments.length > 0 ? (chunk.body.length > 0 ? 2 : 0) + chunk.comments.join('\n\n').length : 0);
        assert.ok(joined <= MAX_ISSUE_CHUNK_LENGTH, 'T2 per-segment budget holds');
      }
      assert.ok(verifyIssueChunkCoverage(commentChunks, { title: 't', body: '', comments: [`human：${single}`] }), 'T2 9001 coverage no overlap/no omission');
      void chunks;
      // End-to-end: 0 body + 9001 comment triages with full coverage.
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-t2-9001-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 't2 9001 ok', suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't2', body: '' } }),
                listComments: async () => ({ data: [{ id: 2, body: 'c'.repeat(9001), user: { login: 'human', type: 'User' } }] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't2', body: '' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'T2 0-body + 9001 comment may APPROVE');
          assert.ok((reviewed.chunkCount ?? 0) >= 2, 'T2 9001 end-to-end splits into >=2');
          assert.ok(counter.count >= 2, 'T2 9001 ran per-segment triage');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T3: multi-comment total 20001+ enters chunked path with full coverage.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-t3-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 't3 ok', suggestedLabels: [] }]);
        try {
          const many = Array.from({ length: 11 }, (_, i) => ({
            id: 100 + i,
            body: 'd'.repeat(2000),
            user: { login: 'human', type: 'User' },
          }));
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't3', body: 'short' } }),
                listComments: async () => ({ data: many }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't3', body: 'short' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'T3 multi-comment 20001+ may APPROVE');
          assert.ok((reviewed.chunkCount ?? 0) >= 3, 'T3 splits multi-comment total into >=3');
          assert.ok(counter.count >= 3, 'T3 ran per-chunk triage over all comments');
          assert.ok(validateIssueChunkReports(reviewed.chunks), 'T3 reports validate');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T4: 0 body + long comment enters chunked path.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-t4-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 't4 ok', suggestedLabels: [] }]);
        try {
          const longComment = 'e'.repeat(5000);
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't4', body: '' } }),
                listComments: async () => ({ data: [{ id: 3, body: longComment, user: { login: 'human', type: 'User' } }] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't4', body: '' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'T4 0-body + long comment may APPROVE');
          assert.ok((reviewed.chunkCount ?? 0) >= 1, 'T4 enters chunked path');
          assert.ok(counter.count >= 1, 'T4 ran AI with full coverage');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T5: boundaries — body 8000 single-turn vs 8001 chunked; total 20000 single-turn vs 20001 chunked.
    {
      // Body 8000 stays single-turn (no chunks field).
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-t5-8000-'));
      try {
        const body = 'x'.repeat(8000);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 't5 8000 ok', suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't5', body } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't5', body } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.chunks, undefined, 'T5 body 8000 stays single-turn');
          assert.equal(counter.count, 1, 'T5 body 8000 runs one triage turn');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
    {
      // Total exactly 20000 stays single-turn; 20001 chunks.
      const makeComments = (lastBodyLen: number): Array<{ id: number; body: string; user: { login: string; type: string } }> => {
        const list = Array.from({ length: 9 }, (_, i) => ({
          id: 200 + i,
          body: 'c'.repeat(2000),
          user: { login: 'human', type: 'User' },
        }));
        list.push({ id: 299, body: 'c'.repeat(lastBodyLen), user: { login: 'human', type: 'User' } });
        return list;
      };
      // 9 * (6+2000) + (6+1939) = 18054 + 1945 = 19999 comment chars; + title 1 = 20000 total.
      for (const [label, lastLen, expectChunked] of [['20000', 1939, false], ['20001', 1940, true]] as const) {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `pocketguard-h-t5-${label}-`));
        try {
          const artifactPath = path.join(tempDir, 'review-output.json');
          const counter = { count: 0 };
          const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: `t5 ${label}`, suggestedLabels: [] }]);
          try {
            const many = makeComments(lastLen);
            const client = {
              rest: {
                users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
                issues: {
                  get: async () => ({ data: { number: 7, title: 't', body: '' } }),
                  listComments: async () => ({ data: many }),
                },
              },
            } as unknown as NonNullable<RunnerContext['githubClient']>;
            const reviewed = await runIssueReviewMode({
              event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: '' } },
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
              githubClient: client,
              writeStdout: () => undefined,
            });
            if (expectChunked) {
              assert.ok(reviewed.chunks && reviewed.chunks.length >= 2, `T5 total ${label} chunks`);
              assert.ok(counter.count >= 2, `T5 total ${label} ran per-chunk triage`);
            } else {
              assert.equal(reviewed.chunks, undefined, `T5 total ${label} stays single-turn`);
              assert.equal(counter.count, 1, `T5 total ${label} runs one turn`);
            }
          } finally {
            restore();
          }
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
    }

    // T6: source coverage — no overlap, no omission across title/body/comments.
    {
      const title = 't6-title';
      const body = 'b'.repeat(9000);
      const comments = [`human：${'c'.repeat(3000)}`, `human：${'d'.repeat(9001)}`, `human：short`];
      const chunks = buildIssueChunks(title, body, comments);
      assert.ok(validateIssueChunks(chunks, { title, body, comments }), 'T6 full coverage validates');
      assert.ok(verifyIssueChunkCoverage(chunks, { title, body, comments }), 'T6 helper confirms coverage');
      // No duplication: concatenated bodies equal full body exactly once.
      assert.equal(chunks.map((c) => c.body).join(''), body, 'T6 body covered exactly once');
      const flatLen = chunks.flatMap((c) => c.comments).reduce((s, c) => s + c.length, 0);
      assert.equal(flatLen, comments.reduce((s, c) => s + c.length, 0), 'T6 comments covered exactly once');
      // No gaps: offsets chain continuously from 0.
      assert.equal(chunks[0].start, 0, 'T6 starts at title');
      for (let i = 1; i < chunks.length; i += 1) {
        assert.equal(chunks[i].start, chunks[i - 1].end, 'T6 no gaps');
        assert.ok(chunks[i].start < chunks[i].end, 'T6 positive span');
      }
      // Tampered coverage is rejected: drop a chunk => invalid.
      assert.equal(validateIssueChunks(chunks.slice(1), { title, body, comments }), undefined, 'T6 omission rejected');
      // Overlapping span is rejected.
      const overlapped = chunks.map((c) => ({ ...c }));
      if (overlapped.length >= 2) {
        overlapped[1] = { ...overlapped[1], start: overlapped[0].start };
        assert.equal(validateIssueChunks(overlapped), undefined, 'T6 overlap rejected');
      }
    }

    // T7: artifact carries readable per-segment verdicts (JSON + Markdown reports).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-t7-'));
      try {
        const body = 'x'.repeat(8001);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'seg0 fine', suggestedLabels: [] },
          { verdict: 'NEEDS_CHANGES', summary: 'seg1 blocking', suggestedLabels: [] },
        ]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't7', body } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't7', body } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.ok(reviewed.chunks && reviewed.chunks.length >= 2, 'T7 splits for per-segment verdicts');
          const verdicts = (reviewed.chunks ?? []).map((c) => c.verdict);
          assert.ok(verdicts.includes('APPROVE') && verdicts.includes('NEEDS_CHANGES'), 'T7 per-segment verdicts differ and are readable');
          const persisted = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
          const persistedChunks = persisted.chunks as Array<Record<string, unknown>>;
          assert.ok(Array.isArray(persistedChunks) && persistedChunks.length >= 2, 'T7 artifact chunks readable');
          for (const seg of persistedChunks) {
            assert.ok('verdict' in seg && 'summary' in seg && 'labels' in seg, 'T7 artifact segment carries verdict/summary/labels');
            assert.ok(!('body' in seg) && !('comments' in seg), 'T7 artifact segment omits full text');
          }
          const reportJson = JSON.parse(fs.readFileSync(path.join(tempDir, 'review-report.json'), 'utf8')) as Record<string, unknown>;
          const segments = (reportJson.segments ?? reportJson.chunks) as Array<Record<string, unknown>>;
          assert.ok(Array.isArray(segments) && segments.length >= 2, 'T7 report carries per-segment results');
          for (const seg of segments) {
            assert.ok('verdict' in seg, 'T7 report segment verdict readable');
          }
          const reportMd = fs.readFileSync(path.join(tempDir, 'review-report.md'), 'utf8');
          assert.ok(reportMd.includes('分段結果') || reportMd.includes('APPROVE') || reportMd.includes('NEEDS_CHANGES'), 'T7 markdown shows per-segment results');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T8: 100+ findings sticky stays bounded with an omission note.
    {
      const manyFindings = Array.from({ length: 100 }, (_, i) => ({
        severity: 'WARN' as const,
        file: `app/src/main/java/demo/File${i}.kt`,
        line: i + 1,
        issue: `finding ${i} — ${'x'.repeat(20)}`,
        suggestion: `fix ${i}`,
      }));
      const prOutput = {
        verdict: 'NEEDS_CHANGES' as const,
        pullRequestNumber: 41,
        baseSha: 'a'.repeat(40),
        headSha: 'b'.repeat(40),
        headRepository: REPO,
        roles: [
          { role: 'chief' as const, modelUsed: 'm', verdict: 'NEEDS_CHANGES' as const, findings: manyFindings.slice(0, 40) },
          { role: 'android_sec' as const, modelUsed: 'm', verdict: 'APPROVE' as const, findings: manyFindings.slice(40, 70) },
          { role: 'android_code' as const, modelUsed: 'm', verdict: 'APPROVE' as const, findings: manyFindings.slice(70) },
        ],
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 10 },
        deterministicViolations: [],
        areaLabels: [],
        changedFiles: [],
        changedFilesComplete: true,
        suggestedLabels: [],
      };
      const sticky = reviewComment(prOutput, [], '報告：不可用（審查報告未能產生或上傳失敗，請見 Actions 執行紀錄）。');
      assert.ok(sticky.includes('省略'), 'T8 PR sticky carries omission note for 100+ findings');
      assert.ok(sticky.length < 60000, `T8 PR sticky bounded (got ${sticky.length})`);
      // T8 extended (Phase 3 P2 #3): whole-sticky hard budget plus tail counts.
      assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `T8 PR sticky within total cap ${MAX_STICKY_TOTAL_LENGTH} (got ${sticky.length})`);
      assert.ok(sticky.length <= GITHUB_COMMENT_HARD_LIMIT, 'T8 PR sticky within GitHub hard limit');
      assert.ok(sticky.includes('共100'), 'T8 tail carries total finding count');
      assert.ok(sticky.includes('artifact'), 'T8 tail guides to the report artifact');
      // Issue sticky: long summary plus many segments stays bounded with notes.
      const issueOutput = {
        verdict: 'APPROVE' as const,
        issueNumber: 7,
        title: 't8',
        tags: [],
        summary: 's'.repeat(5000),
        suggestedLabels: [],
        fingerprint: 'a'.repeat(64),
        commentsComplete: true,
        chunks: Array.from({ length: 15 }, (_, i) => ({
          index: i,
          total: 15,
          start: i * 100,
          end: (i + 1) * 100,
          complete: true,
          coveredLength: 90,
          verdict: 'APPROVE' as const,
          summary: `seg ${i}`,
          labels: [],
          segments: [],
          bodySha: 'b'.repeat(64),
          bodyRanges: [],
        })),
        chunkCoverageComplete: true,
        chunkCount: 15,
      };
      const issueSticky = issueReviewComment(issueOutput, [], '報告：不可用（審查報告未能產生或上傳失敗，請見 Actions 執行紀錄）。');
      assert.ok(issueSticky.includes('分段：'), 'T8 issue sticky carries chunk line with boundaries');
      assert.ok(issueSticky.includes('省略'), 'T8 issue sticky carries omission note');
      assert.ok(truncateStickyText('x'.repeat(100), 10).includes('省略'), 'T8 truncate helper notes omission');
      assert.ok(issueSticky.length < 20000, `T8 issue sticky bounded (got ${issueSticky.length})`);
    }

    // Phase 3 (P2 #3): whole-sticky hard cap. Single sticky only (never a
    // second comment); overflow stays in the artifact with total/shown/omitted
    // plus artifact guidance in the tail.
    {
      assert.equal(MAX_STICKY_TOTAL_LENGTH, 50000, 'P3 total budget leaves 65536 headroom');
      assert.equal(GITHUB_COMMENT_HARD_LIMIT, 65536, 'P3 fail-safe matches the GitHub limit');
      const prShape = (
        roles: Array<{ role: 'chief' | 'android_sec' | 'android_code'; findings: Array<{ severity: 'BLOCK' | 'WARN' | 'SUGGESTION'; file?: string; line?: number; issue: string; suggestion?: string }> }>,
        violations: Array<{ ruleId: string; severity: 'BLOCK' | 'WARN'; category: 'security' | 'reliability'; message: string }> = [],
        coverage: { complete: boolean; omittedFiles: string[]; truncatedFiles: string[]; originalLength: number } = { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 10 },
      ): Parameters<typeof reviewComment>[0] => ({
        verdict: 'NEEDS_CHANGES' as const,
        pullRequestNumber: 41,
        baseSha: 'a'.repeat(40),
        headSha: 'b'.repeat(40),
        headRepository: REPO,
        roles: roles.map((r) => ({ role: r.role, modelUsed: 'm', verdict: 'NEEDS_CHANGES' as const, findings: r.findings })),
        coverage,
        deterministicViolations: violations,
        areaLabels: [],
        changedFiles: [],
        changedFilesComplete: true,
        suggestedLabels: [],
      });

      // P3-1: 3x20 long findings (each issue+suggestion 2000 chars) stay safe.
      {
        const mkLong = (tag: string, i: number): { severity: 'WARN'; file: string; line: number; issue: string; suggestion: string } => ({
          severity: 'WARN' as const,
          file: `app/src/main/java/demo/P3Long${tag}${i}.kt`,
          line: i + 1,
          issue: `P3LONG-${tag}-${i} ${'x'.repeat(2000)}`,
          suggestion: `fix-${tag}-${i} ${'y'.repeat(2000)}`,
        });
        const roles = (['chief', 'android_sec', 'android_code'] as const).map((role) => ({
          role,
          findings: Array.from({ length: 20 }, (_, i) => mkLong(role, i)),
        }));
        const sticky = reviewComment(prShape(roles), [], '報告行');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-1 60 long findings within total cap (got ${sticky.length})`);
        assert.ok(sticky.length <= GITHUB_COMMENT_HARD_LIMIT, 'P3-1 within GitHub hard limit');
        assert.ok(sticky.includes('省略'), 'P3-1 carries omission note');
        assert.ok(sticky.includes('共60'), 'P3-1 tail carries total finding count');
        assert.ok(sticky.includes('artifact'), 'P3-1 tail guides to artifact');
        // Per-item cap: no 2000-char raw run survives verbatim.
        assert.ok(!sticky.includes('x'.repeat(2000)), 'P3-1 single-item cap truncates long issues');
        assert.ok(!sticky.includes('y'.repeat(2000)), 'P3-1 single-item cap truncates long suggestions');
      }

      // P3-2: 100+ long violations stay safe via the 20-pool plus budget fill.
      {
        const violations = Array.from({ length: 120 }, (_, i) => ({
          ruleId: `P3RULE-${i}`,
          severity: (i % 9 === 0 ? 'BLOCK' : 'WARN') as 'BLOCK' | 'WARN',
          category: 'security' as const,
          message: `P3V-${i} ${'z'.repeat(2000)}`,
        }));
        const sticky = reviewComment(prShape([{ role: 'chief', findings: [] }], violations), [], '報告行');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-2 120 long violations within total cap (got ${sticky.length})`);
        assert.ok(sticky.includes('省略'), 'P3-2 carries omission note');
        assert.ok(sticky.includes('共120'), 'P3-2 tail carries total violation count');
        assert.ok(!sticky.includes('z'.repeat(2000)), 'P3-2 single-item cap truncates long messages');
      }

      // P3-3: escape-dense text is truncated->escaped and accounted post-escape.
      {
        const dense = '`*_{}[]()#+-.!|<>\\'.repeat(200);
        const roles = [{ role: 'chief' as const, findings: [{ severity: 'BLOCK' as const, file: 'a*b_c[d](e)#f.kt', line: 1, issue: dense, suggestion: dense }] }];
        const sticky = reviewComment(prShape(roles), [], '報告行');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-3 escape-dense within total cap (got ${sticky.length})`);
        // Truncate->escape helper: raw cut first, escaped length accounted.
        const single = stickyField(dense, MAX_STICKY_ITEM_LENGTH);
        assert.ok(single.includes('省略'), 'P3-3 helper notes omission for dense input');
        assert.ok(single.includes('\\*'), 'P3-3 helper escapes markdown after truncation');
        assert.ok(!sticky.includes(dense), 'P3-3 raw dense run never lands verbatim');
      }

      // P3-4: 500+ char filenames stay safe in findings plus both file lists.
      {
        const longName = `${'d'.repeat(300)}/${'e'.repeat(300)}.kt`;
        assert.ok(longName.length > 500, 'P3-4 fixture really exceeds 500 chars');
        const roles = [{ role: 'chief' as const, findings: [{ severity: 'WARN' as const, file: longName, line: 7, issue: 'name check', suggestion: 'rename' }] }];
        const coverage = {
          complete: false,
          omittedFiles: Array.from({ length: 30 }, (_, i) => `${'o'.repeat(250)}/omitted-${i}.kt`),
          truncatedFiles: Array.from({ length: 25 }, (_, i) => `${'t'.repeat(250)}/truncated-${i}.kt`),
          originalLength: 999,
        };
        const sticky = reviewComment(prShape(roles, [], coverage), [], '報告行');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-4 long filenames within total cap (got ${sticky.length})`);
        assert.ok(!sticky.includes(longName), 'P3-4 full 500+ filename never lands verbatim');
        assert.ok(sticky.includes('共30'), 'P3-4 omitted list carries total count');
        assert.ok(sticky.includes('共25'), 'P3-4 truncated list carries total count');
        assert.ok(sticky.includes(`示${MAX_STICKY_FILE_LIST_SHOWN}`), 'P3-4 file lists carry shown count');
        assert.ok(sticky.includes('省略'), 'P3-4 file lists carry omission note');
      }

      // P3-5: BLOCK all kept, SUGGESTION dropped first, severity order held.
      {
        const blocks = Array.from({ length: 5 }, (_, i) => ({
          severity: 'BLOCK' as const,
          file: `keep/Block${i}.kt`,
          line: i + 1,
          issue: `BLOCKKEEP${i} ${'x'.repeat(2000)}`,
          suggestion: `keep fix ${i}`,
        }));
        const sugs = Array.from({ length: 80 }, (_, i) => ({
          severity: 'SUGGESTION' as const,
          file: `drop/Sug${i}.kt`,
          line: i + 1,
          issue: `SUGDROP${i} ${'q'.repeat(2000)}`,
          suggestion: `drop fix ${i} ${'w'.repeat(2000)}`,
        }));
        // Interleave roles so input order alone would not keep BLOCKs first.
        const roles = [
          { role: 'chief' as const, findings: [...sugs.slice(0, 30), ...blocks.slice(0, 2)] },
          { role: 'android_sec' as const, findings: [...sugs.slice(30, 60), ...blocks.slice(2, 4)] },
          { role: 'android_code' as const, findings: [...sugs.slice(60), ...blocks.slice(4)] },
        ];
        const sticky = reviewComment(prShape(roles), [], '報告行');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-5 mixed severities within total cap (got ${sticky.length})`);
        for (let i = 0; i < 5; i += 1) {
          assert.ok(sticky.includes(`BLOCKKEEP${i}`), `P3-5 keeps BLOCK ${i}`);
        }
        const keptSugs = sugs.filter((s) => sticky.includes(s.issue.slice(0, 12))).length;
        assert.ok(keptSugs < sugs.length, `P3-5 drops some SUGGESTION first (kept ${keptSugs}/${sugs.length})`);
        const firstBlock = sticky.indexOf('BLOCKKEEP');
        const firstSug = sticky.indexOf('SUGDROP');
        assert.ok(firstBlock >= 0 && firstSug >= 0 && firstBlock < firstSug, 'P3-5 BLOCK precedes SUGGESTION');
      }

      // P3-6: pre-send fail-safe never exceeds 65536 and keeps short bodies.
      {
        const huge = `<!-- PocketGuard-review -->\n${'v'.repeat(70000)}`;
        const capped = enforceGithubCommentLimit(huge);
        assert.equal(capped.length, GITHUB_COMMENT_HARD_LIMIT, 'P3-6 fail-safe caps at exactly 65536');
        assert.ok(capped.includes('artifact'), 'P3-6 fail-safe notes the artifact');
        const short = '<!-- PocketGuard-review -->\nshort';
        assert.equal(enforceGithubCommentLimit(short), short, 'P3-6 short body passes through');
      }
    }

    // Phase D (P1 #4) issue double-gate: valid APPROVE output without
    // content-validated reports must fall back to INCONCLUSIVE with no fake
    // link (same sticky, needs-decision), never APPROVE+unavailable.
    {
      const makeValidIssueArtifact = async (tempDir: string): Promise<{ artifactPath: string; title: string; body: string }> => {
        const title = 'phase-d-issue';
        const body = 'phase-d body';
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'd ok', suggestedLabels: [] }]);
        try {
          const client = issueClient({ comments: [], created: 0, updated: 0 }, { getTitle: title, getBody: body });
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title, body } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'D setup stays APPROVE');
          assert.equal(areReviewReportsAvailable({ POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv), true, 'D setup reports content-valid');
        } finally {
          restore();
        }
        return { artifactPath, title, body };
      };
      const publishIssueAndReadSticky = async (artifactPath: string, title: string, body: string): Promise<{ sticky: string; created: number; updated: number; labels: string[][] }> => {
        const state = {
          comments: [{ id: 71, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
          labels: [] as string[][],
        };
        const client = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title, body } }),
              listComments: async () => ({ data: state.comments }),
              createComment: async () => { state.created += 1; return {}; },
              updateComment: async (params: { comment_id: number; body: string }) => {
                state.updated += 1;
                const c = state.comments.find((x) => x.id === params.comment_id);
                if (c) c.body = params.body;
                return {};
              },
              addLabels: async (params: { labels: string[] }) => { state.labels.push(params.labels); return {}; },
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title, body } },
          env: {
            GITHUB_EVENT_NAME: 'issues',
            GITHUB_REPOSITORY: REPO,
            GITHUB_SERVER_URL: 'https://github.com',
            GITHUB_RUN_ID: '424242',
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as unknown as NodeJS.ProcessEnv,
          githubClient: client,
        });
        return { sticky: state.comments[0].body, created: state.created, updated: state.updated, labels: state.labels };
      };
      // D-1: missing markdown report → INCONCLUSIVE, unavailable, no fake link.
      {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-d-issue-missing-md-'));
        try {
          const { artifactPath, title, body } = await makeValidIssueArtifact(tempDir);
          fs.rmSync(path.join(tempDir, 'review-report.md'), { force: true });
          assert.equal(areReviewReportsAvailable({ POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv), false, 'D-1 missing md is unavailable');
          const { sticky, created, updated } = await publishIssueAndReadSticky(artifactPath, title, body);
          assert.equal(created, 0, 'D-1 same sticky');
          assert.equal(updated, 1);
          assert.ok(sticky.includes('判定：INCONCLUSIVE'), 'D-1 falls back to INCONCLUSIVE');
          assert.ok(!sticky.includes('判定：APPROVE'), 'D-1 never APPROVE with unavailable');
          assert.ok(sticky.includes('報告：不可用'), 'D-1 shows unavailable');
          assert.ok(!sticky.includes('actions/runs/'), 'D-1 gives no fake link');
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
      // D-2: missing JSON report → same fallback (partial upload).
      {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-d-issue-missing-json-'));
        try {
          const { artifactPath, title, body } = await makeValidIssueArtifact(tempDir);
          fs.rmSync(path.join(tempDir, 'review-report.json'), { force: true });
          assert.equal(areReviewReportsAvailable({ POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv), false, 'D-2 missing json is unavailable');
          const { sticky } = await publishIssueAndReadSticky(artifactPath, title, body);
          assert.ok(sticky.includes('判定：INCONCLUSIVE'), 'D-2 INCONCLUSIVE');
          assert.ok(!sticky.includes('判定：APPROVE'));
          assert.ok(sticky.includes('報告：不可用'));
          assert.ok(!sticky.includes('actions/runs/'));
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
      // D-3: download failure (both reports gone, output present, job success).
      {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-d-issue-download-'));
        try {
          const { artifactPath, title, body } = await makeValidIssueArtifact(tempDir);
          fs.rmSync(path.join(tempDir, 'review-report.json'), { force: true });
          fs.rmSync(path.join(tempDir, 'review-report.md'), { force: true });
          assert.equal(areReviewReportsAvailable({ POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv), false, 'D-3 download loss is unavailable');
          const { sticky } = await publishIssueAndReadSticky(artifactPath, title, body);
          assert.ok(sticky.includes('判定：INCONCLUSIVE'), 'D-3 download failure stays INCONCLUSIVE');
          assert.ok(sticky.includes('報告：不可用'));
          assert.ok(!sticky.includes('actions/runs/'));
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
      // D-4: output↔report inconsistency (verdict / fingerprint / time).
      for (const variant of ['verdict', 'fingerprint', 'time'] as const) {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `pocketguard-d-issue-tamper-${variant}-`));
        try {
          const { artifactPath, title, body } = await makeValidIssueArtifact(tempDir);
          const reportPath = path.join(tempDir, 'review-report.json');
          const report = JSON.parse(fs.readFileSync(reportPath, 'utf8')) as Record<string, unknown>;
          if (variant === 'verdict') report.verdict = report.verdict === 'APPROVE' ? 'NEEDS_CHANGES' : 'APPROVE';
          if (variant === 'fingerprint') report.fingerprint = `${'0'.repeat(63)}1`;
          if (variant === 'time') report.time = 'not-a-time';
          fs.writeFileSync(reportPath, JSON.stringify(report, null, 2));
          assert.equal(areReviewReportsAvailable({ POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv), false, `D-4 tampered ${variant} is unavailable`);
          const { sticky } = await publishIssueAndReadSticky(artifactPath, title, body);
          assert.ok(sticky.includes('判定：INCONCLUSIVE'), `D-4 tampered ${variant} stays INCONCLUSIVE`);
          assert.ok(!sticky.includes('判定：APPROVE'));
          assert.ok(sticky.includes('報告：不可用'));
          assert.ok(!sticky.includes('actions/runs/'));
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
      // D-5: write failure injection — reports unwritable downgrades APPROVE
      // (report path collides with a directory → EISDIR; no fs mock needed).
      {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-d-issue-writefail-'));
        try {
          const artifactPath = path.join(tempDir, 'review-output.json');
          fs.rmSync(path.join(tempDir, 'review-report.json'), { force: true, recursive: true });
          fs.mkdirSync(path.join(tempDir, 'review-report.json'), { recursive: true });
          const counter = { count: 0 };
          const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'write-fail ok', suggestedLabels: [] }]);
          let reviewed!: Awaited<ReturnType<typeof runIssueReviewMode>>;
          try {
            const client = issueClient({ comments: [], created: 0, updated: 0 }, { getTitle: 'w', getBody: 'b' });
            reviewed = await runIssueReviewMode({
              event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'w', body: 'b' } },
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
              githubClient: client,
              writeStdout: () => undefined,
            });
          } finally {
            restore();
          }
          assert.notEqual(reviewed.verdict, 'APPROVE', 'D-5 write failure never APPROVE');
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'D-5 write failure downgrades to INCONCLUSIVE');
          const persisted = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as { verdict: string };
          assert.notEqual(persisted.verdict, 'APPROVE', 'D-5 persisted file never APPROVE');
          assert.equal(areReviewReportsAvailable({ POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv), false, 'D-5 reports unavailable after injection');
          // Direct writeReviewReports status carries the identifiable error
          // (same directory collision, still EISDIR).
          {
            const failDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-d-issue-direct-'));
            try {
              const failPath = path.join(failDir, 'review-output.json');
              fs.writeFileSync(failPath, '{}');
              fs.rmSync(path.join(failDir, 'review-report.json'), { force: true, recursive: true });
              fs.mkdirSync(path.join(failDir, 'review-report.json'), { recursive: true });
              const status = writeReviewReports(
                { verdict: 'APPROVE', issueNumber: 7, title: 'w', tags: [], summary: 's', suggestedLabels: [], fingerprint: 'a'.repeat(64), commentsComplete: true } as never,
                { env: { POCKETGUARD_OUTPUT: failPath } as NodeJS.ProcessEnv },
              );
              assert.equal(status.ok, false, 'D-5 direct injection reports failure');
              if (!status.ok) assert.ok(status.reason.includes(REVIEW_REPORT_WRITE_ERROR), 'D-5 direct failure carries identifiable error');
            } finally {
              fs.rmSync(failDir, { recursive: true, force: true });
            }
          }
          // Identifiable error string itself is stable.
          assert.ok(REVIEW_REPORT_WRITE_ERROR.includes('failed to write review reports'));
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
    }

    // P2 #4: per-slice RAW binding (segments + bodySha/bodyRanges). Generation
    // and publish compare content/order against the RAW snapshot; reports
    // carry only hash/ranges/verdict and keep redaction.
    {
      // Positive: builder emits segments; full+RAW validation passes.
      const title = 'p2-title';
      const body = 'p2-body';
      const comments = ['human：AAA', 'human：BBB'];
      const ids = [11, 22];
      const full = { title, body, comments, commentIds: ids, rawComments: comments, rawBody: body };
      const chunks = buildIssueChunks(title, body, comments, { rawBody: body, rawComments: comments, rawCommentIds: ids });
      assert.ok(chunks.length >= 1, 'P2 builder emits chunks');
      for (const c of chunks) {
        assert.ok(validateIssueChunk(c), 'P2 each chunk validates');
        assert.ok(Array.isArray(c.segments) && c.segments.length === 2, 'P2 chunk carries per-slice segments');
        assert.ok(/^[0-9a-f]{64}$/.test(c.bodySha), 'P2 bodySha is hex');
        assert.ok(Array.isArray(c.bodyRanges), 'P2 bodyRanges present');
        for (const s of c.segments) {
          assert.ok(s.commentId === 11 || s.commentId === 22, 'P2 segment carries comment ID');
          assert.ok(s.commentIndex === 0 || s.commentIndex === 1, 'P2 segment carries comment index');
          assert.ok(s.sliceEnd > s.sliceStart && s.sliceEnd - s.sliceStart <= MAX_ISSUE_CHUNK_LENGTH, 'P2 slice range bounded');
          assert.ok(/^[0-9a-f]{64}$/.test(s.sha256), 'P2 slice sha is hex');
          assert.ok(s.end >= s.start, 'P2 global range ordered');
        }
      }
      assert.ok(validateIssueChunks(chunks, full), 'P2 builder output validates with RAW snapshot');
      assert.equal(verifyIssueChunkCoverage(chunks, full), true, 'P2 coverage helper confirms RAW binding');
      // Reports validate against live RAW too and carry no plaintext.
      const fakeReports = chunks.map((c) => ({
        index: c.index,
        total: c.total,
        start: c.start,
        end: c.end,
        complete: true,
        coveredLength: c.coveredLength,
        verdict: 'APPROVE' as const,
        summary: 'ok',
        labels: [] as string[],
        segments: c.segments.map((s) => ({ ...s })),
        bodySha: c.bodySha,
        bodyRanges: c.bodyRanges.map((r) => ({ ...r })),
      }));
      assert.ok(validateIssueChunkReports(fakeReports, full), 'P2 reports validate with RAW snapshot');
      assert.equal(verifyIssueChunkCoverage(fakeReports, full), true, 'P2 report coverage confirms RAW binding');
      assert.ok(validateIssueOutput({
        verdict: 'APPROVE', issueNumber: 7, title, tags: [], summary: 'ok',
        suggestedLabels: [], fingerprint: 'a'.repeat(64), commentsComplete: true,
        chunks: fakeReports, chunkCoverageComplete: true, chunkCount: fakeReports.length,
      }), 'P2 output with segments validates (segments schema)');
      // Only hash/ranges/verdict: raw secret never persists in bindings.
      {
        const secretRaw = ['human：api_key supersecret123'];
        const secretRed = ['human：api_key [REDACTED CREDENTIAL]'];
        const sc = buildIssueChunks(title, body, secretRed, { rawBody: body, rawComments: secretRaw, rawCommentIds: [31] });
        assert.ok(validateIssueChunks(sc, { title, body, comments: secretRed, commentIds: [31], rawComments: secretRaw, rawBody: body }), 'P2 secret binding validates');
        const blob = JSON.stringify(sc);
        assert.ok(!blob.includes('supersecret123'), 'P2 bindings carry no raw secret');
        const segSha = sc.flatMap((c) => c.segments)[0].sha256;
        assert.equal(segSha, rawSliceHash(secretRaw[0]), 'P2 slice sha binds RAW (not the redacted mask)');
      }

      // Negative: same-length char swap (lengths add up, content differs).
      {
        const swappedRaw = ['human：AAB', 'human：BBB'];
        const swappedFull = { ...full, rawComments: swappedRaw };
        assert.equal(validateIssueChunks(chunks, swappedFull), undefined, 'P2 same-length swap rejected by validate');
        assert.equal(verifyIssueChunkCoverage(chunks, swappedFull), false, 'P2 same-length swap rejected by verify');
        assert.equal(validateIssueChunkReports(fakeReports, swappedFull), undefined, 'P2 same-length swap rejected by report validate');
        assert.equal(verifyIssueChunkCoverage(fakeReports, swappedFull), false, 'P2 same-length swap rejected by report verify');
      }

      // Negative: swapped comment order (ids + order mismatch).
      {
        const orderFull = {
          title, body,
          comments: [comments[1], comments[0]],
          commentIds: [ids[1], ids[0]],
          rawComments: [comments[1], comments[0]],
          rawBody: body,
        };
        assert.equal(validateIssueChunks(chunks, orderFull), undefined, 'P2 comment reorder rejected by validate');
        assert.equal(verifyIssueChunkCoverage(chunks, orderFull), false, 'P2 comment reorder rejected by verify');
        assert.equal(validateIssueChunkReports(fakeReports, orderFull), undefined, 'P2 comment reorder rejected by report validate');
      }

      // Negative: duplicated slice (artifact tamper).
      {
        const dup = chunks.map((c) => ({
          ...c,
          comments: [...c.comments],
          segments: c.segments.map((s) => ({ ...s })),
          bodyRanges: c.bodyRanges.map((r) => ({ ...r })),
        }));
        dup[0].segments = [dup[0].segments[0], dup[0].segments[0], ...dup[0].segments.slice(1)];
        assert.equal(validateIssueChunks(dup, full), undefined, 'P2 duplicated slice rejected by validate');
        assert.equal(verifyIssueChunkCoverage(dup, full), false, 'P2 duplicated slice rejected by verify');
      }
    }

    // P2 #4 publish double gate: live same-length swap falls back to
    // INCONCLUSIVE on the same sticky (segment hashes + fingerprint gate).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-segswap-'));
      try {
        const bodyA = `${'x'.repeat(8000)}A`;
        const bodyB = `${'x'.repeat(8000)}B`;
        assert.equal(bodyA.length, bodyB.length, 'P2 swap keeps length');
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'p2 ok', suggestedLabels: [] }]);
        try {
          const reviewClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'p2', body: bodyA } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2', body: bodyA } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'P2 review may APPROVE before swap');
          assert.ok(reviewed.chunks && reviewed.chunks.length >= 2, 'P2 review is chunked');
          assert.ok(reviewed.chunks.every((c) => Array.isArray(c.segments) && typeof c.bodySha === 'string'), 'P2 artifact carries bindings');
        } finally {
          restore();
        }
        const state = {
          comments: [{ id: 51, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const publishClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 'p2', body: bodyB } }),
              listComments: async () => ({ data: state.comments }),
              createComment: async () => { state.created += 1; return {}; },
              updateComment: async (params: { comment_id: number; body: string }) => {
                state.updated += 1;
                const c = state.comments.find((x) => x.id === params.comment_id);
                if (c) c.body = params.body;
                return {};
              },
              addLabels: async () => ({}),
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2', body: bodyB } },
          env: {
            GITHUB_EVENT_NAME: 'issues',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: publishClient,
        });
        assert.equal(state.created, 0, 'P2 swap publish creates no second comment');
        assert.equal(state.updated, 1, 'P2 swap publish updates same sticky');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'P2 live swap falls back to INCONCLUSIVE');
        assert.ok(!state.comments[0].body.includes('判定：APPROVE'), 'P2 live swap never APPROVE');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P2 #4 segment gate isolates artifact tamper: live content unchanged
    // (fingerprint still matches) but a forged slice hash falls back to
    // INCONCLUSIVE on the same sticky.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-segtamper-'));
      try {
        const bodyC = `${'y'.repeat(8000)}C`;
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'p2 tamper ok', suggestedLabels: [] }]);
        try {
          const reviewClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'p2t', body: bodyC } }),
                listComments: async () => ({ data: [{ id: 61, body: 'human note', user: { login: 'human', type: 'User' } }] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2t', body: bodyC } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'P2 tamper setup may APPROVE');
          assert.ok(reviewed.chunks && reviewed.chunks.length >= 1, 'P2 tamper setup is chunked');
        } finally {
          restore();
        }
        // Forge one slice hash (keep valid hex + schema so only the live
        // binding mismatches, not the schema gate).
        const artifact = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
        const achunks = artifact.chunks as Array<Record<string, unknown>>;
        let forged = false;
        for (const ch of achunks) {
          const segs = ch.segments as Array<Record<string, unknown>>;
          if (Array.isArray(segs) && segs.length > 0) {
            const sha = String(segs[0].sha256);
            segs[0].sha256 = sha.slice(0, 63) + (sha[63] === '0' ? '1' : '0');
            forged = true;
            break;
          }
        }
        if (!forged) {
          const first = achunks[0] as Record<string, unknown>;
          const sha = String(first.bodySha);
          first.bodySha = sha.slice(0, 63) + (sha[63] === '0' ? '1' : '0');
        }
        fs.writeFileSync(artifactPath, JSON.stringify(artifact, null, 2));
        // Schema still holds (valid hex), so validateIssueOutput passes — the
        // publish live binding is what must catch the forgery.
        assert.ok(validateIssueOutput(JSON.parse(fs.readFileSync(artifactPath, 'utf8'))), 'P2 forged artifact keeps schema');
        const state = {
          comments: [{ id: 52, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const publishClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 'p2t', body: bodyC } }),
              listComments: async (params: { page: number }) => {
                void params;
                // First call is the publish fingerprint/segment re-read (live
                // human comment); sticky lookup reuses the same array since
                // the bot sticky is filtered from the RAW snapshot by author.
                return { data: [{ id: 61, body: 'human note', user: { login: 'human', type: 'User' } }, ...state.comments] };
              },
              createComment: async () => { state.created += 1; return {}; },
              updateComment: async (params: { comment_id: number; body: string }) => {
                state.updated += 1;
                const c = state.comments.find((x) => x.id === params.comment_id);
                if (c) c.body = params.body;
                return {};
              },
              addLabels: async () => ({}),
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2t', body: bodyC } },
          env: {
            GITHUB_EVENT_NAME: 'issues',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: publishClient,
        });
        assert.equal(state.created, 0, 'P2 forgery publish creates no second comment');
        assert.equal(state.updated, 1, 'P2 forgery publish updates same sticky');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'P2 forged slice falls back to INCONCLUSIVE');
        assert.ok(!state.comments[0].body.includes('判定：APPROVE'), 'P2 forged slice never APPROVE');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard phase2-h tests] All tests passed.');
}
