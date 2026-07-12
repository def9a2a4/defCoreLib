/**
 * Color conversion and histogram utilities for texture editing.
 */

/**
 * Convert RGB to HSL color space.
 * @param {number} r - Red (0-255)
 * @param {number} g - Green (0-255)
 * @param {number} b - Blue (0-255)
 * @returns {[number, number, number]} [hue (0-360), saturation (0-100), lightness (0-100)]
 */
export function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    let h, s, l = (max + min) / 2;

    if (max === min) {
        h = s = 0;
    } else {
        const d = max - min;
        s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
        switch (max) {
            case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
            case g: h = ((b - r) / d + 2) / 6; break;
            case b: h = ((r - g) / d + 4) / 6; break;
        }
    }
    return [h * 360, s * 100, l * 100];
}

/**
 * Convert HSL to RGB color space.
 * @param {number} h - Hue (0-360)
 * @param {number} s - Saturation (0-100)
 * @param {number} l - Lightness (0-100)
 * @returns {[number, number, number]} [red (0-255), green (0-255), blue (0-255)]
 */
export function hslToRgb(h, s, l) {
    h /= 360; s /= 100; l /= 100;
    let r, g, b;

    if (s === 0) {
        r = g = b = l;
    } else {
        const hue2rgb = (p, q, t) => {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            if (t < 1/6) return p + (q - p) * 6 * t;
            if (t < 1/2) return q;
            if (t < 2/3) return p + (q - p) * (2/3 - t) * 6;
            return p;
        };
        const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        const p = 2 * l - q;
        r = hue2rgb(p, q, h + 1/3);
        g = hue2rgb(p, q, h);
        b = hue2rgb(p, q, h - 1/3);
    }
    return [Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)];
}

/**
 * Calculate average hue of non-transparent, non-grey pixels.
 * @param {ImageData} imageData - Image data to analyze
 * @returns {number} Average hue in degrees (0-360)
 */
export function calculateAverageHue(imageData) {
    let hueSum = 0;
    let count = 0;
    const data = imageData.data;

    for (let i = 0; i < data.length; i += 4) {
        const r = data[i], g = data[i+1], b = data[i+2], a = data[i+3];
        if (a === 0) continue; // skip transparent

        const [h, s] = rgbToHsl(r, g, b);
        if (s < 10) continue; // skip greys

        hueSum += h;
        count++;
    }

    return count > 0 ? hueSum / count : 0;
}

/**
 * Extract histogram data from ImageData.
 * @param {ImageData} imageData - Image data to analyze
 * @returns {{hueBuckets: number[], brightBuckets: number[]}} Histogram data
 */
export function extractHistogramData(imageData) {
    const hueBuckets = new Array(36).fill(0);    // 36 x 10° buckets
    const brightBuckets = new Array(30).fill(0); // 30 x ~3.33% buckets
    const data = imageData.data;

    for (let i = 0; i < data.length; i += 4) {
        const r = data[i], g = data[i+1], b = data[i+2], a = data[i+3];
        if (a === 0) continue;

        const [h, s, l] = rgbToHsl(r, g, b);

        // Only count hue for saturated pixels
        if (s > 10) {
            const hueBucket = Math.floor(h / 10) % 36;
            hueBuckets[hueBucket]++;
        }

        const brightBucket = Math.min(29, Math.floor(l / (100/30)));
        brightBuckets[brightBucket]++;
    }

    return { hueBuckets, brightBuckets };
}

/**
 * Draw hue histogram to a canvas.
 * @param {HTMLCanvasElement} canvas - Canvas element to draw on
 * @param {number[]} buckets - Hue bucket counts
 * @param {number[]|null} refBuckets - Reference hue bucket counts (optional)
 */
