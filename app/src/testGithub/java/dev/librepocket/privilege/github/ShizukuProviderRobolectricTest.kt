package dev.librepocket.privilege.github

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B1 ShizukuProvider 裝置級存在性測試（Issue #136 B1，Robolectric）。
 *
 * 靜態 overlay pin（`ShizukuManifestPinTest`）只讀 XML 文本；
 * 本測試經 `PackageManager.resolveContentProvider` 與 `getPackageInfo` 驗證合併後 manifest
 * 在執行期確實僅暴露單一 `rikka.shizuku.ShizukuProvider`
 *（authorities＝`dev.librepocket.agent.github.shizuku`，enabled=true，
 * exported=true，read/writePermission=`android.permission.INTERACT_ACROSS_USERS_FULL`，
 * multiprocess=false，主行程，無 URI grant 覆寫或 pattern，且類名可解析）。
 *
 * play/foss 缺席由 `ShizukuManifestPinTest` 靜態覆蓋與
 * `ShizukuFlavorIsolationRobolectricTest` 執行期跨 flavor 覆蓋。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuProviderRobolectricTest {

    @Test fun githubMergedManifestExposesShizukuProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("dev.librepocket.agent.github", context.packageName)

        val authority = "${context.packageName}.shizuku"
        val packageManager = context.packageManager
        val provider = packageManager.resolveContentProvider(authority, 0)
        assertNotNull("github build must expose ShizukuProvider at $authority", provider)
        provider!!
        assertEquals("rikka.shizuku.ShizukuProvider", provider.name)
        assertEquals("dev.librepocket.agent.github.shizuku", provider.authority)
        assertTrue("Shizuku 13.x contract requires exported=true", provider.exported)
        assertTrue("ShizukuProvider must be enabled=true", provider.enabled)
        assertEquals(
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            provider.readPermission,
        )
        assertEquals(
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            provider.writePermission,
        )
        assertFalse("multiprocess must be false", provider.multiprocess)
        assertEquals(context.packageName, provider.processName)
        assertFalse("grantUriPermissions must be false", provider.grantUriPermissions)
        assertTrue(
            "pathPermissions must be null or empty",
            provider.pathPermissions.isNullOrEmpty(),
        )
        assertTrue(
            "uriPermissionPatterns must be null or empty",
            provider.uriPermissionPatterns.isNullOrEmpty(),
        )

        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
            ),
        )
        val providers = packageInfo.providers.orEmpty()
        assertTrue("github build must expose registered providers", providers.isNotEmpty())
        val shizukuProviders = providers.filter { info ->
            val name = info.name.orEmpty().trim()
            val authorities = info.authority.orEmpty()
                .split(';')
                .map { it.trim() }
            name.contains("ShizukuProvider", ignoreCase = true) ||
                name.contains("rikka.shizuku", ignoreCase = true) ||
                name.contains("moe.shizuku", ignoreCase = true) ||
                authorities.any { it.endsWith(".shizuku", ignoreCase = true) }
        }
        assertEquals(
            "github build must register exactly one Shizuku provider",
            1,
            shizukuProviders.size,
        )
        assertEquals("rikka.shizuku.ShizukuProvider", shizukuProviders.single().name)
        assertEquals(authority, shizukuProviders.single().authority)
        Class.forName(provider.name)
    }
}
