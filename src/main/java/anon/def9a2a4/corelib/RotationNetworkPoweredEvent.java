package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired once when a rotation network transitions from unpowered to powered (a false→true rising edge
 * in {@code RotationNetwork.doRecalculate}). Deliberately NOT fired on every recalculation — the
 * recalc path is hot — so listeners see one event per rising edge, not per node or per tick.
 *
 * <p>Redstone-driven, so it carries no player; listeners should grant to players near
 * {@link #getLocation()}. {@link #getSupply()}/{@link #getDemand()}/{@link #getMemberCount()} expose
 * the network stats (torque, load, chain length) for milestone thresholds.
 */
public class RotationNetworkPoweredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location location;
    private final int supply;
    private final int demand;
    private final int memberCount;
    private final List<String> sourceTypes;

    public RotationNetworkPoweredEvent(Location location, int supply, int demand, int memberCount,
                                       List<String> sourceTypes) {
        this.location = location;
        this.supply = supply;
        this.demand = demand;
        this.memberCount = memberCount;
        this.sourceTypes = List.copyOf(sourceTypes);
    }

    /** Location of a representative block in the network. A fresh clone, safe to mutate. */
    public Location getLocation() { return location.clone(); }

    /** Total power supplied by the network's sources. */
    public int getSupply() { return supply; }

    /** Total power demanded by the network's consumers. */
    public int getDemand() { return demand; }

    /** Number of blocks (nodes) in the network. */
    public int getMemberCount() { return memberCount; }

    /** Distinct block-type ids of the sources driving this network (e.g. {@code mech:windmill},
     *  {@code mech:water_wheel}, {@code mech:engine}). Unmodifiable. */
    public List<String> getSourceTypes() { return sourceTypes; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
