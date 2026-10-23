// xrnrule extension: what every part shares. Plain functions and constants, no chrome.*, so the
// tests (test/extension-test.mjs) load this file as it is. Classic script: it sets self.Xrn.

self.Xrn = (() => {
  /** Where xrnrule listens on this device: the laptop daemon, or the phone app. */
  const DEFAULT_PORT = 47321;
  /** How often an open page says it's still in front. The app forgets a site after 6 s of silence. */
  const HEARTBEAT_MS = 2500;
  /** Minutes left at which a page says so, once each. */
  const WARN_AT_MIN = [10, 5, 3, 1];
  /** Kinds of "left" that only run down while you're on the site; the others run by the clock. */
  const USE_KINDS = ["LIMIT", "ROW"];

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

  /** `youtube.com` covers `youtube.com` and `m.youtube.com`, but not `notyoutube.com`. */
  const matches = (host, target) => host === target || host.endsWith("." + target);

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

  return { DEFAULT_PORT, HEARTBEAT_MS, WARN_AT_MIN, USE_KINDS, CLOCK_LABEL, hostOf, matches, blockedBy, enginePage, blockUrl, mmss, warning };
})();
