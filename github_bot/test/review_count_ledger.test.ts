import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  buildStampedBody,
  formatReviewClaimMarker,
  formatReviewCountMarker,
  MAX_REVIEWS_PER_SHA,
  parseReviewClaimSet,
  parseReviewCountLedger,
  parseReviewCountMarker,
  readStickyLedger,
  resolveCountClaimKey,
  runPublishMode,
  runReviewMode,
  runTagMode,
  stripReviewCountMarkers,
  withReviewClaimMarker,
  withReviewCountMarker,
  type RunnerContext,
} from '../src/github_runner';

const TEST_BASE_URL = ['https:', '', 'pocketguard-openai.test', 'v1'].join('/');
const TEST_ORIGIN = new URL(TEST_BASE_URL).origin;
const BASE_SHA = 'a'.repeat(40);
const SHA_A = 'b'.repeat(40);
const SHA_B = 'c'.repeat(40);
const REPO = 'sample/repository';

type FakeComment = { id: number; body: string; user: { login: string; type: string } };

interface FakeState {
  comments: FakeComment[];
  created: number;
  updated: number;
  existingLabels: string[];
  operations: string[];
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
    existingLabels: [],
    operations: [],
    pullRequest: defaultPullRequest(SHA_A),
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
          return { data: { permission: 'write' } };
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
          if (state.updateThrows) throw new Error('synthetic update failure');
          state.updated += 1;
          const comment = state.comments.find((c) => c.id === params.comment_id);
          if (comment) comment.body = params.body;
          return {};
        },
        addLabels: async (params: { labels: string[] }) => {
          state.operations.push('add-labels');
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

function prOpenedEvent(headSha: string): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: REPO },
    pull_request: {
      number: 41,
      title: 'security: validate capability boundary',
      base: { sha: BASE_SHA, ref: 'main' },
      head: { sha: headSha, ref: 'topic', repo: { full_name: REPO } },
    },
  };
}

function stickyBodyFor(ledger: Map<string, number>): string {
  let body = '<!-- PocketGuard-review -->\n\n## PocketGuard 審查\n\n**判定：APPROVE**\n';
  const sorted = [...ledger.entries()].sort((l, r) => l[0].localeCompare(r[0]));
  for (const [sha, count] of sorted) body += `${formatReviewCountMarker(sha, count)}\n`;
  return body;
}

function ledgerOf(body: string): Map<string, number> {
  return parseReviewCountLedger(body);
}

/** One full tag → review → publish pass for the given head SHA and run id. */
async function runFullPass(
  state: FakeState,
  headSha: string,
  runId: string,
  outputPath: string,
  counter: { count: number },
): Promise<{ made: number; gate: string; used: number }> {
  state.pullRequest = defaultPullRequest(headSha);
  const event = prOpenedEvent(headSha);
  const tagEnv = openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' });
  const tagged = await runTagMode({
    event,
    env: tagEnv,
    githubClient: makeClient(state),
    writeStdout: () => undefined,
    runGit: safeGitStub(),
  });
  const before = counter.count;
  await runReviewMode({
    event,
    env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath }),
    githubClient: makeClient(state),
    writeStdout: () => undefined,
    runGit: safeGitStub(),
  });
  await runPublishMode({
    event,
    env: {
      GITHUB_EVENT_NAME: 'pull_request_target',
      GITHUB_REPOSITORY: REPO,
      GITHUB_TOKEN: 'fake-token',
      POCKETGUARD_OUTPUT: outputPath,
      POCKETGUARD_REVIEW_JOB_RESULT: 'success',
      POCKETGUARD_TAG_LABELS: JSON.stringify(tagged.labels),
      POCKETGUARD_RUN_ID: runId,
    } as NodeJS.ProcessEnv,
    githubClient: makeClient(state),
  });
  return { made: counter.count - before, gate: tagged.reviewGate, used: tagged.reviewsUsed };
}

