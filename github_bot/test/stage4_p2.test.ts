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
  runIssueReviewMode,
  runPublishMode,
  runReviewMode,
  runTagMode,
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
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard stage4-p2 tests] All tests passed.');
}
