package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fired when a rotation machine (millstone, press) ejects outputs and no vanilla
 * container exists in the eject direction. Listeners can deliver the items through
 * an alternate path (e.g. pipes) and set the result accordingly.
 */
public class MachineEjectEvent extends BlockEvent {

    public enum Result {
        /** No handler claimed the items — drop on the ground (default behavior). */
        DEFAULT,
        /** A listener delivered all items successfully — consume inputs. */
        HANDLED,
        /** A listener exists but the destination is full — stall the machine. */
        STALL
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final BlockFace direction;
    private final List<ItemStack> outputs;
    private Result result = Result.DEFAULT;

    public MachineEjectEvent(Block machine, BlockFace direction, List<ItemStack> outputs) {
        super(machine);
        this.direction = direction;
        this.outputs = outputs.stream().map(ItemStack::clone)
            .collect(Collectors.toUnmodifiableList());
    }

    public BlockFace getDirection() { return direction; }

    public Block getTarget() { return getBlock().getRelative(direction); }

    public List<ItemStack> getOutputs() { return outputs; }

    public Result getResult() { return result; }

    public void setResult(Result result) { this.result = result; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
