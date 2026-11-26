"""Tests for xrnrule-sync.py: optimistic concurrency, the cheap 304 check, the long-poll.

    python3 -m unittest server/test_xrnrule_sync.py     (from the repo root)
"""
import base64
import concurrent.futures
import gzip
import hashlib
import http.client
import importlib.util
import io
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.parse
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
            f.write("alex:" + bcrypt.hashpw(b"secret", bcrypt.gensalt(4)).decode() + "\n")
            f.write("other:" + bcrypt.hashpw(b"secret2", bcrypt.gensalt(4)).decode() + "\n")
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
        req.add_header("Authorization", "Basic " + base64.b64encode(f"alex:{password}".encode()).decode())
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read().decode(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(), e.headers

    def state_file(self):
        return os.path.join(srv.args.data, "users", "alex", "state.json")

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
            req.add_header("Authorization", "Basic " + base64.b64encode(b"alex:secret").decode())
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

    LOGIN = b"Authorization: Basic " + base64.b64encode(b"alex:secret") + b"\r\n"

    def logged_after(self, raw, close, done=("Traceback", "dropped", "long-poll", '" 400 ')):
        """What the server logs, and answers, once [raw] is sent and [close] has ended the connection."""
        answer = []
        with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as log:
            with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
                s.sendall(raw)
                time.sleep(0.3)
                answer.append(close(s))
            for _ in range(500):  # the handler finishes on its own thread
                if any(word in log.getvalue() for word in done):
                    break
                time.sleep(0.01)
            time.sleep(0.1)
        return answer[0], log.getvalue()

    def test_a_long_poll_whose_caller_gave_up_ends_with_no_answer_and_no_traceback(self):
        # Funnel closes this side once the laptop gives up on a long-poll, which a stalled tailnet
        # path makes routine; the thread was held the rest of the wait for no one.
        _, _, headers = self.call("GET", "/state")
        started = time.monotonic()
        _, log = self.logged_after(
            b"GET /state?wait=30 HTTP/1.1\r\nHost: x\r\n" + self.LOGIN +
            b"If-None-Match: " + headers["ETag"].encode() + b"\r\n\r\n", lambda s: s.close())
        self.assertIn("caller left a long-poll", log)
        self.assertNotIn("Traceback", log)
        self.assertLess(time.monotonic() - started, 10)  # not the 30 s it asked for

    def cut_put(self):
        body = json.dumps({"blocks": ["cut"] * 500}).encode()
        return (b'PUT /state HTTP/1.1\r\nHost: x\r\n' + self.LOGIN + b'If-Match: "999999"\r\n'
                b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body[:len(body) // 2])

    def test_an_upload_cut_short_is_answered_as_such(self):
        def half_close(s):
            s.shutdown(socket.SHUT_WR)
            return b"".join(iter(lambda: s.recv(4096), b""))
        answer, log = self.logged_after(self.cut_put(), half_close)
        self.assertRegex(answer, rb"^HTTP/1\.[01] 400 ")
        self.assertIn(b"the upload stopped short", answer)
        self.assertNotIn("Traceback", log)

    def test_a_caller_that_hangs_up_mid_upload_is_one_line_in_the_log_not_a_traceback(self):
        def reset(s):
            s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        _, log = self.logged_after(self.cut_put(), reset)
        self.assertNotIn("Traceback", log)
        self.assertIn("connection dropped: ConnectionResetError", log)

    def test_head_is_a_get_without_the_body(self):
        # curl -I got 501 "Unsupported method", which xrnhub's log page shows as an error.
        folder = os.path.join(srv.args.data, "update")
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "updates.xml"), "wb") as f:
            f.write(b"<gupdate/>")
        for path, login, header in ((b"/health", b"", b"Content-Length: 2"),
                                    (b"/update/updates.xml", b"", b"Content-Length: 10"),
                                    (b"/state", self.LOGIN, b"ETag: ")):
            with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
                s.sendall(b"HEAD " + path + b" HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" + login + b"\r\n")
                answer = b"".join(iter(lambda: s.recv(4096), b""))
            self.assertRegex(answer, rb"^HTTP/1\.[01] 200 ", path)
            self.assertIn(header, answer, path)
            self.assertTrue(answer.endswith(b"\r\n\r\n"), (path, answer[-40:]))

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
                req.add_header("Authorization", "Basic " + base64.b64encode(f"alex:{password}".encode()).decode())
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
        for bad in ("..%2Fusers%2Falex%2Fstate.json", "../x.crx", ".hidden.crx", "state.json.gz", "x.crx/y", ""):
            self.assertEqual(404, get(f"/update/{bad}")[0], bad)

    def test_gzip_bodies_are_unpacked_only_so_far(self):
        def put(raw):
            req = urllib.request.Request(self.base + "/state", method="PUT", data=raw)
            req.add_header("Authorization", "Basic " + base64.b64encode(b"alex:secret").decode())
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

    def test_json_nested_too_deep_is_a_400(self):
        deep = b"[" * 200_000
        for method, path in (("PUT", "/state"), ("POST", "/events")):
            conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
            conn.request(method, path, body=deep, headers={
                "Authorization": "Basic " + base64.b64encode(b"alex:secret").decode(), "If-Match": "*", "X-Device": "phone"})
            with conn.getresponse() as r:  # no answer at all raises here
                self.assertEqual(400, r.status, path)
            conn.close()

    def test_a_document_too_deep_to_write_back_is_refused_before_anything_is_written(self):
        # json.loads takes a few thousand levels (tens of thousands on Python 3.14), json.dump only
        # about a thousand. A document in between was taken, the day's copy was kept in backups/,
        # and then writing it raised RecursionError: the connection closed with no answer, after a
        # write that never happened, with a half written state.json.tmp left beside it.
        _, _, h = self.call("PUT", "/state", {"blocks": {"kept": {}}}, {"If-Match": "*"})
        backups = os.path.join(os.path.dirname(self.state_file()), "backups")
        shutil.rmtree(backups, ignore_errors=True)
        deep = b'{"blocks":{"a":{"rules":' + b"[" * 2000 + b"]" * 2000 + b"}}}"
        status, body, _ = self.raw("PUT", "/state", deep, {"If-Match": h["ETag"]})
        self.assertEqual((400, True), (status, b"nested" in body))
        status, body, headers = self.call("GET", "/state")
        self.assertEqual((h["ETag"], {"blocks": {"kept": {}}}), (headers["ETag"], json.loads(body)))
        self.assertFalse(os.path.exists(backups))
        self.assertFalse(os.path.exists(self.state_file() + ".tmp"))

    def test_a_document_nested_up_to_the_limit_is_taken(self):
        def nested(depth):  # {"blocks": {"a": {"rules": [[...]]}}}: the outermost object is level 1
            return b'{"blocks":{"a":{"rules":' + b"[" * (depth - 3) + b"]" * (depth - 3) + b"}}}"
        status, _, h = self.raw("PUT", "/state", nested(srv.MAX_DEPTH), {"If-Match": "*"})
        self.assertEqual(200, status)
        status, _, _ = self.raw("PUT", "/state", nested(srv.MAX_DEPTH + 1), {"If-Match": h["ETag"]})
        self.assertEqual(400, status)
        self.assertEqual(h["ETag"], self.call("GET", "/state")[2]["ETag"])

    def test_a_broken_content_length_is_a_400(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        conn.putrequest("PUT", "/state")
        conn.putheader("Authorization", "Basic " + base64.b64encode(b"alex:secret").decode())
        conn.putheader("Content-Length", "lots")
        conn.endheaders()
        with conn.getresponse() as r:
            self.assertEqual(400, r.status)
        conn.close()

    def test_a_connection_that_goes_quiet_is_let_go(self):
        # With no timeout, a client that stopped half way through its request held a thread for good.
        self.assertGreater(srv.Handler.timeout, srv.MAX_WAIT_S)  # and longer than any long poll
        with unittest.mock.patch.object(srv.Handler, "timeout", 0.3):
            with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
                s.sendall(b"GET /state HTTP/1.1\r\nHost: x\r\n")  # and then nothing
                start = time.monotonic()
                self.assertEqual(b"", s.recv(100))
                self.assertLess(time.monotonic() - start, 5)

    def test_an_unparseable_request_gets_an_answer(self):
        with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
            s.sendall(b"GARBAGE\r\n\r\n")
            self.assertIn(b"400", s.recv(200))

    def test_the_log_shows_a_callers_control_characters_escaped(self):
        # The request line and X-Forwarded-For are the caller's. Written raw, an escape sequence
        # rewrote the terminal, and a \r started a line of its own where xrnhub reads the log.
        forged = b'2026-11-06_01:09:14.23919 198.51.100.66 "POST /api/changes HTTP/1.1" 200 -'
        with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as log:
            for raw in (b"GET /a\x1b[2Jb HTTP/1.1\r\nX-Forwarded-For: 203.0.113.70\x1b]0;hi\x07\r\n\r\n",
                        b"GET /a\r" + forged + b" HTTP/1.1\r\n\r\n"):
                with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
                    s.sendall(raw)
                    while s.recv(4096):
                        pass
        text = log.getvalue()
        self.assertIn("203.0.113.70\\x1b]0;hi\\x07", text)
        self.assertIn("/a\\x1b[2Jb", text)
        self.assertIn("/a\\x0d2026-11-06", text)
        self.assertEqual([], [c for c in text if c != "\n" and (ord(c) < 0x20 or 0x7F <= ord(c) < 0xA0)])

    def test_no_answer_names_the_python_version(self):
        # The Server header said "<name>/1 Python/3.x.y" on every answer, error pages included.
        for method in ("GET", "PATCH"):  # PATCH: http.server's own 501 page
            conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
            conn.request(method, "/health")
            res = conn.getresponse()
            res.read()
            conn.close()
            self.assertNotIn("Python", res.getheader("Server"), method)

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

    def test_an_ipv6_guesser_is_its_64_not_each_address_in_it(self):
        def guess(forwarded):
            return self.call("GET", "/state", password=WRONG, headers={"X-Forwarded-For": forwarded})[0]
        for i in range(srv.FAIL_LIMIT):
            self.assertEqual(401, guess(f"2001:db8:1:2::{i + 1:x}"))
        self.assertEqual(429, guess("2001:db8:1:2:aaaa::1"))
        self.assertEqual(401, guess("2001:db8:1:3::1"))  # the /64 next door isn't held
        self.assertEqual(401, guess("::ffff:203.0.113.41"))  # an IPv4 caller written as IPv6
        self.assertIn("203.0.113.41", srv.failures)  # ...is counted as that IPv4 address

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

    def test_a_request_with_no_password_isnt_held(self):
        # The hold after a wrong password slows a guesser down. One that sent no password guessed
        # nothing, and each held a thread for the hold all the same.
        def timed(addr, password=None):
            conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
            headers = {"X-Forwarded-For": addr}
            if password:
                headers["Authorization"] = "Basic " + base64.b64encode(f"alex:{password}".encode()).decode()
            start = time.monotonic()
            conn.request("GET", "/state", headers=headers)
            status = conn.getresponse().status
            conn.close()
            return status, time.monotonic() - start
        srv.failures["203.0.113.61"] = [(time.monotonic(), "alex")] * srv.FAIL_LIMIT  # locked out
        with unittest.mock.patch.object(srv, "FAIL_DELAY_S", 0.5):
            for addr, status in (("203.0.113.60", 401), ("203.0.113.61", 429)):
                got, took = timed(addr)
                self.assertEqual(status, got)
                self.assertLess(took, 0.4, addr)
                got, took = timed(addr, WRONG)
                self.assertEqual(status, got)
                self.assertGreaterEqual(took, 0.5, addr)

    def test_a_name_that_isnt_there_costs_a_check_like_one_that_is(self):
        import bcrypt

        def checks(login):
            header = "Basic " + base64.b64encode(login.encode()).decode()
            with unittest.mock.patch.object(bcrypt, "checkpw", wraps=bcrypt.checkpw) as checkpw:
                self.assertIsNone(srv.check_login(header))
            return checkpw.call_count
        self.assertEqual(1, checks("alex:nope"))
        self.assertEqual(1, checks("nobody:nope"))  # none: the answer came sooner, and said so
        self.assertIsNone(srv.check_login("Basic " + base64.b64encode(b"nobody:secret").decode()))

    def test_a_right_password_clears_earlier_mistakes(self):
        typo = {"X-Forwarded-For": "203.0.113.20"}
        for _ in range(srv.FAIL_LIMIT - 1):
            self.call("GET", "/state", password=WRONG, headers=typo)
        srv.auth_cache.clear()  # typed in, not the copy a device keeps sending
        self.assertEqual(200, self.call("GET", "/state", headers=typo)[0])
        self.assertEqual(401, self.call("GET", "/state", password=WRONG, headers=typo)[0])
        self.assertEqual(200, self.call("GET", "/state", headers=typo)[0])

    def guess_as(self, name, password, addr):
        req = urllib.request.Request(self.base + "/state")
        req.add_header("Authorization", "Basic " + base64.b64encode(f"{name}:{password}".encode()).decode())
        req.add_header("X-Forwarded-For", addr)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code

    def test_a_right_login_clears_only_its_own_users_mistakes(self):
        # A login for one account wiped the whole address's count: guesses at another name from
        # the same address started over with every login of the first.
        addr = "203.0.113.80"
        for _ in range(5):
            self.assertEqual(401, self.guess_as("admin", WRONG, addr))
        for _ in range(4):
            self.assertEqual(401, self.guess_as("alex", WRONG, addr))
        srv.auth_cache.clear()
        self.assertEqual(200, self.guess_as("alex", RIGHT, addr))  # alex's four go, admin's five stay
        for _ in range(srv.FAIL_LIMIT - 5):
            self.assertEqual(401, self.guess_as("admin", WRONG, addr))
        self.assertEqual(429, self.guess_as("admin", WRONG, addr))

    def test_a_device_sending_the_password_it_had_clears_nothing(self):
        # A device at the same address as a guesser (home Wi-Fi, a carrier's shared address)
        # cleared the guesser's count with every sync, so the guesses never ran out. A password
        # the server already knows is right (its auth cache) is a session, not a login.
        addr = "203.0.113.81"
        self.assertEqual(200, self.guess_as("alex", RIGHT, addr))  # the device, signed in
        for _ in range(srv.FAIL_LIMIT - 1):
            self.assertEqual(401, self.guess_as("alex", WRONG, addr))
            self.assertEqual(200, self.guess_as("alex", RIGHT, addr))  # it syncs on
        self.assertEqual(401, self.guess_as("alex", WRONG, addr))
        self.assertEqual(429, self.guess_as("alex", WRONG, addr))

    def test_a_login_in_flight_is_not_a_wrong_guess(self):
        # Counted as wrong before it was checked, a right login being checked took a place: two
        # devices signing in at once, at an address with a few mistakes behind it, got a 429.
        addr = "203.0.113.82"
        real = srv.check_login
        def slow(header):
            time.sleep(0.3)
            return real(header)
        with unittest.mock.patch.object(srv, "FAIL_LIMIT", 3):
            self.assertEqual(401, self.guess_as("alex", WRONG, addr))
            self.assertEqual(401, self.guess_as("alex", WRONG, addr))  # one place left
            srv.auth_cache.clear()
            start = threading.Barrier(2)
            def login(_):
                start.wait()
                return self.guess_as("alex", RIGHT, addr)
            with unittest.mock.patch.object(srv, "check_login", slow):
                with concurrent.futures.ThreadPoolExecutor(2) as pool:
                    self.assertEqual([200, 200], list(pool.map(login, range(2))))

    def test_devices_report_when_each_last_checked_in(self):
        before = int(time.time() * 1000)
        self.call("GET", "/state", headers={"X-Device": "phone"})
        status, body, _ = self.call("GET", "/xrnrule/devices")
        self.assertEqual(200, status)
        seen = json.loads(body)
        self.assertGreaterEqual(seen["phone"], before)
        self.assertNotIn("", seen)  # the status page itself sends no X-Device
        self.assertTrue(os.path.isfile(srv.devices_path("alex")))

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

    def test_each_days_limit_use_is_kept_after_the_state_moves_on(self):
        # A device's use of a limit is in the state for its current day only; the history pages
        # show a block's use against its limit day by day, so the server keeps each day's.
        now = int(time.time() * 1000)
        day1, day2 = now - 2 * 86400 * 1000, now - 86400 * 1000
        use = lambda day, ms: {"dayStartWall": day, "ms": ms, "atWall": day + 1000, "opens": 0}
        state = lambda allowance: {"blocks": {}, "allowance": allowance}
        self.assertEqual(200, self.call("PUT", "/state", state({"yt": {"phone-1": use(day1, 600000)}}), {"If-Match": "*"})[0])
        self.call("PUT", "/state", state({"yt": {"phone-1": use(day1, 900000), "laptop-1": use(day1, 60000)}}), {"If-Match": "*"})
        self.call("PUT", "/state", state({"yt": {"phone-1": use(day2, 120000), "laptop-1": use(day1, 30000)}}), {"If-Match": "*"})
        status, body, _ = self.call("GET", "/limits")
        self.assertEqual(200, status)
        got = json.loads(body)["limits"]["yt"]
        self.assertEqual({str(day1): 900000, str(day2): 120000}, got["phone-1"])
        self.assertEqual({str(day1): 60000}, got["laptop-1"], "a day's use only grows: the largest stays")

    def test_limit_use_that_makes_no_sense_is_skipped_and_the_write_still_goes(self):
        now = int(time.time() * 1000)
        old = now - (srv.EVENTS_KEEP_DAYS + 1) * 86400 * 1000
        weird = {"blocks": {}, "allowance": {
            "a": {"phone-1": {"dayStartWall": "today", "ms": 5}, "laptop-1": {"dayStartWall": now - 1000, "ms": -4}},
            "b": ["not", "a", "map"],
            "c": {"phone-1": {"dayStartWall": old, "ms": 60000}},
            "d": {"phone-1": {"dayStartWall": now - 1000, "ms": True}},
        }}
        self.assertEqual(200, self.call("PUT", "/state", weird, {"If-Match": "*"})[0])
        got = json.loads(self.call("GET", "/limits")[1])["limits"]
        self.assertFalse({"a", "b", "c", "d"} & set(got), got)
        self.assertEqual(401, self.call("GET", "/limits", password=WRONG)[0])

    # ---- screen time (docs/laptop-screen-time.md) ----------------------------------------------

    @staticmethod
    def day(back=0):
        return (srv.datetime.now(srv.timezone.utc).date() - srv.timedelta(days=back)).isoformat()

    def test_screen_time_keeps_the_largest_figure_for_each_device_day_and_app(self):
        today, yesterday = self.day(), self.day(1)
        first = {"days": {today: {"apps": {"zen": 600000, "steam": 60000}, "sites": {"example.org": 300000}},
                          yesterday: {"apps": {"zen": 7200000}}},
                 "names": {"zen": "Zen Browser", "steam": "Steam"}}
        status, body, _ = self.call("POST", "/usage", first, {"X-Device": "laptop"})
        self.assertEqual(200, status)
        self.assertEqual(6, json.loads(body)["grew"])  # four figures and two names
        # Later the same day: more of one, and a copy that lost the other figure; then a resend.
        later = {"days": {today: {"apps": {"zen": 900000}, "sites": {"example.org": 100000}}}}
        self.assertEqual(1, json.loads(self.call("POST", "/usage", later, {"X-Device": "laptop"})[1])["grew"])
        self.assertEqual(0, json.loads(self.call("POST", "/usage", later, {"X-Device": "laptop"})[1])["grew"])
        # Another device's own.
        self.call("POST", "/usage", {"days": {today: {"apps": {"zen": 5000}}}}, {"X-Device": "laptop-2"})
        status, body, _ = self.call("GET", f"/usage?since={yesterday}")
        self.assertEqual(200, status)
        got = json.loads(body)
        self.assertEqual({"apps": {"zen": 900000, "steam": 60000}, "sites": {"example.org": 300000}}, got["usage"]["laptop"][today])
        self.assertEqual({"apps": {"zen": 7200000}, "sites": {}}, got["usage"]["laptop"][yesterday])
        self.assertEqual({"zen": 5000}, got["usage"]["laptop-2"][today]["apps"])
        self.assertEqual("Steam", got["names"]["steam"])
        only_today = json.loads(self.call("GET", f"/usage?since={today}")[1])["usage"]["laptop"]
        self.assertEqual([today], list(only_today))

    def test_the_phone_and_the_laptop_each_keep_their_own_screen_time(self):
        # The phone sends as the laptop does: its packages, its sites, its apps' names (UsageUpload).
        today = self.day()
        phone = {"days": {today: {"apps": {"com.example.reader": 900000, "com.example.chat_2": 60000},
                                  "sites": {"example.org": 300000}}},
                 "names": {"com.example.reader": "Reader", "com.example.chat_2": "Chat"}}
        laptop = {"days": {today: {"apps": {"org.example.Editor": 600000}, "sites": {"example.org": 200000}}},
                  "names": {"org.example.Editor": "Editor"}}
        self.assertEqual(5, json.loads(self.call("POST", "/usage", phone, {"X-Device": "phone"})[1])["grew"])
        self.call("POST", "/usage", laptop, {"X-Device": "laptop"})
        got = json.loads(self.call("GET", f"/usage?since={today}")[1])
        self.assertEqual({"apps": {"com.example.reader": 900000, "com.example.chat_2": 60000}, "sites": {"example.org": 300000}},
                         got["usage"]["phone"][today])
        self.assertEqual(600000, got["usage"]["laptop"][today]["apps"]["org.example.Editor"])
        self.assertNotIn("com.example.reader", got["usage"]["laptop"][today]["apps"])
        self.assertEqual(("Reader", "Chat", "Editor"),
                         (got["names"]["com.example.reader"], got["names"]["com.example.chat_2"], got["names"]["org.example.Editor"]))
        # A phone's resend of a day it counted less of (Android trimmed its history) lowers nothing.
        less = {"days": {today: {"apps": {"com.example.reader": 1000}}}}
        self.assertEqual(0, json.loads(self.call("POST", "/usage", less, {"X-Device": "phone"})[1])["grew"])
        again = json.loads(self.call("GET", f"/usage?since={today}")[1])
        self.assertEqual(900000, again["usage"]["phone"][today]["apps"]["com.example.reader"])

    def test_screen_time_that_makes_no_sense_is_skipped(self):
        today = self.day()
        weird = {"days": {
            "2026-02-30": {"apps": {"zen": 1000}},  # no such day
            "yesterday": {"apps": {"zen": 1000}},
            self.day(srv.EVENTS_KEEP_DAYS + 1): {"apps": {"zen": 1000}},  # past what's kept
            self.day(-3): {"apps": {"zen": 1000}},  # three days ahead
            today: {
                "apps": {"zen": -1, "steam": True, "game": srv.USAGE_MAX_MS + 1, "bad\nid": 5, "ok": 2000, "x" * 201: 5},
                "sites": {"Example.org": 1000, "a b.com": 1000, "fine.example": 3000},
            },
            self.day(1): ["not", "a", "day"],
        }, "names": {"ok": "OK", "bad\nid": "Bad", "x": 7}}
        self.assertEqual(3, srv.add_usage("odd", "laptop", weird))
        with srv.usage_lock:
            kept = srv.load_usage("odd")
        self.assertEqual({today: {"apps": {"ok": 2000}, "sites": {"fine.example": 3000}}}, kept["days"]["laptop"])
        self.assertEqual({"ok": "OK"}, kept["names"])
        self.assertEqual(400, self.call("POST", "/usage", {"days": []}, {"X-Device": "laptop"})[0])
        self.assertEqual(400, self.call("POST", "/usage", {"days": {}})[0])  # no X-Device
        self.assertEqual(400, self.call("GET", "/usage?since=last-week")[0])
        self.assertEqual(401, self.call("GET", "/usage", password=WRONG)[0])
        self.assertEqual(401, self.call("POST", "/usage", {"days": {}}, {"X-Device": "laptop"}, password=WRONG)[0])

    def test_a_day_holds_at_most_so_many_apps(self):
        today = self.day()
        with unittest.mock.patch.object(srv, "USAGE_MAX_KEYS", 3):
            srv.add_usage("many", "laptop", {"days": {today: {"apps": {f"a{i}": 1000 for i in range(5)}}}})
            srv.add_usage("many", "laptop", {"days": {today: {"apps": {"a9": 1000, "a0": 5000}}}})
            with srv.usage_lock:
                self.assertEqual({"a0": 5000, "a1": 1000, "a2": 1000}, srv.load_usage("many")["days"]["laptop"][today]["apps"])

    def test_screen_time_is_on_disk_and_old_days_go(self):
        today, old = self.day(), self.day(srv.EVENTS_KEEP_DAYS + 5)
        path = os.path.join(srv.args.data, "users", "kept", "usage.json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:  # as an earlier run left it, a day since aged out
            json.dump({"days": {"laptop": {old: {"apps": {"zen": 1000}, "sites": {}}}}, "names": {}}, f)
        with unittest.mock.patch.object(srv, "USAGE_WRITE_S", 0):
            srv.add_usage("kept", "laptop", {"days": {today: {"apps": {"zen": 42000}}}})
        with open(path) as f:
            on_disk = json.load(f)
        self.assertEqual({today: {"apps": {"zen": 42000}, "sites": {}}}, on_disk["days"]["laptop"])
        # Read in again after a restart.
        with srv.usage_lock:
            srv.usage.pop("kept")
        self.assertEqual(42000, srv.usage_since("kept", srv.date.min)["usage"]["laptop"][today]["apps"]["zen"])

    def test_screen_time_is_written_to_disk_before_it_counts_as_written(self):
        today = self.day()
        path = os.path.join(srv.args.data, "users", "synced", "usage.json")
        real_fsync = os.fsync
        synced = []
        with unittest.mock.patch.object(srv.os, "fsync", side_effect=lambda fd: (synced.append(fd), real_fsync(fd))):
            srv.add_usage("synced", "laptop", {"days": {today: {"apps": {"zen": 1000}}}})
        self.assertEqual(1, len(synced), "flushed to disk before the rename, as state.json is")
        # A write that fails stays to do: the next call tries again, whatever USAGE_WRITE_S says.
        with unittest.mock.patch.object(srv, "USAGE_WRITE_S", 0), \
                unittest.mock.patch.object(srv.os, "replace", side_effect=OSError(28, "No space left on device")):
            srv.add_usage("synced", "laptop", {"days": {today: {"apps": {"zen": 2000}}}})
        with open(path) as f:
            self.assertEqual(1000, json.load(f)["days"]["laptop"][today]["apps"]["zen"])
        with srv.usage_lock:
            self.assertIn("synced", srv.usage_dirty)
            srv.flush_usage("synced", now=True)
        with open(path) as f:
            self.assertEqual(2000, json.load(f)["days"]["laptop"][today]["apps"]["zen"])

    def test_an_unreadable_usage_file_is_kept_aside(self):
        today = self.day()
        folder = os.path.join(srv.args.data, "users", "garbled")
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "usage.json"), "w") as f:
            f.write('{"days": {"laptop": {')  # cut off by a power cut
        srv.add_usage("garbled", "laptop", {"days": {today: {"apps": {"zen": 1000}}}})
        aside = [n for n in os.listdir(folder) if n.startswith("usage.json.unreadable-")]
        self.assertEqual(1, len(aside))
        with open(os.path.join(folder, aside[0])) as f:
            self.assertEqual('{"days": {"laptop": {', f.read())

    def test_screen_time_still_in_memory_is_written_at_shutdown(self):
        today = self.day()
        path = os.path.join(srv.args.data, "users", "stopping", "usage.json")
        srv.add_usage("stopping", "laptop", {"days": {today: {"apps": {"zen": 1000}}}})
        srv.add_usage("stopping", "laptop", {"days": {today: {"apps": {"zen": 5000}}}})  # held back: written just now
        srv.flush_all_usage()
        with open(path) as f:
            self.assertEqual(5000, json.load(f)["days"]["laptop"][today]["apps"]["zen"])

    def test_screen_time_is_written_at_most_so_often(self):
        today = self.day()
        path = os.path.join(srv.args.data, "users", "often", "usage.json")
        srv.add_usage("often", "laptop", {"days": {today: {"apps": {"zen": 1000}}}})  # the first goes at once
        with open(path) as f:
            self.assertEqual(1000, json.load(f)["days"]["laptop"][today]["apps"]["zen"])
        srv.add_usage("often", "laptop", {"days": {today: {"apps": {"zen": 2000}}}})
        with open(path) as f:
            self.assertEqual(1000, json.load(f)["days"]["laptop"][today]["apps"]["zen"], "held back")
        with srv.usage_lock:
            srv.usage_written["often"] -= srv.USAGE_WRITE_S
        srv.usage_since("often", srv.date.min)  # a read writes what's held back once it may
        with open(path) as f:
            self.assertEqual(2000, json.load(f)["days"]["laptop"][today]["apps"]["zen"])

    def test_an_event_whose_type_is_no_string_is_dropped_not_a_dropped_connection(self):
        # A list or an object can't be looked up in the set of types: TypeError, no answer at all.
        day_ago = int(time.time() * 1000) - 86400 * 1000  # out of the other tests' way
        batch = {"events": [{"t": [], "at": day_ago}, {"t": {"a": 1}, "at": day_ago}, {"t": "break", "at": day_ago, "ms": 1000}]}
        status, body, _ = self.call("POST", "/events", batch, {"X-Device": "phone"})
        self.assertEqual((200, 1), (status, json.loads(body)["added"]))

    def test_an_event_stamped_far_ahead_of_the_clock_is_refused(self):
        # One would never age out, and xrnhub would count it among today's every day.
        now = int(time.time() * 1000)
        batch = {"events": [
            {"t": "blocked", "at": now + 3600 * 1000, "target": "hour-fast.example"},  # a clock an hour fast
            {"t": "blocked", "at": now + 2 * 86400 * 1000, "target": "days-ahead.example"},
            {"t": "blocked", "at": 10**20, "target": "no-time.example"},
        ]}
        self.assertEqual(1, srv.add_events("ahead", "phone", batch["events"]))  # as POST /events does
        with srv.events_lock:
            self.assertEqual(["hour-fast.example"], [e["target"] for e in srv.load_events("ahead")])
        # One kept before this rule goes when the log is next read in, and the file with it.
        path = os.path.join(srv.args.data, "users", "earlier", "events.jsonl")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            for at, target in ((10**20, "no-time.example"), (now - 1000, "real.example")):
                f.write(json.dumps({"d": "phone", "t": "blocked", "at": at, "target": target}) + "\n")
        with srv.events_lock:
            srv.events.pop("earlier", None)
            self.assertEqual(["real.example"], [e["target"] for e in srv.load_events("earlier")])
        with open(path) as f:
            self.assertNotIn("no-time.example", f.read())

    def test_a_user_keeps_at_most_so_many_events_the_newest(self):
        # A device could send 8 MB of events a batch, and every one was kept, on disk and in memory.
        now = int(time.time() * 1000)
        def batch(first, n):
            return [{"t": "blocked", "at": now - 10**6 + i, "target": f"t{i}"} for i in range(first, first + n)]
        path = os.path.join(srv.args.data, "users", "busy", "events.jsonl")
        def on_disk():
            with open(path) as f:
                return [json.loads(line)["target"] for line in f]
        with unittest.mock.patch.object(srv, "EVENTS_MAX", 10, create=True):
            self.assertEqual(8, srv.add_events("busy", "phone", batch(0, 8)))
            self.assertEqual(5, srv.add_events("busy", "phone", batch(8, 5)))  # 13: down to the newest 9
            with srv.events_lock:
                kept = [e["target"] for e in srv.load_events("busy")]
            self.assertEqual([f"t{i}" for i in range(4, 13)], kept)
            self.assertEqual(kept, on_disk())
            # A log kept before the limit is held to it once it's read in again.
            with open(path, "a") as f:
                for e in batch(13, 5):
                    f.write(json.dumps({"d": "phone", **e}) + "\n")
            with srv.events_lock:
                srv.events.pop("busy")
                self.assertEqual([f"t{i}" for i in range(8, 18)], [e["target"] for e in srv.load_events("busy")])
            self.assertEqual([f"t{i}" for i in range(8, 18)], on_disk())

    def test_a_batch_doesnt_go_over_the_whole_log_again(self):
        # The set of every stored event's key, up to 50,000, was built anew for each POST /events.
        now = int(time.time() * 1000)
        srv.add_events("long", "phone", [{"t": "blocked", "at": now - 10**6 + i, "target": f"t{i}"} for i in range(500)])
        with unittest.mock.patch.object(srv, "event_key", wraps=srv.event_key) as keyed:
            self.assertEqual(1, srv.add_events("long", "phone", [{"t": "blocked", "at": now, "target": "new"}]))
            self.assertLess(keyed.call_count, 10)
        # Still each one once, after the log is cut down to its newest as well.
        self.assertEqual(0, srv.add_events("long", "phone", [{"t": "blocked", "at": now, "target": "new"}]))
        with unittest.mock.patch.object(srv, "EVENTS_MAX", 100):
            srv.add_events("long", "phone", [{"t": "blocked", "at": now - 1, "target": "newer"}])
            with srv.events_lock:
                self.assertEqual(90, len(srv.load_events("long")))
            self.assertEqual(0, srv.add_events("long", "phone", [{"t": "blocked", "at": now - 1, "target": "newer"}]))
            self.assertEqual(1, srv.add_events("long", "phone", [{"t": "blocked", "at": now - 10**6, "target": "t0"}]))

    def test_a_page_on_another_site_cant_write_with_the_browsers_password(self):
        # A browser sends the password it was once given with a form another site posts here,
        # and a text/plain form body can be JSON.
        now = int(time.time() * 1000)
        forged = {"events": [{"t": "blocked", "at": now, "target": "forged.example"}]}
        def post(path, body, headers):
            return self.call("POST", path, body, {"Content-Type": "text/plain", "X-Device": "x", **headers})[0]
        for elsewhere in ({"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"},
                          {"Sec-Fetch-Site": "same-site", "Origin": "https://other.homeserver.example"},
                          {"Origin": "https://evil.example"}):  # a browser from before Sec-Fetch-Site
            self.assertEqual(403, post("/events", forged, elsewhere), elsewhere)
            self.assertEqual(403, post("/session", None, elsewhere), elsewhere)
        self.assertNotIn("forged.example", self.call("GET", f"/events?since={now - 1000}")[1])
        # Reads, this host's own pages (behind the proxy too), the extensions and the devices still do.
        self.assertEqual(200, self.call("GET", "/state", headers={"Sec-Fetch-Site": "cross-site"})[0])
        empty = {"events": []}
        for ours in ({"Sec-Fetch-Site": "same-origin", "Origin": "https://homeserver.example"},
                     {"Origin": f"http://127.0.0.1:{self.httpd.server_address[1]}"},
                     {"Origin": "https://HomeServer.example", "X-Forwarded-Host": "homeserver.example:443"},
                     {"Sec-Fetch-Site": "cross-site", "Origin": "chrome-extension://abcdefghijklmnop"},
                     {"Sec-Fetch-Site": "cross-site", "Origin": "moz-extension://8c2f5e7a-0000-4000-8000-000000000000"},
                     {"Sec-Fetch-Site": "none"},
                     {}):
            self.assertEqual(200, post("/events", empty, ours), ours)

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
        status, set_cookie = self.web("POST", "/xrnrule/session", auth="alex:secret")
        self.assertEqual(200, status)
        return set_cookie.split(";")[0].partition("=")[2]

    def test_a_password_signs_the_web_pages_in_with_a_cookie_scripts_cannot_read(self):
        status, set_cookie = self.web("POST", "/xrnrule/session", auth="alex:secret")
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
        self.assertEqual((401, ""), self.web("POST", "/session", auth="alex:nope"))
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
        self.assertEqual(401, self.web("GET", "/state", f"{body}.{srv.session_mac(body, srv.password_tag('alex'))}")[0])
        # A new password ends the sessions made with the old one.
        import bcrypt
        with open(srv.args.users) as f:
            old = f.read()
        try:
            with open(srv.args.users, "w") as f:
                f.write("alex:" + bcrypt.hashpw(b"secret", bcrypt.gensalt(4)).decode() + "\n")
            self.assertEqual(401, self.web("GET", "/state", cookie)[0])
        finally:
            with open(srv.args.users, "w") as f:
                f.write(old)
        self.assertEqual(200, self.web("GET", "/state", cookie)[0])

    def a_cookie(self, expires):
        """A login cookie as make_session makes it, running out at [expires]: two made in the same
        second are the same cookie."""
        who = base64.urlsafe_b64encode(b"alex").decode().rstrip("=")
        body = f"{who}.{expires}"
        return f"{body}.{srv.session_mac(body, srv.password_tag('alex'))}"

    def shared_list(self):
        """signed-out.json beside the users file: what every server that takes the cookie reads."""
        return os.path.join(os.path.dirname(srv.args.users), "signed-out.json")

    def read_list(self):
        try:
            with open(self.shared_list(), encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return {}

    def write_list(self, doc):
        """The shared list as another server (the hub) writes it: a new file in the old one's place."""
        with open(self.shared_list() + ".test", "w", encoding="utf-8") as f:
            f.write(doc if isinstance(doc, str) else json.dumps(doc))
        os.replace(self.shared_list() + ".test", self.shared_list())

    @staticmethod
    def hash_of(cookie):
        return hashlib.sha256(cookie.encode()).hexdigest()

    def test_a_cookie_signed_out_on_another_server_is_refused_here(self):
        # Signing out of xrnhub ended the cookie there only: a copy of it went on opening the
        # blocks for the rest of its 30 days.
        expires = int(time.time()) + 8000
        cookie = self.a_cookie(expires)
        self.assertEqual(200, self.web("GET", "/state", cookie)[0])
        self.write_list({**self.read_list(), self.hash_of(cookie): expires})
        self.assertEqual(401, self.web("GET", "/state", cookie)[0])
        self.assertEqual(401, self.web("GET", "/devices", cookie)[0])
        self.assertEqual(200, self.call("GET", "/state")[0])  # a password still signs in

    def test_signing_out_ends_the_cookie_here_and_on_every_other_server(self):
        # DELETE /session only told the browser to drop its cookie: a copy of it (another browser,
        # a stolen one) went on signing in, here and on the hub, habits and songs pages.
        expires = int(time.time()) + 8100
        mine, other = self.a_cookie(expires), self.a_cookie(expires + 1)
        self.assertEqual(200, self.web("DELETE", "/xrnrule/session", mine)[0])
        self.assertEqual(401, self.web("GET", "/state", mine)[0])
        self.assertEqual(200, self.web("GET", "/state", other)[0])
        self.assertEqual(expires, self.read_list()[self.hash_of(mine)])
        self.assertEqual(0o600, os.stat(self.shared_list()).st_mode & 0o777)
        # A sign-out with no session of ours puts nothing on the list.
        before = self.read_list()
        for cookie in ("junk", self.a_cookie(int(time.time()) - 5), None):
            self.assertEqual(200, self.web("DELETE", "/session", cookie)[0])
        self.assertEqual(before, self.read_list())

    def test_a_missing_or_broken_list_breaks_no_sign_in(self):
        gone, live = self.a_cookie(int(time.time()) + 8200), self.a_cookie(int(time.time()) + 8300)
        srv.end_session(f"{srv.SESSION_COOKIE}={gone}")
        self.write_list("{half a file")
        with unittest.mock.patch.object(sys, "stderr", io.StringIO()) as said:
            for _ in range(3):
                self.assertEqual(200, self.web("GET", "/state", live)[0])
                # The list read before the file broke still holds.
                self.assertEqual(401, self.web("GET", "/state", gone)[0])
            self.assertEqual(200, self.call("GET", "/state")[0])
            self.assertEqual(200, self.web("POST", "/session", auth="alex:secret")[0])
        self.assertEqual(1, said.getvalue().count("signed-out.json"), said.getvalue())  # one line, not one a request
        os.remove(self.shared_list())
        self.assertEqual(200, self.web("GET", "/state", live)[0])
        self.assertEqual(200, self.call("GET", "/state")[0])
        self.assertEqual(200, self.web("POST", "/session", auth="alex:secret")[0])

    def test_a_new_session_is_never_one_signed_out(self):
        # A cookie is made from the name, the second it runs out and the password, so a sign-in in
        # the second of a sign-out (here or on another server) was handed the cookie just ended.
        clock = unittest.mock.Mock()
        clock.time.return_value = 1_900_000_100.5
        with unittest.mock.patch.object(srv, "time", clock):
            first = srv.make_session("alex")
            self.assertEqual("alex", srv.end_session(f"{srv.SESSION_COOKIE}={first}"))
            again = srv.make_session("alex")
            self.assertEqual(int(first.split(".")[1]) + 1, int(again.split(".")[1]))
            self.assertEqual("alex", srv.check_session(f"{srv.SESSION_COOKIE}={again}"))
            # Signed out by the hub instead, the next one is new again.
            self.write_list({**self.read_list(), self.hash_of(again): int(again.split(".")[1])})
            third = srv.make_session("alex")
            self.assertNotIn(third, (first, again))
            self.assertEqual("alex", srv.check_session(f"{srv.SESSION_COOKIE}={third}"))

    def test_servers_signing_out_at_once_keep_every_sign_out(self):
        # The servers share one file: written without a lock, of two signing out together, one
        # would undo the other.
        go = os.path.join(self.tmp.name, "go")
        script = ("import importlib.util, os, sys, time\n"
                  "spec = importlib.util.spec_from_file_location('other', sys.argv[1])\n"
                  "m = importlib.util.module_from_spec(spec)\n"
                  "spec.loader.exec_module(m)\n"
                  "m.args = type('Args', (), {'users': sys.argv[2]})()\n"
                  "while not os.path.exists(sys.argv[4]): time.sleep(0.001)\n"
                  "for i in range(30): m.sign_out(f'{sys.argv[3]}-{i}.4000000000.x')\n")
        others = [subprocess.Popen([sys.executable, "-c", script, os.path.join(HERE, "xrnrule-sync.py"),
                                    srv.args.users, f"server{n}", go]) for n in range(3)]
        time.sleep(0.3)
        open(go, "w").close()

        def here(n):
            for i in range(30):
                srv.sign_out(f"here{n}-{i}.4000000000.x")

        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            list(pool.map(here, range(2)))
        for other in others:
            self.assertEqual(0, other.wait(timeout=60))
        os.remove(go)
        listed = self.read_list()
        for who in ("server0", "server1", "server2", "here0", "here1"):
            self.assertEqual(30, sum(self.hash_of(f"{who}-{i}.4000000000.x") in listed for i in range(30)), who)
        self.write_list({k: v for k, v in listed.items() if v != 4_000_000_000})

    def test_a_sign_out_drops_the_ones_that_have_run_out(self):
        self.write_list({"a" * 64: int(time.time()) - 5, "b" * 64: int(time.time()) + 600})
        mine = self.a_cookie(int(time.time()) + 8400)
        srv.end_session(f"{srv.SESSION_COOKIE}={mine}")
        listed = self.read_list()
        self.assertNotIn("a" * 64, listed)
        self.assertIn("b" * 64, listed)
        self.assertIn(self.hash_of(mine), listed)

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

    # ---- icons: the logos the pages show ----------------------------------------------------

    PNG = b"\x89PNG\r\n\x1a\n" + bytes(16) + b"a tiny made-up logo"

    def raw(self, method, path, data=None, headers=None, login="alex:secret"):
        """A request with [data] as it is (no JSON): (status, body bytes, headers)."""
        req = urllib.request.Request(self.base + path, method=method, data=data)
        if login:
            req.add_header("Authorization", "Basic " + base64.b64encode(login.encode()).decode())
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    @staticmethod
    def quoted(key):
        return urllib.parse.quote(key, safe="")

    def icons_folder(self, user="alex"):
        return os.path.join(srv.args.data, "users", user, "icons")

    def test_an_icon_sent_is_listed_and_served_as_the_image_it_is(self):
        key = "app:com.example.chat"
        self.assertEqual(204, self.raw("PUT", f"/xrnrule/icons/{self.quoted(key)}", self.PNG, {"X-Device": "phone"})[0])
        status, body, _ = self.raw("GET", "/xrnrule/icons")
        self.assertEqual(200, status)
        listed = json.loads(body)
        self.assertEqual(hashlib.sha256(self.PNG).hexdigest()[:12], listed[key])
        for path in (f"/xrnrule/icons/{self.quoted(key)}", f"/icons/{key}"):  # with and without the prefix, the ':' as is
            status, body, h = self.raw("GET", path)
            self.assertEqual((200, self.PNG), (status, body), path)
            self.assertEqual("image/png", h["Content-Type"])
            self.assertEqual("nosniff", h["X-Content-Type-Options"])
            self.assertEqual("same-origin", h["Cross-Origin-Resource-Policy"])
            self.assertEqual("private, max-age=86400", h["Cache-Control"])
            self.assertEqual(f'"{listed[key]}"', h["ETag"])
            self.assertIn("sandbox", h["Content-Security-Policy"])
        self.assertEqual(304, self.raw("GET", f"/icons/{self.quoted(key)}", headers={"If-None-Match": f'"{listed[key]}"'})[0])
        # Sent again, changed: the new one, under the same key.
        changed = self.PNG + b" v2"
        self.assertEqual(204, self.raw("PUT", f"/icons/{self.quoted(key)}", changed)[0])
        self.assertEqual(changed, self.raw("GET", f"/icons/{self.quoted(key)}")[1])
        self.assertEqual(hashlib.sha256(changed).hexdigest()[:12], json.loads(self.raw("GET", "/icons")[1])[key])
        # A laptop app's id may have a space or a plus in it.
        for other in ("desk:Some App+", "site:news.example.org"):
            self.assertEqual(204, self.raw("PUT", f"/icons/{self.quoted(other)}", self.PNG)[0], other)
            self.assertEqual(200, self.raw("GET", f"/icons/{self.quoted(other)}")[0], other)

    def test_head_of_an_icon_is_its_headers(self):
        self.raw("PUT", "/icons/site%3Ahead.example", self.PNG)
        with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
            s.sendall(b"HEAD /icons/site%3Ahead.example HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" + self.LOGIN + b"\r\n")
            answer = b"".join(iter(lambda: s.recv(4096), b""))
        self.assertRegex(answer, rb"^HTTP/1\.[01] 200 ")
        self.assertIn(b"Content-Type: image/png", answer)
        self.assertTrue(answer.endswith(b"\r\n\r\n"))

    def test_every_answer_says_the_connection_closes_as_it_does(self):
        # The server closes each connection after its answer but didn't say so. Java's HttpClient,
        # the laptop daemon's, kept such a connection for its next request, and a PUT it sent down
        # one this side had closed was lost: an icon pass ended part way.
        self.raw("PUT", "/icons/site%3Aclose.example", self.PNG)
        sent = {
            "a page": b"GET /health HTTP/1.1\r\nHost: x\r\n\r\n",
            "a PUT": b"PUT /icons/site%3Aclose.example HTTP/1.1\r\nHost: x\r\n" + self.LOGIN +
                     b"Content-Length: " + str(len(self.PNG)).encode() + b"\r\n\r\n" + self.PNG,
            "an icon": b"GET /icons/site%3Aclose.example HTTP/1.1\r\nHost: x\r\n" + self.LOGIN + b"\r\n",
            "an icon's 304": b"GET /icons/site%3Aclose.example HTTP/1.1\r\nHost: x\r\n" + self.LOGIN +
                             b'If-None-Match: "' + hashlib.sha256(self.PNG).hexdigest()[:12].encode() + b'"\r\n\r\n',
            "http.server's own error": b"BREW / HTTP/1.1\r\nHost: x\r\n\r\n",  # it says so itself: once
        }
        for what, request in sent.items():
            with socket.create_connection(("127.0.0.1", self.httpd.server_address[1]), timeout=10) as s:
                s.sendall(request)
                answer = b"".join(iter(lambda: s.recv(4096), b""))
            head = answer.split(b"\r\n\r\n", 1)[0].lower()
            self.assertRegex(head, rb"^http/1\.[01] [2-5]\d\d ", what)
            self.assertEqual(1, head.count(b"\r\nconnection:"), what)
            self.assertIn(b"\r\nconnection: close\r\n", head + b"\r\n", what)

    def test_an_icon_the_server_hasnt_got_is_a_404_left_out_of_the_log(self):
        # The pages ask for every target's logo; xrnhub's log page counts each 404 as a warning.
        with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as log:
            status, _, h = self.raw("GET", "/icons/site%3Anever-sent.example")
            self.assertEqual(404, status)
            self.assertEqual("same-origin", h["Cross-Origin-Resource-Policy"])
            self.assertEqual(404, self.raw("GET", "/nothing-here")[0])  # any other 404 still is
            time.sleep(0.1)
        self.assertNotIn("never-sent", log.getvalue())
        self.assertIn("/nothing-here", log.getvalue())

    def test_every_raster_kind_is_taken_and_served_by_its_first_bytes(self):
        kinds = {
            "image/png": self.PNG,
            "image/jpeg": b"\xff\xd8\xff\xe0" + bytes(20),
            "image/gif": b"GIF89a" + bytes(20),
            "image/webp": b"RIFF\x10\x00\x00\x00WEBPVP8 " + bytes(20),
            "image/x-icon": b"\x00\x00\x01\x00\x01\x00" + bytes(20),
        }
        for ctype, data in kinds.items():
            key = f"site:{ctype.split('/')[1]}.example"
            # Whatever the device says it is: the type comes from the bytes.
            self.assertEqual(204, self.raw("PUT", f"/icons/{self.quoted(key)}", data, {"Content-Type": "text/html"})[0], ctype)
            status, body, h = self.raw("GET", f"/icons/{self.quoted(key)}")
            self.assertEqual((200, data, ctype), (status, body, h["Content-Type"]))

    def test_svg_html_and_anything_else_but_a_raster_is_refused(self):
        # Served from the status page's origin, an SVG or HTML "icon" could run script there.
        for bad in (b'<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>',
                    b'<?xml version="1.0"?><svg/>',
                    b"<!doctype html><script>alert(1)</script>",
                    b"\xef\xbb\xbf<svg/>",  # behind a byte-order mark
                    b"  <svg/>",
                    b"\x89PN" + b"G" * 20,  # PNG's start, cut
                    b"GIF8<script>",
                    b"RIFF\x00\x00\x00\x00AVI LIST",
                    b"just text"):
            status, _, _ = self.raw("PUT", "/icons/site%3Abad.example", bad)
            self.assertEqual(400, status, bad)
        self.assertNotIn("site:bad.example", json.loads(self.raw("GET", "/icons")[1]))
        self.assertEqual(404, self.raw("GET", "/icons/site%3Abad.example")[0])

    def test_an_icon_over_64_kb_is_refused(self):
        exact = self.PNG + bytes(srv.ICON_MAX - len(self.PNG))
        self.assertEqual(204, self.raw("PUT", "/icons/site%3Aexact.example", exact)[0])
        self.assertEqual(413, self.raw("PUT", "/icons/site%3Abig.example", exact + b"x")[0])
        self.assertEqual(413, self.raw("PUT", "/icons/site%3Abig.example", b"")[0])  # nothing at all
        # Nor unpacked past it.
        bomb = gzip.compress(exact + bytes(srv.ICON_MAX))
        self.assertEqual(413, self.raw("PUT", "/icons/site%3Abig.example", bomb, {"Content-Encoding": "gzip"})[0])
        self.assertNotIn("site:big.example", json.loads(self.raw("GET", "/icons")[1]))

    def files_in_data(self):
        return {os.path.relpath(os.path.join(d, f), srv.args.data) for d, _, fs in os.walk(srv.args.data) for f in fs}

    def test_a_key_that_isnt_one_is_refused_and_never_a_path(self):
        before = self.files_in_data()
        bad_keys = ["../state.json", "..%2F..%2Fusers%2Falex%2Fstate.json", "%2e%2e%2fstate.json", "site:Reddit.com",
                    "site:reddit.com%2F..%2F..%2Fstate.json", "site:a%00b", "site:a%2Fb", "web:reddit.com", "app:a%20b",
                    "app:com.x%2F..", "desk:", "desk:a%0Ab", "site:" + "a" * 254, "app:" + "a" * 201, "site:%E2%80%AEx.com",
                    "state.json", "", "%"]
        for key in bad_keys:
            self.assertEqual(400, self.raw("PUT", f"/icons/{key}", self.PNG)[0], key)
            self.assertIn(self.raw("GET", f"/icons/{key}")[0], (400, 404), key)
        # A slash that isn't encoded is a path, which no key is.
        self.assertEqual(400, self.raw("PUT", "/icons/site:a.example/../../state.json", self.PNG)[0])
        listed = json.loads(self.raw("GET", "/icons")[1])
        self.assertFalse([k for k in listed if not srv.ICON_KEY.fullmatch(k)], listed)
        self.assertEqual(set(), self.files_in_data() - before, "nothing written anywhere")

    def test_icons_need_the_login(self):
        self.raw("PUT", "/icons/site%3Aprivate.example", self.PNG)
        guesser = {"X-Forwarded-For": "203.0.113.90"}  # its wrong passwords counted apart from the other tests'
        for method, path, data in (("GET", "/icons", None), ("GET", "/icons/site%3Aprivate.example", None),
                                   ("PUT", "/icons/site%3Anew.example", self.PNG)):
            self.assertEqual(401, self.raw(method, path, data, guesser, login=None)[0], (method, path))
            self.assertEqual(401, self.raw(method, path, data, guesser, login="alex:nope")[0], (method, path))
        self.assertNotIn("site:new.example", json.loads(self.raw("GET", "/icons")[1]))

    def test_the_status_pages_images_sign_in_with_the_cookie_alone(self):
        # An <img> can't add X-Xrn-Client: the page's images carry the login cookie and nothing else.
        self.raw("PUT", "/icons/site%3Acookie.example", self.PNG)
        cookie = self.sign_in()
        jar = {"Cookie": f"{srv.SESSION_COOKIE}={cookie}"}
        status, body, _ = self.raw("GET", "/xrnrule/icons/site%3Acookie.example", headers=jar, login=None)
        self.assertEqual((200, self.PNG), (status, body))
        # Nor does a missing or stale one pop the browser's login box for every image.
        for headers in ({}, {"Cookie": f"{srv.SESSION_COOKIE}=garbage"}):
            status, _, h = self.raw("GET", "/icons/site%3Acookie.example", headers=headers, login=None)
            self.assertEqual(401, status)
            self.assertIsNone(h.get("WWW-Authenticate"))
        # The cookie alone reads that one image, nothing more: the list, the state and every write
        # still want the header too.
        self.assertEqual(401, self.raw("GET", "/icons", headers=jar, login=None)[0])
        self.assertEqual(401, self.raw("GET", "/state", headers=jar, login=None)[0])
        self.assertEqual(401, self.raw("PUT", "/icons/site%3Aforged.example", self.PNG, headers=jar, login=None)[0])
        # A page on another site can't send one with the browser's login.
        self.assertEqual(403, self.raw("PUT", "/icons/site%3Aforged.example", self.PNG,
                                       {"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"})[0])

    def test_one_user_cannot_read_or_overwrite_anothers_icons(self):
        self.assertEqual(204, self.raw("PUT", "/icons/site%3Amine.example", self.PNG)[0])
        self.assertNotIn("site:mine.example", json.loads(self.raw("GET", "/icons", login="other:secret2")[1]))
        self.assertEqual(404, self.raw("GET", "/icons/site%3Amine.example", login="other:secret2")[0])
        self.assertEqual(204, self.raw("PUT", "/icons/site%3Amine.example", self.PNG + b"theirs", login="other:secret2")[0])
        self.assertEqual(self.PNG, self.raw("GET", "/icons/site%3Amine.example")[1])

    def test_a_user_keeps_at_most_so_many_icons_those_written_longest_ago_going_first(self):
        user = "many"
        with unittest.mock.patch.object(srv, "ICONS_MAX", 3):
            for i in range(5):
                srv.put_icon(user, f"site:s{i}.example", self.PNG + bytes([i]))
                time.sleep(0.002)
            self.assertEqual(["site:s2.example", "site:s3.example", "site:s4.example"], sorted(srv.icon_index(user)))
            # Written again, an old one is new: the next to go is s3.
            srv.put_icon(user, "site:s2.example", self.PNG + b"again")
            srv.put_icon(user, "site:s5.example", self.PNG)
            self.assertEqual(["site:s2.example", "site:s4.example", "site:s5.example"], sorted(srv.icon_index(user)))
            self.assertIsNone(srv.get_icon(user, "site:s0.example"))
        # Their files go with them: what is on disk is what is listed.
        self.assertEqual(4, len(os.listdir(self.icons_folder(user))))  # three icons and the index
        srv.icons.pop(user)
        self.assertEqual(3, len(srv.icon_index(user)))  # read back from the disk the same

    def test_an_icon_index_that_cant_be_read_starts_over(self):
        # Every logo is a copy of one the devices have: they send again what the list lacks.
        user = "broken"
        srv.put_icon(user, "site:a.example", self.PNG)
        srv.put_icon(user, "site:b.example", self.PNG)
        with open(os.path.join(self.icons_folder(user), "index.json"), "w") as f:
            f.write("{half a file")
        srv.icons.pop(user)
        with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as log:
            self.assertEqual({}, srv.icon_index(user))
        self.assertIn("icons of broken unreadable", log.getvalue())
        srv.put_icon(user, "site:c.example", self.PNG)
        self.assertEqual(["site:c.example"], list(srv.icon_index(user)))
        self.assertEqual(2, len(os.listdir(self.icons_folder(user))))  # the old files went

    def test_the_session_key_is_private_and_shared_by_every_server_on_the_users_file(self):
        path = os.path.join(os.path.dirname(srv.args.users), "session.key")
        self.assertEqual(0o600, os.stat(path).st_mode & 0o777)
        self.assertEqual(srv.session_key, srv.load_session_key())
        self.assertGreaterEqual(len(srv.session_key), 32)

    # ---- device logins (DeviceTokens) ---------------------------------------------------------

    def tokens_path(self):
        return os.path.join(os.path.dirname(srv.args.users), "device-tokens.json")

    def write_tokens(self, entries):
        """device-tokens.json as the server's owner writes it: a new file in the old one's place."""
        with open(self.tokens_path() + ".test", "w", encoding="utf-8") as f:
            f.write(entries if isinstance(entries, str) else json.dumps({"tokens": entries}))
        os.replace(self.tokens_path() + ".test", self.tokens_path())

    @staticmethod
    def a_token(services=("xrnrule",), user="alex"):
        """(a device token, its entry in the file)."""
        token_id = os.urandom(4).hex()
        secret = base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("=")
        entry = {"id": token_id, "user": user, "device": f"device {token_id}", "services": list(services),
                 "sha256": hashlib.sha256(secret.encode()).hexdigest(), "created": 1764115200}
        return f"xrndev_{token_id}_{secret}", entry

    def device_call(self, auth, addr, method="GET", path="/state", body=b"", **headers):
        """(status, body, headers) of a request sent with Authorization: [auth], from [addr]."""
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        conn.request(method, path, body=body, headers={"Authorization": auth, "X-Forwarded-For": addr, **headers})
        res = conn.getresponse()
        answer = res.status, res.read(), res
        conn.close()
        return answer

    def bearer(self, token, addr, **kw):
        return self.device_call(f"Bearer {token}", addr, **kw)[0]

    def with_password(self, addr, **kw):
        return self.device_call("Basic " + base64.b64encode(b"alex:secret").decode(), addr, **kw)[0]

    def test_a_device_token_signs_in_as_its_user(self):
        token, entry = self.a_token()
        self.write_tokens([entry])
        status, body, _ = self.device_call(f"Bearer {token}", "198.18.7.1", path="/xrnrule/state")
        self.assertEqual(200, status)
        self.assertEqual(self.device_call("Basic " + base64.b64encode(b"alex:secret").decode(), "198.18.7.1")[1], body, "alex's, as the password reads them")

    def test_a_token_for_other_services_is_refused_and_guessed_nothing(self):
        token, entry = self.a_token(services=("xrnwal", "xrnnote"))
        self.write_tokens([entry])
        addr = "198.18.7.2"
        for _ in range(srv.FAIL_LIMIT + 2):
            status, body, res = self.device_call(f"Bearer {token}", addr)
            self.assertEqual(403, status)
            self.assertIn(b"xrnrule", body)
            self.assertIsNone(res.getheader("WWW-Authenticate"))
        self.assertEqual(200, self.with_password(addr), "not locked out: it guessed nothing")

    def test_a_wrong_token_is_a_wrong_login(self):
        token, entry = self.a_token()
        self.write_tokens([entry])
        addr = "198.18.7.3"
        wrong = [f"{token[:16]}{self.a_token()[0][16:]}",  # its id, another secret
                 self.a_token()[0],  # an id that isn't there
                 "xrnhub_" + token[7:],  # another kind of token
                 "not-a-token"]
        for i in range(srv.FAIL_LIMIT):
            self.assertEqual(401, self.bearer(wrong[i % len(wrong)], addr))
        self.assertEqual(429, self.bearer(token, addr), "locked out, the right token too, as with a password")
        self.assertEqual(200, self.bearer(token, "198.18.7.4"))

    def test_a_revoked_token_is_refused_from_its_next_request(self):
        token, entry = self.a_token()
        other, kept = self.a_token()
        self.write_tokens([entry, kept])
        self.assertEqual(200, self.bearer(token, "198.18.7.5"))
        self.write_tokens([kept])  # revoked, and nothing restarted
        self.assertEqual(401, self.bearer(token, "198.18.7.5"))
        self.assertEqual(200, self.bearer(other, "198.18.7.5"))

    def test_a_tokens_user_must_be_in_the_users_file(self):
        import bcrypt

        token, entry = self.a_token(user="sam")
        self.write_tokens([entry])
        self.assertEqual(401, self.bearer(token, "198.18.7.6"), "no sam in the users file")
        with open(srv.args.users, encoding="utf-8") as f:
            before = f.read()
        try:
            with open(srv.args.users, "a", encoding="utf-8") as f:
                f.write("sam:" + bcrypt.hashpw(b"other", bcrypt.gensalt(4)).decode() + "\n")
            self.assertEqual(200, self.bearer(token, "198.18.7.6"))
        finally:
            with open(srv.args.users + ".test", "w", encoding="utf-8") as f:
                f.write(before)
            os.replace(srv.args.users + ".test", srv.args.users)
        self.assertEqual(401, self.bearer(token, "198.18.7.6"), "taken out of it, sam's tokens end")

    def test_a_tokens_file_that_cant_be_read_lets_no_token_in_and_the_password_still_works(self):
        token, entry = self.a_token()
        addr = "198.18.7.7"
        broken = ("{not json", '{"tokens": {}}', "[1]")
        with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as log:
            for doc in broken:
                self.write_tokens(doc)
                self.assertEqual(401, self.bearer(token, addr))
                self.assertEqual(401, self.bearer(token, addr))
                self.assertEqual(200, self.with_password(addr))
        self.assertEqual(len(broken), log.getvalue().count("can't be read, so no device token signs in"),
                         "a line for each, not for each request")
        self.write_tokens([entry])
        self.assertEqual(200, self.bearer(token, "198.18.7.8"))
        os.remove(self.tokens_path())
        self.assertEqual(401, self.bearer(token, "198.18.7.8"), "no file, no tokens")

    def test_an_entry_not_as_it_should_be_signs_no_one_in(self):
        token, entry = self.a_token()
        for bad in (dict(entry, sha256="\u00e9" * 64), dict(entry, sha256=entry["sha256"].upper()),
                    dict(entry, user="../alex"), dict(entry, user=["alex"]), dict(entry, services="xrnrule"),
                    {k: v for k, v in entry.items() if k != "services"}):
            self.write_tokens([bad, "not an entry"])
            self.assertEqual(401, self.bearer(token, "198.18.7.9"), bad)

    def test_a_token_cant_become_a_sign_in_cookie(self):
        token, entry = self.a_token()
        self.write_tokens([entry])
        status, _, res = self.device_call(f"Bearer {token}", "198.18.7.10", method="POST", path="/session",
                                          **{"X-Xrn-Client": "web"})
        self.assertEqual(401, status)
        self.assertIsNone(res.getheader("Set-Cookie"))
        self.assertEqual(200, self.bearer(token, "198.18.7.10"), "and it still signs in itself")

    def test_when_a_device_was_last_here_is_written_down_now_and_then(self):
        token, entry = self.a_token()
        gone, revoked = self.a_token()
        self.write_tokens([entry, revoked])
        seen = os.path.join(os.path.dirname(srv.args.users), "device-seen", "xrnrule.json")

        def written():
            st = os.stat(seen)
            with open(seen, encoding="utf-8") as f:
                return (st.st_ino, st.st_mtime_ns), json.load(f), st.st_mode & 0o777

        start = int(time.time())
        self.assertEqual(200, self.bearer(gone, "198.18.7.11"))
        self.assertEqual(200, self.bearer(token, "198.18.7.11"))
        stamp, doc, mode = written()
        self.assertGreaterEqual(doc[entry["id"]], start)
        self.assertIn(revoked["id"], doc)
        self.assertEqual(0o600, mode)
        for _ in range(3):
            self.assertEqual(200, self.bearer(token, "198.18.7.11"))
        self.assertEqual(stamp, written()[0], "not written again within DEVICE_SEEN_S")
        self.write_tokens([entry])
        with unittest.mock.patch.object(srv, "DEVICE_SEEN_S", 0):
            self.assertEqual(200, self.bearer(token, "198.18.7.11"))
        stamp2, doc, _ = written()
        self.assertNotEqual(stamp, stamp2)
        self.assertEqual([entry["id"]], list(doc), "a revoked token's time goes with it")
        with open(seen, encoding="utf-8") as f:
            self.assertNotIn(token[16:], f.read())

    def test_a_token_never_reaches_the_log(self):
        token, entry = self.a_token()
        self.write_tokens([entry])
        wrong = self.a_token()[0]
        with unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as log:
            self.assertEqual(200, self.bearer(token, "198.18.7.12"))
            self.assertEqual(401, self.bearer(wrong, "198.18.7.12"))
            time.sleep(0.05)
        self.assertIn("/state", log.getvalue())
        for secret in (token[16:], wrong[16:]):
            self.assertNotIn(secret, log.getvalue())


if __name__ == "__main__":
    unittest.main()
