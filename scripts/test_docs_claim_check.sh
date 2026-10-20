#!/usr/bin/env bash
# test_docs_claim_check.sh — fixture self-test for scripts/docs_claim_check.sh.
#
# Stage4 D-02: the guard had no fixture tests, so a rule could be weakened (or
# silently fail open) without anything turning red. This harness builds a
# minimal synthetic repo that passes every section, then clones it per bypass
# surface and asserts the gate FAILS with the exact reason:
#   1. out-of-package reference  (production code outside the package imports MCP)
#  1b. out-of-package reference not hidden by an in-code package-path comment
#      (Stage6 F2: filename prefilter, not a line-based skip)
#  1c. positive: in-package file may mention its own package path
#  1d. path-lookalike file with `package com.example` is still outside-scanned
#      (Stage6 B: package-declaration classification, not directory path)
#  1e. positive: `package dev.librepocket.mcp` at an unrelated path is
#      in-package and passes (Stage6 B; path-based classification would red)
#  1f. `package dev.librepocket.mcp` forged in a block comment does not hide a
#      real out-of-package FQN use (Stage6 fix2: comments are stripped)
#  1f2. same with the forged line inside a raw string (Stage6 fix2)
#  1g. positive: `package dev.librepocket.mcp.transport` (subpackage) is
#      in-package (Stage6 fix2)
#  1h. package segments split by a comment (`dev.librepocket./*x*/mcp`) still
#      classify in-package, so its outbound import fails (fix3 M1)
#  1i. package segments split by whitespace (`dev . librepocket . mcp`) (fix3)
#  1j. package segments backticked (`package `dev`.librepocket.mcp`) (fix3)
#  1k. comment-split package + manifest `.Bt` resolves into the type set
#      (fix3 M1: C2 sees the package-classified file)
#  1l. Kotlin `${…}` template containing a raw-string package forgery cannot
#      swallow the real package (fix3 P0-1 recursive lexer)
#  1m. Java `\uXXXX` package/comment escapes are decoded before lexing; the
#      escaped comment is stripped and the ESC pre-scan fails closed (P0-2)
#  1n. Java FQN-only Unicode escape is caught by the ESC pre-scan (P0-2)
#  1o. more than one real package declaration fails closed (fix3, >1 pin)
#  1p. positive: comment-split canonical package with no outbound use (fix3)
#  1q. positive: benign `\u00e9` in a Java string does not trip the ESC scan
#  1r. Kotlin raw-string `${…}` template with a nested raw-string package
#      forgery cannot desynchronise the lexer (fix3: raw templates resolve as
#      code; the real package stays visible/OUT and C4 scans)
#  1s. `package …; import …` on one physical line is not skipped by C3
#      (fix3: only an entire-line package declaration is exempt)
#  1u. Java same-line `package …; import \u0064ev…` — only the package header
#      is masked by the ESC pre-scan; the remainder is still decoded/scanned
#      (fix4 M1)
#  1v. same-line version where the package keyword is escaped too (fix4 M1)
#  1w. outside backticked FQN (`dev.librepocket.`mcp`.Transport`) — C4
#      normalises backticks (fix4 M3)
#  1x. outside comment-split FQN (`dev.librepocket./*x*/mcp.Transport`) (M3)
#  1y. outside spaced-dot FQN (`dev . librepocket . mcp`) (M3)
#  1z. positive: outside only `dev.librepocket.mcpfoo` / `dev.librepocket.other`
#      (C4 trailing boundary + no false kill) (M3)
#  1aa. backticked `package dev.librepocket.mcp` text is not a declaration
#      (fix4 S1); the default-package file is OUT and C4 scans the FQN use
#  1ab. in-package `dev.librepocket.mcpfoo.Bar` is outbound, not a
#      self-mention substring (fix4 S5)
#  1ac. positive: outside `dev.librepocket.mcpfoo.X` is not the mcp package
#      (C4 boundary pin, S6)
#  1ad. positive: in-package allowlisted import with a trailing block comment
#      is normalised before the allowlist test (C3 pin, S6)
#  1ae. Java text-block `\"""` is an escaped quote, not a delimiter; the text
#      block cannot forge a package / swallow the real declaration (fix4)
#  1af. positive: allowlisted import with a `;` + trailing line comment is
#      normalised (fix4)
#  1ag. positive: tab after `import` is still classified as an import (fix4)
#  1ah. C4 `//` inside a string does not truncate a later MCP FQN (fix4)
#  1ai. positive: a comment containing `; import …` after an allowlisted
#      import is not treated as a real second import (fix4)
#   2. in-package outbound import (mcp package imports beyond the allowlist)
#  2b. double-space `import  dev.librepocket.…` is still checked (fix4 M2)
#  2c. Java `import static dev.librepocket.router.…` is still checked (M2)
#  2d. positive: Java same-line package + allowlisted import (M2)
#  2e. positive: subpackage imports a parent-package mcp type
#      (`import dev.librepocket.mcp.McpServer`) (M2)
#  2f. block-comment-prefixed/suffixed outbound import is still checked (fix4)
#  2g. block-comment-split outbound FQN is normalised and checked (fix4)
#  2h. string comment delimiters cannot hide a contiguous outbound FQN (fix4)
#  2i. backticked outbound import segment is still checked (fix4)
#  2j. second outbound import on one physical line is still checked (fix4)
#  2k. comment between `;` and the second outbound import is still checked
#   3. in-package FQN outbound use (fully-qualified call/type without import)
#   4. manifest registration      (AndroidManifest* registers an MCP component)
#   5. new-module reference       (a second module's src tree references MCP)
#   6. manifest relative component (android:name=".Transport" resolves to a
#      class inside dev/librepocket/mcp/ — P3-1)
#  6b. manifest relative component, single-quoted + multi-segment
#      (android:name='.mcp.Transport' → basename Transport — N9)
#  6c. manifest relative component with spaces around `=` (Stage6 F1)
#  6d. multi-line manifest attribute (Stage6 F1)
#  6e. namespace-prefix alias (`a:name`, Stage6 F1)
#  6f. unqualified manifest component (`name="Transport"`, Stage6 F1)
#  6g. `${mcpComponent}` placeholder fails closed (Stage6 F1)
#  6h. malformed manifest fails closed (Stage6 F1)
#  6i. (positive) test-source-only basename does not fail (Stage6 F1)
#  6j. entity-encoded FQN (`&#46;`) caught by the XML layer (Stage6 A4)
#  6k. (positive) meta-data `name` is not component-basename matched (A1)
#  6l. targetActivity reference into the mcp package (Stage6 A1)
#  6m. parentActivityName reference into the mcp package (Stage6 A1)
#  6n. declared type name in a differently named file (Stage6 A2)
#  6o. nested-class basename `.mcp.Outer$Inner` (Stage6 A2)
#  6p. gradle manifestPlaceholders + MCP reference (Stage6 A3)
#  6q. gradle manifest.srcFile custom path (Stage6 A3)
#  6r. gradle applicationId = dev.librepocket.mcp (Stage6 A3)
#  6s. <manifest package="dev.librepocket&#46;mcp&#46;legacy"> (entity form;
#      only the XML package branch can catch it — Stage6 A1)
#  6t. every component element (application/activity/activity-alias/receiver/
#      provider/instrumentation/uses-library/uses-native-library) resolving
#      `.Transport` into mcp (Stage6 A1)
#  6u. backupAgent/manageSpaceActivity/appComponentFactory refs (Stage6 A1)
#  6v. KTS namespace = dev.librepocket.mcp (Stage6 fix2)
#  6w. Groovy applicationId "dev.librepocket.mcp" (Stage6 fix2)
#  6x. cross-file manifestPlaceholders + root build extra MCP literal (S2)
#  6y. gradle.properties MCP literal consumed via project.property (S2)
#  6z. getManifest().setSrcFile getter form (S2d)
#  6aa. manifest comment mentioning MCP pins the text-grep layer (Stage6 fix2)
#  6ab. typealias name counts for relative resolution (N4 over-approx)
#  6ad. addManifestPlaceholders + MCP literal (fix3 P0-3)
#  6ae. setNamespace("dev.librepocket.mcp") setter form (fix3 P1)
#  6af. setApplicationId("dev.librepocket.mcp") setter form (fix3 P1)
#  6ag. getManifest().srcFile getter property form (fix3 P1)
#  6ah. gradle `namespace = "\u0064ev…"` Unicode escape is decoded (fix3 P1)
#  6ai. gradle.properties `\uXXXX` MCP literal is decoded (fix3 P1)
#  6aj. positive: addManifestPlaceholders with a non-mcp dev.librepocket value
#  6al. namespace = "dev.librepocket.mcp.legacy" is a subpackage (fix4 S2)
#  6am. positive: namespace dev.librepocket.mcpfoo is not the package (S2)
#  6an. defensive `manifest.setSrcFile(...)` spelling is rejected (fix4 S3)
#  6ao. cross-line `namespace =` + value on the next line (fix4 S4)
#  6ap. `<manifest package="${mcpPkg}">` placeholder fails closed (S6 pin)
#  6aq. positive: manifest package="dev.librepocket.mcpfoo" (S6 boundary pin)
#  6ar. backticked in-package type name resolves a relative component (fix4)
#  6as. triple-quoted KTS namespace MCP value is rejected (fix4)
#  6at. comment between `namespace` and `=` is rejected (fix4)
#  6au. Gradle Property `applicationId.set(...)` MCP value is rejected (fix4)
#  6av. comment/whitespace around `manifest.srcFile` dot is rejected (fix4)
#  6aw. positive: triple-quoted namespace mcpfoo stays green (fix4)
#  6ax. standalone MCP literal in any gradle file fails closed (fix4)
#  6ay. receiver-less/aliased `srcFile` token is rejected (fix4)
#  6az. `\`-continued gradle.properties MCP literal is joined and rejected
#   7. discovery: no manifests at all (fail closed, never silent pass)
#   8. discovery: no */src source roots at all
#   9. discovery: unreadable production source (chmod 000 → scan fails closed)
#  10. discovery: zero production Kotlin/Java sources
# 10b. discovery: no production source declares package dev.librepocket.mcp
#  11. Section A: scripts/tests/*.py referenced by the workflow but absent
#      from docs/TESTING.md (P3-2 subdirectory coverage)
#  N8. (positive) applicationId dev.librepocket.mcpfoo (trailing boundary, S2)
#  N8b. (positive) namespace dev.librepocket.mcpfoo (trailing boundary, S2)
# Positive controls: the clean fixture, an in-package path comment (1c), a
# test-source-only basename (6i), a meta-data name (6k), an out-of-directory
# mcp package (1e), an mcp subpackage (1g), the mcpfoo boundaries
# (N8/N8b/1ac/6am/6aq), the comment-split package (1p), the benign Java
# Unicode escape (1q), the non-mcp manifest placeholder (6aj), the
# normalised-import/self-import positives (1ad/1af/1ag/1ai/2d/2e), the
# triple-quoted mcpfoo boundary (6aw), the C4 lookalike boundary (1z), and
# the REAL repository must PASS, and the gate source must
# still carry every C1–C4 section marker (N-B7: a section wrapped in
# `if false` would otherwise stay green).
#
# Counts are runtime counters (authoritative: the final say() line).
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
pos_cases=0
neg_cases=0
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
    namespace = "dev.librepocket.agent"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.librepocket.agent"
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
  <activity android:name = ".MainActivity" />
  <service android:name="com.other.Service" />
  <service android:name="${applicationId}.Service" />
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
 * Self path: /dev/librepocket/mcp/ (in-package path string, C3 self-mention).
 */
