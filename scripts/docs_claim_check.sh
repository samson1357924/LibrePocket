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
#         closed. A raw-text scan (FQN boundary / Mcp[A-Z]) remains as
#         defense-in-depth (comments still turn it red — accepted cost);
#      2. Gradle manifest injection: no `manifestPlaceholders` /
#         `add|set|getManifestPlaceholders` in ANY gradle file alongside an
#         MCP literal in ANY gradle file (set-level co-occurrence, so a root
#         extra cannot hide it), no `manifest.srcFile` or getter
#         `getManifest().srcFile|setSrcFile`, no namespace/applicationId/
#         setNamespace/setApplicationId set to dev.librepocket.mcp, and no
#         MCP literal in any gradle.properties or *.toml version catalog
#         (gradle/properties decoded through JLS `\uXXXX` escapes first; TOML
#         basic strings decode `\uXXXX`/`\UXXXXXXXX` but literal strings and
#         comments do not, and the raw text is scanned as a backstop);
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
#         self-mentions, and KDoc links in comments (blanked before scanning,
#         so a bracketed/generic FQN in real code or a string is still found) —
#         any other import (incl. star imports) or fully-qualified outbound use
#         fails;
#      4. whole-module package-classification outside references: no
#         production source file OUTSIDE the MCP package (same package-based
#         split; every module's production source roots — main + flavors +
#         debug, Kotlin and Java, excluding test*/androidTest*) references
#         the mcp package or any Mcp* type. Scanned by a Python normaliser
#         (backticks, same-line /*…*/ comments, // comments, whitespace
#         around dots) rather than a literal grep. Java is lexed
#         after JLS Unicode escape decoding; an escape-obscured MCP reference
#         (including one that decodes to whitespace inside the FQN) fails
#         closed via the decoded scan and the ESC pre-scan (which masks only
#         the package header, never the whole line);
#      5. discovery/classification fail-closed: no src roots / no manifests /
#         no in-package sources / unreadable or ambiguous package
#         declarations / text-scan, parse or find errors ⇒ fail, never silent
#         pass.
#         Merged-manifest output, string-concatenated gradle literals,
#         arbitrary dynamic property resolution and dynamic/reflective wiring
#         are outside this text guard's evidence: a green CI run does not
#         prove they are absent. Symlinked sources/directories are not
#         followed; the helper fails closed with a diagnostic instead of
#         silently skipping them (G-13).
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
# Shared stdlib-only lexer/scan helper (Stage1): C1 discovery, C2 type names and
# C3 outbound are delegated to it. A missing/unreadable helper or missing
# python3 fails closed (never a silent pass).
MCP_SCAN="$ROOT/scripts/mcp_scan.py"

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

for f in "$WORKFLOW" "$TESTING" "$ENV_DOC" "$TOML" "$APP_BUILD" "$WRAPPER" \
  "$MCP_SCAN"; do
  if [ ! -f "$f" ]; then
    report "missing required file: $f"
  fi
done
if [ ! -r "$MCP_SCAN" ]; then
  report "helper scripts/mcp_scan.py missing or unreadable (fail closed)"
fi
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
#   1. AndroidManifest registration (raw-text scan + real XML parse, C2);
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

# --- C1. Discovery: every production source of every module, repo-wide, via
# the shared helper (fail-closed on find/python3/discover errors or an empty
# result). The helper scans all production `.kt`/`.java` files — including a
# source root outside `*/src` configured through a custom `java.srcDir(...)` —
# and excludes only the real Gradle test source sets, i.e. files at
# `<module>/src/<sourceSet>/…` where the module root comes from the root
# settings.gradle[.kts] (`include`/`includeBuild`/`projectDir`; with no root
# settings, repo root + depth-1 children with build.gradle[.kts] + `src/`)
# and the sourceSet starts with `test`/`androidTest`. Everything else stays in
# scope: a production package literally named `…src.test…` under `src/main`, a
# custom `extra/java` root that later contains `src/test`, and an inert
# marker file (or marker + `src/` child) in a package directory (neither is a
# module root, so a sibling package named `build` is not pruned). A `build`
# directory is pruned only when it is a real Gradle output root, not a package
# segment. Symlinked sources/directories are not followed: discovery fails
# closed with a diagnostic rather than silently skipping them.
# The zero-src-root sanity check is kept: a repo with no `*/src` directory at
# all means parser/checkout drift, never a silent pass.
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

# Production source files (Kotlin/Java) of every module, including roots that
# are not under a `*/src` directory. The helper prunes build/.gradle/.git/
# node_modules and does not follow symlinks; a walk/read error exits non-zero.
PROD_FILES=()
PROD_LIST=""
if ! command -v python3 >/dev/null 2>&1; then
  report "python3 not available for production source discovery (fail closed)"
elif [ ! -r "$MCP_SCAN" ]; then
  report "helper scripts/mcp_scan.py missing or unreadable (fail closed)"
else
  set +e
  PROD_LIST="$(python3 "$MCP_SCAN" discover "$ROOT" 2>&1)"
  DISC_RC=$?
  set -e
  if [ "$DISC_RC" -ne 0 ]; then
    report "production source discovery failed (fail closed, rc=$DISC_RC): $PROD_LIST"
    PROD_LIST=""
  fi
fi
while IFS= read -r f; do
  [ -n "$f" ] && PROD_FILES+=("$f")
done <<< "$PROD_LIST"
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
# The raw manifest text scan uses the same JVM identifier-part predicate
# (letters, Nd/Nl, Mn/Mc/Cf/Pc/Sc, `$`/`_`), so `dev.librepocket.mcpfoo`,
# `…mcp$Compat`, `…mcpé`, `…mcp\u0301`, `…mcp\u200d` and `…mcp€X` are different
# packages, while `Mcp[A-Z]` stays deliberately wide (over-approximate).
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
import unicodedata

root = sys.argv[1]
files = sys.argv[2:]

# A package segment is scanned with the full JVM identifier-part predicate.
# `\w`/`[^\W\d]` are NOT enough: Python's `\w` excludes Mn/Mc/Cf/Pc/Sc, so a
# lookalike package such as `dev.librepocket.mcp\u0301` used to be truncated to
# `dev.librepocket.mcp`, classified IN, and its real MCP import was stripped as
# a self-mention (G-17).  The exact `Character.isJavaIdentifierPart` categories
# are used here too: L*, Nd, Nl, Mn/Mc/Cf/Pc/Sc plus `$`/`_` and the
# identifier-ignorable controls.  Category No (e.g. U+00B2) is intentionally
# excluded.
FQN_RE = re.compile(r"^dev\.librepocket\.mcp(?:\..+)?$")
PKG_HEAD = re.compile(r"package\s+")
PKG_LINE = re.compile(r"^\s*package\b")
ESC_MCP = re.compile(r"dev\.librepocket\.|Mcp[A-Z]")
# JVM whitespace around a dot (space/tab/form feed/vtab) collapses; a line
# terminator does not (that would join separate physical lines).
DOTS = re.compile(r"[^\S\n]*\.[^\S\n]*")
_IDENT_EXTRA = frozenset({"Mn", "Mc", "Cf", "Pc", "Sc"})


def is_ident_part(c):
    if c == "$" or c == "_":
        return True
    category = unicodedata.category(c)
    if category[0] == "L" or category in ("Nd", "Nl"):
        return True
    if category in _IDENT_EXTRA:
        return True
    o = ord(c)
    return o <= 0x08 or 0x0E <= o <= 0x1B or 0x7F <= o <= 0x9F


def scan_pkg_name(code, i):
    """Scan a full qualified name starting at `i` with JVM identifier rules.

    Returns (normalised_name_or_None, end_index).  Comments are already blanked
    to spaces by the lexer; whitespace and dots may separate segments and a
    Kotlin backticked segment is accepted.  Scanning manually (instead of a
    `\\w`-based regex) means a legal lookalike continuation such as U+0301,
    U+20AC, U+203F or U+200D is part of the name, so it can never be truncated
    back to `dev.librepocket.mcp`.
    """
    n = len(code)
    segments = []
    while True:
        while i < n and code[i].isspace():
            i += 1
        if i < n and code[i] == "`":
            j = code.find("`", i + 1)
            if j < 0:
                return None, i
            segments.append(code[i + 1:j])
            i = j + 1
        else:
            start = i
            while i < n and is_ident_part(code[i]):
                i += 1
            if i == start:
                return None, i
            segments.append(code[start:i])
        j = i
        while j < n and code[j].isspace():
            j += 1
        if j < n and code[j] == ".":
            i = j + 1
            continue
        return ".".join(segments), i


