#!/bin/sh
# D07 Play + Foss policy gates (ROADMAP P7 §验收.2 / BACKLOG D07 / CAPABILITY_MATRIX §2).
#
# Usage:
#   scripts/play_policy_check.sh <play-apk-or-aab> [second-artifact ...]
#   scripts/play_policy_check.sh --foss <foss-apk-or-aab> [...]
#
# Play gate (default) checks (any failure exits non-zero):
#   1. source manifests (app/src/main + app/src/play) declare no blacklisted
#      permission — catches accidental additions before the build;
#   2. `aapt dump permissions` on the artifact contains no blacklisted
#      permission (SEND/RECEIVE/READ_SMS, MANAGE_EXTERNAL_STORAGE,
#      BIND_ACCESSIBILITY_SERVICE, BIND_VPN_SERVICE);
#   3. `dexdump` class scan: no class DEFINED under a self-install-only package
#      (Ldev/librepocket/agent/github/, Ldev/librepocket/agent/foss/,
#      Ldev/librepocket/privilege/github/, or the Shizuku transport
#      Lrikka/shizuku/) and no class EXTENDING a forbidden superclass (VpnService /
#      AccessibilityService). Descriptor form is used deliberately: plain-word
#      grep would self-match the policy constants themselves;
#   4. manifest service dump (APK via aapt xmltree) declares no
#      AccessibilityService / VpnService service.
#   5. S4: unzipped entry scan declares no on-device Linux payload
#      (`proot` binary / `rootfs` / `linux/image` — rootfs is download-only
#      in every flavor, so any such entry is a packaging regression).
#   Bundle (.aab) inputs are scanned from the unzipped bundle manifests, so the
#   same gate covers both playRelease and githubRelease bundles (a
#   githubRelease bundle is expected to FAIL the play gate — positive control
#   for self-install-only code).
#
# Foss gate (--foss) checks (any failure exits non-zero):
#   1. source manifests (app/src/main + app/src/foss) contain no proprietary
#      needle (com.google.mlkit / com.google.android.gms /
#      com.microsoft.cognitiveservices.speech) — catches an
#      accidental proprietary dependency before the build;
#   2. `dexdump` Class-descriptor scan: no class REFERENCED under a proprietary
#      prefix (Lcom/google/mlkit/ or Lcom/google/android/gms/ or
#      Lcom/microsoft/cognitiveservices/speech/). Descriptor form
#      (L + slashes + /) is used deliberately: the dot-form policy constants
#      can never self-match it;
#   3. manifest dump (APK via aapt, AAB via bundle-manifest scan) declares no
#      proprietary (GMS) permission.
#
# S2: Azure Speech is github-flavor only (azure_tts default off); the foss
# gate covers it alongside ML Kit / GMS.
#
# Mirrors HardeningPolicy (PLAY_PERMISSION_BLACKLIST / PLAY_CLASS_BLACKLIST /
# PLAY_SUPERCLASS_BLACKLIST / PLAY_LINUX_ENTRY_BLACKLIST / FOSS_STRING_BLACKLIST
# + checkFossArtifact):
# play class prefixes cover both self-install flavors (github + foss a11y),
# the S3 privilege bridge, and the Shizuku transport (githubImplementation
# only); foss dex needles are the Dalvik form (L + slashes + /) of the
# dot-form policy constants, so the artifact scanner stays silent on the
# policy class itself.
set -eu
# Deterministic grep/sort byte semantics across locales.
export LC_ALL=C

BLACKLIST_PERMS="SEND_SMS RECEIVE_SMS READ_SMS MANAGE_EXTERNAL_STORAGE BIND_ACCESSIBILITY_SERVICE BIND_VPN_SERVICE"
BLACKLIST_CLASS_PREFIXES="Ldev/librepocket/agent/github/ Ldev/librepocket/agent/foss/ Ldev/librepocket/privilege/github/ Lrikka/shizuku/"
BLACKLIST_SUPERS="Landroid/net/VpnService; Landroid/accessibilityservice/AccessibilityService;"
FOSS_BLACKLIST="com.google.mlkit com.google.android.gms com.microsoft.cognitiveservices.speech rikka.shizuku"
FOSS_DEX_PREFIXES="Lcom/google/mlkit/ Lcom/google/android/gms/ Lcom/microsoft/cognitiveservices/speech/ Lrikka/shizuku/"
FAIL=0
# Current per-artifact temp dir (single EXIT trap below cleans it on interrupt).
TMP_CURRENT=""

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
    # Fallback: newest installed build-tools (version-sorted, not hardcoded).
    for sdk in "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        if [ -n "$sdk" ]; then
            latest=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)
            if [ -x "${latest}aapt" ]; then printf '%s' "${latest}aapt"; return 0; fi
        fi
    done
    if command -v aapt >/dev/null 2>&1; then command -v aapt; return 0; fi
    return 1
}

MODE="play"
if [ "${1:-}" = "--foss" ]; then
    MODE="foss"
    shift
fi

if [ "$#" -lt 1 ]; then
    printf 'usage: %s [--foss] <apk-or-aab> [...]\n' "$0" >&2
    exit 2
