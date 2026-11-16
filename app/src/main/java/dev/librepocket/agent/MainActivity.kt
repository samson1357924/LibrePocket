package dev.librepocket.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.librepocket.agent.assistant.AssistantVoice
import dev.librepocket.agent.assistant.VoiceTaskIdentity
import dev.librepocket.agent.ui.MainScreen
import dev.librepocket.agent.ui.theme.LibrePocketTheme
import dev.librepocket.entry.EntryGate
import dev.librepocket.entry.EntryGateResult
import dev.librepocket.entry.EntryInput
import dev.librepocket.entry.EntryKind
import dev.librepocket.entry.EntryNormalize

open class MainActivity : ComponentActivity() {
    private val entryState = mutableStateOf(EntryConsumptionState())
    private var entryConsumptionState by entryState
    // C2 一次態 voice 請求（Issue #137）：須同時滿足系統可信語音互動根節點
    // （Activity.isVoiceInteractionRoot，經 isTrustedVoiceSession() 檢查）＋
    // ACTION_ASSIST＋非歷史重放（!FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY）＋
    // EXTRA_VOICE_INPUT=true 才會置 true；外部第三方偽造 intent 不具
    // isVoiceInteractionRoot，只走 EntryGate 開 App 而不自動彈 STT。
    // UI 消費（自動開系統辨識）即清回 false；旋轉時若 savedInstanceState 顯式含
    // KEY_ASSIST_VOICE 則須同時通過 isTrustedVoiceSession() 才還原（已消費不復活、
    // 待消費保留，非可信工作階段仍 fail-closed），否則再檢 isTrustedVoiceRequest(intent)。
    private val assistVoiceState = mutableStateOf(false)

    // C2 retrigger identity binding (Issue #137): when this activity hosts the trusted voice task,
    // bind retrigger delivery/consume to (taskId, sessionInstanceId). The normal-task MainActivity
    // instance sees null here, so it can never steal the one-shot STT retrigger.
    // Plain var, not Compose state: it is never read during composition (it is only passed to
    // installMainScreenContent once at onCreate), so it needs no snapshot observation.
    private var assistVoiceIdentity: VoiceTaskIdentity? = null

    /**
     * 檢查目前 Activity 是否處於系統可信語音互動工作階段根節點
     * （由 `VoiceInteractionSession.startVoiceActivity` 啟動時由系統授予）。
     * 外部第三方偽造 `ACTION_ASSIST + EXTRA_VOICE_INPUT` 不具此狀態，只開 App 不自動彈 STT；
     * 測試子類別可覆寫以模擬系統可信喚起與第三方偽造情境。
     */
    protected open fun isTrustedVoiceSession(): Boolean = isVoiceInteractionRoot

    /**
     * Test seam: Robolectric cannot set [taskId]; production reads the real task id.
     * The value binds retrigger delivery/consume to this activity's task.
     */
    protected open fun currentTaskId(): Int = taskId

    private fun isTrustedVoiceRequest(intent: Intent?): Boolean =
        isTrustedVoiceSession() && AssistantVoice.isVoiceInputRequested(intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        entryConsumptionState = EntryConsumptionState.restore(
            savedInstanceState = savedInstanceState,
            launchText = extractSharedTurnText(intent),
        )
        assistVoiceState.value =
            if (savedInstanceState?.containsKey(KEY_ASSIST_VOICE) == true) {
                isTrustedVoiceSession() && savedInstanceState.getBoolean(KEY_ASSIST_VOICE, false)
            } else {
                isTrustedVoiceRequest(intent)
            }
        assistVoiceIdentity = computeVoiceIdentity(intent, savedInstanceState)
        enableEdgeToEdge()
        installMainScreenContent(
            sharedText = { entryConsumptionState.pendingText },
            onSharedConsumed = { entryConsumptionState = entryConsumptionState.consume() },
            voiceInputRequested = { assistVoiceState.value },
            onVoiceInputConsumed = { assistVoiceState.value = false },
            voiceTaskIdentity = assistVoiceIdentity,
        )
    }

