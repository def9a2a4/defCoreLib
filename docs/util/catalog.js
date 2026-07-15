import {
  esc, mcText, stripColors, iconHtml, recipesHtml, producedByHtml, hydrateHeads, itemHref,
} from './render.js';
import { thumbnailDataURL, placedVariantBlocks } from './placed3d.js';

const SLAB_NAMESPACE = 'verticalslabs';
const NS_LABELS = { mech: 'Mechanism', demo: 'Demo', corelib: 'Core' };
const NS_PRIORITY = ['mech', 'corelib', 'demo'];   // shown first, in this order; others follow alphabetically

// Non-slab namespaces in display order: priority ones first, then the rest alphabetically.
function orderedNamespaces() {
  const all = CATALOG.namespaces.filter((ns) => ns !== SLAB_NAMESPACE);
  const rest = all.filter((ns) => !NS_PRIORITY.includes(ns)).sort();
  return [...NS_PRIORITY.filter((ns) => all.includes(ns)), ...rest];
}
const nsLabel = (ns) => NS_LABELS[ns] || ns.charAt(0).toUpperCase() + ns.slice(1);

let CATALOG = { items: [], namespaces: [] };
let itemsById = new Map();
let activeNamespace = null;
let subsetIds = null;   // Set<fullId> or null when no ?items= given

// Lazily fills each card's placed-thumbnail (static PNG, generated offscreen → 0 live WebGL contexts on
// the grid). Reset + re-armed every render() so search/filter re-renders don't retain detached cards.
const thumbObserver = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    const el = e.target;
    thumbObserver.unobserve(el);
    const item = itemsById.get(el.dataset.thumb);
    if (!item) continue;
    thumbnailDataURL(placedVariantBlocks(item), { size: 300, cacheKey: item.fullId }).then((url) => {
      if (!url || !el.isConnected) return;
      const img = new Image();
      img.src = url;
      img.alt = '';
      img.className = 'placed-thumb-img';
      el.appendChild(img);
    });
  }
}, { rootMargin: '200px' });

function card(item) {
  const a = document.createElement('a');
  a.className = 'item-card';
  a.href = itemHref(item.fullId);

  const loreLines = item.lore || [];
  const lore = loreLines.length
    ? `<div class="item-lore">${loreLines.map((l) => `<div class="line">${mcText(l)}</div>`).join('')}</div>`
    : '';
  // Badge counts the dominant variant group so a wood-family card reads "12 woods", not "12 states".
  let badge = '';
  if (item.variants?.length) {
    const counts = new Map();
    for (const v of item.variants) counts.set(v.group, (counts.get(v.group) || 0) + 1);
    const dominant = [...counts.entries()].sort((a, b) => b[1] - a[1])[0][0];
    const label = { woods: 'woods', power: 'power levels', facing: 'facings' }[dominant] || 'states';
    badge = `<span class="state-badge" title="${item.variants.length} states/textures">${item.variants.length} ${label}</span>`;
  }
  // Placed 3D preview (static thumbnail) for blocks that have a captured placed form.
  const placed = item.placedVariants?.length
    ? `<div class="card-3d"><div class="placed-thumb" data-thumb="${esc(item.fullId)}"></div></div>`
    : '';

  a.innerHTML = `
    <div class="item-header">
      <div class="item-icon ${item.glint ? 'glint' : ''}">${iconHtml(item)}</div>
      <div class="item-title">
        <div class="item-name">${mcText(item.name)}</div>
        <div class="item-id">${esc(item.fullId)} ${badge}</div>
      </div>
    </div>
    ${placed}
    ${lore}
    ${item.recipes?.length || !item.producedBy?.length ? recipesHtml(item, itemsById) : ''}
    ${item.producedBy?.length ? producedByHtml(item.producedBy, itemsById) : ''}
  `;
  return a;
}

function currentQuery() {
  return document.getElementById('search-input').value.trim().toLowerCase();
}

function matches(it, q) {
  return !q || stripColors(it.name).toLowerCase().includes(q) || it.fullId.toLowerCase().includes(q);
}

function inSubset(it) { return !subsetIds || subsetIds.has(it.fullId); }

