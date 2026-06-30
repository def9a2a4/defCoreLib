/**
 * Face/texture manipulation utilities for Minecraft skin head editing.
 */

/**
 * Head face coordinates in the skin texture.
 * Format: [inner_x, inner_y, outer_x, outer_y]
 */
export const FACE_COORDS = {
    top:    [8, 0, 40, 0],
    bottom: [16, 0, 48, 0],
    right:  [0, 8, 32, 8],
    front:  [8, 8, 40, 8],
    left:   [16, 8, 48, 8],
    back:   [24, 8, 56, 8]
};

/**
 * Extract an 8x8 face from ImageData.
 * @param {ImageData} imageData - Source image data
 * @param {number} x - X coordinate of face top-left
 * @param {number} y - Y coordinate of face top-left
 * @returns {Uint8ClampedArray} Face pixel data (8x8x4 bytes)
 */
export function extractFace(imageData, x, y) {
    const face = new Uint8ClampedArray(8 * 8 * 4);
    for (let fy = 0; fy < 8; fy++) {
        for (let fx = 0; fx < 8; fx++) {
            const srcIdx = ((y + fy) * imageData.width + (x + fx)) * 4;
            const dstIdx = (fy * 8 + fx) * 4;
            face[dstIdx] = imageData.data[srcIdx];
            face[dstIdx + 1] = imageData.data[srcIdx + 1];
            face[dstIdx + 2] = imageData.data[srcIdx + 2];
            face[dstIdx + 3] = imageData.data[srcIdx + 3];
        }
    }
    return face;
}

/**
 * Place an 8x8 face into ImageData.
 * @param {ImageData} imageData - Target image data
 * @param {number} x - X coordinate of face top-left
 * @param {number} y - Y coordinate of face top-left
 * @param {Uint8ClampedArray} face - Face pixel data (8x8x4 bytes)
 */
export function placeFace(imageData, x, y, face) {
    for (let fy = 0; fy < 8; fy++) {
        for (let fx = 0; fx < 8; fx++) {
            const dstIdx = ((y + fy) * imageData.width + (x + fx)) * 4;
            const srcIdx = (fy * 8 + fx) * 4;
            imageData.data[dstIdx] = face[srcIdx];
            imageData.data[dstIdx + 1] = face[srcIdx + 1];
            imageData.data[dstIdx + 2] = face[srcIdx + 2];
            imageData.data[dstIdx + 3] = face[srcIdx + 3];
        }
    }
}

/**
 * Rotate an 8x8 face 90 degrees clockwise.
 * @param {Uint8ClampedArray} face - Face pixel data
 * @returns {Uint8ClampedArray} Rotated face pixel data
 */
export function rotateFace90CW(face) {
    const rotated = new Uint8ClampedArray(8 * 8 * 4);
    for (let y = 0; y < 8; y++) {
        for (let x = 0; x < 8; x++) {
            const srcIdx = (y * 8 + x) * 4;
            const dstIdx = (x * 8 + (7 - y)) * 4;
            rotated[dstIdx] = face[srcIdx];
            rotated[dstIdx + 1] = face[srcIdx + 1];
            rotated[dstIdx + 2] = face[srcIdx + 2];
            rotated[dstIdx + 3] = face[srcIdx + 3];
        }
    }
    return rotated;
}

/**
 * Rotate an 8x8 face 90 degrees counter-clockwise.
 * @param {Uint8ClampedArray} face - Face pixel data
 * @returns {Uint8ClampedArray} Rotated face pixel data
 */
export function rotateFace90CCW(face) {
    const rotated = new Uint8ClampedArray(8 * 8 * 4);
    for (let y = 0; y < 8; y++) {
        for (let x = 0; x < 8; x++) {
            const srcIdx = (y * 8 + x) * 4;
            const dstIdx = ((7 - x) * 8 + y) * 4;
            rotated[dstIdx] = face[srcIdx];
            rotated[dstIdx + 1] = face[srcIdx + 1];
            rotated[dstIdx + 2] = face[srcIdx + 2];
            rotated[dstIdx + 3] = face[srcIdx + 3];
        }
    }
    return rotated;
}

/**
 * Rotate an 8x8 face 180 degrees.
 * @param {Uint8ClampedArray} face - Face pixel data
 * @returns {Uint8ClampedArray} Rotated face pixel data
 */
