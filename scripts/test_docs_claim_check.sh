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
#  1aj. form feed is JVM whitespace, not a line break: a same-line FQN split by
#      `\f` is still caught (Stage1 C4-FF)
#  1ak. production source outside `*/src` (custom `java.srcDir`) is scanned
#      (Stage1 C1-SRC)
#  1al. production package `…/com/example/src/test/…` under `src/main` is
#      scanned (Stage1 C1-TEST)
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
#  2l. spaced-dot outbound import (Kotlin) is rejected (Stage1 C3-WS)
#  2m. spaced-dot outbound import (Java) is rejected (Stage1 C3-WS)
#  2n. single-space outbound import is rejected (Stage1 C3-WS)
#  2o. same-line package + import + outbound FQN is still scanned (Stage1 F2)
#  2p. string `"/*"` does not desync the outbound FQN scan (Stage1 F3)
#  2q. positive: two individually allowlisted imports on one line pass (C3-OB)
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
#  6aa. manifest comment mentioning MCP pins the raw-text layer (Stage6 fix2)
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
#  6ba. comment-spliced type declaration resolves a relative component (Stage1)
#  6bb. Java `cl\u0061ss` type declaration resolves a relative component (Stage1)
#  6bc. gradle version catalog (libs.versions.toml) MCP literal (Stage1 C2B-TOML)
#  6bd. TOML `\UXXXXXXXX` escape decodes to the mcp package (G-04)
#  6be. (positive) TOML literal string ignores escapes (G-08)
#  6bf. Unicode Java type name resolves a relative component (G-05)
#  6bg. backticked punctuated Kotlin type name resolves a component (G-05)
#  6bh. `$`-leading Java type name resolves a relative component (G-05)
#  6bi. (positive) manifest component `dev.librepocket.mcpé…` is a lookalike
#  6bj. (positive) manifest component `dev.librepocket.mcpfoo…` is a lookalike
#  6bk. TOML multiline basic string `\uXXXX` decodes (G-04)
#  6bl. (positive) TOML multiline literal string ignores escapes (G-08)
#  1am. C4 catches an escape-space-obscured FQN on one line (G-03)
#  1an. same with a tab escape (G-03)
#  1ao. in-package `mcp$Compat` lookalike is outbound (G-06)
#  1ap. in-package `mcpé` lookalike is outbound (G-06)
#  1aq. production package named `build` is scanned (G-07)
#  1ar. custom `extra/java` root containing `src/test` is production (C1-TEST)
#  1as. a file whose package is `dev.librepocket.mcp$Compat` is OUTSIDE (C4)
#  1at. a file whose package is `dev.librepocket.mcpé` is OUTSIDE (C4)
#  2r. Java generic type argument is not a KDoc link (G-01)
#  2s. Kotlin grouped annotation `@[...]` is not a KDoc link (G-01)
#  2t. Kotlin generic type argument is not a KDoc link (G-01)
#  2u. Java import split across lines is fully validated (G-02)
#  2v. block-comment/newline-split Java import is validated (G-02)
#  2w. line-comment-split Java import is validated (G-02)
#  2x. `\u000a`-split Java import is validated (G-02)
#  2y. (positive) allowlisted Java import split across lines (G-09)
#  2z. (positive) static member of an allowlisted type (G-10)
#  2aa. a bracketed FQN inside a string is not a KDoc link
#  1au. in-package `mcp<combining>` lookalike is outbound (G-11)
#  1av. in-package `mcp<ZWJ>` string lookalike is outbound (G-11)
#  1aw. in-package `mcp€X` lookalike is outbound (G-11)
#  6bm. combining-mark type name resolves a relative component (G-12)
#  6bn. ZWJ type name resolves a relative component (G-12)
#  6bo. currency-symbol-leading type name resolves a component (G-12)
#  6bp. `$1Wire` type name resolves a relative component (G-12)
#  1ax. C4 lookalikes with combining/ZWJ/currency parts are not the mcp pkg
#  6bq. manifest lookalikes with combining/ZWJ/currency parts are not mcp
#  2ab. Kotlin spaced-dot FQN in a string is caught (G-15)
#  2ac. Java spaced-dot FQN in a string is caught (G-15)
#  2ad. Kotlin backticked-segment string FQN is caught (G-15)
#  2ae. Java same-line-comment string FQN is caught (G-15)
#  2af. contiguous string FQN control is caught (G-15)
#  1ay. main srcDir("src/test") is a test-into-production config (G-16)
#  1az. symlinked production source fails closed, not silently skipped (G-13)
#  1ba. marker + build package (settings.gradle) is scanned (G-14)
#  1bb. marker + build package (build.gradle.kts) is scanned (G-14)
#  2ag. (positive) backticked Kotlin import alias with a space (O-01)
#  1bc. C1 classifier: package `dev.librepocket.mcp` + U+0301/U+20AC/U+203F/
#      U+200D is a lookalike (OUT), so its real MCP import is C4-scanned (G-17)
#  1bd. C1 classifier: lookalike package with a fully-qualified use, no import
#  1be. (positive) a file in a lookalike package referencing only that package
#      stays green (C4 must not over-flag the lookalike name) (G-17)
#  1bf. (positive) `dev.librepocket.mcp²` is a self-mention: U+00B2 (No) is not
#      a java identifier part (is_ident_part exactness)
#  1bg. only a lookalike declaration: zero real IN files fails closed (G-17)
#  1bh. marker+src spoof inside a package still scans its build/ (G-14c)
#  1bi. (positive) root settings include(":app") prunes the module build (G-14)
#  1bj. (positive) nested include(":feature:mcpui") module build is pruned
#  1bk. (positive) no settings: depth-1 app/build.gradle.kts + src (G-14)
#  1bl. no settings: a depth-2 marker+src spoof cannot prune build/ (G-14)
#  1bm. main srcDir val indirection (G-16b)
#  1bn. main srcDir file(...) (G-16d)
#  1bo. main srcDir string concatenation (G-16e)
#  1bp. main setSrcDirs(listOf(...)) (G-16f)
#  1bq. main srcDirs(listOf(...)) (G-16g)
#  1br. main srcDir project.file(...) (G-16h)
#  1bs. main srcDir $buildDir (G-16i)
#  1bt. main srcDir layout.buildDirectory.dir (G-16j)
#  1bu. main srcDirs += (Groovy assignment form)
#  1bv. unresolvable main srcDir provider fails closed
#  1bw. (positive) srcDir extra/java; srcDir src/main/java; test-source own
#      srcDir; extra/src/test; extra/src/testFixtures; src/test in a package
#      path (P1/P2/P4/P5/P6/P7)
#  1bx. srcDir("build/generated/ksp") stays an accepted fail-closed over-block
#      (P3; explicit main srcDirs under build are not added to discovery)
#  1by. an explicit main receiver on the srcDir call wins over an enclosing test
#      block (F-1: direct call, .apply, .also, sourceSets.test property block);
#      a balanced lambda before `.java.srcDir` is crossed (apply/also/multiline);
#      with(...)/safe-call/asserted-call/require/check receivers resolve; a
#      newline/comment before the with block and chained run/apply/let/also
#      blocks resolve (including a later chain after an earlier lambda); bare
#      and nested wrapper arguments resolve; receiver
#      aliases (direct, after apply, with, sourceSets) resolve or fail closed;
#      an opaque helper/listOf argument mention fails closed (F-1/F-3)
#  1bz. a module literally named build has its build.gradle.kts scanned for a
#      main srcDir and for manifestPlaceholders (F-2 N3/N9); untrusted settings
#      fail closed instead of silently pruning that module; a literal projectDir
#      below build/ keeps its ancestor traversable, including nested production
#      sources; a root build/generated file next to that projectDir is a
#      documented accepted over-block and stays red
#  1ca. the same module's build/src/main sources are still discovered (N3b)
#  1cb. the same module's AndroidManifest is scanned, not pruned as output
#   7. discovery: no manifests at all (fail closed, never silent pass)
#   8. discovery: no */src source roots at all
#   9. discovery: unreadable production source (chmod 000 → scan fails closed)
#  10. discovery: zero production Kotlin/Java sources
# 10b. discovery: no production source declares package dev.librepocket.mcp
#  11. Section A: scripts/tests/*.py referenced by the workflow but absent
#      from docs/TESTING.md (P3-2 subdirectory coverage)
#  12. discovery: the shared helper is missing (fail closed)
#  N8. (positive) applicationId dev.librepocket.mcpfoo (trailing boundary, S2)
#  N8b. (positive) namespace dev.librepocket.mcpfoo (trailing boundary, S2)
# Positive controls: the clean fixture, an in-package path comment (1c), a
# test-source-only basename (6i), a meta-data name (6k), an out-of-directory
# mcp package (1e), an mcp subpackage (1g), the mcpfoo boundaries
# (N8/N8b/1ac/6am/6aq), the comment-split package (1p), the benign Java
# Unicode escape (1q), the non-mcp manifest placeholder (6aj), the
# normalised-import/self-import positives (1ad/1af/1ag/1ai/2d/2e), the
# two-allowlisted-imports-on-one-line positive (2q), the split-allowlisted
# import (2y), the allowlisted static member (2z), the TOML literal string
# (6be), the TOML multiline literal string (6bl), the triple-quoted mcpfoo
# boundary (6aw), the manifest lookalikes (6bi/6bj/6bq), the C4 lookalike
# boundaries (1z/1ax), the backticked Kotlin import alias (2ag), and the REAL
# repository must PASS, and the gate source must
# still carry every C1–C4 section marker (N-B7: a section wrapped in
# `if false` would otherwise stay green).
# Stage1: C1/C2/C3 delegate to stdlib scripts/mcp_scan.py (copied per fixture);
# C4/ESC split on \n only and decode Java `\uXXXX`; C2b scans *.toml catalogs
# with a TOML-aware (basic-vs-literal string) escape decoder.
# Counts are runtime counters (authoritative: the final say() line).
# Stdlib only (bash/grep/find + the gate's own python3/yaml), CWD-independent,
# temp dirs cleaned on exit. Exit non-zero on any mismatch.
# --- minimal repo that must pass every gate section -------------------------
make_template() {
  local d="$1"
  mkdir -p "$d/scripts" "$d/.github/workflows" "$d/docs" "$d/gradle/wrapper" \
    "$d/app/src/main/java/dev/librepocket/mcp" \
    "$d/app/src/main/java/dev/librepocket/other"
  cp "$GATE" "$d/scripts/docs_claim_check.sh"
  cp "${GATE%/*}/mcp_scan.py" "$d/scripts/mcp_scan.py"

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

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$ROOT/scripts/docs_claim_check.sh"
HELPER="$ROOT/scripts/mcp_scan.py"
if [ ! -f "$GATE" ]; then
  echo "test_docs_claim_check: missing gate at $GATE"
  exit 1
fi
if [ ! -r "$HELPER" ]; then
  echo "test_docs_claim_check: missing or unreadable helper at $HELPER"
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
pos_cases=0
neg_cases=0
say() { echo "test_docs_claim_check: $1"; }

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

# 1aj. Stage1 C4-FF: form feed is ordinary JVM whitespace, not a line
# terminator. The old `str.splitlines()` cut `dev.librepocket.<FF>mcp.X` into
# two lines (neither matching) and the reference escaped. The scan must split
# on `\n` only and collapse JVM whitespace (incl. `\f`) around the dot.
cp -r "$TMP/clean" "$TMP/outside_form_feed"
mkdir -p "$TMP/outside_form_feed/app/src/main/java/com/example"
{ printf 'package com.example;\n\npublic class Use {\n  Object a = dev.librepocket.';
  printf '\014';
  printf 'mcp.OneShotJsonBody.class;\n}\n'; } \
  > "$TMP/outside_form_feed/app/src/main/java/com/example/Use.java"
expect_fail "$TMP/outside_form_feed" \
  "form-feed-separated FQN on one physical line is caught (Stage1)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 1ak. Stage1 C1-SRC: a production source root outside `*/src` (configured
# through a custom `java.srcDir(...)`) used to be invisible to the whole
# C1/C3/C4 machinery. The helper discovers every production `.kt`/`.java` file
# repo-wide, so this outside reference is scanned.
cp -r "$TMP/clean" "$TMP/outside_srcdir"
mkdir -p "$TMP/outside_srcdir/app/extra/java/com/example"
cat > "$TMP/outside_srcdir/app/extra/java/com/example/Wired.kt" <<'KT'
package com.example

import dev.librepocket.mcp.McpServer

internal class Wired(private val s: McpServer)
KT
cat >> "$TMP/outside_srcdir/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/java")
}
KTS
expect_fail "$TMP/outside_srcdir" \
  "production source outside */src (custom srcDir) is scanned (Stage1)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1al. Stage1 C1-TEST: the old `-not -path '*/src/test*'` exclusion dropped any
# production path containing `/src/test`, including a package literally named
# `com.example.src.test` under a normal `src/main` root. Only the source-set
# component immediately after the module's `src` dir is a test source set.
cp -r "$TMP/clean" "$TMP/outside_src_test_pkg"
mkdir -p "$TMP/outside_src_test_pkg/app/src/main/java/com/example/src/test"
cat > "$TMP/outside_src_test_pkg/app/src/main/java/com/example/src/test/Out.kt" <<'KT'
package com.example.src.test

internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/outside_src_test_pkg" \
  "production package .../src/test/... under src/main is still scanned (Stage1)" \
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

# 2j. fix4 follow-up: two import declarations on one physical line; the second
# (outbound) import is classified independently and rejected (Stage1: the
# helper's import parser no longer relies on the old raw leftover scan).
cp -r "$TMP/clean" "$TMP/in_import_two_on_line"
cat > "$TMP/in_import_two_on_line/app/src/main/java/dev/librepocket/mcp/TwoImports.java" <<'JAVA'
package dev.librepocket.mcp;

import java.util.concurrent.TimeUnit; import dev.librepocket.router.PrivilegeGate;

public class TwoImports { }
JAVA
expect_fail "$TMP/in_import_two_on_line" \
  "second outbound import on one line is still checked (fix4)" \
  "outbound import not on allowlist"

# 2k. fix4 follow-up: a block comment between the `;` and the second import
# must not hide the second declaration.
cp -r "$TMP/clean" "$TMP/in_import_two_on_line_comment"
cat > "$TMP/in_import_two_on_line_comment/app/src/main/java/dev/librepocket/mcp/GapImport.kt" <<'KT'
package dev.librepocket.mcp

import java.util.concurrent.TimeUnit; /* gap */ import dev.librepocket.router.PrivilegeGate;

internal class GapImport(private val gate: PrivilegeGate)
KT
expect_fail "$TMP/in_import_two_on_line_comment" \
  "comment-gapped second outbound import is still checked (fix4)" \
  "outbound import not on allowlist"

# 2l. Stage1 C3-WS: whitespace around the dots of an outbound import is legal
# Java/Kotlin. The helper normalises it before the allowlist test, so it can no
# longer fall through the old exact-prefix `case` and its early `continue`.
cp -r "$TMP/clean" "$TMP/in_import_spaced_dot"
cat > "$TMP/in_import_spaced_dot/app/src/main/java/dev/librepocket/mcp/SpacedDot.kt" <<'KT'
package dev.librepocket.mcp

import dev . librepocket . router . PrivilegeGate

internal class SpacedDot(private val g: PrivilegeGate)
KT
expect_fail "$TMP/in_import_spaced_dot" \
  "spaced-dot outbound import (Kotlin) is rejected (Stage1)" \
  "outbound import not on allowlist"

# 2m. Same, Java grammar (every dot spaced, terminating `;`).
cp -r "$TMP/clean" "$TMP/in_import_spaced_dot_java"
cat > "$TMP/in_import_spaced_dot_java/app/src/main/java/dev/librepocket/mcp/SpacedDot.java" <<'JAVA'
package dev.librepocket.mcp;

import dev . librepocket . router . PrivilegeGate;

public class SpacedDot { PrivilegeGate g; }
JAVA
expect_fail "$TMP/in_import_spaced_dot_java" \
  "spaced-dot outbound import (Java) is rejected (Stage1)" \
  "outbound import not on allowlist"

# 2n. A single space before one dot is enough (the lead's original repro).
cp -r "$TMP/clean" "$TMP/in_import_spaced_dot_single"
cat > "$TMP/in_import_spaced_dot_single/app/src/main/java/dev/librepocket/mcp/SpacedDot.kt" <<'KT'
package dev.librepocket.mcp

import dev.librepocket .router.PrivilegeGate

internal class SpacedDot(private val g: PrivilegeGate)
KT
expect_fail "$TMP/in_import_spaced_dot_single" \
  "single-space outbound import is rejected (Stage1)" \
  "outbound import not on allowlist"

