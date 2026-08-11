package anon.def9a2a4.corelib;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when an assembled mechanism's onboard rotation network is re-solved — i.e. when what is
 * powered aboard it may have changed.
 *
 * <p>Consumers that cache anything derived from live rotation state (a ship caching how much thrust
 * its propellers are producing, say) should invalidate on this rather than polling every tick.
 *
 * <h2>When this actually fires</h2>
 * Topology is frozen while a mechanism is assembled — blocks can't be added or removed, and world
 * redstone can't reach a riding block — so the only thing that can change the solve is a SOURCE
 * flipping. In practice that means an engine starting or running dry, checked once every 20 ticks.
 * A mechanism whose only sources are constant (windmills) never re-solves after assembly.
 *
 * <p>So this is cheap: at most one event per mechanism per 20 ticks, and only on an actual change.
 * It is also not a complete change signal for everything you might care about — a fuel-burning
 * machine's inventory emptying is caught, but if you depend on something the solver doesn't model,
 * keep a slow poll as a backstop.
 *
 * <p>Not cancellable: the solve has already happened.
 */
public class MechanismRotationSolvedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Mechanism mechanism;

    MechanismRotationSolvedEvent(Mechanism mechanism) {
        this.mechanism = mechanism;
    }

    /** The mechanism whose network was re-solved. */
    public Mechanism getMechanism() {
        return mechanism;
    }

    /** Cheap guard so the driver can skip constructing the event when nothing is listening. */
    static boolean hasListeners() {
        return HANDLERS.getRegisteredListeners().length > 0;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
