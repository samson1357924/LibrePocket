#!/bin/sh
# APK-only D07 Play/Foss policy gate. The public release workflow remains
# frozen while Issue #12's release-verification acceptance is open.
#
# Usage:
#   scripts/play_policy_check.sh <play-apk> [second-play-apk ...]
#   scripts/play_policy_check.sh --foss <foss-apk> [second-foss-apk ...]
#   scripts/play_policy_check.sh --github <github-apk> [...]
#   scripts/play_policy_check.sh [--foss|--github] --aapt <path> <apk> [...]
#
# --aapt overrides the gate's automatic aapt discovery with the CALLER's
# executable (existence + executability validated, named fail otherwise).
# Without it, discovery is unchanged (newest build-tools dir carrying BOTH
# aapt and dexdump; see find_tool_pair below). The release verifier pins its
# aapt by an explicit caller path (verify_release_apk.py --aapt); when this
# gate runs inside that flow (release_artifact_stage.py::_run_policy) the
# SAME path is forwarded here so one staged traffic of bytes is measured by
# one ruler - not by two independently discovered aapt binaries.
#
# The common manifest policy (P2-1: NO SMS / MANAGE_EXTERNAL_STORAGE / VPN in
# ANY flavor) is enforced at source level in EVERY lane -- see the
# structured permission-removal gate below -- and on the artifact by
# scripts/apk_policy_inspect.py --mode github for the github flavor.
#
# "Every lane" scans the PER-LANE UNIVERSE (AGP manifest merge precedence:
# variant > buildType > flavor > main), not one branch of anything: for mode
# X the lane's scan universe is
#   app/src/main/AndroidManifest.xml                  (base, mandatory)
#   app/src/<X>/AndroidManifest.xml                   (flavor overlay, mandatory)
#   app/src/{debug,release}/AndroidManifest.xml       (buildType, all flavors)
#   app/src/<X><BuildType>/AndroidManifest.xml        (variant dirs: playRelease,
#                                                      fossDebug, githubRelease, ...)
# Other flavors' overlays (e.g. app/src/play/ for the foss lane) are OUTSIDE
# the universe - a play overlay may carry GMS strings and must never trip a
# foss scan - and the test source sets (test*/androidTest) never merge into
# an APK. A rogue app/src/release/AndroidManifest.xml therefore fails every
# lane here even though a Debug APK never contains the release source set.
#
# The Python scanner checks ZIP/APK structure, manifest output from aapt,
# DEX type_ids and relevant class_defs. Native dexdump is a bounded-time,
# output-discarded parser sanity check; it is not the source of type refs and
# this scanner is not a complete DEX/ART semantic verifier.
set -eu
export LC_ALL=C

MODE=play
case "${1:-}" in
    --foss)
        MODE=foss
        shift
        ;;
    --github)
        MODE=github
        shift
        ;;
esac
# --aapt <path> must be parsed BEFORE the artifact-argument loop (an override
# path is not an APK and must never reach the *.apk extension filter). It
# rides --foss/--github and precedes the APK operands: options before
# operands, first arg still the mode flag, last arg still the primary APK.
AAPT_OVERRIDE=
case "${1:-}" in
    --aapt)
        if [ "$#" -lt 2 ]; then
            printf 'usage: %s [--foss|--github] [--aapt <path>] <apk> [...]; AAB is unsupported\n' "$0" >&2
            exit 2
        fi
        AAPT_OVERRIDE=$2
        shift 2
        ;;
esac
if [ "$#" -lt 1 ]; then
    printf 'usage: %s [--foss|--github] [--aapt <path>] <apk> [...]; AAB is unsupported\n' "$0" >&2
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
# Comma form for the structured validator (same tokens).
BLACKLIST_PERMS_CSV=$(printf '%s' "$BLACKLIST_PERMS" | tr ' \n' ',,')
FOSS_BLACKLIST="com.google.mlkit com.google.android.gms com.microsoft.cognitiveservices.speech rikka.shizuku"
# P2-1 通行 manifest 政策（locked decision：任何 flavor 皆 NO SMS、NO
# MANAGE_EXTERNAL_STORAGE、NO VPN）：結構化 permission-removal 掃描在「每一個」
# 分支都要跑（F6：MAIN manifest 加進 SEND_SMS 必須讓每個 flavor 門都紅）。
# play 在通行黑名單之上額外保留 play-only 的 BIND_ACCESSIBILITY_SERVICE；
# a11y token 永不得進入通行黑名單——foss/github overlay 依法合法宣告
# accessibility service（見兩份 overlay manifest 註記）。
COMMON_BLACKLIST_PERMS="SEND_SMS RECEIVE_SMS READ_SMS MANAGE_EXTERNAL_STORAGE BIND_VPN_SERVICE"
# Comma form for the structured validator (same tokens).
COMMON_BLACKLIST_PERMS_CSV=$(printf '%s' "$COMMON_BLACKLIST_PERMS" | tr ' \n' ',,')

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# The backup-rules validator (source-level) runs before the artifact loop, so
# require python3 up front rather than failing later with an opaque message.
command -v python3 >/dev/null 2>&1 || fail 'python3 is required for the backup rules validator and APK policy scanner'

