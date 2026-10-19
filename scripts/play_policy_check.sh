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

# The backup-rules validator (source-level) runs before the artifact loop, so
# require python3 up front rather than failing later with an opaque message.
command -v python3 >/dev/null 2>&1 || fail 'python3 is required for the backup rules validator and APK policy scanner'

# Preserve source-level checks as defense in depth; artifact checks remain the
# authority for the bytes supplied to this gate.
if [ "$MODE" = play ]; then
    for m in "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/src/play/AndroidManifest.xml"; do
        [ -f "$m" ] || continue
        # Quote-tolerant: single-quoted tools:node value is equally valid XML
        # and equally inert for the merger; a filter that only eats the
        # double-quoted spelling would false-FAIL it.
        BODY=$(grep -vE "tools:node=[\"']remove[\"']" "$m" || true)
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
# Quote-tolerant greps: single-quoted attribute values are equally valid XML
# and equally enable cleartext; a double-quote-literal-only grep was escapable.
if grep -rnE "usesCleartextTraffic=[\"']true" "$ROOT/app/src" 2>/dev/null | grep -q .; then
    fail 'cleartext explicitly enabled somewhere under app/src'
fi
# 結構化 NSC gate（scripts/nsc_check.py）：舊版「檔內任一處出現
# cleartextTrafficPermitted=false 即過」的反例是 base-config false + domain-config
# true（example.com）——整檔語意已放行明文流量而 gate 仍 PASS。改為結構化 XML
# 驗證（ElementTree 忽略註解，註解內殘字不滿足釘點），規則集依「來源集歸屬」
# 分流：debug source set → loopback 白名單規則；其餘 release-bearing source set
# （main/play/foss/github/未來 release overlay）一律 deny-all（規則表見
# scripts/nsc_check.py 模組說明）。
# 深掃吞 alias：語意同形檔名 *network*security*config*.xml 一併驗證（未來 flavor
# overlay 以別名新增 NSC 時也不能漏驗）；find 依舊非管線代入取自身狀態（POSIX
# 賦值狀態＝最後管段，find 不得與 sort 同管），fail-closed。
NSC_LIST="$(find "$ROOT/app/src" -type f -name '*network*security*config*.xml')" ||
    fail 'network security config scan failed'
[ -n "$NSC_LIST" ] || fail 'missing network_security_config.xml under app/src'
[ -f "$ROOT/app/src/main/res/xml/network_security_config.xml" ] ||
    fail 'missing app/src/main/res/xml/network_security_config.xml'
while IFS= read -r nsconfig; do
    [ -n "$nsconfig" ] || continue
    case "$nsconfig" in
        "$ROOT/app/src/debug/"*) nsc_mode=debug ;;
        *) nsc_mode=release ;;
    esac
    python3 "$SCRIPT_DIR/nsc_check.py" --mode "$nsc_mode" "$nsconfig"
done <<EOF
$NSC_LIST
EOF
for f in "$ROOT/app/src/main/res/xml/backup_rules.xml" "$ROOT/app/src/main/res/xml/data_extraction_rules.xml"; do
    [ -f "$f" ] || fail "missing $f"
done
# BackupPolicy.KEY_FILES_EXCLUDED 5 項逐項斷言：以結構化 XML 驗證區分
# cloud-backup / device-transfer 並忽略註解（grep 全文比對會誤判 PASS，見
# scripts/backup_rules_check.py 說明）。
# 檔名↔root 綁定 fail-closed：backup_rules.xml 必須是 <full-backup-content>、
# data_extraction_rules.xml 必須是 <data-extraction-rules>——root 與 Android
# 預期不符時 runtime 會直接丟出解析例外（備份靜默停用），gate 不得誤判 PASS。
python3 "$SCRIPT_DIR/backup_rules_check.py" \
    --expect-root full-backup-content \
    "$ROOT/app/src/main/res/xml/backup_rules.xml"
python3 "$SCRIPT_DIR/backup_rules_check.py" \
    --expect-root data-extraction-rules \
    "$ROOT/app/src/main/res/xml/data_extraction_rules.xml"

