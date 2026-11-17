// The status page's reading of the shared state (server/web/blocks.js) against the engine's rules
// (docs/domain-contract.md §2.2, I39, I44, I50, I52, I54).
//
//     node --test server        (from the repo root)
import { test } from "node:test";
import assert from "node:assert/strict";

// Viewed from a browser in another zone than the devices': its day and hours are not theirs.
process.env.TZ = "America/Los_Angeles";
const { blockStatus, chips, today } = await import("./web/blocks.js");

const MIN = 60_000;
const DAY = 86_400_000;
/** 12:00 on a Wednesday in Tokyo, where the phone set its zone; 19:00 on the Tuesday here. */
const NOW = new Date(Date.UTC(2026, 10, 18, 3, 0));
/** Midnight in Tokyo: the devices' day start. */
const DAY_START = Date.UTC(2026, 10, 17, 15, 0);
const EVERY_DAY = { activeDays: [1, 2, 3, 4, 5, 6, 7], activeWindow: null, moreWindows: [] };
const hours = (from, to, days = EVERY_DAY.activeDays) => ({ activeDays: days, activeWindow: { startMinuteOfDay: from * 60, endMinuteOfDay: to * 60 }, moreWindows: [] });

/** A block as the devices write it: every field there (encodeDefaults). */
const block = (over = {}) => ({
  id: "b", name: "Feeds", enabled: true, mode: "HABIT", schedule: EVERY_DAY, enforcementTier: "SOFT",
  trigger: { type: "CHECKLIST", requiredAmount: -1, unit: "ITEMS" }, reward: { unlockDurationMs: 5 * MIN, creditTtlMs: 2 * 3600_000 },
  dailyAllowanceMs: 0, sessionMs: 0, sessionBreakMs: 0, perDevice: false, lockdownMs: 0, selfPaced: false,
  devices: "BOTH", blockedHours: null, habitFirst: false, lock: null, opensPerDay: 0, openWaitS: 0, ...over,
});
const doc = (over = {}) => ({ zones: { phone: { zone: "Asia/Tokyo", atWall: NOW.getTime() - DAY } }, ...over });
/** The phone's use of block "b" today. */
const used = (ms, opens = 0) => ({ allowance: { b: { phone: { dayStartWall: DAY_START, ms, atWall: NOW.getTime() - MIN, opens } } } });
const pause = { startedAtMonotonic: 0, startedAtWall: NOW.getTime() - MIN, durationMs: 15 * MIN };
const session = { b: { blockId: "b", startedAtMonotonic: 0, startedAtWall: NOW.getTime() - MIN, durationMs: 5 * MIN } };
const locked = { untilWall: NOW.getTime() + DAY, setAtWall: NOW.getTime() - DAY };

const status = (d, b) => blockStatus(d, b, NOW);

test("a habit block with a daily limit gives its free minutes first, then asks for the habit (I44)", () => {
  const b = block({ dailyAllowanceMs: 30 * MIN });
  assert.deepEqual(status(doc(used(10 * MIN)), b), ["open", "20 of 30 min left today"]);
  assert.equal(status(doc(used(30 * MIN)), b)[0], "blocked");
  assert.match(status(doc(used(30 * MIN)), b)[1], /habit/);
  assert.equal(status(doc({ ...used(30 * MIN), sessions: session }), b)[0], "open", "the habit opens it again");
});

test("habit first: blocked until the habit, and a used-up limit holds through an unlock (I44)", () => {
  const b = block({ dailyAllowanceMs: 30 * MIN, habitFirst: true });
  assert.match(status(doc(used(5 * MIN)), b).join(" "), /^blocked .*habit/);
  assert.equal(status(doc({ ...used(5 * MIN), sessions: session }), b)[0], "open");
  assert.match(status(doc({ ...used(30 * MIN), sessions: session }), b).join(" "), /^blocked Used up/);
});

