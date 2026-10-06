# LibrePocket 能力矩陣（CAPABILITY_MATRIX）

> 目的：誠實界定「什麼條件下能做什麼」。模型看到的工具子集由能力投影按本表即時計算。
> 風味：`play`（Play 上架合規版）/ `foss`（F-Droid 純開源版）/ `github`（GitHub 直裝完整版，含專有服務）。
> 權限列：`N` = 原生（公開 API/Intent）/`D` = 降級（轉系統 App 接管或只讀）/`—` = 不提供。
> foss / github 能力相同，差異僅在實作棧（OCR/條碼：foss 用 ZXing/Tesseract/LiteRT 純 OSS；github 用 ML Kit；play 兩者皆無，見 `ARCHITECTURE.md` §9.4）。

## 0. 閱讀方式

- 同一列從左到右：免 Root（基線）→ Shizuku → Root → LSPosed 模組。每格寫實作路徑與等級。
- Play 版欄為合規裁剪後的結果；foss / github 版欄為技術上限。三者差異見 §2。
- 凡涉及自動點擊螢幕、讀取他 App 畫面、攔截簡訊/通知全文，一律只出現在 foss / github 版且需使用者顯式開關。

## 1. 系統操作矩陣（快通道為主，Intent 優先）

> Play 版放寬原則（2026-01-28 無障礙收緊後）：無障礙自動化仍整包移除；以下在申報 + 醒目披露 + 演示影片 + Data Safety 下可上 Play：日曆讀寫、前台位置、通知監聽（預設只讀標題）、MediaProjection 系統截圖（每次授權）、精準鬧鐘、聯繫人委託選人（免權限）/ 全量另審、Photo Picker / SAF 文件。

