// Insights: what happened on both devices, from the event log the phone and the laptop send the
// sync server (xrnrule-sync.py, POST /events) and the daily limit use it keeps (/limits). Shared
// by the laptop's page (the desktop build copies this file; the daemon merges its own log in at
// /api/history) and this status page. The logic is plain functions (server/insights.test.mjs);
// renderInsights draws it.
//
// Events, as the server keeps them: {d: device, t: "blocked"|"habit"|"unlock"|"bought"|"break",
// at: ms, target?, name?, block?, ms?, start?}. A blocked try names what was tried (an app's
// package or laptop id, or a site's host), not the block: the block is the first in the list
// that covers it. A habit done or an unlock is shared, so each device logs it; one of each is
// kept. The rules are the phone's (app/.../ui/InsightsModel.kt), so both show the same figures.

import { h, s, icon, deviceIcon, deviceOf, duration, inkOn } from "./ui.js";

/** A block's colour: by its place in the block list, round again past the eighth. */
export const PALETTE = ["#E8590C", "#1C6FD8", "#2B9348", "#7048E8", "#D6336C", "#0CA678", "#F59F00", "#1283C4"];
/** What no block covers any more (a block deleted, a target taken off). */
export const OTHER = "#8A8A93";

export const RANGES = { today: 1, week: 7, month: 30 };
const DAY = 86_400_000;
/** One device's habit done, logged again by the other within this, is the same one. */
const SAME_HABIT_MS = 10 * 60_000;
/** Tries at one thing on one device this close together are one row of the history. */
const RUN_MS = 30 * 60_000;

// ---- blocks --------------------------------------------------------------------------------

/**
 * The blocks as the insights need them, in list order with their colours, from either page's
 * shape: the laptop's /api/history ({id, name, apps, sites, except, dailyMs, perDevice}) or the
 * shared state's ({id, name, targetPackages, targetDesktopApps, targetSites, exceptSites, …}).
 */
export function normalizeBlocks(list) {
  return (list ?? []).map((b, i) => ({
    id: b.id,
    name: b.name || "Block",
    colour: PALETTE[i % PALETTE.length],
    apps: new Set(b.apps ?? [...(b.targetPackages ?? []), ...(b.targetDesktopApps ?? [])]),
    sites: (b.sites ?? b.targetSites ?? []).map((x) => String(x).split("/")[0].toLowerCase()),
    except: (b.except ?? b.exceptSites ?? []).map((x) => String(x).toLowerCase()),
    dailyMs: b.dailyMs ?? b.dailyAllowanceMs ?? 0,
    perDevice: !!b.perDevice,
  }));
}

/** A host as one site, without "www." (as the phone's time per site goes), and without a path. */
const bare = (host) => String(host ?? "").toLowerCase().split("/")[0].replace(/^www\./, "");
const under = (host, site) => host === site || host.endsWith("." + site);

/** The block a blocked try counts for: the first that covers what was tried, or null. */
export function blockOf(blocks, target) {
  const t = String(target ?? "");
  const host = bare(t);
  return blocks.find((b) => b.apps.has(t) || (b.sites.some((x) => under(host, x)) && !b.except.some((x) => under(host, x)))) ?? null;
}

/** Whether a blocked try was at a website rather than an app. */
export function isSite(e, blocks) {
  const t = String(e.target ?? "");
  if (blocks.some((b) => b.apps.has(t))) return false;
  if (blocks.some((b) => b.sites.some((x) => under(bare(t), x)))) return true;
  return (e.name ?? t) === t && /^[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}$/i.test(t) && !/^(com|org|net|io|me|de|app)\.[a-z0-9-]+\./i.test(t);
}

// ---- time where the devices are (I54) ------------------------------------------------------

