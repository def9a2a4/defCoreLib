import {
  esc, mcText, stripColors, prettyMaterial, materialImg,
  iconHtml, recipesHtml, hydrateHeads, itemHref,
} from './render.js';

const SLAB_NAMESPACE = 'verticalslabs';

let CATALOG = { items: [], grindRecipes: [], namespaces: [] };
let itemsById = new Map();
let activeNamespace = null;

function card(item) {
  const a = document.createElement('a');
  a.className = 'item-card';
  a.href = itemHref(item.fullId);

  const lore = item.lore.length
    ? `<div class="item-lore">${item.lore.map((l) => `<div class="line">${mcText(l)}</div>`).join('')}</div>`
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

  // Main grid: everything except slabs, honoring the namespace filter.
  const main = CATALOG.items.filter((it) =>
    it.namespace !== SLAB_NAMESPACE &&
    (!activeNamespace || it.namespace === activeNamespace) &&
    matches(it, q));

  const container = document.getElementById('items-container');
  container.innerHTML = '';
  main.forEach((it) => container.appendChild(card(it)));
  document.getElementById('counter').textContent = `${main.length} items`;

  // Vertical slabs: their own section, also filtered by search.
  const slabs = CATALOG.items.filter((it) => it.namespace === SLAB_NAMESPACE && matches(it, q));
  const slabGrid = document.getElementById('slab-grid');
  slabGrid.innerHTML = '';
  slabs.forEach((it) => slabGrid.appendChild(card(it)));
  document.getElementById('slab-section').style.display = slabs.length ? '' : 'none';
  document.getElementById('slab-counter').textContent = `${slabs.length} slabs`;

  hydrateHeads(container);
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

function renderGrind() {
  const grid = document.getElementById('grind-grid');
  grid.innerHTML = '';
  for (const r of CATALOG.grindRecipes) {
    const row = document.createElement('div');
    row.className = 'grind-row';
    const amt = r.amount > 1 ? `<span class="amount">${r.amount}</span>` : '';
    row.innerHTML = `
      <div class="slot" title="${esc(prettyMaterial(r.input))}">${materialImg(r.input, prettyMaterial(r.input))}</div>
      <span class="recipe-arrow">→</span>
      <div class="slot result" title="${esc(prettyMaterial(r.output))}">${materialImg(r.output, prettyMaterial(r.output))}${amt}</div>`;
    grid.appendChild(row);
  }
  document.getElementById('grind-section').style.display = CATALOG.grindRecipes.length ? '' : 'none';
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
  renderGrind();
}

init();
