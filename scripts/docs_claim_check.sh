#!/usr/bin/env bash
# docs_claim_check.sh — fail-closed guard that docs match checked-in CI/toolchain truth.
#
# Checks (grep/sed + python3 with PyYAML for workflow YAML validity; no builds):
#   A. Every :app: Gradle task and every scripts/[subdir/]name.(sh|py) path
#      referenced anywhere in pr-check.yml (run steps and path-filter lists
#      alike) is documented in docs/TESTING.md (and vice versa for tasks:
#      no :app: task documented in TESTING.md that CI does not run).
#   B. docs/ENV.md version table matches gradle/libs.versions.toml,
#      app/build.gradle.kts, gradle-wrapper.properties, and the workflow JDK.
#   C. MCP zero-wiring (fail-closed surfaces, Stage4 D-01 / Stage6):
#      1. AndroidManifest registration: no AndroidManifest*.xml anywhere in
#         the repo registers a dev.librepocket.mcp component. Parsed as real
#         XML and matched element/attribute-aware: component `name`
#         attributes and the component-reference attributes targetActivity/
#         parentActivityName/backupAgent/manageSpaceActivity/
#         appComponentFactory get FQN/Mcp token/placeholder/relative-basename
#         checks; other elements' `name` (meta-data, action, category…) get
#         the token check only. <manifest package="dev.librepocket.mcp…">
#         fails. Placeholders mentioning MCP fail closed; parse errors fail
#         closed. A text grep (FQN boundary / Mcp[A-Z]) remains as
#         defense-in-depth (comments still turn it red — accepted cost);
#      2. Gradle manifest injection: no `manifestPlaceholders` /
#         `add|set|getManifestPlaceholders` in ANY gradle file alongside an
#         MCP literal in ANY gradle file (set-level co-occurrence, so a root
#         extra cannot hide it), no `manifest.srcFile` or getter
#         `getManifest().srcFile|setSrcFile`, no namespace/applicationId/
#         setNamespace/setApplicationId set to dev.librepocket.mcp, and no
#         MCP literal in any gradle.properties (all decoded through JLS
#         `\uXXXX` escapes first);
#      3. in-package outbound import allowlist / FQN: inside the MCP package
#         — classified by the UNIQUE real `package` declaration after a
#         recursive Kotlin/Java lexer strips comments/strings/char literals
#         and resolves `${…}` templates (a forged line in a block comment, a
#         raw string or a string template cannot count; BOM ignored), not by
#         directory path; package segments tolerate whitespace/comments/
#         backticks; subpackages dev.librepocket.mcp.* are in-package; more
#         than one declaration fails closed — the ONLY allowed
#         dev.librepocket.* references are the import allowlist
#         (keystore.KeyVault + the 7 tool contract types), the package decl,
#         self-mentions, and KDoc links ([dev.librepocket.…]) — any other
#         import (incl. star imports) or fully-qualified outbound use fails;
#      4. whole-module package-classification outside references: no
#         production source file OUTSIDE the MCP package (same package-based
#         split; every module's production source roots — main + flavors +
#         debug, Kotlin and Java, excluding test*/androidTest*) references
#         the mcp package or any Mcp* type. Scanned by a Python normaliser
#         (backticks, single-line /*…*/ and // comments, whitespace around
#         dots) rather than a literal grep; multi-line block comments are a
#         documented residual. Java is lexed after JLS Unicode escape
#         decoding; an escape-obscured MCP reference is reported by the ESC
#         pre-scan (which masks only the package header, never the whole
#         line) and fails closed;
#      5. discovery/classification fail-closed: no src roots / no manifests /
#         no in-package sources / unreadable or ambiguous package
#         declarations / grep or find errors ⇒ fail, never silent pass.
#         Merged-manifest output, string-concatenated gradle literals,
#         arbitrary dynamic property resolution and dynamic/reflective wiring
#         are outside this text guard's evidence: a green CI run does not
#         prove they are absent. Symlinks are not followed (`find -type f`).
#
# Fails closed: any mismatch prints a message and exits non-zero.
# Self-test: scripts/test_docs_claim_check.sh (fixture repos per wiring vector
# and per discovery fail-closed path).
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

# --- A0. Every checked-in workflow must be valid YAML and use # comments. ---
# A syntax error silently disables a workflow (GitHub renames it to the file
# path and stops triggering), so this fails closed before that reaches main.
# Glob is $ROOT-anchored (Stage4 D-05): a CWD-relative glob silently skipped
# this whole section whenever the script was invoked from another directory.
for wf in "$ROOT"/.github/workflows/*.yml; do
  [ -f "$wf" ] || continue
  if ! python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$wf" >/dev/null 2>&1; then
    report "${wf#"$ROOT"/} is not valid YAML (workflow would be disabled)"
  fi
  if grep -nE '^[[:space:]]*//' "$wf" >/dev/null 2>&1; then
    report "${wf#"$ROOT"/} uses // line comments (YAML only accepts #)"
  fi
done

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
# the guard's own existence). Stage4 D-05: .py scripts count as well as .sh;
# P3-2: the regex covers one level of subdirectory (scripts/tests/*.py — the
# workflow references them as literal paths in run: lines and path filters).
# Forward check only — the reverse direction would false-fail local-only
# scripts such as build_release.sh that CI deliberately never runs.
mapfile -t WF_SCRIPTS < <(grep -oE 'scripts/([A-Za-z0-9_]+/)?[A-Za-z0-9_]+\.(sh|py)' "$WORKFLOW" | sort -u || true)
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

# --- C. MCP zero-wiring (not-wired invariant) — five fail-closed surfaces:
#   1. AndroidManifest registration (text grep + real XML parse, C2);
#   2. Gradle manifest injection (placeholders / srcFile / namespace /
#      applicationId / gradle.properties, C2b);
#   3. in-package outbound import allowlist / FQN (C3);
#   4. whole-module package-classification outside references (C4);
#   5. discovery/classification fail-closed (C1/C1b + each surface above).
# (C1/C1b discovery+classification, C2 manifest, C2b gradle, C3 in-package,
# C4 outside) ---
# MCP (package dev.librepocket.mcp) is declared+implemented but deliberately
# NOT wired: no production code outside its own package may reference it, no
# manifest may register it, and the package itself may only depend outward on
# the allowlisted contract types. It has JVM unit tests only (no device run),
# and every MCP tool stays behind the executionReady=false fail-closed double
# gate. A wiring PR must flip tools per the acceptance checklist in
# docs/CAPABILITY_MATRIX.md AND deliberately widen this gate — never widen it
# as a side effect (Stage4 D-01: the old single grep let a manifest component,
# an in-package outbound import, or a brand-new module slip through).

# --- C1. Discovery: every production source root of every module (fail-closed
# on find errors or an empty result — parser drift must not silently pass). ---
if ! SRC_LIST="$(find "$ROOT" -type d -name src \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' \
  -not -path '*/node_modules/*' 2>&1)"; then
  report "find over source roots failed (fail closed): $SRC_LIST"
  SRC_LIST=""
fi
SRC_DIRS=()
while IFS= read -r d; do
  [ -n "$d" ] && SRC_DIRS+=("$d")
done <<< "$SRC_LIST"
if [ "${#SRC_DIRS[@]}" -eq 0 ]; then
  report "no */src source roots found under repo root (parser drift?) — MCP scan cannot run (fail closed)"
fi

