package dev.librepocket.agent.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatScreen
import dev.librepocket.agent.assistant.VoiceHandoffProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.ChatViewModel
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.cancelAndJoinChatTestScopes
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.tool.DenyReason
import dev.librepocket.voice.VoiceStt
import dev.librepocket.voice.VoiceSwitchSnapshot
import dev.librepocket.voice.VoiceTools
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

private class RoutingFakeProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    @Volatile var streamCalls = 0

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls++
        emit(StreamEvent.Done("stop"))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainScreenAssistVoiceRoutingTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val vmStore = ViewModelStore()

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        VoiceHandoffProvider.resetForTest()
        registerRecognitionService()
    }

    @After
    fun tearDown() {
        try {
            vmStore.clear()
        } finally {
            try {
                cancelAndJoinChatTestScopes(scopes)
            } finally {
                try {
                    tmpDirs.forEach { it.deleteRecursively() }
                } finally {
                    VoiceHandoffProvider.resetForTest()
                    Dispatchers.resetMain()
                }
            }
        }
    }

    private fun registerRecognitionService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = ComponentName("com.example.stt", "com.example.stt.RecognizerActivity")
        val appInfo = ApplicationInfo().apply { packageName = component.packageName }
        val activityInfo = ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            applicationInfo = appInfo
            exported = true
        }
        val shadowPm = Shadows.shadowOf(app.packageManager)
        @Suppress("DEPRECATION")
        shadowPm.addOrUpdateActivity(activityInfo)
        @Suppress("DEPRECATION")
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
    }

    private fun sampleEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private class RecordingRegistry : ActivityResultRegistry() {
        val launchedIntents = mutableListOf<Intent>()

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            if (input is Intent) {
                launchedIntents += input
            }
        }
    }

    private class Harness(val chatViewModel: ChatViewModel) {
        val gate = MutableStateFlow<EndpointGate>(EndpointGate.NoEndpoint)
        val pending = MutableStateFlow(false)
        val assistVoiceRequested = MutableStateFlow(false)
        val pendingRetriggerToken = MutableStateFlow(0L)
        val voiceSwitchesLoaded = MutableStateFlow(false)
        val voiceSwitchLoadFailed = MutableStateFlow(false)
        val userSwitches = MutableStateFlow(
            mapOf(
                VoiceTools.SWITCH_INPUT to false,
                VoiceTools.SWITCH_OUTPUT to false,
                VoiceTools.SWITCH_OPENAI to false,
            ),
        )
        val viewedSessionId = MutableStateFlow<String?>(null)
        var consumedCount = 0
        var retriggerConsumedCount = 0
        val registry = RecordingRegistry()
        lateinit var nav: NavHostController
        lateinit var drawerState: DrawerState
        lateinit var uiScope: CoroutineScope
        val recreationEpoch = MutableStateFlow(0)

        fun recreate() {
            recreationEpoch.value++
        }

        fun emitVoiceSnapshot(snapshot: VoiceSwitchSnapshot) {
            voiceSwitchesLoaded.value = snapshot.loaded || snapshot.loadFailed
            voiceSwitchLoadFailed.value = snapshot.loadFailed
            userSwitches.value = if (snapshot.loaded && !snapshot.loadFailed) {
                snapshot.switches
            } else {
                mapOf(
                    VoiceTools.SWITCH_INPUT to false,
                    VoiceTools.SWITCH_OUTPUT to false,
                    VoiceTools.SWITCH_OPENAI to false,
                )
            }
        }
    }

    private fun newChatViewModel(provider: RoutingFakeProvider): ChatViewModel {
        val dir = Files.createTempDirectory("main-assist-routing").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "routing.preferences_pb") },
            ),
        )
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(sampleEndpoint())
        }
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> provider },
        )
        val vm = ChatViewModel(store, factory, policy)
        vmStore.put("routing-vm-${vmStore.keys().size}", vm)
        return vm
    }

    @Composable
    private fun Harness.Content() {
        val epoch by recreationEpoch.collectAsState()
        key(epoch) {
            nav = rememberNavController()
            drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            uiScope = rememberCoroutineScope()
            val g by gate.collectAsState()
            val p by pending.collectAsState()
            val req by assistVoiceRequested.collectAsState()
            val pendingRetrigger by pendingRetriggerToken.collectAsState()
            val loaded by voiceSwitchesLoaded.collectAsState()
            val loadFailed by voiceSwitchLoadFailed.collectAsState()
            val switches by userSwitches.collectAsState()
            val viewed by viewedSessionId.collectAsState()
            val backStack by nav.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            val hasEndpoint = g is EndpointGate.Ready
            val navLoading = startupEffectiveLoading(g is EndpointGate.Loading, p)
            val deliverRetrigger = pendingRetrigger != 0L &&
                currentRoute == Routes.CHAT &&
                hasEndpoint &&
                !navLoading &&
                viewed == null &&
                drawerState.isClosed &&
                drawerState.targetValue == DrawerValue.Closed
            val voiceRetriggerToken = if (deliverRetrigger) pendingRetrigger else 0L
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }

            AssistVoiceCoordinator(
                assistVoiceRequested = req,
                hasEndpoint = hasEndpoint,
                navLoading = navLoading,
                currentRoute = currentRoute,
                navController = nav,
                drawerState = drawerState,
                viewedSessionId = viewed,
                onClearViewedSession = { viewedSessionId.value = null },
                onAssistVoiceConsumed = {
                    consumedCount++
                    assistVoiceRequested.value = false
                },
            )

            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                StartupGateHost(
                    gate = g,
                    startupReconcilePending = p,
                    errorCode = null,
                    logoutInFlight = false,
                    navController = nav,
                    appName = "TestApp",
                    drawerState = drawerState,
                    chatContent = { padding ->
                        ChatScreen(
                            padding = padding,
                            viewModel = chatViewModel,
                            voiceInputRequested = shouldDeliverVoiceToChat(
                                assistVoiceRequested = req,
                                currentRoute = currentRoute,
                                hasEndpoint = hasEndpoint,
                                navLoading = navLoading,
                                viewedSessionId = viewed,
                                drawerState = drawerState,
                            ),
                            onVoiceInputConsumed = {
                                consumedCount++
                                assistVoiceRequested.value = false
                            },
                            voiceRetriggerToken = voiceRetriggerToken,
                            onVoiceRetriggerConsumed = {
                                retriggerConsumedCount++
                                pendingRetriggerToken.value = 0L
                                true
                            },
                            voiceSwitchesLoaded = loaded,
                            voiceSwitchLoadFailed = loadFailed,
                            userSwitches = switches,
                        )
                    },
                    settingsContent = { padding ->
                        Text("SETTINGS_BODY", modifier = Modifier.padding(padding))
                    },
                    setupContent = { padding, _ ->
                        Text("SETUP_BODY", modifier = Modifier.padding(padding))
                    },
                )
            }
        }
    }

    private fun render(h: Harness) {
        composeRule.setContent { h.Content() }
        composeRule.waitForIdle()
    }

    private fun awaitRoute(h: Harness, route: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            h.nav.currentDestination?.route == route
        }
    }

    @Test
    fun coldStart_noEndpoint_consumesAndNavigatesToSetup_neverLaunchesSttEvenAfterEndpointReady() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.NoEndpoint
        h.assistVoiceRequested.value = true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Later setup completes and returns to CHAT -> STT must NOT suddenly pop up.
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
            h.nav.navigate(Routes.CHAT) {
                popUpTo(Routes.SETUP) { inclusive = true }
                launchSingleTop = true
            }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun warmStart_fromSettingsWithOpenDrawerAndTranscriptOverlay_navigatesToChatClosesOverlaysAndLaunchesSttOnce() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = false

        render(h)
        awaitRoute(h, Routes.CHAT)

        // Move to SETTINGS with drawer open and read-only transcript overlay active.
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
            h.viewedSessionId.value = "imported-session-1"
            h.uiScope.launch { h.drawerState.open() }
        }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.waitUntil(timeoutMillis = 5_000) { h.drawerState.isOpen }

        // Deliver warm assist voice request while switches are still loading.
        composeRule.runOnIdle { h.assistVoiceRequested.value = true }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            h.drawerState.isClosed && h.drawerState.targetValue == DrawerValue.Closed
        }
        composeRule.waitForIdle()

        assertNull(h.viewedSessionId.value)
        assertTrue(h.drawerState.isClosed)
        assertEquals(0, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // VoiceSwitchStore finishes loading with voice input enabled -> launches STT once.
        composeRule.runOnIdle {
            h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
            h.voiceSwitchesLoaded.value = true
        }
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun delayedVoiceSwitchStore_waitsForLoadedBeforeConsumingOrLaunching() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.assistVoiceRequested.value = true
        // Loading snapshot (fail-closed all-false map while voiceSwitchesLoaded = false).
        h.voiceSwitchesLoaded.value = false
        h.userSwitches.value = mapOf(
            VoiceTools.SWITCH_INPUT to false,
            VoiceTools.SWITCH_OUTPUT to false,
            VoiceTools.SWITCH_OPENAI to false,
        )

        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        // Must NOT consume or fail with USER_DISABLED during LOADING.
        assertEquals(0, h.consumedCount)
        assertTrue(h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertCountEquals(0)

        // Switches finish loading with SWITCH_INPUT = true -> launches STT once.
        composeRule.runOnIdle {
            h.userSwitches.value = mapOf(
                VoiceTools.SWITCH_INPUT to true,
                VoiceTools.SWITCH_OUTPUT to true,
                VoiceTools.SWITCH_OPENAI to false,
            )
            h.voiceSwitchesLoaded.value = true
        }
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertCountEquals(0)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun coldStart_navLoading_holdsRequestUntilSettledToReady_thenLaunchesSttOnce() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Loading
        h.pending.value = true
        h.assistVoiceRequested.value = true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)

        // While gate is Loading and reconcile is pending (navLoading == true),
        // the request must be held without consuming or launching STT.
        assertEquals(0, h.consumedCount)
        assertTrue(h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Even after gate resolves to Ready, startupReconcilePending == true keeps navLoading == true.
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
        }
        composeRule.waitForIdle()
        assertEquals(0, h.consumedCount)
        assertTrue(h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Once reconcile settles (navLoading == false), it routes to CHAT and launches STT once.
        composeRule.runOnIdle {
            h.pending.value = false
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun coldStart_navLoading_holdsRequestUntilSettledToNoEndpoint_thenConsumesWithoutLaunch() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Loading
        h.pending.value = false
        h.assistVoiceRequested.value = true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)

        assertEquals(0, h.consumedCount)
        assertTrue(h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())

        composeRule.runOnIdle {
            h.gate.value = EndpointGate.NoEndpoint
        }
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(Routes.SETUP, h.nav.currentDestination?.route)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun delayedVoiceSwitchStore_whenDisabled_degradesToUserDisabledAndConsumesOnceWithoutReplay() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.pending.value = false
        h.assistVoiceRequested.value = true
        h.emitVoiceSnapshot(VoiceSwitchSnapshot.LOADING)

        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(0, h.consumedCount)
        assertTrue(h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertCountEquals(0)

        // Emit snapshot with loaded = true, switches = mapOf(VoiceTools.SWITCH_INPUT to false)
        composeRule.runOnIdle {
            h.emitVoiceSnapshot(
                VoiceSwitchSnapshot(
                    switches = mapOf(VoiceTools.SWITCH_INPUT to false),
                    loaded = true,
                    loadFailed = false,
                ),
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertIsDisplayed()

        // Trigger recomposition / flip switch; assert no STT launch occurs and consumedCount stays 1.
        composeRule.runOnIdle {
            h.emitVoiceSnapshot(
                VoiceSwitchSnapshot(
                    switches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    loaded = true,
                    loadFailed = false,
                ),
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun delayedVoiceSwitchStore_whenLoadFailed_degradesToUserDisabledAndConsumesOnceWithoutReplay() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.pending.value = false
        h.assistVoiceRequested.value = true
        h.emitVoiceSnapshot(VoiceSwitchSnapshot.LOADING)

        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(0, h.consumedCount)
        assertTrue(h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertCountEquals(0)

        // Emit snapshot with loaded = true, loadFailed = true, switches = mapOf(VoiceTools.SWITCH_INPUT to false)
        composeRule.runOnIdle {
            h.emitVoiceSnapshot(
                VoiceSwitchSnapshot(
                    switches = mapOf(VoiceTools.SWITCH_INPUT to false),
                    loaded = true,
                    loadFailed = true,
                ),
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertIsDisplayed()

        // Trigger recomposition / flip switch; assert no STT launch occurs and consumedCount stays 1.
        composeRule.runOnIdle {
            h.emitVoiceSnapshot(
                VoiceSwitchSnapshot(
                    switches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    loaded = true,
                    loadFailed = false,
                ),
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun settingsRoute_flaggedOnNewIntent_routesToChatAndSurvivesRecreationOnce() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.pending.value = false
        h.assistVoiceRequested.value = false
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
        }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.waitForIdle()

        assertEquals(0, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Deliver voice request (simulating onNewIntent: harness.assistVoiceRequested.value = true)
        composeRule.runOnIdle {
            h.assistVoiceRequested.value = true
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertTrue(h.drawerState.isClosed)
        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)

        // Simulate state restoration / recreation where consumed = true (assistVoiceRequested = false), verify no re-trigger.
        composeRule.runOnIdle {
            h.recreate()
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun setupRoute_flaggedOnNewIntent_clearsStaleFlagWithoutLeakingAcrossRecreation() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.NoEndpoint
        h.pending.value = false
        h.assistVoiceRequested.value = false
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        assertEquals(0, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Deliver voice request (simulating onNewIntent while on Setup route)
        composeRule.runOnIdle {
            h.assistVoiceRequested.value = true
        }
        composeRule.waitForIdle()

        // Coordinator clears stale flag immediately
        assertEquals(1, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Settle gate to EndpointGate.Ready, assert STT is NOT launched
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
            h.nav.navigate(Routes.CHAT) {
                popUpTo(Routes.SETUP) { inclusive = true }
                launchSingleTop = true
            }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, provider.streamCalls)

        // Simulate state restoration / recreation where assistVoiceRequested = false
        composeRule.runOnIdle {
            h.recreate()
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.consumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retriggerToken_deliveredOnlyWhenChatSafe_thenConsumedOnce() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        // Not deliverable on non-CHAT route.
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
        }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.runOnIdle { h.pendingRetriggerToken.value = 11L }
        composeRule.waitForIdle()
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Not deliverable while transcript overlay is active (even on CHAT).
        composeRule.runOnIdle {
            h.nav.navigate(Routes.CHAT)
            h.viewedSessionId.value = "readonly-1"
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Deliver once when CHAT-safe boundary is satisfied.
        composeRule.runOnIdle { h.viewedSessionId.value = null }
        composeRule.waitForIdle()
        assertEquals(1, h.retriggerConsumedCount)
        assertEquals(0L, h.pendingRetriggerToken.value)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertEquals(0, provider.streamCalls)
    }
}
