// What both web pages share (server/web/ui.js) that isn't drawing: the blocks' tones, what a block
// covers, the hours a schedule blocks today, how a limit's meter is coloured, how long ago.
//
//     node --test server        (from the repo root)
import { test } from "node:test";
import assert from "node:assert/strict";

const { TONES, toneAt, coverParts, daySpans, limitTone, ago } = await import("./web/ui.js");

test("a block's tone is its place in the list, the phone's eight muted tones round again", () => {
  assert.deepEqual(TONES, ["#D27A4A", "#5B8FD6", "#4E9E68", "#8F7AD8", "#CE6488", "#3E9E92", "#AE8834", "#4C98C4"]);
  assert.equal(toneAt(0), "#D27A4A");
  assert.equal(toneAt(7), "#4C98C4");
  assert.equal(toneAt(8), "#D27A4A", "the ninth block shares the first's");
});

test("what a block covers is a part per kind, each to go after its glyph, what it leaves alone last", () => {
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
