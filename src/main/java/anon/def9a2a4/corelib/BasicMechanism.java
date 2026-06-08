package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
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

import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link Mechanism}.
 * Manages the passenger chain, display transforms, collider positioning,
 * and block restoration on disassembly.
 */
final class BasicMechanism implements Mechanism {

    private final UUID id;
    private final String type;
    private Location pivot;
    private float currentYaw = 0f;
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

    BasicMechanism(UUID id, String type, Location pivot,
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
        this.previousVehicleLoc = pivot.clone();
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
    // Movement
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void rotate(float yaw) {
        checkMainThread();
        this.currentYaw = yaw;
        Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-yaw));
        this.currentTransform = rot;

        for (int i = 0; i < blocks.size(); i++) {
            MechanismBlockData mb = blocks.get(i);
            Matrix4f dm = new Matrix4f(rot).mul(mb.localTransform);
            dm.m31(dm.m31() - rideOffset); // compensate vehicle passenger riding offset

            // Primary display (index 0): BlockDisplay uses corner rendering — shift -0.5 XZ
            // in LOCAL space (post-multiply) so the shift rotates with the block.
            // ItemDisplay renders centered — no XZ shift needed.
            List<Display> group = displaysPerBlock.get(i);
            Display primary = group.get(0);
            if (primary instanceof org.bukkit.entity.BlockDisplay) {
                Matrix4f bdm = new Matrix4f(dm).translate(-0.5f, 0f, -0.5f);
                primary.setTransformationMatrix(bdm);
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
                    Matrix4f extra = new Matrix4f(dm).mul(transformToMatrix(dec.transform()));
                    group.get(displayIdx).setTransformationMatrix(extra);
                }
            }
        }

        // Reposition collider carriers
        for (ColliderPair cp : colliders) {
            Vector3f worldOff = rot.transformPosition(
                blocks.get(cp.blockIndex()).localTransform.getTranslation(new Vector3f()),
                new Vector3f());
            TeleportCompat.teleport(cp.carrier(),
                pivot.clone().add(worldOff.x, worldOff.y, worldOff.z));
        }
    }

    @Override
    public void move(Location position, float yaw) {
        checkMainThread();
        TeleportCompat.teleport(vehicle, position);
        this.pivot = position.clone();
        rotate(yaw);
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
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void disassemble() {
        checkMainThread();
        float snappedYaw = Math.round(currentYaw / 90f) * 90f;
        Matrix4f rotation = new Matrix4f().rotateY((float) Math.toRadians(-snappedYaw));

        for (int i = 0; i < blocks.size(); i++) {
            MechanismBlockData mb = blocks.get(i);
            Vector3f worldOffset = rotation.transformPosition(
                mb.localTransform.getTranslation(new Vector3f()), new Vector3f());
            // localTransform uses center-to-center offsets (integers after rotation).
            // Use Math.round to handle floating-point epsilon from trig (e.g., 1.9999999f → 2).
            Location blockLoc = pivot.clone().add(
                Math.round(worldOffset.x), Math.round(worldOffset.y), Math.round(worldOffset.z));
            Block target = blockLoc.getBlock();

            if (target.getType().isAir() || target.getType() == Material.WATER
                    || target.getType() == Material.LAVA) {
                placeBlock(target, mb, snappedYaw);
            } else if (FragileBlocks.isFragile(target.getType())) {
                target.breakNaturally();
                placeBlock(target, mb, snappedYaw);
            } else {
                // Solid block wins — explosion effect + drop mechanism block as item
                target.getWorld().spawnParticle(Particle.EXPLOSION,
                    blockLoc.clone().add(0.5, 0.5, 0.5), 1);
                dropBlockAsItem(blockLoc, mb);
            }
        }

        removeAllEntities();
        if (mechanismRegistry != null) mechanismRegistry.onMechanismRemoved(this);
        if (serializer != null) serializer.onDisassemble(this);
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
            if (type != null) {
                registry.markBlock(target, type, mb.customState);
                int power = registry.readPower(target, type);
                registry.applyConfig(target, type, mb.customState, power);
                registry.restoreBlock(target, type, mb.customState);
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
        // Guard: distanceSquared throws if worlds differ (e.g., entity teleported cross-world)
        if (!loc.getWorld().equals(previousVehicleLoc.getWorld())) {
            previousVehicleLoc = loc.clone();
            previousVehicleYaw = yaw;
            return;
        }
        double distSq = loc.distanceSquared(previousVehicleLoc);
        boolean moved = distSq > 0.0001 || Math.abs(yaw - previousVehicleYaw) > 0.1f;
        if (!moved) return;

        this.pivot = loc.clone();
        // Teleport parent to follow vehicle (for non-passenger parent, e.g., minecart path)
        if (!ownsVehicle) {
            TeleportCompat.teleport(parent, loc);
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

    private static void checkMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Mechanism methods must be called from the main server thread");
        }
    }
}