internal class McpStub(private val def: ToolDef, private val vault: KeyVault)
KT
}

expect_pass() {
  local dir="$1" label="$2" out="$TMP/last.out"
  pos_cases=$((pos_cases + 1))
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
  neg_cases=$((neg_cases + 1))
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

# 1b. Stage6 F2: an in-code comment containing the package path must not hide
# a real outside import (the old line-based `case` filter skipped the whole
# grep hit because the source line itself contained /dev/librepocket/mcp/).
cp -r "$TMP/clean" "$TMP/out_pkg_path_comment"
mkdir -p "$TMP/out_pkg_path_comment/app/src/main/java/dev/librepocket/x"
cat > "$TMP/out_pkg_path_comment/app/src/main/java/dev/librepocket/x/Out.kt" <<'KT'
package dev.librepocket.x

import dev.librepocket.mcp.McpServer // see /dev/librepocket/mcp/
KT
expect_fail "$TMP/out_pkg_path_comment" \
  "outside import is not hidden by an in-code package-path comment (F2)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1c. Stage6 F2 positive control: an in-package file may freely mention its own
# path (C3 allows self-mentions; C4 must exclude the file by filename).
cp -r "$TMP/clean" "$TMP/in_pkg_path_comment"
cat > "$TMP/in_pkg_path_comment/app/src/main/java/dev/librepocket/mcp/PathComment.kt" <<'KT'
package dev.librepocket.mcp

// see /dev/librepocket/mcp/ (self-mention)
/** Package path: /dev/librepocket/mcp/ */
internal class PathComment
KT
expect_pass "$TMP/in_pkg_path_comment" \
  "in-package package-path comment is not mistaken for outside wiring (F2)"

# 1d. Stage6 B: classification is by `package` declaration, not path. A file
# whose path contains .../dev/librepocket/mcp/ but declares `package
# com.example` is outside production code and must still be scanned (the old
# path filter exempted it).
cp -r "$TMP/clean" "$TMP/out_pkg_midpath"
mkdir -p "$TMP/out_pkg_midpath/app/src/main/java/com/example/dev/librepocket/mcp"
cat > "$TMP/out_pkg_midpath/app/src/main/java/com/example/dev/librepocket/mcp/Sneaky.kt" <<'KT'
package com.example

val s: McpServer? = null
KT
expect_fail "$TMP/out_pkg_midpath" \
  "path-lookalike file with a non-mcp package is still scanned (B)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1e. Stage6 B positive control: the inverse — a file outside the directory
# path but declaring `package dev.librepocket.mcp` is in-package and passes.
# The path deliberately contains no `dev/librepocket/mcp` segment: a
# path-based classifier would put it OUTSIDE and red it on `McpServer`, so
# this fixture pins the package-based rule.
cp -r "$TMP/clean" "$TMP/in_pkg_elsewhere"
mkdir -p "$TMP/in_pkg_elsewhere/app/src/main/java/com/example/inpkg"
cat > "$TMP/in_pkg_elsewhere/app/src/main/java/com/example/inpkg/InPkg.kt" <<'KT'
package dev.librepocket.mcp

val s: McpServer? = null
KT
expect_pass "$TMP/in_pkg_elsewhere" \
  "mcp package declaration (not path) classifies in-package (B)"

# 1f. Stage6 fix2 regression (M1): a `package dev.librepocket.mcp` line inside
# a block comment must not forge the classification. The real package is
# com.example, so the FQN use must be scanned by C4 and rejected. (The old
# grep classifier treated the file as in-package, skipped C4 and C3 stripped
# the FQN as a self-mention → green.)
cp -r "$TMP/clean" "$TMP/decoy_block_comment"
mkdir -p "$TMP/decoy_block_comment/app/src/main/java/com/example"
cat > "$TMP/decoy_block_comment/app/src/main/java/com/example/Decoy.kt" <<'KT'
package com.example

/*
package dev.librepocket.mcp
*/

val t = dev.librepocket.mcp.McpTransport()
KT
expect_fail "$TMP/decoy_block_comment" \
  "block-comment package forgery does not hide outside MCP use (M1)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 1f2. Same forgery inside a raw string.
cp -r "$TMP/clean" "$TMP/decoy_raw_string"
mkdir -p "$TMP/decoy_raw_string/app/src/main/java/com/example"
cat > "$TMP/decoy_raw_string/app/src/main/java/com/example/Decoy.kt" <<'KT'
package com.example

val s = """
package dev.librepocket.mcp
"""

val t = dev.librepocket.mcp.McpTransport()
KT
expect_fail "$TMP/decoy_raw_string" \
  "raw-string package forgery does not hide outside MCP use (M1)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 1g. Stage6 fix2 (S1): a declaration in an mcp SUBpackage is in-package, not
# outside. The manifest scan's type set picks it up too (no false red here).
cp -r "$TMP/clean" "$TMP/in_pkg_subpackage"
mkdir -p "$TMP/in_pkg_subpackage/app/src/main/java/dev/librepocket/mcp/transport"
cat > "$TMP/in_pkg_subpackage/app/src/main/java/dev/librepocket/mcp/transport/Frame.kt" <<'KT'
package dev.librepocket.mcp.transport

internal class Frame
KT
expect_pass "$TMP/in_pkg_subpackage" \
  "mcp subpackage declaration classifies in-package (S1)"

# 1h. Stage6 fix3 (lens B M1): a comment split inside the package path is
# still the canonical package once comments are stripped, so its outbound
# import must be caught by C3 (the old regex would classify it OUT => C3 skip).
cp -r "$TMP/clean" "$TMP/pkg_comment_split"
cat > "$TMP/pkg_comment_split/app/src/main/java/dev/librepocket/mcp/CommentSplit.kt" <<'KT'
package dev.librepocket./*x*/mcp

import dev.librepocket.router.PrivilegeGate

internal class CommentSplit(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/pkg_comment_split" \
  "comment-split package declaration still classifies in-package (M1)" \
  "outbound import not on allowlist"

# 1i. Whitespace around package dots.
cp -r "$TMP/clean" "$TMP/pkg_whitespace"
cat > "$TMP/pkg_whitespace/app/src/main/java/dev/librepocket/mcp/Spaced.kt" <<'KT'
package dev . librepocket . mcp

import dev.librepocket.router.PrivilegeGate

internal class Spaced(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/pkg_whitespace" \
  "whitespace-split package declaration still classifies in-package (M1)" \
  "outbound import not on allowlist"

# 1j. Backticked package segments.
cp -r "$TMP/clean" "$TMP/pkg_backtick"
cat > "$TMP/pkg_backtick/app/src/main/java/dev/librepocket/mcp/Backtick.kt" <<'KT'
package `dev`.librepocket.mcp

import dev.librepocket.router.PrivilegeGate

internal class Backtick(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/pkg_backtick" \
  "backticked package declaration still classifies in-package (M1)" \
  "outbound import not on allowlist"

# 1k. A comment-split canonical package must feed C2's type set: the manifest
# registers relative `.Bt`, whose class lives in a comment-split-package file.
cp -r "$TMP/clean" "$TMP/pkg_comment_manifest"
cat > "$TMP/pkg_comment_manifest/app/src/main/java/dev/librepocket/mcp/Bt.kt" <<'KT'
package dev.librepocket./*x*/mcp

internal class Bt
KT
cat > "$TMP/pkg_comment_manifest/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".Bt" />
</manifest>
XML
expect_fail "$TMP/pkg_comment_manifest" \
  "manifest relative component resolves into a comment-split mcp package (M1)" \
  "Android manifest registers MCP (relative component .Bt)"

# 1l. Stage6 fix3 P0-1: a Kotlin string-template expression containing a raw
# string with a forged package must not desynchronise the lexer and swallow
# the real `package com.example` + FQN. (Naive scanning sees `"""` inside the
# template, swallows the rest as a raw string, classifies IN and both C3/C4
# skip => green.)
cp -r "$TMP/clean" "$TMP/kt_template_forgery"
mkdir -p "$TMP/kt_template_forgery/app/src/main/java/com/example"
cat > "$TMP/kt_template_forgery/app/src/main/java/com/example/Evil.kt" <<'KT'
@file:Suppress("${"""package dev.librepocket.mcp"""}")
package com.example

val t = dev.librepocket.mcp.McpTransport()
KT
expect_fail "$TMP/kt_template_forgery" \
  "Kotlin template/raw-string package forgery does not hide outside MCP use (P0-1)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 1m. Stage6 fix3 P0-2: Java translates JLS Unicode escapes before lexing.
# The escaped package is real, the escaped `//` line is a comment (forged
# package stripped), and the ESC pre-scan still fails closed on the
# escape-obscured MCP references.
cp -r "$TMP/clean" "$TMP/java_unicode_forgery"
mkdir -p "$TMP/java_unicode_forgery/app/src/main/java/com/example"
cat > "$TMP/java_unicode_forgery/app/src/main/java/com/example/Evil.java" <<'JAVA'
\u0070ackage com.example;
\u002f\u002f package dev.librepocket.mcp
public class Evil { Object t = dev.librepocket.mcp.McpTransport.INSTANCE; }
JAVA
expect_fail "$TMP/java_unicode_forgery" \
  "Java Unicode-escaped comment/package forgery fails closed (P0-2)" \
  "Java Unicode escape obscures MCP-related reference"

# 1n. Stage6 fix3 P0-2: FQN-only Unicode escape (no literal mcp token in the
# raw file) must be caught after decoding by the ESC pre-scan.
cp -r "$TMP/clean" "$TMP/java_fqn_escape"
mkdir -p "$TMP/java_fqn_escape/app/src/main/java/com/example"
cat > "$TMP/java_fqn_escape/app/src/main/java/com/example/Escaped.java" <<'JAVA'
package com.example;

public class Escaped {
    Object t = \u0064ev.librepocket.mcp.\u004dcpTransport.INSTANCE;
}
JAVA
expect_fail "$TMP/java_fqn_escape" \
  "Java FQN-only Unicode escape fails closed (P0-2)" \
  "Java Unicode escape obscures MCP-related reference"

# 1o. Stage6 fix3: >1 real package declaration is ambiguous/forged and must
# fail closed (pins the BAD branch).
cp -r "$TMP/clean" "$TMP/multiple_pkg"
mkdir -p "$TMP/multiple_pkg/app/src/main/java/com/example"
cat > "$TMP/multiple_pkg/app/src/main/java/com/example/Multi.kt" <<'KT'
package dev.librepocket.mcp
package com.example

internal class Multi
KT
expect_fail "$TMP/multiple_pkg" \
  "more than one package declaration fails closed (fix3)" \
  "multiple package declarations"

# 1p. Stage6 fix3 positive control: comment-split canonical package, no
# outbound import/FQN — C3 must not mistake the declaration itself for an
# outbound reference.
cp -r "$TMP/clean" "$TMP/pkg_comment_clean"
cat > "$TMP/pkg_comment_clean/app/src/main/java/dev/librepocket/mcp/CommentClean.kt" <<'KT'
package dev.librepocket./*x*/mcp

internal class CommentClean
KT
expect_pass "$TMP/pkg_comment_clean" \
  "comment-split package with no outbound use passes (fix3)"

# 1q. Stage6 fix3 positive control: a benign Java Unicode escape is decoded
# but carries no MCP reference, so the ESC pre-scan stays green.
cp -r "$TMP/clean" "$TMP/java_benign_escape"
mkdir -p "$TMP/java_benign_escape/app/src/main/java/com/example"
cat > "$TMP/java_benign_escape/app/src/main/java/com/example/Utf.java" <<'JAVA'
package com.example;

final class Utf {
    String s = "\u00e9";
}
JAVA
expect_pass "$TMP/java_benign_escape" \
  "benign Java Unicode escape passes (fix3)"

# 1r. Stage6 fix3: raw-string templates are lexed recursively too. Without
# that, the nested `"""` inside `${…}` closes the outer raw string early, the
# forged package becomes code and the real package can be swallowed as a raw
# string => IN and both C3/C4 skip. With the fix the file is OUT and C4 sees
# the out-of-package text.
cp -r "$TMP/clean" "$TMP/kt_raw_template_forgery"
mkdir -p "$TMP/kt_raw_template_forgery/app/src/main/java/com/example"
cat > "$TMP/kt_raw_template_forgery/app/src/main/java/com/example/RawEvil.kt" <<'KT'
val s = """${"""package dev.librepocket.mcp"""}""" + """
package com.example
val t = dev.librepocket.mcp.McpTransport()
"""
KT
expect_fail "$TMP/kt_raw_template_forgery" \
  "Kotlin raw-string template package forgery does not hide outside MCP use (fix3)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 1s. Stage6 fix3: only a line that is ENTIRELY the package declaration may be
# skipped by C3. `package …; import …` is legal Java/Kotlin on one physical
# line; the old `case package\ *)` skip hid the outbound import.
cp -r "$TMP/clean" "$TMP/pkgsame_line_import"
cat > "$TMP/pkgsame_line_import/app/src/main/java/dev/librepocket/mcp/SameLine.kt" <<'KT'
package dev.librepocket.mcp; import dev.librepocket.router.PrivilegeGate

internal class SameLine(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/pkgsame_line_import" \
  "outbound import after the package declaration on one line is still caught (fix3)" \
  "outbound import not on allowlist"

# 1u. fix4 M1: only the package declaration HEADER is masked by the ESC
# pre-scan. Java allows `package …; import …` on one physical line; skipping
# the whole line hid the escape-obscured import in the remainder.
cp -r "$TMP/clean" "$TMP/java_same_line_escape"
mkdir -p "$TMP/java_same_line_escape/app/src/main/java/com/example"
cat > "$TMP/java_same_line_escape/app/src/main/java/com/example/SameLine.java" <<'JAVA'
package com.example; import \u0064ev.librepocket.mcp.\u004dcpServer;
JAVA
expect_fail "$TMP/java_same_line_escape" \
  "same-line escaped import after the package header is caught (M1)" \
  "Java Unicode escape obscures MCP-related reference"

# 1v. fix4 M1: same, but the package keyword is escaped as well (the decoded
# line still starts with `package`, so the old whole-line skip hid it).
cp -r "$TMP/clean" "$TMP/java_same_line_escape_pkg"
mkdir -p "$TMP/java_same_line_escape_pkg/app/src/main/java/com/example"
cat > "$TMP/java_same_line_escape_pkg/app/src/main/java/com/example/SameLine.java" <<'JAVA'
\u0070ackage com.example; import \u0064ev.librepocket.mcp.\u004dcpServer;
JAVA
expect_fail "$TMP/java_same_line_escape_pkg" \
  "same-line escaped package header + escaped import is caught (M1)" \
  "Java Unicode escape obscures MCP-related reference"

# 1w. fix4 M3: a backticked package segment compiles but defeats a literal
# grep. C4 normalises backticks.
cp -r "$TMP/clean" "$TMP/out_pkg_backtick"
mkdir -p "$TMP/out_pkg_backtick/app/src/main/java/com/example"
cat > "$TMP/out_pkg_backtick/app/src/main/java/com/example/Out.kt" <<'KT'
package com.example

val t = dev.librepocket.`mcp`.Transport
KT
expect_fail "$TMP/out_pkg_backtick" \
  "outside backticked FQN is caught by the C4 normaliser (M3)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1x. fix4 M3: a single-line block comment between package segments compiles.
cp -r "$TMP/clean" "$TMP/out_pkg_comment_split"
mkdir -p "$TMP/out_pkg_comment_split/app/src/main/java/com/example"
cat > "$TMP/out_pkg_comment_split/app/src/main/java/com/example/Out.kt" <<'KT'
package com.example

val t = dev.librepocket./*x*/mcp.Transport
KT
expect_fail "$TMP/out_pkg_comment_split" \
  "outside comment-split FQN is caught by the C4 normaliser (M3)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1y. fix4 M3: whitespace around package dots compiles.
cp -r "$TMP/clean" "$TMP/out_pkg_spaced_dot"
mkdir -p "$TMP/out_pkg_spaced_dot/app/src/main/java/com/example"
cat > "$TMP/out_pkg_spaced_dot/app/src/main/java/com/example/Out.kt" <<'KT'
package com.example

val t = dev . librepocket . mcp . Transport
KT
expect_fail "$TMP/out_pkg_spaced_dot" \
  "outside spaced-dot FQN is caught by the C4 normaliser (M3)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1z. fix4 M3 positive control: the trailing boundary keeps lookalike
# packages green.
cp -r "$TMP/clean" "$TMP/out_pkg_boundary"
mkdir -p "$TMP/out_pkg_boundary/app/src/main/java/com/example"
cat > "$TMP/out_pkg_boundary/app/src/main/java/com/example/Out.kt" <<'KT'
package com.example

val a = dev.librepocket.mcpfoo.Thing
val b = dev.librepocket.other.Thing
KT
expect_pass "$TMP/out_pkg_boundary" \
  "outside mcpfoo/other references remain green (M3 boundary)"

# 1aa. fix4 S1: a backticked `package dev.librepocket.mcp` prose token is not
# a declaration. Without the backtick lexer the file was classified IN and C3
# stripped the FQN as a self-mention; with it the (default-package) file is
# OUT and C4 catches the FQN use. No real package declaration here, so the
# forged token is the only declaration the buggy lexer would see.
cp -r "$TMP/clean" "$TMP/backtick_pkg_forgery"
mkdir -p "$TMP/backtick_pkg_forgery/app/src/main/java/com/example"
cat > "$TMP/backtick_pkg_forgery/app/src/main/java/com/example/Backtick.kt" <<'KT'
val `package dev.librepocket.mcp` = 1
val t = dev.librepocket.mcp.McpTransport()
KT
expect_fail "$TMP/backtick_pkg_forgery" \
  "backticked package prose does not forge the declaration (S1)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1ab. fix4 S5: `dev.librepocket.mcpfoo` must not be stripped as a
# self-mention substring (`dev.librepocket.mcp`) inside the mcp package.
cp -r "$TMP/clean" "$TMP/in_self_mention_lookalike"
cat > "$TMP/in_self_mention_lookalike/app/src/main/java/dev/librepocket/mcp/Lookalike.kt" <<'KT'
package dev.librepocket.mcp

internal class Lookalike {
    val x = dev.librepocket.mcpfoo.Bar
}
KT
expect_fail "$TMP/in_self_mention_lookalike" \
  "in-package mcpfoo reference is outbound, not a self-mention (S5)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 1ac. fix4 S6 positive pin: outside `dev.librepocket.mcpfoo.X` is a
# different package (C4 trailing boundary).
cp -r "$TMP/clean" "$TMP/out_pkg_mcpfoo"
mkdir -p "$TMP/out_pkg_mcpfoo/app/src/main/java/com/example"
cat > "$TMP/out_pkg_mcpfoo/app/src/main/java/com/example/Out.kt" <<'KT'
package com.example

val x = dev.librepocket.mcpfoo.X
KT
expect_pass "$TMP/out_pkg_mcpfoo" \
  "outside dev.librepocket.mcpfoo reference passes (S6 boundary pin)"

# 1ad. fix4 S6 positive pin: the allowlist test runs on the normalised import
# (trailing block comment dropped).
cp -r "$TMP/clean" "$TMP/in_import_block_comment"
cat > "$TMP/in_import_block_comment/app/src/main/java/dev/librepocket/mcp/Commented.kt" <<'KT'
package dev.librepocket.mcp

import dev.librepocket.tool.ToolDef /* ok */

internal class Commented(private val def: ToolDef)
KT
expect_pass "$TMP/in_import_block_comment" \
  "allowlisted import with a block-comment tail passes (S6 pin)"

# 1ae. fix4 follow-up: Java text blocks are not raw strings. `\"""` is an
# escaped quote plus two quotes, not a closing delimiter. If the lexer treats
# it as a delimiter, the rest of the text block becomes code (forging a
# package) and the real closing `"""` then swallows the following code. A
# default-package file would be classified IN, C3 would allow the self-import,
# and C4 would skip the `McpStub` field => zero-wiring bypass.
cp -r "$TMP/clean" "$TMP/java_text_block_escape"
mkdir -p "$TMP/java_text_block_escape/app/src/main/java/com/example"
cat > "$TMP/java_text_block_escape/app/src/main/java/com/example/TextBlock.java" <<'JAVA'
import dev.librepocket.mcp.McpStub;

@SuppressWarnings("""
x \""" package dev.librepocket.mcp
""")
public class TextBlock {
    McpStub s;
}
JAVA
expect_fail "$TMP/java_text_block_escape" \
  "Java text-block escaped quote does not forge a package declaration (fix4)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 1af. fix4 follow-up: a legal Java-style semicolon plus trailing line
# comment on an allowlisted import is normalised (comment dropped, `;`
# dropped) instead of being rejected as non-allowlisted.
cp -r "$TMP/clean" "$TMP/in_import_semicolon_comment"
cat > "$TMP/in_import_semicolon_comment/app/src/main/java/dev/librepocket/mcp/Semi.kt" <<'KT'
package dev.librepocket.mcp

import dev.librepocket.tool.ToolDef; // allowed contract

internal class Semi(private val def: ToolDef)
KT
expect_pass "$TMP/in_import_semicolon_comment" \
  "allowlisted import with semicolon + line comment passes (fix4)"

# 1ag. fix4 follow-up: a tab after `import` is still an import (the shell
# case class is [[:space:]], not a literal space).
cp -r "$TMP/clean" "$TMP/in_import_tab"
printf 'package dev.librepocket.mcp\n\nimport\tdev.librepocket.tool.ToolDef\n\ninternal class TabImport(private val def: ToolDef)\n' \
  > "$TMP/in_import_tab/app/src/main/java/dev/librepocket/mcp/TabImport.kt"
expect_pass "$TMP/in_import_tab" \
  "tab-separated allowlisted import passes (fix4)"

# 1ai. fix4 follow-up: the multi-import fallback must inspect the normalised
# (comment-stripped) line, so a trailing comment containing `; import …` on an
# allowlisted import is not mistaken for a second declaration.
cp -r "$TMP/clean" "$TMP/in_import_comment_fake_second"
cat > "$TMP/in_import_comment_fake_second/app/src/main/java/dev/librepocket/mcp/ImportComment.kt" <<'KT'
package dev.librepocket.mcp

import dev.librepocket.tool.ToolDef /* ; import dev.librepocket.router.PrivilegeGate */

internal class ImportComment(private val def: ToolDef)
KT
expect_pass "$TMP/in_import_comment_fake_second" \
  "allowlisted import with fake second import in a comment passes (fix4)"

# 1ah. fix4 follow-up: C4 comment stripping must be string-aware. A `//`
# inside a URL string used to truncate the line and hide a later MCP FQN.
cp -r "$TMP/clean" "$TMP/outside_url_string"
mkdir -p "$TMP/outside_url_string/app/src/main/java/dev/librepocket/agent"
cat > "$TMP/outside_url_string/app/src/main/java/dev/librepocket/agent/UrlString.kt" <<'KT'
package dev.librepocket.agent

fun wireMcp() {
    register("https://mcp.example.com/rpc", dev.librepocket.mcp.McpServer())
}
KT
expect_fail "$TMP/outside_url_string" \
  "C4 keeps scanning after // inside a string literal (fix4)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

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

# 2b. fix4 M2: a legal double-space import used to miss the exact-prefix
# `case` and hit the early `continue` without any allowlist report.
cp -r "$TMP/clean" "$TMP/in_import_spaces"
cat > "$TMP/in_import_spaces/app/src/main/java/dev/librepocket/mcp/Spaced.kt" <<'KT'
package dev.librepocket.mcp

import  dev.librepocket.router.PrivilegeGate

internal class Spaced(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/in_import_spaces" \
  "double-space outbound import is still checked (M2)" \
  "outbound import not on allowlist"

# 2c. fix4 M2: Java `import static …` also used to fall through the
# exact-prefix `case`.
cp -r "$TMP/clean" "$TMP/in_import_static"
cat > "$TMP/in_import_static/app/src/main/java/dev/librepocket/mcp/StaticWired.java" <<'JAVA'
package dev.librepocket.mcp;

import static dev.librepocket.router.PrivilegeGate.check;

public class StaticWired { }
JAVA
expect_fail "$TMP/in_import_static" \
  "static outbound import is still checked (M2)" \
  "outbound import not on allowlist"

# 2d. fix4 M2 positive control: Java same-line package + allowlisted import.
cp -r "$TMP/clean" "$TMP/in_import_same_line"
cat > "$TMP/in_import_same_line/app/src/main/java/dev/librepocket/mcp/SameLine.java" <<'JAVA'
package dev.librepocket.mcp; import dev.librepocket.tool.ToolDef;

public class SameLine { }
JAVA
expect_pass "$TMP/in_import_same_line" \
  "Java same-line allowlisted import passes (M2)"

# 2e. fix4 M2 positive control: a subpackage file may import a parent-package
# mcp type (not outbound).
cp -r "$TMP/clean" "$TMP/in_import_parent"
mkdir -p "$TMP/in_import_parent/app/src/main/java/dev/librepocket/mcp/transport"
cat > "$TMP/in_import_parent/app/src/main/java/dev/librepocket/mcp/transport/Parent.java" <<'JAVA'
package dev.librepocket.mcp.transport;

import dev.librepocket.mcp.McpServer;

public class Parent {
    McpServer server;
}
JAVA
expect_pass "$TMP/in_import_parent" \
  "subpackage importing a parent mcp type passes (M2)"

# 2f. fix4 follow-up: block comments before AND after the outbound module
# path must not hide the import. Two comments on one line also pin the
# non-greedy comment removal (a greedy `.*` would swallow the path itself).
cp -r "$TMP/clean" "$TMP/in_import_block_prefix"
cat > "$TMP/in_import_block_prefix/app/src/main/java/dev/librepocket/mcp/BlockPrefix.kt" <<'KT'
package dev.librepocket.mcp

import /* outbound */ dev.librepocket.router.PrivilegeGate /* tail */;

internal class BlockPrefix(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/in_import_block_prefix" \
  "block-comment-prefixed outbound import is still checked (fix4)" \
  "outbound import not on allowlist"

# 2g. fix4 follow-up: a block comment between package segments of a
# fully-qualified outbound use is normalised before the leftover check.
cp -r "$TMP/clean" "$TMP/in_fqn_block_comment"
cat > "$TMP/in_fqn_block_comment/app/src/main/java/dev/librepocket/mcp/BlockFqn.kt" <<'KT'
package dev.librepocket.mcp

internal fun rogue() = dev./* no-op */librepocket.router.PrivilegeGate
KT
expect_fail "$TMP/in_fqn_block_comment" \
  "block-comment-split outbound FQN is still checked (fix4)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2h. fix4 follow-up: a string containing the comment delimiters must not let
# the C3 block-comment normaliser swallow a real outbound FQN on the line.
cp -r "$TMP/clean" "$TMP/in_fqn_string_comment"
cat > "$TMP/in_fqn_string_comment/app/src/main/java/dev/librepocket/mcp/StringComment.kt" <<'KT'
package dev.librepocket.mcp

internal fun rogue() {
    val s = "/*"; val x = dev.librepocket.router.PrivilegeGate; val t = "*/"
}
KT
expect_fail "$TMP/in_fqn_string_comment" \
  "string comment delimiters do not hide an outbound FQN (fix4)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2i. fix4 follow-up: a backticked first package segment in an outbound
# import is legal Kotlin and must still reach the allowlist check.
cp -r "$TMP/clean" "$TMP/in_import_backtick"
cat > "$TMP/in_import_backtick/app/src/main/java/dev/librepocket/mcp/BacktickImport.kt" <<'KT'
package dev.librepocket.mcp

import `dev`.librepocket.router.PrivilegeGate

internal class BacktickImport(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/in_import_backtick" \
  "backticked outbound import segment is still checked (fix4)" \
  "outbound import not on allowlist"

# 2j. fix4 follow-up: two import declarations on one physical line; the
# leftover scan catches the second (outbound) import.
cp -r "$TMP/clean" "$TMP/in_import_two_on_line"
cat > "$TMP/in_import_two_on_line/app/src/main/java/dev/librepocket/mcp/TwoImports.java" <<'JAVA'
package dev.librepocket.mcp;

import java.util.concurrent.TimeUnit; import dev.librepocket.router.PrivilegeGate;

public class TwoImports { }
JAVA
expect_fail "$TMP/in_import_two_on_line" \
  "second outbound import on one line is still checked (fix4)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2k. fix4 follow-up: a block comment between the `;` and the second import
# must not hide the multi-import form from the fallback guard.
cp -r "$TMP/clean" "$TMP/in_import_two_on_line_comment"
cat > "$TMP/in_import_two_on_line_comment/app/src/main/java/dev/librepocket/mcp/GapImport.kt" <<'KT'
package dev.librepocket.mcp

import java.util.concurrent.TimeUnit; /* gap */ import dev.librepocket.router.PrivilegeGate;

internal class GapImport(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/in_import_two_on_line_comment" \
  "comment-gapped second outbound import is still checked (fix4)" \
  "outbound reference from inside dev/librepocket/mcp/"

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
cat > "$TMP/manifest/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name="dev.librepocket.mcp.McpService" />
</manifest>
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
cat > "$TMP/rel_manifest/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".Transport" />
</manifest>
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
cat > "$TMP/rel_manifest_sq/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name='.mcp.Transport' />
</manifest>
XML
expect_fail "$TMP/rel_manifest_sq" \
  "single-quoted multi-segment relative component resolving into mcp package is rejected" \
  "Android manifest registers MCP (relative component .Transport)"

# 6c. Stage6 F1: legal XML with whitespace around `=` must not bypass the
# manifest scan (the old regex only matched `android:name="` with no spaces).
cp -r "$TMP/clean" "$TMP/rel_manifest_eq_space"
cat > "$TMP/rel_manifest_eq_space/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/rel_manifest_eq_space/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name = ".MainActivity" />
  <service android:name = ".mcp.Transport" />
</manifest>
XML
expect_fail "$TMP/rel_manifest_eq_space" \
  "manifest relative component with spaces around '=' is rejected (F1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6d. Stage6 F1: attribute split across lines (XML whitespace is insignificant).
cp -r "$TMP/clean" "$TMP/rel_manifest_multiline"
cat > "$TMP/rel_manifest_multiline/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/rel_manifest_multiline/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service
      android:name
      = ".mcp.Transport" />
</manifest>
XML
expect_fail "$TMP/rel_manifest_multiline" \
  "multi-line manifest relative component is rejected (F1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6e. Stage6 F1: namespace-prefix alias for the Android namespace — matched
# by attribute local-name, not by the literal `android:` prefix.
cp -r "$TMP/clean" "$TMP/rel_manifest_prefix"
cat > "$TMP/rel_manifest_prefix/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/rel_manifest_prefix/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:a="http://schemas.android.com/apk/res/android">
  <activity a:name=".MainActivity" />
  <service a:name=".mcp.Transport" />
</manifest>
XML
expect_fail "$TMP/rel_manifest_prefix" \
  "namespace-prefix alias manifest component is rejected (F1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6f. Stage6 F1: unqualified name (no leading dot, no dot at all) is resolved
# against the package namespace conservatively; it must not slip through.
cp -r "$TMP/clean" "$TMP/rel_manifest_unqualified"
cat > "$TMP/rel_manifest_unqualified/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/rel_manifest_unqualified/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name="Transport" />
</manifest>
XML
expect_fail "$TMP/rel_manifest_unqualified" \
  "unqualified manifest component resolving into mcp package is rejected (F1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6g. Stage6 F1: placeholder naming an mcp component fails closed; ordinary
# placeholders such as ${applicationId} are covered by the clean fixture.
cp -r "$TMP/clean" "$TMP/manifest_placeholder"
cat > "$TMP/manifest_placeholder/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name="${mcpComponent}" />
</manifest>
XML
expect_fail "$TMP/manifest_placeholder" \
  "manifest placeholder naming an mcp component is rejected (F1)" \
  'Android manifest registers MCP (placeholder component ${mcpComponent} could resolve to the mcp package; fail closed)'

# 6h. Stage6 F1: a malformed AndroidManifest must fail closed (the XML layer
# cannot prove the file is safe if it does not parse).
cp -r "$TMP/clean" "$TMP/manifest_malformed"
cat > "$TMP/manifest_malformed/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
XML
expect_fail "$TMP/manifest_malformed" \
  "malformed AndroidManifest fails closed (F1)" \
  "cannot parse AndroidManifest XML (fail closed)"

# 6i. Stage6 F1 positive control: a basename that exists only under a test
# source set is not production code, so it must not turn the manifest red.
cp -r "$TMP/clean" "$TMP/rel_manifest_test_only"
mkdir -p "$TMP/rel_manifest_test_only/app/src/test/java/dev/librepocket/mcp"
cat > "$TMP/rel_manifest_test_only/app/src/test/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/rel_manifest_test_only/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".Transport" />
</manifest>
XML
expect_pass "$TMP/rel_manifest_test_only" \
  "test-source-only basename does not turn the manifest red (F1)"

# 6j. Stage6 A4: entity-encoded dots defeat the raw text grep
# (`dev.librepocket&#46;mcp&#46;SomeService` has no literal `.`), so only the
# XML FQN branch can catch it. Pins that branch.
cp -r "$TMP/clean" "$TMP/manifest_entity_fqn"
cat > "$TMP/manifest_entity_fqn/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name="dev.librepocket&#46;mcp&#46;SomeService" />
</manifest>
XML
expect_fail "$TMP/manifest_entity_fqn" \
  "entity-encoded FQN manifest component is rejected (A4)" \
  "Android manifest registers MCP"

# 6k. Stage6 A1 positive control: a non-component `name` (meta-data) is data,
# not a class reference — it must not be basename-matched even when a
# same-named production type exists in the mcp package.
cp -r "$TMP/clean" "$TMP/manifest_meta_name"
cat > "$TMP/manifest_meta_name/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/manifest_meta_name/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <meta-data android:name="Transport" android:value="fixture" />
</manifest>
XML
expect_pass "$TMP/manifest_meta_name" \
  "meta-data name is not treated as a component basename (A1)"

# 6l. Stage6 A1: component-reference attribute targetActivity.
cp -r "$TMP/clean" "$TMP/manifest_target_activity"
cat > "$TMP/manifest_target_activity/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/manifest_target_activity/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" android:targetActivity=".mcp.Transport" />
</manifest>
XML
expect_fail "$TMP/manifest_target_activity" \
  "targetActivity reference into the mcp package is rejected (A1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6m. Stage6 A1: component-reference attribute parentActivityName.
cp -r "$TMP/clean" "$TMP/manifest_parent_activity"
cat > "$TMP/manifest_parent_activity/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/manifest_parent_activity/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" android:parentActivityName=".mcp.Transport" />
</manifest>
XML
expect_fail "$TMP/manifest_parent_activity" \
  "parentActivityName reference into the mcp package is rejected (A1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6n. Stage6 A2: declared type names count, not just file basenames. Here
# `class Transport` lives in Wire.kt, so filename-only matching would miss it.
cp -r "$TMP/clean" "$TMP/manifest_type_name"
cat > "$TMP/manifest_type_name/app/src/main/java/dev/librepocket/mcp/Wire.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
cat > "$TMP/manifest_type_name/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".Transport" />
</manifest>
XML
expect_fail "$TMP/manifest_type_name" \
  "declared type in a differently named file is rejected (A2)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6o. Stage6 A2: nested class basename `.mcp.Outer$Inner` is reduced to the
# segment after `$` (the outer/inner split is not a package dot).
cp -r "$TMP/clean" "$TMP/manifest_nested_type"
cat > "$TMP/manifest_nested_type/app/src/main/java/dev/librepocket/mcp/Outer.kt" <<'KT'
package dev.librepocket.mcp

internal class Outer {
    internal class Inner
}
KT
cat > "$TMP/manifest_nested_type/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".mcp.Outer$Inner" />
</manifest>
XML
expect_fail "$TMP/manifest_nested_type" \
  "nested-class manifest basename resolving into the mcp package is rejected (A2)" \
  "Android manifest registers MCP (relative component .Inner)"

# 6p. Stage6 A3: gradle manifestPlaceholders + MCP reference.
cp -r "$TMP/clean" "$TMP/gradle_placeholder"
cat >> "$TMP/gradle_placeholder/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        manifestPlaceholders = ["mcpService": "dev.librepocket.mcp.McpService"]
    }
}
KTS
expect_fail "$TMP/gradle_placeholder" \
  "manifestPlaceholders requesting an MCP component is rejected (A3)" \
  "manifest placeholders request MCP components"

# 6q. Stage6 A3: custom manifest path bypasses the AndroidManifest*.xml scan.
cp -r "$TMP/clean" "$TMP/gradle_srcfile"
cat >> "$TMP/gradle_srcfile/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        manifest.srcFile("src/main/OtherManifest.xml")
    }
}
KTS
expect_fail "$TMP/gradle_srcfile" \
  "custom manifest.srcFile is rejected (A3)" \
  "custom manifest srcFile bypasses MCP manifest scan"

# 6r. Stage6 A3: namespace/applicationId claiming the mcp package.
cp -r "$TMP/clean" "$TMP/gradle_application_id"
cat >> "$TMP/gradle_application_id/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        applicationId = "dev.librepocket.mcp"
    }
}
KTS
expect_fail "$TMP/gradle_application_id" \
  "applicationId set to the mcp package is rejected (A3)" \
  "namespace/applicationId requests MCP package"

# 6s. Stage6 A1: <manifest package="dev.librepocket.mcp…"> in entity form —
# the raw text grep sees no literal dot, so only the XML package branch can
# catch it (Stage6 fix2).
cp -r "$TMP/clean" "$TMP/manifest_package"
cat > "$TMP/manifest_package/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.librepocket&#46;mcp&#46;legacy">
  <activity android:name=".MainActivity" />
</manifest>
XML
expect_fail "$TMP/manifest_package" \
  "manifest package attribute claiming the mcp package is rejected (A1)" \
  "Android manifest declares MCP package"

# 6t. Stage6 fix2: every component element resolving `.Transport` into mcp.
for el in application activity activity-alias receiver provider \
  instrumentation uses-library uses-native-library; do
  dir="$TMP/manifest_el_${el//-/_}"
  cp -r "$TMP/clean" "$dir"
  cat > "$dir/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
  cat > "$dir/app/src/main/AndroidManifest.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <$el android:name=".Transport" />
</manifest>
XML
  expect_fail "$dir" \
    "component element <$el> resolving .Transport into mcp is rejected" \
    "Android manifest registers MCP (relative component .Transport)"
done

# 6u. Stage6 fix2: component-reference attributes on <application>.
for attr in backupAgent manageSpaceActivity appComponentFactory; do
  dir="$TMP/manifest_ref_$attr"
  cp -r "$TMP/clean" "$dir"
  cat > "$dir/app/src/main/java/dev/librepocket/mcp/Transport.kt" <<'KT'
package dev.librepocket.mcp

internal class Transport
KT
  cat > "$dir/app/src/main/AndroidManifest.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:$attr=".mcp.Transport" />
</manifest>
XML
  expect_fail "$dir" \
    "component-reference attribute $attr into mcp is rejected" \
    "Android manifest registers MCP (relative component .Transport)"
done

# 6v. Stage6 fix2: KTS namespace = dev.librepocket.mcp.
cp -r "$TMP/clean" "$TMP/gradle_namespace"
cat >> "$TMP/gradle_namespace/app/build.gradle.kts" <<'KTS'

android {
    namespace = "dev.librepocket.mcp"
}
KTS
expect_fail "$TMP/gradle_namespace" \
  "namespace set to the mcp package is rejected (fix2)" \
  "namespace/applicationId requests MCP package"

# 6w. Stage6 fix2: Groovy applicationId "dev.librepocket.mcp" (no `=`).
cp -r "$TMP/clean" "$TMP/gradle_groovy_id"
cat > "$TMP/gradle_groovy_id/app/inject.gradle" <<'GROOVY'
android {
    defaultConfig {
        applicationId "dev.librepocket.mcp"
    }
}
GROOVY
expect_fail "$TMP/gradle_groovy_id" \
  "Groovy applicationId set to the mcp package is rejected (fix2)" \
  "namespace/applicationId requests MCP package"

# 6x. Stage6 fix2 (S2): set-level co-occurrence — the placeholder lives in one
# gradle file and the MCP literal in another (root extra). Same-file matching
# would stay green.
cp -r "$TMP/clean" "$TMP/gradle_cross_file"
cat > "$TMP/gradle_cross_file/build.gradle.kts" <<'KTS'
extra["svc"] = "dev.librepocket.mcp.McpService"
KTS
cat >> "$TMP/gradle_cross_file/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        manifestPlaceholders = ["svc": rootProject.extra["svc"]]
    }
}
KTS
expect_fail "$TMP/gradle_cross_file" \
  "cross-file placeholder + root extra MCP literal is rejected (S2)" \
  "manifest placeholders request MCP components"

