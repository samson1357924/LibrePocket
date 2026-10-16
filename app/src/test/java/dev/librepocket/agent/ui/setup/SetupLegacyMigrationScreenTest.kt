package dev.librepocket.agent.ui.setup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.Verdict
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1 migration prefill at the Compose layer: a cold-started upgrade with a
 * quarantined legacy custom row auto-routes to SETUP (first run), where the
 * screen recovers the saved address/model into the form exactly once — the
 * key field stays blank and a re-paste notice is shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SetupLegacyMigrationScreenTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
        { _, _ -> error("no provider needed") as LlmProvider }

    private fun legacyCustom(url: String) = EndpointConfig(
        providerId = "preset:custom",
        presetId = "custom",
        label = "自訂",
        baseUrl = url,
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "local",
        apiKeyRef = "provider_key/preset:custom",
    )

    private fun newVm(url: String): Pair<SetupViewModel, KeyVault> {
        val dir = Files.createTempDirectory("setup-legacy-migration-screen").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "legacy-migration-screen.preferences_pb") },
            ),
        )
        val vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            vault.putKey("preset:custom", "stage1-legacy-key-12345678".toCharArray())
            store.save(legacyCustom(url))
        }
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        return vm to vault
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun coldStartLegacyRowPrefillsAddressAndDemandsRepaste() {
        val url = "http://127.0.0.1:11434/v1"
        val (vm, _) = newVm(url)
        composeRule.setContent {
            SetupScreen(
                padding = PaddingValues(0.dp),
                isFirstRun = true,
                onSaved = { error("must not save without a key") },
                viewModel = vm,
            )
        }
        composeRule.waitForIdle()
        // The saved address/model recover into the form (never defaults).
        composeRule.onNodeWithText(url).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("舊版端點", substring = true).performScrollTo().assertIsDisplayed()
        // Key stays blank: the ViewModel form carries no pasted key.
        runBlocking { kotlinx.coroutines.withTimeout(10_000) {
            val form = vm.form.value
            assertEquals(url, form.baseUrl)
            assertEquals("custom", form.presetId)
            assertTrue(form.apiKey.isEmpty())
        } }
    }

    @Test
    fun transientFailureExposesRetryAndSameScreenRecovers() {
        val url = "http://127.0.0.1:11434/v1"
        val dir = Files.createTempDirectory("setup-legacy-migration-retry").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "legacy-migration-retry.preferences_pb") },
        )
        runBlocking {
            EndpointStore(raw).save(legacyCustom(url))
            // A teardown journal blocks the probe as RetryLater without
            // failing any read (the gate still settles NoEndpoint on the
            // legacy row, unaffected).
            raw.edit { prefs ->
                prefs[ENDPOINT_PENDING_DELETE_V1] = "junk-journal"
            }
        }
        val store = EndpointStore(raw)
        val vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            vault.putKey("preset:custom", "stage1-legacy-key-12345678".toCharArray())
        }
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        composeRule.setContent {
            SetupScreen(
                padding = PaddingValues(0.dp),
                isFirstRun = true,
                onSaved = { error("must not save without a key") },
                viewModel = vm,
            )
        }
        composeRule.waitForIdle()
        // Retry action is exposed while the form is still pristine.
        composeRule.onNodeWithText("重新載入舊版設定").performScrollTo().assertIsDisplayed()
        // Teardown reconciles (journal clears); the same screen's retry then
        // recovers the address while the key stays blank.
        runBlocking {
            raw.edit { prefs ->
                prefs.remove(ENDPOINT_PENDING_DELETE_V1)
            }
        }
        composeRule.onNodeWithText("重新載入舊版設定").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(url).performScrollTo().assertIsDisplayed()
        val form = vm.form.value
        assertEquals(url, form.baseUrl)
        assertEquals("custom", form.presetId)
        assertTrue(form.apiKey.isEmpty())
    }

    @Test
    fun nonFirstRunSetupDoesNotPrefillLegacy() {
        val url = "http://127.0.0.1:11434/v1"
        val (vm, _) = newVm(url)
        composeRule.setContent {
            SetupScreen(
                padding = PaddingValues(0.dp),
                isFirstRun = false,
                onSaved = {},
                viewModel = vm,
            )
        }
        composeRule.waitForIdle()
        // Edit navigation without an explicit prefill keeps defaults; the
        // legacy address must not leak into an unrelated edit session.
        val form = vm.form.value
        assertEquals(ProviderCatalog.OPENAI_ID, form.presetId)
    }
}

