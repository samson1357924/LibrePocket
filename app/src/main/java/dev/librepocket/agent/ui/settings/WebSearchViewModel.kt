package dev.librepocket.agent.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.librepocket.web.DdgsSearchProvider
import dev.librepocket.web.MAX_WEB_BASE_URL_LENGTH
import dev.librepocket.web.SearxngSearchProvider
import dev.librepocket.web.WebHttpGet
import dev.librepocket.web.WebProbe
import dev.librepocket.web.WebSearchSettings
import dev.librepocket.web.WebSearchSnapshot
import dev.librepocket.web.WebSwitchStore
import dev.librepocket.web.WebUnavailableReason
import dev.librepocket.web.isSafeUrlForStorage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WebSearchViewModel"

/**
 * Settings→Tools→Web ViewModel（D3 Phase 2）。
 *
 * - 上游 [WebSwitchStore.observeState] 經 [WebSearchSnapshot.decideEffective] 折疊
 *   `WhileSubscribed(5000)` 重訂閱空窗，避免背景恢復時閃現 LOADING；
 * - 讀取失敗（`loadFailed`）與未載入態全程 fail-closed，所有寫入與探測皆拒絕；
 * - SearXNG URL 輸入、開關切換、首次同意（[WebSwitchStore.confirmConsentAndEnableMaster]）
 *   與重設統一經單一不可變 [InternalState]（由極簡 `stateLock` 保護）排入單一順序命令通道
 *   [storeCommandChannel]（`Channel.UNLIMITED`），於 `ioDispatcher` 單一協程循序執行，
 *   `combine` 保持 100% 純函式無副作用；
 * - [ioDispatcher] 必須為非同步排程器（例如 [Dispatchers.IO] 或測試用
 *   `StandardTestDispatcher`，生產環境切勿傳入 `Unconfined`）。
 */
