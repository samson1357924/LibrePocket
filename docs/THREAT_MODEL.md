# 威脅模型：目前資料流與安全邊界

> **狀態：Current snapshot**，依 `6658e63086ea5b1af25ffe5bca4aaf28d6cee78e`（2026-10-15 提交，2026-10-15 核對）。這不是正式安全認證，也不代表所有列出的防護均已驗證。B1/B2 有界切片（PR #80）僅經 JVM 單測驗證，裝置 smoke 與快照更新待後續。
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

## 主要資產、威脅主體與信任邊界

| 資產 | 需考慮的威脅 | 現有邊界／限制 |
|---|---|---|
| Provider key | 裝置或 app 私有資料遭讀取、錯誤匯出、雲端/裝置轉移備份 | Android backup 規則排除特定 key 檔；不是對所有匯出路徑或遭 root 裝置的保證。 |
| Transcript、附件與 provider 請求 | Provider 接收敏感內容；備份或匯出副本外流；本機帳號/裝置遭存取 | Room 寫入 redaction 不等於送出前 redaction；`allowBackup=true` 且 transcript 未列入 key-only exclusions。 |
| 外部副作用 | 模型輸出、錯誤授權或競態導致不當操作 | 目前 built-in tools 全部 not-ready；未來接線必須逐工具驗證 permission、fresh evaluation、不可變確認快照及實際副作用。 |
| App workspace（`ScopedFileStore` 根） | 毒根別名（`alias/.`、`alias/sub`）、workspace 內 symlink 穿出、併發 `rename`/`symlink` 替換 | 單線程 NOFOLLOW 確定性保證（根鏈+段鏈全驗，毒根拒絕，缺席根單層建，單線程操作數本鏈穿出已擋（hardlink/mount、遞迴後代跟隨除外，見同行尾））；B2 刪前重驗最終目標（NOFOLLOW lstat 是 link 即拒不刪、正規域內 containment，單次置換的誤刪替換後外部檔已修為拒絕（單次 parent/final/root 置換拒絕，root 另以 fileKey 定錨、實目錄替換亦拒（ino 重用除外）、鍵缺席即拒；併發雙重替換僅有界））、寫入暫存建立前與落盤搬移前再驗整鏈、列目僅回相對路徑不洩全路徑（DirectoryStream 單遍 best-effort，不保證單遍快照隔離與跨調一致，短暫洩名窗口已揭露）；非 app UID 的併發 symlink 交換為 out-of-scope，Java 無 `O_NOFOLLOW/openat`，併發 TOCTOU 僅有界不消除；hardlink/mount 不在門內（與 ShellPolicy 同，列 out-of-scope）；B1 另有三態未知即拒＋real 身分獨立（見 ShellPolicy KDoc，store 側無此二者）；遞迴後代 interior-link + 跟隨旗標（find -L、grep -R 等）不在 B1 門內（二階殘餘，見 ShellPolicy KDoc，Refs #9）。 |
| Flavor / 發布產物 | 不同 channel 意外帶入依賴、權限或錯誤 artifact | source-set 與 policy checks 存在；本機已有 staged final-byte signature/certificate/package/version/debug fail-closed gate（`scripts/verify_release_apk.py`+`scripts/release_artifact_stage.py` 對 staged snapshot bytes 驗證，policy 跑在 staged bytes 上並重核 checksum，見 [Release](RELEASE.md)）；正式 key custody/provenance 與 signer-pin registry 未建立，public release workflow 仍停用（github 僅 signature/identity/checksum，無 artifact policy）。 |

威脅主體包括惡意或遭入侵的 provider/MCP endpoint、提示注入內容、取得裝置或備份副本的人、以及錯誤配置或被替換的發布產物。本清單不宣稱每項攻擊都有防護。

## Target：接線前需維持的安全契約