# Production source files of every module: Kotlin/Java, excluding only the
# test*/androidTest* source sets that legitimately reference MCP (flavor code
# ships in the APK, so a Mcp* reference there is production wiring too).
PROD_FILES=()
if [ "${#SRC_DIRS[@]}" -gt 0 ]; then
  if ! PROD_LIST="$(find "${SRC_DIRS[@]}" -type f \( -name '*.kt' -o -name '*.java' \) \
    -not -path '*/src/test*' -not -path '*/src/androidTest*' 2>&1)"; then
    report "find over production sources failed (fail closed): $PROD_LIST"
    PROD_LIST=""
  fi
  while IFS= read -r f; do
    [ -n "$f" ] && PROD_FILES+=("$f")
  done <<< "$PROD_LIST"
fi
if [ "${#PROD_FILES[@]}" -eq 0 ]; then
  report "no production Kotlin/Java sources found (parser drift?) — MCP scan cannot run (fail closed)"
fi

# --- C1b. Package-based classification (Stage6 B, hardened in Stage6 fix2
# and fix3): the `package` declaration is the single source of truth, NOT the
# directory path. A file at `com/example/dev/librepocket/mcp/Sneaky.kt` with
# `package com.example` is outside the MCP package and must be scanned by C4;
# a file declaring `package dev.librepocket.mcp` (or a subpackage such as
# `dev.librepocket.mcp.transport`) is in-package even from another directory.
#
# The declaration is extracted by a python3 stdlib recursive lexer that strips
# comments and string/char literals, so a literal `package dev.librepocket.mcp`
# inside a block comment or a raw string cannot forge the classification:
#   - `.kt` block comments nest, `.java` ones do not;
#   - `//`, `/* … */`, `"…"` (backslash escapes), `"""…"""` (Kotlin raw /
#     Java text block, trailing extra quotes consumed) and `'…'` are removed
#     (newlines preserved); an unterminated line literal stops at the newline
#     (fail-safe — it must not swallow the rest of the file);
#   - KOTLIN string templates `${…}` are lexed recursively as code (braces
#     tracked), so a nested `"""` inside a template cannot terminate the
#     enclosing string early and swallow a later real package declaration;
#     `$identifier` stays string content. Java lexes with templates disabled
#     (its text blocks are still recognised);
#   - `.java` is JLS-Unicode-escape decoded (`\uXXXX`, `\uuuu0041`; a
#     backslash is eligible only after an even run of backslashes; invalid
#     hex stays literal) BEFORE lexing, because javac translates escapes
#     before tokenising — an escaped `package`/comment must classify the same
#     way the compiler sees it;
#   - a leading UTF-8 BOM is ignored.
# Package segments may be separated by whitespace/newlines/comments and may
# use Kotlin backticks; the captured declaration is normalised (backticks and
# whitespace removed) before matching, so `package dev.librepocket./*x*/mcp`
# and `package dev . librepocket . mcp` classify like the canonical form.
# Exactly one declaration in dev.librepocket.mcp(.subpackage)* => IN; exactly
# one non-mcp declaration (or none: default package/unparsed) => OUT, left to
# C4; MORE THAN ONE declaration => BAD (forgery / ambiguity), fail closed.
# A read/decode failure is BAD too. Any BAD scans fails the gate but the other
# files keep their classification (partial results are retained, so C3/C4
# still report their own findings instead of a misleading all-OUT diagnostic).
# C2 (type names), C3 (outbound) and C4 (outside references) all use this
# split. If python3 is missing or the scan itself fails (rc>=2), the gate is
# already failed; IN/OUT may stay empty and C1's "no production source
# declares…" message is suppressed.
#
# The same Python pass also runs the ESC pre-scan: every `.java` file whose raw
# text contains `\u` is decoded and checked line by line. Only the package
# declaration HEADER up to its first `;` is masked (a same-line
# `package …; import …` remainder is still scanned; a declaration with no `;`
# consumes the line). A line still carrying `dev.librepocket.` or `Mcp[A-Z]`
# after decoding means a Java Unicode escape obscured an MCP-related
# reference, reported as `ESC\t<path>\t<lineno>\t<text>` and failed closed.
MCP_FQN_BOUNDARY='dev\.librepocket\.mcp([^A-Za-z0-9_]|$)|Mcp[A-Z]'
IN_PKG_FILES=()
OUTSIDE_FILES=()
PKG_SCAN_FAILED=0
if [ "${#PROD_FILES[@]}" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    report "python3 not available for package classification (fail closed)"
    PKG_SCAN_FAILED=1
  else
    set +e
    PKG_TSV="$(python3 - "$ROOT" "${PROD_FILES[@]}" <<'PY'
import os
import re
import sys

root = sys.argv[1]
files = sys.argv[2:]

FQN_RE = re.compile(r"^dev\.librepocket\.mcp(\.[A-Za-z_][A-Za-z0-9_]*)*$")
PKG_DECL = re.compile(
    r"(?<![A-Za-z0-9_$`])package\s+"
    r"((?:`?[A-Za-z_][A-Za-z0-9_]*`?\s*\.\s*)*"
    r"`?[A-Za-z_][A-Za-z0-9_]*`?)")
PKG_LINE = re.compile(r"^\s*package\b")
ESC_MCP = re.compile(r"dev\.librepocket\.|Mcp[A-Z]")


def rel(path):
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


def blank(segment):
    # Preserve newlines so diagnostics and any later line logic stay aligned.
    return "".join(ch if ch == "\n" else " " for ch in segment)


def java_unicode_decode(text):
    # JLS 3.3: translate \\uXXXX before lexing. A backslash is eligible only
    # when preceded by an even number of contiguous backslashes; \\uuuu0041
    # (one or more u) is accepted; invalid hex stays literal.
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] != "\\":
            out.append(text[i])
            i += 1
            continue
        j = i
        while j < n and text[j] == "\\":
            j += 1
        k = i
        while k < j:
            if ((k - i) % 2 == 0 and text.startswith("\\u", k)):
                m = k + 2
                while m < n and text[m] == "u":
                    m += 1
                digits = text[m:m + 4]
                if (len(digits) == 4 and
                        all(c in "0123456789abcdefABCDEF" for c in digits)):
                    out.append(chr(int(digits, 16)))
                    k = m + 4
                    continue
            out.append(text[k])
            k += 1
        i = k
    return "".join(out)


def scan_string(text, i, nested, raw, templates):
    n = len(text)
    if raw:
        # Kotlin raw string / Java text block: scan to the next triple quote
        # (outside a ${…} template), then consume any extra trailing quotes.
        # Newlines are preserved. Java passes templates=False, so a `$` stays
        # plain text there.
        out = []
        j = i + 3
        start = j
        while j < n:
            # Java text blocks are NOT raw: `\"` is an escaped quote, so the
            # quote sequence in `\"""` is not a closing delimiter. Without
            # this, the lexer stops at the escaped quote, treats the rest of
            # the text block as code (a forged `package` can land there) and
            # can then swallow the real package inside the next `"""`.
            # Kotlin raw strings have no escapes (templates=False is Java).
            if not templates and text[j] == "\\" and j + 1 < n:
                j += 2
                continue
            if text.startswith('"""', j):
                j += 3
                while j < n and text[j] == '"':
                    j += 1
                out.append(blank(text[start:j]))
                return j, "".join(out)
            if templates and text.startswith("${", j):
                out.append(blank(text[start:j]))
                out.append("  ")
                j += 2
                j, inner, _ = scan_code(text, j, nested, stop_brace=True)
                out.append(inner)
                if j < n and text[j] == "}":
                    out.append(" ")
                    j += 1
                start = j
                continue
            j += 1
        out.append(blank(text[start:j]))
        return j, "".join(out)
    j = i + 1
    out = [" "]
    start = j
    while j < n:
        c = text[j]
        if c == "\\" and j + 1 < n and text[j + 1] != "\n":
            j += 2
            continue
        if templates and c == "$" and j + 1 < n and text[j + 1] == "{":
            # Kotlin template: the body is real code, lex it recursively so
            # nested strings/comments cannot desynchronise the outer string.
            out.append(blank(text[start:j]))
            out.append("  ")
            j += 2
            j, inner, _ = scan_code(text, j, nested, stop_brace=True)
            out.append(inner)
            if j < n and text[j] == "}":
                out.append(" ")
                j += 1
            start = j
            continue
        if c == '"':
            j += 1
            out.append(blank(text[start:j]))
            return j, "".join(out)
        if c == "\n":
            # Unterminated line literal: stop at the newline and leave it for
            # scan_code so line structure is preserved.
            out.append(blank(text[start:j]))
            return j, "".join(out)
        j += 1
    out.append(blank(text[start:j]))
    return j, "".join(out)


