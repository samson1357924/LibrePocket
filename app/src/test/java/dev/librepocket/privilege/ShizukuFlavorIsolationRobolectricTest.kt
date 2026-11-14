package dev.librepocket.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Merged-manifest & classpath isolation test for ShizukuProvider across all flavors.
 *
 * Runs in `app/src/test` under every flavor (`play`, `foss`, `github`):
 * - For non-`github` flavors (`play` = `dev.librepocket.agent`, `foss` =
 *   `dev.librepocket.agent.foss`), `<packageName>.shizuku` must resolve to `null`,
 *   registered providers must be non-empty (non-vacuous check) with no provider
 *   (including disabled components) referencing `ShizukuProvider` / `rikka.shizuku` /
 *   `moe.shizuku` or a `.shizuku` authority, and Shizuku API/provider classes must
 *   not exist on the classpath.
 * - For the `github` flavor (`dev.librepocket.agent.github`), `<packageName>.shizuku`
 *   must resolve to the sole registered Shizuku provider (`rikka.shizuku.ShizukuProvider`)
 *   protected by `android.permission.INTERACT_ACROSS_USERS_FULL` with no URI grant
 *   overrides or patterns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuFlavorIsolationRobolectricTest {

    private fun isShizukuProvider(info: ProviderInfo): Boolean {
        val name = info.name.orEmpty().trim()
        val authorities = info.authority.orEmpty()
            .split(';')
            .map { it.trim() }
        return name.contains("ShizukuProvider", ignoreCase = true) ||
            name.contains("rikka.shizuku", ignoreCase = true) ||
            name.contains("moe.shizuku", ignoreCase = true) ||
            authorities.any { it.endsWith(".shizuku", ignoreCase = true) }
    }

    @Test fun shizukuProvider_isolatedByFlavor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val authority = "${context.packageName}.shizuku"
        val provider = packageManager.resolveContentProvider(authority, 0)
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
            ),
        )
        val providers = packageInfo.providers.orEmpty()

        if (context.packageName != GITHUB_PACKAGE_NAME) {
            assertTrue(
                "unexpected non-github packageName: ${context.packageName}",
                context.packageName == PLAY_PACKAGE_NAME || context.packageName == FOSS_PACKAGE_NAME,
            )
            assertNull(
                "non-github flavor (${context.packageName}) must not expose ShizukuProvider at $authority",
                provider,
            )
            assertTrue(
                "non-github flavor (${context.packageName}) expected registered providers so none() is non-vacuous",
                providers.isNotEmpty(),
            )
            assertTrue(
                "non-github flavor (${context.packageName}) must not register any Shizuku provider",
                providers.none(::isShizukuProvider),
            )
            for (className in FORBIDDEN_NON_GITHUB_CLASSES) {
                assertThrows(
                    "non-github flavor (${context.packageName}) must not include $className on classpath",
                    ClassNotFoundException::class.java,
                ) {
                    Class.forName(className)
                }
            }
        } else {
            assertNotNull("github build must expose ShizukuProvider at $authority", provider)
            provider!!
            assertTrue(
                "github build expected registered providers",
                providers.isNotEmpty(),
            )
            val shizukuProviders = providers.filter(::isShizukuProvider)
            assertEquals(
                "github flavor must register exactly one Shizuku provider",
                1,
                shizukuProviders.size,
            )
            assertEquals(SHIZUKU_PROVIDER_CLASS, shizukuProviders.single().name)
            assertEquals(authority, shizukuProviders.single().authority)
            assertEquals(SHIZUKU_PROVIDER_CLASS, provider.name)
            assertEquals(authority, provider.authority)
            assertTrue(provider.enabled)
            assertTrue(provider.exported)
            assertFalse(provider.multiprocess)
            assertEquals(INTERACT_ACROSS_USERS_FULL, provider.readPermission)
            assertEquals(INTERACT_ACROSS_USERS_FULL, provider.writePermission)
            assertFalse(
                "ShizukuProvider must not set grantUriPermissions",
                provider.grantUriPermissions,
            )
            assertTrue(
                "ShizukuProvider must not declare pathPermissions",
                provider.pathPermissions.isNullOrEmpty(),
            )
            assertTrue(
                "ShizukuProvider must not declare uriPermissionPatterns",
                provider.uriPermissionPatterns.isNullOrEmpty(),
            )
        }
    }

    private companion object {
        const val PLAY_PACKAGE_NAME = "dev.librepocket.agent"
        const val FOSS_PACKAGE_NAME = "dev.librepocket.agent.foss"
        const val GITHUB_PACKAGE_NAME = "dev.librepocket.agent.github"
        const val SHIZUKU_PROVIDER_CLASS = "rikka.shizuku.ShizukuProvider"
        val FORBIDDEN_NON_GITHUB_CLASSES = listOf(
            SHIZUKU_PROVIDER_CLASS,
            "rikka.shizuku.Shizuku",
            "moe.shizuku.api.BinderContainer",
        )
        const val INTERACT_ACROSS_USERS_FULL = "android.permission.INTERACT_ACROSS_USERS_FULL"
    }
}
