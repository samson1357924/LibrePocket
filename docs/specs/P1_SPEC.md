# LibrePocket P1 核心階段 — Historical Target Specification

> **狀態：Historical target specification, not a frozen current implementation contract.** Reviewed against `b3d8a818f37f8455a254a0670b512286e4deb750` on 2026-10-07. This file preserves design and acceptance intent; some APIs, wiring, test gates, persistence behavior, and security assumptions diverge from current code. Do not use its checklists as evidence of completion.
>
> - **範圍：** P1 design for provider BYOK, chat, session persistence, policy, and tests.
> - **Owner role：** app/runtime maintainers; no individual assigned.
> - **Source of truth for Current：** production composition and source/configuration at the pinned commit. See [Architecture](../ARCHITECTURE.md), [Testing](../TESTING.md), and [Threat Model](../THREAT_MODEL.md).
> - **Update trigger：** Accepted cross-layer design changes or implementation changes that invalidate the retained design/acceptance criteria.
>
> **Current facts that override stale examples below:** Room is the current transcript store; JSONL import/export exists as library code but is not a user-facing workflow; Android `allowBackup=true` with key-specific exclusions is configured; provider request text is not proven redacted before transmission; built-in tools are not execution-ready; CI runs the instrumented matrix only on schedule/dispatch and label-gated PRs (run-instrumented); it is not a merge gate — see docs/TESTING.md. The spec's JSONL-authority, UI, test matrix, and acceptance language is Target unless explicitly verified elsewhere.
>
> **Target scope:** provider BYOK, minimum chat loop, session persistence, policy model, and local tests. Agent tool execution, GUI, terminal, MCP, memory, and cloud sync were originally scoped as later work. “No app-managed cloud sync” does not mean Android Auto Backup is disabled.

### Current 行為補充（conversation-ledger；以程式為準，補充核對至 `0185935` S1–S7＋R27＋R28-1，另加 PR #94 P1/P2：`Failed.code` 傳遞、classifier `text_too_large`、sink distinct 256 順序、預算／用量邊界與 cancel+usage 測試硬化；以 `502f6bf` 程式為準，review-only 驗證，未跑 Gradle——ANDROID_HOME／local.properties 未配置）

