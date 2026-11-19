// xrnrule on the laptop: the local page ("/": Blocks, Insights, Settings, as the phone's tabs)
// and the block page ("/block?app=…" or "/block?site=…&back=…") that blocked apps and sites land
// on. Everything comes from the daemon's /api/state, polled every second; the engine behind it
// is the phone's. Insights reads /api/history (both devices' log, History.kt). The look, the
// icons and Insights are shared with the sync server's status page (server/web, copied in).
//
// The page redraws every second, but only what has no text field in it: the block form and the
// settings you edit are built once and kept, so typing never loses its place.

import { h, s, icon, duration, coverText, savedTheme, saveTheme, applyTheme } from "./ui.js";
import { normalizeBlocks, mergeShared, renderInsights } from "./insights.js";

const params = new URLSearchParams(location.search);
const onBlockPage = location.pathname === "/block";
const blockedApp = params.get("app");
const blockedSite = params.get("site");
// The page a blocked tab left: the extension puts it in the hash (raw), older links in ?back=.
const backUrl = location.hash.length > 1 ? location.hash.slice(1) : params.get("back");

const THEME_KEY = "xrnrule.theme";
const INSIGHTS_KEY = "xrnrule.insights";
applyTheme(savedTheme(THEME_KEY));

let state = null;
let apps = null; // installed desktop apps, loaded when the form opens
let habits = null; // [{uuid, name}] to link tasks to, or {error}; loaded when the form opens
let form = null; // the block form: { blockId (null: a new block), data, apps: Set, sitesText, filter, error }
let formNode = null; // the form's page, kept while it is open, so typing survives the once-a-second refresh
let guard = null; // { token, until, waitMs, label, close }: a loosening waiting out its 60 s
let message = "";
let failed = false;
let phrase = ""; // the override sentence as typed so far, kept across redraws
let clockOffset = 0; // the daemon's wall clock less this browser's
let tab = onBlockPage ? "blocks" : tabOf(location.hash);

function tabOf(hash) {
  return hash === "#insights" ? "insights" : hash === "#settings" ? "settings" : "blocks";
}

// ---- data ----------------------------------------------------------------------------------

async function post(path, body) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Xrnrule": "1" },
    body: JSON.stringify(body),
    keepalive: true,
  });
  return res.json().catch(() => ({}));
}

/**
 * The page redraws every second, and a button redrawn under a held press never gets its click: the
 * browser sees the press and the release land on two different elements. So no redraw during a press.
 */
let pressing = false;
window.addEventListener("pointerdown", () => (pressing = true), true);
for (const end of ["pointerup", "pointercancel"]) {
  window.addEventListener(end, () => setTimeout(() => (pressing = false)), true); // after the click it causes
}

async function refresh() {
  try {
    const subject = onBlockPage ? `?${new URLSearchParams(blockedApp ? { app: blockedApp } : { site: blockedSite ?? "" })}` : "";
    const res = await fetch(`/api/state${subject}`, { cache: "no-store" });
    state = await res.json();
    clockOffset = state.nowWall - Date.now();
    failed = false;
  } catch {
    failed = true;
  }
  render();
}

async function act(body) {
  const result = await post("/api/event", body);
  if (result.token) startGuard(result.token, result.waitMs, "Starting the break");
  else if (result.error) message = result.error;
  refresh();
}

function startGuard(token, waitMs, label, close = true) {
  guard = { token, until: Date.now() + waitMs, waitMs, label, close };
  render();
}

async function tickGuard() {
  if (!guard || Date.now() < guard.until) return;
  const { token, close } = guard;
  guard = null;
  showWait();
  const result = await post("/api/confirm", { token });
  message = result.error ?? "";
  if (!result.error && close) closeForm();
  refresh();
}

// Leaving the page cancels a wait, as leaving the screen does on the phone (contract §2.3).
function cancelGuard() {
  if (!guard) return;
  post("/api/confirm", { token: guard.token, cancel: true });
  guard = null;
  message = "Wait cancelled";
  showWait();
}
document.addEventListener("visibilitychange", () => document.visibilityState === "hidden" && cancelGuard());
window.addEventListener("pagehide", cancelGuard);

// The block page stands in for the blocked app or site while you wait on it (I4).
function heartbeat() {
  if (!onBlockPage) return;
  post("/api/heartbeat", { app: blockedApp, host: blockedSite, focused: document.hasFocus() && document.visibilityState === "visible" }).catch(() => {});
}

// ---- helpers -------------------------------------------------------------------------------

// A website's own icon, served by the daemon (which asks the site once and keeps it). The page
// redraws every second: one that isn't there yet is asked for again after 30 s, not every time.
const iconMissing = new Map();
function siteIcon(site) {
  const host = site.split("/")[0]; // a target can carry a path
  const since = iconMissing.get(host);
  if (since && Date.now() - since < 30_000) return null;
  return h("img", {
    class: "favicon", alt: "", src: `/api/site-icon?host=${encodeURIComponent(host)}`,
    onerror: (e) => { iconMissing.set(host, Date.now()); e.target.remove(); },
  });
}

