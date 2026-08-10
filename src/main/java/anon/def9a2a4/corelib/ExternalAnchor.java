package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A glue anchor owned by another plugin — a block the engine has no registry type for, but which
 * should still be brushable with the Glue Brush and should carry its glued region across a
 * mechanism ride.
 *
 * <p>The motivating case is a BlockShips ship wheel: a plain {@code PLAYER_HEAD} with no custom-block
 * identity, so {@link Anchors#ANCHOR_IDS} can never match it. Register one of these through
 * {@link CoreLibPlugin#registerAnchorProvider} and the engine wraps it in an internal anchor that
 * stores offsets in the block's own skull PDC — the same storage every engine anchor uses, so
 * capture-at-assembly and rebind-at-landing work unchanged.
 *
 * @see CoreLibPlugin#registerAnchorProvider
 */
public interface ExternalAnchor {

    /**
     * The block whose integer coordinates glue offsets are relative to. Must be a skull
     * ({@code PLAYER_HEAD} / {@code PLAYER_WALL_HEAD}) — that is where the offsets are persisted.
     *
     * <p>Must be the block the player clicked to open the session: the authoring toggle compares the
     * clicked block against this, so a mismatched origin makes "click again to close" silently fail.
     */
    Block originBlock();

    /**
     * Whether the structure is at rest. Authoring is refused while false, and an external anchor that
     * is not at rest is never captured as a nested anchor by another mechanism's move.
     *
     * <p>For a ship: false while assembled.
     */
    boolean isAtRest();

    /** Whether this player may author here at all — ownership, permissions. */
    boolean canAuthor(Player player);

    /**
     * Whether this player may glue one specific world cell. Defaults to the anchor-level answer.
     *
     * <p>Override this for region protection. Gluing modifies no block, so no {@code BlockBreakEvent}
     * fires and no protection plugin can see it on its own — meaning without a per-cell check a player
     * can park an anchor next to someone else's build, glue their wall to it, and take it away.
     */
    default boolean canAuthorCell(Player player, Block cell) { return canAuthor(player); }

    /**
     * Blocks that count as connectivity for authoring, beyond the already-glued cells. Return the
     * owning structure's own cells (a ship's hull) so a player can brush a block anywhere on a large
     * body rather than only cardinally adjacent to the origin.
     *
     * <p>Called on every authoring op, so return a <b>cached</b> set — do not recompute a flood fill
     * per click.
     */
    default Set<Block> connectorBlocks() { return Set.of(); }

    /**
     * Whether the engine may prune landed glue that is no longer cardinally connected to the origin.
     *
     * <p>Defaults to <b>false</b> for external anchors, because an owner plugin's structure is held
     * together by its own rules rather than by glue adjacency. Pruning a ship's glue would delete
     * nearly all of it on the first landing — the glued extras sit on a hull whose ordinary blocks
     * are ship members but are not glued, so almost none of them chain back to the wheel.
     *
     * @see Anchor#prunesOnLanding()
     */
    default boolean prunesOnLanding() { return false; }

    /** Provider that recognises this plugin's anchor blocks. */
    @FunctionalInterface
    interface Provider {
        /** The anchor owning this block, or {@code null} when the block is not one of ours. */
        @Nullable ExternalAnchor anchorFor(Block block);
    }
}