- **8 個 `CHAT_*` notice 碼**（見 `ChatScreen.chatNoticeText`／`ChatViewModel`）：`CHAT_SEND_DENIED`、`CHAT_APPROVAL_REQUIRED`、`CHAT_POLICY_UNAVAILABLE`、`CHAT_INPUT_TOO_LONG`、`CHAT_STORE_UNAVAILABLE` 皆為 NOT accepted（本次未送出，呼叫者保留文字；`INPUT_TOO_LONG` 為 100k 邊界 fail-fast）；`CHAT_STORE_PENDING` 為 UNKNOWN／PENDING_COMMIT（見下）；`CHAT_CANCELLED_RECOVERY`、`CHAT_QUEUE_RECOVERY_REQUIRED` 為排隊保留 recovery（取消／待送訊息已保留，須手動確認重送）。
- **UNKNOWN／settle（勿重送）：** `TranscriptPendingException` 表示超時送後 fate 未定；ownership 保留，同 opId 重送被擋，直到 `TurnController.settlePendingAdmission` 以同一邏輯 id 認領（claim）或轉失敗可重試（convert）。呼叫者收到 PENDING 時必須 NOT 重送。
- **request-trim badge（與 load-time 正交、不相加）：** `TurnController.lastRequestTrimmedCount`／`requestTrimmedCount` 計算送出時為塞入 100k 單請求預算而自頭丟棄的整訊息數；與載入時 DB tail cap 的 `droppedHistoryCount` 正交，兩者永不相加，僅以前者作持久 badge。
- **snapshot 雙預算＋abandon：** `SnapshotBudget` 為 `min(1999 rows, 2M UTF-16 chars)`（`HISTORY_LOAD_CAP=2000` 留一格給 `model switched` marker；incomplete 時再留一格給 `snapshot incomplete` marker，R28-1 寫前 clamp 至 1998 carried＋2 markers，同一 batch auto-prune 不再刪 head switch marker）；超預算前綴整批放棄（zero rows＋incomplete marker，永不寫截半前綴），結果為 `SKIPPED／CLEAN／DANGLING_WARNED／FENCE_FAILED_WARNED／BYTE_WARNED／FAILED_WARNED` 六態。
- **fence／deferred 預算：** 每相 `SESSION_FLUSH_TIMEOUT_MS=5s`；flush-then-close 至多約 2x，endpoint-switch／同 id reopen fence 至多約 6x（約 30s）；同 id reopen 的 deferred-backfill 阻擋期 `PENDING_BACKFILL_BLOCK_MS` 為 6x（約 30s，promotion 證明 terminal 可提前解）。
- **`droppedHistoryCount` combined 語義：** `buildRequest` 合併 tail-loaded capped prefix＋已結算非 partial live 後，對 combined 套 `historyCap` 整訊息 oldest-first 截斷，`droppedHistoryCount = combined − base`（`Unbounded` 時為 0）。
- **row／byte 不對稱：** 快照 2M 以 `String.length`（UTF-16 code units）計，匯出上限 `EXPORT_MAX_BYTES=20MB` 以 UTF-8 bytes 計，兩者比例僅為約略（名目約 1/10）；單列上限 `MAX_TEXT_CHARS=100k chars`。
- **R19-1 cancel-receipt close barrier（Current，已實作）：** 每個 cancel 在 host 的 cancel catch 安裝 attempt-scoped PENDING receipt（in-memory `cancelReceipts`，一 cancel 一條、永不刪除、attempt 數量上限），背景 waiter 觀察 per-entry terminal outcome（null／true／false）後交接：REVOKED（optimistic 已成立，零新列）、COMPENSATED（deferred system／batch 已寫）、SEALED（seal／timeout，降級給 reopen INTERRUPTED backfill）。`close` 的 shutdown settle 依序 join doomed-host＋orphan＋waiter 快照（Phase 5 重 snapshot：與 close 併發安裝的新 waiter 也在同一餘額內 join），再過 flush barrier 再 seal；compensation admission 永遠在 `channel.close()` 之前。`flushDurable` 合取「無 PENDING／CLAIMED receipt」，immediate close 永不 true-and-dangling（`CancelReceiptCloseBarrierTest` 六形狀＋Phase-2 四形狀為證）。資源邊界（Stage-3 P2-6，Current）：`cancelReceipts`／`cancelReceiptWaiters` 皆 session 生命期永不刪除、以 attempt 數量為界（單條僅數個 AtomicReference，無 payload／transcript 列）；session 級 FIFO cap（如 128 條、優先驅逐 sealed-and-backfilled）為 Target，未實作——kill 恢復不依賴此表（重啟由 reopen INTERRUPTED backfill 擁有），見 `TurnController.cancelReceipts` KDoc。
- **Phase 1 SEALED truth（Current，已實作）：** SEALED 單獨不等於 settled；只有 `suppressedFinal && terminalOutcome==true`（final intent 終端已提交、無系統修補欠款）的 SEALED 視為 settled，其餘 SEALED（non-final notice-first 的晚 SUCCESS、終端失敗、system 失敗）維持 uncompensated，`flushDurable` 為 false 直到 reopen INTERRUPTED backfill。注意 `systemOutcome` 目前恆為 SKIPPED（無寫入點，PENDING／FAILED 分支為死碼，見 `TurnController` 欄位 KDoc；接上 optimistic 寫入為 Target，未宣稱完成；死碼錨：`TurnController.CancelReceipt.systemOutcome` KDoc（Phase 5 Current）＋ waiter PENDING／FAILED 分支，見 `TurnController.kt:923-936,990-1022` 附近）。
- **Phase 2 remaining-budget 線性化（Current，已實作）：** 整個 settle（doomed-host＋orphan＋waiters）共用一個 `drainTimeoutMs` fence；host／orphan 先花，waiter 只拿餘額（`deadline−now`，永不重開新預算）。終端在 waiter 存活時落定則 barrier 前交接（正向 control `equalBudget_earlyTerminal_handsOff`：500==500 照樣 REVOKED／COMPENSATED＋`flushDurable==true`）；seal 後才落定則 SEALED（反向 controls：T2 reverse、T3 500==500 FAILED／SUCCESS 兩測）。`OrderedTranscriptSink.shutdown` 背景工作上限約 3x（settle 1x＋flush barrier 全額 1x＋writer-join 1x）；`TurnController.flushTranscript` 呼叫者上限約 2x（shutdown-join 1x＋admitted-drain 1x）。
- **R21-1／R22 admission／promotion／host／sticky 證明（Current，已實作）：** `TurnController.flushDurable` 共八檢查：① `flushTranscript` 排空、② 無 PENDING／CLAIMED receipt（R19-1）、③ 無未補償 SEALED（SEALED truth）、④ `hasUnsettledAdmission` 為 false（direct pending、promotion reservation、UNKNOWN ghost、orphan drain 任一即 true；running host 本身不算，由 ⑥ 覆蓋）、⑤ `hasUnsettledPromotion` 為 false（④ 的具名子集，顯式合取）、⑥ `hasActiveHost` 為 false（live host 擁有無 proven terminal 的 user 列）、⑦ `hasClosedUnsettledAdmission` 為 false（sticky close 義務：UNKNOWN ghost／promotion 取永久 tier，direct pending／live host／orphan 取可解除 tier）、⑧ durable-failure epoch 在 admission 證明前後三讀且 fail-last（R22-3 TOCTOU 線性化＋Stage-2 對稱搬移：第一讀後測試門 `beforeFlushDurableReverifyForTest`，第二快照後測試門 `beforeFlushDurableReturnForTest`，終讀 failure 收尾；failure 為 receipt/admission 單調後果，覆蓋 snapshot-to-return 缺口；單鎖快照＋單調 epoch 仍為 Target）。任一失敗即 false（never true-and-dangling）。`ChatSession` 介面預設全 false（fake 的 `true` 不是耐久證據，見 `flushDurable` KDoc）。
- **R20-1：** 程式與測試中無此編號契約（`R20` 零命中）；不虛構，特此註記。
- **import 形狀（Current，已實作）：** `assistant` 列 `isFinal=false` 必須同時 `isPartial=true`（中間 partial 唯一生產形狀）；crafted 非 partial 非 final `assistant` 列 import 直接拒絕（行號錯誤，不貼內容）。`reasoning` flag-only marker 的 `isFinal=false`＋`isPartial=false` 合法，不受影響。
- **R23-1 sticky split（Current，已實作）：** sticky close debt（close 掉落的 UNKNOWN ghost／promotion 取永久 tier，`hasClosedUnsettledAdmission`；non-final SEALED-uncompensated，`hasSealedUncompensatedReceipts`）擋 `flushDurable`（合取保留，never true-and-dangling；`isLedgerStreamingOrPending` 同樣 trip）但不擋 writer-dead 證明（`ChatViewModel.isWriterSealedDead` 去 sticky 合取：sealed＋pending-empty＋`isTranscriptWriterCompleted`＋無非 sticky 活義務即讀 dead）。故同一 VM 生命期內過期的 deferred-backfill block 可 promote 單一 `turn <family> interrupted`（sticky 永不解除，再 reopen 零重複）；writer 仍卡 barrier 時過期仍 defer。證據：`ChatSameIdReopenStickyDebtTest`（真 controller＋真 sink＋真 VM 同 id reopen，ghost＋SEALED 兩組＋負控）＋`WriterStuckExpiryPhase4Test`（真 Room 卡 barrier 對照）＋Phase1 `ChatSameIdReopenBackfillExpiryTest.expiryWhileWriterAlive` 負控。
- **F1 marker-first（Current，已實作）：** `SessionTranscriptSink` 2-row batch 順序為 [`reasoning`?, `assistant`]；interface-default sequential `SessionStore.appendEvents` fallback 中段失敗至多留孤兒 `reasoning` marker（無 user family、不進 model context、`findDanglingRunIds` 忽略、有界 UI pending），family 保持 dangling（fail-closed），而非孤兒 final assistant（fail-open）。例外：`onTurnFailedAndCancelled` 3-row [partial, `reasoning`, cancel] batch 第 2-row fault 留孤兒非 final partial（`isPartial=1`／`isFinal=0`，仍 fail-closed：不進 model context、非 family terminal，reopen 照樣 backfill INTERRUPTED）。呼叫義務：throw 視為可能 partial（除非 store 保證 atomicity）；禁 assistant-first 重排。證據：`SessionSequentialFallbackTest`（真 `Factory.open` reopen-twice：首 open 恰一 INTERRUPTED，次 open 零新增）。
- **F2 無條件 fork 過濾（Current，已實作）：** `snapshotSwitchHistory` 以 `findDanglingRunIds` 無條件過濾 carried prefix（不再以 `excludeDanglingFamilies` 門控；旗標保留僅為 call-site 相容），dangling 家族永不拷貝（否則 reopen 誤標 INTERRUPTED），batch 帶 `dangling excluded` incomplete marker。clean-fence fork 前綴仍 dangling（settled-live race／真 stranded）同樣降級；withheld／byte-abandon 零列路徑不受影響（no-op）。
- **R23-2 cap-2000 精確邊界（Current，已實作）：** 恰 `HISTORY_LOAD_CAP (2000)` qualifying rows＋最新 pair 的 payload-free `reasoning` marker 時 `droppedCount==0`、`reasoningIncomplete==false`，最新 assistant 經 in-window marker 恢復 `hasReasoning==true`，舊 plain turns 維持 false；marker 本身不進 model context。證據：fake（`ChatSessionFactoryHistoryTailTest.open_exactCap2000WithNewestMarker_reportsZeroDroppedAndFlagsNewest`）＋Room（`RoomReasoningAtomicTest.exactCap2000WithNewestMarker_reportsZeroDroppedAndFlagsNewest`）雙對照；2002-over／3-under 由既有案覆蓋。
- **D2 canonical abstract（Current，已實作）：** `ChatSessionProvider` canonical 4-arg `create`／4-arg `open`（`backfillInterrupted` 閘）為 abstract（lossy canonical→compat delegates 已刪除；3-arg `create`／3-arg `open` compat shims 走 interface default 分別順接 canonical，`initialHistory = emptyList()`／`backfillInterrupted = true` 對稱）；fake bodies 移入 canonical forms 並 fail-fast（storeless provider-loop fake 遇非空 prefix 即錯，見 `ChatWiringTest` scope note）。真 factory gate pin（`ChatSameIdReopenFenceTest.sameIdReopenWhenFlushFails_defersBackfillAndWarns`）：`backfillInterrupted=false` 零 marker 但仍 hydrate（首 request 見完整前文），下次 gated open 重試標記。Shim pin（`ChatSessionProviderShimTest`）：僅 override canonical 的 provider 經 3-arg 照樣進 canonical（空 prefix／gate-open）。
- **S5 Sink fail-closed（Current，已實作）：** 外部 `TranscriptSink` 必須 override 全形契約（真 linkage `parentRunId != null`／`attemptIndex != 0`、`isFinal=false`、`hasReasoning=true` 丟失即拋 `UnsupportedOperationException`，不降級寫 final；首輪（`parentRunId == null`＋`attemptIndex == null`／`0`，即 production 首 attempt 形狀）仍轉發；見 `chat/TranscriptSink.kt`）。Legacy pin（`TranscriptSinkLegacyFailClosedTest`）：真 linkage 拋＋首輪（`null`／`0`）轉發＋non-final／reasoning 拋＋`onTurnFailedAndCancelled` legacy 零列拋。
- **D3 單一 choke 點（Current，已實作）：** `isSameOriginForResume` 為 factory `open` 與 VM `settleDetachedLedger` 共用唯一同源判定（同 stored header＋同 endpoint ⇒ 同 verdict；stored／live 雙側同 `normalizeOrigin` 空間比對，path／default port／case 拼寫變體一致，僅真 host/scheme/port 變更 fork）。cross-origin snapshot 零列斷言；同 ledger 雙路徑一致 pin（legacy／port／case／fork-new-id，`ChatSessionFactoryProvenanceTest.sameOriginDecision_*`＋`sameOriginVerdicts_factoryOpenAndVmSettle_agreeOnOneLedger`）。
- **D1 雙軌（Current）：** storeless scope notes（provider-loop only；fake `flushDurable==true` 非耐久證據）＋with-store e2e wiring track（真 factory＋FakeSessionStore，`SessionTranscriptSink` 騎真 `OrderedTranscriptSink`；`openSessionReplaysHistoryThenLiveAppends` 等四案）；耐久主張一律引 ledger／receipt／Room 測（見 `ChatWiringTest` scope note）。
- **F4 歷史缺口（歷史資料限定，不回填）：** pre-R18-1 premature-close ledger `[user + system cancelled]` 無 partial——family 已被 exact-text system mark 關閉，reopen 不補 INTERRUPTED 亦不回填 partial；UI replay 僅呈 user 列（該 cancelled 家族 partial 為空；system mark 隱藏）。證據：`RetryableCancelNoticeFirstTest.oldGapUserSystemWithoutPartialNotRetroactive`。此形狀僅存於 R18-1 前舊資料；現行 cancel 一律經 notice-first atomic batch 寫 partial＋終標。
- **F6 崩潰恢復矩陣（Current；kill 點 × 恢復動作 × 保證等級）：** ① kill 於 user durable 後／terminal 前 → 首 `open` 補單一 INTERRUPTED（best-effort：marker 寫失敗留 dangling 待下次 open；次 open 零新增）——`RoomKillPersistencePhase4Test` 真 Room＋真 controller 證明（含 JSONL round-trip＋reload 等價）；② kill 於 backoff sleeper 中／partial 寫入與 retry notice 之間 → 家族仍 dangling（`isFinal=0` 非 terminal），首 open 補 INTERRUPTED；③ transcript batch：Room 單 tx／fake snapshot＋rollback 保 all-or-zero，sequential fallback 至多孤兒 marker（F1）；④ deferred-block：同 id reopen 首見 defer（`HISTORY_SNAPSHOT_INCOMPLETE`），writer 存活時過期仍 defer，writer 完成後過期 promote 單一 INTERRUPTED（R23-1）；⑤ session 列：`create`／fork 內 caught-failure 才 rollback（`deleteUnusedTranscriptSession` 有界 NonCancellable 清理；`discardUnattached(deleteTranscriptRow=true)` 顯式丟棄同理）；⑥ audit markers：`snapshot incomplete` best-effort（寫入失敗僅丟 notice，不擋 live turn；head／tail 16 掃描 `hasIncompleteSnapshotMarker` 重警告，同因同 notice）。
- **F3 孤兒 session 列缺口承認（已知限制）：** kill 於 Room insert 與 provider／session 建構完成之間（kill-between-insert-and-attach）的孤兒 session 列無啟動期 GC；目前僅 caught-failure 路徑回滾（見 F6-⑤），kill 致孤兒待後續 Target（需啟動期掃描＋活躍 id 鑑別，另 PR）。
- **R24-1 receipt pre-dispatch fail-safe（Current，已實作）：** `launchCancelReceiptWaiter` 的 job 加 `invokeOnCompletion` completion fail-safe，對仍 PENDING／CLAIMED 的 receipt 單調 →SEALED（callback 零 transcript write；REVOKED／COMPENSATED 永不覆寫）。cancelled-before-start 的 waiter（parent teardown 時 body 尚未執行）亦收斂，不再把已死 writer 當活 obligation 卡住同 ID deferred INTERRUPTED backfill。dead proof 的 PENDING／CLAIMED guard 保留（R14-1 fence：在途 NonCancellable compensation 的 CLAIMED 仍擋 dead）。證據：`CancelReceiptPreDispatchTest`（可控 dispatcher＋body-entry seam，cancel-then-resume 證 body cold 仍 SEALED；真 VM same-ID reopen 首輪 defer、writer 仍 block 時續期、release 後恰一 INTERRUPTED、再 reopen 零重複）。
- **R24-2 oldest-marker boundary scan（Current，已實作）：** `loadModelHistoryScan` 在 kept 滿 CAP 後仍收完同頁餘下 marker，並在飽和後多做一次 marker-only backward page（只收 `reasoning` runId，不加 kept、不動 dropped 語義）。恰 2000 qualifying 且 oldest retained assistant 的相鄰 marker 在窗外一行時仍可找回（`dropped==0` 不 over-flag）；超出一頁的遠 marker 由 R25-1 有界探測覆蓋（見下）。證據：fake＋Room/JSONL round-trip 雙回歸（seq=1 reasoning＋seq=2 assistant＋1999 filler；修正前皆紅、修正後皆綠）；newest-marker exact-2000 雙對照保留。
- **R25-1 far-marker bounded probe（Current，已實作）：** 滿窗（`kept==CAP`）且 `dropped==0` 時，以單一有界 `hasReasoningBefore(extraFloor)` COUNT（嚴格低於 R24-2 extra 頁底，不無界掃描；失敗即 suspect）檢查未掃描區是否存在任一 `reasoning` 列；存在即 `reasoningIncomplete=true`（全員保守，如同 `dropped>0`）。覆蓋 seq=1 marker＋200 notices＋2000 qualifying 的遠缺口（主 scan 保留 2201..202，extra 200 頁僅 201..2，floor=2，探測 seq<2 命中 seq=1）；相鄰 marker 已被 extra 頁收集（floor=1，探測 seq<1 為空，R24-2 精確旗 preserved）。已知 over-flag：無關遠 marker 亦致全旗，後續收窄為 Target（R26-1 家族邊界僅處理 user-less 孤兒，不收窄遠 marker over-flag）。證據：fake（`ChatSessionFactoryHistoryTailTest.open_exactCap2000WithFarMarkerBeyondOnePage_flagsAllConservative`）＋Room JSONL round-trip（`RoomReasoningAtomicTest.exactCap2000WithFarMarkerBeyondOnePageViaJsonlImport_flagsAllConservative`）雙回歸；LegacySink（`TranscriptSinkLegacyFailClosedTest`，僅 override 2-arg 的 retryable failure 不 premature close）與 D2 shim（`ChatSessionProviderShimTest`）同批綠。
- **R26-2 UI far-marker display-only（Current，已揭露＋pin，未對稱探測）：** `ChatViewModel.loadHistory` 的 UI replay 在 exact-cap 遠 marker（R25-1 布局）下保持 display-only——尾迴圈在 2000 列滿窗即退出、到不了 seq=1，不做與 Factory 對稱的有界 `hasReasoningBefore(extraFloor)` 探測（風險決策：UI 執行期多一次 COUNT 的價值不及路徑分歧風險；enforcement 一律用 model-context flag）。`dropped` COUNT 基準亦不同（model `includePartial=false` vs UI `includePartial=true`，見 F4）。證據：`Stage3ResidualPinTest.farMarker_uiStaysDisplayOnlyWhileModelFlagsAll`（真 VM replay：同 ledger 上 model 全旗、UI 首 assistant `hasReasoning=false`，誠實分歧）；對稱探測保持 Target。
- **R26-1 family-aware prune＋讀側去孤兒（Current，已實作；R27-1 單一 tx＋R27-2 無界收斂＋shape-aware）：** 新 prune 的 keep-newest 切割不得拆散 user/assistant 家族與 marker-first pair（`reasoning`＋`assistant` 同 `runId`；marker 恆新於其 user，故 marker 孤兒恆伴 user-less）。Room prune 的 raw `deleteEventsBeyondLimit` 與 family fixup（`oldestHistory`＋`deleteFamily` 迴圈）跑在同一 Room transaction（每 session 一 `db.runInTransaction`，R27-1；kill 落在之間整筆 rollback→重啟見 pre-prune 形狀，memory suspect 丟失亦正確，因無 durable 切口）。fixup 在同 tx 內無條件執行（無 `deletes>0` 門，R27-2）：首列為 assistant 即刪整家族（`runId`／`parentRunId` 同刪，`oldestHistory` 以 `seq ASC, rowId ASC` 與 raw 切口對齊），無界 `while(true)`＋break-on-no-progress 收斂（每輪嚴格遞減故必終止；舊 10-cap 已刪，crafted 15 頭孤兒單 sweep 收斂）。同 tx 內 shape-aware 判定 clean：post-fixup 首列為 user／空即 clean，仍為 assistant（blank 家族／`famDeleted<=0` 無進展 residue）即非 clean；讀側條件去頭仍為第二道門。`isPruneSuspect` 為逐 sweep（非 sticky）：刪除前 pre-publish（關閉 DB-delete→publish 競態，單一 latch-pinned 交錯測試（after-delete hook 窗）證 DELETE-commit 後、flag reconcile 前窗內 load 仍見 suspect；舊 post-publish 碼該測試為紅）、clean sweep（`deleted==0` 且 post-fixup 首列 user／空）清 flag（含既有）、residue sweep（`deleted==0` 但首列仍 assistant）保留 suspect fail-closed、throw 時無既有則 remove 後重拋、有既有則保留重拋（fail-closed 僅對已證據），`deleteSession` 清 suspect，append 永不清；併發 sweep 以獨立 event-cap mutex 序列化（last-finisher-wins，同 outcome 交錯 deterministic 已 pin）。`loadModelHistory` 取 COUNT 前後 max（併發 prune/append 不 under-flag）並在 `dropped>0` 或 suspect 時去頭孤兒（far-only 不去，保留 R25 精確），`loadSwitchSnapshotEvents` 無條件去頭孤兒（列-cap 靜默，家族去頭同靜默；helper 與 Room fixup 同為無界收斂，舊 10-cap／第 11 個 crafted 頭孤兒進 fork 窄窗已隨 R27-2 消除；生產切口至多拆散一個家族）。新 prune 不再留孤兒，真重啟（關檔→重開檔→新 store→真 `Factory.open`，backfill＋hydrate＋零新增）無 fail-open（memory suspect 重啟丟失不再 load-bearing，因孤兒形狀已 durably 不存在）；crash-mid-fixup 窗已由 R27-1 單一 tx 關閉（kill→rollback→pre-prune，證據 `fileBacked_fixupFault_rollback_reopenClean`＋`crashAtomicity_open_convergedHeadHonestDroppedAndFlags`，不再是已知限制）；pre-R26／interior 既有孤兒 fixup 不刪（僅頭）：suspect 持有期間被 all-flag 遮住，clean sweep 清 flag 或重啟丟失後、無證據時相容重播（已知限制，已 pin；R27 不擴大 fixup 至 interior）。已知 over-drop（保守方向，均已揭露；僅 bare tool／usage 殘留另有 pin，`runId` 重用／head-partial 牽引專測為 Target，未稱 pinned）：crafted `runId` 重用／亂序（假設生產寫序＋`runId` 唯一，整家族同刪含中段同 id 列）；`oldestHistory` 無 `isPartial` 過濾（crafted 頭 partial 牽引 fixup）；bare attempt-keyed tool／usage 列（`parentRunId==null`）為 P2-3 DB 殘留（不進 model context、非 terminal、不誤 INTERRUPTED）。Fake parity（測試 fake，非生產證據；形狀仍為 R26，未跟進 R27）：`SuspectFakeStore.simulatePruneCut` 仍僅 `drop>0` 跑 fixup、10-cap 有界迴圈、`drop==0` 無條件清 suspect，與 R27 Room（無條件 fixup、無界收斂、shape-aware 清除）門控不同；Room/Fake 同數收斂（deleted==2／count==1999／loaded dropped==0＋suspect，DB-delete vs read-filter 差異已消除）僅覆蓋兩者交集形狀，R27 新語義以 Room 真測為證（見下）。證據：`RoomR26FamilyAwarePruneTest`（user/marker 邊界＋throw/clean-sweep＋併發＋latch 交錯＋真重啟＋pre-R26 相容-then-sweep＋三負向控制＋attempt 大家族）＋R27：`fifteenHeadOrphans_convergentSingleSweep`（15 頭孤兒單 sweep deleted==15，含 pure helper 同形收斂）＋`secondSweep_cleanClearsSuspect_fileBackedRestartClean`（次 sweep clean 清 flag＋file 重啟乾淨＋真 `Factory.open` 零新增）＋`fileBacked_fixupFault_rollback_reopenClean`（tx 內 fixup fault→rollback→重開見 pre-prune）＋`residueHeadOrphan_deletedZeroKeepsSuspect`（residue `deleted==0` 保留 suspect fail-closed）＋`RoomR27FactoryOpenSnapshotTest.crashAtomicity_open_convergedHeadHonestDroppedAndFlags`（重啟後 honest dropped＋精確旗標＋真 `Factory.open` 零新增）＋更新後 `RoomS4PruneBulkTest`（a-old 整組消失＋Fake 同數收斂）。

