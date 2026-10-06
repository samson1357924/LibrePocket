package dev.librepocket.hardening

/**
 * D07 hardening policy (P7, CAPABILITY_MATRIX §2).
 *
 * Pure constants + pure checks, zero Android dependencies so the policy is
 * assertable from plain JVM unit tests AND mirrored by
 * `scripts/play_policy_check.sh` on built artifacts.
 *
 * Locked decisions:
 * - Cleartext is never permitted ([CLEARTEXT_PERMITTED] = false; enforced by
 *   `res/xml/network_security_config.xml`, wired in the manifest).
 * - API keys are never backed up ([BackupPolicy.KEY_FILES_EXCLUDED][dev.librepocket.backup.BackupPolicy]);
 *   a migrated device re-enters keys. Key rotation = [KeyVault.deleteKey] +
 *   [KeyVault.putKey][dev.librepocket.keystore.KeyVault] on the device; there
 *   is no server side to rotate against (BYOK, no account system per P7).
 * - Play flavor never ships SMS / full-storage / VPN / injection code.
 *
 * NOTE on dex needles: they use Dalvik descriptor form (`L...;`), which only
 * ever appears in type references / class definitions — never as a plain
 * word. In particular these constants can never match themselves, so the
 * artifact scanner stays silent on the policy class itself.
 */
object HardeningPolicy {

    /** Cleartext HTTP is forbidden in every flavor. */
    const val CLEARTEXT_PERMITTED = false

    /**
     * Permissions the play artifact must never declare (CAPABILITY_MATRIX §2).
     * `BIND_ACCESSIBILITY_SERVICE` covers the automation-service guard; full-only
     * a11y lives exclusively under `src/full` and is asserted absent from play.
     */
    val PLAY_PERMISSION_BLACKLIST: List<String> = listOf(
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_VPN_SERVICE",
    )

    /**
     * Class-descriptor prefixes that must never be DEFINED in the play dex
     * (full-flavor code, e.g. `Ldev/librepocket/agent/full/FullAccessibilityService;`).
     */
    val PLAY_CLASS_BLACKLIST: List<String> = listOf(
        "Ldev/librepocket/agent/full/",
    )

    /**
     * Superclasses that no play-dex class may extend (a `Superclass` entry of
     * one of these means a forbidden VPN/automation service subclass exists).
     */
    val PLAY_SUPERCLASS_BLACKLIST: List<String> = listOf(
        "Landroid/net/VpnService;",
        "Landroid/accessibilityservice/AccessibilityService;",
    )

    /** Result of [checkPlayArtifact]; empty means compliant. */
    data class Violation(val check: String, val detail: String)

    /**
     * Pure play-compliance check over extracted artifact facts.
     *
     * @param permissions permission names from `aapt dump permissions`.
     * @param classDescriptors `Class descriptor` entries from `dexdump`.
     * @param superclasses `Superclass` entries from `dexdump`.
     * @param services manifest `<service>` android:name values.
     */
    fun checkPlayArtifact(
        permissions: Collection<String>,
        classDescriptors: Collection<String>,
        superclasses: Collection<String>,
        services: Collection<String>,
    ): List<Violation> {
        val out = mutableListOf<Violation>()
        for (denied in PLAY_PERMISSION_BLACKLIST) {
            if (denied in permissions) {
                out += Violation("permission-blacklist", "play artifact declares $denied")
            }
        }
        for (descriptor in classDescriptors) {
            val hit = PLAY_CLASS_BLACKLIST.firstOrNull { descriptor.startsWith(it) }
            if (hit != null) {
                out += Violation("class-blacklist", "play dex defines '$descriptor'")
            }
        }
        for (superclass in superclasses) {
            if (superclass in PLAY_SUPERCLASS_BLACKLIST) {
                out += Violation("superclass-blacklist", "play dex subclasses '$superclass'")
            }
        }
        for (service in services) {
            if ("accessibilityservice" in service.lowercase() ||
                "vpnservice" in service.lowercase()
            ) {
                out += Violation("service-blacklist", "play manifest registers '$service'")
            }
        }
        return out
    }
}