export function drawHueHistogram(canvas, buckets, refBuckets = null) {
    const ctx = canvas.getContext('2d');
    const numBuckets = 36;
    const barWidth = canvas.width / numBuckets;

    // Find max across both datasets for consistent scaling
    const max = Math.max(...buckets, ...(refBuckets || [0]));

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Draw main histogram (filled bars)
    buckets.forEach((count, i) => {
        const height = max > 0 ? (count / max) * canvas.height : 0;
        const hue = i * 10 + 5; // center of bucket
        ctx.fillStyle = `hsl(${hue}, 100%, 50%)`;
        ctx.fillRect(i * barWidth, canvas.height - height, barWidth - 1, height);
    });

    // Draw reference histogram (outline only)
    if (refBuckets) {
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.8)';
        ctx.lineWidth = 2;
        refBuckets.forEach((count, i) => {
            const height = max > 0 ? (count / max) * canvas.height : 0;
            if (height > 0) {
                ctx.strokeRect(i * barWidth, canvas.height - height, barWidth - 1, height);
            }
        });
    }
}

/**
 * Draw brightness histogram to a canvas.
 * @param {HTMLCanvasElement} canvas - Canvas element to draw on
 * @param {number[]} buckets - Brightness bucket counts
 * @param {number[]|null} refBuckets - Reference brightness bucket counts (optional)
 */
export function drawBrightnessHistogram(canvas, buckets, refBuckets = null) {
    const ctx = canvas.getContext('2d');
    const numBuckets = 30;
    const barWidth = canvas.width / numBuckets;

    // Find max across both datasets for consistent scaling
    const max = Math.max(...buckets, ...(refBuckets || [0]));

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Draw main histogram (filled bars)
    buckets.forEach((count, i) => {
        const height = max > 0 ? (count / max) * canvas.height : 0;
        const lightness = (i / numBuckets) * 100 + (100 / numBuckets / 2); // center of bucket
        ctx.fillStyle = `hsl(0, 0%, ${lightness}%)`;
        ctx.fillRect(i * barWidth, canvas.height - height, barWidth - 1, height);
    });

    // Draw reference histogram (outline only)
    if (refBuckets) {
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.8)';
        ctx.lineWidth = 2;
        refBuckets.forEach((count, i) => {
            const height = max > 0 ? (count / max) * canvas.height : 0;
            if (height > 0) {
                ctx.strokeRect(i * barWidth, canvas.height - height, barWidth - 1, height);
            }
        });
    }
}

/**
 * Find the best hue shift to match a reference image's hue distribution.
 * @param {ImageData} sourceImageData - Source image to shift
 * @param {ImageData} referenceImageData - Reference image to match
 * @returns {number} Best hue shift in degrees (-180 to 180)
 */
export function findBestHueShift(sourceImageData, referenceImageData) {
    // Get reference histogram (fixed target)
    const refData = extractHistogramData(referenceImageData);
    const refHue = refData.hueBuckets;

    // Normalize reference histogram
    const refTotal = refHue.reduce((a, b) => a + b, 0);
    if (refTotal === 0) return 0;
    const refNorm = refHue.map(v => v / refTotal);

    // Get source histogram
    const srcData = extractHistogramData(sourceImageData);
    const srcHue = srcData.hueBuckets;
    const srcTotal = srcHue.reduce((a, b) => a + b, 0);
    if (srcTotal === 0) return 0;
    const srcNorm = srcHue.map(v => v / srcTotal);

    let bestShift = 0;
    let bestScore = Infinity;

    // Try shifts in 10° increments (matching bucket size)
    for (let shiftBuckets = -18; shiftBuckets < 18; shiftBuckets++) {
        // Shift source histogram by this many buckets
        const shiftedHue = new Array(36).fill(0);
        for (let i = 0; i < 36; i++) {
            const newIdx = ((i + shiftBuckets) % 36 + 36) % 36;
            shiftedHue[newIdx] = srcNorm[i];
        }

        // Calculate difference (sum of squared differences)
        let score = 0;
        for (let i = 0; i < 36; i++) {
            score += Math.pow(refNorm[i] - shiftedHue[i], 2);
        }

        if (score < bestScore) {
            bestScore = score;
            bestShift = shiftBuckets * 10; // Convert buckets to degrees
        }
    }

    return bestShift;
}
