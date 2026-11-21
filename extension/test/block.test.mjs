// block.js, the page a blocked site turns into, against a stubbed page and background.
// Run: node --test extension/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), "utf8");

/** block.js opened at [url], with xrnrule answering every beat with [verdict]. */
function blockPage(url, verdict) {
  const u = new URL(url);
  const elements = {};
  const element = (id) => (elements[id] ??= {
    id, textContent: "", hidden: false, href: "",
    classList: { toggle() {}, add() {}, remove() {} },
    addEventListener() {},
  });
  const replaced = [];
  const sandbox = {
    URL, URLSearchParams, console,
    navigator: { userAgent: "Mozilla/5.0 (X11; Linux x86_64)" },
    document: {
      title: "", visibilityState: "visible", hasFocus: () => true,
      getElementById: element, addEventListener() {},
    },
    window: { addEventListener() {}, close() {} },
    history: { length: 1, back() {} },
    location: { href: url, protocol: u.protocol, search: u.search, hash: u.hash, replace: (to) => { replaced.push(to); } },
    setInterval: () => 0, setTimeout: () => 0, clearTimeout() {},
    chrome: { runtime: { sendMessage: (_msg, reply) => reply(verdict), lastError: undefined } },
  };
  sandbox.self = sandbox;
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("block.js"), sandbox);
  return { replaced, elements };
}

const PAGE = "chrome-extension://test/block.html";

test("once the site opens again, the block page goes back where it came from", () => {
  const back = "https://m.example.com/watch?v=1";
  const p = blockPage(`${PAGE}?site=example.com#${back}`, { blocked: false });
  assert.deepEqual(p.replaced, [back]);
});

test("the block page only goes back to the site it stands in for, not anywhere it was given", () => {
  // block.html is open to every site (web_accessible_resources), so any page could send a
  // visitor through it to an address of its choosing, from the extension's own origin.
  for (const elsewhere of ["https://phish.example/login", "https://example.com.phish.example/", "javascript:alert(1)"]) {
    const p = blockPage(`${PAGE}?site=example.com#${elsewhere}`, { blocked: false });
    assert.deepEqual(p.replaced, [], elsewhere);
    assert.equal(p.elements.title.textContent, "example.com is open again");
  }
  // A rule on a path stands in for that site too.
  assert.deepEqual(blockPage(`${PAGE}?site=example.com%2Fshorts#https://www.example.com/shorts/1`, { blocked: false }).replaced, ["https://www.example.com/shorts/1"]);
});

test("the letter's square is xrnrule's tile, and its white letter reaches 3:1 for large text", () => {
  const tile = read("icons/icon.svg").match(/<rect [^>]*fill="(#[0-9A-Fa-f]{6})"/)[1].toLowerCase();
  const brand = read("page.css").match(/--brand:\s*(#[0-9a-fA-F]{6})/)[1].toLowerCase();
  assert.equal(brand, tile);
  const lum = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
    .reduce((sum, c, i) => sum + c * [0.2126, 0.7152, 0.0722][i], 0);
  assert.match(read("page.css"), /\.shield \.pic \{[^}]*font-size: 34px[^}]*color: #fff|\.shield \.pic \{[^}]*color: #fff[^}]*font-size: 34px/);
  assert.ok(1.05 / (lum(brand) + 0.05) >= 3);
});
