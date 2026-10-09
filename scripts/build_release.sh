#!/bin/sh
# Local APK-only release builder for play / foss / github.
#
# Passwords are NEVER stored in this repo. Two supported sources (per flavor,
# independently — already-exported env vars always win):
#   1. Bitwarden via `bw` CLI (local flow):
#        export BW_SESSION=$(bw unlock --raw)   # once per shell
#      The script reads each password with `bw get password <item>` and keeps
#      it in a shell variable only. Nothing is written to disk.
#      Item names (override when yours differ):
#        BW_ITEM_PLAY / BW_ITEM_FOSS / BW_ITEM_GITHUB
#        (defaults: librepocket-play / librepocket-foss / librepocket-github)
#   2. Pre-exported env vars (CI flow, e.g. GitHub Actions Secrets):
#        PLAY_KEYSTORE_PASSWORD / FOSS_KEYSTORE_PASSWORD / DIRECT_KEYSTORE_PASSWORD
#      The github flavor uses DIRECT_ because GitHub reserves the GITHUB_ prefix.
#
# Keystores stay local: keystores/release-<flavor>.p12 (gitignored).
# Override path per flavor with <FLAVOR>_KEYSTORE_FILE if needed.
#
# Usage:
#   sh scripts/build_release.sh --apk [play] [foss] [github] \
#     --expected-package play=PACKAGE --expected-version-code play=CODE \
#     --expected-version-name play=NAME --expected-cert-sha256 play=HEX64 \
#     --expected-package foss=PACKAGE --expected-version-code foss=CODE \
#     --expected-version-name foss=NAME --expected-cert-sha256 foss=HEX64 \
#     --expected-package github=PACKAGE --expected-version-code github=CODE \
#     --expected-version-name github=NAME --expected-cert-sha256 github=HEX64 \
#     --apksigner /sdk/build-tools/VERSION/apksigner \
#     --aapt /sdk/build-tools/VERSION/aapt --output-parent /existing/output-dir
#
# Artifact identity must be supplied by the trusted caller; it is never inferred
# from the candidate APK. Public release/publisher workflows remain disabled.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

log() { printf '%s\n' "$*"; }
die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

WANT_APK=0
FLAVORS=""
APKSIGNER=""
AAPT=""
OUTPUT_PARENT=""
PACKAGE_PLAY=""; VERSION_CODE_PLAY=""; VERSION_NAME_PLAY=""; CERT_PLAY=""
PACKAGE_FOSS=""; VERSION_CODE_FOSS=""; VERSION_NAME_FOSS=""; CERT_FOSS=""
PACKAGE_GITHUB=""; VERSION_CODE_GITHUB=""; VERSION_NAME_GITHUB=""; CERT_GITHUB=""

