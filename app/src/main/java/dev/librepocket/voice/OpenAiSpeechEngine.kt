package dev.librepocket.voice

import android.media.MediaDataSource
import android.media.MediaPlayer
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.SetupValidation
import dev.librepocket.keystore.KeyVault
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.redact.Redactor
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.S1bFallback
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 記憶體音訊播放介面。
 * 遵循「音檔不落地（受限記憶體緩衝播放）」安全契約，直接從記憶體 ByteArray 播放，絕不寫入檔案系統。
 *
 * 記憶體行為校準（Bounded in-memory buffering）：
 * 音訊在網路下載後全額緩衝於記憶體中，在峰值時存在多份 ByteArray 拷貝（OkHttp 內部下載緩衝、
 * 合成結果陣列、以及 [ByteArrayMediaDataSource] 內部之防禦性副本等）。25MB 上限可降低但無法杜絕
 * 極限低記憶體設備情況下的 OOM 風險。
 */
interface AudioPlayer {
    /**
     * 受限記憶體緩衝播放位元組，掛起直到播放結束或發生例外。
     *
     * 零拷貝契約（RequiresNoMutation 語義）：
     * 實作得以零拷貝引用 [audioBytes]（不 clone），以降低峰值記憶體。
     * 因此呼叫端在 play 返回或被取消前**絕對不可修改**該陣列內容；
     * 若違反，播放讀取結果未定義。測試以 copyFalse 釘選此語義。
     *
     * @param audioBytes 記憶體中的音訊二進位資料。
     */
    suspend fun play(audioBytes: ByteArray)

    /**
     * 中斷目前播放並釋放播放器資源。
     */
    fun stop()
}

/**
 * Android [MediaDataSource] 受限記憶體實作。
 * 直接自 [data] ByteArray 進行受限記憶體區塊讀取（bounded in-memory read），保證音訊位元組永不落地（零檔案系統 I/O）。
 * 注意：預設以 `copy = true` 持有 [data] 的防禦性副本（defensive copy clone），以防外部修改；此處會額外佔用一份 ByteArray 拷貝。
 * 若以 `copy = false` 建構則不建立防禦性副本，直接引用傳入陣列；此為零拷貝路徑（RequiresNoMutation 語義，
 * 刻意保留 copy=false 以降低峰值記憶體，見 `byteArrayMediaDataSource_copyFalseDoesNotCloneArray` 釘選測試）。
 * 此路徑依賴傳入 array 在播放期間不可修改，
 * 呼叫端必須保證播放完成前不寫入該陣列，否則讀取結果未定義（[AndroidAudioPlayer.play] 即採用此路徑以避免額外拷貝，
 * 其傳入的 `result.audioBytes` 由 client 全新配置，天然滿足不可變契約）。
 */
class ByteArrayMediaDataSource(
    data: ByteArray,
    copy: Boolean = true,
) : MediaDataSource() {
    @Volatile
    private var data: ByteArray? = if (copy) data.clone() else data
    @Volatile
    private var isClosed: Boolean = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (isClosed) return -1
        val currentData = data ?: return -1
        if (position < 0 || position >= currentData.size) return -1
        if (size <= 0) return 0
        if (offset < 0 || offset >= buffer.size) return 0
        val remaining = (currentData.size - position).toInt()
        val toCopy = minOf(size, remaining, buffer.size - offset)
        if (toCopy <= 0) return 0
        System.arraycopy(currentData, position.toInt(), buffer, offset, toCopy)
        return toCopy
    }

    override fun getSize(): Long = if (isClosed) 0L else (data?.size?.toLong() ?: 0L)

    override fun close() {
        isClosed = true
        data = null
    }
}

/**
 * Android 生產環境預設播放器，利用 [MediaPlayer] 搭配 [ByteArrayMediaDataSource] 達成受限記憶體緩衝播放。
 * 併發與生命週期保證（世代柵欄 + 非阻塞釋放）：
 * - 僅以 [lock] 保護狀態（activePlayer/activeDataSource/activeContinuation/activeEpoch），絕不在持有任何 Java monitor
 *   時呼叫 MediaPlayer native 阻塞方法（setDataSource/prepareAsync/start/reset/release），避免 AB-BA 死鎖；
 * - 每次 play 以遞增世代（epoch）佔位，native 前後皆以世代柵欄複檢，失格即釋放並取消，遲到回呼永不 start；
 * - [stop] 僅做 detach + cancel（持 [lock] 短臨界），native reset/release 投遞至背景釋放執行緒，呼叫線程永不被 native 阻塞；
 * - prepare 另設 [PREPARE_TIMEOUT_MS] 超時，逾時即 detach + 釋放 + resumeWithException。
 *
 * 採用 [MediaPlayer.prepareAsync] 非同步準備，所有 native 呼叫皆在鎖外執行，確保 [stop] 與新播放能隨時即時中斷。
 */
