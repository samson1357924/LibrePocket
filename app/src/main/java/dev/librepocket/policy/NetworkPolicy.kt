package dev.librepocket.policy

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * S1 (Issue #135 A1) + S2 (A2 接線)：出站網路政策核心——cleartext／私有 IP 決策的單一真相。
 *
 * 純 JVM（無 Android API）。S2 已接線 provider transport（見
 * `ProviderTransport.requireNetworkPolicyAllowed`）：建連前先調
 * [evaluateUrl]，僅當 [NetworkEvaluation.allowed] 為 true 才可連線；
 * 拒絕即 typed fatal（不送 credential／body），redirect 0-follow
 *（[NetworkDenyCode.REDIRECT_BLOCKED] 由執行層擋下）。
 * 仍不碰 `AndroidManifest`／NSC（見 S2 殘留：NSC／DNS pinning 裝置部分待 A3/A4）。
 *
 * Fail-closed 貫穿全程：
 * - 私有判定一律以解析後位元組（[classifyAddress]）為準，永不做字串前綴比對
 *   （舊 `validateBaseUrl` 的 `host.startsWith("10.")` 會把 `10.evil.com`
 *   誤放行，此處結構上不可能重犯）；
 * - onion／非法 host／解析失敗一律拒絕，且 onion 在查 DNS 前就擋下（不洩漏）；
 * - 預設 [EndpointNetworkOptIn] 即 [NetworkEgressPolicy.HTTPS_ONLY]，
 *   私有與明文預設全拒（deny-all），opt-in 只能 per-endpoint 持有，
 *   沒有全域一鍵開放；
 * - `public http://` 永不開放（non-goal）；http 另要求全部已解析答案皆為 LOOPBACK／PRIVATE（逐 IP 檢查，混入 PUBLIC 整組拒，pin 只拿授權集合）；metadata／link-local／CGNAT／
 *   multicast／16-byte IPv4-mapped／IPv4 保留段（0/8、240/4、broadcast）／
 *   NAT64 Local-Use（`64:ff9b:1::/48`）／compat·6to4·Teredo·SIIT·NAT64-well-known 內嵌非公網 v4／userinfo 即使 opt-in 也永遠阻擋；
 *   字面 `::ffff:a.b.c.d` 經平台正規化後等價內嵌 v4，按 v4 決策（見測試）。
 */
object NetworkPolicy {

  internal sealed interface PreflightOutcome<out T> {
    data class Completed<out T>(val value: T) : PreflightOutcome<T>
    data object TimedOut : PreflightOutcome<Nothing>
    data object OverloadRejected : PreflightOutcome<Nothing>
  }

  private sealed interface PreDnsDecision {
    data class Denied(val eval: NetworkEvaluation) : PreDnsDecision
    data class Literal(val scheme: String, val normalizedUrl: String, val address: InetAddress) : PreDnsDecision
    data class NeedsDns(val scheme: String, val normalizedUrl: String, val host: String) : PreDnsDecision
  }

  private val preflightThreadCounter = AtomicInteger(1)

  /**
   * 共享有界 DNS preflight 執行器：零無界佇列（[SynchronousQueue] + [ThreadPoolExecutor.AbortPolicy]），
   * 最多擴展至 16 條 daemon 執行緒並於閒置 30 秒回收；當原生 `getaddrinfo()` 阻塞達上限時，
   * 以 [RejectedExecutionException] 確定性拒絕過載，防止 OOM 或無限排隊。
   */
  internal val defaultPreflightExecutor: ExecutorService = ThreadPoolExecutor(
    0,
    16,
    30L,
    TimeUnit.SECONDS,
    SynchronousQueue(),
    ThreadFactory { runnable ->
      Thread(runnable, "network-preflight-${preflightThreadCounter.getAndIncrement()}").apply {
        isDaemon = true
      }
    },
    ThreadPoolExecutor.AbortPolicy(),
  )

  private fun normalizeHost(host: String): String =
    host.trim().trimEnd('.').lowercase(Locale.ROOT)

  private fun hasValidHostChars(h: String): Boolean {
    if (h.isEmpty() || h.length > 253) return false
    if (h == "onion" || h.endsWith(".onion")) return false
    if ('@' in h || '/' in h || '\\' in h ||
      h.any { it.isWhitespace() || it < ' ' || it.isISOControl() }
    ) {
      return false
    }
    return true
  }

  private fun isValidPreDnsHost(h: String, literalIp: InetAddress? = parseLiteralIp(h)): Boolean {
    if (!hasValidHostChars(h)) return false
    if (literalIp != null) return true
    if (':' in h) return false
    if (IPV4_SHAPED.matches(h)) return false
    return isValidDnsName(h)
  }

  private fun evaluatePreDns(
    rawUrl: String,
    optIn: EndpointNetworkOptIn,
  ): PreDnsDecision {
    // 先於 trim() 與 scheme 預檢攔截任何控制字元（含首尾 C0 如 \u0000/\u0007/\t/\n/\r 與 \u007f/\u0085），
    // 嚴防 trim() 靜默剝除首尾控制字元或繞過 SCHEME_PREFIX。
    if (rawUrl.any { (it < ' ' && it != ' ') || it.isISOControl() }) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
    }
    // HttpUrl 只解析 http(s)：先顯式識別其餘 scheme（與 WebFetch.checkUrl
    // 姿態對稱），保留 UNSUPPORTED_SCHEME 細分碼。
    val schemePrefix = SCHEME_PREFIX.find(rawUrl)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    if (schemePrefix != null && schemePrefix != "http" && schemePrefix != "https") {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.UNSUPPORTED_SCHEME, null))
    }
    // 原始 authority／空白檢查（對齊 McpWire.requireSecureEndpoint 姿態，並先於 toHttpUrlOrNull）：
    // 第一個 `:` 之後必須緊接 `//`，嚴防單斜線／三斜線／反斜線 scheme 分隔符繞過；
    // 首尾僅剝除一般空白 ' '（控制字元已於上方拒絕）。
    val trimmed = rawUrl.trim { it == ' ' }
    val schemeEnd = trimmed.indexOf(':')
    if (schemeEnd <= 0 || !trimmed.startsWith("//", schemeEnd + 1)) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
    }
    val rawScheme = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
    if (rawScheme != "http" && rawScheme != "https") {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
    }
    val authStart = schemeEnd + 3
    var authEnd = trimmed.length
    for (i in authStart until trimmed.length) {
      val c = trimmed[i]
      if (c == '/' || c == '?' || c == '#') {
        authEnd = i
        break
      }
    }
    val authority = trimmed.substring(authStart, authEnd)
    if (authority.isEmpty() ||
      authority.any { it.isWhitespace() || it < ' ' || it.isISOControl() }
    ) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
    }
    // Userinfo／反斜線永遠阻擋（含空 userinfo `http://@host/`，先於 toHttpUrlOrNull）。
    if ('@' in authority || '\\' in authority) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.USERINFO_BLOCKED, null))
    }
    // 先於 OkHttp 嚴格驗證方括號 IPv6 字面（防禦 OkHttp decodeIpv6 對超長組數拋 ArrayIndexOutOfBoundsException）。
    if ('[' in authority || ']' in authority) {
      val closeIdx = authority.indexOf(']')
      if (!authority.startsWith('[') ||
        closeIdx <= 1 ||
        authority.indexOf('[', 1) >= 0 ||
        authority.indexOf(']', closeIdx + 1) >= 0 ||
        (closeIdx < authority.length - 1 && authority[closeIdx + 1] != ':')
      ) {
        return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
      }
      val literal = authority.substring(1, closeIdx)
      if (parseIpv6Bytes(literal) == null) {
        return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
      }
    }
    val url = trimmed.toHttpUrlOrNull()
      ?: return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
    if (url.username.isNotEmpty() || !url.password.isNullOrEmpty()) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.USERINFO_BLOCKED, null))
    }
    val host = normalizeHost(url.host)
    if (!hasValidHostChars(host)) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
    }
    val literalIp = parseLiteralIp(host)
    if (!isValidPreDnsHost(host, literalIp)) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null))
    }
    // 明文且 opt-in 完全不允許時，非字面 DNS 名不必查 DNS（避免隱私／延遲回歸）；
    // 字面 IP 原本即零 DNS，跳過此短路以確保 IPv4／IPv6 分類碼（如 METADATA_ADDRESS_BLOCKED）完全對稱。
    if (url.scheme == "http" && !optIn.allowsCleartext && literalIp == null) {
      return PreDnsDecision.Denied(NetworkEvaluation.denied(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, null))
    }
    val normalizedUrl = url.toString()
    return if (literalIp != null) {
      PreDnsDecision.Literal(url.scheme, normalizedUrl, literalIp)
    } else {
      PreDnsDecision.NeedsDns(url.scheme, normalizedUrl, host)
    }
  }

  /**
   * 當且僅當 [evaluateUrl] 需要呼叫 `resolver.resolve` 時回 true。
   * 不支援的 scheme、畸形 URL／authority、userinfo、非明文許可下的 `http://`、
   * 字面 IP、以及無效 DNS 名一律回 false（呼叫方可直接略過 preflight 執行器）。
   */
  internal fun requiresDnsLookup(
    rawUrl: String,
    optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
  ): Boolean = evaluatePreDns(rawUrl, optIn) is PreDnsDecision.NeedsDns

  /**
   * 有界阻塞 preflight：在 [executor] 上執行 [block]，受 [deadlineNs] 嚴格約束。
   * 排隊等待時間計入 deadline；worker 完成後清除中斷旗標防執行緒污染；
   * 執行器滿載回 [PreflightOutcome.OverloadRejected]；呼叫方中斷透傳為 [CancellationException]。
   */
  internal fun <T> runPreflightBlocking(
    deadlineNs: Long,
    executor: ExecutorService = defaultPreflightExecutor,
    nanoClock: () -> Long = System::nanoTime,
    block: () -> T,
  ): PreflightOutcome<T> {
    if (Thread.currentThread().isInterrupted) {
      throw CancellationException("preflight interrupted")
    }
    val remainingNs = deadlineNs - nanoClock()
    if (remainingNs <= 0L) return PreflightOutcome.TimedOut
    val interruptLock = Any()
    var cancelled = false
    var workerThread: Thread? = null
    val future = try {
      executor.submit(
        Callable<PreflightOutcome<T>> {
          val entered = synchronized(interruptLock) {
            if (cancelled) {
              false
            } else {
              workerThread = Thread.currentThread()
              true
            }
          }
          if (!entered) return@Callable PreflightOutcome.TimedOut
          try {
            if (deadlineNs - nanoClock() <= 0L) {
              PreflightOutcome.TimedOut
            } else {
              PreflightOutcome.Completed(block())
            }
          } finally {
            synchronized(interruptLock) {
              workerThread = null
              Thread.interrupted()
            }
          }
        },
      )
    } catch (_: RejectedExecutionException) {
      return PreflightOutcome.OverloadRejected
    }
    fun cancelWorkerAndFuture() {
      synchronized(interruptLock) {
        cancelled = true
        workerThread?.interrupt()
        workerThread = null
      }
      future.cancel(false)
    }
    return try {
      val waitNs = deadlineNs - nanoClock()
      if (waitNs <= 0L) {
        cancelWorkerAndFuture()
        PreflightOutcome.TimedOut
      } else {
        future.get(waitNs, TimeUnit.NANOSECONDS)
      }
    } catch (_: TimeoutException) {
      cancelWorkerAndFuture()
      PreflightOutcome.TimedOut
    } catch (e: InterruptedException) {
      cancelWorkerAndFuture()
      Thread.currentThread().interrupt()
      throw CancellationException("preflight interrupted", e)
    } catch (e: ExecutionException) {
      throw (e.cause ?: e)
    } catch (t: Throwable) {
      cancelWorkerAndFuture()
      throw t
    }
  }

  /**
   * 有界協程 preflight：使用 `suspendCancellableCoroutine` + `withTimeoutOrNull`，
   * 避免 `ExecutorCoroutineDispatcher` 在過載拒絕時誤取消呼叫方協程；
   * 逾時或父協程取消時以同步鎖中斷 worker 並 `future.cancel(false)` 馬上返回
   *（消除 `FutureTask.cancel(true)` 與 `Thread.interrupted()` 之間的執行緒中斷競爭）。
   */
  internal suspend fun <T> runPreflightSuspend(
    deadlineNs: Long,
    executor: ExecutorService = defaultPreflightExecutor,
    nanoClock: () -> Long = System::nanoTime,
    block: () -> T,
  ): PreflightOutcome<T> {
    coroutineContext.ensureActive()
    val initialRemainingNs = deadlineNs - nanoClock()
    if (initialRemainingNs <= 0L) return PreflightOutcome.TimedOut
    val remainingMs =
      (initialRemainingNs / 1_000_000L) + if (initialRemainingNs % 1_000_000L > 0L) 1L else 0L
    return withTimeoutOrNull(remainingMs) {
      suspendCancellableCoroutine<PreflightOutcome<T>> { cont ->
        val futureRef = AtomicReference<Future<*>?>(null)
        val interruptLock = Any()
        var cancelled = false
        var workerThread: Thread? = null
        val completed = AtomicBoolean(false)
        fun cancelWorkerAndFuture() {
          synchronized(interruptLock) {
            cancelled = true
            workerThread?.interrupt()
            workerThread = null
          }
          futureRef.get()?.cancel(false)
        }
        cont.invokeOnCancellation {
          cancelWorkerAndFuture()
        }
        val alreadyCancelled = synchronized(interruptLock) { cancelled } || cont.isCancelled
        if (alreadyCancelled) {
          cancelWorkerAndFuture()
          return@suspendCancellableCoroutine
        }
        val task = Runnable {
          val entered = synchronized(interruptLock) {
            if (cancelled || cont.isCancelled) {
              false
            } else {
              workerThread = Thread.currentThread()
              true
            }
          }
          if (!entered) {
            return@Runnable
          }
          try {
            val outcomeOrError = try {
              if (deadlineNs - nanoClock() <= 0L) {
                Result.success(PreflightOutcome.TimedOut)
              } else {
                Result.success(PreflightOutcome.Completed(block()))
              }
            } catch (t: Throwable) {
              Result.failure(t)
            }
            if (!cont.isActive || !completed.compareAndSet(false, true)) {
              return@Runnable
            }
            outcomeOrError.fold(
              onSuccess = { outcome -> cont.resume(outcome) },
              onFailure = { error -> cont.resumeWithException(error) },
            )
          } finally {
            synchronized(interruptLock) {
              workerThread = null
              Thread.interrupted()
            }
          }
        }
        try {
          val submitted = executor.submit(task)
          futureRef.set(submitted)
          val postSubmitCancelled = synchronized(interruptLock) { cancelled } || cont.isCancelled
          if (postSubmitCancelled) {
            cancelWorkerAndFuture()
          }
        } catch (_: RejectedExecutionException) {
          if (cont.isActive && completed.compareAndSet(false, true)) {
            cont.resume(PreflightOutcome.OverloadRejected)
          }
        }
      }
    } ?: PreflightOutcome.TimedOut
  }

  /**
   * 首跳 DNS 決策；呼叫方（S2 的 pinning `Dns`）必須拿
   * [NetworkEvaluation.resolvedAddresses] 把答案釘住建連，不得二次解析
   * （否則 TOCTOU rebind；rebind 即視為新決策重驗）。
   *
   * S2：[resolver] 無預設值，呼叫方必須顯式注入（production 經
   * `PinnedNetworkDns` 釘住後的 resolver，測試用 fake）；舊預設
   * `SystemHostResolver` 僅保留給 [resolveHost] 的純工具場景。
   */
  fun evaluateUrl(
    rawUrl: String,
    optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
    resolver: HostResolver,
  ): NetworkEvaluation {
    val scheme: String
    val normalizedUrl: String
    val resolved: List<InetAddress>
    when (val pre = evaluatePreDns(rawUrl, optIn)) {
      is PreDnsDecision.Denied -> return pre.eval
      is PreDnsDecision.Literal -> {
        scheme = pre.scheme
        normalizedUrl = pre.normalizedUrl
        resolved = listOf(pre.address)
      }
      is PreDnsDecision.NeedsDns -> {
        scheme = pre.scheme
        normalizedUrl = pre.normalizedUrl
        resolved = resolveValidatedDnsHost(pre.host, resolver)
          ?: return NetworkEvaluation.denied(NetworkDenyCode.INVALID_HOST, null)
      }
    }
    val classes = resolved.map(::classifyAddress).toSet()
    val merged = mergeClass(classes)
    if (merged == AddressClass.METADATA) {
      return NetworkEvaluation.denied(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, merged, resolved)
    }
    if (merged != AddressClass.PUBLIC && merged != AddressClass.LOOPBACK && merged != AddressClass.PRIVATE) {
      // LINK_LOCAL / CGNAT / MULTICAST / IPV4_MAPPED / UNSPECIFIED：opt-in 也打不開。
      return NetworkEvaluation.denied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, merged, resolved)
    }
    if (scheme == "http") {
      // 明文只對迴環／私有開放，且須最高 tier；public http 永不開放。
      if (merged == AddressClass.PUBLIC || !optIn.allowsCleartext) {
        return NetworkEvaluation.denied(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, merged, resolved)
      }
      // 混合 DNS（如 PUBLIC+LOOPBACK 經 merge 得 LOOPBACK）：http 要求全部答案皆為
      // LOOPBACK／PRIVATE，混入其餘整組拒（pin 只拿授權集合）。
      if (classes.any { it != AddressClass.LOOPBACK && it != AddressClass.PRIVATE }) {
        return NetworkEvaluation.denied(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, merged, resolved)
      }
    }
    if ((merged == AddressClass.LOOPBACK || merged == AddressClass.PRIVATE) && !optIn.allowsPrivateAddresses) {
      return NetworkEvaluation.denied(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, merged, resolved)
    }
    return NetworkEvaluation.allowed(merged, resolved, normalizedUrl)
  }

  /**
   * 解析 host 但不做政策決策；回傳 null 表示該 host 永不得撥號（onion、
   * 非法字面、非法 DNS 名、解析失敗）。Onion 在碰 [resolver] 前就回 null，
   * 保證測試可用必敗 resolver 驗證零 DNS 洩漏。
   */
  fun resolveHost(host: String, resolver: HostResolver = SystemHostResolver): List<InetAddress>? {
    val h = normalizeHost(host)
    if (!hasValidHostChars(h)) return null
    val literalIp = parseLiteralIp(h)
    if (!isValidPreDnsHost(h, literalIp)) return null
    if (literalIp != null) return listOf(literalIp)
    return resolveValidatedDnsHost(h, resolver)
  }

  private fun resolveValidatedDnsHost(h: String, resolver: HostResolver): List<InetAddress>? =
    try {
      val addrs = resolver.resolve(h)
      if (addrs.isEmpty()) null else addrs
    } catch (e: Exception) {
      // resolver 任何例外（含 RuntimeException／IO）一律 fail-closed；
      // 唯協程取消與中斷必須透傳，不可吞沒（否則結構化併發與 timeout 失效）。
      if (e is CancellationException) throw e
      if (hasInterruptedCause(e) || Thread.currentThread().isInterrupted) {
        throw CancellationException("resolver interrupted", e)
      }
      null
    }

  internal fun hasInterruptedCause(t: Throwable): Boolean {
    val seen = IdentityHashMap<Throwable, Boolean>()
    var cur: Throwable? = t
    while (cur != null && seen.put(cur, true) == null) {
      if (cur is InterruptedException) return true
      cur = cur.cause
    }
    return false
  }

  /**
   * 純字面解析，不觸碰網路。字面 IPv4 與 IPv6 皆採純 Kotlin 嚴格 RFC 4291/5952
   * 位元組解析，並以 [InetAddress.getByAddress] 建構實例（跨 JVM 與 Android `libcore`
   * 保證零 OS DNS / `getaddrinfo()` 呼叫）；前導零（如 `0177` 疑似八進位）、
   * 十六進位／超長段／畸形冒號等非正規寫法一律回 null，並在 DNS 前檢查攔下。
   */
  fun parseLiteralIp(host: String): InetAddress? {
    val v4Bytes = parseStrictIpv4Bytes(host)
    if (v4Bytes != null) return InetAddress.getByAddress(v4Bytes)
    return parseLiteralIpv6(host)
  }

  private fun parseLiteralIpv6(host: String): InetAddress? {
    val bytes = parseIpv6Bytes(host) ?: return null
    return InetAddress.getByAddress(bytes)
  }

  private fun parseStrictIpv4Bytes(s: String): ByteArray? {
    if ('.' !in s) return null
    val parts = s.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (i in 0..3) {
      val part = parts[i]
      if (part.isEmpty() || part.length > 3) return null
      if (part.length > 1 && part[0] == '0') return null
      var value = 0
      for (c in part) {
        if (c !in '0'..'9') return null
        value = value * 10 + (c - '0')
      }
      if (value !in 0..255) return null
      bytes[i] = value.toByte()
    }
    return bytes
  }

  private fun parseHexGroup(group: String): Int? {
    if (group.length !in 1..4) return null
    var value = 0
    for (c in group) {
      val digit = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> return null
      }
      value = (value shl 4) or digit
    }
    return value
  }

  private fun parseIpv6Bytes(host: String): ByteArray? {
    if (host.length > 45) return null
    if (host.length < 2 || ':' !in host) return null
    if ('[' in host || ']' in host) return null
    if (!IPV6_LITERAL_CHARS.matches(host)) return null
    if (":::" in host) return null

    val compressIdx = host.indexOf("::")
    if (compressIdx >= 0 && host.indexOf("::", compressIdx + 2) >= 0) return null

    val leftRaw: List<String>
    val rightRaw: List<String>
    if (compressIdx < 0) {
      if (host.startsWith(':') || host.endsWith(':')) return null
      leftRaw = host.split(':')
      rightRaw = emptyList()
    } else {
      val leftPart = host.substring(0, compressIdx)
      val rightPart = host.substring(compressIdx + 2)
      if (leftPart.isNotEmpty() && (leftPart.startsWith(':') || leftPart.endsWith(':'))) return null
      if (rightPart.isNotEmpty() && (rightPart.startsWith(':') || rightPart.endsWith(':'))) return null
      // 內嵌 IPv4 僅允許出現在末段；若 `::` 存在，leftPart 永不可含 `.`。
      if ('.' in leftPart) return null
      leftRaw = if (leftPart.isEmpty()) emptyList() else leftPart.split(':')
      rightRaw = if (rightPart.isEmpty()) emptyList() else rightPart.split(':')
    }

    val lastSegment = if (compressIdx < 0) leftRaw.lastOrNull() else rightRaw.lastOrNull()
    val v4Tail = if (lastSegment != null && '.' in lastSegment) {
      parseStrictIpv4Bytes(lastSegment) ?: return null
    } else {
      null
    }

    val leftHexStrings = if (compressIdx < 0 && v4Tail != null) leftRaw.dropLast(1) else leftRaw
    val rightHexStrings = if (compressIdx >= 0 && v4Tail != null) rightRaw.dropLast(1) else rightRaw

    val leftGroups = IntArray(leftHexStrings.size)
    for (i in leftHexStrings.indices) {
      leftGroups[i] = parseHexGroup(leftHexStrings[i]) ?: return null
    }
    val rightGroups = IntArray(rightHexStrings.size)
    for (i in rightHexStrings.indices) {
      rightGroups[i] = parseHexGroup(rightHexStrings[i]) ?: return null
    }

    val totalHexGroupsNeeded = if (v4Tail != null) 6 else 8
    val explicitHexCount = leftGroups.size + rightGroups.size
    val compressedGroups = if (compressIdx < 0) {
      if (explicitHexCount != totalHexGroupsNeeded) return null
      0
    } else {
      val missing = totalHexGroupsNeeded - explicitHexCount
      if (missing < 1) return null
      missing
    }

    val out = ByteArray(16)
    var pos = 0
    for (g in leftGroups) {
      out[pos++] = (g ushr 8).toByte()
      out[pos++] = (g and 0xff).toByte()
    }
    pos += compressedGroups * 2
    for (g in rightGroups) {
      out[pos++] = (g ushr 8).toByte()
      out[pos++] = (g and 0xff).toByte()
    }
    if (v4Tail != null) {
      v4Tail.copyInto(out, destinationOffset = pos)
      pos += 4
    }
    if (pos != 16) return null
    return out
  }

  private fun isValidDnsName(host: String): Boolean {
    val labels = host.split('.')
    if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return false
    // 攔截非正規 IPv4 形狀（單整數 `2130706433`、十六進位 `0x7f.0.0.1`、`0x7f000001`、
    // 省略段 `127.1`、`0177.1`）以及末段 TLD 全數字或 `0x`-hex，防堵 OS `inet_aton()` 解析。
    if (NON_CANONICAL_IPV4_SHAPED.matches(host)) return false
    val lastLabel = labels.last()
    if (ALL_DIGITS_LABEL.matches(lastLabel) || HEX_PREFIX_LABEL.matches(lastLabel)) return false
    return labels.all { label ->
      label.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' } &&
        !label.startsWith('-') && !label.endsWith('-')
    }
  }

  /** 最嚴格者勝出：METADATA > 永遠阻擋類 > 需 opt-in 類 > PUBLIC。
   * 永遠阻擋類（[ALWAYS_BLOCKED]）已含 [AddressClass.UNSPECIFIED]，
   * 故保留段（0/8、240/4、broadcast）與拆解失敗的內嵌 v4 一律 opt-in 也打不開。 */
  internal fun mergeClass(classes: Set<AddressClass>): AddressClass {
    if (AddressClass.METADATA in classes) return AddressClass.METADATA
    for (c in ALWAYS_BLOCKED) if (c in classes) return c
    if (AddressClass.LOOPBACK in classes) return AddressClass.LOOPBACK
    if (AddressClass.PRIVATE in classes) return AddressClass.PRIVATE
    return AddressClass.PUBLIC
  }

  /**
   * S2 redirect 執行面：provider／MCP 0-follow，真正的跳轉碼皆終端拒絕。
   * 僅做分類（[NetworkDenyCode.REDIRECT_BLOCKED] 保留碼），實際阻擋由 transport
   * 執行層拋出；[evaluateUrl] 只驗首跳，不跟隨跳轉。
   * 304 (Not Modified)／305 (Use Proxy)／306 均無 Location 跳轉語義，
   * 明確排除，避免把快取回應誤判為跳轉阻擋。
   */
  fun isRedirectStatus(code: Int): Boolean = code in 300..303 || code == 307 || code == 308

  private val ALWAYS_BLOCKED = setOf(
    AddressClass.LINK_LOCAL,
    AddressClass.CGNAT,
    AddressClass.MULTICAST,
    AddressClass.IPV4_MAPPED,
    AddressClass.UNSPECIFIED,
  )

  private val IPV4_SHAPED = Regex("""^\d+(\.\d+){3}$""")

  private val NON_CANONICAL_IPV4_SHAPED =
    Regex("""^([0-9]+|0x[0-9a-f]+)(\.([0-9]+|0x[0-9a-f]+)){0,3}$""")

  private val ALL_DIGITS_LABEL = Regex("""^\d+$""")

  private val HEX_PREFIX_LABEL = Regex("""^0x[0-9a-f]+$""")

  private val IPV6_LITERAL_CHARS = Regex("""^[0-9a-fA-F:.]+$""")

  private val SCHEME_PREFIX = Regex("""^\s*([A-Za-z][A-Za-z0-9+.-]*):""")

  /**
   * 純位元組分類，不觸碰網路。檢查順序具語義：metadata 先於 link-local
   * （169.254.169.254 ⊂ 169.254/16），IPv4-mapped 先於其內嵌 v4 的分類
   * （::ffff:127.0.0.1 不得被當成 LOOPBACK）；其餘 IPv4-embedded 前綴
   * （NAT64 well-known `64:ff9b::/96`、SIIT `::ffff:0:0:0/96`、
   * compat `::/96`、6to4 `2002::/16`、Teredo `2001::/32`）緊隨其後，取出內嵌 v4
   * 按 v4 規則重走（metadata 優先，拆解失敗即 UNSPECIFIED），封裝旁路無所遁形；
   * RFC 8215 NAT64 Local-Use `64:ff9b:1::/48`（Global=False）掃描 RFC 6052 全部偏移
   * （/48、/56、/64、/96）：任一候選為 metadata 回 METADATA，其餘一律 UNSPECIFIED（永擋）；
   * 保留／特殊用途段（IPv4: 0/8、192.0.0/24、192.88.99/24、198.18/15、240/4、broadcast；
   * IPv6: `100::/64`、`2001:2::/48`、`2001:10::/28`、`2001:20::/28`、`3fff::/20`）
   * 以位元組精確拒——保留 TEST-NET-1/2/3 與 `2001:db8::/32` 作為測試用合成公網位址。
   */
  fun classifyAddress(address: InetAddress): AddressClass {
    val b = address.address
    if (isMetadataEndpoint(b)) return AddressClass.METADATA
    if (isIpv4Mapped(b)) return AddressClass.IPV4_MAPPED
    if (isEmbeddedV4(b)) {
      // 內嵌 v4 拆解失敗 fail-closed：寧擋一個怪地址，不放行。
      val v4 = try {
        extractEmbeddedV4(b)
      } catch (_: Exception) {
        null
      }
      return if (v4 != null) classifyEmbeddedV4(v4) else AddressClass.UNSPECIFIED
    }
    if (isNat64LocalUse(b)) {
      return if (hasNat64LocalUseMetadata(b)) AddressClass.METADATA else AddressClass.UNSPECIFIED
    }
    if (isReservedV4(b) || isReservedV6(b)) return AddressClass.UNSPECIFIED
    if (address.isMulticastAddress) return AddressClass.MULTICAST
    if (address.isAnyLocalAddress) return AddressClass.UNSPECIFIED
    if (address.isLoopbackAddress) return AddressClass.LOOPBACK
    if (address.isLinkLocalAddress) return AddressClass.LINK_LOCAL
    if (isCgnat(b)) return AddressClass.CGNAT
    if (address.isSiteLocalAddress || isUla(b)) return AddressClass.PRIVATE
    return AddressClass.PUBLIC
  }
}

