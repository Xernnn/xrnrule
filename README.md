# xrnrule

Rules for your phone and your laptop. Distracting apps and websites stay behind a block until
you've done a habit, used up today's minutes, or are inside the hours you set; then they open
for a while and lock again on their own. No accounts, no cloud: everything stays on the phone,
unless you turn on sync with a server of your own, which also brings the same blocks to a
Linux laptop.

xrnrule was called xrnhabit until 24 Oct 2026, when it took in
[xrnfocus](https://github.com/Xernnn/xrnfocus), a browser extension that put time limits on
sites. One set of rules now covers apps and sites on both devices.

## How it fits together

```
Phone                                      Laptop (KDE Plasma 6)
 xrnrule app: rules, apps, habits           xrnrule-desktop: rules, desktop apps
   ↑ 127.0.0.1:47321                          ↑ 127.0.0.1:47321
 Quetta + the xrnrule extension: sites      Helium + the xrnrule extension: sites
              \                             /
               your home server (server/): blocks, unlocks, minutes used, bought minutes
```

A block is about a thing, not a device: a "YouTube" block can cover the phone app,
youtube.com in both browsers and a laptop app. Each block is one of three kinds:

1. **Earn it.** Blocked until you do the habit: a checklist, a timer (optionally counted in a
   *different* app, such as a reading app), or both. A checklist item can be linked to a habit
   in [xrnloops](#companion-app-xrnloops), so only a real tick there counts.
2. **Limit.** N minutes a day, and/or "at most N minutes in a row, then a break". Phone and
   laptop share one limit, or each gets its own (a choice per block).
3. **Set time.** Blocked on a weekly schedule (as many time windows as you like), or locked
   down right now for N minutes.

A thing can be in several blocks; whichever is strictest right now wins. A block can leave
parts of a site alone (music.youtube.com under youtube.com).

**Ways through a block**, the same on the phone and the laptop:

- **Do the habit** (Earn-it blocks): it opens on both devices.
- **Buy a few minutes** (Limit and Set-time blocks): start, wait 45 s, type "I choose to keep
  scrolling", get a few minutes. A few times a day per block, counted across both devices.
  Never past a lockdown, a hardcore block or a forced break.
- **The daily break**: 15 minutes off every soft block, once a day.

## What else it does

- **Hardcore mode** (sideload build). A password gate on Android Settings (and phone-maker
  security apps) plus Device Admin anti-uninstall while a hardcore block is active.
- **Hard to talk yourself out of.** Deleting a block or making one easier (including the
  buy-minutes settings) takes a 60-second wait, or the hardcore password. Changing the phone's
  clock by hand doesn't reset limits or move schedules.
- **Status at a glance.** The notification shows what's open and for how long, warns 30 s
  before an unlock ends, and says so if blocking stops working. On limited sites the extension
  can show a corner clock of what runs out first, and warns at 10, 5, 3 and 1 minutes left.
- **Backup.** Export your blocks to a file and import them on a new install.
- **Insights.** Per-app screen time, time per site, habits done, times blocked and your streak.
- Built to survive reboots, process death and OEM battery killers.

## The browser extension

Websites are blocked by the extension in `extension/`, not by reading the screen: nothing in
xrnrule uses Accessibility, which banking apps refuse to run next to. The extension asks
xrnrule on the same device (`127.0.0.1:47321`) what to do and decides nothing itself. It sends
a blocked site to the block page before it loads, moves open tabs off a site the moment it's
blocked, and while xrnrule doesn't answer it keeps blocking what it last heard was blocked.

- **Laptop (Helium or another Chromium browser):** extensions page → Developer mode → Load
  unpacked → `extension/`.
- **Phone (Quetta):** `scripts/pack-extension.sh` makes `dist/xrnrule-extension.zip`; copy it to
  the phone, then in Quetta: extensions page → Developer mode → "(from .zip/.crx/.user.js)".

The phone app warns when a browser is in front and the extension says nothing, so a browser
without it (Chrome on Android has no extensions) doesn't quietly let sites through.

## Security

- **Only the sync server is on the internet.** The phone app and the laptop daemon answer on
  `127.0.0.1:47321` and nowhere else. Every change needs an `X-Xrnrule` header, which other
  websites can't add (a CORS preflight that is never approved), and the request must name that
  address as its Host, which shuts out DNS-rebinding pages too. The laptop's pages can't be put
  in another site's frame and run no scripts but their own.
- **Other apps on the phone** can reach `127.0.0.1:47321` as well. They can read which sites are
  blocked and report a site in front, nothing more, and nothing they send can crash the app.
  If xrnrule were down and another app took the port, the extension would still send blocked
  sites only to its own block page or one on `127.0.0.1:47321`, never to a page that app names.
- **The sync server** (behind Tailscale Funnel, HTTPS) wants the login for every byte of data.
  Ten wrong passwords from one address in 15 minutes lock that address out; the address is the
  one Funnel saw, not a header the caller could forge. Uploads are capped at 8 MB, unpacked or
  not. The status page keeps the login in the browser's storage, so it runs only its own scripts.
- **Secrets** stay where they're used: the sync password in the phone app's private storage
  (never backed up) and in a file only you can read on the laptop. The hardcore password exists
  only as a salted hash on the phone and is never synced or exported.

## Build

Requirements: **JDK 17** (Gradle 8.11.1 doesn't run on newer JDKs) and the Android SDK
(platform 35). Point Gradle at the SDK with `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.

```sh
./gradlew :domain:test                 # engine unit tests (pure JVM)
./gradlew :app:assembleSideloadDebug   # full build, with hardcore mode
./gradlew :app:assemblePlayDebug       # Play-safe build: no Device Admin
./gradlew :app:installSideloadDebug    # build and install on a connected device
node --test extension/test             # the extension's helpers
scripts/pack-extension.sh              # the extension as a .zip, for Quetta
```

The two flavors exist because the distribution channel is undecided: anti-uninstall uses
Device Admin, which carries Play Store policy risk, so the `play` build leaves it out.

Minimum Android version: 8.0 (API 26).

## Permissions

Usage access (to see the foreground app), draw over other apps (the block screen),
notifications, and exemption from battery optimization. On Xiaomi/MIUI also enable
autostart. The Settings screen shows what's missing and links to each one. Sites need the
browser extension (above); the app only listens for it on `127.0.0.1`.

## Companion app: xrnloops

[Loop Habit Tracker](https://github.com/iSoron/uhabits) doesn't let other apps read its data,
so habit-linked checklist items need **xrnloops**, a small GPLv3 fork of Loop kept as a
separate project. It shares today's ticks through a signature-protected content provider and
pings xrnrule whenever something is ticked. Both apps must be signed with the same key.
xrnrule itself contains no Loop code. xrnloops can also sync its habits with your server,
which serves a web page for ticking and reviewing them from a laptop.

## Project layout

| Module | |
|---|---|
| `:domain` | The rules engine: a pure Kotlin state machine with no Android dependencies, fully unit-tested |
| `:app` | Compose UI, the foreground service that enforces blocks, the extension's door, persistence, permissions |
| `:hardcore` | Device Admin receiver (sideload builds only) |
| `:desktop` | The laptop daemon and its KWin script; see [desktop/README.md](desktop/README.md) |
| `extension/` | The browser extension, for both devices |
| `server/` | `xrnrule-sync.py`, the tiny sync server (Python), and its status page |

The engine's rules (states, transitions, invariants I1–I34, multi-device sync in §2.4) are
specified in `docs/domain-contract.md`, which is in Vietnamese.
