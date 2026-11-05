package dev.librepocket.agent.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1b RoleManager 純邏輯測試（Issue #137：全 fake 快照，純 JVM）。
 *
 * 覆盖：快照→決策真值表、雙向調和（held 優先清除／GRANTED 非持有覆寫撤銷）、
 * 原因碼文案（被拒／撤銷／OEM 不支援／請求失敗皆有原因，不靜默）。
 *
 * 命名：舊測保留原名相容；Phase 1 新增一律 given-when-then 風格。
 */
class AssistantRoleTest {

    @Test
    fun held_shortCircuitsRegardlessOfAvailability() {
        assertEquals(
            AssistantRoleDecision.AlreadyHeld,
            decideAssistantRole(AssistantRoleSnapshot(roleAvailable = true, roleHeld = true)),
        )
        assertEquals(
            AssistantRoleDecision.AlreadyHeld,
            decideAssistantRole(AssistantRoleSnapshot(roleAvailable = false, roleHeld = true)),
        )
    }

    @Test
    fun availableAndNotHeld_requestsRole() {
        assertEquals(
            AssistantRoleDecision.RequestRole,
            decideAssistantRole(AssistantRoleSnapshot(roleAvailable = true, roleHeld = false)),
        )
    }

    @Test
    fun unsupportedAndNotHeld_fallsBackWithoutRequest() {
        assertEquals(
            AssistantRoleDecision.UnsupportedFallback,
            decideAssistantRole(AssistantRoleSnapshot(roleAvailable = false, roleHeld = false)),
        )
    }

