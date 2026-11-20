// xrnrule status page: a read-only view of the state the phone and the laptop share
// (docs/domain-contract.md §2.4), kept live with a long-poll, and Insights: both devices' event
// log and limit use as this server keeps them (/events, /limits). It shares its login with the
// xrnloops web app on the same server: a cookie either page's sign-in sets, which no script can
// read (xrnrule-sync.py, "/session"). The password itself is never kept. The look, the icons and
// Insights are shared with the laptop's local page (the desktop build copies them). The real icons
// of what blocks cover come from this server too (/icons, which the phone and the laptop fill).

import { blockStatus, minutes, timeFmt, today, sharedZone, rulesOf, useToday, isLocked, windows } from "./blocks.js";
import { h, icon, coverText, checkIn, daySpans, dayStrip, limitTone, row, tag, joinTag, statePill, cluster, blockLogos, iconSource } from "./ui.js";
import { normalizeBlocks, mergeShared, renderInsights, dayByBlock, dayLine, clockAt } from "./insights.js";

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
let todayLog = null; // { events (today's, merged), at }: each block's day so far, on its card
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

/** Today's events, for each block's figures on its card; asked again at most once a minute. */
async function fetchToday() {
  if (todayLog && Date.now() - todayLog.at < 60_000) return;
  const at = Date.now();
  try {
    const since = today(doc, new Date()).dayStart;
    const res = await fetch(new URL(`events?since=${since}`, location.href), { headers: HEADERS, cache: "no-store" });
    check(res);
    if (res.ok) todayLog = { events: mergeShared((await res.json()).events ?? [], doc.emergencyLog), at };
  } catch {
    /* the cards show without it; the next redraw tries again */
  }
}

/**
 * Which icons this server has (GET /icons: key -> hash), asked again at most once a minute: the
 * page asks only for those, so nothing it shows waits on a 404. Without an answer (a server from
 * before /icons) every logo is its kind's glyph.
 */
let iconsAt = 0;
async function fetchIcons() {
  if (Date.now() - iconsAt < 60_000) return;
  iconsAt = Date.now();
  try {
    const res = await fetch(new URL("icons", location.href), { headers: HEADERS, cache: "no-store" });
    check(res);
    iconSource({ known: res.ok ? Object.keys(await res.json()) : [] });
  } catch (e) {
    if (e instanceof AuthError) throw e;
    iconSource({ known: [] });
  }
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
  await Promise.all([fetchDevices(), fetchToday(), fetchIcons()]);
  shell = null;
  render();
  live(gen);
}

