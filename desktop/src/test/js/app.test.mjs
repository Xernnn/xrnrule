// The laptop's page (src/main/resources/web/app.js), run in a stubbed page: no DOM, no daemon.
// Run: node --test desktop/src/test/js/*.test.mjs (AppJsTest runs it with the desktop tests).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

/**
 * The page's script with what it imports (server/web/ui.js and insights.js, which the build
 * copies in beside it) run before it in one context, their imports and exports taken out.
 */
function source() {
  const strip = (text) => text.replace(/^import .*$/gm, "").replace(/^export /gm, "");
  const read = (path) => strip(readFileSync(new URL(path, import.meta.url), "utf8"));
  return [read("../../../../server/web/ui.js"), read("../../../../server/web/insights.js"), read("../../main/resources/web/app.js")].join("\n;\n");
}

function load(href) {
  const url = new URL(href);
  const noop = () => {};
  const page = {
    location: { search: url.search, pathname: url.pathname, hash: url.hash, replace: noop },
    window: { addEventListener: noop, scrollTo: noop },
    document: { addEventListener: noop, getElementById: () => null, hasFocus: () => false, visibilityState: "hidden", documentElement: { dataset: {} } },
    fetch: () => new Promise(noop),
    setInterval: noop,
    setTimeout: noop,
    clearTimeout: noop,
    URL,
    URLSearchParams,
    Intl,
  };
  vm.runInNewContext(source() + "\n;globalThis.backHere = backHere; globalThis.joinTag = joinTag; globalThis.unlockWords = unlockWords; globalThis.iconSrc = iconSrc;", page);
  return page;
}

test("the block page goes back only to the site it stands in for", () => {
  // Any page could link to 127.0.0.1:47321/block?site=example.com#https://phish.example/ and,
  // once example.com wasn't blocked, send a visitor on from xrnrule's own page.
  const { backHere } = load("http://127.0.0.1:47321/block?site=youtube.com");
  assert.equal(backHere("https://www.youtube.com/watch?v=1", "youtube.com"), true);
  assert.equal(backHere("https://m.youtube.com/", "youtube.com/shorts"), true);
  assert.equal(backHere("https://phish.example/", "youtube.com"), false);
  assert.equal(backHere("https://youtube.com.phish.example/", "youtube.com"), false);
  assert.equal(backHere("https://notyoutube.com/", "youtube.com"), false);
  assert.equal(backHere("javascript:alert(1)", "youtube.com"), false);
  assert.equal(backHere("https://youtube.com/", null), false);
  assert.equal(backHere(null, "youtube.com"), false);
});

test("a habit block's rows say how they add up, as the phone's card does", () => {
  const { joinTag } = load("http://127.0.0.1:47321/");
  const block = (trigger, requiredItems = -1, combineOr = false) => ({ trigger, requiredItems, combineOr });
  assert.equal(joinTag(block("CHECKLIST"), 1), null);
  assert.equal(joinTag(block("CHECKLIST"), 2), "Do both");
  assert.equal(joinTag(block("CHECKLIST"), 3), "Do all");
  assert.equal(joinTag(block("CHECKLIST", 2), 3), "Do 2 of 3");
  assert.equal(joinTag(block("COMBINED", -1, true), 2), "Do either");
  assert.equal(joinTag(block("COMBINED"), 3), "Do all");
  assert.equal(joinTag(block("TIMER"), 1), null);
});

/** A habit as /api/state has it (UiHabit): amounts in thousandths, a timer's in minutes. */
const habit = (name, kind, unlock, amount, unit = "") => ({ id: name, name, kind, unit, unlock, amount, spent: 0, day: 0, dayValue: amount, yesterday: false, focusing: false, onBreak: false });
const item = (text, done = false, h = null) => ({ id: text, text, done, ...(h ? { habit: h } : {}) });
const habitBlock = (over) => ({ trigger: "CHECKLIST", requiredItems: -1, combineOr: false, timerMs: 0, requiredMs: 0, items: [], ...over });

test("the block page says what a habit block asks for, and what's left of it, as Habits First's", () => {
  const { unlockWords } = load("http://127.0.0.1:47321/block?site=reddit.com");
  const social = habitBlock({
    items: [
      item("Inbox zero"),
      item("Read", false, habit("Read", "COUNT", 20_000, 12_000, "pages")),
      item("Workout", false, habit("Workout", "TIMER", 30_000, 18_000)),
      item("Vitamins", true, habit("Vitamins", "CHECK", 1, 1)),
      item("Water", false, habit("Water", "COUNT", null, 0)), // "at most": never opens, nothing to do for it
    ],
  });
  assert.deepEqual({ ...unlockWords(social, "reddit.com") }, {
    title: "To unlock reddit.com, finish your habits first!",
    left: "Inbox zero, Read: 8 more pages and Workout: 12 more min.",
  });
  // Only some of them needed.
  const some = habitBlock({ requiredItems: 1, items: [item("Stretch"), item("Walk"), item("Tidy", false)] });
  assert.equal(unlockWords(some, "x.com").left, "Any 1 of Stretch, Walk or Tidy.");
  // A timer alone, and a timer that will do instead of the tasks.
  const timer = habitBlock({ trigger: "TIMER", requiredMs: 20 * 60_000, timerMs: 5 * 60_000 });
  assert.deepEqual({ ...unlockWords(timer, "Steam") }, { title: "To unlock Steam, wait out its timer first!", left: "15 more min on the timer." });
  const either = habitBlock({ trigger: "COMBINED", combineOr: true, requiredMs: 10 * 60_000, items: [item("Stretch"), item("Walk")] });
  assert.equal(unlockWords(either, "x.com").left, "Stretch and Walk or 10 more min on the timer.");
  // Nothing left (it's about to open): no line.
  assert.equal(unlockWords(habitBlock({ items: [item("Stretch", true)] }), "x.com").left, null);
});

test("the laptop's page asks the daemon for an icon by its key", () => {
  const { iconSrc } = load("http://127.0.0.1:47321/");
  assert.equal(iconSrc("site:reddit.com"), "/api/icon?key=site%3Areddit.com");
  assert.equal(iconSrc("desk:net.lutris.Lutris"), "/api/icon?key=desk%3Anet.lutris.Lutris");
});
