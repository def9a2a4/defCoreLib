import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const CORS_PROXY = 'https://corsproxy.io/?';

/**
 * Decode base64 texture data to get the Minecraft texture URL
 */
function getTextureUrl(base64) {
  try {
    const json = JSON.parse(atob(base64));
    return json.textures.SKIN.url;
  } catch (e) {
    console.error('Failed to decode texture:', e);
    return null;
  }
}

/**
 * Load a skin texture from base64-encoded texture data
 * @param {string} base64 - Base64 encoded Minecraft texture JSON
 * @returns {Promise<HTMLImageElement>} - Loaded image element
 */
export function loadSkinTexture(base64) {
  return new Promise((resolve, reject) => {
    const textureUrl = getTextureUrl(base64);
    if (!textureUrl) {
      reject(new Error('Invalid texture data'));
      return;
    }

    const proxiedUrl = CORS_PROXY + encodeURIComponent(textureUrl);
    const img = new Image();
    img.crossOrigin = 'anonymous';

    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Failed to load texture'));

    img.src = proxiedUrl;
  });
}

/**
 * Extract a face region from the skin image
 */
function extractFace(skin, x, y, w, h, rotate180 = false) {
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  if (rotate180) {
    ctx.translate(w, h);
    ctx.rotate(Math.PI);
  }
  ctx.drawImage(skin, x, y, w, h, 0, 0, w, h);
  return canvas;
}

/**
 * Render an isometric view of a Minecraft head
 * @param {string} base64 - Base64 encoded Minecraft texture JSON
 * @param {HTMLElement} container - Container element to render into
 * @param {number} size - Size of the rendered head in pixels (default 112, which gives ~256x300 canvas)
 */
export async function renderIsometricHead(base64, container, size = 112) {
  const skin = await loadSkinTexture(base64);

  // Scale factor is size / 8 (8 is the pixel dimension of a face)
  const scale = size / 8;

  // Canvas dimensions proportional to original 256x300 at scale=14
  // Original: 256 = 8*14*~2.3, 300 = 8*14*~2.7
  const canvas = document.createElement('canvas');
  canvas.width = Math.ceil(size * 2.3);
  canvas.height = Math.ceil(size * 2.7);
  canvas.style.imageRendering = 'pixelated';
  container.appendChild(canvas);

  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;

  // Extract inner faces
  const topInnerRaw = extractFace(skin, 8, 0, 8, 8);
  // Rotate top face 180 degrees
  const topInner = document.createElement('canvas');
  topInner.width = 8;
  topInner.height = 8;
  const topCtx = topInner.getContext('2d');
  topCtx.translate(4, 4);
  topCtx.rotate(Math.PI);
  topCtx.drawImage(topInnerRaw, -4, -4);

  const frontInner = extractFace(skin, 8, 8, 8, 8);
  const rightInner = extractFace(skin, 0, 8, 8, 8);

  // Extract outer faces
  const topOuter = extractFace(skin, 40, 0, 8, 8);
  const frontOuter = extractFace(skin, 40, 8, 8, 8);
  const rightOuter = extractFace(skin, 32, 8, 8, 8);

  // Clear canvas with transparent background
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  const centerX = canvas.width / 2;
  const centerY = canvas.height / 2 + size * 0.54; // proportional offset
  const faceSize = 8 * scale;
  const outerScale = scale * 1.1;

  // === INNER LAYER ===
  // Left side visually (right face of head)
  ctx.save();
  ctx.translate(centerX - faceSize, centerY - faceSize);
  ctx.transform(1, 0.5, 0, 1, 0, 0);
  ctx.scale(scale, scale * 1.15);
  ctx.drawImage(rightInner, 0, 0);
  ctx.restore();

  // Right side visually (front face of head)
  ctx.save();
  ctx.translate(centerX, centerY - faceSize / 2);
  ctx.transform(1, -0.5, 0, 1, 0, 0);
  ctx.scale(scale, scale * 1.15);
  ctx.drawImage(frontInner, 0, 0);
  ctx.restore();

  // Top face
  ctx.save();
  ctx.translate(centerX, centerY - faceSize / 2);
  ctx.transform(1, -0.5, -1, -0.5, 0, 0);
  ctx.scale(scale, scale);
  ctx.drawImage(topInner, 0, 0);
  ctx.restore();

  // === OUTER LAYER ===
  const outerFaceSize = 8 * outerScale;

  // Rotate outer top face 180 degrees
  const topOuterRotated = document.createElement('canvas');
  topOuterRotated.width = 8;
  topOuterRotated.height = 8;
  const topOuterCtx = topOuterRotated.getContext('2d');
  topOuterCtx.translate(4, 4);
  topOuterCtx.rotate(Math.PI);
  topOuterCtx.drawImage(topOuter, -4, -4);

  // Left side visually (right face outer)
  ctx.save();
  ctx.translate(centerX - outerFaceSize, centerY - outerFaceSize);
  ctx.transform(1, 0.5, 0, 1, 0, 0);
  ctx.scale(outerScale, outerScale * 1.15);
  ctx.drawImage(rightOuter, 0, 0);
  ctx.restore();

  // Right side visually (front face outer)
  ctx.save();
  ctx.translate(centerX, centerY - outerFaceSize / 2);
  ctx.transform(1, -0.5, 0, 1, 0, 0);
  ctx.scale(outerScale, outerScale * 1.15);
  ctx.drawImage(frontOuter, 0, 0);
  ctx.restore();

  // Top face outer
  ctx.save();
  ctx.translate(centerX, centerY - outerFaceSize / 2);
  ctx.transform(1, -0.5, -1, -0.5, 0, 0);
  ctx.scale(outerScale, outerScale);
  ctx.drawImage(topOuterRotated, 0, 0);
  ctx.restore();
}

