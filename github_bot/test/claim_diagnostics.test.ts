import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  buildStampedBody,
  classifyStickyWriteError,
  parseReviewClaimSet,
  parseReviewCountLedger,
  runClaimMode,
  sanitizeErrorSummary,
  type RunnerContext,
} from '../src/github_runner';

// Hosted claim-slot diagnostics regressions (sanitized, never logs tokens,
// headers, response bodies, or diffs). Every scenario here mirrors a hosted
// failure shape that previously exited 1 with a generic message and no JSON:
// the claim must resolve fail-closed with an http-<status>-<op> reason and a
// parseable stdout line.

const BASE_SHA = 'a'.repeat(40);
const SHA_A = 'b'.repeat(40);
const SHA_B = 'c'.repeat(40);
const REPO = 'sample/repository';
const RUN_ID = 'run-diag-1';
const TOKEN_PATTERN = /gh[pousr]_[A-Za-z0-9_]{16,}/;

type FakeComment = { id: number; body: string; user: { login: string; type: string } };

interface FakeState {
  comments: FakeComment[];
  created: number;
  updated: number;
  nextId: number;
  listCalls: number;
  pullRequest: Record<string, unknown>;
}

interface FakeHooks {
  // Injected write failures (Octokit-shaped errors: status plus message only).
  listCommentsErrorAfterFirstCall?: () => Error;
  updateCommentError?: () => Error;
  createCommentError?: () => Error;
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
    nextId: 100,
    listCalls: 0,
    pullRequest: defaultPullRequest(SHA_A),
    ...overrides,
  };
}

// Octokit-shaped failure: only an HTTP status and a generic message, never
// headers or a response body.
function httpError(message: string, status: number): Error {
  return Object.assign(new Error(message), { status });
}

function makeClient(state: FakeState, hooks: FakeHooks = {}): NonNullable<RunnerContext['githubClient']> {
  return {
    rest: {
      pulls: {
        get: async () => ({ data: state.pullRequest }),
      },
      users: {
        getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }),
      },
      repos: {
        getCollaboratorPermissionLevel: async () => ({ data: { permission: 'write' } }),
      },
      issues: {
        listComments: async () => {
          state.listCalls += 1;
          // The baseline ledger read succeeds once; every later list (inside
          // publishStickyComment) fails, which is the hosted split-fake shape.
          if (hooks.listCommentsErrorAfterFirstCall && state.listCalls > 1) {
            throw hooks.listCommentsErrorAfterFirstCall();
          }
          return { data: state.comments };
        },
        createComment: async (params: { body: string }) => {
          if (hooks.createCommentError) throw hooks.createCommentError();
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
          if (hooks.updateCommentError) throw hooks.updateCommentError();
          state.updated += 1;
          const comment = state.comments.find((c) => c.id === params.comment_id);
          if (comment) comment.body = params.body;
          return {};
        },
      },
    },
  } as unknown as NonNullable<RunnerContext['githubClient']>;
}

