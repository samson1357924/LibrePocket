package dev.librepocket.agent

import android.os.Bundle

/**
 * Activity-owned delivery state for a normalized external entry.
 *
 * The saved snapshot supports Activity recreation when Android supplies it; it
 * is not durable exactly-once state if the process dies before that snapshot is saved.
 */
internal data class EntryConsumptionState(
    val pendingText: String? = null,
) {
    fun accept(text: String): EntryConsumptionState = copy(pendingText = text)

    fun consume(): EntryConsumptionState =
        if (pendingText == null) this else copy(pendingText = null)

    fun saveTo(outState: Bundle) {
        outState.putBoolean(KEY_SNAPSHOT_PRESENT, true)
        outState.putString(KEY_PENDING_TEXT, pendingText)
    }

    companion object {
        private const val KEY_SNAPSHOT_PRESENT = "dev.librepocket.agent.entry.snapshot_present"
        private const val KEY_PENDING_TEXT = "dev.librepocket.agent.entry.pending_text"

        /** A present marker makes even a null pending text an authoritative consumed snapshot. */
        fun restore(savedInstanceState: Bundle?, launchText: String?): EntryConsumptionState {
            return if (savedInstanceState?.containsKey(KEY_SNAPSHOT_PRESENT) == true) {
                EntryConsumptionState(savedInstanceState.getString(KEY_PENDING_TEXT))
            } else {
                EntryConsumptionState(launchText)
            }
        }
    }
}
