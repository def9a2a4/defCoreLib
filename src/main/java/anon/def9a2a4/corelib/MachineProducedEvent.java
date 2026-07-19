package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fired once per <em>successful</em> output delivery of a processing machine (millstone, press,
 * sieve) — into the container below, through a {@link MachineEjectEvent} handler, or dropped to the
 * ground. Never fired on a stall/rollback, and never for pass-through forwarding (suction hopper →
 * pipe), so listeners can trust the outputs were genuinely produced by this machine.
 *
 * <p>Purely informational (not cancellable) — delivery routing stays {@link MachineEjectEvent}'s
 * job. Redstone/tick-driven, so it carries no player.
 */
public class MachineProducedEvent extends BlockEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String machineType;
    private final List<ItemStack> outputs;

    public MachineProducedEvent(Block machine, String machineType, List<ItemStack> outputs) {
        super(machine);
        this.machineType = machineType;
        this.outputs = outputs.stream().map(ItemStack::clone)
            .collect(Collectors.toUnmodifiableList());
    }

    /** The machine's block-type id — e.g. {@code mech:millstone}, {@code mech:sieve}. */
    public String getMachineType() { return machineType; }

    public List<ItemStack> getOutputs() { return outputs; }

    /** Cheap guard so machine tick paths cost nothing when no plugin listens. */
    public static boolean hasListeners() { return HANDLERS.getRegisteredListeners().length > 0; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