/**
 * 每跳 DNS 查詢的可注入 seam。Production 用 [SystemHostResolver]；
 * 測試注入 fake（見 `NetworkPolicyTest.fakeDns`）；S2 的 pinning `Dns`
 * 實作亦經此介面拿首跳答案。
 */
fun interface HostResolver {
  @Throws(UnknownHostException::class)
  fun resolve(host: String): List<InetAddress>
}

/** 系統預設解析（`InetAddress.getAllByName`，多答案全回由政策取最嚴格）。 */
object SystemHostResolver : HostResolver {
  override fun resolve(host: String): List<InetAddress> =
    InetAddress.getAllByName(host).toList()
}

/**
 * Per-endpoint 出站 tier（S1 先 data model，不接 UI、不寫入既有 config）。
 * 單調遞增：每個 tier 包含前者全部授權，S2 綁定 endpoint identity＋
 * configRevision，變更即重綁（沿用 S4-C 顯式重綁，不與 chat key 混用）。
 */
enum class NetworkEgressPolicy {
  /** 預設：公網 https-only；一切明文與非公網位址全拒（Play 姿態停在此 tier）。 */
  HTTPS_ONLY,
  /** 另允許解析到私有／迴環的 DNS 名走 https（foss／github LAN pilot）；明文仍拒。 */
  ALLOW_PRIVATE_DNS,
  /** 另允許迴環／RFC1918／ULA（字面或 DNS 解析到）走 http；公網 http 永不開放。 */
  ALLOW_CLEARTEXT_PRIVATE,
}