# 6y. Stage6 fix2 (S2): gradle.properties carries the MCP literal consumed via
# project.property(...).
cp -r "$TMP/clean" "$TMP/gradle_properties"
printf '%s\n' 'svc=dev.librepocket.mcp.McpService' > "$TMP/gradle_properties/gradle.properties"
cat >> "$TMP/gradle_properties/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        manifestPlaceholders = ["svc": project.property("svc")]
    }
}
KTS
expect_fail "$TMP/gradle_properties" \
  "gradle.properties MCP literal is rejected (S2)" \
  "gradle.properties exposes MCP components"

# 6z. Stage6 fix2 (S2): getManifest().setSrcFile(...) getter form.
cp -r "$TMP/clean" "$TMP/gradle_srcfile_getter"
cat >> "$TMP/gradle_srcfile_getter/app/build.gradle.kts" <<'KTS'

android.sourceSets.getByName("main").getManifest().setSrcFile(file("src/main/Other.xml"))
KTS
expect_fail "$TMP/gradle_srcfile_getter" \
  "getManifest().setSrcFile is rejected (S2)" \
  "custom manifest srcFile bypasses MCP manifest scan"

# 6aa. Stage6 fix2: a manifest comment mentioning MCP still turns the gate red
# (the text-grep layer is deliberately defense-in-depth).
cp -r "$TMP/clean" "$TMP/manifest_comment"
cat > "$TMP/manifest_comment/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <!-- dev.librepocket.mcp.McpService -->
</manifest>
XML
expect_fail "$TMP/manifest_comment" \
  "manifest comment mentioning MCP is rejected" \
  "Android manifest registers MCP"