| 操作 | 免 Root（基線路徑） | Shizuku 增強 | Root 增強 | LSPosed（可選） | Play 版 | Foss 版 | GitHub 版 |
|---|---|---|---|---|---|---|---|
| 導航（去某地/路線） | `N` 地圖 geo Intent / 網頁地圖降級 | 同左，無增強 | 同左，無增強 | 無 | ✅ 完整 | ✅ 完整 | ✅ 完整 |
| 開啟 App | `N` 明確/隱式 Intent（需澄清模糊名） | 同左 | 同左 | 無 | ✅ | ✅ | ✅ |
| 郵件（寫/草稿） | `N` 郵件 Intent 預填（經系統 App 發出） | 同左 | 同左 | 無 | ✅（僅經系統 App，不後台直發） | ✅（可配 MCP/擴充直發，需確認） | ✅（可配 MCP/擴充直發，需確認） |
| 鬧鐘（新增/查詢） | `N` `ALARM_CLOCK` Intent + 系統鬧鐘 App | 同左 | 同左 | 無 | ✅ | ✅ | ✅ |
| 電話（撥打） | `D` 僅 `ACTION_DIAL` 預填號碼，由使用者按撥出；不直撥 | 同左 | 同左 | 無 | ✅（僅 DIAL） | ✅（僅 DIAL，預設亦不直撥） | ✅（僅 DIAL，預設亦不直撥） |
| 簡訊（發送/讀取） | `D` 跳系統簡訊編輯器預填，不自動發送；不讀取簡訊庫 | 同左 | 同左 | 無 | ✅（僅預填；不申請 SMS 權限） | ✅（預填；讀取需另行授權且預設關） | ✅（預填；讀取需另行授權且預設關） |
| 日曆（查/建事件） | `N` Calendar Provider + 系統日曆 Intent | 同左 | 同左 | 無 | ✅（需日曆權限 + 申報 + 用途披露） | ✅ | ✅ |
| 位置（定位/導航輔助） | `N` 前台定位（僅使用期間）；導航本身免權限 | 同左 | 同左 | 無 | ✅（僅前台；後台不上 Play） | ✅（前台；後台僅 foss / github 嘗試，另審，預設不含） | ✅（前台；後台僅 foss / github 嘗試，另審，預設不含） |
| 聯繫人（選人/查號） | `N` 委託系統選人（免權限，臨時授權）優先 | 同左 | 同左 | 無 | ✅（僅委託，不申請 READ_CONTACTS） | ✅（委託優先；全量另審，預設關） | ✅（委託優先；全量另審，預設關） |
| 音樂（播放/切歌） | `N` 媒體 Intent + MediaSession 控制（前台） | 同左 | 同左 | 無 | ✅ | ✅ | ✅ |
| 音量（調節/靜音） | `N` AudioManager 公開 API | 同左 | 同左 | 無 | ✅ | ✅ | ✅ |
| 通知（查詢/朗讀） | `D` 僅讀本 App 通知或經 NotificationListener 需使用者授權；預設只讀標題 | 同左 | 同左 | 無 | ✅（Listener 需申報 + 明示用途 + 最小化，預設關，標題優先） | ✅（同左，可選全文需二次確認） | ✅（同左，可選全文需二次確認） |
| 截圖（擷取/分享） | `D` 經系統截圖 / MediaProjection 需每次授權 | 同左 | `N` 可選幀緩衝直取（僅 foss / github + Root + 明示開關） | 無 | ✅（僅系統路徑，每次授權 + FGS 類型聲明） | ✅（系統路徑 + Root 可選路徑） | ✅（系統路徑 + Root 可選路徑） |
| 剪貼簿（讀/寫） | `N` 前台讀寫（背景讀受系統限制，誠實提示） | 同左 | 同左 | 無 | ✅（前台） | ✅（前台） | ✅（前台） |
| 檔案（列/讀/寫自有域） | `N` App 私有域 + SAF/ MediaStore 公開域 | `N` 可列他域（經 Shizuku 檔案橋，需授權） | `N` 同 Shizuku 路徑 | 無 | ✅（自有域+SAF；不申請全存取） | ✅（+ Shizuku/Root 可選路徑） | ✅（+ Shizuku/Root 可選路徑） |
| 終端命令（受限 shell） | `N` App 內受限 shell（無提權、白名單命令） | `N` 可選提權子進程（顯式開關） | `N` 可選 su 子進程（顯式開關） | 無 | ✅（僅受限 shell） | ✅（受限 + 可選提權） | ✅（受限 + 可選提權） |
| Linux 環境（PRoot 發行版） | `N` 使用者態 PRoot（無需 Root，效能降級明示） | 無增強 | 無增強 | 無 | ✅（若體積政策允許，否則改下載式插件） | ✅ | ✅ |
| GUI 自動化（點/滑/填表） | `—` 不提供；改給手動步驟指引 | `—`（仍不提供點擊；僅輔助讀狀態） | `—` 原則不提供；僅 foss / github 版經無障礙節點有限支援 | 有限支援（模組僅作手勢輔助，需另行安裝） | ❌ 整個類別隱藏 | ⚠️ 可選（預設關，需無障礙授權+二次確認） | ⚠️ 可選（預設關，需無障礙授權+二次確認） |
| VPN/流量攔截 | `—` 不提供 | `—` | `—` | `—` | ❌ | ❌（三風味皆不提供） | ❌（三風味皆不提供） |
| Xposed 注入他 App | `—` 不提供 | `—` | `—` | 本 App 不內建注入，僅預留外部模組協議 | ❌ | ❌（不內建） | ❌（不內建） |

## 2. Play / Foss / GitHub 裁剪清單（合規邊界）

> 歷史注記：pre-1.0 文档中的 `full` 風味即現 `github` 風味；`foss` 為新增的 F-Droid 純開源風味。見 `MIGRATION_FULL_TO_GITHUB.md`。

Play 版（`flavor == play`）**一律不包含 / 不申請**：

