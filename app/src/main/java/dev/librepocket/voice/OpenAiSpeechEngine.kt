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
 * 遵循「音檔不落地（純記憶體）」安全契約，直接從記憶體 ByteArray 播放，絕不寫入檔案系統。
 */
interface AudioPlayer {
    /**
     * 純記憶體播放位元組，掛起直到播放結束或發生例外。
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
 * Android [MediaDataSource] 純記憶體實作。
 * 直接自 [data] ByteArray 讀取串流，保證音訊位元組永不落地（零檔案系統 I/O）。
 */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val remaining = (data.size - position).toInt()
        val toCopy = minOf(size, remaining)
        System.arraycopy(data, position.toInt(), buffer, offset, toCopy)
        return toCopy
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {
        // 純記憶體來源，無外部資源需釋放
    }
}

/**
 * Android 生產環境預設播放器，利用 [MediaPlayer] 搭配 [ByteArrayMediaDataSource] 達成純記憶體播放。
 * 內部對 [MediaPlayer] 的存取全面加鎖，確保前一個播放器在啟動新播放器前正確 stop/release，
 * 且 [stop] 能可靠停止所有進行中的播放。
 *
 * 採用 [MediaPlayer.prepareAsync] 非同步準備，避免同步 `prepare()` 長時間持有鎖阻礙 [stop] 呼叫。
 */