# --aapt override validation (named fail, never a silent fallback): the
# override must be an existing, non-symlink, executable regular file - the
# same shape build_release.sh demands of its caller-supplied aapt. A broken
# override fails HERE (before any source/artifact work) so the release flow
# cannot (a) silently rebuild the ruler via auto-discovery when the caller's
# tool wiring is broken, nor (b) surface the defect as an opaque scanner
# error deep inside the native scan. Misuse surface (message text only, no
# behavior change): the --aapt case above consumes the NEXT token
# UNCONDITIONALLY as the override path (options-before-operands is
# documented here, not re-ordered by lookahead), so a misordered invocation
# feeds the APK operand into the override slot and fails in THIS check
# rather than as a usage error; the named message therefore points at the
# likely misplaced flag, with an extra cue when the rejected token itself
# ends in .apk (the APK operand was eaten as the override path).
if [ -n "$AAPT_OVERRIDE" ]; then
    if [ ! -L "$AAPT_OVERRIDE" ] && [ -f "$AAPT_OVERRIDE" ] && [ -x "$AAPT_OVERRIDE" ]; then
        :
    else
        aapt_hint='; --aapt consumes the next token unconditionally (option order: [--foss|--github] --aapt <path> <apk> [...]): if this token is actually the APK operand, the --aapt flag is likely misplaced'
        case "$AAPT_OVERRIDE" in
            *.apk|*.APK)
                aapt_hint="${aapt_hint}; this token itself ends with .apk - the APK operand was likely eaten as the override path"
                ;;
        esac
        fail "aapt override is not an existing non-symlink executable file: $AAPT_OVERRIDE${aapt_hint}"
    fi
fi

# Preserve source-level checks as defense in depth; artifact checks remain the
# authority for the bytes supplied to this gate. The Play permission blacklist
# is enforced by the STRUCTURED manifest scanner (backup_rules_check.py
# validate_permission_removals), NOT by the old per-line grep: a legal
# multi-line element differs over which line carries "tools:node=remove", so
#   BODY=$(grep -vE "tools:node=[\"']remove[\"']" ...) ; grep -q SEND_SMS
# false-FAILED <uses-permission android:name="...SEND_SMS"\n
# tools:node="remove" /> (its first line still contained the token), while a
# whole-body grep cannot separate real elements from XML comments anyway. The
# structured scan is element-aware (multi-line/continuation spellings included,
# comments dropped by the stdlib parser) and requires tools:node="remove" ON
# THE ELEMENT for every blacklisted name - the exact merger-marker machinery
# the other manifest checks here already run.
#
# P2-1：通行黑名單在每個 lane 都結構化執行（foss lane 過去只掃專有字串、
# github lane 在 shell 層完全沒有 lane）：token 依 mode 分流，play 用完整六
# token（含 a11y），foss/github 只用通行五 token（絕不含 a11y）；manifest 缺
# 檔一律 FAIL（過去 `|| continue` 讓整段結構化掃描可被 missing file 靜默
# 跳過＝fail-open，故改 fail-closed）。
# P2：掃描宇宙＝per-lane universe（AGP merge 優先序：variant > buildType >
# flavor > main）＝main + 該 lane 自己的 flavor overlay（固定路徑，缺檔
# named fail）+ 該 flavor 的 buildType overlay（app/src/{debug,release} 對
# 所有 flavor 生效）+ 其 <flavor><BuildType> variant 目錄（例 playRelease、
# fossDebug、githubRelease，前綴命中）；其它 flavor 的 overlay（例 play
# overlay 對 foss lane 的 GMS needle 掃描會誤殺）與 test*/androidTest 測試
# source set（不屬 APK merge）排除。植入 app/src/release/AndroidManifest.xml
# 的 SEND_SMS 曾躲過「main + flavor overlay」兩固定路徑掃描——Debug APK 不含
# release source set，三 lane 全綠直至 release staging——現在 release/variant
# overlay 屬於每個 lane 的掃描宇宙（glob 枚舉 + case 過濾，fail-closed：
# overlay 存在即掃，未來新增 release/<flavor><BuildType> 目錄不可能漏掃）。
case "$MODE" in
    play)
        PERM_TOKENS="$BLACKLIST_PERMS_CSV"
        FLAVOR_DIR="play"
        ;;
    foss)
        PERM_TOKENS="$COMMON_BLACKLIST_PERMS_CSV"
        FLAVOR_DIR="foss"
        ;;
    github)
        PERM_TOKENS="$COMMON_BLACKLIST_PERMS_CSV"
        FLAVOR_DIR="github"
        ;;
    *)
        fail "unknown mode: $MODE"
        ;;
