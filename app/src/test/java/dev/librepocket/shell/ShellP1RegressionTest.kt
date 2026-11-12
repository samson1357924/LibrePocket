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

    // Elevated channel parity
    @Test fun elevated_wcBooleanFlagDoesNotSkipBarePath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validateElevated(listOf("wc", "-c", "leak"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    @Test fun elevated_grepConsumedPatternDoesNotSkipFollow() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validateElevated(listOf("grep", "-e", "-e", "-R", scope.absolutePath + "/subtree"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    @Test fun elevated_dfOptionValueNotScopedPath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validateElevated(listOf("df", "-x", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    // Multicall effective form (toybox)
    @Test fun multicall_wcBooleanFlagDoesNotSkipBarePath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("toybox", "wc", "-c", "leak"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    @Test fun multicall_grepConsumedPatternDoesNotSkipFollow() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("toybox", "grep", "-e", "-e", "-R", scope.absolutePath + "/subtree"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    @Test fun multicall_dfOptionValueNotScopedPath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("toybox", "df", "-x", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
        } finally { scope.deleteRecursively() }
    }

    // grep -e -R /scope/known: -R is pattern value, preserve legal semantics (not denied as traversal)
    @Test fun grepPatternValueR_notTraversal() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val known = java.io.File(scope, "known").apply { mkdirs() }
            val v = ShellPolicy.validate(listOf("grep", "-e", "-R", known.absolutePath), privateRoot = scope.absolutePath)
            // -R consumed as pattern value; known path vetted -> may be Allowed or denied on other grounds, but NOT traversal-blacklisted due to -R-as-pattern
            if (v is Validation.Denied) {
                val msg = v.message
                assertTrue("should not be traversal denial, got $v", !msg.contains("traversal"))
            }
        } finally { scope.deleteRecursively() }
    }
}
