package dev.librepocket.systemb

/**
 * P2 快通道 B 組七工具（原創，僅委託 / 預填 / 系統接管，不後台直發）。
 *
 * - 郵件：SENDTO + mailto 預填，經系統 App 發出。
 * - 電話：僅 ACTION_DIAL，不直撥（無 ACTION_CALL）。
 * - 簡訊：僅 SENDTO + smsto 預填，不申請任何 SMS 權限。
 * - 通知：NotificationListener 標題優先，預設關（USER_DISABLED）。
 * - 截圖：MediaProjection 系統路徑，每次授權（NEEDS_FRESH_AUTH）。
 * - 聯繫人：僅委託系統選人（PICK），不申請 READ_CONTACTS。
 * - 位置：僅前台，background=true 直接 FLAVOR_BLOCKED。
 */

private fun denied(reason: ReasonCode, note: String) =
  ExecResult(started = false, viaSystemApp = false, intent = null, reason = reason, note = note)

/** 郵件（寫/草稿）：Intent 預填，經系統 App 發出，本包不後台直發。 */
object EmailTool : SystemBTool {
  override val name = "systemb.email.compose"
  override val sideEffect = SideEffectLevel.WRITE

  const val ACTION = "android.intent.action.SENDTO"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    val hasTo = !args["to"].isNullOrBlank()
    val hasContent = !args["subject"].isNullOrBlank() || !args["body"].isNullOrBlank()
    if (!hasTo && !hasContent) {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.MISSING_ARG, "缺少收件人/主題/正文，無法預填郵件")
    }
    return CheckResult(Availability.NATIVE, ReasonCode.OK, "經系統郵件 App 預填發出")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return denied(c.reason, c.message)
    val to = (args["to"] ?: "").trim()
    val extras = buildMap {
      args["subject"]?.takeIf { it.isNotBlank() }?.let { put("subject", it) }
      args["body"]?.takeIf { it.isNotBlank() }?.let { put("body", it) }
      args["cc"]?.takeIf { it.isNotBlank() }?.let { put("cc", it) }
    }
    return ExecResult(
      started = true,
      viaSystemApp = true,
      intent = IntentSpec(action = ACTION, dataUri = "mailto:$to", extras = extras),
      reason = ReasonCode.OK,
      note = "已預填並委託系統郵件 App；是否發送由使用者在系統 App 內決定",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到自動發郵件（$reason）→ 可替代：為你預填草稿 → 需要你做：在系統郵件 App 內確認發送",
    manualSteps = listOf("開啟系統郵件 App", "新建郵件並填寫收件人/主題/正文", "手動點發送"),
  )
}

/** 電話（撥打）：僅 ACTION_DIAL 預填號碼，由使用者按撥出；永不直撥。 */
object DialTool : SystemBTool {
  override val name = "systemb.phone.dial"
  override val sideEffect = SideEffectLevel.PRIVILEGED

  const val ACTION_DIAL = "android.intent.action.DIAL"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    if (args["directCall"] == "true") {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.UNSUPPORTED, "不支援後台直撥，僅 ACTION_DIAL 預填")
    }
    val number = (args["number"] ?: "").filter { it.isDigit() || it == '+' }
    if (number.isBlank()) {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.MISSING_ARG, "缺少有效電話號碼")
    }
    return CheckResult(Availability.DEGRADED, ReasonCode.OK, "僅預填號碼，需使用者手動撥出")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return denied(c.reason, c.message)
    val number = (args["number"] ?: "").filter { it.isDigit() || it == '+' }
    return ExecResult(
      started = true,
      viaSystemApp = true,
      intent = IntentSpec(action = ACTION_DIAL, dataUri = "tel:$number"),
      reason = ReasonCode.OK,
      note = "已跳轉系統撥號盤預填；本 App 未撥出",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到自動撥號（$reason）→ 可替代：為你打開撥號盤預填號碼 → 需要你做：手動按撥出",
    manualSteps = listOf("開啟系統電話 App", "輸入號碼 ${args["number"] ?: ""}", "手動按撥出"),
  )
}

/** 簡訊（發送）：跳系統簡訊編輯器預填，不自動發送；不申請 SMS 權限。 */
object SmsTool : SystemBTool {
  override val name = "systemb.sms.prefill"
  override val sideEffect = SideEffectLevel.PRIVILEGED

  const val ACTION = "android.intent.action.SENDTO"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    if ((args["number"] ?: "").isBlank()) {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.MISSING_ARG, "缺少收件號碼")
    }
    // 降級使用：系統編輯器預填，正文可空（使用者現場補寫）。
    return CheckResult(Availability.DEGRADED, ReasonCode.OK, "僅預填系統簡訊編輯器，不自動發送")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return denied(c.reason, c.message)
    // 合規：此處故意不觸碰 permissionSink；任何 SMS 權限申請即視為違規。
    val number = args["number"]!!.trim()
    val extras = buildMap {
      args["body"]?.takeIf { it.isNotBlank() }?.let { put("sms_body", it) }
    }
    return ExecResult(
      started = true,
      viaSystemApp = true,
      intent = IntentSpec(action = ACTION, dataUri = "smsto:$number", extras = extras),
      reason = ReasonCode.OK,
      note = "已預填系統簡訊編輯器；本 App 未發送",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到自動發簡訊（$reason）→ 可替代：為你預填系統簡訊編輯器 → 需要你做：在系統 App 內確認發送",
    manualSteps = listOf("開啟系統簡訊 App", "選擇收件人並填寫正文", "手動點發送"),
  )
}

/** 通知（查詢/朗讀）：NotificationListener 標題優先，預設關，最小化。 */
object NotificationTool : SystemBTool {
  override val name = "systemb.notification.titles"
  override val sideEffect = SideEffectLevel.READ

