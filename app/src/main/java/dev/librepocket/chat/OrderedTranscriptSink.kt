package dev.librepocket.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
 * Durable ack: [onTurnStarted], [onTurnSucceeded], [onTurnCancelled],
 * [onLogicalTurnCancelled] and [onTurnFailed] suspend until the writer has run the delegate. A delegate
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
   * Stage 3 (R4-5) unified admission sequencer shared by every admission
   * type (steer / core / notice / flush barrier).
   *
   * Each admission synchronously reserves one slot before its first suspend
   * point: a monotonic ticket ([nextTicket]) plus a chain link
   * ([admissionChain] tail swap) bundled in a single tiny synchronized
   * section. The section never suspends and never touches the controller
   * lock (lock order is always controller -> admissionLock, never reversed),
   * so [offerSteer] stays non-suspending and lock-held callers cannot
   * deadlock. Uncontended cost is one gate allocation plus two atomics;
   * contended parking reuses the existing writerScope hop only.
   *
   * Admission attempts run strictly in reservation order: each holder awaits
   * its predecessor's gate (completed once that predecessor *attempted* its
   * channel send, never on persistence), attempts its own send, then
   * completes its gate (waking exactly one successor) and CAS-clears the
   * tail when still current. Gates always complete normally with Unit — even
   * on cancellation, timeout, or seal — so an abandoned slot skips without
   * sticking its successor. Completion precedes the CAS clear so a newcomer
   * arriving between the two can never fast-path past an already-linked
   * successor.
   *
   * A bare Mutex cannot do this: acquisition order is not arrival order and
   * parked bodies still lose to direct sends. The synchronous link is the
   * single linearization point for all types.
   *
   * PG-W2 lock-order contract: the synchronized section below covers ONLY
   * `nextTicket.getAndIncrement()` + `admissionChain.getAndSet()` — two
   * lock-free atomics, no suspend, no channel send/trySend, no delegate call,
   * no controller-lock acquisition, no `interrupted`/hook invocation, and no
   * other monitor. `@GuardedBy("admissionLock")` therefore means exactly
   * those two fields during reservation; everything else (hooks, sends,
   * awaits, delegate, seal/barrier work) runs after the monitor is released.
   * The only controller → sink nesting is `TurnController.enqueueLocked`
   * (holding its lock) → `offerSteer` → `reserveAdmission`; the order
   * controller → admissionLock is never reversed, and `reserveAdmission`
   * never blocks on the controller, so lock-held callers cannot deadlock.
   */
  private val admissionLock = Any()
  private val nextTicket = AtomicLong(0)
  private val admissionChain = AtomicReference<CompletableDeferred<Unit>?>(null)

  private data class Admission(
    val ticket: Long,
    val prev: CompletableDeferred<Unit>?,
    val gate: CompletableDeferred<Unit>,
  )

  /**
   * Test-only hooks (internal, default null, no production effect).
   *
   * - [onAdmissionReservedForTest] runs synchronously right after the ticket
   *   + chain link are reserved, still before the first suspend point, so a
   *   test observing it knows a later arrival can no longer overtake.
   * - [beforeSendForTest] runs after the predecessor gate completes and
   *   before the channel send; tests suspend it on a gate to deterministically
   *   widen the await -> send gap that pre-fix newcomers exploited.
   * - [afterSendForTest] runs after the channel send returns and before the
   *   `sent` proof flag is set (core writes only). Deterministic proxy
   *   (stronger-than-production: guaranteed-admitted) for the send-races-cancel
   *   flag shape (`sent == false && sendAttempted == true`): production's true
   *   window is a CE racing a suspended channel send (send throws CE with the
   *   attempt already recorded — delivery ambiguous, entry may already be with
   *   the writer), while send-return → `sent.set` has no suspension point so an
   *   external cancel cannot land there. Tests park here to exercise the same
   *   flag shape with guaranteed admission; a cancel landing here must map to
   *   UNKNOWN, never NOT_ADMITTED.
   */
  internal var onAdmissionReservedForTest: ((Long) -> Unit)? = null
  internal var beforeSendForTest: (suspend (Long) -> Unit)? = null
  internal var afterSendForTest: (suspend (Long) -> Unit)? = null

  /**
   * Reserves one admission slot (ticket + chain link) in a single tiny
   * synchronized section.
   *
   * Lock-order contract (PG-W2, see [admissionLock]): MAY be called with the
   * controller lock held (the `enqueueLocked` → `offerSteer` path does so);
   * MUST NOT acquire the controller lock or any other monitor inside; MUST
   * NOT suspend (no await/send/delegate inside the section — only the two
   * atomics); test hooks ([onAdmissionReservedForTest]) and every send/await
   * run strictly after the monitor is released (guarded by
   * `check(!Thread.holdsLock(admissionLock))`). Global order is always
   * controller → admissionLock, never reversed.
   */
  private fun reserveAdmission(): Admission {
    val gate = CompletableDeferred<Unit>()
    val ticket: Long
    val prev: CompletableDeferred<Unit>?
    synchronized(admissionLock) {
      ticket = nextTicket.getAndIncrement()
      prev = admissionChain.getAndSet(gate)
    }
    try {
      checkNotHoldingAdmissionLock("onAdmissionReservedForTest")
      onAdmissionReservedForTest?.invoke(ticket)
    } catch (e: Throwable) {
      releaseAdmission(gate)
      throw e
    }
    return Admission(ticket, prev, gate)
  }

  private fun releaseAdmission(gate: CompletableDeferred<Unit>) {
    try {
      gate.complete(Unit)
    } catch (_: Exception) {
      // Idempotent best effort: a cancelled/completed gate still wakes.
    }
    admissionChain.compareAndSet(gate, null)
  }

  /**
   * PG-W2 sink-local guard: test hooks must never run while holding
   * [admissionLock] (a future move of a hook into the critical section would
   * risk suspend/channel/delegate under the monitor and lock-order drift).
   * `Thread.holdsLock` is exact at the synchronous hook call site — no
   * coroutine hop is involved there — so this `check` is fail-fast with zero
   * behaviour change while every hook stays outside the lock.
   */
  private fun checkNotHoldingAdmissionLock(hook: String) =
    check(!Thread.holdsLock(admissionLock)) { "PG-W2: $hook must not run under admissionLock" }

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


  /**
   * R5-1 send-proof admission: same as [onTurnStarted] but sets [sent] to
   * true immediately after the channel send succeeds (before the durable-ack
   * wait). Lets [TurnController] distinguish timeout-before-send
   * (NOT_ADMITTED, never entered the channel) from timeout-after-send
   * (UNKNOWN/PENDING_COMMIT, ghost may late-commit via the independent
   * writer). Channel semantics unchanged: still a single ordered
   * [channel.send] under the same admission ticket/chain.
   *
   * R8-6: [sent] == false is untrusted — a [CancellationException] racing a
   * suspended [channel.send] may fire after the entry was already handed to
   * the writer. [sendAttempted] is therefore set immediately *before* the
   * send call: only `!sent && !sendAttempted` proves the entry never entered
   * (NOT_ADMITTED); `!sent && sendAttempted` is fuzzy and must map to UNKNOWN
   * until the independent ack (or a non-delivery proof: seal / closed channel
   * / pre-send liveness throw) settles it.
   */
  suspend fun onTurnStartedTracked(
    runId: String,
    text: String,
    sent: AtomicBoolean,
    sendAttempted: AtomicBoolean = AtomicBoolean(false),
  ) = writeCoreTracked(runId, { delegate.onTurnStarted(runId, text) }, sent, sendAttempted)

  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) =
    writeCore(runId) { delegate.onTurnSucceeded(runId, text, hasReasoning) }

  override suspend fun onTurnSucceeded(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = writeCore(runId) { delegate.onTurnSucceeded(runId, text, parentRunId, attemptIndex, hasReasoning) }

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

  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
  ) = writeCore(runId) { delegate.onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal) }

  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) =
    writeCore(runId) { delegate.onTurnCancelled(runId, partialText, hasReasoning) }

  override suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = writeCore(runId) { delegate.onTurnCancelled(runId, partialText, parentRunId, attemptIndex, hasReasoning) }

  override suspend fun onLogicalTurnCancelled(
    logicalTurnId: String,
    attemptRunId: String,
    partialText: String,
    attemptIndex: Int?,
  ) = writeCore(logicalTurnId) {
    delegate.onLogicalTurnCancelled(logicalTurnId, attemptRunId, partialText, attemptIndex)
  }

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

  /**
   * Awaits the unified admission tail until stable (no newer reservation).
   * Used only by [flush] before it reserves its barrier slot: steers, cores,
   * or notices offered while a previous tail was awaited join a newer gate
   * that the first await did not cover, so the tail is re-read. Ends on a
   * stable (same completed gate, whose send was already attempted) or empty
   * chain. A reservation made after the barrier slot is taken is ordered
   * behind the barrier, by construction.
   */
  private suspend fun awaitAdmissionTail() {
    var tail = admissionChain.get()
    while (tail != null) {
      tail.await()
      val current = admissionChain.get()
      if (current == null || current === tail) break
      tail = current
    }
  }

  /** Core event: suspends until the single writer has run the delegate. */
  private suspend fun writeCore(runId: String, block: suspend () -> Unit) {
    writeCoreTracked(runId, block, AtomicBoolean(false))
  }

  /**
   * R5-1 tracked core write. Identical ordering/ack contract to [writeCore];
   * additionally flips [sent] once the entry is buffered in [channel].
   * R8-6: [sent] == false no longer proves the entry never entered —
   * [sendAttempted] is set immediately before the (possibly suspending)
   * channel send, so only `!sent && !sendAttempted` is proven NOT_ADMITTED;
   * a [CancellationException] after the send was invoked is fuzzy (the entry
   * may already have been handed to the writer) and keeps its ack + pending
   * mark for the writer/settle to decide, mapping to UNKNOWN upstream. Proven
   * non-delivery (sealed pre-check, [ClosedSendChannelException], pre-send
   * liveness throw — all before the send is invoked) keeps the existing
   * fail-closed semantics. No channel semantic change.
   * PG-W2: reservation follows the [reserveAdmission] lock-order contract.
   */
  private suspend fun writeCoreTracked(
    runId: String,
    block: suspend () -> Unit,
    sent: AtomicBoolean,
    sendAttempted: AtomicBoolean = AtomicBoolean(false),
  ) {
    // Fail fast behind a dead/sealed writer: never park unbounded with no
    // drainer. The mark keeps the interruption explicit.
    if (writerDead.get() || writer.isCompleted || channel.isClosedForSend) {
      markInterrupted(runId)
      throw CancellationException("transcript writer sealed")
    }
    // Stage 3 (R4-5): reserve the admission slot synchronously, before the
    // first suspend point and before registering pending marks, so a test
    // observing pendingRunIds knows no later arrival can overtake this one.
    // Every later admission links behind this gate.
    val reservation = reserveAdmission()
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
    // R8-6 send-attempt proof (sink-local; mirrored to the caller-owned
    // [sendAttempted] flag at the linearization point below).
    var sendInvoked = false
    try {
      // Admission order, not resumption order: every smaller ticket's send
      // was already attempted once its gate completes.
      reservation.prev?.await()
      checkNotHoldingAdmissionLock("beforeSendForTest:writeCoreTracked")
      beforeSendForTest?.invoke(reservation.ticket)
      // R5-2: channel.send below does not suspend when capacity is available,
      // so a close/cancel that landed while parked above would otherwise slip
      // an entry into the channel after its owner gave up (the independent
      // writer would then persist a ghost row). Revalidate liveness at the
      // linearization point: no suspension happens between this check and the
      // synchronous send, so a throw here provably means the entry never
      // entered, and anything arriving later cannot interleave before it.
      if (coroutineContext[Job]?.isCancelled == true) {
        throw CancellationException("admission waiter cancelled before send")
      }
      // R8-6: record the send attempt BEFORE the (possibly suspending)
      // channel.send. Delivery under cancel is ambiguous: a CE racing a
      // suspended send may fire after the entry was already handed to the
      // writer, with `sent` still false. Only a CE with `sendInvoked == false`
      // proves the entry never entered (NOT_ADMITTED); anything after this
      // point is fuzzy and maps to UNKNOWN upstream. No suspension happens
      // between the liveness check above and this flag + send pair, so a
      // throw above provably means the entry never entered.
      sendInvoked = true
      sendAttempted.set(true)
      channel.send(Entry(runId, block, ack))
      checkNotHoldingAdmissionLock("afterSendForTest:writeCoreTracked")
      afterSendForTest?.invoke(reservation.ticket)
      sent.set(true)
    } catch (e: CancellationException) {
      if (!sendInvoked) {
        // Proven: the send was never attempted — the entry never entered
        // the channel, so drop the pending mark and propagate. The gate is
        // still completed normally by the finally below, skipping the slot
        // without sticking the successor.
        coreAcks.remove(ack)
        pendingCore.remove(runId)
        throw e
      }
      // R8-6 fuzzy: the send was invoked, so this CE may have raced a
      // successful handoff to the writer. Keep the ack in coreAcks and the id
      // in pendingCore so the independent writer (late-commit claim) or a
      // later seal (INTERRUPTED convert) settles it; never mark here and
      // never report NOT_ADMITTED. The caller maps `sent == false &&
      // sendAttempted == true` to UNKNOWN via the flags above.
      throw e
    } catch (e: ClosedSendChannelException) {
      // Sealed after shutdown/writer death: fail fast instead of reporting
      // success; the mark keeps the interruption explicit.
      coreAcks.remove(ack)
      markInterrupted(runId)
      if (!ack.isCompleted) ack.completeExceptionally(e)
      throw e
    } catch (e: Exception) {
      // L1: test-seam hook failure (production hooks are null). Pre-send
      // (!sendInvoked) proves the entry never entered: drop the pending mark
      // and ack like the CE pre-send path. Post-send (send invoked) is fuzzy
      // like the CE race: retain both for the writer/settle, never mark here;
      // the caller maps `sent == false && sendAttempted == true` to UNKNOWN.
      if (!sendInvoked) {
        coreAcks.remove(ack)
        pendingCore.remove(runId)
      }
      throw e
    } finally {
      // Gate signals send *attempt*, not persistence: successors may attempt
      // their sends as soon as this entry is buffered, while this waiter
      // moves on to the durable ack below. Always completes normally.
      releaseAdmission(reservation.gate)
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
    // caller instead of succeeding silently. The finally still completes the
    // gate normally so an abandoned slot never sticks its successor.
    // Stage 3 (R4-5): same synchronous reservation as cores, so a waiting
    // notice holds its place ahead of later arrivals.
    val reservation = reserveAdmission()
    try {
      reservation.prev?.await()
      checkNotHoldingAdmissionLock("beforeSendForTest:writeOrdered")
      beforeSendForTest?.invoke(reservation.ticket)
      channel.send(Entry(runId = null, block))
    } finally {
      releaseAdmission(reservation.gate)
    }
  }

  /**
   * Non-suspending steer enqueue for lock-held admission paths. Never drops
   * silently while open: a full channel parks the send on the writer scope,
   * chained behind the previous admission (steer, core, notice, or barrier
   * reservation) so admissions match reservation order regardless of
   * dispatcher scheduling (a mutex cannot do this: acquisition order is not
   * offer order, and parked bodies still lose to direct sends).
   *
   * Stage E: the fast path is only taken when no parked predecessor exists.
   * A later offer arriving while the admission chain is non-empty joins the
   * same admission path (queued behind the tail) instead of trySend-ing past
   * it — otherwise a freed slot lets the newcomer overtake parked A,B
   * (A,B,D,C reorder).
   *
   * Stage 3 (R4-5): the reservation now covers every admission type, not just
   * steers. A core or notice that reserved before this offer is a
   * predecessor this offer must await; conversely this offer's synchronous
   * link stops a still-waiting core/notice predecessor's chain slot from
   * looking empty to trySend. The synchronous link (ticket + getAndSet
   * bundled) is the linearization point either way.
   *
   * A seal race after shutdown drops here with no recovery
   * path (`close()` clears the controller FIFO, so there is no drainQueued
   * fallback for it).
   * PG-W2: reservation follows the [reserveAdmission] lock-order contract
   * (MAY be called with the controller lock held via `enqueueLocked`).
   */
  fun offerSteer(text: String) {
    val entry = Entry(runId = null, block = { delegate.onSteerQueued(text) })
    // Synchronous, non-suspending reservation: the single linearization
    // point. Only test hooks throw here, and reserveAdmission already
    // released the slot on that path.
    val reservation = reserveAdmission()
    if (reservation.prev == null) {
      // No predecessor: try immediate admission without parking.
      if (channel.trySend(entry).isSuccess) {
        releaseAdmission(reservation.gate)
        return
      }
      // Buffer full: park. If already sealed, drop explicitly instead of
      // parking behind a dead writer.
      if (channel.isClosedForSend || writerDead.get() || !writerScope.isActive) {
        releaseAdmission(reservation.gate)
        return
      }
      val job = writerScope.launch {
        try {
          // No predecessor to await (prev == null by construction here).
          checkNotHoldingAdmissionLock("beforeSendForTest:offerSteerFast")
          beforeSendForTest?.invoke(reservation.ticket)
          channel.send(entry)
        } catch (e: CancellationException) {
          throw e
        } catch (_: ClosedSendChannelException) {
          // Sealed after shutdown: no recovery path, drop explicitly.
        } finally {
          releaseAdmission(reservation.gate)
        }
      }
      // Defensive: if the scope was cancelled under us and the body never
      // runs, its finally never fires. The completion handler releases the
      // idempotent gate so successors cannot stick.
      job.invokeOnCompletion {
        if (!reservation.gate.isCompleted) releaseAdmission(reservation.gate)
      }
      return
    }
    val job = writerScope.launch {
      try {
        // Admission order, not launch order: the predecessor's gate completes
        // once its send was attempted, so this send runs strictly after it.
        reservation.prev.await()
        checkNotHoldingAdmissionLock("beforeSendForTest:offerSteerQueued")
        beforeSendForTest?.invoke(reservation.ticket)
        channel.send(entry)
      } catch (e: CancellationException) {
        throw e
      } catch (_: ClosedSendChannelException) {
        // Sealed after shutdown: no recovery path, drop explicitly.
      } finally {
        releaseAdmission(reservation.gate)
      }
    }
    job.invokeOnCompletion {
      if (!reservation.gate.isCompleted) releaseAdmission(reservation.gate)
    }
  }

  /**
   * Suspends until every event admitted so far is persisted, bounded by
   * [timeoutMs]. Returns true when the barrier drained (or, after [shutdown]
   * seals the channel, when the writer finished pre-seal work in time);
   * false on timeout. Cancellation of the caller still propagates.
   *
   * Note on durability: [flush] returns true once the barrier drains through the
   * writer, even if individual core entries encountered store exceptions and were
   * marked interrupted. Callers requiring guaranteed durable persistence without
   * store failures should check [hasDurableFailures] or call [flushDurable].
   *
   * Admissions reserved before this call are awaited first via the unified
   * chain: without that, this barrier could be admitted ahead of their
   * still-unscheduled sends and report a drain that missed them. Stage E:
   * after awaiting the snapshot tail, the tail is re-read — a send reserved
   * while the tail was awaited joins a newer gate that the first await did
   * not cover. The loop ends on a stable (same completed gate, whose send
   * was already attempted) or empty chain.
   *
   * Stage 3 (R4-5): the barrier slot itself is reserved only after the tail
   * is stable, at barrier-admission time rather than at flush entry. An
   * entry-time ticket would linearize the barrier ahead of a concurrent
   * arrival (B) that the stable-tail wait is required to include; a late
   * ticket keeps the Stage E guarantee (every steer offered before the
   * barrier is admitted drains first) while still serializing later arrivals
   * behind the barrier. A timeout abandoning the reserved slot completes it
   * normally so successors are never stuck.
   * PG-W2: barrier reservation follows the [reserveAdmission] lock-order contract.
   */
  suspend fun flush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean {
    try {
      return withTimeoutOrNull(timeoutMs) {
        awaitAdmissionTail()
        val reservation = reserveAdmission()
        try {
          reservation.prev?.await()
          val barrier = CompletableDeferred<Unit>()
          checkNotHoldingAdmissionLock("beforeSendForTest:flush")
          beforeSendForTest?.invoke(reservation.ticket)
          try {
            channel.send(Entry(runId = null, block = {}, barrier))
          } catch (_: ClosedSendChannelException) {
            // Sealed: instead of the barrier, wait for the writer to finish
            // what was admitted pre-seal (still bounded by the same timeout).
            writer.join()
            return@withTimeoutOrNull
          }
          // Release before the drain: the send was attempted, so successors
          // may proceed while this caller waits. Idempotent with the finally
          // below, which covers timeout/cancel abandonment paths.
          releaseAdmission(reservation.gate)
          barrier.await()
        } finally {
          // Always completes normally, including on timeout abandonment, so
          // the chain never sticks.
          releaseAdmission(reservation.gate)
        }
      } != null
    } catch (e: CancellationException) {
      throw e
    }
  }

  /**
   * True if any admitted core event failed during persistence or was interrupted by teardown/timeout.
   */
  fun hasDurableFailures(): Boolean = synchronized(interrupted) { interrupted.isNotEmpty() }

  /**
   * Suspends until every event admitted so far is persisted and verifies that
   * no durable store failure occurred.
   *
   * Returns true only when [flush] drains within [timeoutMs] AND [hasDurableFailures] is false.
   */
  suspend fun flushDurable(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean =
    flush(timeoutMs) && !hasDurableFailures()

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

  internal fun markInterrupted(runId: String) {
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
   * R9-1 non-delivery proof resolver: flush barrier drained while [runId] is
   * still pending. Channel FIFO guarantees an admitted ghost's pending is
   * removed by the writer `finally` before the barrier drains, so
   * still-pending after a successful barrier proves the fuzzy entry never
   * entered (writer only commits Entries it received). Atomically releases the
   * dangling ack so the ghost can never late-commit, records the true
   * INTERRUPTED mark via the existing ack handler (+ explicit guarantee), and
   * returns true. Already-cleared (delivered + claimed, or already resolved)
   * returns false (idempotent); sealed returns false (seal wins, caller defers
   * to the seal convert path).
   */
  fun tryResolveUndelivered(runId: String): Boolean {
    if (isSealed()) return false
    if (!pendingCore.remove(runId)) return false
    for ((ack, id) in coreAcks.entries.toList()) {
      if (id == runId) {
        coreAcks.remove(ack)
        try {
          if (!ack.isCompleted) {
            ack.completeExceptionally(CancellationException("transcript undelivered ghost resolved"))
          }
        } catch (_: Exception) {
          // Best effort: the waiter already observes the interrupt mark.
        }
      }
    }
    // Explicit guarantee alongside the 409 handler above (idempotent): the
    // barrier proof makes this a true mark, never a false INTERRUPTED.
    markInterrupted(runId)
    return true
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
