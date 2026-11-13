package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.tool.WebSearchLocal
import dev.librepocket.web.WebSearchSettings
import dev.librepocket.web.WebSwitchStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebSearchSettingsIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val vmStore = ViewModelStore()

    private class ControllablePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        val errorChannel = Channel<IOException>(Channel.BUFFERED)
        private val mutex = Mutex()

        fun injectError(error: IOException) {
            errorChannel.trySend(error)
        }

        override val data: Flow<Preferences> = flow {
            merge(
                state.map { Result.success(it) },
                errorChannel.receiveAsFlow().map { Result.failure<Preferences>(it) },
            ).collect { res ->
                emit(res.getOrThrow())
            }
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return mutex.withLock {
                val next = transform(state.value)
                state.value = next
                next
            }
        }
    }

    private lateinit var fakeDs: ControllablePreferencesDataStore
    private lateinit var store: WebSwitchStore
    private lateinit var vm: WebSearchViewModel

    private fun readyGate(): EndpointGate.Ready = EndpointGate.Ready(
        EndpointConfig(
            providerId = "preset:openai",
            presetId = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = "provider_key/preset:openai",
        ),
    )

    @Before
    fun setUp() {
        fakeDs = ControllablePreferencesDataStore()
        store = WebSwitchStore(fakeDs, Dispatchers.IO)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WebSearchViewModel(store, Dispatchers.IO) as T
            }
        }
        vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
    }

    @After
    fun tearDown() {
        vmStore.clear()
    }

    private fun setSettingsContent() {
        composeRule.setContent {
            val webState by vm.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState,
                onWebMasterSwitch = vm::onMasterSwitch,
                onWebDdgsSwitch = vm::onDdgsSwitch,
                onWebSearxngUrlChange = vm::onSearxngUrlChange,
                onWebProbe = vm::onProbe,
                onWebConsentConfirm = vm::onConsentConfirm,
                onWebConsentDismiss = vm::onConsentDismiss,
                onWebRetryLoad = vm::retryLoad,
                onWebResetSettings = vm::onResetSettings,
            )
        }
    }

    @Test
    fun initialLoad_showsDefaultsAndDisabledResetButton() {
        setSettingsContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.isLoading
        }

        composeRule.onNodeWithTag("webMasterSwitch").performScrollTo().assertIsDisplayed().assertIsOff()
        composeRule.onNodeWithTag("webDdgsSwitch").performScrollTo().assertIsDisplayed().assertIsOff()
        composeRule.onNodeWithTag("webSearxngStatus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("狀態：缺 URL", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("webResetButton").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("webScaffoldNotice").performScrollTo().assertIsDisplayed()

        val prefs = fakeDs.state.value
        assertFalse(prefs[booleanPreferencesKey(WebSearchLocal.SWITCH)] ?: false)
        assertFalse(prefs[booleanPreferencesKey(WebSearchSettings.DDGS_SWITCH)] ?: false)
        assertFalse(prefs[booleanPreferencesKey(WebSearchSettings.CONSENT_ACK_KEY)] ?: false)
        assertEquals("", prefs[stringPreferencesKey(WebSearchSettings.SEARXNG_URL_KEY)] ?: "")
    }

    @Test
    fun urlTyping_updatesUiAndPersistsToDataStore() {
        setSettingsContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.isLoading
        }

        composeRule.onNodeWithTag("webSearxngUrl").performScrollTo().performTextInput("https://search.example")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDs.state.value[stringPreferencesKey(WebSearchSettings.SEARXNG_URL_KEY)] == "https://search.example"
        }

        composeRule.onNodeWithTag("webSearxngStatus").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("webSearxngStatus").assertTextContains(
            WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.AVAILABLE),
            substring = true,
        )
        composeRule.onNodeWithTag("webResetButton").performScrollTo().assertIsEnabled()
    }

    @Test
    fun togglingMasterSwitch_opensConsentDialog_andConfirmingAtomicallyActivatesMaster() {
        setSettingsContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.isLoading
        }

        composeRule.onNodeWithTag("webMasterSwitch").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("webConsentTitle").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("webConsentTitle").assertIsDisplayed()
        composeRule.onNodeWithTag("webConsentText").assertIsDisplayed()
        assertFalse(fakeDs.state.value[booleanPreferencesKey(WebSearchLocal.SWITCH)] ?: false)

        composeRule.onNodeWithTag("webConsentConfirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDs.state.value[booleanPreferencesKey(WebSearchLocal.SWITCH)] == true &&
                fakeDs.state.value[booleanPreferencesKey(WebSearchSettings.CONSENT_ACK_KEY)] == true
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("webConsentTitle").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("webMasterSwitch").performScrollTo().assertIsOn()
    }

    @Test
    fun togglingDdgsSwitch_persistsToDataStore() {
        setSettingsContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.isLoading
        }

        composeRule.onNodeWithTag("webDdgsSwitch").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDs.state.value[booleanPreferencesKey(WebSearchSettings.DDGS_SWITCH)] == true
        }

        composeRule.onNodeWithTag("webDdgsSwitch").performScrollTo().assertIsOn()
    }

    @Test
    fun tappingResetButton_resetsAllKeysAtomicallyToDefaults() {
        setSettingsContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.isLoading
        }

        composeRule.onNodeWithTag("webSearxngUrl").performScrollTo().performTextInput("https://search.example")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDs.state.value[stringPreferencesKey(WebSearchSettings.SEARXNG_URL_KEY)] == "https://search.example"
        }

        composeRule.onNodeWithTag("webResetButton").performScrollTo().assertIsEnabled().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            val prefs = fakeDs.state.value
            (prefs[booleanPreferencesKey(WebSearchLocal.SWITCH)] ?: false) == false &&
                (prefs[booleanPreferencesKey(WebSearchSettings.DDGS_SWITCH)] ?: false) == false &&
                (prefs[booleanPreferencesKey(WebSearchSettings.CONSENT_ACK_KEY)] ?: false) == false &&
                (prefs[stringPreferencesKey(WebSearchSettings.SEARXNG_URL_KEY)] ?: "").isEmpty()
        }

        composeRule.onNodeWithTag("webMasterSwitch").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("webDdgsSwitch").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("webResetButton").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun injectingReadFailure_displaysLoadFailedAndRetryButton_clickingRetryRecovers() {
        setSettingsContent()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.isLoading
        }

        fakeDs.injectError(IOException("disk read failed"))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("webLoadFailed").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("webLoadFailed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("webRetryLoadButton").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("webRetryLoadButton").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("webLoadFailed").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("webMasterSwitch").performScrollTo().assertIsDisplayed().assertIsOff()
    }
}