const fmts = new Map();
function parts(zone, ms) {
  let f = fmts.get(zone ?? "");
  if (!f) {
    const o = { hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric", second: "numeric", weekday: "short" };
    try {
      f = new Intl.DateTimeFormat("en-US", zone ? { ...o, timeZone: zone } : o);
    } catch {
      f = new Intl.DateTimeFormat("en-US", o);
    }
    fmts.set(zone ?? "", f);
  }
  const p = Object.fromEntries(f.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  const iso = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 }[p.weekday];
  return { y: +p.year, m: +p.month, d: +p.day, hour: +p.hour % 24, minute: +p.minute, second: +p.second, iso };
}

/** The day [ms] falls on, as days since 1970-01-01, where the devices are. */
export function dayOf(zone, ms) {
  const p = parts(zone, ms);
  return Date.UTC(p.y, p.m - 1, p.d) / DAY;
}

/** When [day] began there. */
export function dayStart(zone, day) {
  const asUtc = (ms) => {
    const q = parts(zone, ms);
    return Date.UTC(q.y, q.m - 1, q.d, q.hour, q.minute, q.second);
  };
  let t = day * DAY;
  for (let i = 0; i < 2; i++) t = day * DAY - (asUtc(t) - t);
  return t;
}

export const hourOf = (zone, ms) => parts(zone, ms).hour;
export const weekdayOf = (zone, ms) => parts(zone, ms).iso;

// ---- events --------------------------------------------------------------------------------

/** An unlock from before sessions were named (no start) is the same one on both devices this close together. */
const SAME_UNLOCK_MS = 2 * 60_000;

/**
 * The log with each shared event once, oldest first, each with `dev`: "phone", "laptop", or ""
 * for what belongs to every device at once. A habit done and an unlock used are shared (§2.4),
 * so every device logs them as it hears of them: two devices' lines for one are one event, on
 * the device that logged it first (where it happened; the other heard of it later). An unlock
 * goes by its block and session start (I28), a habit by its block within SAME_HABIT_MS. The
 * shared state's emergency unlocks (I40, "blockId@wall" -> wall) come in as events of every
 * device: the state doesn't say where one was used. The same rules as the phone's InsightsModel.
 */
export function mergeShared(events, emergencies = {}) {
  const all = [
    ...(events ?? []).filter((x) => x && Number.isFinite(x.at)).map((e) => ({ ...e, dev: deviceOf(e.d) })),
    ...Object.entries(emergencies ?? {}).filter(([, at]) => Number.isFinite(at))
      .map(([key, at]) => ({ t: "emergency", at, block: key.slice(0, key.lastIndexOf("@")), dev: "" })),
  ];
  const rank = { "": -1, phone: 0, laptop: 1 };
  all.sort((a, b) => a.at - b.at || (rank[a.dev] ?? 2) - (rank[b.dev] ?? 2));
  const out = [];
  const sessions = new Map(); // block@start -> the unlock kept
  const recent = new Map(); // kind@block -> the shared events kept lately
  for (const e of all) {
    if (e.t === "unlock" && e.start > 0) {
      const key = `${e.block}@${e.start}`;
      const had = sessions.get(key);
      if (had) {
        if (had.dev !== e.dev) continue;
      } else {
        sessions.set(key, e);
      }
    } else if (e.t === "habit" || e.t === "unlock") {
      const window = e.t === "habit" ? SAME_HABIT_MS : SAME_UNLOCK_MS;
      const key = `${e.t}@${e.block}`;
      const earlier = (recent.get(key) ?? []).filter((k) => e.at - k.at <= window);
      recent.set(key, earlier);
      if (earlier.some((k) => k.dev !== e.dev)) continue;
      earlier.push(e);
    }
    out.push(e);
  }
  return out;
}

/** The block ids a "bought" event names (one purchase can cover several, comma-separated). */
const boughtBlocks = (e) => String(e.block ?? "").split(",").filter(Boolean);

/**
 * The block an event is about, as far as can be told, or null: a blocked try's first covering
 * block, the first block a purchase names that is still there, or the block a habit, an unlock
 * or an emergency unlock names. A break is about none.
 */
function blockFor(e, blocks) {
  const known = (id) => (blocks.some((b) => b.id === id) ? id : null);
  if (e.t === "blocked") return blockOf(blocks, e.target)?.id ?? null;
  if (e.t === "bought") return boughtBlocks(e).map(known).find(Boolean) ?? null;
  if (e.t === "break") return null;
  return known(e.block);
}

const eventBlocks = (e, blocks) => {
  const id = blockFor(e, blocks);
  return id ? [id] : [];
};

/**
 * Everything the Insights page shows, for [range] ("today", "week", "month") on [device] ("all",
 * "phone", "laptop"), of one block or all ([block]: an id or null).
 *
 * input: {events (merged, mergeShared), blocks (normalizeBlocks), limits ({block: {device id:
 * {day start ms: ms}}}), now, zone}
 */
export function compute(input, { range = "week", device = "all", block = null } = {}) {
  const { events = [], blocks = [], limits = {}, now, zone } = input;
  const today = dayOf(zone, now);
  const n = RANGES[range] ?? 7;
  const first = today - n + 1;
  const from = dayStart(zone, first);
  const byId = new Map(blocks.map((b) => [b.id, b]));
  const onDevice = (e) => device === "all" || e.dev === "" || e.dev === device;
  const ofBlock = (e) => !block || blockFor(e, blocks) === block;
  const shown = events.filter((e) => onDevice(e) && ofBlock(e));
  const inRange = shown.filter((e) => e.at >= from && e.at <= now);
  const tries = inRange.filter((e) => e.t === "blocked");

  const figures = {
    tries: tries.length,
    unlockedMs: inRange.filter((e) => e.t === "unlock").reduce((a, e) => a + (e.ms ?? 0), 0),
    boughtMs: inRange.filter((e) => e.t === "bought").reduce((a, e) => a + (e.ms ?? 0), 0),
    breaks: block ? 0 : inRange.filter((e) => e.t === "break").length,
    habits: inRange.filter((e) => e.t === "habit").length,
  };

  // Blocked tries over time, each block its own part: by hour for today, else by day.
  const order = [...blocks.map((b) => b.id), null];
  const bucketOf = range === "today" ? (e) => hourOf(zone, e.at) : (e) => dayOf(zone, e.at) - first;
  const size = range === "today" ? 24 : n;
  const bars = Array.from({ length: size }, (_, i) => ({
    key: range === "today" ? i : first + i,
    hour: range === "today" ? i : null,
    day: range === "today" ? today : first + i,
    total: 0,
    parts: new Map(),
  }));
  for (const e of tries) {
    const bar = bars[bucketOf(e)];
    if (!bar) continue;
    const id = blockOf(blocks, e.target)?.id ?? null;
    bar.total++;
    bar.parts.set(id, (bar.parts.get(id) ?? 0) + 1);
  }
  const barList = bars.map((b) => ({ ...b, parts: order.filter((id) => b.parts.has(id)).map((id) => ({ block: id, n: b.parts.get(id) })) }));

  // Weekday × hour.
  const heat = Array.from({ length: 7 }, () => new Array(24).fill(0));
  for (const e of tries) heat[weekdayOf(zone, e.at) - 1][hourOf(zone, e.at)]++;
  const heatMax = Math.max(0, ...heat.flat());

  // Only days the log covers count: an empty log has no clean days to show.
  const firstDay = shown.length ? dayOf(zone, Math.min(...shown.map((e) => e.at))) : today + 1;
  const DIRTY = new Set(["blocked", "bought", "emergency"]);

  const perBlock = blocks.filter((b) => !block || b.id === block).map((b) => {
    const mine = inRange.filter((e) => eventBlocks(e, blocks).includes(b.id));
    const unlocks = mine.filter((e) => e.t === "unlock");
    // Clean days: nothing of it tried, bought past or unlocked in an emergency, counted back
    // from today (which counts while it is clean so far), never before the log's first day.
    const dirtyOn = new Set(shown.filter((e) => DIRTY.has(e.t) && blockFor(e, blocks) === b.id).map((e) => dayOf(zone, e.at)));
    let streak = 0;
    for (let d = today; d >= firstDay && !dirtyOn.has(d); d--) streak++;
    return {
      id: b.id, name: b.name, colour: b.colour,
      tries: mine.filter((e) => e.t === "blocked").length,
      unlocks: unlocks.length,
      unlockedMs: unlocks.reduce((a, e) => a + (e.ms ?? 0), 0),
      boughtMs: mine.filter((e) => e.t === "bought").reduce((a, e) => a + (e.ms ?? 0), 0),
      limit: b.dailyMs > 0 ? limitUse(limits[b.id], b, { zone, first, today, device }) : null,
      streak,
    };
  });

  // What was tried most.
  const tops = new Map();
  for (const e of tries) {
    const site = isSite(e, blocks);
    const key = site ? bare(e.target) : String(e.target);
    const t = tops.get(key) ?? { key, name: site ? bare(e.target) : e.name || e.target, site, n: 0, phone: 0, laptop: 0, block: blockOf(blocks, e.target)?.id ?? null };
    t.n++;
    if (e.dev === "phone" || e.dev === "laptop") t[e.dev]++;
    tops.set(key, t);
  }
  const top = [...tops.values()].sort((a, b) => b.n - a.n || a.name.localeCompare(b.name)).slice(0, 8);

  return { today, first, days: n, range, figures, bars: barList, heat, heatMax, perBlock, top, timeline: timeline(inRange, blocks, zone), byId };
}

/**
 * A block's limit use day by day ({used, allowance, days: [{day, used}]}), from the server's
 * record: a block shared by the devices adds their use up, one with a limit each (I33) goes by
 * the device that used the most, as the engine does.
 */
export function limitUse(record, block, { zone, first, today, device = "all" }) {
  const byDay = new Map();
  for (const [dev, days] of Object.entries(record ?? {})) {
    if (device !== "all" && deviceOf(dev) !== device) continue;
    for (const [start, ms] of Object.entries(days ?? {})) {
      const day = dayOf(zone, Number(start) + 12 * 3_600_000);
      if (!Number.isFinite(day) || day < first || day > today) continue;
      const had = byDay.get(day) ?? 0;
      byDay.set(day, block.perDevice ? Math.max(had, ms) : had + ms);
    }
  }
  const days = [];
  for (let d = first; d <= today; d++) days.push({ day: d, used: byDay.get(d) ?? null });
  const tracked = days.filter((x) => x.used != null);
  return {
    used: tracked.reduce((a, x) => a + x.used, 0),
    allowance: block.dailyMs * Math.max(1, tracked.length),
    daily: block.dailyMs,
    over: tracked.filter((x) => x.used >= block.dailyMs).length,
    tracked: tracked.length,
    days,
  };
}

/** The history, newest first, day by day; tries at one thing in a run are one row. */
export function timeline(events, blocks, zone) {
  const rows = [];
  for (const e of [...events].sort((a, b) => b.at - a.at)) {
    const last = rows[rows.length - 1];
    if (e.t === "blocked" && last?.t === "blocked" && last.target === e.target && last.dev === e.dev && last.from - e.at <= RUN_MS &&
      dayOf(zone, last.from) === dayOf(zone, e.at)) {
      last.n++;
      last.from = e.at;
      continue;
    }
    const ids = eventBlocks(e, blocks).filter(Boolean);
    rows.push({ ...e, n: 1, from: e.at, blocks: ids, site: e.t === "blocked" && isSite(e, blocks) });
  }
  const days = [];
  for (const r of rows) {
    const day = dayOf(zone, r.at);
    let d = days[days.length - 1];
    if (!d || d.day !== day) days.push((d = { day, rows: [], tries: 0 }));
    d.rows.push(r);
    if (r.t === "blocked") d.tries += r.n;
  }
  return days;
}

// ---- the view ------------------------------------------------------------------------------

const WEEKDAY = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const dayFmt = new Intl.DateTimeFormat(undefined, { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
const shortDay = new Intl.DateTimeFormat(undefined, { day: "numeric", timeZone: "UTC" });

/** "Today", "Yesterday", "Mon 16 Nov". */
function dayTitle(day, today) {
  if (day === today) return "Today";
  if (day === today - 1) return "Yesterday";
  return dayFmt.format(new Date(day * DAY));
}

/** Times as this browser shows them, in the devices' zone. */
function clockIn(zone) {
  try {
    return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: zone });
  } catch {
    return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  }
}

function seg(options, value, onPick, cls = "") {
  return h("div", { class: `seg ${cls}`.trim(), role: "tablist" }, options.map(([v, label, ic]) =>
    h("button", { type: "button", class: v === value ? "on" : "", "aria-pressed": String(v === value), onclick: () => onPick(v) }, ic && icon(ic), label && h("span", {}, label))));
}

/** A block's chip, in its colour; a click shows that block alone. */
function chip(b, onPick, extra = null) {
  if (!b) return h("span", { class: "bchip other" }, "Other");
  return h("button", {
    type: "button", class: "bchip", title: b.name,
    style: { "--c": b.colour, "--ink": inkOn(b.colour) },
    onclick: onPick ? () => onPick(b.id) : null,
  }, h("span", {}, b.name), extra);
}

function stat(ic, cls, value, label) {
  return h("div", { class: `stat ${cls}` }, h("span", { class: "stat-icon" }, icon(ic)), h("b", {}, value), h("span", { class: "label" }, label));
}

/** A round top for the chart, a little above its busiest column. */
function niceMax(max) {
  if (max <= 4) return 4;
  const p = 10 ** Math.floor(Math.log10(max));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * p >= max) return m * p;
  return 10 * p;
}

