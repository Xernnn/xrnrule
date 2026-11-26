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

import { h, s, icon, deviceIcon, deviceOf, duration, toneAt, toneDot, OTHER_TONE, limitTone, meter, blockLogos, cluster, logo, siteKey, targetKey } from "./ui.js";

export const RANGES = { today: 1, week: 7, month: 30 };
const DAY = 86_400_000;
/** One device's habit done, logged again by the other within this, is the same one. */
const SAME_HABIT_MS = 10 * 60_000;
/** Tries at one thing on one device this close together are one row of the history. */
const RUN_MS = 30 * 60_000;

// ---- blocks --------------------------------------------------------------------------------

/**
 * The blocks as the insights need them, in list order with their tones (ui.js TONES) and their
 * icons (ui.js blockLogos, or [logos] as the page worked them out), from either page's shape: the
 * laptop's /api/history ({id, name, apps, sites, except, dailyMs, perDevice}) or the shared
 * state's ({id, name, targetPackages, targetDesktopApps, targetSites, exceptSites, …}).
 */
export function normalizeBlocks(list) {
  return (list ?? []).map((b, i) => ({
    id: b.id,
    name: b.name || "Block",
    tone: toneAt(i),
    apps: new Set(b.apps ?? [...(b.targetPackages ?? []), ...(b.targetDesktopApps ?? [])]),
    sites: (b.sites ?? b.targetSites ?? []).map((x) => String(x).split("/")[0].toLowerCase()),
    except: (b.except ?? b.exceptSites ?? []).map((x) => String(x).toLowerCase()),
    dailyMs: b.dailyMs ?? b.dailyAllowanceMs ?? 0,
    perDevice: !!b.perDevice,
    logos: b.logos ?? blockLogos(b),
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

/**
 * A blocked try's icon key: a site's is the host the block names (a try at m.youtube.com has
 * youtube.com's icon), an app's by the device it was tried on (ui.js targetKey).
 */
export function tryKey(e, blocks) {
  if (!isSite(e, blocks)) return targetKey(e.target, e.dev ?? deviceOf(e.d), false);
  const host = bare(e.target);
  const named = blocks.flatMap((b) => b.sites).find((x) => under(host, x));
  return siteKey(named ?? host);
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

/** [ms] where the devices are: its ISO weekday and minute of the day. */
export function clockAt(zone, ms) {
  const p = parts(zone, ms);
  return { iso: p.iso, minute: p.hour * 60 + p.minute };
}

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
 * {day start ms: ms}}}), now, zone, usedToday? ({block: ms}, today's limit use as the shared state
 * has it: what a limit shows, as today's, when the server has kept no day of it)}
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

  const figuresOf = (list) => ({
    tries: list.filter((e) => e.t === "blocked").length,
    unlockedMs: list.filter((e) => e.t === "unlock").reduce((a, e) => a + (e.ms ?? 0), 0),
    boughtMs: list.filter((e) => e.t === "bought").reduce((a, e) => a + (e.ms ?? 0), 0),
    breaks: block ? 0 : list.filter((e) => e.t === "break").length,
    habits: list.filter((e) => e.t === "habit").length,
  });
  const figures = figuresOf(inRange);
  // The period before, as long as this one (yesterday, the 7 or the 30 days before), once the log
  // reaches back to its first day: a log begun since would say "0 before" about days it never saw.
  const logStart = events.reduce((a, e) => Math.min(a, e.at), Infinity);
  const beforeFrom = dayStart(zone, first - n);
  const before = logStart < dayStart(zone, first - n + 1) ? figuresOf(shown.filter((e) => e.at >= beforeFrom && e.at < from)) : null;

  // Blocked tries over time, and each block's part of them: by hour for today, else by day.
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
  // Today's hours still to come draw nothing, and a block's sparkline stops at now.
  const nowHour = hourOf(zone, now);
  const sparkLength = range === "today" ? nowHour + 1 : size;

  // Weekday × hour, and its busiest hour (the first, Monday 00:00 onwards, as the phone has it).
  const heat = Array.from({ length: 7 }, () => new Array(24).fill(0));
  for (const e of tries) heat[weekdayOf(zone, e.at) - 1][hourOf(zone, e.at)]++;
  const heatMax = Math.max(0, ...heat.flat());
  let peak = null;
  heat.forEach((row, day) => row.forEach((count, hour) => {
    if (count === heatMax && heatMax > 0 && !peak) peak = { day, hour, n: count };
  }));

  // Only days the log covers count: an empty log has no clean days to show.
  const firstDay = shown.length ? dayOf(zone, Math.min(...shown.map((e) => e.at))) : today + 1;
  const DIRTY = new Set(["blocked", "bought", "emergency"]);

  // A limit's use a day, from the server's record (GET /limits); with no day of it there (a
  // server from before /limits, or nothing kept yet), today's as the shared state has it.
  const limitOf = (b) => {
    const use = limitUse(limits[b.id], b, { zone, first, today, device });
    const used = input.usedToday?.[b.id];
    return !use.tracked && device === "all" && Number.isFinite(used) ? { ...use, today: used } : use;
  };

  const perBlock = blocks.filter((b) => !block || b.id === block).map((b) => {
    const mine = inRange.filter((e) => eventBlocks(e, blocks).includes(b.id));
    const unlocks = mine.filter((e) => e.t === "unlock");
    // Clean days: nothing of it tried, bought past or unlocked in an emergency, counted back
    // from today (which counts while it is clean so far), never before the log's first day.
    const dirtyOn = new Set(shown.filter((e) => DIRTY.has(e.t) && blockFor(e, blocks) === b.id).map((e) => dayOf(zone, e.at)));
    let streak = 0;
    for (let d = today; d >= firstDay && !dirtyOn.has(d); d--) streak++;
    return {
      id: b.id, name: b.name, tone: b.tone, logos: b.logos,
      tries: mine.filter((e) => e.t === "blocked").length,
      spark: bars.slice(0, sparkLength).map((x) => x.parts.get(b.id) ?? 0),
      unlocks: unlocks.length,
      unlockedMs: unlocks.reduce((a, e) => a + (e.ms ?? 0), 0),
      boughtMs: mine.filter((e) => e.t === "bought").reduce((a, e) => a + (e.ms ?? 0), 0),
      limit: b.dailyMs > 0 ? limitOf(b) : null,
      streak,
    };
  });

  // What was tried most, all of it (the page shows the first TOP_SHOWN until asked for more), each with its icon.
  const tops = new Map();
  for (const e of tries) {
    const site = isSite(e, blocks);
    const key = site ? bare(e.target) : String(e.target);
    const t = tops.get(key) ?? { key, name: site ? bare(e.target) : e.name || e.target, site, n: 0, phone: 0, laptop: 0, block: blockOf(blocks, e.target)?.id ?? null, icon: null };
    t.n++;
    if (e.dev === "phone" || e.dev === "laptop") t[e.dev]++;
    t.icon ??= tryKey(e, blocks);
    tops.set(key, t);
  }
  const top = [...tops.values()].sort((a, b) => b.n - a.n || a.name.localeCompare(b.name));

  const screen = input.usage ? screenTime(input.usage, input.usageNames, { first, today, device, block, blocks }) : null;

  return { today, first, days: n, range, nowHour, figures, before, bars: barList, heat, heatMax, peak, perBlock, top, screen, timeline: timeline(inRange, blocks, zone), byId };
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
    const site = e.t === "blocked" && isSite(e, blocks);
    rows.push({ ...e, n: 1, from: e.at, blocks: ids, site, icon: e.t === "blocked" ? tryKey(e, blocks) : null });
  }
  const days = [];
  for (const r of rows) {
    const day = dayOf(zone, r.at);
    let d = days[days.length - 1];
    if (!d || d.day !== day) days.push((d = { day, rows: [], tries: 0, unlocks: 0, unlockedMs: 0, boughtMs: 0, parts: new Map() }));
    d.rows.push(r);
    if (r.t === "blocked") {
      d.tries += r.n;
      const id = r.blocks[0] ?? null;
      d.parts.set(id, (d.parts.get(id) ?? 0) + r.n);
    } else if (r.t === "unlock") {
      d.unlocks++;
      d.unlockedMs += r.ms ?? 0;
    } else if (r.t === "bought") {
      d.boughtMs += r.ms ?? 0;
    }
  }
  return days;
}

/**
 * Each block's day so far ({tries, unlocks, unlockedMs, boughtMs} by id), for the Blocks tab's
 * cards: the same counting as compute's, without the rest.
 */
export function dayByBlock({ events = [], blocks = [], now, zone }) {
  const from = dayStart(zone, dayOf(zone, now));
  const out = new Map(blocks.map((b) => [b.id, { tries: 0, unlocks: 0, unlockedMs: 0, boughtMs: 0 }]));
  for (const e of events) {
    if (e.at < from || e.at > now) continue;
    const day = out.get(blockFor(e, blocks));
    if (!day) continue;
    if (e.t === "blocked") day.tries++;
    else if (e.t === "unlock") {
      day.unlocks++;
      day.unlockedMs += e.ms ?? 0;
    } else if (e.t === "bought") day.boughtMs += e.ms ?? 0;
  }
  return out;
}

/** "4 tries · 12 min open · 5 min bought": a block's day in one line, nothing that is nought but its tries. */
export function dayLine(day) {
  if (!day) return "";
  return [
    `${day.tries} ${day.tries === 1 ? "try" : "tries"}`,
    day.unlockedMs > 0 && `${duration(day.unlockedMs)} open`,
    day.boughtMs > 0 && `${duration(day.boughtMs)} bought`,
  ].filter(Boolean).join(" · ");
}

// ---- screen time (docs/laptop-screen-time.md) ----------------------------------------------

/** "2026-11-26" as days since 1970-01-01, or NaN. */
function dayNumber(name) {
  const [y, m, d] = String(name).split("-").map(Number);
  const t = Date.UTC(y, m - 1, d);
  return Number.isFinite(t) ? t / DAY : NaN;
}

/**
 * Screen time from [first] to [today], out of the devices' counts ([usage]: {device: {"YYYY-MM-DD":
 * {apps: {id: ms}, sites: {host: ms}}}}, as the server's /usage keeps them): each day's time on
 * each device, the range's, and the apps and the sites with theirs, most first. A day's time on a
 * device is its apps' time: a site's is part of its browser's. Only the devices [device] shows;
 * with a [block] picked, only what it covers, its apps' time and its sites'. An app goes by its
 * device too (a laptop app and a phone package are two); a site is one row with each device's part.
 */
export function screenTime(usage, names, { first, today, device = "all", block = null, blocks = [] }) {
  const days = [];
  for (let d = first; d <= today; d++) days.push({ day: d, total: 0, phone: 0, laptop: 0 });
  const picked = block ? blocks.find((b) => b.id === block) : null;
  const covers = (target) => !block || (!!picked && blockOf([picked], target) != null);
  const apps = new Map();
  const sites = new Map();
  for (const [dev, byDay] of Object.entries(usage ?? {})) {
    const which = deviceOf(dev);
    if (device !== "all" && which !== device) continue;
    for (const [name, use] of Object.entries(byDay ?? {})) {
      const day = days[dayNumber(name) - first];
      if (!day) continue;
      const add = (ms) => {
        day.total += ms;
        if (which) day[which] += ms;
      };
      for (const [id, ms] of Object.entries(use?.apps ?? {})) {
        if (!Number.isFinite(ms) || ms <= 0 || !covers(id)) continue;
        add(ms);
        const key = `${which}|${id}`;
        const row = apps.get(key) ?? { key: id, name: names?.[id] || id, dev: which, ms: 0 };
        row.ms += ms;
        apps.set(key, row);
      }
      for (const [host, ms] of Object.entries(use?.sites ?? {})) {
        if (!Number.isFinite(ms) || ms <= 0 || !covers(host)) continue;
        if (block) add(ms); // a block's sites aren't its apps: their time is its own
        const row = sites.get(host) ?? { key: host, name: host, ms: 0, phone: 0, laptop: 0 };
        row.ms += ms;
        if (which) row[which] += ms;
        sites.set(host, row);
      }
    }
  }
  const sum = (k) => days.reduce((a, d) => a + d[k], 0);
  const ranked = (m) => [...m.values()].filter((r) => r.ms >= MIN_SHOWN).sort((a, b) => b.ms - a.ms || a.name.localeCompare(b.name));
  return { days, total: sum("total"), phone: sum("phone"), laptop: sum("laptop"), apps: ranked(apps), sites: ranked(sites) };
}

/** Under a minute isn't a row. */
const MIN_SHOWN = 60_000;

// ---- the view ------------------------------------------------------------------------------

const WEEKDAY = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const dayFmt = new Intl.DateTimeFormat(undefined, { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
const tickFmt = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", timeZone: "UTC" });
/** 0 for a Monday: 1970-01-01, day 0, was a Thursday. */
const weekdayIndex = (day) => (((day + 3) % 7) + 7) % 7;
const hh = (hour) => `${String(hour).padStart(2, "0")}:00`;

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
  return h("div", { class: `seg ${cls}`.trim(), role: "group" }, options.map(([v, label]) =>
    h("button", { type: "button", class: v === value ? "on" : "", "aria-pressed": String(v === value), onclick: () => onPick(v) }, label)));
}

/** A block's tone and name by its id; what no block covers is grey "Other". */
const toneOf = (data, id) => data.byId.get(id)?.tone ?? OTHER_TONE;
const nameOf = (data, id) => data.byId.get(id)?.name ?? "Other";

/** A block's dot and its name, side by side wherever its tone shows. */
const named = (data, id) => [toneDot(toneOf(data, id)), h("span", { class: "bname" }, nameOf(data, id))];

/** A figure under the big one: the figure, what it counts, the period before's, small. */
function stat(value, label, before) {
  return h("div", { class: "stat" }, h("b", {}, value), h("span", { class: "label" }, label),
    before != null && h("span", { class: "before" }, `${before} before`));
}

/**
 * The period at a glance, as Habits First's screen time: the blocked tries big, in the data
 * colour, the period before's beside its label; under them the time unlocked, the minutes bought,
 * the breaks (not for one block: a break is every block's) and the habits done, each with its own.
 */
function headline(data, picked) {
  const f = data.figures;
  const b = data.before;
  return h("section", { class: "card glance" },
    h("b", { class: "glance-num" }, String(f.tries)),
    h("span", { class: "glance-label" }, icon("blocked"), h("span", {}, f.tries === 1 ? "Blocked try" : "Blocked tries"),
      b && h("span", { class: "before" }, `· ${b.tries} before`)),
    h("div", { class: "stats" },
      stat(duration(f.unlockedMs), "Unlocked", b && duration(b.unlockedMs)),
      stat(duration(f.boughtMs), "Bought", b && duration(b.boughtMs)),
      !picked && stat(String(f.breaks), f.breaks === 1 ? "Break" : "Breaks", b && String(b.breaks)),
      stat(String(f.habits), "Habits done", b && String(b.habits))));
}

/** How many of the most tried show before "Show more". */
const TOP_SHOWN = 8;

/** A round top for the chart, a little above its busiest column. */
function niceMax(max) {
  if (max <= 4) return 4;
  const p = 10 ** Math.floor(Math.log10(max));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * p >= max) return m * p;
  return 10 * p;
}

/** A card's heading, with a figure at its right. */
const cardHead = (title, figure = null) => h("div", { class: "card-head" }, h("h3", { class: "section" }, title), figure && h("span", { class: "head-fig" }, figure));

/**
 * Blocked tries over time: a column per hour or day, stacked by block in their tones, the first
 * block at the bottom. Hovering, focusing or tapping a column shows its total and each block's
 * part beside it. Today's column is marked; today's hours still to come stay empty.
 */
function barsChart(data) {
  const bars = data.bars;
  const hourly = data.range === "today";
  const max = niceMax(Math.max(1, ...bars.map((b) => b.total)));
  /** [text, class] under a column, or null: 7 days by weekday, 30 by date every fifth, hours every third. */
  const tick = (b, i) => {
    if (hourly) return b.hour % 3 ? null : [String(b.hour).padStart(2, "0"), b.hour % 6 ? "minor" : ""];
    if (bars.length <= 7) return [WEEKDAY[weekdayIndex(b.day)], ""];
    const back = bars.length - 1 - i;
    return back % 5 ? null : [tickFmt.format(new Date(b.day * DAY)), back % 10 ? "minor" : ""];
  };
  const isNow = (b) => (hourly ? b.hour === data.nowHour : b.day === data.today);
  const later = (b) => hourly && b.hour > data.nowHour;
  const when = (b) => (hourly ? `${hh(b.hour)}–${hh((b.hour + 1) % 24)}` : dayTitle(b.day, data.today));

  const plot = h("div", { class: "bplot" }, h("i", { class: "gl", style: { top: "0" } }), h("i", { class: "gl", style: { top: "50%" } }));
  const tip = h("div", { class: "btip", hidden: true, "aria-hidden": "true" });
  let on = null;
  const hide = () => {
    tip.hidden = true;
    on?.classList.remove("on");
    on = null;
  };
  const show = (b, col) => {
    on?.classList.remove("on");
    on = col;
    col.classList.add("on");
    tip.replaceChildren(
      h("div", { class: "btip-head" }, h("span", {}, when(b)), h("b", {}, String(b.total))),
      ...b.parts.map((p) => h("div", { class: "btip-row" }, named(data, p.block), h("b", {}, String(p.n)))));
    tip.hidden = false;
    // Above its column, or beside it where a tall column leaves no room.
    const width = plot.clientWidth;
    const w = tip.offsetWidth;
    const x = col.offsetLeft + col.offsetWidth / 2;
    let left = Math.min(Math.max(0, x - w / 2), width - w);
    let top = plot.clientHeight * (1 - b.total / max) - tip.offsetHeight - 8;
    if (top < 0) {
      top = 0;
      left = x + col.offsetWidth / 2 + 6;
      if (left + w > width) left = Math.max(0, x - col.offsetWidth / 2 - 6 - w);
    }
    tip.style.left = `${left}px`;
    tip.style.top = `${top}px`;
  };
  for (const b of bars) {
    const label = [`${when(b)} · ${b.total}`, ...b.parts.map((p) => `${nameOf(data, p.block)} ${p.n}`)].join(", ");
    const col = h("div", { class: `bcol${isNow(b) ? " now" : ""}${later(b) ? " later" : ""}`, tabindex: later(b) ? null : "0", "aria-label": label },
      b.total > 0 && h("div", { class: "bstack", style: { height: `${(b.total / max) * 100}%` } },
        b.parts.map((p) => h("i", { style: { flex: `${p.n} 0 0px`, "--tone": toneOf(data, p.block) } }))));
    if (!later(b)) {
      col.addEventListener("pointerenter", () => show(b, col));
      col.addEventListener("click", () => show(b, col));
      col.addEventListener("focus", () => show(b, col));
      col.addEventListener("blur", () => on === col && hide());
    }
    plot.append(col);
  }
  plot.append(tip);
  plot.addEventListener("pointerleave", (e) => e.pointerType === "mouse" && hide());
  return h("div", { class: "bars", role: "group", "aria-label": "Blocked tries" },
    h("div", { class: "baxis" }, [max, max / 2, 0].map((v, i) => h("span", { style: { top: `${i * 50}%` } }, String(Math.round(v))))),
    plot,
    h("span"),
    h("div", { class: "blabels" }, bars.map((b, i) => {
      const [text, cls] = tick(b, i) ?? ["", ""];
      return h("span", { class: `${cls}${isNow(b) ? " now" : ""}`.trim() }, text);
    })));
}

function heatmap(data) {
  const cells = [];
  cells.push(h("span", { class: "hm-corner" }));
  for (let hr = 0; hr < 24; hr++) cells.push(h("span", { class: "hm-hour" }, hr % 3 === 0 ? String(hr).padStart(2, "0") : ""));
  data.heat.forEach((row, d) => {
    cells.push(h("span", { class: "hm-day" }, WEEKDAY[d]));
    row.forEach((n, hr) => {
      const level = n === 0 || data.heatMax === 0 ? 0 : Math.min(5, Math.ceil((n / data.heatMax) * 5));
      cells.push(h("span", { class: `hm-cell l${level}`, title: `${WEEKDAY[d]} ${hh(hr)} · ${n}` }));
    });
  });
  return h("div", { class: "heatmap", role: "img", "aria-label": "Blocked tries by weekday and hour" }, cells);
}

/** A block's tries day by day over the range, small, in its tone (not on Today: one day has no line). */
function spark(values, tone) {
  if (!values.some((v) => v > 0)) return null; // none: its faint 0 says so
  const W = 72, H = 20;
  const max = Math.max(1, ...values);
  const x = (i) => (values.length === 1 ? W / 2 : (i * W) / (values.length - 1));
  const y = (v) => H - 1.5 - (v / max) * (H - 3);
  const points = values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`);
  return s("svg", { viewBox: `0 0 ${W} ${H}`, className: "spark", preserveAspectRatio: "none", "aria-hidden": "true" },
    s("path", { d: `M0,${H} L${points.join(" L")} L${W},${H} Z`, className: "area", fill: tone }),
    s("polyline", { points: points.join(" "), className: "line", stroke: tone }));
}

/** A figure in the By block list: its column says what it is, or, too narrow for columns, its own label. Nought is faint. */
const fig = (cls, value, label, zero = false) => h("span", { class: `bb-fig ${cls}${zero ? " zero" : ""}` }, h("b", {}, value), h("small", {}, label));

/**
 * Each block in a row: its dot and name; its tries, with how they went over the range; its unlocks
 * and their time; the minutes bought past it; for a block with a daily limit, its use a day
 * against the limit; and its clean days in a row. A name shows that block alone.
 */
function byBlock(data, actions, view) {
  const withLimit = data.perBlock.some((p) => p.limit);
  const cls = `bb-row${withLimit ? " with-limit" : ""}`;
  const limitWord = view.range === "today" ? "today" : "a day";
  return h("div", { class: "bb" },
    h("div", { class: `${cls} bb-head`, "aria-hidden": "true" },
      h("span"), h("span", {}, "Tries"), h("span", {}, "Unlocks"), h("span", {}, "Open"), h("span", {}, "Bought"),
      withLimit && h("span", {}, view.range === "today" ? "Limit today" : "Limit a day"), h("span", {}, "Clean days")),
    data.perBlock.map((p) => {
      const limit = p.limit;
      const onlyToday = !!limit && !limit.tracked && limit.today != null;
      const perDay = limit?.tracked ? limit.used / (view.range === "today" ? 1 : limit.tracked) : onlyToday ? limit.today : null;
      const share = perDay == null ? 0 : perDay / limit.daily;
      const name = [cluster(p.logos, 30), h("span", { class: "bname" }, p.name)];
      return h("div", { class: `${cls}${limit ? " has-limit" : ""}` },
        view.block ? h("span", { class: "bb-name" }, name)
          : h("button", { type: "button", class: "bb-name", title: p.name, onclick: () => actions.pickBlock(p.id) }, name),
        h("span", { class: "bb-tries" }, view.range !== "today" && spark(p.spark, p.tone), fig("f-tries", String(p.tries), p.tries === 1 ? "try" : "tries", p.tries === 0)),
        fig("f-unlocks", String(p.unlocks), p.unlocks === 1 ? "unlock" : "unlocks", p.unlocks === 0),
        fig("f-open", duration(p.unlockedMs), "open", p.unlockedMs < 60_000),
        fig("f-bought", duration(p.boughtMs), "bought", p.boughtMs < 60_000),
        withLimit && h("span", { class: "bb-limit" }, limit && [
          h("span", { class: "value" }, `${perDay == null ? "–" : duration(perDay)} / ${duration(limit.daily)}`,
            h("small", { class: onlyToday ? "always" : "" }, ` ${onlyToday ? "today" : limitWord}`)),
          meter(share, perDay == null ? "" : limitTone(share), "thin")]),
        fig("f-clean", String(p.streak), p.streak === 1 ? "clean day" : "clean days", p.streak === 0));
    }));
}

/**
 * What was tried most, as Habits First's list of apps: its own icon, its name and, on every
 * device's view, each device's part; the count at the right, a thin bar under them. The first
 * eight, then "Show N more". Its block is in its title.
 */
function topList(data, view, actions) {
  const max = Math.max(1, ...data.top.map((t) => t.n));
  const split = view.device === "all";
  const shown = view.topAll ? data.top : data.top.slice(0, TOP_SHOWN);
  const rest = data.top.length - shown.length;
  return [
    h("div", { class: "ranked" }, shown.map((t) =>
      h("div", { class: "rank", title: data.byId.get(t.block)?.name ?? null },
        logo(t.icon, 28, { cls: "rank-logo" }),
        h("div", { class: "rank-main" },
          h("div", { class: "rank-top" },
            h("span", { class: "rank-name" }, t.name),
            split && h("span", { class: "rank-devs" },
              t.phone > 0 && h("span", { title: "Phone" }, deviceIcon("phone"), String(t.phone)),
              t.laptop > 0 && h("span", { title: "Laptop" }, deviceIcon("laptop"), String(t.laptop))),
            h("b", {}, String(t.n))),
          h("span", { class: "rank-bar" }, h("i", { style: { width: `${(t.n / max) * 100}%` } })))))),
    rest > 0 && h("button", { type: "button", class: "show-more", onclick: () => actions.moreTop?.() }, `Show ${rest} more`),
  ];
}

/**
 * Screen time: the range's time, and each device's beside its icon when there are two; a column
 * a day, split by device (not on Today); then the apps and the sites with their time, the first
 * eight of each.
 */
function screenCard(data, view, actions) {
  const st = data.screen;
  const devices = ["phone", "laptop"].filter((d) => st[d] > 0);
  const most = Math.max(1, ...st.days.map((d) => d.total));
  const list = (rows, site) => {
    const max = Math.max(1, ...rows.map((r) => r.ms));
    const shown = view.screenAll ? rows : rows.slice(0, TOP_SHOWN);
    return h("div", { class: "ranked" }, shown.map((r) =>
      h("div", { class: "rank" },
        logo(site ? siteKey(r.key) : targetKey(r.key, r.dev, false), 28, { cls: "rank-logo" }),
        h("div", { class: "rank-main" },
          h("div", { class: "rank-top" },
            h("span", { class: "rank-name" }, r.name),
            site && devices.length > 1 && h("span", { class: "rank-devs" }, devices.filter((d) => r[d] > 0).map((d) =>
              h("span", { title: d === "phone" ? "Phone" : "Laptop" }, deviceIcon(d), duration(r[d])))),
            h("b", {}, duration(r.ms))),
          h("span", { class: "rank-bar" }, h("i", { style: { width: `${(r.ms / max) * 100}%` } }))))));
  };
  const rest = Math.max(st.apps.length, st.sites.length) - TOP_SHOWN;
  return [
    devices.length > 1 && h("div", { class: "st-devs" }, devices.map((d) =>
      h("span", { title: d === "phone" ? "Phone" : "Laptop" }, deviceIcon(d), h("b", {}, duration(st[d]))))),
    data.range !== "today" && h("div", { class: "st-days", "aria-hidden": "true" }, st.days.map((d) =>
      h("span", { class: `st-day${d.day === data.today ? " now" : ""}`, title: `${dayTitle(d.day, data.today)} · ${duration(d.total)}` },
        h("span", { class: "st-col", style: { height: `${(d.total / most) * 100}%` } },
          ["laptop", "phone"].filter((k) => d[k] > 0).map((k) => h("i", { class: `st-${k}`, style: { flex: `${d[k]} 0 0px` } })))))),
    h("div", { class: "st-lists" },
      h("div", {}, h("h4", { class: "st-head" }, "Apps"), st.apps.length ? list(st.apps, false) : h("p", { class: "empty" }, "None")),
      h("div", {}, h("h4", { class: "st-head" }, "Sites"), st.sites.length ? list(st.sites, true) : h("p", { class: "empty" }, "None"))),
    rest > 0 && !view.screenAll && h("button", { type: "button", class: "show-more", onclick: () => actions.moreScreen?.() }, `Show ${rest} more`),
  ];
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

/** Each kind of event's icon, in a muted colour of what it means, at icon size only. */
const KIND = {
  blocked: ["blocked", "k-red"],
  unlock: ["lockOpen", "k-green"],
  habit: ["task", "k-green"],
  bought: ["moreTime", "k-amber"],
  break: ["coffee", "k-blue"],
  emergency: ["warning", "k-amber"],
};

/** A day's tries as one thin strip, each block its share, as long as the day is busy against the busiest. */
function triesStrip(d, data, most) {
  const order = [...data.byId.keys(), null].filter((id) => d.parts.has(id));
  return h("span", { class: "hstrip", "aria-hidden": "true" },
    d.tries > 0 && h("span", { class: "hstrip-fill", style: { width: `${(d.tries / most) * 100}%` } },
      order.map((id) => h("i", { style: { flex: `${d.parts.get(id)} 0 0px`, "--tone": toneOf(data, id) } }))));
}

/** "26 tries · 2 unlocks · 18 min open": a day's head, leaving out what didn't happen. */
const dayFigures = (d) => [
  `${d.tries} ${d.tries === 1 ? "try" : "tries"}`,
  d.unlocks > 0 && `${d.unlocks} ${d.unlocks === 1 ? "unlock" : "unlocks"}`,
  d.unlockedMs >= 60_000 && `${duration(d.unlockedMs)} open`,
  d.boughtMs >= 60_000 && `${duration(d.boughtMs)} bought`,
].filter(Boolean).join(" · ");

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
      return h("div", { class: `hday${open ? " open" : ""}` },
        h("button", { type: "button", class: "hday-head", "aria-expanded": String(open), onclick: () => actions.toggleDay(d.day, !open) },
          icon(open ? "chevronDown" : "chevronRight", "chev"),
          h("b", {}, dayTitle(d.day, data.today)),
          triesStrip(d, data, most),
          h("span", { class: "hday-figs" }, dayFigures(d))),
        open && h("div", { class: "hrows" }, d.rows.map((r) => {
          const [kindIcon, kindTone] = KIND[r.t] ?? ["globe", ""];
          // A try shows what was tried, by its own icon; everything else its kind's.
          return h("div", { class: `hrow k-${r.t}` },
            h("span", { class: "htime" }, r.n > 1 ? `${clock.format(r.from)}–${clock.format(r.at)}` : clock.format(r.at)),
            h("span", { class: "hdev", title: r.dev === "phone" ? "Phone" : r.dev === "laptop" ? "Laptop" : "Both" }, deviceIcon(r.dev)),
            r.t === "blocked" && r.icon ? h("span", { class: "hkind" }, logo(r.icon, 20)) : h("span", { class: `hkind ${kindTone}`.trim() }, icon(kindIcon)),
            h("span", { class: "htext" }, rowText(r)),
            h("span", { class: "hblock" }, r.blocks.filter((id) => data.byId.has(id)).slice(0, 1).map((id) => named(data, id))));
        })));
    }),
    data.timeline.length > view.historyDays && h("button", { type: "button", class: "btn more", onclick: actions.more }, "More"),
  ];
}

/**
 * The Insights page into [root]. [view]: {range, device, block, historyDays, topAll}; [actions]:
 * {setRange, setDevice, pickBlock (an id, or null to show every block), more, moreTop, toggleDay
 * (a day, open or not)}. Habits First's order: the period at a glance, then what was tried most
 * by its icons, beside when; then each block, and the history.
 */
export function renderInsights(root, input, view, actions) {
  const data = compute(input, view);
  const picked = view.block && data.byId.get(view.block);
  const peak = data.peak;
  root.replaceChildren(
    h("div", { class: "ins-controls" },
      seg([["today", "Today"], ["week", "7 days"], ["month", "30 days"]], view.range, actions.setRange),
      seg([["all", "All"], ["phone", "Phone"], ["laptop", "Laptop"]], view.device, actions.setDevice, "devices"),
      picked && h("span", { class: "picked" }, cluster(picked.logos, 24), h("span", {}, picked.name),
        h("button", { type: "button", class: "icon-btn small", title: "Every block", "aria-label": "Every block", onclick: () => actions.pickBlock(null) }, icon("close")))),
    headline(data, picked),
    h("div", { class: "ins-grid" },
      h("section", { class: "card tops" }, cardHead("Most tried"),
        data.top.length ? topList(data, view, actions) : h("p", { class: "empty" }, "Nothing yet")),
      h("div", { class: "ins-col" },
        h("section", { class: "card" }, cardHead("Blocked tries"), barsChart(data)),
        h("section", { class: "card" },
          cardHead("When", peak && [`${WEEKDAY[peak.day]} ${hh(peak.hour)} · `, h("b", {}, String(peak.n))]),
          heatmap(data))),
      data.perBlock.length > 0 && h("section", { class: "card wide" }, cardHead("By block"), byBlock(data, actions, view)),
      data.screen && h("section", { class: "card wide screen" }, cardHead("Screen time", duration(data.screen.total)), screenCard(data, view, actions))),
    h("section", { class: "card history" }, cardHead("History"), historyList(data, view, actions, input.zone)));
}
