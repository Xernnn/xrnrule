// The status page's sign-in form (server/web/status.js): it names no account of its own, and fills
// in the name last signed in with from this browser. The page runs here on a few stand-ins for the
// browser: elements that keep their attributes and listeners, localStorage, and a server that
// takes any password.
//
//     node --test server        (from the repo root)
import { test } from "node:test";
import assert from "node:assert/strict";

class Node {}
class Element extends Node {
  constructor(tag) {
    super();
    this.tag = tag;
    this.value = "";
    this.attributes = {};
    this.listeners = {};
    this.children = [];
    this.style = { setProperty() {}, cssText: "" };
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }
  addEventListener(type, listener) {
    this.listeners[type] = listener;
  }
  append(...nodes) {
    this.children.push(...nodes);
  }
  replaceChildren(...nodes) {
    this.children = nodes;
  }
  focus() {
    document.activeElement = this;
  }
  find(name) {
    if (this.attributes.name === name) return this;
    for (const child of this.children) {
      const found = child instanceof Element && child.find(name);
      if (found) return found;
    }
    return null;
  }
}

const app = new Element("div");
const stored = new Map();
const asked = [];
globalThis.Node = Node;
globalThis.document = {
  activeElement: null,
  createElement: (tag) => new Element(tag),
  createElementNS: (_, tag) => new Element(tag),
  getElementById: (id) => (id === "app" ? app : null),
};
globalThis.window = { addEventListener() {}, scrollY: 0, scrollTo() {} };
globalThis.location = { hash: "", href: "https://example.test/xrnrule/" };
globalThis.localStorage = {
  getItem: (key) => stored.get(key) ?? null,
  setItem: (key, value) => stored.set(key, String(value)),
  removeItem: (key) => stored.delete(key),
};
globalThis.setInterval = () => 0; // the page's redraw every half minute
globalThis.fetch = async (url, init = {}) => {
  asked.push(`${init.method ?? "GET"} ${new URL(url).pathname}`);
  if (init.method === "POST") return { status: 200, ok: true };
  throw new Error("offline"); // past the sign-in, the page has nothing to show here
};

/** Opens the page afresh, as a new visit from this browser, and returns its form once drawn. */
async function visit(time) {
  app.replaceChildren();
  await import(`./web/status.js?visit=${time}`);
  await new Promise((resolve) => setTimeout(resolve, 0));
  return { user: app.find("user"), password: app.find("password"), form: app.children[0]?.children[1] };
}

test("the sign-in form names no account at first, then the one last signed in with", async () => {
  const first = await visit(1);
  assert.equal(first.user.value, "", "no name filled in on a first visit");
  assert.equal(document.activeElement, first.user, "so typing starts with the name");

  first.user.value = " alex ";
  first.password.value = "a password";
  await first.form.listeners.submit({ preventDefault() {} });
  assert.deepEqual(asked.slice(0, 1), ["POST /xrnrule/session"]);
  assert.equal(stored.get("xrn.user"), JSON.stringify("alex"), "the name, never the password");
  assert.ok(![...stored.values()].some((value) => value.includes("a password")));

  stored.delete("xrn.signedIn"); // signed out since
  const next = await visit(2);
  assert.equal(next.user.value, "alex");
  assert.equal(document.activeElement, next.password, "only the password left to type");
});