# 2o. Stage1 F2: a package declaration, an (allowed) import and an outbound
# FQN on ONE physical line. The old import branch `continue`d and skipped the
# leftover FQN scan; the helper removes the package/import and scans the rest.
cp -r "$TMP/clean" "$TMP/in_fqn_same_line_class"
cat > "$TMP/in_fqn_same_line_class/app/src/main/java/dev/librepocket/mcp/Rogue.java" <<'JAVA'
package dev.librepocket.mcp; import java.util.List; class Rogue { dev.librepocket.router.PrivilegeGate gate; }
JAVA
expect_fail "$TMP/in_fqn_same_line_class" \
  "same-line import + class + outbound FQN is still scanned (Stage1)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2p. Stage1 F3: a string literal `"/*"` desynchronises a comment stripper that
# is not string-aware, letting it swallow `dev.` and hide the (comment-split)
# outbound FQN. The helper lexes strings, so the comment is blanked correctly
# and the whitespace around the dot is normalised.
cp -r "$TMP/clean" "$TMP/in_fqn_string_desync"
cat > "$TMP/in_fqn_string_desync/app/src/main/java/dev/librepocket/mcp/Desync.kt" <<'KT'
package dev.librepocket.mcp

internal fun rogue() {
    val s = "/*"; val x = dev./* x */librepocket.router.PrivilegeGate; val t = "*/"
}
KT
expect_fail "$TMP/in_fqn_string_desync" \
  "string comment delimiters do not desync the outbound FQN scan (Stage1)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2q. Stage1 C3-OB positive control: two INDIVIDUALLY allowlisted imports
# (ToolDef + KeyVault) on one physical line are not outbound. The old per-line
# `^…$` allowlist test (and the raw leftover fallback) false-red this. The
# helper classifies each `;`-separated import independently.
cp -r "$TMP/clean" "$TMP/in_import_two_allowlisted"
cat > "$TMP/in_import_two_allowlisted/app/src/main/java/dev/librepocket/mcp/TwoAllow.java" <<'JAVA'
package dev.librepocket.mcp;

import dev.librepocket.tool.ToolDef; import dev.librepocket.keystore.KeyVault;

public class TwoAllow { }
JAVA
expect_pass "$TMP/in_import_two_allowlisted" \
  "two individually allowlisted imports on one line pass (Stage1)"

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

# 6j. Stage6 A4: entity-encoded dots defeat the raw text scan
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
# the raw text scan sees no literal dot, so only the XML package branch can
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
# (the raw-text layer is deliberately defense-in-depth).
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
  "namespace dev.librepocket.mcpfoo is not the mcp package (S2/6am)"

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

# 6ba. Stage1 C2-TD: a comment between the type keyword and its name made the
# raw-text TYPE_DECL miss the type, so a relative manifest component resolved
# into the (now-invisible) class. Names come from the lexed view.
cp -r "$TMP/clean" "$TMP/manifest_type_comment"
cat > "$TMP/manifest_type_comment/app/src/main/java/dev/librepocket/mcp/Wire.java" <<'JAVA'
package dev.librepocket.mcp;
class /* c */ Transport {}
JAVA
cat > "$TMP/manifest_type_comment/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".mcp.Transport" />
</manifest>
XML
expect_fail "$TMP/manifest_type_comment" \
  "comment-spliced type declaration still resolves a relative component (Stage1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6bb. Stage1 C2-TD (Java Unicode variant): javac translates `\uXXXX` before
# lexing, so `cl\u0061ss Transport` is a real declaration the raw-text scan
# missed.
cp -r "$TMP/clean" "$TMP/manifest_type_unicode"
cat > "$TMP/manifest_type_unicode/app/src/main/java/dev/librepocket/mcp/Wire.java" <<'JAVA'
package dev.librepocket.mcp;
class Wire {}
cl\u0061ss Transport {}
JAVA
cat > "$TMP/manifest_type_unicode/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".mcp.Transport" />
</manifest>
XML
expect_fail "$TMP/manifest_type_unicode" \
  "Java Unicode-escaped class keyword still resolves a relative component (Stage1)" \
  "Android manifest registers MCP (relative component .Transport)"

# 6bc. Stage1 C2B-TOML: the Gradle version catalog is a committed build input;
# an MCP literal in it (consumed as libs.versions.mcpPkg.get()) must fail closed
# exactly like a gradle.properties literal.
cp -r "$TMP/clean" "$TMP/gradle_toml_catalog"
cat > "$TMP/gradle_toml_catalog/gradle/libs.versions.toml" <<'TOML'
[versions]
agp = "9.0.0"
kotlin = "2.1.0"
compileSdk = "35"
targetSdk = "35"
minSdk = "26"
mcpPkg = "dev.librepocket.mcp"
TOML
sed -i 's|namespace = "dev.librepocket.agent"|namespace = libs.versions.mcpPkg.get()|' \
  "$TMP/gradle_toml_catalog/app/build.gradle.kts"
expect_fail "$TMP/gradle_toml_catalog" \
  "gradle version catalog MCP literal is rejected (Stage1)" \
  "gradle version catalog references MCP components"

# --- Stage1-fix2 regressions: bypasses found by adversarial review ----------
# Each fixture pins the legal construct that used to reach the code view in a
# form the gate could not see, plus the allowlisted/positive inverses.

# 2r. G-01: a Java generic type argument is real code, not a KDoc link. The old
# code-view strip removed `<dev.librepocket.` and hid the outbound type.
cp -r "$TMP/clean" "$TMP/in_fqn_generic_java"
cat > "$TMP/in_fqn_generic_java/app/src/main/java/dev/librepocket/mcp/Gen.java" <<'JAVA'
package dev.librepocket.mcp;
import java.util.List;
class Gen { List<dev.librepocket.router.PrivilegeGate> gates; }
JAVA
expect_fail "$TMP/in_fqn_generic_java" \
  "Java generic type argument is not stripped as a KDoc link (G-01)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2s. G-01 (Kotlin): a grouped annotation `@[...]` is real code too.
cp -r "$TMP/clean" "$TMP/in_grouped_annotation"
cat > "$TMP/in_grouped_annotation/app/src/main/java/dev/librepocket/mcp/Anno.kt" <<'KT'
package dev.librepocket.mcp
@[dev.librepocket.router.Anno]
internal class Anno
KT
expect_fail "$TMP/in_grouped_annotation" \
  "Kotlin grouped annotation is not stripped as a KDoc link (G-01)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2t. G-01 (Kotlin generic): `<dev.librepocket.…>` as a type argument.
cp -r "$TMP/clean" "$TMP/in_fqn_generic_kotlin"
cat > "$TMP/in_fqn_generic_kotlin/app/src/main/java/dev/librepocket/mcp/GenK.kt" <<'KT'
package dev.librepocket.mcp
internal class GenK(private val g: List<dev.librepocket.router.PrivilegeGate>)
KT
expect_fail "$TMP/in_fqn_generic_kotlin" \
  "Kotlin generic type argument is not stripped as a KDoc link (G-01)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2u. G-02: a Java import declaration may span physical lines. The whole
# declaration must be parsed and its COMPLETE path validated.
cp -r "$TMP/clean" "$TMP/in_import_newline_java"
cat > "$TMP/in_import_newline_java/app/src/main/java/dev/librepocket/mcp/SplitJ.java" <<'JAVA'
package dev.librepocket.mcp;
import dev.
librepocket.router.PrivilegeGate;
class SplitJ { PrivilegeGate g; }
JAVA
expect_fail "$TMP/in_import_newline_java" \
  "Java import split across a physical line is fully validated (G-02)" \
  "outbound import not on allowlist"

# 2v. G-02: block comment between package segments of a split import.
cp -r "$TMP/clean" "$TMP/in_import_comment_newline_java"
cat > "$TMP/in_import_comment_newline_java/app/src/main/java/dev/librepocket/mcp/SplitC.java" <<'JAVA'
package dev.librepocket.mcp;
import dev.librepocket./*
*/router.PrivilegeGate;
class SplitC { }
JAVA
expect_fail "$TMP/in_import_comment_newline_java" \
  "comment/newline-split Java import is fully validated (G-02)" \
  "outbound import not on allowlist"

# 2w. G-02: line comment terminates the first physical line of the import.
cp -r "$TMP/clean" "$TMP/in_import_linecomment_java"
cat > "$TMP/in_import_linecomment_java/app/src/main/java/dev/librepocket/mcp/SplitL.java" <<'JAVA'
package dev.librepocket.mcp;
import dev.// split
librepocket.router.PrivilegeGate;
class SplitL { }
JAVA
expect_fail "$TMP/in_import_linecomment_java" \
  "line-comment-split Java import is fully validated (G-02)" \
  "outbound import not on allowlist"

# 2x. G-02: JLS `\u000a` escape is translated before lexing, so the import
# really does continue on the next physical line.
cp -r "$TMP/clean" "$TMP/in_import_escaped_newline_java"
cat > "$TMP/in_import_escaped_newline_java/app/src/main/java/dev/librepocket/mcp/SplitE.java" <<'JAVA'
package dev.librepocket.mcp;
import dev.\u000alibrepocket.router.PrivilegeGate;
class SplitE { }
JAVA
expect_fail "$TMP/in_import_escaped_newline_java" \
  "Unicode-escaped newline in a Java import is fully validated (G-02)" \
  "outbound import not on allowlist"

# 2y. G-09 (+G-02) positive: a Java-legal import split after the keyword, where
# the COMPLETE path is allowlisted, must pass.
cp -r "$TMP/clean" "$TMP/in_import_allowed_split_java"
cat > "$TMP/in_import_allowed_split_java/app/src/main/java/dev/librepocket/mcp/SplitAllow.java" <<'JAVA'
package dev.librepocket.mcp;
import
dev.librepocket.tool.ToolDef;
class SplitAllow { }
JAVA
expect_pass "$TMP/in_import_allowed_split_java" \
  "allowlisted Java import split across lines passes (G-09)"

# 2z. G-10 positive: a static member of an allowlisted contract type is not a
# new outbound type.
cp -r "$TMP/clean" "$TMP/in_static_member_allowlisted"
cat > "$TMP/in_static_member_allowlisted/app/src/main/java/dev/librepocket/mcp/StaticMember.java" <<'JAVA'
package dev.librepocket.mcp;
import static dev.librepocket.tool.ToolDef.DEFAULT;
class StaticMember { }
JAVA
expect_pass "$TMP/in_static_member_allowlisted" \
  "static member import of an allowlisted type passes (G-10)"

# 2aa. G-01 follow-up: comments are blanked in both lexer views, so a `[`/`<`
# before a FQN is never a KDoc link by the time C3 scans. Stripping it from the
# string-kept view hid a bracketed reflective class name.
cp -r "$TMP/clean" "$TMP/in_bracket_string"
cat > "$TMP/in_bracket_string/app/src/main/java/dev/librepocket/mcp/Bracket.kt" <<'KT'
package dev.librepocket.mcp
internal val gate = "[dev.librepocket.router.PrivilegeGate]"
KT
expect_fail "$TMP/in_bracket_string" \
  "bracketed FQN inside a string is not stripped as a KDoc link (G-01)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 1am. G-03: an escape that decodes to a space inside a one-line FQN is still
# an MCP reference (javac translates the escape before tokenising).
cp -r "$TMP/clean" "$TMP/outside_escape_space"
mkdir -p "$TMP/outside_escape_space/app/src/main/java/com/example"
cat > "$TMP/outside_escape_space/app/src/main/java/com/example/Use.java" <<'JAVA'
package com.example;
public class Use { Object x = dev\u0020.librepocket.mcp.OneShotJsonBody.class; }
JAVA
expect_fail "$TMP/outside_escape_space" \
  "escape-space-obscured FQN on one physical line is caught (G-03)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 1an. G-03: same with a tab escape.
cp -r "$TMP/clean" "$TMP/outside_escape_tab"
mkdir -p "$TMP/outside_escape_tab/app/src/main/java/com/example"
cat > "$TMP/outside_escape_tab/app/src/main/java/com/example/Use.java" <<'JAVA'
package com.example;
public class Use { Object x = dev\u0009.librepocket.mcp.OneShotJsonBody.class; }
JAVA
expect_fail "$TMP/outside_escape_tab" \
  "escape-tab-obscured FQN on one physical line is caught (G-03)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# 6bd. G-04: TOML basic strings support `\UXXXXXXXX` (8 hex) as well as
# `\uXXXX`; Gradle's parser resolves it, so it must not hide a catalog literal.
cp -r "$TMP/clean" "$TMP/gradle_toml_u8"
cat > "$TMP/gradle_toml_u8/gradle/libs.versions.toml" <<'TOML'
[versions]
agp = "9.0.0"
kotlin = "2.1.0"
compileSdk = "35"
targetSdk = "35"
minSdk = "26"
mcpPkg = "dev.librepocket\U0000002Emcp"
TOML
sed -i 's|namespace = "dev.librepocket.agent"|namespace = libs.versions.mcpPkg.get()|' \
  "$TMP/gradle_toml_u8/app/build.gradle.kts"
expect_fail "$TMP/gradle_toml_u8" \
  "TOML \\UXXXXXXXX escape is decoded and rejected (G-04)" \
  "gradle version catalog references MCP components"

# 6be. G-08 positive: TOML LITERAL strings do not process escapes, so a
# `\u002e` next to `dev.librepocket` is prose, not the mcp package.
cp -r "$TMP/clean" "$TMP/gradle_toml_literal"
cat >> "$TMP/gradle_toml_literal/gradle/libs.versions.toml" <<'TOML'
note = 'dev.librepocket\u002emcp (literal, not a package)'
TOML
expect_pass "$TMP/gradle_toml_literal" \
  "TOML literal string with an escape stays green (G-08)"

# 6bf. G-05: Unicode Java identifier as a declared type name.
cp -r "$TMP/clean" "$TMP/manifest_type_unicode_ident"
cat > "$TMP/manifest_type_unicode_ident/app/src/main/java/dev/librepocket/mcp/Wire.java" <<'JAVA'
package dev.librepocket.mcp;
class Tränsport {}
JAVA
cat > "$TMP/manifest_type_unicode_ident/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".mcp.Tränsport" />
</manifest>
XML
expect_fail "$TMP/manifest_type_unicode_ident" \
  "Unicode type name resolves a relative manifest component (G-05)" \
  "Android manifest registers MCP (relative component .Tränsport)"

# 6bg. G-05: backticked Kotlin type name with non-identifier punctuation.
cp -r "$TMP/clean" "$TMP/manifest_type_backtick_punct"
cat > "$TMP/manifest_type_backtick_punct/app/src/main/java/dev/librepocket/mcp/Bar.kt" <<'KT'
package dev.librepocket.mcp
internal class `Transport-X`
KT
cat > "$TMP/manifest_type_backtick_punct/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".mcp.Transport-X" />
</manifest>
XML
expect_fail "$TMP/manifest_type_backtick_punct" \
  "backticked punctuated type name resolves a relative component (G-05)" \
  "Android manifest registers MCP (relative component .Transport-X)"

# 6bh. G-05: `$` is a legal Java identifier start, not a terminator. A type
# declared in a differently named file must still enter the C2 name set.
cp -r "$TMP/clean" "$TMP/manifest_type_dollar_ident"
cat > "$TMP/manifest_type_dollar_ident/app/src/main/java/dev/librepocket/mcp/Dollar.java" <<'JAVA'
package dev.librepocket.mcp;
class $Wire {}
JAVA
cat > "$TMP/manifest_type_dollar_ident/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".mcp.$Wire" />
</manifest>
XML
expect_fail "$TMP/manifest_type_dollar_ident" \
  "dollar-leading type name resolves a relative component (G-05)" \
  "Android manifest registers MCP (relative component .\$Wire)"

# 6bi. G-06 positive: a Unicode lookalike package is not the mcp package in a
# manifest component name (the raw-text layer is Unicode-identifier-aware).
cp -r "$TMP/clean" "$TMP/manifest_component_unicode_lookalike"
cat > "$TMP/manifest_component_unicode_lookalike/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name="dev.librepocket.mcpé.Service" />
</manifest>
XML
expect_pass "$TMP/manifest_component_unicode_lookalike" \
  "manifest component dev.librepocket.mcpé is not the mcp package (G-06)"

# 6bj. G-06 positive: `mcpfoo` is a different package in a manifest component
# name too (full_reason uses the same identifier-aware boundary).
cp -r "$TMP/clean" "$TMP/manifest_component_mcpfoo"
cat > "$TMP/manifest_component_mcpfoo/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name="dev.librepocket.mcpfoo.Service" />
</manifest>
XML
expect_pass "$TMP/manifest_component_mcpfoo" \
  "manifest component dev.librepocket.mcpfoo is not the mcp package (G-06)"

# 6bk. G-04: multiline basic strings decode escapes like single-line ones.
cp -r "$TMP/clean" "$TMP/gradle_toml_multiline_basic"
cat >> "$TMP/gradle_toml_multiline_basic/gradle/libs.versions.toml" <<'TOML'
mcpPkg = """dev.librepocket\u002emcp"""
TOML
sed -i 's|namespace = "dev.librepocket.agent"|namespace = libs.versions.mcpPkg.get()|' \
  "$TMP/gradle_toml_multiline_basic/app/build.gradle.kts"
