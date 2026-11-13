package dev.librepocket.shell

import android.content.Context
import dev.librepocket.privilege.github.ShizukuGrantStore
import dev.librepocket.privilege.github.ShizukuPermissionRequests

object FlavorPrivilegeScope {
    suspend fun withdraw(context: Context) {
        var failure: Throwable? = null
        try {
            ShizukuGrantStore(context).clear()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            ShizukuPermissionRequests.process().invalidatePending()
        } catch (t: Throwable) {
            if (failure != null) {
                failure.addSuppressed(t)
            } else {
                failure = t
            }
        }
        if (failure != null) throw failure
    }
}
