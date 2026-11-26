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
 * RULES membership. 路由順序見下方「matchTemplate 路由順序」段：symbol 前閘最先
 * （stage-8.5）；SLOW_HINTS 次之；RULES 最後，`查 AlarmManager 符號` 綁 lsp.symbols——由
 * `FastDispatchTest.codeIdentifierSymbolNamesRouteToLspSymbols` 釘住。
 *
 * A1/A2/stage-1 routing hardening (fail-opens closed, conservatively): the
 * `lsp.symbols` template no longer fires on mere hint presence — it matches
 * ONLY an **explicit lookup command** under the affirmative-command
 * admission model ([isAffirmativeSymbolCommand], #19 B3 stage-1):
 * 句首（可選 EN `please`／CJK 請|麻煩|帮我|幫我|给我|給我|请 前綴）必須是查詢動詞
 * （find/search/lookup/locate，或 CJK 命令式查詢動詞符元 SYMBOL_CJK_COMMAND_TOKEN：
 * 複合 查詢|查询|搜尋|搜寻|搜索|查找|尋找|寻找|找尋|找寻 longest-match-first 排前、
 * 單字 查/找/尋/搜/詢/寻/询 殿後；Java 交替同位置有序首中 ⇒ 複合先＝最長匹配）或
 * `symbol(s) <name>` 命令形；句中出現授權/條件授權子句（命令式 opener +
 * 結構性授權/條件子句否決：only after/when/if/until/once/before/with/upon …、
 * after|until|unless|once|when|while|if|before|since|whenever|as|because|
 * provided|upon|following|pending|with|subject to|prior to|given|on 後接可選受詞/限定詞（0–3 個）再接
 * approve/agree/consent/confirm/say/allow/release/permit/ok/ready/permission/
 * review 等詞幹（gerund 與過去式一併涵蓋）、wait for/until/till、hold on/off、
 * not yet、CJK 結構性條件句（等…（有界 possessor）同意、（後）再/才、只有…才等有界框架、
 * 以及 stage-9.1 arm H deferred-action nucleus `再|才`＋動作動詞核（做查跑說寫讀處理執行更新重啟處辦辦理，
 * stage-10.1 擴充 通知|告訴|通報|報告|匯報|彙報|回信|提醒，共 20 個）、
 * arm I conditional-framing nucleus 如果|假如|倘若|要是|的話、
 * 以及 stage-10.1 arm J wait/until＋completion nucleus 等|待|直到|直至…放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知，
 * 與 arm K bare temporal nucleus 明早|明晚|明天|明日|後天|后天|大後天|大后天|下週|下星期|下周|下下週|下下周|下個月|下个月|月底|年底|日後|日后|幾天後|几天后|過幾天|过几天|稍後|稍后|晚點|晚点|等下|待會|待会|待會兒|待会儿|回頭|回头|隔天|隔日|次日|翌日|翌週|翌周|翌年，
 * 皆任意位置、不依賴 consent 動詞識別）等），
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
 * - 殘餘（accepted, by design；Re-review #7 複核修訂）：CJK 無窮盡的兩字動詞形——
 *   首字為單字 opener（查找尋搜詢寻询）、次字為非 opener CJK 字元者（`詢問`/`查看`/
 *   `查閱`/`查驗`/`尋覓`/`尋思`/`搜羅`/`找遍`…無窮類）一律視為剩餘識別字原樣執行
 *   （如 `詢問Foo符號` ⇒ FAST query=`問Foo`）——READ-only；查詢不改寫 verbatim；
 *   記錄殘餘（pinned），非 no-match、不宣稱否決；`質詢`（首字 `質` ∉ opener）
 *   → no-match DENIED。字典無窮不可封閉枚舉，刻意不再嘗試。
 * - Cf/default-ignorable-split normalization 已納（本輪）：授權子句/教學標記在
 *   raw 文本與兩個正規化探針（NFKC + Cf 摺空白；NFKC + Cf 刪除）上取聯集測試；
 *   CJK 條件框架（等…possessor…consent 動詞）亦同樣以三文本取聯集測試——
 *   字內部 Cf 拆分（如 `等\u200B主管批准再做`）亦否決。
 * - noun-form/inflected consent clauses 已納結構性錨定（本輪）：連接詞集
 *   （含 since|whenever|as|while|because|provided|upon|following|pending|with|subject to|prior to|given|on）
 *   + 0–3 個可選受詞/限定詞 + 動詞/詞幹槽（gerund/過去式/第三人稱涵蓋）。
 * - 已知殘餘（accepted, open-world）：任意非槽詞 possessor（含專有名詞：after Sam approves、
 *   on Foo approve）亦不收——anchor-or-nothing。
 * - 已知殘餘（accepted, anchor-or-nothing 一致性）：`with readiness`／`readiness\b`
 *   與 bare `pending`／`review` 之類 identifier 形態一致性接受。
 * - 殘餘：CJK 結構性條件句僅覆蓋所列框架（`等` ＋ 可選 possessor 槽——平坦 {0,64} 類（non-atomic；
 *   其兩個 delimiter 槽為原子群組 `(?>…)`，possessor 槽本身不原子，須回退出動詞左端字元），
 *   涵蓋 \p{L}/\p{N} ＋ ASCII 識別字標點（`._-$#@/():,;+=~`）＋ \s（及 NFKC 探針摺疊之半全形空白）＋ 半/全形逗號（`,` `，`）＋
 *   常見 CJK 句讀（`。、！？!?；;：:．‥…`）＋ 全形斜線 `／`（U+FF0F）／全形底線 `＿`（U+FF3F），
 *   空白/半全形逗號仍可夾 possessor 前後；possessor 內部可夾斜線/識別字標點/空白/CJK 句讀——
 *   `查Foo符號等資安/法務批准`（半形斜線）／`查Foo符號等資安／法務批准`（U+FF0F 全形斜線）／
 *   `查Foo符號等CISO/CEO同意`／`查Foo符號等資安_法務批准`／`查Foo符號等主管-法務批准`／
 *   `查Foo符號等資安 / 法務批准`（斜線兩側夾空白）／`查Foo符號等我回覆再做`／`查Foo符號等你回复`／
 *   `查Foo符號等經理答覆後再做`／`查Foo符號等經理答复再做`／`查Foo符號等董事會回覆` 此類
 *   「尚待批准／回覆」形態——所列形態與已知變體（含斜線 possessor、回覆族動詞、動詞內 ≤16 插入
 *   （CONSENT_VERBS_CJK_FLEX）、Cf 拆分）皆否決；未列示 consent 動詞（執行，fail-OPEN）及
 *   動詞內 >16 插入仍為已知殘餘（見下節「未列示 consent 動詞」與「動詞內 >16 插入」，
 *   皆 READ-only／未接線）；stage-9.1 起 arm H/I 顆粒（`再|才`＋動作動詞核／如果|假如|倘若|要是|的話）
 *   於任意位置出現即否決，不受此框架形狀所限（deferred-action／conditional-framing 形態）；
 *   stage-10.1 起 arm J/K 顆粒（wait/until＋completion-endpoint／裸時間顆粒）同樣任意位置即否決；
 *   未列 possessor 亦皆收（`等主管批准`／`等老闆同意`／`等經理核准`／
 *   `等董事會點頭`／`等董老闆批准`／長組織名如 `等安全審核委員會主席批准`／`等國家安全委員會批准`／
 *   9/12 位數字 `等123456789批准`／`等123456789012批准` 皆否決；`等我們同意`／`等你點頭` 仍匹配））＋ consent 動詞集
 *   同意|核准|核準|批准|確認|确认|許可|许可|點頭|点头|回覆|回复|答覆|答复（繁簡對稱；單一事實來源
 *   CONSENT_VERBS_CJK_TOKENS，由 tokens 推導 CONSENT_VERBS_CJK_FLEX）、
 *   consent 動詞（CONSENT_VERBS_CJK_FLEX：動詞內 ≤16 個 INS 插入 INS＝`[\p{L}\p{N}._-$#@/():,;+=~]`；
 *   INS 排除 \s 與 U+FF0C（全形逗號）；刻意包含 U+002C（半形逗號）——平坦、有界 {0,16} 缺口，
 *   與 possessor 由動詞首字元錨隔開（無 delimiter 原子性互動；平坦已驗證）；Lo filler 含
 *   U+115F/1160/3164/FFA0 ⊆ \p{L}；插入上限與 arm D′ 一致，security-audit M2）＋ 可選分隔符 [\s，,.;；:：。、！？!?．‥…了過_]*（半/全形空白、`,` `，` `.` `。` `;` `；` `:` `：` `、` `！` `？` `!` `?`、`．` U+FF0E `‥` U+2025 `…` U+2026、下劃線 `_`，以及助詞 `了` `過`）
 *   ＋ 必填 後-family 後綴（之後|之后|以后|以後|後|后）＋ 可選分隔符 [\s，,.;；:：。、！？!?]*（同上，不含 `了` `過`）＋ 可選 再|才 尾標（arm A），
 *   或 consent 動詞 ＋ 可選分隔符 [\s，,.;；:：。、！？!?．‥…了過_]*（同上）＋ 必填 再|才 尾標（arm B），
 *   只有/只許/只许/只允…才之有界缺口）；EN 開放詞彙不宣稱完備；`\bban\w*\b` 之類（如 `symbols Foo banana` → NEED_CLARIFICATION）、`特別找`
 *   之類普通詞仍收為否定系副作用（recorded, fail-safe）；裸名詞絕不單獨收錄（anchor-or-nothing）；EN 連接詞詞幹槽已納 CJK 許可詞幹槽
 *   CONSENT_VERBS_CJK_FLEX——錨定於 EN 連接詞之後者否決；EN/CJK 交集其餘仍 open-world recorded。
 * - arm D′（動詞＋ CJK/ASCII 字母數字＋助詞 `了過`＋識別字標點/空白插入 ≤16 個字元＋ 再|才）：`批准Ｄ再做`（全形 D NFKC 折回）／`批准五再說`／
 *   `同意白拿了再做`／`批准主張再做`／`批准主張同意再做`／`批准Ｄ_Ｄ再做`／`批准a-b再做`／`批准Ｄ/Ｄ再做`／`批准Ｄ;Ｄ再做`／`批准Ｄ#Ｄ再做` 皆否決——
 *   L/N/了過/識別字標點/半全形空白插入語全封（stage-8.1 O1：含 `／` U+FF0F／`＿` U+FF3F）；不取 ≥17 字（過長歧義為
 *   識別字可能性上升、過度否決成本逆轉，{0,16} 為審裁下限）；帶空白 ⇒ whitespace 閘；全形英文撇號等
 *   fullwidth 字母（NFKC 折回 ASCII）亦收，所字形不再被識別字規避。`批准然後再做`（然後＝2×\p{L}）已由本臂
 *   否決（`然`/`後` 皆為 Lo）——舊「然後未列 linker」殘餘記錄自 D′ 起作廢。
 *   （可接受的過度否決記錄：黏合 CJK 識別字 vs 同意子句無法僅憑語法分離——例如 `查批准的再生符號`
 *   被 arm D′ 否決（span 到達 再）；fail-safe 方向，記錄）
 * - 已知殘餘（accepted, frame-local；Re-review #8 A1 修訂）：possessor 槽為 {0,64} 平坦類（non-atomic；
 *   其兩個 delimiter 槽為原子群組 `(?>…)`，possessor 槽本身不原子——須回退出動詞左端字元），
 *   （\p{L}/\p{N} ＋ ASCII 識別字標點 `._-$#@/():,;+=~` ＋ \s（及 NFKC 探針摺疊之半全形空白）＋ 半/全形逗號 ＋ CJK 句讀 `。、！？!?；;：:．‥…`
 *   ＋ `／` U+FF0F ／ `＿` U+FF3F；有界、無巢狀量化、無災難性回溯（possessor 槽不回溯出動詞左端字元之外；delimiter 為原子群組）（最壞多項式有界）），
 *   涵蓋現實組織名（含數字、中文、識別字標點與 CJK 句讀）；possessor 超過 64 個單位時框架不匹配 → fail-open
 *   （單 token 命令形 end-to-end；帶空白則不執行）；consent 動詞與 再|才 之間僅許分隔符/了過（arm A/B）
 *   或 ≤16 個 CJK/ASCII 字母數字＋助詞 `了過`（arm D′ 自身插入槽；動詞內插入 ≤16 見 FLEX）才否決——`批准Ｄ再做`（全形 D NFKC 折回）／`批准五再說`／
 *   `同意白拿了再做`／`批准主張再做`／`批准然後再做`（然後＝2×\p{L}，已為 arm D′ 所收）皆否決；
 *   仍殘餘者：possessor >64 之 `等…同意`（無再|才）——見下節，open-world 殘餘；
 *   動詞內 >16 插入（如 `批`+17 個插入字元+`准`）亦否決不了——見下節「未列示 consent 動詞」（FLEX 上限 16），fail-OPEN。
 *   {0,64} 槽與 consent 動詞集（含 FLEX）不巢狀量化。
 *   過度否決記錄（accepted fail-safe, do not "fix"）：possessor 內部斜線/識別字標點/空白/CJK 句讀
 *   （如 `等資安/法務批准`／`等CISO/CEO同意`／`等資安 / 法務批准`／`等主管-法務批准`／`等資安_法務批准`）
 *   新帶來的否決即為 A1 修正目標形態本身；任何超出該形態的黏合識別字過度否決一律接受——
 *   方向固定為 fail-safe（從不執行），不得以「收回」此類輸入為名放寬 possessor 槽。
 * - arm H（deferred-action nucleus；stage-9.1；Re-review #9 A1 [P1]；任意位置，CJK）：`(?:再|才)(?:做|查|跑|說|寫|讀|處理|執行|更新|重啟|處辦|辦理|通知|告訴|通報|報告|匯報|彙報|回信|提醒)`
 *   ——文中任意位置出現 `再|才` ＋ 有界動作動詞核（20 個 Lo 動詞：做查跑說寫讀處理執行更新重啟處辦辦理，
 *   stage-10.1 新增 通知|告訴|通報（通知/告訴 系）與 報告|匯報|彙報（報告 系）、回信（回覆系）、提醒（提示系）八個延後動作動詞）即否決；
 *   不依賴 consent 動詞識別 ⇒ 未列示 consent 動詞亦無法繞過；`查Foo符號等審批再做`／`查Foo符號等授權再做`／
 *   `查Foo符號明天再做`／`查Foo符號等審批後再做`／`查Foo符號明天再通知我` 皆否決（deny-by-admission；READ-only 亦從不執行）。
 *   動詞核為 common CJK action verbs 之有界集——不封閉宣稱：核外動詞（未列入此 20 個者）仍不匹配
 *   ⇒ fail-OPEN（open-world 殘餘，如實記錄，不宣稱封閉；見下節「未列示 consent 動詞」）。
 * - arm I（conditional-framing nucleus；stage-9.1；Re-review #9 A1 [P1]；任意位置，CJK）：`(?:如果|假如|倘若|要是|的話)`
 *   ——文中任意位置出現條件框架顆粒即否決；`查Foo符號如果我同意` 否決。`的話` over-deny 黏合識別字
 *   為 accepted fail-safe（記錄，不得「收回」）；arm I 同樣不依賴 consent 動詞識別。
 * - arm J（wait/until + completion nucleus；stage-10.1；Re-review #10 A1 [P1]；任意位置，CJK）：`(?:等|待|直到|直至).{0,16}?(?:放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知)`
 *   ——文中任意位置出現 等|待|直到|直至 ＋ 有界 {0,16} 平坦缺口（`.` 單一有界量化、無巢狀量化、無災難性回溯）
 *   ＋ 有名 completion-endpoint 集（放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知，11 個）即否決；
 *   `查Foo符號等我放行`／`查Foo符號等審核結束`／`查Foo符號直到審核結束`／`查Foo符號等我回覆` 皆否決
 *   （deny-by-admission；READ-only 亦從不執行）；不依賴 consent 動詞識別。
 *   過度否決記錄（accepted fail-safe, do not "fix"）：識別字本身含 等|待|直到|直至 × completion-endpoint
 *   （如 `查 等就結束 符號`）一律 DENIED——黏合 CJK 識別字 vs 等待完成句無法僅憑語法分離
 *   （與 arm H/I 同一接受類；識別字若不可查，使用者可改寫查詢）。
 * - arm L（person-anchored wait nucleus；stage-11.1；Re-review #11 A1 [P1]；任意位置，CJK）：`(?:等|待|直到|直至)(?:我|你|您|他|她|我們|我们|你們|你们|您們|您们|他們|他们|她們|她们|妳|妳們|妳们|大家|全員|全员|各位|老闆|經理|主管|主任|總監|副理|董事|總經理|執行長)`
 *   ——文中任意位置出現 等|待|直到|直至 ＋ 緊接其後之人稱字（我|你|您|他|她|我們|我们|你們|你们|您們|您们|他們|他们|她們|她们|妳|妳們|妳们|大家|全員|全员|各位|老闆|經理|主管|主任|總監|副理|董事|總經理|執行長，31 個）即否決；
 *   任何等待/直至顆粒「立即」接人稱字 ⇒ 外部動作延後 ⇒ DENIED；
 *   `查Foo符號等我簽核`／`查Foo符號等他簽核`／`查Foo符號等主管核對`／`查Foo符號等老闆允許` 皆否決
 *   （deny-by-admission；READ-only 亦從不執行）；不依賴 consent 動詞識別、不枚舉端點動詞
 *   （簽核/核對/允許/放行/核准… 皆由結構涵蓋）。
 *   平坦字面交替、零量化；無巢狀量化、無災難性回溯。
 *   過度否決記錄（accepted fail-safe, do not "fix"）：尾綴裸 `等我`（如 `查Foo符號等我`）及識別字本身含
 *   等|待|直到|直至＋人稱字（如 `查 等我 符號`／`查 等他 符號`）一律 DENIED——黏合 CJK 識別字 vs 等待句無法僅憑語法分離
 *   （與 arm H/I/J/K 同一接受類；識別字若不可查，使用者可改寫查詢）。
 * - arm K（bare temporal nucleus；stage-10.1；Re-review #10 A1 [P1]；任意位置，CJK）：`(?:明早|明晚|明天|明日|後天|后天|大後天|大后天|下週|下星期|下周|下下週|下下周|下個月|下个月|月底|年底|日後|日后|幾天後|几天后|過幾天|过几天|稍後|稍后|晚點|晚点|等下|待會|待会|待會兒|待会儿|回頭|回头|隔天|隔日|次日|翌日|翌週|翌周|翌年)`
 *   ——文中任意位置出現裸時間顆粒（明早|明晚|明天|明日|後天|后天|大後天|大后天|下週|下星期|下周|下下週|下下周|下個月|下个月|月底|年底|日後|日后|幾天後|几天后|過幾天|过几天|稍後|稍后|晚點|晚点|等下|待會|待会|待會兒|待会儿|回頭|回头|隔天|隔日|次日|翌日|翌週|翌周|翌年，41 個）即否決；
 *   `查Foo符號明天再通知我`（同時命中臂 H `再通知`）／`查Foo符號稍後通知`／`查Foo符號明天再通ㅤ知我`
 *   （U+3164 於 通/知 之間；stage-9.5 於子句探針先剝除 ⇒ 臂 H `再通知` 亦命中）皆否決
 *   （deny-by-admission；READ-only 亦從不執行）。
 *   過度否決記錄（accepted fail-safe, do not "fix"）：識別字本身含裸時間顆粒（如 `查 明日報表 符號`／`查 年底 符號`）一律 DENIED——
 *   時間顆粒 vs 識別字無法僅憑語法分離（與 arm H/I/J 同一接受類；識別字若不可查，使用者可改寫查詢）。
 *   EN 側（tomorrow／later 等）刻意不動、如實記錄（不宣稱 EN 封閉表）。
 * - arm M（cardinal-time-expression nucleus；stage-11.1；Re-review #11 A1 [P1]；任意位置，CJK）：`(?:(?:[0-9０-９一二兩两三四五六七八九十廿卅百零幾几千萬万]{1,5}|半)(?:個|多)?(?:天|日|週|周|星期|礼拜|禮拜|個月|个月|月|年|小時|小时|鐘頭|钟头|分鐘|分钟|秒|時|时|點|点|點鐘|点钟)(?:之|以)?(?:後|后))`
 *   ——文中任意位置出現 基數詞（`[0-9０-９一二兩两三四五六七八九十廿卅百零幾几千萬万]{1,5}` 平坦類，或 `半`）＋時間單位（天|日|週|周|星期|礼拜|禮拜|個月|个月|月|年|小時|小时|鐘頭|钟头|分鐘|分钟|秒|時|时|點|点|點鐘|点钟），可夾一枚 `個|多`
 *   ＋ 後|后 ⇒ 未來/延後時間表達式 ⇒ DENIED；
 *   `查Foo符號三天後再啟動`／`查Foo符號兩天後再測試`／`查Foo符號30天後再跑`／`查Foo符號半小時後移交`／`查Foo符號十天後再查` 皆否決
 *   （deny-by-admission；READ-only 亦從不執行）；不依賴 consent 動詞識別、不枚舉延後動詞
 *   （再啟動/再跑/再測試… 皆由結構涵蓋）。
 *   單一有界 {1,5} 平坦量化、無巢狀量化、無災難性回溯。
 *   過度否決記錄（accepted fail-safe, do not "fix"）：識別字本身含基數時間表達式（如 `查 三天後 符號`）一律 DENIED——
 *   時間表達式 vs 識別字無法僅憑語法分離（與 arm H/I/J/K/L 同一接受類；識別字若不可查，使用者可改寫查詢）。
 *   裸 `之後|以後|之後再`（無基數詞＋時間單位前綴）不納入（開放詞彙殘餘）；帶前綴之 `之|以` 後綴已由臂 M `之|以` infix 收錄（`三天以後`／`一年之後`／`三天以后`／`兩周以后` 皆已釘 DENIED）。
 *   arm H/I/J/K/L/M 於准入檢查（[isAffirmativeSymbolCommand] 之 PERMISSION_CONDITIONAL_CLAUSE gate，raw/spaced/joined
 *   三探針聯集）落地，與其他 arm 同一 clause gate、同閘否決——非於 extraction。
 * - 已知殘餘（accepted, open-world fail-OPEN；Re-review #8 安全審計 M3；stage-9.1 收窄；stage-10.1 再收窄）：未列示 consent 動詞
 *   （`審批`／`應允`／`承諾`／`認可`／`允許`／`授權`／`首肯`／`放行` 等近義詞族——列舉不盡，封閉清單不可能完備）
 *   且不含 arm H/I/J/K/L/M 顆粒（無 `再|才`＋有界動作動詞核、無 如果|假如|倘若|要是|的話、
 *   無 等|待|直到|直至 × completion-endpoint、無裸時間顆粒、
 *   無 等|待|直到|直至 × 人稱字、無基數時間表達式 × 後|后）者，
 *   一律不匹配條件句框架 ⇒ 不成立 clause-gate 否決 ⇒ 落其餘閘；是 FAST 即執行（fail-OPEN——與
 *   前閘「過度否決 fail-safe」前提相反，如實記錄之方向）。stage-9.1 起，`查Foo符號等審批再做`／
 *   `查Foo符號等授權再做` 等形因 arm H（`再|才`＋動作動詞核）於准入檢查否決，不再 fail-OPEN；
 *   stage-10.1 起，`查Foo符號等我放行`／`查Foo符號等審核結束`／`查Foo符號明天再通知我` 等形因
 *   arm J/K（wait/until＋completion-endpoint／裸時間顆粒）於准入檢查否決，亦不再 fail-OPEN；
 *   stage-11.1 起，`查Foo符號等我簽核`／`查Foo符號等我核對`／`查Foo符號等我允許` 等形因 arm L
 *   （person-anchored wait nucleus，等|待|直到|直至＋人稱字）於准入檢查否決；
 *   `查Foo符號三天後再啟動` 等形因 arm M（cardinal-time-expression nucleus，基數詞＋時間單位＋後|后）於准入檢查否決，亦不再 fail-OPEN；
 *   另 裸 `之後|以後|之後再`（無基數詞＋時間單位）仍 fail-OPEN（核外、不宣稱封閉——帶前綴之 `之|以` 後綴已由臂 M infix 收錄；arm L 需人稱錨定）；
 *   核外動詞/核外端點殘餘（動作動詞之無窮類 ∉ 20 個核內動詞；completion-endpoint 之無窮類 ∉ 11 個端點集，
 *   如 `撤銷`／`掛起` 等核外端點）仍 fail-OPEN；核內有界缺口之超出亦然——
 *   `等|待|直到|直至`＋>16 個單位缺口＋completion-endpoint（尤其 `放行`／`結束`／`完成`／`完畢`／`通過`／`通知`）
 *   不匹配臂 J ⇒ fail-OPEN（記錄，不宣稱封閉；缺口上限 ≤16 為 mandated 形狀，見臂 J 註解）。
 *   臂 L/M 之核外殘餘亦然（不宣稱封閉）：`週末`／`周末`／`月末`／`年末`／`月內`／`年內`／`週內`／`日內`／`以內`／
 *   `等`＋無人稱顆粒（如 `等簽核`／`等批示`——臂 L 需緊接人稱字）／組織錨定（如 `等貴公司核可`／`等貴司核准`，arm L 同為無人稱字 ⇒ 不命中）／
 *   `一年後的報告` 類名詞片語（臂 M 不區分時態位置，含 `一年後` ⇒ 臂 M 命中 ⇒ 是 over-deny、非 fail-OPEN；accepted fail-safe，列於臂 M 過度否決記錄，不得「收回」）；
 *   基數串皆可用 `[class]{1,5}` 之 ≤5-cut 子串匹配（`containsMatchIn` 子串語義）——`一千三百五十天後`（`三百五十天後`）、`二天後以後`（`二天後`）皆已由臂 M 逐位置命中 ⇒ DENIED，
 *   >5 位元基數串亦然（任意 ≤5 基數單位子串皆命中；本行取代先前「>5 單位仍 fail-OPEN」之誤記）；二天後以後 類混合框架亦同。
 *   真正仍 fail-OPEN 者僅剩上列列舉項／組織錨定／`等`＋無人稱顆粒（記錄，不宣稱封閉）。
 *   `幾個月後`／`幾個星期後`／`好幾個月後` 已由臂 M 收錄；`隔天`／`隔日`／`次日`／`翌日`／`翌週`／`翌周`／`翌年` 已由臂 K 收錄（41 顆粒）；
 *   `三天以後`／`一週之後`／`三天以后`／`兩周以后` 已由臂 M 之 `之|以` infix 收錄；`十一點後`／`一個小時後`／`半個鐘頭後`／`一時後`
 *   （`時` 納入單位，`一時` 慣用語 over-deny 為 accepted fail-safe）亦由臂 M 收錄；`两天后`（`两` 簡體基數）亦然。
 *   Arm M 對 M-category 類（Mn/Me，如 `三\u0301天後` 之 combining mark）不命中——實測端到端仍 DENIED（既有 fail-closed 後徑背擋；端到端 fail-open 之說僅指 arm M 本身顆粒類，非路由結果）／與 arm H/I/K 顆粒同一已記錄類，對稱記錄；
 *   Cf/Hangul filler 插入則由 joined/spaced 探針剝除後可見 arm M（三ㅤ天後 ⇒ `三天後` 命中）。Arms L/M 皆為 deny-only 新增分支，admit 方向不變更。
 *   臂內顆粒對 M/Cf 類插入不敏感（如 `明\u0301天` 夾 combining mark ⇒ arm K 不命中 ⇒ fail-OPEN；
 *   arm J 之 `.{0,16}?` 缺口為 M/Cf-tolerant，其餘臂 H/I/K/L/M 顆粒非）——fail-open 方向，對稱記錄、不宣稱封閉（stage-11.1 對稱擴充至 arms L/M，同一已記錄類）。
 *   以上皆如實記錄、不宣稱封閉。目前唯一 executionReady 工具為 lsp.symbols，
 *   READ-only、未接線——以本 lane 現況（唯一 executionReady 為 READ-only、未接線）之實害為零；含 consent 動詞內 >16 插入者亦同（FLEX 上限 16，對稱 arm D′）。
 *   若本 lane 未來接線非 READ 工具，封閉 consent 動詞清單必須先重新審視——已規劃的補救方向為
 *   對 `等…` 框架加超集 deny-guard（open-world deny-by-default 之一致化），非逐一枚舉動詞。
 * - 已知過度否決（accepted fail-safe, do not "fix"；stage-11.1；Re-review #11 A1 [P1]）：arm L 人稱錨等待核
 *   （如 `查 等我 符號`／尾綴裸 `等我`——`查Foo符號等我` 亦 DENIED）與 arm M 基數時間核
 *   （如 `查 三天後 符號`）過度否決黏合識別字，一律 DENIED——黏合 CJK 識別字 vs 等待句／時間表達式
 *   無法僅憑語法分離（與 arm H/I/J/K 同一接受類；識別字若不可查，使用者可改寫查詢）。
 *   另記錄（P2 acknowledgment；stage-11.1）：arm K 過度否決使合法 CJK 識別字（如 `symbols 明日報表`／`symbols "明日報表"`）不可查；
 *   本文件不承諾任何合法識別字皆可查詢；識別字邊界／引號逸出為未來 syntax/UX 軌道，刻意不在本 PR。
 * - 已知殘餘（accepted, not fixed；separator hardening 現況）：consent 動詞與 後-family/再|才 之間夾
 *   半/全形分隔符（`\s` `,` `，` `.` `。` `;` `；` `:` `：` `、` `！` `？` `!` `?`、`．` U+FF0E `‥` U+2025 `…` U+2026、下劃線 `_`）與助詞 `了` `過`
 *   一律視為連接符——`批准,再做`／`批准，再做`／`批准;再做`／`批准:再做`／`批准了再做`／
 *   `批准。再做`／`同意後，再查`／`批准，才做` 皆否決；possessor 兩端單空白落在框架兩個分隔槽內，
 *   arm D 仍然匹配（如 `等 安全審核委員會主席 批准` → clause gate 否決）；possessor 內部夾空白
 *   （如 `等安全 審核主席 批准`／`等資安 / 法務批准`，stage-8.1 起斜線兩側夾空白亦同）由
 *   possessor 平坦類收（\s 含空白與半全形空白）——框架匹配 ⇒ clause gate 否決（fail-safe；
 *   否決方向從不執行）；帶空白不再落 whitespace 澄清／no-match 路徑，直接 clause gate 否決；
 *   殘餘：possessor 超過 64 個單位 → fail-open；
 *   `只有.{0,8}才` 之有界 {0,8} 不變；EN 開放詞彙 open-world 不變。
 *   （`批准然後再做` 自 arm D′ 起 ¬ 殘餘——然後 2×\p{L}，必否決。）
 * - 已知殘餘（accepted, frame-local, not fixed；audit F7）：CJK 框架分隔符現涵蓋 \s，
 *   故 `同意 後再查`（consent 動詞與後-family 間僅一空白）已由 arm A 於 clause gate 否決（原
 *   「clause gate 放行、extraction whitespace 閘把關」之描述自分隔符類納入後不再成立）；
 *   框架內更廣義的空白相鄰（如 possessor 內部夾空白）—— stage-8.1 起 possessor 平坦類含 \s，
 *   possessor 內部夾空白已由 等-arm 框架匹配 ⇒ clause gate 否決（fail-safe，永不執行）；僅記錄。
 * - 已知過度否決（accepted fail-safe, do not "fix"）：`查 Main 方法 符號` 因 CJK
 *   方法 教學標記事否決落 DENIED——保留，僅記錄；`查 Foo 符號 pending review`
 *   亦因結構性詞幹 `review` 否決——為預期結果。
 *
 * matchTemplate 路由順序（B3 re-review pin；依序，fail-safe）：
 * 1. code-symbol lookup 前閘最先（stage-8.5）：containsAny(SYMBOL_HINTS) 且
 *    [isAffirmativeSymbolCommand] 命中（命令式肯定語句准入，見上段）才回
 *    `lsp.symbols`——在一切關鍵詞工具與 SLOW 之前。
 *    識別字如 `AlarmManager`／`NotificationManager`／`CalendarProvider`／
 *    `ScreenshotController`／`EmailClient`／`VolumeController` 內含其他工具的
 *    無錨點 hint 子串（`alarm`、`notification`、`calendar`、`screenshot`、
 *    `e-?mail`、`volume`），若仍走 RULES 順序會被這些工具先搶走
 *    （`查 AlarmManager 符號` 會落到 alarm.create 的缺時間澄清）。
 *    前閘保證 `查 AlarmManager 符號` 綁 lsp.symbols 而非 alarm.create；
 *    stage-8.5：識別字內含慢速 hint 子串（如 `自動操作`、`自動填表`）的
 *    准入之 `查 X 符號` 形亦必綁 lsp.symbols（疑問尾標／條件授權子句先被否決、再落 SLOW），
 *    不落 SLOW automation descriptor；
 *    #4（B3 re-review）/stage-1：問句/知識/教學（how-to）語句與含授權/條件授權
 *    子句或教學/說明標記的語句一律不滿足 symbol 前閘——
 *    但句首（可選 EN please／CJK 請|麻煩|帮我|幫我|给我|給我|请 前綴）必須是查詢動詞
 *    （find/search/lookup/locate，或 CJK 命令式查詢動詞符元 SYMBOL_CJK_COMMAND_TOKEN：
 *    複合 查詢|查询|搜尋|搜寻|搜索|查找|尋找|寻找|找尋|找寻 longest-match-first 排前、
 *    單字 查/找/尋/搜/詢/寻/询 殿後；Java 交替同位置有序首中 ⇒ 複合先＝最長匹配）或 `symbol(s) <name>`
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
 * 2. SLOW_HINTS（非查詢自動化語句仍先轉 automation descriptor，projection/play
 *    FLAVOR_BLOCKED）——僅在 symbol 前閘不成立時先領（stage-8.5）：
 *    containsAny(SLOW_HINTS) 命中即回 SLOW automation descriptor，由 projection
 *    誠實拒絕，play 上 FLAVOR_BLOCKED；
 * 3. RULES 關鍵詞工具最後（SYMBOLS 已自 RULES 移除，symbol 匹配由前閘獨占；
 *    [templateToolNames] 顯式補入 lsp.symbols 維持 expansion-rule oracle）。
 * 前閘要求命令式肯定語句准入（僅有 hint 不足；查詢動詞出現在句中——如
 * `I want to find symbol Foo`——同樣不足）；
 * SLOW 僅在 symbol 前閘不成立時先領（非查詢自動化語句照常路由）；准入之 `查 X 符號` 形則必綁 lsp.symbols（含疑問尾標／條件授權子句者先被否決、再落 SLOW）。
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
        // Symbol-naming lookup is checked FIRST (stage-8.5; before SLOW_HINTS):
        // identifiers may embed slow-lane substrings (`自動操作`、`自動填表`), but
        // `查 自動操作 符號` is a code-symbol query, not an automation ask.
        // identifiers like `AlarmManager` contain other tools' hint words, but
        // `查 AlarmManager 符號` is a code-symbol query, not an alarm/call/mail ask.
        // Affirmative-command admission (#19 B3 stage-1): the gate matches ONLY an
        // explicit lookup command ([isAffirmativeSymbolCommand]) — anything else
        // falls through (never FAST); a question/knowledge/teaching utterance
        // with a lookup verb no longer qualifies (#4).
        if (containsAny(text, SYMBOL_HINTS) && isAffirmativeSymbolCommand(text)) {
            return TemplateMatch(LspTools.SYMBOLS_NAME, symbolArgs(text))
        }
        // Slow-lane intents resolve to the automation descriptor so projection
        // can deny them honestly (FLAVOR_BLOCKED on play) instead of NO_MATCH.
        // Checked AFTER the affirmative-command symbol gate (stage-8.5), still
        // before RULES: a non-query automation statement (e.g. bare `自動操作`)
        // routes slow as before.
        if (containsAny(text, SLOW_HINTS)) {
            return TemplateMatch(ToolRegistry.SLOW_TOOL_NAME, jsonText(text))
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
        // Dual-tracked bound: SafeToolSlice.kt's MAX_INPUT_CHARS pins the same 4096 here — keep both in sync.
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
        // #19 B3 stage-12.1（Re-review #12 A1）：terminal-position rule suffix 掃描（抽取層 [finishSymbolQueryExtraction]；僅非 EN-delimited 相）。
        // 僅對最後一個 CJK cue 之後的短尾綴呼叫一次，O(suffix)；Java Regex 依碼點比對，補充平面表意字亦正確命中。
        private val LETTER_OR_NUMBER = Regex("[\\p{L}\\p{N}]")
        // #19 B3 stage-1（affirmative-command admission model，取代 A2 lookup-marker
        // 准入）: symbol 前閘僅收**命令式肯定語句**（explicit lookup command）。
        // 准入條件（isAffirmativeSymbolCommand；全部成立才滿足前閘）：
        // 1. 句首（start-anchored，IGNORE_CASE）必須是以下之一：
        //    - EN：`^(?:please[\s,]+)?(?:find|search|lookup|locate)\b`（可選 please 前綴）
        //    - EN 單詞命令形：`^symbol(s)?\s+\S`（如 `symbols Foo`，原樣保留）
        //    - CJK：`^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\s，,]*)?SYMBOL_CJK_COMMAND_TOKEN`
        //      （可選禮貌前綴 請|麻煩|帮我|幫我|给我|給我|请 後接
        //       查詢動詞符元 SYMBOL_CJK_COMMAND_TOKEN =
        //       `(?:查詢|查询|搜尋|搜寻|搜索|查找|尋找|寻找|找尋|找寻|查|找|尋|搜|詢|寻|询)`
        //       ——複合 longest-match-first 排前、單字殿後（Java 交替同位置有序首中，
        //       複合先＝最長匹配；`查詢Foo符號` 整體消費 `查詢`）；
        //       `請先說明…` 不匹配——請後接 先 而非動詞，正確；
        //       CJK 兩字動詞首字為單字 opener 者一律視為剩餘 verbatim 識別字（如 `詢問Foo符號` ⇒ FAST query=`問Foo`；
        //        READ-only；記錄殘餘；`質詢` 因首字 ∉ opener → no-match；字典無窮不可封閉枚舉）
        // 2. 任意位置不得出現授權/條件授權子句（對 raw text 與下方兩個正規化探針測——
        //    「任意位置」＝三個文本之一命中即否決；IGNORE_CASE；結構性錨定，絕不裸名詞）：
        //    A. `\bonly\s+(?:after|when|if|until|once|before|with|upon)\b` 變體（approval|consent|permission 收尾）、
        //    B. 連接詞集 after|until|unless|once|when|while|if|before|since|whenever|as|
        //       because|provided|upon|following|pending|with|subject to|prior to|given|on ＋ 可選受詞/限定詞槽
        //       （i|we|you|they|anyone|he|she|it|my|our|your|their|his|her|the|this|that|
        //        a|an|some|any|prior|is|are|was|were|has|have|had|been，最多 3 個）
        //       ＋ 動詞/詞幹槽（approv\w*|agree\w*|consent\w*|confirm\w*|say\w*/said|
        //       allow\w*|release\w*|permit\w*|ok\w*|okay\w*|go[- ]?ahead\w*|ready|
        //       permission|review|CONSENT_VERBS_CJK_FLEX（consent 動詞槽單一事實來源，插入 ≤16），EN 詞幹皆 \b/\w* 錨定；CJK FLEX 無詞邊界概念（依連接詞左錨））——gerund/過去式/第三人稱
        //       一併涵蓋（`after approving`、`once approved`、`after I had approved`、
        //       `once it is approved`、`since I approved`、`after my approval`）；
        //       裸 `\b(?:approval|approved|consent|pending)\b` 絕不單獨收錄；
        //    C. `\bwait\s+(?:for|until|till)\b`、`\bhold\s+(?:on|off)\b`、`\bnot\s+yet\b`；
        //    D. CJK 結構性條件句僅覆蓋所列框架：`等(?>[\s，,]*)(?:FLAT{0,64}(?>[\s，,]*))?CONSENT_VERBS_CJK_FLEX`
        //       （stage-8.1 A1 ＋ security-audit M1/M2：possessor 槽放寬為平坦類——\p{L}/\p{N} ＋ ASCII 識別字標點
        //        `._-$#@/():,;+=~` ＋ \s（及 NFKC 探針摺疊之半全形空白）＋ 半/全形逗號 `,` `，`
        //        ＋ CJK 句讀 `。、！？!?；;：:．‥…` ＋ `／` U+FF0F ／ `＿` U+FF3F；無巢狀量化、無類內交替、
        //        同一 {0,64} 上界；其兩個 delimiter 槽為原子群組 `(?>…)`——possessor 槽本身 non-atomic，
        //        須回退出動詞左端字元（M1：possessor/delimiter 重疊 ⇒ 4090 個逗號級輸入否決失敗曾 O(N²·64)）；
        //        possessor 內部可夾斜線/識別字標點/空白/CJK 句讀；
        //        ——`等資安/法務批准`／`等CISO/CEO同意`／`等資安 / 法務批准` 皆否決）；
        //        consent 動詞槽＝CONSENT_VERBS_CJK_FLEX（由 CONSENT_VERBS_CJK_TOKENS 推導；
        //        M2：動詞內 ≤16 個 INS 插入，INS＝`[\p{L}\p{N}._-$#@/():,;+=~]`，Lo filler ⊆ \p{L}；
        //        INS 排除 \s 與 U+FF0C（全形逗號），刻意包含 U+002C（半形逗號）——平坦、有界 {0,16} 缺口，
        //        與 possessor 由動詞首字元錨隔開（無 delimiter 原子性互動；平坦已驗證）；
        //        插入上限與 arm D′ 一致；FLEX 僅於五處列舉點使用）；
        //        不再限封閉代名詞表——未列 possessor 皆收（`等主管批准`／`等老闆同意`／`等經理核准`／
        //        `等董事會點頭`／`等董老闆批准`／長組織名 `等安全審核委員會主席批准`／
        //        `等國家安全委員會批准`／9/12 位數字 `等123456789批准`／`等123456789012批准` 皆否決；`等我們同意`／`等你點頭` 仍匹配）；
        //        有界、無巢狀量化、無災難性回溯（possessor 槽不回溯出動詞左端字元之外；delimiter 為原子群組）（最壞多項式有界））、
        //       `CONSENT_VERBS_CJK_FLEX[\s，,.;；:：。、！？!?．‥…了過_]*(?:之後|之后|以後|以后|後|后)(?:[\s，,.;；:：。、！？!?．‥…_]*(?:再|才))?`／
        //       `CONSENT_VERBS_CJK_FLEX[\s，,.;；:：。、！？!?．‥…了過_]*(?:再|才)`
        //       （兩臂：A＝動詞＋可選分隔符 [\s，,.;；:：。、！？!?．‥…了過_]*（半/全形空白、`,` `，` `.` `。` `;` `；` `:` `：` `、` `！` `？` `!` `?`、`．` `‥` `…` `_`、助詞 `了` `過`）
        //        ＋必填後-family＋可選分隔符 [\s，,.;；:：。、！？!?]*（同上，不含 `了` `過`）＋可選 再|才，
        //       `同意後`／`批准後`／`批准後再做`／`同意後，再查`／`批准。再做` 皆否決；
        //       B＝動詞＋可選分隔符（同上）＋必填再|才（無後-family），`批准再做`／`批准才做`／`批准,再做`／`批准，才做`／`批准了再做` 皆否決；
        //        無 `再|才` 亦不匹配——`查 批准 符號` 保持 FAST；
        //       D′＝FLEX 動詞＋插入 arm（L/N/了過＋識別字標點/空白 ≤16 個字元）必填再|才：`批准Ｄ再做`／`同意白拿了再做` 皆否決；
        //       FLEX 動詞槽（security-audit M2）：動詞內各字元之間允許 ≤16 個 INS 插入
        //       （INS＝`[\p{L}\p{N}._-$#@/():,;+=~]`）——`批A准再做`／`批_准`／`核1準` 等
        //       動詞內 1 插入字元亦否決（fail-safe）；插入 >16 字仍為殘餘（fail-OPEN，見 M3 節）；
        //       以及有界缺口 `只有.{0,8}才`／`只許.{0,8}才|只许.{0,8}才`／`只允.{0,8}才`（無裸名詞 CJK 匹配）；
        //       H＝deferred-action nucleus（stage-9.1；Re-review #9 A1 [P1]；任意位置，CJK）：`(?:再|才)(?:做|查|跑|說|寫|讀|處理|執行|更新|重啟|處辦|辦理|通知|告訴|通報|報告|匯報|彙報|回信|提醒)`
        //        ——文中任意位置出現 `再|才` ＋ 有界動作動詞核（20 個 Lo 動詞：十二個 stage-9.1 核＋ stage-10.1 擴充 通知|告訴|通報|報告|匯報|彙報|回信|提醒）即否決；不依賴 consent 動詞識別；
        //       `查Foo符號等審批再做`／`查Foo符號等授權再做`／`查Foo符號明天再做`／`查Foo符號等審批後再做`／`查Foo符號明天再通知我` 皆否決；
        //        動詞核為 common CJK action verbs 之有界集——不封閉宣稱：核外動詞仍 fail-OPEN（open-world 殘餘，如實記錄，見 M3 節）；
        //       I＝conditional-framing nucleus（stage-9.1；Re-review #9 A1 [P1]；任意位置，CJK）：`(?:如果|假如|倘若|要是|的話)`
        //        ——文中任意位置出現條件框架顆粒即否決；`查Foo符號如果我同意` 否決；`的話` over-deny 為接受之 fail-safe；
        //       J＝wait/until + completion nucleus（stage-10.1；Re-review #10 A1 [P1]；任意位置，CJK）：`(?:等|待|直到|直至).{0,16}?(?:放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知)`
        //        ——文中任意位置出現 等|待|直到|直至 ＋ 有界 {0,16} 平坦缺口（`.` 單一有界量化）＋ completion-endpoint 集（11 個：放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知）即否決；
        //       `查Foo符號等我放行`／`查Foo符號等審核結束`／`查Foo符號直到審核結束`／`查Foo符號等我回覆` 皆否決；
        //        `.` 平坦 {0,16} 有界、無巢狀量化、無災難性回溯；over-deny（如 `查 等就結束 符號`）為接受之 fail-safe（記錄，不得「收回」）；
        //       K＝bare temporal nucleus（stage-10.1；Re-review #10 A1 [P1]；任意位置，CJK）：`(?:明早|明晚|明天|明日|後天|后天|大後天|大后天|下週|下星期|下周|下下週|下下周|下個月|下个月|月底|年底|日後|日后|幾天後|几天后|過幾天|过几天|稍後|稍后|晚點|晚点|等下|待會|待会|待會兒|待会儿|回頭|回头|隔天|隔日|次日|翌日|翌週|翌周|翌年)`
        //        ——文中任意位置出現裸時間顆粒（41 個）即否決；`查Foo符號明天再通知我`／`查Foo符號稍後通知`／`查 明日報表 符號` 皆否決；
        //        over-deny 為接受之 fail-safe（記錄，不得「收回」）；EN 側（tomorrow 等）刻意不動、如實記錄；
        //        arm H/I/J/K/L/M 於准入檢查（isAffirmativeSymbolCommand 之 clause gate，raw/spaced/joined 三探針聯集）落地，非於 extraction；
        //       L＝person-anchored wait nucleus（stage-11.1；Re-review #11 A1 [P1]；任意位置，CJK）：`(?:等|待|直到|直至)(?:我|你|您|他|她|我們|我们|你們|你们|您們|您们|他們|他们|她們|她们|妳|妳們|妳们|大家|全員|全员|各位|老闆|經理|主管|主任|總監|副理|董事|總經理|執行長)`
        //        ——文中任意位置出現 等|待|直到|直至 ＋ 緊接人稱字（31 個）⇒ 外部動作延後 ⇒ DENIED；平坦字面交替、零量化；不依賴 consent 動詞識別、不枚舉端點動詞；
        //       M＝cardinal-time-expression nucleus（stage-11.1；Re-review #11 A1 [P1]；任意位置，CJK）：`(?:(?:[0-9０-９一二兩两三四五六七八九十廿卅百零幾几千萬万]{1,5}|半)(?:個|多)?(?:天|日|週|周|星期|礼拜|禮拜|個月|个月|月|年|小時|小时|鐘頭|钟头|分鐘|分钟|秒|時|时|點|点|點鐘|点钟)(?:之|以)?(?:後|后))`
        //       ——基數詞＋時間單位＋後|后 ⇒ 未來時間表達式 ⇒ DENIED；`{1,5}` 平坦有界、無巢狀量化、`(?:之|以)?` infix；不依賴 consent 動詞識別、不枚舉延後動詞；
        //       已知殘餘（accepted, frame-local）：`等` 與 consent 動詞之間的 possessor 超過 64 個單位
        //       不匹配 → fail-open（單 token 命令形 end-to-end FAST；帶空白則不執行）；
        //       插入 ≤16 字 L/N/了過＋必填再|才（arm D′）——`批准Ｄ再做` 等亦否決；
        //       Cf-split 拆分（如 `等\u200B主管批准`）由 spaced/joined 探針還原覆蓋（兩探針與 raw 取聯集）。
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
        // 命中准入 ⇒ 前閘成立；未命中 ⇒ SLOW_HINTS 次之（非查詢自動化語句，
        // stage-8.5），再落入 RULES 迴圈——無規則命中則 no-match
        // DENIED（reasonCode null）；命中 keyword 規則者落其原有 fail-closed
        // 語意（如 `查 AlarmManager 符號呢` → alarm.create 缺時間澄清）——絕不執行。
        // EN 開放詞彙一律 open-world deny-by-default：非命令式一律 no-match。
        // CJK 否定顆粒（不/別/沒…）仍為封閉否定表（見 [NEGATION_HINTS]／
        // [NEGATION_MARKERS]），屬防禦縱深，非本前閘之准入依據。
        private val AFFIRMATIVE_OPENER_EN =
            Regex("^(?:please[\\s,]+)?(?:find|search|lookup|locate)\\b", RegexOption.IGNORE_CASE)
        private val AFFIRMATIVE_OPENER_EN_COMMAND = Regex("^symbols?\\s+\\S", RegexOption.IGNORE_CASE)
        // #19 B3 stage-7.2（A2）：CJK 查詢動詞符元——AFFIRMATIVE_OPENER_CJK 與
        // SYMBOL_OPENER_CJK 共用的單一事實來源（single source；兩正則不可能再各自
        // 漂移）。複合形 longest-match-first 排前（Java 交替在同位置為有序首中 ⇒
        // 複合先＝最長匹配），單字殿後：`查詢Foo符號` 整體消費 `查詢`，語義修正
        // （否則殘餘 `詢Foo` 會被當識別字前綴執行）。單字開頭仍保留合法識別字形態
        // （`查 詢Foo 符號` → query=`詢Foo`）。CJK 兩字動詞首字為單字 opener 者
        // （`詢問Foo符號` ⇒ FAST query=`問Foo` 等無窮類）視為剩餘 verbatim 識別字
        // 原樣執行（READ-only；記錄殘餘；`質詢` 因首字 ∉ opener → no-match）。
        private const val SYMBOL_CJK_COMMAND_TOKEN = "(?:查詢|查询|搜尋|搜寻|搜索|查找|尋找|寻找|找尋|找寻|查|找|尋|搜|詢|寻|询)"
        // #19 B3 stage-8.1（A1 修正＋security-audit M2 修正）：CJK consent 動詞集——
        // PERMISSION_CONDITIONAL_CLAUSE 五處列舉點的單一事實來源（single source；比照
        // SYMBOL_CJK_COMMAND_TOKEN 之於 opener，兩正則不可能再各自漂移）。繁簡對稱；
        // 回覆|回复|答覆|答复 自 stage-8.1 起納入。
        // TOKENS＝字面動詞清單；CONSENT_VERBS_CJK_FLEX 由 tokens 推導（tokens 單一來源）。
        // FLEX＝動詞內允許 ≤16 個 INS 插入（INS＝extraction allow-list 字母/數字/識別字標點的
        // Lo filler 含 U+115F/1160/3164/FFA0 皆 ⊆ \p{L}；INS 排除 \s 與 U+FF0C（全形逗號），
        // 刻意包含 U+002C（半形逗號）——平坦、有界 {0,16} 缺口，與 possessor 由動詞首字元錨隔開
        // （無 delimiter 原子性互動；平坦已驗證）；插入上限與 arm D′ 一致；verb 內夾空白會撞
        // whitespace 閘 ⇒ 澄清 ⇒ 永不執行）。
        // {0,16} 插入上限與 arm D′ 一致。INS 內 `-` 逸出為 `\\-`、`$` 以 `${'$'}` 表示；類內無 `]` 未逸出字元。
        // 任一列舉點再手寫同意|核准… 即視為漂移，須改回引用 FLEX（tokens 推導）。
        private val CONSENT_VERBS_CJK_TOKENS = listOf(
            "同意", "核准", "核準", "批准", "確認", "确认", "許可", "许可", "點頭", "点头", "回覆", "回复", "答覆", "答复",
        )
        // 動詞內插入：tokens 內各字元之間放一個有界 [INS]{0,16}（FLEX 僅於五處列舉點使用）；
        // 平坦交替、無巢狀量化、無類別量化（INS 排除 \s 與 U+FF0C；`,` 仍含於 INS（有界 {0,16}、與 possessor 由動詞首字元隔開）），nesting depth ≤2。
        private val CONSENT_VERBS_CJK_FLEX = "(?:" +
            CONSENT_VERBS_CJK_TOKENS.joinToString("|") { v ->
                v.map { it }.joinToString("[\\p{L}\\p{N}._\\-${'$'}#@/():,;+=~]{0,16}")
            } + ")"
        private val AFFIRMATIVE_OPENER_CJK =
            Regex("^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\\s，,]*)?" + SYMBOL_CJK_COMMAND_TOKEN)
        private val PERMISSION_CONDITIONAL_CLAUSE = Regex(
            "\\bonly\\s+(?:after|when|if|until|once|before|with|upon)\\s+[a-z\\s]*?(?:approval|consent|permission)\\b" +
                "|\\b(?:after|until|unless|once|when|while|if|before|since|whenever|as|because|provided|upon|following|pending|with|subject\\s+to|prior\\s+to|given|on)[\\s,]+" +
                "(?:(?:i|we|you|they|anyone|he|she|it|my|our|your|their|his|her|the|this|that|a|an|some|any|prior|is|are|was|were|has|have|had|been)[\\s,]+){0,3}?" +
                "(?:approv\\w*|agree\\w*|consent\\w*|confirm\\w*|say\\w*|said\\b|allow\\w*|release\\w*|permit\\w*|ok\\w*|okay\\w*|go[- ]?ahead\\w*|ready\\b|permission\\b|review\\b|" + CONSENT_VERBS_CJK_FLEX + ")" +
                "|\\bwait\\s+(?:for|until|till)\\b" +
                "|\\bhold\\s+(?:on|off)\\b" +
                "|\\bnot\\s+yet\\b" +
                // #19 B3 stage-8.1（A1）＋security-audit M1/M2：possessor 槽放寬為平坦 {0,64} 類（non-atomic）——
                // \p{L}/\p{N} ＋
                // ASCII 識別字標點 `._-$#@/():,;+=~` ＋ \s ＋ 半/全形逗號 `,` `，` ＋
                // 常見 CJK 句讀 `。、！？!?；;：:．‥…` ＋ 全形斜線／ U+FF0F／全形底線＿ U+FF3F。
                // 無巢狀量化、無類內交替、同一 {0,64} 上界；其兩個 delimiter 槽為原子群組 `(?>…)`
                // （M1：`\s`/`,`/`，` 與 delimiter 重疊，4090 個逗號級輸入曾造成 possessor/delimiter
                // 反覆回溯 ⇒ O(N²·64)；atomic delimiter 封死回溯，possessor 本身保持 non-atomic
                // 以便回退出動詞左端字元）；
                // possessor 內部可夾斜線/識別字標點/空白/CJK 句讀（`等資安/法務批准`／
                // `等CISO/CEO同意`／`等資安 / 法務批准`）⇒ clause gate 否決。
                // `-` 逸出為 `\\-`；`$` 以 `${'$'}` 表示；類內無 `]` 未逸出字元。
                // （`.`（U+002E）已含於 ASCII 識別字標點；`．` U+FF0E 已含於 CJK 句讀；
                //  具體形狀：`等(?>[\\s，,]*)(?:POSS{0,64}(?>[\\s，,]*))?FLEX動詞`。）
                "|等(?>[\\s，,]*)(?:[\\p{L}\\p{N}._\\-${'$'}#@/():,;+=~\\s，,。、！？!?；;：:．‥…／＿]{0,64}(?>[\\s，,]*))?" + CONSENT_VERBS_CJK_FLEX +
                "|" + CONSENT_VERBS_CJK_FLEX + "[\\s，,.;；:：。、！？!?．‥…了過_]*(?:之後|之后|以後|以后|後|后)(?:[\\s，,.;；:：。、！？!?．‥…_]*(?:再|才))?" +
                "|" + CONSENT_VERBS_CJK_FLEX + "[\\s，,.;；:：。、！？!?．‥…了過_]*(?:再|才)" +
                "|" + CONSENT_VERBS_CJK_FLEX + "[\\p{L}\\p{N}了過\\s，,.;；:：。、！？!?．‥…_$#@/():,;+=~／＿-]{0,16}(?:再|才)" + // arm D′：FLEX 動詞＋CJK/ASCII/全形字母（NFKC 折）＋助詞＋識別字標點/空白（含 `／` U+FF0F／`＿` U+FF3F）插入於動詞與再|才之間（如 `批准Ｄ再做`/`同意白拿了再做`/`批准Ｄ_Ｄ再做`/`批准a-b再做`/`批准Ｄ/Ｄ再做`/`批准Ｄ／Ｄ再做`）——L/N/了過/標點/空白插入 ≤16 字、fail-safe 否決
                // #19 B3 stage-9.1（Re-review #9 A1，[P1]）：arm H——deferred-action nucleus（任意位置，CJK）。
                // 文中任意位置出現 `再|才` ＋ 有界動作動詞核（20 個 Lo 動詞：做查跑說寫讀處理執行更新重啟處辦辦理，
                // ＋ stage-10.1 擴充 通知|告訴|通報|報告|匯報|彙報|回信|提醒——通知/告訴 系、報告 系、回信、提醒 四批延後動作動詞）
                // 即否決（clause gate at admission）。`查Foo符號等審批再做`／`查Foo符號等授權再做`／`查Foo符號明天再做`／
                // `查Foo符號等審批後再做`／`查Foo符號明天再通知我` 皆否決（fail-safe direction；deny-by-admission，never FAST）。
                // 動詞核為 common CJK action verbs 之有界集——不封閉宣稱（非 open-world closure）：
                // 核外動詞（未列入此 20 個者）仍不匹配 ⇒ fail-OPEN（open-world 殘餘，如實記錄；見 KDoc「未列示 consent 動詞」節）。
                // 【接受之 fail-safe 過度否決（記錄，不得「收回」）】：識別字本身含 再|才×動作動詞核（如 `查 再做 符號`／
                // `查 再做一次 符號`／`查 才做 符號`／`查 再查 符號`／`查 授權再做 符號`／`查 再通知 符號`）一律 DENIED——黏合 CJK 識別字 vs
                // 延後條件句無法僅憑語法分離（與 arm I `的話` 同一接受類；識別字若不可查，使用者可改寫查詢）。
                "|(?:再|才)(?:做|查|跑|說|寫|讀|處理|執行|更新|重啟|處辦|辦理|通知|告訴|通報|報告|匯報|彙報|回信|提醒)" + // arm H：deferred-action nucleus（stage-9.1；stage-10.1 動詞核擴充至 20 個 Lo 動詞）——任意位置 CJK 再|才＋有界動作動詞核 ⇒ clause gate 否決（識別字含核者一律 DENIED，accepted fail-safe）
                // #19 B3 stage-9.1（Re-review #9 A1，[P1]）：arm I——conditional-framing nucleus（任意位置，CJK）。
                // 文中任意位置出現條件框架顆粒（如果|假如|倘若|要是|的話）即否決（clause gate at admission）。
                // `查Foo符號如果我同意` 否決。`的話` over-deny 黏合識別字為 accepted fail-safe（記錄，不得「收回」）。
                // arm H/I/J/K 不依賴 consent 動詞識別，未列示 consent 動詞亦無法繞過。
                "|(?:如果|假如|倘若|要是|的話)" + // arm I：conditional-framing nucleus（stage-9.1）——任意位置 CJK 條件框架顆粒 ⇒ clause gate 否決（`的話` over-deny 為接受之 fail-safe）
                // #19 B3 stage-10.1（Re-review #10 A1，[P1]）：arm J——wait/until + completion nucleus（任意位置，CJK）。
                // 文中任意位置出現 等|待|直到|直至 ＋ 有界 {0,16} 平坦缺口（`.` 單一有界量化、無巢狀量化、無災難性回溯）
                // ＋ 有名 completion-endpoint 集（放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知，11 個）即否決（clause gate at admission）。
                // `查Foo符號等我放行`／`查Foo符號等審核結束`／`查Foo符號直到審核結束`／`查Foo符號等我回覆` 皆否決（deny-by-admission；READ-only 亦從不執行）。
                // arm J 不依賴 consent 動詞識別，未列示 consent 動詞亦無法繞過。
                // 【接受之 fail-safe 過度否決（記錄，不得「收回」）】：識別字本身含 等|待|直到|直至 × completion-endpoint（如 `查 等就結束 符號`）一律 DENIED——
                // 黏合 CJK 識別字 vs 等待完成句無法僅憑語法分離（與 arm H/I 同一接受類；識別字若不可查，使用者可改寫查詢）。
                "|(?:等|待|直到|直至).{0,16}?(?:放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知)" + // arm J：wait/until + completion nucleus（stage-10.1；Re-review #10 A1 [P1]）——任意位置 CJK 等|待|直到|直至＋有界 {0,16} 缺口＋completion-endpoint ⇒ clause gate 否決（識別字含核者一律 DENIED，accepted fail-safe）
                // #19 B3 stage-11.1（Re-review #11 A1，[P1]）：arm L——person-anchored wait nucleus（任意位置，CJK）。
                // 文中任意位置出現 等|待|直到|直至 ＋ 緊接人稱字（我|你|您|他|她|我們|我们|你們|你们|您們|您们|他們|他们|她們|她们|妳|妳們|妳们|大家|全員|全员|各位|老闆|經理|主管|主任|總監|副理|董事|總經理|執行長，31 個）即否決（clause gate at admission）。
                // `查Foo符號等我簽核`／`查Foo符號等我核對`／`查Foo符號等我允許`／`查Foo符號等他簽核`／`查Foo符號等主管核對` 皆否決（deny-by-admission；READ-only 亦從不執行）。
                // arm L 不依賴 consent 動詞識別、不枚舉端點動詞（簽核/核對/允許/放行/核准… 皆由結構涵蓋）。
                // 平坦字面交替、零量化；無巢狀量化、無災難性回溯。
                // 【接受之 fail-safe 過度否決（記錄，不得「收回」）】：尾綴裸 `等我`（如 `查Foo符號等我`）及識別字本身含 等|待|直到|直至＋人稱字（如 `查 等我 符號`）一律 DENIED——
                // 黏合 CJK 識別字 vs 等待句無法僅憑語法分離（與 arm H/I/J/K 同一接受類；識別字若不可查，使用者可改寫查詢）。
                // stage-12.1 起：cue 之後的尾綴（`等我簽核` 類）已由 terminal-position rule 嚴格 subsumed——本 arm 保留為防禦縱深；
                // 對 EN opener 夾帶 CJK cue（如 `symbols …等我…`）仍為主要否決依據（terminal-position rule 不適用 EN 側）。
                "|(?:等|待|直到|直至)(?:我|你|您|他|她|我們|我们|你們|你们|您們|您们|他們|他们|她們|她们|妳|妳們|妳们|大家|全員|全员|各位|老闆|經理|主管|主任|總監|副理|董事|總經理|執行長)" + // arm L：person-anchored wait nucleus（stage-11.1；Re-review #11 A1 [P1]）——任意位置 CJK 等|待|直到|直至＋緊接人稱字 ⇒ clause gate 否決（識別字含核者一律 DENIED，accepted fail-safe）
                // #19 B3 stage-10.1（Re-review #10 A1，[P1]）：arm K——bare temporal nucleus（任意位置，CJK）。
                // 文中任意位置出現裸時間顆粒（明早|明晚|明天|明日|後天|后天|大後天|大后天|下週|下星期|下周|下下週|下下周|下個月|下个月|月底|年底|日後|日后|幾天後|几天后|過幾天|过几天|稍後|稍后|晚點|晚点|等下|待會|待会|待會兒|待会儿|回頭|回头|隔天|隔日|次日|翌日|翌週|翌周|翌年，41 個）即否決（clause gate at admission）。
                // `查Foo符號明天再通知我`／`查Foo符號稍後通知`／`查 明日報表 符號` 皆否決（deny-by-admission；READ-only 亦從不執行）。
                // （`查Foo符號明天再通知我` 同時命中 arm H `再通知`；`查Foo符號明天再通ㅤ知我`（U+3164 於 通/知 之間）由 stage-9.5 探針剝除 filler 後 arm H 亦命中——雙臂交集，fail-safe 方向不變。）
                // 【接受之 fail-safe 過度否決（記錄，不得「收回」）】：識別字本身含裸時間顆粒（如 `查 明日報表 符號`／`查 年底 符號`）一律 DENIED——
                // 時間顆粒 vs 識別字無法僅憑語法分離（與 arm H/I/J 同一接受類；識別字若不可查，使用者可改寫查詢）。
                // EN 側（tomorrow／later 等）刻意不動、如實記錄（不宣稱 EN 封閉表）。arm K 不依賴 consent 動詞識別。
                "|(?:明早|明晚|明天|明日|後天|后天|大後天|大后天|下週|下星期|下周|下下週|下下周|下個月|下个月|月底|年底|日後|日后|幾天後|几天后|過幾天|过几天|稍後|稍后|晚點|晚点|等下|待會|待会|待會兒|待会儿|回頭|回头|隔天|隔日|次日|翌日|翌週|翌周|翌年)" + // arm K：bare temporal nucleus（stage-10.1；Re-review #10 A1 [P1]）——任意位置 CJK 裸時間顆粒 ⇒ clause gate 否決（識別字含核者一律 DENIED，accepted fail-safe）
                // #19 B3 stage-11.1（Re-review #11 A1，[P1]）：arm M——cardinal-time-expression nucleus（任意位置，CJK）。
                // 文中任意位置出現 基數詞（[0-9０-９一二兩两三四五六七八九十廿卅百零幾几千萬万]{1,5} 平坦類，或 `半`）＋可選 `個|多`；時間單位（天|日|週|周|星期|礼拜|禮拜|個月|个月|月|年|小時|小时|鐘頭|钟头|分鐘|分钟|秒|時|时|點|点|點鐘|点钟）＋可選 `之|以`；後|后 即否決（clause gate at admission）。
                // `查Foo符號三天後再啟動`／`查Foo符號兩天後再測試`／`查Foo符號30天後再跑`／`查Foo符號半小時後移交`／`查Foo符號十天後再查` 皆否決（deny-by-admission；READ-only 亦從不執行）。
                // arm M 不依賴 consent 動詞識別、不枚舉延後動詞（再啟動/再跑/再測試… 皆由結構涵蓋）。
                // 單一有界 {1,5} 平坦量化、無巢狀量化、無災難性回溯。
                // 【接受之 fail-safe 過度否決（記錄，不得「收回」）】：識別字本身含基數時間表達式（如 `查 三天後 符號`）一律 DENIED——
                // 時間表達式 vs 識別字無法僅憑語法分離（與 arm H/I/J/K/L 同一接受類；識別字若不可查，使用者可改寫查詢）。
                // 裸 `之後|以後|之後再`（無基數詞＋時間單位前綴）不納入；帶前綴之 `之|以` 後綴已由 `之|以` infix 收錄。
                // stage-12.1 起：cue 之後的尾綴（`三個工作天後啟動` 類）已由 terminal-position rule 嚴格 subsumed——本 arm 保留為防禦縱深；
                // 對 EN opener 夾帶 CJK cue（如 `symbols …三天後…`）仍為主要否決依據（terminal-position rule 不適用 EN 側）。
                "|(?:(?:[0-9０-９一二兩两三四五六七八九十廿卅百零幾几千萬万]{1,5}|半)(?:個|多)?(?:天|日|週|周|星期|礼拜|禮拜|個月|个月|月|年|小時|小时|鐘頭|钟头|分鐘|分钟|秒|時|时|點|点|點鐘|点钟)(?:之|以)?(?:後|后))" + // arm M：cardinal-time-expression nucleus（stage-11.1；Re-review #11 A1 [P1]）——任意位置 CJK 基數詞＋時間單位＋後|后 ⇒ clause gate 否決（識別字含核者一律 DENIED，accepted fail-safe）
                // #19 B3 stage-13.1（Re-review #15 A1 [P1] + P2）：CJK-leading ambiguous parse ⇒ clarification——EN fallback 不再救援；
                // CJK-leading 且 `符號|符号` 計數 ≥ 2（CJK 相入口，EN-leading 不觸發）⇒ 歧義 ⇒ Rejected ⇒ clarification；
                // EN 命令形 `symbols …`（leading、delimiter-bounded）不變（EN-leading 話語豁免本規則族；stage-8.3 verbatim 契約維持）。
                // #19 B3 stage-12.1（Re-review #12 A1，[P1/BLOCKER]）：terminal-position rule（於抽取層 [finishSymbolQueryExtraction] 落地，非本 clause gate 之臂）。
                // 僅非-EN-delimited（CJK 優先相）適用；EN-delimited 分支豁免（stage-8.3 verbatim 契約：
                // `查 symbols Foo符號`／`symbols Foo符號xyz` 皆 FAST verbatim；`查 symbols Foo符號xyz` 已非 FAST——CJK-leading 先讀 CJK 語法，見下）。
                // CJK-leading 話語中，最後一個 `符號`|`符号` 之後若含任一 \p{L}|\p{N} 碼點詞碼（additive deny-only）⇒ extraction 硬失敗 ⇒ 否決／澄清。
                // 此規則對 CJK-leading/non-enDelimited 話語嚴格 subsumes arms H/I/J/K/L/M 之「cue 之後尾綴」殘餘類——
                // 未列舉 consent 名詞（`等法務簽核`／`等同事核對`）／星期（`週五啟動`）／工作天偏移（`三個工作天後啟動`）／
                // 隔天／週末／隨時／`以後再做` 等（裸 `之後|以後` 尾帶其他文字，如 `查Foo符號以後再做`）皆由此一規則閉合，
                // 不再封閉枚舉身分／日期詞彙。arm K/L/M 在 CJK-leading 話語中對尾綴之有效角色收窄至防禦縱深；
                // EN opener 夾帶 CJK cue 之話語（如 `symbols Foo符號等我核對`）則仍由本 clause gate 之臂 L 否決。
                // cue 之前命中的 arm H/K（如 `查明天做 Foo符號` 之 arm K `明天`）不受影響，仍由本 clause gate 否決；
                // arm I/J 於 EN opener 話語（如 `symbols Foo符號如果我同意`／`symbols …等審核結束`）仍為主要否決依據——terminal-position rule 不適用於 EN 側。
                // 識別字含 CJK 者必須止於 cue；僅 `[\s，,]*`-類尾綴（Step C 消化）或 Step D 剝離後不重露 cue 者仍 FAST；其餘非 L/N 尾綴（`。`、ZWSP、emoji）皆 DENIED（Step E／歧義守衛；fail-closed，非本規則）。詳見抽取層 KDoc。
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
        // extraction particles rule（remainder 含 嗎|吗|呢 ⇒ 澄清）與 whitespace 閘背擋——安全機制不依賴此集完備。
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
         * Lo-category Hangul fillers (U+115F/U+1160/U+3164/U+FFA0) are silently
         * removed in BOTH probes as well: NFKC folds U+3164/U+FFA0 into U+1160
         * （Lo 類）, which would otherwise break a verb nucleus like `再ㅤ做`
         * (`再` + filler + `做`) before the clause gate can match it — same
         * treatment already applied by the negation gate and the interrogative-tail
         * probe, extended here for the clause gate (stage-9.5, F1 residual close).
         * `_` (Pc) is KEEPED in both probes so identifiers like `after_approval`
         * stay one word and cannot collide with the `after … approval` clause.
         * Unlike [isNegated]'s three-text scheme this never feeds `\b`-less
         * matching: [NEGATION_REMOVED]-style full removal is deliberately not
         * used here (it would dissolve the very word boundaries the clause
         * regexes anchor on).
         */
        private fun clauseProbeSpaced(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(HANGUL_FILLER, "")
                .replace(CLAUSE_FOLD, " ")
                .trim()

        private fun clauseProbeJoined(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(CLAUSE_Cf, "")
                .replace(HANGUL_FILLER, "")
                .replace(CLAUSE_FOLD, " ")
                .trim()

        /**
         * Affirmative-command admission predicate for the symbol front gate
         * (#19 B3 stage-1). Returns true iff the trimmed raw text is an
         * explicit lookup command: it opens with the EN verb set
         * (optionally after a leading `please`), the EN single-word command
         * form (`symbol(s) <name>`), or a CJK lookup verb (optionally after a
         * politeness prefix 請|麻煩|帮我|幫我|给我|給我|请 directly followed by
         * [SYMBOL_CJK_COMMAND_TOKEN] = longest-match-first
         * `查詢|查询|搜尋|搜寻|搜索|查找|尋找|寻找|找尋|找寻|查|找|尋|搜|詢|寻|询`
         * （複合排前、單字殿後；Java 交替同位置有序首中 ⇒ 複合先＝最長匹配；
         * CJK 兩字動詞首字為單字 opener 者一律視為剩餘 verbatim 識別字——如
         * `詢問Foo符號` ⇒ FAST query=`問Foo`；READ-only；記錄殘餘；`質詢` 因首字
         * ∉ opener → no-match；不宣稱否決））; it contains NO permission/conditional/consent
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
         * backstopped by the extraction particles rule (a particle anywhere in the
         * remainder denies) and the whitespace gate — never by execution.
         *
         * Gate 2.5 — terminal-position rule (#19 B3 stage-12.1; Re-review #12 A1, [P1/BLOCKER];
         * any branch, at extraction, not here). This rule runs inside [finishSymbolQueryExtraction]
         * (above on the CJK-preferred/EN two‑phase helper), not inside this affirmative gate: if the
         * last occurrence of either CJK symbol cue (`符號` / `符号`) in the remainder is followed by
         * ANY \p{L}|\p{N} code point (CJK ideographs, Kana, Hangul, Latin letters, fullwidth/halfwidth
         * digits), extraction Rejected (hasSymbolQuery=false ⇒ missingQuestionFor ⇒
         * [DenyReason.NEED_CLARIFICATION]; never executes, never FAST). Rationale: a CJK
         * `查<identifier>符號` is a complete affirmative command plus a unique identifier — trailing
         * content with any letter/digit is never part of that shape, it is a deferral/consent/condition/
         * follow-up clause (`查Foo符號等法務簽核`／`查Foo符號等同事核對`／`查Foo符號週五啟動`／
         * `查Foo符號三個工作天後啟動`／`查Foo符號隨時可以`／`查Foo符號以後再做` 皆澄清）. This ONE
         * structural rule closes the whole CJK-leading trailing-clause residual class — unlisted consent nouns,
         * weekday names, work-day offsets, 隔天／週末／隨時, and bare `之後|以後` with trailing text —
         * without another closed synonym enumeration of identities/dates. It fires only in the CJK-preferred/
         * non-enDelimited extraction phase (CJK-leading utterances without EN consumption). The EN-delimited
         * branches are exempt by the stage-8.3 verbatim contract (`symbols Foo符號xyz`（EN-leading），`查 symbols Foo符號`，`查 find 符號` 皆 FAST verbatim).
         * #19 B3 stage-13.1（Re-review #15 A1 [P1] + P2）—— closure：`查 symbols Foo符號等法務簽核`／`查 symbols Foo符號xyz` 不再 FAST——
         * stage-12.1 之 named residual 於 stage-13.1 封口：CJK 相 terminal-position rule Rejects（尾綴 `等法務簽核`／`xyz` 含 \p{L}）⇒
         * 整句 DENY／NEED_CLARIFICATION（hasSymbolQuery=false ⇒ missingQuestionFor）；EN fallback 不再救援（CJK 相 Rejected ⇒ no EN rescue）。
         * 非「已接受殘餘」——兩個 `查`-led 形態現皆終局澄清。Asymmetry 仍成立：EN-leading twin（`symbols 等法務簽核`、`symbols Foo符號xyz`）
         * 仍 FAST by stage-8.3（EN-leading 話語不經 CJK 相）。
         * 同時新增 CJK 相入口多 cue 規則：CJK-leading 且 `符號|符号` 計數 ≥ 2 ⇒ Rejected（ambiguity；P2 結構性封口）——
         * `查Foo符號等法務簽核符號`／`查Foo符號週五啟動符號`／`查 symbols 符號符號` 皆 DENY（EN-leading `symbols 符號符號` 不受影響）。
         * EN 命令形 `symbols …`（leading、delimiter-bounded）不變：`symbols Foo符號等法務簽核`／`symbols Foo符號xyz` 仍 FAST verbatim
         * （EN-leading 話語不經 CJK 相，規則不觸發）。CJK-leading 但 EN-delimited verbatim 形態已無例外：CJK 相或拒（A1）、或多 cue（P2）——
         * 兩規則均要求 AFFIRMATIVE_OPENER_CJK（CJK 相入口先行），EN-leading 話語維持 stage-8.3 carve-out 不變。
         * It is additive deny-only: it can only flip previously-FAST utterances to DENIED/NEED_CLARIFICATION,
         * never the reverse. Negation/interrogative/extraction existing NEED_CLARIFICATION semantics
         * are preserved (extraction now fails earlier, in the same fail-closed direction as the particles rule).
         *
         * Interaction with arms H–M of [PERMISSION_CONDITIONAL_CLAUSE]: those arms remain as
         * defense in depth. The arms fire at admission; the rule fires later in extraction. For
         * CJK-leading/non-enDelimited utterances the rule is the decisive reject for unenumerated
         * trailing nuclei; arms reject cue-PRECEDING nuclei (e.g. `查明天做 Foo符號` — arm K `明天`) and, on the EN side,
         * EN-opener utterances carrying CJK cues before they reach extraction (e.g. `symbols 等我簽核`
         * — admission arms deny before extraction ever runs).
         *
         * Over-deny (accepted, by design): any L/N after the final cue is rejected regardless
         * of semantics — identifiers containing CJK must end at the cue. `查 使用者(符號)`
         * (suffix `)`) and `查 get(符號)` (suffix `)`) stay FAST; a suffix of ONLY non-L/N
         * (punctuation/whitespace) tails that Step C peels (`[\s，,]*`) or Step D removes without re-exposing a cue stay FAST; other non-L/N tails (`。`, ZWSP, emoji) DENY via Step E/ambiguity guards (fail-closed — this is the very reason `查Foo符號。` clarifies) (recorded
         * residual); a suffix containing L/N is denied (`查Foo符號，一下` — `一`/`下` are \p{L}
         * ⇒ DENIED).
         *
         * EN is deliberately untouched: the EN single-word command form `symbols <operand>`
         * admits open-world operand vocabulary, and the terminal-position rule does not apply
         * to EN openers even when the utterance carries a CJK cue — EN behaviour is unchanged
         * (recorded; EN remains open-world, no terminal-position constraint is introduced).
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
            // 於閘層即被辨識（多 token 夾空白的補救顯性見 extraction particles rule backstop）。
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
         * 通用否定詞＋[EN_NEGATION_BEST_EFFORT]（#174 (c) 單一常數表：原 BASE／
         * #3 B3 re-review PROHIBITION／新增 `cannot`/`unwilling`/`reject\w*`/`hate\w*`；
         * 英文否定仍為 best-effort，未列動詞是殘餘，不宣稱 EN 覆蓋完整），
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
         * - 已收斂（#174，行為皆釘於 FastDispatchTest）：
         *   (a) 可見字母插入（`查 不一查 符號`）現由單顆粒 `不` marker 保守澄清
         *       （pin `visibleLetterSplitter`）；封閉複合詞（取消/拒絕等）中插入可見
         *       字母且完全沒有單顆粒旗標者仍為開放殘餘（字典不可封閉）；
         *   (b) Cyrillic/Greek 同形字（`查 nеver 符號`、`查 аvoid 符號`）由
         *       [hasConfusableNegationRun] 的 mixed-script run 規則保守否決
         *       （run 以 Zs 空白分界，run 內 ignorable 才剝離；故 `find symbol π`
         *       之類分開的 Latin/Greek token 仍 FAST），不列 homoglyph 白名單
         *       （pin `homoglyphNegationIsPinnedOrFolded`／`legitimateGreekIdentifierStaysFast`）。
         *       已知 residual：純 Cyrillic/Greek-only（全句無拉丁字母，如
         *       `查 Альфа 符號`／`查 αβγ 符號`）仍不命中——本閘捕獲 mixed-script
         *       homomorphism，不是外語詞否定（pin `pureConfusableScriptNotFlagged`）；
         *   (c) EN 清單收斂為 [EN_NEGATION_BEST_EFFORT]，補 `cannot`/`unwilling`/
         *       `reject\w*`/`hate\w*`/`can'?t`；`later`/`afterwards` 與未列舉動詞
         *       仍不涵蓋，新詞及既有 `ban\w*`/`den[iy]\w*` 對識別字前綴的
         *       過度澄清接受（fail-safe）；
         *   (d) 全形-only cue 不路由已有精確 pin（`fullwidthOnlyCue`）；
         *   (e) `符號是Foo嗎` → no-match（無准入 ⇒ 不進模板，reasonCode null）；
         *       `特別找` 現為 no-match（非命令式句首 ⇒ 不進模板，reasonCode null）；`查 查理 符號` → FAST `查理`（stage-6.3 位置語法，pinned）；
         *       `意思是符號` → no-match（無准入 ⇒ reasonCode null）；
         *   (f) CJK 異體字碼點（查 vs 査）未涵蓋。
         * - **任何 DESTRUCTIVE 工具翻 `executionReady` 前必須補齊本契約**
         *   （否定閘、mixed-script 同形字收斂、EN best-effort 清單與對應行為 pins）；
         *   READ-only 工具不因此被視為 DESTRUCTIVE 已完成。
         * - 命令式肯定語句帶否定顆粒者（如 `查 Foo 符號 不要` 型）才會進模板後閘轉澄清；
         *   非命令式句首者（如 `別問 Foo 符號`、`not symbols`、`我不想查 Foo 符號`）直接
         *   no-match DENIED——皆為 DENIED、絕不執行。
         * - `查 是 符號` → FAST `是`（#19 B3 stage-6.3：CJK 填充詞不再剝離——單一 L* token 殘餘保有；
         *   舊行為（→ clarify）為全域填充詞剝空所致，已隨該步驟退役，pinned）；
         *   `查 要 用 符號` → clarify（殘餘 `要 用` 含空白 ⇒ 澄清）。非命令式句首者一律 no-match：
         *   `什麼是符號`、`為什麼要用符號`、`符號是什麼`、`符號的意思`、
         *   `符號有哪些種類`、bare `symbols`、`What is a symbol?`、`Explain symbols`
         *   （無命令式准入 ⇒ 不進 symbol 模板；否定閘不介入）。
         * - `查 find 符號`/`查 search 符號` 等（stage-9.2 起）：CJK-leading 語句先讀
         *   CJK 語法——尾綴 cue 剝去後 `find`/`search` 為合法 verbatim 識別字
         *   ⇒ FAST `find`／`search`（stage-8.3 的 EN-delimited `符號` verbatim 解讀
         *   已被 CJK-preferred 兩階段取代——五支 stage-8.3 pin 翻轉（find/Symbol/symbols/lookup/locate；三支規格明列＋lookup/locate 同族同根），見 extraction
         *   KDoc 與 FastDispatchTest pins）；
         *   非 cue 位置的動詞不再剝離（見 extraction KDoc）。
         */
        // #174 (c) — EN negation corpus, hoisted so [NEGATION_HINTS] and
        // [NEGATION_MARKERS] cannot drift. BASE = the original best-effort \b set;
        // PROHIBITION = #3 B3 re-review explicit forbid/postpone family;
        // BEST_EFFORT adds `cannot`/`unwilling`/`reject\w*`/`hate\w*`/`can'?t`. Each
        // inclusion is deliberately OVER-CLARIFYING (fail-safe): `reject`/`hate`
        // with `\w*` also catch identifier prefixes (`RejectController`,
        // `HateHandler`) and `cannot`/`unwilling` are closed `\b` words.
        // `\bban\w*\b` likewise over-covers `banana` — accepted fail-safe.
        // The alternative (leave them out) requires behavior pins in FastDispatchTest
        // and is unacceptable once a DESTRUCTIVE tool becomes executionReady.
        // Words NOT in this table (`later`, `afterwards`, unseen verbs) stay an
        // explicit best-effort residual — never claim EN completeness.
        private val EN_NEGATION_BASE = listOf(
            r("\\bnever\\b"), r("\\bnot\\b"), r("\\bno\\b"),
            r("\\bdon'?t\\b"), r("\\bcan'?t\\b"), r("\\bdo not\\b"), r("\\bstop\\b"), r("\\bcancel\\b"),
            r("\\brefus\\w*\\b"), r("\\bavoid\\w*\\b"), r("\\bskip\\w*\\b"), r("\\bwithout\\b"),
        )
        private val EN_NEGATION_PROHIBITION = listOf(
            r("\\bforbid\\w*\\b"), r("\\bprohibit\\w*\\b"), r("\\bban\\w*\\b"),
            r("\\bpostpone\\w*\\b"), r("\\bsuspend\\w*\\b"), r("\\bdefer\\w*\\b"),
            r("\\bden[iy]\\w*\\b"), r("\\bhold\\s*off\\b"),
        )
        private val EN_NEGATION_BEST_EFFORT =
            EN_NEGATION_BASE + EN_NEGATION_PROHIBITION + listOf(
                r("\\bcannot\\b"), r("\\bunwilling\\b"),
                r("\\breject\\w*\\b"), r("\\bhate\\w*\\b"),
            )

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
            *(EN_NEGATION_BEST_EFFORT.toTypedArray()),
        )

        // Conservative negation/uncertainty gate: ANY presence of a negation word
        // clarifies — phrases are open-ended, so a closed verb table cannot be complete.
        // The two tables are COMPLEMENTARY: [NEGATION_MARKERS]
        // holds the single-particle set （不 沒 没 別 别 勿 莫 嫑 否 無 无 免 未 毋 休） plus the
        // English \b set ([EN_NEGATION_BEST_EFFORT], shared with [NEGATION_HINTS])
        // — the original `no`/`not`/`never`/`don'?t`/`do not`/`stop`/`cancel`/`refus\w*`/`avoid\w*`/`skip\w*`/`without`,
        // #3 B3 re-review prohibition/postponement family, plus #174 (c)
        // `cannot`/`unwilling`/`reject\w*`/`hate\w*`/`can'?t` (over-clarifies identifiers;
        // fail-safe). [NEGATION_HINTS] holds the multi-verb compound negations
        // plus general forbiddens, whose unique load-bearing contribution is
        // {取消,停止,禁止,算了} plus the 18 prohibition/deferral compounds
        // (拒絕/拒绝/暫緩/暂缓/延緩/延缓/延後/延后/推遲/推迟/延遲/延迟/延期/推後/推后/寬限/宽限/延宕 —
        // none of which contain a single-particle marker, so they are NOT a
        // subset of this gate). The single-particle set, those four words and
        // those 18 compounds are all load-bearing and must not be deleted;
        // [isNegated] ORs both over the three texts (raw, NFKC-removed,
        // NFKC-spaced) plus the #174 (b) confusable-script run. Over-clarification is the
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
            *(EN_NEGATION_BEST_EFFORT.toTypedArray()),
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
         * B3 symbol-query extraction (#19 stage-6.3, positional cue grammar;
         * #19 B3 stage-9.2 兩階段 CJK-preferred-fallback):
         * remainder extraction for the symbol template. The pipeline is
         * **cue-position only** — it strips exactly what is anchored at
         * position 0 (openers, one leading EN keyword cue, the leading-EN
         * connective run) and exactly one trailing CJK keyword-suffix run,
         * and PRESERVES everything intra-token verbatim. No identifier content
         * is ever rewritten: a remainder that does not reduce to a single,
         * allow-listed, L/N-anchored token is REJECTED (extraction fails ⇒
         * NEED_CLARIFICATION) — never silently rewritten into a different
         * query and executed.
         *
         * Two-phase extraction (stage-9.2, Re-review #9 A2; stage-13.1, Re-review #15 A1 [P1] + P2 型別化): when a CJK opener
         * （SYMBOL_OPENER_CJK）actually leads the utterance, the post-opener
         * remainder is FIRST parsed by the CJK branch alone (suffix-cue peel +
         * both ambiguity guards + Step-B′ EN connectives + Step D edge trim —
         * NO EN cue machine) via the shared
         * [finishSymbolQueryExtraction] helper. A [SymbolExtract.Parsed] result returns
         * immediately; a [SymbolExtract.NoShape] CJK pass (the remainder is NOT CJK
         * grammar at all — currently the Step-E whitespace gate) falls through
         * to the EN pipeline below; a [SymbolExtract.Rejected] CJK pass HARD-DENIES
         * the whole utterance — the EN fallback is NOT run (stage-13.1: a negative
         * CJK decision is never laundered into a positive EN verdict).
         * CJK-leading with `符號|符号` count ≥ 2 is Rejected at the CJK-phase entry
         * (multi-cue ⇒ structurally ambiguous; stage-13.1 P2 結構性封口). Both phases share [finishSymbolQueryExtraction],
         * so the CJK and EN post-opener paths cannot drift on Steps C–E.
         * CJK-opener consumption sets neither enDelimited nor the
         * `symbols?`-cue budget; EN cue/verb/CMD consumption sets both.
         * Cue budget (stage-9.2): a `symbols?` cue consumed by Step A CMD is
         * counted, and Step B — the other cue consumer — RUNS AT MOST ONCE
         * TOTAL per utterance (skipped entirely once CMD spent the budget);
         * Step A CMD itself still consumes at most once. Without the budget a
         * legal `Symbol(s)` identifier was destroyed (`find symbol symbol` /
         * `symbols symbols` mapped to 空 ⇒ 澄清 — stage-9.2, Re-review #9 A2).
         * `查 Symbol 符號` → FAST `Symbol`、`查 symbols 符號` → FAST
         * `symbols`、`查 find 符號` → FAST `find`（五支 stage-8.3 pin 翻轉；三支規格明列＋lookup/locate 同族同根），
         * EN 動詞/ cue 形字詞被 CJK 語境繫結為識別字 verbatim——READ-only 查詢、
         * 不改寫；CJK-leading 語句一律先讀 CJK 語法）；`find symbol symbol` ⇒
         * FAST `symbol`、`symbols symbols` ⇒ FAST `symbols`、
         * `find symbol Foo` ⇒ FAST `Foo`、`查 Symbol Foo` ⇒ FAST `Foo`、
         * `查 Foo符號 符號` 仍澄清（CJK 相 ambiguity guard Rejected ⇒ 終局 deny，EN 相不入——stage-13.1 A1）。
         * Steps, in application (code) order:
         *
         * Step A — anchored openers (each at most once, start-anchored):
         *   EN: `^(?:please[\s,]+)?(?:find|search|lookup|locate)\b[\s,]*`
         *       `(?:for\s+|the\s+|a\s+|an\s+)?` — the verb plus ONE optional
         *       post-verb connective (`find a symbol Foo` → `symbol Foo`);
         *   CJK: `^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\s，,]*)?`
         *       + `SYMBOL_CJK_COMMAND_TOKEN` + `[\s，,]*(?:一下)?[\s，,]*` — optional
         *       politeness prefix + CJK command token
         *       （複合 查詢|查询|搜尋|搜寻|搜索|查找|尋找|寻找|找尋|找寻 longest-match-first
         *       排前、單字 查/找/尋/搜/詢/寻/询 殿後——Java 交替同位置有序首中 ⇒
         *       複合先＝最長匹配；首字為單字 opener 的兩字動詞視為剩餘識別字原樣執行
         *       （如 `詢問Foo符號` ⇒ FAST query=`問Foo`；記錄殘餘；字典無窮不可封閉枚舉）
         *       + optional 一下;
         *   command form A: `^symbols?\b[\s]*` — `symbols Foo` → `Foo`.
         *   Applied sequentially (CJK → EN → command), so `查 find 符號`
         *   reduces to `find 符號` and the CJK-preferred phase (stage-9.2)
         *   peels the suffix cue ⇒ FAST `find` (see the two-phase note above;
         *   EN 動詞形字詞於 CJK 語境為識別字 verbatim).
         *   #19 B3 stage-9.2 — EN-started utterances bind the verb/cue shape
         *   as the identifier the same way when no CJK opener leads:
         *   `find symbol symbol` / `symbols symbols` ⇒ FAST `symbol` /
         *   `symbols`（cue budget 1：Step A CMD 已耗，Step B 跳過）。
         * Step B — ONE leading EN `symbol(s)` cue (`^symbols?\b[\s,]*`,
         *   IGNORE_CASE, word-anchored), stripped at most once:
         *   `symbols Foo` → `Foo`, `Symbol Foo.` → `Foo.`,
         *   (stage-9.2：Step A CMD 已耗 cue budget ⇒ Step B 跳過，殘餘
         *   `named Foo` 由 Step B′ 剝 `named ` ⇒ FAST `Foo`；修前 Step B 本就
         *   不匹配 `named …`，B′ 同樣剝離——行為逐位元不變). Carries the EN keyword
         *   semantics for "symbol named X". Nothing later in the remainder
         *   is touched (never a global `\bsymbols?\b` delete —
         *   `查 Foo.symbols 符號` keeps `Foo.symbols` verbatim).
         *   #19 B3 stage-9.2 — Step B RUNS AT MOST ONCE TOTAL per utterance:
         *   when Step A CMD consumed a `symbols?` cue, Step B is skipped
         *   outright (cue budget of 1 already spent by `symbols?`).
         * Step C — peel at most ONE trailing CJK keyword-SUFFIX cue,
         *   END-anchored, then apply the ambiguity guard (stage-7.3, A3;
         *   EN-delimited 殘餘不進本步驟——見下方 carve-out):
         *   EN-delimited carve-out (stage-8.3, #19 Re-review #8 A2): 若殘餘由
         *   EN opener／EN `symbol(s)` command form 劃定（SYMBOL_OPENER_EN／
         *   SYMBOL_OPENER_CMD／SYMBOL_LEADING_SYMBOLS 任一消費——各以 replaceFirst
         *   前後字串比對判定實際改動；CJK opener 消費不設旗標），查 token 為
         *   EN grammar 劃定之 verbatim identifier——Step C 不剝離、兩道歧義
         *   守衛（pre-Step-B′／post-Step-D）不適用；僅限單一 token
         *   （空白 ⇒ Step E 澄清）。CJK 純分支（enDelimited=false，無任何 EN
         *   消費——`查 Foo符號 符號` 等）逐位元不變（維持雙 cue 澄清）；
         *   #19 B3 stage-9.2 — 兩階段改寫後，「CJK opener 後接 EN 消費」的
         *   混合形態改走 CJK-preferred 優先：CJK 階段先解析（剝尾綴 cue、雙守衛）；
         *   #19 B3 stage-13.1（Re-review #15 A1+P2）起：CJK 階段 **Rejected ⇒ 整句 DENY，EN fallback 不再救援**——
         *   僅 CJK 階段回傳 NoShape（目前僅 Step-E whitespace/empty 閘；如 `查 Symbol Foo`/`查 symbols Foo` 類）才落到
         *   EN pipeline；此時 EN 消費（SYMBOL_OPENER_CMD `symbols `）即設
         *   enDelimited + cue budget（Step B 跳過），Step C 不剝離 ⇒ FAST（verbatim 綁定）。
         *   CJK-leading 且 `符號|符号` 計數 ≥ 2（如 `查 symbols 符號符號`；計數施於 NFKC+Cf 剝離探針
         *   [clauseProbeJoined]，關閉 Cf 拆分繞過；`符號符號` ⇒ 2，`符號`＋`符号` 合計 ⇒ 2）⇒ CJK 相入口 Rejected ⇒ DENY/澄清
         *   （stage-13.1 P2；不再經 EN fallback 洗成 `符號符號`；撤回類「識別字夾雙 cue」——含 `查 符號表 符號`／`查 符號Extra 符號`，
         *   原 FAST 釘已全數撤回 ⇒ DENIED/NEED_CLARIFICATION）；CJK-leading 且尾綴含 L/N（如 `查 symbols Foo符號xyz`、
         *   `查 symbols Foo符號等法務簽核`）⇒ terminal-position rule Rejected ⇒ DENY（stage-13.1 A1；stage-12.1 named residual 封口，
         *   皆為 unpinned-或已列舉之翻轉釘）。CJK「有尾綴 cue 明確資料」敘述僅適用於未經 EN 消費之 CJK 殘餘；
         *   EN 消費發生於 CJK 階段 NoShape 後的 EN fallback，而非 CJK 階段本身。
         *   例：`symbols Foo符號`／`find symbol Foo符號`／`search symbols Foo符號`
         *   → FAST `Foo符號`；`symbols 符號`（目標名「符號」）→ FAST `符號`；
         *   `查 symbols Foo符號`（混合 opener）→ FAST `Foo符號`；
         *   `symbols Foo符號。` → Step D 剝 `。` → FAST `Foo符號`；
         *   「verbatim」＝零 cue 處理（Step C／兩道守衛），不排除共用的 Step D 前緣剝離
         *   （`symbols Foo::` → FAST `Foo::`（stage-9.3 起 verbatim）、`symbols Foo符號。` → FAST `Foo符號`，同類共用）。
         *   `symbols Foo符號 extra`／`symbols Foo 符號`／`symbols 符號 符號`
         *   → 空白 ⇒ Step E 澄清。CJK 分支逐位元不變（`查 Foo符號 符號`
         *   澄清、`查 符號` 澄清、`查 Foo 符號` → FAST `Foo`）。
         *   a single match of
         *   `(?>[\s，,]*)(?:這個|这个|一下)?(?>[\s，,]*)(?:符號|符号)(?>[\s，,]*)$` is
         *   removed (stage-8.2: the flat separator slots are atomic groups — a pure
         *   separator run is never re-partitioned between adjacent slots on a failure
         *   path; success-path matches are byte-identical) — e.g. ` 這個符號`, ` 这个符號`, `一下符號`, ` 符號`
         *   suffixes:
         *   `查 AlarmManager這個符號` → `AlarmManager` (glued single cue),
         *   `查 AlarmManager 這個符號` → `AlarmManager`,
         *   `查 AlarmManager 这个符號` → `AlarmManager`,
         *   `查 使用者 符號` → `使用者`；`查 符號表 符號` ⇒ 澄清（雙 cue ⇒ CJK 相入口 Rejected；撤回類「識別字夾雙 cue」，stage-13.1 P2）。
         *   Ambiguity guard (stage-7.3, A3): after the ONE peel, if the
         *   remainder STILL matches the suffix cue at its end — or is exactly
         *   that cue — the double-cue shape is structurally ambiguous
         *   (`Foo符號 符號`, `Foo符号符號`, `Foo符号 符號`, `Foo 符號 符號`,
         *   `符號 符號`) ⇒ extraction FAILS ⇒ NEED_CLARIFICATION (never a
         *   rewritten query):
         *   `查 Foo符號 符號` → NEED_CLARIFICATION,
         *   `查 Foo符号符號` → NEED_CLARIFICATION,
         *   `查 符號 符號` → NEED_CLARIFICATION.
         *   CJK identifiers ending in `符號` queried as a bare glued
         *   `查 X符號` DO execute with X — the single trailing cue is
         *   consumed: `查 使用者符號` → FAST `使用者`（CJK 分支不變）。
         *   EN-delimited 分支則相反（stage-8.3 carve-out）：EN 命令形後的
         *   identifier 原樣保留——`symbols Foo符號` → FAST `Foo符號`
         *   （cue 為 identifier 的一部分、不被剝離）；CJK-opened 形態仍剝
         *   尾綴 cue（`查 Foo符號` → FAST `Foo`）。「識別字以 `符號` 結尾」的
         *   limitation 因此僅餘 CJK 分支。
          *   `函式|函数` are NO LONGER strip words (neither globally nor as
          *   suffix cues): `查 函式 符號` → `函式` (intended FAST),
          *   `查 Foo 函式 符號` → multi-token ⇒ NEED_CLARIFICATION.
         *   Do NOT strip anything intra-remainder.
         * Step B′ — leading EN connectives stripped, REPEATEDLY, but only when
         *   another token remains after the connective
         *   (`^(?:for|the|a|an|named|called|of|to)\s+(?=\S)`, IGNORE_CASE; the
         *   `named|called` alternative is identical to the old Step D rule —
         *   merged, one loop). `locate symbol named Foo` → `Foo`; a bare
         *   `a` / `to` / `for` at the start of the remainder with NOTHING
         *   after it is NOT stripped (a single-letter identifier stands:
         *   `查 a 符號` → `a`). Applied AFTER the trailing suffix run (step C
         *   runs first, THEN this loop — prose order matches application
         *   order) so the suffix cue cannot mask the "another token remains"
         *   guard.
         * Step D — edge trim ONLY: runs of
         *   LEADING `[\s，。、？?「」『』“”"'（）]`（stage-8.4：前導類不再含
         *   `:`（U+003A）／`：`（U+FF1A））and TRAILING
         *   `[\s，。、？?「」『』“”"'（）]`（stage-9.3：尾端類亦不再含
         *   `:`／`：`；stage-8.2 possessive `++$` 保留無誤）
         *   stripped repeatedly. Nothing
         *   intra-token is ever removed by this step.
         *   Stage-8.4（Re-review #8 A3）：前導 colon 不再剝離——C++
         *   global-namespace 前綴 `::Foo` 與 `Foo` 為不同 scope，一律原樣保留
         *   verbatim（leading colons 一律不剝/不改寫）；單冒號 `:Foo`
         *   （U+003A）亦原樣保留（`查 :Foo 符號` → FAST `:Foo`，記錄，READ-only）；
         *   全形 `：`（U+FF1A）同樣不被前導類剝除，但 Step E 是 ASCII-punct-only
         *   ⇒ `查 ：Foo 符號` 拒絕 ⇒ NEED_CLARIFICATION（fail-closed，絕不
         *   rewrite 成 `Foo`）。
         *   Stage-9.3（Re-review #9 A3, [P2]）：尾端 colon 類亦不再剝除——C++
         *   scoped qualified identifier 之尾端 `::` 為合法 token（`Foo::` 與 `Foo`
         *   為不同 scope 語義），與 stage-8.3/8.4 verbatim-preservation 一致——一律原樣
         *   保留 verbatim：`symbols Foo::`／`查 Foo:: 符號`（Step C 剝尾綴 `符號` ⇒
         *   `Foo::`，純 CJK 分支）／`查 symbols Foo::`（EN-delimited）→ FAST `Foo::`；
         *   `查 ::std::vector:: 符號` → FAST `::std::vector::`（COI token `::Foo` 兩分支對齊：
         *   前導 stage-8.4 verbatim、尾端 stage-9.3 verbatim）；`查 Foo: 符號` 現在 verbatim
         *   `Foo:`（stage-8.2/8.4 時釘為 `Foo`——stage-9.3 翻轉，記錄）；尾端全形 `：`（U+FF1A）
         *   不再剝除 ⇒ Step E 拒絕 U+FF1A ⇒ NEED_CLARIFICATION（fail-closed，與 FF1A
         *   reject 對稱；`查 Foo： 符號`／`symbols Foo：`，≥ stage-8.2 pin 改釘澄清）；
         *   `查 Foo。 符號` → FAST `Foo`（`。` 尾仍剝，不受影響）；`查 ::符號 符號`
         *   之歧義守衛照舊澄清（belt-and-suspenders，pinned）。
         *   NOTE: halfwidth `!` and
         *   the halfwidth parens are deliberately NOT in the edge class —
         *   `查 ! Foo`-class inputs must clarify, and `get(id)` must survive
         *   verbatim (intra parens are allow-listed, never edge-stripped);
         *   halfwidth `?` stays (trailing question particle), as do the
         *   fullwidth `（）？` CJK edge shapes. `-` is no longer in the edge
         *   class either — `查 -main 符號` FASTs with query `-main` verbatim
         *   (Clojure-family identifiers). `.` is NOT in the edge class:
         *   `查 .bar 符號` FASTs with query `.bar` — recorded as accepted.
         *   `查 Foo. 符號` NO LONGER FASTs with query `Foo.` — see step E.
         * Step E — immutable remainder validation (replacing the old
         *   global-deletion aftermath): after steps A–D the remainder is
         *   NEVER rewritten, only admitted or rejected —
         *   (i) an EMPTY remainder fails;
         *   (i-bis) a remainder OPENING with `,` `，` `;` `；` fails
         *   (stage-7.4, A4 — the halfwidth forms are allow-list-legal and need
         *   this explicit rejection; the fullwidth forms are unreachable in (iv));
         *   (ii) ANY remaining whitespace fails;
         *   (iii) a remainder ending in `.` `,` `;` fails
         *   (sentence-final natural-punctuation hazard; a trailing `:` is
         *   NO LONGER eaten by step D's edge class (stage-9.3) — it is kept
         *   verbatim instead and is allow-list-legal here, so no `:` check
         *   is kept; a fullwidth `：` (U+FF1A) is rejected by (iv) instead;
         *   `Foo.bar` / `3,14` / `std::vector` / `get(id)` / `-main`
         *   keep verbatim because they do
         *   not end there and pass the anchor check); (iv) every code point
         *   must be Unicode letter (L*) or number (N*), or one of
         *   `. _ - $ # @ / ( ) : , ; + = ~` — else fails (incl. Format/Cf and
         *   control chars); (v) the remainder must carry at least one
         *   letter-or-number code point. Failing ⇒ the CJK phase returns Rejected; the EN-phase-only/generalist failure returns Rejected at call-site (NoShape emerges only from Step-E whitespace/empty, mapped at call site on EN phase to Rejected)（stage-13.1：
         *   [SymbolExtract]；CJK 相 ⇒ 整句 DENY，EN fallback 不救援；EN 相 ⇒ deny）⇒
         *   [hasSymbolQuery] false ⇒ [missingQuestionFor] ⇒
         *   NEED_CLARIFICATION (never a rewritten query).
         *   Additional standing rule (stage-6.1, unchanged): a remainder
         *   containing 嗎|吗|呢 ANYWHERE fails extraction (fail-closed).
         *
         * The extracted remainder is used intra-token verbatim (esc-escaped)
         * as the query argument — qualified names pass through losslessly:
         * `查 Foo.bar 符號` → `Foo.bar`, `查 Foo.symbol.bar 符號` →
         * `Foo.symbol.bar`, `查 foo/symbol.kt 符號` → `foo/symbol.kt`,
         * `查 Foo::symbol 符號` / `查 std::vector 符號` → `::` preserved,
         * `查 ::Foo 符號` / `查 ::std::vector 符號` → `::Foo` / `::std::vector`
         * （stage-8.4：前導 `::` 原樣保留 verbatim；前導冒號非剝離類）,
         * `查 Foo:: 符號` / `查 ::std::vector:: 符號` → `Foo::` / `::std::vector::`
         * （stage-9.3：尾端 `::` 亦原樣保留 verbatim；尾端冒號非剝離類）,
         * `查 Foo.symbols 符號` → `Foo.symbols` (intra-`symbols` survives),
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
         * - `查 查理 符號` → FAST `查理` (stage-6.3; the old global
         *   `SYMBOL_CJK_FILLER` delete rewrote it to `理` — removed).
         *   `查理 符號` (UNspaced) still FASTs with `理` — the lookup verb `查`
         *   is the start-anchored opener, a positional strip, pinned.
         * - 「特別」 is not an opener (starts with 特) ⇒ no-match DENIED;
         *   「特別找」likewise never reaches this path.
         * - `查Symbol`-class shapes: `查 SymbolTable 符號` keeps the
         *   identifier verbatim; no word boundary ⇒ survived even before.
         * - Intra-comma is preserved: `查 3,14 符號` → `3,14` — the comma is a
         *   legal intra-token character now (never edge-stripped mid-token).
         * - Anything not matching the allow-list, or a remainder ending in
         *   `. , ;` (the comma only for cue-less remainders — a glued
         *   halfwidth comma at the suffix cue is absorbed by step C), or
         *   still carrying whitespace (steps E) ⇒ clarification, never a
         *   rewritten query.
         * - `查 Foo. 符號` ⇒ NEED_CLARIFICATION (stage-6.3; previously FAST
         *   with `Foo.` — retired residual). `search symbol Foo.` /
         *   `search symbol Foo,` / `search symbol Foo;` — same clarification
         *   (step E (iii)).
         * - `3.14`-class names keep their `.`; `get(id)` keeps `( )`;
         *   `foo/bar.kt` keeps `/`; `std::vector` keeps `::`.
         * - `查 a b 符號` strips the leading connective `a ` (another token
         *   follows) leaving `b` — article-stripping at a cue position, the
         *   same mechanic as `a Foo` → `Foo`; the two tokens are
         *   indistinguishable at this position and the rewrite is
         *   deterministic (pinned).
         * - `查 函式 符號` → FAST `函式` (stage-6.3; `函式`/`函数` are no longer
         *   strip words — intended behavior change, pinned).
         * - `查 是 符號` → FAST `是` (stage-6.3; CJK filler substrings are no
         *   longer deleted — the single-token remainder stands on its own;
         *   the old strip-to-empty → clarify behavior is retired, pinned).
         * - `查 符號表 符號` ⇒ 澄清/NEED_CLARIFICATION（雙 cue ⇒ CJK 相入口 Rejected；stage-13.1 P2 撤回類「識別字夾雙 cue」——
         *   原 FAST 釘已撤回，與 stage-6.3「filler-substrings 不再刪除」無關，兩 cues 令本形態歧義）;
         *   `查 使用者 符號` → FAST `使用者` (stage-6.3; filler-substrings no longer deleted, pinned).
         * - Halfwidth `:` is no longer in the edge class's TRAILING branch either
         *   (stage-9.3, Re-review #9 A3 — the LEADING branch dropped `:`／`：` at
         *   stage-8.4, the TRAILING branch now too): `查 Foo: 符號` → FAST `Foo:`
         *   (stage-8.2/8.4 時釘為 `Foo`，stage-9.3 翻轉 — C++ scoped qualified identifier
         *   尾端 `::` 為合法 token，verbatim 保留); `查 Foo:: 符號` → FAST `Foo::`;
         *   `symbols Foo::` / `查 symbols Foo::` → FAST `Foo::`; the leading
         *   `::Foo` shape is preserved verbatim (stage-8.4, Re-review #8 A3 —
         *   C++ global-namespace prefix, never rewritten to `Foo`); `查 :Foo
         *   符號` → FAST `:Foo`（記錄，READ-only）；`查 ：Foo 符號`（全形
         *   U+FF1A）Step E 拒絕 ⇒ NEED_CLARIFICATION（fail-closed）；尾端 `：`（U+FF1A）
         *   同樣不再剝除 ⇒ `查 Foo： 符號`／`symbols Foo：` ⇒ Step E 拒絕 ⇒
         *   NEED_CLARIFICATION（fail-closed，與 FF1A reject 對稱）。
         *   `.` `;` are
         *   NOT in the edge class (`查 Foo. 符號` / `查 Foo; 符號` fail
         *   step E). A halfwidth comma glued to an identifier at cue
         *   position is absorbed by the step-C suffix cue (`查 Foo, 符號` →
         *   FAST `Foo`; `查 Foo， 符號` / `查 Foo，符號` → FAST `Foo`), so
         *   step E's `endsWith(',')` is reachable only for cue-less
         *   remainders (e.g. `search symbol Foo,` — clarifies, pinned).
         * - Double-cue glued shapes are now NEED_CLARIFICATION (stage-7.3,
         *   A3 — ambiguity, not a rewritten query): after the ONE step-C
         *   peel, a remainder still ending in a suffix cue (or exactly that
         *   cue) is structurally ambiguous and extraction FAILS —
         *   `查 Foo符號 符號` → NEED_CLARIFICATION,
         *   `查 Foo符号 符號` → NEED_CLARIFICATION,
         *   `查 Foo符号符號` → NEED_CLARIFICATION,
         *   `查 Foo 符號 符號` → NEED_CLARIFICATION,
         *   `查 符號 符號` → NEED_CLARIFICATION,
         *   `查 符號` → NEED_CLARIFICATION.
         *   (stage-6.3 recorded `查 Foo符号 符號` → FAST `Foo` as a
         *   second-step-C-peel residual — retired; glued double cues are
         *   clarifications now.)
         *   CJK identifiers ending in `符號` queried as a bare glued
         *   `查 X符號` DO execute with X (the single trailing cue is
         *   consumed): `查 使用者符號` → FAST `使用者` — a single glued cue is
         *   unambiguous. Identifiers that END in `符號` are queryable ONLY via
         *   the EN-delimited command forms (stage-8.3 carve-out): `symbols
         *   Foo符號` / `find symbol Foo符號` / `search symbols Foo符號` /
         *   `查 symbols Foo符號` (mixed opener) → FAST `Foo符號` — the cue is
         *   part of the EN-delimited verbatim identifier; the delimiter-side
         *   peel is skipped entirely (Step C 不剝離、歧義守衛不適用). CJK-opened
         *   shapes still peel (`查 Foo符號` → FAST `Foo`) — 「識別字以 `符號`
         *   結尾」的 limitation 僅餘 CJK 分支。
         * - Leading-colon token-body residual (stage-8.4, accepted verbatim-preservation):
         *   leading `:`/`::` 一律不剝後，Step E 的 ASCII allow-list（含 `, ; + = ~` 等）內
         *   緊鄰冒號的 token body 亦原樣入查詢——`查 ::,Foo 符號` ⇒ FAST `::,Foo`／
         *   `查 :;Foo 符號` ⇒ FAST `:;Foo`（單 token、無空白、字母/數字字符類＋allow-list 標點、
         *   JSON-escaped、READ-only、LSP opaque match、executor 端 parseArgs 二次把關）。
         *   與 `.bar`／`-main` 同一已接受殘餘類；絕不因此放寬 Step E。
         * - Automation-phrase-identifier residual (stage-8.5, accepted): affirmative symbol
         *   query 之識別字本身可以是中文自動化片語——`symbols 幫我自動填表` ⇒ FAST
         *   query=`幫我自動填表`；`查 幫我點 符號` ⇒ FAST query=`幫我點`；
         *   `查 自動操作 符號` ⇒ FAST query=`自動操作`（本檔 stage-8.5 前因 SLOW_HINTS
         *   substring 搶先而被錯配 gui.automate／FLAVOR_BLOCKED——已翻轉）。
         *   識別字內容一律視為 LSP 內容（不透明），非自動化權限；fast lane 不執行任何
         *   自動化動作（自動化語句仍落 SLOW，未接線把關）。
         * - CJK-leading single-token CJK-preferred-fallback residual (stage-9.2, accepted verbatim):
         *   CJK-leading 語句先讀 CJK 語法 ⇒ CJK 階段通過後，含裸 `find|search|lookup|locate|symbol(s)`
         *   字面 token 的識別字原樣執行（`查 search 符號`⇒`search`、`查 symbol`⇒`symbol`、
         *   `查 locate 符號`⇒`locate`、`查 Symbols 符號`⇒`Symbols`（大小寫不敏感）以及雙 cue 混合
         *   形態（`查 symbols symbols`⇒`symbols`、`find symbol symbols`⇒`symbols`）皆 FAST；
         *   verbatim 識別字 binding、READ-only、escal() JSON-escape、L/N 錨點仍由 Step E 把關；不改寫、
         *   不執行 consent/條件形態）。EN-started 語句（如 `find symbol Foo`）亦依 cue-budget 語義保證。
         * - EN-delimited glued-cue residual (stage-8.3, accepted verbatim-
         *   preservation rule): `symbols 符號符號` → FAST `符號符號`（EN grammar
         *   劃定之 identifier——glued cue 原樣保留為查詢字串 `符號符號`；
         *   歧義守衛不適用、不剝離）。Accepted verbatim-preservation rule,
         *   READ-only、有界單 token LSP query。本 carve-out 僅 EN-leading twin 適用：
         *   `symbols 符號符號`（EN-leading、無 CJK opener）⇒ FAST `符號符號`；
         *   mixed opener `查 symbols 符號符號` 已撤回（撤回類「識別字夾雙 cue」；stage-13.1 P2）：
         *   CJK-leading 且 `符號|符号` 計數 ≥ 2（NFKC+Cf 剝離探針）⇒ CJK 相入口 Rejected ⇒ DENY/澄清，
         *   EN fallback 不再救援（`符號符號` 不再原樣綁定）；
         *   enDelimited=false 之 CJK 分支不援用本規則。
         *   前緣剝離殘餘：`symbols ？符號` → Step D 剝 `？` → FAST `符號`
         *   （en-delimited，歧義守衛不適用；L/N 錨點仍由 Step E 把關）。
         *   前導 colon 之互動（stage-8.4）：leading `::` 一律原樣保留 verbatim，
         *   兩分支皆然——enDelimited=true（`symbols ::Foo`／`find symbol
         *   ::Foo`／混合 opener `查 symbols ::Foo`）與 enDelimited=false
         *   （`查 ::Foo 符號`）皆適用（Step D 前導類不再含 `:`／`：`）；
         *   全形 `：`（U+FF1A）不論分支皆由 Step E 拒絕 ⇒ 澄清（fail-closed）。
         *   Only DOUBLE-cue-glued shapes (`X符號符號` / `X符號 符號`) clarify —
         *   CJK-delimited branch only; the EN-delimited branch never reaches the
         *   double-cue guard.
         *   (A chimera `这個符號` — simplified 这 U+8FD9 + traditional
         *   個 U+500B — matches NEITHER `這個` nor `这个` suffix alternative,
         *   so the remainder stays multi-token and clarifies:
         *   `查 AlarmManager 这個符號` → NEED_CLARIFICATION, pinned.)
         * - EN keyword-inline comma (stage-7.4, A4 — CLOSED; was stage-6.3
         *   documented-residual route): `find symbol,Foo` → FAST `Foo` —
         *   step A's command-form opener (`^symbols?\b[\s,，]*`) now eats the
         *   keyword AND a glued or following inline comma; `find symbol, Foo`
         *   → FAST `Foo`. A stray leading SEMICOLON — which no opener eats —
         *   never executes: `查 ;Foo 符號` / `查 ；Foo 符號` / `search symbol
         *   ;Foo` clarify via step E's leading-punctuation rejection
         *   (stage-7.4). The CJK/EN openers absorb a stray user-typed comma
         *   after the command by design (`查 ,Foo 符號` / `查 ，Foo 符號` /
         *   `search symbol ,Foo` → FAST `Foo`) — the comma is part of the
         *   (CJK/EN-verb/command-form) opener separator class; halfwidth `.`
         *   stays legal (`查 .bar 符號`
         *   → FAST `.bar`, pinned). Bare `symbols,Foo` (no imperative verb)
         *   fails the affirmative-command admission gate and is no-match
         *   DENIED — never FAST. The old `,Foo` residual never executes again.
         *
         * Invalid-charset / multi-token / cue-less remainders never execute:
         * they fail extraction (empty) and route NEED_CLARIFICATION via
         * [missingQuestionFor] ("請問要查哪個符號？").
         */
        private val SYMBOL_OPENER_CJK = Regex(
            "^(?:(?:請|麻煩|帮我|幫我|给我|給我|请)[\\s，,]*)?" + SYMBOL_CJK_COMMAND_TOKEN + "[\\s，,]*(?:一下)?[\\s，,]*",
        )
        private val SYMBOL_OPENER_EN = Regex(
            "^(?:please[\\s,]+)?(?:find|search|lookup|locate)\\b[\\s,]*(?:for\\s+|the\\s+|a\\s+|an\\s+)?",
            RegexOption.IGNORE_CASE,
        )
        private val SYMBOL_OPENER_CMD = Regex("^symbols?\\b[\\s,，]*", RegexOption.IGNORE_CASE)
        // #19 B3 stage-6.3 (positional cue grammar) — Step B keeps exactly ONE
        // leading EN `symbol(s)` cue, word-anchored, stripped at most once
        // (e.g. `symbols Foo` → `Foo`, `Symbol Foo.` → `Foo.`); it never
        // touches anything later in the remainder.
        // #19 B3 stage-9.2 (Re-review #9 A2) — Step B runs AT MOST ONCE TOTAL
        // per utterance: skipped entirely when Step A CMD already consumed a
        // `symbols?` cue (cue budget of 1 spent). Step A CMD consumes at most
        // once as well.
        private val SYMBOL_LEADING_SYMBOLS = Regex("^symbols?\\b[\\s,]*", RegexOption.IGNORE_CASE)
        // #19 B3 stage-7.3 (A3) — Step C: trailing CJK keyword-suffix cue,
        // END-anchored. ONE suffix cue is peeled; if the remainder STILL ends with a
        // suffix cue (or is exactly that cue), the double-cue shape is structurally
        // ambiguous (`Foo符號 符號`、`Foo符号 符號`、`符號`) and extraction FAILS ⇒
        // NEED_CLARIFICATION. `這個符號`、`这个符號`、`一下符號`、`符號` suffixes.
        // #19 B3 stage-8.3 — EN-delimited 殘餘（SYMBOL_OPENER_EN／SYMBOL_OPENER_CMD／
        // SYMBOL_LEADING_SYMBOLS 任一消費）不進本步驟與兩道歧義守衛（見 extractSymbolQuery
        // 的 enDelimited 旗標）；本 pattern 本身不動——僅調用時機改變。
        // `函式|函数` are deliberately NOT suffix cues here: they are identifier
        // content, never strip words —
        // `查 函式 符號` routes FAST with query `函式`, and
        // `查 Foo 函式 符號` clarifies (multi-token).
        // #19 B3 stage-8.2 — 三個平坦貪婪分隔符槽改為原子群組（同集合、僅原子性）：
        // 純分隔符 run 不可能屬於 `這個|这个|一下`／`符號|符号` 各交替字元，也不可能
        // 被 `$` 以外的必需字元消化——原子接管 run 後，失敗路徑不再枚舉分割；成功路徑
        // 匹配逐字相同。修前對長逗號串為超線性多項式（實測 ~O(n²)，n=400→~267 ms，
        // 4096 字元上限下可致數十秒至數分鐘）；修後為逐位置掃描（4096 字元 full-run
        // <100 ms；等價性以 41,370 個長度≤4 枚舉字串抽驗 containsMatchIn/replaceFirst
        // 全等）。方向恆為 fail-closed（DENIED/澄清），從不執行。
        private val SYMBOL_CJK_KEYWORD_SUFFIX = Regex(
            "(?>[\\s，,]*)(?:這個|这个|一下)?(?>[\\s，,]*)(?:符號|符号)(?>[\\s，,]*)$",
        )
        // #19 B3 stage-7.3 (A3/F1c)：全匹配守衛——殘餘恰好是被識別字標點
        // （SYMBOL_ALLOWED_PUNCT 子集，含成對包裹）包夾的單一 cue（可選 這個/一下
        // 黏在 cue 上）⇒ 拒絕（澄清）。`get(符號)`／`使用者(符號)` 不匹配（有識別字
        // 內容於包裹之外）⇒ 保留 verbatim FAST；`(符號)`／`-符號-`／`_符號_` 澄清。
        // #19 B3 stage-8.2 — 包夾兩個平坦貪婪類（SYMBOL_ALLOWED_PUNCT 子集）改為原子
        // 群組（同集合、僅原子性）。（防禦一致形態：與 SYMBOL_CJK_KEYWORD_SUFFIX 同類
        // 相鄰 greedy 收尾，縱然本 pattern 現為線性／~次毫秒可證，仍採原子群組統一防禦；
        // exhaustive differential 41,370 字串語義一致）；成功路徑匹配逐字相同（含雙端
        // `^…$` 全匹配守衛語意）。
        private val BARE_SYMBOL_CUE_WRAP = Regex(
            "^(?>[\\s，,._\$#@/():,;+=~-]*)(?:這個|这个|一下)?(?:符號|符号)(?>[\\s，,._\$#@/():,;+=~-]*)$",
        )
        private val SYMBOL_LEADING_CONNECTIVE = Regex(
            "^(?:for|the|a|an|named|called|of|to)\\s+(?=\\S)",
            RegexOption.IGNORE_CASE,
        )
        // #19 B3 stage-8.2 — 尾端分支改為 possessive（`++`，同集合、僅原子性）：
        // Step D 的 SYMBOL_EDGE.replace() 在長尾標點 run（如 4096 字元上限下整串逗號）
        // 對每個 start position 重新貪婪掃掠全尾 run 並逐格回溯重檢 `$`，呈超線性
        // （4096 字元輸入實測「修前」~246 ms/次，單次提取即含一輪）。possessive 消費不退讓：
        // 純尾標點 run 在 `$` 錨之外無消費者，故不能剪掉任何成功匹配（成功路徑匹配
        // 逐字相同）；失敗路徑一次掃掠定局。
        // #19 B3 stage-8.4 — 前導分支移除 `:`（U+003A）／`：`（U+FF1A）：C++
        // global-namespace 前綴 `::Foo` 為不同 scope，原樣保留 verbatim——前導 colon
        // 一律不剝（leading colons 一律不剝/不改寫；Re-review #8 A3）。全形 `：`
        // 亦不再被前導類剝除，但 Step E 是 ASCII-punct-only ⇒ `查 ：Foo 符號`
        // 拒絕 ⇒ NEED_CLARIFICATION（fail-closed，絕不 rewrite 成 `Foo`）。
        // possessive 變更實測無效能收益（no-op；start-position × trailing-run 掃描仍為超線性——僅消除單次 attempt 內的 give-back 回溯，已量測 ~246/275 ms 修前後不變）；保留為安全/正典形態。
        // #19 B3 stage-9.3（Re-review #9 A3, [P2]）—— 尾端分支移除 `:`（U+003A）／`：`（U+FF1A）：
        // C++ scoped qualified identifier 之尾端 `::` 原樣保留 verbatim（`Foo::` 為 C++
        // scoped 語意之合法 token，與 stage-8.3/8.4 verbatim-preservation 一致）：
        // `symbols Foo::` ⇒ FAST `Foo::`／`查 Foo:: 符號` ⇒ FAST `Foo::`／
        // `查 symbols Foo::` ⇒ FAST `Foo::`（EN-delimited verbatim）／
        // `查 Foo: 符號` ⇒ FAST `Foo:`（stage-8.2/8.4 時釘為 `Foo`，stage-9.3 翻轉）；
        // `查 Foo。 符號` ⇒ FAST `Foo`（`。` 尾仍剝，`。` 不變）；
        // `查 ::std::vector:: 符號` ⇒ FAST `::std::vector::`（COI token `::Foo` 兩分支對齊：
        // 前導 by stage-8.4、尾端 by stage-9.3）。尾端 `：`（U+FF1A）不再剝除 ⇒
        // `查 Foo： 符號`／`symbols Foo：` ⇒ Step E 拒絕 U+FF1A ⇒ NEED_CLARIFICATION
        // （fail-closed，與 FF1A leading reject 對稱；≥ stage-8.2 pin 改釘澄清）。
        // possessive `++$` 保留無誤（stage-8.2）；前導分支不動（stage-8.4 已移除 colons）。
        private val SYMBOL_EDGE = Regex(
            "^[\\s，。、？?「」『』“”\"'（）]+|[\\s，。、？?「」『』“”\"'（）]++$",
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

        // #19 B3 stage-13.1（Re-review #15 A1 [P1] + P2）：extraction 的型別化結果。
        // 【稽核可見契約：NoShape ⇒ EN fallback；Rejected ⇒ 終局否決】（兩相共用
        // [finishSymbolQueryExtraction]；EN 相永不產生 NoShape 語義）：
        //   Parsed(content) — Steps C–E 全部通過，verbatim 識別字（原樣綁定、READ-only）；
        //   Rejected        — 終局否決：terminal-position rule／BARE_SYMBOL_CUE_WRAP（包夾守衛）／
        //                     SYMBOL_CJK_KEYWORD_SUFFIX 後置歧義守衛／particles（嗎|吗|呢）／
        //                     stage-13.1 多 cue 計數（CJK 相入口，NFKC+Cf 剝離探針）／Step E 之
        //                     非-whitespace 檢核失敗（含 Cf/控制字元、允許集外碼點、無 L/N 錨點、
        //                     句首/句尾標點——如 `查 符號！`（Cf/allow-list 失敗，Step E）；
        //                     （`查 符號+`／`查 符號:` 則早在 BARE_SYMBOL_CUE_WRAP 包夾守衛拒絕）：
        //                     deny-by-fail-closed——CJK 相：整句 DENY
        //                     （hasSymbolQuery=false ⇒ missingQuestionFor ⇒ NEED_CLARIFICATION），
        //                     EN fallback 不入；EN 相：呼叫端映為 deny。絕不執行、絕不改寫；
        //   NoShape         — 僅代表「此殘餘非 CJK 語法形態」：僅 Step E whitespace／empty 閘
        //                     回傳（如 `查 Symbol Foo` 之 EN grammar 形態）；CJK 相 NoShape ⇒
        //                     EN fallback 照常（today's behavior）；EN 相→ deny。
        private sealed class SymbolExtract {
            data class Parsed(val content: String) : SymbolExtract()
            object NoShape : SymbolExtract()
            object Rejected : SymbolExtract()
        }

        /** 非重疊計數（`符號符號` ⇒ 2；`符號`/`符号` 無自重疊邊界，非重疊步進即精確）。 */
        private fun countOccurrences(text: String, needle: String): Int {
            var count = 0
            var index = text.indexOf(needle)
            while (index >= 0) {
                count++
                index = text.indexOf(needle, index + needle.length)
            }
            return count
        }

        /**
         * #19 B3 stage-9.2 (Re-review #9 A2, [P2]) — two-phase extraction,
         * CJK-preferred with EN fallback; #19 B3 stage-13.1（Re-review #15 A1 [P1] + P2）型別化。
         * A CJK opener
         * （SYMBOL_OPENER_CJK）leading the utterance means the utterance is CJK
         * grammar: the post-opener remainder is first parsed by the CJK branch
         * ONLY (suffix-cue peel + both ambiguity guards + Step-B′ EN connectives
         * + Step D edge trim — NO EN cue machine). If that yields a [SymbolExtract.Parsed]
         * result it is returned; a [SymbolExtract.NoShape] CJK pass (i.e. the
         * remainder is NOT CJK grammar at all — currently the Step-E whitespace/empty
         * gate) falls through to the EN pipeline below; a [SymbolExtract.Rejected]
         * CJK pass HARD-DENIES the whole utterance — the EN fallback is NOT run.
         *
         * stage-13.1 不變式（CJK-leading ambiguous parse ⇒ clarification；EN fallback 不再救援）：
         *   CJK 相 Parsed ⇒ 直接返回；CJK 相 Rejected ⇒ 整句 DENY／NEED_CLARIFICATION
         *   （hasSymbolQuery=false ⇒ missingQuestionFor）；絕不執行、絕不改寫、絕不落 EN；
         *   CJK-leading 且 `符號|符号` 計數 ≥ 2（CJK 相入口，EN-leading 不觸發）⇒ Rejected
         *   （歧義——兩側皆無法唯一綁定；P2 結構性封口）。計數施於 **NFKC+Cf 剝離探針**
         *   （[clauseProbeJoined]——與 admission clause gate 同一 Cf-strip 探針；關閉 `符\u200B號`
         *   拆分繞過；`符號符號` ⇒ 2、`符號`＋`符号` 合計 ⇒ 2；撤回類「識別字夾雙 cue」——
         *   `查 符號表 符號`／`查 符號Extra 符號`／`查 symbols 符號符號` 原 FAST 釘已全數撤回 ⇒ DENIED）；
         *   EN 命令形 `symbols …`（leading、delimiter-bounded）不變（EN-leading 話語不經 CJK 相）。
         *
         * CJK-pass examples: `查 Symbol 符號` → peel → FAST `Symbol`；
         * `查 symbols 符號` → peel → FAST `symbols`；`查 find 符號` → peel →
         * FAST `find`；`查 Foo符號` → FAST `Foo`；`查 Symbol Foo` → 無尾綴 cue
         * ⇒ 不剝（Step E 拒絕空白）⇒ CJK pass NoShape ⇒ EN pipeline：
         * SYMBOL_OPENER_CMD 消費 `Symbol `（cue 記帳）⇒ Step B 跳過 ⇒ FAST `Foo`。
         * `查 Foo符號 符號` — CJK ambiguity guard（peel 後殘餘仍匹配尾綴 cue）
         * FAILS ⇒ Rejected ⇒ 整句 DENY（NEED_CLARIFICATION；EN 相不入；絕不執行）。
         * `查 symbols Foo符號等法務簽核` — CJK terminal-position rule FAILS（L 尾綴）
         * ⇒ Rejected ⇒ 整句 DENY（stage-13.1：不再經 EN fallback 洗成 FAST
         * `Foo符號等法務簽核`；stage-12.1 之 named residual 已封口）。
         */
        private fun extractSymbolQuery(text: String): SymbolExtract {
            val trimmed = text.trim()
            // #19 B3 stage-9.2 — Phase 1: CJK-preferred pass (only when a CJK
            // opener actually leads). CJK-opener consumption does NOT set
            // enDelimited and does NOT consume an EN `symbols?` cue — the CJK
            // branch bypasses the whole Step A-EN/CMD/Step-B machine.
            val cjkMatch = SYMBOL_OPENER_CJK.find(trimmed)
            if (cjkMatch != null && cjkMatch.range.first == 0) {
                // #19 B3 stage-13.1（P2 結構性；Re-review #15 P2；R1/R4 修訂）：CJK-leading 且 `符號|符号` 計數 ≥ 2 ⇒
                // 歧義（兩側皆無法唯一綁定）⇒ Rejected。計數施於 **NFKC+Cf 剝離探針**
                // （[clauseProbeJoined]——與 admission clause gate 同一 Cf-strip 探針；關閉 `符\u200B號`
                // Cf 拆分繞過），非原始字串（原始字串計數會被 Cf 拆分規避：`符\u200B號符號`  raw 計 1）；
                // CJK cue 同家族、單一預算：`符號符號` ⇒ 2、`符號`＋`符号` 合計 ⇒ 2。
                // cjkMatch 非 null 且位於 0 ⇒
                // AFFIRMATIVE_OPENER_CJK 已匹配（與其同前綴形）；僅 CJK 相生效，EN-leading 話語不觸發。
                val probe = clauseProbeJoined(trimmed)
                if (countOccurrences(probe, "符號") + countOccurrences(probe, "符号") >= 2) {
                    return SymbolExtract.Rejected
                }
                val cjkRemainder = trimmed.substring(cjkMatch.range.last + 1).trim()
                when (val r = finishSymbolQueryExtraction(cjkRemainder, enDelimited = false)) {
                    is SymbolExtract.Parsed -> return r
                    // #19 B3 stage-13.1（Re-review #15 A1 [P1]）：CJK 相 Rejected ⇒ 整句 DENY；
                    // 一個否決的 CJK 判定不得被 EN fallback 洗成肯定通過（絕不落 EN pipeline）。
                    SymbolExtract.Rejected -> return SymbolExtract.Rejected
                    // NoShape（目前僅 Step-E whitespace/empty 閘）⇒ EN fallback 照常。
                    SymbolExtract.NoShape -> Unit
                }
            }
            var s = trimmed
            // #19 B3 stage-8.3 — EN-delimited 殘餘旗標：EN opener（SYMBOL_OPENER_EN）、
            // command-form EN opener（SYMBOL_OPENER_CMD）或前導 EN `symbol(s)` cue
            // （SYMBOL_LEADING_SYMBOLS）任一**實際改動字串** ⇒ enDelimited = true。
            // CJK opener（SYMBOL_CJK_COMMAND_TOKEN）消費本身不設旗標。旗標為 true 時，
            // Step C 尾綴剝離與 pre-Step-B′／post-Step-D 兩道歧義守衛一律不適用——殘餘
            // 為 EN grammar 劃定之 verbatim identifier（僅單一 token；空白 ⇒ Step E 澄清）。
            var enDelimited = false
            // Step A — anchored openers (each applied at most once a position;
            // sequential; with a CJK opener leading, the stage-9.2 CJK-preferred
            // pass above already had first claim on the remainder).
            s = SYMBOL_OPENER_CJK.replaceFirst(s, "").trim()
            var afterEn = SYMBOL_OPENER_EN.replaceFirst(s, "")
            if (afterEn != s) enDelimited = true
            s = afterEn.trim()
            var afterCmd = SYMBOL_OPENER_CMD.replaceFirst(s, "")
            // #19 B3 stage-9.2 (Re-review #9 A2) — cue budget: a `symbols?` cue
            // consumed by Step A CMD is counted here. Step B (the OTHER cue
            // consumer) RUNS AT MOST ONCE TOTAL per utterance — it is skipped
            // entirely when Step A CMD already consumed a cue. Step A CMD itself
            // still consumes at most once. This stops `extractSymbolQuery`
            // eating the cue twice (`find symbol symbol` → `symbol symbol` →
            // `symbol`（CMD）⇒ B 跳過 ⇒ `symbol`，而非空 ⇒ 澄清).
            var symbolCueConsumed = false
            if (afterCmd != s) {
                enDelimited = true
                symbolCueConsumed = true
            }
            s = afterCmd.trim()
            // Step B — ONE leading EN `symbol(s)` cue (word-anchored, at most
            // once). No other part of the remainder is touched here.
            // #19 B3 stage-9.2 — skipped when Step A CMD consumed the cue
            // (cue budget of 1 per utterance spent).
            val afterLead = if (symbolCueConsumed) s else {
                val lead = SYMBOL_LEADING_SYMBOLS.replaceFirst(s, "")
                if (lead != s) enDelimited = true
                lead
            }
            s = afterLead.trim()
            return when (val r = finishSymbolQueryExtraction(s, enDelimited)) {
                is SymbolExtract.Parsed -> r
                // EN 相：NoShape（Step-E whitespace/empty）映為 deny（無後續 fallback）；Rejected ⇒ deny（fail-closed）。
                else -> SymbolExtract.Rejected
            }
        }

        /**
         * Steps C–E shared verbatim by BOTH extraction phases (#19 B3 stage-9.2):
         * the CJK-preferred pass and the EN pipeline tail. Sharing this helper is
         * the INTENTIONAL alignment mechanism between the two branches — the two
         * paths cannot drift here without both reviewers' attention. Do not
         * loosen the guards per branch; the enDelimited flag is the ONLY
         * branch-switch, exactly as in the pre-9.2 single pipeline.
         *
         * stage-13.1（Re-review #15 A1+P2）：回傳型別改為 [SymbolExtract]——
         * Parsed 即 verbatim 內容；Rejected 即硬性 DENY（deny-by-fail-closed；
         * hasSymbolQuery=false ⇒ NEED_CLARIFICATION；絕不執行、絕不改寫；終局否決）；
         * NoShape 僅於 Step-E whitespace／empty 閘回傳（CJK 相：EN fallback 仍適用；
         * EN 相：呼叫端映為 deny（[SymbolExtract.Rejected]）；EN 相不產生 NoShape 語義）。
         * 對應表（NoShape vs Rejected 分流，與 [SymbolExtract] 宣告處註解一致）：
         * Step E whitespace/empty ⇒ [SymbolExtract.NoShape]；terminal-position rule／
         * BARE_SYMBOL_CUE_WRAP／SYMBOL_CJK_KEYWORD_SUFFIX 後置歧義守衛／particles（嗎|吗|呢）／
         * Step E 非-whitespace 檢核失敗 ⇒ [SymbolExtract.Rejected]。
         *
         * Step C — peel at most ONE trailing CJK keyword suffix (end-anchored).
         * EN-delimited 殘餘（enDelimited == true）一律跳過：EN grammar 劃定之
         * identifier 原樣保留——不剝離、不守衛（stage-8.3 carve-out）。
         */
        private fun finishSymbolQueryExtraction(s0: String, enDelimited: Boolean): SymbolExtract {
            var s = s0
            // #19 B3 stage-12.1（Re-review #12 A1，[P1/BLOCKER]）：terminal-position rule（非-enDelimited 抽取相適用；
            // 位於抽取層、非准入閘及任何 arm；deny-by-fail-closed，永不執行；EN-delimited 相不適用——見下）。
            // 取最後一個 symbol cue（符號|符号）之位置；其後（suffix）若含任一 \p{L}/\p{N} 碼點，整串即非單一
            // complete affirmative command＋唯一識別字（尾綴即附帶的非查詢子句／延後／批准／時間條件／
            // 後續動作——如 `等法務簽核`／`週五啟動`／`三個工作天後啟動`／`隨時可以`／`以後再做`），
            // extraction 硬失敗 ⇒ hasSymbolQuery=false ⇒ missingQuestionFor ⇒ NEED_CLARIFICATION；永不執行。
            // 識別字含 CJK 者必須止於 cue：`查 使用者(符號)`（suffix `)`）／`查 get(符號)`（suffix `)`）不被本規則否決；
            // （識別字含 CJK 必須止於 cue；僅 `[\s，,]*` 尾綴或被 Step D 剝離後不重露 cue 者仍 FAST；其餘非 L/N 尾綴皆 DENIED；fail-closed，記錄）。
            // 只否決、不改寫；admit 方向不變；negation/extraction 之既有 NEED_CLARIFICATION 語義保留不變；
            // EN-delimited 分支豁免（stage-8.3 verbatim 契約——`symbols Foo符號xyz` ⇒ `Foo符號xyz`
            // 原樣綁定；`查 symbols Foo符號` ⇒ `Foo符號` 原樣綁定；兩者 皆釘住（pinned）FAST）。
            // CJK-leading 純中文殘餘（`查Foo符號等法務簽核` 等；無 EN 消費）CJK 相即 Rejected ⇒ 整句 DENY（stage-13.1：EN fallback 不再救援；
            // 未消費 EN 之 EN 相若再入本 helper，enDelimited=false 亦重新觸發同一條規則——fail-closed 不變；admission gate 不動）。
            if (!enDelimited) {
                val lastCueIdx = maxOf(s.lastIndexOf("符號"), s.lastIndexOf("符号"))
                if (lastCueIdx >= 0 && LETTER_OR_NUMBER.containsMatchIn(s.substring(lastCueIdx + 2))) {
                    return SymbolExtract.Rejected
                }
            }
            if (!enDelimited) {
                s = SYMBOL_CJK_KEYWORD_SUFFIX.replaceFirst(s, "").trim()
                // Ambiguity guard (stage-7.3, A3): after the ONE peel, if the remainder
                // STILL matches the suffix cue at its end — or is exactly that cue — the
                // double-cue shape is structurally ambiguous (`Foo符號 符號`,
                // `Foo符号 符號`, `符號`) ⇒ extraction FAILS ⇒ NEED_CLARIFICATION.
                // Identifier content is never rewritten — a verbatim remainder may only be
                // admitted when it parses unambiguously. (EN-delimited 殘餘不適用——見上。)
                if (s.isNotEmpty() &&
                    (SYMBOL_CJK_KEYWORD_SUFFIX.containsMatchIn(s) || BARE_SYMBOL_CUE_WRAP.containsMatchIn(s))
                ) {
                    return SymbolExtract.Rejected
                }
            }
            // Step B′ — leading EN connectives, repeatedly, only when another
            // token remains after the connective. Applied AFTER the trailing
            // suffix cue peel so `查 a 符號` keeps its single-letter identifier:
            // the suffix strip leaves `a` with nothing after it, and a bare
            // connective at the end of the remainder is NOT stripped.
            while (true) {
                val t = s.trim()
                val m = SYMBOL_LEADING_CONNECTIVE.find(t)
                if (m == null) {
                    s = t
                    break
                }
                s = t.substring(m.range.last + 1)
            }
            // Step D — edge trim only, repeatedly.
            while (true) {
                val t = s.trim()
                val u = SYMBOL_EDGE.replace(t, "")
                if (u == t) {
                    s = u
                    break
                }
                s = u
            }
            // Ambiguity guard, re-applied AFTER the edge trim (stage-7.3, A3):
            // Step D can expose a hidden cue (`查 符號。` → step D strips `。`,
            // leaving exactly `符號`) ⇒ extraction FAILS ⇒ NEED_CLARIFICATION.
            // The full-match clause additionally rejects a cue wrapped ONLY in
            // identifier punctuation (`查 (符號)` / `查 -符號-` / `查 _符號_`)
            // — symmetric with the fullwidth `（符號）` case; verbatim Fast
            // admission only for識別字 with real content on both sides of the
            // cue (e.g. `get(符號)` / `使用者(符號)`).
            // EN-delimited 殘餘不適用（stage-8.3）——EN grammar 劃定之 verbatim
            // identifier 原樣保留（零 cue 處理）。
            if (!enDelimited && s.isNotEmpty() &&
                (SYMBOL_CJK_KEYWORD_SUFFIX.containsMatchIn(s) || BARE_SYMBOL_CUE_WRAP.containsMatchIn(s))
            ) {
                return SymbolExtract.Rejected
            }
            // Particles rule (stage-6.1, unchanged) — CJK interrogative
            // particles （嗎|吗|呢） anywhere in the remainder ⇒ extraction
            // FAILS (empty) ⇒ NEED_CLARIFICATION (fail-closed; never
            // stripped, never part of a plausible query).
            if (s.contains('嗎') || s.contains('吗') || s.contains('呢')) return SymbolExtract.Rejected
            // Step E — immutable remainder validation. After steps A–D the
            // remainder is NEVER rewritten; it is only admitted or rejected:
            // empty ⇒ fail; ANY remaining whitespace ⇒ fail; sentence-final
            // `. , ;` ⇒ fail (natural-punctuation hazard; a trailing `:` (stage-9.3)
            // is no longer stripped by step D — it is allow-list-legal here, and a
            // fullwidth `：` (U+FF1A) fails the allow-list below, so no
            // `:` disjunct is kept, defense-in-depth noted); any code point
            // outside {Unicode L*, N*} ∪ `. _ - $ # @ / ( ) : , ; + = ~` ⇒
            // fail (incl. Format/control); at least one L/N anchor required.
            // 空殘餘（如 `查 符號` peel 後為空、或 mid-pipeline 完全剝光）同屬「非 CJK 語法形態」——
            // stage-13.1 R3：Step E whitespace／empty 皆 NoShape（CJK 相：EN fallback 照常；EN 相：呼叫端映為 deny）。
            if (s.isEmpty()) return SymbolExtract.NoShape
            if (s.startsWith(',') || s.startsWith('；') || s.startsWith('，') || s.startsWith(';')) return SymbolExtract.Rejected
            if (s.endsWith('.') || s.endsWith(',') || s.endsWith(';')) return SymbolExtract.Rejected
            var hasLetterOrDigit = false
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val type = Character.getType(cp)
                // #19 B3 stage-13.1：whitespace／empty 閘僅代表「非 CJK 單 token 語法形態」（如 `查 Symbol Foo` 之 EN grammar）；
                // CJK 相 ⇒ NoShape（EN fallback 適用）；EN 相由呼叫端映為 deny（EN 相無後續 fallback）。
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return SymbolExtract.NoShape
                if (type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt()) return SymbolExtract.Rejected
                if (!isQueryCodePointAllowed(cp)) return SymbolExtract.Rejected
                if (isQueryLetterOrNumberType(type)) hasLetterOrDigit = true
                i += Character.charCount(cp)
            }
            // …and at least one letter/digit must anchor the query.
            return if (hasLetterOrDigit) SymbolExtract.Parsed(s) else SymbolExtract.Rejected
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
         * This predicate is intentionally stricter than Step E's code-point
         * letters/numbers test inside [extractSymbolQuery] (it rejects
         * No-category glyphs and astral letters/digits, e.g. ① U+2460, U+1D7D8);
         * the stricter of the two decides — fail-safe direction, pinned by
         * the `查 𠮷 符號` clarification pin.
         *
         * 僅 [SymbolExtract.Parsed] 視為有意義殘餘——NoShape／Rejected 皆不綁定
         * （hasSymbolQuery=false ⇒ [missingQuestionFor] ⇒ NEED_CLARIFICATION；
         * toolCall 永不綁定）。BMP-Char 錨定語意不變（strictly BMP `isLetterOrDigit()`）。
         */
        private fun hasSymbolQuery(text: String): Boolean =
            (extractSymbolQuery(text) as? SymbolExtract.Parsed)?.content?.any { it.isLetterOrDigit() } == true

        private fun symbolArgs(text: String): String {
            // 非 Parsed ⇒ query 綁為空字串；toolCall 永不會以此路徑綁定（hasSymbolQuery=false
            // ⇒ missingQuestionFor 先行 ⇒ NEED_CLARIFICATION；空 query 絕不執行）。
            val q = (extractSymbolQuery(text) as? SymbolExtract.Parsed)?.content.orEmpty()
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

        // #174 (b): confusable-script backstop. NFKC does NOT fold Cyrillic/Greek
        // lookalikes back to ASCII, and a homoglyph whitelist can never be complete.
        // Strip only IN-RUN ignorables (Cc/Cf, digits, punctuation, Lm, Hangul
        // fillers) while preserving \p{Zs} whitespace as real run boundaries, then
        // reject a run that MIXES Latin with Cyrillic/Greek — the shape a homoglyph
        // negation attempt takes (`n\u0435ver`, `\u0430void`, ZWJ-split `n\u200Dеver`).
        // UnicodeScript is the policy oracle; no per-code-point lookalike table.
        // Han+Latin runs (`查Foo符號`) stay unaffected (HAN is not confusable here);
        // separate Latin/Greek tokens (`find symbol π`) stay FAST; pure
        // Cyrillic/Greek-only runs are NOT flagged (known residual, see KDoc).
        private val NEGATION_CONFUSABLE_JOINED = Regex("[^\\p{L}\\p{Zs}]+|\\p{Lm}+|[\\u115F\\u1160]+")
        private val NEGATION_RUN_SPLIT = Regex("\\p{Zs}+")

        private fun hasConfusableNegationRun(nfkc: String): Boolean {
            val joined = nfkc.replace(NEGATION_CONFUSABLE_JOINED, "")
            return joined.split(NEGATION_RUN_SPLIT).any { run ->
                var latin = false
                var confusable = false
                var index = 0
                while (index < run.length) {
                    val cp = run.codePointAt(index)
                    when (Character.UnicodeScript.of(cp)) {
                        Character.UnicodeScript.LATIN -> latin = true
                        Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK -> confusable = true
                        else -> Unit
                    }
                    index += Character.charCount(cp)
                }
                latin && confusable
            }
        }

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
         *     belt-and-braces), plus (4) [hasConfusableNegationRun]: any Zs-delimited
         *     letter run (in-run ignorables removed) mixing Latin with Cyrillic/Greek
         *     (#174 b) clarifies; purely Cyrillic/Greek runs are left alone.
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
        // [isNegated] 同命三文本（raw / NFKCremoved / NFKCspaced）＋ mixed-script run
        // 探針（#174 b）；A1 保守否定閘。兩表互補（COMPLEMENTARY），不得刪除
        // 單顆粒集或四個通用禁止詞：[NEGATION_MARKERS] 收單一顆粒集
        //（不 沒 没 別 别 勿 莫 嫑 否 無 无 免 未 毋 休）＋[EN_NEGATION_BEST_EFFORT]
        //（含 #174 c 新增 cannot/unwilling/reject\w*/hate\w*/can'?t）；[NEGATION_HINTS]
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
                containsAny(spaced, NEGATION_MARKERS) ||
                hasConfusableNegationRun(nfkc)
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