def scan_code(text, i, nested, stop_brace=False):
    # Remove comments and string/char literals, preserving newlines. Kotlin
    # block comments nest; Java's do not. With stop_brace (template body),
    # track `{`/`}` depth and return at the depth-0 `}` (unconsumed).
    out = []
    depth = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "/" and text.startswith("//", i):
            j = text.find("\n", i)
            if j == -1:
                j = n
            out.append(blank(text[i:j]))
            i = j
        elif ch == "/" and text.startswith("/*", i):
            level = 1
            j = i + 2
            while j < n and level:
                if nested and text.startswith("/*", j):
                    level += 1
                    j += 2
                elif text.startswith("*/", j):
                    level -= 1
                    j += 2
                else:
                    j += 1
            out.append(blank(text[i:j]))
            i = j
        elif ch == '"':
            i, segment = scan_string(
                text, i, nested, text.startswith('"""', i), nested)
            out.append(segment)
        elif ch == "`":
            # Kotlin backtick identifier. Lex the whole quoted token so its
            # text can never forge a package declaration:
            #   - a plain identifier keeps its content (and delimiters, which
            #     PKG_DECL tolerates) so `package `dev`.librepocket.mcp` still
            #     classifies;
            #   - anything else (spaces, `package …` prose, an unterminated
            #     backtick) collapses to `_BT_`, so `val `package dev…` = 1`
            #     cannot count as a declaration. Newlines are preserved to keep
            #     diagnostics aligned.
            j = text.find("`", i + 1)
            if j == -1:
                out.append("_BT_" + blank(text[i + 1:]))
                i = n
                continue
            content = text[i + 1:j]
            if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", content):
                out.append(text[i:j + 1])
            else:
                out.append("_BT_" + "".join(
                    "\n" for c in content if c == "\n"))
            i = j + 1
        elif ch == "'":
            j = i + 1
            while j < n:
                c = text[j]
                if c == "\\" and j + 1 < n and text[j + 1] != "\n":
                    j += 2
                    continue
                if c == "'" or c == "\n":
                    if c == "'":
                        j += 1
                    break
                j += 1
            out.append(blank(text[i:j]))
            i = j
        elif stop_brace and ch == "{":
            depth += 1
            out.append(ch)
            i += 1
        elif stop_brace and ch == "}":
            if depth == 0:
                return i, "".join(out), depth
            depth -= 1
            out.append(ch)
            i += 1
        else:
            out.append(ch)
            i += 1
    return i, "".join(out), depth


def read_text(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    if text.startswith("\ufeff"):
        text = text[1:]
    return text


def classify(text, is_java):
    nested = not is_java
    _, code, _ = scan_code(text, 0, nested)
    decls = [re.sub(r"[`\s]", "", d) for d in PKG_DECL.findall(code)]
    if len(decls) > 1:
        return ("BAD", "multiple package declarations")
    if not decls:
        return ("OUT", "")
    if FQN_RE.match(decls[0]):
        return ("IN", "")
    return ("OUT", "")


def main():
    for path in files:
        try:
            text = read_text(path)
        except Exception as exc:
            print("\t".join(("BAD", path, f"read error: {exc}")))
            continue
        is_java = path.endswith(".java")
        if is_java:
            decoded = java_unicode_decode(text)
            if "\\u" in text:
                for lineno, line in enumerate(decoded.splitlines(), 1):
                    # Mask only the package declaration HEADER (up to its
                    # first `;`), never the whole physical line: Java allows
                    # `package a; import b;` on one line, and skipping the
                    # entire line would hide an escape-obscured reference in
                    # the remainder. A declaration with no `;` consumes the
                    # line (Kotlin-style / unterminated).
                    scan_line = line
                    if PKG_LINE.match(scan_line):
                        semi = scan_line.find(";")
                        if semi == -1:
                            continue
                        scan_line = scan_line[semi + 1:]
                    if ESC_MCP.search(scan_line):
                        print("\t".join(
                            ("ESC", path, str(lineno), line.strip())))
        else:
            decoded = text
        kind, reason = classify(decoded, is_java)
        print("\t".join((kind, path, reason)))
    return 0


try:
    sys.exit(main())
except Exception as exc:
    print(f"package classification crashed (fail closed): {exc}")
    sys.exit(2)
PY
)"
    PKG_RC=$?
    set -e
    if [ "$PKG_RC" -ge 2 ]; then
      report "package classification failed (fail closed, rc=$PKG_RC)"
      PKG_SCAN_FAILED=1
    fi
    PKG_PATHS=""
    while IFS=$'\t' read -r kind path reason extra; do
      [ -n "$kind" ] || continue
      case "$kind" in
        ESC)
          report "Java Unicode escape obscures MCP-related reference (fail closed): ${path#"$ROOT"/}:$reason: $extra"
          ;;
        IN|OUT|BAD)
          PKG_PATHS+="$path"$'\n'
          case "$kind" in
            IN) IN_PKG_FILES+=("$path") ;;
            OUT) OUTSIDE_FILES+=("$path") ;;
            BAD)
              report "package classification failed (fail closed): ${path#"$ROOT"/}: $reason"
              PKG_SCAN_FAILED=1
              ;;
          esac
          ;;
        *)
          report "package classification returned unknown kind '$kind' (fail closed): ${path#"$ROOT"/}"
          PKG_SCAN_FAILED=1
          ;;
      esac
    done <<< "$PKG_TSV"
    # Every production file must be classified exactly once. A parser crash
    # or a partial stream must not silently drop files from C3/C4.
    for f in "${PROD_FILES[@]}"; do
      seen="$(printf '%s' "$PKG_PATHS" | grep -Fxc -- "$f" || true)"
      seen="${seen:-0}"
      if [ "$seen" -ne 1 ]; then
        report "package classification incomplete (fail closed): ${f#"$ROOT"/}"
        PKG_SCAN_FAILED=1
      fi
    done
  fi
fi
# Zero in-package files while production sources exist means the package
# declaration parser drifted (or every source moved) — fail closed. Zero
# OUTSIDE files is a legitimate state (single-package repo): C4 is a no-op.
# Suppressed when the scan itself failed: that failure is the real diagnostic.
if [ "${#IN_PKG_FILES[@]}" -eq 0 ] && [ "$PKG_SCAN_FAILED" -eq 0 ]; then
  report "no production source declares package dev.librepocket.mcp (parser drift?) — MCP package scan cannot run (fail closed)"
fi

