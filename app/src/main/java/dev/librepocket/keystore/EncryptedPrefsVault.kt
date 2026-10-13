package dev.librepocket.keystore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Minimal storage seam behind [EncryptedPrefsVault].
 *
 * The product path uses [AndroidEncryptedPrefs] (EncryptedSharedPreferences).
 * Plain-JVM unit tests inject an in-memory fake instead, so vault logic
 * (key format, validation, wipe behavior) is testable without Android.
 *
 * Durability contract (Stage R1): [putDurable]/[removeDurable] must be
 * synchronously flushed before returning `true`. A `false` return (or a
 * throw) means "not durable" — callers fail closed and never report success.
 * There is intentionally no fire-and-forget `put`/`remove`: every production
 * write goes through the ACK-checked path so "key durable → publish metadata"
 * (save) and "Journal durable → deletion durable → finalize" (logout) hold.
 */
interface PrefBackend {
    fun get(key: String): String?
    fun putDurable(key: String, value: String): Boolean
    fun removeDurable(key: String): Boolean
    fun contains(key: String): Boolean
}

/**
 * P1 shipping vault (M2, spec §7.3): EncryptedSharedPreferences wrapper.
 *
 * - Master-key alias `librepocket_master` (AES256_GCM).
 * - Prefs file `librepocket_keys` (keys AES256_SIV, values AES256_GCM).
 * - Entry key `provider_key/<providerId>`.
 *
 * Key rules (spec §7.2): trimmed before write, minimum length 8, blank rejected.
 * The caller's [CharArray] is wiped in `finally` after the write.
 *
 * Durability, stated honestly (Stage R1: durable ACK enforced):
 * - The product path ([AndroidEncryptedPrefs]) persists via `commit()`
 *   (synchronous fsync) and checks the result: `false`/throw fails closed
 *   (save aborts before the metadata CAS; logout keeps the journal and
 *   reports `LOGOUT_DELETE_FAILED`). The ordering is therefore
 *   key-durable → publish (save) and Journal-durable → deletion-durable →
 *   finalize (logout); an unknown outcome never reports success.
 * - `commit()` narrows the window, it does not close it: there is still no
 *   distributed transaction between the vault write and the DataStore
 *   metadata CAS, so a kill landing between the two stores still splits them.
 *   A crash between put-durable and CAS (or delete-durable and clear) leaves
 *   an unreferenced staged/retiring alias at rest: without a vault list API
 *   it cannot be enumerated for GC, so it lingers encrypted until that
 *   follow-up lands (staged-alias registry + startup reconcile GC, not this
 *   phase). At-rest leak is strictly better than loss; the revision CAS still
 *   fences stale sessions across the window.
 * - The plain-JVM [InMemoryPrefs] used by unit tests is fully synchronous
 *   (memory == durable) and `putDurable`/`removeDurable` report `true`
 *   honestly. A `commit() → real fsync` proof needs Robolectric/device
 *   coverage (plain-JVM fakes cannot prove fsync); the ACK-check wiring
 *   (false/throw → abort, zero publish, journal kept) is covered by plain-JVM
 *   fault-injection tests. `commit()` runs on the caller's dispatcher —
 *   all production vault writes already run on Dispatchers.IO paths, never
 *   on Main.
 */
class EncryptedPrefsVault(private val backend: PrefBackend) : KeyVault {

    constructor(context: Context) : this(AndroidEncryptedPrefs(context.applicationContext))

    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        val id = requireValidProviderId(providerId)
        val raw = String(apiKey)
        try {
            val key = raw.trim()
            require(key.length >= MIN_KEY_LENGTH) { "API key too short" }
            val durable = try {
                backend.putDurable(prefsKey(id), key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("VAULT_DURABILITY_FAILED", e)
            }
            if (!durable) throw IOException("VAULT_DURABILITY_FAILED")
        } finally {
            apiKey.fill('\u0000')
        }
    }

    override suspend fun getKey(providerId: String): CharArray? {
        val id = requireValidProviderId(providerId)
        return backend.get(prefsKey(id))?.toCharArray()
    }

    override suspend fun deleteKey(providerId: String) {
        val id = requireValidProviderId(providerId)
        val durable = try {
            backend.removeDurable(prefsKey(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("VAULT_DURABILITY_FAILED", e)
        }
        if (!durable) throw IOException("VAULT_DURABILITY_FAILED")
    }

    override suspend fun hasKey(providerId: String): Boolean {
        val id = requireValidProviderId(providerId)
        return backend.contains(prefsKey(id))
    }

    companion object {
        const val PREFS_FILE = "librepocket_keys"
        const val MASTER_KEY_ALIAS = "librepocket_master"
        const val MIN_KEY_LENGTH = 8
        private const val KEY_PREFIX = "provider_key/"

        fun prefsKey(providerId: String): String = KEY_PREFIX + providerId

        fun requireValidProviderId(providerId: String): String {
            require(providerId.isNotBlank()) { "providerId must not be blank" }
            require('\u0000' !in providerId) { "providerId contains NUL" }
            return providerId
        }
    }
}

/** Real [PrefBackend] over EncryptedSharedPreferences (product path).
 *
 * Uses `commit()` (synchronous fsync) for both writes and deletes and reports
 * the result (see the durability note on [EncryptedPrefsVault]): `false`
 * fails the vault op fail-closed upstream. `commit()` performs disk I/O on
 * the calling thread — all production vault calls already run off-Main.
 */
class AndroidEncryptedPrefs(context: Context) : PrefBackend {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context, EncryptedPrefsVault.MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            EncryptedPrefsVault.PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun putDurable(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()

    override fun removeDurable(key: String): Boolean =
        prefs.edit().remove(key).commit()

    override fun contains(key: String): Boolean = prefs.contains(key)
}

/** In-memory [PrefBackend] for plain-JVM unit tests (never ships keys anywhere).
 *
 * Fully synchronous (memory == durable): [putDurable]/[removeDurable] apply
 * immediately and honestly report `true`.
 */
class InMemoryPrefs : PrefBackend {
    private val map = LinkedHashMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = map[key]

    @Synchronized
    override fun putDurable(key: String, value: String): Boolean {
        map[key] = value
        return true
    }

    @Synchronized
    override fun removeDurable(key: String): Boolean {
        map.remove(key)
        return true
    }

    @Synchronized
    override fun contains(key: String): Boolean = map.containsKey(key)
}
