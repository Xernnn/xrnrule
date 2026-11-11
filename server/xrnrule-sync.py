#!/usr/bin/env python3
"""xrnrule sync server: the state the phone and the laptop share, behind HTTP Basic auth.

One JSON document per user, the `SharedState` of `:domain` (docs/domain-contract.md §2.4).
The server keeps no logic of its own: each device merges three ways on its side and writes back
with If-Match, so two devices can't overwrite each other. Made to run on a small home server
next to Radicale, published under a path (e.g. /xrnrule) by a reverse proxy such as Tailscale
Funnel, which may or may not strip that prefix. Standard library only, plus `bcrypt` to read an
htpasswd file (Radicale's works as-is, so the calendar password is the sync password).

    GET  /state               200, the document; ETag: "<version>"
                              If-None-Match: "<version>" -> 304 when unchanged (a cheap check)
                              ?wait=S with If-None-Match long-polls up to S seconds (the laptop)
    PUT  /state               needs If-Match: "<version>" (or "*"); 200 with the new ETag, or
                              412 if someone wrote first: re-read, merge, try again
                              503 if the stored file can't be read: never an empty document, which
                              a device would take as everything deleted (contract I45)
    GET  /devices             when each device last checked in (X-Device), for the status page
    POST /events              {"events": [{"t": "blocked"|"habit"|"unlock"|"bought"|"break", "at": ms, ...}]} from a
                              device's local log (X-Device); duplicates are dropped. 120 days kept
    GET  /events?since=MS     those events, for the weekly review in the habits web app
    POST /session             with Basic auth: sets the web pages' login cookie (below)
    DELETE /session           signs the browser out
    GET  /health              200 "ok", no login (for monitoring)
    GET  /update/<file>       releases from scripts/release.mjs, in <data>/update: the browser
                              extension's updates.xml and signed .crx with no login (a browser's
                              own updater fetches them), the phone app's app.json and .apk with it
    GET  /, /status.js, ...   a read-only status page (static files; the data needs a login)

The devices send Basic auth with every request. The web pages (this status page, the xrnloops
web app on the same host) sign in once and then carry an HttpOnly cookie, `xrn_session`, so the
password never sits where a page's script could read it: the host is shared with other apps,
and a script bug in any of them could read the pages' storage. The cookie counts only with an
`X-Xrn-Client` header, which another site's page can't send. It's signed with `session.key`,
made next to the users file on first start, which xrnloops-sync shares, so either sign-in works
for both. A new password ends every session; so does deleting session.key.

    python3 xrnrule-sync.py --data ~/xrnrule-sync/data --users ~/.config/radicale/users \
        --web ~/xrnrule-sync/web
"""
import argparse
import base64
import gzip
import hashlib
import hmac
import ipaddress
import json
import math
import mimetypes
import os
import re
import shutil
import sys
import threading
import time
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

MAX_BODY = 8 * 1024 * 1024
FAIL_DELAY_S = 2  # after a wrong password, as Radicale does
AUTH_CACHE_S = 600  # a bcrypt check costs ~0.3s on a phone; don't pay it per request
MAX_WAIT_S = 55
BACKUP_DAYS = 14  # daily copies of each user's state, next to it in backups/
FAIL_LIMIT = 10  # wrong logins from one address within FAIL_WINDOW_S, then 429 until they age out
FAIL_WINDOW_S = 15 * 60
SEEN_WRITE_S = 60  # devices.json is rewritten at most this often per user
EVENTS_KEEP_DAYS = 120
EVENT_TYPES = {"blocked", "habit", "unlock", "bought", "break"}
PREFIX = "/xrnrule"
# What /update/ may serve: a plain file name, no path, no leading dot. The extension's two kinds
# are public, since a browser's updater sends no login; the app is the private repo's code.
UPDATE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\.(?:xml|crx|json|apk)")
UPDATE_TYPES = {
    ".xml": "application/xml; charset=utf-8",
    ".crx": "application/x-chrome-extension",
    ".json": "application/json; charset=utf-8",
    ".apk": "application/vnd.android.package-archive",
}
PUBLIC_UPDATES = (".xml", ".crx")
SESSION_COOKIE = "xrn_session"
SESSION_S = 30 * 86400

