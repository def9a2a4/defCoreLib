package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.inventory.Inventory;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A moveable block structure — displays + shulker colliders driven by a consumer.
 * All mutating methods must be called from the main server thread.
 */
public interface Mechanism {

    /** Per-cell disassembly decision (see {@link CellPlacePolicy}). */
    @ApiStatus.Experimental
    enum PlaceDecision {
        /** Land the block normally (default). */ PLACE,
        /** Drop the block as an item instead of writing it (e.g. a WorldGuard-protected destination). */ DROP,
        /** Discard the block silently (its banners still drop). */ SKIP
    }

    /**
     * Consumer policy consulted for EACH block cell at {@link #disassemble()}, after the off-world +
     * protected-cell checks and before the normal air/fragile/solid dispatch. Lets a consumer redirect a
     * cell to a drop (anti-block-laundering across a protected boundary) or skip it, without forking the
     * landing loop. {@code null} = every cell places normally.
     */
    @ApiStatus.Experimental
    @FunctionalInterface
    interface CellPlacePolicy {
        PlaceDecision decide(Block target, MechanismBlockData block);
    }

    /**
     * Consumer hook to construct the {@link ItemStack} a block drops when it can't be placed (off-world,
     * DROP policy, or solid-wins). Receives the engine's default drop (may be {@code null} — e.g. a wall
     * variant with no item form) and returns the final item to drop, or {@code null} to suppress the drop.
     * Use for a config-driven identity ({@code ship_engine}), a wall-variant→floor-variant remap, or to
     * transfer a head texture / name onto the item.
     */
    @ApiStatus.Experimental
    @FunctionalInterface
    interface DropItemHook {
        @Nullable ItemStack construct(MechanismBlockData block, @Nullable ItemStack defaultDrop);
    }

    /** Set the per-cell placement policy consulted at {@link #disassemble()}; {@code null} clears it. */
    @ApiStatus.Experimental
    void setCellPlacePolicy(@Nullable CellPlacePolicy policy);

    /** Set the drop-item construction hook; {@code null} clears it (engine default drops apply). */
    @ApiStatus.Experimental
    void setDropItemHook(@Nullable DropItemHook hook);

    /**
     * Set a callback invoked at {@link #disassemble()} AFTER all blocks have landed but BEFORE the
     * mechanism's display/collider/vehicle entities are removed — the window a consumer needs to read
     * live collider state against the just-placed blocks (e.g. re-parent leads from collider shulkers
     * onto freshly-placed fences). {@code null} clears it.
     */
    @ApiStatus.Experimental
    void setBeforeEntityRemoval(@Nullable Runnable callback);

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

    // ── Seats (4a) ────────────────────────────────────────────────────────────
    // A "seat" is a collider block a player rides (its shulker is the mount point). The consumer decides
    // which blocks are seats and performs the actual addPassenger/dismount; core owns the seat marking,
    // occupancy bookkeeping, repositioning (nested re-mount + movement threshold), and persistence (the
    // seat's shulker is tagged, so seats survive a restart and re-fire onSeatRecovered).

    /**
     * Designate the collider at {@code blockIndex} as a seat; {@code driver} marks the steering seat. Tags
     * the seat's shulker so it survives recovery, and fires {@link SeatListener#onSeatSpawned}. No-op if
     * that block has no collider (nothing to ride). Call after assembly.
     */
    @ApiStatus.Experimental
    void designateSeat(int blockIndex, boolean driver);

    /** The live shulker a rider mounts for a seat block, or {@code null} if {@code blockIndex} isn't a seat
     *  (or its shulker is gone). Stable across recovery — keyed on block index. */
    @ApiStatus.Experimental
    @Nullable Shulker seatEntity(int blockIndex);

    /** Whether {@code blockIndex} is a designated seat. */
    @ApiStatus.Experimental
    boolean isSeat(int blockIndex);

    /** Whether {@code blockIndex} is the (a) driver/steering seat. */
    @ApiStatus.Experimental
    boolean isDriverSeat(int blockIndex);

    /** Block indices currently designated as seats (a copy — safe to iterate). */
    @ApiStatus.Experimental
    Set<Integer> seatBlockIndices();

    /** Record who occupies a seat (core bookkeeping; the consumer still does the actual mount). {@code null}
     *  clears it. */
    @ApiStatus.Experimental
    void setSeatOccupant(int blockIndex, @Nullable UUID player);

    /** The recorded occupant of a seat, or {@code null} if empty / not a seat. */
    @ApiStatus.Experimental
    @Nullable UUID seatOccupant(int blockIndex);
}
