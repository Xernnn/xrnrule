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

/** chrome.storage.local.get: one key or a list of them. */
const pick = (stored, keys) => Object.fromEntries([keys].flat().filter((k) => k in stored).map((k) => [k, stored[k]]));

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
    "chrome.storage.local.get": async (keys) => pick(stored, keys),
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
  const bodies = [];
  const stored = {};
  const sandbox = {
    URL, console, AbortController, setTimeout, clearTimeout,
    fetch: async (url, init) => {
      urls.push(String(url));
      bodies.push(init?.body ? JSON.parse(init.body) : null);
      return { ok: true, json: async () => ({ v: "1", blocked: [], watched: [] }) };
    },
  };
  sandbox.self = sandbox;
  sandbox.chrome = fakeChrome({
    "chrome.runtime.getURL": (p) => `chrome-extension://test/${p}`,
    "chrome.runtime.getManifest": () => ({ version: "0" }),
    "chrome.management.getSelf": async () => ({ installType: "normal" }),
    "chrome.storage.local.get": async (keys) => pick(stored, keys),
    "chrome.storage.local.set": async (values) => { Object.assign(stored, values); },
    "chrome.storage.local.remove": async (key) => { delete stored[key]; },
    ...overrides,
  });
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("background.js"), sandbox);
  return { sandbox, urls, bodies, stored };
}

test("a page in a private window says so, for the laptop to leave its site out of screen time", async () => {
  const messages = [];
  const bg = answering({ "chrome.runtime.onMessage.addListener": (fn) => { messages.push(fn); } });
  const [onMessage] = messages;
  const heartbeats = () => bg.bodies.filter((_b, i) => bg.urls[i].endsWith("/ext/heartbeat"));
  // What the private tab is comes from the browser (the sender's tab), not from the page's message.
  await new Promise((resolve) => onMessage({ type: "beat", host: "example.com", focused: true, inPrivate: false }, { tab: { id: 1, incognito: true } }, resolve));
  await new Promise((resolve) => onMessage({ type: "beat", host: "example.org", focused: true, inPrivate: true }, { tab: { id: 2, incognito: false } }, resolve));
  assert.deepEqual(heartbeats().map((b) => [b.host, b.inPrivate]), [["example.com", true], ["example.org", false]]);
});

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

test("the rules alarm is made only when it isn't there, so a start of the worker doesn't put the poll off", async () => {
  const created = [];
  const create = (name) => { created.push(name); };
  // Already there, a minute after the last poll: a new start of the worker keeps it.
  answering({ "chrome.alarms.get": async (name) => (name === "rules" ? { name, periodInMinutes: 1 } : undefined), "chrome.alarms.create": create });
  await settle();
  assert.deepEqual(created, []);
  // None yet (just installed, or dropped at a browser restart): it's made.
  answering({ "chrome.alarms.get": async () => undefined, "chrome.alarms.create": create });
  await settle();
  assert.deepEqual(created, ["rules"]);
});

test("setPort and checkUpdate are taken from the extension's own pages only, not a content script", async () => {
  const listeners = [];
  const bg = answering({ "chrome.runtime.onMessage.addListener": (fn) => { listeners.push(fn); } });
  const [onMessage] = listeners;
  const page = { id: "test", tab: { id: 7 }, frameId: 0, url: "https://evil.example/" };
  let replied = false;
  assert.equal(onMessage({ type: "setPort", port: 9999 }, page, () => { replied = true; }), false);
  assert.equal(onMessage({ type: "checkUpdate" }, page, () => { replied = true; }), false);
  await settle();
  assert.equal(bg.stored.port, undefined, "the port stays");
  assert.equal(replied, false);
  // A page's content script still reports the site in front.
  assert.ok(await new Promise((resolve) => onMessage({ type: "beat", host: "example.com", focused: true }, page, resolve)));
  // The options page, in a tab of its own, may.
  const options = { id: "test", tab: { id: 8 }, url: "chrome-extension://test/options.html" };
  await new Promise((resolve) => onMessage({ type: "setPort", port: 9999 }, options, resolve));
  assert.equal(bg.stored.port, 9999);
});

