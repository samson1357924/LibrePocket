import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  classifyCommentCommand,
  isCommentCommandAllowed,
  stripNonCommandContent,
} from '../src/comment_command';
import {
  fetchFreshIssueFields,
  hasUnknownAiLabels,
  issueContentFingerprint,
  normalizeRawForFingerprint,
  runIssueReviewMode,
  runPublishMode,
  runReviewMode,
  runTagMode,
  scanIssueDeterministicBlock,
  validateIssueOutput,
  type RunnerContext,
} from '../src/github_runner';
import { redactForModel } from '../src/redact';

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

function safeGitStub(): (args: string[]) => string {
  return (args: string[]) => {
    if (args[0] === 'fetch') return '';
    if (args[0] === 'merge-base') return BASE_SHA;
    if (args[0] === 'diff' && args[1] === '--name-only') return 'app/src/main/java/demo/Safe.kt\0';
    return [
      'diff --git a/app/src/main/java/demo/Safe.kt b/app/src/main/java/demo/Safe.kt',
      '--- a/app/src/main/java/demo/Safe.kt',
      '+++ b/app/src/main/java/demo/Safe.kt',
      '@@ -1,1 +1,1 @@',
      '+class Safe',
    ].join('\n');
  };
}

function installOpenAIStub(
  counter: { count: number },
  payload: unknown,
  onRequest?: (bodyText: string) => void,
): () => void {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async (_url: unknown, init?: { body?: unknown }) => {
    counter.count += 1;
    if (onRequest && typeof init?.body === 'string') onRequest(init.body);
    return new Response(JSON.stringify({
      output: [{ content: [{ type: 'output_text', text: JSON.stringify(payload) }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  return () => { globalThis.fetch = previousFetch; };
}

function prCommentEvent(body: string, login: string): Record<string, unknown> {
  return {
    action: 'created',
    repository: { full_name: REPO },
    issue: { number: 41, pull_request: { url: 'unused' }, title: 'topic' },
    comment: { body, user: { login, type: 'User' } },
  };
}

export async function runStage4P2Tests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    // P2 #4: only /review is actionable on pull requests.
    assert.equal(isCommentCommandAllowed('review', 'pull-request'), true);
    assert.equal(isCommentCommandAllowed('explain', 'pull-request'), false, 'explain is a no-op');
    assert.equal(isCommentCommandAllowed('fix', 'pull-request'), false, 'fix is a no-op');
    assert.equal(isCommentCommandAllowed('fix-ci', 'pull-request'), false, 'fix-ci is a no-op');
    assert.equal(isCommentCommandAllowed('triage', 'pull-request'), false);
    assert.equal(isCommentCommandAllowed('triage', 'issue'), true);
    assert.equal(isCommentCommandAllowed('unsupported', 'pull-request'), false);
    // Classification still recognizes the verbs (allowed-gate is separate).
    assert.equal(classifyCommentCommand('/explain'), 'explain');
    assert.equal(classifyCommentCommand('/fix'), 'fix');
    assert.equal(classifyCommentCommand('/fix-ci'), 'fix-ci');

    // P2 #4: fence/quote stripping — command-looking text inside fences,
    // inline code, or quotations never triggers.
    assert.equal(classifyCommentCommand('```\n/review\n```'), 'unsupported', 'fenced /review is ignored');
    assert.equal(classifyCommentCommand('```js\n/review\n```'), 'unsupported', 'fenced with language is ignored');
    assert.equal(classifyCommentCommand('~~~\n/review\n~~~'), 'unsupported', 'tilde fence is ignored');
    assert.equal(classifyCommentCommand('```\n/review\n```\n/review'), 'review', 'command after a closed fence still triggers');
    assert.equal(classifyCommentCommand('> /review'), 'unsupported', 'quoted /review is ignored');
    assert.equal(classifyCommentCommand('> quoted line\n/review'), 'review', 'command after a quote still triggers');
    assert.equal(classifyCommentCommand('`/review`'), 'unsupported', 'inline code /review is ignored');
    assert.equal(classifyCommentCommand('```\n@pocketguard review\n```'), 'unsupported', 'fenced mention is ignored');
    assert.equal(classifyCommentCommand('/review'), 'review', 'plain /review still triggers');
    assert.equal(classifyCommentCommand('Please @pocketguard review this'), 'review', 'inline mention still triggers');
    assert.ok(!stripNonCommandContent('```\n/review\n```').includes('/review'), 'strip helper removes fenced content');

    // P2 #4: no-op commands consume no quota and run no AI.
    for (const verb of ['/explain', '/fix', '/fix-ci']) {
      const state = {
        comments: [] as Array<{ id: number; body: string; user: { login: string; type: string } }>,
        created: 0,
        updated: 0,
        labelsAdded: [] as string[][],
      };
      const client = {
        rest: {
          pulls: {
            get: async () => ({
              data: {
                number: 41,
                base: { sha: BASE_SHA },
                head: { sha: HEAD_SHA, repo: { full_name: REPO } },
                user: { login: 'pr-author' },
              },
            }),
          },
          users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
          repos: { getCollaboratorPermissionLevel: async () => ({ data: { permission: 'write' } }) },
          issues: {
            listComments: async () => ({ data: state.comments }),
            createComment: async () => { state.created += 1; return {}; },
            updateComment: async () => { state.updated += 1; return {}; },
            addLabels: async (params: { labels: string[] }) => { state.labelsAdded.push(params.labels); return {}; },
            listLabelsOnIssue: async () => ({ data: [] }),
            removeLabel: async () => ({}),
          },
        },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      const tagged = await runTagMode({
        event: prCommentEvent(verb, 'maintainer'),
        env: openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }),
        githubClient: client,
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(tagged.routeKind, 'ignore', `${verb}: routed to ignore`);
      assert.equal(tagged.reviewGate, 'none', `${verb}: no review gate`);
      assert.equal(tagged.shouldReview, false, `${verb}: no review scheduled`);
      assert.equal(tagged.reviewsUsed, 0, `${verb}: consumes no quota`);
      const counter = { count: 0 };
      const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
      try {
        const reviewed = await runReviewMode({
          event: prCommentEvent(verb, 'maintainer'),
          env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }), POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
          githubClient: client,
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', `${verb}: no AI review`);
        assert.equal(counter.count, 0, `${verb}: zero OpenAI calls`);
      } finally {
        restore();
      }
      await runPublishMode({
        event: prCommentEvent(verb, 'maintainer'),
        env: {
          GITHUB_EVENT_NAME: 'issue_comment',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_OUTPUT: path.join(os.tmpdir(), `pocketguard-p2-noop-${Date.now()}.json`),
          POCKETGUARD_REVIEW_JOB_RESULT: 'success',
          POCKETGUARD_TAG_LABELS: '[]',
        } as NodeJS.ProcessEnv,
        githubClient: client,
      });
      assert.equal(state.created, 0, `${verb}: publish creates nothing`);
      assert.equal(state.updated, 0, `${verb}: publish updates nothing`);
      assert.deepEqual(state.labelsAdded, [], `${verb}: publish writes no labels`);
    }

    // P2 #5: fetchFreshIssueFields — live read, failure, and missing API.
    {
      const okClient = {
        rest: { issues: { get: async () => ({ data: { number: 7, title: 'live', body: 'live body' } }) } },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      assert.deepEqual(await fetchFreshIssueFields(okClient, 'o', 'r', 7), { title: 'live', body: 'live body' });
      const throwingClient = {
        rest: { issues: { get: async () => { throw new Error('down'); } } },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      await assert.rejects(fetchFreshIssueFields(throwingClient, 'o', 'r', 7), /failed to fetch current issue state/);
      const mismatchClient = {
        rest: { issues: { get: async () => ({ data: { number: 8, title: 'other', body: '' } }) } },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      await assert.rejects(fetchFreshIssueFields(mismatchClient, 'o', 'r', 7), /failed to fetch current issue state/);
      const noGetClient = {
        rest: { issues: { listComments: async () => ({ data: [] }) } },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      assert.equal(await fetchFreshIssueFields(noGetClient, 'o', 'r', 7), undefined, 'missing get falls back');
      assert.equal(await fetchFreshIssueFields(undefined, 'o', 'r', 7), undefined);
    }

    // P2 #5: review uses the live title/body, not the webhook snapshot.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-fresh-review-'));
      try {
        let captured = '';
        const counter = { count: 0 };
        const restore = installOpenAIStub(
          counter,
          { verdict: 'APPROVE', summary: 'fresh ok', suggestedLabels: [] },
          (bodyText) => { captured = bodyText; },
        );
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 'live-fresh-title-abc', body: 'live-fresh-body' } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: {
              action: 'opened',
              repository: { full_name: REPO },
              issue: { number: 7, title: 'webhook-stale-title-xyz', body: 'webhook-stale-body' },
            },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(counter.count, 1);
          assert.equal(reviewed.title, 'live-fresh-title-abc', 'review records the live title');
          assert.ok(captured.includes('live-fresh-title-abc'), 'live title reaches the model');
          assert.ok(!captured.includes('webhook-stale-title-xyz'), 'webhook snapshot must not reach the model');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P2 #5: review degrades to INCONCLUSIVE when the live read fails.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-fresh-fail-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => { throw new Error('GitHub down'); },
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: {
              action: 'opened',
              repository: { full_name: REPO },
              issue: { number: 7, title: 't', body: 'b' },
            },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'fresh-read failure is fail-closed');
          assert.equal(counter.count, 0, 'fresh-read failure makes zero AI calls');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P2 #5: publish detects staleness against the live issue and falls back.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-fresh-stale-'));
      try {
        const oldFp = issueContentFingerprint('old-title', 'old-body', []);
        const artifactPath = path.join(tempDir, 'old.json');
        fs.writeFileSync(artifactPath, JSON.stringify({
          verdict: 'APPROVE',
          issueNumber: 7,
          title: 'old-title',
          tags: [],
          summary: 'old review',
          suggestedLabels: [],
          fingerprint: oldFp,
        }));
        const state = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const client = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 'new-title-after-edit', body: 'old-body' } }),
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
          event: {
            action: 'created',
            repository: { full_name: REPO },
            issue: { number: 7, title: 'old-title', body: 'old-body' },
            comment: { body: 'human follow-up', user: { login: 'human', type: 'User' } },
          },
          env: {
            GITHUB_EVENT_NAME: 'issue_comment',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: client,
        });
        assert.equal(state.created, 0, 'stale publish creates no second comment');
        assert.equal(state.updated, 1, 'stale publish updates the same sticky');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'stale live content falls back');
        assert.ok(!state.comments[0].body.includes('判定：APPROVE'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P2 #5: publish is INCONCLUSIVE when the live read fails.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-pub-fail-'));
      try {
        const fp = issueContentFingerprint('t', 'b', []);
        const artifactPath = path.join(tempDir, 'r.json');
        fs.writeFileSync(artifactPath, JSON.stringify({
          verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's', suggestedLabels: [], fingerprint: fp,
        }));
        const state = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const client = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => { throw new Error('GitHub down'); },
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
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
          env: {
            GITHUB_EVENT_NAME: 'issues',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: client,
        });
        assert.equal(state.updated, 1);
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'));
        assert.ok(state.comments[0].body.includes('could not be fetched'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P2 #6: type labels are allowlisted for issue triage.
    {
      assert.equal(hasUnknownAiLabels(['bug']), false, 'bug is allowlisted');
      assert.equal(hasUnknownAiLabels(['enhancement']), false, 'enhancement is allowlisted');
      assert.equal(hasUnknownAiLabels(['documentation']), false, 'documentation is allowlisted');
      const prompt = fs.readFileSync(path.resolve(__dirname, '../prompts/issue_triage.md'), 'utf8');
      assert.ok(prompt.includes('bug'), 'issue_triage.md allowlists bug');
      assert.ok(prompt.includes('enhancement'), 'issue_triage.md allowlists enhancement');
      assert.ok(prompt.includes('documentation'), 'issue_triage.md allowlists documentation');
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-typelabels-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: ['bug', 'area:docs'] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: { listComments: async () => ({ data: [] }) },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 'plain title', body: 'body' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'allowlisted type labels do not downgrade');
          assert.ok(reviewed.tags.includes('bug'), 'bug label survives the allowlist');
          assert.ok(reviewed.tags.includes('area:docs'));
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P2 #7: unknown AI labels are counted, never logged verbatim.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p2-unknownlog-'));
      try {
        const sentinel = `alien-label-sentinel-${Date.now()}`;
        const warnings: string[] = [];
        const originalWarn = console.warn;
        console.warn = (message?: unknown, ...rest: unknown[]) => { warnings.push(String(message)); void rest; };
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [sentinel] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: { listComments: async () => ({ data: [] }) },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.notEqual(reviewed.verdict, 'APPROVE', 'unknown labels force non-APPROVE');
          assert.ok(warnings.some((w) => /Discarded 1 unknown AI label/.test(w)), 'discard is logged as a count');
          assert.ok(warnings.every((w) => !w.includes(sentinel)), 'raw unknown label never enters the log');
        } finally {
          restore();
          console.warn = originalWarn;
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: incomplete issue comments never APPROVE (fail-closed).
    // First-page throw with an APPROVE-happy model still yields INCONCLUSIVE
    // with zero OpenAI calls.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-throw-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'should not approve', suggestedLabels: [] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: { listComments: async () => { throw new Error('403'); } },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'first-page throw never approves');
          assert.equal(reviewed.commentsComplete, false, 'throw marks incomplete');
          assert.equal(counter.count, 0, 'incomplete review makes zero OpenAI calls');
          assert.ok(reviewed.summary.includes('could not be fully fetched') || reviewed.summary.includes('freshness'), 'summary names incomplete comments');
          const persisted = validateIssueOutput(JSON.parse(fs.readFileSync(path.join(tempDir, 'r.json'), 'utf8')) as unknown);
          assert.ok(persisted && persisted.commentsComplete === false, 'artifact persists incomplete flag');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: non-array comment payload is incomplete, never APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-nonarray-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: { listComments: async () => ({ data: null }) },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'non-array payload never approves');
          assert.equal(reviewed.commentsComplete, false);
          assert.equal(counter.count, 0, 'non-array makes zero OpenAI calls');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: non-object entries mark the read incomplete.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-nonobject-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: { listComments: async () => ({ data: [null, 'oops', 123] }) },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'non-object entries never approve');
          assert.equal(reviewed.commentsComplete, false);
          assert.equal(counter.count, 0);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: missing listComments API is incomplete (no client path).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-noapi-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {},
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'missing listComments never approves');
          assert.equal(reviewed.commentsComplete, false);
          assert.equal(counter.count, 0);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: page 1 ok but page 2 403 is incomplete, never APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-page2-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        try {
          const page1 = Array.from({ length: 100 }, (_, i) => ({
            id: 1000 + i,
            body: `human note ${i}`,
            user: { login: 'human', type: 'User' },
          }));
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                listComments: async (params: { page: number }) => {
                  if (params.page === 1) return { data: page1 };
                  throw new Error('403 on page 2');
                },
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'page-2 failure never approves');
          assert.equal(reviewed.commentsComplete, false);
          assert.equal(counter.count, 0, 'page-2 failure makes zero OpenAI calls');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: bot-only noise still counts as complete and may APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-botfilter-'));
      try {
        let captured = '';
        const counter = { count: 0 };
        const restore = installOpenAIStub(
          counter,
          { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [] },
          (bodyText) => { captured = bodyText; },
        );
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                listComments: async () => ({
                  data: [
                    { id: 1, body: 'human insight', user: { login: 'human', type: 'User' } },
                    { id: 2, body: 'bot noise', user: { login: 'pocketguard[bot]', type: 'Bot' } },
                    { id: 3, body: 'other bot', user: { login: 'helper[bot]', type: 'User' } },
                    { id: 4, body: 'bot type', user: { login: 'human2', type: 'Bot' } },
                    { id: 5, body: '   ', user: { login: 'human', type: 'User' } },
                  ],
                }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'APPROVE', 'bot filtering keeps completeness and may approve');
          assert.equal(reviewed.commentsComplete, true, 'bot filtering stays complete');
          assert.equal(counter.count, 1);
          assert.ok(captured.includes('human insight'), 'human comment reaches the model');
          assert.ok(!captured.includes('bot noise'), 'bot comments stay excluded');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: review and publish with the same missing comments compute the
    // same fingerprint yet must share one INCONCLUSIVE sticky, never APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-samemissing-'));
      try {
        const artifactPath = path.join(tempDir, 'r.json');
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'should not approve', suggestedLabels: [] });
        const publishState = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const reviewClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              listComments: async () => { throw new Error('403'); },
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        // Publish fingerprint read throws once, then the sticky lookup
        // succeeds so the fallback can update the same comment in place.
        let publishListCalls = 0;
        const publishClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              listComments: async () => {
                publishListCalls += 1;
                if (publishListCalls === 1) throw new Error('403');
                return { data: publishState.comments };
              },
              createComment: async () => ({}),
              updateComment: async (params: { comment_id: number; body: string }) => {
                const found = publishState.comments.find((c) => c.id === params.comment_id);
                if (found) found.body = params.body;
                publishState.updated += 1;
                return {};
              },
              addLabels: async () => ({}),
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        const issueCommentEvent = {
          action: 'created',
          repository: { full_name: REPO },
          issue: { number: 7, title: 't', body: 'b' },
          comment: { body: 'human follow-up', user: { login: 'human', type: 'User' } },
        };
        let reviewed: Awaited<ReturnType<typeof runIssueReviewMode>>;
        try {
          reviewed = await runIssueReviewMode({
            event: issueCommentEvent,
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
        } finally {
          restore();
        }
        assert.equal(reviewed!.verdict, 'INCONCLUSIVE');
        assert.equal(reviewed!.commentsComplete, false);
        assert.equal(counter.count, 0);
        // The same missing input yields the same fingerprint on both sides,
        // yet publish must still fall back because completeness is false.
        const reviewFp = reviewed!.fingerprint;
        assert.match(reviewFp, /^[0-9a-f]{64}$/);
        await runPublishMode({
          event: issueCommentEvent,
          env: {
            GITHUB_EVENT_NAME: 'issue_comment',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: publishClient,
        });
        assert.equal(publishState.created, 0, 'same-missing publish creates no second comment');
        assert.equal(publishState.updated, 1, 'same-missing publish updates the same sticky');
        assert.ok(publishState.comments[0].body.includes('判定：INCONCLUSIVE'), 'same missing falls back');
        assert.ok(!publishState.comments[0].body.includes('判定：APPROVE'), 'same missing never approves');
        assert.ok(
          publishState.comments[0].body.includes('freshness') || publishState.comments[0].body.includes('could not be fully fetched'),
          'fallback names unverifiable freshness',
        );
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: complete artifact but failing publish re-read falls back.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-pubfail-'));
      try {
        const artifactPath = path.join(tempDir, 'good.json');
        const goodCounter = { count: 0 };
        const goodRestore = installOpenAIStub(goodCounter, { verdict: 'APPROVE', summary: 'fine', suggestedLabels: [] });
        const goodClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: { listComments: async () => ({ data: [] }) },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        let good: Awaited<ReturnType<typeof runIssueReviewMode>>;
        try {
          good = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: goodClient,
            writeStdout: () => undefined,
          });
        } finally {
          goodRestore();
        }
        assert.equal(good!.verdict, 'APPROVE');
        assert.equal(good!.commentsComplete, true);
        const state = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        // Publish fingerprint read fails once, then the sticky lookup
        // succeeds so the fallback updates the same comment.
        let failingListCalls = 0;
        const failingClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              listComments: async () => {
                failingListCalls += 1;
                if (failingListCalls === 1) throw new Error('403 at publish');
                return { data: state.comments };
              },
              createComment: async () => { state.created += 1; return {}; },
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
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
          env: {
            GITHUB_EVENT_NAME: 'issues',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: failingClient,
        });
        assert.equal(state.created, 0, 'publish failure creates no second comment');
        assert.equal(state.updated, 1, 'publish failure updates the same sticky');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'));
        assert.ok(!state.comments[0].body.includes('判定：APPROVE'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2: fingerprint equality lock — the same missing input hashes equal,
    // old artifacts without the flag are invalid, and truncation is incomplete.
    {
      assert.equal(
        issueContentFingerprint('t', 'b', []),
        issueContentFingerprint('t', 'b', []),
        'same missing comments hash equal (the collision publish must still reject)',
      );
      assert.notEqual(
        issueContentFingerprint('t', 'b', []),
        issueContentFingerprint('t', 'b', ['human：hi']),
        'present comments hash different',
      );
      assert.equal(
        validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's', suggestedLabels: [], fingerprint: 'a'.repeat(64) }),
        undefined,
        'pre-fix artifact without commentsComplete is invalid (fail-closed)',
      );
      assert.ok(
        validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's', suggestedLabels: [], fingerprint: 'a'.repeat(64), commentsComplete: true }),
        'artifact with commentsComplete validates',
      );
      assert.equal(
        validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's', suggestedLabels: [], fingerprint: 'a'.repeat(64), commentsComplete: 'yes' }),
        undefined,
        'non-boolean completeness rejects',
      );
      // Truncation that discards a comment is incomplete and never approves.
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1c-truncate-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        try {
          const big = `x`.repeat(6000);
          const many = Array.from({ length: 10 }, (_, i) => ({
            id: 2000 + i,
            body: `${big}-${i}`,
            user: { login: 'human', type: 'User' },
          }));
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: { listComments: async () => ({ data: many }) },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.commentsComplete, false, 'truncation discarding comments is incomplete');
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'truncated comments never approve');
          assert.equal(counter.count, 0, 'truncated comments make zero OpenAI calls');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #1: single-field truncation is fail-closed with a full-content
    // fingerprint (no tail collision). Body 8001: tail X vs Y both yield
    // INCONCLUSIVE with zero OpenAI calls, yet hash differently.
    {
      const bodyX = `${'a'.repeat(8000)}X`;
      const bodyY = `${'a'.repeat(8000)}Y`;
      const seen: Array<Awaited<ReturnType<typeof runIssueReviewMode>>> = [];
      for (const body of [bodyX, bodyY]) {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1t-bodycap-'));
        try {
          const counter = { count: 0 };
          const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
          try {
            const client = {
              rest: {
                users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
                issues: { listComments: async () => ({ data: [] }) },
              },
            } as unknown as NonNullable<RunnerContext['githubClient']>;
            const reviewed = await runIssueReviewMode({
              event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body } },
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
              githubClient: client,
              writeStdout: () => undefined,
            });
            assert.equal(reviewed.verdict, 'INCONCLUSIVE', '8001-char body never approves');
            assert.equal(reviewed.commentsComplete, false, 'body-cap cut is incomplete');
            assert.equal(counter.count, 0, 'body-cap cut makes zero OpenAI calls');
            seen.push(reviewed);
          } finally {
            restore();
          }
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
      assert.notEqual(seen[0].fingerprint, seen[1].fingerprint, 'body tail change alters the full fingerprint');
      assert.equal(seen[0].fingerprint, issueContentFingerprint('t', bodyX, []), 'fingerprint covers the full body');
      assert.equal(seen[1].fingerprint, issueContentFingerprint('t', bodyY, []));
    }

    // P1 #1: comment 2001 chars — tail X vs Y both INCONCLUSIVE, zero AI,
    // distinct full fingerprints.
    {
      const commentX = `${'b'.repeat(2000)}X`;
      const commentY = `${'b'.repeat(2000)}Y`;
      const seen: Array<Awaited<ReturnType<typeof runIssueReviewMode>>> = [];
      for (const commentBody of [commentX, commentY]) {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1t-commentcap-'));
        try {
          const counter = { count: 0 };
          const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
          try {
            const client = {
              rest: {
                users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
                issues: {
                  listComments: async () => ({
                    data: [{ id: 31, body: commentBody, user: { login: 'human', type: 'User' } }],
                  }),
                },
              },
            } as unknown as NonNullable<RunnerContext['githubClient']>;
            const reviewed = await runIssueReviewMode({
              event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' } },
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
              githubClient: client,
              writeStdout: () => undefined,
            });
            assert.equal(reviewed.verdict, 'INCONCLUSIVE', '2001-char comment never approves');
            assert.equal(reviewed.commentsComplete, false, 'comment-cap cut is incomplete');
            assert.equal(counter.count, 0, 'comment-cap cut makes zero OpenAI calls');
            seen.push(reviewed);
          } finally {
            restore();
          }
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
      assert.notEqual(seen[0].fingerprint, seen[1].fingerprint, 'comment tail change alters the full fingerprint');
      // Phase B v2: raw hash binds comment id/updated_at (id 31, no updated_at → null).
      assert.equal(seen[0].fingerprint, issueContentFingerprint('t', 'b', [`human：${commentX}`], { commentIds: [31], commentUpdatedAts: [null] }));
      assert.equal(seen[1].fingerprint, issueContentFingerprint('t', 'b', [`human：${commentY}`], { commentIds: [31], commentUpdatedAts: [null] }));
    }

    // P1 #1: title 2001 chars — tail X vs Y both INCONCLUSIVE, zero AI,
    // distinct full fingerprints.
    {
      const titleX = `${'t'.repeat(2000)}X`;
      const titleY = `${'t'.repeat(2000)}Y`;
      const seen: Array<Awaited<ReturnType<typeof runIssueReviewMode>>> = [];
      for (const title of [titleX, titleY]) {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1t-titlecap-'));
        try {
          const counter = { count: 0 };
          const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
          try {
            const client = {
              rest: {
                users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
                issues: { listComments: async () => ({ data: [] }) },
              },
            } as unknown as NonNullable<RunnerContext['githubClient']>;
            const reviewed = await runIssueReviewMode({
              event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title, body: 'b' } },
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
              githubClient: client,
              writeStdout: () => undefined,
            });
            assert.equal(reviewed.verdict, 'INCONCLUSIVE', '2001-char title never approves');
            assert.equal(reviewed.commentsComplete, false, 'title-cap cut is incomplete');
            assert.equal(counter.count, 0, 'title-cap cut makes zero OpenAI calls');
            seen.push(reviewed);
          } finally {
            restore();
          }
        } finally {
          fs.rmSync(tempDir, { recursive: true, force: true });
        }
      }
      assert.notEqual(seen[0].fingerprint, seen[1].fingerprint, 'title tail change alters the full fingerprint');
      assert.equal(seen[0].fingerprint, issueContentFingerprint(titleX, 'b', []));
      assert.equal(seen[1].fingerprint, issueContentFingerprint(titleY, 'b', []));
    }

    // P1 #1: total context past MAX_ISSUE_CONTEXT_LENGTH drops comments and
    // is fail-closed (INCONCLUSIVE, zero OpenAI). Each comment is exactly at
    // its single-field cap so only the shared budget triggers.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1t-budget-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        try {
          const many = Array.from({ length: 7 }, (_, i) => ({
            id: 3000 + i,
            body: 'd'.repeat(2000),
            user: { login: 'human', type: 'User' },
          }));
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: { listComments: async () => ({ data: many }) },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'c'.repeat(8000) } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.commentsComplete, false, 'budget truncation is incomplete');
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'budget truncation never approves');
          assert.equal(counter.count, 0, 'budget truncation makes zero OpenAI calls');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #1: publish-side tail collision still falls back on the same sticky.
    // A pre-fix artifact hashed the truncated body (X and Y share it); the
    // live issue now carries the Y tail, so the full fingerprint mismatches
    // and publish must not APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-p1t-pubtail-'));
      try {
        const liveY = `${'a'.repeat(8000)}Y`;
        const collidingFp = issueContentFingerprint('t', 'a'.repeat(8000), []);
        const artifactPath = path.join(tempDir, 'colliding.json');
        fs.writeFileSync(artifactPath, JSON.stringify({
          verdict: 'APPROVE',
          issueNumber: 7,
          title: 't',
          tags: [],
          summary: 'pre-fix colliding review',
          suggestedLabels: [],
          fingerprint: collidingFp,
          commentsComplete: true,
        }));
        const state = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        const client = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 't', body: liveY } }),
              listComments: async () => ({ data: state.comments }),
              createComment: async () => { state.created += 1; return {}; },
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
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'stale-webhook' } },
          env: {
            GITHUB_EVENT_NAME: 'issues',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: client,
        });
        assert.equal(state.created, 0, 'tail collision creates no second comment');
        assert.equal(state.updated, 1, 'tail collision updates the same sticky');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'tail collision falls back');
        assert.ok(!state.comments[0].body.includes('判定：APPROVE'), 'tail collision never approves');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // P1 #2 Phase B: raw fingerprint binds secrets (no mask collision).
    // Same-mask pairs hash differently raw, identically redacted (control
    // locking the pre-fix collision).
    {
      const pairs: Array<[string, string, string]> = [
        ['password', 'password: first-secret-AAA', 'password: second-secret-BBB'],
        ['api_key', 'api_key: supersecret123', 'api_key: othersecret456'],
        ['sk', 'sk-live-AAAAAAAAAAAAAAAA', 'sk-live-BBBBBBBBBBBBBBBB'],
        ['gh_p', 'ghp_AAAAAAAAAAAAAAAAAAAA', 'ghp_BBBBBBBBBBBBBBBBBBBB'],
        ['Bearer', 'Bearer abc123XYZ456', 'Bearer different789QQQ'],
      ];
      for (const [label, rawA, rawB] of pairs) {
        const fpA = issueContentFingerprint('t', `body ${rawA}`, []);
        const fpB = issueContentFingerprint('t', `body ${rawB}`, []);
        assert.notEqual(fpA, fpB, `Phase B ${label}: raw hashes differ`);
        const redA = redactForModel(`body ${rawA}`);
        const redB = redactForModel(`body ${rawB}`);
        assert.equal(redA, redB, `Phase B ${label}: redacted masks equal (control)`);
        assert.equal(
          issueContentFingerprint('t', redA, []),
          issueContentFingerprint('t', redB, []),
          `Phase B ${label}: redacted hashes equal (pre-fix collision locked)`,
        );
      }
      // Control-char normalization is shared, masking is not.
      assert.equal(normalizeRawForFingerprint('a\u0000b\u001fc'), 'a b c', 'control normalized without masking');
      assert.equal(normalizeRawForFingerprint('password: keepme'), 'password: keepme', 'raw preserves secret');
      assert.ok(redactForModel('password: keepme').includes('[REDACTED'), 'redacted masks secret');
      // Comment id binding: same body different ids hash differently.
      const fpId1 = issueContentFingerprint('t', 'b', ['human：hi'], { commentIds: [31], commentUpdatedAts: [null] });
      const fpId2 = issueContentFingerprint('t', 'b', ['human：hi'], { commentIds: [32], commentUpdatedAts: [null] });
      assert.notEqual(fpId1, fpId2, 'comment id binds into hash');
      // fetchFreshIssueFields retains id/updated_at when present, omits when absent.
      {
        const withMeta = {
          rest: { issues: { get: async () => ({ data: { number: 7, title: 't', body: 'b', id: 99, updated_at: '2026-01-02T03:04:05Z' } }) } },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        const fresh = await fetchFreshIssueFields(withMeta, 'o', 'r', 7);
        assert.equal(fresh?.id, 99, 'fresh retains issue id');
        assert.equal(fresh?.updatedAt, '2026-01-02T03:04:05Z', 'fresh retains updated_at');
        const withoutMeta = {
          rest: { issues: { get: async () => ({ data: { number: 7, title: 'live', body: 'live body' } }) } },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        assert.deepEqual(await fetchFreshIssueFields(withoutMeta, 'o', 'r', 7), { title: 'live', body: 'live body' });
      }
    }

    // Phase B: deterministic scan eats RAW (order contract in redact.ts).
    {
      const rawSecret = 'ghp_AAAAAAAAAAAAAAAAAAAA';
      assert.equal(scanIssueDeterministicBlock('t', `body ${rawSecret}`, []), true, 'raw ghp BLOCKs');
      assert.equal(scanIssueDeterministicBlock('t', redactForModel(`body ${rawSecret}`), []), false, 'redacted misses (order control)');
      // End-to-end: review with raw ghp BLOCKs to NEEDS_CHANGES (never APPROVE).
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-phaseb-scan-'));
      try {
        const counter = { count: 0 };
        const restore = installOpenAIStub(counter, { verdict: 'APPROVE', summary: 'should not approve', suggestedLabels: [] });
        try {
          const client = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't', body: `body ${rawSecret}` } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          const reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: `body ${rawSecret}` } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
          });
          assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'raw scan BLOCK forces NEEDS_CHANGES');
          const artifactText = fs.readFileSync(path.join(tempDir, 'r.json'), 'utf8');
          assert.ok(!artifactText.includes(rawSecret), 'artifact never persists raw secret');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // Phase B: review(A) → publish live B same mask → INCONCLUSIVE same sticky,
    // label falls back to needs-decision, no raw leak in artifact/report/sticky.
    {
      const secretA = 'password: AlphaSecret123';
      const secretB = 'password: BetaSecret456';
      assert.equal(redactForModel(secretA), redactForModel(secretB), 'same-mask sanity');
      assert.notEqual(
        issueContentFingerprint('t', `body ${secretA}`, []),
        issueContentFingerprint('t', `body ${secretB}`, []),
        'raw differs sanity',
      );
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-phaseb-stale-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const bodyA = `please handle ${secretA} in this issue`;
        const bodyB = `please handle ${secretB} in this issue`;
        // Review with A (APPROVE-happy model, no deterministic BLOCK for password).
        const reviewCounter = { count: 0 };
        const reviewRestore = installOpenAIStub(reviewCounter, { verdict: 'APPROVE', summary: `triage ok ${secretA}`, suggestedLabels: [] });
        let reviewed!: Awaited<ReturnType<typeof runIssueReviewMode>>;
        try {
          const reviewClient = {
            rest: {
              users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
              issues: {
                get: async () => ({ data: { number: 7, title: 't', body: bodyA } }),
                listComments: async () => ({ data: [] }),
              },
            },
          } as unknown as NonNullable<RunnerContext['githubClient']>;
          reviewed = await runIssueReviewMode({
            event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: bodyA } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: reviewClient,
            writeStdout: () => undefined,
          });
        } finally {
          reviewRestore();
        }
        assert.equal(reviewed.verdict, 'APPROVE', 'review A approves (password has no deterministic BLOCK)');
        assert.equal(reviewed.commentsComplete, true);
        assert.match(reviewed.fingerprint, /^[0-9a-f]{64}$/);
        // Artifact must not contain raw secrets (only [REDACTED + hex).
        const artifactText = fs.readFileSync(artifactPath, 'utf8');
        assert.ok(!artifactText.includes('AlphaSecret123'), 'artifact omits raw secret A');
        assert.ok(!artifactText.includes(secretA), 'artifact omits full raw A');
        assert.ok(artifactText.includes(reviewed.fingerprint), 'artifact carries hex fingerprint');
        // Reports must not contain raw secrets.
        const jsonReport = fs.readFileSync(path.join(tempDir, 'review-report.json'), 'utf8');
        const mdReport = fs.readFileSync(path.join(tempDir, 'review-report.md'), 'utf8');
        assert.ok(!jsonReport.includes('AlphaSecret123'), 'JSON report omits raw secret A');
        assert.ok(!mdReport.includes('AlphaSecret123'), 'Markdown report omits raw secret A');
        assert.ok(jsonReport.includes('[REDACTED') || jsonReport.includes(reviewed.fingerprint), 'report carries redaction/hex only');
        // Publish with live B (same mask, different raw) → stale INCONCLUSIVE.
        const publishState = {
          comments: [{ id: 77, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
          labelsAdded: [] as string[][],
        };
        const publishClient = {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              get: async () => ({ data: { number: 7, title: 't', body: bodyB } }),
              listComments: async () => ({ data: publishState.comments }),
              createComment: async () => { publishState.created += 1; return {}; },
              updateComment: async (params: { comment_id: number; body: string }) => {
                publishState.updated += 1;
                const found = publishState.comments.find((c) => c.id === params.comment_id);
                if (found) found.body = params.body;
                return {};
              },
              addLabels: async (params: { labels: string[] }) => { publishState.labelsAdded.push(params.labels); return {}; },
              listLabelsOnIssue: async () => ({ data: [] }),
              removeLabel: async () => ({}),
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        await runPublishMode({
          event: { action: 'opened', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: bodyA } },
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
        assert.equal(publishState.created, 0, 'same-mask stale creates no second comment');
        assert.equal(publishState.updated, 1, 'same-mask stale updates the same sticky');
        assert.equal(publishState.comments[0].id, 77, 'same comment ID updated in place');
        assert.ok(publishState.comments[0].body.includes('判定：INCONCLUSIVE'), 'same-mask stale falls back');
        assert.ok(!publishState.comments[0].body.includes('判定：APPROVE'), 'same-mask stale never approves');
        assert.ok(publishState.comments[0].body.includes('修訂指紋'), 'sticky carries revision fingerprint');
        assert.ok(!publishState.comments[0].body.includes('AlphaSecret123'), 'sticky omits raw secret A');
        assert.ok(!publishState.comments[0].body.includes('BetaSecret456'), 'sticky omits raw secret B');
        assert.ok(!publishState.comments[0].body.includes(secretA) && !publishState.comments[0].body.includes(secretB), 'sticky omits full raws');
        const flatLabels = publishState.labelsAdded.flat();
        assert.ok(flatLabels.includes('status:needs-decision'), 'label falls back to needs-decision');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard stage4-p2 tests] All tests passed.');
}
