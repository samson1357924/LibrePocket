package dev.librepocket.agent

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder launcher activity proving the flavored APKs install and start.
 * Real UI is future work.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "LibrePocket skeleton"
            },
        )
    }
}
