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
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

/**
 * Manages active mechanisms: assembly, ticking (particles + animations),
 * collider lookup, and cleanup.
 */
public class MechanismRegistry {

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final ColliderRegistry colliderRegistry = new ColliderRegistry(); // vanilla block → collider shape
    private final MassRegistry massRegistry = new MassRegistry(); // block → inertial mass (heavier = slower)

    private final Map<UUID, BasicMechanism> activeMechanisms = new HashMap<>();
    private final Map<UUID, ColliderRef> colliderIndex = new HashMap<>(); // shulker UUID → ref
    private final MechanismPersistence persistence; // crash-safe state for opted-in (persisted) mechanisms
    // Mechanisms whose recovery is in flight (adopting their still-loading persistent entities). Guards
    // cleanupOrphanedEntities from reaping those entities before recovery claims them, and bridges the
    // 1-tick deferEntityRemoval window on a restore-to-blocks landing.
    private final Set<UUID> mechIdsBeingRecovered = new HashSet<>();
    // Persisted mechanisms mid-recovery whose entities are still arriving across chunk loads. A large or
    // chunk-straddling structure's displays/colliders/seats can sit in neighbour chunks that load AFTER the
    // pivot chunk; we accumulate across successive EntitiesLoad events and only finalize (build + fire the
    // recovered MechanismAssembleEvent) once complete (found >= entityCount) or the whole chunk footprint is
    // loaded. Mirrors BlockShips' expectedEntityCount / isRecoveryComplete incremental recovery.
    private final Map<UUID, MechanismState> pendingRecoveries = new HashMap<>();
    // Consumer hooks fired at assembly AFTER displays+colliders spawn but BEFORE the source blocks are
    // aired out — the window where the source blocks are still LIVE and the colliders exist (leads: a
    // consumer re-parents leashes from world fences onto the mechanism's collider shulkers). See 3a-ii.
    private final List<java.util.function.BiConsumer<Mechanism, List<Block>>> preAirOutListeners = new ArrayList<>();
    // Single consumer hook for seat spawn/recovery (the consumer configures the seat entity it doesn't own).
    private @Nullable SeatListener seatListener;
    private final Set<UUID> tickWarned = new HashSet<>();  // mechs already warned about a tick throw (rate-limit)

    private @Nullable BukkitTask tickTask;
    private @Nullable BukkitTask flushTask; // 60s async chunk-index flush
    private boolean colliderGlowEnabled = false;
    private boolean dynamicLightsEnabled = true; // tag light-emitting blocks for the optional DynLight plugin
    private boolean scaleWarned = false; // one-time guard for the missing-scale-attribute warning

    // Glue-structure size cap (config rotation.glueMaxSize). Bounds the sticky-closure walk in the
    // landing-glue disconnection prune (GlueManager.rebindLanded) for captured nested anchors — must
    // match the cap authoring used, else the prune can starve a legitimate sticky bridge and over-prune
    // still-connected glue. Set by CoreLibPlugin once GlueManager exists; Integer.MAX_VALUE until then
    // (uncapped = over-prune-safe, just unbounded — the setter always runs at enable).
    private int glueMaxSize = Integer.MAX_VALUE;
    void setGlueMaxSize(int n) { this.glueMaxSize = n; }
    int glueMaxSize() { return glueMaxSize; }

    // Powers rotation parts riding assembled mechanisms (built on assemble, ticked per mech,
    // torn down on removal). Set by CoreLibPlugin once the rotation systems exist; null-safe
    // so bare MechanismRegistry construction (tests, other consumers) keeps working.
    private @Nullable MechanismRotationDriver rotationDriver;

    void setRotationDriver(@Nullable MechanismRotationDriver driver) { this.rotationDriver = driver; }

    // Reports whether a captured block is the in-world anchor of a mechanism that is currently mid-motion
    // (a rotator/door pivot head still in-world during a swing, a piston core mid-stroke, a moving hoist
    // head). Moving such an anchor into an OUTER mechanism would air it out → force-disassemble the inner
    // mechanism → orphan its platform. Set by CoreLibPlugin once the movers exist; null-safe so bare
    // MechanismRegistry construction (tests, other consumers) keeps working. Gluing an idle anchor stays
    // allowed — only a mid-motion anchor is refused, and only at capture time.
    private @Nullable Predicate<Block> anchorInMotion;

    void setAnchorInMotion(@Nullable Predicate<Block> predicate) {
        this.anchorInMotion = predicate;
    }

    /** The first block in {@code blocks} that is a mid-motion mechanism anchor, or null if none — a mover
     *  calls this on its FINAL captured list before any side effect and refuses the move if non-null. */
    @Nullable Block firstMovingCapturedAnchor(List<Block> blocks) {
        if (anchorInMotion == null) return null;
        for (Block b : blocks) {
            if (anchorInMotion.test(b)) return b;
        }
        return null;
    }

    // Lets BetterBanners displays (flag/large/huge/bed — standalone ItemDisplays keyed to a host
    // block's coords) ride assembled mechanisms and re-attach on landing. Set by CoreLibPlugin;
    // null-safe so bare MechanismRegistry construction keeps working. Always on when set — banner
    // riding is core behavior, independent of the bbanners plugin's placement gate.
    private @Nullable BannerManager bannerManager;

    void setBannerManager(@Nullable BannerManager bm) { this.bannerManager = bm; }

    @Nullable BannerManager bannerManager() { return bannerManager; }

    // Reusable work matrix for animation tick — holds animation().apply(...)'s OUTPUT.
    private final Matrix4f workMatrix = new Matrix4f();
    // Second reusable scratch for updateAnimatedDisplays: first the transformToMatrix dest (the `base` fed to
    // animation.apply — dead once apply() returns), then overwritten via .set() into the `placed` accumulator.
    // Must stay distinct from workMatrix (read at placed.mul(workMatrix)). Main-thread + sequential → safe to share.
    private final Matrix4f placedMatrix = new Matrix4f();

    public MechanismRegistry(JavaPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.persistence = new MechanismPersistence(plugin);
    }

    /**
     * Opt a mechanism into crash-safe persistence: writes its state to disk now (and future saves),
     * so on shutdown/world-unload it is saved-and-left rather than disassembled, then recovered on
     * chunk load. Call after assembly. No-op for a non-{@link BasicMechanism}.
     */
    public void persist(Mechanism mech) {
        if (!(mech instanceof BasicMechanism bm)) return;
        bm.setPersisted(true);
        persistence.save(bm.snapshotState());
    }

    MechanismPersistence persistence() { return persistence; }

    /**
     * Register a hook fired during assembly AFTER the mechanism's displays + collider shulkers are spawned
     * but BEFORE its source blocks are removed from the world — the only window where both the live source
     * blocks and the colliders coexist. A consumer uses it to move live-block-attached state onto the
     * mechanism (e.g. re-parent leashes from world fences onto the collider shulkers). Filter by
     * {@code mech.type()}. <b>Must not yield the tick</b> (no scheduling): the source blocks are aired out
     * synchronously on the same tick, so a deferred hook would run against already-removed blocks.
     */
    @org.jetbrains.annotations.ApiStatus.Experimental
    public void addPreAirOutListener(java.util.function.BiConsumer<Mechanism, List<Block>> listener) {
        preAirOutListeners.add(listener);
    }

    /**
     * Register the (single) seat listener — fired when a seat's shulker becomes ridable (fresh
     * {@link Mechanism#designateSeat} or crash recovery re-adopting a seat-tagged collider). {@code null}
     * clears it. See {@link SeatListener}.
     */
    @org.jetbrains.annotations.ApiStatus.Experimental
    public void setSeatListener(@Nullable SeatListener listener) {
        this.seatListener = listener;
    }

    /** Fire onSeatSpawned (called from {@link BasicMechanism#designateSeat}). Isolated so a bad hook can't
     *  break assembly/designation. */
    void fireSeatSpawned(Mechanism mech, int seatIndex, Shulker seat) {
        if (seatListener == null) return;
        try {
            seatListener.onSeatSpawned(mech, seatIndex, seat);
        } catch (Exception e) {
            plugin.getLogger().warning("SeatListener.onSeatSpawned threw for " + mech.type()
                + " seat " + seatIndex + " (" + e.getMessage() + ")");
        }
    }

    /** Fire onSeatRecovered (called from {@link #recoverOne}). */
    void fireSeatRecovered(Mechanism mech, int seatIndex, Shulker seat) {
        if (seatListener == null) return;
        try {
            seatListener.onSeatRecovered(mech, seatIndex, seat);
        } catch (Exception e) {
            plugin.getLogger().warning("SeatListener.onSeatRecovered threw for " + mech.type()
                + " seat " + seatIndex + " (" + e.getMessage() + ")");
        }
    }

    /** Fire the pre-air-out listeners (source blocks still live). Isolated so one bad hook can't abort assembly. */
    private void firePreAirOut(Mechanism mech, List<Block> sourceBlocks) {
        for (var listener : preAirOutListeners) {
            try {
                listener.accept(mech, sourceBlocks);
            } catch (Exception e) {
                plugin.getLogger().warning("pre-air-out listener threw for mechanism " + mech.type()
                    + " (" + e.getMessage() + "); continuing assembly");
            }
        }
    }

    /** Live snapshot of the currently-assembled mechanisms (a copy — safe to iterate). Lets a consumer/demo
     *  look one up after crash recovery repopulated the registry from disk (the in-memory handles are gone). */
    public java.util.List<Mechanism> activeMechanisms() {
        return new java.util.ArrayList<>(activeMechanisms.values());
    }

    /** The live (in-memory, currently-assembled) mechanism with this id, or null if none is active. Lets a
     *  consumer test recoverability of an entity it references by id without scanning {@link #activeMechanisms()}.
     *  NOTE: null here does NOT mean "gone forever" — a persisted-but-not-yet-recovered mechanism is absent from
     *  this map until its chunk's EntitiesLoad recovery runs. */
    public @Nullable Mechanism byId(UUID id) {
        return activeMechanisms.get(id);
    }

    /** Load vanilla-block collider shapes (colliders.yml) into the registry. */
    public void loadColliders(java.io.InputStream in) {
        colliderRegistry.load(in, plugin.getLogger());
    }

    /** Load per-block inertial masses (mass.yml) into the registry. */
    public void loadMasses(java.io.InputStream in) {
        massRegistry.load(in, plugin.getLogger());
    }

    /** Inertial mass of a mechanism block (custom-type mass wins over its material). A block-free /
     *  standalone display part (a decorative banner/head ItemDisplay, P7.B) has no inertial mass. */
    double massOf(MechanismBlockData mb) {
        if (mb.blockData == null) return 0;
        return massRegistry.get(mb.blockData.getMaterial(), mb.customTypeId);
    }

    /**
     * The {@link org.bukkit.event.inventory.InventoryType} a vanilla container block opens, for paths that
     * have a material but no live {@code BlockState} to ask (recovery rebuild). Returns {@code CHEST} for
     * chest-shaped and unrecognised containers, which {@link #createTypedInventory} then sizes rather than
     * types — so the only entries that matter here are the fixed-shape ones whose slot count is NOT a
     * multiple of 9 and would otherwise blow up the size-based overload.
     */
    static org.bukkit.event.inventory.InventoryType containerTypeOf(@Nullable Material m) {
        if (m == null) return org.bukkit.event.inventory.InventoryType.CHEST;
        // Shulker box (any dye): 27 slots so the CHEST size-branch wouldn't THROW, but it should still open as a
        // shulker GUI rather than a chest. One tag check covers the undyed + all 16 coloured variants.
        if (org.bukkit.Tag.SHULKER_BOXES.isTagged(m)) return org.bukkit.event.inventory.InventoryType.SHULKER_BOX;
        return switch (m) {
            case HOPPER -> org.bukkit.event.inventory.InventoryType.HOPPER;
            case DROPPER -> org.bukkit.event.inventory.InventoryType.DROPPER;
            case DISPENSER -> org.bukkit.event.inventory.InventoryType.DISPENSER;
            case FURNACE -> org.bukkit.event.inventory.InventoryType.FURNACE;
            case BLAST_FURNACE -> org.bukkit.event.inventory.InventoryType.BLAST_FURNACE;
            case SMOKER -> org.bukkit.event.inventory.InventoryType.SMOKER;
            case BREWING_STAND -> org.bukkit.event.inventory.InventoryType.BREWING;
            // 9 slots, so it never threw — but a carried crafter should open as its 3×3 grid, not a row.
            case CRAFTER -> org.bukkit.event.inventory.InventoryType.CRAFTER;
            default -> org.bukkit.event.inventory.InventoryType.CHEST;
        };
    }

    /**
     * Create a storage inventory that PRESERVES the container's GUI type. Fixed-shape types (hopper 5,
     * dropper/dispenser 3×3, furnace/smoker/blast-furnace, brewing, shulker box, crafter) are created by
     * {@link org.bukkit.event.inventory.InventoryType} so they open the correct GUI; CHEST/BARREL/generic
     * use {@code size} — {@code InventoryType.CHEST} is fixed at 27 and so cannot express a prefab cargo
     * hold declared larger. Shared by world-container capture, recovery rebuild, and the block-free
     * storage descriptor.
     */
    static Inventory createTypedInventory(org.bukkit.inventory.@Nullable InventoryHolder holder,
                                          org.bukkit.event.inventory.InventoryType type, int size,
                                          net.kyori.adventure.text.@Nullable Component title) {
        boolean typed = switch (type) {
            case HOPPER, DROPPER, DISPENSER, FURNACE, BLAST_FURNACE, SMOKER, BREWING, SHULKER_BOX,
                 CRAFTER -> true;
            default -> false;
        };
        if (typed) {
            return title != null ? Bukkit.createInventory(holder, type, title)
                                 : Bukkit.createInventory(holder, type);
        }
        return title != null ? Bukkit.createInventory(holder, size, title)
                             : Bukkit.createInventory(holder, size);
    }

