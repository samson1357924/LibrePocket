#!/usr/bin/env python3
r"""mcp_scan.py — stdlib-only helper for the MCP zero-wiring gate.

This is the shared, hardened source view used by scripts/docs_claim_check.sh.
It owns one recursive Kotlin/Java lexer (comments and string/char literals
blanked, newlines preserved) and three CLI surfaces:

  discover ROOT
      Print the absolute path of every production `.kt`/`.java` source file in
      the repository, one per line.  Production = every source file except the
      real Gradle test source sets at `<module>/src/<sourceSet>/…` (sourceSet
      `test*`/`androidTest*`).  Module roots are derived from the root
      `settings.gradle[.kts]` (`include`, `includeBuild`, `projectDir`
      overrides) plus the repo root; with no root settings, only the repo root
      and depth-1 children containing build.gradle[.kts] + `src/` qualify.
      Files outside `*/src` (e.g. a custom `java.srcDir("extra/java")` root)
      ARE included, and so is a production package whose path later contains
      `src/test`.  `.gradle/`, `.git/` and `node_modules/` are pruned;
      `build/` only when it is a real Gradle output root (repo root or a
      settings-derived module root).  Exit 2 on a walk error; exit 0 otherwise
      (possibly no output).

  types ROOT FILE...
      Print the declared type name of every `class`/`object`/`interface`/
      `enum`/`record`/`typealias` (including `enum class`, data/sealed/value
      forms and backticked Kotlin names) found in the comment/string-stripped,
      Java-Unicode-decoded source, one per line.  Exit 2 on a read error.

  outbound ROOT FILE...
      Validate every in-package (`dev.librepocket.mcp(.subpackage)*`) source
      passed in.  Each import declaration is parsed independently (so two
      allowlisted imports on one physical line pass, and `import X; class Y {
      outbound FQN }` is still scanned), normalised (comments/whitespace around
      dots, backticks, `static`) and checked against the exact allowlist; a
      Java declaration may span physical lines and ends at `;`, a Kotlin one
      ends at `;` or end of line.  The remainder is scanned for outbound
      `dev.librepocket.*` references using a code view (comments+strings
      blanked, dots/backticks normalised) plus a string-kept view (comments
      blanked, contiguous `dev.librepocket.` only).  Exit 0 (clean), 1
      (findings printed) or 2 (read/parse failure).

Design notes (why this exists):

* All line iteration is JVM-faithful: `\r\n`/`\r` are normalised to `\n` and
  the text is split on `\n` only.  `str.splitlines()` splits on form feed
  (`\x0c`), which is ordinary Java/Kotlin whitespace (legal around a `.`), so a
  reference on one physical line used to be cut in two and missed.
* Whitespace around `.`: `[^\S\n]*\.[^\S\n]*` collapses every JVM whitespace
  character except a line terminator, so `dev.librepocket.\x0cmcp` normalises.
"""

import os
import re
import sys
import unicodedata

# JVM identifier character classes, shared by the self-mention boundary
# (G-11) and the declared-type-name scanner (G-12).  Java's
# `Character.isJavaIdentifierPart` accepts letters/digits, `$`, `_`, currency
# symbols (`Sc`), connecting punctuation (`Pc`), combining marks (`Mn`/`Mc`)
# and identifier-ignorable controls (`Cf` plus the ISO control ranges).
# Python's `\w` only covers letters/digits/`_`, so a combining mark, ZWJ or
# currency symbol after `mcp` used to look like a package boundary, hiding a
# genuinely different package.
_IDENT_EXTRA_CATEGORIES = frozenset({"Mn", "Mc", "Cf", "Pc", "Sc"})


def is_ident_part(c):
    # Java's Character.isJavaIdentifierPart: `$`/`_`, the Unicode letter
    # categories (L*), decimal digits (Nd), letter numbers (Nl), the extra
    # categories above, and identifier-ignorable controls.  Deliberately NOT
    # `str.isalnum()`: that also accepts category No (e.g. U+00B2 SUPERSCRIPT
    # TWO), which Java does not treat as an identifier part.
    if c == "$" or c == "_":
        return True
    category = unicodedata.category(c)
    if category[0] == "L" or category in ("Nd", "Nl"):
        return True
    if category in _IDENT_EXTRA_CATEGORIES:
        return True
    # Character.isIdentifierIgnorable: ISO controls and format characters.
    o = ord(c)
    return o <= 0x08 or 0x0E <= o <= 0x1B or 0x7F <= o <= 0x9F


# The only `dev.librepocket.*` types the MCP package may import outward.
ALLOWLIST = frozenset({
    "dev.librepocket.keystore.KeyVault",
    "dev.librepocket.tool.CapabilityLevel",
    "dev.librepocket.tool.DenyReason",
    "dev.librepocket.tool.Projection",
    "dev.librepocket.tool.ProjectionContext",
    "dev.librepocket.tool.SideEffect",
    "dev.librepocket.tool.ToolAnnotations",
    "dev.librepocket.tool.ToolDef",
})

