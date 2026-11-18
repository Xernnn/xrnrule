// xrnrule status page: what a block in the shared state (docs/domain-contract.md §2.4) is doing
// now, by the engine's rules (BlockEngine.isBlocking and blockReason, §2.2). No DOM here, so
// server/status.test.mjs loads it as it is. The page isn't a device: what only a device knows (its
// own open run, a wait before opening, which device it is) isn't in the shared state, so a block
// is read as it holds on the devices it names (I42), and a per-device limit by the device that
// has used the most of it (I33).

const DAY_MS = 86400000;
const ISO_DAY = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

/** Times as the viewing browser reads them. */
export const timeFmt = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

export const minutes = (ms) => Math.round(ms / 60000);

/**
 * The zone the devices' days and hours go by (I54): the one a phone set itself, the latest; the
 * laptop follows it. Undefined (this browser's own) until a phone has said.
 */
export function sharedZone(doc) {
  let best = null;
  for (const z of Object.values(doc?.zones ?? {})) if (z?.zone && (!best || z.atWall > best.atWall)) best = z;
  return best?.zone;
}

const fmtCache = new Map();
function partsIn(zone, ms) {
  let f = fmtCache.get(zone);
  if (!f) {
    const opts = { hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric", second: "numeric", weekday: "short" };
    try {
      f = new Intl.DateTimeFormat("en-US", { ...opts, timeZone: zone });
    } catch {
      f = new Intl.DateTimeFormat("en-US", opts); // a zone this browser doesn't know: its own
    }
    fmtCache.set(zone, f);
  }
  const p = Object.fromEntries(f.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { y: +p.year, m: +p.month, d: +p.day, hour: +p.hour % 24, minute: +p.minute, second: +p.second, iso: ISO_DAY[p.weekday] };
}

/** [now] where the devices are: its ISO weekday and minute of the day. */
function clockOf(doc, now) {
  const p = partsIn(sharedZone(doc), now.getTime());
  return { iso: p.iso, minute: p.hour * 60 + p.minute };
}

/** The devices' today (I54): its epoch day, and when it began (Clock.startOfLocalDayMs). */
export function today(doc, now) {
  const zone = sharedZone(doc);
  const p = partsIn(zone, now.getTime());
  const epochDay = Date.UTC(p.y, p.m - 1, p.d) / DAY_MS;
  // Midnight there: its wall time read as UTC, less the zone's offset then (twice, for a DST change).
  const wallAsUtc = (ms) => {
    const q = partsIn(zone, ms);
    return Date.UTC(q.y, q.m - 1, q.d, q.hour, q.minute, q.second);
  };
  let dayStart = epochDay * DAY_MS;
  for (let i = 0; i < 2; i++) dayStart = epochDay * DAY_MS - (wallAsUtc(dayStart) - dayStart);
  return { dayStart, epochDay };
}

/** The epoch day [ms] falls on where the devices are. */
function dayOf(doc, ms) {
  const p = partsIn(sharedZone(doc), ms);
  return Date.UTC(p.y, p.m - 1, p.d) / DAY_MS;
}

/** BlockEngine.scheduleActive (I20, I30): any of its windows; one past midnight belongs to the day it starts on. */
function scheduleActive(schedule, clock) {
  const days = schedule?.activeDays ?? [1, 2, 3, 4, 5, 6, 7];
  return windowActive(days, schedule?.activeWindow, clock) ||
    (schedule?.moreWindows ?? []).some((w) => windowActive(w.days, w.window, clock));
}

function windowActive(days, w, { iso, minute }) {
  if (!w) return days.includes(iso);
  if (w.startMinuteOfDay <= w.endMinuteOfDay) return days.includes(iso) && minute >= w.startMinuteOfDay && minute < w.endMinuteOfDay;
  if (minute >= w.startMinuteOfDay) return days.includes(iso);
  if (minute < w.endMinuteOfDay) return days.includes(iso === 1 ? 7 : iso - 1);
  return false;
}

function hhmm(minuteOfDay) {
  return `${String(Math.floor(minuteOfDay / 60)).padStart(2, "0")}:${String(minuteOfDay % 60).padStart(2, "0")}`;
}

/** I39: locked until its lock's time. */
export const isLocked = (block, wall) => !!block.lock && wall < block.lock.untilWall;

/** Model.Block's derived rules (I32, I44). */
export function rulesOf(block) {
  const mode = block.mode ?? "HABIT";
  const daily = block.dailyAllowanceMs ?? 0;
  const opens = block.opensPerDay ?? 0;
  const row = (mode === "ALLOWANCE" || mode === "HABIT") && (block.sessionMs ?? 0) > 0 && (block.sessionBreakMs ?? 0) > 0;
  const hasLimit = mode === "ALLOWANCE" || (mode === "HABIT" && (daily > 0 || row || opens > 0));
  const limitFirst = mode === "HABIT" && (daily > 0 || opens > 0) && !block.habitFirst;
  return { mode, daily, opens, row, hasLimit, limitFirst };
}

/**
 * Today's use of [block] by the devices (I19, I52): minutes and opens, all of them together, or,
 * each device its own (I33), the one that has used the most.
 */
export function useToday(doc, block, now) {
  const day = today(doc, now).epochDay;
  const uses = Object.values(doc.allowance?.[block.id] ?? {}).filter((u) => dayOf(doc, u.dayStartWall) === day);
  const add = block.perDevice ? Math.max : (a, b) => a + b;
  return {
    ms: uses.reduce((sum, u) => add(sum, u.ms ?? 0), 0),
    opens: uses.reduce((sum, u) => add(sum, u.opens ?? 0), 0),
  };
}

/** I32: the end of a forced break on any device, or 0. */
function rowBreakUntil(doc, block) {
  return Math.max(0, ...Object.values(doc.allowance?.[block.id] ?? {}).map((u) => u.run?.breakUntilWall ?? 0));
}

/** When minutes bought past this block (I34) run out, or 0; none count while it's locked (I50). */
function bought(doc, block, wall) {
  const g = doc.overrides?.[block.id];
  if (!g || isLocked(block, wall)) return 0;
  return wall < g.startedAtWall + g.durationMs ? g.startedAtWall + g.durationMs : 0;
}

/** A HABIT block's unlock still running, or null (I41: a self-paced one by its use, within its day). */
function openSession(doc, block, now) {
  const s = doc.sessions?.[block.id];
  if (!s) return null;
  if (!s.selfPaced) return now.getTime() < s.startedAtWall + s.durationMs ? s : null;
  const use = Object.values(s.usedMs ?? {}).reduce((a, b) => a + b, 0);
  return use < s.durationMs && s.startedAtWall >= today(doc, now).dayStart ? s : null;
}

/** [class, text]: what [block] is doing now, the firmest reason first (BlockEngine.blockReason). */
export function blockStatus(doc, block, now) {
  const wall = now.getTime();
  const clock = clockOf(doc, now);
  const { mode, daily, opens, row, hasLimit, limitFirst } = rulesOf(block);
  if (block.enabled === false) return ["idle", "Turned off"];

  // I25, I50: a break lifts a SOFT block that is neither a lockdown nor locked.
  const pause = doc.pause;
  const pauseEnds = pause ? pause.startedAtWall + pause.durationMs : 0;
  const pausable = (block.enforcementTier ?? "SOFT") === "SOFT" && mode !== "LOCKDOWN" && !isLocked(block, wall);
  if (pausable && wall < pauseEnds) return ["open", `On a break until ${timeFmt.format(pauseEnds)}`];

  if (mode === "LOCKDOWN") {
    const l = doc.lockdowns?.[block.id];
    return l && wall < l.startedAtWall + l.durationMs
      ? ["blocked", `Locked down until ${timeFmt.format(l.startedAtWall + l.durationMs)}`]
      : ["idle", "Not running"];
  }

  const scheduleOn = scheduleActive(block.schedule, clock);
  const boughtUntil = bought(doc, block, wall);
  // I44: blocked hours, or a SCHEDULE block's schedule; only bought minutes lift them.
  const inHours = mode === "SCHEDULE" ? scheduleOn : !!block.blockedHours && scheduleActive(block.blockedHours, clock);
  if (inHours && !boughtUntil) return ["blocked", mode === "SCHEDULE" ? "Blocked by its schedule" : "Blocked by its hours"];
  if (mode === "SCHEDULE") {
    return boughtUntil ? ["open", `Bought time until ${timeFmt.format(boughtUntil)}`] : ["idle", "Outside its hours"];
  }

  // I32: a forced break holds through bought minutes and an unlock alike.
  const breakUntil = row ? rowBreakUntil(doc, block) : 0;
  if (hasLimit && scheduleOn && breakUntil > wall) return ["blocked", `On a break until ${timeFmt.format(breakUntil)}`];

  // I19, I52: the day's minutes or opens gone. A habit gets past that only with its limit first.
  const use = useToday(doc, block, now);
  const minutesGone = daily > 0 && use.ms >= daily;
  const opensGone = opens > 0 && use.opens >= opens;
  const spent = minutesGone || opensGone;
  const usedUp = minutesGone
    ? `Used up: ${minutes(use.ms)} of ${minutes(daily)} min today`
    : `Used up: ${use.opens} of ${opens} opens today`;
  if (hasLimit && !limitFirst && scheduleOn && spent && !(mode === "ALLOWANCE" && boughtUntil)) return ["blocked", usedUp];

  if (mode === "HABIT" && scheduleOn && (!limitFirst || spent)) {
    const session = openSession(doc, block, now);
    if (session) {
      return session.selfPaced
        ? ["open", `Open · ${minutes(session.durationMs - Object.values(session.usedMs ?? {}).reduce((a, b) => a + b, 0))} min of use left`]
        : ["open", `Open until ${timeFmt.format(session.startedAtWall + session.durationMs)}`];
    }
    const credit = doc.credits?.[block.id];
    if (credit && wall < credit.expiresAtWall) {
      return ["earned", `Earned · ${minutes(credit.amountMs)} min ready, until ${timeFmt.format(credit.expiresAtWall)}`];
    }
    return ["blocked", "Blocked until you do the habit"];
  }

  if (!scheduleOn) return ["idle", "Outside its hours"];
  if (boughtUntil) return ["open", `Bought time until ${timeFmt.format(boughtUntil)}`];
  if (daily > 0) return ["open", `${minutes(daily - use.ms)} of ${minutes(daily)} min left today`];
  if (opens > 0) return ["open", `${opens - use.opens} of ${opens} opens left today`];
  return ["open", `At most ${minutes(block.sessionMs ?? 0)} min in a row`];
}

/** The card's chips: its mode, and what else holds it (I39, I42). */
export function chips(block, now) {
  const out = [(block.mode ?? "HABIT").toLowerCase()];
  if ((block.enforcementTier ?? "SOFT") === "HARDCORE") out.push("hardcore");
  if (block.devices === "PHONE" || block.devices === "LAPTOP") out.push(block.devices.toLowerCase());
  if (isLocked(block, now.getTime())) out.push("locked");
  return out;
}

export const windows = (s) => [s?.activeWindow, ...(s?.moreWindows ?? []).map((x) => x.window)]
  .filter(Boolean).map((w) => `${hhmm(w.startMinuteOfDay)}–${hhmm(w.endMinuteOfDay)}`);

export function unlockMethod(block, items) {
  const { mode, daily, opens, row, hasLimit } = rulesOf(block);
  if (mode === "SCHEDULE") {
    const w = windows(block.schedule);
    return w.length ? `Blocked ${w.join(", ")}` : "Blocked all day on its days";
  }
  if (mode === "LOCKDOWN") return `${minutes(block.lockdownMs ?? 0)} min lockdown`;
  const parts = [];
  if (block.blockedHours) {
    const w = windows(block.blockedHours);
    parts.push(w.length ? `Blocked ${w.join(", ")}` : "Blocked all day on its days");
  }
  if (hasLimit) {
    if (daily > 0) parts.push(`${minutes(daily)} min a day`);
    if (opens > 0) parts.push(`${opens} opens a day`);
    if (row) parts.push(`${minutes(block.sessionMs)} min in a row, then ${minutes(block.sessionBreakMs)} off`);
    if (block.perDevice) parts.push("each device its own");
  }
  if (mode === "HABIT") {
    if (hasLimit && block.habitFirst) parts.push("habit first");
    parts.push(habitMethod(block, items));
  }
  return parts.join(" · ");
}

function habitMethod(block, items) {
  const t = block.trigger ?? {};
  const reward = `opens for ${minutes(block.reward?.unlockDurationMs ?? 300000)} min`;
  const timer = `${minutes(t.requiredAmount ?? 0)} min timer`;
  if (t.type === "TIMER") return `${timer}, ${reward}`;
  const count = t.requiredAmount === -1 || t.requiredAmount == null ? "all" : t.requiredAmount;
  if (t.type === "CHECKLIST") return `${count === "all" ? "Every" : count} item${count === 1 ? "" : "s"} of ${items.length}, ${reward}`;
  return `${timer} ${t.combineMode === "OR" ? "or" : "and"} every item, ${reward}`;
}
