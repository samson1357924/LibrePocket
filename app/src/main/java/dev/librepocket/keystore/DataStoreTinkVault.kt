package dev.librepocket.keystore

import android.content.Context
import com.google.crypto.tink.Aead

/**
 * Long-term vault (M2, spec §7.4): DataStore + Tink.
 *
 * P1 scope is a stub only: every operation throws [UnsupportedOperationException].
 * The real implementation (DataStore-Preferences holding Tink-AEAD ciphertext,
 * keyset in Android Keystore) lands in P2, along with the
 * `EncryptedPrefs → DataStoreTink` migration (see [KeyMigration]).
 */
class DataStoreTinkVault(
    @Suppress("unused") context: Context,
    @Suppress("unused") tinkAead: Aead,
) : KeyVault {
    override suspend fun putKey(providerId: String, apiKey: CharArray): Nothing =
        throw UnsupportedOperationException("P2: DataStoreTinkVault is not implemented in P1")

    override suspend fun getKey(providerId: String): CharArray? =
        throw UnsupportedOperationException("P2: DataStoreTinkVault is not implemented in P1")

    override suspend fun deleteKey(providerId: String): Unit =
        throw UnsupportedOperationException("P2: DataStoreTinkVault is not implemented in P1")

    override suspend fun hasKey(providerId: String): Boolean =
        throw UnsupportedOperationException("P2: DataStoreTinkVault is not implemented in P1")
}

/** Outcome of a [KeyMigration.migrate] run. */
data class MigrationResult(val migrated: Int)

/**
 * Migration helper (P1 placeholder surface for the future
 * `EncryptedPrefs → DataStoreTink` move, spec §7.4).
 *
 * Two-phase semantics: copy + verify **all** keys first, delete from the source
 * only after every read-back matches. Any failure aborts before any source
 * deletion, so an interrupted migration always retains the old store.
 */
object KeyMigration {
    suspend fun migrate(
        providerIds: List<String>,
        source: KeyVault,
        target: KeyVault,
        deleteSourceAfterVerify: Boolean = true,
    ): MigrationResult {
        val staged = LinkedHashMap<String, CharArray>()
        try {
            for (id in providerIds) {
                val key = source.getKey(id) ?: continue
                staged[id] = key
            }
            for ((id, key) in staged) {
                target.putKey(id, key)
                val readBack = target.getKey(id)
                try {
                    check(readBack != null && readBack.contentEquals(key)) {
                        "migration verify failed"
                    }
                } finally {
                    readBack?.fill('\u0000')
                }
            }
            if (deleteSourceAfterVerify) {
                for (id in staged.keys) {
                    source.deleteKey(id)
                }
            }
            return MigrationResult(staged.size)
        } finally {
            for (key in staged.values) {
                key.fill('\u0000')
            }
        }
    }
}
