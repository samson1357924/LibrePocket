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
 *   不偽造成功）；
 * - 零真機依賴：單測以「同名方法但非 Process」的假物件斷言委派，證明無 cast。
 */
class ShizukuRemoteProcess(private val remote: Any) : Process() {
    private fun call(name: String, vararg args: Any?): Any? {
        val methods = remote.javaClass.methods.filter { it.name == name && it.parameterCount == args.size }
        val method = methods.firstOrNull()
            ?: throw UnsupportedOperationException("shizuku remote has no $name/${args.size}")
        method.isAccessible = true
        val params = method.parameterTypes
        val coerced = args.mapIndexed { i, a ->
            coerceArg(a, params[i])
        }.toTypedArray()
        return method.invoke(remote, *coerced)
    }

    private fun coerceArg(value: Any?, type: Class<*>): Any? {
        if (value == null) return null
        if (type.isInstance(value)) return value
        if (type == java.lang.Long.TYPE && value is Number) return value.toLong()
        if (type == java.lang.Integer.TYPE && value is Number) return value.toInt()
        return value
    }

    override fun getOutputStream(): OutputStream =
        call("getOutputStream") as OutputStream

    override fun getInputStream(): InputStream =
        call("getInputStream") as InputStream

    override fun getErrorStream(): InputStream =
        call("getErrorStream") as InputStream

    override fun waitFor(): Int =
        (call("waitFor") as Number).toInt()

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
        call("waitFor", timeout, unit) as Boolean

    override fun exitValue(): Int =
        (call("exitValue") as Number).toInt()

    override fun destroy() {
        runCatching { call("destroy") }
    }

    override fun destroyForcibly(): Process {
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
