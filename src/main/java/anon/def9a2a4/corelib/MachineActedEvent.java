package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a rotation machine performs its state-changing action: a drill breaks a block, a fan
 * pushes an entity, a placer sets a block, a suction hopper captures an item, a dynamo emits a
 * nonzero signal, a clutch locks a live line, a reverser flips a powered line. Pure "it happened"
 * signal — machines that produce items report through {@link MachineProducedEvent} instead.
 *
 * <p>Redstone/tick-driven, so it carries no player; listeners that need one should look up nearby
 * players around the block. Purely informational (not cancellable). Fire sites are guarded with
 * {@link #hasListeners()} so ticking machines pay nothing when no plugin listens.
 */
public class MachineActedEvent extends BlockEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String machineType;

    public MachineActedEvent(Block machine, String machineType) {
        super(machine);
        this.machineType = machineType;
    }

    /** The machine's block-type id — e.g. {@code mech:drill}, {@code mech:clutch}. */
    public String getMachineType() { return machineType; }

    /** Cheap guard so hot tick paths cost nothing when no plugin listens. */
    public static boolean hasListeners() { return HANDLERS.getRegisteredListeners().length > 0; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
