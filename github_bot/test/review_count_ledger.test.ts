import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  assertStampedBodyFits,
  buildStampedBody,
  buildStampedBodyWithLength,
  estimateStickyMetadataLength,
  formatReviewClaimMarker,
  formatReviewCountMarker,
  GITHUB_COMMENT_HARD_LIMIT,
  MAX_REVIEWS_PER_SHA,
  MAX_STICKY_TOTAL_LENGTH,
  parseLedgerFooter,
  parseReviewClaimSet,
  parseReviewCountLedger,
  parseReviewCountMarker,
  readStickyLedger,
  resolveCountClaimKey,
  reviewComment,
  runClaimMode,
  runPublishMode,
  runReviewMode,
  runTagMode,
  stripReviewCountMarkers,
  verifyLedgerFooter,
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

// Deterministic synthetic head SHAs for large-ledger tests (hex, 40 chars).
function synthSha(index: number): string {
  return index.toString(16).padStart(40, '0');
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

    // Phase 1: requiredLength reporting, fit-or-throw, and footer integrity.
    {
      const measured = buildStampedBodyWithLength('fresh\n', new Map([[SHA_A, 2]]), new Set(), SHA_B, 1, 'run-x:1');
      assert.equal(measured.requiredLength, measured.body.length, 'requiredLength is the exact body length');
      assert.equal(measured.fits, true, 'small stamped body fits');
      assert.equal(
        assertStampedBodyFits('fresh\n', new Map([[SHA_A, 2]]), new Set(), SHA_B, 1, 'run-x:1'),
        measured.body,
        'fit-or-throw returns the body when it fits',
      );
      const footer = parseLedgerFooter(measured.body);
      assert.ok(footer, 'stamped body carries a ledger footer');
      assert.equal(footer!.counts, 2, 'footer counts the merged ledger entries');
      assert.equal(footer!.claims, 1, 'footer counts the merged claim markers');
      assert.equal(
        verifyLedgerFooter(measured.body, parseReviewCountLedger(measured.body), parseReviewClaimSet(measured.body)),
        true,
        'fresh footer verifies',
      );
      // Dropping one marker line while keeping the footer breaks both the
      // count and the length proof.
      const tampered = measured.body.split('\n').filter((line) => !line.includes(SHA_A)).join('\n');
      assert.equal(
        verifyLedgerFooter(tampered, parseReviewCountLedger(tampered), parseReviewClaimSet(tampered)),
        false,
        'truncated markers fail the footer check',
      );
      // A 1000-entry ledger overflows even the minimal visible body.
      const huge = new Map<string, number>();
      for (let i = 0; i < 1000; i += 1) huge.set(synthSha(i), 1);
      const hugeMeasured = buildStampedBodyWithLength('fresh\n', huge, new Set(), synthSha(1001), 1, 'run-big:1');
      assert.ok(hugeMeasured.requiredLength > GITHUB_COMMENT_HARD_LIMIT, `1000 markers overflow (got ${hugeMeasured.requiredLength})`);
      assert.equal(hugeMeasured.fits, false);
      assert.throws(
        () => assertStampedBodyFits('fresh\n', huge, new Set(), synthSha(1001), 1, 'run-big:1'),
        /comment limit/,
        'fit-or-throw refuses to truncate metadata',
      );
    }
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

    // --- Test 10 (Phase 1): 500+ markers stay fully readable end to end. ---
    // A:2 plus 500 synthetic SHAs: claim a fresh SHA, run AI, publish, then
    // re-read — every marker parses and A:2 survives.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-big-'));
      try {
        const seedLedger = new Map<string, number>([[SHA_A, 2]]);
        for (let i = 0; i < 500; i += 1) seedLedger.set(synthSha(i), 1);
        const seedBody = buildStampedBody('<!-- PocketGuard-review -->\n\n## PocketGuard 審查\n', seedLedger, new Set(), undefined, undefined, undefined);
        assert.ok(seedBody.length <= GITHUB_COMMENT_HARD_LIMIT, `500-entry seed fits (got ${seedBody.length})`);
        const state = makeState({
          comments: [{ id: 7, body: seedBody, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        const freshSha = synthSha(9999);
        const claimed = await runClaimPass(state, freshSha, 'run-big-1');
        assert.equal(claimed.claimed, true, 'claim on a 500-entry ledger completes');
        assert.equal(claimed.used, 1);

        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        const outputPath = path.join(tempDir, 'review.json');
        try {
          state.pullRequest = defaultPullRequest(freshSha);
          const before = counter.count;
          await runReviewMode({
            event: prOpenedEvent(freshSha),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-big-1' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.ok(counter.count - before > 0, 'claimed run reaches AI');
          await runPublishMode({
            event: prOpenedEvent(freshSha),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: outputPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
              POCKETGUARD_RUN_ID: 'run-big-1',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
        } finally {
          restore();
        }
        const reread = await readStickyLedger(makeClient(state), 'sample', 'repository', 41);
        assert.equal(reread.ok, true, 'big sticky re-reads cleanly with a valid footer');
        if (reread.ok) {
          assert.equal(reread.ledger.size, 502, 'all 502 entries readable');
          assert.equal(reread.ledger.get(SHA_A), 2, 'A:2 survives under 500 other SHAs');
          assert.equal(reread.ledger.get(freshSha), 1, 'fresh SHA keeps its single claimed slot');
          for (let i = 0; i < 500; i += 1) assert.equal(reread.ledger.get(synthSha(i)), 1, `synthetic SHA ${i} readable`);
          assert.ok(reread.claims.has(`${freshSha}:run-big-1:1`), 'claim marker survives publish');
          assert.ok(state.comments[0].body.includes('判定'), 'visible verdict preserved alongside the ledger');
        }
        assert.equal(state.comments.length, 1, 'single sticky throughout');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 11 (Phase 1): over-limit ledger fails closed with zero writes. ---
    // A 1000-entry ledger overflows even the minimal placeholder body: the new
    // claim reports claimed:false/quota-unknown with reviewsUsed 0, writes
    // nothing, consumes nothing, and leaves the old ledger byte-identical.
    {
      const seedLedger = new Map<string, number>([[SHA_A, 2]]);
      for (let i = 0; i < 1000; i += 1) seedLedger.set(synthSha(i), 1);
      const seedBody = buildStampedBody('<!-- PocketGuard-review -->\nold\n', seedLedger, new Set(), undefined, undefined, undefined);
      assert.ok(seedBody.length > GITHUB_COMMENT_HARD_LIMIT, `1000-entry seed overflows (got ${seedBody.length})`);
      const state = makeState({
        comments: [{ id: 7, body: seedBody, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
      });
      const freshSha = synthSha(4242);
      state.pullRequest = defaultPullRequest(freshSha);
      const outcome = await runClaimMode({
        event: prOpenedEvent(freshSha),
        env: {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_RUN_ID: 'run-overflow-1',
        } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(outcome.claimed, false, 'overflow claim is an explicit failure, not a partial write');
      assert.match(outcome.reason, /quota-unknown/, 'overflow reports quota-unknown');
      assert.equal(outcome.reviewsUsed, 0, 'overflow consumes no quota');
      assert.equal(state.created, 0, 'overflow creates nothing');
      assert.equal(state.updated, 0, 'overflow updates nothing');
      assert.equal(state.comments[0].body, seedBody, 'old ledger preserved byte-identical');
      assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'A:2 intact after refused claim');
      assert.equal(parseReviewCountLedger(state.comments[0].body).size, 1001, 'no entry lost, none added');
      assert.equal(parseReviewClaimSet(state.comments[0].body).size, 0, 'refused claim leaves no marker');
    }

    // --- Test 12 (Phase 1): A x2, multi-SHA, back to A — third A is 0 AI. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-multisha-'));
      try {
        const state = makeState();
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        try {
          const outputPath = path.join(tempDir, 'review.json');
          await runFullPass(state, SHA_A, 'run-m1', outputPath, counter);
          await runFullPass(state, SHA_A, 'run-m2', outputPath, counter);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2);
          await runFullPass(state, SHA_B, 'run-m3', outputPath, counter);
          await runFullPass(state, synthSha(7), 'run-m4', outputPath, counter);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'A:2 survives B and C');
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 1);
          assert.equal(parseReviewCountMarker(state.comments[0].body, synthSha(7)), 1);

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
          assert.equal(thirdTag.reviewGate, 'none');
          const beforeThird = counter.count;
          const thirdReview = await runReviewMode({
            event: thirdEvent,
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-m5' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(thirdReview.verdict, 'INCONCLUSIVE');
          assert.equal(counter.count - beforeThird, 0, 'third A makes zero OpenAI calls');
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
              POCKETGUARD_RUN_ID: 'run-m5',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
          assert.equal(state.updated, updatedBefore, 'third publish updates nothing');
          assert.equal(state.created, createdBefore, 'third publish creates nothing');
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2);
          assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_B), 1);
          assert.equal(parseReviewCountMarker(state.comments[0].body, synthSha(7)), 1);
        } finally {
          restore();
        }
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 13 (Phase 1): same-key claim retry never adds a second slot. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-claimretry-'));
      try {
        const state = makeState();
        const first = await runClaimPass(state, SHA_A, 'run-dup-1');
        assert.equal(first.claimed, true);
        assert.equal(first.used, 1);
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);
        const second = await runClaimPass(state, SHA_A, 'run-dup-1');
        assert.equal(second.claimed, true, 'same-key re-entry still owns its slot');
        assert.equal(second.used, 1, 'same-key re-entry consumes nothing more');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'retry never increments');
        assert.equal(state.comments.length, 1, 'single sticky throughout');

        // Same-key publish retry after a real review also stays at 1.
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        const outputPath = path.join(tempDir, 'review.json');
        try {
          await runReviewMode({
            event: prOpenedEvent(SHA_A),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-dup-1' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
        } finally {
          restore();
        }
        const publishEnv = {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_OUTPUT: outputPath,
          POCKETGUARD_REVIEW_JOB_RESULT: 'success',
          POCKETGUARD_TAG_LABELS: '[]',
          POCKETGUARD_RUN_ID: 'run-dup-1',
        } as NodeJS.ProcessEnv;
        await runPublishMode({ event: prOpenedEvent(SHA_A), env: publishEnv, githubClient: makeClient(state) });
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1);
        await runPublishMode({ event: prOpenedEvent(SHA_A), env: publishEnv, githubClient: makeClient(state) });
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 1, 'publish retry never increments');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Test 14 (Phase 1): heavy findings trim the summary, never metadata. ---
    {
      const roles = ['chief', 'android_sec', 'android_code'] as const;
      const output = {
        verdict: 'NEEDS_CHANGES' as const,
        pullRequestNumber: 41,
        baseSha: BASE_SHA,
        headSha: SHA_A,
        headRepository: REPO,
        roles: roles.map((role, roleIdx) => ({
          role,
          modelUsed: 'fake-model',
          verdict: 'NEEDS_CHANGES' as const,
          findings: Array.from({ length: roleIdx === 0 ? 100 : 50 }, (_, i) => {
            const global = roleIdx * 100 + i;
            const severity = global < 5 ? ('BLOCK' as const) : ('SUGGESTION' as const);
            return {
              severity,
              file: `src/Heavy${global}.kt`,
              line: global + 1,
              issue: `${global < 5 ? `HFBLOCK${global}` : `HFSUG${global}`} ${'y'.repeat(800)}`,
              suggestion: `fix it ${'z'.repeat(200)}`,
            };
          }),
        })),
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 0 },
        deterministicViolations: [],
        areaLabels: [],
        changedFiles: ['app/src/main/java/demo/Safe.kt'],
        changedFilesComplete: true,
        suggestedLabels: [],
      };
      const ledger = new Map<string, number>([[SHA_A, 2], [SHA_B, 1]]);
      for (let i = 0; i < 5; i += 1) ledger.set(synthSha(i), 1);
      const claims = new Set<string>([`${SHA_A}:run-hf:1`]);
      const reserve = estimateStickyMetadataLength(ledger, claims);
      const visible = reviewComment(output, [], '報告行', reserve);
      assert.ok(visible.length <= MAX_STICKY_TOTAL_LENGTH, `reserved visible stays in budget (got ${visible.length})`);
      const stamped = buildStampedBody(visible, ledger, claims, undefined, undefined, undefined);
      assert.ok(stamped.length <= GITHUB_COMMENT_HARD_LIMIT, `stamped heavy review fits (got ${stamped.length})`);
      assert.equal(parseReviewCountMarker(stamped, SHA_A), 2, 'ledger metadata intact under heavy findings');
      assert.equal(parseReviewCountMarker(stamped, SHA_B), 1);
      for (let i = 0; i < 5; i += 1) assert.equal(parseReviewCountMarker(stamped, synthSha(i)), 1);
      assert.ok(parseReviewClaimSet(stamped).has(`${SHA_A}:run-hf:1`), 'claim metadata intact');
      assert.ok(verifyLedgerFooter(stamped, parseReviewCountLedger(stamped), parseReviewClaimSet(stamped)), 'footer verifies');
      assert.ok(stamped.includes('<!-- PocketGuard-review -->'), 'review marker intact');
      assert.ok(stamped.includes(SHA_A), 'head SHA metadata intact');
      assert.ok(stamped.includes('共200項發現'), 'tail counts line intact');
      assert.ok(stamped.includes('省略'), 'trimming is disclosed, not silent');
      for (let i = 0; i < 5; i += 1) assert.ok(stamped.includes(`HFBLOCK${i}`), `BLOCK ${i} kept by priority`);
    }

    // --- Test 15 (Phase 1): 300 SHAs x1~2 + 300 claims stay fully readable. ---
    // Seed A:2 plus 300 synthetic SHAs with alternating 1~2 counts and one
    // claim per synthetic SHA. buildStampedBody must fit, the footer must
    // match both sizes, and a readStickyLedger re-read must match both sizes
    // with A:2 intact and no entry lost.
    {
      const ledger = new Map<string, number>([[SHA_A, 2]]);
      for (let i = 0; i < 300; i += 1) ledger.set(synthSha(i), (i % 2) + 1);
      const claims = new Set<string>();
      for (let i = 0; i < 300; i += 1) claims.add(`${synthSha(i)}:run-t15-${i}:1`);
      assert.equal(ledger.size, 301, '300 synthetic SHAs plus A');
      assert.equal(claims.size, 300, 'one claim per synthetic SHA');
      const visibleBase = '<!-- PocketGuard-review -->\n\n## PocketGuard 審查\n';
      const measured = buildStampedBodyWithLength(visibleBase, ledger, claims, undefined, undefined, undefined);
      assert.equal(measured.requiredLength, measured.body.length, 'requiredLength is exact');
      assert.equal(measured.fits, true, '300+300 stamped body fits the hard limit');
      assert.ok(measured.body.length <= GITHUB_COMMENT_HARD_LIMIT, `stamped fits (got ${measured.body.length})`);
      const footer = parseLedgerFooter(measured.body);
      assert.ok(footer, 'stamped body carries a ledger footer');
      assert.equal(footer!.counts, ledger.size, 'footer counts match the ledger');
      assert.equal(footer!.claims, claims.size, 'footer claims match the claim set');
      const parsedLedger = parseReviewCountLedger(measured.body);
      const parsedClaims = parseReviewClaimSet(measured.body);
      assert.equal(parsedLedger.size, ledger.size, 'all counts parse');
      assert.equal(parsedClaims.size, claims.size, 'all claims parse');
      assert.equal(
        estimateStickyMetadataLength(parsedLedger, parsedClaims),
        estimateStickyMetadataLength(ledger, claims),
        'estimate is stable across a parse round-trip',
      );
      assert.equal(verifyLedgerFooter(measured.body, parsedLedger, parsedClaims), true, 'fresh footer verifies');
      assert.equal(parsedLedger.get(SHA_A), 2, 'A:2 survives 300 other SHAs');
      for (const idx of [0, 1, 42, 299]) {
        assert.equal(parsedLedger.get(synthSha(idx)), (idx % 2) + 1, `synthetic SHA ${idx} keeps its 1~2 count`);
        assert.ok(parsedClaims.has(`${synthSha(idx)}:run-t15-${idx}:1`), `claim ${idx} survives stamping`);
      }
      const state = makeState({
        comments: [{ id: 7, body: measured.body, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
      });
      const reread = await readStickyLedger(makeClient(state), 'sample', 'repository', 41);
      assert.equal(reread.ok, true, 'large sticky re-reads cleanly with a valid footer');
      if (reread.ok) {
        assert.equal(reread.ledger.size, ledger.size, 're-read ledger size matches');
        assert.equal(reread.claims.size, claims.size, 're-read claim size matches');
        assert.equal(reread.ledger.get(SHA_A), 2, 'A:2 survives the sticky re-read');
        for (const idx of [0, 1, 42, 299]) {
          assert.equal(reread.ledger.get(synthSha(idx)), (idx % 2) + 1, `re-read SHA ${idx} intact`);
        }
        assert.ok(reread.claims.has(`${synthSha(42)}:run-t15-42:1`), 're-read claim marker intact');
        assert.equal(verifyLedgerFooter(reread.body, reread.ledger, reread.claims), true, 're-read footer verifies');
        const rereadFooter = parseLedgerFooter(reread.body);
        assert.equal(rereadFooter!.counts, ledger.size, 're-read footer counts match');
        assert.equal(rereadFooter!.claims, claims.size, 're-read footer claims match');
      }
      assert.equal(state.comments.length, 1, 'single sticky throughout');
    }

    // --- Test 16 (Phase 1): heavy 200 findings over a large ledger keep metadata. ---
    // A 302-entry ledger reserves metadata headroom, then a 200-finding review
    // (5 BLOCK + rest SUGGESTION) is rendered with that reserve and stamped.
    // Either the stamped body fits with BLOCK priority, an omission tail, and
    // a valid footer — or the over-limit path fails closed without truncation.
    {
      const ledger = new Map<string, number>([[SHA_A, 2], [SHA_B, 1]]);
      for (let i = 0; i < 300; i += 1) ledger.set(synthSha(10000 + i), 1);
      const claims = new Set<string>([`${SHA_A}:run-hflarge:1`]);
      const reserve = estimateStickyMetadataLength(ledger, claims);
      assert.ok(reserve > 20000, `large ledger reserves headroom (got ${reserve})`);
      const roles = ['chief', 'android_sec', 'android_code'] as const;
      const output = {
        verdict: 'NEEDS_CHANGES' as const,
        pullRequestNumber: 41,
        baseSha: BASE_SHA,
        headSha: SHA_A,
        headRepository: REPO,
        roles: roles.map((role, roleIdx) => ({
          role,
          modelUsed: 'fake-model',
          verdict: 'NEEDS_CHANGES' as const,
          findings: Array.from({ length: roleIdx === 0 ? 100 : 50 }, (_, i) => {
            const global = roleIdx * 100 + i;
            const severity = global < 5 ? ('BLOCK' as const) : ('SUGGESTION' as const);
            return {
              severity,
              file: `src/Heavy${global}.kt`,
              line: global + 1,
              issue: `${global < 5 ? `HFLBLOCK${global}` : `HFLSUG${global}`} ${'y'.repeat(800)}`,
              suggestion: `fix it ${'z'.repeat(200)}`,
            };
          }),
        })),
        coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 0 },
        deterministicViolations: [],
        areaLabels: [],
        changedFiles: ['app/src/main/java/demo/Safe.kt'],
        changedFilesComplete: true,
        suggestedLabels: [],
      };
      const visible = reviewComment(output, [], '報告行', reserve);
      assert.ok(visible.length <= MAX_STICKY_TOTAL_LENGTH, `reserved visible stays in budget (got ${visible.length})`);
      const measured = buildStampedBodyWithLength(visible, ledger, claims, undefined, undefined, undefined);
      if (!measured.fits) {
        assert.ok(measured.requiredLength > GITHUB_COMMENT_HARD_LIMIT, `over-limit large+heavy fails closed (needs ${measured.requiredLength})`);
        assert.throws(
          () => assertStampedBodyFits(visible, ledger, claims, undefined, undefined, undefined),
          /comment limit/,
          'fit-or-throw refuses to truncate metadata under heavy findings',
        );
      } else {
        const stamped = measured.body;
        assert.ok(stamped.length <= GITHUB_COMMENT_HARD_LIMIT, `stamped heavy+large fits (got ${stamped.length})`);
        assert.equal(parseReviewCountMarker(stamped, SHA_A), 2, 'ledger metadata intact under heavy findings');
        assert.equal(parseReviewCountMarker(stamped, SHA_B), 1);
        assert.equal(parseReviewCountMarker(stamped, synthSha(10000)), 1, 'large-ledger entry intact');
        assert.equal(parseReviewCountMarker(stamped, synthSha(10299)), 1, 'large-ledger tail intact');
        assert.ok(parseReviewClaimSet(stamped).has(`${SHA_A}:run-hflarge:1`), 'claim metadata intact');
        assert.ok(verifyLedgerFooter(stamped, parseReviewCountLedger(stamped), parseReviewClaimSet(stamped)), 'footer verifies');
        const footer = parseLedgerFooter(stamped);
        assert.equal(footer!.counts, ledger.size, 'footer counts match the large ledger');
        assert.equal(footer!.claims, claims.size, 'footer claims match');
        assert.ok(stamped.includes('共200項發現'), 'tail counts line intact');
        assert.ok(stamped.includes('省略'), 'trimming is disclosed, not silent');
        for (let i = 0; i < 5; i += 1) assert.ok(stamped.includes(`HFLBLOCK${i}`), `BLOCK ${i} kept by priority over SUGGESTION`);
      }
    }

    // --- Test 17 (Phase 1): 500 counts + 500 claims already overflow, claim fails closed. ---
    // The seed itself exceeds the hard limit; a new claim must report
    // claimed:false/quota-unknown with reviewsUsed 0, write nothing, and leave
    // the old ledger byte-identical with both sizes unchanged and a valid footer.
    {
      const seedLedger = new Map<string, number>([[SHA_A, 2]]);
      for (let i = 0; i < 500; i += 1) seedLedger.set(synthSha(i), 1);
      const seedClaims = new Set<string>();
      for (let i = 0; i < 500; i += 1) seedClaims.add(`${synthSha(i)}:run-ov-${i}:1`);
      const seedBody = buildStampedBody('<!-- PocketGuard-review -->\nold\n', seedLedger, seedClaims, undefined, undefined, undefined);
      assert.ok(seedBody.length > GITHUB_COMMENT_HARD_LIMIT, `500+500 seed overflows (got ${seedBody.length})`);
      const seedFooter = parseLedgerFooter(seedBody);
      assert.ok(seedFooter, 'overflow seed carries a footer');
      assert.equal(seedFooter!.counts, seedLedger.size, 'seed footer counts match (501)');
      assert.equal(seedFooter!.claims, seedClaims.size, 'seed footer claims match (500)');
      assert.equal(
        verifyLedgerFooter(seedBody, parseReviewCountLedger(seedBody), parseReviewClaimSet(seedBody)),
        true,
        'overflow seed footer verifies before the refused claim',
      );
      const ledgerBefore = parseReviewCountLedger(seedBody).size;
      const claimsBefore = parseReviewClaimSet(seedBody).size;
      assert.equal(ledgerBefore, 501, 'seed holds 501 counts');
      assert.equal(claimsBefore, 500, 'seed holds 500 claims');
      const state = makeState({
        comments: [{ id: 7, body: seedBody, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
      });
      const freshSha = synthSha(5000);
      state.pullRequest = defaultPullRequest(freshSha);
      const outcome = await runClaimMode({
        event: prOpenedEvent(freshSha),
        env: {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: REPO,
          GITHUB_TOKEN: 'fake-token',
          POCKETGUARD_RUN_ID: 'run-ov-new-1',
        } as NodeJS.ProcessEnv,
        githubClient: makeClient(state),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.equal(outcome.claimed, false, 'overflow claim is an explicit failure, not a partial write');
      assert.match(outcome.reason, /quota-unknown/, 'overflow reports quota-unknown');
      assert.equal(outcome.reviewsUsed, 0, 'overflow consumes no quota');
      assert.equal(state.created, 0, 'overflow creates nothing');
      assert.equal(state.updated, 0, 'overflow updates nothing');
      assert.equal(state.comments[0].body, seedBody, 'old ledger preserved byte-identical');
      assert.equal(parseReviewCountLedger(state.comments[0].body).size, ledgerBefore, 'count size unchanged');
      assert.equal(parseReviewClaimSet(state.comments[0].body).size, claimsBefore, 'claim size unchanged');
      assert.equal(parseReviewCountLedger(state.comments[0].body).size, 501, 'no entry lost, none added');
      assert.equal(parseReviewClaimSet(state.comments[0].body).size, 500, 'refused claim leaves no marker');
      assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'A:2 intact after refused claim');
      assert.equal(
        verifyLedgerFooter(state.comments[0].body, parseReviewCountLedger(state.comments[0].body), parseReviewClaimSet(state.comments[0].body)),
        true,
        'footer still verifies after the refused claim',
      );
      const afterFooter = parseLedgerFooter(state.comments[0].body);
      assert.equal(afterFooter!.counts, 501, 'footer counts unchanged');
      assert.equal(afterFooter!.claims, 500, 'footer claims unchanged');
    }

    // --- Test 18 (Phase 1): workflow reporting for the claim-slot job. ---
    // Claim outputs pass through GITHUB_OUTPUT; an over-limit claimed==false
    // keeps every review-send PR branch false; a gate-none publish skips
    // without touching the sticky.
    {
      // (a) Claim GITHUB_OUTPUT passthrough: success then overflow.
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-outputs-'));
      try {
        const successFile = path.join(tempDir, 'github_output_success');
        fs.writeFileSync(successFile, '', 'utf8');
        const successState = makeState();
        successState.pullRequest = defaultPullRequest(SHA_A);
        const success = await runClaimMode({
          event: prOpenedEvent(SHA_A),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-out-ok-1',
            GITHUB_OUTPUT: successFile,
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(successState),
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(success.claimed, true, 'success claim owns its slot');
        assert.equal(success.reviewsUsed, 1);
        const successText = fs.readFileSync(successFile, 'utf8');
        assert.ok(successText.includes('claimed=true'), 'GITHUB_OUTPUT carries claimed=true');
        assert.ok(successText.includes('reviews_used=1'), 'GITHUB_OUTPUT carries reviews_used=1');
        assert.ok(successText.includes(`claim_sha=${SHA_A.toLowerCase()}`), 'GITHUB_OUTPUT carries the claim SHA');
        assert.ok(successText.includes('claim_reason='), 'GITHUB_OUTPUT carries the claim reason');

        const overflowLedger = new Map<string, number>([[SHA_A, 2]]);
        for (let i = 0; i < 500; i += 1) overflowLedger.set(synthSha(i), 1);
        const overflowClaims = new Set<string>();
        for (let i = 0; i < 500; i += 1) overflowClaims.add(`${synthSha(i)}:run-out-ov-${i}:1`);
        const overflowBody = buildStampedBody('<!-- PocketGuard-review -->\nold\n', overflowLedger, overflowClaims, undefined, undefined, undefined);
        assert.ok(overflowBody.length > GITHUB_COMMENT_HARD_LIMIT, 'overflow fixture exceeds the limit');
        const overflowFile = path.join(tempDir, 'github_output_overflow');
        fs.writeFileSync(overflowFile, '', 'utf8');
        const overflowState = makeState({
          comments: [{ id: 7, body: overflowBody, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        const overflowSha = synthSha(5001);
        overflowState.pullRequest = defaultPullRequest(overflowSha);
        const overflow = await runClaimMode({
          event: prOpenedEvent(overflowSha),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-out-ov-1',
            GITHUB_OUTPUT: overflowFile,
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(overflowState),
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(overflow.claimed, false, 'overflow claim fails closed');
        assert.equal(overflow.reviewsUsed, 0);
        const overflowText = fs.readFileSync(overflowFile, 'utf8');
        assert.ok(overflowText.includes('claimed=false'), 'GITHUB_OUTPUT carries claimed=false on overflow');
        assert.ok(overflowText.includes('reviews_used=0'), 'GITHUB_OUTPUT carries reviews_used=0 on overflow');
        assert.ok(overflowText.includes('claim_reason='), 'GITHUB_OUTPUT carries the overflow reason');
        assert.match(overflow.reason, /quota-unknown/, 'overflow reason is quota-unknown');

        // (b) Workflow wiring: claim outputs feed review-send and publish gates.
        const workflow = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/pocketguard.yml'), 'utf8');
        const claimIdx = workflow.indexOf('  claim-slot:');
        const reviewIdx = workflow.indexOf('  review-send:');
        const publishIdx = workflow.indexOf('  publish:');
        assert.ok(claimIdx !== -1 && reviewIdx !== -1 && publishIdx !== -1, 'claim/review/publish jobs exist');
        const claimJob = workflow.slice(claimIdx, reviewIdx);
        const reviewJob = workflow.slice(reviewIdx, publishIdx);
        const publishJob = workflow.slice(publishIdx);
        const outputsBlock = claimJob.slice(claimJob.indexOf('outputs:'), claimJob.indexOf('    steps:'));
        assert.match(outputsBlock, /claimed:/, 'claim-slot forwards claimed');
        assert.match(outputsBlock, /claim_sha:/, 'claim-slot forwards claim_sha');
        assert.match(outputsBlock, /reviews_used:/, 'claim-slot forwards reviews_used');
        const claimedGates = reviewJob.match(/needs\.claim-slot\.outputs\.claimed\s*==\s*'true'/g) ?? [];
        assert.ok(claimedGates.length >= 2, `both PR review-send branches require the approved claim (got ${claimedGates.length})`);
        assert.match(reviewJob, /needs:\s*\[prepare-tag,\s*claim-slot\]/, 'review-send waits for the claim');
        assert.match(publishJob, /review_gate\s*!=\s*'none'/, 'publish skips when the gate is none');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }

      // (c) Runtime: overflow claimed==false means zero AI; gate-none publish is a no-op.
      {
        const overflowLedger = new Map<string, number>([[SHA_A, 2]]);
        for (let i = 0; i < 500; i += 1) overflowLedger.set(synthSha(6000 + i), 1);
        const overflowClaims = new Set<string>();
        for (let i = 0; i < 500; i += 1) overflowClaims.add(`${synthSha(6000 + i)}:run-rt-ov-${i}:1`);
        const overflowBody = buildStampedBody('<!-- PocketGuard-review -->\nold\n', overflowLedger, overflowClaims, undefined, undefined, undefined);
        const state = makeState({
          comments: [{ id: 7, body: overflowBody, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        const freshSha = synthSha(7000);
        state.pullRequest = defaultPullRequest(freshSha);
        const failed = await runClaimMode({
          event: prOpenedEvent(freshSha),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-rt-ov-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(failed.claimed, false, 'overflow claim fails so review-send must stay skipped');
        const counter = { count: 0 };
        const restore = installCountingOpenAI(counter, { verdict: 'APPROVE', summary: 'ok', findings: [] });
        const outputPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-rt-')), 'review.json');
        try {
          const reviewed = await runReviewMode({
            event: prOpenedEvent(freshSha),
            env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target', POCKETGUARD_OUTPUT: outputPath, POCKETGUARD_RUN_ID: 'run-rt-ov-1' }),
            githubClient: makeClient(state),
            writeStdout: () => undefined,
            runGit: safeGitStub(),
          });
          assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'review without its own claim falls back');
          assert.equal(counter.count, 0, 'failed claim means zero OpenAI calls (review-send stays false)');
        } finally {
          restore();
          fs.rmSync(path.dirname(outputPath), { recursive: true, force: true });
        }
        const bodyBefore = state.comments[0].body;
        const createdBefore = state.created;
        const updatedBefore = state.updated;
        await runPublishMode({
          event: prOpenedEvent(freshSha),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: outputPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
            POCKETGUARD_RUN_ID: 'run-rt-ov-1',
          } as NodeJS.ProcessEnv,
          githubClient: makeClient(state),
        });
        assert.equal(state.comments[0].body, bodyBefore, 'overflow publish leaves the sticky byte-identical');
        assert.equal(state.created, createdBefore, 'overflow publish creates nothing');
        assert.equal(state.updated, updatedBefore, 'overflow publish updates nothing');
      }

      // (d) Gate-none publish skips without touching the sticky.
      {
        const sticky = stickyBodyFor(new Map([[SHA_A, 2]]));
        const state = makeState({
          comments: [{ id: 7, body: sticky, user: { login: 'pocketguard[bot]', type: 'Bot' } }],
        });
        state.pullRequest = defaultPullRequest(SHA_A);
        const tagged = await runTagMode({
          event: prOpenedEvent(SHA_A),
          env: openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
          githubClient: makeClient(state),
          writeStdout: () => undefined,
          runGit: safeGitStub(),
        });
        assert.equal(tagged.reviewsUsed, 2);
        assert.equal(tagged.reviewGate, 'none', 'quota-exhausted tag closes the gate');
        const bodyBefore = state.comments[0].body;
        const createdBefore = state.created;
        const updatedBefore = state.updated;
        const missingPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-ledger-gatenone-')), 'absent.json');
        try {
          await runPublishMode({
            event: prOpenedEvent(SHA_A),
            env: {
              GITHUB_EVENT_NAME: 'pull_request_target',
              GITHUB_REPOSITORY: REPO,
              GITHUB_TOKEN: 'fake-token',
              POCKETGUARD_OUTPUT: missingPath,
              POCKETGUARD_REVIEW_JOB_RESULT: 'success',
              POCKETGUARD_TAG_LABELS: '[]',
              POCKETGUARD_RUN_ID: 'run-gate-none-rt-1',
            } as NodeJS.ProcessEnv,
            githubClient: makeClient(state),
          });
        } finally {
          fs.rmSync(path.dirname(missingPath), { recursive: true, force: true });
        }
        assert.equal(state.comments[0].body, bodyBefore, 'gate-none publish leaves the sticky untouched');
        assert.equal(state.created, createdBefore, 'gate-none publish creates nothing');
        assert.equal(state.updated, updatedBefore, 'gate-none publish updates nothing');
        assert.equal(parseReviewCountMarker(state.comments[0].body, SHA_A), 2, 'A:2 preserved');
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
