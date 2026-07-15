// Renders a placed custom block as an interactive 3D scene from the GROUND-TRUTH data exported
// by the plugin (scripts → DisplayExporter → docs/data/display-spec.json → items.json placedVariants).
// Each display carries the real read-back transform matrix[16], a position offset (captures
// wall_offset), and, when animated, a baked keyframe track the browser just plays back — so there
// is no animation math here and nothing re-derived from YAML.

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { loadSkin, skullMesh } from './head3d.js';
import { buildBlockMesh, fallbackBox, texturesSettled } from './blockmodel.js';

let MANIFEST = null;
async function manifest() {
  if (!MANIFEST) {
    MANIFEST = await fetch('./data/models-manifest.json').then((r) => r.ok ? r.json() : {}).catch(() => ({}));
  }
  return MANIFEST;
}

// Mirror scripts/generate_catalog.py canonical_block(): wool/banner default to white,
// iron_chain → chain (the 1.21.9+ IRON_CHAIN rename; pinned assets only ship the `chain` model).
function canonical(ref) {
  let n = String(ref).split('[')[0].split(':').pop().toLowerCase();
  if (n.endsWith('_wool') || n === 'wool') return 'white_wool';
  if (n.endsWith('_banner') || n === 'banner') return 'white_banner';
  if (n === 'iron_chain') return 'chain';
  return n;
}

function makeViewer(container, { dist = 3.4, target = [0, 0, 0] } = {}) {
  const width = container.clientWidth || 300;
  const height = container.clientHeight || 300;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100);
  camera.position.set(dist * 0.75, dist * 0.5, dist);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));   // cap before setSize; >2 wastes a lot of fill
  renderer.setSize(width, height);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);

  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.06;
  controls.target.set(...target);
  controls.minDistance = 1.2;
  controls.maxDistance = 12;
  controls.update();

  scene.add(new THREE.AmbientLight(0xffffff, 1.0));
  return { scene, camera, renderer, controls };
}

async function buildDisplayObject(de, models) {
  if (de.kind === 'head') {
    try { return skullMesh(await loadSkin(de.ref)); }
    catch { return fallbackBox(0x9b6cff); }
  }
  // BlockDisplays read back corner-origin; ItemDisplays read back centered.
  const centered = de.kind !== 'block';
  const id = canonical(de.ref);
  if (models[id]) {
    try { return await buildBlockMesh(id, { centered }); }
    catch { return fallbackBox(0xcccccc, { centered }); }
  }
  return fallbackBox(0xcccccc, { centered });
}

// Pre-decompose a baked keyframe track ([[16],...]) for cheap per-frame interpolation.
function prepareTrack(frames) {
  return frames.map((f) => {
    const m = new THREE.Matrix4().fromArray(f);
    const pos = new THREE.Vector3();
    const quat = new THREE.Quaternion();
    const scale = new THREE.Vector3();
    m.decompose(pos, quat, scale);
    return { pos, quat, scale };
  });
}

function sampleTrack(track, period, tick, out) {
  const n = track.length;
  const x = ((tick % period) / period) * n;       // [0, n)
  const i = Math.floor(x) % n;
  const j = (i + 1) % n;
  const a = track[i], b = track[j];
  const f = x - Math.floor(x);
  const pos = a.pos.clone().lerp(b.pos, f);
  const quat = a.quat.clone().slerp(b.quat, f);
  const scale = a.scale.clone().lerp(b.scale, f);
  out.compose(pos, quat, scale);
}

const IDENTITY = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

// Build one block group: base head (if any) + each display, parented by its position offset.
async function buildBlockGroup(blk, models, animated) {
  const group = new THREE.Group();
  group.userData.blockId = blk.id ?? null;   // lets renderScene's highlight() match this block by id
  const off = blk.offset || [0, 0, 0];
  group.position.set(off[0], off[1], off[2]);

  // Base head (the custom head block itself). A floor PLAYER_HEAD is seated; a PLAYER_WALL_HEAD is
  // mounted vertically-centred and pushed onto the wall (−0.25·facing), matching MC's skull renderer.
  if (blk.baseHeadTextureUrl) {
    try {
      const wall = !!blk.baseHeadWall;
      const skull = skullMesh(await loadSkin(blk.baseHeadTextureUrl), { seated: !wall });
      if (wall && blk.baseHeadFacing) {
        const f = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0] }[blk.baseHeadFacing] || [0, 0, 0];
        // The skull's FACE is authored on the −z (north) cube side (cubeMaterials maps skin front
        // region (8,8) → −z; the "+z front" comment there is mislabelled). Rotate FROM north to look
        // along baseHeadFacing: three.js rotation.y=θ sends −z to (−sinθ,−cosθ) → 0=north, π=south,
        // −π/2=east, +π/2=west. Skull group is xz-centred so this spins in place; the −0.25·facing
        // push then seats it on the wall.
        skull.rotation.y = { north: 0, south: Math.PI, east: -Math.PI / 2, west: Math.PI / 2 }[blk.baseHeadFacing] || 0;
        skull.position.set(-0.25 * f[0], 0, -0.25 * f[2]);
      }
      group.add(skull);
    } catch { /* skip */ }
  }

  for (const de of blk.displays || []) {
    const obj = await buildDisplayObject(de, models);
    obj.matrixAutoUpdate = false;
    obj.matrix.fromArray(de.matrix || IDENTITY);
    obj.matrixWorldNeedsUpdate = true;

    const parent = new THREE.Group();
    const p = de.position || [0, 0, 0];
    parent.position.set(p[0], p[1], p[2]);
    parent.add(obj);
    group.add(parent);

    if (de.animation && de.animation.frames && de.animation.frames.length) {
      animated.push({ obj, track: prepareTrack(de.animation.frames), period: de.animation.period || de.animation.frames.length });
    }
  }
  return group;
}

