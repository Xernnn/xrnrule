// xrnrule extension: the toolbar popup. What xrnrule says about the page in front, whether it's
// answering at all, and a way into it (the laptop's local page, or the phone app).

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