/** Blocked tries over time: a stacked column per hour or day, each block its own part. */
function barsChart(data) {
  const bars = data.bars;
  const max = niceMax(Math.max(1, ...bars.map((b) => b.total)));
  const labelEvery = bars.length > 14 ? 5 : bars.length > 7 ? 2 : 1;
  const label = (b, i) => b.hour != null ? (b.hour % 6 === 0 ? String(b.hour).padStart(2, "0") : "")
    : (bars.length - 1 - i) % labelEvery === 0 ? (bars.length <= 7 ? WEEKDAY[(((b.day + 3) % 7) + 7) % 7] : shortDay.format(new Date(b.day * DAY))) : "";
  const when = (b) => b.hour != null ? `${String(b.hour).padStart(2, "0")}:00` : dayTitle(b.day, data.today);
  const today = (b) => b.hour == null && b.day === data.today;
  return h("div", { class: "bars", role: "img", "aria-label": "Blocked tries" },
    h("div", { class: "baxis" }, [max, max / 2, 0].map((v, i) => h("span", { style: { top: `${i * 50}%` } }, String(Math.round(v))))),
    h("div", { class: "bplot" },
      h("i", { class: "gl", style: { top: "0" } }), h("i", { class: "gl", style: { top: "50%" } }),
      bars.map((b) => h("div", { class: `bcol${today(b) ? " now" : ""}`, title: `${when(b)} · ${b.total}` },
        b.total > 0 && h("div", { class: "bstack", style: { height: `${(b.total / max) * 100}%` } },
          b.parts.map((p) => h("i", {
            style: { flex: `${p.n} 0 0px`, background: data.byId.get(p.block)?.colour ?? OTHER },
            title: `${when(b)} · ${data.byId.get(p.block)?.name ?? "Other"} ${p.n}`,
          })))))),
    h("span"),
    h("div", { class: "blabels" }, bars.map((b, i) => h("span", { class: today(b) ? "now" : "" }, label(b, i)))));
}

