// What xrnrule's two web pages share: the laptop's local page (the desktop build copies this
// file into its resources) and this status page. A tiny DOM builder and the icons, drawn inline
// (both pages' Content-Security-Policy allows their own files only, and no style attributes:
// sizes and colours go through CSSOM, which h() uses for `style`).

/** An element; `on…` attributes are listeners, `style` a CSS text or an object of properties. */
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (k === "style" && typeof v === "object") for (const [p, x] of Object.entries(v)) el.style.setProperty(p, x);
    else if (k in el && k !== "style" && !k.startsWith("aria") && !k.startsWith("data")) el[k] = v;
    else if (k === "style") el.style.cssText = v;
    else el.setAttribute(k, v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

const SVG = "http://www.w3.org/2000/svg";

/** An SVG element. */
export function s(tag, attrs = {}, ...children) {
  const el = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k === "className" ? "class" : k, v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
}

/** Material icons (Apache 2.0), 24 × 24. */
const PATHS = {
  shield: "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z",
  chart: "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z",
  settings: "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
  add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  phone: "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z",
  laptop: "M20 18c1.1 0 1.99-.9 1.99-2L22 6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z",
  devices: "M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 9h-4v-7h4v7z",
  coffee: "M20 3H4v10c0 2.21 1.79 4 4 4h6c2.21 0 4-1.79 4-4v-3h2c1.11 0 2-.89 2-2V5c0-1.11-.89-2-2-2zm-4 10c0 1.1-.9 2-2 2H8c-1.1 0-2-.9-2-2V5h10v8zm4-5h-2V5h2v3zM4 19h16v2H4z",
  lock: "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z",
  lockOpen: "M12 17c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm6-9h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6h1.9c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm0 12H6V10h12v10z",
  timer: "M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
  hourglass: "M18 22l-.01-6L14 12l3.99-4.01L18 2H6v6l4 4-4 3.99V22h12zM8 7.5V4h8v3.5l-4 4-4-4z",
  calendar: "M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20a2 2 0 002 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zM9 14H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2zm-8 4H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2z",
  check: "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  close: "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  chevronDown: "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z",
  chevronRight: "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
  openNew: "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
  task: "M22 5.18L10.59 16.6l-4.24-4.24 1.41-1.41 2.83 2.83 10-10L22 5.18zm-2.21 5.04c.13.57.21 1.17.21 1.78 0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8c1.58 0 3.04.46 4.28 1.25l1.44-1.44C16.1 2.67 14.13 2 12 2 6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10c0-1.19-.22-2.33-.6-3.39l-1.61 1.61z",
  play: "M8 5v14l11-7z",
  addCircle: "M13 7h-2v4H7v2h4v4h2v-4h4v-2h-4V7zm-1-5C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
  pause: "M6 19h4V5H6v14zm8-14v14h4V5h-4z",
  globe: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z",
  bolt: "M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z",
  cloud: "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM10 17l-3.5-3.5 1.41-1.41L10 14.17 15.18 9l1.41 1.41L10 17z",
  cloudOff: "M19.35 10.04C18.67 6.59 15.64 4 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46C10.21 6.23 11.08 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19c1.66 0 3 1.34 3 3 0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 18.16 24 16.68 24 15c0-2.64-2.05-4.78-4.65-4.96zM3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4 3 5.27zM7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73z",
  edit: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  delete: "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
  logout: "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z",
  extension: "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z",
  desktop: "M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z",
  warning: "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
  moreTime: "M10 8v6l4.7 2.9.8-1.2-4-2.4V8zm7.92 4A6.957 6.957 0 0111 20c-3.9 0-7-3.1-7-7s3.1-7 7-7c.7 0 1.37.1 2 .29V4.23c-.64-.15-1.31-.23-2-.23-5 0-9 4-9 9s4 9 9 9 9-4 9-9c0-.34-.02-.67-.06-1h-2.02zM20 5V2h-2v3h-3v2h3v3h2V7h3V5h-3z",
  blocked: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z",
  fire: "M13.5.67s.74 2.65.74 4.8c0 2.06-1.35 3.73-3.41 3.73-2.07 0-3.63-1.67-3.63-3.73l.03-.36C5.21 7.51 4 10.62 4 14c0 4.42 3.58 8 8 8s8-3.58 8-8C20 8.61 17.41 3.8 13.5.67zM11.71 19c-1.78 0-3.22-1.4-3.22-3.14 0-1.62 1.05-2.76 2.81-3.12 1.77-.36 3.6-1.21 4.62-2.58.39 1.29.59 2.65.59 4.04 0 2.65-2.15 4.8-4.8 4.8z",
  nfc: "M20 2H4c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 18H4V4h16v16zM18 6h-5c-1.1 0-2 .9-2 2v2.28c-.6.35-1 .98-1 1.72 0 1.1.9 2 2 2s2-.9 2-2c0-.74-.4-1.38-1-1.72V8h3v8H8V8h2V6H6v12h12V6z",
  sun: "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0 .39-.39.39-1.03 0-1.41l-1.06-1.06zm1.06-10.96c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z",
};

/** An icon; [cls] sizes and colours it (currentColor). */
export function icon(name, cls = "") {
  return s("svg", { viewBox: "0 0 24 24", "aria-hidden": "true", className: `icon ${cls}`.trim(), focusable: "false" }, s("path", { d: PATHS[name] ?? PATHS.globe }));
}

/**
 * A block's tone: by its place in the block list, round again past the eighth, the same on the
 * phone (BlockColors.kt), in both themes. Muted, and only ever small: a dot before its name, its
 * part of a stacked chart or a day's strip, the bar of something it covers, its sparkline, the
 * marker on its card. Never a card's tint, a chip, a letter tile or the colour of words.
 */
export const TONES = ["#D27A4A", "#5B8FD6", "#4E9E68", "#8F7AD8", "#CE6488", "#3E9E92", "#AE8834", "#4C98C4"];
/** What no block covers any more (a block deleted, a target taken off). */
export const OTHER_TONE = "#8A8A93";

export const toneAt = (index) => TONES[((index % TONES.length) + TONES.length) % TONES.length];

/** A block's dot, before its name. */
export function toneDot(tone, cls = "") {
  return h("i", { class: `tdot ${cls}`.trim(), style: { "--tone": tone ?? OTHER_TONE }, "aria-hidden": "true" });
}

/**
 * How a limit's meter is coloured (its share used, 0..1+): the data colour, then amber from 80 %,
 * red once used up.
 */
export function limitTone(fraction) {
  return fraction >= 1 ? "over" : fraction >= 0.8 ? "warn" : "";
}

/** A thin meter; [tone] "warn" or "over" for a limit near or at its end. */
export function meter(fraction, tone = "", cls = "") {
  return h("div", { class: `meter ${tone} ${cls}`.replace(/\s+/g, " ").trim() },
    h("i", { style: { width: `${Math.max(0, Math.min(100, fraction * 100))}%` } }));
}

// ---- logos: the real icons of what a block covers ------------------------------------------
// Each page asks its own server by key (the icon contract): the status page `icons/<key>`
// (relative, so it works under the server's path and in the hub's window), the laptop's page
// `/api/icon?key=<key>`. What has none shows a grey glyph for its kind: a globe, a phone or a laptop.

/** "site:reddit.com": a site by its host, lowercased, as a block names it (no path). */
export const siteKey = (host) => `site:${String(host ?? "").toLowerCase().split("/")[0]}`;
/** "app:com.instagram.android": a phone app by its package. */
export const appKey = (pkg) => `app:${pkg}`;
/** "desk:steam": a laptop app by its id, as blocks and the laptop's events name it. */
export const deskKey = (id) => `desk:${id}`;

/** A blocked try's target as an icon key: a host is a site, an id from the phone an app, one from the laptop a laptop app. */
export function targetKey(target, device, site) {
  if (target == null || target === "") return null;
  if (site) return siteKey(target);
  return device === "phone" ? appKey(target) : device === "laptop" ? deskKey(target) : null;
}

const KIND_GLYPH = { site: "globe", app: "phone", desk: "laptop" };
const kindOf = (key) => String(key ?? "").split(":")[0];

let iconUrl = (key) => `icons/${encodeURIComponent(key)}`;
/** The keys the server has (its index), or null to ask for any. */
let iconKnown = null;
/** Keys that answered 404 lately, and when: a page that redraws asks again after a minute, not every time. */
const iconMissing = new Map();
const MISSING_MS = 60_000;

/** Where this page's icons come from: [url] a key's address, [known] the keys there are (null: any). */
export function iconSource({ url, known } = {}) {
  if (url) iconUrl = url;
  if (known !== undefined) iconKnown = known ? new Set(known) : null;
}

/** The address of [key]'s icon, or null while it's known not to be there. */
export function iconSrc(key, now = Date.now()) {
  if (!key) return null;
  if (iconKnown && !iconKnown.has(key)) return null;
  const since = iconMissing.get(key);
  if (since != null && now - since < MISSING_MS) return null;
  return iconUrl(key);
}

/** The grey glyph for what has no icon: a globe for a site, a phone or a laptop for an app. */
export function glyphTile(key, size, cls = "") {
  return h("span", { class: `logo glyph s${size} ${cls}`.trim(), "aria-hidden": "true" }, icon(KIND_GLYPH[kindOf(key)] ?? "globe"));
}

/**
 * A view drawn again every second (the laptop's Blocks) gets the same icon elements back, in the
 * order it asks for them, instead of new ones that could show blank for a moment each time. Only
 * inside [pooled]; anything else gets new ones.
 */
const pool = new Map(); // "key|size|cls|title" -> elements
const handed = new Map(); // "key|size|cls|title" -> how many this drawing has taken
let pooling = false;
export function pooled(draw) {
  pooling = true;
  handed.clear();
  try {
    return draw();
  } finally {
    pooling = false;
  }
}

/** [key]'s icon at [size] px (a class per size: no style attributes), its glyph if it has none. */
export function logo(key, size, { title = null, cls = "" } = {}) {
  const src = iconSrc(key);
  if (!src) {
    const g = glyphTile(key, size, cls);
    if (title) g.title = title;
    return g;
  }
  const slot = `${key}|${size}|${cls}|${title ?? ""}`;
  const kept = pooling ? pool.get(slot) ?? [] : null;
  if (kept) {
    const i = handed.get(slot) ?? 0;
    handed.set(slot, i + 1);
    if (kept[i]) return kept[i];
  }
  const img = h("img", {
    class: `logo s${size} ${cls}`.trim(), src, alt: "", width: size, height: size, loading: "lazy", title,
    onerror: (e) => {
      iconMissing.set(key, Date.now());
      pool.delete(slot);
      const g = glyphTile(key, size, cls);
      if (title) g.title = title;
      e.target.replaceWith(g);
    },
  });
  if (kept) pool.set(slot, [...kept, img]);
  return img;
}

/** Sites.ALIASES: another name of a site, shown only where its main name isn't in the list too. */
const ALIASES = { "redd.it": "reddit.com", "twitter.com": "x.com", "t.co": "x.com", "fb.com": "facebook.com", "fb.me": "facebook.com",
  "fb.watch": "facebook.com", "youtu.be": "youtube.com", "instagr.am": "instagram.com" };
/** Companions.PAIRS: a site and its phone apps, one tile for one service. */
const PAIRS = {
  "youtube.com": ["com.google.android.youtube"], "instagram.com": ["com.instagram.android"], "facebook.com": ["com.facebook.katana", "com.facebook.lite"],
  "x.com": ["com.twitter.android"], "reddit.com": ["com.reddit.frontpage"], "tiktok.com": ["com.zhiliaoapp.musically", "com.ss.android.ugc.trill"],
  "pinterest.com": ["com.pinterest"], "snapchat.com": ["com.snapchat.android"], "twitch.tv": ["tv.twitch.android.app"], "discord.com": ["com.discord"],
  "netflix.com": ["com.netflix.mediaclient"],
};
const covers = (site, domain) => {
  const host = String(site).split("/")[0];
  return host === domain || host.endsWith(`.${domain}`);
};

/**
 * What a block covers as icons, in the phone's order (AppTile): its phone apps, its laptop apps,
 * then its sites, a site's other name folded into its main one (Sites.shown) and a site whose app
 * is in the block left out (Companions.withoutPaired). Either page's block: the shared state's
 * (targetPackages, targetDesktopApps, targetSites) or the laptop's (phonePackages, desktopApps,
 * shownSites, and only a count of phone apps where it has no packages). [names]: a laptop app's
 * name by its id. Returns {items: [{key, name}], extra}: [extra] counts phone apps it can't show.
 */
export function blockLogos(b, names = (id) => id) {
  const packages = [...new Set(b.phonePackages ?? b.targetPackages ?? [])].sort();
  const desk = [...new Set(b.desktopApps ?? b.targetDesktopApps ?? [])].sort((x, y) => String(names(x)).localeCompare(String(names(y))));
  const all = (b.shownSites ?? b.targetSites ?? b.sites ?? []).map((x) => String(x).toLowerCase());
  const named = new Set(all);
  const sites = [];
  for (const site of [...all].sort()) {
    if (ALIASES[site] && named.has(ALIASES[site])) continue;
    if (Object.entries(PAIRS).some(([domain, apps]) => covers(site, domain) && apps.some((a) => packages.includes(a)))) continue;
    const host = site.split("/")[0];
    if (!sites.includes(host)) sites.push(host);
  }
  const items = [
    ...packages.map((p) => ({ key: appKey(p), name: p })),
    ...desk.map((id) => ({ key: deskKey(id), name: names(id) })),
    ...sites.map((s) => ({ key: siteKey(s), name: s })),
  ];
  const extra = b.phonePackages || b.targetPackages ? 0 : (b.phoneApps ?? 0);
  return { items, extra };
}

/**
 * A block's icons as the phone's AppTile draws them: one alone at full size, or up to four in a
 * 2 × 2 on a grey tile with "+N" for the rest; a shield on the tile when there's nothing to show.
 */
export function cluster({ items = [], extra = 0 } = {}, size = 46, title = null) {
  const hidden = extra + Math.max(0, items.length - 4);
  if (items.length === 1 && hidden === 0) return h("span", { class: `cluster one s${size}`, title }, logo(items[0].key, size, { title: items[0].name }));
  const cell = CELL[size] ?? Math.round(size * 0.38);
  return h("span", { class: `cluster s${size}`, title },
    items.length === 0 ? icon("shield", "cluster-glyph")
      : h("span", { class: `cluster-grid n${Math.min(4, items.length)}` }, items.slice(0, 4).map((x) => logo(x.key, items.length === 1 ? ONE[size] ?? cell : cell, { title: x.name }))),
    hidden > 0 && h("span", { class: "cluster-more" }, `+${hidden}`));
}
/** A cluster's cell by its size, and its one icon beside a "+N" (each a class in xrn.css). */
const CELL = { 46: 18, 52: 20, 30: 13, 24: 11 };
const ONE = { 46: 36, 52: 40, 30: 24, 24: 18 };

/** Loop's habit colours (Habit.paletteColor, 0..19), as the phone has them: its dark palette and its light one. */
export const LOOP_DARK = ["#EF9A9A", "#FFAB91", "#FFCC80", "#FFECB3", "#FFF59D", "#E6EE9C", "#C5E1A5", "#69F0AE", "#80CBC4", "#80DEEA",
  "#81D4FA", "#64B5F6", "#9FA8DA", "#B39DDB", "#CE93D8", "#F48FB1", "#BCAAA4", "#F5F5F5", "#E0E0E0", "#9E9E9E"];
export const LOOP_LIGHT = ["#D32F2F", "#E64A19", "#F57C00", "#FF8F00", "#F9A825", "#AFB42B", "#7CB342", "#388E3C", "#00897B", "#00ACC1",
  "#039BE5", "#1976D2", "#303F9F", "#5E35B1", "#8E24AA", "#D81B60", "#5D4037", "#424242", "#757575", "#9E9E9E"];

/** A habit's own colour from its palette index, {dark, light}; null for none (it's drawn in the data colour). */
export function habitHue(index) {
  if (!Number.isInteger(index)) return null;
  const i = Math.max(0, Math.min(LOOP_DARK.length - 1, index));
  return { dark: LOOP_DARK[i], light: LOOP_LIGHT[i] };
}

/**
 * "Do both", "Do all", "Do 2 of 3": how a habit block's rows add up, or null for one row (the
 * phone's joinTag). [b]: {trigger: "CHECKLIST"|"TIMER"|"COMBINED", requiredItems (-1: all), combineOr}.
 */
export function joinTag(b, parts) {
  if (parts < 2) return null;
  if (b.trigger === "COMBINED") {
    if (b.combineOr) return parts === 2 ? "Do either" : "Timer or all tasks";
    return parts === 2 ? "Do both" : "Do all";
  }
  if (b.trigger === "CHECKLIST") {
    if (b.requiredItems === -1 || b.requiredItems >= parts) return parts === 2 ? "Do both" : "Do all";
    return `Do ${b.requiredItems} of ${parts}`;
  }
  return null;
}

/** How a block stands, where Habits First has its switch: a padlock on red while it blocks, open on green while it's open. Its words in its title. */
const STATE_ICON = { blocked: "lock", open: "lockOpen", earned: "lockOpen", idle: "lockOpen" };
export function statePill(tone, words) {
  return h("span", { class: `state ${tone}`, role: "img", title: words, "aria-label": words }, icon(STATE_ICON[tone] ?? "lockOpen"));
}

/**
 * One thing a block asks for or holds to, as Habits First's habit row: its icon in its colour (a
 * habit's own, else the data colour; a limit's turns amber from 80 % and red once used up), its
 * words and figure, a thin bar in the same colour, and what you can do about it at the right.
 * [ic]: a glyph name or a node; [hue]: habitHue(); [tone]: "", "warn", "over" or "plain" (grey).
 */
export function row({ ic, label, value, fraction, hue = null, tone = "", note, dim, action, strip, done }) {
  return h("div", { class: `rrow${dim ? " dim" : ""}${done ? " done" : ""}${hue ? " hued" : ""}${tone ? ` ${tone}` : ""}`, style: hue ? { "--hc-d": hue.dark, "--hc-l": hue.light } : null },
    h("span", { class: "ricon" }, typeof ic === "string" || ic == null ? icon(ic ?? "task") : ic),
    h("div", { class: "main" },
      h("div", { class: "top" }, h("span", { class: "lbl" }, label), value && h("span", { class: "val" }, value)),
      fraction != null && h("div", { class: "rbar" }, h("i", { style: { width: `${Math.max(0, Math.min(100, fraction * 100))}%` } })),
      note && h("span", { class: "note" }, note),
      strip),
    h("div", { class: "acts" }, action));
}

/** The small caps tag over a habit block's rows: "DO ALL", "DO EITHER". */
export const tag = (text) => text && h("span", { class: "jointag" }, text);

/**
 * The stretches of [day] (ISO weekday, 1 = Monday) a schedule blocks, as [from, to] minutes of
 * that day, merged: each window on its days, one past midnight running into the next day
 * (BlockEngine.scheduleActive, I20, I30). A window of no times is its days whole.
 * [schedule]: {activeDays, activeWindow: {startMinuteOfDay, endMinuteOfDay}?, moreWindows: [{days, window}]}.
 */
export function daySpans(schedule, day) {
  if (!schedule) return [];
  const before = day === 1 ? 7 : day - 1;
  const spans = [];
  const add = (days, w) => {
    if (!w) {
      if (days.includes(day)) spans.push([0, 1440]);
      return;
    }
    const { startMinuteOfDay: from, endMinuteOfDay: to } = w;
    if (from < to) {
      if (days.includes(day)) spans.push([from, to]);
    } else if (from > to) {
      if (days.includes(day)) spans.push([from, 1440]);
      if (days.includes(before) && to > 0) spans.push([0, to]);
    }
  };
  add(schedule.activeDays ?? [1, 2, 3, 4, 5, 6, 7], schedule.activeWindow);
  for (const m of schedule.moreWindows ?? []) add(m.days ?? [], m.window);
  spans.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const [a, b] of spans) {
    const last = merged[merged.length - 1];
    if (last && a <= last[1]) last[1] = Math.max(last[1], b);
    else merged.push([a, b]);
  }
  return merged;
}

