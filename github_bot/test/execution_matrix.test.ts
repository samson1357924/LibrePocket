import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  buildForkFetchRefspec,
  fetchForkReviewCommits,
  isAllowedForkFetchRefspec,
  MAX_ISSUE_CONTEXT_LENGTH,
  MAX_REVIEWS_PER_SHA,
  formatReviewCountMarker,
  parseReviewCountMarker,
  readStickyReviewCount,
  resolveReviewGate,
  runClaimMode,
  runIssueReviewMode,
  runPublishMode,
  runReviewEntryMode,
  runReviewMode,
  runTagMode,
  validateIssueOutput,
  type RunnerContext,
} from '../src/github_runner';

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
  permissionThrows?: string;
  pullRequest: Record<string, unknown>;
  pullsGetThrows?: boolean;
  nextId: number;
}

function defaultPullRequest(headSha: string, headRepo = REPO): Record<string, unknown> {
  return {
    number: 41,
    base: { sha: BASE_SHA },
    head: { sha: headSha, repo: { full_name: headRepo } },
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
          if (state.pullsGetThrows) throw new Error('synthetic pulls failure');
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
          if (state.permissionThrows) throw new Error(state.permissionThrows);
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
          state.comments.push({
            id: state.nextId,
            body: params.body,
            user: { login: 'pocketguard[bot]', type: 'Bot' },
          });
          return {};
        },
        updateComment: async (params: { comment_id: number; body: string }) => {
          state.operations.push('update-comment');
          state.updated += 1;
          const comment = state.comments.find((candidate) => candidate.id === params.comment_id);
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
          state.existingLabels = state.existingLabels.filter((label) => label !== params.name);
          return {};
        },
      },
    },
  } as unknown as NonNullable<RunnerContext['githubClient']>;
}

