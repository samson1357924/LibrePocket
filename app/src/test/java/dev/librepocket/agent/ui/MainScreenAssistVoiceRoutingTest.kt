package dev.librepocket.agent.ui

import android.app.Activity
import android.app.Application
import androidx.activity.result.ActivityResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.SystemClock
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
import dev.librepocket.agent.assistant.AssistantVoiceHandoff
import dev.librepocket.agent.assistant.PendingVoiceRetrigger
import dev.librepocket.agent.assistant.VoiceHandoffContract
import dev.librepocket.agent.assistant.VoiceHandoffProvider
import dev.librepocket.agent.assistant.VoiceTaskIdentity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

// Harness for MainScreen voice-routing integration tests. Each piece shares its production
// counterpart's source of truth rather than mirroring it:
//   - delivery gate + token derivation: shouldDeliverVoiceRetrigger / voiceRetriggerTokenFor
//     imports (same functions MainScreen uses),
//   - pending retrigger source: AssistantVoiceHandoff.pendingFlow() (real provider, not a harness
//     stand-in MutableStateFlow),
//   - onVoiceRetriggerConsumed: consumeVoiceRetrigger (production function, the exact code path
//     MainScreen uses; the harness short-circuits only to simulate a non-committing consume failure),
//   - stale-clear effect: RetriggerStaleClearEffect (production composable, the exact code path
//     MainScreen uses),
//   - AssistVoiceCoordinator / AssistVoiceRetriggerCoordinator / StartupGateHost: shared production
//     composables,
//   - chat tree: production ChatScreen with the same onVoiceRetriggerConsumed/onVoiceInputConsumed seams.
// Anything not listed here (e.g. RecordingRegistry instead of SavedStateRegistry, fake Drawer/Nav
// hosts) is test-only scaffolding with no production counterpart.
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
        var lastRequestCode = -1
        val launchedIntents = mutableListOf<Intent>()
        val launchedRequestCodes = mutableListOf<Int>()

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastRequestCode = requestCode
            launchedRequestCodes += requestCode
            if (input is Intent) {
                launchedIntents += input
            }
        }
    }

    private class Harness(val chatViewModel: ChatViewModel) {
        val appContext: Context
            get() = ApplicationProvider.getApplicationContext<Context>()
        val gate = MutableStateFlow<EndpointGate>(EndpointGate.NoEndpoint)
        val pending = MutableStateFlow(false)
        val assistVoiceRequested = MutableStateFlow(false)

        // Trusted voice-task identity for this activity instance; delivery is fail-closed without
        // a match. Override per-test to exercise identity gates.
        var voiceTaskIdentity: VoiceTaskIdentity? = VoiceTaskIdentity(taskId = 10, sessionInstanceId = 7L)
        var consumeResult: Boolean = true
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

        /**
         * Publishes a retrigger entry into the REAL provider (mirrors the :voice-process session
         * publishing via AssistantVoiceHandoff.requestRetrigger). Returns the minted token.
         */
        fun publishRetrigger(
            targetTaskId: Int = 10,
            sessionInstanceId: Long = 7L,
            issuedElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        ): Long = VoiceHandoffProvider.nextToken().also { token ->
            VoiceHandoffProvider.publish(
                PendingVoiceRetrigger(
                    token = token,
                    targetTaskId = targetTaskId,
                    sessionInstanceId = sessionInstanceId,
                    issuedElapsedRealtimeMs = issuedElapsedRealtimeMs,
                ),
            )
        }

        /** Assertion helper: the provider's currently pending entry, or null. */
        fun providerPending(): PendingVoiceRetrigger? = VoiceHandoffProvider.pending.value

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
            val pendingRetrigger by AssistantVoiceHandoff.pendingFlow().collectAsState(initial = null)
            val loaded by voiceSwitchesLoaded.collectAsState()
            val loadFailed by voiceSwitchLoadFailed.collectAsState()
            val switches by userSwitches.collectAsState()
            val viewed by viewedSessionId.collectAsState()
            val backStack by nav.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            val hasEndpoint = g is EndpointGate.Ready
            val navLoading = startupEffectiveLoading(g is EndpointGate.Loading, p)
            val voiceRetriggerToken = voiceRetriggerTokenFor(
                pending = pendingRetrigger,
                identity = voiceTaskIdentity,
                currentRoute = currentRoute,
                hasEndpoint = hasEndpoint,
                navLoading = navLoading,
                viewedSessionId = viewed,
                drawerState = drawerState,
            )
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

            // Production retrigger convergence twin of AssistVoiceCoordinator (same code path MainScreen
            // uses): identity-gated; navigates to CHAT / clears overlay / closes drawer, or drops the
            // token without an endpoint. Never launches STT.
            AssistVoiceRetriggerCoordinator(
                pending = pendingRetrigger,
                identity = voiceTaskIdentity,
                hasEndpoint = hasEndpoint,
                navLoading = navLoading,
                currentRoute = currentRoute,
                navController = nav,
                drawerState = drawerState,
                viewedSessionId = viewed,
                onClearViewedSession = { viewedSessionId.value = null },
                context = appContext,
            )

            // Production stale-clear effect (single source of truth): expired entries converge
            // regardless of identity; non-expired entries stay identity-gated + token-matched.
            RetriggerStaleClearEffect(
                pending = pendingRetrigger,
                identity = voiceTaskIdentity,
                navLoading = navLoading,
                hasEndpoint = hasEndpoint,
                context = appContext,
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
                            onVoiceRetriggerConsumed = { token ->
                                retriggerConsumedCount++
                                if (!consumeResult) {
                                    // Non-committing rejection (pins "no launch on rejected consume");
                                    // note provider-side consume() drops the entry on TTL-rejection —
                                    // a different path, pinned by the provider tests.
                                    false
                                } else {
                                    consumeVoiceRetrigger(token, pendingRetrigger, voiceTaskIdentity, appContext)
                                }
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
        composeRule.waitUntil(timeoutMillis = 10_000) {
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
        composeRule.waitUntil(timeoutMillis = 10_000) { h.drawerState.isOpen }

        // Deliver warm assist voice request while switches are still loading.
        composeRule.runOnIdle { h.assistVoiceRequested.value = true }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitUntil(timeoutMillis = 10_000) {
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
    fun retriggerToken_convergesFromSettingsAndOverlay_thenDeliveredOnce() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        // (a) Retrigger gesture published while on SETTINGS converges to CHAT (like the initial
        // voice request): navigates to CHAT, STT launched once, consumed once, provider empty.
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
        }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()
        assertEquals(1, h.retriggerConsumedCount)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)

        // Settle the first recognition: under the single-flight gate a fresh retrigger published
        // while an STT launch is in flight stays held instead of stacking a second recognizer,
        // so (b) below exercises the admitted-on-settle path.
        composeRule.runOnIdle {
            h.registry.dispatchResult(
                h.registry.lastRequestCode,
                ActivityResult(Activity.RESULT_CANCELED, null),
            )
        }
        composeRule.waitForIdle()

        // (b) Fresh publish on CHAT while the read-only overlay is active: the coordinator clears
        // the overlay in the same run; the boundary is satisfied and STT launches exactly once more
        // (gate is free after the settle above).
        composeRule.runOnIdle {
            h.viewedSessionId.value = "readonly-2"
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()
        assertNull(h.viewedSessionId.value)
        assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
        assertEquals(2, h.retriggerConsumedCount)
        assertEquals(2, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.last().action)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retriggerToken_publishedOnSettings_navigatesToChat_launchesSttOnce() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
        }
        awaitRoute(h, Routes.SETTINGS)

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.retriggerConsumedCount)
        // The initial-voice flag is untouched by the retrigger path.
        assertEquals(0, h.consumedCount)
        assertEquals(false, h.assistVoiceRequested.value)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retriggerToken_publishedWithDrawerOpen_closesDrawerAndLaunches() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle {
            h.uiScope.launch { h.drawerState.open() }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { h.drawerState.isOpen }

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            h.drawerState.isClosed && h.drawerState.targetValue == DrawerValue.Closed
        }
        composeRule.waitForIdle()

        assertTrue(h.drawerState.isClosed)
        assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertEquals(1, h.retriggerConsumedCount)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retriggerToken_publishedWithReadonlyOverlay_clearsOverlayAndLaunches() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle {
            h.viewedSessionId.value = "ro-1"
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        assertNull(h.viewedSessionId.value)
        assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.last().action)
        assertEquals(1, h.retriggerConsumedCount)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retriggerToken_publishedWithNoEndpoint_clearsTokenWithoutLaunch() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.NoEndpoint
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        // Token-matched clear without navigation or launch (StartupGateHost forces SETUP anyway).
        assertNull(h.providerPending())
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(Routes.SETUP, h.nav.currentDestination?.route)

        // Later the endpoint becomes ready and the user returns to CHAT: no retroactive STT for the
        // dropped token.
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
            h.nav.navigate(Routes.CHAT) {
                popUpTo(Routes.SETUP) { inclusive = true }
                launchSingleTop = true
            }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, h.retriggerConsumedCount)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retrigger_navLoading_holdsTokenThenConvergesAndLaunches() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Loading
        h.pending.value = true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        // Held: no launch, no consume, token preserved.
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.providerPending() != null)

        // Startup reconcile settles but the gate is still Loading: still holds.
        composeRule.runOnIdle {
            h.pending.value = false
        }
        composeRule.waitForIdle()
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.providerPending() != null)

        // Gate resolves to Ready: NavHost composes at CHAT, the held token converges and delivers.
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.retriggerConsumedCount)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retrigger_navLoadingGateReady_pendingStillTrue_holdsUntilSettled() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        // Gate already Ready, but the startup reconcile is still pending: navLoading stays true
        // even though the gate-driven arm is settled (gate-driven vs pending-driven navLoading).
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.pending.value = true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        // Held: no delivery, no consume; the token is preserved.
        assertTrue(h.providerPending() != null)
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Reconcile settles (navLoading == false): the converge path delivers exactly once.
        composeRule.runOnIdle {
            h.pending.value = false
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(1, h.retriggerConsumedCount)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retrigger_loadingSettlesToNoEndpoint_clearsToken_noRetroactiveDelivery() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Loading
        h.pending.value = true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        // Held while navLoading; the entry is not delivered and not cleared yet.
        assertTrue(h.providerPending() != null)
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Startup reconcile settles to NoEndpoint: the owning instance drops the token, without
        // navigation, delivery, or consume — and StartupGateHost forces SETUP.
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.NoEndpoint
            h.pending.value = false
        }
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        assertNull(h.providerPending())
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(Routes.SETUP, h.nav.currentDestination?.route)

        // Later the endpoint becomes ready and the user returns to CHAT: the dropped token is
        // never delivered retroactively.
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
            h.nav.navigate(Routes.CHAT) {
                popUpTo(Routes.SETUP) { inclusive = true }
                launchSingleTop = true
            }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, h.retriggerConsumedCount)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retrigger_publishSupersedes_holdKeepsNewerToken() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.NoEndpoint
        // Startup reconcile still pending: navLoading holds, so no stale/NoEndpoint clear may
        // touch either token while both are in flight.
        h.pending.value = true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        composeRule.waitForIdle()

        var a = 0L
        var b = 0L
        composeRule.runOnIdle {
            a = h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
            // Token A is replaced in the provider's single slot by the newer gesture.
            b = h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        // A is gone (superseded at publish time); B survives. This pins publish-supersedes-publish
        // plus the navLoading hold — it does NOT exercise the token-matched clear (under hold no
        // clear effect runs). Token-matched clearing is pinned at provider level in
        // VoiceHandoffProviderTest.clearMatching_withStaleToken_keepsNewerEntry.
        // (The settled NoEndpoint behavior — fresh entries are dropped once navLoading falls — is
        // pinned by retriggerToken_publishedWithNoEndpoint_clearsTokenWithoutLaunch.)
        assertEquals(b, h.providerPending()?.token)
        assertTrue(a != b)
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
    }

    @Test
    fun retriggerToken_publishedOnSettings_withDrawerOpen_convergesChatThenClosesDrawerAndLaunches() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        // SETTINGS with the drawer open, then the retrigger gesture.
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
            h.uiScope.launch { h.drawerState.open() }
        }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.waitUntil(timeoutMillis = 10_000) { h.drawerState.isOpen }

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            h.drawerState.isClosed && h.drawerState.targetValue == DrawerValue.Closed
        }
        composeRule.waitForIdle()

        assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
        assertTrue(h.drawerState.isClosed)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertEquals(1, h.retriggerConsumedCount)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retriggerToken_publishedWithReadonlyOverlayOnSettings_clearsOverlayAndNavigates() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        // SETTINGS with the read-only transcript overlay active, then the retrigger gesture.
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
            h.viewedSessionId.value = "ro-1"
        }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertNull(h.viewedSessionId.value)
        assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, h.registry.launchedIntents.single().action)
        assertEquals(1, h.retriggerConsumedCount)
        assertNull(h.providerPending())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retrigger_nonOwningInstance_noEndpoint_tokenUntouched() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.NoEndpoint
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        // Normal-task instance: no voice-task identity. The identity gate precedes even the
        // NoEndpoint clear, so this instance converges nothing.
        h.voiceTaskIdentity = null

        render(h)
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        var token = 0L
        composeRule.runOnIdle {
            token = h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        // Token retained, no navigation, no clear, no launch.
        assertEquals(token, h.providerPending()?.token)
        assertEquals(Routes.SETUP, h.nav.currentDestination?.route)
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retrigger_staleOnSettings_notNavigated_notDelivered() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
        }
        awaitRoute(h, Routes.SETTINGS)

        // Over-TTL entry: the coordinator must not navigate; the owning instance's stale-clear
        // effect drops it.
        composeRule.runOnIdle {
            h.publishRetrigger(
                targetTaskId = 10,
                sessionInstanceId = 7L,
                issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - 11_000L,
            )
        }
        composeRule.waitForIdle()

        assertEquals(Routes.SETTINGS, h.nav.currentDestination?.route)
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertNull(h.providerPending())
    }

    @Test
    fun retrigger_nonOwningInstance_onSettings_tokenUntouched_noNavigation() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        // Normal-task instance: no voice-task identity, so the coordinator must not converge.
        h.voiceTaskIdentity = null

        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.runOnIdle {
            h.nav.navigate(Routes.SETTINGS)
        }
        awaitRoute(h, Routes.SETTINGS)

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        assertEquals(Routes.SETTINGS, h.nav.currentDestination?.route)
        assertTrue(h.providerPending() != null)
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
    }

    @Test
    fun retrigger_notDelivered_toNonTargetTask_evenWhenChatSafe() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        // Pending entry was published for task 10; this trusted activity hosts task 11.
        h.voiceTaskIdentity = VoiceTaskIdentity(taskId = 11, sessionInstanceId = 7L)

        render(h)
        awaitRoute(h, Routes.CHAT)

        var token = 0L
        composeRule.runOnIdle {
            token = h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(token, h.providerPending()?.token)
    }

    @Test
    fun retrigger_notDelivered_whenSessionInstanceMismatch() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        // Same task, different session instance: the normal-task/sibling-cycle instance must not steal it.
        h.voiceTaskIdentity = VoiceTaskIdentity(taskId = 10, sessionInstanceId = 99L)

        render(h)
        awaitRoute(h, Routes.CHAT)

        var token = 0L
        composeRule.runOnIdle {
            token = h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(token, h.providerPending()?.token)
    }

    @Test
    fun retrigger_staleToken_notDelivered() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        // Stale entry is not delivered, and the stale-clear effect converges
        // (drops) it — the non-owner path converges ONLY over-TTL entries (by design);
        // this entry is age-11s (over-TTL), so identity is not what gates the clear here —
        // the identity gate holds only for age-0 (non-expired) entries.
        composeRule.runOnIdle {
            h.publishRetrigger(
                targetTaskId = 10,
                sessionInstanceId = 7L,
                issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - 11_000L,
            )
        }
        composeRule.waitForIdle()

        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertNull(h.providerPending())
    }

    @Test
    fun retrigger_negativeAgeToken_notDelivered_andCleared() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        // Future-dated entry (clock skew / bogus timestamp): negative age, never deliverable,
        // and the owning instance's stale-clear effect converges it.
        composeRule.runOnIdle {
            h.publishRetrigger(
                targetTaskId = 10,
                sessionInstanceId = 7L,
                issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() + 60_000L,
            )
        }
        composeRule.waitForIdle()

        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertNull(h.providerPending())
    }

    @Test
    fun retrigger_staleToken_cleared_byNonOwningInstance() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        // Normal-task instance: no voice-task identity. Expired entries are converged regardless
        // of identity match (an over-TTL entry is undeliverable anyway), so the stale-clear
        // effect drops it here too. Non-expired entries keep the identity gate (see
        // retrigger_nonOwningInstance_noEndpoint_tokenUntouched).
        h.voiceTaskIdentity = null

        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle {
            h.publishRetrigger(
                targetTaskId = 10,
                sessionInstanceId = 7L,
                issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - 11_000L,
            )
        }
        composeRule.waitForIdle()

        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertNull("expired entry is converged regardless of identity match", h.providerPending())
    }

    @Test
    fun retrigger_staleToken_cleared_evenWithIdentityMismatch() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        // Identity present but mismatched (different task and session instance): the stale-clear
        // effect must still converge an expired entry — an over-TTL entry is undeliverable
        // anyway, so no orphaned over-TTL entry stays resident for the process lifetime.
        h.voiceTaskIdentity = VoiceTaskIdentity(taskId = 99, sessionInstanceId = 999L)

        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle {
            h.publishRetrigger(
                targetTaskId = 10,
                sessionInstanceId = 7L,
                issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - 11_000L,
            )
        }
        composeRule.waitForIdle()

        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertNull("expired entry is cleared even with identity mismatch", h.providerPending())
    }

    @Test
    fun retrigger_consumeRejected_doesNotLaunch() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        h.consumeResult = false

        render(h)
        awaitRoute(h, Routes.CHAT)

        var token = 0L
        composeRule.runOnIdle {
            token = h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitForIdle()

        // The fake short-circuits before invoking the real consume; AssistantVoiceHandoff.consume is
        // not called: no STT launch and the pending entry survives.
        // (harness returns false without consuming — a non-committing failure; provider-side
        // consume() dropping the entry on TTL-rejection is a different path, pinned by provider tests.)
        assertEquals(1, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertEquals(token, h.providerPending()?.token)
    }

    // ---- Stage 5: consume/drawer/navLoading pins ----

    @Test
    fun consumeVoiceRetrigger_tokenMismatch_returnsFalse_andPendingSurvives() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.pending.value = true // navLoading == true: delivery holds, entry survives for the direct-call arms
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)

        var published = 0L
        composeRule.runOnIdle {
            published = h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        val pending = h.providerPending()
        val identity = VoiceTaskIdentity(taskId = 10, sessionInstanceId = 7L)
        assertEquals(published, pending?.token)

        // Token mismatch: false, entry preserved.
        assertFalse(consumeVoiceRetrigger(0x7FFF_FFFFL, pending, identity, h.appContext))
        assertEquals(published, h.providerPending()?.token)

        // Null identity: false, entry preserved.
        assertFalse(consumeVoiceRetrigger(published, pending, null, h.appContext))
        assertEquals(published, h.providerPending()?.token)

        // Null pending snapshot: false, entry preserved.
        assertFalse(consumeVoiceRetrigger(published, null, identity, h.appContext))
        assertEquals(published, h.providerPending()?.token)

        // Matching token + identity: true, entry consumed.
        assertTrue(consumeVoiceRetrigger(published, h.providerPending(), identity, h.appContext))
        assertNull(h.providerPending())
        // Positive control only wires the ChatScreen consumed-count; the direct production call
        // above never reaches it, so pin the observable here instead (no STT launch happened):
        assertTrue(h.registry.launchedIntents.isEmpty())
    }

    @Test
    fun retrigger_deliveredWithOpenDrawer_userReopensDrawer_staysOpenAndNoRelaunch() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle {
            h.uiScope.launch { h.drawerState.open() }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { h.drawerState.isOpen }

        composeRule.runOnIdle {
            h.publishRetrigger(targetTaskId = 10, sessionInstanceId = 7L)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            h.drawerState.isClosed && h.drawerState.targetValue == DrawerValue.Closed
        }
        composeRule.waitForIdle()

        // Delivery converged exactly once: drawer closed, STT launched, token consumed.
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(1, h.retriggerConsumedCount)
        assertNull(h.providerPending())

        // User re-opens the drawer after delivery: the coordinator must not reclaim the session —
        // no second launch, no re-close — on this or any later state change.
        composeRule.runOnIdle {
            h.uiScope.launch { h.drawerState.open() }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { h.drawerState.isOpen }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(301L) // settle: any erroneously re-keyed effect gets a chance to fire (Compose test clock)
        composeRule.waitForIdle()

        assertTrue(h.drawerState.isOpen)
        assertEquals(1, h.registry.launchedIntents.size)
        assertEquals(1, h.retriggerConsumedCount)
    }

    @Test
    fun retrigger_staleToken_cleared_evenWithNavLoading() {
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Loading
        h.pending.value = true // startup reconcile in-flight -> navLoading == true
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)

        // Stale entry published while navLoading == true: the stale-clear exemption from the
        // navLoading hold must still converge it (RetriggerStaleClearEffect ignores navLoading
        // for expired tokens).
        composeRule.runOnIdle {
            h.publishRetrigger(
                targetTaskId = 10,
                sessionInstanceId = 7L,
                issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - 11_000L,
            )
        }
        composeRule.waitForIdle()

        assertNull("stale-clear must drop the token even while navLoading == true", h.providerPending())
        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())

        // Reconcile settles and the gate opens: the dropped token must not resurface or deliver.
        composeRule.runOnIdle {
            h.pending.value = false
        }
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        assertEquals(0, h.retriggerConsumedCount)
        assertTrue(h.registry.launchedIntents.isEmpty())
        assertNull(h.providerPending())
    }

    // ── 跨 ChatScreen entry 實例（pop CHAT inclusive → 新 CHAT entry）：新 entry 不得重用舊 rc→key 綁定 ──
    // F1（round-3 review, P2/BLOCK）：resetEpoch 由 rememberSaveable 初始化為 0，且不含 entry
    // instance identity；真實 hard-gate／SETTINGS recovery／ReadFailure 路徑以
    // popUpTo(Routes.CHAT) { inclusive = true } 銷毀舊 CHAT entry 後建立全新 entry，全新 entry
    // 以同一 registry key（"stt-slot0-0"）重新註冊、綁回舊 outstanding launch 的 requestCode。
    // 這裡走真實 StartupGateHost 的 pop 路徑（非 fake shell）佐證跨 entry 隔離。
    @Test
    fun poppedChatEntry_outstandingSttResultNeverDeliveredToNewEntry() {
        registerRecognitionService()
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        // 舊 entry A 啟動 INITIAL STT（rcA），結果懸空不投遞。
        composeRule.runOnIdle { h.assistVoiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(1, h.registry.launchedIntents.size)
        val rcA = h.registry.launchedRequestCodes[0]

        // 真實 hard gate：gate 落為 NoEndpoint ⇒ StartupGateHost 的 LaunchedEffect 以
        // popUpTo(CHAT, inclusive=true) 銷毀舊 CHAT entry 並導向 SETUP。
        composeRule.runOnIdle { h.gate.value = EndpointGate.NoEndpoint }
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        // 重建 CHAT（與 coldStart 測試相同方式：回 CHAT 並 inclusive pop 掉 SETUP）⇒ 全新 entry。
        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
            h.nav.navigate(Routes.CHAT) {
                popUpTo(Routes.SETUP) { inclusive = true }
                launchSingleTop = true
            }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        // 新 entry C 的 assist voice 請求 ⇒ 新 launch（rcC）。
        composeRule.runOnIdle { h.assistVoiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(2, h.registry.launchedIntents.size)
        val rcC = h.registry.launchedRequestCodes[1]
        // Canary：pre-fix 的新 entry 以相同 key "stt-slot0-0" 重新註冊 → registerKey 早退重用 rcA。
        // rc-distinctness is not a contract androidx guarantees to callers, but is deterministic here
        // (fresh keys mint fresh rcs outside rcToKey; A's in-flight binding persists) — the canary pins
        // the binding mechanism, behavior asserts below are the real gate.
        assertTrue(rcC != rcA)

        // A 的晚到結果：不得 prefill 新 entry 的草稿。
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("old transcript"),
        )
        composeRule.runOnIdle {
            h.registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("", h.chatViewModel.input.value)

        // C 自己的結果：fresh，正確 prefill。
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("new transcript"),
        )
        composeRule.runOnIdle {
            h.registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("new transcript", h.chatViewModel.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun poppedChatEntry_freshResultFirst_thenStaleFromPoppedEntryIgnored() {
        // GUARD — passes pre- and post-fix; pins the inverse dispatch order, not the F1 mechanism.
        registerRecognitionService()
        val provider = RoutingFakeProvider()
        val h = Harness(newChatViewModel(provider))
        h.gate.value = EndpointGate.Ready(sampleEndpoint())
        h.voiceSwitchesLoaded.value = true
        h.userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)

        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle { h.assistVoiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(1, h.registry.launchedIntents.size)
        val rcA = h.registry.launchedRequestCodes[0]

        composeRule.runOnIdle { h.gate.value = EndpointGate.NoEndpoint }
        awaitRoute(h, Routes.SETUP)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            h.gate.value = EndpointGate.Ready(sampleEndpoint())
            h.nav.navigate(Routes.CHAT) {
                popUpTo(Routes.SETUP) { inclusive = true }
                launchSingleTop = true
            }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.waitForIdle()

        composeRule.runOnIdle { h.assistVoiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(2, h.registry.launchedIntents.size)
        val rcC = h.registry.launchedRequestCodes[1]

        // 新鮮（C 的）結果先回：prefill。
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("new transcript"),
        )
        composeRule.runOnIdle {
            h.registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("new transcript", h.chatViewModel.input.value)

        // 舊 entry（A）的過期結果後回：草稿不得被覆寫。
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("old transcript"),
        )
        composeRule.runOnIdle {
            h.registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("new transcript", h.chatViewModel.input.value)
        assertEquals(0, provider.streamCalls)
    }

    private fun gateDrawer(): DrawerState =
        DrawerState(initialValue = DrawerValue.Closed, confirmStateChange = { true })

    private fun gateRetrigger(
        pending: PendingVoiceRetrigger?,
        identity: VoiceTaskIdentity?,
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = shouldDeliverVoiceRetrigger(
        pending = pending,
        identity = identity,
        currentRoute = Routes.CHAT,
        hasEndpoint = true,
        navLoading = false,
        viewedSessionId = null,
        drawerState = gateDrawer(),
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
    )

    @Test
    fun shouldDeliverVoiceRetrigger_nullIdentity_returnsFalse() {
        val now = SystemClock.elapsedRealtime()
        val pending = PendingVoiceRetrigger(7L, 10, 7L, now)

        assertEquals(false, gateRetrigger(pending, identity = null, nowElapsedRealtimeMs = now))
    }

    @Test
    fun shouldDeliverVoiceRetrigger_taskMismatch_returnsFalse() {
        val now = SystemClock.elapsedRealtime()
        val pending = PendingVoiceRetrigger(7L, 10, 7L, now)

        assertEquals(false, gateRetrigger(pending, VoiceTaskIdentity(11, 7L), now))
    }

    @Test
    fun shouldDeliverVoiceRetrigger_sessionInstanceMismatch_returnsFalse() {
        val now = SystemClock.elapsedRealtime()
        val pending = PendingVoiceRetrigger(7L, 10, 7L, now)

        assertEquals(false, gateRetrigger(pending, VoiceTaskIdentity(10, 99L), now))
    }

    @Test
    fun shouldDeliverVoiceRetrigger_negativeAge_returnsFalse() {
        val now = SystemClock.elapsedRealtime()
        // Entry issued in the future (clock skew/bogus timestamp) must never be delivered.
        val pending = PendingVoiceRetrigger(7L, 10, 7L, now + 60_000L)

        assertEquals(false, gateRetrigger(pending, VoiceTaskIdentity(10, 7L), now))
    }

    @Test
    fun shouldDeliverVoiceRetrigger_ageExactlyTtl_returnsTrue() {
        val now = SystemClock.elapsedRealtime()
        val pending = PendingVoiceRetrigger(7L, 10, 7L, now - VoiceHandoffContract.RETRIGGER_TTL_MS)

        assertEquals(true, gateRetrigger(pending, VoiceTaskIdentity(10, 7L), now))
    }

    @Test
    fun shouldDeliverVoiceRetrigger_ageTtlPlusOne_returnsFalse() {
        val now = SystemClock.elapsedRealtime()
        val pending = PendingVoiceRetrigger(7L, 10, 7L, now - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)

        assertEquals(false, gateRetrigger(pending, VoiceTaskIdentity(10, 7L), now))
    }
}
