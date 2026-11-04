#!/usr/bin/env python3
"""Monitor-only OSV scan for direct Gradle catalog dependencies (stdlib only).

Parses ``gradle/libs.versions.toml`` for explicitly versioned libraries,
queries the public OSV ``querybatch`` API for Maven coordinates, and reports
known vulnerabilities. This is a visibility control only: it never upgrades
dependencies and never contacts production endpoints besides the OSV API
(or an explicit ``--osv-url`` override, used by tests with a local fake server).

Scope (Current):
  * Direct catalog libraries with an explicit version only. BOM-managed
    entries without a version (e.g. ``compose-ui`` via ``compose-bom``) are
    skipped with a warning, because the catalog does not record their
    resolved version. Transitive and build-plugin (AGP/Kotlin) versions are
    NOT covered here (direct-catalog-only). Artifact integrity for those
    versions is covered by Gradle dependency verification
    (``gradle/verification-metadata.xml``); vulnerability visibility for them
    comes from GitHub dependency-review (PR diffs only) / Dependabot alerts
    (see issue #132).
  * npm (``github_bot``) and pip (``scripts/requirements-docs.txt``) are NOT
    scanned here; they are covered by Dependabot updates.

Exit codes:
  * 0: scan completed, no vulnerabilities found.
  * 1: scan completed, at least one vulnerability found (monitor signal,
    not an upgrade trigger).
  * 2: fail-closed error (unreadable catalog, invalid TOML, network failure,
    non-200 response, invalid JSON, missing ``--osv-url`` scheme, etc.).

Usage:
  python3 -P scripts/osv_scan.py [--catalog PATH] [--osv-url URL]
      [--timeout SEC] [--batch-size N] [--retries N] [--output PATH]
      [--format text|json]

Network note: the default OSV endpoint is public and unauthenticated
(``https://api.osv.dev/v1/querybatch``). Tests must use ``--osv-url`` with a
local fake HTTP server and synthetic catalogs; never point tests at the
production OSV endpoint.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

try:
    import tomllib
except ImportError:  # pragma: no cover - Python < 3.11 has no tomllib
    tomllib = None  # type: ignore[assignment]

DEFAULT_OSV_URL = "https://api.osv.dev/v1/querybatch"
DEFAULT_CATALOG = Path(__file__).resolve().parent.parent / "gradle" / "libs.versions.toml"
DEFAULT_TIMEOUT = 30
DEFAULT_BATCH = 50
DEFAULT_RETRIES = 2
TIMEOUT_MIN, TIMEOUT_MAX = 1, 300
BATCH_MIN, BATCH_MAX = 1, 500
RETRIES_MIN, RETRIES_MAX = 0, 5
# Transient HTTP statuses worth retrying (with exponential backoff). Other
# 4xx are permanent client errors: fail closed immediately without retry.
RETRYABLE_OSV_STATUS = frozenset({408, 429, 500, 502, 503, 504})
USER_AGENT = "LibrePocket-osv-scan/1.0 (+monitor-only)"


def fail(message: str) -> int:
    print(f"osv_scan: ERROR: {message}", file=sys.stderr)
    return 2


def _osv_url_allowed(url: str) -> bool:
    """Exact-match allowlist for --osv-url (no prefix matching)."""
    try:
        parsed = urllib.parse.urlparse(url)
    except Exception:
        return False
    scheme = parsed.scheme.lower()
    if scheme == "https":
        return True
    if scheme == "http":
        host = (parsed.hostname or "").lower()
        if host in ("localhost", "127.0.0.1", "::1"):
            return True
    return False


def parse_catalog(catalog: Path) -> tuple[list[dict[str, str]], list[str], int]:
    """Return (packages, warnings, skipped_count).

    Each package is group/name/version/key (key = catalog alias). Every
    warning corresponds to one skipped library entry (BOM-managed without a
    catalog version, missing group/name, unresolvable version.ref, ...), so
    skipped_count == len(warnings).
    """
    if tomllib is None:
        raise RuntimeError("tomllib is unavailable (requires Python >= 3.11)")
    try:
        if not catalog.is_file():
            raise FileNotFoundError(f"catalog not found: {catalog}")
        data = tomllib.loads(catalog.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise RuntimeError(str(exc)) from exc
    except OSError as exc:
        raise RuntimeError(f"cannot read catalog {catalog}: {exc}") from exc
    except Exception as exc:  # TOML decode errors surface as TOMLDecodeError
        raise RuntimeError(f"invalid TOML in {catalog}: {exc}") from exc
    if not isinstance(data, dict):
        raise RuntimeError(f"invalid catalog root in {catalog}: expected mapping")
    versions = data.get("versions", {})
    libraries = data.get("libraries", {})
    if not isinstance(versions, dict) or not isinstance(libraries, dict):
        raise RuntimeError(f"invalid catalog sections in {catalog}: versions/libraries must be mappings")
    packages: list[dict[str, str]] = []
    warnings: list[str] = []
    for key in sorted(libraries):
        entry = libraries[key]
        if not isinstance(entry, dict):
            warnings.append(f"skip {key}: library entry is not a mapping")
            continue
        group = entry.get("group")
        name = entry.get("name")
        if not isinstance(group, str) or not group or not isinstance(name, str) or not name:
            warnings.append(f"skip {key}: missing group/name")
            continue
        version: str | None = None
        if "version" in entry:
            raw = entry["version"]
            if isinstance(raw, str):
                version = raw
            elif isinstance(raw, dict) and isinstance(raw.get("ref"), str):
                ref = raw["ref"]
                resolved = versions.get(ref)
                if isinstance(resolved, str) and resolved:
                    version = resolved
                else:
                    warnings.append(f"skip {group}:{name}: unresolvable version.ref '{ref}'")
                    continue
            else:
                warnings.append(f"skip {group}:{name}: unsupported version shape")
                continue
        else:
            warnings.append(f"skip {group}:{name}: no version (BOM-managed?)")
            continue
        if not version:
            warnings.append(f"skip {group}:{name}: empty version")
            continue
        packages.append({"group": group, "name": name, "version": version, "key": key})
    # Deduplicate identical coordinates (catalog may alias same GAV).
    seen: set[tuple[str, str, str]] = set()
    unique: list[dict[str, str]] = []
    for pkg in packages:
        gav = (pkg["group"], pkg["name"], pkg["version"])
        if gav in seen:
            continue
        seen.add(gav)
        unique.append(pkg)
    return unique, warnings, len(warnings)


def query_osv(
    packages: list[dict[str, str]],
    osv_url: str,
    timeout: int,
    batch_size: int,
    retries: int = DEFAULT_RETRIES,
) -> list[dict[str, Any]]:
    """Query OSV querybatch; return per-package result list aligned with input.

    Transient failures (HTTP 408/429/500/502/503/504, URLError, OSError,
    HTTPException) are retried up to ``retries`` times with exponential
    backoff (``time.sleep(2 ** attempt)``, attempt from 0), so each batch
    gets at most 1 + retries attempts. Other 4xx and all semantic errors
    (non-200 status, invalid JSON, malformed response, length mismatch)
    fail closed immediately without retry.
    """
    if not _osv_url_allowed(osv_url):
        raise RuntimeError(f"refusing non-https OSV URL outside loopback tests: {osv_url}")
    if not (TIMEOUT_MIN <= timeout <= TIMEOUT_MAX):
        raise RuntimeError(f"invalid timeout: {timeout} (must be {TIMEOUT_MIN}..{TIMEOUT_MAX})")
    if not (BATCH_MIN <= batch_size <= BATCH_MAX):
        raise RuntimeError(f"invalid batch-size: {batch_size} (must be {BATCH_MIN}..{BATCH_MAX})")
    if not (RETRIES_MIN <= retries <= RETRIES_MAX):
        raise RuntimeError(f"invalid retries: {retries} (must be {RETRIES_MIN}..{RETRIES_MAX})")
    merged: list[dict[str, Any]] = []
    for start in range(0, len(packages), batch_size):
        batch = packages[start:start + batch_size]
        payload = {
            "queries": [
                {
                    "package": {"name": f"{p['group']}:{p['name']}", "ecosystem": "Maven"},
                    "version": p["version"],
                }
                for p in batch
            ]
        }
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            osv_url,
            data=body,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": USER_AGENT,
            },
            method="POST",
        )
        attempt = 0
        while True:
            try:
                with urllib.request.urlopen(request, timeout=timeout) as response:
                    status = getattr(response, "status", 200)
                    raw = response.read()
            except urllib.error.HTTPError as exc:
                if exc.code in RETRYABLE_OSV_STATUS and attempt < retries:
                    time.sleep(2**attempt)
                    attempt += 1
                    continue
                raise RuntimeError(
                    f"OSV HTTP error {exc.code} for batch @{start}: {exc.reason}"
                ) from exc
            except urllib.error.URLError as exc:
                if attempt < retries:
                    time.sleep(2**attempt)
                    attempt += 1
                    continue
                raise RuntimeError(f"OSV network error for batch @{start}: {exc.reason}") from exc
            except OSError as exc:
                if attempt < retries:
                    time.sleep(2**attempt)
                    attempt += 1
                    continue
                raise RuntimeError(f"OSV connection failed for batch @{start}: {exc}") from exc
            except http.client.HTTPException as exc:
                if attempt < retries:
                    time.sleep(2**attempt)
                    attempt += 1
                    continue
                raise RuntimeError(f"OSV protocol error for batch @{start}: {exc}") from exc
            break
        if status != 200:
            raise RuntimeError(f"OSV unexpected status {status} for batch @{start}")
        try:
            decoded = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise RuntimeError(f"OSV invalid JSON for batch @{start}: {exc}") from exc
        if not isinstance(decoded, dict) or not isinstance(decoded.get("results"), list):
            raise RuntimeError(f"OSV malformed response for batch @{start}: missing results list")
        results = decoded["results"]
        if len(results) != len(batch):
            raise RuntimeError(
                f"OSV result length mismatch for batch @{start}: "
                f"got {len(results)}, want {len(batch)}"
            )
        for pkg, result in zip(batch, results):
            if not isinstance(result, dict):
                raise RuntimeError(f"OSV malformed per-package result for {pkg['group']}:{pkg['name']}")
            vulns = result.get("vulns", [])
            if vulns is None:
                vulns = []
            if not isinstance(vulns, list):
                raise RuntimeError(f"OSV malformed vulns for {pkg['group']}:{pkg['name']}")
            merged.append({"package": pkg, "vulns": vulns})
    return merged


def summarize(results: list[dict[str, Any]], skipped: int = 0) -> tuple[int, list[str]]:
    """Return (vuln_package_count, text_lines)."""
    lines: list[str] = []
    affected = 0
    total_vulns = 0
    for entry in results:
        pkg = entry["package"]
        vulns = entry["vulns"]
        gav = f"{pkg['group']}:{pkg['name']}:{pkg['version']}"
        if not vulns:
            continue
        affected += 1
        total_vulns += len(vulns)
        lines.append(f"AFFECTED {gav} ({len(vulns)} vuln(s))")
        for vuln in vulns:
            if not isinstance(vuln, dict):
                lines.append("  - <malformed vuln entry>")
                continue
            vid = str(vuln.get("id", "?"))
            summary = vuln.get("summary", "")
            if not isinstance(summary, str):
                summary = str(summary)
            if len(summary) > 120:
                summary = summary[:117] + "..."
            lines.append(f"  - {vid}: {summary}")
    header = (
        f"osv_scan: {len(results)} package(s) scanned, "
        f"{skipped} skipped (BOM-managed, version unknown), "
        f"{affected} affected, {total_vulns} total vuln(s)"
    )
    return affected, [header] + lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--osv-url", default=DEFAULT_OSV_URL)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help="per-request timeout in seconds (1..300, default 30)")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH,
                        help="packages per querybatch request (1..500, default 50)")
    parser.add_argument("--retries", type=int, default=DEFAULT_RETRIES,
                        help="retries for transient OSV failures "
                             "(HTTP 408/429/500/502/503/504, network/protocol errors; "
                             "0..5, default 2; total attempts = 1 + retries)")
    parser.add_argument("--output", type=Path, default=None,
                        help="optional JSON report path (SBOM-lite + vulns)")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args(argv)

    if not (TIMEOUT_MIN <= args.timeout <= TIMEOUT_MAX):
        return fail(f"invalid timeout: {args.timeout} (must be {TIMEOUT_MIN}..{TIMEOUT_MAX})")
    if not (BATCH_MIN <= args.batch_size <= BATCH_MAX):
        return fail(f"invalid batch-size: {args.batch_size} (must be {BATCH_MIN}..{BATCH_MAX})")
    if not (RETRIES_MIN <= args.retries <= RETRIES_MAX):
        return fail(f"invalid retries: {args.retries} (must be {RETRIES_MIN}..{RETRIES_MAX})")

    # Fail fast on an unwritable report path before any network request.
    if args.output is not None:
        parent = args.output.parent
        if not parent.is_dir():
            return fail(f"cannot write report {args.output}: "
                        f"parent directory does not exist: {parent}")
        if not os.access(parent, os.W_OK):
            return fail(f"cannot write report {args.output}: "
                        f"parent directory not writable: {parent}")

    try:
        packages, warnings, skipped = parse_catalog(args.catalog)
    except RuntimeError as exc:
        return fail(str(exc))
    for warning in warnings:
        print(f"osv_scan: WARN: {warning}", file=sys.stderr)
    if not packages:
        return fail(f"no versioned packages found in {args.catalog}")

    try:
        results = query_osv(packages, args.osv_url, args.timeout, args.batch_size, args.retries)
    except RuntimeError as exc:
        return fail(str(exc))
    except Exception as exc:
        return fail(f"OSV unexpected error: {type(exc).__name__}: {exc}")

    affected, lines = summarize(results, skipped)
    if args.format == "json" or args.output is not None:
        report = {
            "catalog": str(args.catalog),
            "osv_url": args.osv_url,
            "packages": [
                {
                    "key": e["package"]["key"],
                    "group": e["package"]["group"],
                    "name": e["package"]["name"],
                    "version": e["package"]["version"],
                    "purl": f"pkg:maven/{e['package']['group']}/{e['package']['name']}@{e['package']['version']}",
                    "vulns": e["vulns"],
                }
                for e in results
            ],
            "warnings": warnings,
            "summary": {
                "scanned": len(results),
                "skipped": skipped,
                "affected": affected,
                "total_vulns": sum(len(e["vulns"]) for e in results),
            },
        }
        text = json.dumps(report, indent=2, sort_keys=True)
        if args.output is not None:
            try:
                args.output.write_text(text + "\n", encoding="utf-8")
            except OSError as exc:
                return fail(f"cannot write report {args.output}: {exc}")
        if args.format == "json":
            print(text)
        else:
            for line in lines:
                print(line)
    else:
        for line in lines:
            print(line)

    if affected:
        print(
            f"osv_scan: MONITOR signal: {affected} package(s) with known vulns; "
            "no upgrade performed (see issue #132).",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
