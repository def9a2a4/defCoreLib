package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Default implementation of {@link Mechanism}.
 * Manages the passenger chain, display transforms, collider positioning,
 * and block restoration on disassembly.
 */
final class BasicMechanism implements Mechanism {

    private final UUID id;
    private final String type;
    private Location pivot;
    private float currentYaw = 0f; // rotation angle (degrees) about rotationAxis; "yaw" kept for the Y/minecart path
    private final Vector3f rotationAxis; // unit axis to rotate about — Y for doors/minecarts, X/Z for drawbridges
    private Matrix4f currentTransform = new Matrix4f(); // identity

    final Entity vehicle;
    final org.bukkit.entity.BlockDisplay parent; // invisible intermediary — all displays mount here
    final float rideOffset; // passenger riding offset — varies by vehicle entity type
    final List<List<Display>> displaysPerBlock;
    final List<ColliderPair> colliders;
    final List<MechanismBlockData> blocks;
    final CustomBlockRegistry registry;
    final @Nullable MechanismSerializer serializer;
    final long startTick;
    final boolean ownsVehicle; // true if we spawned it (should remove on destroy)
    final float assemblyYaw; // vehicle yaw at assembly — delta base for updateFromVehicle

    // Auto-follow: track vehicle movement for passive vehicles (minecarts on rails)
    private Location previousVehicleLoc;
    private float previousVehicleYaw;

    // Back-reference set by MechanismRegistry after construction
    MechanismRegistry mechanismRegistry;

    // Optional glue rebind hook: invoked at disassembly with the blocks actually placed back.
    private @Nullable Consumer<List<Block>> onDisassembled;

    // Guards against a second disassemble() (out-of-band teardown + a manager retry): a re-run would
    // find the just-placed blocks solid and drop the whole structure as duplicate items.
    private boolean disassembled;

