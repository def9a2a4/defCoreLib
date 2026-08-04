package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.util.BoundingBox;
import org.bukkit.inventory.Inventory;
import org.joml.Matrix4f;
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

    /**
     * Driven mode: the consumer has ALREADY positioned the vehicle this tick (e.g. its own physics
     * teleport). Sync the rigid body + collider carriers to the vehicle's current location, apply a
     * client dead-reckoning velocity hint to the vehicle, and rotate by {@code relYaw} degrees
     * <b>relative to the as-built orientation</b>. Does NOT teleport the vehicle (so it never
     * double-moves a consumer that already placed it, nor rewrites the vehicle's frozen yaw).
     *
     * <p>Requires the mechanism to be in driven mode so {@code tickMechanisms} skips the
     * vehicle auto-follow ({@code updateFromVehicle}); otherwise the two fight. Prefer this over
     * {@link #move} for a per-tick consumer-driven body (a ship). The default falls back to a bare
     * rotate for any implementation that doesn't support driven mode.
     */
    default void repositionDriven(float relYaw) { rotate(relYaw); }

    /** Lift entities standing on this mechanism's collision surface up by {@code dy} blocks, preserving
     *  their horizontal/fall velocity, so a rising mechanism carries its riders instead of clipping
     *  through them. No-op if dy <= 0. Call BEFORE the matching upward move(). */
    default void carryRidersUp(double dy) {}

    /** Get block data for a specific index. */
    MechanismBlockData getBlock(int index);

    /** Update a custom head block's state in-place (texture, particles, configs). */
    void setBlockState(int index, String newState);

    /** Get the storage inventory for a block (if it has one). */
    @Nullable Inventory getStorage(int blockIndex);

    /** Restore blocks to the world and remove all entities. */
    void disassemble();

    /**
     * The snapped (multiple-of-90° about the rotation axis) rigid rotation blocks land with on
     * {@link #disassemble()} — maps integer block offsets to integers. Used by the glue rebind:
     * landed offset = this × pre-move offset.
     */
    Matrix4f landingRotation();

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

    /** Number of collision colliders (carrier+shulker pairs) this mechanism currently has. */
    int colliderCount();

    /**
     * World-space bounding box of the live collider shulker for the given block index, or {@code null}
     * if that block has no collider (or its shulker is gone). Keyed on BLOCK INDEX (stable across
     * recovery — unlike collider list position), pairing with {@link #hasCollision(int)} so a consumer
     * can iterate {@link #blockCount()} and read live AABBs (e.g. for its own collision math).
     */
    @Nullable BoundingBox getColliderBoxByBlock(int blockIndex);

    /** Get the primary display entity for a block index. */
    Display primaryDisplay(int blockIndex);
}