/**
 * Render a list of blocks (each `{offset, baseHeadTextureUrl, baseHeadWall, baseHeadFacing, displays}`)
 * into `container` as one interactive scene, playing every display's baked track. With `autoframe` the
 * camera fits the scene bounding box (for multi-block machines). Returns a teardown function.
 */
export async function renderScene(container, blocks, { autoframe = false, dist = 3.6, target = [0, 0.1, 0], zoom = 1 } = {}) {
  const models = await manifest();
  const { scene, camera, renderer, controls } = makeViewer(container, { dist, target });

  const animated = [];
  for (const blk of blocks) scene.add(await buildBlockGroup(blk, models, animated));

  if (autoframe) {
    const box = new THREE.Box3().setFromObject(scene);
    if (!box.isEmpty()) {
      const center = box.getCenter(new THREE.Vector3());
      const size = box.getSize(new THREE.Vector3());
      const d = (Math.max(size.x, size.y, size.z, 1) * 1.6 + 1.5) / zoom;   // zoom>1 tightens the frame
      controls.target.copy(center);
      camera.position.set(center.x + d * 0.6, center.y + d * 0.45, center.z + d);
      controls.maxDistance = d * 4;
      controls.update();
    }
  }

  // Optional hover-highlight: a BoxHelper outline around the block group(s) matching a given id.
  // setFromObject (inside BoxHelper.update) recomputes the group's world matrices itself, so updating
  // the helpers in the RAF loop keeps the outline aligned as the block animates.
  const highlightHelpers = [];
  let currentHighlightId = null;

  let alive = true;
  const t0 = performance.now();
  (function animate(now) {
    if (!alive) return;
    requestAnimationFrame(animate);
    const tick = ((now || performance.now()) - t0) / 1000 * 20;   // 20 ticks/sec
    for (const a of animated) {
      sampleTrack(a.track, a.period, tick, a.obj.matrix);
      a.obj.matrixWorldNeedsUpdate = true;
    }
    for (const h of highlightHelpers) h.update();
    controls.update();
    renderer.render(scene, camera);
  })();

  const teardown = () => {
    alive = false;                       // stop the RAF loop first
    teardown.clearHighlight();
    controls.dispose();
    const disposeMat = (m) => { if (m && !m.userData?.shared) m.dispose(); };   // skip shared singletons
    scene.traverse((o) => {
      o.geometry?.dispose();
      const m = o.material;
      if (Array.isArray(m)) m.forEach(disposeMat);
      else disposeMat(m);
    });
    // NOTE: do NOT dispose textures — they live in the shared texCache/skin cache and are reused;
    // forceContextLoss() below frees their GPU uploads anyway.
    renderer.dispose();
    renderer.forceContextLoss();         // release the WebGL context (browsers cap at ~16)
    renderer.domElement.remove();
  };

  // Outline every top-level block group whose id matches (multiple instances share an id).
  teardown.highlight = (id) => {
    if (!alive || id === currentHighlightId) return;
    teardown.clearHighlight();
    for (const o of scene.children) {
      if (o.userData.blockId !== id) continue;   // tolerates the AmbientLight (no blockId)
      const h = new THREE.BoxHelper(o, 0x4ecca3);
      scene.add(h);
      highlightHelpers.push(h);
    }
    currentHighlightId = id;
  };
  teardown.clearHighlight = () => {
    for (const h of highlightHelpers) {
      scene.remove(h);
      h.geometry.dispose();
      h.material.dispose();   // BoxHelper owns a non-shared LineBasicMaterial — safe to dispose
    }
    highlightHelpers.length = 0;
    currentHighlightId = null;
  };

  return teardown;
}

