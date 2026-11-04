package dev.librepocket.privilege.github

/**
 * Runner grant 明示 allow fixture（S1，owner P1-1 security）。
 *
 * 兩 runner 的 `grantCheck` 無預設值（mandatory dependency）後，測試必須明示供給；
 * 本 fixture 只用於「非 grant 主題」測試的行為不變接線（allow），grant 語義本身
 * 由 `ElevatedRunnerGrantTest` 的 Invalid/翻轉/全鏈路用例覆蓋，不得在此放寬斷言。
 */
object GrantFixtures {
    val allowAlways: () -> GrantCheck = { GrantCheck.Valid }

    fun invalid(reason: String): () -> GrantCheck = { GrantCheck.Invalid(reason) }
}