  /** 邊緣層標記：真實讀取走 NotificationListenerService，本包只產出請求描述。 */
  const val ACTION = "dev.librepocket.systemb.NOTIFICATION_TITLE_READ"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    if (!env.notificationListenerEnabled) {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.USER_DISABLED, "通知監聽預設關閉，需使用者授權後可用")
    }
    return CheckResult(Availability.DEGRADED, ReasonCode.OK, "僅讀取通知標題；全文需二次同意")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return denied(c.reason, c.message)
    val includeBody = args["fullText"] == "true" && env.notificationFullTextConsented
    return ExecResult(
      started = true,
      viaSystemApp = false,
      intent = IntentSpec(
        action = ACTION,
        extras = mapOf("mode" to if (includeBody) "title_and_body" else "title_only"),
      ),
      reason = ReasonCode.OK,
      note = if (includeBody) "已二次同意，全文讀取" else "最小化：僅標題",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到讀取通知（$reason）→ 可替代：下拉通知欄手動查看 → 需要你做：在設定中開啟通知監聽授權（如需此功能）",
    manualSteps = listOf("開啟系統設定 → 通知 → 通知監聽", "允許 LibrePocket 讀取通知", "重新下達查詢指令"),
  )
}

/** 截圖（擷取/分享）：MediaProjection 系統路徑，每次授權，無常駐授權。 */
object ScreenshotTool : SystemBTool {
  override val name = "systemb.screenshot.capture"
  override val sideEffect = SideEffectLevel.PRIVILEGED

  /** 邊緣層標記：真實擷取走 MediaProjection + 前台服務類型聲明。 */
  const val ACTION = "dev.librepocket.systemb.SCREENSHOT_REQUEST"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    if (!env.screenshotFreshGrant) {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.NEEDS_FRESH_AUTH, "截圖需每次授權，尚未取得本次授權")
    }
    return CheckResult(Availability.DEGRADED, ReasonCode.OK, "經系統截圖/MediaProjection 單次授權路徑")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return denied(c.reason, c.message)
    return ExecResult(
      started = true,
      viaSystemApp = true,
      intent = IntentSpec(action = ACTION, extras = mapOf("singleShot" to "true")),
      reason = ReasonCode.OK,
      note = "單次授權僅本次有效；下次截圖需重新授權",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到直接截圖（$reason）→ 可替代：使用系統截圖手勢/按鍵 → 需要你做：在彈出的錄屏授權頁點允許（僅本次有效）",
    manualSteps = listOf("使用電源鍵+音量下觸發系統截圖", "或在授權彈窗中允許本次錄屏", "從相簿/分享面板取圖"),
  )
}

/** 聯繫人（選人/查號）：僅委託系統選人（免權限，臨時授權）優先。 */
object ContactTool : SystemBTool {
  override val name = "systemb.contact.pick"
  override val sideEffect = SideEffectLevel.READ

  const val ACTION = "android.intent.action.PICK"
  const val CONTACTS_URI = "content://com.android.contacts/contacts"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult =
    CheckResult(Availability.NATIVE, ReasonCode.OK, "委託系統選人，免權限")

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    // 合規：委託選人路徑不得申請 READ_CONTACTS，故不觸碰 permissionSink。
    return ExecResult(
      started = true,
      viaSystemApp = true,
      intent = IntentSpec(action = ACTION, dataUri = CONTACTS_URI),
      reason = ReasonCode.OK,
      note = "委託系統聯繫人選人器；僅獲取使用者選中的單條臨時授權",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到自動查號（$reason）→ 可替代：打開系統選人器手動挑選 → 需要你做：在選人器中點選聯繫人",
    manualSteps = listOf("開啟系統聯繫人 App", "手動找到並點選聯繫人", "將號碼複製回對話"),
  )
}

/** 位置（定位/導航輔助）：僅前台（使用期間）；後台不上 Play。 */
object LocationTool : SystemBTool {
  override val name = "systemb.location.foreground"
  override val sideEffect = SideEffectLevel.READ

  /** 邊緣層標記：真實定位用前台 fused provider，本包只產出請求描述。 */
  const val ACTION = "dev.librepocket.systemb.FOREGROUND_LOCATION"

  override fun check(env: SystemBEnv, args: Map<String, String>): CheckResult {
    if (args["background"] == "true") {
      return CheckResult(Availability.UNAVAILABLE, ReasonCode.FLAVOR_BLOCKED, "後台位置不上 Play，已拒絕")
    }
    return CheckResult(Availability.NATIVE, ReasonCode.OK, "僅前台單次定位")
  }

  override fun execute(env: SystemBEnv, args: Map<String, String>): ExecResult {
    val c = check(env, args)
    if (c.availability == Availability.UNAVAILABLE) return denied(c.reason, c.message)
    // 合規：僅前台，不申請 ACCESS_BACKGROUND_LOCATION，不觸碰 permissionSink。
    return ExecResult(
      started = true,
      viaSystemApp = false,
      intent = IntentSpec(action = ACTION, extras = mapOf("mode" to "foreground_single")),
      reason = ReasonCode.OK,
      note = "僅使用期間定位；離開前台即停止",
    )
  }

  override fun fallback(reason: ReasonCode, args: Map<String, String>): Fallback = Fallback(
    reason = reason,
    userMessage = "做不到獲取位置（$reason）→ 可替代：手動分享定位或輸入地名 → 需要你做：在地圖 App 中搜索目的地",
    manualSteps = listOf("開啟系統地圖 App", "手動搜索目的地", "將定位/路線分享回對話"),
  )
}
