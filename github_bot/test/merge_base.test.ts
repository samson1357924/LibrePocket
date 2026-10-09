import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  buildReviewDiff,
  resolveMergeBase,
  runReviewMode,
  runTagMode,
  type RunnerContext,
} from '../src/github_runner';
import { MAX_CHANGED_FILES } from '../src/review_diff';

const PR_MANIFEST = 'app/src/main/AndroidManifest.xml';
const MAIN_ONLY_DOC = 'docs/main-only.md';

function git(dir: string, args: string[]): string {
  return execFileSync('git', ['-C', dir, ...args], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
  }) as unknown as string;
}

function writeFile(dir: string, rel: string, content: string): void {
  const full = path.join(dir, rel);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, content);
}

function initRepo(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-merge-base-'));
  git(dir, ['init']);
  git(dir, ['config', 'user.email', 'pocketguard-test@example.invalid']);
  git(dir, ['config', 'user.name', 'PocketGuard Test']);
  git(dir, ['config', 'commit.gpgsign', 'false']);
  return dir;
}

function commitAll(dir: string, message: string): string {
  git(dir, ['add', '-A']);
  git(dir, ['commit', '-q', '-m', message]);
  return git(dir, ['rev-parse', 'HEAD']).trim();
}

interface DivergedFixture {
  dir: string;
  origin: string;
  base: string;
  head: string;
}

// O → main-only commit (base tip); O → PR-only commit (head tip, behind base).
function initDivergedFixture(): DivergedFixture {
  const dir = initRepo();
  writeFile(dir, 'shared.txt', 'shared\n');
  const origin = commitAll(dir, 'origin');
  writeFile(dir, MAIN_ONLY_DOC, '# main only\n');
  const base = commitAll(dir, 'main only');
  git(dir, ['checkout', '-q', origin]);
  writeFile(dir, PR_MANIFEST, '<manifest />\n');
  const head = commitAll(dir, 'pr only');
  return { dir, origin, base, head };
}

function initFastForwardFixture(): { dir: string; base: string; head: string } {
  const dir = initRepo();
  writeFile(dir, 'shared.txt', 'shared\n');
  commitAll(dir, 'origin');
  writeFile(dir, MAIN_ONLY_DOC, '# base file\n');
  const base = commitAll(dir, 'base');
  writeFile(dir, PR_MANIFEST, '<manifest />\n');
  const head = commitAll(dir, 'head');
  return { dir, base, head };
}

function prEvent(baseSha: string, headSha: string): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: 'sample/repository' },
    pull_request: {
      number: 42,
      title: 'feat: merge base regression',
      base: { sha: baseSha, ref: 'main' },
      head: { sha: headSha, ref: 'topic', repo: { full_name: 'sample/repository' } },
    },
  };
}

interface GitCallRecord {
  fetch: string[][];
  mergeBase: string[][];
  diff: string[][];
}

// Real-git runner: fetch is a no-op (fixture objects are local; no network),
// everything else executes against the fixture. Records calls so tests can
// verify the fetch contract (base+head SHAs, no shallow depth, no checkout).
function makeRealGitRunner(dir: string, record: GitCallRecord): (args: string[]) => string {
  return (args: string[]) => {
    if (args[0] === 'fetch') {
      record.fetch.push(args);
      return '';
    }
    if (args[0] === 'merge-base') record.mergeBase.push(args);
    if (args[0] === 'diff') record.diff.push(args);
    return git(dir, args);
  };
}

function tagEnv(): NodeJS.ProcessEnv {
  return {
    GITHUB_EVENT_NAME: 'pull_request_target',
    GITHUB_REPOSITORY: 'sample/repository',
  } as NodeJS.ProcessEnv;
}