function heatmap(data) {
  const cells = [];
  cells.push(h("span", { class: "hm-corner" }));
  for (let hr = 0; hr < 24; hr++) cells.push(h("span", { class: "hm-hour" }, hr % 3 === 0 ? String(hr).padStart(2, "0") : ""));
  data.heat.forEach((row, d) => {
    cells.push(h("span", { class: "hm-day" }, WEEKDAY[d]));
    row.forEach((n, hr) => {
      const level = n === 0 || data.heatMax === 0 ? 0 : Math.min(5, Math.ceil((n / data.heatMax) * 5));
      cells.push(h("span", { class: `hm-cell l${level}`, title: `${WEEKDAY[d]} ${String(hr).padStart(2, "0")}:00 · ${n}` }));
    });
  });
  return h("div", { class: "heatmap", role: "img", "aria-label": "Blocked tries by weekday and hour" }, cells);
}

function limitBars(limit) {
  const days = limit.days;
  const W = 160, H = 34;
  const max = Math.max(limit.daily, ...days.map((d) => d.used ?? 0)) || 1;
  const step = W / days.length;
  const bw = Math.max(2, step * 0.7);
  const line = H - (limit.daily / max) * H;
  return s("svg", { viewBox: `0 0 ${W} ${H}`, className: "spark", preserveAspectRatio: "none", "aria-hidden": "true" },
    days.map((d, i) => d.used == null ? null : s("rect", {
      x: i * step + (step - bw) / 2, y: H - (d.used / max) * H, width: bw, height: Math.max(1, (d.used / max) * H), rx: 1.5,
      className: d.used >= limit.daily ? "over" : "under",
    })),
    s("line", { x1: 0, x2: W, y1: line, y2: line, className: "allow" }));
}

