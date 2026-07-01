// Interactive 3D Minecraft head, adapted from HeadSmith's head-renderer.js
// (render3DHead): an inner + outer textured cube built from a skin image. Skins load
// from the locally-vendored ./assets/skins/<hash>.png (same-origin, no CORS proxy).

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const skinCache = new Map();   // url -> Promise<HTMLImageElement>

function skinPath(textureUrl) {
  return `./assets/skins/${textureUrl.split('/').pop()}.png`;
}

export function loadSkin(textureUrl) {
  const src = skinPath(textureUrl);
  if (skinCache.has(src)) return skinCache.get(src);
  const p = new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Missing skin: ' + src));
    img.src = src;
  });
  skinCache.set(src, p);
  return p;
}

function faceTexture(skin, x, y, rotate180 = false) {
  const c = document.createElement('canvas');
  c.width = 8; c.height = 8;
  const ctx = c.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  if (rotate180) { ctx.translate(8, 8); ctx.rotate(Math.PI); }
  ctx.drawImage(skin, x, y, 8, 8, 0, 0, 8, 8);
  const tex = new THREE.CanvasTexture(c);
  tex.magFilter = THREE.NearestFilter;
  tex.minFilter = THREE.NearestFilter;
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

// Material order matches THREE.BoxGeometry: [+x, -x, +y, -y, +z, -z].
function cubeMaterials(skin, ox, oy, transparent) {
  const m = (x, y, rot) => new THREE.MeshBasicMaterial({
    map: faceTexture(skin, x, y, rot),
    transparent,
    alphaTest: transparent ? 0.1 : 0,
    side: transparent ? THREE.DoubleSide : THREE.FrontSide,
  });
  // ox/oy shift selects inner (0,0) vs outer-overlay (+32,0) skin regions.
  return [
    m(ox + 0, oy + 8),          // +x right
    m(ox + 16, oy + 8),         // -x left
    m(ox + 8, oy + 0, true),    // +y top (rotated 180)
    m(ox + 16, oy + 0),         // -y bottom
    m(ox + 24, oy + 8),         // +z back (skin back region)
    m(ox + 8, oy + 8),          // -z front/face (skin face region; so the head looks toward -z/north)
  ];
}

/** A THREE.Group containing the head (inner cube + slightly larger hat overlay),
 *  sized to one block (1×1×1) and centered at the origin. */
export function headCubeMesh(skin) {
  const group = new THREE.Group();
  const inner = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), cubeMaterials(skin, 0, 0, false));
  const outer = new THREE.Mesh(new THREE.BoxGeometry(1.08, 1.08, 1.08), cubeMaterials(skin, 32, 0, true));
  group.add(inner, outer);
  return group;
}

/** A THREE.Group containing a player-HEAD skull as it renders in-game / as a head item:
 *  an 8×8×8 (½-block) skull + 8.5³ hat, occupying the bottom-centre of the block model
 *  (xz centred, y −0.5..0). Matches the local geometry of a custom head block and of a
 *  player-head ItemDisplay, so a placed display's read-back transform lands correctly. */
export function skullMesh(skin, { seated = true } = {}) {
  const group = new THREE.Group();
  const S = 0.5;          // 8/16 block
  const inner = new THREE.Mesh(new THREE.BoxGeometry(S, S, S), cubeMaterials(skin, 0, 0, false));
  const outer = new THREE.Mesh(new THREE.BoxGeometry(S * 1.0625, S * 1.0625, S * 1.0625), cubeMaterials(skin, 32, 0, true));
  // seated: floor PLAYER_HEAD, skull on the block floor (y −0.5..0). centered: PLAYER_WALL_HEAD,
  // mounted at the block's vertical middle (y −0.25..0.25), matching MC's skull renderer.
  const yOff = seated ? -0.25 : 0;
  inner.position.y = yOff;
  outer.position.y = yOff;
  group.add(inner, outer);
  return group;
}

/** Standalone interactive head viewer in `container` (its own renderer + controls). */
export async function render3DHead(textureUrl, container) {
  const skin = await loadSkin(textureUrl);
  const width = container.clientWidth || 260;
  const height = container.clientHeight || 260;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100);
  camera.position.set(1.6, 1.2, 2.2);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));   // cap before setSize
  renderer.setSize(width, height);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);

  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.06;
  controls.minDistance = 1.4;
  controls.maxDistance = 6;

  scene.add(new THREE.AmbientLight(0xffffff, 1.0));
  scene.add(headCubeMesh(skin));

  let alive = true;
  (function animate() {
    if (!alive) return;
    requestAnimationFrame(animate);
    controls.update();
    renderer.render(scene, camera);
  })();

  return () => {
    alive = false;
    controls.dispose();
    scene.traverse((o) => {
      o.geometry?.dispose();
      const m = o.material;
      const disposeMat = (x) => { if (x && !x.userData?.shared) { x.map?.dispose(); x.dispose(); } };
      if (Array.isArray(m)) m.forEach(disposeMat);
      else disposeMat(m);
    });
    renderer.dispose();
    renderer.forceContextLoss();
    renderer.domElement.remove();
  };
}
