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
