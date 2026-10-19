#!/usr/bin/env bash
# docs_claim_check.sh — fail-closed guard that docs match checked-in CI/toolchain truth.
#
# Checks (grep/sed + python3 with PyYAML for workflow YAML validity; no builds):
#   A. Every :app: Gradle task and every scripts/[subdir/]name.(sh|py) path
#      referenced anywhere in pr-check.yml (run steps and path-filter lists
#      alike) is documented in docs/TESTING.md (and vice versa for tasks:
#      no :app: task documented in TESTING.md that CI does not run).
#   B. docs/ENV.md version table matches gradle/libs.versions.toml,
#      app/build.gradle.kts, gradle-wrapper.properties, and the workflow JDK.
#   C. MCP zero-wiring (four fail-closed surfaces, Stage4 D-01):
#      1. no AndroidManifest*.xml anywhere in the repo registers a
#         dev.librepocket.mcp component — fully-qualified (FQN or Mcp* type)
#         OR package-relative (android:name=".Foo" or any quote style /
#         segment count such as '.mcp.Foo' — the basename is compared against
#         types inside dev/librepocket/mcp/);
#      2. inside dev/librepocket/mcp/ (production source sets only), the ONLY
#         allowed dev.librepocket.* references are the import allowlist
#         (keystore.KeyVault + the 7 tool contract types), the package decl,
#         self-mentions, and KDoc links ([dev.librepocket.…]) — any other
#         import (incl. star imports) or fully-qualified outbound use fails;
#      3. no production source file OUTSIDE dev/librepocket/mcp/ (every module's
#         production source roots — main + flavors + debug, Kotlin and Java,
#         excluding test*/androidTest*) references the mcp package or any Mcp*
#         type;
#      4. discovery itself fails closed (no src roots / no manifests / grep or
#         find errors ⇒ fail, never silent pass).
#
# Fails closed: any mismatch prints a message and exits non-zero.
# Self-test: scripts/test_docs_claim_check.sh (fixture repos per wiring vector
# and per discovery fail-closed path).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/pr-check.yml"
TESTING="$ROOT/docs/TESTING.md"
ENV_DOC="$ROOT/docs/ENV.md"
TOML="$ROOT/gradle/libs.versions.toml"
APP_BUILD="$ROOT/app/build.gradle.kts"
WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.properties"

fail=0
report() { echo "docs_claim_check: $1"; fail=1; }

# --- A0. Every checked-in workflow must be valid YAML and use # comments. ---
# A syntax error silently disables a workflow (GitHub renames it to the file
# path and stops triggering), so this fails closed before that reaches main.
# Glob is $ROOT-anchored (Stage4 D-05): a CWD-relative glob silently skipped
# this whole section whenever the script was invoked from another directory.
for wf in "$ROOT"/.github/workflows/*.yml; do
  [ -f "$wf" ] || continue
  if ! python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$wf" >/dev/null 2>&1; then
    report "${wf#"$ROOT"/} is not valid YAML (workflow would be disabled)"
  fi
  if grep -nE '^[[:space:]]*//' "$wf" >/dev/null 2>&1; then
    report "${wf#"$ROOT"/} uses // line comments (YAML only accepts #)"
  fi
done

for f in "$WORKFLOW" "$TESTING" "$ENV_DOC" "$TOML" "$APP_BUILD" "$WRAPPER"; do
  if [ ! -f "$f" ]; then
    report "missing required file: $f"
  fi
done
if [ "$fail" -ne 0 ]; then exit 1; fi

# --- A. Workflow run: lines vs docs/TESTING.md ---
# Gradle tasks CI runs (from ./gradlew invocations in the workflow).
mapfile -t WF_TASKS < <(grep -oE ':app:[A-Za-z0-9_]+' "$WORKFLOW" | sort -u || true)
if [ "${#WF_TASKS[@]}" -eq 0 ]; then
  report "no :app: tasks found in $WORKFLOW (parser drift?)"
fi
for task in "${WF_TASKS[@]}"; do
  if ! grep -Fq "$task" "$TESTING"; then
    report "TESTING.md missing CI task '$task' from pr-check.yml"
  fi
