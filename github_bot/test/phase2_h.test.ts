import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  areReviewReportsAvailable,
  buildIssueChunks,
  formatReportLine,
  getReportRunUrl,
  issueContentFingerprint,
  issueReviewComment,
  reviewComment,
  runIssueReviewMode,
  runPublishMode,
  runClaimMode,
  sliceTextForChunk,
  truncateStickyText,
  validateIssueChunk,
  validateIssueChunkReport,
  validateIssueChunkReports,
  validateIssueChunks,
  verifyIssueChunkCoverage,
  writeReviewReports,
  REVIEW_REPORT_WRITE_ERROR,
  MAX_ISSUE_CHUNK_LENGTH,
  MAX_ISSUE_CHUNK_SUMMARY_LENGTH,
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
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard phase2-h tests] All tests passed.');
}