esac
# Two mandatory manifests per lane, by FIXED path (fail-closed named):
# main (the base every variant merges) and the lane's own flavor overlay.
# The repo requires both to exist; a vanished one must stop the gate here.
for m in "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/src/$FLAVOR_DIR/AndroidManifest.xml"; do
    [ -f "$m" ] || fail "missing manifest: $m"
    python3 "$SCRIPT_DIR/backup_rules_check.py" \
        --check-permission-removals "$PERM_TOKENS" \
        "$m"
done
# Extended per-lane universe (base + mandatory overlay already scanned
# above). The glob enumerates every top-level source set's manifest — an
# existing source set WITHOUT a manifest contributes nothing to an AGP
# merge, so scanning exactly the manifests that exist is the complete
# fail-closed universe for future release/<flavor><BuildType> dirs (they
# cannot escape by not existing; if they exist, they are in the glob). The
# case filter keeps buildType overlays (debug/release apply to ALL
# flavors), and the lane's <flavor><BuildType> variant overlays via the
# flavor-name prefix; it drops the two mandatory manifests (no duplicate
# scan), other flavors' overlays (e.g. app/src/play/ must not trip the
# foss lane), and the test source sets (test*/androidTest never merge).
# NOTE: buildType overlays are pinned to {debug, release} literals below;
# add a new buildType in app/build.gradle.kts and extend that arm here.
for m in "$ROOT"/app/src/*/AndroidManifest.xml; do
    case "$m" in
        "$ROOT/app/src/main/AndroidManifest.xml"|"$ROOT/app/src/$FLAVOR_DIR/AndroidManifest.xml") continue ;;
        "$ROOT/app/src/debug/AndroidManifest.xml"|"$ROOT/app/src/release/AndroidManifest.xml") ;;
        "$ROOT/app/src/${FLAVOR_DIR}"*/AndroidManifest.xml) ;;
        "$ROOT/app/src/test"*/AndroidManifest.xml|"$ROOT/app/src/androidTest"*/AndroidManifest.xml) continue ;;
        *) continue ;;
    esac
    [ -f "$m" ] || fail "missing manifest: $m"
    python3 "$SCRIPT_DIR/backup_rules_check.py" \
        --check-permission-removals "$PERM_TOKENS" \
        "$m"
done
# The foss proprietary-string scan follows the SAME per-lane universe —
# NOT a blind "every manifest" sweep, which would grep other flavors'
# overlays (a play overlay could fake a GMS hit for the foss lane) and the
# test source sets. Mandatory pair by fixed path (named fail), extended
# universe by the same glob + case filter as the permission scan above.
if [ "$MODE" = foss ]; then
    for m in "$ROOT/app/src/main/AndroidManifest.xml" "$ROOT/app/src/foss/AndroidManifest.xml"; do
        [ -f "$m" ] || fail "missing manifest: $m"
        for needle in $FOSS_BLACKLIST; do
            if grep -i -q "$needle" "$m"; then
                fail "source manifest $m references proprietary $needle"
            fi
        done
    done
    for m in "$ROOT"/app/src/*/AndroidManifest.xml; do
        case "$m" in
            "$ROOT/app/src/main/AndroidManifest.xml"|"$ROOT/app/src/foss/AndroidManifest.xml") continue ;;
            "$ROOT/app/src/debug/AndroidManifest.xml"|"$ROOT/app/src/release/AndroidManifest.xml") ;;
            "$ROOT/app/src/foss"*/AndroidManifest.xml) ;;
            "$ROOT/app/src/test"*/AndroidManifest.xml|"$ROOT/app/src/androidTest"*/AndroidManifest.xml) continue ;;
            *) continue ;;
        esac
        for needle in $FOSS_BLACKLIST; do
            if grep -i -q "$needle" "$m"; then
                fail "source manifest $m references proprietary $needle"
            fi
        done
    done
