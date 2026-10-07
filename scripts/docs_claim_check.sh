#!/usr/bin/env bash
# docs_claim_check.sh — fail-closed guard that docs match checked-in CI/toolchain truth.
#
# Checks (grep/sed only, no builds):
#   A. Every :app: Gradle task + scripts/*.sh referenced by pr-check.yml run:
#      lines is documented in docs/TESTING.md (and vice versa: no :app: task
#      documented in TESTING.md that CI does not run).
#   B. docs/ENV.md version table matches gradle/libs.versions.toml,
#      app/build.gradle.kts, gradle-wrapper.properties, and the workflow JDK.
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

if [ "$fail" -ne 0 ]; then
  echo "docs_claim_check: FAILED"
  exit 1
fi
echo "docs_claim_check: PASSED"
