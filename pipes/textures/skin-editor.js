/**
 * Main skin texture editor logic.
 * Manages application state and wires up UI interactions.
 */

import { SkinTexture, renderIsometricHead, render3DHead, update3DHeadTexture } from './head-renderer.js';
import {
    rgbToHsl, hslToRgb,
    calculateAverageHue, extractHistogramData,
    drawHueHistogram, drawBrightnessHistogram,
    findBestHueShift
} from './color-utils.js';
import { rotateHeadInImageData, clearOuterLayerInImageData } from './face-utils.js';

// DOM Elements
const dropZone = document.getElementById('dropZone');
const fileInput = document.getElementById('fileInput');
const originalCanvas = document.getElementById('originalCanvas');
const modifiedCanvas = document.getElementById('modifiedCanvas');
const controls = document.getElementById('controls');
const view3dContainer = document.getElementById('view3dContainer');
const isometricContainer = document.getElementById('isometric-container');
const threeContainer = document.getElementById('three-container');

const originalCtx = originalCanvas.getContext('2d');
const modifiedCtx = modifiedCanvas.getContext('2d');

// Application state
let workingImageData = null;
let pristineImageData = null;
let originalImage = null;
let scaleFactor = 1;
let headRefs = null;
let referenceImageData = null;

// Slider elements
const sliders = {
    hue: document.getElementById('hue'),
    saturation: document.getElementById('saturation'),
    lightness: document.getElementById('lightness'),
    contrast: document.getElementById('contrast'),
    colorizeHue: document.getElementById('colorizeHue'),
    colorizeAmount: document.getElementById('colorizeAmount'),
    targetHue: document.getElementById('targetHue'),
    hueRange: document.getElementById('hueRange'),
    greyAmount: document.getElementById('greyAmount')
};

// Value display elements
const values = {
    hue: document.getElementById('hueValue'),
    saturation: document.getElementById('saturationValue'),
    lightness: document.getElementById('lightnessValue'),
    contrast: document.getElementById('contrastValue'),
    colorizeHue: document.getElementById('colorizeHueValue'),
    colorizeAmount: document.getElementById('colorizeAmountValue'),
    targetHue: document.getElementById('targetHueValue'),
    hueRange: document.getElementById('hueRangeValue'),
    greyAmount: document.getElementById('greyAmountValue')
};

// ============================================================
// Image Loading
// ============================================================

dropZone.addEventListener('click', () => fileInput.click());

dropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropZone.classList.add('dragover');
});

dropZone.addEventListener('dragleave', () => {
    dropZone.classList.remove('dragover');
});

dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropZone.classList.remove('dragover');
    const file = e.dataTransfer.files[0];
    if (file && file.type === 'image/png') {
        loadImage(file);
    }
});

fileInput.addEventListener('change', (e) => {
    const file = e.target.files[0];
    if (file) {
        loadImage(file);
    }
});

function processImage(img) {
    // Validate 64x64 skin format
    if (img.width !== 64 || img.height !== 64) {
        alert('Texture must be 64x64 pixels (Minecraft skin format)');
        return;
    }

    originalImage = img;

    // Calculate scale factor for display (show only top 16 pixels)
    scaleFactor = 4; // 16px * 4 = 64px display height per canvas

    const displayWidth = img.width * scaleFactor;
    const displayHeight = 16 * scaleFactor; // Only top 16 rows

    // Set canvas sizes
    originalCanvas.width = displayWidth;
    originalCanvas.height = displayHeight;
    modifiedCanvas.width = displayWidth;
    modifiedCanvas.height = displayHeight;

    // Draw original (only top 16 rows, scaled)
    originalCtx.imageSmoothingEnabled = false;
    originalCtx.drawImage(img, 0, 0, 64, 16, 0, 0, displayWidth, displayHeight);

    // Store original image data at native resolution (full 64x64)
    const tempCanvas = document.createElement('canvas');
    tempCanvas.width = img.width;
    tempCanvas.height = img.height;
    const tempCtx = tempCanvas.getContext('2d');
    tempCtx.drawImage(img, 0, 0);
    pristineImageData = tempCtx.getImageData(0, 0, img.width, img.height);
    workingImageData = new ImageData(
        new Uint8ClampedArray(pristineImageData.data),
        pristineImageData.width,
        pristineImageData.height
    );

    // Show controls, preview row, 3D view, and color analysis
    controls.classList.add('visible');
    document.getElementById('previewRow').classList.add('visible');
    view3dContainer.classList.add('visible');
    document.getElementById('colorAnalysis').classList.add('visible');

    // Calculate average hue and update hue slider gradient
    const avgHue = calculateAverageHue(pristineImageData);
    updateHueSliderGradient(avgHue);

    // Initialize 3D view if not done
    if (!headRefs) {
        const skinTexture = SkinTexture.fromImage(tempCanvas);
        headRefs = render3DHead(skinTexture, threeContainer);
    }

    applyFilters();
}