/** The renderScene block list for one of an item's placed variants (one block at the origin). */
export function placedVariantBlocks(item, variantIndex = 0) {
  const variants = item.placedVariants || [];
  const variant = variants[variantIndex] || variants[0] || { displays: [] };
  return [{
    offset: [0, 0, 0],
    baseHeadTextureUrl: variant.baseHeadTextureUrl,
    baseHeadWall: variant.baseHeadWall,
    baseHeadFacing: variant.baseHeadFacing,
    displays: variant.displays || [],
  }];
}

/** Render a single placed variant into `container`. Teardown fn returned. */
export async function renderPlaced(item, container, variantIndex = 0) {
  // autoframe so the item fills the canvas with the same framing as the catalog snapshot
  // (thumbnailDataURL uses this same box math); dist/target seed the camera pre-frame.
  return renderScene(container, placedVariantBlocks(item, variantIndex),
    { autoframe: true, dist: 3.6, target: [0, 0.1, 0], zoom: 1.7 });
}

// ── Offscreen thumbnails ───────────────────────────────────────────────────
// A single shared, headless renderer reused for every thumbnail (one WebGL context total, regardless
// of how many cards are on the grid — the browser caps live contexts at ~16). Calls are serialized so
// the shared renderer is never re-entered mid-frame.
let THUMB_RENDERER = null;
let thumbChain = Promise.resolve();

function thumbRenderer(size) {
  if (!THUMB_RENDERER) {
    THUMB_RENDERER = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
    THUMB_RENDERER.setPixelRatio(1);
    THUMB_RENDERER.outputColorSpace = THREE.SRGBColorSpace;
  }
  THUMB_RENDERER.setSize(size, size);
  return THUMB_RENDERER;
}

/**
 * Render `blocks` (same shape as renderScene) to a single still PNG data-URL, framed like the live
 * placed viewer. Awaits texture loads first so the frame isn't captured untextured. Animated displays
 * are posed at a fixed mid-loop tick so the snapshot looks alive. Pass `cacheKey` to memoise in
 * localStorage. Returns the data-URL (or null on failure).
 */
export function thumbnailDataURL(blocks, { size = 256, tick = 12, cacheKey = null, zoom = 1.7 } = {}) {
  // Bump the version prefix whenever the render changes, so every visitor's cached PNGs regenerate
  // (v3: base heads are rotated to face their direction, with the corrected rotation sign).
  const lsKey = cacheKey ? 'defcorelib-thumb:v3:' + cacheKey : null;
  if (lsKey) {
    const cached = localStorage.getItem(lsKey);
    if (cached) return Promise.resolve(cached);
  }

  const run = async () => {
    const models = await manifest();
    const scene = new THREE.Scene();
    scene.add(new THREE.AmbientLight(0xffffff, 1.0));
    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);

    const animated = [];
    for (const blk of blocks) scene.add(await buildBlockGroup(blk, models, animated));
    await texturesSettled();   // block textures load async; wait so the single frame isn't blank

    for (const a of animated) {
      sampleTrack(a.track, a.period, tick, a.obj.matrix);
      a.obj.matrixWorldNeedsUpdate = true;
    }

    const box = new THREE.Box3().setFromObject(scene);   // mirror renderScene's autoframe math
    if (!box.isEmpty()) {
      const center = box.getCenter(new THREE.Vector3());
      const sz = box.getSize(new THREE.Vector3());
      const d = (Math.max(sz.x, sz.y, sz.z, 1) * 1.6 + 1.5) / zoom;   // matches renderScene; zoom>1 tightens
      camera.position.set(center.x + d * 0.6, center.y + d * 0.45, center.z + d);
      camera.lookAt(center);
    } else {
      camera.position.set(2.55, 1.8, 3.4);
      camera.lookAt(0, 0.1, 0);
    }

    const renderer = thumbRenderer(size);
    renderer.render(scene, camera);
    const url = renderer.domElement.toDataURL('image/png');

    // Dispose the throwaway scene (keep the shared renderer + shared material singletons).
    const disposeMat = (m) => { if (m && !m.userData?.shared) m.dispose(); };
    scene.traverse((o) => {
      o.geometry?.dispose();
      const m = o.material;
      if (Array.isArray(m)) m.forEach(disposeMat); else disposeMat(m);
    });

    if (lsKey) { try { localStorage.setItem(lsKey, url); } catch { /* quota: ignore */ } }
    return url;
  };

  thumbChain = thumbChain.then(run, run);   // serialize; a prior failure must not block the queue
  return thumbChain.catch((e) => { console.warn(e); return null; });
}
