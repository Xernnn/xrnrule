// xrnrule extension: the toolbar popup. What xrnrule says about the page in front, whether it's
// answering at all, and a way into it (the laptop's local page, or the phone app).

// Both kinds of browser let the host permissions be withheld: Firefox (Zen) at install or in
// about:addons, Chromium with "Site access: on specific sites". Then nothing is blocked or counted,
// and the background says nothing to xrnrule, so xrnrule takes this browser as one without the
// extension (I49). The request must come from the click itself, before anything is awaited.
(async () => {
  const access = document.getElementById("access");
  const origins = self.Xrn.SITE_ORIGINS;
  document.getElementById("grant").addEventListener("click", () => {
    chrome.permissions.request({ origins }).then((ok) => { if (ok) location.reload(); }, () => {});
  });
  access.hidden = await chrome.permissions?.contains({ origins }).catch(() => true) ?? true;
})();

(async () => {
  const X = self.Xrn;
  const $ = (id) => document.getElementById(id);
  const android = /Android/i.test(navigator.userAgent);
  const ask = (msg) => new Promise((resolve) => chrome.runtime.sendMessage(msg, (r) => { void chrome.runtime.lastError; resolve(r); }));

  const engine = await ask({ type: "engine" });
  $("open").href = android ? "intent://open#Intent;scheme=xrnrule;package=com.xern.xrnrule;end" : `${engine}/`;

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const host = X.locationOf(tab?.url || "");
  if (!host) {
    $("site").textContent = "This page";
    $("state").textContent = "Not a website: nothing to block here.";
  } else {
    $("site").textContent = X.hostOf(tab.url);
  }

  const v = await ask({ type: "check", host });
  if (v?.access === false) {
    $("state").hidden = true; // the access line above says it
    return;
  }
  $("engine").textContent = v?.reachable
    ? `Connected to xrnrule on this ${android ? "phone" : "laptop"}.`
    : "xrnrule isn't running. Open it to get your blocks back: in a minute, everything with a rule is blocked.";
  $("engine").classList.toggle("bad", !v?.reachable);
  if (!host) return;
  if (v?.blocked) $("state").textContent = "Blocked.";
  else if (v?.left) $("state").textContent = `${X.mmss(v.left.ms)} ${X.CLOCK_LABEL[v.left.kind] ?? "left"}${v.label ? ` · ${v.label}` : ""}`;
  else if (v?.reachable) $("state").textContent = "No rule covers this site.";
  else $("state").textContent = "Not blocked.";
})();
