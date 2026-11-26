package dev.librepocket.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.view.Choreographer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B3 (#16) bounded on-device benchmark: fixed synthetic transcript, no network,
 * no production credentials, no private data, no OOM stress.
 *
 * Phases (per replicate):
 * - `create`: in-memory Room session + [ROWS] synthetic rows written through
 *   the real [RoomSessionStore.appendEvent] shaping;
 * - `resume`: bounded `loadTailEvents` read (the production resume read shape)
 *   over the SAME in-memory DB immediately after its own writes — a WARM read,
 *   NOT cold-disk hydration (no disk I/O is measured here);
 * - `hydration`: a LOCAL window/join workload with 200 rows / 60_000 chars —
 *   the current `HistoryWindowCap.DEFAULT` numbers — labelled honestly as a
 *   hydration-SHAPED workload, NOT a full `ChatSessionFactory` create/resume
 *   wiring proof and NOT a tokenizer measurement.
 *
 * GATE (structural only): fixed input is exactly [ROWS] x [CHARS_PER_ROW] chars,
 * the resume read returns all rows, the local window obeys 200/60_000, and the
 * report is written/parseable. DIAGNOSTIC (never a threshold): native/Java
 * heap deltas, `Choreographer` frame count and elapsed ms are recorded but NOT
 * asserted against any number. The frame count is a main-thread baseline
 * (idle frames + stop-the-world GC), NOT UI jank or work-cost evidence; the
 * Java heap delta has no end-of-phase GC and includes uncollected garbage.
 * Thresholds are deliberately NOT established here: measure on the authorized
 * device first, then set them in a reviewed follow-up (see docs/TESTING.md).
 *
 * Boundedness: [ROWS]=200, [CHARS_PER_ROW]=400 (80k chars total), [REPLICATES]=3,
 * a 300 ms frame-collection window, an in-memory DB closed in [tearDown], and a
 * JUnit wall-clock timeout. No huge fixtures, no unbounded loops, no sleeps
 * outside the fixed windows, no install/uninstall or data clearing.
 */
@RunWith(AndroidJUnit4::class)
class BudgetDeviceBenchmark {
  companion object {
    private const val ROWS = 200
    private const val CHARS_PER_ROW = 400
    private const val REPLICATES = 3
    private const val RESUME_LIMIT = 200
    private const val WINDOW_ROWS = 200
    private const val WINDOW_CHARS = 60_000
    private const val FRAME_WINDOW_MS = 300L
    private const val SETTLE_MS = 50L
  }

  private var db: LibrePocketDb? = null
  private var activeSampler: FrameSampler? = null

  @After
  fun tearDown() {
    // Fail-closed: a GATE assertion can throw mid-phase; never leave a
    // self-reposting frame callback behind for the next test in the process.
    activeSampler?.stop()
    activeSampler = null
    db?.close()
    db = null
  }

  /** Start a sampler and park it in the field so @After can always stop it. */
  private fun startFrameSampling() {
    activeSampler?.stop()
    val sampler = newFrameSampler()
    activeSampler = sampler
    sampler.start()
  }

  /** Idempotent; returns the diagnostic frame count collected so far. */
  private fun finishFrameSampling(): Int {
    val sampler = activeSampler ?: return 0
    val count = sampler.stop()
    activeSampler = null
    return count
  }

  private data class Heap(val nativeBytes: Long, val javaUsedBytes: Long, val availBytes: Long)

  private fun snapshotHeap(context: Context): Heap {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    val runtime = Runtime.getRuntime()
    return Heap(
      nativeBytes = Debug.getNativeHeapAllocatedSize(),
      javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
      availBytes = info.availMem,
    )
  }

  /** Choreographer is Looper-bound: construct/post/remove on the main thread. */
  private class FrameSampler {
    private val choreographer: Choreographer = Choreographer.getInstance()
    @Volatile private var frames = 0
    private val callback = object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        frames++
        choreographer.postFrameCallback(this)
      }
    }

    fun start() {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        choreographer.postFrameCallback(callback)
      }
    }

    /** Stops the callback; the count is diagnostic only, never a gate. */
    fun stop(): Int {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        choreographer.removeFrameCallback(callback)
      }
      return frames
    }
  }

  private fun newFrameSampler(): FrameSampler {
    var sampler: FrameSampler? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { sampler = FrameSampler() }
    return requireNotNull(sampler)
  }

  private fun settle() {
    System.gc()
    SystemClock.sleep(SETTLE_MS)
  }

  private fun syntheticRow(index: Int): String {
    val tag = "r$index:"
    return tag + "x".repeat(CHARS_PER_ROW - tag.length)
  }

  @Test(timeout = 120_000)
  fun measureCreateResumeHydration() {
    val context: Context = ApplicationProvider.getApplicationContext()
    val gitSha = InstrumentationRegistry.getArguments().getString("gitSha", "UNSPECIFIED")
    val replicates = JSONArray()

    repeat(REPLICATES) { replicate ->
      settle()
      val phases = JSONArray()

      var startHeap = snapshotHeap(context)
      var endHeap = startHeap
      var elapsedNs = 0L
      var frames = 0
      var store: RoomSessionStore? = null
      var sid: String? = null
      // ---- create: bounded synthetic writes through the real store path ----
      startFrameSampling()
      try {
        val startNs = System.nanoTime()
        val opened = LibrePocketDb.openInMemory(context)
        db = opened
        val sessionStore = RoomSessionStore(opened)
        store = sessionStore
        val createdSid = kotlinx.coroutines.runBlocking { sessionStore.createSession("bm-synthetic", "fake/model") }
        sid = createdSid
        kotlinx.coroutines.runBlocking {
          for (i in 0 until ROWS) {
            sessionStore.appendEvent(
              TranscriptEvent(
                sessionId = createdSid,
                runId = "bm-run-$i",
                kind = if (i % 2 == 0) "user" else "assistant",
                text = syntheticRow(i),
                createdAt = 1_700_000_000_000L + i,
              ),
            )
          }
        }
        elapsedNs = System.nanoTime() - startNs
        SystemClock.sleep(FRAME_WINDOW_MS)
        endHeap = snapshotHeap(context)
      } finally {
        frames = finishFrameSampling()
      }
      phases.put(phaseJson("create", startHeap, endHeap, elapsedNs, frames, JSONObject()))
      val sessionStore = requireNotNull(store)
      val sessionId = requireNotNull(sid)

      // ---- resume: bounded WARM in-memory tail read (no cold-disk I/O) ----
      settle()
      startHeap = snapshotHeap(context)
      endHeap = startHeap
      elapsedNs = 0L
      var events: List<TranscriptEvent> = emptyList()
      startFrameSampling()
      try {
        val startNs = System.nanoTime()
        events = kotlinx.coroutines.runBlocking { sessionStore.loadTailEvents(sessionId, RESUME_LIMIT) }
        elapsedNs = System.nanoTime() - startNs
        SystemClock.sleep(FRAME_WINDOW_MS)
        endHeap = snapshotHeap(context)
      } finally {
        frames = finishFrameSampling()
      }
      // GATE: fixed input is reproduced exactly, never a timing/heap number.
      assertEquals(ROWS, events.size)
      assertEquals((ROWS * CHARS_PER_ROW).toLong(), events.sumOf { it.text.length.toLong() })
      phases.put(phaseJson("resume", startHeap, endHeap, elapsedNs, frames, JSONObject()))

      // ---- hydration: local 200/60k window + join (NOT a tokenizer) ----
      settle()
      startHeap = snapshotHeap(context)
      endHeap = startHeap
      elapsedNs = 0L
      var keptRows = 0
      var keptChars = 0L // sum of row text lengths, separators excluded
      var joinedLength = 0L
      startFrameSampling()
      try {
        val startNs = System.nanoTime()
        val builder = StringBuilder()
        for (event in events.asReversed()) { // newest first, drop oldest
          val separator = if (keptRows == 0) 0L else 1L
          val nextWithSeparators = keptChars + separator + event.text.length.toLong()
          if (keptRows >= WINDOW_ROWS || nextWithSeparators > WINDOW_CHARS.toLong()) break
          if (builder.isNotEmpty()) builder.append('\n')
          builder.append(event.text)
          keptChars += event.text.length.toLong()
          keptRows++
        }
        joinedLength = builder.length.toLong()
        elapsedNs = System.nanoTime() - startNs
        SystemClock.sleep(FRAME_WINDOW_MS)
        endHeap = snapshotHeap(context)
      } finally {
        frames = finishFrameSampling()
      }
      val structural = JSONObject().apply {
        put("keptRows", keptRows)
        put("keptChars", keptChars)
        put("joinedLength", joinedLength)
      }
      // GATE: the local window obeys the honest char-based DEFAULT bounds.
      assertTrue("window must keep at least one row", keptRows > 0)
      assertTrue("window rows must be bounded", keptRows <= WINDOW_ROWS)
      assertTrue("window chars must be bounded", keptChars <= WINDOW_CHARS.toLong())
      assertEquals(keptChars + (keptRows - 1).toLong(), joinedLength)
      phases.put(phaseJson("hydration", startHeap, endHeap, elapsedNs, frames, structural))

      replicates.put(JSONObject().apply {
        put("replicate", replicate)
        put("phases", phases)
      })
      // Close before the next replicate so heap deltas are not contaminated
      // by the previous DB (tearDown is the backstop if an assertion throws).
      db?.close()
      db = null
    }

    val report = JSONObject().apply {
      put("schema", "librepocket-budget-device-benchmark/v1")
      put("gitSha", gitSha)
      put("deviceNote", "serial is operator-supplied via adb -s; not read by the test app")
      put("build", JSONObject().apply {
        put("model", Build.MODEL)
        put("api", Build.VERSION.SDK_INT)
        put("fingerprint", Build.FINGERPRINT)
        put("package", context.packageName)
      })
      put("workload", JSONObject().apply {
        put("rows", ROWS)
        put("charsPerRow", CHARS_PER_ROW)
        put("totalChars", ROWS * CHARS_PER_ROW)
        put("replicates", REPLICATES)
        put("scope", "hydration-shaped local window; NOT production ChatSessionFactory wiring; " +
          "resume=warm in-memory read over openInMemory (no cold-disk I/O); " +
          "frames=main-thread Choreographer baseline, NOT UI jank evidence")
      })
      put("tokenWindow", JSONObject().apply {
        put("status", "TARGET_NOT_IMPLEMENTED")
        put("note", "char-based ~4 chars/token approximation only; no tokenizer count")
      })
      put("thresholds", JSONObject().apply {
        put("status", "NOT_ESTABLISHED")
        put("note", "heap/frame/latency numbers are diagnostic; measure before setting a gate")
      })
      put("replicates", replicates)
    }

    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val out = File(dir, "budget-device-benchmark.json")
    out.writeText(report.toString(2))
    println("BudgetDeviceBenchmark: wrote ${out.absolutePath} (gitSha=$gitSha)")
    println(report.toString())
    assertTrue("report must be readable back", JSONObject(out.readText()).getJSONArray("replicates").length() == REPLICATES)
  }

  /** All metric fields are diagnostic; [structural] fields carry the only gates. */
  private fun phaseJson(
    name: String,
    before: Heap,
    after: Heap,
    elapsedNs: Long,
    frames: Int,
    structural: JSONObject,
  ): JSONObject = JSONObject().apply {
    put("phase", name)
    put("diagnostic", JSONObject().apply {
      put("note", "framesObserved is a main-thread Choreographer baseline (idle/GC), NOT UI jank " +
        "or work-cost evidence; resume is a warm in-memory read with no cold-disk I/O; " +
        "javaHeapUsedDeltaBytes has no end-of-phase GC and includes uncollected garbage — all diagnostic only")
      put("elapsedMs", elapsedNs / 1_000_000.0)
      put("nativeHeapDeltaBytes", after.nativeBytes - before.nativeBytes)
      put("javaHeapUsedDeltaBytes", after.javaUsedBytes - before.javaUsedBytes)
      put("nativeHeapAfterBytes", after.nativeBytes)
      put("availableMemoryBytes", after.availBytes)
      put("framesObserved", frames)
    })
    put("structural", structural)
  }
}