test("without its site access the extension says nothing to xrnrule, so xrnrule takes the browser as one without it (I49)", async () => {
  // Chromium's "Site access: on specific sites" with only xrnrule's address, or Zen's https://*/*
  // switched off: no content script runs and no redirect applies, yet the worker still reached
  // xrnrule every minute, and on the laptop and the phone alike that is the extension being here.
  let granted = false;
  const asked = [];
  const added = [];
  const messages = [];
  const bg = answering({
    "chrome.permissions.contains": async ({ origins }) => { asked.push([...origins]); return granted; },
    "chrome.permissions.onAdded.addListener": (fn) => { added.push(fn); },
    "chrome.runtime.onMessage.addListener": (fn) => { messages.push(fn); },
    "chrome.windows.getLastFocused": async () => ({ id: 1, focused: true }),
  });
  await settle();
  assert.ok(asked.length, "read at start-up");
  assert.deepEqual(asked[0], JSON.parse(read("manifest.json")).host_permissions);
  const [onMessage] = messages;
  const page = { id: "test", tab: { id: 7 }, frameId: 0, url: "http://example.com/" };
  const ask = (msg) => new Promise((resolve) => onMessage(msg, page, resolve));

  await bg.sandbox.refreshRules();
  const beat = await ask({ type: "beat", host: "example.com", focused: true });
  const check = await ask({ type: "check", host: "example.com" });
  await settle();
  assert.deepEqual(bg.urls, [], "no rule fetch, heartbeat or check");
  assert.equal(beat.access, false);
  assert.equal(check.access, false);

  // Given back: xrnrule hears from it at once, not at the next poll.
  granted = true;
  for (const fn of added) fn({ origins: ["https://*/*"] });
  await settle();
  await settle();
  assert.ok(bg.urls.some((u) => u.endsWith("/ext/rules?focused=1")), bg.urls.join(" "));
  await ask({ type: "beat", host: "example.com", focused: true });
  assert.ok(bg.urls.some((u) => u.endsWith("/ext/heartbeat")), bg.urls.join(" "));
});

/**
 * background.js with xrnrule refusing the connection while [down] says so, answering at once
 * otherwise, under a clock the test moves; reddit.com has a limit and nothing is blocked yet.
 */
function sometimesDown() {
  const clock = { now: 1_800_000_000_000, down: false };
  const stored = {};
  const written = [];
  const rules = { v: "1", blocked: [], watched: [{ domain: "reddit.com", except: [] }] };
  const sandbox = {
    URL, console, AbortController, setTimeout, clearTimeout,
    Date: class extends Date { static now() { return clock.now; } },
    fetch: async () => {
      if (clock.down) throw new Error("connection refused");
      return { ok: true, json: async () => structuredClone(rules) };
    },
  };
  sandbox.self = sandbox;
  sandbox.chrome = fakeChrome({
    "chrome.runtime.getURL": (p) => `chrome-extension://test/${p}`,
    "chrome.runtime.getManifest": () => ({ version: "0" }),
    "chrome.management.getSelf": async () => ({ installType: "normal" }),
    "chrome.storage.local.get": async (keys) => pick(stored, keys),
    "chrome.storage.local.set": async (values) => { Object.assign(stored, values); },
    "chrome.storage.local.remove": async (key) => { delete stored[key]; },
    "chrome.declarativeNetRequest.getSessionRules": async () => [],
    "chrome.declarativeNetRequest.updateSessionRules": async ({ addRules }) => { written.push(addRules); },
  });
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("background.js"), sandbox);
  const refresh = async () => {
    await sandbox.refreshRules();
    for (let i = 0; i < 5; i++) await settle();
  };
  const failingClosed = () => (written.at(-1) ?? []).some((r) => r.condition.requestDomains.includes("reddit.com"));
  return { clock, stored, refresh, failingClosed };
}

test("stops shorter than a minute add up: a minute of them in all blocks everything with a rule (I48)", async () => {
  // Stopped for 50 s, let answer once, stopped again: each answer started the minute over, so
  // limited sites stayed open through the stops, and none of that time was counted.
  const bg = sometimesDown();
  await bg.refresh();
  bg.clock.down = true;
  await bg.refresh();
  bg.clock.now += 50_000;
  await bg.refresh();
  assert.equal(bg.failingClosed(), false, "50 s gone");
  bg.clock.down = false;
  await bg.refresh(); // one answer
  assert.equal(bg.failingClosed(), false, "answering: what's blocked now");
  bg.clock.now += 1_000;
  bg.clock.down = true;
  await bg.refresh();
  bg.clock.now += 10_000;
  await bg.refresh();
  assert.equal(bg.failingClosed(), true, "50 s and 10 s: a minute in all");
});

