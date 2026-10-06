package dev.librepocket.systemb

/**
 * Play 合規審計常量（僅測試源集）。
 *
 * B8 要求 play dex 字串掃描零匹配，為避免 `android.permission.*` 字面被編入
 * main dex（三風味共用），拒絕清單只保留在測試源集。此處邏輯與原 main 常量一致，
 * 供 [SystemBMappingTest] 斷言「拒絕零請求」使用；生產碼永不申請此處列出的權限，
 * 且生產碼本身不引用本對象（見 main/SystemBModels.kt 註解）。
 */
object PlayCompliance {
  const val SEND_SMS = "android.permission.SEND_SMS"
  const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
  const val READ_SMS = "android.permission.READ_SMS"
  const val ACCESS_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
  const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"

  val FORBIDDEN_PERMISSIONS: Set<String> = setOf(
    SEND_SMS,
    RECEIVE_SMS,
    READ_SMS,
    ACCESS_BACKGROUND_LOCATION,
    MANAGE_EXTERNAL_STORAGE,
  )
}