export async function runMergeBaseTests(): Promise<void> {
  // 1. Diverged PR (behind default branch): only PR-only content is reviewed.
  {
    const { dir, origin, base, head } = initDivergedFixture();
    try {
      const record: GitCallRecord = { fetch: [], mergeBase: [], diff: [] };
      const runGit = makeRealGitRunner(dir, record);
      const tagged = await runTagMode({
        event: prEvent(base, head),
        env: tagEnv(),
        writeStdout: () => undefined,
        runGit,
      });
      assert.deepEqual(tagged.changedFiles, [PR_MANIFEST]);
      assert.equal(tagged.changedFilesComplete, true);
      assert.deepEqual(tagged.areaLabels, ['area:delivery']);
      assert.ok(!tagged.labels.includes('area:docs'), 'main-only area must not leak into labels');

      // Fetch contract: base+head objects, full history (no --depth), no checkout.
      assert.equal(record.fetch.length, 1);
      assert.ok(record.fetch[0].includes(base) && record.fetch[0].includes(head));
      assert.ok(!record.fetch[0].some((arg) => arg.startsWith('--depth')));
      assert.deepEqual(record.mergeBase, [['merge-base', base, head]]);
      assert.ok(record.diff.length > 0 && record.diff.every((args) => args.includes(head)));
      assert.ok(record.diff.every((args) => !args.includes(base) || args.includes(origin)),
        'per-file diffs compare against the merge-base, not the base tip');

      // The old direct base..head comparison would have included main-only content.
      const direct = git(dir, ['diff', '--name-only', '-z', base, head]).split('\0').filter(Boolean).sort();
      assert.deepEqual(direct, [MAIN_ONLY_DOC, PR_MANIFEST].sort());

      const mergeBase = resolveMergeBase(runGit, base, head);
      assert.equal(mergeBase, origin);
      const reviewDiff = buildReviewDiff({ runGit } as RunnerContext, mergeBase, head, tagged.changedFiles);
      assert.ok(reviewDiff.diff.includes('<manifest />'));
      assert.ok(!reviewDiff.diff.includes('main only'));
      assert.equal(reviewDiff.coverage.complete, true);

      const outputPath = path.join(dir, 'review-output.json');
      const reviewed = await runReviewMode({
        event: prEvent(base, head),
        env: {
          ...tagEnv(),
          POCKETGUARD_SAFE_REVIEW: 'true',
          POCKETGUARD_OPENAI_STUB: '1',
          POCKETGUARD_OUTPUT: outputPath,
          OPENAI_BASE_URL: 'https://pocketguard-openai.test/v1',
          OPENAI_API_KEY: 'fake-openai-key',
          POCKETGUARD_OPENAI_ORIGIN: 'https://pocketguard-openai.test',
          POCKETGUARD_MODEL_CHIEF: 'fake-chief-model',
          POCKETGUARD_MODEL_ANDROID_SEC: 'fake-sec-model',
          POCKETGUARD_MODEL_ANDROID_CODE: 'fake-code-model',
          POCKETGUARD_MODEL_PROFILES: '{"fake-chief-model":"chat","fake-sec-model":"chat","fake-code-model":"chat"}',
        } as NodeJS.ProcessEnv,
        githubClient: {
          rest: {
            users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
            issues: { listComments: async () => ({ data: [] }) },
          },
        } as unknown as RunnerContext['githubClient'],
        writeStdout: () => undefined,
        runGit,
      });
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.deepEqual(reviewed.changedFiles, [PR_MANIFEST]);
      assert.equal(reviewed.changedFilesComplete, true);
      assert.equal(reviewed.coverage.complete, true);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }

  // 2. Fast-forward: merge-base == base, behavior matches the linear diff.
  {
    const { dir, base, head } = initFastForwardFixture();
    try {
      const record: GitCallRecord = { fetch: [], mergeBase: [], diff: [] };
      const runGit = makeRealGitRunner(dir, record);
      assert.equal(resolveMergeBase(runGit, base, head), base);
      const tagged = await runTagMode({
        event: prEvent(base, head),
        env: tagEnv(),
        writeStdout: () => undefined,
        runGit,
      });
      assert.deepEqual(tagged.changedFiles, [PR_MANIFEST]);
      assert.equal(tagged.changedFilesComplete, true);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }

  // 3. Unresolvable merge-base (shallow/isolated history) fails closed with zero OpenAI calls.
  {
    const previousFetch = globalThis.fetch;
    let openaiCalls = 0;
    globalThis.fetch = (async () => {
      openaiCalls += 1;
      throw new Error('network disabled in test');
    }) as typeof fetch;
    try {
      const failingRunners: Array<{ name: string; runGit: (args: string[]) => string }> = [
        {
          name: 'merge-base-throws',
          runGit: (args: string[]) => {
            if (args[0] === 'fetch') return '';
            throw new Error('fatal: shallow history');
          },
        },
        {
          name: 'merge-base-non-sha',
          runGit: (args: string[]) => {
            if (args[0] === 'fetch') return '';
            if (args[0] === 'merge-base') return 'not-a-sha\n';
            throw new Error('must not diff without a merge-base');
          },
        },
        {
          name: 'fetch-throws',
          runGit: () => {
            throw new Error('fetch unavailable');
          },
        },
      ];
      for (const { name, runGit } of failingRunners) {
        const tagged = await runTagMode({
          event: prEvent('a'.repeat(40), 'b'.repeat(40)),
          env: tagEnv(),
          writeStdout: () => undefined,
          runGit,
        });
        assert.deepEqual(tagged.changedFiles, [], `${name}: no partial file list`);
        assert.equal(tagged.changedFilesComplete, false, `${name}: tag marks coverage incomplete`);
        assert.ok(tagged.areaLabels.includes('status:needs-decision'), `${name}: tag requires decision`);

        const before = openaiCalls;
        const reviewed = await runReviewMode({
          event: prEvent('a'.repeat(40), 'b'.repeat(40)),
          env: {
            ...tagEnv(),
            POCKETGUARD_SAFE_REVIEW: 'true',
            POCKETGUARD_OUTPUT: path.join(os.tmpdir(), `pocketguard-${name}-output.json`),
            OPENAI_BASE_URL: 'https://pocketguard-openai.test/v1',
            OPENAI_API_KEY: 'fake-openai-key',
            POCKETGUARD_OPENAI_ORIGIN: 'https://pocketguard-openai.test',
            POCKETGUARD_MODEL_CHIEF: 'fake-chief-model',
            POCKETGUARD_MODEL_ANDROID_SEC: 'fake-sec-model',
            POCKETGUARD_MODEL_ANDROID_CODE: 'fake-code-model',
          POCKETGUARD_MODEL_PROFILES: '{"fake-chief-model":"chat","fake-sec-model":"chat","fake-code-model":"chat"}',
          } as NodeJS.ProcessEnv,
          writeStdout: () => undefined,
          runGit,
        });
        assert.equal(reviewed.verdict, 'INCONCLUSIVE', `${name}: review fails closed`);
        assert.deepEqual(reviewed.changedFiles, [], `${name}: no partial file list`);
        assert.equal(reviewed.changedFilesComplete, false, `${name}: review marks coverage incomplete`);
        assert.equal(openaiCalls - before, 0, `${name}: zero OpenAI calls`);
      }
      assert.throws(() => resolveMergeBase(() => 'zzz', 'a'.repeat(40), 'b'.repeat(40)),
        /unable to resolve merge base/);
      assert.throws(() => resolveMergeBase(() => { throw new Error('exit 128'); }, 'a'.repeat(40), 'b'.repeat(40)),
        /unable to resolve merge base/);
      assert.equal(resolveMergeBase(() => `  ${'b'.repeat(40)}\n`, 'a'.repeat(40), 'b'.repeat(40)),
        'b'.repeat(40));
    } finally {
      globalThis.fetch = previousFetch;
    }
  }

  // 4. Rename + pathspec-magic filenames stay literal (no throw, no exclusion).
  {
    const dir = initRepo();
    try {
      writeFile(dir, 'rename-me.txt', 'rename me\n');
      writeFile(dir, 'normal.txt', 'normal\n');
      const origin = commitAll(dir, 'origin');
      git(dir, ['checkout', '-q', origin]);
      execFileSync('git', ['-C', dir, 'mv', 'rename-me.txt', 'renamed.txt']);
      writeFile(dir, ':(exclude)tricky.txt', 'literal magic\n');
      const head = commitAll(dir, 'rename and magic name');
      const record: GitCallRecord = { fetch: [], mergeBase: [], diff: [] };
      const runGit = makeRealGitRunner(dir, record);
      const mergeBase = resolveMergeBase(runGit, origin, head);
      const changed = git(dir, ['diff', '--name-only', '-z', mergeBase, head]).split('\0').filter(Boolean);
      assert.ok(changed.includes('renamed.txt'), `rename target listed, got: ${JSON.stringify(changed)}`);
      assert.ok(changed.includes(':(exclude)tricky.txt'), `magic name stays literal, got: ${JSON.stringify(changed)}`);
      const reviewDiff = buildReviewDiff({ runGit } as RunnerContext, mergeBase, head, changed);
      assert.ok(reviewDiff.diff.includes(':(exclude)tricky.txt'));
      assert.ok(reviewDiff.diff.includes('renamed.txt'));
      assert.equal(reviewDiff.coverage.complete, true);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }

  // 5. Changed-file cap fails closed (no OpenAI, incomplete coverage).
  {
    const previousFetch = globalThis.fetch;
    let openaiCalls = 0;
    globalThis.fetch = (async () => {
      openaiCalls += 1;
      throw new Error('network disabled in test');
    }) as typeof fetch;
    try {
      const oversized = Array.from({ length: MAX_CHANGED_FILES + 1 }, (_, i) => `f${String(i).padStart(5, '0')}.kt`).join('\0');
      const runGit = (args: string[]): string => {
        if (args[0] === 'fetch') return '';
        if (args[0] === 'merge-base') return 'a'.repeat(40);
        if (args[0] === 'diff' && args[1] === '--name-only') return oversized;
        throw new Error('must not build per-file diffs past the cap');
      };
      const tagged = await runTagMode({
        event: prEvent('a'.repeat(40), 'b'.repeat(40)),
        env: tagEnv(),
        writeStdout: () => undefined,
        runGit,
      });
      assert.equal(tagged.changedFilesComplete, false, 'oversized list is incomplete');
      const before = openaiCalls;
      const reviewed = await runReviewMode({
        event: prEvent('a'.repeat(40), 'b'.repeat(40)),
        env: {
          ...tagEnv(),
          POCKETGUARD_SAFE_REVIEW: 'true',
          POCKETGUARD_OUTPUT: path.join(os.tmpdir(), 'pocketguard-cap-output.json'),
          OPENAI_BASE_URL: 'https://pocketguard-openai.test/v1',
          OPENAI_API_KEY: 'fake-openai-key',
          POCKETGUARD_OPENAI_ORIGIN: 'https://pocketguard-openai.test',
          POCKETGUARD_MODEL_CHIEF: 'fake-chief-model',
          POCKETGUARD_MODEL_ANDROID_SEC: 'fake-sec-model',
          POCKETGUARD_MODEL_ANDROID_CODE: 'fake-code-model',
          POCKETGUARD_MODEL_PROFILES: '{"fake-chief-model":"chat","fake-sec-model":"chat","fake-code-model":"chat"}',
        } as NodeJS.ProcessEnv,
        writeStdout: () => undefined,
        runGit,
      });
      assert.equal(reviewed.verdict, 'INCONCLUSIVE');
      assert.equal(reviewed.changedFilesComplete, false);
      assert.equal(openaiCalls - before, 0, 'zero OpenAI calls past the cap');
    } finally {
      globalThis.fetch = previousFetch;
    }
  }

  // 6. Workflow + source wiring: full history for merge-base, no shallow fetch in code.
  {
    const workflow = fs.readFileSync(
      path.resolve(__dirname, '../../.github/workflows/pocketguard.yml'), 'utf8');
    assert.doesNotMatch(workflow, /fetch-depth:\s*1/, 'no shallow checkout remains');
    assert.equal(workflow.match(/fetch-depth:\s*0/g)?.length, 4, 'tag, claim, review, and publish check out full history');
    assert.match(workflow, /ref:\s*\$\{\{\s*github\.event\.repository\.default_branch\s*\}\}/,
      'checkouts stay on the default branch');
    assert.doesNotMatch(workflow, /ref:\s*.*refs\/pull/, 'never check out PR head refs');
    assert.match(workflow, /persist-credentials:\s*false/, 'checkouts persist no credentials');
    // P1 #1: fork diffs arrive via an explicit runner-side
    // `git fetch origin <base> +refs/pull/<N>/head` (documented in workflow
    // comments) plus a FETCH_HEAD SHA-pin; the checkout itself never takes a
    // PR ref. The runner fetch is read-only: no checkout/switch/clone/reset.
    assert.match(workflow, /refs\/pull/, 'fork fetch refspec is documented');
    const runnerSource = fs.readFileSync(path.resolve(__dirname, '../src/github_runner.ts'), 'utf8');
    assert.match(runnerSource, /merge-base/, 'reviewer resolves the merge-base');
    assert.doesNotMatch(runnerSource, /--depth=1/, 'no shallow fetch that would starve merge-base');
    assert.match(runnerSource, /refs\/pull/, 'runner fetches fork heads via the allowlisted PR refspec');
    assert.doesNotMatch(runnerSource, /git\s+checkout/i, 'runner never checks out fork code');
    assert.doesNotMatch(runnerSource, /'checkout'/, 'runner never shells out to git checkout');
    assert.doesNotMatch(runnerSource, /'switch'/, 'runner never shells out to git switch');
    assert.doesNotMatch(runnerSource, /'clone'/, 'runner never shells out to git clone');
  }

  console.log('[PocketGuard merge-base tests] All tests passed.');
}
