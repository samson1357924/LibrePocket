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

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        sharedText = extractSharedText(intent)
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
        extractSharedText(intent)?.let { text ->
            sharedText = text
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        // D06 entry: SEND(text/plain) normalizes to a UserTurn prefill; ASSIST just opens.
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        }
        return null
    }
}
