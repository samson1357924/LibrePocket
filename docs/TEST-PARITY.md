# TEST-PARITY — 提權橋 ↔ Shizuku 授權測試鏡像對照（#136-B1）

本檔列舉兩組**刻意孿生**的授權機制之測試對應關係，作為漂移（drift）防線：

- **bridge 側**：`PrivilegeBridgeStore` / `PrivilegeBridgeGrantPolicy` / `PrivilegeBridgeState`（main 源集，三 flavor 共用；橋授權持久真相源）
- **Shizuku 側**：`ShizukuGrantStore` / `ShizukuGrantPolicy`（github 源集；Shizuku Manager 授權持久化）

兩側實作同構（marker → journal → 有序擦除、fail-closed folding、self-heal），
修正一側而不回填另一側即構成漂移。**漂移規則：一側修正必須於同一 PR 回填另一側；
新增測試必須成對新增，並在本檔同步登記（同 PR 更新）。刻意分歧須登錄於下方
「已知分歧」一節並附理由；未登記的分歧視為缺陷。**

行號以本文檔合入時的 HEAD（d2c9a385）為準；測試檔改動時本檔行號須同步更新。
重建對照方式見文末附錄。

## 表 1 — Store／policy 邏輯鏡像（同名測試；`PrivilegeBridgeGrantTest` ↔ `ShizukuGrantTest`）

| # | bridge 側（file:line） | Shizuku 側（file:line） | 意圖 |
|---|------------------------|------------------------|------|
| 1 | `PrivilegeBridgeGrantTest.kt:64` neverGrantedIsInvalid | `ShizukuGrantTest.kt:55` neverGrantedIsInvalid | 從未授權 → Invalid(NEVER_GRANTED) |
| 2 | `:70` malformedGrantIsInvalid | `:61` malformedGrantIsInvalid | 格式損毀 → Invalid |
| 3 | `:137` unreadableConfigFailsClosed | `:136` unreadableConfigFailsClosed | 讀不到當前 endpoint/revision → Invalid（fail-closed） |
| 4 | `:146` endpointSwitchInvalidatesGrant | `:143` endpointSwitchInvalidatesGrant | endpoint 變更即失效 |
| 5 | `:153` revisionBumpInvalidatesGrant | `:150` revisionBumpInvalidatesGrant | revision 推進即失效 |
| 6 | `:160` matchingEndpointAndRevisionIsValid | `:157` matchingEndpointAndRevisionIsValid | 相同 endpoint＋revision → Valid |
| 7 | `:212` grantValidationRejectsInvalidInputs | `:103` grantValidationRejectsInvalidInputs | 簽發參數校驗 |
| 8 | `:251` saveBlankProviderThrows | `:199` saveBlankProviderThrows | 空白 providerId 簽發／落庫即拋 |
| 9 | `:321` revokeClearsPersistence | `:174` revokeClearsPersistence | clear 後 load()==null |
| 10 | `:331` loadMissingKeysYieldsNull | `:571` loadMissingKeysYieldsNull | 缺鍵 → null |
| 11 | `:358` cancellationExceptionPropagatesWithoutBeingSwallowed | `:634` cancellationExceptionPropagatesWithoutBeingSwallowed | CE 不得被吞 |
| 12 | `:563` journalBlocksResurrection_whenClearFails_afterJournalWritten | `:296` journalBlocksResurrection_whenClearFails_afterJournalWritten | journal 落盤後 clear 失敗：journal 擋復活 |
| 13 | `:598` journalWritten_clearThrows_restartLoadDenied | `:364` journalWritten_clearThrows_restartLoadDenied | journal 已寫＋clear 拋：重啟後 load()==null |
| 14 | `:627` pendingMarker_parentIsRegularFile_clearFails_restartGrantMustNotResurrect | `:329` pendingMarker_parentIsRegularFile_clearFails_restartGrantMustNotResurrect | marker 父為普通檔案 → clear 失敗 → 重啟後授權不復活 |
| 15 | `:663` saveClearsJournal | `:478` saveClearsJournal | 新授權取代撤權日誌 |
| 16 | `:689` journalWriteFailure_bestEffortWipesKeys_sameProcessAndRestartDeny | `:393` journalWriteFailure_bestEffortWipesKeys_sameProcessAndRestartDeny | journal 寫失敗＋best-effort wipe：同進程／重啟皆拒 |
| 17 | `:726` saveAfterCrashedClear_survivesStaleJournalHeal | `:430` saveAfterCrashedClear_survivesStaleJournalHeal | crash 後 save：新授權不被 stale journal heal 誤擦 |
| 18 | `:773` check_withUnhealedJournalPending_returnInvalid | `:504` check_withUnhealedJournalPending_returnInvalid | journal 未癒合時 check → Invalid |
| 19 | `:824` pendingRevokeMarkerPreventsResurrectionAndSelfHeals | `:532` pendingRevokeMarkerPreventsResurrectionAndSelfHeals | marker 擋復活＋self-heal |
| 20 | `:885` saveLandsBetweenJournalAndErase_clearKeepsFreshGrant | `:593` saveLandsBetweenJournalAndErase_clearKeepsFreshGrant | journal 與擦除之間落 grant：clear 保留新授權 |
| 21 | `:293` storeRoundTripBindsRevisionAndExpiry（同意圖、不同名；bridge 側含 `expiresAtMs` round-trip 斷言） | `:162` storeRoundTripBindsRevision | grant 落庫 round-trip（綁定 revision；bridge 另綁 expiry） |