test("the clock set back while xrnrule is gone is past the minute, not short of it", async () => {
  // Date.now() - offlineSince went negative, so it stayed "under a minute" until the clock
  // caught up, and with the daemon stopped its own clock guard (I54) can't help.
  const bg = sometimesDown();
  await bg.refresh();
  bg.clock.down = true;
  await bg.refresh();
  assert.equal(bg.failingClosed(), false);
  bg.clock.now -= 3_600_000;
  await bg.refresh();
  assert.equal(bg.failingClosed(), true);
});

test("a worker started while xrnrule has been gone a minute never puts the narrower list back (I48)", async () => {
  // The worker comes and goes. Started again, its first word from xrnrule's silence came before
  // the stored silence was read: the redirects went back to only what was blocked, and the page
  // asking was told it wasn't blocked, until the next beat or poll.
  const now = 1_800_000_000_000;
  const stored = {
    rules: { v: "1", blocked: [], watched: [{ domain: "reddit.com", except: [] }] },
    offlineSince: now - 120_000, offlineMs: 0, offlineFrom: now - 120_000,
  };
  const written = [];
  const messages = [];
  const sandbox = {
    URL, console, AbortController, setTimeout, clearTimeout,
    Date: class extends Date { static now() { return now; } },
    fetch: async () => { throw new Error("connection refused"); },
  };
  sandbox.self = sandbox;
  sandbox.chrome = fakeChrome({
    "chrome.runtime.getURL": (p) => `chrome-extension://test/${p}`,
    "chrome.runtime.getManifest": () => ({ version: "0" }),
    "chrome.runtime.onMessage.addListener": (fn) => { messages.push(fn); },
    "chrome.management.getSelf": async () => ({ installType: "normal" }),
    // Read from disk: a moment later than the rest.
    "chrome.storage.local.get": async (keys) => { await settle(); return pick(stored, keys); },
    "chrome.storage.local.set": async (values) => { Object.assign(stored, values); },
    "chrome.storage.local.remove": async (key) => { delete stored[key]; },
    "chrome.declarativeNetRequest.getSessionRules": async () => [],
    "chrome.declarativeNetRequest.updateSessionRules": async ({ addRules }) => { written.push(addRules); },
  });
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("background.js"), sandbox);
  const [onMessage] = messages;
  const page = { id: "test", tab: { id: 7 }, frameId: 0, url: "https://reddit.com/" };
  const verdict = await new Promise((resolve) => onMessage({ type: "beat", host: "reddit.com", focused: true }, page, resolve));
  for (let i = 0; i < 10; i++) await settle();
  assert.equal(verdict.blocked, true, "the page asking is blocked");
  assert.ok(written.length, "the redirects were written");
  for (const rules of written) {
    assert.ok(rules.some((r) => r.condition.requestDomains.includes("reddit.com")), "never the narrower list");
  }
});

test("the browser hearing a tab is told to that tab's page, which can't hear an inner frame itself (I47)", async () => {
  const updated = [];
  const told = [];
  answering({
    "chrome.tabs.onUpdated.addListener": (fn) => { updated.push(fn); },
    "chrome.tabs.sendMessage": async (tabId, msg, options) => { told.push([tabId, msg, options]); },
  });
  const fire = (id, info, tab) => updated.forEach((fn) => fn(id, info, { id, active: false, ...tab }));
  fire(7, { audible: true }, { audible: true, mutedInfo: { muted: false } });
  fire(8, { audible: true }, { audible: true, mutedInfo: { muted: true } }); // muted: nothing heard
  fire(7, { title: "x" }, { audible: true });
  fire(7, { audible: false }, { audible: false });
  await settle();
  assert.deepEqual(told.map(([id, msg]) => [id, msg.type, msg.audible]), [[7, "audible", true], [8, "audible", false], [7, "audible", false]]);
  assert.equal(told[0][2]?.frameId, 0);
});