function blockStatCard(p, data, actions, range) {
  const limit = p.limit;
  const pct = limit ? Math.min(1, limit.used / Math.max(1, limit.allowance)) : 0;
  return h("section", { class: "card bstat", style: { "--c": p.colour, "--ink": inkOn(p.colour) } },
    h("div", { class: "bstat-head" }, chip(data.byId.get(p.id), actions.pickBlock),
      h("span", { class: "streak", title: "Clean days" }, icon("fire"), h("b", {}, String(p.streak)), h("span", {}, p.streak === 1 ? "day" : "days"))),
    h("div", { class: "bstat-figs" },
      h("div", {}, h("b", {}, String(p.tries)), h("span", {}, "tries")),
      h("div", {}, h("b", {}, String(p.unlocks)), h("span", {}, p.unlocks === 1 ? "unlock" : "unlocks")),
      // A limit or a schedule opens by minutes bought, not by unlocks: those are its time open.
      p.unlocks === 0 && p.boughtMs > 0
        ? h("div", {}, h("b", {}, duration(p.boughtMs)), h("span", {}, "bought"))
        : h("div", {}, h("b", {}, duration(p.unlockedMs)), h("span", {}, "unlocked"))),
    limit && h("div", { class: "bstat-limit" },
      h("div", { class: "row-between" },
        h("span", { class: "label" }, range === "today" ? "Today" : "A day"),
        h("span", { class: "value" }, limit.tracked ? `${duration(limit.used / Math.max(1, range === "today" ? 1 : limit.tracked))} / ${duration(limit.daily)}` : `– / ${duration(limit.daily)}`)),
      h("div", { class: "meter" }, h("i", { style: { width: `${pct * 100}%` } })),
      range !== "today" && limitBars(limit)));
}

