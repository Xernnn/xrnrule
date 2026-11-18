// xrnrule on the laptop: the local page ("/") and the block page ("/block?app=…" or
// "/block?site=…&back=…") that blocked apps and sites land on. Everything comes from the
// daemon's /api/state, polled every second; the engine behind it is the phone's.

const params = new URLSearchParams(location.search);
const onBlockPage = location.pathname === "/block";
const blockedApp = params.get("app");
const blockedSite = params.get("site");
// The page a blocked tab left: the extension puts it in the hash (raw), older links in ?back=.
const backUrl = location.hash.length > 1 ? location.hash.slice(1) : params.get("back");

let state = null;
let apps = null; // installed desktop apps, loaded when the form opens
let habits = null; // [{uuid, name}] to link tasks to, or {error}; loaded when the form opens
let form = null; // the block form: { blockId (null: a new block), data, apps: Set, sitesText, filter, error }
let formNode = null; // kept across redraws, so typing survives the once-a-second refresh
let guard = null; // { token, until, label, close }: a loosening waiting out its 60 s
let message = "";
let failed = false;
let phrase = ""; // the override sentence as typed so far, kept across redraws
let settingsForm = null; // the settings card's fields while open

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
  guard = { token, until: Date.now() + waitMs, label, close };
  render();
}

async function tickGuard() {
  if (!guard || Date.now() < guard.until) return;
  const { token, close } = guard;
  guard = null;
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
}
document.addEventListener("visibilitychange", () => document.visibilityState === "hidden" && cancelGuard());
window.addEventListener("pagehide", cancelGuard);

// The block page stands in for the blocked app or site while you wait on it (I4).
function heartbeat() {
  if (!onBlockPage) return;
  post("/api/heartbeat", { app: blockedApp, host: blockedSite, focused: document.hasFocus() && document.visibilityState === "visible" }).catch(() => {});
}