args = None
lock = threading.Lock()
changed = threading.Condition(lock)
auth_cache = {}  # sha256(user:password) -> (user, expires, users-file mtime)
fail_lock = threading.Lock()
failures = {}  # client address -> monotonic times of its recent wrong logins
seen_lock = threading.Lock()
seen = {}  # user -> {device: epoch ms of its last request}, mirrored to devices.json
seen_written = {}  # user -> time.monotonic() of the last devices.json write
events_lock = threading.Lock()
events = {}  # user -> list of event dicts, oldest first, mirrored to events.jsonl
session_key = b""  # signs the web pages' login cookies; see load_session_key()


SECURITY_HEADERS = {  # on every answer; main() adds the status page's Content-Security-Policy
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
}


def content_policy(web):
    """The status page's CSP: its own files only, plus index.html's inline scripts by hash."""
    hashes = []
    try:
        with open(os.path.join(web, "index.html"), encoding="utf-8") as f:
            for script in re.findall(r"<script>(.*?)</script>", f.read(), re.S):
                digest = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
                hashes.append(f" 'sha256-{digest}'")
    except OSError:
        pass
    return ("default-src 'self'; script-src 'self'" + "".join(hashes) + "; style-src 'self'; img-src 'self' data:; "
            "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")


def users_mtime():
    try:
        return os.stat(args.users).st_mtime
    except OSError:
        return 0


def check_login(header):
    """The user name for a valid `Authorization: Basic ...`, else None."""
    if not header or not header.startswith("Basic "):
        return None
    try:
        user, _, password = base64.b64decode(header[6:]).decode("utf-8").partition(":")
    except Exception:
        return None
    key = hashlib.sha256(f"{user}:{password}".encode()).hexdigest()
    mtime = users_mtime()
    hit = auth_cache.get(key)
    if hit and hit[1] > time.time() and hit[2] == mtime:
        return hit[0]
    import bcrypt  # only needed once someone logs in, so the tests can run without it

    try:
        checked, spare = False, None
        with open(args.users, encoding="utf-8") as f:
            for line in f:
                name, _, hashed = line.strip().partition(":")
                spare = hashed or spare
                if name == user and hashed:
                    checked = True
                    if bcrypt.checkpw(password.encode(), hashed.encode()):
                        auth_cache[key] = (user, time.time() + AUTH_CACHE_S, mtime)
                        return user
        if not checked and spare:
            # A name that isn't there takes as long as a wrong password for one that is, or
            # the time an answer takes says which names exist. The result is never used.
            bcrypt.checkpw(password.encode(), spare.encode())
    except (OSError, ValueError):
        pass
    return None


def load_session_key():
    """The key that signs login cookies: random, made on first start next to the users file, so
    every server reading that file (xrnloops-sync too) takes the others' cookies."""
    path = os.path.join(os.path.dirname(args.users), "session.key")
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "wb") as f:
            f.write(os.urandom(32))
    for _ in range(50):  # the other server may be writing it this very moment
        with open(path, "rb") as f:
            key = f.read()
        if len(key) >= 32:
            return key
        time.sleep(0.1)
    raise SystemExit(f"{path} is too short to sign with")


def password_tag(user):
    """A fingerprint of [user]'s password hash, so a new password ends the old sessions."""
    try:
        with open(args.users, encoding="utf-8") as f:
            for line in f:
                name, _, hashed = line.strip().partition(":")
                if name == user and hashed:
                    return hashlib.sha256(hashed.encode()).hexdigest()
    except OSError:
        pass
    return None