---

## 1. 模組劃分與 Package / 類 / 介面簽名

### 1.1 模組總覽

| # | 模組 | Package | 職責 | 對應測試包 |
|---|------|---------|------|-----------|
| M1 | Provider BYOK 層 | `dev.librepocket.provider` | 三協議請求建構、SSE 解析、統一事件流、重試/超時、圖片降級、models.dev 快照 | `dev.librepocket.provider` |
| M2 | Key 金庫 | `dev.librepocket.keystore` | API Key 加密存取（security-crypto 過渡 → DataStore+Tink 長期） | `dev.librepocket.keystore` |
| M3 | 最小聊天閉環 | `dev.librepocket.chat` | 發送 / 流式渲染 / 取消 / steering 下一輪 | `dev.librepocket.chat` |
| M4 | 會話存儲 | `dev.librepocket.session` | Room `transcript_events`、JSONL 匯出/匯入、脫敏寫入 | `dev.librepocket.session` |
| M5 | 脫敏 | `dev.librepocket.redact` | 寫入前脫敏（正則表 §6），純函數、零 Android 依賴 | `dev.librepocket.redact` |
| M6 | 權限模型 | `dev.librepocket.policy` | ask/allow/deny × wildcard 三態、ruleset DSL、執行前重檢 | `dev.librepocket.policy` |
| M7 | 測試基建 | `app/src/test` + `app/src/androidTest` | JUnit / Robolectric / instrumented 劃分（§10） | — |

依賴方向（禁止反向依賴）：

```text
chat → provider, session, policy, keystore
session → redact
provider → keystore (僅取 Key，不感知加密細節)
policy → (無依賴，純 Kotlin)
redact → (無依賴，純 Kotlin + java.util.regex)
keystore → (僅依賴 AndroidX Security / DataStore / Tink)
```

### 1.2 M1 — Provider BYOK 層（`dev.librepocket.provider`）

> 基線協議 = OpenAI Chat Completions。Responses 與 Anthropic Messages 為適配器，
> 皆投影為同一 `StreamEvent` 流（§2）與同一 `ChatRequest` / `ChatResult`。

```kotlin
package dev.librepocket.provider

// --- 協議選擇 ---
enum class ProviderProtocol { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC }

// --- 統一請求模型（協議無關） ---
data class ChatMessage(
  val role: String,            // "system" | "user" | "assistant" | "tool"
  val text: String,
  val images: List<ChatImage> = emptyList(),   // §4 圖片輸入
  val toolCallId: String? = null,             // role=="tool" 時必填
  val toolCalls: List<ToolCall> = emptyList(), // assistant 攜帶的工具調用
)

data class ChatImage(
  val bytes: ByteArray,        // 原始位元組（不預縮放，由降級策略決定）
  val mimeType: String,        // "image/png" | "image/jpeg" | "image/webp"
  val preserveOriginal: Boolean = true,       // true=禁止轉碼縮放；超限則報錯而非降質
)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ChatRequest(
  val model: String,
  val messages: List<ChatMessage>,
  val tools: List<ToolSchema> = emptyList(),  // P1 允許空；閉環先跑純聊天
  val maxTokens: Int? = null,
  val temperature: Float? = null,
  val systemPromptOverride: String? = null,
)

data class ToolSchema(val name: String, val description: String, val jsonSchema: String)

// --- 傳輸配置（沿用調查結論，見 §5） ---
data class ProviderHttpConfig(
  val connectTimeoutMs: Long = 15_000,
  val writeTimeoutMs: Long = 30_000,
  val readTimeoutMs: Long = 300_000,  // 讀 idle 超時，非總時限
  val maxRetries: Int = 3,
  val retryDelaysMs: List<Long> = listOf(2_000, 4_000, 8_000),
)

// --- Provider 客戶端門面 ---
interface LlmProvider {
  val protocol: ProviderProtocol
  /** 串流；取消靠 scope/job（見 M3）。失敗按 §5 分類可重試/不可重試。 */
  fun stream(request: ChatRequest): Flow<StreamEvent>
  /** 一次性列模（設定頁「測試連線」用，不走 SSE）。 */
  suspend fun listModels(): List<String>
}

interface ProviderFactory {
  fun create(config: ProviderConfig): LlmProvider
}

data class ProviderConfig(
  val id: String,              // 穩定 UUID，Keystore 以此為 key（§7.3）
  val label: String,           // 顯示名
  val baseUrl: String,         // 必須 https（§7.2 校验）+ 完整 path 由適配器補
  val protocol: ProviderProtocol,
  val apiKeyRef: String,       // Keystore 引用，不存明文（格式見 §7.3）
  val http: ProviderHttpConfig = ProviderHttpConfig(),
)
```

具象類（每個協議一個檔案，一一對應測試）：

| 類 | 檔案 | 協議 | 關鍵點 |
|----|------|------|--------|
| `ChatCompletionsProvider : LlmProvider` | `provider/ChatCompletionsProvider.kt` | Chat Completions | 基線；`index` 聚合 tool_call delta；`finish_reason` 結束可見塊；`[DONE]` 收尾 |
| `ResponsesProvider : LlmProvider` | `provider/ResponsesProvider.kt` | Responses | `stream:true, store:false`；不送 `previous_response_id`；`response.completed` 為權威終態 |
| `AnthropicProvider : LlmProvider` | `provider/AnthropicProvider.kt` | Anthropic Messages | `content_block_start/delta/stop` + `message_stop`；thinking 簽名回傳（P1 只透傳、不執行工具） |
| `SseFrameParser` | `provider/SseFrameParser.kt` | 共用 | 多行 `data:` 合併、註釋心跳、UTF-8 分幀（§3.4）；純 Kotlin，零 OkHttp 依賴 |
| `ProviderErrorClassifier` | `provider/ProviderErrorClassifier.kt` | 共用 | 暫時性 vs 非暫時性（§5.2）；純函數 |
| `ImageFallbackPolicy` | `provider/ImageFallbackPolicy.kt` | 共用 | §4 降級決策表；純函數 |
| `ModelsDevSnapshot` | `models/ModelsDevSnapshot.kt` (`dev.librepocket.models`) | 快照 | §7 models.dev 讀取/快取/降級 |

### 1.3 M2 — Key 金庫（`dev.librepocket.keystore`）

```kotlin
package dev.librepocket.keystore

interface KeyVault {
  /** 寫入/覆寫某 provider 的 Key（記憶體不留明文，用後即清）。 */
  suspend fun putKey(providerId: String, apiKey: CharArray)
  /** 取 Key；呼叫方須在 finally 中 wipe 回傳陣列。 */
  suspend fun getKey(providerId: String): CharArray?
  /** 刪除（含登出/更換 Provider 時）。 */
  suspend fun deleteKey(providerId: String)
  /** 是否存在（設定頁顯示 ●●●● 狀態用，不洩露長度）。 */
  suspend fun hasKey(providerId: String): Boolean
}

// 過渡實現（P1 首選出貨）：EncryptedSharedPreferences 包裝
class EncryptedPrefsVault(context: Context) : KeyVault

// 長期實現（P1 規格凍結、P2 實作）：DataStore + Tink
class DataStoreTinkVault(context: Context, tinkAead: Aead) : KeyVault
```

