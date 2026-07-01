// Shared rendering helpers used by both the catalog grid (catalog.js) and the
// item detail page (item.js): Minecraft color codes, item/material icons, recipe
// grids, and head-icon hydration. All image paths point at the locally-vendored
// docs/assets/ folder (populated by scripts/generate_catalog.py).

import { headDataUrl } from './head-icon.js';
import './tooltip.js';   // side effect: installs the shared [data-tip] hover tooltip

// Single owner for a slot's hover label: `data-tip` drives the themed tooltip,
// `aria-label` preserves the accessible name that `title` used to provide. Stamp
// this on the slot container only — icons inside it stay label-free so there's
// exactly one tooltip per slot.
export const tipAttrs = (label) => `data-tip="${esc(label)}" aria-label="${esc(label)}"`;

const MC_COLORS = {
  '0': 'mc-0', '1': 'mc-1', '2': 'mc-2', '3': 'mc-3', '4': 'mc-4', '5': 'mc-5',
  '6': 'mc-6', '7': 'mc-7', '8': 'mc-8', '9': 'mc-9', 'a': 'mc-a', 'b': 'mc-b',
  'c': 'mc-c', 'd': 'mc-d', 'e': 'mc-e', 'f': 'mc-f', 'l': 'mc-l', 'o': 'mc-o',
  'n': 'mc-n', 'm': 'mc-m'
};