done

# Helper scripts CI invokes must be documented too (except this guard itself:
# requiring TESTING.md to mention the docs guard would couple docs edits to
# the guard's own existence). Stage4 D-05: .py scripts count as well as .sh;
# P3-2: the regex covers one level of subdirectory (scripts/tests/*.py — the
# workflow references them as literal paths in run: lines and path filters).
# Forward check only — the reverse direction would false-fail local-only
# scripts such as build_release.sh that CI deliberately never runs.
mapfile -t WF_SCRIPTS < <(grep -oE 'scripts/([A-Za-z0-9_]+/)?[A-Za-z0-9_]+\.(sh|py)' "$WORKFLOW" | sort -u || true)
for script in "${WF_SCRIPTS[@]}"; do
  if [ "$script" = "scripts/docs_claim_check.sh" ]; then
    continue
  fi
  if ! grep -Fq "$script" "$TESTING"; then
    report "TESTING.md missing CI script '$script' from pr-check.yml"
  fi
done

# Reverse: every :app: task documented in TESTING.md must be a task CI runs.
mapfile -t DOC_TASKS < <(grep -oE ':app:[A-Za-z0-9_]+' "$TESTING" | sort -u || true)
for task in "${DOC_TASKS[@]}"; do
  if ! grep -Fq "$task" "$WORKFLOW"; then
    report "TESTING.md documents '$task' which pr-check.yml does not run"
  fi
done

# --- B. docs/ENV.md version table vs sources of truth ---
toml_value() {
  # $1 = key in [versions], e.g. agp -> 9.4.1 (fails closed when absent)
  local key="$1" val
  val="$(grep -E "^${key} = " "$TOML" | sed -E 's/^[^"]*"([^"]+)".*/\1/' || true)"
  if [ -z "$val" ]; then
    report "cannot parse '$key' from gradle/libs.versions.toml"
  fi
  printf '%s' "$val"
}

AGP="$(toml_value agp)"
KOTLIN="$(toml_value kotlin)"
COMPILE_SDK="$(toml_value compileSdk)"
TARGET_SDK="$(toml_value targetSdk)"
MIN_SDK="$(toml_value minSdk)"

# Catalog values must appear in the ENV.md table.
for entry in "Android Gradle Plugin|$AGP" "Kotlin|$KOTLIN" \
  "compileSdk|$COMPILE_SDK" "targetSdk|$TARGET_SDK" "minSdk|$MIN_SDK"; do
  label="${entry%%|*}"
  value="${entry#*|}"
  if [ -n "$value" ] && ! grep -Fq "$value" "$ENV_DOC"; then
    report "ENV.md missing $label value '$value' from gradle/libs.versions.toml"
  fi
done

# app/build.gradle.kts SDK integers must agree with the catalog.
for entry in "compileSdk|$COMPILE_SDK" "targetSdk|$TARGET_SDK" "minSdk|$MIN_SDK"; do
  field="${entry%%|*}"
  value="${entry#*|}"
  if [ -n "$value" ] && ! grep -Eq "${field} *= *${value}" "$APP_BUILD"; then
    report "app/build.gradle.kts $field does not match catalog value '$value'"
  fi
  if [ -n "$value" ] && ! grep -Fq "$value" "$ENV_DOC"; then
    report "ENV.md missing app $field value '$value'"
  fi
done

# Gradle wrapper version must appear in ENV.md.
WRAPPER_VER="$(grep -E '^distributionUrl=' "$WRAPPER" | sed -E 's/.*gradle-([0-9.]+)-bin\.zip.*/\1/' || true)"
if [ -z "$WRAPPER_VER" ]; then
  report "cannot parse Gradle version from gradle-wrapper.properties"
elif ! grep -Fq "$WRAPPER_VER" "$ENV_DOC"; then
  report "ENV.md missing Gradle wrapper value '$WRAPPER_VER'"
fi

# Workflow JDK version must match the ENV.md baseline claim.
JDK_VER="$(grep -E 'java-version:' "$WORKFLOW" | head -n 1 | grep -oE '[0-9]+' | head -n 1 || true)"
if [ -z "$JDK_VER" ]; then
  report "cannot parse java-version from pr-check.yml"
