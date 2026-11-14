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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
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
                    Dispatchers.resetMain()
                }
            }
        }
    }

    private fun registerRecognitionService() {
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
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
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
}