// ---- helpers -------------------------------------------------------------------------------

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (k in el) el[k] = v;
    else el.setAttribute(k, v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

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

/** The engine's words for how a block stands: the same sentence as the phone's card. */
const TONE = { BLOCKED: "blocked", READY: "earned", OPEN: "open", OFF: "idle" };

/** Loop's entry values (contract §1). */
const YES_MANUAL = 2, YES_AUTO = 1, NO = 0;

/**
 * A habit a block asks for (contract I23, I36): tick it (on the item's day), add to it, or run its
 * focus timer. Amounts are thousandths of the unit (minutes for a timer).
 */
function habitItem(x) {
  const units = (n) => +(n / 1000).toFixed(3);
  const unit = x.unit && x.kind !== "TIMER" ? ` ${x.unit}` : "";
  const day = x.yesterday ? " (yesterday)" : "";
  if (x.kind === "CHECK") {
    const ticked = x.dayValue === YES_MANUAL;
    return h("label", {},
      h("input", { type: "checkbox", checked: ticked || x.dayValue === YES_AUTO, onchange: () => act({ type: "set", habitId: x.id, day: x.day, value: ticked ? NO : YES_MANUAL }) }),
      x.name + day,
      x.unlock === null ? h("span", { class: "linked" }, "never opens")
        : x.spent > 0 && x.amount < 1 && h("span", { class: "linked" }, "used"));
  }
  const toward = x.unlock === null ? "never opens"
    : x.kind === "TIMER" ? `${Math.floor(Math.min(x.amount, x.unlock) / 1000)} / ${units(x.unlock)} min` : `${units(Math.min(x.amount, x.unlock))} / ${units(x.unlock)}${unit}`;
  return h("div", { class: "habit" },
    h("div", { class: "actions" },
      h("span", {}, x.name + day),
      h("span", { class: "note" }, toward),
      x.kind === "COUNT" && !x.yesterday && h("button", { class: "btn", onclick: () => act({ type: "log", habitId: x.id, amount: 1000 }) }, "+1"),
      x.kind === "COUNT" && !x.yesterday && x.dayValue > 0 && h("button", { class: "btn", onclick: () => act({ type: "log", habitId: x.id, amount: -1000 }) }, "−1"),
      x.kind === "TIMER" && !x.yesterday && h("button", { class: "btn", onclick: () => act({ type: x.focusing ? "focusPause" : "focus", habitId: x.id }) }, x.focusing ? "Pause" : x.onBreak ? "Resume" : "Focus"),
      x.kind === "TIMER" && (x.focusing || x.onBreak) && h("button", { class: "btn", onclick: () => act({ type: "focusEnd" }) }, "End")),
    h("div", { class: "progress" }, h("i", { style: `width:${Math.min(100, (x.amount / Math.max(1, x.unlock ?? 1)) * 100)}%` })));
}

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

// ---- views ---------------------------------------------------------------------------------

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
 * The block's picture, as the phone's card has one: its site's icon, up to four of them, or the
 * first letter of its laptop app (the page has no app icons).
 */
function blockTile(b) {
  const icons = b.shownSites.slice(0, 4).map(siteIcon).filter(Boolean);
  if (icons.length === 1) return h("div", { class: "tile one" }, icons[0]);
  if (icons.length > 1) return h("div", { class: "tile many" }, icons);
  const first = (b.desktopApps.length ? appName(b.desktopApps[0]) : b.name).trim();
  return h("div", { class: "tile" }, h("span", { class: "glyph" }, (first[0] ?? "x").toUpperCase()));
}

function blockCard(b) {
  const cls = TONE[b.headline.tone] ?? "idle";
  const hasTimer = b.mode === "HABIT" && (b.trigger === "TIMER" || b.trigger === "COMBINED") && b.state === "ARMED";
  const manualOpen = b.mode === "HABIT" && b.state === "ARMED";
  const actions = [];
  if (b.state === "EARNED" && b.mode === "HABIT") {
    const capped = b.redeemRejection === "CAP_REACHED";
    const held = b.redeemRejection === "HELD"; // I44: opening now would open nothing
    actions.push(h("button", { class: "btn primary", disabled: capped || held, onclick: () => act({ type: "redeem", blockId: b.id }) }, `Open for ${min(b.unlockMs)} min`));
    if (capped) actions.push(h("span", { class: "note" }, `${min(state.dailyCapMs)} min used today`));
  }
  if (b.mode === "LOCKDOWN" && b.lockdownRemainingMs === 0 && b.enabled) {
    actions.push(h("button", { class: "btn", onclick: () => act({ type: "lockdown", blockId: b.id }) }, `Start lockdown (${min(b.lockdownMs)} min)`));
  }
  const edit = !onBlockPage && form?.blockId !== b.id && h("button", { class: "btn edit", onclick: () => openForm(b) }, "Edit");
  return h("section", { class: "card block" + (form?.blockId === b.id ? " editing" : "") },
    h("div", { class: "head" },
      blockTile(b),
      h("div", { class: "name" },
        h("h3", {}, b.name),
        h("div", { class: `headline ${cls}` }, b.headline.text),
        h("div", { class: "chips" },
          b.devices === "PHONE" && h("span", { class: "chip" }, "phone only"),
          b.devices === "LAPTOP" && h("span", { class: "chip" }, "laptop only"),
          b.hardcore && h("span", { class: "chip strict" }, "hardcore"))),
      edit),
    hasTimer && h("div", {},
      h("div", { class: "progress" }, h("i", { style: `width:${Math.min(100, (b.timerMs / Math.max(1, b.requiredMs)) * 100)}%` })),
      h("div", { class: "actions", style: "margin-top:8px" },
        h("span", { class: "note" }, `${clock(b.timerMs)} of ${clock(b.requiredMs)}`),
        h("button", { class: "btn", onclick: () => act({ type: "timer", blockId: b.id, running: !b.timerRunning }) }, b.timerRunning ? "Pause" : "Start"))),
    b.items.length > 0 && b.mode === "HABIT" && h("div", { class: "items" }, b.items.map((i) => i.habit ? habitItem(i.habit) :
      // An NFC tag (I43): only the phone ticks it, by reading the tag.
      i.tag ? h("label", {},
        h("input", { type: "checkbox", checked: i.done, disabled: !(manualOpen && i.done), onchange: (e) => act({ type: "toggle", itemId: i.id, done: e.target.checked }) }),
        i.text, h("small", {}, i.done ? " · scanned" : " · hold the phone to it")) :
      h("label", {},
        h("input", { type: "checkbox", checked: i.done, disabled: !manualOpen, onchange: (e) => act({ type: "toggle", itemId: i.id, done: e.target.checked }) }),
        i.text))),
    how(b) && h("div", { class: "how" }, how(b)),
    h("div", { class: "targets" },
      !onBlockPage && b.phoneApps > 0 && h("span", { class: "target" }, `${b.phoneApps} phone app${b.phoneApps === 1 ? "" : "s"}`),
      b.shownSites.map((s) => h("span", { class: "target" }, siteIcon(s), s)),
      b.exceptSites.map((s) => h("span", { class: "target except", title: "Left alone" }, `not ${s}`)),
      b.desktopApps.map((a) => h("span", { class: "target" }, `💻 ${appName(a)}`))),
    actions.length > 0 && h("div", { class: "actions" }, actions),
    form?.blockId === b.id && formView(b));
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

function formView(b) {
  if (!formNode) formNode = buildForm(b);
  formNode.querySelector(".guard").replaceChildren(guardView());
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
  const field = (label, input, hint) => h("label", { class: "field" }, h("span", {}, label), input, hint && h("small", {}, hint));
  const check = (key, label) => h("label", { class: "check" }, h("input", { type: "checkbox", checked: d[key], onchange: (e) => { d[key] = e.target.checked; rebuildForm(); } }), label);
  const seg = (key, options) => h("div", { class: "seg" }, options.map(([value, label]) =>
    h("button", { type: "button", class: d[key] === value ? "on" : "", onclick: () => { d[key] = value; rebuildForm(); } }, label)));

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
  const targets = h("fieldset", {},
    h("legend", {}, "What to block"),
    h("input", { type: "search", placeholder: "Find a laptop app", value: form.filter, oninput: (e) => { form.filter = e.target.value; drawList(); } }),
    list,
    field("Websites", h("textarea", { value: form.sitesText, placeholder: "youtube.com\nreddit.com", oninput: (e) => (form.sitesText = e.target.value) }),
      "One per line"),
    field("Leave these alone", h("textarea", { class: "short", value: form.exceptText, placeholder: "music.youtube.com", oninput: (e) => (form.exceptText = e.target.value) }),
      "One per line"),
    b?.phoneApps > 0 && h("div", { class: "note" }, `+ ${b.phoneApps} phone app${b.phoneApps === 1 ? "" : "s"}`));

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
  const timesEditor = (s, allDay = false) => [
    dayPick(s.days, (days) => (s.days = days)),
    !allDay && times(s),
    s.moreWindows.map((w, i) => h("div", { class: "window" },
      dayPick(w.days, (days) => (w.days = days)),
      times(w),
      h("button", { class: "btn ghost", type: "button", onclick: () => { s.moreWindows.splice(i, 1); rebuildForm(); } }, "Remove these times"))),
    h("button", { class: "btn", type: "button", onclick: () => { s.moreWindows.push({ days: [1, 2, 3, 4, 5, 6, 7], startMin: 1080, endMin: 1320 }); rebuildForm(); } }, "More times"),
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
    d.openWaitOn && field("Seconds", number("openWaitS"), "Websites only"),
    field("Phone and laptop", seg("perDevice", [[false, "Share one limit"], [true, "Each has its own"]])),
  ];
  const limitSet = mode === "ALLOWANCE" ? h("fieldset", {}, h("legend", {}, "Daily limit"), limitFields())
    : mode === "HABIT" && h("fieldset", {}, h("legend", {}, "Daily limit"),
      check("limitOn", "Limit it too"),
      d.limitOn && limitFields(),
      d.limitOn && (d.dailyOn || d.opensOn) && field("What comes first", seg("habitFirst", [[false, "Limit first"], [true, "Habit first"]])));
  const hoursSet = (mode === "HABIT" || mode === "ALLOWANCE") && h("fieldset", {}, h("legend", {}, "Blocked hours"),
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
          x.name,
          on && h("small", {}, h("label", { class: "check inline" }, h("input", { type: "checkbox", checked: d.linked.get(x.uuid).yesterday, onchange: (e) => (d.linked.get(x.uuid).yesterday = e.target.checked) }), "counts yesterday's tick")));
      }) : h("div", { class: "note" }, "No habits yet."))
      : h("div", { class: "note" }, habits?.error ?? (state?.syncConfigured ? "Loading…" : "Needs sync"));
    rules = h("fieldset", {},
      h("legend", {}, "How it unlocks"),
      isNew
        ? h("div", { class: "checks" }, check("checklist", "Do tasks"), check("timer", "Wait on a timer"))
        : h("div", { class: "note form-how" }, how(b) ?? ""),
      hasTasks && hasTimer && field("Needs", seg("combineOr", [[false, "Both"], [true, "Either one"]])),
      hasTimer && field("Timer (minutes)", number("timerMin"), "It counts while you wait on the block page."),
      isNew && hasTasks && field("Tasks", h("textarea", { value: d.itemsText, placeholder: "One per line", oninput: (e) => (d.itemsText = e.target.value) })),
      isNew && hasTasks && h("div", { class: "field" }, h("span", {}, "Habits that unlock it"), linkList),
      !isNew && hasTasks && h("div", { class: "field" }, h("span", {}, "Tasks"), h("div", { class: "form-items" }),
        h("div", { class: "row" },
          h("input", { type: "text", placeholder: "A new task", value: d.newTask, oninput: (e) => (d.newTask = e.target.value), onkeydown: (e) => e.key === "Enter" && addTask() }),
          h("button", { class: "btn", type: "button", onclick: addTask }, "Add")),
        Array.isArray(habits) && habits.length > 0 && h("div", { class: "row" },
          h("select", { id: "link-pick" }, habits.filter((x) => !b.items.some((i) => i.habit?.id === x.uuid)).map((x) => h("option", { value: x.uuid }, x.name))),
          h("button", {
            class: "btn", type: "button",
            onclick: () => {
              const pick = formNode.querySelector("#link-pick");
              const x = habits.find((y) => y.uuid === pick?.value);
              if (x) itemOp({ op: "link", habit: { uuid: x.uuid, name: x.name } });
            },
          }, "Link habit"))),
      field("Block again after (minutes)", number("unlockMin", { max: min(state.dailyCapMs) }), `Up to ${min(state.dailyCapMs)} min a day`),
      field("Counted as", seg("selfPaced", [[false, "Timer"], [true, "Self paced"]])));
  } else if (mode === "SCHEDULE") {
    rules = h("fieldset", {}, h("legend", {}, "When it blocks"), timesEditor(d));
  } else if (mode === "LOCKDOWN") {
    rules = h("fieldset", {}, h("legend", {}, "Lockdown"), field("Minutes", number("lockdownMin")));
  }

  // An error is about what was sent: it goes as soon as something changes.
  return h("div", { class: "editor form", "data-block": form.blockId ?? "", oninput: () => form.error && showFormError(""), onchange: () => form.error && showFormError("") },
    isNew && h("h3", {}, "New block"),
    field("Name", h("input", { type: "text", value: d.name, placeholder: "Games, Social, …", oninput: (e) => (d.name = e.target.value) })),
    isNew && field("Kind", seg("kind", [["HABIT", "Habit"], ["ALLOWANCE", "Daily limit"], ["TIME", "Set time"]])),
    isNew && d.kind === "TIME" && seg("setTime", [["SCHEDULE", "On a schedule"], ["LOCKDOWN", "For a while, from now"]]),
    targets,
    field("Blocks on", seg("devices", [["BOTH", "Both"], ["PHONE", "Phone"], ["LAPTOP", "Laptop"]])),
    rules,
    limitSet,
    hoursSet,
    errorBox,
    h("div", { class: "guard" }),
    h("div", { class: "actions" },
      h("button", { class: "btn primary", type: "button", onclick: submitForm }, isNew ? "Create block" : "Save"),
      h("button", { class: "btn ghost", type: "button", onclick: () => { cancelGuard(); message = ""; closeForm(); } }, "Cancel"),
      !isNew && h("span", { class: "spacer" }),
      !isNew && h("button", { class: "btn danger", type: "button", onclick: () => deleteBlock(b) }, "Delete block")));
}

