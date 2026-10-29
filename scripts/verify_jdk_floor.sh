#!/usr/bin/env bash
# verify_jdk_floor.sh — fail-closed verification of JDK security floor for CVE-2026-91403.
#
# CVE-2026-91403 (eMudhra intermediate CA compromise) requires JDK >= 25.0.5 in
# the October 2026 patch cycle (specifically Temurin 25.0.5+8):
# https://www.oracle.com/security-alerts/alert-cve-2026-91403.html
#
# Options:
#   --floor <version>           Minimum required version tuple (default: 25.0.5)
#   --min-build <build>         Minimum required build number when version == floor (default: 8)
#   --version-string <string>   Override raw version string (for testing without installed JDK)
#   --require-vendor <vendor>   Optional vendor string required in version output (e.g. Temurin)
#   -h, --help                  Show this help message
#
# Exits 0 if installed/provided version >= floor (with build >= min-build when version == floor),
# 1 otherwise (fail closed).
set -euo pipefail

DEFAULT_FLOOR="25.0.5"
DEFAULT_MIN_BUILD="8"

FLOOR="$DEFAULT_FLOOR"
MIN_BUILD="$DEFAULT_MIN_BUILD"
VERSION_STRING=""
VERSION_STRING_SET=0
REQUIRE_VENDOR=""
REQUIRE_VENDOR_SET=0

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
    --min-build)
      if [[ $# -lt 2 ]]; then
        echo "verify_jdk_floor: ERROR: --min-build requires a build number argument" >&2
        exit 1
      fi
      MIN_BUILD="$2"
      shift 2
      ;;
    --min-build=*)
      MIN_BUILD="${1#*=}"
      shift
      ;;
    --version-string)
      if [[ $# -lt 2 ]]; then
        echo "verify_jdk_floor: ERROR: --version-string requires an argument" >&2
        exit 1
      fi
      VERSION_STRING="$2"
      VERSION_STRING_SET=1
      shift 2
      ;;
    --version-string=*)
      VERSION_STRING="${1#*=}"
      VERSION_STRING_SET=1
      shift
      ;;
    --require-vendor)
      if [[ $# -lt 2 ]]; then
        echo "verify_jdk_floor: ERROR: --require-vendor requires a vendor argument" >&2
        exit 1
      fi
      REQUIRE_VENDOR="$2"
      REQUIRE_VENDOR_SET=1
      shift 2
      ;;
    --require-vendor=*)
      REQUIRE_VENDOR="${1#*=}"
      REQUIRE_VENDOR_SET=1
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--floor <version>] [--min-build <build>] [--version-string <string>] [--require-vendor <vendor>]"
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

if ! [[ "$MIN_BUILD" =~ ^[0-9]{1,18}$ ]]; then
  echo "verify_jdk_floor: ERROR: invalid min-build '$MIN_BUILD' (must be a non-negative integer of at most 18 digits, fail closed)" >&2
  exit 1
fi

