#!/usr/bin/env bash
# test_docs_claim_check.sh — fixture self-test for scripts/docs_claim_check.sh.
#
# Stage4 D-02: the guard had no fixture tests, so a rule could be weakened (or
# silently fail open) without anything turning red. This harness builds a
# minimal synthetic repo that passes every section, then clones it per bypass
# surface and asserts the gate FAILS with the exact reason:
#   1. out-of-package reference  (production code outside the package imports MCP)
#   2. in-package outbound import (mcp package imports beyond the allowlist)
#   3. in-package FQN outbound use (fully-qualified call/type without import)
#   4. manifest registration      (AndroidManifest* registers an MCP component)
#   5. new-module reference       (a second module's src tree references MCP)
#   6. manifest relative component (android:name=".Transport" resolves to a
#      class inside dev/librepocket/mcp/ — P3-1)
#  6b. manifest relative component, single-quoted + multi-segment
#      (android:name='.mcp.Transport' → basename Transport — N9)
#   7. discovery: no manifests at all (fail closed, never silent pass)
#   8. discovery: no */src source roots at all
#   9. discovery: unreadable production source (chmod 000 → grep rc=2)
#  10. discovery: zero production Kotlin/Java sources
#  11. Section A: scripts/tests/*.py referenced by the workflow but absent
#      from docs/TESTING.md (P3-2 subdirectory coverage)
# Positive controls: the clean fixture and the REAL repository must PASS, and
# the gate source must still carry every C1–C4 section marker (N-B7: a section
# wrapped in `if false` would otherwise stay green).
#
# Stdlib only (bash/grep/find + the gate's own python3/yaml), CWD-independent,
# temp dirs cleaned on exit. Exit non-zero on any mismatch.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$ROOT/scripts/docs_claim_check.sh"
if [ ! -f "$GATE" ]; then
  echo "test_docs_claim_check: missing gate at $GATE"
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
say() { echo "test_docs_claim_check: $1"; }

# --- minimal repo that must pass every gate section -------------------------
make_template() {
  local d="$1"
  mkdir -p "$d/scripts" "$d/.github/workflows" "$d/docs" "$d/gradle/wrapper" \
    "$d/app/src/main/java/dev/librepocket/mcp" \
    "$d/app/src/main/java/dev/librepocket/other"
  cp "$GATE" "$d/scripts/docs_claim_check.sh"

  cat > "$d/.github/workflows/pr-check.yml" <<'YAML'
name: pr-check
on:
  pull_request:
jobs:
  unit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: ./gradlew :app:testPlayDebugUnitTest
YAML

  cat > "$d/docs/TESTING.md" <<'MD'
# Testing

Local: `./gradlew :app:testPlayDebugUnitTest`.
MD

  cat > "$d/docs/ENV.md" <<'MD'
# Build environment

- Android Gradle Plugin 9.0.0
- Kotlin 2.1.0
- compileSdk 35 / targetSdk 35 / minSdk 26
- Gradle 8.10
- JDK 25
MD

  cat > "$d/gradle/libs.versions.toml" <<'TOML'
[versions]
agp = "9.0.0"
kotlin = "2.1.0"
compileSdk = "35"
targetSdk = "35"
minSdk = "26"
TOML

  cat > "$d/app/build.gradle.kts" <<'KTS'
android {
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        targetSdk = 35
    }
}
KTS

  printf '%s\n' \
    'distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip' \
    > "$d/gradle/wrapper/gradle-wrapper.properties"

  cat > "$d/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
</manifest>
XML

  cat > "$d/app/src/main/java/dev/librepocket/other/Widget.kt" <<'KT'
package dev.librepocket.other

/** No MCP references anywhere. */
class Widget
KT

  # Package stub exercising the ALLOWED forms: package decl, allowlisted
  # imports (dev.librepocket.tool.ToolDef, dev.librepocket.keystore.KeyVault
  # with a trailing comment carrying the letter n and a backslash — SF-1: the
  # comment tail must be `//.*`; a POSIX `[^\n]` bracket class would wrongly
  # reject it), and a KDoc link to an out-of-allowlist type — none of those
  # may trip the outbound scan.
  cat > "$d/app/src/main/java/dev/librepocket/mcp/McpStub.kt" <<'KT'
