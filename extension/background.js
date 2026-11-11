// xrnrule extension, background: the go-between for the pages and xrnrule on 127.0.0.1 (the
// laptop daemon, or the phone app). xrnrule decides what's blocked, the same blocks on every
// device; this only reports the site in front, passes the answer back, and keeps the browser in
// line with the last rule list it got:
//
//   - redirect rules (declarativeNetRequest) send a new visit to a blocked site straight to the
//     block page, before anything loads;
//   - tabs already open on a site that just got blocked are moved there too;
//   - while xrnrule doesn't answer, what it last said was blocked stays blocked (on our own block
//     page, since xrnrule's can't load then), and the toolbar icon shows "!"; after a minute of
//     that, everything with a rule is blocked, so stopping xrnrule isn't a way past a limit.

// Chromium runs this as a service worker and needs importScripts; Firefox (Zen) runs it as an event
// page, with shared.js listed before it in the manifest's background.scripts, and has no importScripts.
if (typeof importScripts === "function" && !self.Xrn) importScripts("shared.js");

const { DEFAULT_PORT, FAIL_CLOSED_MS, CALL_TIMEOUT_MS, locationOf, redirectRules, failClosed, blockedBy, enginePage, blockUrl } = self.Xrn;
const OWN_PAGE = chrome.runtime.getURL("block.html");
/** Our redirect rules' ids. Each rewrite removes the whole range first. */
const DNR_BASE = 1000;
/** Whether we may run in incognito windows, for xrnrule to warn about; null if the browser won't say. */
let incognito = null;
chrome.extension?.isAllowedIncognitoAccess?.().then((allowed) => { incognito = allowed; }, () => {});

async function engineUrl() {
  const { port } = await chrome.storage.local.get("port");
  return `http://127.0.0.1:${port || DEFAULT_PORT}`;
}

async function call(path, body) {
  const engine = await engineUrl();
  // Stopped (kill -STOP) or hung, xrnrule still takes the connection and never answers: without
  // a limit the call would wait for good, and with one refresh at a time, it would never be
  // counted as gone, nor everything with a rule blocked a minute on (I48).
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), CALL_TIMEOUT_MS);
  try {
    const res = await fetch(`${engine}${path}`, {
      method: body === undefined ? "GET" : "POST",
      headers: { "Content-Type": "application/json", "X-Xrnrule": "1" },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      signal: abort.signal,
    });
    if (!res.ok) throw new Error(`xrnrule answered ${res.status}`);
    const answer = await res.json();
    // Rules and verdicts name the block page: only one on xrnrule's own address is followed.
    if (answer && typeof answer === "object" && "page" in answer) answer.page = enginePage(answer.page, engine);
    return answer;
  } finally {
    clearTimeout(timer);
  }
}

let reachable = null;
/** When xrnrule stopped answering (kept, since the worker comes and goes), or null. */
let offlineSince = null;

/** I48: what counts as blocked now: after a minute without xrnrule, everything with a rule. */
function inForce(rules) {
  if (!rules || offlineSince == null || Date.now() - offlineSince < FAIL_CLOSED_MS) return rules;
  return failClosed(rules);
}

let offlineRead = false;
async function noteReachable(ok) {
  if (!offlineRead) {
    offlineSince ??= (await chrome.storage.local.get("offlineSince")).offlineSince ?? null;
    offlineRead = true;
  }
  if (ok && offlineSince != null) {
    offlineSince = null;
    await chrome.storage.local.remove("offlineSince");
    refreshRules(); // back to what's blocked now, not everything with a rule
  } else if (!ok && offlineSince == null) {
    offlineSince = Date.now();
    await chrome.storage.local.set({ offlineSince });
  }
}

function badge(ok) {
  noteReachable(ok).catch(() => {});
  if (ok === reachable) return;
  reachable = ok;
  chrome.action.setBadgeText({ text: ok ? "" : "!" });
  if (!ok) chrome.action.setBadgeBackgroundColor({ color: "#c62828" });
  chrome.action.setTitle({ title: ok ? "xrnrule" : "xrnrule isn't answering: only what it last blocked stays blocked" });
  // Which block page the redirects name depends on it.
  cachedRules().then((rules) => rules && syncRedirects(rules));
}

/** xrnrule's block page, or null for ours: while xrnrule doesn't answer, its page won't load either. */
const pageOf = (rules) => (reachable === false ? null : rules?.page ?? null);

// ---- the rule list -------------------------------------------------------------------------

let rulesCache;
async function cachedRules() {
  if (rulesCache === undefined) rulesCache = (await chrome.storage.local.get("rules")).rules ?? null;
  return rulesCache;
}

/** Every kind of window, so an undocked DevTools window in front is one of ours too. */
const WINDOW_TYPES = ["normal", "popup", "panel", "app", "devtools"];

