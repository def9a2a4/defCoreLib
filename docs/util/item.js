import {
  esc, mcText, stripColors, iconHtml, recipesHtml, hydrateHeads, materialPath,
} from './render.js';
import { render3DHead } from './head3d.js';
import { renderPlaced } from './placed3d.js';

const GROUP_TITLES = { states: 'States', power: 'Redstone power', facing: 'By facing' };
const GROUP_ORDER = ['states', 'power', 'facing'];

function showError(msg) {
  const err = document.getElementById('error');
  err.style.display = 'block';
  err.textContent = msg;
}

// Group variants by their `group` field, preserving generator order within a group.
function variantsHtml(item) {
  if (!item.variants?.length) return '';
  const byGroup = new Map();
  for (const v of item.variants) {
    if (!byGroup.has(v.group)) byGroup.set(v.group, []);
    byGroup.get(v.group).push(v);
  }
  const groups = [...byGroup.keys()].sort((a, b) => GROUP_ORDER.indexOf(a) - GROUP_ORDER.indexOf(b));

  const sections = groups.map((g) => {
    const cells = byGroup.get(g).map((v) => `
      <div class="variant">
        <div class="variant-icon">
          <span class="slot-label head-pending" data-head="${esc(v.textureUrl)}" data-title="${esc(v.label)}"></span>
        </div>
        <div class="variant-label">${esc(v.label)}</div>
      </div>`).join('');
    return `<div class="variant-group">
      <div class="variant-group-title">${esc(GROUP_TITLES[g] || g)}</div>
      <div class="variant-row">${cells}</div>
    </div>`;
  }).join('');

  return `<div class="detail-section">
    <h2 class="section-title">States &amp; Textures</h2>
    ${sections}
    ${transitionsHtml(item)}
  </div>`;
}

function transitionsHtml(item) {
  if (!item.transitions?.length) return '';
  const rows = item.transitions.map((t) => {
    const trig = t.trigger ? ` <span class="trans-trigger">(${esc(t.trigger)})</span> ` : ' → ';
    return `<div class="transition">
      <span class="trans-state">${esc(t.from ?? '?')}</span>${trig}<span class="trans-arrow">→</span>
      <span class="trans-state">${esc(t.to ?? '?')}</span>
    </div>`;
  }).join('');
  return `<div class="transitions"><div class="variant-group-title">Transitions</div>${rows}</div>`;
}

function renderItem(item, itemsById) {
  const detail = document.getElementById('detail');
  document.title = `DefCoreLib — ${stripColors(item.name)}`;

  const loreLines = item.lore || [];
  const lore = loreLines.length
    ? `<div class="item-lore">${loreLines.map((l) => `<div class="line">${mcText(l)}</div>`).join('')}</div>`
    : '';

  detail.innerHTML = `
    <div class="detail-header">
      <div class="detail-icon ${item.glint ? 'glint' : ''}">${iconHtml(item)}</div>
      <div>
        <h1 class="detail-name">${mcText(item.name)}</h1>
        <div class="item-id">${esc(item.fullId)}</div>
      </div>
    </div>
    ${lore}
    <div class="viewers" id="viewers"></div>
    <div class="detail-section">
      <h2 class="section-title">Recipes</h2>
      ${recipesHtml(item, itemsById)}
    </div>
    ${variantsHtml(item)}
  `;
  hydrateHeads(detail);
  mountViewers(item);
}

// Registry of live viewer teardowns; released on navigation so WebGL contexts don't leak.
const viewerTeardowns = new Set();
window.addEventListener('pagehide', () => {
  for (const t of viewerTeardowns) { try { t(); } catch { /* ignore */ } }
  viewerTeardowns.clear();
});

// Add a labelled 3D viewer panel and drive `renderFn(canvasContainer)` into it. `renderFn` may
// resolve to a teardown function (render3DHead does) — track it so the context is released.
function addViewer(parent, label, renderFn) {
  const panel = document.createElement('div');
  panel.className = 'viewer';
  panel.innerHTML = `<div class="viewer-label">${esc(label)}</div><div class="viewer-canvas"></div>`;
  parent.appendChild(panel);
  const canvas = panel.querySelector('.viewer-canvas');
  Promise.resolve(renderFn(canvas))
    .then((t) => { if (typeof t === 'function') viewerTeardowns.add(t); })
    .catch((e) => {
      console.warn(e);
      canvas.innerHTML = '<div class="viewer-fail">3D unavailable</div>';
    });
}

function inHandViewer(item) {
  const ih = item.inHand || {};
  if (ih.kind === 'head' && ih.textureUrl) return (c) => render3DHead(ih.textureUrl, c);
  // Vanilla item_material items: show the reliable 2D inventory icon (their 3D block
  // form appears in the "Placed" viewer).
  if (ih.kind === 'item' && ih.block) {
    return (c) => { c.innerHTML = `<img class="flat-item" src="${materialPath(ih.block)}" alt="">`; };
  }
  return null;
}

// "In hand" viewer + a "Placed" viewer with a variant selector (Floor / Wall N·E·S·W).
function mountViewers(item) {
  const host = document.getElementById('viewers');
  const inHand = inHandViewer(item);
  if (inHand) addViewer(host, 'In hand', inHand);

  const variants = item.placedVariants || [];
  if (variants.length) mountPlacedViewer(host, item, variants);
}

function mountPlacedViewer(host, item, variants) {
  const panel = document.createElement('div');
  panel.className = 'viewer';
  const selector = variants.length > 1
    ? `<select class="variant-select">${variants
        .map((v, i) => `<option value="${i}">${esc(v.label || v.id)}</option>`).join('')}</select>`
    : '';
  panel.innerHTML = `<div class="viewer-label">Placed ${selector}</div><div class="viewer-canvas"></div>`;
  host.appendChild(panel);

  const canvas = panel.querySelector('.viewer-canvas');
  let teardown = null;
  const show = (idx) => {
    if (teardown) { viewerTeardowns.delete(teardown); teardown(); teardown = null; }
    canvas.innerHTML = '';
    Promise.resolve(renderPlaced(item, canvas, idx))
      .then((t) => { teardown = t; if (t) viewerTeardowns.add(t); })
      .catch((e) => { console.warn(e); canvas.innerHTML = '<div class="viewer-fail">3D unavailable</div>'; });
  };
  const select = panel.querySelector('.variant-select');
  if (select) select.addEventListener('change', () => show(+select.value));
  // Default to the floor orientation (the canonical, known-good view) when present, else variant 0.
  const floorIdx = Math.max(0, variants.findIndex((v) => String(v.id || '').startsWith('floor')));
  if (select) select.value = String(floorIdx);
  show(floorIdx);
}

async function init() {
  const id = new URLSearchParams(window.location.search).get('id');
  if (!id) { showError('No item specified.'); return; }

  let catalog;
  try {
    const res = await fetch('./data/items.json');
    if (!res.ok) throw new Error(`items.json: ${res.status}`);
    catalog = await res.json();
  } catch (e) {
    showError('Failed to load catalog: ' + e.message);
    return;
  }

  const itemsById = new Map(catalog.items.map((it) => [it.fullId, it]));
  const item = itemsById.get(id);
  if (!item) { showError(`Unknown item: ${id}`); return; }
  renderItem(item, itemsById);
}

init();
