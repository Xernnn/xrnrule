// The Insights page's reading of both devices' event log (server/web/insights.js): the blocks it
// reads and their tones, which block a try counts for, one of each shared event, ranges and days
// where the devices are (I54), the period before, the busiest hour, each block's tries over the
// range and its day so far, clean-day streaks, a limit's use day by day and a history day's figures,
// and which icon each try, each thing tried most and each block shows.
//
//     node --test server        (from the repo root)
import { test } from "node:test";
import assert from "node:assert/strict";

// Viewed from a browser in another zone than the devices': their days are the ones counted.
process.env.TZ = "America/Los_Angeles";
const { normalizeBlocks, blockOf, isSite, mergeShared, compute, limitUse, dayOf, dayStart, timeline, dayByBlock, dayLine, tryKey } = await import("./web/insights.js");

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const ZONE = "Asia/Tokyo";
/** 12:00 on Wednesday 18 Nov 2026 in Tokyo. */
const NOW = Date.UTC(2026, 10, 18, 3, 0);
/** Midnight in Tokyo, the devices' day start. */
const TODAY = Date.UTC(2026, 10, 17, 15, 0);

const blocks = normalizeBlocks([
  { id: "social", name: "Social", targetPackages: ["com.instagram.android"], targetSites: ["reddit.com", "x.com"] },
  { id: "yt", name: "YouTube", targetPackages: ["com.google.android.youtube"], targetSites: ["youtube.com"], exceptSites: ["music.youtube.com"], dailyAllowanceMs: 30 * MIN },
  { id: "games", name: "Games", targetDesktopApps: ["steam"] },
  ...["a", "b", "c", "d", "e", "f"].map((id) => ({ id, name: id })),
]);
const tried = (d, target, at, name = target) => ({ d, t: "blocked", target, name, at });

test("blocks keep their list order, and take their tone by it, round again past the eighth", () => {
  assert.deepEqual(blocks.map((b) => b.id), ["social", "yt", "games", "a", "b", "c", "d", "e", "f"]);
  assert.deepEqual(blocks.map((b) => b.tone), ["#D27A4A", "#5B8FD6", "#4E9E68", "#8F7AD8", "#CE6488", "#3E9E92", "#AE8834", "#4C98C4", "#D27A4A"]);
  // The laptop's /api/history shape reads the same.
  const laptop = normalizeBlocks([{ id: "x", name: "X", apps: ["steam"], sites: ["youtube.com/shorts"], except: [], dailyMs: 5, perDevice: true }]);
  assert.deepEqual([...laptop[0].apps], ["steam"]);
  assert.deepEqual(laptop[0].sites, ["youtube.com"]);
  assert.equal(laptop[0].dailyMs, 5);
});

test("a try counts for the first block that covers it, sites and their subdomains included", () => {
  assert.equal(blockOf(blocks, "com.instagram.android").id, "social");
  assert.equal(blockOf(blocks, "www.youtube.com").id, "yt");
  assert.equal(blockOf(blocks, "m.youtube.com").id, "yt");
  assert.equal(blockOf(blocks, "steam").id, "games");
  assert.equal(blockOf(blocks, "music.youtube.com"), null, "left alone by its block");
  assert.equal(blockOf(blocks, "notreddit.com"), null);
  assert.equal(isSite(tried("phone", "reddit.com", 0), blocks), true);
  assert.equal(isSite(tried("phone", "com.instagram.android", 0, "Instagram"), blocks), false);
  assert.equal(isSite(tried("phone", "com.gone.app", 0), blocks), false, "a package no block names any more");
});