# 6ab. Stage6 fix2: typealias names count for relative resolution (TYPE_DECL is
# deliberately over-approximate — matching extra names only fails closed).
cp -r "$TMP/clean" "$TMP/manifest_typealias"
cat > "$TMP/manifest_typealias/app/src/main/java/dev/librepocket/mcp/Alias.kt" <<'KT'
package dev.librepocket.mcp

typealias Transport = String
KT
cat > "$TMP/manifest_typealias/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".Transport" />
</manifest>
XML
expect_fail "$TMP/manifest_typealias" \
  "typealias name counts for relative manifest resolution (fix2)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6ad. Stage6 fix3 P0-3: `addManifestPlaceholders(mapOf(...))` is
# case-insensitive placeholder wiring (the old grep only knew the exact
# `manifestPlaceholders` spelling) and the manifest consumes `${svc}`.
cp -r "$TMP/clean" "$TMP/gradle_add_placeholder"
cat >> "$TMP/gradle_add_placeholder/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        addManifestPlaceholders(mapOf("svc" to "dev.librepocket.mcp.McpService"))
    }
}
KTS
cat > "$TMP/gradle_add_placeholder/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name="${svc}" />
</manifest>
XML
expect_fail "$TMP/gradle_add_placeholder" \
  "addManifestPlaceholders requesting an MCP component is rejected (P0-3)" \
  "manifest placeholders request MCP components"

