import assert from 'node:assert/strict';
import {
  DeterministicScanner,
  securityLabelsFor,
} from '../src/deterministic_scanner';
import {
  isReasoningNoneSupported,
  resolveDefaultReasoningEffort,
  resolveModelFallbackChain,
  resolveModelProfile,
  resolveOpenAIConfig,
  resolveRoleModel,
  sendOpenAISingleTurn,
  sendOpenAISingleTurnWithFallback,
} from '../src/send_openai';
import {
  coverageSummary,
  filterReviewDiffFiles,
  MAX_DIFF_LENGTH,
  prioritizeFiles,
  truncateDiff,
} from '../src/review_diff';
import { buildReviewDiff } from '../src/github_runner';
import { orchestrateReview, triageIssue } from '../src/orchestrator';

const FAKE_OPENAI_BASE_URL = ['https:', '', 'pocketguard-openai.test', 'v1'].join('/');
const FAKE_ALLOWED_ORIGINS = [new URL(FAKE_OPENAI_BASE_URL).origin];
const FAKE_MODEL_ID = 'fake-model-id';
const FAKE_SECRET_FIXTURES = {
  placeholderKey: '${fake-placeholder}',
  credential: ['s', 'k-test-FAKE-not-a-real-key'].join(''),
  removedCredential: ['s', 'k-test-FAKE-removed-only'].join(''),
};

function diffFor(file: string, added: string[], removed: string[] = []): string {
  const oldCount = Math.max(removed.length, 1);
  const newCount = Math.max(added.length, 1);
  return [
    `diff --git a/${file} b/${file}`,
    `--- a/${file}`,
    `+++ b/${file}`,
    `@@ -1,${oldCount} +1,${newCount} @@`,
    ...removed.map((line) => `-${line}`),
    ...added.map((line) => `+${line}`),
  ].join('\n');
}

