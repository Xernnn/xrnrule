// What both web pages share (server/web/ui.js) that isn't drawing: the blocks' tones, what a block
// covers and its icons, the icons' keys and addresses, a habit's own colour, how a habit block's
// rows add up, the hours a schedule blocks today, how a limit's meter is coloured, how long ago.
//
//     node --test server        (from the repo root)
import { test } from "node:test";
import assert from "node:assert/strict";

const { TONES, toneAt, coverParts, coverText, daySpans, limitTone, ago, siteKey, appKey, deskKey, targetKey, iconSrc, iconSource, blockLogos, habitHue, joinTag } =
  await import("./web/ui.js");

test("a block's tone is its place in the list, the phone's eight muted tones round again", () => {
  assert.deepEqual(TONES, ["#D27A4A", "#5B8FD6", "#4E9E68", "#8F7AD8", "#CE6488", "#3E9E92", "#AE8834", "#4C98C4"]);
  assert.equal(toneAt(0), "#D27A4A");
  assert.equal(toneAt(7), "#4C98C4");
  assert.equal(toneAt(8), "#D27A4A", "the ninth block shares the first's");
});

test("what a block covers is a part per kind, what it leaves alone last", () => {
  assert.deepEqual(coverParts({ sites: ["reddit.com", "x.com"], phoneApps: 2 }), [
    { kind: "site", text: "reddit.com, x.com" },
    { kind: "phone", text: "2 apps" },
  ]);
  assert.deepEqual(coverParts({ sites: ["youtube.com"], phoneApps: 1, except: ["music.youtube.com"] }), [
    { kind: "site", text: "youtube.com" },
    { kind: "phone", text: "1 app" },
    { kind: "except", text: "except music.youtube.com" },
  ]);
  assert.deepEqual(coverParts({ laptopApps: ["Steam", "Discord"] }), [{ kind: "laptop", text: "Steam, Discord" }]);
  assert.deepEqual(coverParts({}), []);
});

const every = [1, 2, 3, 4, 5, 6, 7];
const win = (from, to) => ({ startMinuteOfDay: from * 60, endMinuteOfDay: to * 60 });

test("a day's blocked hours: each window on its days, one past midnight running into the next day", () => {
  // Weekdays 09-18 and every night 23-06, as the demo's Games block.
  const games = { activeDays: [1, 2, 3, 4, 5], activeWindow: win(9, 18), moreWindows: [{ days: every, window: win(23, 6) }] };
  assert.deepEqual(daySpans(games, 5), [[0, 360], [540, 1080], [1380, 1440]], "a Friday");
  assert.deepEqual(daySpans(games, 6), [[0, 360], [1380, 1440]], "a Saturday: only the night");
  // A window past midnight belongs to the day it starts on: Friday night's runs into Saturday only.
  const fridayNight = { activeDays: [5], activeWindow: win(22, 2), moreWindows: [] };
  assert.deepEqual(daySpans(fridayNight, 5), [[1320, 1440]]);
  assert.deepEqual(daySpans(fridayNight, 6), [[0, 120]]);
  assert.deepEqual(daySpans(fridayNight, 1), [], "Sunday night isn't one of its");
  // Monday's early hours come from Sunday's window.
  assert.deepEqual(daySpans({ activeDays: [7], activeWindow: win(23, 1), moreWindows: [] }, 1), [[0, 60]]);
  // Whole days, and windows that touch, are one stretch.
  assert.deepEqual(daySpans({ activeDays: [3], activeWindow: null, moreWindows: [] }, 3), [[0, 1440]]);
  assert.deepEqual(daySpans({ activeDays: every, activeWindow: win(8, 12), moreWindows: [{ days: every, window: win(12, 14) }] }, 2), [[480, 840]]);
  assert.deepEqual(daySpans(null, 1), []);
});

test("a limit's meter is the data colour, amber from 80 %, red once used up", () => {
  assert.equal(limitTone(0), "");
  assert.equal(limitTone(0.79), "");
  assert.equal(limitTone(0.8), "warn");
  assert.equal(limitTone(0.99), "warn");
  assert.equal(limitTone(1), "over");
  assert.equal(limitTone(1.4), "over");
});

test("how long ago a device checked in, short", () => {
  assert.equal(ago(20_000), "now");
  assert.equal(ago(16 * 60_000), "16 min");
  assert.equal(ago(3 * 3_600_000), "3 h");
  assert.equal(ago(4 * 86_400_000), "4 d");
});

test("an icon's key is the icon contract's: a site by its host, a phone app by its package, a laptop app by its id", () => {
  assert.equal(siteKey("Reddit.com"), "site:reddit.com");
  assert.equal(siteKey("youtube.com/shorts"), "site:youtube.com", "a rule on part of a site has the site's icon");
  assert.equal(appKey("com.instagram.android"), "app:com.instagram.android");
  assert.equal(deskKey("net.lutris.Lutris"), "desk:net.lutris.Lutris");
  // A blocked try: a host is a site; an id is an app of the device that tried it.
  assert.equal(targetKey("x.com", "phone", true), "site:x.com");
  assert.equal(targetKey("com.zhiliaoapp.musically", "phone", false), "app:com.zhiliaoapp.musically");
  assert.equal(targetKey("steam", "laptop", false), "desk:steam");
  assert.equal(targetKey("steam", "", false), null, "an app of no known device has no icon to ask for");
  assert.equal(targetKey("", "phone", false), null);
});