# 6ae. Stage6 fix3 P1: setNamespace(...) setter form.
cp -r "$TMP/clean" "$TMP/gradle_set_namespace"
cat >> "$TMP/gradle_set_namespace/app/build.gradle.kts" <<'KTS'

android {
    setNamespace("dev.librepocket.mcp")
}
KTS
expect_fail "$TMP/gradle_set_namespace" \
  "setNamespace requesting the mcp package is rejected (P1)" \
  "namespace/applicationId requests MCP package"

# 6af. Stage6 fix3 P1: setApplicationId(...) setter form.
cp -r "$TMP/clean" "$TMP/gradle_set_app_id"
cat >> "$TMP/gradle_set_app_id/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        setApplicationId("dev.librepocket.mcp")
    }
}
KTS
expect_fail "$TMP/gradle_set_app_id" \
  "setApplicationId requesting the mcp package is rejected (P1)" \
  "namespace/applicationId requests MCP package"

# 6ag. Stage6 fix3 P1: getter property form `getManifest().srcFile = ...`
# (the old rule only knew `setSrcFile`).
cp -r "$TMP/clean" "$TMP/gradle_srcfile_property"
cat >> "$TMP/gradle_srcfile_property/app/build.gradle.kts" <<'KTS'

android.sourceSets.getByName("main").getManifest().srcFile = file("src/main/Other.xml")
KTS
expect_fail "$TMP/gradle_srcfile_property" \
  "getManifest().srcFile property form is rejected (P1)" \
  "custom manifest srcFile bypasses MCP manifest scan"

