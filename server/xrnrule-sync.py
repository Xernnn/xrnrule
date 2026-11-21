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
                              ?wait=S with If-None-Match long-polls up to S seconds (the laptop);
                              one whose caller hangs up first ends there, unanswered
    PUT  /state               needs If-Match: "<version>" (or "*"); 200 with the new ETag, or
                              412 if someone wrote first: re-read, merge, try again
                              503 if the stored file can't be read: never an empty document, which
                              a device would take as everything deleted (contract I45)
    GET  /devices             when each device last checked in (X-Device), for the status page
    POST /events              {"events": [{"t": "blocked"|"habit"|"unlock"|"bought"|"break", "at": ms, ...}]} from a
                              device's local log (X-Device); duplicates are dropped, and so is
                              one stamped more than a day ahead of this server. 120 days kept,
                              and at most EVENTS_MAX of them
    GET  /events?since=MS     those events, for the weekly review in the habits web app
    GET  /limits              each block's daily limit use per device and day, as the devices'
                              writes of /state said it (its `allowance`), for the history pages:
                              {"limits": {block: {device id: {day start ms: ms}}}}; 120 days kept
    GET  /icons               the blocked apps' and sites' logos kept here, for the pages:
                              {"site:reddit.com" | "app:<package>" | "desk:<laptop app id>": the first
                              12 hex of its SHA-256}
    GET  /icons/<key>         one of them, its key URL-encoded: the image as it was sent (PNG, JPEG,
                              GIF, WebP or ICO), cached a day, or 404. The status page's <img> asks
                              with its login cookie alone, since an image can't carry X-Xrn-Client
    PUT  /icons/<key>         a device's copy: a raster image of at most ICON_MAX bytes, told by its
                              first bytes (never SVG, which could carry script); 204. At most
                              ICONS_MAX per user, the one written longest ago dropped first
    POST /session             with Basic auth: sets the web pages' login cookie (below)
    DELETE /session           signs the browser out, and ends its cookie on every server (below)
    GET  /health              200 "ok", no login (for monitoring)
    GET  /update/<file>       releases from scripts/release.mjs, in <data>/update: the browser
                              extension's updates.xml and signed .crx with no login (a browser's
                              own updater fetches them), the phone app's app.json and .apk with it
    GET  /, /status.js, ...   a read-only status page (static files; the data needs a login)
    HEAD                      any GET above, answered without its body

The devices send Basic auth with every request. The web pages (this status page, the xrnloops
web app on the same host) sign in once and then carry an HttpOnly cookie, `xrn_session`, so the
password never sits where a page's script could read it: the host is shared with other apps,
and a script bug in any of them could read the pages' storage. The cookie counts only with an
`X-Xrn-Client` header, which another site's page can't send. It's signed with `session.key`,
made next to the users file on first start, which xrnloops-sync shares, so either sign-in works
for both. A new password ends every session; so does deleting session.key. Signing out ends that
one cookie: its SHA-256 goes on `signed-out.json` beside session.key until it would have run out,
a list that xrnhub, xrnloops-sync and xrninsta (which take the same cookie) also write to and refuse.

    python3 xrnrule-sync.py --data ~/xrnrule-sync/data --users ~/.config/radicale/users \
        --web ~/xrnrule-sync/web