MCP_PKG = "dev.librepocket.mcp"

# Deliberately over-approximate (mirrors the gate's historic TYPE_DECL):
# enum/record/typealias cannot be manifest components, but matching extra names
# only turns the gate red (fail-closed).  Applied to the lexed/decoded view.
# Unicode identifiers are legal in Java/Kotlin (`class Tränsport`) and backticked
# Kotlin names may contain almost anything (`class `Transport-X``); both forms
# feed the C2 relative-component name set (over-approximation is fail-closed).
TYPE_KEYWORDS = r"\b(?:enum\s+class|enum|class|object|interface|record|typealias)"
# The name itself is scanned manually with the JVM identifier predicates
# above, because Python regex character classes cannot express the required
# Unicode categories (combining marks, currency symbols, ZWJ).  This pattern
# only finds the keyword and an optional backticked token / `$`-run prefix;
# the plain branch (including `$1Wire`, `$Wire`, `€Wire`, `Tra\u0308nsport`)
# is consumed by `iter_type_names`.
TYPE_DECL = re.compile(TYPE_KEYWORDS + r"\s+(`[^`\n]+`|[$]*)")


def iter_type_names(code):
    """Yield declared type names found in the lexed source view."""
    n = len(code)
    for match in TYPE_DECL.finditer(code):
        token = match.group(1)
        if token.startswith("`"):
            yield token[1:-1]
            continue
        i = match.end()
        # `token` is the `$`-run prefix (possibly empty).
        start = i - len(token)
        while i < n and is_ident_part(code[i]):
            i += 1
        if i == start:
            continue
        yield code[start:i]

# JVM whitespace except a line terminator.
WS = r"[^\S\n]*"
# A raw (un-backticked) JVM/Kotlin identifier: Unicode letters/underscore (or a
# leading `$` run, legal in Java) first, then Unicode word chars / `$`.
# Backticks are accepted around any segment.
NAME = r"[$]*[^\W\d][\w$]*"
SEG = r"`?" + NAME + r"`?"
# Kotlin import aliases may be backticked and contain spaces/punctuation
# (`import dev.librepocket.tool.ToolDef as `T D``) — O-01.
ALIAS = r"(?:`[^`\n]+`|" + SEG + r")"

# An import declaration, tolerant of comments (already blanked to spaces),
# whitespace/backticks around dots, `static`, and a trailing star.  The path is
# captured raw and normalised later.  Two variants, because the languages
# terminate differently:
#   * Java ends at `;`, and the whole declaration may span physical lines
#     (newlines/comments/whitespace are legal anywhere in the qualified name:
#     `import dev.\nlibrepocket.router.X;`).  GAP therefore includes `\n`.
#   * Kotlin ends at `;` or the end of the physical line (kotlinc rejects an
#     import split across lines), so GAP excludes `\n`.
GAP = r"\s*"
IMPORT_JAVA_RE = re.compile(
    r"(?<![\w$`])import\b" + GAP +
    r"(?:static\b" + GAP + r")?"
    r"(" + SEG + r"(?:" + GAP + r"\." + GAP + SEG + r")*"
    r"(?:" + GAP + r"\." + GAP + r"\*)?" + r")" + GAP + r";")
IMPORT_KT_RE = re.compile(
    r"(?<![\w$`])import\b" + WS +
    r"(?:static\b" + WS + r")?"
    r"(" + SEG + r"(?:" + WS + r"\." + WS + SEG + r")*"
    r"(?:" + WS + r"\." + WS + r"\*)?" + r")" +
    r"(?:" + WS + r"\bas\b" + WS + ALIAS + r")?" + WS +
    r"(?:;|\n|$)")

# A package declaration: statement-position `package` up to `;`/end of line.
PKG_SPAN_RE = re.compile(r"(?m)^[ \t]*package\b[^\n;]*")

FQN_DOT = re.compile(r"dev\.librepocket\.")


def rel_to(root, path):
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


def blank(segment):
    # Preserve newlines so diagnostics and line arithmetic stay aligned.
    return "".join(ch if ch == "\n" else " " for ch in segment)


def java_unicode_decode(text):
    # JLS 3.3: translate \\uXXXX before lexing.  A backslash is eligible only
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


