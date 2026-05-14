/**
 * Universal custom dropdown — upgrades every <select> on the page.
 * Keeps the original <select> hidden + in-sync so all existing JS (.value reads,
 * event listeners, form submissions) continue to work unchanged.
 */
(function () {
  "use strict";

  /* ── Helpers ── */
  function optionLabel(opt) {
    return opt.textContent.trim();
  }

  function chevronSVG() {
    return `<svg class="dd-chev" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>`;
  }

  function closeAll(except) {
    document.querySelectorAll(".uni-dd-panel.open").forEach((p) => {
      if (p !== except) {
        p.classList.remove("open");
        p.previousElementSibling.classList.remove("open");
      }
    });
  }

  /* ── Build one dropdown ── */
  function buildDropdown(sel) {
    if (sel.classList.contains("dd-upgraded")) return;
    sel.classList.add("dd-upgraded");

    const wrap = document.createElement("div");
    wrap.className = "uni-dd";

    // Mirror width hint from original
    const cs = getComputedStyle(sel);
    if (sel.style.width) wrap.style.width = sel.style.width;

    /* Trigger */
    const trigger = document.createElement("div");
    trigger.className = "uni-dd-trigger";
    trigger.setAttribute("tabindex", "0");

    const valEl = document.createElement("span");
    valEl.className = "uni-dd-val";

    trigger.appendChild(valEl);
    trigger.insertAdjacentHTML("beforeend", chevronSVG());
    wrap.appendChild(trigger);

    /* Panel */
    const panel = document.createElement("div");
    panel.className = "uni-dd-panel";
    wrap.appendChild(panel);

    /* Sync sel → custom UI */
    function syncFromSelect() {
      const sel_val = sel.value;
      valEl.textContent = sel.options[sel.selectedIndex]
        ? optionLabel(sel.options[sel.selectedIndex])
        : "";

      panel.querySelectorAll(".uni-dd-option").forEach((o) => {
        o.classList.toggle("selected", o.dataset.val === sel_val);
      });
    }

    /* Rebuild options (handles dynamically populated selects) */
    function rebuildOptions() {
      panel.innerHTML = "";
      Array.from(sel.options).forEach((opt) => {
        if (opt.disabled && !opt.value) return; // skip placeholder-only disabled
        const o = document.createElement("div");
        o.className = "uni-dd-option" + (opt.selected ? " selected" : "");
        o.dataset.val = opt.value;
        o.textContent = optionLabel(opt);
        if (opt.disabled) o.classList.add("disabled");

        o.addEventListener("click", (e) => {
          e.stopPropagation();
          if (opt.disabled) return;
          sel.value = opt.value;
          sel.dispatchEvent(new Event("change", { bubbles: true }));
          syncFromSelect();
          panel.classList.remove("open");
          trigger.classList.remove("open");
        });

        panel.appendChild(o);
      });
      syncFromSelect();
    }

    /* Open / close */
    trigger.addEventListener("click", (e) => {
      e.stopPropagation();
      const isOpen = panel.classList.contains("open");
      closeAll();
      if (!isOpen) {
        rebuildOptions(); // refresh in case options changed
        panel.classList.add("open");
        trigger.classList.add("open");
        // Flip upward if not enough space below
        const rect = panel.getBoundingClientRect();
        const spaceBelow = window.innerHeight - trigger.getBoundingClientRect().bottom;
        panel.classList.toggle("flip", spaceBelow < 220 && rect.top > 220);
      }
    });

    trigger.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); trigger.click(); }
      if (e.key === "Escape") { panel.classList.remove("open"); trigger.classList.remove("open"); }
    });

    /* Watch select for external value changes (e.g. admin.js sets .value directly) */
    const mo = new MutationObserver(rebuildOptions);
    mo.observe(sel, { childList: true, attributes: true, attributeFilter: ["value"] });

    /* Insert after select, hide original */
    sel.parentNode.insertBefore(wrap, sel.nextSibling);
    sel.style.display = "none";

    rebuildOptions();
  }

  /* ── Upgrade all selects on page ── */
  function upgradeAll() {
    document.querySelectorAll("select:not(.dd-upgraded)").forEach(buildDropdown);
  }

  /* ── Watch for dynamically rendered selects (admin.js fills tables/lists) ── */
  const bodyObserver = new MutationObserver(() => upgradeAll());
  bodyObserver.observe(document.body, { childList: true, subtree: true });

  /* ── Close on outside click ── */
  document.addEventListener("click", () => closeAll());

  /* ── Initial run ── */
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", upgradeAll);
  } else {
    upgradeAll();
  }

  /* ── Global CSS (injected once) ── */
  const style = document.createElement("style");
  style.textContent = `
.uni-dd{position:relative;display:inline-block;min-width:120px;width:100%}

.uni-dd-trigger{
  display:flex;align-items:center;justify-content:space-between;gap:8px;
  padding:9px 12px;
  border-radius:12px;
  border:1.5px solid #0000001a;
  background:#fff;
  font-size:14px;font-family:inherit;color:#15140f;
  cursor:pointer;user-select:none;
  transition:border-color .15s,box-shadow .15s;
  white-space:nowrap;overflow:hidden;
  min-height:40px;
}
.uni-dd-trigger:hover{border-color:#00000028}
.uni-dd-trigger.open{
  border-color:#e8c624;
  box-shadow:0 0 0 3px rgba(244,211,58,.18);
}
.uni-dd-val{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}

.dd-chev{flex-shrink:0;color:#7a7a72;transition:transform .2s}
.uni-dd-trigger.open .dd-chev{transform:rotate(180deg)}

/* Panel */
.uni-dd-panel{
  position:absolute;left:0;top:calc(100% + 6px);z-index:1000;
  min-width:100%;
  background:rgba(254,252,236,0.88);
  backdrop-filter:blur(28px) saturate(180%);
  -webkit-backdrop-filter:blur(28px) saturate(180%);
  border:1px solid rgba(255,255,255,0.72);
  border-radius:16px;
  box-shadow:
    0 12px 40px -8px rgba(0,0,0,.18),
    0 1px 0 rgba(255,255,255,.9) inset;
  padding:6px;
  display:none;
  overflow:hidden;
  max-height:260px;overflow-y:auto;
}
.uni-dd-panel::-webkit-scrollbar{width:4px}
.uni-dd-panel::-webkit-scrollbar-thumb{background:#0000001a;border-radius:4px}
.uni-dd-panel.open{display:block}
.uni-dd-panel.flip{top:auto;bottom:calc(100% + 6px)}

/* Options */
.uni-dd-option{
  padding:9px 12px;
  border-radius:10px;
  font-size:13px;font-family:inherit;color:#15140f;
  cursor:pointer;
  transition:background .1s;
  white-space:nowrap;
}
.uni-dd-option:hover:not(.disabled){background:rgba(0,0,0,.05)}
.uni-dd-option.selected{
  background:#f4d33a;
  font-weight:500;
}
.uni-dd-option.disabled{color:#a3a39a;cursor:default}

/* Small variant — inside tight forms */
.ap-input + .uni-dd .uni-dd-trigger,
.input-sm + .uni-dd .uni-dd-trigger{font-size:13px;padding:8px 12px;min-height:36px}
  `;
  document.head.appendChild(style);
})();
