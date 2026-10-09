import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  hasUnknownAiLabels,
  issueContentFingerprint,
  runIssueReviewMode,
  runPublishMode,
  runReviewMode,
  runTagMode,
  validateIssueOutput,
  type RunnerContext,
} from '../src/github_runner';
import { triageIssue } from '../src/orchestrator';

const TEST_BASE_URL = ['https:', '', 'pocketguard-openai.test', 'v1'].join('/');
const TEST_ORIGIN = new URL(TEST_BASE_URL).origin;
const BASE_SHA = 'a'.repeat(40);
const HEAD_SHA = 'b'.repeat(40);
const HEAD_SHA_NEW = 'c'.repeat(40);
const REPO = 'sample/repository';

type FakeComment = { id: number; body: string; user: { login: string; type: string } };

interface FakeState {
  comments: FakeComment[];
  created: number;
  updated: number;
  labelsAdded: string[][];
  existingLabels: string[];
  operations: string[];
  permission: string;
  pullRequest: Record<string, unknown>;
  nextId: number;
  updateThrows?: boolean;
}

function defaultPullRequest(headSha: string): Record<string, unknown> {
  return {
    number: 41,
    base: { sha: BASE_SHA },
    head: { sha: headSha, repo: { full_name: REPO } },
    user: { login: 'pr-author' },
  };
}

function makeState(overrides: Partial<FakeState> = {}): FakeState {
  return {
    comments: [],
    created: 0,
    updated: 0,
    labelsAdded: [],
    existingLabels: [],
    operations: [],
    permission: 'write',
    pullRequest: defaultPullRequest(HEAD_SHA),
    nextId: 100,
    ...overrides,
  };
}

function makeClient(state: FakeState): NonNullable<RunnerContext['githubClient']> {
  return {
    rest: {
      pulls: {
        get: async () => {
          state.operations.push('pulls-get');
          return { data: state.pullRequest };
        },
      },
      users: {
        getAuthenticated: async () => {
          state.operations.push('authenticated-user');
          return { data: { login: 'pocketguard[bot]' } };
        },
      },
      repos: {
        getCollaboratorPermissionLevel: async () => {
          state.operations.push('permission-check');
          return { data: { permission: state.permission } };
        },
      },
      issues: {
        listComments: async () => {
          state.operations.push('list-comments');
          return { data: state.comments };
        },
        createComment: async (params: { body: string }) => {
          state.operations.push('create-comment');
          state.created += 1;
          state.nextId += 1;
          state.comments.push({ id: state.nextId, body: params.body, user: { login: 'pocketguard[bot]', type: 'Bot' } });
          return {};
        },
        updateComment: async (params: { comment_id: number; body: string }) => {
          state.operations.push('update-comment');
          if (state.updateThrows) throw new Error('synthetic update failure');
          state.updated += 1;
          const comment = state.comments.find((c) => c.id === params.comment_id);
          if (comment) comment.body = params.body;
          return {};
        },
        addLabels: async (params: { labels: string[] }) => {
          state.operations.push('add-labels');
          state.labelsAdded.push(params.labels);
          for (const label of params.labels) {
            if (!state.existingLabels.includes(label)) state.existingLabels.push(label);
          }
          return {};
        },
        listLabelsOnIssue: async () => {
          state.operations.push('list-labels');
          return { data: state.existingLabels.map((name) => ({ name })) };
        },
        removeLabel: async (params: { name: string }) => {
          state.operations.push('remove-label');
          state.existingLabels = state.existingLabels.filter((l) => l !== params.name);
          return {};
        },
      },
    },
  } as unknown as NonNullable<RunnerContext['githubClient']>;
}

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

function installCountingOpenAI(counter: { count: number }, payload: unknown): () => void {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => {
    counter.count += 1;
    return new Response(JSON.stringify({
      output: [{ content: [{ type: 'output_text', text: JSON.stringify(payload) }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }) as typeof fetch;
  return () => { globalThis.fetch = previousFetch; };
}

function prOpenedEvent(): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: REPO },
    pull_request: {
      number: 41,
      title: 'security: validate capability boundary',
      base: { sha: BASE_SHA, ref: 'main' },
      head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: REPO } },
    },
  };
}