/**
 * Per-endpoint opt-in（S1 data model；S2 綁定 identity＋revision＋one-shot 確認）。
 * 預設建構即 deny-all 姿態。
 */
data class EndpointNetworkOptIn(
  val policy: NetworkEgressPolicy = NetworkEgressPolicy.HTTPS_ONLY,
  val configRevision: Long = 0L,
) {
  val allowsPrivateAddresses: Boolean
    get() = policy == NetworkEgressPolicy.ALLOW_PRIVATE_DNS ||
      policy == NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE
  val allowsCleartext: Boolean
    get() = policy == NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE
}

/** 位元組層位址分類（[classifyAddress] 的輸出，可單獨單測）。 */
enum class AddressClass {
  /** 127/8、`::1`——可 opt-in。 */
  LOOPBACK,
  /** RFC1918、ULA `fc00::/7`——可 opt-in。 */
  PRIVATE,
  /** 169.254/16、`fe80::/10`——永遠阻擋。 */
  LINK_LOCAL,
  /** CGNAT 100.64/10——永遠阻擋。 */
  CGNAT,
  /** 雲 metadata 端點（169.254.169.254 等）——永遠阻擋，獨立錯誤碼。 */
  METADATA,
  /** Multicast——永遠阻擋。 */
  MULTICAST,
  /** `::ffff/96` 16-byte IPv4-mapped——永遠阻擋；字面經正規化等價內嵌 v4 者按 v4 決策。 */
  IPV4_MAPPED,
  /** 0.0.0.0／`::`、IPv4／IPv6 特殊保留段、拆解失敗的內嵌 v4——永遠阻擋。 */
  UNSPECIFIED,
  /** 其餘可路由位址（含 TEST-NET-1/2/3 與 `2001:db8::/32`，供測試佔位）。 */
  PUBLIC,
}