function loadImage(file) {
    const reader = new FileReader();
    reader.onload = (e) => {
        const img = new Image();
        img.onload = () => processImage(img);
        img.src = e.target.result;
    };
    reader.readAsDataURL(file);
}

function loadFromUrlInput() {
    const input = document.getElementById('urlInput').value.trim();
    if (!input) return;

    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => processImage(img);
    img.onerror = () => alert('Failed to load image. Check the URL or base64 data.');

    // Determine input type and set src
    if (input.startsWith('data:')) {
        img.src = input;
    } else if (input.startsWith('http://') || input.startsWith('https://')) {
        img.src = input;
    } else {
        // Try to decode as Minecraft texture JSON first
        try {
            const decoded = atob(input);
            const data = JSON.parse(decoded);
            if (data.textures?.SKIN?.url) {
                img.src = data.textures.SKIN.url;
                return;
            }
        } catch (e) {
            // Not Minecraft format, continue to raw base64
        }
        // Fall back to raw image base64
        img.src = 'data:image/png;base64,' + input;
    }
}

document.getElementById('loadUrlBtn').addEventListener('click', loadFromUrlInput);
document.getElementById('urlInput').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') loadFromUrlInput();
});

// ============================================================
// Hue Slider Gradient
// ============================================================

function updateHueSliderGradient(centerHue) {
    const hueSlider = document.getElementById('hue');
    const colors = [];
    for (let offset = -180; offset <= 180; offset += 60) {
        const h = (centerHue + offset + 360) % 360;
        colors.push(`hsl(${h}, 100%, 50%)`);
    }
    hueSlider.style.background = `linear-gradient(to right, ${colors.join(', ')})`;
}

// ============================================================
// Histogram Updates
// ============================================================

function updateHistograms(imageData) {
    const { hueBuckets, brightBuckets } = extractHistogramData(imageData);

    let refHueBuckets = null;
    let refBrightBuckets = null;
    if (referenceImageData) {
        const refData = extractHistogramData(referenceImageData);
        refHueBuckets = refData.hueBuckets;
        refBrightBuckets = refData.brightBuckets;
    }

    drawHueHistogram(document.getElementById('hueHistogram'), hueBuckets, refHueBuckets);
    drawBrightnessHistogram(document.getElementById('brightnessHistogram'), brightBuckets, refBrightBuckets);
}

// ============================================================
// Filter Application
// ============================================================

function getFilteredImageData() {
    if (!workingImageData) return null;

    const hueShift = parseInt(sliders.hue.value);
    const saturationAdjust = parseInt(sliders.saturation.value);
    const lightnessAdjust = parseInt(sliders.lightness.value);
    const contrastAdjust = parseInt(sliders.contrast.value);
    const colorizeHue = parseInt(sliders.colorizeHue.value);
    const colorizeAmount = parseInt(sliders.colorizeAmount.value) / 100;
    const targetHue = parseInt(sliders.targetHue.value);
    const hueRange = parseInt(sliders.hueRange.value);
    const greyAmount = parseInt(sliders.greyAmount.value) / 100;

    const newImageData = new ImageData(
        new Uint8ClampedArray(workingImageData.data),
        workingImageData.width,
        workingImageData.height
    );
    const data = newImageData.data;

    const contrastFactor = (259 * (contrastAdjust + 255)) / (255 * (259 - contrastAdjust));

    for (let i = 0; i < data.length; i += 4) {
        let r = data[i];
        let g = data[i + 1];
        let b = data[i + 2];
        const a = data[i + 3];

        if (a === 0) continue;

        let [h, s, l] = rgbToHsl(r, g, b);
        h = (h + hueShift + 360) % 360;
        s = Math.max(0, Math.min(100, s + saturationAdjust));
        l = Math.max(0, Math.min(100, l + lightnessAdjust));

        if (colorizeAmount > 0 && s > 5) {
            h = h + (colorizeHue - h) * colorizeAmount;
            h = (h + 360) % 360;
        }

        if (greyAmount > 0) {
            let hueDiff = Math.abs(h - targetHue);
            if (hueDiff > 180) hueDiff = 360 - hueDiff;
            if (hueDiff <= hueRange) {
                const influence = 1 - (hueDiff / hueRange);
                s = s * (1 - greyAmount * influence);
            }
        }

        [r, g, b] = hslToRgb(h, s, l);

        if (contrastAdjust !== 0) {
            r = Math.max(0, Math.min(255, Math.round(contrastFactor * (r - 128) + 128)));
            g = Math.max(0, Math.min(255, Math.round(contrastFactor * (g - 128) + 128)));
            b = Math.max(0, Math.min(255, Math.round(contrastFactor * (b - 128) + 128)));
        }

        data[i] = r;
        data[i + 1] = g;
        data[i + 2] = b;
    }

    return newImageData;
}

