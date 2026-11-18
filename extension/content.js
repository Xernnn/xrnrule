// xrnrule extension, in every web page. It decides nothing: it says the page is in front, acts on
// xrnrule's answer, and draws what that answer says is left.
//
//   - a heartbeat every 2.5 s while the page is visible, and at once on focus changes;
//   - blocked: off to the block page, replacing this one in history so Back doesn't return;
//   - a one-shot timer for the moment the time runs out, so the block lands then and not up to
//     a heartbeat late;
//   - the corner clock (if turned on in xrnrule's settings) and a warning at 10, 5, 3 and 1
//     minutes left.

(() => {
  const X = self.Xrn;
  const OWN_PAGE = chrome.runtime.getURL("block.html");

  let stopped = false;
  let deadline = null;
  /** The last answer, when it came, and time in front since (for kinds that run down with use). */
  let base = null;
  let baseAt = 0;
  let inFrontMs = 0;
  let lastFrame = 0;
  const warned = new Set();

  const inFront = () => document.visibilityState === "visible" && document.hasFocus();

  function send(msg) {
    return new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage(msg, (reply) => {
          void chrome.runtime.lastError; // the worker was restarting: try again next beat
          resolve(reply || null);
        });
      } catch {
        // The extension was reloaded under this page: this copy is orphaned.
        stopped = true;
        teardown();
        resolve(null);
      }
    });
  }

  /**
   * Sound from anywhere in this tab as the browser hears it, which the background passes on
   * (I47): an inner frame's player or WebAudio, which this script can't see.
   */
  let tabSounding = false;
  try {
    chrome.runtime.onMessage.addListener((msg) => {
      if (msg?.type !== "audible") return;
      tabSounding = !!msg.audible;
      beat();
    });
  } catch {} // orphaned: send() stops this copy

  /** Sound coming out of this page: muted previews and paused players don't count. */
  const sounding = () => tabSounding || [...document.querySelectorAll("video, audio")]
    .some((m) => !m.paused && !m.ended && !m.muted && m.volume > 0 && m.readyState > 2);

  /**
   * A page out of sight says nothing, unless it's playing: that's use like any other (I47), and
   * if the site gets blocked this is what stops it. Sound also keeps its timers running.
   */
  async function beat() {
    if (stopped) return;
    const front = inFront();
    const playing = !front && sounding();
    if (document.visibilityState !== "visible" && !playing) return;
    handle(await send({ type: "beat", host: X.locationOf(location.href), focused: front, playing }));
  }

  function handle(v) {
    if (!v || stopped) return;
    if (v.blocked) {
      // Not stopped here: a page can refuse to go (beforeunload, "Stay on page"), and then this
      // copy must beat on and send it off again, or it would never be counted or blocked again.
      // What it plays stops meanwhile.
      clearTimeout(deadline);
      for (const m of document.querySelectorAll("video, audio")) m.pause();
      location.replace(X.blockUrl(v.page, OWN_PAGE, v.site || X.locationOf(location.href), location.href));
      return;
    }
    base = v;
    baseAt = Date.now();
    inFrontMs = 0;
    lastFrame = baseAt;
    clearTimeout(deadline);
    const left = leftNow();
    if (left != null) deadline = setTimeout(beat, Math.min(left + 250, 2_147_000_000));
    // The clock runs only while something is left: most pages have no rule at all.
    if (left != null) {
      painter ??= setInterval(tick, 1000);
    } else {
      clearInterval(painter);
      painter = null;
    }
    paint();
    warn();
  }

  /** What's left now: use-kinds run down while the page is in front, the rest by the clock. */
  function leftNow() {
    const left = base?.left;
    if (!left) return null;
    const spent = X.USE_KINDS.includes(left.kind) ? inFrontMs : Date.now() - baseAt;
    return Math.max(0, left.ms - spent);
  }

  function warn() {
    const left = leftNow();
    if (left == null || !inFront()) return;
    for (const m of X.WARN_AT_MIN) {
      // Once per mark per page, as the countdown crosses it; a page opened at 4 minutes left
      // says so once, not for 10 and 5 as well.
      if (left <= m * 60_000 && left > (m - 1) * 60_000 && !warned.has(m)) {
        warned.add(m);
        toast(X.warning(base.left.kind, m, base.label));
      }
    }
  }

  // ---- the corner clock and the toast, in a shadow root so the page's styles can't reach them ----

  let host = null;
  let shadow = null;

  const CSS = `
    :host { all: initial; }
    .toast, .clock {
      position: fixed; z-index: 2147483647;
      font: 500 13px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
      color: #fff; background: #1d2433; border: 1px solid #38415a;
      border-radius: 10px; box-shadow: 0 8px 28px rgba(0,0,0,.4);
    }
    .toast {
      right: 16px; bottom: 16px; padding: 12px 14px;
      max-width: min(320px, calc(100vw - 32px));
      opacity: 0; transform: translateY(8px);
      transition: opacity .18s ease, transform .18s ease;
      pointer-events: none;
    }
    .toast.in { opacity: 1; transform: none; }
    .clock {
      display: none; align-items: center; gap: 7px; right: 12px; bottom: 12px;
      padding: 5px 10px 5px 8px; border-radius: 999px;
      font-variant-numeric: tabular-nums;
      opacity: .5; transition: opacity .3s ease;
      user-select: none;
    }
    .clock:hover, .clock.strong { opacity: 1; }
    .strong .t { font-weight: 800; }
    .clock.on { display: inline-flex; }
    .dot { width: 7px; height: 7px; border-radius: 50%; background: #6f9bf0; flex: none; }
    .warn .dot { background: #e0a44a; }
    .crit .dot { background: #e2705a; }
    .t { font-weight: 600; font-size: 12.5px; }
    .lbl { font-size: 10.5px; opacity: .65; }
  `;

  function ensureShadow() {
    // Made again if the page tore our host out (single-page apps rewriting the document do).
    if (host?.isConnected) return shadow;
    const root = document.documentElement || document.body;
    if (!root) return null;
    host = document.createElement("div");
    host.id = "xrnrule-host";
    shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = CSS;
    shadow.append(style, el("div", "toast"), el("div", "clock"));
    root.appendChild(host);
    return shadow;
  }

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function teardown() {
    clearTimeout(deadline);
    clearInterval(beats);
    clearInterval(painter);
    if (host?.isConnected) host.remove();
    host = null;
    shadow = null;
  }

  function toast(text) {
    const sh = ensureShadow();
    if (!sh) return;
    const t = sh.querySelector(".toast");
    t.textContent = text;
    requestAnimationFrame(() => t.classList.add("in"));
    setTimeout(() => t.classList.remove("in"), 5000);
  }

  function paint() {
    const left = leftNow();
    const show = base?.showClock && left != null && !document.fullscreenElement;
    if (!show && !host) return;
    const sh = ensureShadow();
    if (!sh) return;
    const node = sh.querySelector(".clock");
    if (!show) {
      node.className = "clock";
      return;
    }
    const tone = left <= 60_000 ? "crit" : left <= 5 * 60_000 ? "warn" : "";
    node.className = `clock on ${tone} ${X.strongClock(left) ? "strong" : ""}`.replace(/\s+/g, " ").trim();
    node.replaceChildren(el("span", "dot"), el("span", "t", X.mmss(left)), el("span", "lbl", X.CLOCK_LABEL[base.left.kind] ?? ""));
    node.title = base.label ? `${base.label}: ${X.mmss(left)} ${X.CLOCK_LABEL[base.left.kind] ?? ""}` : "";
  }

  // Once a second while something is left: count time in front (what xrnrule counts too) and repaint.
  let painter = null;
  function tick() {
    if (stopped) return;
    const now = Date.now();
    // Clamped: a throttled background timer can hand back a huge gap.
    if (inFront()) inFrontMs += Math.min(Math.max(0, now - lastFrame), 2000);
    lastFrame = now;
    if (base) {
      paint();
      warn();
    }
  }

  const beats = setInterval(beat, X.HEARTBEAT_MS);
  document.addEventListener("visibilitychange", beat);
  document.addEventListener("fullscreenchange", paint);
  window.addEventListener("focus", beat);
  window.addEventListener("blur", beat);
  window.addEventListener("pageshow", beat);

  // Ask before the page paints, so a site that's blocked shows the block page, not a flash of feed.
  beat();
})();
