# xrnrule on the laptop

The same blocks as the phone, enforced on a Linux laptop running KDE Plasma 6 or Hyprland. Websites are
blocked in the browser, and desktop apps (Steam, Discord, …) are closed. With sync set up,
everything is shared: an unlock redeemed on either device opens the block on both until the
same moment, daily limits count both devices' use (unless a block gives each its own), and
bought minutes, the daily unlock limit, the 15-minute break and lockdowns are shared too.

```
 xrnrule-desktop (systemd user service, JVM, runs :domain's engine)
   ├─ syncs the shared state with xrnrule-sync     (server/xrnrule-sync.py)
   ├─ shares habits and ticks with xrnloops-sync   (../xrnloops/server)
   ├─ D-Bus  ← KWin script (kwin/): which window is in front; closes blocked ones
   │   (on Hyprland: its own sockets instead, see below)
   └─ http://127.0.0.1:47321
        ├─ the local page: Blocks, Insights (both devices' history, /api/history), Settings
        ├─ /block: where blocked apps and sites land, with what unlocks them
        └─ /ext/*: the browser extension (../extension) asks what's blocked
```

## Install

Needs JDK 17 or newer to build (it targets Java 17, like the Android app), plus `notify-send` and
`xdg-open`, and either KDE Plasma 6 with `qdbus6` and `kwriteconfig6`, or Hyprland.

```sh
desktop/install.sh
```

It builds the daemon, installs it to `~/.local/share/xrnrule-desktop`, installs and turns on
the KWin script (not on Hyprland), and starts the systemd user service `xrnrule-desktop`. Run
it again to update.

