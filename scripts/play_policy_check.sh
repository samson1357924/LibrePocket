#!/bin/sh
# D07 Play policy gate (ROADMAP P7 §验收.2 / BACKLOG D07 / CAPABILITY_MATRIX §2).
#
# Usage:
#   scripts/play_policy_check.sh <play-apk-or-aab> [second-artifact ...]
#
# Checks (any failure exits non-zero):
#   1. source manifests (app/src/main + app/src/play) declare no blacklisted
#      permission — catches accidental additions before the build;
#   2. `aapt dump permissions` on the artifact contains no blacklisted
#      permission (SEND/RECEIVE/READ_SMS, MANAGE_EXTERNAL_STORAGE,
#      BIND_ACCESSIBILITY_SERVICE, BIND_VPN_SERVICE);
#   3. `dexdump` class scan: no class DEFINED under the full-only package
#      (Ldev/librepocket/agent/full/) and no class EXTENDING a forbidden
#      superclass (VpnService / AccessibilityService). Descriptor form is
#      used deliberately: plain-word grep would self-match the policy
#      constants themselves;
#   4. manifest service dump (APK via aapt xmltree) declares no
#      AccessibilityService / VpnService service.
#
# Mirrors HardeningPolicy (PLAY_PERMISSION_BLACKLIST / PLAY_CLASS_BLACKLIST /
# PLAY_SUPERCLASS_BLACKLIST).
set -eu

BLACKLIST_PERMS="SEND_SMS RECEIVE_SMS READ_SMS MANAGE_EXTERNAL_STORAGE BIND_ACCESSIBILITY_SERVICE BIND_VPN_SERVICE"
BLACKLIST_CLASS_PREFIX="Ldev/librepocket/agent/full/"
BLACKLIST_SUPERS="Landroid/net/VpnService; Landroid/accessibilityservice/AccessibilityService;"
FAIL=0

log() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*"; FAIL=1; }

# --- locate repo root + aapt -------------------------------------------------
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

find_aapt() {
    for cand in "${ANDROID_HOME:-}/build-tools/36.0.0/aapt" \
                "${ANDROID_HOME:-}/build-tools/35.0.0/aapt" \
                "$HOME/Android/Sdk/build-tools/36.0.0/aapt" \
                "$HOME/Android/Sdk/build-tools/35.0.0/aapt"; do
        if [ -x "$cand" ]; then printf '%s' "$cand"; return 0; fi
    done
    if command -v aapt >/dev/null 2>&1; then command -v aapt; return 0; fi
    return 1
}

if [ "$#" -lt 1 ]; then
    printf 'usage: %s <play-apk-or-aab> [...]\n' "$0" >&2
    exit 2
fi

# --- 1. source-manifest guard --------------------------------------------------
log "== source manifests =="
# NOTE: lines with tools:node="remove" are the play overlay's explicit
# removals (defense in depth) — compliant by design, so strip them before
# asserting. A real declaration has no such attribute.
for m in "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/src/play/AndroidManifest.xml"; do
    if [ ! -f "$m" ]; then
        log "  (skip) missing $m"
        continue
    fi
    BODY=$(grep -v 'tools:node="remove"' "$m" || true)
    for p in $BLACKLIST_PERMS; do
        if printf '%s\n' "$BODY" | grep -q "$p"; then
            fail "source manifest $m declares $p"
        fi
    done
done
if grep -rn "usesCleartextTraffic=\"true\"" "$ROOT/app/src" 2>/dev/null | grep -q .; then
    fail "cleartext explicitly enabled somewhere under app/src"
fi
for f in "$ROOT/app/src/main/res/xml/network_security_config.xml"; do
    if [ -f "$f" ]; then
        if ! grep -q 'cleartextTrafficPermitted="false"' "$f"; then
            fail "$f does not deny cleartext"
        fi
    else
        fail "missing $f"
    fi
done
for f in "$ROOT/app/src/main/res/xml/backup_rules.xml" \
         "$ROOT/app/src/main/res/xml/data_extraction_rules.xml"; do
    if [ -f "$f" ]; then
        if ! grep -q "librepocket_keys" "$f"; then
            fail "$f does not exclude key prefs"
        fi
    else
        fail "missing $f"
    fi
