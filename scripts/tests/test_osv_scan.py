#!/usr/bin/env python3
"""Regression tests for scripts/osv_scan.py (stdlib only, no production OSV).

Uses a local fake HTTP server for OSV querybatch and synthetic catalogs.
Never contacts https://api.osv.dev in tests.
"""

import json
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/osv_scan.py"

FAKE_TOML = """\
[versions]
foo = "1.0.0"
bar = "2.0.0"

[libraries]
foo-lib = { group = "com.example", name = "foo", version.ref = "foo" }
bar-lib = { group = "com.example", name = "bar", version.ref = "bar" }
bom-managed = { group = "androidx.compose.ui", name = "ui" }
"""

EMPTY_TOML = """\
[versions]
foo = "1.0.0"

[libraries]
bom-managed = { group = "androidx.compose.ui", name = "ui" }
"""


class FakeOsvHandler(BaseHTTPRequestHandler):
    mode = "clean"  # clean | vuln | error500 | badjson | mismatch | garbage | nonlist_vulns
    last_queries: list = []

    def log_message(self, *args):  # silence test server logs
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        try:
            payload = json.loads(body.decode("utf-8"))
            queries = payload["queries"]
        except Exception:
            self.send_response(400)
            self.end_headers()
            return
        FakeOsvHandler.last_queries.extend(queries)
        if FakeOsvHandler.mode == "error500":
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b"boom")
            return
        if FakeOsvHandler.mode == "badjson":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"{not json")
            return
        if FakeOsvHandler.mode == "mismatch":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"results": []}).encode())
            return
        if FakeOsvHandler.mode == "nonlist_vulns":
            results = []
            for i, _query in enumerate(queries):
                if i == 0:
                    results.append({"vulns": "GHSA-x"})
                else:
                    results.append({})
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"results": results}).encode())
            return
        if FakeOsvHandler.mode == "garbage":
            self.wfile.write(b"GARBAGE-NOT-HTTP\r\n\r\n")
            self.wfile.flush()
            self.close_connection = True
            return
        results = []
        for query in queries:
            name = query["package"]["name"]
            if FakeOsvHandler.mode == "vuln" and name == "com.example:foo":
                results.append({"vulns": [{"id": "GHSA-test-0001", "summary": "fake vuln"}]})
            else:
                results.append({})
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"results": results}).encode())


