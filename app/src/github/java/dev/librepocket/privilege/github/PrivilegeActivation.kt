package dev.librepocket.privilege.github

/**
 * 提權 runner 上線閘（S1，owner P1-1 security）。
 *
 * B1 授權生命週期未完（Store/UX/revoke 統一歸 B3），產品不得接線 runner。
 * B3 將 [ELEVATED_RUNNERS_READY] 翻為 `true` 的前提：
 * grant 真實接線（`{ store.check(freshProviderId, freshRevision) }`，每次新鮮供給）＋
 * 子孫清理裝置驗收（E2E）。
 *
 * U1（owner 新 P1-1＋P1-2）：本旗已被兩 runner（[RootSuRunner]／[ShizukuShellRunner]）
 * 經 `activationCheck` 預設消費（default-deny：`false` 即零 spawn 拒絕）；
 * B3 產品接線時傳明示 allow（`{ ELEVATED_RUNNERS_READY }` 仍須每次重驗，不得快取）。
 */
object PrivilegeActivation {
    const val ELEVATED_RUNNERS_READY = false
}