done

# --- per-artifact checks -------------------------------------------------------
AAPT=$(find_aapt || true)
if [ -z "${AAPT:-}" ]; then
    fail "aapt not found (set ANDROID_HOME)"
fi
BT_DIR=$(dirname -- "${AAPT:-/nonexistent}")
if [ -x "$BT_DIR/dexdump" ]; then DEXDump="$BT_DIR/dexdump"; else DEXDump=""; fi

for ART in "$@"; do
    if [ ! -f "$ART" ]; then
        fail "artifact not found: $ART"
        continue
    fi
    log "== artifact: $ART =="

    # 2. permission blacklist via aapt (APK; AAB has no runtime manifest per
    #    se, so fall back to the base manifest inside the bundle).
    case "$ART" in
        *.aab)
            TMPB=$(mktemp -d)
            trap 'rm -rf "$TMPB"' EXIT INT TERM
            unzip -oq "$ART" -d "$TMPB"
            if grep -R -l "MANAGE_EXTERNAL_STORAGE\|READ_SMS\|RECEIVE_SMS\|SEND_SMS" "$TMPB" 2>/dev/null | grep -q .; then
                fail "$ART: blacklisted permission string inside bundle"
            else
                log "  permissions(bundle-scan): OK"
            fi
            rm -rf "$TMPB"
            trap - EXIT INT TERM
            ;;
        *)
            PERMS=$("$AAPT" dump permissions "$ART" 2>/dev/null || true)
            for p in $BLACKLIST_PERMS; do
                if printf '%s\n' "$PERMS" | grep -q "$p"; then
                    fail "$ART declares $p"
                fi
            done
            log "  permissions(aapt): OK"
            ;;
    esac

    # 3. dex class scan (works for APK and AAB: both are zips with dex files).
    #    Uses dexdump class descriptors — precise and immune to const-string
    #    self-matches (e.g. the policy constants themselves).
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT INT TERM
    unzip -oq "$ART" -d "$TMP"
    DEX_FAIL=0
    if [ -n "${DEXDump:-}" ]; then
        DUMP=$(for d in "$TMP"/classes*.dex "$TMP"/base/classes*.dex; do
            [ -f "$d" ] || continue
            "$DEXDump" "$d" 2>/dev/null | grep -E "Class descriptor|Superclass" || true
        done)
        if printf '%s\n' "$DUMP" | grep -F -q "$BLACKLIST_CLASS_PREFIX"; then
            fail "$ART dex defines full-only class ($BLACKLIST_CLASS_PREFIX)"
            DEX_FAIL=1
        fi
        for sup in $BLACKLIST_SUPERS; do
            if printf '%s\n' "$DUMP" | grep -F -q "Superclass        : '$sup'"; then
                fail "$ART dex subclasses $sup"
                DEX_FAIL=1
            fi
        done
    else
        # Fallback without dexdump: descriptor-form binary grep (L-prefix + ';'
        # suffix never matches plain-word const-strings).
        for needle in "${BLACKLIST_CLASS_PREFIX}FullAccessibilityService;" \
                      "Landroid/net/VpnService;" \
                      "Landroid/accessibilityservice/AccessibilityService;"; do
            if grep -R -l -F "$needle" "$TMP" 2>/dev/null | grep -q .; then
                fail "$ART dex contains $needle"
                DEX_FAIL=1
            fi
        done
    fi
    if [ "$DEX_FAIL" -eq 0 ]; then
        log "  dex-classes: OK"
    fi

    # 4. manifest service assertion (APK only; AAB services are covered by the
    #    dex scan above since the class must be present to be registered).
    case "$ART" in
        *.apk)
            XMLTREE=$("$AAPT" dump xmltree "$ART" AndroidManifest.xml 2>/dev/null || true)
            if printf '%s\n' "$XMLTREE" | grep -i -q "accessibilityservice\|vpnservice"; then
                fail "$ART manifest registers an Accessibility/Vpn service"
            else
                log "  manifest-services: OK"
            fi
            ;;
    esac
    rm -rf "$TMP"
    trap - EXIT INT TERM
done

if [ "$FAIL" -ne 0 ]; then
    log "play_policy_check: FAILED"
    exit 1
fi
log "play_policy_check: PASS"
