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
      result.textContent = r?.ok ? "Saved" : "Saved · xrnrule not answering";
    });
  });
})();

// Where new versions come from (background.js, "updating itself"), and a button that looks now.
// An installType of "development" is an unpacked load.
const UPDATE_RESULT = {
  reloading: (r) => `Reloading ${r.version}`,
  update_available: (r) => `Installing ${r.version}`,
  no_update: () => "Up to date",
  throttled: () => "Try again later",
  no_update_url: () => (GECKO ? "No update address" : "No update address · install the .crx"),
  unsupported: () => "Not supported here",
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
    blurb.textContent = `${version} · temporary add-on`;
  } else if (installType === "development") {
    blurb.textContent = `${version} · unpacked`;
  } else if (GECKO && !updateUrl) {
    blurb.textContent = `${version} · .xpi`;
  } else if (updateUrl) {
    blurb.textContent = `${version} · .crx · ${new URL(updateUrl).host}`;
  } else {
    blurb.textContent = `${version} · .zip · never updates`;
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
