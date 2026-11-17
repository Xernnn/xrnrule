// xrnrule extension: the page a blocked site turns into on the phone, where the real block
// screen is xrnrule's overlay on top of the browser (the laptop daemon has a block page of its
// own). The phone app serves this same page at http://127.0.0.1:47321/block, because Quetta
// refuses to open an extension page for a link that came from another app; the extension's own
// copy is for when the app doesn't answer.
//
// It stands in for the site underneath the overlay: while it's the tab you're looking at, it
// keeps telling the app that site is in front, so the overlay stays and a timer habit counts.
// It goes back to the site the moment it opens, and leaves (steps back, or closes its tab) when
// you choose to leave the site on the overlay.

(() => {
  const X = self.Xrn;
  const params = new URLSearchParams(location.search);
  const site = params.get("site") || "";
  // Raw, as the redirect put it there: decoding could break a URL with a "%" of its own. Only
  // followed back to the site this page stands in for: any site may open this page, and could
  // otherwise send a visitor through it to an address of its choosing.
  const back = location.hash.slice(1);
  const backHere = /^https?:/.test(back) && X.matches(X.locationOf(back) || "", site.split("/")[0]);
  const android = /Android/i.test(navigator.userAgent);
  /** Served by xrnrule itself, not the extension: it asks xrnrule directly. */
  const served = location.protocol === "http:";
  /** Faster than a web page's beat: "Leave" on the overlay should land within a second. */
  const BEAT_MS = 1000;

  document.getElementById("title").textContent = site ? `${site} is blocked` : "Blocked";
  document.title = site ? `${site} is blocked · xrnrule` : "Blocked · xrnrule";

  const open = document.getElementById("open");
  if (android) {
    // The app's main screen; Chromium on Android opens intent: links from a tap.
    open.href = "intent://open#Intent;scheme=xrnrule;package=com.xern.xrnrule;end";
    open.hidden = false;
  }

  const leaveButton = document.getElementById("leave");
  /** Without xrnrule there's no telling when it's back, only that it stays blocked. */
  const offline = "xrnrule offline";

  /** What xrnrule says about the site, or that it says nothing. */
  function show(v) {
    const off = !!v.offline;
    const when = document.getElementById("back");
    when.textContent = off ? "" : v.back ?? "";
    when.hidden = !when.textContent;
    const why = document.getElementById("why");
    why.textContent = off ? offline : v.why ?? "";
    why.hidden = !why.textContent;
    // Leaving is the point of this page, unless opening xrnrule is what gets it going again.
    leaveButton.classList.toggle("primary", !(off && android));
    open.classList.toggle("primary", off && android);
  }

  let gone = false;
  let staying = null;

  /** Off this page: back where you came from, or, with nowhere to go back to, the tab closes. */
  function leave() {
    gone = true;
    if (history.length > 1) {
      history.back();
      // Still here a while later: there was nothing behind this page, only ahead of it.
      staying = setTimeout(close, 3000);
    } else {
      close();
    }
  }

  function close() {
    // Allowed for a tab with one page in its history, such as one a link from another app opened.
    window.close();
    setTimeout(() => location.replace("about:blank"), 300);
  }

  leaveButton.addEventListener("click", leave);
  window.addEventListener("pagehide", () => clearTimeout(staying));
  // Back to this page from the history cache (Forward after leaving): it stands in again.
  window.addEventListener("pageshow", (e) => {
    if (!e.persisted) return;
    gone = false;
    beat();
  });

  /** xrnrule's answer about [site], from the extension or, served, straight from xrnrule. */
  function ask(focused, reply) {
    const msg = { host: site, focused, standIn: true };
    if (!served) {
      chrome.runtime.sendMessage({ type: "beat", ...msg }, (v) => {
        void chrome.runtime.lastError;
        reply(v);
      });
      return;
    }
    fetch("/ext/heartbeat", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Xrnrule": "1" },
      body: JSON.stringify(msg),
      cache: "no-store",
    })
      .then((res) => (res.ok ? res.json() : null))
      .then(reply, () => reply({ site, blocked: true, offline: true }));
  }

  function beat() {
    // A tab in the background says nothing: the site isn't in front, so neither is the overlay.
    if (gone || !site || document.visibilityState !== "visible") return;
    ask(document.hasFocus(), (v) => {
      if (!v || gone) return;
      show(v);
      if (v.leave) {
        leave();
      } else if (!v.blocked && !v.offline) {
        gone = true;
        if (backHere) location.replace(back);
        else document.getElementById("title").textContent = `${site} is open again`;
      } else if (v.page && !location.href.startsWith(v.page)) {
        // xrnrule answers again and has a block page of its own: that one says more.
        gone = true;
        location.replace(X.blockUrl(v.page, null, site, back));
      }
    });
  }

  beat();
  setInterval(beat, BEAT_MS);
  document.addEventListener("visibilitychange", beat);
})();
