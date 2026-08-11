package anon.def9a2a4.corelib;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

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

    // Per-tick movement scratch — reused every rotate()/repositionColliders() pass so the display path
    // allocates near-nothing per block (the pattern battle-tested BlockShips-main uses; the collider path
    // still allocates carrier.getLocation() + a passenger list per collider). setTransformationMatrix
    // decomposes/copies the matrix, so a shared scratch is safe to hand it. Main-thread only; per instance,
    // so mechanisms never share these. NOT valid across a yield — read them within one synchronous pass.
    private final Matrix4f scratchBase = new Matrix4f();      // the per-block base dm
    private final Matrix4f scratchDisplay = new Matrix4f();   // each derived per-display matrix
    private final Matrix4f scratchTransform = new Matrix4f(); // transformToMatrix dest in the hot loop
    private final Vector3f scratchLocal = new Vector3f();
    private final Vector3f scratchWorld = new Vector3f();
    private @Nullable Location scratchColliderTarget;         // reused collider teleport target (lazy)
    // Cached driven corner→center offset (pivot − vehicle), refreshed once per positioning pass by
    // refreshDrivenOffset() so addDrivenBaseOffset applies it per block/display with no getLocation() alloc.
    private double drivenOffX, drivenOffY, drivenOffZ;

    final Entity vehicle;
    final org.bukkit.entity.BlockDisplay parent; // invisible intermediary — all displays mount here
    final float rideOffset; // passenger riding offset — varies by vehicle entity type
    final List<List<Display>> displaysPerBlock;
    // Parallel to displaysPerBlock/blocks: the banner ItemDisplays riding each block (one per
    // BannerAttachment in blocks.get(i).banners, same order; empty list for banner-less/ghost
    // blocks). Kept OUT of displaysPerBlock so the group shape [primary, itemExtras…, blockExtras…]
    // and its positional indexing (rotate/updateAnimatedDisplays/setBlockState) stay untouched.
    final List<List<Display>> bannerDisplaysPerBlock;
    final List<ColliderPair> colliders;
    // block index → its collider, for O(1) lookup (getColliderBoxByBlock/colliderEntity/seatEntity are
    // called per block per tick by driven consumers; a list scan there is O(N×C)). Built once from the
    // immutable colliders list; appended ghost blocks carry no collider, so it never goes stale.
    private final Map<Integer, ColliderPair> colliderByBlock;
    final List<MechanismBlockData> blocks;
    // Fast path for MechanismRegistry.updateAnimatedDisplays: the block indices carrying at least one
    // ANIMATED display/blockDisplay config. Computed at construction; refreshed by setBlockState (the only
    // post-construction config mutator). A mechanism with none (e.g. a 300-block static ship) skips the
    // whole per-tick scan. Ghost-appended blocks (appendGhost) carry null configs and land at the end, so
    // appending never animates a block nor stales these.
    boolean hasAnimatedDisplays;
    int[] animatedBlockIndices = new int[0];
    // Memoized Σ mechanismRegistry.massOf(block). Lazy (the registry back-ref is null during construction);
    // -1 = not computed. appendGhost — the sole `blocks` growth path — keeps it in step. Main-thread only.
    private double totalMassCache = -1;
    final CustomBlockRegistry registry;
    final @Nullable MechanismSerializer serializer;
    final long startTick;
    final boolean ownsVehicle; // true if we spawned it (should remove on destroy)
    final float assemblyYaw; // vehicle yaw at assembly — delta base for updateFromVehicle
    // Driven mode: a consumer positions the vehicle itself each tick and calls repositionDriven();
    // tickMechanisms then SKIPS updateFromVehicle for this mechanism so the two don't fight.
    boolean driven;
    // Persistence opt-in (set via MechanismRegistry.persist): a persisted mechanism is written to disk
    // and survives restart/crash; shutdown/onWorldUnload save-and-leave it rather than disassembling.
    // Default false → today's behaviour (disassemble on stop, no state file).
    boolean persisted;
    // Block-free (P7.B): this mechanism was assembled from an in-memory part list (a prefab ship), not from
    // world blocks — so it has NO blocks to restore. disassemble() routes to destroy() (drop riding
    // banners/storage + remove entities, no world write), and its parts may carry null blockData.
    boolean blockFree;

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
                   List<List<Display>> bannerDisplaysPerBlock,
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
        this.bannerDisplaysPerBlock = bannerDisplaysPerBlock;
        this.colliders = colliders;
        Map<Integer, ColliderPair> cbb = new HashMap<>(Math.max(4, colliders.size() * 2));
        for (ColliderPair cp : colliders) cbb.putIfAbsent(cp.blockIndex(), cp); // first-wins, matching the old scan
        this.colliderByBlock = cbb;
        this.blocks = blocks;
        recomputeAnimatedBlockIndices();
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
    @Override public @Nullable Entity vehicle() { return vehicle; }
    @Override public int blockCount() { return blocks.size(); }
    @Override public float getCurrentYaw() { return currentYaw; }
    @Override public MechanismBlockData getBlock(int index) { return blocks.get(index); }
    @Override public boolean hasCollision(int blockIndex) { return blocks.get(blockIndex).collision.enabled(); }

    @Override public int colliderCount() { return colliders.size(); }

    @Override
    public @Nullable BoundingBox getColliderBoxByBlock(int blockIndex) {
        ColliderPair cp = colliderByBlock.get(blockIndex);
        if (cp == null) return null;
        Shulker s = cp.shulker();
        return s.isValid() ? s.getBoundingBox() : null;
    }

    @Override
    public List<BoundingBox> colliderBoxes() {
        // O(collider-count), not O(blockCount) with per-index map lookups: iterate the collider list
        // directly (as native BlockShips does). Fresh list — never a shared scratch, so two ships' snapshots
        // held live at once by ShipCollisionCoordinator can't alias. Invalid/gone shulkers skipped.
        List<BoundingBox> out = new ArrayList<>(colliders.size());
        for (ColliderPair cp : colliders) {
            Shulker s = cp.shulker();
            if (s.isValid()) out.add(s.getBoundingBox());
        }
        return out;
    }

    @Override
    public @Nullable Shulker colliderEntity(int blockIndex) {
        ColliderPair cp = colliderForBlock(blockIndex);
        return (cp != null && cp.shulker().isValid()) ? cp.shulker() : null;
    }

    @Override
    public Display primaryDisplay(int blockIndex) {
        return displaysPerBlock.get(blockIndex).get(0);
    }

    @Override
    public double totalMass() {
        // Null back-ref: dead-safe fallback (set right after construction in practice). Return the LIVE
        // blocks.size() and DO NOT memoize — the registry may be wired up before the next call. This is the
        // only fallback; a legitimate 0 total is computed and cached as 0 (masses are clamped ≥0, so the
        // -1 sentinel is unambiguous).
        if (mechanismRegistry == null) return blocks.size();
        if (totalMassCache < 0) {
            double sum = 0;
            for (MechanismBlockData mb : blocks) sum += mechanismRegistry.massOf(mb);
            totalMassCache = sum;
        }
        return totalMassCache;
    }

    // ── Live rotation state (public API; see Mechanism) ──────────────────────
    // All three read the driver's cached last solve — no re-solve, no allocation beyond the debug
    // record. Null-safe for an undriven mechanism or a block that isn't a rotation node.

    private MechanismRotationDriver.@Nullable RotationDebug rotationInfo(int blockIndex) {
        if (mechanismRegistry == null || blockIndex < 0 || blockIndex >= blocks.size()) return null;
        return mechanismRegistry.rotationDebug(this, blockIndex);
    }

    @Override
    public boolean isRotationPowered(int blockIndex) {
        MechanismRotationDriver.RotationDebug d = rotationInfo(blockIndex);
        return d != null && d.powered();
    }

    @Override
    public int rotationSurplus(int blockIndex) {
        MechanismRotationDriver.RotationDebug d = rotationInfo(blockIndex);
        return d == null ? -1 : d.surplus();
    }

    @Override
    public @Nullable BlockFace rotationFacing(int blockIndex) {
        MechanismRotationDriver.RotationDebug d = rotationInfo(blockIndex);
        return d == null ? null : d.actuationFacing();
    }

    @Override
    public double blockMass(int blockIndex) {
        return mechanismRegistry == null ? 1.0 : mechanismRegistry.massOf(blocks.get(blockIndex));
    }

    @Override
    public @Nullable Inventory getStorage(int blockIndex) {
        return blocks.get(blockIndex).storage;
    }

    /** Force-close any player viewing a captured block's storage before its contents are copied back to
     *  the world (disassemble/destroy/drop). Otherwise the open view aliases a snapshot that is ALSO
     *  written to the landed block → the viewer extracts a duplicate. One chokepoint covers every
     *  teardown caller (RC-1). Viewer-count-agnostic; iterates a snapshot of getViewers(). */
    private void closeStorageViewers() {
        for (MechanismBlockData mb : blocks) {
            if (mb.storage == null) continue;
            for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(mb.storage.getViewers())) {
                viewer.closeInventory();
            }
        }
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

    /** Any mechanism-local face rotated by the current transform (consumer aim, fan blow direction,
     *  hopper mount, pipe chains). Null when mid-rotation leaves it off-axis. A consumer's true local
     *  aim comes from the driver's {@code NodeSpec.actuationFacing} (which resolves a floating head's
     *  up/down from state), not from a block's raw data. */
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
     * Like {@link #liveDirection} but never null: rounds a mechanism-local face, rotated by the current
     * transform, to its dominant cardinal axis (largest |component|). Used for the mounted drill's
     * crack/particle direction mid-sweep, when {@link #liveDirection} returns null for an off-axis facing.
     */
    BlockFace liveDirectionApprox(BlockFace local) {
        Vector3f v = currentTransform.transformDirection(
            new Vector3f(local.getModX(), local.getModY(), local.getModZ()), new Vector3f());
        float ax = Math.abs(v.x), ay = Math.abs(v.y), az = Math.abs(v.z);
        if (ax >= ay && ax >= az) return v.x >= 0 ? BlockFace.EAST : BlockFace.WEST;
        if (ay >= az) return v.y >= 0 ? BlockFace.UP : BlockFace.DOWN;
        return v.z >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /**
     * The world cell one step along {@code localAim} from a block:
     * {@code floor(pivot + currentTransform·(localTranslation + localAim))} — the true rotated block
     * centre stepped one unit along the rotated aim, then floored. Transforming the whole local target
     * cell (rather than flooring the block cell and adding a re-rounded cardinal step) keeps a consumer's
     * target on its real aim arc at any sweep angle; for an outward aim it can never floor onto the pivot
     * cell. Same convention as {@link #liveCell} ({@code currentTransform} is a pure rotation).
     */
    org.joml.Vector3i liveTargetCell(int index, BlockFace localAim) {
        Vector3f local = blocks.get(index).localTransform.getTranslation(new Vector3f())
            .add(localAim.getModX(), localAim.getModY(), localAim.getModZ());
        Vector3f off = currentTransform.transformPosition(local, new Vector3f());
        return new org.joml.Vector3i(
            (int) Math.floor(pivot.getX() + off.x),
            (int) Math.floor(pivot.getY() + off.y),
            (int) Math.floor(pivot.getZ() + off.z));
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
        refreshDrivenOffset(); // one vehicle.getLocation() for the whole pass, not one per block

        for (int i = 0; i < blocks.size(); i++) {
            MechanismBlockData mb = blocks.get(i);
            Matrix4f dm = scratchBase.set(rot).mul(mb.localTransform);
            dm.m31(dm.m31() - rideOffset); // compensate vehicle passenger riding offset
            // Driven-mode corner-vehicle → center-pivot frame reconciliation (see addDrivenBaseOffset).
            // dm is the per-block base every primary/aux/block/banner display derives from, so this one
            // call propagates to all of them.
            addDrivenBaseOffset(dm);

            // Primary display (index 0): BlockDisplay renders the unit cube from its MIN corner, so
            // shift -0.5 on ALL axes (in LOCAL space, post-multiply) to put the cube's true 3D center
            // at the transform origin. Combined with the block-centered pivot + center-based
            // localTransform, the cube then orbits its center about ANY cardinal axis (drawbridges).
            // ItemDisplay renders centered — no shift needed.
            List<Display> group = displaysPerBlock.get(i);
            // Partial-recovery safety: a rebound mechanism whose primary display for this block is still
            // loading (an adjacent chunk not yet in) has an empty group. Skip it rather than throw — the
            // block's other entities (collider) still reposition below, and rotate() is re-run on the next
            // tick's rebind pass once the display arrives. Never empty on a freshly assembled mechanism.
            if (group.isEmpty()) continue;
            Display primary = group.get(0);
            if (primary instanceof org.bukkit.entity.BlockDisplay) {
                Matrix4f bdm = scratchDisplay.set(dm).translate(-0.5f, -0.5f, -0.5f);
                primary.setTransformationMatrix(bdm);
            } else if (mb.wallFacing != null) {
                // Wall-mounted custom head: floor heads (wallFacing == null) are already center-bottom
                // correct; a wall head shifts +0.25 up and +0.25 toward its attachment face (= -wallFacing).
                // Applied in the LOCAL frame on a COPY of dm so it swings with the door and doesn't corrupt
                // the aux displays below (which reuse dm).
                //   ...and a yaw so the head FACES its wall direction. The static block gets this from the
                // real vanilla skull; the moving ItemDisplay is unrotated by default, so without this the
                // head renders facing the wrong way. rotateY is post-multiplied (applied to the model first,
                // before the translate/dm), i.e. the head yaws about its own centre, then is positioned.
                Matrix4f wdm = scratchDisplay.set(dm).translate(
                    -mb.wallFacing.x * 0.25f, 0.25f, -mb.wallFacing.z * 0.25f);
                wdm.rotateY(faceYawRadians(mb.wallFacing));
                primary.setTransformationMatrix(wdm);
            } else if (mb.floorHeadYaw != null) {
                // Floor custom head: the moving ItemDisplay spawns unrotated, so apply the skull's captured
                // 16-step yaw (already sign-corrected). Post-multiplied on a COPY of dm so it composes with
                // the mechanism's rotation (swings with rotators/drawbridges) and doesn't corrupt aux displays.
                // No wall translate — a floor head is already center-bottom correct.
                Matrix4f fdm = scratchDisplay.set(dm);
                fdm.rotateY(mb.floorHeadYaw);
                primary.setTransformationMatrix(fdm);
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
                    Matrix4f extra = scratchDisplay.set(dm);
                    applyWallOffset(extra, mb.wallFacing, dec.wallOffset());
                    extra.mul(transformToMatrix(dec.transform(), scratchTransform));
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
                    Matrix4f bd = scratchDisplay.set(dm);
                    applyWallOffset(bd, mb.wallFacing, bdc.wallOffset());
                    bd.mul(transformToMatrix(bdc.transform(), scratchTransform));
                    group.get(idx).setTransformationMatrix(bd);
                }
            }

            // Banner displays (parallel structure): dm (center frame, ride-compensated) shifted to
            // the attachment's anchor cell, then the captured display transformation — the exact
            // composition of the standing world display at identity rotation, so it swings with the
            // body about any axis.
            List<Display> bannerGroup = bannerDisplaysPerBlock.get(i);
            if (!bannerGroup.isEmpty() && mb.banners != null) {
                int n = Math.min(bannerGroup.size(), mb.banners.size());
                for (int b = 0; b < n; b++) {
                    Display bd = bannerGroup.get(b);
                    if (!bd.isValid()) continue;
                    BannerAttachment att = mb.banners.get(b);
                    Matrix4f m = scratchDisplay.set(dm)
                        .translate(att.anchorOffset().x, att.anchorOffset().y, att.anchorOffset().z)
                        .mul(transformToMatrix(att.transformation(), scratchTransform));
                    bd.setTransformationMatrix(m);
                }
            }
        }

        repositionColliders();
    }

    /**
     * Driven mode: the per-block displays are passengers of the CONSUMER's vehicle, which the consumer drives
     * in the block-CORNER frame (e.g. BlockShips spawns its ArmorStand at the block corner). But display
     * transforms are authored in the block-CENTER pivot frame (center-to-center localTransform + the -0.5
     * corner shift) — the SAME frame the colliders are teleported into by {@link #repositionColliders()}.
     * Without compensation the vehicle-anchored displays render (pivot - vehicle) = half a block low/-X/-Z
     * from the pivot-anchored colliders. Add that constant world offset so displays share the collider frame
     * (matching a single-frame consumer like native BlockShips). The offset is a constant (~+0.5/axis): the
     * pivot delta-tracks vehicle movement, so it never drifts and adds no jitter.
     *
     * <p>No-op unless {@code driven} (owned doors/drawbridges/minecarts have vehicle == the centered pivot).
     * This is the SOLE source of the correction — it MUST be called from EVERY display-positioning path
     * ({@link #rotate(float)} AND {@code MechanismRegistry.updateAnimatedDisplays}) so none can drift out of
     * frame. m30/m31/m32 are the world-space translation (the parent's entity yaw is frozen 0, so a display
     * matrix's translation is world-space).
     *
     * <p><b>CONTRACT — refresh before add, same synchronous pass.</b> This reads the offset CACHED by
     * {@link #refreshDrivenOffset()}, not {@code (pivot − vehicle)} live. Any caller MUST invoke
     * {@code refreshDrivenOffset()} once at the start of the same synchronous positioning pass, before its
     * first {@code addDrivenBaseOffset} call and with no vehicle move in between — otherwise this applies a
     * STALE offset from a previous pass (a silent display shift). Nothing enforces this at compile time (a
     * main-thread assert would check the thread, not that the refresh ran). Today's callers comply: {@code
     * rotate()} refreshes at its top; {@code updateAnimatedDisplays} refreshes after its static early-return
     * (safe because {@code addDrivenBaseOffset} runs only inside the animated branches that the early-return
     * skips). A future caller must uphold the same.
     */
    void addDrivenBaseOffset(Matrix4f m) {
        if (!driven) return;
        m.m30(m.m30() + (float) drivenOffX);
        m.m31(m.m31() + (float) drivenOffY);
        m.m32(m.m32() + (float) drivenOffZ);
    }

    /**
     * Recompute the cached driven corner→center offset {@code (pivot − vehicle)} once per positioning pass,
     * so {@link #addDrivenBaseOffset} can apply it per block/display without a {@code vehicle.getLocation()}
     * allocation on each call. MUST be called at the start of every pass that will invoke
     * {@code addDrivenBaseOffset} — {@link #rotate(float)} and {@code MechanismRegistry.updateAnimatedDisplays}.
     * No-op unless {@code driven} (vehicle is guaranteed non-null then).
     */
    void refreshDrivenOffset() {
        if (!driven) return;
        Location vl = vehicle.getLocation();
        drivenOffX = pivot.getX() - vl.getX();
        drivenOffY = pivot.getY() - vl.getY();
        drivenOffZ = pivot.getZ() - vl.getZ();
    }

    /** True if any display or blockDisplay config on this block carries an animation. */
    private static boolean blockIsAnimated(MechanismBlockData mb) {
        if (mb.displayEntityConfigs != null) {
            for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                if (mb.displayEntityConfigs.get(d).animation() != null) return true;
            }
        }
        if (mb.blockDisplayEntityConfigs != null) {
            for (int d = 0; d < mb.blockDisplayEntityConfigs.size(); d++) {
                if (mb.blockDisplayEntityConfigs.get(d).animation() != null) return true;
            }
        }
        return false;
    }

    /** Rebuild {@link #animatedBlockIndices}/{@link #hasAnimatedDisplays} from the current block configs.
     *  Called at construction and again by {@link #setBlockState} only when a state change flips a block's
     *  animated status. O(blocks). */
    private void recomputeAnimatedBlockIndices() {
        int[] tmp = new int[blocks.size()];
        int n = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (blockIsAnimated(blocks.get(i))) tmp[n++] = i;
        }
        animatedBlockIndices = java.util.Arrays.copyOf(tmp, n);
        hasAnimatedDisplays = n > 0;
    }

    /**
     * Teleport each free collider carrier to its cell under the current pivot + transform. Called from
     * rotate() (orientation changed) AND updateFromVehicle() (pivot translated) — colliders are not
     * parent passengers, so neither the parent teleport nor a frozen rotation moves them on their own.
     *
     * <p>localTransform is center-based in all axes and the pivot is block-centered, so rotating the
     * plain translation orbits the true block center. A sub-cube collider's block-local offset is added
     * inside the rotated frame (so it tracks the block through rotation), while the -0.5 Y — which
     * anchors the feet-anchored shulker box to the cell bottom — is world-space and applied OUTSIDE the
     * rotation. Matches the collider spawn in assembleCore.
     */
    private void repositionColliders() {
        for (ColliderPair cp : colliders) {
            int bi = cp.blockIndex();
            // Bounds guard: a recovered/mismatched collider index (blocks list rebuilt out of step with
            // the tags) must not throw mid-tick and freeze the whole mechanism.
            if (bi < 0 || bi >= blocks.size()) continue;
            MechanismBlockData mb = blocks.get(bi);
            Vector3f local = mb.localTransform.getTranslation(scratchLocal).add(mb.collision.offset());
            Vector3f worldOff = currentTransform.transformPosition(local, scratchWorld);
            if (scratchColliderTarget == null) scratchColliderTarget = new Location(pivot.getWorld(), 0, 0, 0);
            Location target = scratchColliderTarget;
            target.setWorld(pivot.getWorld());
            target.setX(pivot.getX() + worldOff.x);
            target.setY(pivot.getY() + worldOff.y - 0.5);
            target.setZ(pivot.getZ() + worldOff.z);
            target.setYaw(pivot.getYaw());   // match the old pivot.clone() (colliders don't rotate)
            target.setPitch(pivot.getPitch());
            Entity carrier = cp.carrier();
            // Movement threshold: skip a no-op teleport. Teleporting a carrier every tick (even at rest)
            // is needless churn — and on pre-1.21.9 each teleport ejects/re-adds passengers, which
            // jitters or dismounts a seated rider. Only move a carrier that actually moved.
            Location cur = carrier.getLocation();
            if (cur.getWorld() != null && cur.getWorld().equals(target.getWorld())
                    && cur.distanceSquared(target) < 1.0e-6) {
                continue;
            }
            // Nested-passenger safety: TeleportCompat re-mounts only the carrier's DIRECT passenger (the
            // shulker), not a rider seated ON the shulker (player → shulker → carrier). Capture seated
            // riders and re-mount any the teleport dropped. (No-op when nothing is seated / on 1.21.9+
            // where the native teleport retains the whole passenger subtree.)
            Shulker shulker = cp.shulker();
            List<Entity> nested = shulker.getPassengers().isEmpty()
                ? java.util.List.of() : new ArrayList<>(shulker.getPassengers());
            TeleportCompat.teleport(carrier, target);
            for (Entity p : nested) {
                if (p.isValid() && !shulker.getPassengers().contains(p)) shulker.addPassenger(p);
            }
        }
    }

    /**
     * Append a synthetic block to a mechanism that is already in flight, at {@code localOffset} from the
     * pivot. It rides the rigid body from this instant and lands like any other block on disassembly (a
     * protected landing cell is still skipped).
     *
     * <p>For a mover that <b>creates</b> material as it travels — the chain hoist pays a new link out of
     * its own cell every block — rather than sliding a pre-built rod like the piston. Assembly-time ghosts
     * can't express that: every link a stroke will ever need would have to exist at t=0, stacked in plain
     * air above the hoist. The alternative, writing real blocks into the world mid-stroke, is worse: the
     * body is drawn at the client's interpolated position, so a block placed at the server's position
     * appears ahead of it, and a stroke cut short lands the body on top of what it already wrote.
     *
     * <p>Appended blocks carry {@link CollisionConfig#NONE}: colliders are spawned per block at assembly and
     * indexed by position, and nothing here needs one — the load underneath carries the riders.
     */
    void appendGhost(Vector3f localOffset, BlockData data) {
        checkMainThread();
        if (disassembled) return;
        MechanismBlockData mb = new MechanismBlockData(data,
            new Matrix4f().translation(localOffset), CollisionConfig.NONE,
            null, null, null, null, null, null, false, null);
        mb.ghost = true;
        int index = blocks.size();
        blocks.add(mb);
        // Keep the memoized total in step: an appended ghost carries real BlockData, so massOf(mb) is a real
        // lookup and changes the sum. If the cache is still cold (-1) leave it — the next totalMass() sums
        // fresh, including this block. (No animated-index update needed: ghosts carry null configs and land
        // at the end, so no existing index shifts and no block becomes animated.)
        if (totalMassCache >= 0) totalMassCache += mechanismRegistry.massOf(mb);
        Display d = mechanismRegistry.spawnMechBlockDisplay(parent.getLocation(), data, id, index, "display");
        parent.addPassenger(d);
        displaysPerBlock.add(new ArrayList<>(List.of(d)));
        bannerDisplaysPerBlock.add(new ArrayList<>()); // keep the parallel structure aligned
        rotate(currentYaw);   // place it on the body now, rather than a frame late at the pivot
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

    /** Enable/disable driven mode. In driven mode the CONSUMER teleports the vehicle each tick and
     *  calls {@link #repositionDriven}; {@code tickMechanisms} skips {@link #updateFromVehicle}. */
    void setDriven(boolean driven) { this.driven = driven; }

    void setPersisted(boolean persisted) { this.persisted = persisted; }
    boolean isPersisted() { return persisted; }

    void setBlockFree(boolean blockFree) { this.blockFree = blockFree; }
    boolean isBlockFree() { return blockFree; }

    /** Build a serializable snapshot of this mechanism for persistence/recovery. Main thread only
     *  (reads live entity/block state). Display/particle configs are re-derived on recovery. */
    MechanismState snapshotState() {
        MechanismState st = new MechanismState();
        st.mechId = id;
        st.type = type;
        st.worldName = pivot.getWorld() != null ? pivot.getWorld().getName() : "world";
        st.px = pivot.getX(); st.py = pivot.getY(); st.pz = pivot.getZ();
        // Record (pivot − vehicle) so recovery reproduces this mechanism's frame constant exactly instead of
        // synthesizing one. Assembly fixes it at floor(v0)+0.5 − v0, which is +0.5 ONLY for a vehicle built on
        // an integer corner — true of a BlockShips wheel, false of assembleFromParts (raw vehicle location ⇒ 0)
        // and of an external cart. Same-world is not guaranteed: a consumer can teleport the vehicle across
        // worlds and snapshot before the next repositionDriven re-bases the pivot, and a cross-world subtract
        // is meaningless — leave the flag false there so recovery uses its fallback. A removed entity still
        // reports its last Location, so entity validity is not required here.
        if (vehicle != null && vehicle.getWorld().equals(pivot.getWorld())) {
            Location vl = vehicle.getLocation();
            st.hasPivotDelta = true;
            st.dpx = pivot.getX() - vl.getX();
            st.dpy = pivot.getY() - vl.getY();
            st.dpz = pivot.getZ() - vl.getZ();
        }
        st.axisX = rotationAxis.x; st.axisY = rotationAxis.y; st.axisZ = rotationAxis.z;
        st.currentYaw = currentYaw;
        st.rideOffset = rideOffset;
        st.ownsVehicle = ownsVehicle;
        st.driven = driven;
        st.blockFree = blockFree; // restored on recovery so the rebound mechanism keeps no-restore teardown
        st.vehicleUuid = vehicle != null ? vehicle.getUniqueId() : null;
        // Recovery-completeness count: vehicle + parent + every display/banner + each collider's carrier &
        // shulker. recoverOne warns if the entities it finds fall short (a chunk still settling / drifted).
        int ec = 2; // vehicle + parent
        for (List<Display> g : displaysPerBlock) ec += g.size();
        for (List<Display> g : bannerDisplaysPerBlock) ec += g.size();
        ec += colliders.size() * 2;
        st.entityCount = ec;
        for (MechanismBlockData mb : blocks) {
            MechanismState.BlockRec b = new MechanismState.BlockRec();
            b.blockData = mb.blockData != null ? mb.blockData.getAsString() : null; // null = block-free part

            mb.localTransform.get(b.localTransform); // column-major 16 floats
            b.colEnabled = mb.collision.enabled;
            b.colSize = mb.collision.size;
            b.colOffX = mb.collision.offset.x;
            b.colOffY = mb.collision.offset.y;
            b.colOffZ = mb.collision.offset.z;
            b.customType = mb.customTypeId;
            b.customState = mb.customState;
            b.spinReversed = mb.spinReversed;
            if (mb.wallFacing != null) {
                b.hasWallFacing = true;
                b.wfX = mb.wallFacing.x; b.wfY = mb.wallFacing.y; b.wfZ = mb.wallFacing.z;
            }
            if (mb.floorHeadYaw != null) {
                b.hasFloorYaw = true;
                b.floorYaw = mb.floorHeadYaw;
            }
            b.ghost = mb.ghost;
            b.wasBare = mb.wasBare;
            b.throttleLevel = mb.throttleLevel;
            if (mb.storage != null) {
                try {
                    b.storage = ItemStack.serializeItemsAsBytes(mb.storage.getContents());
                    b.storageType = mb.storage.getType().name(); // preserve the GUI type across recovery
                    b.storageTitle = mb.storageTitle;
                    b.storageTitleJson = mb.storageTitleJson;
                } catch (Throwable ignored) {
                    // unserializable inventory — skip storage (block still restores, just empty)
                }
            }
            b.glueOffsets = mb.glueOffsets;
            b.configPdc = mb.configPdc;
            b.blockEntity = mb.blockEntitySnapshot;
            // Riding banners (all kinds, incl. the BLOCK_FACE_KEY entry that carries a vanilla banner's
            // patterns). One YAML-safe map per attachment; transformation as 14 doubles (quaternions x,y,z,w).
            if (mb.banners != null && !mb.banners.isEmpty()) {
                List<Map<String, Object>> bl = new ArrayList<>(mb.banners.size());
                for (BannerAttachment a : mb.banners) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("item", java.util.Base64.getEncoder().encodeToString(a.item().serializeAsBytes()));
                    m.put("face", a.faceKey());
                    org.bukkit.util.Transformation t = a.transformation();
                    Vector3f tr = t.getTranslation(), sc = t.getScale();
                    org.joml.Quaternionf lq = t.getLeftRotation(), rq = t.getRightRotation();
                    m.put("xf", List.of(
                        (double) tr.x, (double) tr.y, (double) tr.z,
                        (double) lq.x, (double) lq.y, (double) lq.z, (double) lq.w,
                        (double) sc.x, (double) sc.y, (double) sc.z,
                        (double) rq.x, (double) rq.y, (double) rq.z, (double) rq.w));
                    Vector3f an = a.anchorOffset();
                    m.put("anchor", List.of((double) an.x, (double) an.y, (double) an.z));
                    bl.add(m);
                }
                b.banners = bl;
            }
            // Facing-RESOLVED item-display transforms: only for a type with a displayTransformResolver
            // (recovery's static resolveDisplayEntities can't reproduce them without a live block). {i, xf:[14]}.
            if (mb.customTypeId != null && mb.displayEntityConfigs != null && !mb.displayEntityConfigs.isEmpty()) {
                CustomHeadBlock dt = registry.getType(mb.customTypeId);
                if (dt != null && dt.displayTransformResolver() != null) {
                    List<Map<String, Object>> dl = new ArrayList<>();
                    for (int di = 0; di < mb.displayEntityConfigs.size(); di++) {
                        org.bukkit.util.Transformation t = mb.displayEntityConfigs.get(di).transform();
                        Vector3f tr = t.getTranslation(), sc = t.getScale();
                        org.joml.Quaternionf lq = t.getLeftRotation(), rq = t.getRightRotation();
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("i", di);
                        m.put("xf", List.of(
                            (double) tr.x, (double) tr.y, (double) tr.z,
                            (double) lq.x, (double) lq.y, (double) lq.z, (double) lq.w,
                            (double) sc.x, (double) sc.y, (double) sc.z,
                            (double) rq.x, (double) rq.y, (double) rq.z, (double) rq.w));
                        dl.add(m);
                    }
                    if (!dl.isEmpty()) b.displayXf = dl;
                }
            }
            st.blocks.add(b);
        }
        return st;
    }

    @Override
    public void repositionDriven(float relYaw) {
        checkMainThread();
        Location loc = vehicle.getLocation();
        // Cross-world guard (a consumer teleport across worlds): re-baseline, skip the velocity hint —
        // distanceSquared/subtract across worlds is meaningless and setVelocity would fling the body.
        if (loc.getWorld() == null || previousVehicleLoc.getWorld() == null
                || !loc.getWorld().equals(previousVehicleLoc.getWorld())) {
            // Preserve the maintained (pivot − vehicle) constant across the jump instead of re-snapping to
            // floor+0.5. addDrivenBaseOffset reads (pivot − vehicle) live, so it must stay equal to the assembly
            // comp (+0.5); a plain floor+0.5 re-snap discards a FRACTIONAL vehicle's remainder → a half-block-
            // remainder shift. pivot and previousVehicleLoc are co-world here (pre-jump), so the delta is valid.
            double cx = this.pivot.getX() - previousVehicleLoc.getX();
            double cy = this.pivot.getY() - previousVehicleLoc.getY();
            double cz = this.pivot.getZ() - previousVehicleLoc.getZ();
            this.pivot = loc.clone().add(cx, cy, cz);
            rotate(relYaw);
            this.previousVehicleLoc = loc.clone();
            this.previousVehicleYaw = loc.getYaw();
            return;
        }
        // Displacement since the last reposition — a client dead-reckoning hint so trackers extrapolate
        // the vehicle (and the passenger display chain riding it) between the ~3-tick position updates,
        // matching the per-tick collider teleports. Vehicle ONLY — never the carriers (that jitters
        // standing/seated riders). The consumer's own per-tick teleport stays authoritative for position.
        org.bukkit.util.Vector disp = loc.toVector().subtract(previousVehicleLoc.toVector());
        // Delta-track the pivot (accumulate movement) rather than overwriting it with the raw vehicle loc.
        // The pivot is the block-CENTER snapped frame established at assembly (floor+0.5); the consumer
        // drives the vehicle in the block-corner frame, so overwriting would discard that +0.5 and sink
        // every display + collider half a block (−X,−Y,−Z). Mirrors updateFromVehicle:delta-track + the
        // constructor contract ("deltas accumulate onto [the snapped pivot]").
        this.pivot.add(disp.getX(), disp.getY(), disp.getZ());
        rotate(relYaw);   // absolute about the axis; relYaw is "degrees from as-built" (rotate(0) == as-built)
        vehicle.setVelocity(disp);
        this.previousVehicleLoc = loc.clone();
        this.previousVehicleYaw = loc.getYaw();
    }

    // Velocity-preserving teleport flags (the position part is always absolute; these keep the rider's
    // existing momentum from being zeroed by the teleport). See carryRidersUp.
    private static final TeleportFlag[] CARRY_FLAGS = {
        TeleportFlag.Relative.VELOCITY_X, TeleportFlag.Relative.VELOCITY_Y,
        TeleportFlag.Relative.VELOCITY_Z, TeleportFlag.Relative.VELOCITY_ROTATION,
    };

    @Override
    public void carryRidersUp(double dy) {
        checkMainThread();
        if (dy <= 0 || colliders.isEmpty()) return;
        World w = pivot.getWorld();
        if (w == null) return;

        Set<Entity> riders = new HashSet<>();
        for (ColliderPair cp : colliders) {
            BoundingBox box = cp.shulker().getBoundingBox();
            // A thin slab straddling THIS collider's TOP face (whatever its real height is): catches an
            // entity whose feet rest on it (feet Y ~= box.maxY) without dragging along something merely
            // beside it.
            BoundingBox top = new BoundingBox(
                box.getMinX(), box.getMaxY() - 0.05, box.getMinZ(),
                box.getMaxX(), box.getMaxY() + 0.30, box.getMaxZ());
            for (Entity e : w.getNearbyEntities(top)) {
                if (isMechanismEntity(e)) continue;   // never lift ANY mechanism's own colliders/carriers/etc.
                // A passenger (e.g. a player SEATED on a seat shulker) is already carried up by its vehicle;
                // teleporting it here would just eject it from the seat. Skip anything that's already riding.
                if (e.getVehicle() != null) continue;
                riders.add(e);
            }
        }

        // Teleport each rider up by dy to an ABSOLUTE target (current + dy — keeps x/z/yaw/pitch). The
        // CARRY_FLAGS preserve their existing momentum across the teleport instead of us re-imposing it, so
        // horizontal walking and fall/jump velocity carry through.
        for (Entity e : riders) {
            Location to = e.getLocation().add(0, dy, 0);
            e.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN, CARRY_FLAGS);
        }
    }

    /** True for any DefCoreLib mechanism entity — collider shulker, carrier, vehicle, parent, or display —
     *  of ANY mechanism, identified by the shared "corelib:mech:" scoreboard-tag prefix.
     *  Package-visible: CoreLibPlugin's portal guard uses the same predicate rather than restating the
     *  tag convention. */
    static boolean isMechanismEntity(Entity e) {
        for (String tag : e.getScoreboardTags()) {
            if (tag.startsWith("corelib:mech:")) return true;
        }
        return false;
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

        // The new state may add or drop an animation on this block; keep the animated-index fast-path cache
        // current, but rebuild only on an actual flip (cheap membership scan otherwise).
        boolean nowAnimated = blockIsAnimated(mb);
        boolean wasAnimated = false;
        for (int idx : animatedBlockIndices) { if (idx == index) { wasAnimated = true; break; } }
        if (nowAnimated != wasAnimated) recomputeAnimatedBlockIndices();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void setOnDisassembled(@Nullable Consumer<List<Block>> callback) {
        this.onDisassembled = callback;
    }

    // Consumer disassembly seams (3a-ii). Null = engine defaults.
    private @Nullable CellPlacePolicy cellPlacePolicy = null;
    private @Nullable DropItemHook dropItemHook = null;
    private @Nullable Runnable beforeEntityRemoval = null;

    @Override public void setCellPlacePolicy(@Nullable CellPlacePolicy policy) { this.cellPlacePolicy = policy; }
    @Override public void setDropItemHook(@Nullable DropItemHook hook) { this.dropItemHook = hook; }
    @Override public void setBeforeEntityRemoval(@Nullable Runnable callback) { this.beforeEntityRemoval = callback; }

    // ── Seats (4a) ────────────────────────────────────────────────────────────
    // Seat = a collider block a player rides. Core marks it (+ tags its shulker so it survives recovery),
    // tracks occupancy, and repositions it (repositionColliders already re-mounts the nested rider and
    // skips no-op teleports). The consumer performs the actual addPassenger/dismount.
    private final Set<Integer> seatIndices = new HashSet<>();
    private final Set<Integer> driverSeatIndices = new HashSet<>();
    private final Map<Integer, UUID> seatOccupants = new HashMap<>();

    /** The collider (carrier+shulker) for a block index, or null if that block has none. */
    private @Nullable ColliderPair colliderForBlock(int blockIndex) {
        return colliderByBlock.get(blockIndex);
    }

    @Override
    public void designateSeat(int blockIndex, boolean driver) {
        checkMainThread();
        ColliderPair cp = colliderForBlock(blockIndex);
        if (cp == null) return; // no collider → nothing to ride
        seatIndices.add(blockIndex);
        if (driver) driverSeatIndices.add(blockIndex);
        Shulker s = cp.shulker();
        // Tag the (persistent) shulker so recovery re-detects the seat after a restart — no separate
        // serialization needed (idempotent; addScoreboardTag no-ops if already present).
        s.addScoreboardTag("corelib:mech:" + id + ":" + blockIndex + ":seat");
        if (driver) s.addScoreboardTag("corelib:mech:" + id + ":" + blockIndex + ":driver_seat");
        if (mechanismRegistry != null) mechanismRegistry.fireSeatSpawned(this, blockIndex, s);
    }

    /** Recovery path: re-record a seat detected from its shulker tag, WITHOUT re-tagging or firing
     *  onSeatSpawned (recoverOne fires onSeatRecovered instead). */
    void addRecoveredSeat(int blockIndex, boolean driver) {
        seatIndices.add(blockIndex);
        if (driver) driverSeatIndices.add(blockIndex);
    }

    @Override
    public @Nullable Shulker seatEntity(int blockIndex) {
        if (!seatIndices.contains(blockIndex)) return null;
        ColliderPair cp = colliderForBlock(blockIndex);
        return (cp != null && cp.shulker().isValid()) ? cp.shulker() : null;
    }

    @Override public boolean isSeat(int blockIndex) { return seatIndices.contains(blockIndex); }
    @Override public boolean isDriverSeat(int blockIndex) { return driverSeatIndices.contains(blockIndex); }
    @Override public Set<Integer> seatBlockIndices() { return new HashSet<>(seatIndices); }

    @Override
    public void setSeatOccupant(int blockIndex, @Nullable UUID player) {
        if (player == null) seatOccupants.remove(blockIndex);
        else seatOccupants.put(blockIndex, player);
    }

    @Override public @Nullable UUID seatOccupant(int blockIndex) { return seatOccupants.get(blockIndex); }

    @Override
    public Matrix4f landingRotation() {
        float snappedYaw = Math.round(currentYaw / 90f) * 90f;
        return new Matrix4f().rotate((float) Math.toRadians(-snappedYaw),
                rotationAxis.x, rotationAxis.y, rotationAxis.z);
    }

    // Cells that must never be overwritten on disassemble (e.g. a piston core the rod slides through).
    // A mechanism block whose landing cell is protected is discarded (not placed) — but its contained
    // player items (banners + captured storage) still drop, never silently swallowed.
    private java.util.@Nullable Set<CustomBlockRegistry.LocationKey> protectedCells = null;

    /** Mark cells that disassembly must not overwrite (consumed instead). */
    void setProtectedCells(java.util.Set<CustomBlockRegistry.LocationKey> cells) {
        this.protectedCells = cells;
    }

    @Override
    public void disassemble() {
        checkMainThread();
        if (disassembled) return;   // idempotent: a second pass would dupe the structure as items
        // A block-free mechanism (prefab ship, model-assembled — P7.B) has NO world blocks to restore. Its
        // teardown is destroy() semantics: drop riding banners/storage at each part's live cell, remove the
        // entities, deregister — never run the landing/cell/blockData math below (parts may have null block
        // data). destroy() shares the same `disassembled` latch, so this stays single-shot.
        //
        // The two CONSUMER hooks are fired around it by hand, because destroy() deliberately fires neither
        // (its own contract is "discard, no callbacks" and direct callers rely on that). Without this,
        // disassemble() would silently drop a documented public callback based on an internal flag — and
        // BlockShips already registers beforeEntityRemoval to move leads off the collider shulkers, so a
        // block-free ship would leak them. Ordering mirrors the block path below: beforeEntityRemoval while
        // the entities are still live, onDisassembled once they are gone. `placed` is List.of() — a
        // block-free teardown lands nothing, which is the honest argument here.
        if (blockFree) {
            if (beforeEntityRemoval != null) {
                try {
                    beforeEntityRemoval.run();
                } catch (Exception ignored) {
                    // consumer hook failed; proceed with teardown so nothing leaks (mirrors the block path)
                }
            }
            destroy();
            if (onDisassembled != null) onDisassembled.accept(List.of());
            return;
        }
        disassembled = true;
        // The idempotency latch above is set BEFORE any work, so a throw mid-teardown makes this mech
        // permanently un-retryable (a later disassemble() short-circuits). Guarantee the two teardown
        // invariants — deregister (else a ghost lingers in activeMechanisms, re-ticked forever, its
        // rotationDriver still driving a drill) and entity removal (else displays/colliders/vehicle
        // orphan) — via a finally, so every one of the ~16 disassemble() callers (many bare) is covered
        // by this one guard rather than a per-caller catch. No-op on the success path (completed=true).
        boolean completed = false;
        try {
        // RC-1: sever any open storage view BEFORE contents are copied back to landed blocks, or the
        // viewer aliases a snapshot that is also written to the world → duplicate on extract.
        closeStorageViewers();
        // Snap to 90° about the rotation axis. For Y this is yaw; for X/Z it tips a drawbridge
        // back to a cardinal orientation. 90° rotations about a cardinal axis map integer
        // offsets to integers, so block positions stay exact.
        float snappedYaw = Math.round(currentYaw / 90f) * 90f;
        // Settle the LIVE transform to the snapped landing before placing. Placement + glue snap via
        // landingRotation() regardless, but a mid-motion teardown (e.g. a rotator whose chunk unloads
        // mid-spin) leaves currentYaw at an arbitrary angle, so the lingering displays/colliders would
        // sit a quarter-turn off the cells the blocks land in. Driving currentYaw to snappedYaw makes
        // displays, colliders, glue and placement all coincide. No-op for a mechanism already at a
        // cardinal angle (currentYaw == 0 for every non-rotating type).
        if (currentYaw != snappedYaw) rotate(snappedYaw);
        Matrix4f rotation = landingRotation();

        // Banner landing frame: quarter-turns about the rotation axis. Only a Y-axis landing (or a
        // net-zero turn about any axis) leaves a banner's up-vector at +Y — anything else (X/Z
        // drawbridge at 90/180/270) has no re-attachable orientation, so banners drop as items.
        // floorMod, not %: rotators spin CW with NEGATIVE yaw, and a bare % would mint
        // non-canonical rot-tags downstream (see BlockRotation's identical normalization).
        int rotSteps = Math.floorMod(Math.round(snappedYaw / 90f), 4);
        boolean upright = Math.abs(rotationAxis.y) > 0.5f || rotSteps == 0;

        // The cells where blocks actually landed — handed to the glue rebind hook so an anchor's
        // offset set tracks the structure's new rest positions (dropped-as-item blocks are excluded).
        List<Block> placed = new ArrayList<>(blocks.size());

        // Shaft cells that were captured BARE (reverted to an encased head for the move) and must be
        // re-bared once landed. Drained AFTER the neighbor-notify funnel below — past the glue re-stamp and
        // chain-break guard — so the shaft only becomes a CHAIN once those CHAIN-walking passes have run.
        List<Block> rebareTargets = new ArrayList<>();

        // Carried-hoist chain-break guard: the cells where a captured CHAIN link failed to land (solid-win /
        // off-world drop). A shorter landed chain shifts a carried hoist's platform seed, so after the loop the
        // hoist OWNING each such cell has its glue invalidated (matches the reactive break guard in
        // ChainHoistManager). Full 3D cells, not (x,z) columns, so the owner is resolved by walking up the
        // rope — two hoists stacked in one column stay distinguishable. Lazily allocated — common case drops none.
        Set<Block> droppedChainCells = null;

        // Captured nested-anchor heads that landed (e.g. a piston head inside a rotator's swing): their
        // region is re-stamped AFTER the loop — once `placed` is known — so rebindLanded can prune the
        // glue to the cells that actually placed and propagate disconnection. Parallel lists: target
        // block + its pre-move offsets (mb.glueOffsets). Was an inline rebindLandedGlue call per anchor.
        // Hoist skulls are EXCLUDED (see the isHoist guard below): a hoist's offsets are stored
        // seed-relative (HoistAnchor), not skull-relative, so the generic BlockAnchor rebind here would
        // write garbage; configPdc restore strips corelib: keys, so the landed hoist simply re-derives
        // its platform from its seed/chain instead — the correct HoistAnchor-domain behavior.
        List<Block> landedAnchorTargets = new ArrayList<>();
        List<int[]> landedAnchorOffsets = new ArrayList<>();
        // F3: landed HOISTS route here instead of the generic list — their glue offsets are SEED-relative, so they
        // must be re-stamped through a HoistAnchor (seed origin), not the skull-relative BlockAnchor used below.
        List<Block> landedHoistTargets = new ArrayList<>();
        List<int[]> landedHoistOffsets = new ArrayList<>();

        // Two-pass landing, mirroring airOutSourceBlocks' two-pass removal in reverse: supports
        // first, attachables second — an attachable (banner/torch/sign/…) placed before its support
        // exists pops during setBlockData and drops WITHOUT its captured block-entity data.
        List<Integer> landingOrder = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            if (!FragileBlocks.isAttachable(blocks.get(i).blockData.getMaterial())) landingOrder.add(i);
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (FragileBlocks.isAttachable(blocks.get(i).blockData.getMaterial())) landingOrder.add(i);
        }

        for (int i : landingOrder) {
            MechanismBlockData mb = blocks.get(i);
            Vector3f worldOffset = rotation.transformPosition(
                mb.localTransform.getTranslation(new Vector3f()), new Vector3f());
            // localTransform is center-to-center (integer) and the pivot is block-centered, so a 90°
            // rotation maps integers to integers; Math.round handles trig epsilon (1.9999999f → 2).
            Location blockLoc = pivot.clone().add(
                Math.round(worldOffset.x), Math.round(worldOffset.y), Math.round(worldOffset.z));

            // Per-block failure isolation: a throw landing ONE block (setBlockData, a restore provider,
            // onRestore…) must not strand blocks k+1..n — the outer finally only deregisters + removes
            // entities, and the `disassembled` latch makes a re-run impossible. On a caught throw, best-effort
            // drop THIS block and carry on. `fallbackDrop` gates that drop: it starts true and is cleared the
            // instant the block is committed to the world (post-placeBlock, which is post-commit-safe) or a
            // branch takes ownership of the block's disposal (its own drop) or deliberately withholds one
            // (protected / SKIP / ghost). So the fallback can neither re-drop a placed block (dupe) nor mint a
            // drop a consume/ghost branch exists to suppress. blockLoc is pure math, kept above the try.
            boolean fallbackDrop = true;
            try {
            // Off-world guard: a tall drawbridge can swing a block below world-min or above
            // world-max. Don't try to place there — drop it as an item instead.
            if (blockLoc.getBlockY() < blockLoc.getWorld().getMinHeight()
                    || blockLoc.getBlockY() >= blockLoc.getWorld().getMaxHeight()) {
                droppedChainCells = noteIfChain(droppedChainCells, mb, blockLoc);
                fallbackDrop = false; // this branch owns the drop
                dropBlockAsItem(blockLoc, mb);
                continue;
            }
            Block target = blockLoc.getBlock();

            // Protected cell (e.g. the piston core the rod slid through): the block is consumed (not placed)
            // — but never its contents; banners AND captured storage drop, or they'd vanish with the block.
            if (protectedCells != null && protectedCells.contains(CustomBlockRegistry.LocationKey.of(target))) {
                fallbackDrop = false; // block intentionally consumed — the fallback must not mint it
                droppedChainCells = noteIfChain(droppedChainCells, mb, blockLoc);
                dropBannerItems(blockLoc, mb);
                dropStorageItems(blockLoc, mb);
                continue;
            }

            // Consumer pre-place policy (3a-ii): redirect this cell to a drop (anti-laundering across a
            // protected boundary) or skip it, before the normal air/fragile/solid dispatch below.
            if (cellPlacePolicy != null) {
                PlaceDecision decision;
                try {
                    decision = cellPlacePolicy.decide(target, mb);
                } catch (RuntimeException ex) {
                    // A misbehaving consumer policy must not fall to the per-block guard's fallback drop
                    // (which would mint an item on a ghost cell, or drop one a SKIP cell means to withhold).
                    // Default to PLACE — the normal dispatch below then handles this cell exactly as it
                    // would with no policy set, and each branch clears fallbackDrop itself.
                    registry.getPlugin().getLogger().log(Level.WARNING,
                        "disassemble: cellPlacePolicy.decide threw at " + blockLoc + "; defaulting to PLACE", ex);
                    decision = PlaceDecision.PLACE;
                }
                if (decision == PlaceDecision.DROP) {
                    droppedChainCells = noteIfChain(droppedChainCells, mb, blockLoc);
                    fallbackDrop = false; // this branch owns the drop
                    dropBlockAsItem(blockLoc, mb);
                    continue;
                }
                if (decision == PlaceDecision.SKIP) {
                    fallbackDrop = false; // block intentionally skipped — the fallback must not mint it
                    droppedChainCells = noteIfChain(droppedChainCells, mb, blockLoc);
                    dropBannerItems(blockLoc, mb); // never silently swallow a host's banners…
                    dropStorageItems(blockLoc, mb); // …or its captured storage
                    continue;
                }
                // PLACE → fall through to the normal dispatch
            }

            if (MovableBlocks.isEmptyOrPassable(target.getType())) {
                // Overwrite air/fluid — and an ephemeral LIGHT block a stroke swept through (else it would
                // fall to the "solid wins" branch below and drop this block as an item + explosion).
                placeBlock(target, mb, snappedYaw);
                fallbackDrop = false; // committed to the world (placeBlock is post-commit-safe)
                placed.add(target);
                if (mb.wasBare) rebareTargets.add(target);
                landBanners(target, mb, snappedYaw, upright);
                if (mb.glueOffsets != null) { if (ChainHoistManager.isHoist(target, registry)) { landedHoistTargets.add(target); landedHoistOffsets.add(mb.glueOffsets); } else { landedAnchorTargets.add(target); landedAnchorOffsets.add(mb.glueOffsets); } }
            } else if (FragileBlocks.isFragile(target.getType())) {
                target.breakNaturally();
                placeBlock(target, mb, snappedYaw);
                fallbackDrop = false; // committed to the world (placeBlock is post-commit-safe)
                placed.add(target);
                if (mb.wasBare) rebareTargets.add(target);
                landBanners(target, mb, snappedYaw, upright);
                if (mb.glueOffsets != null) { if (ChainHoistManager.isHoist(target, registry)) { landedHoistTargets.add(target); landedHoistOffsets.add(mb.glueOffsets); } else { landedAnchorTargets.add(target); landedAnchorOffsets.add(mb.glueOffsets); } }
            } else if (mb.ghost && target.getBlockData().equals(mb.blockData)) {
                fallbackDrop = false; // ghost silent-discard — dropping would mint an item from nothing
                // A blocked GHOST whose cell already holds its identical block is discarded silently:
                // ghosts are data-only (never captured from the world), so dropping one here mints an
                // item from nothing. The concrete case: a hoist reel-in stopped short lands its emerging
                // ghost link on the still-real swallowed link it was visually duplicating — solid-wins
                // dropped a phantom chain item on every interrupted rise (farmable by pulsing power).
            } else {
                // Solid block wins — explosion effect + drop mechanism block as item
                target.getWorld().spawnParticle(Particle.EXPLOSION,
                    blockLoc.clone().add(0.5, 0.5, 0.5), 1);
                droppedChainCells = noteIfChain(droppedChainCells, mb, blockLoc);
                fallbackDrop = false; // this branch owns the drop
                dropBlockAsItem(blockLoc, mb);
            }
            } catch (RuntimeException ex) {
                registry.getPlugin().getLogger().log(Level.WARNING,
                    "disassemble: landing block index " + i + " at " + blockLoc
                    + " failed; best-effort drop, continuing teardown", ex);
                if (fallbackDrop) {
                    try {
                        droppedChainCells = noteIfChain(droppedChainCells, mb, blockLoc);
                        dropBlockAsItem(blockLoc, mb);
                    } catch (RuntimeException ignore) { /* drop itself failed; single-block loss, keep going */ }
                }
            }
        }

        // Re-stamp captured nested-anchor glue onto each landed skull, pruned to the cells that actually
        // placed (disconnection propagated). Deferred to here so `placed` is complete; runs BEFORE the
        // chain-break guard below so that guard still wins (it fully invalidates a short-chained hoist).
        if (!landedAnchorTargets.isEmpty()) {
            Set<Block> placedSet = new HashSet<>(placed);
            for (int k = 0; k < landedAnchorTargets.size(); k++) {
                // Cap the sticky-closure walk by the SAME maxSize authoring used (via the registry) — a
                // smaller cap (e.g. blocks.size()) can starve a legitimate derived bridge and over-prune.
                int cap = mechanismRegistry != null ? mechanismRegistry.glueMaxSize() : Integer.MAX_VALUE;
                // A foreign plugin's anchor must land as a ProvidedAnchor, not a plain BlockAnchor:
                // the owner decides whether the connectivity prune applies, and a ship's would delete
                // nearly all of its glue here (its extras sit on a hull that is not itself glued).
                // Falls back to BlockAnchor when no provider claims the block — the engine's own case.
                Block landed = landedAnchorTargets.get(k);
                ExternalAnchor ext = Anchors.externalFor(landed);
                Anchor anchor = ext != null ? new ProvidedAnchor(ext) : new BlockAnchor(landed, () -> true);
                GlueManager.rebindLanded(registry, cap, anchor,
                    landedAnchorOffsets.get(k), rotation, placedSet);
            }
        }

        // F3: re-stamp landed HOISTS via a HoistAnchor (seed-relative origin, re-derived from the now-landed
        // column) — the generic BlockAnchor above uses the skull as origin, which is the wrong frame for a hoist's
        // seed-relative offsets (why they were previously excluded, losing the glue). Runs BEFORE the chain-break
        // guard below so a short-chained hoist still gets its (mis-referenced) glue wiped — the guard wins. The
        // `upright` gate mirrors that guard: a hoist only nests upright, so a hypothetical non-upright landing
        // degrades to "glue lost" rather than stamping against a sideways-mapped seed.
        if (upright && !landedHoistTargets.isEmpty()) {
            Set<Block> placedSet = new HashSet<>(placed);
            int cap = mechanismRegistry != null ? mechanismRegistry.glueMaxSize() : Integer.MAX_VALUE;
            for (int k = 0; k < landedHoistTargets.size(); k++) {
                GlueManager.rebindLanded(registry, cap,
                    new HoistAnchor(landedHoistTargets.get(k), registry, () -> true),
                    landedHoistOffsets.get(k), rotation, placedSet);
            }
        }

        // A carried hoist that landed with a short chain (a link solid-won / went off-world above): its
        // platform seed is derived from the live chain depth, so a shorter chain shifts the seed and the
        // stored glue now mis-references. Wipe the OWNING hoist's glue — same "chain broke → invalidate" rule
        // as ChainHoistManager's reactive guard. Attribute each dropped link to its hoist by walking UP the
        // (now fully landed) rope, exactly like the reactive path (owningHoist); this keeps two hoists stacked
        // in one column distinct. Walk-up only holds for an upright landing (a horizontal rotation maps the
        // column sideways), which GlueManager.expandNested guarantees — the guard co-locates that invariant.
        // Resolve from EVERY dropped cell, not one: walking up from a lower cell in a multi-link drop hits the
        // gap above it and yields null; only the topmost dropped cell reaches the head. Dedupe (clearOffsets
        // is idempotent) to avoid redundant PDC writes.
        if (droppedChainCells != null && upright) {
            Set<Block> landed = new HashSet<>(placed);
            Set<CustomBlockRegistry.LocationKey> cleared = new HashSet<>();
            for (Block cell : droppedChainCells) {
                Block hoist = ChainHoistManager.owningHoist(cell, registry);
                // Only invalidate a hoist that was part of THIS landing. The walk-up reads the live world, so
                // a carried rope overlapping a PRE-EXISTING stationary hoist's column would otherwise resolve
                // to that bystander (whose chain never changed) and wrongly wipe its glue. Stacked carried
                // hoists both land → both in `placed`, so this keeps the stacked-hoist fix intact.
                if (hoist == null || !landed.contains(hoist)
                        || !cleared.add(CustomBlockRegistry.LocationKey.of(hoist))) continue;
                HoistAnchor a = new HoistAnchor(hoist, registry, () -> true);
                if (GlueManager.isValidOffsets(a.readOffsets())) a.clearOffsets();
            }
        }

        // Banner mech-displays go NOW, not via the 1-tick deferral below: the re-attached world
        // banner's spawn packet renders this same tick, so deferring would only z-fight two
        // coincident banners for a frame. (removeAllEntities re-visits them — Entity.remove is
        // idempotent.) The deferral stays for primary/extras, whose landed custom-block displays
        // need the frame.
        for (List<Display> bannerGroup : bannerDisplaysPerBlock) {
            for (Display d : bannerGroup) d.remove();
        }

        // Consumer pre-removal seam (3a-ii): every block is placed, but the display/collider/vehicle
        // entities are still LIVE — the window to read live collider state against the freshly-placed
        // blocks (e.g. re-parent leads off collider shulkers onto the landed fences). Best-effort: a hook
        // throw must not abort teardown (blocks placed but entities leaked), so swallow and continue.
        if (beforeEntityRemoval != null) {
            try {
                beforeEntityRemoval.run();
            } catch (Exception ignored) {
                // consumer hook failed; proceed with entity removal so nothing leaks
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
        // Landed blocks were written physics-suppressed (placeBlock), so notify reactive neighbors
        // explicitly — pipes reconnect, and a rotation node re-scans to pick up an adjacent landed
        // passive windmill. Replaces the BlockPhysicsEvent we no longer emit. This is the single funnel
        // for every mechanism mover (pistons, chain hoists, rotators, drawbridges, minecart-carried).
        for (Block b : placed) registry.notifyBlockAppearedOrMoved(b);
        // Re-bare any shaft that rode bare (reverted to an encased head for the move). Deferred to here —
        // past the glue re-stamp and chain-break guard, which walk CHAIN columns — so the shaft becomes a
        // CHAIN only now. makeShaftBare (the reland handler) re-adds/recalcs its rotation node; the node
        // already exists from restoreBlock's onChunkLoad above, so it takes the recalculate branch.
        // Resolve the type per-block from the just-landed encased head (getTypeFromBlock), not a hardcoded
        // "mech:shaft", so a future revert-handler bare type re-bares as itself rather than as a shaft.
        for (Block b : rebareTargets) registry.rebareAfterLanding(b, registry.getTypeFromBlock(b));
        completed = true;
        } finally {
            if (!completed) {
                // Body threw mid-teardown. Both calls are idempotent (activeMechanisms.remove /
                // colliderIndex.remove / rotationDriver.onRemoved are map-removes; removeAllEntities is
                // idempotent), so a late throw after the normal onMechanismRemoved at line 633 re-runs
                // harmlessly. Remove entities synchronously — this is an error path where a 1-frame
                // owned-vehicle flicker is irrelevant, and we may have thrown before the deferral was
                // ever scheduled, so the deferred removal can't be relied on.
                if (mechanismRegistry != null) mechanismRegistry.onMechanismRemoved(this);
                removeAllEntities();
            }
        }
    }

    /**
     * Block-free teardown: remove the mechanism's entities WITHOUT restoring any world blocks — the teardown a
     * prefab (block-free) mechanism gets, and the error/rollback teardown for any mechanism.
     *
     * <p><b>Cargo-drop ownership (contract).</b> For a block-free mechanism the ENGINE is the single drop
     * authority: this method drops each part's captured storage contents AND riding banners at that part's live
     * cell. A consumer MUST NOT also drop the cargo — doing both duplicates items. (BlockShips relies on this:
     * a delegated prefab keeps its cargo on the mechanism, not in the consumer's own storage map.)
     *
     * <p><b>What destroy() deliberately does NOT do.</b> Unlike the block path's {@link #disassemble()}, it fires
     * NEITHER {@code beforeEntityRemoval} NOR {@code onDisassembled}, and it does NOT consult {@code DropItemHook}
     * or drop a block-item for the discarded appearance. The consumer owns the "drop the whole thing as an item"
     * behaviour (e.g. a ship-kit item on death) and any graceful lead/passenger transfer.
     */
    @Override
    public void destroy() {
        checkMainThread();
        // Share disassemble()'s terminal latch: destroy() and disassemble() are mutually-exclusive teardowns,
        // so whichever runs first makes the other a no-op — else a second pass re-drops banners/storage (dupe).
        if (disassembled) return;
        disassembled = true;
        // Latch is set before any work (like disassemble()), so the teardown below MUST be finally-guarded:
        // a throw in the drop loop would otherwise skip deregister + entity removal, leaving a re-ticked ghost
        // in activeMechanisms with leaked entities — made permanent by the latch. Both teardown calls are
        // idempotent (see disassemble()).
        try {
            // RC-1: sever any open storage view before contents are dropped to the world (see disassemble()).
            closeStorageViewers();
            // destroy() discards the blocks by design, but the riding banners AND captured storage would
            // otherwise vanish with them — their world displays/contents are already gone (removed at capture),
            // so dropping nothing here would delete the items with no trace. Drop both at each block's live cell.
            World w = pivot.getWorld();
            if (w != null) {
                for (int i = 0; i < blocks.size(); i++) {
                    MechanismBlockData mb = blocks.get(i);
                    // blockEntitySnapshot is in the guard because it can carry player items too (a lectern's
                    // book, a chiseled bookshelf's contents) — skipping on it would delete exactly the cargo
                    // dropStorageItems was widened to save.
                    if (mb.banners == null && mb.storage == null && mb.blockEntitySnapshot == null) continue;
                    // Per-block isolation (mirrors disassemble()'s landing loop): one failed drop must not
                    // skip the rest, and — with the latch set — must not escape into the finally either.
                    try {
                        org.joml.Vector3i cell = liveCell(i);
                        Location loc = new Location(w, cell.x, cell.y, cell.z);
                        if (mb.banners != null) dropBannerItems(loc, mb);
                        dropStorageItems(loc, mb);   // null-safe: no-op when this block carried no items
                    } catch (RuntimeException ex) {
                        registry.getPlugin().getLogger().log(Level.WARNING,
                            "destroy: drop failed for block " + i + " (continuing)", ex);
                    }
                }
            }
        } finally {
            // Deregister BEFORE removing entities: if removeAllEntities threw first, the mech would linger
            // in activeMechanisms as a ghost. Order between the two is independent (onMechanismRemoved reads
            // mech.colliders, which removeAllEntities doesn't clear).
            if (mechanismRegistry != null) mechanismRegistry.onMechanismRemoved(this);
            removeAllEntities();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    private void placeBlock(Block target, MechanismBlockData mb, float snappedYaw) {
        // Destination-authoritative waterlog: capture cleared it (see assembleCore), so a waterloggable
        // block landing INTO a water source re-waterlogs. Sample the target BEFORE we overwrite it. Only a
        // true source (Levelled level 0), never transient flowing water — matches BlockShips.
        boolean intoWaterSource = target.getType() == Material.WATER
            && target.getBlockData() instanceof org.bukkit.block.data.Levelled lv && lv.getLevel() == 0;

        // Attachables land physics-suppressed (matching airOutSourceBlocks' physics-false removal):
        // the two-pass landing order puts supports first, but a support that was consumed/dropped
        // would still pop the attachable DURING setBlockData — before its block-entity data
        // (banner patterns) is written back, dropping a blank item.
        boolean attachable = FragileBlocks.isAttachable(mb.blockData.getMaterial());
        BlockData landed = BlockRotation.rotateBlockData(mb.blockData, snappedYaw);
        if (intoWaterSource && landed instanceof org.bukkit.block.data.Waterlogged wl && !wl.isWaterlogged()) {
            landed = landed.clone();
            ((org.bukkit.block.data.Waterlogged) landed).setWaterlogged(true);
        }
        target.setBlockData(landed, !attachable);   // COMMIT POINT: the sole world write, and the one
        // statement left OUTSIDE the try below. Everything below runs against an already-committed block, so
        // isolate it — a throw in the restoration tail must NOT propagate out of placeBlock, or disassemble()'s
        // per-block guard would drop this already-placed block as an item (dupe). Swallow + log; the block
        // stays placed, at worst with partial state. (A throw from the commit call ITSELF is left uncaught on
        // purpose: it's near-unreachable — Bukkit swallows physics-listener exceptions — and leaving it out
        // lets a genuine pre-write failure fall through to the per-block guard and recover the block as a drop.)
        try {

        // Vanilla banner block: write the captured patterns back into the landed block entity
        // (the orientation was already rotated by rotateBlockData above).
        if (mb.banners != null && target.getState() instanceof org.bukkit.block.Banner bannerState) {
            for (BannerAttachment att : mb.banners) {
                if (!att.isBlockBanner()) continue;
                if (att.item().getItemMeta() instanceof org.bukkit.inventory.meta.BannerMeta meta) {
                    bannerState.setPatterns(meta.getPatterns());
                    bannerState.update(true, false);
                }
                break;
            }
        }

        if (mb.customTypeId != null) {
            CustomHeadBlock type = registry.getType(mb.customTypeId);
            if (type != null && type.baseBlock() != null) {
                // Bare-first block (e.g. casing = OAK_STAIRS): no block-entity, so markBlock can't stamp
                // it. The base block was placed by setBlockData above; register its identity in the
                // display-backed registry (durable chunk PDC + tagged display) and respawn the display.
                // (The shaft is captured/re-placed as an encased head — baseBlock() null — so it takes the
                // skull path below.) A symmetric bare block has no rotatable state; keep the captured one.
                // A type that pins its data is the exception: rotateBlockData above would have turned the
                // casing's stair to a new facing, and a landed casing perpendicular to its neighbours is
                // exactly what the pin exists to prevent. Re-assert it — the shell hides the stair anyway.
                org.bukkit.block.data.BlockData pinned = type.baseBlockData();
                if (pinned != null) target.setBlockData(pinned, false);
                String landedState = mb.customState;
                registry.addBareBlock(target, type);
                int power = registry.readPower(target, type);
                registry.applyConfig(target, type, landedState, power);
                registry.restoreBlock(target, type, landedState);
                if (mb.storage != null) registry.restoreStorageSnapshot(target, mb.storage);
                registry.restoreConfigPdc(target, mb.configPdc);   // usually null for a bare block
                // Re-load any side-store state from the restored PDC, mirroring the skull arm below. Latent
                // today (no bare-first type registers onRestore), but keeps the capture/restore hook symmetric.
                if (type.onRestore() != null) type.onRestore().accept(target);
                registry.applyThrottleLevel(target, mb.throttleLevel);   // no-op unless a throttle (bare-first)
                flipLandedSpinDir(target, mb, snappedYaw);
            } else if (type != null) {
                // The vanilla data was rotated above; re-derive the custom state for the landed
                // orientation so it doesn't snap to an impossible state (and rejoins the network on the
                // correct axis).
                String landedState = BlockRotation.rotateCustomState(type, mb.customState, target.getBlockData());
                // A wall ratchet's allowed CW/CCW lives in its state token, but rotateCustomState rebuilds
                // orientation from the placement map (which hard-codes cw) — losing a CCW setting and never
                // axis-flipping it. Re-inject the captured direction, flipped iff the landing negated the
                // spin axis. No-op for every non-ratchet state (none carry a cw/ccw token).
                landedState = BlockRotation.preserveSpinToken(mb.customState, landedState, snappedYaw);
                registry.markBlock(target, type, landedState);
                int power = registry.readPower(target, type);
                registry.applyConfig(target, type, landedState, power);
                registry.restoreBlock(target, type, landedState);
                // Write captured storage back into the landed skull, keeping the shared cache + PDC
                // consistent (custom-storage blocks don't hit the vanilla-Container branch below).
                if (mb.storage != null) registry.restoreStorageSnapshot(target, mb.storage);
                // Carry over per-block config (rotator angle, throttle levels, dynamo mode, …) — must
                // run AFTER the steps above, which own the identity/state/storage keys it skips.
                registry.restoreConfigPdc(target, mb.configPdc);
                // Re-load any side-store state (engine fuel) from the just-restored tile PDC — restoreBlock's
                // onChunkLoad above ran BEFORE restoreConfigPdc, so it read a stale/empty counter.
                if (type.onRestore() != null) type.onRestore().accept(target);
                flipLandedSpinDir(target, mb, snappedYaw);
            }
        } else if (mb.storage != null && target.getState() instanceof Container c) {
            Inventory dest = c.getSnapshotInventory();
            ItemStack[] saved = mb.storage.getContents();
            // setContents throws when the array is LONGER than the destination, and the caller's catch
            // turns that into a WARNING with the items gone. Cap, then drop the tail as entities rather
            // than swallowing it.
            // Unreachable on every path that exists today, and deliberately kept anyway: a captured world
            // container is sized from its LIVE inventory (createTypedInventory) so the sizes match by
            // construction, and a double chest is split into 27-slot halves at capture. A part whose
            // declared storageSize exceeds its block — prefab cargo — never lands at all: those
            // mechanisms are blockFree, and disassemble() short-circuits to destroy() (which drops the
            // whole inventory, uncapped) before reaching this method. So this only fires on a
            // model/persistence inconsistency, e.g. a recovered mechanism that lost its block_free flag.
            // Deliberately drops BEFORE the update() below: this whole block is inside a catch-and-log,
            // so materialising the tail first means an update() failure cannot swallow it.
            if (saved.length > dest.getSize()) {
                dest.setContents(java.util.Arrays.copyOf(saved, dest.getSize()));
                World w = target.getWorld();
                for (int s = dest.getSize(); s < saved.length; s++) {
                    if (saved[s] != null && !saved[s].getType().isAir()) {
                        w.dropItemNaturally(target.getLocation().add(0.5, 0.5, 0.5), saved[s]);
                    }
                }
            } else {
                dest.setContents(saved);
            }
            // update(force, applyPhysics) — physics OFF, matching setBlockData(landed, !attachable) above
            // and airOutSourceBlocks. A physics-applying update here would run neighbour updateShape on a
            // landed chest, which is exactly what re-pairs two chests the capture deliberately split.
            c.update(true, false);
        }

        // Decorated block-entity state (sign text, skull profile, container name, …). LAST — after
        // BlockData + custom/container/banner restores — so each provider fetches a fresh, correctly-typed
        // BlockState and re-applies its keys (see BlockSnapshotProvider). No-op when nothing was captured.
        if (mb.blockEntitySnapshot != null) {
            registry.applyBlockSnapshot(target, mb.blockEntitySnapshot);
        }
        } catch (RuntimeException ex) {
            // A custom block's identity/config restore failing is corruption-class (SEVERE); a vanilla
            // block's banner/snapshot restore failing is cosmetic (WARNING). Either way the block stays
            // placed and is not dropped (bar the near-unreachable commit-line case noted at the COMMIT POINT
            // above) — so no dupe, at worst partial block-entity state on this one cell.
            Level sev = mb.customTypeId != null ? Level.SEVERE : Level.WARNING;
            registry.getPlugin().getLogger().log(sev,
                "placeBlock: post-place restore failed at " + target.getLocation()
                + " (type=" + mb.customTypeId + "); block left placed with partial state", ex);
        }
    }

    /** Keep a landed rotation-power source spinning the SAME physical way. A source stores its allowed spin
     *  as a {@code mech:spin_dir} PDC token (cw/ccw = positive about its unsigned axle), written back verbatim
     *  by restoreConfigPdc. If the landing yaw negated the source's spin axis (a horizontal axle turned 180°,
     *  or a quarter-turn onto the opposite cardinal), that same token now names the opposite physical spin —
     *  so flip it. A stale token would reverse the WHOLE downstream domain (RotationNetwork anchors each
     *  domain to this stored token) and could spuriously jam. Reads a fresh TileState AFTER restoreConfigPdc's
     *  own update() so it doesn't clobber the just-merged config keys. No-op when the block stores no
     *  spin_dir (every non-source block) or its axle is Y (yaw never negates it). */
    private void flipLandedSpinDir(Block target, MechanismBlockData mb, float snappedYaw) {
        if (mb.customState == null) return;
        if (!(target.getState() instanceof TileState tile)) return;
        var pdc = tile.getPersistentDataContainer();
        String token = pdc.get(RotationNetwork.SPIN_DIR_KEY, PersistentDataType.STRING);
        if (token == null) return;
        String flipped = BlockRotation.rotateSpinDir(
            RotationNetwork.axisFromState(mb.customState), snappedYaw, token);
        if (flipped.equals(token)) return;
        pdc.set(RotationNetwork.SPIN_DIR_KEY, PersistentDataType.STRING, flipped);
        tile.update();
    }

    /** Record the cell of a dropped CHAIN link for the carried-hoist chain-break guard; lazily creates the set
     *  and returns it (unchanged for a non-chain block). The full 3D cell (not an (x,z) column) is stored so
     *  the consumer can attribute the link to its owning hoist by walking up the rope. */
    private static Set<Block> noteIfChain(@Nullable Set<Block> cells, MechanismBlockData mb, Location loc) {
        // A ghost is a hoist's own emerging/reeling link — a stroke artifact, not a real chain that
        // shortened. (Today the hoist's protected head is never in `placed` so it couldn't match anyway;
        // this makes the exclusion explicit rather than incidental.)
        if (mb.ghost) return cells;
        if (!ChainHoistManager.isChainMaterial(mb.blockData.getMaterial())) return cells;
        // Real hoist rope is a plain vanilla chain (customTypeId == null). A decorative CUSTOM chain (or a
        // bare-block chain shaft) carries a non-null id — it isn't rope, so its landing must not clear a
        // hoist's glue.
        if (mb.customTypeId != null) return cells;
        if (cells == null) cells = new HashSet<>();
        cells.add(loc.getBlock());
        return cells;
    }

    private void dropBlockAsItem(Location loc, MechanismBlockData mb) {
        ItemStack drop;
        if (mb.customTypeId != null) {
            CustomHeadBlock type = registry.getType(mb.customTypeId);
            drop = (type != null) ? type.createItem(1) : new ItemStack(mb.blockData.getMaterial());
        } else if (blockBannerItem(mb) != null) {
            // A vanilla banner block drops its captured pattern-carrying item. Also load-bearing for
            // wall banners: *_WALL_BANNER has no item form, so new ItemStack() below would throw.
            drop = blockBannerItem(mb).asQuantity(1);
        } else if (mb.blockData.getMaterial().isItem()) {
            drop = new ItemStack(mb.blockData.getMaterial());
        } else {
            drop = null; // block-only material with no item mapping (e.g. wall variants) — no drop
        }
        // Consumer drop-item hook (3a-ii): substitute a custom-item identity, remap a wall variant that
        // has no item form (default drop == null), or suppress the drop (return null). The hook fully
        // decides the final item from the engine default.
        if (dropItemHook != null) {
            drop = dropItemHook.construct(mb, drop);
        }
        if (drop != null) {
            // Re-apply captured head skin / custom name onto the drop (parity with the landing path's
            // applyBlockSnapshot). Runs AFTER the hook so a consumer's wall→floor material remap is also
            // decorated. Snapshot-gated → no-op for blocks with no captured decoration.
            if (mb.blockEntitySnapshot != null) {
                DefaultBlockSnapshotProvider.decorateItem(drop, mb.blockEntitySnapshot);
            }
            loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), drop);
        }

        dropStorageItems(loc, mb);

        // A dropped host takes its riding BetterBanners down with it — as items, never silently.
        dropBannerItems(loc, mb);
    }

    /** Drop this block's captured contents as loose items at {@code loc}. Used by every branch that discards
     *  a cargo-bearing block without a placed tile to restore into — the drop / off-world / solid-win paths
     *  (via {@link #dropBlockAsItem}) and the consumed protected / SKIP cells. Contents are player items and
     *  must never be silently swallowed. No-op for a block that captured nothing.
     *
     *  <p>TWO sources, because a block's items live in one of two places: the typed {@code storage} inventory
     *  (vanilla containers + custom storage blocks) and the block-entity snapshot (lectern book, jukebox disc,
     *  decorated-pot item, chiseled-bookshelf/shelf contents). Both were emptied out of the world by
     *  airOutSourceBlocks' pass 0, so whatever this method skips is destroyed. */
    private void dropStorageItems(Location loc, MechanismBlockData mb) {
        World w = loc.getWorld();
        if (w == null) return;
        if (mb.storage != null) {
            for (ItemStack item : mb.storage.getContents()) {
                if (item != null && !item.getType().isAir()) {
                    w.dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), item);
                }
            }
        }
        for (ItemStack item : DefaultBlockSnapshotProvider.capturedItems(mb.blockEntitySnapshot)) {
            if (item != null && !item.getType().isAir()) {
                w.dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), item);
            }
        }
    }

    /** The BLOCK_FACE_KEY attachment's item (a vanilla banner block's pattern-carrying drop), or null. */
    private static @Nullable ItemStack blockBannerItem(MechanismBlockData mb) {
        if (mb.banners == null) return null;
        for (BannerAttachment att : mb.banners) {
            if (att.isBlockBanner()) return att.item();
        }
        return null;
    }

    /**
     * Re-attach or drop this block's riding BetterBanners at its landed host. Non-upright landings
     * (X/Z drawbridge quarter/half turns) have no valid banner orientation — drop. The BLOCK entry
     * is excluded throughout: the vanilla banner block itself landed via placeBlock.
     */
    private void landBanners(Block target, MechanismBlockData mb, float snappedYaw, boolean upright) {
        if (mb.banners == null) return;
        boolean hasEntityBanners = false;
        for (BannerAttachment att : mb.banners) {
            if (!att.isBlockBanner()) { hasEntityBanners = true; break; }
        }
        if (!hasEntityBanners) return;
        BannerManager bm = mechanismRegistry != null ? mechanismRegistry.bannerManager() : null;
        if (bm == null || !upright) {
            dropBannerItems(target.getLocation(), mb);
            return;
        }
        bm.placeLandedBanners(target, mb.banners, snappedYaw);
    }

    /** Drop one banner item per distinct faceKey (a flag's front/back pair is ONE item — mirrors
     *  BannerManager.handleRemoval), skipping the BLOCK entry (dropped with the block itself). */
    private void dropBannerItems(Location loc, MechanismBlockData mb) {
        if (mb.banners == null) return;
        World w = loc.getWorld();
        if (w == null) return;
        Set<String> droppedFaces = new HashSet<>();
        for (BannerAttachment att : mb.banners) {
            if (att.isBlockBanner() || !droppedFaces.add(att.faceKey())) continue;
            w.dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), att.item().asQuantity(1));
        }
    }

    void removeAllEntities() {
        for (var group : displaysPerBlock) {
            for (Display d : group) d.remove();
        }
        for (var group : bannerDisplaysPerBlock) {
            for (Display d : group) d.remove();
        }
        for (ColliderPair cp : colliders) {
            cp.carrier().remove();
            cp.shulker().remove();
        }
        parent.remove(); // Entity.remove() implicitly ejects from vehicle
        if (ownsVehicle) {
            vehicle.remove();
        } else if (vehicle != null) {
            // Non-owned vehicle (e.g. a mechanism minecart) survives disassembly, but assembly tagged
            // it corelib:mech:{id}:vehicle (MechanismRegistry.assembleMechanism). Strip that tag now —
            // otherwise on the next chunk load the orphan sweep in recoverMechanismsInChunk sees a tag whose
            // mech is gone from activeMechanisms and reaps the cart (taking its PDC-stored glue with it).
            vehicle.removeScoreboardTag("corelib:mech:" + id() + ":vehicle");
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
        // Colliders aren't parent passengers — they're free carriers positioned only by the collider
        // loop, which used to run via the per-tick rotate() below. With rotation frozen, follow the
        // translated pivot here so the shulker hitboxes track the moving cart (identity transform).
        repositionColliders();
        // Rotation frozen for now: the yaw-fold below recomputes orientation ABSOLUTELY each tick as
        // fold(rawYaw − assemblyYaw) into ±90°, which is correct only for a NET turn within ±90° of
        // assembly. A net turn >90° (two curves = 180°) wraps the folded delta back toward 0°, so the
        // structure ends at its initial orientation instead of turned. Until a cumulative-turn
        // accumulator lands (integrate per-tick signed heading change; treat a 1-tick ~180° jump as a
        // reversal→no-rotation, real arcs accumulate), the mechanism follows the cart's POSITION but
        // keeps its assembly orientation. Assembly calls rotate(0), so currentYaw stays 0 and
        // landingRotation() snaps to the assembly orientation — self-consistent. Old drive:
        // float delta = yaw - assemblyYaw;
        // while (delta > 90f)  delta -= 180f;
        // while (delta < -90f) delta += 180f;
        // rotate(delta);
        previousVehicleLoc = loc.clone();
        previousVehicleYaw = yaw;
    }

    Matrix4f currentTransform() { return currentTransform; }

    /** Build the Transformation's matrix into {@code dest} (reset first) instead of allocating — every caller
     *  is the per-tick hot loop and hands a reused scratch matrix. */
    static Matrix4f transformToMatrix(org.bukkit.util.Transformation t, Matrix4f dest) {
        return dest.identity()
                .translate(t.getTranslation())
                .rotate(t.getLeftRotation())
                .scale(t.getScale())
                .rotate(t.getRightRotation());
    }

    /**
     * Half-turn correcting the head ITEM model's default face. Measured in game: a player-head ItemDisplay at
     * identity renders its face toward -Z, not the +Z the block-entity form uses — so every yaw derived from a
     * skull's BlockData needs π added before it can drive a display. Exactly the same discrepancy the banner
     * path already carries as {@code 180 - yaw} in {@code MechanismRegistry.vanillaBannerTransform}, and for
     * the same reason: the item model faces the opposite way from the block-entity.
     *
     * <p>Without this, every custom wall head on a mechanism renders backwards. It went unnoticed for a long
     * time because most head blocks (gears, casings, bearings) are near enough rotationally symmetric to hide
     * it; the thruster's jet nozzle is the first skin directional enough to make it obvious.
     */
    private static final float HEAD_ITEM_MODEL_FACE_YAW = (float) Math.PI;

    /**
     * Yaw (radians, about +Y) that turns a wall head's primary ItemDisplay so its face points along the
     * horizontal {@code facing}, matching the vanilla skull block's orientation on the static path. Only the
     * four horizontal cardinals occur for wall heads.
     *
     * <p>The four cardinal cases below are the block-entity convention (+Z→facing, mirroring
     * {@code ExtendablePistonManager.faceRotation}); {@link #HEAD_ITEM_MODEL_FACE_YAW} then converts that into
     * the item model's frame. Keep the two steps separate — the cardinal table is verified geometry, the
     * half-turn is a model-space correction, and folding them together makes the next bug unreadable.
     */
    private static float faceYawRadians(Vector3f facing) {
        float h = (float) (Math.PI / 2), p = (float) Math.PI;
        float yaw;
        if (facing.z < -0.5f)      yaw = p;    // north (-Z)
        else if (facing.x > 0.5f)  yaw = h;    // east (+X)
        else if (facing.x < -0.5f) yaw = -h;   // west (-X)
        else                       yaw = 0f;   // south (+Z), and the fallback
        return yaw + HEAD_ITEM_MODEL_FACE_YAW;
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