function claimEnv(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  return {
    GITHUB_EVENT_NAME: 'pull_request_target',
    GITHUB_REPOSITORY: REPO,
    GITHUB_TOKEN: 'fake-token',
    POCKETGUARD_RUN_ID: RUN_ID,
    ...extra,
  } as NodeJS.ProcessEnv;
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

// Same-repo readable diff with one changed file.
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

// Empty diff: merge-base still resolves, `diff --name-only` is empty.
function emptyDiffGitStub(): (args: string[]) => string {
  return (args: string[]) => {
    if (args[0] === 'fetch') return '';
    if (args[0] === 'merge-base') return BASE_SHA;
    return '';
  };
}

function seededStickyBody(): string {
  return buildStampedBody(
    '<!-- PocketGuard-review -->\n\n## PocketGuard Review\n\n**Verdict: APPROVE**\n',
    new Map([[SHA_B, 1]]),
    new Set(),
    undefined,
    undefined,
    undefined,
  );
}

function stickyComment(body: string): FakeComment {
  return { id: 7, body, user: { login: 'pocketguard[bot]', type: 'Bot' } };
}

function assertNoTokenShapes(text: string): void {
  assert.equal(TOKEN_PATTERN.test(text), false, `token shape leaked: ${text}`);
  // Every Bearer occurrence must be the sanitized placeholder, never a credential.
  for (const match of text.match(/Bearer\s+\S+/gi) ?? []) {
    assert.equal(
      match.toLowerCase().startsWith('bearer [redacted-token]'),
      true,
      `bearer credential leaked: ${match}`,
    );
  }
}

export async function runClaimDiagnosticsTests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    // --- Unit: classifyStickyWriteError keeps only op + HTTP status. ---
    {
      // Publish tags the failing op/status on the wrapped error.
      assert.deepEqual(
        classifyStickyWriteError(Object.assign(new Error('wrapped'), { stickyOp: 'create', stickyStatus: 403 })),
        { op: 'create', status: 403 },
        'tagged error reports the tagged op and status',
      );
      // Raw Octokit errors expose a top-level status.
      assert.deepEqual(
        classifyStickyWriteError(httpError('Resource not accessible by integration', 403)),
        { op: 'update', status: 403 },
        'octokit status is read without tagging',
      );
      // ...or a nested response status.
      assert.deepEqual(
        classifyStickyWriteError(Object.assign(new Error('boom'), { response: { status: 422 } })),
        { op: 'update', status: 422 },
        'nested response status is read',
      );
      // A cause-wrapped publish tag is recovered too.
      assert.deepEqual(
        classifyStickyWriteError(Object.assign(new Error('outer'), { cause: Object.assign(new Error('inner'), { stickyOp: 'list', stickyStatus: 500 }) })),
        { op: 'list', status: 500 },
        'cause-wrapped tag is recovered',
      );
      // Untagged plain failures keep a conservative default and no status.
      const plain = classifyStickyWriteError(new Error('plain failure'));
      assert.deepEqual(Object.keys(plain).sort(), ['op'], 'plain failure carries no status key');
      assert.equal(plain.op, 'update');
      assert.deepEqual(classifyStickyWriteError('string failure'), { op: 'update' });
      assert.deepEqual(classifyStickyWriteError(null), { op: 'update' });
      assert.deepEqual(classifyStickyWriteError(42), { op: 'update' });
      // Non-HTTP status values are never invented.
      assert.deepEqual(Object.keys(classifyStickyWriteError(Object.assign(new Error('x'), { status: 99 }))).sort(), ['op']);
      assert.deepEqual(Object.keys(classifyStickyWriteError(Object.assign(new Error('x'), { status: '403' }))).sort(), ['op']);
      // The classifier output never carries the original message.
      const serialized = JSON.stringify(classifyStickyWriteError(httpError('boom-with-secret-token', 500)));
      assert.equal(serialized.includes('boom'), false, 'classifier never serializes the error message');
      assert.equal(serialized.includes('token'), false, 'classifier never serializes the error message');
    }

    // --- Unit: sanitizeErrorSummary redacts token shapes, then slices. ---
    {
      const secretPat = `ghp_${'A'.repeat(36)}`;
      const secretSrv = `ghs_${'B'.repeat(36)}`;
      const summary = sanitizeErrorSummary(
        new Error(`request failed for https://api.github.com/x with token ${secretPat} and ${secretSrv} and Authorization: Bearer ${secretSrv}`),
      );
      assert.equal(summary.includes(secretPat), false, 'PAT is redacted');
      assert.equal(summary.includes(secretSrv), false, 'server token is redacted');
      assertNoTokenShapes(summary);
      assert.ok(summary.includes('[redacted-token]'), 'redaction marker present');

      // Sliced to 200 chars and newline-collapsed.
      const long = sanitizeErrorSummary(new Error(`x${'y'.repeat(500)}\nsecond line`));
      assert.equal(long.length, 200, 'summary is sliced to 200 chars');
      assert.equal(long.includes('\n'), false, 'newlines collapse to spaces');

      // Non-Error inputs never throw.
      assert.equal(sanitizeErrorSummary('plain string failure'), 'plain string failure');
      assert.equal(sanitizeErrorSummary({ weird: true }).length > 0, true);
    }

    // --- Regression: createComment 403 resolves fail-closed with stdout JSON. ---
    // The hosted GITHUB_TOKEN failure used to throw a generic error, so the
    // claim job exited 1 with no evidence. It must now resolve, emit JSON,
    // and name only the HTTP status and the failing op.
    {
      const state = makeState();
      const outputs: string[] = [];
      const result = await runClaimMode({
        event: prOpenedEvent(SHA_A),
        env: claimEnv(),
        githubClient: makeClient(state, {
          createCommentError: () => httpError('Resource not accessed by integration', 403),
        }),
        writeStdout: (text: string) => outputs.push(text),
        runGit: safeGitStub(),
      });
      assert.equal(result.claimed, false, 'a failed create never claims');
      assert.equal(result.reviewsUsed, 0, 'nothing consumed on a failed write');
      assert.equal(result.issueNumber, 41, 'PR number is reported');
      assert.equal(result.claimSha, SHA_A, 'claim SHA is reported');
      assert.equal(
        result.reason,
        'claim-failed: sticky write failed (http-403-create); fail-closed',
        'reason carries the sanitized status and op only',
      );
      assert.equal(state.comments.length, 0, 'no comment was written');
      assert.equal(state.created, 0, 'create did not record');
      assert.equal(state.updated, 0, 'nothing was updated');

      assert.equal(outputs.length, 1, 'exactly one stdout line');
      const parsed = JSON.parse(outputs[0]);
      assert.equal(parsed.claimed, false, 'stdout JSON carries claimed:false');
      assert.equal(parsed.reviewsUsed, 0);
      assert.ok(parsed.reason.includes('http-403-create'), 'stdout JSON carries the reason');
      assertNoTokenShapes(outputs[0]);
    }

    // --- Regression: publish-internal list failure (split fake). ---
    // The baseline ledger read succeeds; the list inside publishStickyComment
    // fails. The claim resolves as claim-failed with op=list and never writes.
    {
      const state = makeState();
      const outputs: string[] = [];
      const result = await runClaimMode({
        event: prOpenedEvent(SHA_A),
        env: claimEnv(),
        githubClient: makeClient(state, {
          listCommentsErrorAfterFirstCall: () => httpError('list failed', 500),
        }),
        writeStdout: (text: string) => outputs.push(text),
        runGit: safeGitStub(),
      });
      assert.equal(result.claimed, false);
      assert.equal(result.reviewsUsed, 0);
      assert.ok(
        result.reason.includes('http-500-list'),
        `list failure names the list op (got ${result.reason})`,
      );
      assert.ok(result.reason.startsWith('claim-failed:'), 'list failure is claim-failed, not quota-unknown');
      assert.ok(state.listCalls >= 2, 'the failing list happened inside publish');
      assert.equal(state.comments.length, 0, 'list failure writes nothing');
      assert.equal(state.created, 0);
      assert.equal(state.updated, 0);
      assertNoTokenShapes(JSON.stringify(result));

      // Same split fake without an HTTP status degrades to http-unknown-list.
      const statusless = makeState();
      const withoutStatus = await runClaimMode({
        event: prOpenedEvent(SHA_A),
        env: claimEnv(),
        githubClient: makeClient(statusless, {
          listCommentsErrorAfterFirstCall: () => new Error('plain list failure'),
        }),
        writeStdout: () => undefined,
        runGit: safeGitStub(),
      });
      assert.ok(
        withoutStatus.reason.includes('http-unknown-list'),
        `statusless list failure degrades (got ${withoutStatus.reason})`,
      );
      assert.equal(withoutStatus.claimed, false);
    }

    // --- Regression: updateComment 422 preserves the old ledger byte-identical. ---
    {
      const seeded = seededStickyBody();
      assert.equal(parseReviewCountLedger(seeded).get(SHA_B), 1, 'seed ledger carries B:1');
      const state = makeState({ comments: [stickyComment(seeded)] });
      const outputs: string[] = [];
      const result = await runClaimMode({
        event: prOpenedEvent(SHA_A),
        env: claimEnv(),
        githubClient: makeClient(state, {
          updateCommentError: () => httpError('Validation Failed', 422),
        }),
        writeStdout: (text: string) => outputs.push(text),
        runGit: safeGitStub(),
      });
      assert.equal(result.claimed, false, 'a failed update never claims');
      assert.equal(result.reviewsUsed, 0, 'existing ledger count is reported');
      assert.ok(
        result.reason.includes('http-422-update'),
        `update failure names the update op (got ${result.reason})`,
      );
      assert.equal(state.comments[0].body, seeded, 'the old ledger is preserved byte-identical');
      assert.equal(state.created, 0, 'no second sticky is created');
      assert.equal(state.updated, 0, 'the update did not record');
      assert.equal(parseReviewClaimSet(seeded).size, 0, 'no claim marker was left behind');
      assert.equal(parseReviewCountLedger(seeded).size, 1, 'no ledger entry lost or added');
      assertNoTokenShapes(outputs.join(''));
    }

    // --- Regression: unwritable GITHUB_OUTPUT still yields stdout JSON. ---
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-claim-diag-output-'));
      try {
        // Point GITHUB_OUTPUT at a path whose parent directory does not exist
        // so appendFileSync fails without touching the real environment.
        const missingOutput = path.join(tempDir, 'absent-directory', 'output');

        // Fail-closed path: the JSON still reaches stdout.
        const failed = makeState();
        const failedOutputs: string[] = [];
        const failedResult = await runClaimMode({
          event: prOpenedEvent(SHA_A),
          env: claimEnv({ GITHUB_OUTPUT: missingOutput }),
          githubClient: makeClient(failed, {
            createCommentError: () => httpError('Resource not accessed by integration', 403),
          }),
          writeStdout: (text: string) => failedOutputs.push(text),
          runGit: safeGitStub(),
        });
        assert.equal(failedResult.claimed, false);
        assert.equal(failedOutputs.length, 1, 'fail-closed JSON still emitted');
        assert.ok(failedOutputs[0].includes('http-403-create'));
        assert.equal(fs.existsSync(missingOutput), false, 'no workflow output file was created');

        // Success path: same degradation, still exactly one JSON line.
        const ok = makeState();
        const okOutputs: string[] = [];
        const okResult = await runClaimMode({
          event: prOpenedEvent(SHA_A),
          env: claimEnv({ GITHUB_OUTPUT: missingOutput }),
          githubClient: makeClient(ok),
          writeStdout: (text: string) => okOutputs.push(text),
          runGit: emptyDiffGitStub(),
        });
        assert.equal(okResult.claimed, true, 'unwritable GITHUB_OUTPUT never blocks the claim');
        assert.equal(okOutputs.length, 1, 'success JSON still emitted');
        const parsed = JSON.parse(okOutputs[0]);
        assert.equal(parsed.claimed, true);
        assert.equal(parsed.reviewsUsed, 1);
        assert.equal(fs.existsSync(missingOutput), false, 'no workflow output file was created');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // --- Happy path: empty diff plus a fresh create claims the slot. ---
    {
      const state = makeState();
      const outputs: string[] = [];
      const result = await runClaimMode({
        event: prOpenedEvent(SHA_A),
        env: claimEnv(),
        githubClient: makeClient(state),
        writeStdout: (text: string) => outputs.push(text),
        runGit: emptyDiffGitStub(),
      });
      assert.equal(result.claimed, true, 'an empty diff still claims');
      assert.equal(result.reviewsUsed, 1, 'one slot consumed');
      assert.equal(state.created, 1, 'the claim created exactly one sticky');
      assert.equal(state.comments.length, 1);
      assert.equal(parseReviewCountLedger(state.comments[0].body).get(SHA_A), 1, 'ledger records A:1');
      assert.ok(parseReviewClaimSet(state.comments[0].body).has(`${SHA_A}:${RUN_ID}:1`), 'claim marker recorded');
      const parsed = JSON.parse(outputs[0]);
      assert.equal(parsed.claimed, true);
      assert.ok(parsed.reason.startsWith('claimed:'), `reason describes the claim (got ${parsed.reason})`);
    }

    // --- Regression: a token-shaped fake error never leaks into any output. ---
    {
      const secretPat = `ghp_${'C'.repeat(36)}`;
      const secretSrv = `ghs_${'D'.repeat(36)}`;
      const secretUser = `ghu_${'E'.repeat(36)}`;
      const fakeMessage = [
        `create failed: POST /repos/${REPO}/issues/41/comments returned 403`,
        `authorization: token ${secretPat}`,
        `x-github-session: ${secretSrv}`,
        `Bearer ${secretUser}`,
      ].join('\n');
      const state = makeState();
      const outputs: string[] = [];
      const result = await runClaimMode({
        event: prOpenedEvent(SHA_A),
        env: claimEnv(),
        githubClient: makeClient(state, { createCommentError: () => httpError(fakeMessage, 403) }),
        writeStdout: (text: string) => outputs.push(text),
        runGit: safeGitStub(),
      });
      assert.equal(result.claimed, false);
      const surfaces = [result.reason, outputs.join(''), JSON.stringify(result)];
      for (const surface of surfaces) {
        assert.equal(surface.includes(secretPat), false, 'PAT never reaches stdout/reason');
        assert.equal(surface.includes(secretSrv), false, 'server token never reaches stdout/reason');
        assert.equal(surface.includes(secretUser), false, 'user token never reaches stdout/reason');
        assertNoTokenShapes(surface);
      }
      // The reason still identifies the failure without any payload detail.
      assert.equal(result.reason, 'claim-failed: sticky write failed (http-403-create); fail-closed');

      // Same guarantee through the stderr sanitizer used by the main catch.
      const stderrSummary = sanitizeErrorSummary(new Error(fakeMessage), 200);
      assert.equal(stderrSummary.includes(secretPat), false, 'PAT redacted for stderr');
      assert.equal(stderrSummary.includes(secretSrv), false, 'server token redacted for stderr');
      assert.equal(stderrSummary.includes(secretUser), false, 'user token redacted for stderr');
      assertNoTokenShapes(stderrSummary);
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard claim-diagnostics tests] All tests passed.');
}
