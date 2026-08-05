import { getCachedIsometricHead } from './head-renderer.js';

const MC_COLORS = {
  '0': 'mc-0', '1': 'mc-1', '2': 'mc-2', '3': 'mc-3', '4': 'mc-4', '5': 'mc-5',
  '6': 'mc-6', '7': 'mc-7', '8': 'mc-8', '9': 'mc-9', 'a': 'mc-a', 'b': 'mc-b',
  'c': 'mc-c', 'd': 'mc-d', 'e': 'mc-e', 'f': 'mc-f', 'l': 'mc-l', 'o': 'mc-o',
  'n': 'mc-n', 'm': 'mc-m'
};

// MyOctagon Minecraft Block Textures - 3D rendered block images
const OCTAGON_BASE = 'https://raw.githubusercontent.com/MyOctagon/Minecraft-Block-Textures/main';

// Fallback: InventivetalentDev minecraft-assets for items/blocks not in Octagon repo
const MC_ASSETS_BASE = 'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.4/assets/minecraft/textures';

// Minecraft language file for display name lookups (used for wiki invicon fallback)
const MC_LANG_URL = `${MC_ASSETS_BASE.replace('/textures', '')}/lang/en_us.json`;

// Loaded from octagon-textures.json
let octagonTextures = {};

// Sets of known items and blocks from fallback source (loaded at init)
let itemTextures = new Set();
let blockTextures = new Set();

// Minecraft display names from en_us.json (block.minecraft.* and item.minecraft.*)
let langData = {};

// Global heads data for filtering
let headsData = {};

// Tag ordering from config.yml
let tagOrderFirst = [];
let tagOrderLast = [];

// Render ID to prevent race conditions in async renderHeads()
let currentRenderId = 0;

// IntersectionObserver for lazy loading textures
let textureObserver;

function setupTextureObserver() {
  textureObserver = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        const el = entry.target;
        textureObserver.unobserve(el);
        loadTexture(el);
      }
    }
  }, {
    rootMargin: '200px',
    threshold: 0
  });
}

// Active filter state
let activeTags = new Set();  // Multiple flat tags allowed
let activeTagPath = [];      // Hierarchical tag path, e.g., ["alphabet", "oak"]
let activeProperty = null;

// URL state management
function saveStateToURL() {
  const params = new URLSearchParams();
  const searchInput = document.getElementById('search-input');

  if (searchInput?.value) {
    params.set('q', searchInput.value);
  }
  if (activeTagPath.length > 0) {
    params.set('path', activeTagPath.join(','));
  }
  if (activeTags.size > 0) {
    params.set('tags', [...activeTags].join(','));
  }
  if (activeProperty) {
    params.set('prop', activeProperty);
  }

  const newUrl = params.toString()
    ? `${window.location.pathname}?${params.toString()}`
    : window.location.pathname;
  history.replaceState(null, '', newUrl);
}

function loadStateFromURL() {
  const params = new URLSearchParams(window.location.search);
  const searchInput = document.getElementById('search-input');

  // Restore search query
  const query = params.get('q');
  if (query && searchInput) {
    searchInput.value = query;
  }

  // Restore hierarchical tag path
  const path = params.get('path');
  if (path) {
    activeTagPath = path.split(',').filter(s => s);
  }

  // Restore flat tags
  const tags = params.get('tags');
  if (tags) {
    activeTags = new Set(tags.split(',').filter(s => s));
  }

  // Restore property filter
  const prop = params.get('prop');
  if (prop) {
    activeProperty = prop;
  }
}

// Strip Minecraft color codes for search matching
function stripColorCodes(text) {
  if (!text) return '';
  return text.replace(/&[0-9a-fklmnor]/gi, '');
}

