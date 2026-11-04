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
            "此裝置不支援系統助理角色：已改用語音輸入設定。",
            assistantReasonText(AssistantReasonCode.UNSUPPORTED),
        )
        assertEquals(
            "無法開啟系統授權頁：已改用語音輸入設定。",
            assistantReasonText(AssistantReasonCode.REQUEST_FAILED),
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

    // ---- Stage 2 新增：四參版調和（everHeld＋justGranted） ----

    @Test
    fun givenEverHeldButStoredNull_whenNotHeld_thenReportsRevoked() {
        // 外部授予→撤銷鏈路：stored 雖 null（未經本應用回調），但曾觀察 held 即判撤銷。
        val revoked = effectiveAssistantReason(
            stored = null,
            snapshot = AssistantRoleSnapshot(roleAvailable = true, roleHeld = false),
            everHeld = true,
            justGranted = false,
        )
        assertEquals(AssistantReasonCode.REVOKED, revoked)
    }

    @Test
    fun givenJustGranted_whenNotHeld_thenKeepsGranted() {
        // RESULT_OK 落盤延遲寬限：回調後首幀保持 GRANTED，不立即誤判 REVOKED。
        val kept = effectiveAssistantReason(
            stored = AssistantReasonCode.GRANTED,
            snapshot = AssistantRoleSnapshot(roleAvailable = true, roleHeld = false),
            everHeld = false,
            justGranted = true,
        )
        assertEquals(AssistantReasonCode.GRANTED, kept)
    }

    @Test
    fun givenHeld_whenJustGranted_thenHeldClearsFirst() {
        // held 優先於 justGranted：持有態一律清除原因碼，不殘留 GRANTED。
        listOf(
            AssistantRoleSnapshot(roleAvailable = true, roleHeld = true),
            AssistantRoleSnapshot(roleAvailable = false, roleHeld = true),
        ).forEach { snapshot ->
            assertNull(
                "held+justGranted 必須清除（avail=${snapshot.roleAvailable}）",
                effectiveAssistantReason(
                    stored = AssistantReasonCode.GRANTED,
                    snapshot = snapshot,
                    everHeld = true,
                    justGranted = true,
                ),
            )
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
                justGranted = false,
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
                justGranted = false,
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
                justGranted = false,
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
                justGranted = false,
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
                    justGranted = false,
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
                    justGranted = false,
                ),
            )
        }
    }

    // ---- Stage 2 B＋C：三態調和矩陣（AssistantSnapshot） ----

    @Test
    fun givenKnownHeld_whenTriReconciled_thenClearsRegardless() {
        // Known held 優先於一切：所有 stored／everHeld／justGranted 組合皆清 null。
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
            ).forEach { stored ->
                listOf(false, true).forEach { everHeld ->
                    listOf(false, true).forEach { justGranted ->
                        assertNull(
                            "Known held 必須清除（$snapshot, stored=$stored, " +
                                "everHeld=$everHeld, justGranted=$justGranted）",
                            effectiveAssistantReason(stored, snapshot, everHeld, justGranted),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun givenKnownNotHeldGranted_whenTriReconciled_thenReportsRevoked() {
        // Known !held＋GRANTED（無寬限）→ REVOKED；avail 無關。
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
                    justGranted = false,
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
                    justGranted = false,
                ),
            )
        }
    }

    @Test
    fun givenReadError_whenTriReconciled_thenAlwaysPassesThrough() {
        // ReadError 一律 passthrough：不判撤銷（null＋everHeld 仍 null）、
        // 不宣稱持有、不因寬限憑空造 GRANTED。
        // GRANTED＋justGranted 回 GRANTED 是 passthrough（stored 原樣），
        // 非寬限——同值在 justGranted=false 時亦成立（下測鎖定）。
        val storedCases: List<String?> = listOf(
            null,
            AssistantReasonCode.GRANTED,
            AssistantReasonCode.DENIED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REQUEST_FAILED,
        )
        storedCases.forEach { stored ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { justGranted ->
                    assertEquals(
                        "ReadError 必須 passthrough（stored=$stored, " +
                            "everHeld=$everHeld, justGranted=$justGranted）",
                        stored,
                        effectiveAssistantReason(
                            stored,
                            AssistantSnapshot.ReadError,
                            everHeld,
                            justGranted,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun givenReadErrorGranted_whenGraceOff_thenStillGrantedProvesPassthrough() {
        // 對照鎖定：ReadError＋GRANTED 在 justGranted=false 時同樣回 GRANTED，
        // 證明上測的 GRANTED 來自 passthrough 而非寬限。
        assertEquals(
            AssistantReasonCode.GRANTED,
            effectiveAssistantReason(
                AssistantReasonCode.GRANTED,
                AssistantSnapshot.ReadError,
                everHeld = false,
                justGranted = false,
            ),
        )
    }

    @Test
    fun givenUnsupported_whenTriReconciled_thenAlwaysPassesThrough() {
        // Unsupported 同理：一律 passthrough。注意語義變更點：
        // 舊二態 (false,false)＋GRANTED 會判 REVOKED；三態下快照不可讀時
        // 不斷言撤銷，GRANTED 原樣保留（無快照證據不斷案）。
        val storedCases: List<String?> = listOf(
            null,
            AssistantReasonCode.GRANTED,
            AssistantReasonCode.DENIED,
            AssistantReasonCode.REVOKED,
            AssistantReasonCode.UNSUPPORTED,
            AssistantReasonCode.REQUEST_FAILED,
        )
        storedCases.forEach { stored ->
            listOf(false, true).forEach { everHeld ->
                listOf(false, true).forEach { justGranted ->
                    assertEquals(
                        "Unsupported 必須 passthrough（stored=$stored, " +
                            "everHeld=$everHeld, justGranted=$justGranted）",
                        stored,
                        effectiveAssistantReason(
                            stored,
                            AssistantSnapshot.Unsupported,
                            everHeld,
                            justGranted,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun givenJustGranted_whenOnlyKnown_thenGraceApplies() {
        // 寬限僅 Known 生效：
        // - Known !held＋GRANTED＋justGranted → GRANTED（寬限）。
        // - ReadError／Unsupported＋null＋justGranted → null（不憑空造 GRANTED）。
        assertEquals(
            AssistantReasonCode.GRANTED,
            effectiveAssistantReason(
                AssistantReasonCode.GRANTED,
                AssistantSnapshot.Known(available = true, held = false),
                everHeld = false,
                justGranted = true,
            ),
        )
        assertNull(
            effectiveAssistantReason(
                null,
                AssistantSnapshot.ReadError,
                everHeld = false,
                justGranted = true,
            ),
        )
        assertNull(
            effectiveAssistantReason(
                null,
                AssistantSnapshot.Unsupported,
                everHeld = false,
                justGranted = true,
            ),
        )
        // ReadError＋everHeld＋null＋justGranted 亦不判撤銷（未知≠已撤銷）。
        assertNull(
            effectiveAssistantReason(
                null,
                AssistantSnapshot.ReadError,
                everHeld = true,
                justGranted = true,
            ),
        )
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

    // ---- Stage 2 B：驗證代幣消耗（consumePendingVerify）＋雙時序收斂 ----

    @Test
    fun givenKnown_whenConsumePendingVerify_thenConsumes() {
        assertEquals(
            false,
            consumePendingVerify(
                true,
                AssistantSnapshot.Known(available = true, held = false),
            ),
        )
        assertEquals(
            false,
            consumePendingVerify(
                true,
                AssistantSnapshot.Known(available = false, held = true),
            ),
        )
    }

    @Test
    fun givenUnknown_whenConsumePendingVerify_thenRetains() {
        // 未知態不消耗（保留到下一次 Known 讀取；調和本就忽略寬限，無掩蓋風險）。
        assertEquals(true, consumePendingVerify(true, AssistantSnapshot.ReadError))
        assertEquals(true, consumePendingVerify(true, AssistantSnapshot.Unsupported))
    }

    @Test
    fun givenNoPending_whenConsumePendingVerify_thenStaysFalse() {
        assertEquals(
            false,
            consumePendingVerify(
                false,
                AssistantSnapshot.Known(available = true, held = false),
            ),
        )
        assertEquals(false, consumePendingVerify(false, AssistantSnapshot.ReadError))
        assertEquals(false, consumePendingVerify(false, AssistantSnapshot.Unsupported))
    }

    /**
     * 雙時序收斂模擬（純函數層；UI 真機時序見缺口註記）。
     *
     * 以 UI 相同協議（回調設 pending=true＋重讀→以 true 判定→SideEffect 消耗）
     * 走兩種順序，皆收斂到「寬限恰一幀，次幀 REVOKED」。
     * 真正的 Lifecycle＋重組交錯（Robolectric compose）因 RoleManager 系統服務
     * 難控，未在此覆蓋——缺口見本檔尾 KDoc／PR 說明。
     */
    @Test
    fun givenCallbackAfterResume_whenTokenProtocol_thenGraceOneFrame() {
        // 順序 A（callback-after-resume）：resume 先重讀（無旗），回調後設旗重讀。
        var pending = false
        var stored: String? = null
        // resume tick：Known !held，無旗 → passthrough null。
        var snapshot: AssistantSnapshot =
            AssistantSnapshot.Known(available = true, held = false)
        assertNull(effectiveAssistantReason(stored, snapshot, false, pending))
        pending = consumePendingVerify(pending, snapshot)
        assertEquals(false, pending)
        // 回調 RESULT_OK：stored=GRANTED＋pending=true，下次重讀幀寬限。
        stored = AssistantReasonCode.GRANTED
        pending = true
        snapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.GRANTED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
        pending = consumePendingVerify(pending, snapshot)
        assertEquals(false, pending)
        // 次幀（如下次 resume 重讀）：無旗 → REVOKED，不再常駐 GRANTED。
        snapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
    }

    @Test
    fun givenCallbackBeforeResume_whenTokenProtocol_thenConverges() {
        // 順序 B（callback-before-resume）：回調先設旗，ON_RESUME 緊接再重讀。
        var stored: String? = AssistantReasonCode.GRANTED
        var pending = true
        // 首幀（回調 tick 重讀）：寬限 GRANTED。
        var snapshot: AssistantSnapshot =
            AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.GRANTED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
        // 交錯 B1：SideEffect 已消耗 → 次幀（resume tick）正常判 REVOKED。
        pending = consumePendingVerify(pending, snapshot)
        snapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
        // 交錯 B2：resume 搶在首幀 effect 前（旗仍 true）→ 次幀再寬限一幀，
        // 隨後消耗收斂（至多兩幀，不常駐）。
        pending = true
        snapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.GRANTED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
        pending = consumePendingVerify(pending, snapshot)
        assertEquals(false, pending)
        snapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(stored, snapshot, false, pending),
        )
    }
}
