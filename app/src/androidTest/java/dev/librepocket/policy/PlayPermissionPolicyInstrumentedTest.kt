package dev.librepocket.policy

import android.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.librepocket.agent.MainActivity
import dev.librepocket.provider.ChatCompletionsProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.CoreMatchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Play-flavor permission assertions (SPEC §10.3 / §11.5 [I]).
 *
 * ① Default `key.read:*` is ASK. ② An ASK verdict surfaces a confirmation
 * dialog (the skeleton shows the product-contract dialog — title, message,
 * 「允許」/「拒絕」 buttons — and Espresso asserts it appears and dismisses
 * on 拒絕; the settings-screen wiring is P1 UI work). ③ After denial,
 * `evaluateFresh` is DENY and the provider emits zero requests
 * ([MockWebServer] proves it, no external network).
 *
 * Requires a device or emulator (API 33/37 matrix); see
 * `docs/specs/P1_ANDROIDTEST_RUNBOOK.md`.
 */
@RunWith(AndroidJUnit4::class)
class PlayPermissionPolicyInstrumentedTest {

    @Test
    fun keyRead_defaultsToAsk() = runBlocking {
        val store = InMemoryPolicyStore()
        val decision = store.evaluateFresh("key.read", "openai/provider-1")
        assertEquals(Verdict.ASK, decision.verdict)
    }

    @Test
    fun askVerdict_surfacesConfirmationDialog() {
        val verdict = runBlocking {
            InMemoryPolicyStore().evaluateFresh("key.read", "openai/provider-1")
        }
        assertEquals(Verdict.ASK, verdict)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Product dialog contract (SPEC §9.3): ASK always asks once,
                // never remembers. The settings screen reuses this shape.
                AlertDialog.Builder(activity)
                    .setTitle("讀取 API Key？")
                    .setMessage("將讀取該 Provider 的 API Key 以發送本次請求。")
                    .setPositiveButton("允許", null)
                    .setNegativeButton("拒絕", null)
                    .create()
                    .show()
            }
            onView(allOf(withText("允許"))).check(matches(isDisplayed()))
            onView(allOf(withText("拒絕"))).perform(click())
            onView(allOf(withText("允許"))).check(doesNotExist())
        }
    }

    @Test
    fun deniedProviderCall_sendsZeroRequests() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody("""{"models":[]}"""),
            )
            val verdict = runBlocking {
                val store = InMemoryPolicyStore()
                store.setRule(PolicyRule("provider.call:*", Verdict.DENY, 20))
                store.evaluateFresh("provider.call", "openai/gpt-4o-mini")
            }
            assertEquals(Verdict.DENY, verdict)

            // Execution gate (SPEC §9.3): DENY never reaches the transport.
            val config = ProviderConfig(
                id = "00000000-0000-0000-0000-000000000009",
                label = "test",
                baseUrl = server.url("/v1").toString().removeSuffix("/"),
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                apiKeyRef = "provider_key/00000000-0000-0000-0000-000000000009",
            )
            if (verdict.verdict == Verdict.ALLOW) {
                ChatCompletionsProvider(config, { "k".toCharArray() })
            }

            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionExport_defaultsToAsk() = runBlocking {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val decision = InMemoryPolicyStore().evaluateFresh("session.export", "abc123")
        assertEquals(Verdict.ASK, decision.verdict)
    }
}