expect_fail "$TMP/gradle_toml_multiline_basic" \
  "TOML multiline basic string escape is decoded and rejected (G-04)" \
  "gradle version catalog references MCP components"

# 6bl. G-08 positive: multiline literal strings process no escapes either.
cp -r "$TMP/clean" "$TMP/gradle_toml_multiline_literal"
cat >> "$TMP/gradle_toml_multiline_literal/gradle/libs.versions.toml" <<'TOML'
note = '''dev.librepocket\u002emcp'''
TOML
expect_pass "$TMP/gradle_toml_multiline_literal" \
  "TOML multiline literal string with an escape stays green (G-08)"

# 1ao. G-06: `$` continues a JVM identifier, so `mcp$Compat` is a different
# package and must stay outbound instead of being stripped as a self-mention.
cp -r "$TMP/clean" "$TMP/in_self_mention_dollar"
cat > "$TMP/in_self_mention_dollar/app/src/main/java/dev/librepocket/mcp/UseDollar.java" <<'JAVA'
package dev.librepocket.mcp;
class UseDollar { dev.librepocket.mcp$Compat.Widget w; }
JAVA
expect_fail "$TMP/in_self_mention_dollar" \
  "mcp\$Compat lookalike is outbound, not a self-mention (G-06)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 1ap. G-06: a Unicode letter continues the identifier too.
cp -r "$TMP/clean" "$TMP/in_self_mention_unicode"
cat > "$TMP/in_self_mention_unicode/app/src/main/java/dev/librepocket/mcp/UseUni.java" <<'JAVA'
package dev.librepocket.mcp;
class UseUni { dev.librepocket.mcpé.Widget w; }
JAVA
expect_fail "$TMP/in_self_mention_unicode" \
  "mcpé lookalike is outbound, not a self-mention (G-06)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 1as. G-06 (classification): a package that *starts* like the mcp package but
# continues with `$` is a different package. The old ASCII PKG_DECL truncated
# it to `dev.librepocket.mcp`, classified the file IN and skipped C4.
cp -r "$TMP/clean" "$TMP/outside_pkg_dollar_lookalike"
mkdir -p "$TMP/outside_pkg_dollar_lookalike/app/src/main/java/com/example"
cat > "$TMP/outside_pkg_dollar_lookalike/app/src/main/java/com/example/Look.kt" <<'KT'
package dev.librepocket.mcp$Compat
import dev.librepocket.mcp.McpServer
internal val server: McpServer? = null
KT
expect_fail "$TMP/outside_pkg_dollar_lookalike" \
  "package dev.librepocket.mcp\$Compat is outside and C4-scanned (G-06)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1at. G-06 (classification): same for a Unicode continuation. A truncated
# ASCII capture must not turn a lookalike package into the mcp package.
cp -r "$TMP/clean" "$TMP/outside_pkg_unicode_lookalike"
mkdir -p "$TMP/outside_pkg_unicode_lookalike/app/src/main/java/com/example"
cat > "$TMP/outside_pkg_unicode_lookalike/app/src/main/java/com/example/Look.kt" <<'KT'
package dev.librepocket.mcpé
import dev.librepocket.mcp.McpServer
internal val server: McpServer? = null
KT
expect_fail "$TMP/outside_pkg_unicode_lookalike" \
  "package dev.librepocket.mcpé is outside and C4-scanned (G-06)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1aq. G-07: a production PACKAGE literally named `build` must be scanned; only
# a real Gradle output root is pruned.
cp -r "$TMP/clean" "$TMP/outside_build_package"
mkdir -p "$TMP/outside_build_package/app/src/main/java/com/example/build"
cat > "$TMP/outside_build_package/app/src/main/java/com/example/build/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/outside_build_package" \
  "production package named build is scanned (G-07)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1ar. C1-TEST: a custom `java.srcDir("extra/java")` root whose package path
# later contains `src/test` is a production root, not a test source set.
cp -r "$TMP/clean" "$TMP/outside_srcdir_src_test"
mkdir -p "$TMP/outside_srcdir_src_test/app/extra/java/dev/librepocket/mcp/src/test"
cat > "$TMP/outside_srcdir_src_test/app/extra/java/dev/librepocket/mcp/src/test/Evil.kt" <<'KT'
package dev.librepocket.mcp
import dev.librepocket.router.PrivilegeGate
internal class Evil(private val g: PrivilegeGate)
KT
cat >> "$TMP/outside_srcdir_src_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/java")
}
KTS
expect_fail "$TMP/outside_srcdir_src_test" \
  "custom srcDir root containing src/test is still production (C1-TEST)" \
  "outbound import not on allowlist"

# --- Stage1-fix3: identifier-part boundary, string normalisation, source-set
# --- and symlink/module-root scope (second adversarial re-review) -----------

# 1au. G-11: a combining mark (Mn) continues a JVM identifier, so
# `dev.librepocket.mcp<U+0301>` is a different package, not a self-mention.
cp -r "$TMP/clean" "$TMP/in_self_mention_combining"
printf 'package dev.librepocket.mcp;\nclass UseComb { dev.librepocket.mcp\u0301.Widget w; }\n' \
  > "$TMP/in_self_mention_combining/app/src/main/java/dev/librepocket/mcp/UseComb.java"
expect_fail "$TMP/in_self_mention_combining" \
  "combining-mark lookalike is outbound, not a self-mention (G-11)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 1av. G-11: ZWJ (Cf, Java identifier-ignorable) continues the identifier.
cp -r "$TMP/clean" "$TMP/in_self_mention_zwj"
printf 'package dev.librepocket.mcp;\nclass UseZwj { String s = "dev.librepocket.mcp\u200d.Widget"; }\n' \
  > "$TMP/in_self_mention_zwj/app/src/main/java/dev/librepocket/mcp/UseZwj.java"
expect_fail "$TMP/in_self_mention_zwj" \
  "ZWJ string lookalike is outbound, not a self-mention (G-11)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 1aw. G-11: a currency symbol (Sc) continues the identifier too.
cp -r "$TMP/clean" "$TMP/in_self_mention_currency"
printf 'package dev.librepocket.mcp;\nclass UseCur { dev.librepocket.mcp\u20acX.Widget w; }\n' \
  > "$TMP/in_self_mention_currency/app/src/main/java/dev/librepocket/mcp/UseCur.java"
expect_fail "$TMP/in_self_mention_currency" \
  "currency-symbol lookalike is outbound, not a self-mention (G-11)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 6bm. G-12: decomposed Unicode in a declared type name must be captured in
# full (the old ASCII remainder stopped at `Tra`).
cp -r "$TMP/clean" "$TMP/manifest_type_combining"
printf 'package dev.librepocket.mcp;\nclass Tra\u0308nsport {}\n' \
  > "$TMP/manifest_type_combining/app/src/main/java/dev/librepocket/mcp/Wire.java"
printf '<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n  <activity android:name=".MainActivity" />\n  <service android:name=".mcp.Tra\u0308nsport" />\n</manifest>\n' \
  > "$TMP/manifest_type_combining/app/src/main/AndroidManifest.xml"
expect_fail "$TMP/manifest_type_combining" \
  "combining-mark type name resolves a relative component (G-12)" \
  "Android manifest registers MCP (relative component .Tra"$'\u0308'"nsport)"

# 6bn. G-12: ZWJ inside a declared type name.
cp -r "$TMP/clean" "$TMP/manifest_type_zwj"
printf 'package dev.librepocket.mcp;\nclass Tra\u200dnsport {}\n' \
  > "$TMP/manifest_type_zwj/app/src/main/java/dev/librepocket/mcp/Wire.java"
printf '<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n  <activity android:name=".MainActivity" />\n  <service android:name=".mcp.Tra\u200dnsport" />\n</manifest>\n' \
  > "$TMP/manifest_type_zwj/app/src/main/AndroidManifest.xml"
expect_fail "$TMP/manifest_type_zwj" \
  "ZWJ type name resolves a relative component (G-12)" \
  "Android manifest registers MCP (relative component .Tra"$'\u200d'"nsport)"

# 6bo. G-12: a currency symbol is a legal Java identifier start (`€Wire`).
cp -r "$TMP/clean" "$TMP/manifest_type_currency_start"
printf 'package dev.librepocket.mcp;\nclass \u20acWire {}\n' \
  > "$TMP/manifest_type_currency_start/app/src/main/java/dev/librepocket/mcp/Wire.java"
printf '<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n  <activity android:name=".MainActivity" />\n  <service android:name=".mcp.\u20acWire" />\n</manifest>\n' \
  > "$TMP/manifest_type_currency_start/app/src/main/AndroidManifest.xml"
expect_fail "$TMP/manifest_type_currency_start" \
  "currency-symbol-leading type name resolves a component (G-12)" \
  "Android manifest registers MCP (relative component ."$'\u20ac'"Wire)"

# 6bp. G-12: `$` followed by a digit is a legal JVM identifier (`$1Wire`).
cp -r "$TMP/clean" "$TMP/manifest_type_dollar_digit"
cat > "$TMP/manifest_type_dollar_digit/app/src/main/java/dev/librepocket/mcp/Wire.java" <<'JAVA'
package dev.librepocket.mcp;
class $1Wire {}
JAVA
cat > "$TMP/manifest_type_dollar_digit/app/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <activity android:name=".MainActivity" />
  <service android:name=".mcp.$1Wire" />
</manifest>
XML
expect_fail "$TMP/manifest_type_dollar_digit" \
  "dollar-digit type name resolves a relative component (G-12)" \
  'Android manifest registers MCP (relative component .$1Wire)'

# 1ax (positive). G-11: a legal lookalike package is NOT the mcp package, so
# C4 must stay green for combining/ZWJ/currency continuations.
cp -r "$TMP/clean" "$TMP/outside_pkg_combining_lookalike"
mkdir -p "$TMP/outside_pkg_combining_lookalike/app/src/main/java/com/example"
printf 'package com.example;\nclass UseComb { dev.librepocket.mcp\u0301.Widget w; }\n' \
  > "$TMP/outside_pkg_combining_lookalike/app/src/main/java/com/example/UseComb.java"
expect_pass "$TMP/outside_pkg_combining_lookalike" \
  "C4: dev.librepocket.mcp<combining> is a lookalike package (G-11)"

cp -r "$TMP/clean" "$TMP/outside_pkg_zwj_lookalike"
mkdir -p "$TMP/outside_pkg_zwj_lookalike/app/src/main/java/com/example"
printf 'package com.example;\nclass UseZwj { String s = "dev.librepocket.mcp\u200d.Widget"; }\n' \
  > "$TMP/outside_pkg_zwj_lookalike/app/src/main/java/com/example/UseZwj.java"
expect_pass "$TMP/outside_pkg_zwj_lookalike" \
  "C4: dev.librepocket.mcp<ZWJ> is a lookalike package (G-11)"

cp -r "$TMP/clean" "$TMP/outside_pkg_currency_lookalike"
mkdir -p "$TMP/outside_pkg_currency_lookalike/app/src/main/java/com/example"
printf 'package com.example;\nclass UseCur { dev.librepocket.mcp\u20acX.Widget w; }\n' \
  > "$TMP/outside_pkg_currency_lookalike/app/src/main/java/com/example/UseCur.java"
expect_pass "$TMP/outside_pkg_currency_lookalike" \
  "C4: dev.librepocket.mcp<currency> is a lookalike package (G-11)"

# 6bq (positive). G-11: the manifest raw-text and XML layers use the same
# identifier-aware boundary, so legal lookalike packages are not false reds.
for lookalike in combining zwj currency; do
  d="$TMP/manifest_component_${lookalike}_lookalike"
  cp -r "$TMP/clean" "$d"
  case "$lookalike" in
    combining) value='dev.librepocket.mcp\u0301.Transport' ;;
    zwj) value='dev.librepocket.mcp\u200d.Transport' ;;
    currency) value='dev.librepocket.mcp\u20acX.Transport' ;;
  esac
  printf '<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n  <activity android:name=".MainActivity" />\n  <service android:name="%b" />\n</manifest>\n' \
    "$value" > "$d/app/src/main/AndroidManifest.xml"
  expect_pass "$d" \
    "manifest: dev.librepocket.mcp<${lookalike}> is a lookalike package (G-11)"
done

# 2ab. G-15: the C3 string view is normalised like HEAD's line view, so a
# legal spaced-dot FQN inside a string is still outbound.
cp -r "$TMP/clean" "$TMP/string_view_spaced_fqn"
cat > "$TMP/string_view_spaced_fqn/app/src/main/java/dev/librepocket/mcp/S.kt" <<'KT'
package dev.librepocket.mcp
internal val s = "dev . librepocket . router . PrivilegeGate"
KT
expect_fail "$TMP/string_view_spaced_fqn" \
  "spaced-dot FQN in a Kotlin string is caught (G-15)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2ac. G-15 (Java grammar): same spaced-dot string form.
cp -r "$TMP/clean" "$TMP/string_view_spaced_fqn_java"
cat > "$TMP/string_view_spaced_fqn_java/app/src/main/java/dev/librepocket/mcp/S.java" <<'JAVA'
package dev.librepocket.mcp;
class S { String s = "dev . librepocket . router . PrivilegeGate"; }
JAVA
expect_fail "$TMP/string_view_spaced_fqn_java" \
  "spaced-dot FQN in a Java string is caught (G-15)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2ad. G-15: backticked package segment inside a string.
cp -r "$TMP/clean" "$TMP/string_view_backticked_fqn"
cat > "$TMP/string_view_backticked_fqn/app/src/main/java/dev/librepocket/mcp/S.kt" <<'KT'
package dev.librepocket.mcp
internal val s = "dev.`librepocket`.router.PrivilegeGate"
KT
expect_fail "$TMP/string_view_backticked_fqn" \
  "backticked-segment string FQN is caught (G-15)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2ae. G-15: a same-line block comment between string segments.
cp -r "$TMP/clean" "$TMP/string_view_comment_fqn"
cat > "$TMP/string_view_comment_fqn/app/src/main/java/dev/librepocket/mcp/S.java" <<'JAVA'
package dev.librepocket.mcp;
class S { String s = "dev/*x*/.librepocket.router.PrivilegeGate"; }
JAVA
expect_fail "$TMP/string_view_comment_fqn" \
  "same-line-comment string FQN is caught (G-15)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 2af. G-15 control: the contiguous string form was always caught; the raw
# string view remains active alongside the normalised one.
cp -r "$TMP/clean" "$TMP/string_view_contiguous_fqn"
cat > "$TMP/string_view_contiguous_fqn/app/src/main/java/dev/librepocket/mcp/S.kt" <<'KT'
package dev.librepocket.mcp
internal val s = "dev.librepocket.router.PrivilegeGate"
KT
expect_fail "$TMP/string_view_contiguous_fqn" \
  "contiguous string FQN control is caught (G-15)" \
  "outbound reference from inside dev/librepocket/mcp/"

# 1ay. G-16: a MAIN source set pointed at `src/test` compiles test-shaped code
# as production; fail closed instead of dropping it as a test source.
cp -r "$TMP/clean" "$TMP/main_srcdir_into_src_test"
mkdir -p "$TMP/main_srcdir_into_src_test/app/src/test/java/com/example"
cat > "$TMP/main_srcdir_into_src_test/app/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA
cat >> "$TMP/main_srcdir_into_src_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("src/test")
}
KTS
expect_fail "$TMP/main_srcdir_into_src_test" \
  "main srcDir into src/test fails closed (G-16)" \
  "main source set adds a test/build source dir"

# 1az. G-13: a production source that is a symlink to an excluded test payload
# must fail closed rather than being silently skipped.
cp -r "$TMP/clean" "$TMP/discover_symlink_test_payload"
mkdir -p "$TMP/discover_symlink_test_payload/app/src/main/java/com/example" \
  "$TMP/discover_symlink_test_payload/app/src/test/java/com/example"
cat > "$TMP/discover_symlink_test_payload/app/src/test/java/com/example/Payload.java" <<'JAVA'
package com.example;
class Payload { Object x = dev.librepocket.mcp.OneShotJsonBody.class; }
JAVA
ln -s ../../../../test/java/com/example/Payload.java \
  "$TMP/discover_symlink_test_payload/app/src/main/java/com/example/Payload.java"
expect_fail "$TMP/discover_symlink_test_payload" \
  "symlinked production source fails closed (G-13)" \
  "symlinked source not scanned (fail closed)"

