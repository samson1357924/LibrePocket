package dev.librepocket.hardening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D07 hardening policy mirror test: the same blacklist enforced by
 * `scripts/play_policy_check.sh` on built artifacts.
 *
 * Pure JVM.
 */
class HardeningPolicyTest {

    @Test fun cleanPlayArtifact_passes() {
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = listOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS",
            ),
            classDescriptors = listOf("Ldev/librepocket/hardening/HardeningPolicy;"),
            superclasses = listOf("Ljava/lang/Object;"),
            services = listOf("dev.librepocket.agent.MainActivity"),
        )
        assertTrue(violations.isEmpty())
    }

    @Test fun blacklistedPermission_flagged() {
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = listOf("android.permission.INTERNET", "android.permission.READ_SMS"),
            classDescriptors = emptyList(),
            superclasses = emptyList(),
            services = emptyList(),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().detail.contains("READ_SMS"))
    }

    @Test fun fullClassAndForbiddenSuperclass_flagged() {
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = emptyList(),
            classDescriptors = listOf("Ldev/librepocket/agent/full/FullAccessibilityService;"),
            superclasses = listOf("Landroid/accessibilityservice/AccessibilityService;"),
            services = listOf(".full.FullAccessibilityService"),
        )
        assertEquals(3, violations.size)
    }

    @Test fun vpnSubclass_flagged() {
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = emptyList(),
            classDescriptors = listOf("Lcom/example/MyVpn;"),
            superclasses = listOf("Landroid/net/VpnService;"),
            services = emptyList(),
        )
        assertEquals(1, violations.size)
    }

    @Test fun cleartext_neverPermitted() {
        assertEquals(false, HardeningPolicy.CLEARTEXT_PERMITTED)
    }
}