詳細方案見 §7.3。**P1 只實作 `EncryptedPrefsVault`；`DataStoreTinkVault` 在 P1 僅保留介面相容的 stub + 遷移測試**，避免與基建組依賴升級衝突。

### 1.4 M3 — 最小聊天閉環（`dev.librepocket.chat`）

```kotlin
package dev.librepocket.chat

import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.StreamEvent

/** 單輪對話控制器：發送 / 流式 / 取消 / steering。 */
interface ChatSession {
  /** 當前展示用訊息流（UI collect；含 streaming 佔位）。 */
  val uiState: StateFlow<ChatUiState>

  /** 發送一則使用者訊息並開始串流。同一時間只允許一個 in-flight turn。 */
  suspend fun send(text: String, images: List<ChatImageRef> = emptyList())

  /** 取消 in-flight turn；必須 <200ms 內停止 UI 更新（手動驗證，§11.4）。 */
  fun cancel()

  /**
   * Steering：在 turn 執行中追加下一輪指令。
   * 語義：不取消當前 HTTP 請求、不關閉當前 turn；指令排隊，
   * 當前 turn 完整結束（含工具批次，P1 無工具即 assistant 回覆結束）後自動作為下一輪 user 訊息發送。
   */
  fun steer(text: String)

  /** 釋放（關閉 HTTP call、清空 queue）。 */
  fun close()
}

data class ChatImageRef(
  val filePath: String,        // App 私有區路徑；不接受 content:// 直寫（先複製進工作區）
  val mimeType: String,
  val preserveOriginal: Boolean = true,
)

data class ChatUiState(
  val messages: List<UiMessage>,
  val status: ChatStatus,      // IDLE | STREAMING | CANCELLED | ERROR | WAITING_STEERED
  val pendingSteerCount: Int,  // 排隊中的 steering 條數
  val error: String? = null,   // 脫敏後錯誤（不得含 Key/正文，見 §6.5）
)

enum class ChatStatus { IDLE, STREAMING, CANCELLED, ERROR, WAITING_STEERED }
data class UiMessage(val id: String, val role: String, val text: String, val isPartial: Boolean)
```

具象類：

| 類 | 檔案 | 說明 |
|----|------|------|
| `ChatSessionImpl : ChatSession` | `chat/ChatSessionImpl.kt` | 持有 `Job`（in-flight）、`ArrayDeque<String>`（steer queue）、單一 `OkHttp Call` 引用；狀態機 `IDLE → STREAMING → (IDLE \| CANCELLED \| ERROR)`；`steer()` 僅在 `STREAMING` 收斂，不改變狀態 |
| `TurnController` | `chat/TurnController.kt` | 單 turn 的 Flow 收集 + 重試編排（調用 M1 重試參數）；失敗半截輸出標記 `isPartial=true` 保留，不拼接到下一輪 |

不變量（P1 驗收必查）：

1. `send()` 在有 in-flight turn 時直接返回 `IllegalStateException`（經 `ChatUiState.error` 投影，不崩潰）。
2. `cancel()` 只做原子狀態翻轉 + 關閉 HTTP call + 取消 Job；不做 IO、不寫 DB（DB 寫入走 `session` 模組異步路徑，見 §8.4）。
3. `steer()` 永不取消當前 turn；turn 結束後若 queue 非空，自動取出一條 `send()`（FIFO，一次一條）。
4. 重試使用新輪次 ID；失敗半截塊保留並標 `isPartial`，後續輸出不回填舊塊。

### 1.5 M4 — 會話存儲（`dev.librepocket.session`）

```kotlin
package dev.librepocket.session

data class SessionMeta(
  val sessionId: String,       // UUID v4
  val title: String,            // 首則 user 訊息前 30 字（脫敏後）
  val createdAt: Long,          // epoch millis
  val updatedAt: Long,
  val model: String,            // "providerId/modelId"
)

interface SessionStore {
  suspend fun createSession(title: String, model: String): String  // 回 sessionId
  suspend fun appendEvent(event: TranscriptEvent): Long            // 回 rowId
  suspend fun loadEvents(sessionId: String, afterSeq: Long = 0, limit: Int = 200): List<TranscriptEvent>
  suspend fun exportJsonl(sessionId: String, destFile: File)       // §8.5
  suspend fun importJsonl(srcFile: File): String                   // 回 sessionId；逐條校验
  suspend fun prune(policy: PrunePolicy): PruneResult              // §8.3
  suspend fun deleteSession(sessionId: String)
}

data class TranscriptEvent(
  val seq: Long = 0,             // DB 自增；寫入時忽略
  val sessionId: String,
  val runId: String,            // user/retry 列 = logical turn id；assistant/tool/usage 列 = attempt id（首嘗試重用 logical id）
  val kind: String,             // "user" | "assistant" | "tool" | "steer" | "retry" | "system"
  val text: String,             // 已脫敏（寫入前強制過 Redactor，§6）
  val imagesOmitted: Int = 0,   // 圖片正文剝離計數（只存省略說明，不存位元組）
  val createdAt: Long,
  val isPartial: Boolean = false, // Phase 3：取消/失敗 assistant 半截標記
  val failureReason: String? = null, // Phase 3：失敗行的脫敏原因（其餘行為 null）
  val parentRunId: String? = null, // Stage C：attempt assistant 終端回綁的 logical turn id（首嘗試為 null；舊列為 null）
  val attemptIndex: Int? = null, // Stage C：0-based 嘗試序號（user/舊列為 null）
)

data class PrunePolicy(
  val maxEventsPerSession: Int = 2000,
  val maxAgeDays: Int = 90,
  val keepPinnedSessions: Boolean = true,
)
data class PruneResult(val deletedEvents: Int, val deletedSessions: Int)
```

具象類：`RoomSessionStore : SessionStore`（`session/RoomSessionStore.kt`）+ DAO/Entity（§8）。

### 1.6 M5 — 脫敏（`dev.librepocket.redact`）

```kotlin
package dev.librepocket.redact

/** 純函數；零 Android 依賴；JVM 單測可跑。 */
object Redactor {
  /** 全量脫敏：順序套用 §6 正則表，返回脫敏後文本 + 命中計數。 */
  fun redact(input: String): RedactResult
  /** 錯誤訊息脫敏（更嚴格：順帶剝離 URL query/token 片段，見 §6.5）。 */
  fun redactError(input: String): String
}

data class RedactResult(val text: String, val hits: Map<String, Int>)
```

### 1.7 M6 — 權限模型（`dev.librepocket.policy`）

```kotlin
package dev.librepocket.policy

enum class Verdict { ALLOW, ASK, DENY }   // 三態

data class PolicyRule(
  val pattern: String,         // wildcard DSL（§9.1），如 "file.read:/workspace/**"
  val verdict: Verdict,
  val priority: Int = 0,       // 大者勝；同 priority 見 §9.2 衝突規則
)

data class PolicyDecision(
  val verdict: Verdict,
  val matchedRule: PolicyRule?,
  val recheckedAt: Long,       // 執行前重檢時間戳（§9.3 證明有重檢）
)

interface PolicyStore {
  /** 評估：純函數式，不做 IO；呼叫方在執行前再次呼叫以重檢。 */
  fun evaluate(action: String, resource: String): PolicyDecision
  suspend fun setRule(rule: PolicyRule)
  suspend fun removeRule(pattern: String)
  suspend fun listRules(): List<PolicyRule>
  /** 執行前重檢：重新從持久層載入 ruleset 再 evaluate（防 TOCTOU）。 */
  suspend fun evaluateFresh(action: String, resource: String): PolicyDecision
}
```

具象類：`InMemoryPolicyStore`（單測）+ `DataStorePolicyStore : PolicyStore`（產品；DataStore backing，讀寫在 IO dispatcher）。

---

## 2. StreamEvent 統一模型

三協議全部投影為同一密封類。UI / 存儲 / 重試只消費 `StreamEvent`，不得觸碰協議原生 JSON。

```kotlin
package dev.librepocket.provider

sealed interface StreamEvent {
  /** 可見正文增量（已按 block 身份切分，見 §3.5）。 */
  data class TextDelta(val round: Int, val blockIndex: Int, val delta: String) : StreamEvent
  /** 可見思考增量（reasoning_content / reasoning summary / thinking_text）。 */
  data class ReasoningDelta(val round: Int, val blockIndex: Int, val delta: String) : StreamEvent
  /** 工具調用參數增量（按 index 聚合後輸出；終態見 ToolDone）。 */
  data class ToolDelta(val toolIndex: Int, val idChunk: String?, val nameChunk: String?, val argsChunk: String) : StreamEvent
  /** 單個工具調用完成（攜帶最終聚合 ID + 參數；ID 修復規則見 §3.5）。 */
  data class ToolDone(val toolIndex: Int, val id: String, val name: String, val argumentsJson: String) : StreamEvent
  /** 本輪用量（token 計費 / 上下文壓縮觸發用；P1 只記錄不壓縮）。 */
  data class Usage(val inputTokens: Int?, val outputTokens: Int?) : StreamEvent
  /** 終態：成功（攜帶 finish 原因；P1 不執行工具，tool_calls 僅記錄）。 */
  data class Done(val finishReason: String) : StreamEvent
  /** 終態：失敗（已分類；retryable 指導 TurnController 是否重試；code 攜帶型別化失敗碼）(Current)。 */
  data class Failed(val message: String, val retryable: Boolean, val code: String? = null) : StreamEvent
  /** 重試通知（第幾次、等待多久；UI 顯示「重試中 1/3…」）。 */
  data class Retrying(val attempt: Int, val maxAttempts: Int, val delayMs: Long) : StreamEvent
}
```

**投影規則（跨協議一致）：**

- 正文/思考/工具類型切換時，上一可見塊立即定稿；後續同類型內容不回填舊塊。
- 終態 `Done` 只在權威終態與已流式內容不一致時攜帶一次替換（P1 簡化：不一致則以終態為準並記 `blockReplaced=true` 到 transcript `retry` 事件）。
- `Failed.message` 必須先過 `Redactor.redactError()`（不得洩露 Key、URL token、正文）。

---

## 3. 三協議差異表

### 3.1 請求形態

| 項目 | Chat Completions（基線） | Responses | Anthropic Messages |
|------|--------------------------|-----------|--------------------|
| 方法/路徑 | `POST {base}/chat/completions` | `POST {base}/responses` | `POST {base}/v1/messages` |
| 流式開關 | `"stream": true` | `"stream": true`（固定） | `"stream": true` + `Accept: text/event-stream` |
| 系統提示 | 首條唯一 `system` 消息（合併全部 system 內容保序） | `instructions` 欄位（完整 system/developer 投影） | 頂層 `system`（string 或 block 陣列） |
| 歷史 | `messages[]`（role/content） | `input[]`（`type:"message"` items 重建） | `messages[]`（不含 system；tool_result 用 `tool_result` block） |
| 工具聲明 | `tools[]` + `tool_choice` | `tools[]`（function 類型） | `tools[]`（`input_schema`） |
| 圖片 | `content[]` multimodal (`image_url.data:`) | `input_content[]` (`input_image`) | `content[]` (`image.base64`) |
| 關鍵固定值 | — | `store:false`；不送 `previous_response_id` | `anthropic-version: 2023-06-01` 頭必填；`max_tokens` 必填 |
| P1 工具執行 | 不執行；`tool_calls` 記 transcript `tool` 事件 | 同左 | 同左（`tool_use` 僅記錄；签名塊透傳不持久化） |

### 3.2 SSE 事件 → StreamEvent 映射