export const esc = (s) => String(s).replace(/[&<>"']/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));

export function stripColors(text) {
  return (text || '').replace(/&[0-9a-fklmnor]/gi, '');
}

// Convert &-color-coded text into colored HTML spans (escaped).
export function mcText(text) {
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

export function prettyMaterial(name) {
  return (name || '')
    .toLowerCase().split('_').filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

export const itemHref = (fullId) => `./item.html?id=${encodeURIComponent(fullId)}`;
export const skinPath = (url) => `./assets/skins/${url.split('/').pop()}.png`;
export const materialPath = (material) => `./assets/items/${material.toLowerCase()}.png`;

// <img> for a vanilla material, falling back to a text label if the asset is missing.
// No `title` — the enclosing slot owns the tooltip (see tipAttrs); `alt` stays for a11y.
export function materialImg(material, label) {
  return `<img class="mat-img" src="${materialPath(material)}" alt="${esc(label)}"
    onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'slot-label',textContent:this.alt}));">`;
}

// Inner HTML for an item's icon: a vanilla image, or a head placeholder hydrated later.
export function iconHtml(item) {
  const label = stripColors(item.name);
  if (item.icon?.type === 'material') return materialImg(item.icon.material, label);
  if (item.icon?.type === 'head' && item.icon.textureUrl) {
    return `<span class="slot-label head-pending" data-head="${esc(item.icon.textureUrl)}" data-title="${esc(label)}"></span>`;
  }
  return `<span class="placeholder">?</span>`;
}

// Recipe ingredient tags (#banners / #wool / …) render as a representative image + descriptive
// label instead of bare "#tag" text. materialImg falls back to the label text if the image is missing.
const TAG_PLACEHOLDER = {
  banners: { img: 'white_banner', label: 'Banner (any colour)' },
  banner: { img: 'white_banner', label: 'Banner (any colour)' },
  wool: { img: 'white_wool', label: 'Wool (any colour)' },
  planks: { img: 'oak_planks', label: 'Planks (any wood)' },
  wooden_slabs: { img: 'oak_slab', label: 'Slab (any wood)' },
};

// One ingredient slot. block refs link to that item's page and show its icon.
// `badges` is optional trailing HTML (amount/chance) placed inside the slot.
function slotEl(ing, itemsById, extraClass = '', badges = '') {
  const cls = ['slot', extraClass];
  if (!ing) { cls.push('empty'); return `<div class="${cls.join(' ')}"></div>`; }

  if (ing.kind === 'block') {
    const ref = itemsById.get(ing.value);
    const label = ref ? stripColors(ref.name) : ing.value;
    const inner = ref ? iconHtml(ref) : `<span class="slot-label">${esc(label)}</span>`;
    return `<a class="${cls.join(' ')}" href="${itemHref(ing.value)}" ${tipAttrs(label)}>${inner}${badges}</a>`;
  }
  if (ing.kind === 'tag') {
    const ph = TAG_PLACEHOLDER[ing.value];
    const label = ph ? ph.label : '#' + ing.value;
    cls.push('tag');
    const inner = ph ? materialImg(ph.img, label) : `<span class="slot-label">${esc('#' + ing.value)}</span>`;
    return `<div class="${cls.join(' ')}" ${tipAttrs(label)}>${inner}${badges}</div>`;
  }
  const label = prettyMaterial(ing.value);
  return `<div class="${cls.join(' ')}" ${tipAttrs(label)}>${materialImg(ing.value, label)}${badges}</div>`;
}

function resultSlot(item, amount) {
  const label = stripColors(item.name);
  const amt = amount > 1 ? `<span class="amount">${amount}</span>` : '';
  return `<a class="slot result" href="${itemHref(item.fullId)}" ${tipAttrs(label)}>${iconHtml(item)}${amt}</a>`;
}

export function renderRecipe(item, recipe, itemsById) {
  if (recipe.type === 'shaped') {
    const cols = Math.max(1, ...recipe.pattern.map((r) => r.length));
    let cells = '';
    for (const row of recipe.pattern) {
      for (let c = 0; c < cols; c++) {
        const ch = row[c] || ' ';
        cells += slotEl(ch === ' ' ? null : recipe.key[ch], itemsById);
      }
    }
    return `<div class="recipe">
      <div class="recipe-type">Crafting</div>
      <div class="recipe-body">
        <div class="craft-grid cols-${cols}">${cells}</div>
        <span class="recipe-arrow">→</span>
        ${resultSlot(item, recipe.amount)}
      </div></div>`;
  }
  if (recipe.type === 'shapeless') {
    const ings = recipe.ingredients.map((i) => slotEl(i, itemsById)).join('');
    return `<div class="recipe">
      <div class="recipe-type">Shapeless</div>
      <div class="recipe-body">
        <div class="shapeless-list">${ings}</div>
        <span class="recipe-arrow">→</span>
        ${resultSlot(item, recipe.amount)}
      </div></div>`;
  }
  if (recipe.type === 'stonecutter') {
    return `<div class="recipe">
      <div class="recipe-type">Stonecutter</div>
      <div class="recipe-body">
        ${slotEl(recipe.input, itemsById)}
        <span class="recipe-arrow">→</span>
        ${resultSlot(item, recipe.amount)}
      </div></div>`;
  }
  return '';
}

export function recipesHtml(item, itemsById) {
  const recipes = item.recipes || [];   // guard: this runs per catalog card — a missing list would kill the grid
  return recipes.length
    ? `<div class="recipes">${recipes.map((r) => renderRecipe(item, r, itemsById)).join('')}</div>`
    : `<div class="no-recipe">No recipe — obtained via commands.</div>`;
}

// A machine-recipe ref ("namespace:id" custom item, or a vanilla Material name) → ingredient shape.
const refIng = (ref) => (ref.includes(':') ? { kind: 'block', value: ref } : { kind: 'material', value: ref });

// Amount (>1) and chance (<1) badges shown inside a machine-recipe slot.
const slotBadges = (count, chance) =>
  (count > 1 ? `<span class="amount">${count}</span>` : '')
  + (chance != null && chance < 1 ? `<span class="chance">${Math.round(chance * 100)}%</span>` : '');

// Processing-machine recipes (millstone, press, …): input (+ extra inputs like glass
// bottles) → one or more outputs, with input/output amounts and per-output chance.
// Resolves both vanilla materials and custom items via the shared slot renderer.
export function machineRecipesHtml(recipes, itemsById) {
  const list = recipes || [];
  if (!list.length) return '';
  const rows = list.map((r) => {
    const inputs = [slotEl(refIng(r.input), itemsById, '', slotBadges(r.inputAmount, 1))]
      .concat((r.extraInputs || []).map((e) => slotEl(refIng(e.ref), itemsById, '', slotBadges(e.amount, 1))))
      .join('');
    const outputs = (r.outputs || [])
      .map((o) => slotEl(refIng(o.ref), itemsById, 'result', slotBadges(o.amount, o.chance)))
      .join('');
    return `<div class="grind-row">
      <div class="machine-io">${inputs}</div>
      <span class="recipe-arrow">→</span>
      <div class="machine-io">${outputs}</div>
    </div>`;
  }).join('');
  return `<div class="grind-grid">${rows}</div>`;
}

// Reverse of machineRecipesHtml: this item's catalog page showing the machine(s) that produce
// it. Groups by machine, with a heading linking to the machine, then the producing recipe rows.
export function producedByHtml(producedBy, itemsById) {
  const list = producedBy || [];
  if (!list.length) return '';
  const byMachine = new Map();   // machineId → { machineType, recipes: [] }
  for (const p of list) {
    if (!byMachine.has(p.machine)) byMachine.set(p.machine, { machineType: p.machineType, recipes: [] });
    byMachine.get(p.machine).recipes.push(p.recipe);
  }
  return [...byMachine.entries()].map(([machineId, g]) => {
    const m = itemsById.get(machineId);
    const name = m ? stripColors(m.name) : machineId;
    const heading = m
      ? `<a class="produced-by-machine" href="${itemHref(machineId)}">${esc(name)}</a>`
      : `<span class="produced-by-machine">${esc(name)}</span>`;
    return `<div class="produced-by"><div class="produced-by-label">${heading}</div>${machineRecipesHtml(g.recipes, itemsById)}</div>`;
  }).join('');
}

// Replace [data-head] placeholders with rendered isometric head images (from local skins).
export async function hydrateHeads(root) {
  const els = root.querySelectorAll('[data-head]');
  await Promise.all([...els].map(async (el) => {
    const url = el.getAttribute('data-head');
    if (!url) return;
    const dataUrl = await headDataUrl(url);
    const title = el.getAttribute('data-title') || el.textContent || '';
    if (dataUrl) {
      const img = new Image();
      img.src = dataUrl;
      img.alt = title;
      img.dataset.tip = title;
      img.setAttribute('aria-label', title);
      img.className = 'head-img';
      el.replaceWith(img);
    } else if (!el.textContent) {
      // Skin failed to load: fall back to a short text label.
      el.textContent = title;
    }
  }));
}
