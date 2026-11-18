// xrnrule status page: a read-only view of the state the phone and the laptop share
// (docs/domain-contract.md §2.4), kept live with a long-poll, and Insights: both devices' event
// log and limit use as this server keeps them (/events, /limits). It shares its login with the
// xrnloops web app on the same server: a cookie either page's sign-in sets, which no script can
// read (xrnrule-sync.py, "/session"). The password itself is never kept. The look, the icons and
// Insights are shared with the laptop's local page (the desktop build copies them).

import { blockStatus, minutes, timeFmt, today, sharedZone, rulesOf, useToday, isLocked, windows } from "./blocks.js";
import { h, icon, inkOn } from "./ui.js";
import { PALETTE, normalizeBlocks, mergeShared, renderInsights } from "./insights.js";

const KEY_SIGNED_IN = "xrn.signedIn"; // a hint only: whether to try the cookie or show the form
const KEY_OLD_AUTH = "xrnloops.auth"; // the password, as these pages kept it before the cookie
const KEY_INSIGHTS = "xrnrule.insights";
const HEADERS = { "X-Xrn-Client": "web" }; // what makes the cookie count
const DAY = 86_400_000;

let signedIn = load(KEY_SIGNED_IN) === true;
let doc = null;
let etag = "";
let online = true;
let updated = { at: "", by: "" };
let devices = {}; // device -> epoch ms of its last check-in
let tab = location.hash === "#insights" ? "insights" : "blocks";

function load(key) {
  try {
    return JSON.parse(localStorage.getItem(key));
  } catch {
    return null;
  }
}

function save(key, value) {
  try {
    if (value) localStorage.setItem(key, JSON.stringify(value));
    else localStorage.removeItem(key);
  } catch {
    /* private window: works for this visit only */
  }
}

class AuthError extends Error {}

/** The server's answer to too many wrong passwords from this address. */
const LOCKED_OUT = "Too many tries · wait 15 min";
const WRONG_LOGIN = "Wrong password";

function check(res) {
  if (res.status === 401) throw new AuthError();
  if (res.status === 429) throw new Error(LOCKED_OUT);
}

/** Trades a password ("Basic …") for the login cookie. */
async function signIn(basic) {
  const res = await fetch(new URL("session", location.href), { method: "POST", headers: { ...HEADERS, Authorization: basic }, cache: "no-store" });
  check(res);
  if (!res.ok) throw new Error(`${res.status}`);
  signedIn = true;
  save(KEY_SIGNED_IN, true);
}

async function fetchState(wait) {
  const headers = { ...HEADERS };
  if (wait && etag) headers["If-None-Match"] = etag;
  const res = await fetch(new URL(wait ? `state?wait=${wait}` : "state", location.href), { headers, cache: "no-store" });
  check(res);
  if (res.status === 304) return false;
  if (!res.ok) throw new Error(`${res.status}`);
  doc = await res.json();
  etag = res.headers.get("ETag") ?? "";
  updated = { at: res.headers.get("X-Updated") ?? "", by: res.headers.get("X-Updated-By") ?? "" };
  return true;
}

/** When the phone and the laptop last checked in; refreshed with each redraw. */
async function fetchDevices() {
  try {
    const res = await fetch(new URL("devices", location.href), { headers: HEADERS, cache: "no-store" });
    check(res);
    if (res.ok) devices = await res.json();
  } catch {
    /* the state still shows; the next redraw tries again */
  }
}

async function live(gen) {
  while (gen === generation) {
    try {
      if (await fetchState(25)) render();
      setOnline(true);
    } catch (e) {
      if (e instanceof AuthError) return signOut("Signed out");
      setOnline(false);
      await new Promise((r) => setTimeout(r, 5000));
    }
  }
}

let generation = 0;

function setOnline(value) {
  if (online === value) return;
  online = value;
  render();
}