/**
 * 拒絕碼（typed errors 的 S1 核心側；S2 映射為 provider fatal 不重試、
 * `CLEARTEXT_NOT_PERMITTED` fatal、`REDIRECT_BLOCKED` 執行層）。
 * `REDIRECT_BLOCKED` 屬分類保留：provider／MCP 0-redirect 與 WebFetch
 * 逐跳重驗的執行面在 S2/A4 落地。
 */
enum class NetworkDenyCode {
  CLEARTEXT_NOT_PERMITTED,
  PRIVATE_ADDRESS_BLOCKED,
  METADATA_ADDRESS_BLOCKED,
  REDIRECT_BLOCKED,
  USERINFO_BLOCKED,
  UNSUPPORTED_SCHEME,
  INVALID_HOST,
}

/** 一次 URL 決策。被拒時 [resolvedAddresses] 仍回傳已解析答案以供稽核。 */
data class NetworkEvaluation(
  val allowed: Boolean,
  val denyCode: NetworkDenyCode?,
  val addressClass: AddressClass?,
  val resolvedAddresses: List<InetAddress> = emptyList(),
  val normalizedUrl: String? = null,
) {
  override fun toString(): String =
    "NetworkEvaluation(allowed=$allowed, denyCode=$denyCode, addressClass=$addressClass, addressCount=${resolvedAddresses.size})"

  companion object {
    fun allowed(
      addressClass: AddressClass,
      resolvedAddresses: List<InetAddress>,
      normalizedUrl: String,
    ) = NetworkEvaluation(true, null, addressClass, resolvedAddresses, normalizedUrl)

    fun denied(
      denyCode: NetworkDenyCode,
      addressClass: AddressClass?,
      resolvedAddresses: List<InetAddress> = emptyList(),
    ) = NetworkEvaluation(false, denyCode, addressClass, resolvedAddresses, null)
  }
}