    BasicMechanism(UUID id, String type, Location pivot, Vector3f rotationAxis,
                   Entity vehicle, org.bukkit.entity.BlockDisplay parent,
                   float rideOffset, boolean ownsVehicle,
                   List<List<Display>> displaysPerBlock,
                   List<ColliderPair> colliders,
                   List<MechanismBlockData> blocks,
                   CustomBlockRegistry registry,
                   @Nullable MechanismSerializer serializer) {
        this.id = id;
        this.type = type;
        this.pivot = pivot;
        this.rotationAxis = new Vector3f(rotationAxis).normalize();
        this.vehicle = vehicle;
        this.parent = parent;
        this.rideOffset = rideOffset;
        this.ownsVehicle = ownsVehicle;
        this.displaysPerBlock = displaysPerBlock;
        this.colliders = colliders;
        this.blocks = blocks;
        this.registry = registry;
        this.serializer = serializer;
        this.startTick = Bukkit.getServer().getCurrentTick();
        this.assemblyYaw = vehicle.getLocation().getYaw();
        // Track the RAW vehicle position so updateFromVehicle's first delta is zero if the vehicle
        // hasn't moved, or exactly the 1-tick assembly drift if it has — not a spurious snap-vs-vehicle
        // offset. The pivot itself is the snapped frame; deltas accumulate onto it.
        this.previousVehicleLoc = vehicle.getLocation();
        this.previousVehicleYaw = assemblyYaw;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Mechanism interface
    // ──────────────────────────────────────────────────────────────────────

    @Override public UUID id() { return id; }
    @Override public String type() { return type; }
    @Override public Location pivot() { return pivot.clone(); }
    @Override public int blockCount() { return blocks.size(); }
    @Override public float getCurrentYaw() { return currentYaw; }
    @Override public MechanismBlockData getBlock(int index) { return blocks.get(index); }
    @Override public boolean hasCollision(int blockIndex) { return blocks.get(blockIndex).hasCollision; }

    @Override
    public Display primaryDisplay(int blockIndex) {
        return displaysPerBlock.get(blockIndex).get(0);
    }

    @Override
    public @Nullable Inventory getStorage(int blockIndex) {
        return blocks.get(blockIndex).storage;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Live-position queries (used by MechanismRotationDriver)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The world cell a block currently occupies: {@code pivot + currentTransform·localTransform},
     * floored. The pivot is block-centered and local offsets are integer center-to-center, so at
     * near-cardinal orientations the sum sits at a cell center and floor() is exact — the same math
     * as the collider repositioning in {@link #rotate} and the landing cells in {@link #disassemble}.
     */
    org.joml.Vector3i liveCell(int index) {
        Vector3f off = currentTransform.transformPosition(
            blocks.get(index).localTransform.getTranslation(new Vector3f()), new Vector3f());
        return new org.joml.Vector3i(
            (int) Math.floor(pivot.getX() + off.x),
            (int) Math.floor(pivot.getY() + off.y),
            (int) Math.floor(pivot.getZ() + off.z));
    }

    /**
     * A block's current world facing: its local facing (wall head → {@code Directional.getFacing()},
     * floor head → DOWN, matching the drill's facing-PDC convention) rotated by the mechanism's
     * current transform. Matrix-based, so it is correct for any rotation axis (drawbridges too).
     * Null when the mechanism is mid-rotation and the rotated facing isn't axis-aligned.
     */
    @Nullable BlockFace liveFacing(int index) {
        MechanismBlockData mb = blocks.get(index);
        BlockFace local = mb.blockData instanceof org.bukkit.block.data.Directional d
            ? d.getFacing() : BlockFace.DOWN;
        return liveDirection(local);
    }

    /** Any mechanism-local face rotated by the current transform (fan blow direction, hopper
     *  mount, pipe chains). Null when mid-rotation leaves it off-axis. */
    @Nullable BlockFace liveDirection(BlockFace local) {
        Vector3f v = currentTransform.transformDirection(
            new Vector3f(local.getModX(), local.getModY(), local.getModZ()), new Vector3f());
        int rx = Math.round(v.x), ry = Math.round(v.y), rz = Math.round(v.z);
        for (BlockFace f : Faces.CARDINAL) {
            if (f.getModX() == rx && f.getModY() == ry && f.getModZ() == rz) return f;
        }
        return null;
    }

    /**
     * Whether the current rotation angle is within ~1° of a 90° multiple — the only orientations
     * where integer local offsets map to unambiguous world cells. The rotation driver skips
     * consumer actuation (not power/spin bookkeeping) while this is false.
     */
    boolean isNearCardinal() {
        float m = Math.abs(currentYaw % 90f);
        return m < 1.0f || m > 89.0f;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Movement
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void rotate(float yaw) {
        checkMainThread();
        this.currentYaw = yaw;
        // Rotate about the mechanism's axis (Y for doors/minecarts → identical to rotateY).
        // Negated to match Minecraft's CW-from-+axis convention (load-bearing for the Y path).
        Matrix4f rot = new Matrix4f().rotate((float) Math.toRadians(-yaw),
                rotationAxis.x, rotationAxis.y, rotationAxis.z);
        this.currentTransform = rot;

        for (int i = 0; i < blocks.size(); i++) {
            MechanismBlockData mb = blocks.get(i);
            Matrix4f dm = new Matrix4f(rot).mul(mb.localTransform);
            dm.m31(dm.m31() - rideOffset); // compensate vehicle passenger riding offset

            // Primary display (index 0): BlockDisplay renders the unit cube from its MIN corner, so
            // shift -0.5 on ALL axes (in LOCAL space, post-multiply) to put the cube's true 3D center
            // at the transform origin. Combined with the block-centered pivot + center-based
            // localTransform, the cube then orbits its center about ANY cardinal axis (drawbridges).
            // ItemDisplay renders centered — no shift needed.
            List<Display> group = displaysPerBlock.get(i);
            Display primary = group.get(0);
            if (primary instanceof org.bukkit.entity.BlockDisplay) {
                Matrix4f bdm = new Matrix4f(dm).translate(-0.5f, -0.5f, -0.5f);
                primary.setTransformationMatrix(bdm);
            } else if (mb.wallFacing != null) {
                // Wall-mounted custom head: floor heads (wallFacing == null) are already center-bottom
                // correct; a wall head shifts +0.25 up and +0.25 toward its attachment face (= -wallFacing).
                // Applied in the LOCAL frame on a COPY of dm so it swings with the door and doesn't corrupt
                // the aux displays below (which reuse dm).
                Matrix4f wdm = new Matrix4f(dm).translate(
                    -mb.wallFacing.x * 0.25f, 0.25f, -mb.wallFacing.z * 0.25f);
                primary.setTransformationMatrix(wdm);
            } else {
                primary.setTransformationMatrix(dm);
            }

            // Additional displays: rot * localTransform * decTransform
            // Skip animated ones — tickMechanisms() handles those
            if (mb.displayEntityConfigs != null) {
                for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                    int displayIdx = d + 1;
                    if (displayIdx >= group.size()) break;
                    var dec = mb.displayEntityConfigs.get(d);
                    if (dec.animation() != null) continue;
                    Matrix4f extra = new Matrix4f(dm);
                    applyWallOffset(extra, mb.wallFacing, dec.wallOffset());
                    extra.mul(transformToMatrix(dec.transform()));
                    group.get(displayIdx).setTransformationMatrix(extra);
                }
            }

            // Additional BLOCK-data displays (e.g. a vertical slab's body). Composed exactly like the item
            // path above — dm (center frame) · wallOffset · configTransform, with NO -0.5 corner shift: unlike
            // the PRIMARY BlockDisplay (identity transform, needs the shift), these carry an authored transform
            // that already bakes in the corner offset, matching the static DisplayUtil.spawnBlock (center-spawn,
            // no shift). Indexed after the item extras. Animated ones are handled in updateAnimatedDisplays().
            if (mb.blockDisplayEntityConfigs != null) {
                int base = 1 + (mb.displayEntityConfigs != null ? mb.displayEntityConfigs.size() : 0);
                for (int d = 0; d < mb.blockDisplayEntityConfigs.size(); d++) {
                    int idx = base + d;
                    if (idx >= group.size()) break;
                    var bdc = mb.blockDisplayEntityConfigs.get(d);
                    if (bdc.animation() != null) continue;
                    Matrix4f bd = new Matrix4f(dm);
                    applyWallOffset(bd, mb.wallFacing, bdc.wallOffset());
                    bd.mul(transformToMatrix(bdc.transform()));
                    group.get(idx).setTransformationMatrix(bd);
                }
            }
        }

        // Reposition collider carriers. localTransform is now center-based in all axes and the pivot
        // is block-centered, so rotating the plain translation orbits the true block center. The -0.5 Y
        // anchors the carrier at the cell bottom (the shulker box sits ~half a block above its carrier),
        // matching the collider spawn in assembleCore.
        for (ColliderPair cp : colliders) {
            Vector3f worldOff = rot.transformPosition(
                blocks.get(cp.blockIndex()).localTransform.getTranslation(new Vector3f()),
                new Vector3f());
            TeleportCompat.teleport(cp.carrier(),
                pivot.clone().add(worldOff.x, worldOff.y - 0.5, worldOff.z));
        }
    }

    @Override
    public void move(Location position, float yaw) {
        checkMainThread();
        TeleportCompat.teleport(vehicle, position);
        this.pivot = position.clone();
        rotate(yaw);
        // A manual move() is authoritative: re-baseline the vehicle tracker so updateFromVehicle()
        // (run every tick for all mechanisms) doesn't re-apply this teleport as drift next tick.
        this.previousVehicleLoc = vehicle.getLocation();
        this.previousVehicleYaw = this.previousVehicleLoc.getYaw();
    }

    // ──────────────────────────────────────────────────────────────────────
    // State transitions
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void setBlockState(int index, String newState) {
        checkMainThread();
        MechanismBlockData mb = blocks.get(index);
        if (mb.customTypeId == null) return;
        CustomHeadBlock type = registry.getType(mb.customTypeId);
        if (type == null) return;

        mb.customState = newState;

        // Update primary display (skull texture)
        Display primary = displaysPerBlock.get(index).get(0);
        if (primary instanceof ItemDisplay id) {
            String tex = type.resolveTexture(newState, 0, null);
            id.setItemStack(HeadUtil.createHead(tex, 1));
        }

        // Update configs for tick loop
        mb.particles = type.resolveParticles(newState);
        mb.displayEntityConfigs = type.resolveDisplayEntities(newState);
        mb.blockDisplayEntityConfigs = type.resolveBlockDisplayEntities(newState);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void setOnDisassembled(@Nullable Consumer<List<Block>> callback) {
        this.onDisassembled = callback;
    }

    // Cells that must never be overwritten on disassemble (e.g. a piston core the rod slides through).
    // A mechanism block whose landing cell is protected is discarded — not placed, not dropped.
    private java.util.@Nullable Set<CustomBlockRegistry.LocationKey> protectedCells = null;

    /** Mark cells that disassembly must not overwrite (consumed instead). */
    void setProtectedCells(java.util.Set<CustomBlockRegistry.LocationKey> cells) {
        this.protectedCells = cells;
    }

    @Override
    public void disassemble() {
        checkMainThread();
        if (disassembled) return;   // idempotent: a second pass would dupe the structure as items
        disassembled = true;
        // Snap to 90° about the rotation axis. For Y this is yaw; for X/Z it tips a drawbridge
        // back to a cardinal orientation. 90° rotations about a cardinal axis map integer
        // offsets to integers, so block positions stay exact.
        float snappedYaw = Math.round(currentYaw / 90f) * 90f;
        Matrix4f rotation = new Matrix4f().rotate((float) Math.toRadians(-snappedYaw),
                rotationAxis.x, rotationAxis.y, rotationAxis.z);

        // The cells where blocks actually landed — handed to the glue rebind hook so an anchor's
        // offset set tracks the structure's new rest positions (dropped-as-item blocks are excluded).
        List<Block> placed = new ArrayList<>(blocks.size());

        for (int i = 0; i < blocks.size(); i++) {
            MechanismBlockData mb = blocks.get(i);
            Vector3f worldOffset = rotation.transformPosition(
                mb.localTransform.getTranslation(new Vector3f()), new Vector3f());
            // localTransform is center-to-center (integer) and the pivot is block-centered, so a 90°
            // rotation maps integers to integers; Math.round handles trig epsilon (1.9999999f → 2).
            Location blockLoc = pivot.clone().add(
                Math.round(worldOffset.x), Math.round(worldOffset.y), Math.round(worldOffset.z));

            // Off-world guard: a tall drawbridge can swing a block below world-min or above
            // world-max. Don't try to place there — drop it as an item instead.
            if (blockLoc.getBlockY() < blockLoc.getWorld().getMinHeight()
                    || blockLoc.getBlockY() >= blockLoc.getWorld().getMaxHeight()) {
                dropBlockAsItem(blockLoc, mb);
                continue;
            }
            Block target = blockLoc.getBlock();

            // Protected cell (e.g. the piston core the rod slid through): consume the block silently.
            if (protectedCells != null && protectedCells.contains(CustomBlockRegistry.LocationKey.of(target))) {
                continue;
            }

            if (target.getType().isAir() || target.getType() == Material.WATER
                    || target.getType() == Material.LAVA) {
                placeBlock(target, mb, snappedYaw);
                placed.add(target);
            } else if (FragileBlocks.isFragile(target.getType())) {
                target.breakNaturally();
                placeBlock(target, mb, snappedYaw);
                placed.add(target);
            } else {
                // Solid block wins — explosion effect + drop mechanism block as item
                target.getWorld().spawnParticle(Particle.EXPLOSION,
                    blockLoc.clone().add(0.5, 0.5, 0.5), 1);
                dropBlockAsItem(blockLoc, mb);
            }
        }

        // Unregister now so the tick loop won't re-touch this disassembled mech. For an owned vehicle, DEFER
        // removing the mech's display entities one tick so the just-placed blocks' own displays get a frame to
        // render first — else custom-block displays blink as the mech displays vanish (the landing flicker).
        // The mech is already unregistered, so the lingering entities are never ticked. External (minecart)
        // vehicles remove synchronously (their landed payload is vanilla and renders same-tick).
        if (mechanismRegistry != null) mechanismRegistry.onMechanismRemoved(this);
        if (ownsVehicle && mechanismRegistry != null) {
            mechanismRegistry.deferEntityRemoval(this);
        } else {
            removeAllEntities();
        }
        if (serializer != null) serializer.onDisassemble(this);
        if (onDisassembled != null) onDisassembled.accept(placed);
    }

    @Override
    public void destroy() {
        checkMainThread();
        removeAllEntities();
        if (mechanismRegistry != null) mechanismRegistry.onMechanismRemoved(this);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    private void placeBlock(Block target, MechanismBlockData mb, float snappedYaw) {
        target.setBlockData(BlockRotation.rotateBlockData(mb.blockData, snappedYaw));

        if (mb.customTypeId != null) {
            CustomHeadBlock type = registry.getType(mb.customTypeId);
            if (type != null && type.baseBlock() != null) {
                // Bare-first block (e.g. casing = OAK_PLANKS): no block-entity, so markBlock can't stamp
                // it. The base block was placed by setBlockData above; register its identity in the
                // display-backed registry (durable chunk PDC + tagged display) and respawn the display.
                // (The shaft is captured/re-placed as an encased head — baseBlock() null — so it takes the
                // skull path below.) A symmetric bare block has no rotatable state; keep the captured one.
                String landedState = mb.customState;
                registry.addBareBlock(target, type);
                int power = registry.readPower(target, type);
                registry.applyConfig(target, type, landedState, power);
                registry.restoreBlock(target, type, landedState);
                if (mb.storage != null) registry.restoreStorageSnapshot(target, mb.storage);
            } else if (type != null) {
                // The vanilla data was rotated above; re-derive the custom state for the landed
                // orientation so it doesn't snap to an impossible state (and rejoins the network on the
                // correct axis).
                String landedState = BlockRotation.rotateCustomState(type, mb.customState, target.getBlockData());
                registry.markBlock(target, type, landedState);
                int power = registry.readPower(target, type);
                registry.applyConfig(target, type, landedState, power);
                registry.restoreBlock(target, type, landedState);
                // Write captured storage back into the landed skull, keeping the shared cache + PDC
                // consistent (custom-storage blocks don't hit the vanilla-Container branch below).
                if (mb.storage != null) registry.restoreStorageSnapshot(target, mb.storage);
            }
        } else if (mb.storage != null && target.getState() instanceof Container c) {
            c.getSnapshotInventory().setContents(mb.storage.getContents());
            c.update();
        }
    }

    private void dropBlockAsItem(Location loc, MechanismBlockData mb) {
        ItemStack drop;
        if (mb.customTypeId != null) {
            CustomHeadBlock type = registry.getType(mb.customTypeId);
            drop = (type != null) ? type.createItem(1) : new ItemStack(mb.blockData.getMaterial());
        } else {
            drop = new ItemStack(mb.blockData.getMaterial());
        }
        loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), drop);

        if (mb.storage != null) {
            for (ItemStack item : mb.storage.getContents()) {
                if (item != null && !item.getType().isAir()) {
                    loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), item);
                }
            }
        }
    }

    void removeAllEntities() {
        for (var group : displaysPerBlock) {
            for (Display d : group) d.remove();
        }
        for (ColliderPair cp : colliders) {
            cp.carrier().remove();
            cp.shulker().remove();
        }
        parent.remove(); // Entity.remove() implicitly ejects from vehicle
        if (ownsVehicle) {
            vehicle.remove();
        }
    }

    /**
     * Check if the vehicle has moved/rotated since last tick and update transforms.
     * Used for passive vehicles (minecarts on rails) that move on their own.
     * For consumer-driven mechanisms (door demo), the vehicle stays put and this is a no-op.
     */
    void updateFromVehicle() {
        if (!vehicle.isValid()) return;
        Location loc = vehicle.getLocation();
        float yaw = loc.getYaw();
        // Guard: distanceSquared throws if worlds differ (e.g., entity teleported cross-world).
        // Re-snap the pivot to the new world's block center so the snapped frame survives the jump.
        if (!loc.getWorld().equals(previousVehicleLoc.getWorld())) {
            this.pivot = loc.clone();
            this.pivot.setX(Math.floor(loc.getX()) + 0.5);
            this.pivot.setY(Math.floor(loc.getY()) + 0.5);
            this.pivot.setZ(Math.floor(loc.getZ()) + 0.5);
            previousVehicleLoc = loc.clone();
            previousVehicleYaw = yaw;
            return;
        }
        double distSq = loc.distanceSquared(previousVehicleLoc);
        boolean moved = distSq > 0.0001 || Math.abs(yaw - previousVehicleYaw) > 0.1f;
        if (!moved) return;

        // Delta-track: accumulate the vehicle's movement onto the pivot so it stays in the snapped
        // frame (a constant offset from the raw vehicle), preserving the integer-offset invariant.
        // Overwriting with the raw vehicle position would destroy that frame and skew rotation.
        this.pivot.add(
            loc.getX() - previousVehicleLoc.getX(),
            loc.getY() - previousVehicleLoc.getY(),
            loc.getZ() - previousVehicleLoc.getZ());
        // Teleport parent to follow the SNAPPED pivot (for non-passenger parent, e.g., minecart path).
        // Zero out yaw/pitch — all rotation is handled via display transform matrices (deltaYaw).
        // If we pass the vehicle's yaw here, displays would double-rotate (parent entity yaw +
        // transform rotation), since passenger displays inherit the parent's entity orientation.
        if (!ownsVehicle) {
            Location parentLoc = this.pivot.clone();
            parentLoc.setYaw(0);
            parentLoc.setPitch(0);
            TeleportCompat.teleport(parent, parentLoc);
        }
        rotate(yaw - assemblyYaw);
        previousVehicleLoc = loc.clone();
        previousVehicleYaw = yaw;
    }

    Matrix4f currentTransform() { return currentTransform; }

    static Matrix4f transformToMatrix(org.bukkit.util.Transformation t) {
        return new Matrix4f()
                .translate(t.getTranslation())
                .rotate(t.getLeftRotation())
                .scale(t.getScale())
                .rotate(t.getRightRotation());
    }

    /**
     * Apply a wall-mounted display's {@code wall_offset} as a block-local shift of {@code -facing·wallOffset},
     * mirroring the live placement in {@code CustomBlockRegistry.applyConfig}. The shift sits between the
     * block offset and the display transform (it moves the display/spin center, not the spin itself), and —
     * being inside the mechanism's rotation — swings with the door. No-op for non-wall-mounted blocks.
     */
    static void applyWallOffset(Matrix4f m, @Nullable Vector3f facing, float wallOffset) {
        if (facing != null && wallOffset != 0f) {
            m.translate(-facing.x * wallOffset, -facing.y * wallOffset, -facing.z * wallOffset);
        }
    }

    private static void checkMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Mechanism methods must be called from the main server thread");
        }
    }
}