fi
# Quote-tolerant, error-honest cleartext scan: single-quoted attribute values
# are equally valid XML and equally enable cleartext (the double-quote-only
# grep was escapable), whitespace around "=" is legal spelling, and POSIX
# grep marking (0=violation, 1=clean, 2+=scan failure) must be observed - the
# retired `2>/dev/null` + pipeline wiring swallowed rc 2, silently PASSing on
# unreadable subtrees (fail-open). 這裡保留 raw-text 掃描：註解內殘字也算
# 違規（比 runtime 更嚴做 fail-closed）；編譯後語意另由 NSC/manifest 結構化門管。
cleartext_rc=0
grep -rnE "usesCleartextTraffic[[:space:]]*=[[:space:]]*[\"']true" "$ROOT/app/src" || cleartext_rc=$?
case "$cleartext_rc" in
    0) fail 'cleartext explicitly enabled somewhere under app/src' ;;
    1) : ;;
    *) fail 'cleartext scan failed (grep filesystem error under app/src)' ;;
esac
# 結構化 NSC gate（scripts/nsc_check.py）：舊版「檔內任一處出現
# cleartextTrafficPermitted=false 即過」的反例是 base-config false + domain-config
# true（example.com）——整檔語意已放行明文流量而 gate 仍 PASS。改為結構化 XML
# 驗證（ElementTree 忽略註解，註解內殘字不滿足釘點），規則集依「來源集歸屬」
# 分流：debug source set → loopback 白名單規則；其餘 release-bearing source set
# （main/play/foss/github/未來 release overlay）一律 deny-all（規則表見
# scripts/nsc_check.py 模組說明）。
# 深掃吞 alias：語意同形檔名一併驗證——`*network*security*.xml` 比「必須含
# config」的舊比對更寬：dash 拼法（network-security-config.xml）、缺段落別名
# （network-security.xml）均列入，未來 flavor overlay 以別名新增 NSC 時也不
# 能漏驗；檔名放寬只能讓 find 掃到「更多檔」，不可能放寬內容判準
# （nsc_check.py 以 XML root/shape fail-closed 驗證每一個被掃到的檔，與 NSC
# 本體檔名無關的檔會被拒）。如實記錄清單寬度：雙 `*` 碎片核取連「無分隔符全
# 連寫」形 networksecurityconfig.xml 也一併命中（network * 空 * security），
# 清單不含的情況不存在於此側。find 依舊非管線代入取自身狀態（POSIX 賦值狀態＝
# 最後管段，find 不得與 sort 同管），fail-closed。
NSC_LIST="$(find "$ROOT/app/src" -type f -name '*network*security*.xml')" ||
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
# 深掃逐一驗證。overlay manifest 缺檔不得被靜默跳過（`|| continue` 曾讓
# missing flavor manifest 使整段 overlay 綁定驗證靜默失效＝fail-open）。
MAIN_MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
[ -f "$MAIN_MANIFEST" ] || fail "missing manifest: $MAIN_MANIFEST"
python3 "$SCRIPT_DIR/backup_rules_check.py" --check-manifest --require-both "$MAIN_MANIFEST"
for m in "$ROOT"/app/src/*/AndroidManifest.xml; do
    case "$m" in "$ROOT/app/src/main/AndroidManifest.xml") continue ;; esac
    [ -f "$m" ] || fail "missing manifest: $m"
    python3 "$SCRIPT_DIR/backup_rules_check.py" --check-manifest "$m"
done
# NSC 綁定縱深（attr↔resource）：上方的 source 級深掃只驗證了「存在的 NSC 檔」，
# 但 merged manifest 若把 android:networkSecurityConfig rebind 到一個未被掃到的
# 資源（或屬性被整段刪掉），release 就直接落回 platform 預設（允許明文）——
# 檔案本身再乾淨也攔不到。故主 manifest 必須把屬性釘在
# @xml/network_security_config（require-both：缺屬性即 fail），overlay「有設則
# 必須等值」。校驗語義（merger markers、quote-tolerant）由 backup_rules_check
# .validate_manifest 以屬性集參數統一實作，nsc_check.py 只傳入自己的綁定。
# NSC 綁定 loop 同樣 fail-closed：缺 overlay manifest 即 FAIL，不得靜默略過。
python3 "$SCRIPT_DIR/nsc_check.py" --check-manifest --require-both "$MAIN_MANIFEST"
for m in "$ROOT"/app/src/*/AndroidManifest.xml; do
    case "$m" in "$ROOT/app/src/main/AndroidManifest.xml") continue ;; esac
    [ -f "$m" ] || fail "missing manifest: $m"
    python3 "$SCRIPT_DIR/nsc_check.py" --check-manifest "$m"