/** 雲 metadata 端點比對（位元組精確比對）。 */
private fun isMetadataEndpoint(b: ByteArray): Boolean {
  if (b.size == 4) {
    val x = b.map { it.toInt() and 0xff }
    return x == listOf(169, 254, 169, 254) ||
      x == listOf(169, 254, 169, 253) ||
      x == listOf(169, 254, 169, 123) ||
      x == listOf(100, 100, 100, 200)
  }
  if (b.size == 16) {
    // fd00:ec2::254（AWS IPv6 metadata）。
    val want = intArrayOf(
      0xfd, 0x00, 0x0e, 0xc2,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x02, 0x54,
    )
    if (b.size == want.size && want.indices.all { (b[it].toInt() and 0xff) == want[it] }) return true
  }
  return false
}

private fun isIpv4Mapped(b: ByteArray): Boolean =
  b.size == 16 && b.take(10).all { it == 0.toByte() } &&
    (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff

private fun isCgnat(b: ByteArray): Boolean =
  b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xff) in 64..127

/**
 * IPv4 保留／非全域路由段位元組精確拒：0.0.0.0/8、192.0.0.0/24（IETF 協議指派）、
 * 192.88.99.0/24（6to4 anycast）、198.18.0.0/15（Benchmarking／Android VPN fake-IP TUN）、
 * 240.0.0.0/4（含 255.255.255.255 broadcast）。
 * 保留 TEST-NET-1 (192.0.2/24)、TEST-NET-2 (198.51.100/24)、TEST-NET-3 (203.0.113/24)
 * 供測試合成公網使用。
 */