test("a habit done or an unlock, logged by both devices, counts once", () => {
  const merged = mergeShared([
    { d: "laptop", t: "habit", block: "social", at: NOW - 60 * MIN },
    { d: "phone", t: "habit", block: "social", at: NOW - 58 * MIN }, // the same, heard by sync
    { d: "phone", t: "habit", block: "social", at: NOW - 10 * MIN }, // done again later
    { d: "laptop", t: "unlock", block: "social", ms: 5 * MIN, start: NOW - 40 * MIN, at: NOW - 35 * MIN },
    { d: "phone", t: "unlock", block: "social", ms: 6 * MIN, start: NOW - 40 * MIN, at: NOW - 34 * MIN },
    tried("phone", "reddit.com", NOW - 5 * MIN),
    tried("laptop", "reddit.com", NOW - 5 * MIN),
  ]);
  assert.deepEqual(merged.filter((e) => e.t === "habit").map((e) => e.dev), ["laptop", "phone"], "each on the device that logged it first");
  const unlocks = merged.filter((e) => e.t === "unlock");
  assert.deepEqual(unlocks.map((e) => [e.ms, e.dev]), [[5 * MIN, "laptop"]], "the first to log it");
  assert.deepEqual(merged.filter((e) => e.t === "blocked").map((e) => e.dev), ["phone", "laptop"], "a try is one device's");
  // An unlock from before sessions were named: the same one on both devices within two minutes.
  const old = mergeShared([
    { d: "phone", t: "unlock", block: "social", ms: 5 * MIN, at: NOW - 10 * MIN },
    { d: "laptop", t: "unlock", block: "social", ms: 5 * MIN, at: NOW - 9 * MIN },
    { d: "laptop", t: "unlock", block: "social", ms: 5 * MIN, at: NOW - 2 * MIN },
  ]);
  assert.deepEqual(old.map((e) => e.dev), ["phone", "laptop"]);
});

test("an emergency unlock belongs to every device, and shows under each", () => {
  const events = mergeShared([tried("phone", "reddit.com", TODAY + HOUR)], { [`social@${TODAY + 2 * HOUR}`]: TODAY + 2 * HOUR });
  const e = events.find((x) => x.t === "emergency");
  assert.deepEqual([e.block, e.dev, e.at], ["social", "", TODAY + 2 * HOUR]);
  for (const device of ["all", "phone", "laptop"]) {
    const days = compute({ events, blocks, limits: {}, now: NOW, zone: ZONE }, { range: "today", device }).timeline;
    assert.ok(days[0].rows.some((r) => r.t === "emergency"), device);
  }
});

test("ranges are the devices' days, and the device filter keeps shared events", () => {
  const events = mergeShared([
    tried("phone", "reddit.com", TODAY + 1 * MIN), // 00:01 Wednesday in Tokyo
    tried("phone", "reddit.com", TODAY - 1 * MIN), // 23:59 Tuesday: yesterday there
    tried("laptop", "steam", TODAY - 6 * DAY + HOUR), // six days back: in the week
    tried("laptop", "steam", TODAY - 7 * DAY + HOUR), // seven: not
    { d: "laptop", t: "unlock", block: "social", ms: 10 * MIN, start: TODAY + HOUR, at: TODAY + HOUR + 10 * MIN },
    { d: "phone", t: "bought", block: "yt,games", ms: 5 * MIN, at: TODAY + 2 * HOUR },
    { d: "laptop", t: "break", ms: 15 * MIN, at: TODAY + 3 * HOUR },
  ]);
  const input = { events, blocks, limits: {}, now: NOW, zone: ZONE };
  assert.equal(compute(input, { range: "today" }).figures.tries, 1);
  assert.equal(compute(input, { range: "week" }).figures.tries, 3);
  assert.equal(compute(input, { range: "month" }).figures.tries, 4);
  const phone = compute(input, { range: "today", device: "phone" }).figures;
  assert.deepEqual([phone.tries, phone.unlockedMs, phone.boughtMs, phone.breaks], [1, 0, 5 * MIN, 0]);
  const laptop = compute(input, { range: "today", device: "laptop" }).figures;
  assert.deepEqual([laptop.tries, laptop.unlockedMs, laptop.boughtMs, laptop.breaks], [0, 10 * MIN, 0, 1], "the unlock is the laptop's, which logged it");
  // One block alone: a purchase past several counts for the first of them still there.
  const yt = compute(input, { range: "today", block: "yt" }).figures;
  assert.deepEqual([yt.tries, yt.boughtMs, yt.unlockedMs], [0, 5 * MIN, 0]);
  assert.equal(compute(input, { range: "today", block: "games" }).figures.boughtMs, 0);
  // Today goes by the hour, in the devices' zone; the heatmap by their weekday.
  const today = compute(input, { range: "today" });
  assert.equal(today.bars.length, 24);
  assert.deepEqual(today.bars[0].parts, [{ block: "social", n: 1 }]);
  assert.equal(today.heat[2][0], 1, "Wednesday, 00:00");
  const week = compute(input, { range: "week" });
  assert.equal(week.bars.length, 7);
  assert.deepEqual(week.bars.map((b) => b.total), [1, 0, 0, 0, 0, 1, 1]);
});

