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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import dev.librepocket.entry.EntryNormalize
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
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

    /**
     * #137 C2 prefill 契約（合成 RecognizerIntent extras）：超長結果只進輸入框，
     * 且被截斷至 [EntryNormalize.MAX_TEXT_LENGTH]；AUTO_SEND_AFTER_VOICE=false
     *（常數另由 AssistantVoiceTest.autoSend_isLockedOff 鎖死），故零 provider 請求、
     * 零聊天訊息。voice/assist 只委派系統辨識 Activity，結果不直接進 provider request。
     */
    @Test
    fun sttResultOk_syntheticRecognizerExtras_prefillCappedAndNeverReachesProvider() {
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

        val cap = EntryNormalize.MAX_TEXT_LENGTH
        val transcript = "a".repeat(cap + 500)
        val resultIntent = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("   ", transcript),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequestCode, ActivityResult(Activity.RESULT_OK, resultIntent))
        }
        composeRule.waitForIdle()

        assertEquals(transcript.take(cap), vm.input.value)
        assertEquals(cap, vm.input.value.length)
        assertTrue("prefill 不得建立聊天訊息", vm.messages.value.isEmpty())
        assertEquals("語音字串只進輸入框，不得發出 provider request", 0, provider.streamCalls)
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

    @Test
    fun staleInitialResult_neverPrefillsOverSuccessorDraft() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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

        // Advance past the recognizer watchdog: the INITIAL launch is treated as settled at the
        // next decision, but its callback slot stays wedged (result never dispatched below).
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()

        // The mic (USER) arm is admitted via the watchdog; slot 0 remains wedged.
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)

        // The USER launch reports back first: its (fresh) transcript prefills the draft.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh text"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.launchedRequestCodes[1],
                ActivityResult(Activity.RESULT_OK, freshResult),
            )
        }
        composeRule.waitForIdle()
        assertEquals("fresh text", vm.input.value)

        // The stale INITIAL launch reports back late: it must never overwrite the successor draft.
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale draft overwrite attempt"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.launchedRequestCodes[0],
                ActivityResult(Activity.RESULT_OK, staleResult),
            )
        }
        composeRule.waitForIdle()
        assertEquals("fresh text", vm.input.value)
        assertEquals(2, registry.launchedIntents.size)
    }

    @Test
    fun bothSlotsBusy_thirdArm_degradesToUnresponsive_tokenNotConsumed() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)
        val retriggerToken = MutableStateFlow(0L)
        val consumedRetrigger = ArrayList<Long>()

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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
        // Launch #1: INITIAL in flight; slot 0 held; no result dispatched (never returns).
        assertEquals(1, registry.launchedIntents.size)

        // Past the watchdog: the mic (USER) arm is admitted; launch #2 takes slot 1; slot 0 wedged.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)

        // Past the watchdog again: both arms are watchdog-evicted by the next decision, yet both
        // slots stay busy (neither result ever returned). A fresh retrigger arm is admitted by
        // the (now free) gate, finds no slot, degrades with a user-visible notice and releases.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.runOnIdle { retriggerToken.value = 42L }
        composeRule.waitForIdle()

        // reserve-before-consume contract change: the one-shot token is NOT consumed on the
        // no-slot path, no recognizer is launched, and the DETAIL_RECOGNIZER_UNRESPONSIVE
        // fallback is shown alongside the reset affordance. The token stays armed (valid until TTL).
        assertEquals(emptyList<Long>(), consumedRetrigger)
        assertEquals(2, registry.launchedIntents.size)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("重設語音辨識").assertIsDisplayed()
        assertEquals(0, provider.streamCalls)

        // A repeated re-arm degrades identically and, like the first, preserves the one-shot
        // token. (Gate-release itself is pinned where admission is observable — see
        // initialVoice_notConsumed_whenBothSlotsWedged_unresponsiveShown_resetVisible: launch #3
        // after the slot-0 late return requires a fresh decide() admission.)
        composeRule.runOnIdle { retriggerToken.value = 43L }
        composeRule.waitForIdle()
        assertEquals(emptyList<Long>(), consumedRetrigger)
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun initialVoice_notConsumed_whenBothSlotsWedged_unresponsiveShown_resetVisible() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(false)
        var consumedCount = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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

        // Two hanging launches (USER arm each): slot 0 then slot 1; neither ever returns.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // Both slots wedged: the INITIAL one-shot flag must NOT be consumed, the unresponsive
        // fallback is shown, and the reset affordance becomes visible.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.runOnIdle { voiceRequested.value = true }
        composeRule.waitForIdle()

        assertEquals(0, consumedCount)
        assertEquals(2, registry.launchedIntents.size)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("重設語音辨識").assertIsDisplayed()
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)

        // Late return from slot 0 (gen 1): bookkeeping frees slot 0. The result is stale
        // (watchdog-evicted admit generation has fallen behind the current one), so the draft
        // must NOT be prefilled — but the slot freed by the late return lets the armed INITIAL
        // retry: it is admitted and launches on slot 0.
        val lateResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("你好"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.launchedRequestCodes[0],
                ActivityResult(Activity.RESULT_OK, lateResult),
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, consumedCount)
        assertEquals(3, registry.launchedIntents.size)
        // Slot 1 is still wedged (its result never returned), so the retried INITIAL launch can
        // only have reused slot 0 — the exact slot freed by the stale late return above.
        // Stale late result never overwrites the draft.
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun retriggerToken_notConsumed_whenBothSlotsWedged_unresponsiveShown() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val retriggerToken = MutableStateFlow(0L)
        val consumedRetrigger = ArrayList<Long>()
        var consumeLambdaCalls = 0

        composeRule.setContent {
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumeLambdaCalls++
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Two hanging launches (USER arm each): slot 0 then slot 1; neither ever returns.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // Arm a retrigger token with both slots wedged: admit happens, but the one-shot token
        // must NOT be consumed, no third recognizer launches, and the unresponsive notice shows.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.runOnIdle { retriggerToken.value = 77L }
        composeRule.waitForIdle()

        assertEquals(0, consumeLambdaCalls)
        assertEquals(emptyList<Long>(), consumedRetrigger)
        // Token stays armed (valid until its TTL); it is simply unconsumed.
        assertEquals(77L, retriggerToken.value)
        assertEquals(2, registry.launchedIntents.size)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun wedgedSlot_lateReturn_freesSlotForReuse() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L

        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        // Two hanging launches: slot 0 then slot 1; neither returns.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // Slot 0's wedged recognizer finally returns (CANCELED, gen 1 — stale by now): the slot
        // is still released for reuse even though the result itself is discarded.
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.launchedRequestCodes[0],
                ActivityResult(Activity.RESULT_CANCELED, null),
            )
        }
        composeRule.waitForIdle()

        // Past the watchdog again, a fresh mic tap is admitted (slot 0 freed by the stale return)
        // and must launch on slot 0 again.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()

        assertEquals(3, registry.launchedIntents.size)
        // Slot 1 is still wedged (its result never returned), so the third launch can only
        // have reused slot 0 — the exact slot freed by the stale late return above.
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun watchdogTimerExpiry_releasesGateWithZeroUserInteraction() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L

        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        // Launch #1 (USER arm, slot 0) hangs. A second mic tap arms USER again, but the gate is
        // busy, so the arm is held without launching.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // The arbiter clock passes the watchdog while nothing else changes; the watchdog timer
        // effect (121s on the compose test clock) wakes the mirror, which re-decides with zero
        // further user interaction: the held USER arm is admitted onto the free slot 1.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.mainClock.advanceTimeBy(121_000L)
        composeRule.runOnIdle {}
        composeRule.waitForIdle()

        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun unresponsiveWedge_resetButton_reinstatesVoiceInput_andDropsOutstandingResults() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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
        assertEquals(1, registry.launchedIntents.size) // INITIAL gen1 slot0, hangs, voiceInputRequested consumed

        // Watchdog-evict gen1; launch mic on slot1 (gen2).
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // Watchdog-evict gen2; next mic tap finds both slots wedged → unresponsive message + reset button.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()

        // User hard-resets: wedge cleared, unresponsive notice dismissed.
        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertCountEquals(0)

        // A late, pre-reset recognizer result on slot0 (gen1) is unattributable after the reset:
        // it must not prefill the draft. (Pre-reset rc dispatch reaches an unregistered callback —
        // the reset advances resetEpoch, which re-keys the launchers and disposes/unregisters the
        // pre-reset ones, so the registry boundary discards the result before handleSttResult ever
        // runs. Re-occupancy is pinned by resetEpoch_slotReoccupiedByNewLaunch_oldResultNeverPrefillsOrSettlesIt.)
        val lateResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("pre-reset stale transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.launchedRequestCodes[0],
                ActivityResult(Activity.RESULT_OK, lateResult),
            )
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)

        // Voice input is usable again: mic launches immediately, no degrade.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size)
    }

    @Test
    fun resetAffordance_notShown_whenNoWedge() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }

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

        // Healthy launch: no error, hence no reset affordance.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        composeRule.onAllNodesWithText("重設語音辨識").assertCountEquals(0)
    }

    @Test
    fun bootstrapRestoredSlotStaysOccupied_micLaunchesOnOtherSlot() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val now = VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 10_000L
        // Simulates the saved-bundle snapshot after Activity recreation: slot 0 wedged by a
        // pre-recreation INITIAL launch (gen 3), gate watermark at 3. admittedAtNanos is stale
        // vs the clock, so the first decision evicts the restored launch (slot stays wedged).
        val bootstrap = SttLaunchState(
            slotBusy = listOf(true, false),
            slotGen = listOf(3L, 0L),
            admitGeneration = 3L,
            inFlightName = "INITIAL",
            admittedAtNanos = 1_000L,
            screenId = "123e4567-e89b-42d3-a456-426655440000",
            resetEpoch = 0,
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
                LocalSttLaunchStateBootstrap provides bootstrap,
            ) {
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

        // Restored slot 0 stays occupied: exactly one recognizer launches (via the other slot).
        assertEquals(1, registry.launchedIntents.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, registry.launchedIntents.single().action)
        // No result dispatched yet: the draft must be untouched.
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)

        // Both slots are now occupied (slot 0 wedged by the pre-recreation INITIAL launch, slot 1
        // held by the fresh USER arm in flight). A second mic click queues behind the busy gate:
        // no additional recognizer is launched and no degrade notice fires at this point —
        // degradation with both slots wedged requires the gate to be free, which is covered by
        // bootstrapRestoredBothSlotsBusy_micDegradesToNoService (and by the StateRestorationTester
        // end-to-end variant in this file). Slot count pins occupancy, not launch eligibility.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // The launch rides the post-restore generation watermark (4 > 3): its result is NOT
        // mistaken for a stale pre-recreation delivery and prefills the draft.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("你好"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.launchedRequestCodes[0],
                ActivityResult(Activity.RESULT_OK, freshResult),
            )
        }
        composeRule.waitForIdle()
        assertEquals("你好", vm.input.value)
        // The settle frees the gate; the queued mic click then admits after the dispatch and
        // launches on the FREED slot 1 — the wedged bootstrap slot 0 is never recycled. This pins
        // slot identity end to end: slot 0 stays wedged, slot 1 carries both the fresh admit and
        // its attribution.
        assertEquals(2, registry.launchedIntents.size)
    }

    @Test
    fun bootstrapRestoredBothSlotsBusy_micDegradesToUnresponsive() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val now = VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 10_000L
        // Both slots wedged by pre-recreation launches; admittedAtNanos stale so the restored
        // gate is watchdog-evicted at the first decision — the degrade comes from the wedged
        // slots, not from a busy gate.
        val bootstrap = SttLaunchState(
            slotBusy = listOf(true, true),
            slotGen = listOf(3L, 4L),
            admitGeneration = 4L,
            inFlightName = "INITIAL",
            admittedAtNanos = 1_000L,
            screenId = "123e4567-e89b-42d3-a456-426655440000",
            resetEpoch = 0,
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
                LocalSttLaunchStateBootstrap provides bootstrap,
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()

        // No slot is free: no recognizer is launched, the DETAIL_RECOGNIZER_UNRESPONSIVE
        // fallback is shown, and the reset affordance becomes visible; the draft is untouched.
        assertTrue(registry.launchedIntents.isEmpty())
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("重設語音辨識").assertIsDisplayed()
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun recreationDuringOutstandingStt_preRecreationResultDeliveredToRestoredComposition() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L

        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        // Mic tap: the fresh composition admits a USER arm (gen 1) and launches on slot 0; the
        // recognizer never reports before recreation.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // Activity recreation: rememberSaveable round-trips the slot table + arbiter watermark/gate
        // + launch key through the real (Parcel-backed) saveable path.
        tester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        // The pre-recreation recognizer result is delivered to the restored composition on the
        // same launch rc (the rc survives the restore through the registry).
        val preRecreationRc = registry.launchedRequestCodes[0]
        val recognized = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("你好"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                preRecreationRc,
                ActivityResult(Activity.RESULT_OK, recognized),
            )
        }
        composeRule.waitForIdle()

        // Restored attribution: the result is paired (slot 0 / gen 1 / watermark 1) and prefilled —
        // not dropped as unattributed (gen 0) or stale.
        assertEquals("你好", vm.input.value)

        // Slot 0 freed by the settle: a subsequent mic click launches again on a free slot.
        // (A non-blank draft hides the mic behind the send button, so clear it through the same
        // onInputChange entry point the TextField uses.)
        composeRule.runOnIdle { vm.onInputChange("") }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun recreationDuringOutstandingStt_successorSupersedesPreRecreationResult() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L

        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        // Hanging pre-recreation launch #1 (USER, gen 1, slot 0) — never returns.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        val staleRc = registry.launchedRequestCodes[0]

        // Activity recreation through the real saveable round-trip.
        tester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        // Watchdog-evict the restored gen-1 arm, then tap mic: the USER successor is admitted
        // (gen 2) and launches on the free slot 1 (slot 0 stays wedged from the pre-recreation
        // launch).
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        val successorRc = registry.launchedRequestCodes[1]

        // The pre-recreation result arrives late: gen 1 is stale vs the successor's admit
        // generation (gen 2) — the draft is NOT overwritten, and the successor's gate is not
        // settled by the stale callback.
        val staleIntent = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale-old-transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                staleRc,
                ActivityResult(Activity.RESULT_OK, staleIntent),
            )
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)

        // Successor gate unaffected: its own result is fresh and prefills the draft.
        val successorIntent = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("successor-text"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(
                successorRc,
                ActivityResult(Activity.RESULT_OK, successorIntent),
            )
        }
        composeRule.waitForIdle()
        assertEquals("successor-text", vm.input.value)
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun saverRestore_bothSlotsWedged_throughRealSaveableRoundTrip() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L

        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                )
            }
        }
        composeRule.waitForIdle()

        // Hanging launch #1 (USER, gen 1, slot 0) — never returns.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)

        // Watchdog-evict the in-flight arm and admit the USER successor (gen 2): hanging
        // launch #2 launches on slot 1. Both slots are now wedged.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // Activity recreation through the real saveable round-trip: both wedged slots and the
        // in-flight (USER, gen 2) gate round-trip into the restored composition.
        tester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        // Watchdog-evict the restored launch, then tap mic: the gate is free but both slots are
        // wedged by pre-recreation launches — no recognizer is launched; the DETAIL_RECOGNIZER_UNRESPONSIVE
        // fallback is shown and the gate releases.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()

        assertEquals(2, registry.launchedIntents.size)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("重設語音辨識").assertIsDisplayed()
        assertEquals("", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    // ── 複合快照 malformed-restore 回歸：保存的 SttLaunchState bundle 被污染時，重建不得 NPE、
    // 也不得把重建前 A 的晚到結果誤歸屬給新 composition 的 C。────────────────
    // 相位 1：在注入的 in-memory SaveableStateRegistry 下組合 → tap mic（A 起、gen1 slot0、懸掛）。
    // 相位 2：與 StateRestorationTester 相同的 shouldEmitChildren 機制（先丟棄 children、再以
    // 污染後的 seed 重建；避免 key() 改變 composite key hash 而使還原路徑失效）→ 斷言不崩潰、
    // A 的晚到結果不 prefill、C 的結果正常 prefill、永不 auto-send。
    private class SwitchableSaveableStateRegistry(
        initialDelegate: SaveableStateRegistry,
    ) : SaveableStateRegistry {
        var delegate: SaveableStateRegistry = initialDelegate
            private set

        fun swapTo(newDelegate: SaveableStateRegistry) {
            delegate = newDelegate
        }

        override fun canBeSaved(value: Any): Boolean = delegate.canBeSaved(value)
        override fun consumeRestored(key: String): Any? = delegate.consumeRestored(key)
        override fun performSave(): Map<String, List<Any?>> = delegate.performSave()
        override fun registerProvider(
            key: String,
            valueProvider: () -> Any?,
        ): SaveableStateRegistry.Entry = delegate.registerProvider(key, valueProvider)
    }

    // saved map 的結構：rememberSaveable 以 (composite group key) → 該 group 內所有
    // provider 的 save 輸出串接後的 List。SttLaunchStateSaver 的輸出被 mutableStateSaver
    // 包成 MutableState<List<Any?>>，是 key1 串接 List 內的一枚元素（含 wasActive=MS(false)、
    // sttLaunchSnapshot=MS(compositeBundle)——內含 slot/gen/watermark/screenId/resetEpoch
    // 的單枚複合 bundle）。因此以
    // 「MutableState<List<*>> 且 value[0] 為 size 2 的 List<Boolean>」的形狀定位該元素。
    private fun isSttLaunchStateBundleSaverValue(value: Any?): Boolean =
        value is List<*> && value.isNotEmpty() &&
            value[0] is List<*> &&
            (value[0] as List<*>).size == 2 &&
            (value[0] as List<*>).all { it is Boolean }

    private fun findSttLaunchStateBundle(
        saved: Map<String, List<Any?>>,
    ): Triple<String, Int, MutableState<*>> {
        val candidates = saved.flatMap { (key, providers) ->
            providers.mapIndexedNotNull { index, element ->
                if (element is MutableState<*> && isSttLaunchStateBundleSaverValue(element.value)) {
                    Triple(key, index, element)
                } else {
                    null
                }
            }
        }
        assertEquals("exactly one SttLaunchState bundle element expected", 1, candidates.size)
        return candidates.single()
    }

    private fun composeVoiceLaunchProbe(
        vm: ChatViewModel,
        registry: RecordingRegistry,
        owner: ActivityResultRegistryOwner,
        saveRegistry: SwitchableSaveableStateRegistry,
        shouldEmit: MutableState<Boolean>,
        now: Long,
    ) {
        composeRule.setContent {
            val emit by shouldEmit
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
                LocalSaveableStateRegistry provides saveRegistry,
            ) {
                // 與 StateRestorationTester 相同的 shouldEmitChildren 機制：if 分支進／出
                // 會丟棄並重建 children group，但 group 的來源位置（composite key hash）不變——
                // rememberSaveable 才會在重建後讀到 seeded registry 裡的還原值。
                if (emit) {
                    ChatScreen(
                        padding = PaddingValues(0.dp),
                        viewModel = vm,
                        userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                        voiceSwitchesLoaded = true,
                    )
                }
            }
        }
    }

    private fun malformedRestoreRecreationProbe(
        corrupt: (MutableList<Any?>) -> Unit,
    ) {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val now = 1_000_000_000L
        val saveRegistry = SwitchableSaveableStateRegistry(SaveableStateRegistry(emptyMap()) { true })
        val shouldEmit = mutableStateOf(true)

        // 相位 1：組合、mic tap → A 起（gen1、slot0）、懸掛；然後讀取保存的 bundle。
        composeVoiceLaunchProbe(vm, registry, owner, saveRegistry, shouldEmit, now)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size) // A USER gen1 slot0, hangs
        val rcA = registry.launchedRequestCodes[0]

        // 相位 1 結束即讀取保存的 bundle（children 丟棄後 registry 已清空，必須先讀）。
        val saved = saveRegistry.performSave()
        val (key, index, element) = findSttLaunchStateBundle(saved)

        // 相位 2 步驟 1：丟棄 children（與 activity recreation 的 dispose 同路徑——DisposableEffect
        // onDispose 同步 unregister launchers；key 間的 composite key hash 保持不變）。
        shouldEmit.value = false
        composeRule.waitForIdle()

        // 相位 2 步驟 2：以污染後的 seed 重建（同一 RecordingRegistry 實例；重建的 composition
        // 從 seeded registry 還原——而非從相位 1 的 live 狀態）。
        @Suppress("UNCHECKED_CAST")
        val inner = (element.value as List<Any?>).toMutableList().also(corrupt)
        val seededElement = mutableStateOf(inner as List<Any?>)
        val seededList = saved.getValue(key).toMutableList().also { it[index] = seededElement }
        val reseed = saved.toMutableMap().apply { put(key, seededList) }
        saveRegistry.swapTo(SaveableStateRegistry(reseed) { true })
        shouldEmit.value = true
        composeRule.waitForIdle()

        // 不崩潰、不自動 launch、草稿不變。
        assertEquals(1, registry.launchedIntents.size)
        assertEquals("", vm.input.value)

        // 再按 mic → C 起（新 fresh 世代 gen1、新 slot0），rcC != rcA。
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        val rcC = registry.launchedRequestCodes[1]
        assertTrue(rcC != rcA)
        assertEquals("", vm.input.value)

        // A 的晚到結果：相位 2 為 fresh composite（新 UUID）⇒ A 的 key 永不重新註冊，
        // 結果在 registry 邊界不可投遞 → 草稿保持空白，也不結算 C 的閘。
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("old transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)

        // C 自己的結果：新鮮、正常 prefill；永不 auto-send。
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("new transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("new transcript", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun malformedRestore_corruptSlotBusySize_recreationFailsClosedWithoutCrash() {
        // 污染 (a)：slotBusy size==1 → inner restore 回 null → 複合 saver 回 fresh composite（不 NPE）。
        malformedRestoreRecreationProbe { it[0] = listOf(true) }
    }

    @Test
    fun malformedRestore_invariantViolation_recreationFailsClosedWithoutCrash() {
        // 污染 (b)：busy slot0 但 slotGen 全 0（跨欄位不變量 1 違反）；保留
        // admitGeneration=1／inFlightName="USER"／admittedAtNanos>0。
        malformedRestoreRecreationProbe { it[1] = listOf(0L, 0L) }
    }

    // ── resetEpoch（「重設語音辨識」世代）：重設後，重設前 launch 的 rc 永遠不可達 ─────────────
    // 三段式 prologue（與 unresponsiveWedge_resetButton_... 相同）：voiceInputRequested=true 的
    // INITIAL gen1 懸掛 slot0 → watchdog → mic B gen2 懸掛 slot1 → watchdog → mic（兩 slot 全忙）
    // → unresponsive 橫幅 → 使用者 tap「重設語音辨識」→ mic → C gen3 slot0。
    // 重設前的 launcher 於重組時被 DisposableEffect(slotKey) 的 onDispose 同步 unregister；其 rc→key
    // 綁定雖保留（in-flight unregister 語義），callback 已移除——重設前 rc 的晚到結果在 registry 邊界
    // 永不投遞（late result 停靠 pendingResults，鍵不再註冊），無法冒充重設後的 launch。

    @Test
    fun resetEpoch_slotReoccupiedByNewLaunch_oldResultNeverPrefillsOrSettlesIt() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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
        assertEquals(1, registry.launchedIntents.size) // A INITIAL gen1 slot0, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // B USER gen2 slot1, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // both wedged → unresponsive
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()

        // Hard reset: pre-reset launchers are disposed/unregistered; unresponsive notice dismissed.
        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertCountEquals(0)

        // Mic → C admits (gen3) on the freed slot0. Its launch is a NEW registry key derived from
        // the advanced resetEpoch, so its rc must differ from both pre-reset launches.
        // rc-distinctness is not a contract androidx guarantees to callers, but is deterministic here
        // (fresh keys mint fresh rcs outside rcToKey; A's in-flight binding persists) — the canary pins
        // the binding mechanism, behavior asserts below are the real gate.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size)
        val rcA = registry.launchedRequestCodes[0]
        val rcB = registry.launchedRequestCodes[1]
        val rcC = registry.launchedRequestCodes[2]
        assertTrue(rcC != rcA)
        assertTrue(rcC != rcB)

        // A's late pre-reset result: undeliverable at the registry boundary — must not prefill
        // and must not settle C's gate early. (The gate is probed indirectly here — no premature
        // C prefill/settle; the direct probe lives in T3, resetEpoch_oldCancelAfterReoccupancy_….)
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("pre-reset stale transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)
        assertEquals(3, registry.launchedIntents.size)

        // C's own result: fresh, prefills, and settles exactly its own launch.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)
        assertEquals(0, provider.streamCalls)

        // C's settle was exact: a subsequent mic tap launches again. (Clear the draft first — a
        // non-blank draft hides the mic behind the send button.)
        composeRule.runOnIdle { vm.onInputChange("") }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(4, registry.launchedIntents.size)
    }

    @Test
    fun resetEpoch_freshResultArrivesFirst_freshPrefillsThenStaleDropped() {
        // GUARD — passes pre- and post-fix; pins ordering, not the F1 mechanism.
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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
        assertEquals(1, registry.launchedIntents.size) // A INITIAL gen1 slot0, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // B USER gen2 slot1, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // both wedged → unresponsive

        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size) // C USER gen3 slot0
        val rcA = registry.launchedRequestCodes[0]
        val rcC = registry.launchedRequestCodes[2]

        // C reports first: fresh result prefills and settles.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)

        // A's late pre-reset result afterwards: undeliverable, no effect on the settled state.
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()

        // B's late pre-reset result (slot1 axis): likewise undeliverable at the registry boundary.
        val slot1StaleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("slot1 stale transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(registry.launchedRequestCodes[1], ActivityResult(Activity.RESULT_OK, slot1StaleResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)
        assertEquals(3, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun resetEpoch_oldCancelAfterReoccupancy_noPrefillNoEarlySettle() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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
        assertEquals(1, registry.launchedIntents.size) // A INITIAL gen1 slot0, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // B USER gen2 slot1, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // both wedged → unresponsive

        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size) // C USER gen3 slot0
        val rcA = registry.launchedRequestCodes[0]
        val rcC = registry.launchedRequestCodes[2]

        // A's pre-reset recognizer reports RESULT_CANCELED late: undeliverable at the registry
        // boundary — must not prefill, must not settle C's gate.
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_CANCELED, null))
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)

        // C's gate is still held: a mic tap queues a USER arm without launching.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size)

        // C's own fresh result: prefills.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun resetEpoch_staleResultAfterFreshResult_noEffectOnSettledState() {
        // GUARD — passes pre- and post-fix; pins ordering, not the F1 mechanism.
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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
        assertEquals(1, registry.launchedIntents.size) // A INITIAL gen1 slot0, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // B USER gen2 slot1, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // both wedged → unresponsive

        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size) // C USER gen3 slot0
        val rcA = registry.launchedRequestCodes[0]
        val rcC = registry.launchedRequestCodes[2]

        // C settles first (fresh).
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)

        // A's late pre-reset result afterwards: undeliverable; settled state unchanged.
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)
        assertEquals(3, registry.launchedIntents.size)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun resetEpoch_pendingInitialArmSurvivesReset_launchesAndAttributes() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(false)
        var consumedCount = 0

        composeRule.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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

        // Wedge both slots with mic taps: A USER gen1 slot0, B USER gen2 slot1.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // An INITIAL request arrives while both slots are wedged: reserved-before-consume —
        // not launched, not consumed, unresponsive banner stays up.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.runOnIdle { voiceRequested.value = true }
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        assertEquals(0, consumedCount)
        assertTrue(voiceRequested.value)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()

        // Hard reset: the pending INITIAL arm survives; the settle-sequence bump re-decides and
        // admits it as gen3 onto the freed slot0.
        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size)
        assertEquals(1, consumedCount)
        assertEquals(false, voiceRequested.value)
        val rcA = registry.launchedRequestCodes[0]
        val rcC = registry.launchedRequestCodes[2]
        // rc-distinctness is not a contract androidx guarantees to callers, but is deterministic here
        // (fresh keys mint fresh rcs outside rcToKey; A's in-flight binding persists) — the canary pins
        // the binding mechanism, behavior asserts below are the real gate.
        assertTrue(rcC != registry.launchedRequestCodes[0])
        assertTrue(rcC != registry.launchedRequestCodes[1])

        // A's late pre-reset result: undeliverable at the registry boundary.
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)

        // C's own fresh result attributes correctly.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun resetEpoch_retriggerArmSurvivesReset_consumesOnceAndAttributes() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val retriggerToken = MutableStateFlow(0L)
        val consumedRetrigger = ArrayList<Long>()
        var consumeLambdaCalls = 0

        composeRule.setContent {
            val token by retriggerToken.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
                ChatScreen(
                    padding = PaddingValues(0.dp),
                    viewModel = vm,
                    userSwitches = mapOf(VoiceTools.SWITCH_INPUT to true),
                    voiceSwitchesLoaded = true,
                    voiceRetriggerToken = token,
                    onVoiceRetriggerConsumed = {
                        consumeLambdaCalls++
                        consumedRetrigger += it
                        retriggerToken.value = 0L
                        true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Wedge both slots with mic taps: A USER gen1 slot0, B USER gen2 slot1. Neither returns.
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size)
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // Retrigger token arrives while both slots are wedged: reserved-before-consume — the
        // one-shot token must NOT be consumed, no third recognizer launches, banner stays up.
        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.runOnIdle { retriggerToken.value = 77L }
        composeRule.waitForIdle()
        assertEquals(0, consumeLambdaCalls)
        assertEquals(emptyList<Long>(), consumedRetrigger)
        assertEquals(77L, retriggerToken.value)
        assertEquals(2, registry.launchedIntents.size)
        composeRule.onNodeWithText(
            VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE),
        ).assertIsDisplayed()

        // Hard reset: the pending RETRIGGER arm survives; the settle-sequence bump re-decides and
        // admits it as gen3 on the freed slot0 (first free slot).
        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size)
        assertEquals(1, consumeLambdaCalls)
        assertEquals(listOf(77L), consumedRetrigger)
        assertEquals(0L, retriggerToken.value)
        val rcA = registry.launchedRequestCodes[0]
        val rcC = registry.launchedRequestCodes[2]
        // The third launch re-keys on the advanced resetEpoch, so its rc differs from both
        // pre-reset launches.
        assertTrue(rcC != registry.launchedRequestCodes[0])
        assertTrue(rcC != registry.launchedRequestCodes[1])

        // A's late pre-reset result: undeliverable at the registry boundary.
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)

        // C's own fresh result attributes correctly.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun resetEpoch_stateRestorationAfterReset_oldRcUndeliverableAfterRestore() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var now = 1_000_000_000L
        val voiceRequested = MutableStateFlow(true)

        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalVoiceSttArbiterClock provides { now },
            ) {
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
        assertEquals(1, registry.launchedIntents.size) // A INITIAL gen1 slot0, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // B USER gen2 slot1, hangs

        composeRule.runOnIdle { now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1_000L }
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size) // both wedged → unresponsive

        composeRule.onNodeWithText("重設語音辨識").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size) // C USER gen3 slot0
        val rcA = registry.launchedRequestCodes[0]
        val rcC = registry.launchedRequestCodes[2]

        // Recreation through the real saveable round-trip (resetEpoch itself is rememberSaveable,
        // so the restored composition registers the same post-reset keys).
        tester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(3, registry.launchedIntents.size)

        // A's pre-reset rc: undeliverable after the restore too — its key is gone from the
        // registry (unregistered pre-reset; never re-registered).
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)
        assertEquals(3, registry.launchedIntents.size)

        // C's result: fresh, prefills.
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("fresh"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("fresh", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }

    // ── 跨 ChatScreen entry 實例（pop → 新 entry）：新 entry 不得重用舊 rc→key 綁定 ────────────
    // F1（round-3 review, P2/BLOCK）：resetEpoch 由 rememberSaveable 初始化為 0，且不含
    // screen/entry instance identity；舊 CHAT entry 被 popUpTo(CHAT, inclusive=true) 銷毀（其
    // launcher onDispose 會 unregister，但 outstanding launch 的 rc→key 綁定留在 Activity-scoped
    // registry）後，全新 CHAT entry 一律從 epoch 0 起算、以相同 key 重新註冊（"stt-slot0-0"），
    // 依 androidx registerKey 早退重用同一 requestCode——使舊辨識的晚到 RESULT_OK 被投遞進新
    // entry 的 callback，prefill 舊逐字稿覆寫新草稿並提早結算新閘。此節的鑰匙是 entry-scoped
    // screenId（複合 SttLaunchState 快照欄位，重建還原同一 entry），key 推導為 "stt-<screenId>-slotN-<epoch>"：
    // 同一 entry 的 recreation 沿用原 key 可收回有效結果；新 entry 絕不重用已銷毀 entry 的
    // outstanding rc→key；重設仍以 epoch 斷開舊 callback。

    @Test
    fun newChatEntry_popThenNewEntry_outstandingOldResultNeverPrefillsOrSettlesIt() {
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val entryId = MutableStateFlow("A")
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val id by entryId.collectAsState()
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                // key(id) 換鍵＝舊 entry 離開組合（DisposableEffect onDispose 會 unregister，
                // in-flight outstanding launch 的 rc→key 綁定留在 registry），新 entry 全新組合
                // （fresh screenId UUID、resetEpoch=0、slot 表全空）。
                key(id) {
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
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size) // A INITIAL gen1 slot0, hangs
        // A 的結果懸空不投遞——這正是本測試的前提。
        val rcA = registry.launchedRequestCodes[0]

        // Pop the old entry: A 離開組合（unregister；in-flight 綁定保留），C 全新組合進場。
        composeRule.runOnIdle { entryId.value = "C" }
        composeRule.waitForIdle()

        // 新 entry 的 mic 啟動 C（slot0 全空，可派）。
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        val rcC = registry.launchedRequestCodes[1]
        // Canary pinning androidx-internal minting（registerKey 早退重用已占用 rc 的行為）：
        // pre-fix 的新 entry 以相同 key "stt-slot0-0" 重新註冊 → rcC == rcA。
        // rc-distinctness is not a contract androidx guarantees to callers, but is deterministic here
        // (fresh keys mint fresh rcs outside rcToKey; A's in-flight binding persists) — the canary pins
        // the binding mechanism, behavior asserts below are the real gate.
        assertTrue(rcC != rcA)

        // A 的晚到結果（舊 entry 已 pop）：必須在 registry 邊界終結——不得 prefill C 的草稿、
        // 不得結算 C 的閘。
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("old transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("", vm.input.value)

        // C 的 gate 仍被 C 自己的 launch 持有：mic tap 只佇列一枚 USER arm、不啟動
        // gate 仍由 C 持有 ⇒ 佇列 arm 不被消費（此 USER arm 留至 C settle 時被消費，見下文）。
        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)

        // C 自己的結果：fresh，正確 prefill，且 settle 的是自己的 launch（不 auto-send）。
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("new transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("new transcript", vm.input.value)
        assertEquals(0, provider.streamCalls)
        // settle 釋放閘後，gate-hold probe 排隊的 USER arm 於 settle 點亮時被喚起 ⇒ 第三枚 recognizer 啟動（不依賴後續點按）
        assertEquals(3, registry.launchedIntents.size)
    }

    @Test
    fun newChatEntry_freshResultFirst_freshPrefillsThenPoppedEntryStaleResultIgnored() {
        // GUARD — passes pre- and post-fix; pins the inverse dispatch order, not the F1 mechanism.
        registerRecognitionService()
        val provider = VoiceLaunchFakeProvider()
        val vm = newVm(provider)
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val entryId = MutableStateFlow("A")
        val voiceRequested = MutableStateFlow(true)

        composeRule.setContent {
            val id by entryId.collectAsState()
            val req by voiceRequested.collectAsState()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                key(id) {
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
        }
        composeRule.waitForIdle()
        assertEquals(1, registry.launchedIntents.size) // A INITIAL gen1 slot0, hangs
        val rcA = registry.launchedRequestCodes[0]

        composeRule.runOnIdle { entryId.value = "C" }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("語音輸入").performClick()
        composeRule.waitForIdle()
        assertEquals(2, registry.launchedIntents.size)
        val rcC = registry.launchedRequestCodes[1]

        // 新鮮（C 的）結果先回：prefill。
        val freshResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("new transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcC, ActivityResult(Activity.RESULT_OK, freshResult))
        }
        composeRule.waitForIdle()
        assertEquals("new transcript", vm.input.value)

        // 舊 entry（A）的過期結果後回：草稿不得被覆寫。
        val staleResult = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS,
            arrayListOf("stale transcript"),
        )
        composeRule.runOnIdle {
            registry.dispatchResult(rcA, ActivityResult(Activity.RESULT_OK, staleResult))
        }
        composeRule.waitForIdle()
        assertEquals("new transcript", vm.input.value)
        assertEquals(0, provider.streamCalls)
    }
}