fi

# --- 1. source-manifest guard --------------------------------------------------
if [ "$MODE" = "play" ]; then
    log "== source manifests (play gate) =="
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
else
    log "== source manifests (foss gate) =="
    # Mirrors HardeningPolicy.FOSS_STRING_BLACKLIST: the foss overlay must not
    # pull in proprietary Play-services / ML Kit / Azure Speech references. Manifest-only
    # scan (the policy class itself legitimately names these in dot form, so
    # .kt sources are covered by the dex gate instead).
    for m in "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/src/foss/AndroidManifest.xml"; do
        if [ ! -f "$m" ]; then
            log "  (skip) missing $m"
            continue
        fi
        for needle in $FOSS_BLACKLIST; do
            if grep -i -q "$needle" "$m"; then
                fail "source manifest $m references proprietary $needle"
            fi
        done
    done
fi
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
    # Fail fast (not via fail()): without aapt every artifact check below
    # would vacuously print OK on empty output.
    printf 'FAIL: aapt not found (set ANDROID_HOME)\n'
    printf 'play_policy_check (%s gate): FAILED\n' "$MODE"
    exit 1
fi
BT_DIR=$(dirname -- "${AAPT:-/nonexistent}")
if [ -x "$BT_DIR/dexdump" ]; then DEXDump="$BT_DIR/dexdump"; else DEXDump=""; fi