/** Today's 24 hours as a strip: the blocked stretches shaded, a mark at [nowMinute]. */
export function dayStrip(spans, nowMinute, blockedNow = false) {
  const pct = (m) => `${(m / 1440) * 100}%`;
  return h("div", { class: `daystrip${blockedNow ? " now-blocked" : ""}`, "aria-hidden": "true" },
    [360, 720, 1080].map((m) => h("i", { class: "dtick", style: { left: pct(m) } })),
    spans.map(([a, b]) => h("i", { class: "span", style: { left: pct(a), width: pct(b - a) } })),
    nowMinute != null && h("i", { class: "nowmark", style: { left: pct(nowMinute) } }));
}

/** The device an event or a device id is from: "phone", "laptop" or "" (another, or none). */
export function deviceOf(d) {
  const x = String(d ?? "").toLowerCase();
  return x.startsWith("phone") ? "phone" : x.startsWith("laptop") ? "laptop" : "";
}

/** A device's icon: the phone, the laptop, or both (a habit or an unlock holds on both). */
export function deviceIcon(device, cls = "") {
  return icon(device === "phone" ? "phone" : device === "laptop" ? "laptop" : "devices", `dev ${cls}`.trim());
}

/**
 * What a block covers, a part per kind, for its icons' title (coverText): its sites, how many phone apps
 * (the pages know their count, not their names), its laptop apps, and what it leaves alone.
 */