test("each page asks its own server for an icon, and nothing for one its server doesn't have", () => {
  // The status page: relative, so it works under /xrnrule/ and in the hub's window.
  assert.equal(iconSrc("site:reddit.com"), "icons/site%3Areddit.com");
  assert.equal(iconSrc("desk:Some App"), "icons/desk%3ASome%20App");
  // Its index of what the server has: anything else is its glyph, with no request.
  iconSource({ known: ["site:reddit.com"] });
  assert.equal(iconSrc("site:reddit.com"), "icons/site%3Areddit.com");
  assert.equal(iconSrc("site:x.com"), null);
  iconSource({ known: null });
  assert.equal(iconSrc("site:x.com"), "icons/site%3Ax.com");
  // The laptop's page.
  iconSource({ url: (key) => `/api/icon?key=${encodeURIComponent(key)}` });
  assert.equal(iconSrc("app:com.instagram.android"), "/api/icon?key=app%3Acom.instagram.android");
  assert.equal(iconSrc(null), null);
  iconSource({ url: (key) => `icons/${encodeURIComponent(key)}` });
});

test("a block's icons go as the phone's tile: its apps, its laptop apps, then its sites, one tile for one service", () => {
  // The shared state's block: Instagram's site goes with its app; redd.it with reddit.com; one icon a host.
  const social = blockLogos({
    targetPackages: ["com.zhiliaoapp.musically", "com.instagram.android"], targetDesktopApps: ["steam"],
    targetSites: ["x.com", "reddit.com", "redd.it", "instagram.com", "youtube.com/shorts", "youtube.com"],
  });
  assert.deepEqual(social.items.map((x) => x.key), [
    "app:com.instagram.android", "app:com.zhiliaoapp.musically", "desk:steam", "site:reddit.com", "site:x.com", "site:youtube.com",
  ]);
  assert.equal(social.extra, 0);
  // redd.it alone is its own site, as Sites.shown keeps it.
  assert.deepEqual(blockLogos({ targetSites: ["redd.it"] }).items.map((x) => x.key), ["site:redd.it"]);
  // The laptop's block: its apps by their names, and only a count of the phone's.
  const names = { steam: "Steam", "net.lutris.Lutris": "Lutris" };
  const games = blockLogos({ desktopApps: ["steam", "net.lutris.Lutris"], shownSites: [], phoneApps: 2 }, (id) => names[id]);
  assert.deepEqual(games.items, [{ key: "desk:net.lutris.Lutris", name: "Lutris" }, { key: "desk:steam", name: "Steam" }]);
  assert.equal(games.extra, 2, "phone apps it can't show count in its +N");
  // Once the laptop's state names the phone's packages, they show, and their sites pair off.
  const yt = blockLogos({ phonePackages: ["com.google.android.youtube"], desktopApps: [], shownSites: ["youtube.com"], phoneApps: 1 });
  assert.deepEqual(yt.items.map((x) => x.key), ["app:com.google.android.youtube"]);
  assert.equal(yt.extra, 0);
});

test("what a block covers, in words, for its icons' title", () => {
  assert.equal(coverText({ sites: ["reddit.com", "x.com"], phoneApps: 2, laptopApps: ["Steam"], except: ["old.reddit.com"] }),
    "reddit.com, x.com · 2 phone apps · Steam · except old.reddit.com");
  assert.equal(coverText({ phoneApps: 1 }), "1 phone app");
  assert.equal(coverText({}), null);
});

test("a habit's own colour is its place in Loop's palette, light or dark; none without one", () => {
  assert.deepEqual(habitHue(0), { dark: "#EF9A9A", light: "#D32F2F" });
  assert.deepEqual(habitHue(11), { dark: "#64B5F6", light: "#1976D2" });
  assert.deepEqual(habitHue(40), habitHue(19), "past the end: the last");
  assert.equal(habitHue(undefined), null);
  assert.equal(habitHue(null), null);
});

test("a habit block's rows say how they add up, as the phone's card does", () => {
  const block = (trigger, requiredItems = -1, combineOr = false) => ({ trigger, requiredItems, combineOr });
  assert.equal(joinTag(block("CHECKLIST"), 1), null);
  assert.equal(joinTag(block("CHECKLIST"), 2), "Do both");
  assert.equal(joinTag(block("CHECKLIST"), 3), "Do all");
  assert.equal(joinTag(block("CHECKLIST", 2), 3), "Do 2 of 3");
  assert.equal(joinTag(block("COMBINED", -1, true), 2), "Do either");
  assert.equal(joinTag(block("COMBINED", -1, true), 3), "Timer or all tasks");
  assert.equal(joinTag(block("COMBINED"), 3), "Do all");
  assert.equal(joinTag(block("TIMER"), 1), null);
});
