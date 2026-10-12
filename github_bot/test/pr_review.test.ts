import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { collectFileDiffs } from '../src/review_diff';
import {
  defaultGit,
  parseReviewCountMarker,
  reviewComment,
  runClaimMode,
  runPublishMode,
  runReviewMode,
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

function prEvent(): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: REPO },
    pull_request: {
      number: 41,
      title: 'single-turn review',
      base: { sha: BASE_SHA, ref: 'main' },
      head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: REPO } },
    },
  };
}

function fileDiff(name: string, bodyLines: string[]): string {
  return [
    `diff --git a/${name} b/${name}`,
    `--- a/${name}`,
    `+++ b/${name}`,
    '@@ -1,1 +1,1 @@',
    ...bodyLines,
  ].join('\n');
}

function bigFileDiff(name: string, lines: number): string {
  const body = Array.from({ length: lines }, (_, i) => `+line ${i} ${'y'.repeat(40)}`);
  return fileDiff(name, body);
}

function prGitStub(files: Record<string, string>): (args: string[]) => string {
  return (args: string[]) => {
    if (args[0] === 'fetch') return '';
    if (args[0] === 'merge-base') return BASE_SHA;
    if (args[0] === 'diff' && args[1] === '--name-only') {
      return `${Object.keys(files).join('\0')}\0`;
    }
    return files[args[args.length - 1]] ?? '';
  };
}

type StickyComment = { id: number; body: string; user: { login: string; type: string } };