# 6ah. Stage6 fix3 P1: gradle `\uXXXX` escapes are decoded before matching.
cp -r "$TMP/clean" "$TMP/gradle_namespace_escape"
cat >> "$TMP/gradle_namespace_escape/app/build.gradle.kts" <<'KTS'

android {
    namespace = "\u0064ev.librepocket.mcp"
}
KTS
expect_fail "$TMP/gradle_namespace_escape" \
  "Unicode-escaped gradle namespace is decoded and rejected (P1)" \
  "namespace/applicationId requests MCP package"

# 6ai. Stage6 fix3 P1: gradle.properties `\uXXXX` escape.
cp -r "$TMP/clean" "$TMP/gradle_properties_escape"
printf '%s\n' 'svc=dev.librepocket.\u006dcp.Transport' \
  > "$TMP/gradle_properties_escape/gradle.properties"
expect_fail "$TMP/gradle_properties_escape" \
  "Unicode-escaped gradle.properties MCP literal is rejected (P1)" \
  "gradle.properties exposes MCP components"

# 6aj. Stage6 fix3 positive control: placeholders whose value is a
# dev.librepocket.* but NOT the mcp package must not false-kill.
cp -r "$TMP/clean" "$TMP/gradle_placeholder_nonmcp"
cat >> "$TMP/gradle_placeholder_nonmcp/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        addManifestPlaceholders(mapOf("svc" to "dev.librepocket.agent.Service"))
    }
}
KTS
expect_pass "$TMP/gradle_placeholder_nonmcp" \
  "non-mcp manifestPlaceholders value passes (fix3)"

