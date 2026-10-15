package dev.librepocket.agent.ui.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.librepocket.keystore.KeyVault
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.validateBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val Context.endpointDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "librepocket_endpoint",
)

// Versioned keys: bump the suffix (and migrate) when fields change.
internal val ENDPOINT_PROVIDER_ID_V1 = stringPreferencesKey("endpoint_provider_id_v1")
internal val ENDPOINT_PRESET_ID_V1 = stringPreferencesKey("endpoint_preset_id_v1")
internal val ENDPOINT_LABEL_V1 = stringPreferencesKey("endpoint_label_v1")
internal val ENDPOINT_BASE_URL_V1 = stringPreferencesKey("endpoint_base_url_v1")
internal val ENDPOINT_PROTOCOL_V1 = stringPreferencesKey("endpoint_protocol_v1")
internal val ENDPOINT_MODEL_V1 = stringPreferencesKey("endpoint_model_v1")
internal val ENDPOINT_API_KEY_REF_V1 = stringPreferencesKey("endpoint_api_key_ref_v1")
internal val ENDPOINT_CONFIG_REVISION_V1 = longPreferencesKey("endpoint_config_revision_v1")
internal val ENDPOINT_PENDING_DELETE_V1 = stringPreferencesKey("endpoint_pending_delete_v1")

/**
 * Crash-resumable logout teardown journal (phase 1, Re-review #8 Finding 1 P1).
 *
 * Written by the logout path BEFORE `KeyVault.deleteKey(alias)`, removed
 * atomically with the fenced metadata clear on success. A crash or cancel
 * landing between the vault delete and the metadata clear leaves this journal
 * behind; the next startup ([SetupViewModel.reconcilePendingDelete]) or logout
 * retry replays it (idempotent delete + fenced clear) instead of silently
 * keeping torn-down metadata nobody discovers.
 *
 * Wire format `"v1|<alias>|<rev>|<ts>"`, where `<rev>` is empty for a fresh
 * `null` revision and `<ts>` is `System.currentTimeMillis()`. Parsing is
 * strict: any malformed non-null value fails closed (throws
 * [IllegalStateException], readers fold to unavailable, reconcile keeps the
 * journal untouched). The alias MUST NOT contain `|` (construction rejects it;
 * a dirty alias that cannot be journaled fails the logout closed before any
 * side effect).
 *
 * The journal NEVER advances [ENDPOINT_CONFIG_REVISION_V1]: only
 * [EndpointStore.save]/[saveIfRevision]/[clear]/[clearIfRevision] advance the
 * revision. A present journal BLOCKS [saveIfRevision] fail-closed inside the
 * same atomic edit (Stage 2 P2 atomic fence): saves can no longer CAS over a
 * pending teardown and silently strand it — the blocked save reports
 * `false`, writes nothing, and the caller must reconcile-then-retry instead.
 * (The legacy unconditional [EndpointStore.save] still overwrites; it is kept
 * for tests / non-fencing paths only.)
 */
data class PendingDelete(
    val alias: String,
    val revision: Long?,
    val timestampMs: Long,
) {
    init {
        require(alias.isNotBlank()) { "PENDING_DELETE_ALIAS_BLANK" }
        require('|' !in alias && '\n' !in alias && '\r' !in alias && '\u0000' !in alias) {
            "PENDING_DELETE_ALIAS_MALFORMED"
        }
        require(timestampMs >= 0) { "PENDING_DELETE_TS_MALFORMED" }
    }

    fun format(): String = "v1|$alias|${revision?.toString() ?: ""}|$timestampMs"

    companion object {
        /**
         * Strict parse. Throws [IllegalStateException] on ANY malformed input
         * (wrong version, wrong arity, blank/`|`-bearing alias, non-numeric
         * rev/ts, negative ts). Callers MUST treat a throw as fail-closed
         * (no vault I/O, no metadata write, journal kept).
         */
        fun parse(raw: String): PendingDelete {
            fun malformed(): Nothing = throw IllegalStateException("ENDPOINT_PENDING_DELETE_MALFORMED")
            val parts = raw.split('|')
            if (parts.size != 4 || parts[0] != "v1") malformed()
            val alias = parts[1]
            if (alias.isBlank() || '\n' in alias || '\r' in alias || '\u0000' in alias) malformed()
            val revision = if (parts[2].isEmpty()) {
                null
            } else {
                parts[2].toLongOrNull() ?: malformed()
            }
            val ts = parts[3].toLongOrNull() ?: malformed()
            if (ts < 0) malformed()
            return PendingDelete(alias, revision, ts)
        }
    }
}