// Filter heads based on search query and active filters
function filterHeads(query) {
  const q = query.toLowerCase().trim();

  return Object.entries(headsData).filter(([id, head]) => {
    // Hierarchical tag filter (if activeTagPath is set)
    if (activeTagPath.length > 0) {
      const fullTagPath = activeTagPath.join('/');
      // Head must have a tag that matches or starts with the path
      const hasMatchingTag = head.tags?.some(tag =>
        tag === fullTagPath || tag.startsWith(fullTagPath + '/')
      );
      if (!hasMatchingTag) return false;
    }
    // Flat tag filter (AND - must have ALL selected tags)
    if (activeTags.size > 0) {
      for (const tag of activeTags) {
        if (!head.tags?.includes(tag)) return false;
      }
    }
    // Property filter
    if (activeProperty && !head.properties?.includes(activeProperty)) return false;
    // If no search query, pass through
    if (!q) return true;
    // Match against ID
    if (id.toLowerCase().includes(q)) return true;
    // Match against name (stripped of color codes)
    if (stripColorCodes(head.name || '').toLowerCase().includes(q)) return true;
    // Match against tags
    if (head.tags?.some(tag => tag.toLowerCase().includes(q))) return true;
    // Match against lore (stripped of color codes)
    if (head.lore?.some(line => stripColorCodes(line).toLowerCase().includes(q))) return true;
    return false;
  }).map(([id]) => id);
}

// Debounce helper
function debounce(fn, delay) {
  let timeout;
  return (...args) => {
    clearTimeout(timeout);
    timeout = setTimeout(() => fn(...args), delay);
  };
}

// Load tag ordering config from config.yml
async function loadTagOrderConfig() {
  try {
    const response = await fetch('./data/config.yml');
    if (!response.ok) return;
    const yaml = await response.text();
    const config = jsyaml.load(yaml);
    tagOrderFirst = config['tag-order']?.first?.filter(t => t) || [];
    tagOrderLast = config['tag-order']?.last?.filter(t => t) || [];
  } catch (e) {
    console.warn('Failed to load tag order config:', e);
  }
}

// Sort tags with first/last pins (modifies array in place)
function sortTagsWithPins(tags) {
  // Sort alphabetically first
  tags.sort((a, b) => a.tag.localeCompare(b.tag));

  // Move "first" tags to beginning (reverse order so first in config = first in list)
  for (let i = tagOrderFirst.length - 1; i >= 0; i--) {
    const idx = tags.findIndex(t => t.tag === tagOrderFirst[i]);
    if (idx > -1) {
      const [removed] = tags.splice(idx, 1);
      tags.unshift(removed);
    }
  }

  // Move "last" tags to end
  for (const tag of tagOrderLast) {
    const idx = tags.findIndex(t => t.tag === tag);
    if (idx > -1) {
      const [removed] = tags.splice(idx, 1);
      tags.push(removed);
    }
  }

  return tags;
}

// Build hierarchical tag tree from flat tags
// Returns { tagTree: { parent: { child: count } }, flatTags: [[tag, count]] }
function buildTagTree() {
  const tree = {};      // { "alphabet": { "oak": 5, "birch": 3 }, "storage": null }
  const flatTags = {};  // For non-hierarchical tags

  for (const head of Object.values(headsData)) {
    head.tags?.forEach(tag => {
      const slashIndex = tag.indexOf('/');
      if (slashIndex > 0) {
        // Hierarchical tag: "alphabet/oak"
        const parent = tag.substring(0, slashIndex);
        const child = tag.substring(slashIndex + 1);
        if (!tree[parent]) tree[parent] = {};
        tree[parent][child] = (tree[parent][child] || 0) + 1;
      } else {
        // Flat tag: "storage"
        flatTags[tag] = (flatTags[tag] || 0) + 1;
      }
    });
  }

  return { tree, flatTags };
}