- Credential 綁定 endpoint origin/config revision；讀取、解析或持久化故障採 fail-closed，不得靜默沿用其他 endpoint 的 credential。
- S4-B typed-key 身份守衛（方案 B，hash 自動重綁）已 superseded（BLOCKER-1：K→切 B→加 x 變 Kx 即重綁送 B 的前綴外洩，故退役；見 S4-C）。保留敘述僅供歷史對照，不得再實作 hash 重綁。
- S4-C 顯式重綁確認（取代 S4-B，見 `SetupViewModel.PendingRebind`／`SetupUiState.confirmRebind`／`rebindSummary`）：已貼明文 key 綁定輸入時 endpoint 身份（providerId＋effectiveBaseUrl，trim 後全字串相等、case-sensitive：host 大小寫、尾端斜線、有無預設埠皆視為不同身份，不做其他正規化）；身份切換保留文字但永不自動重綁（`onApiKeyChange` 僅當 bound==null 且非空白才綁，非 null 任何變化不動；`selectPreset`／`onBaseUrlChange` 永不碰 binding）。三 entry（`testConnection`／`refreshModels`／`saveWithConsent`）按序 `confirmKeyWrite || confirmRebind`→silent return、logoutInFlight（probe）→`effectiveBaseUrlOf`→`deriveProviderId`→`keyIdentityChanged`（先於 validate、先於任何 policy／vault／provider I/O，短 key 亦報 `SETUP_KEY_IDENTITY_CHANGED`）→true 則 mint `PendingRebind`（in-memory、禁持久化／SavedState、零 key material；欄位 oldBound／newIdentity／oldLabel／newLabel／oldBaseUrl／newBaseUrl／newModel／formRevision／nonce）＋同 emission 設 `confirmRebind=true`＋`rebindSummary`（舊→新快照，顯示用）＋errorCode，零 I/O 返回；確認後不 auto-send（需再按一次測試／儲存）。`confirmRebind()` 成功只重綁＋清錯＋藏框（testing／saving 保持 false、零 provider 呼叫，不碰 `KeyWriteConsent`）；消費時驗 formRevision＋I==pending.newIdentity＋binding==oldBound＋T 非空，二次點按 fail-closed。對話框期間 selectPreset／onBaseUrlChange／onApiKeyChange 經 `invalidateRebind` 丟 pending＋藏框（binding 保留）；prefill／logout／retry 成功／save 成功同批清 binding＋pending＋藏框；雙對話框互斥。形式化：擋下 ⟺ T 非空 ∧ (B==null ∨ B!=I)，解除僅 switch-back（免確認藏框清錯）、顯式確認、fresh 終端清 B 後重貼。Informed-consent 殘餘：確認文案明示「不會立即傳送或儲存」但使用者仍可能未細讀即確認後再按測試（屬已知殘餘，未持有為充分知情）；同一 key 跨 endpoint 合法共用需經顯式確認（屬刻意摩擦，非 bypass）。
- `ASK` 不可默認成 `ALLOW`。高風險確認必須綁定不可變操作快照，副作用前重新評估授權。
- Setup／chat 授權門（S6 Current，JVM 單測覆蓋、未經裝置端到端驗證）：
  - probe 閘：`testConnection`／`refreshModels` 先經 typed-key 身份綁定比對（零 policy I/O），再取新鮮 `provider.call` verdict；僅 `ALLOW` 繼續，`ASK`／`DENY`／讀失敗皆 `PROBE_POLICY_DENIED` 且零 provider 建構、零請求；IO 內另有 fence＋pre-IO 複驗，`refreshModels` 另有 live 後、目錄抓取前第三閘。
  - `EDIT_KEEP_EXISTING` metadata-only：空白 key＋身份不變（providerId＋effectiveBaseUrl 全字串相等）時重用 live vault key，僅 metadata CAS；唯一 vault 接觸為存在性 `hasKey` 前提（不讀內容），零 put/get/delete；`ASK` 時同樣經 one-shot 確認對話框（身份列＋保留文案「不會讀取金鑰內容，僅確認存在；零 put/get/delete」），STAGED 對話框亦顯示身份列（label＋baseUrl＋newModel）。
  - chat `key.read` one-shot consent：T0 新鮮評估 `ASK` 需綁定一次性確認（對話框＋`confirmKeyRead(expected, token)`，綁 providerId／baseUrl／origin／protocol／apiKeyRef／configRevision／model＋mint generation；P2 token：拒絕時同 emission 產 `PendingKeyReadConfirm(binding＋generation＋confirmSequence＋opId/openSessionId)`，sequence 單調遞增永不重置，驗「仍為原請求、generation/seq 未變、binding 全欄相等」三者全等才 mint，任一不符 false 且零 vault 讀，mint 成功同鎖耗 token（單次有效，同 token 二次必 false），無 pending／已撤銷／已 newChat／已 open／logout 的 stale tap 必 false；`invalidateLifecycle(clearChat)`／`invalidateForEndpointChange(null)`／`openSession` 新輪／logout 皆 burn token＋consent（同一 `keyReadConsentLock` 下 bump＋burn），`dismiss` 同鎖丟棄兩者；`ChatScreen` capture seq＋binding＋token，mint 前驗新鮮、mint 後 stale completion 丟棄）；`DENY`／評估失敗恆拒並丟棄已 mint 同意書；T1 capture 邊界重驗：`ALLOW` 直通、`ASK` 僅憑 T0 同操作 proof 放行（`ALLOW`→`ASK` 翻轉拒絕），T1 拒絕丟棄殘留同意書槽位防重放；T1→`vault()`→`getKey` 含 suspend／IO（dispatcher hop＋Keystore/disk IO）的殘留窗口未持有門控跨越（屬已知殘留）；窗口內 `DENY` 翻轉後續無門捕捉，binding／generation 翻轉擋 attach 但擋不住已發生的 vault 讀（僅 close 抹除）。
  - `hasKey` 存在性檢查位置（刻意）：gate flow 的 live-alias `hasKey`、staged orphan-guard `hasKey`、keep reuse 前提 `hasKey` 皆置於 verdict 之前——僅測存在性（per-op 新 alias 或同 emission 快照 alias，不讀內容），oracle 值可忽略；真正副作用（putKey／metadata CAS／provider 建構）仍由其後新鮮 verdict 閘守。
  - providerId 粗粒度（已知限制）：`deriveProviderId` 為純 `preset:` 拼接；自訂端點共用同一 resource，不同自訂 URL 間政策無法區分；端點綁定仍以全字串身份比對 fail-closed（綁定≠授權粒度）。
  - `POLICY_DENIED_KEY_WRITE` 收斂理由（刻意不拆）：全 save 拒絕（`DENY`／`ASK` 失配／fence／快照讀失敗／vault 失敗／CAS 競爭）收斂至單一碼，避免以錯誤碼區分洩漏門控狀態（policy vs revision vs vault oracle）；teardown 競爭另立 `SAVE_BLOCKED_BY_TEARDOWN`（可重試語義不同），未知 preset／身份綁定另立 `SETUP_*`（policy 未被諮詢）。