export function coverParts({ sites = [], phoneApps = 0, laptopApps = [], except = [] }) {
  return [
    sites.length > 0 && { kind: "site", text: sites.join(", ") },
    phoneApps > 0 && { kind: "phone", text: `${phoneApps} app${phoneApps === 1 ? "" : "s"}` },
    laptopApps.length > 0 && { kind: "laptop", text: laptopApps.join(", ") },
    except.length > 0 && { kind: "except", text: `except ${except.join(", ")}` },
  ].filter(Boolean);
}

/** What a block covers in words, for its icons' title: "reddit.com, x.com · 2 phone apps · Steam · except …". */
export function coverText(what) {
  return coverParts(what).map((p) => (p.kind === "phone" ? p.text.replace("app", "phone app") : p.text)).join(" · ") || null;
}

/** "now", "16 min", "3 h", "4 d": how long ago, short. */
export function ago(ms) {
  const m = Math.floor(Math.max(0, ms) / 60000);
  if (m < 1) return "now";
  if (m < 60) return `${m} min`;
  if (m < 48 * 60) return `${Math.floor(m / 60)} h`;
  return `${Math.floor(m / 1440)} d`;
}

/** A device's last check-in, "● Phone 2 min": the dot green while fresh, amber after half an hour. */
export function checkIn(label, agoMs) {
  return h("span", { class: `checkin${agoMs > 30 * 60_000 ? " stale" : ""}` }, label, " ", h("b", {}, ago(agoMs)));
}

/** "1 h 05", "25 min", "0 min": a duration in whole minutes. */
export function duration(ms) {
  const m = Math.max(0, Math.round(ms / 60000));
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")}`;
}

/** The page's theme: "auto" follows the system, else "light" or "dark" (kept in this browser). */
export function applyTheme(choice) {
  const root = document.documentElement;
  if (choice === "light" || choice === "dark") root.dataset.theme = choice;
  else delete root.dataset.theme;
}

export function savedTheme(key) {
  try {
    return localStorage.getItem(key) ?? "auto";
  } catch {
    return "auto";
  }
}

export function saveTheme(key, choice) {
  try {
    localStorage.setItem(key, choice);
  } catch {
    /* a private window: this visit only */
  }
  applyTheme(choice);
}
