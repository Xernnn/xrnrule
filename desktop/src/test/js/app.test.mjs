// The laptop's page (src/main/resources/web/app.js), run in a stubbed page: no DOM, no daemon.
// Run: node --test desktop/src/test/js (AppJsTest runs it with the desktop tests).
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
  vm.runInNewContext(source() + "\n;globalThis.backHere = backHere; globalThis.joinTag = joinTag;", page);
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
