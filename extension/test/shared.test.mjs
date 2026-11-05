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

test("locationOf keeps the path, without query, case or trailing slash", () => {
  assert.equal(X.locationOf("https://www.YouTube.com/Shorts/?x=1#y"), "youtube.com/shorts");
  assert.equal(X.locationOf("https://m.youtube.com/watch?v=1"), "m.youtube.com/watch");
  assert.equal(X.locationOf("https://reddit.com/"), "reddit.com");
  assert.equal(X.locationOf("chrome://extensions"), null);
});

test("a target with a path covers that part of the site and nothing else", () => {
  assert.equal(X.matches("youtube.com/shorts", "youtube.com/shorts"), true);
  assert.equal(X.matches("m.youtube.com/shorts/abc", "youtube.com/shorts"), true);
  assert.equal(X.matches("youtube.com/shortsfoo", "youtube.com/shorts"), false);
  assert.equal(X.matches("youtube.com/watch", "youtube.com/shorts"), false);
  assert.equal(X.matches("youtube.com", "youtube.com/shorts"), false);
  assert.equal(X.matches("youtube.com/watch", "youtube.com"), true, "a host covers its paths");
  const rules = { blocked: [{ domain: "youtube.com", except: ["youtube.com/watch"] }] };
  assert.equal(X.blockedBy(rules, "youtube.com/watch/x"), null);
  assert.equal(X.blockedBy(rules, "youtube.com/feed").domain, "youtube.com");
});

test("redirectRules: a plain host, a path, and exceptions of both kinds", () => {
  const rules = { blocked: [
    { domain: "reddit.com", except: ["old.reddit.com"] },
    { domain: "youtube.com/shorts" },
    { domain: "x.com", except: ["x.com/messages"] },
  ] };
  const out = X.redirectRules(rules, null, "own.html", 1000);
  assert.deepEqual([...out].map((r) => r.id), [1000, 1001, 1002, 1003]);
  assert.equal(out[0].condition.regexFilter, "^https?://.*");
  assert.deepEqual([...out[0].condition.excludedRequestDomains], ["old.reddit.com"]);
  const path = new RegExp(out[1].condition.regexFilter);
  assert.deepEqual([...out[1].condition.requestDomains], ["youtube.com"]);
  assert.equal(path.test("https://www.youtube.com/shorts/abc?x=1"), true);
  assert.equal(path.test("https://youtube.com/shorts"), true);
  assert.equal(path.test("https://youtube.com/shortsfoo"), false);
  assert.equal(path.test("https://notyoutube.com/shorts"), false);
  assert.equal(out[1].action.redirect.regexSubstitution, "own.html?site=youtube.com%2Fshorts#\\0");
  assert.equal(out[2].condition.excludedRequestDomains, undefined, "a path can't go in that list");
  assert.equal(out[3].action.type, "allow");
  assert.ok(out[3].priority > out[2].priority);
});

test("blockUrl prefers the engine's page and carries the way back in the hash", () => {
  assert.equal(X.blockUrl("http://127.0.0.1:47321/block", "chrome-extension://x/block.html", "reddit.com", "https://reddit.com/r/a?b=1"),
    "http://127.0.0.1:47321/block?site=reddit.com#https://reddit.com/r/a?b=1");
  assert.equal(X.blockUrl(null, "chrome-extension://x/block.html", "reddit.com"), "chrome-extension://x/block.html?site=reddit.com");
});

test("enginePage only follows a block page on xrnrule's own address", () => {
  const engine = "http://127.0.0.1:47321";
  assert.equal(X.enginePage("http://127.0.0.1:47321/block", engine), "http://127.0.0.1:47321/block");
  assert.equal(X.enginePage(null, engine), null);
  assert.equal(X.enginePage("https://evil.example/login", engine), null);
  assert.equal(X.enginePage("http://127.0.0.1:47322/block", engine), null);
  assert.equal(X.enginePage("http://127.0.0.1.evil.example:47321/block", engine), null);
  assert.equal(X.enginePage("javascript:alert(1)", engine), null);
  assert.equal(X.enginePage("not a url", engine), null);
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