# 1ba/1bb. G-14: an inert marker file in a package directory is not a module
# root, so a sibling production package named `build` is still scanned.
for marker in settings.gradle build.gradle.kts; do
  d="$TMP/module_marker_${marker//./_}"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/main/java/com/example/build"
  cat > "$d/app/src/main/java/com/example/build/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
  : > "$d/app/src/main/java/com/example/$marker"
  expect_fail "$d" \
    "marker-only package dir does not prune build package ($marker) (G-14)" \
    "outside dev/librepocket/mcp/ references MCP types"
done

# 2ag (positive). O-01: a backticked Kotlin import alias containing a space is
# legal and the imported type is allowlisted.
cp -r "$TMP/clean" "$TMP/in_import_backticked_alias"
cat > "$TMP/in_import_backticked_alias/app/src/main/java/dev/librepocket/mcp/K2.kt" <<'KT'
package dev.librepocket.mcp
import dev.librepocket.tool.ToolDef as `T D`
internal class K2x
KT
expect_pass "$TMP/in_import_backticked_alias" \
  "backticked Kotlin import alias with a space passes (O-01)"

# --- Stage1-fix3 round 2: package-classifier lookalikes (G-17) -------------

# 1bc. G-17: a legal Java identifier-part continuation must be part of the
# declared package name, not truncated back to `dev.librepocket.mcp`.  Each
# lookalike file imports the REAL mcp type, so C4 must flag it.
for lookalike in combining currency pc zwj; do
  d="$TMP/outside_pkg_${lookalike}_continuation"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/main/java/com/example"
  case "$lookalike" in
    combining) part='\u0301' ;;
    currency) part='\u20acX' ;;
    pc) part='\u203fX' ;;
    zwj) part='\u200d' ;;
  esac
  printf 'package dev.librepocket.mcp%b;\nimport dev.librepocket.mcp.McpStub;\nclass Evil { Object x = McpStub.class; }\n' \
    "$part" > "$d/app/src/main/java/com/example/Evil.java"
  expect_fail "$d" \
    "package dev.librepocket.mcp<${lookalike}> is OUT and C4-scanned (G-17)" \
    "outside dev/librepocket/mcp/ references MCP types"
done

# 1bd. G-17: the same truncation with a fully-qualified use and no import.
cp -r "$TMP/clean" "$TMP/outside_pkg_combining_fqn"
mkdir -p "$TMP/outside_pkg_combining_fqn/app/src/main/java/com/example"
printf 'package dev.librepocket.mcp\u0301;\nclass Evil { Object x = dev.librepocket.mcp.McpStub.class; }\n' \
  > "$TMP/outside_pkg_combining_fqn/app/src/main/java/com/example/Evil.java"
expect_fail "$TMP/outside_pkg_combining_fqn" \
  "lookalike package with a fully-qualified MCP use is C4-scanned (G-17)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1be (positive). G-17: C4 must not over-flag the lookalike package itself.
# This file references only its own lookalike package, never the real one.
cp -r "$TMP/clean" "$TMP/outside_pkg_lookalike_self"
mkdir -p "$TMP/outside_pkg_lookalike_self/app/src/main/java/com/example"
printf 'package dev.librepocket.mcp\u0301;\nclass Own { dev.librepocket.mcp\u0301.Widget w; }\n' \
  > "$TMP/outside_pkg_lookalike_self/app/src/main/java/com/example/Own.java"
expect_pass "$TMP/outside_pkg_lookalike_self" \
  "a lookalike package referencing only itself stays green (G-17)"

# 1bf (positive). U+00B2 is category No, which Java does NOT accept as an
# identifier part.  `dev.librepocket.mcp²` is therefore the mcp package and the
# reference is a self-mention (the old `isalnum()` over-approximation made this
# red).
cp -r "$TMP/clean" "$TMP/in_self_mention_superscript"
printf 'package dev.librepocket.mcp;\nclass Sup { String s = "dev.librepocket.mcp\u00b2.Widget"; }\n' \
  > "$TMP/in_self_mention_superscript/app/src/main/java/dev/librepocket/mcp/Sup.java"
expect_pass "$TMP/in_self_mention_superscript" \
  "dev.librepocket.mcp<U+00B2> is a self-mention (exact is_ident_part)"

# 1bg. G-17 parser-drift guard: a tree whose only "mcp" declaration is a
# lookalike has ZERO real in-package files; the gate must fail closed rather
# than accept the truncated declaration.
cp -r "$TMP/clean" "$TMP/no_in_pkg_lookalike"
printf 'package dev.librepocket.mcp\u0301\nclass OtherStub\n' \
  > "$TMP/no_in_pkg_lookalike/app/src/main/java/dev/librepocket/mcp/McpStub.kt"
expect_fail "$TMP/no_in_pkg_lookalike" \
  "only a lookalike declaration fails the zero-IN guard (G-17)" \
  "no production source declares package dev.librepocket.mcp"

# --- Stage1-fix3 round 2: settings-driven module roots (G-14) ---------------

# 1bh. G-14c: an inert settings.gradle + src/.keep inside a Java package is
# not a Gradle module; its sibling `build/` is production code.
cp -r "$TMP/clean" "$TMP/module_marker_src_spoof"
mkdir -p "$TMP/module_marker_src_spoof/app/src/main/java/com/example/build" \
  "$TMP/module_marker_src_spoof/app/src/main/java/com/example/src"
: > "$TMP/module_marker_src_spoof/app/src/main/java/com/example/settings.gradle"
: > "$TMP/module_marker_src_spoof/app/src/main/java/com/example/src/.keep"
cat > "$TMP/module_marker_src_spoof/app/src/main/java/com/example/build/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/module_marker_src_spoof" \
  "marker+src package dir does not prune a sibling build package (G-14c)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1bi (positive). A real root settings.gradle.kts include(":app") still prunes
# the module's build output. The payload is a WIRED reference: if pruning were
# disabled, C4 would scan app/build and fail, so PASS proves the tree is pruned.
cp -r "$TMP/clean" "$TMP/module_root_settings_app"
cat > "$TMP/module_root_settings_app/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
KTS
mkdir -p "$TMP/module_root_settings_app/app/build/generated/ksp/com/example"
cat > "$TMP/module_root_settings_app/app/build/generated/ksp/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/module_root_settings_app" \
  "settings include(:app) actually prunes a wired app/build (G-14)"

# 1bj (positive). A nested module included by the root settings is a module
# root too, so its generated build output (wired payload) is pruned.
cp -r "$TMP/clean" "$TMP/module_root_settings_nested"
cat > "$TMP/module_root_settings_nested/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
include(":feature:mcpui")
KTS
mkdir -p "$TMP/module_root_settings_nested/feature/mcpui/src/main/java/com/example" \
  "$TMP/module_root_settings_nested/feature/mcpui/build/generated/com/example"
cat > "$TMP/module_root_settings_nested/feature/mcpui/build.gradle.kts" <<'KTS'
plugins { id("java") }
KTS
cat > "$TMP/module_root_settings_nested/feature/mcpui/src/main/java/com/example/Clean.kt" <<'KT'
package com.example
internal class Clean
KT
cat > "$TMP/module_root_settings_nested/feature/mcpui/build/generated/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/module_root_settings_nested" \
  "nested settings include(:feature:mcpui) prunes a wired build (G-14)"

# 1bk (positive). No settings file: the depth-1 app/build.gradle.kts + src
# fallback still recognizes app as a module (and prunes a wired app/build).
cp -r "$TMP/clean" "$TMP/module_no_settings_app"
mkdir -p "$TMP/module_no_settings_app/app/build/generated/com/example"
cat > "$TMP/module_no_settings_app/app/build/generated/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/module_no_settings_app" \
  "no-settings depth-1 app module prunes a wired build (G-14)"

# 1bk2 (negative control). The same wired app/build without an include(:app)
# must be scanned and rejected: a green here would mean pruning ignored the
# settings file's module list.
cp -r "$TMP/clean" "$TMP/module_root_settings_no_include"
cat > "$TMP/module_root_settings_no_include/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
KTS
mkdir -p "$TMP/module_root_settings_no_include/app/build/generated/com/example"
cat > "$TMP/module_root_settings_no_include/app/build/generated/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/module_root_settings_no_include" \
  "settings without include(:app) scans a wired app/build (G-14)" \
  "outside dev/librepocket/mcp/ references MCP types"

# 1bl. No settings file: the fallback is depth-1 only, so a depth-2
# marker+src spoof cannot prune its build/ tree.
cp -r "$TMP/clean" "$TMP/module_no_settings_spoof_depth2"
mkdir -p "$TMP/module_no_settings_spoof_depth2/app/spoof/build" \
  "$TMP/module_no_settings_spoof_depth2/app/spoof/src"
: > "$TMP/module_no_settings_spoof_depth2/app/spoof/settings.gradle"
: > "$TMP/module_no_settings_spoof_depth2/app/spoof/src/.keep"
cat > "$TMP/module_no_settings_spoof_depth2/app/spoof/build/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/module_no_settings_spoof_depth2" \
  "no-settings depth-2 marker+src spoof still scans build/ (G-14)" \
  "outside dev/librepocket/mcp/ references MCP types"

# --- Stage1-fix3 round 2: whole-expression srcDir parsing (G-16) -----------

for form in val file concat setlist srcslist projectfile assign; do
  d="$TMP/main_srcdir_$form"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cat > "$d/app/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA
  case "$form" in
    val) snippet='    val d = "src/test"
    sourceSets.getByName("main").java.srcDir(d)' ;;
    file) snippet='    sourceSets.getByName("main").java.srcDir(file("src/test"))' ;;
    concat) snippet='    sourceSets.getByName("main").java.srcDir("src/" + "test")' ;;
    setlist) snippet='    sourceSets.getByName("main").java.setSrcDirs(listOf("src/test"))' ;;
    srcslist) snippet='    sourceSets.getByName("main").java.srcDirs(listOf("src/test"))' ;;
    projectfile) snippet='    sourceSets.getByName("main").java.srcDir(project.file("src/test"))' ;;
    assign) snippet='    sourceSets.getByName("main").java.srcDirs += "src/test"' ;;
  esac
  printf '\nandroid {\n%s\n}\n' "$snippet" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "main srcDir $form form is a test-into-production config (G-16)" \
    "main source set adds a test/build source dir"
done

# 1bs/1bt. G-16i/j: build-dir providers and a source committed under build/.
for form in buildvar builddir; do
  d="$TMP/main_srcdir_$form"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/build/gen/com/example"
  cat > "$d/app/build/gen/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA
  case "$form" in
    buildvar) snippet='    sourceSets.getByName("main").java.srcDir("$buildDir/gen")' ;;
    builddir) snippet='    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("gen"))' ;;
  esac
  printf '\nandroid {\n%s\n}\n' "$snippet" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "main srcDir $form resolves under the module build dir (G-16)" \
    "main source set adds a test/build source dir"
done

# 1bv. A main srcDir expression that cannot be resolved to a safe path (an
# arbitrary provider/lambda) fails closed with its own diagnostic.
cp -r "$TMP/clean" "$TMP/main_srcdir_unresolvable"
mkdir -p "$TMP/main_srcdir_unresolvable/app/src/test/java/com/example"
cat > "$TMP/main_srcdir_unresolvable/app/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA
cat >> "$TMP/main_srcdir_unresolvable/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir(provider { "src/test" })
}
KTS
expect_fail "$TMP/main_srcdir_unresolvable" \
  "unresolvable main srcDir fails closed (G-16)" \
  "main source set source dir cannot be resolved to a safe path"

# 1bw (positive). Legitimate custom roots; a test source set's own srcDir; and
# a production package path that merely contains `src/test`.
cp -r "$TMP/clean" "$TMP/main_srcdir_extra_java"
mkdir -p "$TMP/main_srcdir_extra_java/app/extra/java/com/example"
cat > "$TMP/main_srcdir_extra_java/app/extra/java/com/example/Clean.kt" <<'KT'
package com.example
internal class Clean
KT
cat >> "$TMP/main_srcdir_extra_java/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/java")
}
KTS
expect_pass "$TMP/main_srcdir_extra_java" \
  "srcDir(extra/java) stays green (P1)"

cp -r "$TMP/clean" "$TMP/main_srcdir_src_main"
cat >> "$TMP/main_srcdir_src_main/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("src/main/java")
}
KTS
expect_pass "$TMP/main_srcdir_src_main" \
  "srcDir(src/main/java) stays green (P2)"

cp -r "$TMP/clean" "$TMP/main_srcdir_test_set"
cat >> "$TMP/main_srcdir_test_set/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("test").java.srcDir("src/test")
}
KTS
expect_pass "$TMP/main_srcdir_test_set" \
  "a test source set's own srcDir stays green (P4)"

cp -r "$TMP/clean" "$TMP/main_srcdir_extra_src_test"
mkdir -p "$TMP/main_srcdir_extra_src_test/app/extra/src/test/com/example"
cat > "$TMP/main_srcdir_extra_src_test/app/extra/src/test/com/example/Clean.kt" <<'KT'
package com.example
internal class Clean
KT
cat >> "$TMP/main_srcdir_extra_src_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/src/test")
}
KTS
expect_pass "$TMP/main_srcdir_extra_src_test" \
  "srcDir(extra/src/test) is a scanned production root (P5)"

# 1bw2 (P5 negative). The same custom root must actually be scanned: a wired
# file under app/extra/src/test is rejected even though its directory name
# contains `test` (it is not `<module>/src/test*`).
cp -r "$TMP/clean" "$TMP/main_srcdir_extra_src_test_wired"
mkdir -p "$TMP/main_srcdir_extra_src_test_wired/app/extra/src/test/com/example"
cat > "$TMP/main_srcdir_extra_src_test_wired/app/extra/src/test/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
cat >> "$TMP/main_srcdir_extra_src_test_wired/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/src/test")
}
KTS
expect_fail "$TMP/main_srcdir_extra_src_test_wired" \
  "custom srcDir(extra/src/test) is scanned, so wired MCP fails (P5)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/main_srcdir_extra_src_testfixtures"
mkdir -p "$TMP/main_srcdir_extra_src_testfixtures/app/extra/src/testFixtures/com/example"
cat > "$TMP/main_srcdir_extra_src_testfixtures/app/extra/src/testFixtures/com/example/Clean.kt" <<'KT'
package com.example
internal class Clean
KT
cat >> "$TMP/main_srcdir_extra_src_testfixtures/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/src/testFixtures")
}
KTS
expect_pass "$TMP/main_srcdir_extra_src_testfixtures" \
  "srcDir(extra/src/testFixtures) is a scanned production root (P6)"

# 1bw3 (P6 negative). A wired file in the custom testFixtures-named root proves
# it is scanned rather than classified as `<module>/src/test*`.
cp -r "$TMP/clean" "$TMP/main_srcdir_extra_src_testfixtures_wired"
mkdir -p "$TMP/main_srcdir_extra_src_testfixtures_wired/app/extra/src/testFixtures/com/example"
cat > "$TMP/main_srcdir_extra_src_testfixtures_wired/app/extra/src/testFixtures/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
cat >> "$TMP/main_srcdir_extra_src_testfixtures_wired/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/src/testFixtures")
}
KTS
expect_fail "$TMP/main_srcdir_extra_src_testfixtures_wired" \
  "custom srcDir(extra/src/testFixtures) is scanned, so wired MCP fails (P6)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/main_srcdir_package_src_test"
mkdir -p "$TMP/main_srcdir_package_src_test/app/extra/java/com/example/src/test"
cat > "$TMP/main_srcdir_package_src_test/app/extra/java/com/example/src/test/Clean.kt" <<'KT'
package com.example.src.test
internal class Clean
KT
cat >> "$TMP/main_srcdir_package_src_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("extra/java")
}
KTS
expect_pass "$TMP/main_srcdir_package_src_test" \
  "srcDir(extra/java) with a src/test package path stays green (P7)"

# 1bx. P3 accepted fail-closed over-block: an explicit main srcDir under the
# module build root (build/generated/ksp) is red because explicit build srcDirs
# are not currently added to discovery.
cp -r "$TMP/clean" "$TMP/main_srcdir_build_generated"
cat >> "$TMP/main_srcdir_build_generated/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("build/generated/ksp")
}
KTS
expect_fail "$TMP/main_srcdir_build_generated" \
  "srcDir(build/generated/ksp) is an accepted fail-closed over-block (P3)" \
  "main source set adds a test/build source dir"

# --- Stage1 fix5: ancestor/pruned in-module srcDir roots (G-23) ------------

for form in ancestor_src ancestor_dot ancestor_dotdot ancestor_join ancestor_file; do
  d="$TMP/srcdir_g23_$form"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cat > "$d/app/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA
  case "$form" in
    ancestor_src) snippet='    sourceSets.getByName("main").java.srcDir("src")' ;;
    ancestor_dot) snippet='    sourceSets.getByName("main").java.srcDir(".")' ;;
    ancestor_dotdot) snippet='    sourceSets.getByName("main").java.srcDir("src/main/../..")' ;;
    ancestor_join) snippet='    sourceSets.getByName("main").java.srcDir("src" + "")' ;;
    ancestor_file) snippet='    sourceSets.getByName("main").java.srcDir(file("."))' ;;
  esac
  printf '
