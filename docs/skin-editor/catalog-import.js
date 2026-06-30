// Provides the list of catalog heads (name + texture URL) for the editor's
// "Import from catalog" dropdown. Data comes from the same data/items.json the
// catalog renders from; we surface every player-head item plus its variants.

import { stripColors } from '../util/render.js';

// Returns [{ label, textureUrl }] for each head item and each of its variants.
// Resolves to [] if the catalog data can't be loaded.
export async function loadCatalogHeads() {
    let items;
    try {
        const res = await fetch('../data/items.json');
        if (!res.ok) throw new Error(`items.json: ${res.status}`);
        items = (await res.json()).items || [];
    } catch (e) {
        console.warn('Skin editor: catalog import unavailable —', e);
        return [];
    }

    const heads = [];
    const seen = new Set();
    const add = (label, textureUrl) => {
        if (!textureUrl || seen.has(label)) return;
        seen.add(label);
        heads.push({ label, textureUrl });
    };

    for (const item of items) {
        const textureUrl = item.icon?.textureUrl;
        if (!textureUrl) continue;                 // not a head item
        const name = stripColors(item.name) || item.fullId || item.id;
        add(name, textureUrl);
        for (const v of item.variants || []) {
            const tag = v.label || v.state;
            if (v.textureUrl && tag) add(`${name} (${tag})`, v.textureUrl);
        }
    }

    heads.sort((a, b) => a.label.localeCompare(b.label));
    return heads;
}
