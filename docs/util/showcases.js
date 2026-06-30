// Renders the multi-block "showcase" machines captured by the plugin (ShowcaseRunner capture →
// docs/data/showcase-spec.json → generate_catalog → docs/data/showcases.json). Each machine is a list
// of blocks at offsets; we composite them into one auto-framed scene and play the baked animation
// tracks, reusing the placed3d.js renderer.

import { renderScene } from './placed3d.js';

const root = document.getElementById('showcases');
const errEl = document.getElementById('error');

function fail(msg) {
  if (errEl) errEl.textContent = msg;
  console.error(msg);
}

// Showcase blocks carry `facing` ("floor" | "wall_<n|s|e|w>"); map to placed3d's base-head fields.
function toRenderBlock(blk) {
  const wall = typeof blk.facing === 'string' && blk.facing.startsWith('wall_');
  return {
    offset: blk.offset || [0, 0, 0],
    baseHeadTextureUrl: blk.baseHeadTextureUrl,
    baseHeadWall: wall,
    baseHeadFacing: wall ? blk.facing.slice(5) : null,
    displays: blk.displays || [],
  };
}

async function main() {
  let data;
  try {
    data = await fetch('./data/showcases.json').then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return r.json();
    });
  } catch (e) {
    fail(`Could not load showcases.json (${e.message}). Run \`make showcase-capture\` to generate it.`);
    return;
  }

  const showcases = data.showcases || [];
  if (!showcases.length) {
    fail('No showcases found in showcases.json.');
    return;
  }

  for (const sc of showcases) {
    const card = document.createElement('section');
    card.className = 'showcase';

    const h2 = document.createElement('h2');
    h2.textContent = sc.name || sc.id;
    card.appendChild(h2);

    if (sc.blurb) {
      const p = document.createElement('p');
      p.className = 'showcase-blurb';
      p.textContent = sc.blurb;
      card.appendChild(p);
    }

    const canvas = document.createElement('div');
    canvas.className = 'showcase-canvas';
    card.appendChild(canvas);

    root.appendChild(card);

    const blocks = (sc.blocks || []).map(toRenderBlock);
    renderScene(canvas, blocks, { autoframe: true }).catch((e) =>
      console.error(`render ${sc.id} failed:`, e));
  }
}

main();
