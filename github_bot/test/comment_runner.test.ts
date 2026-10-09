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
  isRepositoryOwner,
  routeEvent,
  routeReviewFlags,
  runPublishMode,
  runReviewMode,
  runTagMode,
  type RunnerContext,
} from '../src/github_runner';

const TEST_BASE_URL = ['https:', '', 'pocketguard-openai.test', 'v1'].join('/');
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
  assert.equal(classifyCommentCommand('@pocketguard review'), 'review');
  assert.equal(classifyCommentCommand('@pocketguard /review'), 'review');
  assert.equal(classifyCommentCommand('@POCKETGUARD REVIEW'), 'review');
  assert.equal(classifyCommentCommand('／review'), 'review');
  // Bare "@pocketguard" (no verb) is no longer a review command (S3).
  assert.equal(classifyCommentCommand('@pocketguard'), 'unsupported');
  assert.equal(classifyCommentCommand('Please @pocketguard check this'), 'unsupported');
  assert.equal(classifyCommentCommand('Please @pocketguard review this'), 'review');
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
      githubClient: {
        rest: {
          users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
          issues: { listComments: async () => ({ data: [] }) },
        },
      } as unknown as RunnerContext['githubClient'],
      writeStdout: (value) => { tagStdout += value; },
      runGit: (args: string[]) => {
        if (args[0] === 'fetch') return '';
        if (args[0] === 'merge-base') return BASE_SHA;
        if (args[0] === 'diff' && args[1] === '--name-only') return 'app/src/main/AndroidManifest.xml\0';
        throw new Error('unexpected tag-mode git call');
      },
    });
    assert.equal(tagged.safeReview, true);
    assert.equal(tagged.authorized, true, 'pull_request_target carries no commenter gate');
    assert.deepEqual(tagged.areaLabels, ['area:delivery']);
    assert.deepEqual(tagged.changedFiles, ['app/src/main/AndroidManifest.xml']);
    assert.equal(tagged.changedFilesComplete, true);
    assert.deepEqual(tagged.labels, ['area:delivery', 'security']);
    assert.deepEqual(JSON.parse(tagStdout).labels, ['area:delivery', 'security']);
    assert.equal(tagged.routeKind, 'first-review');
    assert.equal(tagged.shouldReview, true);
    assert.equal(tagged.shouldTag, true);
    assert.ok(tagged.reason.length > 0);
    assert.deepEqual(
      { should_review: JSON.parse(tagStdout).shouldReview, should_tag: JSON.parse(tagStdout).shouldTag },
      { should_review: true, should_tag: true },
    );
    assert.equal(JSON.parse(tagStdout).routeKind, 'first-review');

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
    assert.equal(forkTag.authorized, true, 'pull_request_target origin deny still reports authorized');

    const mentionContext = {
      event: {
        action: 'created',
        repository: { full_name: 'sample/repository' },
        issue: { number: 41, pull_request: { url: 'unused' }, title: 'topic' },
        comment: { body: '@pocketguard review', user: { login: 'maintainer', type: 'User' } },
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
          users: {
            getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }),
          },
          issues: {
            listComments: async () => ({ data: [] }),
          },
          repos: {
            getCollaboratorPermissionLevel: async () => ({ data: { permission: 'write' } }),
          },
        },
      },
      writeStdout: () => undefined,
      runGit: (args: string[]) => {
        if (args[0] === 'fetch') return '';
        if (args[0] === 'merge-base') return BASE_SHA;
        if (args[0] === 'diff' && args[1] === '--name-only') return 'app/src/main/AndroidManifest.xml\0';
        throw new Error('unexpected mention-mode git call');
      },
    } as unknown as RunnerContext;
    const mentioned = await runTagMode(mentionContext);
    assert.equal(mentioned.command, 'review');
    assert.equal(mentioned.safeReview, true);
    assert.equal(mentioned.authorized, true, 'authorized maintainer mention');
    assert.equal(mentioned.routeKind, 'manual-pr-review');
    assert.equal(mentioned.shouldReview, true);
    assert.equal(mentioned.shouldTag, true);
    assert.ok(mentioned.reason.length > 0);

    // Bare "@pocketguard" (no verb) is unsupported since S3: authorized but
    // never safe for review, and routed to ignore.
    const bareMention = await runTagMode({
      ...mentionContext,
      event: {
        ...(mentionContext.event as Record<string, unknown>),
        comment: { body: '@pocketguard', user: { login: 'maintainer', type: 'User' } },
      },
    });
    assert.equal(bareMention.command, 'unsupported');
    assert.equal(bareMention.safeReview, false, 'bare mention is never safe for review');
    assert.equal(bareMention.authorized, true, 'bare mention by a writer still passes the auth gate');
    assert.equal(bareMention.routeKind, 'ignore');
    assert.equal(bareMention.shouldReview, false);
    assert.equal(bareMention.shouldTag, false);

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
          POCKETGUARD_OPENAI_STUB: '1',
          POCKETGUARD_OUTPUT: outputPath,
          OPENAI_BASE_URL: TEST_BASE_URL,
          OPENAI_API_KEY: 'fake-openai-key',
          POCKETGUARD_OPENAI_ORIGIN: TEST_ORIGIN,
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
        } as unknown as NonNullable<RunnerContext['githubClient']>,
        writeStdout: () => undefined,
        runGit: (args) => {
          if (args[0] === 'fetch') return '';
          if (args[0] === 'merge-base') return BASE_SHA;
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
      assert.equal(reviewed.roles.every((role) => role.verdict === 'APPROVE'), true,
        'a no-blocker review still reaches the OpenAI stub');
      assert.equal(reviewed.coverage.complete, true);
      const persistedReview = JSON.parse(fs.readFileSync(outputPath, 'utf8')) as {
        verdict: string;
        pullRequestNumber: number;
        baseSha: string;
        headSha: string;
        headRepository: string;
        areaLabels: string[];
        changedFiles: string[];
        changedFilesComplete: boolean;
      };
      assert.equal(reviewed.pullRequestNumber, 41);
      assert.equal(reviewed.baseSha, BASE_SHA);
      assert.equal(reviewed.headSha, HEAD_SHA);
      assert.equal(reviewed.headRepository, 'sample/repository');
      assert.equal(persistedReview.verdict, 'APPROVE');
      assert.equal(persistedReview.pullRequestNumber, 41);
      assert.equal(persistedReview.baseSha, BASE_SHA);
      assert.equal(persistedReview.headSha, HEAD_SHA);
      assert.equal(persistedReview.headRepository, 'sample/repository');
      assert.deepEqual(persistedReview.areaLabels, ['area:delivery']);
      assert.deepEqual(persistedReview.changedFiles, [
        'app/src/main/AndroidManifest.xml',
        'app/src/main/java/demo/Safe.kt',
      ]);
      assert.equal(persistedReview.changedFilesComplete, true);

      const blockerOutputPath = path.join(tempDirectory, 'deterministic-block-review-output.json');
      const blockerEnv = {
        ...reviewContext.env,
        POCKETGUARD_OUTPUT: blockerOutputPath,
      } as NodeJS.ProcessEnv;
      delete blockerEnv.POCKETGUARD_OPENAI_STUB;
      const fakeSplitSecretParts = ['const fakeSecret = "ghp_', `${'A'.repeat(20)}";`];
      let openaiRequestCount = 0;
      const fetchBeforeBlockerReview = globalThis.fetch;
      globalThis.fetch = (async () => {
        openaiRequestCount += 1;
        throw new Error('unexpected OpenAI request for deterministic blocker');
      }) as typeof fetch;
      const blockedReview = await (async () => {
        try {
          return await runReviewMode({
            ...reviewContext,
            // Quota reads must not touch the network-counted fetch stub; an
            // empty sticky list reports zero prior reviews.
            githubClient: {
              rest: {
                users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
                issues: { listComments: async () => ({ data: [] }) },
              },
            } as unknown as NonNullable<RunnerContext['githubClient']>,
            env: blockerEnv,
            runGit: (args) => {
              if (args[0] === 'fetch') return '';
              if (args[0] === 'merge-base') return BASE_SHA;
              if (args[0] === 'diff' && args[1] === '--name-only') {
                return 'app/src/main/java/demo/Secret.kt\0';
              }
              return [
                'diff --git a/app/src/main/java/demo/Secret.kt b/app/src/main/java/demo/Secret.kt',
                '--- a/app/src/main/java/demo/Secret.kt',
                '+++ b/app/src/main/java/demo/Secret.kt',
                '@@ -1,0 +1,2 @@',
                `+${fakeSplitSecretParts[0]}`,
                `+${fakeSplitSecretParts[1]}`,
              ].join('\n');
            },
          });
        } finally {
          globalThis.fetch = fetchBeforeBlockerReview;
        }
      })();
      assert.equal(openaiRequestCount, 0, 'deterministic BLOCK must prevent every OpenAI request');
      assert.equal(blockedReview.verdict, 'NEEDS_CHANGES');
      assert.ok(blockedReview.deterministicViolations.some((violation) =>
        violation.ruleId === 'SEC-PRIVATE-KEY' && violation.severity === 'BLOCK'));
      assert.equal(blockedReview.roles.every((role) =>
        role.modelUsed === 'not-run' && role.verdict === 'INCONCLUSIVE'), true);
      const persistedBlockerReview = JSON.parse(fs.readFileSync(blockerOutputPath, 'utf8')) as typeof blockedReview;
      assert.equal(persistedBlockerReview.verdict, 'NEEDS_CHANGES');
      assert.equal(persistedBlockerReview.pullRequestNumber, 41);
      assert.equal(persistedBlockerReview.baseSha, BASE_SHA);
      assert.equal(persistedBlockerReview.headSha, HEAD_SHA);
      assert.equal(persistedBlockerReview.headRepository, 'sample/repository');
      assert.deepEqual(persistedBlockerReview.changedFiles, ['app/src/main/java/demo/Secret.kt']);
      assert.equal(persistedBlockerReview.changedFilesComplete, true);
      assert.equal(persistedBlockerReview.coverage.complete, true);
      assert.ok(persistedBlockerReview.deterministicViolations.some((violation) =>
        violation.ruleId === 'SEC-PRIVATE-KEY' && violation.severity === 'BLOCK'));
      assert.equal(persistedBlockerReview.roles.every((role) =>
        role.modelUsed === 'not-run' && role.verdict === 'INCONCLUSIVE'), true);

      let unsafeGitCalls = 0;
      const fallbackPath = path.join(tempDirectory, 'fork-review-output.json');
      const untrusted = await runReviewMode({
        event: forkEvent,
        env: {
          GITHUB_EVENT_NAME: 'pull_request_target',
          GITHUB_REPOSITORY: 'sample/repository',
          POCKETGUARD_SAFE_REVIEW: 'false',
          POCKETGUARD_OUTPUT: fallbackPath,
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
          if (args[0] === 'merge-base') return BASE_SHA;
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

      type TestComment = { id: number; body: string; user: { login: string; type: string } };
      const humanMarkerBody = '<!-- PocketGuard-review --> human-authored marker';
      const priorApproveBody = '<!-- PocketGuard-review -->\n\n## PocketGuard 審查\n\n**判定：APPROVE**\nold result';
      const makePublishHarness = (options: {
        outputPath: string;
        jobResult?: string;
        pullRequest?: unknown;
        getFails?: boolean;
        identityFails?: boolean;
        commentFailure?: 'list' | 'update' | 'create';
        listLabelsFails?: boolean;
        omitBotComment?: boolean;
        addLabelsFails?: boolean;
        removeLabelsFails?: boolean;
        existingLabels?: string[];
        changePullRequestDuringCommentLookup?: unknown;
        changePullRequestAfterCommentUpdate?: unknown;
        event?: Record<string, unknown>;
      }) => {
        const state = {
          comments: [
            ...(!options.omitBotComment
              ? [{ id: 7, body: priorApproveBody, user: { login: 'pocketguard[bot]', type: 'Bot' } }]
              : []),
            { id: 8, body: humanMarkerBody, user: { login: 'contributor', type: 'User' } },
          ] as TestComment[],
          created: 0,
          updated: 0,
          labels: [] as string[][],
          commentWrites: [] as string[],
          existingLabels: options.existingLabels ?? ['area:docs', 'area:policy', 'security', 'performance'],
          operations: [] as string[],
          currentPullRequest: options.pullRequest ?? {
            number: 41,
            base: { sha: BASE_SHA },
            head: { sha: HEAD_SHA, repo: { full_name: 'sample/repository' } },
          },
          lookupChangedPullRequest: false,
        };
        const publishClient = {
          rest: {
            pulls: {
              get: async () => {
                state.operations.push('fresh-get');
                if (options.getFails) throw new Error('GitHub unavailable');
                return { data: state.currentPullRequest };
              },
            },
            users: { getAuthenticated: async () => {
              state.operations.push('authenticated-user');
              if (options.identityFails) throw new Error('sensitive identity detail');
              return { data: { login: 'pocketguard[bot]' } };
            } },
            issues: {
              listLabelsOnIssue: async () => {
                state.operations.push('list-labels');
                if (options.listLabelsFails) throw new Error('synthetic label listing failure');
                return { data: state.existingLabels.map((name) => ({ name })) };
              },
              listComments: async () => {
                state.operations.push('list-comments');
                if (options.commentFailure === 'list') throw new Error('synthetic list failure');
                if (options.changePullRequestDuringCommentLookup && !state.lookupChangedPullRequest) {
                  state.currentPullRequest = options.changePullRequestDuringCommentLookup;
                  state.lookupChangedPullRequest = true;
                }
                return { data: state.comments };
              },
              createComment: async (params: { body: string }) => {
                state.operations.push('create-comment');
                if (options.commentFailure === 'create') throw new Error('synthetic create failure');
                state.commentWrites.push(params.body);
                state.created += 1;
                state.comments.push({
                  id: 9,
                  body: params.body,
                  user: { login: 'pocketguard[bot]', type: 'Bot' },
                });
                return {};
              },
              updateComment: async (params: { comment_id: number; body: string }) => {
                state.operations.push('update-comment');
                if (options.commentFailure === 'update') throw new Error('synthetic update failure');
                state.commentWrites.push(params.body);
                state.updated += 1;
                const comment = state.comments.find((candidate) => candidate.id === params.comment_id);
                if (comment) comment.body = params.body;
                if (state.updated === 1 && options.changePullRequestAfterCommentUpdate) {
                  state.currentPullRequest = options.changePullRequestAfterCommentUpdate;
                }
                return {};
              },
              addLabels: async (params: { labels: string[] }) => {
                state.operations.push('add-labels');
                if (options.addLabelsFails) throw new Error('synthetic label failure');
                state.labels.push(params.labels);
                for (const label of params.labels) {
                  if (!state.existingLabels.includes(label)) state.existingLabels.push(label);
                }
                return {};
              },
              removeLabel: async (params: { name: string }) => {
                state.operations.push('remove-label');
                if (options.removeLabelsFails) throw new Error('sensitive removal failure detail');
                state.existingLabels = state.existingLabels.filter((label) => label !== params.name);
                return {};
              },
            },
          },
        };
        const context: RunnerContext = {
          event: options.event ?? pullRequestEvent(),
          env: {
            GITHUB_EVENT_NAME: 'pull_request_target',
            GITHUB_REPOSITORY: 'sample/repository',
            GITHUB_TOKEN: 'fake-publish-token',
            POCKETGUARD_OUTPUT: options.outputPath,
            POCKETGUARD_REVIEW_JOB_RESULT: options.jobResult ?? 'success',
          } as NodeJS.ProcessEnv,
          githubClient: publishClient as unknown as NonNullable<RunnerContext['githubClient']>,
        };
        return { state, context };
      };

      fs.writeFileSync(outputPath, JSON.stringify(reviewed));
      const blockerPublishHarness = makePublishHarness({ outputPath: blockerOutputPath });
      await runPublishMode(blockerPublishHarness.context);
      const blockerPublishedBody = blockerPublishHarness.state.comments.find((comment) => comment.id === 7)?.body ?? '';
      assert.ok(blockerPublishedBody.includes('判定：NEEDS_CHANGES'),
        'the deterministic blocker artifact must pass strict publish validation');
      assert.equal(blockerPublishedBody.includes('schema or contents are invalid'), false);

      const successHarness = makePublishHarness({ outputPath });
      await runPublishMode(successHarness.context);
      assert.equal(successHarness.state.created, 0);
      assert.equal(successHarness.state.updated, 1, 'the existing bot sticky comment is updated');
      const successfulBody = successHarness.state.comments.find((comment) => comment.id === 7)?.body ?? '';
      assert.ok(successfulBody.includes('判定：APPROVE'));
      assert.ok(successfulBody.includes(`審查的 head SHA：\`${HEAD_SHA}\``));
      assert.equal(successHarness.state.comments.find((comment) => comment.id === 8)?.body, humanMarkerBody);
      assert.equal(successHarness.state.existingLabels.includes('status:needs-decision'), false);
      assert.deepEqual(successHarness.state.existingLabels.filter((label) => label.startsWith('area:')).sort(), ['area:delivery']);
      assert.ok(successHarness.state.existingLabels.includes('security'), 'security is outside the reconciliation scope');
      assert.ok(successHarness.state.existingLabels.includes('performance'), 'performance is outside the reconciliation scope');
      assert.equal(successHarness.state.operations[0], 'fresh-get', 'fresh PR state is checked before writes');

      const incompleteOutputPath = path.join(tempDirectory, 'incomplete-review-output.json');
      fs.writeFileSync(incompleteOutputPath, JSON.stringify({
        ...reviewed,
        verdict: 'INCONCLUSIVE',
        coverage: {
          complete: false,
          omittedFiles: ['app/src/main/java/demo/Skipped.kt'],
          truncatedFiles: [],
          originalLength: 123,
        },
        areaLabels: [],
        changedFiles: [],
        changedFilesComplete: false,
      }));
      const incompleteCoverageHarness = makePublishHarness({ outputPath: incompleteOutputPath });
      await runPublishMode(incompleteCoverageHarness.context);
      assert.ok(incompleteCoverageHarness.state.existingLabels.includes('status:needs-decision'),
        'incomplete coverage must reconcile the implicit maintainer-decision label');
      assert.deepEqual(incompleteCoverageHarness.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
        ['area:docs', 'area:policy'], 'incomplete coverage must preserve existing area labels');

      for (const contradictoryCoverage of [
        {
          name: 'omitted-files-marked-complete',
          coverage: {
            complete: true,
            omittedFiles: ['app/src/main/java/demo/Skipped.kt'],
            truncatedFiles: [],
            originalLength: 123,
          },
        },
        {
          name: 'truncated-files-marked-complete',
          coverage: {
            complete: true,
            omittedFiles: [],
            truncatedFiles: ['app/src/main/java/demo/Truncated.kt'],
            originalLength: 123,
          },
        },
      ]) {
        const contradictoryOutputPath = path.join(tempDirectory, `${contradictoryCoverage.name}.json`);
        fs.writeFileSync(contradictoryOutputPath, JSON.stringify({
          ...reviewed,
          verdict: 'INCONCLUSIVE',
          coverage: contradictoryCoverage.coverage,
          changedFilesComplete: true,
        }));
        const contradictoryHarness = makePublishHarness({ outputPath: contradictoryOutputPath });
        await runPublishMode(contradictoryHarness.context);
        const contradictoryBody = contradictoryHarness.state.comments.find((comment) => comment.id === 7)?.body ?? '';
        assert.ok(contradictoryBody.includes('判定：INCONCLUSIVE'),
          `${contradictoryCoverage.name}: contradictory metadata must fall back to INCONCLUSIVE`);
        assert.ok(contradictoryBody.includes('schema or contents are invalid'),
          `${contradictoryCoverage.name}: reject contradictory coverage metadata`);
        assert.deepEqual(contradictoryHarness.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
          ['area:docs', 'area:policy'], `${contradictoryCoverage.name}: preserve existing area labels`);
        assert.deepEqual(contradictoryHarness.state.labels, [['status:needs-decision']],
          `${contradictoryCoverage.name}: apply only the fallback decision label`);
        assert.equal(contradictoryHarness.state.operations.includes('remove-label'), false,
          `${contradictoryCoverage.name}: never remove labels based on contradictory coverage`);
      }

      const identityFallbackHarness = makePublishHarness({ outputPath, identityFails: true });
      await runPublishMode(identityFallbackHarness.context);
      assert.equal(identityFallbackHarness.state.updated, 1,
        'identity lookup failure still updates the bot-authored marker comment');
      assert.ok(identityFallbackHarness.state.comments.find((comment) => comment.id === 7)?.body.includes('判定：APPROVE'));
      assert.equal(identityFallbackHarness.state.comments.find((comment) => comment.id === 8)?.body, humanMarkerBody,
        'identity fallback must not mutate a human-authored marker comment');

      const assertFailClosed = async (
        name: string,
        options: {
          artifact?: unknown;
          rawText?: string;
          missingArtifact?: boolean;
          jobResult?: string;
          pullRequest?: unknown;
          getFails?: boolean;
          addLabelsFails?: boolean;
          reason: string;
        },
      ) => {
        const casePath = path.join(tempDirectory, `${name}-review-output.json`);
        if (!options.missingArtifact) {
          const text = options.rawText ?? JSON.stringify(options.artifact ?? reviewed);
          fs.writeFileSync(casePath, text);
        }
        const harness = makePublishHarness({
          outputPath: casePath,
          jobResult: options.jobResult,
          pullRequest: options.pullRequest,
          getFails: options.getFails,
          addLabelsFails: options.addLabelsFails,
        });
        await runPublishMode(harness.context);
        assert.equal(harness.state.updated, 1, `${name}: update only the existing bot sticky comment`);
        assert.equal(harness.state.created, 0, `${name}: do not create a duplicate bot comment`);
        const botBody = harness.state.comments.find((comment) => comment.id === 7)?.body ?? '';
        assert.ok(botBody.includes('判定：INCONCLUSIVE'), `${name}: explicitly show INCONCLUSIVE`);
        assert.ok(botBody.includes(options.reason), `${name}: explain why the result is unavailable`);
        assert.equal(botBody.includes('判定：APPROVE'), false, `${name}: never retain/publish the old approval`);
        assert.equal(harness.state.comments.find((comment) => comment.id === 8)?.body, humanMarkerBody,
          `${name}: preserve the human-authored marker comment`);
        assert.ok(harness.state.existingLabels.includes('status:needs-decision'), `${name}: require maintainer decision`);
        assert.deepEqual(harness.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
          ['area:docs', 'area:policy'], `${name}: preserve existing area labels`);
        assert.ok(harness.state.existingLabels.includes('security'), `${name}: preserve security label`);
        assert.ok(harness.state.existingLabels.includes('performance'), `${name}: preserve performance label`);
        assert.equal(harness.state.operations[0], 'fresh-get', `${name}: fresh GET must precede all writes`);
        return harness.state;
      };

      await assertFailClosed('stale-head', {
        artifact: { ...reviewed, headSha: 'c'.repeat(40) },
        reason: 'stale',
      });
      await assertFailClosed('stale-base', {
        artifact: { ...reviewed, baseSha: 'c'.repeat(40) },
        reason: 'stale',
      });
      await assertFailClosed('stale-pr-number', {
        artifact: { ...reviewed, pullRequestNumber: 42 },
        reason: 'stale',
      });
      await assertFailClosed('stale-head-repository', {
        artifact: { ...reviewed, headRepository: 'untrusted/fork' },
        reason: 'stale',
      });
      await assertFailClosed('missing', {
        missingArtifact: true,
        reason: 'missing or unreadable',
      });
      await assertFailClosed('malformed-json', {
        rawText: '{not json',
        reason: 'JSON is malformed',
      });
      await assertFailClosed('invalid-schema', {
        artifact: { ...reviewed, roles: [] },
        reason: 'schema or contents are invalid',
      });
      await assertFailClosed('missing-area-labels', {
        artifact: { ...reviewed, areaLabels: undefined },
        reason: 'schema or contents are invalid',
      });
      await assertFailClosed('incomplete-approval', {
        artifact: { ...reviewed, changedFilesComplete: false },
        reason: 'schema or contents are invalid',
      });
      await assertFailClosed('missing-completeness', {
        artifact: { ...reviewed, changedFilesComplete: undefined },
        reason: 'schema or contents are invalid',
      });
      await assertFailClosed('failed-review-job', {
        jobResult: 'failure',
        reason: 'did not complete successfully',
      });
      await assertFailClosed('cancelled-review-job', {
        jobResult: 'cancelled',
        reason: 'did not complete successfully',
      });
      await assertFailClosed('fresh-get-failure', {
        getFails: true,
        reason: 'could not be fetched from GitHub',
      });

      const changedPullRequest = {
        number: 41,
        base: { sha: BASE_SHA },
        head: { sha: 'c'.repeat(40), repo: { full_name: 'sample/repository' } },
      };
      const changedDuringLookup = makePublishHarness({
        outputPath,
        changePullRequestDuringCommentLookup: changedPullRequest,
      });
      await runPublishMode(changedDuringLookup.context);
      const lookupFallbackBody = changedDuringLookup.state.comments.find((comment) => comment.id === 7)?.body ?? '';
      assert.equal(changedDuringLookup.state.commentWrites.length, 1);
      assert.equal(changedDuringLookup.state.commentWrites.some((body) => body.includes('判定：APPROVE')), false,
        'a PR change during sticky-comment lookup must prevent publishing the old approval');
      assert.ok(lookupFallbackBody.includes('判定：INCONCLUSIVE'));
      assert.ok(lookupFallbackBody.includes('changed after review'));
      assert.deepEqual(changedDuringLookup.state.labels, [['status:needs-decision']],
        'a stale review during comment lookup may reconcile only the fallback status label');
      assert.deepEqual(changedDuringLookup.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
        ['area:docs', 'area:policy']);
      assert.equal(changedDuringLookup.state.operations.includes('remove-label'), false);

      const changedAfterCommentUpdate = makePublishHarness({
        outputPath,
        changePullRequestAfterCommentUpdate: changedPullRequest,
      });
      await runPublishMode(changedAfterCommentUpdate.context);
      const postUpdateFallbackBody = changedAfterCommentUpdate.state.comments.find((comment) => comment.id === 7)?.body ?? '';
      assert.equal(changedAfterCommentUpdate.state.updated, 2,
        'a PR change before normal label reconciliation overwrites the just-published approval');
      assert.ok(changedAfterCommentUpdate.state.commentWrites[0].includes('判定：APPROVE'));
      assert.ok(changedAfterCommentUpdate.state.commentWrites[1].includes('判定：INCONCLUSIVE'));
      assert.ok(postUpdateFallbackBody.includes('判定：INCONCLUSIVE'));
      assert.deepEqual(changedAfterCommentUpdate.state.labels, [['status:needs-decision']],
        'a stale review before label reconciliation may reconcile only fallback status');
      assert.deepEqual(changedAfterCommentUpdate.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
        ['area:docs', 'area:policy']);
      assert.equal(changedAfterCommentUpdate.state.operations.includes('remove-label'), false);

      const existingDecisionLabel = makePublishHarness({
        outputPath: path.join(tempDirectory, 'missing-existing-decision-review-output.json'),
        existingLabels: ['area:docs', 'area:policy', 'security', 'performance', 'status:needs-decision'],
      });
      await runPublishMode(existingDecisionLabel.context);
      const existingDecisionBody = existingDecisionLabel.state.comments.find((comment) => comment.id === 7)?.body ?? '';
      assert.ok(existingDecisionBody.includes('判定：INCONCLUSIVE'));
      assert.ok(existingDecisionLabel.state.existingLabels.includes('status:needs-decision'));
      assert.equal(existingDecisionLabel.state.operations.includes('list-labels'), true,
        'fallback reconciliation checks whether the decision label is already present');
      assert.equal(existingDecisionLabel.state.operations.includes('add-labels'), false,
        'an existing decision label is recognized as skipped rather than added again');

      for (const failure of ['update', 'create'] as const) {
        const publishFailure = makePublishHarness({
          outputPath,
          commentFailure: failure,
          omitBotComment: failure === 'create',
        });
        await assert.rejects(
          runPublishMode(publishFailure.context),
          (error: unknown) => error instanceof Error &&
            error.message === 'PocketGuard: failed to publish review comment.',
          `${failure} comment API failure must fail with generic text`,
        );
        assert.equal(publishFailure.state.operations.includes('list-labels'), false,
          `${failure} comment API failure must prevent label reconciliation`);
        assert.equal(publishFailure.state.operations.includes('add-labels'), false);
        assert.equal(publishFailure.state.operations.includes('remove-label'), false);
        assert.equal(publishFailure.state.comments.find((comment) => comment.id === 8)?.body, humanMarkerBody,
          `${failure} comment API failure must not mutate the human-authored marker`);
      }

      // A list failure during the fail-closed quota re-check publishes
      // nothing (no throw, no sticky write, no labels) and leaves the prior
      // sticky untouched.
      {
        const listFailure = makePublishHarness({ outputPath, commentFailure: 'list' });
        const stickyBefore = listFailure.state.comments.find((comment) => comment.id === 7)?.body;
        await runPublishMode(listFailure.context);
        assert.equal(listFailure.state.operations.includes('list-labels'), false,
          'list comment API failure must prevent label reconciliation');
        assert.equal(listFailure.state.operations.includes('add-labels'), false);
        assert.equal(listFailure.state.operations.includes('remove-label'), false);
        assert.equal(listFailure.state.comments.find((comment) => comment.id === 7)?.body, stickyBefore,
          'list comment API failure must leave the sticky untouched (quota-unknown fail-closed)');
        assert.equal(listFailure.state.comments.find((comment) => comment.id === 8)?.body, humanMarkerBody,
          'list comment API failure must not mutate the human-authored marker');
      }

      const failedDecisionLabel = makePublishHarness({
        outputPath: path.join(tempDirectory, 'missing-fallback-review-output.json'),
        addLabelsFails: true,
      });
      await assert.rejects(
        runPublishMode(failedDecisionLabel.context),
        (error: unknown) => error instanceof Error &&
          error.message === 'PocketGuard: failed to ensure maintainer decision label.',
        'a failed fallback label add must fail visibly with generic text',
      );
      const failedDecisionBody = failedDecisionLabel.state.comments.find((comment) => comment.id === 7)?.body ?? '';
      assert.ok(failedDecisionBody.includes('判定：INCONCLUSIVE'),
        'publish the inconclusive sticky comment before attempting the fallback label');
      assert.equal(failedDecisionBody.includes('判定：APPROVE'), false);
      assert.equal(failedDecisionLabel.state.existingLabels.includes('status:needs-decision'), false);
      assert.deepEqual(failedDecisionLabel.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
        ['area:docs', 'area:policy'], 'fallback label failure must not remove area labels');
      assert.ok(failedDecisionLabel.state.existingLabels.includes('security'));
      assert.ok(failedDecisionLabel.state.existingLabels.includes('performance'));
      assert.equal(failedDecisionLabel.state.operations.includes('remove-label'), false,
        'fallback status reconciliation must not remove any area labels');

      const failedReviewLabel = makePublishHarness({ outputPath, addLabelsFails: true });
      await assert.rejects(
        runPublishMode(failedReviewLabel.context),
        (error: unknown) => error instanceof Error &&
          error.message === 'PocketGuard: failed to reconcile review labels.' &&
          !error.message.includes('synthetic'),
        'a valid review must fail generically when a desired label cannot be added',
      );
      assert.equal(failedReviewLabel.state.updated, 1, 'publish the valid review comment before label reconciliation');
      assert.ok(failedReviewLabel.state.comments.find((comment) => comment.id === 7)?.body.includes('判定：APPROVE'));
      assert.equal(failedReviewLabel.state.existingLabels.includes('area:delivery'), false,
        'the failed label must not be represented as successfully applied');

      const failedReviewRemoval = makePublishHarness({ outputPath, removeLabelsFails: true });
      await assert.rejects(
        runPublishMode(failedReviewRemoval.context),
        (error: unknown) => error instanceof Error &&
          error.message === 'PocketGuard: failed to reconcile review labels.' &&
          !error.message.includes('sensitive removal failure detail'),
        'a normal reconciliation removal failure must fail generically',
      );
      assert.ok(failedReviewRemoval.state.operations.includes('remove-label'),
        'normal reconciliation attempts stale managed-label removal');

      const failedReviewListing = makePublishHarness({ outputPath, listLabelsFails: true });
      await assert.rejects(
        runPublishMode(failedReviewListing.context),
        (error: unknown) => error instanceof Error &&
          error.message === 'PocketGuard: failed to reconcile review labels.' &&
          !error.message.includes('synthetic'),
        'a valid review must fail generically when existing labels cannot be listed');
      assert.equal(failedReviewListing.state.updated, 1, 'publish the review comment before label reconciliation');
      assert.ok(failedReviewListing.state.existingLabels.includes('area:delivery'),
        'successful append after a failed listing does not prove complete reconciliation');
      assert.deepEqual(failedReviewListing.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
        ['area:delivery', 'area:docs', 'area:policy'], 'failed listing must not claim stale area labels were removed');
      assert.equal(failedReviewListing.state.operations.includes('remove-label'), false,
        'failed listing must never try to remove labels it could not inspect');

      const emptyLabelsOutputPath = path.join(tempDirectory, 'empty-labels-review-output.json');
      fs.writeFileSync(emptyLabelsOutputPath, JSON.stringify({ ...reviewed, areaLabels: [] }));
      const failedEmptyLabelsListing = makePublishHarness({
        outputPath: emptyLabelsOutputPath,
        listLabelsFails: true,
      });
      await assert.rejects(
        runPublishMode(failedEmptyLabelsListing.context),
        (error: unknown) => error instanceof Error &&
          error.message === 'PocketGuard: failed to reconcile review labels.',
        'a failed listing must reject even when the review has no desired labels');
      assert.deepEqual(failedEmptyLabelsListing.state.labels, [],
        'an empty desired-label set does not trigger an unnecessary add');

      const fallbackListFailure = makePublishHarness({
        outputPath: path.join(tempDirectory, 'missing-list-fallback-review-output.json'),
        listLabelsFails: true,
      });
      await runPublishMode(fallbackListFailure.context);
      const fallbackListFailureBody = fallbackListFailure.state.comments.find((comment) => comment.id === 7)?.body ?? '';
      assert.ok(fallbackListFailureBody.includes('判定：INCONCLUSIVE'));
      assert.ok(fallbackListFailure.state.existingLabels.includes('status:needs-decision'),
        'fallback succeeds if the decision-label append succeeds despite a failed listing');
      assert.deepEqual(fallbackListFailure.state.labels, [['status:needs-decision']],
        'fallback adds only the maintainer decision label');
      assert.deepEqual(fallbackListFailure.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
        ['area:docs', 'area:policy'], 'fallback listing failure preserves existing area labels');
      assert.equal(fallbackListFailure.state.operations.includes('remove-label'), false);

      const failedFallbackListAndAdd = makePublishHarness({
        outputPath: path.join(tempDirectory, 'missing-list-and-add-fallback-review-output.json'),
        listLabelsFails: true,
        addLabelsFails: true,
      });
      await assert.rejects(
        runPublishMode(failedFallbackListAndAdd.context),
        (error: unknown) => error instanceof Error &&
          error.message === 'PocketGuard: failed to ensure maintainer decision label.' &&
          !error.message.includes('synthetic'),
        'a failed listing and decision-label append must fail generically');
      assert.deepEqual(failedFallbackListAndAdd.state.existingLabels.filter((label) => label.startsWith('area:')).sort(),
        ['area:docs', 'area:policy'], 'a failed fallback must preserve existing area labels');
      assert.equal(failedFallbackListAndAdd.state.operations.includes('remove-label'), false);

      const issueLabelFailure = makePublishHarness({
        outputPath,
        addLabelsFails: true,
        event: {
          action: 'opened',
          repository: { full_name: 'sample/repository' },
          issue: { number: 41, title: 'security: issue label regression' },
        },
      });
      issueLabelFailure.context.env = {
        ...issueLabelFailure.context.env,
        GITHUB_EVENT_NAME: 'issues',
      } as NodeJS.ProcessEnv;
      await assert.rejects(
        runPublishMode(issueLabelFailure.context),
        (error: unknown) => error instanceof Error &&
          error.message === 'PocketGuard: failed to apply issue labels.' &&
          !error.message.includes('synthetic'),
        'issues-event label API failures must reject with generic text',
      );
      assert.ok(issueLabelFailure.state.operations.includes('add-labels'));

      const triageHarness = makePublishHarness({
        outputPath,
        event: {
          action: 'created',
          repository: { full_name: 'sample/repository' },
          issue: { number: 41, pull_request: { url: 'unused' } },
          comment: { body: '/triage' },
        },
      });
      triageHarness.context.env = {
        ...triageHarness.context.env,
        GITHUB_EVENT_NAME: 'issue_comment',
      } as NodeJS.ProcessEnv;
      await runPublishMode(triageHarness.context);
      assert.equal(triageHarness.state.updated, 0, 'non-review comment commands remain a legitimate skip');
      assert.equal(triageHarness.state.created, 0);
      assert.equal(triageHarness.state.operations.includes('update-comment'), false);
      assert.equal(triageHarness.state.operations.includes('create-comment'), false);
      assert.equal(triageHarness.state.operations.includes('add-labels'), false);
      assert.equal(triageHarness.state.operations.includes('remove-label'), false);

      const workflow = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/pocketguard.yml'), 'utf8');
      const jobsStart = workflow.indexOf('jobs:');
      const prepareTag = workflow.slice(workflow.indexOf('  prepare-tag:'), workflow.indexOf('  review-send:'));
      const reviewJob = workflow.slice(workflow.indexOf('  review-send:'), workflow.indexOf('  publish:'));
      const publishJob = workflow.slice(workflow.indexOf('  publish:'));
      const publishCondition = publishJob.slice(0, publishJob.indexOf('    steps:'));
      const workflowStep = (job: string, name: string): string => {
        const marker = `      - name: ${name}`;
        const start = job.indexOf(marker);
        assert.notEqual(start, -1, `workflow step exists: ${name}`);
        const contentsStart = start + marker.length;
        const remainder = job.slice(contentsStart);
        const nextStep = remainder.search(/^      - name:/m);
        return remainder.slice(0, nextStep < 0 ? undefined : nextStep);
      };
      const nestedMapping = (step: string, key: 'env' | 'with'): string => {
        const lines = step.split('\n');
        const start = lines.findIndex((line) => new RegExp(`^\\s{8}${key}:\\s*$`).test(line));
        if (start < 0) return '';
        const values: string[] = [];
        for (let index = start + 1; index < lines.length; index += 1) {
          const line = lines[index];
          if (line.trim() && !/^\s{10}\S/.test(line)) break;
          values.push(line);
        }
        return values.join('\n');
      };
      const withValue = (step: string, key: string): string | undefined => {
        const withBlock = nestedMapping(step, 'with');
        const match = withBlock.match(new RegExp(`^\\s{10}${key}:\\s*(.*?)\\s*$`, 'm'));
        return match?.[1].replace(/^['"]|['"]$/g, '');
      };
      const uploadStep = workflowStep(reviewJob, 'Upload review result');
      const downloadStep = workflowStep(publishJob, 'Download review result');
      const publishStep = workflowStep(publishJob, 'Publish sticky result and labels');
      assert.match(downloadStep, /continue-on-error:\s*true/,
        'artifact download failure must not block fallback publishing');
      const conditionLine = publishCondition.match(/^\s{4}if:\s*(.*)$/m);
      assert.ok(conditionLine, 'publish job declares an if condition');
      const conditionLines = publishCondition.split('\n');
      const conditionIndex = conditionLines.findIndex((line) => /^\s{4}if:/.test(line));
      const conditionParts = [conditionLine?.[1] ?? ''];
      for (let index = conditionIndex + 1; index < conditionLines.length; index += 1) {
        const line = conditionLines[index];
        if (!/^\s{6}\S/.test(line)) break;
        conditionParts.push(line.trim());
      }
      const normalizedCondition = conditionParts.join(' ').replace(/\s+/g, ' ').trim();
      assert.match(normalizedCondition, /\balways\(\)/,
        'publish runs even when review-send fails or is skipped');
      assert.match(normalizedCondition, /needs\.prepare-tag\.result\s*==\s*'success'/,
        'publish requires successful deterministic tagging');
      assert.doesNotMatch(normalizedCondition, /needs\.review-send\.result/,
        'review-send failure or cancellation must not skip publish');
      assert.match(normalizedCondition, /needs\.prepare-tag\.outputs\.authorized\s*==\s*'true'/,
        'unauthorized issue_comment skips publish without touching prior approval');
      const tagStep = workflowStep(prepareTag, 'Emit deterministic labels and routing context');
      assert.match(nestedMapping(tagStep, 'env'), /^\s{10}GITHUB_TOKEN:/m,
        'prepare-tag exposes only the read-only token for the authorization gate');
      assert.match(prepareTag, /pull-requests:\s*read/,
        'prepare-tag may list pull requests for the read-only gate');
      assert.match(prepareTag, /authorized:\s*\$\{\{\s*steps\.tag\.outputs\.authorized\s*\}\}/,
        'prepare-tag forwards the authorization verdict');
      const reviewCondition = reviewJob.slice(0, reviewJob.indexOf('    steps:'));
      const squashedReviewCondition = reviewCondition.replace(/\s+/g, ' ');
      assert.match(squashedReviewCondition, /needs\.prepare-tag\.outputs\.authorized\s*==\s*'true'/,
        'unauthorized issue_comment never schedules the secrets-bearing review job');
      assert.doesNotMatch(squashedReviewCondition, /should_tag\s*==\s*'true'/,
        'should_tag is never a review-job scheduling reason');
      assert.match(squashedReviewCondition, /target\s*==\s*'issue'.*route_kind/,
        'review-send schedules issue-auto execution by explicit route');
      const trustedStep = workflowStep(reviewJob, 'Run trusted single-turn review');
      const squashedTrusted = trustedStep.replace(/\s+/g, ' ');
      assert.match(squashedTrusted, /steps\.gate\.outputs\.diff_safe\s*==\s*'true'\s*&&\s*steps\.gate\.outputs\.authorized\s*==\s*'true'/,
        'OpenAI credentials are injected only when the readable diff plus authorization both pass');
      assert.match(squashedTrusted, /steps\.gate\.outputs\.target\s*==\s*'issue'.*should_review\s*==\s*'true'.*route_kind/,
        'trusted step admits the intentional issue-auto route to secrets');
      const genericStep = workflowStep(reviewJob, 'Write generic result for untrusted pull request');
      assert.match(genericStep.replace(/\s+/g, ' '), /steps\.gate\.outputs\.target\s*==\s*'issue'/,
        'generic complement excludes the issue-auto route from the no-secrets path');
      const uploadedArtifactName = withValue(uploadStep, 'name');
      const downloadedArtifactName = withValue(downloadStep, 'name');
      assert.ok(uploadedArtifactName, 'review job declares an uploaded artifact name');
      assert.ok(downloadedArtifactName, 'publish job declares a downloaded artifact name');
      assert.equal(uploadedArtifactName, downloadedArtifactName, 'upload and download artifact names match');
      assert.equal(withValue(uploadStep, 'path'), 'github_bot/review-output.json');
      assert.equal(withValue(downloadStep, 'path'), 'github_bot');
      const publishEnv = nestedMapping(publishStep, 'env');
      assert.match(publishEnv, /^\s{10}POCKETGUARD_OUTPUT:\s*['"]?review-output\.json['"]?\s*$/m,
        'download destination contains the file expected by the publisher');
      assert.match(reviewJob, /OPENAI_API_KEY:/);
      assert.match(reviewJob, /OPENAI_BASE_URL:/);
      const openaiSecretKeys = /OPENAI_API_KEY|OPENAI_BASE_URL/;
      assert.doesNotMatch(workflow.slice(0, jobsStart), openaiSecretKeys,
        'OpenAI secrets stay out of workflow-level configuration');
      assert.doesNotMatch(prepareTag, openaiSecretKeys, 'OpenAI secrets stay out of prepare-tag');
      assert.doesNotMatch(publishJob, openaiSecretKeys, 'OpenAI secrets stay out of publish');
    } finally {
      fs.rmSync(tempDirectory, { recursive: true, force: true });
    }
  } finally {
    globalThis.fetch = previousFetch;
  }

  const runnerSource = fs.readFileSync(path.resolve(__dirname, '../src/github_runner.ts'), 'utf8');
  assert.doesNotMatch(runnerSource, /send_openai/i, 'tag and publish entry must not import the OpenAI client');
  console.log('[PocketGuard comment/runner tests] All tests passed.');
}

type RunnerVerdictLike = 'APPROVE' | 'NEEDS_CHANGES' | 'INCONCLUSIVE';

export async function runCommentAuthTests(): Promise<void> {
  const previousFetch = globalThis.fetch;
  const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-comment-auth-'));
  let outputSeq = 0;
  try {
    let openaiRequestCount = 0;
    globalThis.fetch = (async () => {
      openaiRequestCount += 1;
      return new Response(JSON.stringify({
        output: [{ content: [{ type: 'output_text', text: JSON.stringify({ verdict: 'APPROVE', summary: 'ok', findings: [] }) }] }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;

    const authGit = (args: string[]): string => {
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

    const makeAuthHarness = (options: {
      permission?: string;
      permissionThrows?: string;
      omitRepos?: boolean;
      body?: string;
      commentUser?: { login?: string; type?: string } | null;
      sender?: { login?: string; type?: string };
      action?: string;
      omitAction?: boolean;
      authorAssociation?: string;
      safeReviewEnv?: string;
    }) => {
      const state = {
        permissionCalls: [] as Array<{ owner: string; repo: string; username: string }>,
        pullsGetCalls: 0,
      };
      const client = {
        rest: {
          pulls: {
            get: async () => {
              state.pullsGetCalls += 1;
              return { data: {
                number: 41,
                base: { sha: BASE_SHA },
                head: { sha: HEAD_SHA, repo: { full_name: 'sample/repository' } },
              } };
            },
          },
          users: {
            getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }),
          },
          issues: {
            listComments: async () => ({ data: [] }),
          },
          ...(options.omitRepos ? {} : {
            repos: {
              getCollaboratorPermissionLevel: async (params: { owner: string; repo: string; username: string }) => {
                state.permissionCalls.push(params);
                if (options.permissionThrows) throw new Error(options.permissionThrows);
                return { data: { permission: options.permission } };
              },
            },
          }),
        },
      };
      const commentUser = options.commentUser === undefined
        ? { login: 'maintainer', type: 'User' }
        : options.commentUser;
      const event: Record<string, unknown> = {
        ...(options.omitAction ? {} : { action: options.action ?? 'created' }),
        repository: { full_name: 'sample/repository' },
        issue: { number: 41, pull_request: { url: 'unused' }, title: 'topic' },
        comment: {
          body: options.body ?? '/review',
          ...(commentUser ? { user: commentUser } : {}),
          ...(options.authorAssociation ? { author_association: options.authorAssociation } : {}),
        },
        ...(options.sender ? { sender: options.sender } : {}),
      };
      const baseEnv = {
        GITHUB_EVENT_NAME: 'issue_comment',
        GITHUB_REPOSITORY: 'sample/repository',
        GITHUB_TOKEN: 'fake-read-token',
        POCKETGUARD_SAFE_REVIEW: options.safeReviewEnv ?? 'true',
        OPENAI_BASE_URL: TEST_BASE_URL,
        OPENAI_API_KEY: 'fake-openai-key',
        POCKETGUARD_OPENAI_ORIGIN: TEST_ORIGIN,
        POCKETGUARD_MODEL_CHIEF: 'fake-chief-model',
        POCKETGUARD_MODEL_ANDROID_SEC: 'fake-sec-model',
        POCKETGUARD_MODEL_ANDROID_CODE: 'fake-code-model',
        POCKETGUARD_MODEL_PROFILES: '{"fake-chief-model":"chat","fake-sec-model":"chat","fake-code-model":"chat"}',
      } as NodeJS.ProcessEnv;
      const tagContext = {
        event,
        env: baseEnv,
        githubClient: client,
        writeStdout: () => undefined,
        runGit: authGit,
      } as unknown as RunnerContext;
      const reviewContext: RunnerContext = {
        event,
        env: {
          ...baseEnv,
          POCKETGUARD_OUTPUT: path.join(tempDirectory, `auth-review-${outputSeq++}.json`),
        } as NodeJS.ProcessEnv,
        githubClient: client as unknown as NonNullable<RunnerContext['githubClient']>,
        writeStdout: () => undefined,
        runGit: authGit,
      };
      return { state, tagContext, reviewContext };
    };

    const runAuthCase = async (
      name: string,
      options: Parameters<typeof makeAuthHarness>[0],
      expected: { safeReview: boolean; authorized: boolean; verdict: RunnerVerdictLike; openaiCalls: number | 'positive' },
    ) => {
      const harness = makeAuthHarness(options);
      let tagStdout = '';
      const tagged = await runTagMode({ ...harness.tagContext, writeStdout: (value) => { tagStdout += value; } });
      assert.equal(tagged.safeReview, expected.safeReview, `${name}: safeReview`);
      assert.equal(tagged.authorized, expected.authorized, `${name}: authorized`);
      const before = openaiRequestCount;
      let reviewStdout = '';
      const reviewed = await runReviewMode({ ...harness.reviewContext, writeStdout: (value) => { reviewStdout += value; } });
      assert.equal(reviewed.verdict, expected.verdict, `${name}: review verdict`);
      const made = openaiRequestCount - before;
      if (expected.openaiCalls === 'positive') {
        assert.ok(made > 0, `${name}: authorized review must reach the OpenAI`);
      } else {
        assert.equal(made, expected.openaiCalls, `${name}: denied review must not call the OpenAI`);
      }
      return { harness, tagged, reviewed, tagStdout, reviewStdout };
    };

    // a. maintainer with write permission is authorized.
    await runAuthCase('maintainer-write', { permission: 'write' },
      { safeReview: true, authorized: true, verdict: 'APPROVE', openaiCalls: 'positive' });
    // b. maintainer with admin permission is authorized.
    await runAuthCase('maintainer-admin', { permission: 'admin' },
      { safeReview: true, authorized: true, verdict: 'APPROVE', openaiCalls: 'positive' });

    // c. outsider with read permission is denied; the permission call carries the comment username.
    {
      const { harness } = await runAuthCase('outsider-read',
        { permission: 'read', commentUser: { login: 'outsider', type: 'User' } },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 2, 'outsider-read: tag and review each verify');
      assert.equal(harness.state.permissionCalls[0].username, 'outsider', 'outsider-read: username forwarded');
      assert.equal(harness.state.pullsGetCalls, 2, 'outsider-read: tag and review each fetch the PR for the author check');
    }

    // d. collaborator without access, and a 404-style API failure, both deny.
    {
      const { harness } = await runAuthCase('outsider-none', { permission: 'none' },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.pullsGetCalls, 2, 'outsider-none: tag and review each fetch the PR for the author check');
    }
    {
      const { harness } = await runAuthCase('permission-404',
        { permissionThrows: 'synthetic permission lookup failure' },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.pullsGetCalls, 2, 'permission-404: the author check still runs after a permission failure');
    }

    // e. FIRST_TIME_CONTRIBUTOR without write access is denied.
    await runAuthCase('first-time-contributor',
      { permission: 'none', authorAssociation: 'FIRST_TIME_CONTRIBUTOR' },
      { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });

    // f. bot comments deny before any permission API call.
    {
      const { harness } = await runAuthCase('bot-comment',
        {
          permission: 'write',
          commentUser: { login: 'github-actions[bot]', type: 'Bot' },
        },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 0, 'bot-comment: no permission API call');
      assert.equal(harness.state.pullsGetCalls, 0, 'bot-comment: no pulls.get call');
    }

    // g. bot senders deny even with a human comment author.
    {
      const { harness } = await runAuthCase('bot-sender',
        { permission: 'write', sender: { login: 'sender-bot', type: 'Bot' } },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 0, 'bot-sender: no permission API call');
      assert.equal(harness.state.pullsGetCalls, 0, 'bot-sender: no pulls.get call');
    }

    // h. a spoofed COLLABORATOR association with only read access is denied.
    {
      const { harness } = await runAuthCase('spoofed-association',
        { permission: 'read', authorAssociation: 'COLLABORATOR' },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.pullsGetCalls, 2, 'spoofed-association: tag and review each fetch the PR for the author check');
    }

    // i. missing usernames deny without touching the permission API.
    {
      const { harness } = await runAuthCase('missing-username',
        { permission: 'write', commentUser: null },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 0, 'missing-username: no permission API call');
      assert.equal(harness.state.pullsGetCalls, 0, 'missing-username: no pulls.get call');
    }

    // j. permission API failures deny with generic output that leaks no detail.
    {
      const probe = 'synthetic-probe-username-7f3a';
      const { harness, tagStdout, reviewStdout } = await runAuthCase('permission-500',
        { permissionThrows: `synthetic failure for ${probe}`, commentUser: { login: probe, type: 'User' } },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 2, 'permission-500: tag and review each verify');
      assert.ok(!tagStdout.includes('synthetic'), 'permission-500: tag output stays generic');
      assert.ok(!tagStdout.includes(probe), 'permission-500: tag output hides the username');
      assert.ok(!reviewStdout.includes('synthetic'), 'permission-500: review output stays generic');
      assert.ok(!reviewStdout.includes(probe), 'permission-500: review output hides the username');
    }

    // k. edited actions pass the gate like created (S3): an authorized
    // maintainer edit proceeds to the permission check and review.
    {
      const { harness } = await runAuthCase('edited-action',
        { permission: 'write', action: 'edited' },
        { safeReview: true, authorized: true, verdict: 'APPROVE', openaiCalls: 'positive' });
      assert.equal(harness.state.permissionCalls.length, 2, 'edited-action: tag and review each verify');
      assert.ok(harness.state.pullsGetCalls > 0, 'edited-action: authorized edit fetches the pull request');
    }

    // k2. deleted and missing actions deny on the issue_comment path.
    {
      const { harness } = await runAuthCase('deleted-action',
        { permission: 'write', action: 'deleted' },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 0, 'deleted-action: no permission API call');
      assert.equal(harness.state.pullsGetCalls, 0, 'deleted-action: no pulls.get call');
    }
    {
      const { harness } = await runAuthCase('missing-action',
        { permission: 'write', omitAction: true },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 0, 'missing-action: no permission API call');
      assert.equal(harness.state.pullsGetCalls, 0, 'missing-action: no pulls.get call');
    }

    // k3. Bot type matching is case-insensitive (bot/BOT deny like Bot).
    for (const botType of ['bot', 'BOT']) {
      const { harness } = await runAuthCase(`bot-type-${botType}`,
        { permission: 'write', commentUser: { login: 'some-bot', type: botType } },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 0, `bot-type-${botType}: no permission API call`);
      assert.equal(harness.state.pullsGetCalls, 0, `bot-type-${botType}: no pulls.get call`);
    }
    {
      const { harness } = await runAuthCase('bot-sender-lowercase',
        { permission: 'write', sender: { login: 'sender-bot', type: 'bot' } },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.permissionCalls.length, 0, 'bot-sender-lowercase: no permission API call');
      assert.equal(harness.state.pullsGetCalls, 0, 'bot-sender-lowercase: no pulls.get call');
    }

    // k4. Authorization deny wins over any bypass: an outsider stays at zero
    // OpenAI calls even with POCKETGUARD_SAFE_REVIEW='false'.
    {
      const { harness } = await runAuthCase('outsider-deny-safe-review-false',
        { permission: 'read', commentUser: { login: 'outsider', type: 'User' }, safeReviewEnv: 'false' },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.pullsGetCalls, 2, 'outsider-deny-safe-review-false: author check runs, bypass stays denied');
    }

    // A missing repos API on the client denies fail-closed.
    {
      const { harness } = await runAuthCase('missing-repos-api', { omitRepos: true },
        { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
      assert.equal(harness.state.pullsGetCalls, 2, 'missing-repos-api: the author check still runs without a repos API');
    }

    // An authorized maintainer sending an unsupported command is authorized
    // but not safe for review; an outsider sending the same is neither.
    await runAuthCase('maintainer-unsupported', { permission: 'write', body: 'hello' },
      { safeReview: false, authorized: true, verdict: 'INCONCLUSIVE', openaiCalls: 0 });
    await runAuthCase('outsider-unsupported',
      { permission: 'read', body: 'hello', commentUser: { login: 'outsider', type: 'User' } },
      { safeReview: false, authorized: false, verdict: 'INCONCLUSIVE', openaiCalls: 0 });

    // Tag mode forwards the authorization verdict to GITHUB_OUTPUT for the
    // workflow read-only gate (fail-closed string comparison).
    {
      const outputPath = path.join(tempDirectory, 'auth-outputs.txt');
      const allowed = makeAuthHarness({ permission: 'write' });
      await runTagMode({
        ...allowed.tagContext,
        env: { ...allowed.tagContext.env, GITHUB_OUTPUT: outputPath } as NodeJS.ProcessEnv,
      });
      const allowedOutputs = fs.readFileSync(outputPath, 'utf8');
      assert.match(allowedOutputs, /^authorized=true$/m, 'authorized maintainer emits authorized=true');
      fs.rmSync(outputPath, { force: true });
      const denied = makeAuthHarness({ permission: 'read', commentUser: { login: 'outsider', type: 'User' } });
      await runTagMode({
        ...denied.tagContext,
        env: { ...denied.tagContext.env, GITHUB_OUTPUT: outputPath } as NodeJS.ProcessEnv,
      });
      const deniedOutputs = fs.readFileSync(outputPath, 'utf8');
      assert.match(deniedOutputs, /^authorized=false$/m, 'outsider emits authorized=false');
    }
  } finally {
    fs.rmSync(tempDirectory, { recursive: true, force: true });
    globalThis.fetch = previousFetch;
  }
  console.log('[PocketGuard comment-auth tests] All tests passed.');
}

export async function runRouteEventTests(): Promise<void> {
  const prEnv = (overrides: Record<string, string> = {}): NodeJS.ProcessEnv => ({
    GITHUB_EVENT_NAME: 'pull_request_target',
    GITHUB_REPOSITORY: 'Sample/Repository',
    ...overrides,
  } as NodeJS.ProcessEnv);

  // pull_request_target opened/reopened route regardless of fork or permission.
  for (const action of ['opened', 'reopened']) {
    for (const head of ['sample/repository', 'untrusted/fork']) {
      const routed = routeEvent({
        action,
        repository: { full_name: 'sample/repository' },
        pull_request: {
          number: 41, title: 't',
          base: { sha: BASE_SHA }, head: { sha: HEAD_SHA, repo: { full_name: head } },
        },
        sender: { login: 'outsider', type: 'User' },
      }, prEnv());
      assert.equal(routed.kind, 'first-review', `${action}/${head}`);
      assert.ok(routed.reason.length > 0);
      const flags = routeReviewFlags(routed.kind, 'pull_request_target', {
        pull_request: { number: 41 },
      });
      assert.deepEqual(flags, { shouldReview: true, shouldTag: true });
    }
  }

  // pull_request_target synchronize: owner-only.
  const syncEvent = (sender?: { login?: string; type?: string }) => ({
    action: 'synchronize',
    repository: { full_name: 'sample/repository' },
    pull_request: {
      number: 41, title: 't',
      base: { sha: BASE_SHA }, head: { sha: HEAD_SHA, repo: { full_name: 'sample/repository' } },
    },
    ...(sender ? { sender } : {}),
  });
  assert.equal(routeEvent(syncEvent({ login: 'sample', type: 'User' }),
    prEnv({ GITHUB_ACTOR: 'SAMPLE', POCKETGUARD_REPO_OWNER: 'sample' })).kind, 'owner-commit');
  assert.equal(routeEvent(syncEvent({ login: 'contributor', type: 'User' }),
    prEnv({ GITHUB_ACTOR: 'contributor', POCKETGUARD_REPO_OWNER: 'sample' })).kind, 'ignore');
  // Missing actor fails closed to ignore.
  assert.equal(routeEvent({ action: 'synchronize', repository: { full_name: 'sample/repository' } },
    prEnv({ POCKETGUARD_REPO_OWNER: 'sample' })).kind, 'ignore');
  // Missing owner fails closed to ignore (neither explicit owner nor repository).
  assert.equal(routeEvent(
    {
      action: 'synchronize',
      sender: { login: 'sample', type: 'User' },
      pull_request: {
        number: 41, title: 't',
        base: { sha: BASE_SHA }, head: { sha: HEAD_SHA, repo: { full_name: 'sample/repository' } },
      },
    },
    { GITHUB_EVENT_NAME: 'pull_request_target' } as NodeJS.ProcessEnv).kind, 'ignore');
  // Owner comparison is case-insensitive and never uses commit authors.
  assert.equal(isRepositoryOwner('Sample', 'sample'), true);
  assert.equal(isRepositoryOwner('other', 'sample'), false);
  assert.equal(isRepositoryOwner(undefined, 'sample'), false);
  assert.equal(isRepositoryOwner('sample', undefined), false);

  // issues opened/edited/reopened (non-bot).
  const issuesEnv = (): NodeJS.ProcessEnv =>
    ({ GITHUB_EVENT_NAME: 'issues', GITHUB_REPOSITORY: 'sample/repository' } as NodeJS.ProcessEnv);
  assert.equal(routeEvent({ action: 'opened', issue: { number: 7 } }, issuesEnv()).kind, 'first-review');
  const issueOpenedFlags = routeReviewFlags('first-review', 'issues', { issue: { number: 7 } });
  assert.deepEqual(issueOpenedFlags, { shouldReview: true, shouldTag: true });
  assert.equal(routeEvent({ action: 'edited', issue: { number: 7 } }, issuesEnv()).kind, 'issue-update');
  assert.equal(routeEvent({ action: 'reopened', issue: { number: 7 } }, issuesEnv()).kind, 'issue-update');
  assert.deepEqual(routeReviewFlags('issue-update', 'issues', { issue: { number: 7 } }), { shouldReview: true, shouldTag: true });
  assert.equal(routeEvent(
    { action: 'opened', issue: { number: 7 }, sender: { login: 'bot[bot]', type: 'User' } },
    issuesEnv()).kind, 'ignore');
  assert.equal(routeEvent(
    { action: 'opened', issue: { number: 7 }, sender: { login: 'human', type: 'Bot' } },
    issuesEnv()).kind, 'ignore');

  // issue_comment matrix.
  const commentEnv = (): NodeJS.ProcessEnv =>
    ({ GITHUB_EVENT_NAME: 'issue_comment', GITHUB_REPOSITORY: 'sample/repository' } as NodeJS.ProcessEnv);
  const prComment = (body: string, user = { login: 'maintainer', type: 'User' }) => ({
    action: 'created',
    repository: { full_name: 'sample/repository' },
    issue: { number: 41, pull_request: {} },
    comment: { body, user },
  });
  assert.equal(routeEvent(prComment('/review'), commentEnv()).kind, 'manual-pr-review');
  assert.equal(routeEvent({ ...prComment('/review'), action: 'edited' }, commentEnv()).kind, 'manual-pr-review');
  assert.equal(routeEvent(prComment('@pocketguard review'), commentEnv()).kind, 'manual-pr-review');
  assert.equal(routeEvent(prComment('@pocketguard /review'), commentEnv()).kind, 'manual-pr-review');
  assert.equal(routeEvent(prComment('/explain'), commentEnv()).kind, 'manual-pr-review');
  assert.equal(routeEvent(prComment('hello'), commentEnv()).kind, 'ignore');
  assert.equal(routeEvent(prComment('@pocketguard'), commentEnv()).kind, 'ignore');
  assert.equal(routeEvent(prComment('/triage'), commentEnv()).kind, 'ignore');
  assert.equal(routeEvent(
    prComment('/review', { login: 'pocketguard[bot]', type: 'Bot' }), commentEnv()).kind, 'ignore');
  assert.equal(routeEvent({
    action: 'created',
    repository: { full_name: 'sample/repository' },
    issue: { number: 41, pull_request: {} },
    comment: { body: '/review', user: { login: 'maintainer', type: 'User' } },
    sender: { login: 'github-actions[bot]', type: 'Bot' },
  }, commentEnv()).kind, 'ignore');

  const issueComment = (body: string) => ({
    action: 'created',
    repository: { full_name: 'sample/repository' },
    issue: { number: 7, title: 't' },
    comment: { body, user: { login: 'human', type: 'User' } },
  });
  assert.equal(routeEvent(issueComment('looks good'), commentEnv()).kind, 'issue-update');
  assert.equal(routeEvent({ ...issueComment('looks good'), action: 'edited' }, commentEnv()).kind, 'issue-update');
  assert.equal(routeEvent({
    action: 'created',
    repository: { full_name: 'sample/repository' },
    issue: { number: 7 },
    comment: { body: 'bot note', user: { login: 'bot[bot]', type: 'User' } },
  }, commentEnv()).kind, 'ignore');

  // Tag mode forwards routing flags and snapshot identity to GITHUB_OUTPUT.
  const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketguard-route-event-'));
  try {
    const outputPath = path.join(tempDirectory, 'tag-outputs.txt');
    await runTagMode({
      event: {
        action: 'opened',
        repository: { full_name: 'sample/repository' },
        pull_request: {
          number: 41, title: 't',
          base: { sha: BASE_SHA }, head: { sha: HEAD_SHA, repo: { full_name: 'sample/repository' } },
        },
      },
      env: {
        ...prEnv(),
        GITHUB_OUTPUT: outputPath,
        GITHUB_ACTOR: 'sample',
        POCKETGUARD_REPO_OWNER: 'sample',
      } as NodeJS.ProcessEnv,
      githubClient: {
        rest: {
          users: { getAuthenticated: async () => ({ data: { login: 'pocketguard[bot]' } }) },
          issues: { listComments: async () => ({ data: [] }) },
        },
      } as unknown as RunnerContext['githubClient'],
      writeStdout: () => undefined,
      runGit: () => { throw new Error('no git needed for routing outputs'); },
    });
    const outputs = fs.readFileSync(outputPath, 'utf8');
    assert.match(outputs, /^should_review=true$/m);
    assert.match(outputs, /^should_tag=true$/m);
    assert.match(outputs, /^reason=.+$/m);
    assert.match(outputs, /^route_kind=first-review$/m);
    assert.match(outputs, /^is_owner=true$/m);
  } finally {
    fs.rmSync(tempDirectory, { recursive: true, force: true });
  }

  // Workflow triggers cover edited/reopened issue paths and edited comments.
  const workflow = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/pocketguard.yml'), 'utf8');
  assert.match(workflow, /issues:\s*\n\s*types:\s*\[opened,\s*edited,\s*reopened\]/);
  assert.match(workflow, /issue_comment:\s*\n\s*types:\s*\[created,\s*edited\]/);
  assert.match(workflow, /POCKETGUARD_REPO_OWNER:\s*\$\{\{\s*github\.repository_owner\s*\}\}/);

  console.log('[PocketGuard route-event tests] All tests passed.');
}