private fun isReservedV4(b: ByteArray): Boolean {
  if (b.size != 4) return false
  val b0 = b[0].toInt() and 0xff
  val b1 = b[1].toInt() and 0xff
  val b2 = b[2].toInt() and 0xff
  if (b0 == 0 || b0 >= 240) return true
  if (b0 == 192 && b1 == 0 && b2 == 0) return true
  if (b0 == 192 && b1 == 88 && b2 == 99) return true
  if (b0 == 198 && (b1 and 0xfe) == 18) return true
  return false
}

/**
 * IPv6 非全域路由特殊前綴：`100::/64`（Discard-Only）、`2001:2::/48`（BMWG）、
 * `2001:10::/28`（ORCHID）、`2001:20::/28`（ORCHIDv2）、`3fff::/20`（RFC 9637 文件段）。
 * 保留 `2001:db8::/32` 供測試合成公網使用。
 */
private fun isReservedV6(b: ByteArray): Boolean {
  if (b.size != 16) return false
  val b0 = b[0].toInt() and 0xff
  val b1 = b[1].toInt() and 0xff
  val b2 = b[2].toInt() and 0xff
  val b3 = b[3].toInt() and 0xff
  val b4 = b[4].toInt() and 0xff
  val b5 = b[5].toInt() and 0xff
  if (b0 == 0x01 && b1 == 0x00 && (2..7).all { b[it] == 0.toByte() }) return true
  if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x02 && b4 == 0x00 && b5 == 0x00) return true
  if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && ((b3 and 0xf0) == 0x10 || (b3 and 0xf0) == 0x20)) return true
  if (b0 == 0x3f && b1 == 0xff && (b2 and 0xf0) == 0x00) return true
  return false
}

