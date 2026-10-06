package dev.librepocket.agent.full

import android.content.Intent
import android.net.VpnService

/**
 * Full-flavor placeholder. Declared so the merged full manifest is valid;
 * intentionally establishes no tunnel. Real functionality is future work.
 */
class FullVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
