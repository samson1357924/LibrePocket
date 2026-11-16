package dev.librepocket.router

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Architecture pin: the fast-lane execution surface ([FastDispatcher] and
 * its executors/outcomes in `FastDispatch.kt`) is an UNWIRED, internal-only
 * execution path. Production composition is records-only (`toolSlice = null`,
 * pinned by `ProductionChatCompositionTest`) and nothing outside
 * `dev/librepocket/router/` may reference the fast-lane symbols — including
 * flavor-specific production source sets compiled into the foss/github APKs
 * (app/src/{foss,github}/java).
 *
 * Wiring (any non-test caller outside the router package) must come only
 * with the full activation prerequisites: fresh ctxSupplier, fresh
 * `tool.exec` ALLOW-only policy, immutable confirmation snapshots, Room
 * audit, device E2E — see docs/CAPABILITY_MATRIX.md §啟用前置.
 */
class FastDispatchSurfaceArchitectureTest {

    private val forbiddenTokens = listOf(
        "FastDispatcher",
        "LspSymbolsExecutor",
        "FastDispatchOutcome",
        "decideAndDispatch",
        "FastExecResult",
        "FastLaneResult",
        "FastToolExecutor",
    )

    @Test
    fun fastLaneExecutionSurfaceIsUnreachableFromProductionSources() {
        // Test cwd differs by harness (module dir vs repo root); accept both
        // layouts. Scan ALL production source sets (main + every flavor), so
        // flavor wiring into the foss/github APKs also trips this pin.
        // At least one root must exist, and zero scanned Kotlin files is a
        // hard failure — the pin must never pass vacuously.
        val sourceRoots = listOf(
            "src/main/java", "app/src/main/java",
            "src/foss/java", "app/src/foss/java",
            "src/github/java", "app/src/github/java",
            "src/play/java", "app/src/play/java",
            "src/debug/java", "app/src/debug/java",
        ).map { File(it) }.filter { it.isDirectory }
        assertTrue(
            "no Kotlin source root found (tried main + foss/github/play/debug flavors)",
            sourceRoots.isNotEmpty(),
        )

        val kotlinFiles = sourceRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file ->
                    val normalized = file.path.replace(File.separatorChar, '/')
                    normalized.contains("/dev/librepocket/router/") ||
                        normalized.contains("/build/") ||
                        normalized.contains("/generated/")
                }
                .toList()
        }
        assertTrue(
            "zero Kotlin production files found outside dev/librepocket/router/; " +
                "the pin cannot pass vacuously",
            kotlinFiles.isNotEmpty(),
        )

        val violations = mutableListOf<String>()
        for (file in kotlinFiles) {
            val text = file.readText(Charsets.UTF_8)
            for (token in forbiddenTokens) {
                if (text.contains(token)) {
                    violations += "${file.path}: contains fast-lane token `$token`"
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "fast-lane execution must stay records-only/unwired; a guard was crossed:\n" +
                    violations.joinToString("\n"),
            )
        }
    }
}