function render() {
  const q = currentQuery();

  thumbObserver.disconnect();   // drop observations on cards about to be replaced (no stale refs)

  // One titled section per non-slab namespace (rotation first), honoring search + the pill filter.
  const container = document.getElementById('items-container');
  container.innerHTML = '';
  let total = 0;
  for (const ns of orderedNamespaces()) {
    if (activeNamespace && activeNamespace !== ns) continue;
    const its = CATALOG.items.filter((it) => it.namespace === ns && matches(it, q) && inSubset(it));
    if (!its.length) continue;
    total += its.length;

    // All items in a namespace section share a providing plugin (banners → BetterBanners); link it.
    const plugin = its.find((it) => it.plugin)?.plugin;
    const modrinth = plugin
      ? ` <a class="section-modrinth" href="https://modrinth.com/plugin/${esc(plugin.slug)}" target="_blank" rel="noopener">Download ${esc(plugin.name)} on Modrinth ↗</a>`
      : '';
    const section = document.createElement('section');
    section.innerHTML = `<h2 class="section-title">${esc(nsLabel(ns))} <span class="counter inline">${its.length} items</span>${modrinth}</h2>`;
    const grid = document.createElement('div');
    grid.className = 'item-grid';
    its.forEach((it) => grid.appendChild(card(it)));
    section.appendChild(grid);
    container.appendChild(section);
    hydrateHeads(grid);
  }
  document.getElementById('counter').textContent = `${total} items`;

  // Vertical slabs: their own section (only when not filtered to another namespace).
  const slabs = (!activeNamespace) ? CATALOG.items.filter((it) => it.namespace === SLAB_NAMESPACE && matches(it, q) && inSubset(it)) : [];
  const slabGrid = document.getElementById('slab-grid');
  slabGrid.innerHTML = '';
  slabs.forEach((it) => slabGrid.appendChild(card(it)));
  document.getElementById('slab-section').style.display = slabs.length ? '' : 'none';
  document.getElementById('slab-counter').textContent = `${slabs.length} slabs`;
  hydrateHeads(slabGrid);

  document.querySelectorAll('.placed-thumb').forEach((el) => thumbObserver.observe(el));
}

function renderPills() {
  const pills = document.getElementById('namespace-pills');
  const make = (label, ns) => {
    const el = document.createElement('button');
    el.className = 'pill' + (activeNamespace === ns ? ' active' : '');
    el.textContent = label;
    el.onclick = () => { activeNamespace = activeNamespace === ns ? null : ns; syncUrl(); renderPills(); render(); };
    return el;
  };
  pills.innerHTML = '';
  pills.appendChild(make('All', null));
  // Slabs have their own section, so they're not a main-grid filter.
  CATALOG.namespaces.filter((ns) => ns !== SLAB_NAMESPACE).forEach((ns) => pills.appendChild(make(ns, ns)));
}

// Seed filter state from the URL (?q=…, ?ns=…, ?items=a,b) so links open a specific subset.
function readUrl() {
  const p = new URLSearchParams(location.search);
  const q = p.get('q');
  if (q) document.getElementById('search-input').value = q;
  const ns = p.get('ns');
  if (ns && CATALOG.namespaces.includes(ns) && ns !== SLAB_NAMESPACE) activeNamespace = ns;
  const items = p.get('items');
  if (items) {
    const ids = items.split(',').map((s) => s.trim()).filter(Boolean);
    if (ids.length) subsetIds = new Set(ids);
  }
}

// Mirror the current filter state back into the URL so any view is copy-pasteable.
function syncUrl() {
  const p = new URLSearchParams();
  const q = document.getElementById('search-input').value.trim();
  if (q) p.set('q', q);
  if (activeNamespace) p.set('ns', activeNamespace);
  if (subsetIds) p.set('items', [...subsetIds].join(','));
  const qs = p.toString();
  history.replaceState(null, '', qs ? `?${qs}` : location.pathname);
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
  readUrl();
  document.getElementById('search-input').addEventListener('input', () => { syncUrl(); render(); });
  renderPills();
  render();
}

init();