"""
import argparse
import base64
import fcntl
import gzip
import hashlib
import hmac
import ipaddress
import json
import math
import mimetypes
import os
import re
import select
import shutil
import socket
import sys
import threading
import time
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote

MAX_BODY = 8 * 1024 * 1024
FAIL_DELAY_S = 2  # after a wrong password, as Radicale does
AUTH_CACHE_S = 600  # a bcrypt check costs ~0.3s on a phone; don't pay it per request
MAX_WAIT_S = 55
GONE_CHECK_S = 1  # how often a long-poll that hears nothing looks whether its caller is still there
BACKUP_DAYS = 14  # daily copies of each user's state, next to it in backups/
FAIL_LIMIT = 10  # wrong logins from one address within FAIL_WINDOW_S, then 429 until they age out
FAIL_WINDOW_S = 15 * 60
SEEN_WRITE_S = 60  # devices.json is rewritten at most this often per user
EVENTS_KEEP_DAYS = 120
EVENTS_AHEAD_MS = 86400 * 1000  # an event stamped further ahead of this server's clock is no time at all
# The most events kept for one user, oldest dropped first: some 400 a day for the 120 days, about
# 5 MB of file and 30 MB here, where a device could otherwise send 8 MB a batch without end.
EVENTS_MAX = 50000
EVENT_TYPES = {"blocked", "habit", "unlock", "bought", "break"}
LIMITS_WRITE_S = 60  # limits.json is rewritten at most this often per user
LIMITS_MAX_KEYS = 200  # blocks, devices per block: more than any real state has
# Logos of what blocks name, sent by the devices for the pages (GET/PUT /icons). An icon of 96-128 px
# is a few KB; the most a user keeps, 400 of the largest, is some 25 MB.
ICON_MAX = 64 * 1024
ICONS_MAX = 400
ICON_KEY = re.compile(r"site:[a-z0-9.-]{1,253}|app:[A-Za-z0-9._]{1,200}|desk:[A-Za-z0-9._ +-]{1,200}")
# Served as an image only: no script runs in it even opened on its own, and no other site's page can load it.
ICON_HEADERS = {
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; sandbox",
    "Cross-Origin-Resource-Policy": "same-origin",
}
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
fail_settled = threading.Condition(fail_lock)  # a login being checked has been settled
failures = {}  # client address -> (monotonic time, user name tried) of its recent wrong logins
checking = {}  # client address -> how many of its logins are being checked right now
seen_lock = threading.Lock()
seen = {}  # user -> {device: epoch ms of its last request}, mirrored to devices.json
seen_written = {}  # user -> time.monotonic() of the last devices.json write
events_lock = threading.Lock()
events = {}  # user -> list of event dicts, oldest first, mirrored to events.jsonl
event_keys = {}  # user -> the event_key of each of events[user], so a batch needn't go over them all
limits_lock = threading.Lock()
limits = {}  # user -> {block: {device id: {day start ms (str): ms}}}, mirrored to limits.json
limits_written = {}  # user -> time.monotonic() of the last limits.json write
limits_dirty = set()  # users whose limits.json is behind what's here
icons_lock = threading.Lock()
icons = {}  # user -> {key: {"h": first 12 hex of the image's sha256, "at": ms it was written}}, mirrored to icons/index.json
session_key = b""  # signs the web pages' login cookies; see load_session_key()
signed_out_lock = threading.Lock()
signed_out_seen = None  # the signed-out.json (path, inode, mtime, size) signed_out_list was read from
signed_out_list = {}  # sha256 of a signed-out cookie -> when it would have run out; see signed_out()


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


def basic_login(header):
    """(user, password) from `Authorization: Basic ...`, or None."""
    if not header or not header.startswith("Basic "):
        return None
    try:
        user, _, password = base64.b64decode(header[6:]).decode("utf-8").partition(":")
    except Exception:
        return None
    return user, password


def cached_login(header):
    """The user name for a password already found right (the auth cache), else None: no check."""
    login = basic_login(header)
    if not login:
        return None
    hit = auth_cache.get(hashlib.sha256(f"{login[0]}:{login[1]}".encode()).hexdigest())
    return hit[0] if hit and hit[1] > time.time() and hit[2] == users_mtime() else None


def check_login(header):
    """The user name for a valid `Authorization: Basic ...`, else None."""
    login = basic_login(header)
    if not login:
        return None
    user, password = login
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


def signed_out_path():
    """The cookies signed out on any server that shares the users file and session.key (xrnhub,
    xrnloops-sync and xrninsta too): {sha256 of the cookie: when it would have run out}, beside them."""
    return os.path.join(os.path.dirname(args.users), "signed-out.json")


def read_signed_out(path):
    """signed-out.json's list: {} with no file, None (and a line in the log) for one that can't be read."""
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        if not isinstance(doc, dict):
            raise ValueError("not a JSON object")
    except FileNotFoundError:
        return {}
    except (OSError, ValueError, RecursionError) as e:  # ValueError: also bad UTF-8
        sys.stderr.write(f"{path} can't be read, so the sign-outs read before it hold: {e}\n")
        return None
    return {k: v for k, v in doc.items() if isinstance(k, str) and isinstance(v, (int, float))}


def signed_out():
    """The list as the file has it now, read again only when it has changed, so a sign-out on
    another server counts here from the next request. A file that can't be read keeps the last list."""
    global signed_out_seen, signed_out_list
    path = signed_out_path()
    try:
        st = os.stat(path)
        now = (path, st.st_ino, st.st_mtime_ns, st.st_size)
    except FileNotFoundError:
        now = (path, None)
    except OSError as e:
        now = (path, "unreadable", e.errno)
    with signed_out_lock:
        if now != signed_out_seen:
            signed_out_seen = now
            listed = {} if now[1] is None else read_signed_out(path)
            if listed is not None:
                signed_out_list = listed
        return signed_out_list


