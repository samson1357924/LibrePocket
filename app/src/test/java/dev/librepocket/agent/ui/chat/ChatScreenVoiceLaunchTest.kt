package dev.librepocket.agent.ui.chat

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.assistant.VoiceHandoffProvider
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.tool.DenyReason
import dev.librepocket.voice.VoiceStt
import dev.librepocket.voice.VoiceTools
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config

private class VoiceLaunchFakeProvider : LlmProvider {
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
class ChatScreenVoiceLaunchTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val vmStore = ViewModelStore()

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        VoiceHandoffProvider.resetForTest()
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

    private fun registerRecognitionService(includeDefaultCategory: Boolean = true) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = ComponentName("com.example.stt", "com.example.stt.RecognizerActivity")
        val appInfo = ApplicationInfo().apply {
            packageName = component.packageName
        }
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
                // MATCH_DEFAULT_ONLY probes only find services carrying CATEGORY_DEFAULT;
                // omit it to model a recognizer the system cannot launch by default.
                if (includeDefaultCategory) {
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            },
        )
    }

    private class ThrowingRegistry : ActivityResultRegistry() {
        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            throw RuntimeException("launchBoom")
        }
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        var lastRequestCode = -1
        val launchedIntents = mutableListOf<Intent>()

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastRequestCode = requestCode
            if (input is Intent) {
                launchedIntents += input
            }
        }
    }

    private fun newVm(provider: VoiceLaunchFakeProvider): ChatViewModel {
        val dir = Files.createTempDirectory("chat-voice-launch").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "voice-launch.preferences_pb") },
            ),
        )
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(
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
        }
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> provider },
        )
        val vm = ChatViewModel(store, factory, policy)
        vmStore.put("chat-voice-launch-${vmStore.keys().size}", vm)
        return vm
    }

    @Test
    fun voiceInputRequested_waitsForSwitchesLoaded_thenConsumesOnceAndLaunchesRecognizerIntent() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val switchesLoaded = MutableStateFlow(false)
        val userSwitches = MutableStateFlow(mapOf(VoiceTools.SWITCH_INPUT to false))
        val tick = MutableStateFlow(0)
        var consumedCount = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val loaded by switchesLoaded.collectAsState()
            val switches by userSwitches.collectAsState()
            @Suppress("UNUSED_VARIABLE")
            val unusedTick by tick.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = switches,
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedCount++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = loaded,
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(0, consumedCount)
        assertTrue(registry.launchedIntents.isEmpty())
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertCountEquals(0)

        userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true)
        switchesLoaded.value = true
        composeRule.waitForIdle()

        assertEquals(1, consumedCount)
        assertEquals(1, registry.launchedIntents.size)
        val launched = registry.launchedIntents.single()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, launched.action)
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            launched.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertEquals(VoiceStt.LANGUAGE, launched.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(1, launched.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -1))

        // Recomposition must not re-launch or re-consume.
        tick.value = 1
        composeRule.waitForIdle()
        assertEquals(1, consumedCount)
        assertEquals(1, registry.launchedIntents.size)
    }

    @Test
    fun voiceInputRequested_switchDisabledAfterLoad_degradesToUserDisabledWithoutLaunching() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        var consumedCount = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to false),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedCount++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, consumedCount)
        assertTrue(registry.launchedIntents.isEmpty())
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertIsDisplayed()
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultOk_prefillsWithoutAutoSend() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        val resultIntent = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("", "你好"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, resultIntent))
        }
        composeRule.waitForIdle()

        assertEquals("你好", vm.input.value)
        composeRule.onNodeWithText("你好").assertIsDisplayed()
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultEmpty_showsEmptyResultErrorAndNeverSends() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        val emptyIntent = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf(),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, emptyIntent))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT),
        ).assertIsDisplayed()
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultCanceled_keepsManualInputWithoutError() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        vm.onInputChange("手動草稿")
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_CANCELED, null))
        }
        composeRule.waitForIdle()

        assertEquals("手動草稿", vm.input.value)
        composeRule.onAllNodesWithText("做不到", substring = true).assertCountEquals(0)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun noRecognitionService_showsNoPrivilegeErrorWithoutLaunching() {
        // Deliberately do NOT register RecognizerIntent.ACTION_RECOGNIZE_SPEECH.
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        var consumedCount = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedCount++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, consumedCount)
        assertTrue(registry.launchedIntents.isEmpty())
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE),
        ).assertIsDisplayed()
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultOk_nullData_showsEmptyResultErrorWithoutPrefillOrSend() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, null))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT),
        ).assertIsDisplayed()
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultOk_malformedResultsExtra_showsEmptyResultErrorWithoutCrash() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        val malformedIntent = Intent().putExtra(RecognizerIntent.EXTRA_RESULTS, "bad")
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, malformedIntent))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT),
        ).assertIsDisplayed()
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultOk_integerArrayListElements_showsEmptyResultErrorWithoutPrefillOrSend() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        val malformedIntent = Intent().putIntegerArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf(42),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, malformedIntent))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT),
        ).assertIsDisplayed()
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultOk_mixedNullInvalidElements_showsEmptyResultErrorWithoutPrefillOrSend() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        val mixedIntent = Intent().putExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf<Any?>(null, 1, "  "),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, mixedIntent))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT),
        ).assertIsDisplayed()
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultOk_mixedElementsWithValidString_prefillsTrimmedWithoutSend() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        val mixedIntent = Intent().putExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf<Any?>(1, "  hi  "),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, mixedIntent))
        }
        composeRule.waitForIdle()

        assertEquals("hi", vm.input.value)
        composeRule.onNodeWithText("hi").assertIsDisplayed()
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultNonOkNonCanceled_keepsManualInputWithoutErrorOrSend() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        vm.onInputChange("手動草稿")
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.lastRequestCode,
                ActivityResult(Activity.RESULT_FIRST_USER, null),
            )
        }
        composeRule.waitForIdle()

        assertEquals("手動草稿", vm.input.value)
        composeRule.onAllNodesWithText("做不到", substring = true).assertCountEquals(0)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun voiceRetriggerToken_consumesOnceAndLaunchesRecognizerIntent_whenSwitchesReady() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val retriggerToken = MutableStateFlow(0L)
        val consumed = ArrayList<Long>()

        composeRule.setContent {
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumed += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(registry.launchedIntents.isEmpty())

        composeRule.runOnIdle { retriggerToken.value = 42L }
        composeRule.waitForIdle()

        assertEquals(listOf(42L), consumed)
        assertEquals(1, registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, registry.launchedIntents.single().action)
        assertEquals(0, provider.streamCalls)

        // No replay on recomposition/state stability.
        composeRule.waitForIdle()
        assertEquals(listOf(42L), consumed)
        assertEquals(1, registry.launchedIntents.size)
    }

    @Test
    fun voiceRetriggerToken_waitsForSwitchesLoaded_thenLaunchesOnce() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val retriggerToken = MutableStateFlow(7L)
        val switchesLoaded = MutableStateFlow(false)
        val consumed = ArrayList<Long>()

        composeRule.setContent {
            val token by retriggerToken.collectAsState()
            val loaded by switchesLoaded.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = loaded,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumed += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(consumed.isEmpty())
        assertTrue(registry.launchedIntents.isEmpty())

        composeRule.runOnIdle { switchesLoaded.value = true }
        composeRule.waitForIdle()

        assertEquals(listOf(7L), consumed)
        assertEquals(1, registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, registry.launchedIntents.single().action)
    }

    @Test
    fun recognizerWithoutDefaultCategory_degradesToNoPrivilege_initialVoice() {
        // Service exists but without CATEGORY_DEFAULT: the MATCH_DEFAULT_ONLY probe cannot see it.
        registerRecognitionService(includeDefaultCategory = false)
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        var consumedCount = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedCount++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, consumedCount)
        assertTrue(registry.launchedIntents.isEmpty())
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE),
        ).assertCountEquals(1)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun voiceRetriggerConsumedFalse_doesNotLaunch() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val retriggerToken = MutableStateFlow(33L)
        val consumed = ArrayList<Long>()

        composeRule.setContent {
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumed += it
                        false
                    },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(listOf(33L), consumed)
        assertTrue(registry.launchedIntents.isEmpty())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun initialVoiceAndRetrigger_dontStackInSameFrame() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(44L)
        val consumedRetrigger = ArrayList<Long>()
        var consumedInitial = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedInitial++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Both armed on frame 1 with loaded=true: the arbiter admits INITIAL (precedence
        // INITIAL > RETRIGGER). At most one recognizer is launched and the retrigger is
        // NOT consumed yet — it stays armed pending the in-flight launch's settle.
        assertEquals(1, consumedInitial)
        assertEquals(1, registry.launchedIntents.size)
        assertEquals(emptyList<Long>(), consumedRetrigger)
        assertEquals(0, provider.streamCalls)

        // The settle (result callback) releases the gate; the pending retrigger is admitted
        // on the next frame and its own launch follows.
        val resultIntent = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("", "你好"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, resultIntent))
        }
        composeRule.waitForIdle()

        assertEquals(2, registry.launchedIntents.size)
        assertEquals(listOf(44L), consumedRetrigger)
        assertEquals("你好", vm.input.value)
        assertEquals(1, consumedInitial)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun launchThrows_showsNoServiceFallback_consumedStillOnce() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = ThrowingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        var consumedCount = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedCount++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        // Registry blew up inside launch: no crash escapes, the no-service fallback shows,
        // and the one-shot voice consume still happened exactly once.
        assertEquals(1, consumedCount)
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE),
        ).assertCountEquals(1)
        assertEquals(0, provider.streamCalls)
    }


    @Test
    fun bothArmed_loadingSettles_initialWins_successionAdmitsRetriggerOnResult() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(44L)
        val switchesLoaded = MutableStateFlow(false)
        val consumedRetrigger = ArrayList<Long>()
        var consumedInitial = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            val loaded by switchesLoaded.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedInitial++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = loaded,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // LOADING window: Wait consumes nothing and launches nothing.
        assertEquals(0, consumedInitial)
        assertTrue(consumedRetrigger.isEmpty())
        assertTrue(registry.launchedIntents.isEmpty())

        composeRule.runOnIdle { switchesLoaded.value = true }
        composeRule.waitForIdle()

        // Settle: INITIAL wins (precedence); retrigger stays armed, not consumed.
        assertEquals(1, consumedInitial)
        assertTrue(consumedRetrigger.isEmpty())
        assertEquals(1, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)

        val resultA = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("a"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, resultA))
        }
        composeRule.waitForIdle()

        // Settle admits the pending retrigger on the next frame; its own launch follows.
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(listOf(44L), consumedRetrigger)
        assertEquals("a", vm.input.value)
        assertEquals(1, consumedInitial)
        assertEquals(0, provider.streamCalls)

        val resultB = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("b"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, resultB))
        }
        composeRule.waitForIdle()

        assertEquals("b", vm.input.value)
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun recognitionInFlight_secondGesture_heldUntilResult() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(0L)
        val consumedRetrigger = ArrayList<Long>()

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // A second gesture (retrigger) arrives while the first recognition is in flight:
        // it stays armed and is NOT launched.
        composeRule.runOnIdle { retriggerToken.value = 55L }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        assertTrue(consumedRetrigger.isEmpty())

        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_CANCELED, null))
        }
        composeRule.waitForIdle()

        assertEquals(2, registry.launchedIntents.size)
        assertEquals(listOf(55L), consumedRetrigger)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun bothArmed_switchDisabled_degradesAndConsumesBoth_zeroLaunches() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(66L)
        val switchesLoaded = MutableStateFlow(false)
        val userSwitches = MutableStateFlow(mapOf(VoiceTools.SWITCH_INPUT to false))
        val consumedRetrigger = ArrayList<Long>()
        var consumedInitial = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            val loaded by switchesLoaded.collectAsState()
            val switches by userSwitches.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = switches,
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedInitial++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = loaded,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { switchesLoaded.value = true }
        composeRule.waitForIdle()

        // Switch off at settle: both arms degrade in the same pass; zero recognizers launched.
        assertTrue(registry.launchedIntents.isEmpty())
        assertEquals(1, consumedInitial)
        assertEquals(listOf(66L), consumedRetrigger)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF),
        ).assertIsDisplayed()
        assertEquals(0, provider.streamCalls)

        // Gate is not wedged: enabling the switch lets a fresh arm launch.
        composeRule.runOnIdle { userSwitches.value = mapOf(VoiceTools.SWITCH_INPUT to true) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { voiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
    }

    @Test
    fun bothArmed_noService_degradesBoth_gateFree() {
        // Deliberately do NOT register RecognizerIntent.ACTION_RECOGNIZE_SPEECH yet.
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(77L)
        val switchesLoaded = MutableStateFlow(false)
        val consumedRetrigger = ArrayList<Long>()
        var consumedInitial = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            val loaded by switchesLoaded.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedInitial++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = loaded,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { switchesLoaded.value = true }
        composeRule.waitForIdle()

        // No service at settle: both arms degrade in the same pass; zero recognizers launched.
        assertTrue(registry.launchedIntents.isEmpty())
        assertEquals(1, consumedInitial)
        assertEquals(listOf(77L), consumedRetrigger)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE),
        ).assertIsDisplayed()
        assertEquals(0, provider.streamCalls)

        // Gate is not wedged: once a recognizer appears, a fresh arm launches.
        registerRecognitionService()
        composeRule.runOnIdle { voiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
    }

    @Test
    fun retriggerConsumeRejected_doesNotLaunch_gateReleased() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(88L)
        val consumedRetrigger = ArrayList<Long>()

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        false
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Initial still launches; the retrigger stays held by the in-flight gate.
        assertEquals(1, registry.launchedIntents.size)
        assertTrue(consumedRetrigger.isEmpty())

        // Settle: retrigger is admitted; consume rejects it; no second launch happens.
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_CANCELED, null))
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        assertEquals(listOf(88L), consumedRetrigger)
        assertEquals(0, provider.streamCalls)

        // Gate is not wedged: a later armed gesture CAN launch.
        composeRule.runOnIdle { voiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
    }

    @Test
    fun throwingRegistry_successionStillAdmitsAfterInitialSettle() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = ThrowingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(0L)
        val consumedRetrigger = ArrayList<Long>()
        var consumedInitial = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedInitial++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Registry blew up on the initial arm: error shown; the slot is released (not wedged).
        assertEquals(1, consumedInitial)
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE),
        ).assertCountEquals(1)
        assertEquals(0, provider.streamCalls)

        // Arm the retrigger after release: the effect admits it; it booms too.
        composeRule.runOnIdle { retriggerToken.value = 99L }
        composeRule.waitForIdle()
        assertEquals(listOf(99L), consumedRetrigger)
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE),
        ).assertCountEquals(1)

        // Gate is not wedged: a fresh initial arm can boom again.
        composeRule.runOnIdle { voiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(2, consumedInitial)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun sttResultNonOk_releasesSlot_admitsPendingRetrigger() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        vm.onInputChange("手動草稿")
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(0L)
        val consumedRetrigger = ArrayList<Long>()

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // Arm the retrigger mid-flight: held by the in-flight gate.
        composeRule.runOnIdle { retriggerToken.value = 57L }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // Non-OK result: draft preserved, error NOT shown, gate released — retrigger admitted.
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.lastRequestCode,
                ActivityResult(Activity.RESULT_FIRST_USER, null),
            )
        }
        composeRule.waitForIdle()

        assertEquals(2, registry.launchedIntents.size)
        assertEquals(listOf(57L), consumedRetrigger)
        assertEquals("手動草稿", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun micTap_launchesStt_whenGateFree() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        // No initial request, no retrigger token: the mic tap can only be served by the USER arm.
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(registry.launchedIntents.isEmpty())

        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()

        assertEquals(1, registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, registry.launchedIntents.single().action)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun micTap_whileInitialInFlight_holdsUntilSettle_thenLaunches() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(0L)
        val consumedRetrigger = ArrayList<Long>()

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = { voiceRequested.value = false },
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // Mic tap while the initial recognizer is in flight: held, no second launch.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        assertTrue(consumedRetrigger.isEmpty())

        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_CANCELED, null))
        }
        composeRule.waitForIdle()

        // Settle releases the gate: the held mic (USER) arm is admitted next.
        assertEquals(2, registry.launchedIntents.size)
        assertTrue(consumedRetrigger.isEmpty())
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun userArm_queuedBehindInitialAndRetrigger_settleAdmitsInPrecedenceOrder() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(42L)
        val consumedRetrigger = ArrayList<Long>()
        var consumedInitial = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceInputRequested = req,
                    onVoiceInputConsumed = {
                        consumedInitial++
                        voiceRequested.value = false
                    },
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // INITIAL launched; retrigger stays armed (not consumed); USER not armed yet.
        assertEquals(1, consumedInitial)
        assertEquals(1, registry.launchedIntents.size)
        assertTrue(consumedRetrigger.isEmpty())

        // Arm USER by tapping mic: still held by the in-flight initial launch.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        assertTrue(consumedRetrigger.isEmpty())

        // Settle #1: the pending RETRIGGER is admitted next (INITIAL > RETRIGGER > USER).
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_CANCELED, null))
        }
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(listOf(42L), consumedRetrigger)
        assertEquals(1, consumedInitial)

        // Settle #2: the queued USER arm is admitted last. Order: INITIAL → RETRIGGER → USER.
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_CANCELED, null))
        }
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size)
        assertEquals(listOf(42L), consumedRetrigger)
        assertEquals(1, consumedInitial)
        assertEquals(0, provider.streamCalls)
    }
}