test("blocked hours block a limit outright, and only inside them (I44)", () => {
  const inside = block({ mode: "ALLOWANCE", dailyAllowanceMs: 30 * MIN, blockedHours: hours(11, 13) });
  assert.equal(status(doc(used(0)), inside)[0], "blocked");
  const outside = block({ mode: "ALLOWANCE", dailyAllowanceMs: 30 * MIN, blockedHours: hours(13, 14) });
  assert.deepEqual(status(doc(used(0)), outside), ["open", "30 of 30 min left today"]);
  // A habit can't open blocked hours.
  assert.equal(status(doc({ sessions: session }), block({ blockedHours: hours(11, 13) }))[0], "blocked");
});

test("a locked block isn't lifted by a break, nor by minutes bought before (I50)", () => {
  const b = block({ mode: "ALLOWANCE", dailyAllowanceMs: 30 * MIN });
  const grant = { overrides: { b: { blockId: "b", startedAtMonotonic: 0, startedAtWall: NOW.getTime() - MIN, durationMs: 10 * MIN, deviceId: "phone" } } };
  assert.match(status(doc({ ...used(30 * MIN), pause }), b).join(" "), /^open On a break/);
  assert.match(status(doc({ ...used(30 * MIN), pause }), { ...b, lock: locked }).join(" "), /^blocked Used up/);
  assert.equal(status(doc({ ...used(30 * MIN), ...grant }), b)[0], "open");
  assert.equal(status(doc({ ...used(30 * MIN), ...grant }), { ...b, lock: locked })[0], "blocked");
  // A lock that ran out holds nothing.
  assert.equal(status(doc({ ...used(30 * MIN), pause }), { ...b, lock: { untilWall: NOW.getTime() - 1, setAtWall: 0 } })[0], "open");
});

test("opens a day count like minutes (I52)", () => {
  const b = block({ mode: "ALLOWANCE", opensPerDay: 3 });
  assert.deepEqual(status(doc(used(0, 1)), b), ["open", "2 of 3 opens left today"]);
  assert.equal(status(doc(used(0, 3)), b)[0], "blocked");
  // On a habit block, the limit first: the opens, then the habit.
  assert.equal(status(doc(used(0, 1)), block({ opensPerDay: 3 }))[0], "open");
  assert.equal(status(doc(used(0, 3)), block({ opensPerDay: 3 }))[0], "blocked");
});

test("each device its own limit: one device's use isn't the other's (I33)", () => {
  const b = block({ mode: "ALLOWANCE", dailyAllowanceMs: 30 * MIN, perDevice: true });
  const both = { allowance: { b: {
    phone: { dayStartWall: DAY_START, ms: 20 * MIN, atWall: 0, opens: 0 },
    laptop: { dayStartWall: DAY_START, ms: 20 * MIN, atWall: 0, opens: 0 },
  } } };
  assert.deepEqual(status(doc(both), b), ["open", "10 of 30 min left today"]);
  assert.equal(status(doc(both), { ...b, perDevice: false })[0], "blocked");
});

test("the day and the hours are the devices', not the viewing browser's (I54)", () => {
  // Here it is still Tuesday evening; on the devices it is Wednesday noon.
  assert.deepEqual(status(doc(used(25 * MIN)), block({ mode: "ALLOWANCE", dailyAllowanceMs: 30 * MIN })), ["open", "5 of 30 min left today"]);
  assert.equal(status(doc(), block({ mode: "SCHEDULE", schedule: hours(11, 13, [3]) }))[0], "blocked");
  assert.equal(status(doc(), block({ mode: "SCHEDULE", schedule: hours(18, 20, [2]) }))[0], "idle");
  assert.deepEqual(today(doc(), NOW), { dayStart: DAY_START, epochDay: Date.UTC(2026, 10, 18) / DAY });
});

test("the card says where a block holds and while it's locked (I39, I42)", () => {
  assert.deepEqual(chips(block(), NOW), ["habit"]);
  assert.deepEqual(chips(block({ devices: "LAPTOP", lock: locked, enforcementTier: "HARDCORE" }), NOW), ["habit", "hardcore", "laptop", "locked"]);
  assert.deepEqual(chips(block({ mode: "SCHEDULE", devices: "PHONE", lock: { untilWall: 1, setAtWall: 0 } }), NOW), ["schedule", "phone"]);
});