test("a stacked bar has a part per block in list order, and one for what no block covers", () => {
  const events = mergeShared([
    tried("laptop", "steam", TODAY + HOUR),
    tried("phone", "unknown.example", TODAY + HOUR),
    tried("phone", "com.instagram.android", TODAY + HOUR, "Instagram"),
    tried("phone", "com.instagram.android", TODAY + HOUR, "Instagram"),
  ]);
  const bar = compute({ events, blocks, limits: {}, now: NOW, zone: ZONE }, { range: "today" }).bars[1];
  assert.deepEqual(bar.parts, [{ block: "social", n: 2 }, { block: "games", n: 1 }, { block: null, n: 1 }]);
  assert.equal(bar.total, 4);
});

test("clean days: none tried, bought past or unlocked in an emergency, back to the log's first day", () => {
  const streaks = (events, view = {}) => Object.fromEntries(compute({ events, blocks, limits: {}, now: NOW, zone: ZONE }, { range: "month", ...view }).perBlock.map((p) => [p.id, p.streak]));
  const events = mergeShared([
    { d: "phone", t: "break", ms: 1, at: TODAY - 10 * DAY }, // the log begins ten days ago
    { d: "laptop", t: "break", ms: 1, at: TODAY - 10 * DAY },
    tried("phone", "reddit.com", TODAY - 3 * DAY + HOUR),
    tried("laptop", "steam", TODAY + HOUR),
    { d: "phone", t: "bought", block: "yt", ms: 5 * MIN, at: TODAY - DAY + HOUR },
  ], { [`a@${TODAY - 2 * DAY}`]: TODAY - 2 * DAY });
  const all = streaks(events);
  assert.equal(all.social, 3, "today, yesterday and the day before");
  assert.equal(all.games, 0, "tried today");
  assert.equal(all.yt, 1, "bought past yesterday");
  assert.equal(all.a, 2, "unlocked in an emergency two days ago");
  assert.equal(all.b, 11, "nothing since the log began");
  // On the laptop alone, the phone's try doesn't count against it.
  assert.equal(streaks(events, { device: "laptop" }).social, 11);
  // An empty log has no clean days to show.
  assert.deepEqual(Object.values(streaks([])).filter((n) => n !== 0), []);
});

test("a limit's use day by day: devices add up, or the one that used most when each has its own", () => {
  const record = {
    "phone-1": { [TODAY]: 20 * MIN, [TODAY - DAY]: 35 * MIN },
    "laptop-1": { [TODAY]: 5 * MIN },
  };
  const shared = limitUse(record, blocks[1], { zone: ZONE, first: dayOf(ZONE, NOW) - 6, today: dayOf(ZONE, NOW) });
  assert.deepEqual([shared.used, shared.tracked, shared.over, shared.allowance], [60 * MIN, 2, 1, 60 * MIN]);
  assert.equal(shared.days.at(-1).used, 25 * MIN);
  assert.equal(shared.days[0].used, null, "a day nobody reported");
  const own = limitUse(record, { ...blocks[1], perDevice: true }, { zone: ZONE, first: dayOf(ZONE, NOW), today: dayOf(ZONE, NOW) });
  assert.equal(own.used, 20 * MIN);
  const laptop = limitUse(record, blocks[1], { zone: ZONE, first: dayOf(ZONE, NOW), today: dayOf(ZONE, NOW), device: "laptop" });
  assert.equal(laptop.used, 5 * MIN);
});

