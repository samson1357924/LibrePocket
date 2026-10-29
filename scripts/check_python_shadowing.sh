#!/usr/bin/env bash
# Fail closed on repo-local modules that would shadow Python stdlib/PyYAML,
# and pin the CI dependency manifest before pip is allowed to execute it.
#
# `python3 -m unittest` puts the current directory on sys.path, and
# `python3 scripts/foo.py` puts scripts/ on sys.path. A checked-in
# yaml.py/hashlib.py/unittest.py (or .pyc/.so variant) can therefore replace
# the parser, a gate helper, or the test runner without touching the checker.
# This preflight runs before any Python import in the CI jobs that consume
# PyYAML/unittest/policy scripts; it is intentionally a shell script so it
# cannot be shadowed by a Python module.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="${1:-$(cd -- "$SCRIPT_DIR/.." && pwd -P)}"
ROOT="${ROOT%/}"
status=0

# The requirements file is consumed by pip before the workflow checker runs.
# Pin its raw bytes so a PR cannot add a local wheel/sdist/.pth that executes
# during `pip install` (or at the next interpreter start) and exits 0 before
# the checker sees the workflow.
REQUIREMENTS_SHA256="b7078b93108cf8befcc2286b3adea4bc4042ebe9795c7b7dc69a6b7d9b258762"
requirements="$ROOT/scripts/requirements-docs.txt"
if [ ! -f "$requirements" ]; then
    echo "::error::missing scripts/requirements-docs.txt"
    status=1
elif command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$requirements" | awk '{print $1}')"
    if [ "$actual" != "$REQUIREMENTS_SHA256" ]; then
        echo "::error::scripts/requirements-docs.txt bytes do not match the pinned hash"
        status=1
    fi
elif command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$requirements" | awk '{print $1}')"
    if [ "$actual" != "$REQUIREMENTS_SHA256" ]; then
        echo "::error::scripts/requirements-docs.txt bytes do not match the pinned hash"
        status=1
    fi
else
    echo "::error::sha256sum or shasum is required to pin scripts/requirements-docs.txt"
    status=1
fi

# Python's stdlib names are the authoritative shadow set. -P/-S keep the
# discovery interpreter from loading repo modules or site .pth files.
if ! STDLIB_NAMES="$(python3 -P -S -c 'import sys; print("\n".join(sorted(sys.stdlib_module_names)))')"; then
    echo "::error::cannot enumerate Python stdlib module names (fail closed)"
    status=1
    STDLIB_NAMES=""
elif [ -z "$STDLIB_NAMES" ]; then
    echo "::error::stdlib module enumeration was empty (fail closed)"
    status=1
fi

declare -A FORBIDDEN=()
while IFS= read -r name; do
    [ -n "$name" ] && FORBIDDEN["$name"]=1
done <<< "$STDLIB_NAMES"
for name in yaml _yaml sitecustomize usercustomize pip wheel setuptools; do
    FORBIDDEN["$name"]=1
done

# Only the directories that become sys.path roots for the CI invocations.
# (scripts/tests is imported as a package, not as a top-level module root.)
for import_root in "$ROOT" "$ROOT/scripts"; do
    [ -d "$import_root" ] || continue
    while IFS= read -r -d '' path; do
        base="$(basename "$path")"
        case "$base" in
            *.py) name="${base%.py}" ;;
            *.pyc) name="${base%.pyc}" ;;
            *.so) name="${base%%.*}" ;;
            *.pyd) name="${base%%.*}" ;;
            *) name="$base" ;;
        esac
        if [ -n "$name" ] && [ -n "${FORBIDDEN[$name]:-}" ]; then
            echo "::error::forbidden Python import shadow: ${path#"$ROOT"/}"
            status=1
        fi
    done < <(find "$import_root" -maxdepth 1 -mindepth 1 -print0)
done

# A checked-in .pth is never legitimate here; pip-installed .pth files are
# blocked by the requirements pin above.
while IFS= read -r -d '' path; do
    echo "::error::forbidden .pth file: ${path#"$ROOT"/}"
    status=1
done < <(find "$ROOT" -name .git -type d -prune -o -name '*.pth' -print0)

if [ "$status" -ne 0 ]; then
    echo "FAIL: repo-local Python modules/dependency manifest are not trusted" >&2
    exit 1
fi

echo "PASS: no Python import shadowing modules found; dependency manifest is pinned"
