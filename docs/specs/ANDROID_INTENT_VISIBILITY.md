# Android Intent 可見性與權限宣告契約（ANDROID_INTENT_VISIBILITY）

> **狀態：Current。** 本文件是 `app/src/main/AndroidManifest.xml` 內
> SET_ALARM 發送端權限與 `<queries>` 可見性註解的正式 home：manifest 只留
> 短指標，完整理由集中在此。宣告的元素清單以 manifest 為準，本文件不改變
> 也不擴充宣告；測試釘選見 §6。
>
> Source of truth：
> - `app/src/main/AndroidManifest.xml`（common manifest；元素與屬性唯一來源）
> - `app/src/main/java/dev/librepocket/systema/`（`IntentSpec`、
>   `AndroidIntentLauncher`、`AlarmTool`、`CalendarTool`、`VolumeTool`、
>   `MusicTool`、`NavigationTool`、`LaunchAppTool` 各檔 KDoc）
> - `app/src/test/java/dev/librepocket/systema/IntentAdapterStage1Test.kt`、
>   `app/src/test/java/dev/librepocket/voice/VoiceSttTest.kt`、
>   `app/src/test/java/dev/librepocket/voice/VoiceDisclosureTest.kt`（manifest pin）
> - `app/src/play/AndroidManifest.xml` + `app/src/foss` + `app/src/github` overlays
>   （tools:node="remove" 錨點，見 §5）

## 1. 宣告了什麼（按元素）

### 1a. uses-permission：SET_ALARM 發送端權限

`com.android.alarm.permission.SET_ALARM`（#14，主 manifest 唯一宣告一次）。

- **為何是「發送端」權限**：接收端 activity（Google Clock / AOSP DeskClock 的
  SET_ALARM handler）以 `android:permission` 保護，限制的是**呼叫端**；
  發送 App 未持有會在 `startActivity` 被 `SecurityException` 拒絕。
  來源：官方 Common Intents 契約（SET_ALARM 條目）。
- **風險等級**：normal 級（安裝即自動授權、非高風險），Play 無需申報，
  也不進 Data Safety 披露。
- **唯一宣告位置**：只在 common manifest 宣告一次；三個 flavor overlay
  不得偷加（pin：`IntentAdapterStage1Test.manifest_declaresSetAlarmSenderPermission`
  的負向檢查，含「不得拼成 framework 命名空間
  `android.permission.SET_ALARM`」——該字面值不存在）。

### 1b. `<queries>`：可見性（不是能力）

| 意圖 | 服務對象 | 瑣記 |
|---|---|---|
| `android.speech.action.RECOGNIZE_SPEECH` | S2 `VoiceStt`（委託系統語音辨識，不新增 RECORD_AUDIO） | action 必須與 `VoiceStt.ACTION` 同字面值；禁用 `RecognitionService`（服務類名，非 intent action，解析不到）；無 handler 時 ChatScreen 降級手動輸入（NO_PRIVILEGE） |
| `android.intent.action.TTS_SERVICE` | S2 `VoiceTts`（`getEngines()` 引擎探測） | targetSdk 36 過濾下若缺此宣告，引擎表可能為空而被誤判 NO_ENGINE |
| `android.intent.action.SET_ALARM` | `AlarmTool` | 執行期 Intent 無 data，action-only 即可匹配時鐘 App 過濾器 |
| `android.intent.action.INSERT` + `mimeType vnd.android.cursor.dir/event` | `CalendarTool.buildInsertIntent` | 以 `CalendarContract.Events.CONTENT_TYPE`（dir/event）匹配日曆 App 過濾器 |
| `android.intent.action.EDIT` + `mimeType vnd.android.cursor.item/event` | `CalendarTool.buildEditIntent` | 以 `CalendarContract.Events.CONTENT_ITEM_TYPE`（item/event）匹配；mimeType 常量與 queries 條目精確對應（pin：`IntentAdapterStage1Test` 的字面 cross-check） |
| `android.intent.action.VIEW` + `scheme geo` | `NavigationTool`（地圖 App，本機 geo intent） | scheme-only 條目 |
| `android.intent.action.VIEW` + `scheme https` | `MusicTool`（網頁搜尋）與 `NavigationTool`（網頁地圖） | scheme-only 條目 |
| `android.settings.SOUND_SETTINGS` | `VolumeTool` 降級取系統音量面板（打不開 AudioManager 的替代） | 同 targetSdk 36 過濾，未宣告時與 geo/https 同形矛盾 |

`<queries>` 只向 PackageManager 申請**可見性**；它不授予執行權，也不改變
reason-code 契約（降級話術仍走 app 內 `ReasonCodes` 的完整對照表；flavor 差異
見 CAPABILITY_MATRIX「Current：可確認的邊界」一節）。

## 2. 為何必須宣告：targetSdk 36 package visibility

- targetSdk 36 下，API 30+ package visibility 會把**未宣告**的隱式 Intent
  從 PackageManager 過濾掉。
- launcher 決策是 resolve-first：`AndroidIntentLauncher` 先
  `resolveActivity` → `startActivity`（見 `IntentSpec` KDoc）。未宣告即
  resolve 恆 null，工具收斂為假 NO_HANDLER——但 static 能力檢查（各工具
  `check`）已宣稱 NATIVE。**宣告不出現 ≠ 能力不宣稱**，屬契約自相矛盾，
  故凡 resolve-first 必經的隱式 Intent 一律最小宣告。
