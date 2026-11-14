package dev.librepocket.shell

import android.content.Context

object FlavorPrivilegeScope {
    suspend fun withdraw(context: Context) {
        // No-op on play flavor (zero privilege bridge runners)
    }
}