def sign_out(token):
    """Puts a cookie on the list, dropping what has run out. Under an flock on signed-out.json.lock,
    with the file read again inside it, so two servers signing out at once both keep theirs; written
    whole to a new file that then takes the old one's place."""
    global signed_out_seen, signed_out_list
    path = signed_out_path()
    lock = os.open(f"{path}.lock", os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX)
        listed = read_signed_out(path)
        if listed is None:
            listed = dict(signed_out_list)
        listed[hashlib.sha256(token.encode()).hexdigest()] = int(token.split(".")[1])
        now = time.time()
        kept = {k: v for k, v in listed.items() if v >= now}  # run out: refused anyway
        tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(kept, f, separators=(",", ":"))
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
        st = os.stat(path)
        with signed_out_lock:
            signed_out_seen, signed_out_list = (path, st.st_ino, st.st_mtime_ns, st.st_size), kept
    finally:
        os.close(lock)  # and with it the flock


def make_session(user):
    """A login cookie's value for [user]: who, until when, and a signature over both."""
    who = base64.urlsafe_b64encode(user.encode()).decode().rstrip("=")
    tag = password_tag(user)
    expires = int(time.time()) + SESSION_S
    # A cookie is made from the name, the second it runs out and the password alone, so one made in
    # the second the same one was signed out (here or on another server) would be that cookie. It
    # runs out a second later instead, which keeps the format every server reads.
    listed = signed_out()
    while True:
        body = f"{who}.{expires}"
        token = f"{body}.{session_mac(body, tag)}"
        if hashlib.sha256(token.encode()).hexdigest() not in listed:
            return token
        expires += 1


def session_token(cookie_header):
    """The login cookie's value in a Cookie header, or ""."""
    token = ""
    for part in (cookie_header or "").split(";"):
        name, _, value = part.strip().partition("=")
        if name == SESSION_COOKIE:
            token = value
    return token


def check_session(cookie_header):
    """The user a request's login cookie names, if it's ours, unexpired, not signed out and the
    password unchanged."""
    token = session_token(cookie_header)
    try:
        who, expires, mac = token.split(".")
        user = base64.urlsafe_b64decode(who + "=" * (-len(who) % 4)).decode("utf-8")
        if int(expires) < time.time():
            return None
    except ValueError:  # also a bad base64 or UTF-8
        return None
    if hashlib.sha256(token.encode()).hexdigest() in signed_out():
        return None
    tag = password_tag(user)
    # As bytes: compare_digest raises on a str that isn't ASCII, and a cookie can hold anything.
    if tag is None or not hmac.compare_digest(session_mac(f"{who}.{expires}", tag).encode(), mac.encode()):
        return None
    return user


def end_session(cookie_header):
    """Signs out the session a request's cookie carries, on every server that takes it, until it
    would have run out anyway. The user it was, or None when it was no session of ours."""
    user = check_session(cookie_header)
    if not user:
        return None
    try:
        sign_out(session_token(cookie_header))
    except OSError as e:
        sys.stderr.write(f"sign-out not kept: {e!r}\n")
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