export async function runScannerSendOpenAITests(): Promise<void> {
  // Credential rules only inspect added lines, so a secret removed by a repair is not flagged.
  const removedOnly = diffFor('app/src/main/res/values/strings.xml', [], [FAKE_SECRET_FIXTURES.removedCredential]);
  assert.deepEqual(DeterministicScanner.scan(['app/src/main/res/values/strings.xml'], removedOnly).violations, []);
  const addedSecret = diffFor('app/src/main/res/values/strings.xml', [FAKE_SECRET_FIXTURES.credential]);
  const secretResult = DeterministicScanner.scan(['app/src/main/res/values/strings.xml'], addedSecret);
  assert.equal(secretResult.hasBlockers, true);
  assert.equal(secretResult.violations[0]?.ruleId, 'SEC-PRIVATE-KEY');
  assert.deepEqual(securityLabelsFor(secretResult.violations), ['security']);
  const extractedSecret = DeterministicScanner.extractAddedContent(addedSecret);
  assert.equal(DeterministicScanner.scan(['app/src/main/res/values/strings.xml'], extractedSecret).hasBlockers, true);

  const credentialFile = 'app/src/main/res/values/strings.xml';
  const splitCredentialParts = [
    "const credential = 'sk-",
    'test-FAKE-not-',
    "a-real-key';",
  ];
  const sameHunkSplitCredential = DeterministicScanner.scan(
    [credentialFile],
    [
      `diff --git a/${credentialFile} b/${credentialFile}`,
      `--- a/${credentialFile}`,
      `+++ b/${credentialFile}`,
      '@@ -1 +1,4 @@',
      ' context before the new credential',
      ...splitCredentialParts.map((line) => `+${line}`),
    ].join('\n'),
  );
  assert.equal(sameHunkSplitCredential.hasBlockers, true);
  const splitCredentialViolation = sameHunkSplitCredential.violations.find((violation) =>
    violation.ruleId === 'SEC-PRIVATE-KEY');
  assert.equal(splitCredentialViolation?.severity, 'BLOCK');
  assert.equal(splitCredentialViolation?.file, credentialFile);
  assert.equal(splitCredentialViolation?.line, 2, 'a multiline match reports its first added line after context');

  const splitAcrossHunks = [
    `diff --git a/${credentialFile} b/${credentialFile}`,
    `--- a/${credentialFile}`,
    `+++ b/${credentialFile}`,
    '@@ -1,0 +1 @@',
    `+${splitCredentialParts[0]}`,
    '@@ -5,0 +2,2 @@',
    `+${splitCredentialParts[1]}`,
    `+${splitCredentialParts[2]}`,
  ].join('\n');
  assert.equal(DeterministicScanner.scan([credentialFile], splitAcrossHunks).hasBlockers, false);

  const splitAcrossContextGap = [
    `diff --git a/${credentialFile} b/${credentialFile}`,
    `--- a/${credentialFile}`,
    `+++ b/${credentialFile}`,
    '@@ -1 +1,4 @@',
    `+${splitCredentialParts[0]}`,
    ' unchanged context line',
    `+${splitCredentialParts[1]}`,
    `+${splitCredentialParts[2]}`,
  ].join('\n');
  assert.equal(DeterministicScanner.scan([credentialFile], splitAcrossContextGap).hasBlockers, false);

  const otherCredentialFile = 'app/src/main/res/values/other.xml';
  const splitAcrossFiles = [
    `diff --git a/${credentialFile} b/${credentialFile}`,
    `--- a/${credentialFile}`,
    `+++ b/${credentialFile}`,
    '@@ -1,0 +1 @@',
    `+${splitCredentialParts[0]}`,
    `diff --git a/${otherCredentialFile} b/${otherCredentialFile}`,
    `--- a/${otherCredentialFile}`,
    `+++ b/${otherCredentialFile}`,
    '@@ -1,0 +1,2 @@',
    `+${splitCredentialParts[1]}`,
    `+${splitCredentialParts[2]}`,
  ].join('\n');
  assert.equal(
    DeterministicScanner.scan([credentialFile, otherCredentialFile], splitAcrossFiles).hasBlockers,
    false,
  );

  const fourLineCredential = diffFor(credentialFile, [
    's',
    'k-test-',
    'FAKE',
    '-not-a-real-key',
  ]);
  assert.equal(DeterministicScanner.scan([credentialFile], fourLineCredential).hasBlockers, false);

  const smsDiff = diffFor('app/src/main/AndroidManifest.xml', [
    '<uses-permission android:name="android.permission.SEND_SMS" />',
  ]);
  const smsResult = DeterministicScanner.scan(['app/src/main/AndroidManifest.xml'], smsDiff);
  assert.equal(smsResult.hasBlockers, true);
  assert.equal(smsResult.violations[0]?.ruleId, 'SEC-MANIFEST-CAPABILITY');

  const forbiddenManifestCapabilities = [
    '<uses-permission android:name="android.permission.READ_SMS" />',
    '<uses-permission android:name="android.permission.RECEIVE_SMS" />',
    '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />',
    '<service android:permission="android.permission.BIND_VPN_SERVICE" />',
  ];
  for (const declaration of forbiddenManifestCapabilities) {
    const diff = diffFor('app/src/main/AndroidManifest.xml', [declaration]);
    const result = DeterministicScanner.scan(['app/src/main/AndroidManifest.xml'], diff);
    assert.equal(result.hasBlockers, true, declaration);
    assert.equal(result.violations[0]?.ruleId, 'SEC-MANIFEST-CAPABILITY', declaration);
    assert.equal(result.violations[0]?.severity, 'BLOCK', declaration);
  }

  for (const attributeOnly of [
    'android:permission="android.permission.BIND_VPN_SERVICE"',
    'android:name="android.permission.SEND_SMS"',
  ]) {
    const result = DeterministicScanner.scan(
      ['app/src/main/AndroidManifest.xml'],
      diffFor('app/src/main/AndroidManifest.xml', [attributeOnly]),
    );
    assert.equal(result.hasBlockers, true, attributeOnly);
    assert.equal(result.violations[0]?.ruleId, 'SEC-MANIFEST-CAPABILITY', attributeOnly);
    assert.equal(result.violations[0]?.severity, 'BLOCK', attributeOnly);
  }

  const quotedManifestDiff = diffFor('app/src/main/AndroidManifest.xml', [
    '<uses-permission android:name="android.permission.SEND_SMS" />',
  ]).replace('+++ b/app/src/main/AndroidManifest.xml', '+++ "b/app/src/main/AndroidManifest.xml"');
  const quotedManifestResult = DeterministicScanner.scan(
    ['app/src/main/AndroidManifest.xml'],
    quotedManifestDiff,
  );
  assert.equal(quotedManifestResult.hasBlockers, true);
  assert.equal(quotedManifestResult.violations[0]?.ruleId, 'SEC-MANIFEST-CAPABILITY');

  const playAccessibility = diffFor('app/src/play/AndroidManifest.xml', [
    '<service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" />',
  ]);
  const playResult = DeterministicScanner.scan(['app/src/play/AndroidManifest.xml'], playAccessibility);
  assert.equal(playResult.passed, true);
  assert.equal(playResult.violations[0]?.ruleId, 'FLAVOR-BOUNDARY');
  assert.equal(playResult.violations[0]?.severity, 'WARN');

  const fossilDisabledAccessibility = diffFor('app/src/foss/AndroidManifest.xml', [
    '<service android:enabled="false" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" />',
  ]);
  assert.deepEqual(
    DeterministicScanner.scan(['app/src/foss/AndroidManifest.xml'], fossilDisabledAccessibility).violations,
    [],
  );

  const backupFlag = diffFor('app/src/main/AndroidManifest.xml', ['<application android:allowBackup="true" />']);
  const backupResult = DeterministicScanner.scan(['app/src/main/AndroidManifest.xml'], backupFlag);
  assert.equal(backupResult.hasBlockers, false);
  assert.equal(backupResult.violations[0]?.ruleId, 'BUILD-DEBUG-FLAG');

  const truncation = truncateDiff('0123456789', 5);
  assert.equal(truncation.truncated, true);
  assert.equal(truncation.originalLength, 10);
  assert.ok(truncation.diff.length <= 5);
  const noticedTruncation = truncateDiff('0123456789'.repeat(20), 100);
  assert.ok(noticedTruncation.diff.length <= 100);
  assert.ok(noticedTruncation.diff.includes('PocketGuard diff truncated'));

  const filteredFiles = filterReviewDiffFiles([
    'app/src/main/res/drawable/screenshot.png',
    'app/src/main/AndroidManifest.xml',
    'app/build.gradle.kts',
    'app/build/proguard-rules.pro',
    'app/build/proguard/rules.png',
    'app/src/main/res/permission-map.map',
    '.github/workflows/ci.yml',
    'app/src/test/assets/screenshot.png',
    'app/src/test/java/example/SampleTest.kt',
  ]);
  assert.deepEqual(filteredFiles, [
    'app/src/main/AndroidManifest.xml',
    'app/build.gradle.kts',
    'app/build/proguard-rules.pro',
    'app/build/proguard/rules.png',
    'app/src/main/res/permission-map.map',
    '.github/workflows/ci.yml',
    'app/src/test/assets/screenshot.png',
    'app/src/test/java/example/SampleTest.kt',
  ]);

  const boundaryDiffs: Record<string, string> = {
    'app/src/main/java/example/First.kt': 'A'.repeat(999850),
    'app/src/main/java/example/Second.kt': 'B'.repeat(1000),
  };
  const assembled = buildReviewDiff({
    runGit: (args) => boundaryDiffs[args[args.length - 1]] ?? '',
  }, 'a'.repeat(40), 'b'.repeat(40), Object.keys(boundaryDiffs));
  assert.equal(assembled.diff.length, MAX_DIFF_LENGTH);
  assert.ok(assembled.diff.includes('PocketGuard diff truncated'));
  assert.deepEqual(assembled.coverage.truncatedFiles, ['app/src/main/java/example/Second.kt']);

  const pathspecMagicFile = ':(exclude)**';
  const pathspecMagicDiff = [
    `diff --git a/${pathspecMagicFile} b/${pathspecMagicFile}`,
    `--- a/${pathspecMagicFile}`,
    `+++ b/${pathspecMagicFile}`,
    '@@ -0,0 +1 @@',
    `+${FAKE_SECRET_FIXTURES.credential}`,
  ].join('\n');
  let usedLiteralPathspecs = false;
  const literalPathspecReview = buildReviewDiff({
    runGit: (args) => {
      if (args[0] !== '--literal-pathspecs' || args[1] !== 'diff') return '';
      usedLiteralPathspecs = true;
      assert.equal(args[args.length - 1], pathspecMagicFile);
      return pathspecMagicDiff;
    },
  }, 'a'.repeat(40), 'b'.repeat(40), [pathspecMagicFile]);
  assert.equal(usedLiteralPathspecs, true, 'PR-controlled filenames use literal Git pathspec handling');
  assert.equal(literalPathspecReview.coverage.complete, true);
  assert.ok(literalPathspecReview.fullDiff.includes(FAKE_SECRET_FIXTURES.credential));
  assert.ok(literalPathspecReview.diff.includes(FAKE_SECRET_FIXTURES.credential));
  assert.equal(
    DeterministicScanner.scan([pathspecMagicFile], literalPathspecReview.fullDiff).hasBlockers,
    true,
    'the literal filename diff reaches deterministic scanning',
  );

  assert.throws(() => buildReviewDiff({
    runGit: () => '',
  }, 'a'.repeat(40), 'b'.repeat(40), [pathspecMagicFile]),
  /no diff for a changed review file/, 'empty retrieval for a review-eligible changed file fails closed');

  assert.deepEqual(prioritizeFiles([
    'docs/guide.md',
    'app/src/main/java/example/Regular.java',
    'app/src/test/java/example/RegularTest.java',
    '.github/workflows/ci.yml',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/example/CredentialStore.kt',
  ]), [
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/example/CredentialStore.kt',
    'app/build.gradle.kts',
    '.github/workflows/ci.yml',
    'app/src/main/java/example/Regular.java',
    'app/src/test/java/example/RegularTest.java',
    'docs/guide.md',
  ]);

  const incompleteCoverage = coverageSummary({
    complete: false,
    omittedFiles: ['not-included.kt'],
    truncatedFiles: ['partially-included.kt'],
    originalLength: 130001,
  });
  assert.match(incompleteCoverage, /^Review coverage: incomplete;/);
  assert.ok(incompleteCoverage.includes('1 omitted file(s)'));
  // Callers must treat incomplete coverage as requiring a human decision.

  assert.throws(
    () => resolveOpenAIConfig({ OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL, OPENAI_API_KEY: FAKE_SECRET_FIXTURES.placeholderKey }, FAKE_ALLOWED_ORIGINS),
    /OpenAI configuration/,
  );
  assert.throws(
    () => resolveOpenAIConfig({ OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL.replace('https:', 'http:'), OPENAI_API_KEY: 'fake-key' }, FAKE_ALLOWED_ORIGINS),
    /OpenAI configuration/,
  );
  const userInfoUrl = new URL(FAKE_OPENAI_BASE_URL);
  userInfoUrl.username = 'fake-user';
  userInfoUrl.password = 'fake-pass';
  assert.throws(
    () => resolveOpenAIConfig({ OPENAI_BASE_URL: userInfoUrl.toString(), OPENAI_API_KEY: 'fake-key' }, FAKE_ALLOWED_ORIGINS),
    /OpenAI configuration/,
  );
  assert.throws(
    () => resolveOpenAIConfig({ OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL, OPENAI_API_KEY: 'fake-key' }, []),
    /OpenAI configuration/,
  );
  for (const suffix of ['?', '#']) {
    assert.throws(
      () => resolveOpenAIConfig({ OPENAI_BASE_URL: `${FAKE_OPENAI_BASE_URL}${suffix}`, OPENAI_API_KEY: 'fake-key' }, FAKE_ALLOWED_ORIGINS),
      /OpenAI configuration/,
    );
  }
  assert.throws(() => resolveRoleModel('android_sec', {}), /model not configured/);
  // Fork onboarding defaults: an unset base URL falls back to the official
  // endpoint and an empty allowlist falls back to the official origin.
  const defaultedConfig = resolveOpenAIConfig({ OPENAI_API_KEY: 'fake-key' }, []);
  assert.equal(defaultedConfig.baseUrl, 'https://api.openai.com/v1');
  const placeholderBase = resolveOpenAIConfig(
    { OPENAI_BASE_URL: '${OPENAI_BASE_URL}', OPENAI_API_KEY: 'fake-key' },
    [],
  );
  assert.equal(placeholderBase.baseUrl, 'https://api.openai.com/v1');
  assert.throws(
    () => resolveOpenAIConfig({ OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL, OPENAI_API_KEY: 'fake-key' }, []),
    /OpenAI configuration/,
    'a custom endpoint still requires its origin in the allowlist',
  );

  const originalFetch = globalThis.fetch;
  const openaiEnvironment = {
    OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL,
    OPENAI_API_KEY: 'fake-api-key',
    POCKETGUARD_MODEL_PROFILES: JSON.stringify({
      [FAKE_MODEL_ID]: 'chat',
      'gpt-5.2': 'reasoning:high',
      'gpt-4o': 'chat',
    }),
  };
  try {
    let ordinaryRedirectMode: RequestInit['redirect'] | undefined;
    let recordedHeaders: HeadersInit | undefined;
    globalThis.fetch = (async (_input, init) => {
      ordinaryRedirectMode = init?.redirect;
      recordedHeaders = init?.headers;
      return new Response(JSON.stringify({
        output: [{ content: [{ type: 'output_text', text: 'review result' }] }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    const result = await sendOpenAISingleTurn({
      modelId: FAKE_MODEL_ID,
      systemPrompt: 'fake system prompt',
      userPrompt: 'fake user prompt',
      allowedOrigins: FAKE_ALLOWED_ORIGINS,
      env: openaiEnvironment,
      timeoutMs: 1000,
    });
    assert.deepEqual(result, { content: 'review result', modelId: FAKE_MODEL_ID });
    assert.equal(ordinaryRedirectMode, 'error');
    const headerRecord = recordedHeaders as Record<string, string> | undefined;
    assert.equal(headerRecord?.['User-Agent'], 'LibrePocket-Bot/1.0');

    const redirectTarget = 'https://redirect-target.test/receive';
    for (const status of [307, 308]) {
      let targetRequests = 0;
      const forwardedBodies: string[] = [];
      const initialBodies: string[] = [];
      const invalidRedirectModes: unknown[] = [];
      const unexpectedTargets: string[] = [];
      globalThis.fetch = (async (input, init) => {
        const url = new URL(String(input));
        if (url.origin === new URL(FAKE_OPENAI_BASE_URL).origin) {
          initialBodies.push(typeof init?.body === 'string' ? init.body : '<missing body>');
          const redirectResponse = new Response(null, {
            status,
            headers: { Location: redirectTarget },
          });
          if (init?.redirect === 'error') throw new TypeError('redirect rejected');
          if (init?.redirect === undefined || init.redirect === 'follow') {
            return globalThis.fetch(redirectResponse.headers.get('Location')!, init);
          }
          invalidRedirectModes.push(init.redirect);
          return redirectResponse;
        }

        if (url.href === redirectTarget) {
          targetRequests += 1;
          if (typeof init?.body === 'string') forwardedBodies.push(init.body);
          return new Response(JSON.stringify({
            output: [{ content: [{ type: 'output_text', text: 'redirected response' }] }],
          }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }

        unexpectedTargets.push(url.href);
        return new Response('unexpected target', { status: 500 });
      }) as typeof fetch;

      await assert.rejects(
        sendOpenAISingleTurn({
          modelId: FAKE_MODEL_ID,
          systemPrompt: 'fake system prompt',
          userPrompt: 'fake user prompt',
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
          env: openaiEnvironment,
          timeoutMs: 1000,
        }),
        /OpenAI request failed/,
        `${status} redirect should be rejected`,
      );
      assert.equal(initialBodies.length, 1, `${status} initial request count`);
      assert.ok(initialBodies[0]?.includes('fake user prompt'), `${status} request body should reach the configured endpoint`);
      assert.equal(targetRequests, 0, `${status} redirect target request count`);
      assert.deepEqual(forwardedBodies, [], `${status} request body must not be forwarded`);
      assert.deepEqual(invalidRedirectModes, [], `${status} should use a supported redirect mode`);
      assert.deepEqual(unexpectedTargets, [], `${status} should not reach an unexpected target`);
    }

    globalThis.fetch = (async () => new Response('FAKE_RESPONSE_BODY_SECRET', { status: 503 })) as typeof fetch;
    await assert.rejects(
      sendOpenAISingleTurn({
        modelId: FAKE_MODEL_ID,
        systemPrompt: 'fake system prompt',
        userPrompt: 'fake user prompt',
        allowedOrigins: FAKE_ALLOWED_ORIGINS,
        env: openaiEnvironment,
        timeoutMs: 1000,
      }),
      (error: unknown) => {
        assert.ok(error instanceof Error);
        assert.equal(error.message, 'OpenAI request failed');
        assert.equal(error.message.includes('FAKE_RESPONSE_BODY_SECRET'), false);
        assert.equal((error as Error & { statusCode?: number }).statusCode, 503);
        return true;
      },
    );

    globalThis.fetch = (async () => new Response(JSON.stringify({ output: [] }), { status: 200 })) as typeof fetch;
    await assert.rejects(
      sendOpenAISingleTurn({
        modelId: FAKE_MODEL_ID,
        systemPrompt: 'fake system prompt',
        userPrompt: 'fake user prompt',
        allowedOrigins: FAKE_ALLOWED_ORIGINS,
        env: openaiEnvironment,
        timeoutMs: 1000,
      }),
      /OpenAI response empty/,
    );

    const orchestratorEnv = {
      ...openaiEnvironment,
      POCKETGUARD_MODEL_CHIEF: FAKE_MODEL_ID,
      POCKETGUARD_MODEL_ANDROID_SEC: FAKE_MODEL_ID,
      POCKETGUARD_MODEL_ANDROID_CODE: FAKE_MODEL_ID,
    };
    async function runOrchestratorResponse(content: string, coverage = {
      complete: true,
      omittedFiles: [] as string[],
      truncatedFiles: [] as string[],
      originalLength: 20,
    }) {
      const previous = globalThis.fetch;
      const requests: Array<Record<string, unknown>> = [];
      globalThis.fetch = (async (_input, init) => {
        requests.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
        return new Response(JSON.stringify({
          output: [{ content: [{ type: 'output_text', text: content }] }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }) as typeof fetch;
      try {
        const review = await orchestrateReview({
          changedFiles: ['app/src/main/AndroidManifest.xml'],
          diff: '+synthetic test diff',
          coverage,
          deterministicViolations: [],
          env: orchestratorEnv,
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
        });
        assert.equal(requests.length, 3);
        for (const request of requests) {
          assert.equal(Array.isArray(request.input), true);
          assert.equal((request.input as unknown[]).length, 2);
          assert.equal(Object.hasOwn(request, 'tools'), false);
          assert.equal(Object.hasOwn(request, 'previous_response_id'), false);
        }
        return review;
      } finally {
        globalThis.fetch = previous;
      }
    }

    const blockingRole = await runOrchestratorResponse(JSON.stringify({
      verdict: 'APPROVE',
      findings: [{ severity: 'BLOCK', issue: 'Synthetic blocking finding.' }],
    }));
    assert.equal(blockingRole.verdict, 'NEEDS_CHANGES');

    const omittedCritical = await runOrchestratorResponse(JSON.stringify({ verdict: 'APPROVE', findings: [] }), {
      complete: false,
      omittedFiles: ['app/src/main/AndroidManifest.xml'],
      truncatedFiles: [],
      originalLength: 20,
    });
    assert.equal(omittedCritical.verdict, 'INCONCLUSIVE');

    for (const omittedFile of [
      'app/src/main/AndroidManifest.xml',
      'app/build.gradle.kts',
      '.github/workflows/ci.yml',
    ]) {
      const completeWithOmission = await runOrchestratorResponse(JSON.stringify({ verdict: 'APPROVE', findings: [] }), {
        complete: true,
        omittedFiles: [omittedFile],
        truncatedFiles: [],
        originalLength: 20,
      });
      assert.equal(completeWithOmission.coverage.complete, true, omittedFile);
      assert.equal(completeWithOmission.verdict, 'INCONCLUSIVE', omittedFile);
    }

    const unknownFindingField = await runOrchestratorResponse(JSON.stringify({
      verdict: 'APPROVE',
      findings: [{ severity: 'WARN', issue: 'Synthetic finding.', unexpected: true }],
    }));
    assert.equal(unknownFindingField.verdict, 'INCONCLUSIVE');
    assert.equal(unknownFindingField.roles.every((role) => role.findings.length === 0), true);

    const unknownTopLevelField = await runOrchestratorResponse(JSON.stringify({
      verdict: 'APPROVE',
      findings: [],
      unexpected: true,
    }));
    assert.equal(unknownTopLevelField.verdict, 'INCONCLUSIVE');
    assert.equal(unknownTopLevelField.roles.every((role) => role.verdict === 'INCONCLUSIVE'), true);

    // P1 #3: per-model request profiles. Reasoning bodies carry
    // reasoning.effort and never temperature/top_p; chat bodies never carry
    // reasoning; the default body is otherwise minimal.
    async function captureSingleTurnBody(
      modelId: string,
      env: Record<string, string>,
      extra: { temperature?: number; topP?: number } = {},
    ): Promise<Record<string, unknown>> {
      const previous = globalThis.fetch;
      const bodies: Array<Record<string, unknown>> = [];
      globalThis.fetch = (async (_input, init) => {
        bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
        return new Response(JSON.stringify({
          output: [{ content: [{ type: 'output_text', text: 'profile probe' }] }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }) as typeof fetch;
      try {
        await sendOpenAISingleTurn({
          modelId,
          systemPrompt: 'fake system prompt',
          userPrompt: 'fake user prompt',
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
          env,
          timeoutMs: 1000,
          ...extra,
        });
        assert.equal(bodies.length, 1);
        return bodies[0] as Record<string, unknown>;
      } finally {
        globalThis.fetch = previous;
      }
    }

    const reasoningBody = await captureSingleTurnBody('gpt-5.2', openaiEnvironment);
    assert.deepEqual(reasoningBody.reasoning, { effort: 'high' });
    assert.deepEqual(reasoningBody.include, ['reasoning.encrypted_content']);
    assert.equal(Object.hasOwn(reasoningBody, 'temperature'), false);
    assert.equal(Object.hasOwn(reasoningBody, 'top_p'), false);
    assert.equal(reasoningBody.model, 'gpt-5.2');
    assert.equal(reasoningBody.stream, false);

    const chatBody = await captureSingleTurnBody('gpt-4o', openaiEnvironment);
    assert.equal(Object.hasOwn(chatBody, 'reasoning'), false);
    assert.equal(Object.hasOwn(chatBody, 'temperature'), false);
    assert.equal(Object.hasOwn(chatBody, 'top_p'), false);
    assert.equal(chatBody.model, 'gpt-4o');

    // Explicit temperature/topP are forwarded on chat bodies (send_openai.ts
    // sends them only when the caller sets them).
    const chatExplicitBody = await captureSingleTurnBody('gpt-4o', openaiEnvironment, {
      temperature: 0.2,
      topP: 0.9,
    });
    assert.equal(Object.hasOwn(chatExplicitBody, 'reasoning'), false);
    assert.equal(chatExplicitBody.temperature, 0.2);
    assert.equal(chatExplicitBody.top_p, 0.9);

    // Reasoning with effort none sends reasoning:{effort:'none'} explicitly
    // (P2 #3; never omitted) and stays mutually exclusive with
    // temperature/top_p.
    const noneEnv = {
      ...openaiEnvironment,
      POCKETGUARD_MODEL_CHIEF: 'gpt-5.2',
      POCKETGUARD_MODEL_CHIEF_PROFILE: 'reasoning:none',
    };
    assert.deepEqual(resolveModelProfile('gpt-5.2', noneEnv), { kind: 'reasoning', effort: 'none' });
    const noneBody = await captureSingleTurnBody('gpt-5.2', noneEnv);
    assert.deepEqual(noneBody.reasoning, { effort: 'none' });
    assert.equal(Object.hasOwn(noneBody, 'temperature'), false);
    assert.equal(Object.hasOwn(noneBody, 'top_p'), false);

    // P2 #3 explicit-none wire contract: the reasoning key is present with
    // effort none (never omitted).
    {
      assert.equal(JSON.stringify(noneBody).includes('"effort":"none"'), true, 'none is explicit on the wire');
      assert.equal(JSON.stringify(noneBody).includes('"reasoning"'), true, 'none carries the reasoning key');
      assert.equal(isReasoningNoneSupported('gpt-5.2'), true, 'gpt-5 supports none');
      assert.equal(isReasoningNoneSupported('gpt-5.5'), true, 'gpt-5.5 supports none');
      assert.equal(isReasoningNoneSupported('o3-mini'), false, 'o-series does not support none');
    }

    // P2 #3 gpt-5.5 none is explicit on the wire.
    {
      const none55Env = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_CHIEF: 'gpt-5.5',
        POCKETGUARD_MODEL_CHIEF_PROFILE: 'reasoning:none',
      };
      assert.deepEqual(resolveModelProfile('gpt-5.5', none55Env), { kind: 'reasoning', effort: 'none' });
      const none55Body = await captureSingleTurnBody('gpt-5.5', none55Env);
      assert.deepEqual(none55Body.reasoning, { effort: 'none' });
      assert.equal(Object.hasOwn(none55Body, 'temperature'), false);
      assert.equal(Object.hasOwn(none55Body, 'top_p'), false);
    }

    // P2 #7 JSON-map none path: string, object, and prefix-key forms all
    // resolve to reasoning none and send it explicitly on the wire.
    {
      const jsonMapStringEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_PROFILES: JSON.stringify({ 'gpt-5.2': 'reasoning:none' }),
      };
      assert.deepEqual(resolveModelProfile('gpt-5.2', jsonMapStringEnv), { kind: 'reasoning', effort: 'none' });
      const jsonMapStringBody = await captureSingleTurnBody('gpt-5.2', jsonMapStringEnv);
      assert.deepEqual(jsonMapStringBody.reasoning, { effort: 'none' });

      const jsonMapObjectEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_PROFILES: JSON.stringify({ 'gpt-5.2': { kind: 'reasoning', effort: 'none' } }),
      };
      assert.deepEqual(resolveModelProfile('gpt-5.2', jsonMapObjectEnv), { kind: 'reasoning', effort: 'none' });
      const jsonMapObjectBody = await captureSingleTurnBody('gpt-5.2', jsonMapObjectEnv);
      assert.deepEqual(jsonMapObjectBody.reasoning, { effort: 'none' });

      // Longest-prefix key: a 'gpt-5' map entry covers family variants.
      const jsonMapPrefixEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_PROFILES: JSON.stringify({ 'gpt-5': 'reasoning:none' }),
      };
      assert.deepEqual(resolveModelProfile('gpt-5-codex', jsonMapPrefixEnv), { kind: 'reasoning', effort: 'none' });
      const jsonMapPrefixBody = await captureSingleTurnBody('gpt-5-codex', jsonMapPrefixEnv);
      assert.deepEqual(jsonMapPrefixBody.reasoning, { effort: 'none' });
      assert.equal(JSON.stringify(jsonMapPrefixBody).includes('"effort":"none"'), true);
    }

    // P2 #7 effort case-insensitivity: None/NONE (with surrounding
    // whitespace) resolve as explicit none and normalize to 'none' on
    // the wire; unsupported models still fail closed.
    for (const effort of ['None', 'NONE', ' nOnE ']) {
      const caseEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_CHIEF: 'gpt-5.2',
        POCKETGUARD_MODEL_CHIEF_PROFILE: `reasoning:${effort}`,
      };
      assert.deepEqual(resolveModelProfile('gpt-5.2', caseEnv), { kind: 'reasoning', effort: effort.trim() });
      const caseBody = await captureSingleTurnBody('gpt-5.2', caseEnv);
      assert.deepEqual(caseBody.reasoning, { effort: 'none' }, `effort ${JSON.stringify(effort)} normalizes on the wire`);

      const caseMapEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_PROFILES: JSON.stringify({ 'gpt-5.2': `reasoning:${effort}` }),
      };
      assert.deepEqual(resolveModelProfile('gpt-5.2', caseMapEnv), { kind: 'reasoning', effort: effort.trim() });
      const caseMapBody = await captureSingleTurnBody('gpt-5.2', caseMapEnv);
      assert.deepEqual(caseMapBody.reasoning, { effort: 'none' }, `map effort ${JSON.stringify(effort)} normalizes on the wire`);
    }
    {
      const unsupportedCaseEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_CHIEF: 'o3-mini',
        POCKETGUARD_MODEL_CHIEF_PROFILE: 'reasoning:NONE',
      };
      assert.equal(resolveModelProfile('o3-mini', unsupportedCaseEnv), undefined, 'o3-mini + NONE is undefined');
    }

    // P2 #7 gpt-5 prefix variants: isReasoningNoneSupported matches the
    // whole family by prefix (case-insensitive); wire proof for a short
    // variant and an uppercase model id.
    {
      for (const modelId of ['gpt-5', 'gpt-5-mini', 'gpt-5-nano', 'gpt-5.2', 'gpt-5.5-turbo', 'GPT-5.2']) {
        assert.equal(isReasoningNoneSupported(modelId), true, `${modelId} supports none`);
      }
      assert.equal(isReasoningNoneSupported('gpt-4o'), false, 'gpt-4o does not support none');
      assert.equal(isReasoningNoneSupported(''), false, 'empty model id does not support none');

      const miniMapEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_PROFILES: JSON.stringify({ 'gpt-5-mini': 'reasoning:none' }),
      };
      assert.deepEqual(resolveModelProfile('gpt-5-mini', miniMapEnv), { kind: 'reasoning', effort: 'none' });
      const miniBody = await captureSingleTurnBody('gpt-5-mini', miniMapEnv);
      assert.deepEqual(miniBody.reasoning, { effort: 'none' });

      const upperEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_CHIEF: 'GPT-5.2',
        POCKETGUARD_MODEL_CHIEF_PROFILE: 'reasoning:none',
      };
      assert.deepEqual(resolveModelProfile('GPT-5.2', upperEnv), { kind: 'reasoning', effort: 'none' });
      const upperBody = await captureSingleTurnBody('GPT-5.2', upperEnv);
      assert.deepEqual(upperBody.reasoning, { effort: 'none' });
    }

    // P2 #3 temperature/top_p are mutually exclusive with reasoning
    // (including explicit none): carrying both throws before any fetch.
    {
      const previous = globalThis.fetch;
      let fetchCount = 0;
      globalThis.fetch = (async () => {
        fetchCount += 1;
        return new Response(JSON.stringify({
          output: [{ content: [{ type: 'output_text', text: 'must not be called' }] }],
        }), { status: 200 });
      }) as typeof fetch;
      try {
        await assert.rejects(
          sendOpenAISingleTurn({
            modelId: 'gpt-5.2',
            systemPrompt: 'fake system prompt',
            userPrompt: 'fake user prompt',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: noneEnv,
            timeoutMs: 1000,
            temperature: 0.2,
          }),
          /temperature\/top_p/,
        );
        await assert.rejects(
          sendOpenAISingleTurn({
            modelId: 'gpt-5.2',
            systemPrompt: 'fake system prompt',
            userPrompt: 'fake user prompt',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: openaiEnvironment,
            timeoutMs: 1000,
            temperature: 0.2,
          }),
          /temperature\/top_p/,
        );
        assert.equal(fetchCount, 0, 'mutually exclusive params make zero fetch calls');
      } finally {
        globalThis.fetch = previous;
      }
    }

    // P2 #5 unsupported none fails closed with zero fetch (before any send).
    {
      const unsupportedEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_CHIEF: 'o3-mini',
        POCKETGUARD_MODEL_CHIEF_PROFILE: 'reasoning:none',
      };
      assert.equal(resolveModelProfile('o3-mini', unsupportedEnv), undefined, 'o3-mini + none is undefined');
      const previous = globalThis.fetch;
      let fetchCount = 0;
      globalThis.fetch = (async () => {
        fetchCount += 1;
        return new Response(JSON.stringify({
          output: [{ content: [{ type: 'output_text', text: 'must not be called' }] }],
        }), { status: 200 });
      }) as typeof fetch;
      try {
        await assert.rejects(
          sendOpenAISingleTurn({
            modelId: 'o3-mini',
            systemPrompt: 'fake system prompt',
            userPrompt: 'fake user prompt',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: unsupportedEnv,
            timeoutMs: 1000,
          }),
          /model profile not configured/,
        );
        assert.equal(fetchCount, 0, 'unsupported none makes zero fetch calls');
      } finally {
        globalThis.fetch = previous;
      }
    }

    // P2 #3 unsupported none on a chat-family model also fails closed.
    {
      const chatNoneEnv = {
        ...openaiEnvironment,
        POCKETGUARD_MODEL_CHIEF: 'gpt-4o',
        POCKETGUARD_MODEL_CHIEF_PROFILE: 'reasoning:none',
      };
      assert.equal(resolveModelProfile('gpt-4o', chatNoneEnv), undefined, 'gpt-4o + none is undefined');
      const previous = globalThis.fetch;
      let fetchCount = 0;
      globalThis.fetch = (async () => {
        fetchCount += 1;
        return new Response(JSON.stringify({
          output: [{ content: [{ type: 'output_text', text: 'must not be called' }] }],
        }), { status: 200 });
      }) as typeof fetch;
      try {
        await assert.rejects(
          sendOpenAISingleTurn({
            modelId: 'gpt-4o',
            systemPrompt: 'fake system prompt',
            userPrompt: 'fake user prompt',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: chatNoneEnv,
            timeoutMs: 1000,
          }),
          /model profile not configured/,
        );
        assert.equal(fetchCount, 0, 'chat-family none makes zero fetch calls');
      } finally {
        globalThis.fetch = previous;
      }
    }

    // P2 #5 DEFAULT_REASONING_EFFORT is env-configurable (vars preferred).
    {
      assert.equal(resolveDefaultReasoningEffort({}), 'high', 'builtin default is high');
      assert.equal(
        resolveDefaultReasoningEffort({ POCKETGUARD_REASONING_EFFORT: 'medium' }),
        'medium',
      );
      assert.equal(
        resolveDefaultReasoningEffort({ POCKETGUARD_DEFAULT_REASONING_EFFORT: 'low' }),
        'low',
      );
      const customDefaultEnv = {
        OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL,
        OPENAI_API_KEY: 'fake-api-key',
        POCKETGUARD_REASONING_EFFORT: 'medium',
      };
      const customDefaultBody = await captureSingleTurnBody('gpt-5.2', customDefaultEnv);
      assert.deepEqual(customDefaultBody.reasoning, { effort: 'medium' }, 'env default backs builtin reasoning');
    }

    // Builtin family defaults apply without any explicit profile map.
    const builtinEnv = { OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL, OPENAI_API_KEY: 'fake-api-key' };
    const builtinReasoning = await captureSingleTurnBody('gpt-5.2', builtinEnv);
    assert.deepEqual(builtinReasoning.reasoning, { effort: 'high' });
    assert.equal(Object.hasOwn(builtinReasoning, 'temperature'), false);
    const builtinChat = await captureSingleTurnBody('gpt-4o-mini', builtinEnv);
    assert.equal(Object.hasOwn(builtinChat, 'reasoning'), false);

    // Per-role profile override with a custom effort.
    const perRoleEnv = {
      ...openaiEnvironment,
      POCKETGUARD_MODEL_CHIEF: 'gpt-5.2',
      POCKETGUARD_MODEL_CHIEF_PROFILE: 'reasoning:medium',
    };
    const perRoleBody = await captureSingleTurnBody('gpt-5.2', perRoleEnv);
    assert.deepEqual(perRoleBody.reasoning, { effort: 'medium' });
    assert.equal(Object.hasOwn(perRoleBody, 'temperature'), false);
    assert.deepEqual(resolveModelProfile('gpt-5.2', perRoleEnv), { kind: 'reasoning', effort: 'medium' });
    assert.deepEqual(resolveModelProfile('gpt-4o', openaiEnvironment), { kind: 'chat' });

    // A 400 for an unsupported parameter rejects with its status code.
    {
      const previous = globalThis.fetch;
      globalThis.fetch = (async () => new Response('unsupported parameter', { status: 400 })) as typeof fetch;
      try {
        await assert.rejects(
          sendOpenAISingleTurn({
            modelId: 'gpt-5.2',
            systemPrompt: 'fake system prompt',
            userPrompt: 'fake user prompt',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: openaiEnvironment,
            timeoutMs: 1000,
          }),
          (error: unknown) => {
            assert.ok(error instanceof Error);
            assert.equal((error as Error & { statusCode?: number }).statusCode, 400);
            return true;
          },
        );
      } finally {
        globalThis.fetch = previous;
      }
    }

    // Orchestrator 400 fail-closed: three roles INCONCLUSIVE, one fetch each,
    // no retry, no APPROVE, no label suggestions.
    {
      const previous = globalThis.fetch;
      let fetchCount = 0;
      globalThis.fetch = (async () => {
        fetchCount += 1;
        return new Response('unsupported parameter', { status: 400 });
      }) as typeof fetch;
      try {
        const review = await orchestrateReview({
          changedFiles: ['app/src/main/AndroidManifest.xml'],
          diff: '+synthetic test diff',
          coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 20 },
          deterministicViolations: [],
          env: orchestratorEnv,
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
        });
        assert.equal(fetchCount, 3, 'each role makes exactly one request with no retry');
        assert.equal(review.roles.length, 3);
        assert.ok(review.roles.every((role) => role.verdict === 'INCONCLUSIVE'));
        assert.ok(review.roles.every((role) => role.suggestedLabels.length === 0));
        assert.equal(review.verdict, 'INCONCLUSIVE');
      } finally {
        globalThis.fetch = previous;
      }
    }

    // Triage 400 fail-closed: INCONCLUSIVE with no suggestions.
    {
      const previous = globalThis.fetch;
      globalThis.fetch = (async () => new Response('unsupported parameter', { status: 400 })) as typeof fetch;
      try {
        const triaged = await triageIssue({
          input: { title: 'fake title', body: 'fake body', comments: [] },
          env: orchestratorEnv,
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
        });
        assert.deepEqual(triaged, { verdict: 'INCONCLUSIVE', summary: '', suggestedLabels: [] });
      } finally {
        globalThis.fetch = previous;
      }
    }

    // Issue triage redaction: raw credentials never reach the model, while
    // the deterministic scanner still flags the ORIGINAL content. Order
    // contract: scan original first, send only the redacted copy to AI.
    {
      const fakeToken = ['s', 'k-test-FAKE-REDACT-12345678'].join('');
      const beginMarker = ['-----BEGIN', 'PRIVATE KEY-----'].join(' ');
      const endMarker = ['-----END', 'PRIVATE KEY-----'].join(' ');
      const fakeKeyBody = 'FAKE-BODY-12345678';
      const fakeKeyBlock = `${beginMarker}\n${fakeKeyBody}\n${endMarker}`;
      const rawTitle = `help with pasted token ${fakeToken}`;
      const rawBody = `pasted key block:\n${fakeKeyBlock}`;
      const rawComments = [`follow-up still shows ${fakeToken}`];
      const originalText = [rawTitle, rawBody, ...rawComments].join('\n');
      const scanOriginal = DeterministicScanner.scan(['issue.txt'], originalText);
      assert.equal(scanOriginal.hasBlockers, true, 'scanner flags the original issue credentials');
      assert.ok(scanOriginal.violations.some((violation) => violation.ruleId === 'SEC-PRIVATE-KEY'));

      const previous = globalThis.fetch;
      let fetchCount = 0;
      let capturedUserPrompt = '';
      globalThis.fetch = (async (_input, init) => {
        fetchCount += 1;
        const body = JSON.parse(String(init?.body)) as { input: Array<{ role: string; content: string }> };
        capturedUserPrompt = body.input.find((entry) => entry.role === 'user')?.content ?? '';
        return new Response(JSON.stringify({
          output: [{ content: [{ type: 'output_text', text: JSON.stringify({ verdict: 'INCONCLUSIVE', summary: 'ok', suggestedLabels: [] }) }] }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }) as typeof fetch;
      try {
        const triaged = await triageIssue({
          input: { title: rawTitle, body: rawBody, comments: rawComments },
          env: orchestratorEnv,
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
        });
        assert.equal(fetchCount, 1, 'triage stays single-turn');
        assert.equal(triaged.verdict, 'INCONCLUSIVE');
        assert.ok(!capturedUserPrompt.includes(fakeToken), 'raw token must not reach the model');
        assert.ok(!capturedUserPrompt.includes(fakeKeyBody), 'raw key body must not reach the model');
        assert.ok(!capturedUserPrompt.includes(beginMarker), 'raw key marker must not reach the model');
        assert.ok(capturedUserPrompt.includes('[REDACTED'), 'redacted marker reaches the model instead');
        const scanRedacted = DeterministicScanner.scan(['issue.txt'], capturedUserPrompt);
        assert.equal(scanRedacted.hasBlockers, false, 'redacted prompt carries no detectable secret (scan the original)');
      } finally {
        globalThis.fetch = previous;
      }
    }

    // Unknown profile fail-closed: no request is sent.
    {
      const previous = globalThis.fetch;
      let fetchCount = 0;
      globalThis.fetch = (async () => {
        fetchCount += 1;
        return new Response(JSON.stringify({
          output: [{ content: [{ type: 'output_text', text: 'must not be called' }] }],
        }), { status: 200 });
      }) as typeof fetch;
      try {
        assert.equal(resolveModelProfile('unknown-model-xyz', openaiEnvironment), undefined);
        await assert.rejects(
          sendOpenAISingleTurn({
            modelId: 'unknown-model-xyz',
            systemPrompt: 'fake system prompt',
            userPrompt: 'fake user prompt',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: openaiEnvironment,
            timeoutMs: 1000,
          }),
          /model profile not configured/,
        );
        assert.equal(fetchCount, 0, 'unknown profile must not send a request');
        const unknownReview = await orchestrateReview({
          changedFiles: ['app/src/main/AndroidManifest.xml'],
          diff: '+synthetic test diff',
          coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 20 },
          deterministicViolations: [],
          env: {
            ...openaiEnvironment,
            POCKETGUARD_MODEL_CHIEF: 'unknown-model-xyz',
            POCKETGUARD_MODEL_ANDROID_SEC: 'unknown-model-xyz',
            POCKETGUARD_MODEL_ANDROID_CODE: 'unknown-model-xyz',
          },
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
        });
        assert.ok(unknownReview.roles.every((role) => role.verdict === 'INCONCLUSIVE'));
        assert.ok(unknownReview.roles.every((role) => role.suggestedLabels.length === 0));
        assert.equal(unknownReview.verdict, 'INCONCLUSIVE');
        assert.equal(fetchCount, 0, 'unknown profile orchestrator must not send requests');
      } finally {
        globalThis.fetch = previous;
      }
    }

    // --- Model fallback chain (Vegan-scan-app parity, fail-closed deltas). ---
    {
      // Chain composition: primary, then role fallbacks, then global, deduped,
      // placeholders filtered. Unset lists mean no fallback at all.
      assert.deepEqual(
        resolveModelFallbackChain('primary-model', {
          POCKETGUARD_MODEL_CHIEF: 'primary-model',
          POCKETGUARD_MODEL_CHIEF_FALLBACKS: 'b-model, c-model, primary-model, ${MISSING}',
          POCKETGUARD_MODEL_FALLBACKS: 'c-model, d-model',
        }, 'chief'),
        ['primary-model', 'b-model', 'c-model', 'd-model'],
        'chain keeps order, drops duplicates and placeholders',
      );
      assert.deepEqual(
        resolveModelFallbackChain('primary-model', {}, 'chief'),
        ['primary-model'],
        'no configured fallbacks yields the previous single-model behaviour',
      );

      const mapEnv: Record<string, string> = {
        OPENAI_BASE_URL: FAKE_OPENAI_BASE_URL,
        OPENAI_API_KEY: 'fake-key-for-fallback-probe',
        POCKETGUARD_MODEL_PROFILES: JSON.stringify({
          'primary-model': 'chat',
          'second-model': 'chat',
          'third-model': 'chat',
        }),
      };
      const chainEnv = { ...mapEnv, POCKETGUARD_MODEL_FALLBACKS: 'second-model, third-model' };
      const okResponse = (): Response => new Response(JSON.stringify({
        output: [{ content: [{ type: 'output_text', text: 'ok' }] }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });

      // Retryable status: primary 503 -> next model serves, and the result
      // attributes the model that actually answered.
      {
        const models: string[] = [];
        const previous = globalThis.fetch;
        globalThis.fetch = (async (_input, init) => {
          models.push((JSON.parse(String(init?.body)) as { model: string }).model);
          return models.length === 1
            ? new Response('busy', { status: 503 })
            : okResponse();
        }) as typeof fetch;
        try {
          const result = await sendOpenAISingleTurnWithFallback({
            modelId: 'primary-model',
            systemPrompt: 's',
            userPrompt: 'u',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: chainEnv,
            timeoutMs: 1000,
          });
          assert.deepEqual(models, ['primary-model', 'second-model']);
          assert.equal(result.modelId, 'second-model', 'result attributes the serving model');
          assert.equal(result.content, 'ok');
        } finally {
          globalThis.fetch = previous;
        }
      }

      // Primary 400 (simulating unlaunched or undeployed model) automatically
      // falls back to the second model and returns successfully.
      {
        const models: string[] = [];
        const previous = globalThis.fetch;
        globalThis.fetch = (async (_input, init) => {
          models.push((JSON.parse(String(init?.body)) as { model: string }).model);
          return models.length === 1
            ? new Response('model not deployed', { status: 400 })
            : okResponse();
        }) as typeof fetch;
        try {
          const result = await sendOpenAISingleTurnWithFallback({
            modelId: 'primary-model',
            systemPrompt: 's',
            userPrompt: 'u',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: chainEnv,
            timeoutMs: 1000,
          });
          assert.deepEqual(models, ['primary-model', 'second-model']);
          assert.equal(result.modelId, 'second-model', 'result attributes the serving model after 400 fallback');
          assert.equal(result.content, 'ok');
        } finally {
          globalThis.fetch = previous;
        }
      }

      // A candidate without a profile is skipped without any request; when every
      // candidate is unconfigured the chain fails closed with zero requests.
      {
        let fetchCount = 0;
        const previous = globalThis.fetch;
        globalThis.fetch = (async () => {
          fetchCount += 1;
          return okResponse();
        }) as typeof fetch;
        try {
          const result = await sendOpenAISingleTurnWithFallback({
            modelId: 'unknown-primary',
            systemPrompt: 's',
            userPrompt: 'u',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: { ...mapEnv, POCKETGUARD_MODEL_FALLBACKS: 'second-model' },
            timeoutMs: 1000,
          });
          assert.equal(fetchCount, 1, 'unconfigured primary sends nothing');
          assert.equal(result.modelId, 'second-model');

          fetchCount = 0;
          await assert.rejects(
            sendOpenAISingleTurnWithFallback({
              modelId: 'unknown-primary',
              systemPrompt: 's',
              userPrompt: 'u',
              allowedOrigins: FAKE_ALLOWED_ORIGINS,
              env: { ...mapEnv },
              timeoutMs: 1000,
            }),
            /model profile not configured/,
          );
          assert.equal(fetchCount, 0, 'all-unconfigured chain fails closed without requests');
        } finally {
          globalThis.fetch = previous;
        }
      }

      // Chain exhaustion: all candidates fail -> throws aggregated error without retrying.
      {
        let fetchCount = 0;
        const requestedModels: string[] = [];
        const previous = globalThis.fetch;
        globalThis.fetch = (async (_input, init) => {
          fetchCount += 1;
          requestedModels.push((JSON.parse(String(init?.body)) as { model: string }).model);
          return new Response('busy', { status: 503 });
        }) as typeof fetch;
        try {
          await assert.rejects(
            sendOpenAISingleTurnWithFallback({
              modelId: 'primary-model',
              systemPrompt: 's',
              userPrompt: 'u',
              allowedOrigins: FAKE_ALLOWED_ORIGINS,
              env: chainEnv,
              timeoutMs: 1000,
            }),
            (error: Error & { statusCode?: number }) => {
              assert.match(error.message, /^All models failed/);
              assert.ok(error.message.includes('primary-model'));
              assert.ok(error.message.includes('second-model'));
              assert.ok(error.message.includes('third-model'));
              assert.equal(error.statusCode, 503);
              return true;
            },
          );
          assert.equal(fetchCount, 3, 'each candidate is tried once with no extra retries');
          assert.deepEqual(requestedModels, ['primary-model', 'second-model', 'third-model']);
        } finally {
          globalThis.fetch = previous;
        }
      }

      // Orchestrator level: primary down for every role -> all INCONCLUSIVE and
      // the reported model is the one that actually served (no false credit).
      {
        const roleEnv = {
          ...chainEnv,
          POCKETGUARD_MODEL_CHIEF: 'primary-model',
          POCKETGUARD_MODEL_ANDROID_SEC: 'primary-model',
          POCKETGUARD_MODEL_ANDROID_CODE: 'primary-model',
        };
        const previous = globalThis.fetch;
        const models: string[] = [];
        globalThis.fetch = (async (_input, init) => {
          models.push((JSON.parse(String(init?.body)) as { model: string }).model);
          return new Response('busy', { status: 503 });
        }) as typeof fetch;
        try {
          const review = await orchestrateReview({
            changedFiles: ['app/src/main/java/demo/Safe.kt'],
            diff: 'diff --git a/app/src/main/java/demo/Safe.kt b/app/src/main/java/demo/Safe.kt',
            coverage: { complete: true, omittedFiles: [], truncatedFiles: [], originalLength: 10 },
            deterministicViolations: [],
            env: roleEnv,
            timeoutMs: 1000,
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
          });
          assert.equal(review.verdict, 'INCONCLUSIVE');
          assert.equal(review.roles.every((role) => role.verdict === 'INCONCLUSIVE'), true);
          assert.equal(models.length, 9, 'three roles each walk the full chain');
          // Roles run concurrently, so assert per-model coverage instead of order.
          for (const model of ['primary-model', 'second-model', 'third-model']) {
            assert.equal(models.filter((entry) => entry === model).length, 3,
              `every role reaches ${model}`);
          }
        } finally {
          globalThis.fetch = previous;
        }
      }

      // Message-typed transport failures also fall through (no statusCode).
      for (const failure of ['OpenAI request failed', 'OpenAI response invalid', 'OpenAI response empty'] as const) {
        const models: string[] = [];
        const previous = globalThis.fetch;
        globalThis.fetch = (async (_input, init) => {
          const model = (JSON.parse(String(init?.body)) as { model: string }).model;
          models.push(model);
          // Only the first attempt fails, so the fallback candidate must serve.
          if (models.length > 1) return okResponse();
          if (failure === 'OpenAI request failed') throw new Error('network down');
          if (failure === 'OpenAI response invalid') {
            return new Response('not json', { status: 200, headers: { 'Content-Type': 'application/json' } });
          }
          return new Response(JSON.stringify({ output: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }) as typeof fetch;
        try {
          const result = await sendOpenAISingleTurnWithFallback({
            modelId: 'primary-model',
            systemPrompt: 's',
            userPrompt: 'u',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: chainEnv,
            timeoutMs: 1000,
          });
          assert.deepEqual(models, ['primary-model', 'second-model'], `${failure} falls back`);
          assert.equal(result.modelId, 'second-model');
        } finally {
          globalThis.fetch = previous;
        }
      }

      // A rejected origin / unusable configuration is not a model outage: it
      // stops the chain immediately with zero requests instead of walking the
      // chain looking for a model that can bypass the allowlist.
      {
        let fetchCount = 0;
        const previous = globalThis.fetch;
        globalThis.fetch = (async () => { fetchCount += 1; return okResponse(); }) as typeof fetch;
        try {
          await assert.rejects(
            sendOpenAISingleTurnWithFallback({
              modelId: 'primary-model',
              systemPrompt: 's',
              userPrompt: 'u',
              allowedOrigins: ['https://allowed-origin.test'],
              env: { ...chainEnv, OPENAI_BASE_URL: 'https://disallowed-origin.test/v1' },
              timeoutMs: 1000,
            }),
            /OpenAI configuration rejected/,
          );
          assert.equal(fetchCount, 0, 'configuration errors never touch the chain');
        } finally {
          globalThis.fetch = previous;
        }
      }

      // Primary failure on 404, 500, 503 (as well as 401, 403, 409, 422, 429)
      // successfully falls back to the next candidate.
      for (const status of [404, 500, 503, 401, 403, 409, 422, 429]) {
        const models: string[] = [];
        const previous = globalThis.fetch;
        globalThis.fetch = (async (_input, init) => {
          models.push((JSON.parse(String(init?.body)) as { model: string }).model);
          return models.length === 1
            ? new Response('error', { status })
            : okResponse();
        }) as typeof fetch;
        try {
          const result = await sendOpenAISingleTurnWithFallback({
            modelId: 'primary-model',
            systemPrompt: 's',
            userPrompt: 'u',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: chainEnv,
            timeoutMs: 1000,
          });
          assert.deepEqual(models, ['primary-model', 'second-model'], `status ${status} falls back`);
          assert.equal(result.modelId, 'second-model');
          assert.equal(result.content, 'ok');
        } finally {
          globalThis.fetch = previous;
        }
      }

      // A missing or placeholder primary is a configuration failure: no request
      // is sent and no fallback candidate may satisfy it silently.
      for (const primary of ['', '   ', '${MODEL}']) {
        let fetchCount = 0;
        const previous = globalThis.fetch;
        globalThis.fetch = (async () => { fetchCount += 1; return okResponse(); }) as typeof fetch;
        try {
          await assert.rejects(
            sendOpenAISingleTurnWithFallback({
              modelId: primary,
              systemPrompt: 's',
              userPrompt: 'u',
              allowedOrigins: FAKE_ALLOWED_ORIGINS,
              env: chainEnv,
              timeoutMs: 1000,
            }),
            /model not configured/,
          );
          assert.equal(fetchCount, 0, `primary '${primary}' sends no request`);
        } finally {
          globalThis.fetch = previous;
        }
      }

      // t0 timeout on the primary falls through to the next candidate.
      {
        const models: string[] = [];
        const previous = globalThis.fetch;
        globalThis.fetch = ((_input: unknown, init: { signal?: AbortSignal }) => {
          const attempt = models.push('attempt');
          // Only the primary stalls; the next candidate answers after its own
          // timeout window, proving each attempt is bounded independently.
          if (attempt > 1) return Promise.resolve(okResponse());
          return new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => reject(new Error('aborted')));
          });
        }) as typeof fetch;
        try {
          const result = await sendOpenAISingleTurnWithFallback({
            modelId: 'primary-model',
            systemPrompt: 's',
            userPrompt: 'u',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: chainEnv,
            timeoutMs: 120,
          });
          assert.equal(models.length, 2, 'aborted primary falls back');
          assert.equal(result.modelId, 'second-model');
        } finally {
          globalThis.fetch = previous;
        }
      }

      // Output shape compatibility (Vegan-scan-app parity): string content,
      // direct output_text items, and reasoning blocks alongside message.
      for (const [shapeName, payload] of [
        ['message string', { output: [{ type: 'message', content: 'string message' }] }],
        ['message text parts', { output: [{ type: 'reasoning', summary: [] }, { type: 'message', content: [{ type: 'text', text: 'text part' }] }] }],
        ['direct output_text', { output: [{ type: 'output_text', text: 'direct output text' }] }],
      ] as const) {
        const previous = globalThis.fetch;
        globalThis.fetch = (async () => new Response(JSON.stringify(payload), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })) as typeof fetch;
        try {
          const result = await sendOpenAISingleTurnWithFallback({
            modelId: 'primary-model',
            systemPrompt: 's',
            userPrompt: 'u',
            allowedOrigins: FAKE_ALLOWED_ORIGINS,
            env: chainEnv,
            timeoutMs: 1000,
          });
          assert.ok(result.content.length > 0, `${shapeName} extracts usable content`);
        } finally {
          globalThis.fetch = previous;
        }
      }
    }
  } finally {
    globalThis.fetch = originalFetch;
  }

  console.log('[PocketGuard scanner/OpenAI tests] All tests passed.');
}
