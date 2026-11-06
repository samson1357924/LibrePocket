package dev.librepocket.privilege.github

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 遠端進程包裝（B2，Issue #136 B2，僅 github 風味）。
 *
 * 背景：Shizuku 13.x 的 `newProcess(argv, env, dir)` 為 private，反射回傳物
 * 並非 `java.lang.Process` 子類（舊碼 `as Process` 即 CCE，見 Issue #136 §5）。
 * 本類以反射委派同名方法（streams/wait/exit/destroy），呼叫方一律經
 * [wrap] 取 [Process]，永不直 cast。
 *
 * - 反射方法缺失/簽名漂移（版本差異）即抛給呼叫方轉 `Failed`（fail-closed，
 *   不偽造成功；下游 cast 漂移同理以 IllegalStateException 收斂為 Failed）；
 * - P1-B：[call] 精確匹配重載（名 + 參數個數 + 參數型別可賦值性，primitive
 *   long/int 由 Number 承接、boolean 由 Boolean 承接），無精確匹配即抛
 *   UnsupportedOperationException，避免 overload 選錯靜默誤調；
 * - 零真機依賴：單測以「同名方法但非 Process」的假物件斷言委派，證明無 cast。
 * P2-A 可見性：僅 [ShizukuShellRunner]＋同模組單測使用，收斂為 internal；
 * 外部一律經 `Process` 介面（[wrap] 回傳型別）互動，不直接依賴本類。
 * R3-B（PocketGuard WARN 反射）：fail-closed＋版本漂移收斂已在上；Shizuku
 * 升級時跑 ShizukuRemoteProcessTest 即知，不改行為。
 */
internal class ShizukuRemoteProcess(private val remote: Any) : Process() {
    private fun call(name: String, vararg args: Any?): Any? {
        val candidates = remote.javaClass.methods.filter { it.name == name && it.parameterCount == args.size }
        // P1-B 精確匹配：名 + 個數 + 參數型別可賦值性（含 primitive long/int/boolean），
        // 無精確匹配即抛，避免 overload 選錯靜默誤調。
        val method = candidates.firstOrNull { m ->
            m.parameterTypes.indices.all { i -> isCompatible(m.parameterTypes[i], args[i]) }
        }
            ?: throw UnsupportedOperationException("shizuku remote has no $name/${args.size}")
        method.isAccessible = true
        val params = method.parameterTypes
        val coerced = args.mapIndexed { i, a ->
            coerceArg(a, params[i])
        }.toTypedArray()
        return method.invoke(remote, *coerced)
    }

    private fun isCompatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        if (type.isInstance(value)) return true
        // primitive 承接：invoke 側由 coerceArg 拆箱，選型側先放行。
        if (type == java.lang.Long.TYPE && value is Number) return true
        if (type == java.lang.Integer.TYPE && value is Number) return true
        if (type == java.lang.Boolean.TYPE && value is Boolean) return true
        return false
    }

    private fun coerceArg(value: Any?, type: Class<*>): Any? {
        if (value == null) return null
        if (type.isInstance(value)) return value
        if (type == java.lang.Long.TYPE && value is Number) return value.toLong()
        if (type == java.lang.Integer.TYPE && value is Number) return value.toInt()
        if (type == java.lang.Boolean.TYPE && value is Boolean) return value
        return value
    }

    override fun getOutputStream(): OutputStream =
        call("getOutputStream") as? OutputStream
            ?: throw IllegalStateException("shizuku remote getOutputStream type drift (fail-closed)")

    override fun getInputStream(): InputStream =
        call("getInputStream") as? InputStream
            ?: throw IllegalStateException("shizuku remote getInputStream type drift (fail-closed)")

    override fun getErrorStream(): InputStream =
        call("getErrorStream") as? InputStream
            ?: throw IllegalStateException("shizuku remote getErrorStream type drift (fail-closed)")

    override fun waitFor(): Int =
        (call("waitFor") as? Number)?.toInt()
            ?: throw IllegalStateException("shizuku remote waitFor type drift (fail-closed)")

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
        call("waitFor", timeout, unit) as? Boolean
            ?: throw IllegalStateException("shizuku remote waitFor(timeout) type drift (fail-closed)")

    override fun exitValue(): Int =
        (call("exitValue") as? Number)?.toInt()
            ?: throw IllegalStateException("shizuku remote exitValue type drift (fail-closed)")

    override fun destroy() {
        // P2 對稱語義（best-effort 吞＋suppressed 註記）：Shizuku 遠端 destroy
        // 可為同步 Binder IPC（stall/死亡即抛），呼叫方（drain kill 監督 worker／
        // ElevatedProcessKiller）一律 best-effort，已逐段 runCatching；此處再兜底
        // 吞（雙層 best-effort，永不外抛，不靜默：上層監督旗標＋計數仍帶出缺口）。
        // B2 P1-3：吞≠已停，真死由 drain post-kill 驗證（waitFor／liveness，
        // binder 不可達視 unknown）確認，失敗即 cleanupIncomplete=true。
        runCatching { call("destroy") }
    }

    override fun destroyForcibly(): Process {
        // P2 對稱語義（同 destroy）：至少一次成功才算成功已收斂為「全 best-effort
        // 吞」——destroyForcibly 缺席/抛即回落 destroy，仍抛即吞（永不外抛，
        // 由上層 killer/drain 監督收斂；舊碼第二段直調可抛，現收斂）。
        // B2 P1-3：同上，吞≠已停，由 drain 驗證（見上）。
        runCatching { call("destroyForcibly") }
        runCatching { call("destroy") }
        return this
    }

    companion object {
        /**
         * 反射回傳物 → [Process]：已是 Process 即直回（`is` 檢查，非 cast 斷言），
         * 否則以 [ShizukuRemoteProcess] 包裝；null 即抛（fail-closed）。
         */
        fun wrap(raw: Any?): Process {
            if (raw == null) throw IllegalStateException("shizuku newProcess returned null")
            if (raw is Process) return raw
            return ShizukuRemoteProcess(raw)
        }
    }
}