def read_text(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    if text.startswith("\ufeff"):
        text = text[1:]
    # JVM physical line terminators: CRLF and CR become LF, and only LF ends a
    # line.  Form feed is ordinary whitespace, NOT a line break.
    if "\r" in text:
        text = text.replace("\r\n", "\n").replace("\r", "\n")
    return text


def add_code(code, keep, segment):
    # Real code: verbatim in both views.
    code.append(segment)
    keep.append(segment)


def add_blank(code, keep, segment):
    # Comments / char literals: blanked in both views (same length).
    code.append(blank(segment))
    keep.append(blank(segment))


def add_string(code, keep, segment):
    # String literal: blanked in the code view, verbatim in the string-kept
    # view.  Both are the same length, so code/keep spans map 1:1.
    code.append(blank(segment))
    keep.append(segment)


def scan_string(text, i, nested, raw, templates, code, keep):
    n = len(text)
    j = i + (3 if raw else 1)
    start = i
    while j < n:
        if raw:
            # Java text blocks are NOT raw: `\"` is an escaped quote, so the
            # quote sequence in `\"""` is not a closing delimiter.  Kotlin raw
            # strings have no escapes (templates=False means Java).
            if not templates and text[j] == "\\" and j + 1 < n:
                j += 2
                continue
            if text.startswith('"""', j):
                j += 3
                while j < n and text[j] == '"':
                    j += 1
                add_string(code, keep, text[start:j])
                return j
            if templates and text.startswith("${", j):
                add_string(code, keep, text[start:j])
                code.append("  ")
                keep.append("${")
                j += 2
                j = scan_code(text, j, nested, code, keep, stop_brace=True)
                if j < n and text[j] == "}":
                    code.append(" ")
                    keep.append("}")
                    j += 1
                start = j
                continue
            j += 1
        else:
            c = text[j]
            if c == "\\" and j + 1 < n and text[j + 1] != "\n":
                j += 2
                continue
            if templates and c == "$" and j + 1 < n and text[j + 1] == "{":
                # Kotlin template: the body is real code, lexed recursively.
                add_string(code, keep, text[start:j])
                code.append("  ")
                keep.append("${")
                j += 2
                j = scan_code(text, j, nested, code, keep, stop_brace=True)
                if j < n and text[j] == "}":
                    code.append(" ")
                    keep.append("}")
                    j += 1
                start = j
                continue
            if c == '"':
                j += 1
                add_string(code, keep, text[start:j])
                return j
            if c == "\n":
                # Unterminated line literal: stop at the newline and leave it
                # for scan_code so line structure is preserved.
                add_string(code, keep, text[start:j])
                return j
            j += 1
    add_string(code, keep, text[start:j])
    return j


def scan_code(text, i, nested, code, keep, stop_brace=False):
    # Remove comments and string/char literals, preserving newlines and length.
    # Kotlin block comments nest; Java's do not.  With stop_brace (template
    # body), track `{`/`}` depth and return at the depth-0 `}` (unconsumed).
    depth = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "/" and text.startswith("//", i):
            j = text.find("\n", i)
            if j == -1:
                j = n
            add_blank(code, keep, text[i:j])
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
            add_blank(code, keep, text[i:j])
            i = j
        elif ch == '"':
            i = scan_string(text, i, nested, text.startswith('"""', i),
                            nested, code, keep)
        elif ch == "`":
            # Kotlin backtick identifier.  Keep the quoted content (and
            # delimiters) verbatim so a real type name such as `Transport-X`
            # reaches C2's TYPE_DECL.  Comments and strings are already blanked,
            # so a backticked token here is real code; a prose token such as
            # `package dev.librepocket.mcp` still cannot forge a package
            # declaration (the classifier's `package` pattern has a lookbehind
            # that rejects a preceding backtick).
            j = text.find("`", i + 1)
            if j == -1:
                seg = "_BT_" + blank(text[i + 1:])
                code.append(seg)
                keep.append(seg)
                i = n
                continue
            content = text[i + 1:j]
            if "\n" in content:
                # A backtick token does not legally span physical lines; blank
                # it to a forge-proof marker while preserving line structure.
                seg = "_BT_" + blank(content)
                code.append(seg)
                keep.append(seg)
            else:
                code.append(text[i:j + 1])
                keep.append(text[i:j + 1])
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
            add_blank(code, keep, text[i:j])
            i = j
        elif stop_brace and ch == "{":
            depth += 1
            add_code(code, keep, ch)
            i += 1
        elif stop_brace and ch == "}":
            if depth == 0:
                return i
            depth -= 1
            add_code(code, keep, ch)
            i += 1
        else:
            add_code(code, keep, ch)
            i += 1
    return i


def lex(text, is_java):
    """Return (code, keep): two equal-length views of `text`.

    `code` blanks comments and string/char literals (newlines preserved);
    `keep` blanks only comments (string contents kept).  Equal length means a
    span located in code maps 1:1 between the two views.
    """
    nested = not is_java
    code = []
    keep = []
    scan_code(text, 0, nested, code, keep)
    return "".join(code), "".join(keep)


def normalize_view(s):
    """Remove backticks and collapse JVM whitespace around dots (no newlines)."""
    out = []
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c == "`":
            i += 1
            continue
        if c == ".":
            while out and out[-1] in " \t\f\v":
                out.pop()
            out.append(".")
            i += 1
            while i < n and s[i] in " \t\f\v":
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


SELF_PREFIX = "dev.librepocket.mcp"


SAME_LINE_BLOCK = re.compile(r"/\*.*?\*/")


def normalized_string_view(s):
    """Normalise the string-kept view the way the old gate normalised a line.

    Backticks are removed, same-line `/*…*/` sequences are blanked (length
    preserved so line/column arithmetic still works) and JVM whitespace around
    dots is collapsed.  Newlines are preserved: an FQN split across physical
    lines stays the documented N-B3 residual.  The raw string view is still
    scanned as a fallback, so a literal `/*` cannot hide a contiguous FQN.
    """
    return normalize_view(SAME_LINE_BLOCK.sub(
        lambda m: " " * len(m.group(0)), s))


def strip_self_mentions(s):
    """Drop boundary-aware self-mentions of the mcp package.

    The boundary uses the full JVM identifier-part predicate, so lookalike
    packages (`dev.librepocket.mcpfoo`, `…mcp$Compat`, `…mcpé`, `…mcp\u0301`,
    `…mcp\u200d`, `…mcp€X`) stay outbound instead of being stripped.
    """
    out = []
    i = 0
    n = len(s)
    prefix_len = len(SELF_PREFIX)
    while True:
        j = s.find(SELF_PREFIX, i)
        if j < 0:
            out.append(s[i:])
            break
        k = j + prefix_len
        if k < n and is_ident_part(s[k]):
            # Not the mcp package: keep it (the FQN_DOT scan will catch it).
            out.append(s[i:k])
            i = k
        else:
            out.append(s[i:j])
            i = k
    return "".join(out)


def strip_string_view(s):
    """Drop boundary-aware self-mentions from the string-kept view.

    Comments are already blanked in this view, so KDoc links cannot reach it.
    A `[`/`<` immediately before an FQN is therefore real string content (for
    example a reflective class name or an annotation/XML payload) and must NOT
    be stripped; doing so hid an outbound reference in `"[dev.librepocket.…]"`.
    """
    return strip_self_mentions(s)


def blank_spans(s, spans):
    if not spans:
        return s
    chars = list(s)
    for start, end in spans:
        for k in range(start, end):
            if chars[k] != "\n":
                chars[k] = " "
    return "".join(chars)


def normalize_path(raw):
    return re.sub(r"[`\s]", "", raw)


def import_allowed(path):
    if path in ALLOWLIST:
        return True
    # A static member (or nested type) of an allowlisted contract type is fine:
    # `import static dev.librepocket.tool.ToolDef.DEFAULT;` imports a member of
    # an allowed type, not a new outbound type.  The owner must still match the
    # allowlist exactly, so `dev.librepocket.tool.ToolDefWhatever` and
    # `dev.librepocket.router.PrivilegeGate.check` stay outbound.
    for allowed in ALLOWLIST:
        if path.startswith(allowed + "."):
            return True
    if path.endswith(".*"):
        # Star imports to dev.librepocket.* (incl. the mcp package) fail.
        return not path.startswith("dev.librepocket.")
    if path == MCP_PKG or path.startswith(MCP_PKG + "."):
        # An mcp subpackage may import a parent/self mcp type.
        return True
    if path.startswith("dev.librepocket."):
        return False
    # Not in the namespace: only accept a path that is clearly some other
    # package.  A bare prefix of the namespace ("dev", "dev.librepocket") must
    # not be treated as allowed — a partial capture would otherwise blank an
    # outbound import and hide it (fail closed).
    if path == "dev" or path == "dev.librepocket":
        return False
    return True


def source_line(lines, lineno):
    if 1 <= lineno <= len(lines):
        return lines[lineno - 1]
    return ""


# --- subcommands -----------------------------------------------------------

TEST_SRC_SET = re.compile(r"(?:^test)|(?:^androidTest)")
SETTINGS_FILES = ("settings.gradle.kts", "settings.gradle")
BUILD_FILES = ("build.gradle", "build.gradle.kts")
INCLUDE_RE = re.compile(r"(?<![\w$.])include\b")
INCLUDE_BUILD_RE = re.compile(r"(?<![\w$.])includeBuild\b")
PROJECT_CALL_RE = re.compile(r"(?<![\w$.])project\s*(?=\()")
QUOTED_RE = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def _strip_gradle_comments(text):
    """Blank `//` and `/*...*/` comments, keeping strings and length."""
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
        if c == "`":
            j = text.find("`", i + 1)
            j = n if j < 0 else j + 1
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


def _call_arguments(text, open_index):
    """Return the balanced text inside the `(` at `open_index`."""
    depth = 0
    i = open_index
    n = len(text)
    quote = None
    triple = False
    while i < n:
        c = text[i]
        if quote is not None:
            if triple:
                if text.startswith(quote * 3, i):
                    quote = None
                    triple = False
                    i += 3
                    continue
                i += 1
                continue
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in "\"'":
            if text.startswith(c * 3, i):
                quote = c
                triple = True
                i += 3
                continue
            quote = c
            i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:i], i + 1
        i += 1
    return text[open_index + 1:], n


