package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A moveable block structure — displays + shulker colliders driven by a consumer.
 * All mutating methods must be called from the main server thread.
 */
public interface Mechanism {

    UUID id();
    String type();
    Location pivot();
    int blockCount();
    float getCurrentYaw();

    /** Teleport the mechanism to a new position and rotate. */
    void move(Location position, float yaw);

    /** Rotate in place (pivot stays, only transforms update). */
    void rotate(float yaw);

    /** Get block data for a specific index. */
    MechanismBlockData getBlock(int index);

    /** Update a custom head block's state in-place (texture, particles, configs). */
    void setBlockState(int index, String newState);

    /** Get the storage inventory for a block (if it has one). */
    @Nullable Inventory getStorage(int blockIndex);

    /** Restore blocks to the world and remove all entities. */
    void disassemble();

    /**
     * Set a callback invoked at the end of {@link #disassemble()} with the blocks that were actually
     * placed back into the world (at their final, possibly-rotated landing cells). Used by the glue
     * layer to rebind an anchor's offset set to the structure's new rest positions. {@code null} clears it.
     */
    void setOnDisassembled(@Nullable Consumer<List<Block>> callback);

    /** Remove all entities without restoring blocks. */
    void destroy();

    /** Whether a specific block index has collision enabled. */
    boolean hasCollision(int blockIndex);

    /** Get the primary display entity for a block index. */
    Display primaryDisplay(int blockIndex);
}