| 原生事件 | Chat Completions | Responses | Anthropic |
|----------|------------------|-----------|-----------|
| 正文增量 | `choices[].delta.content` → `TextDelta` | `response.output_text.delta` → `TextDelta` | `content_block_delta(text_delta)` → `TextDelta` |
| 思考增量 | `delta.reasoning_content`（兼容 `reasoning` / `reasoning_details` 摘要；同分片多表示只取一次） → `ReasoningDelta` | `reasoning_summary_text.delta` / `reasoning_text.delta` → `ReasoningDelta` | `thinking_delta` → `ReasoningDelta`（簽名/加密塊不展示，工具回合透傳見 §3.3） |
| 工具增量 | `delta.tool_calls[]`（按 `index` 聚合；空 ID 不覆蓋有效 ID） → `ToolDelta` | `response.output_item.added/done`（function_call） → `ToolDelta`/`ToolDone` | `content_block_start(tool_use)` + `input_json_delta` → `ToolDelta`；`content_block_stop` → `ToolDone` |
| 工具聚合上限 | 三協議＋TurnController 收集層共用單 tool call 聚合預算（args+id+name，沿用 1 MiB 串流記憶體預算；超限 → `Failed("TOOL_ARGS_TOO_LARGE", retryable=false, code="AGG_TOO_LARGE")`，不靜默截斷、不重試、不記錄毒化片段） | 同左 | 同左 |
| 用量 | 終態 `usage` → `Usage` | `response.completed.usage` → `Usage` | `message_start.message.usage` + `message_delta.usage` → `Usage` |
| 終態成功 | `finish_reason` + `[DONE]` → `Done` | `response.completed`（非空 output 為權威） → `Done` | `message_stop`（前需 `message_delta stop_reason`） → `Done` |
| 終態異常 | 缺 `[DONE]`/無合法 `finish_reason` → `Failed(retryable=true)` | `response.completed` 缺 `output`/空陣列 → 用流內已收增量恢復（記 `recovered=true`）；`response.failed/incomplete` → `Failed` | 缺 `message_stop` 或可見/工具塊未閉合 → `Failed(retryable=true)` |

- 發送前上下文窗口（Current）：`HistoryWindowCap` 僅裁剪發送用請求副本（oldest-first，淘汰最舊、保留最新、整訊息；預設 `Unbounded` 即不裁剪），UI 與 transcript 保留完整歷史；裁剪數見 `TurnController.droppedHistoryCount`（永不靜默）。token 預算待裝置量測後再定（TODO #16，不硬編數字）。
- UI 更新成本（Current）：可見增量先進 per-attempt buffer（O(delta) 累加）；區塊尚小時即時向 UI 提交（首繪快速、小 turn 行為不變），超出 4 KiB／32 append 節奏後才批次提交；終端與 cancel 必沖洗，快照讀 buffer，故串流可見且快照精確；`streamedTextDeltaCount` 計到達數（超限觸發 delta 在計數前被拒，不計入）、`uiTextUpdateCount` 計提交數（無匹配 UI 列的 flush 跳過提交、不計數）、`uiTextCopiedChars` 計提交拷貝字元。
- 可見文字預算（Current，#83）：每 attempt `TextDelta`＋`ReasoningDelta` 合計不得超過 100_000 字元（`MAX_TURN_TEXT_CHARS`，等於 durable-row 上限，tool marker 不計）；超限為 typed non-retryable `TEXT_TOO_LARGE`（code `AGG_TOO_LARGE`），不靜默截斷、不重試，失敗片段保留 partial。
- 用量權威值（Current，#84＋P1 fail-closed 跟進）：每 attempt 單一權威 Usage（last-wins 合併，null 欄位保留舊值，`TurnController` 在終端前寫一次（flush 亦清 attempt 計數），`SessionTranscriptSink` 在終端 core ack 後 best-effort 補寫（不在 terminal batch 內，失敗吞掉不影響終端；core 失敗時緩衝保留，同 runId 重試仍保有該列）；controller 的 attemptRunId 即 sink 的 runId；Anthropic 雙 Usage 正常 turn 單行，100 事件 flood 仍單行，flood 上限各層 128（第 129 起凍結）；sink 另有 distinct runId 256 上限（超限 best-effort 丟棄，先擋後記帳）＋ post-terminal trailing 每 runId 限追加一次（終端已沖洗者直接丟棄，全 runId 唯一一列）；`lastUsage` 為 lifetime-last（無 Usage 的 turn 不清除）。

### 3.3 工具 ID 與多輪一致性（P1 記錄、不執行）

- Chat：響應結束時為缺失/衝突 ID 分配響應級唯一值（`call_<uuid8>`），避讓原歷史 + 本響應既有 ID；`ToolDone` 與終態調用用同一 ID。
- Responses：`item_id/output_index/content_index` 區分同輪多 output item；opaque 欄位（encrypted reasoning 等）只駐內存，不進 transcript/Room/JSONL。
- Anthropic：思考塊 + 簽名在工具回合透傳給下一輪上下文（內存），不持久化；P1 因不執行工具，`tool_use` 僅記 `tool` 事件，下一輪不自動回傳 `tool_result`（記 `toolResultPending=true` 供 P2 接續）。

### 3.4 SSE 分幀（`SseFrameParser` 行為契約）

- 按行切分；`data:` 前綴去一空格後拼接，多行 `data:` 以 `\n` 合併為單一 payload。
- `:` 開頭行為註釋心跳（丟棄，不報錯）。
- `data: [DONE]`（Chat）為流結束標記，不做 JSON 解析。
- UTF-8 按位元組流解碼，不得按 Char 切分（多位元組字元跨 chunk 時緩衝未完成位元組）。
- 單行上限 1 MiB（防惡意服務端撐爆內存；超限 → `Failed("SSE_LINE_TOO_LONG", retryable=false, code="AGG_TOO_LARGE")`）。
- 單事件 `data:` 總量上限 1 MiB（沿用單行預算，非新數字；防多小行堆疊攻擊——每行合法但合併超限 → `Failed("SSE_FRAME_TOO_LARGE", retryable=false, code="AGG_TOO_LARGE")`，與單行超限／`SSE_TRUNCATED` 斷線區分）。
- 空行 = 事件邊界；無 `event:` 欄位時按 payload 內容嗅探（Chat/Responses/Anthropic 各自 try-parse，順序見實現）。

### 3.5 Block 身份規則

- Chat：在 delta 類型切換（text ↔ reasoning ↔ tool）時 `blockIndex++`。
- Responses：以 `(output_index, content_index)` 映射 `blockIndex`；同輪多 item 互不干擾。
- Anthropic：直接保留 `content_block.index` 為 `blockIndex`。
- 終態替換：僅當權威終態文本 ≠ 已流式聚合文本時觸發一次 `TextDelta(blockIndex=last, delta=fullReplacement)` + transcript `retry` 事件註記（P1 簡化，不做逐塊 diff）。

---

## 4. 圖片輸入降級（`ImageFallbackPolicy`）

| 順序 | 條件 | 動作 |
|------|------|------|
| 1 | 單圖 > 10 MiB 或邊長 > 8192px | 拒絕：`Failed("IMAGE_TOO_LARGE", retryable=false, code=null)`（送前拒絕，非聚合預算），不轉碼、不縮放 |
| 2 | `preserveOriginal=true`（預設） | 原位元組 + 原 MIME 直送；超 Provider 上限 → 拒絕（同上），不降質 |
| 3 | `preserveOriginal=false` 且 Provider 支援 | 允許轉 JPEG（quality 85）+ 最長邊縮至 2048；轉換在 IO dispatcher 執行 |
| 4 | Provider 不支援圖片（如純文本模型） | 剝離圖片、文本照發；transcript 記 `imagesOmitted=N` + 省略說明 |
| 5 | 多圖（>4 張） | 只送前 4 張，其餘記 `imagesOmitted`；UI toast 提示 |

- 圖片位元組永不寫入 Room/JSONL/logcat；transcript 只記 `imagesOmitted` 計數 + 穩定省略說明字串（見 §8.4）。
- `ChatImageRef` 路徑必須位於 App 私有區；外部 `content://` 先複製進工作區（有界 10 MiB，超限拒收）。

---

## 5. 重試 / 超時參數

### 5.1 參數表（沿用調查結論，全域預設，不可單請求覆寫——P1 簡化）

| 參數 | 值 | 說明 |
|------|----|------|
| 連接超時 | 15s | TCP + TLS 握手 |
| 寫超時 | 30s | 請求體上傳（含圖片 base64） |
| 讀超時 | 5min | **等待下一個 SSE chunk 的 idle 超時**，非整輪總時限；SSE 心跳不重置？**重置**（任一有效位元組到達即重置計時） |
| 最大重試 | 3 次 | 每次成功 turn 重置預算 |
| Backoff | 2s / 4s / 8s | 固定序列（非指數抖動，P1 簡化）；等待可被 `cancel()` 中斷 |
| OkHttp 底層重試 | 關閉（`retryOnConnectionFailure=false`） | 重試統一由 `TurnController` 編排，避免雙層重試放大 |

### 5.2 可重試 vs 不可重試（`ProviderErrorClassifier`）

```kotlin
package dev.librepocket.provider

enum class FailureKind { RETRYABLE, FATAL }

object ProviderErrorClassifier {
  fun classify(httpCode: Int?, ioError: Throwable?, bodySnippet: String?): FailureKind
}
```

| 類別 | 條件 | `retryable` |
|------|------|-------------|
| RETRYABLE | 連接中斷、讀超時、提前 EOF、HTTP 408/409/425/429/5xx（除 501）、`SSE_TRUNCATED` | `true` |
| FATAL | 401/403（認證）、402/402變體（額度/計費）、400/422（協議格式）、證書錯誤（SSLHandshake）、`IMAGE_TOO_LARGE`、`SSE_LINE_TOO_LONG`、`SSE_FRAME_TOO_LARGE`、`TOOL_ARGS_TOO_LARGE`、`TEXT_TOO_LARGE`、URL/協議配置錯 | `false` |
| 啟發式 | body 含 `insufficient_quota|billing|invalid_api_key|unauthorized|image_too_large|sse_line_too_long|sse_frame_too_large|tool_args_too_large|text_too_large`（大小寫無關） → FATAL；含 `rate_limit|overloaded|timeout|temporarily` → RETRYABLE | — |
- 型別化交叉引用（Current）：聚合／SSE 預算溢出另以型別化 `ProviderFailureCode.AGG_TOO_LARGE`（`ProviderFailure.code`／`StreamEvent.Failed.code`）傳遞，與上表 body 標記同義（`isResourceExhausted` 認 code 亦認 marker；實作見 `StreamEvent.kt`／`ProviderTransport.streamingFlow`／`TurnController.shouldDiscardPendingTools`）。未知 runtime 失敗另以 `ProviderFailureCode.UNKNOWN_RUNTIME` 傳遞（`streamingFlow` unknown fallback／直接拋 unknown／Error），`shouldDiscardPendingTools` 認 `AGG_TOO_LARGE` 或 `UNKNOWN_RUNTIME`；其他 `code == null` 致命事件（401／validation）維持 flush。

### 5.3 重試語義

