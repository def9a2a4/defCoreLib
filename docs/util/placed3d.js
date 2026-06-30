// Renders a placed custom block as an interactive 3D scene from the GROUND-TRUTH data exported
// by the plugin (scripts → DisplayExporter → docs/data/display-spec.json → items.json placedVariants).
// Each display carries the real read-back transform matrix[16], a position offset (captures
// wall_offset), and, when animated, a baked keyframe track the browser just plays back — so there
// is no animation math here and nothing re-derived from YAML.

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { loadSkin, skullMesh } from './head3d.js';
import { buildBlockMesh, fallbackBox } from './blockmodel.js';

let MANIFEST = null;
async function manifest() {
  if (!MANIFEST) {
    MANIFEST = await fetch('./data/models-manifest.json').then((r) => r.ok ? r.json() : {}).catch(() => ({}));
  }
  return MANIFEST;
}

// Mirror scripts/generate_catalog.py canonical_block(): wool/banner default to white.
function canonical(ref) {
  let n = String(ref).split('[')[0].split(':').pop().toLowerCase();
  if (n.endsWith('_wool') || n === 'wool') return 'white_wool';
  if (n.endsWith('_banner') || n === 'banner') return 'white_banner';
  return n;
}

function makeViewer(container, { dist = 3.4, target = [0, 0, 0] } = {}) {
  const width = container.clientWidth || 300;
  const height = container.clientHeight || 300;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100);
  camera.position.set(dist * 0.75, dist * 0.5, dist);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setSize(width, height);
  renderer.setPixelRatio(window.devicePixelRatio);
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
  const id = canonical(de.ref);
  if (models[id]) {
    try { return await buildBlockMesh(id); }
    catch { return fallbackBox(); }
  }
  return fallbackBox();
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

/** Render a placed variant into `container`. Returns a teardown function. */
export async function renderPlaced(item, container, variantIndex = 0) {
  const models = await manifest();
  const variants = item.placedVariants || [];
  const variant = variants[variantIndex] || variants[0] || { displays: [] };

  const { scene, camera, renderer, controls } = makeViewer(container, { dist: 3.6, target: [0, 0.1, 0] });

  // Base head (the custom head block itself), rendered as a floor-seated skull.
  if (variant.baseHeadTextureUrl) {
    try { scene.add(skullMesh(await loadSkin(variant.baseHeadTextureUrl))); } catch { /* skip */ }
  }

  const animated = [];
  for (const de of variant.displays || []) {
    const obj = await buildDisplayObject(de, models);
    obj.matrixAutoUpdate = false;
    obj.matrix.fromArray(de.matrix || [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
    obj.matrixWorldNeedsUpdate = true;

    const parent = new THREE.Group();
    const p = de.position || [0, 0, 0];
    parent.position.set(p[0], p[1], p[2]);
    parent.add(obj);
    scene.add(parent);

    if (de.animation && de.animation.frames && de.animation.frames.length) {
      animated.push({ obj, track: prepareTrack(de.animation.frames), period: de.animation.period || de.animation.frames.length });
    }
  }

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
    controls.update();
    renderer.render(scene, camera);
  })();

  return () => { alive = false; renderer.dispose(); };
}
