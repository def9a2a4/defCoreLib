// Shared helpers for mounting custom-block 3D viewers and for mapping captured "showcase" blocks
// into placed3d's render shape. The mount* helpers return a teardown function (or null when no live
// WebGL context was created). Teardown ownership stays with the CALLER: each page keeps its own
// registry + pagehide cleanup (the catalog also tears down per-render()), so this module deliberately
// does NOT hold a global Set of teardowns.

import { render3DHead } from './head3d.js';
import { renderPlaced } from './placed3d.js';
import { materialPath } from './render.js';

// Showcase blocks carry `facing` ("floor" | "wall_<n|s|e|w>"); map to placed3d's base-head fields.
// The "wall_<dir>" here is the PLACEMENT face the head sits ON (ShowcaseRunner stores bs.facing());
// the head LOOKS the opposite way. The item path stores the look direction (placedOn.getOppositeFace())
// and placed3d's push + rotation both expect the look direction, so invert here to match.
const OPPOSITE = { north: 'south', south: 'north', east: 'west', west: 'east' };
export function toRenderBlock(blk) {
  const wall = typeof blk.facing === 'string' && blk.facing.startsWith('wall_');
  return {
    id: blk.id,
    offset: blk.offset || [0, 0, 0],
    baseHeadTextureUrl: blk.baseHeadTextureUrl,
    baseHeadWall: wall,
    baseHeadFacing: wall ? (OPPOSITE[blk.facing.slice(5)] || null) : null,
    displays: blk.displays || [],
  };
}

// True when an item has an "in hand" representation worth a viewer panel.
export function hasInHand(item) {
  const ih = item.inHand || {};
  return (ih.kind === 'head' && !!ih.textureUrl) || (ih.kind === 'item' && !!ih.block);
}

// Mount an item's "in hand" view into `container`. Head items render as a live 3D head (teardown
// returned); vanilla item_material items show the reliable 2D inventory icon (no context → null).
export async function mountInHand(item, container) {
  const ih = item.inHand || {};
  if (ih.kind === 'head' && ih.textureUrl) return render3DHead(ih.textureUrl, container);
  if (ih.kind === 'item' && ih.block) {
    container.innerHTML = `<img class="flat-item" src="${materialPath(ih.block)}" alt="">`;
    return null;
  }
  return null;
}

// Mount a placed variant into `container`; no-op (null) when the item has no placed variants.
export async function mountPlaced(item, container, variantIndex = 0) {
  if (!item.placedVariants?.length) return null;
  return renderPlaced(item, container, variantIndex);
}