set_expected_identity() {
    ID_FIELD=$1
    ID_MAPPING=$2
    case "$ID_MAPPING" in
        *=*) ID_FLAVOR=${ID_MAPPING%%=*}; ID_VALUE=${ID_MAPPING#*=} ;;
        *) die "expected $ID_FIELD must use FLAVOR=VALUE syntax" ;;
    esac
    case "$ID_FLAVOR" in
        play|foss|github) ;;
        *) die "unknown flavor in expected identity: $ID_FLAVOR" ;;
    esac
    case "$ID_FIELD:$ID_FLAVOR" in
        package:play) [ -z "$PACKAGE_PLAY" ] || die "duplicate expected package for play"; PACKAGE_PLAY=$ID_VALUE ;;
        version-code:play) [ -z "$VERSION_CODE_PLAY" ] || die "duplicate expected version code for play"; VERSION_CODE_PLAY=$ID_VALUE ;;
        version-name:play) [ -z "$VERSION_NAME_PLAY" ] || die "duplicate expected version name for play"; VERSION_NAME_PLAY=$ID_VALUE ;;
        cert:play) [ -z "$CERT_PLAY" ] || die "duplicate expected certificate for play"; CERT_PLAY=$ID_VALUE ;;
        package:foss) [ -z "$PACKAGE_FOSS" ] || die "duplicate expected package for foss"; PACKAGE_FOSS=$ID_VALUE ;;
        version-code:foss) [ -z "$VERSION_CODE_FOSS" ] || die "duplicate expected version code for foss"; VERSION_CODE_FOSS=$ID_VALUE ;;
        version-name:foss) [ -z "$VERSION_NAME_FOSS" ] || die "duplicate expected version name for foss"; VERSION_NAME_FOSS=$ID_VALUE ;;
        cert:foss) [ -z "$CERT_FOSS" ] || die "duplicate expected certificate for foss"; CERT_FOSS=$ID_VALUE ;;
        package:github) [ -z "$PACKAGE_GITHUB" ] || die "duplicate expected package for github"; PACKAGE_GITHUB=$ID_VALUE ;;
        version-code:github) [ -z "$VERSION_CODE_GITHUB" ] || die "duplicate expected version code for github"; VERSION_CODE_GITHUB=$ID_VALUE ;;
        version-name:github) [ -z "$VERSION_NAME_GITHUB" ] || die "duplicate expected version name for github"; VERSION_NAME_GITHUB=$ID_VALUE ;;
        cert:github) [ -z "$CERT_GITHUB" ] || die "duplicate expected certificate for github"; CERT_GITHUB=$ID_VALUE ;;
        *) die "unknown expected identity field: $ID_FIELD" ;;
    esac
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --apk) WANT_APK=1; shift ;;
        --aab) die "AAB is unsupported; only final APK verification is implemented" ;;
        --expected-package)
            [ "$#" -ge 2 ] || die "--expected-package requires FLAVOR=PACKAGE"
            set_expected_identity package "$2"; shift 2 ;;
        --expected-version-code)
            [ "$#" -ge 2 ] || die "--expected-version-code requires FLAVOR=INTEGER"
            set_expected_identity version-code "$2"; shift 2 ;;
        --expected-version-name)
            [ "$#" -ge 2 ] || die "--expected-version-name requires FLAVOR=NAME"
            set_expected_identity version-name "$2"; shift 2 ;;
        --expected-cert-sha256)
            [ "$#" -ge 2 ] || die "--expected-cert-sha256 requires FLAVOR=HEX64"
            set_expected_identity cert "$2"; shift 2 ;;
        --apksigner)
            [ "$#" -ge 2 ] || die "--apksigner requires an executable path"
            [ -z "$APKSIGNER" ] || die "duplicate --apksigner"
            APKSIGNER=$2; shift 2 ;;
        --aapt)
            [ "$#" -ge 2 ] || die "--aapt requires an executable path"
            [ -z "$AAPT" ] || die "duplicate --aapt"
            AAPT=$2; shift 2 ;;
        --output-parent)
            [ "$#" -ge 2 ] || die "--output-parent requires an existing directory"
            [ -z "$OUTPUT_PARENT" ] || die "duplicate --output-parent"
            OUTPUT_PARENT=$2; shift 2 ;;
        play|foss|github)
            case " $FLAVORS " in
                *" $1 "*) ;;
                *) FLAVORS="$FLAVORS $1" ;;
            esac
            shift ;;
        -h|--help)
            sed -n '2,/^set -eu/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) die "unknown argument: $1 (see --help)" ;;
    esac
done

if [ -z "$FLAVORS" ]; then
    FLAVORS="play foss github"
fi

