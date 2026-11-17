// content.js, the script in every web page, against a stubbed page and background: what it does
// with xrnrule's answers. Run: node --test extension/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), "utf8");

/**
 * content.js loaded in a page at [url], visible and focused, with the background answering each
 * beat with [answer](msg). Timers run only when the test says. location.replace records where it
 * was sent and leaves the page where it is, as a page whose beforeunload is cancelled does.
 */
function page(url, answer) {
  const intervals = [];
  const timeouts = [];
  const sent = [];
  const replaced = [];
  const media = [];
  const node = () => ({
    classList: { add() {}, remove() {}, toggle() {} },
    attachShadow: () => ({ append() {}, querySelector: () => node() }),
    append() {}, appendChild() {}, replaceChildren() {}, remove() {},
    isConnected: true,
  });
  const listeners = [];
  const document = {
    visibilityState: "visible",
    hasFocus: () => true,
    addEventListener() {},
    querySelectorAll: (sel) => (sel === "video, audio" ? media : []),
    createElement: node,
    documentElement: node(),
    fullscreenElement: null,
  };
  const sandbox = {
    URL, console, document,
    location: { href: url, replace: (to) => { replaced.push(to); } },
    window: { addEventListener() {} },
    setInterval: (fn, ms) => intervals.push({ fn, ms }),
    clearInterval: () => {},
    setTimeout: (fn, ms) => timeouts.push({ fn, ms }),
    clearTimeout: () => {},
    requestAnimationFrame: () => {},
    chrome: {
      runtime: {
        getURL: (p) => `chrome-extension://test/${p}`,
        sendMessage: (msg, reply) => { sent.push(msg); reply(answer(msg)); },
        onMessage: { addListener: (fn) => listeners.push(fn) },
        lastError: undefined,
      },
    },
  };
  sandbox.self = sandbox;
  vm.runInNewContext(read("shared.js"), sandbox);
  vm.runInNewContext(read("content.js"), sandbox);
  const heartbeat = intervals.find((t) => t.ms === sandbox.Xrn.HEARTBEAT_MS);
  const fromBackground = (msg) => listeners.forEach((fn) => fn(msg, { id: "test" }, () => {}));
  return { sandbox, document, sent, replaced, media, fromBackground, beatAgain: () => heartbeat.fn() };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("a page that stays after being sent to the block page (beforeunload cancelled) beats on and is sent again", async () => {
  // The page asks "Leave site?" and Cancel keeps it. The copy that stopped for good there was
  // never counted again, and in-page navigation (YouTube's) makes no request for the redirect
  // rules to catch.
  const p = page("https://www.example.com/watch?v=1", () => ({ site: "example.com", blocked: true, page: null }));
  const video = { paused: false, ended: false, muted: false, volume: 1, readyState: 4, pause() { this.paused = true; } };
  p.media.push(video);
  await settle();
  assert.equal(p.replaced.length, 1);
  assert.match(p.replaced[0], /^chrome-extension:\/\/test\/block\.html\?site=example\.com#https:\/\/www\.example\.com\/watch\?v=1$/);
  assert.ok(video.paused, "what plays behind the dialog stops");
  p.beatAgain();
  await settle();
  assert.equal(p.sent.length, 2, "still beating");
  assert.equal(p.replaced.length, 2, "and sent off again");
});

test("a tab out of sight whose sound comes from an inner frame or WebAudio is reported playing (I47)", async () => {
  // Only the page's own <video> and <audio> were heard: an embedded player in a background tab
  // played on, and its site's limit counted nothing.
  const p = page("https://example.com/", () => ({ site: "example.com", blocked: false }));
  await settle();
  p.document.visibilityState = "hidden";
  p.document.hasFocus = () => false;
  p.sent.length = 0;
  p.beatAgain();
  await settle();
  assert.deepEqual(p.sent, [], "a silent tab out of sight says nothing");
  p.fromBackground({ type: "audible", audible: true }); // the browser hears this tab
  await settle();
  assert.equal(p.sent.at(-1)?.playing, true);
  assert.equal(p.sent.at(-1)?.focused, false);
  p.fromBackground({ type: "audible", audible: false });
  p.sent.length = 0;
  p.beatAgain();
  await settle();
  assert.deepEqual(p.sent, []);
});