# --- C2. Surface: AndroidManifest registration (any module, any flavor). ---
# N-B1 accepted cost: the bare `Mcp[A-Z]` substring is deliberately wide — a
# third-party string containing e.g. `McpCompat` also turns this red
# (fail-closed direction, not tightened: any token-boundary rule risks
# dropping a real `dev.librepocket.mcp.McpServer` reference mid-string).
# The FQN half of the text pattern carries a trailing boundary
# (`dev\.librepocket\.mcp([^A-Za-z0-9_]|$)`) so a lookalike package such as
# `dev.librepocket.mcpfoo` is no longer a false red. XML component checks keep
# the substring form (over-approximate, fail-closed).
#
# Two layers:
#   1. A text grep (FQN with trailing boundary / Mcp[A-Z]) over the raw
#      manifest files. It is
#      defense-in-depth: it also catches comments, though `xml.etree` does not
#      expose comments (documented accepted cost — a comment mentioning MCP
#      turns the gate red). Entity-encoded dots (&#46;) have no literal `.`,
#      so only layer 2 can catch them (pinned by a fixture).
#   2. A real XML parse (python3 stdlib only: no PyYAML, no new dependency).
#      This is the authoritative layer. It matches attributes by local-name
#      (namespace-prefix tolerant), so quote style, `=` whitespace, line
#      breaks and prefixes are handled by XML itself. Element/attribute
#      policy (Stage6 A1):
#        - `name` on a component element (application, activity,
#          activity-alias, service, receiver, provider, instrumentation,
#          uses-library, uses-native-library) gets the FULL rule set below;
#        - the component-reference attributes targetActivity,
#          parentActivityName, backupAgent, manageSpaceActivity and
#          appComponentFactory get the same FULL rule set on any element;
#        - `name` on any other element (meta-data, action, category, …) gets
#          ONLY the FQN / `Mcp[A-Z]` token check, never basename resolution
#          (`<meta-data android:name="Transport">` is data, not a component);
#        - <manifest package="…"> fails when equal to dev.librepocket.mcp or
#          a dot-prefixed subpackage (bare `dev.librepocket.mcpfoo` is not
#          the MCP package), and when it carries an MCP-mentioning `${...}`
#          placeholder (e.g. `${mcpPkg}`); `${applicationId}` never fails.
#      FULL rule set (fail closed):
#        - value contains `dev.librepocket.mcp` or matches `Mcp[A-Z]`;
#        - value contains a `${...}` placeholder whose text (case-insensitive)
#          mentions mcp (could expand into the mcp package), while ordinary
#          placeholders such as `${applicationId}` never fail;
#        - value starts with `.` => last segment (basename) is compared with
#          the package-based name set; value with no dot at all (e.g.
#          `Transport`) => same check (Android's unqualified resolution is
#          treated conservatively); a `$`-bearing basename also compares the
#          segment after `$` (`.mcp.Outer$Inner` → `Inner`).
#      The name set is built from IN_PKG_FILES (C1b, package declaration):
#      each file's basename plus every declared type name (`class`, `object`,
#      `interface`, `enum`/`enum class`, `record`, `typealias`, incl.
#      data/sealed/value/annotation class forms and backticked Kotlin names
#      such as class `Transport`). This catches a manifest registering
#      `Transport` when `class Transport` lives in a differently named file,
#      and mirrors C3/C4's package-based truth. TYPE_DECL is
#      deliberately over-approximate: `enum`/`record`/`typealias` cannot be
#      manifest components, but matching them only turns the gate red
#      (fail-closed, never green).
#      The name set is a package-agnostic basename set: a same-named
#      component outside dev.librepocket.mcp also turns red — deliberate
#      conservatism (C2 does not resolve the manifest's effective package).
#      Known limits (deliberate, not widened): `find -type f` does not follow
#      symlinks, and the merged manifest is not inspected.
#        - unreadable/invalid XML, or python3 unavailable => fail.
#          (defence in depth; A0/A already require python3)
if ! MF_LIST="$(find "$ROOT" -type f -name 'AndroidManifest*.xml' \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' 2>&1)"; then
  report "find over manifests failed (fail closed): $MF_LIST"
  MF_LIST=""
fi
MANIFESTS=()
while IFS= read -r f; do
  [ -n "$f" ] && MANIFESTS+=("$f")
done <<< "$MF_LIST"
if [ "${#MANIFESTS[@]}" -eq 0 ]; then
  report "no AndroidManifest*.xml found (parser drift?) — MCP manifest scan cannot run (fail closed)"
else
  set +e
  MF_HITS="$(grep -HnE "$MCP_FQN_BOUNDARY" "${MANIFESTS[@]}" 2>&1)"
  MF_RC=$?
  set -e
  if [ "$MF_RC" -ge 2 ]; then
    report "manifest grep failed (fail closed, rc=$MF_RC): $MF_HITS"
  elif [ "$MF_RC" -eq 0 ]; then
    report "Android manifest registers MCP (MCP must stay unwired until the wiring PR updates this gate):"
    printf '%s\n' "$MF_HITS"
  fi

  # Authoritative XML layer (Stage6 A/B). IN_PKG_FILES was classified by C1b
  # via the package declaration; the parser adds declared type names to the
  # basename set. Anything the parser cannot do fails closed; its stdout lines
  # are forwarded to `report` verbatim.
  if ! command -v python3 >/dev/null 2>&1; then
    # defence in depth; A0/A already require python3
    report "python3 not available for AndroidManifest XML parsing (fail closed)"
  else
    set +e
    MF_XML_OUT="$(python3 - "$ROOT" "${#IN_PKG_FILES[@]}" \
      ${IN_PKG_FILES[@]+"${IN_PKG_FILES[@]}"} "${MANIFESTS[@]}" <<'PY'
import os
import re
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
n_in = int(sys.argv[2])
in_files = sys.argv[3:3 + n_in]
manifests = sys.argv[3 + n_in:]

FQN = "dev.librepocket.mcp"
MCP_TYPE = re.compile(r"Mcp[A-Z]")
# Deliberately over-approximate: enum/record/typealias cannot be manifest
# components, but matching extra names only turns the gate red (fail-closed).
TYPE_DECL = re.compile(
    r"\b(?:enum\s+class|enum|class|object|interface|record|typealias)"
    # Kotlin permits backticked type names (e.g. class `Transport`), and the
    # manifest refers to the JVM name without the backticks.
    r"\s+`?([A-Za-z_][A-Za-z0-9_$]*)`?")

# Only these element names carry a component class in `name`. Everything else
# (meta-data, action, category, uses-permission…) holds data, not a component.
COMPONENT_ELEMENTS = {
    "application", "activity", "activity-alias", "service", "receiver",
    "provider", "instrumentation", "uses-library", "uses-native-library",
}
# Attributes whose value is a component class reference on any element.
COMPONENT_REF_ATTRS = {
    "targetActivity", "parentActivityName", "backupAgent",
    "manageSpaceActivity", "appComponentFactory",
}

names = set()


def rel(path):
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


def local_name(name):
    # `{uri}name` and plain `name` both reduce to `name`.
    return name.rsplit("}", 1)[-1]


def package_names():
    # Package-based (not path-based): in_files are production sources whose
    # package declaration is dev.librepocket.mcp(.…). Collect file basenames
    # plus every declared type (class/object/interface/enum/record/typealias
    # and modifiers such as data/sealed/value, which still carry `class`).
    found = set()
    for path in in_files:
        found.add(os.path.basename(path).rsplit(".", 1)[0])
        with open(path, encoding="utf-8", errors="replace") as handle:
            found.update(TYPE_DECL.findall(handle.read()))
    return found


def relative_candidates(value):
    if value.startswith("."):
        base = value.rsplit(".", 1)[-1]
    elif "." not in value:
        base = value
    else:
        return []
    candidates = [base]
    if "$" in base:
        tail = base.rsplit("$", 1)[-1]
        if tail and tail != base:
            candidates.append(tail)
    return [c for c in candidates if c]


def full_reason(value):
    # FULL rule set: used for component names and component-reference attrs.
    if FQN in value or MCP_TYPE.search(value):
        return "Android manifest registers MCP"
    if "${" in value and "mcp" in value.lower():
        return ("Android manifest registers MCP (placeholder component "
                f"{value} could resolve to the mcp package; fail closed)")
    for base in relative_candidates(value):
        if base in names:
            return ("Android manifest registers MCP "
                    f"(relative component .{base})")
    return None


def main():
    global names
    try:
        names = package_names()
    except Exception as exc:
        print("cannot enumerate dev/librepocket/mcp/ production types "
              f"(fail closed): {exc}")
        return 2

    failed = False
    for manifest in manifests:
        try:
            tree = ET.parse(manifest)
        except Exception as exc:
            print("cannot parse AndroidManifest XML (fail closed): "
                  f"{rel(manifest)}: {exc}")
            failed = True
            continue
        for element in tree.iter():
            tag = local_name(element.tag)
            for attr, raw_value in element.attrib.items():
                attribute = local_name(attr)
                value = raw_value.strip()
                if tag == "manifest" and attribute == "package":
                    if value == FQN or value.startswith(FQN + "."):
                        print("Android manifest declares MCP package: "
                              f"{rel(manifest)} package={value}")
                        failed = True
                    elif "${" in value and "mcp" in value.lower():
                        print("Android manifest declares MCP package "
                              f"(placeholder {value} could resolve to the mcp "
                              f"package; fail closed): {rel(manifest)} "
                              f"package={value}")
                        failed = True
                    continue
                reason = None
                if attribute == "name":
                    if tag in COMPONENT_ELEMENTS:
                        reason = full_reason(value)
                    elif FQN in value or MCP_TYPE.search(value):
                        # Non-component name: token check only. A bare
                        # basename (e.g. meta-data "Transport") is data.
                        # redundant with the C2 text-grep layer; do not remove
                        # both
                        reason = "Android manifest registers MCP"
                elif attribute in COMPONENT_REF_ATTRS:
                    reason = full_reason(value)
                if reason:
                    print(f"{reason}: {rel(manifest)} "
                          f"{tag}/{attribute}={value}")
                    failed = True
    return 1 if failed else 0


try:
    sys.exit(main())
except Exception as exc:
    print(f"AndroidManifest XML scan crashed (fail closed): {exc}")
    sys.exit(2)
PY
)"
    MF_XML_RC=$?
    set -e
    if [ "$MF_XML_RC" -ge 2 ]; then
      report "AndroidManifest XML scan failed (fail closed, rc=$MF_XML_RC)"
      [ -n "$MF_XML_OUT" ] && printf '%s\n' "$MF_XML_OUT"
    elif [ "$MF_XML_RC" -eq 1 ]; then
      while IFS= read -r msg; do
        [ -n "$msg" ] || continue
        report "$msg"
      done <<< "$MF_XML_OUT"
    fi
  fi