# 6al. fix4 S2: `dev.librepocket.mcp.legacy` is a subpackage, not a
# lookalike — the namespace boundary must reject it.
cp -r "$TMP/clean" "$TMP/gradle_namespace_subpkg"
cat >> "$TMP/gradle_namespace_subpkg/app/build.gradle.kts" <<'KTS'

android {
    namespace = "dev.librepocket.mcp.legacy"
}
KTS
expect_fail "$TMP/gradle_namespace_subpkg" \
  "namespace dev.librepocket.mcp.legacy is rejected (S2)" \
  "namespace/applicationId requests MCP package"

# 6am. fix4 S2 positive control: mcpfoo is still a different package.
cp -r "$TMP/clean" "$TMP/gradle_namespace_mcpfoo_subpkg"
cat >> "$TMP/gradle_namespace_mcpfoo_subpkg/app/build.gradle.kts" <<'KTS'

android {
    namespace = "dev.librepocket.mcpfoo"
}
KTS
expect_pass "$TMP/gradle_namespace_mcpfoo_subpkg" \
  "namespace dev.librepocket.mcpfoo is not the mcp package (S2)"

# 6an. fix4 S3: defensive `manifest.setSrcFile(...)` spelling. AGP's real API
# is `manifest.srcFile(...)`; the setter spelling is guarded anyway.
cp -r "$TMP/clean" "$TMP/gradle_srcfile_setter"
cat >> "$TMP/gradle_srcfile_setter/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        manifest.setSrcFile(file("src/main/Other.xml"))
    }
}
KTS
expect_fail "$TMP/gradle_srcfile_setter" \
  "defensive manifest.setSrcFile spelling is rejected (S3)" \
  "custom manifest srcFile bypasses MCP manifest scan"

# 6ao. fix4 S4: the namespace value on the following line. The per-line scan
# misses it; the whole-file scan catches it.
cp -r "$TMP/clean" "$TMP/gradle_namespace_multiline"
cat >> "$TMP/gradle_namespace_multiline/app/build.gradle.kts" <<'KTS'

android {
    namespace =
        "dev.librepocket.mcp"
}
KTS
expect_fail "$TMP/gradle_namespace_multiline" \
  "cross-line namespace value is rejected (S4)" \
  "namespace/applicationId requests MCP package"

# 6ap. fix4 S6 pin: manifest package placeholder naming mcp fails closed.
cp -r "$TMP/clean" "$TMP/manifest_package_placeholder"
cat > "$TMP/manifest_package_placeholder/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${mcpPkg}">
  <activity android:name=".MainActivity" />
