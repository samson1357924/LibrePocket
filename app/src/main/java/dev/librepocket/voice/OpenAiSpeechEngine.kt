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
 * 注意：內部持有 [data] 的防禦性副本（defensive copy clone），以防外部修改；此處會額外佔用一份 ByteArray 拷貝。
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
 * 內部對 [MediaPlayer] 採用無鎖釋放與世代安全架構，嚴格禁止在持有 Java 鎖期間呼叫 MediaPlayer 的 native 阻塞方法，
 * 徹底解除 AB-BA 死鎖，且 [stop] 能可靠停止所有進行中的播放。
 *
 * 採用 [MediaPlayer.prepareAsync] 非同步準備，在鎖外執行非同步準備與 native 呼叫，確保 [stop] 與新播放能隨時即時中斷。
 */
class AndroidAudioPlayer(
    private val mediaPlayerFactory: () -> MediaPlayer = { MediaPlayer() },
) : AudioPlayer {
    private var activePlayer: MediaPlayer? = null
    private var activeDataSource: ByteArrayMediaDataSource? = null
    private var activeContinuation: CancellableContinuation<Unit>? = null
    private val lock = Any()

    private data class DetachedSession(
        val player: MediaPlayer?,
        val dataSource: ByteArrayMediaDataSource?,
        val continuation: CancellableContinuation<Unit>?,
    )

    private fun detachIfActiveLocked(player: MediaPlayer): DetachedSession? {
        if (activePlayer === player) {
            val p = activePlayer
            val ds = activeDataSource
            val cont = activeContinuation
            activePlayer = null
            activeDataSource = null
            activeContinuation = null
            return DetachedSession(p, ds, cont)
        }
        return null
    }

    private fun safelyReleasePlayer(player: MediaPlayer?, dataSource: ByteArrayMediaDataSource?) {
        if (player != null) {
            synchronized(player) {
                try {
                    player.setOnPreparedListener(null)
                    player.setOnCompletionListener(null)
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
        }
        if (dataSource != null) {
            try {
                dataSource.close()
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun play(audioBytes: ByteArray): Unit = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine

        val player = try {
            mediaPlayerFactory()
        } catch (e: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
            return@suspendCancellableCoroutine
        }
        val dataSource = try {
            ByteArrayMediaDataSource(audioBytes, copy = false)
        } catch (e: Throwable) {
            safelyReleasePlayer(player, null)
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
            return@suspendCancellableCoroutine
        }

        val oldDetached = synchronized(lock) {
            if (!continuation.isActive) {
                return@synchronized null
            }
            // 確保前一個播放器在啟動新播放器前正確釋放
            val p = activePlayer
            val ds = activeDataSource
            val cont = activeContinuation
            activePlayer = player
            activeDataSource = dataSource
            activeContinuation = continuation
            DetachedSession(p, ds, cont)
        }

        if (oldDetached == null) {
            safelyReleasePlayer(player, dataSource)
            return@suspendCancellableCoroutine
        }

        safelyReleasePlayer(oldDetached.player, oldDetached.dataSource)
        if (oldDetached.continuation?.isActive == true) {
            oldDetached.continuation.cancel()
        }

        continuation.invokeOnCancellation {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player)
            }
            if (detached != null) {
                safelyReleasePlayer(detached.player, detached.dataSource)
            }
        }

        try {
            val proceeded = synchronized(player) {
                val shouldProceed = synchronized(lock) {
                    continuation.isActive && activePlayer === player
                }
                if (!shouldProceed) {
                    false
                } else {
                    player.setDataSource(dataSource)
                    player.setOnPreparedListener { mp ->
                        val shouldStart = synchronized(lock) {
                            activePlayer === player
                        }
                        if (!shouldStart) return@setOnPreparedListener

                        var startException: Exception? = null
                        try {
                            synchronized(player) {
                                val stillActive = synchronized(lock) {
                                    activePlayer === player
                                }
                                if (stillActive) {
                                    mp.start()
                                }
                            }
                        } catch (e: Exception) {
                            startException = e
                        }

                        if (startException != null) {
                            val detached = synchronized(lock) {
                                detachIfActiveLocked(player)
                            }
                            if (detached != null) {
                                safelyReleasePlayer(detached.player, detached.dataSource)
                                if (detached.continuation?.isActive == true) {
                                    detached.continuation.resumeWithException(startException)
                                }
                            }
                        }
                    }
                    player.setOnCompletionListener {
                        val detached = synchronized(lock) {
                            detachIfActiveLocked(player)
                        }
                        if (detached != null) {
                            safelyReleasePlayer(detached.player, detached.dataSource)
                            if (detached.continuation?.isActive == true) {
                                detached.continuation.resume(Unit)
                            }
                        }
                    }
                    player.setOnErrorListener { _, what, extra ->
                        val detached = synchronized(lock) {
                            detachIfActiveLocked(player)
                        }
                        if (detached != null) {
                            safelyReleasePlayer(detached.player, detached.dataSource)
                            if (detached.continuation?.isActive == true) {
                                detached.continuation.resumeWithException(
                                    IOException("MediaPlayer 播放錯誤：what=$what, extra=$extra"),
                                )
                            }
                        }
                        true
                    }
                    // 使用非同步 prepareAsync，不阻塞當前執行緒與鎖，讓 stop() 能隨時即時獲得鎖並中斷
                    player.prepareAsync()
                    true
                }
            }
            if (!proceeded) {
                return@suspendCancellableCoroutine
            }
        } catch (e: Exception) {
            val detached = synchronized(lock) {
                detachIfActiveLocked(player)
            }
            if (detached != null) {
                safelyReleasePlayer(detached.player, detached.dataSource)
            }
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }

    override fun stop() {
        val detached = synchronized(lock) {
            val p = activePlayer
            val ds = activeDataSource
            val cont = activeContinuation
            activePlayer = null
            activeDataSource = null
            activeContinuation = null
            DetachedSession(p, ds, cont)
        }
        safelyReleasePlayer(detached.player, detached.dataSource)
        if (detached.continuation?.isActive == true) {
            detached.continuation.cancel()
        }
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
    ): SynthOutcome = withContext(Dispatchers.IO) {
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

        // 前置檢查確認通過後，才遞增 speechEpoch 並取消舊工作
        val callEpoch = speechEpoch.incrementAndGet()
        val oldJob = activeJob.getAndSet(null)
        val oldJobs = synchronized(activeJobs) {
            val list = (activeJobs + listOfNotNull(oldJob)).distinct()
            activeJobs.clear()
            list
        }
        oldJobs.forEach { it.cancel(CancellationException("Superseded by speech request epoch $callEpoch")) }

        val currentJob = coroutineContext[Job]
        if (callEpoch != speechEpoch.get() || currentJob?.isActive == false) {
            return@withContext SynthOutcome.Failed(
                detail = DETAIL_CANCELLED,
                message = "語音合成已被中斷",
            )
        }
        activeJob.set(currentJob)
        if (currentJob != null) {
            synchronized(activeJobs) {
                if (callEpoch == speechEpoch.get() && currentJob.isActive) {
                    activeJobs.add(currentJob)
                }
            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenAiTtsException) {
            SynthOutcome.Failed(
                detail = e.errorCode.name,
                message = e.safeMessage,
            )
        } catch (e: Exception) {
            SynthOutcome.Failed(
                detail = DETAIL_PLAYBACK_ERROR,
                message = "播放失敗：${Redactor.redactError(e.message ?: e.javaClass.simpleName)}",
            )
        } finally {
            activeJob.compareAndSet(currentJob, null)
            if (currentJob != null) {
                synchronized(activeJobs) {
                    activeJobs.remove(currentJob)
                }
            }
        }
    }

    /**
     * 立即中斷播放並取消所有進行中的合成請求。
     */
    fun stop() {
        val currentStopEpoch = stopEpoch.incrementAndGet()
        val currentSpeechEpoch = speechEpoch.incrementAndGet()
        val oldJob = activeJob.getAndSet(null)
        val jobs = synchronized(activeJobs) {
            val list = (activeJobs + listOfNotNull(oldJob)).distinct()
            activeJobs.clear()
            list
        }
        jobs.forEach { it.cancel(CancellationException("Speech synthesis stopped (epoch $currentSpeechEpoch)")) }
        player.stop()
    }
}
