// Showcase detail page: one machine rendered large + live (the captured multi-block scene), its
// long-form description, and a "Blocks used" list linking to each custom block's catalog page. The
// per-block previews are STATIC thumbnails (in-hand 2D icon + offscreen-rendered placed PNG) so the
// page only ever holds ONE live WebGL context (the machine viewer), well under the browser cap.

import { esc, mcText, iconHtml, hydrateHeads, itemHref } from './render.js';
import { renderScene, thumbnailDataURL, placedVariantBlocks } from './placed3d.js';
import { toRenderBlock } from './viewers.js';

function showError(msg) {
  const err = document.getElementById('error');
  err.style.display = 'block';
  err.textContent = msg;
}

// Live viewer teardowns; released on navigation so the WebGL context doesn't leak.
const teardowns = new Set();
let machineApi = null;   // the machine viewer's teardown fn, carrying highlight()/clearHighlight()
window.addEventListener('pagehide', () => {
  for (const t of teardowns) { try { t(); } catch { /* ignore */ } }
  teardowns.clear();
});

// One distinct block in "Blocks used": a link to its catalog page with its in-hand icon and a static
// placed thumbnail. Falls back to a non-link card when the block has no catalog item.
function blockCard(block, item) {
  if (!item) {
    return `<div class="block-card block-card--bare" data-block-id="${esc(block.id)}">
      <div class="block-card-id">${esc(block.id)}</div>
    </div>`;
  }
  return `<a class="block-card" href="${itemHref(item.fullId)}" data-block-id="${esc(block.id)}">
    <div class="block-card-thumbs">
      <div class="block-thumb">${item.placedVariants?.length ? `<div class="placed-thumb" data-thumb="${esc(item.fullId)}"></div>` : iconHtml(item)}</div>
      <div class="block-thumb block-thumb--hand">${iconHtml(item)}</div>
    </div>
    <div class="block-card-name">${mcText(item.name)}</div>
    <div class="block-card-id">${esc(item.fullId)}</div>
  </a>`;
}

function renderShowcase(sc, itemsById) {
  const detail = document.getElementById('detail');
  document.title = `DefCoreLib — ${sc.name || sc.id}`;

  // Distinct block ids, first-seen order.
  const seen = new Set();
  const distinct = [];
  for (const b of sc.blocks || []) {
    if (seen.has(b.id)) continue;
    seen.add(b.id);
    distinct.push(b);
  }

  detail.innerHTML = `
    <div class="detail-header">
      <div><h1 class="detail-name">${esc(sc.name || sc.id)}</h1></div>
    </div>
    <div class="showcase-canvas" id="machine"></div>
    ${sc.description ? `<p class="showcase-description">${mcText(sc.description)}</p>` : ''}
    <div class="detail-section">
      <h2 class="section-title">Blocks used</h2>
      <div class="blocks-used">
        ${distinct.map((b) => blockCard(b, itemsById.get(b.id))).join('')}
      </div>
    </div>
  `;

  // Live machine viewer.
  const machine = document.getElementById('machine');
  renderScene(machine, (sc.blocks || []).map(toRenderBlock), { autoframe: true })
    .then((t) => { if (typeof t === 'function') { teardowns.add(t); machineApi = t; } })
    .catch((e) => { console.warn(e); machine.innerHTML = '<div class="viewer-fail">3D unavailable</div>'; });

  // Hover a "Blocks used" card → outline the matching block(s) in the live machine view. Delegated
  // (mouseenter/leave don't bubble); the relatedTarget guard mirrors tooltip.js so moving among a
  // card's own children doesn't flicker. machineApi starts null → hovers before the scene resolves no-op.
  const blocksUsed = detail.querySelector('.blocks-used');
  if (blocksUsed) {
    blocksUsed.addEventListener('mouseover', (e) => {
      const card = e.target.closest('.block-card');
      if (!card) return;
      const id = card.dataset.blockId;
      if (!id) return;
      machineApi?.highlight(id);
    });
    blocksUsed.addEventListener('mouseout', (e) => {
      const card = e.target.closest('.block-card');
      if (!card || card.contains(e.relatedTarget)) return;
      machineApi?.clearHighlight();
    });
  }

  // In-hand head icons (2D) + static placed thumbnails for the blocks-used cards.
  hydrateHeads(detail);
  detail.querySelectorAll('.placed-thumb').forEach((el) => {
    const item = itemsById.get(el.dataset.thumb);
    if (!item) return;
    thumbnailDataURL(placedVariantBlocks(item), { size: 192, cacheKey: item.fullId }).then((url) => {
      if (!url || !el.isConnected) return;
      const img = new Image();
      img.src = url;
      img.alt = '';
      img.className = 'placed-thumb-img';
      el.appendChild(img);
    });
  });
}

async function init() {
  const id = new URLSearchParams(window.location.search).get('id');
  if (!id) { showError('No showcase specified.'); return; }

  let scData, itemsData;
  try {
    [scData, itemsData] = await Promise.all([
      fetch('./data/showcases.json').then((r) => { if (!r.ok) throw new Error(`showcases.json: ${r.status}`); return r.json(); }),
      fetch('./data/items.json').then((r) => { if (!r.ok) throw new Error(`items.json: ${r.status}`); return r.json(); }),
    ]);
  } catch (e) {
    showError(`Could not load data (${e.message}). Run \`make showcase-capture\` to generate it.`);
    return;
  }

  const sc = (scData.showcases || []).find((s) => s.id === id);
  if (!sc) { showError(`Unknown showcase: ${id}`); return; }
  const itemsById = new Map((itemsData.items || []).map((it) => [it.fullId, it]));
  renderShowcase(sc, itemsById);
}

init();
