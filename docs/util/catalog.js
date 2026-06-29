import { headDataUrl } from './head-icon.js';

// Vanilla item/block textures for recipe ingredients & material-icon items.
// Best-effort: try item texture, then block texture, then fall back to a text label.
const MC_ASSETS = 'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.4/assets/minecraft/textures';

const MC_COLORS = {
  '0': 'mc-0', '1': 'mc-1', '2': 'mc-2', '3': 'mc-3', '4': 'mc-4', '5': 'mc-5',
  '6': 'mc-6', '7': 'mc-7', '8': 'mc-8', '9': 'mc-9', 'a': 'mc-a', 'b': 'mc-b',
  'c': 'mc-c', 'd': 'mc-d', 'e': 'mc-e', 'f': 'mc-f', 'l': 'mc-l', 'o': 'mc-o',
  'n': 'mc-n', 'm': 'mc-m'
};

let CATALOG = { items: [], grindRecipes: [], namespaces: [] };
let itemsById = new Map();
let activeNamespace = null;

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));

function stripColors(text) {
  return (text || '').replace(/&[0-9a-fklmnor]/gi, '');
}

// Convert &-color-coded text into colored HTML spans (escaped).
function mcText(text) {
  if (!text) return '';
  let out = '';
  let open = 0;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '&' && i + 1 < text.length && MC_COLORS[text[i + 1].toLowerCase()]) {
      const code = text[i + 1].toLowerCase();
      if (code === 'r') { while (open-- > 0) out += '</span>'; open = 0; }
      else { out += `<span class="${MC_COLORS[code]}">`; open++; }
      i++;
    } else {
      out += esc(text[i]);
    }
  }
  while (open-- > 0) out += '</span>';
  return out;
}

