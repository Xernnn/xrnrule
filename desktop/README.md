# xrnrule on the laptop

The same blocks as the phone, enforced on a Linux laptop running KDE Plasma 6. Websites are
blocked in the browser, and desktop apps (Steam, Discord, …) are closed. With sync set up,
everything is shared: an unlock redeemed on either device opens the block on both until the
same moment, daily limits count both devices' use (unless a block gives each its own), and
bought minutes, the daily unlock limit, the 15-minute break and lockdowns are shared too.

```
 xrnrule-desktop (systemd user service, JVM, runs :domain's engine)
   ├─ syncs the shared state with xrnrule-sync     (server/xrnrule-sync.py)
   ├─ reads habit ticks from xrnloops-sync           (../xrnloops/server)
   ├─ D-Bus  ← KWin script (kwin/): which window is in front; closes blocked ones
   └─ http://127.0.0.1:47321
        ├─ the local page: blocks, unlocks, laptop apps & sites per block
        ├─ /block: where blocked apps and sites land, with what unlocks them
        └─ /ext/*: the browser extension (../extension) asks what's blocked
```

## Install

Needs JDK 17 to build (the same toolchain as the Android app), plus KDE Plasma 6 with
`qdbus6`, `kwriteconfig6`, `notify-send` and `xdg-open`.

```sh
desktop/install.sh
```

It builds the daemon, installs it to `~/.local/share/xrnrule-desktop`, installs and turns on
the KWin script, and starts the systemd user service `xrnrule-desktop`. Run it again to
update.

Then load the browser extension once. The browser's extensions page (`chrome://extensions`
in Chromium browsers, including Helium) → Developer mode → **Load unpacked** → `extension/`
at the top of this repo (the phone's Quetta uses the same extension). On its Details page,
allow it in incognito windows, or incognito tabs aren't blocked.

Moving from xrnhabit: `install.sh` stops `xrnhabit-desktop`, removes its KWin script, and
moves `~/.config/xrnhabit` and `~/.local/share/xrnhabit` over, so the blocks and this
laptop's device id carry on.

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

Without a server it blocks on the laptop only, which is of little use: blocks are created on
the phone.

## Using it

- **Blocks** can be made on either device. On the local page, **+ New block** makes one with
  the phone's choices (a habit with tasks, a timer or both, a daily limit, a schedule or a
  lockdown), covering laptop apps and websites; add phone apps to it on the phone. A daily
  limit can also have "at most N minutes in a row, then a break" and can be shared or per
  device; a schedule can have several times; any block can leave parts of its sites alone
  (music.youtube.com). **Edit** changes a block's name, laptop apps, sites, minutes and tasks,
  links a task to a habit, or deletes it. Sites are shared with the phone; laptop apps only matter here. Tightening is
  instant. Anything that loosens a block waits 60 seconds with the page open, like on the
  phone, and a hardcore block can only be loosened on the phone.
- **Opening a blocked app** closes it at once (a tray app like Steam is also stopped). A
  notification appears, and the block page opens in the browser.
- **Opening a blocked site** turns the tab into the block page, before the site loads. Once
  the site is open again, the tab goes back where it was. On limited sites the extension can
  show a corner clock (Settings on the local page) and warns at 10, 5, 3 and 1 minutes left.
- **Buying minutes.** On a daily limit's or a schedule's block page, **Buy … minutes** starts
  a wait; then type the sentence it shows. The sentence, the wait, the minutes and how many a
  day are in **Settings** on the local page (shared with the phone; easing them waits 60 s).
- **Unlocking** works as on the phone. Tick the habit (in xrnloops or on the habits web page,
  for linked items), tick a to-do item, or wait out a timer *on the block page*: it stands in
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
- the local page warns when the KWin script hasn't reported in, or sync is failing.

Desktop apps are matched by their `.desktop` id (what KWin calls `desktopFileName`), or by
WM class when a window has none.

## Troubleshooting

```sh
systemctl --user status xrnrule-desktop      # running?
journalctl --user -u xrnrule-desktop -n 50   # what it said
qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.isScriptLoaded xrnrule   # KWin script loaded?
curl -s http://127.0.0.1:47321/api/state | head -c 400
```

State lives in `~/.local/share/xrnrule/` (`world.json`, and `shared-base.json`: the copy last
agreed with the server).