function stickyBody(headSha: string, count: number): string {
  return `<!-- PocketGuard-review -->\n\n## PocketGuard 審查\n\n**判定：APPROVE**\n${formatReviewCountMarker(headSha, count)}\n`;
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

function throwingGitStub(): (args: string[]) => string {
  return () => { throw new Error('git must not run on a denied path'); };
}

// P1 #1 fork stub: simulates the safe fork fetch (`git fetch origin <base>`
// then `git fetch origin +refs/pull/<N>/head`), the FETCH_HEAD SHA-pin
// (`git rev-parse FETCH_HEAD` -> expected head SHA), plus merge-base and a
// one-file diff. Records every invocation so tests can assert the fetch
// allowlist (only base SHA + the exact PR refspec) and zero execution
// (never checkout/switch/clone/reset to fork code).
function forkGitStub(
  calls: string[][],
  opts: { revParseSha?: string; fetchThrows?: boolean; mergeBaseThrows?: boolean } = {},
): (args: string[]) => string {
  return (args: string[]) => {
    calls.push(args);
    if (args[0] === 'fetch') {
      if (opts.fetchThrows) throw new Error('synthetic fork fetch failure');
      return '';
    }
    if (args[0] === 'rev-parse') return `${opts.revParseSha ?? HEAD_SHA}\n`;
    if (args[0] === 'merge-base') {
      if (opts.mergeBaseThrows) throw new Error('synthetic merge-base failure');
      return BASE_SHA;
    }
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

function assertZeroExecution(gitCalls: string[][]): void {
  for (const args of gitCalls) {
    assert.ok(!args.includes('checkout'), `no git checkout of fork code (got ${JSON.stringify(args)})`);
    assert.ok(!args.includes('switch'), `no git switch to fork code (got ${JSON.stringify(args)})`);
    assert.ok(!args.includes('clone'), `no git clone of fork code (got ${JSON.stringify(args)})`);
    assert.ok(!args.includes('reset'), `no git reset to fork code (got ${JSON.stringify(args)})`);
  }
}

function assertForkFetchAllowlist(gitCalls: string[][], prNumber: number): void {
  const fetches = gitCalls.filter((args) => args[0] === 'fetch');
  assert.ok(fetches.length >= 2, `fork path fetches base plus PR ref (got ${fetches.length} fetches)`);
  const allowed = new Set([BASE_SHA, buildForkFetchRefspec(prNumber)]);
  for (const fetch of fetches) {
    assert.deepEqual(fetch.slice(0, 3), ['fetch', '--no-tags', 'origin']);
    assert.ok(!fetch.some((arg) => arg.startsWith('--depth')), 'no shallow fetch');
    for (const source of fetch.slice(3)) {
      assert.ok(allowed.has(source), `fetch source allowlisted (got ${source})`);
    }
  }
  assert.ok(
    fetches.some((fetch) => fetch.includes(buildForkFetchRefspec(prNumber))),
    'fork fetch uses the explicit +refs/pull/<N>/head refspec',
  );
  assert.ok(
    gitCalls.some((args) => args[0] === 'rev-parse' && args.includes('FETCH_HEAD')),
    'fork fetch is SHA-pinned via FETCH_HEAD before any OpenAI call',
  );
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

function prOpenedEvent(headRepo = REPO): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: REPO },
    pull_request: {
      number: 41,
      title: 'security: validate capability boundary',
      base: { sha: BASE_SHA, ref: 'main' },
      head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: headRepo } },
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

export async function runExecutionMatrixTests(): Promise<void> {
  // Counter marker round-trip.
  assert.equal(formatReviewCountMarker(HEAD_SHA, 1), `<!-- PocketGuard-reviews:${HEAD_SHA}:1 -->`);
  assert.equal(parseReviewCountMarker(stickyBody(HEAD_SHA, 2), HEAD_SHA), 2);
  assert.equal(parseReviewCountMarker(stickyBody(HEAD_SHA, 2), HEAD_SHA_NEW), 0, 'another SHA reads zero');
  assert.equal(parseReviewCountMarker('<!-- PocketGuard-review --> no marker', HEAD_SHA), 0, 'missing marker reads zero');
  assert.equal(parseReviewCountMarker(undefined, HEAD_SHA), 0);
  assert.equal(parseReviewCountMarker(stickyBody(HEAD_SHA, 2), 'not-a-sha'), 0);

  // Gate matrix (PR gates only; issue targets are forced to none in tag mode
  // and scheduled via the explicit issue-auto route instead).
  assert.equal(resolveReviewGate('first-review', true), 'auto');
  assert.equal(resolveReviewGate('owner-commit', true), 'auto');
  assert.equal(resolveReviewGate('manual-pr-review', true), 'manual');
  assert.equal(resolveReviewGate('first-review', false), 'none');
  assert.equal(resolveReviewGate('issue-update', true), 'none', 'issue-update carries no PR gate; scheduling uses the issue-auto route');
  assert.equal(resolveReviewGate('ignore', false), 'none');

  // reviews_used reads through the real helper: missing client and bad SHA are unknown (fail-closed).
  assert.deepEqual(await readStickyReviewCount(undefined, 'sample', 'repository', 41, HEAD_SHA), { ok: false });
  const emptyState = makeState();
  assert.deepEqual(
    await readStickyReviewCount(makeClient(emptyState), 'sample', 'repository', 41, HEAD_SHA),
    { ok: true, used: 0 },
    'no sticky comment reads ok/zero (first review proceeds)',
  );

  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    const counter = { count: 0 };
    const restoreOpenAI = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });

    const tagFor = async (
      name: string,
      event: Record<string, unknown>,
      env: NodeJS.ProcessEnv,
      state: FakeState,
      runGit?: (args: string[]) => string,
    ) => {
      let tagStdout = '';
      const tagged = await runTagMode({
        event,
        env,
        githubClient: makeClient(state),
        writeStdout: (value) => { tagStdout += value; },
        runGit: runGit ?? throwingGitStub(),
      });
      void name;
      return { tagged, tagStdout };
    };

    // First review, same repo: auto gate, no commenter check, AI runs.
    {
      const state = makeState();
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' });
      const { tagged, tagStdout } = await tagFor('first-same-repo', prOpenedEvent(), env, state, safeGitStub());
      assert.equal(tagged.routeKind, 'first-review');
      assert.equal(tagged.reviewGate, 'auto');
      assert.equal(tagged.reviewsUsed, 0);
      assert.equal(tagged.shouldReview, true);
      assert.match(tagStdout, /"reviewGate":"auto"/);
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prOpenedEvent(),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.ok(counter.count - before > 0, 'first same-repo review reaches OpenAI');
    }

    // First review, fork: auto gate (no commenter check), readable diff via
    // the pinned PR-ref fetch — reaches OpenAI with zero execution (the
    // checkout stays on the default branch; fork code is only read as a diff).
    {
      const state = makeState();
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' });
      const tagCalls: string[][] = [];
      const { tagged } = await tagFor('first-fork', prOpenedEvent('untrusted/fork'), env, state, forkGitStub(tagCalls));
      assert.equal(tagged.routeKind, 'first-review');
      assert.equal(tagged.reviewGate, 'auto');
      assert.equal(tagged.safeReview, false, 'safeReview stays same-repo-only');
      assert.equal(tagged.diffSafe, true, 'fork first review has a safely readable diff');
      assert.ok(tagged.changedFiles.length > 0, 'fork tag resolves changed paths via the pinned fetch');
      assertForkFetchAllowlist(tagCalls, 41);
      assertZeroExecution(tagCalls);
      const reviewCalls: string[][] = [];
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prOpenedEvent('untrusted/fork'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: forkGitStub(reviewCalls),
      });
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.ok(counter.count - before > 0, 'fork first review reaches OpenAI');
      assertForkFetchAllowlist(reviewCalls, 41);
      assertZeroExecution(reviewCalls);
    }

    // Fork fetch/merge-base failure fails closed: INCONCLUSIVE, zero OpenAI.
    {
      const state = makeState();
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' });
      for (const opts of [{ fetchThrows: true }, { mergeBaseThrows: true }]) {
        const name = opts.fetchThrows ? 'fork-fetch-throws' : 'fork-merge-base-throws';
        const before = counter.count;
        const reviewed = await runReviewMode({
          event: prOpenedEvent('untrusted/fork'),
          env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
          runGit: forkGitStub([], opts),
        });
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', `${name}: review fails closed`);
        assert.equal(counter.count - before, 0, `${name}: zero OpenAI calls`);
      }
    }

    // Fork SHA-pin mismatch (TOCTOU push / wrong ref) fails closed: the
    // fetched head does not equal the expected head SHA, so INCONCLUSIVE
    // with zero OpenAI calls and no diff processing.
    {
      const state = makeState();
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' });
      const pinCalls: string[][] = [];
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prOpenedEvent('untrusted/fork'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: forkGitStub(pinCalls, { revParseSha: 'd'.repeat(40) }),
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'SHA-pin mismatch fails closed');
      assert.equal(counter.count - before, 0, 'SHA-pin mismatch makes zero OpenAI calls');
      assertZeroExecution(pinCalls);
    }

    // Fork fetch refspec allowlist: only the base SHA and exactly
    // `+refs/pull/<N>/head` are ever fetched; anything else is rejected.
    {
      assert.equal(buildForkFetchRefspec(41), '+refs/pull/41/head');
      assert.equal(isAllowedForkFetchRefspec('+refs/pull/41/head', 41), true);
      assert.equal(isAllowedForkFetchRefspec('+refs/pull/42/head', 41), false, 'wrong PR number rejected');
      assert.equal(isAllowedForkFetchRefspec('+refs/pull/41/merge', 41), false, 'merge ref rejected');
      assert.equal(isAllowedForkFetchRefspec('refs/pull/41/head', 41), false, 'missing + rejected');
      assert.equal(isAllowedForkFetchRefspec('+refs/heads/main', 41), false, 'branch ref rejected');
      assert.equal(isAllowedForkFetchRefspec(HEAD_SHA, 41), false, 'bare head SHA is not a refspec');
      assert.equal(isAllowedForkFetchRefspec('+refs/pull/41/head;evil', 41), false, 'injection rejected');
      assert.throws(() => buildForkFetchRefspec(0), /invalid pull request number/);
      assert.throws(() => buildForkFetchRefspec(Number.NaN), /invalid pull request number/);
      assert.throws(
        () => fetchForkReviewCommits(() => '', BASE_SHA, HEAD_SHA, 0),
        /invalid pull request number/,
      );
      assert.throws(
        () => fetchForkReviewCommits(() => '', 'not-a-sha', HEAD_SHA, 41),
        /invalid SHAs/,
      );
    }

    // Non-owner synchronize on the same repo: routed ignore, zero OpenAI
    // calls and zero git calls even though the origin check would pass.
    {
      const state = makeState();
      const env = openAiEnv({
        GITHUB_EVENT_NAME: 'pull_request_target',
        GITHUB_ACTOR: 'contributor',
        POCKETGUARD_REPO_OWNER: 'sample',
      });
      const syncEvent = {
        action: 'synchronize',
        repository: { full_name: REPO },
        pull_request: {
          number: 41,
          title: 't',
          base: { sha: BASE_SHA, ref: 'main' },
          head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: REPO } },
        },
        sender: { login: 'contributor', type: 'User' },
      };
      const { tagged } = await tagFor('non-owner-sync', syncEvent, env, state);
      assert.equal(tagged.routeKind, 'ignore');
      assert.equal(tagged.reviewGate, 'none');
      assert.equal(tagged.shouldReview, false);
      let gitCalls = 0;
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: syncEvent,
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: () => { gitCalls += 1; return ''; },
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE');
      assert.equal(counter.count - before, 0, 'non-owner synchronize makes zero OpenAI calls');
      assert.equal(gitCalls, 0, 'non-owner synchronize runs zero git commands');
    }

    // Owner synchronize: owner-commit, auto gate, AI runs.
    {
      const state = makeState();
      const env = openAiEnv({
        GITHUB_EVENT_NAME: 'pull_request_target',
        GITHUB_ACTOR: 'sample',
        POCKETGUARD_REPO_OWNER: 'sample',
      });
      const syncEvent = {
        action: 'synchronize',
        repository: { full_name: REPO },
        pull_request: {
          number: 41,
          title: 't',
          base: { sha: BASE_SHA, ref: 'main' },
          head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: REPO } },
        },
        sender: { login: 'sample', type: 'User' },
      };
      const { tagged } = await tagFor('owner-sync', syncEvent, env, state, safeGitStub());
      assert.equal(tagged.routeKind, 'owner-commit');
      assert.equal(tagged.reviewGate, 'auto');
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: syncEvent,
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.ok(counter.count - before > 0, 'owner synchronize reaches OpenAI');
    }

    // Unauthorized /review: manual gate but denied, zero OpenAI calls.
    {
      const state = makeState({ permission: 'read' });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const { tagged } = await tagFor('outsider-review', prCommentEvent('/review', 'outsider'), env, state);
      assert.equal(tagged.routeKind, 'manual-pr-review');
      assert.equal(tagged.reviewGate, 'manual');
      assert.equal(tagged.authorized, false);
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'outsider'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: throwingGitStub(),
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE');
      assert.equal(counter.count - before, 0, 'unauthorized /review makes zero OpenAI calls');
    }

    // Maintainer /review: exactly one AI pass on a fresh SHA.
    {
      const state = makeState({ permission: 'write' });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const { tagged } = await tagFor(
        'maintainer-review', prCommentEvent('/review', 'maintainer'), env, state, safeGitStub(),
      );
      assert.equal(tagged.reviewGate, 'manual');
      assert.equal(tagged.authorized, true);
      assert.equal(tagged.reviewsUsed, 0);
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'maintainer'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.ok(counter.count - before > 0, 'maintainer /review reaches OpenAI');
    }

    // PR author self-review without write permission: allowed, exactly once.
    {
      const state = makeState({ permission: 'read' });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const { tagged } = await tagFor(
        'author-self-review', prCommentEvent('/review', 'pr-author'), env, state, safeGitStub(),
      );
      assert.equal(tagged.authorized, true, 'PR author may re-review their own PR');
      assert.equal(tagged.safeReview, true);
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'pr-author'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.ok(counter.count - before > 0, 'author self-review reaches OpenAI');
    }

    // Fork author self-review without write permission: the PR author may
    // re-review their own fork PR (pinned PR-ref fetch, same budget).
    {
      const state = makeState({ permission: 'read', pullRequest: defaultPullRequest(HEAD_SHA, 'untrusted/fork') });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const tagCalls: string[][] = [];
      const { tagged } = await tagFor(
        'fork-author-self-review', prCommentEvent('/review', 'pr-author'), env, state, forkGitStub(tagCalls),
      );
      assert.equal(tagged.authorized, true, 'fork PR author may re-review their own PR');
      assert.equal(tagged.safeReview, false, 'safeReview stays same-repo-only for forks');
      assert.equal(tagged.diffSafe, true, 'fork author review has a safely readable diff');
      assertForkFetchAllowlist(tagCalls, 41);
      assertZeroExecution(tagCalls);
      const reviewCalls: string[][] = [];
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'pr-author'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: forkGitStub(reviewCalls),
      });
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.ok(counter.count - before > 0, 'fork author self-review reaches OpenAI');
      assertForkFetchAllowlist(reviewCalls, 41);
      assertZeroExecution(reviewCalls);
    }

    // Fork non-author without write permission stays denied: zero OpenAI
    // calls and zero git calls.
    {
      const state = makeState({ permission: 'read', pullRequest: defaultPullRequest(HEAD_SHA, 'untrusted/fork') });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const { tagged } = await tagFor(
        'fork-non-author', prCommentEvent('/review', 'someone-else'), env, state,
      );
      assert.equal(tagged.authorized, false);
      assert.equal(tagged.diffSafe, false);
      const before = counter.count;
      let gitCalls = 0;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'someone-else'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: () => { gitCalls += 1; throw new Error('must not fetch on a denied path'); },
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE');
      assert.equal(counter.count - before, 0, 'fork non-author makes zero OpenAI calls');
      assert.equal(gitCalls, 0, 'fork non-author runs zero git commands');
    }

    // Author comparison is case-insensitive; a different read-only user stays denied.
    {
      const upperState = makeState({ permission: 'none' });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const { tagged } = await tagFor('author-case', prCommentEvent('/review', 'PR-Author'), env, upperState);
      assert.equal(tagged.authorized, true, 'author login comparison is case-insensitive');
      const otherState = makeState({ permission: 'read' });
      const denied = await tagFor('non-author', prCommentEvent('/review', 'someone-else'), env, otherState);
      assert.equal(denied.tagged.authorized, false);
    }

    // Missing PR author identity denies fail-closed.
    {
      const state = makeState({ permission: 'read', pullRequest: defaultPullRequest(HEAD_SHA) });
      delete (state.pullRequest as Record<string, unknown>).user;
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const { tagged } = await tagFor('author-missing', prCommentEvent('/review', 'outsider'), env, state);
      assert.equal(tagged.authorized, false, 'an unreadable PR author denies');
    }

    // Quota exhausted on the same SHA: tag closes the gate, review mode
    // re-verifies (defense in depth) with zero OpenAI calls.
    {
      const state = makeState({
        permission: 'write',
        comments: [{ id: 7, body: stickyBody(HEAD_SHA, 2), user: { login: 'pocketguard[bot]', type: 'Bot' } }],
      });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      let tagStdout = '';
      const tagged = await runTagMode({
        event: prCommentEvent('/review', 'maintainer'),
        env,
        githubClient: makeClient(state),
        writeStdout: (value) => { tagStdout += value; },
        runGit: safeGitStub(),
      });
      assert.equal(tagged.reviewsUsed, 2);
      assert.equal(tagged.reviewGate, 'none');
      assert.equal(tagged.shouldReview, false);
      assert.match(tagged.reason, /quota-exhausted/);
      assert.match(tagStdout, /"reviewGate":"none"/);
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'maintainer'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: throwingGitStub(),
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE');
      assert.equal(counter.count - before, 0, 'quota-exhausted review makes zero OpenAI calls');
    }

    // P2 #3 reopened+ledger: reopened keeps first-review routing but shares
    // the per-SHA ledger (reopening never resets). used=1 stays auto (a
    // second review is allowed); used=2 closes to none quota-exhausted;
    // unreadable closes to none quota-unknown.
    {
      const reopenedEvent = {
        action: 'reopened',
        repository: { full_name: REPO },
        pull_request: {
          number: 41,
          title: 'security: validate capability boundary',
          base: { sha: BASE_SHA, ref: 'main' },
          head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: REPO } },
        },
      };
      const reopenedEnv = openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' });
      // used=1: gate stays auto, second review allowed.
      {
        const state = makeState({
          comments: [{ id: 7, body: stickyBody(HEAD_SHA, 1), user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        const tagged = await runTagMode({
          event: reopenedEvent,
          env: reopenedEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(tagged.routeKind, 'first-review', 'reopened routes first-review');
        assert.equal(tagged.action, 'reopened');
        assert.equal(tagged.reviewsUsed, 1);
        assert.equal(tagged.reviewGate, 'auto', 'reopened used=1 stays auto');
        assert.equal(tagged.shouldReview, true, 'reopened used=1 allows a second review');
        assert.ok(!/quota-exhausted/.test(tagged.reason) && !/quota-unknown/.test(tagged.reason));
      }
      // used=2: gate closes quota-exhausted, zero OpenAI.
      {
        const state = makeState({
          comments: [{ id: 7, body: stickyBody(HEAD_SHA, 2), user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        const tagged = await runTagMode({
          event: reopenedEvent,
          env: reopenedEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(tagged.routeKind, 'first-review', 'reopened used=2 still routes first-review');
        assert.equal(tagged.reviewsUsed, 2);
        assert.equal(tagged.reviewGate, 'none', 'reopened used=2 closes the gate');
        assert.equal(tagged.shouldReview, false);
        assert.match(tagged.reason, /quota-exhausted/);
        const before = counter.count;
        const reviewed = await runReviewMode({
          event: reopenedEvent,
          env: { ...reopenedEnv, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
          runGit: throwingGitStub(),
        });
        assert.equal(reviewed.verdict, 'INCONCLUSIVE');
        assert.equal(counter.count - before, 0, 'reopened quota-exhausted makes zero OpenAI calls');
      }
      // Unreadable ledger: gate closes quota-unknown.
      {
        const throwingClient = {
          rest: {
            pulls: {
              get: async () => ({ data: defaultPullRequest(HEAD_SHA) }),
            },
            users: {
              getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }),
            },
            issues: {
              listComments: async () => { throw new Error('synthetic reopened ledger failure'); },
              createComment: async () => { throw new Error('must not write on quota-unknown'); },
              updateComment: async () => { throw new Error('must not write on quota-unknown'); },
              addLabels: async () => { throw new Error('must not label on quota-unknown'); },
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        const tagged = await runTagMode({
          event: reopenedEvent,
          env: reopenedEnv,
          githubClient: throwingClient,
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(tagged.routeKind, 'first-review');
        assert.equal(tagged.reviewGate, 'none', 'reopened unreadable ledger closes the gate');
        assert.equal(tagged.shouldReview, false);
        assert.match(tagged.reason, /quota-unknown/);
      }
    }

    // Quota unknown (fail-closed): listComments throw closes all three
    // stages with zero OpenAI and an untouched sticky.
    {
      const throwingClient = {
        rest: {
          pulls: {
            get: async () => ({ data: defaultPullRequest(HEAD_SHA) }),
          },
          users: {
            getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }),
          },
          repos: {
            getCollaboratorPermissionLevel: async () => ({ data: { permission: 'write' } }),
          },
          issues: {
            listComments: async () => { throw new Error('synthetic list failure'); },
            createComment: async () => { throw new Error('must not write on quota-unknown'); },
            updateComment: async () => { throw new Error('must not write on quota-unknown'); },
            addLabels: async () => { throw new Error('must not label on quota-unknown'); },
          },
        },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const tagged = await runTagMode({
        event: prCommentEvent('/review', 'maintainer'),
        env,
        githubClient: throwingClient,
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(tagged.reviewGate, 'none');
      assert.equal(tagged.shouldReview, false);
      assert.match(tagged.reason, /quota-unknown/);
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'maintainer'),
        env: { ...env, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: throwingClient,
        writeStdout: () => undefined,
        runGit: throwingGitStub(),
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE');
      assert.equal(counter.count - before, 0, 'quota-unknown review makes zero OpenAI calls');
      const publishState = makeState({ permission: 'write' });
      const quotaSticky = stickyBody(HEAD_SHA, 1);
      publishState.comments = [{ id: 7, body: quotaSticky, user: { login: 'pocketguard[bot]', type: 'Bot' } }];
      const quotaClient = {
        rest: {
          pulls: {
            get: async () => ({ data: defaultPullRequest(HEAD_SHA) }),
          },
          users: {
            getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }),
          },
          issues: {
            listComments: async () => { throw new Error('synthetic list failure'); },
            createComment: async () => { publishState.created += 1; return {}; },
            updateComment: async () => { publishState.updated += 1; return {}; },
            addLabels: async () => { publishState.labelsAdded.push([]); return {}; },
          },
        },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      const tempQuotaPath = path.join(os.tmpdir(), `pocketguard-matrix-quota-unknown-${Date.now()}.json`);
      fs.writeFileSync(tempQuotaPath, JSON.stringify({
        verdict: 'APPROVE',
        pullRequestNumber: 41,
        baseSha: BASE_SHA,
        headSha: HEAD_SHA,
        headRepository: REPO,
        roles: [
          { role: 'chief', modelUsed: 'm', verdict: 'APPROVE', findings: [] },
          { role: 'android_sec', modelUsed: 'm', verdict: 'APPROVE', findings: [] },
          { role: 'android_code', modelUsed: 'm', verdict: 'APPROVE', findings: [] },
        ],
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 10 },
        deterministicViolations: [],
        areaLabels: [],
        changedFiles: [],
        changedFilesComplete: true,
      }));
      await runPublishMode({
        event: prCommentEvent('/review', 'maintainer'),
        env: {
          GITHUB_EVENT_NAME: 'issue_comment',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_OUTPUT: tempQuotaPath,
          POCKETGUARD_REVIEW_JOB_RESULT: 'success',
          POCKETGUARD_TAG_LABELS: '[]',
        } as NodeJS.ProcessEnv,
        githubClient: quotaClient,
      });
      assert.equal(publishState.created, 0, 'quota-unknown publish creates nothing');
      assert.equal(publishState.updated, 0, 'quota-unknown publish updates nothing');
      assert.equal(publishState.comments[0].body, quotaSticky, 'quota-unknown publish leaves the sticky untouched');
      fs.rmSync(tempQuotaPath, { force: true });
    }

    // Missing client is also unknown: tag closes, review stays generic.
    {
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const noClientEnv = { ...env, GITHUB_TOKEN: '' } as NodeJS.ProcessEnv;
      delete (noClientEnv as Record<string, unknown>).GITHUB_TOKEN;
      const tagged = await runTagMode({
        event: prCommentEvent('/review', 'maintainer'),
        env: { ...noClientEnv, GITHUB_REPOSITORY: REPO } as NodeJS.ProcessEnv,
        githubClient: undefined,
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      // Without a client the permission gate denies first (authorized false),
      // and the quota re-check in review mode stays fail-closed.
      const before = counter.count;
      const reviewed = await runReviewMode({
        event: prCommentEvent('/review', 'maintainer'),
        env: { ...noClientEnv, GITHUB_REPOSITORY: REPO, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: undefined,
        writeStdout: () => undefined,
        runGit: throwingGitStub(),
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE');
      assert.equal(counter.count - before, 0, 'missing-client review makes zero OpenAI calls');
      void tagged;
    }

    // A new head SHA restarts the budget even with an exhausted old marker.
    {
      const state = makeState({
        permission: 'write',
        comments: [{ id: 7, body: stickyBody(HEAD_SHA, 2), user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        pullRequest: defaultPullRequest(HEAD_SHA_NEW),
      });
      const env = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
      const tagged = await runTagMode({
        event: prCommentEvent('/review', 'maintainer'),
        env,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(tagged.reviewsUsed, 0, 'a new head SHA restarts the budget');
      assert.equal(tagged.reviewGate, 'manual');
    }
    restoreOpenAI();

    // Full quota lifecycle: two counted reviews, then a silent third.
    {
      const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-matrix-quota-'));
      try {
        const state = makeState({ permission: 'write' });
        const outputPath = path.join(tempDirectory, 'review-output.json');
        const openAiCounter = { count: 0 };
        const restore = installCountingOpenAI(openAiCounter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        try {
          const runFullPass = async (label: string) => {
            const tagEnv = openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' });
            const tagged = await runTagMode({
              event: prCommentEvent('/review', 'maintainer'),
              env: tagEnv,
              githubClient: makeClient(state),
              writeStdout: () => undefined,
              runGit: safeGitStub(),
            });
            const runId = `matrix-${label}`;
            const claimed = await runClaimMode({
              event: prCommentEvent('/review', 'maintainer'),
              env: {
                GITHUB_EVENT_NAME: 'issue_comment',
                GITHUB_REPOSITORY: REPO,
                GITHUB_TOKEN: 'fake-token',
                POCKETGUARD_RUN_ID: runId,
                ...(tagged.quotaHeadSha ? { POCKETGUARD_QUOTA_SHA: tagged.quotaHeadSha } : {}),
              } as NodeJS.ProcessEnv,
              githubClient: makeClient(state),
              writeStdout: () => undefined,
            });
            if (tagged.reviewGate !== 'none') {
              assert.equal(claimed.claimed, true, `${label}: open gate claims a slot`);
            }
            const reviewEnv = openAiEnv({
              GITHUB_EVENT_NAME: 'issue_comment',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_RUN_ID: runId,
            });
            const before = openAiCounter.count;
            const reviewed = await runReviewMode({
              event: prCommentEvent('/review', 'maintainer'),
              env: reviewEnv,
              githubClient: makeClient(state),
              writeStdout: () => undefined,
              runGit: safeGitStub(),
            });
            const publishEnv = {
              GITHUB_EVENT_NAME: 'issue_comment',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: JSON.stringify(tagged.labels),
              POCKETGUARD_RUN_ID: runId,
            } as NodeJS.ProcessEnv;
            const opsBefore = state.operations.length;
            await runPublishMode({
              event: prCommentEvent('/review', 'maintainer'),
              env: publishEnv,
              githubClient: makeClient(state),
            });
            return { tagged, reviewed, made: openAiCounter.count - before, opsBefore };
          };

          const first = await runFullPass('first');
          assert.equal(first.tagged.reviewsUsed, 0);
          assert.equal(first.reviewed.verdict, 'APPROVE');
          assert.ok(first.made > 0);
          assert.equal(state.created, 1, 'first review creates the sticky comment');
          assert.ok(
            state.comments[0].body.includes(formatReviewCountMarker(HEAD_SHA, 1)),
            'first sticky write counts one review',
          );

          const second = await runFullPass('second');
          assert.equal(second.tagged.reviewsUsed, 1);
          assert.equal(second.reviewed.verdict, 'APPROVE');
          assert.equal(state.updated, 3, 'second claim plus publish update the sticky comment');
          assert.ok(
            state.comments[0].body.includes(formatReviewCountMarker(HEAD_SHA, 2)),
            'second claim holds two reviews',
          );

          const stickyBefore = state.comments[0].body;
          const third = await runFullPass('third');
          assert.equal(third.tagged.reviewsUsed, 2);
          assert.equal(third.tagged.reviewGate, 'none');
          assert.equal(third.reviewed.verdict, 'INCONCLUSIVE');
          assert.equal(third.made, 0, 'the third review on the same SHA makes zero OpenAI calls');
          assert.equal(state.created, 1, 'quota-exhausted run creates nothing');
          assert.equal(state.updated, 3, 'quota-exhausted claim and publish update nothing');
          assert.equal(state.comments[0].body, stickyBefore, 'quota-exhausted publish leaves the sticky untouched');
          assert.deepEqual(
            state.operations.slice(third.opsBefore).filter((op) => op !== 'list-comments' && op !== 'pulls-get' && op !== 'permission-check' && op !== 'authenticated-user'),
            [],
            'quota-exhausted publish performs no comment or label writes',
          );

          // A new head SHA restarts the lifecycle.
          state.pullRequest = defaultPullRequest(HEAD_SHA_NEW);
          const fresh = await runFullPass('fresh-sha');
          assert.equal(fresh.tagged.reviewsUsed, 0);
          assert.equal(fresh.reviewed.verdict, 'APPROVE');
          assert.ok(
            state.comments[0].body.includes(formatReviewCountMarker(HEAD_SHA_NEW, 1)),
            'a new head SHA restarts the counter',
          );
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDirectory, { recursive: true, force: true });
      }
    }

    // Unauthorized /review publish is a legitimate skip: zero writes.
    {
      const state = makeState({
        permission: 'read',
        comments: [{ id: 7, body: stickyBody(HEAD_SHA, 1), user: { login: 'pocketguard[bot]', type: 'Bot' } }],
      });
      const stickyBefore = state.comments[0].body;
      const opsBefore = state.operations.length;
      await runPublishMode({
        event: prCommentEvent('/review', 'outsider'),
        env: {
          GITHUB_EVENT_NAME: 'issue_comment',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_OUTPUT: path.join(os.tmpdir(), 'pocketguard-matrix-unauth-publish.json'),
          POCKETGUARD_REVIEW_JOB_RESULT: 'success',
          POCKETGUARD_TAG_LABELS: '[]',
        } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
      });
      assert.equal(state.created, 0, 'unauthorized /review publish creates nothing');
      assert.equal(state.updated, 0, 'unauthorized /review publish updates nothing');
      assert.equal(state.comments[0].body, stickyBefore, 'unauthorized publish leaves the sticky untouched');
      assert.deepEqual(
        state.operations.slice(opsBefore).filter((op) => op !== 'list-comments' && op !== 'pulls-get' && op !== 'permission-check' && op !== 'authenticated-user'),
        [],
        'unauthorized publish performs no comment or label writes',
      );
    }

    // P1 #2: a failed review job without verifiable output never started, so
    // the INCONCLUSIVE fallback preserves the ledger with no new marker.
    {
      const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-matrix-fallback-'));
      try {
        const missingPath = path.join(tempDirectory, 'absent-output.json');
        const state = makeState({ permission: 'write' });
        await runPublishMode({
          event: prOpenedEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: missingPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'failure',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
        });
        assert.equal(state.created, 1, 'a failed review still publishes the INCONCLUSIVE fallback');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'));
        assert.ok(
          !state.comments[0].body.includes(formatReviewCountMarker(HEAD_SHA, 1)),
          'unstarted reviews never consume the per-SHA budget',
        );
        assert.equal(parseReviewCountMarker(state.comments[0].body, HEAD_SHA), 0);
      } finally {
        fs.rmSync(tempDirectory, { recursive: true, force: true });
      }
    }

    // Zero-OpenAI locks: ordinary chatter, unsubscribed PR actions, and bot commands.
    {
      const zeroCounter = { count: 0 };
      const restore = installCountingOpenAI(zeroCounter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
      try {
        const prEnv = openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' });
        // Ordinary chatter on a PR.
        const chatterState = makeState({ permission: 'write' });
        const chatterTag = await runTagMode({
          event: prCommentEvent('looks good to me', 'maintainer'),
          env: openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }),
          githubClient: makeClient(chatterState),
          writeStdout: () => undefined,
          runGit: throwingGitStub(),
        });
        assert.equal(chatterTag.routeKind, 'ignore');
        assert.equal(chatterTag.reviewGate, 'none');
        const chatterReview = await runReviewMode({
          event: prCommentEvent('looks good to me', 'maintainer'),
          env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }), POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
          githubClient: makeClient(chatterState),
          writeStdout: () => undefined,
          runGit: throwingGitStub(),
        });
        assert.equal(chatterReview.verdict, 'INCONCLUSIVE');

        // PR title/body edits and label changes are not routed.
        for (const action of ['edited', 'labeled', 'unlabeled', 'assigned']) {
          const editedReview = await runReviewMode({
            event: { ...prOpenedEvent(), action },
            env: { ...prEnv, POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
            runGit: throwingGitStub(),
          });
          assert.equal(editedReview.verdict, 'INCONCLUSIVE', `pull_request_target ${action} stays generic`);
        }

        // Bot /review commands deny before any OpenAI call.
        const botState = makeState({ permission: 'write' });
        const botReview = await runReviewMode({
          event: prCommentEvent('/review', 'pocketguard[bot]'),
          env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }), POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
          githubClient: makeClient(botState),
          writeStdout: () => undefined,
          runGit: throwingGitStub(),
        });
        assert.equal(botReview.verdict, 'INCONCLUSIVE');
        assert.equal(zeroCounter.count, 0, 'ignored and bot events make zero OpenAI calls');
      } finally {
        restore();
      }
    }

    // Issue execution: opened, edited, and human comments each take one
    // chief single-turn; issues are never counted.
    {
      const issueOpened = {
        action: 'opened',
        repository: { full_name: REPO },
        issue: { number: 7, title: 'security: token handling looks wrong', body: 'Steps to reproduce...' },
      };
      const tagState = makeState();
      const tagEnv = openAiEnv({ GITHUB_EVENT_NAME: 'issues' });
      const tagged = await runTagMode({
        event: issueOpened,
        env: tagEnv,
        githubClient: makeClient(tagState),
        writeStdout: () => undefined,
        runGit: throwingGitStub(),
      });
      assert.equal(tagged.routeKind, 'first-review');
      assert.equal(tagged.reviewGate, 'none', 'issue opens never open a PR review gate');
      assert.equal(tagged.reviewsUsed, 0, 'issues are never counted');
      assert.equal(tagged.shouldReview, true, 'human issue opens request review via the issue-auto route');
      assert.equal(tagged.shouldTag, true);

      const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-matrix-issue-'));
      try {
        const issueCounter = { count: 0 };
        const restore = installCountingOpenAI(issueCounter, { verdict: 'APPROVE', summary: 'no action needed' });
        try {
          const outputPath = path.join(tempDirectory, 'issue-output.json');
          const reviewState = makeState();
          const reviewed = await runIssueReviewMode({
            event: issueOpened,
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: outputPath } as NodeJS.ProcessEnv,
            githubClient: makeClient(reviewState),
            writeStdout: () => undefined,
          });
          assert.equal(issueCounter.count, 1, 'issue execution takes exactly one single-turn call');
          assert.equal(reviewed.verdict, 'APPROVE');
          assert.equal(reviewed.issueNumber, 7);
          assert.ok(reviewed.tags.includes('security'), 'issue tags stay rules-only');
          assert.equal(reviewed.summary, 'no action needed');
          const validated = validateIssueOutput(JSON.parse(fs.readFileSync(outputPath, 'utf8')) as unknown);
          assert.deepEqual(validated, reviewed);

          // Human comments join the context; bot comments are excluded.
          let capturedInput = '';
          const captureFetch = globalThis.fetch;
          globalThis.fetch = (async (_url: unknown, init?: { body?: unknown }) => {
            issueCounter.count += 1;
            capturedInput = JSON.stringify((JSON.parse(String(init?.body)) as { input: unknown }).input ?? '');
            return new Response(JSON.stringify({
              output: [{ content: [{ type: 'output_text', text: JSON.stringify({ verdict: 'NEEDS_CHANGES', summary: 'needs work' }) }] }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
          }) as typeof fetch;
          try {
            const commentState = makeState({
              comments: [
                { id: 11, body: 'human insight about the token flow', user: { login: 'human', type: 'User' } },
                { id: 12, body: 'bot noise', user: { login: 'pocketguard[bot]', type: 'Bot' } },
              ],
            });
            const edited = await runIssueReviewMode({
              event: {
                action: 'edited',
                repository: { full_name: REPO },
                issue: { number: 7, title: 'security: token handling looks wrong', body: 'Steps...' },
              },
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDirectory, 'edited.json') } as NodeJS.ProcessEnv,
              githubClient: makeClient(commentState),
              writeStdout: () => undefined,
            });
            assert.equal(edited.verdict, 'NEEDS_CHANGES');
            assert.ok(capturedInput.includes('human insight about the token flow'), 'human comments join the context');
            assert.ok(!capturedInput.includes('bot noise'), 'bot comments are excluded from the context');
          } finally {
            globalThis.fetch = captureFetch;
          }

          // A human comment on an issue also executes, without counting.
          const onComment = await runIssueReviewMode({
            event: {
              action: 'created',
              repository: { full_name: REPO },
              issue: { number: 7, title: 'plain title' },
              comment: { body: 'any follow-up', user: { login: 'human', type: 'User' } },
            },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }), POCKETGUARD_OUTPUT: path.join(tempDirectory, 'comment.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
          });
          assert.equal(onComment.verdict, 'APPROVE');

          // Issue publish writes exactly one sticky (S5) and applies labels
          // from a matching valid artifact (rules + allowlisted AI).
          const publishState = makeState();
          await runPublishMode({
            event: issueOpened,
            env: {
              GITHUB_EVENT_NAME: 'issues',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(publishState),
          });
          assert.equal(publishState.created, 1, 'issue publish creates the single sticky');
          assert.equal(publishState.updated, 0);
          assert.ok(publishState.comments[0].body.includes('<!-- PocketGuard-review -->'), 'issue sticky carries the shared marker');
          assert.ok(publishState.comments[0].body.includes('判定：APPROVE'), 'issue sticky shows the verdict');
          assert.ok(publishState.comments[0].body.includes('修訂指紋'), 'issue sticky carries the revision fingerprint');
          assert.ok(publishState.operations.includes('add-labels') || publishState.operations.includes('list-labels'), 'issue publish reconciles labels');
          assert.ok(publishState.existingLabels.includes('security'));

          // A mismatched artifact falls back to INCONCLUSIVE on the same
          // sticky (no second comment) with title labels plus decision.
          const mismatchState = makeState();
          fs.writeFileSync(path.join(tempDirectory, 'mismatch.json'), JSON.stringify({ ...reviewed, issueNumber: 999 }));
          await runPublishMode({
            event: issueOpened,
            env: {
              GITHUB_EVENT_NAME: 'issues',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: path.join(tempDirectory, 'mismatch.json'),
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(mismatchState),
          });
          assert.equal(mismatchState.created, 1, 'mismatched issue artifact still uses a single sticky');
          assert.equal(mismatchState.updated, 0);
          assert.ok(mismatchState.comments[0].body.includes('判定：INCONCLUSIVE'), 'stale issue artifact falls back to INCONCLUSIVE');
          assert.ok(mismatchState.operations.includes('list-labels') || mismatchState.operations.includes('add-labels'));
        } finally {
          restore();
        }

        // AI failure degrades to rules-only INCONCLUSIVE with zero crash.
        const failingFetch = globalThis.fetch;
        globalThis.fetch = (async () => { throw new Error('OpenAI down'); }) as typeof fetch;
        try {
          const failed = await runIssueReviewMode({
            event: issueOpened,
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDirectory, 'failed.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
          });
          assert.equal(failed.verdict, 'INCONCLUSIVE');
          assert.ok(failed.tags.includes('security'), 'failure keeps rules-only tags');
        } finally {
          globalThis.fetch = failingFetch;
        }

        // Oversized bodies truncate to the shared budget.
        let bigInput = '';
        const bigFetch = globalThis.fetch;
        globalThis.fetch = (async (_url: unknown, init?: { body?: unknown }) => {
          bigInput = JSON.stringify((JSON.parse(String(init?.body)) as { input: unknown }).input ?? '');
          return new Response(JSON.stringify({
            output: [{ content: [{ type: 'output_text', text: JSON.stringify({ verdict: 'INCONCLUSIVE', summary: '' }) }] }],
          }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }) as typeof fetch;
        try {
          await runIssueReviewMode({
            event: {
              action: 'opened',
              repository: { full_name: REPO },
              issue: { number: 7, title: 't', body: 'x'.repeat(30000) },
            },
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDirectory, 'big.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
          });
          assert.ok(
            bigInput.length <= MAX_ISSUE_CONTEXT_LENGTH + 100,
            `issue context truncates to the shared budget (got ${bigInput.length})`,
          );
        } finally {
          globalThis.fetch = bigFetch;
        }

        // Bot issue comments stay silent: routed ignore, zero OpenAI calls.
        {
          const botCommentCounter = { count: 0 };
          const restoreBotComment = installCountingOpenAI(botCommentCounter, { verdict: 'APPROVE', summary: 'x' });
          try {
            const botCommentReview = await runIssueReviewMode({
              event: {
                action: 'created',
                repository: { full_name: REPO },
                issue: { number: 7, title: 't' },
                comment: { body: 'bot follow-up', user: { login: 'some-bot[bot]', type: 'Bot' } },
              },
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issue_comment' }), POCKETGUARD_OUTPUT: path.join(tempDirectory, 'bot-comment.json') } as NodeJS.ProcessEnv,
              githubClient: makeClient(makeState()),
              writeStdout: () => undefined,
            });
            assert.equal(botCommentReview.verdict, 'INCONCLUSIVE');
            assert.equal(botCommentCounter.count, 0, 'bot issue comments make zero OpenAI calls');
          } finally {
            restoreBotComment();
          }
        }

        // Bot-opened issues stay silent end to end.
        const botCounter = { count: 0 };
        const restoreBot = installCountingOpenAI(botCounter, { verdict: 'APPROVE', summary: 'x' });
        try {
          const botIssue = {
            action: 'opened',
            repository: { full_name: REPO },
            issue: { number: 7, title: 't', body: 'b' },
            sender: { login: 'some-bot', type: 'Bot' },
          };
          const botReview = await runIssueReviewMode({
            event: botIssue,
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: path.join(tempDirectory, 'bot.json') } as NodeJS.ProcessEnv,
            githubClient: makeClient(makeState()),
            writeStdout: () => undefined,
          });
          assert.equal(botReview.verdict, 'INCONCLUSIVE');
          assert.equal(botCounter.count, 0, 'bot issues make zero OpenAI calls');
          const botPublishState = makeState();
          await runPublishMode({
            event: botIssue,
            env: {
              GITHUB_EVENT_NAME: 'issues',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: path.join(tempDirectory, 'bot.json'),
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(botPublishState),
          });
          assert.equal(botPublishState.operations.length, 0, 'bot issues publish nothing');
        } finally {
          restoreBot();
        }
      } finally {
        fs.rmSync(tempDirectory, { recursive: true, force: true });
      }
    }

    // --mode=review entry dispatches by target.
    {
      const entryIssue = await runReviewEntryMode({
        event: {
          action: 'opened',
          repository: { full_name: REPO },
          issue: { number: 7, title: 't', body: 'b' },
        },
        env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'issues' }), POCKETGUARD_OUTPUT: '' } as NodeJS.ProcessEnv,
        githubClient: makeClient(makeState()),
        writeStdout: () => undefined,
      });
      assert.equal((entryIssue as { issueNumber: number }).issueNumber, 7);
      assert.ok(!('roles' in entryIssue), 'issue entry carries no role reviews');
    }

    // validateIssueOutput strictness.
    assert.equal(validateIssueOutput(undefined), undefined);
    assert.equal(
      validateIssueOutput({ verdict: 'APPROVE', issueNumber: 7, title: 't', tags: ['security'], summary: 's', extra: 1 }),
      undefined,
      'unknown keys reject',
    );
    assert.equal(
      validateIssueOutput({ verdict: 'MAYBE', issueNumber: 7, title: 't', tags: [], summary: '' }),
      undefined,
      'unknown verdicts reject',
    );

    // Workflow wiring: route-driven gates, quota outputs, and the quota mutex note.
    {
      const workflow = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/pocketguard.yml'), 'utf8');
      const jobsStart = workflow.indexOf('jobs:');
      assert.notEqual(jobsStart, -1);
      const prepareTag = workflow.slice(workflow.indexOf('  prepare-tag:'), workflow.indexOf('  claim-slot:'));
      const claimJob = workflow.slice(workflow.indexOf('  claim-slot:'), workflow.indexOf('  review-send:'));
      const reviewJob = workflow.slice(workflow.indexOf('  review-send:'), workflow.indexOf('  publish:'));
      const publishJob = workflow.slice(workflow.indexOf('  publish:'));
      for (const output of ['should_review', 'should_tag', 'route_kind', 'review_gate', 'reviews_used', 'diff_safe', 'quota_head_sha']) {
        assert.match(
          prepareTag,
          new RegExp(`${output}:\\s*\\$\\{\\{\\s*steps\\.tag\\.outputs\\.${output}\\s*\\}\\}`),
          `prepare-tag forwards ${output}`,
        );
      }
      const squashed = (text: string): string => text.replace(/\s+/g, ' ').trim();
      const reviewCondition = squashed(reviewJob.slice(0, reviewJob.indexOf('    steps:')));
      assert.match(reviewCondition, /should_review\s*==\s*'true'/, 'review-send requires should_review');
      assert.match(
        reviewCondition,
        /target\s*==\s*'pull-request'.*should_review\s*==\s*'true'.*review_gate\s*==\s*'auto'/,
        'review-send schedules PR auto reviews explicitly',
      );
      assert.match(
        reviewCondition,
        /target\s*==\s*'pull-request'.*review_gate\s*==\s*'manual'.*authorized\s*==\s*'true'/,
        'review-send gates manual PR reviews on authorization',
      );
      assert.match(
        reviewCondition,
        /target\s*==\s*'issue'.*should_review\s*==\s*'true'.*route_kind\s*==\s*'first-review'/,
        'review-send schedules issue-auto execution by explicit route',
      );
      assert.match(
        reviewCondition,
        /route_kind\s*==\s*'issue-update'/,
        'review-send covers the issue-update route',
      );
      assert.doesNotMatch(
        reviewCondition,
        /should_tag\s*==\s*'true'/,
        'should_tag is never a review-job scheduling reason',
      );
      // Trusted step admits the readable-diff route (same-repo or fork via
      // the pinned PR-ref fetch) to the OpenAI secrets; the generic
      // complement keeps no secrets. safe_review stays same-repo-only.
      const trustedStep = reviewJob.slice(reviewJob.indexOf('Run trusted single-turn review'));
      assert.match(
        squashed(trustedStep.slice(0, 2000)),
        /diff_safe\s*==\s*'true'.*authorized\s*==\s*'true'/,
        'trusted step gates the readable diff plus authorization',
      );
      assert.match(
        squashed(trustedStep.slice(0, 2000)),
        /target\s*==\s*'issue'.*should_review\s*==\s*'true'.*route_kind/,
        'trusted step admits the intentional issue-auto route to secrets',
      );
      const publishCondition = squashed(publishJob.slice(0, publishJob.indexOf('    steps:')));
      assert.match(publishCondition, /\balways\(\)/, 'publish still runs on review failure');
      assert.match(
        publishCondition,
        /authorized\s*==\s*'true'.*review_gate\s*!=\s*'none'/,
        'quota-exhausted issue_comment skips publish without touching the sticky',
      );
      const concurrencyBlock = workflow.slice(workflow.indexOf('concurrency:'), workflow.indexOf('  prepare-tag:'));
      assert.match(concurrencyBlock, /quota/, 'concurrency documents the quota mutex without CAS');
      assert.match(concurrencyBlock, /claim/, 'concurrency documents the claim pre-occupation');
      assert.match(prepareTag, /issues:\s*read/, 'prepare-tag holds read-only issue metadata scope');
      assert.match(reviewJob.slice(0, reviewJob.indexOf('    steps:')), /issues:\s*read/,
        'review-send holds read-only issue metadata scope');
      assert.doesNotMatch(reviewJob.slice(0, reviewJob.indexOf('    steps:')), /:\s*write/,
        'review-send holds no write permission');
      assert.match(claimJob.slice(0, claimJob.indexOf('    steps:')), /issues:\s*write/,
        'claim-slot holds the minimal sticky write scope');
      assert.doesNotMatch(claimJob, /OPENAI_API_KEY/, 'claim-slot takes no OpenAI secrets');
      assert.match(reviewJob, /needs:\s*\[prepare-tag,\s*claim-slot\]/, 'review-send waits for the claim');
      assert.match(
        squashed(reviewJob.slice(0, reviewJob.indexOf('    steps:'))),
        /needs\.claim-slot\.outputs\.claimed\s*==\s*'true'/,
        'review-send requires the approved claim for pull-request reviews',
      );
      assert.equal(MAX_REVIEWS_PER_SHA, 2, 'budget constant matches the specified two reviews per SHA');
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard execution-matrix tests] All tests passed.');
}
