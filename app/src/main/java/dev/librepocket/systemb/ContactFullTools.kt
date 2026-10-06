package dev.librepocket.systemb

import dev.librepocket.tool.Flavor

/**
 * S1-C 全量聯繫人三工具（READ，foss/github 限定、預設關）。
 *
 * - 矩陣 §1「聯繫人」行：委託系統選人（[ContactTool]，免權限）優先；
 *   全量（關鍵字搜尋 / 列表 / 按 id 取）另審，play 不上（[ReasonCode.FLAVOR_BLOCKED]），
 *   foss/github 需 READ_CONTACTS 已授權（[SystemBEnv.contactsReadGranted]，
 *   缺授權即 [ReasonCode.NO_PRIVILEGE]），另有 ToolRegistry 開關
 *   `contacts_full`（預設 false）做投影門。
 * - 本包為純 Kotlin，不依賴 android.* 類：真實查詢走 Contacts Provider，
 *   本層只產出請求描述（[IntentSpec] 攜 action + extras），呼叫方在邊緣層
 *   翻譯為真正的 Provider 查詢；拒絕時 started=false + intent=null
 *   （拒絕零請求），且永不觸碰 [SystemBEnv.permissionSink]。
 * - Play 合規：本檔案不含任何黑名單權限字面（RECORD_AUDIO 留 S2，
 *   無障礙留 S3）；READ_CONTACTS 字面僅出現在 ToolRegistry 投影註解，
 *   此處只讀布林快照。
 */

private fun fullDenied(reason: ReasonCode, note: String) =
  ExecResult(started = false, viaSystemApp = false, intent = null, reason = reason, note = note)

/** 全量門禁共用：play 擋風味 → 查授權 → 查參數（各工具自訂）。 */
private fun fullGate(env: SystemBEnv): CheckResult? {
  if (env.flavor == Flavor.PLAY) {
    return CheckResult(Availability.UNAVAILABLE, ReasonCode.FLAVOR_BLOCKED, "全量聯繫人不上 Play，僅委託選人")
  }
  if (!env.contactsReadGranted) {
    return CheckResult(Availability.UNAVAILABLE, ReasonCode.NO_PRIVILEGE, "需聯繫人讀取授權")
  }
  return null
}

/** 聯繫人關鍵字搜尋（全量）：需 query 非空。 */
object ContactSearchTool : SystemBTool {
  override val name = "systemb.contact.search"
  override val sideEffect = SideEffectLevel.READ

  /** 邊緣層標記：真實查詢走 Contacts Provider，本包只產出請求描述。 */
  const val ACTION = "dev.librepocket.systemb.CONTACT_SEARCH"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    fullGate(env)?.let { return it }
    if (args["query"].isNullOrBlank()) {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.MISSING_ARG, "缺少搜尋關鍵字")
    }
    return CheckResult(Availability.NATIVE, ReasonCode.OK, "全量關鍵字搜尋可用")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return fullDenied(c.reason, c.message)
    return ExecResult(
      started = true,
      viaSystemApp = false,
      intent = IntentSpec(
        action = ACTION,
        extras = buildMap {
          put("query", args["query"]!!.trim())
          args["limit"]?.takeIf { it.isNotBlank() }?.let { put("limit", it.trim()) }
        },
      ),
      reason = ReasonCode.OK,
      note = "全量搜尋請求已描述；端側經 Provider 執行",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到搜尋聯繫人（$reason）→ 可替代：委託系統選人器手動挑選 → 需要你做：在選人器中點選聯繫人",
    manualSteps = listOf("開啟系統聯繫人 App", "手動搜尋並點選聯繫人", "將號碼複製回對話"),
  )
}

/** 聯繫人列表（全量）：無參數即可列（limit 可選）。 */
object ContactListTool : SystemBTool {
  override val name = "systemb.contact.list"
  override val sideEffect = SideEffectLevel.READ

  const val ACTION = "dev.librepocket.systemb.CONTACT_LIST"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    fullGate(env)?.let { return it }
    return CheckResult(Availability.NATIVE, ReasonCode.OK, "全量列表可用")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return fullDenied(c.reason, c.message)
    return ExecResult(
      started = true,
      viaSystemApp = false,
      intent = IntentSpec(
        action = ACTION,
        extras = buildMap {
          args["limit"]?.takeIf { it.isNotBlank() }?.let { put("limit", it.trim()) }
        },
      ),
      reason = ReasonCode.OK,
      note = "全量列表請求已描述；端側經 Provider 執行",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到列出聯繫人（$reason）→ 可替代：委託系統選人器手動挑選 → 需要你做：在選人器中點選聯繫人",
    manualSteps = listOf("開啟系統聯繫人 App", "手動找到並點選聯繫人", "將號碼複製回對話"),
  )
}

/** 按 id 取單條聯繫人（全量）：需 contactId 非空。 */
object ContactGetTool : SystemBTool {
  override val name = "systemb.contact.get"
  override val sideEffect = SideEffectLevel.READ

  const val ACTION = "dev.librepocket.systemb.CONTACT_GET"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    fullGate(env)?.let { return it }
    if (args["contactId"].isNullOrBlank()) {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.MISSING_ARG, "缺少聯繫人 id")
    }
    return CheckResult(Availability.NATIVE, ReasonCode.OK, "按 id 取單條可用")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return fullDenied(c.reason, c.message)
    return ExecResult(
      started = true,
      viaSystemApp = false,
      intent = IntentSpec(
        action = ACTION,
        extras = mapOf("contactId" to args["contactId"]!!.trim()),
      ),
      reason = ReasonCode.OK,
      note = "按 id 取單條請求已描述；端側經 Provider 執行",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到讀取聯繫人（$reason）→ 可替代：委託系統選人器手動挑選 → 需要你做：在選人器中點選聯繫人",
    manualSteps = listOf("開啟系統聯繫人 App", "手動找到並點選聯繫人", "將號碼複製回對話"),
  )
}
