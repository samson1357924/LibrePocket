#!/usr/bin/env bash
# docs_claim_check.sh — fail-closed guard that docs match checked-in CI/toolchain truth.
#
# Checks (grep/sed only, no builds):
#   A. Every :app: Gradle task + scripts/*.sh referenced by pr-check.yml run:
#      lines is documented in docs/TESTING.md (and vice versa: no :app: task
#      documented in TESTING.md that CI does not run).
#   B. docs/ENV.md version table matches gradle/libs.versions.toml,
#      app/build.gradle.kts, gradle-wrapper.properties, and the workflow JDK.
#   C. MCP zero-wiring: no production source file outside
#      dev/librepocket/mcp/ (in any production source set — main + flavors
#      play/foss/github + debug, Kotlin and Java, excluding test*/androidTest*)
#      references the mcp package or any Mcp* type (the "not wired" claim in
#      docs is enforced in CI).
#
# Fails closed: any mismatch prints a message and exits non-zero.
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
for wf in .github/workflows/*.yml; do
  [ -f "$wf" ] || continue
  if ! python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$wf" >/dev/null 2>&1; then
    report "$wf is not valid YAML (workflow would be disabled)"
  fi
  if grep -nE '^[[:space:]]*//' "$wf" >/dev/null 2>&1; then
    report "$wf uses // line comments (YAML only accepts #)"
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
# the guard's own existence).
mapfile -t WF_SCRIPTS < <(grep -oE 'scripts/[A-Za-z0-9_]+\.sh' "$WORKFLOW" | sort -u || true)
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

# --- C. MCP zero-wiring (not-wired invariant) ---
# MCP (package dev.librepocket.mcp) is declared+implemented but deliberately
# NOT wired: no production code outside its own package may reference it. It
# has JVM unit tests only (no device run), and every MCP tool stays behind the
# executionReady=false fail-closed double gate. A wiring PR must flip tools
# per the acceptance checklist in docs/CAPABILITY_MATRIX.md AND deliberately
# widen this gate — never widen it as a side effect.
# Matches the package import path plus any Mcp* type name (McpServer, McpScope,
# McpWire, McpRegistry, McpTransport, McpTool, ...); prose that says only
# "MCP" (e.g. KDoc forward mentions) does not match. Scans every .kt/.java file
# under app/src regardless of source root (java/, kotlin/, …) — main + flavors
# play/foss/github + debug — excluding only the test*/androidTest* source sets
# that legitimately reference MCP: flavor code ships in the APK, so a Mcp*
# reference there is production wiring too. Exclusion is by package path
# (…/dev/librepocket/mcp/) with grep -H (filename always shown) so a real
# wiring import outside the package never matches it.
if [ ! -d "$ROOT/app/src" ]; then
  report "missing app/src — MCP zero-wiring scan cannot run (fail closed)"
else
  mapfile -t MCP_REFS < <(
    find "$ROOT/app/src" -type f \( -name '*.kt' -o -name '*.java' \) \
      -not -path '*/src/test*' -not -path '*/src/androidTest*' \
      -exec grep -HnE 'dev\.librepocket\.mcp|Mcp[A-Z]' {} + 2>/dev/null \
      | grep -v '/dev/librepocket/mcp/' || true
  )
  if [ "${#MCP_REFS[@]}" -ne 0 ]; then
    report "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types (MCP must stay unwired until the wiring PR updates this gate):"
    printf '%s\n' "${MCP_REFS[@]}"
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "docs_claim_check: FAILED"
  exit 1
fi
echo "docs_claim_check: PASSED"