/**
 * Teardown domain state for the pending-delete journal (Stage E R13 P2/BLOCK).
 *
 * Four states replace the Stage D2 `UNKNOWN_PRESENT` sentinel (deleted: one
 * shared non-null [PendingDelete] for malformed journals AND read failures, so
 * a transient read failure with NO journal still derived `LOGOUT_UNKNOWN`
 * retry-clear — phantom pending with a destructive-looking recovery):
 * - [Absent]: healthy read, no journal — definitely nothing pending;
 * - [KnownPending]: well-formed journal — confirmed present, resume it;
 * - [MalformedPending]: non-null journal failing strict parse — present
 *   fail-closed, never a usable journal identity (no vault/clear path may use
 *   it for delete or compare-and-delete);
 * - [UnknownReadFailure]: the DataStore read itself threw — presence unknown,
 *   fail-closed as present (never [Absent]).
 *
 * Only [CancellationException][kotlinx.coroutines.CancellationException]
 * propagates; every other read/parse failure folds to a non-absent state
 * (fail-closed: folding to [Absent] would hide a retained journal).
 */
sealed interface PendingTeardownState {
    data object Absent : PendingTeardownState
    data class KnownPending(val pending: PendingDelete) : PendingTeardownState
    data object UnknownReadFailure : PendingTeardownState
    data object MalformedPending : PendingTeardownState
}

/** Usability verdict for the endpoint gate (log the code only, never key material). */
enum class EndpointReadiness {
    READY,
    NO_ENDPOINT_METADATA,
    INVALID_BASE_URL,
    NO_KEY,
}

/**
 * Active BYOK endpoint metadata (single-endpoint MVP; multi-endpoint is P2).
 *
 * Never carries the plaintext key: the key itself lives in
 * [dev.librepocket.keystore.KeyVault] under the alias addressed by [apiKeyRef]
 * (form `provider_key/<alias>`), resolved via
 * [SetupValidation.aliasFromRef]. Fresh/legacy metadata points at the fixed
 * live alias (`provider_key/<providerId>`); saves committed through the
 * staged scheme point at a per-operation restart-unique staged alias
 * (`provider_key/<providerId>#staged-<uuid32>`, see
 * [SetupValidation.newStagedAlias]). Readers MUST resolve the alias
 * from [apiKeyRef] and MUST NOT re-derive it from [providerId]: after a staged
 * commit the live alias and the providerId-derived alias differ.
 * [providerId] is the stable per-endpoint entity id (Stage1):
 * `preset:<presetId>` for built-in presets (fixed catalog URL, unchanged),
 * `custom:<sha256-32hex>` derived per custom baseUrl (see
 * [SetupValidation.deriveEndpointId]). Pre-Stage1 custom rows stored the
 * shared `preset:custom` id (see [SetupValidation.isLegacyEndpoint]) and
 * must be re-keyed, never silently reused. The local
 * [configRevision] is atomically advanced by [EndpointStore.save]/[clear] and
 * is for stale-session fencing, not endpoint identity or vault-write atomicity.
 */
data class EndpointConfig(
    val providerId: String,
    val presetId: String,
    val label: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val model: String,
    val apiKeyRef: String,
    /**
     * Monotonic metadata revision, assigned atomically by [EndpointStore].
     *
     * WARNING: fencing callers MUST source the expected revision from
     * [EndpointStore.currentRevision()], never `observe().configRevision`.
     * [observe] folds missing/partial metadata (including the post-clear
     * tombstone) to `null`, and folds a missing revision key on present
     * metadata to `0L`, so reusing its value conflates fresh `null` with
     * dirty `0L` and loses the tombstone revision.
     */
    val configRevision: Long = 0L,
)

/**
 * Persists the active endpoint metadata. Shape mirrors
 * [dev.librepocket.policy.DataStorePolicyStore] conventions: dual constructors,
 * injectable [ioDispatcher], fail-closed reads.
 *
 * Revision model:
 * - `null` means fresh: [ENDPOINT_CONFIG_REVISION_V1] was never written.
 * - `0L` means a dirty/hand-written value (only reachable via a direct
 *   `dataStore.edit` that bypasses [save]/[saveIfRevision]/[clear]/[clearIfRevision]);
 *   it is distinct from fresh `null` and never produced by the store itself.
 * - Every successful [save]/[saveIfRevision]/[clear]/[clearIfRevision] advances
 *   the revision by exactly one via `Math.addExact`, so overflow fails closed with
 *   [ArithmeticException] instead of wrapping.
 *
 * CAS notes:
 * - [currentRevision] exposes the raw stored revision (including the value
 *   across [clear], where [observe] folds to `null`); it never catches, so
 *   DataStore failures propagate fail-closed to the caller.
 * - [saveIfRevision]/[clearIfRevision] perform the compare-and-set inside a
 *   single `dataStore.edit {}` transaction; a mismatch `return@edit`s without
 *   writing and reports `false`. [saveIfRevision] additionally requires the
 *   teardown journal to be absent in the SAME edit (Stage 2 P2 fence), and the
 *   fenced [writePendingDeleteIfAbsent] overload requires the metadata
 *   generation to still match — so neither a save nor a stale logout can slip
 *   past the other's in-flight state. There is no distributed transaction with [KeyVault]:
 *   callers (VM layer, phase 2) must coordinate metadata fencing with vault
 *   writes/reads.
 * - Single-process only; not safe across processes: DataStore is not
 *   multi-process safe, so the CAS only fences threads/coroutines in this process.
 */