function signOut(message) {
  generation++;
  signedIn = false;
  shell = null;
  hist = null;
  todayLog = null;
  iconsAt = 0;
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

// ---- Blocks --------------------------------------------------------------------------------

const TONE = { blocked: "blocked", open: "open", earned: "earned", idle: "idle" };

/** A done ring, to look at: this page changes nothing. */
const doneRing = (done) => h("input", { type: "checkbox", class: "tick", checked: done, disabled: true, tabindex: -1, "aria-label": done ? "Done" : "Not done" });

/**
 * A block's card, as Habits First's and the phone's, to look at: the icons of what it covers and
 * its name, how it stands in grey words after a coloured dot, its day so far; at the right, its
 * state where Habits First has its switch (this page changes nothing, and a block is never simply
 * switched off: I-numbers in docs/domain-contract.md); then its rules, "DO ALL" and a row per
 * thing it asks for. What it covers in full is the icons' title.
 */
function blockCard(block, items, now, day) {
  const [cls, text] = blockStatus(doc, block, now);
  const { mode, daily, opens, row: inRow, hasLimit } = rulesOf(block);
  const on = block.enabled !== false;
  const rows = [];
  if (hasLimit && on) {
    const use = useToday(doc, block, now);
    if (daily > 0) rows.push(row({ ic: "hourglass", label: "Daily limit", value: `${minutes(use.ms)} / ${minutes(daily)} min`, fraction: use.ms / daily, tone: limitTone(use.ms / daily) }));
    if (inRow) rows.push(row({ ic: "timer", label: "Time in a row", value: `${minutes(block.sessionMs)} min · ${minutes(block.sessionBreakMs)} off`, tone: "plain" }));
    if (opens > 0) rows.push(row({ ic: "lockOpen", label: "Opens today", value: `${use.opens} / ${opens}`, fraction: use.opens / opens, tone: limitTone(use.opens / opens) }));
  }
  const hours = mode === "SCHEDULE" ? block.schedule : block.blockedHours;
  if (hours && (mode !== "SCHEDULE" || windows(hours).length || (hours.activeDays ?? []).length < 7)) {
    const clock = clockAt(sharedZone(doc), now.getTime());
    const blockedNow = cls === "blocked" && /hours|schedule/.test(text);
    rows.push(row({
      ic: "calendar", label: "Blocked hours", value: windows(hours).join(", ") || "All day", dim: cls !== "blocked", tone: "plain",
      strip: dayStrip(daySpans(hours, clock.iso), clock.minute, blockedNow),
    }));
  }
  if (mode === "LOCKDOWN") rows.push(row({ ic: "lock", label: `${minutes(block.lockdownMs ?? 0)} min lockdown`, dim: cls !== "blocked", tone: "plain" }));
  if (mode === "HABIT" && on) {
    const t = block.trigger ?? {};
    const parts = [];
    // A linked habit's progress is the habit server's, not in the shared state: its name only.
    if (t.type !== "TIMER") {
      for (const i of items) {
        parts.push(i.habitId
          ? row({ ic: "task", label: i.text + (i.linkedDay === "YESTERDAY" ? " (yesterday)" : "") })
          : row({ ic: i.tag ? "nfc" : "task", tone: "plain", label: i.text, dim: i.done, action: doneRing(!!i.done) }));
      }
    }
    if (t.type === "TIMER" || t.type === "COMBINED") parts.push(row({ ic: "timer", label: "Timer", value: `${minutes(t.requiredAmount ?? 0)} min` }));
    const how = { trigger: t.type, requiredItems: t.type === "CHECKLIST" ? t.requiredAmount ?? -1 : -1, combineOr: t.combineMode === "OR" };
    rows.push(tag(joinTag(how, parts.length)), ...parts);
  }
  const sub = [
    day && dayLine(day),
    block.devices === "PHONE" && "Phone only",
    block.devices === "LAPTOP" && "Laptop only",
    isLocked(block, now.getTime()) && `Locked until ${new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short" }).format(block.lock.untilWall)}`,
    (block.enforcementTier ?? "SOFT") === "HARDCORE" && "Hardcore",
  ].filter(Boolean);
  const covered = coverText({ sites: block.targetSites ?? [], phoneApps: (block.targetPackages ?? []).length, laptopApps: block.targetDesktopApps ?? [], except: block.exceptSites ?? [] });
  const tone = TONE[cls] ?? "idle";
  return h("section", { class: `card bcard${on ? "" : " off"}` },
    h("div", { class: "head" },
      cluster(blockLogos(block), 46, covered),
      h("div", { class: "name" }, h("h3", {}, h("span", {}, block.name)), h("div", { class: `headline ${tone}` }, text), sub.length > 0 && h("div", { class: "sub" }, sub.join(" · "))),
      h("div", { class: "side" }, statePill(tone, text))),
    rows.filter(Boolean).length > 0 && h("div", { class: "rows" }, rows));
}

/** A figure at the right of the page's title, as the laptop's page has it. */
const figure = (value, label) => h("div", { class: "figure" }, h("b", {}, value), h("span", {}, label));

const DEVICE_NAME = { phone: "Phone", laptop: "Laptop" };

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
  // When each device last checked in, beside the title: green while fresh, amber after half an hour.
  const seen = Object.entries(devices).sort(([a], [b]) => a.localeCompare(b)).map(([device, at]) => checkIn(DEVICE_NAME[device] ?? device, now.getTime() - at));
  const days = todayLog ? dayByBlock({ events: todayLog.events, blocks: normalizeBlocks(blocks), now: now.getTime(), zone: sharedZone(doc) }) : null;
  return h("div", {},
    h("div", { class: "page-title" },
      h("h2", {}, "Blocks"), summary && h("span", { class: "summary" }, summary),
      seen.length > 0 && h("span", { class: "checkins" }, seen),
      h("span", { class: "spacer" }),
      h("div", { class: "today" }, figure(`${minutes(usedMs)} / ${minutes(cap)} min`, "Unlocked today"), figure(breakText, "Break"))),
    blocks.length ? h("div", { class: "blocks" }, blocks.map((b) => blockCard(b, items.filter((x) => x.blockId === b.id).sort((x, y) => x.order - y.order), now, days?.get(b.id))))
      : h("div", { class: "card empty" }, "No blocks"));
}

// ---- Insights ------------------------------------------------------------------------------

let hist = null; // { events (merged), limits, at }
let historyLoading = false;
let insights = (() => {
  const kept = load(KEY_INSIGHTS) ?? {};
  return { range: kept.range ?? "week", device: kept.device ?? "all", block: null, historyDays: 7, openDays: {}, topAll: false };
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
    // Today's limit use, for a limit the server has kept no day of.
    usedToday: Object.fromEntries(Object.values(doc.blocks ?? {}).filter((b) => (b.dailyAllowanceMs ?? 0) > 0)
      .map((b) => [b.id, useToday(doc, b, new Date()).ms])),
  };
  renderInsights(insightsNode, input, insights, {
    setRange: (range) => setInsights({ range, historyDays: 7, topAll: false }),
    setDevice: (device) => setInsights({ device }),
    pickBlock: (block) => { setInsights({ block, historyDays: 7, topAll: false }); window.scrollTo({ top: 0, behavior: "smooth" }); },
    more: () => setInsights({ historyDays: insights.historyDays + 7 }),
    moreTop: () => setInsights({ topAll: true }),
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
    h("span", { class: `sync${online ? "" : " off"}`, title: updated.at ? `Updated ${whenFmt.format(new Date(updated.at))}${updated.by ? ` · ${updated.by}` : ""}` : "Not synced" },
      !online && icon("cloudOff"), h("span", {}, online ? "Live" : "Offline")),
    h("a", { class: "app-link", href: "../xrnloops/", title: "xrnloops" }, h("img", { src: "xrnloops.svg", alt: "xrnloops" })),
    h("button", { class: "icon-btn", type: "button", title: "Sign out", "aria-label": "Sign out", onclick: () => signOut("") }, icon("logout"))));
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
  if (signedIn && doc) await Promise.all([fetchDevices(), fetchToday(), fetchIcons().catch(() => {})]);
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
