(() => {
  const state = {
    items: [],
    enchantments: [],
    /** Relevant attrs from API: {id, name, label, amount, operation, slot, source} */
    relevantAttrs: [],
    /** User edits: {id, name, amount, operation, slot} */
    chosenAttrs: [],
    selectedItem: null,
    chosenEnchants: [],
    players: [],
    editingAttrId: null,
    /** Currently selected option in forge enchant picker */
    forgeEnchantPick: null,

    // Inventory Modify tab
    allPlayers: [],
    invSlots: [],
    invSelected: null,
    invEnchantments: [],
    invRelevantAttrs: [],
    invChosenAttrs: [],
    invChosenEnchants: [],
    invEditingAttrId: null,
    invPlayerMeta: null,
    invEnchantPick: null,
    /** Loaded per-line lore styles for inventory appearance: {text,color,bold,italic}[] */
    invLore: [],
  };

  const $ = (id) => document.getElementById(id);

  async function api(path, options) {
    const res = await fetch(path, options);
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
    if (!res.ok) {
      const msg = data.message || data.error || res.statusText;
      throw new Error(msg);
    }
    return data;
  }

  function showToast(msg, ok) {
    const el = $("toast");
    el.hidden = false;
    el.textContent = msg;
    el.className = "toast " + (ok ? "ok" : "err");
  }

  function showInvToast(msg, ok) {
    const el = $("invToast");
    el.hidden = false;
    el.textContent = msg;
    el.className = "toast " + (ok ? "ok" : "err");
  }

  function setStatus(text, ok) {
    const el = $("status");
    el.textContent = text;
    el.className = "status " + (ok ? "ok" : "bad");
  }

  function escapeHtml(s) {
    return String(s)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function toRoman(n) {
    const map = [[1000,"M"],[900,"CM"],[500,"D"],[400,"CD"],[100,"C"],[90,"XC"],[50,"L"],[40,"XL"],[10,"X"],[9,"IX"],[5,"V"],[4,"IV"],[1,"I"]];
    let num = Math.max(1, Math.min(3999, n|0));
    let out = "";
    for (const [v, s] of map) {
      while (num >= v) { out += s; num -= v; }
    }
    return out || String(n);
  }

  /* ===================== TABS ===================== */

  function switchTab(name) {
    document.querySelectorAll(".tab").forEach((btn) => {
      const active = btn.dataset.tab === name;
      btn.classList.toggle("active", active);
      btn.setAttribute("aria-selected", active ? "true" : "false");
    });
    document.querySelectorAll(".tab-panel").forEach((panel) => {
      const active = panel.id === "tab-" + name;
      panel.classList.toggle("active", active);
      panel.hidden = !active;
    });
    if (name === "inventory") {
      loadAllPlayers().catch((e) => showInvToast(e.message, false));
    }
  }

  document.querySelectorAll(".tab").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  });

  /* ===================== FORGE TAB ===================== */

  function displayItemName(it) {
    const filter = $("modFilter").value;
    if (filter !== "all") {
      return it.baseName || it.name || it.id;
    }
    return it.name || it.baseName || it.id;
  }

  function populateModFilter() {
    const sel = $("modFilter");
    const current = sel.value || "all";
    const namespaces = new Map();
    for (const it of state.items) {
      const ns = it.namespace || "minecraft";
      if (!namespaces.has(ns)) {
        namespaces.set(ns, it.source || (ns === "minecraft" ? "Vanilla Minecraft" : ns));
      }
    }
    sel.innerHTML = "";
    const optAll = document.createElement("option");
    optAll.value = "all";
    optAll.textContent = "All sources";
    sel.appendChild(optAll);
    if (namespaces.has("minecraft")) {
      const o = document.createElement("option");
      o.value = "minecraft";
      o.textContent = "Vanilla only";
      sel.appendChild(o);
    }
    [...namespaces.entries()]
      .filter(([ns]) => ns !== "minecraft")
      .sort((a, b) => a[1].localeCompare(b[1]))
      .forEach(([ns, label]) => {
        const o = document.createElement("option");
        o.value = ns;
        o.textContent = label;
        sel.appendChild(o);
      });
    if ([...sel.options].some((o) => o.value === current)) {
      sel.value = current;
    }
  }

  function renderItems() {
    const list = $("itemList");
    list.innerHTML = "";
    const q = $("itemSearch").value.trim().toLowerCase();
    const filter = $("modFilter").value;

    const filtered = state.items.filter((it) => {
      if (filter === "minecraft" && it.namespace !== "minecraft") return false;
      if (filter !== "all" && filter !== "minecraft" && it.namespace !== filter) return false;
      if (!q) return true;
      const hay = (it.id + " " + (it.name || "") + " " + (it.baseName || "") + " " + (it.source || "")).toLowerCase();
      return hay.includes(q);
    }).slice(0, 250);

    for (const it of filtered) {
      const div = document.createElement("div");
      div.className = "list-item" + (state.selectedItem?.id === it.id ? " active" : "");
      const img = document.createElement("img");
      img.src = it.iconUrl;
      img.alt = "";
      img.onerror = () => { img.style.display = "none"; };
      const text = document.createElement("div");
      text.innerHTML = `<div>${escapeHtml(displayItemName(it))}</div><div class="meta">${escapeHtml(it.id)}</div>`;
      div.append(img, text);
      div.onclick = () => selectItem(it);
      list.appendChild(div);
    }
  }

  async function selectItem(it) {
    state.selectedItem = it;
    $("selectedItem").textContent = `Selected: ${displayItemName(it)} (${it.id})`;
    state.chosenAttrs = [];
    state.editingAttrId = null;
    renderItems();
    await loadEnchantmentsForItem();
    await loadRelevantAttributes();
    updatePreview();
    updateIcon();
    updateSubmitState();
  }

  function updateIcon() {
    const img = $("itemIcon");
    const fb = $("itemIconFallback");
    if (!state.selectedItem) {
      img.hidden = true;
      fb.hidden = false;
      fb.textContent = "?";
      return;
    }
    img.hidden = false;
    fb.hidden = true;
    img.src = state.selectedItem.iconUrl + "?t=" + Date.now();
    img.onerror = () => {
      img.hidden = true;
      fb.hidden = false;
      fb.textContent = (state.selectedItem.path || "?")[0]?.toUpperCase() || "?";
    };
  }

  /* ---------- Multi-line enchantment picker ---------- */

  function createEnchantPicker(cfg) {
    const toggle = $(cfg.toggleId);
    const menu = $(cfg.menuId);
    const list = $(cfg.listId);
    const search = $(cfg.searchId);
    let options = [];
    let selected = null;
    let open = false;

    function setOpen(v) {
      open = v;
      menu.hidden = !open;
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      if (open) {
        search.value = "";
        renderList();
        setTimeout(() => search.focus(), 0);
      }
    }

    function enchantLabel(e) {
      // Never surface raw registry ids (minecraft:sharpness) as the visible name
      const n = (e && e.name) ? String(e.name).trim() : "";
      if (n && n !== e.id && !looksLikeRegistryId(n)) return n;
      return humanizeEnchantId(e && e.id);
    }

    function looksLikeRegistryId(s) {
      return typeof s === "string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/i.test(s.trim());
    }

    function humanizeEnchantId(id) {
      if (!id) return "Enchantment";
      const path = String(id).includes(":") ? String(id).split(":").pop() : String(id);
      return path.split(/[_/]+/).filter(Boolean).map((p) =>
        p.charAt(0).toUpperCase() + p.slice(1).toLowerCase()
      ).join(" ") || "Enchantment";
    }

    function updateToggle() {
      const ph = toggle.querySelector(".ep-placeholder");
      const sel = toggle.querySelector(".ep-selected");
      const nameEl = toggle.querySelector(".ep-name");
      const descEl = toggle.querySelector(".ep-desc");
      if (!selected) {
        ph.hidden = false;
        sel.hidden = true;
        nameEl.textContent = "";
        descEl.textContent = "";
        return;
      }
      ph.hidden = true;
      sel.hidden = false;
      nameEl.textContent = `${enchantLabel(selected)} (max ${selected.maxLevel || 1})`;
      // Flavor only — never the registry id
      const flavor = (selected.description || "").trim();
      const showFlavor = flavor && !looksLikeRegistryId(flavor) && flavor !== selected.id;
      descEl.textContent = showFlavor ? flavor : "";
      descEl.hidden = !showFlavor;
    }

    function filtered() {
      const q = (search.value || "").trim().toLowerCase();
      if (!q) return options;
      return options.filter((e) => {
        const hay = [
          e.id, e.name, e.description, e.namespace, `max ${e.maxLevel}`
        ].join(" ").toLowerCase();
        return hay.includes(q);
      });
    }

    function renderList() {
      list.innerHTML = "";
      const rows = filtered();
      if (!rows.length) {
        const empty = document.createElement("div");
        empty.className = "enchant-picker-empty";
        empty.textContent = options.length ? "No matches" : "No enchantments available";
        list.appendChild(empty);
        return;
      }
      for (const e of rows) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "enchant-option" + (selected?.id === e.id ? " active" : "");
        btn.setAttribute("role", "option");
        btn.dataset.id = e.id;
        const name = document.createElement("div");
        name.className = "eo-name";
        name.textContent = enchantLabel(e);
        btn.appendChild(name);
        const flavor = (e.description || "").trim();
        if (flavor && !looksLikeRegistryId(flavor) && flavor !== e.id) {
          const desc = document.createElement("div");
          desc.className = "eo-desc";
          desc.textContent = flavor;
          btn.appendChild(desc);
        }
        const meta = document.createElement("div");
        meta.className = "eo-meta";
        // Human-readable only: max level + optional mod source label (not raw id)
        const source = e.namespace && e.namespace !== "minecraft"
          ? e.namespace.replace(/_/g, " ")
          : "vanilla";
        meta.textContent = `max ${e.maxLevel || 1} · ${source}`;
        btn.title = e.id || ""; // full id only on hover for debugging
        btn.appendChild(meta);
        btn.onclick = () => {
          selected = e;
          updateToggle();
          setOpen(false);
          if (typeof cfg.onSelect === "function") cfg.onSelect(e);
        };
        list.appendChild(btn);
      }
    }

    toggle.addEventListener("click", (ev) => {
      ev.stopPropagation();
      setOpen(!open);
    });
    search.addEventListener("input", renderList);
    search.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape") setOpen(false);
    });

    return {
      setOptions(listIn) {
        options = Array.isArray(listIn) ? listIn : [];
        if (selected && !options.some((o) => o.id === selected.id)) {
          selected = options[0] || null;
        } else if (!selected && options.length) {
          selected = options[0];
        } else if (!options.length) {
          selected = null;
        } else if (selected) {
          selected = options.find((o) => o.id === selected.id) || options[0];
        }
        updateToggle();
        if (open) renderList();
        if (typeof cfg.onSelect === "function" && selected) cfg.onSelect(selected);
      },
      getSelected() { return selected; },
      clear() {
        selected = null;
        options = [];
        updateToggle();
        list.innerHTML = "";
      },
      close() { setOpen(false); },
    };
  }

  function syncEnchantLevelMax(levelInputId, overrideChecked, enchant) {
    const input = $(levelInputId);
    if (!enchant) {
      input.max = overrideChecked ? 255 : 1;
      return;
    }
    input.max = overrideChecked ? 255 : (enchant.maxLevel || 1);
    const cur = parseInt(input.value, 10) || 1;
    if (!overrideChecked && cur > (enchant.maxLevel || 1)) {
      input.value = enchant.maxLevel || 1;
    }
  }

  const forgeEnchantPicker = createEnchantPicker({
    toggleId: "enchantPickerToggle",
    menuId: "enchantPickerMenu",
    listId: "enchantPickerList",
    searchId: "enchantPickerSearch",
    onSelect(e) {
      state.forgeEnchantPick = e;
      syncEnchantLevelMax("enchantLevel", $("overrideLimits").checked, e);
    },
  });

  const invEnchantPicker = createEnchantPicker({
    toggleId: "invEnchantPickerToggle",
    menuId: "invEnchantPickerMenu",
    listId: "invEnchantPickerList",
    searchId: "invEnchantPickerSearch",
    onSelect(e) {
      state.invEnchantPick = e;
      syncEnchantLevelMax("invEnchantLevel", $("invOverrideLimits").checked, e);
    },
  });

  document.addEventListener("click", (ev) => {
    if (!ev.target.closest("#enchantPicker")) forgeEnchantPicker.close();
    if (!ev.target.closest("#invEnchantPicker")) invEnchantPicker.close();
  });

  async function loadEnchantmentsForItem() {
    const override = $("overrideLimits").checked;
    const item = state.selectedItem?.id || "";
    const data = await api(`/api/enchantments?item=${encodeURIComponent(item)}&override=${override}`);
    state.enchantments = data.enchantments || [];
    const visible = state.enchantments.filter((e) => override || e.compatible !== false);
    $("enchantLevel").value = 1;
    forgeEnchantPicker.setOptions(visible);
  }

  async function loadRelevantAttributes() {
    const empty = $("attrEmpty");
    if (!state.selectedItem) {
      state.relevantAttrs = [];
      renderAttributes();
      empty.hidden = false;
      empty.textContent = "Select an item to see attributes.";
      return;
    }
    const enchantsParam = state.chosenEnchants
      .map((e) => `${e.id}:${e.level}`)
      .join(",");
    let url = `/api/attributes?relevant=true&item=${encodeURIComponent(state.selectedItem.id)}`;
    if (enchantsParam) url += `&enchants=${encodeURIComponent(enchantsParam)}`;
    try {
      const data = await api(url);
      state.relevantAttrs = data.attributes || [];
      const allowed = new Set(state.relevantAttrs.map((a) => a.id));
      state.chosenAttrs = state.chosenAttrs.filter((a) => allowed.has(a.id));
      for (const ra of state.relevantAttrs) {
        if (!state.chosenAttrs.some((c) => c.id === ra.id)) {
          state.chosenAttrs.push({
            id: ra.id,
            name: ra.name || ra.id,
            amount: ra.amount ?? 0,
            operation: ra.operation || "ADD_VALUE",
            slot: ra.slot || "any",
            source: ra.source || "",
          });
        }
      }
      renderAttributes();
      empty.hidden = state.relevantAttrs.length > 0;
      if (!state.relevantAttrs.length) {
        empty.textContent = "No attributes on this item or its enchantments.";
      }
    } catch (e) {
      empty.hidden = false;
      empty.textContent = "Failed to load attributes: " + e.message;
    }
    updatePreview();
  }

  function renderEnchantChips() {
    const ul = $("enchantList");
    ul.innerHTML = "";
    for (const e of state.chosenEnchants) {
      const li = document.createElement("li");
      const over = e.level > (e.maxLevel || 1);
      li.innerHTML = `<span>${escapeHtml(displayEnchantName(e))} ${e.level}${over ? " ⚠" : ""}</span>`;
      if (over) li.style.color = "#ff55ff";
      const btn = document.createElement("button");
      btn.type = "button";
      btn.textContent = "Remove";
      btn.onclick = async () => {
        state.chosenEnchants = state.chosenEnchants.filter((x) => x.id !== e.id);
        renderEnchantChips();
        await loadRelevantAttributes();
        updatePreview();
        updateSubmitState();
      };
      li.appendChild(btn);
      ul.appendChild(li);
    }
  }

  function renderAttributes() {
    const ul = $("attrList");
    ul.innerHTML = "";
    for (const a of state.chosenAttrs) {
      const li = document.createElement("li");
      const editing = state.editingAttrId === a.id;
      if (editing) {
        li.className = "editing";
        li.innerHTML = `
          <div><strong>${escapeHtml(a.name || a.id)}</strong>
            <div class="attr-meta">${escapeHtml(a.source || "")}</div></div>
          <div class="attr-edit-row">
            <input type="number" step="0.01" value="${a.amount}" data-attr-input />
            <button type="button" data-act="save">Save</button>
            <button type="button" data-act="cancel">Cancel</button>
          </div>`;
        li.querySelector('[data-act="save"]').onclick = (ev) => {
          ev.stopPropagation();
          const input = li.querySelector("[data-attr-input]");
          a.amount = parseFloat(input.value) || 0;
          state.editingAttrId = null;
          renderAttributes();
          updatePreview();
        };
        li.querySelector('[data-act="cancel"]').onclick = (ev) => {
          ev.stopPropagation();
          state.editingAttrId = null;
          renderAttributes();
        };
      } else {
        const sign = a.amount >= 0 ? "+" : "";
        li.innerHTML = `
          <div>
            <div>${escapeHtml(a.name || a.id)} <strong>${sign}${a.amount}</strong></div>
            <div class="attr-meta">${escapeHtml(a.source || a.id)}</div>
          </div>
          <span class="hint">Click to edit</span>`;
        li.onclick = () => {
          state.editingAttrId = a.id;
          renderAttributes();
        };
      }
      ul.appendChild(li);
    }
  }

  function renderPlayers() {
    const sel = $("playerSelect");
    const current = sel.value;
    sel.innerHTML = "";
    if (!state.players.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "(no players online)";
      sel.appendChild(opt);
      return;
    }
    for (const p of state.players) {
      const opt = document.createElement("option");
      opt.value = p.uuid;
      opt.textContent = p.name;
      sel.appendChild(opt);
    }
    if (current && [...sel.options].some((o) => o.value === current)) {
      sel.value = current;
    }
  }

  function loreLines() {
    const color = $("loreColor").value;
    const italic = $("loreItalic").checked;
    const bold = $("loreBold").checked;
    return $("loreText").value.split("\n")
      .map((t) => t.trimEnd())
      .filter((t) => t.length)
      .map((text) => ({ text, color, italic, bold }));
  }

  function needsOverride() {
    for (const e of state.chosenEnchants) {
      if (e.level > (e.maxLevel || 1)) return true;
    }
    return false;
  }

  function updateSubmitState() {
    const btn = $("submit");
    const msg = $("overrideRequiredMsg");
    const need = needsOverride();
    const overrideOn = $("overrideLimits").checked;
    if (need && !overrideOn) {
      btn.disabled = true;
      msg.hidden = false;
      const offenders = state.chosenEnchants
        .filter((e) => e.level > (e.maxLevel || 1))
        .map((e) => `${e.name || e.id} ${e.level} (max ${e.maxLevel})`)
        .join(", ");
      msg.innerHTML = `Enable <strong>Override limits</strong> — configuration exceeds normal max levels: <em>${escapeHtml(offenders)}</em>`;
    } else {
      btn.disabled = !state.selectedItem || !state.players.length;
      msg.hidden = true;
    }
  }

  function updatePreview() {
    const tt = $("tooltip");
    tt.innerHTML = "";
    if (!state.selectedItem) {
      tt.innerHTML = `<div class="tt-empty">Select an item</div>`;
      return;
    }

    const nameEl = document.createElement("div");
    nameEl.className = "tt-name";
    const custom = $("itemName").value.trim();
    nameEl.textContent = custom || displayItemName(state.selectedItem);
    nameEl.style.color = custom ? $("nameColor").value : "#ffffff";
    nameEl.style.fontWeight = $("nameBold").checked ? "700" : "400";
    nameEl.style.fontStyle = $("nameItalic").checked ? "italic" : "normal";
    tt.appendChild(nameEl);

    for (const e of state.chosenEnchants) {
      const line = document.createElement("div");
      line.className = "tt-enchant";
      const roman = toRoman(e.level);
      line.textContent = `${displayEnchantName(e)}${e.level > 1 ? " " + roman : ""}`;
      if (e.level > (e.maxLevel || 1)) line.style.color = "#ff55ff";
      tt.appendChild(line);
    }

    for (const line of loreLines()) {
      const el = document.createElement("div");
      el.className = "tt-lore";
      el.textContent = line.text;
      el.style.color = line.color;
      el.style.fontStyle = line.italic ? "italic" : "normal";
      el.style.fontWeight = line.bold ? "700" : "400";
      tt.appendChild(el);
    }

    for (const a of state.chosenAttrs) {
      const el = document.createElement("div");
      el.className = "tt-attr";
      const sign = a.amount >= 0 ? "+" : "";
      el.textContent = `${sign}${a.amount} ${a.name || a.id}`;
      tt.appendChild(el);
    }
  }

  async function loadBootstrap() {
    const health = await api("/api/health");
    setStatus(health.serverReady ? "Server ready" : "Server not ready", !!health.serverReady);

    const items = await api("/api/items?limit=10000");
    state.items = items.items || [];
    populateModFilter();
    renderItems();

    const players = await api("/api/players");
    state.players = players.players || [];
    renderPlayers();
    startPlayerStream();
    updateSubmitState();
  }

  function startPlayerStream() {
    try {
      const es = new EventSource("/api/players/stream");
      es.onmessage = (ev) => {
        try {
          state.players = JSON.parse(ev.data) || [];
          renderPlayers();
          updateSubmitState();
        } catch (_) { /* ignore */ }
      };
      es.onerror = () => {
        es.close();
        setInterval(async () => {
          try {
            const players = await api("/api/players");
            state.players = players.players || [];
            renderPlayers();
            updateSubmitState();
          } catch (_) { /* ignore */ }
        }, 2000);
      };
    } catch (_) {
      setInterval(async () => {
        try {
          const players = await api("/api/players");
          state.players = players.players || [];
          renderPlayers();
          updateSubmitState();
        } catch (e) { /* ignore */ }
      }, 2000);
    }
  }

  $("itemSearch").addEventListener("input", renderItems);
  $("modFilter").addEventListener("change", renderItems);

  $("overrideLimits").addEventListener("change", async () => {
    await loadEnchantmentsForItem();
    updateSubmitState();
  });

  function displayEnchantName(e) {
    if (!e) return "Enchantment";
    const n = (e.name || "").trim();
    if (n && n !== e.id && !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/i.test(n)) return n;
    const id = e.id || "";
    const path = id.includes(":") ? id.split(":").pop() : id;
    return path.split(/[_/]+/).filter(Boolean).map((p) =>
      p.charAt(0).toUpperCase() + p.slice(1).toLowerCase()
    ).join(" ") || "Enchantment";
  }

  $("addEnchant").onclick = async () => {
    const pick = forgeEnchantPicker.getSelected() || state.forgeEnchantPick;
    if (!pick?.id) return;
    const level = parseInt($("enchantLevel").value, 10) || 1;
    const maxLevel = pick.maxLevel || 1;
    const name = displayEnchantName(pick);
    state.chosenEnchants = state.chosenEnchants.filter((e) => e.id !== pick.id);
    state.chosenEnchants.push({ id: pick.id, level, name, maxLevel });
    renderEnchantChips();
    await loadRelevantAttributes();
    updatePreview();
    updateSubmitState();
  };

  ["itemName", "nameColor", "nameBold", "nameItalic", "loreText", "loreColor", "loreItalic", "loreBold"]
    .forEach((id) => $(id).addEventListener("input", updatePreview));

  $("submit").onclick = async () => {
    if (!state.selectedItem) {
      showToast("Select an item first", false);
      return;
    }
    if (needsOverride() && !$("overrideLimits").checked) {
      showToast("Enable Override limits for this configuration", false);
      return;
    }
    const uuid = $("playerSelect").value;
    if (!uuid) {
      showToast("Select an online player", false);
      return;
    }
    const body = {
      itemId: state.selectedItem.id,
      overrideLimits: $("overrideLimits").checked,
      name: {
        text: $("itemName").value,
        color: $("nameColor").value,
        bold: $("nameBold").checked,
        italic: $("nameItalic").checked,
      },
      lore: loreLines(),
      enchantments: state.chosenEnchants.map((e) => ({ id: e.id, level: e.level })),
      attributes: state.chosenAttrs.map((a) => ({
        id: a.id,
        amount: a.amount,
        operation: a.operation || "ADD_VALUE",
        slot: a.slot || "any",
      })),
      targetPlayerUuid: uuid,
    };
    try {
      const res = await api("/api/forge", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      showToast(res.message || "Done", !!res.success);
    } catch (e) {
      showToast(e.message || String(e), false);
    }
  };

  /* ===================== INVENTORY MODIFY TAB ===================== */

  async function loadAllPlayers() {
    const data = await api("/api/players/all");
    state.allPlayers = data.players || [];
    renderInvPlayers();
  }

  function renderInvPlayers() {
    const sel = $("invPlayerSelect");
    const current = sel.value;
    sel.innerHTML = "";
    if (!state.allPlayers.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "(no known players)";
      sel.appendChild(opt);
      return;
    }
    for (const p of state.allPlayers) {
      const opt = document.createElement("option");
      opt.value = p.uuid;
      opt.textContent = `${p.name}${p.online ? " ● online" : " ○ offline"}`;
      sel.appendChild(opt);
    }
    if (current && [...sel.options].some((o) => o.value === current)) {
      sel.value = current;
    }
  }

  async function loadInventory() {
    const uuid = $("invPlayerSelect").value;
    if (!uuid) {
      showInvToast("Select a player first", false);
      return;
    }
    const forgeableOnly = $("invForgeableOnly").checked;
    try {
      const data = await api(
        `/api/inventory?uuid=${encodeURIComponent(uuid)}&forgeableOnly=${forgeableOnly}`
      );
      state.invSlots = data.slots || [];
      state.invPlayerMeta = data;
      state.invSelected = null;
      state.invChosenEnchants = [];
      state.invChosenAttrs = [];
      $("invSelected").textContent = "No slot selected";
      $("invPlayerMeta").innerHTML =
        `${escapeHtml(data.name || uuid)} · ` +
        `<span class="online-badge ${data.online ? "on" : "off"}">${data.online ? "online" : "offline"}</span> · ` +
        `${data.count ?? state.invSlots.length} item(s)`;
      renderInvSlots();
      renderInvEnchantChips();
      renderInvAttributes();
      updateInvPreview();
      updateInvIcon();
      updateInvApplyState();
      showInvToast(`Loaded ${state.invSlots.length} item(s)`, true);
    } catch (e) {
      showInvToast(e.message || String(e), false);
    }
  }

  function renderInvSlots() {
    const list = $("invSlotList");
    list.innerHTML = "";
    const q = $("invSearch").value.trim().toLowerCase();
    const filtered = state.invSlots.filter((s) => {
      if (!q) return true;
      const hay = (
        (s.itemId || "") + " " +
        (s.name || "") + " " +
        (s.location || "") + " " +
        (s.path || "")
      ).toLowerCase();
      return hay.includes(q);
    });

    if (!filtered.length) {
      const empty = document.createElement("div");
      empty.className = "list-item";
      empty.innerHTML = `<div class="meta">${state.invSlots.length ? "No matches" : "No items (refresh or uncheck filter)"}</div>`;
      list.appendChild(empty);
      return;
    }

    for (const s of filtered) {
      const div = document.createElement("div");
      div.className = "list-item" + (state.invSelected?.path === s.path ? " active" : "");
      const img = document.createElement("img");
      img.src = s.iconUrl || "";
      img.alt = "";
      img.onerror = () => { img.style.display = "none"; };
      const text = document.createElement("div");
      const count = s.count > 1 ? ` ×${s.count}` : "";
      const enchCount = (s.enchantments || []).length;
      const enchHint = enchCount ? ` · ${enchCount} enchant${enchCount > 1 ? "s" : ""}` : "";
      text.innerHTML =
        `<div>${escapeHtml(s.name || s.itemId)}${count}</div>` +
        `<div class="loc">${escapeHtml(s.location || s.path || "")}</div>` +
        `<div class="meta">${escapeHtml(s.itemId || "")}${enchHint}</div>`;
      div.append(img, text);
      div.onclick = () => selectInvSlot(s);
      list.appendChild(div);
    }
  }

  async function selectInvSlot(slot) {
    state.invSelected = slot;
    state.invEditingAttrId = null;
    $("invSelected").textContent =
      `Selected: ${slot.name || slot.itemId} @ ${slot.location || slot.path}`;

    // Seed enchants from existing item
    state.invChosenEnchants = (slot.enchantments || []).map((e) => ({
      id: e.id,
      level: e.level,
      name: displayEnchantName(e),
      maxLevel: e.maxLevel || 1,
    }));

    // Prefill custom name + style only when the item has an explicit custom name
    const cn = slot.customName;
    if (cn && (cn.text || "").trim()) {
      $("invItemName").value = cn.text;
      if (cn.color) $("invNameColor").value = normalizeHexColor(cn.color, "#55ffff");
      $("invNameBold").checked = !!cn.bold;
      $("invNameItalic").checked = !!cn.italic;
    } else {
      $("invItemName").value = "";
      $("invNameColor").value = "#55ffff";
      $("invNameBold").checked = false;
      $("invNameItalic").checked = false;
    }

    // Prefill lore / flavor text so apply does not wipe existing lines
    state.invLore = Array.isArray(slot.lore)
      ? slot.lore
          .filter((l) => l && String(l.text || "").length)
          .map((l) => ({
            text: String(l.text),
            color: l.color || "#aaaaaa",
            bold: !!l.bold,
            italic: l.italic !== false,
          }))
      : [];
    $("invLoreText").value = state.invLore.map((l) => l.text).join("\n");
    if (state.invLore.length) {
      const first = state.invLore[0];
      $("invLoreColor").value = normalizeHexColor(first.color, "#aaaaaa");
      $("invLoreItalic").checked = first.italic !== false;
      $("invLoreBold").checked = !!first.bold;
    } else {
      $("invLoreColor").value = "#aaaaaa";
      $("invLoreItalic").checked = true;
      $("invLoreBold").checked = false;
    }

    renderInvSlots();
    renderInvEnchantChips();
    await loadInvEnchantments();
    await loadInvRelevantAttributes(true);
    updateInvPreview();
    updateInvIcon();
    updateInvApplyState();
  }

  /** Accept #RGB / #RRGGBB / RRGGBB for <input type="color">. */
  function normalizeHexColor(raw, fallback) {
    if (!raw || typeof raw !== "string") return fallback;
    let s = raw.trim();
    if (!s.startsWith("#")) s = "#" + s;
    if (/^#[0-9a-fA-F]{6}$/.test(s)) return s.toLowerCase();
    if (/^#[0-9a-fA-F]{3}$/.test(s)) {
      const r = s[1], g = s[2], b = s[3];
      return ("#" + r + r + g + g + b + b).toLowerCase();
    }
    return fallback;
  }

  function updateInvIcon() {
    const img = $("invItemIcon");
    const fb = $("invItemIconFallback");
    if (!state.invSelected) {
      img.hidden = true;
      fb.hidden = false;
      fb.textContent = "?";
      return;
    }
    img.hidden = false;
    fb.hidden = true;
    img.src = (state.invSelected.iconUrl || "") + "?t=" + Date.now();
    img.onerror = () => {
      img.hidden = true;
      fb.hidden = false;
      fb.textContent = (state.invSelected.pathName || "?")[0]?.toUpperCase() || "?";
    };
  }

  async function loadInvEnchantments() {
    if (!state.invSelected) return;
    const override = $("invOverrideLimits").checked;
    const item = state.invSelected.itemId || "";
    const data = await api(`/api/enchantments?item=${encodeURIComponent(item)}&override=${override}`);
    state.invEnchantments = data.enchantments || [];
    const maxById = new Map(state.invEnchantments.map((e) => [e.id, e.maxLevel || 1]));
    for (const ce of state.invChosenEnchants) {
      if (maxById.has(ce.id)) ce.maxLevel = maxById.get(ce.id);
    }
    const visible = state.invEnchantments.filter((e) => override || e.compatible !== false);
    $("invEnchantLevel").value = 1;
    invEnchantPicker.setOptions(visible);
    renderInvEnchantChips();
  }

  async function loadInvRelevantAttributes(seedFromSlot) {
    const empty = $("invAttrEmpty");
    if (!state.invSelected) {
      state.invRelevantAttrs = [];
      state.invChosenAttrs = [];
      renderInvAttributes();
      empty.hidden = false;
      empty.textContent = "Select an inventory item first.";
      return;
    }
    const enchantsParam = state.invChosenEnchants
      .map((e) => `${e.id}:${e.level}`)
      .join(",");
    let url = `/api/attributes?relevant=true&item=${encodeURIComponent(state.invSelected.itemId)}`;
    if (enchantsParam) url += `&enchants=${encodeURIComponent(enchantsParam)}`;
    try {
      const data = await api(url);
      state.invRelevantAttrs = data.attributes || [];
      const allowed = new Set(state.invRelevantAttrs.map((a) => a.id));
      state.invChosenAttrs = state.invChosenAttrs.filter((a) => allowed.has(a.id));

      // Overlay amounts from the live item on first select
      const fromItem = new Map();
      if (seedFromSlot && state.invSelected.attributes) {
        for (const a of state.invSelected.attributes) {
          fromItem.set(a.id, a);
        }
      }

      for (const ra of state.invRelevantAttrs) {
        const existing = state.invChosenAttrs.find((c) => c.id === ra.id);
        if (existing) continue;
        const itemAttr = fromItem.get(ra.id);
        state.invChosenAttrs.push({
          id: ra.id,
          name: ra.name || ra.id,
          amount: itemAttr ? itemAttr.amount : (ra.amount ?? 0),
          operation: itemAttr?.operation || ra.operation || "ADD_VALUE",
          slot: itemAttr?.slot || ra.slot || "any",
          source: ra.source || "",
        });
      }

      // Include item-only attrs not in relevant list (custom mods)
      if (seedFromSlot && state.invSelected.attributes) {
        for (const a of state.invSelected.attributes) {
          if (!state.invChosenAttrs.some((c) => c.id === a.id)) {
            state.invChosenAttrs.push({
              id: a.id,
              name: a.id,
              amount: a.amount ?? 0,
              operation: a.operation || "ADD_VALUE",
              slot: a.slot || "any",
              source: "on item",
            });
          }
        }
      }

      renderInvAttributes();
      empty.hidden = state.invChosenAttrs.length > 0;
      if (!state.invChosenAttrs.length) {
        empty.textContent = "No attributes on this item or its enchantments.";
      }
    } catch (e) {
      empty.hidden = false;
      empty.textContent = "Failed to load attributes: " + e.message;
    }
    updateInvPreview();
  }

  function renderInvEnchantChips() {
    const ul = $("invEnchantList");
    ul.innerHTML = "";
    for (const e of state.invChosenEnchants) {
      const li = document.createElement("li");
      const over = e.level > (e.maxLevel || 1);
      li.innerHTML = `<span>${escapeHtml(displayEnchantName(e))} ${e.level}${over ? " ⚠" : ""}</span>`;
      if (over) li.style.color = "#ff55ff";
      const btn = document.createElement("button");
      btn.type = "button";
      btn.textContent = "Remove";
      btn.onclick = async () => {
        state.invChosenEnchants = state.invChosenEnchants.filter((x) => x.id !== e.id);
        renderInvEnchantChips();
        await loadInvRelevantAttributes(false);
        updateInvPreview();
        updateInvApplyState();
      };
      li.appendChild(btn);
      ul.appendChild(li);
    }
  }

  function renderInvAttributes() {
    const ul = $("invAttrList");
    ul.innerHTML = "";
    for (const a of state.invChosenAttrs) {
      const li = document.createElement("li");
      const editing = state.invEditingAttrId === a.id;
      if (editing) {
        li.className = "editing";
        li.innerHTML = `
          <div><strong>${escapeHtml(a.name || a.id)}</strong>
            <div class="attr-meta">${escapeHtml(a.source || "")}</div></div>
          <div class="attr-edit-row">
            <input type="number" step="0.01" value="${a.amount}" data-attr-input />
            <button type="button" data-act="save">Save</button>
            <button type="button" data-act="cancel">Cancel</button>
          </div>`;
        li.querySelector('[data-act="save"]').onclick = (ev) => {
          ev.stopPropagation();
          const input = li.querySelector("[data-attr-input]");
          a.amount = parseFloat(input.value) || 0;
          state.invEditingAttrId = null;
          renderInvAttributes();
          updateInvPreview();
        };
        li.querySelector('[data-act="cancel"]').onclick = (ev) => {
          ev.stopPropagation();
          state.invEditingAttrId = null;
          renderInvAttributes();
        };
      } else {
        const sign = a.amount >= 0 ? "+" : "";
        li.innerHTML = `
          <div>
            <div>${escapeHtml(a.name || a.id)} <strong>${sign}${a.amount}</strong></div>
            <div class="attr-meta">${escapeHtml(a.source || a.id)}</div>
          </div>
          <span class="hint">Click to edit</span>`;
        li.onclick = () => {
          state.invEditingAttrId = a.id;
          renderInvAttributes();
        };
      }
      ul.appendChild(li);
    }
  }

  function invLoreLines() {
    const color = $("invLoreColor").value;
    const italic = $("invLoreItalic").checked;
    const bold = $("invLoreBold").checked;
    const texts = $("invLoreText").value.split("\n")
      .map((t) => t.trimEnd())
      .filter((t) => t.length);

    // If the user has not changed the global style controls from the loaded first line,
    // preserve per-line styles for lines that still match their original text.
    const loaded = state.invLore || [];
    const first = loaded[0];
    const styleUntouched = !!(first &&
      normalizeHexColor(first.color || "#aaaaaa", "#aaaaaa") === color &&
      (first.italic !== false) === italic &&
      !!first.bold === bold);

    return texts.map((text, i) => {
      if (styleUntouched && loaded[i] && loaded[i].text === text) {
        return {
          text,
          color: loaded[i].color || color,
          italic: loaded[i].italic !== false,
          bold: !!loaded[i].bold,
        };
      }
      if (styleUntouched && loaded[i]) {
        // Text edited but style controls unchanged: keep original line style for that index
        return {
          text,
          color: loaded[i].color || color,
          italic: loaded[i].italic !== false,
          bold: !!loaded[i].bold,
        };
      }
      return { text, color, italic, bold };
    });
  }

  function invNeedsOverride() {
    for (const e of state.invChosenEnchants) {
      if (e.level > (e.maxLevel || 1)) return true;
    }
    return false;
  }

  function updateInvApplyState() {
    const btn = $("invApply");
    const msg = $("invOverrideRequiredMsg");
    const need = invNeedsOverride();
    const overrideOn = $("invOverrideLimits").checked;
    if (!state.invSelected) {
      btn.disabled = true;
      msg.hidden = true;
      return;
    }
    if (need && !overrideOn) {
      btn.disabled = true;
      msg.hidden = false;
      const offenders = state.invChosenEnchants
        .filter((e) => e.level > (e.maxLevel || 1))
        .map((e) => `${e.name || e.id} ${e.level} (max ${e.maxLevel})`)
        .join(", ");
      msg.innerHTML = `Enable <strong>Override limits</strong> — exceeds max: <em>${escapeHtml(offenders)}</em>`;
    } else {
      btn.disabled = false;
      msg.hidden = true;
    }
  }

  function updateInvPreview() {
    const tt = $("invTooltip");
    tt.innerHTML = "";
    if (!state.invSelected) {
      tt.innerHTML = `<div class="tt-empty">Select an inventory item</div>`;
      return;
    }

    const nameEl = document.createElement("div");
    nameEl.className = "tt-name";
    const custom = $("invItemName").value.trim();
    nameEl.textContent = custom || state.invSelected.name || state.invSelected.itemId;
    nameEl.style.color = custom ? $("invNameColor").value : "#ffffff";
    nameEl.style.fontWeight = $("invNameBold").checked ? "700" : "400";
    nameEl.style.fontStyle = $("invNameItalic").checked ? "italic" : "normal";
    tt.appendChild(nameEl);

    for (const e of state.invChosenEnchants) {
      const line = document.createElement("div");
      line.className = "tt-enchant";
      const roman = toRoman(e.level);
      line.textContent = `${displayEnchantName(e)}${e.level > 1 ? " " + roman : ""}`;
      if (e.level > (e.maxLevel || 1)) line.style.color = "#ff55ff";
      tt.appendChild(line);
    }

    for (const line of invLoreLines()) {
      const el = document.createElement("div");
      el.className = "tt-lore";
      el.textContent = line.text;
      el.style.color = line.color;
      el.style.fontStyle = line.italic ? "italic" : "normal";
      el.style.fontWeight = line.bold ? "700" : "400";
      tt.appendChild(el);
    }

    for (const a of state.invChosenAttrs) {
      const el = document.createElement("div");
      el.className = "tt-attr";
      const sign = a.amount >= 0 ? "+" : "";
      el.textContent = `${sign}${a.amount} ${a.name || a.id}`;
      tt.appendChild(el);
    }
  }

  $("invRefresh").onclick = () => loadInventory();
  $("invPlayerSelect").addEventListener("change", () => {
    state.invSlots = [];
    state.invSelected = null;
    renderInvSlots();
    $("invSelected").textContent = "No slot selected";
    $("invPlayerMeta").textContent = "";
    updateInvPreview();
    updateInvIcon();
    updateInvApplyState();
  });
  $("invForgeableOnly").addEventListener("change", () => {
    if ($("invPlayerSelect").value) loadInventory();
  });
  $("invSearch").addEventListener("input", renderInvSlots);

  $("invOverrideLimits").addEventListener("change", async () => {
    await loadInvEnchantments();
    updateInvApplyState();
  });

  $("invAddEnchant").onclick = async () => {
    if (!state.invSelected) return;
    const pick = invEnchantPicker.getSelected() || state.invEnchantPick;
    if (!pick?.id) return;
    const level = parseInt($("invEnchantLevel").value, 10) || 1;
    const maxLevel = pick.maxLevel || 1;
    const name = displayEnchantName(pick);
    state.invChosenEnchants = state.invChosenEnchants.filter((e) => e.id !== pick.id);
    state.invChosenEnchants.push({ id: pick.id, level, name, maxLevel });
    renderInvEnchantChips();
    await loadInvRelevantAttributes(false);
    updateInvPreview();
    updateInvApplyState();
  };

  ["invItemName", "invNameColor", "invNameBold", "invNameItalic",
   "invLoreText", "invLoreColor", "invLoreItalic", "invLoreBold"]
    .forEach((id) => $(id).addEventListener("input", updateInvPreview));

  $("invApply").onclick = async () => {
    if (!state.invSelected) {
      showInvToast("Select an inventory item first", false);
      return;
    }
    if (invNeedsOverride() && !$("invOverrideLimits").checked) {
      showInvToast("Enable Override limits for this configuration", false);
      return;
    }
    const uuid = $("invPlayerSelect").value;
    if (!uuid) {
      showInvToast("Select a player", false);
      return;
    }
    const body = {
      uuid,
      path: state.invSelected.path,
      itemId: state.invSelected.itemId,
      overrideLimits: $("invOverrideLimits").checked,
      name: {
        text: $("invItemName").value,
        color: $("invNameColor").value,
        bold: $("invNameBold").checked,
        italic: $("invNameItalic").checked,
      },
      lore: invLoreLines(),
      enchantments: state.invChosenEnchants.map((e) => ({ id: e.id, level: e.level })),
      attributes: state.invChosenAttrs.map((a) => ({
        id: a.id,
        amount: a.amount,
        operation: a.operation || "ADD_VALUE",
        slot: a.slot || "any",
      })),
    };
    try {
      const res = await api("/api/inventory/modify", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      showInvToast(res.message || "Applied", !!res.success);
      if (res.success) {
        // Reload inventory and reselect same path
        const path = state.invSelected.path;
        await loadInventory();
        const again = state.invSlots.find((s) => s.path === path);
        if (again) await selectInvSlot(again);
      }
    } catch (e) {
      showInvToast(e.message || String(e), false);
    }
  };

  loadBootstrap().catch((e) => {
    setStatus("Failed: " + e.message, false);
    showToast(e.message, false);
  });
})();