function applyFilters() {
    if (!workingImageData) return;

    const filteredData = getFilteredImageData();

    // Create temp canvas with filtered data
    const tempCanvas = document.createElement('canvas');
    tempCanvas.width = workingImageData.width;
    tempCanvas.height = workingImageData.height;
    const tempCtx = tempCanvas.getContext('2d');
    tempCtx.putImageData(filteredData, 0, 0);

    // Draw only top 16 rows to display canvas
    modifiedCtx.clearRect(0, 0, modifiedCanvas.width, modifiedCanvas.height);
    modifiedCtx.imageSmoothingEnabled = false;
    modifiedCtx.drawImage(tempCanvas, 0, 0, 64, 16, 0, 0, modifiedCanvas.width, modifiedCanvas.height);

    // Create SkinTexture from filtered canvas (already 64x64)
    const skinTexture = SkinTexture.fromImage(tempCanvas);

    // Update isometric view
    isometricContainer.innerHTML = '';
    renderIsometricHead(skinTexture, isometricContainer, 80);

    // Update 3D view
    if (headRefs) {
        update3DHeadTexture(headRefs, skinTexture);
    }

    // Update histograms
    updateHistograms(filteredData);
}

// ============================================================
// Slider Event Listeners
// ============================================================

Object.keys(sliders).forEach(key => {
    sliders[key].addEventListener('input', () => {
        values[key].textContent = sliders[key].value;
        applyFilters();
    });
});

