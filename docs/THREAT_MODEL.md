# 威脅模型：目前資料流與安全邊界

> **狀態：Current snapshot**，依 `b3d8a818f37f8455a254a0670b512286e4deb750`（2026-10-07）核對。這不是正式安全認證，也不代表所有列出的防護均已驗證。
>
> - **範圍：** Android app 的聊天、provider、Room transcript、Android backup 與已宣告工具邊界。
> - **Owner role：** app/runtime 維護者；目前未指派個人或安全團隊。
> - **Source of truth：** manifest、backup XML、provider/session/tool source sets；總覽另見 [README](../README.md) 與 [capability matrix](CAPABILITY_MATRIX.md)。
> - **更新觸發：** provider request、transcript/key persistence、backup、flavor manifest、tool execution 或權限模型變更時，同一變更更新本文件。

## Current：已核對的資料與邊界

- 使用者輸入文字與請求內容會經網路送往所選 provider 才能取得遠端模型回覆。一般聊天請求沒有全域 outbound redaction：例如 Chat Completions 在一般 chat path 會序列化原始訊息文字；部分 hosted-search path 才條件式套用 `Redactor`。Room 寫入 redaction 不會追溯影響已送出的 request。BYOK 表示使用者提供 credential，不表示本機推論、零資料傳輸或 provider 不保留請求。使用者須自行確認 endpoint、供應商條款與傳送內容。
- 有使用者訊息的 turn，其送出的 `ChatRequest` 會在最後一則使用者訊息副本後附加 ephemeral `Runtime time context`（當下 wall-clock＋IANA 時區＋UTC offset＋weekday＋有 session start 時另附 session start，秒精度）；該區塊隨請求傳往 provider，不寫入 transcript／匯出／備份留存路徑。
- Room 是目前 transcript 的持久化來源。Transcript sink/store 寫入時會套用 redaction；JSONL export helper 也再套一次。這只描述本機保存/匯出路徑，**不代表送出 provider 前的標準聊天請求已遮蔽**。不要因保存副本被遮蔽，就把原始文字當成未離開裝置。
- manifest 設定 `android:allowBackup="true"`，並連結 legacy 與 Android 12+ backup rules。這些規則排除 key 專用檔案／目錄；它們沒有把 transcript、一般 preferences 或 audit data 設為排除對象。實際備份還受 Android/OEM/使用者設定影響，不能承諾一定備份或一定不備份。
- JSONL codec、transcript export/import 與 backup bundle 有程式碼實作，但目前沒有可供使用者完成 flavor 遷移的 export/import UI 流程。backup bundle 預設不帶 key；其 API 可顯式要求帶 key。不要把「系統備份排除 key 檔」解讀成所有 app-level export 都不可能包含 key。
- `play`、`foss`、`github` 由不同 source set、manifest 與依賴組成。`foss`/`github` 含 accessibility service；該 service 不是 OS sandbox。Root、Shizuku、PRoot 同樣不等於隔離執行環境。
- 工具 registry 有 41 個定義，但目前所有內建 `ToolDef.executionReady` 均為 false；模型可見的內建工具集合為空。高風險工具描述或 scaffold 不代表目前可執行能力。
- 直接 shell 通道的可信 executable 解析（`ShellExecutables.resolve`，JVM 單測覆蓋、尚未經指定裝置/API 實跑驗證）：bare 只在受控搜尋目錄按固定順序找，不查宿主 `PATH`、不看 cwd；含分隔符 `argv[0]` 詞法父目錄須在可信表內；候選經 `toRealPath()` 固定後驗正規檔＋可執行、containment（快照＋系統實體前綴）、real 本體至根不可寫、lexical entry 至所屬 searchDir 不可寫、searchDir 本體及其詞法與 real 兩側祖先至根不可寫；任一失敗即拒且零 spawn。拒絕僅回 reason code（`SEARCH_DIR_UNTRUSTED`／`ENTRY_WRITABLE`／`REAL_WRITABLE`／`OUTSIDE_ROOTS` 等），不含任意 filesystem path。殘餘 TOCTOU（驗證至 spawn 非原子）、APEX/overlay 窮舉不能、舊 toolbox applet 語義未驗仍在；操作數路徑（`FileScope.decide` 詞法前綴、symlink/TOCTOU）屬另一邊界，見 #9，不因 executable resolver 改善而關閉。
- provider SSE 串流在本機有 fail-closed 預算邊界（工具聚合與 per-attempt 集合有界；transcript／UI 文字與非工具 delta 數量／串流時長仍無界——無數小 text／reasoning frame 仍經 TurnController 無上限累積，見 #83）：單行 1 MiB（`SseFrameParser.MAX_LINE_BYTES`）、單事件 `data:` 總量 1 MiB（`SseFrameParser.MAX_FRAME_BYTES`）、每工具聚合 1 MiB（`args`+`id`+`name`，字元數 ×3 估算，`MAX_TOOL_CALL_AGG_BYTES`）、每 attempt 工具聚合總計同 1 MiB（LIVE-sum cap：同一時刻緩衝量；Anthropic TOOL restart／demote 會退休舊字元後重計，累積到達量可超 1 MiB 而 LIVE-sum 仍 ≤1 MiB，細節見 mapper 註解）、每 attempt distinct admission 上限 64（`MAX_TOOL_CALLS_PER_ATTEMPT`，Anthropic 任意 content-block kind 共用同一配額）、Responses `emittedToolIds` dedup set 同配額（`output_item.done` 路徑預先檢查；`finishPendingTools` drain 對每個已受限的 `callsByItem` 條目最多再收一個 pair，實務上界為 2× 上限（寬鬆安全上界；emit-once 冪等收斂後實際已發出 ≤64，見 mapper 註解））、JSON 巢狀深度上限 64（`MiniJson.MAX_DEPTH`）。超限回 typed non-retryable `ProviderFailure`（`TOOL_ARGS_TOO_LARGE` / `SSE_LINE_TOO_LONG` / `SSE_FRAME_TOO_LARGE`，code `AGG_TOO_LARGE`），不靜默截斷、不重試毒化串流；超出深度上限的 JSON 同屬此 typed 路徑（mapper 將 `MiniJsonDepthException` 轉為 `TOOL_ARGS_TOO_LARGE` / `AGG_TOO_LARGE` 並 latch 該 attempt：後續 terminal 不得回 Done，EOF 只回 typed 失敗、絕非 retryable `SSE_TRUNCATED`），一般 malformed frame 才走丟棄該 frame 路徑。Responses `output_item.done` 每 `(toolIndex, id)` pair 最多 emit 一次（重放為真 no-op）。殘餘邊界：這些只是本機記憶體／聚合邊界，不限制 provider 端計費、回應大小或對方記錄；transcript 與 UI 文字累積在 TurnController 層尚未有同類預算（#16 的端到端 budget 範圍；#54 只管 ledger／hydration）；另有三類殘餘未封（同屬 #16 範圍，除已緩解項外不在本變更修復）：key-storage 字節已緩解（S1 以 `MAX_TOOL_ID_CHARS`=4096／鍵封頂，`callsByItem`＋`emittedToolIds` 合併最壞 786432 字元有界，見 `StreamEvent` 註解）、事件速率／時長未封（`TextDelta`／`ReasoningDelta` 數量與 slow-drip 串流時長無 cap，只以計數器觀測）、可觀測性／錯誤碼傳遞缺口（一般 malformed frame 仍丟棄該 frame 且無計數；TurnController `catch (ProviderFailure)` 轉 `Failed` 時不攜 `code`，僅靠訊息標記回退判毒）；安全港（`Redactor`）與這些預算是不同機制，不可互相推論。

