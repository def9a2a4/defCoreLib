import {
  esc, mcText, stripColors, iconHtml, recipesHtml, hydrateHeads,
} from './render.js';

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

  const lore = item.lore.length
    ? `<div class="item-lore">${item.lore.map((l) => `<div class="line">${mcText(l)}</div>`).join('')}</div>`
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
    <div class="detail-section">
      <h2 class="section-title">Recipes</h2>
      ${recipesHtml(item, itemsById)}
    </div>
    ${variantsHtml(item)}
  `;
  hydrateHeads(detail);
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