function prClient(state: { comments: StickyComment[]; created: number; updated: number }): NonNullable<RunnerContext['githubClient']> {
  return {
    rest: {
      pulls: {
        get: async () => ({
          data: {
            number: 41,
            base: { sha: BASE_SHA },
            head: { sha: HEAD_SHA, repo: { full_name: REPO } },
          },
        }),
      },
      users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
      issues: {
        listComments: async () => ({ data: state.comments }),
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

function reviewFiles(): Record<string, string> {
  return {
    'app/src/main/AndroidManifest.xml': fileDiff('app/src/main/AndroidManifest.xml', ['+<manifest />']),
    'app/src/main/java/demo/Safe.kt': fileDiff('app/src/main/java/demo/Safe.kt', ['+class Safe']),
    'app/src/main/java/demo/Big.kt': bigFileDiff('app/src/main/java/demo/Big.kt', 2500),
  };
}

export async function runPrReviewTests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    // Unit: collectFileDiffs uses literal pathspecs and fails closed on empty review patches.
    {
      const seen: string[][] = [];
      const diffs = collectFileDiffs(
        (args: string[]) => { seen.push(args); return 'patch'; },
        BASE_SHA, HEAD_SHA, ['weird:(exclude)**'],
      );
      assert.equal(diffs.get('weird:(exclude)**'), 'patch');
      assert.ok(seen.every((args) => args[0] === '--literal-pathspecs'), 'literal pathspecs forced');
      assert.throws(() => collectFileDiffs(() => '', BASE_SHA, HEAD_SHA, ['app/src/main/java/demo/Safe.kt']),
        /no diff for a changed review file/, 'empty review patch fails closed');
    }

    // Regression (hosted smoke): the runner is invoked with
    // `working-directory: github_bot`, so repo-root-relative pathspecs must not
    // be resolved against that cwd. defaultGit pins the git process at the
    // worktree root, so a root-relative pathspec resolves even when
    // process.cwd() is a subdirectory (previously every per-file diff came back
    // empty with exit code 0 and the claim pre-flight failed closed).
    {
      const previousCwd = process.cwd();
      const subdir = path.join(defaultGit(['rev-parse', '--show-toplevel']).trim(), 'github_bot');
      fs.mkdirSync(subdir, { recursive: true });
      try {
        process.chdir(subdir);
        assert.equal(defaultGit(['rev-parse', '--show-prefix']).trim(), '',
          'git runs at the worktree root regardless of process.cwd()');
        // A root-relative pathspec matches from the subdir; from that cwd the
        // interpreted path would be github_bot/github_bot/... and fail (128).
        assert.doesNotThrow(() => defaultGit(['ls-files', '--error-unmatch', '--', 'github_bot/package.json']),
          'root-relative pathspecs resolve from a subdirectory cwd');
      } finally {
        process.chdir(previousCwd);
      }
    }

    // E2E: large multi-file diff gets full coverage and may APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-approve-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'chunk ok', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const files = reviewFiles();
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'APPROVE', 'full coverage may APPROVE');
        assert.equal(reviewed.coverage.complete, true);
        assert.deepEqual(reviewed.coverage.omittedFiles, []);
        assert.deepEqual(reviewed.coverage.truncatedFiles, []);
        assert.equal(counter.count, 4, 'first wave runs 3 roles plus synthesis (got 4)');
        assert.equal(reviewed.synthesis?.verdict, 'APPROVE', 'synthesis confirms APPROVE');
        assert.equal(reviewed.synthesis?.skipped, undefined, 'synthesis ran');
        const sticky = reviewComment(reviewed, [], 'report line');
        assert.ok(sticky.includes('Segments: single segment'), 'single-turn sticky carries single segment line');
        assert.ok(sticky.includes('Verdict: APPROVE'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: diff past the 1M budget truncates with full coverage false and can
    // never APPROVE, even when all roles approve.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-overbudget-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const files = {
            'app/src/main/AndroidManifest.xml': fileDiff('app/src/main/AndroidManifest.xml', ['+<manifest />']),
            'app/src/main/java/demo/Huge.kt': bigFileDiff('app/src/main/java/demo/Huge.kt', 22000),
          };
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'over-budget diff never approves');
        assert.equal(reviewed.coverage.complete, false);
        assert.ok(reviewed.coverage.truncatedFiles.includes('app/src/main/java/demo/Huge.kt'), 'oversized file recorded truncated');
        assert.equal(counter.count, 3, 'single-turn still runs 3 roles in parallel (got 3)');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: deterministic BLOCK over the full diff short-circuits before any AI.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-block-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const secretParts = ['const fakeSecret = "ghp_', `${'A'.repeat(20)}";`];
        const files = {
          ...reviewFiles(),
          'app/src/main/java/demo/Secret.kt': fileDiff('app/src/main/java/demo/Secret.kt', [
            `+${secretParts[0]}`,
            `+${secretParts[1]}`,
          ]),
        };
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'must not run', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'full-diff BLOCK short-circuits');
        assert.equal(counter.count, 0, 'BLOCK makes zero OpenAI calls');
        assert.ok(reviewed.deterministicViolations.some((v) => v.ruleId === 'SEC-PRIVATE-KEY' && v.severity === 'BLOCK'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: any role NEEDS_CHANGES synthesizes NEEDS_CHANGES.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-needs-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'NEEDS_CHANGES', summary: 'blocking issue', findings: [{ severity: 'WARN', issue: 'needs work' }], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'one NEEDS role forces NEEDS_CHANGES');
        assert.equal(reviewed.synthesis?.skipped, true, 'non-APPROVE first round skips synthesis');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: any role INCONCLUSIVE (schema failure) synthesizes INCONCLUSIVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-incon-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'bad', findings: [], suggestedLabels: 'not-an-array' },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'one failed role forces INCONCLUSIVE');
        assert.equal(reviewed.synthesis?.skipped, true, 'non-APPROVE first round skips synthesis');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: synthesis tightens APPROVE to NEEDS_CHANGES with evidenced BLOCK.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-needs-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          {
            verdict: 'NEEDS_CHANGES',
            summary: 'found an issue on second look',
            findings: [{ severity: 'BLOCK', file: 'app/src/main/java/demo/Safe.kt', issue: 'unguarded call', evidence: 'class Safe' }],
            suggestedLabels: [],
          },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'NEEDS_CHANGES', 'evidenced synthesis BLOCK tightens to NEEDS_CHANGES');
        assert.equal(reviewed.synthesis?.verdict, 'NEEDS_CHANGES', 'synthesis recorded');
        assert.equal(counter.count, 4, 'synthesis ran as the fourth turn');
        const architecture = reviewed.roles.find((r) => r.role === 'architecture');
        assert.ok(architecture?.findings.some((f) => f.severity === 'BLOCK' && f.issue === 'unguarded call'), 'synthesis BLOCK attached to architecture role');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: synthesis NEEDS_CHANGES without BLOCK evidence becomes INCONCLUSIVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-warn-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'NEEDS_CHANGES', summary: 'vague worry', findings: [{ severity: 'WARN', issue: 'maybe' }], suggestedLabels: [] },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'evidenceless synthesis dissent cannot NEEDS_CHANGES');
        assert.equal(reviewed.synthesis?.verdict, 'INCONCLUSIVE');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: synthesis schema failure never stores APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-schema-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: 'not-an-array' },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'broken synthesis never stores APPROVE');
        assert.equal(counter.count, 4, 'synthesis was attempted');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: synthesis transport failure never stores APPROVE.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-fail-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const previousFetch = globalThis.fetch;
        const okPayload = { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] };
        globalThis.fetch = (async () => {
          counter.count += 1;
          if (counter.count >= 4) throw new Error('synthesis transport down');
          return new Response(JSON.stringify({
            output: [{ content: [{ type: 'output_text', text: JSON.stringify(okPayload) }] }],
          }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }) as typeof fetch;
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          globalThis.fetch = previousFetch;
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'failed synthesis never stores APPROVE');
        assert.equal(counter.count, 4, 'synthesis was attempted');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E (F3): margin-first budget — 500000 fits two waves
    // (2x249500 + 1000 margin); the old allocate-then-add-margin formula
    // misjudged this as single-wave.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-marginfirst-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_DEADLINE_MS: '500000',
            } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'APPROVE', 'margin-first budget runs both waves');
        assert.equal(reviewed.synthesis?.skipped, undefined, 'synthesis ran, not skipped');
        assert.equal(reviewed.synthesis?.verdict, 'APPROVE', 'synthesis confirmed');
        assert.equal(counter.count, 4, 'both waves ran');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E (F3): per-wave ceiling below the viable threshold forces the
    // single wave; an unverified APPROVE then converges to INCONCLUSIVE
    // (never stored) with synthesis marked skipped.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-skip-downgrade-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_CHUNK_TIMEOUT_MS: '100',
            } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'skipped synthesis never stores APPROVE');
        assert.equal(reviewed.synthesis?.skipped, true, 'synthesis marked skipped');
        assert.equal(counter.count, 3, 'only the first wave ran');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E (F5): the synthesis excerpt carries evidence-anchored tail
    // snippets (not just a head slice), and silent findings truncation is
    // surfaced as shown/total counts.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-evidence-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const tailName = 'app/src/main/java/demo/Big.kt';
        const tailBody = Array.from({ length: 2500 }, (_, i) => `+line ${i} ${'y'.repeat(40)}`);
        const tailDiff = [
          `diff --git a/${tailName} b/${tailName}`,
          `--- a/${tailName}`,
          `+++ b/${tailName}`,
          '@@ -1,1 +1,2500 @@',
          ...tailBody,
        ].join('\n');
        const files = {
          'app/src/main/AndroidManifest.xml': reviewFiles()['app/src/main/AndroidManifest.xml'],
          'app/src/main/java/demo/Safe.kt': reviewFiles()['app/src/main/java/demo/Safe.kt'],
          [tailName]: tailDiff,
        };
        const extraWarns = Array.from({ length: 20 }, (_, i) => ({ severity: 'WARN', issue: `nit ${i}` }));
        const rolePayload = {
          verdict: 'APPROVE',
          summary: 'fine',
          findings: [
            { severity: 'WARN', file: 'app/src/main/java/demo/Big.kt', line: 2400, issue: 'tail check' },
            ...extraWarns,
          ],
          suggestedLabels: [],
        };
        const synthPayload = { verdict: 'APPROVE', summary: 'confirmed', findings: [] };
        const seenBodies: string[] = [];
        const previousFetch = globalThis.fetch;
        globalThis.fetch = (async (_input, init) => {
          counter.count += 1;
          seenBodies.push(String((init as { body?: unknown })?.body ?? ''));
          const payload = counter.count <= 3 ? rolePayload : synthPayload;
          return new Response(JSON.stringify({
            output: [{ content: [{ type: 'output_text', text: JSON.stringify(payload) }] }],
          }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }) as typeof fetch;
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
            } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          globalThis.fetch = previousFetch;
        }
        assert.equal(counter.count, 4, 'both waves ran');
        assert.equal(reviewed.verdict, 'APPROVE', 'synthesis confirmed');
        assert.equal(seenBodies.length, 4, 'four model calls captured');
        const synthInput = seenBodies[3];
        assert.ok(synthInput.includes('+line 2399'), 'F5 synthesis sees the tail hunk window');
        assert.ok(
          synthInput.includes('evidence snippet: app/src/main/java/demo/Big.kt (around line 2400)'),
          'F5 snippet labeled with anchor',
        );
        assert.ok(synthInput.includes('20/21'), 'F5 findings truncation surfaced as shown/total');
        assert.ok(synthInput.includes('Excerpt plan:'), 'F5 excerpt plan header present');
        assert.ok(!synthInput.includes('+line 2499'), 'F5 does not dump the whole tail file');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E (G1): a WARN anchor naming a review-excluded file never pulls
    // its patch into the synthesis prompt, while tail anchors still work.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-excluded-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const tailName = 'app/src/main/java/demo/Big.kt';
        const tailBody = Array.from({ length: 2500 }, (_, i) => `+line ${i} ${'y'.repeat(40)}`);
        const files = {
          'app/src/main/AndroidManifest.xml': reviewFiles()['app/src/main/AndroidManifest.xml'],
          'app/src/main/java/demo/Safe.kt': reviewFiles()['app/src/main/java/demo/Safe.kt'],
          [tailName]: [
            `diff --git a/${tailName} b/${tailName}`,
            `--- a/${tailName}`,
            `+++ b/${tailName}`,
            '@@ -1,1 +1,2500 @@',
            ...tailBody,
          ].join('\n'),
          'app/local.properties': [
            'diff --git a/app/local.properties b/app/local.properties',
            '--- a/app/local.properties',
            '+++ b/app/local.properties',
            '@@ -1,2 +1,2 @@',
            ' sdk.dir=/opt/old',
            '+sdk.dir=/opt/G1-EXCLUDED-MARKER',
          ].join('\n'),
        };
        const rolePayload = {
          verdict: 'APPROVE',
          summary: 'fine',
          findings: [
            { severity: 'WARN', file: tailName, line: 2400, issue: 'tail check' },
            { severity: 'WARN', file: 'app/local.properties', line: 2, issue: 'excluded check' },
          ],
          suggestedLabels: [],
        };
        const synthPayload = { verdict: 'APPROVE', summary: 'confirmed', findings: [] };
        const seenBodies: string[] = [];
        const previousFetch = globalThis.fetch;
        globalThis.fetch = (async (_input, init) => {
          counter.count += 1;
          seenBodies.push(String((init as { body?: unknown })?.body ?? ''));
          const payload = counter.count <= 3 ? rolePayload : synthPayload;
          return new Response(JSON.stringify({
            output: [{ content: [{ type: 'output_text', text: JSON.stringify(payload) }] }],
          }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }) as typeof fetch;
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
            } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          globalThis.fetch = previousFetch;
        }
        assert.equal(counter.count, 4, 'both waves ran');
        assert.equal(reviewed.verdict, 'APPROVE', 'synthesis confirmed');
        const synthInput = seenBodies[3];
        assert.ok(!synthInput.includes('G1-EXCLUDED-MARKER'), 'G1 excluded patch never reaches synthesis');
        assert.ok(synthInput.includes('+line 2399'), 'G1 tail anchor still snippeted');
        assert.ok(synthInput.includes('local.properties') && synthInput.includes('omitted'), 'G1 excluded anchor recorded omitted');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E (I1): a synthesis BLOCK quoting another hunk of the same file
    // cannot tighten — the review converges to INCONCLUSIVE, never a
    // misattributed NEEDS_CHANGES.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-crosshunk-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const fooName = 'app/src/main/java/demo/Foo.kt';
        const fooDiff = [
          `diff --git a/${fooName} b/${fooName}`,
          `--- a/${fooName}`,
          `+++ b/${fooName}`,
          '@@ -10,2 +10,2 @@',
          ' ctx10',
          '+fun harmless() {}',
          '@@ -100,2 +100,2 @@',
          ' ctx100',
          '+fun dangerousAction() {}',
        ].join('\n');
        const files = {
          'app/src/main/AndroidManifest.xml': reviewFiles()['app/src/main/AndroidManifest.xml'],
          [fooName]: fooDiff,
        };
        const rolePayload = {
          verdict: 'APPROVE',
          summary: 'fine',
          findings: [{ severity: 'WARN', file: fooName, line: 10, issue: 'anchor check' }],
          suggestedLabels: [],
        };
        const synthPayload = {
          verdict: 'NEEDS_CHANGES',
          summary: 'blocking',
          findings: [{ severity: 'BLOCK', file: fooName, line: 10, issue: 'wrong hunk', evidence: 'fun dangerousAction() {}' }],
        };
        const previousFetch = globalThis.fetch;
        globalThis.fetch = (async () => {
          counter.count += 1;
          const payload = counter.count <= 3 ? rolePayload : synthPayload;
          return new Response(JSON.stringify({
            output: [{ content: [{ type: 'output_text', text: JSON.stringify(payload) }] }],
          }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }) as typeof fetch;
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
            } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(files),
          });
        } finally {
          globalThis.fetch = previousFetch;
        }
        assert.equal(counter.count, 4, 'both waves ran');
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'I1 cross-hunk evidence never tightens');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E (F3): a budget too small for even one wave fails closed before any
    // model call (zero AI, INCONCLUSIVE).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-tiny-budget-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'must not run', findings: [], suggestedLabels: [] }]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: {
              ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }),
              POCKETGUARD_OUTPUT: artifactPath,
              POCKETGUARD_REVIEW_DEADLINE_MS: '15000',
            } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'tiny budget fails closed');
        assert.equal(counter.count, 0, 'no model calls on a hopeless budget');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: synthesis APPROVE carrying BLOCK findings is contradictory and
    // can never be stored.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-contra-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          {
            verdict: 'APPROVE',
            summary: 'fine',
            findings: [{ severity: 'BLOCK', file: 'app/src/main/java/demo/Safe.kt', issue: 'contradiction', evidence: 'class Safe' }],
            suggestedLabels: [],
          },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'APPROVE with BLOCK findings is contradictory');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: synthesis BLOCK with unsupported evidence cannot tighten.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-synth-noev-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          { verdict: 'APPROVE', summary: 'fine', findings: [], suggestedLabels: [] },
          {
            verdict: 'NEEDS_CHANGES',
            summary: 'claims an issue without proof',
            findings: [{ severity: 'BLOCK', file: 'app/src/main/java/demo/Safe.kt', issue: 'phantom', evidence: 'no-such-quote-anywhere-zzz' }],
            suggestedLabels: [],
          },
        ]);
        let reviewed!: Awaited<ReturnType<typeof runReviewMode>>;
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          reviewed = await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', 'unevidenced BLOCK cannot tighten');
        assert.equal(reviewed.synthesis?.verdict, 'INCONCLUSIVE');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: forged chunk fields in the artifact are rejected fail-closed.
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-pub-'));
      try {
        const artifactPath = path.join(tempDir, 'review-output.json');
        const counter = { count: 0 };
        const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] }]);
        try {
          const client = prClient({ comments: [], created: 0, updated: 0 });
          await runReviewMode({
            event: prEvent(),
            env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath } as NodeJS.ProcessEnv,
            githubClient: client,
            writeStdout: () => undefined,
            runGit: prGitStub(reviewFiles()),
          });
        } finally {
          restore();
        }
        // Retired chunk keys are outside the artifact schema: a forged
        // artifact carrying them is invalid and falls back on the same sticky.
        const forged = {
          ...JSON.parse(fs.readFileSync(artifactPath, 'utf8')),
          chunks: [{
            index: 0,
            total: 1,
            start: 0,
            end: 100,
            complete: true,
            coveredLength: 100,
            files: ['app/src/main/AndroidManifest.xml'],
            verdict: 'APPROVE' as const,
          }],
          chunkCoverageComplete: true,
          chunkCount: 1,
        } as Record<string, unknown>;
        fs.writeFileSync(artifactPath, JSON.stringify(forged));
        const state = {
          comments: [{ id: 7, body: '<!-- PocketGuard-review -->\nold', user: { login: 'pocketguard[bot]', type: 'Bot' } }],
          created: 0,
          updated: 0,
        };
        await runPublishMode({
          event: prEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_OUTPUT: artifactPath,
            POCKETGUARD_REVIEW_JOB_RESULT: 'success',
            POCKETGUARD_TAG_LABELS: '[]',
          } as NodeJS.ProcessEnv,
          githubClient: prClient(state),
        });
        assert.equal(state.created, 0, 'forged-chunk publish creates no second comment');
        assert.equal(state.updated, 1, 'forged-chunk publish updates the same sticky');
        assert.ok(state.comments[0].body.includes('Verdict: INCONCLUSIVE'), 'forged chunks fall back');
        assert.ok(!state.comments[0].body.includes('Verdict: APPROVE'));
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }

    // E2E: review rounds consume one slot each; re-review budget stays 2 per SHA (claim untouched).
    {
      const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-pr-review-slot-'));
      try {
        const runReviewPublish = async (runId: string, artifactPath: string): Promise<void> => {
          const counter = { count: 0 };
          const restore = installQueuedOpenAI(counter, [{ verdict: 'APPROVE', summary: 'ok', findings: [], suggestedLabels: [] }]);
          try {
            const claimed = await runClaimMode({
              event: prEvent(),
              env: {
                GITHUB_EVENT_NAME: 'pull_request_target',
                GITHUB_REPOSITORY: REPO,
                GITHUB_TOKEN: 'fake-token',
                POCKETGUARD_RUN_ID: runId,
              } as NodeJS.ProcessEnv,
              githubClient: prClient(shared),
              writeStdout: () => undefined,
              runGit: prGitStub(reviewFiles()),
            });
            assert.equal(claimed.claimed, true, `${runId} claims one slot`);
            const client = prClient(shared);
            const reviewed = await runReviewMode({
              event: prEvent(),
              env: { ...openAiEnv({ GITHUB_EVENT_NAME: 'pull_request_target' }), POCKETGUARD_OUTPUT: artifactPath, POCKETGUARD_RUN_ID: runId } as NodeJS.ProcessEnv,
              githubClient: client,
              writeStdout: () => undefined,
              runGit: prGitStub(reviewFiles()),
            });
            assert.equal(counter.count, 4, `${runId} runs first wave plus synthesis (4 calls)`);
            assert.equal(reviewed.synthesis?.verdict, 'APPROVE', `${runId} synthesis confirms`);
            await runPublishMode({
              event: prEvent(),
              env: {
                GITHUB_EVENT_NAME: 'pull_request_target',
                GITHUB_REPOSITORY: REPO,
                GITHUB_TOKEN: 'fake-token',
                POCKETGUARD_OUTPUT: artifactPath,
                POCKETGUARD_REVIEW_JOB_RESULT: 'success',
                POCKETGUARD_TAG_LABELS: '[]',
                POCKETGUARD_RUN_ID: runId,
              } as NodeJS.ProcessEnv,
              githubClient: prClient(shared),
            });
          } finally {
            restore();
          }
        };
        const shared: { comments: StickyComment[]; created: number; updated: number } = { comments: [], created: 0, updated: 0 };
        await runReviewPublish('run-a', path.join(tempDir, 'a-output.json'));
        assert.equal(parseReviewCountMarker(shared.comments[0]?.body ?? '', HEAD_SHA), 1, 'first round holds 1 slot');
        const stickyId = shared.comments[0]?.id;
        await runReviewPublish('run-b', path.join(tempDir, 'b-output.json'));
        assert.equal(parseReviewCountMarker(shared.comments[0]?.body ?? '', HEAD_SHA), 2, 're-review holds the 2nd slot');
        assert.equal(shared.comments.length, 1, 'single sticky across rounds');
        assert.equal(shared.comments[0]?.id, stickyId, 'same sticky ID updated in place');
        assert.ok(shared.comments[0]?.body.includes('Verdict: APPROVE'), 'single-turn APPROVE publishes');
        assert.ok(shared.comments[0]?.body.includes('Segments: single segment'), 'single-turn sticky carries single segment line');
        // Third round is quota-exhausted: claim refuses, budget stays 2.
        const denied = await runClaimMode({
          event: prEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: REPO,
            GITHUB_TOKEN: 'fake-token',
            POCKETGUARD_RUN_ID: 'run-c',
          } as NodeJS.ProcessEnv,
          githubClient: prClient(shared),
          writeStdout: () => undefined,
          runGit: prGitStub(reviewFiles()),
        });
        assert.equal(denied.claimed, false, 'third round claims nothing (budget 2)');
        assert.equal(parseReviewCountMarker(shared.comments[0]?.body ?? '', HEAD_SHA), 2, 'budget stays 2');
      } finally {
        fs.rmSync(tempDir, { recursive: true, force: true });
      }
    }
  } finally {
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard pr-review tests] All tests passed.');
}