def recent_failures(addr, now):
    """This address's wrong logins within FAIL_WINDOW_S; older ones are forgotten. Under fail_lock."""
    recent = [f for f in failures.get(addr, ()) if now - f[0] < FAIL_WINDOW_S]
    if recent:
        failures[addr] = recent
    else:
        failures.pop(addr, None)
    return recent


def locked_out(addr):
    """Whether this address has used up its wrong logins for now."""
    with fail_lock:
        return len(recent_failures(addr, time.monotonic())) >= FAIL_LIMIT


def reserve_guess(addr):
    """Waits for a place to check a password from this address in; False once the address has
    used up its wrong logins. A login being checked holds a place until settle_guess: counted only
    afterwards, guesses sent at once all passed the check while the first were still in bcrypt.
    It isn't a wrong login, though: one that finds no place waits for it to be settled rather than
    being turned away, so two devices signing in at once never lock each other out."""
    with fail_settled:
        now = time.monotonic()
        if len(failures) > 10000:  # a flood from many addresses: forget the stale ones
            for a in [a for a, fs in failures.items() if now - fs[-1][0] >= FAIL_WINDOW_S]:
                del failures[a]
        while True:
            wrong = len(recent_failures(addr, time.monotonic()))
            if wrong >= FAIL_LIMIT:
                return False
            if wrong + checking.get(addr, 0) < FAIL_LIMIT:
                checking[addr] = checking.get(addr, 0) + 1
                return True
            fail_settled.wait(1)


def settle_guess(addr, name, right):
    """A login reserve_guess let through has been checked. A wrong one counts against the address,
    under the name it tried. A right one clears that name's mistakes from this address, and no one
    else's: a device logging in mustn't give a guesser behind the same address its guesses back.
    None, a password already found right (cached_login) or a check that broke, settles nothing."""
    with fail_settled:
        left = checking.get(addr, 1) - 1
        if left > 0:
            checking[addr] = left
        else:
            checking.pop(addr, None)
        if right is False:
            failures.setdefault(addr, []).append((time.monotonic(), name[:64]))
        elif right:
            kept = [f for f in failures.get(addr, ()) if f[1] != name[:64]]
            if kept:
                failures[addr] = kept
            else:
                failures.pop(addr, None)
        fail_settled.notify_all()


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


def clean_event(raw, device, latest):
    """A device's event as stored, or None if it isn't one we know or is stamped after [latest]."""
    if not isinstance(raw, dict) or not isinstance(raw.get("t"), str) or raw["t"] not in EVENT_TYPES \
            or not isinstance(raw.get("at"), int):
        return None
    if raw["at"] > latest:  # it would never age out, and would count as today's every day
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
    """The user's events (cached), oldest first, dropping any past EVENTS_KEEP_DAYS, any stamped
    more than EVENTS_AHEAD_MS ahead and any past the newest EVENTS_MAX (from before those were
    refused). Call under events_lock."""
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
        kept.sort(key=lambda e: e.get("at", 0))  # the file is in the order events came in
        events[user] = kept
        event_keys.pop(user, None)
    now = time.time()
    cutoff = int((now - EVENTS_KEEP_DAYS * 86400) * 1000)
    latest = int(now * 1000) + EVENTS_AHEAD_MS
    have = events[user]
    if have and (have[0].get("at", 0) < cutoff or have[-1].get("at", 0) > latest or len(have) > EVENTS_MAX):
        events[user] = [e for e in have if cutoff <= e.get("at", 0) <= latest][-EVENTS_MAX:]
        event_keys.pop(user, None)
        rewrite_events(user)
    if user not in event_keys:
        event_keys[user] = {event_key(e) for e in events[user]}
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
        known = event_keys[user]
        latest = int(time.time() * 1000) + EVENTS_AHEAD_MS
        new = []
        for raw in raw_events:
            e = clean_event(raw, device, latest)
            if e and event_key(e) not in known:
                known.add(event_key(e))
                new.append(e)
        if new:
            stored.extend(new)
            stored.sort(key=lambda e: e.get("at", 0))
            if len(stored) > EVENTS_MAX:
                # The oldest go, as they would with age; a tenth at once, so that a full log isn't
                # written out again for every batch.
                del stored[:len(stored) - EVENTS_MAX * 9 // 10]
                event_keys[user] = {event_key(e) for e in stored}
                rewrite_events(user)
            else:
                path = events_path(user)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "a", encoding="utf-8") as f:
                    for e in new:
                        f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")
        return len(new)