    @Test
    fun grantedButNoLongerHeld_reportsRevoked() {
        val revoked = effectiveAssistantReason(
            AssistantReasonCode.GRANTED,
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = false),
        )
        assertEquals(AssistantReasonCode.REVOKED, revoked)
    }

    @Test
    fun grantedAndStillHeld_staysGranted() {
        // Phase 1 語義變更：held 持有態優先，一律清除原因碼（頭部已表達持有，
        // GRANTED 原因文案與 held 頭部完全同字串，保留會重複）。舊名保留相容。
        val cleared = effectiveAssistantReason(
            AssistantReasonCode.GRANTED,
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
        )
        assertNull(cleared)
    }

    @Test
    fun nonGrantedReasons_passThroughIncludingNull() {
        val snapshot = AssistantRoleSnapshot(roleAvailable = false, roleHeld = false)
        assertEquals(
            AssistantReasonCode.DENIED,
            effectiveAssistantReason(AssistantReasonCode.DENIED, snapshot),
        )
        assertNull(effectiveAssistantReason(null, snapshot))
    }

    @Test
    fun everyReasonCode_hasNonBlankText() {
        listOf(
            AssistantReasonCode.GRANTED,
            AssistantReasonCode.DENIED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REQUEST_FAILED,
            AssistantReasonCode.VERIFYING,
            AssistantReasonCode.UNCONFIRMED,
        ).forEach { code ->
            assertTrue("$code 文案不可為空", assistantReasonText(code).isNotBlank())
        }
        // 未知碼原樣回報，不偽造語義。
        assertTrue(assistantReasonText("SOME_FUTURE_CODE").contains("SOME_FUTURE_CODE"))
    }

    // ---- Phase 1 新增（given-when-then）：雙向調和 ----

    @Test
    fun givenDeniedButNowHeld_whenReconciled_thenClearsReason() {
        // DENIED＋held 矛盾鎖定：頭部已顯示持有，原因碼必須清除，
        // 否則頭部「已持有」與原因「被拒」同時出現。
        listOf(
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
            AssistantRoleSnapshot(roleAvailable = false, roleHeld = true),
        ).forEach { snapshot ->
            assertNull(
                "DENIED+held 必須清除（avail=${snapshot.roleAvailable}）",
                effectiveAssistantReason(AssistantReasonCode.DENIED, snapshot),
            )
        }
    }

    @Test
    fun givenGrantedAndHeld_whenReconciled_thenClearsToNull() {
        // GRANTED＋held 去重鎖定：held 頭部與 GRANTED 原因文案完全同字串，
        // 原因碼必須清除，否則同一文案出現兩次。
        listOf(
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
            AssistantRoleSnapshot(roleAvailable = false, roleHeld = true),
        ).forEach { snapshot ->
            assertNull(
                "GRANTED+held 必須清除（avail=${snapshot.roleAvailable}）",
                effectiveAssistantReason(AssistantReasonCode.GRANTED, snapshot),
            )
        }
    }

    @Test
    fun givenOtherCodesButNowHeld_whenReconciled_thenClearsReason() {
        // M2：held 持有態優先於一切原因碼（KDoc 語義鎖定）：除 DENIED／GRANTED 外，
        // UNSUPPORTED／REVOKED／REQUEST_FAILED＋held 亦必須清除，否則頭部「已持有」
        // 與原因區文案同時出現。
        listOf(
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.REQUEST_FAILED,
        ).forEach { code ->
            listOf(
                AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
                AssistantRoleSnapshot(roleAvailable = false, roleHeld = true),
            ).forEach { snapshot ->
                assertNull(
                    "$code+held 必須清除（avail=${snapshot.roleAvailable}）",
                    effectiveAssistantReason(code, snapshot),
                )
            }
        }
    }

    @Test
    fun givenPassthroughCodes_whenNotHeld_thenPassThroughUnchanged() {
        // 非持有時非 GRANTED 碼原樣 passthrough（參數化）。
        val snapshot = AssistantRoleSnapshot(roleAvailable = false, roleHeld = false)
        listOf(
            AssistantReasonCode.DENIED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REQUEST_FAILED,
        ).forEach { code ->
            assertEquals(
                "$code 非持有時應原樣 passthrough",
                code,
                effectiveAssistantReason(code, snapshot),
            )
        }
    }

    @Test
    fun givenGranted_whenNotHeldAndUnavailable_thenReportsRevoked() {
        // B10：撤銷偵測與 roleAvailable 無關；OEM 回報不支援但曾授予，
        // 只要非持有即視為撤銷。
        val revoked = effectiveAssistantReason(
            AssistantReasonCode.GRANTED,
            AssistantRoleSnapshot(roleAvailable = false, roleHeld = false),
        )
        assertEquals(AssistantReasonCode.REVOKED, revoked)
    }

    @Test
    fun givenNull_whenHeld_thenStaysNull() {
        // held＋null：無原因可清，維持 null（不憑空產生原因碼）。
        assertNull(
            effectiveAssistantReason(
                null,
                AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
            ),
        )
    }

    // ---- Phase 1 新增（given-when-then）：文案精確斷言 ----

    @Test
    fun givenEveryReasonCode_whenMappedToText_thenExactChineseText() {
        // 精確比對（非僅 isNotBlank）：鎖定 UI 文案，改字即破測。
        assertEquals(
            "已設為預設助理：長按首頁鍵或系統手勢可叫出 LibrePocket。",
            assistantReasonText(AssistantReasonCode.GRANTED),
        )
        assertEquals(
            "要求被拒：維持現狀，可改用語音輸入設定或稍後重試。",
            assistantReasonText(AssistantReasonCode.DENIED),
        )
        assertEquals(
            "預設助理角色已被撤銷：可重新要求，或改用語音輸入設定。",
            assistantReasonText(AssistantReasonCode.REVOKED),
        )
        assertEquals(
            "不支援系統助理角色，請改用語音輸入設定。",
            assistantReasonText(AssistantReasonCode.UNSUPPORTED),
        )
        assertEquals(
            "無法開啟系統授權頁，請改用語音輸入設定。",
            assistantReasonText(AssistantReasonCode.REQUEST_FAILED),
        )
        assertEquals(
            "要求已送出，正在向系統確認角色狀態…",
            assistantReasonText(AssistantReasonCode.VERIFYING),
        )
        assertEquals(
            "授權要求已送出，但未能確認角色狀態，可重新檢查。",
            assistantReasonText(AssistantReasonCode.UNCONFIRMED),
        )
    }

    @Test
    fun givenEmptyCode_whenMappedToText_thenUnknownBranch() {
        // 空字串走未知分支：原樣回報，不偽造語義、不崩潰。
        assertEquals("未知狀態（）。", assistantReasonText(""))
    }

    // ---- Stage 1 新增：selectFallbackTarget（resolver 繞過 hardening） ----

    @Test
    fun givenSingleSystemCandidate_whenSelected_thenReturnsIt() {
        val system = FallbackCandidate("com.android.settings", "VoiceInputSettings", true, false)
        assertEquals(system, selectFallbackTarget(listOf(system)))
    }

    @Test
    fun givenSystemPlusThirdParty_whenSelected_thenReturnsSystem() {
        val system = FallbackCandidate("com.android.settings", "VoiceInputSettings", true, false)
        val third = FallbackCandidate("com.evil.app", "HijackActivity", false, false)
        assertEquals(system, selectFallbackTarget(listOf(system, third)))
    }

    @Test
    fun givenResolverPlusSystem_whenSelected_thenExcludesResolver() {
        val resolver = FallbackCandidate("android", "ResolverActivity", true, true)
        val system = FallbackCandidate("com.android.settings", "VoiceInputSettings", true, false)
        assertEquals(system, selectFallbackTarget(listOf(resolver, system)))
    }

    @Test
    fun givenOnlyThirdParty_whenSelected_thenReturnsNull() {
        val third = FallbackCandidate("com.evil.app", "HijackActivity", false, false)
        assertNull(selectFallbackTarget(listOf(third)))
    }

    @Test
    fun givenOnlyResolver_whenSelected_thenReturnsNull() {
        val resolver = FallbackCandidate("android", "ResolverActivity", true, true)
        assertNull(selectFallbackTarget(listOf(resolver)))
    }

    @Test
    fun givenMultipleSystemCandidates_whenSelected_thenReturnsNull() {
        // 多系統候選無法安全決定唯一目標：回 null 顯示失敗，不放行。
        val a = FallbackCandidate("com.android.settings", "VoiceInputSettingsA", true, false)
        val b = FallbackCandidate("com.oem.settings", "VoiceInputSettingsB", true, false)
        assertNull(selectFallbackTarget(listOf(a, b)))
    }

    // ---- Stage 2 新增：四參版調和（everHeld＋pendingVerify） ----

    @Test
    fun givenEverHeldButStoredNull_whenNotHeld_thenReportsRevoked() {
        // 外部授予→撤銷鏈路：stored 雖 null（未經本應用回調），但曾觀察 held 即判撤銷。
        val revoked = effectiveAssistantReason(
            stored = null,
            snapshot = AssistantRoleSnapshot(roleAvailable = true, roleHeld = false),
            everHeld = true,
            pendingVerify = false,
        )
        assertEquals(AssistantReasonCode.REVOKED, revoked)
    }

    /**
     * 行為變更（re-review #3 finding #1 [P2]，舊名 `givenJustGranted…KeepsGranted`）：
     * RESULT_OK 回調不再寫 GRANTED（否則用早期 false 快照斷言撤銷），驗證中
     * 回 VERIFYING——既不宣稱授予、亦不判撤銷。新流程下驗證中 stored 恆為 null；
     * stored GRANTED＋pending 組合僅兼容舊構造，同樣回 VERIFYING。
     */
    @Test
    fun givenPendingVerify_whenNotHeld_thenShowsVerifying() {
        // RESULT_OK 後落盤延遲：重讀快照仍非持有是預期競態，顯示驗證中。
        listOf(
            null, // 新流程：回調只置 pending，stored 清 null。
            AssistantReasonCode.GRANTED, // 舊構造兼容：同樣視為驗證中。
        ).forEach { stored ->
            assertEquals(
                "驗證中 stored=$stored 必須回 VERIFYING（非 GRANTED／REVOKED）",
                AssistantReasonCode.VERIFYING,
                effectiveAssistantReason(
                    stored = stored,
                    snapshot = AssistantRoleSnapshot(roleAvailable = true, roleHeld = false),
                    everHeld = false,
                    pendingVerify = true,
                ),
            )
        }
    }

    @Test
    fun givenHeld_whenPendingVerify_thenHeldClearsFirst() {
        // held 優先於 pendingVerify：持有態一律清除原因碼，不殘留 GRANTED／VERIFYING；
        // held 確認同時是驗證結束信號（consumePendingVerify 同判 false）。
        listOf(
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
            AssistantRoleSnapshot(roleAvailable = false, roleHeld = true),
        ).forEach { snapshot ->
            listOf(
                null,
                AssistantReasonCode.GRANTED,
                AssistantReasonCode.DENIED,
            ).forEach { stored ->
                assertNull(
                    "held+pendingVerify 必須清除（avail=${snapshot.roleAvailable}, stored=$stored）",
                    effectiveAssistantReason(
                        stored = stored,
                        snapshot = snapshot,
                        everHeld = true,
                        pendingVerify = true,
                    ),
                )
            }
        }
    }

    @Test
    fun givenNeverHeldAndStoredNull_whenNotHeld_thenPassThroughNull() {
        // 從未持有＋stored null：無授予痕跡，原樣 passthrough null（不憑空判撤銷）。
        assertNull(
            effectiveAssistantReason(
                stored = null,
                snapshot = AssistantRoleSnapshot(roleAvailable = true, roleHeld = false),
                everHeld = false,
                pendingVerify = false,
            ),
        )
    }

    @Test
    fun givenDeniedWithEverHeld_whenNotHeld_thenDeniedPassthrough() {
        // 新鮮 DENIED 不被陳舊 everHeld 覆寫：拒絕優先於持有痕跡。
        val snapshot = AssistantRoleSnapshot(roleAvailable = true, roleHeld = false)
        assertEquals(
            AssistantReasonCode.DENIED,
            effectiveAssistantReason(
                stored = AssistantReasonCode.DENIED,
                snapshot = snapshot,
                everHeld = true,
                pendingVerify = false,
            ),
        )
    }

    @Test
    fun givenUnsupportedWithEverHeld_whenNotHeld_thenUnsupportedPassthrough() {
        // 新鮮 UNSUPPORTED 同理 passthrough，不判撤銷。
        val snapshot = AssistantRoleSnapshot(roleAvailable = false, roleHeld = false)
        assertEquals(
            AssistantReasonCode.UNSUPPORTED,
            effectiveAssistantReason(
                stored = AssistantReasonCode.UNSUPPORTED,
                snapshot = snapshot,
                everHeld = true,
                pendingVerify = false,
            ),
        )
    }

    @Test
    fun givenRequestFailedWithEverHeld_whenNotHeld_thenRequestFailedPassthrough() {
        // 新鮮 REQUEST_FAILED 不被陳舊 everHeld 覆寫：請求失敗優先於持有痕跡。
        val snapshot = AssistantRoleSnapshot(roleAvailable = true, roleHeld = false)
        assertEquals(
            AssistantReasonCode.REQUEST_FAILED,
            effectiveAssistantReason(
                stored = AssistantReasonCode.REQUEST_FAILED,
                snapshot = snapshot,
                everHeld = true,
                pendingVerify = false,
            ),
        )
    }

    @Test
    fun givenGrantedWithEverHeld_whenNotHeld_thenReportsRevoked() {
        // 四參版：stored GRANTED＋曾持有但當前非持有 → 撤銷（GRANTED 覆寫分支
        // 優先於 everHeld 分支，everHeld 不改變此判定）。
        listOf(
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = false),
            AssistantRoleSnapshot(roleAvailable = false, roleHeld = false),
        ).forEach { snapshot ->
            assertEquals(
                "GRANTED+everHeld 非持有必須判 REVOKED（avail=${snapshot.roleAvailable}）",
                AssistantReasonCode.REVOKED,
                effectiveAssistantReason(
                    stored = AssistantReasonCode.GRANTED,
                    snapshot = snapshot,
                    everHeld = true,
                    pendingVerify = false,
                ),
            )
        }
    }

    @Test
    fun givenNullWithEverHeld_whenHeld_thenStaysNull() {
        // 四參版：stored null＋held（含 everHeld sticky）→ 維持 null，
        // 不憑空產生原因碼。
        listOf(
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
            AssistantRoleSnapshot(roleAvailable = false, roleHeld = true),
        ).forEach { snapshot ->
            assertNull(
                "null+held 必須維持 null（avail=${snapshot.roleAvailable}）",
                effectiveAssistantReason(
                    stored = null,
                    snapshot = snapshot,
                    everHeld = true,
                    pendingVerify = false,
                ),
            )
        }
    }

    // ---- Stage 2 B＋C：三態調和矩陣（AssistantSnapshot） ----

    @Test
    fun givenKnownHeld_whenTriReconciled_thenClearsRegardless() {
        // Known held 優先於一切：所有 stored／everHeld／pendingVerify 組合皆清 null。
        listOf(
            AssistantSnapshot.Known(available = true, held = true),
            AssistantSnapshot.Known(available = false, held = true),
        ).forEach { snapshot ->
            listOf(
                null,
                AssistantReasonCode.GRANTED,
                AssistantReasonCode.DENIED,
                AssistantReasonCode.REVOKED,
                AssistantReasonCode.UNSUPPORTED,
                AssistantReasonCode.REQUEST_FAILED,
                AssistantReasonCode.VERIFYING,
            ).forEach { stored ->
                listOf(false, true).forEach { everHeld ->
                    listOf(false, true).forEach { pendingVerify ->
                        assertNull(
                            "Known held 必須清除（$snapshot, stored=$stored, " +
                                "everHeld=$everHeld, pendingVerify=$pendingVerify）",
                            effectiveAssistantReason(stored, snapshot, everHeld, pendingVerify),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun givenKnownNotHeldGranted_whenTriReconciled_thenReportsRevoked() {
        // Known !held＋GRANTED（非驗證中）→ REVOKED；avail 無關。
        listOf(
            AssistantSnapshot.Known(available = true, held = false),
            AssistantSnapshot.Known(available = false, held = false),
        ).forEach { snapshot ->
            assertEquals(
                "Known !held＋GRANTED 必須判 REVOKED（$snapshot）",
                AssistantReasonCode.REVOKED,
                effectiveAssistantReason(
                    stored = AssistantReasonCode.GRANTED,
                    snapshot = snapshot,
                    everHeld = false,
                    pendingVerify = false,
                ),
            )
        }
    }

    @Test
    fun givenKnownNotHeldNullEverHeld_whenTriReconciled_thenReportsRevoked() {
        // Known !held＋null＋everHeld → REVOKED（外部撤銷鏈路）。
        listOf(
            AssistantSnapshot.Known(available = true, held = false),
            AssistantSnapshot.Known(available = false, held = false),
        ).forEach { snapshot ->
            assertEquals(
                "Known !held＋null＋everHeld 必須判 REVOKED（$snapshot）",
                AssistantReasonCode.REVOKED,
                effectiveAssistantReason(
                    stored = null,
                    snapshot = snapshot,
                    everHeld = true,
                    pendingVerify = false,
                ),
            )
        }
    }

    /**
     * 行為變更（re-review #3 finding #2 [P2]，舊名同但鎖定錯誤的 GRANTED passthrough）：
     * ReadError＋GRANTED 不再 passthrough——未知態不可將歷史成功顯示為當前持有，
     * 否則頭部「無法確認」＋原因行「已設為…」矛盾並存；GRANTED 一律遮罩為 null。
     * 其餘碼（DENIED／REVOKED／UNSUPPORTED／REQUEST_FAILED／null／VERIFYING）
     * 仍 passthrough（VERIFYING 保留另見專測）。
     */
    @Test
    fun givenReadError_whenTriReconciled_thenPassesThroughExceptGranted() {
        // ReadError 一律 passthrough（GRANTED 除外且 VERIFYING 另見專測，見上 KDoc）：不判撤銷
        //（null＋everHeld 仍 null）、不宣稱持有、不因驗證憑空造 VERIFYING。
        val storedCases: List<String?> = listOf(
            null,
            AssistantReasonCode.DENIED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REQUEST_FAILED,
        )
        storedCases.forEach { stored ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { pendingVerify ->
                    assertEquals(
                        "ReadError 必須 passthrough（stored=$stored, " +
                            "everHeld=$everHeld, pendingVerify=$pendingVerify）",
                        stored,
                        effectiveAssistantReason(
                            stored,
                            AssistantSnapshot.ReadError,
                            everHeld,
                            pendingVerify,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 行為變更（re-review #3 finding #2 [P2]）：ReadError＋GRANTED（pendingVerify
     * 無關）一律遮罩為 null——未知態不可將歷史成功顯示為當前持有。舊行為
     * （passthrough GRANTED）已廢棄，此測鎖定新語義。
     */
    @Test
    fun givenReadErrorGranted_whenReconciled_thenMasksToNull() {
        // 對照鎖定：ReadError＋GRANTED 在 pendingVerify=true／false 皆回 null
        //（遮罩，非驗證語義；與 Known !held＋GRANTED→REVOKED 對照）。
        listOf(false, true).forEach { pendingVerify ->
            listOf(false, true).forEach { everHeld ->
                assertNull(
                    "ReadError＋GRANTED 必須遮罩為 null " +
                        "(pendingVerify=$pendingVerify, everHeld=$everHeld)",
                    effectiveAssistantReason(
                        AssistantReasonCode.GRANTED,
                        AssistantSnapshot.ReadError,
                        everHeld = everHeld,
                        pendingVerify = pendingVerify,
                    ),
                )
            }
        }
    }

    /**
     * 行為變更（re-review #3 finding #2 [P2]）：Unsupported＋GRANTED 同理遮罩為
     * null——「不支援」頭部＋「已設為…」原因行矛盾並存。舊二態 (false,false)＋
     * GRANTED 判 REVOKED、三態初期 passthrough GRANTED，皆已廢棄。
     */
    @Test
    fun givenUnsupported_whenTriReconciled_thenPassesThroughExceptGranted() {
        // Unsupported：一律 passthrough（GRANTED 除外且 VERIFYING 另見專測，見上 KDoc）。
        // 注意語義變更點：舊二態 (false,false)＋GRANTED 會判 REVOKED；三態下快照
        // 不可讀時不斷言撤銷；re-review #3 #2 後 GRANTED 更進一步遮罩為 null
        //（未知≠持有），而非原樣保留。
        val storedCases: List<String?> = listOf(
            null,
            AssistantReasonCode.DENIED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REQUEST_FAILED,
        )
        storedCases.forEach { stored ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { pendingVerify ->
                    assertEquals(
                        "Unsupported 必須 passthrough（stored=$stored, " +
                            "everHeld=$everHeld, pendingVerify=$pendingVerify）",
                        stored,
                        effectiveAssistantReason(
                            stored,
                            AssistantSnapshot.Unsupported,
                            everHeld,
                            pendingVerify,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 行為變更（re-review #3 finding #1 [P2]，舊名 `…GraceApplies`；再變更
     * re-review #3 finding #2 [P2]：未知態＋GRANTED 由 passthrough 改為遮罩 null）：
     * 驗證中 Known !held 回 VERIFYING（非 GRANTED 斷言）；未知態不憑空造
     * VERIFYING（stored 非 VERIFYING 時不產生它），stored 原樣 passthrough——
     * 唯 GRANTED 例外遮罩為 null（歷史成功≠當前持有）。
     */
    @Test
    fun givenPendingVerify_whenTriReconciled_thenVerifyingMatrix() {
        // - Known !held＋null／GRANTED＋pendingVerify → VERIFYING（驗證中）。
        listOf(null, AssistantReasonCode.GRANTED).forEach { stored ->
            listOf(false, true).forEach { everHeld ->
                assertEquals(
                    "Known !held＋pending 必須回 VERIFYING（stored=$stored, everHeld=$everHeld）",
                    AssistantReasonCode.VERIFYING,
                    effectiveAssistantReason(
                        stored,
                        AssistantSnapshot.Known(available = true, held = false),
                        everHeld = everHeld,
                        pendingVerify = true,
                    ),
                )
            }
        }
        // - 未知態＋pendingVerify → passthrough stored（不憑空造 VERIFYING）。
        assertNull(
            effectiveAssistantReason(
                null,
                AssistantSnapshot.ReadError,
                everHeld = false,
                pendingVerify = true,
            ),
        )
        assertNull(
            effectiveAssistantReason(
                null,
                AssistantSnapshot.Unsupported,
                everHeld = false,
                pendingVerify = true,
            ),
        )
        // ReadError＋everHeld＋null＋pendingVerify 亦不判撤銷（未知≠已撤銷）。
        assertNull(
            effectiveAssistantReason(
                null,
                AssistantSnapshot.ReadError,
                everHeld = true,
                pendingVerify = true,
            ),
        )
        // Unknown＋GRANTED＋pendingVerify → 遮罩 null（re-review #3 finding #2：
        // 未知態不可將歷史成功顯示為當前持有；pendingVerify 無關）。
        assertNull(
            effectiveAssistantReason(
                AssistantReasonCode.GRANTED,
                AssistantSnapshot.ReadError,
                everHeld = false,
                pendingVerify = true,
            ),
        )
        // 新鮮碼＋pendingVerify 照常 passthrough（調和不掩蓋新鮮判定）。
        listOf(
            AssistantReasonCode.DENIED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REQUEST_FAILED,
        ).forEach { code ->
            assertEquals(
                "$code＋pendingVerify 必須 passthrough",
                code,
                effectiveAssistantReason(
                    code,
                    AssistantSnapshot.Known(available = true, held = false),
                    everHeld = false,
                    pendingVerify = true,
                ),
            )
        }
    }

    // ---- re-review #3 finding #2 [P2]：未知態遮罩表專測 ----

    @Test
    fun givenUnknownGranted_whenReconciled_thenMasksToNull() {
        // Unknown＋GRANTED → null（歷史成功≠當前持有；頭部未知／不支援＋
        // 原因行「已設為…」不可並存）。everHeld／pendingVerify 無關。
        listOf(AssistantSnapshot.ReadError, AssistantSnapshot.Unsupported).forEach { snapshot ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { pendingVerify ->
                    assertNull(
                        "Unknown＋GRANTED 必須遮罩為 null " +
                            "($snapshot, everHeld=$everHeld, pendingVerify=$pendingVerify)",
                        effectiveAssistantReason(
                            AssistantReasonCode.GRANTED,
                            snapshot,
                            everHeld,
                            pendingVerify,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 防禦性契約（re-review #3 finding #2 [P2]）：`stored == VERIFYING` 生產
     * 不可達（VERIFYING 僅合成輸出，只進 `state.reasonCode` 不回流 `stored`；
     * AssistantRoleUi 只寫 null／DENIED／GRANTED／UNSUPPORTED／REQUEST_FAILED）。
     * 此測僅鎖定純函數防禦 fallthrough（`else stored` 原樣 passthrough），非生產
     * 路徑。`pending=false` 組合在生產語義下矛盾（VERIFYING 本意味驗證中）故
     * 刪除，只保留 `pending=true` 兩組（ReadError／Unsupported）。
     */
    @Test
    fun givenUnknownVerifyingStored_whenTriReconciled_thenDefensivePassthrough() {
        // 防禦性 passthrough：未知幀不合成 VERIFYING，此處僅因 stored 恰為
        // VERIFYING 而原樣保留；pending 在未知態被忽略（consumePendingVerify
        // 保留旗供重試排程繼續，但不合成 VERIFYING 顯示）。
        listOf(AssistantSnapshot.ReadError, AssistantSnapshot.Unsupported).forEach { snapshot ->
            listOf(false, true).forEach { everHeld ->
                assertEquals(
                    "Unknown＋VERIFYING 防禦性 passthrough " +
                        "($snapshot, everHeld=$everHeld, pendingVerify=true)",
                    AssistantReasonCode.VERIFYING,
                    effectiveAssistantReason(
                        AssistantReasonCode.VERIFYING,
                        snapshot,
                        everHeld,
                        true,
                    ),
                )
            }
        }
    }

    @Test
    fun givenUnknownFutureCode_whenReconciled_thenPassesThrough() {
        // 未知未來碼原樣 passthrough（不偽造語義、不遮罩；與文案未知分支對照）。
        listOf(AssistantSnapshot.ReadError, AssistantSnapshot.Unsupported).forEach { snapshot ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { pendingVerify ->
                    assertEquals(
                        "Unknown＋未來碼必須 passthrough " +
                            "($snapshot, everHeld=$everHeld, pendingVerify=$pendingVerify)",
                        "SOME_FUTURE_CODE",
                        effectiveAssistantReason(
                            "SOME_FUTURE_CODE",
                            snapshot,
                            everHeld,
                            pendingVerify,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun givenUnknownDenied_whenReconciled_thenPassesThrough() {
        // Unknown＋DENIED → DENIED 保留（事件記錄非狀態斷言；未知≠已撤銷）。
        listOf(AssistantSnapshot.ReadError, AssistantSnapshot.Unsupported).forEach { snapshot ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { pendingVerify ->
                    assertEquals(
                        "Unknown＋DENIED 必須 passthrough " +
                            "($snapshot, everHeld=$everHeld, pendingVerify=$pendingVerify)",
                        AssistantReasonCode.DENIED,
                        effectiveAssistantReason(
                            AssistantReasonCode.DENIED,
                            snapshot,
                            everHeld,
                            pendingVerify,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun givenUnknownNull_whenReconciled_thenStaysNull() {
        // Unknown＋null → null（不憑空判撤銷；everHeld 在未知態一律忽略）。
        listOf(AssistantSnapshot.ReadError, AssistantSnapshot.Unsupported).forEach { snapshot ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { pendingVerify ->
                    assertNull(
                        "Unknown＋null 必須維持 null " +
                            "($snapshot, everHeld=$everHeld, pendingVerify=$pendingVerify)",
                        effectiveAssistantReason(
                            null,
                            snapshot,
                            everHeld,
                            pendingVerify,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun givenTriSnapshot_whenDecided_thenMapsCorrectly() {
        // Known 映射與舊版一致；Unsupported／ReadError 皆 fail-closed。
        assertEquals(
            AssistantRoleDecision.AlreadyHeld,
            decideAssistantRole(AssistantSnapshot.Known(available = true, held = true)),
        )
        assertEquals(
            AssistantRoleDecision.AlreadyHeld,
            decideAssistantRole(AssistantSnapshot.Known(available = false, held = true)),
        )
        assertEquals(
            AssistantRoleDecision.RequestRole,
            decideAssistantRole(AssistantSnapshot.Known(available = true, held = false)),
        )
        assertEquals(
            AssistantRoleDecision.UnsupportedFallback,
            decideAssistantRole(AssistantSnapshot.Known(available = false, held = false)),
        )
        assertEquals(
            AssistantRoleDecision.UnsupportedFallback,
            decideAssistantRole(AssistantSnapshot.Unsupported),
        )
        assertEquals(
            AssistantRoleDecision.UnsupportedFallback,
            decideAssistantRole(AssistantSnapshot.ReadError),
        )
    }

    // ---- Stage 2 B（re-review #3 finding #1 語義修正）：驗證結束判定
    // （consumePendingVerify）＋雙時序收斂 ----

    @Test
    fun givenKnown_whenConsumePendingVerify_thenEndsOrRetains() {
        // Known held：確認持有，驗證結束（次數無關）。
        listOf(0, 1, VERIFY_MAX_ATTEMPTS, VERIFY_MAX_ATTEMPTS + 1).forEach { attempt ->
            assertEquals(
                false,
                consumePendingVerify(
                    true,
                    AssistantSnapshot.Known(available = false, held = true),
                    attempt,
                ),
            )
        }
        // Known !held：次數未滿保留（等待排程下一次重讀），達上限超時結束。
        assertEquals(
            true,
            consumePendingVerify(
                true,
                AssistantSnapshot.Known(available = true, held = false),
                0,
            ),
        )
        assertEquals(
            true,
            consumePendingVerify(
                true,
                AssistantSnapshot.Known(available = true, held = false),
                VERIFY_MAX_ATTEMPTS - 1,
            ),
        )
        assertEquals(
            false,
            consumePendingVerify(
                true,
                AssistantSnapshot.Known(available = true, held = false),
                VERIFY_MAX_ATTEMPTS,
            ),
        )
        assertEquals(
            false,
            consumePendingVerify(
                true,
                AssistantSnapshot.Known(available = true, held = false),
                VERIFY_MAX_ATTEMPTS + 5,
            ),
        )
    }

    @Test
    fun givenUnknown_whenConsumePendingVerify_thenRetainsUntilMaxThenEnds() {
        // 未知態次數未滿保留到下一次 Known 讀取（調和在未知態本就 passthrough，
        // 無掩蓋風險；排程照常重試，系統服務恢復後仍可確認）；
        // re-review #4 finding #1 [P2/Merge blocker]：attempt>=MAX 強制終止，
        // 無論快照類型（舊行為無限保留 true，排程 guard 已 false 不再排程，
        // pending 永不清——終端殘留驗證中）。
        listOf(0, VERIFY_MAX_ATTEMPTS - 1).forEach { attempt ->
            assertEquals(
                "未知態次數未滿須保留 (attempt=$attempt)",
                true,
                consumePendingVerify(true, AssistantSnapshot.ReadError, attempt),
            )
            assertEquals(
                "未知態次數未滿須保留 (attempt=$attempt)",
                true,
                consumePendingVerify(true, AssistantSnapshot.Unsupported, attempt),
            )
        }
        listOf(VERIFY_MAX_ATTEMPTS, VERIFY_MAX_ATTEMPTS + 5).forEach { attempt ->
            assertEquals(
                "未知態耗盡須終止 (attempt=$attempt)",
                false,
                consumePendingVerify(true, AssistantSnapshot.ReadError, attempt),
            )
            assertEquals(
                "未知態耗盡須終止 (attempt=$attempt)",
                false,
                consumePendingVerify(true, AssistantSnapshot.Unsupported, attempt),
            )
        }
    }

    @Test
    fun givenNoPending_whenConsumePendingVerify_thenStaysFalse() {
        // 非驗證中無事可做（快照／次數無關）。
        listOf(
            AssistantSnapshot.Known(available = true, held = false),
            AssistantSnapshot.Known(available = true, held = true),
            AssistantSnapshot.ReadError,
            AssistantSnapshot.Unsupported,
        ).forEach { snapshot ->
            listOf(0, VERIFY_MAX_ATTEMPTS).forEach { attempt ->
                assertEquals(false, consumePendingVerify(false, snapshot, attempt))
            }
        }
    }

    /**
     * 雙時序收斂模擬（純函數層；UI 真機時序見缺口註記）。
     *
     * 以 UI 相同協議（回調設 pending=true＋attempt=0＋tick 重讀→VERIFYING→
     * 排程重讀 attempt++→SideEffect 判定結束）走兩種順序：
     * A 收斂到「超時→UNCONFIRMED（re-review #4 finding #1：從未確認持有不再回
     * 中性 idle，原因行提示可重新檢查）」，
     * B 收斂到「held 確認→清除結束」。
     * 真正的 Lifecycle＋重組交錯（Robolectric compose）因 RoleManager 系統服務
     * 難控，未在此覆蓋——缺口見本檔尾 KDoc／PR 說明。
     */
    @Test
    fun givenCallbackAfterResume_whenVerifyProtocol_thenTimeoutConvergesToUnconfirmed() {
        // 順序 A（callback-after-resume）：resume 先重讀（非驗證中），回調後進入驗證。
        var pending = false
        var attempt = 0
        var stored: String? = null
        // resume tick：Known !held，非驗證中 → passthrough null；SideEffect 無事可做。
        var snapshot: AssistantSnapshot =
            AssistantSnapshot.Known(available = true, held = false)
        assertNull(effectiveAssistantReason(stored, snapshot, false, pending))
        pending = consumePendingVerify(pending, snapshot, attempt)
        assertEquals(false, pending)
        // 回調 RESULT_OK：stored=null＋pending=true＋attempt=0，下次重讀幀 VERIFYING。
        stored = null
        pending = true
        attempt = 0
        snapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.VERIFYING,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
        pending = consumePendingVerify(pending, snapshot, attempt)
        assertEquals(true, pending) // 次數未滿保留
        // 排程重讀（attempt++＋tick++）：每幀仍 VERIFYING，直至次數耗盡。
        repeat(VERIFY_MAX_ATTEMPTS) {
            attempt++
            snapshot = AssistantSnapshot.Known(available = true, held = false)
            assertEquals(
                "排程重讀 attempt=$attempt 仍須 VERIFYING",
                AssistantReasonCode.VERIFYING,
                effectiveAssistantReason(stored, snapshot, false, pending),
            )
            pending = consumePendingVerify(pending, snapshot, attempt)
        }
        // attempt==MAX：最後一次 SideEffect 超時終止 → pending=false，同時按
        // verificationTimeoutReason 重寫 stored（!everHeld→UNCONFIRMED）。
        assertEquals(false, pending)
        stored = verificationTimeoutReason(snapshot, everHeld = false)
        assertEquals(AssistantReasonCode.UNCONFIRMED, stored)
        // 次幀按正常規則走：UNCONFIRMED passthrough（原因行提示可重新檢查，
        // 不再回中性 idle——re-review #4 finding #1 誠實性修正）。
        snapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.UNCONFIRMED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
    }

    @Test
    fun givenCallbackBeforeResume_whenHeldObserved_thenConfirmsAndClears() {
        // 順序 B（callback-before-resume）：回調先設旗，ON_RESUME 緊接再重讀
        // （額外新鮮讀取，不計 attempt、不重啟排程）。
        val stored: String? = null
        var pending = true
        val attempt = 0
        // 首幀（回調 tick 即時重讀）：VERIFYING。
        var snapshot: AssistantSnapshot =
            AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.VERIFYING,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
        pending = consumePendingVerify(pending, snapshot, attempt)
        assertEquals(true, pending) // 次數未滿保留
        // resume tick 額外重讀：系統已落盤 → held → 調和清 null＋驗證結束。
        snapshot = AssistantSnapshot.Known(available = true, held = true)
        assertNull(effectiveAssistantReason(stored, snapshot, false, pending))
        pending = consumePendingVerify(pending, snapshot, attempt)
        assertEquals(false, pending)
        // 後續按正常規則：held → null（everHeld 無關）。
        assertNull(effectiveAssistantReason(stored, snapshot, false, pending))
    }

    @Test
    fun givenEverHeld_whenVerifyTimeout_thenReportsRevoked() {
        // 驗證中即使 everHeld＋Known(!held) 亦不判 REVOKED（一律 VERIFYING）；
        // 超時終止（re-review #4 finding #1）：SideEffect 先按
        // verificationTimeoutReason 重寫 stored（everHeld→REVOKED），
        // 再置 pending=false；次幀按正常規則走同樣 REVOKED
        // （曾確認持有、現已非持有＝genuine 撤銷證據）。
        val stored: String? = null
        var pending = true
        val snapshot: AssistantSnapshot =
            AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.VERIFYING,
            effectiveAssistantReason(stored, snapshot, true, pending),
        )
        pending = consumePendingVerify(pending, snapshot, VERIFY_MAX_ATTEMPTS)
        assertEquals(false, pending)
        val terminated = verificationTimeoutReason(snapshot, everHeld = true)
        assertEquals(AssistantReasonCode.REVOKED, terminated)
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(terminated, snapshot, true, pending),
        )
    }

    @Test
    fun givenNeverHeld_whenVerifyTimeout_thenConvergesToUnconfirmed() {
        // 誠實核心（re-review #4 finding #1 [P2/Merge blocker]，取代旧中性 idle）：
        // RESULT_OK 後從未觀察 held、重試耗盡仍 !held → 終止寫入 UNCONFIRMED
        // （要求已送出但未確認，不斷言撤銷、亦不回中性 idle 假裝成功），
        // 次幀 UNCONFIRMED passthrough（原因行提示可重新檢查）；
        // 與 everHeld 路徑（上測 REVOKED）對照鎖定。
        val stored: String? = null
        var pending = true
        var attempt = 0
        val snapshot: AssistantSnapshot =
            AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.VERIFYING,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
        repeat(VERIFY_MAX_ATTEMPTS) {
            attempt++
            pending = consumePendingVerify(pending, snapshot, attempt)
        }
        assertEquals(false, pending)
        val terminated = verificationTimeoutReason(snapshot, everHeld = false)
        assertEquals(AssistantReasonCode.UNCONFIRMED, terminated)
        assertEquals(
            AssistantReasonCode.UNCONFIRMED,
            effectiveAssistantReason(terminated, snapshot, false, pending),
        )
    }

    // ---- re-review #4 finding #1 [P2/Merge blocker]：終止矩陣＋UNCONFIRMED 調和 ----

    @Test
    fun givenTimeoutReason_whenAllSnapshots_thenTerminationMatrix() {
        // 終止矩陣（MAX＋快照／everHeld → 終止寫入碼；由 UI SideEffect 執行，
        // 此處鎖定純函數映射）：
        // - MAX＋Known !held＋everHeld → REVOKED；
        // - MAX＋Known !held＋!everHeld → UNCONFIRMED；
        // - MAX＋ReadError → UNCONFIRMED；
        // - MAX＋Unsupported → UNCONFIRMED；
        // - MAX＋held → null（held 優先已清，生產不會走到終止）。
        assertEquals(
            AssistantReasonCode.REVOKED,
            verificationTimeoutReason(
                AssistantSnapshot.Known(available = true, held = false),
                everHeld = true,
            ),
        )
        assertEquals(
            AssistantReasonCode.REVOKED,
            verificationTimeoutReason(
                AssistantSnapshot.Known(available = false, held = false),
                everHeld = true,
            ),
        )
        assertEquals(
            AssistantReasonCode.UNCONFIRMED,
            verificationTimeoutReason(
                AssistantSnapshot.Known(available = true, held = false),
                everHeld = false,
            ),
        )
        assertEquals(
            AssistantReasonCode.UNCONFIRMED,
            verificationTimeoutReason(
                AssistantSnapshot.Known(available = false, held = false),
                everHeld = false,
            ),
        )
        listOf(false, true).forEach { everHeld ->
            assertEquals(
                "ReadError 超時須 UNCONFIRMED（everHeld=$everHeld）",
                AssistantReasonCode.UNCONFIRMED,
                verificationTimeoutReason(AssistantSnapshot.ReadError, everHeld),
            )
            assertEquals(
                "Unsupported 超時須 UNCONFIRMED（everHeld=$everHeld）",
                AssistantReasonCode.UNCONFIRMED,
                verificationTimeoutReason(AssistantSnapshot.Unsupported, everHeld),
            )
        }
        listOf(false, true).forEach { everHeld ->
            assertNull(
                "held 不走到終止（everHeld=$everHeld）",
                verificationTimeoutReason(
                    AssistantSnapshot.Known(available = true, held = true),
                    everHeld,
                ),
            )
        }
    }

    @Test
    fun givenUnconfirmedStored_whenReconciled_thenPassesThroughExceptHeld() {
        // UNCONFIRMED 調和一律 passthrough（else stored）：
        // Known 非持有非驗證中／未知態皆保留；Known held 仍優先清除為 null。
        val knownNotHeld = AssistantSnapshot.Known(available = true, held = false)
        listOf(false, true).forEach { everHeld ->
            listOf(false, true).forEach { pendingVerify ->
                // 非驗證中必 passthrough；驗證中＋UNCONFIRMED stored 非 null／
                // GRANTED 故同樣 passthrough（VERIFYING 合成僅 stored null／GRANTED）。
                assertEquals(
                    "UNCONFIRMED 須 passthrough（Known !held, " +
                        "everHeld=$everHeld, pendingVerify=$pendingVerify）",
                    AssistantReasonCode.UNCONFIRMED,
                    effectiveAssistantReason(
                        AssistantReasonCode.UNCONFIRMED,
                        knownNotHeld,
                        everHeld,
                        pendingVerify,
                    ),
                )
            }
        }
        listOf(AssistantSnapshot.ReadError, AssistantSnapshot.Unsupported).forEach { snapshot ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { pendingVerify ->
                    assertEquals(
                        "UNCONFIRMED 須 passthrough（$snapshot, " +
                            "everHeld=$everHeld, pendingVerify=$pendingVerify）",
                        AssistantReasonCode.UNCONFIRMED,
                        effectiveAssistantReason(
                            AssistantReasonCode.UNCONFIRMED,
                            snapshot,
                            everHeld,
                            pendingVerify,
                        ),
                    )
                }
            }
        }
        // held 優先：UNCONFIRMED＋held → null（頭部已表達持有，不重複原因行）。
        listOf(false, true).forEach { everHeld ->
            listOf(false, true).forEach { pendingVerify ->
                assertNull(
                    "UNCONFIRMED＋held 必須清除（everHeld=$everHeld, " +
                        "pendingVerify=$pendingVerify）",
                    effectiveAssistantReason(
                        AssistantReasonCode.UNCONFIRMED,
                        AssistantSnapshot.Known(available = true, held = true),
                        everHeld,
                        pendingVerify,
                    ),
                )
            }
        }
    }

    @Test
    fun givenReadErrorRetries_whenExhausted_thenTerminatesToUnconfirmed() {
        // 重試循環觸發終止的模擬（b 問題：3 次皆 ReadError → 不再永久保留 pending）：
        // RESULT_OK 後連續 ReadError，次數未滿保留（排程繼續），耗盡強制終止
        // （pending=false）＋終止寫入 UNCONFIRMED，次幀 passthrough。
        var stored: String? = null
        var pending = true
        var attempt = 0
        val snapshot: AssistantSnapshot = AssistantSnapshot.ReadError
        // 首幀：未知態＋pending 不合成 VERIFYING（passthrough null）。
        assertNull(effectiveAssistantReason(stored, snapshot, false, pending))
        pending = consumePendingVerify(pending, snapshot, attempt)
        assertEquals(true, pending)
        repeat(VERIFY_MAX_ATTEMPTS - 1) {
            attempt++
            assertNull(effectiveAssistantReason(stored, snapshot, false, pending))
            pending = consumePendingVerify(pending, snapshot, attempt)
            assertEquals(true, pending)
        }
        // 最後一次排程重讀耗盡：強制終止。
        attempt++
        assertEquals(VERIFY_MAX_ATTEMPTS, attempt)
        pending = consumePendingVerify(pending, snapshot, attempt)
        assertEquals(false, pending)
        stored = verificationTimeoutReason(snapshot, everHeld = false)
        assertEquals(AssistantReasonCode.UNCONFIRMED, stored)
        assertEquals(
            AssistantReasonCode.UNCONFIRMED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
    }

    // ---- re-review #4 finding #2 [P3/UX]：驗證中 request 防線（純函數） ----

    @Test
    fun givenPendingVerify_whenCanStartRequest_thenReturnsFalse() {
        // 驗證重試跑時 request() 入口純 no-op（UI 另以 state.verifying disabled
        // 主按鈕，此為防禦性第二層，即使競態穿透亦不重開授權頁）。
        assertEquals(false, canStartRequest(true))
    }

    @Test
    fun givenNotPendingVerify_whenCanStartRequest_thenReturnsTrue() {
        assertEquals(true, canStartRequest(false))
    }

    // ---- re-review #5 finding #1 [P2/Merge blocker]：held 觀察幀清除序列 ----

    @Test
    fun givenDeniedThenHeldObserved_whenExternallyRevoked_thenReportsRevoked() {
        // 序列鎖定（純函數層模擬 UI SideEffect 三步）：
        // DENIED（!held passthrough）→ held 觀察（everHeld＋清除→null）
        // → 外部撤銷（!held＋everHeld＋null→REVOKED，而非殘留 DENIED）。
        var stored: String? = AssistantReasonCode.DENIED
        var everHeld = false
        val notHeld = AssistantSnapshot.Known(available = true, held = false)
        // 第 1 幀：無 held 觀察史時 passthrough（舊孤立真值表語義仍對）。
        assertEquals(
            AssistantReasonCode.DENIED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
        // 第 2 幀：觀察到 held（可信確認）→ sticky＋經純函數清除舊事件碼。
        val held = AssistantSnapshot.Known(available = true, held = true)
        if (held.held) everHeld = true
        stored = reconcileStoredOnHeldObserved(stored, heldConfirmed = true)
        assertNull("held 觀察幀必須清除陳舊 DENIED", stored)
        assertNull(effectiveAssistantReason(stored, held, everHeld, false))
        // 第 3 幀：外部撤銷 → REVOKED（不清則殘留 DENIED，此測鎖定修復）。
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
    }

    @Test
    fun givenUnconfirmedThenHeldObserved_whenExternallyRevoked_thenReportsRevoked() {
        // UNCONFIRMED 同理：超時未確認碼亦是舊事件，held 觀察後過期。
        var stored: String? = AssistantReasonCode.UNCONFIRMED
        var everHeld = false
        val notHeld = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.UNCONFIRMED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
        val held = AssistantSnapshot.Known(available = true, held = true)
        if (held.held) everHeld = true
        stored = reconcileStoredOnHeldObserved(stored, heldConfirmed = true)
        assertNull("held 觀察幀必須清除陳舊 UNCONFIRMED", stored)
        assertNull(effectiveAssistantReason(stored, held, everHeld, false))
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
    }

    @Test
    fun givenRequestFailedThenHeldObserved_whenExternallyRevoked_thenReportsRevoked() {
        // REQUEST_FAILED 同理（至少一例）。
        var stored: String? = AssistantReasonCode.REQUEST_FAILED
        var everHeld = false
        val notHeld = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.REQUEST_FAILED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
        val held = AssistantSnapshot.Known(available = true, held = true)
        if (held.held) everHeld = true
        stored = reconcileStoredOnHeldObserved(stored, heldConfirmed = true)
        assertNull("held 觀察幀必須清除陳舊 REQUEST_FAILED", stored)
        assertNull(effectiveAssistantReason(stored, held, everHeld, false))
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
    }

    @Test
    fun givenHeldObservedWithPendingVerify_whenCleared_thenPendingEndsWithoutConflict() {
        // 同幀無矛盾：held 幀清除 stored 同時 consumePendingVerify(pending, held)
        // 恆回 false（次數無關），VERIFYING 不殘留、pending 不殘留。
        var stored: String? = AssistantReasonCode.DENIED
        val everHeld = true // 先前已 sticky（二次 held 觀察亦須清除）。
        val held = AssistantSnapshot.Known(available = true, held = true)
        stored = reconcileStoredOnHeldObserved(stored, heldConfirmed = true)
        assertNull(stored)
        assertEquals(false, consumePendingVerify(true, held, 0))
        assertEquals(false, consumePendingVerify(true, held, VERIFY_MAX_ATTEMPTS))
        assertNull(effectiveAssistantReason(stored, held, everHeld, true))
    }

    @Test
    fun givenFreshDeniedWithEverHeld_whenNotHeld_thenDeniedPreserved() {
        // 「不清」路徑：清除僅發生在 held 觀察幀；新 DENIED 寫入幀 snapshot 非
        // held，故保留——新鮮 DENIED 優先於更舊 everHeld（證明不誤殺新鮮碼）。
        assertEquals(
            AssistantReasonCode.DENIED,
            reconcileStoredOnHeldObserved(AssistantReasonCode.DENIED, heldConfirmed = false),
        )
        assertEquals(
            AssistantReasonCode.DENIED,
            effectiveAssistantReason(
                AssistantReasonCode.DENIED,
                AssistantSnapshot.Known(available = true, held = false),
                everHeld = true,
                pendingVerify = false,
            ),
        )
    }

    @Test
    fun givenUnsupportedOrRequestFailedText_whenRead_thenDoesNotClaimFallbackOpened() {
        // re-review #4 finding #3 [P3/UX]：原因行不預設 fallback 已開啟
        // （祈使非完成式）；fallback 真失敗才由 fallbackFailed 紅字精確表達。
        // 此處鎖定「已改用」完成式不再出現（與紅字「無法開啟語音輸入設定」矛盾）。
        listOf(
            assistantReasonText(AssistantReasonCode.UNSUPPORTED),
            assistantReasonText(AssistantReasonCode.REQUEST_FAILED),
        ).forEach { text ->
            assertTrue(
                "原因行不可預設 fallback 已開啟：$text",
                !text.contains("已改用"),
            )
            assertTrue(
                "原因行須給改用語音輸入設定動作：$text",
                text.contains("請改用語音輸入設定"),
            )
        }
    }

    // ---- re-review #6 finding #3 [P3/UX]：held 觀察幀清除 fallbackFailed ----

    @Test
    fun givenHeldObserved_whenFallbackFailed_thenClearsToFalse() {
        // held 可信確認：舊 fallback 失敗紅字過期（主入口已生效），一律清 false。
        assertEquals(
            false,
            reconcileFallbackFailedOnHeldObserved(true, heldConfirmed = true),
        )
        assertEquals(
            false,
            reconcileFallbackFailedOnHeldObserved(false, heldConfirmed = true),
        )
    }

    @Test
    fun givenNotHeldObserved_whenFallbackFailed_thenPreserves() {
        // 「不清」路徑：清除僅發生在 held 觀察幀；!held 幀原樣保留
        // （與 reconcileStoredOnHeldObserved 對稱）。
        assertEquals(
            true,
            reconcileFallbackFailedOnHeldObserved(true, heldConfirmed = false),
        )
        assertEquals(
            false,
            reconcileFallbackFailedOnHeldObserved(false, heldConfirmed = false),
        )
    }

    @Test
    fun givenDeniedAndFallbackFailedThenHeldObserved_whenCleared_thenNoContradiction() {
        // 組合序列（純函數層模擬 UI SideEffect held 分支雙清除）：
        // DENIED＋fallbackFailed=true（!held passthrough＋保留）→ held 觀察
        // （stored→null＋fallback→false）→ 調和 null＋無紅字，頭部 held 與紅字
        // 不再並存；後續外部撤銷仍判 REVOKED（stored 清除語義不變）。
        var stored: String? = AssistantReasonCode.DENIED
        var fallbackFailed = true
        var everHeld = false
        val notHeld = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.DENIED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
        assertEquals(
            true,
            reconcileFallbackFailedOnHeldObserved(fallbackFailed, heldConfirmed = false),
        )
        val held = AssistantSnapshot.Known(available = true, held = true)
        if (held.held) everHeld = true
        stored = reconcileStoredOnHeldObserved(stored, heldConfirmed = true)
        fallbackFailed =
            reconcileFallbackFailedOnHeldObserved(fallbackFailed, heldConfirmed = true)
        assertNull("held 觀察幀必須清除陳舊 DENIED", stored)
        assertEquals("held 觀察幀必須清除過期 fallbackFailed", false, fallbackFailed)
        assertNull(effectiveAssistantReason(stored, held, everHeld, false))
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(stored, notHeld, everHeld, false),
        )
    }
}