# 執行期綁定（attr↔resource）：以結構化 XML 解析逐檔驗證（bare-text grep 會被
# 註解內的同字串假冒——屬性已刪光而僅剩註解字樣時 gate 仍 PASS，即本 gate 要防
# 的金鑰外洩；單引號等合法 XML 格式也會誤判）。主 manifest 兩屬性必填且等值，
# 缺任一即 fail；flavor overlay「有設則必須等值」（省略屬性＝合併沿用 main 值，
# 合法）。此綁定同時封死「rebind 到其他資源逃過驗證」的路徑：屬性只能指向
# @xml/backup_rules / @xml/data_extraction_rules，而這兩個檔名形態已被上方與
# 深掃逐一驗證。
MAIN_MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
[ -f "$MAIN_MANIFEST" ] || fail "missing $MAIN_MANIFEST"
python3 "$SCRIPT_DIR/backup_rules_check.py" --check-manifest --require-both "$MAIN_MANIFEST"
for m in "$ROOT"/app/src/*/AndroidManifest.xml; do
    case "$m" in "$ROOT/app/src/main/AndroidManifest.xml") continue ;; esac
    [ -f "$m" ] || continue
    python3 "$SCRIPT_DIR/backup_rules_check.py" --check-manifest "$m"
done
# NSC 綁定縱深（attr↔resource）：上方的 source 級深掃只驗證了「存在的 NSC 檔」，
# 但 merged manifest 若把 android:networkSecurityConfig rebind 到一個未被掃到的
# 資源（或屬性被整段刪掉），release 就直接落回 platform 預設（允許明文）——
# 檔案本身再乾淨也攔不到。故主 manifest 必須把屬性釘在
# @xml/network_security_config（require-both：缺屬性即 fail），overlay「有設則
# 必須等值」。校驗語義（merger markers、quote-tolerant）由 backup_rules_check
# .validate_manifest 以屬性集參數統一實作，nsc_check.py 只傳入自己的綁定。
python3 "$SCRIPT_DIR/nsc_check.py" --check-manifest --require-both "$MAIN_MANIFEST"
for m in "$ROOT"/app/src/*/AndroidManifest.xml; do
    case "$m" in "$ROOT/app/src/main/AndroidManifest.xml") continue ;; esac
    [ -f "$m" ] || continue
    python3 "$SCRIPT_DIR/nsc_check.py" --check-manifest "$m"
done

# 縱深：掃描 app/src 下所有語意同形資源（檔名尾段含 backup*rules /
# data_extraction*rules；未來 flavor overlay 以別名新增／覆寫 backup rules
# 時也不能漏驗——精確檔名比對曾讓 overlay 逃過整段驗證）。main 兩檔已由
# 上方固定路徑檢查，此處重複驗證無害。find 失敗不可靜默：POSIX 管道無
# pipefail，assignment 的狀態取自最後一個管段（sort），故 find 不得與
# sort 同管——先以非管道代入取 find 自身狀態（fail-closed），再由近乎
# 不會失敗的 printf|sort 做排序。
BACKUP_RULES_LIST="$(find "$ROOT/app/src" -type f \( -name '*backup*rules*.xml' -o -name '*data_extraction*rules*.xml' \) )" ||
    fail 'deep-scan of backup rules resources failed'
BACKUP_RULES_FILES="$(printf '%s\n' "$BACKUP_RULES_LIST" | sort)"
while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
        *backup*rules*.xml) broot=full-backup-content ;;
        *data_extraction*rules*.xml) broot=data-extraction-rules ;;
        *) continue ;;
    esac
    python3 "$SCRIPT_DIR/backup_rules_check.py" --expect-root "$broot" "$f"
done <<EOF
$BACKUP_RULES_FILES
EOF

find_tool_pair() {
    for sdk in "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        [ -n "$sdk" ] || continue
        if [ -d "$sdk/build-tools" ]; then
            # Take the newest directory that contains BOTH executables; a
            # newer incomplete directory must not shadow an older complete
            # pair.
            candidates=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V -r || true)
            # Split on newlines only (version dirs are one per line) and
            # disable globbing so SDK paths with spaces still resolve.
            _old_ifs=$IFS
            IFS='
'
            set -f
            for candidate in $candidates; do
                if [ -x "$candidate/aapt" ] && [ -x "$candidate/dexdump" ]; then
                    AAPT="$candidate/aapt"
                    DEXDUMP="$candidate/dexdump"
                    IFS=$_old_ifs
                    set +f
                    return 0
                fi
            done
            IFS=$_old_ifs
            set +f
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

for ART in "$@"; do
    printf '== artifact (%s APK gate): %s ==\n' "$MODE" "$ART"
    python3 "$ROOT/scripts/apk_policy_inspect.py" \
        --mode "$MODE" \
        --apk "$ART" \
        --aapt "$AAPT" \
        --dexdump "$DEXDUMP"
done
printf 'play_policy_check (%s APK gate): PASS\n' "$MODE"
