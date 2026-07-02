import {
  esc, mcText, stripColors, iconHtml, itemHref, recipesHtml, machineRecipesHtml, producedByHtml, hydrateHeads,
} from './render.js';
import { mountInHand, mountPlaced, hasInHand, toRenderBlock } from './viewers.js';
import { thumbnailDataURL } from './placed3d.js';

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

// Backlinks to the showcase machines that use this block (reverse of showcases.json; built by
// generate_catalog into item.usedInShowcases as [{id, name}]). Each card carries a static 3D
// screenshot of the machine, hydrated later by hydrateShowcaseThumbs once showcases.json is loaded.
function usedInShowcasesHtml(item) {
  const used = item.usedInShowcases || [];
  if (!used.length) return '';
  const cards = used.map((s) => `
    <a class="showcase-backlink" href="./showcase.html?id=${encodeURIComponent(s.id)}">
      <div class="showcase-thumb" data-showcase-thumb="${esc(s.id)}"></div>
      <div class="showcase-backlink-name">${esc(s.name)}</div>
    </a>`).join('');
  return `<div class="detail-section used-in">
    <h2 class="section-title">Used in showcases</h2>
    <div class="used-in-showcases">${cards}</div>
  </div>`;
}

// Fill each [data-showcase-thumb] with an offscreen-rendered PNG of the whole machine. zoom:1 (not the
// single-block 1.7 default) keeps the multi-block machine framed, matching the live showcase viewer.
function hydrateShowcaseThumbs(root, showcasesById) {
  root.querySelectorAll('[data-showcase-thumb]').forEach((el) => {
    const sc = showcasesById.get(el.dataset.showcaseThumb);
    if (!sc) return;
    thumbnailDataURL((sc.blocks || []).map(toRenderBlock), { size: 300, cacheKey: 'showcase:' + sc.id, zoom: 1 })
      .then((url) => {
        if (!url || !el.isConnected) return;
        const img = new Image();
        img.src = url;
        img.alt = '';
        img.className = 'placed-thumb-img';
        el.appendChild(img);
      });
  });
}

// Render one note line. Supports a `[label](namespace:id)` link syntax that turns into an
// internal item-page link; everything else goes through mcText (which escapes HTML and
// applies &-colour codes). The required colon in the id keeps ordinary prose parens like
// "(see below)" from being mistaken for links, and an id not present in itemsById falls back
// to plain text so a broken link never renders a dead anchor.
const NOTE_LINK_RE = /\[([^\]]+)\]\(([a-z0-9_]+:[a-z0-9_./-]+)\)/gi;
function noteHtml(line, itemsById) {
  let out = '';
  let last = 0;
  for (const m of line.matchAll(NOTE_LINK_RE)) {
    out += mcText(line.slice(last, m.index));
    const [, label, id] = m;
    out += itemsById.has(id)
      ? `<a class="note-link" href="${esc(itemHref(id))}">${mcText(label)}</a>`
      : mcText(m[0]);
    last = m.index + m[0].length;
  }
  out += mcText(line.slice(last));
  return out;
}