android {
%s
}
' "$snippet" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "in-module srcDir $form is an ancestor of src/test (G-23)" \
    "main source set adds a test/build source dir"
done

for form in gradle node_modules git; do
  d="$TMP/srcdir_g23_pruned_$form"
  cp -r "$TMP/clean" "$d"
  case "$form" in
    gradle) rel=".gradle/gen" ;;
    node_modules) rel="node_modules/gen" ;;
    git) rel=".git/gen" ;;
  esac
  mkdir -p "$d/app/$rel/com/example"
  cat > "$d/app/$rel/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
  printf '
android {
    sourceSets.getByName("main").java.srcDir("%s")
}
' \
    "$rel" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "srcDir($rel) is in a discovery-pruned tree (G-23)" \
    "main source set source dir cannot be resolved to a safe path"
done

cp -r "$TMP/clean" "$TMP/srcdir_g23_controls"
mkdir -p "$TMP/srcdir_g23_controls/app/extra/java/com/example"
cat > "$TMP/srcdir_g23_controls/app/extra/java/com/example/Clean.kt" <<'KT'
package com.example
internal class Clean
KT
cat >> "$TMP/srcdir_g23_controls/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("src/main")
    sourceSets.getByName("main").java.srcDir("src/main/java")
    sourceSets.getByName("main").java.srcDir("extra/java")
}
KTS
expect_pass "$TMP/srcdir_g23_controls" \
  "src/main, src/main/java and extra/java stay safe (G-23 controls)"

# --- Stage1 fix4: structural source-set context (G-18) ----------------------
# A local variable named testMode/label inside a MAIN block must not suppress
# the guard; a local `main` label inside a TEST block must not make it main.

cp -r "$TMP/clean" "$TMP/srcdir_g18_main_apply_local_test"
cat >> "$TMP/srcdir_g18_main_apply_local_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.apply {
        val testMode = true
        java.srcDir("src/test")
    }
}
KTS
expect_fail "$TMP/srcdir_g18_main_apply_local_test" \
  "main apply block with local testMode still flags src/test (G-18 X1)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g18_main_apply_control"
cat >> "$TMP/srcdir_g18_main_apply_control/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.apply {
        java.srcDir("src/test")
    }
}
KTS
expect_fail "$TMP/srcdir_g18_main_apply_control" \
  "the same main apply block without testMode is still flagged (G-18 X1b)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g18_main_block_label"
cat >> "$TMP/srcdir_g18_main_block_label/app/build.gradle.kts" <<'KTS'

android {
    sourceSets {
        main {
            val label = "test"
            java.srcDir("src/test")
        }
    }
}
KTS
expect_fail "$TMP/srcdir_g18_main_block_label" \
  "main source-set block with a local test label still flags src/test (G-18 X5)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g18_test_block_label"
cat >> "$TMP/srcdir_g18_test_block_label/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("test") {
        val label = "main"
        java.srcDir("src/test")
    }
}
KTS
expect_pass "$TMP/srcdir_g18_test_block_label" \
  "test source-set block with a local main label stays green (G-18 X10)"

cp -r "$TMP/clean" "$TMP/srcdir_g18_receivers_test_sets"
cat >> "$TMP/srcdir_g18_receivers_test_sets/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.create("testCreate") { java.srcDir("src/testCreate") }
    sourceSets.maybeCreate("testMaybe") { java.srcDir("src/testMaybe") }
    sourceSets.named("testNamed") { java.srcDir("src/testNamed") }
    sourceSets["testIndex"] { java.srcDir("src/testIndex") }
    sourceSets.testProp { java.srcDir("src/testProp") }
    sourceSets { testBare { java.srcDir("src/testBare") } }
    sourceSets["testDirect"].java.srcDir("src/testDirect")
    sourceSets.testDirectProp.java.srcDir("src/testDirectProp")
}
KTS
expect_pass "$TMP/srcdir_g18_receivers_test_sets" \
  "all documented source-set receiver forms identify test sets (G-18)"

cp -r "$TMP/clean" "$TMP/srcdir_g18_main_after_nested_test"
cat >> "$TMP/srcdir_g18_main_after_nested_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        sourceSets.getByName("test") {
            java.srcDir("src/test")
        }
        java.srcDir("src/test")
    }
}
KTS
expect_fail "$TMP/srcdir_g18_main_after_nested_test" \
  "main srcDir after a nested test block is still main (G-18 window leak)" \
  "main source set adds a test/build source dir"

# --- Stage1 fix5: source-set context prefers enclosing blocks (G-24) ------

mkdir -p "$TMP/srcdir_g24_wire/app/src/test/java/com/example"
cat > "$TMP/srcdir_g24_wire/app/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA

for case_name in d15 d18 d14 d17; do
  d="$TMP/srcdir_g24_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_g24_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    d15) snippet='    sourceSets.getByName("main") {
        println(sourceSets.getByName("test").name)
        java.srcDir("src/test")
    }' ;;
    d18) snippet='    sourceSets.getByName("main") {
        println(sourceSets.named("test"))
        java.srcDir("src/test")
    }' ;;
    d14) snippet='    sourceSets.getByName("main").java.apply {
        val x = foo(sourceSets.getByName("test"))
        srcDir("src/test")
    }' ;;
    d17) snippet='    println(sourceSets.getByName("test").name)
    sourceSets.getByName("main") {
        java.srcDir("src/test")
    }' ;;
  esac
  printf '
android {
%s
}
' "$snippet" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "enclosing main block wins over an unrelated test mention ($case_name, G-24)" \
    "main source set adds a test/build source dir"
done

for case_name in d21 d22; do
  d="$TMP/srcdir_g24_$case_name"
  cp -r "$TMP/clean" "$d"
  case "$case_name" in
    d21) snippet='    sourceSets.getByName("test") {
        println(sourceSets.getByName("main").name)
        java.srcDir("src/test")
    }' ;;
    d22) snippet='    sourceSets.getByName("test").java.apply {
        val mainDirs = sourceSets.getByName("main").java.srcDirs
        srcDir("src/test")
    }' ;;
  esac
  printf '
android {
%s
}
' "$snippet" >> "$d/app/build.gradle.kts"
  expect_pass "$d" \
    "enclosing test block wins over an unrelated main mention ($case_name, G-24)"
done

cp -r "$TMP/clean" "$TMP/srcdir_g24_d1_multiline_test"
cat >> "$TMP/srcdir_g24_d1_multiline_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets
        .getByName("test")
        .java
        .srcDir("src/test")
}
KTS
expect_pass "$TMP/srcdir_g24_d1_multiline_test" \
  "a multi-line test receiver is still recognised (G-24 D1)"

cp -r "$TMP/clean" "$TMP/srcdir_g24_d2_multiline_main"
cat >> "$TMP/srcdir_g24_d2_multiline_main/app/build.gradle.kts" <<'KTS'

android {
    sourceSets
        .getByName("main")
        .java
        .srcDir("src/test")
}
KTS
expect_fail "$TMP/srcdir_g24_d2_multiline_main" \
  "a multi-line main receiver is still flagged (G-24 D2)" \
  "main source set adds a test/build source dir"

# --- Final gate F-1: an explicit main receiver wins inside a test block ----
# The enclosing test block must not hide a direct main mutation at the call
# site.  Each fixture puts the wired production-shaped file under src/test and
# widens main to it; only the main-srcDir guard can catch it.

mkdir -p "$TMP/srcdir_f1_wire/app/src/test/java/com/example"
cat > "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer.class; }
JAVA

for case_name in n1 n2 n10 n11; do
  d="$TMP/srcdir_f1_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    n1) snippet='    sourceSets.getByName("test") {
        java.srcDir("src/testResources")
        sourceSets.getByName("main").java.srcDir("src/test")
    }' ;;
    n2) snippet='    sourceSets.getByName("test").apply {
        sourceSets.getByName("main").java.srcDir("src/test")
    }' ;;
    n10) snippet='    sourceSets.getByName("test").also {
        sourceSets.getByName("main").java.srcDir("src/test")
    }' ;;
    n11) snippet='    sourceSets.test {
        sourceSets.getByName("main").java.srcDir("src/test")
    }' ;;
  esac
  printf '
android {
%s
}
' "$snippet" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "direct main receiver wins over an enclosing test block ($case_name, F-1)" \
    "main source set adds a test/build source dir"
done

# A lambda before `.java.srcDir` is part of the receiver chain.  The chain walk
# must cross the balanced `}` instead of falling back to the enclosing test
# block.  Cover one-line apply/also and a multi-line chained form.
for case_name in apply also multiline; do
  d="$TMP/srcdir_f1_lambda_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    apply) snippet='    sourceSets.getByName("test") {
        sourceSets.getByName("main").apply {
        }.java.srcDir("src/test")
    }' ;;
    also) snippet='    sourceSets.getByName("test") {
        sourceSets.getByName("main").also {
        }.java.srcDir("src/test")
    }' ;;
    multiline) snippet='    sourceSets.getByName("test") {
        sourceSets.getByName("main")
            .apply {
            }
            .java
            .srcDir("src/test")
    }' ;;
  esac
  printf '
android {
%s
}
' "$snippet" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "a lambda receiver chain is not truncated before srcDir ($case_name, F-1)" \
    "main source set adds a test/build source dir"
done

# A source-set mention inside a helper call argument is not the receiver of
# srcDir.  There is deliberately no enclosing source-set block, so the old
# receiver matcher would treat the argument as the context and pass.
cp -r "$TMP/clean" "$TMP/srcdir_f1_helper_arg_noblock"
mkdir -p "$TMP/srcdir_f1_helper_arg_noblock/app/src/test/java/com/example"
cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
  "$TMP/srcdir_f1_helper_arg_noblock/app/src/test/java/com/example/Wired.java"
cat >> "$TMP/srcdir_f1_helper_arg_noblock/app/build.gradle.kts" <<'KTS'

android {
    helper(sourceSets.getByName("test")).java.srcDir("src/test")
}
KTS
expect_fail "$TMP/srcdir_f1_helper_arg_noblock" \
  "an opaque helper argument mention fails closed (F-1)" \
  "main source set source dir cannot be resolved to a safe path (fail closed)"

# `with(<source-set>)` creates a source-set receiver block; safe-call `?.` and
# asserted-call `!!` are receiver-chain operators; `requireNotNull` is a
# transparent wrapper.  In all cases the direct main receiver must beat the
# enclosing test block.  `listOf(...).single()` is opaque and must fail closed
# rather than fall back to the enclosing test block.
for case_name in with_block with_this safe_call not_null require_not_null \
    checknotnull_main listof_single with_bare_main; do
  d="$TMP/srcdir_f1_receiver_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    with_block) snippet='    sourceSets.getByName("test") {
        with(sourceSets.getByName("main")) {
            java.srcDir("src/test")
        }
    }' ;;
    with_this) snippet='    sourceSets.getByName("test") {
        with(sourceSets.getByName("main")) { this }.java.srcDir("src/test")
    }' ;;
    safe_call) snippet='    sourceSets.getByName("test") {
        sourceSets.getByName("main")?.java?.srcDir("src/test")
    }' ;;
    not_null) snippet='    sourceSets.getByName("test") {
        sourceSets.getByName("main")!!.java.srcDir("src/test")
    }' ;;
    require_not_null) snippet='    sourceSets.getByName("test") {
        requireNotNull(sourceSets.getByName("main")).java.srcDir("src/test")
    }' ;;
    checknotnull_main) snippet='    sourceSets.getByName("test") {
        checkNotNull(sourceSets.getByName("main")).java.srcDir("src/test")
    }' ;;
    listof_single) snippet='    sourceSets.getByName("test") {
        listOf(sourceSets.getByName("main")).single().java.srcDir("src/test")
    }' ;;
    with_bare_main) snippet='    sourceSets.getByName("test") {
        with(main) { java.srcDir("src/test") }
    }' ;;
  esac
  printf '\nandroid {\n%s\n}\n' "$snippet" >> "$d/app/build.gradle.kts"
  if [ "$case_name" = listof_single ]; then
    expect_fail "$d" \
      "opaque receiver argument fails closed ($case_name, F-1)" \
      "main source set source dir cannot be resolved to a safe path (fail closed)"
  else
    expect_fail "$d" \
      "receiver form reaches the direct main source set ($case_name, F-1)" \
      "main source set adds a test/build source dir"
  fi
done

# The same transparent wrappers around a test source set stay green.  These
# are top-level (or inside main), not nested in an enclosing test block, so
# removing wrapper recognition makes HEAD red: the controls are non-vacuous
# (R-2).
for case_name in with_test with_bare_test require_test checknotnull_test; do
  d="$TMP/srcdir_f1_receiver_${case_name}_control"
  cp -r "$TMP/clean" "$d"
  case "$case_name" in
    with_test) snippet='with(sourceSets.getByName("test")) {
    java.srcDir("src/test")
}' ;;
    with_bare_test) snippet='with(test) {
    java.srcDir("src/test")
}' ;;
    require_test) snippet='requireNotNull(test).java.srcDir("src/test")' ;;
    checknotnull_test) snippet='checkNotNull(test).java.srcDir("src/test")' ;;
  esac
  printf '\nandroid {\n    %s\n}\n' "$snippet" >> "$d/app/build.gradle.kts"
  expect_pass "$d" \
    "receiver wrapper around test stays green ($case_name, F-1)"
done

# Newline/comment before the `with` block body; chained run/apply/let/also
# wrappers; bare and nested transparent arguments.  Each must resolve to main
# instead of falling back to the enclosing test block.
for case_name in with_source_newline with_source_comment_newline \
    with_bare_comment_newline with_run_chain with_apply_chain with_let_it \
    with_also_it with_source_run with_source_let with_source_also \
    with_apply_then_run with_run_then_also source_apply_then_run \
    require_bare check_bare nested_require; do
  d="$TMP/srcdir_f1_wrapper_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    with_source_newline) payload='