def _unquote(token):
    quote = token[0]
    body = token[1:-1]
    if len(body) >= 4 and body.startswith(quote * 2) and \
            body.endswith(quote * 2):
        body = body[2:-2]
    return re.sub(
        r"\\(.)",
        lambda m: {"n": "\n", "t": "\t", "r": "\r"}.get(
            m.group(1), m.group(1)),
        body)


def _inside(path, parent):
    try:
        return os.path.commonpath((path, parent)) == parent
    except ValueError:
        return False


def _settle(path, base):
    path = os.path.normpath(path if os.path.isabs(path)
                            else os.path.join(base, path))
    return path


def _settings_path(directory):
    for name in SETTINGS_FILES:
        candidate = os.path.join(directory, name)
        if os.path.isfile(candidate):
            return candidate
    return None


def _blank_gradle_strings(text):
    """Blank string/backtick contents (newlines kept) for a code-only view."""
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


def _token_view(text):
    """Length-preserving view with each string/backtick token as `S`."""
    out = list(_blank_gradle_strings(text))
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in "\"'`":
            out[i] = "S"
            if c == "`":
                j = text.find("`", i + 1)
                i = n if j < 0 else j + 1
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
                i = j
        else:
            i += 1
    return "".join(out)


def _depth_table(code):
    depth = 0
    table = []
    for char in code:
        table.append(depth)
        if char == "{":
            depth += 1
        elif char == "}":
            depth = max(0, depth - 1)
    return table


