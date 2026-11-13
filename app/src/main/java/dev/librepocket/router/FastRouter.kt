package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast lane: deterministic intent-template routing (ARCHITECTURE §9.1/§9.3).
 *
 * Rule engine first: an intent matching a known template goes FAST with a
 * schema-checked [ToolCall]; a match whose projection is UNAVAILABLE is
 * denied with the projection reason code; anything else is DENIED as
 * no-match with manual guidance. Every outcome carries a [TranscriptHeader]
 * with the decision, the projection snapshot and the model-visible snapshot.
 *
 * Stage1 (P2 safe slice, #19 前置): guessing never executes. Missing
 * alarm time (never defaulted: [missingQuestionFor] intercepts first, so
 * [alarmArgs]' 7:00 is only a dead template-direct fallback), missing phone
 * number (empty string), empty-field templates (`sms.compose` to /
 * `calendar.create` start-end) and negated intents all become DENIED +
 * NEED_CLARIFICATION with a 補參 question (audit-distinct from
 * USER_DISABLED). No new execution path: FAST still requires projected AND
 * `executionReady`.
 *
 * Wiring gate (PR#1 re-review scope-down): a match whose tool is projected
 * but NOT `executionReady` is denied as USER_DISABLED (capability not
 * enabled for this round; the wiring PR flips tools to ready one by one).
 * FAST therefore means *routable AND executable*, never projection-only.
 * Confirmation is NOT checked here; [PrivilegeGate] owns the PRIVILEGED
 * execution gate.
 *
 * B3 (#19) expansion: `lsp.symbols` (the single `executionReady` tool,
 * READ-only stub echo) gains a template so the fast lane is reachable
 * end-to-end in JVM tests: a symbol-naming intent routes FAST, and
 * [FastDispatcher] executes it against an injected [LspTools.Backend] fake.
 * This router still only decides, never executes. Expansion rule
 * (fail-safe ordering, pinned by `FastDispatchTest` + [templateToolNames]):
 * a new tool must gain its RULES template AND its NEGATION verbs BEFORE its
 * `executionReady` flips — a RULES gap is fail-safe (no template ⇒ DENIED
 * no-match, never executes) but a NEGATION gap is not (a complete-args
 * negated intent would sail through to FAST). `lsp.symbols` is the
 * reference expansion (template + `別查`/`别查` + blank-query clarification).
 *
 * Pure logic, no Android dependencies.
 *
 * Note: the [TranscriptHeader.visibleTools] snapshot carried by every outcome
 * is a registry capability snapshot, not a per-ChatRequest wire guarantee —
 * production stays records-only with no slice attached, the continuation path
 * carries `allowTools=false`, and when the slice/context is unavailable the
 * wire tool list may be empty. Audits must not treat the header as proof of
 * what was actually sent on the wire.
 */
class FastRouter(
    private val registry: ToolRegistry = ToolRegistry,
) {
    private val callIds = AtomicLong(0)

    fun decide(
        intentText: String,
        ctx: ProjectionContext,
        sessionId: String? = null,
        turnIndex: Int? = null,
    ): RouteDecision {
        val projections = registry.projectAll(ctx)
        // Canonical snapshots (PR#1 scope-down): projection for audit,
        // model-visible list for "what the model saw". Never self-built.
        val projected = registry.projectedTools(ctx).map { it.name }
        val modelVisible = registry.visibleTools(ctx).map { it.name }
        fun header(route: RouteKind, toolName: String?, reason: dev.librepocket.tool.DenyReason?) =
            TranscriptHeader(
                sessionId = sessionId,
                turnIndex = turnIndex,
                route = route,
                toolName = toolName,
                reasonCode = reason?.name,
                visibleTools = modelVisible,
                projectedTools = projected,
            )

        val match = matchTemplate(intentText.trim())
            ?: return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = null,
                fallbackMessage = FallbackMessages.forNoMatch(intentText),
                header = header(RouteKind.DENIED, null, null),
            )

        val tool = registry.find(match.toolName)
            ?: return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = null,
                fallbackMessage = FallbackMessages.forNoMatch(intentText),
                header = header(RouteKind.DENIED, match.toolName, null),
            )
        val projection = projections.getValue(tool.name)
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            val reason = projection.reason ?: dev.librepocket.tool.DenyReason.USER_DISABLED
            return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = reason,
                fallbackMessage = FallbackMessages.forTool(tool.name, reason, tool.annotations.fallbackHint),
                header = header(RouteKind.DENIED, tool.name, reason),
            )
        }
        // Stage1 guess-rejection (no new execution path): negated or
        // under-specified intents become NEED_CLARIFICATION (audit-distinct
        // from USER_DISABLED wiring/switch-off) with a 補參 question.
        // Projection UNAVAILABLE stays first so FLAVOR/NO_PRIVILEGE mask
        // clarification; wiring USER_DISABLED stays after so missing args
        // are never masked as "not wired".
        val trimmed = intentText.trim()
        if (isNegated(trimmed)) {
            val reason = dev.librepocket.tool.DenyReason.NEED_CLARIFICATION
            val question = "看起來你說不要執行「${trimmed.take(24)}」，請確認是否要執行？若要執行請補充細節"
            return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = reason,
                fallbackMessage = FallbackMessages.forClarification(
                    tool.name, question, tool.annotations.fallbackHint,
                ),
                header = header(RouteKind.DENIED, tool.name, reason),
            )
        }
        val missingQuestion = missingQuestionFor(tool.name, trimmed)
        if (missingQuestion != null) {
            val reason = dev.librepocket.tool.DenyReason.NEED_CLARIFICATION
            return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = reason,
                fallbackMessage = FallbackMessages.forClarification(
                    tool.name, missingQuestion, tool.annotations.fallbackHint,
                ),
                header = header(RouteKind.DENIED, tool.name, reason),
            )
        }
        // Wiring gate: projected but not executionReady ⇒ not enabled this
        // round (USER_DISABLED + manual alternative; the wiring PR restores
        // FAST per tool as flags flip with dispatcher/executor/E2E).
        if (!tool.executionReady) {
            val reason = dev.librepocket.tool.DenyReason.USER_DISABLED
            return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = reason,
                fallbackMessage = FallbackMessages.forTool(tool.name, reason, tool.annotations.fallbackHint),
                header = header(RouteKind.DENIED, tool.name, reason),
            )
        }
        val call = ToolCall(
            toolName = tool.name,
            argumentsJson = match.argumentsJson,
            callId = "fast-${callIds.incrementAndGet()}",
        )
        return RouteDecision(
            route = RouteKind.FAST,
            toolCall = call,
            reasonCode = null,
            fallbackMessage = null,
            header = header(RouteKind.FAST, tool.name, null),
        )
    }

    private data class TemplateMatch(val toolName: String, val argumentsJson: String)

    private fun matchTemplate(text: String): TemplateMatch? {
        if (text.isEmpty()) return null
        // Slow-lane intents resolve to the automation descriptor so projection
        // can deny them honestly (FLAVOR_BLOCKED on play) instead of NO_MATCH.
        if (containsAny(text, SLOW_HINTS)) {
            return TemplateMatch(ToolRegistry.SLOW_TOOL_NAME, jsonText(text))
        }
        for ((toolName, hints, args) in RULES) {
            if (containsAny(text, hints)) return TemplateMatch(toolName, args(text))
        }
        return null
    }

    companion object {
        private fun r(pattern: String): Regex = Regex(pattern, RegexOption.IGNORE_CASE)

        // Template hints, precompiled once per process: these lists were
        // per-instance vals (recompiling 100+ patterns per FastRouter()),
        // but decide() is a hot path — hoisted with identical patterns.
        private val SCREENSHOT_HINTS = listOf(r("截圖"), r("截图"), r("擷取"), r("screenshot"), r("螢幕.*存"), r("屏幕.*存"))
        private val NOTIFICATION_HINTS = listOf(r("通知"), r("notification"), r("未讀"), r("未读"), r("訊息中心"))
        private val ALARM_HINTS = listOf(r("鬧鐘"), r("闹钟"), r("alarm"), r("定時.*響"), r("定时.*响"), r("提醒.*點"), r("提醒.*点"))
        private val PHONE_HINTS = listOf(r("打電話"), r("打电话"), r("撥打"), r("拨打"), r("撥號"), r("拨号"), r("打給"), r("打给"), r("致電"), r("致电"), r("\\bcall\\b"), r("tel:"))
        private val SMS_HINTS = listOf(r("簡訊"), r("简讯"), r("短信"), r("短訊"), r("短讯"), r("\\bsms\\b"), r("傳訊息給"), r("发消息"))
        private val MAIL_HINTS = listOf(r("郵件"), r("邮件"), r("e-?mail"), r("寫信"), r("写信"), r("寄信"), r("發郵件"), r("发邮件"))
        private val CALENDAR_HINTS = listOf(r("日曆"), r("日历"), r("calendar"), r("行程"), r("會議"), r("会议"), r("約會"), r("约会"), r("安排.*(會|事)"))
        private val MUSIC_HINTS = listOf(r("音樂"), r("音乐"), r("播放.*歌"), r("放.*歌"), r("切歌"), r("下一首"), r("上一首"), r("暫停.*音樂"), r("暂停.*音乐"), r("\\bmusic\\b"))
        private val VOLUME_HINTS = listOf(r("音量"), r("靜音"), r("静音"), r("大聲"), r("大声"), r("小聲"), r("小声"), r("調.*音"), r("调.*音"), r("volume"), r("mute"))
        private val NAVIGATE_HINTS = listOf(r("導航"), r("导航"), r("路線"), r("路线"), r("帶我去"), r("带我去"), r("怎麼去"), r("怎么去"), r("去.*(路|街|店|餐廳|餐厅|公司|家|站|場|场|店)"), r("\\bmap\\b"))
        private val OPEN_APP_HINTS = listOf(r("打開"), r("打开"), r("開啟"), r("开启"), r("啟動"), r("启动"), r("開.*app"), r("开.*app"))
        // B3 (#19) reference expansion: the single `executionReady` tool
        // (`lsp.symbols`, READ-only) becomes fast-lane routable. Hints stay
        // narrow (symbol words only — a bare 「查」 without a symbol word is
        // NOT a symbol query and stays no-match, fail-safe).
        private val SYMBOL_HINTS = listOf(r("符號"), r("symbol"))
        private val SLOW_HINTS = listOf(
            r("自動點擊"), r("自动点击"), r("點擊螢幕"), r("点击屏幕"), r("幫我點"), r("帮我点"),
            r("自動填表"), r("自动填表"), r("代操"), r("自動操作"), r("自动操作"),
        )

        /**
         * Stage1 否定句門：裸「別/别」不用（避「特別/别人/特別/別人」誤判），只收
         * 「別/别+動詞」（繁簡對稱）與明確否定詞；命中且同時命中工具模板即轉澄清，
         * 絕不執行。
         *
         * 擴表規則（B3 fail-safe ordering）：封閉動詞表——新工具上線為執行 ready
         * 前必須先擴充此表（並在 RULES 增模板，見 [templateToolNames]），否則該
         * 工具參數齊備時的否定句會一路走到 FAST 執行（非 fail-safe；今日僅因
         * 各工具多為非 ready 才看似安全）。`lsp.symbols` 是參照擴充（`別查`／
         * `别查`，與模板＋缺參門同 PR 落地，先於其既有 ready 旗標——該旗標早於
         * 模板存在，但無模板時路由不可達，仍屬 fail-safe）。通用否定詞（不要／
         * 不用／…）覆蓋所有模板工具，見 `FastDispatchTest`；裸「別+動詞」須逐
         * 工具增補（繁簡對稱），殘餘缺口（未收錄動詞的「別…」句式）在 ready 翻
         * 轉前必須補齊。
         * 已知侷限：複合句（「取消舊鬧鐘再設一個新的」同時命中模板與取消/停止詞）
         * 會被轉澄清，屬誤澄清（安全方向），由測試釘住。
         */
        private val NEGATION_HINTS = listOf(
            r("不要"), r("不用"), r("不需要"), r("不必"), r("不需"), r("勿"),
            r("取消"), r("停止"), r("禁止"), r("算了"),
            r("別打"), r("別設"), r("別開"), r("別截"), r("別播"),
            r("別調"), r("別導"), r("別發"), r("別傳"), r("別幫"), r("別給"),
            r("別查"),
            r("别打"), r("别设"), r("别开"), r("别截"), r("别播"),
            r("别调"), r("别导"), r("别发"), r("别传"), r("别帮"), r("别给"),
            r("别查"),
            r("\\bdon'?t\\b"), r("\\bdo not\\b"), r("\\bstop\\b"), r("\\bcancel\\b"),
        )

        // Shared argument patterns (also hoisted: previously recompiled per
        // call inside hasExplicitAlarmTime/hasPhoneNumber/phoneArgs and
        // music/volume/alarm arg builders).
        private val ALARM_TIME = Regex("(\\d{1,2})[:點点](\\d{1,2})?")
        private val PHONE_CANDIDATE = Regex("[+\\d][\\d\\-\\.\\s]{5,16}")
        private val MUSIC_NEXT = Regex("下一首|切歌|next", RegexOption.IGNORE_CASE)
        private val MUSIC_PREV = Regex("上一首|previous", RegexOption.IGNORE_CASE)
        private val MUSIC_PAUSE = Regex("暫停|暂停|pause", RegexOption.IGNORE_CASE)
        private val VOLUME_MUTE = Regex("靜音|静音|mute", RegexOption.IGNORE_CASE)
        private val VOLUME_LEVEL = Regex("(\\d{1,3})")

        // FastRouter template rules + arg builders, hoisted to the
        // companion with the hint regexes (Stage C completion): the
        // 12-entry rule list and the builder closures stop allocating
        // per FastRouter(); decide() is a hot path. The shared JSON/esc
        // helpers and the phone finder live here too so the RULES lambdas
        // never capture the instance.
        //
        // Expansion rule (B3): a new tool lands here AND in NEGATION_HINTS
        // BEFORE its `executionReady` flips (see [templateToolNames]).
        private val RULES: List<Triple<String, List<Regex>, (String) -> String>> = listOf(
            Triple(
                "screenshot.capture",
                SCREENSHOT_HINTS,
                { t -> "{\"share\": false, \"text\": \"${esc(t)}\"}" },
            ),
            Triple(
                "notification.read",
                NOTIFICATION_HINTS,
                { t -> "{\"limit\": 10, \"text\": \"${esc(t)}\"}" },
            ),
            Triple(
                "alarm.create",
                ALARM_HINTS,
                { t -> alarmArgs(t) },
            ),
            Triple(
                "phone.dial",
                PHONE_HINTS,
                { t -> phoneArgs(t) },
            ),
            Triple(
                "sms.compose",
                SMS_HINTS,
                { t -> "{\"to\": \"\", \"body\": \"${esc(t)}\", \"raw\": \"${esc(t)}\"}" },
            ),
            Triple(
                "mail.compose",
                MAIL_HINTS,
                { t -> "{\"to\": \"\", \"subject\": \"\", \"body\": \"${esc(t)}\"}" },
            ),
            Triple(
                "calendar.create",
                CALENDAR_HINTS,
                { t -> "{\"title\": \"${esc(t)}\", \"start\": \"\", \"end\": \"\"}" },
            ),
            Triple(
                "music.control",
                MUSIC_HINTS,
                { t -> "{\"action\": \"${musicAction(t)}\", \"text\": \"${esc(t)}\"}" },
            ),
            Triple(
                "volume.set",
                VOLUME_HINTS,
                { t -> volumeArgs(t) },
            ),
            Triple(
                "navigate",
                NAVIGATE_HINTS,
                { t -> "{\"destination\": \"${esc(t)}\", \"mode\": \"driving\"}" },
            ),
            Triple(
                "open_app",
                OPEN_APP_HINTS,
                { t -> "{\"appName\": \"${esc(t)}\", \"packageName\": \"\"}" },
            ),
            // B3 reference expansion (`lsp.symbols`, the single ready tool):
            // symbol-naming intents route FAST only when projected AND ready;
            // the query is the hint-stripped remainder (blank ⇒ clarification
            // in [missingQuestionFor], never an empty-query execution).
            Triple(
                LspTools.SYMBOLS_NAME,
                SYMBOL_HINTS,
                { t -> symbolArgs(t) },
            ),
        )

        /**
         * Fast-lane template coverage: every tool name with a RULES template.
         * Expansion-rule oracle for `FastDispatchTest`: every
         * `executionReady` registry tool must be covered here BEFORE its flag
         * flips (a RULES gap is fail-safe, a NEGATION gap is not — see the
         * class KDoc). Untemplated tools can never reach FAST by construction
         * ([matchTemplate] returns null ⇒ DENIED no-match).
         */
        fun templateToolNames(): Set<String> = RULES.map { it.first }.toSet()

        private fun jsonText(text: String): String =
            "{\"text\": \"${esc(text)}\"}"

        private fun findPhoneCandidate(text: String): String? =
            PHONE_CANDIDATE.findAll(text)
                .maxByOrNull { m -> m.value.count { it.isDigit() } }
                ?.value

        // RFC-8259-complete escape encoder (C0 + quote + backslash), mirroring
        // dev.librepocket.mcp.McpWire.esc (which additionally escapes lone
        // surrogates). Control-char rule changes must be made in BOTH, or
        // extract a shared helper — do not silently diverge.
        private fun esc(text: String): String = buildString {
            for (c in text) {
                when (c) {
                    '\\' -> { append('\\'); append('\\') }
                    '"' -> { append('\\'); append('"') }
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (c.code < 0x20) append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code)) else append(c)
                }
            }
        }

        private fun phoneArgs(text: String): String {
            val digits = findPhoneCandidate(text)?.trim() ?: ""
            return "{\"number\": \"${esc(digits)}\", \"raw\": \"${esc(text)}\"}"
        }

        private fun musicAction(text: String): String = when {
            MUSIC_NEXT.containsMatchIn(text) -> "next"
            MUSIC_PREV.containsMatchIn(text) -> "previous"
            MUSIC_PAUSE.containsMatchIn(text) -> "pause"
            else -> "play"
        }

        private fun volumeArgs(text: String): String {
            val mute = VOLUME_MUTE.containsMatchIn(text)
            val level = VOLUME_LEVEL.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?.coerceIn(0, 100)
            return "{\"level\": ${level ?: 50}, \"mute\": $mute}"
        }

        private fun alarmArgs(text: String): String {
            val m = ALARM_TIME.find(text)
            val hour = m?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?.coerceIn(0, 59) ?: 0
            return "{\"hour\": $hour, \"minute\": $minute, \"label\": \"${esc(text)}\"}"
        }

        /**
         * B3 symbol-query strip tokens: hint words removed from the intent to
         * isolate the symbol name. Deliberately token-based (not char-based)
         * so a symbol name merely CONTAINING a hint char (e.g. 「查理」) is not
         * shredded char by char — though a name exactly equal to joined hint
         * tokens still strips to blank and clarifies (fail-safe direction).
         * Residual: CJK names embedding hint tokens over-strip; a READ-only
         * echo mis-query is harmless, and future NER work owns precision.
         */
        private val SYMBOL_QUERY_STRIP =
            Regex("幫我|這個|那個|一下|請|查|找|尋|搜|詢|符號|函式|函数|symbols?|lsp|[\\s，。、？?！!：:「」『』\"'（）()]", RegexOption.IGNORE_CASE)

        private fun extractSymbolQuery(text: String): String =
            SYMBOL_QUERY_STRIP.replace(text, "").trim()

        private fun hasSymbolQuery(text: String): Boolean =
            extractSymbolQuery(text).isNotEmpty()

        private fun symbolArgs(text: String): String {
            val q = extractSymbolQuery(text)
            return "{\"query\": \"${esc(q)}\", \"limit\": ${LspTools.DEFAULT_LIMIT}}"
        }

        private fun isNegated(text: String): Boolean = containsAny(text, NEGATION_HINTS)

        private fun hasExplicitAlarmTime(text: String): Boolean =
            ALARM_TIME.containsMatchIn(text)

        private fun hasPhoneNumber(text: String): Boolean {
            val raw = findPhoneCandidate(text) ?: return false
            // Require a real digit run: a bare "+" with spaces/punctuation is not
            // a number, and a single stray digit among punctuation is not either.
            val digits = raw.count { it.isDigit() }
            return digits >= 7
        }

        /**
         * Stage1 缺參門（B3 擴至五項，措辭收斂：只斷言此五項，不推及其他工具）：
         * 缺 alarm 時間（不預設 7:00）、缺 phone 號碼（不送空字串）、
         * `sms.compose`（to 恆空，待收件者抽取落地前一律補參）、`calendar.create`
         *（start/end 恆空，待日期解析落地前一律補參）、`lsp.symbols`（剝離提示詞後
         * query 為空，不送空白 query 執行）。回傳問句即需澄清，null
         * 即參數齊備（進接線門）。
         *
         * 範圍註記：`mail.compose`（to/subject 模板恆空）與 `open_app`
         *（packageName 模板恆空）同屬空欄位形狀，但本輪不在缺參門內（後續收件者／
         * 包名抽取落地後再收斂）；此二者仍被接線門以 USER_DISABLED 拒絕，絕不執行。
         */
        private fun missingQuestionFor(toolName: String, text: String): String? = when (toolName) {
            "alarm.create" ->
                if (!hasExplicitAlarmTime(text)) "請問要設幾點的鬧鐘？例如「明天早上7點30分叫我」" else null
            "phone.dial" ->
                if (!hasPhoneNumber(text)) "請問要打給哪個號碼？例如「打電話給 0912345678」" else null
            "sms.compose" ->
                "請問簡訊要傳給誰？請補上收件號碼，例如「傳簡訊給 0912345678 說…」"
            "calendar.create" ->
                "請問行程的時間是？請補上日期時間，例如「明天下午三點加個行程」需含時間"
            LspTools.SYMBOLS_NAME ->
                if (!hasSymbolQuery(text)) "請問要查哪個符號？例如「幫我查 Foo 這個符號」" else null
            else -> null
        }

        private fun containsAny(text: String, hints: List<Regex>): Boolean =
            hints.any { it.containsMatchIn(text) }
    }
}
