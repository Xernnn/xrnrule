# Laptop screen time, and the phone's

Insights on the phone shows time per app and site, but only the phone's: Android keeps that
history, and the phone app keeps its own per-site log. The laptop counted nothing. A browser
extension that logs every site visited ("all sites plus tabs") filled the gap, and it sent that
log to somebody else. This replaces it. The laptop daemon counts minutes per app and per site,
sends them to the user's own sync server, and Insights shows them beside the phone's. The phone
sends its own the same way (the owner's call, 2026-11-26), so the web pages and the hub show both.

## What counts

**On the laptop.** The daemon already knows what is in front, for enforcement:

- **App:** the focused window's app (KWin's `desktopFileName`, or on Hyprland the id worked out
  from the window class, `Hyprland.appId`). It's the same id a block's `targetDesktopApps` uses.
  A browser is an app like any other, so its whole time counts under it.
- **Site:** the host of the active tab in the focused browser window. The extension's content
  script reports it to the daemon (`POST /ext/heartbeat` on 127.0.0.1, every 2.5 s, and at once
  when focus moves). Sites are a breakdown of the browser's time, not extra time. A host counts
  without `www.`, `m.` or `mobile.`, and without the path a block can name (I46), as on the phone.
- **Not counted:**
  - time on a block page, xrnrule's or the extension's. The block page stands in for the blocked
    app or site so a wait timer runs (I4), but nobody is using that site. The time goes to the
    browser, with no site.
  - sites in a private window. The extension says when a page is private (`inPrivate` in the
    heartbeat, from 2.3.12). The time goes to the browser, with no site. An older extension
    doesn't say, so its private pages count like the rest.
  - a tab playing out of sight. Background sound counts toward a limit (I47), but it isn't screen time.

Nothing counts while the laptop isn't in use:

- **Locked screen.** The same signal enforcement uses (`WindowSystem.screenLocked`, a monitor
  reporting `LOCK` on Hyprland), read every 3 s.
- **Asleep.** The daemon's tick sees a gap in the boot-time clock (more than 10 s between ticks).
  What was open is closed at the last tick before the gap.
- **No input for 3 minutes.** The daemon asks the compositor through the standard Wayland protocol
  `ext-idle-notify-v1` (`IdleWatch`), as idle daemons do. It needs no new permission, and KWin
  and Hyprland both have it. The compositor won't call it idle while an app inhibits idle, as a
  browser does while a video plays. So watching a video counts, and walking away from a paused
  one stops counting. When the idle notice comes, the three minutes before it are taken back.
  Without the protocol, idle isn't known, and a locked screen and sleep still stop the count.

Time is measured on the boot-time clock (`/proc/uptime`, which runs through sleep) and given to days
by the wall clock, split at local midnight in the zone the laptop goes by (I54).

**On the phone**, as its Insights has always counted:

- **App:** the app in front as Android's usage events have it (`ScreenTime`, the figure Digital
  Wellbeing shows): from an activity resuming to the app's last one pausing, only while the
  screen is on, without the home screen and the system UI.
- **Site:** the host the xrnrule extension in the phone's browser reports, while that browser is
  in front and the screen is on and unlocked (`SiteUsageLog`), by the same host rule as the
  laptop's (`Sites.usageHost`).
- **Not counted as a site**, as on the laptop: the block page standing in for a site, a private
  tab (`inPrivate`, from extension 2.3.12), and a tab playing out of sight. Their time is the
  browser's, with no site. The engine still hears the block page's site, for its wait timer (I4).
- Days split at local midnight in the zone the phone goes by (I54).

## Where it lives

**On the laptop** (`UsageLog`, `usage.json` in the daemon's data dir, owner-only like the rest),
kept 120 days:

```json
{"days": {"20418": {"apps": {"zen": 5400000, "steam": 900000}, "sites": {"youtube.com": 1200000}}}}
```

The figure for what's in front grows as it's used. To take the idle minutes back, the last 3
minutes are shown but not stored until they're older than that. So a stored figure only ever
grows. It's written once a minute and when the daemon stops: a crash loses at most a minute and
those 3.

**On the phone**: Android keeps the apps' history; the app keeps `site_usage.json` (31 days), and,
in the `usage_upload` preferences, the last day a send got through and to which server and user.

**On the server** (`xrnrule-sync.py`, `usage.json` beside `events.jsonl`):

```
POST /usage   {"days": {"2026-11-26": {"apps": {app id: ms}, "sites": {host: ms}}}, "names": {app id: name}}
              from a device (X-Device: laptop or phone). The largest figure seen stays for each
              device, day and app or site: a day's use only grows, and a device that lost its file
              can't lower it.
GET  /usage?since=YYYY-MM-DD
              {"usage": {device: {day: {"apps": {...}, "sites": {...}}}}, "names": {...}}
```

The laptop sends once a minute, the days whose stored figures changed since the last send. A new
daemon sends everything once, and every ten minutes today and yesterday go again whatever. Like the event log, the server keeps 120 days. Days come as
`YYYY-MM-DD`, the laptop's local date. Laptop app ids come with their names, which other devices
can't look up. The server checks the shape (a real date within the kept range, at most a day
ahead; ids and hosts of the usual characters; figures between 0 and 25 hours; at most 500 apps
and 500 sites a day) and skips anything else. It writes the file at most every 5 minutes, so a
restart may lose the last few; the laptop's resend of today and yesterday puts them back.

