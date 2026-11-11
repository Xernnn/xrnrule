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

test("a minute without xrnrule blocks every rule's site and still what it said was blocked", () => {
  const rules = {
    v: "1",
    // A running lockdown on reddit.com, which an xrnrule before the fix left out of watched.
    blocked: [{ domain: "reddit.com", except: [] }, { domain: "youtube.com", except: [] }],
    watched: [{ domain: "youtube.com", except: ["music.youtube.com"] }, { domain: "x.com", except: [] }],
  };
  const closed = X.failClosed(rules);
  assert.deepEqual([...closed.blocked].map((r) => r.domain), ["reddit.com", "youtube.com", "x.com"]);
  assert.ok(X.blockedBy(closed, "old.reddit.com"));
  assert.ok(X.blockedBy(closed, "x.com"));
  // An exception holds only where both lists give it.
  assert.ok(X.blockedBy(closed, "music.youtube.com"));
  assert.equal(closed.v, "1");
  // An xrnrule that sends no watched list: what it said was blocked.
  assert.deepEqual(JSON.parse(JSON.stringify(X.failClosed({ blocked: [{ domain: "a.com" }] }).blocked)), [{ domain: "a.com", except: [] }]);
  assert.equal(X.failClosed(null), null);
});

test("locationOf keeps the path, without query, case or trailing slash", () => {
  assert.equal(X.locationOf("https://www.YouTube.com/Shorts/?x=1#y"), "youtube.com/shorts");
  assert.equal(X.locationOf("https://m.youtube.com/watch?v=1"), "m.youtube.com/watch");
  assert.equal(X.locationOf("https://reddit.com/"), "reddit.com");
  assert.equal(X.locationOf("chrome://extensions"), null);
});

test("locationOf reads a path as the site does, so another spelling isn't a way past a path rule", () => {
  // youtube.com/%73horts is youtube.com/shorts to the server, and a doubled slash often is too.
  assert.equal(X.locationOf("https://www.youtube.com/%73horts/abc"), "youtube.com/shorts/abc");
  assert.equal(X.locationOf("https://www.youtube.com//shorts//abc/"), "youtube.com/shorts/abc");
  assert.ok(X.blockedBy({ blocked: [{ domain: "youtube.com/shorts" }] }, X.locationOf("https://youtube.com/%53Horts")));
  // Only the letters, digits and -._~ an escape may stand for: a space or a slash stays escaped.
  assert.equal(X.locationOf("https://a.com/a%20b%2Fc/%zz"), "a.com/a%20b%2fc/%zz");
  // xrnrule takes what follows "://" for the host: a path must never carry one.
  assert.equal(X.locationOf("https://limited.com/x/https://other.com/"), "limited.com/x/https:/other.com");
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
  assert.deepEqual([...out].map((r) => r.id), [1000, 1001, 1002, 1003, 1004, 1005, 1006]); // then the embeds
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

test("redirectRules: a blocked site embedded in another page is refused, an exception still holding", () => {
  const rules = { blocked: [{ domain: "youtube.com", except: ["music.youtube.com", "youtube.com/watch"] }] };
  const out = X.redirectRules(rules, "http://127.0.0.1:47321/block", "own.html", 1000);
  const embeds = out.filter((r) => r.condition.resourceTypes.includes("sub_frame"));
  const refused = embeds.find((r) => r.action.type === "block");
  assert.ok(refused, "a YouTube player inside another page doesn't play");
  assert.deepEqual([...refused.condition.requestDomains], ["youtube.com"]);
  assert.deepEqual([...refused.condition.excludedRequestDomains], ["music.youtube.com"]);
  assert.equal(new RegExp(refused.condition.regexFilter).test("https://www.youtube.com/embed/abc"), true);
  const allowed = embeds.find((r) => r.action.type === "allow");
  assert.ok(allowed && allowed.priority > refused.priority, "an exception with a path holds for an embed too");
  // Only a page of its own goes to the block page, which stands in for the site (I4): never a frame.
  for (const r of out.filter((x) => x.action.type === "redirect")) assert.deepEqual([...r.condition.resourceTypes], ["main_frame"]);
  // Embeds come last, so with too many rules it's they that are left out.
  assert.equal(out.at(-1), refused);
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

test("every element block.js asks for is on the page", () => {
  const html = readFileSync(new URL("../block.html", import.meta.url), "utf8");
  const script = readFileSync(new URL("../block.js", import.meta.url), "utf8");
  const ids = [...script.matchAll(/getElementById\("([^"]+)"\)/g)].map((m) => m[1]);
  assert.ok(ids.length > 0);
  for (const id of ids) assert.ok(html.includes(`id="${id}"`), `block.html has no #${id}`);
});

test("the clock stands out as CornerClock does on the phone", () => {
  for (const min of [10, 5, 3, 1]) {
    assert.equal(X.strongClock(min * 60_000 + 1), false);
    assert.equal(X.strongClock(min * 60_000), true);
    assert.equal(X.strongClock(min * 60_000 - 5000), true);
    assert.equal(X.strongClock(min * 60_000 - 6000), false);
  }
  assert.equal(X.strongClock(31_000), false);
  assert.equal(X.strongClock(30_000), true);
  assert.equal(X.strongClock(0), true);
});
