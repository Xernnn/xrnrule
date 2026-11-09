// The extension in Firefox and its forks (Zen): the manifest wiring Firefox needs next to
// Chromium's, and background.js started the way Firefox starts it, as an event page with
// shared.js loaded first and no importScripts. Run: node --test extension/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), "utf8");
const manifest = JSON.parse(read("manifest.json"));

test("one manifest serves both: a service worker for Chromium, background scripts for Firefox", () => {
  assert.equal(manifest.background.service_worker, "background.js");
  assert.deepEqual(manifest.background.scripts, ["shared.js", "background.js"]);
  assert.equal(manifest.browser_specific_settings.gecko.id, "xrnrule@sonn.dev");
});

test("the origins the popup asks for are the manifest's host permissions", () => {
  const sandbox = { URL };
  sandbox.self = sandbox;
  vm.runInNewContext(read("shared.js"), sandbox);
  assert.deepEqual([...sandbox.Xrn.SITE_ORIGINS], manifest.host_permissions);
});

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

function startAsEventPage(manifestNow, extra = {}) {
  const sandbox = { URL, console, fetch: async () => { throw new Error("offline"); }, setTimeout, clearTimeout };
  sandbox.self = sandbox;
  sandbox.chrome = fakeChrome({
    "chrome.runtime.getURL": (p) => `moz-extension://test/${p}`,
    "chrome.runtime.getManifest": () => manifestNow,
    "chrome.runtime.requestUpdateCheck": undefined,
    "chrome.management.getSelf": async () => ({ installType: "normal" }),
    ...extra,
  });
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("background.js"), sandbox);
  return sandbox;
}

test("background.js starts without importScripts or a service worker", () => {
  assert.doesNotThrow(() => startAsEventPage(manifest));
});

test("asked to check for an update, Firefox's lack of requestUpdateCheck is an answer, not a crash", async () => {
  const bg = startAsEventPage({ ...manifest, update_url: "https://example.invalid/updates.xml" });
  const r = await bg.checkForUpdate();
  assert.equal(r.status, "unsupported");
  assert.equal(r.version, manifest.version);
  const plain = await startAsEventPage(manifest).checkForUpdate();
  assert.equal(plain.status, "no_update_url");
});