function prCommentEvent(body: string, login: string): Record<string, unknown> {
  return {
    action: 'created',
    repository: { full_name: REPO },
    issue: { number: 41, pull_request: { url: 'unused' }, title: 'topic' },
    comment: { body, user: { login, type: 'User' } },
  };
}

function issueOpenedEvent(): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: REPO },
    issue: { number: 7, title: 'security: token handling looks wrong', body: 'Steps to reproduce...' },
  };
}

export async function runStickyLabelTests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    // S5 AI label schema strictness: only string arrays pass.
    assert.equal(hasUnknownAiLabels(['security', 'area:runtime']), false);
    assert.equal(hasUnknownAiLabels(['policy']), false, 'synonym policy normalizes to area:policy');
    assert.equal(hasUnknownAiLabels(['alien-label']), true);
    assert.equal(hasUnknownAiLabels(['security', 'alien']), true);
    assert.equal(issueContentFingerprint('t', 'b', []).length, 64);
    assert.notEqual(issueContentFingerprint('t', 'b', ['a']), issueContentFingerprint('t', 'b', ['b']));

    // validateIssueOutput requires fingerprint + strict suggestedLabels.
    assert.equal(validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's' }), undefined, 'missing fingerprint rejects');
    assert.equal(validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's', suggestedLabels: 'bad', fingerprint: '0'.repeat(64) }), undefined, 'non-array suggestions reject');
    assert.equal(validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's', suggestedLabels: [123], fingerprint: '0'.repeat(64) }), undefined, 'non-string entries reject');
    assert.equal(validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: [], summary: 's', suggestedLabels: [], fingerprint: 'short' }), undefined, 'short fingerprint rejects');

    // Orchestrator triage strictness: bad suggestedLabels schema falls back.
    {
      const badFetch = globalThis.fetch;
      globalThis.fetch = (async () => new Response(JSON.stringify({
        output: [{ content: [{ type: 'output_text', text: JSON.stringify({ verdict: 'APPROVE', summary: 'ok', suggestedLabels: 'not-an-array' }) }] }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch;
      try {
        const triaged = await triageIssue({
          input: { title: 't', body: 'b', comments: [] },
          env: openAiEnv(),
          allowedOrigins: [TEST_ORIGIN],
        });
        assert.equal(triaged.verdict, 'INCONCLUSIVE', 'bad triage schema falls back');
        assert.deepEqual(triaged.suggestedLabels, []);
      } finally {
        globalThis.fetch = badFetch;
      }
    }

    // PR same-SHA lifecycle with valid AI labels: first two AI, third zero,
    // one comment ID, only first createComment. New SHA reuses the same ID.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-pr-'));
      try {
        const state = makeState({ existingLabels: ['bug'] });
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: ['performance'] });
        try {
          const outputPath = path.join(tempDir, 'review.json');
          const runFullPass = async () => {
            const tagEnv = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
            const tagged = await runTagMode({
              event: prCommentEvent('/review', 'maintainer'),
              env: tagEnv,
              githubClient: makeClient(state),
              writeStdout: () => undefined,
              runGit: safeGitStub(),
            });
            const before = counter.count;
            const reviewed = await runReviewMode({
              event: prCommentEvent('/review', 'maintainer'),
              env: openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment', POCKETGUARD_OUTPUT: outputPath }),
              githubClient: makeClient(state),
              writeStdout: () => undefined,
              runGit: safeGitStub(),
            });
            await runPublishMode({
              event: prCommentEvent('/review', 'maintainer'),
              env: {
                GITHUB_EVENT_NAME: 'issue_comment',
                GITHUB_REPOSITORY: REPO,
                GITHUB_TOKEN: 'fake-token',
                POCKETGUARD_OUTPUT: outputPath,
                POCKETGUARD_REVIEW_JOB_RESULT: 'success',
                POCKETGUARD_TAG_LABELS: JSON.stringify(tagged.labels),
              } as NodeJS.ProcessEnv,
              githubClient: makeClient(state),
            });
            return { tagged, reviewed, made: counter.count - before };
          };
          const first = await runFullPass();
          assert.ok(first.made > 0);
          assert.equal(state.created, 1, 'first PR review creates the sticky');
          assert.equal(state.updated, 0);
          const stickyId = state.comments[0].id;
          assert.ok(state.comments[0].body.includes('<!-- PocketGuard-review -->'));
          assert.ok(state.comments[0].body.includes('判定：APPROVE'));
          assert.ok(state.comments[0].body.includes(BASE_SHA.slice(0, 8)), 'PR sticky carries base SHA');
          assert.ok(state.comments[0].body.includes(HEAD_SHA.slice(0, 8)), 'PR sticky carries head SHA');
          assert.ok(state.existingLabels.includes('performance'), 'valid AI label is applied');
          assert.ok(state.existingLabels.includes('bug'), 'manual labels survive reconciliation');

          const second = await runFullPass();
          assert.ok(second.made > 0);
          assert.equal(state.created, 1, 'second PR review does not create');
          assert.equal(state.updated, 1, 'second PR review updates the same sticky');
          assert.equal(state.comments[0].id, stickyId, 'same comment ID');

          const third = await runFullPass();
          assert.equal(third.made, 0, 'third review on the same SHA makes zero AI calls');
          assert.equal(third.tagged.reviewGate, 'none');
          assert.equal(state.created, 1);
          assert.equal(state.updated, 1, 'quota-exhausted publish touches nothing');
          assert.equal(state.comments[0].id, stickyId);

          state.pullRequest = defaultPullRequest(HEAD_SHA_NEW);
          const fresh = await runFullPass();
          assert.ok(fresh.made > 0, 'new SHA restarts the budget');
          assert.equal(state.comments[0].id, stickyId, 'new SHA reuses the same sticky ID');
          assert.ok(state.comments[0].body.includes(HEAD_SHA_NEW.slice(0, 8)));
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // PR illegal AI labels: unknown entries never written, manual kept,
    // verdict never APPROVE, no second comment.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-illegal-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: ['alien-label', 'area:runtime'] });
        let reviewed: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const state = makeState();
          reviewed = await runReviewMode({
            event: prOpenedEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
        } finally {
          restore();
        }
        assert.notEqual(reviewed!.verdict, 'APPROVE', 'unknown AI labels downgrade review verdict');
        // Publish a crafted APPROVE artifact carrying unknown labels: must
        // fall back to INCONCLUSIVE on the same sticky without writing them.
        const crafted = { ...(reviewed as object), verdict: 'APPROVE', suggestedLabels: ['alien-label'] };
        // Recompute a valid APPROVE artifact shape would fail verdict math;
        // instead craft a minimal valid APPROVE then inject unknown labels
        // via a direct publish with a valid base artifact.
        const goodCounter = { count: 0 };
        const restoreGood = installCountingOpenAI(goodCounter, { verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] });
        let goodArtifact: unknown;
        try {
          const s = makeState();
          const r = await runReviewMode({
            event: prOpenedEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'good.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(s),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          goodArtifact = JSON.parse(fs.readFileSync(path.join(tempDir, 'good.json'), 'utf8'));
          void r;
        } finally {
          restoreGood();
        }
        const tampered = { ...(goodArtifact as Record<string, unknown>), suggestedLabels: ['alien-label'] };
        const tamperedPath = path.join(tempDir, 'tampered.json');
        fs.writeFileSync(tamperedPath, JSON.stringify(tampered));
        const publishState = makeState({
          existingLabels: ['bug', 'area:docs'],
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        await runPublishMode({
          event: prOpenedEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: tamperedPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(publishState),
        });
        assert.equal(publishState.created, 0, 'illegal AI publish creates no second comment');
        assert.equal(publishState.updated, 1, 'illegal AI publish updates the same sticky');
        assert.equal(publishState.comments.length, 1);
        assert.ok(publishState.comments[0].body.includes('判定：INCONCLUSIVE'), 'illegal AI forces non-APPROVE');
        assert.ok(!publishState.existingLabels.includes('alien-label'), 'unknown labels never written');
        assert.ok(publishState.existingLabels.includes('bug'), 'manual labels preserved');
        void crafted;
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // PR bad-schema suggestedLabels artifact: invalid schema falls back on
    // the same sticky.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-badschema-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] });
        let goodArtifact: unknown;
        try {
          const s = makeState();
          await runReviewMode({
            event: prOpenedEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'good.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(s),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          goodArtifact = JSON.parse(fs.readFileSync(path.join(tempDir, 'good.json'), 'utf8'));
        } finally {
          restore();
        }
        const bad = { ...(goodArtifact as Record<string, unknown>), suggestedLabels: 'not-an-array' };
        const badPath = path.join(tempDir, 'bad.json');
        fs.writeFileSync(badPath, JSON.stringify(bad));
        const publishState = makeState({
          existingLabels: ['bug'],
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        await runPublishMode({
          event: prOpenedEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: badPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(publishState),
        });
        assert.equal(publishState.created, 0);
        assert.equal(publishState.updated, 1);
        assert.ok(publishState.comments[0].body.includes('判定：INCONCLUSIVE'));
        assert.ok(publishState.existingLabels.includes('bug'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // PR deterministic BLOCK cannot be cleared by AI (empty suggestions).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-block-'));
      try {
        const fakeSecretParts = ['const fakeSecret = "ghp_', `${'A'.repeat(20)}";`];
        const blockGit = (args: string[]): string => {
          if (args[0] === 'fetch') return '';
          if (args[0] === 'merge-base') return BASE_SHA;
          if (args[0] === 'diff' && args[1] === '--name-only') return 'app/src/main/java/demo/Secret.kt\0';
          return [
            'diff --git a/app/src/main/java/demo/Secret.kt b/app/src/main/java/demo/Secret.kt',
            '--- a/app/src/main/java/demo/Secret.kt',
            '+++ b/app/src/main/java/demo/Secret.kt',
            '@@ -1,0 +1,2 @@',
            `+${fakeSecretParts[0]}`,
            `+${fakeSecretParts[1]}`,
          ].join('\n');
        };
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] });
        try {
          const state = makeState({ existingLabels: ['bug'] });
          const outputPath = path.join(tempDir, 'block.json');
          const reviewed = await runReviewMode({
            event: prOpenedEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: outputPath } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: blockGit,
          });
          assert.equal(reviewed.verdict, 'NEEDS_CHANGES');
          assert.equal(counter.count, 0, 'deterministic BLOCK makes zero AI calls');
          await runPublishMode({
            event: prOpenedEvent(),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
          assert.ok(state.existingLabels.includes('security'), 'deterministic security survives empty AI suggestions');
          assert.ok(state.existingLabels.includes('status:needs-decision'));
          assert.ok(state.existingLabels.includes('bug'));
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // PR API edit error: update failure throws without a second comment and
    // without label writes.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-editerr-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] });
        try {
          const s = makeState();
          await runReviewMode({
            event: prOpenedEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(s),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
        } finally {
          restore();
        }
        const publishState = makeState({
          updateThrows: true,
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        await assert.rejects(
          runPublishMode({
            event: prOpenedEvent(),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json'),
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(publishState),
          }),
          /failed to publish review comment/,
        );
        assert.equal(publishState.created, 0, 'edit error creates no second comment');
        assert.equal(publishState.comments.length, 1);
        assert.ok(!publishState.operations.includes('add-labels') && !publishState.operations.includes('list-labels'), 'edit error blocks label writes');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // Issue opened -> edited -> human comment share one sticky; bot comment
    // makes zero AI calls and publishes nothing.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-issue-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'looks fine', suggestedLabels: ['area:docs'] });
        try {
          const state = makeState({ existingLabels: ['bug'] });
          const clientFor = () => makeClient(state);
          const events = [
            { name: 'opened', event: issueOpenedEvent(), eventName: 'issues' },
            { name: 'edited', event: { action: 'edited', repository: { full_name: REPO }, issue: { number: 7, title: 'security: token handling looks wrong', body: 'Steps to reproduce...' } }, eventName: 'issues' },
            { name: 'comment', event: { action: 'created', repository: { full_name: REPO }, issue: { number: 7, title: 'security: token handling looks wrong', body: 'Steps to reproduce...' }, comment: { body: 'extra human context', user: { login: 'human', type: 'User' } } }, eventName: 'issue_comment' },
          ];
          let aiBefore = counter.count;
          for (const [index, entry] of events.entries()) {
            const outputPath = path.join(tempDir, `issue-${entry.name}.json`);
            const reviewed = await runIssueReviewMode({
              event: entry.event,
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: entry.eventName }), POCKETGUARD_OUTPUT: outputPath } as NodeJS.ProcessEnv,
              githubClient: clientFor(),
              writeStdout: () => undefined,
            });
            assert.ok(counter.count - aiBefore > 0, `issue ${entry.name} reaches AI`);
            aiBefore = counter.count;
            await runPublishMode({
              event: entry.event,
              env: {
                GITHUB_EVENT_NAME: entry.eventName,
                GITHUB_REPOSITORY: REPO,
                GITHUB_TOKEN: 'fake-token',
                POCKETGUARD_OUTPUT: outputPath,
                POCKETGUARD_REVIEW_JOB_RESULT: 'success',
                POCKETGUARD_TAG_LABELS: '[]',
              } as NodeJS.ProcessEnv,
              githubClient: clientFor(),
            });
            void reviewed;
            if (index === 0) assert.equal(state.created, 1, 'issue opened creates the sticky');
            else assert.equal(state.created, 1, `issue ${entry.name} creates no second comment`);
          }
          assert.equal(state.comments.length, 1, 'opened/edited/comment share one sticky');
          assert.equal(state.updated, 2, 'edited + comment update the same sticky');
          assert.ok(state.comments[0].body.includes('<!-- PocketGuard-review -->'));
          assert.ok(state.comments[0].body.includes('修訂指紋'));
          assert.ok(state.existingLabels.includes('security'), 'deterministic title label applied');
          assert.ok(state.existingLabels.includes('area:docs'), 'valid AI label applied');
          assert.ok(state.existingLabels.includes('bug'), 'manual label preserved');

          // Bot comment: zero AI, zero writes.
          const botAiBefore = counter.count;
          const botReviewed = await runIssueReviewMode({
            event: { action: 'created', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' }, comment: { body: 'bot note', user: { login: 'x[bot]', type: 'Bot' } } },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'bot.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
          });
          assert.equal(botReviewed.verdict, 'INCONCLUSIVE');
          assert.equal(counter.count - botAiBefore, 0, 'bot issue comment makes zero AI calls');
          const botPublishState = makeState({ comments: [...state.comments] });
          const botStickyBefore = botPublishState.comments[0].body;
          await runPublishMode({
            event: { action: 'created', repository: { full_name: REPO }, issue: { number: 7, title: 't', body: 'b' }, comment: { body: 'bot note', user: { login: 'x[bot]', type: 'Bot' } } },
            env: {
              GITHUB_EVENT_NAME: 'issue_comment',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: path.join(tempDir, 'bot.json'),
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(botPublishState),
          });
          assert.equal(botPublishState.created, 0, 'bot comment publish creates nothing');
          assert.equal(botPublishState.updated, 0, 'bot comment publish updates nothing');
          assert.equal(botPublishState.comments[0].body, botStickyBefore);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // Issue stale fingerprint + invalid artifact + edit error: same sticky,
    // no second comment.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-issue-stale-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: [] });
        const outputPath = path.join(tempDir, 'issue.json');
        try {
          await runIssueReviewMode({
            event: issueOpenedEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: outputPath } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
          });
        } finally {
          restore();
        }
        const good = JSON.parse(fs.readFileSync(outputPath, 'utf8')) as Record<string, unknown>;
        // Stale fingerprint.
        const stalePath = path.join(tempDir, 'stale.json');
        fs.writeFileSync(stalePath, JSON.stringify({ ...good, fingerprint: 'f'.repeat(64) }));
        const staleState = makeState({ comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }] });
        await runPublishMode({
          event: issueOpenedEvent(),
          env: { GITHUB_EVENT_NAME: 'issues', GITHUB_REPOSITORY: REPO, GITHUB_TOKEN: 'fake-token', POCKETGUARD_OUTPUT: stalePath, POCKETGUARD_REVIEW_JOB_RESULT: 'success', POCKETGUARD_TAG_LABELS: '[]' } as NodeJS.ProcessEnv,
          githubClient: makeClient(staleState),
        });
        assert.equal(staleState.created, 0);
        assert.equal(staleState.updated, 1);
        assert.equal(staleState.comments.length, 1);
        assert.ok(staleState.comments[0].body.includes('判定：INCONCLUSIVE'));

        // Invalid schema (missing fingerprint).
        const invalidPath = path.join(tempDir, 'invalid.json');
        const { fingerprint: _dropped, ...withoutPrint } = good as Record<string, unknown>;
        void _dropped;
        fs.writeFileSync(invalidPath, JSON.stringify(withoutPrint));
        const invalidState = makeState({ comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }] });
        await runPublishMode({
          event: issueOpenedEvent(),
          env: { GITHUB_EVENT_NAME: 'issues', GITHUB_REPOSITORY: REPO, GITHUB_TOKEN: 'fake-token', POCKETGUARD_OUTPUT: invalidPath, POCKETGUARD_REVIEW_JOB_RESULT: 'success', POCKETGUARD_TAG_LABELS: '[]' } as NodeJS.ProcessEnv,
          githubClient: makeClient(invalidState),
        });
        assert.equal(invalidState.created, 0);
        assert.equal(invalidState.updated, 1);
        assert.ok(invalidState.comments[0].body.includes('判定：INCONCLUSIVE'));

        // Edit error.
        const errState = makeState({ updateThrows: true, comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }] });
        await assert.rejects(
          runPublishMode({
            event: issueOpenedEvent(),
            env: { GITHUB_EVENT_NAME: 'issues', GITHUB_REPOSITORY: REPO, GITHUB_TOKEN: 'fake-token', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_REVIEW_JOB_RESULT: 'success', POCKETGUARD_TAG_LABELS: '[]' } as NodeJS.ProcessEnv,
            githubClient: makeClient(errState),
          }),
          /failed to publish review comment/,
        );
        assert.equal(errState.created, 0, 'issue edit error creates no second comment');
        assert.equal(errState.comments.length, 1);
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // Issue illegal AI labels: unknown discarded, manual kept, non-APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-s5-issue-illegal-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', suggestedLabels: ['alien-label'] });
        let reviewed: Awaited<ReturnType<typeof runIssueReviewMode>>;
        try {
          reviewed = await runIssueReviewMode({
            event: issueOpenedEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDir, 'r.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
          });
        } finally {
          restore();
        }
        assert.notEqual(reviewed!.verdict, 'APPROVE', 'issue unknown AI labels force non-APPROVE');
        assert.ok(!reviewed!.tags.includes('alien-label'));
        assert.ok(reviewed!.tags.includes('security'), 'deterministic issue label survives');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // Workflow wiring: publish allows human issue updates (including
    // issue_comment on issues) while bots still skip via route ignore.
    {
      const workflow = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/pocketguard.yml'), 'utf8');
      const publishJob = workflow.slice(workflow.indexOf('  publish:'));
      const condition = publishJob.slice(0, publishJob.indexOf('    steps:')).replace(/\s+/g, ' ');
      assert.match(condition, /always\(\)/, 'publish still runs on review failure');
      assert.match(condition, /needs\.prepare-tag\.outputs\.authorized\s*==\s*'true'/, 'PR manual reviews still need authorization');
      assert.match(condition, /needs\.prepare-tag\.outputs\.target\s*==\s*'issue'/, 'issue target admitted to publish');
      assert.match(condition, /needs\.prepare-tag\.outputs\.should_review\s*==\s*'true'/, 'human issue updates admitted');
      assert.match(condition, /needs\.prepare-tag\.outputs\.route_kind\s*!=\s*'ignore'/, 'bot-routed ignores still skip');
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard sticky-label tests] All tests passed.');
}
