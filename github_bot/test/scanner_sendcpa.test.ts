import assert from 'node:assert/strict';
import {
  DeterministicScanner,
  securityLabelsFor,
} from '../src/deterministic_scanner';
import {
  resolveCpaConfig,
  resolveRoleModel,
  sendCpaSingleTurn,
} from '../src/send_cpa';
import {
  coverageSummary,
  filterReviewDiffFiles,
  MAX_DIFF_LENGTH,
  prioritizeFiles,
  truncateDiff,
} from '../src/review_diff';
import { buildReviewDiff } from '../src/github_runner';
import { orchestrateReview } from '../src/orchestrator';

const FAKE_CPA_BASE_URL = ['https:', '', 'pocketguard-cpa.test', 'v1'].join('/');
const FAKE_ALLOWED_ORIGINS = [new URL(FAKE_CPA_BASE_URL).origin];
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

export async function runScannerSendCpaTests(): Promise<void> {
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
    'app/src/main/java/example/First.kt': 'A'.repeat(119850),
    'app/src/main/java/example/Second.kt': 'B'.repeat(1000),
  };
  const assembled = buildReviewDiff({
    runGit: (args) => boundaryDiffs[args[args.length - 1]] ?? '',
  }, 'a'.repeat(40), 'b'.repeat(40), Object.keys(boundaryDiffs));
  assert.equal(assembled.diff.length, MAX_DIFF_LENGTH);
  assert.ok(assembled.diff.includes('PocketGuard diff truncated'));
  assert.deepEqual(assembled.coverage.truncatedFiles, ['app/src/main/java/example/Second.kt']);

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
    () => resolveCpaConfig({ CPA_BASE_URL: FAKE_CPA_BASE_URL, CPA_API_KEY: FAKE_SECRET_FIXTURES.placeholderKey }, FAKE_ALLOWED_ORIGINS),
    /CPA configuration/,
  );
  assert.throws(
    () => resolveCpaConfig({ CPA_BASE_URL: FAKE_CPA_BASE_URL.replace('https:', 'http:'), CPA_API_KEY: 'fake-key' }, FAKE_ALLOWED_ORIGINS),
    /CPA configuration/,
  );
  const userInfoUrl = new URL(FAKE_CPA_BASE_URL);
  userInfoUrl.username = 'fake-user';
  userInfoUrl.password = 'fake-pass';
  assert.throws(
    () => resolveCpaConfig({ CPA_BASE_URL: userInfoUrl.toString(), CPA_API_KEY: 'fake-key' }, FAKE_ALLOWED_ORIGINS),
    /CPA configuration/,
  );
  assert.throws(
    () => resolveCpaConfig({ CPA_BASE_URL: FAKE_CPA_BASE_URL, CPA_API_KEY: 'fake-key' }, []),
    /CPA configuration/,
  );
  for (const suffix of ['?', '#']) {
    assert.throws(
      () => resolveCpaConfig({ CPA_BASE_URL: `${FAKE_CPA_BASE_URL}${suffix}`, CPA_API_KEY: 'fake-key' }, FAKE_ALLOWED_ORIGINS),
      /CPA configuration/,
    );
  }
  assert.throws(() => resolveRoleModel('android_sec', {}), /model not configured/);

  const originalFetch = globalThis.fetch;
  const cpaEnvironment = {
    CPA_BASE_URL: FAKE_CPA_BASE_URL,
    CPA_API_KEY: 'fake-api-key',
  };
  try {
    let ordinaryRedirectMode: RequestInit['redirect'] | undefined;
    globalThis.fetch = (async (_input, init) => {
      ordinaryRedirectMode = init?.redirect;
      return new Response(JSON.stringify({
        output: [{ content: [{ type: 'output_text', text: 'review result' }] }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    const result = await sendCpaSingleTurn({
      modelId: FAKE_MODEL_ID,
      systemPrompt: 'fake system prompt',
      userPrompt: 'fake user prompt',
      allowedOrigins: FAKE_ALLOWED_ORIGINS,
      env: cpaEnvironment,
      timeoutMs: 1000,
    });
    assert.deepEqual(result, { content: 'review result', modelId: FAKE_MODEL_ID });
    assert.equal(ordinaryRedirectMode, 'error');

    const redirectTarget = 'https://redirect-target.test/receive';
    for (const status of [307, 308]) {
      let targetRequests = 0;
      const forwardedBodies: string[] = [];
      const initialBodies: string[] = [];
      const invalidRedirectModes: unknown[] = [];
      const unexpectedTargets: string[] = [];
      globalThis.fetch = (async (input, init) => {
        const url = new URL(String(input));
        if (url.origin === new URL(FAKE_CPA_BASE_URL).origin) {
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
        sendCpaSingleTurn({
          modelId: FAKE_MODEL_ID,
          systemPrompt: 'fake system prompt',
          userPrompt: 'fake user prompt',
          allowedOrigins: FAKE_ALLOWED_ORIGINS,
          env: cpaEnvironment,
          timeoutMs: 1000,
        }),
        /CPA request failed/,
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
      sendCpaSingleTurn({
        modelId: FAKE_MODEL_ID,
        systemPrompt: 'fake system prompt',
        userPrompt: 'fake user prompt',
        allowedOrigins: FAKE_ALLOWED_ORIGINS,
        env: cpaEnvironment,
        timeoutMs: 1000,
      }),
      (error: unknown) => {
        assert.ok(error instanceof Error);
        assert.equal(error.message, 'CPA request failed');
        assert.equal(error.message.includes('FAKE_RESPONSE_BODY_SECRET'), false);
        assert.equal((error as Error & { statusCode?: number }).statusCode, 503);
        return true;
      },
    );

    globalThis.fetch = (async () => new Response(JSON.stringify({ output: [] }), { status: 200 })) as typeof fetch;
    await assert.rejects(
      sendCpaSingleTurn({
        modelId: FAKE_MODEL_ID,
        systemPrompt: 'fake system prompt',
        userPrompt: 'fake user prompt',
        allowedOrigins: FAKE_ALLOWED_ORIGINS,
        env: cpaEnvironment,
        timeoutMs: 1000,
      }),
      /CPA response empty/,
    );

    const orchestratorEnv = {
      ...cpaEnvironment,
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
  } finally {
    globalThis.fetch = originalFetch;
  }

  console.log('[PocketGuard scanner/CPA tests] All tests passed.');
}