// Get tags to display at current navigation level
function getTagsAtLevel(tree, flatTags) {
  if (activeTagPath.length === 0) {
    // Root level: show top-level hierarchical parents + flat tags
    const result = [];
    // Add hierarchical parent tags with total child counts
    for (const [parent, children] of Object.entries(tree)) {
      const totalCount = Object.values(children).reduce((a, b) => a + b, 0);
      result.push({ tag: parent, count: totalCount, hasChildren: true });
    }
    // Add flat tags
    for (const [tag, count] of Object.entries(flatTags)) {
      result.push({ tag, count, hasChildren: false });
    }
    return sortTagsWithPins(result);
  } else {
    // Drill-down level: show children of current parent (alphabetical only, no pins)
    const parentKey = activeTagPath[0];
    const children = tree[parentKey] || {};
    return Object.entries(children)
      .map(([tag, count]) => ({ tag, count, hasChildren: false }))
      .sort((a, b) => a.tag.localeCompare(b.tag));
  }
}

// Collect unique tags and properties with counts from all heads
function collectFilters() {
  const { tree, flatTags } = buildTagTree();
  const propCounts = {};
  for (const head of Object.values(headsData)) {
    head.properties?.forEach(p => { propCounts[p] = (propCounts[p] || 0) + 1; });
  }
  return {
    tagTree: tree,
    flatTags,
    tagsAtLevel: getTagsAtLevel(tree, flatTags),
    properties: Object.entries(propCounts).sort((a, b) => a[0].localeCompare(b[0]))
  };
}

// Render filter pill buttons with counts
function renderFilterPills() {
  const { tagTree, flatTags, properties } = collectFilters();
  const categoriesSection = document.getElementById('categories-section');
  const tagsSection = document.getElementById('tags-section');
  const propsSection = document.getElementById('properties-section');

  // Categories section: hierarchical parent tags (alphabet, color, etc.)
  const categories = sortTagsWithPins(Object.entries(tagTree).map(([parent, children]) => ({
    tag: parent,
    count: Object.values(children).reduce((a, b) => a + b, 0)
  })));

  if (categories.length === 0) {
    categoriesSection.style.display = 'none';
  } else {
    categoriesSection.style.display = '';

    // Build breadcrumb if navigating into a category
    let breadcrumb = '';
    if (activeTagPath.length > 0) {
      breadcrumb = `
        <span class="tag-breadcrumb">
          <button class="breadcrumb-link" data-tag-level="-1">All</button>
          ${activeTagPath.map((seg, i) =>
            `<span class="breadcrumb-sep">›</span>
             <button class="breadcrumb-link${i === activeTagPath.length - 1 ? ' current' : ''}"
                     data-tag-level="${i}">${seg}</button>`
          ).join('')}
        </span>
      `;
    }

    // Show category pills or subtag pills depending on navigation
    let pills;
    if (activeTagPath.length === 0) {
      // Root level: show category pills
      pills = categories.map(({ tag, count }) =>
        `<button class="filter-pill has-children" data-tag="${tag}" data-has-children="true">${tag} (${count}) ›</button>`
      ).join('');
    } else {
      // Drilled down: show subtag pills for the selected category
      const children = tagTree[activeTagPath[0]] || {};
      pills = Object.entries(children)
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([tag, count]) =>
          `<button class="filter-pill${activeTagPath.length > 1 && activeTagPath[1] === tag ? ' active' : ''}"
                   data-tag="${tag}" data-has-children="false">${tag} (${count})</button>`
        ).join('');
    }

    document.getElementById('categories-list').innerHTML = breadcrumb + pills;
  }

  // Tags section: flat (non-hierarchical) tags only
  const flatTagsList = Object.entries(flatTags).sort((a, b) => a[0].localeCompare(b[0]));

  if (flatTagsList.length === 0) {
    tagsSection.style.display = 'none';
  } else {
    tagsSection.style.display = '';
    document.getElementById('tags-list').innerHTML = flatTagsList.map(([tag, count]) =>
      `<button class="filter-pill${activeTags.has(tag) ? ' active' : ''}"
               data-tag="${tag}" data-flat="true">${tag} (${count})</button>`
    ).join('');
  }

  if (properties.length === 0) {
    propsSection.style.display = 'none';
  } else {
    propsSection.style.display = '';
    document.getElementById('properties-list').innerHTML =
      properties.map(([p, count]) =>
        `<button class="filter-pill${activeProperty === p ? ' active' : ''}" data-property="${p}">${p} (${count})</button>`
      ).join('');
  }
}