test("a limit the server has kept no day of shows today's use, as the shared state has it", () => {
  const input = { events: [], blocks, now: NOW, zone: ZONE, usedToday: { yt: 12 * MIN } };
  const yt = (limits, device = "all") => compute({ ...input, limits }, { range: "week", device }).perBlock.find((p) => p.id === "yt").limit;
  assert.equal(yt({}).today, 12 * MIN);
  assert.equal(yt({ yt: { "phone-1": { [TODAY]: 20 * MIN } } }).today, undefined, "the server's record when it has one");
  assert.equal(yt({}, "phone").today, undefined, "the state's figure is every device's");
});

test("the history runs newest first, a run of tries at one thing on one device as one row", () => {
  const events = mergeShared([
    tried("phone", "reddit.com", TODAY + HOUR),
    tried("phone", "reddit.com", TODAY + HOUR + 5 * MIN),
    tried("phone", "reddit.com", TODAY + HOUR + 20 * MIN),
    tried("laptop", "reddit.com", TODAY + HOUR + 21 * MIN),
    tried("phone", "reddit.com", TODAY + 3 * HOUR),
    tried("phone", "reddit.com", TODAY - HOUR),
  ]);
  const days = timeline(events, blocks, ZONE);
  assert.deepEqual(days.map((d) => d.tries), [5, 1]);
  assert.deepEqual(days[0].rows.map((r) => [r.dev, r.n]), [["phone", 1], ["laptop", 1], ["phone", 3]]);
  assert.equal(days[0].rows[2].from, TODAY + HOUR);
  assert.deepEqual(days[0].rows[0].blocks, ["social"]);
});

test("a try shows what was tried by its own icon: a site the block names, an app by the device it was tried on", () => {
  const key = (d, target, name) => tryKey({ ...tried(d, target, 0, name), dev: d }, blocks);
  assert.equal(key("phone", "com.instagram.android", "Instagram"), "app:com.instagram.android");
  assert.equal(key("laptop", "steam", "Steam"), "desk:steam");
  assert.equal(key("phone", "m.youtube.com"), "site:youtube.com", "the block's site, whose icon there is");
  assert.equal(key("laptop", "www.reddit.com"), "site:reddit.com");
  assert.equal(key("laptop", "example.org"), "site:example.org", "a site no block names any more: its own host");
  // The history's rows and the most tried carry them; other events have none.
  const events = mergeShared([
    tried("phone", "com.instagram.android", NOW - 3 * HOUR, "Instagram"),
    tried("laptop", "m.youtube.com", NOW - 2 * HOUR),
    { d: "laptop", t: "unlock", block: "social", ms: 5 * MIN, at: NOW - HOUR },
  ]);
  assert.deepEqual(timeline(events, blocks, ZONE)[0].rows.map((r) => r.icon), [null, "site:youtube.com", "app:com.instagram.android"]);
  const data = compute({ events, blocks, limits: {}, now: NOW, zone: ZONE }, { range: "today" });
  assert.deepEqual(data.top.map((t) => [t.name, t.icon]), [["Instagram", "app:com.instagram.android"], ["m.youtube.com", "site:youtube.com"]]);
});

