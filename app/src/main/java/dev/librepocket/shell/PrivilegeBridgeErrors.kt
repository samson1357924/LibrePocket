package dev.librepocket.shell

/**
 * #136-B1 提權橋錯誤碼常量（typed error codes）。
 *
 * 值與既有字串一致（tests／docs 以字串釘住，不得改值）；PrivilegeBridgeState 的
 * IllegalStateException 錯誤碼與 grant() 的 AT_MS_IN_FUTURE 一律經此引用，
 * 避免字串漂移（Policy 理由碼與 require() 校驗碼維持原定義點，刻意不集中）。github 源集專用碼（REQUEST_CODE_SPACE_EXHAUSTED）留在該源集
 * 內（見 ShizukuPermissionRequest.kt 的 ShizukuRequestErrors）——兩個命名空間
 * 各自按源集歸位，不跨源集耦合。
 */
internal object PrivilegeBridgeErrors {
    /** opMutex 等待逾時（[PrivilegeBridgeState.revoke]）。 */
    const val REVOKE_TIMEOUT = "PRIVILEGE_REVOKE_TIMEOUT"

    /** revoke 執行中（入口已封存 seal）時呼叫 grant()：fail-fast。 */
    const val REVOKE_IN_PROGRESS = "PRIVILEGE_REVOKE_IN_PROGRESS"

    /** 臨界區內 store.clear() 逾時。 */
    const val REVOKE_STORE_TIMEOUT = "PRIVILEGE_REVOKE_STORE_TIMEOUT"

    /** 鎖外 withdrawScope 逾時。 */
    const val REVOKE_SCOPE_TIMEOUT = "PRIVILEGE_REVOKE_SCOPE_TIMEOUT"

    /** 鎖外 best-effort clear 逾時（以 suppressed ISE 附加）。 */
    const val REVOKE_BEST_EFFORT_CLEAR_TIMEOUT = "PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_TIMEOUT"

    /** 鎖外 best-effort clear 失敗（以 suppressed ISE 附加，cause 為原例外）。 */
    const val REVOKE_BEST_EFFORT_CLEAR_FAILED = "PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED"

    /** revoke-wins：簽發／重估期間偵測到撤銷發生。 */
    const val GRANT_REVOKED_CONCURRENTLY = "PRIVILEGE_GRANT_REVOKED_CONCURRENTLY"

    /** 呼叫端傳入未來 atMs（fail-fast）。 */
    const val AT_MS_IN_FUTURE = "AT_MS_IN_FUTURE"
}
