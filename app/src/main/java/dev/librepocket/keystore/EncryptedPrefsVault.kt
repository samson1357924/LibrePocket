package dev.librepocket.keystore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Minimal storage seam behind [EncryptedPrefsVault].
 *
 * The product path uses [AndroidEncryptedPrefs] (EncryptedSharedPreferences).
 * Plain-JVM unit tests inject an in-memory fake instead, so vault logic
 * (key format, validation, wipe behavior) is testable without Android.
 */
interface PrefBackend {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
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
 * Durability, stated honestly (phase 1: document only, do not change):
 * - The product path ([AndroidEncryptedPrefs]) persists via `apply()` (async):
 *   the in-memory SharedPreferences cache is updated synchronously, but the
 *   fsync to disk happens later on a queued single-thread writer. An
 *   in-process re-read (same process, before any restart) sees the new value,
 *   which masks the gap; a process kill / device restart before the flush
 *   loses the write. Concretely: a save whose staged `putKey` is still only in
 *   memory when the metadata CAS commits reaches `READY` in-process but
 *   restarts to `NO_KEY` (metadata points at a missing alias); a logout whose
 *   `deleteKey` is still only in memory when the metadata clear commits looks
 *   fully torn down in-process but restarts with the key bytes revived as an
 *   unreferenced orphan (metadata gone, `hasKey` true). The plain-JVM
 *   [InMemoryPrefs] used by unit tests is fully synchronous
 *   (memory == durable) and therefore masks this gap — the `DeferredFlushPrefs`
 *   model double in tests exists precisely to demonstrate both cases without
 *   changing production behavior.
 * - Switching `apply()` to `commit()` (synchronous fsync) only narrows the
 *   window, it does not close it: there is still no distributed transaction
 *   between the vault write and the DataStore metadata CAS, so a kill landing
 *   between the two stores still splits them regardless of `commit()`. A
 *   `commit()` switch additionally needs Robolectric coverage (it exercises
 *   real SharedPreferences fsync semantics that plain-JVM fakes cannot prove),
 *   so this phase deliberately keeps `apply()` and only documents the gap.
 * - Long term (not this phase): a vault journal and/or a vault list API for
 *   orphan GC. Listing is currently unavailable by design (no enumeration of
 *   staged/live aliases), so crash orphans linger encrypted at rest until that
 *   follow-up lands. At-rest leak is strictly better than loss; the revision
 *   CAS still fences stale sessions across the window.
 */
class EncryptedPrefsVault(private val backend: PrefBackend) : KeyVault {

    constructor(context: Context) : this(AndroidEncryptedPrefs(context.applicationContext))

    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        val id = requireValidProviderId(providerId)
        val raw = String(apiKey)
        try {
            val key = raw.trim()
            require(key.length >= MIN_KEY_LENGTH) { "API key too short" }
            backend.put(prefsKey(id), key)
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
        backend.remove(prefsKey(id))
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
 * Uses `apply()` for both writes and deletes (see the durability note on
 * [EncryptedPrefsVault]): memory-vs-durable gap is documented there and
 * intentionally unchanged this phase. A future `commit()` switch must add
 * Robolectric coverage and still cannot close the cross-store window without
 * a journal/GC follow-up.
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

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}

/** In-memory [PrefBackend] for plain-JVM unit tests (never ships keys anywhere). */
class InMemoryPrefs : PrefBackend {
    private val map = LinkedHashMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = map[key]

    @Synchronized
    override fun put(key: String, value: String) {
        map[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    override fun contains(key: String): Boolean = map.containsKey(key)
}
