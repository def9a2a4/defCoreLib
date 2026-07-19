package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player commits glue with the Glue Brush — a single-block bond or a cuboid fill that
 * added at least one block. Not fired for derived bonds (sticky-block auto-glue): the event means
 * "the player authored glue", not "a bond exists".
 *
 * <p>Purely informational (not cancellable). {@link #getAnchorBlock()} is the session's anchor
 * origin block at commit time.
 */
public class GlueAppliedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Block anchorBlock;
    private final int addedCount;

    public GlueAppliedEvent(Player player, Block anchorBlock, int addedCount) {
        super(player);
        this.anchorBlock = anchorBlock;
        this.addedCount = addedCount;
    }

    /** The glue session's anchor origin block (e.g. the rotator/cart hardware the bond hangs off). */
    public Block getAnchorBlock() { return anchorBlock; }

    /** Blocks added by this commit — 1 for a single-block bond, the fill size for a cuboid. */
    public int getAddedCount() { return addedCount; }

    /** Cheap guard so fire sites cost nothing when no plugin listens. */
    public static boolean hasListeners() { return HANDLERS.getRegisteredListeners().length > 0; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
