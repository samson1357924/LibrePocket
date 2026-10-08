import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
  classifyCommentCommand,
  commentCommandNeedsGitDiff,
  isCommentCommandAllowed,
} from '../src/comment_command';
import {
  runPublishMode,
  runReviewMode,
  runTagMode,
  type RunnerContext,
} from '../src/github_runner';

const TEST_BASE_URL = ['https:', '', 'pocketguard-cpa.test', 'v1'].join('/');
const TEST_ORIGIN = new URL(TEST_BASE_URL).origin;
const BASE_SHA = 'a'.repeat(40);
const HEAD_SHA = 'b'.repeat(40);

function pullRequestEvent(): Record<string, unknown> {
  return {
    action: 'opened',
    repository: { full_name: 'sample/repository' },
    pull_request: {
      number: 41,
      title: 'security: validate capability boundary',
      base: { sha: BASE_SHA, ref: 'main' },
      head: { sha: HEAD_SHA, ref: 'topic', repo: { full_name: 'sample/repository' } },
    },
  };
}

export async function runCommentRunnerTests(): Promise<void> {
  assert.equal(classifyCommentCommand('/review'), 'review');
  assert.equal(classifyCommentCommand('/triage'), 'triage');
  assert.equal(classifyCommentCommand('/explain'), 'explain');
  assert.equal(classifyCommentCommand('/fix'), 'fix');
  assert.equal(classifyCommentCommand('/fix-ci'), 'fix-ci');
  assert.equal(classifyCommentCommand('@pocketguard'), 'review');
  assert.equal(classifyCommentCommand('Please @pocketguard check this'), 'review');
  assert.equal(classifyCommentCommand('/not-supported'), 'unsupported');
  assert.equal(commentCommandNeedsGitDiff('fix-ci'), true);
  assert.equal(commentCommandNeedsGitDiff('triage'), false);
  assert.equal(isCommentCommandAllowed('triage', 'issue'), true);
  assert.equal(isCommentCommandAllowed('triage', 'pull-request'), false);

  const previousFetch = globalThis.fetch;
  globalThis.fetch = (async () => { throw new Error('network disabled in test'); }) as typeof fetch;
  try {
    let tagStdout = '';
    const tagged = await runTagMode({
      event: pullRequestEvent(),
      env: {
        GITHUB_EVENT_NAME: 'pull_request_target',
        GITHUB_REPOSITORY: 'sample/repository',
      } as NodeJS.ProcessEnv,
      writeStdout: (value) => { tagStdout += value; },
      runGit: (args: string[]) => {
        if (args[0] === 'fetch') return '';
        if (args[0] === 'diff' && args[1] === '--name-only') return 'app/src/main/AndroidManifest.xml\0';
        throw new Error('unexpected tag-mode git call');
      },
    });
    assert.equal(tagged.safeReview, true);
    assert.deepEqual(tagged.areaLabels, ['area:delivery']);
    assert.deepEqual(tagged.changedFiles, ['app/src/main/AndroidManifest.xml']);
    assert.equal(tagged.changedFilesComplete, true);
    assert.deepEqual(tagged.labels, ['area:delivery', 'security']);
    assert.deepEqual(JSON.parse(tagStdout).labels, ['area:delivery', 'security']);

    const forkEvent = JSON.parse(JSON.stringify(pullRequestEvent())) as {
      pull_request: { head: { repo: { full_name: string } } };
    };
    forkEvent.pull_request.head.repo.full_name = 'untrusted/fork';
    const forkTag = await runTagMode({
      event: forkEvent,
      env: {
        GITHUB_EVENT_NAME: 'pull_request_target',
        GITHUB_REPOSITORY: 'sample/repository',
      } as NodeJS.ProcessEnv,
      writeStdout: () => undefined,
    });
    assert.equal(forkTag.safeReview, false);

    const mentionContext = {
      event: {
        action: 'created',
        repository: { full_name: 'sample/repository' },
        issue: { number: 41, pull_request: { url: 'unused' }, title: 'topic' },
        comment: { body: '@pocketguard' },
      },
      env: {
        GITHUB_EVENT_NAME: 'issue_comment',
        GITHUB_REPOSITORY: 'sample/repository',
        GITHUB_TOKEN: 'fake-read-token',
      } as NodeJS.ProcessEnv,
      githubClient: {
        rest: {
          pulls: {
            get: async () => ({ data: {
              number: 41,
              base: { sha: BASE_SHA },
              head: { sha: HEAD_SHA, repo: { full_name: 'sample/repository' } },
            } }),
          },
        },
      },
      writeStdout: () => undefined,
      runGit: (args: string[]) => {
        if (args[0] === 'fetch') return '';
        if (args[0] === 'diff' && args[1] === '--name-only') return 'app/src/main/AndroidManifest.xml\0';
        throw new Error('unexpected mention-mode git call');
      },
    } as unknown as RunnerContext;
    const mentioned = await runTagMode(mentionContext);
    assert.equal(mentioned.command, 'review');
    assert.equal(mentioned.safeReview, true);

    const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-comment-runner-'));
    try {
      const outputPath = path.join(tempDirectory, 'review-output.json');
      const reviewContext: RunnerContext = {
        event: pullRequestEvent(),
        env: {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: 'sample/repository',
          GITHUB_TOKEN: 'fake-read-token',
          POCKETGUARD_SAFE_REVIEW: 'true',
          POCKETGUARD_CPA_STUB: '1',
          POCKETGUARD_OUTPUT: outputPath,
          CPA_BASE_URL: TEST_BASE_URL,
          CPA_API_KEY: 'fake-cpa-key',
          POCKETGUARD_CPA_ORIGIN: TEST_ORIGIN,
          POCKETGUARD_MODEL_CHIEF: 'fake-chief-model',
          POCKETGUARD_MODEL_ANDROID_SEC: 'fake-sec-model',
          POCKETGUARD_MODEL_ANDROID_CODE: 'fake-code-model',
        } as NodeJS.ProcessEnv,
        writeStdout: () => undefined,
        runGit: (args) => {
          if (args[0] === 'fetch') return '';
          if (args[0] === 'diff' && args[1] === '--name-only') {
            return 'app/src/main/AndroidManifest.xml\0app/src/main/java/demo/Safe.kt\0';
          }
          const file = args[args.length - 1];
          if (file === 'app/src/main/AndroidManifest.xml') {
            return [
              'diff --git a/app/src/main/AndroidManifest.xml b/app/src/main/AndroidManifest.xml',
              '--- a/app/src/main/AndroidManifest.xml',
              '+++ b/app/src/main/AndroidManifest.xml',
              '@@ -1,1 +1,1 @@',
              '+<manifest />',
            ].join('\n');
          }
          return [
            'diff --git a/app/src/main/java/demo/Safe.kt b/app/src/main/java/demo/Safe.kt',
            '--- a/app/src/main/java/demo/Safe.kt',
            '+++ b/app/src/main/java/demo/Safe.kt',
            '@@ -1,1 +1,1 @@',
            '+class Safe',
          ].join('\n');
        },
      };
      const reviewed = await runReviewMode(reviewContext);
      assert.equal(reviewed.verdict, 'APPROVE');
      assert.equal(reviewed.roles.length, 3);
      assert.equal(reviewed.coverage.complete, true);
      const persistedReview = JSON.parse(fs.readFileSync(outputPath, 'utf8')) as {
        verdict: string;
        areaLabels: string[];
        changedFiles: string[];
        changedFilesComplete: boolean;
      };
      assert.equal(persistedReview.verdict, 'APPROVE');
      assert.deepEqual(persistedReview.areaLabels, ['area:delivery']);
      assert.deepEqual(persistedReview.changedFiles, [
        'app/src/main/AndroidManifest.xml',
        'app/src/main/java/demo/Safe.kt',
      ]);
      assert.equal(persistedReview.changedFilesComplete, true);

      let unsafeGitCalls = 0;
      const genericPath = path.join(tempDirectory, 'fork-review-output.json');
      const untrusted = await runReviewMode({
        event: forkEvent,
        env: {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: 'sample/repository',
          POCKETGUARD_SAFE_REVIEW: 'false',
          POCKETGUARD_OUTPUT: genericPath,
        } as NodeJS.ProcessEnv,
        runGit: () => {
          unsafeGitCalls += 1;
          throw new Error('must not fetch fork changes');
        },
        writeStdout: () => undefined,
      });
      assert.equal(untrusted.verdict, 'INCONCLUSIVE');
      assert.equal(untrusted.roles.every((role) => role.modelUsed === 'not-run'), true);
      assert.equal(unsafeGitCalls, 0);

      const truncatedPath = path.join(tempDirectory, 'truncated-review-output.json');
      const truncatedReview = await runReviewMode({
        ...reviewContext,
        env: { ...reviewContext.env, POCKETGUARD_OUTPUT: truncatedPath } as NodeJS.ProcessEnv,
        runGit: (args) => {
          if (args[0] === 'fetch') return '';
          if (args[0] === 'diff' && args[1] === '--name-only') return 'app/src/main/AndroidManifest.xml\0';
          return [
            'diff --git a/app/src/main/AndroidManifest.xml b/app/src/main/AndroidManifest.xml',
            '--- a/app/src/main/AndroidManifest.xml',
            '+++ b/app/src/main/AndroidManifest.xml',
            '@@ -1,1 +1,1 @@',
            `+${'x'.repeat(130000)}`,
          ].join('\n');
        },
      });
      assert.equal(truncatedReview.verdict, 'INCONCLUSIVE');
      assert.deepEqual(truncatedReview.coverage.truncatedFiles, ['app/src/main/AndroidManifest.xml']);

      fs.writeFileSync(outputPath, JSON.stringify({
        ...reviewed,
        verdict: 'NEEDS_CHANGES',
        deterministicViolations: [{
          ruleId: 'SEC-TEST',
          severity: 'BLOCK',
          category: 'security',
          message: 'A deterministic security check blocked this change.',
        }],
      }));

      const fakeClientState = {
        comments: [{
          id: 7,
          body: '<!-- PocketGuard-review --> user-authored marker',
          user: { login: 'contributor', type: 'User' },
        }],
        created: 0,
        updated: 0,
        identityFails: false,
        labels: [] as string[][],
        existingLabels: ['area:docs', 'status:needs-decision'],
      };
      const publishClient = {
        rest: {
          pulls: {
            get: async () => ({ data: {
              number: 41,
              base: { sha: BASE_SHA },
              head: { sha: HEAD_SHA, repo: { full_name: 'sample/repository' } },
            } }),
          },
          users: { getAuthenticated: async () => {
            if (fakeClientState.identityFails) throw new Error('authenticated-user endpoint unavailable');
            return { data: { login: 'pocketguard[bot]' } };
          } },
          issues: {
            listLabelsOnIssue: async () => ({
              data: fakeClientState.existingLabels.map((name) => ({ name })),
            }),
            listComments: async () => ({ data: fakeClientState.comments }),
            createComment: async (params: { body: string }) => {
              fakeClientState.created += 1;
              fakeClientState.comments.push({
                id: 8,
                body: params.body,
                user: { login: 'pocketguard[bot]', type: 'Bot' },
              });
              return {};
            },
            updateComment: async (params: { comment_id: number; body: string }) => {
              fakeClientState.updated += 1;
              const comment = fakeClientState.comments.find((candidate) => candidate.id === params.comment_id);
              if (comment) comment.body = params.body;
              return {};
            },
            addLabels: async (params: { labels: string[] }) => {
              fakeClientState.labels.push(params.labels);
              for (const label of params.labels) {
                if (!fakeClientState.existingLabels.includes(label)) fakeClientState.existingLabels.push(label);
              }
              return {};
            },
            removeLabel: async (params: { name: string }) => {
              fakeClientState.existingLabels = fakeClientState.existingLabels.filter((label) => label !== params.name);
              return {};
            },
          },
        },
      };
      const publishContext: RunnerContext = {
        event: pullRequestEvent(),
        env: {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: 'sample/repository',
          GITHUB_TOKEN: 'fake-publish-token',
          POCKETGUARD_OUTPUT: outputPath,
        } as NodeJS.ProcessEnv,
        githubClient: publishClient as unknown as NonNullable<RunnerContext['githubClient']>,
      };
      await runPublishMode(publishContext);
      assert.equal(fakeClientState.created, 1);
      assert.equal(fakeClientState.updated, 0);
      assert.ok(fakeClientState.labels.some((labels) => labels.includes('security')));
      assert.deepEqual(fakeClientState.existingLabels.filter((label) => label.startsWith('area:')).sort(), ['area:delivery']);

      await runPublishMode(publishContext);
      assert.equal(fakeClientState.created, 1);
      assert.equal(fakeClientState.updated, 1);

      fs.writeFileSync(outputPath, JSON.stringify(reviewed));
      fakeClientState.identityFails = true;
      await runPublishMode(publishContext);
      assert.equal(fakeClientState.updated, 2, 'Bot marker comment is updated if getAuthenticated is forbidden');
      assert.equal(fakeClientState.existingLabels.includes('status:needs-decision'), false);
      assert.deepEqual(fakeClientState.existingLabels.filter((label) => label.startsWith('area:')).sort(), ['area:delivery']);

      const partialCoverage = {
        ...reviewed,
        verdict: 'INCONCLUSIVE',
        coverage: {
          complete: false,
          omittedFiles: ['app/src/main/java/private/Omitted.kt'],
          truncatedFiles: ['app/src/main/java/private/Truncated.kt'],
          originalLength: 654321,
        },
        areaLabels: [],
        changedFiles: [],
        changedFilesComplete: false,
      };
      fs.writeFileSync(outputPath, JSON.stringify(partialCoverage));
      await runPublishMode(publishContext);
      assert.equal(fakeClientState.updated, 3);
      assert.deepEqual(fakeClientState.existingLabels.filter((label) => label.startsWith('area:')).sort(), ['area:delivery']);
      const partialComment = fakeClientState.comments.filter((comment) =>
        comment.user?.type === 'Bot' && comment.body?.includes('PocketGuard-review')).pop()?.body ?? '';
      assert.ok(partialComment.includes(String.raw`app/src/main/java/private/Omitted\.kt`));
      assert.ok(partialComment.includes(String.raw`app/src/main/java/private/Truncated\.kt`));
      assert.ok(partialComment.includes('654321 original diff chars'));
      assert.equal(partialComment.includes('+<manifest />'), false, 'coverage detail does not publish diff content');
    } finally {
      fs.rmSync(tempDirectory, { recursive: true, force: true });
    }
  } finally {
    globalThis.fetch = previousFetch;
  }

  const runnerSource = fs.readFileSync(path.resolve(__dirname, '../src/github_runner.ts'), 'utf8');
  assert.doesNotMatch(runnerSource, /send_cpa/i, 'tag and publish entry must not import the CPA client');
  console.log('[PocketGuard comment/runner tests] All tests passed.');
}