// Hex input for Colorize Hue
const colorizeHexInput = document.getElementById('colorizeHexInput');
colorizeHexInput.addEventListener('change', () => {
    let input = colorizeHexInput.value.trim();

    if (input.startsWith('#')) {
        input = input.slice(1);
    }

    let r, g, b;

    // Try hex
    if (/^[0-9a-fA-F]{6}$/.test(input)) {
        r = parseInt(input.substr(0, 2), 16);
        g = parseInt(input.substr(2, 2), 16);
        b = parseInt(input.substr(4, 2), 16);
    }
    // Try RGB
    else {
        const rgbMatch = input.match(/(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
        if (rgbMatch) {
            r = parseInt(rgbMatch[1]);
            g = parseInt(rgbMatch[2]);
            b = parseInt(rgbMatch[3]);
        }
    }

    if (r !== undefined && g !== undefined && b !== undefined) {
        const [h] = rgbToHsl(r, g, b);
        sliders.colorizeHue.value = Math.round(h);
        values.colorizeHue.textContent = Math.round(h);
        applyFilters();
    }
});

// ============================================================
// Head Rotation
// ============================================================

function rotateHead(direction) {
    if (!workingImageData) return;
    rotateHeadInImageData(workingImageData, direction);
    applyFilters();
}

document.getElementById('rotateUpBtn').addEventListener('click', () => rotateHead('up'));
document.getElementById('rotateDownBtn').addEventListener('click', () => rotateHead('down'));
document.getElementById('rotateLeftBtn').addEventListener('click', () => rotateHead('left'));
document.getElementById('rotateRightBtn').addEventListener('click', () => rotateHead('right'));

// ============================================================
// Reset Button
// ============================================================

document.getElementById('resetBtn').addEventListener('click', () => {
    // Reset sliders
    Object.keys(sliders).forEach(key => {
        const defaultValue = sliders[key].getAttribute('value');
        sliders[key].value = defaultValue;
        values[key].textContent = defaultValue;
    });

    // Reset texture to original
    if (pristineImageData) {
        workingImageData = new ImageData(
            new Uint8ClampedArray(pristineImageData.data),
            pristineImageData.width,
            pristineImageData.height
        );
    }

    applyFilters();
});

// ============================================================
// Download Button
// ============================================================

document.getElementById('downloadBtn').addEventListener('click', () => {
    if (!workingImageData) return;

    const filteredData = getFilteredImageData();
    const tempCanvas = document.createElement('canvas');
    tempCanvas.width = workingImageData.width;
    tempCanvas.height = workingImageData.height;
    const tempCtx = tempCanvas.getContext('2d');
    tempCtx.putImageData(filteredData, 0, 0);

    const link = document.createElement('a');
    link.download = 'texture-modified.png';
    link.href = tempCanvas.toDataURL('image/png');
    link.click();
});

// ============================================================
// Clear Outer Layer
// ============================================================

function clearOuterLayer() {
    if (!workingImageData) return;
    clearOuterLayerInImageData(workingImageData);
    applyFilters();
}

document.getElementById('clearOuterBtn').addEventListener('click', clearOuterLayer);

// ============================================================
// Reference Image Handling
// ============================================================

const refDropZone = document.getElementById('refDropZone');
const refFileInput = document.getElementById('refFileInput');
const clearRefBtn = document.getElementById('clearRefBtn');
const matchHueBtn = document.getElementById('matchHueBtn');
const histogramLegend = document.getElementById('histogramLegend');

function loadReferenceImage(file) {
    const reader = new FileReader();
    reader.onload = (e) => {
        const img = new Image();
        img.onload = () => {
            const tempCanvas = document.createElement('canvas');
            tempCanvas.width = img.width;
            tempCanvas.height = img.height;
            const tempCtx = tempCanvas.getContext('2d');
            tempCtx.drawImage(img, 0, 0);
            referenceImageData = tempCtx.getImageData(0, 0, img.width, img.height);
            clearRefBtn.style.display = 'inline-block';
            matchHueBtn.style.display = 'inline-block';
            histogramLegend.classList.add('visible');
            if (workingImageData) {
                const filteredData = getFilteredImageData();
                updateHistograms(filteredData);
            }
        };
        img.src = e.target.result;
    };
    reader.readAsDataURL(file);
}

refDropZone.addEventListener('click', () => refFileInput.click());

refDropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    refDropZone.style.borderColor = '#7b68ee';
});

refDropZone.addEventListener('dragleave', () => {
    refDropZone.style.borderColor = '#444';
});

refDropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    refDropZone.style.borderColor = '#444';
    const file = e.dataTransfer.files[0];
    if (file && (file.type === 'image/png' || file.type === 'image/jpeg')) {
        loadReferenceImage(file);
    }
});

refFileInput.addEventListener('change', (e) => {
    const file = e.target.files[0];
    if (file) {
        loadReferenceImage(file);
    }
});

clearRefBtn.addEventListener('click', () => {
    referenceImageData = null;
    clearRefBtn.style.display = 'none';
    matchHueBtn.style.display = 'none';
    histogramLegend.classList.remove('visible');
    document.querySelectorAll('.ref-preset-icon').forEach(i => i.classList.remove('selected'));
    if (workingImageData) {
        const filteredData = getFilteredImageData();
        updateHistograms(filteredData);
    }
});

function loadReferenceFromUrl(url) {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
        const tempCanvas = document.createElement('canvas');
        tempCanvas.width = img.width;
        tempCanvas.height = img.height;
        const tempCtx = tempCanvas.getContext('2d');
        tempCtx.drawImage(img, 0, 0);
        referenceImageData = tempCtx.getImageData(0, 0, img.width, img.height);
        clearRefBtn.style.display = 'inline-block';
        matchHueBtn.style.display = 'inline-block';
        histogramLegend.classList.add('visible');
        if (workingImageData) {
            const filteredData = getFilteredImageData();
            updateHistograms(filteredData);
        }
    };
    img.src = url;
}

matchHueBtn.addEventListener('click', () => {
    if (!pristineImageData || !referenceImageData) return;
    const bestShift = findBestHueShift(pristineImageData, referenceImageData);
    sliders.hue.value = bestShift;
    values.hue.textContent = bestShift;
    applyFilters();
});

// Preset reference image click handlers
const refPresetIcons = document.querySelectorAll('.ref-preset-icon');
refPresetIcons.forEach(icon => {
    icon.addEventListener('click', () => {
        const url = icon.src;
        loadReferenceFromUrl(url);
        refPresetIcons.forEach(i => i.classList.remove('selected'));
        icon.classList.add('selected');
    });
});
