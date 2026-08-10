package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;

import java.util.Set;

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

    /**
     * Whether {@code player} may author this anchor's glue. Consulted at EVERY mutation (and by the
     * outline renderer), not just at session open — a session lives for minutes, and the outline
     * enumerates the structure to whoever holds the brush. The engine cannot answer ownership or
     * region protection for a foreign plugin's anchor, so that plugin overrides this.
     */
    default boolean canAuthor(Player player) { return true; }

    /**
     * Whether {@link GlueManager#rebindLanded} may prune landed offsets that are no longer cardinally
     * connected back to the origin.
     *
     * <p>True for every engine-owned anchor: a door or drawbridge's glued region IS a connected body,
     * so a disconnected survivor means something failed to place and the glue should self-correct.
     *
     * <p>An owner plugin whose structure is held together by its OWN rules returns false. A ship is the
     * motivating case: its glued cells are scattered extras on a hull whose ordinary blocks are ship
     * members but are not glued, so origin-seeded pruning would delete nearly all of them on the first
     * landing — silently, since the blocks themselves land fine. The {@code placed}-membership filter
     * still runs and still does the real safety work.
     */
    default boolean prunesOnLanding() { return true; }

    /**
     * Extra origin-relative cells that count as connectivity for authoring, on top of the glued cells
     * and the derived sticky closure. Lets an owner plugin declare "my whole structure is a connector"
     * — a ship hull, say — so a player can brush a block on the far side of a large body instead of
     * only next to the origin.
     *
     * <p>Called per authoring op, so implementations MUST return a cached set rather than recomputing
     * (a ship's would come from a flood fill).
     */
    default Set<Vector3i> extraConnectors() { return Set.of(); }
}