/**
 * RFC 8215 NAT64 Local-Use `64:ff9b:1::/48`（Global=False）：永不解析為 PUBLIC／PRIVATE／LOOPBACK。
 */
private fun isNat64LocalUse(b: ByteArray): Boolean =
  b.size == 16 &&
    (b[0].toInt() and 0xff) == 0x00 &&
    (b[1].toInt() and 0xff) == 0x64 &&
    (b[2].toInt() and 0xff) == 0xff &&
    (b[3].toInt() and 0xff) == 0x9b &&
    b[4] == 0.toByte() &&
    (b[5].toInt() and 0xff) == 0x01

/**
 * 依 RFC 6052 檢查 `64:ff9b:1::/48` 下全部可內嵌 IPv4 偏移（/48、/56、/64 須 `b[8]==0`，及 /96）：
 * 任一候選為雲 metadata 端點即回 true，防止非 /96 偏移繞過 METADATA 分類。
 */
private fun hasNat64LocalUseMetadata(b: ByteArray): Boolean {
  if (b[8] == 0.toByte()) {
    if (isMetadataEndpoint(byteArrayOf(b[6], b[7], b[9], b[10]))) return true
    if (isMetadataEndpoint(byteArrayOf(b[7], b[9], b[10], b[11]))) return true
    if (isMetadataEndpoint(byteArrayOf(b[9], b[10], b[11], b[12]))) return true
  }
  return isMetadataEndpoint(byteArrayOf(b[12], b[13], b[14], b[15]))
}

