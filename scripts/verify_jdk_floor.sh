#!/usr/bin/env bash
# verify_jdk_floor.sh — fail-closed verification of JDK security floor for CVE-2026-91403.
#
# CVE-2026-91403 (eMudhra intermediate CA compromise) requires JDK >= 25.0.5 in
# the October 2026 patch cycle:
# https://www.oracle.com/security-alerts/alert-cve-2026-91403.html
#
# Options:
#   --floor <version>           Minimum required version tuple (default: 25.0.5)
#   --version-string <string>   Override raw version string (for testing without installed JDK)
#   -h, --help                  Show this help message
#
# Exits 0 if installed/provided version >= floor, 1 otherwise (fail closed).
set -euo pipefail

DEFAULT_FLOOR="25.0.5"
FLOOR="$DEFAULT_FLOOR"
VERSION_STRING=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --floor)
      if [[ $# -lt 2 ]]; then
        echo "verify_jdk_floor: ERROR: --floor requires a version argument" >&2
        exit 1
      fi
      FLOOR="$2"
      shift 2
      ;;
    --floor=*)
      FLOOR="${1#*=}"
      shift
      ;;
    --version-string)
      if [[ $# -lt 2 ]]; then
        echo "verify_jdk_floor: ERROR: --version-string requires an argument" >&2
        exit 1
      fi
      VERSION_STRING="$2"
      shift 2
      ;;
    --version-string=*)
      VERSION_STRING="${1#*=}"
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--floor <version>] [--version-string <string>]"
      exit 0
      ;;
    *)
      echo "verify_jdk_floor: ERROR: unknown argument '$1'" >&2
      exit 1
      ;;
  esac
done

if ! [[ "$FLOOR" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  echo "verify_jdk_floor: ERROR: invalid floor version '$FLOOR' (fail closed)" >&2
  exit 1
fi

if [[ -n "$VERSION_STRING" ]]; then
  RAW_VERSION="$VERSION_STRING"
  echo "verify_jdk_floor: checking provided JDK version string:"
  echo "$RAW_VERSION"
else
  if ! command -v java >/dev/null 2>&1; then
    echo "verify_jdk_floor: ERROR: 'java' command not found on PATH (fail closed)" >&2
    exit 1
  fi
  RAW_VERSION="$(java -version 2>&1)"
  echo "verify_jdk_floor: checking installed JDK version:"
  echo "$RAW_VERSION"
fi

# Extract version tuple:
# 1) Try version enclosed in quotes, e.g. openjdk version "25.0.5" ...
# 2) Fall back to bare version tuple X.Y(.Z...)
PARSED_VERSION=""
if [[ "$RAW_VERSION" =~ \"([0-9]+(\.[0-9]+)*) ]]; then
  PARSED_VERSION="${BASH_REMATCH[1]}"
elif [[ "$RAW_VERSION" =~ ([0-9]+(\.[0-9]+)+) ]]; then
  PARSED_VERSION="${BASH_REMATCH[1]}"
elif [[ "$RAW_VERSION" =~ ^[[:space:]]*([0-9]+) ]]; then
  PARSED_VERSION="${BASH_REMATCH[1]}"
fi

if [[ -z "$PARSED_VERSION" ]]; then
  echo "verify_jdk_floor: ERROR: unable to parse version tuple from JDK output (fail closed)" >&2
  exit 1
fi

echo "verify_jdk_floor: parsed JDK version '$PARSED_VERSION' (required floor: '$FLOOR')"

# Compare version tuples component by component
IFS='.' read -r -a VER_PARTS <<< "$PARSED_VERSION"
IFS='.' read -r -a FLOOR_PARTS <<< "$FLOOR"

max_len="${#VER_PARTS[@]}"
if [[ "${#FLOOR_PARTS[@]}" -gt "$max_len" ]]; then
  max_len="${#FLOOR_PARTS[@]}"
fi

for ((i = 0; i < max_len; i++)); do
  v="${VER_PARTS[i]:-0}"
  f="${FLOOR_PARTS[i]:-0}"

  if ! [[ "$v" =~ ^[0-9]+$ ]] || ! [[ "$f" =~ ^[0-9]+$ ]]; then
    echo "verify_jdk_floor: ERROR: non-numeric version component in comparison (v='$v', f='$f')" >&2
    exit 1
  fi

  v_num=$((10#$v))
  f_num=$((10#$f))

  if (( v_num > f_num )); then
    echo "verify_jdk_floor: PASSED: $PARSED_VERSION >= $FLOOR"
    exit 0
  elif (( v_num < f_num )); then
    echo "verify_jdk_floor: FAILED: $PARSED_VERSION < $FLOOR (CVE-2026-91403 floor not met)" >&2
    exit 1
  fi
done

# If all compared components are equal
echo "verify_jdk_floor: PASSED: $PARSED_VERSION >= $FLOOR"
exit 0
