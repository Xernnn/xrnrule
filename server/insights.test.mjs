// The Insights page's reading of both devices' event log (server/web/insights.js): the blocks it
// reads, which block a try counts for, one of each shared event, ranges and days where the devices are
// (I54), clean-day streaks and a limit's use day by day.
//
//     node --test server        (from the repo root)
import { test } from "node:test";
import assert from "node:assert/strict";

// Viewed from a browser in another zone than the devices': their days are the ones counted.
process.env.TZ = "America/Los_Angeles";
const { normalizeBlocks, blockOf, isSite, mergeShared, compute, limitUse, dayOf, dayStart, timeline } = await import("./web/insights.js");

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

test("blocks keep their list order and carry no colour: a block is told by its name", () => {
  assert.deepEqual(blocks.map((b) => b.id), ["social", "yt", "games", "a", "b", "c", "d", "e", "f"]);
  assert.ok(blocks.every((b) => !("colour" in b)));
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

test("a day starts at midnight where the devices are, across a change of the clocks", () => {
  assert.equal(dayStart(ZONE, dayOf(ZONE, NOW)), TODAY);
  // Europe/Berlin went to summer time on 29 Mar 2026: that day had 23 hours.
  const day = dayOf("Europe/Berlin", Date.UTC(2026, 2, 29, 12));
  assert.equal(dayStart("Europe/Berlin", day + 1) - dayStart("Europe/Berlin", day), 23 * HOUR);
});