</manifest>
XML
expect_fail "$TMP/manifest_package_placeholder" \
  "manifest package placeholder naming mcp fails closed (S6 pin)" \
  'Android manifest declares MCP package (placeholder'

# 6aq. fix4 S6 positive pin: manifest package dev.librepocket.mcpfoo is a
# different package (trailing boundary).
cp -r "$TMP/clean" "$TMP/manifest_package_mcpfoo"
cat > "$TMP/manifest_package_mcpfoo/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.librepocket.mcpfoo">
  <activity android:name=".MainActivity" />
</manifest>
XML
expect_pass "$TMP/manifest_package_mcpfoo" \
  "manifest package dev.librepocket.mcpfoo passes (S6 pin)"

# 6ar. fix4 follow-up: Kotlin backticked type names count for relative
# component resolution. The file basename is Bar, so only the declared
# `Transport` name can resolve `.Transport`.
cp -r "$TMP/clean" "$TMP/manifest_backtick_type"
cat > "$TMP/manifest_backtick_type/app/src/main/java/dev/librepocket/mcp/Bar.kt" <<'KT'
package dev.librepocket.mcp

internal class `Transport`
KT
sed -i 's#</manifest>#  <service android:name=".Transport" />\n</manifest>#' \
  "$TMP/manifest_backtick_type/app/src/main/AndroidManifest.xml"
expect_fail "$TMP/manifest_backtick_type" \
  "backticked in-package type resolves a relative component (fix4)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6as. fix4 follow-up: triple-quoted KTS string around the namespace value.
cp -r "$TMP/clean" "$TMP/gradle_namespace_triple"
cat >> "$TMP/gradle_namespace_triple/app/build.gradle.kts" <<'KTS'

android {
    namespace = """dev.librepocket.mcp"""
}
KTS
expect_fail "$TMP/gradle_namespace_triple" \
  "triple-quoted namespace MCP value is rejected (fix4)" \
  "namespace/applicationId requests MCP package"

# 6at. fix4 follow-up: a comment between `namespace` and `=`.
cp -r "$TMP/clean" "$TMP/gradle_namespace_comment"
cat >> "$TMP/gradle_namespace_comment/app/build.gradle.kts" <<'KTS'

android {
    namespace /* build-time package */ = "dev.librepocket.mcp"
}
KTS
expect_fail "$TMP/gradle_namespace_comment" \
  "comment-obfuscated namespace assignment is rejected (fix4)" \
  "namespace/applicationId requests MCP package"

# 6au. fix4 follow-up: Gradle Property `.set(...)` form.
cp -r "$TMP/clean" "$TMP/gradle_applicationid_set"
cat >> "$TMP/gradle_applicationid_set/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        applicationId.set("dev.librepocket.mcp")
    }
}
KTS
expect_fail "$TMP/gradle_applicationid_set" \
  "applicationId.set MCP value is rejected (fix4)" \
  "namespace/applicationId requests MCP package"

# 6av. fix4 follow-up: comments/whitespace around the manifest.srcFile dot.
cp -r "$TMP/clean" "$TMP/gradle_srcfile_dotted"
cat >> "$TMP/gradle_srcfile_dotted/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        manifest /* custom */ . srcFile(file("src/main/Other.xml"))
    }
}
KTS
expect_fail "$TMP/gradle_srcfile_dotted" \
  "comment-split manifest.srcFile is rejected (fix4)" \
  "custom manifest srcFile bypasses MCP manifest scan"

# 6aw. fix4 follow-up positive: triple-quoted lookalike mcpfoo stays green.
cp -r "$TMP/clean" "$TMP/gradle_namespace_triple_mcpfoo"
cat >> "$TMP/gradle_namespace_triple_mcpfoo/app/build.gradle.kts" <<'KTS'

android {
    namespace = """dev.librepocket.mcpfoo"""
}
KTS
expect_pass "$TMP/gradle_namespace_triple_mcpfoo" \
  "triple-quoted namespace mcpfoo passes (fix4)"

# 6ax. fix4 follow-up: an MCP literal in any gradle file is fail-closed even
# when it is not adjacent to a known injection API. Here it feeds a root
# extra consumed by namespace through a dynamic expression.
cp -r "$TMP/clean" "$TMP/gradle_mcp_literal_extra"
cat > "$TMP/gradle_mcp_literal_extra/build.gradle.kts" <<'KTS'
extra["mcpPkg"] = "dev.librepocket.mcp"
KTS
cat > "$TMP/gradle_mcp_literal_extra/app/inject.gradle.kts" <<'KTS'
android {
    namespace = rootProject.extra["mcpPkg"].toString()
}
KTS
expect_fail "$TMP/gradle_mcp_literal_extra" \
  "standalone MCP literal in a gradle file fails closed (fix4)" \
  "gradle build files reference MCP components"

# 6ay. fix4 follow-up: `srcFile` behind an alias / `.apply {}` receiver is
# still a custom-manifest surface; match the token, not the `manifest.` prefix.
cp -r "$TMP/clean" "$TMP/gradle_srcfile_apply"
cat >> "$TMP/gradle_srcfile_apply/app/build.gradle.kts" <<'KTS'

android {
    val mf = sourceSets.getByName("main").manifest
    mf.apply { srcFile("src/main/mcpComponents.xml") }
}
KTS
expect_fail "$TMP/gradle_srcfile_apply" \
  "receiver-less manifest srcFile is rejected (fix4)" \
  "custom manifest srcFile bypasses MCP manifest scan"

# 6az. fix4 follow-up: Java-properties `\` continuation joins before the MCP
# literal scan, so a split FQN cannot hide from gradle.properties.
cp -r "$TMP/clean" "$TMP/gradle_properties_continuation"
cat > "$TMP/gradle_properties_continuation/gradle.properties" <<'PROP'
component=dev.librepocket.mc\
    p.Mc\
    pServer
PROP
cat >> "$TMP/gradle_properties_continuation/app/build.gradle.kts" <<'KTS'

android {
    defaultConfig {
        manifestPlaceholders["component"] =
            providers.gradleProperty("component").get()
    }
}
KTS
expect_fail "$TMP/gradle_properties_continuation" \
  "backslash-continued gradle.properties MCP literal is rejected (fix4)" \
  "gradle.properties exposes MCP components"

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

# 9. discovery fail-closed: unreadable production source. Stage6 fix2: the
# package classifier reads every file first, so this now fails at C1b (read
# error => BAD, fail closed) rather than at the later C4 grep.
cp -r "$TMP/clean" "$TMP/unreadable"
chmod 000 "$TMP/unreadable/app/src/main/java/dev/librepocket/other/Widget.kt"
expect_fail "$TMP/unreadable" \
  "unreadable production source fails closed (package classification)" \
  "package classification failed (fail closed)"
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

# 10b. discovery fail-closed: production sources exist but none declares the
# mcp package (classifier drift / package move) — the zero-IN fail-closed path.
# Stage6 fix3: also rename the moved stub so C4 does not independently fire on
# its `McpStub` class name; this fixture must isolate the zero-IN diagnostic.
cp -r "$TMP/clean" "$TMP/no_in_pkg"
sed -i -e 's/^package dev\.librepocket\.mcp$/package dev.librepocket.other/' \
  -e 's/McpStub/OtherStub/g' \
  "$TMP/no_in_pkg/app/src/main/java/dev/librepocket/mcp/McpStub.kt"
expect_fail "$TMP/no_in_pkg" \
  "zero in-package declarations fails closed (discovery surface)" \
  "no production source declares package dev.librepocket.mcp"

# N8. Stage6 fix2 positive control (S2): applicationId dev.librepocket.mcpfoo
# is a different package — the trailing boundary must keep it green.
cp -r "$TMP/clean" "$TMP/appid_mcpfoo"
sed -i 's|applicationId = "dev\.librepocket\.agent"|applicationId = "dev.librepocket.mcpfoo"|' \
  "$TMP/appid_mcpfoo/app/build.gradle.kts"
expect_pass "$TMP/appid_mcpfoo" \
  "applicationId dev.librepocket.mcpfoo is not the mcp package (S2)"

# N8b. Stage6 fix2 positive control (S2): namespace dev.librepocket.mcpfoo.
cp -r "$TMP/clean" "$TMP/namespace_mcpfoo"
sed -i 's|namespace = "dev\.librepocket\.agent"|namespace = "dev.librepocket.mcpfoo"|' \
  "$TMP/namespace_mcpfoo/app/build.gradle.kts"
expect_pass "$TMP/namespace_mcpfoo" \
  "namespace dev.librepocket.mcpfoo is not the mcp package (S2)"

# Positive control on the real repository (not just synthetic fixtures).
expect_pass "$ROOT" "real repository passes"

if [ "$failures" -ne 0 ]; then
  say "FAILED ($failures case(s))"
  exit 1
fi
say "PASSED (${pos_cases} positive controls incl. real repo, ${neg_cases} negative surfaces, C1–C4 markers)"