def session_mac(body, tag):
    return hmac.new(session_key, f"{body}|{tag}".encode(), hashlib.sha256).hexdigest()


def make_session(user):
    """A login cookie's value for [user]: who, until when, and a signature over both."""
    who = base64.urlsafe_b64encode(user.encode()).decode().rstrip("=")
    body = f"{who}.{int(time.time()) + SESSION_S}"
    return f"{body}.{session_mac(body, password_tag(user))}"


def check_session(cookie_header):
    """The user a request's login cookie names, if it's ours, unexpired and the password unchanged."""
    token = ""
    for part in (cookie_header or "").split(";"):
        name, _, value = part.strip().partition("=")
        if name == SESSION_COOKIE:
            token = value
    try:
        who, expires, mac = token.split(".")
        user = base64.urlsafe_b64decode(who + "=" * (-len(who) % 4)).decode("utf-8")
        if int(expires) < time.time():
            return None
    except ValueError:  # also a bad base64 or UTF-8
        return None
    tag = password_tag(user)
    # As bytes: compare_digest raises on a str that isn't ASCII, and a cookie can hold anything.
    if tag is None or not hmac.compare_digest(session_mac(f"{who}.{expires}", tag).encode(), mac.encode()):
        return None
    return user


def session_cookie(value, max_age):
    # Path=/: the xrnloops web app (/xrnloops) signs in for this page too.
    return f"{SESSION_COOKIE}={value}; Path=/; Max-Age={max_age}; HttpOnly; Secure; SameSite=Strict"


def client_address(handler):
    """The caller's address. Behind the local proxy (Funnel) that's the last X-Forwarded-For hop,
    the one the proxy added: anything before it came from the caller, who could make up a new
    address for every password guess. Only trusted from a loopback peer, for the same reason.
    An IPv6 address counts as its /64: a line comes with at least that many, so counted one by one
    its owner had a new address for every guess and never reached the limit."""
    peer = handler.client_address[0]
    forwarded = handler.headers.get("X-Forwarded-For")
    addr = forwarded.split(",")[-1].strip() if forwarded and peer in ("127.0.0.1", "::1") else peer
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return addr
    if ip.version == 4:
        return addr
    if ip.ipv4_mapped:
        return str(ip.ipv4_mapped)
    return f"{ipaddress.IPv6Address(int(ip) >> 64 << 64)}/64"


def from_another_site(headers):
    """Whether a request came from another site's page. A browser that was once given the password
    in its own sign-in box sends it again with a form any site posts here, and a text/plain form
    body can be JSON. The browser says where the page was in Sec-Fetch-Site; one too old for that
    still sends Origin with a POST, held against this host's name (or the proxy's X-Forwarded-Host).
    The devices send neither. The extensions' pages are another origin, and may write."""
    origin = (headers.get("Origin") or "").strip().lower()
    if origin.startswith(("chrome-extension://", "moz-extension://")):
        return False
    site = (headers.get("Sec-Fetch-Site") or "").strip().lower()
    if site:
        return site in ("cross-site", "same-site")
    scheme, _, host = origin.partition("://")
    if scheme not in ("http", "https"):
        return False  # no Origin, or "null"
    ours = [headers.get("Host") or "", *(headers.get("X-Forwarded-Host") or "").split(",")]
    return host_key(host) not in {host_key(h) for h in ours if h.strip()}


def host_key(host):
    """A host as Origin and Host both write it: lower case, without the default port."""
    return host.strip().lower().removesuffix(":443").removesuffix(":80")


def locked_out(addr):
    """Whether this address has used up its wrong logins for now."""
    now = time.monotonic()
    with fail_lock:
        recent = [t for t in failures.get(addr, ()) if now - t < FAIL_WINDOW_S]
        if recent:
            failures[addr] = recent
        else:
            failures.pop(addr, None)
        return len(recent) >= FAIL_LIMIT