# Force decimal interpretation: bash treats values with a leading zero (e.g. 08
# or 010) as octal inside arithmetic expansion, which either errors out or
# silently changes the intended value. 10# always parses base 10.
MIN_BUILD_DEC=$((10#$MIN_BUILD))

if [[ "$VERSION_STRING_SET" -eq 1 && -z "${VERSION_STRING//[[:space:]]/}" ]]; then
  echo "verify_jdk_floor: ERROR: --version-string cannot be empty (fail closed)" >&2
  exit 1
fi

if [[ "$REQUIRE_VENDOR_SET" -eq 1 && -z "${REQUIRE_VENDOR//[[:space:]]/}" ]]; then
  echo "verify_jdk_floor: ERROR: --require-vendor cannot be empty (fail closed)" >&2
  exit 1
fi

# Consistency check with $JAVA_HOME if set
if [[ -n "${JAVA_HOME:-}" ]]; then
  if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "verify_jdk_floor: ERROR: \$JAVA_HOME is set to '$JAVA_HOME', but '$JAVA_HOME/bin/java' does not exist or is not executable (fail closed)" >&2
    exit 1
  fi
  PATH_JAVA="$(command -v java 2>/dev/null || true)"
  if [[ -z "$PATH_JAVA" ]]; then
    echo "verify_jdk_floor: ERROR: \$JAVA_HOME is set to '$JAVA_HOME', but 'java' command not found on PATH (fail closed)" >&2
    exit 1
  fi
  JAVA_HOME_REAL="$(realpath "$JAVA_HOME/bin/java" 2>/dev/null || true)"
  PATH_JAVA_REAL="$(realpath "$PATH_JAVA" 2>/dev/null || true)"
  # `-ef` compares device+inode after following symlinks, so it correctly treats
  # a symlinked java on PATH as equivalent to the same binary under JAVA_HOME.
  if ! [[ "$JAVA_HOME/bin/java" -ef "$PATH_JAVA" ]]; then
    echo "verify_jdk_floor: ERROR: \$JAVA_HOME/bin/java ('${JAVA_HOME_REAL:-$JAVA_HOME/bin/java}') does not match 'java' on PATH ('${PATH_JAVA_REAL:-$PATH_JAVA}') (fail closed)" >&2
    exit 1
  fi
  echo "verify_jdk_floor: verified \$JAVA_HOME consistency: ${JAVA_HOME_REAL:-$JAVA_HOME/bin/java}"

  SECURITY_CONF="$JAVA_HOME/conf/security/java.security"
  if [[ -f "$SECURITY_CONF" ]]; then
    echo "verify_jdk_floor: (informational) inspecting distrust configuration in $SECURITY_CONF:"
    DISTRUST_POLICIES="$(awk '/^[[:space:]]*jdk\.security\.caDistrustPolicies/{
      print $0;
      while ($0 ~ /\\$/) {
        if (getline <= 0) break;
        print $0;
      }
    }' "$SECURITY_CONF" || true)"
    if [[ -n "$DISTRUST_POLICIES" ]]; then
      echo "$DISTRUST_POLICIES"
    else
      echo "verify_jdk_floor: notice: no jdk.security.caDistrustPolicies entry found in $SECURITY_CONF"
    fi
  fi
fi

if [[ "$VERSION_STRING_SET" -eq 1 ]]; then
  RAW_VERSION="$VERSION_STRING"
  echo "verify_jdk_floor: checking provided JDK version string:"
  echo "$RAW_VERSION"
else
  if ! command -v java >/dev/null 2>&1; then
    echo "verify_jdk_floor: ERROR: 'java' command not found on PATH (fail closed)" >&2
    exit 1
  fi
  JAVA_VERSION_STATUS=0
  RAW_VERSION="$(java -version 2>&1)" || JAVA_VERSION_STATUS=$?
  if [[ "$JAVA_VERSION_STATUS" -ne 0 ]]; then
    echo "verify_jdk_floor: ERROR: 'java -version' failed with exit status $JAVA_VERSION_STATUS (fail closed)" >&2
    exit 1
  fi
  echo "verify_jdk_floor: checking installed JDK version:"
  echo "$RAW_VERSION"
fi

if [[ -n "$REQUIRE_VENDOR" ]]; then
  if ! grep -qiF -- "$REQUIRE_VENDOR" <<< "$RAW_VERSION"; then
    echo "verify_jdk_floor: ERROR: required vendor '$REQUIRE_VENDOR' not found in JDK version output (fail closed)" >&2
    exit 1
  fi
  echo "verify_jdk_floor: verified vendor '$REQUIRE_VENDOR' present"
fi

# Extract version tuple:
# 1) Try version enclosed in quotes, e.g. openjdk version "25.0.5" ...
# 2) Fall back to bare version tuple X.Y(.Z...)
PARSED_VERSION=""
if [[ "$RAW_VERSION" =~ \"([0-9]+(\.[0-9]+)*) ]]; then
  PARSED_VERSION="${BASH_REMATCH[1]}"
elif [[ "$RAW_VERSION" =~ (^|[^0-9.])([0-9]+(\.[0-9]+)+)([^0-9.]|$) ]]; then
  PARSED_VERSION="${BASH_REMATCH[2]}"
elif [[ "$RAW_VERSION" =~ ^[[:space:]]*([0-9]+)[[:space:]]*$ ]]; then
  PARSED_VERSION="${BASH_REMATCH[1]}"
fi

if [[ -z "$PARSED_VERSION" ]]; then
  echo "verify_jdk_floor: ERROR: unable to parse version tuple from JDK output (fail closed)" >&2
  exit 1
fi

# Extract build number suffix:
# 1) e.g. +8 from 25.0.5+8 or Temurin-25.0.5+8
# 2) e.g. build 8 or (build 8); the trailing boundary rejects version-like
#    values such as "(build 25.0.5)" so that "25" is not mistaken for a build.
# 3) e.g. -b8 (legacy JDK build notation)
PARSED_BUILD=""
if [[ "$RAW_VERSION" =~ \+([0-9]+) ]]; then
  PARSED_BUILD="${BASH_REMATCH[1]}"
elif [[ "$RAW_VERSION" =~ \+ ]]; then
  echo "verify_jdk_floor: ERROR: malformed build suffix in JDK output '$RAW_VERSION' (fail closed)" >&2
  exit 1
elif [[ "$RAW_VERSION" =~ (^|[[:space:]\(])build[[:space:]]+([0-9]+)([^0-9.]|$) ]]; then
  PARSED_BUILD="${BASH_REMATCH[2]}"
elif [[ "$RAW_VERSION" =~ -b([0-9]+) ]]; then
  PARSED_BUILD="${BASH_REMATCH[1]}"
fi

if [[ -n "$PARSED_BUILD" && "${#PARSED_BUILD}" -gt 18 ]]; then
  echo "verify_jdk_floor: ERROR: build suffix too large ('$PARSED_BUILD', max 18 digits) (fail closed)" >&2
  exit 1
fi

echo "verify_jdk_floor: parsed JDK version '$PARSED_VERSION' (build: '${PARSED_BUILD:-none}', required floor: '$FLOOR', min-build: '$MIN_BUILD_DEC')"

# Compare version tuples component by component
IFS='.' read -r -a VER_PARTS <<< "$PARSED_VERSION"
IFS='.' read -r -a FLOOR_PARTS <<< "$FLOOR"

max_len="${#VER_PARTS[@]}"
if [[ "${#FLOOR_PARTS[@]}" -gt "$max_len" ]]; then
  max_len="${#FLOOR_PARTS[@]}"
fi

cmp=0
for ((i = 0; i < max_len; i++)); do
  v="${VER_PARTS[i]:-0}"
  f="${FLOOR_PARTS[i]:-0}"

  if ! [[ "$v" =~ ^[0-9]+$ ]] || ! [[ "$f" =~ ^[0-9]+$ ]]; then
    echo "verify_jdk_floor: ERROR: non-numeric version component in comparison (v='$v', f='$f')" >&2
    exit 1
  fi

  # Bash arithmetic uses signed 64-bit integers; cap component length well below
  # the overflow point so absurd inputs fail closed instead of wrapping.
  if [[ "${#v}" -gt 18 || "${#f}" -gt 18 ]]; then
    echo "verify_jdk_floor: ERROR: version component too large in comparison (v='$v', f='$f', max 18 digits) (fail closed)" >&2
    exit 1
  fi

  v_num=$((10#$v))
  f_num=$((10#$f))

  if (( v_num > f_num )); then
    cmp=1
    break
  elif (( v_num < f_num )); then
    cmp=-1
    break
  fi
done

if (( cmp < 0 )); then
  echo "verify_jdk_floor: FAILED: $PARSED_VERSION < $FLOOR (CVE-2026-91403 floor not met)" >&2
  exit 1
elif (( cmp > 0 )); then
  echo "verify_jdk_floor: PASSED: $PARSED_VERSION > $FLOOR"
  exit 0
else
  # Version matches floor exactly (e.g. 25.0.5 == 25.0.5)
  if (( MIN_BUILD_DEC > 0 )); then
    if [[ -z "$PARSED_BUILD" ]]; then
      echo "verify_jdk_floor: FAILED: $PARSED_VERSION matches floor $FLOOR but no build suffix found (requires build >= $MIN_BUILD_DEC)" >&2
      exit 1
    fi
    if ! [[ "$PARSED_BUILD" =~ ^[0-9]+$ ]]; then
      echo "verify_jdk_floor: ERROR: non-numeric build number '$PARSED_BUILD'" >&2
      exit 1
    fi
    b_num=$((10#$PARSED_BUILD))
    if (( b_num < MIN_BUILD_DEC )); then
      echo "verify_jdk_floor: FAILED: $PARSED_VERSION+$PARSED_BUILD build $b_num < $MIN_BUILD_DEC (CVE-2026-91403 floor not met)" >&2
      exit 1
    fi
    echo "verify_jdk_floor: PASSED: $PARSED_VERSION+$PARSED_BUILD >= $FLOOR+$MIN_BUILD_DEC"
    exit 0
  else
    echo "verify_jdk_floor: PASSED: $PARSED_VERSION >= $FLOOR"
    exit 0
  fi
fi