class EndpointStore(
    internal val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.endpointDataStore)

    /**
     * Lenient UI read. Folds DataStore failures to `null` (via `catch`) and
     * folds missing/partial metadata (including the post-[clear] tombstone) to
     * `null`.
     *
     * A non-null [ENDPOINT_PENDING_DELETE_V1] journal (even a malformed one)
     * also folds to `null`: a logout teardown is in flight (vault delete won,
     * metadata clear pending) and the endpoint is conservatively unavailable
     * until the next startup/logout replay resolves it. Fencing/reconcile
     * readers MUST use [snapshotStrict]/[snapshotDeleteStateStrict]/
     * [pendingDelete] instead of relying on this fold.
     *
     * ONLY for UI gate/[StateFlow] display ([readiness], [hasMetadata], gate).
     * FORBIDDEN for deleteKey/restore authorization: destructive decisions MUST
     * use [snapshotStrict] (or [snapshotWithRevisionStrict]), never this.
     * Lenient `null` conflates transient failure with absent winner metadata
     * and would mis-authorize an orphan delete.
     */
    fun observe(): Flow<EndpointConfig?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            if (prefs[ENDPOINT_PENDING_DELETE_V1] != null) null else parseConfig(prefs)
        }

    /**
     * Stage R2 strict gate source (no catch-folding).
     *
     * Same mapping as [observe] but read failures rethrow instead of folding to
     * `emptyPreferences()` → `null`. Lets [SetupViewModel.gate] distinguish a
     * proven Absent (`null` from a successful read) from an unconfirmed
     * ReadFailure (exception → `EndpointGate.ReadFailure`), so a transient
     * failure never masquerades as NoEndpoint. Existing [observe] callers
     * (chat, lenient readers) are untouched.
     */
    fun observeStrict(): Flow<EndpointConfig?> = dataStore.data
        .map { prefs ->
            if (prefs[ENDPOINT_PENDING_DELETE_V1] != null) null else parseConfig(prefs)
        }

    /**
     * Stage E four-state teardown domain read (R13 Finding P2/BLOCK).
     *
     * Derives [PendingTeardownState] from the single
     * [ENDPOINT_PENDING_DELETE_V1] key (zero vault I/O):
     * - absent → [PendingTeardownState.Absent];
     * - well-formed → [PendingTeardownState.KnownPending];
     * - malformed non-null → [PendingTeardownState.MalformedPending] (present
     *   fail-closed, never a usable journal identity);
     * - DataStore read failure → [PendingTeardownState.UnknownReadFailure] via
     *   `catch` (unknown即present — a `catch` folding to `Absent` would hide a
     *   retained journal; only [CancellationException] rethrows).
     *
     * The inner flow completes after a `catch` emission, so a transient
     * failure sticks until the next collection: holder flows MUST re-collect
     * to recover (see `SetupViewModel.pendingTeardown` tick — the store stays
     * the single source of truth, no manual write-back). A missed re-collect
     * only leaves phantom present (fail-closed — retry-read, never silent
     * fresh).
     *
     * Presence is no longer one boolean: [PendingTeardownState.KnownPending]
     * (confirmed) derives retained retry-clear recovery, while
     * [PendingTeardownState.UnknownReadFailure] and
     * [PendingTeardownState.MalformedPending] (unconfirmed) derive
     * retry-read-only (see
     * [dev.librepocket.agent.ui.effectiveLogoutGuardError]). Safe reconcile
     * reuses the strict snapshot + `runPendingDeleteLocked` core (never a
     * domain state for vault/clear paths).
     */
    fun observePendingDelete(): Flow<PendingTeardownState> = dataStore.data
        .map { prefs ->
            val raw = prefs[ENDPOINT_PENDING_DELETE_V1]
            if (raw == null) {
                PendingTeardownState.Absent
            } else {
                try {
                    PendingTeardownState.KnownPending(PendingDelete.parse(raw))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PendingTeardownState.MalformedPending
                }
            }
        }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(PendingTeardownState.UnknownReadFailure)
        }

    private fun parseConfig(prefs: Preferences): EndpointConfig? {
        val providerId = prefs[ENDPOINT_PROVIDER_ID_V1] ?: return null
        val baseUrl = prefs[ENDPOINT_BASE_URL_V1] ?: return null
        val apiKeyRef = prefs[ENDPOINT_API_KEY_REF_V1] ?: return null
        val protocolName = prefs[ENDPOINT_PROTOCOL_V1] ?: return null
        if (providerId.isBlank() || baseUrl.isBlank() || apiKeyRef.isBlank()) return null
        val protocol = try {
            ProviderProtocol.valueOf(protocolName)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return EndpointConfig(
            providerId = providerId,
            presetId = prefs[ENDPOINT_PRESET_ID_V1].orEmpty(),
            label = prefs[ENDPOINT_LABEL_V1].orEmpty(),
            baseUrl = baseUrl,
            protocol = protocol,
            model = prefs[ENDPOINT_MODEL_V1].orEmpty(),
            apiKeyRef = apiKeyRef,
            configRevision = prefs[ENDPOINT_CONFIG_REVISION_V1] ?: 0L,
        )
    }

    /**
     * Strict read for destructive authorization (deleteKey/restore gating).
     *
     * Reads one `dataStore.data.first()` emission and parses directly WITHOUT
     * `catch`: DataStore failures propagate fail-closed to the caller, which
     * MUST treat any [Exception] ([CancellationException] rethrown) as
     * no-touch. Unlike [observe], a transient failure never folds to `null`,
     * so a missing-winner `null` is trustworthy (healthy read of absent
     * metadata) rather than a failure masquerading as absent.
     *
     * Same level as [currentRevision]: raw, fail-closed, single emission.
     *
     * Does NOT fold a pending-delete journal: during a logout teardown the raw
     * config (live alias + revision) stays visible here so the fencing and
     * reconcile paths can authorize the delete and the trailing
     * `clearIfRevision`. Lenient readers ([observe]) fold instead.
     */
    suspend fun snapshotStrict(): EndpointConfig? = withContext(ioDispatcher) {
        val prefs = dataStore.data.first()
        parseConfig(prefs)
    }

    /**
     * Strict single-emission read of revision + config together.
     *
     * Collects ONE `dataStore.data.first()` emission and derives both values
     * from the same [Preferences] snapshot, eliminating the TOCTOU window
     * between separate [currentRevision] + [snapshotStrict] collections (a
     * concurrent writer landing between two reads could otherwise advance the
     * revision after the first read but before the second).
     *
     * Does NOT fold a pending-delete journal (see [snapshotDeleteStateStrict]
     * for the journal-aware variant): fencing readers that need the live
     * alias/revision during a teardown must see the raw values.
     *
     * Fail-closed like [snapshotStrict]: no `catch`, failures propagate.
     * Stage 1 callers still use the two-read sequence (documented window);
     * stage 2 should migrate fenced paths to this.
     */
    suspend fun snapshotWithRevisionStrict(): Pair<EndpointConfig?, Long?> =
        withContext(ioDispatcher) {
            val prefs = dataStore.data.first()
            parseConfig(prefs) to prefs[ENDPOINT_CONFIG_REVISION_V1]
        }

    /**
     * Strict single-emission read of the teardown journal.
     *
     * Returns `null` when no journal is present. A malformed non-null journal
     * throws [IllegalStateException] (fail-closed: the caller MUST NOT touch
     * the vault or metadata and MUST keep the journal). DataStore failures
     * propagate like [snapshotStrict].
     */
    suspend fun pendingDelete(): PendingDelete? = withContext(ioDispatcher) {
        val raw = dataStore.data.map { prefs -> prefs[ENDPOINT_PENDING_DELETE_V1] }.first()
        raw?.let { PendingDelete.parse(it) }
    }

    /**
     * Strict single-emission read of (journal, config, revision) together.
     *
     * All three values come from the SAME [Preferences] snapshot, so the
     * logout/reconcile path cannot observe a journal from one generation
     * against metadata from another. Fail-closed like [snapshotStrict]: no
     * `catch` — DataStore failures AND malformed journals
     * ([IllegalStateException]) propagate and the caller MUST abort without
     * side effects.
     */
    suspend fun snapshotDeleteStateStrict(): Triple<PendingDelete?, EndpointConfig?, Long?> =
        withContext(ioDispatcher) {
            val prefs = dataStore.data.first()
            val raw = prefs[ENDPOINT_PENDING_DELETE_V1]
            Triple(
                raw?.let { PendingDelete.parse(it) },
                parseConfig(prefs),
                prefs[ENDPOINT_CONFIG_REVISION_V1],
            )
        }

    /**
     * Atomically journals a pending teardown unless one is already present.
     *
     * Inside a single `dataStore.edit {}` transaction: a present journal (any
     * non-null value) is NEVER overwritten — returns `false` fail-closed so
     * the caller resumes the existing journal instead of clobbering it. Absent
     * journal → writes [pending] (validated by construction, [format]) and
     * returns `true`.
     *
     * This overload checks journal absence ONLY (no revision/ref fencing):
     * kept for tests/preseed. Production logout MUST use the fenced overload
     * below so a stale snapshot cannot journal over — and temporarily mask —
     * a newer generation's committed metadata.
     *
     * Intentionally does NOT advance [ENDPOINT_CONFIG_REVISION_V1]: the journal
     * is not a metadata generation, and bumping here would break the trailing
     * `clearIfRevision(expectedRevision)` fence. Fail-closed: validation
     * ([IllegalArgumentException]) and DataStore failures propagate; only the
     * already-journaled contention path returns `false`.
     */
    suspend fun writePendingDeleteIfAbsent(pending: PendingDelete): Boolean {
        // Validate before I/O (constructor already validated; format is pure).
        pending.format()
        return withContext(ioDispatcher) {
            var wrote = false
            dataStore.edit { prefs ->
                if (prefs[ENDPOINT_PENDING_DELETE_V1] != null) {
                    return@edit
                }
                prefs[ENDPOINT_PENDING_DELETE_V1] = pending.format()
                wrote = true
            }
            wrote
        }
    }

    /**
     * Fenced journal write (Stage 2 P2 atomic fence, scheme B).
     *
     * Like the absent-only overload, but additionally requires the stored
     * metadata generation to still match the caller's snapshot: inside the
     * SAME single `dataStore.edit {}` transaction the write proceeds only when
     * the journal is absent AND `prefs[ENDPOINT_CONFIG_REVISION_V1] ==
     * [expectedRevision]` AND ([expectedRef] is null OR
     * `prefs[ENDPOINT_API_KEY_REF_V1] == [expectedRef]`). Any mismatch
     * `return@edit`s with `false` — nothing is written, so a stale logout
     * whose snapshot predates a winner's commit can neither journal over it
     * nor (via the lenient fold) temporarily mask the winner as unavailable.
     * A present journal (any non-null value, including malformed) likewise
     * returns `false` and is never overwritten.
     *
     * @param expectedRevision MUST come from the same snapshot emission as the
     * journal absence check ([snapshotDeleteStateStrict]), never a stale
     * re-read: the fence only holds against the generation observed at
     * snapshot time.
     * @param expectedRef the snapshot's raw `apiKeyRef` (e.g.
     * `provider_key/<alias>`), or null to fence on the revision only.
     *
     * Fail-closed: [pending] validation ([IllegalArgumentException]) and
     * DataStore failures propagate; only the expected contention paths
     * (already-journaled, revision/ref moved) return `false`. The caller MUST
     * treat `false` as STALE-or-superseded (re-snapshot; report STALE and skip
     * the vault delete when the generation moved, resume the present journal
     * otherwise) — never as "retry the write".
     */
    suspend fun writePendingDeleteIfAbsent(
        pending: PendingDelete,
        expectedRevision: Long?,
        expectedRef: String? = null,
    ): Boolean {
        // Validate before I/O (constructor already validated; format is pure).
        pending.format()
        return withContext(ioDispatcher) {
            var wrote = false
            dataStore.edit { prefs ->
                if (prefs[ENDPOINT_PENDING_DELETE_V1] != null) {
                    return@edit
                }
                if (prefs[ENDPOINT_CONFIG_REVISION_V1] != expectedRevision) {
                    return@edit
                }
                if (expectedRef != null && prefs[ENDPOINT_API_KEY_REF_V1] != expectedRef) {
                    return@edit
                }
                prefs[ENDPOINT_PENDING_DELETE_V1] = pending.format()
                wrote = true
            }
            wrote
        }
    }

    /**
     * Conditional discard of a superseded teardown journal (STALE path only).
     *
     * Inside a single `dataStore.edit {}` transaction compares the stored
     * [ENDPOINT_PENDING_DELETE_V1] raw string against [expected]
     * ([PendingDelete.format]): match → removes ONLY the journal key (winner
     * metadata and the revision survive byte-for-byte) and returns `true`;
     * mismatch (journal already replaced by a newer generation, or already
     * gone) → writes nothing and returns `false`.
     *
     * Raw-string compare covers alias+revision+timestamp, so a journal
     * recreated for a newer generation — even with the same alias+revision —
     * never compare-equals a stale predecessor (no ABA delete of the
     * replacement). Fail-closed: [expected] validation
     * ([IllegalArgumentException]) and DataStore failures propagate; only the
     * expected already-replaced contention path returns `false`.
     */
    suspend fun removePendingDeleteIfMatches(expected: PendingDelete): Boolean {
        val expectedRaw = expected.format()
        return withContext(ioDispatcher) {
            var removed = false
            dataStore.edit { prefs ->
                if (prefs[ENDPOINT_PENDING_DELETE_V1] != expectedRaw) {
                    return@edit
                }
                prefs.remove(ENDPOINT_PENDING_DELETE_V1)
                removed = true
            }
            removed
        }
    }

    /**
     * Unconditionally removes the teardown journal WITHOUT touching metadata
     * or the revision.
     *
     * WARNING: production MUST NOT call this — the unconditional remove can
     * ABA-delete a journal recreated for a newer generation between the
     * STALE classification and the discard. Kept for tests only; STALE
     * callers MUST use [removePendingDeleteIfMatches] (single-edit
     * compare-and-delete) so a superseded discard never deletes a newer
     * generation's journal. Never use this to finish a teardown either — the
     * fenced clear ([clearIfRevision], which removes the journal atomically
     * on a win) owns that.
     */
    suspend fun removePendingDelete() {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs.remove(ENDPOINT_PENDING_DELETE_V1)
            }
        }
    }

    /**
     * Raw monotonic revision, or `null` when the key was never written (fresh).
     *
     * Unlike [observe] — which folds missing/partial metadata (including the
     * post-[clear] tombstone) to `null` — this exposes the underlying
     * [ENDPOINT_CONFIG_REVISION_V1] value so callers can fence stale sessions.
     * Fail-closed: DataStore failures are NOT caught and propagate to the caller.
     */
    suspend fun currentRevision(): Long? = withContext(ioDispatcher) {
        dataStore.data.map { prefs -> prefs[ENDPOINT_CONFIG_REVISION_V1] }.first()
    }

    /** Metadata present (does NOT imply a usable endpoint; see [readiness]). */
    suspend fun hasMetadata(): Boolean = withContext(ioDispatcher) {
        try {
            observe().first() != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gate verdict: metadata complete + baseUrl valid + [KeyVault] holds the key.
     * Network reachability is deliberately excluded (checked via listModels on demand).
     *
     * A non-null pending-delete journal folds to [EndpointReadiness.NO_KEY]
     * without touching the vault: the teardown already deleted (or is about to
     * re-delete) the live bytes, so the endpoint is conservatively unusable
     * until the replay resolves it. Malformed journals fold the same way
     * (fail-closed). DataStore failures still fold to
     * [EndpointReadiness.NO_ENDPOINT_METADATA].
     *
     * Ref-driven: the vault alias is resolved from `config.apiKeyRef` via
     * [SetupValidation.aliasFromRef], never re-derived from `providerId`
     * (staged commits point metadata at a per-operation alias). A missing
     * prefix or blank alias fails closed to [EndpointReadiness.NO_KEY] without
     * touching the vault.
     */
    suspend fun readiness(vault: KeyVault): EndpointReadiness {
        val prefs = try {
            withContext(ioDispatcher) { dataStore.data.first() }
        } catch (_: Exception) {
            return EndpointReadiness.NO_ENDPOINT_METADATA
        }
        if (prefs[ENDPOINT_PENDING_DELETE_V1] != null) return EndpointReadiness.NO_KEY
        val config = parseConfig(prefs) ?: return EndpointReadiness.NO_ENDPOINT_METADATA
        try {
            validateBaseUrl(config.baseUrl)
        } catch (_: IllegalArgumentException) {
            return EndpointReadiness.INVALID_BASE_URL
        }
        // Stage1 compat: a legacy custom row (pre-per-endpoint id) is proven
        // unusable without vault I/O — its shared fixed slot may hold
        // another URL's key — so it fails closed as NO_KEY (never READY, never
        // a vault read here). Re-key via Setup. Checked after URL validity so
        // a malformed endpoint still reports INVALID_BASE_URL.
        if (SetupValidation.isLegacyEndpoint(config)) return EndpointReadiness.NO_KEY
        // Stage1 triple consistency (read side): a non-legacy row whose vault
        // alias falls outside its own providerId namespace can never be READY
        // (fail closed without vault I/O, same as legacy). Legacy rows already
        // returned NO_KEY above. Same predicate as the Setup gate and
        // credential capture — the three never disagree.
        if (!SetupValidation.aliasInNamespace(config.providerId, config.apiKeyRef)) {
            return EndpointReadiness.NO_KEY
        }
        val alias = SetupValidation.aliasFromRef(config.apiKeyRef)
        val hasKey = try {
            vault.hasKey(alias)
        } catch (_: Exception) {
            false
        }
        return if (hasKey) EndpointReadiness.READY else EndpointReadiness.NO_KEY
    }

    /**
     * Legacy unconditional write. Kept for tests / non-fencing paths only.
     *
     * New production code that needs stale-session fencing MUST use
     * [saveIfRevision] with the revision observed at read time instead.
     */
    suspend fun save(config: EndpointConfig) {
        validateForSave(config)
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[ENDPOINT_PROVIDER_ID_V1] = config.providerId
                prefs[ENDPOINT_PRESET_ID_V1] = config.presetId
                prefs[ENDPOINT_LABEL_V1] = config.label
                prefs[ENDPOINT_BASE_URL_V1] = config.baseUrl
                prefs[ENDPOINT_PROTOCOL_V1] = config.protocol.name
                prefs[ENDPOINT_MODEL_V1] = config.model
                prefs[ENDPOINT_API_KEY_REF_V1] = config.apiKeyRef
                prefs[ENDPOINT_CONFIG_REVISION_V1] = nextRevision(prefs[ENDPOINT_CONFIG_REVISION_V1])
            }
        }
    }

    /**
     * Atomic compare-and-set write. Single-process only; not safe across processes
     * (DataStore is not multi-process safe).
     *
     * Validates exactly like [save], then inside a single `dataStore.edit {}`
     * transaction requires BOTH that no teardown journal is present AND that
     * the stored revision matches [expectedRevision]:
     * - present journal (ANY non-null [ENDPOINT_PENDING_DELETE_V1] value,
     *   including malformed) → `return@edit` without writing anything,
     *   returns `false` (blocked: a logout teardown owns the endpoint until
     *   the journal is reconciled; the caller must reconcile-then-retry, never
     *   overwrite or discard the journal here);
     * - revision mismatch → `return@edit` without writing anything, returns
     *   `false` (the `edit` early-return does not advance the revision);
     * - both clear → writes all fields and bumps to
     *   `Math.addExact(expectedRevision ?: 0L, 1L)`, returns `true`.
     *
     * The journal check and the revision compare share the one atomic edit, so
     * a journal landing between the caller's snapshot and this CAS still fails
     * closed (no save-then-mask window).
     *
     * @param expectedRevision MUST come from [currentRevision()], never
     * `observe().configRevision`: [observe] folds missing/partial metadata
     * (including the post-[clear] tombstone) to `null` — losing the tombstone
     * revision — and folds a missing revision key on present metadata to `0L`,
     * so reusing its value conflates fresh `null` with dirty `0L` and can
     * silently clobber a concurrent writer.
     *
     * `null` expected means "must be fresh" (key never written); a dirty
     * hand-written `0L` does NOT equal fresh `null`. Fail-closed: validation
     * failures, revision overflow ([ArithmeticException]), and DataStore
     * failures propagate; only the expected contention paths (journal present,
     * revision moved) return `false`.
     */
    suspend fun saveIfRevision(config: EndpointConfig, expectedRevision: Long?): Boolean {
        validateForSave(config)
        return withContext(ioDispatcher) {
            var wrote = false
            dataStore.edit { prefs ->
                if (prefs[ENDPOINT_PENDING_DELETE_V1] != null) {
                    return@edit
                }
                val actual = prefs[ENDPOINT_CONFIG_REVISION_V1]
                if (actual != expectedRevision) {
                    return@edit
                }
                prefs[ENDPOINT_PROVIDER_ID_V1] = config.providerId
                prefs[ENDPOINT_PRESET_ID_V1] = config.presetId
                prefs[ENDPOINT_LABEL_V1] = config.label
                prefs[ENDPOINT_BASE_URL_V1] = config.baseUrl
                prefs[ENDPOINT_PROTOCOL_V1] = config.protocol.name
                prefs[ENDPOINT_MODEL_V1] = config.model
                prefs[ENDPOINT_API_KEY_REF_V1] = config.apiKeyRef
                prefs[ENDPOINT_CONFIG_REVISION_V1] = Math.addExact(expectedRevision ?: 0L, 1L)
                wrote = true
            }
            wrote
        }
    }

    /**
     * Privileged unconditional clear. Only explicit logout may call this;
     * stale callers are forbidden — use [clearIfRevision] for any fenced path.
     *
     * Intentionally asymmetric with [saveIfRevision]: logout is authoritative
     * teardown that must always advance the revision (invalidating outstanding
     * snapshots/consents), never fail with `false`. Fencing belongs on the
     * write path; a stale logout must not silently no-op and leave metadata
     * behind, so only the session owner reaching this privileged path may clear.
     *
     * Clears metadata only. Callers rotating/logging out MUST also call
     * `KeyVault.deleteKey(providerId)` or orphan keys linger in EncryptedSharedPreferences.
     *
     * Also removes any [ENDPOINT_PENDING_DELETE_V1] journal: an unconditional
     * clear is authoritative teardown and leaves no in-flight state behind.
     */
    suspend fun clear() {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs.remove(ENDPOINT_PROVIDER_ID_V1)
                prefs.remove(ENDPOINT_PRESET_ID_V1)
                prefs.remove(ENDPOINT_LABEL_V1)
                prefs.remove(ENDPOINT_BASE_URL_V1)
                prefs.remove(ENDPOINT_PROTOCOL_V1)
                prefs.remove(ENDPOINT_MODEL_V1)
                prefs.remove(ENDPOINT_API_KEY_REF_V1)
                prefs.remove(ENDPOINT_PENDING_DELETE_V1)
                // Keep the revision across logout so a later endpoint cannot
                // accidentally compare equal to a pre-logout snapshot.
                prefs[ENDPOINT_CONFIG_REVISION_V1] = nextRevision(prefs[ENDPOINT_CONFIG_REVISION_V1])
            }
        }
    }

    /**
     * Fenced clear. Single-process only; not safe across processes
     * (DataStore is not multi-process safe).
     *
     * Compares the stored revision against [expectedRevision] inside a single
     * `dataStore.edit {}` transaction: mismatch `return@edit`s without writing
     * and returns `false`; match removes all metadata fields, bumps to
     * `Math.addExact(expectedRevision ?: 0L, 1L)`, and returns `true`.
     *
     * On a win the [ENDPOINT_PENDING_DELETE_V1] journal is removed in the SAME
     * atomic edit (logout teardown commits journal + metadata together); on a
     * mismatch nothing is written and a superseded journal survives for the
     * caller to discard via [removePendingDeleteIfMatches] (never by retrying here — a
     * retry would delete the winner's metadata).
     *
     * @param expectedRevision MUST come from [currentRevision()], never
     * `observe().configRevision`: [observe] folds the post-[clear] tombstone to
     * `null`, so reusing its value loses the revision that must be fenced.
     *
     * Fail-closed: revision overflow ([ArithmeticException]) and DataStore
     * failures propagate; only the expected contention path returns `false`.
     * Like [clear], this removes metadata only — callers MUST also coordinate
     * `KeyVault.deleteKey(providerId)` (no distributed transaction with the vault).
     */
    suspend fun clearIfRevision(expectedRevision: Long?): Boolean {
        return withContext(ioDispatcher) {
            var wrote = false
            dataStore.edit { prefs ->
                val actual = prefs[ENDPOINT_CONFIG_REVISION_V1]
                if (actual != expectedRevision) {
                    return@edit
                }
                prefs.remove(ENDPOINT_PROVIDER_ID_V1)
                prefs.remove(ENDPOINT_PRESET_ID_V1)
                prefs.remove(ENDPOINT_LABEL_V1)
                prefs.remove(ENDPOINT_BASE_URL_V1)
                prefs.remove(ENDPOINT_PROTOCOL_V1)
                prefs.remove(ENDPOINT_MODEL_V1)
                prefs.remove(ENDPOINT_API_KEY_REF_V1)
                prefs.remove(ENDPOINT_PENDING_DELETE_V1)
                // Keep the revision across clear so a later endpoint cannot
                // accidentally compare equal to a pre-clear snapshot.
                prefs[ENDPOINT_CONFIG_REVISION_V1] = Math.addExact(expectedRevision ?: 0L, 1L)
                wrote = true
            }
            wrote
        }
    }

    private fun nextRevision(current: Long?): Long =
        Math.addExact(current ?: 0L, 1L)

    private fun validateForSave(config: EndpointConfig) {
        require(config.providerId.isNotBlank()) { "PROVIDER_ID_BLANK" }
        require(config.baseUrl.isNotBlank()) { "BASE_URL_BLANK" }
        require(config.apiKeyRef.isNotBlank()) { "PROVIDER_KEY_REF_BLANK" }
        // Boundary guard: the ref must be a vault alias, never pasted key material.
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        require('\n' !in config.apiKeyRef && '\r' !in config.apiKeyRef) { "PROVIDER_KEY_REF_MALFORMED" }
        validateBaseUrl(config.baseUrl)
        // Stage1 triple consistency (non-legacy rows only): the stored
        // providerId must equal the derived per-endpoint id for its own
        // (presetId, baseUrl), and the vault alias must belong to that id's
        // namespace (live alias or per-operation staged alias). Legacy rows
        // (pre-per-endpoint id) are exempt here to preserve migration seeding
        // via save(); production saveIfRevision paths never mint legacy
        // (SetupViewModel derives the id), and readers fail closed on legacy.
        if (!SetupValidation.isLegacyEndpoint(config)) {
            require(config.providerId == SetupValidation.deriveEndpointId(config.presetId, config.baseUrl)) {
                "ENDPOINT_ID_MISMATCH"
            }
            require(SetupValidation.aliasFromRef(config.apiKeyRef).isNotBlank()) {
                "PROVIDER_KEY_REF_MALFORMED"
            }
            require(SetupValidation.aliasInNamespace(config.providerId, config.apiKeyRef)) {
                "PROVIDER_KEY_REF_NAMESPACE_MISMATCH"
            }
        }
    }
}