// Update visual state of filter pills
function updateActiveFilters() {
  document.querySelectorAll('.filter-pill').forEach(el => {
    el.classList.toggle('active',
      activeTags.has(el.dataset.tag) || el.dataset.property === activeProperty);
  });
}

// Update head counter display
function updateHeadCounter(displayedCount) {
  const total = Object.keys(headsData).length;
  document.getElementById('head-counter').textContent =
    `Showing ${displayedCount} of ${total} heads`;
}

async function loadTextureLists() {
  try {
    const [octagonData, itemList, blockList, langJson] = await Promise.all([
      fetch('./util/octagon-textures.json').then(r => r.json()),
      fetch(`${MC_ASSETS_BASE}/item/_list.json`).then(r => r.json()),
      fetch(`${MC_ASSETS_BASE}/block/_list.json`).then(r => r.json()),
      fetch(MC_LANG_URL).then(r => r.json()).catch(() => ({}))
    ]);
    octagonTextures = octagonData;
    // Lists contain filenames like "glass_bottle.png", store without extension
    itemTextures = new Set(itemList.files.map(f => f.replace('.png', '')));
    blockTextures = new Set(blockList.files.map(f => f.replace('.png', '')));
    langData = langJson;
  } catch (e) {
    console.warn('Failed to load texture lists:', e);
  }
}

// Get the display name for a material from the Minecraft language file
function getDisplayName(material) {
  const name = material.toLowerCase();
  // Try block.minecraft.X first, then item.minecraft.X
  return langData[`block.minecraft.${name}`] || langData[`item.minecraft.${name}`] || null;
}

function getTextureUrl(material) {
  const name = material.toLowerCase();
  const triedUrls = [];

  // First, check if we have a MyOctagon 3D render for this material
  if (octagonTextures[name]) {
    const { folder, file } = octagonTextures[name];
    return `${OCTAGON_BASE}/${encodeURIComponent(folder)}/${encodeURIComponent(file)}`;
  }
  triedUrls.push(`octagon-textures.json[${name}] -> not found`);

  // Fallback to InventivetalentDev assets
  if (itemTextures.has(name)) {
    return `${MC_ASSETS_BASE}/item/${name}.png`;
  }
  triedUrls.push(`${MC_ASSETS_BASE}/item/${name}.png -> not in item list`);

  if (blockTextures.has(name)) {
    return `${MC_ASSETS_BASE}/block/${name}.png`;
  }
  triedUrls.push(`${MC_ASSETS_BASE}/block/${name}.png -> not in block list`);

  // Final fallback: try wiki invicon (3D rendered inventory icon)
  const displayName = getDisplayName(name);
  if (displayName) {
    const filename = 'Invicon_' + displayName.replace(/ /g, '_') + '.png';
    const wikiUrl = `https://minecraft.wiki/w/Special:FilePath/${encodeURIComponent(filename)}`;
    triedUrls.push(`wiki invicon -> ${wikiUrl}`);
    return wikiUrl;
  }
  triedUrls.push(`langData[block.minecraft.${name}] -> ${langData[`block.minecraft.${name}`] || 'not found'}`);
  triedUrls.push(`langData[item.minecraft.${name}] -> ${langData[`item.minecraft.${name}`] || 'not found'}`);
  triedUrls.push(`wiki invicon -> no display name found, cannot construct URL`);

  console.error(`Failed to find texture for material: ${material}\nTried:\n  ${triedUrls.join('\n  ')}`);
  return null;
}

function parseMinecraftColors(text) {
  if (!text) return '';
  let result = '';
  let i = 0;
  let openSpans = 0;
  while (i < text.length) {
    if (text[i] === '&' && i + 1 < text.length) {
      const code = text[i + 1].toLowerCase();
      if (MC_COLORS[code]) {
        result += `<span class="${MC_COLORS[code]}">`;
        openSpans++;
        i += 2;
        continue;
      }
    }
    result += text[i];
    i++;
  }
  result += '</span>'.repeat(openSpans);
  return result;
}

