package dev.librepocket.privilege.github

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
 * 本測試經 `PackageManager.resolveContentProvider` 驗證合併後 manifest
 * 在執行期確實暴露 `rikka.shizuku.ShizukuProvider`
 *（authorities＝`<packageName>.shizuku`，exported＝true，
 * Shizuku 13.x Manager 回傳 binder 合約）。
 *
 * play/foss 缺席由 `ShizukuManifestPinTest` 靜態覆蓋
 *（本測試只跑 github variant，不跨 flavor 斷言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuProviderRobolectricTest {

    @Test fun githubMergedManifestExposesShizukuProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = context.packageName + ".shizuku"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull("github build must expose ShizukuProvider at $authority", provider)
        assertEquals("rikka.shizuku.ShizukuProvider", provider!!.name)
        assertEquals(authority, provider.authority)
        assertTrue("Shizuku 13.x contract requires exported=true", provider.exported)
    }
}