- 誠實分際：宣告可見性只修「查不到 handler」這一層；真機上 handler 是否
  可用（實際啟動成功與否）仍未驗證，屬各工具 KDoc 記載的 E2E 缺口，
  不因本文件的宣告而自動變綠。

## 3. MIME 級匹配與直接啟動政策

- **INSERT／EDIT 為何只寫 mimeType、不寫 data URI**：執行期 Intent 帶
  content URI ＋顯式 dir/item type（與 queries 條目精確對應），匹配在
  PM 級完成、不依賴 provider-resolve；queries 的 `path` 屬性一律視為
  `*`，寫 data URI 也幫不上過濾（見 `CalendarTool.buildInsertIntent` 註記）。
- **SET_ALARM 為何 action-only**：執行期 Intent 無 data；時鐘 App 的
  過濾器不需要 mimeType。
- **直啟（setPackage）不採用**：OEM 時鐘／日曆包名各異，硬編碼等於猜測
  包名（#19 猜測執行禁令），且剝奪使用者選擇器。

## 4. 刻意不宣告的清單

- **開 App 的 launcher 清單查詢**：刻意不加入 `<queries>`——以
  `LaunchAppTool.QUERY_WHITELIST` 作唯一真相來源（白名單外不可見 →
  NeedClarification / Unknown，禁止猜包名）；缺口註記與後續條件
  （需裝置驗證後再決定是否補宣告）見 `LaunchAppTool` KDoc 的
  Target 缺口段落。
- **刪除日曆事件（DELETE）**：刻意不宣告——刪除不經 resolve-first launcher
  發射，無「可見性 → 可用性」語法，完整描述待寫（落地前見 `CalendarTool`
  KDoc）；先宣告只會把未經驗證的承載寫進 manifest。
- **直啟 setPackage**（§3，同屬「不宣告」決策的另一面）。

## 5. Play overlay 的 tools:node="remove" 錨點

- play overlay 對 `SEND_SMS` / `READ_SMS` / `RECEIVE_SMS` /
  `MANAGE_EXTERNAL_STORAGE` / `BIND_ACCESSIBILITY_SERVICE` /
  `BIND_VPN_SERVICE` 六個 uses-permission 加 6× `tools:node="remove"`
  錨點：defense in depth，保證 Play APK 在 base manifest 日後漂移時也
  不會帶這些權限。
- 邊界誠實記載：`BIND_ACCESSIBILITY_SERVICE` 的 remove 錨點只出現在
  play overlay——foss / github overlay 本就合法宣告 a11y service
  （預設關閉、two-step consent），故該 token 也永不加入 common 黑名單
  （pin：`ApkPolicyHarness.test_accessibility_token_stays_play_only` 等，
  見 docs/TESTING.md）。capability boundary 口徑：CAPABILITY_MATRIX
  「Current：可確認的邊界」（'Current verifiable boundary'）+ BACKLOG B5。
- 發送端權限與 visibility 宣告同理只放 common：flavor overlay 不得
  重複宣告 SET_ALARM / calendar mimeType
  （pin：`IntentAdapterStage1Test.manifest_flavorOverlays_doNotTouchAlarmCalendar`）。

## 6. 測試釘選與文件修改規則

| 檢查 | 釘選 | 語法 |
|---|---|---|
| SET_ALARM / INSERT・EDIT mimeType / VIEW geo・https / SOUND_SETTINGS 元素存在且單值 | `IntentAdapterStage1Test.manifest_main_declaresAlarmCalendarQueries` | 寬容 regex（語義鎖值；次位屬性如 `tools:node` 被容納） |
| SET_ALARM 權限存在 + 命名空間拼寫負向 + overlay 不得偷加 | `IntentAdapterStage1Test.manifest_declaresSetAlarmSenderPermission` | regex ＋ 嚴格保守 `contains` 負向 |
| overlay 不得動可見性 / Play 高風險宣告仍在 | `IntentAdapterStage1Test.manifest_flavorOverlays_doNotTouchAlarmCalendar` | 負向 contains ＋ regex |
| `RECOGNIZE_SPEECH` queries 條目（B1） | `VoiceSttTest` | 精確字串（逐字元） |
| `TTS_SERVICE` queries 條目 | `VoiceDisclosureTest` | 精確字串（逐字元） |

修改規則（fail-closed 觀點，本文件與 manifest 註解共同遵守）：

- 註解可自由增刪——結構化 gates（`backup_rules_check.py` /
  `apk_policy_inspect.py`）以 ElementTree 解析並丟棄註解
  （pin：`BackupRulesValidator.test_manifest_comment_survivor_no_longer_fools_the_check`
  等，見 docs/TESTING.md），註解內的殘留物不會使 gate 假 PASS。
- 主 manifest 註解**不得**引入字面值 `android.permission.SET_ALARM`
  （負向 pin 的嚴格保守 `contains` 會直接假紅），也不要重現
  `<action …/>` 同形元素文本（raw-text 級 regex pin 會連註解一起命中；
  helper 的 KDoc 明載此界限）。本文遵循這兩條。
