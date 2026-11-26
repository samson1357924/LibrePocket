package dev.librepocket.privilege

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #136-B4 flavor 邊界 source-level tripwire（純靜態；main＋三 flavor JVM 單測皆跑）。
 *
 * 補既有覆蓋的 source 面缺口：
 * - `ShizukuManifestPinTest`／`ShizukuFlavorIsolationRobolectricTest` 已釘
 *   play/foss merged manifest／classpath 無 ShizukuProvider；本檔補「play/foss
 *   生產原始碼不得出現 github runner／提權橋／Shizuku binder token」。
 * - `ElevatedSoleEntryTest` 已釘 main 源集 sole entry；本檔只限制 play/foss
 *   source set，不改 main／github 的既有接線形狀。
 * - `--expect-provider required` 的 artifact gate 已由
 *   `scripts/tests/test_apk_policy_check.py::GitHubProviderGateWiring` 與
 *   `scripts/check_workflow_policy.py` 釘死；本檔不重複，也不放寬它。
 *
 * 找不到源集根即 fail（非靜默跳過），掃到零檔案亦 fail（避免空轉）。
 */
class ElevatedFlavorBoundaryTest {

    private fun appSrcDir(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        var dir = File(cwd)
        while (true) {
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return File(dir, "app/src")
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return File(dir, "src")
            dir = dir.parentFile ?: break
        }
        throw AssertionError("cannot locate app/src from $cwd (flavor boundary pin is inert)")
    }

    private fun sourceFiles(srcDir: File, flavor: String): List<File> {
        val roots = listOf(File(srcDir, "$flavor/java"), File(srcDir, "$flavor/kotlin"))
        return roots.filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                    .toList()
            }
    }

    @Test fun playAndFossSourcesHaveNoGithubElevatedRunnerReferences() {
        val srcDir = appSrcDir()
        // github runner／grant 橋／Shizuku binder 的 production reference token。
        val forbidden = listOf(
            "RootSuRunner",
            "ShizukuShellRunner",
            "PrivilegeActivation",
            "ElevatedExecPolicy",
            "dev.librepocket.privilege.github",
            "ShizukuProvider",
            "rikka.shizuku",
            "moe.shizuku",
        )
        for (flavor in listOf("play", "foss")) {
            val files = sourceFiles(srcDir, flavor)
            assertTrue("$flavor source scan found zero files (pin inert)", files.isNotEmpty())
            for (file in files) {
                val text = file.readText()
                for (token in forbidden) {
                    assertTrue(
                        "$flavor production source ${file.path} must not reference $token",
                        !text.contains(token),
                    )
                }
            }
        }
    }

    @Test fun githubElevatedSourcesExistSoTheBoundaryScanIsNonVacuous() {
        val srcDir = appSrcDir()
        val githubJava = File(srcDir, "github/java")
        assertTrue("github source set missing: ${githubJava.path}", githubJava.isDirectory)
        for (name in listOf("RootSuRunner.kt", "ShizukuShellRunner.kt", "PrivilegeActivation.kt")) {
            val file = File(githubJava, "dev/librepocket/privilege/github/$name")
            assertTrue("expected github-only elevated source missing: ${file.path}", file.isFile)
        }
    }

    @Test fun playAndFossManifestsHaveNoShizukuPermissionOrProvider() {
        val srcDir = appSrcDir()
        val forbidden = listOf(
            "ShizukuProvider",
            "rikka.shizuku",
            "moe.shizuku",
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            "${'$'}{applicationId}.shizuku",
        )
        for (flavor in listOf("play", "foss")) {
            val manifest = File(srcDir, "$flavor/AndroidManifest.xml")
            assertTrue("missing $flavor manifest: ${manifest.path}", manifest.isFile)
            val text = manifest.readText()
            for (token in forbidden) {
                assertTrue("$flavor manifest must not contain $token", !text.contains(token))
            }
        }
        val github = File(srcDir, "github/AndroidManifest.xml")
        assertTrue("missing github manifest: ${github.path}", github.isFile)
        val githubText = github.readText()
        assertTrue(
            "github must keep the ShizukuProvider permission declaration",
            githubText.contains("android.permission.INTERACT_ACROSS_USERS_FULL"),
        )
        assertTrue("github manifest must keep ShizukuProvider", githubText.contains("ShizukuProvider"))
    }
}