# Reject unsupported mode and validate every trusted identity/tool/output input
# before reading password variables, invoking Bitwarden, or starting Gradle.
[ "$WANT_APK" -eq 1 ] || die "APK mode is required; AAB is unsupported"
[ -n "$APKSIGNER" ] || die "--apksigner is required"
[ -n "$AAPT" ] || die "--aapt is required"
[ -n "$OUTPUT_PARENT" ] || die "--output-parent is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
command -v mktemp >/dev/null 2>&1 || die "mktemp is required"
command -v env >/dev/null 2>&1 || die "env is required"
[ -f "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || die "apksigner is unavailable or not executable: $APKSIGNER"
[ -f "$AAPT" ] && [ -x "$AAPT" ] || die "aapt is unavailable or not executable: $AAPT"
[ -r "$SCRIPT_DIR/verify_release_apk.py" ] || die "release APK verifier is missing"
[ -r "$SCRIPT_DIR/release_artifact_stage.py" ] || die "release artifact stage helper is missing"
[ -d "$OUTPUT_PARENT" ] && [ ! -L "$OUTPUT_PARENT" ] && [ -w "$OUTPUT_PARENT" ] \
    || die "output parent must be an existing writable non-symlink directory"
OUTPUT_PARENT=$(CDPATH= cd -- "$OUTPUT_PARENT" && pwd -P) \
    || die "cannot resolve output parent"

validate_identity() {
    python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import re
import sys

flavor, package, version_code, version_name, certificate = sys.argv[1:]
if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
    raise SystemExit(f"invalid expected package for {flavor}")
if not re.fullmatch(r"[1-9][0-9]*", version_code) or int(version_code) > 2_147_483_647:
    raise SystemExit(f"invalid expected versionCode for {flavor}")
if not version_name or len(version_name) > 256 or any(ord(char) < 0x20 for char in version_name):
    raise SystemExit(f"invalid expected versionName for {flavor}")
if not re.fullmatch(r"[0-9A-Fa-f]{64}", certificate):
    raise SystemExit(f"invalid expected certificate SHA-256 for {flavor}")
PY
}

for f in $FLAVORS; do
    case "$f" in
        play)
            [ -n "$PACKAGE_PLAY" ] && [ -n "$VERSION_CODE_PLAY" ] && [ -n "$VERSION_NAME_PLAY" ] && [ -n "$CERT_PLAY" ] \
                || die "all four explicit expected identity values are required for selected flavor play"
            validate_identity play "$PACKAGE_PLAY" "$VERSION_CODE_PLAY" "$VERSION_NAME_PLAY" "$CERT_PLAY" \
                || die "invalid expected identity for play" ;;
        foss)
            [ -n "$PACKAGE_FOSS" ] && [ -n "$VERSION_CODE_FOSS" ] && [ -n "$VERSION_NAME_FOSS" ] && [ -n "$CERT_FOSS" ] \
                || die "all four explicit expected identity values are required for selected flavor foss"
            validate_identity foss "$PACKAGE_FOSS" "$VERSION_CODE_FOSS" "$VERSION_NAME_FOSS" "$CERT_FOSS" \
                || die "invalid expected identity for foss" ;;
        github)
            [ -n "$PACKAGE_GITHUB" ] && [ -n "$VERSION_CODE_GITHUB" ] && [ -n "$VERSION_NAME_GITHUB" ] && [ -n "$CERT_GITHUB" ] \
                || die "all four explicit expected identity values are required for selected flavor github"
            validate_identity github "$PACKAGE_GITHUB" "$VERSION_CODE_GITHUB" "$VERSION_NAME_GITHUB" "$CERT_GITHUB" \
                || die "invalid expected identity for github" ;;
    esac
done

for f in play foss github; do
    case " $FLAVORS " in
        *" $f "*) ;;
        *)
            case "$f" in
                play) [ -z "$PACKAGE_PLAY$VERSION_CODE_PLAY$VERSION_NAME_PLAY$CERT_PLAY" ] || die "identity supplied for unselected flavor play" ;;
                foss) [ -z "$PACKAGE_FOSS$VERSION_CODE_FOSS$VERSION_NAME_FOSS$CERT_FOSS" ] || die "identity supplied for unselected flavor foss" ;;
                github) [ -z "$PACKAGE_GITHUB$VERSION_CODE_GITHUB$VERSION_NAME_GITHUB$CERT_GITHUB" ] || die "identity supplied for unselected flavor github" ;;
            esac ;;
    esac