/**
 * IPv4-embedded IPv6 前綴偵測：NAT64 `64:ff9b::/96`、
 * SIIT `::ffff:0:0:0/96`、compat `::/96`、6to4 `2002::/16`、Teredo `2001::/32`。
 * `::ffff/96` 不在此列（另判 IPV4_MAPPED 永擋，語義不變）；`::`／`::1` 排除於 compat 之外，
 * 維持既有 UNSPECIFIED／LOOPBACK 語義；`64:ff9b:1::/48` 由 [isNat64LocalUse] 獨立處置。
 */
private fun isEmbeddedV4(b: ByteArray): Boolean {
  if (b.size != 16) return false
  // NAT64 64:ff9b::/96（RFC 6052 well-known prefix，/96 後 64 位須為零，即位元組 4..11）。
  if ((b[0].toInt() and 0xff) == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
    (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b &&
    b.slice(4..11).all { it == 0.toByte() }
  ) return true
  // SIIT IPv4-translated ::ffff:0:0:0/96（bytes 0..7 == 0, bytes 8..9 == ff ff, bytes 10..11 == 0）。
  if (b.slice(0..7).all { it == 0.toByte() } &&
    (b[8].toInt() and 0xff) == 0xff && (b[9].toInt() and 0xff) == 0xff &&
    b[10] == 0.toByte() && b[11] == 0.toByte()
  ) return true
  // 6to4 2002::/16：位元組 2..5 即內嵌 v4。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x02) return true
  // Teredo 2001::/32（2001:0000::/32；2001:db8:: 等文件段位元組 2 非零，不誤判）。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x01 &&
    b[2] == 0.toByte() && b[3] == 0.toByte()
  ) return true
  // compat ::/96（已廢棄）：前 96 位全零但排除 :: 與 ::1。
  if (b.slice(0..11).all { it == 0.toByte() }) {
    val tail = b.slice(12..15)
    if (!tail.all { it == 0.toByte() } && tail != listOf(0.toByte(), 0.toByte(), 0.toByte(), 1.toByte())) return true
  }
  return false
}

/** 取出內嵌 v4（4 bytes）；前綴不符即拋，呼叫方 fail-closed 回 UNSPECIFIED。 */
private fun extractEmbeddedV4(b: ByteArray): ByteArray {
  require(b.size == 16) { "NOT_EMBEDDED_V4" }
  // NAT64 well-known：末 32 位即內嵌 v4（前 96 位須為 64:ff9b::/96，位元組 4..11 全零）。
  if ((b[0].toInt() and 0xff) == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
    (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b &&
    b.slice(4..11).all { it == 0.toByte() }
  ) return b.sliceArray(12..15)
  // SIIT IPv4-translated ::ffff:0:0:0/96：取末 32 位（bytes 12..15）。
  if (b.slice(0..7).all { it == 0.toByte() } &&
    (b[8].toInt() and 0xff) == 0xff && (b[9].toInt() and 0xff) == 0xff &&
    b[10] == 0.toByte() && b[11] == 0.toByte()
  ) return b.sliceArray(12..15)
  // 6to4：位元組 2..5。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x02) return b.sliceArray(2..5)
  // Teredo：末 32 位經 ones-complement 混淆，取反還原——否則攻擊者可以混淆後
  // 位元組繞過 metadata／私有檢查（如混淆後的 169.254.169.254 看似公網）。
  if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x01 &&
    b[2] == 0.toByte() && b[3] == 0.toByte()
  ) return ByteArray(4) { i -> (b[12 + i].toInt() xor 0xff).toByte() }
  // compat ::/96。
  if (b.slice(0..11).all { it == 0.toByte() }) {
    val tail = b.sliceArray(12..15)
    require(!tail.all { it == 0.toByte() }) { "NOT_EMBEDDED_V4" }
    require(!(tail[0] == 0.toByte() && tail[1] == 0.toByte() && tail[2] == 0.toByte() && tail[3] == 1.toByte())) {
      "NOT_EMBEDDED_V4"
    }
    return tail
  }
  throw IllegalArgumentException("NOT_EMBEDDED_V4")
}

/**
 * 內嵌 v4 按 v4 規則重走：metadata 優先，其次保留段→迴環→link-local→
 * CGNAT→multicast→私有→公網，與 [NetworkPolicy.classifyAddress] 的 v4 側一致。
 */
private fun classifyEmbeddedV4(v4: ByteArray): AddressClass {
  require(v4.size == 4) { "NOT_IPV4" }
  if (isMetadataEndpoint(v4)) return AddressClass.METADATA
  if (isReservedV4(v4)) return AddressClass.UNSPECIFIED
  val f0 = v4[0].toInt() and 0xff
  val f1 = v4[1].toInt() and 0xff
  if (f0 == 127) return AddressClass.LOOPBACK
  if (f0 == 169 && f1 == 254) return AddressClass.LINK_LOCAL
  if (isCgnat(v4)) return AddressClass.CGNAT
  if (f0 in 224..239) return AddressClass.MULTICAST
  if (f0 == 10 || (f0 == 172 && f1 in 16..31) || (f0 == 192 && f1 == 168)) return AddressClass.PRIVATE
  return AddressClass.PUBLIC
}

private fun isUla(b: ByteArray): Boolean =
  b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
