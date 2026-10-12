import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  areReviewReportsAvailable,
  enforceGithubCommentLimit,
  formatReportLine,
  getReportRunUrl,
  issueContentFingerprint,
  issueReviewComment,
  isIssueCodeRefAllowed,
  reviewComment,
  runIssueReviewMode,
  runPublishMode,
  runClaimMode,
  resolveIssueCodeRef,
  stickyField,
  truncateStickyText,
  validateIssueOutput,
  writeReviewReports,
  REVIEW_REPORT_WRITE_ERROR,
  GITHUB_COMMENT_HARD_LIMIT,
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
    POCKETGUARD_MODEL_ARCHITECTURE: 'fake-architecture-model',
    POCKETGUARD_MODEL_ANDROID_SEC: 'fake-sec-model',
    POCKETGUARD_MODEL_ANDROID_CODE: 'fake-code-model',
    POCKETGUARD_MODEL_PROFILES: '{"fake-architecture-model":"chat","fake-sec-model":"chat","fake-code-model":"chat"}',
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

    // H-1b: issue AI suggesting a human-only label (gate:*) is discarded and
    // forces INCONCLUSIVE; allowlisted suggestions are kept.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-issue-gate-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'ok', suggestedLabels: ['bug', 'gate:live'] },
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
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'human-only AI label forces INCONCLUSIVE');
          assert.ok(!reviewed.tags.includes('gate:live'), 'gate label never written');
          assert.ok(!reviewed.tags.includes('bug'), 'tainted AI batch is discarded wholesale');
          assert.ok(reviewed.tags.includes('status:needs-decision'), 'non-APPROVE carries needs-decision');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-1c: publish rejects a crafted APPROVE artifact whose suggestedLabels
    // carry a human-only label (gate:*) — same sticky INCONCLUSIVE, never written.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-issue-gate-pub-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', suggestedLabels: ['bug'] }]);
        try {
          const reviewState = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const reviewClient = issueClient(reviewState, { getTitle: 'gate-title', getBody: 'gate-body' });
          await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'gate-title', body: 'gate-body' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
        } finally {
          restore();
        }
        const forged = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
        forged.suggestedLabels = ['gate:live'];
        fs.writeFileSync(artifactPath, JSON.stringify(forged));
        const applied: string[] = [];
        const state = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const client = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 'gate-title', body: 'gate-body' } }),
              listComments: async (params: { page: number }) => {
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
              addLabels: async (params: { labels: string[] }) => { applied.push(...params.labels); return {}; },
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'gate-title', body: 'gate-body' } },
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
        assert.equal(state.created, 0, 'forged-gate publish creates no second comment');
        assert.equal(state.updated, 1, 'forged-gate publish updates the same sticky');
        assert.ok(state.comments[0].body.includes('INCONCLUSIVE'), 'forged gate falls back');
        assert.ok(!applied.includes('gate:live'), 'gate label never applied');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-1e: publish rejects a forged APPROVE artifact carrying retired chunk
    // fields — same sticky INCONCLUSIVE, never written.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-issue-forgechunk-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] }]);
        try {
          const reviewState = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const reviewClient = issueClient(reviewState, { getTitle: 'fc-title', getBody: 'fc-body' });
          await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'fc-title', body: 'fc-body' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
        } finally {
          restore();
        }
        const forged = {
          ...JSON.parse(fs.readFileSync(artifactPath, 'utf8')),
          chunks: [{ index: 0, total: 1 }],
          chunkCoverageComplete: true,
          chunkCount: 1,
        } as Record<string, unknown>;
        fs.writeFileSync(artifactPath, JSON.stringify(forged));
        const applied: string[] = [];
        const state = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const client = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 'fc-title', body: 'fc-body' } }),
              listComments: async (params: { page: number }) => {
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
              addLabels: async (params: { labels: string[] }) => { applied.push(...params.labels); return {}; },
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'fc-title', body: 'fc-body' } },
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
        assert.equal(state.created, 0, 'forged-chunk publish creates no second comment');
        assert.equal(state.updated, 1, 'forged-chunk publish updates the same sticky');
        assert.ok(state.comments[0].body.includes('INCONCLUSIVE'), 'forged chunks fall back');
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
          assert.ok(!state.comments[0].body.includes('Report: unavailable'), 'success does not show unavailable');
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
          assert.ok(state.comments[0].body.includes('Verdict: INCONCLUSIVE'), 'failure is INCONCLUSIVE');
          assert.ok(state.comments[0].body.includes('Report: unavailable'), 'failure shows unavailable');
          assert.ok(!state.comments[0].body.includes('actions/runs/'), 'failure gives no fake link');
          void stickyId;
        }
        // formatReportLine unit: missing identity => unavailable, no fake URL.
        assert.equal(getReportRunUrl({} as NodeJS.ProcessEnv), undefined);
        assert.ok(formatReportLine({} as NodeJS.ProcessEnv, true).includes('unavailable'));
        assert.ok((getReportRunUrl({ GITHUB_REPOSITORY: REPO, GITHUB_RUN_ID: '999' } as unknown as NodeJS.ProcessEnv) ?? '').includes('actions/runs/999'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-3: 20001-char body is truncated with honest counts and stays
    // INCONCLUSIVE with zero AI (deterministic BLOCK may NEEDS_CHANGES).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-trunc20001-'));
      try {
        const longBody = 'x'.repeat(20001);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'must not run', suggestedLabels: [] }]);
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
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', '20001-char body never approves');
          assert.equal(reviewed.commentsComplete, false);
          assert.equal(counter.count, 0, 'truncated content makes zero AI calls');
          assert.deepEqual(reviewed.truncation, {
            shownChars: 4 + 8000,
            totalChars: 4 + 20001,
            shownComments: 0,
            totalComments: 0,
          }, 'honest truncation counts recorded');
          assert.ok(reviewed.summary.includes('truncated'), 'summary states truncation');
          assert.ok(reviewed.summary.includes('8004/20005'), 'summary carries shown/total counts');
          // Fingerprint still covers the full body (no tail collision).
          const expectedFp = issueContentFingerprint('long', longBody, []);
          assert.equal(reviewed.fingerprint, expectedFp, 'full fingerprint verified');
          // Artifact carries the truncation proof and no chunk fields.
          const persisted = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
          assert.deepEqual(persisted.truncation, reviewed.truncation, 'artifact carries truncation proof');
          assert.ok(!('chunks' in persisted), 'no chunk fields in artifact');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // H-3b: truncated content with a deterministic BLOCK still yields
    // NEEDS_CHANGES with zero AI and carries the truncation proof.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-h-truncblock-'));
      try {
        const secretBody = `leaked token ghp_${'A'.repeat(20)} plus padding ${'x'.repeat(9000)}`;
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'must not run', suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'leak', body: secretBody } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'leak', body: secretBody } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'truncated BLOCK still needs changes');
          assert.equal(counter.count, 0, 'truncated BLOCK makes zero AI calls');
          assert.ok(reviewed.truncation, 'truncated BLOCK carries proof');
          assert.ok(reviewed.summary.includes('showing'), 'BLOCK summary states truncation');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P6: code reference guards (traversal, absolute, magic, secrets).
    {
      assert.equal(isIssueCodeRefAllowed('app/src/main/java/demo/Safe.kt'), true);
      assert.equal(isIssueCodeRefAllowed('app/build.gradle.kts'), true);
      assert.equal(isIssueCodeRefAllowed('.github/workflows/pocketguard.yml'), true);
      assert.equal(isIssueCodeRefAllowed('AndroidManifest.xml'), true);
      for (const bad of ['../secret.txt', 'a/../../b', '/etc/passwd', 'a:(exclude)b', 'a*.kt', 'a?.kt', 'a b', '', 'https://x/y', 'key.keystore', 'app/local.properties', 'a\u0000b']) {
        assert.equal(isIssueCodeRefAllowed(bad), false, `${bad} rejected`);
      }
      assert.equal(resolveIssueCodeRef('main'), 'main');
      assert.equal(resolveIssueCodeRef('feature/x'), 'feature/x');
      for (const bad of [undefined, '', 'main;rm', 'a b', 'a:b', '..', 'x'.repeat(65)]) {
        assert.equal(resolveIssueCodeRef(bad), undefined, `${String(bad)} rejected`);
      }
    }

    // P6: second-layer diagnosis tightens with code evidence.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p6-tighten-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const gitCalls: string[][] = [];
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'looks fine', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
          { verdict: 'NEEDS_CHANGES', summary: 'code confirms the bug', suggestedLabels: ['bug'] },
        ]);
        try {
          const state = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const client = issueClient(state, { getTitle: 'crash', getBody: 'app crashes on launch' });
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO, default_branch: 'main' }, issue: { number: 7, title: 'crash', body: 'app crashes on launch' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: (args: string[]) => { gitCalls.push(args); return 'class Safe { fun start() = TODO() }'; },
          });
          assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'code-grounded diagnosis tightens');
          assert.equal(counter.count, 2, 'two layers ran');
          assert.ok(gitCalls.some((args) => args[0] === 'show' && args[1] === 'main:app/src/main/java/demo/Safe.kt'), 'read-only show on the default branch');
          assert.deepEqual(reviewed.codeRefs, [{ path: 'app/src/main/java/demo/Safe.kt', shownChars: 35, totalChars: 35 }], 'code proof persisted');
          assert.ok(reviewed.tags.includes('bug'), 'second-layer labels union');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P6: second layer confirms APPROVE; failures and illegal paths keep round one.
    for (const shape of [
      {
        label: 'confirm',
        first: { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
        second: { verdict: 'APPROVE', summary: 'code is fine', suggestedLabels: [] },
        git: 'class Safe {}',
        expectVerdict: 'APPROVE',
        expectCalls: 2,
        expectSummary: 'code is fine',
      },
      {
        label: 'transport-failure',
        first: { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
        second: null,
        git: 'class Safe {}',
        expectVerdict: 'APPROVE',
        expectCalls: 2,
      },
      {
        label: 'illegal-path',
        first: { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: ['../secret.txt'] },
        second: { verdict: 'NEEDS_CHANGES', summary: 'must not run', suggestedLabels: [] },
        git: 'class Safe {}',
        expectVerdict: 'APPROVE',
        expectCalls: 1,
      },
      {
        label: 'empty-file',
        first: { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
        second: { verdict: 'NEEDS_CHANGES', summary: 'must not run', suggestedLabels: [] },
        git: '',
        expectVerdict: 'APPROVE',
        expectCalls: 1,
      },
    ] as const) {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `pocketguard-p6-${shape.label}-`));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const previousFetch = globalThis.fetch;
        let idx = 0;
        const payloads = shape.second ? [shape.first, shape.second] : [shape.first];
        globalThis.fetch = (async () => {
          counter.count += 1;
          if (!shape.second && counter.count >= 2) throw new Error('second layer down');
          const payload = idx < payloads.length ? payloads[idx] : payloads[payloads.length - 1];
          idx += 1;
          return new Response(JSON.stringify({
            output: [{ content: [{ type: 'output_text', text: JSON.stringify(payload) }] }],
          }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }) as typeof fetch;
        try {
          const state = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const client = issueClient(state, { getTitle: 't', getBody: 'b' });
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO, default_branch: 'main' }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: () => shape.git,
          });
          assert.equal(reviewed.verdict, shape.expectVerdict, `P6 ${shape.label} keeps round one`);
          assert.equal(counter.count, shape.expectCalls, `P6 ${shape.label} call count`);
          if ('expectSummary' in shape) {
            assert.equal(reviewed.summary, shape.expectSummary, `P6 ${shape.label} ties follow the fresher summary`);
          }
        } finally {
          globalThis.fetch = previousFetch;
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P6: second layer is skipped when the first verdict is already fixed
    // (NEEDS), blocked deterministically, or the ref is missing.
    for (const shape of [
      {
        label: 'needs-first',
        title: 't',
        body: 'b',
        first: { verdict: 'NEEDS_CHANGES', summary: 'needs work', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
        eventRepo: { full_name: REPO, default_branch: 'main' },
      },
      {
        label: 'deterministic-block',
        title: 'leak',
        body: `token ghp_${'A'.repeat(20)}`,
        first: { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
        eventRepo: { full_name: REPO, default_branch: 'main' },
      },
      {
        label: 'missing-ref',
        title: 't',
        body: 'b',
        first: { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
        eventRepo: { full_name: REPO },
      },
    ] as const) {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `pocketguard-p6-skip-${shape.label}-`));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [shape.first]);
        try {
          const state = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const client = issueClient(state, { getTitle: shape.title, getBody: shape.body });
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: shape.eventRepo, issue: { number: 7, title: shape.title, body: shape.body } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: () => { throw new Error('must not read code'); },
          });
          assert.equal(counter.count, 1, `P6 ${shape.label} runs only the first layer`);
          assert.equal(reviewed.codeRefs, undefined, `P6 ${shape.label} stores no code proof`);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P6: a successful second layer never loosens; ties follow the fresher summary.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p6-noloosen-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'INCONCLUSIVE', summary: 'unclear', suggestedLabels: [], codeRefs: ['app/src/main/java/demo/Safe.kt'] },
          { verdict: 'APPROVE', summary: 'actually fine', suggestedLabels: [] },
        ]);
        try {
          const state = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const client = issueClient(state, { getTitle: 't', getBody: 'b' });
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO, default_branch: 'main' }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: () => 'class Safe {}',
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'second layer never loosens');
          assert.equal(reviewed.summary, 'unclear', 'a looser second layer never rewrites the summary');
          assert.equal(counter.count, 2, 'both layers ran');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P6: malformed codeRefs fail the strict triage schema (fail-closed).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p6-badrefs-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: 'not-an-array' },
        ]);
        try {
          const state = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const client = issueClient(state, { getTitle: 't', getBody: 'b' });
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO, default_branch: 'main' }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: () => 'x',
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'malformed codeRefs fail closed');
          assert.equal(counter.count, 1, 'only the first layer ran');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P6: code reference count is capped at 10 files.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p6-cap-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const refs = Array.from({ length: 11 }, (_, i) => `app/src/main/java/demo/File${i}.kt`);
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [], codeRefs: refs },
          { verdict: 'APPROVE', summary: 'code fine', suggestedLabels: [] },
        ]);
        try {
          const state = { comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>, created: 0, updated: 0 };
          const client = issueClient(state, { getTitle: 't', getBody: 'b' });
          let shows = 0;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO, default_branch: 'main' }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: (args: string[]) => { if (args[0] === 'show') shows += 1; return 'x'; },
          });
          assert.equal(shows, 10, 'at most 10 files fetched');
          assert.equal(reviewed.codeRefs?.length, 10, 'proof capped at 10');
          assert.equal(reviewed.verdict, 'APPROVE');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T1: body 8001/15000 is truncated and stays INCONCLUSIVE with zero AI.
    for (const [label, bodyLen] of [['8001', 8001], ['15000', 15000]] as const) {
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
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', `T1 body ${label} never approves`);
          assert.equal(reviewed.commentsComplete, false, `T1 body ${label} incomplete`);
          assert.equal(counter.count, 0, `T1 body ${label} makes zero AI calls`);
          assert.equal(reviewed.truncation?.totalChars, 2 + bodyLen, `T1 body ${label} total recorded`);
          assert.equal(reviewed.truncation?.shownChars, 2 + 8000, `T1 body ${label} shown capped at 8000`);
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
          assert.equal(reviewed.truncation, undefined, 'T1 fetch failure stores no truncation proof');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T2: single comment 2001/9001 is truncated and stays INCONCLUSIVE.
    for (const [label, commentLen] of [['2001', 2001], ['9001', 9001]] as const) {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `pocketguard-h-t2-${label}-`));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: `t2 ${label} ok`, suggestedLabels: [] }]);
        try {
          const longComment = 'e'.repeat(commentLen);
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't2', body: 'b' } }),
                listComments: async () => ({ data: [{ id: 3, body: longComment, user: { login: 'human', type: 'User' } }] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't2', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', `T2 comment ${label} never approves`);
          assert.equal(counter.count, 0, `T2 comment ${label} makes zero AI calls`);
          assert.equal(reviewed.truncation?.totalComments, 1);
          assert.equal(reviewed.truncation?.shownComments, 1, 'comment kept but cut');
          assert.ok((reviewed.truncation?.totalChars ?? 0) > (reviewed.truncation?.shownChars ?? 0), 'chars omitted recorded');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T3: multi-comment total past budget is truncated and stays INCONCLUSIVE.
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
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'T3 multi-comment over budget never approves');
          assert.equal(counter.count, 0, 'T3 makes zero AI calls');
          assert.ok((reviewed.truncation?.shownComments ?? 11) < 11, 'T3 dropped comments recorded');
          assert.equal(reviewed.truncation?.totalComments, 11);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T4: 0 body + long comment is truncated and stays INCONCLUSIVE.
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
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'T4 0-body + long comment never approves');
          assert.equal(counter.count, 0, 'T4 makes zero AI calls');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // T5: boundaries — body 8000 and total 20000 stay single-turn;
    // body 8001 and total 20001 truncate to INCONCLUSIVE with zero AI.
    for (const shape of [
      { label: 'body-8000', title: 't5', body: 'x'.repeat(8000), comments: [], complete: true },
      { label: 'body-8001', title: 't5', body: 'x'.repeat(8001), comments: [], complete: false },
      {
        label: 'total-20000',
        title: 't5',
        body: 'x'.repeat(8000),
        comments: [
          ...Array.from({ length: 4 }, (_, i) => ({ id: 10 + i, body: 'y'.repeat(1992), user: { login: 'human', type: 'User' } })),
          ...Array.from({ length: 2 }, (_, i) => ({ id: 14 + i, body: 'y'.repeat(1997), user: { login: 'human', type: 'User' } })),
        ],
        complete: true,
      },
      {
        label: 'total-20001',
        title: 't5',
        body: 'x'.repeat(8000),
        comments: [
          ...Array.from({ length: 4 }, (_, i) => ({ id: 10 + i, body: 'y'.repeat(1992), user: { login: 'human', type: 'User' } })),
          { id: 14, body: 'y'.repeat(1997), user: { login: 'human', type: 'User' } },
          { id: 15, body: 'y'.repeat(1998), user: { login: 'human', type: 'User' } },
        ],
        complete: false,
      },
    ] as const) {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `pocketguard-h-t5-${shape.label}-`));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: `t5 ${shape.label} ok`, suggestedLabels: [] }]);
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: shape.title, body: shape.body } }),
                listComments: async () => ({ data: shape.comments }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: shape.title, body: shape.body } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          if (shape.complete) {
            assert.equal(reviewed.verdict, 'APPROVE', `T5 ${shape.label} single-turn may APPROVE`);
            assert.equal(reviewed.commentsComplete, true);
            assert.equal(reviewed.truncation, undefined, `T5 ${shape.label} stores no truncation proof`);
            assert.equal(counter.count, 1, `T5 ${shape.label} runs one triage turn`);
          } else {
            assert.equal(reviewed.verdict, 'INCONCLUSIVE', `T5 ${shape.label} never approves`);
            assert.equal(reviewed.commentsComplete, false);
            assert.equal(counter.count, 0, `T5 ${shape.label} makes zero AI calls`);
            assert.ok(reviewed.truncation, `T5 ${shape.label} records truncation`);
          }
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
          { role: 'architecture' as const, modelUsed: 'm', verdict: 'NEEDS_CHANGES' as const, findings: manyFindings.slice(0, 40) },
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
      const sticky = reviewComment(prOutput, [], 'Report: unavailable (review report could not be generated or failed to upload, see Actions run log).');
      assert.ok(sticky.includes('omitted'), 'T8 PR sticky carries omission note for 100+ findings');
      assert.ok(sticky.length < 60000, `T8 PR sticky bounded (got ${sticky.length})`);
      // T8 extended (Phase 3 P2 #3): whole-sticky hard budget plus tail counts.
      assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `T8 PR sticky within total cap ${MAX_STICKY_TOTAL_LENGTH} (got ${sticky.length})`);
      assert.ok(sticky.length <= GITHUB_COMMENT_HARD_LIMIT, 'T8 PR sticky within GitHub hard limit');
      assert.ok(sticky.includes('100 finding(s)'), 'T8 tail carries total finding count');
      assert.ok(sticky.includes('artifact'), 'T8 tail guides to the report artifact');
      // Issue sticky: long summary plus truncation proof stays bounded.
      const issueOutput = {
        verdict: 'INCONCLUSIVE' as const,
        issueNumber: 7,
        title: 't8',
        tags: [],
        summary: 's'.repeat(5000),
        suggestedLabels: [],
        fingerprint: 'a'.repeat(64),
        commentsComplete: false,
        truncation: { shownChars: 8004, totalChars: 20005, shownComments: 0, totalComments: 0 },
      };
      const issueSticky = issueReviewComment(issueOutput, [], 'Report: unavailable (review report could not be generated or failed to upload, see Actions run log).');
      assert.ok(issueSticky.includes('Truncated: showing 8004/20005 chars, 0/0 comments'), 'T8 issue sticky carries honest truncation line');
      assert.ok(issueSticky.includes('omitted'), 'T8 issue sticky carries omission note');
      assert.ok(truncateStickyText('x'.repeat(100), 10).includes('omitted'), 'T8 truncate helper notes omission');
      assert.ok(issueSticky.length < 20000, `T8 issue sticky bounded (got ${issueSticky.length})`);
    }

    // Phase 3 (P2 #3): whole-sticky hard cap. Single sticky only (never a
    // second comment); overflow stays in the artifact with total/shown/omitted
    // plus artifact guidance in the tail.
    {
      assert.equal(MAX_STICKY_TOTAL_LENGTH, 50000, 'P3 total budget leaves 65536 headroom');
      assert.equal(GITHUB_COMMENT_HARD_LIMIT, 65536, 'P3 fail-safe matches the GitHub limit');
      const prShape = (
        roles: Array<{ role: 'architecture' | 'android_sec' | 'android_code'; findings: Array<{ severity: 'BLOCK' | 'WARN' | 'SUGGESTION'; file?: string; line?: number; issue: string; suggestion?: string }> }>,
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
        const roles = (['architecture', 'android_sec', 'android_code'] as const).map((role) => ({
          role,
          findings: Array.from({ length: 20 }, (_, i) => mkLong(role, i)),
        }));
        const sticky = reviewComment(prShape(roles), [], 'report line');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-1 60 long findings within total cap (got ${sticky.length})`);
        assert.ok(sticky.length <= GITHUB_COMMENT_HARD_LIMIT, 'P3-1 within GitHub hard limit');
        assert.ok(sticky.includes('omitted'), 'P3-1 carries omission note');
        assert.ok(sticky.includes('60 finding(s)'), 'P3-1 tail carries total finding count');
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
        const sticky = reviewComment(prShape([{ role: 'architecture', findings: [] }], violations), [], 'report line');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-2 120 long violations within total cap (got ${sticky.length})`);
        assert.ok(sticky.includes('omitted'), 'P3-2 carries omission note');
        assert.ok(sticky.includes('120 deterministic violation(s)'), 'P3-2 tail carries total violation count');
        assert.ok(!sticky.includes('z'.repeat(2000)), 'P3-2 single-item cap truncates long messages');
      }

      // P3-3: escape-dense text is truncated->escaped and accounted post-escape.
      {
        const dense = '`*_{}[]()#+-.!|<>\\'.repeat(200);
        const roles = [{ role: 'architecture' as const, findings: [{ severity: 'BLOCK' as const, file: 'a*b_c[d](e)#f.kt', line: 1, issue: dense, suggestion: dense }] }];
        const sticky = reviewComment(prShape(roles), [], 'report line');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-3 escape-dense within total cap (got ${sticky.length})`);
        // Truncate->escape helper: raw cut first, escaped length accounted.
        const single = stickyField(dense, MAX_STICKY_ITEM_LENGTH);
        assert.ok(single.includes('omitted'), 'P3-3 helper notes omission for dense input');
        assert.ok(single.includes('\\*'), 'P3-3 helper escapes markdown after truncation');
        assert.ok(!sticky.includes(dense), 'P3-3 raw dense run never lands verbatim');
      }

      // P3-4: 500+ char filenames stay safe in findings plus both file lists.
      {
        const longName = `${'d'.repeat(300)}/${'e'.repeat(300)}.kt`;
        assert.ok(longName.length > 500, 'P3-4 fixture really exceeds 500 chars');
        const roles = [{ role: 'architecture' as const, findings: [{ severity: 'WARN' as const, file: longName, line: 7, issue: 'name check', suggestion: 'rename' }] }];
        const coverage = {
          complete: false,
          omittedFiles: Array.from({ length: 30 }, (_, i) => `${'o'.repeat(250)}/omitted-${i}.kt`),
          truncatedFiles: Array.from({ length: 25 }, (_, i) => `${'t'.repeat(250)}/truncated-${i}.kt`),
          originalLength: 999,
        };
        const sticky = reviewComment(prShape(roles, [], coverage), [], 'report line');
        assert.ok(sticky.length <= MAX_STICKY_TOTAL_LENGTH, `P3-4 long filenames within total cap (got ${sticky.length})`);
        assert.ok(!sticky.includes(longName), 'P3-4 full 500+ filename never lands verbatim');
        assert.ok(sticky.includes('30 total'), 'P3-4 omitted list carries total count');
        assert.ok(sticky.includes('25 total'), 'P3-4 truncated list carries total count');
        assert.ok(sticky.includes(`${MAX_STICKY_FILE_LIST_SHOWN} shown`), 'P3-4 file lists carry shown count');
        assert.ok(sticky.includes('omitted'), 'P3-4 file lists carry omission note');
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
          { role: 'architecture' as const, findings: [...sugs.slice(0, 30), ...blocks.slice(0, 2)] },
          { role: 'android_sec' as const, findings: [...sugs.slice(30, 60), ...blocks.slice(2, 4)] },
          { role: 'android_code' as const, findings: [...sugs.slice(60), ...blocks.slice(4)] },
        ];
        const sticky = reviewComment(prShape(roles), [], 'report line');
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
          assert.ok(sticky.includes('Verdict: INCONCLUSIVE'), 'D-1 falls back to INCONCLUSIVE');
          assert.ok(!sticky.includes('Verdict: APPROVE'), 'D-1 never APPROVE with unavailable');
          assert.ok(sticky.includes('Report: unavailable'), 'D-1 shows unavailable');
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
          assert.ok(sticky.includes('Verdict: INCONCLUSIVE'), 'D-2 INCONCLUSIVE');
          assert.ok(!sticky.includes('Verdict: APPROVE'));
          assert.ok(sticky.includes('Report: unavailable'));
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
          assert.ok(sticky.includes('Verdict: INCONCLUSIVE'), 'D-3 download failure stays INCONCLUSIVE');
          assert.ok(sticky.includes('Report: unavailable'));
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
          assert.ok(sticky.includes('Verdict: INCONCLUSIVE'), `D-4 tampered ${variant} stays INCONCLUSIVE`);
          assert.ok(!sticky.includes('Verdict: APPROVE'));
          assert.ok(sticky.includes('Report: unavailable'));
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

    // Fingerprint gate: live content change after review falls back to
    // INCONCLUSIVE on the same sticky (full RAW fingerprint, no tail collision).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-stale-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'p2 ok', suggestedLabels: [] }]);
        try {
          const reviewClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'p2', body: 'body before' } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2', body: 'body before' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'review may APPROVE before the edit');
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
              get: async () => ({ data: { number: 7, title: 'p2', body: 'body after' } }),
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
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2', body: 'body after' } },
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
        assert.equal(state.created, 0, 'stale publish creates no second comment');
        assert.equal(state.updated, 1, 'stale publish updates same sticky');
        assert.ok(state.comments[0].body.includes('Verdict: INCONCLUSIVE'), 'live edit falls back to INCONCLUSIVE');
        assert.ok(!state.comments[0].body.includes('Verdict: APPROVE'), 'stale content never APPROVE');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // Truncation-proof gate: a forged truncation proof (shown > total)
    // invalidates the artifact and falls back to INCONCLUSIVE on the same
    // sticky, even when the live content is unchanged.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-trunctamper-'));
      try {
        const longBody = 'z'.repeat(9001);
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'must not run', suggestedLabels: [] }]);
        try {
          const reviewClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'p2t', body: longBody } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2t', body: longBody } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'truncated setup stays INCONCLUSIVE');
          assert.ok(reviewed.truncation, 'truncated setup carries proof');
        } finally {
          restore();
        }
        // Forge the proof (shown past total); schema still parses as an
        // object, so only the proof validation must catch it.
        const artifact = JSON.parse(fs.readFileSync(artifactPath, 'utf8')) as Record<string, unknown>;
        const proof = { ...(artifact.truncation as Record<string, unknown>) };
        proof.shownChars = (proof.totalChars as number) + 1;
        artifact.truncation = proof;
        fs.writeFileSync(artifactPath, JSON.stringify(artifact, null, 2));
        assert.equal(validateIssueOutput(JSON.parse(fs.readFileSync(artifactPath, 'utf8'))), undefined, 'forged proof fails validation');
        const state = {
          comments: [{ id: 52, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const publishClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 'p2t', body: longBody } }),
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
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'p2t', body: longBody } },
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
        assert.equal(state.created, 0, 'forgery publish creates no second comment');
        assert.equal(state.updated, 1, 'forgery publish updates same sticky');
        assert.ok(state.comments[0].body.includes('Verdict: INCONCLUSIVE'), 'forged proof falls back to INCONCLUSIVE');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard phase2-h tests] All tests passed.');
}