function formatMaterial(mat) {
  return mat.toLowerCase().replace(/_/g, ' ');
}

function renderItemIcon(material, headId, allHeads, small = false) {
  if (headId) {
    // For head references, use isometric container that will be rendered later
    const headDef = allHeads?.[headId];
    if (headDef?.texture) {
      const containerClass = small ? 'drop-head-icon-container' : 'head-icon-container';
      return `<div class="item-icon ${containerClass}" data-texture="${headDef.texture}"></div>`;
    }
    return `<span class="item-text head-ref">${headId}</span>`;
  }
  if (material) {
    const url = getTextureUrl(material);
    if (!url) {
      return `<span class="item-text">${formatMaterial(material)}</span>`;
    }
    return `<img class="item-icon lazy-img" data-src="${url}" alt="${material}" title="${formatMaterial(material)}">`;
  }
  return '';
}

function renderOutputItem(recipe, allHeads) {
  // Recipe output is identified by the recipe's id field (which is a head id)
  const headId = recipe.id;
  const amount = recipe.amount || 1;
  const icon = renderItemIcon(null, headId, allHeads);
  return `
    <div class="recipe-output">
      <div class="output-icon">${icon}</div>
      <div class="output-amount">${amount > 1 ? `x${amount}` : ''}</div>
    </div>
  `;
}

function renderIngredient(ing, allHeads, small = false) {
  const icon = renderItemIcon(ing.material, ing.head, allHeads, small);
  const label = ing.head
    ? `<span class="head-ref">${stripColorCodes(allHeads[ing.head]?.name || ing.head)}</span>`
    : `<span class="material">${formatMaterial(ing.material || '?')}</span>`;
  return `${icon} ${label}`;
}

function renderShapedRecipe(recipe, allHeads) {
  const pattern = recipe.pattern || [];
  const key = recipe.key || {};
  let gridHtml = '<div class="crafting-grid">';
  for (let row = 0; row < 3; row++) {
    const line = pattern[row] || '   ';
    for (let col = 0; col < 3; col++) {
      const char = line[col] || ' ';
      if (char === ' ') {
        gridHtml += '<div class="grid-slot empty"></div>';
      } else {
        const ing = key[char];
        const icon = ing ? renderItemIcon(ing.material, ing.head, allHeads) : '';
        const amt = ing && ing.amount > 1 ? `<span class="amount-badge">x${ing.amount}</span>` : '';
        gridHtml += `<div class="grid-slot">${icon}${amt}</div>`;
      }
    }
  }
  gridHtml += '</div>';
  return `
    <div class="recipe">
      <div class="recipe-type">Shaped Crafting</div>
      <div class="recipe-flow">
        <div class="recipe-inputs">${gridHtml}</div>
        <div class="recipe-arrow">→</div>
        ${renderOutputItem(recipe, allHeads)}
      </div>
    </div>
  `;
}

function renderShapelessRecipe(recipe, allHeads) {
  const ingredients = recipe.ingredients || [];
  let html = '<div class="ingredients-list">';
  for (const ing of ingredients) {
    html += `<div class="ingredient">
      <span class="amount">${ing.amount || 1}x</span>
      ${renderIngredient(ing, allHeads)}
    </div>`;
  }
  html += '</div>';
  return `
    <div class="recipe">
      <div class="recipe-type">Shapeless Crafting</div>
      <div class="recipe-flow">
        <div class="recipe-inputs">${html}</div>
        <div class="recipe-arrow">→</div>
        ${renderOutputItem(recipe, allHeads)}
      </div>
    </div>
  `;
}

function renderStonecutterRecipe(recipe, allHeads) {
  const input = recipe.input || {};
  return `
    <div class="recipe">
      <div class="recipe-type">Stonecutter</div>
      <div class="recipe-flow">
        <div class="recipe-inputs">
          <div class="ingredient">
            <span class="amount">${input.amount || 1}x</span>
            ${renderIngredient(input, allHeads)}
          </div>
        </div>
        <div class="recipe-arrow">→</div>
        ${renderOutputItem(recipe, allHeads)}
      </div>
    </div>
  `;
}