def limits_path(user):
    return os.path.join(args.data, "users", user, "limits.json")


def load_limits(user):
    """The user's limit record (cached). Call under limits_lock."""
    if user not in limits:
        try:
            with open(limits_path(user), encoding="utf-8") as f:
                kept = json.load(f)
        except (OSError, ValueError):
            kept = {}
        # Only the shape note_limits writes: block -> device -> day -> ms.
        limits[user] = {
            block: {device: {day: ms for day, ms in days.items() if isinstance(ms, int)}
                    for device, days in by_device.items() if isinstance(days, dict)}
            for block, by_device in (kept.items() if isinstance(kept, dict) else []) if isinstance(by_device, dict)
        }
    return limits[user]


def note_limits(user, data):
    """Keeps each block's limit use per device and day out of a state document's `allowance`
    (SharedState.allowance: block -> device id -> {dayStartWall, ms, ...}). A device reports only
    its current day, so the day before is gone from the state once its next day starts: kept here,
    the history pages can show a block's use against its limit day by day. The largest use seen
    for a day stays (a day's use only grows). Anything malformed is skipped: this never stands in
    the way of a write."""
    allowance = data.get("allowance") if isinstance(data, dict) else None
    if not isinstance(allowance, dict):
        return
    cutoff = int((time.time() - EVENTS_KEEP_DAYS * 86400) * 1000)
    latest = int(time.time() * 1000) + EVENTS_AHEAD_MS
    with limits_lock:
        record = load_limits(user)
        changed = False
        for block, by_device in list(allowance.items())[:LIMITS_MAX_KEYS]:
            if not isinstance(block, str) or not isinstance(by_device, dict):
                continue
            for device, use in list(by_device.items())[:LIMITS_MAX_KEYS]:
                if not isinstance(device, str) or not isinstance(use, dict):
                    continue
                day, ms = use.get("dayStartWall"), use.get("ms")
                if not isinstance(day, int) or not isinstance(ms, int) or isinstance(ms, bool) or ms < 0 \
                        or not cutoff <= day <= latest:
                    continue
                days = record.setdefault(block[:200], {}).setdefault(device[:64], {})
                if days.get(str(day), -1) < ms:
                    days[str(day)] = ms
                    changed = True
        # Days past what events are kept for go, and blocks or devices left with none.
        for block in list(record):
            for device in list(record[block]):
                days = record[block][device]
                for day in [d for d in days if not d.lstrip("-").isdigit() or int(d) < cutoff]:
                    del days[day]
                    changed = True
                if not days:
                    del record[block][device]
            if not record[block]:
                del record[block]
        if changed:
            limits_dirty.add(user)
        if user in limits_dirty and time.monotonic() - limits_written.get(user, -LIMITS_WRITE_S) >= LIMITS_WRITE_S:
            write_limits(user)


def write_limits(user):
    """Call under limits_lock."""
    limits_written[user] = time.monotonic()
    limits_dirty.discard(user)
    path = limits_path(user)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(limits[user], f, separators=(",", ":"))
    os.replace(path + ".tmp", path)


def limits_of(user):
    """The limit record, with the stored state's own figures in it: the first read after an
    update has them before any device writes again."""
    try:
        note_limits(user, read_state(user).get("data"))
    except Unreadable:
        pass
    with limits_lock:
        return json.loads(json.dumps(load_limits(user)))