def _previous_significant(tokens, pos):
    i = pos - 1
    while i >= 0 and tokens[i].isspace():
        i -= 1
    if i < 0:
        return None
    char = tokens[i]
    if char == ")":
        level = 0
        j = i
        while j >= 0:
            if tokens[j] == ")":
                level += 1
            elif tokens[j] == "(":
                level -= 1
                if level == 0:
                    break
            j -= 1
        if j < 0:
            return ("char", ")")
        k = j - 1
        while k >= 0 and tokens[k].isspace():
            k -= 1
        end = k + 1
        while k >= 0 and (tokens[k].isalnum() or tokens[k] in "_$"):
            k -= 1
        ident = tokens[k + 1:end]
        if ident in ("if", "while", "for", "when", "catch"):
            return ("control", ident)
        return ("char", ")")
    if char == ">" and i > 0 and tokens[i - 1] == "-":
        return ("token", "->")
    if char.isalnum() or char in "_$":
        j = i
        while j >= 0 and (tokens[j].isalnum() or tokens[j] in "_$"):
            j -= 1
        return ("token", tokens[j + 1:i + 1])
    return ("char", char)


def _top_level_statement(code, tokens, depths, pos):
    """True when `pos` is a standalone top-level settings statement."""
    if depths[pos] != 0:
        return False
    start = max(code.rfind("\n", 0, pos), code.rfind(";", 0, pos)) + 1
    if code[start:pos].strip():
        return False
    prev = _previous_significant(tokens, pos)
    if prev is None:
        return True
    kind, value = prev
    if kind == "control":
        return False
    if kind == "token" and value in ("else", "->", "return"):
        return False
    if kind == "char" and value in ("=", "+", ",", "(", "[", ".", ":"):
        return False
    return True


def _split_call_args(args):
    parts = []
    start = 0
    depth = 0
    i = 0
    n = len(args)
    while i < n:
        c = args[i]
        if c in "\"'`":
            if c == "`":
                j = args.find("`", i + 1)
                i = n if j < 0 else j + 1
                continue
            triple = args.startswith(c * 3, i)
            j = i + (3 if triple else 1)
            while j < n:
                if not triple and args[j] == "\\":
                    j += 2
                    continue
                if triple and args.startswith(c * 3, j):
                    j += 3
                    break
                if not triple and args[j] == c:
                    j += 1
                    break
                j += 1
            i = j
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth = max(0, depth - 1)
        elif c == "," and depth == 0:
            parts.append(args[start:i])
            start = i + 1
        i += 1
    parts.append(args[start:])
    if parts and not parts[-1].strip():
        parts.pop()
    return parts


def _literal_string(token):
    quote = token[0]
    body = token[1:-1]
    if len(body) >= 4 and body.startswith(quote * 2) and \
            body.endswith(quote * 2):
        body = body[2:-2]
    i = 0
    while i < len(body):
        c = body[i]
        if c == "\\":
            i += 2
            continue
        if c == "$":
            # Interpolation is not a compile-time constant.
            return None
        i += 1
    return _unquote(token)