function formatCondition(when) {
  if (!when || Object.keys(when).length === 0) {
    return 'Always';
  }
  return Object.entries(when)
    .map(([key, value]) => `${key.replace(/_/g, ' ')} = ${value}`)
    .join(', ');
}

function renderDrops(drops, allHeads) {
  if (!drops || !drops.on_break || drops.on_break.length === 0) return '';
  let html = '<div class="drops-section"><h3>Drops</h3>';
  for (const rule of drops.on_break) {
    const condition = formatCondition(rule.when);
    html += `<div class="drop-rule">
      <div class="drop-condition">${condition}</div>`;
    for (const drop of (rule.drops || [])) {
      html += `<div class="ingredient">
        <span class="amount">${drop.amount || 1}x</span>
        ${renderIngredient(drop, allHeads, true)}
      </div>`;
    }
    html += '</div>';
  }
  html += '</div>';
  return html;
}

function renderHead(id, head, allHeads) {
  const hasTexture = !!head.texture;
  let textureHtml;
  if (hasTexture) {
    textureHtml = `<div class="head-texture-container" data-texture="${head.texture}"></div>`;
  } else {
    textureHtml = `<div class="head-texture placeholder">No texture</div>`;
  }

  let loreHtml = '';
  if (head.lore && head.lore.length > 0) {
    loreHtml = '<div class="lore">' +
      head.lore.map(l => `<p>${parseMinecraftColors(l)}</p>`).join('') +
      '</div>';
  }

  let recipesHtml = '';
  const recipes = head.recipes || {};
  const craft = recipes.craft || {};
  const hasRecipes = (craft.shaped?.length || craft.shapeless?.length || recipes.stonecutter?.length);
  if (hasRecipes) {
    recipesHtml = '<div class="recipes-section"><h3>Recipes</h3>';
    for (const r of (craft.shaped || [])) {
      recipesHtml += renderShapedRecipe(r, allHeads);
    }
    for (const r of (craft.shapeless || [])) {
      recipesHtml += renderShapelessRecipe(r, allHeads);
    }
    for (const r of (recipes.stonecutter || [])) {
      recipesHtml += renderStonecutterRecipe(r, allHeads);
    }
    recipesHtml += '</div>';
  }

  const dropsHtml = renderDrops(head.drops, allHeads);

  const headIdHtml = `<div class="head-id">${id}</div>`;

  return `
    <div class="head-card">
      <div class="head-header">
        ${textureHtml}
        <div class="head-info">
          <h2>${parseMinecraftColors(head.name || id)}</h2>
          ${headIdHtml}
        </div>
      </div>
      ${loreHtml}
      ${recipesHtml}
      ${dropsHtml}
    </div>
  `;
}

async function loadTexture(el) {
  // Handle lazy-loaded images (item icons)
  if (el.classList.contains('lazy-img')) {
    el.src = el.dataset.src;
    el.classList.remove('lazy-img');
    return;
  }

  // Handle head texture containers
  const texture = el.dataset.texture;
  const size = JSON.parse(el.dataset.size);
  if (!texture) return;
  try {
    const dataUrl = await getCachedIsometricHead(texture);
    const img = document.createElement('img');
    img.src = dataUrl;
    img.style.imageRendering = 'pixelated';
    img.style.width = size.width;
    img.style.height = size.height;
    el.appendChild(img);
  } catch (e) {
    el.textContent = size.errorText;
    if (size.addPlaceholder) el.classList.add('placeholder');
  }
}

