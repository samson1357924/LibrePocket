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
  runIssueReviewMode,
  runPublishMode,
  runClaimMode,
  validateIssueChunk,
  validateIssueChunks,
  MAX_ISSUE_CHUNK_LENGTH,
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

    // H-3: 20001-char body chunking with full coverage may APPROVE.
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
          // Per-segment schema: start/end/complete + continuity.
          const validated = validateIssueChunks(reviewed.chunks);
          assert.ok(validated, 'chunks pass per-segment schema');
          for (const chunk of reviewed.chunks ?? []) {
            assert.ok(validateIssueChunk(chunk), 'each chunk validates');
            assert.ok(chunk.start < chunk.end, 'start<end recorded');
            assert.equal(chunk.complete, true, 'complete recorded');
          }
          assert.ok(counter.count >= 3, `per-chunk triage ran (got ${counter.count})`);
          // Fingerprint covers the full body (no tail collision).
          const expectedFp = issueContentFingerprint('long', longBody, []);
          // Note: runIssueReviewMode uses cleanForModel(title/body); plain
          // x-strings survive redaction unchanged, so the hash matches.
          assert.equal(reviewed.fingerprint, expectedFp, 'full fingerprint verified');
          assert.ok(MAX_ISSUE_CHUNK_LENGTH === 8000, 'chunk budget constant');
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

    // H-6: chunk builder unit (start/end/complete/per-segment, full coverage).
    {
      const chunks = buildIssueChunks('t', 'x'.repeat(20001), []);
      assert.ok(chunks.length >= 3, 'builder splits 20001 body');
      assert.ok(validateIssueChunks(chunks), 'builder output validates');
      const totalSpan = chunks[chunks.length - 1].end - chunks[0].start;
      assert.ok(totalSpan > 20001, 'offsets cover title+body');
      for (let i = 1; i < chunks.length; i += 1) {
        assert.equal(chunks[i].start, chunks[i - 1].end, 'no gaps between chunks');
      }
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard phase2-h tests] All tests passed.');
}