**The phone sends too** (`UsageUpload`), in the same format, as device `phone`: each day's time per
app (its package) and per site, with the apps' names as the phone shows them. It needs no job of its
own. A send follows a sync round that worked, at most every 5 minutes, so it goes while the phone
is in use and the server can be reached. It sends the days from the last one a send got through
(yesterday at the latest, for the minutes after that send) to today. A figure only grows and the
server keeps the largest, so a day sent again changes nothing. A phone that was offline catches up
the same way, 30 days at most (as far as Insights goes, and the site log keeps). The first send,
and the first to another server or user, sends those 30 days. It goes only to the sync server set
in Settings, with the sync login; with sync off nothing goes. Nothing else leaves the phone for it.

## Where it shows

- **Phone, Insights.**
  - The Apps and Sites cards add the laptop's time to the phone's. Under All, each row shows the
    two devices' times beside their icons. Phone and Laptop each show their own.
  - A Screen time card shows each day's total as a bar split by device, with each device's
    figure. Today shows the figures alone.
  - A day's total on each device is its apps' time; sites are part of it. Laptop apps go by their
    laptop names, under a laptop icon.
  - The phone's own time comes from the phone, not the server: its copy there is left out of what
    the server answers, so nothing counts twice.
- **Laptop page and the server's status page** (the shared `insights.js`). The same Screen time
  card: the range's time, each device's when there are two, a column a day split by device, then
  the apps and the sites with their time.
  The laptop page reads it from `/api/history`, which merges this laptop's own count with the
  server's.
- **xrnhub**, `GET /api/v1/screen-time?from=&to=&device=`: each day's time per device (`laptop`,
  `phone`), app and site, from the server's `usage.json`.

## Limits and screen time

Screen time is a statistic. It never goes into the engine, so it can't change what's blocked
(contract §6).

A limit that applies on both devices already counts both devices' time, and nothing here changes
that, the phone's sending included. Each device reports its own use of each limit in the shared
state (`allowance`, by device id), not in `/usage`, and the engine adds the other devices' use to
its own unless the block is per device (I19, I33, §2.4). The review of 2026-11-26 saw it happen:
phone 15 + laptop 15 = a 30-minute limit.

The two figures differ on purpose, and the limit is never the smaller for it:

- a limit counts only while its target isn't blocked (I19), and not on an excepted host (I31);
- it counts a tab playing out of sight (I47), which screen time doesn't;
- it keeps counting while the laptop is idle and unlocked. Idle stops only the statistic: making
  it stop a limit would make leaving a video paused a way to save minutes.

## Stopping things (anti-bypass)

None of this is enforcement, so stopping any of it can't get past a limit. What enforcement does
is unchanged:

- **The daemon stopped.** Nothing is counted on the laptop for that time. Enforcement is as before.
  The extension blocks every site with a rule after 60 s of silence in a day (I48). Desktop apps
  aren't closed, the known limit of a tool without root (desktop/README "Limits"). The gap shows as
  less screen time, never as more allowance.
- **The extension stopped or removed.** No sites are counted. The browser's time still counts under
  the browser. Enforcement is as before: a browser without the extension is closed while site
  blocks apply (I49).
- **The idle watch failing.** More counts, never less.
- **The phone not sending** (sync off, offline, a server from before `/usage`). The web pages and
  the hub lack the phone's time until it catches up. Insights on the phone is as before.
- **`usage.json` deleted.** The server keeps the largest figure for each day, and the pages show
  the larger of the laptop's and the server's. Nothing it held goes into a limit anyway.

## Left out

- Time by hour. Days are enough for the totals and the hub. The phone's own chart stays as it is.
- Window titles, page titles and paths. Only app ids and hosts are kept.
- A switch to turn it off, on either device. It counts whenever the daemon or the phone app runs,
  and goes to the server whenever sync is set up, as the event log does.
- The phone reading its own time back from the server, as the laptop page does (after a reinstall
  its site log starts afresh, and Android keeps the apps' only so long).

## Open questions

- ~~Should the phone send its time too?~~ Yes, to the owner's own sync server only (owner,
  2026-11-26). The Usage access line that said "Nothing leaves your phone" now says "Screen time
  goes only to your own server".
- Is 3 minutes the right idle time?
- While the phone's block screen covers an app, Android counts that app in front, so the time
  counts under it. The laptop leaves its block page out. Leave the phone's out too?