android {
    sourceSets.getByName("test") {
        with(sourceSets.getByName("main"))
        { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
    with_source_comment_newline) payload='
android {
    sourceSets.getByName("test") {
        with(sourceSets.getByName("main")) /* c */
        { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
    with_bare_comment_newline) payload='
android {
    sourceSets.getByName("test") {
        with(main) /* c */
        { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
    with_run_chain) payload='
android {
    sourceSets.getByName("test") {
        with(main).run { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
    with_apply_chain) payload='
android {
    sourceSets.getByName("test") {
        with(main).apply { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
    with_let_it) payload='
android {
    sourceSets.getByName("test") {
        with(main).let { it.java.srcDir("src/test") }
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    with_also_it) payload='
android {
    sourceSets.getByName("test") {
        with(main).also { it.java.srcDir("src/test") }
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    with_source_run) payload='
android {
    sourceSets.getByName("test") {
        with(sourceSets.getByName("main")).run { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
    with_source_let) payload='
android {
    sourceSets.getByName("test") {
        with(sourceSets.getByName("main")).let { it.java.srcDir("src/test") }
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    with_source_also) payload='
android {
    sourceSets.getByName("test") {
        with(sourceSets.getByName("main")).also { it.java.srcDir("src/test") }
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    with_apply_then_run) payload='
android {
    sourceSets.getByName("test") {
        with(main).apply { }.run { java.srcDir("src/test") }
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    with_run_then_also) payload='
android {
    sourceSets.getByName("test") {
        with(main).run { }.also { java.srcDir("src/test") }
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    source_apply_then_run) payload='
android {
    sourceSets.getByName("test") {
        sourceSets.getByName("main").apply { }.run { java.srcDir("src/test") }
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    require_bare) payload='
android {
    sourceSets.getByName("test") {
        requireNotNull(main).java.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    check_bare) payload='
android {
    sourceSets.getByName("test") {
        checkNotNull(main).java.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    nested_require) payload='
android {
    sourceSets.getByName("test") {
        with(requireNotNull(main)) { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
  esac
  printf '%s\n' "$payload" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "wrapper/chained receiver resolves or fails closed ($case_name, F-1)" \
    "$expected"
done

# Same-file receiver aliases: direct, with(...), after apply, alias of
# sourceSets, shadowing, unresolved alias, and assigned var.  A resolvable
# main alias is red; unresolved/assigned receivers fail closed.
for case_name in alias_receiver alias_declared_outside alias_with_block \
    alias_java_chain alias_apply_chain alias_sourcesets alias_shadow \
    unknown_bare_receiver var_alias_assigned; do
  d="$TMP/srcdir_f1_alias_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    alias_receiver) payload='
android {
    sourceSets.getByName("test") {
        val mainSet = sourceSets.getByName("main")
        mainSet.java.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    alias_declared_outside) payload='
val mainSet = sourceSets.getByName("main")

android {
    sourceSets.getByName("test") {
        mainSet.java.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    alias_with_block) payload='
android {
    sourceSets.getByName("test") {
        val mainSet = sourceSets.getByName("main")
        with(mainSet) { java.srcDir("src/test") }
    }
}' ; expected="main source set adds a test/build source dir" ;;
    alias_java_chain) payload='
android {
    sourceSets.getByName("test") {
        val mainJava = sourceSets.getByName("main").java
        mainJava.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    alias_apply_chain) payload='
android {
    sourceSets.getByName("test") {
        val mainSet = sourceSets.getByName("main").apply { }
        mainSet.java.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    alias_sourcesets) payload='
android {
    sourceSets.getByName("test") {
        val ss = sourceSets
        ss.getByName("main").java.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    alias_shadow) payload='
val mainSet = sourceSets.getByName("test")

android {
    sourceSets.getByName("test") {
        val mainSet = sourceSets.getByName("main")
        mainSet.java.srcDir("src/test")
    }
}' ; expected="main source set adds a test/build source dir" ;;
    unknown_bare_receiver) payload='
android {
    sourceSets.getByName("test") {
        mystery.java.srcDir("src/test")
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    var_alias_assigned) payload='
android {
    sourceSets.getByName("test") {
        var mainSet = sourceSets.getByName("test")
        mainSet = sourceSets.getByName("main")
        mainSet.java.srcDir("src/test")
    }
}' ; expected="main source set source dir cannot be resolved to a safe path (fail closed)" ;;
  esac
  printf '%s\n' "$payload" >> "$d/app/build.gradle.kts"
  expect_fail "$d" \
    "receiver alias is resolved or fails closed ($case_name, F-3)" \
    "$expected"
done

# A test-source-set alias remains legitimate and is green on HEAD too through
# the enclosing test block; the current resolver must not turn it red.
cp -r "$TMP/clean" "$TMP/srcdir_f1_alias_test_inside"
cat >> "$TMP/srcdir_f1_alias_test_inside/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("test") {
        val testSs = sourceSets.getByName("test")
        testSs.java.srcDir("src/test")
    }
}
KTS
expect_pass "$TMP/srcdir_f1_alias_test_inside" \
  "a test source-set receiver alias stays green (F-3 testSs control)"

# --- Re-review 4: alias shadowing, trailing lambdas, grouping/casts --------
# A same-file declaration shadows a built-in source-set name.  These fixtures
# are the exact fail-open shapes reported in Re-review 4: without resolver
# precedence, `test`/`androidTest`/`testFixtures` (and `var` reassignment)
# silently select the built-in test source set and the gate goes green.
for case_name in r4_alias_test_inside r4_alias_test_outside \
    r4_alias_androidtest_outside r4_alias_testfixtures_outside \
    r4_alias_test_require_inside r4_alias_test_with_inside \
    r4_alias_test_with_outside r4_var_test_inside r4_var_test_outside \
    r4_var_androidtest_outside; do
  d="$TMP/srcdir_r4_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    r4_alias_test_inside) payload='    sourceSets.getByName("test") {
        val test = sourceSets.getByName("main")
        test.java.srcDir("src/test")
    }' ;;
    r4_alias_test_outside) payload='    val test = sourceSets.getByName("main")
    test.java.srcDir("src/test")' ;;
    r4_alias_androidtest_outside) payload='    val androidTest = sourceSets.getByName("main")
    androidTest.java.srcDir("src/test")' ;;
    r4_alias_testfixtures_outside) payload='    val testFixtures = sourceSets.getByName("main")
    testFixtures.java.srcDir("src/test")' ;;
    r4_alias_test_require_inside) payload='    sourceSets.getByName("test") {
        val test = sourceSets.getByName("main")
        requireNotNull(test).java.srcDir("src/test")
    }' ;;
    r4_alias_test_with_inside) payload='    sourceSets.getByName("test") {
        val test = sourceSets.getByName("main")
        with(test) { java.srcDir("src/test") }
    }' ;;
    r4_alias_test_with_outside) payload='    val test = sourceSets.getByName("main")
    with(test) { java.srcDir("src/test") }' ;;
    r4_var_test_inside) payload='    sourceSets.getByName("test") {
        var test = sourceSets.getByName("test")
        test = sourceSets.getByName("main")
        test.java.srcDir("src/test")
    }' ;;
    r4_var_test_outside) payload='    var test = sourceSets.getByName("test")
    test = sourceSets.getByName("main")
    test.java.srcDir("src/test")' ;;
    r4_var_androidtest_outside) payload='    var androidTest = sourceSets.getByName("test")
    androidTest = sourceSets.getByName("main")
    androidTest.java.srcDir("src/test")' ;;
  esac
  printf '\nandroid {\n%s\n}\n' "$payload" >> "$d/app/build.gradle.kts"
  if [ "$case_name" = r4_var_test_inside ] ||
    [ "$case_name" = r4_var_test_outside ] ||
    [ "$case_name" = r4_var_androidtest_outside ]; then
    expect_fail "$d" \
      "reassigned var shadows a built-in source-set name ($case_name, R4)" \
      "main source set source dir cannot be resolved to a safe path (fail closed)"
  else
    expect_fail "$d" \
      "alias shadows a built-in source-set name ($case_name, R4)" \
      "main source set adds a test/build source dir"
  fi
done

# Trailing-lambda results decide the receiver of a following call.  The last
# expression is `main` in every red case; `this`/test expressions are green.
for case_name in r4_with_lambda_main_outside r4_with_lambda_main_inside \
    r4_with_let_main_outside r4_with_let_main_inside \
    r4_source_let_main_outside r4_source_run_main_outside \
    r4_test_let_main_outside r4_test_let_main_inside \
    r4_test_run_main_inside r4_require_let_main_inside \
    r4_with_lambda_comment_outside r4_with_lambda_comment_inside \
    r4_with_let_notnull_inside r4_with_let_safe_inside \
    r4_with_require_lambda_main r4_with_run_this_test \
    r4_with_this_test r4_with_explicit_test_body \
    r4_let_explicit_main_body r4_run_explicit_main_body \
    r4_alias_lambda_body r4_apply_body_main r4_also_body_main \
    r4_last_expr_test_then_main r4_last_expr_newline \
    r4_with_unknown_body r4_with_body_java_inside \
    r4_lambda_body_alias_test_positive r4_lambda_body_alias_main; do
  d="$TMP/srcdir_r4_lambda_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    r4_with_lambda_main_outside) payload='    with(test) { main }.java.srcDir("src/test")' ;;
    r4_with_lambda_main_inside) payload='    sourceSets.getByName("test") {
        with(test) { main }.java.srcDir("src/test")
    }' ;;
    r4_with_let_main_outside) payload='    with(test).let { main }.java.srcDir("src/test")' ;;
    r4_with_let_main_inside) payload='    sourceSets.getByName("test") {
        with(test).let { main }.java.srcDir("src/test")
    }' ;;
    r4_source_let_main_outside) payload='    sourceSets.getByName("test").let { main }.java.srcDir("src/test")' ;;
    r4_source_run_main_outside) payload='    sourceSets.getByName("test").run { main }.java.srcDir("src/test")' ;;
    r4_test_let_main_outside) payload='    test.let { main }.java.srcDir("src/test")' ;;
    r4_test_let_main_inside) payload='    sourceSets.getByName("test") {
        test.let { main }.java.srcDir("src/test")
    }' ;;
    r4_test_run_main_inside) payload='    sourceSets.getByName("test") {
        test.run { main }.java.srcDir("src/test")
    }' ;;
    r4_require_let_main_inside) payload='    sourceSets.getByName("test") {
        requireNotNull(test).let { main }.java.srcDir("src/test")
    }' ;;
    r4_with_lambda_comment_outside) payload='    with(test) /* c */
    .let { main }
    .java.srcDir("src/test")' ;;
    r4_with_lambda_comment_inside) payload='    sourceSets.getByName("test") {
        with(test) /* c */
        .let { main }
        .java.srcDir("src/test")
    }' ;;
    r4_with_let_notnull_inside) payload='    sourceSets.getByName("test") {
        with(test)!!.let { main }!!.java.srcDir("src/test")
    }' ;;
    r4_with_let_safe_inside) payload='    sourceSets.getByName("test") {
        with(test)?.let { main }?.java?.srcDir("src/test")
    }' ;;
    r4_with_require_lambda_main) payload='    with(requireNotNull(test)) { main }.java.srcDir("src/test")' ;;
    r4_with_run_this_test) payload='    with(test).run { this }.java.srcDir("src/test")' ;;
    r4_with_this_test) payload='    with(test) { this }.java.srcDir("src/test")' ;;
    r4_with_explicit_test_body) payload='    with(test) { sourceSets.getByName("test") }.java.srcDir("src/test")' ;;
    r4_let_explicit_main_body) payload='    with(test).let { sourceSets.getByName("main") }.java.srcDir("src/test")' ;;
    r4_run_explicit_main_body) payload='    sourceSets.getByName("test").run { sourceSets.getByName("main") }.java.srcDir("src/test")' ;;
    r4_alias_lambda_body) payload='    val mainSet = sourceSets.getByName("main")
    with(test) { mainSet }.java.srcDir("src/test")' ;;
    r4_apply_body_main) payload='    with(test).apply { main }.java.srcDir("src/test")' ;;
    r4_also_body_main) payload='    with(test).also { main }.java.srcDir("src/test")' ;;
    r4_last_expr_test_then_main) payload='    with(test) { test; main }.java.srcDir("src/test")' ;;
    r4_last_expr_newline) payload='    with(test) {
        test
        main
    }.java.srcDir("src/test")' ;;
    r4_with_unknown_body) payload='    with(test) { mystery }.java.srcDir("src/test")' ;;
    r4_with_body_java_inside) payload='    sourceSets.getByName("test") {
        with(test) { java }.java.srcDir("src/test")
    }' ;;
    r4_lambda_body_alias_test_positive) payload='    with(test) {
        val testSet = sourceSets.getByName("test")
        testSet
    }.java.srcDir("src/test")' ;;
    r4_lambda_body_alias_main) payload='    with(test) {
        val mainSet = sourceSets.getByName("main")
        mainSet
    }.java.srcDir("src/test")' ;;
  esac
  printf '\nandroid {\n%s\n}\n' "$payload" >> "$d/app/build.gradle.kts"
  case "$case_name" in
    r4_with_run_this_test|r4_with_this_test|r4_with_explicit_test_body|\
    r4_apply_body_main|r4_also_body_main|r4_lambda_body_alias_test_positive)
      expect_pass "$d" \
        "trailing-lambda result resolves to the test source set ($case_name, R4)" ;;
    r4_with_unknown_body|r4_with_body_java_inside)
      expect_fail "$d" \
        "an unprovable trailing-lambda result fails closed ($case_name, R4)" \
        "main source set source dir cannot be resolved to a safe path (fail closed)" ;;
    *)
      expect_fail "$d" \
        "trailing-lambda result reaches main ($case_name, R4)" \
        "main source set adds a test/build source dir" ;;
  esac
done

# Grouping parentheses and casts are stripped before alias resolution; an
# unresolved base still fails closed instead of inheriting the test block.
for case_name in r4_alias_grouped_inside r4_alias_cast_inside \
    r4_alias_cast_nullable r4_alias_asserted r4_alias_double_group \
    r4_grouped_with_alias r4_grouped_explicit_main \
    r4_grouped_explicit_test r4_alias_grouped_test_positive \
    r4_cast_test_positive r4_unknown_cast; do
  d="$TMP/srcdir_r4_group_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/src/test/java/com/example"
  cp "$TMP/srcdir_f1_wire/app/src/test/java/com/example/Wired.java" \
    "$d/app/src/test/java/com/example/Wired.java"
  case "$case_name" in
    r4_alias_grouped_inside) payload='    sourceSets.getByName("test") {
        val mainSet = sourceSets.getByName("main")
        (mainSet).java.srcDir("src/test")
    }' ;;
    r4_alias_cast_inside) payload='    sourceSets.getByName("test") {
        val mainSet = sourceSets.getByName("main")
        (mainSet as SourceSet).java.srcDir("src/test")
    }' ;;
    r4_alias_cast_nullable) payload='    val mainSet = sourceSets.getByName("main")
    (mainSet as SourceSet?).java.srcDir("src/test")' ;;
    r4_alias_asserted) payload='    val mainSet = sourceSets.getByName("main")
    (mainSet)!!.java.srcDir("src/test")' ;;
    r4_alias_double_group) payload='    val mainSet = sourceSets.getByName("main")
    ((mainSet)).java.srcDir("src/test")' ;;
    r4_grouped_with_alias) payload='    val mainSet = sourceSets.getByName("main")
    with((mainSet)) { java.srcDir("src/test") }' ;;
    r4_grouped_explicit_main) payload='    (sourceSets.getByName("main")).java.srcDir("src/test")' ;;
    r4_grouped_explicit_test) payload='    (sourceSets.getByName("test")).java.srcDir("src/test")' ;;
    r4_alias_grouped_test_positive) payload='    val testSet = sourceSets.getByName("test")
    (testSet).java.srcDir("src/test")' ;;
    r4_cast_test_positive) payload='    val testSet = sourceSets.getByName("test")
    with((testSet) as SourceSet) { java.srcDir("src/test") }' ;;
    r4_unknown_cast) payload='    (mystery as SourceSet).java.srcDir("src/test")' ;;
  esac
  printf '\nandroid {\n%s\n}\n' "$payload" >> "$d/app/build.gradle.kts"
  if [ "$case_name" = r4_grouped_explicit_test ] ||
    [ "$case_name" = r4_alias_grouped_test_positive ] ||
    [ "$case_name" = r4_cast_test_positive ]; then
    expect_pass "$d" \
      "grouped/cast test receiver stays green ($case_name, R4)"
  elif [ "$case_name" = r4_unknown_cast ]; then
    expect_fail "$d" \
      "unresolved grouped/cast receiver fails closed ($case_name, R4)" \
      "main source set source dir cannot be resolved to a safe path (fail closed)"
  else
    expect_fail "$d" \
      "grouped/cast receiver resolves to main ($case_name, R4)" \
      "main source set adds a test/build source dir"
  fi
done

# The four top-level Re-review 4 positives must also stay green.
for case_name in r4p_checknotnull_test r4p_require_test \
    r4p_with_bare_test r4p_with_test; do
  d="$TMP/srcdir_r4pos_$case_name"
  cp -r "$TMP/clean" "$d"
  case "$case_name" in
    r4p_checknotnull_test) snippet='    checkNotNull(test).java.srcDir("src/test")' ;;
    r4p_require_test) snippet='    requireNotNull(test).java.srcDir("src/test")' ;;
    r4p_with_bare_test) snippet='    with(test) {
        java.srcDir("src/test")
    }' ;;
    r4p_with_test) snippet='    with(sourceSets.getByName("test")) {
        java.srcDir("src/test")
    }' ;;
  esac
  printf '\nandroid {\n%s\n}\n' "$snippet" >> "$d/app/build.gradle.kts"
  expect_pass "$d" \
    "top-level test receiver wrapper stays green ($case_name, R4)"
done

# --- Stage1 fix4: scope-aware val/var/def resolution (G-19) ----------------
# A sibling test-block declaration must not shadow a main declaration, and a
# declaration named srcDir is not an assignment.

cp -r "$TMP/clean" "$TMP/srcdir_g19_shadowed_main_build"
cat >> "$TMP/srcdir_g19_shadowed_main_build/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        val gen = "build/gen"
        java.srcDir(gen)
    }
    sourceSets.getByName("test") {
        val gen = "extra/java"
        java.srcDir(gen)
    }
}
KTS
expect_fail "$TMP/srcdir_g19_shadowed_main_build" \
  "sibling test val does not shadow a main build val (G-19 X16)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g19_shadowed_main_build_control"
cat >> "$TMP/srcdir_g19_shadowed_main_build_control/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        val gen = "build/gen"
        java.srcDir(gen)
    }
}
KTS
expect_fail "$TMP/srcdir_g19_shadowed_main_build_control" \
  "the same main build val without a shadow is correctly flagged (G-19 X16b)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g19_sibling_test_dir"
cat >> "$TMP/srcdir_g19_sibling_test_dir/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        val d = "extra/java"
        java.srcDir(d)
    }
    sourceSets.getByName("test") {
        val d = "src/test"
        java.srcDir(d)
    }
}
KTS
expect_pass "$TMP/srcdir_g19_sibling_test_dir" \
  "sibling test val does not false-red a safe main val (G-19 X17)"

cp -r "$TMP/clean" "$TMP/srcdir_g19_top_level_srcdir_name"
cat >> "$TMP/srcdir_g19_top_level_srcdir_name/app/build.gradle.kts" <<'KTS'

val srcDir = "src/test"

