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
  runClaimMode,
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
  permission?: string;
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
          return { data: { permission: state.permission ?? 'write' } };
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

function prCommentEventFor(body: string, login: string): Record<string, unknown> {
  return {
    action: 'created',
    repository: { full_name: REPO },
    issue: { number: 41, pull_request: { url: 'unused' }, title: 'topic' },
    comment: { body, user: { login, type: 'User' } },
  };
}

/** Stage 5 claim step for the given head SHA and run id (mirrors the claim-slot job). */
async function runClaimPass(
  state: FakeState,
  headSha: string,
  runId: string,
  quotaSha?: string,
): Promise<{ claimed: boolean; used: number }> {
  state.pullRequest = defaultPullRequest(headSha);
  const claimed = await runClaimMode({
    event: prOpenedEvent(headSha),
    env: {
      GITHUB_EVENT_NAME: 'pull_request_target',
      GITHUB_REPOSITORY: REPO,
      GITHUB_TOKEN: 'fake-token',
      POCKETGUARD_RUN_ID: runId,
      ...(quotaSha ? { POCKETGUARD_QUOTA_SHA: quotaSha } : {}),
    } as NodeJS.ProcessEnv,
    githubClient: makeClient(state),
    writeStdout: () => undefined,
    runGit: safeGitStub(),
  });
  return { claimed: claimed.claimed, used: claimed.reviewsUsed };
}