done

# --- resolve passwords (env wins, else Bitwarden) ------------------------------
need_bw=0
for f in $FLAVORS; do
    case $f in
        play) prefix=PLAY ;;
        foss) prefix=FOSS ;;
        github) prefix=DIRECT ;;
    esac
    eval "pw=\${${prefix}_KEYSTORE_PASSWORD:-}"
    if [ -z "$pw" ]; then
        need_bw=1
    fi
done

if [ "$need_bw" -eq 1 ]; then
    command -v bw >/dev/null 2>&1 || die "bw CLI not found and some passwords are missing from env"
    [ -n "${BW_SESSION:-}" ] || die "BW_SESSION is not set (run: export BW_SESSION=\$(bw unlock --raw))"
    bw status --session "$BW_SESSION" 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"unlocked"' \
        || die "vault is not unlocked for this BW_SESSION"
    for f in $FLAVORS; do
        case $f in
            play) prefix=PLAY; item=${BW_ITEM_PLAY:-librepocket-play} ;;
            foss) prefix=FOSS; item=${BW_ITEM_FOSS:-librepocket-foss} ;;
            github) prefix=DIRECT; item=${BW_ITEM_GITHUB:-librepocket-github} ;;
        esac
        eval "pw=\${${prefix}_KEYSTORE_PASSWORD:-}"
        if [ -z "$pw" ]; then
            pw=$(bw get password "$item" --session "$BW_SESSION" 2>/dev/null) \
                || die "cannot read Bitwarden item '$item' (override with BW_ITEM_${prefix})"
            [ -n "$pw" ] || die "Bitwarden item '$item' has an empty password"
            export "${prefix}_KEYSTORE_PASSWORD=$pw"
        fi
    done
    pw=""
fi

# --- build ---------------------------------------------------------------------
cd "$ROOT"
TASKS=""
for f in $FLAVORS; do
    case $f in
        play) variant=Play ;;
        foss) variant=Foss ;;
        github) variant=Github ;;
    esac
    TASKS="$TASKS :app:assemble${variant}Release"
done
# -PreleaseSigning=true activates the per-flavor release signingConfigs in
# app/build.gradle.kts. Without it AGP would silently debug-sign the release
# artifact — the flag makes signing explicit, never accidental.
# shellcheck disable=SC2086: TASKS is an intentional word-split task list.
./gradlew -PreleaseSigning=true $TASKS --console=plain

# --- final-byte verification, policy, and all-or-nothing output -----------------
RESULT=$(env -i \
    PATH="$PATH" HOME="${HOME:-}" ANDROID_HOME="${ANDROID_HOME:-}" \
    ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-}" JAVA_HOME="${JAVA_HOME:-}" \
    TMPDIR="${TMPDIR:-}" LC_ALL=C \
    python3 "$SCRIPT_DIR/release_artifact_stage.py" \
    --flavors "$FLAVORS" \
    --candidate-root "$ROOT/app/build/outputs/apk" \
    --output-parent "$OUTPUT_PARENT" \
    --apksigner "$APKSIGNER" \
    --aapt "$AAPT" \
    --play-package "$PACKAGE_PLAY" --play-version-code "$VERSION_CODE_PLAY" \
    --play-version-name "$VERSION_NAME_PLAY" --play-cert-sha256 "$CERT_PLAY" \
    --foss-package "$PACKAGE_FOSS" --foss-version-code "$VERSION_CODE_FOSS" \
    --foss-version-name "$VERSION_NAME_FOSS" --foss-cert-sha256 "$CERT_FOSS" \
    --github-package "$PACKAGE_GITHUB" --github-version-code "$VERSION_CODE_GITHUB" \
    --github-version-name "$VERSION_NAME_GITHUB" --github-cert-sha256 "$CERT_GITHUB") \
    || die "final APK identity/policy transaction failed; no distribution is ready"
log "$RESULT"