- 失敗嘗試不提交 assistant history；前面已完成 turn 的工具記錄保持不變。
- 一個 logical turn 只寫一列 user（`runId=logicalTurnId`）；首嘗試重用該 id，後續重試各用新 `runId=attemptRunId` + 新 UI 輪次，並以 `parentRunId=logicalTurnId` + `attemptIndex` 回綁（logical 家族語義）；失敗半截輸出保留標 `isPartial`，新輸出不拼接舊塊。
- 每次 retryable 失敗先持久化該 attempt 的 partial 終端（`assistant` 列，`isPartial=1` + 脫敏 `failureReason`，已綁定家族），再寫 `retry` notice（`runId=logicalTurnId`）；重開/匯出不丟中間 partial。
- Stage F 終局旗標：`isFinal` 區分 logical-turn 最終結果與 per-attempt 中間 partial。success / terminal-failure / cancel assistant 列 `isFinal=1`；retryable-failed 中間 partial `isFinal=0`（寫於 retry notice 之前，永不關閉家族）；retry notice 不變。backoff 間隙的 cancel 命中已 terminalized attempt 時不再寫第二筆同 attempt assistant，改寫一列 system-kind 最終 cancel 標（`turn <familyId> cancelled`，`isFinal=1`，UI 隱藏、export 可見、backfill 認作終局）；未命中時沿用原 cancel 列（`isFinal=1`）。家族完成 ⟺ 有 `assistant isFinal=1` 或 system cancel / interrupted 終標；`isFinal=0` 非終端。
- 每次 `Retrying(attempt, max, delayMs)` 發射到 `Flow`，UI 顯示進度；等待期間 `cancel()` 立即中斷（`delay` 可取消）。
- 3 次耗盡 → `Failed` 終態 + transcript `retry` 事件（記 attempts=3）。

---

## 6. 脫敏正則表（`Redactor`）

順序執行；命中計數按規則名記錄。替換格式統一 `⟦RULE:…⟧`（避免與使用者原文碰撞）。

| # | 規則名 | 正則（Java/Kotlin） | 替換 | 備註 |
|---|--------|---------------------|------|------|
| R1 | `API_KEY_VALUE` | `(?i)(sk-[A-Za-z0-9-_]{8,}\|xox[bpas]-[A-Za-z0-9-]{8,}\|ghp_[A-Za-z0-9]{8,}\|gsk_[A-Za-z0-9]{8,}\|AIza[A-Za-z0-9-_]{8,})` | `⟦REDACTED:API_KEY⟧` | 常見前綴先行；通用 key 格式見 R2 |
| R2 | `BEARER_TOKEN` | `(?i)bearer\s+[A-Za-z0-9\-._~+/]+=*\s*` | `⟦REDACTED:TOKEN⟧` | 覆蓋 Authorization 頭回顯 |
| R3 | `JSON_KEY_FIELD` | `(?i)("?(api[_-]?key\|secret\|token\|password\|passwd\|auth)"?\s*[:=]\s*"?)[^",\s}]{4,}` | `$1⟦REDACTED⟧` | JSON/表單回顯；保留鍵名 |
| R4 | `URL_CREDENTIAL` | `(?i)(https?://)[^/\s:@]+:[^/\s@]+@` | `$1⟦REDACTED⟧@` | URL 內嵌帳密 |
| R5 | `URL_TOKEN_PARAM` | `(?i)([?&](api[_-]?key\|token\|access_token\|secret)\s*=\s*)[^&\s]+"` → 實作以 `[^&\s]*` | `$1⟦REDACTED⟧` | URL query token（`redactError` 必備） |
| R6 | `EMAIL` | `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` | `⟦REDACTED:EMAIL⟧` | 聊天匯出也套用 |
| R7 | `PHONE_GENERIC` | `(?<!\d)(\+?886[-.\s]?)?09\d{2}[-.\s]?\d{3}[-.\s]?\d{3}(?!\d)` | `⟦REDACTED:PHONE⟧` | 台灣手機優先；國際號見 R8 |
| R8 | `PHONE_INTL` | `(?<!\d)\+\d{1,3}[-.\s]?\d{4,14}(?!\d)` | `⟦REDACTED:PHONE⟧` | R7 之後執行，避免重複計數 |
| R9 | `ID_TW` | `[A-Z][12]\d{8}` | `⟦REDACTED:ID⟧` | 台灣身分證字號（寬鬆版，寧可誤殺） |
| R10 | `CARD_16` | `(?<!\d)(?:\d[ -]?){15,16}(?!\d)` + Luhn 校验通過才替換 | `⟦REDACTED:CARD⟧` | 需 Luhn 檢查（實作內建，不純靠正則） |
| R11 | `IPV4_PRIVATE` | `\b(10\.\d{1,3}\.\d{1,3}\.\d{1,3}\|192\.168\.\d{1,3}\.\d{1,3}\|172\.(1[6-9]\|2\d\|3[01])\.\d{1,3}\.\d{1,3})\b` | `⟦REDACTED:IP⟧` | 內網 IP（除錯 log 常見） |
| R12 | `ANDROID_ID_LIKE` | `(?i)(android[_-]?id|device[_-]?id|imei|serial)\s*[:=]\s*\S+` | `$1=⟦REDACTED⟧` | 設備標識回顯 |

### 6.5 錯誤訊息脫敏（`redactError`）

- 先跑 R1–R5 + R11（Key/Token/URL/內網 IP）＋ R13 `GEO_COORD` ＋ R14 `ANDROID_PATH`（beyond-spec 硬化：座標與絕對裝置路徑亦剝離，見 `redact/Redactor.kt` `ERROR_RULE_IDS`），再截斷至 500 字元。
- HTTP 錯誤只保留 `code + reason`（如 `HTTP 429 rate_limited`），body 片段最多 200 字元且須脫敏。
- `ChatUiState.error` 與 logcat 皆使用脫敏後字串；原始錯誤只駐內存，不落盤。

---

## 7. Key 存儲方案

### 7.1 威脅模型（P1 範圍）

- Target threat: reduce exposure to other apps, backup/restore, logs, and exported files. Current manifest sets `allowBackup=true`; backup XML excludes key-specific files, not transcripts or all app data. This is not a current backup-exclusion guarantee.
- 不防：Root 後記憶體 dump、系統級鍵盤記錄（超出 P1 範圍，如實告知用戶）。

### 7.2 配置校验（寫入 Keystore 前）

- `baseUrl` 必須 `https://`（`http://` 僅允許 `localhost`/`127.0.0.1`/`10.*`/`192.168.*` 開發用，且 UI 顯紅字警告）。
- Key 最小長度 8；全空白拒收；寫入前 `trim()`，不做格式強校验（各 Provider 前綴不一）。

### 7.3 過渡方案（P1 出貨）：`EncryptedPrefsVault`

```kotlin
// 依賴（由基建組在 catalog 宣告；規格僅聲明需求，不改 gradle）：
//   androidx.security:security-crypto:1.1.0（對齊骨架 catalog）
// key alias: "librepocket_master"（MasterKey.AES256_GCM）
// prefs file: "librepocket_keys"（EncryptedSharedPreferences AES256_SIV + AES256_GCM）
// key 格式：prefsKey = "provider_key/" + providerId（providerId 為 UUID，無注入風險）
```

- `getKey()` 回傳 `CharArray`；呼叫方（Provider 構造 OkHttp request 時）用後 `fill('\u0000')`。
- 不在任何 `toString()`/`log`/`Bundle`/`SavedState` 中出現明文。

### 7.4 長期方案（P2 實作，P1 凍結介面）：`DataStoreTinkVault`

- `androidx.datastore:datastore-preferences` 存 `Tink AEAD` 密文（Base64）。
- Tink keyset 由 Android Keystore 持有（`AndroidKeysetManager`，AES256-GCM）。
- P1 交付物：`DataStoreTinkVault` stub（拋 `UnsupportedOperationException("P2")`）+ 遷移單測（`EncryptedPrefs → DataStoreTink` 的資料搬遷斷言，見 §10.3 T4）。
- 遷移觸發：首次啟動檢測到舊 prefs 非空 → 後台搬遷 → 校验讀回 → 刪除舊 prefs（原子性：搬遷完成前保留舊檔）。

---

## 8. DB Schema（Room）

### 8.1 表

```kotlin
// sessions
@Entity(tableName = "sessions")
data class SessionEntity(
  @PrimaryKey val sessionId: String,   // UUID
  val title: String,                   // 脫敏後
  val model: String,                   // "providerId/modelId"
  val createdAt: Long,
  val updatedAt: Long,
  val pinned: Boolean = false,
)

// transcript_events（§1.5 TranscriptEvent 的持久形態；text 寫入前已脫敏）
@Entity(
  tableName = "transcript_events",
  foreignKeys = [ForeignKey(
    entity = SessionEntity::class, parentColumns = ["sessionId"],
    childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
  )],
  indices = [Index("sessionId", "seq"), Index("runId")],
)
data class TranscriptEventEntity(
  @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
  val sessionId: String,
  val seq: Long,                       // 單調遞增（同一 session）；由 DAO 在事務內 max+1
  val runId: String,
  val kind: String,                    // user|assistant|tool|steer|retry|system
  val text: String,                    // 脫敏後；大文本沿用分塊？P1 簡化：單 TEXT 欄，上限 100k（超限截斷+記 truncated=true）
  val truncated: Boolean = false,
  val imagesOmitted: Int = 0,
  val createdAt: Long,
  // Phase 3（DB version 2，Migration 1→2 向後相容加入；舊列預設 false/null）：
  // 取消/失敗 assistant 半截（isPartial）+ 失敗行的脫敏原因（failureReason）。
  val isPartial: Boolean = false,      // 欄位名 `isPartial INTEGER NOT NULL DEFAULT 0`
  val failureReason: String? = null,   // 欄位名 `failureReason TEXT NULL`
  // Stage C（DB version 3，Migration 2→3 向後相容加入；舊列預設 null/null）：
  // attempt 終端回綁 logical turn（parentRunId）+ 0-based 嘗試序號（attemptIndex）。
  val parentRunId: String? = null,     // 欄位名 `parentRunId TEXT NULL`
  val attemptIndex: Int? = null,       // 欄位名 `attemptIndex INTEGER NULL`
  // Stage F（DB version 4，Migration 3→4 向後相容加入；舊列預設 true）：
  // logical-turn 最終結果（true）vs per-attempt 中間 partial（false）。
  val isFinal: Boolean = true,         // 欄位名 `isFinal INTEGER NOT NULL DEFAULT 1`
)
```

DAO（`session/SessionDao.kt`）：

```kotlin
@Dao
interface SessionDao {
  @Insert suspend fun insertSession(s: SessionEntity)
  @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
  suspend fun allSessions(): List<SessionEntity>
  @Query("SELECT COALESCE(MAX(seq),0)+1 FROM transcript_events WHERE sessionId=:sid")
  suspend fun nextSeq(sid: String): Long
  @Insert suspend fun insertEvent(e: TranscriptEventEntity): Long
  @Query("SELECT * FROM transcript_events WHERE sessionId=:sid AND seq>:after ORDER BY seq ASC LIMIT :limit")
  suspend fun eventsAfter(sid: String, after: Long, limit: Int): List<TranscriptEventEntity>
  @Query("DELETE FROM sessions WHERE sessionId=:sid")
  suspend fun deleteSession(sid: String)   // CASCADE 清 events
  @Query("DELETE FROM transcript_events WHERE sessionId=:sid AND seq <= :throughSeq")
  suspend fun deleteEventsThrough(sid: String, throughSeq: Long): Int
  @Query("SELECT COUNT(*) FROM transcript_events WHERE sessionId=:sid")
  suspend fun eventCount(sid: String): Int
}
```

- DB 名：`librepocket.db`；版本 4（Stage F 起；`Migration(2, 3)` 以 `ALTER TABLE transcript_events ADD COLUMN parentRunId TEXT` + `ADD COLUMN attemptIndex INTEGER` 向後相容升級，永不用 destructive migration；舊列讀回預設 `parentRunId=null` / `attemptIndex=null`，即單 id 舊語義 `family = runId`。`Migration(1, 2)` 保留見上；Stage F 起 `Migration(3, 4)` 以 `ALTER TABLE transcript_events ADD COLUMN isFinal INTEGER NOT NULL DEFAULT 1` 向後相容升級，舊列讀回預設 `isFinal=true`，即舊單終端語義）。
- `seq` 分配與插入必須在同一 `@Transaction`（`RoomSessionStore.appendEvent` 內 `withTransaction`）。
- 並發：單一 `SessionStore` 實例 + `Mutex` 保證同 session `seq` 不重（Room 事務為第二道防線）。