android {
    sourceSets.getByName("test") {
        java.srcDir(srcDir)
    }
}
KTS
expect_pass "$TMP/srcdir_g19_top_level_srcdir_name" \
  "val named srcDir is a declaration, not an assignment (G-19 X11)"

cp -r "$TMP/clean" "$TMP/srcdir_g19_test_local_srcdir"
cat >> "$TMP/srcdir_g19_test_local_srcdir/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("test") {
        val srcDir = "src/test"
        java.srcDir(srcDir)
    }
}
KTS
expect_pass "$TMP/srcdir_g19_test_local_srcdir" \
  "test-local val named srcDir stays green (G-19 X6)"

cp -r "$TMP/clean" "$TMP/srcdir_g19_main_test_val"
cat >> "$TMP/srcdir_g19_main_test_val/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        val d = "src/test"
        java.srcDir(d)
    }
}
KTS
expect_fail "$TMP/srcdir_g19_main_test_val" \
  "main val resolving to src/test is flagged (G-19 X2)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g19_named_srcDir_main"
cat >> "$TMP/srcdir_g19_named_srcDir_main/app/build.gradle.kts" <<'KTS'

val srcDir = "src/test"

android {
    sourceSets.getByName("main") {
        java.srcDir(srcDir)
    }
}
KTS
expect_fail "$TMP/srcdir_g19_named_srcDir_main" \
  "a val named srcDir is still resolved when used by main (G-19)" \
  "main source set adds a test/build source dir"

# --- Stage1 fix5: var/def assignments invalidate the declaration (G-25) ----

for case_name in b1 b2 b3; do
  d="$TMP/srcdir_g25_$case_name"
  cp -r "$TMP/clean" "$d"
  mkdir -p "$d/app/build/gen/com/example"
  cat > "$d/app/build/gen/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
  case "$case_name" in
    b1) snippet='    sourceSets.getByName("main") {
        var gen = "extra/java"
        gen = "build/gen"
        java.srcDir(gen)
    }' ;;
    b2) snippet='var d = "extra/java"

android {
    sourceSets.getByName("main") {
        d = "build/gen"
        java.srcDir(d)
    }
}' ;;
    b3) snippet='    sourceSets.getByName("main") {
        var gen = "extra/java"
        run {
            gen = "build/gen"
        }
        java.srcDir(gen)
    }' ;;
  esac
  if [ "$case_name" = "b2" ]; then
    printf '
%s
' "$snippet" >> "$d/app/build.gradle.kts"
  else
    printf '
android {
%s
}
' "$snippet" >> "$d/app/build.gradle.kts"
  fi
  expect_fail "$d" \
    "a reassigned var before srcDir fails closed ($case_name, G-25)" \
    "main source set source dir cannot be resolved to a safe path"
done

cp -r "$TMP/clean" "$TMP/srcdir_g25_b1b_control"
cat >> "$TMP/srcdir_g25_b1b_control/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main") {
        var gen = "extra/java"
        java.srcDir(gen)
    }
}
KTS
expect_pass "$TMP/srcdir_g25_b1b_control" \
  "an unassigned var still resolves to its safe initial value (G-25 B1b)"

cp -r "$TMP/clean" "$TMP/srcdir_g25_b4_groovy_build"
cat > "$TMP/srcdir_g25_b4_groovy_build/app/extra.gradle" <<'GRADLE'
android {
    sourceSets.getByName("main") {
        def gen = "extra/java"
        def gen = "build/gen"
        java.srcDir(gen)
    }
}
GRADLE
expect_fail "$TMP/srcdir_g25_b4_groovy_build" \
  "Groovy duplicate def resolves to the nearest preceding value (G-25 B4)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g25_b5_groovy_safe"
cat > "$TMP/srcdir_g25_b5_groovy_safe/app/extra.gradle" <<'GRADLE'
android {
    sourceSets.getByName("main") {
        def gen = "build/gen"
        def gen = "extra/java"
        java.srcDir(gen)
    }
}
GRADLE
expect_pass "$TMP/srcdir_g25_b5_groovy_safe" \
  "Groovy duplicate def nearest safe value stays green (G-25 B5)"

# --- Stage1 fix5: backticked receivers (G-26) ------------------------------

cp -r "$TMP/clean" "$TMP/srcdir_g26_backtick_receivers"
cat >> "$TMP/srcdir_g26_backtick_receivers/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.`getByName`("test").java.srcDir("src/test")
    sourceSets.`create`("testCreate").java.srcDir("src/testCreate")
    sourceSets.`named`("testNamed").java.srcDir("src/testNamed")
    sourceSets["testIndex"].java.srcDir("src/testIndex")
    sourceSets.`testProp`.java.srcDir("src/testProp")
}
KTS
expect_pass "$TMP/srcdir_g26_backtick_receivers" \
  "backticked test source-set receivers stay green (G-26)"

cp -r "$TMP/clean" "$TMP/srcdir_g26_backtick_main"
cat >> "$TMP/srcdir_g26_backtick_main/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.`getByName`("main").java.srcDir("src/test")
}
KTS
expect_fail "$TMP/srcdir_g26_backtick_main" \
  "a backticked main receiver is still flagged (G-26 D12)" \
  "main source set adds a test/build source dir"

# --- Stage1 fix5: typed srcDir declarations/parameters (G-27) --------------

cp -r "$TMP/clean" "$TMP/srcdir_g27_typed_val"
cat >> "$TMP/srcdir_g27_typed_val/app/build.gradle.kts" <<'KTS'

val srcDir: String = "src/test"

android {
    sourceSets.getByName("test") {
        java.srcDir(srcDir)
    }
}
KTS
expect_pass "$TMP/srcdir_g27_typed_val" \
  "a typed val named srcDir is not a command-syntax call (G-27 D29)"

cp -r "$TMP/clean" "$TMP/srcdir_g27_helper_param"
cat >> "$TMP/srcdir_g27_helper_param/app/build.gradle.kts" <<'KTS'

fun copyInto(srcDir: String) {
    println(srcDir)
}
KTS
expect_pass "$TMP/srcdir_g27_helper_param" \
  "a function parameter named srcDir is not a call (G-27 D30)"

cp -r "$TMP/clean" "$TMP/srcdir_g27_typed_val_main"
cat >> "$TMP/srcdir_g27_typed_val_main/app/build.gradle.kts" <<'KTS'

val srcDir: String = "src/test"

android {
    sourceSets.getByName("main") {
        java.srcDir(srcDir)
    }
}
KTS
expect_fail "$TMP/srcdir_g27_typed_val_main" \
  "a typed val used by main is still resolved and flagged (G-27 D31)" \
  "main source set adds a test/build source dir"

# --- Stage1 fix5: triple-quoted srcDir literals (G-28) ---------------------