（bridge 檔案行號：`app/src/test/java/dev/librepocket/shell/PrivilegeBridgeGrantTest.kt`；
Shizuku 檔案行號：`app/src/testGithub/java/dev/librepocket/privilege/github/ShizukuGrantTest.kt`。）

## 表 2 — Robolectric Store 生命週期鏡像（`PrivilegeBridgeStoreRobolectricTest` ↔ `ShizukuGrantStoreRobolectricTest`）

| # | bridge 側（file:line） | Shizuku 側（file:line） | 意圖 |
|---|------------------------|------------------------|------|
| R1 | `:74` contextConstructorUsesNoBackupFilesDirForPendingRevokeMarker | `:67` 同名 | context-constructor marker 落 noBackupFilesDir |
| R2 | `:81` contextConstructorNoBackupFilesDirUnavailable_markerInMemoryOnly | `:74` 同名 | noBackupFilesDir 不可用 → in-memory-only、不落備份域 |
| R3 | `:100` realContextRoundTripAndClear | `:93` 同名 | 真實 context round-trip＋clear |
| R4 | `:113` pendingRevokeMarkerSelfHealsAcrossRestarts | `:106` 同名 | 跨重啟 marker self-heal |
| R5 | `:138` selfHealEditFailure_loadReturnsNullAndMarkerAndJournalRemain | `:131` 同名 | self-heal edit 失敗 → null、marker/journal 殘留 |
| R6 | `:171` journalSelfHealEditFailure_loadReturnsNullAndJournalRemains | `:197` 同名（行號差大於其他行：Shizuku 版位於檔尾） | 無 marker 僅 journal：heal 失敗 → null、journal 保留 |
| R7 | `:200` corruptedPreferencesSelfHealsViaCorruptionHandler | `:165` 同名 | ReplaceFileCorruptionHandler 損毀修復（行號順序互換，測試本體同構） |

（bridge 檔案：`app/src/test/java/dev/librepocket/shell/PrivilegeBridgeStoreRobolectricTest.kt`；
Shizuku 檔案：`app/src/testGithub/java/dev/librepocket/privilege/github/ShizukuGrantStoreRobolectricTest.kt`。）

## 已知分歧（intentional divergence；附理由）