function renderItem(item, itemsById, showcasesById) {
  const detail = document.getElementById('detail');
  document.title = `DefCoreLib — ${stripColors(item.name)}`;

  const loreLines = item.lore || [];
  const lore = loreLines.length
    ? `<div class="item-lore">${loreLines.map((l) => `<div class="line">${mcText(l)}</div>`).join('')}</div>`
    : '';

  const noteLines = item.notes || [];
  const notes = noteLines.length
    ? `<div class="detail-section"><h2 class="section-title">Notes</h2>${
        noteLines.map((l) => `<div class="note-line">${noteHtml(l, itemsById)}</div>`).join('')}</div>`
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
    ${notes}
    <div class="viewers" id="viewers"></div>
    ${item.recipes?.length || !item.producedBy?.length
      ? `<div class="detail-section"><h2 class="section-title">Recipes</h2>${recipesHtml(item, itemsById)}</div>`
      : ''}
    ${item.producedBy?.length
      ? `<div class="detail-section"><h2 class="section-title">Obtained from</h2>${producedByHtml(item.producedBy, itemsById)}</div>`
      : ''}
    ${item.machineRecipes?.length
      ? `<div class="detail-section"><h2 class="section-title">${esc(item.machineType || 'Processing')} Recipes</h2>${machineRecipesHtml(item.machineRecipes, itemsById)}</div>`
      : ''}
    ${variantsHtml(item)}
    ${usedInShowcasesHtml(item)}
  `;
  hydrateHeads(detail);
  mountViewers(item);
  if (showcasesById) hydrateShowcaseThumbs(detail, showcasesById);
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

// Placed variants split into a placement axis (Floor / Wall N·E·S·W) and a spin axis (Stopped/CW/CCW),
// parsed from the variant id (e.g. 'wall_west_cw' -> {placement:'wall_west', spin:'cw'}).
const SPINS = ['stopped', 'cw', 'ccw'];                          // also the display order
const PLACES = ['floor', 'wall_north', 'wall_east', 'wall_south', 'wall_west'];

function splitVariantId(id) {
  for (const s of SPINS) {
    if (id === s) return { placement: '', spin: s };
    if (id.endsWith('_' + s)) return { placement: id.slice(0, -(s.length + 1)), spin: s };
  }
  return { placement: id, spin: '' };                           // no spin suffix (placement-only)
}

const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);
const placeLabel = (p) => (p === 'floor' ? 'Floor' : p.startsWith('wall_') ? `Wall ${cap(p.slice(5))}` : (p || 'Default'));
const spinLabel = (s) => ({ stopped: 'Stopped', cw: 'CW', ccw: 'CCW' }[s] || s);

const order = (arr, ref) => arr.slice().sort((a, b) => {
  const ia = ref.indexOf(a), ib = ref.indexOf(b);
  return (ia < 0 ? ref.length : ia) - (ib < 0 ? ref.length : ib);
});

function buildPlacedModel(variants) {
  const places = new Set(), spins = new Set();
  const index = new Map();
  variants.forEach((v, i) => {
    const { placement, spin } = splitVariantId(String(v.id || ''));
    places.add(placement); spins.add(spin);
    index.set(`${placement}|${spin}`, i);
  });
  const placements = order([...places], PLACES);
  const sp = order([...spins], SPINS);
  return { placements, spins: sp, index, isRotation: sp.includes('cw'), hasSpin: sp.some((s) => s !== '') };
}

// Resolve a (placement, spin) pair to a variant index, falling back to the same placement with any
// available spin, then to variant 0.
function pickIndex(m, placement, spin) {
  if (m.index.has(`${placement}|${spin}`)) return m.index.get(`${placement}|${spin}`);
  for (const s of m.spins) if (m.index.has(`${placement}|${s}`)) return m.index.get(`${placement}|${s}`);
  return 0;
}

// "In hand" viewer + one or two "Placed" viewers (floor + wall) with placement/spin dropdowns.
function mountViewers(item) {
  const host = document.getElementById('viewers');
  if (hasInHand(item)) addViewer(host, 'In hand', (c) => mountInHand(item, c));

  const variants = item.placedVariants || [];
  if (!variants.length) return;

  const m = buildPlacedModel(variants);
  const defSpin = m.isRotation ? 'cw' : m.spins[0];             // rotation components spin by default
  const walls = m.placements.filter((p) => p.startsWith('wall'));
  if (m.placements.includes('floor') && walls.length) {
    const wall = walls.includes('wall_west') ? 'wall_west' : walls[0];
    mountPlacedBox(host, item, m, 'floor', defSpin);
    mountPlacedBox(host, item, m, wall, defSpin);
  } else {
    mountPlacedBox(host, item, m, m.placements[0], defSpin);
  }
}

function mountPlacedBox(host, item, m, place0, spin0) {
  const sel = (cls, opts, val) =>
    `<select class="variant-select ${cls}">${opts
      .map(([v, lab]) => `<option value="${esc(v)}"${v === val ? ' selected' : ''}>${esc(lab)}</option>`)
      .join('')}</select>`;
  const placeSel = m.placements.length > 1
    ? sel('place-select', m.placements.map((p) => [p, placeLabel(p)]), place0) : '';
  const spinSel = m.hasSpin
    ? sel('spin-select', m.spins.map((s) => [s, spinLabel(s)]), spin0) : '';

  const panel = document.createElement('div');
  panel.className = 'viewer';
  panel.innerHTML = `<div class="viewer-label">Placed ${placeSel}${spinSel}</div><div class="viewer-canvas"></div>`;
  host.appendChild(panel);

  const canvas = panel.querySelector('.viewer-canvas');
  const placeEl = panel.querySelector('.place-select');
  const spinEl = panel.querySelector('.spin-select');
  let teardown = null;
  const show = () => {
    const placement = placeEl ? placeEl.value : place0;
    const spin = spinEl ? spinEl.value : spin0;
    if (teardown) { viewerTeardowns.delete(teardown); teardown(); teardown = null; }
    canvas.innerHTML = '';
    Promise.resolve(mountPlaced(item, canvas, pickIndex(m, placement, spin)))
      .then((t) => { teardown = t; if (t) viewerTeardowns.add(t); })
      .catch((e) => { console.warn(e); canvas.innerHTML = '<div class="viewer-fail">3D unavailable</div>'; });
  };
  if (placeEl) placeEl.addEventListener('change', show);
  if (spinEl) spinEl.addEventListener('change', show);
  show();
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

  // Only this item's pages that actually appear in a showcase need the (~900 KB) showcase block data,
  // for the bottom-of-page machine screenshots. A failed/missing load just drops the thumbnails.
  let showcasesById = null;
  if (item.usedInShowcases?.length) {
    try {
      const sc = await fetch('./data/showcases.json').then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); });
      showcasesById = new Map((sc.showcases || []).map((s) => [s.id, s]));
    } catch (e) { console.warn('showcases.json unavailable:', e); }
  }

  renderItem(item, itemsById, showcasesById);
}

init();