async function start() {
  const gen = ++generation;
  await fetchState(0);
  await fetchDevices();
  shell = null;
  render();
  live(gen);
}

function signOut(message) {
  generation++;
  signedIn = false;
  shell = null;
  hist = null;
  save(KEY_SIGNED_IN, null);
  fetch(new URL("session", location.href), { method: "DELETE", headers: HEADERS }).catch(() => {});
  loginView(message);
}

function loginView(message = "") {
  const user = h("input", { name: "user", value: "sonn", autocomplete: "username", required: true });
  const password = h("input", { name: "password", type: "password", autocomplete: "current-password", required: true });
  const error = h("div", { class: "error" }, message);
  const form = h("form", {
    class: "card",
    onsubmit: async (e) => {
      e.preventDefault();
      try {
        await signIn("Basic " + btoa(unescape(encodeURIComponent(`${user.value.trim()}:${password.value}`))));
        await start();
      } catch (err) {
        error.textContent = err instanceof AuthError ? WRONG_LOGIN : err.message === LOCKED_OUT ? LOCKED_OUT : `Server unreachable · ${err.message}`;
      }
    },
  }, h("label", {}, "User", user), h("label", {}, "Password", password), error, h("button", { class: "btn primary big", type: "submit" }, "Sign in"));
  document.getElementById("app").replaceChildren(h("div", { class: "login" },
    h("img", { class: "login-mark", src: "icon.svg", alt: "xrnrule", width: 64, height: 64 }),
    form));
  setTimeout(() => password.focus(), 0);
}

// ---- time ----------------------------------------------------------------------------------