/**
 * Whether a window this extension can see has focus; null if the browser won't say. Told to
 * xrnrule with every rule fetch (once a minute): with none, the browser window in front is one the
 * extension can't see, a private window it may not run in or another profile's, and the laptop
 * treats that as the extension being off (I49). Our pages and the browser's own (about:, the new
 * tab page, xrnrule's block page) are windows it sees, though no content script runs in them.
 */
async function seesFocus() {
  if (typeof chrome.windows?.getLastFocused !== "function") return null;
  try {
    return !!(await chrome.windows.getLastFocused({ windowTypes: WINDOW_TYPES }))?.focused;
  } catch {} // a browser that takes fewer window types: ask without
  try {
    return !!(await chrome.windows.getLastFocused())?.focused;
  } catch {
    return false; // no window it can see is open at all, only private ones: none focused
  }
}

let refreshing = null;
/** Fetches the rule list and applies it; one at a time. */
function refreshRules() {
  refreshing ??= (async () => {
    try {
      const focused = await seesFocus();
      const rules = await call(focused == null ? "/ext/rules" : `/ext/rules?focused=${focused ? 1 : 0}`);
      rulesCache = rules;
      await chrome.storage.local.set({ rules });
      await applyRules(rules);
      badge(true);
    } catch {
      badge(false);
      await noteReachable(false).catch(() => {});
      const rules = await cachedRules();
      if (rules) await applyRules(inForce(rules)); // a minute in, this is the wider list
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

async function applyRules(rules) {
  await syncRedirects(rules);
  await moveBlockedTabs(rules);
}

let redirecting = Promise.resolve();
/** Rewrites the redirect rules; one rewrite at a time, so an older list can't land last. */
function syncRedirects(rules) {
  redirecting = redirecting.then(() => writeRedirects(rules)).catch(() => {});
  return redirecting;
}

async function writeRedirects(rules) {
  let existing = [];
  try {
    existing = await chrome.declarativeNetRequest.getSessionRules();
  } catch {
    return;
  }
  const removeRuleIds = existing.map((r) => r.id).filter((id) => id >= DNR_BASE && id < DNR_BASE + 1000);
  const addRules = redirectRules(rules, pageOf(rules), OWN_PAGE, DNR_BASE);
  try {
    await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds, addRules });
  } catch (e) {
    console.warn("[xrnrule] redirect rules not taken", e);
  }
}

/** Tabs already open on a site that is blocked now: a redirect rule can't see those. */
async function moveBlockedTabs(rules) {
  let tabs = [];
  try {
    tabs = await chrome.tabs.query({ url: ["http://*/*", "https://*/*"] });
  } catch {
    return;
  }
  for (const tab of tabs) {
    const location = locationOf(tab.url || "");
    if (!blockedBy(rules, location)) continue;
    chrome.tabs.update(tab.id, { url: blockUrl(pageOf(rules), OWN_PAGE, location, tab.url) }).catch(() => {});
  }
}

// ---- the site in front ---------------------------------------------------------------------

/**
 * Tells xrnrule which site is in front ([host] null: none, or a page that isn't a website) and
 * returns its answer: blocked or not, and what runs out first. [standIn]: our block page,
 * standing in for the site it blocks, so a timer habit counts while you wait there.
 */
async function beat(host, focused, standIn = false, playing = false) {
  try {
    const verdict = await call("/ext/heartbeat", { host, focused, standIn, playing, incognito, version: chrome.runtime.getManifest().version });
    badge(true);
    const rules = await cachedRules();
    if (!rules || rules.v !== verdict.rules) refreshRules();
    return verdict;
  } catch {
    badge(false);
    // Fail closed: what xrnrule last said was blocked stays blocked, and a minute in, all of it.
    const rules = inForce(await cachedRules());
    const site = host ? locationOf(`https://${host}`) : null;
    return { site, blocked: !!blockedBy(rules, site), page: null, showClock: false, offline: true };
  }
}

/** The active tab, for switches between tabs and windows: pages beat on their own every few seconds. */
async function beatActive() {
  let tab;
  let focused = false;
  try {
    [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab) focused = (await chrome.windows.get(tab.windowId)).focused;
  } catch {
    return;
  }
  // xrnrule's own pages (its block page stands in for a site) speak for themselves.
  if (tab?.url?.startsWith(`${await engineUrl()}/`)) return;
  const host = tab ? locationOf(tab.url || "") : null;
  const verdict = await beat(host, focused);
  if (verdict.blocked && tab && host) {
    chrome.tabs.update(tab.id, { url: blockUrl(verdict.page, OWN_PAGE, verdict.site || host, tab.url) }).catch(() => {});
  }
}

chrome.tabs.onActivated.addListener(() => beatActive());
chrome.windows.onFocusChanged.addListener(() => beatActive());
chrome.tabs.onUpdated.addListener((_id, info, tab) => {
  if (info.url && tab.active) beatActive();
});

// ---- pages ---------------------------------------------------------------------------------

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  switch (msg?.type) {
    case "beat":
      beat(msg.host ?? null, !!msg.focused, !!msg.standIn, !!msg.playing).then(reply);
      return true; // the reply comes later
    case "check": // the popup: about a host, without saying it's in front
      call(`/ext/check?host=${encodeURIComponent(msg.host ?? "")}`)
        .then((v) => reply({ ...v, reachable: true }))
        .catch(async () => reply({ reachable: false, blocked: !!blockedBy(inForce(await cachedRules()), msg.host) }));
      return true;
    case "engine":
      engineUrl().then(reply);
      return true;
    case "setPort":
      chrome.storage.local.set({ port: msg.port || null }).then(() => refreshRules()).then(() => reply({ ok: reachable }));
      return true;
    case "checkUpdate": // the options page's "Check now"
      checkForUpdate().then(reply);
      return true;
  }
  return false;
});