def _literal_expr(expr):
    """Evaluate a literal or literal-only `+` concatenation."""
    i = 0
    n = len(expr)
    parts = []
    expect_value = True
    parens = 0
    while True:
        while i < n and expr[i].isspace():
            i += 1
        if i >= n:
            break
        c = expr[i]
        if expect_value:
            if c in "\"'":
                match = QUOTED_RE.match(expr, i)
                if not match:
                    return None
                value = _literal_string(match.group(0))
                if value is None:
                    return None
                parts.append(value)
                i = match.end()
                expect_value = False
            elif c == "(":
                parens += 1
                i += 1
            else:
                return None
        else:
            if c == "+":
                expect_value = True
                i += 1
            elif c == ")" and parens > 0:
                parens -= 1
                i += 1
            else:
                return None
    if expect_value or parens:
        return None
    return "".join(parts)


def _literal_values(args):
    parts = _split_call_args(args)
    if not parts:
        return None
    values = []
    for part in parts:
        value = _literal_expr(part)
        if value is None:
            return None
        values.append(value)
    return values


def _expression_end(code, keep, start):
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
            saw = True
        i += 1
    return n


def _project_dir_literal(rhs):
    rhs = rhs.strip()
    match = re.match(
        r"(?:file|new\s+File|layout\s*\.\s*projectDirectory\s*\.\s*dir)"
        r"\s*\(", rhs)
    if not match:
        return None
    open_index = match.end() - 1
    args, close = _call_arguments(rhs, open_index)
    if rhs[close:].strip():
        return None
    values = _literal_values(args)
    if values is None or len(values) != 1 or not values[0]:
        return None
    return values[0]


def _parse_settings(directory, roots, visited, depth=0):
    """Add the modules declared by `directory`'s settings file.

    Returns True only when every include/includeBuild/projectDir declaration
    is a top-level statement with a literal (or literal-only concatenated)
    argument.  A call hidden in a string, a conditional/lambda/function, a
    variable argument, or a non-literal projectDir makes the whole settings
    file untrusted, so discovery falls back to the repo root only.
    """
    if depth > 8:
        return False
    settings = _settings_path(directory)
    if settings is None:
        return False
    if settings in visited:
        return True
    visited.add(settings)
    try:
        keep = _strip_gradle_comments(read_text(settings))
    except OSError:
        return False
    code = _blank_gradle_strings(keep)
    tokens = _token_view(keep)
    depths = _depth_table(code)
    found_call = False
    parsed = True
    projects = {}

    for match in INCLUDE_RE.finditer(code):
        found_call = True
        if not _top_level_statement(code, tokens, depths, match.start()):
            parsed = False
            continue
        open_index = match.end()
        while open_index < len(keep) and keep[open_index] in " \t\r":
            open_index += 1
        if open_index < len(keep) and keep[open_index] == "(":
            args, _ = _call_arguments(keep, open_index)
        else:
            # Groovy command syntax: `include ':app', ':lib'` on one line.
            if open_index >= len(keep) or keep[open_index] == "\n":
                parsed = False
                continue
            end = _expression_end(code, keep, match.end())
            newline = keep.find("\n", match.end())
            if newline >= 0:
                end = min(end, newline)
            args = keep[match.end():end]
        values = _literal_values(args)
        if values is None:
            parsed = False
            continue
        for value in values:
            if not value:
                parsed = False
                continue
            parts = value[1:].split(":") if value.startswith(":") \
                else value.split(":")
            if any(not part for part in parts):
                parsed = False
                continue
            module = _settle(os.path.join(*parts), directory)
            if not _inside(module, directory):
                parsed = False
                continue
            projects[":" + ":".join(parts)] = module

    for match in INCLUDE_BUILD_RE.finditer(code):
        found_call = True
        if not _top_level_statement(code, tokens, depths, match.start()):
            parsed = False
            continue
        open_index = match.end()
        while open_index < len(keep) and keep[open_index] in " \t\r":
            open_index += 1
        if open_index < len(keep) and keep[open_index] == "(":
            args, _ = _call_arguments(keep, open_index)
        else:
            if open_index >= len(keep) or keep[open_index] == "\n":
                parsed = False
                continue
            end = _expression_end(code, keep, match.end())
            newline = keep.find("\n", match.end())
            if newline >= 0:
                end = min(end, newline)
            args = keep[match.end():end]
        values = _literal_values(args)
        if values is None or len(values) != 1 or not values[0]:
            parsed = False
            continue
        included = _settle(values[0], directory)
        if not _inside(included, directory):
            parsed = False
            continue
        roots.add(included)
        # Follow an included build's own settings when it has one.
        if _settings_path(included) is not None:
            if not _parse_settings(included, roots, visited, depth + 1):
                parsed = False

    for match in PROJECT_CALL_RE.finditer(code):
        if not _top_level_statement(code, tokens, depths, match.start()):
            parsed = False
            continue
        open_index = code.find("(", match.end())
        if open_index < 0:
            parsed = False
            continue
        args, close = _call_arguments(keep, open_index)
        assign = re.match(r"\s*\.\s*projectDir\s*=\s*", code[close:])
        if not assign:
            continue
        project_values = _literal_values(args)
        if project_values is None or len(project_values) != 1 \
                or not project_values[0]:
            parsed = False
            continue
        rhs_start = close + assign.end()
        rhs_end = _expression_end(code, keep, rhs_start)
        module_value = _project_dir_literal(keep[rhs_start:rhs_end])
        if module_value is None:
            parsed = False
            continue
        module = _settle(module_value, directory)
        if not _inside(module, directory):
            parsed = False
            continue
        project = project_values[0]
        if not project.startswith(":"):
            project = ":" + project
        projects[project] = module

    if not found_call:
        # A root settings file with no include/includeBuild at all tells us
        # nothing about module layout: only the repo root may prune `build/`.
        return False
    if not parsed:
        return False
    roots.update(projects.values())
    return True


