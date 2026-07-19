package anon.def9a2a4.corelib;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/**
 * One captured banner display riding a mechanism block. BetterBanners flags contribute TWO
 * attachments (front + back, same faceKey); shears/removal semantics pay one item per distinct
 * faceKey, so drops must dedupe on it.
 *
 * @param item           cloned banner item (patterns + large/huge tier PDC intact)
 * @param faceKey        {@code rot{0..15}} (flag) | {@code north/south/east/west/up/down}
 *                       (large/huge) | {@code bed} | {@link #BLOCK_FACE_KEY}
 * @param transformation the captured display transformation, relative to its anchor cell center
 * @param anchorOffset   host-block-local offset of the anchor cell: (0,0,0) for flag/bed/block,
 *                       the face unit vector for large/huge (they anchor in the neighbor cell)
 */
record BannerAttachment(ItemStack item, String faceKey, Transformation transformation,
                        Vector3f anchorOffset) {

    /**
     * faceKey for an attachment synthesized from a vanilla banner BLOCK (standing/wall banner with
     * patterns): rendered in transit and used for pattern-carrying drops, but landing restores the
     * patterns through the block-placement path, never as a display entity.
     */
    static final String BLOCK_FACE_KEY = "block";

    boolean isBlockBanner() { return BLOCK_FACE_KEY.equals(faceKey); }
}
