#!/bin/sh
# Release builder for the three flavors (play / foss / github).
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
#      (The github flavor uses DIRECT_ because GitHub reserves the GITHUB_
#      secret prefix.)
#
# Keystores stay local: keystores/release-<flavor>.p12 (gitignored).
# Override path per flavor with <FLAVOR>_KEYSTORE_FILE if needed.
#
# Usage:
#   sh scripts/build_release.sh [--apk] [play] [foss] [github]
# Examples:
#   sh scripts/build_release.sh                 # AABs for all three flavors
#   sh scripts/build_release.sh --apk github    # APK for direct download
#   sh scripts/build_release.sh play foss       # AABs for two flavors
#
# After the build, the play artifact is verified with play_policy_check.sh.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

log() { printf '%s\n' "$*"; }
die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

WANT_APK=0
FLAVORS=""
for arg in "$@"; do
    case "$arg" in
        --apk) WANT_APK=1 ;;
        play|foss|github)
            case " $FLAVORS " in
                *" $arg "*) ;;
                *) FLAVORS="$FLAVORS $arg" ;;
            esac
            ;;
        -h|--help)
            sed -n '2,/^set -eu/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) die "unknown argument: $arg (see --help)" ;;
    esac
done
if [ -z "$FLAVORS" ]; then
    FLAVORS="play foss github"
fi

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
            # Shell variable `pw` is cleared below; nothing touches disk.
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
    if [ "$WANT_APK" -eq 1 ]; then
        TASKS="$TASKS :app:assemble${variant}Release"
    else
        TASKS="$TASKS :app:bundle${variant}Release"
    fi
done
# -PreleaseSigning=true activates the per-flavor release signingConfigs in
# app/build.gradle.kts. Without it AGP would silently debug-sign the release
# artifact — the flag makes signing explicit, never accidental.
# shellcheck disable=SC2086: TASKS is an intentional word-split task list.
./gradlew -PreleaseSigning=true $TASKS --console=plain

# --- report + gate ---------------------------------------------------------------
for f in $FLAVORS; do
    if [ "$WANT_APK" -eq 1 ]; then
        art=$(ls "app/build/outputs/apk/$f/release/"*.apk 2>/dev/null | head -n 1 || true)
    else
        art=$(ls "app/build/outputs/bundle/${f}Release/"*.aab 2>/dev/null | head -n 1 || true)
    fi
    [ -n "$art" ] || die "expected artifact for flavor '$f' not found"
    log "built ($f): $art"
    case $f in
        play) sh scripts/play_policy_check.sh "$art" ;;
        foss) sh scripts/play_policy_check.sh --foss "$art" ;;
    esac
done
log "build_release: DONE"
