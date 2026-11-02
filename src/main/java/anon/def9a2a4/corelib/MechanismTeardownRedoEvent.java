package anon.def9a2a4.corelib;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired just before defCoreLib lands a mechanism again after a crash undid its landing: it had been
 * disassembled, but the server stopped before the chunks holding the landed blocks were written, so on this boot
 * the blocks are missing (or only partly there). The landing is redone from the record kept at the time, at the
 * same pose; cells that already hold their block are left alone.
 *
 * <p>The mechanism is a stand-in rebuilt for the landing (no displays or colliders), and it is not registered.
 * Consumers that set per-landing hooks ({@link Mechanism#setCellPlacePolicy}, {@link Mechanism#setDropItemHook})
 * set them here, so the redone landing follows the same rules the original did. Not cancellable.
 */
public class MechanismTeardownRedoEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Mechanism mechanism;

    MechanismTeardownRedoEvent(Mechanism mechanism) {
        this.mechanism = mechanism;
    }

    /** The stand-in about to land. Its {@link Mechanism#id()} and {@link Mechanism#type()} are the original's. */
    public Mechanism getMechanism() {
        return mechanism;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