### 8.2 索引

- `INDEX transcript_events(sessionId, seq)`：翻頁讀取主路徑。
- `INDEX transcript_events(runId)`：重試/steer 追溯（除錯用）。
- sessions 預設以 `updatedAt DESC` 排序（小表，全掃可接受，P1 不另建索引）。

### 8.3 Prune 策略

- 觸發：App 啟動 + 每 append 100 條後（計數器，非精確）。
- 規則（按序）：① 刪除 `pinned=false` 且 `updatedAt < now - maxAgeDays` 的 session（CASCADE）；② 單 session 超 `maxEventsPerSession` 時刪最舊 `seq`（保留最新 N 條）。
- 未確認結果和待匯入歸檔不淘汰（P1 無此狀態，條款保留供 P2）。
- `prune()` 返回 `PruneResult` 並寫 log（僅計數，無正文）。

### 8.4 寫入路徑（脫敏強制點）

```text
ChatSessionImpl 產生 assistant 文本
  → Redactor.redact()（M5）
  → imagesOmitted 計數 + 圖片位元組丟棄
  → RoomSessionStore.appendEvent()（IO dispatcher）
  → UI 的 UiMessage 顯示原文（內存），DB 只有脫敏版
```

- `steer` 佇列內容寫 `kind="steer"` 事件（含排隊時間，供除錯）。
- `Retrying` 寫 `kind="retry"` 事件（attempt/max/delayMs 結構化，不寫模型正文；Stage C 起一律綁 `runId=logicalTurnId`，非 terminal）。
- turn succeeded 寫 `kind="assistant"`（`isPartial=0`、`isFinal=1`，Stage C 起帶 `parentRunId/attemptIndex` 回綁家族）；cancel 寫 `kind="assistant"` 並置 `isPartial=1`（保留半截正文，Stage C 起同樣回綁，Stage F 起 `isFinal=1`）；turn failed（三參數/五參數）寫 `kind="assistant"`（`isPartial=1`）+ `failureReason`（脫敏原因，Stage C 起回綁；Stage F 起 terminal 用 `isFinal=1`、retryable 中間 partial 用六參數 `isFinal=0`）；每次 retryable 失敗先寫該 attempt 的 partial 終端（`isFinal=0`）再寫 `retry` notice；backoff 間隙 cancel 命中已 terminalized attempt 時改寫 `kind="system"` 的 `turn <familyId> cancelled` 終局標（`isFinal=1`，replay 隱藏、export 可見）；舊二參數 `onTurnFailed` 相容保留為 `kind="system"`（`text="turn <runId> failed: <reason>"`）。

### 8.5 JSONL 匯出 / 匯入（目標介面；非目前 UI 流程）

- 匯出：一行一 JSON（`{"seq":N,"runId":"…","kind":"…","text":"…(已脫敏)","imagesOmitted":0,"createdAt":…,"isPartial":false,"isFinal":true}`；Phase 3 起失敗/取消行另帶 `"failureReason":"…"`，`null` 時省略該鍵；Stage C 起 attempt 終端另帶 `"parentRunId":"…"` / `"attemptIndex":N`，`null` 時省略；Stage F 起常帶 `"isFinal":true/false`）+ 檔頭註解行？**無檔頭**（純 JSONL，`jq` 可直接處理）。
- 匯入：逐行 `kotlinx.serialization` 解析 → 校验 `kind ∈ {…}` + `seq` 遞增 → 新 `sessionId` 重寫入；`isPartial` / `failureReason` / `parentRunId` / `attemptIndex` / `isFinal` 五鍵可選（缺鍵的舊匯出讀回預設 `false` / `null` / `null` / `null` / `true`，即單 id 舊語義 + 舊單終端語義；`isFinal` 另接受 0/1）；失敗行整批回滾（單事務），錯誤訊息只報行號不貼內容。
- 續聊恢復（Phase 3；Stage C 改為 logical 家族判定）：`open` 在 meta 載入後、attach 前，把無 terminal 紀錄的 dangling 家族（有 user 列而同家族 `parentRunId ?: runId` 無 `assistant isFinal=1` 列、亦無 system cancel / interrupted / 失敗終標；`isFinal=0` 中間 partial 永非 terminal，retry/tool/steer 永非 terminal）補一列 `kind="system"` 的 `turn <familyId> interrupted` marker（replay 已過濾 system，不污染正常歷史）；retry→success 與 retry→terminal-failure 家族已擁 final attempt 列，不誤標；僅含非終局 partial（`isFinal=0`）+ retry 的家族（backoff sleeper 中被 kill，或 kill 於 partial 寫入與 retry notice 之間）仍 dangling 並補一 marker；backoff-gap cancel 家族擁 system cancel 終標，不誤標；已恢復的 user/assistant 前文（partial 列除外）納入首個 request，窗口上限介面見 `HistoryWindowCap`（量測出 token 預算前預設無截斷，截斷可觀察不靜默丟）。
- 續聊跨 provider 及跨 origin fail-closed（Stage G / R2-3 [P1/privacy]；已實作，Phase 4 加嚴）：`open` 比對 `meta.model` 的出處資訊與當前 endpoint 的 `providerId` 及 normalized origin（`scheme://host[:port]`，path 丟棄），不符即 fork 新 provider-owned session（舊歷史不自動轉送給新 provider 或不同 host，`historyWithheld=true`，UI 顯示 `RESUME_CROSS_PROVIDER_HISTORY_WITHHELD`）；本地顯示 replay 與 INTERRUPTED backfill 保持不變。新建會話一律寫入完整出處 `"${endpoint.providerId}@${origin}/${model}"`（`origin = normalizeOrigin(baseUrl)`）。比對邏輯支援：① 帶有 `@origin` 格式比對 `providerId` 與 `currentOrigin`（同 providerId 但不同 baseUrl，尤其是 custom 端點換 host 或任何 endpoint 換 host 一律 fail-closed）；② **舊的無 `@origin` 格式（如 `"preset:openai/gpt-4o-mini"`）一律 fail-closed 隔離（fork，不 hydrate；舊 canonical-origin 放行已移除，因 pre-R4-1 同 id 可能已混 A+B 列）**；③ 未知來源（legacy bare model id，無 `/`，如 `"gpt-4o-mini"`、`"m"`、`""`）不再自動放行，一律 fail-closed (withhold/fork)；④ 格式錯誤／外來／匯入列（如 `"imported/unknown"`、`"/gpt-4o-mini"`）一律 fail-closed (withhold/fork)。同 provider 同 origin 換 model 仍 hydrate（credential 與接收方不變，KeyVault 以 providerId 綁定）。窄綁定：hydrate 僅比對 `providerId`＋origin，不驗 `apiKeyRef`／config revision——同 origin 換 key 仍 hydrate。Stage 5（R4-1；已實作）：跨 provider/origin（含 legacy 無 `@origin`）的 `open` 不再原地 withhold，改為 fork 新 provider-owned session（title 沿用、provenance 重蓋，舊 session 只讀封存不寫 backfill/marker，僅新 ledger 寫 withheld marker 並對新 ledger 做 backfill；舊列永不拷貝）。**保證範圍限於 R4-1 後新建/fork 的 ledger；R2-3 時代已混雜 ledger（同一 id 內同時有 A 列與 B 列）不遷移／不回填／不拆分，legacy 無 `@origin` 重開一律 fork 隔離（不整批 hydrate），pre-R4-1 有 `@origin` 但已混雜者不自動偵測，疑似混雜一律開新對話手動隔離。 reasoning 截斷邊界 fail-closed：tail cap 省略舊列（`droppedCount>0`）或滿窗遠 marker（R25-1 `hasReasoningBefore` 探測命中）時保留 assistant 一律 `hasReasoning=true`（後者已知 over-flag，pair-aware 收窄為 Target），tool-dependent／thinking follow-up 顯式拒絕，純聊天不受影響；UI replay 僅 display-only，enforcement 以 model-context 為準；快照列-cap 丟棄維持靜默 legacy（無可觀察量：`droppedCount` 僅 same-id open 計數，不覆蓋 fork 快照；僅 char-cap／dangling／unsettled 寫 incomplete marker）。**
- 檔名：`librepocket-<sessionId8>-<yyyyMMddHHmm>.jsonl`；經 SAF 寫入用戶選位（P1 不自建 FileProvider 分享）。
- 大小上限：單 session 匯出 ≤ 20 MiB（超限拒絕並提示 prune）。

---

## 9. 權限 ruleset DSL

### 9.1 語法（`pattern = "action:resource"`）

```text
rule     := action ":" resource
action   := "chat.send" | "provider.call" | "session.export" | "key.read" | "key.write" | "*"
resource := wildcard 路徑，支援 "*"（單段）與 "**"（跨段），如：
  "provider.call:openai/*"        單段：該 provider 下所有模型
  "session.export:**"             跨段：所有 session 匯出
  "key.read:*"                    讀任何 Key
  "*:*"                           全通配（預設 DENY 覆寫用，見 §9.2）
```

- 大小寫敏感；前後空白 trim；空 action/resource 非法（`setRule` 拋 `IllegalArgumentException`）。
- P1 動作集合封閉（上表 5 個 + `*`）；未知 action 在 `evaluate` 視為 `DENY`（fail closed）。

### 9.2 評估語義

1. 收集所有 `action` 匹配（精確或 `*`）且 `resource` 匹配（wildcard）的規則。
2. 無匹配 → `DENY`（預設拒絕）。
3. 有匹配 → 取 `priority` 最大者；同 `priority` 出現 `DENY` 勝出（`DENY > ASK > ALLOW`），並記 `conflictResolved=true`（`PolicyDecision` 擴充欄位，實作可加）。
4. 預設 ruleset（首次啟動寫入）：
   ```text
   (0)  "*:*" → ASK              # 預設三態開關 = ASK（P1 保守預設）
   (10) "key.read:*" → ASK       # 讀 Key 每次確認（三風味斷言見 §11.5）
   (10) "key.write:*" → ASK
   (10) "session.export:**" → ASK # 匯出必然彈確認
   (10) "chat.send:*" → ALLOW    # 純聊天預設放行（無工具調用）
   (10) "provider.call:*" → ALLOW
   ```

### 9.3 執行前重檢（防 TOCTOU）

- 任何受控動作執行前必須呼叫 `evaluateFresh()`（重新載入 DataStore 快照再評估），`recheckedAt` 寫入 `PolicyDecision`。
- `evaluate()`（內存快照）僅供 UI 預顯示開關狀態；**不得作為執行依據**（Code Review 必查：執行路徑出現 `evaluate(` 而非 `evaluateFresh(` 即打回）。
- 重檢與執行間不持有鎖；若重檢結果為 `ASK`，走系統對話框（P1 用 `AlertDialog`，同意一次即放行本次，不記住）。

### 9.4 三態開關 UI 綁定

- 設定頁每條規則一行三段開關（允許 / 每次詢問 / 拒絕）↔ `Verdict`。
- wildcard 規則顯示原始 pattern（可刪不可改；新增走「新增規則」對話框，P1 簡單文本輸入 + 即時校验）。

---

## 10. 本機測試基建

### 10.1 三層劃分

| 層 | 位置 | Runner | 用途 | 禁止 |
|----|------|--------|------|------|
| JVM 單測 | `app/src/test` | JUnit4 + Robolectric（按需） | 純邏輯：SSE 解析、脫敏、分類器、降級策略、權限評估、轉錄編解碼 | 觸碰真實網路、真實 Keystore、真實 Room（用 fake/in-memory） |
| Robolectric | `app/src/test`（`@Config(sdk=[33,36,37])`） | Robolectric 4.17 | DataStore/EncryptedPrefs 行為、API 級別分支（§10.4 差異表斷言） | 真機硬體、Binder IPC |
| Instrumented | `app/src/androidTest`（P1 新建） | AndroidX Test + 模擬器/真機（API 33/37 矩陣） | 真實 Keystore 加解密往返、Room 事務併發、取消延遲手動驗證腳本 | 連外網（用 MockWebServer） |