- 測試衛生／掃描殘留（S6）：倉庫內 `unit-test-key-*`、`test-key-123`、`unit-test-device-roundtrip` 等皆為合成假字串（fake vault／本機回放，無付費或 production endpoint，無真 key）；其餘 `sk-` 字面皆為合成測試向量（詳見 `grep -rn 'sk-' app/src/test app/src/androidTest`），刻意保留作掃描器面向向量；會觸發 secret 掃描誤報，屬已知噪音而非洩漏。
- Tool readiness 逐項推進並以負向控制驗證；未 verified 的工具保持不可執行。不能以設定開關、projection 或一般單測取代端到端安全證據。
- Session 初始化採 single-flight/generation；過期 callback 不得接入新 session。取消須解除 HTTP blocking I/O。
- 路徑權限以實體路徑、可信 executable 和 OS 邊界判斷；Root/Shizuku/PRoot 不可描述為 sandbox。
- `ScopedFileStore` 根須為 app 私有域可信基直建的正規目錄；毒根別名須 fail-closed；單線程穿出、delete 單次置換誤刪須維持拒絕（刪前重驗最終目標非 symlink、正規化後不在域內／逸出即拒）；非 app UID 的併發 symlink 交換為 out-of-scope，併發 TOCTOU 僅要求有界不消除（Java 無 `O_NOFOLLOW/openat`），不宣稱消除併發 escapes。
- 直接 shell 通道啟用前須完成 Android 實機 smoke（生產預設 resolver/env）：toybox/toolbox/APEX 的 bare＋絕對 `argv[0]` 允許／拒絕與實際 argv0/env，以及 `Files.isWritable` 在 App UID/SELinux 下的表現；JVM 綠＋組裝綠不能代替裝置證據。exotic ROM（合法二進位在快照外或系統目錄可寫）降級為拒，須回報擴表、不得放寬。
- 對使用者明確揭露 provider 傳輸、Android backup、匯出檔敏感性及 redaction 的實際作用範圍。

以上為待維持的工程契約，不表示整套 Target 已完成。更詳細的測試與 release 缺口見 [Testing](TESTING.md) 與 [Release](RELEASE.md)。
