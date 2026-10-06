package dev.librepocket.systemb

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-C 全量聯繫人測試：search/list/get（READ，需 READ_CONTACTS +
 * 開關 `contacts_full` 預設 false）；play 全量 FLAVOR_BLOCKED，
 * foss/github 需授權；保留 pick 委託免權路徑。
 */
class ContactFullTest {

    private class RecordingSink : PermissionSink {
        val requests = mutableListOf<String>()
        override fun request(permission: String) {
            requests += permission
        }
    }

    private fun env(sink: RecordingSink, block: SystemBEnv.() -> SystemBEnv = { this }): SystemBEnv =
        SystemBEnv(permissionSink = sink).block()

    private fun grantedFoss(sink: RecordingSink): SystemBEnv =
        env(sink) { copy(flavor = Flavor.FOSS, contactsReadGranted = true) }

    // ---- 註冊 ----

    @Test fun registry_fullContactsAreReadWithSwitchOffByDefault() {
        for (name in listOf("contact.search", "contact.list", "contact.get")) {
            val tool = ToolRegistry.find(name)!!
            assertEquals(name, SideEffect.READ, tool.sideEffect)
            assertEquals(name, "contacts_full", tool.annotations.requiresSwitch)
            assertEquals(name, false, tool.annotations.switchDefault)
            assertEquals(name, "android.permission.READ_CONTACTS", tool.annotations.requiresPermission)
        }
    }