package dev.librepocket.mcp

import dev.librepocket.tool.ToolDef
import dev.librepocket.keystore.KeyVault // trailing note with n and \ backslash test

/**
 * Fixture stub: key storage lives at [dev.librepocket.keystore.KeyVault],
 * privilege checks at [dev.librepocket.router.PrivilegeGate.check].
 */
internal class McpStub(private val def: ToolDef, private val vault: KeyVault)
KT
}

expect_pass() {
  local dir="$1" label="$2" out="$TMP/last.out"
  if (cd "$dir" && bash scripts/docs_claim_check.sh) >"$out" 2>&1 &&
    grep -q 'docs_claim_check: PASSED' "$out"; then
    say "PASS $label"
  else
    say "FAIL $label (expected gate PASSED)"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  fi
}

expect_fail() {
  local dir="$1" label="$2" needle="$3" out="$TMP/last.out"
  if (cd "$dir" && bash scripts/docs_claim_check.sh) >"$out" 2>&1; then
    say "FAIL $label (gate PASSED but must fail closed)"
    failures=$((failures + 1))
  elif grep -Fq "$needle" "$out"; then
    say "PASS $label"
  else
    say "FAIL $label (failed without expected reason: $needle)"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  fi
}

make_template "$TMP/clean"
expect_pass "$TMP/clean" "clean fixture passes every section"

# N-B7: the gate source must still carry every surface marker — a whole
# section accidentally wrapped in `if false` would keep the fixtures green.
gate_marker_missing=0
for marker in '--- C1\.' '--- C2\.' '--- C3\.' '--- C4\.'; do
  if ! grep -qE -- "$marker" "$GATE"; then
    say "FAIL gate source missing section marker '$marker'"
    failures=$((failures + 1))
    gate_marker_missing=1
  fi
done
if [ "$gate_marker_missing" -eq 0 ]; then
  say "PASS gate source carries all C1-C4 section markers"
fi

# 1. out-of-package production reference (surface: whole-tree scan)
cp -r "$TMP/clean" "$TMP/out_pkg"
mkdir -p "$TMP/out_pkg/app/src/main/java/dev/librepocket/x"
cat > "$TMP/out_pkg/app/src/main/java/dev/librepocket/x/Out.kt" <<'KT'
package dev.librepocket.x

import dev.librepocket.mcp.McpServer

class Out(val server: McpServer)
KT
expect_fail "$TMP/out_pkg" \
  "out-of-package production import is rejected" \
  "outside dev/librepocket/mcp/ references MCP types"

# 2. in-package outbound import beyond the allowlist (surface: import allowlist)
cp -r "$TMP/clean" "$TMP/in_import"
cat > "$TMP/in_import/app/src/main/java/dev/librepocket/mcp/Wired.kt" <<'KT'
package dev.librepocket.mcp

import dev.librepocket.router.PrivilegeGate

