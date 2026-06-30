import {
  esc, mcText, stripColors, iconHtml, recipesHtml, hydrateHeads, itemHref,
} from './render.js';

const SLAB_NAMESPACE = 'verticalslabs';
const NS_LABELS = { rotation: 'Rotation', demo: 'Demo', corelib: 'Core' };
const NS_PRIORITY = ['rotation', 'corelib', 'demo'];   // shown first, in this order; others follow alphabetically

// Non-slab namespaces in display order: priority ones first, then the rest alphabetically.
function orderedNamespaces() {
  const all = CATALOG.namespaces.filter((ns) => ns !== SLAB_NAMESPACE);
  const rest = all.filter((ns) => !NS_PRIORITY.includes(ns)).sort();
  return [...NS_PRIORITY.filter((ns) => all.includes(ns)), ...rest];
}
const nsLabel = (ns) => NS_LABELS[ns] || ns.charAt(0).toUpperCase() + ns.slice(1);

let CATALOG = { items: [], grindRecipes: [], namespaces: [] };
let itemsById = new Map();
let activeNamespace = null;

function card(item) {
  const a = document.createElement('a');
  a.className = 'item-card';
  a.href = itemHref(item.fullId);

  const loreLines = item.lore || [];
  const lore = loreLines.length
    ? `<div class="item-lore">${loreLines.map((l) => `<div class="line">${mcText(l)}</div>`).join('')}</div>`
    : '';
  const badge = item.variants?.length
    ? `<span class="state-badge" title="${item.variants.length} states/textures">${item.variants.length} states</span>`
    : '';

  a.innerHTML = `
    <div class="item-header">
      <div class="item-icon ${item.glint ? 'glint' : ''}">${iconHtml(item)}</div>
      <div class="item-title">
        <div class="item-name">${mcText(item.name)}</div>
        <div class="item-id">${esc(item.fullId)} ${badge}</div>
      </div>
    </div>
    ${lore}
    ${recipesHtml(item, itemsById)}
  `;
  return a;
}

function currentQuery() {
  return document.getElementById('search-input').value.trim().toLowerCase();
}

function matches(it, q) {
  return !q || stripColors(it.name).toLowerCase().includes(q) || it.fullId.toLowerCase().includes(q);
}

function render() {
  const q = currentQuery();

  // One titled section per non-slab namespace (rotation first), honoring search + the pill filter.
  const container = document.getElementById('items-container');
  container.innerHTML = '';
  let total = 0;
  for (const ns of orderedNamespaces()) {
    if (activeNamespace && activeNamespace !== ns) continue;
    const its = CATALOG.items.filter((it) => it.namespace === ns && matches(it, q));
    if (!its.length) continue;
    total += its.length;

    const section = document.createElement('section');
    section.innerHTML = `<h2 class="section-title">${esc(nsLabel(ns))} <span class="counter inline">${its.length} items</span></h2>`;
    const grid = document.createElement('div');
    grid.className = 'item-grid';
    its.forEach((it) => grid.appendChild(card(it)));
    section.appendChild(grid);
    container.appendChild(section);
    hydrateHeads(grid);
  }
  document.getElementById('counter').textContent = `${total} items`;

  // Vertical slabs: their own section (only when not filtered to another namespace).
  const slabs = (!activeNamespace) ? CATALOG.items.filter((it) => it.namespace === SLAB_NAMESPACE && matches(it, q)) : [];
  const slabGrid = document.getElementById('slab-grid');
  slabGrid.innerHTML = '';
  slabs.forEach((it) => slabGrid.appendChild(card(it)));
  document.getElementById('slab-section').style.display = slabs.length ? '' : 'none';
  document.getElementById('slab-counter').textContent = `${slabs.length} slabs`;
  hydrateHeads(slabGrid);
}

function renderPills() {
  const pills = document.getElementById('namespace-pills');
  const make = (label, ns) => {
    const el = document.createElement('button');
    el.className = 'pill' + (activeNamespace === ns ? ' active' : '');
    el.textContent = label;
    el.onclick = () => { activeNamespace = activeNamespace === ns ? null : ns; renderPills(); render(); };
    return el;
  };
  pills.innerHTML = '';
  pills.appendChild(make('All', null));
  // Slabs have their own section, so they're not a main-grid filter.
  CATALOG.namespaces.filter((ns) => ns !== SLAB_NAMESPACE).forEach((ns) => pills.appendChild(make(ns, ns)));
}

async function init() {
  try {
    const res = await fetch('./data/items.json');
    if (!res.ok) throw new Error(`items.json: ${res.status}`);
    CATALOG = await res.json();
  } catch (e) {
    const err = document.getElementById('error');
    err.style.display = 'block';
    err.textContent = 'Failed to load catalog: ' + e.message;
    return;
  }
  itemsById = new Map(CATALOG.items.map((it) => [it.fullId, it]));
  document.getElementById('search-input').addEventListener('input', render);
  renderPills();
  render();
}

init();