test("the most tried is all of it, the page showing the first eight until asked for more", () => {
  const events = mergeShared(Array.from({ length: 11 }, (_, i) => tried("laptop", `site${i}.example.com`, NOW - (i + 1) * MIN)));
  assert.equal(compute({ events, blocks, limits: {}, now: NOW, zone: ZONE }, { range: "today" }).top.length, 11);
});

test("each block brings its icons, or the ones its page worked out", () => {
  assert.deepEqual(blocks[0].logos.items.map((x) => x.key), ["app:com.instagram.android", "site:reddit.com", "site:x.com"]);
  const given = { items: [{ key: "desk:steam", name: "Steam" }], extra: 1 };
  assert.equal(normalizeBlocks([{ id: "g", name: "G", apps: ["steam"], sites: [], logos: given }])[0].logos, given);
  const perBlock = compute({ events: [], blocks, limits: {}, now: NOW, zone: ZONE }, {}).perBlock;
  assert.equal(perBlock[0].logos, blocks[0].logos, "By block draws them");
});

test("a day starts at midnight where the devices are, across a change of the clocks", () => {
  assert.equal(dayStart(ZONE, dayOf(ZONE, NOW)), TODAY);
  // Europe/Berlin went to summer time on 29 Mar 2026: that day had 23 hours.
  const day = dayOf("Europe/Berlin", Date.UTC(2026, 2, 29, 12));
  assert.equal(dayStart("Europe/Berlin", day + 1) - dayStart("Europe/Berlin", day), 23 * HOUR);
});

test("each figure has the period before's: yesterday, or the 7 or 30 days before, once the log reaches back", () => {
  const events = mergeShared([
    tried("phone", "reddit.com", TODAY + HOUR),
    tried("phone", "reddit.com", TODAY - HOUR), // yesterday
    tried("phone", "reddit.com", TODAY - 2 * HOUR),
    { d: "laptop", t: "unlock", block: "social", ms: 10 * MIN, start: TODAY - 3 * HOUR, at: TODAY - 3 * HOUR },
    tried("laptop", "steam", TODAY - 8 * DAY), // the 7 days before this week
    tried("laptop", "steam", TODAY - 13 * DAY + HOUR), // their first
    tried("laptop", "steam", TODAY - 14 * DAY + HOUR), // the log's first day, before them
  ]);
  const input = { events, blocks, limits: {}, now: NOW, zone: ZONE };
  const today = compute(input, { range: "today" });
  assert.deepEqual([today.figures.tries, today.before.tries, today.before.unlockedMs], [1, 2, 10 * MIN]);
  const week = compute(input, { range: "week" });
  assert.deepEqual([week.figures.tries, week.before.tries], [3, 2]);
  assert.equal(compute(input, { range: "week", device: "phone" }).before.tries, 0, "the device filter holds for it too");
  // The log begins within these 30 days: "0 before" would be about days it never saw.
  assert.equal(compute(input, { range: "month" }).before, null);
  assert.equal(compute({ ...input, events: [] }, { range: "today" }).before, null, "nothing logged at all");
  // Begun in the middle of the 7 days before: not all of them seen, so nothing to set beside.
  const late = mergeShared([tried("phone", "reddit.com", TODAY - 10 * DAY), tried("phone", "reddit.com", TODAY + HOUR)]);
  assert.equal(compute({ ...input, events: late }, { range: "week" }).before, null);
});

test("the busiest hour of the week is the first of the busiest, Monday 00:00 onwards, as the phone has it", () => {
  // Wednesday 18 Nov in Tokyo: two at 05:00 and two at 09:00; Tuesday: one at 23:00.
  const events = mergeShared([
    tried("phone", "reddit.com", TODAY + 9 * HOUR + MIN), tried("phone", "x.com", TODAY + 9 * HOUR + 2 * MIN),
    tried("phone", "reddit.com", TODAY + 5 * HOUR), tried("laptop", "steam", TODAY + 5 * HOUR + MIN),
    tried("phone", "reddit.com", TODAY - HOUR),
  ]);
  const data = compute({ events, blocks, limits: {}, now: NOW, zone: ZONE }, { range: "week" });
  assert.deepEqual(data.peak, { day: 2, hour: 5, n: 2 });
  assert.equal(compute({ events: [], blocks, limits: {}, now: NOW, zone: ZONE }, { range: "week" }).peak, null);
});

