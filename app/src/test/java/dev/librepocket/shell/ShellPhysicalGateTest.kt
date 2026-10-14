package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * B1 實體解析門 regression（RED 未在版控留痕；原 9 件路徑門案〈不含 spawn 重驗〉中
 * 8 件拒絕案在無 B1+F1 時理論 RED；新增案中僅中段鏈／毒根／尾斜線鏈／`..`／`=` 後綴同門類具差分，
 * 其餘為放行守衛。37a45aa 訊息所稱 RED 4 pre-fix 是對實際 parent 的本地觀測，
 * 與本理論基線不同，見 PR #80 審查記錄）：
 * - 詞法域內但實體指到域外的 symlink 操作數必須拒絕（直接/提權/guest host-tree 同門）。
 * - F1：原始含 `..` 段即 fail-closed 拒（不先詞法折疊；spawn 用原始操作數故驗側嚴於用側）。
 * - spawn 前重驗：解析結果在驗證與 spawn 間被置換必須拒且零 spawn。
 * - hardlink 不測（僅 KDoc 揭露：本門不解 hardlink，同域 hardlink 指域外不在承諾內）。
 */
class ShellPhysicalGateTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test fun symlinkOutsideDomain_deniedDirect() {
        val scope = tempDir("b1-scope")
        val outside = tempDir("b1-outside")
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            val v = ShellPolicy.validate(
                listOf("cat", link.toString()),
                scope.absolutePath,
            )
            assertTrue("symlink escape must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue(
                "message must distinguish symlink escape, got ${v.message}",
                v.message.contains("symlink") || v.message.contains("cross-domain"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun symlinkOutsideDomain_deniedElevatedEvenWithBridge() {
        val scope = tempDir("b1-elev-scope")
        val outside = tempDir("b1-elev-outside")
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            val v = ShellPolicy.validateElevated(
                listOf("cat", link.toString()),
                scope.absolutePath,
                flavor = Flavor.GITHUB,
                bridgeGranted = true,
            )
            assertTrue("elevated symlink escape must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue(
                "message must distinguish symlink escape, got ${v.message}",
                v.message.contains("symlink") || v.message.contains("cross-domain"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun symlinkOutsideDomain_deniedGuestHostTree() {
        val scope = tempDir("b1-guest-scope")
        val outside = tempDir("b1-guest-outside")
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            // guest host-tree 路徑（privateRoot 內詞法）同樣走實體門，不因 guest 放行。
            val v = ShellPolicy.validate(
                listOf("cat", link.toString()),
                scope.absolutePath,
                isGuest = true,
            )
            assertTrue("guest host-tree symlink escape must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue(
                "message must distinguish symlink escape, got ${v.message}",
                v.message.contains("symlink") || v.message.contains("cross-domain"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dotdotViaSymlinkedDir_deniedDirect() {
        // F1 反例：/scope/sub/../escape，sub→域外。詞法折疊後為 /scope/escape（域內），
        // 但 spawn 用原始操作數，核心先解 sub→域外再回 ..，實為域外，故必須拒。
        val scope = tempDir("b1-dotdot-scope")
        val outside = tempDir("b1-dotdot-outside")
        try {
            Files.createSymbolicLink(File(scope, "sub").toPath(), outside.toPath())
            val evil = scope.absolutePath + "/sub/../escape.txt"
            val v = ShellPolicy.validate(
                listOf("cat", evil),
                scope.absolutePath,
            )
            assertTrue("dotdot via symlinked dir must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun linkSlashDotDot_deniedDirect() {
        // F1 反例：/scope/link/..，link→域外目錄。詞法折疊後為 /scope（域內），
        // 但實為域外父目錄，故必須拒。
        val scope = tempDir("b1-linkdotdot-scope")
        val outside = tempDir("b1-linkdotdot-outside")
        try {
            Files.createSymbolicLink(File(scope, "link").toPath(), outside.toPath())
            val evil = scope.absolutePath + "/link/.."
            val v = ShellPolicy.validate(
                listOf("ls", evil),
                scope.absolutePath,
            )
            assertTrue("link/.. must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun plainDotdotInsideStillDenied() {
        // F1 正策反例：即使無 symlink、折疊後仍在域內，原始含 `..` 一律拒（驗側嚴於用側）。
        val scope = tempDir("b1-plaindotdot-scope")
        try {
            File(scope, "a").mkdirs()
            val evil = scope.absolutePath + "/a/../b.txt"
            val v = ShellPolicy.validate(
                listOf("cat", evil),
                scope.absolutePath,
            )
            assertTrue("plain dotdot must be denied fail-closed, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun normalInsideFile_allowedDirect() {
        // F1 正例：無 `..`、無 symlink 的域內實檔維持放行（不誤殺）。
        val scope = tempDir("b1-normal-scope")
        try {
            val inner = File(scope, "inner.txt").apply { writeText("ok") }
            val v = ShellPolicy.validate(
                listOf("cat", inner.absolutePath),
                scope.absolutePath,
            )
            assertTrue("normal inside file must be allowed, got $v", v is Validation.Allowed)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun dotdotViaSymlinkedDir_deniedElevated() {
        // F1 提權同門：即使橋接已授權，`..` 仍先拒（提權不放寬詞法）。
        val scope = tempDir("b1-dotdot-elev-scope")
        val outside = tempDir("b1-dotdot-elev-outside")
        try {
            Files.createSymbolicLink(File(scope, "sub").toPath(), outside.toPath())
            val evil = scope.absolutePath + "/sub/../escape.txt"
            val v = ShellPolicy.validateElevated(
                listOf("cat", evil),
                scope.absolutePath,
                flavor = Flavor.GITHUB,
                bridgeGranted = true,
            )
            assertTrue("elevated dotdot must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun danglingLink_deniedDirectAndElevated() {
        // 懸空連結回歸鎖定（非 exists 行差分證明）：單線程下步驟1逐段 lstat 已對懸空
        // final-link 回 true 即拒（symlink denied），根本到不了 exists 行；修前 following
        // exists 回 false 亦同樣被步驟1拒。exists NOFOLLOW 僅收斂步驟1→exists→toRealPath
        // 競態窗口（窗口內換入懸空鏈時 following 回 false 誤放行，NOFOLLOW 回 true 進
        // toRealPath 拒），屬縱深。fixture 用 canonicalFile（防 macOS /var→/private/var 詞法誤殺）。
        val scope = tempDir("b1-dangling-scope").canonicalFile
        try {
            val missing = File(scope, "no-such-target.txt")
            val dangling = File(scope, "dangling.txt").toPath()
            Files.createSymbolicLink(dangling, missing.toPath())
            val direct = ShellPolicy.validate(
                listOf("cat", dangling.toString()),
                scope.absolutePath,
            )
            assertTrue("dangling link must be denied, got $direct", direct is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (direct as Validation.Denied).reason)
            assertTrue(
                "message must mention symlink, got ${direct.message}",
                direct.message.contains("symlink"),
            )
            val elevated = ShellPolicy.validateElevated(
                listOf("cat", dangling.toString()),
                scope.absolutePath,
                flavor = Flavor.GITHUB,
                bridgeGranted = true,
            )
            assertTrue("elevated dangling link must be denied, got $elevated", elevated is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (elevated as Validation.Denied).reason)
            assertTrue(
                "message must mention symlink, got ${elevated.message}",
                elevated.message.contains("symlink"),
            )
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun spawnReverify_mismatchDeniedWithoutSpawn() {
        val scope = tempDir("b1-reverify-scope")
        try {
            var calls = 0
            val runner = object : ProcessRunner {
                var spawnCalls = 0
                override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                    spawnCalls++
                    return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
                }
            }
            // 首次解析回 A，spawn 前重驗回 B（模擬驗過→spawn 窗口置換）：必須拒且零 spawn。
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = {
                    calls++
                    if (calls == 1) ShellExecutables.ResolvedExec("/trusted/A", null)
                    else ShellExecutables.ResolvedExec("/trusted/B", null)
                },
            )
            val r = shell.execute(listOf("echo", "hi"))
            assertTrue("reverify mismatch must be denied, got $r", r is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertEquals(0, runner.spawnCalls)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun symlinkChain_middleSegment_denied() {
        // 中段鏈：scope/a 真目錄 + a/b symlink→域外，候選 scope/a/b/c.txt 必須拒（逐段 lstat 擋中段）。
        // fixture 用 canonicalFile（防 macOS /var→/private/var 詞法誤殺）。
        assumeTrue(!System.getProperty("os.name", "").startsWith("Windows"))
        val scope = tempDir("b1-mid-scope").canonicalFile
        val outside = tempDir("b1-mid-outside").canonicalFile
        try {
            val a = File(scope, "a").apply { mkdirs() }
            Files.createSymbolicLink(File(a, "b").toPath(), outside.toPath())
            val evil = File(a, "b/c.txt").absolutePath
            val v = ShellPolicy.validate(
                listOf("cat", evil),
                scope.absolutePath,
            )
            assertTrue("middle-segment symlink must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue(
                "message must distinguish symlink escape, got ${v.message}",
                v.message.contains("symlink") || v.message.contains("cross-domain"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun symlinkChain_privateRootItselfSymlinked_denied() {
        // 毒根：real 真目錄 + alias symlink→real，以 alias 為 privateRoot，
        // 候選 alias/f.txt（f.txt 建在 real 內）必須拒（根鏈別名讓詞法前綴失效，靠根鏈 lstat 擋）。
        assumeTrue(!System.getProperty("os.name", "").startsWith("Windows"))
        val parent = tempDir("b1-alias-parent").canonicalFile
        try {
            val real = File(parent, "real").apply { mkdirs() }
            File(real, "f.txt").writeText("ok")
            val alias = File(parent, "alias")
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            val candidate = File(alias, "f.txt").absolutePath
            val v = ShellPolicy.validate(
                listOf("cat", candidate),
                alias.absolutePath,
            )
            assertTrue("aliased privateRoot must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue(
                "message must distinguish symlink escape, got ${v.message}",
                v.message.contains("symlink") || v.message.contains("cross-domain"),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun guestContainerInternal_allowed_vs_externalDenied() {
        // Guest 容器內（/inbox 開頭，見 ShellPolicy KDoc + LinuxInboxStager.GUEST_INBOX_DIR）
        // 屬容器命名空間，政策短路放行（continue 先於 FS 門，見 checkPathArgs；本案不斷言零 FS 觸碰）；
        // /sdcard 外掛點縱深保留拒絕（GUEST_EXTERNAL_PREFIXES）。
        val scope = tempDir("b1-guest-inbox-scope").canonicalFile
        try {
            val internal = ShellPolicy.validate(
                listOf("cat", "/inbox/staged.txt"),
                scope.absolutePath,
                isGuest = true,
            )
            assertTrue("guest container-internal must be allowed, got $internal", internal is Validation.Allowed)
            val external = ShellPolicy.validate(
                listOf("cat", "/sdcard/x.txt"),
                scope.absolutePath,
                isGuest = true,
            )
            assertTrue("guest external mount must stay denied, got $external", external is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (external as Validation.Denied).reason)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun nonexistentInside_allowed() {
        // 不存在不誤殺：scope/nope.txt 不建檔，ls 仍放行（不存在降級為父鏈 walk，不以 ENOENT 誤殺）。
        val scope = tempDir("b1-noent-scope").canonicalFile
        try {
            val v = ShellPolicy.validate(
                listOf("ls", File(scope, "nope.txt").absolutePath),
                scope.absolutePath,
            )
            assertTrue("nonexistent inside must stay allowed, got $v", v is Validation.Allowed)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun trailingSlash_dirAllowed_linkDenied() {
        // 尾 slash：真目錄 scope/subdir/ 放行；scope/link/（link→域外目錄）拒。
        // 斷言分開寫清：前者 Allowed，後者 Denied + BLACKLISTED + symlink/cross-domain。
        assumeTrue(!System.getProperty("os.name", "").startsWith("Windows"))
        val scope = tempDir("b1-slash-scope").canonicalFile
        val outside = tempDir("b1-slash-outside").canonicalFile
        try {
            val sub = File(scope, "subdir").apply { mkdirs() }
            val ok = ShellPolicy.validate(
                listOf("ls", sub.absolutePath + "/"),
                scope.absolutePath,
            )
            assertTrue("real dir with trailing slash must stay allowed, got $ok", ok is Validation.Allowed)
            Files.createSymbolicLink(File(scope, "link").toPath(), outside.toPath())
            val bad = ShellPolicy.validate(
                listOf("ls", File(scope, "link").absolutePath + "/"),
                scope.absolutePath,
            )
            assertTrue("link with trailing slash must be denied, got $bad", bad is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (bad as Validation.Denied).reason)
            assertTrue(
                "message must distinguish symlink escape, got ${bad.message}",
                bad.message.contains("symlink") || bad.message.contains("cross-domain"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dotdotEncoding_locked() {
        // 編碼鎖定（Linux kernel 語義；Windows 語義不同故整案限 Linux）：
        // 字面 %2e%2e 是普通檔名（kernel 不解碼），放行；
        // //sub//..//escape 與 .../a/./../b.txt 含 .. 段即拒（split 出 .. 即拒，不先折疊）。
        // ...、`.. `（點點空格）、全形 `．．` 都不是 ASCII .. 段，不誤殺（放行）。
        // normalize 不做 NFKC／不解 %2e／不折全形，Linux 按位元組比對，故三者同 rationale。
        assumeTrue(!System.getProperty("os.name", "").startsWith("Windows"))
        val scope = tempDir("b1-enc-scope").canonicalFile
        try {
            File(scope, "%2e%2e").mkdirs()
            val literal = ShellPolicy.validate(
                listOf("cat", File(scope, "%2e%2e/escape.txt").absolutePath),
                scope.absolutePath,
            )
            assertTrue("literal percent-encoded name must stay allowed, got $literal", literal is Validation.Allowed)
            val doubleSlash = ShellPolicy.validate(
                listOf("cat", scope.absolutePath + "//sub//..//escape.txt"),
                scope.absolutePath,
            )
            assertTrue("double-slash dotdot must be denied, got $doubleSlash", doubleSlash is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (doubleSlash as Validation.Denied).reason)
            assertTrue(
                "message must carry reason code, got ${doubleSlash.message}",
                doubleSlash.message.contains("DOTDOT"),
            )
            val dotDotMixed = ShellPolicy.validate(
                listOf("cat", scope.absolutePath + "/.../a/./../b.txt"),
                scope.absolutePath,
            )
            assertTrue("mixed dotdot must be denied, got $dotDotMixed", dotDotMixed is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (dotDotMixed as Validation.Denied).reason)
            assertTrue(
                "message must carry reason code, got ${dotDotMixed.message}",
                dotDotMixed.message.contains("DOTDOT"),
            )
            val tripleDot = ShellPolicy.validate(
                listOf("cat", File(scope, ".../x.txt").absolutePath),
                scope.absolutePath,
            )
            assertTrue("triple-dot segment must not be mis-killed, got $tripleDot", tripleDot is Validation.Allowed)
            val dotdotSpace = ShellPolicy.validate(
                listOf("cat", File(scope, ".. /x.txt").absolutePath),
                scope.absolutePath,
            )
            assertTrue("dotdot-space segment must not be mis-killed, got $dotdotSpace", dotdotSpace is Validation.Allowed)
            val fullWidth = ShellPolicy.validate(
                listOf("cat", File(scope, "．．/x.txt").absolutePath),
                scope.absolutePath,
            )
            assertTrue("full-width dots must not be mis-killed, got $fullWidth", fullWidth is Validation.Allowed)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun equalsSuffixSymlink_denied() {
        // `=` 後綴同門鎖定：absoluteCandidates 對任何 `--key=/abs` 都抽候選先驗（與鍵無關，
        // 見 checkPathArgs 內 absoluteCandidates 迴圈），故 `--opt=<link>` 走同一 B1 門必須拒。
        // 無放行對照：非路徑鍵的放行屬旗標政策（與 B1 正交），不屬本門承諾。
        assumeTrue(!System.getProperty("os.name", "").startsWith("Windows"))
        val scope = tempDir("b1-eq-scope").canonicalFile
        val outside = tempDir("b1-eq-outside").canonicalFile
        try {
            File(outside, "secret.txt").writeText("OUTSIDE")
            Files.createSymbolicLink(File(scope, "link.txt").toPath(), File(outside, "secret.txt").toPath())
            val v = ShellPolicy.validate(
                listOf("cat", "--opt=" + File(scope, "link.txt").absolutePath),
                scope.absolutePath,
            )
            assertTrue("equals-suffix symlink must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue(
                "message must distinguish symlink escape, got ${v.message}",
                v.message.contains("symlink") || v.message.contains("cross-domain"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