def reserve_guess(addr):
    """Counts a password from this address as wrong before it is checked; False if the address
    has used up its wrong logins. Counted afterwards, guesses sent at once all passed the check
    while the first were still in bcrypt. A right one clears the count (see Handler.login)."""
    now = time.monotonic()
    with fail_lock:
        if len(failures) > 10000:  # a flood from many addresses: forget the stale ones
            for a in [a for a, ts in failures.items() if now - ts[-1] >= FAIL_WINDOW_S]:
                del failures[a]
        recent = [t for t in failures.get(addr, ()) if now - t < FAIL_WINDOW_S]
        if len(recent) >= FAIL_LIMIT:
            failures[addr] = recent
            return False
        failures[addr] = recent + [now]
        return True


def devices_path(user):
    return os.path.join(args.data, "users", user, "devices.json")


def note_device(user, device):
    """Remember when [device] (the phone, the laptop) last checked in."""
    device = (device or "")[:64]
    if not device:
        return
    with seen_lock:
        devices = seen.get(user)
        if devices is None:
            try:
                with open(devices_path(user), encoding="utf-8") as f:
                    devices = json.load(f)
            except (OSError, ValueError):
                devices = {}
            seen[user] = devices
        devices[device] = int(time.time() * 1000)
        if time.monotonic() - seen_written.get(user, -SEEN_WRITE_S) >= SEEN_WRITE_S:
            seen_written[user] = time.monotonic()
            path = devices_path(user)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path + ".tmp", "w", encoding="utf-8") as f:
                json.dump(devices, f)
            os.replace(path + ".tmp", path)


def devices_of(user):
    with seen_lock:
        if user in seen:
            return dict(seen[user])
    try:
        with open(devices_path(user), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def events_path(user):
    return os.path.join(args.data, "users", user, "events.jsonl")


def event_key(e):
    return (e.get("d"), e.get("t"), e.get("at"), e.get("block"), e.get("target"), e.get("ms"))


def clean_event(raw, device):
    """A device's event as stored, or None if it isn't one we know."""
    if not isinstance(raw, dict) or raw.get("t") not in EVENT_TYPES or not isinstance(raw.get("at"), int):
        return None
    e = {"d": device, "t": raw["t"], "at": raw["at"]}
    for k in ("block", "target", "name"):
        if isinstance(raw.get(k), str) and raw[k]:
            e[k] = raw[k][:200]
    for k in ("ms", "start"):  # start: an unlock's session (I28), the same on every device that logs it
        if isinstance(raw.get(k), int) and raw[k] >= 0:
            e[k] = raw[k]
    return e


def load_events(user):
    """The user's events (cached), dropping any past EVENTS_KEEP_DAYS. Call under events_lock."""
    if user not in events:
        kept = []
        try:
            with open(events_path(user), encoding="utf-8") as f:
                for line in f:
                    try:
                        kept.append(json.loads(line))
                    except ValueError:
                        pass
        except OSError:
            pass
        events[user] = kept
    cutoff = int((time.time() - EVENTS_KEEP_DAYS * 86400) * 1000)
    if events[user] and events[user][0].get("at", 0) < cutoff:
        events[user] = [e for e in events[user] if e.get("at", 0) >= cutoff]
        rewrite_events(user)
    return events[user]


def rewrite_events(user):
    path = events_path(user)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        for e in events[user]:
            f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")
    os.replace(path + ".tmp", path)


def add_events(user, device, raw_events):
    """Stores the new ones; returns how many were new."""
    with events_lock:
        stored = load_events(user)
        known = {event_key(e) for e in stored}
        new = []
        for raw in raw_events:
            e = clean_event(raw, device)
            if e and event_key(e) not in known:
                known.add(event_key(e))
                new.append(e)
        if new:
            path = events_path(user)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "a", encoding="utf-8") as f:
                for e in new:
                    f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")
            stored.extend(new)
            stored.sort(key=lambda e: e.get("at", 0))
        return len(new)