test("each block's tries over the range, by day, or by hour today up to now", () => {
  const events = mergeShared([
    tried("phone", "reddit.com", TODAY + HOUR),
    tried("phone", "reddit.com", TODAY + 2 * HOUR),
    tried("laptop", "steam", TODAY - 2 * DAY + HOUR),
  ]);
  const input = { events, blocks, limits: {}, now: NOW, zone: ZONE };
  const week = Object.fromEntries(compute(input, { range: "week" }).perBlock.map((p) => [p.id, p.spark]));
  assert.deepEqual(week.social, [0, 0, 0, 0, 0, 0, 2]);
  assert.deepEqual(week.games, [0, 0, 0, 0, 1, 0, 0]);
  const today = compute(input, { range: "today" });
  assert.equal(today.nowHour, 12);
  const social = today.perBlock.find((p) => p.id === "social").spark;
  assert.equal(social.length, 13, "00:00 to 12:00, nothing drawn for hours still to come");
  assert.deepEqual([social[1], social[2]], [1, 1]);
});

test("a block's day so far, for its card: its tries, unlocks and their time, minutes bought", () => {
  const events = mergeShared([
    tried("phone", "reddit.com", TODAY + HOUR),
    tried("laptop", "com.instagram.android", TODAY + 2 * HOUR, "Instagram"),
    tried("phone", "reddit.com", TODAY - HOUR), // yesterday
    { d: "laptop", t: "unlock", block: "social", ms: 12 * MIN, start: TODAY + 3 * HOUR, at: TODAY + 3 * HOUR },
    { d: "phone", t: "unlock", block: "social", ms: 12 * MIN, start: TODAY + 3 * HOUR, at: TODAY + 3 * HOUR + MIN }, // the same one
    { d: "phone", t: "bought", block: "yt", ms: 5 * MIN, at: TODAY + 4 * HOUR },
  ]);
  const days = dayByBlock({ events, blocks, now: NOW, zone: ZONE });
  assert.deepEqual(days.get("social"), { tries: 2, unlocks: 1, unlockedMs: 12 * MIN, boughtMs: 0 });
  assert.deepEqual(days.get("yt"), { tries: 0, unlocks: 0, unlockedMs: 0, boughtMs: 5 * MIN });
  assert.equal(dayLine(days.get("social")), "2 tries · 12 min open");
  assert.equal(dayLine(days.get("yt")), "0 tries · 5 min bought");
  assert.equal(dayLine({ tries: 1, unlocks: 0, unlockedMs: 0, boughtMs: 0 }), "1 try");
});

test("a history day's head: its tries by block, unlocks and their time, minutes bought", () => {
  const events = mergeShared([
    tried("phone", "reddit.com", TODAY + HOUR),
    tried("phone", "reddit.com", TODAY + HOUR + MIN),
    tried("laptop", "steam", TODAY + 2 * HOUR),
    tried("phone", "unknown.example", TODAY + 2 * HOUR),
    { d: "laptop", t: "unlock", block: "social", ms: 10 * MIN, start: TODAY + 3 * HOUR, at: TODAY + 3 * HOUR },
    { d: "phone", t: "bought", block: "yt", ms: 5 * MIN, at: TODAY + 4 * HOUR },
  ]);
  const [day] = timeline(events, blocks, ZONE);
  assert.deepEqual([day.tries, day.unlocks, day.unlockedMs, day.boughtMs], [4, 1, 10 * MIN, 5 * MIN]);
  assert.deepEqual(day.parts, new Map([["social", 2], ["games", 1], [null, 1]]), "what no block covers too");
});