    @Test fun registry_fullContactsHiddenOnPlay() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf("contacts_full" to true),
            grantedPermissions = setOf("android.permission.READ_CONTACTS"),
        )
        for (name in listOf("contact.search", "contact.list", "contact.get")) {
            val p = ToolRegistry.projectAll(ctx)[name]!!
            assertEquals(name, CapabilityLevel.UNAVAILABLE, p.level)
            assertEquals(name, DenyReason.FLAVOR_BLOCKED, p.reason)
        }
    }

    @Test fun registry_fullContactsNeedSwitchAndGrantOnSelfInstall() {
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            // 開關關（預設）即 USER_DISABLED。
            val off = ToolRegistry.projectAll(ProjectionContext(flavor = flavor))["contact.search"]!!
            assertEquals(flavor.name, CapabilityLevel.UNAVAILABLE, off.level)
            assertEquals(flavor.name, DenyReason.USER_DISABLED, off.reason)
            // 開關開但無授權即 NO_PRIVILEGE。
            val noGrant = ToolRegistry.projectAll(
                ProjectionContext(flavor = flavor, userSwitches = mapOf("contacts_full" to true)),
            )["contact.search"]!!
            assertEquals(flavor.name, CapabilityLevel.UNAVAILABLE, noGrant.level)
            assertEquals(flavor.name, DenyReason.NO_PRIVILEGE, noGrant.reason)
            // 雙開即 NATIVE。
            val on = ToolRegistry.projectAll(
                ProjectionContext(
                    flavor = flavor,
                    userSwitches = mapOf("contacts_full" to true),
                    grantedPermissions = setOf("android.permission.READ_CONTACTS"),
                ),
            )["contact.search"]!!
            assertEquals(flavor.name, CapabilityLevel.NATIVE, on.level)
        }
    }

    // ---- 執行：play 擋風味 ----

    @Test fun playFullBlockedWithZeroRequest() {
        val sink = RecordingSink()
        val play = env(sink) { copy(flavor = Flavor.PLAY, contactsReadGranted = true) }
        for ((tool, args) in listOf(
            ContactSearchTool to mapOf("query" to "王"),
            ContactListTool to emptyMap(),
            ContactGetTool to mapOf("contactId" to "1"),
        )) {
            val c = tool.check(play, args)
            assertEquals(tool.name, Availability.UNAVAILABLE, c.availability)
            assertEquals(tool.name, ReasonCode.FLAVOR_BLOCKED, c.reason)
            val e = tool.execute(play, args)
            assertFalse(tool.name, e.started)
            assertNull(tool.name, e.intent)
            val f = tool.fallback(c.reason, args)
            assertTrue(f.userMessage.contains("FLAVOR_BLOCKED"))
        }
        assertTrue(sink.requests.isEmpty())
        // SystemB 註冊表含全量三工具 + 既有 pick。
        for (name in listOf("systemb.contact.search", "systemb.contact.list", "systemb.contact.get")) {
            assertNotNull(name, SystemBRegistry.get(name))
        }
    }

    // ---- 執行：foss/github 缺授權擋 NO_PRIVILEGE ----

    @Test fun selfInstallWithoutGrantDeniedWithZeroRequest() {
        val sink = RecordingSink()
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val e = env(sink) { copy(flavor = flavor, contactsReadGranted = false) }
            for ((tool, args) in listOf(
                ContactSearchTool to mapOf("query" to "王"),
                ContactListTool to emptyMap(),
                ContactGetTool to mapOf("contactId" to "1"),
            )) {
                val c = tool.check(e, args)
                assertEquals("${tool.name}/$flavor", Availability.UNAVAILABLE, c.availability)
                assertEquals("${tool.name}/$flavor", ReasonCode.NO_PRIVILEGE, c.reason)
                val r = tool.execute(e, args)
                assertFalse(r.started)
                assertNull(r.intent)
            }
        }
        assertTrue(sink.requests.isEmpty())
    }

    // ---- 執行：授權後可用 ----

    @Test fun search_withGrant_describesQuery() {
        val sink = RecordingSink()
        val e = grantedFoss(sink)
        val c = ContactSearchTool.check(e, mapOf("query" to "王小明"))
        assertEquals(Availability.NATIVE, c.availability)
        val r = ContactSearchTool.execute(e, mapOf("query" to "王小明", "limit" to "10"))
        assertTrue(r.started)
        assertEquals(ContactSearchTool.ACTION, r.intent!!.action)
        assertEquals("王小明", r.intent!!.extras["query"])
        assertEquals("10", r.intent!!.extras["limit"])
        assertTrue(sink.requests.isEmpty())
    }

    @Test fun search_missingQuery_denied() {
        val sink = RecordingSink()
        val c = ContactSearchTool.check(grantedFoss(sink), emptyMap())
        assertEquals(ReasonCode.MISSING_ARG, c.reason)
        val r = ContactSearchTool.execute(grantedFoss(sink), emptyMap())
        assertFalse(r.started)
        assertNull(r.intent)
    }

    @Test fun list_withGrant_needsNoArgs() {
        val sink = RecordingSink()
        val r = ContactListTool.execute(grantedFoss(sink), emptyMap())
        assertTrue(r.started)
        assertEquals(ContactListTool.ACTION, r.intent!!.action)
        assertTrue(sink.requests.isEmpty())
    }

    @Test fun get_withGrant_describesId() {
        val sink = RecordingSink()
        val r = ContactGetTool.execute(grantedFoss(sink), mapOf("contactId" to "42"))
        assertTrue(r.started)
        assertEquals("42", r.intent!!.extras["contactId"])
        val missing = ContactGetTool.execute(grantedFoss(sink), emptyMap())
        assertFalse(missing.started)
        assertNull(missing.intent)
    }

    // ---- 保留 pick 委託免權路徑 ----

    @Test fun pickDelegationStaysPermissionFree() {
        val sink = RecordingSink()
        // play、無任何授權，pick 仍 NATIVE 可用。
        val e = env(sink) { copy(flavor = Flavor.PLAY, contactsReadGranted = false) }
        val c = ContactTool.check(e, emptyMap())
        assertEquals(Availability.NATIVE, c.availability)
        val r = ContactTool.execute(e, emptyMap())
        assertTrue(r.started)
        assertEquals("android.intent.action.PICK", r.intent!!.action)
        assertTrue(sink.requests.isEmpty())
        assertTrue(sink.requests.none { it.contains("READ_CONTACTS") })
    }
}