class AndroidAudioPlayer(
    private val mediaPlayerFactory: () -> MediaPlayer = { MediaPlayer() },
) : AudioPlayer {
    companion object {
        /** prepare 非同步等待上限（毫秒），逾時視為播放失敗並主動釋放。 */
        const val PREPARE_TIMEOUT_MS = 15_000L
    }

    private var activePlayer: MediaPlayer? = null
    private var activeDataSource: ByteArrayMediaDataSource? = null
    private var activeContinuation: CancellableContinuation<Unit>? = null
    private var activeEpoch: Long = 0L
    private var sessionEpoch: Long = 0L
    private val lock = Any()

    private val releaseExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "AndroidAudioPlayer-release").apply { isDaemon = true }
        }
    private val timeoutScheduler: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AndroidAudioPlayer-timeout").apply { isDaemon = true }
        }

    // prepare 超時任務追蹤：epoch -> ScheduledFuture，避免完成後仍持有 player+continuation 15s。
    // 終端路徑（completion/error/start 異常/stop/detach 成功）一律 cancel(false)。
    private val timeoutFutures = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ScheduledFuture<*>>()

    private fun cancelTimeout(epoch: Long) {
        try {
            timeoutFutures.remove(epoch)?.cancel(false)
        } catch (_: Throwable) {
        }
    }

    /**
     * 釋放內部背景執行緒池（release + timeout）。
     * 兩池皆為 daemon 線程，不關閉亦不會阻塞 JVM/測試結束；但長期持有（每實例兩池）
     * 會在大量測試中累積閒置線程，故提供顯式關閉。呼叫後新的 releaseAsync 會退回同步釋放。
     */
    fun release() {
        // Orderly shutdown：先丟棄 pending prepare 超時（stop 已 cancel 對應 epoch，丟棄無洩漏），
        // 再 orderly 關閉 release 池，確保 shutdown 前由 stop() enqueue 的 releaseAsync 不被丟棄。
        // 切勿對 releaseExecutor 用 shutdownNow：會丟棄佇列中剛 enqueue 的 native reset/release 造成洩漏。
        try {
            timeoutScheduler.shutdownNow()
        } catch (_: Throwable) {
        }
        try {
            releaseExecutor.shutdown()
        } catch (_: Throwable) {
        }
        try {
            releaseExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    /** [release] 的別名，供 try-with-resources / Closeable 語義使用。 */
    fun close() = release()

    private data class DetachedSession(
        val player: MediaPlayer?,
        val dataSource: ByteArrayMediaDataSource?,
        val continuation: CancellableContinuation<Unit>?,
        val epoch: Long,
    )

    private fun detachIfActiveLocked(player: MediaPlayer, epoch: Long): DetachedSession? {
        if (activePlayer === player && activeEpoch == epoch) {
            val p = activePlayer
            val ds = activeDataSource
            val cont = activeContinuation
            val ep = activeEpoch
            activePlayer = null
            activeDataSource = null
            activeContinuation = null
            return DetachedSession(p, ds, cont, ep)
        }
        return null
    }

    private fun detachAnyLocked(): DetachedSession {
        val p = activePlayer
        val ds = activeDataSource
        val cont = activeContinuation
        val ep = activeEpoch
        activePlayer = null
        activeDataSource = null
        activeContinuation = null
        return DetachedSession(p, ds, cont, ep)
    }

    // 注意：此函式絕對不可在 synchronized(lock) 或 synchronized(player) 內呼叫，
    // 所有 native（listener 清除/reset/release）皆在鎖外執行，避免阻塞持有鎖的 stop()/play()。
    private fun safelyReleasePlayer(player: MediaPlayer?, dataSource: ByteArrayMediaDataSource?) {
        if (player != null) {
            try {
                player.setOnPreparedListener(null)
            } catch (_: Throwable) {
            }
            try {
                player.setOnCompletionListener(null)
            } catch (_: Throwable) {
            }
            try {
                player.setOnErrorListener(null)
            } catch (_: Throwable) {
            }
            try {
                player.reset()
            } catch (_: Throwable) {
            }
            try {
                player.release()
            } catch (_: Throwable) {
            }
        }
        if (dataSource != null) {
            try {
                dataSource.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseAsync(detached: DetachedSession) {
        if (detached.player == null && detached.dataSource == null) return
        try {
            releaseExecutor.execute {
                safelyReleasePlayer(detached.player, detached.dataSource)
            }
        } catch (_: Throwable) {
            // 執行緒池已關閉等極端情況，退回同步釋放，避免洩漏
            safelyReleasePlayer(detached.player, detached.dataSource)
        }
    }

    private fun tryResumeUnit(cont: CancellableContinuation<Unit>) {
        try {
            cont.resume(Unit)
        } catch (_: IllegalStateException) {
            // TOCTOU：已被 resume/cancel，忽略
        } catch (_: CancellationException) {
        } catch (_: Throwable) {
        }
    }

    private fun tryResumeException(cont: CancellableContinuation<Unit>, e: Throwable) {
        try {
            cont.resumeWithException(e)
        } catch (_: IllegalStateException) {
            // TOCTOU：已被 resume/cancel，忽略
        } catch (_: CancellationException) {
        } catch (_: Throwable) {
        }
    }

    private fun tryCancel(cont: CancellableContinuation<Unit>?, message: String) {
        if (cont == null) return
        try {
            cont.cancel(CancellationException(message))
        } catch (_: Throwable) {
        }
    }

    override suspend fun play(audioBytes: ByteArray): Unit = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine

        val player = try {
            mediaPlayerFactory()
        } catch (e: Throwable) {
            tryResumeException(continuation, e)
            return@suspendCancellableCoroutine
        }
        val dataSource = try {
            // copy=false：零拷貝路徑，避免額外 ByteArray 拷貝（記憶體考量）。
            // RequiresNoMutation 語義：傳入 array 在播放期間不可被呼叫端修改，
            // 否則讀取結果未定義。result.audioBytes 由 client 全新配置，滿足此契約。
            // 詳見 ByteArrayMediaDataSource KDoc 與 copyFalse 釘選測試。
            ByteArrayMediaDataSource(audioBytes, copy = false)
        } catch (e: Throwable) {
            // resume 先行，釋放投背景，避免阻塞呼叫線程。
            tryResumeException(continuation, e)
            releaseAsync(DetachedSession(player, null, null, -1L))
            return@suspendCancellableCoroutine
        }

        val myEpoch: Long?
        val oldDetached: DetachedSession?
        synchronized(lock) {
            if (!continuation.isActive) {
                myEpoch = null
                oldDetached = null
            } else {
                sessionEpoch += 1
                myEpoch = sessionEpoch
                oldDetached = DetachedSession(activePlayer, activeDataSource, activeContinuation, activeEpoch)
                activePlayer = player
                activeDataSource = dataSource
                activeContinuation = continuation
                activeEpoch = myEpoch
            }
        }

        if (myEpoch == null || oldDetached == null) {
            // 進入鎖前已被取消：從未成為 active，直接釋放新建資源並主動取消，避免懸掛。
            // resume 先行，釋放投背景。
            tryCancel(continuation, "Audio play cancelled before claim (epoch pending)")
            releaseAsync(DetachedSession(player, dataSource, null, -1L))
            return@suspendCancellableCoroutine
        }
        val epoch: Long = myEpoch

        // 釋放被取代的舊世代：cancel 原因攜帶 epoch；native 釋放走背景執行緒，不阻塞新播放。
        // 同時取消舊世代的 prepare 超時任務，避免其持有舊 player+continuation 15s。
        if (oldDetached.player != null || oldDetached.continuation != null || oldDetached.dataSource != null) {
            tryCancel(oldDetached.continuation, "Superseded by audio play epoch $epoch")
            cancelTimeout(oldDetached.epoch)
            releaseAsync(oldDetached)
        }

        continuation.invokeOnCancellation {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player, epoch)
            }
            if (detached != null) {
                cancelTimeout(epoch)
                releaseAsync(detached)
            }
        }

        fun isStillActive(): Boolean =
            synchronized(lock) { activePlayer === player && activeEpoch == epoch } && continuation.isActive

        // 世代柵欄：已失格（被 stop/新 play 取代）即主動釋放並取消，不依賴外部。
        if (!isStillActive()) {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player, epoch)
            }
            if (detached != null) {
                tryCancel(continuation, "Audio play superseded before setDataSource (epoch $epoch)")
                cancelTimeout(epoch)
                releaseAsync(detached)
            } else {
                tryCancel(continuation, "Audio play superseded before setDataSource (epoch $epoch, detached)")
            }
            return@suspendCancellableCoroutine
        }

        // native setDataSource：在鎖外執行，若被 stop 阻塞中，stop 僅 detach+cancel 不等待此處。
        try {
            player.setDataSource(dataSource)
        } catch (e: Throwable) {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player, epoch)
            }
            if (detached != null) {
                // resume 先行，釋放投背景，避免阻塞呼叫線程。
                // setDataSource 失敗屬播放失敗（非取消），以原例外恢復，避免誤報 CANCELLED。
                tryResumeException(continuation, e)
                cancelTimeout(epoch)
                releaseAsync(detached)
            }
            return@suspendCancellableCoroutine
        }

        if (!isStillActive()) {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player, epoch)
            }
            if (detached != null) {
                tryCancel(continuation, "Audio play superseded after setDataSource (epoch $epoch)")
                cancelTimeout(epoch)
                releaseAsync(detached)
            } else {
                tryCancel(continuation, "Audio play superseded after setDataSource (epoch $epoch, detached)")
            }
            return@suspendCancellableCoroutine
        }

        try {
            player.setOnPreparedListener { mp ->
                val shouldStart =
                    synchronized(lock) { activePlayer === mp && activeEpoch == epoch } && continuation.isActive
                if (!shouldStart) return@setOnPreparedListener

                var startException: Exception? = null
                try {
                    // start 前再次柵欄確認，native 本身在鎖外執行
                    val ok =
                        synchronized(lock) { activePlayer === mp && activeEpoch == epoch } && continuation.isActive
                    if (ok) {
                        mp.start()
                    }
                } catch (e: Exception) {
                    startException = e
                }

                if (startException != null) {
                    val detached = synchronized(lock) {
                        detachIfActiveLocked(mp, epoch)
                    }
                    if (detached != null) {
                        // resume 先行，釋放投背景，避免在（可能為主線程的）回呼線程阻塞 native。
                        tryResumeException(continuation, startException)
                        cancelTimeout(epoch)
                        releaseAsync(detached)
                    }
                }
            }
            player.setOnCompletionListener {
                val detached = synchronized(lock) {
                    detachIfActiveLocked(player, epoch)
                }
                if (detached != null) {
                    // resume 先行，釋放投背景。
                    tryResumeUnit(continuation)
                    cancelTimeout(epoch)
                    releaseAsync(detached)
                }
            }
            player.setOnErrorListener { _, what, extra ->
                val detached = synchronized(lock) {
                    detachIfActiveLocked(player, epoch)
                }
                if (detached != null) {
                    // resume 先行，釋放投背景。
                    tryResumeException(
                        continuation,
                        IOException("MediaPlayer 播放錯誤：what=$what, extra=$extra (epoch $epoch)"),
                    )
                    cancelTimeout(epoch)
                    releaseAsync(detached)
                }
                true
            }
        } catch (e: Throwable) {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player, epoch)
            }
            if (detached != null) {
                tryResumeException(continuation, e)
                cancelTimeout(epoch)
                releaseAsync(detached)
            }
            return@suspendCancellableCoroutine
        }

        if (!isStillActive()) {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player, epoch)
            }
            if (detached != null) {
                tryCancel(continuation, "Audio play superseded before prepareAsync (epoch $epoch)")
                cancelTimeout(epoch)
                releaseAsync(detached)
            } else {
                tryCancel(continuation, "Audio play superseded before prepareAsync (epoch $epoch, detached)")
            }
            return@suspendCancellableCoroutine
        }

        try {
            // 非同步 prepareAsync 本身在鎖外呼叫，不阻塞鎖；stop 可隨時 detach。
            player.prepareAsync()
        } catch (e: Throwable) {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player, epoch)
            }
            if (detached != null) {
                tryResumeException(continuation, e)
                cancelTimeout(epoch)
                releaseAsync(detached)
            }
            return@suspendCancellableCoroutine
        }

        // prepare 超時守衛：逾時即 detach + 釋放 + resumeWithException，不永久懸掛。
        // ScheduledFuture 保留於 timeoutFutures，終端路徑一律 cancel(false)，避免持有 player+continuation 15s。
        try {
            val future = timeoutScheduler.schedule({
                val detached = synchronized(lock) {
                    if (activePlayer === player && activeEpoch == epoch && continuation.isActive) {
                        detachIfActiveLocked(player, epoch)
                    } else {
                        null
                    }
                }
                if (detached != null) {
                    timeoutFutures.remove(epoch)
                    // resume 先行，釋放投背景。
                    tryResumeException(
                        continuation,
                        java.util.concurrent.TimeoutException(
                            "MediaPlayer prepare timed out after $PREPARE_TIMEOUT_MS ms (epoch $epoch)",
                        ),
                    )
                    releaseAsync(detached)
                } else {
                    timeoutFutures.remove(epoch)
                }
            }, PREPARE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            // 僅當仍為 active 才保留，否則立即取消（已在排程期間被 stop/取代）。
            val stillActive = synchronized(lock) { activePlayer === player && activeEpoch == epoch }
            if (stillActive) {
                timeoutFutures[epoch] = future
            } else {
                try {
                    future.cancel(false)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            // 排程失敗不影響播放主流程
        }
    }

    override fun stop() {
        val detached = synchronized(lock) {
            val d = detachAnyLocked()
            // 遞增世代使進行中 play 的柵欄失效，遲到回呼永不 start
            sessionEpoch += 1
            activeEpoch = sessionEpoch
            d
        }
        tryCancel(detached.continuation, "AndroidAudioPlayer stopped (epoch ${detached.epoch})")
        cancelTimeout(detached.epoch)
        // native 釋放在背景執行緒，呼叫線程僅做 detach+cancel，有界返回
        releaseAsync(detached)
    }
}

/**
 * OpenAI 雲端語音引擎（主源集，支援全部 flavor）。
 *
 * 生命週期與過渡聲明（短期過渡 Legacy Bridge）：
 * 目前底層 [OpenAiTtsClient] 所使用的 `POST /v1/audio/speech`（`tts-1` / `tts-1-hd`）為**短期過渡 Legacy Bridge**。
 * OpenAI 官方已公告此端點／模型的退場時間為 **2027-01-06**（[SUNSET_DATE]）。
 * 未來遷移目標為 Realtime API (`gpt-realtime-2.1-mini`，見 [MIGRATION_TARGET]）。
 *
 * 安全不變量與架構保證：
 * 1. 雙開關門禁：`voice_output` 與 `openai_tts` 皆開才可用（見 [OpenAiSpeechGate]）；
 * 2. 脫敏送雲：送往 OpenAI 之前一律透過 [VoiceTools.redactForCloud] 遮罩敏感資訊；
 * 3. AI 生成語音揭露：本功能語音為 AI 生成，非真人聲音（AI-generated voice, not a human voice）；
 * 4. 受限記憶體緩衝播放（記憶體行為校準）：遵循音檔不寫入儲存空間之契約，透過 [AudioPlayer]（預設 [AndroidAudioPlayer] + [ByteArrayMediaDataSource]）
 *    直接從記憶體 byte array 播放，絕不寫入檔案系統（零 File/TempFile 引用）。
 *    誠實校準記憶體行為描述：音訊在網路下載後全額緩衝於記憶體中（Bounded in-memory buffering），
 *    在合成與播放峰值時存在多份 ByteArray 拷貝（OkHttp response buffer、合成結果 ByteArray、
 *    ByteArrayMediaDataSource 內部 clone 等），25MB 上限可降低但無法杜絕極限情況的 OOM 風險；
 * 5. 支援中斷與世代防護：透過 [speechEpoch] 與 [stopEpoch] 世代機制與 [activeJobs] 追蹤，[stop] 遞增世代並取消 activeJob 與停止播放器，
 *    後續完成的合成或延遲回調若發現世代不符則立即拋棄，絕不送出播放；
 * 6. 金鑰彈性來源：除獨立的 `VoiceTools.OPENAI_KEY_REF`（`"openai_tts"`），亦支援可選 key supplier 或作用中 OpenAI endpoint；
 * 7. 測試友善：支援注入 [AudioPlayer] 與 [OpenAiTtsClient]，便於 JVM 單元測試。
 */
class OpenAiSpeechEngine(
    val client: OpenAiTtsClient = OpenAiTtsClient(),
    val player: AudioPlayer = AndroidAudioPlayer(),
    val vault: KeyVault? = null,
    val endpointStore: EndpointStore? = null,
    val apiKeySupplier: (suspend () -> CharArray?)? = null,
    val apiKeySupplierOriginUrl: String? = null,
    val allowTestLoopback: Boolean = false,
) {
    private val activeJob = AtomicReference<Job?>(null)
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()
    private val speechEpoch = AtomicLong(0L)
    private val stopEpoch = AtomicLong(0L)
    // 世代躍遷互斥：speechEpoch 遞增 + 舊工作快照/清除 + 新工作註冊必須在同一臨界區完成，
    // 使僅有 winner 能取消舊世代，舊請求絕不取消新請求。stop() 亦使用同一鎖。
    private val generationLock = Any()

    /** 測試專用柵欄：在世代躍遷臨界區外、遞增 epoch 後回調，允許測試暫停舊請求以驗證 winner 語義。生產為 null。
   * 僅測試使用，呼叫端必須自帶超時（例如 `CountDownLatch.await(5, SECONDS)`），且絕不在持有
   * [generationLock] 時阻塞，以免卡住 [stop]。實作上柵欄已移出鎖外，stop 可隨時中斷。 */
    @Volatile
    internal var epochClaimBarrierForTest: ((Long) -> Unit)? = null

    companion object {
        /**
         * 官方已公告 POST /v1/audio/speech (tts-1 / tts-1-hd) 之退場時間為 2027-01-06。
         * 此端點為短期過渡 Legacy Bridge，未來遷移目標為 Realtime API (gpt-realtime-2.1-mini)。
         */
        const val SUNSET_DATE = OpenAiTtsModels.SUNSET_DATE
        const val MIGRATION_TARGET = OpenAiTtsModels.MIGRATION_TARGET
        const val IS_LEGACY_BRIDGE = OpenAiTtsModels.IS_LEGACY_BRIDGE

        const val KEY_REF = VoiceTools.OPENAI_KEY_REF
        const val OPENAI_ENDPOINT_KEY_REF = "preset:openai"
        const val DEFAULT_VOICE = OpenAiTtsModels.DEFAULT_VOICE
        const val DEFAULT_MODEL = OpenAiTtsModels.DEFAULT_MODEL

        const val DETAIL_SWITCH_OFF = "SWITCH_OFF"
        const val DETAIL_NO_KEY = "NO_KEY"
        const val DETAIL_EMPTY_TEXT = "EMPTY_TEXT"
        const val DETAIL_PLAYBACK_ERROR = "PLAYBACK_ERROR"
        const val DETAIL_CANCELLED = "CANCELLED"

        sealed interface Check {
            data object Allowed : Check
            data class Denied(val reason: DenyReason, val detail: String, val message: String) : Check
        }

        sealed interface SynthOutcome {
            data object Ok : SynthOutcome
            data class Denied(val reason: DenyReason, val detail: String, val message: String) : SynthOutcome
            data class Failed(val detail: String, val message: String) : SynthOutcome
        }

        private fun denied(reason: DenyReason, detail: String, need: String): Check.Denied =
            Check.Denied(
                reason = reason,
                detail = detail,
                message = S1bFallback.message(
                    what = "OpenAI 語音（$detail）",
                    reason = reason,
                    detail = detail,
                    alternative = "use the system voice output, or read the text on screen manually",
                    needFromUser = need,
                ),
            )

        /**
         * 嚴格驗證是否為官方 OpenAI 端點網址。
         *
         * 安全規則：
         * - scheme 必須是 "https"
         * - host 必須嚴格等於 "api.openai.com"（不允許任意子網域或前後綴）
         * - port 必須為 -1（未指定）或 443（HTTPS 預設埠）
         * - 不得包含 userInfo 使用者名稱或密碼
         */
        internal fun isOpenAiOfficialEndpoint(baseUrl: String): Boolean =
            OpenAiTtsClient.isOpenAiOfficialEndpoint(baseUrl)

        /**
         * 判定端點組態是否為官方 OpenAI 端點。
         * 必須嚴格驗證預設識別碼、命名空間與 baseUrl 為官方端點，避免自訂或惡意端點金鑰被取用或誤送。
         */
        internal fun isOpenAiEndpoint(config: EndpointConfig): Boolean = when (config.presetId) {
            ProviderCatalog.OPENAI_ID -> {
                config.providerId == SetupValidation.deriveProviderId(ProviderCatalog.OPENAI_ID) &&
                    isOpenAiOfficialEndpoint(config.baseUrl)
            }
            ProviderCatalog.CUSTOM_ID -> {
                config.providerId == SetupValidation.deriveEndpointId(ProviderCatalog.CUSTOM_ID, config.baseUrl) &&
                    isOpenAiOfficialEndpoint(config.baseUrl)
            }
            else -> false
        }

        /**
         * 檢查目標端點是否為本機測試 loopback（localhost, 127.0.0.1, ::1 等）。
         */
        internal fun isLoopbackEndpoint(baseUrl: String): Boolean {
            val httpUrl = baseUrl.trim().toHttpUrlOrNull() ?: return false
            return OpenAiTtsClient.isLoopbackHost(httpUrl.host)
        }

        /**
         * 比對兩個端點位址經解析後是否指向同一語音端點目標。
         */
        internal fun isSameEndpoint(baseUrl1: String, baseUrl2: String): Boolean {
            val e1 = runCatching { OpenAiTtsClient.resolveEndpoint(baseUrl1).toHttpUrlOrNull() }.getOrNull() ?: return false
            val e2 = runCatching { OpenAiTtsClient.resolveEndpoint(baseUrl2).toHttpUrlOrNull() }.getOrNull() ?: return false
            return e1 == e2
        }

        /**
         * 解析金鑰之來源識別。
         */
        sealed interface ResolvedKey {
            val keyRef: String

            /** 官方 OpenAI 金鑰（"openai_tts" 或 "preset:openai"） */
            data class OfficialOpenAi(override val keyRef: String) : ResolvedKey

            /** 來自 EndpointStore 特定端點的金鑰 */
            data class EndpointStoreBound(override val keyRef: String, val endpointBaseUrl: String) : ResolvedKey

            /** 自訂金鑰供應器（若未明確指定 originTargetUrl 則視為官方端點專用） */
            data class CustomSupplier(
                val originTargetUrl: String? = null,
                override val keyRef: String = "custom_supplier",
            ) : ResolvedKey
        }

        /**
         * 驗證金鑰與目標端點的綁定安全契約：
         * 在 OpenAI TTS 路徑中，安全契約嚴格限制為「僅允許官方 OpenAI 端點（https://api.openai.com），測試環境（allowTestLoopback = true）才額外允許 loopback」。
         * 一律拒絕任何第三方非官方端點綁定，不允許將金鑰發往任何非官方端點。
         *
         * 1. targetBaseUrl 必須是官方 OpenAI 端點（isOpenAiOfficialEndpoint），或在明確啟用測試隔離旗標（allowTestLoopback == true）時為本機 loopback 端點。其餘第三方端點一律拒絕。
         * 2. 若取用的金鑰是官方 OpenAI 來源（KEY_REF 或 preset:openai），遵循上述安全政策。
         * 3. 若金鑰來自 EndpointStore 的特定端點：該端點必須為官方 OpenAI 端點，若為第三方自訂端點一律拒絕。
         * 4. 自訂金鑰供應器（ResolvedKey.CustomSupplier）：
         *    - 若未明確綁定特定目標端點（originTargetUrl == null），視為官方端點專用金鑰；
         *    - 若有指定 originTargetUrl，該 originTargetUrl 也必須是官方端點（或測試隔離下的 loopback）。任何第三方非官方端點綁定一律拒絕。
         */
        internal fun isKeyTargetBindingAllowed(
            resolvedKey: ResolvedKey,
            targetBaseUrl: String,
            allowTestLoopback: Boolean = false,
        ): Boolean {
            val targetAllowed = isOpenAiOfficialEndpoint(targetBaseUrl) ||
                (allowTestLoopback && isLoopbackEndpoint(targetBaseUrl))
            if (!targetAllowed) return false

            return when (resolvedKey) {
                is ResolvedKey.OfficialOpenAi -> true
                is ResolvedKey.EndpointStoreBound -> {
                    if (!isOpenAiOfficialEndpoint(resolvedKey.endpointBaseUrl)) return false
                    val openAiProviderId = SetupValidation.deriveProviderId(ProviderCatalog.OPENAI_ID)
                    val customEndpointId = SetupValidation.deriveEndpointId(ProviderCatalog.CUSTOM_ID, resolvedKey.endpointBaseUrl)
                    val apiKeyRef = SetupValidation.deriveApiKeyRef(resolvedKey.keyRef)
                    resolvedKey.keyRef == openAiProviderId ||
                        SetupValidation.aliasInNamespace(openAiProviderId, apiKeyRef) ||
                        SetupValidation.aliasInNamespace(customEndpointId, apiKeyRef)
                }
                is ResolvedKey.CustomSupplier -> {
                    when {
                        resolvedKey.originTargetUrl == null -> true
                        isOpenAiOfficialEndpoint(resolvedKey.originTargetUrl) -> true
                        allowTestLoopback && isLoopbackEndpoint(resolvedKey.originTargetUrl) -> {
                            isLoopbackEndpoint(targetBaseUrl)
                        }
                        else -> false
                    }
                }
            }
        }

        /**
         * 解析 KeyVault 中的金鑰與來源：
         * 1. 獨立專用金鑰 [KEY_REF]（"openai_tts"）優先且獨立保持；
         * 2. 若傳入 [endpointStore]：
         *    - 改用 [EndpointStore.snapshotDeleteStateStrict] 讀取快照；
         *    - 若讀取拋出任何例外（DataStore 讀取失敗），立即 fail-closed 回傳 null，嚴禁向下降級回退到 preset:openai；
         *    - 若 pendingDelete != null（有 pending delete 正在進行），立即 fail-closed 回傳 null，嚴禁向下降級；
         *    - 若 config == null，立即 fail-closed 回傳 null，嚴禁向下降級；
         *    - 嚴格檢查三項端點安全條件：
         *      1. [isOpenAiEndpoint]（必須是官方 OpenAI URL）
         *      2. ![SetupValidation.isLegacyEndpoint]（嚴格拒絕 legacy 自訂端點，防範跨 provider 憑證洩漏）
         *      3. [SetupValidation.aliasInNamespace]（驗證 apiKeyRef 必須在 providerId 命名空間內）
         *      若有任一不符，立即 fail-closed 回傳 null（在 hasKey 或 getKey 前立即返回，零 vault 讀取，零 HTTP 請求，嚴禁向下降級）；
         *    - 通過所有檢查後，取出 alias = SetupValidation.aliasFromRef(config.apiKeyRef)，若 [tryHasKey] 為 true 則回傳 [ResolvedKey.EndpointStoreBound]，否則回傳 null（嚴禁向下降級到 preset:openai）；
         * 3. 僅在 endpointStore == null 時，才允許檢查 [OPENAI_ENDPOINT_KEY_REF]（"preset:openai"）。
         */
        internal suspend fun resolveBoundKey(
            vault: KeyVault,
            endpointStore: EndpointStore? = null,
        ): ResolvedKey? {
            if (tryHasKey(vault, KEY_REF)) return ResolvedKey.OfficialOpenAi(KEY_REF)
            if (endpointStore != null) {
                val (pendingDelete, config, _) = try {
                    endpointStore.snapshotDeleteStateStrict()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return null
                }
                if (pendingDelete != null) return null
                if (config == null) return null
                if (!isOpenAiEndpoint(config)) return null
                if (SetupValidation.isLegacyEndpoint(config)) return null
                if (!SetupValidation.aliasInNamespace(config.providerId, config.apiKeyRef)) return null

                val alias = SetupValidation.aliasFromRef(config.apiKeyRef)
                return if (tryHasKey(vault, alias)) {
                    ResolvedKey.EndpointStoreBound(alias, config.baseUrl)
                } else {
                    null
                }
            }
            if (tryHasKey(vault, OPENAI_ENDPOINT_KEY_REF)) {
                return ResolvedKey.OfficialOpenAi(OPENAI_ENDPOINT_KEY_REF)
            }
            return null
        }

        /**
         * 統一解析金鑰來源為 [ResolvedKey] 體系。
         */
        internal suspend fun resolveEffectiveKey(
            vault: KeyVault?,
            endpointStore: EndpointStore? = null,
            supplier: (suspend () -> CharArray?)? = null,
            supplierOriginUrl: String? = null,
            hasClientSupplier: Boolean = false,
        ): ResolvedKey? = when {
            supplier != null -> ResolvedKey.CustomSupplier(originTargetUrl = supplierOriginUrl)
            vault != null -> {
                val bound = resolveBoundKey(vault, endpointStore)
                bound ?: if (hasClientSupplier) ResolvedKey.CustomSupplier(originTargetUrl = supplierOriginUrl) else null
            }
            hasClientSupplier -> ResolvedKey.CustomSupplier(originTargetUrl = supplierOriginUrl)
            else -> null
        }

        /**
         * 解析 KeyVault 中的金鑰識別名：
         * 1. 獨立的 `KEY_REF`（"openai_tts"）；
         * 2. 若傳入 `endpointStore`，自其作用中的 OpenAI 端點取得金鑰別名（fail-closed，嚴禁向下降級）；
         * 3. 預設 OpenAI 端點別名 "preset:openai"（僅在 `endpointStore == null` 時）。
         */
        suspend fun resolveKeyRef(
            vault: KeyVault,
            endpointStore: EndpointStore? = null,
        ): String? = resolveBoundKey(vault, endpointStore)?.keyRef

        private suspend fun tryHasKey(vault: KeyVault, providerId: String): Boolean =
            try {
                vault.hasKey(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }

        /** 純開關與金鑰標記門禁檢查（靜態純函數）。 */
        fun check(
            voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
            openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
            hasKey: Boolean = false,
            flavor: Flavor = Flavor.PLAY,
        ): Check {
            val denyReason = OpenAiSpeechGate.denyReason(flavor, voiceOutputOn, openAiTtsOn, hasKey)
            if (denyReason != null) {
                val detail = when (denyReason) {
                    DenyReason.USER_DISABLED -> DETAIL_SWITCH_OFF
                    DenyReason.NO_PRIVILEGE -> DETAIL_NO_KEY
                    else -> denyReason.name
                }
                val need = when (denyReason) {
                    DenyReason.USER_DISABLED -> "到設定同時開啟「語音輸出」與「OpenAI 語音」後重試"
                    DenyReason.NO_PRIVILEGE -> "先在設定寫入 OpenAI 金鑰後重試"
                    else -> "檢查語音設定"
                }
                return denied(denyReason, detail, need)
            }
            return Check.Allowed
        }

        /** 帶 KeyVault 的靜態門禁檢查。 */
        suspend fun check(
            voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
            openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
            vault: KeyVault,
            flavor: Flavor = Flavor.PLAY,
            endpointStore: EndpointStore? = null,
            apiKeySupplier: (suspend () -> CharArray?)? = null,
            targetBaseUrl: String? = null,
            apiKeySupplierOriginUrl: String? = null,
            allowTestLoopback: Boolean = false,
        ): Check {
            val resolved = resolveEffectiveKey(
                vault = vault,
                endpointStore = endpointStore,
                supplier = apiKeySupplier,
                supplierOriginUrl = apiKeySupplierOriginUrl,
                hasClientSupplier = false,
            )
            val hasKey = when {
                resolved == null -> false
                targetBaseUrl != null -> isKeyTargetBindingAllowed(resolved, targetBaseUrl, allowTestLoopback)
                else -> true
            }
            return check(voiceOutputOn, openAiTtsOn, hasKey, flavor)
        }
    }

    /** 實例門禁檢查。 */
    suspend fun check(
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
        vaultOverride: KeyVault? = null,
        flavor: Flavor = Flavor.PLAY,
        endpointStoreOverride: EndpointStore? = null,
        apiKeySupplierOverride: (suspend () -> CharArray?)? = null,
        apiKeySupplierOverrideOriginUrl: String? = null,
        allowTestLoopback: Boolean = this.allowTestLoopback,
    ): Check {
        val effectiveVault = vaultOverride ?: this.vault
        val effectiveEndpointStore = endpointStoreOverride ?: this.endpointStore
        val (effectiveSupplier, effectiveOriginUrl) = if (apiKeySupplierOverride != null) {
            apiKeySupplierOverride to apiKeySupplierOverrideOriginUrl
        } else {
            this.apiKeySupplier to this.apiKeySupplierOriginUrl
        }

        val resolved = resolveEffectiveKey(
            vault = effectiveVault,
            endpointStore = effectiveEndpointStore,
            supplier = effectiveSupplier,
            supplierOriginUrl = effectiveOriginUrl,
            hasClientSupplier = client.hasApiKeySupplier,
        )

        val hasKey = resolved != null && isKeyTargetBindingAllowed(resolved, client.baseUrl, allowTestLoopback)
        return Companion.check(voiceOutputOn, openAiTtsOn, hasKey, flavor)
    }

    /** 帶 hasKey 覆寫的實例門禁檢查。 */
    fun check(
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
        hasKey: Boolean,
        flavor: Flavor = Flavor.PLAY,
    ): Check = Companion.check(voiceOutputOn, openAiTtsOn, hasKey, flavor)

    /**
     * 雲端語音合成並於受限記憶體緩衝中播放音訊（不寫入儲存空間，上限 25MB）。
     *
     * 取消契約（統一）：
     * 因新請求取代（superseded）或 [stop] 中斷而失格者，一律正常回傳 `Failed(DETAIL_CANCELLED)`，
     * 不拋出 [CancellationException]；僅當呼叫端 parent Job 本身已被外部明確取消（scope cancel）
     * 時，才重新拋出 [CancellationException]（標準結構化併發語義，優先於世代/stop 狀態）。
     * 所有內部取消原因皆攜帶世代/原因（`Superseded by speech request epoch …` /
     * `Speech synthesis stopped (epoch …)`），與 [AndroidAudioPlayer] 取消原因格式統一。
     *
     * @param text 要合成的文字（送雲前會先執行 [VoiceTools.redactForCloud] 脫敏）。
     * @param vault 提供 OpenAI API 金鑰的 [KeyVault]（未傳則使用建構子注入者）。
     * @param voiceOutputOn 系統語音輸出開關狀態（預設 true）。
     * @param openAiTtsOn OpenAI 語音開關狀態（預設 false，fail-closed）。
     * @param model OpenAI TTS 模型識別碼（預設 "tts-1"）。
     * @param voice OpenAI 發音人識別碼（預設 "alloy"）。
     * @param responseFormat 音訊格式（預設 "mp3"）。
     * @param speed 播放速度（預設 1.0f）。
     * @param apiKeyOverride 測試或自訂金鑰提供者。
     * @param flavor 運行的風味環境（全風味支援）。
     * @param endpointStore 端點存放庫（用於取得作用中端點金鑰）。
     * @param apiKeyOverrideOriginUrl [apiKeyOverride] 所綁定之目標端點網址（若為 null 則視為官方端點專用）。
     * @param allowTestLoopback 測試隔離旗標（預設取自實例設定，生產環境為 false）。
     * @return [SynthOutcome] 包含 [SynthOutcome.Ok], [SynthOutcome.Denied], 或 [SynthOutcome.Failed]。
     */
    suspend fun synthesizeAndPlay(
        text: String,
        vault: KeyVault? = null,
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
        model: String = OpenAiTtsModels.DEFAULT_MODEL,
        voice: String = OpenAiTtsModels.DEFAULT_VOICE,
        responseFormat: String = OpenAiTtsModels.DEFAULT_RESPONSE_FORMAT,
        speed: Float = OpenAiTtsModels.DEFAULT_SPEED,
        apiKeyOverride: (suspend () -> CharArray?)? = null,
        flavor: Flavor = Flavor.PLAY,
        endpointStore: EndpointStore? = null,
        apiKeyOverrideOriginUrl: String? = null,
        allowTestLoopback: Boolean = this.allowTestLoopback,
    ): SynthOutcome {
        // 外層取消轉換：withContext 子 Job 被取代/stop 取消時，內層 return Failed 會被 withContext 覆寫為拋出，
        // 故在呼叫端上下文統一轉換。結構化取消優先：只要呼叫端 parent Job 已取消，一律重拋
        // CancellationException；僅 parent 未取消（內部取代/stop）才回 Failed(CANCELLED)。
        try {
            return withContext(Dispatchers.IO) {
        val preCheckStopEpoch = stopEpoch.get()
        val callerJob = coroutineContext[Job]
        if (callerJob?.isActive == false) {
            return@withContext SynthOutcome.Failed(
                detail = DETAIL_CANCELLED,
                message = "語音合成已被中斷",
            )
        }

        // 1. 輕量開關門禁檢查（檢查不通過絕不遞增 speechEpoch 或中斷正在播放的工作）
        val switchDeny = OpenAiSpeechGate.denyReason(flavor, voiceOutputOn, openAiTtsOn, hasKey = true)
        if (switchDeny != null) {
            val d = denied(switchDeny, DETAIL_SWITCH_OFF, "到設定同時開啟「語音輸出」與「OpenAI 語音」後重試")
            return@withContext SynthOutcome.Denied(d.reason, d.detail, d.message)
        }

        // 2. 脫敏文字與空白檢查
        val redacted = VoiceTools.redactForCloud(text)
        if (redacted.isBlank()) {
            return@withContext SynthOutcome.Denied(
                reason = DenyReason.USER_DISABLED,
                detail = DETAIL_EMPTY_TEXT,
                message = S1bFallback.message(
                    what = "OpenAI 語音（$DETAIL_EMPTY_TEXT）",
                    reason = DenyReason.USER_DISABLED,
                    detail = DETAIL_EMPTY_TEXT,
                    alternative = "read the text on screen manually",
                    needFromUser = "提供要朗讀的文字",
                ),
            )
        }

        // 3. 基本金鑰存在性與端點綁定檢查
        val effectiveVault = vault ?: this@OpenAiSpeechEngine.vault
        val effectiveEndpointStore = endpointStore ?: this@OpenAiSpeechEngine.endpointStore
        val (effectiveSupplier, effectiveOriginUrl) = if (apiKeyOverride != null) {
            apiKeyOverride to apiKeyOverrideOriginUrl
        } else {
            this@OpenAiSpeechEngine.apiKeySupplier to this@OpenAiSpeechEngine.apiKeySupplierOriginUrl
        }

        val resolvedKey = resolveEffectiveKey(
            vault = effectiveVault,
            endpointStore = effectiveEndpointStore,
            supplier = effectiveSupplier,
            supplierOriginUrl = effectiveOriginUrl,
            hasClientSupplier = client.hasApiKeySupplier,
        )

        // 驗證金鑰與目標端點的綁定安全契約：嚴防 OpenAI 官方或自訂憑證送往非授權端點或未隔離之本機 loopback
        if (resolvedKey != null && !isKeyTargetBindingAllowed(resolvedKey, client.baseUrl, allowTestLoopback)) {
            val d = denied(DenyReason.NO_PRIVILEGE, DETAIL_NO_KEY, "先在設定寫入 OpenAI 金鑰後重試")
            return@withContext SynthOutcome.Denied(d.reason, d.detail, d.message)
        }

        val hasKey = resolvedKey != null && isKeyTargetBindingAllowed(resolvedKey, client.baseUrl, allowTestLoopback)
        when (val c = check(voiceOutputOn = voiceOutputOn, openAiTtsOn = openAiTtsOn, hasKey = hasKey, flavor = flavor)) {
            is Check.Denied -> return@withContext SynthOutcome.Denied(c.reason, c.detail, c.message)
            is Check.Allowed -> Unit
        }

        // 檢查期間若發生 stop 或呼叫端協程已被取消，直接返回取消，不進入合成流程
        if (stopEpoch.get() != preCheckStopEpoch || callerJob?.isActive == false) {
            return@withContext SynthOutcome.Failed(
                detail = DETAIL_CANCELLED,
                message = "語音合成已被中斷",
            )
        }

        // 前置檢查確認通過後，才在同一受控躍遷臨界區內決定 winner：遞增世代、快照舊工作並註冊新工作。
        // 僅 winner 能取消舊世代；舊請求快照不含新工作，故絕不取消新請求。
        // 測試柵欄刻意置於鎖外（僅測試用且呼叫端自帶超時），避免阻塞 stop()。
        val currentJob = coroutineContext[Job]
        val callEpoch: Long
        val jobsToCancel: List<Job>
        val claimAborted: Boolean
        synchronized(generationLock) {
            if (stopEpoch.get() != preCheckStopEpoch || currentJob?.isActive == false) {
                callEpoch = -1L
                jobsToCancel = emptyList()
                claimAborted = true
            } else {
                val epoch = speechEpoch.incrementAndGet()
                val prevActive = activeJob.get()
                val snapshot = (activeJobs.toList() + listOfNotNull(prevActive))
                    .distinct()
                    .filter { it !== currentJob }
                activeJobs.clear()
                if (currentJob != null) {
                    activeJobs.add(currentJob)
                    activeJob.set(currentJob)
                } else {
                    activeJob.set(null)
                }
                callEpoch = epoch
                jobsToCancel = snapshot
                claimAborted = false
            }
        }
        // 測試專用柵欄：已移出 generationLock，避免持有鎖阻塞 stop/新請求。
        // 僅測試注入且必須自帶超時；生產為 null。此處暫停舊 claim，讓新請求有機會成為 winner，
        // 語義與鎖內暫停等價（舊 epoch 已佔位，新請求將遞增為更大 epoch 成為 winner）。
        if (!claimAborted) {
            try {
                epochClaimBarrierForTest?.invoke(callEpoch)
            } catch (_: Throwable) {
            }
        }
        if (claimAborted) {
            return@withContext SynthOutcome.Failed(
                detail = DETAIL_CANCELLED,
                message = "語音合成已被中斷",
            )
        }
        jobsToCancel.forEach { it.cancel(CancellationException("Superseded by speech request epoch $callEpoch")) }

        if (callEpoch != speechEpoch.get() || currentJob?.isActive == false) {
            return@withContext SynthOutcome.Failed(
                detail = DETAIL_CANCELLED,
                message = "語音合成已被中斷",
            )
        }

        try {
            if (callEpoch != speechEpoch.get() || currentJob?.isActive == false) {
                return@withContext SynthOutcome.Failed(
                    detail = DETAIL_CANCELLED,
                    message = "語音合成已被中斷",
                )
            }

            val request = OpenAiTtsRequest(
                input = redacted,
                model = model,
                voice = voice,
                responseFormat = responseFormat,
                speed = speed,
            )

            val keySupplier: (suspend () -> CharArray?)? = effectiveSupplier ?: (if (effectiveVault != null && resolvedKey != null && resolvedKey !is ResolvedKey.CustomSupplier) {
                { effectiveVault.getKey(resolvedKey.keyRef) }
            } else null)

            val result = client.synthesize(
                request = request,
                apiKeyOverride = keySupplier,
            )

            // 音訊合成完成後，播放前確認目前 epoch 依然匹配，避免已被 stop 的舊音訊播放
            if (callEpoch != speechEpoch.get() || currentJob?.isActive == false) {
                return@withContext SynthOutcome.Failed(
                    detail = DETAIL_CANCELLED,
                    message = "語音合成已被中斷",
                )
            }

            player.play(result.audioBytes)

            if (callEpoch != speechEpoch.get() || currentJob?.isActive == false) {
                return@withContext SynthOutcome.Failed(
                    detail = DETAIL_CANCELLED,
                    message = "語音合成已被中斷",
                )
            }

            SynthOutcome.Ok
        } catch (e: OpenAiTtsException) {
            SynthOutcome.Failed(
                detail = e.errorCode.name,
                message = e.safeMessage,
            )
        } catch (e: Exception) {
            // CancellationException 已由 Kotlin 協程單獨處理（見外層 catch），此處僅處理非取消失敗。
            // 若上游拋出 CancellationException 子類但非取消語義，仍視為播放失敗，避免誤吞外部取消。
            if (e is CancellationException) throw e
            SynthOutcome.Failed(
                detail = DETAIL_PLAYBACK_ERROR,
                message = "播放失敗：${Redactor.redactError(e.message ?: e.javaClass.simpleName)}",
            )
        } finally {
            activeJob.compareAndSet(currentJob, null)
            if (currentJob != null) {
                synchronized(generationLock) {
                    activeJobs.remove(currentJob)
                }
            }
        }
            }
        } catch (e: CancellationException) {
            // 統一取消契約（外層、呼叫端上下文）：結構化取消優先——
            // 只要呼叫端 parent Job 已取消，一律重拋 CancellationException；
            // 僅 parent 未取消（內部取代/stop/播放器內部取消）才回 Failed(CANCELLED)。
            val callerCancelled = try {
                currentCoroutineContext()[Job]?.isCancelled == true
            } catch (_: Throwable) {
                false
            }
            if (callerCancelled) throw e
            // 世代失格（被取代/stop）或播放器內部取消：正常回傳，不拋出。
            return SynthOutcome.Failed(
                detail = DETAIL_CANCELLED,
                message = "語音合成已被中斷",
            )
        }
    }

    /**
     * 立即中斷播放並取消所有進行中的合成請求。
     * 與 [synthesizeAndPlay] 的世代躍遷共用 [generationLock]，保證原子性。
     */
    fun stop() {
        val jobs: List<Job>
        val currentSpeechEpoch: Long
        synchronized(generationLock) {
            stopEpoch.incrementAndGet()
            currentSpeechEpoch = speechEpoch.incrementAndGet()
            val oldJob = activeJob.getAndSet(null)
            val snapshot = (activeJobs.toList() + listOfNotNull(oldJob)).distinct()
            activeJobs.clear()
            jobs = snapshot
        }
        jobs.forEach { it.cancel(CancellationException("Speech synthesis stopped (epoch $currentSpeechEpoch)")) }
        player.stop()
    }

    /**
     * 關閉引擎持有的播放器資源。
     * 若底層 [player] 為 [AndroidAudioPlayer]，轉調其 `release()` 關閉內部
     * release/timeout 背景池（兩池皆為 daemon，不關閉亦不阻塞 JVM 結束，但顯式關閉可避免測試累積閒置線程）。
     * 非 AndroidAudioPlayer 實作則僅執行 [stop]。
     */
    fun shutdown() {
        try {
            player.stop()
        } catch (_: Throwable) {
        }
        try {
            (player as? AndroidAudioPlayer)?.release()
        } catch (_: Throwable) {
        }
    }
}