    /** Inertial mass of a plain material (for consumers scanning world blocks, e.g. the hoist load). */
    double massOf(Material material) {
        return massRegistry.get(material, null);
    }

    /** Warn once if a sub-cube collider is requested but the scale attribute can't be applied. */
    private void warnScaleUnavailable() {
        if (scaleWarned) return;
        scaleWarned = true;
        plugin.getLogger().warning("generic.scale attribute unavailable — sub-cube mechanism colliders "
            + "(slabs, heads, fences, ...) will collide as full blocks. Requires MC 1.20.5+.");
    }

    /**
     * Wall-mounted head/skull colliders: shift the ≤0.5 box toward the wall + up so it sits where the
     * head renders instead of floating at the cell centre. Mirrors BlockShips' BlockStructureScanner
     * wall-skull special case. The offset is block-local (rotates with the block via the reposition
     * path). Gated on size ≤ 0.5 so a dragon head's full-block collider stays centred (shifting a full
     * box toward the wall would push it out of the cell). The shift is ADDED to any author-supplied
     * offset so a custom wall-head block keeps its configured offset.
     */
    private static CollisionConfig applyWallHeadShift(CollisionConfig collision, BlockData bd) {
        if (collision.size() <= 0.5f && bd instanceof org.bukkit.block.data.Directional dir) {
            String name = bd.getMaterial().name();
            if (name.endsWith("_WALL_HEAD") || name.endsWith("_WALL_SKULL")) {
                org.bukkit.util.Vector f = dir.getFacing().getDirection();
                Vector3f shifted = new Vector3f(collision.offset())
                    .add(-(float) f.getX() * 0.25f, 0.25f, -(float) f.getZ() * 0.25f);
                return new CollisionConfig(true, collision.size(), shifted);
            }
        }
        return collision;
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
            // Marker (zero-height) so the mounted display chain sits AT the pivot cell rather than ~1.975 up
            // at the passenger-attachment height — a Display samples block-light at its entity Location, so
            // this makes the moving structure lit by the block it renders on. Ride offset drops to 0 below;
            // the collider carriers (separate marker ArmorStands, teleported) are unaffected.
            as.setMarker(true);
            as.addScoreboardTag("corelib:mech:" + mechId + ":vehicle");
        });
        // We own the vehicle: if assembly throws (before it's registered in activeMechanisms), remove the
        // just-spawned persistent ArmorStand so it isn't orphaned in the world until the next chunk reload.
        try {
            // rideOffset 0: the marker vehicle mounts the display chain at the pivot cell (see above), so
            // rotate()/updateAnimatedDisplays have no passenger offset to compensate. If a marker's true
            // passenger offset turns out non-zero on this platform, set it to that measured residual.
            return assembleCore(mechId, type, blocks, ghosts, pivot, rotationAxis, vehicle,
                0f, true, false, serializer);
        } catch (RuntimeException e) {
            vehicle.remove();
            throw e;
        }
    }

    /**
     * Assemble a mechanism from world blocks, using an existing entity as the vehicle.
     * The caller retains ownership of the vehicle entity (it won't be removed on disassemble).
     * Classic vehicle-driven mode (the vehicle follows its own physics, e.g. a minecart on rails).
     */
    public Mechanism assembleMechanism(String type, List<Block> blocks, Entity existingVehicle,
                                       float rideOffset, @Nullable MechanismSerializer serializer) {
        return assembleMechanism(type, blocks, existingVehicle, rideOffset, false, serializer);
    }

    /**
     * Assemble a mechanism on an existing caller-owned vehicle, optionally in DRIVEN mode.
     *
     * <p>When {@code driven} is true the consumer positions the vehicle itself each tick (via
     * {@link Mechanism#repositionDriven}); the mechanism uses the SYNCHRONOUS (owned-style)
     * parent→vehicle passenger mount — safe for an ArmorStand — and the per-tick vehicle auto-follow
     * ({@code updateFromVehicle}) is skipped. When false this is the classic vehicle-driven path
     * (minecart on rails: deferred mount + auto-follow). The caller keeps the vehicle either way.
     */
    public Mechanism assembleMechanism(String type, List<Block> blocks, Entity existingVehicle,
                                       float rideOffset, boolean driven,
                                       @Nullable MechanismSerializer serializer) {
        UUID mechId = UUID.randomUUID();
        Location pivot = existingVehicle.getLocation();
        String vehicleTag = "corelib:mech:" + mechId + ":vehicle";
        existingVehicle.addScoreboardTag(vehicleTag);
        // We don't own the vehicle (caller keeps it), but the tag is ours: strip it if assembly throws
        // before the mechanism is registered, so a foreign entity isn't left carrying a stale vehicle tag.
        try {
            return assembleCore(mechId, type, blocks, List.of(), pivot, AXIS_Y, existingVehicle, rideOffset,
                false, driven, serializer);
        } catch (RuntimeException e) {
            existingVehicle.removeScoreboardTag(vehicleTag);
            throw e;
        }
    }

    /**
     * A single member of a block-free, model-driven {@link #assembleFromParts} assembly — pure data, unlike a
     * world {@link Block}. {@code blockData} is the block appearance (null for a standalone display part, e.g.
     * a banner/head ItemDisplay); {@code localTransform} is an arbitrary 4×4 (translation/rotation/scale/shear)
     * relative to the vehicle origin; {@code collision} is the collider ({@link CollisionConfig#NONE} for none)
     * — NOTE the collider honors only the transform's <em>translation and scale</em>: a shulker box is
     * axis-aligned (not rotatable/shearable), so a rotated or sheared part renders correctly (displays honor the
     * full 4×4) but gets a grid-aligned hitbox that won't match the visual (clip/bump mismatch, no crash), and
     * two off-grid parts can share a floored cell index. Only affects genuinely tilted/sheared parts; normal
     * grid-aligned blocks are exact;
     * {@code displayItem}/{@code displayMode} render a standalone display part (ignored when {@code blockData}
     * is set). {@code storageType}/{@code storageSize}/{@code storageTitle} give a block part a typed, named
     * cargo inventory (null {@code storageType} = no storage). Seats are designated by the consumer
     * post-assembly via {@link Mechanism#designateSeat}.
     */
    public record PartSpec(@Nullable BlockData blockData, Matrix4f localTransform, CollisionConfig collision,
                           @Nullable ItemStack displayItem,
                           @Nullable ItemDisplayTransform displayMode,
                           org.bukkit.event.inventory.@Nullable InventoryType storageType,
                           int storageSize, @Nullable String storageTitle) {
        /** A plain block part: a block appearance + collider, no standalone display, no storage. */
        public static PartSpec block(BlockData blockData, Matrix4f localTransform, CollisionConfig collision) {
            return new PartSpec(blockData, localTransform, collision, null, null, null, 0, null);
        }
        /** A block part with a typed, named storage inventory (a prefab ship's cargo container). The engine
         *  builds the inventory at assembly via {@link #createTypedInventory} and persists it, so cargo
         *  survives moves and crash recovery. {@code storageSize} is used only for size-based GUIs
         *  (CHEST/BARREL, e.g. 54 for a double chest); fixed-shape types size themselves. */
        public static PartSpec block(BlockData blockData, Matrix4f localTransform, CollisionConfig collision,
                                     org.bukkit.event.inventory.@Nullable InventoryType storageType,
                                     int storageSize, @Nullable String storageTitle) {
            return new PartSpec(blockData, localTransform, collision, null, null,
                storageType, storageSize, storageTitle);
        }
        /** A standalone display part: an ItemDisplay ({@code item}, {@code mode}) with no backing block. */
        public static PartSpec display(ItemStack item, ItemDisplayTransform mode,
                                       Matrix4f localTransform, CollisionConfig collision) {
            return new PartSpec(null, localTransform, collision, item, mode, null, 0, null);
        }
    }

    /**
     * Assemble a mechanism from an in-memory {@link PartSpec} list with NO world blocks — for a structure
     * that was never placed in the world (a prefab ship built from a model file, a scripted display rig).
     * Nothing is read from or aired out of the world; block parts render/collide exactly like a scanned
     * block. The mechanism is DRIVEN (the consumer positions {@code vehicle} each tick via
     * {@link Mechanism#repositionDriven}) and {@code blockFree} (teardown removes its entities without
     * restoring world blocks). Seats are designated by the consumer afterwards
     * ({@link Mechanism#designateSeat}). The pivot is the vehicle's raw location (no block-center snap):
     * parts carry arbitrary off-grid transforms authored relative to the vehicle origin.
     *
     * <p>Standalone display parts (null {@code blockData}) are supported: they render as their descriptor's
     * {@code displayItem}/{@code displayMode} ItemDisplay via the null-{@code blockData} branch of the spawn
     * selector, and every block-data dereference (mass, block-light, world-cell) skips them.
     */
    public Mechanism assembleFromParts(String type, List<PartSpec> parts, Entity vehicle, float rideOffset) {
        UUID mechId = UUID.randomUUID();
        Location pivot = vehicle.getLocation();
        String vehicleTag = "corelib:mech:" + mechId + ":vehicle";
        vehicle.addScoreboardTag(vehicleTag);
        try {
            List<MechanismBlockData> blockData = new ArrayList<>(parts.size());
            for (PartSpec p : parts) {
                MechanismBlockData mb = new MechanismBlockData(p.blockData(), new Matrix4f(p.localTransform()),
                    p.collision(), null, null, null, null, null, null, false, null);
                if (p.blockData() == null) {
                    // Standalone display part: no backing block — carry the ItemDisplay descriptor so the
                    // spawn selector renders it (and recovery re-adopts the persistent entity by tag).
                    mb.displayItem = p.displayItem();
                    mb.displayMode = p.displayMode();
                }
                if (p.storageType() != null) {
                    // Typed, named cargo inventory for a prefab container part (no world block to capture
                    // from). snapshotState persists storage.getType()/storageTitle so it survives recovery,
                    // where rebuildBlocks re-derives the GUI shape from the saved type.
                    net.kyori.adventure.text.Component title = p.storageTitle() != null
                        ? net.kyori.adventure.text.Component.text(p.storageTitle()) : null;
                    mb.storage = createTypedInventory(null, p.storageType(), p.storageSize(), title);
                    mb.storageTitle = p.storageTitle();
                }
                blockData.add(mb);
            }
            return spawnMechanismEntities(mechId, type, blockData, pivot, AXIS_Y, vehicle, rideOffset,
                false, true, null, List.of(), List.of(), parts.size(), true);
        } catch (RuntimeException e) {
            vehicle.removeScoreboardTag(vehicleTag);
            throw e;
        }
    }

    /**
     * A synthetic block for {@link #assembleMechanism}: renders an appearance at {@code target} without
     * touching whatever real block occupies {@code target}.
     *
     * <p>Give it a {@code template} <b>Block</b> when the ghost should mirror a real block that already
     * exists, including its custom-head identity/state/displays — the piston's internal pole copies a real
     * pole that way. Give it plain {@code data} when there is no such block to point at: the chain hoist's
     * new link is conjured from an item in its storage, and at extension 0 there is no chain in the world
     * to template from. Exactly one of the two is set.
     */
    public record GhostBlock(Location target, @Nullable Block template, @Nullable BlockData data) {
        public GhostBlock(Location target, Block template) { this(target, template, null); }
        public GhostBlock(Location target, BlockData data) { this(target, null, data); }
    }

    private Mechanism assembleCore(UUID mechId, String type, List<Block> blocks, List<GhostBlock> ghosts,
                                    Location pivot, Vector3f rotationAxis, Entity vehicle, float rideOffset,
                                    boolean ownsVehicle, boolean driven,
                                    @Nullable MechanismSerializer serializer) {
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
        // Live BetterBanners world displays captured below. Removed only AFTER airOutSourceBlocks
        // succeeds — any assembly throw before that leaves the world banners (and their items) intact.
        List<Display> capturedWorldBanners = new ArrayList<>();
        for (Block block : blocks) {
            // A bare block WITH a revert handler (the shaft) can't carry its identity through a move as
            // bare, so revert it to an encased head first. A bare block WITHOUT one (the casing) is
            // captured natively — getTypeFromBlock still resolves it, and placeBlock re-lands it bare.
            // Remember whether it was bare so disassembly can re-bare it on landing (rebareAfterLanding).
            boolean wasBare = registry.revertBareBlockForCapture(block);
            BlockData bd = block.getBlockData();
            // Waterlog is destination-authoritative, never inherited from the source (mirrors BlockShips):
            // strip it at capture so the moving/landed block is dry, and re-derive it at the destination in
            // placeBlock (a block landing into a water source re-waterlogs). Avoids carrying "wet" into a dry
            // cell and self-heals stale saved data.
            if (bd instanceof org.bukkit.block.data.Waterlogged wlSrc && wlSrc.isWaterlogged()) {
                bd = bd.clone();
                ((org.bukkit.block.data.Waterlogged) bd).setWaterlogged(false);
            }
            // A double chest is always split into two independent SINGLE chests. The capture below takes
            // each half's own 27 slots, so a landed pair that re-formed a double would present one 54-slot
            // GUI over two separate inventories — the asymmetry this split exists to remove. Also fixes the
            // in-transit BlockDisplay rendering a visibly half-a-double-chest model.
            // instanceof, NOT a Material switch: ten materials map to Chest (plain, trapped, and the four
            // copper tiers plus their waxed variants — colliders.yml/mass.yml already carry copper chests).
            if (bd instanceof org.bukkit.block.data.type.Chest chestData
                    && chestData.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                bd = bd.clone();
                ((org.bukkit.block.data.type.Chest) bd).setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
            }
            Matrix4f local = new Matrix4f().translation(
                (float) ((block.getX() + 0.5) - snapX),
                (float) ((block.getY() + 0.5) - snapY),
                (float) ((block.getZ() + 0.5) - snapZ));

            String customType = null, customState = null;
            List<CustomHeadBlock.DisplayEntityConfig> decs = null;
            List<CustomHeadBlock.BlockDisplayEntityConfig> bdecs = null;
            CustomHeadBlock.ParticleConfig particles = null;
            Inventory storage = null;
            String storageTitleJson = null;
            boolean spinReversed = false;
            Vector3f wallFacing = null;
            Float floorHeadYaw = null;

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
                } else if (!wasBare && block.getType() == Material.PLAYER_HEAD
                        && bd instanceof org.bukkit.block.data.Rotatable rot) {
                    // Floor head: capture the skull's 16-step yaw so the moving ItemDisplay renders it (the
                    // display spawns unrotated). !wasBare excludes a bare shaft reverted to an encased head —
                    // it renders as a CHAIN BlockDisplay in transit, never the head ItemDisplay.
                    floorHeadYaw = floorHeadYawRadians(rot);
                }
                if (chb.storage() != null) {
                    // Snapshot the live cached holder (if a pipe/tick/GUI has out-run the PDC) and evict
                    // it — the block is leaving the world. Deep-cloned inside takeStorageSnapshot.
                    storage = registry.takeStorageSnapshot(block);
                }
            } else if (block.getState() instanceof Container c) {
                // Vanilla containers only: this is the non-custom branch, so a custom block's REAL
                // tile inventory is never snapshotted here. That invariant is what keeps a locked
                // container (e.g. the dynamo's comparator-drive barrel) safe when a glued structure
                // carries it — its plugin-owned filler is wiped by onBlockRemoved on pickup (via
                // airOutSourceBlocks) and refilled by its own tick on landing, never copied/restored.
                // A DOUBLE chest's getInventory() returns the COMBINED 54-slot inventory, and a mechanism
                // scans BOTH halves — so each half would snapshot all 54 items and restore them, duplicating
                // the cargo. getBlockInventory() returns just THIS chest block's own 27 slots (identical to
                // getInventory() for a single chest). Other containers have no double variant, so plain
                // getInventory() is correct for them.
                Inventory orig = (c instanceof org.bukkit.block.Chest chest) ? chest.getBlockInventory()
                                                                             : c.getInventory();
                // Carry a renamed container's name onto the in-flight GUI, so a chest labelled "Cargo"
                // still reads "Cargo" while it is flying. The LANDED block gets its name back separately,
                // from the block-entity snapshot (bs_name) — this is the in-transit half, which has no
                // other home. GSON-serialized so colours survive; see storageTitleJson.
                net.kyori.adventure.text.Component containerName = c.customName();
                if (containerName != null) {
                    storageTitleJson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                        .gson().serialize(containerName);
                }
                // Preserve the container's GUI TYPE (hopper 5, dropper/dispenser 3×3, furnace 3, …) — not
                // just its size — so a moved/recovered container opens its real inventory, not a chest.
                storage = createTypedInventory(null, orig.getType(), orig.getSize(), containerName);
                for (int s = 0; s < orig.getSize(); s++) {
                    ItemStack item = orig.getItem(s);
                    if (item != null) storage.setItem(s, item.clone());
                }
                // A10b: close any open view before the source block is aired out, else the viewer aliases
                // a container whose contents were copied into the mechanism → duplicate on extract.
                // `orig` is this half's live block-entity inventory for a double chest, and that is still
                // the right list to close from: CompoundContainer#onOpen propagates to BOTH halves as well
                // as its own list, so a player viewing the combined GUI appears in each half's viewers.
                // (Do not "simplify" this to getSnapshotInventory() — a snapshot is a detached copy whose
                // viewer list is always empty, which would silently disable this guard.)
                for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(orig.getViewers())) {
                    viewer.closeInventory();
                }
            }

            // Head orientation for a NON-registry vanilla skull (registered heads captured it above). A
            // PLAYER_WALL_HEAD's facing / PLAYER_HEAD's 16-step yaw, so the moving textured ItemDisplay (spawned
            // below from the captured skull profile) renders at the right orientation — mirrors the registered-
            // head path and BlockShips' native applySkullTransform. Guarded so it never overrides the chb capture.
            if (wallFacing == null && floorHeadYaw == null) {
                if (block.getType() == Material.PLAYER_WALL_HEAD
                        && bd instanceof org.bukkit.block.data.Directional wallDir) {
                    org.bukkit.util.Vector f = wallDir.getFacing().getDirection();
                    wallFacing = new Vector3f((float) f.getX(), (float) f.getY(), (float) f.getZ());
                } else if (!wasBare && block.getType() == Material.PLAYER_HEAD
                        && bd instanceof org.bukkit.block.data.Rotatable rot) {
                    floorHeadYaw = floorHeadYawRadians(rot);
                }
            }

            // Resolve the collider: a custom block's own config wins, else the vanilla shape registry,
            // else a full block (colliderRegistry.get returns DEFAULT for unlisted materials).
            CollisionConfig customCollision = chb != null ? chb.resolveCollision(customState) : null;
            CollisionConfig collision = customCollision != null
                ? customCollision : colliderRegistry.get(bd.getMaterial(), bd);
            collision = applyWallHeadShift(collision, bd);
            MechanismBlockData mbd = new MechanismBlockData(bd, local, collision,
                customType, customState, decs, bdecs, particles, storage, spinReversed, wallFacing);
            mbd.storageTitleJson = storageTitleJson;   // named world container: keeps its name on the in-flight GUI
            mbd.wasBare = wasBare;   // re-bared on landing (rebareAfterLanding) so a carried bare shaft stays bare
            mbd.throttleLevel = registry.throttleLevelAt(block);   // chunk-PDC level (not tile) — carried in the field
            mbd.floorHeadYaw = floorHeadYaw;   // rendered in transit by BasicMechanism.rotate(); null unless a floor head

            // Banner attachments: BetterBanners displays hosted on this block, plus a synthesized
            // entry carrying a vanilla banner block's patterns (its block-entity NBT is otherwise
            // lost, and its primary BlockDisplay renders nothing in transit).
            List<BannerAttachment> banners = null;
            if (bannerManager != null) {
                BannerManager.CapturedBanners cap = bannerManager.captureForMechanism(block);
                if (cap != null) {
                    banners = new ArrayList<>(cap.attachments());
                    capturedWorldBanners.addAll(cap.worldDisplays());
                }
            }
            if (chb == null && block.getState() instanceof org.bukkit.block.Banner bannerState) {
                BannerAttachment blockBanner = vanillaBannerAttachment(bannerState, bd);
                if (blockBanner != null) {
                    if (banners == null) banners = new ArrayList<>();
                    banners.add(blockBanner);
                }
            }
            mbd.banners = banners;
            // Preserve authored glue offsets so a captured anchor (e.g. a hoist carried by a rotator)
            // keeps and reorients its glued region on landing. Read from the live skull PDC BEFORE
            // air-out; null for non-anchor blocks. Only custom head blocks carry a skull PDC.
            if (chb != null) mbd.glueOffsets = new BlockAnchor(block, () -> true).readOffsets();
            // Flush any live per-block state kept in a side store (engine fuel lives in an in-memory manager,
            // not the PDC) into this block's tile PDC NOW — before the configPdc snapshot below — so the move
            // carries it. Runs before air-out's onBlockRemoved, which would otherwise discard the counter.
            if (chb != null && chb.onCapture() != null) chb.onCapture().accept(block);
            // Snapshot the whole tile PDC (BEFORE air-out) so per-block config — a rotator's angle, a
            // dynamo's mode, a burner's fuel — survives the move; re-applied (filtered) on landing.
            if (block.getState() instanceof org.bukkit.block.TileState tile) {
                try {
                    mbd.configPdc = tile.getPersistentDataContainer().serializeToBytes();
                } catch (java.io.IOException ignored) {
                    // Unserializable PDC: skip config carry-over (block still moves + restores normally).
                }
            }
            // Decorated block-entity state (sign text, skull profile, container name, …) via registered
            // BlockSnapshotProviders — captured HERE while the block is still live, re-applied in placeBlock.
            // Registry-first: a corelib custom head owns its own identity/state/texture (restoreBlock), so the
            // provider only supplements NON-registry blocks — else it would double-restore (or freeze an
            // animated head at its captured frame).
            if (chb == null) mbd.blockEntitySnapshot = registry.captureBlockSnapshot(block);
            blockData.add(mbd);
        }

        // 1b. Snapshot GHOST blocks — synthetic copies of a template's appearance at a target cell.
        // Their localTransform comes from the TARGET (not the template), and they are NOT aired out
        // (the target cell holds another real block, e.g. the piston core). On disassemble they place
        // like normal blocks; a protected target cell is skipped (see BasicMechanism).
        for (GhostBlock ghost : ghosts) {
            Block tmpl = ghost.template();   // null for a data-only ghost (no real block to mirror)
            BlockData gbd = tmpl != null ? tmpl.getBlockData() : ghost.data();
            if (gbd instanceof org.bukkit.block.data.Waterlogged wlG && wlG.isWaterlogged()) {
                gbd = gbd.clone();
                ((org.bukkit.block.data.Waterlogged) gbd).setWaterlogged(false);
            }
            Matrix4f glocal = new Matrix4f().translation(
                (float) ((ghost.target().getBlockX() + 0.5) - snapX),
                (float) ((ghost.target().getBlockY() + 0.5) - snapY),
                (float) ((ghost.target().getBlockZ() + 0.5) - snapZ));
            String gType = null, gState = null;
            List<CustomHeadBlock.DisplayEntityConfig> gdecs = null;
            List<CustomHeadBlock.BlockDisplayEntityConfig> gbdecs = null;
            CustomHeadBlock.ParticleConfig gparticles = null;
            Vector3f gwall = null;
            // A data-only ghost mirrors no real block, so it has no custom-head identity to copy — it
            // renders as plain BlockData and lands as one.
            CustomHeadBlock gchb = tmpl == null ? null : registry.getTypeFromBlock(tmpl);
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
            CollisionConfig gcustom = gchb != null ? gchb.resolveCollision(gState) : null;
            CollisionConfig gcollision = gcustom != null
                ? gcustom : colliderRegistry.get(gbd.getMaterial(), gbd);
            gcollision = applyWallHeadShift(gcollision, gbd);
            MechanismBlockData gmb = new MechanismBlockData(gbd, glocal, gcollision,
                gType, gState, gdecs, gbdecs, gparticles, null, false, gwall);
            gmb.ghost = true;
            blockData.add(gmb);
        }

        return spawnMechanismEntities(mechId, type, blockData, pivot, rotationAxis, vehicle, rideOffset,
            ownsVehicle, driven, serializer, blocks, capturedWorldBanners, blocks.size(), false);
    }

    /**
     * Spawn the parent + per-part displays + collider pairs for an already-built {@code blockData} list,
     * construct + register the {@link BasicMechanism}, mount it, air out {@code blocks} (EMPTY for a
     * block-free / model-driven assembly), and announce it. Shared by the world-scan {@link #assembleCore}
     * and the block-free {@link #assembleFromParts}. {@code realBlockCount} excludes any appended ghosts
     * (it is the count fed to the rotation network); {@code blockFree} marks a mechanism with no world
     * blocks to restore, so its teardown removes entities without landing them.
     */
    private BasicMechanism spawnMechanismEntities(UUID mechId, String type, List<MechanismBlockData> blockData,
            Location pivot, Vector3f rotationAxis, Entity vehicle, float rideOffset, boolean ownsVehicle,
            boolean driven, @Nullable MechanismSerializer serializer, List<Block> blocks,
            List<Display> capturedWorldBanners, int realBlockCount, boolean blockFree) {

        // Steps 2-3 (tear down custom-block tracking + air out the source blocks) are deferred to AFTER the
        // display spawn — see airOutSourceBlocks(). For owned vehicles the mech displays are mounted and
        // positioned onto the cells FIRST (synchronously), so removing the real blocks leaves no empty frame
        // (the flicker). The snapshot above still runs first, so the capture-order race is unaffected.

        // 4. Spawn display + collider entities.
        // Park the displays at the eventual MOUNTED anchor (pivot + rideOffset), not an arbitrary height:
        // on the mount tick the vehicle's positionRider has already run, so the first entity-spawn packet
        // carries this parked Y while the transform already subtracts rideOffset — any mismatch renders as a
        // one-frame vertical offset that snaps out next tick (was pivot+2.5 → ~0.525 too high). Minecarts
        // (rideOffset 0) park at the pivot and are re-teleported by their deferred mount anyway.
        Location spawnLoc = pivot.clone().add(0, rideOffset, 0);

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
        // Banner displays are a PARALLEL per-block structure, not appended to the display groups:
        // the groups' [primary, itemExtras…, blockExtras…] shape is indexed positionally by
        // rotate()/updateAnimatedDisplays and rewritten wholesale by setBlockState, so a tail of
        // banner displays inside the group would need clamps in four loops to stay collision-free.
        List<List<Display>> bannerDisplaysPerBlock = new ArrayList<>();
        List<ColliderPair> colliders = new ArrayList<>();

        // Pre-compute DynLight tags: map each light-emitting block (by vanilla light level) to a
        // COLLIDER-owning block index, so the tag rides a collider Shulker (a free carrier teleported
        // to the block's real cell each tick), not a mounted display (all mounted displays share the
        // parent's position, so DynLight would emit their light at the pivot). Mirrors BlockShips
        // (ShipInstance#400-455): occlusion-cull interior lights, then assign each light to its own
        // collider or, if it has none (torch/lantern → CollisionConfig.NONE), a neighbouring collider.
        // Ghost blocks are appearance-only snapshots that may overlap real cells, so they're excluded.
        Map<Integer, Integer> colliderLight = new HashMap<>();
        if (dynamicLightsEnabled) {
            int[][] neighbours = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            int[][] delegatePriority = {{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0}};
            Map<String, Integer> posIndex = new HashMap<>();
            for (int i = 0; i < blockData.size(); i++) {
                MechanismBlockData mb = blockData.get(i);
                if (mb.ghost || mb.blockData == null) continue; // block-free parts occupy no world cell
                Vector3f t = mb.localTransform.getTranslation(new Vector3f());
                posIndex.put(Math.round(t.x) + "," + Math.round(t.y) + "," + Math.round(t.z), i);
            }
            for (int i = 0; i < blockData.size(); i++) {
                MechanismBlockData mb = blockData.get(i);
                if (mb.ghost || mb.blockData == null) continue; // block-free parts emit no vanilla block light
                int level = mb.blockData.getLightEmission();
                // A custom head is a PLAYER_HEAD (vanilla emission 0), so its light lives in the head
                // definition, not the block data. Fold in the head's per-state light (glowing heads, lit
                // candles) so it tags a collider and the DynLight companion lights it while moving.
                if (mb.customTypeId != null) {
                    CustomHeadBlock t = registry.getType(mb.customTypeId);
                    if (t != null) {
                        CustomHeadBlock.LightConfig lc = t.resolveLight(mb.customState);
                        if (lc != null) level = Math.max(level, lc.level());
                    }
                }
                if (level <= 0) continue;
                Vector3f t = mb.localTransform.getTranslation(new Vector3f());
                int x = Math.round(t.x), y = Math.round(t.y), z = Math.round(t.z);
                // Occlusion cull: skip lights fully enclosed by present, opaque neighbours.
                boolean allOpaque = true;
                for (int[] d : neighbours) {
                    Integer ni = posIndex.get((x + d[0]) + "," + (y + d[1]) + "," + (z + d[2]));
                    if (ni == null || !blockData.get(ni).blockData.getMaterial().isOccluding()) {
                        allOpaque = false;
                        break;
                    }
                }
                if (allOpaque) continue;
                // Assign the emission to a collider: this block's own, else a neighbour's (by priority).
                if (mb.collision.enabled()) {
                    colliderLight.merge(i, level, Math::max);
                } else {
                    for (int[] d : delegatePriority) {
                        Integer ni = posIndex.get((x + d[0]) + "," + (y + d[1]) + "," + (z + d[2]));
                        if (ni != null && blockData.get(ni).collision.enabled()) {
                            colliderLight.merge(ni, level, Math::max);
                            break;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < blockData.size(); i++) {
            MechanismBlockData mb = blockData.get(i);
            List<Display> group = new ArrayList<>();

            // Primary display
            Display primary;
            if (mb.blockData == null) {
                // Block-free / standalone display part (P7.B): no backing block — render the descriptor's
                // ItemDisplay directly (a prefab ship's banner or balloon head). No BlockDisplay branch
                // below applies (they all read mb.blockData). displayItem is guaranteed for a null-block part.
                ItemStack it = mb.displayItem != null ? mb.displayItem : new ItemStack(Material.STONE);
                ItemDisplay sd = spawnMechDisplay(spawnLoc, it, mechId, i, "display");
                if (mb.displayMode != null) sd.setItemDisplayTransform(mb.displayMode);
                primary = sd;
            } else if (mb.wasBare && "mech:shaft".equals(mb.customTypeId)) {
                // A bare shaft was reverted to an encased head for capture (mb.blockData is the head), but its
                // true form is a bare CHAIN. Render that in transit — the rod extra still spins. Axis from the
                // (always idle_*) captured state; a default CHAIN is Y, so an X/Z shaft must set it explicitly.
                org.bukkit.block.data.BlockData chain = Material.CHAIN.createBlockData();
                if (chain instanceof org.bukkit.block.data.Orientable o) {
                    RotationNetwork.Axis ax = RotationNetwork.axisFromState(
                        mb.customState != null ? mb.customState : "idle_y");
                    o.setAxis(ax == RotationNetwork.Axis.X ? org.bukkit.Axis.X
                            : ax == RotationNetwork.Axis.Z ? org.bukkit.Axis.Z : org.bukkit.Axis.Y);
                }
                primary = spawnMechBlockDisplay(spawnLoc, chain, mechId, i, "display");
            } else if (mb.customTypeId != null) {
                CustomHeadBlock chbType = registry.getType(mb.customTypeId);
                String tex = chbType != null
                    ? chbType.resolveTexture(mb.customState, 0, null)
                    : null;
                ItemStack headItem = tex != null
                    ? HeadUtil.createHead(tex, 1)
                    : new ItemStack(Material.PLAYER_HEAD);
                primary = spawnMechDisplay(spawnLoc, headItem, mechId, i, "display");
            } else if (mb.banners != null && mb.banners.stream().anyMatch(BannerAttachment::isBlockBanner)) {
                // A vanilla banner BLOCK (standing or wall): a BlockDisplay renders only the base cloth
                // colour, never the woven patterns — those can only ride via the banner ItemDisplay spawned
                // below. Make the primary invisible AIR so it doesn't duplicate that ItemDisplay. The real
                // banner blockdata still rides in mb.blockData (landing/persistence read it, not this display),
                // and the AIR entity keeps the "display" tag for recovery/collider/group-shape parity.
                primary = spawnMechBlockDisplay(spawnLoc, Material.AIR.createBlockData(), mechId, i, "display");
            } else if ((mb.blockData.getMaterial() == Material.PLAYER_HEAD
                        || mb.blockData.getMaterial() == Material.PLAYER_WALL_HEAD)
                    && mb.blockEntitySnapshot != null
                    && mb.blockEntitySnapshot.get("bs_skull_tex") instanceof String skullTex) {
                // A NON-registry vanilla skull with a captured profile (e.g. BlockShips' ship wheel, decorative
                // player heads): a BlockDisplay of PLAYER_HEAD carries no profile and renders as Steve, so spawn
                // a TEXTURED ItemDisplay from the captured texture instead — exactly like the registered-head
                // branch above. Orientation (wallFacing/floorHeadYaw, captured for this case too) is applied by
                // rotate(). The real skull profile still lands on disassembly via the block-entity snapshot.
                primary = spawnMechDisplay(spawnLoc, HeadUtil.createHead(skullTex, 1), mechId, i, "display");
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

            // Banner displays: one ItemDisplay per attachment (a flag = 2), positioned by the
            // banner loop in BasicMechanism.rotate(). Ghost blocks have banners == null → empty.
            List<Display> bannerGroup = new ArrayList<>();
            if (mb.banners != null) {
                // One ItemDisplay per attachment, positioned by BasicMechanism.rotate()'s banner loop. The
                // vanilla banner BLOCK entry (BLOCK_FACE_KEY) is INCLUDED — only an ItemDisplay can show its
                // patterns (the primary was aired out above). It's appended last in mb.banners, so bannerGroup
                // stays 1:1 index-aligned with mb.banners, which is how rotate()/recovery pair the two lists.
                for (int b = 0; b < mb.banners.size(); b++) {
                    bannerGroup.add(spawnMechDisplay(spawnLoc, mb.banners.get(b).item().clone(),
                        mechId, i, "banner_" + b));
                }
            }
            bannerDisplaysPerBlock.add(bannerGroup);

            // Collider: marker ArmorStand carrier + Shulker passenger
            if (mb.collision.enabled()) {
                final int blockIdx = i;
                final MechanismBlockData mbf = mb;
                final int dynLight = colliderLight.getOrDefault(i, 0); // 0 = no light on this collider
                Vector3f initOff = mb.localTransform.getTranslation(new Vector3f());
                Vector3f off = mb.collision.offset();
                // -0.5 Y: the shulker box (attachedFace DOWN, marker, peek 0) is feet-anchored, so anchor
                // the carrier at the cell bottom to centre a full box. The block-local collider offset is
                // added on top; at assembly the mechanism is at identity rotation so it applies directly
                // (BasicMechanism.rotate rotates it thereafter). The -0.5 is world-space, never rotated.
                Location carrierLoc = pivot.clone().add(initOff.x + off.x, initOff.y - 0.5 + off.y, initOff.z + off.z);

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
                    // Resize the hitbox for sub-cube colliders. Only touch the attribute when size != 1.0
                    // so the full-block path stays byte-identical to before. Set before addPassenger.
                    if (mbf.collision.size() != 1.0f) {
                        org.bukkit.attribute.Attribute scaleAttr = ScaleAttributeCompat.getScale();
                        org.bukkit.attribute.AttributeInstance ai =
                            scaleAttr != null ? s.getAttribute(scaleAttr) : null;
                        if (ai != null) ai.setBaseValue(mbf.collision.size());
                        else warnScaleUnavailable();
                    }
                    s.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":collider");
                    // DynLight: tag the collider Shulker (a free carrier at the block's real cell, not
                    // a mounted display) so its light emits at the block, not the mechanism pivot.
                    if (dynLight > 0) s.addScoreboardTag(DynLightTags.tag(dynLight));
                });
                carrier.addPassenger(shulker);
                colliders.add(new ColliderPair(carrier, shulker, i));
            }
        }

        // 5. Create mechanism, register colliders
        BasicMechanism mech = new BasicMechanism(mechId, type, pivot, rotationAxis, vehicle, parentDisplay,
            rideOffset, ownsVehicle, displaysPerBlock, bannerDisplaysPerBlock, colliders, blockData,
            registry, serializer);
        mech.mechanismRegistry = this;
        mech.setDriven(driven); // driven mechanisms are positioned each tick by their consumer (repositionDriven)
        mech.setBlockFree(blockFree); // no world blocks to restore → destroy() teardown; parts may be null-block

        for (ColliderPair cp : colliders) {
            colliderIndex.put(cp.shulker().getUniqueId(), new ColliderRef(mech, cp.blockIndex()));
        }

        // 6. Mount displays on parent + set initial transforms, then air out the source blocks.
        if (ownsVehicle || driven) {
            // Owned ArmorStand vehicle, OR a driven consumer-owned ArmorStand (same synchronous path — an
            // ArmorStand accepts addPassenger this tick): mount + position the displays SYNCHRONOUSLY, THEN remove
            // the real blocks — so the mech displays already cover the cells before the originals vanish, with
            // no empty frame (the per-move flicker). No delay tick ⇒ no vehicle-death window. Owned ArmorStands
            // accept addPassenger synchronously (the 1-tick defer only ever existed for minecarts, below).
            try {
                for (var group : displaysPerBlock) {
                    for (Display d : group) parentDisplay.addPassenger(d);
                }
                for (var group : bannerDisplaysPerBlock) {
                    for (Display d : group) parentDisplay.addPassenger(d);
                }
                vehicle.addPassenger(parentDisplay);
                mech.rotate(0);
                updateAnimatedDisplays(mech, 0L);
            } catch (RuntimeException e) {
                mech.removeAllEntities();   // drop the just-spawned mech displays/colliders; blocks untouched
                throw e;                    // the owning overload's catch then removes the vehicle
            }
            firePreAirOut(mech, blocks); // 3a-ii: source blocks still LIVE + colliders spawned (leads seam)
            airOutSourceBlocks(blocks);
            // Remove the captured world banner displays only now: same tick as the mount (no
            // double-render frame), and any throw up to here — including inside airOutSourceBlocks'
            // rotation-network recalc — leaves the world banners (and their items) untouched.
            for (Display d : capturedWorldBanners) d.remove();
        } else {
            // External vehicle (minecart): remove the real blocks now, then defer the mount one tick —
            // minecarts silently reject addPassenger for non-living entities at the NMS level.
            firePreAirOut(mech, blocks); // 3a-ii: source blocks still LIVE + colliders spawned (leads seam)
            airOutSourceBlocks(blocks);
            for (Display d : capturedWorldBanners) d.remove(); // see owned branch — after air-out
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Skip if the vehicle died OR the mechanism was disassembled during the delay tick — in the
                // latter case removeAllEntities() already removed parentDisplay, so re-adding passengers would
                // operate on dead displays. disassemble() is idempotent (no-op if already torn down).
                if (!vehicle.isValid() || !parentDisplay.isValid()) { mech.disassemble(); return; }
                for (var group : displaysPerBlock) {
                    for (Display d : group) parentDisplay.addPassenger(d);
                }
                for (var group : bannerDisplaysPerBlock) {
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

        // Register + drive + announce as one guarded step. The source blocks are ALREADY aired out above, so
        // an unguarded throw in onAssembled (malformed rotation network) would strand a registered, ticking
        // mech whose persistent displays/colliders the orphan sweep can't reap (still registered) — and lose
        // the aired-out blocks. On any throw here, disassemble() is the complete, idempotent rollback: it
        // re-places the source blocks, unregisters (onMechanismRemoved → drains colliderIndex + driver state),
        // and removes the mech's entities. The mech stays registered during callEvent so listeners that scan
        // the registry see it, exactly as before. (disassemble's `disassembled` guard makes the external
        // deferred-mount task's own disassemble() call a no-op.)
        activeMechanisms.put(mechId, mech);
        try {
            // Rotation parts keep functioning while riding: build the mechanism's own rotation
            // network. Only the first blocks.size() entries are real — the ghost snapshots appended
            // after them are appearance-only and may overlap real cells.
            if (rotationDriver != null) rotationDriver.onAssembled(mech, realBlockCount);

            // Surface assembly to companion plugins (e.g. the mech advancement system). Single choke
            // point for every assembleMechanism overload; fired on the main thread, informational only.
            boolean verticalAxis = Math.abs(rotationAxis.y) > 0.5f
                && rotationAxis.x == 0f && rotationAxis.z == 0f;
            Bukkit.getPluginManager().callEvent(
                new MechanismAssembleEvent(mech, type, pivot.clone(), mech.blockCount(), verticalAxis));
        } catch (RuntimeException e) {
            mech.disassemble();   // restore blocks + unregister + drain colliderIndex + remove entities
            throw e;              // the owning overload's catch then removes/untags the vehicle
        }

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

    // Vanilla-banner in-transit rendering constants. The banner ITEM model renders as the full 3D banner
    // (cloth + pole) and its cloth faces 180° opposite the block-entity, so the transform flips it (see
    // vanillaBannerTransform's 180 - yaw). The standing case sits it at the cell floor; the wall case lifts
    // it a block and shifts it toward its attachment face to roughly match the block-entity's cloth height
    // (the item model's pole has no vanilla wall equivalent — accepted approximation). Y values verified
    // in-game (floor at 0.0, wall at 1.0).
    private static final float VANILLA_BANNER_SCALE = 1.0f;
    private static final float VANILLA_STANDING_Y = 0.0f;
    private static final float VANILLA_WALL_Y = -1.0f;
    private static final float VANILLA_WALL_DEPTH = 0.48f; // toward the wall from the cell center

    /**
     * Synthesize the attachment that carries a vanilla banner BLOCK through a move: the equivalent
     * banner item (patterns via BannerMeta — also the drop for a blocked landing; wall-banner
     * materials have no item form, so {@code new ItemStack(*_WALL_BANNER)} would throw) plus a
     * block-local transform approximating the block-entity render. Null if the material can't be
     * mapped to an item (never expected for the 16 vanilla colors).
     */
    private static @Nullable BannerAttachment vanillaBannerAttachment(org.bukkit.block.Banner state,
                                                                      BlockData bd) {
        Material itemMat = Material.getMaterial(
            bd.getMaterial().name().replace("_WALL_BANNER", "_BANNER"));
        if (itemMat == null || !itemMat.isItem()) return null;
        ItemStack item = new ItemStack(itemMat);
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.BannerMeta meta) {
            meta.setPatterns(state.getPatterns());
            item.setItemMeta(meta);
        }
        return new BannerAttachment(item, BannerAttachment.BLOCK_FACE_KEY,
            vanillaBannerTransform(bd), new Vector3f());
    }

    /**
     * Yaw (radians about +Y) turning a floor head's primary ItemDisplay so it matches the vanilla skull's
     * {@code Rotatable} orientation on the static path. 16-step SOUTH=0 · 22.5°/step, same table as standing
     * banners. The sign is baked in (negated) to the proven convention: the standing-banner transform applies
     * its yaw as {@code rotateY(-yaw)}; this returns radians meant to be applied POSITIVELY at the render site.
     */
    private static float floorHeadYawRadians(org.bukkit.block.data.Rotatable rot) {
        return (float) Math.toRadians(-BlockRotation.rotationToStep(rot.getRotation()) * 22.5);
    }

    private static org.bukkit.util.Transformation vanillaBannerTransform(BlockData bd) {
        Vector3f translation = new Vector3f(0, VANILLA_STANDING_Y, 0);
        float yaw = 0f;
        if (bd instanceof org.bukkit.block.data.Rotatable rot) {
            // Standing banner: 16-step rotation, SOUTH=0 · 22.5°/step — same table as floor heads.
            yaw = BlockRotation.rotationToStep(rot.getRotation()) * 22.5f;
        } else if (bd instanceof org.bukkit.block.data.Directional dir) {
            // Wall banner: cloth faces `facing`, hangs against the opposite (attachment) face.
            org.bukkit.util.Vector f = dir.getFacing().getDirection();
            translation = new Vector3f(
                -(float) f.getX() * VANILLA_WALL_DEPTH, VANILLA_WALL_Y,
                -(float) f.getZ() * VANILLA_WALL_DEPTH);
            yaw = switch (dir.getFacing()) {
                case SOUTH -> 0; case WEST -> 90; case NORTH -> 180; case EAST -> 270; default -> 0;
            };
        }
        return new org.bukkit.util.Transformation(
            translation,
            // 180 - yaw: the banner item model's cloth faces the opposite way from the block-entity, so flip
            // it a half-turn on top of the facing yaw (fixes both standing and wall reading backwards).
            new org.joml.Quaternionf().rotateY((float) Math.toRadians(180 - yaw)),
            new Vector3f(VANILLA_BANNER_SCALE, VANILLA_BANNER_SCALE, VANILLA_BANNER_SCALE),
            new org.joml.Quaternionf());
    }

    /**
     * Tear down custom-block tracking (which removes each block's OWN display entities) and air out the source
     * blocks. Must run AFTER the snapshot — {@code onBlockRemoved} triggers a synchronous rotation-network
     * recalc that rewrites downstream transmitters {@code spinning_*→idle_*}, so doing it during capture would
     * snapshot later blocks as idle. Two-pass removal handles attachables before their supports.
     */
    /**
     * The face on which a paired chest half meets its partner — vanilla's {@code
     * ChestBlock#getConnectedDirection}: clockwise of {@code facing} for a LEFT half, counter-clockwise
     * for a RIGHT one. Null for a SINGLE chest or a non-horizontal facing.
     */
    private static org.bukkit.block.@Nullable BlockFace chestPartnerFace(
            org.bukkit.block.data.type.Chest cd) {
        boolean left = cd.getType() == org.bukkit.block.data.type.Chest.Type.LEFT;
        return switch (cd.getFacing()) {
            case NORTH -> left ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST;
            case EAST -> left ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
            case SOUTH -> left ? org.bukkit.block.BlockFace.WEST : org.bukkit.block.BlockFace.EAST;
            case WEST -> left ? org.bukkit.block.BlockFace.NORTH : org.bukkit.block.BlockFace.SOUTH;
            default -> null;
        };
    }

    private void airOutSourceBlocks(List<Block> blocks) {
        for (Block b : blocks) {
            CustomHeadBlock chb = registry.getTypeFromBlock(b);
            // Capture (not break): consumers keep per-block state in the mechanism (e.g. filter items in
            // configPdc) instead of dropping it, so it isn't duplicated on landing (A10a).
            if (chb != null) registry.onBlockRemovedForCapture(b, chb);
        }
        // A double chest with only ONE half in the mechanism leaves the survivor holding
        // type=left/right pointing at air, forever: setType(..., false) sets UPDATE_KNOWN_SHAPE, so
        // updateNeighbourShapes never runs and ChestBlock#updateShape's self-heal branch never fires.
        // A stale half is also a re-pair magnet — a SINGLE chest landing beside it later flips to the
        // opposite type and silently merges into a 54-slot double over two separate inventories.
        // Normalise the survivor to SINGLE here. A same-block rewrite keeps the block entity, so unlike
        // the AIR passes it cannot trip preRemoveSideEffects and drop that chest's items.
        Set<Block> leaving = new HashSet<>(blocks);
        for (Block b : blocks) {
            if (!(b.getBlockData() instanceof org.bukkit.block.data.type.Chest cd)
                    || cd.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) continue;
            org.bukkit.block.BlockFace partnerFace = chestPartnerFace(cd);
            if (partnerFace == null) continue;
            Block partner = b.getRelative(partnerFace);
            if (leaving.contains(partner)) continue;   // both halves are going; nothing survives to fix
            if (partner.getBlockData() instanceof org.bukkit.block.data.type.Chest pd
                    && pd.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                org.bukkit.block.data.type.Chest single = (org.bukkit.block.data.type.Chest) pd.clone();
                single.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
                partner.setBlockData(single, false);
            }
        }

        // Empty every block inventory before the setType(AIR) passes below, or the world spills a
        // second copy of everything the capture loop already cloned into the mechanism.
        // setType(..., applyPhysics=false) maps to chunk-update flag 530, which does NOT carry
        // UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS — so removing the block runs
        // BlockEntity#preRemoveSideEffects → Containers.dropContents and the originals hit the floor.
        // (Mirrors BlockShips' BlockStructureScanner "Pass 0".) Keyed on TileStateInventoryHolder, not
        // Container, so it clears more than the Container capture above took. INVARIANT: everything this
        // pass empties must be captured by something, or the emptying IS the deletion. The pairing —
        //   Container                    → the typed `storage` inventory captured above (~612)
        //   Lectern / Jukebox / DecoratedPot → their own DefaultBlockSnapshotProvider slot-0 keys
        //   every other holder           → that provider's bs_tsih_items catch-all, keyed on this same
        //                                  interface (chiseled bookshelves, 1.21.9+ shelves, whatever's next)
        // Both halves also feed BasicMechanism.dropStorageItems, so a block the mechanism discards instead
        // of landing still drops its cargo rather than taking it to the grave.
        // Deliberately a separate pass AFTER capture succeeded: airOutSourceBlocks runs post display
        // spawn and mount, so clearing any earlier would turn an aborted assembly into deletion.
        // Guarded per block — one failure must not leave a half-cleared, half-removed structure.
        for (Block b : blocks) {
            try {
                if (b.getState() instanceof io.papermc.paper.block.TileStateInventoryHolder tsih) {
                    tsih.getSnapshotInventory().clear();
                    tsih.update();   // write the emptied state so setType(AIR) can't drop items
                }
            } catch (Exception e) {
                plugin.getLogger().warning("airOutSourceBlocks: failed to clear the container at "
                    + b.getLocation() + " before removal (" + e.getMessage()
                    + "); its contents will drop instead of riding along");
            }
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
        // Periodic async flush of the chunk index: moving mechanisms re-index in memory per crossing (no
        // disk), and this lands the dirty worlds' chunks.yml off the main thread every 60s. Chunk/world
        // unload and shutdown flush on their own; between those this bounds how stale the on-disk index gets.
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, persistence::flushDirtyAsync, 20L * 60, 20L * 60);
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (flushTask != null) flushTask.cancel();
        // Restore real blocks for any still-assembled mechanism (e.g. an open door) so the structure
        // isn't lost on /stop. Per-mechanism guarded: a failure falls back to removeAllEntities so we
        // never leak persistent entities. (Full restart recovery is the deferred persistence work.)
        for (BasicMechanism mech : new ArrayList<>(activeMechanisms.values())) {
            // Persisted mechanism: save-and-LEAVE its entities (they're setPersistent(true), so the region
            // file keeps them) rather than disassembling — recovery re-adopts them on next chunk load. Do
            // NOT route through onMechanismRemoved (that would delete the state file).
            if (mech.isPersisted()) {
                try {
                    persistence.save(mech.snapshotState());
                    continue;
                } catch (Exception e) {
                    plugin.getLogger().warning("Mechanism " + mech.id() + " failed to persist on shutdown ("
                        + e.getMessage() + "); disassembling as fallback");
                    // fall through to the disassemble path below
                }
            }
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
        persistence.shutdown();
    }

    /**
     * A world is unloading at runtime (e.g. /mvunload) — a path that may not fire per-chunk
     * EntitiesUnloadEvent. Disassemble every mechanism whose pivot is in that world while its blocks are
     * still writable, so a mid-stroke piston/hoist/door isn't orphaned (captured blocks lost). Mirrors
     * {@link #shutdown()}'s guarded restore, scoped to one world. Idempotent: {@code disassemble()}
     * self-deregisters, so a later EntitiesUnload for the same chunks is a no-op.
     */
    public void onWorldUnload(org.bukkit.World world) {
        for (BasicMechanism mech : new ArrayList<>(activeMechanisms.values())) {
            if (!world.equals(mech.pivot().getWorld())) continue;
            // Persisted mechanism: save-and-leave (the unloading world's region file keeps the persistent
            // entities); recovery re-adopts them when the world reloads. Mirrors the branched shutdown().
            if (mech.isPersisted()) {
                try {
                    persistence.save(mech.snapshotState());
                    activeMechanisms.remove(mech.id());
                    for (ColliderPair cp : mech.colliders) colliderIndex.remove(cp.shulker().getUniqueId());
                    continue;
                } catch (Exception e) {
                    plugin.getLogger().warning("Mechanism " + mech.id() + " failed to persist on world "
                        + "unload (" + e.getMessage() + "); disassembling as fallback");
                    // fall through to the disassemble path below
                }
            }
            try {
                mech.disassemble();
                // Force SYNCHRONOUS entity removal: for an owned vehicle, disassemble() defers removal one
                // tick (anti-landing-flicker), but CraftServer.unloadWorld saves + unloads THIS tick, so the
                // deferred task never runs and the display/collider/vehicle entities would persist into the
                // region file. removeAllEntities is idempotent, so the later deferred task simply no-ops.
                mech.removeAllEntities();
            } catch (Exception e) {
                plugin.getLogger().warning("Mechanism " + mech.id() + " failed to disassemble on world "
                    + "unload (" + e.getMessage() + "); removing entities without block restore");
                try {
                    mech.removeAllEntities();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
        // The parked mechanisms above re-indexed in memory; land the world's chunks.yml before its region
        // file is written (sync, so the on-disk index can't lag a just-unloaded world).
        persistence.flushWorldSync(world.getName());
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

    /** Enable/disable tagging light-emitting mechanism blocks for the DynLight companion. */
    void setDynamicLights(boolean enabled) {
        this.dynamicLightsEnabled = enabled;
    }

    /**
     * Remove orphaned mechanism entities from a chunk. These are entities tagged
     * corelib:mech:* from previous sessions where the mechanism was not properly
     * cleaned up. All mechanism entities have setPersistent(true), so they never
     * despawn naturally — this cleanup prevents permanent entity leaks.
     */
    public void cleanupOrphanedEntities(org.bukkit.Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            // A mechanism minecart is a first-class persistent entity, not a disposable mech display.
            // Never reap it here — even after a hard crash left its stale corelib:mech:{id}:vehicle tag
            // (disassembly never ran to strip it), the cart and its PDC-stored glue must survive.
            if (entity.getScoreboardTags().contains("corelib:mechanism_minecart")) continue;
            for (String tag : entity.getScoreboardTags()) {
                if (!tag.startsWith("corelib:mech:")) continue;
                // Tag format: "corelib:mech:{uuid}:{index}:{role}" or "corelib:mech:{uuid}:{role}". Peel the
                // UUID with substring/indexOf — no split()/regex alloc on this per-entity per-EntitiesLoad
                // path: a UUID has hyphens but no colons, so the first ':' after the "corelib:mech:" prefix
                // terminates it (mirrors attemptRecover and BlockShips' ShipTags.extractShipId). The
                // try/catch below subsumes the old parts.length<3 guard: an empty/garbage id throws → skipped.
                String rest = tag.substring("corelib:mech:".length());
                int c = rest.indexOf(':');
                String idStr = (c < 0) ? rest : rest.substring(0, c);
                try {
                    UUID mechId = UUID.fromString(idStr);
                    // Never reap an entity whose mechanism is active, currently being recovered, or still
                    // has an on-disk state file (a persisted mechanism whose pivot chunk hasn't loaded yet —
                    // its entities must survive until recovery adopts them). Persistence writes are
                    // synchronous today, so recovery in this same EntitiesLoad completes before this sweep;
                    // the guard also covers a not-yet-loaded pivot chunk and the deferred-removal window.
                    if (!activeMechanisms.containsKey(mechId)
                            && !mechIdsBeingRecovered.contains(mechId)
                            && !persistence.hasMetadata(chunk.getWorld().getName(), mechId)) {
                        entity.remove();
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a valid UUID — might be a different tag format, skip
                }
                break; // only check first matching tag per entity
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Crash recovery (persisted mechanisms)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * A chunk's entities are unloading at runtime (player walked away). PARK any persisted mechanism anchored
     * to this chunk: save its current state, drop it from the live maps, but LEAVE its persistent entities
     * (they unload with the chunk and reload with it). Without this the mechanism would sit in
     * {@code activeMechanisms} forever with stale entity refs, and {@link #recoverMechanismsInChunk} would
     * skip it as "already active" on reload — so no recovered {@link MechanismAssembleEvent} ever fires and a
     * consumer (BlockShips) that dropped its wrapper on chunk unload could never rebuild it (a zombie).
     * Parking makes chunk-reload go through the SAME recovery path as a server restart.
     *
     * <p>Keyed on the mechanism's pivot chunk (== its persistence index chunk), so re-save re-indexes to the
     * same chunk recovery will re-trigger on. Non-persisted mechanisms aren't recovered, so they're left
     * alone (they tick-or-skip against their own entities as before).
     */
    public void onEntitiesUnload(org.bukkit.Chunk chunk) {
        if (activeMechanisms.isEmpty()) return;
        for (BasicMechanism mech : new ArrayList<>(activeMechanisms.values())) {
            if (!mech.isPersisted()) continue;
            Location p = mech.pivot();
            if (!chunk.getWorld().equals(p.getWorld())) continue;
            if ((p.getBlockX() >> 4) != chunk.getX() || (p.getBlockZ() >> 4) != chunk.getZ()) continue;
            try {
                persistence.save(mech.snapshotState());
            } catch (Exception e) {
                plugin.getLogger().warning("Mechanism " + mech.id() + " failed to persist on chunk unload ("
                    + e.getMessage() + "); leaving it active");
                continue;
            }
            activeMechanisms.remove(mech.id());
            for (ColliderPair cp : mech.colliders) colliderIndex.remove(cp.shulker().getUniqueId());
        }
        // Parked mechanisms re-indexed in memory above; flush the dirty worlds off-thread (mirrors
        // BlockShips' saveAllChunkIndicesAsync on chunk unload).
        persistence.flushDirtyAsync();
    }

    /**
     * Recover any persisted mechanisms whose pivot chunk is the one that just finished loading its entities.
     * A persisted mechanism was saved-and-left on shutdown/world-unload (its display/collider/vehicle
     * entities are {@code setPersistent(true)}, so they survive in the region file); this rebinds a
     * {@link BasicMechanism} from the saved {@link MechanismState} + those surviving tagged entities and then
     * either lands it (restore-to-blocks) or resumes it live (restore-to-entities), per {@link #recoverOne}.
     *
     * <p>Must run in {@code EntitiesLoad} BEFORE {@link #cleanupOrphanedEntities} so the in-flight guard
     * ({@link #mechIdsBeingRecovered} + {@link MechanismPersistence#hasMetadata}) protects the entities this
     * adopts from being reaped as orphans.
     */
    public void recoverMechanismsInChunk(org.bukkit.Chunk chunk) {
        org.bukkit.World world = chunk.getWorld();
        // 1. Enrol any persisted mechanism indexed to THIS (pivot) chunk that isn't already live or pending.
        for (UUID id : persistence.mechanismsInChunk(world.getName(), chunk.getX(), chunk.getZ())) {
            if (activeMechanisms.containsKey(id) || pendingRecoveries.containsKey(id)) continue;
            MechanismState st = persistence.load(world.getName(), id);
            if (st == null) {
                // Corrupt/unreadable state file (already logged by load) — drop the dangling index entry so
                // we don't retry it every chunk load.
                persistence.remove(world.getName(), id);
                continue;
            }
            pendingRecoveries.put(id, st);
            mechIdsBeingRecovered.add(id); // guards its entities from the orphan sweep while recovery is in flight
        }
        // 2. (Re-)attempt each pending recovery whose footprint contains THIS chunk: the load may have brought
        //    in more of that mechanism's entities. Only mechanisms near the loaded chunk are re-attempted — a
        //    chunk load elsewhere brought in none of a distant mechanism's entities, so re-scanning it is pure
        //    waste. This footprint gate is what keeps a fleet world-load from re-sweeping every pending
        //    mechanism on every EntitiesLoad (O(all pending) → O(pending near this chunk)); it mirrors
        //    BlockShips feeding a ship only when one of its entities is in the just-loaded chunk.
        if (pendingRecoveries.isEmpty()) return;
        int lcx = chunk.getX(), lcz = chunk.getZ();
        for (MechanismState st : new ArrayList<>(pendingRecoveries.values())) {
            if (!world.getName().equals(st.worldName)) continue;
            int pcx = (int) Math.floor(st.px) >> 4;
            int pcz = (int) Math.floor(st.pz) >> 4;
            int radius = st.recoveryChunkRadius();
            if (Math.abs(lcx - pcx) > radius || Math.abs(lcz - pcz) > radius) continue;
            try {
                attemptRecover(world, st);
            } catch (Exception e) {
                plugin.getLogger().warning("Mechanism recovery attempt failed for " + st.mechId + " ("
                    + e.getMessage() + "); leaving its state for a later chunk load");
            }
        }
    }

    /**
     * One accumulation pass for a pending persisted mechanism: gather its surviving tagged entities from the
     * currently-loaded chunks in its footprint, and — once the frame is present AND recovery is complete
     * ({@code found >= entityCount}) or the whole footprint is loaded (fallback) — rebind a
     * {@link BasicMechanism} and land/resume it. Otherwise stay pending for a later chunk load.
     */
    private void attemptRecover(org.bukkit.World world, MechanismState st) {
        int pcx = (int) Math.floor(st.px) >> 4;
        int pcz = (int) Math.floor(st.pz) >> 4;
        int radius = st.recoveryChunkRadius(); // one cached extent; a provable superset of the query cube below

        // Whether the WHOLE footprint is loaded — the fallback that finalizes an under-count so a mechanism
        // that permanently lost an entity still recovers instead of hanging pending forever. Cheap boolean
        // sweep: isChunkLoaded() only, NO getEntities().
        boolean allLoaded = true;
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!world.isChunkLoaded(pcx + dx, pcz + dz)) { allLoaded = false; break outer; }
            }
        }

        // Gather candidate entities with ONE bounded getNearbyEntities around the persisted pivot (only
        // loaded chunks are consulted; it never force-loads). The half-edge is the rotation-exact
        // max-Euclidean-magnitude bound (see MechanismState.recoveryHalf) so every entity is covered at any
        // rotation. Replaces a (2·radius+1)² per-chunk getEntities() sweep.
        double half = st.recoveryHalf();
        Collection<Entity> candidates = world.getNearbyEntities(
            new Location(world, st.px, st.py, st.pz), half, half, half);

        // Bucket the tagged entities by role. Substring off the exact "corelib:mech:{id}:" prefix rather
        // than split(":") — a UUID contains hyphens but no colons, so the prefix is unambiguous, and the
        // remainder is "vehicle" | "parent" | "{i}:{role}".
        String prefix = "corelib:mech:" + st.mechId + ":";
        Entity vehicle = null;
        org.bukkit.entity.BlockDisplay parent = null;
        Map<Integer, Display> primaries = new HashMap<>();
        Map<Integer, TreeMap<Integer, Display>> itemExtras = new HashMap<>();
        Map<Integer, TreeMap<Integer, Display>> blockExtras = new HashMap<>();
        Map<Integer, TreeMap<Integer, Display>> banners = new HashMap<>();
        Map<Integer, Entity> carriers = new HashMap<>();
        Map<Integer, Shulker> shulkers = new HashMap<>();
        Map<Integer, Boolean> seatFlags = new HashMap<>(); // seat block index → isDriver (a shulker carries
                                                           // :collider AND :seat[+:driver_seat], so process ALL tags)
        for (Entity e : candidates) {
            for (String tag : e.getScoreboardTags()) {
                if (!tag.startsWith(prefix)) continue;
                String rest = tag.substring(prefix.length());
                if (rest.equals("vehicle")) { vehicle = e; continue; }
                if (rest.equals("parent")) { if (e instanceof org.bukkit.entity.BlockDisplay bd) parent = bd; continue; }
                int c = rest.indexOf(':');
                if (c < 0) continue;
                int i;
                try { i = Integer.parseInt(rest.substring(0, c)); } catch (NumberFormatException nf) { continue; }
                String role = rest.substring(c + 1);
                if (role.equals("display") && e instanceof Display d) primaries.put(i, d);
                else if (role.startsWith("extra_") && e instanceof Display d) putIndexed(itemExtras, i, role, "extra_", d);
                else if (role.startsWith("block_") && e instanceof Display d) putIndexed(blockExtras, i, role, "block_", d);
                else if (role.startsWith("banner_") && e instanceof Display d) putIndexed(banners, i, role, "banner_", d);
                else if (role.equals("carrier")) carriers.put(i, e);
                else if (role.equals("collider") && e instanceof Shulker s) shulkers.put(i, s);
                else if (role.equals("seat")) seatFlags.merge(i, false, (a, b) -> a); // keep an earlier driver=true
                else if (role.equals("driver_seat")) seatFlags.put(i, true);
                // no break: an entity (a seat shulker) legitimately carries several mech tags
            }
        }

        // The vehicle + parent are the load-bearing frame (the ctor needs both; the vehicle carries the
        // authoritative position). If either isn't present yet, stay PENDING and retry on a later chunk load
        // (the region file may still be settling, or the frame entity is in a not-yet-loaded footprint chunk).
        // Only give up once the WHOLE footprint is loaded and the frame is still missing (it's genuinely
        // gone) — leaving the state file for a future full reload. Never remove entities here; the hasMetadata
        // guard keeps strays alive. Mirrors ShipInstance.recoverEntities returning false on a missing vehicle.
        if (vehicle == null || parent == null) {
            if (allLoaded) {
                pendingRecoveries.remove(st.mechId);
                mechIdsBeingRecovered.remove(st.mechId);
                plugin.getLogger().warning("Mechanism " + st.mechId + " could not recover: vehicle/parent "
                    + "entity missing after its whole chunk footprint loaded; leaving state for a later retry");
            }
            return;
        }

        // Completeness gate (incremental cross-chunk recovery). Count the persistent entities found so far —
        // 2 (vehicle+parent) + displays + banners + collider pairs×2 — the same formula snapshotState uses for
        // entityCount. If we're short AND more of the footprint is still loading, stay pending and accumulate
        // on the next chunk load; finalize only when complete, or as a fallback once the whole footprint is
        // loaded (an entity was permanently lost — recover with what survives rather than hang forever).
        int nBlocks = st.blocks.size();
        int colliderPairs = 0;
        for (Map.Entry<Integer, Entity> ce : carriers.entrySet()) {
            int i = ce.getKey();
            if (i >= 0 && i < nBlocks && shulkers.get(i) != null) colliderPairs++;
        }
        int found = 2 + primaries.size() + sumTree(itemExtras) + sumTree(blockExtras) + sumTree(banners)
            + colliderPairs * 2;
        boolean complete = st.entityCount <= 0 || found >= st.entityCount;
        if (!complete && !allLoaded) return; // still pending: wait for neighbour chunks to load
        if (!complete) {
            plugin.getLogger().warning("Mechanism " + st.mechId + " finalizing with " + found + "/"
                + st.entityCount + " entities after its whole chunk footprint loaded (some were lost).");
        }

        // Rebuild the block snapshots (inverse of BasicMechanism.snapshotState).
        List<MechanismBlockData> blocks = rebuildBlocks(st);
        int n = blocks.size();

        // Assemble the parallel entity structures sized to the block count, in the same [primary, extra_*,
        // block_*] order rotate()/updateAnimatedDisplays index positionally. A missing entity leaves an empty
        // group — safe: rotate() and updateAnimatedDisplays both guard empty/short groups.
        List<List<Display>> displaysPerBlock = new ArrayList<>(n);
        List<List<Display>> bannerDisplaysPerBlock = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Display> group = new ArrayList<>();
            Display primary = primaries.get(i);
            if (primary == null) {
                // F7: this block's primary display was permanently lost, but item/block extras or a banner
                // survived. Without a valid index-0 primary, rotate()/updateAnimatedDisplays would mis-type the
                // first surviving extra AS the primary (applying BlockDisplay/head positioning to it), and an
                // empty group would also skip this block's banner layout. Spawn an invisible AIR primary (mirroring
                // the head-block AIR-primary in assembleCore) so the index-0 invariant holds and the survivors
                // render/position correctly. Only when something actually survived for this block.
                TreeMap<Integer, Display> ieChk = itemExtras.get(i);
                TreeMap<Integer, Display> beChk = blockExtras.get(i);
                TreeMap<Integer, Display> bnChk = banners.get(i);
                boolean hasSurvivors = (ieChk != null && !ieChk.isEmpty())
                    || (beChk != null && !beChk.isEmpty())
                    || (bnChk != null && !bnChk.isEmpty());
                if (hasSurvivors) {
                    primary = spawnMechBlockDisplay(vehicle.getLocation(), Material.AIR.createBlockData(),
                        st.mechId, i, "display");   // repositioned by rotate() below; mounted in the passenger loop
                }
            }
            if (primary != null) group.add(primary);            // index 0 = primary (rotate() reads group.get(0))
            TreeMap<Integer, Display> ie = itemExtras.get(i);
            if (ie != null) group.addAll(ie.values());
            TreeMap<Integer, Display> be = blockExtras.get(i);
            if (be != null) group.addAll(be.values());
            displaysPerBlock.add(group);
            TreeMap<Integer, Display> bn = banners.get(i);
            bannerDisplaysPerBlock.add(bn == null ? new ArrayList<>() : new ArrayList<>(bn.values()));
        }
        List<ColliderPair> colliders = new ArrayList<>();
        for (Map.Entry<Integer, Entity> e : carriers.entrySet()) {
            Shulker sh = shulkers.get(e.getKey());
            if (sh != null && e.getKey() >= 0 && e.getKey() < n) {
                colliders.add(new ColliderPair(e.getValue(), sh, e.getKey()));
            }
        }

        // Position comes from the recovered vehicle's own NBT (BlockShips reads it from the vehicle, never
        // from the sidecar — ShipInstance.recoverEntities), so a ship that drifted before the crash lands
        // where it actually is. For a static owned mechanism the vehicle is exactly at its saved pivot.
        Location pivot = vehicle.getLocation().clone();
        // F2: reproduce the assembly pivot frame. A DRIVEN mechanism maintains pivot = vehicle + 0.5 constantly
        // (assembly spawns the vehicle at an integer corner → floor+0.5 == +0.5; repositionDriven delta-tracks it),
        // and addDrivenBaseOffset reads (pivot − vehicle) LIVE — so it MUST equal +0.5. A recovered driven vehicle
        // is commonly at a FRACTIONAL coord (physics deltas + buoyancy Y; alignToGrid snaps only on explicit
        // align/disassemble), so a plain floor+0.5 would shift the whole ship by the fractional remainder. Owned
        // mechanisms use the block-center frame (floor+0.5). R1: a BLOCK-FREE (prefab) mechanism is a THIRD frame —
        // assembleFromParts sets pivot = raw vehicle location (offset 0, no snap) and the consumer bakes the corner
        // compensation into each part's localTransform because the base offset is 0. So a block-free mech must recover
        // with pivot = raw vehicle (leave it) — applying the custom-ship +0.5 here would shift the whole prefab ½
        // block diagonally, permanently. Gate the +0.5 on !st.blockFree.
        //
        // R2: all three branches below are now the LEGACY fallback, kept only for state files written before
        // the delta was persisted. They each SYNTHESIZE the frame constant from the mechanism's category, which
        // only works while every member of a category agrees on it — and that stopped being true once
        // assembleFromParts introduced a third frame (R1 above is that discovery). A recorded delta needs no
        // category: it is whatever assembly actually established, so it is correct for a corner-built ship, a
        // centre-built prefab, and an external cart on rails alike (the last is why the `else` branch is also
        // wrong in principle — floor(v_live)+0.5 reproduces floor(v0)+0.5 only when the two share a fractional
        // part, and a cart generally does not). Position still comes from the LIVE vehicle either way; only the
        // constant comes from disk, so the drifted-ship property above is preserved.
        if (st.hasPivotDelta) {
            pivot.add(st.dpx, st.dpy, st.dpz);
        } else if (st.blockFree) {
            // leave pivot = raw vehicle location (offset 0), matching assembleFromParts
        } else if (st.driven) {
            pivot.add(0.5, 0.5, 0.5);
        } else {
            pivot.setX(Math.floor(pivot.getX()) + 0.5);
            pivot.setY(Math.floor(pivot.getY()) + 0.5);
            pivot.setZ(Math.floor(pivot.getZ()) + 0.5);
        }
        Vector3f axis = new Vector3f(st.axisX, st.axisY, st.axisZ);
        BasicMechanism mech = new BasicMechanism(st.mechId, st.type, pivot, axis, vehicle, parent,
            st.rideOffset, st.ownsVehicle, displaysPerBlock, bannerDisplaysPerBlock, colliders, blocks,
            registry, null);
        mech.mechanismRegistry = this;
        mech.setPersisted(true); // stays persisted; a later explicit disassemble() removes the state file
        mech.setDriven(st.driven); // a driven (consumer-positioned) body keeps skipping updateFromVehicle
        mech.setBlockFree(st.blockFree); // a recovered prefab-style mech keeps its no-restore teardown

        // Defensively re-establish the passenger chain. Vanilla NBT normally restores display→parent→vehicle
        // and shulker→carrier, but a chunk reload can drop it (BlockShips re-adds carrier→shulker every ~20
        // ticks for exactly this — ShipInstance:1148-1150). Do it once here so the first rotate() lays out a
        // fully-mounted chain. addPassenger is a no-op when the link already exists.
        try {
            if (!vehicle.getPassengers().contains(parent)) vehicle.addPassenger(parent);
            for (List<Display> group : displaysPerBlock) {
                for (Display d : group) if (!parent.getPassengers().contains(d)) parent.addPassenger(d);
            }
            for (List<Display> group : bannerDisplaysPerBlock) {
                for (Display d : group) if (!parent.getPassengers().contains(d)) parent.addPassenger(d);
            }
            for (ColliderPair cp : colliders) {
                if (!cp.carrier().getPassengers().contains(cp.shulker())) cp.carrier().addPassenger(cp.shulker());
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Mechanism recovery: passenger re-mount failed for " + st.mechId
                + " (" + e.getMessage() + "); continuing (the block/collider entities are still bound)");
        }

        // Register BEFORE positioning so tickMechanisms + the collider read-API see it immediately.
        activeMechanisms.put(st.mechId, mech);
        for (ColliderPair cp : colliders) {
            colliderIndex.put(cp.shulker().getUniqueId(), new ColliderRef(mech, cp.blockIndex()));
        }

        // Re-adopt seats from the shulker tags detected above, then notify the consumer so it can re-mirror
        // health onto the seat entity it doesn't own. (No re-tagging: the tags persisted on the shulker.)
        for (Map.Entry<Integer, Boolean> seat : seatFlags.entrySet()) {
            Shulker sh = shulkers.get(seat.getKey());
            if (sh == null) continue; // seat's shulker in a not-yet-loaded neighbour chunk — adopted later
            mech.addRecoveredSeat(seat.getKey(), seat.getValue());
            fireSeatRecovered(mech, seat.getKey(), sh);
        }

        // Position the body at its saved orientation: sets currentYaw + currentTransform, lays out every
        // display on the parent, and repositions the free collider carriers. rotate() guards empty display
        // groups, so a still-loading block is skipped rather than fatal.
        mech.rotate(st.currentYaw);
        updateAnimatedDisplays(mech, 0L);

        // Rebuild the mechanism-LOCAL rotation network. onAssembled + RotationSolver read ONLY the in-memory
        // block list (customTypeId/customState/local offset/spinReversed) — no world block access — so this
        // works even though the source blocks aren't in the world (the moving-ship path already relies on
        // it). Only the first blocks.size() entries are real (recovery reconstructs no ghosts).
        if (rotationDriver != null) {
            try {
                rotationDriver.onAssembled(mech, blocks.size());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Mechanism recovery: rotation-network rebuild failed for "
                    + st.mechId + " (" + e.getMessage() + "); it renders/collides but won't spin until re-solved");
            }
        }

        // Recovery is finalized: this mechanism is now live. Drop it from the pending/in-flight sets.
        pendingRecoveries.remove(st.mechId);
        mechIdsBeingRecovered.remove(st.mechId);

        // Announce as a RECOVERY (recovered=true) so companion systems re-adopt it (re-mirror health, re-link
        // fuel) rather than treat it as a fresh build. Guarded: a listener throw must not abort recovery.
        boolean verticalAxis = Math.abs(axis.y) > 0.5f && axis.x == 0f && axis.z == 0f;
        try {
            Bukkit.getPluginManager().callEvent(
                new MechanismAssembleEvent(mech, st.type, pivot.clone(), mech.blockCount(), verticalAxis, true));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Mechanism recovery: a MechanismAssembleEvent listener threw for "
                + st.mechId + " (" + e.getMessage() + ")");
        }
    }

    /** Sum the sizes of the per-block ordered display sub-maps (extras/banners) — used for the found-entity
     *  count in the incremental recovery completeness gate. */
    private static int sumTree(Map<Integer, TreeMap<Integer, Display>> map) {
        int c = 0;
        for (TreeMap<Integer, Display> t : map.values()) c += t.size();
        return c;
    }

    // (chunkRadiusFor retired — the recovery footprint gate, the allLoaded sweep, and the getNearbyEntities
    //  query now all derive from the single cached MechanismState.recoveryHalf()/recoveryChunkRadius(), a
    //  rotation-exact magnitude bound, so the three can't diverge and a rotated part can't fall outside.)

    /** Add {@code d} to {@code map[blockIndex]} keyed by the integer suffix of {@code role} (e.g. "extra_3" → 3),
     *  so a block's extras/banners land back in their authored order regardless of entity iteration order. */
    private static void putIndexed(Map<Integer, TreeMap<Integer, Display>> map, int blockIndex,
                                   String role, String rolePrefix, Display d) {
        int ord;
        try { ord = Integer.parseInt(role.substring(rolePrefix.length())); }
        catch (NumberFormatException nf) { ord = map.getOrDefault(blockIndex, new TreeMap<>()).size(); }
        map.computeIfAbsent(blockIndex, k -> new TreeMap<>()).put(ord, d);
    }

    /** Reconstruct the {@link MechanismBlockData} list from a saved {@link MechanismState} — the inverse of
     *  {@link BasicMechanism#snapshotState}. Display/particle configs are RE-RESOLVED from the type registry
     *  via {@code customState} (in-memory, no world access) so a recovered custom block still animates; banners,
     *  {@code wasBare}, and the floor-head yaw are restored from the saved record. */
    private List<MechanismBlockData> rebuildBlocks(MechanismState st) {
        List<MechanismBlockData> out = new ArrayList<>(st.blocks.size());
        for (MechanismState.BlockRec b : st.blocks) {
            // Null for a block-free / standalone display part (P7.B): recovery re-adopts its persistent
            // ItemDisplay by tag, so the reconstructed part only needs its transform, not a block appearance.
            BlockData bd = b.blockData != null ? Bukkit.createBlockData(b.blockData) : null;
            Matrix4f local = new Matrix4f().set(b.localTransform); // column-major (matches snapshotState's get())
            CollisionConfig col = new CollisionConfig(b.colEnabled, b.colSize,
                new Vector3f(b.colOffX, b.colOffY, b.colOffZ));
            Inventory storage = null;
            if (b.storage != null) {
                try {
                    ItemStack[] items = ItemStack.deserializeItemsFromBytes(b.storage);
                    if (items.length > 0) {
                        // Recovery has no live BlockState, so re-derive the GUI shape. Prefer the persisted
                        // storage type (captured from the live inventory at assembly — exact for double
                        // chests and the only source for block-free prefab cargo, whose material is null and
                        // would otherwise fold to CHEST); fall back to the block material. Sizing by
                        // items.length alone throws for the fixed-shape containers (hopper/brewing 5,
                        // furnace family 3), and the catch below would turn that into a silently EMPTY
                        // container, i.e. item loss on every chunk recovery.
                        org.bukkit.event.inventory.InventoryType invType;
                        if (b.storageType != null) {
                            try {
                                invType = org.bukkit.event.inventory.InventoryType.valueOf(b.storageType);
                            } catch (IllegalArgumentException ex) {
                                // Name the bad token: falling back to the material can hand a fixed-shape
                                // container the CHEST size-branch, which throws into the catch below and
                                // restores it EMPTY under a generic "unreadable storage" warning.
                                plugin.getLogger().warning("Mechanism recovery: unknown storage type '"
                                    + b.storageType + "' in " + st.mechId + "; falling back to the block material");
                                invType = containerTypeOf(bd == null ? null : bd.getMaterial());
                            }
                        } else {
                            invType = containerTypeOf(bd == null ? null : bd.getMaterial());
                        }
                        // A captured container's name is JSON (formatting preserved); a prefab cargo title
                        // is plain text. Two keys rather than one sniffed key — a prefab title that looked
                        // like JSON would otherwise be silently reinterpreted.
                        net.kyori.adventure.text.Component title = null;
                        if (b.storageTitleJson != null) {
                            try {
                                title = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                                    .gson().deserialize(b.storageTitleJson);
                            } catch (RuntimeException ex) {
                                plugin.getLogger().warning("Mechanism recovery: unreadable storage title in "
                                    + st.mechId + " (" + ex.getMessage() + "); the container will be unnamed");
                            }
                        }
                        if (title == null && b.storageTitle != null) {
                            title = net.kyori.adventure.text.Component.text(b.storageTitle);
                        }
                        storage = createTypedInventory(null, invType, items.length, title);
                        storage.setContents(items.length > storage.getSize()
                            ? java.util.Arrays.copyOf(items, storage.getSize()) : items);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Mechanism recovery: unreadable storage for a block in "
                        + st.mechId + " (" + t.getMessage() + "); it will restore empty");
                }
            }
            // Re-derive the display/particle configs from the (static) state — the neighbour-aware
            // resolveMovingDisplays needs a live block, which recovery hasn't got, but the static configs
            // are enough to resume animation; rotate()/updateAnimatedDisplays position the existing extras.
            List<CustomHeadBlock.DisplayEntityConfig> decs = null;
            List<CustomHeadBlock.BlockDisplayEntityConfig> bdecs = null;
            CustomHeadBlock.ParticleConfig particles = null;
            if (b.customType != null) {
                CustomHeadBlock chb = registry.getType(b.customType);
                if (chb != null) {
                    decs = chb.resolveDisplayEntities(b.customState);
                    bdecs = chb.resolveBlockDisplayEntities(b.customState);
                    particles = chb.resolveParticles(b.customState);
                    // Overwrite the RESOLVED facing transforms the static resolve above can't reproduce
                    // (no live block on recovery). Only present for resolver-driven types; index-aligned
                    // with `decs` because both draw from the same resolveDisplayEntities(customState).
                    if (b.displayXf != null && decs != null) decs = applyDisplayXf(decs, b.displayXf, st.mechId);
                }
            }
            Vector3f wall = b.hasWallFacing ? new Vector3f(b.wfX, b.wfY, b.wfZ) : null;
            MechanismBlockData mbd = new MechanismBlockData(bd, local, col, b.customType, b.customState,
                decs, bdecs, particles, storage, b.spinReversed, wall);
            mbd.ghost = b.ghost;
            mbd.wasBare = b.wasBare;
            mbd.throttleLevel = b.throttleLevel;
            mbd.floorHeadYaw = b.hasFloorYaw ? b.floorYaw : null;
            mbd.glueOffsets = b.glueOffsets;
            mbd.configPdc = b.configPdc;
            mbd.blockEntitySnapshot = b.blockEntity;
            // Carry the GUI title forward, not just into the inventory built above: snapshotState reads
            // this field back when the mechanism is re-saved, so without it a recover→re-save cycle
            // drops the title permanently — correct for one session, blank ever after.
            mbd.storageTitle = b.storageTitle;
            mbd.storageTitleJson = b.storageTitleJson;
            if (b.banners != null) mbd.banners = rebuildBanners(b.banners, st.mechId);
            out.add(mbd);
        }
        return out;
    }

    /** Reconstruct riding banner attachments from a saved block's serialized maps (inverse of the banner
     *  block in {@link BasicMechanism#snapshotState}). All-or-nothing per block: one corrupt entry drops the
     *  whole block's banner list (returns null). The re-adopted {@code banner_k} displays pair to
     *  {@code mb.banners} positionally, and a display carries no {@code faceKey}, so a corrupt attachment
     *  cannot be reconstructed — keeping only the survivors would shift the pairing and make banners wear
     *  each other's transforms. Dropping is the safe choice (rare, save-corruption-only). Null if empty. */
    private @Nullable List<BannerAttachment> rebuildBanners(List<Map<String, Object>> raw, UUID mechId) {
        List<BannerAttachment> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            try {
                ItemStack item = ItemStack.deserializeBytes(
                    java.util.Base64.getDecoder().decode(String.valueOf(m.get("item"))));
                String face = String.valueOf(m.get("face"));
                List<?> xf = (List<?>) m.get("xf");
                org.bukkit.util.Transformation t = new org.bukkit.util.Transformation(
                    new Vector3f(bf(xf, 0), bf(xf, 1), bf(xf, 2)),
                    new org.joml.Quaternionf(bf(xf, 3), bf(xf, 4), bf(xf, 5), bf(xf, 6)),   // JOML order x,y,z,w
                    new Vector3f(bf(xf, 7), bf(xf, 8), bf(xf, 9)),
                    new org.joml.Quaternionf(bf(xf, 10), bf(xf, 11), bf(xf, 12), bf(xf, 13)));
                List<?> an = (List<?>) m.get("anchor");
                out.add(new BannerAttachment(item, face, t, new Vector3f(bf(an, 0), bf(an, 1), bf(an, 2))));
            } catch (Exception e) {
                // Drop the block's ENTIRE banner list — a shorter survivor list would mispair the
                // positionally-paired displays. Collateral: a vanilla banner block then lands blank (no
                // pattern write-back) and no banner items drop for this block.
                plugin.getLogger().warning("Mechanism recovery: unreadable banner for " + mechId
                    + " (" + e.getMessage() + "); dropping this block's banners — it will not re-attach or drop");
                return null;
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static float bf(List<?> l, int i) { return ((Number) l.get(i)).floatValue(); }

    /** Overwrite the facing-resolved transforms onto the statically-rebuilt display configs on recovery
     *  (the resolver can't run without a live block). Index-aligned with {@code decs}; per-entry guarded so a
     *  corrupt transform keeps the static config. Returns a fresh mutable list. */
    private List<CustomHeadBlock.DisplayEntityConfig> applyDisplayXf(
            List<CustomHeadBlock.DisplayEntityConfig> decs, List<Map<String, Object>> saved, UUID mechId) {
        List<CustomHeadBlock.DisplayEntityConfig> out = new ArrayList<>(decs);
        for (Map<String, Object> m : saved) {
            try {
                int i = ((Number) m.get("i")).intValue();
                if (i < 0 || i >= out.size()) continue;
                List<?> xf = (List<?>) m.get("xf");
                org.bukkit.util.Transformation t = new org.bukkit.util.Transformation(
                    new Vector3f(bf(xf, 0), bf(xf, 1), bf(xf, 2)),
                    new org.joml.Quaternionf(bf(xf, 3), bf(xf, 4), bf(xf, 5), bf(xf, 6)),   // JOML order x,y,z,w
                    new Vector3f(bf(xf, 7), bf(xf, 8), bf(xf, 9)),
                    new org.joml.Quaternionf(bf(xf, 10), bf(xf, 11), bf(xf, 12), bf(xf, 13)));
                CustomHeadBlock.DisplayEntityConfig o = out.get(i);
                out.set(i, new CustomHeadBlock.DisplayEntityConfig(o.displayItem(), t, o.tagSuffix(),
                    o.animation(), o.interpolationDuration(), o.wallOffset()));
            } catch (Exception e) {
                plugin.getLogger().warning("Mechanism recovery: unreadable display transform for " + mechId
                    + " (" + e.getMessage() + "); keeping the static config");
            }
        }
        return out;
    }

    private void tickMechanisms() {
        long currentTick = Bukkit.getServer().getCurrentTick();
        // Snapshot: a future in-body path that disassembles a mechanism would remove from
        // activeMechanisms mid-iteration → CME. Defensive; body is read-only today.
        for (BasicMechanism mech : new ArrayList<>(activeMechanisms.values())) {
            // Isolate each mechanism: a throw from one must not freeze the per-tick update of ALL
            // mechanisms (doors/rotators/minecarts/pistons) until a restart. Warn once per mech.
            try {
                // Auto-follow: update transforms if vehicle moved (e.g., minecart on rails)
                // Driven mechanisms are positioned by their consumer each tick (repositionDriven);
                // skip the vehicle auto-follow so the two don't fight (double-track / yaw clobber).
                if (!mech.driven) mech.updateFromVehicle();
                updateAnimatedDisplays(mech, currentTick - mech.startTick);
                if (rotationDriver != null) rotationDriver.tick(mech, currentTick - mech.startTick);
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
        // Static-only mechanism: skip the whole per-block scan AND refreshDrivenOffset. The driven-offset
        // cache is consumed only by addDrivenBaseOffset, which runs only in the animated branches below
        // (rotate() refreshes its own), so skipping it here is safe when there are no animated displays.
        if (!mech.hasAnimatedDisplays) return;
        mech.refreshDrivenOffset(); // cache (pivot − vehicle) once so mech.addDrivenBaseOffset below doesn't re-read it per display
        int[] animated = mech.animatedBlockIndices;
        for (int ai = 0; ai < animated.length; ai++) {
            int i = animated[ai];
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
                    dec.animation().apply(BasicMechanism.transformToMatrix(dec.transform(), placedMatrix), age, workMatrix);

                    // Place: pivot-rotation · block-offset · [wall offset] · animated-local.
                    // Additional displays are always ItemDisplay (center-rendered) — no XZ shift.
                    // placedMatrix is dead as the apply() base above; reuse it as the accumulator.
                    Matrix4f placed = placedMatrix.set(mech.currentTransform()).mul(mb.localTransform);
                    BasicMechanism.applyWallOffset(placed, mb.wallFacing, dec.wallOffset());
                    placed.mul(workMatrix);
                    placed.m31(placed.m31() - mech.rideOffset); // passenger offset — parent space, applied last
                    mech.addDrivenBaseOffset(placed); // driven corner→center frame reconciliation (same as rotate())
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
                    bdc.animation().apply(BasicMechanism.transformToMatrix(bdc.transform(), placedMatrix), age, workMatrix);

                    Matrix4f placed = placedMatrix.set(mech.currentTransform()).mul(mb.localTransform);
                    BasicMechanism.applyWallOffset(placed, mb.wallFacing, bdc.wallOffset());
                    placed.mul(workMatrix);
                    placed.m31(placed.m31() - mech.rideOffset); // passenger offset — parent space, applied last
                    mech.addDrivenBaseOffset(placed); // driven corner→center frame reconciliation (same as rotate())
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

    /** Wrench debug readout for a rotation block on an assembled mechanism (see
     *  {@code MechanismRotationDriver.rotationDebug}); null when the mechanism isn't driven or the block
     *  isn't a rotation node. */
    MechanismRotationDriver.@Nullable RotationDebug rotationDebug(BasicMechanism mech, int blockIndex) {
        return rotationDriver != null ? rotationDriver.rotationDebug(mech, blockIndex) : null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cleanup (called by BasicMechanism on disassemble/destroy)
    // ──────────────────────────────────────────────────────────────────────

    void onMechanismRemoved(BasicMechanism mech) {
        activeMechanisms.remove(mech.id());
        if (rotationDriver != null) rotationDriver.onRemoved(mech);
        for (ColliderPair cp : mech.colliders) {
            colliderIndex.remove(cp.shulker().getUniqueId());
        }
        // A persisted mechanism that is genuinely disassembled (blocks returned to the world) no longer
        // needs its state file. (Shutdown/world-unload of a persisted mechanism must NOT disassemble —
        // see the branched shutdown — so this only fires on a real teardown.)
        if (mech.isPersisted() && mech.pivot().getWorld() != null) {
            persistence.remove(mech.pivot().getWorld().getName(), mech.id());
        }
    }

    /** Remove a disassembled owned-vehicle mechanism's display/collider entities one tick later, so the
     *  just-placed blocks' own displays have a frame to render first (avoids the landing flicker). The mech is
     *  already unregistered by the caller, so the lingering entities are never ticked. */
    void deferEntityRemoval(BasicMechanism mech) {
        // During onDisable (shutdown() disassembling live mechs) the scheduler rejects new tasks
        // (IllegalPluginAccessException) — which used to throw AFTER block placement and send every
        // owned mech through shutdown()'s scary "removing entities without block restore" fallback.
        // There is no next frame to defer for at shutdown; remove synchronously.
        if (!plugin.isEnabled()) {
            mech.removeAllEntities();
            return;
        }
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

    /** Package-private: also used by {@link BasicMechanism#appendGhost} to add a block mid-flight. */
    BlockDisplay spawnMechBlockDisplay(Location loc, BlockData data,
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
