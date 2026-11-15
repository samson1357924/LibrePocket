# 審查標籤／分階段代碼的詞彙表（Round-6 起）

> **為什麼有這份文件**：程式碼與 KDoc 中大量使用短標籤（`Stage 3c 雙持有者`、`AA1`、`M1`、
> `Phase F`、`Round-6 IR-P1`…）。這些標籤在 review round 之間流通，本檔把常見者
> 集中解釋，讓新讀者不必反向考據 commit 歷史。**不承諾涵蓋每一個歷史標籤**；新分層
> 標籤出現時，連同本檔一起更新。
>
> 更新原則：本 PR 之後，任何新審查 round 的標籤，須在同 PR 內在本檔登錄一列。

## 分層主題（Android github flavor 提權執行路徑）

| 標籤 | 語義 | 主要錨點 |
|---|---|---|
| `#136-B2` | 提權執行 fail-closed 清理硬化（本 PR 的範圍） | `app/src/github/java/dev/librepocket/privilege/github/` |
| `#136-B3` | 裝置驗收＋告警接線＋runbook（**未開始**；ELEVATED_RUNNERS_READY=false 不可翻） | `PrivilegeActivation.kt`、PR body「已知限制」段 |
| `Stage 2A/3b/3c/4` | cleanup worker 槽 custody 規則演進（Caller 持槽→post-grace 線性化點→thread-bound 最終態） | `runCleanupBounded`（ElevatedProcessIo.kt）、`releaseCleanupSlotAfterVerify` |
| `Stage 3c 雙持有者` | custody 恰好一次轉移規則：verify 結果已知後 caller 釋放、或 stall 時 worker finally 釋放 | 同上 KDoc「AA1-slot」段 |
| `AA1-slot` | cleanup worker 槽 custody（跨 post-kill verify 持有／thread-bound 轉移） | `runCleanupBounded` |
| `M1` | 飽和臂 quarantine 原子登記：`acquireSlotOrQuarantine`（同一鎖二選一） | `ElevatedCleanupTracker.acquireSlotOrQuarantine` |
| `P1-A1/A2/A3/P1-4` | Round 的 P1 修正編號：原子二選一、untracked Residual 遙測、kill-never-attempted、飽和即拒 | `acquireSlotOrQuarantine`／`untrackedResidual` |
| `Round-6 IR-P1` | 飽和無槽 lingering worker 會被 sweep 放行／准入重開的 IR（independent re-review）P1 | `lingerDebt`、gate 5（`classifyAdmissionLocked`） |
| `Round-6 IR-P2` | 登記雙失敗（無槽＋雙登記皆拋）⇒ admission 無訊號 | `registrationFailedSticky`、`Admission.REGISTRATION_FAILED` |
| `Round-6 D-A1..D-E7` | 子代碼複審發現編號（A=併發、B=提權執行安全、C=測試、D=架構/KDoc、E=scope/CI） | 本檔末 P1 段＋`pr167-review-aggregation.md`（repo 外彙整表） |
| `Round-6 PG-#` | PocketGuard bot 發現（BLOCK/WARN/SUGGESTION） | 同上 |
| `drainFailClosedDrained` | drain 內部逃逸時的 catch-all 收斂臂：killTree best-effort＋中斷 thread＋登記 quarantine＋計數；outstanding 不釋放（B3＝重啟恢復） | `ElevatedProcessIo.drainWithThreadStarter`／`drainFailClosedDrained` |
| `Stage 1/2/3/4（本 PR 的 Round-6 提交階梯）` | 20af2877 linger-debt seal → ed1f8d81 sticky brick＋catch-all backstop → ff509999 遙測/文件 →（本 PR 最终 review commit） | git log |
| `quarantine（閘）／QUARANTINE` | 未驗證死亡 handle 的持久登記，admission 末門 QUARANTINE（fail-closed） | `ElevatedCleanupTracker.quarantineUnverified` |
| `sweep／verify-only sweep` | 以 `waitFor(0ms)` 有界驗證後才移除 quarantine 條目 | `verifyQuarantinedOneBounded` |
| `AB1` | 呼叫級准入（activeCalls / tryEnterReserved），上限 MAX_CONCURRENT_CALLS=4 | `tryEnterReserved` |
| `killTree` | PID-reuse 守門＋信號樹（descendants reflective seam＋本體 attest） | `ElevatedProcessKiller.kt` |

## 四態／五態 state 詞彙（worker/heartbeat 三態修正歷史的注記）

- `state==0` init、`1` held、`2` done-no-debt、`3` done-debt（Round-6 起 markLingering／cleanup cell）。
- `outstanding` = cleanup 槽佔用；`lingerDebt` = 飽和無槽 worker 的密封債務（gate 5）。
- `cleanupSlotState`（custody 狀態機）維持三態（0→1→2），與 markLingering 四態不同。

## 復原與恢復政策（誠實邊界）

- 任何「permanent seal」（Stage 3c verify-stall seal、Round-6 debt seal、round-6 brick）
  皆以**app 重啟**為恢復途徑；產品不自動清（`clearUnverified` 僅測試隔離可調）。
- 告警／runbook／device-acceptance 全歸 `#136-B3`；本 PR 僅到遙測計數與 KDoc。
- 裝置上 PID-reuse attestation 休眠（AOSP `toHandle()` UOE 樁）：`ElevatedProcessKiller`
  承載幾個「Android 上不會生效」的註腳式機制（descendants 信號、本體 identity 重讀），
  見其 KDoc 與 #136-B3 gate。

## #136-B3 待辦（未實作；本 PR 不啟用）

1. 裝置 E2E（Binder stall、Magisk/KernelSU/APatch、SELinux）。
2. `quarantineRegistrationFailedCount > 0`／`lingerDebtCount() > 0`／`cleanupBacklogFull`
   告警接線（audit／log／metric）＋治理決策（brick 是否公開）。
3. `pidReuseSkipped`／`bodyPidReuseSkipped`／`bodyDestroyUnattestedCount`／
   `untrackedResidual`／`killNeverAttempted`／`cleanupWorkerStalledAfterVerify`／
   `sweepSaturationSkips`／`swallowedPollThrows`／`sweepPendingCount` 告警 wiring。
4. spawn-time PID baseline（目前僅 kill-time 兩讀）；PID-reuse 完全驗證。
5. `run()` 文字路徑 Denied/Failed 丟棄 `cleanupIncomplete`（`runBinary` 為準）。
6. late-spawn daemon stall／`drainFailClosedDrained` catch-all 佔住的槽（無計數、
   無 protocol-level 恢復；app 重啟恢復）。
7. 在 `preSpawnLinger` 跨 request 依賴串行准入契約外增加型別級保證（可選）。
8. `docs/REVIEW_TAGS.md`（本檔）的維護責任歸 B3 維護者。
