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
 * reference expansion (template + the full `不/別/沒` × verb NEGATION table,
 * 繁簡對稱 + blank-query clarification). The 14 pinned negated probes are
 * now also checked behaviorally (sampling, not the full table):
 * `FastDispatchTest.newToolMustExpandVerbTableBeforeReady` probes the
 * sampled negated intents to deny the ready tool before FAST, not just
 * RULES membership.
 *
 * A1/A2 routing hardening (fail-opens closed, conservatively): the
 * `lsp.symbols` template no longer fires on mere hint presence — it now
 * requires an explicit lookup intent (single-word command form
 * `^symbol(s)?\s+\S`, or a lookup marker: CJK 查/找/尋/搜/詢 family or
 * `\b(find|search|lookup|locate)\b`; `where` deliberately excluded).
 * Question/knowledge utterances (`What is a symbol?`, `Explain symbols`,
 * `請解釋符號`, `符號有哪些種類`, bare `symbols`, `符號是Foo嗎`) fall through
 * to no-match DENIED (reasonCode null) — not clarification. The negation
 * gate is now a conservative ANY-presence gate ([NEGATION_MARKERS]): any
 * occurrence of 不/沒/別/勿/莫… or English negation words clarifies over
 * the same three normalized texts — phrases are open-ended, so a closed
 * verb table cannot be complete; over-clarification is the fail-safe
 * direction (the single ready tool is READ-only).
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
        val projection = projections.getValue(tool.name)
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
        for ((toolName, hints, args) in RULES) {
            if (containsAny(text, hints)) {
                // A2: the symbol template needs an explicit lookup intent in
                // addition to hint presence; otherwise it falls through to
                // no-match DENIED (reasonCode null), not clarification.
                if (toolName == LspTools.SYMBOLS_NAME && !hasSymbolLookupIntent(text)) continue
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
        // A2: hint membership is only the prefilter — the authoritative
        // test is [hasSymbolLookupIntent] (command form or lookup marker;
        // fullwidth ｓｅａｒｃｈ/ｆｉｎｄ do NOT match the EN marker — they
        // route no-match, same fail-safe direction, pinned by the
        // fullwidth-English tests).
        private val SYMBOL_HINTS = listOf(r("符號"), r("符号"), r("\\bsymbols?\\b"))
        // A2 explicit-lookup gate for the symbol template: either the
        // single-word command form (`symbol(s) <name>`, anchored at start)
        // or a lookup marker (CJK 查/找/尋/搜/詢 — 「查詢/查找/搜尋」 are
        // subsumed by their CJK syllables — or EN find/search/lookup/locate,
        // word-anchored). `where` is deliberately excluded: a bare
        // 「where is symbol Foo」 carries no lookup verb and over-clarifies
        // into no-match (accepted, conservative direction).
        private val SYMBOL_LOOKUP_MARKER =
            Regex("查|找|尋|搜|詢|寻|询|查询|查找|搜尋|搜寻|\\b(find|search|lookup|locate)\\b", RegexOption.IGNORE_CASE)
        private val SYMBOL_COMMAND_FORM = Regex("^symbols?\\s+\\S", RegexOption.IGNORE_CASE)
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
         * 收多動詞複合否定與通用禁止詞（取消/停止/禁止/算了＋不/別/没&貼動詞 等 176 條；
         * 本表相對 [NEGATION_MARKERS] 的獨有載荷 = `取消`/`停止`/`禁止`/`算了` 四個通用禁止詞
         * ——中文複合詞的每條子串皆含不/未/別等單顆粒，實為 MARKERS 的子集合；與 MARKERS
         * 英文集重複的 11 個 \b 詞）；
         * [NEGATION_MARKERS] 收單一顆粒集（不 沒 没 別 别 勿 莫 嫑 否 無 无 免）＋英文 \b 集。
         * [isNegated] 將兩表 OR 同命三個文本：否定詞片語是開放集合，
         * 封閉表不可能完整，故不猜——命中即澄清（過度澄清為 fail-safe 方向）。
         * 「特別找」必澄清（先前即如此釘住）；「特別」/「别人」後接任意模板動詞
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
         * 屬 fail-open 候選，監測留待 Phase 2/3）。其他模板動詞（擷／安排／帶／切／靜音）
         * 在其工具把 executionReady 翻轉前補入——不得提前宣稱涵蓋。
         * 否定偵測同時跑在原始文本、NFKC 正規化後「刪除非（非 Lm）字母字元（含所有數字）與 ASCII 英數字」的文本、以及
         * 「非字母/數字字元→空白」的文本上（見 [isNegated]），故 `別 查`／`不\u200B查`／
         * `不2查`／全形英文（`ｎｅｖｅｒ`）之類插入分隔符、組合字標記、Cf 格式符或寬度混淆的
         * 形式同樣不執行（澄清或 no-match DENIED）。
         * 已知過度澄清殘餘：「特別找」含「別找」，會被誤轉澄清——屬安全方向
         *（寧可澄清、絕不執行），可接受並以測試釘住。
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
         *       （`never`/`don'?t`/`do not`/`stop`/`cancel`/`refus\w*`/`avoid\w*`/`skip\w*`/`without`/`no`/`not`）；
         *       `cannot` 類及未列舉動詞不涵蓋；
         *   (d) 全形-only 符號 hint 不會路由（SYMBOL_HINTS 比對原始文本，安全方向）；
         *   (e) `符號是Foo嗎` → no-match（A2 起：無 lookup marker，不進模板，reasonCode null）；
         *       `特別找` 過度澄清（pinned）；`查理`→`理` 過度剝離（pinned）；
         *       `意思是符號` → no-match（A2：無 lookup marker，reasonCode null）；
         *   (f) CJK 異體字碼點（查 vs 査）未涵蓋。
         * - 含 lookup marker 之否定句仍轉澄清（marker 命中模板後閘才會跑）；
         *   無 marker 之否定句（如 `別問 Foo 符號`、`not symbols`）直接
         *   no-match DENIED——皆為 DENIED、絕不執行。
         * - `查 是 符號` → clarify；`查 要 用 符號` → clarify（marker 命中，
         *   剝離後無有意義殘餘）。無 marker 者一律 no-match：
         *   `什麼是符號`、`為什麼要用符號`、`符號是什麼`、`符號的意思`、
         *   `符號有哪些種類`、bare `symbols`、`What is a symbol?`、`Explain symbols`
         *   （A2：不問模板語意，只問 lookup intent 的兩段式）。
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
        // holds the single-particle set （不 沒 没 別 别 勿 莫 嫑 否 無 无 免） plus the
        // English \b set; [NEGATION_HINTS]
        // holds the multi-verb compound negations plus general forbiddens, whose unique
        // load-bearing contribution is {取消,停止,禁止,算了} (its Chinese compounds and EN
        // entries are subsets of this gate). The single-particle set and those four words
        // are load-bearing and must not be deleted; [isNegated] ORs both
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
            r("無"), r("无"), r("免"),
            r("\\bno\\b"), r("\\bnot\\b"), r("\\bnever\\b"), r("\\bdon'?t\\b"), r("\\bdo not\\b"),
            r("\\bstop\\b"), r("\\bcancel\\b"), r("\\brefus\\w*\\b"), r("\\bavoid\\w*\\b"),
            r("\\bskip\\w*\\b"), r("\\bwithout\\b"),
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
            // A2: hint alone is not enough — the match site requires
            // [hasSymbolLookupIntent] (see matchTemplate).
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
        // dev.librepocket.mcp.McpWire.esc — the file to sync with. Control-char
        // rule changes and the lone-surrogate handling must be made in BOTH,
        // or extract a shared helper — do not silently diverge.
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
         * B3 symbol-query strip tokens: hint words removed from the intent to
         * isolate the symbol name. Substring-based (NOT token-based): every
         * alternative matches as a plain substring, so CJK names embedding hint
         * chars are over-stripped too — a known residual, e.g. 「查理」 → 「理」
         * (accepted, pinned by test; a READ-only echo mis-query is harmless).
         * Latin tokens are word-anchored (`\bsymbols?\b`, `\blsp\b`), so
         * `SymbolTable` / `getSymbol` / `LSPClient` survive intact (an
         * unanchored `symbol` would shred the `Symbol` inside `getSymbol`).
         * Meta-question tokens (什麼/什么/為什麼/为什么/是什麼/是什么/怎麼/怎么/如何/意思/嗎/吗/呢)
         * strip as well, so a marker-bearing utterance that reduces to empty
         * (e.g. `查 是 符號`) clarifies rather than executing. Bare
         * `符號是什麼？` itself carries no lookup marker, so under A2 it does not
         * reach this path at all (no-match DENIED — pinned by test). The bare
         * meta-question verbs 是/的/要/用 are stripped
         * too since the B3 re-review:
         * 「查 是 符號」 / 「查 要 用 符號」 strip to empty and clarify — but note
         * A2: utterances WITHOUT a lookup marker never reach this path at all
         * （`什麼是符號`、`為什麼要用符號`、`符號的意思` 等一律 no-match DENIED）；
         * clarify 只保留帶 marker 者。
         * Accepted over-strip residual class (recorded, safe direction):
         * 「符號是Foo嗎」 → query=Foo if it reached extraction (arguably a
         * legitimate query), and symbol names containing 是/的/要/用 are shredded
         * — both fail safe (a mis-query or a clarification, never a wrong
         * execution); this over-strip is accepted and documented here rather
         * than fixed with token boundaries. A2: 「符號是Foo嗎」 no longer reaches
         * this path — without a lookup marker the symbol template does not
         * match at all (no-match DENIED).
         * The meaningful-remainder predicate lives in
         * [hasSymbolQuery]: at least one Unicode letter/digit must survive,
         * so punctuation-only remainders (e.g. 「查 - 符號」 → 「-」) clarify too.
         * A name exactly equal to joined hint tokens also strips to blank and
         * clarifies (fail-safe direction).
         */
        private val SYMBOL_QUERY_STRIP =
            Regex("幫我|這個|那個|一下|請|查|找|尋|搜|詢|符號|符号|函式|函数|為什麼|为什么|是什麼|是什么|什麼|什么|怎麼|怎么|如何|意思|嗎|吗|呢|是|的|要|用|\\bsymbols?\\b|\\blsp\\b|[\\s，。、？?！!：:「」『』\"'（）(),.;]", RegexOption.IGNORE_CASE)

        private fun extractSymbolQuery(text: String): String =
            SYMBOL_QUERY_STRIP.replace(text, "").trim()

        /**
         * Meaningful-remainder predicate: the stripped remainder must contain
         * at least one Unicode letter or digit — punctuation-only remainders
         * are not symbol names and must never execute.
         *
         * The predicate is BMP-Char-based: `isLetterOrDigit()` is evaluated per
         * UTF-16 code unit, so an astral-only remainder (e.g. 𠮷 U+20BB7, whose
         * two surrogate units are neither letters nor digits) cannot anchor a
         * query — fail-safe direction. The esc test uses 𠮷 together with a
         * BMP anchor (`Foo`) for the same reason.
         */
        // A2 explicit-intent predicate: single-word command form anchored at
        // start (`symbol(s) <name>`), or any lookup marker anywhere. `where`
        // intentionally NOT a marker — English question phrasings without a
        // lookup verb route no-match (documented over-clarify → no-match).
        private fun hasSymbolLookupIntent(text: String): Boolean =
            SYMBOL_COMMAND_FORM.containsMatchIn(text) || SYMBOL_LOOKUP_MARKER.containsMatchIn(text)

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
         * The `查詢` compound is covered by substring match of 別查/不查. Over-clarify residual: `特別找` ⊃
         * `別找` stays mis-routed to clarification — accepted as the safe tail
         * (never executes).
         */
        // [isNegated] 同命三個文本（raw / NFKCremoved / NFKCspaced）；A1 保守否定閘。
        // 兩表互補（COMPLEMENTARY），不得刪除單顆粒集或四個通用禁止詞：[NEGATION_MARKERS]
        // 收單一顆粒集（不 沒 没 別 别 勿 莫 嫑 否 無 无 免）＋英文 \b 集；[NEGATION_HINTS]
        // 收多動詞複合否定與通用禁止詞，其獨有載荷 = {取消,停止,禁止,算了}（中文複合詞與英文集
        // 實為 MARKERS 的子集合/重複，可清理但非必須）。
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
