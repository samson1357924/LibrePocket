# P1 instrumented 測試運行手冊（androidTest）

> 範圍：`app/src/androidTest`（SPEC §10.1 [I] 層）。JVM 單測不受影響，
> 照常用 `./gradlew :app:testPlayDebugUnitTest` 全綠。

## 測試一覽

| 測試類 | 斷言 | 網路 |
|--------|------|------|
| `keystore.EncryptedPrefsVaultInstrumentedTest` | 真 Keystore put→get→delete 往返、`hasKey` 不洩露長度、vault 重建仍可讀、空白/過短 Key 拒收 | 無 |
| `session.RoomSessionStoreConcurrencyInstrumentedTest` | in-memory Room，10 協程 × 50 append，`seq` 保持 1..500 稠密 | 無 |
| `policy.PlayPermissionPolicyInstrumentedTest` | ① `key.read:*` 預設 ASK；② ASK 彈確認對話框（Espresso 斷言「允許」出現＋「拒絕」關閉）；③ 拒絕後 `evaluateFresh` = DENY 且 Provider 零請求（MockWebServer `requestCount == 0`） | 僅 MockWebServer 本機迴環，不連外網 |

## 運行（需模擬器或真機，API 33 / 37 矩陣）

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2
export ANDROID_HOME=~/Android/Sdk
# 列出可用設備
$ANDROID_HOME/platform-tools/adb devices
# Play flavor instrumented 全量
./gradlew :app:connectedPlayDebugAndroidTest
```

## 狀態

- [x] `compilePlayDebugAndroidTestKotlin` 編譯通過（無設備可驗）。
- [ ] `connectedPlayDebugAndroidTest` 待真機/模擬器實跑（本機 `adb devices`
      為空時無法執行，見 SPEC §11.1 合併門檻，合併前須在 API 33 + 37 各跑一次）。
