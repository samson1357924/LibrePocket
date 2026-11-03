#!/usr/bin/env python3
"""Regression tests for scripts/osv_scan.py (stdlib only, no production OSV).

Uses a local fake HTTP server for OSV querybatch and synthetic catalogs.
Never contacts https://api.osv.dev in tests.
"""

import json
import subprocess
import sys
import tempfile
import threading
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


class FakeOsvHandler(BaseHTTPRequestHandler):
    mode = "clean"  # clean | vuln | error500 | badjson | mismatch

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
        self.assertTrue(SCRIPT.is_file(), f"{SCRIPT} missing")
        self.server = HTTPServer(("127.0.0.1", 0), FakeOsvHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        port = self.server.server_address[1]
        self.url = f"http://127.0.0.1:{port}/v1/querybatch"

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
            capture_output=True, text=True, check=False,
        )

    def test_clean_catalog_passes(self):
        FakeOsvHandler.mode = "clean"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("0 affected", proc.stdout)

    def test_vuln_catalog_reports_monitor_signal(self):
        FakeOsvHandler.mode = "vuln"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("GHSA-test-0001", proc.stdout)
            self.assertIn("no upgrade performed", proc.stderr)

    def test_missing_catalog_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            proc = self.run_scan("--catalog", str(Path(td) / "missing.toml"),
                                 "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)

    def test_invalid_toml_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td), content="[[[invalid")
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)

    def test_server_error_fails_closed(self):
        FakeOsvHandler.mode = "error500"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)

    def test_bad_json_fails_closed(self):
        FakeOsvHandler.mode = "badjson"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)

    def test_length_mismatch_fails_closed(self):
        FakeOsvHandler.mode = "mismatch"
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog), "--osv-url", self.url)
            self.assertEqual(proc.returncode, 2)
            self.assertIn("ERROR", proc.stderr)

    def test_non_https_non_loopback_refused(self):
        with tempfile.TemporaryDirectory() as td:
            catalog = self.write_catalog(Path(td))
            proc = self.run_scan("--catalog", str(catalog),
                                 "--osv-url", "http://example.com/v1/querybatch")
            self.assertEqual(proc.returncode, 2)
            self.assertIn("refusing", proc.stderr.lower())

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
            purls = [p["purl"] for p in report["packages"]]
            self.assertIn("pkg:maven/com.example/foo@1.0.0", purls)


if __name__ == "__main__":
    unittest.main()