cp -r "$TMP/clean" "$TMP/srcdir_g28_triple_safe"
cat >> "$TMP/srcdir_g28_triple_safe/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("""extra/java""")
}
KTS
expect_pass "$TMP/srcdir_g28_triple_safe" \
  "a triple-quoted safe srcDir literal stays green (G-28)"

cp -r "$TMP/clean" "$TMP/srcdir_g28_triple_test"
cat >> "$TMP/srcdir_g28_triple_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("""src/test""")
}
KTS
expect_fail "$TMP/srcdir_g28_triple_test" \
  "a triple-quoted test srcDir literal is flagged for the real reason (G-28)" \
  "main source set adds a test/build source dir"

# --- Stage1 fix4: cross-module srcDir paths use module roots (G-20) --------
# A srcDir escaping the module is resolved against settings-derived roots.

# G-20 uses a real second module: `../app/...` normalizes back to the app
# module and would only re-test the in-module rules.
add_shared_module() {
  local d="$1"
  cat > "$d/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
include(":shared")
KTS
  mkdir -p "$d/shared/src/main/java/com/example"
  cat > "$d/shared/src/main/java/com/example/Clean.kt" <<'KT'
package com.example
internal class Clean
KT
}

cp -r "$TMP/clean" "$TMP/srcdir_g20_escape_test"
add_shared_module "$TMP/srcdir_g20_escape_test"
mkdir -p "$TMP/srcdir_g20_escape_test/shared/src/test/java/com/example"
cat > "$TMP/srcdir_g20_escape_test/shared/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA
cat >> "$TMP/srcdir_g20_escape_test/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("../shared/src/test")
}
KTS
expect_fail "$TMP/srcdir_g20_escape_test" \
  "../shared/src/test is another module's test tree (G-20 X3)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g20_escape_build"
add_shared_module "$TMP/srcdir_g20_escape_build"
mkdir -p "$TMP/srcdir_g20_escape_build/shared/build/gen/com/example"
cat > "$TMP/srcdir_g20_escape_build/shared/build/gen/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
cat >> "$TMP/srcdir_g20_escape_build/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("../shared/build/gen")
}
KTS
expect_fail "$TMP/srcdir_g20_escape_build" \
  "../shared/build/gen is another module's build tree (G-20 X4)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g20_escape_testfixtures"
add_shared_module "$TMP/srcdir_g20_escape_testfixtures"
mkdir -p "$TMP/srcdir_g20_escape_testfixtures/shared/src/testFixtures/java/com/example"
cat > "$TMP/srcdir_g20_escape_testfixtures/shared/src/testFixtures/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer; }
JAVA
cat >> "$TMP/srcdir_g20_escape_testfixtures/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("../shared/src/testFixtures")
}
KTS
expect_fail "$TMP/srcdir_g20_escape_testfixtures" \
  "../shared/src/testFixtures is another module's test tree (G-20)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g20_escape_build_generated"
add_shared_module "$TMP/srcdir_g20_escape_build_generated"
mkdir -p "$TMP/srcdir_g20_escape_build_generated/shared/build/generated/com/example"
cat > "$TMP/srcdir_g20_escape_build_generated/shared/build/generated/com/example/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
cat >> "$TMP/srcdir_g20_escape_build_generated/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("../shared/build/generated")
}
KTS
expect_fail "$TMP/srcdir_g20_escape_build_generated" \
  "../shared/build/generated is another module's build tree (G-20)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/srcdir_g20_shared_main"
add_shared_module "$TMP/srcdir_g20_shared_main"
cat >> "$TMP/srcdir_g20_shared_main/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("../shared/src/main")
}
KTS
expect_pass "$TMP/srcdir_g20_shared_main" \
  "../shared/src/main is another module's production root (G-20)"

cp -r "$TMP/clean" "$TMP/srcdir_g20_unknown_internal"
cat >> "$TMP/srcdir_g20_unknown_internal/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("../unknown/src/main")
}
KTS
expect_fail "$TMP/srcdir_g20_unknown_internal" \
  "repo-internal path outside known modules is unresolved (G-20)" \
  "main source set source dir cannot be resolved to a safe path"

cp -r "$TMP/clean" "$TMP/srcdir_g20_outside_repo"
cat >> "$TMP/srcdir_g20_outside_repo/app/build.gradle.kts" <<'KTS'

android {
    sourceSets.getByName("main").java.srcDir("../../outside/src/main")
}
KTS
expect_fail "$TMP/srcdir_g20_outside_repo" \
  "path outside the repo root is unresolved (G-20)" \
  "main source set source dir cannot be resolved to a safe path"

# --- Stage1 fix4: structural settings parsing (G-21) ----------------------
# String contents are not settings code; non-literal/conditional/helper calls
# make the settings file untrusted (repo-root-only pruning, fail closed).

cp -r "$TMP/clean" "$TMP/settings_g21_string_include"
cat > "$TMP/settings_g21_string_include/settings.gradle.kts" <<'KTS'
val note = "include(':evil')"
KTS
mkdir -p "$TMP/settings_g21_string_include/evil/build/gen"
cat > "$TMP/settings_g21_string_include/evil/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_string_include" \
  "include text inside a string is not a settings call (G-21 XS9)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_string_include_control"
cat > "$TMP/settings_g21_string_include_control/settings.gradle.kts" <<'KTS'
val note = "include(':evil')"
include(":app")
KTS
mkdir -p "$TMP/settings_g21_string_include_control/evil/build/gen"
cat > "$TMP/settings_g21_string_include_control/evil/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_string_include_control" \
  "a string include cannot hide an actually unincluded module (G-21 XS9_control)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_string_includebuild"
cat > "$TMP/settings_g21_string_includebuild/settings.gradle.kts" <<'KTS'
val note = "includeBuild('evil')"
KTS
mkdir -p "$TMP/settings_g21_string_includebuild/evil/build/gen"
cat > "$TMP/settings_g21_string_includebuild/evil/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_string_includebuild" \
  "includeBuild text inside a string is not a settings call (G-21 XS10)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_string_concat_include"
cat > "$TMP/settings_g21_string_concat_include/settings.gradle.kts" <<'KTS'
val note = "includeBuild(" + "'evil'" + ")"
include(":app")
KTS
mkdir -p "$TMP/settings_g21_string_concat_include/evil/build/gen"
cat > "$TMP/settings_g21_string_concat_include/evil/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_string_concat_include" \
  "concatenated include text inside a string is not a call (G-21 XS11)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_expr_include"
cat > "$TMP/settings_g21_expr_include/settings.gradle.kts" <<'KTS'
include(":app".uppercase())
KTS
mkdir -p "$TMP/settings_g21_expr_include/app/build/gen"
cat > "$TMP/settings_g21_expr_include/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_expr_include" \
  "non-literal include argument disables module pruning (G-21 XS12)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_expr_include_control"
cat > "$TMP/settings_g21_expr_include_control/settings.gradle.kts" <<'KTS'
include(":APP")
KTS
mkdir -p "$TMP/settings_g21_expr_include_control/app/build/gen"
cat > "$TMP/settings_g21_expr_include_control/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_expr_include_control" \
  "include(:APP) does not prune the lowercase app module (G-21 XS12b)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_conditional_include"
cat > "$TMP/settings_g21_conditional_include/settings.gradle.kts" <<'KTS'
include(":app")
if (false) {
    include(":evil")
}
KTS
mkdir -p "$TMP/settings_g21_conditional_include/app/build/gen"
cat > "$TMP/settings_g21_conditional_include/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_conditional_include" \
  "include under if(false) makes settings untrusted (G-21 XS13)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_conditional_true_control"
cat > "$TMP/settings_g21_conditional_true_control/settings.gradle.kts" <<'KTS'
include(":app")
if (true) {
    include(":evil")
}
KTS
mkdir -p "$TMP/settings_g21_conditional_true_control/app/build/gen"
cat > "$TMP/settings_g21_conditional_true_control/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_conditional_true_control" \
  "conditional include cannot be trusted as top-level app pruning (G-21 XS13b)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_single_statement_if"
cat > "$TMP/settings_g21_single_statement_if/settings.gradle.kts" <<'KTS'
include(":app")
if (false)
    include(":evil")
KTS
mkdir -p "$TMP/settings_g21_single_statement_if/app/build/gen"
cat > "$TMP/settings_g21_single_statement_if/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_single_statement_if" \
  "single-statement if include is not a top-level include (G-21 XS15)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_dynamic_include"
cat > "$TMP/settings_g21_dynamic_include/settings.gradle.kts" <<'KTS'
val mods = listOf(":evil")
include(mods.first())
KTS
mkdir -p "$TMP/settings_g21_dynamic_include/evil/build/gen"
cat > "$TMP/settings_g21_dynamic_include/evil/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_dynamic_include" \
  "dynamic include argument fails closed (G-21 XS5)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_helper_include"
cat > "$TMP/settings_g21_helper_include/settings.gradle.kts" <<'KTS'
fun helper() { include(":evil") }
include(":app")
KTS
mkdir -p "$TMP/settings_g21_helper_include/evil/build/gen"
cat > "$TMP/settings_g21_helper_include/evil/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_helper_include" \
  "include inside a helper function fails closed (G-21 XS7)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_lambda_include"
cat > "$TMP/settings_g21_lambda_include/settings.gradle.kts" <<'KTS'
listOf(":evil").forEach { include(it) }
include(":app")
KTS
mkdir -p "$TMP/settings_g21_lambda_include/evil/build/gen"
cat > "$TMP/settings_g21_lambda_include/evil/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_lambda_include" \
  "include inside a lambda fails closed (G-21 XS8)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_literal_include"
cat > "$TMP/settings_g21_literal_include/settings.gradle.kts" <<'KTS'
include(":app")
KTS
mkdir -p "$TMP/settings_g21_literal_include/app/build/gen"
cat > "$TMP/settings_g21_literal_include/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/settings_g21_literal_include" \
  "literal top-level include prunes a wired app/build (G-21 XS1)"

cp -r "$TMP/clean" "$TMP/settings_g21_concat_include"
cat > "$TMP/settings_g21_concat_include/settings.gradle.kts" <<'KTS'
include(":" + "app")
KTS
mkdir -p "$TMP/settings_g21_concat_include/app/build/gen"
cat > "$TMP/settings_g21_concat_include/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/settings_g21_concat_include" \
  "literal concatenation include prunes a wired app/build (G-21 XS2)"

cp -r "$TMP/clean" "$TMP/settings_g21_includebuild"
cat > "$TMP/settings_g21_includebuild/settings.gradle.kts" <<'KTS'
includeBuild("inc")
KTS
mkdir -p "$TMP/settings_g21_includebuild/inc/build/gen"
cat > "$TMP/settings_g21_includebuild/inc/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/settings_g21_includebuild" \
  "literal includeBuild prunes its wired build tree (G-21 XS4)"

# G-21 XS6a: a non-default mapping must actually be honoured (include(":app")
# alone would already prune <root>/app, so that does not exercise the override).
cp -r "$TMP/clean" "$TMP/settings_g21_projectdir"
cat > "$TMP/settings_g21_projectdir/settings.gradle.kts" <<'KTS'
include(":renamed")
project(":renamed").projectDir = file("modules/app")
KTS
mkdir -p "$TMP/settings_g21_projectdir/modules/app/build/gen"
cat > "$TMP/settings_g21_projectdir/modules/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/settings_g21_projectdir" \
  "literal projectDir override prunes a non-default wired modules/app/build (G-21 XS6a)"

# The same wiring without the override must be scanned: this pins that the
# pass above depends on the projectDir mapping, not on include(":renamed").
cp -r "$TMP/clean" "$TMP/settings_g21_projectdir_missing"
cat > "$TMP/settings_g21_projectdir_missing/settings.gradle.kts" <<'KTS'
include(":renamed")
KTS
mkdir -p "$TMP/settings_g21_projectdir_missing/modules/app/build/gen"
cat > "$TMP/settings_g21_projectdir_missing/modules/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_projectdir_missing" \
  "dropping the projectDir override scans modules/app/build (G-21)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g21_projectdir_dynamic"
cat > "$TMP/settings_g21_projectdir_dynamic/settings.gradle.kts" <<'KTS'
include(":renamed")
project(":renamed").projectDir = file(providers.gradleProperty("appDir").get())
KTS
mkdir -p "$TMP/settings_g21_projectdir_dynamic/modules/app/build/gen"
cat > "$TMP/settings_g21_projectdir_dynamic/modules/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g21_projectdir_dynamic" \
  "a dynamic projectDir override makes settings untrusted (G-21)" \
  "outside dev/librepocket/mcp/ references MCP types"

# --- Stage1 fix5: Groovy command-syntax settings (G-29) -------------------
# `include ':app'` / `includeBuild 'inc'` are valid Groovy-DSL command calls
# and must be parsed like the parenthesised forms.

cp -r "$TMP/clean" "$TMP/settings_g29_groovy_include"
cat > "$TMP/settings_g29_groovy_include/settings.gradle" <<'GRADLE'
rootProject.name = 'fixture'
include ':app', ':lib'
GRADLE
mkdir -p "$TMP/settings_g29_groovy_include/app/build/gen"
cat > "$TMP/settings_g29_groovy_include/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/settings_g29_groovy_include" \
  "Groovy command-syntax include prunes a wired app/build (G-29)"

cp -r "$TMP/clean" "$TMP/settings_g29_groovy_includebuild"
cat > "$TMP/settings_g29_groovy_includebuild/settings.gradle" <<'GRADLE'
rootProject.name = 'fixture'
includeBuild 'inc'
GRADLE
mkdir -p "$TMP/settings_g29_groovy_includebuild/inc/build/gen"
cat > "$TMP/settings_g29_groovy_includebuild/inc/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_pass "$TMP/settings_g29_groovy_includebuild" \
  "Groovy command-syntax includeBuild prunes a wired inc/build (G-29)"

cp -r "$TMP/clean" "$TMP/settings_g29_groovy_dynamic"
cat > "$TMP/settings_g29_groovy_dynamic/settings.gradle" <<'GRADLE'
rootProject.name = 'fixture'
def mods = [':app']
include mods.first()
GRADLE
mkdir -p "$TMP/settings_g29_groovy_dynamic/app/build/gen"
cat > "$TMP/settings_g29_groovy_dynamic/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g29_groovy_dynamic" \
  "Groovy command-syntax dynamic include fails closed (G-29)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/settings_g29_groovy_conditional"
cat > "$TMP/settings_g29_groovy_conditional/settings.gradle" <<'GRADLE'
rootProject.name = 'fixture'
include ':app'
if (false) include ':evil'
GRADLE
mkdir -p "$TMP/settings_g29_groovy_conditional/app/build/gen"
cat > "$TMP/settings_g29_groovy_conditional/app/build/gen/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/settings_g29_groovy_conditional" \
  "conditional command-syntax include makes settings untrusted (G-29)" \
  "outside dev/librepocket/mcp/ references MCP types"

# --- Stage1 fix4: a module named build is not pruned (G-22) ----------------

cp -r "$TMP/clean" "$TMP/module_g22_named_build"
cat > "$TMP/module_g22_named_build/settings.gradle.kts" <<'KTS'
include(":build")
KTS
mkdir -p "$TMP/module_g22_named_build/build/src/main"
cat > "$TMP/module_g22_named_build/build/src/main/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/module_g22_named_build" \
  "include(:build) keeps the build module's production sources (G-22 X18)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/module_g22_named_core"
cat > "$TMP/module_g22_named_core/settings.gradle.kts" <<'KTS'
include(":core")
KTS
mkdir -p "$TMP/module_g22_named_core/core/src/main"
cat > "$TMP/module_g22_named_core/core/src/main/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
expect_fail "$TMP/module_g22_named_core" \
  "include(:core) production sources are scanned (G-22 X18b)" \
  "outside dev/librepocket/mcp/ references MCP types"

# --- Final gate F-2: a module literally named build is not an output tree ---
# Its own build.gradle.kts must be scanned; only <module-root>/build output
# directories are pruned.

cp -r "$TMP/clean" "$TMP/module_f2_named_build_srcdir"
cat > "$TMP/module_f2_named_build_srcdir/settings.gradle.kts" <<'KTS'
include(":app")
include(":build")
KTS
mkdir -p "$TMP/module_f2_named_build_srcdir/build/src/test/java/com/example"
cat > "$TMP/module_f2_named_build_srcdir/build/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer.class; }
JAVA
cat > "$TMP/module_f2_named_build_srcdir/build/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android {
    namespace = "com.example.buildmod"
    sourceSets.getByName("main").java.srcDir("src/test")
}
KTS
expect_fail "$TMP/module_f2_named_build_srcdir" \
  "include(:build) build file main srcDir is scanned (F-2 N3)" \
  "main source set adds a test/build source dir"

cp -r "$TMP/clean" "$TMP/module_f2_named_build_placeholder"
cat > "$TMP/module_f2_named_build_placeholder/settings.gradle.kts" <<'KTS'
include(":app")
include(":build")
KTS
mkdir -p "$TMP/module_f2_named_build_placeholder/build/src/test/java/com/example"
cat > "$TMP/module_f2_named_build_placeholder/build/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer.class; }
JAVA
cat > "$TMP/module_f2_named_build_placeholder/build/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android {
    namespace = "com.example.buildmod"
    defaultConfig { manifestPlaceholders["mcpHost"] = "dev.librepocket.mcp.McpServer" }
}
KTS
expect_fail "$TMP/module_f2_named_build_placeholder" \
  "include(:build) build file manifestPlaceholders is scanned (F-2 N9)" \
  "manifest placeholders request MCP components"

# An untrusted root settings file cannot prove that `build` is output.  The
# module-roots helper must fail closed instead of returning only the repo root,
# otherwise this build file (and the module's sources/manifest) are pruned.
cp -r "$TMP/clean" "$TMP/module_f2_untrusted_named_build"
cat > "$TMP/module_f2_untrusted_named_build/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
include(":build")
include(providers.gradleProperty("extraModules").get())
KTS
mkdir -p "$TMP/module_f2_untrusted_named_build/build/src/test/java/com/example"
cat > "$TMP/module_f2_untrusted_named_build/build/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer.class; }
JAVA
cat > "$TMP/module_f2_untrusted_named_build/build/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android {
    namespace = "com.example.buildmod"
    defaultConfig { manifestPlaceholders["mcpHost"] = "dev.librepocket.mcp.McpServer" }
}
KTS
expect_fail "$TMP/module_f2_untrusted_named_build" \
  "untrusted settings cannot prune a module named build (F-2 R5)" \
  "cannot determine Gradle module roots (fail closed)"

# A literal projectDir can put a real module below the root project's build
# tree.  The ancestor `build/` must stay traversable, but its own generated
# output and the nested module's own build/ output are still pruned.
cp -r "$TMP/clean" "$TMP/module_f2_projectdir_nested_build"
cat > "$TMP/module_f2_projectdir_nested_build/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
include(":renamed")
project(":renamed").projectDir = file("build/renamed")
KTS
mkdir -p "$TMP/module_f2_projectdir_nested_build/build/renamed/src/test/java/com/example"
cat > "$TMP/module_f2_projectdir_nested_build/build/renamed/src/test/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer.class; }
JAVA
cat > "$TMP/module_f2_projectdir_nested_build/build/renamed/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android {
    namespace = "com.example.renamed"
    sourceSets.getByName("main").java.srcDir("src/test")
}
KTS
expect_fail "$TMP/module_f2_projectdir_nested_build" \
  "projectDir module below build/ is scanned (F-2 ancestor pruning)" \
  "main source set adds a test/build source dir"

# Real production sources of a nested projectDir module must be scanned even
# though the module is below the root build/ tree.  This is the R-3 companion
# to the nested source-set fixture above.
cp -r "$TMP/clean" "$TMP/module_f2_projectdir_nested_source"
cat > "$TMP/module_f2_projectdir_nested_source/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
include(":renamed")
project(":renamed").projectDir = file("build/renamed")
KTS
mkdir -p "$TMP/module_f2_projectdir_nested_source/build/renamed/src/main/java/com/example"
cat > "$TMP/module_f2_projectdir_nested_source/build/renamed/src/main/java/com/example/Leak.java" <<'JAVA'
package com.example;
class Leak { Object x = dev.librepocket.mcp.McpServer.class; }
JAVA
cat > "$TMP/module_f2_projectdir_nested_source/build/renamed/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android { namespace = "com.example.renamed" }
KTS
expect_fail "$TMP/module_f2_projectdir_nested_source" \
  "nested projectDir production sources are scanned (F-2 R-3)" \
  "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types"

# A nested projectDir keeps the root build/ ancestor traversable.  Therefore a
# root build/generated Gradle file is accepted over-block, as documented: it
# is scanned and stays red rather than being silently pruned.
cp -r "$TMP/clean" "$TMP/module_f2_projectdir_nested_root_output"
cat > "$TMP/module_f2_projectdir_nested_root_output/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
include(":renamed")
project(":renamed").projectDir = file("build/renamed")
KTS
mkdir -p "$TMP/module_f2_projectdir_nested_root_output/build/renamed"
cat > "$TMP/module_f2_projectdir_nested_root_output/build/renamed/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android { namespace = "com.example.renamed" }
KTS
mkdir -p "$TMP/module_f2_projectdir_nested_root_output/build/generated"
cat > "$TMP/module_f2_projectdir_nested_root_output/build/generated/Leak.gradle.kts" <<'KTS'
android { defaultConfig { manifestPlaceholders["mcpHost"] = "dev.librepocket.mcp.McpServer" } }
KTS
expect_fail "$TMP/module_f2_projectdir_nested_root_output" \
  "documented over-block: root build/generated is scanned with nested projectDir (F-2 R-1)" \
  "manifest placeholders request MCP components"

# app/build output remains pruned even while the root build/ ancestor is kept
# traversable for the nested module.
cp -r "$TMP/clean" "$TMP/module_f2_projectdir_nested_build_app_output"
cat > "$TMP/module_f2_projectdir_nested_build_app_output/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
include(":renamed")
project(":renamed").projectDir = file("build/renamed")
KTS
mkdir -p "$TMP/module_f2_projectdir_nested_build_app_output/build/renamed"
cat > "$TMP/module_f2_projectdir_nested_build_app_output/build/renamed/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android { namespace = "com.example.renamed" }
KTS
mkdir -p "$TMP/module_f2_projectdir_nested_build_app_output/app/build/generated"
cat > "$TMP/module_f2_projectdir_nested_build_app_output/app/build/generated/Leak.gradle.kts" <<'KTS'
android { defaultConfig { manifestPlaceholders["mcpHost"] = "dev.librepocket.mcp.McpServer" } }
KTS
expect_pass "$TMP/module_f2_projectdir_nested_build_app_output" \
  "app/build generated output stays pruned with a nested projectDir module (F-2)"

# In a normal layout the root build/ tree is still output and pruned.
cp -r "$TMP/clean" "$TMP/module_f2_root_build_output"
cat > "$TMP/module_f2_root_build_output/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
KTS
mkdir -p "$TMP/module_f2_root_build_output/build/generated"
cat > "$TMP/module_f2_root_build_output/build/generated/Leak.gradle.kts" <<'KTS'
android { defaultConfig { manifestPlaceholders["mcpHost"] = "dev.librepocket.mcp.McpServer" } }
KTS
expect_pass "$TMP/module_f2_root_build_output" \
  "root build generated output stays pruned without a nested module (F-2)"

cp -r "$TMP/clean" "$TMP/module_f2_named_build_main"
cat > "$TMP/module_f2_named_build_main/settings.gradle.kts" <<'KTS'
include(":app")
include(":build")
KTS
mkdir -p "$TMP/module_f2_named_build_main/build/src/main/java/com/example"
cat > "$TMP/module_f2_named_build_main/build/src/main/java/com/example/Wired.java" <<'JAVA'
package com.example;
class Wired { Object x = dev.librepocket.mcp.McpServer.class; }
JAVA
cat > "$TMP/module_f2_named_build_main/build/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android { namespace = "com.example.buildmod" }
KTS
expect_fail "$TMP/module_f2_named_build_main" \
  "include(:build) production sources are still discovered (F-2 N3b)" \
  "outside dev/librepocket/mcp/ references MCP types"

cp -r "$TMP/clean" "$TMP/module_f2_named_build_manifest"
cat > "$TMP/module_f2_named_build_manifest/settings.gradle.kts" <<'KTS'
include(":app")
include(":build")
KTS
mkdir -p "$TMP/module_f2_named_build_manifest/build/src/main"
cat > "$TMP/module_f2_named_build_manifest/build/src/main/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application>
    <service android:name="dev.librepocket.mcp.McpServer" />
  </application>
</manifest>
XML
cat > "$TMP/module_f2_named_build_manifest/build/build.gradle.kts" <<'KTS'
plugins { id("com.android.library") }
android { namespace = "com.example.buildmod" }
KTS
expect_fail "$TMP/module_f2_named_build_manifest" \
  "include(:build) AndroidManifest is scanned (F-2 manifest control)" \
  "Android manifest registers MCP"

cp -r "$TMP/clean" "$TMP/module_g22_no_settings_build"
rm -f "$TMP/module_g22_no_settings_build/settings.gradle.kts"
mkdir -p "$TMP/module_g22_no_settings_build/build/src/main"
cat > "$TMP/module_g22_no_settings_build/build/src/main/Wired.kt" <<'KT'
package com.example.build
internal val server = dev.librepocket.mcp.McpServer::class
KT
cat > "$TMP/module_g22_no_settings_build/build/build.gradle.kts" <<'KTS'
plugins { id("java") }
KTS
expect_fail "$TMP/module_g22_no_settings_build" \
  "no-settings depth-1 module named build is scanned (G-22)" \
  "outside dev/librepocket/mcp/ references MCP types"

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

# 12. discovery fail-closed: the shared helper is missing. A green gate here
# would mean C1/C2/C3 silently stopped running.
cp -r "$TMP/clean" "$TMP/helper_missing"
rm "$TMP/helper_missing/scripts/mcp_scan.py"
expect_fail "$TMP/helper_missing" \
  "missing helper fails closed (discovery surface)" \
  "helper scripts/mcp_scan.py missing or unreadable (fail closed)"

# 12b. discovery fail-closed: the helper is syntactically broken (Python exits
# 1). The diagnostic must name the discovery failure, not misattribute the
# traceback as a list of source paths.
cp -r "$TMP/clean" "$TMP/helper_broken"
printf 'def broken(:\n' > "$TMP/helper_broken/scripts/mcp_scan.py"
expect_fail "$TMP/helper_broken" \
  "broken helper fails closed at discovery (not as path cascade)" \
  "production source discovery failed (fail closed"

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
  "namespace dev.librepocket.mcpfoo is not the mcp package (S2/N8b)"

# Positive control on the real repository (not just synthetic fixtures).
expect_pass "$ROOT" "real repository passes"

if [ "$failures" -ne 0 ]; then
  say "FAILED ($failures case(s))"
  exit 1
fi
say "PASSED (${pos_cases} positive controls incl. real repo, ${neg_cases} negative surfaces, C1–C4 markers)"