def state_path(user):
    return os.path.join(args.data, "users", user, "state.json")


class Unreadable(Exception):
    """state.json is there and can't be read. Never answer that with an empty document: a device
    takes what's missing as deleted and drops its blocks (docs/domain-contract.md, I45)."""


def read_state(user):
    try:
        with open(state_path(user), encoding="utf-8") as f:
            doc = json.load(f)
    except FileNotFoundError:
        return {"version": 0, "data": {}}  # a new user; the devices fill it
    except (OSError, ValueError) as e:
        print(f"state of {user} unreadable: {e}", file=sys.stderr, flush=True)
        raise Unreadable from e
    if not isinstance(doc, dict) or not isinstance(doc.get("version"), int):
        print(f"state of {user} has no version", file=sys.stderr, flush=True)
        raise Unreadable
    return doc


def keep_backup(path):
    """The first write of a day keeps what's there, for BACKUP_DAYS days."""
    folder = os.path.join(os.path.dirname(path), "backups")
    name = f"state-{datetime.now(timezone.utc):%Y-%m-%d}.json"
    if os.path.exists(os.path.join(folder, name)) or not os.path.exists(path):
        return
    os.makedirs(folder, exist_ok=True)
    shutil.copyfile(path, os.path.join(folder, name))
    for old in sorted(os.listdir(folder))[:-BACKUP_DAYS]:
        os.remove(os.path.join(folder, old))


def write_state(user, doc):
    path = state_path(user)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    keep_backup(path)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def etag_version(header):
    return (header or "").strip().removeprefix("W/").strip('"')