function topList(data, actions, siteIcon) {
  const max = Math.max(1, ...data.top.map((t) => t.n));
  return data.top.map((t) => {
    const b = data.byId.get(t.block);
    // The site's own icon over its letter: one the site hasn't given leaves the letter.
    const letter = h("span", { class: "letter", style: { "--c": b?.colour ?? OTHER, "--ink": inkOn(b?.colour ?? OTHER) } }, (t.name[0] ?? "?").toUpperCase());
    return h("div", { class: "top-row" },
      h("span", { class: "top-pic" }, letter, t.site && siteIcon?.(t.key)),
      h("div", { class: "top-main" },
        h("div", { class: "row-between" },
          h("span", { class: "top-name" }, t.name),
          h("span", { class: "top-devs" },
            t.phone > 0 && h("span", { title: "Phone" }, deviceIcon("phone"), String(t.phone)),
            t.laptop > 0 && h("span", { title: "Laptop" }, deviceIcon("laptop"), String(t.laptop)),
            h("b", {}, String(t.n)))),
        h("div", { class: "meter thin" }, h("i", { style: { width: `${(t.n / max) * 100}%`, background: b?.colour ?? OTHER } }))),
      b && chip(b, actions.pickBlock));
  });
}

function rowText(r) {
  switch (r.t) {
    case "blocked": return [h("span", { class: "what" }, r.site ? bare(r.target) : r.name || r.target), r.n > 1 && h("span", { class: "times" }, `×${r.n}`)];
    case "habit": return h("span", { class: "what" }, "Habit done");
    case "unlock": return [h("span", { class: "what" }, "Unlocked"), h("span", { class: "times" }, duration(r.ms ?? 0))];
    case "bought": return [h("span", { class: "what" }, "Bought"), h("span", { class: "times" }, duration(r.ms ?? 0))];
    case "break": return [h("span", { class: "what" }, "Break"), h("span", { class: "times" }, duration(r.ms ?? 0))];
    case "emergency": return h("span", { class: "what" }, "Emergency unlock");
    default: return h("span", { class: "what" }, r.t);
  }
}

const ROW_ICON = { blocked: "blocked", habit: "task", unlock: "lockOpen", bought: "moreTime", break: "coffee", emergency: "warning" };

/** A day's tries as one strip, each block its share, as long as the day is busy against the busiest. */
function dayStrip(d, data, most) {
  const by = new Map();
  for (const r of d.rows) if (r.t === "blocked") by.set(r.blocks[0] ?? null, (by.get(r.blocks[0] ?? null) ?? 0) + r.n);
  const order = [...data.byId.keys(), null].filter((id) => by.has(id));
  return h("span", { class: "hstrip" }, h("span", { class: "hstrip-fill", style: { width: `${most ? (d.tries / most) * 100 : 0}%` } },
    order.map((id) => h("i", { style: { flex: `${by.get(id)} 0 0px`, background: data.byId.get(id)?.colour ?? OTHER }, title: `${data.byId.get(id)?.name ?? "Other"} ${by.get(id)}` }))));
}

