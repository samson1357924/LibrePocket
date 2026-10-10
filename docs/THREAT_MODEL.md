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
- 續聊恢復有跨 provider 及跨 host/origin 出處檢查（Stage G / R2-3）：`open` 以 `meta.model` 的出處比對當前 endpoint 的 `providerId` 與 normalized origin（`scheme://host[:port]`）。新建會話寫入 `"${providerId}@${origin}/${model}"`。同 provider 但不同 baseUrl（特別是 custom 端點換 host，或 endpoint 換 host）不得自動轉送歷史；舊的無 `@origin` 格式僅 built-in preset 符合 canonical origin 時放行，custom 舊列 fail-closed；未知來源的舊列（裸 model id，無 `/`）一律 fail-closed 拒絕轉送歷史（`historyWithheld=true`，UI 顯示 `RESUME_CROSS_PROVIDER_HISTORY_WITHHELD`）；外來／匯入列同樣 withhold。本地顯示 replay 與 INTERRUPTED backfill 不變。
- Room 是目前 transcript 的持久化來源。Transcript sink/store 寫入時會套用 redaction；JSONL export helper 也再套一次。這只描述本機保存/匯出路徑，**不代表送出 provider 前的標準聊天請求已遮蔽**。不要因保存副本被遮蔽，就把原始文字當成未離開裝置。
- manifest 設定 `android:allowBackup="true"`，並連結 legacy 與 Android 12+ backup rules。這些規則排除 key 專用檔案／目錄；它們沒有把 transcript、一般 preferences 或 audit data 設為排除對象。實際備份還受 Android/OEM/使用者設定影響，不能承諾一定備份或一定不備份。
- JSONL codec、transcript export/import 與 backup bundle 有程式碼實作，但目前沒有可供使用者完成 flavor 遷移的 export/import UI 流程。backup bundle 預設不帶 key；其 API 可顯式要求帶 key。不要把「系統備份排除 key 檔」解讀成所有 app-level export 都不可能包含 key。
- `play`、`foss`、`github` 由不同 source set、manifest 與依賴組成。`foss`/`github` 含 accessibility service；該 service 不是 OS sandbox。Root、Shizuku、PRoot 同樣不等於隔離執行環境。
- 工具 registry 有 41 個定義，但目前所有內建 `ToolDef.executionReady` 均為 false；模型可見的內建工具集合為空。高風險工具描述或 scaffold 不代表目前可執行能力。

## 主要資產、威脅主體與信任邊界

| 資產 | 需考慮的威脅 | 現有邊界／限制 |
|---|---|---|
| Provider key | 裝置或 app 私有資料遭讀取、錯誤匯出、雲端/裝置轉移備份 | Android backup 規則排除特定 key 檔；不是對所有匯出路徑或遭 root 裝置的保證。 |
| Transcript、附件與 provider 請求 | Provider 接收敏感內容；備份或匯出副本外流；本機帳號/裝置遭存取 | Room 寫入 redaction 不等於送出前 redaction；`allowBackup=true` 且 transcript 未列入 key-only exclusions。 |
| 外部副作用 | 模型輸出、錯誤授權或競態導致不當操作 | 目前 built-in tools 全部 not-ready；未來接線必須逐工具驗證 permission、fresh evaluation、不可變確認快照及實際副作用。 |
| Flavor / 發布產物 | 不同 channel 意外帶入依賴、權限或錯誤 artifact | source-set 與 policy checks 存在；目前沒有 final-byte signature/certificate/package/version/debug fail-closed gate。 |

威脅主體包括惡意或遭入侵的 provider/MCP endpoint、提示注入內容、取得裝置或備份副本的人、以及錯誤配置或被替換的發布產物。本清單不宣稱每項攻擊都有防護。

## Target：接線前需維持的安全契約

- Credential 綁定 endpoint origin/config revision；讀取、解析或持久化故障採 fail-closed，不得靜默沿用其他 endpoint 的 credential。
- `ASK` 不可默認成 `ALLOW`。高風險確認必須綁定不可變操作快照，副作用前重新評估授權。
- Tool readiness 逐項推進並以負向控制驗證；未 verified 的工具保持不可執行。不能以設定開關、projection 或一般單測取代端到端安全證據。
- Session 初始化採 single-flight/generation；過期 callback 不得接入新 session。取消須解除 HTTP blocking I/O。
- 路徑權限以實體路徑、可信 executable 和 OS 邊界判斷；Root/Shizuku/PRoot 不可描述為 sandbox。
- 對使用者明確揭露 provider 傳輸、Android backup、匯出檔敏感性及 redaction 的實際作用範圍。

以上為待維持的工程契約，不表示整套 Target 已完成。更詳細的測試與 release 缺口見 [Testing](TESTING.md) 與 [Release](RELEASE.md)。
