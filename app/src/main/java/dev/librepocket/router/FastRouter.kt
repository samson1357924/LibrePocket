package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast lane: deterministic intent-template routing (ARCHITECTURE §9.1/§9.3).
 *
 * Rule engine first: an intent matching a known template goes FAST with a
 * schema-checked [ToolCall]; a match whose projection is not NATIVE is
 * denied with the projection reason code (DEGRADED tools never execute on
 * the fast lane, mirroring the slice's NATIVE-only rule — `lsp.symbols`
 * projects NATIVE once its switch is on, so the pinned suite is
 * unaffected); anything else is DENIED as
 * no-match with manual guidance. Every outcome carries a [TranscriptHeader]
 * with the decision, the projection snapshot and the model-visible snapshot.
 *
 * Stage1 (P2 safe slice, #19 前置): guessing never executes. Missing
 * alarm time (never defaulted: [missingQuestionFor] intercepts first, so
 * [alarmArgs]' 7:00 is unconditionally dead from decide()), missing phone
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
 * reference expansion (template + the full `不/別/沒` × verb NEGATION table,
 * 繁簡對稱 + blank-query clarification). The behaviorally pinned probes are
 * sampling, not the full table:
 * `FastDispatchTest.newToolMustExpandVerbTableBeforeReady` probes the
 * sampled negated intents to deny the ready tool before FAST, not just
 * RULES membership. 路由順序見下方「matchTemplate 路由順序」段：symbol 前閘介於
 * SLOW_HINTS 與 RULES 之間，`查 AlarmManager 符號` 綁 lsp.symbols——由
 * `FastDispatchTest.codeIdentifierSymbolNamesRouteToLspSymbols` 釘住。
 *
 * A1/A2/stage-1 routing hardening (fail-opens closed, conservatively): the
 * `lsp.symbols` template no longer fires on mere hint presence — it matches
 * ONLY an **explicit lookup command** under the affirmative-command
 * admission model ([isAffirmativeSymbolCommand], #19 B3 stage-1):
 * 句首（可選 EN `please`／CJK 請|麻煩|帮我|幫我|给我|給我|请 前綴）必須是查詢動詞
 * （find/search/lookup/locate，或 CJK 查/找/尋/搜/詢/寻/询）或
 * `symbol(s) <name>` 命令形；句中出現授權/條件授權子句（命令式 opener +
 * 結構性授權/條件子句否決：only after/when/if/until/once/before/with/upon …、
 * after|until|unless|once|when|while|if|before|since|whenever|as|because|
 * provided|upon|following|pending|with|subject to|prior to|given|on 後接可選受詞/限定詞（0–3 個）再接
 * approve/agree/consent/confirm/say/allow/release/permit/ok/ready/permission/
 * review 等詞幹（gerund 與過去式一併涵蓋）、wait for/until/till、hold on/off、
 * not yet、CJK 結構性條件句（等…同意、同意後再/才、只有…才等有界框架）等），
 * 或任意教學/說明標記（命令式 opener + 教學標記否決：explain|teach|tutorial|
 * how-to|howto|mean|describe|demonstrate 等屈折形式，或 CJK 說明|説明|講解|
 * 讲解|介紹|介绍|討論|讨论|教程|方法（是）?|意思|名稱|名称|如何|怎麼|怎么|
 * 怎样|怎樣），或疑問尾標（命令式 opener + 疑問尾標否決：? ？ 嗎
 * 吗 呢）⇒ 不滿足前閘。該類語句一律不成立 symbol 模板（不 FAST）——問句/知識/
 * 教學語句與否定前置語句皆然；它們落入 RULES 迴圈後：無 keyword 規則命中者
 * → no-match DENIED（reasonCode null）；命中 keyword 規則者落到該規則原有的
 * fail-closed 語意（例如 `查 AlarmManager 符號呢` → alarm.create 缺時間澄清；
 * 絕不執行）。Cf/格式符拆分（如 `if\u200BI approve`）在子句否決前先經 NFKC +
 * Cf 剝離/刪除 + 非字母數字摺空白的正規化探針覆蓋（見 [isAffirmativeSymbolCommand]）；
 * 教學標記亦在同樣三個文本上測試。The negation
 * gate is a conservative ANY-presence gate ([NEGATION_MARKERS]): any
 * occurrence of 不/沒/別/勿/莫… or English negation words — plus (#3 B3
 * re-review) the explicit prohibition/postponement words
 * （`forbid`/`prohibit`/`ban`/`postpone`/`suspend`/`defer`/`den[iy]\w*`/`hold off`
 * on the EN side; 拒絕/暫緩/延緩/延後/推遲/延遲/延期/推後/寬限/延宕 on the CJK
 * compound side, in [NEGATION_HINTS]） — clarifies over
 * the same three normalized texts — phrases are open-ended, so a closed
 * verb table cannot be complete; over-clarification is the fail-safe
 * direction (the single ready tool is READ-only).
 *
 * 誠實殘餘（honest residuals）：
 * - CJK 否定顆粒（不/別/沒…）仍為封閉否定表——那是防禦縱深（defense in depth），
 *   非 symbol 前閘之准入依據；前閘的准入唯一依據是上列命令式 opener + 結構性
 *   授權/條件子句否決 + 教學標記否決 + 疑問尾標否決。
 * - EN 開放詞彙一律 open-world deny-by-default：非命令式一律 no-match
 *   （DENIED、reasonCode null），不宣稱 EN 否定或 EN 語序的封閉表完整性。
 * - 過度澄清為受控 fail-safe：帶教學標記的命令式語句（如 `查 Foo 符號的方法是`）
 *   一律不成立前閘——寧可 no-match/澄清，絕不把非查詢命令綁到 lsp.symbols 執行。
 * - Cf/default-ignorable-split normalization 已納（本輪）：授權子句/教學標記在
 *   raw 文本與兩個正規化探針（NFKC + Cf 摺空白；NFKC + Cf 刪除）上取聯集測試。
 * - noun-form/inflected consent clauses 已納結構性錨定（本輪）：連接詞集
 *   （含 since|whenever|as|while|because|provided|upon|following|pending|with|subject to|prior to|given|on）
 *   + 0–3 個可選受詞/限定詞 + 動詞/詞幹槽（gerund/過去式/第三人稱涵蓋）。
 * - 已知殘餘（accepted, open-world）：任意非槽詞 possessor（含專有名詞：after Sam approves、
 *   on Foo approve）亦不收——anchor-or-nothing。
 * - 已知殘餘（accepted, anchor-or-nothing 一致性）：`with readiness`／`readiness\b`
 *   與 bare `pending`／`review` 之類 identifier 形態一致性接受。
 * - 殘餘：CJK 結構性條件句僅覆蓋所列框架（等…同意/核准/核準、同意/核準後（再/才）、
 *   只有/只許/只许/只允…才之有界缺口）；EN 開放詞彙不宣稱完備；`\bban\w*\b` 之類（如 `symbols Foo banana` → NEED_CLARIFICATION）、`特別找`
 *   之類普通詞仍收為否定系副作用（recorded, fail-safe）；裸名詞絕不單獨收錄（anchor-or-nothing）；EN 連接詞詞幹槽已納 CJK 許可詞幹
 *   （許可|许可|同意|核准|核準|批准|確認|确认|點頭|点头）——錨定於 EN 連接詞之後者否決；EN/CJK 交集其餘仍 open-world recorded。
 * - 已知殘餘（accepted, frame-local, not fixed；audit F7）：CJK 框架內部的
 *   空白相鄰在 clause 閘仍可通過——如 `同意 後再查`（CJK run 之間僅一個空白）clause gate 放行、
 *   但 extraction 的 whitespace 閘仍會拒絕（multi-token → clarification），永不執行；僅記錄。
 * - 已知過度否決（accepted fail-safe, do not "fix"）：`查 Main 方法 符號` 因 CJK
 *   方法 教學標記事否決落 DENIED——保留，僅記錄；`查 Foo 符號 pending review`
 *   亦因結構性詞幹 `review` 否決——為預期結果。
 *
 * matchTemplate 路由順序（B3 re-review pin；依序，fail-safe）：
 * 1. SLOW_HINTS（自動化描述 → SLOW automation descriptor，由 projection
 *    誠實拒絕，play 上 FLAVOR_BLOCKED）先於一切；
 * 2. code-symbol lookup 前閘第二：containsAny(SYMBOL_HINTS) 且
 *    [isAffirmativeSymbolCommand] 命中（命令式肯定語句准入，見上段）才回
 *    `lsp.symbols`——在任何關鍵詞工具之前。
 *    識別字如 `AlarmManager`／`NotificationManager`／`CalendarProvider`／
 *    `ScreenshotController`／`EmailClient`／`VolumeController` 內含其他工具的
 *    無錨點 hint 子串（`alarm`、`notification`、`calendar`、`screenshot`、
 *    `e-?mail`、`volume`），若仍走 RULES 順序會被這些工具先搶走
 *    （`查 AlarmManager 符號` 會落到 alarm.create 的缺時間澄清）。
 *    前閘保證 `查 AlarmManager 符號` 綁 lsp.symbols 而非 alarm.create；
 *    #4（B3 re-review）/stage-1：問句/知識/教學（how-to）語句與含授權/條件授權
 *    子句或教學/說明標記的語句一律不滿足 symbol 前閘——
 *    但句首（可選 EN please／CJK 請|麻煩|帮我|幫我|给我|給我|请 前綴）必須是查詢動詞
 *    （find/search/lookup/locate，或 CJK 查/找/尋/搜/詢/寻/询）或 `symbol(s) <name>`
 *    命令形；句中出現授權/條件授權子句（only after/until i…approve、
 *    after|until|unless|once|when|while|if|before|since|whenever|as|because|
 *    provided|upon|following|pending|with|subject to|prior to|given|on 後接可選受詞/限定詞（0–3 個）再接詞幹、
 *    wait for/until、hold on/off、not yet、CJK 結構性條件句等，皆對 raw 文本與兩個
 *    正規化探針聯集測試）或任意教學/說明標記（explain|teach|tutorial|how-to|
 *    howto|mean|describe|demonstrate 等屈折形式|說明|講解|如何|怎麼|方法|意思|
 *    名稱…）或疑問尾標（? ？ 嗎 吗 呢）⇒ 不進 symbol 前閘 ⇒
 *    該語句不走 lsp.symbols；無其他規則命中時 no-match DENIED（reasonCode null），
 *    命中 keyword 規則時落到該規則原有的 fail-closed 語意（例如查 AlarmManager
 *    符號呢 → alarm.create 缺時間澄清），絕不執行（pinned by
 *    `FastDispatchTest.interrogativeSymbolUtterancesDenyZeroBackend`）。
 * 3. RULES 關鍵詞工具最後（SYMBOLS 已自 RULES 移除，symbol 匹配由前閘獨占；
 *    [templateToolNames] 顯式補入 lsp.symbols 維持 expansion-rule oracle）。
 * 前閘要求命令式肯定語句准入（僅有 hint 不足；查詢動詞出現在句中——如
 * `I want to find symbol Foo`——同樣不足）；
 * SLOW 命中不受前閘影響（自動化語句仍先轉 automation descriptor）。
 * 由 `FastDispatchTest.codeIdentifierSymbolNamesRouteToLspSymbols`（六個識別字
 * 端到端 FAST＋invocation==1）與 `FastDispatchTest.symbolQueryBeatsKeywordHints`
 * （非 symbol 意圖照常落 keyword 工具、無准入者 no-match）釘住。
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

        // Input length gate: all regex work below operates on ≤ MAX_INTENT_CHARS
        // chars. Over-long input is denied up front with a fixed message (the
        // raw text never echoes into the fallback).
        if (intentText.length > MAX_INTENT_CHARS) {
            return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = null,
                fallbackMessage = "無法理解這個請求，請換個方式描述",
                header = header(RouteKind.DENIED, null, null),
            )
        }

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
        // Deliberate fail-safe branch for an inconsistent registry; never hit with the canonical registry: keep match.toolName in the audit header, like the neighboring match-found DENIED branches.
        val projection = projections[tool.name] ?: return RouteDecision(
            route = RouteKind.DENIED,
            toolCall = null,
            reasonCode = null,
            fallbackMessage = FallbackMessages.forNoMatch(intentText),
            header = header(RouteKind.DENIED, match.toolName, null),
        )
        if (projection.level != CapabilityLevel.NATIVE) {
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
        // Projection not NATIVE stays first so FLAVOR/NO_PRIVILEGE mask
        // clarification (DEGRADED tools never execute on the fast lane);
        // wiring USER_DISABLED stays after so missing args
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
        // Symbol-naming lookup is checked before keyword-matched tools:
        // identifiers like `AlarmManager` contain other tools' hint words, but
        // `查 AlarmManager 符號` is a code-symbol query, not an alarm/call/mail ask.
        // Affirmative-command admission (#19 B3 stage-1): the gate matches ONLY an
        // explicit lookup command ([isAffirmativeSymbolCommand]) — anything else
        // falls through (never FAST); a question/knowledge/teaching utterance
        // with a lookup verb no longer qualifies (#4).
        if (containsAny(text, SYMBOL_HINTS) && isAffirmativeSymbolCommand(text)) {
            return TemplateMatch(LspTools.SYMBOLS_NAME, symbolArgs(text))
        }
        for ((toolName, hints, args) in RULES) {
            if (containsAny(text, hints)) {
                return TemplateMatch(toolName, args(text))
            }
        }
        return null
    }

    companion object {
        private fun r(pattern: String): Regex = Regex(pattern, RegexOption.IGNORE_CASE)

        // Input length gate ([decide]): regex work below this bound only.
        private const val MAX_INTENT_CHARS = 4096

        // Process-wide callId source: kept in the companion (not the class
        // body) so callIds stay unique across multiple FastRouter() instances.
        private val callIds = AtomicLong(0)

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
        // NOT a symbol query and stays no-match, fail-safe). Latin hint is
        // word-anchored: `symbolize` does NOT route; `symbol`/`symbols` do.
        // Prefilter: hint membership is only the prefilter — the authoritative
        // test is [isAffirmativeSymbolCommand] (affirmative-command admission;
        // fullwidth ｓｅａｒｃｈ/ｆｉｎｄ do NOT match the EN opener — they
        // route no-match, same fail-safe direction, pinned by the
        // fullwidth-English tests). The prefilter fires in the symbol front
        // gate (see matchTemplate), never inside the RULES loop.
        private val SYMBOL_HINTS = listOf(r("符號"), r("符号"), r("\\bsymbols?\\b"))
        // #19 B3 stage-1（affirmative-command admission model，取代 A2 lookup-marker
        // 准入）: symbol 前閘僅收**命令式肯定語句**（explicit lookup command）。
        // 准入條件（isAffirmativeSymbolCommand；全部成立才滿足前閘）：
        // 1. 句首（start-anchored，IGNORE_CASE）必須是以下之一：
        //    - EN：`^(?:please[\s,]+)?(?:find|search|lookup|locate)\b`（可選 please 前綴）
        //    - EN 單詞命令形：`^symbol(s)?\s+\S`（如 `symbols Foo`，原樣保留）
        //    - CJK：`^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\s，,]*)?(?:查|找|尋|搜|詢|寻|询)`
        //      （可選禮貌前綴 請|麻煩|帮我|幫我|给我|給我|请 後接查詢動詞
        //       查|找|尋|搜|詢|寻|询；`請先說明…` 不匹配——請後接 先 而非動詞，正確）
        // 2. 任意位置不得出現授權/條件授權子句（對 raw text 與下方兩個正規化探針測——
        //    「任意位置」＝三個文本之一命中即否決；IGNORE_CASE；結構性錨定，絕不裸名詞）：
        //    A. `\bonly\s+(?:after|when|if|until|once|before|with|upon)\b` 變體（approval|consent|permission 收尾）、
        //    B. 連接詞集 after|until|unless|once|when|while|if|before|since|whenever|as|
        //       because|provided|upon|following|pending|with|subject to|prior to|given|on ＋ 可選受詞/限定詞槽
        //       （i|we|you|they|anyone|he|she|it|my|our|your|their|his|her|the|this|that|
        //        a|an|some|any|prior|is|are|was|were|has|have|had|been，最多 3 個）
        //       ＋ 動詞/詞幹槽（approv\w*|agree\w*|consent\w*|confirm\w*|say\w*/said|
        //       allow\w*|release\w*|permit\w*|ok\w*|okay\w*|go[- ]?ahead\w*|ready|
        //       permission|review|（許可|许可|同意|核准|核準|批准|確認|确认|點頭|点头），皆 \w* 或 \b 詞幹錨定）——gerund/過去式/第三人稱
        //       一併涵蓋（`after approving`、`once approved`、`after I had approved`、
        //       `once it is approved`、`since I approved`、`after my approval`）；
        //       裸 `\b(?:approval|approved|consent|pending)\b` 絕不單獨收錄；
        //    C. `\bwait\s+(?:for|until|till)\b`、`\bhold\s+(?:on|off)\b`、`\bnot\s+yet\b`；
        //    D. CJK 結構性條件句僅覆蓋所列框架：`等\s*(我|我們|我们|你|他|她)?\s*(同意|核准|核準|批准|確認|确认|許可|许可|點頭|点头)`、
        //       `(同意|核准|核準|批准|確認|确认|許可|许可)(之後|之后|以後|以后|後|后)(?:再|才)?`、
        //       以及有界缺口 `只有.{0,8}才`／`只許.{0,8}才|只许.{0,8}才`／`只允.{0,8}才`（無裸名詞 CJK 匹配）；
        //    探針：NFKC → 剝 Cf/格式符 → 非字母/數字 run 摺成單一空白（`_` 保留——after_approval
        //    之類識別字不得被拆），以及 NFKC → Cf 直接刪除 → 同樣摺疊（`after I ap\u200Bprove` 這種
        //    字內部拆分才會還原）。兩探針與 raw 取聯集，`_` 以外的連接符不造成誤FAST。
        // 3. 任意位置不得出現教學/說明標記（EN 部分 IGNORE_CASE；CJK 為字面量）：
        //    `\b(?:explain\w*|teach\w*|tutorial|how\s*-?\s*to|howto|mean\w*|describ\w*|demonstrat\w*)\b`
        //    （涵蓋 explained/explaining/described/describing/demonstrated/demonstrating/meant/means 等
        //    屈折形式；`how-to`／`howto` 為明示替代）、
        //    CJK：`說明|説明|講解|讲解|介紹|介绍|討論|讨论|教程|方法(?:是)?|意思|名稱|名称|如何|怎麼|怎么|怎样|怎樣`
        //    （過度封閉為受控 fail-safe 方向，接受）
        // 4. 不以疑問尾標收尾（? ？ 嗎 吗 呢，見 [INTERROGATIVE_ENDINGS]）——
        //    判讀前先重複剝離句尾中性句末終止符 run（[NEUTRAL_SENTENCE_TERMINATORS] 封閉集），
        //    故疑問顆粒後接一串終止符（`呢。`／`嗎！`／`呢!!!`／`嗎。！`）仍歸類為疑問 ⇒ 否決。
        // 5. [hasSymbolQuery] 仍由呼叫端（missingQuestionFor）要求——剝離提示詞
        //    後無有意義殘餘（無 Unicode 字母/數字）時轉 NEED_CLARIFICATION，
        //    FAST 與否皆不可能（與先前行為一致）。
        // `where` 仍不是准入依據（`where is symbol Foo` 不滿足句首動詞條件）。
        // 命中准入 ⇒ 前閘成立；未命中 ⇒ 落入 RULES 迴圈，無規則命中則 no-match
        // DENIED（reasonCode null）；命中 keyword 規則者落其原有 fail-closed
        // 語意（如 `查 AlarmManager 符號呢` → alarm.create 缺時間澄清）——絕不執行。
        // EN 開放詞彙一律 open-world deny-by-default：非命令式一律 no-match。
        // CJK 否定顆粒（不/別/沒…）仍為封閉否定表（見 [NEGATION_HINTS]／
        // [NEGATION_MARKERS]），屬防禦縱深，非本前閘之准入依據。
        private val AFFIRMATIVE_OPENER_EN =
            Regex("^(?:please[\\s,]+)?(?:find|search|lookup|locate)\\b", RegexOption.IGNORE_CASE)
        private val AFFIRMATIVE_OPENER_EN_COMMAND = Regex("^symbols?\\s+\\S", RegexOption.IGNORE_CASE)
        private val AFFIRMATIVE_OPENER_CJK =
            Regex("^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\\s，,]*)?(?:查|找|尋|搜|詢|寻|询)")
        private val PERMISSION_CONDITIONAL_CLAUSE = Regex(
            "\\bonly\\s+(?:after|when|if|until|once|before|with|upon)\\s+[a-z\\s]*?(?:approval|consent|permission)\\b" +
                "|\\b(?:after|until|unless|once|when|while|if|before|since|whenever|as|because|provided|upon|following|pending|with|subject\\s+to|prior\\s+to|given|on)[\\s,]+" +
                "(?:(?:i|we|you|they|anyone|he|she|it|my|our|your|their|his|her|the|this|that|a|an|some|any|prior|is|are|was|were|has|have|had|been)[\\s,]+){0,3}?" +
                "(?:approv\\w*|agree\\w*|consent\\w*|confirm\\w*|say\\w*|said\\b|allow\\w*|release\\w*|permit\\w*|ok\\w*|okay\\w*|go[- ]?ahead\\w*|ready\\b|permission\\b|review\\b|(?:許可|许可|同意|核准|核準|批准|確認|确认|點頭|点头))" +
                "|\\bwait\\s+(?:for|until|till)\\b" +
                "|\\bhold\\s+(?:on|off)\\b" +
                "|\\bnot\\s+yet\\b" +
                "|等\\s*(?:我|我們|我们|你|他|她)?\\s*(?:同意|核准|核準|批准|確認|确认|許可|许可|點頭|点头)" +
                "|(?:同意|核准|核準|批准|確認|确认|許可|许可)(?:之後|之后|以後|以后|後|后)(?:再|才)?" +
                "|只有.{0,8}才" +
                "|只許.{0,8}才|只许.{0,8}才" +
                "|只允.{0,8}才",
            RegexOption.IGNORE_CASE,
        )
        private val TEACHING_DESCRIPTION_MARKER = Regex(
            "\\b(?:explain\\w*|teach\\w*|tutorial|how\\s*-?\\s*to\\b|howto\\b|mean\\w*|describ\\w*|demonstrat\\w*)\\b" +
                "|說明|説明|講解|讲解|介紹|介绍|討論|讨论|教程|方法(?:是)?|意思|名稱|名称|如何|怎麼|怎么|怎样|怎樣",
            RegexOption.IGNORE_CASE,
        )
        private val INTERROGATIVE_ENDINGS = charArrayOf('?', '？', '嗎', '吗', '呢')

        // 此集為刻意的最小 NFKC 閉子集；漏網之終止符形態（如 U+301C〜、U+3030〰、U+22EF⋯）皆由
        // extraction C′（remainder 含 嗎|吗|呢 ⇒ 澄清）與 whitespace 閘背擋——安全機制不依賴此集完備。
        // #19 B3 stage-6.1（疑問尾標加固）：中性句末終止符封閉集（半形＋全形）。
        // 疑問尾標探針在判讀尾碼前會重複剝離此集的句尾 run，再判讀剩餘最後碼點——
        // 疑問顆粒後接一串終止符（`查 Foo 符號呢。`、`查 Foo 符號呢!!!`、
        // `查 Foo 符號呢…`、`查 Foo 符號嗎。！`）仍正確歸類為疑問 ⇒ 否決。
        // 保守封閉集：不在此集且非疑問顆粒 ⇒ 停止剝離（如 `查 Foo 符號呢 Extra`
        // ——multi-token 由 extraction 的 whitespace 閘澄清，永不到執行）。
        // `?`／`？` 本身是疑問顆粒（在 INTERROGATIVE_ENDINGS），不在此集，
        // 故剝離終止符 run 後尾部仍是 `?`／`？` 時正確歸類為疑問。
        private val NEUTRAL_SENTENCE_TERMINATORS: Set<Char> = setOf(
            '.', '．', '。', '！', '!', '‥', '…', '～', '~', '，', ',', '、', '；', ';', '：', ':',
            '）', ')', '」', '』', '”', '"', '\'', '’',
        )

        // 授權子句/教學標記的正規化探針（#19 B3 stage-1 fix；解決 Cf/格式符拆分繞過）。
        // NFKC → 剝 Cf/格式符 → 非字母/數字 run 摺成單一空白（`_` 保留——`after_approval`
        // 之類識別字不得被拆成 after approval）為 spaced 探針；NFKC → Cf 直接刪除（不留空白）
        // → 同樣摺疊為 joined 探針（字內部拆分如 `ap\u200Bprove` 才還原得回 `approve`）。
        // 兩探針與 raw text 取聯集比對：任一命中即否決。CJK 字元皆 \p{L} 被保留，不拆。
        private val CLAUSE_Cf = Regex("\\p{Cf}+")
        private val CLAUSE_FOLD = Regex("[^\\p{L}\\p{N}_]+")
        private val HANGUL_FILLER = Regex("[\\u115F\\u1160\\u3164\\uFFA0]+")
        private val COMBINING_MARKS = Regex("\\p{M}+")

        /**
         * NFKC + Cf-strip + whitespace-fold probe for the admission clauses. Two
         * passes, evaluated as alternatives (union with the raw text):
         * (a) [clauseProbeSpaced] — Cf folds into the space run: `if\u200BI approve`
         *     rejoins as `if I approve`;
         * (b) [clauseProbeJoined] — Cf is REMOVED silently: `ap\u200Bprove` rejoins as
         *     `approve` (an intra-word split neither the raw text nor the spaced
         *     probe can match).
         * `_` (Pc) is KEEPED in both probes so identifiers like `after_approval`
         * stay one word and cannot collide with the `after … approval` clause.
         * Unlike [isNegated]'s three-text scheme this never feeds `\b`-less
         * matching: [NEGATION_REMOVED]-style full removal is deliberately not
         * used here (it would dissolve the very word boundaries the clause
         * regexes anchor on).
         */
        private fun clauseProbeSpaced(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC).replace(CLAUSE_FOLD, " ").trim()

        private fun clauseProbeJoined(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(CLAUSE_Cf, "")
                .replace(CLAUSE_FOLD, " ")
                .trim()

        /**
         * Affirmative-command admission predicate for the symbol front gate
         * (#19 B3 stage-1). Returns true iff the trimmed raw text is an
         * explicit lookup command: it opens with the EN verb set
         * (optionally after a leading `please`), the EN single-word command
         * form (`symbol(s) <name>`), or a CJK lookup verb (optionally after a
         * politeness prefix 請|麻煩|帮我|幫我|给我|給我|请 directly followed by
         * 查|找|尋|搜|詢|寻|询）; it contains NO permission/conditional/consent
         * clause anywhere (checked against the raw text AND the two normalized
         * probes, see [clauseProbeSpaced]/[clauseProbeJoined]); it contains NO
         * teaching/description marker anywhere (same three texts); and its
         * Cf-stripped view, after repeatedly dropping a trailing run of neutral
         * sentence-final terminators ([NEUTRAL_SENTENCE_TERMINATORS], a fixed
         * closed set — halfwidth + fullwidth `. ． 。 ！ ! ‥ … ～ ~ ， , 、 ； ; ： : ） ) 」 』 ” " ' ’`),
         * does NOT end with a question tail （? ？ 嗎 吗 呢，見 [INTERROGATIVE_ENDINGS];
         * the fullwidth forms of the interrogative question particles are listed
         * there). The tail probe strips Cf/Hangul fillers/combining marks AND a
         * trailing terminator run before reading the last code unit — so a
         * particle followed by neutral sentence-final punctuation (`呢。`／`嗎！`／`呢!!!`／`呢…`／`嗎。！`) is
         * correctly denied. `?`/`？` are themselves
         * interrogative particles (NOT in the terminator set), so a run like
         * `嗎？。` still classifies as interrogative after the `。` drop;
         * The terminator set is closed and deliberately minimal; gaps are
         * backstopped by the Step C′ reject (a particle anywhere in the
         * remainder denies) and the whitespace gate — never by execution.
         *
         * Any gate failure falls through to the RULES loop, then to no-match
         * DENIED (reasonCode null) when nothing matches, or to the matching
         * keyword template's own fail-closed outcome — never FAST.
         *
         * The predicate deliberately knows nothing about negation: the closed
         * CJK negation table ([NEGATION_HINTS]/[NEGATION_MARKERS]) remains as
         * defense in depth for command-led utterances carrying negation
         * particles (e.g. `查 Foo 符號 不要`); the affirmative-command model is
         * the admission basis, negation is not. English vocabulary is treated
         * open-world deny-by-default: any non-command utterance no-matches.
         * Over-clarification (an affirmative-looking command that still carries
         * a teaching marker such as `查 Foo 符號的方法是`) is the accepted,
         * controlled fail-safe direction — never the reverse.
         */
        private fun isAffirmativeSymbolCommand(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            val opensWithCommand = AFFIRMATIVE_OPENER_EN.containsMatchIn(trimmed) ||
                AFFIRMATIVE_OPENER_EN_COMMAND.containsMatchIn(trimmed) ||
                AFFIRMATIVE_OPENER_CJK.containsMatchIn(trimmed)
            if (!opensWithCommand) return false
            val spaced = clauseProbeSpaced(trimmed)
            val joined = clauseProbeJoined(trimmed)
            if (PERMISSION_CONDITIONAL_CLAUSE.containsMatchIn(trimmed) ||
                PERMISSION_CONDITIONAL_CLAUSE.containsMatchIn(spaced) ||
                PERMISSION_CONDITIONAL_CLAUSE.containsMatchIn(joined)
            ) {
                return false
            }
            if (TEACHING_DESCRIPTION_MARKER.containsMatchIn(trimmed) ||
                TEACHING_DESCRIPTION_MARKER.containsMatchIn(spaced) ||
                TEACHING_DESCRIPTION_MARKER.containsMatchIn(joined)
            ) {
                return false
            }
            // 疑問尾標檢查跑在剝離 Cf/Hangul filler/combining mark、並重複剝離
            // 句尾中性句末終止符 run（NEUTRAL_SENTENCE_TERMINATORS 封閉集）後的視圖上：
            // 句尾預設忽略字（如 `查 Foo 符號呢\u200B`）及填充字/組合記號
            // （如 `呢\u3164`／`呢\u0301`）不得翻轉 DENIED→FAST；疑問顆粒後接一串
            // 中性終止符（`呢。`／`嗎！`／`呢!!!`／`呢…`／`嗎。！`）時，run 被剝離後
            // 尾部仍是疑問顆粒 ⇒ 否決（見 [INTERROGATIVE_ENDINGS]）。
            val tailProbe = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
                .replace(CLAUSE_Cf, "")
                .replace(HANGUL_FILLER, "")
                .replace(COMBINING_MARKS, "")
                .trim()
            var tailEnd = tailProbe.length
            while (tailEnd > 0 && tailProbe[tailEnd - 1] in NEUTRAL_SENTENCE_TERMINATORS) tailEnd--
            // 終止符 run 可能夾著空白（`呢 。`）；剝 run 後再 trim，使尾部的疑問顆粒
            // 於閘層即被辨識（多 token 夾空白的補救顯性見 extraction C′ backstop）。
            return tailProbe.substring(0, tailEnd).trim().lastOrNull()?.let { it !in INTERROGATIVE_ENDINGS } ?: true
        }
        private val SLOW_HINTS = listOf(
            r("自動點擊"), r("自动点击"), r("點擊螢幕"), r("点击屏幕"), r("幫我點"), r("帮我点"),
            r("自動填表"), r("自动填表"), r("代操"), r("自動操作"), r("自动操作"),
        )

        /**
         * Stage1 否定句門：NEGATION_HINTS 收「別/别+動詞」（繁簡對稱）與明確否定詞；
         * 命中且同時命中工具模板即轉澄清，絕不執行。A1 起再叠加 [NEGATION_MARKERS]
         * ANY-presence 閘（見下）——只要有否定詞/不確定詞存在即澄清。
         *
         * 兩表互補（COMPLEMENTARY），不得刪除單顆粒集或通用禁止詞：本表（[NEGATION_HINTS]）
         * 收多動詞複合否定與通用禁止詞（取消/停止/禁止/算了＋不/別/没&貼動詞 等 194 條——
         * B3 re-review（#3）新增明確禁止/順延 句族：拒絕/暫緩/延緩/延後/推遲/延遲/
         * 延期/推後/寬限/延宕 繁簡對稱共 18 條，與 {取消,停止,禁止,算了} 同屬通用禁止詞載荷；
         * 本表相對 [NEGATION_MARKERS] 的獨有載荷 = `取消`/`停止`/`禁止`/`算了` 四個通用禁止詞
         * ＋ 18 條明確禁止/順延 複合詞（拒絕/拒绝/暫緩/暂缓/延緩/延缓/延後/延后/推遲/推迟/延遲/延迟/延期/推後/推后/寬限/宽限/延宕，繁簡對稱）
         * —— 這 18 條不含任何單顆粒旗標（不/未/別 等），並非 MARKERS 的子集合；
         * 與 MARKERS 英文集重複的 11 個 \b 詞）；
         * [NEGATION_MARKERS] 收單一顆粒集（不 沒 没 別 别 勿 莫 嫑 否 無 无 免 未 毋 休）＋英文 \b 集。
         * [isNegated] 將兩表 OR 同命三個文本：否定詞片語是開放集合，
         * 封閉表不可能完整，故不猜——命中即澄清（過度澄清為 fail-safe 方向）。
         * 「特別找」非命令式句首、亦不命中 symbol 前閘 ⇒ no-match DENIED（非准入、原因為 no-match，永遠不會 FAST）；「特別」/「别人」後接任意模板動詞
         * 同樣轉澄清（A1 閘的必然結果，取代只收「別+動詞」的舊精度）。
         *
         * 擴表規則（B3 fail-safe ordering）：封閉動詞表——新工具上線為執行 ready
         * 前必須先擴充此表（並在 RULES 增模板，見 [templateToolNames]），否則該
         * 工具參數齊備時的否定句會一路走到 FAST 執行（非 fail-safe；今日僅因
         * 各工具多為非 ready 才看似安全）。`lsp.symbols` 是參照擴充（模板＋完整
         * `不/別/沒`×動詞表（繁簡對稱）＋缺參門同 PR 落地，先於其既有 ready 旗標——
         * 該旗標早於模板存在，但無模板時路由不可達，仍屬 fail-safe）。通用否定詞
         *（不要／不用／…）覆蓋所有模板工具，見 `FastDispatchTest`。本表涵蓋的封閉動詞集為
         * {打設開截播調導發傳幫給查找尋搜詢問寫寄} × {不/別/别/沒/没}（繁簡對稱，
         * 含繁簡交叉混排組合——例如 `別尋`/`别尋`/`沒寻`/`没尋` 四組全列，防禦混排輸入）＋
         * 通用否定詞＋`\bnever\b`/`\bno\b`/`\bnot\b`/`\brefus\w*\b`/`\bavoid\w*\b`/`\bskip\w*\b`/`\bwithout\b`
         *（英文否定為 best-effort：`refuses symbols` 已涵蓋；其餘未列動詞仍是殘餘缺口，
         * 屬 fail-open 候選，監測留待 Phase 2/3）；#3 B3 re-review 另於
         * [NEGATION_MARKERS] 英文 \b 集擴充明確禁止/順延句族
         *（`forbid`/`prohibit`/`ban`/`postpone`/`suspend`/`defer`/`den[iy]\w*`/`hold off`），
         * 中文複合禁止/順延句族（拒絕/暫緩/延緩/延後/推遲/延遲/延期/推後/寬限/延宕）則載入本表。其他模板動詞（擷／安排／帶／切／靜音）
         * 在其工具把 executionReady 翻轉前補入——不得提前宣稱涵蓋。
         * 否定偵測同時跑在原始文本、NFKC 正規化後「刪除非（非 Lm）字母字元（含所有數字）與 ASCII 英數字」的文本、以及
         * 「非字母/數字字元→空白」的文本上（見 [isNegated]），故 `別 查`／`不\u200B查`／
         * `不2查`／全形英文（`ｎｅｖｅｒ`）之類插入分隔符、組合字標記、Cf 格式符或寬度混淆的
         * 形式同樣不執行（澄清或 no-match DENIED）。
         * 已記錄：「特別找」含「別找」，但非命令式句首 ⇒ no-match DENIED（非准入、原因為 no-match，永遠不會 FAST，永遠不會執行）。
         * 已知侷限：複合句（「取消舊鬧鐘再設一個新的」同時命中模板與取消/停止詞）
         * 會被轉澄清，屬誤澄清（安全方向），由測試釘住。
         * 測試只釘住連續探針（sampling，非全表）：行為測試抽樣驗證，不宣稱整表行為。
         *
         * 已知殘餘（Known residuals，皆為安全方向或已記錄）：
         * - 完全處理：zero-width／default-ignorable／separator／combining mark／
         *   punctuation／symbol／PUA／Cc/Cf 碼點，Lm modifier letters
         *   （`ˇ` U+02C7、`ˉ` U+02C9、阿拉伯 tatweel U+0640、`々` U+3005 等 400+ 碼點），
         *   所有 Nd/No/Nl 數字（含非 ASCII 與 NFKC-stable Nl，如 U+2180 羅馬數字），
         *   作為分隔符拆開的 CJK 否定詞，以及全形 Latin/digit 否定句（經 NFKC）
         *   ——皆不執行（澄清或 no-match DENIED）。
         * - 亦已處理：ASCII-alphanumeric in-run splitter
         *   （`不2查`、`別¹查` 類 NFKC digit、`不™查`、`別½查`）——removed pass
         *   剝離後接合回連續 hint。
         * - NOT closed（記錄在案，監測）：
         *   (a) negation run 中可見的非 Lm 字母（含非 Nl 的 CJK 漢字，
         *       例如 `不一查`）——無法避免（hint 即 letter runs）；
         *   (b) 混合文字同形字（Cyrillic/Greek lookalikes；例如 `nеver` 用 U+0435）
         *       未折疊——Phase 2/3 再評估 confusable folding；
         *   (c) 英文覆蓋為 best-effort，限於列舉動詞
         *       （`never`/`don'?t`/`do not`/`stop`/`cancel`/`refus\w*`/`avoid\w*`/`skip\w*`/`without`/`no`/`not`
         *       ＋#3 B3 re-review：`forbid\w*`/`prohibit\w*`/`ban\w*`/`postpone\w*`/`suspend\w*`/`defer\w*`/
         *       `den[iy]\w*`/`hold\\s*off`）；
         *       `cannot`/`later`/`afterwards` 類及未列舉動詞不涵蓋；`ban\w*` 會過度涵蓋 `banana` 以及識別字前綴（`BanController`、`BannerLayout`、`DenialOfServiceException`——皆 `\bban\w*\b`/`\bden[iy]\w*\b` 全詞命中，壓成需澄清而非誤執行）
         *       ——皆過度澄清 fail-safe 方向，接受並記錄；
         *   (d) 全形-only 符號 hint 不會路由（SYMBOL_HINTS 比對原始文本，安全方向）；
         *   (e) `符號是Foo嗎` → no-match（無准入 ⇒ 不進模板，reasonCode null）；
         *       `特別找` 現為 no-match（非命令式句首 ⇒ 不進模板，reasonCode null）；`查理`→`理` 過度剝離（pinned）；
         *       `意思是符號` → no-match（無准入 ⇒ reasonCode null）；
         *   (f) CJK 異體字碼點（查 vs 査）未涵蓋。
         * - 命令式肯定語句帶否定顆粒者（如 `查 Foo 符號 不要` 型）才會進模板後閘轉澄清；
         *   非命令式句首者（如 `別問 Foo 符號`、`not symbols`、`我不想查 Foo 符號`）直接
         *   no-match DENIED——皆為 DENIED、絕不執行。
         * - `查 是 符號` → clarify；`查 要 用 符號` → clarify（命令式句首命中，
         *   剝離後無有意義殘餘）。非命令式句首者一律 no-match：
         *   `什麼是符號`、`為什麼要用符號`、`符號是什麼`、`符號的意思`、
         *   `符號有哪些種類`、bare `symbols`、`What is a symbol?`、`Explain symbols`
         *   （無命令式准入 ⇒ 不進 symbol 模板；否定閘不介入）。
         * - `查 find 符號`/`查 search 符號` 等：EN lookup 動詞在 cue 位置
         *   （Step A，句首錨定）為剝離詞，符號名恰為 find/search/lookup/locate
         *   者剝空 → clarify（pinned by `englishLookupMarkerStripped`）；
         *   非 cue 位置的動詞不再剝離（見 SYMBOL_OPENER 系列 KDoc）。
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
            // `不`+動詞（繁簡對稱）
            // 明確禁止/順延（#3 B3 re-review）：自然語言等價於否定/延遲執行——
            // `我拒絕查 Foo 符號`／`先暫緩查 Foo 符號` 一類語句必不得進
            // symbol 模板執行；收錄於複合否定表（非單顆粒表），繁簡對稱＋混排涵蓋
            // 拒絕/暫緩/延緩/延後/推遲/延遲/延期/推後/寬限/延宕 句族。
            r("拒絕"), r("拒绝"), r("暫緩"), r("暂缓"), r("延緩"), r("延缓"),
            r("延後"), r("延后"), r("推遲"), r("推迟"), r("延遲"), r("延迟"),
            r("延期"), r("推後"), r("推后"), r("寬限"), r("宽限"), r("延宕"),
            r("不查"), r("不找"), r("不尋"), r("不搜"), r("不詢"), r("不問"),
            r("不打"), r("不設"), r("不開"), r("不截"), r("不播"),
            r("不調"), r("不導"), r("不發"), r("不傳"), r("不幫"), r("不給"),
            r("不寻"), r("不询"), r("不问"),
            r("不设"), r("不开"), r("不调"), r("不导"), r("不发"), r("不传"), r("不帮"), r("不给"),
            // `別/别`+符號/查詢動詞（繁簡對稱；尋→寻）
            r("別找"), r("別尋"), r("別搜"), r("別詢"), r("別問"),
            r("别找"), r("别寻"), r("别搜"), r("别询"), r("别问"),
            // `沒/没`+動詞（繁簡對稱）
            r("沒查"), r("沒找"), r("沒尋"), r("沒搜"), r("沒詢"), r("沒問"),
            r("沒打"), r("沒設"), r("沒開"), r("沒截"), r("沒播"),
            r("沒調"), r("沒導"), r("沒發"), r("沒傳"), r("沒幫"), r("沒給"),
            r("没查"), r("没找"), r("没寻"), r("没搜"), r("没询"), r("没问"),
            r("没打"), r("没设"), r("没开"), r("没截"), r("没播"),
            r("没调"), r("没导"), r("没发"), r("没传"), r("没帮"), r("没给"),
            // `不/別/别/沒/没`+寫/寄（mail/cancel-adjacent; 繁簡對稱，一對一列）
            r("不寫"), r("不写"), r("別寫"), r("别写"), r("沒寫"), r("没写"),
            r("不寄"), r("別寄"), r("别寄"), r("沒寄"), r("没寄"),
            // 繁簡交叉混排（mixed-script）：12 個動詞對 × {別/别/沒/没} × {繁/簡}
            // 全組合補齊——純繁簡對稱表蓋不到 `別寻`／`别尋` 這類混排輸入。
            r("別寻"), r("別询"), r("別问"), r("別设"), r("別开"), r("別调"),
            r("別导"), r("別发"), r("別传"), r("別帮"), r("別给"), r("別写"),
            r("别尋"), r("别詢"), r("别問"), r("别設"), r("别開"), r("别調"),
            r("别導"), r("别發"), r("别傳"), r("别幫"), r("别給"), r("别寫"),
            r("沒寻"), r("沒询"), r("沒问"), r("沒设"), r("沒开"), r("沒调"),
            r("沒导"), r("沒发"), r("沒传"), r("沒帮"), r("沒给"), r("沒写"),
            r("没尋"), r("没詢"), r("没問"), r("没設"), r("没開"), r("没調"),
            r("没導"), r("没發"), r("没傳"), r("没幫"), r("没給"), r("没寫"),
            r("\\bnever\\b"), r("\\bnot\\b"), r("\\bno\\b"),
            r("\\brefus\\w*\\b"), r("\\bavoid\\w*\\b"), r("\\bskip\\w*\\b"), r("\\bwithout\\b"),
            r("\\bdon'?t\\b"), r("\\bdo not\\b"), r("\\bstop\\b"), r("\\bcancel\\b"),
        )

        // Conservative negation/uncertainty gate: ANY presence of a negation word
        // clarifies — phrases are open-ended, so a closed verb table cannot be complete.
        // The two tables are COMPLEMENTARY: [NEGATION_MARKERS]
        // holds the single-particle set （不 沒 没 別 别 勿 莫 嫑 否 無 无 免 未 毋 休） plus the
        // English \b set — `no`/`not`/`never`/`don'?t`/`do not`/`stop`/`cancel`/`refus\w*`/
        // `avoid\w*`/`skip\w*`/`without` plus (#3 B3 re-review, explicit prohibition/
        // postponement): `forbid\w*`/`prohibit\w*`/`ban\w*`/`postpone\w*`/`suspend\w*`/
        // `defer\w*`/`den[iy]\w*`/`hold\s*off`; [NEGATION_HINTS]
        // holds the multi-verb compound negations plus general forbiddens, whose unique
        // load-bearing contribution is {取消,停止,禁止,算了} plus the 18 prohibition/deferral
        // compounds (拒絕/拒绝/暫緩/暂缓/延緩/延缓/延後/延后/推遲/推迟/延遲/延迟/延期/推後/推后/寬限/宽限/延宕 — none of which
        // contain a single-particle marker, so they are NOT a subset of this gate). The single-particle set,
        // those four words and those 18 compounds are all load-bearing and must not be deleted; [isNegated] ORs both
        // over the same three texts
        // (raw, NFKC-removed, NFKC-spaced). Over-clarification is the
        // fail-safe direction here (the single ready tool is READ-only and this lane
        // dispatches at most one of everything): when in doubt, ask, never execute.
        // Known deliberate over-clarifies (pinned by tests): 「特別」含「別」、
        // 「别人」含「别」——接受，不另設例外（例外清單本身會變成另一個表）。
        private val NEGATION_MARKERS = listOf(
            r("不"), r("沒"), r("没"), r("別"), r("别"), r("勿"), r("莫"), r("嫑"), r("否"),
            // 無/无（無法＝unable）與 免（免查＝refrain/avoid）亦為否定語氣顆粒，
            // 保守閘必須收錄（否則「無法查 Foo 符號」「免查 Foo 符號」會落 FAST）。
            // 未（尚未查＝not yet）、毋（毋查＝do not, literary）、休（休查＝desist, literary）同為
            // 否定語氣顆粒——惟「尚未查 Foo 符號」「未曾查 Foo 符號」「毋查 Foo 符號」「休查 Foo 符號」
            // 皆不滿足命令式句首（尚/未/毋/休 非查詢動詞）⇒ no-match DENIED（reasonCode null），否定閘不介入。
            r("無"), r("无"), r("免"), r("未"), r("毋"), r("休"),
            r("\\bno\\b"), r("\\bnot\\b"), r("\\bnever\\b"), r("\\bdon'?t\\b"), r("\\bdo not\\b"),
            r("\\bstop\\b"), r("\\bcancel\\b"), r("\\brefus\\w*\\b"), r("\\bavoid\\w*\\b"),
            r("\\bskip\\w*\\b"), r("\\bwithout\\b"),
            // 明確禁止/順延EN句族（#3 B3 re-review）：自然語言等價於否定——
            // 明確詞集：`forbid`／`prohibit`／`ban`／`postpone`／`suspend`／`defer`／`den[iy]\w*`（含 deny/denies/denied/denying）／`hold off`。
            // `I forbid you to search symbol Foo`／`postpone finding symbols`／
            // `hold off on symbols`／`suspend lookup` 皆不得落 FAST；模板命中者轉澄清，
            // 無模板命中者 no-match DENIED——皆為 DENIED、絕不執行。
            // 註：`\bban\w*\b` 會過度涵蓋 `banana`（`symbols Foo banana` → 澄清）——
            // 過度澄清為 fail-safe 方向，接受並記錄。
            r("\\bforbid\\w*\\b"), r("\\bprohibit\\w*\\b"), r("\\bban\\w*\\b"),
            r("\\bpostpone\\w*\\b"), r("\\bsuspend\\w*\\b"), r("\\bdefer\\w*\\b"),
            r("\\bden[iy]\\w*\\b"), r("\\bhold\\s*off\\b"),
        )

        // Shared argument patterns (also hoisted: previously recompiled per
        // call inside hasExplicitAlarmTime/hasPhoneNumber/phoneArgs and
        // music/volume/alarm arg builders).
        // ALARM_TIME：`(?<!\d)`／`(?!\d)` 守衛讓 `7點123分`／`123點` 整體不匹配
        // （鄰接數字不會被截出靜默錯誤的時間），一律落缺參澄清。
        private val ALARM_TIME = Regex("(?<!\\d)(\\d{1,2})[:點点](\\d{1,2})?(?!\\d)")
        private val PHONE_CANDIDATE = Regex("[+\\d][\\d\\-\\.\\s]{5,16}")
        private val MUSIC_NEXT = Regex("下一首|切歌|next", RegexOption.IGNORE_CASE)
        private val MUSIC_PREV = Regex("上一首|previous", RegexOption.IGNORE_CASE)
        private val MUSIC_PAUSE = Regex("暫停|暂停|pause", RegexOption.IGNORE_CASE)
        private val VOLUME_MUTE = Regex("靜音|静音|mute", RegexOption.IGNORE_CASE)
        private val VOLUME_LEVEL = Regex("(\\d{1,3})")

        // FastRouter template rules + arg builders, hoisted to the
        // companion with the hint regexes (Stage C completion): the
        // 11-entry rule list and the builder closures stop allocating
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
        )

        /**
         * Fast-lane template coverage: every tool name with a RULES template,
         * plus `lsp.symbols` — its template is the symbol front gate ahead of
         * the RULES loop (see [matchTemplate]), not a RULES entry. This is the
         * expansion-rule oracle for `FastDispatchTest`: every
         * `executionReady` registry tool must be covered here BEFORE its flag
         * flips (a RULES gap is fail-safe, a NEGATION gap is not — see the
         * class KDoc). Untemplated tools can never reach FAST by construction
         * ([matchTemplate] returns null ⇒ DENIED no-match).
         */
        fun templateToolNames(): Set<String> = RULES.map { it.first }.toSet() + setOf(LspTools.SYMBOLS_NAME)

        private fun jsonText(text: String): String =
            "{\"text\": \"${esc(text)}\"}"

        private fun findPhoneCandidate(text: String): String? =
            PHONE_CANDIDATE.findAll(text)
                .maxByOrNull { m -> m.value.count { it.isDigit() } }
                ?.value

        // RFC-8259-complete escape encoder (C0 + quote + backslash), mirroring
        // the sibling escapers: dev.librepocket.mcp.McpWire.esc AND
        // dev.librepocket.jev.JevClient.jsonEscape — all three are the sync
        // pact. Control-char rule changes and the lone-surrogate handling
        // must be made in ALL of them (or extract a shared helper) — do not
        // silently diverge.
        private fun esc(text: String): String = buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' -> { append('\\'); append('\\') }
                    c == '"' -> { append('\\'); append('"') }
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c == '\b' -> append("\\b")
                    c == '\u000C' -> append("\\f")
                    c.code < 0x20 -> append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                    // Lone surrogate（無伴的 high/low）：鏡像 McpWire.esc——
                    // OkHttp/okio 在 UTF-8 encode 時把孤立 surrogate 靜默換成
                    // `?` (0x3F)（**不是** U+FFFD），故上線值會與解析值不同——以
                    // 確定性 \uXXXX 轉義（與 C0 分支同小寫 4-hex 約定）——
                    // 僅轉義「無伴」的 surrogate；成對者不拆開。
                    // **成對** surrogate 原樣通過（兩個原始 UTF-16 單元成對輸出，
                    // 不重組 code point、不切開）。
                    // mirror of McpWire.esc — keep both in sync.
                    c.isSurrogate() -> {
                        val paired = c.isHighSurrogate() &&
                            i + 1 < text.length && text[i + 1].isLowSurrogate()
                        if (paired) {
                            append(c).append(text[i + 1])
                            i++
                        } else {
                            append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                        }
                    }
                    else -> append(c)
                }
                i++
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
         * B3 symbol-query extraction (#19 stage-2, A3/A4 fix): positional,
         * lossless remainder extraction for the symbol template. The intent is
         * NOT regex-replaced globally; instead the pipeline strips only what is
         * anchored at position 0 or at the edge, and preserves everything
         * intra-token verbatim. Steps, in fixed order:
         *
         * Step A — anchored openers (each at most once, start-anchored):
         *   EN: `^(?:please[\s,]+)?(?:find|search|lookup|locate)\b[\s,]*`
         *       `(?:for\s+|the\s+|a\s+|an\s+)?` — the verb plus ONE optional
         *       post-verb connective (`find a symbol Foo` → `symbol Foo`);
         *   CJK: `^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\s，,]*)?`
         *       `(?:查|找|尋|搜|詢|寻|询)[\s，,]*(?:一下)?[\s，,]*` — optional
         *       politeness prefix + lookup verb + optional 一下;
         *   command form A: `^symbols?\b[\s]*` — `symbols Foo` → `Foo`.
         *   Applied sequentially (CJK → EN → command), so `查 find 符號` still
         *   reduces to empty and clarifies.
         * Step B — `symbol(s)` keyword tokens removed wherever they appear as
         *   words (`\bsymbols?\b`, IGNORE_CASE), and CJK hint substrings
         *   （符號|符号|函式|函数）removed wherever they occur (substring, as
         *   before). `SymbolTable`/`getSymbol`/`LSPClient` have no word
         *   boundary at the hint, so they survive intact.
         * Step C — CJK prose fillers removed as substrings (same class as the
         *   accepted CJK over-strip residuals): 幫我|這個|那個|一下|請|查|找|
         *   尋|搜|詢|什麼|什么|何|是|的|要|用|如何|怎麼|怎么|意思|
         *   為什麼|为什么|說明|説明|講解|讲解|介紹|介绍|教程.
         *   #19 B3 stage-6.1：疑問顆粒（嗎|吗|呢）不再列入本步的剝離集——
         *   疑問顆粒出現在殘餘中 anywhere 即解析失敗（見下一步）。
         * Step C′ — CJK interrogative particles （嗎|吗|呢） are NEVER stripped
         *   anywhere in the remainder; a remainder that still contains one
         *   anywhere fails extraction (empty) ⇒ NEED_CLARIFICATION (fail-closed;
         *   those particles are implausible inside a real symbol name).
         *   (`查 呢 符號`-class input now also fails here — same clarification
         *   outcome as before, reached fail-closed instead of via stripping.)
         * Step D — leading EN connectives stripped, REPEATEDLY, but only when
         *   another token remains after the connective
         *   (`^(?:for|the|a|an|named|called|of|to)\s+(?=\S)`, IGNORE_CASE).
         *   `locate symbol named Foo` → `Foo`; a bare `a` / `to` / `for` at the
         *   start of the remainder with NOTHING after it is NOT stripped (a
         *   single-letter identifier stands: `查 a 符號` → `a`).
         * Step E — edge trim ONLY: leading/trailing runs of
         *   `[\s，。、？?：:「」『』“”"'（）]` stripped repeatedly. Nothing
         *   intra-token is ever removed by this step. NOTE: halfwidth `!` and
         *   the halfwidth parens are deliberately NOT in the edge class —
         *   `查 ! Foo`-class inputs must clarify, and `get(id)` must survive
         *   verbatim (intra parens are allow-listed, never edge-stripped);
         *   halfwidth `?` stays (trailing question particle), as do the
         *   fullwidth `（）？` CJK edge shapes. `-` is no longer in the edge
         *   class either — `查 -main 符號` FASTs with query `-main` verbatim
         *   (Clojure-family identifiers). `.` is NOT in the edge class:
         *   `查 .bar 符號` FASTs with query `.bar` — recorded as accepted.
         *   `查 Foo. 符號` FASTs with query `Foo.` — recorded as accepted.
         * Step F — empty remainder, or ANY remaining whitespace, or ANY
         *   Format/control char (Cf/Cc, and any unassigned/surrogate code
         *   point rejected by the charset below) ⇒ extraction FAILS (returns
         *   "") ⇒ [hasSymbolQuery] false ⇒ NEED_CLARIFICATION. The tool is a
         *   single-identifier tool: a multi-token remainder is never a valid
         *   query — `查 SymbolTable Foo 符號` clarifies rather than collapsing
         *   into `SymbolTableFoo`.
         * Step G — charset allow-list: every code point must be Unicode
         *   letter (L*) or number (N*), or one of `. _ - $ # @ / ( ) : , ; + = ~`.
         *   Anything else (e.g. `!`, `。`, emoji, unpaired surrogates) ⇒
         *   extraction FAILS ⇒ clarification. The remainder must ALSO carry at
         *   least one letter/digit code point.
         *
         * The extracted remainder is used intra-token verbatim (esc-escaped)
         * as the query argument — qualified names pass through losslessly:
         * `查 Foo.bar 符號` → `Foo.bar`, `查 std::vector 符號` / `查 Foo::Bar
         * 符號` → `::` preserved, `查 foo/bar.kt 符號` → `/` preserved,
         * `查 get(id) 符號` → `get(id)`, `查 3.14 符號` → `3.14`,
         * `查 3,14 符號` → `3,14`, `查 中文變數 符號` → `中文變數`.
         * Intra-token `(`/`)` are admitted without balance checking
         * (`查 (Foo 符號` → FAST `(Foo`) — inherited from the symbol-aware
         * class, harmless (allow-list + non-injection apply), accepted.
         * Fullwidth letters/numbers (L*, N*) pass the allow-list verbatim
         * (unnormalized — `Ｆｕ` stays `Ｆｕ`), consistent with stage-1.
         * Digit-only remainders
         * anchor the meaningful-remainder gate (load-bearing for
         * `查 3.14 符號` / `查 42 符號` — both FAST, accepted).
         *
         * Accepted residuals (recorded, safe direction, pinned by tests):
         * - CJK over-strip: 查理 → 理 (the leading 查 is the lookup verb, the
         *   remainder 理 looks like a symbol name) — accepted, fail-safe
         *   direction (a mis-query, never a wrong execution).
         * - 「特別」 is not an opener (starts with 特) ⇒ no-match DENIED;
         *   「特別找」likewise never reaches this path.
         * - `查Symbol`-class over-strips (e.g. a symbol literally named with a
         *   hint substring) remain accepted — CJK hints are substring-stripped
         *   by design.
         * - Intra-comma is preserved: `查 3,14 符號` → `3,14` — the comma is a
         *   legal intra-token character now (never edge-stripped mid-token).
         * - Anything not matching the allow-list (step G) or still carrying
         *   whitespace (step F) ⇒ clarification, never a rewritten query.
         * - `3.14`-class names keep their `.`; `get(id)` keeps `( )`;
         *   `foo/bar.kt` keeps `/`; `std::vector` keeps `::`.
         * - `查 a b 符號` strips the leading connective `a ` (another token
         *   follows) leaving `b` — article-stripping at a cue position, the
         *   same mechanic as `a Foo` → `Foo`; the two tokens are indistinguishable
         *   at this position and the rewrite is deterministic (pinned).
         *
         * Invalid-charset / multi-token remainders never execute: they fail
         * extraction (empty) and route NEED_CLARIFICATION via
         * [missingQuestionFor] ("請問要查哪個符號？").
         */
        private val SYMBOL_OPENER_CJK = Regex(
            "^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\\s，,]*)?(?:查|找|尋|搜|詢|寻|询)[\\s，,]*(?:一下)?[\\s，,]*",
        )
        private val SYMBOL_OPENER_EN = Regex(
            "^(?:please[\\s,]+)?(?:find|search|lookup|locate)\\b[\\s,]*(?:for\\s+|the\\s+|a\\s+|an\\s+)?",
            RegexOption.IGNORE_CASE,
        )
        private val SYMBOL_OPENER_CMD = Regex("^symbols?\\b[\\s]*", RegexOption.IGNORE_CASE)
        private val SYMBOL_KEYWORD = Regex("\\bsymbols?\\b", RegexOption.IGNORE_CASE)
        private val SYMBOL_CJK_HINT = Regex("符號|符号|函式|函数")
        private val SYMBOL_CJK_FILLER = Regex(
            "幫我|這個|那個|一下|請|查|找|尋|搜|詢|什麼|什么|何|是|的|要|用|如何|怎麼|怎么|意思|為什麼|为什么|說明|説明|講解|讲解|介紹|介绍|教程",
        )
        private val SYMBOL_LEADING_CONNECTIVE = Regex(
            "^(?:for|the|a|an|named|called|of|to)\\s+(?=\\S)",
            RegexOption.IGNORE_CASE,
        )
        private val SYMBOL_EDGE = Regex(
            "^[\\s，。、？?：:「」『』“”\"'（）]+|[\\s，。、？?：:「」『』“”\"'（）]+$",
        )
        private const val SYMBOL_ALLOWED_PUNCT = "._-${'$'}#@/():,;+=~"

        private fun isQueryLetterOrNumberType(type: Int): Boolean = when (type) {
            Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
            Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }

        private fun isQueryCodePointAllowed(cp: Int): Boolean {
            if (isQueryLetterOrNumberType(Character.getType(cp))) return true
            return cp <= 0x7E && SYMBOL_ALLOWED_PUNCT.indexOf(cp.toChar()) >= 0
        }

        private fun extractSymbolQuery(text: String): String {
            var s = text.trim()
            // Step A — anchored openers (each applied at most once a position;
            // sequential so `查 find 符號` reduces to empty and clarifies).
            s = SYMBOL_OPENER_CJK.replaceFirst(s, "").trim()
            s = SYMBOL_OPENER_EN.replaceFirst(s, "").trim()
            s = SYMBOL_OPENER_CMD.replaceFirst(s, "").trim()
            // Step B — symbol keyword tokens and CJK hint substrings (global).
            s = SYMBOL_KEYWORD.replace(s, "")
            s = SYMBOL_CJK_HINT.replace(s, "")
            // Step C — CJK prose fillers (substring, as accepted before).
            s = SYMBOL_CJK_FILLER.replace(s, "")
            // Step D — leading EN connectives, repeatedly, only when another
            // token remains after the connective.
            while (true) {
                val t = s.trim()
                val m = SYMBOL_LEADING_CONNECTIVE.find(t)
                if (m == null) {
                    s = t
                    break
                }
                s = t.substring(m.range.last + 1)
            }
            // Step E — edge trim only, repeatedly.
            while (true) {
                val t = s.trim()
                val u = SYMBOL_EDGE.replace(t, "")
                if (u == t) {
                    s = u
                    break
                }
                s = u
            }
            // Step C′ — CJK interrogative particles （嗎|吗|呢） anywhere in the
            // remainder ⇒ extraction FAILS (empty) ⇒ NEED_CLARIFICATION
            // (fail-closed; never stripped, never part of a plausible query).
            if (s.contains('嗎') || s.contains('吗') || s.contains('呢')) return ""
            // Step F — empty / whitespace / Cf / Cc (and anything the charset
            // rejects) ⇒ invalid ⇒ return "" so the clarification gate fires.
            if (s.isEmpty()) return ""
            var hasLetterOrDigit = false
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val type = Character.getType(cp)
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
                    type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt()
                ) {
                    return ""
                }
                if (!isQueryCodePointAllowed(cp)) return ""
                if (isQueryLetterOrNumberType(type)) hasLetterOrDigit = true
                i += Character.charCount(cp)
            }
            // Step G — at least one letter/digit must anchor the query.
            return if (hasLetterOrDigit) s else ""
        }

        /**
         * Meaningful-remainder predicate: the stripped remainder must contain
         * at least one Unicode letter or digit — punctuation-only remainders
         * are not symbol names and must never execute.
         *
         * The predicate is BMP-Char-based: `isLetterOrDigit()` is evaluated per
         * UTF-16 code unit, so an astral-only remainder (e.g. 𠮷 U+20BB7, whose
         * two surrogate units are neither letters nor digits) cannot anchor a
         * query — fail-safe direction. The esc test pins passthrough for a
         * paired astral letter embedded in a BMP-letter token instead (a
         * multi-token remainder would clarify before reaching here).
         *
         * This predicate is intentionally stricter than Step G's code-point
         * letters/numbers test inside [extractSymbolQuery] (it rejects
         * No-category glyphs and astral letters/digits, e.g. ① U+2460, U+1D7D8);
         * the stricter of the two decides — fail-safe direction, pinned by
         * the `查 𠮷 符號` clarification pin.
         */
        private fun hasSymbolQuery(text: String): Boolean =
            extractSymbolQuery(text).any { it.isLetterOrDigit() }

        private fun symbolArgs(text: String): String {
            val q = extractSymbolQuery(text)
            return "{\"query\": \"${esc(q)}\", \"limit\": ${LspTools.DEFAULT_LIMIT}}"
        }

        // NFKC + 白名單（whitelist）正規化樣式：非（非 Lm）字母的一段會被整體吃掉，
        // 比黑名單更完整——Java 黑名單無法窮舉 `\p{Cc}`/組合字標記/破折號/符號/
        // PUA 等近 19 萬碼點。removed pass 保留「非 Lm 字母」以外一律刪除
        // （CJK 被拆開的否定詞接合回連續 hint）；spaced pass 折為單一空白（`\b`
        // 因此保持完整，全形英文 NFKC 後的 `\bnever\b` 與多空白英文同樣可匹配）。
        // 兩 passes 皆為 fail-safe 方向。
        // removed：`[^\p{L}]+` 一段吃掉所有非字母（含全部 Nd/No 數字、組合標記
        // Mn/Mc/Me、符號 S、標點 P、Cc/Cf、PUA）；`\p{Lm}+` 吃掉 modifier letters
        // （`ˇ` U+02C7、`ˉ` U+02C9、阿拉伯 tatweel U+0640、`々` U+3005、延長音符
        // U+30FC——400+ 碼點），否則 `別ˇ查`／`別ˉ查`／`別ـ查`／`別々查` 會把否定詞
        // 拆開逃過偵測。諺文 filler（U+115F/U+1160 為 Lo 字母，NFKC 把 U+3164/
        // U+FFA0 折為 U+1160）非 Lm——顯式 `[\u115F\u1160]+` 剝離，否則 `別ㅤ查`
        // 會逃過正規化。`[A-Za-z0-9]+` 保留：`不™查` NFKC→`不TM查`（ASCII 字母），
        // `[^\p{L}]+` 吃不到，非剝離則無法接合回 hint；此支同時吃掉 ASCII 數字（冗餘，
        // fail-safe 方向）。
        // spaced 維持原樣不動（須保住 ASCII `\b` 以匹配英文 hint；它本就保留
        // `\p{N}` 與 ASCII 字母，EN hint 由本 pass 以 `\b`-錨定匹配）。
        private val NEGATION_REMOVED = Regex("[^\\p{L}]+|\\p{Lm}+|\\p{Nl}+|[A-Za-z0-9]+|[\\u115F\\u1160]+")
        private val NEGATION_SPACED = Regex("[^\\p{L}\\p{N}]+|[\\u30FC\\u115F\\u1160]+")

        /**
         * NFKC + whitelist-normalized negation matching: negation hints are
         * tested against THREE texts —
         * (1) the raw text,
         * (2) the NFKC-normalized text with every run of non-letter code points
         *     (`[^\p{L}]+` — ALL digits Nd/No, marks Mn/Mc/Me, symbols S,
         *     punctuation P, Cc/Cf, PUA), every run of modifier letters
         *     (`\p{Lm}+`, e.g. `ˇ` U+02C7, `ˉ` U+02C9, tatweel U+0640,
         *     `々` U+3005, U+30FC), the Lo hangul fillers `[\u115F\u1160]+`
         *     and every run of ASCII alphanumerics REMOVED — keeps only
         *     non-Lm letters, which is all the CJK/English hints need
         *     (`別 查` /
         *     `別　查` / `別，查` / `不\u200B查` / `別\uFE0E查` / `別-查` /
         *     `別😀查` / `不2查` / `別¹查` / `別ˇ查` / `不\u09E8查` all rejoin into a
         *     contiguous hint;
         *     fullwidth Latin/digit negations strip out entirely here — CJK
         *     rejoin is what this pass is for),
         * (3) the same with non-letter/non-digit runs folded to a single space
         *     (ASCII alphanumerics KEPT so `\b` stays intact: fullwidth-after-
         *     NFKC English and multi-space English match there too —
         *     belt-and-braces).
         * A blacklist of separator classes can never be complete (Cc/marks/
         * dashes/symbols/Lm/Lo/PUA span ~190k code points), hence the whitelist
         * approach. Both passes push in the fail-safe direction (extra
         * clarification, never extra execution). Modifier letters (Lm — e.g.
         * U+30FC, U+02C7, U+02C9, U+0640, U+3005) are stripped by `\p{Lm}+`;
         * the Lo Hangul fillers (U+115F/U+1160 — U+3164/U+FFA0 fold in via
         * NFKC) are removed explicitly for the same reason.
         * The `查詢` compound is covered by substring match of 別查/不查. 「特別找」為 no-match DENIED
         * （非命令式句首、非准入、原因為 no-match，永遠不會 FAST，永遠不會執行）——pinned。
         */
        // [isNegated] 同命三個文本（raw / NFKCremoved / NFKCspaced）；A1 保守否定閘。
        // 兩表互補（COMPLEMENTARY），不得刪除單顆粒集或四個通用禁止詞：[NEGATION_MARKERS]
        // 收單一顆粒集（不 沒 没 別 别 勿 莫 嫑 否 無 无 免 未 毋 休）＋英文 \b 集
        //（`no`/`not`/`never`/`don'?t`/`do not`/`stop`/`cancel`/`refus\w*`/`avoid\w*`/`skip\w*`/`without`
        // ＋#3 B3 re-review：`forbid\w*`/`prohibit\w*`/`ban\w*`/`postpone\w*`/`suspend\w*`/`defer\w*`/
        // `den[iy]\w*`/`hold\s*off`）；[NEGATION_HINTS]
        // 收多動詞複合否定與通用禁止詞，其獨有載荷 = {取消,停止,禁止,算了} + 18 條（拒絕/暫緩/延緩/延後/推遲/延遲/延期/推後/寬限/延宕，
        // 繁簡對稱——皆不含單顆粒旗標，非 MARKERS 子集合；其餘中文複合詞與英文集與 MARKERS 重複，可清理但非必須）。
        // 兩表皆 OR 同命同文本——任何否定詞/不確定詞存在即澄清，絕不執行（封閉表不
        // 完整是既定限制，不再宣稱；任一表皆不得以涵蓋另一表為由刪除）。
        private fun isNegated(text: String): Boolean {
            val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val removed = nfkc.replace(NEGATION_REMOVED, "")
            val spaced = nfkc.replace(NEGATION_SPACED, " ").trim()
            return containsAny(text, NEGATION_HINTS) ||
                containsAny(text, NEGATION_MARKERS) ||
                containsAny(removed, NEGATION_HINTS) ||
                containsAny(removed, NEGATION_MARKERS) ||
                containsAny(spaced, NEGATION_HINTS) ||
                containsAny(spaced, NEGATION_MARKERS)
        }

        private fun hasExplicitAlarmTime(text: String): Boolean {
            // 明確但超出範圍的時間值（hour > 23、minute > 59，例如 `25點`／`7點99分`／
            // `7:99`）一律視為「無明確時間」⇒ missingQuestionFor 轉澄清，
            // 絕不以 coerceIn 夾值後執行（鬧鐘 25 點 是壞指令，不是 23 點）。
            val m = ALARM_TIME.find(text) ?: return false
            val hour = m.groupValues[1].toIntOrNull() ?: return false
            if (hour > 23) return false
            val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            return minute <= 59
        }

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