const time = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const lockEnd = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short" });
const min = (ms) => Math.max(0, Math.round(ms / 60000));
const clock = (ms) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}` : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const siteMatches = (location, target) => {
  const host = location.split("/")[0];
  const targetHost = target.split("/")[0];
  if (host !== targetHost && !host.endsWith("." + targetHost)) return false;
  const want = target.slice(targetHost.length);
  const path = location.slice(host.length);
  return !want || path === want || path.startsWith(want + "/");
};
/**
 * Whether [back], the address a blocked tab left, is on [site]'s own host or one under it: only
 * then does the page go back to it once the site opens. Any page can open /block with an address
 * after the "#", and would otherwise send a visitor through xrnrule's own page wherever it liked.
 */
const backHere = (back, site) => {
  if (!back || !site || !/^https?:/i.test(back)) return false;
  let host;
  try {
    host = new URL(back).hostname.toLowerCase().replace(/^www\./, "");
  } catch {
    return false;
  }
  return siteMatches(host, site.split("/")[0].toLowerCase());
};
const siteBlocked = (host) => state.blockedSites.some((d) => siteMatches(host, d) && !(state.siteExceptions[d] ?? []).some((e) => siteMatches(host, e)));
const appName = (id) => state?.appNames?.[id] ?? apps?.find((a) => a.id === id)?.name ?? id;

const initial = (name) => (String(name ?? "").trim()[0] ?? "x").toUpperCase();

/** The engine's words for how a block stands: the same sentence as the phone's card. */
const TONE = { BLOCKED: "blocked", READY: "earned", OPEN: "open", OFF: "idle" };

/** Loop's entry values (contract §1). */
const YES_MANUAL = 2, YES_AUTO = 1, NO = 0;

function limitHow(b) {
  const parts = [];
  if (b.allowanceMs > 0) parts.push(`${min(b.allowanceMs)} min a day`);
  if (b.rowMs > 0) parts.push(`at most ${min(b.rowMs)} min in a row, then ${min(b.rowBreakMs)} min off`);
  if (b.opensPerDay > 0) parts.push(`${b.opensUsed} of ${b.opensPerDay} opens used today`);
  if (b.openWaitS > 0) parts.push(`a ${b.openWaitS} s pause before each open`);
  const limit = `${parts.join(", ")}${parts.length ? "; " : ""}${b.perDevice ? "each device has its own" : "phone and laptop share it"}.`;
  if (b.mode !== "HABIT") return limit;
  return `${limit} ${b.limitFirst ? "Limit first." : "Habit first."}`;
}

function habitHow(b) {
  const unlock = b.selfPaced ? `opens for ${min(b.unlockMs)} min of use over the day` : `opens for ${min(b.unlockMs)} min`;
  const items = b.items.length;
  if (b.trigger === "TIMER") return `Wait ${min(b.requiredMs)} min on the block page, then it ${unlock}.`;
  const need = b.requiredItems === -1 ? (items === 1 ? "the item" : `all ${items} items`) : `${b.requiredItems} of ${items} items`;
  if (b.trigger === "CHECKLIST") return `Do ${need}, then it ${unlock}.`;
  return `Wait ${min(b.requiredMs)} min ${b.combineOr ? "or" : "and"} do every item, then it ${unlock}.`;
}

/** A block's rules in words; it can have several (I44). */
function how(b) {
  const rules = [
    b.mode === "HABIT" && habitHow(b),
    b.hasLimit && limitHow(b),
    b.mode !== "SCHEDULE" && b.hoursText && `Blocked outright: ${b.hoursText}.`,
  ].filter(Boolean);
  return rules.length ? rules.join(" ") : null;
}

/** "Do both", "Do all", "Do 2 of 3": how a habit block's rows add up, or null for one row. */
function joinTag(b, parts) {
  if (parts < 2) return null;
  if (b.trigger === "COMBINED") {
    if (b.combineOr) return parts === 2 ? "Do either" : "Timer or all tasks";
    return parts === 2 ? "Do both" : "Do all";
  }
  if (b.trigger === "CHECKLIST") {
    if (b.requiredItems === -1 || b.requiredItems >= parts) return parts === 2 ? "Do both" : "Do all";
    return `Do ${b.requiredItems} of ${parts}`;
  }
  return null;
}

// ---- the wait (contract §2.3): a ring counting down over the dimmed page ---------------------
// Its length and its rules are the daemon's: 60 s, confirmed once over, cancelled by leaving.

const RING_R = 80;
const RING_C = 2 * Math.PI * RING_R;

/** The ring and the seconds in it. */
function ring() {
  const arc = s("circle", { class: "arc", cx: 92, cy: 92, r: RING_R, "stroke-dasharray": RING_C, "stroke-dashoffset": 0 });
  const num = h("b", {}, "");
  const node = h("div", { class: "ring" },
    s("svg", { viewBox: "0 0 184 184", "aria-hidden": "true" }, s("circle", { class: "track", cx: 92, cy: 92, r: RING_R }), arc),
    h("div", { class: "ring-num" }, num, h("span", {}, "s")));
  return {
    node,
    set(left, total) {
      const frac = total > 0 ? Math.max(0, Math.min(1, left / total)) : 0;
      arc.setAttribute("stroke-dashoffset", String(RING_C * (1 - frac)));
      const text = String(Math.ceil(Math.max(0, left) / 1000));
      if (num.textContent !== text) num.textContent = text;
      node.classList.toggle("done", left <= 0);
    },
  };
}

let waitView = null; // { node, update, key }
let waitFrame = 0;

/** Shows, updates or takes down the wait: a loosening's (guard), or minutes being bought. */
function showWait() {
  const sub = state?.subject;
  const buying = onBlockPage && sub?.overrideReadyAtWall && !sub.overrideRejection;
  const want = guard ? `guard:${guard.token}` : buying ? `buy:${sub.overrideReadyAtWall}` : null;
  if (!want) {
    waitView?.node.remove();
    waitView = null;
    cancelAnimationFrame(waitFrame);
    return;
  }
  if (waitView?.key !== want) {
    waitView?.node.remove();
    waitView = guard ? guardModal() : buyModal();
    waitView.key = want;
    document.body.append(waitView.node);
    waitView.node.querySelector("input, .btn")?.focus();
  }
  cancelAnimationFrame(waitFrame);
  const step = () => {
    if (!waitView) return;
    waitView.update();
    waitFrame = requestAnimationFrame(step);
  };
  step();
}

function guardModal() {
  const r = ring();
  const cancel = () => { cancelGuard(); render(); };
  const node = h("div", { class: "scrim", role: "dialog", "aria-modal": "true", "aria-label": guard.label, onkeydown: (e) => e.key === "Escape" && cancel() },
    h("div", { class: "modal" },
      r.node,
      h("p", { class: "what" }, guard.label),
      h("button", { class: "btn outline", type: "button", onclick: cancel }, "Cancel")));
  return { node, update: () => guard && r.set(guard.until - Date.now(), guard.waitMs) };
}

// ---- buying minutes (contract I34) ---------------------------------------------------------
// The daemon owns the wait and checks the sentence; this page only shows them.

function subjectBody() {
  return blockedApp ? { app: blockedApp } : { site: blockedSite };
}

async function buy(op, extra = {}) {
  const result = await post("/api/override", { op, ...subjectBody(), ...extra });
  message = result.error ?? "";
  if (op === "claim" && !result.error) phrase = "";
  refresh();
}

function buyModal() {
  const r = ring();
  const minutes = min(state.settings.grantMs);
  const claim = () => buy("claim", { phrase });
  const button = h("button", { class: "btn primary", type: "button", onclick: claim }, `Open for ${minutes} min`);
  const error = h("p", { class: "phrase-line bad" });
  const node = h("div", { class: "scrim", role: "dialog", "aria-modal": "true", "aria-label": `${minutes} more minutes`, onkeydown: (e) => e.key === "Escape" && buy("cancel") },
    h("div", { class: "modal" },
      r.node,
      h("p", { class: "what" }, `${blockedApp ? appName(blockedApp) : blockedSite} · ${minutes} min`),
      h("p", { class: "phrase-line" }, "Type ", h("b", {}, `“${state.settings.phrase}”`)),
      h("input", {
        type: "text", class: "phrase", value: phrase, placeholder: state.settings.phrase, autocomplete: "off", spellcheck: false,
        oninput: (e) => (phrase = e.target.value), onkeydown: (e) => e.key === "Enter" && claim(),
      }),
      error,
      h("div", { class: "actions" },
        button,
        h("button", { class: "btn outline", type: "button", onclick: () => buy("cancel") }, "Cancel"))));
  return {
    node,
    update: () => {
      const sub = state?.subject;
      if (!sub?.overrideReadyAtWall) return;
      const left = sub.overrideReadyAtWall - (Date.now() + clockOffset);
      r.set(left, state.settings.waitMs);
      button.disabled = left > 0;
      if (error.textContent !== message) error.textContent = message;
    },
  };
}

// ---- Blocks --------------------------------------------------------------------------------

/** Off the block page: back where you came from; with nowhere to go, the tab closes or, failing that, all blocks. */
let leaving = null;
function leaveBlockPage() {
  if (history.length > 1) history.back();
  else window.close();
  leaving = setTimeout(() => location.replace("/"), 1200);
}
window.addEventListener("pagehide", () => clearTimeout(leaving));

/** A block held by time, on the block page: nothing to do here, so only its rules, in a line. */
const ruleLine = (b) => h("p", { class: "rule" }, h("b", {}, b.name), `: ${how(b) ?? b.hoursText ?? b.headline.text}`);

/**
 * One thing a block asks for or holds to (the phone's HabitRow): its words, its figure, a meter
 * for what it has used, and what you can do about it. No icon or colour of its own: only a habit
 * linked to xrnloops carries xrnloops' tile, and a meter the one data colour.
 */
function rrow({ label, value, fraction, note, dim, action, loop }) {
  return h("div", { class: `rrow${dim ? " dim" : ""}` },
    h("div", { class: "main" },
      h("div", { class: "top" },
        loop && h("img", { class: "loop", src: "/xrnloops.svg", alt: "xrnloops" }),
        h("span", { class: "lbl" }, label), value && h("span", { class: "val" }, value)),
      fraction != null && h("div", { class: "meter" }, h("i", { style: { width: `${Math.max(0, Math.min(100, fraction * 100))}%` } })),
      note && h("span", { class: "note" }, note)),
    h("div", { class: "acts" }, action));
}

/** A round tick, the phone's row action. */
const tick = (key, checked, disabled, onchange) =>
  h("input", { type: "checkbox", class: "tick", "data-key": key, checked, disabled, onchange, "aria-label": "Done" });

/** A round button at the end of a row: an icon, or a short text ("+1"). */
const ract = (key, content, title, onclick, cls = "") =>
  h("button", { type: "button", class: `ract ${cls}`.trim(), "data-key": key, title, "aria-label": title, onclick }, cls.includes("text") ? content : icon(content));

/** A block's limit and its blocked hours, on any kind of block that has them (I44). */
function ruleRows(b) {
  const rows = [];
  if (b.hasLimit) {
    if (b.allowanceMs > 0) {
      rows.push(rrow({ label: b.perDevice ? "Daily limit, this laptop" : "Daily limit", value: `${min(b.allowanceUsedMs)} / ${min(b.allowanceMs)} min`, fraction: b.allowanceUsedMs / b.allowanceMs }));
    }
    if (b.rowMs > 0) {
      rows.push(rrow({
        label: "Time in a row", value: `${min(b.rowUsedMs)} / ${min(b.rowMs)} min`, fraction: b.rowUsedMs / b.rowMs,
        note: b.rowBreakRemainingMs > 0 ? `Break · ${clock(b.rowBreakRemainingMs)}` : null,
      }));
    }
    if (b.opensPerDay > 0) {
      rows.push(rrow({ label: b.perDevice ? "Opens today, this laptop" : "Opens today", value: `${b.opensUsed} / ${b.opensPerDay}`, fraction: b.opensUsed / b.opensPerDay }));
    }
  }
  if (b.mode !== "SCHEDULE" && b.hoursText) rows.push(rrow({ label: "Blocked hours", note: b.hoursText, dim: b.reason !== "HOURS" }));
  return rows;
}

/** A habit (contract I23, I36): tick it (on the item's day), add to it, or run its focus timer. Amounts are thousandths of the unit. */
function habitRow(x) {
  const units = (n) => +(n / 1000).toFixed(3);
  const unit = x.unit && x.kind !== "TIMER" ? ` ${x.unit}` : "";
  const label = x.name + (x.yesterday ? " (yesterday)" : "");
  if (x.kind === "CHECK") {
    const ticked = x.dayValue === YES_MANUAL;
    return rrow({
      loop: true, label,
      value: x.unlock === null ? "Never opens" : x.spent > 0 && x.amount < 1 ? "Used" : null,
      action: tick(`habit:${x.id}`, ticked || x.dayValue === YES_AUTO, false, () => act({ type: "set", habitId: x.id, day: x.day, value: ticked ? NO : YES_MANUAL })),
    });
  }
  const toward = x.unlock === null ? "Never opens"
    : x.kind === "TIMER" ? `${Math.floor(Math.min(x.amount, x.unlock) / 1000)} / ${units(x.unlock)} min` : `${units(Math.min(x.amount, x.unlock))} / ${units(x.unlock)}${unit}`;
  const actions = [];
  if (x.kind === "COUNT" && !x.yesterday) {
    if (x.dayValue > 0) actions.push(ract(`minus:${x.id}`, "−1", "Less one", () => act({ type: "log", habitId: x.id, amount: -1000 }), "text"));
    actions.push(ract(`plus:${x.id}`, "+1", "Add one", () => act({ type: "log", habitId: x.id, amount: 1000 }), "text"));
  }
  if (x.kind === "TIMER" && !x.yesterday) {
    actions.push(ract(`focus:${x.id}`, x.focusing ? "pause" : "play", x.focusing ? "Pause" : x.onBreak ? "Resume" : "Focus", () => act({ type: x.focusing ? "focusPause" : "focus", habitId: x.id })));
  }
  if (x.kind === "TIMER" && (x.focusing || x.onBreak)) actions.push(ract(`end:${x.id}`, "check", "End", () => act({ type: "focusEnd" }), "ring"));
  return rrow({ loop: true, label, value: toward, fraction: x.unlock ? Math.min(1, x.amount / Math.max(1, x.unlock)) : null, action: actions });
}

/** A habit block's body, as the phone's: its open pill when earned, then a row per thing it asks for. */
function habitBody(b) {
  if (b.state === "UNLOCKED") return []; // the headline says how long
  const out = [];
  if (b.state === "EARNED") {
    const capped = b.redeemRejection === "CAP_REACHED";
    const held = b.redeemRejection === "HELD"; // I44: opening now would open nothing
    if (capped) out.push(h("div", { class: "pill" }, `${min(state.dailyCapMs)} min used today`));
    else if (held) out.push(h("div", { class: "pill" }, "Done · opens once it's no longer blocked"));
    else out.push(h("button", { class: "pill go", type: "button", "data-key": `redeem:${b.id}`, onclick: () => act({ type: "redeem", blockId: b.id }) }, `Open for ${min(b.unlockMs)} min`));
  }
  const armed = b.state === "ARMED";
  const rows = b.items.map((i) => {
    if (i.habit) return habitRow(i.habit);
    // An NFC tag (I43): only the phone ticks it, by reading the tag; unticking is always fine.
    if (i.tag) {
      return rrow({
        label: i.text, dim: i.done, value: i.done ? "Scanned" : "On the phone",
        action: tick(`item:${i.id}`, i.done, !(armed && i.done), (e) => act({ type: "toggle", itemId: i.id, done: e.target.checked })),
      });
    }
    return rrow({ label: i.text, dim: i.done, action: tick(`item:${i.id}`, i.done, !armed, (e) => act({ type: "toggle", itemId: i.id, done: e.target.checked })) });
  });
  if (b.trigger === "TIMER" || b.trigger === "COMBINED") {
    rows.push(rrow({
      label: "Timer", value: `${clock(Math.min(b.timerMs, b.requiredMs))} / ${clock(b.requiredMs)}`,
      fraction: b.timerMs / Math.max(1, b.requiredMs),
      action: armed && b.timerMs < b.requiredMs && ract(`timer:${b.id}`, b.timerRunning ? "pause" : "play", b.timerRunning ? "Pause" : "Start", () => act({ type: "timer", blockId: b.id, running: !b.timerRunning })),
    }));
  }
  const tag = joinTag(b, rows.length);
  if (tag) out.push(h("span", { class: "jointag" }, tag));
  if (!rows.length) out.push(h("span", { class: "label" }, "Nothing unlocks it yet"));
  out.push(...rows);
  return out;
}

/** Daily limit, schedule, lockdown: no habit to do, so the card says where the rule stands. */
function strictBody(b) {
  if (b.mode === "ALLOWANCE") return ruleRows(b);
  if (b.mode === "SCHEDULE") return b.scheduleText ? [rrow({ label: "Blocked hours", note: b.scheduleText, dim: !b.blocking })] : [];
  if (b.mode === "LOCKDOWN") {
    if (b.lockdownRemainingMs > 0) {
      return [rrow({ label: "Locked down", value: `${clock(b.lockdownRemainingMs)} left`, fraction: 1 - b.lockdownRemainingMs / Math.max(1, b.lockdownMs) })];
    }
    // The headline says it is over: only the way to start it again.
    return [
      b.enabled && h("button", { class: "pill outline", type: "button", "data-key": `lockdown:${b.id}`, onclick: () => act({ type: "lockdown", blockId: b.id }) }, icon("lock"), `Block again for ${duration(b.lockdownMs)}`),
    ].filter(Boolean);
  }
  return [];
}

/** What a block covers, in one quiet line; the block page is about the laptop, so no phone apps there. */
function targets(b) {
  const text = coverText([...b.shownSites, ...b.desktopApps.map(appName)], onBlockPage ? 0 : b.phoneApps, b.exceptSites);
  return text ? h("p", { class: "targets" }, text) : null;
}

function blockCard(b) {
  const cls = TONE[b.headline.tone] ?? "idle";
  const editable = !onBlockPage;
  const sub = [
    b.devices === "PHONE" && "Phone only",
    b.devices === "LAPTOP" && "Laptop only",
    b.lockedUntilWall && `Locked until ${lockEnd.format(b.lockedUntilWall)}`,
    b.hardcore && "Hardcore",
  ].filter(Boolean);
  const body = !b.enabled ? [] : b.mode === "HABIT" ? [...ruleRows(b), ...habitBody(b)] : strictBody(b);
  const head = [h("div", { class: "name" }, h("h3", {}, b.name), h("div", { class: `headline ${cls}` }, b.headline.text), sub.length > 0 && h("div", { class: "sub" }, sub.join(" · ")))];
  return h("section", { class: `card bcard${b.enabled ? "" : " off"}`, "data-block": b.id },
    h("div", { class: "head" },
      editable ? h("button", { class: "open", type: "button", "data-key": `open:${b.id}`, title: "Edit", onclick: () => openForm(b) }, head) : h("div", { class: "open" }, head),
      editable && h("button", { class: "icon-btn", type: "button", "data-key": `edit:${b.id}`, title: "Edit", "aria-label": `Edit ${b.name}`, onclick: () => openForm(b) }, icon("edit"))),
    body.length > 0 && h("div", { class: "rows" }, body),
    targets(b));
}

// ---- the block form ------------------------------------------------------------------------
// The phone's create screen, on the laptop (BlockForms.kt has the rules). A new block targets
// laptop apps and websites; phone apps are added on the phone. An edit keeps the block's kind
// and unlock method; anything that loosens it waits 60 s with the page open.

const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const hhmm = (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
// Minutes past midnight from "22:00", "7:30" or "0730"; null for anything else, a half-typed time included.
const minutesOf = (text) => {
  const m = /^(\d{1,2}):?(\d{2})$/.exec(text.trim());
  return !m || +m[1] > 23 || +m[2] > 59 ? null : +m[1] * 60 + +m[2];
};

async function openForm(b) {
  cancelGuard();
  message = "";
  const hours = { days: [1, 2, 3, 4, 5, 6, 7], startMin: 540, endMin: 1020, moreWindows: [] };
  const base = { limitOn: false, habitFirst: false, hoursOn: false, hoursAllDay: false, hours, checklist: true, timer: false, combineOr: false, timerMin: 30, unlockMin: 5, selfPaced: true, devices: "BOTH", dailyOn: true, allowanceMin: 30, rowOn: false, rowMin: 15, rowBreakMin: 15, opensOn: false, opensPerDay: 3, openWaitOn: false, openWaitS: 5, perDevice: false, lockdownMin: 60, days: [1, 2, 3, 4, 5], startMin: 540, endMin: 1020, moreWindows: [] };
  form = b
    ? {
      blockId: b.id,
      data: {
        ...base,
        name: b.name,
        mode: b.mode,
        timerMin: min(b.requiredMs) || 30,
        combineOr: b.combineOr,
        unlockMin: min(b.unlockMs),
        selfPaced: b.selfPaced,
        devices: b.devices,
        dailyOn: !b.hasLimit || b.allowanceMs > 0,
        limitOn: b.hasLimit,
        habitFirst: b.habitFirst,
        hoursOn: b.hoursDays != null,
        hoursAllDay: b.hoursDays != null && b.hoursStart == null,
        hours: {
          days: b.hoursDays ?? hours.days,
          startMin: b.hoursStart ?? hours.startMin,
          endMin: b.hoursEnd ?? hours.endMin,
          moreWindows: b.hoursMore.map((w) => ({ ...w })),
        },
        allowanceMin: min(b.allowanceMs) || 30,
        rowOn: b.rowMs > 0,
        rowMin: min(b.rowMs) || 15,
        rowBreakMin: min(b.rowBreakMs) || 15,
        opensOn: b.opensPerDay > 0,
        opensPerDay: b.opensPerDay || 3,
        openWaitOn: b.openWaitS > 0,
        openWaitS: b.openWaitS || 5,
        perDevice: b.perDevice,
        lockdownMin: min(b.lockdownMs) || 60,
        days: b.scheduleDays.length ? b.scheduleDays : base.days,
        startMin: b.scheduleStart ?? base.startMin,
        endMin: b.scheduleEnd ?? base.endMin,
        moreWindows: b.moreWindows.map((w) => ({ ...w })),
        newTask: "",
      },
      apps: new Set(b.desktopApps), sitesText: b.sites.join("\n"), exceptText: b.exceptSites.join("\n"), filter: "", error: "",
    }
    : { blockId: null, data: { ...base, name: "", kind: "HABIT", setTime: "SCHEDULE", itemsText: "", linked: new Map() }, apps: new Set(), sitesText: "", exceptText: "", filter: "", error: "" };
  formNode = null;
  window.scrollTo(0, 0);
  render();
  const loads = [];
  if (!apps) loads.push(fetch("/api/apps").then((r) => r.json()).then((list) => (apps = list)));
  if (!habits && state?.syncConfigured) loads.push(fetch("/api/habits").then((r) => r.json()).then((list) => (habits = list)).catch(() => (habits = { error: "Can't list your habits." })));
  await Promise.allSettled(loads);
  if (form) rebuildForm();
}

function closeForm() {
  form = null;
  formNode = null;
  render();
}

/** The mode the form is about: the block's own, or the one picked for a new block. */
function formMode() {
  const d = form.data;
  if (form.blockId) return d.mode;
  return d.kind === "TIME" ? d.setTime : d.kind;
}

/** The form's page: built once, then only what the daemon changes (its tasks, its rules in words) moves. */
function formView(b) {
  if (!formNode) formNode = buildForm(b);
  formNode.querySelector(".form-notice").replaceChildren(noticeView() ?? "");
  const howNote = formNode.querySelector(".form-how");
  if (howNote && b && howNote.textContent !== (how(b) ?? "")) howNote.textContent = how(b) ?? "";
  const list = formNode.querySelector(".form-items");
  if (list && b) {
    const key = JSON.stringify(b.items);
    if (list.dataset.key !== key) {
      list.dataset.key = key;
      list.replaceChildren(...itemRows(b));
    }
  }
  return formNode;
}

function rebuildForm() {
  if (!form) return;
  const old = formNode;
  formNode = null;
  if (old?.isConnected) old.replaceWith(formView(state?.blocks.find((x) => x.id === form.blockId)));
  else render();
}

function buildForm(b) {
  const d = form.data;
  const isNew = !form.blockId;
  const mode = formMode();
  const errorBox = h("div", { class: "error" }, form.error);
  const number = (key, attrs = {}) => h("input", { type: "number", min: 1, step: 1, value: d[key], oninput: (e) => (d[key] = Number(e.target.value)), ...attrs });
  const field = (label, input) => h("label", { class: "field" }, h("span", {}, label), input);
  const check = (key, label) => h("label", { class: "check" }, h("span", {}, label), h("input", { type: "checkbox", class: "switch", checked: d[key], onchange: (e) => { d[key] = e.target.checked; rebuildForm(); } }));
  const pick = (key, label) => h("label", { class: "check" }, h("input", { type: "checkbox", class: "pick", checked: d[key], onchange: (e) => { d[key] = e.target.checked; rebuildForm(); } }), label);
  const seg = (key, options) => h("div", { class: "seg" }, options.map(([value, label]) =>
    h("button", { type: "button", class: d[key] === value ? "on" : "", onclick: () => { d[key] = value; rebuildForm(); } }, label)));
  const group = (title, ...children) => [h("h3", { class: "section" }, title), h("div", { class: "group" }, children)];

  // What to block
  const list = h("div", { class: "applist" });
  const drawList = () => {
    const q = form.filter.toLowerCase();
    const shown = (apps ?? []).filter((a) => form.apps.has(a.id) || !q || a.name.toLowerCase().includes(q) || a.id.toLowerCase().includes(q));
    const missing = [...form.apps].filter((id) => !(apps ?? []).some((a) => a.id === id));
    list.replaceChildren(...(apps ? [] : [h("div", { class: "note" }, "Loading your apps…")]),
      ...[...missing.map((id) => ({ id, name: id })), ...shown].slice(0, 200).map((a) =>
        h("label", {},
          h("input", { type: "checkbox", checked: form.apps.has(a.id), onchange: (e) => { e.target.checked ? form.apps.add(a.id) : form.apps.delete(a.id); } }),
          a.name, h("small", {}, a.id))));
  };
  drawList();
  const targetsGroup = group("What to block",
    h("input", { type: "search", placeholder: "Find a laptop app", value: form.filter, oninput: (e) => { form.filter = e.target.value; drawList(); } }),
    list,
    field("Websites", h("textarea", { value: form.sitesText, placeholder: "youtube.com\nreddit.com", oninput: (e) => (form.sitesText = e.target.value) })),
    field("Leave these alone", h("textarea", { class: "short", value: form.exceptText, placeholder: "music.youtube.com", oninput: (e) => (form.exceptText = e.target.value) })),
    b?.phoneApps > 0 && h("div", { class: "note" }, `+ ${b.phoneApps} phone app${b.phoneApps === 1 ? "" : "s"}`),
    field("Blocks on", seg("devices", [["BOTH", "Both"], ["PHONE", "Phone"], ["LAPTOP", "Laptop"]])));

  // Days and times, for a schedule or a block's blocked hours.
  const dayPick = (days, set) => h("div", { class: "daypick" }, DAYS.map((name, i) => h("label", {},
    h("input", { type: "checkbox", checked: days.includes(i + 1), onchange: (e) => set(e.target.checked ? [...new Set([...days, i + 1])].sort() : days.filter((x) => x !== i + 1)) }), name)));
  // A browser's own time field follows its language and can show am/pm, so this one is plain text.
  // Until the text is a whole time the last good one stands, and leaving the field shows it again.
  const timeInput = (w, key) => h("input", {
    type: "text", class: "clock", maxlength: 5, placeholder: "22:00", value: hhmm(w[key]),
    oninput: (e) => { const m = minutesOf(e.target.value); if (m != null) w[key] = m; },
    onblur: (e) => (e.target.value = hhmm(w[key])),
  });
  const times = (w) => h("div", { class: "row" }, field("From", timeInput(w, "startMin")), field("Until", timeInput(w, "endMin")));
  const timesEditor = (sched, allDay = false) => [
    dayPick(sched.days, (days) => (sched.days = days)),
    !allDay && times(sched),
    sched.moreWindows.map((w, i) => h("div", { class: "window" },
      dayPick(w.days, (days) => (w.days = days)),
      times(w),
      h("button", { class: "btn ghost", type: "button", onclick: () => { sched.moreWindows.splice(i, 1); rebuildForm(); } }, icon("delete"), "Remove"))),
    h("button", { class: "btn outline", type: "button", onclick: () => { sched.moreWindows.push({ days: [1, 2, 3, 4, 5, 6, 7], startMin: 1080, endMin: 1320 }); rebuildForm(); } }, icon("add"), "More times"),
  ];

  // The limit and blocked hours go on a habit block too (I44).
  const limitFields = () => [
    check("dailyOn", "A limit a day"),
    d.dailyOn && field("Minutes a day", number("allowanceMin")),
    check("rowOn", "At most so long in a row"),
    d.rowOn && h("div", { class: "row" }, field("Minutes in a row", number("rowMin")), field("Then a break of (minutes)", number("rowBreakMin"))),
    check("opensOn", "At most so many opens a day"),
    d.opensOn && field("Opens a day", number("opensPerDay")),
    check("openWaitOn", "A pause before it opens"),
    d.openWaitOn && field("Seconds (websites)", number("openWaitS")),
    field("Phone and laptop", seg("perDevice", [[false, "Share one limit"], [true, "Each has its own"]])),
  ];
  const limitSet = mode === "ALLOWANCE" ? group("Daily limit", limitFields())
    : mode === "HABIT" && group("Daily limit",
      check("limitOn", "Limit it too"),
      d.limitOn && limitFields(),
      d.limitOn && (d.dailyOn || d.opensOn) && field("What comes first", seg("habitFirst", [[false, "Limit first"], [true, "Habit first"]])));
  const hoursSet = (mode === "HABIT" || mode === "ALLOWANCE") && group("Blocked hours",
    check("hoursOn", "Block it outright at set times"),
    d.hoursOn && check("hoursAllDay", "All day on these days"),
    d.hoursOn && timesEditor(d.hours, d.hoursAllDay));

  // How it unlocks
  let rules;
  if (mode === "HABIT") {
    const hasTasks = isNew ? d.checklist : b.trigger !== "TIMER";
    const hasTimer = isNew ? d.timer : b.trigger !== "CHECKLIST";
    const linkList = !isNew ? null : Array.isArray(habits)
      ? h("div", { class: "applist short" }, habits.length ? habits.map((x) => {
        const on = d.linked.has(x.uuid);
        return h("label", {},
          h("input", { type: "checkbox", checked: on, onchange: (e) => { e.target.checked ? d.linked.set(x.uuid, { uuid: x.uuid, name: x.name, yesterday: false }) : d.linked.delete(x.uuid); rebuildForm(); } }),
          h("img", { class: "favicon", src: "/xrnloops.svg", alt: "" }),
          x.name,
          on && h("small", {}, h("label", { class: "check inline" }, h("input", { type: "checkbox", class: "pick", checked: d.linked.get(x.uuid).yesterday, onchange: (e) => (d.linked.get(x.uuid).yesterday = e.target.checked) }), "yesterday's tick")));
      }) : h("div", { class: "note" }, "No habits yet"))
      : h("div", { class: "note" }, habits?.error ?? (state?.syncConfigured ? "Loading…" : "Needs sync"));
    rules = group("How it unlocks",
      isNew
        ? h("div", { class: "checks" }, pick("checklist", "Do tasks"), pick("timer", "Wait on a timer"))
        : h("div", { class: "note form-how" }, how(b) ?? ""),
      hasTasks && hasTimer && field("Needs", seg("combineOr", [[false, "Both"], [true, "Either one"]])),
      hasTimer && field("Timer (minutes)", number("timerMin")),
      isNew && hasTasks && field("Tasks", h("textarea", { value: d.itemsText, placeholder: "One per line", oninput: (e) => (d.itemsText = e.target.value) })),
      isNew && hasTasks && h("div", { class: "field" }, h("span", {}, "Habits that unlock it"), linkList),
      !isNew && hasTasks && h("div", { class: "field" }, h("span", {}, "Tasks"), h("div", { class: "form-items" }),
        h("div", { class: "row" },
          h("input", { type: "text", class: "new-task", placeholder: "A new task", value: d.newTask, oninput: (e) => (d.newTask = e.target.value), onkeydown: (e) => e.key === "Enter" && addTask() }),
          h("button", { class: "btn", type: "button", onclick: addTask }, icon("add"), "Add")),
        Array.isArray(habits) && habits.length > 0 && h("div", { class: "row" },
          h("select", { id: "link-pick" }, habits.filter((x) => !b.items.some((i) => i.habit?.id === x.uuid)).map((x) => h("option", { value: x.uuid }, x.name))),
          h("button", {
            class: "btn", type: "button",
            onclick: () => {
              const chosen = formNode.querySelector("#link-pick");
              const x = habits.find((y) => y.uuid === chosen?.value);
              if (x) itemOp({ op: "link", habit: { uuid: x.uuid, name: x.name } });
            },
          }, h("img", { class: "favicon", src: "/xrnloops.svg", alt: "" }), "Link habit"))),
      field(`Block again after (minutes, up to ${min(state.dailyCapMs)} a day)`, number("unlockMin", { max: min(state.dailyCapMs) })),
      field("Counted as", seg("selfPaced", [[false, "Timer"], [true, "Self paced"]])));
  } else if (mode === "SCHEDULE") {
    rules = group("When it blocks", timesEditor(d));
  } else if (mode === "LOCKDOWN") {
    rules = group("Lockdown", field("Minutes", number("lockdownMin")));
  }

  const leave = () => { cancelGuard(); message = ""; closeForm(); };
  // An error is about what was sent: it goes as soon as something changes.
  return h("div", { class: "subpage", "data-block": form.blockId ?? "" },
    h("div", { class: "form-notice" }),
    h("div", { class: "page-title" },
      h("button", { class: "round", type: "button", title: "Back", "aria-label": "Back", onclick: leave }, icon("back")),
      h("h2", {}, isNew ? "New block" : "Edit block"),
      h("button", { class: "btn primary", type: "button", onclick: submitForm }, isNew ? "Create" : "Save")),
    h("div", { class: "editor form", oninput: () => form.error && showFormError(""), onchange: () => form.error && showFormError("") },
      h("div", { class: "group" },
        field("Name", h("input", { type: "text", value: d.name, placeholder: "Games, Social, …", oninput: (e) => (d.name = e.target.value) })),
        isNew && field("Kind", seg("kind", [["HABIT", "Habit"], ["ALLOWANCE", "Daily limit"], ["TIME", "Set time"]])),
        isNew && d.kind === "TIME" && seg("setTime", [["SCHEDULE", "On a schedule"], ["LOCKDOWN", "For a while, from now"]])),
      targetsGroup,
      rules,
      limitSet,
      hoursSet,
      errorBox,
      h("div", { class: "form-foot" },
        h("button", { class: "btn primary big", type: "button", onclick: submitForm }, isNew ? "Create block" : "Save"),
        h("button", { class: "btn ghost", type: "button", onclick: leave }, "Cancel"),
        !isNew && h("span", { class: "spacer" }),
        !isNew && h("button", { class: "btn danger", type: "button", onclick: () => deleteBlock(b) }, icon("delete"), "Delete block"))));
}

function itemRows(b) {
  if (!b.items.length) return [h("div", { class: "note" }, "No tasks yet")];
  return b.items.map((i) => h("div", { class: "row item" },
    h("span", {}, i.text, i.habit && h("small", {}, i.habit.yesterday ? " · habit, yesterday's tick" : " · habit"), i.tag && h("small", {}, " · NFC tag")),
    h("button", { class: "btn ghost", type: "button", onclick: () => itemOp({ op: "remove", itemId: i.id }, `Removing “${i.text}”`) }, "Remove")));
}

function showFormError(text) {
  form.error = text;
  formNode?.querySelector(".error")?.replaceChildren(text);
}

async function submitForm() {
  const d = form.data;
  const result = await post("/api/block", {
    id: form.blockId,
    name: d.name,
    mode: formMode(),
    desktopApps: [...form.apps],
    sites: form.sitesText.split(/[\s,]+/).filter(Boolean),
    exceptSites: form.exceptText.split(/[\s,]+/).filter(Boolean),
    checklist: d.checklist,
    timer: d.timer,
    combineOr: d.combineOr,
    timerMin: d.timerMin,
    unlockMin: d.unlockMin,
    selfPaced: d.selfPaced,
    devices: d.devices,
    items: (d.itemsText ?? "").split("\n"),
    linked: [...(d.linked?.values() ?? [])],
    dailyOn: d.dailyOn,
    allowanceMin: d.allowanceMin,
    rowMin: d.rowOn ? d.rowMin : 0,
    rowBreakMin: d.rowBreakMin,
    opensPerDay: d.opensOn ? d.opensPerDay : 0,
    openWaitS: d.openWaitOn ? d.openWaitS : 0,
    perDevice: d.perDevice,
    lockdownMin: d.lockdownMin,
    days: d.days,
    startMin: d.startMin,
    endMin: d.endMin,
    moreWindows: d.moreWindows,
    limitOn: d.limitOn,
    habitFirst: d.habitFirst,
    hoursOn: d.hoursOn,
    hoursAllDay: d.hoursAllDay,
    hoursDays: d.hours.days,
    hoursStartMin: d.hours.startMin,
    hoursEndMin: d.hours.endMin,
    hoursMore: d.hours.moreWindows,
  });
  if (result.error) showFormError(result.error);
  else if (result.token) startGuard(result.token, result.waitMs, result.label ?? `Saving ${d.name}`);
  else {
    closeForm();
    refresh();
  }
}

async function addTask() {
  const text = form.data.newTask.trim();
  if (!text) return;
  form.data.newTask = "";
  formNode.querySelector("input.new-task").value = "";
  await itemOp({ op: "add", text });
}

async function itemOp(body, label = "") {
  const result = await post("/api/item", { blockId: form.blockId, ...body });
  if (result.error) showFormError(result.error);
  else if (result.token) startGuard(result.token, result.waitMs, label, false);
  else showFormError("");
  refresh();
}

async function deleteBlock(b) {
  if (!confirm(`Delete "${b.name}"? It goes on the phone too.`)) return;
  const result = await post("/api/block/delete", { blockId: b.id });
  if (result.error) showFormError(result.error);
  else if (result.token) startGuard(result.token, result.waitMs, `Deleting ${b.name}`);
  else {
    closeForm();
    refresh();
  }
}

// ---- Insights ------------------------------------------------------------------------------

let insights = loadInsightsView();
let historyData = null; // what /api/history answered, shared events merged
let historyAt = 0;
let historyLoading = false;
let insightsNode = null;
let insightsDrawn = null; // the data the node was last drawn from

function loadInsightsView() {
  const fallback = { range: "week", device: "all", block: null, historyDays: 7, openDays: {} };
  try {
    const kept = JSON.parse(localStorage.getItem(INSIGHTS_KEY) ?? "{}");
    return { ...fallback, range: kept.range ?? fallback.range, device: kept.device ?? fallback.device };
  } catch {
    return fallback;
  }
}

function setInsights(change) {
  insights = { ...insights, ...change };
  try {
    localStorage.setItem(INSIGHTS_KEY, JSON.stringify({ range: insights.range, device: insights.device }));
  } catch {
    /* a private window: this visit only */
  }
  drawInsights(true);
}

async function loadHistory() {
  if (historyLoading) return;
  historyLoading = true;
  try {
    const res = await fetch("/api/history", { cache: "no-store" });
    const raw = await res.json();
    historyData = {
      events: mergeShared(raw.events, raw.emergencies),
      blocks: normalizeBlocks(raw.blocks),
      limits: raw.limits ?? {},
      now: raw.now,
      zone: raw.zone,
      reached: raw.synced ? raw.reached : null,
    };
  } catch {
    /* the next minute tries again */
  } finally {
    historyAt = Date.now();
    historyLoading = false;
  }
  drawInsights(false);
}

function drawInsights(force) {
  if (!insightsNode) insightsNode = h("div", { class: "insights" });
  if (!historyData) {
    if (!insightsNode.firstChild) insightsNode.replaceChildren(h("p", { class: "boot" }, "Loading…"));
    return insightsNode;
  }
  if (!force && insightsDrawn === historyData) return insightsNode;
  insightsDrawn = historyData;
  renderInsights(insightsNode, historyData, insights, {
    setRange: (range) => setInsights({ range, historyDays: 7 }),
    setDevice: (device) => setInsights({ device }),
    pickBlock: (block) => { setInsights({ block, historyDays: 7 }); window.scrollTo({ top: 0, behavior: "smooth" }); },
    more: () => setInsights({ historyDays: insights.historyDays + 7 }),
    toggleDay: (day, open) => setInsights({ openDays: { ...insights.openDays, [day]: open } }),
  });
  if (historyData.reached === false) {
    insightsNode.querySelector(".ins-controls")?.append(h("span", { class: "offline", title: "This laptop's history only" }, icon("cloudOff"), "Phone not reached"));
  }
  return insightsNode;
}

function insightsView() {
  if (!historyLoading && Date.now() - historyAt > 60_000) loadHistory();
  return drawInsights(false);
}

// ---- Settings ------------------------------------------------------------------------------

let settingsPage = null; // { node, sync, prefs, status, bar }: kept, so a field being typed in stays
let settingsForm = null; // the buying-minutes fields as edited, until saved
let settingsDirty = false;
let prefsKey = "";
let settingsError = "";

function settingsView() {
  if (!settingsPage) {
    settingsPage = { sync: h("div"), prefs: h("div"), status: h("div"), bar: h("div") };
    settingsPage.node = h("div", { class: "settings" },
      settingsPage.sync,
      h("h3", { class: "section" }, "Buying minutes"),
      settingsPage.prefs,
      h("h3", { class: "section" }, "This laptop"),
      settingsPage.status,
      settingsPage.bar,
      h("div", { class: "foot-mark" }, h("img", { src: "/icon.svg", alt: "xrnrule" })));
  }
  const st = state.settings;
  const key = JSON.stringify(st);
  if (!settingsDirty && prefsKey !== key) {
    settingsForm = { phrase: st.phrase, waitSec: Math.round(st.waitMs / 1000), grantMin: min(st.grantMs), perDay: st.perDay, showClock: st.showClock, blockOtherBrowsers: st.blockOtherBrowsers };
    settingsPage.prefs.replaceChildren(buildPrefs());
    prefsKey = key;
  }
  const sync = !state.syncConfigured ? ["idle", "cloudOff", "Not set up"]
    : state.sync?.ok === false ? ["off", "cloudOff", state.sync.message]
      : ["", "cloud", state.lastContactWall ? `Synced · ${time.format(state.lastContactWall)}` : "Synced"];
  settingsPage.sync.replaceChildren(h("section", { class: `card sync-card ${sync[0]}` }, icon(sync[1]),
    h("div", { class: "stitle" }, h("b", {}, "Home server"), h("small", {}, sync[2]))));
  const theme = savedTheme(THEME_KEY);
  const ext = state.extensionMissing ? ["bad", "Off in this browser"]
    : state.extensionLatest ? ["bad", `${state.extensionVersion} · ${state.extensionLatest} is out`]
      : state.extensionNoIncognito ? ["bad", "Not in private windows"]
        : ["", state.extensionVersion ?? "–"];
  const windows = state.kwinSeenAgoMs == null ? ["bad", "Not watched"] : ["", state.windowSystem === "hyprland" ? "Hyprland" : "KWin"];
  const row = (lead, title, small, trailing = null, cls = "") => h("div", { class: "srow" }, lead,
    h("div", { class: "stitle" }, h("span", {}, title), small != null && h("small", { class: cls }, small)), trailing);
  settingsPage.status.replaceChildren(h("div", { class: "group-card" },
    row(icon("sun"), "Appearance", null, h("div", { class: "seg" }, [["auto", "Auto"], ["light", "Light"], ["dark", "Dark"]].map(([v, label]) =>
      h("button", { type: "button", class: theme === v ? "on" : "", "data-key": `theme:${v}`, onclick: () => { saveTheme(THEME_KEY, v); render(); } }, label)))),
    row(icon("desktop"), "Windows", windows[1], null, windows[0]),
    row(icon("extension"), "Browser extension", ext[1], null, ext[0]),
    state.habitsUrl && h("a", { class: "srow", href: state.habitsUrl, target: "_blank" }, h("span", { class: "lead" }, h("img", { src: "/xrnloops.svg", alt: "" })),
      h("div", { class: "stitle" }, h("span", {}, "xrnloops"), h("small", { class: state.loop?.ok === false ? "bad" : "" }, state.loop?.ok === false ? state.loop.message : "Synced")), icon("openNew")),
    row(icon("lockOpen"), "Unlock time a day", null, h("span", { class: "val num" }, `${min(state.dailyCapMs)} min`))));
  settingsPage.bar.replaceChildren(settingsDirty ? h("div", { class: "savebar" },
    h("span", { class: "error" }, settingsError),
    h("button", { class: "btn ghost", type: "button", onclick: () => { settingsDirty = false; settingsError = ""; prefsKey = ""; render(); } }, "Cancel"),
    h("button", { class: "btn primary", type: "button", onclick: saveSettings }, "Save")) : "");
  return settingsPage.node;
}

/** The settings you edit, built once; a change shows the Save bar. */
function buildPrefs() {
  const f = settingsForm;
  const dirty = () => {
    if (!settingsDirty) {
      settingsDirty = true;
      render();
    }
  };
  const num = (key, attrs = {}) => h("input", { type: "number", min: 0, step: 1, value: f[key], oninput: (e) => { f[key] = Number(e.target.value); dirty(); }, ...attrs });
  const sw = (key) => h("input", { type: "checkbox", class: "switch", checked: f[key], onchange: (e) => { f[key] = e.target.checked; dirty(); } });
  const row = (ic, title, ...trailing) => h("label", { class: "srow" }, icon(ic), h("div", { class: "stitle" }, h("span", {}, title)), trailing);
  return h("div", { class: "group-card" },
    row("edit", "Sentence", h("input", { type: "text", value: f.phrase, oninput: (e) => { f.phrase = e.target.value; dirty(); } })),
    row("timer", "Wait", num("waitSec"), h("span", { class: "unit" }, "s")),
    row("moreTime", "Minutes", num("grantMin", { min: 1 }), h("span", { class: "unit" }, "min")),
    row("calendar", "Times a day per block", num("perDay"), h("span", { class: "unit" })),
    row("timer", "Corner clock on limited sites", sw("showClock")),
    row("globe", "Close other browsers", sw("blockOtherBrowsers")));
}

async function saveSettings() {
  const result = await post("/api/settings", settingsForm);
  if (result.error) {
    settingsError = result.error;
    render();
    return;
  }
  settingsDirty = false;
  settingsError = "";
  prefsKey = "";
  if (result.token) startGuard(result.token, result.waitMs, "Saving the settings");
  refresh();
}

// ---- the page ------------------------------------------------------------------------------

function alerts() {
  const out = [];
  if (failed) out.push(["The laptop app isn't answering", "Is xrnrule-desktop running? systemctl --user status xrnrule-desktop"]);
  if (!state) return out;
  if (state.kwinSeenAgoMs == null) {
    if (state.windowSystem === "hyprland") out.push(["Desktop apps aren't being watched", "Hyprland's sockets haven't answered. Is HYPRLAND_INSTANCE_SIGNATURE in the service's environment? systemctl --user show-environment"]);
    else out.push(["Desktop apps aren't being watched", "Run desktop/install.sh again"]);
  }
  if (state.extensionMissing) out.push(["Website blocking is off in this browser", "Turn it on in the browser's extensions page"]);
  if (state.extensionNoIncognito) out.push(["Sites aren't blocked in private windows", "Allow it in incognito on the extensions page"]);
  if (!state.extensionMissing && state.extensionLatest) out.push(["The browser extension is out of date", `${state.extensionVersion}; ${state.extensionLatest} is out. Reload it on the extensions page`]);
  if (state.syncConfigured && state.sync && !state.sync.ok) out.push(["Not syncing with the phone", state.sync.message]);
  if (state.phoneQuiet) out.push(["The phone has gone quiet", state.phoneQuiet]);
  if (state.clockNote) out.push(["Blocks keep their own clock", state.clockNote]);
  // A setup step, not a fault: said calmly.
  if (!state.syncConfigured) out.push(["Not syncing", "Fill in ~/.config/xrnrule/desktop.properties", true]);
  return out;
}

function alertsView() {
  // The block page is about the blocked thing: a setup step can wait for the main page.
  const list = alerts().filter(([, , calm]) => !(calm && onBlockPage));
  return list.length ? h("div", { class: "alerts" }, list.map(([t, d, calm]) =>
    h("div", { class: `alert${calm ? " calm" : ""}` }, icon(calm ? "cloudOff" : "warning"), h("div", {}, h("b", {}, t), h("span", {}, d))))) : null;
}

function noticeView() {
  return message ? h("div", { class: "notice" }, h("span", {}, message), h("button", { class: "round small", type: "button", title: "Close", onclick: () => { message = ""; render(); } }, icon("close"))) : null;
}

function blocksView() {
  const breakButton = !state.pauseRemainingMs && state.breakLifts && h("button", {
    class: "round", type: "button", "data-key": "break", disabled: !state.canPause || !!guard,
    title: state.canPause ? "Take a 15-min break" : "Break used today", "aria-label": "Take a 15-min break", onclick: () => act({ type: "pause" }),
  }, icon("coffee"));
  return h("div", { class: "blocks-page" },
    alertsView(),
    noticeView(),
    h("div", { class: "page-title" },
      h("h2", {}, "Blocks"),
      state.summary && h("span", { class: "summary" }, state.summary),
      h("span", { class: "spacer" }),
      h("div", { class: "today" },
        h("div", { class: "figure" }, h("b", {}, `${min(state.unlockUsedMs)} / ${min(state.dailyCapMs)} min`), h("span", {}, "Unlocked today")),
        breakButton,
        h("button", { class: "btn primary", type: "button", "data-key": "new", onclick: () => openForm(null) }, icon("add"), "New block"))),
    state.pauseRemainingMs > 0 && h("div", { class: "banner" }, icon("coffee"),
      h("div", {}, h("b", {}, "On a break"), h("span", { class: "left" }, `${clock(state.pauseRemainingMs)} left`)),
      h("button", { class: "btn ghost", type: "button", "data-key": "endPause", onclick: () => act({ type: "endPause" }) }, "End now")),
    state.blocks.length
      ? h("div", { class: "blocks" }, state.blocks.map(blockCard))
      : h("section", { class: "card first-run" }, h("p", { class: "label" }, "No blocks yet"), h("button", { class: "btn primary", type: "button", onclick: () => openForm(null) }, icon("add"), "New block")));
}

function blockPageView() {
  const sub = state.subject;
  const still = sub ? sub.blocked : blockedApp ? state.blockedApps.includes(blockedApp) : siteBlocked(blockedSite ?? "");
  // Only what's holding this: a block over the same site that's open has nothing to say here.
  let blocks = state.blocks.filter((b) => sub?.blockers.includes(b.id));
  const what = blockedApp ? appName(blockedApp) : blockedSite;
  if (!still && backHere(backUrl, blockedSite)) {
    location.replace(backUrl);
    return null;
  }
  const hero = h("section", { class: "hero" + (still ? "" : " open") },
    h("div", { class: "pic letter" }, initial(what), blockedSite && !blockedApp && siteIcon(blockedSite)),
    h("h2", {}, sub?.opening ? `${what} is opening` : still ? `${what} is blocked` : `${what} is open`),
    still && sub?.back && h("div", { class: "back" }, sub.back),
    still && sub?.why && h("p", {}, sub.why));
  const buyable = still && sub && !sub.overrideRejection && !sub.overrideReadyAtWall;
  const way = still && h("section", { class: "way" },
    h("button", { class: "btn big leave", type: "button", "data-key": "leave", onclick: leaveBlockPage }, blockedApp ? "Close this page" : "Leave this site"),
    buyable && h("button", { class: "quiet", type: "button", "data-key": "buy", onclick: () => buy("begin") }, `Take ${min(state.settings.grantMs)} more minutes`),
    sub?.overrideRejection === "NONE_LEFT" && h("p", { class: "rule" }, "None left to buy today"));
  // Time holds these, so they have nothing to do here: only a habit gets its card.
  const rules = blocks.filter((b) => b.reason !== "HABIT").map(ruleLine);
  blocks = blocks.filter((b) => b.reason === "HABIT");
  document.title = sub?.opening ? `${what} is opening · xrnrule` : still ? `${what} is blocked · xrnrule` : "xrnrule";
  return h("div", { class: "focus" },
    alertsView(),
    hero,
    !waitView && noticeView(),
    way,
    blocks.length > 0 && h("div", { class: "blocks" }, blocks.map(blockCard)),
    rules);
}

const TABS = [["blocks", "Blocks", "shield"], ["insights", "Insights", "chart"], ["settings", "Settings", "settings"]];

function header() {
  const sync = !state ? null : !state.syncConfigured ? ["idle", "cloudOff", "Local only"] : state.sync?.ok === false ? ["off", "cloudOff", "Offline"] : ["", "cloud", "Synced"];
  return h("header", { class: "bar" }, h("div", { class: "wrap" },
    h("a", { class: "mark", href: "/", title: "xrnrule" }, h("img", { src: "/icon.svg", alt: "xrnrule" })),
    !onBlockPage && h("nav", { class: "tabs" }, TABS.map(([id, label, ic]) => h("button", {
      type: "button", class: tab === id ? "on" : "", "data-key": `tab:${id}`, "aria-current": tab === id ? "page" : null,
      onclick: () => goTo(id),
    }, icon(ic), h("span", {}, label)))),
    h("span", { class: "spacer" }),
    onBlockPage ? h("a", { class: "btn outline", href: "/" }, "All blocks") : [
      sync && h("span", { class: `sync ${sync[0]}`, title: state.sync?.message ?? sync[2] }, icon(sync[1]), h("span", {}, sync[2])),
      state?.habitsUrl && h("a", { class: "app-link", href: state.habitsUrl, target: "_blank", title: "xrnloops" }, h("img", { src: "/xrnloops.svg", alt: "xrnloops" })),
    ]));
}

function goTo(id) {
  if (form) {
    cancelGuard();
    form = null;
    formNode = null;
  }
  tab = id;
  const hash = id === "blocks" ? "" : `#${id}`;
  if (location.hash !== hash) history.replaceState(null, "", location.pathname + location.search + hash);
  window.scrollTo(0, 0);
  render();
}
window.addEventListener("hashchange", () => !onBlockPage && goTo(tabOf(location.hash)));

