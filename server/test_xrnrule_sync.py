"""Tests for xrnrule-sync.py: optimistic concurrency, the cheap 304 check, the long-poll.

    python3 -m unittest server/test_xrnrule_sync.py     (from the repo root)
"""
import base64
import concurrent.futures
import gzip
import hashlib
import http.client
import importlib.util
import json
import os
import shutil
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("xrnrule_sync", os.path.join(HERE, "xrnrule-sync.py"))
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)


# The test login (both servers are started with this one user).
RIGHT = "secret"
WRONG = "nope"

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
        srv.session_key = srv.load_session_key()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
        cls.httpd.daemon_threads = True
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.tmp.cleanup()

    def call(self, method, path, body=None, headers=None, password=RIGHT):
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

    def state_file(self):
        return os.path.join(srv.args.data, "users", "sonn", "state.json")

    def test_an_unreadable_file_is_never_an_empty_document(self):
        status, _, h = self.call("GET", "/state")
        put, _, h = self.call("PUT", "/state", {"blocks": {"a": {}}}, {"If-Match": h["ETag"]})
        self.assertEqual(put, 200)
        with open(self.state_file()) as f:
            good = f.read()
        with open(self.state_file(), "w") as f:
            f.write(good[:20])
        try:
            self.assertEqual(self.call("GET", "/state")[0], 503)
            self.assertEqual(self.call("PUT", "/state", {"blocks": {}}, {"If-Match": "*"})[0], 503)
        finally:
            with open(self.state_file(), "w") as f:
                f.write(good)
        self.assertEqual(json.loads(self.call("GET", "/state")[1]), {"blocks": {"a": {}}})

    def test_a_file_that_was_never_written_is_version_zero(self):
        saved = self.state_file() + ".aside"
        self.call("PUT", "/state", {"a": 1}, {"If-Match": "*"})
        os.replace(self.state_file(), saved)
        try:
            status, body, h = self.call("GET", "/state")
            self.assertEqual((status, body, h["ETag"]), (200, "{}", '"0"'))
        finally:
            os.replace(saved, self.state_file())

    def test_the_first_write_of_a_day_keeps_a_copy(self):
        self.call("PUT", "/state", {"n": 1}, {"If-Match": "*"})
        folder = os.path.join(os.path.dirname(self.state_file()), "backups")
        shutil.rmtree(folder, ignore_errors=True)
        self.call("PUT", "/state", {"n": 2}, {"If-Match": "*"})
        self.call("PUT", "/state", {"n": 3}, {"If-Match": "*"})
        copies = os.listdir(folder)
        self.assertEqual(len(copies), 1)
        with open(os.path.join(folder, copies[0])) as f:
            self.assertEqual(json.load(f)["data"], {"n": 1}, "what was there before the day's first write")

    def test_old_copies_go(self):
        self.call("PUT", "/state", {"n": 1}, {"If-Match": "*"})
        folder = os.path.join(os.path.dirname(self.state_file()), "backups")
        shutil.rmtree(folder, ignore_errors=True)
        os.makedirs(folder)
        for day in range(1, srv.BACKUP_DAYS + 5):
            open(os.path.join(folder, f"state-2020-01-{day:02d}.json"), "w").close()
        self.call("PUT", "/state", {"n": 2}, {"If-Match": "*"})
        self.assertEqual(len(os.listdir(folder)), srv.BACKUP_DAYS)

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

    def test_a_wait_that_is_no_number_is_refused(self):
        _, _, headers = self.call("GET", "/state")
        # NaN is past every comparison: the long poll would never see its time run out.
        for bad in ("nan", "-nan", "x"):
            req = urllib.request.Request(self.base + f"/state?wait={bad}")
            req.add_header("Authorization", "Basic " + base64.b64encode(b"sonn:secret").decode())
            req.add_header("If-None-Match", headers["ETag"])
            try:
                with urllib.request.urlopen(req, timeout=3) as r:
                    status = r.status
            except urllib.error.HTTPError as e:
                status = e.code
            except OSError:
                status = None  # no answer in time
                self.call("PUT", "/state", {"woken": bad}, {"If-Match": "*"})  # lets the stuck poll go
                _, _, headers = self.call("GET", "/state")
            self.assertEqual(400, status, bad)

    def test_login_and_open_paths(self):
        self.assertEqual(401, self.call("GET", "/state", password=WRONG)[0])
        status, _, headers = self.call("GET", "/state", password=WRONG, headers={"X-Xrn-Client": "web"})
        self.assertEqual(401, status)
        self.assertIsNone(headers.get("WWW-Authenticate"))
        with urllib.request.urlopen(self.base + "/xrnrule/health") as r:
            self.assertEqual("ok", r.read().decode())
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("status", r.read().decode())

    def test_updates_serve_the_extension_openly_and_the_app_only_with_the_login(self):
        folder = os.path.join(srv.args.data, "update")
        os.makedirs(folder, exist_ok=True)
        files = {"updates.xml": b"<gupdate/>", "xrnrule-2.1.0.crx": b"Cr24" + bytes(3000),
                 "app.json": b'{"versionCode":2}', "xrnrule-2.apk": b"PK" + bytes(3000)}
        for name, data in files.items():
            with open(os.path.join(folder, name), "wb") as f:
                f.write(data)

        def get(path, password=None):
            req = urllib.request.Request(self.base + path)
            req.add_header("Accept-Encoding", "gzip")  # never applied: these go out as they are
            if password:
                req.add_header("Authorization", "Basic " + base64.b64encode(f"sonn:{password}".encode()).decode())
            try:
                with urllib.request.urlopen(req, timeout=10) as r:
                    return r.status, r.read(), r.headers
            except urllib.error.HTTPError as e:
                return e.code, e.read(), e.headers

        status, body, headers = get("/xrnrule/update/xrnrule-2.1.0.crx")
        self.assertEqual((200, files["xrnrule-2.1.0.crx"]), (status, body))
        self.assertEqual("application/x-chrome-extension", headers["Content-Type"])
        self.assertIsNone(headers.get("Content-Encoding"))
        self.assertEqual(200, get("/update/updates.xml")[0])
        for name in ("app.json", "xrnrule-2.apk"):
            self.assertEqual(401, get(f"/update/{name}")[0], name)
            self.assertEqual(401, get(f"/update/{name}", password=WRONG)[0], name)
            status, body, _ = get(f"/update/{name}", password=RIGHT)
            self.assertEqual((200, files[name]), (status, body))
        # A release that isn't there, without the login, looks like one that is.
        self.assertEqual(401, get("/update/xrnrule-9.apk")[0])
        self.assertEqual(404, get("/update/xrnrule-9.apk", password=RIGHT)[0])
        self.assertEqual(404, get("/update/missing.crx")[0])
        for bad in ("..%2Fusers%2Fsonn%2Fstate.json", "../x.crx", ".hidden.crx", "state.json.gz", "x.crx/y", ""):
            self.assertEqual(404, get(f"/update/{bad}")[0], bad)

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
            self.assertEqual(401, self.call("GET", "/state", password=WRONG, headers=guesser)[0])
        # Locked out now, even with the right password; everyone else isn't.
        self.assertEqual(429, self.call("GET", "/state", headers=guesser)[0])
        self.assertEqual(200, self.call("GET", "/state", headers={"X-Forwarded-For": "203.0.113.10"})[0])

    def test_a_made_up_first_hop_doesnt_dodge_the_lockout(self):
        for i in range(srv.FAIL_LIMIT):
            forwarded = {"X-Forwarded-For": f"198.51.100.{i}, 203.0.113.40"}  # what a proxy appends to
            self.assertEqual(401, self.call("GET", "/state", password=WRONG, headers=forwarded)[0])
        forwarded = {"X-Forwarded-For": "198.51.100.200, 203.0.113.40"}
        self.assertEqual(429, self.call("GET", "/state", headers=forwarded)[0])

    def test_guesses_sent_at_once_still_stop_at_the_limit(self):
        def slow_wrong(header):  # bcrypt takes a while: every guess is in flight at once
            time.sleep(0.3)
            return None
        guesser = {"X-Forwarded-For": "203.0.113.30"}
        limit, n = 3, 12  # a limit the test server's listen backlog lets every guess reach at once
        start = threading.Barrier(n)
        def guess(_):
            start.wait()
            return self.call("GET", "/state", password=WRONG, headers=guesser)[0]
        with unittest.mock.patch.object(srv, "check_login", slow_wrong), unittest.mock.patch.object(srv, "FAIL_LIMIT", limit):
            with concurrent.futures.ThreadPoolExecutor(n) as pool:
                statuses = list(pool.map(guess, range(n)))
        self.assertEqual(limit, statuses.count(401))
        self.assertEqual(n - limit, statuses.count(429))

    def test_a_right_password_clears_earlier_mistakes(self):
        typo = {"X-Forwarded-For": "203.0.113.20"}
        for _ in range(srv.FAIL_LIMIT - 1):
            self.call("GET", "/state", password=WRONG, headers=typo)
        self.assertEqual(200, self.call("GET", "/state", headers=typo)[0])
        self.assertEqual(401, self.call("GET", "/state", password=WRONG, headers=typo)[0])
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

    def test_shortcuts_are_kept_as_events(self):
        now = int(time.time() * 1000)
        batch = {"events": [
            {"t": "bought", "at": now - 2000, "block": "b1,b2", "ms": 240000},
            {"t": "break", "at": now - 1000, "ms": 900000},
            {"t": "nonsense", "at": now},
        ]}
        status, body, _ = self.call("POST", "/events", batch, {"X-Device": "laptop"})
        self.assertEqual((200, 2), (status, json.loads(body)["added"]))
        _, body, _ = self.call("GET", f"/events?since={now - 3000}")
        got = [e for e in json.loads(body)["events"] if e["t"] in ("bought", "break")]
        self.assertEqual(["bought", "break"], [e["t"] for e in got])
        self.assertEqual("b1,b2", got[0]["block"])
        self.assertEqual(900000, got[1]["ms"])

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

    def web(self, method, path, cookie=None, client=True, auth=None):
        """A request as the web pages make it: no password, the login cookie and X-Xrn-Client."""
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        headers = {}
        if cookie:
            headers["Cookie"] = f"other=1; {srv.SESSION_COOKIE}={cookie}"
        if client:
            headers["X-Xrn-Client"] = "web"
        if auth:
            headers["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
        conn.request(method, path, headers=headers)
        res = conn.getresponse()
        res.read()
        conn.close()
        return res.status, res.getheader("Set-Cookie") or ""

    def sign_in(self):
        status, set_cookie = self.web("POST", "/xrnrule/session", auth="sonn:secret")
        self.assertEqual(200, status)
        return set_cookie.split(";")[0].partition("=")[2]

    def test_a_password_signs_the_web_pages_in_with_a_cookie_scripts_cannot_read(self):
        status, set_cookie = self.web("POST", "/xrnrule/session", auth="sonn:secret")
        self.assertEqual(200, status)
        for flag in ("HttpOnly", "Secure", "SameSite=Strict", "Path=/", f"Max-Age={srv.SESSION_S}"):
            self.assertIn(flag, set_cookie)
        cookie = set_cookie.split(";")[0].partition("=")[2]
        self.assertEqual(200, self.web("GET", "/xrnrule/state", cookie)[0])
        self.assertEqual(200, self.web("GET", "/devices", cookie)[0])
        # Without the header another site's page can't add, the cookie is worth nothing.
        self.assertEqual(401, self.web("GET", "/state", cookie, client=False)[0])
        # A cookie can't make itself a new one, and a wrong password makes none.
        self.assertEqual(401, self.web("POST", "/session", cookie)[0])
        self.assertEqual((401, ""), self.web("POST", "/session", auth="sonn:nope"))
        status, set_cookie = self.web("DELETE", "/session", cookie)
        self.assertEqual(200, status)
        self.assertIn("Max-Age=0", set_cookie)

    def test_a_forged_expired_or_old_password_cookie_is_refused(self):
        cookie = self.sign_in()
        who, expires, mac = cookie.split(".")
        other = base64.urlsafe_b64encode(b"admin").decode().rstrip("=")
        for bad in (f"{other}.{expires}.{mac}", f"{who}.{int(expires) + 1}.{mac}", f"{who}.{expires}.{'0' * 64}",
                    "garbage", "", f"{who}.{expires}", "%%%.1.2"):
            self.assertEqual(401, self.web("GET", "/state", bad)[0], bad)
        body = f"{who}.{int(time.time()) - 1}"
        self.assertEqual(401, self.web("GET", "/state", f"{body}.{srv.session_mac(body, srv.password_tag('sonn'))}")[0])
        # A new password ends the sessions made with the old one.
        import bcrypt
        with open(srv.args.users) as f:
            old = f.read()
        try:
            with open(srv.args.users, "w") as f:
                f.write("sonn:" + bcrypt.hashpw(b"secret", bcrypt.gensalt(4)).decode() + "\n")
            self.assertEqual(401, self.web("GET", "/state", cookie)[0])
        finally:
            with open(srv.args.users, "w") as f:
                f.write(old)
        self.assertEqual(200, self.web("GET", "/state", cookie)[0])

    def test_a_cookie_that_isnt_ascii_is_refused_not_a_crash(self):
        who, expires, mac = self.sign_in().split(".")
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        conn.putrequest("GET", "/state")
        conn.putheader("X-Xrn-Client", "web")
        conn.putheader("Cookie", f"{srv.SESSION_COOKIE}={who}.{expires}.{mac[:-1]}".encode() + b"\xe9")
        conn.endheaders()
        with conn.getresponse() as r:  # no answer at all raises here
            self.assertEqual(401, r.status)
        conn.close()

    def test_the_session_key_is_private_and_shared_by_every_server_on_the_users_file(self):
        path = os.path.join(os.path.dirname(srv.args.users), "session.key")
        self.assertEqual(0o600, os.stat(path).st_mode & 0o777)
        self.assertEqual(srv.session_key, srv.load_session_key())
        self.assertGreaterEqual(len(srv.session_key), 32)


if __name__ == "__main__":
    unittest.main()