fi

# --- C2b. Surface: Gradle build-time manifest injection (Stage6 A3, S2,
# fix3). A component can be injected, or the manifest swapped wholesale,
# without any MCP token in AndroidManifest*.xml — the XML scan above would
# stay green. Fail closed on (one Python pass over every gradle file and
# gradle.properties; Unicode `\uXXXX` escapes are decoded first because
# Kotlin/Groovy/properties all support them):
#   - any standalone MCP FQN / `Mcp[A-Z]` literal in ANY gradle file fails
#     closed. A literal can feed a namespace/applicationId placeholder or an
#     otherwise dynamic property even when it is not adjacent to a known API
#     (e.g. rootProject.extra["pkg"] = "dev.librepocket.mcp" consumed by the
#     app build script). `manifestPlaceholders` (case-insensitive; covers
#     add/set/getManifestPlaceholders and getManifestPlaceholders().put) with
#     such a literal gets a specific diagnostic, but the literal alone is
#     already fatal;
#   - any `srcFile` / `setSrcFile` token in a scanned gradle file. AGP's real
#     API is the receiver form `manifest.srcFile(...)`, but an alias,
#     `.apply { srcFile(...) }`, a newline before the dot, or the defensive
#     `manifest.setSrcFile` spelling are all legal/possible; a token-only
#     rule is deliberately over-approximate (fail-closed); comments and
#     spaces around the dot are normalised before this check;
#   - namespace/applicationId (and setNamespace/setApplicationId) assigned
#     dev.librepocket.mcp, in KTS (`=`), Groovy (space-separated), slashy /
#     triple-quoted strings, parenthesized setter form, and Gradle Property
#     `.set`/`.convention`/`.value` forms, including a value on the next line
#     (`namespace =\n    "dev.librepocket.mcp"`) or with comments between the
#     keyword and `=`; the trailing boundary keeps `dev.librepocket.mcpfoo`
#     green;
#   - an MCP FQN / `Mcp[A-Z]` literal in any gradle.properties (a property
#     consumed via project.property(...) is an indirect wiring surface).
# Comment/space normalisation uses a second view of the same text (newlines
# preserved for diagnostics); the raw view is still scanned so a token that
# only appears in a comment remains fail-closed.
# Discovery is `find` over every gradle file and gradle.properties
# (build/.gradle/.git excluded) — a superset of git-tracked files, so an
# untracked local injection also fails closed. Find/read/parse errors fail
# closed. Residual: string-concatenation-built gradle literals
# (`"dev.librepocket." + "mcp."`), non-literal dynamic property resolution
# (environment/system property with no MCP literal anywhere in a scanned
# gradle/properties file), and injection expressed only in buildSrc or a
# convention plugin `.kt` file stay outside this text guard.
if ! GRADLE_LIST="$(find "$ROOT" -type f \( -name '*.gradle' -o -name '*.gradle.kts' \) \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' 2>&1)"; then
  report "find over gradle files failed (fail closed): $GRADLE_LIST"
  GRADLE_LIST=""
fi
GRADLE_FILES=()
while IFS= read -r f; do
  [ -n "$f" ] && GRADLE_FILES+=("$f")
done <<< "$GRADLE_LIST"

if ! GRP_LIST="$(find "$ROOT" -type f -name 'gradle.properties' \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' 2>&1)"; then
  report "find over gradle.properties failed (fail closed): $GRP_LIST"
  GRP_LIST=""
fi
PROPERTIES_FILES=()
while IFS= read -r f; do
  [ -n "$f" ] && PROPERTIES_FILES+=("$f")
done <<< "$GRP_LIST"