    /** Installs the production screen; overridable so lifecycle tests can fake dispatch. */
    internal open fun installMainScreenContent(
        sharedText: () -> String?,
        onSharedConsumed: () -> Unit,
        voiceInputRequested: () -> Boolean = { false },
        onVoiceInputConsumed: () -> Unit = {},
        voiceTaskIdentity: VoiceTaskIdentity? = null,
    ) {
        setContent {
            LibrePocketTheme {
                MainScreen(
                    sharedText = sharedText(),
                    onSharedConsumed = onSharedConsumed,
                    assistVoiceRequested = voiceInputRequested(),
                    onAssistVoiceConsumed = onVoiceInputConsumed,
                    voiceTaskIdentity = voiceTaskIdentity,
                )
            }
        }
    }

    /**
     * Binds this activity's identity when it hosts a trusted voice session: requires the
     * trusted voice-interaction root plus a session-instance nonce from the launch intent
     * (or restored saved state). Returns null for the normal-task instance and for any
     * session-less launch — retrigger delivery is impossible there (fail-closed).
     *
     * Note: taskId is deliberately NOT persisted in onSaveInstanceState (only the sid is).
     * After process death the recreated activity may land in a *different* task; the restored
     * identity is then bound to the new currentTaskId(), which never matches the taskId the
     * session published its retrigger tokens for — so delivery fails closed (and an untrusted
     * restore yields identity == null, i.e. retrigger is impossible). The voice task's own
     * recreation into the same task is the expected restore path.
     */
    private fun computeVoiceIdentity(intent: Intent?, saved: Bundle?): VoiceTaskIdentity? {
        if (!isTrustedVoiceSession()) return null
        val savedSid = saved?.getLong(KEY_ASSIST_SID, 0L) ?: 0L
        val sid = if (savedSid != 0L) {
            savedSid
        } else {
            intent?.getLongExtra(AssistantVoice.EXTRA_SESSION_INSTANCE_ID, 0L) ?: 0L
        }
        if (sid == 0L) return null
        return VoiceTaskIdentity(currentTaskId(), sid)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        entryConsumptionState.saveTo(outState)
        outState.putBoolean(KEY_ASSIST_VOICE, assistVoiceState.value)
        outState.putLong(KEY_ASSIST_SID, assistVoiceIdentity?.sessionInstanceId ?: 0L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Voice auto-launch is only armed on initial trusted creation/handoff
        // (onCreate launch intent) or trusted saved-state restore. Subsequent
        // onNewIntent deliveries do not carry verified per-intent voice provenance,
        // so they must never re-arm pending assist voice (including trusted-root
        // reuse or external/unverified-shaped intents). Non-voice intents must
        // still not clear pending voice.
        extractSharedTurnText(intent)?.let { text ->
            entryConsumptionState = entryConsumptionState.accept(text)
        }
    }

    /**
     * D06 entry pipeline (thin Android layer): intent -> [EntryInput] ->
     * [EntryGate] -> [EntryNormalize] -> normalized text. Dispatch
     * (send-now vs steer) happens in [MainScreen], which owns the busy state.
     * ASSIST carries no text and just opens the app; background intents without
     * a user gesture are stashed, never auto-sent.
     */
    private fun extractSharedTurnText(intent: Intent?): String? {
        val input = toEntryInput(intent) ?: return null
        if (EntryGate.check(input) != EntryGateResult.Allowed) return null
        return try {
            EntryNormalize.normalize(input).text
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun toEntryInput(intent: Intent?): EntryInput? {
        if (intent == null) return null
        return when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> EntryInput(
                kind = EntryKind.SHARE,
                rawText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                shareTitle = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
                // No sender supplies sessionId yet, so normalize() always yields
                // SessionTarget.New (Resume is future work); the VM reuses the
                // live session and only the normalized text travels downstream.
                sessionId = null,
                isForeground = true,
                hasUserGesture = true,
            )
            intent.action == Intent.ACTION_ASSIST -> EntryInput(
                kind = EntryKind.ASSIST,
                isForeground = true,
                hasUserGesture = true,
            )
            else -> null
        }
    }

    companion object {
        private const val KEY_ASSIST_VOICE = "dev.librepocket.agent.assist_voice"
        private const val KEY_ASSIST_SID = "dev.librepocket.agent.assist_session_instance_id"
    }
}