| 分歧 | 理由 |
|------|------|
| **TTL／到期時效**：bridge 側有 `expiresAtMs`＋`REASON_EXPIRED`／`shared clock`，Shizuku 側無 TTL（`serviceVersionChangeKeepsValid` P3-1 pin；無到期判定） | 產品決策：Shizuku 授權綁 endpoint＋revision，刻意不綁 TTL（見 `ShizukuGrantStore.check` KDoc 的 P3-1／TODO(#136-B3)）；TTL 側改動須先走 policy 變更流程 |
| **時鐘注入**：`PrivilegeBridgeStore` 有 `clock: () -> Long` 參數（`checkEvaluatesClockAfterLoadCompletes` 等以此釘）；`ShizukuGrantStore` 無 | bridge 側 TTL 判定需要時鐘源；Shizuku 側無時間判定需求 |
| **`SERVICE_VERSION`**：Shizuku 側 `SHIZUKU_GRANT_SERVICE_VERSION`（`Int`）落庫且僅留存稽核（`serviceVersionChangeKeepsValid`／`grantedAtMsDoesNotAffectCheck`）；bridge 側無此鍵 | Shizuku 側為 Manager 端版本留存；bridge 側授權與版本無關 |
| **`PrivilegeBridgeState`／`refresh`／`grant`／revoke 合併邏輯測試**（`stateGrant*`、`stateRefresh*`、`stateRevoke*`、`refreshAndCheck_withPendingMarker_returnInvalid`、`processSingletonIsStableUntilReset`、`checkEvaluatesClockAfterLoadCompletes`）僅 bridge 側存在 | State 機（opMutex、epoch 雙重檢查、withdrawScope 合成）為 bridge 專屬接線；Shizuku 側無對應層 |
| **`FlavorPrivilegeScope`** 與各 flavor scope：github 側＝detach→clear 合成；play/foss＝no-op | 源集差異；合成語義測試僅 github flavor 有意義（見 `FlavorPrivilegeScopeTest`） |
| **`AT_MS_IN_FUTURE` 等 bridge 專屬碼**（`grantWithFutureAtMs_failsFast`／`AT_MS_IN_FUTURE`／`PRIVILEGE_GRANT_REVOKED_CONCURRENTLY` 等）僅 bridge 側 | github 側無 atMs 過去/未來判定（授權簽發語義不同） |
| **`ttlExpiryBoundary`／`ttlOverflowThrows`／`negativeTtlFailsFast`／`nullTtlNeverExpires`／`zeroTtlExpiresImmediately`／`storeRoundTripWithoutExpiry`／`clockRollbackInvalidatesGrant`／`checkEvaluatesClockAfterLoadCompletes`／`refreshAndCheck_withPendingMarker_returnInvalid`**：bridge-only | 同「TTL／時鐘注入」；Shizuku 側 policy 無對應概念 |
| **`journalSelfHealEditFailure…` 行號 158（bridge）vs 197（Shizuku）；`corruptedPreferencesSelfHealsViaCorruptionHandler` 順序互換** | 檔內測試排序不同，測試本體同構；非語義分歧 |

## 未鏡像／缺口（誠實列缺）

- **`loadFailureFoldsToNull`（bridge:381）在 Shizuku 側無對應測試**：Shizuku `load()`
  同為 fail-closed folding（見 `ShizukuGrantStore.load()` 的 catch→null），但**未釘住**——
  建議回填（同一 fail-closed 實作，補釘即關閉此缺口）。
- **`refreshAndCheck_withPendingMarker_returnInvalid`（bridge:800）**：pending-marker × refresh()
  路徑的橋側專屬釘；Shizuku 側無 refresh()，僅有 `check_withUnhealedJournalPending_returnInvalid`
  （journal 分支，表 1 第 18 項）。
- Shizuku 側 **`SaveAfterCrashedClear`／`saveLandsBetweenJournalAndErase`** 與 bridge 側
  皆存在（表 1 第 17、20 項，同名）——非缺口。

## 附錄 — 重建對照

```sh
cd <repo根>
for f in app/src/test/java/dev/librepocket/shell/PrivilegeBridgeGrantTest.kt \
         app/src/testGithub/java/dev/librepocket/privilege/github/ShizukuGrantTest.kt \
         app/src/test/java/dev/librepocket/shell/PrivilegeBridgeStoreRobolectricTest.kt \
         app/src/testGithub/java/dev/librepocket/privilege/github/ShizukuGrantStoreRobolectricTest.kt; do
  echo "== $f"
  grep -B1 'fun ' "$f" | grep -A1 '@Test' | grep -oE 'fun [a-zA-Z0-9_]+' | sed 's/fun //' | sort
done
# 同名集合 = 鏡像對；差集 = 已知分歧／未鏡像（對照本檔各節歸類）。
# file:line 對照：grep -n "fun <testName>" <file>
```
