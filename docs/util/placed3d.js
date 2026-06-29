// Renders a placed custom block as an interactive 3D scene: the base head plus every
// YAML display entity (head / block / item), each with its transform and animation.
// Transform order and animation math mirror the plugin exactly:
//   base matrix = T · Rleft · S · Rright   (left/right rotations are axis-angle [deg,x,y,z])
//   animations (DisplayAnimation.java), with tickAge = elapsedSeconds × 20:
//     rotate: out = R(axis, deg/tick · tick) · base      (pre-multiply)
//     bob:    out = base · translate(0, A·sin(2π·tick/period), 0)
//     pulse:  out = base · scale(mid + amp·sin(2π·tick/period))
//     orbit:  out = base · translate(circular offset)
//     compose: layers, each rebased on the previous output

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { loadSkin, headCubeMesh } from './head3d.js';
import { buildBlockMesh, fallbackBox } from './blockmodel.js';

let MANIFEST = null;
async function manifest() {
  if (!MANIFEST) {
    MANIFEST = await fetch('./data/models-manifest.json').then((r) => r.ok ? r.json() : {}).catch(() => ({}));
  }
  return MANIFEST;
}

function axisAngleMatrix(aa) {
  if (!aa) return new THREE.Matrix4();
  const axis = new THREE.Vector3(aa[1], aa[2], aa[3]);
  if (axis.lengthSq() < 1e-9) return new THREE.Matrix4();
  axis.normalize();
  return new THREE.Matrix4().makeRotationAxis(axis, THREE.MathUtils.degToRad(aa[0]));
}

// T · Rleft · S · Rright
function baseMatrix(t) {
  const T = new THREE.Matrix4().makeTranslation(...(t.translation || [0, 0, 0]));
  const Rl = axisAngleMatrix(t.leftRotation);
  const S = new THREE.Matrix4().makeScale(...(t.scale || [1, 1, 1]));
  const Rr = axisAngleMatrix(t.rightRotation);
  return T.multiply(Rl).multiply(S).multiply(Rr);
}

function applyAnimation(anim, base, tick) {
  if (!anim) return base;
  switch (anim.type) {
    case 'rotate': {
      const axis = new THREE.Vector3(...anim.axis);
      if (axis.lengthSq() < 1e-9) return base.clone();
      axis.normalize();
      const R = new THREE.Matrix4().makeRotationAxis(axis, THREE.MathUtils.degToRad(anim.speed) * tick);
      return R.multiply(base.clone());                       // R · base
    }
    case 'bob': {
      const dy = anim.amplitude * Math.sin((2 * Math.PI / anim.period) * tick);
      return base.clone().multiply(new THREE.Matrix4().makeTranslation(0, dy, 0));
    }
    case 'pulse': {
      const mid = (anim.minScale + anim.maxScale) / 2;
      const amp = (anim.maxScale - anim.minScale) / 2;
      const s = mid + amp * Math.sin((2 * Math.PI / anim.period) * tick);
      return base.clone().multiply(new THREE.Matrix4().makeScale(s, s, s));
    }
    case 'orbit': {
      const n = new THREE.Vector3(...anim.axis).normalize();
      const tangent = (Math.abs(n.y) < 0.9 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(1, 0, 0))
        .cross(n).normalize();
      const bitangent = n.clone().cross(tangent).normalize();
      const a = (2 * Math.PI / anim.period) * tick;
      const off = tangent.multiplyScalar(anim.radius * Math.cos(a))
        .add(bitangent.multiplyScalar(anim.radius * Math.sin(a)));
      return base.clone().multiply(new THREE.Matrix4().makeTranslation(off.x, off.y, off.z));
    }
    case 'compose': {
      let out = base;
      for (const layer of anim.layers) out = applyAnimation(layer, out, tick);
      return out;
    }
    default:
      return base;
  }
}

function makeViewer(container, { dist = 3.2, target = [0, 0, 0] } = {}) {
  const width = container.clientWidth || 280;
  const height = container.clientHeight || 280;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100);
  camera.position.set(dist * 0.7, dist * 0.55, dist);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setSize(width, height);
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);

  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.06;
  controls.target.set(...target);
  controls.minDistance = 1.4;
  controls.maxDistance = 10;
  controls.update();

  scene.add(new THREE.AmbientLight(0xffffff, 1.0));
  return { scene, camera, renderer, controls };
}

async function buildEntityObject(de, models) {
  if (de.kind === 'head') {
    try { return headCubeMesh(await loadSkin(de.textureUrl)); }
    catch { return fallbackBox(0x9b6cff); }
  }
  if (models[de.block]) {
    try { return await buildBlockMesh(de.block); }
    catch { return fallbackBox(); }
  }
  return fallbackBox();
}

/** Render the placed block (base head + display entities) into `container`.
 *  Returns a teardown function. */
export async function renderPlaced(item, container) {
  const models = await manifest();
  const placed = item.placed || {};
  const { scene, camera, renderer, controls } = makeViewer(container, { dist: 3.4, target: [0, 0.2, 0] });

  if (placed.baseHead) {
    try { scene.add(headCubeMesh(await loadSkin(placed.baseHead))); } catch { /* skip base */ }
  }

  const animated = [];
  for (const de of placed.displayEntities || []) {
    const obj = await buildEntityObject(de, models);
    const base = baseMatrix(de.transform || {});
    obj.matrixAutoUpdate = false;
    obj.matrix.copy(base);
    obj.matrixWorldNeedsUpdate = true;
    scene.add(obj);
    if (de.animation) animated.push({ obj, base, anim: de.animation });
  }

  let alive = true;
  const t0 = performance.now();
  (function animate(now) {
    if (!alive) return;
    requestAnimationFrame(animate);
    const tick = ((now || performance.now()) - t0) / 1000 * 20;   // 20 ticks/sec
    for (const a of animated) {
      a.obj.matrix.copy(applyAnimation(a.anim, a.base, tick));
      a.obj.matrixWorldNeedsUpdate = true;
    }
    controls.update();
    renderer.render(scene, camera);
  })();

  return () => { alive = false; renderer.dispose(); };
}

/** Render a single vanilla block/item model (the in-hand view for item_material items). */
export async function renderBlock(blockId, container) {
  const models = await manifest();
  const { scene, camera, renderer, controls } = makeViewer(container, { dist: 2.6 });
  let obj;
  if (models[blockId]) { try { obj = await buildBlockMesh(blockId); } catch { obj = fallbackBox(); } }
  else obj = fallbackBox();
  scene.add(obj);

  let alive = true;
  (function animate() {
    if (!alive) return;
    requestAnimationFrame(animate);
    controls.update();
    renderer.render(scene, camera);
  })();
  return () => { alive = false; renderer.dispose(); };
}