1. 無障礙自動化點擊/填表能力（程式碼以風味原始碼集整包移除，不僅是隱藏按鈕）；
2. `SEND_SMS / RECEIVE_SMS / READ_SMS` 等 SMS 權限；簡訊一律走系統編輯器預填；
3. `MANAGE_EXTERNAL_STORAGE` 存取；僅用 SAF + MediaStore + 自有域；
4. VPNService 流量路徑；
5. 任何 Xposed/注入式能力。

雙黑名單（程式碼事實：`HardeningPolicy` + `scripts/play_policy_check.sh`）：

- Play 黑名單（play 產物零容忍）：權限 `SEND_SMS / RECEIVE_SMS / READ_SMS / MANAGE_EXTERNAL_STORAGE / BIND_ACCESSIBILITY_SERVICE / BIND_VPN_SERVICE`；dex 定義前綴 `Ldev/librepocket/agent/github/`、`Ldev/librepocket/agent/foss/`；超類 `VpnService`、`AccessibilityService`；manifest service 含 accessibilityservice / vpnservice 字樣；zip 條目含 `proot` / `rootfs` / `linux/image`（S4 下載式 rootfs，全風味不內嵌，見 `HardeningPolicy.PLAY_LINUX_ENTRY_BLACKLIST`，此為 zip 掃描，非 dex 掃描）。
- Foss 黑名單（foss 產物零容忍，純開源自證）：dex 不得引用 `com.google.mlkit` / `com.google.android.gms`（`FOSS_STRING_BLACKLIST`，`--foss` 門）；foss 視覺棧只用 ZXing / Tesseract / LiteRT。

Play 版上架自查（每次發版必跑）：

- `aapt dump permissions <apk>` 斷言不含上述權限；
- 反編譯/字串掃描斷言不含 `VPNService` 子類、`AccessibilityService` 自動化子類（foss / github 版專屬目錄在 play 構建中不存在）；
- `scripts/play_policy_check.sh <play-apk/aab>` 通過；foss 產物另跑 `scripts/play_policy_check.sh --foss <foss-apk/aab>`；
- 資料安全表單與實際權限一致；通知監聽、截圖、日曆用途需在商店描述中明示。

Foss / GitHub 版額外能力全部預設關閉，開啟需兩步（系統授權 + App 內二次確認），且能力投影會把未授權項標為 `UNAVAILABLE`。

## 3. 提權路徑說明（Shizuku / Root 僅可選增強）

- 定位：提權只用於「檔案跨域讀寫、受限 shell 提權子進程、截圖可選路徑」三類增強，不改變主流程。
- 探測：啟動時被動探測（Shizuku binder 可達性 / su 可用性），失敗不打擾使用者，僅在使用者進入相關功能時提示。
- 授權：每次跨權限邊界呼叫攜帶调用原因碼，寫入審計表；使用者可在設定中一鍵收回。
- 降級：提權不可用時自動回落到免 Root 路徑；若回落後無法完成，模型必須說出原因與手動替代步驟，禁止虛構成功。

## 4. 授權模型對照

| 等級 | 例子 | 授權方式 |
|---|---|---|
| READ | 查日曆、列自有檔案 | 首次系統權限授權即可 |
| WRITE | 建鬧鐘、建日曆事件、寫自有檔案 | 系統權限 + App 內一次性確認（可記住） |
| PRIVILEGED | DIAL 預填、簡訊預填、截圖、通知全文、提權命令、GUI 自動化 | 每次確認或系統接管（由系統 App 完成最後一步） |

## 5. 降級話術規範（模型必須遵守）

- 不可用時回覆模板：`做不到 X（原因碼）→ 可替代 Y → 需要你做 Z`。
- 禁止語：假裝已發送/已撥打/已刪除；禁止編造「已為你點擊」。
- 所有降級回覆需附理由碼（`FLAVOR_BLOCKED / NO_PRIVILEGE / USER_DISABLED`），理由碼來自投影快照，不由模型自由發揮。