## 主要資產、威脅主體與信任邊界

| 資產 | 需考慮的威脅 | 現有邊界／限制 |
|---|---|---|
| Provider key | 裝置或 app 私有資料遭讀取、錯誤匯出、雲端/裝置轉移備份 | Android backup 規則排除特定 key 檔；不是對所有匯出路徑或遭 root 裝置的保證。 |
| Transcript、附件與 provider 請求 | Provider 接收敏感內容；備份或匯出副本外流；本機帳號/裝置遭存取 | Room 寫入 redaction 不等於送出前 redaction；`allowBackup=true` 且 transcript 未列入 key-only exclusions。 |
| 外部副作用 | 模型輸出、錯誤授權或競態導致不當操作 | 目前 built-in tools 全部 not-ready；未來接線必須逐工具驗證 permission、fresh evaluation、不可變確認快照及實際副作用。 |
| App workspace（`ScopedFileStore` 根） | 毒根別名（`alias/.`、`alias/sub`）、workspace 內 symlink 穿出、併發 `rename`/`symlink` 替換 | 單線程 NOFOLLOW 確定性保證（根鏈+段鏈全驗，毒根拒絕，缺席根單層建）；非 app UID 的併發 symlink 交換為 out-of-scope，Java 無 `O_NOFOLLOW/openat`，殘餘 TOCTOU 僅有界不消除。 |
| Flavor / 發布產物 | 不同 channel 意外帶入依賴、權限或錯誤 artifact | source-set 與 policy checks 存在；目前沒有 final-byte signature/certificate/package/version/debug fail-closed gate。 |

威脅主體包括惡意或遭入侵的 provider/MCP endpoint、提示注入內容、取得裝置或備份副本的人、以及錯誤配置或被替換的發布產物。本清單不宣稱每項攻擊都有防護。

## Target：接線前需維持的安全契約

- Credential 綁定 endpoint origin/config revision；讀取、解析或持久化故障採 fail-closed，不得靜默沿用其他 endpoint 的 credential。
- `ASK` 不可默認成 `ALLOW`。高風險確認必須綁定不可變操作快照，副作用前重新評估授權。
- Tool readiness 逐項推進並以負向控制驗證；未 verified 的工具保持不可執行。不能以設定開關、projection 或一般單測取代端到端安全證據。
- Session 初始化採 single-flight/generation；過期 callback 不得接入新 session。取消須解除 HTTP blocking I/O。
- 路徑權限以實體路徑、可信 executable 和 OS 邊界判斷；Root/Shizuku/PRoot 不可描述為 sandbox。
- `ScopedFileStore` 根須為 app 私有域可信基直建的正規目錄；毒根別名 fail-closed；非 app UID 的併發 symlink 交換為 out-of-scope，不宣稱消除併發 escapes。
- 直接 shell 通道啟用前須完成 Android 實機 smoke（生產預設 resolver/env）：toybox/toolbox/APEX 的 bare＋絕對 `argv[0]` 允許／拒絕與實際 argv0/env，以及 `Files.isWritable` 在 App UID/SELinux 下的表現；JVM 綠＋組裝綠不能代替裝置證據。exotic ROM（合法二進位在快照外或系統目錄可寫）降級為拒，須回報擴表、不得放寬。
- 對使用者明確揭露 provider 傳輸、Android backup、匯出檔敏感性及 redaction 的實際作用範圍。

以上為待維持的工程契約，不表示整套 Target 已完成。更詳細的測試與 release 缺口見 [Testing](TESTING.md) 與 [Release](RELEASE.md)。
