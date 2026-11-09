// xrnrule extension: what every part shares. Plain functions and constants, no chrome.*, so the
// tests (test/extension-test.mjs) load this file as it is. Classic script: it sets self.Xrn.

self.Xrn = (() => {
  /** Where xrnrule listens on this device: the laptop daemon, or the phone app. */
  const DEFAULT_PORT = 47321;
  /** How often an open page says it's still in front. The app forgets a site after 6 s of silence. */
  const HEARTBEAT_MS = 2500;
  /** Minutes left at which a page says so, once each. */
  const WARN_AT_MIN = [10, 5, 3, 1];
  /** The clock stands out this long at each of those, and all through the last 30 s (CornerClock in :domain). */
  const STRONG_MS = 6000;
  const LAST_MS = 30_000;
  /** Kinds of "left" that only run down while you're on the site; the others run by the clock. */
  const USE_KINDS = ["LIMIT", "ROW"];
  /** The longest path kept of a page, as the app does. */
  const MAX_PATH = 200;
  /** How many redirect and allow rules go to the browser. */
  const MAX_RULES = 999;
  /** How long xrnrule may be gone before everything with a rule is blocked (I48). */
  const FAIL_CLOSED_MS = 60_000;
  /** The manifest's host_permissions: every web page, and xrnrule's own address on 127.0.0.1. */
  const SITE_ORIGINS = ["http://*/*", "https://*/*"];

  /** "https://www.YouTube.com/watch" -> "youtube.com"; null for anything that isn't a web page. */
  function hostOf(url) {
    try {
      const u = new URL(url);
      if (u.protocol !== "http:" && u.protocol !== "https:") return null;
      return u.hostname.toLowerCase().replace(/\.$/, "").replace(/^www\./, "") || null;
    } catch {
      return null;
    }
  }

  /** "https://www.YouTube.com/Shorts/?x=1" -> "youtube.com/shorts": what a page is matched by. */
  function locationOf(url) {
    const host = hostOf(url);
    if (!host) return null;
    return host + new URL(url).pathname.toLowerCase().replace(/\/+$/, "").slice(0, MAX_PATH);
  }

  const hostPart = (location) => location.split("/")[0];

  /**
   * `youtube.com` covers `youtube.com` and `m.youtube.com/anything`, but not `notyoutube.com`;
   * `youtube.com/shorts` covers that path and what's under it, on the same hosts.
   */
  function matches(location, target) {
    const host = hostPart(location);
    const targetHost = hostPart(target);
    if (host !== targetHost && !host.endsWith("." + targetHost)) return false;
    const want = target.slice(targetHost.length);
    if (!want) return true;
    const path = location.slice(host.length);
    return path === want || path.startsWith(want + "/");
  }

  /** The URL pattern of a target that has a path; one without leaves it to requestDomains. */
  function urlPattern(target) {
    const host = hostPart(target);
    const path = target.slice(host.length);
    if (!path) return "^https?://.*";
    const quote = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return `^https?://([^/]*\\.)?${quote(host)}${quote(path)}([/?#].*)?$`;
  }

  /**
   * The rule list as declarativeNetRequest rules, numbered from [base]: a redirect for each
   * blocked target, and above it an allow rule for each exception with a path, which
   * excludedRequestDomains can't say. [back] is the block page's "where it came from" part.
   */
  function redirectRules(rules, page, ownPage, base) {
    const out = [];
    const add = (rule) => { if (out.length < MAX_RULES) out.push({ id: base + out.length, ...rule }); };
    for (const r of rules?.blocked ?? []) {
      const hosts = (r.except || []).filter((e) => !e.includes("/"));
      add({
        priority: 1,
        // \\0 is the whole URL asked for: the block page goes back there once the site opens.
        action: { type: "redirect", redirect: { regexSubstitution: blockUrl(page, ownPage, r.domain, "\\0") } },
        condition: {
          regexFilter: urlPattern(r.domain),
          requestDomains: [hostPart(r.domain)],
          // An exception must hold here too, or the network layer would block music.youtube.com
          // while every other part lets it through.
          ...(hosts.length ? { excludedRequestDomains: hosts } : {}),
          resourceTypes: ["main_frame"],
        },
      });
      for (const e of (r.except || []).filter((x) => x.includes("/"))) {
        add({
          priority: 2,
          action: { type: "allow" },
          condition: { regexFilter: urlPattern(e), requestDomains: [hostPart(e)], resourceTypes: ["main_frame"] },
        });
      }
    }
    return out;
  }

  /** The rule blocking [host] in the engine's last rule list, or null: a domain matches and none of its exceptions. */
  function blockedBy(rules, host) {
    if (!rules || !host) return null;
    return (rules.blocked || []).find((r) => matches(host, r.domain) && !(r.except || []).some((e) => matches(host, e))) || null;
  }

  /**
   * The block page xrnrule names, if it is on xrnrule's own address ([engine], such as
   * `http://127.0.0.1:47321`); else null, which means ours. Whatever answers on that port is
   * believed about what's blocked, but if xrnrule were down and another program had taken the
   * port, it must not be able to send blocked sites to a page of its choosing.
   */
  function enginePage(page, engine) {
    try {
      const u = new URL(page);
      return u.origin === new URL(engine).origin ? u.href : null;
    } catch {
      return null;
    }
  }

  /**
   * Where a blocked page goes: the engine's block page when it has one (the laptop), else ours
   * (the phone, whose block screen is the app's overlay). The page it left rides in the hash,
   * so the block page can go back once the site opens.
   */
  function blockUrl(page, ownPage, site, back) {
    const base = `${page || ownPage}?site=${encodeURIComponent(site)}`;
    return back ? `${base}#${back}` : base;
  }

  /** 4:05, or 1:02 for an hour and two minutes. */
  function mmss(ms) {
    const t = Math.max(0, Math.ceil(ms / 1000));
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const s = t % 60;
    return h ? `${h}:${String(m).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
  }

  /** The corner clock's label for a kind of "left". */
  const CLOCK_LABEL = { LIMIT: "left today", ROW: "until a break", UNLOCK: "open", OVERRIDE: "bought", BREAK: "break" };

  const strongClock = (left) => left <= LAST_MS ||
    WARN_AT_MIN.some((min) => left <= min * 60_000 && left > min * 60_000 - STRONG_MS);

  /** The warning at [min] minutes left, in words. */
  function warning(kind, min, label) {
    const what = label || "this site";
    const mins = `${min} minute${min === 1 ? "" : "s"}`;
    switch (kind) {
      case "ROW": return `${mins} until a break from ${what}`;
      case "UNLOCK": return `${what} locks again in ${mins}`;
      case "OVERRIDE": return `Your bought time on ${what} ends in ${mins}`;
      case "BREAK": return `Your break ends in ${mins}`;
      default: return `${mins} left on ${what} today`;
    }
  }

  return { DEFAULT_PORT, SITE_ORIGINS, HEARTBEAT_MS, WARN_AT_MIN, strongClock, USE_KINDS, CLOCK_LABEL, FAIL_CLOSED_MS, hostOf, locationOf, matches, redirectRules, blockedBy, enginePage, blockUrl, mmss, warning };
})();
