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
    private final Set<UUID> tickWarned = new HashSet<>();  // mechs already warned about a tick throw (rate-limit)

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
        return assembleMechanism(type, blocks, List.of(), pivot, rotationAxis, serializer);
    }

    /**
     * Assemble from world blocks plus synthetic "ghost" blocks — each copies a template block's
     * appearance but sits at a target cell and is <b>not</b> aired out (its cell holds another block,
     * e.g. a piston core's internal pole). Ghosts ride the rigid body and land like normal blocks on
     * disassembly (a protected target cell is skipped). Spawns a new owned ArmorStand vehicle.
     */
    public Mechanism assembleMechanism(String type, List<Block> blocks, List<GhostBlock> ghosts,
                                       Location pivot, Vector3f rotationAxis,
                                       @Nullable MechanismSerializer serializer) {
        UUID mechId = UUID.randomUUID();
        // Spawn the vehicle at the block-CENTERED pivot Y so the display chain (mounted on this
        // ArmorStand) shares the same centered frame as the rotation/collider/disassembly. Otherwise
        // displays sit half a block low (the corner-shift's -0.5 Y would be uncompensated).
        Location vehicleLoc = pivot.clone();
        vehicleLoc.setY(Math.floor(pivot.getY()) + 0.5);
        ArmorStand vehicle = vehicleLoc.getWorld().spawn(vehicleLoc, ArmorStand.class, as -> {
            as.setInvisible(true); as.setGravity(false); as.setSilent(true);
            as.setPersistent(true); as.setRotation(0, 0);
            as.addScoreboardTag("corelib:mech:" + mechId + ":vehicle");
        });
        // We own the vehicle: if assembly throws (before it's registered in activeMechanisms), remove the
        // just-spawned persistent ArmorStand so it isn't orphaned in the world until the next chunk reload.
        try {
            return assembleCore(mechId, type, blocks, ghosts, pivot, rotationAxis, vehicle,
                ARMORSTAND_RIDE_OFFSET, true, serializer);
        } catch (RuntimeException e) {
            vehicle.remove();
            throw e;
        }
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
        return assembleCore(mechId, type, blocks, List.of(), pivot, AXIS_Y, existingVehicle, rideOffset,
            false, serializer);
    }

    /** A synthetic block for {@link #assembleMechanism}: renders {@code template}'s appearance at
     *  {@code target}, without touching whatever real block occupies {@code target}. */
    public record GhostBlock(Location target, Block template) {}

    private Mechanism assembleCore(UUID mechId, String type, List<Block> blocks, List<GhostBlock> ghosts,
                                    Location pivot, Vector3f rotationAxis, Entity vehicle, float rideOffset,
                                    boolean ownsVehicle, @Nullable MechanismSerializer serializer) {
        List<MechanismBlockData> blockData = new ArrayList<>();

        // 1. Snapshot each block
        // Snap the pivot to the nearest block CENTER on all three axes, and make localTransform
        // center-to-center, so the rotation orbits the block's true center about any cardinal axis
        // (load-bearing for X/Z drawbridges; Y doors are unaffected). Offsets stay integer (rotation by
        // 90° maps integers to integers, keeping disassembly's Math.round exact). Compute in double, cast
        // to float only at matrix build — float can't represent the .5 offset past ~8M blocks. The snapped
        // pivot flows downstream (collider spawn, BasicMechanism ctor) so the whole mechanism shares one frame.
        double snapX = Math.floor(pivot.getX()) + 0.5;
        double snapY = Math.floor(pivot.getY()) + 0.5;
        double snapZ = Math.floor(pivot.getZ()) + 0.5;
        pivot = pivot.clone(); // don't mutate the caller's Location
        pivot.setX(snapX);
        pivot.setY(snapY);
        pivot.setZ(snapZ);
        for (Block block : blocks) {
            // A bare shaft can't carry its (rod-based) identity through a mechanism move — revert it
            // to an encased head first, so it's captured/re-placed as a normal skull shaft.
            if (registry.isChainShaft(block)) registry.revertChainShaftToHead(block);
            BlockData bd = block.getBlockData();
            Matrix4f local = new Matrix4f().translation(
                (float) ((block.getX() + 0.5) - snapX),
                (float) ((block.getY() + 0.5) - snapY),
                (float) ((block.getZ() + 0.5) - snapZ));

            String customType = null, customState = null;
            List<CustomHeadBlock.DisplayEntityConfig> decs = null;
            List<CustomHeadBlock.BlockDisplayEntityConfig> bdecs = null;
            CustomHeadBlock.ParticleConfig particles = null;
            Inventory storage = null;
            boolean spinReversed = false;
            Vector3f wallFacing = null;

            CustomHeadBlock chb = registry.getTypeFromBlock(block);
            if (chb != null) {
                customType = chb.fullId();
                customState = registry.getState(block);
                // Capture the neighbour-RESOLVED display transforms (not just the static YAML config) while the
                // block is still live, so a MOVING block renders its real orientation in transit (e.g. a piston
                // head pointing down, or a dynamo's facing) instead of the +axis fallback.
                decs = resolveMovingDisplays(chb, block, customState, chb.resolveDisplayEntities(customState));
                bdecs = chb.resolveBlockDisplayEntities(customState);
                particles = chb.resolveParticles(customState);
                // Capture spin direction + wall facing BEFORE onBlockRemoved() clears the direction.
                spinReversed = registry.isSpinReversed(CustomBlockRegistry.LocationKey.of(block));
                if (block.getType() == Material.PLAYER_WALL_HEAD
                        && bd instanceof org.bukkit.block.data.Directional wallDir) {
                    org.bukkit.util.Vector f = wallDir.getFacing().getDirection();
                    wallFacing = new Vector3f((float) f.getX(), (float) f.getY(), (float) f.getZ());
                }
                if (chb.storage() != null) {
                    // Snapshot the live cached holder (if a pipe/tick/GUI has out-run the PDC) and evict
                    // it — the block is leaving the world. Deep-cloned inside takeStorageSnapshot.
                    storage = registry.takeStorageSnapshot(block);
                }
            } else if (block.getState() instanceof Container c) {
                Inventory orig = c.getInventory();
                storage = Bukkit.createInventory(null, orig.getSize());
                for (int s = 0; s < orig.getSize(); s++) {
                    ItemStack item = orig.getItem(s);
                    if (item != null) storage.setItem(s, item.clone());
                }
            }

            blockData.add(new MechanismBlockData(bd, local, true, 1.0f,
                customType, customState, decs, bdecs, particles, storage, spinReversed, wallFacing));
        }

        // 1b. Snapshot GHOST blocks — synthetic copies of a template's appearance at a target cell.
        // Their localTransform comes from the TARGET (not the template), and they are NOT aired out
        // (the target cell holds another real block, e.g. the piston core). On disassemble they place
        // like normal blocks; a protected target cell is skipped (see BasicMechanism).
        for (GhostBlock ghost : ghosts) {
            Block tmpl = ghost.template();
            BlockData gbd = tmpl.getBlockData();
            Matrix4f glocal = new Matrix4f().translation(
                (float) ((ghost.target().getBlockX() + 0.5) - snapX),
                (float) ((ghost.target().getBlockY() + 0.5) - snapY),
                (float) ((ghost.target().getBlockZ() + 0.5) - snapZ));
            String gType = null, gState = null;
            List<CustomHeadBlock.DisplayEntityConfig> gdecs = null;
            List<CustomHeadBlock.BlockDisplayEntityConfig> gbdecs = null;
            CustomHeadBlock.ParticleConfig gparticles = null;
            Vector3f gwall = null;
            CustomHeadBlock gchb = registry.getTypeFromBlock(tmpl);
            if (gchb != null) {
                gType = gchb.fullId();
                gState = registry.getState(tmpl);
                gdecs = resolveMovingDisplays(gchb, tmpl, gState, gchb.resolveDisplayEntities(gState));
                gbdecs = gchb.resolveBlockDisplayEntities(gState);
                gparticles = gchb.resolveParticles(gState);
                if (tmpl.getType() == Material.PLAYER_WALL_HEAD
                        && gbd instanceof org.bukkit.block.data.Directional wallDir) {
                    org.bukkit.util.Vector f = wallDir.getFacing().getDirection();
                    gwall = new Vector3f((float) f.getX(), (float) f.getY(), (float) f.getZ());
                }
            }
            blockData.add(new MechanismBlockData(gbd, glocal, true, 1.0f,
                gType, gState, gdecs, gbdecs, gparticles, null, false, gwall));
        }

        // Steps 2-3 (tear down custom-block tracking + air out the source blocks) are deferred to AFTER the
        // display spawn — see airOutSourceBlocks(). For owned vehicles the mech displays are mounted and
        // positioned onto the cells FIRST (synchronously), so removing the real blocks leaves no empty frame
        // (the flicker). The snapshot above still runs first, so the capture-order race is unaffected.

        // 4. Spawn display + collider entities
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

            // Additional block-data displays from display_entities config (e.g. a vertical slab's body).
            // Appended AFTER the item extras, so group = [primary, itemExtras…, blockExtras…]; the transform
            // loops in rotate()/updateAnimatedDisplays() index these at base = 1 + itemCount.
            if (mb.blockDisplayEntityConfigs != null) {
                for (int d = 0; d < mb.blockDisplayEntityConfigs.size(); d++) {
                    var bdc = mb.blockDisplayEntityConfigs.get(d);
                    Display extra = spawnMechBlockDisplay(spawnLoc, bdc.blockData(), mechId, i, "block_" + d);
                    if (bdc.interpolationDuration() != 0) {
                        extra.setInterpolationDuration(bdc.interpolationDuration());
                    }
                    group.add(extra);
                }
            }
            displaysPerBlock.add(group);

            // Collider: marker ArmorStand carrier + Shulker passenger
            if (mb.hasCollision) {
                final int blockIdx = i;
                Vector3f initOff = mb.localTransform.getTranslation(new Vector3f());
                // -0.5 Y: the shulker box (attachedFace DOWN, marker, peek 0) sits ~half a block above
                // its carrier, so anchor the carrier at the cell bottom to center the box on the cell.
                Location carrierLoc = pivot.clone().add(initOff.x, initOff.y - 0.5, initOff.z);

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

        // 5. Create mechanism, register colliders
        BasicMechanism mech = new BasicMechanism(mechId, type, pivot, rotationAxis, vehicle, parentDisplay,
            rideOffset, ownsVehicle, displaysPerBlock, colliders, blockData, registry, serializer);
        mech.mechanismRegistry = this;

        for (ColliderPair cp : colliders) {
            colliderIndex.put(cp.shulker().getUniqueId(), new ColliderRef(mech, cp.blockIndex()));
        }

        // 6. Mount displays on parent + set initial transforms, then air out the source blocks.
        if (ownsVehicle) {
            // Owned ArmorStand vehicle: mount + position the displays SYNCHRONOUSLY (this tick), THEN remove
            // the real blocks — so the mech displays already cover the cells before the originals vanish, with
            // no empty frame (the per-move flicker). No delay tick ⇒ no vehicle-death window. Owned ArmorStands
            // accept addPassenger synchronously (the 1-tick defer only ever existed for minecarts, below).
            try {
                for (var group : displaysPerBlock) {
                    for (Display d : group) parentDisplay.addPassenger(d);
                }
                vehicle.addPassenger(parentDisplay);
                mech.rotate(0);
                updateAnimatedDisplays(mech, 0L);
            } catch (RuntimeException e) {
                mech.removeAllEntities();   // drop the just-spawned mech displays/colliders; blocks untouched
                throw e;                    // the owning overload's catch then removes the vehicle
            }
            airOutSourceBlocks(blocks);
        } else {
            // External vehicle (minecart): remove the real blocks now, then defer the mount one tick —
            // minecarts silently reject addPassenger for non-living entities at the NMS level.
            airOutSourceBlocks(blocks);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!vehicle.isValid()) { mech.disassemble(); return; } // vehicle died during the delay tick
                for (var group : displaysPerBlock) {
                    for (Display d : group) parentDisplay.addPassenger(d);
                }
                // Start the parent at the SNAPPED pivot, not the raw vehicle position, which may have drifted
                // during this 1-tick delay (NMS rail physics). updateFromVehicle() maintains it from here.
                Location parentLoc = mech.pivot();
                parentLoc.setYaw(0);
                parentLoc.setPitch(0);
                TeleportCompat.teleport(parentDisplay, parentLoc);
                mech.rotate(0);
                updateAnimatedDisplays(mech, 0L); // place animated displays on the first frame — no 1-tick pop
            }, 1L);
        }

        activeMechanisms.put(mechId, mech);

        // Surface assembly to companion plugins (e.g. the mech advancement system). Single choke
        // point for every assembleMechanism overload; fired on the main thread, informational only.
        boolean verticalAxis = Math.abs(rotationAxis.y) > 0.5f
            && rotationAxis.x == 0f && rotationAxis.z == 0f;
        Bukkit.getPluginManager().callEvent(
            new MechanismAssembleEvent(mech, type, pivot.clone(), mech.blockCount(), verticalAxis));

        return mech;
    }

    /**
     * If {@code chb} has a {@link CustomHeadBlock.DisplayTransformResolver}, return a COPY of {@code decs} with
     * each entry's transform replaced by the resolver's neighbour-aware output — so a MOVING block carries the
     * resolved orientation (e.g. a piston head's outward-facing cap) rather than the static YAML fallback.
     * Returns {@code decs} unchanged when there is no resolver / nothing to resolve. Must be called while the
     * block is still live (before air-out) so the resolver sees real neighbours. Copies the list because the
     * source may be an immutable ({@code List.copyOf}) or a shared cached {@code StateConfig} list — mutating
     * it in place would throw or corrupt every future placement.
     */
    private static List<CustomHeadBlock.DisplayEntityConfig> resolveMovingDisplays(
            CustomHeadBlock chb, Block block, @Nullable String state,
            @Nullable List<CustomHeadBlock.DisplayEntityConfig> decs) {
        if (chb.displayTransformResolver() == null || decs == null || decs.isEmpty()) return decs;
        List<CustomHeadBlock.DisplayEntityConfig> out = new ArrayList<>(decs);
        for (int i = 0; i < out.size(); i++) {
            CustomHeadBlock.DisplayEntityConfig d = out.get(i);
            org.bukkit.util.Transformation resolved = chb.displayTransformResolver().resolve(block, state, d, i);
            if (resolved != null) {
                out.set(i, new CustomHeadBlock.DisplayEntityConfig(d.displayItem(), resolved, d.tagSuffix(),
                    d.animation(), d.interpolationDuration(), d.wallOffset()));
            }
        }
        return out;
    }

    /**
     * Tear down custom-block tracking (which removes each block's OWN display entities) and air out the source
     * blocks. Must run AFTER the snapshot — {@code onBlockRemoved} triggers a synchronous rotation-network
     * recalc that rewrites downstream transmitters {@code spinning_*→idle_*}, so doing it during capture would
     * snapshot later blocks as idle. Two-pass removal handles attachables before their supports.
     */
    private void airOutSourceBlocks(List<Block> blocks) {
        for (Block b : blocks) {
            CustomHeadBlock chb = registry.getTypeFromBlock(b);
            if (chb != null) registry.onBlockRemoved(b, chb);
        }
        for (Block b : blocks) {
            if (FragileBlocks.isAttachable(b.getType())) b.setType(Material.AIR, false);
        }
        for (Block b : blocks) {
            if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
        }
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
            // Isolate each mechanism: a throw from one must not freeze the per-tick update of ALL
            // mechanisms (doors/rotators/minecarts/pistons) until a restart. Warn once per mech.
            try {
                // Auto-follow: update transforms if vehicle moved (e.g., minecart on rails)
                mech.updateFromVehicle();
                updateAnimatedDisplays(mech, currentTick - mech.startTick);
                // TODO: particle ticking for mechanism blocks
            } catch (Throwable t) {
                if (tickWarned.add(mech.id())) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Mechanism " + mech.type() + " (" + mech.id() + ") threw during tick; skipping it", t);
                }
            }
        }
    }

    /**
     * Position every animated auxiliary display of a mechanism for the given (unsigned) tick age.
     * Composition: {@code currentTransform · localTransform · [wallOffset] · animation(decTransform)} — the
     * animation runs in the block-LOCAL frame (so a spin rotates the display about its own axle, not about
     * the mechanism pivot), the captured CW/CCW direction is honored, and {@code rideOffset} is applied to
     * the final translation (parent space), matching the primary display. Called from {@link #tickMechanisms}
     * each tick and once at assembly with {@code tickAge = 0} to place displays on the first frame.
     */
    void updateAnimatedDisplays(BasicMechanism mech, long tickAge) {
        for (int i = 0; i < mech.blockCount(); i++) {
            List<Display> displays = mech.displaysPerBlock.get(i);
            if (displays.isEmpty() || !displays.get(0).isValid()) continue;

            MechanismBlockData mb = mech.blocks.get(i);

            if (mb.displayEntityConfigs != null) {
                for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                    var dec = mb.displayEntityConfigs.get(d);
                    if (dec.animation() == null) continue;
                    int displayIdx = d + 1;
                    if (displayIdx >= displays.size()) continue;
                    Display display = displays.get(displayIdx);
                    if (!display.isValid()) continue;

                    // Animate the display's LOCAL transform (origin = block center), exactly as the standalone
                    // path does — negating the age for a CCW-captured source.
                    long age = mb.spinReversed ? -tickAge : tickAge;
                    dec.animation().apply(BasicMechanism.transformToMatrix(dec.transform()), age, workMatrix);

                    // Place: pivot-rotation · block-offset · [wall offset] · animated-local.
                    // Additional displays are always ItemDisplay (center-rendered) — no XZ shift.
                    Matrix4f placed = new Matrix4f(mech.currentTransform()).mul(mb.localTransform);
                    BasicMechanism.applyWallOffset(placed, mb.wallFacing, dec.wallOffset());
                    placed.mul(workMatrix);
                    placed.m31(placed.m31() - mech.rideOffset); // passenger offset — parent space, applied last
                    display.setTransformationMatrix(placed);
                }
            }

            // Animated BLOCK-data displays, indexed after the item extras. Same composition as above and,
            // like the non-animated block path in rotate(), NO -0.5 corner shift (the authored transform
            // already carries it, matching the static center-spawn).
            if (mb.blockDisplayEntityConfigs != null) {
                int base = 1 + (mb.displayEntityConfigs != null ? mb.displayEntityConfigs.size() : 0);
                for (int d = 0; d < mb.blockDisplayEntityConfigs.size(); d++) {
                    var bdc = mb.blockDisplayEntityConfigs.get(d);
                    if (bdc.animation() == null) continue;
                    int idx = base + d;
                    if (idx >= displays.size()) continue;
                    Display display = displays.get(idx);
                    if (!display.isValid()) continue;

                    long age = mb.spinReversed ? -tickAge : tickAge;
                    bdc.animation().apply(BasicMechanism.transformToMatrix(bdc.transform()), age, workMatrix);

                    Matrix4f placed = new Matrix4f(mech.currentTransform()).mul(mb.localTransform);
                    BasicMechanism.applyWallOffset(placed, mb.wallFacing, bdc.wallOffset());
                    placed.mul(workMatrix);
                    placed.m31(placed.m31() - mech.rideOffset); // passenger offset — parent space, applied last
                    display.setTransformationMatrix(placed);
                }
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

    /** Remove a disassembled owned-vehicle mechanism's display/collider entities one tick later, so the
     *  just-placed blocks' own displays have a frame to render first (avoids the landing flicker). The mech is
     *  already unregistered by the caller, so the lingering entities are never ticked. */
    void deferEntityRemoval(BasicMechanism mech) {
        Bukkit.getScheduler().runTaskLater(plugin, mech::removeAllEntities, 1L);
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
