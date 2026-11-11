// background.js against an xrnrule that takes the call and never answers: stopped (kill -STOP) or
// hung. Its port still accepts, so only a time limit on the call tells it from one answering
// slowly. Run: node --test extension/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), "utf8");

/** A chrome.* where every call resolves to nothing and every event takes listeners. */
function fakeChrome(overrides) {
  const stub = (path) => new Proxy(function () {}, {
    get: (_t, key) => {
      const name = `${path}.${String(key)}`;
      if (key === "then") return undefined;
      return Object.hasOwn(overrides, name) ? overrides[name] : stub(name);
    },
    apply: () => Promise.resolve(path.endsWith("query") || path.endsWith("Rules") ? [] : {}),
  });
  return stub("chrome");
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("an xrnrule that never answers is unreachable, and a minute on everything with a rule is blocked", { timeout: 5000 }, async () => {
  let now = 1_800_000_000_000;
  const timers = [];
  // The last rules xrnrule sent: nothing blocked yet, reddit.com has a limit.
  const stored = { rules: { v: "1", blocked: [], watched: [{ domain: "reddit.com", except: [] }] } };
  const written = [];
  const sandbox = {
    URL, console, AbortController,
    Date: class extends Date { static now() { return now; } },
    // Takes the call, never answers: only giving up ends it.
    fetch: (_url, { signal } = {}) => new Promise((_resolve, reject) => signal?.addEventListener("abort", () => reject(new Error("aborted")))),
    setTimeout: (fn, ms) => timers.push({ fn, ms }),
    clearTimeout: () => {},
  };
  sandbox.self = sandbox;
  sandbox.chrome = fakeChrome({
    "chrome.runtime.getURL": (p) => `chrome-extension://test/${p}`,
    "chrome.runtime.getManifest": () => ({ version: "0" }),
    "chrome.management.getSelf": async () => ({ installType: "normal" }),
    "chrome.storage.local.get": async (key) => (key in stored ? { [key]: stored[key] } : {}),
    "chrome.storage.local.set": async (values) => { Object.assign(stored, values); },
    "chrome.storage.local.remove": async (key) => { delete stored[key]; },
    "chrome.declarativeNetRequest.getSessionRules": async () => [],
    "chrome.declarativeNetRequest.updateSessionRules": async ({ addRules }) => { written.push(addRules); },
  });
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("background.js"), sandbox);
  const limit = sandbox.Xrn.CALL_TIMEOUT_MS;

  /** One rule fetch, xrnrule never answering: given up once it has waited its limit. */
  async function refreshWhileHung() {
    timers.length = 0;
    const done = sandbox.refreshRules();
    await settle();
    const giveUp = timers.find((t) => t.ms === limit);
    assert.ok(giveUp, "a call to xrnrule has a time limit");
    giveUp.fn();
    await done;
    await settle();
  }

  await refreshWhileHung();
  assert.equal(stored.offlineSince, now, "unreachable from the first call it didn't answer");
  assert.ok(!written.flat().some((r) => r.condition.requestDomains.includes("reddit.com")), "not yet a minute");

  now += sandbox.Xrn.FAIL_CLOSED_MS;
  await refreshWhileHung();
  assert.ok(written.at(-1).some((r) => r.condition.requestDomains.includes("reddit.com")), "a minute on, everything with a rule");
});

/** background.js loaded with xrnrule answering every call at once; [overrides] add to chrome.*. */
function answering(overrides = {}) {
  const urls = [];
  const stored = {};
  const sandbox = {
    URL, console, AbortController, setTimeout, clearTimeout,
    fetch: async (url) => {
      urls.push(String(url));
      return { ok: true, json: async () => ({ v: "1", blocked: [], watched: [] }) };
    },
  };
  sandbox.self = sandbox;
  sandbox.chrome = fakeChrome({
    "chrome.runtime.getURL": (p) => `chrome-extension://test/${p}`,
    "chrome.runtime.getManifest": () => ({ version: "0" }),
    "chrome.management.getSelf": async () => ({ installType: "normal" }),
    "chrome.storage.local.get": async (key) => (key in stored ? { [key]: stored[key] } : {}),
    "chrome.storage.local.set": async (values) => { Object.assign(stored, values); },
    "chrome.storage.local.remove": async (key) => { delete stored[key]; },
    ...overrides,
  });
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("background.js"), sandbox);
  return { sandbox, urls, stored };
}

test("every rule fetch says whether a window the extension can see has focus (I49)", async () => {
  // A private window it may not run in, or another profile's, is in front when none has: the
  // laptop then treats the window in front as the extension being off.
  let focused = false;
  const bg = answering({ "chrome.windows.getLastFocused": async () => ({ id: 1, focused }) });
  await bg.sandbox.refreshRules();
  assert.ok(bg.urls.some((u) => u.endsWith("/ext/rules?focused=0")), bg.urls.join(" "));
  // The new tab page, about:addons, xrnrule's block page: no content script, but its own window.
  focused = true;
  await bg.sandbox.refreshRules();
  assert.ok(bg.urls.at(-1).endsWith("/ext/rules?focused=1"), bg.urls.at(-1));

  // Only private windows open: no window it can see at all, so none has focus.
  const none = answering({ "chrome.windows.getLastFocused": async () => { throw new Error("No last-focused window"); } });
  await none.sandbox.refreshRules();
  assert.ok(none.urls.at(-1).endsWith("/ext/rules?focused=0"), none.urls.at(-1));

  // A browser that can't say (no windows API) says nothing, which xrnrule takes as before.
  const blind = answering({ "chrome.windows.getLastFocused": undefined });
  await blind.sandbox.refreshRules();
  assert.ok(blind.urls.at(-1).endsWith("/ext/rules"), blind.urls.at(-1));
});