function historyList(data, view, actions, zone) {
  const clock = clockIn(zone);
  const days = data.timeline.slice(0, view.historyDays);
  if (!days.length) return h("p", { class: "empty" }, "Nothing yet");
  const most = Math.max(1, ...days.map((d) => d.tries));
  // The latest day is open unless closed; the others closed unless opened.
  const isOpen = (d, i) => (view.openDays ?? {})[d.day] ?? i === 0;
  return [
    days.map((d, i) => {
      const open = isOpen(d, i);
      const habitsDone = d.rows.filter((r) => r.t === "habit").length;
      const unlocked = d.rows.filter((r) => r.t === "unlock").reduce((a, r) => a + (r.ms ?? 0), 0);
      return h("div", { class: `hday${open ? " open" : ""}` },
        h("button", { type: "button", class: "hday-head", "aria-expanded": String(open), onclick: () => actions.toggleDay(d.day, !open) },
          icon(open ? "chevronDown" : "chevronRight", "chev"),
          h("b", {}, dayTitle(d.day, data.today)),
          dayStrip(d, data, most),
          h("span", { class: "hday-figs" },
            h("span", { title: "Blocked tries" }, icon("blocked"), String(d.tries)),
            habitsDone > 0 && h("span", { class: "habits", title: "Habits done" }, icon("task"), String(habitsDone)),
            unlocked > 0 && h("span", { class: "unlocked", title: "Unlocked" }, icon("lockOpen"), duration(unlocked)))),
        open && h("div", { class: "hrows" }, d.rows.map((r) => h("div", { class: `hrow k-${r.t}` },
          h("span", { class: "htime" }, r.n > 1 ? `${clock.format(r.from)}–${clock.format(r.at)}` : clock.format(r.at)),
          h("span", { class: "hdev", title: r.dev === "phone" ? "Phone" : r.dev === "laptop" ? "Laptop" : "Both" }, deviceIcon(r.dev)),
          h("span", { class: "hkind" }, icon(ROW_ICON[r.t] ?? "globe")),
          h("span", { class: "htext" }, rowText(r)),
          h("span", { class: "hchips" }, r.blocks.length ? r.blocks.map((id) => chip(data.byId.get(id), actions.pickBlock)) : r.t === "blocked" ? chip(null) : null)))));
    }),
    data.timeline.length > view.historyDays && h("button", { type: "button", class: "btn more", onclick: actions.more }, "More"),
  ];
}

/**
 * The Insights page into [root]. [view]: {range, device, block, historyDays}; [actions]:
 * {setRange, setDevice, pickBlock (an id, or null to show every block), more, toggleDay (a day,
 * open or not)}; [siteIcon]: an
 * <img> for a host, where the page has them.
 */
export function renderInsights(root, input, view, actions, siteIcon = null) {
  const data = compute(input, view);
  const picked = view.block && data.byId.get(view.block);
  const f = data.figures;
  root.replaceChildren(
    h("div", { class: "ins-controls" },
      seg([["today", "Today"], ["week", "7 days"], ["month", "30 days"]], view.range, actions.setRange),
      seg([["all", "All"], ["phone", "Phone", "phone"], ["laptop", "Laptop", "laptop"]], view.device, actions.setDevice, "devices"),
      picked && h("span", { class: "picked" }, chip(picked, null), h("button", { type: "button", class: "round small", title: "Every block", onclick: () => actions.pickBlock(null) }, icon("close")))),
    h("div", { class: "stats" },
      stat("blocked", "tries", String(f.tries), "Blocked tries"),
      stat("lockOpen", "unlocked", duration(f.unlockedMs), "Unlocked"),
      stat("moreTime", "bought", duration(f.boughtMs), "Bought"),
      !picked && stat("coffee", "breaks", String(f.breaks), f.breaks === 1 ? "Break" : "Breaks"),
      stat("task", "habits", String(f.habits), "Habits done")),
    h("div", { class: "ins-grid" },
      h("section", { class: "card chart-card wide" }, h("h3", { class: "section" }, "Blocked tries"), barsChart(data)),
      h("section", { class: "card heat-card" }, h("h3", { class: "section" }, "When"), heatmap(data)),
      h("section", { class: "card top-card" }, h("h3", { class: "section" }, "Most tried"),
        data.top.length ? topList(data, actions, siteIcon) : h("p", { class: "empty" }, "Nothing yet"))),
    data.perBlock.length > 0 && h("div", { class: "bstats" }, data.perBlock.map((p) => blockStatCard(p, data, actions, view.range))),
    h("section", { class: "card history" }, h("h3", { class: "section" }, "History"), historyList(data, view, actions, input.zone)));
}