Then load the browser extension once. The browser's extensions page (`chrome://extensions`
in Chromium browsers, including Helium) → Developer mode → **Load unpacked** → `extension/`
at the top of this repo (the phone's Quetta uses the same extension). On its Details page,
allow it in incognito windows, or incognito tabs aren't blocked: a private window it can't see,
kept in front, has the browser closed as if the extension were off.

Moving from xrnhabit: `install.sh` stops `xrnhabit-desktop`, removes its KWin script, and
moves `~/.config/xrnhabit` and `~/.local/share/xrnhabit` over, so the blocks and this
laptop's device id carry on.

### On Hyprland

Found at start from `HYPRLAND_INSTANCE_SIGNATURE` (or `XDG_CURRENT_DESKTOP=Hyprland`); anywhere
else it's the KWin path. Instead of the KWin script, the daemon follows Hyprland's event socket
(`$XDG_RUNTIME_DIR/hypr/<signature>/.socket2.sock`: `openwindow`, `activewindowv2`,
`closewindow`) and asks `.socket.sock` for `j/clients`, so it hears the same things in the same
order as the script would say them. A window's class (`initialClass` if it has none) is turned
into the `.desktop` id KWin would have given: a `.desktop` file of that name, else the one whose
`StartupWMClass` it is (Steam, Flatpak Discord), else the class in lower case. Blocked windows
are closed over the same socket with `hl.dsp.window.close` (Hyprland 0.56's Lua dispatch), or
`closewindow` on older versions. A locked screen is a monitor reporting `LOCK` in
`solitaryBlockedBy`. If Hyprland restarts, the daemon reconnects (to the newest instance if the
old signature is gone) and lists the windows again.

The service needs `HYPRLAND_INSTANCE_SIGNATURE` in the user manager's environment. Omarchy puts it
there at login (`systemctl --user show-environment` shows it); `install.sh` imports it if not.
`XRNRULE_DEBUG=1` logs each window reported and the daemon's answer.

### Sync with the phone

Edit `~/.config/xrnrule/desktop.properties`:

```properties
server=https://your-server        # where xrnrule-sync (/xrnrule) and xrnloops-sync (/xrnloops) live
user=you
passwordFile=~/path/to/a/file/with/the/password   # or password=…
port=47321
```

Then `systemctl --user restart xrnrule-desktop`. The phone uses the same server address with
`/xrnrule` on the end (xrnrule → Settings → Sync with laptop), and xrnloops uses `/xrnloops`
(xrnloops → Settings → Sync with home server).

Without a server it blocks on the laptop only, and nothing it does reaches the phone.

## Using it

- **Blocks** can be made on either device. On the local page, **New block** makes one with
  the phone's choices (a habit with tasks, a timer or both, a daily limit, a schedule or a
  lockdown), covering laptop apps and websites; add phone apps to it on the phone. A daily
  limit can also have "at most N minutes in a row, then a break" and can be shared or per
  device; a schedule can have several times; any block can leave parts of its sites alone
  (music.youtube.com). **Edit** changes a block's name, laptop apps, sites, minutes and tasks,
  links a task to a habit, or deletes it. Sites are shared with the phone; laptop apps only matter here. Tightening is
  instant. Anything that loosens a block waits 60 seconds with the page open, like on the
  phone, and a hardcore block can only be loosened on the phone.
- **Opening a blocked app** closes it at once (a tray app like Steam is also stopped), and the
  block page opens in the browser to say why.
- **Popups** are few: one at a time, gone by themselves after 10 seconds, and the same one at
  most every 10 minutes. The end of an unlock only pops up if the block has a site or a laptop
  app to lock here.
- **The phone going quiet** gets a popup and a line on the page: if it hasn't reached your home
  server for 12 hours, xrnrule may have stopped there.
- **Opening a blocked site** turns the tab into the block page, before the site loads. Once
  the site is open again, the tab goes back where it was. On limited sites the extension can
  show a corner clock (Settings on the local page) and warns at 10, 5, 3 and 1 minutes left.
- **Buying minutes.** On a daily limit's or a schedule's block page, **Take N more minutes** under
  **Leave this site** starts a wait; then type the sentence it shows. The sentence, the wait, the minutes and how many a
  day are in **Settings** on the local page (shared with the phone; easing them waits 60 s).
- **Unlocking** works as on the phone. Tick or log the habit (on the block's card, the phone, in
  xrnloops or on the habits web page: they share one habit server), tick a to-do item, or wait
  out a timer *on the block page*: it stands in
  for the blocked app, as the phone's shield does. Then press **Open**.
- **When an unlock runs out**, open blocked apps are closed and blocked tabs move to the
  block page within a few seconds.
- A locked screen or a sleeping laptop counts as "nothing in use", so daily allowances don't
  drain overnight.
- **History.** Blocked app opens, habits done and unlock time go to the sync server, which
  keeps 120 days of them from both devices for the **Review** page of the habits web app.
- If sync has been failing for an hour, a notification says so.

## Limits

This is a self-control tool, not a lock. Without root, nothing stops you stopping the
service, disabling the extension, or using a browser without it. The daemon makes that
visible instead:

- it notifies **"Website blocking is off"** when a browser is in front but the extension
  isn't answering;
- the local page warns when the KWin script (or Hyprland) hasn't reported in, or sync is failing.

Desktop apps are matched by their `.desktop` id (what KWin calls `desktopFileName`), or by
WM class when a window has none (on Hyprland, see above).

## Troubleshooting

```sh
systemctl --user status xrnrule-desktop      # running?
journalctl --user -u xrnrule-desktop -n 50   # what it said
qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.isScriptLoaded xrnrule   # KWin script loaded?
systemctl --user show-environment | grep HYPRLAND      # Hyprland: can the service find it?
curl -s http://127.0.0.1:47321/api/state | head -c 400
```

State lives in `~/.local/share/xrnrule/` (owner-only): `world.json`, `habit-entries.json` (the
habit history), `shared-base.json` (the copy last agreed with the server), `clock.json` (how far the clock was
set by hand, and the time zone blocks go by), `site-icons/`
(each blocked website's own icon, asked of that site, served to the page at `/api/site-icon`), and
`server-icons/` (the sync server's copies of the other devices' logos, the phone's apps above all).
The page asks `/api/icon?key=site:<host>|desk:<app id>|app:<package>` for any of them: the
laptop's own first, else the server's copy.

## Working on the page

The page is `src/main/resources/web/`; what it shares with the sync server's status page (the
look `xrn.css`, `ui.js`, Insights `insights.js`, the font, xrnloops' tile) lives in
`server/web/` and the build copies it in. To look at it on made-up data, with nothing enforced,
synced or fetched (never point it at the real data dir):

```sh
./gradlew --no-daemon :desktop:demo --args="47390 $(mktemp -d)"         # a month on both devices
./gradlew --no-daemon :desktop:demo --args="47391 $(mktemp -d) empty"   # a fresh install
```

Tests: `node --test desktop/src/test/js server` (the page, Insights) besides `:desktop:test`.