function itemRows(b) {
  if (!b.items.length) return [h("div", { class: "note" }, "No tasks yet.")];
  return b.items.map((i) => h("div", { class: "row item" },
    h("span", {}, i.text, i.habit && h("small", {}, i.habit.yesterday ? " · habit, yesterday's tick" : " · habit"), i.tag && h("small", {}, " · NFC tag, set up on the phone")),
    h("button", { class: "btn ghost", type: "button", title: "Removing a task waits 60 s", onclick: () => itemOp({ op: "remove", itemId: i.id }, `Removing "${i.text}"`) }, "Remove")));
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
  else if (result.token) startGuard(result.token, result.waitMs, result.label ?? "Saving");
  else {
    closeForm();
    refresh();
  }
}

async function addTask() {
  const text = form.data.newTask.trim();
  if (!text) return;
  form.data.newTask = "";
  formNode.querySelector('.row input[type="text"]').value = "";
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

function guardView() {
  if (!guard) return "";
  return h("div", { class: "alert" }, h("b", {}, `${guard.label}…`), `${Math.ceil((guard.until - Date.now()) / 1000)} s · leaving cancels`);
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

let buyNode = null; // kept while a wait runs, so typing survives the once-a-second redraw
let buyKey = null;

function buyView() {
  const s = state.subject;
  if (!s) return null;
  const minutes = min(state.settings.grantMs);
  if (s.overrideRejection === "NONE_LEFT") return h("p", { class: "note" }, "None left to buy today");
  if (s.overrideRejection) return null;
  if (!s.overrideReadyAtWall) {
    buyNode = null;
    return h("div", { class: "buy" }, h("button", { class: "quiet", onclick: () => buy("begin") }, `Take ${minutes} more minutes`));
  }
  if (!buyNode || buyKey !== s.overrideReadyAtWall) {
    buyKey = s.overrideReadyAtWall;
    const claim = () => buy("claim", { phrase });
    buyNode = h("div", { class: "buy" },
      h("p", {}, "Type: ", h("b", {}, `“${state.settings.phrase}”`)),
      h("input", { type: "text", class: "phrase", value: phrase, placeholder: state.settings.phrase, autocomplete: "off", spellcheck: false,
        oninput: (e) => (phrase = e.target.value), onkeydown: (e) => e.key === "Enter" && claim() }),
      h("div", { class: "actions" },
        h("button", { class: "btn primary claim", onclick: claim }),
        h("button", { class: "btn ghost", onclick: () => buy("cancel") }, "Never mind")));
  }
  const waitLeft = s.overrideReadyAtWall - state.nowWall;
  const button = buyNode.querySelector(".claim");
  button.disabled = waitLeft > 0;
  button.textContent = waitLeft > 0 ? `Wait ${Math.ceil(waitLeft / 1000)} s` : `Open for ${minutes} min`;
  return buyNode;
}

// ---- settings ------------------------------------------------------------------------------

function settingsView() {
  const s = state.settings;
  if (!settingsForm) {
    return h("section", { class: "card settings" },
      h("div", { class: "top" }, h("h3", {}, "Settings"), h("span", { class: "spacer" }),
        h("button", { class: "btn ghost", onclick: () => { settingsForm = { phrase: s.phrase, waitSec: Math.round(s.waitMs / 1000), grantMin: min(s.grantMs), perDay: s.perDay, showClock: s.showClock, blockOtherBrowsers: s.blockOtherBrowsers }; render(); } }, "Edit")),
      h("div", { class: "note" },
        s.perDay > 0 ? `Buying minutes: ${Math.round(s.waitMs / 1000)} s, ${min(s.grantMs)} min, ${s.perDay} a day` : "Buying minutes: off",
        ` · Corner clock: ${s.showClock ? "on" : "off"}`,
        ` · Other browsers: ${s.blockOtherBrowsers ? "closed" : "allowed"}`));
  }
  if (!settingsNode) settingsNode = buildSettings();
  return settingsNode;
}

let settingsNode = null; // kept while open, so typing survives the once-a-second redraw

function buildSettings() {
  const f = settingsForm;
  const field = (label, input, hint) => h("label", { class: "field" }, h("span", {}, label), input, hint && h("small", {}, hint));
  const num = (key, attrs = {}) => h("input", { type: "number", min: 0, step: 1, value: f[key], oninput: (e) => (f[key] = Number(e.target.value)), ...attrs });
  return h("section", { class: "card settings" },
    h("h3", {}, "Settings"),
    h("div", { class: "editor form" },
      field("Sentence for extra minutes", h("input", { type: "text", value: f.phrase, oninput: (e) => (f.phrase = e.target.value) })),
      h("div", { class: "row" },
        field("Wait (seconds)", num("waitSec")),
        field("Minutes bought", num("grantMin", { min: 1 })),
        field("Times a day per block", num("perDay"), "0 turns it off.")),
      h("label", { class: "check" }, h("input", { type: "checkbox", checked: f.showClock, onchange: (e) => (f.showClock = e.target.checked) }), "Show the corner clock on limited sites"),
      h("label", { class: "check" }, h("input", { type: "checkbox", checked: f.blockOtherBrowsers, onchange: (e) => (f.blockOtherBrowsers = e.target.checked) }), "Close other browsers"),
      h("div", { class: "actions" },
        h("button", { class: "btn primary", onclick: saveSettings }, "Save"),
        h("button", { class: "btn ghost", onclick: () => { settingsForm = null; settingsNode = null; render(); } }, "Cancel"))));
}

async function saveSettings() {
  const result = await post("/api/settings", settingsForm);
  if (result.error) message = result.error;
  else if (result.token) startGuard(result.token, result.waitMs, "Saving the settings");
  settingsForm = null;
  settingsNode = null;
  refresh();
}

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
  if (!state.syncConfigured) out.push(["Not syncing", "Fill in ~/.config/xrnrule/desktop.properties"]);
  return out;
}

function render() {
  if (pressing) return;
  const app = document.getElementById("app");
  const scrollY = window.scrollY;
  const header = h("header", { class: "bar" }, h("div", { class: "wrap" },
    h("h1", {}, h("a", { href: "/", style: "display:contents;color:inherit" }, h("img", { src: "/icon.svg", alt: "" }), h("span", { class: "wordmark" }, "xrnrule"), !onBlockPage && h("small", {}, "laptop"))),
    h("span", { class: "spacer" }),
    onBlockPage ? h("a", { class: "link", href: "/" }, "All blocks") : [
      state && h("span", { class: "dot" + (!state.syncConfigured ? " idle" : state.sync?.ok === false ? " off" : "") },
        !state.syncConfigured ? "Local only" : state.sync?.ok === false ? "Offline" : "Synced"),
      state?.habitsUrl && h("a", { class: "link", href: state.habitsUrl, target: "_blank" }, "Habits"),
    ]));

  if (!state) {
    app.replaceChildren(header, h("main", { class: "wrap" }, alerts().map(([t, d]) => h("div", { class: "alert" }, h("b", {}, t), d))));
    return;
  }

  let blocks = state.blocks;
  let hero = null;
  let way = null;
  let rules = null;
  if (onBlockPage) {
    const s = state.subject;
    const still = s ? s.blocked : blockedApp ? state.blockedApps.includes(blockedApp) : siteBlocked(blockedSite ?? "");
    // Only what's holding this: a block over the same site that's open has nothing to say here.
    blocks = blocks.filter((b) => s?.blockers.includes(b.id));
    const what = blockedApp ? appName(blockedApp) : blockedSite;
    if (!still && backHere(backUrl, blockedSite)) {
      location.replace(backUrl);
      return;
    }
    hero = h("section", { class: "hero" + (still ? "" : " open") },
      blockedSite && !blockedApp && siteIcon(blockedSite),
      h("h2", {}, s?.opening ? `${what} is opening` : still ? `${what} is blocked` : `${what} is open`),
      still && s?.back && h("div", { class: "back" }, s.back),
      still && s?.why && h("p", {}, s.why));
    way = still && h("section", { class: "way" },
      h("div", { class: "actions" }, h("button", { class: "btn primary", onclick: leaveBlockPage }, blockedApp ? "Close this page" : "Leave this site")),
      buyView());
    // Time holds these, so they have nothing to do here: only a habit gets its card.
    rules = blocks.filter((b) => b.reason !== "HABIT").map(ruleLine);
    blocks = blocks.filter((b) => b.reason === "HABIT");
    document.title = s?.opening ? `${what} is opening · xrnrule` : still ? `${what} is blocked · xrnrule` : "xrnrule";
  }

  const pause = !state.pauseRemainingMs && !state.breakLifts ? null : state.pauseRemainingMs > 0
    ? h("div", { class: "actions" }, h("span", { class: "note" }, `On a break: ${clock(state.pauseRemainingMs)} left`), h("button", { class: "btn", onclick: () => act({ type: "endPause" }) }, "End it now"))
    : h("button", { class: "btn", disabled: !state.canPause || !!guard, onclick: () => act({ type: "pause" }) }, state.canPause ? "Take a 15-min break" : "Break used today");

  app.replaceChildren(header, h("main", { class: "wrap" + (onBlockPage ? " focus" : "") },
    alerts().map(([t, d]) => h("div", { class: "alert" }, h("b", {}, t), d)),
    hero,
    message && h("div", { class: "alert" }, message),
    guard && !form && guardView(),
    !onBlockPage && h("section", { class: "card summary" },
      h("div", { class: "stat" }, h("b", {}, `${min(state.unlockUsedMs)} / ${min(state.dailyCapMs)} min`), h("span", {}, "Unlocked today")),
      h("span", { class: "spacer" }),
      pause,
      !form && h("button", { class: "btn primary", onclick: () => openForm(null) }, "+ New block")),
    form && !form.blockId && !onBlockPage && h("section", { class: "card" }, formView(null)),
    blocks.length ? h("div", { class: "blocks" }, blocks.map(blockCard)) : !form && !onBlockPage && h("section", { class: "card note" }, "No blocks"),
    !onBlockPage && settingsView(),
    way,
    rules,
    !onBlockPage && h("div", { class: "foot" },
      h("span", {}, state.loop?.ok === false ? `Habits: ${state.loop.message}` : ""),
      h("span", {}, state.lastContactWall ? `Home server last seen ${time.format(state.lastContactWall)}` : state.sync ? `Last try ${time.format(state.sync.atWallMs)}` : ""))));
  window.scrollTo(0, scrollY);
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
