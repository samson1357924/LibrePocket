package dev.librepocket.keystore

/**
 * Encrypted API-key vault (M2, spec §1.3 / §7).
 *
 * Implementations must never expose key length or content through [hasKey],
 * logs, or exceptions. [getKey] callers must wipe the returned array in a
 * `finally` block once the key has been consumed.
 */
interface KeyVault {
    /** Write/overwrite the key for [providerId] (input array is wiped afterwards). */
    suspend fun putKey(providerId: String, apiKey: CharArray)

    /** Read the key; caller must wipe the returned array in `finally`. Null if absent. */
    suspend fun getKey(providerId: String): CharArray?

    /** Delete the key (logout / provider rotation). */
    suspend fun deleteKey(providerId: String)

    /** Whether a key exists (boolean only; reveals nothing about length/content). */
    suspend fun hasKey(providerId: String): Boolean
}