done

# 縱深：掃描 app/src 下所有語意同形資源（檔名尾段含 backup*rules /
# data_extraction*rules；P2-1 深掃再吞 dash 拼法——data-extraction-rules.xml
# 這種下劃線↔連字號別名過去的精確比對會漏掃，等於 overlay 以 dash 別名新增
# backup rules 時整段驗證可被跳過）。main 兩檔已由上方固定路徑檢查，此處
# 重複驗證無害。find 失敗不可靜默：POSIX 管道無 pipefail，assignment 的狀態
# 取自最後一個管段（sort），故 find 不得與 sort 同管——先以非管道代入取
# find 自身狀態（fail-closed），再由近不會失敗的 printf|sort 做排序。
# 如實記錄清單邊界：這份別名集合概不含「無分隔符全連寫」形——
# dataextractionrules.xml 不命中任何 -name 碎片（對照 NSC 側的
# *network*security* 連 networksecurityconfig.xml 都會命中）。被清單漏掉的
# 拼法若真被 runtime 綁定，由 manifest 精確綁定 pin 兜底：本門的
# --check-manifest「有設則必須等值」只認 @xml/backup_rules /
# @xml/data_extraction_rules 字面值，rebind 別名一律 named fail；main 面另
# 由固定路徑 --expect-root 全值驗證把關。
BACKUP_RULES_LIST="$(find "$ROOT/app/src" -type f \( -name '*backup*rules*.xml' -o -name '*data_extraction*rules*.xml' -o -name '*data-extraction*rules*.xml' \) )" ||
    fail 'deep-scan of backup rules resources failed'
BACKUP_RULES_FILES="$(printf '%s\n' "$BACKUP_RULES_LIST" | sort)"
while IFS= read -r f; do
    [ -n "$f" ] || continue
    # 歸屬分派為 case first-match：每個 arm 假設檔名拼字只屬「單一」語意家族。
    # 混合拼法（例 backup_rules-data-extraction.xml 同時命中 *backup*rules* 與
    # *data-extraction*rules*）會落在第一個 arm、以 full-backup-content root
    # 為判準——誤判屬 fail-closed 方向（root 不符必 FAIL，不死放行），但 FAIL
    # 訊息不指向真因。混合命名視為一種「新規範」：需要時必須在此明確新增
    # dedicated arm，不得依賴隱式 first-match 語意。
    case "$f" in
        *backup*rules*.xml) broot=full-backup-content ;;
        *data_extraction*rules*.xml) broot=data-extraction-rules ;;
        *data-extraction*rules*.xml) broot=data-extraction-rules ;;
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

find_dexdump() {
    # Dexdump-only discovery for the --aapt override lane: aapt comes from the
    # caller there, so demanding a DISCOVERED aapt just to accept the override
    # would reject a healthy environment (e.g. an SDK layout carrying only a
    # dexdump next to the caller-forwarded aapt) over a tool the override
    # already bounds. Same probing order, IFS/newline discipline, and
    # version-sort preference as find_tool_pair above - only the required
    # executable set narrows to dexdump.
    for sdk in "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        [ -n "$sdk" ] || continue
        if [ -d "$sdk/build-tools" ]; then
            candidates=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V -r || true)
            _old_ifs=$IFS
            IFS='
'
            set -f
            for candidate in $candidates; do
                if [ -x "$candidate/dexdump" ]; then
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
    if command -v dexdump >/dev/null 2>&1; then
        DEXDUMP=$(command -v dexdump)
        return 0
    fi
    return 1
}

if [ -n "$AAPT_OVERRIDE" ]; then
    # Override lane: aapt is the caller-validated path (same bytes, same ruler
    # as the release verifier); dexdump is still the gate's responsibility.
    AAPT=$AAPT_OVERRIDE
    if ! find_dexdump; then
        fail 'required Android build-tools dexdump was not found (set ANDROID_HOME)'
    fi
else
    if ! find_tool_pair; then
        fail 'required Android build-tools aapt and dexdump were not both found (set ANDROID_HOME)'
    fi
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