class Handler(BaseHTTPRequestHandler):
    server_version = "xrnrule-sync/1"
    timeout = 120  # seconds of silence before a connection is dropped, so a stalled client can't hold a thread

    def log_message(self, fmt, *a):
        who = self.headers.get("X-Forwarded-For", self.client_address[0]) if self.headers else "-"
        sys.stderr.write(f"{who} {fmt % a}\n")

    def parse_request(self):
        if not super().parse_request():
            return False
        # Before the login, which a page elsewhere can have the browser pass for it.
        if self.command not in ("GET", "HEAD", "OPTIONS") and from_another_site(self.headers):
            self.send(403, "refused: sent from another site's page")
            return False
        return True

    def route(self):
        path, _, query = self.path.partition("?")
        if path == PREFIX or path.startswith(PREFIX + "/"):
            path = path[len(PREFIX):] or "/"
        return path, {k: v[-1] for k, v in parse_qs(query).items()}

    def send(self, code, body=b"", ctype="text/plain; charset=utf-8", headers=None):
        if isinstance(body, str):
            body = body.encode()
        if len(body) > 1024 and "gzip" in (self.headers.get("Accept-Encoding") or ""):
            body = gzip.compress(body, 6)
            headers = {**(headers or {}), "Content-Encoding": "gzip"}
        self.send_response(code)
        self.send_header("Cache-Control", "no-store")
        for k, v in SECURITY_HEADERS.items():
            self.send_header(k, v)
        if code != 304:
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD" and code != 304:
            self.wfile.write(body)

    def body(self):
        """The request's body, gunzipped if it came so; None once an error has been answered."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self.send(400, "bad Content-Length")
            return None
        if length <= 0 or length > MAX_BODY:
            self.send(413, "body missing or too large")
            return None
        raw = self.rfile.read(length)
        if (self.headers.get("Content-Encoding") or "").lower() != "gzip":
            return raw
        # Unpacked only up to MAX_BODY: a few megabytes of gzip can unpack to gigabytes.
        unpack = zlib.decompressobj(wbits=16 + zlib.MAX_WBITS)
        try:
            out = unpack.decompress(raw, MAX_BODY + 1)
        except zlib.error:
            out = None
        if out is None or (not unpack.eof and len(out) <= MAX_BODY):
            self.send(400, "bad gzip")
            return None
        if len(out) > MAX_BODY:
            self.send(413, "body too large")
            return None
        return out

    def login(self):
        # A web page: its login cookie, with the header another site's page couldn't add.
        if not self.headers.get("Authorization") and self.headers.get("X-Xrn-Client"):
            user = check_session(self.headers.get("Cookie"))
            if not user:
                self.send(401, "login required")
            return user
        addr = client_address(self)
        password = self.headers.get("Authorization")
        if not (reserve_guess(addr) if password else not locked_out(addr)):
            if password:
                time.sleep(FAIL_DELAY_S)
            self.send(429, "too many wrong passwords; try again later", headers={"Retry-After": str(FAIL_WINDOW_S)})
            return None
        user = check_login(password)
        if not user:
            if password:  # a request with none guessed nothing, and needn't hold a thread for it
                time.sleep(FAIL_DELAY_S)
            # The status page logs in with its own form: a challenge would pop the browser's dialog.
            challenge = {} if self.headers.get("X-Xrn-Client") else {"WWW-Authenticate": 'Basic realm="xrnrule"'}
            self.send(401, "login required", headers=challenge)
            return None
        with fail_lock:
            failures.pop(addr, None)
        note_device(user, self.headers.get("X-Device"))
        return user

    def do_GET(self):
        path, query = self.route()
        if path == "/health":
            return self.send(200, "ok")
        if path.startswith("/update/"):
            return self.update_file(path[len("/update/"):])
        if path == "/devices":
            user = self.login()
            if user:
                self.send(200, json.dumps(devices_of(user)), "application/json; charset=utf-8")
            return
        if path == "/events":
            user = self.login()
            if not user:
                return
            try:
                since = int(query.get("since", 0))
            except ValueError:
                return self.send(400, "bad since")
            with events_lock:
                found = [e for e in load_events(user) if e.get("at", 0) >= since]
            return self.send(200, json.dumps({"events": found}, ensure_ascii=False, separators=(",", ":")),
                             "application/json; charset=utf-8")
        if path != "/state":
            return self.static(path)
        user = self.login()
        if not user:
            return
        known = etag_version(self.headers.get("If-None-Match"))
        try:
            wait = float(query.get("wait", 0))
            if not math.isfinite(wait):  # NaN is past every comparison: the wait below would never end
                raise ValueError
            wait = min(max(wait, 0), MAX_WAIT_S)
        except ValueError:
            return self.send(400, "bad wait")
        try:
            with lock:
                doc = read_state(user)
                deadline = time.monotonic() + wait
                while known and known == str(doc["version"]):
                    left = deadline - time.monotonic()
                    if left <= 0:
                        break
                    changed.wait(left)
                    doc = read_state(user)
        except Unreadable:
            return self.send(503, "the stored state can't be read")
        etag = {"ETag": f'"{doc["version"]}"'}
        if known and known == str(doc["version"]):
            return self.send(304, headers=etag)
        body = json.dumps(doc.get("data") or {}, ensure_ascii=False, separators=(",", ":"))
        self.send(200, body, "application/json; charset=utf-8",
                  {**etag, "X-Updated": doc.get("updated", ""), "X-Updated-By": doc.get("by", "")})

    def do_POST(self):
        path, _ = self.route()
        if path == "/session":
            # Only a password signs in: a cookie can't be stretched into a new one.
            if not self.headers.get("Authorization"):
                return self.send(401, "login required")
            user = self.login()
            if user:
                self.send(200, '{"ok":true}', "application/json",
                          {"Set-Cookie": session_cookie(make_session(user), SESSION_S)})
            return
        user = self.login()
        if not user:
            return
        if path != "/events":
            return self.send(404, "not found")
        raw = self.body()
        if raw is None:
            return
        try:
            body = json.loads(raw)
            raw = body["events"]
            if not isinstance(raw, list):
                raise ValueError
        except (ValueError, KeyError, TypeError, RecursionError):  # RecursionError: nested too deep
            return self.send(400, 'body must be {"events": [...]}')
        device = (self.headers.get("X-Device") or "")[:64]
        if not device:
            return self.send(400, "X-Device required")
        added = add_events(user, device, raw)
        self.send(200, json.dumps({"ok": True, "added": added}), "application/json")

    def do_DELETE(self):
        path, _ = self.route()
        if path != "/session":
            return self.send(404, "not found")
        self.send(200, '{"ok":true}', "application/json", {"Set-Cookie": session_cookie("", 0)})

    def do_PUT(self):
        path, _ = self.route()
        user = self.login()
        if not user:
            return
        if path != "/state":
            return self.send(404, "not found")
        raw = self.body()
        if raw is None:
            return
        try:
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise ValueError
        except (ValueError, RecursionError):  # RecursionError: nested too deep
            return self.send(400, "body must be a JSON object")
        want = (self.headers.get("If-Match") or "").strip()
        if not want:
            return self.send(428, "If-Match required")
        with lock:
            try:
                cur = read_state(user)
            except Unreadable:
                return self.send(503, "the stored state can't be read")
            if want != "*" and etag_version(want) != str(cur["version"]):
                return self.send(412, "changed since you read it", headers={"ETag": f'"{cur["version"]}"'})
            version = cur["version"] + 1
            by = (self.headers.get("X-Device") or "")[:64]
            write_state(user, {"version": version, "updated": datetime.now(timezone.utc).isoformat(),
                               "by": by, "data": data})
            changed.notify_all()
        self.send(200, '{"ok":true}', "application/json", {"ETag": f'"{version}"'})

    def update_file(self, name):
        """A release, streamed as is: an .apk is already compressed and runs to megabytes."""
        if not UPDATE_NAME.fullmatch(name):
            return self.send(404, "not found")
        ext = os.path.splitext(name)[1]
        # The login comes first, so a caller without one can't even tell which releases exist.
        if ext not in PUBLIC_UPDATES and not self.login():
            return
        try:
            f = open(os.path.join(args.data, "update", name), "rb")
        except OSError:
            return self.send(404, "not found")
        with f:
            # The release script swaps files in by rename, so the open file is one whole version.
            size = os.fstat(f.fileno()).st_size
            self.send_response(200)
            self.send_header("Cache-Control", "no-store")
            for k, v in SECURITY_HEADERS.items():
                self.send_header(k, v)
            self.send_header("Content-Type", UPDATE_TYPES[ext])
            self.send_header("Content-Length", str(size))
            self.end_headers()
            shutil.copyfileobj(f, self.wfile)

    def static(self, path):
        if path in ("", "/"):
            path = "/index.html"
        name = os.path.normpath(path.lstrip("/"))
        file = os.path.join(args.web, name)
        if name.startswith("..") or not os.path.isfile(file):
            return self.send(404, "not found")
        with open(file, "rb") as f:
            data = f.read()
        ctype = mimetypes.guess_type(file)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "image/svg+xml"):
            ctype += "; charset=utf-8"
        self.send(200, data, ctype)


def main():
    global args, session_key
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=5235)
    p.add_argument("--data", required=True, help="where the documents live")
    p.add_argument("--users", required=True, help="htpasswd file with bcrypt hashes")
    p.add_argument("--web", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "web"),
                   help="the status page's folder")
    args = p.parse_args()
    args.data = os.path.expanduser(args.data)
    args.users = os.path.expanduser(args.users)
    args.web = os.path.abspath(os.path.expanduser(args.web))
    mimetypes.add_type("text/javascript", ".js")
    SECURITY_HEADERS["Content-Security-Policy"] = content_policy(args.web)
    session_key = load_session_key()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    sys.stderr.write(f"xrnrule-sync on http://{args.host}:{args.port}, data in {args.data}\n")
    srv.serve_forever()


if __name__ == "__main__":
    main()