let shell = null; // { bar, main }

function render() {
  if (pressing) return;
  const app = document.getElementById("app");
  if (!app) return;
  if (!shell) {
    shell = { bar: h("div"), main: h("main", { class: "wrap" }) };
    app.replaceChildren(shell.bar, shell.main);
  }
  // A redrawn button takes the focus back by its key; fields live in parts that aren't redrawn.
  const focused = document.activeElement?.dataset?.key;
  shell.bar.replaceChildren(header());
  let view;
  if (!state) view = h("div", {}, alertsView() ?? h("p", { class: "boot" }, "Loading…"));
  else if (onBlockPage) view = blockPageView();
  else if (form) view = formView(state.blocks.find((x) => x.id === form.blockId));
  else if (tab === "insights") view = insightsView();
  else if (tab === "settings") view = settingsView();
  else view = blocksView();
  if (!view) return;
  if (shell.main.firstChild !== view || shell.main.childNodes.length !== 1) {
    const scrollY = window.scrollY;
    shell.main.replaceChildren(view);
    window.scrollTo(0, scrollY);
  }
  if (focused) document.querySelector(`[data-key="${CSS.escape(focused)}"]`)?.focus({ preventScroll: true });
  showWait();
}

refresh();
setInterval(() => {
  refresh();
  tickGuard();
}, 1000);
setInterval(heartbeat, 2000);
heartbeat();
window.addEventListener("focus", heartbeat);
window.addEventListener("blur", heartbeat);