class OsvScanTest(unittest.TestCase):
    def setUp(self):
        FakeOsvHandler.mode = "clean"
        FakeOsvHandler.last_queries = []
        self.addCleanup(setattr, FakeOsvHandler, "mode", "clean")
        self.addCleanup(setattr, FakeOsvHandler, "last_queries", [])
        self.assertTrue(SCRIPT.is_file(), f"{SCRIPT} missing")
        self.server = HTTPServer(("127.0.0.1", 0), FakeOsvHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        port = self.server.server_address[1]
        self.url = f"http://127.0.0.1:{port}/v1/querybatch"
        for _ in range(50):
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                    break
            except OSError:
                time.sleep(0.05)

    def tearDown(self):
        self.server.shutdown()
        self.thread.join(timeout=5)
        self.server.server_close()

    def write_catalog(self, directory: Path, content: str = FAKE_TOML) -> Path:
        catalog = directory / "libs.versions.toml"
        catalog.write_text(content, encoding="utf-8")
        return catalog

    def run_scan(self, *args: str):
        return subprocess.run(
            [sys.executable, "-P", str(SCRIPT), *args],
            capture_output=True, text=True, check=False, timeout=60,
        )

    def test_clean_catalog_passes(self):
        FakeOsvHandler.mode = "clean"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("0 affected", proc.stdout)
            self.assertIn("2 package(s) scanned", proc.stdout)
            self.assertIn("BOM-managed", proc.stderr)
            self.assertEqual(len(FakeOsvHandler.last_queries), 2)
            for query in FakeOsvHandler.last_queries:
                self.assertEqual(query["package"]["ecosystem"], "Maven")
                self.assertIn(":", query["package"]["name"])

    def test_vuln_catalog_reports_monitor_signal(self):
        FakeOsvHandler.mode = "vuln"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("GHSA-test-0001", proc.stdout)
            self.assertIn("1 affected", proc.stdout)
            self.assertIn("AFFECTED com.example:foo", proc.stdout)
            self.assertNotIn("AFFECTED com.example:bar", proc.stdout)
            self.assertIn("no upgrade performed", proc.stderr)

    def test_missing_catalog_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            proc = self.run_scan("--catalog", str(Path(td) / "missing.toml"),
                                 "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("catalog not found", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_invalid_toml_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td), content="[[[invalid")
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("invalid TOML", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_server_error_fails_closed(self):
        FakeOsvHandler.mode = "error500"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("HTTP error", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_bad_json_fails_closed(self):
        FakeOsvHandler.mode = "badjson"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("invalid JSON", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_length_mismatch_fails_closed(self):
        FakeOsvHandler.mode = "mismatch"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("length mismatch", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_non_https_non_loopback_refused(self):
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog),
                                 "--osv-url", "http://example.com/v1/querybatch")
            self.assertEqual(proc.returncode, 2)
            self.assertIn("refusing", proc.stderr.lower())
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_real_catalog_parses_and_skips_bom(self):
        FakeOsvHandler.mode = "clean"
        catalog = ROOT / "gradle/libs.versions.toml"
        self.assertTrue(catalog.is_file())
        proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url,
                             "--batch-size", "5")
        # Real catalog has versioned libs, so scan completes (clean fake server).
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("package(s) scanned", proc.stdout)
        # BOM-managed compose entries must be skipped with a warning.
        self.assertIn("BOM-managed", proc.stderr)

    def test_json_report_writes_purls(self):
        FakeOsvHandler.mode = "vuln"
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            catalog = self.write_catalog(root)
            out = root / "report.json"
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url,
                                 "--format", "json", "--output", str(out))
            self.assertEqual(proc.returncode, 1)
            report = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(report["summary"]["scanned"], 2)
            self.assertEqual(report["summary"]["affected"], 1)
            self.assertEqual(report["summary"]["total_vulns"], 1)
            self.assertTrue(report["warnings"], "BOM skip warnings must be recorded")
            purls = [p["purl"] for p in report["packages"]]
            self.assertIn("pkg:maven/com.example/foo@1.0.0", purls)
            foo = next(p for p in report["packages"] if p["name"] == "foo")
            self.assertEqual(foo["vulns"][0]["id"], "GHSA-test-0001")

    def test_lookalike_loopback_hosts_refused(self):
        FakeOsvHandler.mode = "clean"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            for evil in (
                "http://127.evil.com/v1/querybatch",
                "http://localhost.evil.com/v1/querybatch",
            ):
                with self.subTest(url=evil):
                    proc = self.run_scan("--catalog", str(catalog),
                                         "--osv-url", evil)
                    self.assertEqual(proc.returncode, 2, proc.stderr)
                    self.assertIn("refusing", proc.stderr.lower())
                    self.assertNotIn("package(s) scanned", proc.stdout)

    def test_osv_url_allowlist_exact_match(self):
        FakeOsvHandler.mode = "clean"
        from scripts import osv_scan as osv_scan_mod
        self.assertTrue(
            osv_scan_mod._osv_url_allowed("https://api.osv.dev/v1/querybatch"))
        self.assertTrue(
            osv_scan_mod._osv_url_allowed("http://127.0.0.1:8080/v1/querybatch"))
        self.assertTrue(
            osv_scan_mod._osv_url_allowed("http://localhost:8080/v1/querybatch"))
        self.assertTrue(
            osv_scan_mod._osv_url_allowed("http://[::1]:8080/v1/querybatch"))
        self.assertFalse(
            osv_scan_mod._osv_url_allowed("http://127.evil.com/v1/querybatch"))
        self.assertFalse(
            osv_scan_mod._osv_url_allowed("http://localhost.evil.com/v1/querybatch"))
        self.assertFalse(
            osv_scan_mod._osv_url_allowed("ftp://api.osv.dev/v1/querybatch"))
        self.assertFalse(
            osv_scan_mod._osv_url_allowed("api.osv.dev/v1/querybatch"))

    def test_garbage_status_line_fails_closed(self):
        FakeOsvHandler.mode = "garbage"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2, proc.stderr)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("protocol error", proc.stderr)
            self.assertNotIn("AFFECTED", proc.stdout)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_batching_merges_across_batches(self):
        FakeOsvHandler.mode = "vuln"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url,
                                 "--batch-size", "1")
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("2 package(s) scanned", proc.stdout)
            self.assertIn("1 affected", proc.stdout)
            self.assertIn("GHSA-test-0001", proc.stdout)
            self.assertIn("AFFECTED com.example:foo", proc.stdout)
            self.assertNotIn("AFFECTED com.example:bar", proc.stdout)
            self.assertEqual(len(FakeOsvHandler.last_queries), 2)

    def test_nonlist_vulns_fails_closed(self):
        FakeOsvHandler.mode = "nonlist_vulns"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2, proc.stderr)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("malformed vulns", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_zero_batch_size_fails_closed(self):
        FakeOsvHandler.mode = "clean"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url,
                                 "--batch-size", "0")
            self.assertEqual(proc.returncode, 2, proc.stderr)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("invalid timeout/batch-size", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)

    def test_empty_catalog_fails_closed(self):
        FakeOsvHandler.mode = "clean"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td), content=EMPTY_TOML)
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2, proc.stderr)
            self.assertIn("ERROR", proc.stderr)
            self.assertIn("no versioned packages", proc.stderr)
            self.assertNotIn("package(s) scanned", proc.stdout)


if __name__ == "__main__":
    unittest.main()
