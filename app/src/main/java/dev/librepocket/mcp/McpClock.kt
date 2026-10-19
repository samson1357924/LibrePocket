package dev.librepocket.mcp

/**
 * C-08 預設時鐘：**單調**毫秒來源（`System.nanoTime()` 基準）。
 *
 * - `nanoTime()` 起點任意（不從 epoch 起），但**差值**單調、單位 ns——本包兩處
 *   用法只取差值（[McpServer] 的 `latencyMs`＝end−start、[McpCircuitBreaker] 的
 *   `now >= openUntilMs`／`now + openMs`），起點任意不影響正確性；回傳單位維持**毫秒**。
 * - 牆鐘 `System.currentTimeMillis()` 受 NTP 影響：回撥讓 latency 出現負值直進
 *   稽核（[McpCallResult.auditMap] 的 `latencyMs`），前跳/回撥同時把熔斷開窗
 *   縮短/拉長。預設改本函即治本；[McpServer] 另以 `elapsedMs` 對**注入**的
 *   非單調假時鐘 `coerceAtLeast(0)`（防禦深度，見該函 KDoc）。
 * - 單調性來源：JVM/Android 的 `nanoTime` 取自開機基準（CLOCK_MONOTONIC 類來源），
 *   故值遠小於 epoch 毫秒——`defaultMonotonicClockIsUptimeBasedNotEpoch` 釘此量級
 *   （`currentTimeMillis() - clock > 1e6`；若有人改回 `System::currentTimeMillis`，該測即紅）。
 */
internal fun monotonicClockMs(): Long = System.nanoTime() / 1_000_000L
