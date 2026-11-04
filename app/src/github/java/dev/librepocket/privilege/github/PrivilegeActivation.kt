package dev.librepocket.privilege.github

/**
 * 提權 runner 上線閘（S1，owner P1-1 security）。
 *
 * B1 授權生命週期未完（Store/UX/revoke 統一歸 B3），產品不得接線 runner。
 * B3 將 [ELEVATED_RUNNERS_READY] 翻為 `true` 的前提：
 * grant 真實接線（`{ store.check(freshProviderId, freshRevision) }`，每次新鮮供給）＋
 * 子孫清理裝置驗收（E2E）。
 */
object PrivilegeActivation {
    const val ELEVATED_RUNNERS_READY = false
}