elif ! grep -Eq "JDK ${JDK_VER}([^0-9]|$)" "$ENV_DOC"; then
  report "ENV.md missing JDK $JDK_VER baseline from pr-check.yml"
fi

# --- C. MCP zero-wiring (not-wired invariant) — four fail-closed surfaces ---
# MCP (package dev.librepocket.mcp) is declared+implemented but deliberately
# NOT wired: no production code outside its own package may reference it, no
# manifest may register it, and the package itself may only depend outward on
# the allowlisted contract types. It has JVM unit tests only (no device run),
# and every MCP tool stays behind the executionReady=false fail-closed double
# gate. A wiring PR must flip tools per the acceptance checklist in
# docs/CAPABILITY_MATRIX.md AND deliberately widen this gate — never widen it
# as a side effect (Stage4 D-01: the old single grep let a manifest component,
# an in-package outbound import, or a brand-new module slip through).

# --- C1. Discovery: every production source root of every module (fail-closed
# on find errors or an empty result — parser drift must not silently pass). ---
if ! SRC_LIST="$(find "$ROOT" -type d -name src \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' \
  -not -path '*/node_modules/*' 2>&1)"; then
  report "find over source roots failed (fail closed): $SRC_LIST"
  SRC_LIST=""
fi
SRC_DIRS=()
while IFS= read -r d; do
  [ -n "$d" ] && SRC_DIRS+=("$d")
done <<< "$SRC_LIST"
if [ "${#SRC_DIRS[@]}" -eq 0 ]; then
  report "no */src source roots found under repo root (parser drift?) — MCP scan cannot run (fail closed)"
fi

# Production source files of every module: Kotlin/Java, excluding only the
# test*/androidTest* source sets that legitimately reference MCP (flavor code
# ships in the APK, so a Mcp* reference there is production wiring too).
PROD_FILES=()
if [ "${#SRC_DIRS[@]}" -gt 0 ]; then
  if ! PROD_LIST="$(find "${SRC_DIRS[@]}" -type f \( -name '*.kt' -o -name '*.java' \) \
    -not -path '*/src/test*' -not -path '*/src/androidTest*' 2>&1)"; then
    report "find over production sources failed (fail closed): $PROD_LIST"
    PROD_LIST=""
  fi
  while IFS= read -r f; do
    [ -n "$f" ] && PROD_FILES+=("$f")
  done <<< "$PROD_LIST"
fi
if [ "${#PROD_FILES[@]}" -eq 0 ]; then
  report "no production Kotlin/Java sources found (parser drift?) — MCP scan cannot run (fail closed)"
fi

# --- C2. Surface: AndroidManifest registration (any module, any flavor). ---
# N-B1 accepted cost: the bare `Mcp[A-Z]` substring is deliberately wide — a
# third-party string containing e.g. `McpCompat` also turns this red
# (fail-closed direction, not tightened: any token-boundary rule risks
# dropping a real `dev.librepocket.mcp.McpServer` reference mid-string).
if ! MF_LIST="$(find "$ROOT" -type f -name 'AndroidManifest*.xml' \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' 2>&1)"; then
  report "find over manifests failed (fail closed): $MF_LIST"
  MF_LIST=""
fi
MANIFESTS=()
while IFS= read -r f; do
  [ -n "$f" ] && MANIFESTS+=("$f")
done <<< "$MF_LIST"
if [ "${#MANIFESTS[@]}" -eq 0 ]; then
  report "no AndroidManifest*.xml found (parser drift?) — MCP manifest scan cannot run (fail closed)"
