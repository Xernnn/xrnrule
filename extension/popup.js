// xrnrule extension: the toolbar popup. What xrnrule says about the page in front, whether it's
// answering at all, and a way into it (the laptop's local page, or the phone app).

// Firefox (Zen) treats an MV3 extension's host permissions as optional: they can be withheld at
// install or switched off in about:addons, and then neither the pages nor xrnrule's address can be
// reached. Chromium always grants them, so this stays hidden there. The request must come from the
// click itself, before anything is awaited.
(async () => {
  const access = document.getElementById("access");
  const origins = self.Xrn.SITE_ORIGINS;
  document.getElementById("grant").addEventListener("click", () => {
    chrome.permissions.request({ origins }).then((ok) => { if (ok) access.hidden = true; }, () => {});
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
