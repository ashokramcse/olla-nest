/**
 * @file theme.js
 * @description Olla Nest design-system colour engine.
 *
 * Exports:
 *   hexToRgb / rgbToHsl / hslToHex / luminance / textColour
 *   computeDayTokens / computeNightTokens / applyTheme
 *
 * On every page: reads localStorage for { themeHex, themeMode } and
 * calls applyTheme() so CSS variables are set before first paint.
 */

// ── Colour math helpers ────────────────────────────────────────────────────

function hexToRgb(hex) {
  const h = hex.replace(/^#/, "");
  const n = h.length === 3
    ? h.split("").map(c => parseInt(c + c, 16))
    : [parseInt(h.slice(0,2),16), parseInt(h.slice(2,4),16), parseInt(h.slice(4,6),16)];
  return { r: n[0], g: n[1], b: n[2] };
}

function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r,g,b), min = Math.min(r,g,b);
  let h, s, l = (max + min) / 2;
  if (max === min) { h = s = 0; }
  else {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
      case g: h = ((b - r) / d + 2) / 6; break;
      case b: h = ((r - g) / d + 4) / 6; break;
    }
  }
  return { h: h * 360, s: s * 100, l: l * 100 };
}

function hslToHex(h, s, l) {
  h = ((h % 360) + 360) % 360;
  s = Math.max(0, Math.min(100, s)) / 100;
  l = Math.max(0, Math.min(100, l)) / 100;
  const a = s * Math.min(l, 1 - l);
  const f = n => {
    const k = (n + h / 30) % 12;
    const v = l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)));
    return Math.round(255 * v).toString(16).padStart(2, "0");
  };
  return `#${f(0)}${f(8)}${f(4)}`;
}

function luminance(r, g, b) {
  const s = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  return 0.2126 * s(r) + 0.7152 * s(g) + 0.0722 * s(b);
}

function textColour(hex) {
  const { r, g, b } = hexToRgb(hex);
  return luminance(r, g, b) > 0.35 ? "#1a1a1a" : "#ffffff";
}

// ── Token generators ────────────────────────────────────────────────────────

function computeDayTokens(hex) {
  const { r, g, b } = hexToRgb(hex);
  const { h, s, l } = rgbToHsl(r, g, b);
  return {
    ac:              hex,
    acText:          textColour(hex),
    acPale:          hslToHex(h, s * 0.70, 95),
    acMid:           hslToHex(h, s * 0.75, 87),
    acDark:          hslToHex(h, Math.min(s + 5, 100), Math.max(l * 0.35, 20)),
    bg:              hslToHex(h, Math.min(s * 0.15, 10), 95),
    navBorder:       hslToHex(h, Math.min(s * 0.20, 14), 88),
    div:             hslToHex(h, Math.min(s * 0.15, 10), 90),
    track:           hslToHex(h, Math.min(s * 0.12,  9), 92),
    navBg:           "#ffffff",
    hdrBg:           "#ffffff",
    hdrDiv:          hslToHex(h, Math.min(s * 0.20, 14), 88),
    hdrText:         "#0f0f0f",
    hdrMuted:        "#888888",
    border:          hslToHex(h, Math.min(s * 0.20, 14), 88),
    bodyText:        "#1a1a1a",
    muted1:          "#555555",
    muted2:          "#888888",
    midx:            hslToHex(h, Math.min(s + 5, 100), Math.max(l * 0.35, 20)),
    bubble:          "#ffffff",
    bubbleReply:     hslToHex(h, s * 0.70, 95),
    bubbleReplyText: "#1a1a1a",
    actionBg:        "#ffffff",
    logOk:           "#16a34a",
    logWarn:         "#d97706",
    activeText:      "#1a1a1a",
  };
}

function computeNightTokens(hex) {
  const { r, g, b } = hexToRgb(hex);
  const { h, s, l } = rgbToHsl(r, g, b);
  return {
    ac:              hex,
    acText:          textColour(hex),
    // Tinted dark surface — slightly coloured but still very dark
    acPale:          hslToHex(h, Math.min(s * 0.35, 22), 17),
    acMid:           hslToHex(h, Math.min(s * 0.30, 18), 22),
    acDark:          hslToHex(h, s,         Math.min(l + (100 - l) * 0.42, 74)),
    // Page & surface layers — distinct zinc steps (each 3-6 L% apart)
    bg:              "#0a0a0c",   // page floor — near-black
    navBg:           "#111115",   // sidebar / nav
    hdrBg:           "#111115",   // header bar
    hdrDiv:          "#2a2a30",   // header bottom border
    hdrText:         "#f4f4f5",   // header labels — zinc-100
    hdrMuted:        "#71717a",   // header secondary text — zinc-500
    border:          "#3f3f46",   // card & input borders — zinc-700, visibly lighter than bg
    navBorder:       "#2a2a30",   // sidebar separators
    div:             "#2a2a30",   // horizontal rule / divider
    track:           "#27272a",   // input / toggle track backgrounds
    // Text hierarchy — all ≥ WCAG AA on #0a0a0c
    bodyText:        "#e4e4e7",   // zinc-200 — primary text, clearly readable
    muted1:          "#a1a1aa",   // zinc-400 — secondary text
    muted2:          "#71717a",   // zinc-500 — tertiary / placeholders
    midx:            hslToHex(h, s, Math.min(l + (100 - l) * 0.42, 74)),
    // Card surface — elevated above bg by ~4 L steps
    bubble:          "#1c1c22",   // card background
    bubbleReply:     hex,
    bubbleReplyText: textColour(hex),
    actionBg:        "#111115",
    logOk:           "#4ade80",
    logWarn:         "#fb923c",
    activeText:      hex,
  };
}

