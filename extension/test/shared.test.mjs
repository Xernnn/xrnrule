// The extension's shared helpers (shared.js), loaded as the browser loads it: a classic script
// that sets self.Xrn. Run: node --test extension/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const sandbox = { URL };
sandbox.self = sandbox;
vm.runInNewContext(readFileSync(new URL("../shared.js", import.meta.url), "utf8"), sandbox);
const X = sandbox.Xrn;

test("hostOf keeps web pages only, without www", () => {
  assert.equal(X.hostOf("https://www.YouTube.com/watch?v=1"), "youtube.com");
  assert.equal(X.hostOf("http://m.youtube.com."), "m.youtube.com");
  assert.equal(X.hostOf("chrome://extensions"), null);
  assert.equal(X.hostOf("not a url"), null);
});

test("blockedBy honours exceptions and subdomains", () => {
  const rules = { blocked: [{ domain: "youtube.com", except: ["music.youtube.com"] }, { domain: "reddit.com" }] };
  assert.equal(X.blockedBy(rules, "m.youtube.com").domain, "youtube.com");
  assert.equal(X.blockedBy(rules, "music.youtube.com"), null);
  assert.equal(X.blockedBy(rules, "old.reddit.com").domain, "reddit.com");
  assert.equal(X.blockedBy(rules, "notreddit.com"), null);
  assert.equal(X.blockedBy(null, "reddit.com"), null);
});

test("blockUrl prefers the engine's page and carries the way back in the hash", () => {
  assert.equal(X.blockUrl("http://127.0.0.1:47321/block", "chrome-extension://x/block.html", "reddit.com", "https://reddit.com/r/a?b=1"),
    "http://127.0.0.1:47321/block?site=reddit.com#https://reddit.com/r/a?b=1");
  assert.equal(X.blockUrl(null, "chrome-extension://x/block.html", "reddit.com"), "chrome-extension://x/block.html?site=reddit.com");
});

test("mmss and warnings read well", () => {
  assert.equal(X.mmss(65_000), "1:05");
  assert.equal(X.mmss(3_720_000), "1:02");
  assert.equal(X.mmss(-5), "0:00");
  assert.equal(X.warning("LIMIT", 1, "YouTube"), "1 minute left on YouTube today");
  assert.equal(X.warning("ROW", 5, "YouTube"), "5 minutes until a break from YouTube");
});

test("the manifest loads shared.js before content.js and exposes the block page", () => {
  const manifest = JSON.parse(readFileSync(new URL("../manifest.json", import.meta.url), "utf8"));
  assert.deepEqual(manifest.content_scripts[0].js, ["shared.js", "content.js"]);
  assert.ok(manifest.web_accessible_resources[0].resources.includes("block.html"));
  for (const page of ["block.html", "popup.html", "options.html"]) {
    const html = readFileSync(new URL(`../${page}`, import.meta.url), "utf8");
    assert.ok(html.indexOf("shared.js") < html.indexOf(page.replace(".html", ".js")), `${page}: shared.js first`);
  }
});