if [ "${#GRADLE_FILES[@]}" -gt 0 ] || [ "${#PROPERTIES_FILES[@]}" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    report "python3 not available for gradle injection scan (fail closed)"
  else
    set +e
    GR_OUT="$(python3 - "$ROOT" "${#GRADLE_FILES[@]}" \
      ${GRADLE_FILES[@]+"${GRADLE_FILES[@]}"} \
      "${#PROPERTIES_FILES[@]}" \
      ${PROPERTIES_FILES[@]+"${PROPERTIES_FILES[@]}"} <<'PY'
import os
import re
import sys

root = sys.argv[1]
n_gradle = int(sys.argv[2])
pos = 3
gradle_files = sys.argv[pos:pos + n_gradle]
pos += n_gradle
n_props = int(sys.argv[pos])
pos += 1
properties_files = sys.argv[pos:pos + n_props]

# Same boundary as the manifest/C4 text grep.
MCP = re.compile(r"dev\.librepocket\.mcp([^A-Za-z0-9_]|$)|Mcp[A-Z]")
# Match the srcFile/setSrcFile token regardless of receiver. AGP exposes
# `manifest.srcFile(...)`, but valid Kotlin can hide the receiver behind an
# alias, `.apply { srcFile(...) }`, or a newline before the dot; a token-only
# rule is deliberately over-approximate (fail-closed).
SRC = re.compile(r"\b(?:set)?srcfile\b", re.IGNORECASE)
NS = re.compile(
    r"(?<![A-Za-z0-9_])"
    r"(namespace|applicationid|setnamespace|setapplicationid)"
    # `namespace =`, Groovy `namespace "…"`, setter `setNamespace(…)`, and
    # Property forms `namespace.set/convention/value(…)`. Quote class covers
    # plain, triple-quoted (Kotlin/Groovy) and Groovy slashy strings.
    r"\s*(?:=\s*|\(\s*|\.\s*(?:set|convention|value)\s*\(\s*)?"
    r"[\"'/]{0,3}dev\.librepocket\.mcp"
    r"([^A-Za-z0-9_]|$)", re.IGNORECASE)
PH_TOKEN = "manifestplaceholders"

# Comment/whitespace normalisation view: strip `/*…*/` and `//…` (newlines
# preserved so diagnostics keep their line numbers), collapse spaces/tabs
# around dots, and normalise empty getter parens. This catches legal spellings
# such as `namespace /*x*/ = "…"`, `dev.librepocket./*x*/mcp`,
# `manifest . srcFile` and `getManifest ( ) . srcFile`; raw text is scanned
# too (fail-closed on tokens that only appear in comments).
COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)
DOTS = re.compile(r"[ \t]*\.[ \t]*")
EMPTY_PARENS = re.compile(r"\([ \t]*\)")


def strip_comments(text):
    def blank(match):
        return "".join("\n" if c == "\n" else " " for c in match.group(0))
    return COMMENTS.sub(blank, text)


def normalize(text):
    return EMPTY_PARENS.sub("()", DOTS.sub(".", strip_comments(text)))


def logical_properties_lines(text):
    """Join Java-properties `\\`-continued physical lines.

    Gradle uses java.util.Properties semantics: an odd number of trailing
    backslashes continues onto the next line, whose leading whitespace is
    dropped. Scanning physical lines separately lets
    `component=dev.librepocket.mc\\\n  p.McpServer` hide the literal.
    Returns (first_physical_line, joined_line) pairs.
    """
    result = []
    buf = ""
    start = 1
    for lineno, line in enumerate(text.splitlines(), 1):
        line = line.rstrip("\r")
        trailing = len(line) - len(line.rstrip("\\"))
        if not buf:
            start = lineno
        if trailing % 2 == 1:
            piece = line[:-1]
            buf += piece.lstrip() if buf else piece
            continue
        if buf:
            buf += line.lstrip()
            result.append((start, buf))
            buf = ""
        else:
            result.append((lineno, line))
    if buf:
        result.append((start, buf))
    return result


def rel(path):
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


def unicode_escape_decode(text):
    # Kotlin/Groovy/Java-properties `\uXXXX`. Only a backslash preceded by an
    # even run of contiguous backslashes is eligible; invalid hex is literal.
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] != "\\":
            out.append(text[i])
            i += 1
            continue
        j = i
        while j < n and text[j] == "\\":
            j += 1
        k = i
        while k < j:
            if ((k - i) % 2 == 0 and text.startswith("\\u", k)):
                m = k + 2
                while m < n and text[m] == "u":
                    m += 1
                digits = text[m:m + 4]
                if (len(digits) == 4 and
                        all(c in "0123456789abcdefABCDEF" for c in digits)):
                    out.append(chr(int(digits, 16)))
                    k = m + 4
                    continue
            out.append(text[k])
            k += 1
        i = k
    return "".join(out)


def read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def main():
    failed = False
    has_ph = False
    mcp_hits = []
    src_hits = []
    ns_hits = []
    prop_hits = []

    for path in gradle_files:
        try:
            text = unicode_escape_decode(read(path))
        except Exception as exc:
            print(f"cannot read gradle file (fail closed): {rel(path)}: {exc}")
            failed = True
            continue
        original_lines = text.splitlines()
        # Scan both the raw text (fail-closed on tokens in comments/strings)
        # and the comment/dot-normalised view (catches legal obfuscation such
        # as `namespace /*x*/ =` or `manifest . srcFile`). Line numbers and
        # the reported source line come from the original file.
        for view in (text, normalize(text)):
            for lineno, line in enumerate(view.splitlines(), 1):
                original = (original_lines[lineno - 1]
                            if lineno - 1 < len(original_lines) else line)
                where = f"{rel(path)}:{lineno}:{original}"
                if PH_TOKEN in line.lower():
                    has_ph = True
                if MCP.search(line) and where not in mcp_hits:
                    mcp_hits.append(where)
                if SRC.search(line) and where not in src_hits:
                    src_hits.append(where)
                if NS.search(line) and where not in ns_hits:
                    ns_hits.append(where)

            # Cross-line namespace/applicationId: the value may sit on the
            # next line (`namespace =\n    "dev.librepocket.mcp"`). NS uses
            # \s*, which already spans newlines; comments are blanked in the
            # normalised view, so `namespace = /*x*/\n "…"` is covered too.
            for match in NS.finditer(view):
                lineno = view.count("\n", 0, match.start()) + 1
                original = (original_lines[lineno - 1]
                            if lineno - 1 < len(original_lines) else "")
                where = f"{rel(path)}:{lineno}:{original}"
                if where not in ns_hits:
                    ns_hits.append(where)

    for path in properties_files:
        try:
            raw = read(path)
            text = unicode_escape_decode(raw)
        except Exception as exc:
            print("cannot read gradle.properties (fail closed): "
                  f"{rel(path)}: {exc}")
            failed = True
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if MCP.search(line):
                prop_hits.append(f"{rel(path)}:{lineno}:{line}")
        # Properties joins `\\`-continued physical lines before decoding
        # Unicode escapes; scan that logical view too, otherwise a literal
        # split as `mc\\` + `p.McpServer` is invisible.
        try:
            for lineno, joined in logical_properties_lines(raw):
                line = unicode_escape_decode(joined)
                if MCP.search(line):
                    hit = f"{rel(path)}:{lineno}:{line}"
                    if hit not in prop_hits:
                        prop_hits.append(hit)
        except Exception as exc:
            print("cannot join gradle.properties lines (fail closed): "
                  f"{rel(path)}: {exc}")
            failed = True

    if has_ph and mcp_hits:
        print("manifest placeholders request MCP components:")
        for hit in mcp_hits:
            print(hit)
    elif mcp_hits:
        # An MCP literal anywhere in a gradle file is itself fail-closed: it
        # may feed a namespace/applicationId placeholder or another dynamic
        # property even when the literal is not next to a known API.
        print("gradle build files reference MCP components (fail closed):")
        for hit in mcp_hits:
            print(hit)
    if src_hits:
        print("custom manifest srcFile bypasses MCP manifest scan:")
        for hit in src_hits:
            print(hit)
    if ns_hits:
        print("namespace/applicationId requests MCP package:")
        for hit in ns_hits:
            print(hit)
    if prop_hits:
        print("gradle.properties exposes MCP components:")
        for hit in prop_hits:
            print(hit)

    caught = bool(mcp_hits or src_hits or ns_hits or prop_hits)
    return 1 if (caught or failed) else 0


try:
    sys.exit(main())
except Exception as exc:
    print(f"gradle injection scan crashed (fail closed): {exc}")
    sys.exit(2)
PY
)"
    GR_RC=$?
    set -e
    if [ "$GR_RC" -ge 2 ]; then
      report "gradle injection scan failed (fail closed, rc=$GR_RC)"
    elif [ "$GR_RC" -eq 1 ]; then
      while IFS= read -r msg; do
        [ -n "$msg" ] || continue
        report "$msg"
      done <<< "$GR_OUT"
    fi
  fi
fi

