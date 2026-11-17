// xrnrule extension: the one setting it has, the port xrnrule listens on, and how it updates.

// Withheld site access, as in the popup (popup.js). The request must come from the click itself.
(async () => {
  const access = document.getElementById("access");
  const origins = self.Xrn.SITE_ORIGINS;
  document.getElementById("grant").addEventListener("click", () => {
    chrome.permissions.request({ origins }).then((ok) => { if (ok) access.hidden = true; }, () => {});
  });
  access.hidden = await chrome.permissions?.contains({ origins }).catch(() => true) ?? true;
})();

(async () => {
  const input = document.getElementById("port");
  const result = document.getElementById("result");
  const { port } = await chrome.storage.local.get("port");
  if (port) input.value = port;
  document.getElementById("save").addEventListener("click", () => {
    const value = Number(input.value) || null;
    chrome.runtime.sendMessage({ type: "setPort", port: value }, (r) => {
      void chrome.runtime.lastError;
      result.textContent = r?.ok ? "Saved. xrnrule is answering." : "Saved, but xrnrule isn't answering on that port.";
    });
  });
})();

// Where new versions come from (background.js, "updating itself"), and a button that looks now.
// An installType of "development" is an unpacked load.
const UPDATE_RESULT = {
  reloading: (r) => `Version ${r.version} is in the folder: reloading. This page closes; open it again.`,
  update_available: (r) => `Version ${r.version} found: installing. This page closes when it's in; open it again.`,
  no_update: () => "Up to date.",
  throttled: () => "The browser looked a moment ago and won't again yet. It also looks on its own every few hours.",
  no_update_url: () => (GECKO
    ? "This copy has no update address: Firefox and Zen take new versions from wherever the .xpi came from."
    : "This copy has no update address: install the .crx from your home server instead."),
  unsupported: () => "This browser can't be asked to look now; it looks for new versions on its own.",
};
/** Firefox and its forks (Zen) have browser.runtime.getBrowserInfo; Chromium has neither. */
const GECKO = typeof browser !== "undefined" && !!browser.runtime?.getBrowserInfo;

(async () => {
  const blurb = document.getElementById("update-blurb");
  const result = document.getElementById("update-result");
  const button = document.getElementById("update-check");
  const { version, update_url: updateUrl } = chrome.runtime.getManifest();
  const installType = await chrome.management?.getSelf().then((s) => s.installType, () => null);
  if (installType === "development" && GECKO) {
    blurb.textContent = `Version ${version}, loaded as a temporary add-on. It reloads itself when its folder gets a new version, and the browser removes it when it closes: install a signed .xpi to keep it.`;
  } else if (installType === "development") {
    blurb.textContent = `Version ${version}, loaded unpacked from a folder. It reloads itself when that folder gets a new version.`;
  } else if (GECKO && !updateUrl) {
    blurb.textContent = `Version ${version}, installed from an .xpi. Install a newer .xpi the same way to update it.`;
  } else if (updateUrl) {
    blurb.textContent = `Version ${version}, from ${new URL(updateUrl).host}. New versions install themselves: the browser looks every few hours, and Check now looks straight away.`;
  } else {
    blurb.textContent = `Version ${version}. A copy installed from a .zip never updates: install xrnrule.crx from your home server's /xrnrule/update/ instead.`;
  }
  button.addEventListener("click", () => {
    button.disabled = true;
    result.hidden = false;
    result.textContent = "Checking…";
    chrome.runtime.sendMessage({ type: "checkUpdate" }, (r) => {
      const error = chrome.runtime.lastError?.message;
      button.disabled = false;
      const say = r && UPDATE_RESULT[r.status];
      result.textContent = say ? say(r) : `Couldn't check: ${r?.error || error || "no answer"}`;
    });
  });
})();