export async function runReviewCountLedgerTests(): Promise<void> {
  // --- Unit: multi-marker ledger parse / merge / max / claims. ---
  {
    assert.equal(parseReviewCountMarker('no markers', SHA_A), 0);
    const both = `${formatReviewCountMarker(SHA_A, 2)}\n${formatReviewCountMarker(SHA_B, 1)}\n`;
    const body = `<!-- PocketGuard-review -->\ntext\n${both}`;
    assert.equal(parseReviewCountMarker(body, SHA_A), 2);
    assert.equal(parseReviewCountMarker(body, SHA_B), 1);
    assert.deepEqual([...parseReviewCountLedger(body).entries()].sort(), [[SHA_A, 2], [SHA_B, 1]].sort());

    // Merge preserves the other SHA and takes max() for the target.
    const merged = withReviewCountMarker(body, SHA_A, 1);
    assert.equal(parseReviewCountMarker(merged, SHA_A), 2, 'downgrade never lowers the stored count');
    assert.equal(parseReviewCountMarker(merged, SHA_B), 1, 'other SHA preserved');
    const upgraded = withReviewCountMarker(merged, SHA_B, 5);
    assert.equal(parseReviewCountMarker(upgraded, SHA_B), 5);
    assert.equal(parseReviewCountMarker(upgraded, SHA_A), 2);

    // Appending to a fresh body keeps prior markers carried in that body.
    const fresh: string = withReviewCountMarker('<!-- PocketGuard-review -->\n', SHA_A, 1);
    const fresh2 = withReviewCountMarker(fresh, SHA_B, 1);
    assert.equal(parseReviewCountMarker(fresh2, SHA_A), 1);
    assert.equal(parseReviewCountMarker(fresh2, SHA_B), 1);

    // Duplicate markers for one SHA collapse to max().
    const dup = `${formatReviewCountMarker(SHA_A, 1)}\n${formatReviewCountMarker(SHA_A, 3)}\n`;
    assert.equal(parseReviewCountMarker(dup, SHA_A), 3);

    // Claims round-trip; invalid ids are ignored.
    assert.equal(resolveCountClaimKey({ POCKETGUARD_RUN_ID: 'run-1' } as NodeJS.ProcessEnv), 'run-1:1');
    assert.equal(resolveCountClaimKey({} as NodeJS.ProcessEnv), undefined);
    assert.equal(resolveCountClaimKey({ POCKETGUARD_RUN_ID: 'bad id!' } as NodeJS.ProcessEnv), undefined);
    const claimed = withReviewClaimMarker('base\n', SHA_A, 'run-1', '1');
    assert.ok(parseReviewClaimSet(claimed).has(`${SHA_A}:run-1:1`));
    assert.equal(withReviewClaimMarker(claimed, SHA_A, 'run-1', '1'), claimed, 'duplicate claim is idempotent');
    assert.equal(stripReviewCountMarkers(body).includes('PocketGuard-reviews:'), false);
    assert.ok(stripReviewCountMarkers(body).includes('PocketGuard-review'));

    // buildStampedBody preserves other SHAs and adds the claim.
    const stamped = buildStampedBody('fresh\n', new Map([[SHA_A, 2]]), new Set(), SHA_B, 1, 'run-x:1');
    assert.equal(parseReviewCountMarker(stamped, SHA_A), 2);
    assert.equal(parseReviewCountMarker(stamped, SHA_B), 1);
    assert.ok(parseReviewClaimSet(stamped).has(`${SHA_B}:run-x:1`));
    const preserved = buildStampedBody('fresh\n', new Map([[SHA_A, 2]]), new Set(), undefined, undefined, undefined);
    assert.equal(parseReviewCountMarker(preserved, SHA_A), 2, 'uncounted write preserves the ledger');
  }

  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    // --- Test 1: A→B→A keeps history; third A is gated with zero OpenAI. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-aba-'));
      try {
        const state = makeState();
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        try {
          const outputPath = path.join(tempDir, 'review.json');
          await runFullPass(state, SHA_A, 'run-a1', outputPath, counter);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);
          await runFullPass(state, SHA_A, 'run-a2', outputPath, counter);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2);
          const stickyId = state.comments[0].id;

          await runFullPass(state, SHA_B, 'run-b1', outputPath, counter);
          assert.equal(state.comments[0].id, stickyId, 'new SHA reuses the same sticky');
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'A history survives B');
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 1);

          state.pullRequest = defaultPullRequest(SHA_A);
          const event = prOpenedEvent(SHA_A);
          const tagged = await runTagMode({
            event,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(tagged.reviewsUsed, 2);
          assert.equal(tagged.reviewGate, 'none');
          const before = counter.count;
          const reviewed = await runReviewMode({
            event,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE');
          assert.equal(counter.count - before, 0, 'quota-exhausted A makes zero OpenAI calls');
          const updatedBefore = state.updated;
          await runPublishMode({
            event,
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
              POCKETGUARD_RUN_ID: 'run-a3-blocked',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
          assert.equal(state.updated, updatedBefore, 'quota-exhausted publish touches nothing');
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 1);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 2: stale A artifact while B is current counts toward A only. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-stale-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        const outputPath = path.join(tempDir, 'review-a.json');
        try {
          const buildState = makeState({ pullRequest: defaultPullRequest(SHA_A) });
          await runReviewMode({
            event: prOpenedEvent(SHA_A),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath }),
            githubClient: makeClient(buildState),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
        } finally {
          restore();
        }
        const state = makeState({ pullRequest: defaultPullRequest(SHA_B) });
        await runPublishMode({
          event: prOpenedEvent(SHA_B),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: outputPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-stale-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
        });
        assert.equal(state.created, 1);
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'stale publishes a fallback');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'stale counts toward artifact SHA');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 0, 'current SHA is not polluted');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 3: send-failure after AI started counts exactly once. ---
    // --- Test 7 (part 1): three roles together count as one publish. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-sendfail-'));
      try {
        const counter = { count: 0 };
        const throwingFetch = globalThis.fetch;
        globalThis.fetch = (async () => {
          counter.count += 1;
          throw new Error('synthetic send failure');
        }) as typeof fetch;
        const outputPath = path.join(tempDir, 'review.json');
        try {
          const buildState = makeState();
          const reviewed = await runReviewMode({
            event: prOpenedEvent(SHA_A),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath }),
            githubClient: makeClient(buildState),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE');
          assert.ok(counter.count > 0, 'AI was attempted before failing');
          assert.ok(counter.count >= 3, `three roles attempted (got ${counter.count})`);
        } finally {
          globalThis.fetch = throwingFetch;
        }
        const state = makeState();
        await runPublishMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: outputPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-sendfail-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
        });
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'started review counts once despite 3 role attempts');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 7 (part 2): successful three-role review counts +1 only. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-roles-'));
      try {
        const state = makeState();
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        try {
          const outputPath = path.join(tempDir, 'review.json');
          const { made } = await runFullPass(state, SHA_A, 'run-roles-1', outputPath, counter);
          assert.ok(made >= 3, `three roles ran (got ${made})`);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'one publish counts +1 total');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 4: missing artifact / failed job without output: zero count, no marker. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-unstarted-'));
      try {
        // Empty sticky + missing artifact: fallback published, still no marker.
        const missingPath = path.join(tempDir, 'absent.json');
        const state = makeState();
        await runPublishMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: missingPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-missing-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
        });
        assert.equal(state.created, 1);
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'));
        assert.equal(ledgerOf(state.comments[0].body).size, 0, 'no marker for unstarted review');

        // Failed job result: same, no marker.
        const state2 = makeState();
        await runPublishMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: missingPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'failure',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-failed-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state2),
        });
        assert.equal(ledgerOf(state2.comments[0].body).size, 0);

        // Existing ledger preserved, no new SHA marker, no increment.
        const state3 = makeState({
          comments: [{ id: 7, body: stickyBodyFor(new Map([[SHA_A, 1]])), user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          pullRequest: defaultPullRequest(SHA_B),
        });
        await runPublishMode({
          event: prOpenedEvent(SHA_B),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: missingPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-missing-2',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state3),
        });
        assert.equal(parseReviewCountMarker(state3.comments[0].body, SHA_A), 1, 'existing ledger preserved');
        assert.equal(parseReviewCountMarker(state3.comments[0].body, SHA_B), 0, 'no marker for the unstarted SHA');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 5: publish write failure throws, writes no labels, retry is idempotent. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-retry-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        const outputPath = path.join(tempDir, 'review.json');
        try {
          const buildState = makeState();
          await runReviewMode({
            event: prOpenedEvent(SHA_A),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath }),
            githubClient: makeClient(buildState),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
        } finally {
          restore();
        }
        const state = makeState({
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          updateThrows: true,
        });
        const publishEnv = {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_OUTPUT: outputPath,
          POCKETGUARD_REVIEW_JOB_RESULT: 'success',
          POCKETGUARD_TAG_LABELS: '[]',
          POCKETGUARD_RUN_ID: 'run-retry-1',
        } as NodeJS.ProcessEnv;
        await assert.rejects(
          runPublishMode({ event: prOpenedEvent(SHA_A), env: publishEnv, githubClient: makeClient(state) }),
          /failed to publish review comment/,
        );
        assert.ok(!state.operations.includes('add-labels') && !state.operations.includes('list-labels'), 'failed write blocks labels');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 0, 'failed write counts zero');

        // Retry with the same run id succeeds exactly once.
        state.updateThrows = false;
        await runPublishMode({ event: prOpenedEvent(SHA_A), env: publishEnv, githubClient: makeClient(state) });
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);

        // Duplicate retry with the same run id never inflates.
        await runPublishMode({ event: prOpenedEvent(SHA_A), env: publishEnv, githubClient: makeClient(state) });
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'retry stays idempotent');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 6: same-SHA sequential publishes merge to the correct total. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-concur-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        const outputPath = path.join(tempDir, 'review.json');
        try {
          const buildState = makeState();
          await runReviewMode({
            event: prOpenedEvent(SHA_A),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath }),
            githubClient: makeClient(buildState),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
        } finally {
          restore();
        }
        const state = makeState();
        const publishFor = (runId: string) =>
          runPublishMode({
            event: prOpenedEvent(SHA_A),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
              POCKETGUARD_RUN_ID: runId,
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
        await publishFor('run-c1');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);
        await publishFor('run-c2');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'second sequential publish merges to 2');
        assert.equal(state.comments.length, 1, 'single sticky throughout');

        // Other-SHA entries survive a counted write.
        state.pullRequest = defaultPullRequest(SHA_B);
        const counter2 = { count: 0 };
        const restore2 = installCountingOpenAI(counter2, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        try {
          const outputB = path.join(tempDir, 'review-b.json');
          await runReviewMode({
            event: prOpenedEvent(SHA_B),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputB }),
            githubClient: makeClient(makeState({ pullRequest: defaultPullRequest(SHA_B) })),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          await runPublishMode({
            event: prOpenedEvent(SHA_B),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputB,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
              POCKETGUARD_RUN_ID: 'run-c3-b',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
        } finally {
          restore2();
        }
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'A preserved');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 1, 'B added');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Ledger read helper: unknown stays fail-closed. ---
    {
      const broken = {
        rest: {
          pulls: { get: async () => ({ data: defaultPullRequest(SHA_A) }) },
          users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
          issues: {
            listComments: async () => { throw new Error('synthetic list failure'); },
            createComment: async () => ({}),
            updateComment: async () => ({}),
          },
        },
      } as unknown as NonNullable<RunnerContext['githubClient']>;
      const read = await readStickyLedger(broken, 'sample', 'repository', 41);
      assert.equal(read.ok, false);
      void formatReviewClaimMarker;
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard review-count-ledger tests] All tests passed.');
}
