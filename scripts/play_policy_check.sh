#!/bin/sh
# APK-only D07 Play/Foss policy gate. The public release workflow remains
# frozen while Issue #12's release-verification acceptance is open.
#
# Usage:
#   scripts/play_policy_check.sh <play-apk> [second-play-apk ...]
#   scripts/play_policy_check.sh --foss <foss-apk> [second-foss-apk ...]
#
# The Python scanner checks ZIP/APK structure, manifest output from aapt,
# DEX type_ids and relevant class_defs. Native dexdump is a bounded-time,
# output-discarded parser sanity check; it is not the source of type refs and
# this scanner is not a complete DEX/ART semantic verifier.
set -eu
export LC_ALL=C

MODE=play
if [ "${1:-}" = "--foss" ]; then
    MODE=foss
    shift
fi
if [ "$#" -lt 1 ]; then
    printf 'usage: %s [--foss] <apk> [...]; AAB is unsupported\n' "$0" >&2
    exit 2
fi

# Reject unsupported formats and missing inputs before source/tool checks so an
# AAB can never be mistaken for a policy-scanned APK.
for ART in "$@"; do
    case "$ART" in
        *.aab|*.AAB)
            printf 'FAIL: AAB is unsupported by the APK-only policy gate: %s\n' "$ART" >&2
            exit 1
            ;;
        *.apk|*.APK) ;;
        *)
            printf 'FAIL: policy gate accepts APK files only (AAB unsupported): %s\n' "$ART" >&2
            exit 1
            ;;
    esac
    if [ ! -f "$ART" ]; then
        printf 'FAIL: artifact not found: %s\n' "$ART" >&2
        exit 1
    fi
done

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
BLACKLIST_PERMS="SEND_SMS RECEIVE_SMS READ_SMS MANAGE_EXTERNAL_STORAGE BIND_ACCESSIBILITY_SERVICE BIND_VPN_SERVICE"
FOSS_BLACKLIST="com.google.mlkit com.google.android.gms com.microsoft.cognitiveservices.speech rikka.shizuku"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# Preserve source-level checks as defense in depth; artifact checks remain the
# authority for the bytes supplied to this gate.
if [ "$MODE" = play ]; then
    for m in "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/src/play/AndroidManifest.xml"; do
        [ -f "$m" ] || continue
        BODY=$(grep -v 'tools:node="remove"' "$m" || true)
        for p in $BLACKLIST_PERMS; do
            if printf '%s\n' "$BODY" | grep -q "$p"; then
                fail "source manifest $m declares $p"
            fi
        done
    done
else
    for m in "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/src/foss/AndroidManifest.xml"; do
        [ -f "$m" ] || continue
        for needle in $FOSS_BLACKLIST; do
            if grep -i -q "$needle" "$m"; then
                fail "source manifest $m references proprietary $needle"
            fi
        done
    done
fi
if grep -rn 'usesCleartextTraffic="true"' "$ROOT/app/src" 2>/dev/null | grep -q .; then
    fail 'cleartext explicitly enabled somewhere under app/src'
fi
NETWORK_CONFIG="$ROOT/app/src/main/res/xml/network_security_config.xml"
[ -f "$NETWORK_CONFIG" ] || fail "missing $NETWORK_CONFIG"
grep -q 'cleartextTrafficPermitted="false"' "$NETWORK_CONFIG" || fail "$NETWORK_CONFIG does not deny cleartext"
for f in "$ROOT/app/src/main/res/xml/backup_rules.xml" "$ROOT/app/src/main/res/xml/data_extraction_rules.xml"; do
    [ -f "$f" ] || fail "missing $f"
    grep -q 'librepocket_keys' "$f" || fail "$f does not exclude key prefs"
done

find_tool_pair() {
    for sdk in "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        [ -n "$sdk" ] || continue
        if [ -d "$sdk/build-tools" ]; then
            latest=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -n 1 || true)
            if [ -n "$latest" ] && [ -x "$latest/aapt" ] && [ -x "$latest/dexdump" ]; then
                AAPT="$latest/aapt"
                DEXDUMP="$latest/dexdump"
                return 0
            fi
        fi
    done
    if command -v aapt >/dev/null 2>&1 && command -v dexdump >/dev/null 2>&1; then
        AAPT=$(command -v aapt)
        DEXDUMP=$(command -v dexdump)
        return 0
    fi
    return 1
}

if ! find_tool_pair; then
    fail 'required Android build-tools aapt and dexdump were not both found (set ANDROID_HOME)'
fi
command -v python3 >/dev/null 2>&1 || fail 'python3 is required for the bounded APK policy scanner'

for ART in "$@"; do
    printf '== artifact (%s APK gate): %s ==\n' "$MODE" "$ART"
    python3 "$ROOT/scripts/apk_policy_inspect.py" \
        --mode "$MODE" \
        --apk "$ART" \
        --aapt "$AAPT" \
        --dexdump "$DEXDUMP"
done
printf 'play_policy_check (%s APK gate): PASS\n' "$MODE"
