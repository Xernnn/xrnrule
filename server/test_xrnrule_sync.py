"""Tests for xrnrule-sync.py: optimistic concurrency, the cheap 304 check, the long-poll.

    python3 -m unittest server/test_xrnrule_sync.py     (from the repo root)
"""
import base64
import gzip
import hashlib
import http.client
import importlib.util
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("xrnrule_sync", os.path.join(HERE, "xrnrule-sync.py"))
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)


class SyncServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import bcrypt

        cls.tmp = tempfile.TemporaryDirectory()
        users = os.path.join(cls.tmp.name, "htpasswd")
        with open(users, "w") as f:
            f.write("sonn:" + bcrypt.hashpw(b"secret", bcrypt.gensalt(4)).decode() + "\n")
        web = os.path.join(cls.tmp.name, "web")
        os.makedirs(web)
        with open(os.path.join(web, "index.html"), "w") as f:
            f.write("<!doctype html>status")
        srv.args = type("Args", (), {"data": os.path.join(cls.tmp.name, "data"), "users": users, "web": web})()
        srv.FAIL_DELAY_S = 0
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
        cls.httpd.daemon_threads = True
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.tmp.cleanup()

    def call(self, method, path, body=None, headers=None, password="secret"):
        req = urllib.request.Request(self.base + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Authorization", "Basic " + base64.b64encode(f"sonn:{password}".encode()).decode())
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read().decode(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(), e.headers

    def test_write_needs_the_version_it_read(self):
        status, body, headers = self.call("GET", "/xrnrule/state")
        self.assertEqual(200, status)
        v0 = headers["ETag"]
        self.assertEqual(428, self.call("PUT", "/state", {"a": 1})[0])
        status, _, headers = self.call("PUT", "/state", {"a": 1}, {"If-Match": v0})
        self.assertEqual(200, status)
        v1 = headers["ETag"]
        self.assertNotEqual(v0, v1)
        status, _, headers = self.call("PUT", "/state", {"a": 2}, {"If-Match": v0})  # stale
        self.assertEqual(412, status)
        self.assertEqual(v1, headers["ETag"])
        _, body, _ = self.call("GET", "/state")
        self.assertEqual({"a": 1}, json.loads(body))

    def test_unchanged_answers_304(self):
        _, _, headers = self.call("GET", "/state")
        status, body, _ = self.call("GET", "/state", headers={"If-None-Match": headers["ETag"]})
        self.assertEqual((304, ""), (status, body))

    def test_long_poll_wakes_on_a_write(self):
        _, _, headers = self.call("GET", "/state")
        etag = headers["ETag"]
        threading.Timer(0.3, lambda: self.call("PUT", "/state", {"b": 1}, {"If-Match": etag})).start()
        start = time.monotonic()
        status, body, headers2 = self.call("GET", "/state?wait=20", headers={"If-None-Match": etag})
        self.assertLess(time.monotonic() - start, 5)
        self.assertEqual(200, status)
        self.assertEqual({"b": 1}, json.loads(body))
        self.assertNotEqual(etag, headers2["ETag"])

    def test_long_poll_times_out_with_304(self):
        _, _, headers = self.call("GET", "/state")
        start = time.monotonic()
        status, _, _ = self.call("GET", "/state?wait=0.5", headers={"If-None-Match": headers["ETag"]})
        self.assertEqual(304, status)
        self.assertGreaterEqual(time.monotonic() - start, 0.4)

    def test_login_and_open_paths(self):
        self.assertEqual(401, self.call("GET", "/state", password="nope")[0])
        status, _, headers = self.call("GET", "/state", password="nope", headers={"X-Xrn-Client": "web"})
        self.assertEqual(401, status)
        self.assertIsNone(headers.get("WWW-Authenticate"))
        with urllib.request.urlopen(self.base + "/xrnrule/health") as r:
            self.assertEqual("ok", r.read().decode())
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("status", r.read().decode())

    def test_gzip_bodies_are_unpacked_only_so_far(self):
        def put(raw):
            req = urllib.request.Request(self.base + "/state", method="PUT", data=raw)
            req.add_header("Authorization", "Basic " + base64.b64encode(b"sonn:secret").decode())
            req.add_header("Content-Encoding", "gzip")
            req.add_header("If-Match", '"999"')  # never the version: nothing gets written
            try:
                with urllib.request.urlopen(req, timeout=10) as r:
                    return r.status
            except urllib.error.HTTPError as e:
                return e.code

        bomb = gzip.compress(b'{"a":"' + b"x" * (srv.MAX_BODY + 1) + b'"}')
        self.assertLess(len(bomb), 100_000)
        self.assertEqual(413, put(bomb))
        self.assertEqual(400, put(b"not gzip at all"))
        self.assertEqual(400, put(gzip.compress(b'{"a":1}')[:-12]))  # cut short
        self.assertEqual(412, put(gzip.compress(b'{"a":1}')))  # read fine; only the version is off

    def test_a_broken_content_length_is_a_400(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        conn.putrequest("PUT", "/state")
        conn.putheader("Authorization", "Basic " + base64.b64encode(b"sonn:secret").decode())
        conn.putheader("Content-Length", "lots")
        conn.endheaders()
        with conn.getresponse() as r:
            self.assertEqual(400, r.status)
        conn.close()

    def test_pages_cannot_be_framed_or_sniffed(self):
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertEqual("DENY", r.headers["X-Frame-Options"])
            self.assertEqual("nosniff", r.headers["X-Content-Type-Options"])
            self.assertEqual("no-referrer", r.headers["Referrer-Policy"])

    def test_the_content_policy_allows_index_html_inline_scripts_by_hash(self):
        with tempfile.TemporaryDirectory() as web:
            with open(os.path.join(web, "index.html"), "w") as f:
                f.write("<head><script>go()</script><script type=module src=a.js></script></head>")
            policy = srv.content_policy(web)
        digest = base64.b64encode(hashlib.sha256(b"go()").digest()).decode()
        self.assertIn(f"script-src 'self' 'sha256-{digest}';", policy)
        self.assertIn("frame-ancestors 'none'", policy)

    def test_wrong_passwords_lock_out_that_address_only(self):
        guesser = {"X-Forwarded-For": "203.0.113.9"}  # as Funnel's proxy passes it on
        for _ in range(srv.FAIL_LIMIT):
            self.assertEqual(401, self.call("GET", "/state", password="nope", headers=guesser)[0])
        # Locked out now, even with the right password; everyone else isn't.
        self.assertEqual(429, self.call("GET", "/state", headers=guesser)[0])
        self.assertEqual(200, self.call("GET", "/state", headers={"X-Forwarded-For": "203.0.113.10"})[0])

    def test_a_right_password_clears_earlier_mistakes(self):
        typo = {"X-Forwarded-For": "203.0.113.20"}
        for _ in range(srv.FAIL_LIMIT - 1):
            self.call("GET", "/state", password="nope", headers=typo)
        self.assertEqual(200, self.call("GET", "/state", headers=typo)[0])
        self.assertEqual(401, self.call("GET", "/state", password="nope", headers=typo)[0])
        self.assertEqual(200, self.call("GET", "/state", headers=typo)[0])

    def test_devices_report_when_each_last_checked_in(self):
        before = int(time.time() * 1000)
        self.call("GET", "/state", headers={"X-Device": "phone"})
        status, body, _ = self.call("GET", "/xrnrule/devices")
        self.assertEqual(200, status)
        seen = json.loads(body)
        self.assertGreaterEqual(seen["phone"], before)
        self.assertNotIn("", seen)  # the status page itself sends no X-Device
        self.assertTrue(os.path.isfile(srv.devices_path("sonn")))

    def test_events_are_kept_once_and_read_back_by_time(self):
        now = int(time.time() * 1000)
        batch = {"events": [
            {"t": "blocked", "at": now - 5000, "target": "com.example.game", "name": "Game"},
            {"t": "unlock", "at": now - 1000, "block": "b1", "ms": 300000, "start": now - 301000},
            {"t": "nonsense", "at": now},  # dropped
        ]}
        status, body, _ = self.call("POST", "/events", batch, {"X-Device": "phone"})
        self.assertEqual((200, 2), (status, json.loads(body)["added"]))
        # A resend (the reply got lost) adds nothing; the same event from another device does.
        self.assertEqual(0, json.loads(self.call("POST", "/events", batch, {"X-Device": "phone"})[1])["added"])
        self.assertEqual(2, json.loads(self.call("POST", "/events", batch, {"X-Device": "laptop"})[1])["added"])
        self.assertEqual(400, self.call("POST", "/events", batch)[0])  # no X-Device
        _, body, _ = self.call("GET", f"/events?since={now - 2000}")
        got = json.loads(body)["events"]
        self.assertEqual([("laptop", "unlock", 300000), ("phone", "unlock", 300000)],
                         sorted((e["d"], e["t"], e["ms"]) for e in got))
        self.assertEqual({now - 301000}, {e["start"] for e in got})  # one session, logged by both


if __name__ == "__main__":
    unittest.main()