# --- C3. Surface: in-package outbound dependencies (allowlist, fail-closed).
# Only these exact imports may cross the package boundary (import allowlist =
# KeyVault + the 7 tool contract types; star imports and anything else fail);
# outside imports, the package decl, self-mentions (dev.librepocket.mcp) and
# KDoc links ([dev.librepocket.…] or the HTML form <dev.librepocket.…>) must
# carry every remaining occurrence. N-B2: import matching tolerates leading
# indentation and trailing whitespace/comments (line is left-trimmed before
# classification; the allowlist anchors accept trailing [[:space:]] and //
# comments — the comment tail is `//.*`, NOT `//[^\n]*`: inside a POSIX
# bracket expression `\n` is the two literal chars backslash+n, not a
# newline, so `[^\n]` wrongly rejected any trailing comment containing the
# letter n or a backslash; lines are read one at a time so `.` cannot cross
# lines). Import lines are normalised before classification (`static` and
# repeated whitespace collapsed; block comments anywhere on the line and a
# `//` tail dropped; trailing `;` dropped), so `import  dev…`,
# `import static dev…`, `import /*x*/ dev…`, `import X; // note` and
# `import X /* note */;` cannot fall through the allowlist by taking an early
# `continue`; a subpackage may import a parent-package mcp type. A package
# header followed by more code on the same physical line drops only the
# header up to the first `;`, then continues scanning the remainder. N-B3
# known limitation: an FQN split across physical lines
# (e.g. "dev.librepocket\n.router.X") is not caught by this per-line C3 scan.
# Single-line block comments between segments, spaces around dots and
# backticked segments are normalised before the leftover check (C4 does the
# same for the outside split); the raw line is also checked after only the
# KDoc/self-mention allowances, so a literal containing `/*`/`*/` cannot make
# the normaliser swallow a real contiguous FQN. That raw check deliberately
# over-approximates: a bare outbound FQN inside a comment or string in an
# in-package file also turns red. The Kotlin formatter does not wrap at
# package-segment boundaries, so the remaining line-split form is accepted
# residual risk rather than a silent gap in the single-line rules. The
# self-mention strip is boundary-aware (`dev.librepocket.mcpfoo` is outbound,
# not a self-mention).
MCP_ALLOWLIST='^[[:space:]]*import dev\.librepocket\.(keystore\.KeyVault|tool\.(CapabilityLevel|DenyReason|Projection|ProjectionContext|SideEffect|ToolAnnotations|ToolDef))([[:space:]]*//.*)?[[:space:]]*$'
if [ "${#IN_PKG_FILES[@]}" -gt 0 ]; then
  MCP_PKG_FILES=("${IN_PKG_FILES[@]}")
  for f in ${MCP_PKG_FILES[@]+"${MCP_PKG_FILES[@]}"}; do
    if [ ! -r "$f" ]; then
      report "unreadable mcp source (fail closed): $f"
      continue
    fi
    lineno=0
    while IFS= read -r line || [ -n "$line" ]; do
      lineno=$((lineno + 1))
      # N-B2: classify on the left-trimmed form so indented package/import
      # lines behave like column-0 ones (the report still quotes the original).
      trimmed="${line#"${line%%[![:space:]]*}"}"
      # A package declaration may be followed by more code on the same
      # physical line (`package …; import …` is legal Java/Kotlin). Drop only
      # the declaration header up to the first semicolon and keep scanning the
      # remainder; a declaration with no semicolon consumes the whole line.
      # This also handles non-canonical spellings (comments/whitespace/
      # backticks) without letting them hide a same-line outbound reference.
      case "$trimmed" in
        package[[:space:]]*)
          case "$trimmed" in
            *\;*)
              trimmed="${trimmed#*;}"
              trimmed="${trimmed#"${trimmed%%[![:space:]]*}"}"
              ;;
            *) continue ;;
          esac
          ;;
      esac
      case "$trimmed" in
        import[[:space:]]*)
          # Normalise the import before classifying it: drop block comments
          # anywhere on the line and a `//` tail, remove backticks from
          # package segments, collapse `static` and any run of whitespace
          # (`import  dev…`, `import static dev…`, `import `dev`.…`), and drop
          # the trailing semicolon. The allowlist is tested against this
          # normalised string, so a non-canonical but legal spelling (comment
          # before the path, `import X; // note`, `import X /* note */;`,
          # `import `dev`.librepocket…`) cannot fall through the exact-prefix
          # `case`.
          import_norm="$(printf '%s\n' "$trimmed" | sed -E \
            's#/\*([^*]|\*+[^*/])*\*+/##g; s#[[:space:]]*//.*$##; s/`//g; s/^import[[:space:]]+(static[[:space:]]+)?/import /; s/;[[:space:]]*(\/\/.*)?$/\1/; s/;[[:space:]]*$//; s/[[:space:]]+/ /g; s/[[:space:]]*$//')"
          case "$import_norm" in
            "import dev.librepocket."*)
              # A subpackage file may import a parent-package mcp type
              # (dev.librepocket.mcp.McpServer) without that being outbound.
              if printf '%s\n' "$import_norm" | \
                grep -qE '^import dev\.librepocket\.mcp(\.[A-Za-z_][A-Za-z0-9_]*)*$'; then
                continue
              fi
              if ! printf '%s\n' "$import_norm" | grep -qE "$MCP_ALLOWLIST"; then
                report "outbound import not on allowlist (fail closed): ${f#"$ROOT"/}:$lineno: $line"
                fail=1
              fi
              ;;
          esac
          # A line may contain more than one import declaration
          # (`import java…; import dev.librepocket…;`). The per-line prefix
          # classifier sees only the first; fall through to the leftover scan
          # for the multi-import form instead of taking the early continue.
          # Detect on the normalised text, not the raw line: comments are
          # already stripped there, so `; /* gap */ import …` is recognised
          # and an allowlisted import whose trailing comment merely contains
          # the text `; import …` does not become a false red.
          if ! printf '%s\n' "$import_norm" | \
            grep -qE ';[[:space:]]*import[[:space:]]'; then
            continue
          fi
          ;;
      esac
      # Normalise legal-but-non-canonical single-line spellings before the
      # leftover check: block comments between package segments (including
      # two comments on one line), spaces around dots, and backticks. Comments
      # are removed with a non-greedy ERE so two comments cannot swallow the
      # path between them; `//` is deliberately left alone (it comments out
      # the rest of the physical line, and truncating at a `//` inside a
      # string would hide a later reference).
      # Then strip allowed forms: KDoc links start with "[dev.librepocket."
      # or "<dev.librepocket." and self-mentions are "dev.librepocket.mcp"
      # (backtick prose or package path). The self-mention match is
      # boundary-aware so a lookalike package `dev.librepocket.mcpfoo` is NOT
      # stripped (it is a real outbound ref).
      stripped="$(printf '%s\n' "$line" | sed -E \
        's#/\*([^*]|\*+[^*/])*\*+/##g; s/[ \t]*\.[ \t]*/./g; s/`//g; s/\[dev\.librepocket\.//g; s/<dev\.librepocket\.//g; s/dev\.librepocket\.mcp([^A-Za-z0-9_]|$)/\1/g')"
      case "$stripped" in
        *dev.librepocket.*)
          report "outbound reference from inside dev/librepocket/mcp/ (fail closed): ${f#"$ROOT"/}:$lineno: $line"
          fail=1
          continue
          ;;
      esac
      # Also check the raw line with only the KDoc/self-mention allowances.
      # The normalisation above is not string-aware; a literal such as
      # `val s = "/*"; val x = dev.librepocket.router.X; val t = "*/"` could
      # otherwise make the block-comment removal swallow the real reference.
      # This is deliberately over-approximate: a bare outbound FQN inside a
      # comment/string in an in-package file also turns red (fail-closed).
      raw_stripped="$(printf '%s\n' "$line" | sed -E \
        's/\[dev\.librepocket\.//g; s/<dev\.librepocket\.//g; s/dev\.librepocket\.mcp([^A-Za-z0-9_]|$)/\1/g')"
      case "$raw_stripped" in
        *dev.librepocket.*)
          report "outbound reference from inside dev/librepocket/mcp/ (fail closed): ${f#"$ROOT"/}:$lineno: $line"
          fail=1
          ;;
      esac
    done < "$f"
  done