- 基建組領地：`gradle/libs.versions.toml`、`app/build.gradle.kts` 的依賴/flavor 宣告。P1 規格**只聲明所需依賴**，不動檔案：
  ```text
  testImplementation junit:junit:4.13.2（已有）
  testImplementation robolectric:4.17（已有）
  testImplementation androidx.room:room-testing（需基建組加）
  androidTestImplementation androidx.test.ext:junit / espresso-core / room-testing（需基建組加）
  debugImplementation / testImplementation okhttp:mockwebserver（需基建組加，用於 SSE 重放）
  ```

### 10.2 API 33 / 36 / 37 行為差異表（測試必須覆蓋）

| 領域 | API 33（minSdk） | API 36 | API 37（target） | P1 對策 / 斷言 |
|------|------------------|--------|------------------|----------------|
| 通知權限 | `POST_NOTIFICATIONS` 執行期權限（33 引入） | 同左 | 同左 + 前台服務類型更嚴 | 重試/steer 通知走同一 `NotificationPermissionGate`；Robolectric `@Config(sdk)` 斷言三版行為一致 |
| 前台服務 | `FOREGROUND_SERVICE_SPECIAL_USE` 需聲明 | 36 對 `specialUse` 屬性要求更嚴（需 `property`） | 37 啟動限制再收緊（背景啟動 FGS 拒絕） | P1 聊天不開 FGS（純 Activity 內串流）；斷言 `ChatSessionImpl` 無 FGS 調用（Robolectric shadow 檢查） |
| Photo Picker | 自帶（33+） | 同左 | 同左 | 圖片輸入走 Photo Picker（不申請 `READ_MEDIA_IMAGES`）；instrumented 斷言未申請該權限 |
| Keystore | StrongBox 可用性因設備而異 | 同左 | 同左 + `KeyGenParameterSpec` 預設更嚴 | `EncryptedPrefsVault` 不指定 StrongBox（相容性優先）；instrumented 在兩版 API 上做加解密往返 |
| TLS | 預設 TLS 1.3 | 同左 | 同左；明文 HTTP 預設拒（`usesCleartextTraffic=false` 預設） | `http://` 非本地地址在 `ProviderConfig` 校验層拒絕（三版同一斷言） |
| Locale/夜間 | `localeFilters`（已有） | 同左 | 同左 | 脫敏/錯誤字串不依賴 locale（斷言英文固定 token `⟦REDACTED…⟧` 不被偽本地化影響） |

### 10.3 測試清單（每個類 → 測試類 + 斷言要點）

> 命名：`<Class>Test.kt`；位置按 §10.1 分層。`[J]`=JVM，`[R]`=Robolectric，`[I]`=instrumented。

**M1 Provider：**

| 測試類 | 層 | 斷言要點 |
|--------|----|----------|
| `SseFrameParserTest` | [J] | 多行 `data:` 合併；註釋心跳丟棄；`[DONE]` 識別；UTF-8 多位元組跨 chunk；單行超 1 MiB → 錯誤；空行切分 |
| `ChatCompletionsProviderTest` | [J]（MockWebServer 重放，真實 SSE 文本 fixture） | `index` 聚合 tool_call；空 ID 不覆蓋；缺 ID 分配唯一值；`finish_reason` 結束塊；缺 `[DONE]` → `Failed(retryable=true)`；`reasoning_content` 去重 |
| `ResponsesProviderTest` | [J] | `stream:true/store:false` 出站斷言；`response.completed` 空 output 用流內增量恢復；`response.failed` → FATAL；opaque 欄位不進 transcript |
| `AnthropicProviderTest` | [J] | `anthropic-version` 頭；`max_tokens` 必填；`thinking_delta` 投影；塊未閉合 + 缺 `message_stop` → `Failed`；簽名塊不持久化 |
| `ProviderErrorClassifierTest` | [J] | 表 §5.2 全覆蓋：429/5xx→RETRYABLE；401/402/400→FATAL；body 啟發式大小寫無關；`null` code + `SSLHandshakeException`→FATAL |
| `ImageFallbackPolicyTest` | [J] | 5 條規則逐條：超 10 MiB 拒；preserve 原樣直送；不支援模型剝離記 `imagesOmitted`；>4 張截斷 |
| `ModelsDevSnapshotTest` | [J]+[R] | 在線 JSON 超大小/非 https/協議校验失敗 → 回退快照；快照解析出候選模型；讀目錄不帶 Key（出站 header 斷言無 Authorization） |

**M2 Keystore：**

| 測試類 | 層 | 斷言要點 |
|--------|----|----------|
| `EncryptedPrefsVaultTest` | [I]（真 Keystore）+ [R]（邏輯：key 格式/空值拒收） | put→get→delete 往返；`hasKey` 不洩露長度；重啟進程後仍可讀；`http://` 校验在更上層（此層不管 URL） |
| `DataStoreTinkMigrationTest` | [J]（fake vault 雙寫邏輯） | 舊 prefs 非空 → 搬遷 → 讀回一致 → 刪舊檔；搬遷中斷（拋異常）時舊檔保留 |

**M3 聊天閉環：**

| 測試類 | 層 | 斷言要點 |
|--------|----|----------|
| `ChatSessionTest` | [J]（fake `LlmProvider` 吐固定 `StreamEvent` 序列） | send→STREAMING→IDLE；double-send 拋錯不崩；steer 排隊且不取消當前（fake 收到 cancel 次數=0）；turn 結束自動消耗一條 steer；cancel 後狀態 CANCELLED 且半截保留 |
| `TurnControllerTest` | [J] | 3 次重試序列 2/4/8s（fake clock，不真睡）；FATAL 不重試；`Retrying` 事件順序；runId 每次更換 |
| 取消延遲 | 手動（§11.4） | <200ms；instrumented 只記腳本，不自動斷言時間 |

**M4/M5 存儲+脫敏：**

| 測試類 | 層 | 斷言要點 |
|--------|----|----------|
| `RedactorTest` | [J] | §6 R1–R12 逐條正/反例；`redactError` 截斷 500 字 + URL token 剝離；`hits` 計數；Luhn 誤殺（非卡號 16 位數字不替換） |
| `RoomSessionStoreTest` | [I]（in-memory Room；併發寫用 10 協程各 append 50 條不斷 `seq`） | `seq` 單調；CASCADE 刪 session 清 events；prune 刪舊留新、pinned 豁免；匯出 JSONL 行數=事件數且 `jq` 可解析；匯入壞行整批回滾 |

**M6 權限：**

| 測試類 | 層 | 斷言要點 |
|--------|----|----------|
| `PolicyStoreTest` | [J] | 無匹配→DENY；priority 大者勝；同 priority DENY 勝出；`*` action；非法 pattern 拋錯；未知 action→DENY；`evaluateFresh` 重載後決策更新（fake backing 換 ruleset）；預設 ruleset 快照（§9.2 六條） |
| 三風味權限斷言 | [I]（見 §11.5） | `key.read:*` 三風味預設 ASK；`session.export` 觸發系統對話框（Espresso 斷言 dialog 出現）；foss/github 另斷言自動化開關預設關 |

### 10.4 Fixture 規範

- SSE 重放文本放 `app/src/test/resources/sse/chat-basic.txt`、`responses-recover.txt`、`anthropic-thinking.txt`（基建組建目錄，P1 規格定義檔名與內容格式）。
- models.dev 快照 fixture：`models-snapshot-min.json`（3 個模型，含 `reasoning:false` 標記其一）。
- 脫敏語料：`redact-cases.txt`（每行 `RULEID || input || expected`，測試逐行斷言）。

---

## 11. P1 Target 驗收標準（待依現況重新核定）

### 11.1 原規劃自動化門檻（非目前 CI / merge gate）

```bash
./gradlew :app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest   # JVM + Robolectric 三風味矩陣（含 sdk=33/36/37）
./gradlew :app:connectedPlayDebugAndroidTest :app:connectedFossDebugAndroidTest :app:connectedGithubDebugAndroidTest  # instrumented 三風味矩陣（Keystore 往返 + 權限斷言）
```

- 上述命令是原規劃的驗收目標，不代表目前 CI 合併門檻或已執行結果；以 [Testing](../TESTING.md) 的 Current 說明與該 SHA 的 CI/device 證據為準。
- 基建組負責 flavor 維度存在（`play` / `foss` / `github` 三風味含 `key.read ASK` 預設，見 §9.2）；P1 規格組負責斷言語句正確。

### 11.2 SSE 解析單測

- `SseFrameParserTest`、`ChatCompletionsProviderTest`、`ResponsesProviderTest`、`AnthropicProviderTest` 四類全綠，且 fixture 為真實抓包脫敏文本（非手編最小串）。

### 11.3 脫敏單測

- `RedactorTest` 全綠；R1–R12 每條 ≥3 正例 + ≥2 反例；`redactError` 有 URL token 案例。

### 11.4 取消 <200ms（手動驗證）

步驟：① 連 MockWebServer（chunk 間隔 500ms，連續 20 塊）；② 收到第 3 塊時點取消；③ 從點擊到 UI 狀態變 `CANCELLED` 且無新塊渲染，用 `adb shell screenrecord` / 慢動作計時（或 `SystemClock.elapsedRealtime()` 打點 log）。<200ms 通過。記錄設備型號 + API 級別存檔（`docs/specs/P1_ACCEPT.md`，P1 後補，不屬本規格）。

### 11.5 三風味權限斷言

- `connectedPlayDebugAndroidTest` 內：① `PolicyStore` 預設 `key.read:*` = ASK；② 觸發 `key.read` 彈系統對話框（Espresso `onView(withText("允許"))` 存在性斷言）；③ 拒絕後 `evaluateFresh` = DENY 且 Provider 未發出任何請求（MockWebServer requestCount=0）。
- `connectedFossDebugAndroidTest` / `connectedGithubDebugAndroidTest` 內：同上三斷言另加 ④ a11y 自動化類存在但開關預設關（`AutomationPolicy` 初始 `false` dump）。

### 11.6 驗收 Checklist（合併發布前逐項勾）

- [ ] `:app:testPlay/Foss/GithubDebugUnitTest` 全綠（含 sdk 33/36/37 Robolectric）
- [ ] SSE 四測試類全綠（真實 fixture）
- [ ] `RedactorTest` 全綠（R1–R12 + redactError）
- [ ] 手動取消 <200ms（記錄存檔）
- [ ] `connectedPlay/Foss/GithubDebugAndroidTest` 全綠（Keystore 往返 + 三風味權限斷言）
- [ ] `docs/specs/P1_SPEC.md` 與實現一致（Code Review 對照 §1 簽名）
- [ ] 未動 `app/src` 業務碼與 gradle 配置之外的新增依賴已交基建組（§10.1 清單）
- [ ] JSONL 匯出可用 `jq` 解析（抽查一 session）
- [ ] 預設 ruleset 快照測試通過（防預設被誤改）

---

## 12. 與基建組的邊界（避免衝突）

1. 本規格**只新增 `docs/specs/P1_SPEC.md`**；不碰 `app/src/**`、`gradle/**`、`*.toml`、`*.kts`。
2. 需要基建組配合：`room-testing`、`espresso-core`、`mockwebserver` 依賴；`androidTest` 原始碼集；`play` flavor 維度；`test/resources` 目錄。
3. Package 根 `dev.librepocket`（P1 新包）與骨架既有 `dev.librepocket.agent` 並存互不碰撞；實作 PR 需經基建組確認最終落點（`main` vs 新 source set）。
4. 超時/重試數值凍結（§5.1）；任何調整需更新本規格 + 同步調查結論文檔，禁止實作側擅改。

---

*文檔結束。實作時以 §1 介面簽名為準；簽名變更視為破壞性，需修訂本規格版本號。*
