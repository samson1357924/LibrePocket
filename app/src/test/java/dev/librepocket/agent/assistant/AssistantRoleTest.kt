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
}