def module_roots(root):
    """Return the dirs whose `build/` is a real Gradle output root.

    Root-settings-driven when a root settings file exists (includes,
    includeBuild, projectDir overrides).  A nested inert settings/build file
    inside a source package is never a module root.  Without root settings,
    only the repo root and depth-1 children with build.gradle[.kts] + src/
    qualify (the real `app` is depth 1).  If a root settings file exists but
    cannot be parsed, fail closed: only the repo root may prune `build/`.
    """
    root = os.path.abspath(root)
    roots = {root}
    if _settings_path(root) is not None:
        if not _parse_settings(root, roots, set()):
            return {root}
        return roots
    try:
        names = sorted(os.listdir(root))
    except OSError:
        return {root}
    for name in names:
        child = os.path.join(root, name)
        if os.path.islink(child) or not os.path.isdir(child):
            continue
        if not os.path.isdir(os.path.join(child, "src")):
            continue
        if any(os.path.isfile(os.path.join(child, marker))
               for marker in BUILD_FILES):
            roots.add(child)
    return roots


def _prune_dir(name, parent, root, module_roots):
    # `.git`/`.gradle`/`node_modules` are never production trees.  `build` is
    # ONLY pruned when it is a real Gradle output root: directly under the repo
    # root or directly under a module root derived from the root settings file
    # (see `module_roots`).  A package segment literally named `build` inside a
    # source tree, or an inert marker + `src/` directory in a package, is kept.
    if name in (".git", ".gradle", "node_modules"):
        return True
    if name == "build":
        full = os.path.normpath(os.path.join(parent, name))
        # A *module* may itself be named `build` (include(":build")).  Its
        # sources are production sources; only its own `<module>/build`
        # child is a pruned output tree.
        if full in module_roots:
            return False
        return parent == root or parent in module_roots
    return False


def is_test_source(path, root, module_roots):
    # A file is a test source only when it lives at
    # `<module-root>/src/<sourceSet>/...` with a sourceSet starting with
    # `test`/`androidTest`.  The module roots come from the root settings file
    # (or the depth-1 fallback), never from marker files found while walking.
    # This keeps a production file whose *package* happens to contain a later
    # `src/test` (e.g. com/example/src/test/Out.kt) AND a custom `java.srcDir`
    # root (e.g. app/extra/java/.../src/test/Evil.kt) in scope: neither is under
    # `<module>/src/`.
    parent = os.path.dirname(path)
    if parent == path:
        return False
    base = parent
    while True:
        if base in module_roots:
            relparts = os.path.relpath(path, base).split(os.sep)
            if len(relparts) >= 3 and relparts[0] == "src":
                return bool(TEST_SRC_SET.search(relparts[1]))
            return False
        if base == root or os.path.dirname(base) == base:
            return False
        base = os.path.dirname(base)


def cmd_discover(root):
    root = os.path.abspath(root)
    found = []
    module_root_set = module_roots(root)
    # A symlinked source or source directory cannot be classified reliably
    # (its resolved path may land in an excluded test set) and is never
    # followed by os.walk.  Skipping it silently would hide production code, so
    # discovery fails closed with an explicit diagnostic instead (G-13).
    symlink_error = None
    try:
        for dirpath, dirnames, filenames in os.walk(root, onerror=_walk_error):
            keep = []
            for name in dirnames:
                full = os.path.join(dirpath, name)
                if _prune_dir(name, dirpath, root, module_root_set):
                    continue
                if os.path.islink(full):
                    symlink_error = (
                        "symlinked source directory not scanned (fail closed): "
                        f"{rel_to(root, full)}")
                    break
                keep.append(name)
            if symlink_error:
                break
            dirnames[:] = sorted(keep)
            for name in filenames:
                if not (name.endswith(".kt") or name.endswith(".java")):
                    continue
                full = os.path.join(dirpath, name)
                if os.path.islink(full):
                    symlink_error = (
                        "symlinked source not scanned (fail closed): "
                        f"{rel_to(root, full)}")
                    break
                if is_test_source(full, root, module_root_set):
                    continue
                found.append(full)
            if symlink_error:
                break
    except OSError as exc:
        print(f"source discovery failed (fail closed): {exc}")
        return 2
    if symlink_error:
        print(symlink_error)
        return 2
    for path in sorted(found):
        print(path)
    return 0