class AndroidAudioPlayer(
    private val mediaPlayerFactory: () -> MediaPlayer = { MediaPlayer() },
) : AudioPlayer {
    private var activePlayer: MediaPlayer? = null
    private var activeDataSource: ByteArrayMediaDataSource? = null
    private var activeContinuation: CancellableContinuation<Unit>? = null
    private val lock = Any()

    private fun releasePlayerLocked(player: MediaPlayer?, dataSource: ByteArrayMediaDataSource?) {
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
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

    private fun releaseCurrentLocked(cancelContinuation: Boolean = true): CancellableContinuation<Unit>? {
        val cont = activeContinuation
        activeContinuation = null

        val p = activePlayer
        val ds = activeDataSource
        activePlayer = null
        activeDataSource = null

        releasePlayerLocked(p, ds)

        return if (cancelContinuation) cont else null
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
        val dataSource = ByteArrayMediaDataSource(audioBytes)

        val oldCont = synchronized(lock) {
            if (!continuation.isActive) {
                releasePlayerLocked(player, dataSource)
                return@suspendCancellableCoroutine
            }
            // 確保前一個播放器在啟動新播放器前正確 stop/release
            releaseCurrentLocked(cancelContinuation = true).also {
                activePlayer = player
                activeDataSource = dataSource
                activeContinuation = continuation
            }
        }
        if (oldCont?.isActive == true) {
            oldCont.cancel()
        }

        continuation.invokeOnCancellation {
            synchronized(lock) {
                if (activePlayer === player) {
                    releaseCurrentLocked(cancelContinuation = false)
                }
            }
        }

        synchronized(lock) {
            if (!continuation.isActive || activePlayer !== player) {
                return@suspendCancellableCoroutine
            }

            try {
                player.setDataSource(dataSource)
                player.setOnPreparedListener { mp ->
                    var startException: Exception? = null
                    var contToFail: CancellableContinuation<Unit>? = null
                    synchronized(lock) {
                        if (activePlayer === player) {
                            try {
                                mp.start()
                            } catch (e: Exception) {
                                startException = e
                                contToFail = activeContinuation
                                activeContinuation = null
                                activePlayer = null
                                activeDataSource = null
                                releasePlayerLocked(player, dataSource)
                            }
                        }
                    }
                    if (startException != null && contToFail?.isActive == true) {
                        contToFail.resumeWithException(startException)
                    }
                }
                player.setOnCompletionListener {
                    val cont: CancellableContinuation<Unit>?
                    synchronized(lock) {
                        if (activePlayer === player) {
                            cont = activeContinuation
                            activeContinuation = null
                            activePlayer = null
                            activeDataSource = null
                            releasePlayerLocked(player, dataSource)
                        } else {
                            cont = null
                        }
                    }
                    if (cont?.isActive == true) {
                        cont.resume(Unit)
                    }
                }
                player.setOnErrorListener { _, what, extra ->
                    val cont: CancellableContinuation<Unit>?
                    synchronized(lock) {
                        if (activePlayer === player) {
                            cont = activeContinuation
                            activeContinuation = null
                            activePlayer = null
                            activeDataSource = null
                            releasePlayerLocked(player, dataSource)
                        } else {
                            cont = null
                        }
                    }
                    if (cont?.isActive == true) {
                        cont.resumeWithException(
                            IOException("MediaPlayer 播放錯誤：what=$what, extra=$extra"),
                        )
                    }
                    true
                }
                // 使用非同步 prepareAsync，不阻塞當前執行緒與鎖，讓 stop() 能隨時即時獲得鎖並中斷
                player.prepareAsync()
            } catch (e: Exception) {
                synchronized(lock) {
                    if (activePlayer === player) {
                        releaseCurrentLocked(cancelContinuation = false)
                    }
                }
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    override fun stop() {
        val cont = synchronized(lock) {
            releaseCurrentLocked(cancelContinuation = true)
        }
        if (cont?.isActive == true) {
            cont.cancel()
        }
    }
}

/**
 * OpenAI 雲端語音引擎（主源集，支援全部 flavor）。
 *
 * 安全不變量與架構保證：
 * 1. 雙開關門禁：`voice_output` 與 `openai_tts` 皆開才可用（見 [OpenAiSpeechGate]）；
 * 2. 脫敏送雲：送往 OpenAI 之前一律透過 [VoiceTools.redactForCloud] 遮罩敏感資訊；
 * 3. 音檔不落地：遵守純記憶體契約，串流音訊透過 [AudioPlayer]（預設 [AndroidAudioPlayer] + [ByteArrayMediaDataSource]）
 *    直接從記憶體 byte array 播放，絕不寫入檔案系統（零 File/TempFile 引用）；
 * 4. 支援中斷與世代防護：透過 [speechEpoch] 世代機制與 [activeJobs] 追蹤，[stop] 遞增世代並取消 activeJob 與停止播放器，
 *    後續完成的合成或延遲回調若發現世代不符則立即拋棄，絕不送出播放；
 * 5. 金鑰彈性來源：除獨立的 `VoiceTools.OPENAI_KEY_REF`（`"openai_tts"`），亦支援可選 key supplier 或作用中 OpenAI endpoint；
 * 6. 測試友善：支援注入 [AudioPlayer] 與 [OpenAiTtsClient]，便於 JVM 單元測試。
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

    companion object {
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
         * 必須嚴格驗證 baseUrl 為官方端點，避免自訂或惡意端點金鑰被取用或誤送。
         */
        internal fun isOpenAiEndpoint(config: EndpointConfig): Boolean =
            isOpenAiOfficialEndpoint(config.baseUrl)

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
                    isOpenAiOfficialEndpoint(resolvedKey.endpointBaseUrl)
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
         * 1. 獨立的 `KEY_REF`（"openai_tts"）；
         * 2. 若傳入 `endpointStore`，自其作用中的 OpenAI 端點取得金鑰別名；
         * 3. 預設 OpenAI 端點別名 "preset:openai"。
         */
        internal suspend fun resolveBoundKey(
            vault: KeyVault,
            endpointStore: EndpointStore? = null,
        ): ResolvedKey? {
            if (tryHasKey(vault, KEY_REF)) return ResolvedKey.OfficialOpenAi(KEY_REF)
            if (endpointStore != null) {
                val config = runCatching { endpointStore.snapshotStrict() }.getOrNull()
                if (config != null && isOpenAiEndpoint(config)) {
                    val alias = SetupValidation.aliasFromRef(config.apiKeyRef)
                    if (tryHasKey(vault, alias)) return ResolvedKey.EndpointStoreBound(alias, config.baseUrl)
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
         * 2. 若傳入 `endpointStore`，自其作用中的 OpenAI 端點取得金鑰別名；
         * 3. 預設 OpenAI 端點別名 "preset:openai"。
         */
        suspend fun resolveKeyRef(
            vault: KeyVault,
            endpointStore: EndpointStore? = null,
        ): String? = resolveBoundKey(vault, endpointStore)?.keyRef

        private suspend fun tryHasKey(vault: KeyVault, providerId: String): Boolean =
            try {
                vault.hasKey(providerId)
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
     * 雲端語音合成並於純記憶體中播放音訊。
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
        val callEpoch = speechEpoch.incrementAndGet()
        // 新呼叫勝出獲取最新世代時，明確取消舊的所有活躍 Job，中斷前一輪進行中的 OkHttp Call 與播放
        val oldJob = activeJob.getAndSet(null)
        val oldJobs = synchronized(activeJobs) {
            val list = (activeJobs + listOfNotNull(oldJob)).distinct()
            activeJobs.clear()
            list
        }
        oldJobs.forEach { it.cancel(CancellationException("Superseded by speech request epoch $callEpoch")) }

        return withContext(Dispatchers.IO) {
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

                if (callEpoch != speechEpoch.get() || currentJob?.isActive == false) {
                    return@withContext SynthOutcome.Failed(
                        detail = DETAIL_CANCELLED,
                        message = "語音合成已被中斷",
                    )
                }

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
    }

    /**
     * 立即中斷播放並取消所有進行中的合成請求。
     */
    fun stop() {
        val stopEpoch = speechEpoch.incrementAndGet()
        val oldJob = activeJob.getAndSet(null)
        val jobs = synchronized(activeJobs) {
            val list = (activeJobs + listOfNotNull(oldJob)).distinct()
            activeJobs.clear()
            list
        }
        jobs.forEach { it.cancel(CancellationException("Speech synthesis stopped (epoch $stopEpoch)")) }
        player.stop()
    }
}