// Title-case a MATERIAL_NAME -> "Material Name"
function prettyMaterial(name) {
  return (name || '')
    .toLowerCase()
    .split('_')
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

// An <img> that tries item/<name>.png then block/<name>.png, then reveals a text fallback.
function materialImg(material, label) {
  const lower = material.toLowerCase();
  const itemUrl = `${MC_ASSETS}/item/${lower}.png`;
  const blockUrl = `${MC_ASSETS}/block/${lower}.png`;
  return `<img src="${itemUrl}" alt="${esc(label)}" title="${esc(label)}"
    onerror="this.onerror=null; if(this.dataset.f){this.replaceWith(Object.assign(document.createElement('span'),{className:'slot-label',textContent:this.alt}));}else{this.dataset.f=1;this.src='${blockUrl}';}">`;
}

// Render one ingredient/slot's inner HTML.
function slotContent(ing) {
  if (!ing) return '';
  if (ing.kind === 'block') {
    const ref = itemsById.get(ing.value);
    const label = ref ? stripColors(ref.name) : ing.value;
    if (ref && ref.icon?.type === 'head') {
      return `<span class="slot-label" data-head="${esc(ref.icon.textureUrl || '')}" title="${esc(label)}">${esc(label)}</span>`;
    }
    if (ref && ref.icon?.type === 'material') {
      return materialImg(ref.icon.material, label);
    }
    return `<span class="slot-label" title="${esc(label)}">${esc(label)}</span>`;
  }
  if (ing.kind === 'tag') {
    const label = '#' + ing.value;
    return `<span class="slot-label" title="${esc(label)}">${esc(label)}</span>`;
  }
  // material
  const label = prettyMaterial(ing.value);
  return materialImg(ing.value, label);
}

function slotEl(ing, extraClass = '') {
  const cls = ['slot', extraClass];
  if (!ing) cls.push('empty');
  else if (ing.kind === 'tag') cls.push('tag');
  return `<div class="${cls.join(' ')}">${slotContent(ing)}</div>`;
}

function resultSlot(item, amount) {
  const amt = amount > 1 ? `<span class="amount">${amount}</span>` : '';
  let inner;
  if (item.icon?.type === 'head') {
    inner = `<span class="slot-label" data-head="${esc(item.icon.textureUrl || '')}">${esc(stripColors(item.name))}</span>`;
  } else if (item.icon?.type === 'material') {
    inner = materialImg(item.icon.material, stripColors(item.name));
  } else {
    inner = `<span class="slot-label">${esc(stripColors(item.name))}</span>`;
  }
  return `<div class="slot result">${inner}${amt}</div>`;
}

function renderRecipe(item, recipe) {
  if (recipe.type === 'shaped') {
    const cols = Math.max(1, ...recipe.pattern.map((r) => r.length));
    let cells = '';
    for (const row of recipe.pattern) {
      for (let c = 0; c < cols; c++) {
        const ch = row[c] || ' ';
        const ing = ch === ' ' ? null : recipe.key[ch];
        cells += slotEl(ing);
      }
    }
    return `<div class="recipe">
      <div class="recipe-type">Crafting</div>
      <div class="recipe-body">
        <div class="craft-grid cols-${cols}">${cells}</div>
        <span class="recipe-arrow">→</span>
        ${resultSlot(item, recipe.amount)}
      </div>
    </div>`;
  }
  if (recipe.type === 'shapeless') {
    const ings = recipe.ingredients.map((i) => slotEl(i)).join('');
    return `<div class="recipe">
      <div class="recipe-type">Shapeless</div>
      <div class="recipe-body">
        <div class="shapeless-list">${ings}</div>
        <span class="recipe-arrow">→</span>
        ${resultSlot(item, recipe.amount)}
      </div>
    </div>`;
  }
  if (recipe.type === 'stonecutter') {
    return `<div class="recipe">
      <div class="recipe-type">Stonecutter</div>
      <div class="recipe-body">
        ${slotEl(recipe.input)}
        <span class="recipe-arrow">→</span>
        ${resultSlot(item, recipe.amount)}
      </div>
    </div>`;
  }
  return '';
}

function renderCard(item) {
  const loreHtml = item.lore.length
    ? `<div class="item-lore">${item.lore.map((l) => `<div class="line">${mcText(l)}</div>`).join('')}</div>`
    : '';
  const recipesHtml = item.recipes.length
    ? `<div class="recipes">${item.recipes.map((r) => renderRecipe(item, r)).join('')}</div>`
    : `<div class="no-recipe">No recipe — obtained via commands.</div>`;

  let iconInner = '<span class="placeholder">?</span>';
  if (item.icon?.type === 'material') {
    iconInner = materialImg(item.icon.material, stripColors(item.name));
  } else if (item.icon?.type === 'head' && item.icon.textureUrl) {
    iconInner = `<span class="slot-label" data-head="${esc(item.icon.textureUrl)}"></span>`;
  }

  const card = document.createElement('div');
  card.className = 'item-card';
  card.innerHTML = `
    <div class="item-header">
      <div class="item-icon ${item.glint ? 'glint' : ''}">${iconInner}</div>
      <div class="item-title">
        <div class="item-name">${mcText(item.name)}</div>
        <div class="item-id">${esc(item.fullId)}</div>
      </div>
    </div>
    ${loreHtml}
    ${recipesHtml}
  `;
  return card;
}

// Replace any [data-head] placeholder spans with the rendered isometric head image.
async function hydrateHeads(root) {
  const els = root.querySelectorAll('[data-head]');
  await Promise.all([...els].map(async (el) => {
    const url = el.getAttribute('data-head');
    if (!url) return;
    const dataUrl = await headDataUrl(url);
    if (dataUrl) {
      const img = new Image();
      img.src = dataUrl;
      img.alt = el.textContent || '';
      img.title = el.title || el.textContent || '';
      el.replaceWith(img);
    }
  }));
}

function currentFilter() {
  return document.getElementById('search-input').value.trim().toLowerCase();
}

function render() {
  const container = document.getElementById('items-container');
  const q = currentFilter();
  const filtered = CATALOG.items.filter((it) => {
    if (activeNamespace && it.namespace !== activeNamespace) return false;
    if (!q) return true;
    return stripColors(it.name).toLowerCase().includes(q) || it.fullId.toLowerCase().includes(q);
  });

  container.innerHTML = '';
  for (const item of filtered) container.appendChild(renderCard(item));
  document.getElementById('counter').textContent =
    `${filtered.length} / ${CATALOG.items.length} items`;
  hydrateHeads(container);
}

function renderPills() {
  const pills = document.getElementById('namespace-pills');
  const make = (label, ns) => {
    const el = document.createElement('button');
    el.className = 'pill' + (activeNamespace === ns ? ' active' : '');
    el.textContent = label;
    el.onclick = () => {
      activeNamespace = activeNamespace === ns ? null : ns;
      renderPills();
      render();
    };
    return el;
  };
  pills.innerHTML = '';
  pills.appendChild(make('All', null));
  for (const ns of CATALOG.namespaces) pills.appendChild(make(ns, ns));
}

function renderGrind() {
  const grid = document.getElementById('grind-grid');
  grid.innerHTML = '';
  for (const r of CATALOG.grindRecipes) {
    const row = document.createElement('div');
    row.className = 'grind-row';
    row.innerHTML = `
      ${slotEl({ kind: 'material', value: r.input })}
      <span class="recipe-arrow">→</span>
      <div class="slot result">${materialImg(r.output, prettyMaterial(r.output))}${r.amount > 1 ? `<span class="amount">${r.amount}</span>` : ''}</div>`;
    grid.appendChild(row);
  }
  document.getElementById('grind-section').style.display =
    CATALOG.grindRecipes.length ? '' : 'none';
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
