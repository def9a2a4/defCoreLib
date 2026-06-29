// Renders a Minecraft player-head texture as a small isometric icon on a 2D canvas.
// Ported from HeadSmith's head-renderer.js (the canvas-only isometric path — no Three.js).
// Skins are loaded from the locally-vendored docs/assets/skins/<hash>.png (same-origin,
// so no CORS proxy is needed); the <hash> is the last path segment of the texture URL.

const skinCache = new Map();   // localPath -> Promise<HTMLImageElement>

// textures.minecraft.net/texture/<hash> -> ./assets/skins/<hash>.png
function localSkinPath(textureUrl) {
  return `./assets/skins/${textureUrl.split('/').pop()}.png`;
}

function loadSkin(textureUrl) {
  const src = localSkinPath(textureUrl);
  if (skinCache.has(src)) return skinCache.get(src);
  const p = new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Missing skin asset: ' + src));
    img.src = src;
  });
  skinCache.set(src, p);
  return p;
}

function extractFace(skin, x, y, w, h) {
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(skin, x, y, w, h, 0, 0, w, h);
  return canvas;
}

function rotate180(face) {
  const c = document.createElement('canvas');
  c.width = 8;
  c.height = 8;
  const ctx = c.getContext('2d');
  ctx.translate(4, 4);
  ctx.rotate(Math.PI);
  ctx.drawImage(face, -4, -4);
  return c;
}

// Draw an isometric head into a freshly created canvas and return it.
function drawIsometric(skin, size) {
  const scale = size / 8;
  const canvas = document.createElement('canvas');
  canvas.width = Math.ceil(size * 2.3);
  canvas.height = Math.ceil(size * 2.7);
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;

  const frontInner = extractFace(skin, 8, 8, 8, 8);
  const rightInner = extractFace(skin, 0, 8, 8, 8);
  const topInner = rotate180(extractFace(skin, 8, 0, 8, 8));

  const frontOuter = extractFace(skin, 40, 8, 8, 8);
  const rightOuter = extractFace(skin, 32, 8, 8, 8);
  const topOuter = rotate180(extractFace(skin, 40, 0, 8, 8));

  const centerX = canvas.width / 2;
  const centerY = canvas.height / 2 + size * 0.54;
  const faceSize = 8 * scale;
  const outerScale = scale * 1.1;
  const outerFaceSize = 8 * outerScale;

  const drawLayer = (right, front, top, s, fSize) => {
    ctx.save();
    ctx.translate(centerX - fSize, centerY - fSize);
    ctx.transform(1, 0.5, 0, 1, 0, 0);
    ctx.scale(s, s * 1.15);
    ctx.drawImage(right, 0, 0);
    ctx.restore();

    ctx.save();
    ctx.translate(centerX, centerY - fSize / 2);
    ctx.transform(1, -0.5, 0, 1, 0, 0);
    ctx.scale(s, s * 1.15);
    ctx.drawImage(front, 0, 0);
    ctx.restore();

    ctx.save();
    ctx.translate(centerX, centerY - fSize / 2);
    ctx.transform(1, -0.5, -1, -0.5, 0, 0);
    ctx.scale(s, s);
    ctx.drawImage(top, 0, 0);
    ctx.restore();
  };

  drawLayer(rightInner, frontInner, topInner, scale, faceSize);
  drawLayer(rightOuter, frontOuter, topOuter, outerScale, outerFaceSize);

  return canvas;
}

/**
 * Resolve a texture URL to a cached PNG data-URL of the isometric head.
 * Returns null if the texture can't be loaded.
 */
export async function headDataUrl(textureUrl) {
  if (!textureUrl) return null;
  const cacheKey = 'defcorelib-head:' + textureUrl.slice(-32);
  const cached = localStorage.getItem(cacheKey);
  if (cached) return cached;

  try {
    const skin = await loadSkin(textureUrl);
    const dataUrl = drawIsometric(skin, 48).toDataURL('image/png');
    try { localStorage.setItem(cacheKey, dataUrl); } catch { /* quota: ignore */ }
    return dataUrl;
  } catch (e) {
    console.warn(e);
    return null;
  }
}
