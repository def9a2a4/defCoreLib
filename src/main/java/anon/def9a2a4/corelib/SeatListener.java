package anon.def9a2a4.corelib;

import org.bukkit.entity.Shulker;
import org.jetbrains.annotations.ApiStatus;

/**
 * Consumer hook fired when a mechanism seat's ridable shulker becomes available — at fresh
 * {@link Mechanism#designateSeat(int, boolean)} (spawn) and again after crash recovery re-adopts a
 * seat-tagged collider (recovered). The consumer uses it to configure the seat entity it doesn't own
 * (e.g. mirror ship HP onto the shulker's max-health attribute for the vanilla vehicle HUD).
 *
 * <p>Registered once on the {@link MechanismRegistry} ({@link MechanismRegistry#setSeatListener}); a single
 * listener sees every mechanism's seats (dispatch on {@code mech} / {@code mech.type()}). Core owns seat
 * occupancy + repositioning; the consumer still performs the actual {@code addPassenger}/dismount itself.
 */
@ApiStatus.Experimental
public interface SeatListener {

    /** A freshly-designated seat's shulker is live and ready to ride. */
    void onSeatSpawned(Mechanism mech, int seatIndex, Shulker seat);

    /** A persisted seat's shulker was re-adopted on chunk load after a restart. Defaults to the same
     *  handling as a fresh spawn — override only if recovery needs to differ (e.g. re-mirror, don't reset). */
    default void onSeatRecovered(Mechanism mech, int seatIndex, Shulker seat) {
        onSeatSpawned(mech, seatIndex, seat);
    }
}