def icon_type(data):
    """The media type of a raster image, told by its first bytes; None for anything else. SVG,
    HTML and text above all: served from this origin, a browser could run script in them."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[:4] == b"\x00\x00\x01\x00":
        return "image/x-icon"
    return None


def icon_hash(data):
    return hashlib.sha256(data).hexdigest()[:12]


def icons_dir(user):
    return os.path.join(args.data, "users", user, "icons")


def icon_file(user, key):
    """Named by the key's hash: a key is the devices' text, never a path."""
    return os.path.join(icons_dir(user), hashlib.sha256(key.encode()).hexdigest())


def load_icons(user):
    """The user's icon index (cached). One that can't be read starts over: the devices send again
    what it lacks, and the files it named go with the next write. Call under icons_lock."""
    if user not in icons:
        path = os.path.join(icons_dir(user), "index.json")
        try:
            with open(path, encoding="utf-8") as f:
                doc = json.load(f)
            if not isinstance(doc, dict):
                raise ValueError("not a JSON object")
        except FileNotFoundError:
            doc = {}
        except (OSError, ValueError, RecursionError) as e:
            print(f"icons of {user} unreadable, starting over: {e}", file=sys.stderr, flush=True)
            doc = {}
        icons[user] = {k: v for k, v in doc.items() if isinstance(k, str) and ICON_KEY.fullmatch(k) and isinstance(v, dict)
                       and isinstance(v.get("h"), str) and isinstance(v.get("at"), int)}
    return icons[user]


def write_whole(path, data):
    """[data] in [path], all or nothing: a new file that then takes the old one's place."""
    tmp = f"{path}.{threading.get_ident()}.tmp"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def put_icon(user, key, data):
    """Keeps [data] as [key]'s icon. Past ICONS_MAX, the ones written longest ago go."""
    with icons_lock:
        index = load_icons(user)
        folder = icons_dir(user)
        os.makedirs(folder, exist_ok=True)
        write_whole(icon_file(user, key), data)
        index[key] = {"h": icon_hash(data), "at": int(time.time() * 1000)}
        if len(index) > ICONS_MAX:
            # Never the one just written, whatever the clock says.
            for old in sorted((k for k in index if k != key), key=lambda k: index[k]["at"])[:len(index) - ICONS_MAX]:
                del index[old]
        write_whole(os.path.join(folder, "index.json"), json.dumps(index, separators=(",", ":")).encode())
        # The files of icons dropped, of an index that couldn't be read and of a write cut off: nothing
        # names them now, and every write here is under this lock.
        kept = {os.path.basename(icon_file(user, k)) for k in index} | {"index.json"}
        for name in os.listdir(folder):
            if name not in kept:
                try:
                    os.remove(os.path.join(folder, name))
                except OSError:
                    pass


def get_icon(user, key):
    """[key]'s image, or None."""
    with icons_lock:
        if key not in load_icons(user):
            return None
        try:
            with open(icon_file(user, key), "rb") as f:
                return f.read(ICON_MAX + 1)
        except OSError:
            return None


def icon_index(user):
    with icons_lock:
        return {k: v["h"] for k, v in load_icons(user).items()}


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


# The request line and X-Forwarded-For come from the caller. Written to the log as they are, a
# control character in them could rewrite the terminal the log is read in, or (a \r) start a line
# of its own for xrnhub's log reader. Escaped as http.server's own log_message, replaced here, does.
LOG_ESCAPES = str.maketrans({**{c: f"\\x{c:02x}" for c in (*range(0x20), *range(0x7F, 0xA0))}, "\\": "\\\\"})


