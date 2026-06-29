package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Manages active mechanisms: assembly, ticking (particles + animations),
 * collider lookup, and cleanup.
 */
public class MechanismRegistry {

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;

    private final Map<UUID, BasicMechanism> activeMechanisms = new HashMap<>();
    private final Map<UUID, ColliderRef> colliderIndex = new HashMap<>(); // shulker UUID → ref

    private @Nullable BukkitTask tickTask;
    private boolean colliderGlowEnabled = false;

    // Reusable work matrix for animation tick
    private final Matrix4f workMatrix = new Matrix4f();

    public MechanismRegistry(JavaPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Assembly
    // ──────────────────────────────────────────────────────────────────────

    /** Default ride offset for ArmorStand vehicles. Empirically tuned (matches BlockShips). */
    public static final float ARMORSTAND_RIDE_OFFSET = 1.975f;

    /** Unit Y axis — the default rotation axis (doors, minecarts). */
    private static final Vector3f AXIS_Y = new Vector3f(0, 1, 0);

    /**
     * Assemble a mechanism from world blocks, spawning a new ArmorStand as the vehicle.
     * Rotates about the vertical (Y) axis.
     */
    public Mechanism assembleMechanism(String type, List<Block> blocks, Location pivot,
                                       @Nullable MechanismSerializer serializer) {
        return assembleMechanism(type, blocks, pivot, AXIS_Y, serializer);
    }

    /**
     * Assemble a mechanism from world blocks, spawning a new ArmorStand as the vehicle, and
     * rotating about an arbitrary cardinal axis (Y = door/turntable, X/Z = drawbridge).
     */
    public Mechanism assembleMechanism(String type, List<Block> blocks, Location pivot,
                                       Vector3f rotationAxis,
                                       @Nullable MechanismSerializer serializer) {
        UUID mechId = UUID.randomUUID();
        ArmorStand vehicle = pivot.getWorld().spawn(pivot, ArmorStand.class, as -> {
            as.setInvisible(true); as.setGravity(false); as.setSilent(true);
            as.setPersistent(true); as.setRotation(0, 0);
            as.addScoreboardTag("corelib:mech:" + mechId + ":vehicle");
        });
        return assembleCore(mechId, type, blocks, pivot, rotationAxis, vehicle,
            ARMORSTAND_RIDE_OFFSET, true, serializer);
    }

    /**
     * Assemble a mechanism from world blocks, using an existing entity as the vehicle.
     * The caller retains ownership of the vehicle entity (it won't be removed on disassemble).
     */
    public Mechanism assembleMechanism(String type, List<Block> blocks, Entity existingVehicle,
                                       float rideOffset, @Nullable MechanismSerializer serializer) {
        UUID mechId = UUID.randomUUID();
        Location pivot = existingVehicle.getLocation();
        existingVehicle.addScoreboardTag("corelib:mech:" + mechId + ":vehicle");
        return assembleCore(mechId, type, blocks, pivot, AXIS_Y, existingVehicle, rideOffset,
            false, serializer);
    }

    private Mechanism assembleCore(UUID mechId, String type, List<Block> blocks, Location pivot,
                                    Vector3f rotationAxis, Entity vehicle, float rideOffset,
                                    boolean ownsVehicle, @Nullable MechanismSerializer serializer) {
        List<MechanismBlockData> blockData = new ArrayList<>();

        // 1. Snapshot each block
        // Snap pivot XZ to the nearest block center so offsets are always integers (rotation by 90°
        // then maps integers to integers, keeping disassembly exact). Compute in double, cast to float
        // only at matrix build — float can't represent the .5 offset past ~8M blocks. The snapped pivot
        // flows downstream (collider spawn, BasicMechanism ctor) so the whole mechanism shares one frame.
        double snapX = Math.floor(pivot.getX()) + 0.5;
        double snapZ = Math.floor(pivot.getZ()) + 0.5;
        pivot = pivot.clone(); // don't mutate the caller's Location
        pivot.setX(snapX);
        pivot.setZ(snapZ);
        for (Block block : blocks) {
            BlockData bd = block.getBlockData();
            Matrix4f local = new Matrix4f().translation(
                (float) ((block.getX() + 0.5) - snapX),
                (float) (block.getY() - pivot.getY()),
                (float) ((block.getZ() + 0.5) - snapZ));

            String customType = null, customState = null;
            List<CustomHeadBlock.DisplayEntityConfig> decs = null;
            CustomHeadBlock.ParticleConfig particles = null;
            Inventory storage = null;

            CustomHeadBlock chb = registry.getTypeFromBlock(block);
            if (chb != null) {
                customType = chb.fullId();
                customState = registry.getState(block);
                decs = chb.resolveDisplayEntities(customState);
                particles = chb.resolveParticles(customState);
                if (chb.storage() != null) {
                    storage = Bukkit.createInventory(null, chb.storage().layout().slots);
                    registry.loadInventoryFromPDC(block, storage);
                    for (int s = 0; s < storage.getSize(); s++) {
                        ItemStack item = storage.getItem(s);
                        if (item != null) storage.setItem(s, item.clone());
                    }
                }
                registry.onBlockRemoved(block, chb);
            } else if (block.getState() instanceof Container c) {
                Inventory orig = c.getInventory();
                storage = Bukkit.createInventory(null, orig.getSize());
                for (int s = 0; s < orig.getSize(); s++) {
                    ItemStack item = orig.getItem(s);
                    if (item != null) storage.setItem(s, item.clone());
                }
            }

            blockData.add(new MechanismBlockData(bd, local, true, 1.0f,
                customType, customState, decs, particles, storage));
        }

        // 2. Two-pass block removal
        for (Block b : blocks) {
            if (FragileBlocks.isAttachable(b.getType())) b.setType(Material.AIR, false);
        }
        for (Block b : blocks) {
            if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
        }

        // 3. Spawn display + collider entities
        Location spawnLoc = pivot.clone().add(0, 2.5, 0);

        // Parent BlockDisplay(AIR): invisible intermediary for multi-passenger support.
        // Minecarts only allow one passenger, so displays mount on parent, parent on vehicle.
        // BlockDisplay→Display passenger offset is zero (confirmed by BlockShips pattern).
        BlockDisplay parentDisplay = pivot.getWorld().spawn(spawnLoc, BlockDisplay.class, d -> {
            d.setBlock(Bukkit.createBlockData(Material.AIR));
            d.setTeleportDuration(0); d.setViewRange(64f);
            d.setPersistent(true); d.setGravity(false);
            d.addScoreboardTag("corelib:mech:" + mechId + ":parent");
        });

        List<List<Display>> displaysPerBlock = new ArrayList<>();
        List<ColliderPair> colliders = new ArrayList<>();

        for (int i = 0; i < blockData.size(); i++) {
            MechanismBlockData mb = blockData.get(i);
            List<Display> group = new ArrayList<>();

            // Primary display
            Display primary;
            if (mb.customTypeId != null) {
                CustomHeadBlock chbType = registry.getType(mb.customTypeId);
                String tex = chbType != null
                    ? chbType.resolveTexture(mb.customState, 0, null)
                    : null;
                ItemStack headItem = tex != null
                    ? HeadUtil.createHead(tex, 1)
                    : new ItemStack(Material.PLAYER_HEAD);
                primary = spawnMechDisplay(spawnLoc, headItem, mechId, i, "display");
            } else {
                primary = spawnMechBlockDisplay(spawnLoc, mb.blockData, mechId, i, "display");
            }
            group.add(primary);

            // Additional displays from display_entities config
            if (mb.displayEntityConfigs != null) {
                for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                    var dec = mb.displayEntityConfigs.get(d);
                    Display extra = spawnMechDisplay(spawnLoc, dec.displayItem().clone(),
                        mechId, i, "extra_" + d);
                    if (dec.interpolationDuration() != 0) {
                        ((ItemDisplay) extra).setInterpolationDuration(dec.interpolationDuration());
                    }
                    group.add(extra);
                }
            }
            displaysPerBlock.add(group);

            // Collider: marker ArmorStand carrier + Shulker passenger
            if (mb.hasCollision) {
                final int blockIdx = i;
                Vector3f initOff = mb.localTransform.getTranslation(new Vector3f());
                Location carrierLoc = pivot.clone().add(initOff.x, initOff.y, initOff.z);

                ArmorStand carrier = pivot.getWorld().spawn(carrierLoc, ArmorStand.class, as -> {
                    as.setInvisible(true); as.setGravity(false); as.setSilent(true);
                    as.setPersistent(true); as.setInvulnerable(true);
                    as.setMarker(true); // zero height → shulker at exact carrier position
                    as.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":carrier");
                });
                Shulker shulker = pivot.getWorld().spawn(carrierLoc, Shulker.class, s -> {
                    s.setAI(false); s.setInvisible(true); s.setGravity(false);
                    s.setSilent(true); s.setPersistent(true);
                    s.setCollidable(true);
                    s.setPeek(0);
                    s.setAttachedFace(org.bukkit.block.BlockFace.DOWN);
                    s.setGlowing(colliderGlowEnabled);
                    s.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":collider");
                });
                carrier.addPassenger(shulker);
                colliders.add(new ColliderPair(carrier, shulker, i));
            }
        }

        // 4. Create mechanism, register colliders
        BasicMechanism mech = new BasicMechanism(mechId, type, pivot, rotationAxis, vehicle, parentDisplay,
            rideOffset, ownsVehicle, displaysPerBlock, colliders, blockData, registry, serializer);
        mech.mechanismRegistry = this;

        for (ColliderPair cp : colliders) {
            colliderIndex.put(cp.shulker().getUniqueId(), new ColliderRef(mech, cp.blockIndex()));
        }

        // 5. 1-tick delay: mount displays on parent, set initial transforms.
        //    For owned vehicles (ArmorStand): parent mounts on vehicle as passenger.
        //    For external vehicles (minecarts): parent is teleported each tick instead,
        //    because minecarts silently reject addPassenger for non-living entities at NMS level.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!vehicle.isValid()) { mech.disassemble(); return; } // vehicle died during the delay tick
            for (var group : displaysPerBlock) {
                for (Display d : group) parentDisplay.addPassenger(d);
            }
            if (ownsVehicle) {
                vehicle.addPassenger(parentDisplay);
            } else {
                // External vehicle (minecart): start the parent at the SNAPPED pivot, not the raw
                // vehicle position, which may have drifted during this 1-tick delay (NMS rail physics).
                // updateFromVehicle() maintains it from here. Zero yaw — rotation is via display matrices.
                Location parentLoc = mech.pivot();
                parentLoc.setYaw(0);
                parentLoc.setPitch(0);
                TeleportCompat.teleport(parentDisplay, parentLoc);
            }
            mech.rotate(0);
        }, 1L);

        activeMechanisms.put(mechId, mech);
        return mech;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick
    // ──────────────────────────────────────────────────────────────────────

    public void startTasks() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMechanisms, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        // Restore real blocks for any still-assembled mechanism (e.g. an open door) so the structure
        // isn't lost on /stop. Per-mechanism guarded: a failure falls back to removeAllEntities so we
        // never leak persistent entities. (Full restart recovery is the deferred persistence work.)
        for (BasicMechanism mech : new ArrayList<>(activeMechanisms.values())) {
            try {
                mech.disassemble();
            } catch (Exception e) {
                plugin.getLogger().warning("Mechanism " + mech.id() + " failed to disassemble on "
                    + "shutdown (" + e.getMessage() + "); removing entities without block restore");
                try {
                    mech.removeAllEntities();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
        activeMechanisms.clear();
        colliderIndex.clear();
    }

    /** Toggle collider debug glow on all active (and future) mechanism shulkers. */
    public void setColliderGlow(boolean enabled) {
        this.colliderGlowEnabled = enabled;
        for (BasicMechanism mech : activeMechanisms.values()) {
            for (ColliderPair cp : mech.colliders) {
                if (cp.shulker().isValid()) cp.shulker().setGlowing(enabled);
            }
        }
    }

    public boolean isColliderGlowEnabled() {
        return colliderGlowEnabled;
    }

    /**
     * Remove orphaned mechanism entities from a chunk. These are entities tagged
     * corelib:mech:* from previous sessions where the mechanism was not properly
     * cleaned up. All mechanism entities have setPersistent(true), so they never
     * despawn naturally — this cleanup prevents permanent entity leaks.
     */
    public void cleanupOrphanedEntities(org.bukkit.Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            for (String tag : entity.getScoreboardTags()) {
                if (!tag.startsWith("corelib:mech:")) continue;
                // Tag format: "corelib:mech:{uuid}:{index}:{role}" or "corelib:mech:{uuid}:{role}"
                // Extract the UUID (3rd colon-separated part, but UUID contains hyphens not colons)
                // Split by ":" gives ["corelib", "mech", "{uuid}", ...]
                String[] parts = tag.split(":");
                if (parts.length < 3) continue;
                try {
                    UUID mechId = UUID.fromString(parts[2]);
                    if (!activeMechanisms.containsKey(mechId)) {
                        entity.remove();
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a valid UUID — might be a different tag format, skip
                }
                break; // only check first matching tag per entity
            }
        }
    }

    private void tickMechanisms() {
        long currentTick = Bukkit.getServer().getCurrentTick();
        for (BasicMechanism mech : activeMechanisms.values()) {
            // Auto-follow: update transforms if vehicle moved (e.g., minecart on rails)
            mech.updateFromVehicle();

            for (int i = 0; i < mech.blockCount(); i++) {
                List<Display> displays = mech.displaysPerBlock.get(i);
                if (displays.isEmpty() || !displays.get(0).isValid()) continue;

                MechanismBlockData mb = mech.blocks.get(i);

                // Animations on additional displays
                if (mb.displayEntityConfigs != null) {
                    for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                        var dec = mb.displayEntityConfigs.get(d);
                        if (dec.animation() == null) continue;
                        int displayIdx = d + 1;
                        if (displayIdx >= displays.size()) continue;
                        Display display = displays.get(displayIdx);
                        if (!display.isValid()) continue;

                        Matrix4f base = new Matrix4f(mech.currentTransform())
                            .mul(mb.localTransform)
                            .mul(BasicMechanism.transformToMatrix(dec.transform()));
                        // Additional displays are always ItemDisplay (center-rendered) — no XZ shift
                        base.m31(base.m31() - mech.rideOffset);
                        long tickAge = currentTick - mech.startTick;
                        dec.animation().apply(base, tickAge, workMatrix);
                        display.setTransformationMatrix(workMatrix);
                    }
                }

                // TODO: particle ticking for mechanism blocks
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Collider lookup
    // ──────────────────────────────────────────────────────────────────────

    record ColliderRef(BasicMechanism mechanism, int blockIndex) {}

    @Nullable ColliderRef getColliderRef(Shulker shulker) {
        return colliderIndex.get(shulker.getUniqueId());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cleanup (called by BasicMechanism on disassemble/destroy)
    // ──────────────────────────────────────────────────────────────────────

    void onMechanismRemoved(BasicMechanism mech) {
        activeMechanisms.remove(mech.id());
        for (ColliderPair cp : mech.colliders) {
            colliderIndex.remove(cp.shulker().getUniqueId());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Entity spawn helpers
    // ──────────────────────────────────────────────────────────────────────

    private ItemDisplay spawnMechDisplay(Location loc, ItemStack item,
                                         UUID mechId, int blockIdx, String role) {
        return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setTeleportDuration(0); d.setShadowRadius(0f); d.setShadowStrength(0f);
            d.setViewRange(64f); d.setPersistent(true); d.setGravity(false);
            d.setInterpolationDuration(2);
            d.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":" + role);
        });
    }

    private BlockDisplay spawnMechBlockDisplay(Location loc, BlockData data,
                                               UUID mechId, int blockIdx, String role) {
        return loc.getWorld().spawn(loc, BlockDisplay.class, d -> {
            d.setBlock(data);
            d.setTeleportDuration(0); d.setShadowRadius(0f); d.setShadowStrength(0f);
            d.setViewRange(64f); d.setPersistent(true); d.setGravity(false);
            d.setInterpolationDuration(2);
            d.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":" + role);
        });
    }
}