const whenFmt = new Intl.DateTimeFormat(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

function ago(ms) {
  const m = Math.floor(ms / 60000);
  if (m < 1) return "Just now";
  if (m < 60) return `${m} min ago`;
  if (m < 48 * 60) return `${Math.floor(m / 60)} h ago`;
  return `${Math.floor(m / 1440)} days ago`;
}

// ---- Blocks --------------------------------------------------------------------------------

const TONE = { blocked: "blocked", open: "open", earned: "earned", idle: "idle" };

/** One thing a block asks for or holds to (the phone's HabitRow), [kind] its colour. */
function rrow({ ic, kind, label, value, fraction, note, dim, loop, done }) {
  return h("div", { class: `rrow k-${kind}${dim ? " dim" : ""}` },
    loop ? h("img", { class: "loop", src: "xrnloops.svg", alt: "xrnloops" }) : icon(ic),
    h("div", { class: "main" },
      h("div", { class: "top" }, h("span", { class: "lbl" }, label), value && h("span", { class: "val" }, value)),
      fraction != null && h("div", { class: "meter" }, h("i", { style: { width: `${Math.max(0, Math.min(100, fraction * 100))}%` } })),
      note && h("span", { class: "note" }, note)),
    h("div", { class: "acts" }, done != null && h("input", { type: "checkbox", class: "tick", checked: done, disabled: true, tabindex: -1, "aria-label": done ? "Done" : "Not done", style: loop ? { "--k": "var(--k-loop)" } : null })));
}

/** A block's card, as the phone's, to look at: how it stands, and a row per rule and thing it asks for. */
function blockCard(block, index, items, now) {
  const colour = PALETTE[index % PALETTE.length];
  const [cls, text] = blockStatus(doc, block, now);
  const { mode, daily, opens, row, hasLimit } = rulesOf(block);
  const rows = [];
  if (hasLimit && block.enabled !== false) {
    const use = useToday(doc, block, now);
    if (daily > 0) rows.push(rrow({ ic: "hourglass", kind: "limit", label: "Daily limit", value: `${minutes(use.ms)} / ${minutes(daily)} min`, fraction: use.ms / daily }));
    if (row) rows.push(rrow({ ic: "timer", kind: "limit", label: "Time in a row", value: `${minutes(block.sessionMs)} min · ${minutes(block.sessionBreakMs)} off` }));
    if (opens > 0) rows.push(rrow({ ic: "openNew", kind: "limit", label: "Opens today", value: `${use.opens} / ${opens}`, fraction: use.opens / opens }));
  }
  const hours = mode === "SCHEDULE" ? block.schedule : block.blockedHours;
  if (hours && (mode !== "SCHEDULE" || windows(hours).length || (hours.activeDays ?? []).length < 7)) {
    rows.push(rrow({ ic: "calendar", kind: "strict", label: "Blocked hours", note: windows(hours).join(", ") || "All day", dim: cls !== "blocked" }));
  }
  if (mode === "LOCKDOWN") rows.push(rrow({ ic: "lock", kind: "strict", label: `${minutes(block.lockdownMs ?? 0)} min lockdown`, dim: cls !== "blocked" }));
  if (mode === "HABIT") {
    const t = block.trigger ?? {};
    if (t.type !== "TIMER") {
      for (const i of items) {
        rows.push(i.habitId
          ? rrow({ loop: true, kind: "loop", label: i.text + (i.linkedDay === "YESTERDAY" ? " (yesterday)" : "") })
          : rrow({ ic: i.tag ? "nfc" : "task", kind: i.tag ? "blue" : "habit", label: i.text, dim: i.done, done: !!i.done }));
      }
    }
    if (t.type === "TIMER" || t.type === "COMBINED") rows.push(rrow({ ic: "timer", kind: "timer", label: "Timer", value: `${minutes(t.requiredAmount ?? 0)} min` }));
  }
  const sub = [
    block.devices === "PHONE" && h("span", {}, "Phone only"),
    block.devices === "LAPTOP" && h("span", {}, "Laptop only"),
    isLocked(block, now.getTime()) && h("span", { class: "locked" }, `Locked until ${new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short" }).format(block.lock.untilWall)}`),
    (block.enforcementTier ?? "SOFT") === "HARDCORE" && h("span", { class: "tag strict" }, "Hardcore"),
  ].filter(Boolean);
  const phone = block.targetPackages ?? [];
  const targets = [
    phone.length > 0 && h("span", { class: "target", title: "Phone apps" }, icon("phone"), `${phone.length} app${phone.length === 1 ? "" : "s"}`),
    ...(block.targetSites ?? []).map((x) => h("span", { class: "target" }, icon("globe"), x)),
    ...(block.exceptSites ?? []).map((x) => h("span", { class: "target except", title: "Left alone" }, x)),
    ...(block.targetDesktopApps ?? []).map((a) => h("span", { class: "target", title: "Laptop app" }, icon("laptop"), a)),
  ].filter(Boolean);
  return h("section", { class: `card bcard${block.enabled === false ? " off" : ""}` },
    h("div", { class: "head" },
      h("div", { class: "open" },
        h("div", { class: "tile letter", style: { "--c": colour, "--ink": inkOn(colour) } }, (block.name?.trim()[0] ?? "x").toUpperCase()),
        h("div", { class: "name" }, h("h3", {}, block.name), h("div", { class: `headline ${TONE[cls] ?? "idle"}` }, text), sub.length > 0 && h("div", { class: "sub" }, sub)))),
    rows.length > 0 && h("div", { class: "rows" }, rows),
    targets.length > 0 && h("div", { class: "targets" }, targets));
}

function stat(ic, cls, value, label) {
  return h("div", { class: `stat ${cls}` }, h("span", { class: "stat-icon" }, icon(ic)), h("b", {}, value), h("span", { class: "label" }, label));
}

function blocksView() {
  const now = new Date();
  const blocks = Object.values(doc.blocks ?? {});
  const items = Object.values(doc.items ?? {});
  const day = today(doc, now).epochDay;
  const usedMs = doc.unlockEpochDay === day ? Object.values(doc.unlockLog ?? {}).reduce((a, b) => a + b, 0) : 0;
  const cap = doc.settings?.dailyCapMs ?? 3600000;
  const pause = doc.pause;
  const onBreak = pause && now.getTime() < pause.startedAtWall + pause.durationMs;
  const breakText = onBreak ? `Until ${timeFmt.format(pause.startedAtWall + pause.durationMs)}` : doc.lastPauseEpochDay === day ? "Used" : "Ready";
  const statuses = blocks.map((b) => blockStatus(doc, b, now)[0]);
  const summary = [["blocked", "blocked"], ["earned", "ready"], ["open", "open"]].map(([c, w]) => [statuses.filter((x) => x === c).length, w]).filter(([n]) => n > 0).map(([n, w]) => `${n} ${w}`).join(" · ");
  const known = Object.entries(devices).sort(([a], [b]) => a.localeCompare(b));
  return h("div", {},
    h("div", { class: "page-title" }, h("h2", {}, "Blocks"), summary && h("span", { class: "summary" }, summary)),
    h("div", { class: "stats" },
      stat("lockOpen", "unlocked", `${minutes(usedMs)} / ${minutes(cap)} min`, "Unlocked today"),
      stat("coffee", "breaks", breakText, "Break"),
      known.map(([device, at]) => {
        const stale = now.getTime() - at > 60 * 60000;
        return stat(device === "phone" ? "phone" : device === "laptop" ? "laptop" : "devices", stale ? "stale" : "fresh", ago(now.getTime() - at), device === "phone" ? "Phone" : device === "laptop" ? "Laptop" : device);
      })),
    blocks.length ? h("div", { class: "blocks" }, blocks.map((b, i) => blockCard(b, i, items.filter((x) => x.blockId === b.id).sort((x, y) => x.order - y.order), now)))
      : h("div", { class: "card empty" }, "No blocks"),
    h("div", { class: "foot" }, updated.at ? `Updated ${whenFmt.format(new Date(updated.at))}${updated.by ? ` · ${updated.by}` : ""}` : "Not synced"));
}

// ---- Insights ------------------------------------------------------------------------------

let hist = null; // { events (merged), limits, at }
let historyLoading = false;
let insights = (() => {
  const kept = load(KEY_INSIGHTS) ?? {};
  return { range: kept.range ?? "week", device: kept.device ?? "all", block: null, historyDays: 7, openDays: {} };
})();
let insightsNode = null;
let drawnFrom = null;

async function loadHistory() {
  if (historyLoading) return;
  historyLoading = true;
  try {
    const since = Date.now() - 120 * DAY;
    const [ev, lim] = await Promise.all([
      fetch(new URL(`events?since=${since}`, location.href), { headers: HEADERS, cache: "no-store" }),
      fetch(new URL("limits", location.href), { headers: HEADERS, cache: "no-store" }),
    ]);
    check(ev);
    if (!ev.ok) throw new Error(`${ev.status}`);
    const events = (await ev.json()).events ?? [];
    const limits = lim.ok ? (await lim.json()).limits ?? {} : {}; // a server before /limits has none
    hist = { events: mergeShared(events, doc.emergencyLog), limits, at: Date.now() };
  } catch (e) {
    if (e instanceof AuthError) return signOut("Signed out");
    hist = hist ?? { events: [], limits: {}, at: Date.now(), failed: true };
  } finally {
    historyLoading = false;
  }
  render();
}

function setInsights(change) {
  insights = { ...insights, ...change };
  save(KEY_INSIGHTS, { range: insights.range, device: insights.device });
  drawInsights(true);
}

function drawInsights(force) {
  if (!insightsNode) insightsNode = h("div", { class: "insights" });
  if (!hist) {
    if (!insightsNode.firstChild) insightsNode.replaceChildren(h("p", { class: "boot" }, "Loading…"));
    return insightsNode;
  }
  const key = `${hist.at}|${etag}`;
  if (!force && drawnFrom === key) return insightsNode;
  drawnFrom = key;
  const input = {
    events: hist.events,
    blocks: normalizeBlocks(Object.values(doc.blocks ?? {})),
    limits: hist.limits,
    now: Date.now(),
    zone: sharedZone(doc) ?? Intl.DateTimeFormat().resolvedOptions().timeZone,
  };
  renderInsights(insightsNode, input, insights, {
    setRange: (range) => setInsights({ range, historyDays: 7 }),
    setDevice: (device) => setInsights({ device }),
    pickBlock: (block) => { setInsights({ block, historyDays: 7 }); window.scrollTo({ top: 0, behavior: "smooth" }); },
    more: () => setInsights({ historyDays: insights.historyDays + 7 }),
    toggleDay: (day, open) => setInsights({ openDays: { ...insights.openDays, [day]: open } }),
  });
  return insightsNode;
}

function insightsView() {
  if (!historyLoading && (!hist || Date.now() - hist.at > 60_000)) loadHistory();
  return drawInsights(false);
}

// ---- the page ------------------------------------------------------------------------------

let shell = null; // { bar, main }

function header() {
  return h("header", { class: "bar" }, h("div", { class: "wrap" },
    h("a", { class: "mark", href: "./", title: "xrnrule" }, h("img", { src: "icon.svg", alt: "xrnrule" })),
    h("nav", { class: "tabs" }, [["blocks", "Blocks", "shield"], ["insights", "Insights", "chart"]].map(([id, label, ic]) =>
      h("button", { type: "button", class: tab === id ? "on" : "", "aria-current": tab === id ? "page" : null, onclick: () => goTo(id) }, icon(ic), h("span", {}, label)))),
    h("span", { class: "spacer" }),
    h("span", { class: `sync${online ? "" : " off"}` }, icon(online ? "cloud" : "cloudOff"), h("span", {}, online ? "Live" : "Offline")),
    h("a", { class: "app-link", href: "../xrnloops/", title: "xrnloops" }, h("img", { src: "xrnloops.svg", alt: "xrnloops" })),
    h("button", { class: "round", type: "button", title: "Sign out", "aria-label": "Sign out", onclick: () => signOut("") }, icon("logout"))));
}

function goTo(id) {
  tab = id;
  const hash = id === "blocks" ? "" : `#${id}`;
  if (location.hash !== hash) window.history.replaceState(null, "", location.pathname + location.search + hash);
  window.scrollTo(0, 0);
  render();
}
window.addEventListener("hashchange", () => goTo(location.hash === "#insights" ? "insights" : "blocks"));

function render() {
  if (!doc || !signedIn) return;
  const app = document.getElementById("app");
  if (!shell) {
    shell = { bar: h("div"), main: h("main", { class: "wrap" }) };
    app.replaceChildren(shell.bar, shell.main);
  }
  shell.bar.replaceChildren(header());
  const view = tab === "insights" ? insightsView() : blocksView();
  if (shell.main.firstChild !== view || shell.main.childNodes.length !== 1) {
    const y = window.scrollY;
    shell.main.replaceChildren(view);
    window.scrollTo(0, y);
  }
}

// Statuses change with the clock alone (sessions end, schedules start): redraw each half minute.
setInterval(async () => {
  if (signedIn) await fetchDevices();
  render();
}, 30000);

async function boot() {
  // A password kept by an older version of these pages: traded for the cookie, then dropped.
  const old = load(KEY_OLD_AUTH);
  if (old) {
    try {
      await signIn(old);
      save(KEY_OLD_AUTH, null);
    } catch (e) {
      if (e instanceof AuthError) save(KEY_OLD_AUTH, null);
    }
  }
  if (!signedIn) return loginView();
  start().catch((e) => (e instanceof AuthError ? signOut("Signed out") : loginView(e.message === LOCKED_OUT ? LOCKED_OUT : `Server unreachable · ${e.message}`)));
}

boot();