def package_declarations(code):
    decls = []
    for match in PKG_HEAD.finditer(code):
        before = code[match.start() - 1] if match.start() else ""
        if before and (is_ident_part(before) or before == "`"):
            continue
        name, _ = scan_pkg_name(code, match.end())
        if name is not None:
            decls.append(re.sub(r"[`\s]", "", name))
    return decls


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
            #     the package scanner tolerates) so `package `dev`.librepocket.mcp`
            #     still classifies;
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
    decls = package_declarations(code)
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
                # JVM-faithful line split: form feed (and the other Unicode
                # separator characters `str.splitlines()` treats as breaks) is
                # ordinary whitespace, not a line terminator.
                decoded_lines = decoded.replace(
                    "\r\n", "\n").replace("\r", "\n").split("\n")
                for lineno, line in enumerate(decoded_lines, 1):
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
                    # Collapse whitespace around dots so an escape that decoded
                    # to a space/tab/form-feed inside the FQN
                    # (`dev\u0020.librepocket…`) is still an MCP reference on
                    # ONE physical line.  A decoded line terminator is not
                    # joined (documented N-B3 residual), because DOTS never
                    # crosses `\n`.
                    if ESC_MCP.search(DOTS.sub(".", scan_line)):
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

# --- C2 type-name enumeration (delegated to the shared helper). The manifest
# relative-component name set is the file basenames (added below) plus every
# type declared in an in-package file. Names come from the comment/string-
# stripped, Java-Unicode-decoded lexed view, so `class /* c */ Transport` and
# Java `cl\u0061ss Transport` are both seen. A read failure (rc>=2) is already a
# hard gate failure; the name set then falls back to basenames only.
TYPE_NAMES=""
if [ "${#IN_PKG_FILES[@]}" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    report "python3 not available for MCP type-name enumeration (fail closed)"
  elif [ ! -r "$MCP_SCAN" ]; then
    report "helper scripts/mcp_scan.py missing or unreadable (fail closed)"
  else
    set +e
    TYPE_NAMES="$(python3 "$MCP_SCAN" types "$ROOT" \
      "${IN_PKG_FILES[@]}" 2>&1)"
    TYPE_RC=$?
    set -e
    if [ "$TYPE_RC" -ne 0 ]; then
      report "MCP type-name scan failed (fail closed, rc=$TYPE_RC): $TYPE_NAMES"
      TYPE_NAMES=""
    fi
  fi
fi

# --- C2. Surface: AndroidManifest registration (any module, any flavor). ---
# N-B1 accepted cost: the bare `Mcp[A-Z]` substring is deliberately wide — a
# third-party string containing e.g. `McpCompat` also turns this red
# (fail-closed direction, not tightened: any token-boundary rule risks
# dropping a real `dev.librepocket.mcp.McpServer` reference mid-string).
# The FQN half of the text pattern carries a full JVM identifier-aware trailing
# boundary (combining marks, ZWJ/Cf, currency symbols, `$`) so a lookalike
# package such as `dev.librepocket.mcpfoo` (or `…mcp$Compat`/`…mcpé`/
# `…mcp\u0301`/`…mcp\u200d`/`…mcp€X`) is no longer a false red. XML component
# checks keep the substring form (over-approximate, fail-closed).
#
# Two layers:
#   1. A raw-text scan (FQN with an identifier-aware trailing boundary /
#      Mcp[A-Z]) over the raw manifest files. It is defense-in-depth: it also
#      catches comments, though `xml.etree` does not expose comments
#      (documented accepted cost — a comment mentioning MCP turns the gate
#      red). Entity-encoded dots (&#46;) have no literal `.`, so only layer 2
#      can catch them (pinned by a fixture).
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
#      and mirrors C3/C4's package-based truth. The helper's TYPE_DECL is
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
  # The text layer (comments included) and the authoritative XML layer run in
  # one Python pass below. The text layer is identifier-aware for `$`/Unicode
  # (see mcp_fqn_in in that pass), so a lookalike package is not a false red.

  # Authoritative XML layer (Stage6 A/B). IN_PKG_FILES was classified by C1b
  # via the package declaration; the parser adds declared type names to the
  # basename set. Anything the parser cannot do fails closed; its stdout lines
  # are forwarded to `report` verbatim.
  if ! command -v python3 >/dev/null 2>&1; then
    # defence in depth; A0/A already require python3
    report "python3 not available for AndroidManifest XML parsing (fail closed)"
  else
    set +e
    MF_XML_OUT="$(python3 - "$ROOT" "$TYPE_NAMES" \
      "${#IN_PKG_FILES[@]}" \
      ${IN_PKG_FILES[@]+"${IN_PKG_FILES[@]}"} "${MANIFESTS[@]}" <<'PY'
import os
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET

root = sys.argv[1]
# Newline-separated declared type names emitted by scripts/mcp_scan.py `types`
# (comment/string-stripped, Java-Unicode-decoded). These are the load-bearing
# half of the relative-component name set; basenames are added below.
type_names = [n for n in sys.argv[2].split("\n") if n]
n_in = int(sys.argv[3])
in_files = sys.argv[4:4 + n_in]
manifests = sys.argv[4 + n_in:]

FQN = "dev.librepocket.mcp"
# Identifier-aware trailing boundary using the full JVM identifier-part
# predicate (same one as scripts/mcp_scan.py, G-11): `$`, `_`, letters/digits,
# currency symbols (Sc), connecting punctuation (Pc), combining marks (Mn/Mc)
# and format/ignorable controls (Cf) all continue the package name, so
# lookalikes (`mcpfoo`, `mcp$Compat`, `mcpé`, `mcp\u0301`, `mcp\u200d`,
# `mcp€X`) are not the mcp package. `Mcp[A-Z]` remains deliberately wide
# (fail-closed, N-B1).
_IDENT_EXTRA = frozenset({"Mn", "Mc", "Cf", "Pc", "Sc"})


def is_ident_part(c):
    if c == "$" or c == "_":
        return True
    category = unicodedata.category(c)
    if category[0] == "L" or category in ("Nd", "Nl"):
        return True
    if category in _IDENT_EXTRA:
        return True
    o = ord(c)
    return o <= 0x08 or 0x0E <= o <= 0x1B or 0x7F <= o <= 0x9F


def mcp_fqn_in(text):
    """True when `text` contains the mcp package, boundary-aware."""
    i = 0
    while True:
        j = text.find(FQN, i)
        if j < 0:
            return False
        k = j + len(FQN)
        if k >= len(text) or not is_ident_part(text[k]):
            return True
        i = k


MCP_TYPE = re.compile(r"Mcp[A-Z]")

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
    # package declaration is dev.librepocket.mcp(.…). The declared type names
    # (comment/string-stripped, Java-Unicode-decoded) are precomputed by
    # scripts/mcp_scan.py; the file basenames are added here. Deliberately
    # over-approximate (enum/record/typealias cannot be components): extra names
    # only turn the gate red (fail-closed).
    found = set(type_names)
    for path in in_files:
        found.add(os.path.basename(path).rsplit(".", 1)[0])
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
    if mcp_fqn_in(value) or MCP_TYPE.search(value):
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
        # Defense-in-depth text layer: catches comments (which ElementTree
        # does not expose) and any raw FQN/`Mcp[A-Z]` token. It is an
        # identifier-aware Python regex, so a lookalike package is not a false
        # red. A read error fails closed here (the XML parse below would also
        # fail, but this keeps the diagnostic explicit).
        try:
            with open(manifest, encoding="utf-8", errors="replace") as handle:
                raw = handle.read()
        except Exception as exc:
            print("cannot read AndroidManifest (fail closed): "
                  f"{rel(manifest)}: {exc}")
            failed = True
            continue
        for lineno, line in enumerate(raw.split("\n"), 1):
            if mcp_fqn_in(line) or MCP_TYPE.search(line):
                print("Android manifest registers MCP (MCP must stay unwired "
                      "until the wiring PR updates this gate): "
                      f"{rel(manifest)}:{lineno}: {line.strip()}")
                failed = True
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
                    elif mcp_fqn_in(value) or MCP_TYPE.search(value):
                        # Non-component name: token check only. A bare
                        # basename (e.g. meta-data "Transport") is data.
                        # Redundant with the C2 text-scan layer; do not remove
                        # both.
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
#     consumed via project.property(...) is an indirect wiring surface);
#   - an MCP FQN / `Mcp[A-Z]` literal in any `*.toml` (at minimum the Gradle
#     version catalog `gradle/libs.versions.toml`, whose `[versions]` entries
#     are consumed as `libs.versions.<alias>.get()`; a literal there feeds a
#     namespace/applicationId/placeholder exactly like gradle.properties).
#     TOML basic strings (`"…"`/`"""…"""`) decode `\uXXXX` and `\UXXXXXXXX`
#     like Gradle's parser; literal strings (`'…'`/`'''…'''`) do not process
#     escapes and are left verbatim. The raw file is scanned as a backstop.
# Comment/space normalisation uses a second view of the same text (newlines
# preserved for diagnostics); the raw view is still scanned so a token that
# only appears in a comment remains fail-closed.
# Discovery is `find` over every gradle file, gradle.properties and *.toml
# (build/.gradle/.git excluded) — a superset of git-tracked files, so an
# untracked local injection also fails closed. Find/read/parse errors fail
# closed. Residual: string-concatenation-built gradle literals
# (`"dev.librepocket." + "mcp."`), non-literal dynamic property resolution
# (environment/system property with no MCP literal anywhere in a scanned
# gradle/properties/toml file), and injection expressed only in buildSrc or a
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

if ! TOML_LIST="$(find "$ROOT" -type f -name '*.toml' \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' 2>&1)"; then
  report "find over toml files failed (fail closed): $TOML_LIST"
  TOML_LIST=""
fi
CATALOG_FILES=()
while IFS= read -r f; do
  [ -n "$f" ] && CATALOG_FILES+=("$f")
done <<< "$TOML_LIST"

if [ "${#GRADLE_FILES[@]}" -gt 0 ] || [ "${#PROPERTIES_FILES[@]}" -gt 0 ] \
  || [ "${#CATALOG_FILES[@]}" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    report "python3 not available for gradle injection scan (fail closed)"
  else
    set +e
    GR_OUT="$(python3 - "$ROOT" "${#GRADLE_FILES[@]}" \
      ${GRADLE_FILES[@]+"${GRADLE_FILES[@]}"} \
      "${#PROPERTIES_FILES[@]}" \
      ${PROPERTIES_FILES[@]+"${PROPERTIES_FILES[@]}"} \
      "${#CATALOG_FILES[@]}" \
      ${CATALOG_FILES[@]+"${CATALOG_FILES[@]}"} <<'PY'
import os
import re
import subprocess
import sys
import unicodedata

root = sys.argv[1]
n_gradle = int(sys.argv[2])
pos = 3
gradle_files = sys.argv[pos:pos + n_gradle]
pos += n_gradle
n_props = int(sys.argv[pos])
pos += 1
properties_files = sys.argv[pos:pos + n_props]
pos += n_props
n_toml = int(sys.argv[pos])
pos += 1
toml_files = sys.argv[pos:pos + n_toml]

root = os.path.abspath(root)
module_root_set = None
module_roots_error = None
helper = os.path.join(root, "scripts", "mcp_scan.py")
try:
    proc = subprocess.run(
        [sys.executable, helper, "module-roots", root],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        timeout=120)
    if proc.returncode != 0:
        raise RuntimeError(
            f"module-roots rc={proc.returncode}: "
            f"{(proc.stderr or proc.stdout).strip()}")
    parsed_roots = {
        os.path.normpath(line.strip())
        for line in proc.stdout.splitlines() if line.strip()
    }
    if not parsed_roots:
        raise RuntimeError("module-roots returned no roots")
    module_root_set = parsed_roots
except Exception as exc:
    module_roots_error = str(exc)

# Same identifier-aware boundary as the manifest raw-text scan and C4 (G-11:
# combining marks, ZWJ/Cf and currency symbols continue the identifier).
_IDENT_EXTRA = frozenset({"Mn", "Mc", "Cf", "Pc", "Sc"})


def is_ident_part(c):
    if c == "$" or c == "_":
        return True
    category = unicodedata.category(c)
    if category[0] == "L" or category in ("Nd", "Nl"):
        return True
    if category in _IDENT_EXTRA:
        return True
    o = ord(c)
    return o <= 0x08 or 0x0E <= o <= 0x1B or 0x7F <= o <= 0x9F


def mcp_token_in(text):
    prefix = "dev.librepocket.mcp"
    i = 0
    while True:
        j = text.find(prefix, i)
        if j < 0:
            break
        k = j + len(prefix)
        if k >= len(text) or not is_ident_part(text[k]):
            return True
        i = k
    return bool(re.search(r"Mcp[A-Z]", text))
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
    r"[\"'/]{0,3}dev\.librepocket\.mcp", re.IGNORECASE)


def ns_matches(text):
    # Identifier-aware trailing boundary (same predicate as mcp_token_in), so
    # a legal non-MCP namespace with a combining-mark/currency continuation is
    # not a false red. (AGP restricts namespace segments to ASCII, so this is
    # consistency rather than a fail-open fix.)
    for match in NS.finditer(text):
        k = match.end()
        if k >= len(text) or not is_ident_part(text[k]):
            yield match


def ns_hit(text):
    return next(ns_matches(text), None) is not None
PH_TOKEN = "manifestplaceholders"
# G-16: a MAIN source set that adds a srcDir under <module>/src/test* (or a
# pruned <module>/build root) compiles test-shaped code as production.  The
# helper's path-based test exclusion would then drop it, so fail closed with a
# specific diagnostic.  The WHOLE balanced argument expression is parsed (not
# just a literal immediately after the call): `file(...)`, `project.file(...)`,
# `listOf(...)`, `files(...)`, `setSrcDirs(...)`, string concatenation,
# same-file `val` indirection, `$buildDir`/`buildDir`/`layout.buildDirectory`
# and multi-line expressions are all resolved.  Paths are resolved against the
# build file's MODULE directory and only `<module>/src/<test*|androidTest*>`
# or a path under `<module>/build` is flagged, so `extra/src/testFixtures`
# stays a legitimate production root (P5/P6).  An explicit main srcDir under
# `build/` (P3, `build/generated/ksp`) stays red as an accepted fail-closed
# over-block: explicit build srcDirs are not currently added to discovery.
# An expression that cannot be resolved to a safe path fails closed.
SRCDIR_TOKEN = re.compile(r"(?<![\w$])(setSrcDirs|srcDirs|srcDir)\b")
SRCDIR_ASSIGN = re.compile(r"(?<![\w$])(srcDirs|srcDir)\b\s*(\+=|=)")
SOURCESET_CALL = re.compile(
    r"sourceSets\s*\.\s*(?:getByName|create|maybeCreate|named)"
    r"\s*\(\s*[\"']([^\"'\n]+)[\"']")
SOURCESET_INDEX = re.compile(
    r"sourceSets\s*\[\s*[\"']([^\"'\n]+)[\"']\s*\]")
SOURCESET_PROP = re.compile(r"sourceSets\s*\.\s*([A-Za-z_$][\w$]*)")
SOURCESET_BARE_CALL = re.compile(
    r"\b(?:getByName|create|maybeCreate|named)"
    r"\s*\(\s*[\"']([^\"'\n]+)[\"']")
SOURCESET_MARK = re.compile(r"(?<![\w$.])sourceSets\s*$")
APPLY_HEADER = re.compile(r"(?:^|\.)\s*apply\s*$")
VAL_DECL = re.compile(
    r"(?<![\w$])(?:val|var|def)\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    r"\s*(?::\s*[^=\n]+)?=\s*")
NAME_ASSIGN = re.compile(
    r"(?<![\w$.])([A-Za-z_$][A-Za-z0-9_$]*)\s*"
    r"(?:[+\-*/]?=|\+\+|--)(?![=>])")
BUILD_MARK = "\x00BUILD\x00"
MODULE_MARK = "\x00MODULE\x00"
ROOT_MARK = "\x00ROOT\x00"
TOKEN_RE = re.compile(
    # Triple-quoted forms must precede the single-quote forms so the raw
    # string is one token (`"""extra/java"""`, not three empty strings).
    r'"""(?:\\.|(?!""")[\s\S])*"""'
    r"|'''(?:\\.|(?!''')[\s\S])*'''"
    r'|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    r"|[A-Za-z_$][A-Za-z0-9_$]*|\d+|\S",
    re.S)
IDENT_RE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*$")
WRAPPER_CALLS = frozenset({
    "file", "project.file", "rootProject.file", "files",
    "listOf", "mutableListOf", "setOf", "arrayOf", "list",
    "setSrcDirs", "srcDir", "srcDirs",
})
BUILD_CALLS = frozenset({
    "buildDir", "project.buildDir", "rootProject.buildDir",
    "layout.buildDirectory", "project.layout.buildDirectory",
})
BUILD_DIR_CALLS = frozenset({
    "layout.buildDirectory.dir", "project.layout.buildDirectory.dir",
    "buildDir.resolve", "project.buildDir.resolve",
    "rootProject.buildDir.resolve",
    "layout.buildDirectory.get", "project.layout.buildDirectory.get",
})
BUILD_BARE = frozenset({
    "buildDir", "project.buildDir", "rootProject.buildDir",
    "layout.buildDirectory", "project.layout.buildDirectory",
})
MODULE_BARE = frozenset({"projectDir", "project.projectDir"})
ROOT_BARE = frozenset({"rootDir", "project.rootDir", "rootProject.projectDir"})


def strip_comments_len(text):
    """Blank comments but keep string literals and the exact length."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            quote = c
            triple = text.startswith(c * 3, i)
            j = i + (3 if triple else 1)
            while j < n:
                if not triple and text[j] == "\\":
                    j += 2
                    continue
                if triple and text.startswith(c * 3, j):
                    j += 3
                    break
                if not triple and text[j] == c:
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            if j < 0:
                j = n
            out.append(" " * (j - i))
            i = j
            continue
        if text.startswith("/*", i):
            level = 1
            j = i + 2
            while j < n and level:
                if text.startswith("/*", j):
                    level += 1
                    j += 2
                elif text.startswith("*/", j):
                    level -= 1
                    j += 2
                else:
                    j += 1
            out.append("".join("\n" if ch == "\n" else " "
                               for ch in text[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def blank_strings_len(text):
    """Blank string/backtick literals (newlines kept) to locate real calls."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in "\"'`":
            if c == "`":
                j = text.find("`", i + 1)
                j = n if j < 0 else j + 1
            else:
                quote = c
                triple = text.startswith(c * 3, i)
                j = i + (3 if triple else 1)
                while j < n:
                    if not triple and text[j] == "\\":
                        j += 2
                        continue
                    if triple and text.startswith(c * 3, j):
                        j += 3
                        break
                    if not triple and text[j] == c:
                        j += 1
                        break
                    j += 1
            out.append("".join("\n" if ch == "\n" else " "
                               for ch in text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def blank_quoted_len(text):
    """Blank only quote-delimited literals, leaving backticks as code.

    `blank_strings_len` also blanks backticked identifiers (which are legal
    call/property names).  Source-set matching uses this view so a match that
    starts inside a `"..."`/`'...'` literal is rejected, while a backticked
    method/property is still treated as code (G-26).
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            quote = c
            triple = text.startswith(c * 3, i)
            j = i + (3 if triple else 1)
            while j < n:
                if not triple and text[j] == "\\":
                    j += 2
                    continue
                if triple and text.startswith(c * 3, j):
                    j += 3
                    break
                if not triple and text[j] == c:
                    j += 1
                    break
                j += 1
            out.append("".join("\n" if ch == "\n" else " "
                               for ch in text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def balanced_end(code, open_index):
    depth = 0
    i = open_index
    n = len(code)
    while i < n:
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n


def expression_end(code, keep, start):
    """End of a statement expression (`=`/`+=` RHS), balancing brackets."""
    depth = 0
    i = start
    n = len(code)
    saw = False
    while i < n:
        c = code[i]
        if c in "([{":
            depth += 1
            saw = True
        elif c in ")]}":
            if depth > 0:
                depth -= 1
            saw = True
        elif c == ";" and depth == 0:
            return i
        elif c == "\n" and depth == 0:
            k = i - 1
            while k >= start and keep[k].isspace():
                k -= 1
            prev = keep[k] if k >= start else ""
            if saw and prev not in ("+", ",", ".", "=", "\\", ":", "?"):
                return i
        elif not c.isspace():
            saw = True
        elif not keep[i].isspace():
            # String content is blanked in the code view; it is still a real
            # expression token, so the statement does not end before it.
            saw = True
        i += 1
    return n


def marker_for(name):
    name = re.sub(r"\s+", "", name)
    if name.startswith("$"):
        name = name[1:]
    if name.startswith("{") and name.endswith("}"):
        name = name[1:-1]
    if name in BUILD_BARE or name in ("buildDir", "project.buildDir"):
        return BUILD_MARK
    if name in MODULE_BARE:
        return MODULE_MARK
    if name in ROOT_BARE:
        return ROOT_MARK
    return None


def decode_string_token(token):
    quote = token[0]
    body = token[1:-1]
    if len(body) >= 4 and body.startswith(quote * 2) and \
            body.endswith(quote * 2):
        body = body[2:-2]
    parts = []
    cur = []
    ok = True
    i = 0
    n = len(body)
    escapes = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\",
               '"': '"', "'": "'", "$": "$"}
    while i < n:
        c = body[i]
        if c == "\\" and i + 1 < n:
            cur.append(escapes.get(body[i + 1], body[i + 1]))
            i += 2
            continue
        if c == "$":
            if i + 1 < n and body[i + 1] == "{":
                j = body.find("}", i + 2)
                if j < 0:
                    ok = False
                    break
                marker = marker_for(body[i + 2:j])
                if marker is None:
                    ok = False
                else:
                    if cur:
                        parts.append("".join(cur))
                        cur = []
                    parts.append(marker)
                i = j + 1
                continue
            m = re.match(r"[A-Za-z_$][A-Za-z0-9_$]*", body[i + 1:])
            if m:
                marker = marker_for(m.group(0))
                if marker is None:
                    ok = False
                else:
                    if cur:
                        parts.append("".join(cur))
                        cur = []
                    parts.append(marker)
                i += 1 + len(m.group(0))
                continue
            cur.append("$")
            i += 1
            continue
        cur.append(c)
        i += 1
    if cur:
        parts.append("".join(cur))
    return [parts], ok


def analyze_expr(expr, resolver, block, use_pos, seen=None):
    """Evaluate a srcDir expression to (list-of-path-part-lists, resolved)."""
    seen = set() if seen is None else seen
    tokens = TOKEN_RE.findall(expr)
    return ExprParser(tokens, resolver, block, use_pos, seen).parse()


class ExprParser:
    def __init__(self, tokens, resolver, block, use_pos, seen):
        self.tokens = tokens
        self.i = 0
        self.resolver = resolver
        self.block = block
        self.use_pos = use_pos
        self.seen = seen

    def peek(self):
        return self.tokens[self.i] if self.i < len(self.tokens) else None

    def take(self):
        token = self.peek()
        self.i += 1
        return token

    def parse(self):
        values, ok = self.parse_additive()
        if self.i != len(self.tokens):
            ok = False
        return values, ok

    def parse_additive(self):
        values, ok = self.parse_primary()
        while self.peek() == "+":
            self.i += 1
            rhs, ok2 = self.parse_primary()
            if len(values) == 1 and len(rhs) == 1:
                values = [values[0] + rhs[0]]
            else:
                values = values + rhs
            ok = ok and ok2
        return values, ok

    def parse_primary(self):
        token = self.take()
        if token is None:
            return [], False
        if token[0] in "\"'":
            return decode_string_token(token)
        if IDENT_RE.match(token):
            chain = [token]
            while self.peek() == "." and self.i + 1 < len(self.tokens) \
                    and IDENT_RE.match(self.tokens[self.i + 1]):
                self.i += 1
                chain.append(self.take())
            if self.peek() == "(":
                self.i += 1
                args, ok = self.parse_args()
                return self.apply_call(chain, args, ok)
            return self.apply_bare(chain)
        if token == "(":
            values, ok = self.parse_additive()
            if self.peek() == ")":
                self.i += 1
            else:
                ok = False
            return values, ok
        if token == "[":
            values = []
            ok = True
            if self.peek() == "]":
                self.i += 1
                return values, True
            while True:
                item, ok2 = self.parse_additive()
                values = values + item
                ok = ok and ok2
                if self.peek() == ",":
                    self.i += 1
                    continue
                if self.peek() == "]":
                    self.i += 1
                    break
                ok = False
                break
            return values, ok
        return [], False

    def parse_args(self):
        args = []
        ok = True
        if self.peek() == ")":
            self.i += 1
            return args, True
        while True:
            values, ok2 = self.parse_additive()
            args.append(values)
            ok = ok and ok2
            if self.peek() == ",":
                self.i += 1
                continue
            if self.peek() == ")":
                self.i += 1
                break
            ok = False
            while self.peek() not in (None, ")"):
                self.i += 1
            if self.peek() == ")":
                self.i += 1
            break
        return args, ok

    def flatten(self, args):
        values = []
        for item in args:
            values = values + item
        return values

    def apply_call(self, chain, args, ok):
        name = ".".join(chain)
        if name in WRAPPER_CALLS:
            return self.flatten(args), ok
        if name in BUILD_CALLS:
            return [[BUILD_MARK]], ok
        if name in BUILD_DIR_CALLS:
            values = []
            for item in args:
                if not item:
                    values.append([BUILD_MARK])
                for parts in item:
                    values.append([BUILD_MARK] + parts)
            return values or [[BUILD_MARK]], ok
        if name.endswith("File") or name in ("File", "java.io.File"):
            if args and args[0] and args[0][0] in ([MODULE_MARK], [ROOT_MARK]):
                base = args[0][0][0]
                values = []
                for item in args[1:]:
                    for parts in item:
                        values.append([base] + parts)
                return values, ok
            return self.flatten(args), ok
        # Unknown call: its string arguments are not trustworthy as paths.
        return self.flatten(args), False

    def apply_bare(self, chain):
        name = ".".join(chain)
        if name in BUILD_BARE:
            return [[BUILD_MARK]], True
        if name in MODULE_BARE:
            return [[MODULE_MARK]], True
        if name in ROOT_BARE:
            return [[ROOT_MARK]], True
        if len(chain) == 1:
            decl = self.resolver.resolve(
                chain[0], self.use_pos, self.block)
            if decl is not None and decl["pos"] not in self.seen:
                return analyze_expr(
                    decl["expr"], self.resolver, decl["block"],
                    decl["pos"], self.seen | {decl["pos"]})
        return [], False


def parts_to_path(parts, module_dir, root_dir):
    text = "".join(parts)
    build_dir = os.path.normpath(os.path.join(module_dir, "build"))
    if BUILD_MARK in text:
        if not text.startswith(BUILD_MARK):
            return None
        rest = text[len(BUILD_MARK):].lstrip("/\\")
        return os.path.normpath(os.path.join(build_dir, rest)) if rest \
            else build_dir
    if MODULE_MARK in text:
        if not text.startswith(MODULE_MARK):
            return None
        rest = text[len(MODULE_MARK):].lstrip("/\\")
        return os.path.normpath(os.path.join(module_dir, rest)) if rest \
            else os.path.normpath(module_dir)
    if ROOT_MARK in text:
        if not text.startswith(ROOT_MARK):
            return None
        rest = text[len(ROOT_MARK):].lstrip("/\\")
        return os.path.normpath(os.path.join(root_dir, rest)) if rest \
            else os.path.normpath(root_dir)
    if os.path.isabs(text):
        return os.path.normpath(text)
    return os.path.normpath(os.path.join(module_dir, text))


def _under(path, parent):
    try:
        return os.path.commonpath((path, parent)) == parent
    except ValueError:
        return False


def module_path_problem(path, module_dir, module_roots, root_dir):
    """Classify a srcDir path as safe/problem/unresolved.

    In-module paths keep the historical rules.  A path that escapes its
    module is resolved against the settings-derived module roots: another
    module's pre-pruned `build/` tree or test source set is unsafe, another
    module's production source root is safe, and anything else inside the
    repo is unresolved (fail closed).
    """
    module_dir = os.path.normpath(module_dir)
    path = os.path.normpath(path)
    build_dir = os.path.join(module_dir, "build")
    if path == build_dir or path.startswith(build_dir + os.sep):
        return "problem"
    rel = os.path.relpath(path, module_dir)
    if rel != os.pardir and not rel.startswith(os.pardir + os.sep):
        parts = rel.split(os.sep)
        # An ancestor of `src/test*` puts the whole test tree on the main
        # classpath.  `src` itself also contains every test source set.
        if rel == os.curdir or parts == ["src"]:
            return "problem"
        # Discovery never walks these cache/vendor trees, so a srcDir inside
        # one is invisible to every downstream surface: fail closed.
        if any(part in (".git", ".gradle", "node_modules") for part in parts):
            return "unresolved"
        if parts[0] == "build":
            return "problem"
        if parts[0] == "src":
            if len(parts) < 2:
                return "problem"
            if re.match(r"(?:test|androidTest)", parts[1]):
                return "problem"
        return "safe"
    if not _under(path, root_dir):
        return "unresolved"
    if module_roots is None:
        return "unresolved"
    for base in sorted(module_roots, key=len, reverse=True):
        base = os.path.normpath(base)
        if not _under(path, base):
            continue
        if path == base:
            return "unresolved"
        if _under(path, os.path.join(base, "build")):
            return "problem"
        source = os.path.join(base, "src")
        if _under(path, source):
            relsrc = os.path.relpath(path, source)
            if relsrc == os.curdir:
                return "problem"
            first = relsrc.split(os.sep)[0]
            if re.match(r"(?:test|androidTest)", first):
                return "problem"
            return "safe"
        return "unresolved"
    return "unresolved"


class Block:
    __slots__ = ("open", "close", "parent", "header", "shape_header",
                 "long_header", "shape_long_header")

    def __init__(self, open_index, parent, header, shape_header,
                 long_header, shape_long_header):
        self.open = open_index
        self.close = None
        self.parent = parent
        self.header = header
        self.shape_header = shape_header
        self.long_header = long_header
        self.shape_long_header = shape_long_header


def build_blocks(keep, shape):
    blocks = []
    stack = []
    for i, char in enumerate(shape):
        if char == "{":
            header_start = max(
                shape.rfind("{", 0, i), shape.rfind("}", 0, i),
                shape.rfind(";", 0, i), shape.rfind("\n", 0, i)) + 1
            long_start = max(
                shape.rfind("{", 0, i), shape.rfind("}", 0, i),
                shape.rfind(";", 0, i)) + 1
            parent = stack[-1] if stack else None
            block = Block(
                i, parent, keep[header_start:i].strip(),
                shape[header_start:i].strip(),
                keep[long_start:i].strip(), shape[long_start:i].strip())
            blocks.append(block)
            stack.append(block)
        elif char == "}":
            if stack:
                stack.pop().close = i
    return blocks


def block_at(blocks, pos):
    best = None
    for block in blocks:
        if block.open < pos and (block.close is None or pos < block.close):
            if best is None or block.open > best.open:
                best = block
    return best


def source_set_candidates(text, shape_text, allow_bare=False):
    """Return all source-set names mentioned as real code in `text`.

    Matching is done on a length-preserving backtick-normalised copy, so
    `sourceSets.`getByName`("test")` is the same call as the unquoted form.
    The shape check rejects matches that begin inside a string literal.
    """
    norm = text.replace("`", " ")
    qshape = blank_quoted_len(text)
    candidates = []
    patterns = (SOURCESET_CALL, SOURCESET_INDEX)
    for pattern in patterns:
        for match in pattern.finditer(norm):
            if qshape[match.start()].strip():
                candidates.append((match.start(), match.group(1)))
    for match in SOURCESET_PROP.finditer(norm):
        if not qshape[match.start()].strip():
            continue
        name = match.group(1)
        if name not in ("getByName", "create", "maybeCreate", "named",
                        "findByName"):
            candidates.append((match.start(), name))
    if allow_bare:
        for match in SOURCESET_BARE_CALL.finditer(norm):
            if qshape[match.start()].strip():
                candidates.append((match.start(), match.group(1)))
    return candidates


def source_set_from_text(text, shape_text, allow_bare=False):
    candidates = source_set_candidates(text, shape_text, allow_bare)
    return max(candidates)[1] if candidates else None


def is_source_sets_block(block):
    return bool(re.search(r"(?<![\w$])sourceSets\s*$", block.header))


def source_set_receiver_header(text):
    """True when a block header is a sourceSets receiver chain, not an
    arbitrary lambda that merely mentions sourceSets in an argument."""
    return bool(re.match(r"\s*(?:android\s*\.\s*)?sourceSets\b", text))


def block_source_set(block):
    context = None
    if source_set_receiver_header(block.header):
        context = source_set_from_text(block.header, block.shape_header)
    if not context and (APPLY_HEADER.search(block.header)
                        or block.header.strip() in (")", "]", "}")):
        allow_bare = (block.parent is not None
                      and is_source_sets_block(block.parent))
        apply_match = re.search(
            r"(?:^|\.)\s*apply\s*$", block.long_header)
        if apply_match:
            # Only the receiver chain directly left of `.apply`; never an
            # earlier statement in the same block (G-24).
            start = receiver_chain_start(
                block.shape_long_header, apply_match.start())
            context = source_set_from_text(
                block.long_header[start:apply_match.start()],
                block.shape_long_header[start:apply_match.start()],
                allow_bare=allow_bare)
        else:
            context = source_set_from_text(
                block.long_header, block.shape_long_header,
                allow_bare=allow_bare)
    if context:
        return context
    if block.parent is not None and is_source_sets_block(block.parent):
        context = source_set_from_text(
            block.header, block.shape_header, allow_bare=True)
        if context:
            return context
        match = re.match(r"\s*([A-Za-z_$][\w$]*)\s*$", block.header)
        if match and match.group(1) not in ("sourceSets", "val", "var",
                                            "def"):
            return match.group(1)
    return None


def _matching_open(shape, close_index):
    pairs = {")": "(", "]": "["}
    opener = pairs.get(shape[close_index])
    if opener is None:
        return None
    depth = 0
    i = close_index
    while i >= 0:
        char = shape[i]
        if char == shape[close_index]:
            depth += 1
        elif char == opener:
            depth -= 1
            if depth == 0:
                return i
        i -= 1
    return None


def receiver_chain_start(shape, pos):
    """Start of the receiver chain immediately left of `pos`.

    Walks left over `.ident`, calls and balanced brackets.  A newline is only
    crossed when the dot that introduced the continuation was already
    consumed; this keeps D1's multi-line receiver while refusing to absorb a
    complete earlier statement (G-24).
    """
    i = pos
    crossed_dot = False
    while i > 0:
        j = i - 1
        saw_newline = False
        while j >= 0 and shape[j].isspace():
            if shape[j] == "\n":
                saw_newline = True
            j -= 1
        if j < 0 or shape[j] in ";{}":
            return i
        if saw_newline and not crossed_dot and shape[j] != ".":
            return i
        crossed_dot = False
        char = shape[j]
        if char == ".":
            i = j
            crossed_dot = True
            continue
        if char in ")]":
            open_index = _matching_open(shape, j)
            if open_index is None:
                return i
            k = open_index - 1
            while k >= 0 and shape[k].isspace():
                k -= 1
            if k >= 0 and (shape[k].isalnum() or shape[k] in "_$"):
                while k >= 0 and (shape[k].isalnum() or shape[k] in "_$"):
                    k -= 1
                i = k + 1
            else:
                i = open_index
            continue
        if char.isalnum() or char in "_$":
            while j >= 0 and (shape[j].isalnum() or shape[j] in "_$"):
                j -= 1
            i = j + 1
            continue
        return i
    return i


def source_set_context(keep, shape, blocks, pos):
    # Prefer a provably enclosing source-set block.  This is the actual
    # lexical context of the call; mentions in other statements of the block
    # must not override it (G-24).
    current = block_at(blocks, pos)
    while current is not None:
        context = block_source_set(current)
        if context:
            return context
        current = current.parent

    # No enclosing source-set block: inspect only the receiver chain directly
    # left of the srcDir token (multi-line receivers included).
    start = receiver_chain_start(shape, pos)
    candidates = source_set_candidates(
        keep[start:pos], shape[start:pos])
    names = {name for _, name in candidates}
    if len(names) > 1:
        # More than one source-set receiver in one chain is ambiguous.
        return None
    if names:
        return names.pop()
    return None


class ValResolver:
    def __init__(self, declarations, assignments):
        self.declarations = declarations
        self.assignments = assignments

    def resolve(self, name, use_pos, use_block):
        best = None
        for decl in self.declarations:
            if decl["name"] != name or decl["pos"] >= use_pos:
                continue
            block = decl["block"]
            if block is not None and not (
                    block.open < use_pos
                    and (block.close is None or use_pos < block.close)):
                continue
            if best is None or decl["pos"] > best["pos"]:
                best = decl
        if best is None:
            return None
        # A `var`/`def` may have been changed before the use.  Any assignment
        # between the declaration and the use could be on the reaching path
        # (including a nested lambda), so refuse to resolve it: fail closed.
        if best.get("kind") in ("var", "def"):
            for assignment in self.assignments:
                if assignment["name"] != name:
                    continue
                if best["pos"] < assignment["pos"] < use_pos:
                    return None
        return best


def collect_declarations(code, keep, blocks):
    declarations = []
    name_spans = []
    # Match on the string-preserving view so the `\s*` after `=` cannot run
    # through a blanked string literal; reject a `val` that lives inside a
    # string because `code` is blank there.
    for match in VAL_DECL.finditer(keep):
        if not code[match.start()].strip():
            continue
        end = expression_end(code, keep, match.end())
        kind_match = re.match(r"(val|var|def)\b", keep[match.start():])
        declarations.append({
            "name": match.group(1),
            "kind": kind_match.group(1) if kind_match else "val",
            "expr": keep[match.end():end].strip(),
            "pos": match.start(),
            "block": block_at(blocks, match.start()),
        })
        name_spans.append((match.start(1), match.end(1)))
    return declarations, name_spans


def collect_assignments(code, declarations, name_spans, blocks):
    names = {decl["name"] for decl in declarations}
    assignments = []
    for match in NAME_ASSIGN.finditer(code):
        name = match.group(1)
        if name not in names:
            continue
        if any(start <= match.start() < end for start, end in name_spans):
            # The `=` in a declaration is initialisation, not assignment.
            continue
        assignments.append({
            "name": name,
            "pos": match.start(),
            "block": block_at(blocks, match.start()),
        })
    return assignments


def main_srcdir_lines(text, build_path):
    """Return (unsafe_lines, unresolved_lines) for a Gradle build file."""
    module_dir = os.path.dirname(build_path)
    keep = strip_comments_len(text)
    code = blank_strings_len(keep)
    blocks = build_blocks(keep, code)
    declarations, decl_name_spans = collect_declarations(code, keep, blocks)
    assignments = collect_assignments(
        code, declarations, decl_name_spans, blocks)
    resolver = ValResolver(declarations, assignments)
    unsafe = []
    unresolved = []

    def is_decl_name(pos):
        return any(start <= pos < end for start, end in decl_name_spans)

    def check(start, expr):
        context = source_set_context(keep, code, blocks, start)
        # A test source set's own srcDir is legitimate (P4).  An unknown
        # receiver is treated as main: fail closed rather than skip.
        if context and re.match(r"(?:test|androidTest)", context):
            return
        if not expr.strip():
            return
        values, ok = analyze_expr(
            expr, resolver, block_at(blocks, start), start)
        lineno = text.count("\n", 0, start) + 1
        if not ok:
            unresolved.append(lineno)
            return
        for parts in values:
            path = parts_to_path(parts, module_dir, root)
            if path is None:
                unresolved.append(lineno)
                return
            status = module_path_problem(
                path, module_dir, module_root_set, root)
            if status == "problem":
                unsafe.append(lineno)
                return
            if status == "unresolved":
                unresolved.append(lineno)
                return

    for match in SRCDIR_TOKEN.finditer(code):
        if is_decl_name(match.start()):
            # Typed or untyped declaration (`val srcDir: String = …`): it is
            # not a source-dir argument (G-27).
            continue
        # A parenthesised call may put `(` after whitespace/newlines.
        k = match.end()
        while k < len(keep) and keep[k].isspace():
            k += 1
        if k < len(keep) and keep[k] == "(":
            end = balanced_end(code, k)
            check(match.start(), keep[k + 1:end])
            continue
        # Groovy command syntax has no parentheses: `srcDir 'src/test'`.
        # It must stay on the same physical line; a following `:`, `,`, `)`,
        # `]`, `}`, `=` or property dot is a parameter/type annotation/named
        # argument, not a source-dir argument.
        j = match.end()
        while j < len(keep) and keep[j] in " \t\r":
            j += 1
        if j >= len(keep) or keep[j] == "\n" or keep.startswith("+=", j) \
                or keep[j] in "=.,)]}:":
            continue
        end = expression_end(code, keep, match.end())
        newline = keep.find("\n", match.end())
        if newline >= 0:
            end = min(end, newline)
        expr = keep[match.end():end].strip()
        if expr:
            # Command-syntax arguments are comma separated; treat them like a
            # list so `srcDirs 'src/main/java', 'extra/java'` resolves.
            check(match.start(), "listOf(" + expr + ")")

    for match in SRCDIR_ASSIGN.finditer(code):
        if is_decl_name(match.start()):
            # `val srcDir = …` declares a value; it is not a source-dir
            # assignment.  Its uses are resolved through the scope resolver.
            continue
        end = expression_end(code, keep, match.end())
        check(match.start(), keep[match.end():end])

    return unsafe, unresolved

# Comment/whitespace normalisation view: strip `/*…*/` and `//…` (newlines
# preserved so diagnostics keep their line numbers), collapse spaces/tabs
# around dots, and normalise empty getter parens. This catches legal spellings
# such as `namespace /*x*/ = "…"`, `dev.librepocket./*x*/mcp`,
# `manifest . srcFile` and `getManifest ( ) . srcFile`; raw text is scanned
# too (fail-closed on tokens that only appear in comments).
COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)
DOTS = re.compile(r"[^\S\n]*\.[^\S\n]*")
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
    for lineno, line in enumerate(text.split("\n"), 1):
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
        text = handle.read()
    # JVM physical line terminators only: CRLF/CR become LF, and only LF ends a
    # line. Form feed is ordinary whitespace (never `splitlines()`).
    return text.replace("\r\n", "\n").replace("\r", "\n")


def toml_escape_decode(text):
    """Decode TOML basic-string escapes `\\uXXXX` and `\\UXXXXXXXX`.

    Only the two Unicode escapes matter to the MCP scan; every other sequence
    is copied verbatim.  A backslash is eligible only after an even run of
    contiguous backslashes (a `\\\\u…` escaped backslash is not an escape).
    """
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
            if (k - i) % 2 == 0:
                if text.startswith("\\u", k):
                    m = k + 2
                    digits = text[m:m + 4]
                    if (len(digits) == 4 and
                            all(c in "0123456789abcdefABCDEF"
                                for c in digits)):
                        out.append(chr(int(digits, 16)))
                        k = m + 4
                        continue
                elif text.startswith("\\U", k):
                    m = k + 2
                    digits = text[m:m + 8]
                    if (len(digits) == 8 and
                            all(c in "0123456789abcdefABCDEF"
                                for c in digits)):
                        point = int(digits, 16)
                        if point <= 0x10FFFF:
                            out.append(chr(point))
                            k = m + 8
                            continue
            out.append(text[k])
            k += 1
        i = k
    return "".join(out)


def toml_decode(text):
    """Decode Unicode escapes inside TOML BASIC strings only.

    TOML literal strings (`'…'`, `'''…'''`) process no escapes, so they are
    copied verbatim (a literal `\\u002e` must not become a dot).  Comments,
    keys and bare values are copied verbatim too; the caller still scans the
    RAW text as a fail-closed backstop, so nothing needs to be decoded there.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith('"""', i):
            j = i + 3
            body = []
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    body.append(text[j:j + 2])
                    j += 2
                    continue
                if text.startswith('"""', j):
                    break
                body.append(text[j])
                j += 1
            out.append('"""')
            out.append(toml_escape_decode("".join(body)))
            if text.startswith('"""', j):
                k = j + 3
                while k < n and text[k] == '"':
                    k += 1
                out.append(text[j:k])
                j = k
            out.append('"')
            i = j
            continue
        if text.startswith("'''", i):
            j = i + 3
            while j < n and not text.startswith("'''", j):
                j += 1
            j = min(n, j + 3)
            while j < n and text[j] == "'":
                j += 1
            out.append(text[i:j])
            i = j
            continue
        c = text[i]
        if c == "#":
            j = text.find("\n", i)
            if j < 0:
                j = n
            out.append(text[i:j])
            i = j
            continue
        if c == '"':
            j = i + 1
            body = []
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    body.append(text[j:j + 2])
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    break
                body.append(text[j])
                j += 1
            out.append('"')
            out.append(toml_escape_decode("".join(body)))
            if j < n and text[j] == '"':
                out.append('"')
                j += 1
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n and text[j] not in ("'", "\n"):
                j += 1
            if j < n and text[j] == "'":
                j += 1
            out.append(text[i:j])
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def main():
    failed = False
    if module_roots_error is not None:
        print("cannot determine Gradle module roots (fail closed): "
              f"{module_roots_error}")
        failed = True
    has_ph = False
    mcp_hits = []
    src_hits = []
    ns_hits = []
    prop_hits = []
    toml_hits = []
    srcdir_hits = []
    srcdir_unresolved_hits = []

    for path in gradle_files:
        try:
            text = unicode_escape_decode(read(path))
        except Exception as exc:
            print(f"cannot read gradle file (fail closed): {rel(path)}: {exc}")
            failed = True
            continue
        original_lines = text.split("\n")
        normalized = normalize(text)
        # Scan both the raw text (fail-closed on tokens in comments/strings)
        # and the comment/dot-normalised view (catches legal obfuscation such
        # as `namespace /*x*/ =` or `manifest . srcFile`). Line numbers and
        # the reported source line come from the original file.
        for view in (text, normalized):
            for lineno, line in enumerate(view.split("\n"), 1):
                original = (original_lines[lineno - 1]
                            if lineno - 1 < len(original_lines) else line)
                where = f"{rel(path)}:{lineno}:{original}"
                if PH_TOKEN in line.lower():
                    has_ph = True
                if mcp_token_in(line) and where not in mcp_hits:
                    mcp_hits.append(where)
                if SRC.search(line) and where not in src_hits:
                    src_hits.append(where)
                if ns_hit(line) and where not in ns_hits:
                    ns_hits.append(where)

            # Cross-line namespace/applicationId: the value may sit on the
            # next line (`namespace =\n    "dev.librepocket.mcp"`). NS uses
            # \s*, which already spans newlines; comments are blanked in the
            # normalised view, so `namespace = /*x*/\n "…"` is covered too.
            for match in ns_matches(view):
                lineno = view.count("\n", 0, match.start()) + 1
                original = (original_lines[lineno - 1]
                            if lineno - 1 < len(original_lines) else "")
                where = f"{rel(path)}:{lineno}:{original}"
                if where not in ns_hits:
                    ns_hits.append(where)

        unsafe, unresolved = main_srcdir_lines(text, path)
        for lineno in unsafe:
            original = (original_lines[lineno - 1]
                        if lineno - 1 < len(original_lines) else "")
            where = f"{rel(path)}:{lineno}:{original}"
            if where not in srcdir_hits:
                srcdir_hits.append(where)
        for lineno in unresolved:
            original = (original_lines[lineno - 1]
                        if lineno - 1 < len(original_lines) else "")
            where = f"{rel(path)}:{lineno}:{original}"
            if where not in srcdir_unresolved_hits:
                srcdir_unresolved_hits.append(where)

    for path in properties_files:
        try:
            raw = read(path)
            text = unicode_escape_decode(raw)
        except Exception as exc:
            print("cannot read gradle.properties (fail closed): "
                  f"{rel(path)}: {exc}")
            failed = True
            continue
        for lineno, line in enumerate(text.split("\n"), 1):
            if mcp_token_in(line):
                prop_hits.append(f"{rel(path)}:{lineno}:{line}")
        # Properties joins `\\`-continued physical lines before decoding
        # Unicode escapes; scan that logical view too, otherwise a literal
        # split as `mc\\` + `p.McpServer` is invisible.
        try:
            for lineno, joined in logical_properties_lines(raw):
                line = unicode_escape_decode(joined)
                if mcp_token_in(line):
                    hit = f"{rel(path)}:{lineno}:{line}"
                    if hit not in prop_hits:
                        prop_hits.append(hit)
        except Exception as exc:
            print("cannot join gradle.properties lines (fail closed): "
                  f"{rel(path)}: {exc}")
            failed = True

    for path in toml_files:
        try:
            raw = read(path)
        except Exception as exc:
            print("cannot read build catalog (fail closed): "
                  f"{rel(path)}: {exc}")
            failed = True
            continue
        # TOML-aware decode: escapes are processed only inside basic strings,
        # so `\uXXXX`/`\UXXXXXXXX` resolve like Gradle's tomllj parser while a
        # literal string's `\u…` stays literal.  The raw file is scanned too as
        # a fail-closed backstop (a literal/comment mentioning the FQN fails).
        try:
            decoded = toml_decode(raw)
        except Exception as exc:
            print("cannot decode build catalog (fail closed): "
                  f"{rel(path)}: {exc}")
            failed = True
            continue
        for view in (raw, decoded):
            for lineno, line in enumerate(view.split("\n"), 1):
                if mcp_token_in(line):
                    hit = f"{rel(path)}:{lineno}:{line}"
                    if hit not in toml_hits:
                        toml_hits.append(hit)

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
    if toml_hits:
        print("gradle version catalog references MCP components (fail closed):")
        for hit in toml_hits:
            print(hit)
    if srcdir_hits:
        print("main source set adds a test/build source dir (fail closed):")
        for hit in srcdir_hits:
            print(hit)
    if srcdir_unresolved_hits:
        print("main source set source dir cannot be resolved to a safe path "
              "(fail closed):")
        for hit in srcdir_unresolved_hits:
            print(hit)

    caught = bool(mcp_hits or src_hits or ns_hits or prop_hits or toml_hits
                  or srcdir_hits or srcdir_unresolved_hits)
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
# Delegated to scripts/mcp_scan.py `outbound` (shared lexer; fail-closed if the
# helper or python3 is missing). Each `;`-separated import declaration is
# parsed and classified INDEPENDENTLY on a comment/string-stripped,
# Java-Unicode-decoded view: two individually allowlisted imports on one
# physical line PASS (the old `^…$`-per-line test false-red them), while
# `import X; class Y { outbound FQN }` is still scanned. Imports are normalised
# (comments/whitespace around dots, backticks, `static`) before the allowlist
# test, so `import dev . librepocket . router.X` (legal Java/Kotlin) cannot fall
# through an exact-prefix `case` and its early `continue`. Java declarations end
# at `;` and may span physical lines (`import dev.\nlibrepocket.router.X;`),
# Kotlin declarations end at `;`/end of line. Validated imports and the package
# declaration are removed, then the remainder is scanned for outbound
# `dev.librepocket.*` references on a code view (comments+strings blanked,
# dots/backticks normalised) plus a string-kept view (comments blanked), so a
# string literal `"/*"` cannot desynchronise a comment stripper and swallow an
# FQN. The string view is scanned twice: raw (a contiguous FQN always matches)
# and normalised (backticks removed, same-line `/*…*/` blanked, JVM whitespace
# around dots collapsed) so the legal spellings `"dev . librepocket . X"`,
# `"dev.`librepocket`.X"` and `"dev/*x*/.librepocket.X"` cannot hide it. The
# only allowed outward
# types are KeyVault + the 7 tool contract types (and static members/nested
# types of those types); a subpackage may import a parent-package mcp type;
# star imports to dev.librepocket.* fail; the identifier-aware self-mention
# strip (full JVM identifier-part predicate) keeps `dev.librepocket.mcpfoo`,
# `…mcp$Compat`, `…mcpé`, `…mcp\u0301`, `…mcp\u200d` and `…mcp€X` outbound.
# No KDoc `[`/`<` prefix strip is applied:
# comments are already blanked in both views, so a generic argument, grouped
# annotation or bracketed string is real code/string content and must be
# scanned (stripping it hid `List<dev.librepocket.…>` and `@[…]`).
# N-B3 residual: an FQN split across physical lines is not joined.
if [ "${#IN_PKG_FILES[@]}" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    report "python3 not available for in-package outbound scan (fail closed)"
  elif [ ! -r "$MCP_SCAN" ]; then
    report "helper scripts/mcp_scan.py missing or unreadable (fail closed)"
  else
    set +e
    MCP_OB="$(python3 "$MCP_SCAN" outbound "$ROOT" \
      "${IN_PKG_FILES[@]}" 2>&1)"
    OB_RC=$?
    set -e
    if [ "$OB_RC" -ge 2 ]; then
      report "in-package outbound scan failed (fail closed, rc=$OB_RC)"
    elif [ "$OB_RC" -eq 1 ] && [ -z "$MCP_OB" ]; then
      # rc 1 means "findings printed"; with no output it is a helper crash, not
      # a clean run (fail closed, never a silent pass).
      report "in-package outbound scan failed (fail closed, rc=1 with no findings)"
    fi
    if [ -n "$MCP_OB" ]; then
      while IFS= read -r msg; do
        [ -n "$msg" ] || continue
        report "$msg"
      done <<< "$MCP_OB"
    fi
  fi
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
# Python normaliser: a small recursive lexer blanks // and same-line /*…*/
# comments only outside strings (newlines preserved, so an FQN split by a
# physical newline is not joined — documented N-B3 residual), keeps
# string contents (so a URL `https://…` cannot truncate the line and hide a
# later FQN), understands Kotlin `${…}` templates and Java text-block escapes,
# decodes JLS `\uXXXX` escapes for Java (so `dev\u0020.librepocket…` is joined
# on the same physical line), then removes backticks and collapses same-line
# whitespace around dots.
# This is needed because the same Java/Kotlin code also compiles as
# `dev.librepocket.`mcp`.Transport`, `dev.librepocket./*x*/mcp.Transport` and
# `dev . librepocket . mcp`. Residual: an FQN split across physical lines is
# not joined (C4 scans line by line, including when `\u000a`/`\u000d` produces
# the split); a comment inside a raw string template
# is string content, not code, and is deliberately not parsed as a comment.
# N-B1 accepted cost: the bare `Mcp[A-Z]` substring is deliberately wide — a
# third-party string containing e.g. `McpCompat` also turns this red
# (fail-closed direction, not tightened: a token-boundary rule cannot
# distinguish com.x.McpCompat from a real McpServer reference without
# full parsing, and a missed wiring reference is the worse failure mode).
# The FQN half carries a full JVM identifier-aware trailing boundary so
# lookalike packages such as `dev.librepocket.mcpfoo`,
# `dev.librepocket.mcp$Compat`, `dev.librepocket.mcpé`,
# `dev.librepocket.mcp\u0301`, `dev.librepocket.mcp\u200d` and
# `dev.librepocket.mcp€X` are not the mcp package; `Mcp[A-Z]` stays
# deliberately wide. ---
if [ "${#OUTSIDE_FILES[@]}" -gt 0 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    report "python3 not available for production source scan (fail closed)"
  else
    set +e
    MCP_OUT="$(python3 - "$ROOT" ${OUTSIDE_FILES[@]+"${OUTSIDE_FILES[@]}"} <<'PY'
import os
import re
import sys
import unicodedata

root = sys.argv[1]
files = sys.argv[2:]

# Same identifier-aware trailing boundary as the manifest raw-text scan and
# C2b (G-11): `$`, `_`, alphanumerics, currency symbols (Sc), connecting
# punctuation (Pc), combining marks (Mn/Mc) and format/ignorable controls (Cf)
# all continue the package name, so `dev.librepocket.mcpfoo`, `…mcp$Compat`,
# `…mcpé`, `…mcp\u0301`, `…mcp\u200d` and `…mcp€X` are different packages.
# `Mcp[A-Z]` stays deliberately wide (fail closed).
_IDENT_EXTRA = frozenset({"Mn", "Mc", "Cf", "Pc", "Sc"})


def is_ident_part(c):
    if c == "$" or c == "_":
        return True
    category = unicodedata.category(c)
    if category[0] == "L" or category in ("Nd", "Nl"):
        return True
    if category in _IDENT_EXTRA:
        return True
    o = ord(c)
    return o <= 0x08 or 0x0E <= o <= 0x1B or 0x7F <= o <= 0x9F


def mcp_token_in(text):
    prefix = "dev.librepocket.mcp"
    i = 0
    while True:
        j = text.find(prefix, i)
        if j < 0:
            break
        k = j + len(prefix)
        if k >= len(text) or not is_ident_part(text[k]):
            return True
        i = k
    return bool(re.search(r"Mcp[A-Z]", text))
# JVM whitespace around a dot (space, tab, form feed, vertical tab) collapses;
# a line terminator does not (that would join separate lines).
DOTS = re.compile(r"[^\S\n]*\.[^\S\n]*")


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


def java_unicode_decode(text):
    # JLS 3.3: javac translates \uXXXX before tokenising.  A backslash is
    # eligible only after an even run of contiguous backslashes; invalid hex
    # stays literal.  Decoding here lets DOTS collapse a whitespace escape that
    # was injected inside a FQN (`dev\u0020.librepocket…`).
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
        # still point at the original line. CRLF/CR are normalised to LF and
        # only `\n` splits a line: form feed is JVM whitespace, not a line
        # terminator, so a reference on one physical line is never cut in two.
        if "\r" in text:
            text = text.replace("\r\n", "\n").replace("\r", "\n")
        java = path.endswith(".java")
        original_lines = text.split("\n")
        # JLS 3.3: javac translates \uXXXX before tokenising, so an escape can
        # inject whitespace into a FQN (`dev\u0020.librepocket…`).  Decode
        # before sanitising; DOTS below then collapses the injected
        # space/tab/form-feed.  A decoded line terminator genuinely splits the
        # FQN across physical lines (documented N-B3 residual, not joined).
        if java:
            text = java_unicode_decode(text)
        sanitized = sanitize(text, java)
        for lineno, line in enumerate(sanitized.split("\n"), 1):
            # Normalise legal-but-non-canonical spellings: backticked package
            # segments, blanked /*…*/ comments between segments, and JVM
            # whitespace around dots.
            scan = DOTS.sub(".", line.replace("`", ""))
            if mcp_token_in(scan):
                original = (original_lines[lineno - 1]
                            if lineno - 1 < len(original_lines) else line)
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
