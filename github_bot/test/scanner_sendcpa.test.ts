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
  prioritizeFiles,
  truncateDiff,
} from '../src/review_diff';

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
  assert.ok(truncation.diff.startsWith('01234'));
  assert.ok(truncation.diff.includes('PocketGuard diff truncated: total 10 chars exceeded budget 5'));

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
    globalThis.fetch = (async () => new Response(JSON.stringify({
      output: [{ content: [{ type: 'output_text', text: 'review result' }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch;
    const result = await sendCpaSingleTurn({
      modelId: FAKE_MODEL_ID,
      systemPrompt: 'fake system prompt',
      userPrompt: 'fake user prompt',
      allowedOrigins: FAKE_ALLOWED_ORIGINS,
      env: cpaEnvironment,
      timeoutMs: 1000,
    });
    assert.deepEqual(result, { content: 'review result', modelId: FAKE_MODEL_ID });

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
  } finally {
    globalThis.fetch = originalFetch;
  }

  console.log('[PocketGuard scanner/CPA tests] All tests passed.');
}