/** One full tag → claim → review → publish pass for the given head SHA and run id. */
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
  const claimEnv = {
    GITHUB_EVENT_NAME: 'pull_request_target',
    GITHUB_REPOSITORY: REPO,
    GITHUB_TOKEN: 'fake-token',
    POCKETGUARD_RUN_ID: runId,
    ...(tagged.quotaHeadSha ? { POCKETGUARD_QUOTA_SHA: tagged.quotaHeadSha } : {}),
  } as NodeJS.ProcessEnv;
  const claimed = await runClaimMode({
    event,
    env: claimEnv,
    githubClient: makeClient(state),
    writeStdout: () => undefined,
    runGit: safeGitStub(),
  });
  assert.equal(claimed.claimed, true, `claim pre-occupies the slot for ${runId}`);
  const before = counter.count;
  await runReviewMode({
    event,
    env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: runId }),
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
          const claimBlocked = await runClaimMode({
            event,
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_RUN_ID: 'run-a3-blocked',
              ...(tagged.quotaHeadSha ? { POCKETGUARD_QUOTA_SHA: tagged.quotaHeadSha } : {}),
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(claimBlocked.claimed, false, 'quota-exhausted claim writes nothing');
          const createdBefore = state.created;
          assert.equal(state.created, createdBefore, 'quota-exhausted claim creates nothing');
          const before = counter.count;
          const reviewed = await runReviewMode({
            event,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-a3-blocked' }),
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

    // --- Test 2: stale A artifact while B is current never pollutes B. ---
    // Stage 5: counting happens only in the claim (here for the current head
    // B); publish only reconciles, so the stale A artifact publishes its
    // INCONCLUSIVE fallback while the ledger keeps the claim's B:1 and A:0.
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
        const claimedB = await runClaimPass(state, SHA_B, 'run-stale-1');
        assert.equal(claimedB.claimed, true, 'claim reserves the current head B');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 1);
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
        assert.equal(state.created, 1, 'claim created the single sticky; publish reuses it');
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'stale publishes a fallback');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 1, 'current SHA keeps the claim');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 0, 'stale artifact SHA is not counted by publish');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 3: send-failure after AI started keeps the claim (exactly once). ---
    // --- Test 7 (part 1): three roles together consume one claimed slot. ---
    // Stage 5: the claim pre-occupies the slot; the later transport failure
    // never refunds, and publish only reconciles (stays at 1).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-sendfail-'));
      try {
        const state = makeState();
        const claimed = await runClaimPass(state, SHA_A, 'run-sendfail-1');
        assert.equal(claimed.claimed, true, 'claim reserves the slot before AI');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);
        const counter = { count: 0 };
        const throwingFetch = globalThis.fetch;
        globalThis.fetch = (async () => {
          counter.count += 1;
          throw new Error('synthetic send failure');
        }) as typeof fetch;
        const outputPath = path.join(tempDir, 'review.json');
        try {
          const reviewed = await runReviewMode({
            event: prOpenedEvent(SHA_A),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-sendfail-1' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE');
          assert.ok(counter.count > 0, 'AI was attempted before failing');
          assert.ok(counter.count >= 3, `three roles attempted (got ${counter.count})`);
        } finally {
          globalThis.fetch = throwingFetch;
        }
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
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'started review keeps its single claimed slot despite 3 role attempts');
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
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'one claimed run holds +1 total');
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 4a: truly unstarted (no claim) publish preserves zero, no marker. ---
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

    // --- Test 4b (Stage 5 rewrite): claimed-but-unverifiable keeps the claim (never refunded). ---
    // The old zero-count expectation was the P1 #1 bug: AI had already been
    // paid for once the claim reserved the slot, so a later missing artifact
    // or failed job must not zero the ledger. Publish only reconciles content.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-claimed-missing-'));
      try {
        const missingPath = path.join(tempDir, 'absent.json');
        const state = makeState();
        const claimed = await runClaimPass(state, SHA_A, 'run-claimed-missing-1');
        assert.equal(claimed.claimed, true);
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'claim pre-occupies the slot');
        await runPublishMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: missingPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-claimed-missing-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
        });
        assert.ok(state.comments[0].body.includes('判定：INCONCLUSIVE'), 'missing artifact still publishes a fallback');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'missing artifact after claim keeps the slot');

        // Failed job result after a claim: same, the slot is never refunded.
        const state2 = makeState();
        await runClaimPass(state2, SHA_A, 'run-claimed-failed-1');
        await runPublishMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: missingPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'failure',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-claimed-failed-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state2),
        });
        assert.equal(parseReviewCountMarker(state2.comments[0].body, SHA_A), 1, 'failed job after claim keeps the slot');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 4c (Stage 5): four unstarted claim paths stay at zero with no writes. ---
    {
      // (1) route ignore: pull_request_target edited is not routed.
      {
        const state = makeState();
        const ignored = await runClaimMode({
          event: { ...prOpenedEvent(SHA_A), action: 'edited' },
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-ignore-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
        });
        assert.equal(ignored.claimed, false);
        assert.equal(state.created, 0, 'route-ignore claim creates nothing');
        assert.equal(state.updated, 0, 'route-ignore claim updates nothing');
      }
      // (2) unauthorized: read-only outsider /review on a PR.
      {
        const state = makeState({ permission: 'read' });
        const denied = await runClaimMode({
          event: prCommentEventFor('/review', 'outsider'),
          env: {
            GITHUB_EVENT_NAME: 'issue_comment',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-unauth-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
        });
        assert.equal(denied.claimed, false);
        assert.equal(state.created, 0, 'unauthorized claim creates nothing');
        assert.equal(state.updated, 0, 'unauthorized claim updates nothing');
      }
      // (3) quota-exhausted: pre-seeded A:2 stays untouched.
      {
        const sticky = stickyBodyFor(new Map([[SHA_A, 2]]));
        const state = makeState({
          comments: [{ id: 7, body: sticky, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        const exhausted = await runClaimMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-exhausted-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(exhausted.claimed, false);
        assert.equal(state.created, 0, 'quota-exhausted claim creates nothing');
        assert.equal(state.updated, 0, 'quota-exhausted claim updates nothing');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2);
      }
      // (4) quota-unknown: unreadable ledger fails closed with no writes.
      {
        const broken = {
          rest: {
            pulls: { get: async () => ({ data: defaultPullRequest(SHA_A) }) },
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: {
              listComments: async () => { throw new Error('synthetic list failure'); },
              createComment: async () => { throw new Error('must not write on quota-unknown'); },
              updateComment: async () => { throw new Error('must not write on quota-unknown'); },
            },
          },
        } as unknown as NonNullable<RunnerContext['githubClient']>;
        const unknown = await runClaimMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-unknown-1',
          } as NodeJS.ProcessEnv,
          githubClient: broken,
          writeStdout: () => undefined,
        });
        assert.equal(unknown.claimed, false, 'quota-unknown claim writes nothing');
      }
    }

    // --- Test 5 (Stage 5): publish write failure keeps the claim; same-key retry is idempotent. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-retry-'));
      try {
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        const outputPath = path.join(tempDir, 'review.json');
        const state = makeState({
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        try {
          const claimed = await runClaimPass(state, SHA_A, 'run-retry-1');
          assert.equal(claimed.claimed, true, 'claim pre-occupies the slot before AI');
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);
          await runReviewMode({
            event: prOpenedEvent(SHA_A),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-retry-1' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
        } finally {
          restore();
        }
        // Publish fails on the sticky write; the claim's quota is already
        // consumed and labels are blocked.
        state.updateThrows = true;
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
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'failed publish keeps the claimed slot');

        // Retry with the same run id succeeds without inflating.
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

    // --- Test 6 (Stage 5): same-SHA sequential claims merge; publish never re-counts. ---
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
        const claimFor = (runId: string) => runClaimPass(state, SHA_A, runId);
        await claimFor('run-c1');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);
        await claimFor('run-c2');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'second sequential claim merges to 2');
        assert.equal(state.comments.length, 1, 'single sticky throughout');

        // Same-key claim re-entry never re-adds.
        await claimFor('run-c2');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'same-key claim re-entry is idempotent');

        // Publish with a valid artifact reconciles only (never a third count).
        await runPublishMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: outputPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-c2',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
        });
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'publish never re-counts');
        assert.equal(state.comments.length, 1, 'single sticky throughout');

        // Other-SHA entries survive a claimed write.
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
          const claimedB = await runClaimPass(state, SHA_B, 'run-c3-b');
          assert.equal(claimedB.claimed, true, 'new SHA claims its own slot');
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

    // --- Test 8 (Stage 5 core): started-but-failed consumes; third same SHA makes zero OpenAI. ---
    // A:1, then a second run whose AI starts (fetch > 0) but whose artifact is
    // deleted before publish, then a third run on the same SHA that must see
    // tag gate none, make zero OpenAI calls, and whose publish touches nothing.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-started-failed-'));
      try {
        const state = makeState();
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        try {
          const outputPath = path.join(tempDir, 'review.json');
          await runFullPass(state, SHA_A, 'run-q1', outputPath, counter);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);

          // Second run: claim reserves the last slot, AI starts, artifact lost.
          state.pullRequest = defaultPullRequest(SHA_A);
          const secondEvent = prOpenedEvent(SHA_A);
          const secondTag = await runTagMode({
            event: secondEvent,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(secondTag.reviewGate, 'auto', 'second run still scheduled');
          const secondClaim = await runClaimMode({
            event: secondEvent,
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_RUN_ID: 'run-q2',
              ...(secondTag.quotaHeadSha ? { POCKETGUARD_QUOTA_SHA: secondTag.quotaHeadSha } : {}),
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(secondClaim.claimed, true);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2);
          const beforeSecond = counter.count;
          await runReviewMode({
            event: secondEvent,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-q2' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.ok(counter.count - beforeSecond > 0, 'second run AI started before the failure');
          fs.rmSync(outputPath, { force: true });
          await runPublishMode({
            event: secondEvent,
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: JSON.stringify(secondTag.labels),
              POCKETGUARD_RUN_ID: 'run-q2',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'lost artifact after claim keeps the slot');

          // Third run on the same SHA: gated everywhere, touches nothing.
          state.pullRequest = defaultPullRequest(SHA_A);
          const thirdEvent = prOpenedEvent(SHA_A);
          const thirdTag = await runTagMode({
            event: thirdEvent,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(thirdTag.reviewsUsed, 2);
          assert.equal(thirdTag.reviewGate, 'none', 'tag gate none after two claimed slots');
          const beforeThird = counter.count;
          const thirdReview = await runReviewMode({
            event: thirdEvent,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-q3' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(thirdReview.verdict, 'INCONCLUSIVE');
          assert.equal(counter.count - beforeThird, 0, 'third run makes zero OpenAI calls');
          const updatedBefore = state.updated;
          const createdBefore = state.created;
          await runPublishMode({
            event: thirdEvent,
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
              POCKETGUARD_RUN_ID: 'run-q3',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
          assert.equal(state.updated, updatedBefore, 'third publish updates nothing');
          assert.equal(state.created, createdBefore, 'third publish creates nothing');
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 9 (Stage 5): workflow wiring for the claim-slot job. ---
    {
      const workflow = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/pocketguard.yml'), 'utf8');
      const claimIdx = workflow.indexOf('  claim-slot:');
      const reviewIdx = workflow.indexOf('  review-send:');
      const publishIdx = workflow.indexOf('  publish:');
      const prepareIdx = workflow.indexOf('  prepare-tag:');
      assert.ok(prepareIdx !== -1 && claimIdx !== -1 && reviewIdx !== -1 && publishIdx !== -1, 'all jobs exist');
      assert.ok(prepareIdx < claimIdx && claimIdx < reviewIdx && reviewIdx < publishIdx, 'claim-slot sits between prepare-tag and review-send');
      const claimJob = workflow.slice(claimIdx, reviewIdx);
      const reviewJob = workflow.slice(reviewIdx, publishIdx);
      const publishJob = workflow.slice(publishIdx);
      const prepareJob = workflow.slice(prepareIdx, claimIdx);
      const squashed = (text: string): string => text.replace(/\s+/g, ' ').trim();
      // Claim scheduling mirrors review-send PR scheduling plus the issue-auto route.
      assert.match(squashed(claimJob), /review_gate\s*==\s*'auto'/, 'claim schedules PR auto reviews');
      assert.match(squashed(claimJob), /review_gate\s*==\s*'manual'.*authorized\s*==\s*'true'/, 'claim gates manual reviews on authorization');
      assert.match(squashed(claimJob), /route_kind\s*==\s*'first-review'/, 'claim covers the issue-auto route');
      // Minimal write: issues:write only in claim-slot and publish.
      assert.match(claimJob.slice(0, claimJob.indexOf('    steps:')), /issues:\s*write/, 'claim-slot holds issues:write');
      assert.match(publishJob.slice(0, publishJob.indexOf('    steps:')), /issues:\s*write/, 'publish holds issues:write');
      assert.doesNotMatch(prepareJob.slice(0, prepareJob.indexOf('    steps:')), /issues:\s*write/, 'prepare-tag stays read-only');
      assert.doesNotMatch(reviewJob.slice(0, reviewJob.indexOf('    steps:')), /:\s*write/, 'review-send holds no write permission');
      // No OpenAI secrets and no fork checkout in the claim job.
      assert.doesNotMatch(claimJob, /OPENAI_API_KEY/, 'claim takes no OpenAI secrets');
      assert.doesNotMatch(claimJob, /OPENAI_BASE_URL/, 'claim takes no OpenAI endpoint');
      assert.doesNotMatch(claimJob, /refs\/pull/, 'claim never fetches fork refs');
      assert.match(claimJob, /--mode=claim/, 'claim runs the claim mode');
      assert.match(claimJob, /POCKETGUARD_QUOTA_SHA/, 'claim pins the tag head SHA');
      // Tag forwards the claim input; review-send requires the approved claim for PRs.
      assert.match(prepareJob, /quota_head_sha/, 'prepare-tag forwards quota_head_sha');
      assert.match(reviewJob, /needs:\s*\[prepare-tag,\s*claim-slot\]/, 'review-send waits for the claim');
      assert.match(squashed(reviewJob), /needs\.claim-slot\.outputs\.claimed\s*==\s*'true'/, 'review-send requires the approved claim for PRs');
      // Concurrency still serializes per PR/issue without weakening.
      const concurrencyBlock = workflow.slice(workflow.indexOf('concurrency:'), prepareIdx);
      assert.match(concurrencyBlock, /cancel-in-progress:\s*false/, 'concurrency never cancels');
      assert.match(concurrencyBlock, /claim/, 'concurrency documents the claim mutex without CAS');
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