class Handler(BaseHTTPRequestHandler):
    server_version = "xrnrule-sync/1"
    sys_version = ""  # the Server header named the Python version too
    timeout = 120  # seconds of silence before a connection is dropped, so a stalled client can't hold a thread

    def log_request(self, code="-", size="-"):
        # A logo the server hasn't got is routine: the pages ask for every target's and draw a glyph
        # instead. xrnhub's log page counts each 404 as a warning, and a page asks for dozens.
        if code == 404 and getattr(self, "icon_miss", False):
            return
        super().log_request(code, size)

    def log_message(self, fmt, *a):
        headers = getattr(self, "headers", None)  # none yet on a request line that didn't parse
        who = headers.get("X-Forwarded-For", self.client_address[0]) if headers else "-"
        sys.stderr.write(f"{who} {fmt % a}".translate(LOG_ESCAPES) + "\n")

    def send_header(self, keyword, value):
        if keyword.lower() == "connection":
            self.said_connection = True
        super().send_header(keyword, value)

    def end_headers(self):
        # Every connection is closed after its answer (http.server's HTTP/1.0), and a server that
        # keeps none open must say so in each answer (RFC 9112, 9.6). Java's HttpClient, the laptop
        # daemon's, goes by this header alone: without it, it kept the connection to send its next
        # request down, and when this side closed first that request was lost. Java sends a GET
        # again on a new connection, but not a PUT, so an icon pass ended part way.
        if self.close_connection and not getattr(self, "said_connection", False):
            super().send_header("Connection", "close")
        self.said_connection = False
        super().end_headers()

    def handle(self):
        # A caller hanging up, mid-request or before the answer is out, is the network, not a fault
        # here: the laptop's tailnet path drops, a phone loses its signal, a page is closed. One
        # line says so. socketserver printed some 30 lines of traceback for each, which xrnhub's
        # log page shows as an error.
        try:
            super().handle()
        except ConnectionError as e:
            self.close_connection = True
            self.log_message("connection dropped: %s", type(e).__name__)

    def caller_gone(self):
        """Whether the caller has closed its end, as Funnel does once the caller gives up on a
        long-poll: what it would have been told is then for no one."""
        try:
            readable, _, _ = select.select([self.connection], [], [], 0)
            return bool(readable) and not self.connection.recv(1, socket.MSG_PEEK)
        except (OSError, ValueError):
            return True

    def parse_request(self):
        self.icon_miss = False
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
            if k not in (headers or {}):  # an icon's answer has a policy of its own
                self.send_header(k, v)
        if code != 304:
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD" and code != 304:
            self.wfile.write(body)

    def body(self, limit=MAX_BODY):
        """The request's body, gunzipped if it came so, of at most [limit] bytes; None once an error
        has been answered."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self.send(400, "bad Content-Length")
            return None
        if length <= 0 or length > limit:
            self.send(413, "body missing or too large")
            return None
        raw = self.rfile.read(length)
        if len(raw) < length:
            # The connection closed part way: what came is no document, so it isn't read as one.
            self.close_connection = True
            self.send(400, "the upload stopped short")
            return None
        if (self.headers.get("Content-Encoding") or "").lower() != "gzip":
            return raw
        # Unpacked only up to the limit: a few megabytes of gzip can unpack to gigabytes.
        unpack = zlib.decompressobj(wbits=16 + zlib.MAX_WBITS)
        try:
            out = unpack.decompress(raw, limit + 1)
        except zlib.error:
            out = None
        if out is None or (not unpack.eof and len(out) <= limit):
            self.send(400, "bad gzip")
            return None
        if len(out) > limit:
            self.send(413, "body too large")
            return None
        return out

    def login(self, image=False):
        """The user a request is from, or None once a 401 or 429 has been answered. [image]: an
        <img> on the status page, which can't add X-Xrn-Client, signs in with its cookie alone. It
        reads one logo, and no other site's page gets it: the cookie is SameSite=Strict and the
        answer same-origin only (ICON_HEADERS)."""
        # A web page: its login cookie, with the header another site's page couldn't add.
        cookie_alone = image and session_token(self.headers.get("Cookie"))
        if not self.headers.get("Authorization") and (self.headers.get("X-Xrn-Client") or cookie_alone):
            user = check_session(self.headers.get("Cookie"))
            if not user:
                self.send(401, "login required", headers=ICON_HEADERS if image else None)
            return user
        addr = client_address(self)
        password = self.headers.get("Authorization")
        if not (reserve_guess(addr) if password else not locked_out(addr)):
            if password:
                time.sleep(FAIL_DELAY_S)
            self.send(429, "too many wrong passwords; try again later", headers={"Retry-After": str(FAIL_WINDOW_S)})
            return None
        right = None
        try:
            # A password already found right is a session, not a login, and settles nothing: a
            # device sending it with every sync cleared the count for its whole address, so a
            # guesser behind the same one never ran out of guesses.
            user = cached_login(password)
            if not user:
                user = check_login(password)
                right = user is not None
        finally:
            if password:
                settle_guess(addr, (basic_login(password) or ("",))[0], right)
        if not user:
            if password:  # a request with none guessed nothing, and needn't hold a thread for it
                time.sleep(FAIL_DELAY_S)
            # The status page logs in with its own form: a challenge would pop the browser's dialog,
            # and would for each of its images too.
            quiet = self.headers.get("X-Xrn-Client") or (image and not password)
            challenge = {} if quiet else {"WWW-Authenticate": 'Basic realm="xrnrule"'}
            self.send(401, "login required", headers={**challenge, **(ICON_HEADERS if image else {})})
            return None
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
        if path == "/limits":
            user = self.login()
            if user:
                self.send(200, json.dumps({"limits": limits_of(user)}, separators=(",", ":")),
                          "application/json; charset=utf-8")
            return
        if path == "/icons":
            user = self.login()
            if user:
                self.send(200, json.dumps(icon_index(user), separators=(",", ":")), "application/json; charset=utf-8")
            return
        if path.startswith("/icons/"):
            return self.icon(path[len("/icons/"):])
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
                    if changed.wait(min(left, GONE_CHECK_S)):
                        doc = read_state(user)
                    elif self.caller_gone():
                        self.close_connection = True
                        return self.log_message("caller left a long-poll after %.0fs",
                                                wait - (deadline - time.monotonic()))
        except Unreadable:
            return self.send(503, "the stored state can't be read")
        etag = {"ETag": f'"{doc["version"]}"'}
        if known and known == str(doc["version"]):
            return self.send(304, headers=etag)
        body = json.dumps(doc.get("data") or {}, ensure_ascii=False, separators=(",", ":"))
        self.send(200, body, "application/json; charset=utf-8",
                  {**etag, "X-Updated": doc.get("updated", ""), "X-Updated-By": doc.get("by", "")})

    # A GET's answer without its body, as curl -I and monitors ask: http.server answered 501
    # "Unsupported method", which xrnhub's log page shows as an error.
    do_HEAD = do_GET

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
        # Emptying the browser's cookie alone left any copy of it signing in for its full 30 days.
        end_session(self.headers.get("Cookie"))
        self.send(200, '{"ok":true}', "application/json", {"Set-Cookie": session_cookie("", 0)})

    def do_PUT(self):
        path, _ = self.route()
        user = self.login()
        if not user:
            return
        if path.startswith("/icons/"):
            return self.icon_sent(path[len("/icons/"):], user)
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
        try:
            note_limits(user, data)
        except Exception as e:  # a history for the pages: never a failed write for a device
            print(f"limits of {user} not kept: {e}", file=sys.stderr, flush=True)
        self.send(200, '{"ok":true}', "application/json", {"ETag": f'"{version}"'})

    def icon(self, raw_key):
        """GET /icons/<key>: the image as a device sent it, or 404."""
        user = self.login(image=True)
        if not user:
            return
        key = unquote(raw_key)
        if not ICON_KEY.fullmatch(key):
            return self.send(400, "bad icon key", headers=ICON_HEADERS)
        data = get_icon(user, key)
        ctype = icon_type(data) if data and len(data) <= ICON_MAX else None
        if not ctype:
            self.icon_miss = True
            return self.send(404, "no such icon", headers=ICON_HEADERS)
        etag = icon_hash(data)
        code = 304 if etag_version(self.headers.get("If-None-Match")) == etag else 200
        self.send_response(code)
        self.send_header("Cache-Control", "private, max-age=86400")
        for k, v in {**SECURITY_HEADERS, **ICON_HEADERS}.items():
            self.send_header(k, v)
        self.send_header("ETag", f'"{etag}"')
        if code == 200:
            self.send_header("Content-Type", ctype)  # from the bytes themselves, never from the device
            self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if code == 200 and self.command != "HEAD":
            self.wfile.write(data)

    def icon_sent(self, raw_key, user):
        """PUT /icons/<key>: a device's copy of a logo, a raster image only."""
        key = unquote(raw_key)
        if not ICON_KEY.fullmatch(key):
            return self.send(400, "bad icon key")
        data = self.body(ICON_MAX)
        if data is None:
            return
        if not icon_type(data):
            return self.send(400, "not a PNG, JPEG, GIF, WebP or ICO image")
        put_icon(user, key, data)
        self.send(204)

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
            if self.command != "HEAD":
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
