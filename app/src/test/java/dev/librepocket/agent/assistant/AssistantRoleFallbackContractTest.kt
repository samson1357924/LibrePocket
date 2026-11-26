package dev.librepocket.agent.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #137 C1 fallback 契約（純 JVM；合成快照＋source pin，不碰 RoleManager／系統設定）。
 *
 * 既有 AssistantRoleTest／AssistantRoleRobolectricTest 已逐格覆蓋決策表、調和、
 * fallback intent 與 queries；本檔補兩點尚未被 individually 釘住的邊界：
 * 1. 「RoleManager 不可用 → fallback」「曾持有後被撤 → REVOKED、不自動重新取得」
 *    的串接語義（fail-closed，無背景濫用）；
 * 2. production 接線中 `createRequestRoleIntent`／`launcher.launch` 只能出現在
 *    使用者手動觸發的 `request` 閉包，Effect／composition body 不得自行要求角色。
 *
 * 此為 source/JVM contract，非真機授予／撤銷／OEM 行為的驗證。
 */
class AssistantRoleFallbackContractTest {

    @Test
    fun unavailableOrRevokedRole_fallsBackFailClosedWithoutAutoAcquisition() {
        // RoleManager 缺失（Unsupported）／讀取失敗（ReadError）／有服務但角色不可用：
        // 三者皆不進入 RequestRole，只走 fallback 決策。
        val roleUnavailableSnapshots = listOf(
            AssistantSnapshot.Unsupported,
            AssistantSnapshot.ReadError,
            AssistantSnapshot.Known(available = false, held = false),
        )
        for (snapshot in roleUnavailableSnapshots) {
            assertEquals(
                "未知／不可用快照不得自動要求角色：$snapshot",
                AssistantRoleDecision.UnsupportedFallback,
                decideAssistantRole(snapshot),
            )
        }
        for (snapshot in listOf(AssistantSnapshot.Unsupported, AssistantSnapshot.ReadError)) {
            assertNull(
                "未知態不得宣稱已持有：$snapshot",
                effectiveAssistantReason(null, snapshot, everHeld = true, pendingVerify = false),
            )
        }

        // 角色被外部撤銷：曾觀察 held + 新鮮 !held → REVOKED（不是 GRANTED、不是中性 idle）。
        val revokedSnapshot = AssistantSnapshot.Known(available = true, held = false)
        assertEquals(
            AssistantReasonCode.REVOKED,
            effectiveAssistantReason(null, revokedSnapshot, everHeld = true, pendingVerify = false),
        )
        assertEquals(
            AssistantReasonCode.REVOKED,
            verificationTimeoutReason(revokedSnapshot, everHeld = true),
        )
        // 撤銷後角色若同時不可用，決策仍是 fallback（永不背景重新取得角色）。
        assertEquals(
            AssistantRoleDecision.UnsupportedFallback,
            decideAssistantRole(AssistantSnapshot.Known(available = false, held = false)),
        )
        // 只有使用者手勢可發起新要求；驗證中 request 入口一律 no-op。
        assertTrue(canStartRequest(false))
        assertFalse(canStartRequest(true))
    }

    @Test
    fun roleRequest_isOnlyReachableFromUserGesture_noBackgroundLaunch() {
        // 只讀程式碼：KDoc 大量提及 request()／createRequestRoleIntent，註解不應讓 pin 誤判。
        val raw = File(
            appSrcDir(),
            "main/java/dev/librepocket/agent/assistant/AssistantRoleUi.kt",
        ).readText()
        val source = raw
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")

        // 錨點必須是 production 的 request 閉包，不是 data class 參數 `val request: () -> Unit,`。
        val requestAnchor = "val request: () -> Unit = remember("
        val requestStart = source.indexOf(requestAnchor)
        assertTrue("must locate production request() closure", requestStart >= 0)
        assertEquals(
            "request 閉包錨點必須唯一（data class 參數不算）",
            requestStart,
            source.lastIndexOf(requestAnchor),
        )
        val stateStart = source.indexOf("val state =", requestStart)
        assertTrue("must locate state after request() closure", stateStart > requestStart)

        // request 閉包之前＝composition body＋SideEffect／LaunchedEffect／launcher 建立，
        // 全部不得自行建立角色授權 Intent、launch 授權頁或直接呼叫 request()。
        val beforeRequest = source.substring(0, requestStart)
        assertFalse(
            "composition/effect 不得自行建立角色授權 Intent（只能由使用者手勢觸發 request）",
            beforeRequest.contains("createRequestRoleIntent"),
        )
        assertFalse(
            "composition/effect 不得自行 launch 角色授權頁",
            beforeRequest.contains("launcher.launch"),
        )
        assertFalse(
            "composition/effect 不得自動呼叫 request()",
            beforeRequest.contains("request()"),
        )

        val requestBody = source.substring(requestStart, stateStart)
        assertTrue("request() 須保留 RoleManager 要求入口", requestBody.contains("createRequestRoleIntent"))
        assertTrue("request() 須經 launcher 顯示系統授權頁", requestBody.contains("launcher.launch"))
        assertTrue(
            "request() 入口須由 canStartRequest 防重入／背景濫用",
            requestBody.contains("canStartRequest(pendingVerify)"),
        )

        // 全檔精確計數＋位置：這兩個副作用只能各出現一次，且都在 request 閉包內。
        assertEquals(1, count(source, "createRequestRoleIntent"))
        assertEquals(1, count(source, "launcher.launch"))
        for (token in listOf("createRequestRoleIntent", "launcher.launch")) {
            val index = source.indexOf(token)
            assertTrue(
                "$token 必須位於 request 閉包內（index=$index，body=[$requestStart, $stateStart)）",
                index >= requestStart && index < stateStart,
            )
        }
        assertFalse(
            "val state 之後不得再有 launcher.launch",
            source.substring(stateStart).contains("launcher.launch"),
        )
    }

    private fun count(haystack: String, needle: String): Int {
        var total = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            total++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return total
    }

    private fun appSrcDir(): File {
        System.getProperty("test.src.dir")?.let { return File(it) }
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return File(dir, "src")
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return File(dir, "app/src")
            dir = dir.parentFile ?: break
        }
        error("cannot locate app/src from ${System.getProperty("user.dir")}")
    }
}
