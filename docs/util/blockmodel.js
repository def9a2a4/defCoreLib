// Builds a THREE.Group from a flattened Minecraft block/item model (produced at build
// time by scripts/generate_catalog.py into ./assets/models/<id>.json, with textures in
// ./assets/textures/). This is the small "model → meshes" step that libraries like
// @xmcl/model / three-mcmodel perform; the harder parent/texture resolution is already
// done at build time, so this stays dependency-light and fully under our control.
//
// Coordinates: Minecraft model space is 0..16 per block; we map a block to a 1×1×1 cube
// centered at the origin so a display entity's transform (in block units) composes cleanly.

import * as THREE from 'three';

const modelCache = new Map();    // id -> Promise<model json>
const texCache = new Map();      // path -> THREE.Texture
const texReady = new Map();      // path -> Promise (resolves once the image has loaded/errored)

const TEX_LOADER = new THREE.TextureLoader();

function loadTexture(path) {
  if (texCache.has(path)) return texCache.get(path);
  // TextureLoader.load() returns the Texture synchronously but uploads pixels asynchronously; a live
  // viewer's RAF loop redraws until they arrive, but a one-shot snapshot (thumbnailDataURL) must wait.
  // Track a per-texture promise (resolved on load OR error so a missing PNG never hangs the await).
  let settle;
  texReady.set(path, new Promise((res) => { settle = res; }));
  const tex = TEX_LOADER.load(`./assets/textures/${path}.png`, () => settle(), undefined, () => settle());
  tex.magFilter = THREE.NearestFilter;
  tex.minFilter = THREE.NearestFilter;
  tex.colorSpace = THREE.SRGBColorSpace;
  texCache.set(path, tex);
  return tex;
}

/** Resolve once every texture requested so far has finished loading (or failed). Lets a single-frame
 *  snapshot render with textures present instead of capturing untextured (blank) geometry. */
export async function texturesSettled() {
  await Promise.allSettled([...texReady.values()]);
}

function loadModel(id) {
  if (modelCache.has(id)) return modelCache.get(id);
  const p = fetch(`./assets/models/${id}.json`).then((r) => {
    if (!r.ok) throw new Error(`model ${id}: ${r.status}`);
    return r.json();
  });
  modelCache.set(id, p);
  return p;
}

// THREE.BoxGeometry face groups, in order: +x, -x, +y, -y, +z, -z.
const FACE_DIRS = ['east', 'west', 'up', 'down', 'south', 'north'];
const TRANSPARENT = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0, depthWrite: false });
TRANSPARENT.userData.shared = true;   // module singleton — teardown must NOT dispose it

// Rewrite the 4 uvs of one BoxGeometry face from a Minecraft [x1,y1,x2,y2] rect (0..16,
// origin top-left). THREE face vertices are ordered top-left, top-right, bottom-left, bottom-right.
function setFaceUV(geo, faceIndex, uv) {
  const [x1, y1, x2, y2] = uv;
  const u1 = x1 / 16, u2 = x2 / 16, v1 = 1 - y1 / 16, v2 = 1 - y2 / 16;
  const attr = geo.attributes.uv;
  const o = faceIndex * 4;
  attr.setXY(o + 0, u1, v1);
  attr.setXY(o + 1, u2, v1);
  attr.setXY(o + 2, u1, v2);
  attr.setXY(o + 3, u2, v2);
  attr.needsUpdate = true;
}

function buildElement(el, textures, centered) {
  const from = el.from, to = el.to;
  const size = [(to[0] - from[0]) / 16, (to[1] - from[1]) / 16, (to[2] - from[2]) / 16];
  // Minecraft model space is corner-origin [0,1]³. ItemDisplays read back centered, so we shift the
  // box by -0.5; BlockDisplays read back corner-origin (matrix already in [0,1]³), so we don't.
  const off = centered ? 0.5 : 0;
  const center = [
    (from[0] + to[0]) / 2 / 16 - off,
    (from[1] + to[1]) / 2 / 16 - off,
    (from[2] + to[2]) / 2 / 16 - off,
  ];
  const geo = new THREE.BoxGeometry(size[0], size[1], size[2]);

  const materials = FACE_DIRS.map((dir, i) => {
    const face = el.faces?.[dir];
    if (!face) return TRANSPARENT;
    let texRef = face.texture || '';
    if (texRef.startsWith('#')) texRef = textures[texRef.slice(1)];
    if (!texRef) return TRANSPARENT;
    setFaceUV(geo, i, face.uv || [0, 0, 16, 16]);
    return new THREE.MeshBasicMaterial({ map: loadTexture(texRef), side: THREE.FrontSide });
  });

  const mesh = new THREE.Mesh(geo, materials);
  mesh.position.set(center[0], center[1], center[2]);

  // Optional Minecraft element rotation: { origin:[x,y,z] (0..16), axis:"x"|"y"|"z", angle:deg }.
  // Wrap the mesh in a pivot group at the origin and rotate the group.
  if (el.rotation) {
    const r = el.rotation, o = r.origin || [8, 8, 8];
    const piv = [o[0] / 16 - off, o[1] / 16 - off, o[2] / 16 - off];
    const g = new THREE.Group();
    g.position.set(piv[0], piv[1], piv[2]);
    mesh.position.set(center[0] - piv[0], center[1] - piv[1], center[2] - piv[2]);
    g.rotation[r.axis || 'x'] = THREE.MathUtils.degToRad(r.angle || 0);
    g.add(mesh);
    return g;
  }
  return mesh;
}

/** Build a THREE.Group for a vanilla model id. `centered` (default true) centers the 1-block cube at
 *  the origin (ItemDisplay space); `centered:false` keeps it corner-origin [0,1]³ (BlockDisplay space). */
export async function buildBlockMesh(id, { centered = true } = {}) {
  const model = await loadModel(id);
  const group = new THREE.Group();
  for (const el of model.elements || []) group.add(buildElement(el, model.textures || {}, centered));
  return group;
}

/** Fallback when a model can't be resolved: a plain 1-block cube tinted `color`. Honors the same
 *  origin convention as buildBlockMesh so missing-model block displays aren't half a block off. */
export function fallbackBox(color = 0xcccccc, { centered = true } = {}) {
  const group = new THREE.Group();
  const mesh = new THREE.Mesh(
    new THREE.BoxGeometry(1, 1, 1),
    new THREE.MeshBasicMaterial({ color }),
  );
  if (!centered) mesh.position.set(0.5, 0.5, 0.5);
  group.add(mesh);
  return group;
}
