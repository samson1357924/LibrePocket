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
    NOT covered here; they are covered by Gradle dependency verification
    (``gradle/verification-metadata.xml``) and GitHub dependency-review /
    Dependabot alerts (see issue #132).
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
      [--timeout SEC] [--batch-size N] [--output PATH] [--format text|json]

Network note: the default OSV endpoint is public and unauthenticated
(``https://api.osv.dev/v1/querybatch``). Tests must use ``--osv-url`` with a
local fake HTTP server and synthetic catalogs; never point tests at the
production OSV endpoint.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
import urllib.error
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


def fail(message: str) -> int:
    print(f"osv_scan: ERROR: {message}", file=sys.stderr)
    return 2


def parse_catalog(catalog: Path) -> tuple[list[dict[str, str]], list[str]]:
    """Return (packages, warnings). Each package is group/name/version."""
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
    return unique, warnings


def query_osv(
    packages: list[dict[str, str]],
    osv_url: str,
    timeout: int,
    batch_size: int,
) -> list[dict[str, Any]]:
    """Query OSV querybatch; return per-package result list aligned with input."""
    if not osv_url.startswith(("https://", "http://localhost", "http://127.")):
        raise RuntimeError(f"refusing non-https OSV URL outside loopback tests: {osv_url}")
    if timeout <= 0 or batch_size <= 0:
        raise RuntimeError(f"invalid timeout/batch-size: {timeout}/{batch_size}")
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
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status = getattr(response, "status", 200)
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"OSV HTTP error {exc.code} for batch @{start}: {exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"OSV network error for batch @{start}: {exc.reason}") from exc
        except OSError as exc:
            raise RuntimeError(f"OSV connection failed for batch @{start}: {exc}") from exc
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


def summarize(results: list[dict[str, Any]]) -> tuple[int, list[str]]:
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
            vid = vuln.get("id", "?")
            summary = vuln.get("summary", "")
            if isinstance(summary, str) and len(summary) > 120:
                summary = summary[:117] + "..."
            lines.append(f"  - {vid}: {summary}")
    header = (
        f"osv_scan: {len(results)} package(s) scanned, "
        f"{affected} affected, {total_vulns} vuln(s) total"
    )
    return affected, [header] + lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--osv-url", default=DEFAULT_OSV_URL)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH)
    parser.add_argument("--output", type=Path, default=None,
                        help="optional JSON report path (SBOM-lite + vulns)")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args(argv)

    try:
        packages, warnings = parse_catalog(args.catalog)
    except RuntimeError as exc:
        return fail(str(exc))
    for warning in warnings:
        print(f"osv_scan: WARN: {warning}", file=sys.stderr)
    if not packages:
        return fail(f"no versioned packages found in {args.catalog}")

    try:
        results = query_osv(packages, args.osv_url, args.timeout, args.batch_size)
    except RuntimeError as exc:
        return fail(str(exc))

    affected, lines = summarize(results)
    if args.format == "json" or args.output is not None:
        report = {
            "catalog": str(args.catalog),
            "osv_url": args.osv_url,
            "packages": [
                {
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