function renderHeads(ids = null) {
  // Disconnect previous observers to prevent memory leaks
  textureObserver.disconnect();

  const container = document.getElementById('heads-container');
  const displayIds = ids || Object.keys(headsData);

  // Update the head counter
  updateHeadCounter(displayIds.length);

  if (displayIds.length === 0) {
    container.innerHTML = '<p>No heads found</p>';
    return;
  }
  container.innerHTML = displayIds.map(id => renderHead(id, headsData[id], headsData)).join('');

  // Observe all texture containers for lazy loading
  const headContainers = container.querySelectorAll(
    '.head-texture-container, .head-icon-container, .drop-head-icon-container'
  );
  for (const el of headContainers) {
    // Store size info in data attribute for the observer callback
    if (el.classList.contains('head-texture-container')) {
      el.dataset.size = JSON.stringify({ width: '92px', height: '108px', errorText: 'Failed to load', addPlaceholder: true });
    } else if (el.classList.contains('head-icon-container')) {
      el.dataset.size = JSON.stringify({ width: '46px', height: '54px', errorText: '?' });
    } else {
      el.dataset.size = JSON.stringify({ width: '28px', height: '32px', errorText: '?' });
    }
    textureObserver.observe(el);
  }

  // Observe lazy-loaded item images
  const lazyImages = container.querySelectorAll('.lazy-img');
  for (const el of lazyImages) {
    textureObserver.observe(el);
  }
}

function showError(msg) {
  const el = document.getElementById('error');
  el.textContent = msg;
  el.style.display = 'block';
}

// Find line number of a key in YAML text (1-indexed)
function findYamlLineNumber(yamlText, key) {
  const lines = yamlText.split('\n');
  // Look for the key at the start of a line (under 'heads:' section)
  const keyPattern = new RegExp(`^  ${key}:`, 'm');
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].match(new RegExp(`^  ${key}:`))) {
      return i + 1; // 1-indexed
    }
  }
  return 1; // Fallback to line 1
}

// Progress bar helpers
let headCounts = {};  // filename -> count
let totalHeads = 5000;

function updateProgress(downloaded, parsed) {
  const dlPct = totalHeads > 0 ? (downloaded / totalHeads) * 100 : 0;
  const parsePct = totalHeads > 0 ? (parsed / totalHeads) * 100 : 0;
  document.getElementById('progress-download').style.width = `${dlPct}%`;
  document.getElementById('progress-parse').style.width = `${parsePct}%`;
  document.getElementById('progress-text').textContent = `${parsed} / ${totalHeads} heads`;
}

function hideLoading() {
  document.getElementById('loading-container').style.display = 'none';
}

async function loadHeadFiles() {
  // Load head counts JSON
  try {
    const countResponse = await fetch('./util/head-count.json');
    if (countResponse.ok) {
      headCounts = await countResponse.json();
      totalHeads = headCounts.total || 5000;
      delete headCounts.total;
    }
  } catch (e) {
    // Use defaults
  }
  updateProgress(0, 0);

  // Derive head files list from the JSON keys
  const headFiles = Object.keys(headCounts).filter(k => k.endsWith('.yml'));

  // Load and merge all head files, tracking source file and line
  const allHeads = {};
  let downloadedCount = 0;
  let parsedCount = 0;

  for (const file of headFiles) {
    const response = await fetch(`./data/${file}`);
    if (!response.ok) {
      console.warn(`Failed to load ${file}: ${response.status}`);
      continue;
    }

    // Update download progress (using known count for this file)
    downloadedCount += headCounts[file] || 0;
    updateProgress(downloadedCount, parsedCount);

    const yaml = await response.text();
    const data = jsyaml.load(yaml);
    const heads = data.heads || {};
    for (const [id, head] of Object.entries(heads)) {
      head._sourceFile = file;
      head._sourceLine = findYamlLineNumber(yaml, id);
      allHeads[id] = head;
      parsedCount++;
    }
    // Update parse progress after each file
    updateProgress(downloadedCount, parsedCount);
  }
  hideLoading();
  return allHeads;
}

