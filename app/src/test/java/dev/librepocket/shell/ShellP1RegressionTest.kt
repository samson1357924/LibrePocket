package dev.librepocket.shell

import org.junit.Assert.assertTrue
import org.junit.Test

class ShellP1RegressionTest {
    @Test fun wcBooleanFlagDoesNotSkipBarePath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("wc", "-c", "leak"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    @Test fun grepConsumedPatternDoesNotSkipFollow() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        val outside = java.io.File.createTempFile("out","").apply { delete(); mkdirs() }
        try {
            val sub = java.io.File(scope, "subtree").apply { mkdirs() }
            val v = ShellPolicy.validate(listOf("grep", "-e", "-e", "-R", scope.absolutePath + "/subtree"), privateRoot = scope.absolutePath)
            assertTrue("expected denied (traversal), got $v", v is Validation.Denied)
        } finally {
            scope.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test fun dfOptionValueNotScopedPath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("df", "-x", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    @Test fun wcOldBehaviorRegression_absoluteOutsideStillDenied() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        val outside = java.io.File.createTempFile("out","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("wc", "-c", outside.absolutePath), privateRoot = scope.absolutePath)
            // outside path: vetted against scope => denied
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively(); outside.deleteRecursively() }
    }
}