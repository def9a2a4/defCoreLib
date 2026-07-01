// Renders the multi-block "showcase" machines captured by the plugin (ShowcaseRunner capture →
// docs/data/showcase-spec.json → generate_catalog → docs/data/showcases.json). Each machine is a list
// of blocks at offsets; we composite them into one auto-framed scene and play the baked animation
// tracks, reusing the placed3d.js renderer.

import { renderScene } from './placed3d.js';
import { toRenderBlock } from './viewers.js';
import { esc } from './render.js';

const root = document.getElementById('showcases');
const errEl = document.getElementById('error');

// Showcases hidden from the page for now (still captured; remove an id to re-enable).
const HIDDEN = new Set(['water_wheel_fan']);

function fail(msg) {
  if (errEl) errEl.textContent = msg;
  console.error(msg);
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

  const showcases = (data.showcases || []).filter((sc) => !HIDDEN.has(sc.id));
  if (!showcases.length) {
    fail('No showcases found in showcases.json.');
    return;
  }

  for (const sc of showcases) {
    const card = document.createElement('section');
    card.className = 'showcase';

    const h2 = document.createElement('h2');
    h2.innerHTML = `<a class="showcase-title-link" href="./showcase.html?id=${encodeURIComponent(sc.id)}">${esc(sc.name || sc.id)}</a>`;
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

    const more = document.createElement('a');
    more.className = 'details-link';
    more.href = `./showcase.html?id=${encodeURIComponent(sc.id)}`;
    more.textContent = 'Details →';
    card.appendChild(more);

    root.appendChild(card);

    const blocks = (sc.blocks || []).map(toRenderBlock);
    renderScene(canvas, blocks, { autoframe: true }).catch((e) =>
      console.error(`render ${sc.id} failed:`, e));
  }
}

main();
