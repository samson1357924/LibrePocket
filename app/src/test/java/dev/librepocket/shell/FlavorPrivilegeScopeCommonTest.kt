package dev.librepocket.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Common flavor test verifying FlavorPrivilegeScope.withdraw(context)
 * executes cleanly across all flavors (Play, FOSS, GitHub).
 *
 * github 的 withdraw 會觸碰全域單例 ShizukuPermissionRequests（detach）與共用
 * `librepocket_shizuku_grant` DataStore；本測試前後以
 * [resetPrivilegeSingletons] 隔離，避免污染同 JVM 中 github jvm runner 測試
 * （src/testGithub）的進程單例狀態。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlavorPrivilegeScopeCommonTest {

    @Before
    fun setUp() {
        // 先刪檔再重置：即使反射重置抛 AssertionError，檔案清理仍已完成。
        deleteRevokeArtifacts()
        resetPrivilegeSingletons()
    }

    @After
    fun tearDown() {
        deleteRevokeArtifacts()
        resetPrivilegeSingletons()
    }

    /**
     * github 的 withdraw 會以 context-constructor 觸碰共用 DataStore 與撤權標記檔，
     * 在 robolectric filesDir/noBackupFilesDir 留下殘留檔案會跨案例污染同 JVM
     * 的其他測試——setUp／tearDown 盡力刪除（失敗僅吞掉，不遮蔽測試本體結果）。
     * 檔案清單：`datastore/librepocket_shizuku_grant.preferences_pb`（含 .tmp）、
     * `datastore/librepocket_privilege_bridge.preferences_pb`（含 .tmp）、
     * `librepocket_shizuku_revoke.pending`、`librepocket_privilege_revoke.pending`。
     */
    private fun deleteRevokeArtifacts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val noBackup = context.noBackupFilesDir ?: context.filesDir
        val candidates = listOf(
            File(context.filesDir, "datastore/librepocket_shizuku_grant.preferences_pb"),
            File(context.filesDir, "datastore/librepocket_shizuku_grant.preferences_pb.tmp"),
            File(context.filesDir, "datastore/librepocket_privilege_bridge.preferences_pb"),
            File(context.filesDir, "datastore/librepocket_privilege_bridge.preferences_pb.tmp"),
            File(noBackup, "librepocket_shizuku_revoke.pending"),
            File(noBackup, "librepocket_privilege_revoke.pending"),
            File(context.filesDir, "librepocket_shizuku_revoke.pending"),
            File(context.filesDir, "librepocket_privilege_revoke.pending"),
        )
        candidates.forEach { f ->
            runCatching { Files.deleteIfExists(f.toPath()) }
        }
    }

    /**
     * src/test 為三 flavor 共用：play/foss 無 ShizukuPermissionRequests 類，
     * 靜態引用會讓該 flavor 編譯失敗——以反射盡力重置（類不存在即略過）。
     * main 側 [PrivilegeBridgeState] 單例在所有 flavor 皆可見，直接重置。
     */
    private fun resetPrivilegeSingletons() {
        PrivilegeBridgeState.resetProcessForTest()
        try {
            val cls = Class.forName("dev.librepocket.privilege.github.ShizukuPermissionRequests")
            val companion = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            companion.javaClass.getMethod("resetProcessForTest").invoke(companion)
        } catch (_: ClassNotFoundException) {
            // play/foss：無此類，略過。
        } catch (t: Throwable) {
            throw AssertionError("failed to reset ShizukuPermissionRequests singleton", t)
        }
    }

    @Test
    fun withdrawCompletesWithoutException() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FlavorPrivilegeScope.withdraw(context)
        // github：withdraw 會 process() 建立單例並 detach；反射斷言不崩潰＋singleton 可取、
        // attached=false（detach 語義）。play/foss 無此類即略過（src/test 三 flavor 共用）。
        try {
            val cls = Class.forName("dev.librepocket.privilege.github.ShizukuPermissionRequests")
            val companion = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            val instance = companion.javaClass.getMethod("process").invoke(companion)
            assertNotNull("github withdraw 後 ShizukuPermissionRequests singleton 應可取得", instance)
            assertEquals(
                "github withdraw 後 singleton 應已 detach（attached=false）",
                false,
                cls.getMethod("isAttached").invoke(instance),
            )
        } catch (_: ClassNotFoundException) {
            // play/foss：無此類，僅需不崩潰。
        }
    }
}
