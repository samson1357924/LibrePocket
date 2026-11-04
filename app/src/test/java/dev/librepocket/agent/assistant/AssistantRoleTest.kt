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
}