// ── Apply theme ─────────────────────────────────────────────────────────────

function applyTheme(hex, mode) {
  const tokens = mode === "night" ? computeNightTokens(hex) : computeDayTokens(hex);
  const root = document.documentElement;

  const set = (v, val) => root.style.setProperty(v, val);

  set("--ac",               tokens.ac);
  set("--ac-text",          tokens.acText);
  set("--ac-pale",          tokens.acPale);
  set("--ac-mid",           tokens.acMid);
  set("--ac-dark",          tokens.acDark);
  set("--bg",               tokens.bg);
  set("--border",           tokens.border);
  set("--hdr-bg",           tokens.hdrBg);
  set("--hdr-div",          tokens.hdrDiv);
  set("--hdr-text",         tokens.hdrText);
  set("--hdr-muted",        tokens.hdrMuted);
  set("--nav-bg",           tokens.navBg);
  set("--nav-border",       tokens.navBorder);
  set("--div",              tokens.div);
  set("--track",            tokens.track);
  set("--body-text",        tokens.bodyText);
  set("--muted1",           tokens.muted1);
  set("--muted2",           tokens.muted2);
  set("--midx",             tokens.midx);
  set("--bubble",           tokens.bubble);
  set("--bubble-reply",     tokens.bubbleReply);
  set("--bubble-reply-text",tokens.bubbleReplyText);
  set("--action-bg",        tokens.actionBg);
  set("--log-ok",           tokens.logOk);
  set("--log-warn",         tokens.logWarn);
  set("--active-text",      tokens.activeText);

  // Legacy aliases kept for backward compat with existing CSS
  set("--yellow",           tokens.ac);
  set("--yellow-deep",      tokens.acDark);
  set("--yellow-light",     tokens.acPale);
  set("--yellow-border",    tokens.acMid);
  set("--yellow-glow",      tokens.acMid);
  set("--primary",          tokens.ac);
  set("--text",             tokens.bodyText);
  set("--text-soft",        tokens.muted1);
  set("--muted",            tokens.muted2);
  set("--ink",              tokens.bodyText);
  set("--ink-2",            tokens.bodyText);
  set("--mute",             tokens.muted1);
  set("--mute-2",           tokens.muted2);

  if (mode === "night") {
    // Dark mode overrides for legacy vars
    set("--bg",             tokens.bg);
    set("--dark",           "#14141c");
    set("--card",           tokens.bubble);
    set("--card-solid",     tokens.bubble);
    set("--border-dark",    tokens.border);
    set("--border-light",   tokens.border);
    set("--line",           tokens.border);
    set("--line-soft",      tokens.div);
    document.documentElement.setAttribute("data-theme", "night");
    // Body background
    document.documentElement.style.setProperty("--body-bg", "#09090b");
  } else {
    set("--line",           "rgba(0,0,0,0.10)");
    set("--line-soft",      "rgba(0,0,0,0.06)");
    document.documentElement.removeAttribute("data-theme");
    document.documentElement.style.setProperty("--body-bg", tokens.bg);
  }

  try {
    localStorage.setItem("themeHex", hex);
    localStorage.setItem("themeMode", mode);
  } catch(_) {}

  // Dispatch event so components can react
  window._themeHex  = hex;
  window._themeMode = mode;
  window.dispatchEvent(new CustomEvent("themechange", { detail: { hex, mode, tokens } }));
}

// ── Boot: apply saved or default theme before first paint ──────────────────

(function() {
  const DEFAULT_HEX  = "#F5C800";
  const DEFAULT_MODE = "day";
  let hex  = DEFAULT_HEX;
  let mode = DEFAULT_MODE;
  try {
    hex  = localStorage.getItem("themeHex")  || DEFAULT_HEX;
    mode = localStorage.getItem("themeMode") || DEFAULT_MODE;
  } catch(_) {}
  applyTheme(hex, mode);
})();

// Expose globally
window.applyTheme      = applyTheme;
window.hexToRgb        = hexToRgb;
window.rgbToHsl        = rgbToHsl;
window.hslToHex        = hslToHex;
window.luminance       = luminance;
window.textColour      = textColour;
window.computeDayTokens   = computeDayTokens;
window.computeNightTokens = computeNightTokens;