// ---- staying current -----------------------------------------------------------------------

async function start() {
  const rules = await cachedRules();
  if (rules) await syncRedirects(rules); // session rules are gone after a browser restart
  refreshRules();
}

chrome.runtime.onStartup.addListener(start);
chrome.runtime.onInstalled.addListener(start);
chrome.alarms.create("rules", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "rules") {
    refreshRules();
    checkIfUnpacked();
  }
});

// ---- updating itself -----------------------------------------------------------------------
//
// Nobody installs a new version by hand. Installed from the signed .crx (the phone; see
// scripts/release.mjs), the manifest carries an update_url on the home server and the browser
// fetches new versions from there on its own, every few hours, or now through
// requestUpdateCheck() (the options page's "Check now"). Not on a timer: the browser counts each
// "no update" answer toward a backoff, so hourly checks kept it "throttled", Check now included.
// Loaded unpacked (the laptop, from the working copy) there is no update
// URL, but the browser serves an unpacked extension's files straight from its folder, so a
// manifest there naming another version means the folder has moved on. Either way the new version
// runs at once, not at the next browser start.

chrome.runtime.onUpdateAvailable.addListener(() => chrome.runtime.reload());

async function checkForUpdate() {
  const running = chrome.runtime.getManifest();
  let installType = "normal";
  try {
    ({ installType } = await chrome.management.getSelf());
  } catch {} // a browser without it: treat it as installed
  if (installType === "development") {
    let onDisk = running.version;
    try {
      const res = await fetch(chrome.runtime.getURL("manifest.json"), { cache: "no-store" });
      onDisk = (await res.json()).version;
    } catch {} // mid-edit; the next check reads it again
    if (onDisk === running.version) return { status: "no_update", version: running.version, installType };
    setTimeout(() => chrome.runtime.reload(), 250); // the reply reaches the options page first
    return { status: "reloading", version: onDisk, installType };
  }
  if (!running.update_url) return { status: "no_update_url", version: running.version, installType };
  // Firefox has no requestUpdateCheck: it reads gecko.update_url on its own schedule.
  if (typeof chrome.runtime.requestUpdateCheck !== "function") return { status: "unsupported", version: running.version, installType };
  try {
    // "update_available" (onUpdateAvailable follows once it's downloaded), "no_update", or
    // "throttled" when asked too often.
    const res = await chrome.runtime.requestUpdateCheck();
    return { status: res.status, version: res.version || running.version, installType };
  } catch (e) {
    return { status: "error", error: String(e?.message || e), version: running.version, installType };
  }
}

chrome.alarms.clear("update"); // the hourly check versions up to 2.1.1 made
// Reading one local file is cheap enough for every start of the worker and every minute (the
// rules alarm), so the laptop runs a new version within a minute of it landing in the folder.
// The worker may never stop for an hour: pages beating keep it awake.
async function checkIfUnpacked() {
  const self = await chrome.management?.getSelf().catch(() => null);
  if (self?.installType === "development") checkForUpdate();
}
checkIfUnpacked();

// An update orphans the content script in every open tab (content.js stops itself), and a page
// that doesn't beat isn't counted against a limit: put the new copy in each one now rather than
// at its next reload.
chrome.runtime.onInstalled.addListener(async ({ reason }) => {
  if (reason !== "update") return;
  let tabs = [];
  try {
    tabs = await chrome.tabs.query({ url: ["http://*/*", "https://*/*"] });
  } catch {
    return;
  }
  for (const tab of tabs) {
    if (!locationOf(tab.url || "") || /^https?:\/\/(127\.0\.0\.1|localhost)[:/]/.test(tab.url)) continue;
    chrome.scripting?.executeScript({ target: { tabId: tab.id }, files: ["shared.js", "content.js"] }).catch(() => {});
  }
});
