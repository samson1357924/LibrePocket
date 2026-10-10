package dev.librepocket.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Session-owned serialized transcript writer (Phase 1 conversation-ledger fix).
 *
 * Every transcript event of one controller session flows through a single
 * bounded [Channel] drained by one writer coroutine, so persistence order
 * always matches admission order regardless of delegate latency. The mutex /
 * transaction in `RoomSessionStore` only assigns `seq`; it cannot repair
 * upstream reordering, which is fixed here.
 *
 * Durable ack: [onTurnStarted], [onTurnSucceeded], [onTurnCancelled] and
 * [onTurnFailed] suspend until the writer has run the delegate. A delegate
 * failure (including a non-cancellation store error from a durable core
 * write) fails the ack explicitly via `completeExceptionally` — never a
 * false durable ack — and the waiter converts that into an INTERRUPTED mark
 * via [interruptedRunIds] before rethrowing. A writer teardown mid-entry
 * fails the ack the same way, then seals: the channel is closed, every
 * outstanding and orphan-buffered core ack is failed boundedly, and later
 * core writes fail fast instead of parking forever.
 *
 * Retry / tool /
 * usage / steer notices only suspend until admitted to the channel, so a slow
 * store back-pressures the turn through the bounded channel instead of
 * silently reordering it. Notices admitted after [shutdown] seals the channel
 * throw [ClosedSendChannelException] instead of silently succeeding; the
 * caller owns that seal race explicitly (tool/usage notices have no
 * drainQueued recovery path).
 *
 * Lifecycle: the writer runs in its own [SupervisorJob] scope, independent of
 * the turn host scope, so cancelling the network never discards an admitted
 * event. [shutdown] first lets in-flight hosts settle (bounded, so their
 * non-cancellable terminal records are admitted before the flush barrier),
 * then drains with a bounded timeout, then seals. Core runIds still unacked
 * at that point are exposed via [interruptedRunIds] — explicit INTERRUPTED,
 * never a silent drop. Phase 1 deliberately adds no Room schema change:
 * interruption stays a memory + interface-layer mark, and the cancel-partial
 * / failed-partial flag distinction stays a Phase 3 schema decision.
 */
