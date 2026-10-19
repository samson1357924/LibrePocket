package dev.librepocket.chat

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.withTimeout
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
    /**
     * R18-2 per-entry outcome (null = pending, true = committed, false =
     * failed). Set by the writer finally alongside the ack completion, so a
     * waiter can observe this entry fate without consulting the aggregated
     * per-runId mix and without delicate ack introspection. Null in
     * production for non-terminal entries.
     */
    val outcome: AtomicReference<Boolean?>? = null,
    /**
     * Best-effort accounting entry: a cancellation-cooperative stall or
     * non-VM failure in [block] must not block subsequent durable core writes
     * or seal the writer. Such entries run under [BEST_EFFORT_TIMEOUT_MS]
     * (skipped on timeout) and their non-VM failures (including non-VM
     * Errors) are swallowed; CancellationException from real scope teardown
     * and VM-fatal/ThreadDeath still propagate. A store call that blocks
     * without a suspension point (e.g. a blocking transaction on the writer
     * thread) cannot be preempted by the timeout — that is the same shared
     * store limit as core entries, documented in the P1 spec, and is not
     * covered by this flag.
     */
    val bestEffort: Boolean = false,
  )

  private val writerScope = CoroutineScope(SupervisorJob() + dispatcher)
  private val channel = kotlinx.coroutines.channels.Channel<Entry>(capacity)
  private val pendingCore = ConcurrentHashMap.newKeySet<String>()
  /**
   * R17-1 P1 review: leaf monitor for the writer success-path commit pair
   * (commit count increment + pending clear) and the atomic snapshot below.
   * Nests [poisonLock] for the R23-1 late-commit clear / drain-timeout guard
   * pair (commit-then-clear vs check-then-mark under one order,
   * submitTruthLock → poisonLock, never reversed: no poisonLock section below
   * takes submitTruthLock), and no suspend/delegate/channel work runs inside
   * — increment + remove + two concurrent-map reads plus the poison clear
   * only — so no new lock-order edge exists.
   */
  private val submitTruthLock = Any()
  /**
   * D15-2 committed proof (sink-owned submit truth): successful core commits
   * per runId, incremented in the writer `finally` on the success path only
   * (before the ack completes). Counts — not a set — because a first attempt
   * reuses its logical id for both the user row and its assistant terminal:
   * user commit = 1, terminal commit = 2. Retry attempts mint fresh ids so
   * their terminal commit = 1. The controller combines this count with its
   * attempt-dimension terminal intent to tell SUBMITTING vs COMMITTED vs
   * FAILED without suspending. Append-only, bounded by the session's core
   * write count; never cleared (like the store itself).
   */
  private val commitCounts = ConcurrentHashMap<String, AtomicInteger>()
  /** Outstanding core acks (ack -> runId) for bounded seal/shutdown failure. */
  private val coreAcks = ConcurrentHashMap<CompletableDeferred<Unit>, String>()
  /**
   * R18-2 per-entry outcomes (ack -> outcome ref) for terminal receipts.
   * Populated in [writeCoreWithAck] alongside [coreAcks]; completed by the
   * writer finally (true on commit, false on failure/seal) and removed with
   * the ack. Lets the controller waiter observe one entry fate with a plain
   * atomic read.
   */
  private val coreOutcomes = ConcurrentHashMap<CompletableDeferred<Unit>, AtomicReference<Boolean?>>()
  /** Set once the writer exits (teardown or drain): later cores fail fast. */
  private val writerDead = AtomicBoolean(false)
  /**
   * Poison ordering (A1): every compound check-then-add on [interrupted] /
   * [resolvedUndelivered] holds exactly one outer monitor — [poisonLock] —
   * and never nests the two collection monitors. Inner
   * `synchronizedList`/`synchronizedSet` self-locks are always leaf
   * (poisonLock → self, never reversed, never both inners held at once:
   * sequential contains/add only), so `interruptedRunIds` (single snapshot),
   * [markInterrupted], [tryResolveUndelivered], and [sealWriter] share the
   * global order resolved→interrupted under one lock and cannot deadlock.
   */
  private val poisonLock = Any()
  private val interrupted = java.util.Collections.synchronizedList(mutableListOf<String>())
  /**
   * R10-3 proven-undelivered ghosts: runIds that proved never-delivered via
   * the flush-barrier non-delivery proof ([tryResolveUndelivered]) — the fuzzy
   * send was attempted but the entry never entered the channel, so no ledger
   * row was ever persisted. These are unadmitted/cancelled send attempts, NOT
   * persisted-admission write failures: they stay visible via
   * [interruptedRunIds] (true INTERRUPTED mark for the ghost id, convert +
   * same-opId retry stay retryable) but never count toward
   * [hasDurableFailures]/[flushDurable]. A later successful retry of the same
   * op (fresh id) drains cleanly and [flushDurable] returns true again, while
   * genuine store failures (any non-cancellation Exception from the
   * delegate, e.g. store IOException, plus teardown mid-entry and seal)
   * stay in [interrupted] and keep poisoning durability as before. Seal checks
   * [resolvedUndelivered] under [poisonLock] before poisoning and skips an
   * already-resolved ghost (never dual-poison); the seal/resolve winner is
   * decided atomically by that same lock (see [tryResolveUndelivered]).
   * R11-2: proven never-attempted admissions ([markNeverAdmitted]: the send
   * was never invoked, so non-delivery is provable without a barrier) share
   * this set and semantics — visible but never a durable failure.
   */
  private val resolvedUndelivered = java.util.Collections.synchronizedSet(mutableSetOf<String>())
  /**
   * R23-1 genuine store-failure poison (guarded by [poisonLock], same compound
   * discipline as [interrupted]): runIds whose writer block threw (any
   * non-cancellation Exception from the delegate, e.g. store IOException,
   * plus teardown/fatal mid-entry) as opposed to drain-timeout /
   * seal-race / fail-fast marks (an admitted entry the writer never got to
   * run before the bounded teardown gave up on waiting). A late commit clears
   * a timeout mark (the row IS durable — see the success-path
   * [clearTimeoutMarkOnCommit] clear below) but
   * must never clear a genuine failure: with the first-attempt id shared by
   * the user row and its terminal, a failed user row plus a later committed
   * terminal would otherwise read clean while the family still dangles.
   * Append-only, bounded by the session's failed core count; never cleared.
   */
  private val storeFailed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
   * on cancellation, timeout, or seal — so a pre-send abandoned slot stays
   * parked via a writerScope forwarder awaiting its predecessor instead of
   * skipping past a still-waiting admission; the CAS clear rides with the
   * forwarder so a newcomer arriving after a tail cancel links behind the
   * abandoned gate. Completion precedes the CAS clear so a newcomer
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
   * - [afterSendForTest] runs after the channel send returns — before the
   *   `sent` proof flag on core writes (with a flag); the flush barrier also
   *   fires it (that path has no flag). Deterministic proxy
   *   (stronger-than-production: guaranteed-admitted) for the send-races-cancel
   *   flag shape (`sent == false && sendAttempted == true`): production's true
   *   window is a CE racing a suspended channel send (send throws CE with the
   *   attempt already recorded — delivery ambiguous, entry may already be with
   *   the writer), while send-return → `sent.set` has no suspension point so an
   *   external cancel cannot land there. Tests park here to exercise the same
   *   flag shape with guaranteed admission; a cancel landing here must map to
   *   UNKNOWN, never NOT_ADMITTED.
   */
  @VisibleForTesting
  internal var onAdmissionReservedForTest: ((Long) -> Unit)? = null
  @VisibleForTesting
  internal var beforeSendForTest: (suspend (Long) -> Unit)? = null
  @VisibleForTesting
  internal var afterSendForTest: (suspend (Long) -> Unit)? = null
  /**
   * D15-1 gap-coverage seams (G3): deterministic await-entrance signals
   * (internal, default null, no production effect).
   *
   * - [onPrevAwaitEnteredForTest] runs synchronously right before the
   *   predecessor-gate await (with the admission ticket), still before the
   *   first suspend point of that await, so a test observing it knows the
   *   holder has reserved and reached its park point — no delay inference.
   *   Must not suspend (signals via thread-safe Deferred/collections only).
   *   A throw is treated like any pre-send hook failure (abandon via the
   *   forwarder in the surrounding finally); tests never throw here.
   * - [onFlushTailAwaitEnteredForTest] runs once at [flush] tail-await entry
   *   (before the barrier ticket is reserved, so no ticket exists yet). Lets
   *   G2 deterministically observe a flush parked in [awaitAdmissionTail]
   *   before its timeout/cancel. Must not throw/suspend.
   */
  @VisibleForTesting
  internal var onPrevAwaitEnteredForTest: ((Long) -> Unit)? = null
  @VisibleForTesting
  internal var onFlushTailAwaitEnteredForTest: (() -> Unit)? = null
  /**
   * D15-2 test seam: runs in the writer `finally` after the commit count is
   * recorded and before the ack completes (store-write success only,
   * non-suspending, null in production). Lets the terminal-ack cancel grid
   * deterministically widen the commit→ack window: the hook signals commit,
   * holds the writer until the test cancels the host, then releases so the
   * ack drains. Must not throw (a throw is swallowed so an already-durable
   * commit never fails its ack on hook error).
   */
  @VisibleForTesting
  internal var afterCommitBeforeAckForTest: ((String) -> Unit)? = null

  /**
   * Sink-seal-race test seam (flush barrier only, null in production).
   *
   * Serial flush barrier after firing: runs strictly after the barrier entry
   * is buffered in [channel] (send returned) and its admission gate was
   * released, but before the barrier await — the deterministic proof point
   * that "the barrier is buffered behind whatever is currently in flight and
   * the admission chain is clear". A test observing this hook signal knows
   * any later writer death will fail THIS barrier via the [sealWriter]
   * orphan drain (the exact shape the seal-race mapping in [flush] handles);
   * the hook may suspend to park the flush there while the test arms the
   * teardown. Must not run under [admissionLock] (checked like the other
   * hooks); a hook throw propagates like any post-send caller fault.
   */
  @VisibleForTesting
  internal var afterBarrierSendForTest: (suspend () -> Unit)? = null

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
   *
   * D15-1: the hook runs after the link is taken. A hook throw abandons the
   * slot pre-send (never awaited its predecessor), so the gate MUST go
   * through [releaseAbandoned] (deferred forwarding), never an immediate
   * complete — otherwise the successor is woken past a still-waiting
   * predecessor and a tail clear lets a newcomer fast-path past everyone.
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
      releaseAbandoned(Admission(ticket, prev, gate))
      throw e
    }
    return Admission(ticket, prev, gate)
  }

  private fun releaseAdmission(gate: CompletableDeferred<Unit>) {
    // complete() is non-throwing and idempotent (returns false if already
    // completed or cancelled): a cancelled/completed gate still wakes as a
    // no-op, so no defensive try/catch is needed here.
    gate.complete(Unit)
    admissionChain.compareAndSet(gate, null)
  }

  /**
   * D15-1 deferred forwarding gate for every pre-send (!sendInvoked) abandon
   * path (pre-send CE, liveness throw, hook Exception with the send never
   * invoked, await CE in writeOrdered/offerSteer/flush, tail cancel).
   *
   * The caller rethrows immediately; the gate completion is delegated to a
   * forwarder living in [writerScope] (decoupled from the caller, never a
   * NonCancellable infinite await around the abandon path):
   * - `prev == null` or already completed: no predecessor to preserve, so
   *   complete immediately (identical to [releaseAdmission]).
   * - otherwise `writerScope.launch { prev.await(); complete(gate) }`: the
   *   successor stays parked behind the predecessor via this gate, exactly as
   *   if the abandoned slot had waited. The gate always completes normally
   *   with Unit (never exceptionally, so no successor mistakes it for its own
   *   cancellation), and the chain CAS clear rides with the forwarder — the
   *   tail is never nulled while a predecessor is still pending, so a
   *   newcomer arriving after a tail cancel links behind the abandoned gate
   *   instead of fast-pathing past everyone.
   * - `invokeOnCompletion` seal fallback: if the scope is cancelled under the
   *   forwarder (body never runs or await torn down), complete immediately so
   *   the chain never sticks; successors then hit the seal/ClosedSend path.
   *
   * Post-send (`sendInvoked == true`) paths keep the immediate
   * [releaseAdmission]: the predecessor was already awaited, so the successor
   * transitively waited and waking it is order-safe.
   */
  private fun releaseAbandoned(reservation: Admission) {
    val prev = reservation.prev
    val gate = reservation.gate
    if (prev == null || prev.isCompleted) {
      releaseAdmission(gate)
      return
    }
    try {
      val fwd = writerScope.launch {
        try {
          try {
            prev.await()
          } catch (_: Throwable) {
            // Predecessor abnormal (forwarder torn down with the scope):
            // still forward normally so the successor never sticks; the
            // seal/ClosedSend path below settles delivery.
          }
        } finally {
          releaseAdmission(gate)
        }
      }
      fwd.invokeOnCompletion {
        if (!gate.isCompleted) releaseAdmission(gate)
      }
    } catch (_: Throwable) {
      if (!gate.isCompleted) releaseAdmission(gate)
    }
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
    var lastFailure: Throwable? = null
    try {
      for (entry in channel) {
        var failure: Throwable? = null
        try {
          if (entry.bestEffort) {
            withTimeout(BEST_EFFORT_TIMEOUT_MS) { entry.block() }
          } else {
            entry.block()
          }
        } catch (e: CancellationException) {
          if (entry.bestEffort && writerScope.isActive) {
            // Best-effort accounting: swallow cancellation (including the
            // bounded-timeout cancellation) so a stalled usage write can never
            // seal the writer or block subsequent durable core writes. Scope
            // cancellation (shutdown) still propagates below.
            failure = null
          } else {
            // Writer teardown (scope cancel) OR a CE thrown by the delegate
            // block itself (e.g. an inner withTimeout): remember the failure
            // so the finally below fails the ack explicitly instead of
            // reporting a false durable ack, then propagate so the writer ends.
            failure = e
            throw e
          }
        } catch (e: Exception) {
          // Durable core failure (e.g. store IOException): fail this entry's
          // ack explicitly; the writer itself stays alive for later entries.
          // Notice entries carry no ack, so this stays best-effort for them.
          failure = e
          if (entry.bestEffort) {
            // Accounting failures are best-effort: do not fail/seal.
            failure = null
          }
        } catch (e: Throwable) {
          // Fatal Error (e.g. store AssertionError/LinkageError): fail this
          // entry's ack explicitly instead of reporting a false durable ack,
          // then propagate so the writer seals like teardown. The Error also
          // LEAKS to the submitting caller on purpose: the failed ack delivers
          // it verbatim to whoever is awaiting this entry (type and payload
          // pinned by OrderedTranscriptSinkErrorTest). Never swallow, and
          // never continue draining behind a fatal: after the seal the later
          // cores fail fast instead of pretending durability survived an
          // invariant break.
          failure = e
          if (entry.bestEffort && !(e is VirtualMachineError || e is ThreadDeath)) {
            // Non-VM fatal in an accounting entry must not seal the writer
            // or block later core writes; swallow it (best-effort).
            failure = null
          } else {
            throw e
          }
        } finally {
          // Only propagating failures (cancellation / VM-fatal) should become
          // the seal cause for later outstanding acks; routine durable store
          // failures keep the writer alive and must not poison the seal cause.
          if (failure != null && (failure is CancellationException || failure !is Exception)) {
            lastFailure = failure
          }
          val ack = entry.ack
          if (ack != null) {
            coreAcks.remove(ack)
            val outcome = entry.outcome ?: coreOutcomes.remove(ack)
            if (!ack.isCompleted) {
              if (failure != null) {
                entry.runId?.let(::markStoreFailure)
                outcome?.compareAndSet(null, false)
                coreOutcomes.remove(ack)
                ack.completeExceptionally(failure)
              } else {
                // D15-2: record the commit BEFORE clearing pending, so a
                // host cancelled in the commit→ack window still observes
                // COMMITTED via [coreCommitCount]. The intermediate state
                // (pending + committed) suppresses via either signal, safe.
                // Runs before the test hook below so the hook widens the
                // true post-commit window. The pair is committed under
                // [submitTruthLock] so [coreSubmitTruth] never observes the
                // torn (old count + cleared pending) mix (see its KDoc).
                // R23-1: the same section clears a drain-timeout mark for a
                // genuinely committed row (never a store failure — see
                // [clearTimeoutMarkOnCommit]), so a mark-then-commit race
                // converges clean instead of poisoning durability forever.
                entry.runId?.let { id ->
                  synchronized(submitTruthLock) {
                    commitCounts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                    pendingCore.remove(id)
                    clearTimeoutMarkOnCommit(id)
                  }
                }
                // D15-2 test seam: widens the commit→ack window for the
                // cancel-race grid (null in production). Must not throw;
                // a throw here would fail an already-durable ack.
                val commitHook = afterCommitBeforeAckForTest
                if (commitHook != null && failure == null && entry.runId != null) {
                  try {
                    commitHook(entry.runId)
                  } catch (_: Throwable) {
                    // Test-only: never fail a durable commit on hook error.
                  }
                }
                outcome?.compareAndSet(null, true)
                coreOutcomes.remove(ack)
                ack.complete(Unit)
              }
            } else if (failure != null) {
              entry.runId?.let(::markStoreFailure)
              (entry.outcome ?: coreOutcomes.remove(ack))?.compareAndSet(null, false)
            } else {
              // D15-2: the ack was already completed (abandoned waiter) but
              // the row still committed — record the proof before clearing
              // pending so a racing cancel handler observes COMMITTED,
              // never a false NOT_COMMITTED. Same [submitTruthLock] pair as
              // above: the snapshot below sees either pre- or post-commit,
              // never the torn mix. Same R23-1 late-commit clear as above:
              // a drain-timeout mark (whose waiter ack was failed first)
              // lifts on the proven commit, a store failure never does
              // (see [clearTimeoutMarkOnCommit]).
              entry.runId?.let { id ->
                synchronized(submitTruthLock) {
                  commitCounts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                  pendingCore.remove(id)
                  clearTimeoutMarkOnCommit(id)
                }
              }
              (entry.outcome ?: coreOutcomes.remove(ack))?.compareAndSet(null, true)
            }
          } else {
            entry.runId?.let { pendingCore.remove(it) }
          }
        }
      }
    } finally {
      // Any exit (teardown, fatal, or post-seal drain) seals: no later core
      // may park unbounded behind a dead writer.
      sealWriter(lastFailure ?: CancellationException("transcript writer dead"))
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

  /**
   * R18-2 direction-A per-entry receipt: terminal success using a
   * caller-provided ack. Identical ordering/ack contract to [writeCore],
   * except the entry ack is the supplied [receipt] (retained by the caller
   * for per-entry fate observation: committed vs failed vs pending), not a
   * fresh one. The writer completes it exactly like the internal ack, so a
   * waiter can tell terminal SUCCESS + system FAIL apart from terminal FAIL
   * + system SUCCESS even though both aggregate to the same per-runId
   * commitCounts/interrupted mix.
   */
  suspend fun onTurnSucceededWithReceipt(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
    receipt: CompletableDeferred<Unit>,
    outcome: AtomicReference<Boolean?>,
  ) = writeCoreWithAck(runId, receipt, outcome) {
    delegate.onTurnSucceeded(runId, text, parentRunId, attemptIndex, hasReasoning)
  }

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

  /**
   * R18-3 reasoning-aware failed partial: transparent ordered passthrough to
   * the seven-arg sink form (same [writeCore] admission/ack contract; the
   * marker rides inside the delegate batch, never a second entry, so
   * commitCounts/single-key design is unchanged).
   */
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
    hasReasoning: Boolean,
  ) = writeCore(runId) { delegate.onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal, hasReasoning) }

  /**
   * R18-2 direction-A per-entry receipt: terminal/intermediate failure using
   * a caller-provided ack. Same contract as [onTurnSucceededWithReceipt]:
   * the writer completes [receipt] with this entry outcome only, never an
   * aggregate per-runId count.
   *
   * R18-3: [hasReasoning] rides into the delegate seven-arg failed form so
   * the receipted write persists the marker atomically with its partial.
   */
  suspend fun onTurnFailedWithReceipt(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
    receipt: CompletableDeferred<Unit>,
    outcome: AtomicReference<Boolean?>,
    hasReasoning: Boolean = false,
  ) = writeCoreWithAck(runId, receipt, outcome) {
    delegate.onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal, hasReasoning)
  }

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

  /**
   * R18-1 Stage 2 cancel-intent notice (ordered notice, never a terminal).
   * Best-effort like [onTurnRetried] (same [writeOrdered] admission, no ack,
   * no commitCounts/pending): a kill before the deferred system/batch still
   * leaves the family dangling (reopen INTERRUPTED) instead of silently
   * closing without the partial. `retry` kind never closes a family.
   */
  override suspend fun onTurnCancelRequested(
    logicalTurnId: String,
    attemptRunId: String,
    attemptIndex: Int?,
  ) = writeOrdered { delegate.onTurnCancelRequested(logicalTurnId, attemptRunId, attemptIndex) }

  /**
   * R18-1 Stage 2 atomic FAILED batch (single core entry, single runId key).
   *
   * One [writeCore] entry keyed by the attempt id ([attemptRunId] ==
   * [CancelReceipt.cancelId]) whose delegate block persists
   * `partial(isFinal=false)` + `system cancelled(isFinal=true)` via one
   * [SessionStore.appendEvents] unit. Explicit commit-count design: the writer
   * increments `commitCounts[attemptRunId]` once for this entry, so the D15-2
   * thresholds still hold — first attempt (`cancelId == logicalTurnId`)
   * observes `user(1) + batch(1) = 2` (`>= 2` holds); fresh retry ids observe
   * `batch(1) = 1` (`>= 1` holds). The system row binds the logical family
   * (`runId == logicalTurnId`) for [findDanglingRunIds] closure; its runId is
   * intentionally NOT a second commit key (single-key design, documented here).
   * Old ledgers `[user + system]` without the partial are a known historical
   * gap (pre-R18-1 premature close) and are NOT retroactively backfilled —
   * the family is already closed there by the exact-text system mark.
   *
   * R18-3: [hasReasoning] adds the payload-free `reasoning` marker to the
   * partial inside the same batch (2 rows vs 3); single-key commit design
   * unchanged (one entry, one commit count).
   */
  override suspend fun onTurnFailedAndCancelled(
    logicalTurnId: String,
    attemptRunId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = writeCore(attemptRunId) {
    delegate.onTurnFailedAndCancelled(logicalTurnId, attemptRunId, partialText, error, parentRunId, attemptIndex, hasReasoning)
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
    writeOrdered(bestEffort = true) { delegate.onUsage(runId, inputTokens, outputTokens) }

  override suspend fun flushBufferedUsageBestEffort(runId: String) =
    writeOrdered(bestEffort = true) { delegate.flushBufferedUsageBestEffort(runId) }

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
    checkNotHoldingAdmissionLock("onFlushTailAwaitEnteredForTest")
    onFlushTailAwaitEnteredForTest?.invoke()
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
      // G3: deterministic park-point signal (sync, before the await suspend).
      checkNotHoldingAdmissionLock("onPrevAwaitEnteredForTest:writeCoreTracked")
      onPrevAwaitEnteredForTest?.invoke(reservation.ticket)
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
        // the channel, so drop the pending mark and propagate. D15-1: the
        // gate MUST NOT complete here — it is delegated to the forwarder by
        // the finally below, so the successor stays parked behind this slot's
        // predecessor instead of overtaking a still-waiting admission.
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
      // D15-1: pre-send abandon (!sendInvoked, never awaited-or-attempted)
      // defers to the forwarder so the successor cannot overtake a
      // still-waiting predecessor and the tail CAS is not cleared early;
      // post-send (sendInvoked, predecessor already awaited) completes
      // immediately — the successor transitively waited and waking it is
      // order-safe.
      if (sendInvoked) releaseAdmission(reservation.gate)
      else releaseAbandoned(reservation)
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
   * R18-2 per-entry core write using a caller-provided ack + outcome.
   *
   * Identical admission/ordering/ack contract to [writeCoreTracked] (same
   * ticket/chain, same fail-fast, same send-attempt proof, same writer
   * finally that records commitCounts + clears pending on success or marks
   * INTERRUPTED on failure), except the entry ack is the supplied [ack]
   * instead of a fresh one and the writer publishes this entry fate into
   * [outcome] (null = pending, true = committed, false = failed/sealed)
   * alongside the ack completion. The caller retains both as the terminal
   * entry receipt and the waiter observes [outcome] with a plain atomic
   * read — never the aggregated per-runId commitCounts/interrupted mix and
   * never delicate ack introspection. No channel semantic change; the older
   * [writeCore]/[writeCoreTracked] paths are untouched.
   */
  private suspend fun writeCoreWithAck(
    runId: String,
    ack: CompletableDeferred<Unit>,
    outcome: AtomicReference<Boolean?>,
    block: suspend () -> Unit,
  ) {
    require(!ack.isCompleted) { "receipt ack must be incomplete" }
    if (writerDead.get() || writer.isCompleted || channel.isClosedForSend) {
      markInterrupted(runId)
      outcome.compareAndSet(null, false)
      coreOutcomes.remove(ack)
      if (!ack.isCompleted) ack.completeExceptionally(CancellationException("transcript writer sealed"))
      throw CancellationException("transcript writer sealed")
    }
    val reservation = reserveAdmission()
    ack.invokeOnCompletion { cause ->
      if (cause != null) markInterrupted(runId)
    }
    coreAcks[ack] = runId
    coreOutcomes[ack] = outcome
    pendingCore.add(runId)
    var sendInvoked = false
    try {
      checkNotHoldingAdmissionLock("onPrevAwaitEnteredForTest:writeCoreWithAck")
      onPrevAwaitEnteredForTest?.invoke(reservation.ticket)
      reservation.prev?.await()
      checkNotHoldingAdmissionLock("beforeSendForTest:writeCoreWithAck")
      beforeSendForTest?.invoke(reservation.ticket)
      if (coroutineContext[Job]?.isCancelled == true) {
        throw CancellationException("admission waiter cancelled before send")
      }
      sendInvoked = true
      channel.send(Entry(runId, block, ack, outcome))
      checkNotHoldingAdmissionLock("afterSendForTest:writeCoreWithAck")
      afterSendForTest?.invoke(reservation.ticket)
    } catch (e: CancellationException) {
      if (!sendInvoked) {
        coreAcks.remove(ack)
        coreOutcomes.remove(ack)
        pendingCore.remove(runId)
        throw e
      }
      throw e
    } catch (e: ClosedSendChannelException) {
      coreAcks.remove(ack)
      markInterrupted(runId)
      outcome.compareAndSet(null, false)
      coreOutcomes.remove(ack)
      if (!ack.isCompleted) ack.completeExceptionally(e)
      throw e
    } catch (e: Exception) {
      if (!sendInvoked) {
        coreAcks.remove(ack)
        coreOutcomes.remove(ack)
        pendingCore.remove(runId)
      }
      throw e
    } finally {
      if (sendInvoked) releaseAdmission(reservation.gate)
      else releaseAbandoned(reservation)
    }
    try {
      ack.await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw e
    } finally {
      coreAcks.remove(ack)
      // Outcome stays for the waiter when the entry was admitted
      // (sendInvoked): the writer finally publishes true/false. Pre-send
      // abandon (!sendInvoked) never entered, so drop the mapping.
      if (!sendInvoked) coreOutcomes.remove(ack)
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
  private suspend fun writeOrdered(bestEffort: Boolean = false, block: suspend () -> Unit) {
    // No catch: admission failure is the caller's explicit signal.
    // CancellationException propagates to the cancelled caller, and
    // ClosedSendChannelException (sealed after shutdown) propagates to the
    // caller instead of succeeding silently.
    // Stage 3 (R4-5): same synchronous reservation as cores, so a waiting
    // notice holds its place ahead of later arrivals.
    // D15-1: pre-send abandon (!sendInvoked, await CE or hook throw before
    // the send) defers to the forwarder like cores; post-send (send invoked,
    // predecessor already awaited) completes immediately. The gate always
    // completes normally so an abandoned slot never sticks its successor.
    val reservation = reserveAdmission()
    var sendInvoked = false
    try {
      checkNotHoldingAdmissionLock("onPrevAwaitEnteredForTest:writeOrdered")
      onPrevAwaitEnteredForTest?.invoke(reservation.ticket)
      reservation.prev?.await()
      checkNotHoldingAdmissionLock("beforeSendForTest:writeOrdered")
      beforeSendForTest?.invoke(reservation.ticket)
      sendInvoked = true
      channel.send(Entry(runId = null, block, bestEffort = bestEffort))
    } finally {
      if (sendInvoked) releaseAdmission(reservation.gate)
      else releaseAbandoned(reservation)
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
    // delegated the slot (deferred forwarding when pre-send) on that path.
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
      // D15-1: pre-send abandon (await CE / hook throw before the send)
      // defers to the forwarder; post-send completes immediately. The gate
      // always completes normally with Unit.
      var sendInvoked = false
      try {
        // Admission order, not launch order: the predecessor's gate completes
        // once its send was attempted, so this send runs strictly after it.
        // G3: deterministic park-point signal for the queued steer job.
        checkNotHoldingAdmissionLock("onPrevAwaitEnteredForTest:offerSteerQueued")
        onPrevAwaitEnteredForTest?.invoke(reservation.ticket)
        reservation.prev.await()
        checkNotHoldingAdmissionLock("beforeSendForTest:offerSteerQueued")
        beforeSendForTest?.invoke(reservation.ticket)
        sendInvoked = true
        channel.send(entry)
      } catch (e: CancellationException) {
        throw e
      } catch (_: ClosedSendChannelException) {
        // Sealed after shutdown: no recovery path, drop explicitly.
      } finally {
        if (sendInvoked) releaseAdmission(reservation.gate)
        else releaseAbandoned(reservation)
      }
    }
    // D15-1: the fallback must also defer (not immediate-complete), or it
    // would wake the successor past a still-waiting predecessor whenever the
    // body delegated to a forwarder and the gate is still pending. The
    // duplicate forwarder is idempotent; the scope-cancel case still falls
    // back to an immediate complete inside the forwarder's own handler so
    // the chain never sticks behind a dead scope.
    job.invokeOnCompletion {
      if (!reservation.gate.isCompleted) releaseAbandoned(reservation)
    }
  }

  /**
   * Suspends until every event admitted so far is persisted, bounded by
   * [timeoutMs]. Returns true when the barrier drained (or, after [shutdown]
   * seals the channel, when the writer finished pre-seal work in time);
   * false on timeout, and false when a buffered barrier was failed
   * exceptionally by a writer-teardown seal race (the mapped seal-race
   * shape below — the durable truth then flows through
   * [hasDurableFailures]/[flushDurable] as usual). Cancellation of the
   * caller still propagates.
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
   *
   * Writer-teardown seal race: if the writer dies while a buffered barrier
   * is still queued behind it, [sealWriter]'s orphan drain fails that
   * barrier with the teardown CE. [flush] maps that race to `false` (not
   * drained) instead of rethrowing the foreign teardown exception — the
   * durable-failure truth then flows through [hasDurableFailures]/[flushDurable]
   * as usual. A caller cancellation, and any CE from a still-unsealed
   * writer, keep propagating (see the catch in the body).
   * Contrast with the post-seal shape: a flush that arrives AFTER the seal
   * never enqueues a barrier (ClosedSendChannelException) and instead joins
   * the still-draining pre-seal tail ([writer.join]) — that writer-join
   * wait completes normally and [flush] returns true even for a fatally
   * sealed writer ([flushDurable] stays false; the post-fatal control pins
   * both). The seal race only falsifies a barrier defeated mid-flight,
   * never a barrier issued after the seal.
   */
  suspend fun flush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean {
    // Writer-teardown seal-race signal: set when the buffered barrier was
    // completed exceptionally by [sealWriter]'s orphan drain (see the catch
    // below). Timeout shapes keep `null` (withTimeoutOrNull); an armed seal
    // race forces `false` even though the body otherwise returned.
    var barrierSealed = false
    try {
      return withTimeoutOrNull(timeoutMs) {
        awaitAdmissionTail()
        val reservation = reserveAdmission()
        // D15-1: pre-send abandon (await CE / hook throw / timeout before the
        // barrier send) defers to the forwarder; post-send completes
        // immediately. The gate always completes normally.
        var sendInvoked = false
        try {
          checkNotHoldingAdmissionLock("onPrevAwaitEnteredForTest:flush")
          onPrevAwaitEnteredForTest?.invoke(reservation.ticket)
          reservation.prev?.await()
          val barrier = CompletableDeferred<Unit>()
          checkNotHoldingAdmissionLock("beforeSendForTest:flush")
          beforeSendForTest?.invoke(reservation.ticket)
          sendInvoked = true
          try {
            channel.send(Entry(runId = null, block = {}, barrier))
          } catch (_: ClosedSendChannelException) {
            // Sealed: instead of the barrier, wait for the writer to finish
            // what was admitted pre-seal (still bounded by the same timeout).
            writer.join()
            return@withTimeoutOrNull
          }
          // Post-send test seam (null in production): same signal shape as
          // core writes, so a test can prove the barrier is buffered before
          // acting on the writer (e.g. firing a fatal) — otherwise the
          // send-vs-seal ordering is a race the sealed-join path above could
          // win, reporting a drained flush instead of the parked failure.
          checkNotHoldingAdmissionLock("afterSendForTest:flush")
          afterSendForTest?.invoke(reservation.ticket)
          // Release before the drain: the send was attempted, so successors
          // may proceed while this caller waits. Idempotent with the finally
          // below, which covers timeout/cancel abandonment paths.
          releaseAdmission(reservation.gate)
          // Seal-race test seam: exactly here the barrier is buffered and the
          // admission chain is clear — the deterministic proof point that the
          // writer teardown (whenever the test then arms it) will fail THIS
          // barrier via the [sealWriter] orphan drain (null in production).
          checkNotHoldingAdmissionLock("afterBarrierSendForTest:flush")
          afterBarrierSendForTest?.invoke()
          try {
            barrier.await()
          } catch (e: CancellationException) {
            // Writer-teardown race on a buffered barrier (CI flake root):
            // while this barrier sits in the channel behind a still-running
            // entry, the writer can die (fatal delegate Error, shutdown
            // scope cancel) and [sealWriter]'s orphan drain then completes
            // this VERY barrier exceptionally with its teardown CE
            // ("transcript writer dead"). That failure is not caller
            // cancellation, and rethrowing it would surface a foreign
            // teardown exception through [flushDurable] (which promises a
            // fail-closed Boolean). Mapping, in catch order:
            // - caller coroutine cancelled, or writer not yet sealed (some
            //   unrelated cancellation): rethrow — cancellation semantics
            //   unchanged;
            // - sealed writer: treat as NOT drained (barrierSealed). The
            //   seal already marked every unacked core under [poisonLock],
            //   so [hasDurableFailures] is true and [flushDurable] returns
            //   the fail-closed false instead of throwing.
            if (coroutineContext[Job]?.isCancelled == true || !isSealed()) throw e
            barrierSealed = true
          }
        } finally {
          // D15-1: a timeout/cancel abandoning the reserved slot before the
          // send defers to the forwarder (never an immediate complete past a
          // still-waiting predecessor); post-send is already released above
          // and this stays idempotent. The chain never sticks either way.
          if (sendInvoked) releaseAdmission(reservation.gate)
          else releaseAbandoned(reservation)
        }
      } != null && !barrierSealed
    } catch (e: CancellationException) {
      throw e
    }
  }

  /**
   * True while the sink's durability truth is poisoned: `[interrupted]` is
   * non-empty. The set only ever holds two mark kinds with different
   * permanence —
   * - a genuine persistence failure ([markStoreFailure]): pinned by
   *   [storeFailed], never cleared — [clearTimeoutMarkOnCommit] refuses it;
   * - a drain-timeout / seal / fail-fast orphan mark: **provisional** —
   *   lifted by [clearTimeoutMarkOnCommit] when the still-running writer
   *   proves the same-id commit (R23-1).
   *
   * So `true` never means "permanently failed" for the second kind: a failing
   * read is the fail-closed provisional truth inside that window, and a later
   * proven commit for the same runId legitimately restores a clean read.
   * Deliberately outside this set (and outside this boolean): proven
   * never-delivered ghosts ([resolvedUndelivered] / [markNeverAdmitted]) —
   * they stay visible via [interruptedRunIds] only and never poison
   * durability truth.
   */
  fun hasDurableFailures(): Boolean = synchronized(poisonLock) { interrupted.isNotEmpty() }

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
   *
   * Budget truth (Phase 5): the background job spends up to ~3x
   * [drainTimeoutMs] total — settle ≤ 1x, flush barrier ≤ 1x (full budget,
   * not the settle remainder), writer-join ≤ 1x. A caller awaiting it via
   * flush-then-drain observes up to ~2x its own [flush] timeout (shutdown-join
   * 1x + admitted-drain 1x); the background job may still be inside its third
   * budget when the caller stops waiting.
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
        } catch (e: Throwable) {
          // D15-4 fail-closed + Security Finding3 (Stage-3, intentional
          // teardown swallow): settle is best-effort, so an Error from it
          // (like an Exception, including a CancellationException from a
          // cancelled settle — the NonCancellable context above makes that
          // a settle bug, never an outer-scope cancel) still falls through
          // to the drain attempt below instead of aborting the teardown.
          // Durability truth stays in interrupted/hasDurableFailures, never
          // a false success from this swallow. EXCEPT VM-fatal: OOM /
          // StackOverflowError / ThreadDeath is never swallowed here —
          // rethrown per the S3-F1 house pattern (TurnController); other
          // Errors (e.g. AssertionError/LinkageError) stay in this
          // best-effort swallow. Honest cost: teardown aborts early on OOM
          // and the drain is not attempted, which is the correct
          // fail-loud choice.
          if (e is VirtualMachineError || e is ThreadDeath) throw e
        }
        val flushed = try {
          flush(drainTimeoutMs)
        } catch (_: Throwable) {
          // Security Finding3 (Stage-3, intentional teardown swallow): same
          // rationale as the settle catch above — a throw here (non-VM Error
          // from a broken store, a CE from a flush bug, even VM-fatal) must
          // fall through to the orphan mark + seal below instead of aborting
          // the teardown; the writer entry loop already rethrew any VM-fatal
          // to seal. flushed=false drives the fail-closed path, so the
          // swallow can never fabricate durability.
          false
        }
        if (!flushed) {
          val cause = CancellationException("transcript drain timeout")
          for (runId in pendingCore.toList()) {
            // R23-1 late-commit race: the writer may commit between the
            // snapshot above and this mark. Re-check pending membership under
            // the same [submitTruthLock] the success path uses for its
            // commit-then-clear: a commit that already landed removed pending
            // (and cleared any mark) → skip, never re-poison a durable row.
            // A still-pending id is a genuine timeout orphan → mark (a later
            // commit still clears it via the success path). Pending-gone also
            // covers the already-settled shapes (store failure marked,
            // resolve/seal settled) — skipping them is a no-op either way.
            // Membership (not the aggregate commit count) is the discriminator:
            // a first-attempt id shares its user row and terminal, so a
            // committed user row must never shield its still-pending terminal
            // from the mark. Acks still fail for every id (waiters must unpark
            // either way).
            // Snapshot→mark gap note (no extra seam): ids admitted after the
            // snapshot above are not in this loop, but they are not lost —
            // the [sealWriter] integration gate below (outstanding-ack sweep
            // + orphan-buffered drain after channel.close()) marks every
            // still-unacked core, so the gap stays fail-closed without a
            // flaky admission-vs-snapshot seam. A concurrent late commit for
            // such an id still clears a timeout mark via the success path.
            synchronized(submitTruthLock) {
              if (pendingCore.contains(runId)) markInterrupted(runId)
            }
            failAcksFor(runId, cause)
          }
        }
        channel.close()
        withTimeoutOrNull(drainTimeoutMs) {
          try {
            writer.join()
          } catch (e: Throwable) {
            // D15-4 fail-closed + Security Finding3 (Stage-3, intentional
            // teardown swallow): a failed writer (including a fatal Error
            // it rethrew to seal) still falls through to the scope cancel
            // below instead of aborting the teardown. Best effort: the
            // ack/interrupt marks already carry the failure. EXCEPT
            // VM-fatal: OOM / StackOverflowError / ThreadDeath is never
            // swallowed here — rethrown per the S3-F1 house pattern
            // (TurnController); honest cost: teardown aborts early on OOM
            // (the final scope cancel below is skipped), which is the
            // correct fail-loud choice.
            if (e is VirtualMachineError || e is ThreadDeath) throw e
          }
        }
        writerScope.cancel()
      }
    }
  }

  /** Core runIds admitted but still awaiting the writer. */
  fun pendingRunIds(): List<String> = pendingCore.toList()

  /**
   * D15-2 synchronous submit-truth queries (all non-suspending, safe from a
   * cancelled host): the cancel handler combines pending + commit count with
   * its attempt-dimension terminal intent to tell SUBMITTING (admitted, writer
   * not yet committed) vs COMMITTED (writer committed, ack may still be in
   * flight) vs FAILED-or-never-submitted (no commit for the id, keeps the
   * normal final cancel assistant).
   *
   * - [isCorePending]: id still in [pendingCore] (admitted, writer has not
   *   finished its block). True until the writer `finally` records the commit
   *   and clears pending (committed) or marks INTERRUPTED (failed).
   * - [coreCommitCount]: successful writer commits for the id (user row +
   *   terminal share the first-attempt id, so the controller treats
   *   `cancelId == logicalTurnId` as terminal-committed only at `>= 2`,
   *   fresh retry ids at `>= 1`). Append-only; a failed store write never
   *   increments it.
   * - [isCoreCommitted]: convenience (`count > 0`); the controller prefers
   *   [coreCommitCount] for the attempt-dimension threshold above.
   */
  fun isCorePending(runId: String): Boolean = pendingCore.contains(runId)

  fun coreCommitCount(runId: String): Int = commitCounts[runId]?.get() ?: 0

  fun isCoreCommitted(runId: String): Boolean = coreCommitCount(runId) > 0

  /**
   * R17-1 P1 review: atomic submit-truth snapshot for one runId.
   *
   * The writer success path used to publish its two signals with two
   * unsynchronized map ops (commit count increment, then pending clear), so
   * a waiter reading them with two separate calls (commit-then-pending)
   * could observe the torn mix — stale count `0` with already-cleared
   * pending — and misclaim a committed terminal as FAILED (duplicate
   * compensation write). Both success-path pairs above now publish under
   * [submitTruthLock], and this snapshot reads both under the same lock, so
   * callers see either the pre-commit or the post-commit state, never the
   * mix. (Failure paths never increment the count, so their pending clear
   * cannot tear toward a false COMMITTED; only the success pair needs the
   * lock.) Read order inside is pending-then-count so that even a
   * hypothetical unsynchronized reader would stall one more round (safe
   * side) instead of misclaiming. Prefer this over the separate
   * [isCorePending]/[coreCommitCount] pair wherever a commit-vs-failed
   * decision is made; the older accessors stay for the pre-existing D15
   * settle paths, whose logic is untouched.
   */
  data class CoreSubmitTruth(val commitCount: Int, val pending: Boolean)

  fun coreSubmitTruth(runId: String): CoreSubmitTruth = synchronized(submitTruthLock) {
    // P-then-C inside the lock (order is belt-and-braces under the monitor).
    val pending = pendingCore.contains(runId)
    val count = commitCounts[runId]?.get() ?: 0
    CoreSubmitTruth(count, pending)
  }

  /**
   * R14-1 writer-completion proof: true only once the session writer can no
   * longer execute a store block. The writer runs `entry.block()` with no
   * pre-block cancellation gate, and a blocking delegate (Room
   * `runInTransaction`) has no suspension point between its first write and
   * COMMIT, so `isSealed()` (which is also true on a bare `channel.close()`)
   * plus an emptied pending set does NOT prove a late terminal is
   * impossible — a timed-out shutdown clears pending via [markInterrupted]
   * and closes the channel while the writer is still inside `block()`.
   * `writer.isCompleted` (or [writerDead], set in the writer's own finally)
   * proves the in-flight block resolved (committed or rolled back), so the
   * backfill gate conjoins this with seal + empty pending before treating an
   * expired outstanding teardown as dead.
   */
  fun isWriterCompleted(): Boolean = writer.isCompleted || writerDead.get()

  /**
   * True once the writer is sealed/torn down: later core writes fail fast
   * (fail-fast `CancellationException` or [ClosedSendChannelException]) instead
   * of parking unbounded behind a dead writer. Mirrors the [writeCore]
   * fail-fast gate; callers use it to distinguish a seal race (already marked
   * INTERRUPTED) from a genuine write failure without matching message text.
   */
  fun isSealed(): Boolean = writerDead.get() || writer.isCompleted || channel.isClosedForSend

  /** Core runIds admitted but never acked (drain timeout / seal race). */
  fun interruptedRunIds(): List<String> = synchronized(poisonLock) {
    // Single outer lock, sequential leaf reads — never nests the two
    // collection monitors (A1: no interrupted→resolved nesting).
    interrupted.toList() + resolvedUndelivered.toList()
  }

  internal fun markInterrupted(runId: String) {
    // R10-3: a proven-undelivered ghost resolved via tryResolveUndelivered
    // must never become a global durable failure — not even through the ack
    // handler below (the resolver fails the ghost ack exceptionally, which
    // re-enters here). Keep it only in the resolved set. Atomic with
    // tryResolve/seal under [poisonLock]: check-then-add has no gap.
    synchronized(poisonLock) {
      if (resolvedUndelivered.contains(runId)) {
        pendingCore.remove(runId)
        return
      }
      pendingCore.remove(runId)
      if (!interrupted.contains(runId)) interrupted.add(runId)
    }
  }

  /**
   * Atomic landing-check variant of [markInterrupted] for cancel
   * compensation: mark [runId] only when no commit landed after the caller's
   * [beforeCommitCount] snapshot. The count re-check and the mark run under
   * [submitTruthLock] (nested [poisonLock] inside it, the established
   * submitTruthLock → poisonLock order shared with [clearTimeoutMarkOnCommit]),
   * closing the read→mark gap: a commit racing the caller's timeout either
   * bumped the count before this lock (mark skipped) or takes the same lock
   * afterwards and clears any mark via [clearTimeoutMarkOnCommit] — never a
   * sticky false INTERRUPTED.
   */
  internal fun markInterruptedIfNoNewCommit(runId: String, beforeCommitCount: Int) {
    synchronized(submitTruthLock) {
      val after = commitCounts[runId]?.get() ?: 0
      if (after <= beforeCommitCount) markInterrupted(runId)
    }
  }

  /**
   * R23-1 genuine store-failure poison: the writer block for [runId] threw
   * (any non-cancellation Exception from the delegate, e.g. store
   * IOException, plus teardown/fatal mid-entry), so the row provably never
   * persisted. Records the durable mark like [markInterrupted] plus the
   * [storeFailed] tier, which the success-path late-commit clear honors: a
   * drain-timeout/seal orphan that later commits lifts its mark (the row is
   * durable), a genuine failure never does — even when a later entry reuses
   * the same first-attempt id (user row failed, terminal committed: the
   * family still dangles). Compound under [poisonLock] like every other
   * poison mutation (A1 order).
   */
  internal fun markStoreFailure(runId: String) {
    synchronized(poisonLock) {
      if (resolvedUndelivered.contains(runId)) {
        pendingCore.remove(runId)
        return
      }
      pendingCore.remove(runId)
      if (!interrupted.contains(runId)) interrupted.add(runId)
      storeFailed.add(runId)
    }
  }

  /**
   * R23-1 late-commit clear for a genuinely committed row: lifts a
   * drain-timeout/seal orphan mark (the row IS durable now) but never a
   * genuine store failure ([storeFailed]). Must be called under
   * [submitTruthLock] as part of the commit-then-clear pair; nests
   * [poisonLock] (submitTruthLock → poisonLock order, never reversed, same
   * as the previous inline sections this replaces — no new lock-order edge,
   * no suspend/delegate/channel work inside).
   */
  private fun clearTimeoutMarkOnCommit(runId: String) {
    synchronized(poisonLock) {
      if (!storeFailed.contains(runId)) interrupted.remove(runId)
    }
  }

  /** R10-3: true only for proven-undelivered ghosts (never counted durable-failed). */
  internal fun isResolvedUndelivered(runId: String): Boolean =
    synchronized(poisonLock) { resolvedUndelivered.contains(runId) }

  /**
   * R11-2 proven never-attempted admission: the channel send was never
   * invoked (`!sent && !sendAttempted`), so the entry provably never entered
   * the channel and no ledger row was ever persisted. Records the id as
   * visible-but-not-durable-failed (same set and semantics as the R10-3
   * barrier-resolved ghosts): [interruptedRunIds] still reports it, but
   * [hasDurableFailures]/[flushDurable] never count it, so a later successful
   * retry of the same op on the same live session drains cleanly. Never
   * clears or moves a genuine admitted failure already in [interrupted].
   */
  internal fun markNeverAdmitted(runId: String) {
    synchronized(poisonLock) {
      if (interrupted.contains(runId)) return
      pendingCore.remove(runId)
      if (!resolvedUndelivered.contains(runId)) resolvedUndelivered.add(runId)
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
   * INTERRUPTED mark in the R10-3 resolved-undelivered set (visible via
   * [interruptedRunIds] but never counted by [hasDurableFailures]/
   * [flushDurable]), and returns true. Already-cleared (delivered + claimed)
   * returns false (idempotent); an already-resolved ghost returns true without
   * re-failing acks. A sealed writer returns false on the best-effort
   * pre-check below — but that check is NOT the linearization point: when
   * seal races resolve, the [poisonLock] check-then-add decides exactly one
   * winner (resolve-first → seal skips via its resolved guard, no
   * dual-poison; seal-first → this returns false and defers to the seal's
   * INTERRUPTED mark, which is then the correct terminal-never-lands mark).
   * A resolved ghost therefore never counts toward [hasDurableFailures] even
   * under seal competition, while genuine failures still poison.
   *
   * R10-3 ordering: the id joins [resolvedUndelivered] BEFORE the ack is
   * failed, so the ack handler ([markInterrupted]) observes it and skips the
   * global durable-failure mark even under race. The
   * `interrupted.contains` check and the `resolved.add` share [poisonLock]
   * with [sealWriter]'s resolved-guard + poison, so the gap between them is
   * atomic (A1/A2).
   */
  fun tryResolveUndelivered(runId: String): Boolean {
    if (isSealed()) return false
    if (!pendingCore.remove(runId)) {
      // Idempotent: an already-resolved ghost is still a successful resolve
      // for callers that re-settle, but must not re-fail acks.
      return synchronized(poisonLock) { resolvedUndelivered.contains(runId) }
    }
    synchronized(poisonLock) {
      if (interrupted.contains(runId)) return false
      if (resolvedUndelivered.contains(runId)) return true
      resolvedUndelivered.add(runId)
    }
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
    // No global mark: the barrier proof makes this a proven never-delivered
    // attempt (true mark in the resolved set above), never a durable failure.
    return true
  }

  /**
   * Seals the writer exactly once: closes the channel (unblocking parked
   * senders with [ClosedSendChannelException]), fails every outstanding core
   * ack, and drains orphan-buffered entries admitted but never consumed.
   * Bounded and non-suspending; later core writes fail fast via [writerDead].
   * A2: both poison loops check [resolvedUndelivered] under [poisonLock]
   * before touching [interrupted] — an already-resolved ghost is skipped
   * (never dual-poisoned), while genuine failures still poison.
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
      coreOutcomes.remove(ack)?.compareAndSet(null, false)
      pendingCore.remove(runId)
      synchronized(poisonLock) {
        if (!resolvedUndelivered.contains(runId) && !interrupted.contains(runId)) {
          interrupted.add(runId)
        }
      }
      try {
        if (!ack.isCompleted) ack.completeExceptionally(cause)
      } catch (_: Exception) {
        // Best effort: the waiter already observes the interrupt mark.
      }
    }
    while (true) {
      val entry = channel.tryReceive().getOrNull() ?: break
      entry.outcome?.compareAndSet(null, false)
      entry.ack?.let { coreOutcomes.remove(it)?.compareAndSet(null, false) }
      entry.runId?.let { runId ->
        pendingCore.remove(runId)
        synchronized(poisonLock) {
          if (!resolvedUndelivered.contains(runId) && !interrupted.contains(runId)) {
            interrupted.add(runId)
          }
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
    const val BEST_EFFORT_TIMEOUT_MS = 2_000L
  }
}
