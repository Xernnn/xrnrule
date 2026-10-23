// xrnrule extension: the block page a blocked site turns into on the phone (the laptop uses the
// daemon's own block page). On the phone the real block screen is xrnrule's overlay on top of
// the browser; this page stands in for the site underneath it: it keeps telling the app that
// site is in front, so the overlay stays and a timer habit counts, and it goes back to the site
// the moment it opens.

(() => {
  const X = self.Xrn;
  const params = new URLSearchParams(location.search);
  const site = params.get("site") || "";
  // Raw, as the redirect put it there: decoding could break a URL with a "%" of its own.
  const back = location.hash.slice(1);
  const android = /Android/i.test(navigator.userAgent);

  document.getElementById("title").textContent = site ? `${site} is blocked` : "Blocked";
  document.title = site ? `${site} is blocked · xrnrule` : "Blocked · xrnrule";

  const open = document.getElementById("open");
  if (android) {
    // The app's main screen; Chromium on Android opens intent: links from a tap.
    open.href = "intent://open#Intent;scheme=xrnrule;package=com.xern.xrnrule;end";
    open.hidden = false;
  }
  document.getElementById("back").addEventListener("click", () => {
    if (history.length > 1) history.back();
    else location.replace("about:blank");
  });

  let gone = false;
  function beat() {
    if (gone || !site) return;
    const focused = document.visibilityState === "visible" && document.hasFocus();
    chrome.runtime.sendMessage({ type: "beat", host: site, focused, standIn: true }, (v) => {
      void chrome.runtime.lastError;
      if (!v) return;
      document.getElementById("offline").hidden = !v.offline;
      if (!v.blocked && !v.offline) {
        gone = true;
        if (/^https?:/.test(back)) location.replace(back);
        else document.getElementById("title").textContent = `${site} is open again`;
      } else if (v.page && !location.href.startsWith(v.page)) {
        // The laptop daemon is back: its block page says more than this one.
        gone = true;
        location.replace(X.blockUrl(v.page, null, site, back));
      }
    });
  }

  beat();
  setInterval(beat, X.HEARTBEAT_MS);
  document.addEventListener("visibilitychange", beat);
})();