class OrderedTranscriptSink(
  private val delegate: TranscriptSink,
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
  capacity: Int = DEFAULT_CAPACITY,
) : TranscriptSink {

  private data class Entry(
    val runId: String?,
    val block: suspend () -> Unit,
    val ack: CompletableDeferred<Unit>? = null,
  )

  private val writerScope = CoroutineScope(SupervisorJob() + dispatcher)
  private val channel = kotlinx.coroutines.channels.Channel<Entry>(capacity)
  private val pendingCore = ConcurrentHashMap.newKeySet<String>()
  /** Outstanding core acks (ack -> runId) for bounded seal/shutdown failure. */
  private val coreAcks = ConcurrentHashMap<CompletableDeferred<Unit>, String>()
  /** Set once the writer exits (teardown or drain): later cores fail fast. */
  private val writerDead = AtomicBoolean(false)
  private val interrupted = java.util.Collections.synchronizedList(mutableListOf<String>())
  private val shutdownGuard = AtomicBoolean(false)
  /**
   * Tail of the parked-overflow chain from [offerSteer]. Each parked send
   * waits for its predecessor's admission attempt, so admissions match offer
   * order no matter how the writer dispatcher schedules the parked bodies.
   * [flush] awaits the tail, so a barrier can never overtake steers offered
   * before the flush. Cleared when the chain drains (CAS keeps a newer tail).
   */
  private val overflowChain = AtomicReference<CompletableDeferred<Unit>?>(null)

  private val writer: Job = writerScope.launch {
    try {
      for (entry in channel) {
        var failure: Throwable? = null
        try {
          entry.block()
        } catch (e: CancellationException) {
          // Writer teardown (shutdown scope cancel): remember the failure so
          // the finally below fails the ack explicitly instead of reporting a
          // false durable ack, then propagate so the writer ends.
          failure = e
          throw e
        } catch (e: Exception) {
          // Durable core failure (e.g. store IOException): fail this entry's
          // ack explicitly; the writer itself stays alive for later entries.
          // Notice entries carry no ack, so this stays best-effort for them.
          failure = e
        } finally {
          entry.runId?.let { pendingCore.remove(it) }
          val ack = entry.ack
          if (ack != null) {
            coreAcks.remove(ack)
            if (!ack.isCompleted) {
              if (failure != null) {
                entry.runId?.let(::markInterrupted)
                ack.completeExceptionally(failure)
              } else {
                ack.complete(Unit)
              }
            } else if (failure != null) {
              entry.runId?.let(::markInterrupted)
            }
          }
        }
      }
    } finally {
      // Any exit (teardown, fatal, or post-seal drain) seals: no later core
      // may park unbounded behind a dead writer.
      sealWriter(CancellationException("transcript writer dead"))
    }
  }

  override suspend fun onTurnStarted(runId: String, text: String) =
    writeCore(runId) { delegate.onTurnStarted(runId, text) }

  override suspend fun onTurnSucceeded(runId: String, text: String) =
    writeCore(runId) { delegate.onTurnSucceeded(runId, text) }

  override suspend fun onTurnSucceeded(runId: String, text: String, parentRunId: String?, attemptIndex: Int?) =
    writeCore(runId) { delegate.onTurnSucceeded(runId, text, parentRunId, attemptIndex) }

  override suspend fun onTurnFailed(runId: String, error: String) =
    writeCore(runId) { delegate.onTurnFailed(runId, error) }

  override suspend fun onTurnFailed(runId: String, partialText: String, error: String) =
    writeCore(runId) { delegate.onTurnFailed(runId, partialText, error) }

  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
  ) = writeCore(runId) { delegate.onTurnFailed(runId, partialText, error, parentRunId, attemptIndex) }

  override suspend fun onTurnCancelled(runId: String, partialText: String) =
    writeCore(runId) { delegate.onTurnCancelled(runId, partialText) }

  override suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
  ) = writeCore(runId) { delegate.onTurnCancelled(runId, partialText, parentRunId, attemptIndex) }

  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) =
    writeOrdered { delegate.onTurnRetried(runId, attempt, maxAttempts, delayMs) }

  override suspend fun onSteerQueued(text: String) =
    writeOrdered { delegate.onSteerQueued(text) }

  override suspend fun onToolDone(
    runId: String,
    toolIndex: Int,
    id: String,
    name: String,
    argumentsJson: String,
  ) = writeOrdered { delegate.onToolDone(runId, toolIndex, id, name, argumentsJson) }

  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) =
    writeOrdered { delegate.onUsage(runId, inputTokens, outputTokens) }

  /** Core event: suspends until the single writer has run the delegate. */
  private suspend fun writeCore(runId: String, block: suspend () -> Unit) {
    // Fail fast behind a dead/sealed writer: never park unbounded with no
    // drainer. The mark keeps the interruption explicit.
    if (writerDead.get() || writer.isCompleted || channel.isClosedForSend) {
      markInterrupted(runId)
      throw CancellationException("transcript writer sealed")
    }
    val ack = CompletableDeferred<Unit>()
    // Writer-side failure (teardown mid-entry, seal, drain timeout, or a
    // durable store error for this entry) completes this ack exceptionally;
    // convert that into an explicit INTERRUPTED mark instead of a false
    // durable ack. A merely-abandoned wait (caller cancelled while the ack
    // is still pending) does NOT mark: the handler only fires when the ack
    // itself completes, and abandonment leaves it pending for the
    // independent writer to persist.
    ack.invokeOnCompletion { cause ->
      if (cause != null) markInterrupted(runId)
    }
    coreAcks[ack] = runId
    pendingCore.add(runId)
    try {
      channel.send(Entry(runId, block, ack))
    } catch (e: CancellationException) {
      // The caller was cancelled before admission: the entry never entered
      // the channel, so drop the pending mark and propagate.
      coreAcks.remove(ack)
      pendingCore.remove(runId)
      throw e
    } catch (e: ClosedSendChannelException) {
      // Sealed after shutdown/writer death: fail fast instead of reporting
      // success; the mark keeps the interruption explicit.
      coreAcks.remove(ack)
      markInterrupted(runId)
      if (!ack.isCompleted) ack.completeExceptionally(e)
      throw e
    }
    try {
      ack.await()
    } catch (e: CancellationException) {
      // The handler above already marked INTERRUPTED when the writer failed
      // the ack (teardown/seal: the row may never have persisted). Otherwise
      // only the wait was abandoned and the queued entry is still persisted
      // by the independent writer. Either way propagate.
      throw e
    } catch (e: Exception) {
      // Durable store failure for this entry: already marked INTERRUPTED via
      // the handler; propagate instead of reporting durable success.
      throw e
    } finally {
      coreAcks.remove(ack)
    }
  }

  /**
   * Ordered notice: suspends only until admitted to the session channel.
   *
   * Visible failure (never a silent success): after [shutdown] seals the
   * channel this rethrows [ClosedSendChannelException] so the caller owns the
   * seal race explicitly — tool/usage notices have no drainQueued recovery
   * path, and `close()` clears the controller FIFO, so there is nothing to
   * fall back to here.
   */
  private suspend fun writeOrdered(block: suspend () -> Unit) {
    // No catch: admission failure is the caller's explicit signal.
    // CancellationException propagates to the cancelled caller, and
    // ClosedSendChannelException (sealed after shutdown) propagates to the
    // caller instead of succeeding silently.
    channel.send(Entry(runId = null, block))
  }

  /**
   * Non-suspending steer enqueue for lock-held admission paths. Never drops
   * silently while open: a full channel parks the send on the writer scope,
   * chained behind the previous parked send so admissions match offer order
   * regardless of dispatcher scheduling (a mutex cannot do this: acquisition
   * order is not offer order, and parked bodies still lose to direct sends).
   *
   * Stage E: the fast path is only taken when no parked predecessor exists.
   * A later offer arriving while the overflow chain is non-empty joins the
   * same admission path (queued behind the tail) instead of trySend-ing past
   * it — otherwise a freed slot lets the newcomer overtake parked A,B
   * (A,B,D,C reorder). Callers hold the controller lock, so the
   * chain-check and the park below are mutually ordered with other offers;
   * the synchronous getAndSet is the linearization point either way.
   * A seal race after shutdown drops here with no recovery
   * path (`close()` clears the controller FIFO, so there is no drainQueued
   * fallback for it).
   */
  fun offerSteer(text: String) {
    val entry = Entry(runId = null, block = { delegate.onSteerQueued(text) })
    if (overflowChain.get() == null && channel.trySend(entry).isSuccess) return
    val gate = CompletableDeferred<Unit>()
    val prev = overflowChain.getAndSet(gate)
    writerScope.launch {
      try {
        // Admission order, not launch order: the predecessor's gate completes
        // once its send was attempted, so this send runs strictly after it.
        prev?.await()
        channel.send(entry)
      } catch (e: CancellationException) {
        throw e
      } catch (_: ClosedSendChannelException) {
        // Sealed after shutdown: no recovery path, drop explicitly.
      } finally {
        gate.complete(Unit)
        overflowChain.compareAndSet(gate, null)
      }
    }
  }

  /**
   * Suspends until every event admitted so far is persisted, bounded by
   * [timeoutMs]. Returns true when the barrier drained (or, after [shutdown]
   * seals the channel, when the writer finished pre-seal work in time);
   * false on timeout. Cancellation of the caller still propagates.
   *
   * Parked [offerSteer] sends offered before this call are awaited first via
   * the overflow chain: without that, this barrier could be admitted ahead
   * of their still-unscheduled sends and report a drain that missed them.
   * Stage E: after awaiting the snapshot tail, the tail is re-read — a send
   * parked while we awaited joins a newer gate that the first await did not
   * cover. The loop ends on a stable (same completed gate, whose send was
   * already attempted) or empty chain; a send offered after the barrier is
   * admitted cannot be waited on, by construction.
   */
  suspend fun flush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean {
    try {
      return withTimeoutOrNull(timeoutMs) {
        var tail = overflowChain.get()
        while (tail != null) {
          tail.await()
          val current = overflowChain.get()
          if (current == null || current === tail) break
          tail = current
        }
        val barrier = CompletableDeferred<Unit>()
        try {
          channel.send(Entry(runId = null, block = {}, barrier))
        } catch (e: CancellationException) {
          throw e
        } catch (_: ClosedSendChannelException) {
          // Sealed: instead of the barrier, wait for the writer to finish
          // what was admitted pre-seal (still bounded by the same timeout).
          writer.join()
          return@withTimeoutOrNull
        }
        barrier.await()
      } != null
    } catch (e: CancellationException) {
      throw e
    }
  }

  /**
   * Bounded teardown: settle in-flight hosts first (so their terminal records
   * land ahead of the flush barrier), drain what was admitted, then seal.
   * Core runIds still unacked afterwards are recorded via [interruptedRunIds].
   * Async: the synchronous part only launches this work, so `close()` stays
   * fast even when the store stalls. Returns the background drain job so
   * [TurnController.flushTranscript] can await the full settle+drain+seal.
   */
  fun shutdown(
    drainTimeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS,
    settle: suspend () -> Unit = {},
  ): Job? {
    if (!shutdownGuard.compareAndSet(false, true)) return null
    return writerScope.launch {
      withContext(NonCancellable) {
        try {
          withTimeoutOrNull(drainTimeoutMs) { settle() }
        } catch (_: Exception) {
          // Bounded: fall through to the drain attempt below. This also
          // catches CancellationException from a cancelled settle (the
          // NonCancellable context above makes that a settle bug, never an
          // outer-scope cancel): the drain below still bounds the teardown.
        }
        if (!flush(drainTimeoutMs)) {
          val cause = CancellationException("transcript drain timeout")
          for (runId in pendingCore.toList()) {
            markInterrupted(runId)
            failAcksFor(runId, cause)
          }
        }
        channel.close()
        withTimeoutOrNull(drainTimeoutMs) {
          try {
            writer.join()
          } catch (_: Exception) {
            // Best effort: the scope cancel below still bounds the teardown.
          }
        }
        writerScope.cancel()
      }
    }
  }

  /** Core runIds admitted but still awaiting the writer. */
  fun pendingRunIds(): List<String> = pendingCore.toList()

  /**
   * True once the writer is sealed/torn down: later core writes fail fast
   * (fail-fast `CancellationException` or [ClosedSendChannelException]) instead
   * of parking unbounded behind a dead writer. Mirrors the [writeCore]
   * fail-fast gate; callers use it to distinguish a seal race (already marked
   * INTERRUPTED) from a genuine write failure without matching message text.
   */
  fun isSealed(): Boolean = writerDead.get() || writer.isCompleted || channel.isClosedForSend

  /** Core runIds admitted but never acked (drain timeout / seal race). */
  fun interruptedRunIds(): List<String> = synchronized(interrupted) { interrupted.toList() }

  private fun markInterrupted(runId: String) {
    pendingCore.remove(runId)
    synchronized(interrupted) {
      if (!interrupted.contains(runId)) interrupted.add(runId)
    }
  }

  /** Fails every outstanding core ack for [runId] (drain-timeout orphans). */
  private fun failAcksFor(runId: String, cause: Throwable) {
    for ((ack, id) in coreAcks.entries.toList()) {
      if (id == runId && !ack.isCompleted) {
        coreAcks.remove(ack)
        ack.completeExceptionally(cause)
      }
    }
  }

  /**
   * Seals the writer exactly once: closes the channel (unblocking parked
   * senders with [ClosedSendChannelException]), fails every outstanding core
   * ack, and drains orphan-buffered entries admitted but never consumed.
   * Bounded and non-suspending; later core writes fail fast via [writerDead].
   */
  private fun sealWriter(cause: Throwable) {
    if (!writerDead.compareAndSet(false, true)) return
    try {
      channel.close()
    } catch (_: Exception) {
      // Already sealed: fall through to fail the outstanding acks below.
    }
    for ((ack, runId) in coreAcks.entries.toList()) {
      coreAcks.remove(ack)
      pendingCore.remove(runId)
      synchronized(interrupted) {
        if (!interrupted.contains(runId)) interrupted.add(runId)
      }
      try {
        if (!ack.isCompleted) ack.completeExceptionally(cause)
      } catch (_: Exception) {
        // Best effort: the waiter already observes the interrupt mark.
      }
    }
    while (true) {
      val entry = channel.tryReceive().getOrNull() ?: break
      entry.runId?.let { runId ->
        pendingCore.remove(runId)
        synchronized(interrupted) {
          if (!interrupted.contains(runId)) interrupted.add(runId)
        }
      }
      val ack = entry.ack
      if (ack != null) {
        coreAcks.remove(ack)
        try {
          if (!ack.isCompleted) ack.completeExceptionally(cause)
        } catch (_: Exception) {
          // Best effort: same as above.
        }
      }
    }
  }

  companion object {
    const val DEFAULT_CAPACITY = 128
    const val DEFAULT_DRAIN_TIMEOUT_MS = 5_000L
    const val DEFAULT_FLUSH_TIMEOUT_MS = 5_000L
  }
}