class WebSearchViewModel(
    private val store: WebSwitchStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val probeProvider: suspend (searxngUrl: String) -> Pair<WebProbe, WebProbe> = { url ->
        val noop = WebHttpGet { _, _ -> throw UnsupportedOperationException("D3 no production transport") }
        SearxngSearchProvider(url, noop).probe() to DdgsSearchProvider(noop).probe()
    },
) : ViewModel() {

    private data class SnapshotFrame(
        val snapshot: WebSearchSnapshot,
        val retrying: Boolean,
    )

    private data class InternalState(
        val stateVersion: Long = 0L,
        val frame: SnapshotFrame = SnapshotFrame(WebSearchSnapshot.LOADING, retrying = false),
        val draftUrl: String = "",
        val urlDirty: Boolean = false,
        val pendingCleanVersion: Long = -1L,
        val draftVersion: Long = 0L,
        val resetGeneration: Long = 0L,
        val masterSeq: Long = 0L,
        val ddgsSeq: Long = 0L,
        val consentAttemptId: Long = 0L,
        val showConsent: Boolean = false,
        val consentSubmitting: Boolean = false,
        val probeMessage: String? = null,
        val writeError: String? = null,
        val retryTargetGen: Long = -1L,
        val pendingConsentReopenSeq: Long = -1L,
        val pendingConsentReopenGen: Long = -1L,
        val snapshotEpoch: Long = 0L,
        val isCleared: Boolean = false,
    )

    private sealed interface StoreCommand {
        data class UrlWrite(
            val url: String,
            val draftVersion: Long,
            val resetGeneration: Long,
        ) : StoreCommand

        /** Marker: consume latest conflated URL draft from [pendingUrlWrite]. */
        data object UrlFlush : StoreCommand

        data class MasterSwitch(
            val enabled: Boolean,
            val seq: Long,
            val resetGeneration: Long,
        ) : StoreCommand

        data class DdgsSwitch(
            val enabled: Boolean,
            val seq: Long,
            val resetGeneration: Long,
        ) : StoreCommand

        data class ConsentConfirm(
            val attemptId: Long,
            val resetGeneration: Long,
        ) : StoreCommand

        data class ResetAll(
            val draftVersion: Long,
            val resetGeneration: Long,
        ) : StoreCommand
    }

    private val reloadTick = MutableStateFlow(0L)
    private val reloadGen = AtomicLong(0L)

    private val stateLock = Any()
    private var latestState = InternalState()
    private val _internalState = MutableStateFlow(latestState)
    private val storeCommandChannel = Channel<StoreCommand>(Channel.UNLIMITED)
    /** Latest pending URL draft; consumed by [StoreCommand.UrlFlush] to bound backlog. */
    private val pendingUrlWrite = java.util.concurrent.atomic.AtomicReference<StoreCommand.UrlWrite?>()
    /** At most one [StoreCommand.UrlFlush] marker queued when a URL draft is pending. */
    private val urlFlushQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    /**
     * ResetAll barrier queued but not yet applied. Used so a pre-barrier UrlFlush
     * marker cannot consume/persist a draft authored after ResetAll was enqueued.
     */
    private val pendingResetBarrier = java.util.concurrent.atomic.AtomicReference<StoreCommand.ResetAll?>()

    private val probeGen = AtomicLong(0L)
    private var probeJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val snapshotDriver: Flow<Unit> = reloadTick
        .flatMapLatest { gen ->
            store.observeState().map { incoming -> gen to incoming }
        }
        .map { (gen, incoming) ->
            var livePair: Pair<Boolean, Boolean>? = null
            var checkedSeq = -1L
            var checkedGen = -1L
            var attempts = 0
            while (attempts < 3) {
                attempts++
                val checkTarget = synchronized(stateLock) {
                    val cur = latestState
                    if (!cur.isCleared &&
                        cur.pendingConsentReopenSeq != -1L &&
                        incoming.loaded &&
                        cur.retryTargetGen == -1L &&
                        !incoming.loadFailed &&
                        (incoming.consentAck || incoming.masterEnabled) &&
                        (cur.frame.snapshot.consentAck || cur.frame.snapshot.masterEnabled)
                    ) {
                        cur.pendingConsentReopenSeq to cur.pendingConsentReopenGen
                    } else {
                        null
                    }
                }
                if (checkTarget != null &&
                    (livePair == null || checkTarget.first != checkedSeq || checkTarget.second != checkedGen)
                ) {
                    checkedSeq = checkTarget.first
                    checkedGen = checkTarget.second
                    livePair = try {
                        store.currentConsentAndMaster()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (readEx: Exception) {
                        runCatching { Log.w(TAG, "web live consent check failed", readEx) }
                        true to false
                    }
                }
                var retryCheck = false
                val next = updateState { cur ->
                    if (cur.isCleared) {
                        cur
                    } else {
                        val needsFreshCheck =
                            incoming.loaded &&
                                cur.retryTargetGen == -1L &&
                                !incoming.loadFailed &&
                                cur.pendingConsentReopenSeq != -1L &&
                                (incoming.consentAck || incoming.masterEnabled) &&
                                (cur.frame.snapshot.consentAck || cur.frame.snapshot.masterEnabled) &&
                                (livePair == null ||
                                    cur.pendingConsentReopenSeq != checkedSeq ||
                                    cur.pendingConsentReopenGen != checkedGen)
                        if (needsFreshCheck && attempts < 3) {
                            retryCheck = true
                            cur
                        } else {
                            val nextSnap = WebSearchSnapshot.decideEffective(cur.frame.snapshot, incoming)
                            var updated = cur
                            if (incoming.loaded) {
                                updated = updated.copy(snapshotEpoch = updated.snapshotEpoch + 1)
                            }
                            if (incoming.loaded && updated.retryTargetGen != -1L && gen >= updated.retryTargetGen) {
                                updated = updated.copy(retryTargetGen = -1L)
                            }
                            if (nextSnap.loadFailed) {
                                updated = updated.copy(
                                    urlDirty = false,
                                    pendingCleanVersion = -1L,
                                    draftUrl = "",
                                    showConsent = false,
                                    pendingConsentReopenSeq = -1L,
                                    pendingConsentReopenGen = -1L,
                                    probeMessage = null,
                                    writeError = null,
                                )
                            } else {
                                if (incoming.loaded && updated.retryTargetGen == -1L) {
                                    if (nextSnap.consentAck || nextSnap.masterEnabled) {
                                        val effectiveLivePair = if (
                                            livePair != null &&
                                            updated.pendingConsentReopenSeq == checkedSeq &&
                                            updated.pendingConsentReopenGen == checkedGen
                                        ) {
                                            livePair
                                        } else {
                                            null
                                        }
                                        val transitionedFromUnconsented =
                                            !cur.frame.snapshot.consentAck && !cur.frame.snapshot.masterEnabled
                                        val shouldClearPending =
                                            transitionedFromUnconsented ||
                                                needsFreshCheck ||
                                                (effectiveLivePair != null &&
                                                    (effectiveLivePair.first || effectiveLivePair.second))
                                        updated = updated.copy(
                                            showConsent = false,
                                            pendingConsentReopenSeq =
                                                if (shouldClearPending) -1L else updated.pendingConsentReopenSeq,
                                            pendingConsentReopenGen =
                                                if (shouldClearPending) -1L else updated.pendingConsentReopenGen,
                                        )
                                    } else {
                                        val promoteConsent =
                                            updated.pendingConsentReopenSeq == updated.masterSeq &&
                                                updated.pendingConsentReopenGen == updated.resetGeneration &&
                                                !updated.consentSubmitting
                                        updated = updated.copy(
                                            showConsent = if (promoteConsent) true else updated.showConsent,
                                            pendingConsentReopenSeq = -1L,
                                            pendingConsentReopenGen = -1L,
                                        )
                                    }
                                } else if (nextSnap.loaded &&
                                    (nextSnap.consentAck || nextSnap.masterEnabled) &&
                                    updated.showConsent
                                ) {
                                    updated = updated.copy(showConsent = false)
                                }
                                if (
                                    updated.urlDirty &&
                                    updated.pendingCleanVersion == updated.draftVersion &&
                                    nextSnap.loaded &&
                                    nextSnap.searxngBaseUrl == updated.draftUrl
                                ) {
                                    updated = updated.copy(
                                        urlDirty = false,
                                        pendingCleanVersion = -1L,
                                    )
                                }
                            }
                            updated.copy(
                                frame = SnapshotFrame(
                                    snapshot = nextSnap,
                                    retrying = updated.retryTargetGen != -1L,
                                ),
                            )
                        }
                    }
                }
                if (!retryCheck) {
                    syncUi(next)
                    break
                }
            }
        }
        .shareIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(0),
            replay = 0,
        )

    val uiState: StateFlow<WebSearchSettingsState> = combine(
        _internalState,
        snapshotDriver.onStart { emit(Unit) },
    ) { state, _ ->
        buildUiState(state)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildUiState(InternalState()),
    )

    init {
        viewModelScope.launch(ioDispatcher) {
            for (cmd in storeCommandChannel) {
                when (cmd) {
                    is StoreCommand.UrlFlush -> {
                        // Consume latest conflated draft; if new drafts arrived during
                        // handling, loop until stable (slot stays O(1)). A pre-barrier
                        // flush marker must not consume a draft authored after a queued
                        // ResetAll barrier (post-reset draft).
                        while (true) {
                            urlFlushQueued.set(false)
                            val latest = pendingUrlWrite.getAndSet(null)
                            if (latest != null) {
                                val barrier = pendingResetBarrier.get()
                                val isPostResetDraft =
                                    barrier != null &&
                                        latest.resetGeneration == barrier.resetGeneration &&
                                        latest.draftVersion > barrier.draftVersion
                                if (isPostResetDraft) {
                                    // Leave post-reset draft for processing after ResetAll.
                                    pendingUrlWrite.compareAndSet(null, latest)
                                    break
                                }
                                handleCommand(latest)
                                if (pendingUrlWrite.get() == null) break
                                // A newer draft arrived while handling; re-mark and continue.
                                urlFlushQueued.set(true)
                                continue
                            }
                            // Nothing pending (e.g. stale/cleared marker); stop.
                            if (pendingUrlWrite.get() != null && urlFlushQueued.compareAndSet(false, true)) {
                                continue
                            }
                            break
                        }
                    }
                    is StoreCommand.UrlWrite -> handleCommand(cmd)
                    else -> handleCommand(cmd)
                }
            }
        }
    }

    private fun buildUiState(state: InternalState): WebSearchSettingsState {
        val frame = state.frame
        val snap = frame.snapshot
        val retrying = frame.retrying || state.retryTargetGen != -1L
        val ready = snap.loaded && !snap.loadFailed && !retrying
        val interactive = ready && !state.consentSubmitting
        val failClosed = !ready
        val effectiveUrl = when {
            failClosed -> ""
            state.urlDirty -> state.draftUrl
            else -> snap.searxngBaseUrl
        }
        val searxngProbe = if (failClosed) {
            WebProbe.NotReady(WebUnavailableReason.MISSING_URL)
        } else {
            WebSearchSettings.probeSearxngUrl(effectiveUrl)
        }
        return WebSearchSettingsState(
            masterEnabled = !failClosed && snap.masterEnabled,
            ddgsOptIn = !failClosed && snap.ddgsOptIn,
            searxngUrl = effectiveUrl,
            searxngProbe = searxngProbe,
            ddgsProbe = WebProbe.Ready,
            probeMessage = if (ready) state.probeMessage else null,
            showConsent = state.showConsent && ready && !snap.consentAck && !snap.masterEnabled,
            switchesEnabled = interactive,
            urlEditable = interactive,
            isLoading = !snap.loaded || retrying,
            loadFailed = !retrying && snap.loadFailed,
            writeError = if (ready) state.writeError else null,
            consentSubmitting = state.consentSubmitting,
            canReset = interactive && (
                snap.masterEnabled ||
                    snap.ddgsOptIn ||
                    snap.consentAck ||
                    snap.searxngBaseUrl.isNotEmpty() ||
                    effectiveUrl.isNotEmpty()
                ),
        )
    }

    private inline fun updateState(
        crossinline transform: (InternalState) -> InternalState,
    ): InternalState = synchronized(stateLock) {
        val cur = latestState
        val raw = transform(cur)
        if (raw != cur) {
            val next = raw.copy(stateVersion = cur.stateVersion + 1)
            latestState = next
            next
        } else {
            cur
        }
    }

    private inline fun updateStateAndCommand(
        crossinline transform: (InternalState) -> Pair<InternalState, StoreCommand?>,
    ): InternalState = synchronized(stateLock) {
        val cur = latestState
        val (raw, cmd) = transform(cur)
        val next = if (raw != cur) {
            val bumped = raw.copy(stateVersion = cur.stateVersion + 1)
            latestState = bumped
            bumped
        } else {
            cur
        }
        if (cmd != null) {
            val sent = storeCommandChannel.trySend(cmd)
            if (sent.isFailure && !next.isCleared) {
                runCatching { Log.w(TAG, "web store command rejected unexpectedly") }
            }
        }
        next
    }

    /**
     * Publish latest URL draft for conflated flush. Must be called while holding
     * [stateLock] together with the corresponding draft state transition so a
     * newer draft cannot be displaced by a stale concurrent publication.
     */
    private fun enqueueUrlWriteLocked(cmd: StoreCommand.UrlWrite): Boolean {
        val cur = pendingUrlWrite.get()
        if (cur != null &&
            (cmd.resetGeneration < cur.resetGeneration ||
                (cmd.resetGeneration == cur.resetGeneration && cmd.draftVersion < cur.draftVersion))
        ) {
            // Stale publication; keep the newer conflated draft.
            return true
        }
        pendingUrlWrite.set(cmd)
        if (urlFlushQueued.compareAndSet(false, true)) {
            val sent = storeCommandChannel.trySend(StoreCommand.UrlFlush)
            if (sent.isFailure) {
                urlFlushQueued.set(false)
                return false
            }
        }
        return true
    }

    private fun syncUi(next: InternalState) {
        _internalState.update { cur -> if (next.stateVersion >= cur.stateVersion) next else cur }
    }

    private fun canMutateWith(state: InternalState): Boolean =
        !state.isCleared &&
            state.retryTargetGen == -1L &&
            state.frame.snapshot.loaded &&
            !state.frame.snapshot.loadFailed &&
            !state.frame.retrying

    private fun effectiveUrlLocked(state: InternalState): String =
        if (state.urlDirty) state.draftUrl else state.frame.snapshot.searxngBaseUrl

    private fun cancelProbeLocked(): Job? {
        probeGen.incrementAndGet()
        val old = probeJob
        probeJob = null
        return old
    }

    private suspend fun handleCommand(cmd: StoreCommand) {
        when (cmd) {
            is StoreCommand.UrlWrite -> handleUrlWrite(cmd)
            StoreCommand.UrlFlush -> Unit
            is StoreCommand.MasterSwitch -> handleMasterSwitch(cmd)
            is StoreCommand.DdgsSwitch -> handleDdgsSwitch(cmd)
            is StoreCommand.ConsentConfirm -> handleConsentConfirm(cmd)
            is StoreCommand.ResetAll -> handleResetAll(cmd)
        }
    }

    private suspend fun handleUrlWrite(cmd: StoreCommand.UrlWrite) {
        var shouldWrite = false
        val preNext = updateState { cur ->
            if (cur.isCleared ||
                cmd.resetGeneration != cur.resetGeneration ||
                cmd.draftVersion != cur.draftVersion
            ) {
                cur
            } else if (!canMutateWith(cur)) {
                cur.copy(urlDirty = false, pendingCleanVersion = -1L)
            } else if (!isSafeUrlForStorage(cmd.url)) {
                cur.copy(
                    urlDirty = true,
                    pendingCleanVersion = -1L,
                    writeError = "網址含不支援字元或帳號密碼，未儲存至本機設定。",
                )
            } else {
                shouldWrite = true
                cur
            }
        }
        syncUi(preNext)
        if (!shouldWrite) return

        try {
            withContext(NonCancellable) {
                store.setSearxngBaseUrl(cmd.url)
            }
            val okNext = updateState { cur ->
                if (!cur.isCleared &&
                    cmd.resetGeneration == cur.resetGeneration &&
                    cmd.draftVersion == cur.draftVersion
                ) {
                    if (!canMutateWith(cur) || cur.frame.snapshot.searxngBaseUrl == cmd.url) {
                        cur.copy(urlDirty = false, pendingCleanVersion = -1L)
                    } else {
                        cur.copy(pendingCleanVersion = cmd.draftVersion)
                    }
                } else {
                    cur
                }
            }
            syncUi(okNext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "web searxng url persist failed", e) }
            val errNext = updateState { cur ->
                if (!cur.isCleared &&
                    cmd.resetGeneration == cur.resetGeneration &&
                    cmd.draftVersion == cur.draftVersion
                ) {
                    if (!canMutateWith(cur)) {
                        cur.copy(
                            urlDirty = false,
                            pendingCleanVersion = -1L,
                            draftUrl = "",
                            writeError = null,
                        )
                    } else {
                        cur.copy(
                            urlDirty = true,
                            pendingCleanVersion = -1L,
                            writeError = "SearXNG 網址儲存失敗，請重試。",
                        )
                    }
                } else {
                    cur
                }
            }
            syncUi(errNext)
        }
    }

    private suspend fun handleMasterSwitch(cmd: StoreCommand.MasterSwitch) {
        var shouldWrite = false
        val preNext = updateState { cur ->
            if (cur.isCleared ||
                !canMutateWith(cur) ||
                cur.consentSubmitting ||
                cmd.resetGeneration != cur.resetGeneration ||
                cmd.seq != cur.masterSeq
            ) {
                cur
            } else if (cmd.enabled && !cur.frame.snapshot.consentAck && !cur.frame.snapshot.masterEnabled) {
                cur.copy(showConsent = true)
            } else {
                shouldWrite = true
                cur
            }
        }
        syncUi(preNext)
        if (!shouldWrite) return

        try {
            withContext(NonCancellable) {
                store.setMasterEnabled(cmd.enabled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message == "WEB_CONSENT_REQUIRED") {
                var attempts = 0
                var done = false
                while (!done && attempts < 3) {
                    attempts++
                    val epochBeforeRead = synchronized(stateLock) { latestState.snapshotEpoch }
                    val (liveConsent, liveMaster) = try {
                        store.currentConsentAndMaster()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (readEx: Exception) {
                        runCatching { Log.w(TAG, "web live consent check failed", readEx) }
                        true to false
                    }
                    var shouldRetry = false
                    val consentNext = updateState { cur ->
                        if (cur.isCleared ||
                            !cmd.enabled ||
                            cmd.resetGeneration != cur.resetGeneration ||
                            cmd.seq != cur.masterSeq ||
                            !canMutateWith(cur) ||
                            cur.consentSubmitting ||
                            liveConsent ||
                            liveMaster
                        ) {
                            cur
                        } else if (!cur.frame.snapshot.consentAck && !cur.frame.snapshot.masterEnabled) {
                            cur.copy(showConsent = true)
                        } else if (cur.snapshotEpoch != epochBeforeRead) {
                            shouldRetry = true
                            cur
                        } else {
                            cur.copy(
                                pendingConsentReopenSeq = cmd.seq,
                                pendingConsentReopenGen = cmd.resetGeneration,
                            )
                        }
                    }
                    syncUi(consentNext)
                    done = !shouldRetry
                }
            } else {
                runCatching { Log.w(TAG, "web master switch persist failed", e) }
                val errNext = updateState { cur ->
                    if (!cur.isCleared &&
                        canMutateWith(cur) &&
                        cmd.resetGeneration == cur.resetGeneration &&
                        cmd.seq == cur.masterSeq
                    ) {
                        cur.copy(writeError = "無法儲存網路搜尋開關狀態，請重試。")
                    } else {
                        cur
                    }
                }
                syncUi(errNext)
            }
        }
    }

    private suspend fun handleDdgsSwitch(cmd: StoreCommand.DdgsSwitch) {
        val shouldWrite = synchronized(stateLock) {
            val cur = latestState
            !cur.isCleared &&
                canMutateWith(cur) &&
                !cur.consentSubmitting &&
                cmd.resetGeneration == cur.resetGeneration &&
                cmd.seq == cur.ddgsSeq
        }
        if (!shouldWrite) return

        try {
            withContext(NonCancellable) {
                store.setDdgsOptIn(cmd.enabled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "web ddgs switch persist failed", e) }
            val errNext = updateState { cur ->
                if (!cur.isCleared &&
                    canMutateWith(cur) &&
                    cmd.resetGeneration == cur.resetGeneration &&
                    cmd.seq == cur.ddgsSeq
                ) {
                    cur.copy(writeError = "無法儲存 DDGS 開關狀態，請重試。")
                } else {
                    cur
                }
            }
            syncUi(errNext)
        }
    }

    private suspend fun handleConsentConfirm(cmd: StoreCommand.ConsentConfirm) {
        var shouldRun = false
        val preNext = updateState { cur ->
            if (cur.isCleared) {
                cur
            } else if (!canMutateWith(cur) ||
                !cur.showConsent ||
                cmd.attemptId != cur.consentAttemptId ||
                cmd.resetGeneration != cur.resetGeneration
            ) {
                if (cmd.attemptId == cur.consentAttemptId) {
                    cur.copy(showConsent = false, consentSubmitting = false)
                } else {
                    cur
                }
            } else {
                shouldRun = true
                cur
            }
        }
        syncUi(preNext)
        if (!shouldRun) return

        try {
            withContext(NonCancellable) {
                store.confirmConsentAndEnableMaster()
            }
            val okNext = updateState { cur ->
                if (!cur.isCleared &&
                    cmd.attemptId == cur.consentAttemptId &&
                    cmd.resetGeneration == cur.resetGeneration
                ) {
                    cur.copy(
                        showConsent = false,
                        probeMessage = null,
                        consentSubmitting = false,
                    )
                } else {
                    cur
                }
            }
            syncUi(okNext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "web consent persist failed", e) }
            val errNext = updateState { cur ->
                if (!cur.isCleared &&
                    cmd.attemptId == cur.consentAttemptId &&
                    cmd.resetGeneration == cur.resetGeneration
                ) {
                    if (!canMutateWith(cur)) {
                        cur.copy(
                            showConsent = false,
                            consentSubmitting = false,
                            writeError = null,
                        )
                    } else {
                        cur.copy(
                            showConsent = !cur.frame.snapshot.consentAck &&
                                !cur.frame.snapshot.masterEnabled,
                            consentSubmitting = false,
                            writeError = "無法儲存同意與啟用狀態，請重試。",
                        )
                    }
                } else {
                    cur
                }
            }
            syncUi(errNext)
        } finally {
            val finalNext = updateState { cur ->
                if (!cur.isCleared && cmd.attemptId == cur.consentAttemptId && cur.consentSubmitting) {
                    cur.copy(consentSubmitting = false)
                } else {
                    cur
                }
            }
            syncUi(finalNext)
        }
    }

    private suspend fun handleResetAll(cmd: StoreCommand.ResetAll) {
        var shouldRun = false
        val preNext = updateState { cur ->
            if (cur.isCleared) {
                cur
            } else if (!canMutateWith(cur) ||
                cmd.resetGeneration != cur.resetGeneration
            ) {
                // An accepted ResetAll must not be cancelled by a later queued
                // consent submission (same serial worker): consentSubmitting may be
                // set before this command is dequeued, but consent writes only run
                // when their command executes.
                if (cmd.resetGeneration == cur.resetGeneration && cmd.draftVersion == cur.draftVersion) {
                    cur.copy(urlDirty = false, pendingCleanVersion = -1L)
                } else {
                    cur
                }
            } else {
                shouldRun = true
                cur
            }
        }
        syncUi(preNext)
        if (!shouldRun) {
            pendingResetBarrier.compareAndSet(cmd, null)
            // If this ResetAll is being skipped, do not strand a valid deferred
            // post-reset draft behind a consumed pre-barrier UrlFlush marker.
            val postResetDraft = pendingUrlWrite.get()
            if (postResetDraft != null &&
                postResetDraft.resetGeneration == cmd.resetGeneration &&
                postResetDraft.draftVersion > cmd.draftVersion &&
                pendingUrlWrite.compareAndSet(postResetDraft, null)
            ) {
                handleUrlWrite(postResetDraft)
            }
            return
        }

        try {
            withContext(NonCancellable) {
                store.resetAll()
            }
            val okNext = updateState { cur ->
                if (!cur.isCleared &&
                    cur.resetGeneration == cmd.resetGeneration &&
                    cur.draftVersion == cmd.draftVersion
                ) {
                    if (!canMutateWith(cur) || cur.frame.snapshot.searxngBaseUrl.isEmpty()) {
                        cur.copy(urlDirty = false, pendingCleanVersion = -1L)
                    } else {
                        cur.copy(pendingCleanVersion = cmd.draftVersion)
                    }
                } else {
                    cur
                }
            }
            syncUi(okNext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "web settings reset failed", e) }
            val errNext = updateState { cur ->
                if (!cur.isCleared && cur.resetGeneration == cmd.resetGeneration) {
                    if (!canMutateWith(cur)) {
                        cur.copy(
                            urlDirty = false,
                            pendingCleanVersion = -1L,
                            draftUrl = "",
                            writeError = null,
                        )
                    } else if (cur.draftVersion == cmd.draftVersion) {
                        cur.copy(
                            urlDirty = false,
                            pendingCleanVersion = -1L,
                            writeError = "重設設定失敗，請重試。",
                        )
                    } else {
                        cur.copy(writeError = "重設設定失敗，請重試。")
                    }
                } else {
                    cur
                }
            }
            syncUi(errNext)
        } finally {
            pendingResetBarrier.compareAndSet(cmd, null)
            // A draft authored after this reset barrier must survive the reset.
            // If it was deferred because a pre-barrier UrlFlush marker was consumed,
            // persist it now in-order.
            val postResetDraft = pendingUrlWrite.get()
            if (postResetDraft != null &&
                postResetDraft.resetGeneration == cmd.resetGeneration &&
                postResetDraft.draftVersion > cmd.draftVersion &&
                pendingUrlWrite.compareAndSet(postResetDraft, null)
            ) {
                handleUrlWrite(postResetDraft)
            }
        }
    }

    fun onSearxngUrlChange(newUrl: String) {
        var oldProbe: Job? = null
        var enqueueFailed = false
        val next = synchronized(stateLock) {
            val cur = latestState
            val raw = if (!canMutateWith(cur) || cur.consentSubmitting) {
                cur
            } else {
                oldProbe = cancelProbeLocked()
                val ver = cur.draftVersion + 1
                val rGen = cur.resetGeneration
                if (newUrl.length > MAX_WEB_BASE_URL_LENGTH) {
                    // Drop any previously pending write: this draft is rejected and must
                    // not persist a stale earlier URL.
                    pendingUrlWrite.set(null)
                    cur.copy(
                        draftUrl = newUrl,
                        urlDirty = true,
                        pendingCleanVersion = -1L,
                        draftVersion = ver,
                        probeMessage = null,
                        writeError = "SearXNG 網址過長，未儲存至本機設定。",
                    )
                } else {
                    val urlCmd = StoreCommand.UrlWrite(url = newUrl, draftVersion = ver, resetGeneration = rGen)
                    if (!enqueueUrlWriteLocked(urlCmd)) {
                        enqueueFailed = true
                    }
                    cur.copy(
                        draftUrl = newUrl,
                        urlDirty = true,
                        pendingCleanVersion = -1L,
                        draftVersion = ver,
                        probeMessage = null,
                        writeError = null,
                    )
                }
            }
            val bumped = if (raw != cur) {
                val n = raw.copy(stateVersion = cur.stateVersion + 1)
                latestState = n
                n
            } else {
                cur
            }
            bumped
        }
        if (enqueueFailed && !next.isCleared) {
            runCatching { Log.w(TAG, "web store command rejected unexpectedly") }
        }
        oldProbe?.cancel()
        syncUi(next)
    }

    fun onMasterSwitch(enabled: Boolean) {
        var oldProbe: Job? = null
        val next = updateStateAndCommand { cur ->
            if (!canMutateWith(cur) || cur.consentSubmitting) {
                cur to null
            } else {
                oldProbe = cancelProbeLocked()
                if (enabled && !cur.frame.snapshot.consentAck && !cur.frame.snapshot.masterEnabled) {
                    cur.copy(
                        probeMessage = null,
                        writeError = null,
                        showConsent = true,
                        pendingConsentReopenSeq = -1L,
                        pendingConsentReopenGen = -1L,
                    ) to null
                } else {
                    val seq = cur.masterSeq + 1
                    cur.copy(
                        masterSeq = seq,
                        probeMessage = null,
                        writeError = null,
                        showConsent = if (!enabled) false else cur.showConsent,
                        pendingConsentReopenSeq = -1L,
                        pendingConsentReopenGen = -1L,
                    ) to StoreCommand.MasterSwitch(
                        enabled = enabled,
                        seq = seq,
                        resetGeneration = cur.resetGeneration,
                    )
                }
            }
        }
        oldProbe?.cancel()
        syncUi(next)
    }

    fun onConsentConfirm() {
        var oldProbe: Job? = null
        val next = updateStateAndCommand { cur ->
            if (cur.isCleared || cur.consentSubmitting) {
                cur to null
            } else if (!canMutateWith(cur) || !cur.showConsent) {
                cur.copy(showConsent = false) to null
            } else {
                oldProbe = cancelProbeLocked()
                val attemptId = cur.consentAttemptId + 1
                cur.copy(
                    consentAttemptId = attemptId,
                    consentSubmitting = true,
                    writeError = null,
                ) to StoreCommand.ConsentConfirm(
                    attemptId = attemptId,
                    resetGeneration = cur.resetGeneration,
                )
            }
        }
        oldProbe?.cancel()
        syncUi(next)
    }

    fun onConsentDismiss() {
        val next = updateState { cur ->
            if (cur.isCleared || cur.consentSubmitting || !cur.showConsent) {
                cur
            } else {
                cur.copy(showConsent = false)
            }
        }
        syncUi(next)
    }

    fun onDdgsSwitch(enabled: Boolean) {
        var oldProbe: Job? = null
        val next = updateStateAndCommand { cur ->
            if (!canMutateWith(cur) || cur.consentSubmitting) {
                cur to null
            } else {
                oldProbe = cancelProbeLocked()
                val seq = cur.ddgsSeq + 1
                cur.copy(
                    ddgsSeq = seq,
                    probeMessage = null,
                    writeError = null,
                ) to StoreCommand.DdgsSwitch(
                    enabled = enabled,
                    seq = seq,
                    resetGeneration = cur.resetGeneration,
                )
            }
        }
        oldProbe?.cancel()
        syncUi(next)
    }

    fun onProbe() {
        var oldProbe: Job? = null
        var url = ""
        var gen = 0L
        updateState { cur ->
            if (!canMutateWith(cur) || cur.consentSubmitting) {
                cur
            } else {
                oldProbe = cancelProbeLocked()
                gen = probeGen.get()
                url = effectiveUrlLocked(cur)
                cur
            }
        }
        if (gen == 0L) return
        oldProbe?.cancel()
        val newJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val (searxngProbe, ddgsProbe) = try {
                withContext(ioDispatcher) { probeProvider(url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "web probe failed", e) }
                WebProbe.NotReady(WebUnavailableReason.NO_PROVIDER) to
                    WebProbe.NotReady(WebUnavailableReason.NO_PROVIDER)
            }
            val ss = WebSearchSettings.statusText(WebSearchSettings.statusFor(searxngProbe))
            val ds = WebSearchSettings.statusText(WebSearchSettings.statusFor(ddgsProbe))
            val probed = updateState { cur ->
                if (!cur.isCleared &&
                    gen == probeGen.get() &&
                    effectiveUrlLocked(cur) == url &&
                    canMutateWith(cur) &&
                    !cur.consentSubmitting
                ) {
                    cur.copy(probeMessage = "設定檢查（不連線）— SearXNG：$ss；DDGS：$ds")
                } else {
                    cur
                }
            }
            syncUi(probed)
        }
        val shouldStart = synchronized(stateLock) {
            if (!latestState.isCleared && probeGen.get() == gen) {
                probeJob = newJob
                true
            } else {
                false
            }
        }
        if (shouldStart) {
            newJob.start()
        } else {
            newJob.cancel()
        }
    }

    fun onResetSettings() {
        var oldProbe: Job? = null
        var admittedCmd: StoreCommand.ResetAll? = null
        var sendFailed = false
        val next = updateState { cur ->
            if (!canMutateWith(cur) || cur.consentSubmitting) {
                cur
            } else {
                oldProbe = cancelProbeLocked()
                val rGen = cur.resetGeneration + 1
                val ver = cur.draftVersion + 1
                val cmd = StoreCommand.ResetAll(draftVersion = ver, resetGeneration = rGen)
                // Drop any stale pre-reset URL draft; post-reset drafts are authored
                // after this state transition and must survive the barrier.
                pendingUrlWrite.updateAndGet { pending ->
                    if (pending != null && pending.resetGeneration < rGen) null else pending
                }
                pendingResetBarrier.set(cmd)
                val sent = storeCommandChannel.trySend(cmd)
                if (sent.isSuccess) {
                    admittedCmd = cmd
                } else {
                    pendingResetBarrier.compareAndSet(cmd, null)
                    sendFailed = true
                }
                cur.copy(
                    resetGeneration = rGen,
                    draftVersion = ver,
                    draftUrl = "",
                    urlDirty = true,
                    pendingCleanVersion = -1L,
                    probeMessage = null,
                    showConsent = false,
                    pendingConsentReopenSeq = -1L,
                    pendingConsentReopenGen = -1L,
                    writeError = null,
                )
            }
        }
        if (sendFailed && !next.isCleared) {
            runCatching { Log.w(TAG, "web store command rejected unexpectedly") }
        }
        oldProbe?.cancel()
        syncUi(next)
    }

    fun retryLoad() {
        var oldProbe: Job? = null
        var nextGen = -1L
        val next = updateState { cur ->
            if (cur.isCleared || cur.consentSubmitting) {
                cur
            } else {
                oldProbe = cancelProbeLocked()
                nextGen = reloadGen.incrementAndGet()
                cur.copy(
                    frame = cur.frame.copy(retrying = true),
                    retryTargetGen = nextGen,
                    resetGeneration = cur.resetGeneration + 1,
                    draftVersion = cur.draftVersion + 1,
                    draftUrl = "",
                    urlDirty = false,
                    pendingCleanVersion = -1L,
                    showConsent = false,
                    pendingConsentReopenSeq = -1L,
                    pendingConsentReopenGen = -1L,
                    probeMessage = null,
                    writeError = null,
                )
            }
        }
        if (nextGen != -1L) {
            oldProbe?.cancel()
            syncUi(next)
            reloadTick.update { cur -> if (nextGen > cur) nextGen else cur }
        }
    }

    override fun onCleared() {
        var oldProbe: Job? = null
        val next = updateState { cur ->
            if (cur.isCleared) {
                cur
            } else {
                oldProbe = cancelProbeLocked()
                cur.copy(isCleared = true)
            }
        }
        oldProbe?.cancel()
        syncUi(next)
        storeCommandChannel.close()
        super.onCleared()
    }
}

class WebSearchViewModelFactory(app: Application) : ViewModelProvider.Factory {
    private val store by lazy { WebSwitchStore(app) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(WebSearchViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return WebSearchViewModel(store) as T
    }
}