internal class Wired(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/in_import" \
  "in-package outbound import beyond allowlist is rejected" \
  "outbound import not on allowlist"

# 3. in-package fully-qualified outbound use without import (surface: FQN)
cp -r "$TMP/clean" "$TMP/in_fqn"
cat > "$TMP/in_fqn/app/src/main/java/dev/librepocket/mcp/Fqn.kt" <<'KT'
package dev.librepocket.mcp

internal fun go(): String = dev.librepocket.tool.ToolDef.toString()
KT
expect_fail "$TMP/in_fqn" \
  "in-package fully-qualified outbound use is rejected" \
  "outbound reference from inside dev/librepocket/mcp/"

# 4. manifest registration (surface: AndroidManifest)
cp -r "$TMP/clean" "$TMP/manifest"
cat >> "$TMP/manifest/app/src/main/AndroidManifest.xml" <<'XML'
<service android:name="dev.librepocket.mcp.McpService" />
XML
expect_fail "$TMP/manifest" \
  "manifest MCP registration is rejected" \
  "Android manifest registers MCP"

# 5. second module's production source references MCP (surface: whole module tree)
cp -r "$TMP/clean" "$TMP/multimodule"
mkdir -p "$TMP/multimodule/othermod/src/main/java/dev/librepocket/y"
cat > "$TMP/multimodule/othermod/src/main/java/dev/librepocket/y/Ref.kt" <<'KT'
package dev.librepocket.y

val server: McpServer? = null
KT
expect_fail "$TMP/multimodule" \
  "second-module MCP reference is rejected" \
  "outside dev/librepocket/mcp/ references MCP types"

# 6. manifest package-relative component resolves into the mcp package (P3-1)
cp -r "$TMP/clean" "$TMP/rel_manifest"
cat > "$TMP/rel_manifest/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat >> "$TMP/rel_manifest/app/src/main/AndroidManifest.xml" <<'XML'
<service android:name=".Transport" />
XML
expect_fail "$TMP/rel_manifest" \
  "manifest relative component resolving into mcp package is rejected" \
  "Android manifest registers MCP (relative component .Transport)"

# 6b. N9 hardening: single-quoted, multi-segment relative name — the last
# segment (.mcp.Transport → Transport) must still resolve into the mcp
# package. (The clean fixture's `.MainActivity` covers the no-false-kill
# side: a relative name whose basename has no mcp-package file passes.)
cp -r "$TMP/clean" "$TMP/rel_manifest_sq"
cat > "$TMP/rel_manifest_sq/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat >> "$TMP/rel_manifest_sq/app/src/main/AndroidManifest.xml" <<'XML'
<service android:name='.mcp.Transport' />
XML
expect_fail "$TMP/rel_manifest_sq" \
  "single-quoted multi-segment relative component resolving into mcp package is rejected" \
  "Android manifest registers MCP (relative component .Transport)"

# 7. discovery fail-closed: no AndroidManifest*.xml anywhere
cp -r "$TMP/clean" "$TMP/no_manifest"
rm "$TMP/no_manifest/app/src/main/AndroidManifest.xml"
expect_fail "$TMP/no_manifest" \
  "zero manifests fails closed (discovery surface)" \
  "no AndroidManifest*.xml found"

# 8. discovery fail-closed: no */src source roots anywhere
cp -r "$TMP/clean" "$TMP/no_src"
rm -rf "$TMP/no_src/app/src"
expect_fail "$TMP/no_src" \
  "zero src roots fails closed (discovery surface)" \
  "no */src source roots found"

# 9. discovery fail-closed: unreadable production source (grep rc=2)
cp -r "$TMP/clean" "$TMP/unreadable"
chmod 000 "$TMP/unreadable/app/src/main/java/dev/librepocket/other/Widget.kt"
expect_fail "$TMP/unreadable" \
  "unreadable production source fails closed (grep rc=2)" \
  "grep over production sources failed (fail closed, rc=2)"
chmod 644 "$TMP/unreadable/app/src/main/java/dev/librepocket/other/Widget.kt"

# 10. discovery fail-closed: src roots exist but zero production sources
cp -r "$TMP/clean" "$TMP/no_prod"
rm "$TMP/no_prod/app/src/main/java/dev/librepocket/other/Widget.kt" \
   "$TMP/no_prod/app/src/main/java/dev/librepocket/mcp/McpStub.kt"
expect_fail "$TMP/no_prod" \
  "zero production sources fails closed (discovery surface)" \
  "no production Kotlin/Java sources found"

# 11. Section A: workflow references scripts/tests/*.py (subdir) missing in TESTING.md
cp -r "$TMP/clean" "$TMP/scripts_subdir"
cat >> "$TMP/scripts_subdir/.github/workflows/pr-check.yml" <<'YAML'
      - run: python3 -m unittest scripts/tests/test_newthing.py
YAML
expect_fail "$TMP/scripts_subdir" \
  "workflow-referenced scripts/tests/*.py must be documented in TESTING.md" \
  "TESTING.md missing CI script 'scripts/tests/test_newthing.py' from pr-check.yml"

# Positive control on the real repository (not just synthetic fixtures).
expect_pass "$ROOT" "real repository passes"

if [ "$failures" -ne 0 ]; then
  say "FAILED ($failures case(s))"
  exit 1
fi
say "PASSED (clean + real repo + gate markers + 12 negative surfaces)"