else
  set +e
  MF_HITS="$(grep -HnE 'dev\.librepocket\.mcp|Mcp[A-Z]' "${MANIFESTS[@]}" 2>&1)"
  MF_RC=$?
  set -e
  if [ "$MF_RC" -ge 2 ]; then
    report "manifest grep failed (fail closed, rc=$MF_RC): $MF_HITS"
  elif [ "$MF_RC" -eq 0 ]; then
    report "Android manifest registers MCP (MCP must stay unwired until the wiring PR updates this gate):"
    printf '%s\n' "$MF_HITS"
  fi
  # P3-1: package-relative component names — `android:name=".Transport"`
  # resolves to the manifest's package namespace, which the FQN/Mcp[A-Z]
  # patterns above cannot see. Fail when such a relative basename exists as a
  # production .kt/.java inside dev/librepocket/mcp/ (any module). Only
  # AndroidManifest*.xml files are scanned (same scope as the rest of C2);
  # manifests without the attribute or without a matching class never fail.
  # N9 hardening: accept both quote styles (XML allows `'.Transport'`) and
  # multi-segment tails (`.mcp.Transport` — the last segment is the basename
  # compared below). Names whose basename has no production file inside
  # dev/librepocket/mcp/ (e.g. `.MainActivity`) never fail.
  set +e
  REL_ATTRS="$(grep -hoE "android:name=[\"']\.[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*[\"']" "${MANIFESTS[@]}" 2>&1)"
  REL_RC=$?
  set -e
  if [ "$REL_RC" -ge 2 ]; then
    report "manifest relative-name grep failed (fail closed, rc=$REL_RC): $REL_ATTRS"
  elif [ "$REL_RC" -eq 0 ]; then
    while IFS= read -r comp; do
      [ -n "$comp" ] || continue
      # Strip the `android:name=".'` / `android:name='.'` wrapper, then take
      # the last segment (`.mcp.Transport` → `Transport`).
      rel="$(printf '%s' "$comp" | sed -E "s/^android:name=[\"']\.//; s/[\"']$//")"
      base="${rel##*.}"
      [ -n "$base" ] || continue
      if [ "${#SRC_DIRS[@]}" -gt 0 ]; then
        if ! REL_MATCH="$(find "${SRC_DIRS[@]}" -path '*/dev/librepocket/mcp/*' \
          -type f \( -name "${base}.kt" -o -name "${base}.java" \) \
          -not -path '*/src/test*' -not -path '*/src/androidTest*' 2>&1)"; then
          report "find over mcp package sources failed (fail closed): $REL_MATCH"
        elif [ -n "$REL_MATCH" ]; then
          report "Android manifest registers MCP (relative component .$base)"
        fi
      fi
    done < <(printf '%s\n' "$REL_ATTRS" | sort -u)
  fi
fi

