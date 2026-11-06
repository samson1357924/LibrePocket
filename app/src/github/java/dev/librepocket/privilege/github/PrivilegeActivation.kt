package dev.librepocket.privilege.github

/**
 * 提權 runner 上線閘（S1，owner P1-1 security）。
 *
 * #136-B1 授權生命週期未完（Store/UX/revoke 統一歸 #136-B3），產品不得接線 runner。
 *
 * 註：此處 #136-B1／#136-B3 指 Issue #136 階段（B1 授權持久化／B3 elevated 接線＋E2E），
 * 非 BACKLOG（BACKLOG B3 為 fast-channel 工具項，見 ToolRegistry）。
 *
 * #136-B3 將 [ELEVATED_RUNNERS_READY] 翻為 `true` 的前提：
 * grant 真實接線（`{ store.check(freshProviderId, freshRevision) }`，每次新鮮供給）＋
 * 子孫清理裝置驗收（E2E）。
 *
 * U1（owner 新 P1-1＋P1-2）：本旗已被兩 runner（[RootSuRunner]／[ShizukuShellRunner]）
 * 經 `activationCheck` 預設消費（default-deny：`false` 即零 spawn 拒絕）；
 * #136-B3 產品接線時傳明示 allow（`{ ELEVATED_RUNNERS_READY }` 仍須每次重驗，不得快取）。
 *
 * AA1（owner 新 P1＋P2）：[ElevatedCleanupTracker.isSaturated]（未完成清理達上限，
 * 有不可回收 Binder stall 殘留）即 #136-B3 blocker 條件——兩 runner 入口資源門已
 * fail-closed 拒絕新 spawn，#136-B3 須先解清理缺口（裝置驗收＋殘留回收）再放行，
 * 不得僅調大上限繞行。
 */
object PrivilegeActivation {
    const val ELEVATED_RUNNERS_READY = false
}
