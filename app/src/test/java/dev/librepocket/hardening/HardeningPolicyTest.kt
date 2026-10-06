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
            classDescriptors = listOf("Ldev/librepocket/agent/github/GithubAccessibilityService;"),
            superclasses = listOf("Landroid/accessibilityservice/AccessibilityService;"),
            services = listOf(".github.GithubAccessibilityService"),
        )
        assertEquals(3, violations.size)
    }

    @Test fun fossClass_flaggedInPlay() {
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = emptyList(),
            classDescriptors = listOf("Ldev/librepocket/agent/foss/FossAccessibilityService;"),
            superclasses = emptyList(),
            services = emptyList(),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().check.contains("class-blacklist"))
    }

    @Test fun privilegeBridge_flaggedInPlay() {
        // S3：提權橋（privilege/github）絕不進 play。
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = emptyList(),
            classDescriptors = listOf("Ldev/librepocket/privilege/github/RootSuRunner;"),
            superclasses = emptyList(),
            services = emptyList(),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().check.contains("class-blacklist"))
        assertTrue(HardeningPolicy.PLAY_CLASS_BLACKLIST.contains("Ldev/librepocket/privilege/github/"))
    }

    @Test fun shizukuTransport_flaggedInPlay() {
        // S3：Shizuku 傳輸層（githubImplementation only）絕不進 play。
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = emptyList(),
            classDescriptors = listOf("Lrikka/shizuku/Shizuku;"),
            superclasses = emptyList(),
            services = emptyList(),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().check.contains("class-blacklist"))
    }

    @Test fun cleanFossArtifact_passes() {
        val violations = HardeningPolicy.checkFossArtifact(
            classDescriptors = listOf(
                "Ldev/librepocket/hardening/HardeningPolicy;",
                "Ldev/librepocket/vision/foss/ZxingBarcodeScanner;",
                "Lcom/google/zxing/MultiFormatReader;",
                "Lcom/googlecode/tesseract/android/TessBaseAPI;",
                "Lorg/tensorflow/lite/Interpreter;",
                "Ldev/librepocket/voice/VoiceTts;",
            ),
        )
        assertTrue(violations.isEmpty())
    }

    @Test fun mlkitReference_flaggedInFoss() {
        val violations = HardeningPolicy.checkFossArtifact(
            classDescriptors = listOf("Lcom/google/mlkit/vision/common/InputImage;"),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().detail.contains("InputImage"))
    }

    @Test fun gmsReference_flaggedInFoss() {
        val violations = HardeningPolicy.checkFossArtifact(
            classDescriptors = listOf("Lcom/google/android/gms/common/api/Status;"),
        )
        assertEquals(1, violations.size)
    }

    @Test fun azureReference_flaggedInFoss() {
        // S2: Azure Speech 僅 github 版；foss dex 引用即違規。
        val violations = HardeningPolicy.checkFossArtifact(
            classDescriptors = listOf("Lcom/microsoft/cognitiveservices/speech/SpeechConfig;"),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().check.contains("foss-string-blacklist"))
    }

    @Test fun fossBlacklist_pinsAzureNeedle() {
        assertTrue(
            HardeningPolicy.FOSS_STRING_BLACKLIST.contains("com.microsoft.cognitiveservices.speech"),
        )
    }

    @Test fun shizukuReference_flaggedInFoss() {
        // S3：Shizuku 僅 github 版；foss dex 引用即違規（dot-form 常量，
        // Dalvik 形匹配，避免自匹配）。
        val violations = HardeningPolicy.checkFossArtifact(
            classDescriptors = listOf("Lrikka/shizuku/Shizuku;"),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().check.contains("foss-string-blacklist"))
        assertTrue(HardeningPolicy.FOSS_STRING_BLACKLIST.contains("rikka.shizuku"))
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

    @Test fun benignServiceNames_pass() {
        // Fail-closed substring gate: names WITHOUT the full
        // "accessibilityservice"/"vpnservice" token pass (precision boundary).
        val violations = HardeningPolicy.checkPlayArtifact(
            permissions = emptyList(),
            classDescriptors = emptyList(),
            superclasses = emptyList(),
            services = listOf(
                "dev.librepocket.agent.VpnHelper",
                "com.example.AccessibilitySettings",
            ),
        )
        assertTrue(violations.isEmpty())
    }

    @Test fun cleartext_neverPermitted() {
        assertEquals(false, HardeningPolicy.CLEARTEXT_PERMITTED)
    }
}