export function rotateFace180(face) {
    const rotated = new Uint8ClampedArray(8 * 8 * 4);
    for (let y = 0; y < 8; y++) {
        for (let x = 0; x < 8; x++) {
            const srcIdx = (y * 8 + x) * 4;
            const dstIdx = ((7 - y) * 8 + (7 - x)) * 4;
            rotated[dstIdx] = face[srcIdx];
            rotated[dstIdx + 1] = face[srcIdx + 1];
            rotated[dstIdx + 2] = face[srcIdx + 2];
            rotated[dstIdx + 3] = face[srcIdx + 3];
        }
    }
    return rotated;
}

/**
 * Rotate head faces within ImageData.
 * @param {ImageData} imageData - Image data to modify (mutated in place)
 * @param {'up'|'down'|'left'|'right'} direction - Rotation direction
 */
export function rotateHeadInImageData(imageData, direction) {
    // Extract all faces (inner and outer)
    const faces = {};
    for (const [name, coords] of Object.entries(FACE_COORDS)) {
        faces[name] = {
            inner: extractFace(imageData, coords[0], coords[1]),
            outer: extractFace(imageData, coords[2], coords[3])
        };
    }

    // Rearrange faces based on direction
    let newFaces;
    if (direction === 'up') {
        // All face transitions need 180° for correct orientation
        newFaces = {
            top: { inner: rotateFace180(faces.front.inner), outer: rotateFace180(faces.front.outer) },
            back: { inner: rotateFace180(faces.top.inner), outer: rotateFace180(faces.top.outer) },
            bottom: { inner: rotateFace180(faces.back.inner), outer: rotateFace180(faces.back.outer) },
            front: { inner: rotateFace180(faces.bottom.inner), outer: rotateFace180(faces.bottom.outer) },
            left: { inner: rotateFace90CW(faces.left.inner), outer: rotateFace90CW(faces.left.outer) },
            right: { inner: rotateFace90CCW(faces.right.inner), outer: rotateFace90CCW(faces.right.outer) }
        };
    } else if (direction === 'down') {
        // Inverse of UP
        newFaces = {
            bottom: { inner: rotateFace180(faces.front.inner), outer: rotateFace180(faces.front.outer) },
            back: { inner: rotateFace180(faces.bottom.inner), outer: rotateFace180(faces.bottom.outer) },
            top: { inner: rotateFace180(faces.back.inner), outer: rotateFace180(faces.back.outer) },
            front: { inner: rotateFace180(faces.top.inner), outer: rotateFace180(faces.top.outer) },
            left: { inner: rotateFace90CCW(faces.left.inner), outer: rotateFace90CCW(faces.left.outer) },
            right: { inner: rotateFace90CW(faces.right.inner), outer: rotateFace90CW(faces.right.outer) }
        };
    } else if (direction === 'left') {
        newFaces = {
            left: { inner: faces.front.inner, outer: faces.front.outer },
            back: { inner: faces.left.inner, outer: faces.left.outer },
            right: { inner: faces.back.inner, outer: faces.back.outer },
            front: { inner: faces.right.inner, outer: faces.right.outer },
            top: { inner: rotateFace90CW(faces.top.inner), outer: rotateFace90CW(faces.top.outer) },
            bottom: { inner: rotateFace90CCW(faces.bottom.inner), outer: rotateFace90CCW(faces.bottom.outer) }
        };
    } else if (direction === 'right') {
        newFaces = {
            right: { inner: faces.front.inner, outer: faces.front.outer },
            back: { inner: faces.right.inner, outer: faces.right.outer },
            left: { inner: faces.back.inner, outer: faces.back.outer },
            front: { inner: faces.left.inner, outer: faces.left.outer },
            top: { inner: rotateFace90CCW(faces.top.inner), outer: rotateFace90CCW(faces.top.outer) },
            bottom: { inner: rotateFace90CW(faces.bottom.inner), outer: rotateFace90CW(faces.bottom.outer) }
        };
    }

    // Place faces back
    for (const [name, coords] of Object.entries(FACE_COORDS)) {
        placeFace(imageData, coords[0], coords[1], newFaces[name].inner);
        placeFace(imageData, coords[2], coords[3], newFaces[name].outer);
    }
}

/**
 * Clear the outer layer head region in ImageData.
 * @param {ImageData} imageData - Image data to modify (mutated in place)
 */
export function clearOuterLayerInImageData(imageData) {
    const data = imageData.data;
    const width = imageData.width;

    for (let y = 0; y < 16; y++) {
        for (let x = 32; x < 64; x++) {
            const i = (y * width + x) * 4;
            data[i + 3] = 0; // Set alpha to 0
        }
    }
}