fi

# --- C4. Surface: any production source OUTSIDE the package (whole module
# tree, every module — not just app/). Stage6 B: OUTSIDE_FILES comes from the
# package-based split in C1b, so a path lookalike such as
# `com/example/dev/librepocket/mcp/Sneaky.kt` with `package com.example` is
# scanned here (the old `-path '*/dev/librepocket/mcp/*'` filter exempted it).
# Stage6 F2: the split happens on file identity before scanning, never on
# scan output, so contents cannot hide a hit (`... // see /dev/librepocket/mcp/`).
# Read/scan errors still fail closed; zero outside files is a legitimate
# no-op (C1 separately fails closed on zero production files). The scan is a
# Python normaliser: a small recursive lexer blanks // and /*…*/ comments only
# outside strings (multi-line comments included; newlines preserved), keeps
# string contents (so a URL `https://…` cannot truncate the line and hide a
# later FQN), understands Kotlin `${…}` templates and Java text-block escapes,
# then removes backticks and collapses same-line whitespace around dots.
# This is needed because the same Java/Kotlin code also compiles as
# `dev.librepocket.`mcp`.Transport`, `dev.librepocket./*x*/mcp.Transport` and
# `dev . librepocket . mcp`. Residual: an FQN split across physical lines is
# not joined (C4 scans line by line); a comment inside a raw string template
# is string content, not code, and is deliberately not parsed as a comment.
# N-B1 accepted cost: the bare `Mcp[A-Z]` substring is deliberately wide — a
# third-party string containing e.g. `McpCompat` also turns this red
# (fail-closed direction, not tightened: a token-boundary rule cannot
# distinguish com.x.McpCompat from a real McpServer reference without
# full parsing, and a missed wiring reference is the worse failure mode).
# The FQN half carries a trailing boundary
# (`dev\.librepocket\.mcp([^A-Za-z0-9_]|$)`) so `dev.librepocket.mcpfoo` is
# no longer a false red; `Mcp[A-Z]` stays deliberately wide. ---
if [ "${#OUTSIDE_FILES[@]}" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    report "python3 not available for production source scan (fail closed)"
  else
    set +e
    MCP_OUT="$(python3 - "$ROOT" ${OUTSIDE_FILES[@]+"${OUTSIDE_FILES[@]}"} <<'PY'
import os
import re
import sys

root = sys.argv[1]
files = sys.argv[2:]

# Same trailing boundary as the manifest text grep: `dev.librepocket.mcpfoo`
# is a different package. `Mcp[A-Z]` stays deliberately wide (fail closed).
MCP = re.compile(r"dev\.librepocket\.mcp([^A-Za-z0-9_]|$)|Mcp[A-Z]")
DOTS = re.compile(r"[ \t]*\.[ \t]*")


def rel(path):
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


def sanitize(text, java):
    """Blank comments outside strings, but keep string contents.

    C4 must catch an FQN inside a string literal, so unlike C3 it cannot put
    strings through the comment remover. A naive `//.*$` regex truncates
    `register("https://…", dev.librepocket.mcp.McpServer())` at the URL's
    `//` and hides the real reference. This small recursive lexer keeps
    normal/raw/text-block strings verbatim, handles Kotlin `${…}` templates
    (including nested strings/comments), and blanks `//`/`/*…*/` only in
    code. Newlines in blanked comments are preserved for diagnostics.
    """
    n = len(text)

    def scan_code(i, stop_brace=False):
        out = []
        depth = 0
        while i < n:
            c = text[i]
            if stop_brace and c == "}":
                if depth == 0:
                    return i, "".join(out)
                depth -= 1
                out.append(c)
                i += 1
                continue
            if stop_brace and c == "{":
                depth += 1
                out.append(c)
                i += 1
                continue
            if c == "/" and i + 1 < n and text[i + 1] == "/":
                j = text.find("\n", i)
                if j < 0:
                    j = n
                out.append(" " * (j - i))
                i = j
                continue
            if c == "/" and i + 1 < n and text[i + 1] == "*":
                j = i + 2
                nesting = 1
                while j < n:
                    if not java and text.startswith("/*", j):
                        nesting += 1
                        j += 2
                    elif text.startswith("*/", j):
                        nesting -= 1
                        j += 2
                        if nesting == 0:
                            break
                    else:
                        j += 1
                out.append("".join(
                    "\n" if ch == "\n" else " " for ch in text[i:j]))
                i = j
                continue
            if text.startswith('"""', i):
                j = i + 3
                while j < n:
                    if java:
                        if text[j] == "\\" and j + 1 < n:
                            j += 2
                            continue
                        if text.startswith('"""', j):
                            j += 3
                            break
                    elif text.startswith('"""', j):
                        j += 3
                        break
                    j += 1
                while j < n and text[j] == '"':
                    j += 1
                out.append(text[i:j])
                i = j
                continue
            if c in ('"', "'"):
                quote = c
                out.append(c)
                i += 1
                while i < n:
                    ch = text[i]
                    if ch == "\\" and i + 1 < n:
                        out.append(text[i:i + 2])
                        i += 2
                        continue
                    if ch == quote:
                        out.append(ch)
                        i += 1
                        break
                    if (quote == '"' and ch == "$" and i + 1 < n
                            and text[i + 1] == "{" and not java):
                        out.append("${")
                        i += 2
                        i, inner = scan_code(i, stop_brace=True)
                        out.append(inner)
                        if i < n and text[i] == "}":
                            out.append("}")
                            i += 1
                        continue
                    out.append(ch)
                    i += 1
                continue
            if c == "`" and not java:
                j = text.find("`", i + 1)
                if j < 0:
                    out.append(text[i:])
                    i = n
                else:
                    out.append(text[i:j + 1])
                    i = j + 1
                continue
            out.append(c)
            i += 1
        return i, "".join(out)

    _, result = scan_code(0)
    return result


def main():
    hits = []
    failed = False
    for path in files:
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except Exception as exc:
            print("cannot read production source (fail closed): "
                  f"{rel(path)}: {exc}")
            failed = True
            continue
        # `text` is raw source. Sanitise it once so comments are blanked only
        # outside strings; newlines are preserved, so line numbers/reporting
        # still point at the original line.
        sanitized = sanitize(text, path.endswith(".java"))
        for lineno, line in enumerate(sanitized.splitlines(), 1):
            # Normalise legal-but-non-canonical spellings: backticked package
            # segments, blanked /*…*/ comments between segments, and
            # whitespace around dots.
            scan = DOTS.sub(".", line.replace("`", ""))
            if MCP.search(scan):
                original = text.splitlines()[lineno - 1]
                hits.append(f"{rel(path)}:{lineno}:{original}")
    for hit in hits:
        print(hit)
    if failed:
        return 2
    return 1 if hits else 0


try:
    sys.exit(main())
except Exception as exc:
    print(f"production source scan crashed (fail closed): {exc}")
    sys.exit(2)
PY
)"
    MCP_RC=$?
    set -e
    if [ "$MCP_RC" -ge 2 ]; then
      report "production source scan failed (fail closed, rc=$MCP_RC): $MCP_OUT"
    elif [ "$MCP_RC" -eq 1 ]; then
      report "production Kotlin/Java outside dev/librepocket/mcp/ references MCP types (MCP must stay unwired until the wiring PR updates this gate):"
      printf '%s\n' "$MCP_OUT"
      fail=1
    fi
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "docs_claim_check: FAILED"
  exit 1
fi
echo "docs_claim_check: PASSED"