for ART in "$@"; do
    if [ ! -f "$ART" ]; then
        fail "artifact not found: $ART"
        continue
    fi
    log "== artifact ($MODE gate): $ART =="

    TMP=$(mktemp -d)
    TMP_CURRENT="$TMP"
    # Single-shot cleanup for interrupt: expanded now (double quotes), so later
    # iterations cannot clobber the trapped path (no late "$TMP" expansion).
    # shellcheck disable=SC2064
    trap "rm -rf \"$TMP\"" INT TERM
    unzip -oq "$ART" -d "$TMP"

    if [ "$MODE" = "play" ]; then
        # 2. permission blacklist via aapt / bundle manifest scan
        case "$ART" in
            *.aab)
                AAB_FAIL=0
                if [ -n "$(find "$TMP" -name "AndroidManifest.xml" -print -quit)" ]; then
                    for p in $BLACKLIST_PERMS; do
                        if find "$TMP" -name "AndroidManifest.xml" -exec grep -a -q "$p" {} + 2>/dev/null; then
                            fail "$ART: blacklisted permission ($p) inside bundle manifest"
                            AAB_FAIL=1
                        fi
                    done
                fi
                if [ "$AAB_FAIL" -eq 0 ]; then
                    log "  permissions(bundle-scan): OK"
                fi
                ;;
            *)
                PERMS=$("$AAPT" dump permissions "$ART" 2>/dev/null || true)
                XMLTREE=$("$AAPT" dump xmltree "$ART" AndroidManifest.xml 2>/dev/null || true)
                APK_PERM_FAIL=0
                for p in $BLACKLIST_PERMS; do
                    if printf '%s\n' "$PERMS" | grep -q "$p" || printf '%s\n' "$XMLTREE" | grep -q "$p"; then
                        fail "$ART declares $p"
                        APK_PERM_FAIL=1
                    fi
                done
                if [ "$APK_PERM_FAIL" -eq 0 ]; then
                    log "  permissions(aapt): OK"
                fi
                ;;
        esac

        # 3. dex class scan (works for APK and AAB: both are zips with dex files).
        #    Uses dexdump class descriptors — precise and immune to const-string
        #    self-matches (e.g. the policy constants themselves).
        DEX_FAIL=0
        if [ -n "${DEXDump:-}" ]; then
            # find -exec (not `for d in $(find)`) so paths with spaces are safe.
            DUMP=$(find "$TMP" -type f -name "*.dex" -exec "$DEXDump" {} \; 2>/dev/null | grep -E "Class descriptor|Superclass" || true)
            for prefix in $BLACKLIST_CLASS_PREFIXES; do
                if printf '%s\n' "$DUMP" | grep -F -q "$prefix"; then
                    fail "$ART dex defines self-install-only class ($prefix)"
                    DEX_FAIL=1
                fi
            done
            for sup in $BLACKLIST_SUPERS; do
                if printf '%s\n' "$DUMP" | grep -F -q "Superclass        : '$sup'"; then
                    fail "$ART dex subclasses $sup"
                    DEX_FAIL=1
                fi
            done
        else
            # Fallback without dexdump: descriptor-form binary grep (L-prefix +
            # slashes never matches the dot-form policy constants). Mirrors HardeningPolicy
            # PLAY_CLASS_BLACKLIST (github + foss a11y prefixes, the S3 privilege
            # bridge prefix, and the Shizuku transport prefix, each pinned below
            # on a concrete descriptor).
            for needle in "Ldev/librepocket/agent/github/GithubAccessibilityService;" \
                          "Ldev/librepocket/agent/foss/FossAccessibilityService;" \
                          "Ldev/librepocket/privilege/github/" \
                          "Lrikka/shizuku/" \
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

        # 4. manifest service assertion
        case "$ART" in
            *.aab)
                if [ -n "$(find "$TMP" -name "AndroidManifest.xml" -print -quit)" ]; then
                    if find "$TMP" -name "AndroidManifest.xml" -exec grep -a -i -E "accessibilityservice|vpnservice" {} + >/dev/null 2>&1; then
                        fail "$ART bundle manifest registers an Accessibility/Vpn service"
                    else
                        log "  manifest-services(bundle-scan): OK"
                    fi
                fi
                ;;
            *.apk)
                XMLTREE=$("$AAPT" dump xmltree "$ART" AndroidManifest.xml 2>/dev/null || true)
                # -E alternation (not BRE \|): portable to non-GNU grep.
                if printf '%s\n' "$XMLTREE" | grep -i -E -q "accessibilityservice|vpnservice"; then
                    fail "$ART manifest registers an Accessibility/Vpn service"
                else
                    log "  manifest-services: OK"
                fi
                ;;
        esac

        # 5. S4 on-device Linux payload assertion (mirrors
        #    HardeningPolicy.PLAY_LINUX_ENTRY_BLACKLIST + checkPlayLinuxEntries):
        #    the PRoot binary and any container image/rootfs must never ship
        #    inside the play artifact (rootfs is download-only in all flavors).
        LINUX_FAIL=0
        ENTRIES=$(cd "$TMP" && find . | sed 's|^\./||')
        for needle in proot rootfs linux/image; do
            HIT=$(printf '%s\n' "$ENTRIES" | grep -i -F "$needle" || true)
            if [ -n "$HIT" ]; then
                fail "$ART embeds on-device Linux payload ($needle): $(printf '%s' "$HIT" | head -n 3 | tr '\n' ' ')"
                LINUX_FAIL=1
            fi
        done
        if [ "$LINUX_FAIL" -eq 0 ]; then
            log "  linux-payload: OK"
        fi
    else
        # --- foss gate per-artifact checks (mirror checkFossArtifact) ---
        # 2. dex proprietary-reference scan: Class descriptors only.
        DEX_FAIL=0
        if [ -n "${DEXDump:-}" ]; then
            # find -exec (not `for d in $(find)`) so paths with spaces are safe.
            DUMP=$(find "$TMP" -type f -name "*.dex" -exec "$DEXDump" {} \; 2>/dev/null | grep "Class descriptor" || true)
            for prefix in $FOSS_DEX_PREFIXES; do
                if printf '%s\n' "$DUMP" | grep -F -q "$prefix"; then
                    fail "$ART dex references proprietary ($prefix)"
                    DEX_FAIL=1
                fi
            done
        else
            # Fallback without dexdump: descriptor-form binary grep (L-prefix +
            # slashes never matches the dot-form policy constants).
            for prefix in $FOSS_DEX_PREFIXES; do
                if grep -R -l -F "$prefix" "$TMP" 2>/dev/null | grep -q .; then
                    fail "$ART dex references proprietary ($prefix)"
                    DEX_FAIL=1
                fi
            done
        fi
        if [ "$DEX_FAIL" -eq 0 ]; then
            log "  dex-proprietary: OK"
        fi

        # 3. manifest GMS-permission assertion
        case "$ART" in
            *.aab)
                MAN_FAIL=0
                if [ -n "$(find "$TMP" -name "AndroidManifest.xml" -print -quit)" ]; then
                    for needle in $FOSS_BLACKLIST; do
                        if find "$TMP" -name "AndroidManifest.xml" -exec grep -a -i -q "$needle" {} + 2>/dev/null; then
                            fail "$ART bundle manifest references proprietary ($needle)"
                            MAN_FAIL=1
                        fi
                    done
                fi
                if [ "$MAN_FAIL" -eq 0 ]; then
                    log "  manifest-proprietary(bundle-scan): OK"
                fi
                ;;
            *)
                PERMS=$("$AAPT" dump permissions "$ART" 2>/dev/null || true)
                XMLTREE=$("$AAPT" dump xmltree "$ART" AndroidManifest.xml 2>/dev/null || true)
                MAN_FAIL=0
                for needle in $FOSS_BLACKLIST; do
                    if printf '%s\n' "$PERMS" | grep -i -q "$needle" || printf '%s\n' "$XMLTREE" | grep -i -q "$needle"; then
                        fail "$ART manifest references proprietary ($needle)"
                        MAN_FAIL=1
                    fi
                done
                if [ "$MAN_FAIL" -eq 0 ]; then
                    log "  manifest-proprietary(aapt): OK"
                fi
                ;;
        esac
    fi

    rm -rf "$TMP"
    TMP_CURRENT=""
    trap - INT TERM
done
# Final interrupt-trap cleanup (leftover TMP_CURRENT on early exit).
if [ -n "${TMP_CURRENT:-}" ]; then rm -rf "$TMP_CURRENT"; fi

if [ "$FAIL" -ne 0 ]; then
    log "play_policy_check ($MODE gate): FAILED"
    exit 1
fi
log "play_policy_check ($MODE gate): PASS"