async function init() {
  try {
    setupTextureObserver();
    await Promise.all([loadTextureLists(), loadTagOrderConfig()]);
    headsData = await loadHeadFiles();

    // Restore state from URL before initial render
    loadStateFromURL();

    renderFilterPills();

    // Set up search
    const searchInput = document.getElementById('search-input');

    // Apply filters from URL state
    const filteredIds = filterHeads(searchInput.value);
    renderHeads(filteredIds);
    const handleSearch = debounce(async () => {
      const filteredIds = filterHeads(searchInput.value);
      renderHeads(filteredIds);
      saveStateToURL();
    }, 200);
    searchInput.addEventListener('input', handleSearch);

    // Set up filter pill clicks (event delegation)
    document.addEventListener('click', async (e) => {
      // Handle breadcrumb navigation
      if (e.target.matches('[data-tag-level]')) {
        const level = parseInt(e.target.dataset.tagLevel, 10);
        if (level === -1) {
          // "All" clicked - go to root, show all heads
          activeTagPath = [];
          renderFilterPills();
          renderHeads(filterHeads(searchInput.value));
        } else {
          // Navigate to specific level
          activeTagPath = activeTagPath.slice(0, level + 1);
          renderFilterPills();
          // Check if this level has children - if so, don't filter heads yet
          const { tree } = buildTagTree();
          const isParentLevel = activeTagPath.length === 1 && tree[activeTagPath[0]];
          if (isParentLevel) {
            // At a parent level with children - show hint instead of heads
            document.getElementById('heads-container').innerHTML =
              '<p class="select-subtag-hint">Select a subcategory above to view heads</p>';
            updateHeadCounter(0);
          } else {
            // At a leaf level - filter and show heads
            renderHeads(filterHeads(searchInput.value));
          }
        }
        saveStateToURL();
        return;
      }

      // Handle tag pill clicks
      if (e.target.matches('[data-tag]')) {
        const tag = e.target.dataset.tag;
        const hasChildren = e.target.dataset.hasChildren === 'true';

        if (hasChildren) {
          // Drill down into hierarchical tag - show subtags, clear heads display
          activeTagPath = [tag];
          renderFilterPills();
          // Show instruction instead of heads
          document.getElementById('heads-container').innerHTML =
            '<p class="select-subtag-hint">Select a subcategory above to view heads</p>';
          updateHeadCounter(0);
        } else if (activeTagPath.length > 0 && e.target.dataset.flat !== 'true') {
          // Leaf tag in hierarchical view - select full path and filter heads
          activeTagPath = [activeTagPath[0], tag];
          renderFilterPills();
          renderHeads(filterHeads(searchInput.value));
        } else {
          // Flat tag - toggle in/out of set (multi-select)
          if (activeTags.has(tag)) {
            activeTags.delete(tag);
          } else {
            activeTags.add(tag);
          }
          updateActiveFilters();
          renderHeads(filterHeads(searchInput.value));
        }
        saveStateToURL();
        return;
      }

      if (e.target.matches('[data-property]')) {
        const prop = e.target.dataset.property;
        activeProperty = activeProperty === prop ? null : prop;
        updateActiveFilters();
        renderHeads(filterHeads(searchInput.value));
        saveStateToURL();
      }
      // Handle head ID link clicks to open in VS Code
      // if (e.target.matches('.head-id-link')) {
      //   e.preventDefault();
      //   const file = e.target.dataset.file;
      //   const line = e.target.dataset.line;
      //   // Base path to the resources directory (adjust if your workspace differs)
      //   const basePath = '~/headsmith/src/main/resources';
      //   // file already contains 'heads/' prefix from config.yml
      //   const fullPath = `${basePath}/${file}`;
      //   // Open in VS Code
      //   window.open(`vscode://file${fullPath}:${line}`, '_blank');
      // }
    });

    // Set up refresh button (re-renders with current filters)
    document.getElementById('refresh-btn').addEventListener('click', async () => {
      renderHeads(filterHeads(searchInput.value));
    });

    // Set up clear button (clears all filters and search)
    document.getElementById('clear-btn').addEventListener('click', async () => {
      searchInput.value = '';
      activeTags.clear();
      activeTagPath = [];
      activeProperty = null;
      renderFilterPills();
      updateActiveFilters();
      renderHeads();
      saveStateToURL();
    });
  } catch (e) {
    showError(e.message);
  }
}

init();
