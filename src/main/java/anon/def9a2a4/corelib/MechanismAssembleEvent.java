package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired once when a {@link Mechanism} is assembled (blocks → displays + colliders) at the single
 * choke point {@code MechanismRegistry.assembleCore}. Redstone/interaction-driven, so it carries no
 * player — listeners that need one should look up nearby players around {@link #getPivot()}.
 *
 * <p>Purely informational (not cancellable). {@link #getType()} is the assembler's type id
 * (e.g. {@code mech:rotator}, {@code mech:mechanism_minecart}, {@code demo:door}). For a rotator,
 * {@link #isVerticalAxis()} distinguishes a floor door (vertical/Y axis) from a wall drawbridge
 * (horizontal axis).
 */
public class MechanismAssembleEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Mechanism mechanism;
    private final String type;
    private final Location pivot;
    private final int blockCount;
    private final boolean verticalAxis;

    public MechanismAssembleEvent(Mechanism mechanism, String type, Location pivot,
                                  int blockCount, boolean verticalAxis) {
        this.mechanism = mechanism;
        this.type = type;
        this.pivot = pivot;
        this.blockCount = blockCount;
        this.verticalAxis = verticalAxis;
    }

    public Mechanism getMechanism() { return mechanism; }

    /** Assembler type id — e.g. {@code mech:rotator}, {@code mech:mechanism_minecart}. */
    public String getType() { return type; }

    /** Snapped pivot (block-centered) the mechanism rotates about. A fresh clone, safe to mutate. */
    public Location getPivot() { return pivot.clone(); }

    public int getBlockCount() { return blockCount; }

    /** True if the mechanism rotates about the vertical (Y) axis — a rotator door vs. a wall drawbridge. */
    public boolean isVerticalAxis() { return verticalAxis; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
