package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Owner of a glued block selection. Abstracts a block-with-PDC (skull hinge) from a future
 * entity-with-PDC (minecart, added in Block E). Glue offsets are stored relative to
 * {@link #originBlock()} and persisted in the anchor's PersistentDataContainer.
 */
interface Anchor {

    World world();

    /** The block whose integer coords glue offsets are relative to. */
    Block originBlock();

    /** Assembly pivot (block-centred), distinct from the origin. */
    Location pivot();

    /** Whether the structure is at rest (not assembled/swinging) — authoring is refused otherwise. */
    boolean isAtRest();

    /** Read the stored offset array, or {@code null} if none. */
    @Nullable int[] readOffsets();

    /** Persist the offset array (skull impls MUST call {@code skull.update()}). */
    void writeOffsets(int[] offsets);

    /** Remove the stored offsets. */
    void clearOffsets();

    /** Stable identity for session maps / one-editor locks. */
    Object identityKey();
}
