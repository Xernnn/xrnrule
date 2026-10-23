// xrnrule extension, background: the go-between for the pages and xrnrule on 127.0.0.1 (the
// laptop daemon, or the phone app). xrnrule decides what's blocked, the same blocks on every
// device; this only reports the site in front, passes the answer back, and keeps the browser in
// line with the last rule list it got:
//
//   - redirect rules (declarativeNetRequest) send a new visit to a blocked site straight to the
//     block page, before anything loads;
//   - tabs already open on a site that just got blocked are moved there too;
//   - while xrnrule doesn't answer, what it last said was blocked stays blocked, and the
//     toolbar icon shows "!".

importScripts("shared.js");

const { DEFAULT_PORT, hostOf, blockedBy, enginePage, blockUrl } = self.Xrn;
const OWN_PAGE = chrome.runtime.getURL("block.html");
/** Our redirect rules' ids. Each rewrite removes the whole range first. */
const DNR_BASE = 1000;

async function engineUrl() {
  const { port } = await chrome.storage.local.get("port");
  return `http://127.0.0.1:${port || DEFAULT_PORT}`;
}

async function call(path, body) {
  const engine = await engineUrl();
  const res = await fetch(`${engine}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { "Content-Type": "application/json", "X-Xrnrule": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`xrnrule answered ${res.status}`);
  const answer = await res.json();
  // Rules and verdicts name the block page: only one on xrnrule's own address is followed.
  if (answer && typeof answer === "object" && "page" in answer) answer.page = enginePage(answer.page, engine);
  return answer;
}

let reachable = null;
function badge(ok) {
  if (ok === reachable) return;
  reachable = ok;
  chrome.action.setBadgeText({ text: ok ? "" : "!" });
  if (!ok) chrome.action.setBadgeBackgroundColor({ color: "#c62828" });
  chrome.action.setTitle({ title: ok ? "xrnrule" : "xrnrule isn't answering: only what it last blocked stays blocked" });
}

// ---- the rule list -------------------------------------------------------------------------

let rulesCache;
async function cachedRules() {
  if (rulesCache === undefined) rulesCache = (await chrome.storage.local.get("rules")).rules ?? null;
  return rulesCache;
}

let refreshing = null;
/** Fetches the rule list and applies it; one at a time. */
function refreshRules() {
  refreshing ??= (async () => {
    try {
      const rules = await call("/ext/rules");
      rulesCache = rules;
      await chrome.storage.local.set({ rules });
      await applyRules(rules);
      badge(true);
    } catch {
      badge(false);
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

async function syncRedirects(rules) {
  let existing = [];
  try {
    existing = await chrome.declarativeNetRequest.getSessionRules();
  } catch {
    return;
  }
  const removeRuleIds = existing.map((r) => r.id).filter((id) => id >= DNR_BASE && id < DNR_BASE + 1000);
  const addRules = (rules?.blocked ?? []).slice(0, 999).map((r, i) => ({
    id: DNR_BASE + i,
    priority: 1,
    // \0 is the whole URL asked for: the block page goes back there once the site opens.
    action: { type: "redirect", redirect: { regexSubstitution: blockUrl(rules.page, OWN_PAGE, r.domain, "\\0") } },
    condition: {
      regexFilter: "^https?://.*",
      requestDomains: [r.domain],
      // An exception must hold here too, or the network layer would block music.youtube.com
      // while every other part lets it through.
      ...(r.except?.length ? { excludedRequestDomains: r.except } : {}),
      resourceTypes: ["main_frame"],
    },
  }));
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
    const host = hostOf(tab.url || "");
    const hit = blockedBy(rules, host);
    if (!hit) continue;
    chrome.tabs.update(tab.id, { url: blockUrl(rules.page, OWN_PAGE, host, tab.url) }).catch(() => {});
  }
}

// ---- the site in front ---------------------------------------------------------------------

/**
 * Tells xrnrule which site is in front ([host] null: none, or a page that isn't a website) and
 * returns its answer: blocked or not, and what runs out first. [standIn]: our block page,
 * standing in for the site it blocks, so a timer habit counts while you wait there.
 */
async function beat(host, focused, standIn = false) {
  try {
    const verdict = await call("/ext/heartbeat", { host, focused, standIn });
    badge(true);
    const rules = await cachedRules();
    if (!rules || rules.v !== verdict.rules) refreshRules();
    return verdict;
  } catch {
    badge(false);
    // Fail closed: what xrnrule last said was blocked stays blocked; nothing else is.
    const rules = await cachedRules();
    const site = host ? hostOf(`https://${host}/`) : null;
    return { site, blocked: !!blockedBy(rules, site), page: rules?.page ?? null, showClock: false, offline: true };
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
  const host = tab ? hostOf(tab.url || "") : null;
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
      beat(msg.host ?? null, !!msg.focused, !!msg.standIn).then(reply);
      return true; // the reply comes later
    case "check": // the popup: about a host, without saying it's in front
      call(`/ext/check?host=${encodeURIComponent(msg.host ?? "")}`)
        .then((v) => reply({ ...v, reachable: true }))
        .catch(async () => reply({ reachable: false, blocked: !!blockedBy(await cachedRules(), msg.host) }));
      return true;
    case "engine":
      engineUrl().then(reply);
      return true;
    case "setPort":
      chrome.storage.local.set({ port: msg.port || null }).then(() => refreshRules()).then(() => reply({ ok: reachable }));
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
  if (alarm.name === "rules") refreshRules();
});