def _walk_error(exc):
    raise exc


def cmd_types(root, files):
    rc = 0
    for path in files:
        try:
            text = read_text(path)
        except Exception as exc:
            print("type-name scan read error (fail closed): "
                  f"{rel_to(root, path)}: {exc}")
            rc = 2
            continue
        src = java_unicode_decode(text) if path.endswith(".java") else text
        code, _ = lex(src, path.endswith(".java"))
        for name in iter_type_names(code):
            print(name)
    return rc


def cmd_outbound(root, files):
    findings = []
    failed = False
    for path in files:
        try:
            text = read_text(path)
        except Exception as exc:
            print("in-package outbound scan read error (fail closed): "
                  f"{rel_to(root, path)}: {exc}")
            failed = True
            continue
        is_java = path.endswith(".java")
        src = java_unicode_decode(text) if is_java else text
        code, keep = lex(src, is_java)
        lines = text.split("\n")
        relpath = rel_to(root, path)

        import_spans = []
        import_re = IMPORT_JAVA_RE if is_java else IMPORT_KT_RE
        for match in import_re.finditer(code):
            import_spans.append((match.start(), match.end()))
            name = normalize_path(match.group(1))
            lineno = code.count("\n", 0, match.start()) + 1
            if not import_allowed(name):
                findings.append(
                    "outbound import not on allowlist (fail closed): "
                    f"{relpath}:{lineno}: {source_line(lines, lineno)}")

        pkg_spans = [(m.start(), m.end()) for m in PKG_SPAN_RE.finditer(code)]

        spans = import_spans + pkg_spans
        rem_code = normalize_view(blank_spans(code, spans))
        rem_keep = strip_string_view(blank_spans(keep, spans))

        # Code view: comments are already blanked, so `[...]`/`<...>` before a
        # FQN is real code (generic argument / grouped annotation) — only the
        # self-mention rule applies.  The string view applies the same rule:
        # KDoc links live in comments, which are already blanked in both views.
        # The normalised string view restores HEAD's line normalisation for
        # legal spellings (`"dev . librepocket . X"`, backticked segments and
        # same-line `/*x*/` between segments); the raw string view remains as a
        # fallback so a literal `/*` cannot hide a contiguous FQN.
        string_views = (
            (rem_keep, "string"),
            (strip_self_mentions(normalized_string_view(rem_keep)),
             "string-normalized"),
        )
        for scan, label in ((strip_self_mentions(rem_code), "code"),
                            *string_views):
            seen = set()
            for match in FQN_DOT.finditer(scan):
                lineno = scan.count("\n", 0, match.start()) + 1
                if lineno in seen:
                    continue
                seen.add(lineno)
                findings.append(
                    "outbound reference from inside dev/librepocket/mcp/ "
                    f"(fail closed): {relpath}:{lineno}: "
                    f"{source_line(lines, lineno)}")

    # Deduplicate identical diagnostics (a hit may surface in both views).
    seen = set()
    ordered = []
    for finding in findings:
        if finding not in seen:
            seen.add(finding)
            ordered.append(finding)
    for finding in ordered:
        print(finding)
    if failed:
        return 2
    return 1 if ordered else 0


def main(argv):
    if len(argv) < 2:
        print("usage: mcp_scan.py discover|module-roots|types|outbound "
              "ROOT [FILE...]")
        return 2
    command = argv[1]
    if command == "discover":
        if len(argv) != 3:
            print("usage: mcp_scan.py discover ROOT")
            return 2
        return cmd_discover(argv[2])
    if command == "module-roots":
        if len(argv) != 3:
            print("usage: mcp_scan.py module-roots ROOT")
            return 2
        for path in sorted(module_roots(argv[2])):
            print(path)
        return 0
    if command in ("types", "outbound"):
        if len(argv) < 3:
            print(f"usage: mcp_scan.py {command} ROOT [FILE...]")
            return 2
        root = argv[2]
        files = argv[3:]
        if command == "types":
            return cmd_types(root, files)
        return cmd_outbound(root, files)
    print(f"mcp_scan.py: unknown subcommand '{command}' (fail closed)")
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as exc:  # pragma: no cover - fail closed on any crash
        print(f"mcp_scan.py crashed (fail closed): {exc}")
        sys.exit(2)