/**
 * Render an interactive 3D view of a Minecraft head
 * @param {string} base64 - Base64 encoded Minecraft texture JSON
 * @param {HTMLElement} container - Container element to render into
 */
export async function render3DHead(base64, container) {
  const skin = await loadSkinTexture(base64);

  const width = container.clientWidth || 400;
  const height = container.clientHeight || 400;

  // Scene setup
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0f0f23);

  const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 1000);
  camera.position.set(1.5, 1.2, -2.2);

  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setSize(width, height);
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);

  // Controls
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.05;
  controls.minDistance = 1.5;
  controls.maxDistance = 5;

  // Lighting
  const ambientLight = new THREE.AmbientLight(0xffffff, 1.0);
  scene.add(ambientLight);

  function createFaceMaterial(x, y, w, h, transparent = false, rotate180 = false) {
    const faceCanvas = extractFace(skin, x, y, w, h, rotate180);

    const faceTexture = new THREE.CanvasTexture(faceCanvas);
    faceTexture.magFilter = THREE.NearestFilter;
    faceTexture.minFilter = THREE.NearestFilter;
    faceTexture.colorSpace = THREE.SRGBColorSpace;

    return new THREE.MeshBasicMaterial({
      map: faceTexture,
      transparent: transparent,
      alphaTest: transparent ? 0.1 : 0,
      side: transparent ? THREE.DoubleSide : THREE.FrontSide
    });
  }

  // Inner head cube (base layer)
  const innerMaterials = [
    createFaceMaterial(0, 8, 8, 8),              // right (+x)
    createFaceMaterial(16, 8, 8, 8),             // left (-x)
    createFaceMaterial(8, 0, 8, 8, false, true), // top (+y) - rotated 180
    createFaceMaterial(16, 0, 8, 8),             // bottom (-y)
    createFaceMaterial(24, 8, 8, 8),             // front (+z)
    createFaceMaterial(8, 8, 8, 8),              // back (-z)
  ];

  const innerGeometry = new THREE.BoxGeometry(1, 1, 1);
  const innerHead = new THREE.Mesh(innerGeometry, innerMaterials);
  scene.add(innerHead);

  // Outer head cube (overlay layer) - slightly larger
  const outerMaterials = [
    createFaceMaterial(32, 8, 8, 8, true),             // right (+x)
    createFaceMaterial(48, 8, 8, 8, true),             // left (-x)
    createFaceMaterial(40, 0, 8, 8, true, true),       // top (+y) - rotated 180
    createFaceMaterial(48, 0, 8, 8, true),             // bottom (-y)
    createFaceMaterial(56, 8, 8, 8, true),             // front (+z)
    createFaceMaterial(40, 8, 8, 8, true),             // back (-z)
  ];

  const outerGeometry = new THREE.BoxGeometry(1.125, 1.125, 1.125);
  const outerHead = new THREE.Mesh(outerGeometry, outerMaterials);
  scene.add(outerHead);

  // Animation loop
  function animate() {
    requestAnimationFrame(animate);
    controls.update();
    renderer.render(scene, camera);
  }
  animate();

  // Handle resize
  const resizeHandler = () => {
    const newWidth = container.clientWidth;
    const newHeight = container.clientHeight;
    camera.aspect = newWidth / newHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(newWidth, newHeight);
  };
  window.addEventListener('resize', resizeHandler);
}

/**
 * Get a cached isometric head image, rendering and caching if needed.
 * Always renders at 64px and returns a ~147x173 PNG. Callers should resize via CSS/img dimensions.
 * @param {string} base64 - Base64 encoded Minecraft texture JSON
 * @returns {Promise<string>} - Data URL of the rendered head (PNG)
 */
export async function getCachedIsometricHead(base64) {
  const cacheKey = base64.slice(-32);

  const cached = localStorage.getItem(cacheKey);
  if (cached) {
    return cached;
  }

  const tempContainer = document.createElement('div');
  await renderIsometricHead(base64, tempContainer, 64);

  const canvas = tempContainer.querySelector('canvas');
  const dataUrl = canvas.toDataURL('image/png');

  try {
    localStorage.setItem(cacheKey, dataUrl);
  } catch (e) {
    console.warn('Could not cache head image:', e);
  }

  return dataUrl;
}
