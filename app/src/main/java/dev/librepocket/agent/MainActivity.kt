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
import dev.librepocket.agent.ui.MainScreen
import dev.librepocket.agent.ui.theme.LibrePocketTheme
import dev.librepocket.entry.EntryGate
import dev.librepocket.entry.EntryGateResult
import dev.librepocket.entry.EntryInput
import dev.librepocket.entry.EntryKind
import dev.librepocket.entry.EntryNormalize

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        sharedText = extractSharedTurnText(intent)
        enableEdgeToEdge()
        setContent {
            LibrePocketTheme {
                MainScreen(sharedText = sharedText, onSharedConsumed = { sharedText = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedTurnText(intent)?.let { text ->
            sharedText = text
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
                // No sender supplies sessionId yet; every share starts a new turn
                // in the current session (SessionTarget.New).
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
}
