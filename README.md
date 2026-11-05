# xrnrule

Rules for your phone and your laptop. Distracting apps and websites stay behind a block until
you've done a habit, used up today's minutes, or are inside the hours you set; then they open
for a while and lock again on their own. No accounts, no cloud: everything stays on the phone,
unless you turn on sync with a server of your own, which also brings the same blocks to a
Linux laptop. (The one other thing asked of the internet: each website you name is asked for
its own icon, once a month at most, never through an icon service that would see the list.)

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
- **Locks.** A block can be locked for 24 hours up to a year (its edit page): until then nothing
  that makes it easier to get past can change, on the phone or the laptop, whatever you wait or
  type. Making it stricter still works, and so do the ways through it you set up (habits, bought
  minutes, the break). Five emergency unlocks, shared by both devices and never given back, can
  end a lock early.
- **Status at a glance.** The notification shows what's open and for how long, warns 30 s
  before an unlock ends, and says so if blocking stops working. On limited sites the extension
  can show a corner clock of what runs out first, and warns at 10, 5, 3 and 1 minutes left.
- **App lock.** Opening the app, or coming back to it after 30 seconds away, asks for your
  fingerprint (or the phone's screen-lock PIN). The block screen isn't behind it: that's how you
  get through a block. Settings → App lock turns it off. Android 9 or later.
- **Tells you about updates** (sideload build). New versions come from your server, at the sync
  address and with its login. A new one downloads by itself, then a notification and a banner on
  the Blocks tab say it's ready and take you to Settings → Updates, where it installs when you tap
  Install. See [Updates](#updates).
- **Backup.** Export your blocks to a file and import them on a new install.
- **Insights.** Per-app screen time, time per site, habits done, times blocked and your streak.
- Built to survive reboots, process death and OEM battery killers.

## The browser extension

Websites are blocked by the extension in `extension/`, not by reading the screen: nothing in
xrnrule uses Accessibility, which banking apps refuse to run next to. The extension asks
xrnrule on the same device (`127.0.0.1:47321`) what to do and decides nothing itself. It sends
a blocked site to the block page before it loads, moves open tabs off a site the moment it's
blocked, and while xrnrule doesn't answer it keeps blocking what it last heard was blocked. After
a minute without xrnrule it blocks every site that has a rule, so stopping the app or the laptop
daemon isn't a way past a limit; opening it again brings everything back.

A rule can cover one part of a site: `youtube.com/shorts` blocks Shorts and leaves the rest of
YouTube alone, and a page moving there without loading anew is caught too. A page playing sound
behind another one, or with the screen off, counts against its limit. **Block other browsers**
(on by default) keeps browsers without the extension closed while a block has websites. Turning
that off takes the usual wait.

On the phone, a blocked tab turns into a small page the app serves itself, and the app's block
screen covers the browser only while that tab is the one in front. **Leave this site** (or
Back) closes the block screen at once and steps the tab back to where you were, or closes it if
it has nothing to go back to, so the rest of the browser stays yours.

- **Laptop (Helium or another Chromium browser):** extensions page → Developer mode → Load
  unpacked → `extension/`. It reloads itself whenever that folder's `manifest.json` names a new
  version (a pull, or a release).
- **Phone (Quetta):** open `https://<your server>/xrnrule/update/xrnrule.crx` in Quetta →
  **Download anyway** → **OK** (or hand the file to its extensions page: Developer mode → "(from
  .zip/.crx/.user.js)"). From then on it fetches each new version from your server by itself.
  A copy installed from a `.zip` (`scripts/pack-extension.sh`) works too, but never updates, and
  each one gets a new extension ID, so remove it before installing the `.crx`.

The extension's settings page shows its version and where it updates from, with **Check now**.
The phone app warns when the browser the extension lives in goes quiet, without ever blocking
that browser, since that's where you'd fix it. On the laptop, a browser with the extension turned
off is closed after two minutes in front. Browsers can't be seen inside other apps: a page opened
in an app's own in-app browser isn't blocked.

## Updates

`node scripts/release.mjs` publishes both halves to your server; `… extension` or `… app` does
one. It bumps the version past what the server already has, since a browser or phone only takes
a newer one, and needs `~/.config/xrnrule/release.json`:

```json
{ "server": "https://<your server>/xrnrule", "ssh": ["<ssh host>", "<another way in>"] }
```

- **The extension** is signed with `~/.config/xrnrule/extension.pem`, made on the first release.
  **Back it up.** The key is the extension's identity: an update signed with another one is
  refused, and the phone would have to install afresh under a new ID.
- **The app** is the sideload release build, signed with the key it shares with xrnloops
  (`../xrn-signing.properties`; the release refuses to go without it, since the phone only takes
  an update signed like the app it has). On the phone, Settings → Updates → **Allow installing
  updates** once. A new build downloads by itself every few hours; a notification and a banner on
  the Blocks tab then open Settings → Updates, and nothing installs until you tap **Install**
  there. The first build with the updater has to be installed
  by hand: `./gradlew :app:installSideloadRelease`, or open
  `https://<your server>/xrnrule/update/xrnrule.apk` on the phone and sign in with the sync login.

## Security

- **Only the sync server is on the internet.** The phone app and the laptop daemon answer on
  `127.0.0.1:47321` and nowhere else. Every change needs an `X-Xrnrule` header, which other
  websites can't add (a CORS preflight that is never approved), and the request must name that
  address as its Host, which shuts out DNS-rebinding pages too. The laptop's pages can't be put
  in another site's frame and run no scripts but their own.
- **Website icons** are fetched over HTTPS from each named site itself (its home page's
  `<link rel="icon">`, then `/favicon.ico`), with no login and no cookies, and only for real
  domain names, never an IP address or `localhost`. Only PNG, ICO, JPEG, GIF and WebP are
  kept, checked by their first bytes (no SVG: on the laptop page's origin it could carry script).
- **Other apps on the phone** can reach `127.0.0.1:47321` as well. They can read which sites are
  blocked and report a site in front, nothing more, and nothing they send can crash the app.
  If xrnrule were down and another app took the port, the extension would still send blocked
  sites only to its own block page or one on `127.0.0.1:47321`, never to a page that app names.
- **The sync server** (behind Tailscale Funnel, HTTPS) wants the login for every byte of data.
  Ten wrong passwords from one address in 15 minutes lock that address out; the address is the
  one Funnel saw, not a header the caller could forge. Uploads are capped at 8 MB, unpacked or
  not.
- **The web pages** (this status page and the xrnloops habits page, on a host shared with
  other apps) trade the password for a 30-day cookie that no script can read, and keep nothing
  else: a script bug anywhere on the host can't walk off with the password. The cookie only
  counts with a header other sites can't send, and a new password ends every session. The
  pages run only their own scripts and can't be put in another site's frame.
- **Updates** can only come from you. The server takes no uploads (releases arrive over ssh),
  the extension's files are public because a browser's updater sends no login (it's the
  extension's own code, nothing of yours), and the app's need the sync login. A browser takes
  only a `.crx` signed with the extension's key, and Android only an APK signed with the app's;
  the app also checks the hash, package and build number before installing.
- **The app lock** keeps the app's screens to whoever can unlock the phone. It's a door, not a
  vault: blocks are enforced by the service, which doesn't wait for it.
- **Secrets** stay where they're used: the sync password in the phone app's private storage
  (never backed up) and in a file only you can read on the laptop. The hardcore password exists
  only as a salted hash on the phone and is never synced or exported.
- **Install the release build on the phone** (`installSideloadRelease`). The debug build is
  debuggable, so any computer you've allowed USB debugging can read the app's files, the sync
  password among them.

## Build

Requirements: **JDK 17 or newer** (Gradle 9.8 runs on 17 through 27; everything is built
for Java 17 whatever the JDK) and the Android SDK (platform 35, build tools 36). Point Gradle at the SDK with `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.

```sh
./gradlew :domain:test                 # engine unit tests (pure JVM)
./gradlew :app:assembleSideloadDebug   # full build, with hardcore mode
./gradlew :app:assemblePlayDebug       # Play-safe build: no Device Admin
./gradlew :app:installSideloadDebug    # build and install on a connected device
./gradlew :app:installSideloadRelease  # the build to live with: not debuggable
node --test extension/test             # the extension's helpers and its signed .crx
node scripts/release.mjs               # publish the extension and the app; see Updates
scripts/pack-extension.sh              # the extension as a .zip, loaded by hand (never updates)
```

The two flavors exist because the distribution channel is undecided: anti-uninstall uses
Device Admin, which carries Play Store policy risk, so the `play` build leaves it out.

Minimum Android version: 8.0 (API 26).

## Permissions

Usage access (to see the foreground app), draw over other apps (the block screen),
notifications, and exemption from battery optimization. On Xiaomi/MIUI also enable
autostart. The Settings screen shows what's missing and links to each one. Sites need the
browser extension (above); the app only listens for it on `127.0.0.1`.

## Habits, and xrnloops

Habits live on your server's habit service (xrnloops-sync, from xrnloops, a GPLv3 fork of
[Loop Habit Tracker](https://github.com/iSoron/uhabits)). xrnrule
on the phone and on the laptop, xrnloops and the habits web page all read and write the same
habits and ticks there, so a habit ticked anywhere opens the blocks that ask for it everywhere.
Amounts logged on two devices at once add up; nothing needs to be installed next to xrnrule.

xrnloops stays the habit tracker (its list, habit screens, reminders and widgets) and xrnrule
the blocker; they work together through the habit server. xrnrule keeps its own copy of the
habits, with Loop's rules for scores, streaks and automatic ticks in `:domain`, so blocks can ask
for a habit on the laptop too. xrnloops tells xrnrule the moment something is ticked in it (a
signature-protected broadcast, so both apps must be signed with the same key), and xrnrule asks
it to sync every half hour, raising an alert when it doesn't answer.

## Project layout

| Module | |
|---|---|
| `:domain` | The rules engine: a pure Kotlin state machine with no Android dependencies, fully unit-tested |
| `:app` | Compose UI, the foreground service that enforces blocks, the extension's door, persistence, permissions |
| `:hardcore` | Device Admin receiver (sideload builds only) |
| `:desktop` | The laptop daemon and its KWin script; see [desktop/README.md](desktop/README.md) |
| `extension/` | The browser extension, for both devices |
| `server/` | `xrnrule-sync.py`, the tiny sync server (Python), and its status page |

The engine's rules (states, transitions, invariants I1–I40, multi-device sync in §2.4) are
specified in `docs/domain-contract.md`, which is in Vietnamese.

## License

xrnrule is free software under the GNU General Public License, version 3 or later; see
[LICENSE](LICENSE). Parts of it are ported from [Loop Habit Tracker](https://github.com/iSoron/uhabits)
(also GPLv3), copyright Álinson Santos Xavier and its contributors; those files say so at the
top.