# --- C3. Surface: in-package outbound dependencies (allowlist, fail-closed).
# Only these exact imports may cross the package boundary (import allowlist =
# KeyVault + the 7 tool contract types; star imports and anything else fail);
# outside imports, the package decl, self-mentions (dev.librepocket.mcp) and
# KDoc links ([dev.librepocket.…] or the HTML form <dev.librepocket.…>) must
# carry every remaining occurrence. N-B2: import matching tolerates leading
# indentation and trailing whitespace/comments (line is left-trimmed before
# classification; the allowlist anchors accept trailing [[:space:]] and //
# comments — the comment tail is `//.*`, NOT `//[^\n]*`: inside a POSIX
# bracket expression `\n` is the two literal chars backslash+n, not a
# newline, so `[^\n]` wrongly rejected any trailing comment containing the
# letter n or a backslash; lines are read one at a time so `.` cannot cross
# lines). N-B3 known limitation: a multi-line FQN split across lines
# (e.g. "dev.librepocket\n.router.X") is not caught by this per-line scan —
# the Kotlin formatter does not wrap at package-segment boundaries, so this is
# accepted residual risk rather than a silent gap in the single-line rules.
MCP_ALLOWLIST='^[[:space:]]*import dev\.librepocket\.(keystore\.KeyVault|tool\.(CapabilityLevel|DenyReason|Projection|ProjectionContext|SideEffect|ToolAnnotations|ToolDef))([[:space:]]*//.*)?[[:space:]]*$'
if [ "${#SRC_DIRS[@]}" -gt 0 ]; then
  if ! MCP_PKG_LIST="$(find "${SRC_DIRS[@]}" -type f \( -name '*.kt' -o -name '*.java' \) \
    -path '*/dev/librepocket/mcp/*' -not -path '*/src/test*' -not -path '*/src/androidTest*' 2>&1)"; then
    report "find over mcp package sources failed (fail closed): $MCP_PKG_LIST"
    MCP_PKG_LIST=""
  fi
  MCP_PKG_FILES=()
  while IFS= read -r f; do
    [ -n "$f" ] && MCP_PKG_FILES+=("$f")
  done <<< "$MCP_PKG_LIST"
  if [ "${#MCP_PKG_FILES[@]}" -eq 0 ]; then
    report "no production sources inside dev/librepocket/mcp/ found (parser drift?) — outbound scan cannot run (fail closed)"
  fi
  for f in ${MCP_PKG_FILES[@]+"${MCP_PKG_FILES[@]}"}; do
    if [ ! -r "$f" ]; then
      report "unreadable mcp source (fail closed): $f"
      continue
    fi
    lineno=0
    while IFS= read -r line || [ -n "$line" ]; do
      lineno=$((lineno + 1))
      # N-B2: classify on the left-trimmed form so indented package/import
      # lines behave like column-0 ones (the report still quotes the original).
      trimmed="${line#"${line%%[![:space:]]*}"}"
      case "$trimmed" in
        "package dev.librepocket.mcp"*) continue ;;
      esac
      case "$trimmed" in
        import\ *)
          case "$trimmed" in
            "import dev.librepocket."*)
              if ! printf '%s\n' "$trimmed" | grep -qE "$MCP_ALLOWLIST"; then
                report "outbound import not on allowlist (fail closed): ${f#"$ROOT"/}:$lineno: $line"
                fail=1
              fi
              ;;
          esac
          continue
          ;;
      esac
      # Strip allowed forms, then any leftover dev.librepocket. is a violation:
      # KDoc links start with "[dev.librepocket." or "<dev.librepocket." and
      # self-mentions are "dev.librepocket.mcp" (backtick prose or package
      # path). A remaining occurrence is an FQN call/type reference —
      # production wiring.
      stripped="${line//\[dev.librepocket.}"
      stripped="${stripped//<dev.librepocket.}"
      stripped="${stripped//dev.librepocket.mcp}"
      case "$stripped" in
        *dev.librepocket.*)
          report "outbound reference from inside dev/librepocket/mcp/ (fail closed): ${f#"$ROOT"/}:$lineno: $line"
          fail=1
          ;;
      esac
    done < "$f"
  done
fi

# --- C4. Surface: any production source OUTSIDE the package (whole module
# tree, every module — not just app/). Grep errors (rc>=2) fail closed; the
# package's own directory is filtered out of matches (grep -H shows filenames,
# so a real wiring import outside the package never matches it).
# N-B1 accepted cost: the bare `Mcp[A-Z]` substring is deliberately wide — a
# third-party string containing e.g. `McpCompat` also turns this red
# (fail-closed direction, not tightened: a token-boundary rule cannot
# distinguish com.x.McpCompat from a real McpServer reference without
# full parsing, and a missed wiring reference is the worse failure mode). ---
if [ "${#PROD_FILES[@]}" -gt 0 ]; then
  set +e
  MCP_OUT="$(grep -HnE 'dev\.librepocket\.mcp|Mcp[A-Z]' "${PROD_FILES[@]}" 2>&1)"
  MCP_RC=$?
  set -e
  if [ "$MCP_RC" -ge 2 ]; then
    report "grep over production sources failed (fail closed, rc=$MCP_RC): $MCP_OUT"
  elif [ "$MCP_RC" -eq 0 ]; then
    MCP_REFS=()
    while IFS= read -r l; do
      [ -z "$l" ] && continue
      case "$l" in
        */dev/librepocket/mcp/*) continue ;;
      esac
      MCP_REFS+=("$l")
    done <<< "$MCP_OUT"
    if [ "${#MCP_REFS[@]}" -ne 0 ]; then
      report "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types (MCP must stay unwired until the wiring PR updates this gate):"
      printf '%s\n' "${MCP_REFS[@]}"
      fail=1
    fi
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "docs_claim_check: FAILED"
  exit 1
fi
echo "docs_claim_check: PASSED"
