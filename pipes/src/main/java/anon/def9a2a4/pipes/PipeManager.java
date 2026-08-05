package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import anon.def9a2a4.corelib.CoreLibPlugin;
import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import anon.def9a2a4.corelib.container.ContainerAdapter;
import anon.def9a2a4.corelib.container.ContainerAdapterRegistry;
import anon.def9a2a4.corelib.fluid.FluidEndpoint;
import anon.def9a2a4.corelib.fluid.FluidEndpoints;
import anon.def9a2a4.corelib.fluid.FluidType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import anon.def9a2a4.pipes.config.DisplayConfig;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PipeManager {

    private record CachedPath(Location destination, Location lastPipeLocation,
                               List<Location> pipeChain, int minItemsPerTransfer,
                               List<Location> filterPipes) {}

    private final PipesPlugin plugin;
    private final World world;
    private final Map<Location, PipeData> pipes = new HashMap<>();
    private final Map<Location, CachedPath> pathCache = new HashMap<>();
    private final Map<Location, PipeFilterStore.FilterData> filterCache = new HashMap<>();
    // Last-seen redstone state of each filter pipe, for edge-detecting a power drop in onNeighborChange.
    private final Map<Location, Boolean> filterPowered = new HashMap<>();
    private final Map<Location, Long> sleepUntil = new HashMap<>();
    private final Map<Location, Long> deadEndRecheckAt = new HashMap<>();
    private final Random random = new Random();
    private BukkitTask transferTask;
    private BukkitTask particleTask;

    public PipeManager(PipesPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    public void startTasks() {
        // Task runs every tick; each pipe fires only on its own phase offset
        transferTask = Bukkit.getScheduler().runTaskTimer(plugin, this::transferAllPipes, 20, 1);

        if (plugin.getPipeConfig().isDebugParticles()) {
            int particleInterval = plugin.getPipeConfig().getParticleInterval();
            particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnDebugParticles, 20, particleInterval);
        }
    }

    public void registerPipe(Location location, BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {
        Location normalized = normalizeLocation(location);
        pipes.put(normalized, new PipeData(facing, displayEntityIds, variant));
        // Seed the ring's power baseline so onFilterPowerEdge's first observation matches the ring the
        // resolver just drew at spawn — otherwise a pipe placed powered then unpowered never redraws.
        if (variant.isFilter()) {
            filterPowered.put(normalized, isPipePowered(normalized.getBlock()));
        }
        clearPathCaches();
    }

    public boolean isPipe(Location location) {
        return pipes.containsKey(normalizeLocation(location));
    }

    /** Cached filter for a filter pipe; lazily read from the block PDC on first access. */
    private PipeFilterStore.FilterData getFilter(Location normalized) {
        PipeFilterStore.FilterData cached = filterCache.get(normalized);
        if (cached != null) return cached;
        PipeFilterStore.FilterData fresh = PipeFilterStore.read(normalized.getBlock());
        filterCache.put(normalized, fresh);
        return fresh;
    }

    /**
     * Re-read a filter pipe's config from PDC into the cache (called after every GUI edit). Cheap: only
     * the cache entry changes — path geometry is unaffected by a filter-content edit, so the path cache is
     * left intact. Waking asleep extractors is deferred to {@link #wakeAll()} on GUI close.
     */
    public void refreshFilter(Location location) {
        Location normalized = normalizeLocation(location);
        filterCache.put(normalized, PipeFilterStore.read(normalized.getBlock()));
    }

    /**
     * Clear all source-empty/dest-full sleep timers so pipes re-check next tick. Called once when a filter
     * GUI closes: an upstream extractor may have fallen asleep while a mid-chain filter blocked everything,
     * and editing that filter must let flow resume promptly without a relog.
     */
    public void wakeAll() {
        sleepUntil.clear();
    }

    /**
     * On a filter pipe's neighbor change, edge-detect its redstone power against the last-seen value.
     * On the powered→UNpowered transition (its off-switch released) wake sleeping extractors so a
     * redstone-blocked chain resumes promptly — power-on and unrelated churn don't wake, since an
     * actively-transferring extractor already stops by reading power live and a missed wake self-heals
     * within one sleep window. Returns {@code true} on <em>either</em> transition so the caller can redraw
     * the ring display entity (which shows on/off by live power). Edge detection mirrors RotationRotator.
     */
    public boolean onFilterPowerEdge(Block block) {
        Location key = normalizeLocation(block.getLocation());
        boolean now = isPipePowered(block);
        Boolean was = filterPowered.put(key, now);
        boolean changed = was == null ? now : was != now;
        if (Boolean.TRUE.equals(was) && !now) {
            wakeAll();
        }
        return changed;
    }

    public PipeData getPipeData(Location location) {
        return pipes.get(normalizeLocation(location));
    }

    private boolean isChest(Block block) {
        Material type = block.getType();
        String typeName = type.name();
        return type == Material.CHEST
            || type == Material.TRAPPED_CHEST
            || type == Material.ENDER_CHEST
            || typeName.contains("COPPER") && typeName.contains("CHEST");
    }

    private boolean isHopper(Block block) {
        return block.getType() == Material.HOPPER;
    }

    /**
     * Categorize the block at the source (input) side of the pipe for display adjustments.
     */
    private String categorizeSourceBlock(Block sourceBlock, BlockFace currentFacing) {
        // Check if it's a pipe first
        PipeData pipeData = getPipeData(sourceBlock.getLocation());
        if (pipeData != null) {
            if (pipeData.variant().behaviorType() == BehaviorType.CORNER) {
                // Corner pipe outputs INTO this pipe if corner's facing == opposite of currentFacing
                if (pipeData.facing() == currentFacing.getOppositeFace()) {
                    return "corner-into";
                }
                return "block"; // Corner pipe not feeding into us, treat as solid
            }
            // Regular pipe
            if (pipeData.facing() == currentFacing) {
                return "pipe-continuous";
            }
            if (pipeData.facing() == currentFacing.getOppositeFace()) {
                return "pipe-into";
            }
            return "pipe-orthogonal"; // Orthogonal pipe behind us
        }

        // Check container types
        if (isChest(sourceBlock)) return "chest";
        if (isHopper(sourceBlock)) return "hopper";
        if (ContainerAdapterRegistry.findAdapter(sourceBlock).isPresent()) return "container";
        if (sourceBlock.getType().isAir() || !sourceBlock.getType().isSolid()) return "air";
        return "block";
    }

    /**
     * Categorize the block at the destination (output) side of the pipe for display adjustments.
     */
    private String categorizeDestinationBlock(Block destBlock, BlockFace currentFacing) {
        PipeData pipeData = getPipeData(destBlock.getLocation());
        if (pipeData != null) {
            if (pipeData.variant().behaviorType() == BehaviorType.CORNER) {
                // Corner outputs INTO this pipe if corner's facing == opposite of currentFacing
                if (pipeData.facing() == currentFacing.getOppositeFace()) {
                    return "corner-into";
                }
                return "corner-pipe";
            }
            // Regular pipe
            if (pipeData.facing() == currentFacing) {
                return "pipe-continuous";
            }
            if (pipeData.facing() == currentFacing.getOppositeFace()) {
                return "pipe-into";
            }
            return "pipe-orthogonal";
        }

        if (isChest(destBlock)) return "chest";
        if (isHopper(destBlock)) return "hopper";
        if (ContainerAdapterRegistry.findAdapter(destBlock).isPresent()) return "container";
        if (destBlock.getType().isAir() || !destBlock.getType().isSolid()) return "air";
        return "block";
    }

    /**
     * Get the direction key for config lookup based on pipe facing.
     * @param facing The direction the pipe is facing
     * @param isSource True for source side, false for destination side
     * @return "side", "up", or "down"
     */
    private String getDirectionKey(BlockFace facing, boolean isSource) {
        return switch (facing) {
            case UP -> isSource ? "down" : "up";
            case DOWN -> isSource ? "up" : "down";
            default -> "side";
        };
    }

    private Transformation calculateTransformation(Location pipeLocation, BlockFace facing, PipeVariant variant) {
        // Corner pipes use simple fixed transformation
        if (variant.behaviorType() == BehaviorType.CORNER) {
            return calculateCornerTransformation();
        }

        // ============================================================
        // REGULAR PIPE DISPLAY TRANSFORMATION
        // ============================================================
        // The item display entity spawns at block center (0.5, 0.5, 0.5).
        // Without any transformation, the display's geometric center sits
        // at the source-side block boundary (the wall the head attaches to).
        //
        // We control the display by specifying where each endpoint should be:
        // - sourceEnd: position of back of display (relative to source boundary)
        //   Positive = extend into source block, Negative = retract toward dest
        // - destEnd: position of front of display (relative to dest boundary)
        //   Positive = extend into dest block, Negative = retract toward source
        //
        // All positions are in "forward" units along the pipe's facing direction.
        // ============================================================

        Block pipeBlock = pipeLocation.getBlock();
        DisplayConfig display = plugin.getDisplayConfig();

        // Base scale factor (2.0 means 1 block of model = 1 block of world space)
        double baseFacingScale = display.getFacingScale();
        double perpScale = display.getPerpendicularScale();

        // Perpendicular offsets (right/up) - these don't change with endpoint logic
        DisplayConfig.DirectionalOffset offset = switch (facing) {
            case UP -> display.getOffsetUp();
            case DOWN -> display.getOffsetDown();
            default -> display.getOffsetHorizontal();
        };
        double offsetRight = offset.right();
        double offsetUp = offset.up();

        // Get adjacent blocks and categorize them
        Block sourceBlock = pipeBlock.getRelative(facing.getOppositeFace());
        Block destBlock = pipeBlock.getRelative(facing);
        String sourceCategory = categorizeSourceBlock(sourceBlock, facing);
        String destCategory = categorizeDestinationBlock(destBlock, facing);

        // Get endpoint adjustments from config (with directional variants)
        String sourceDir = getDirectionKey(facing, true);
        String destDir = getDirectionKey(facing, false);
        double sourceEndOffset = display.getSourceAdjustment(sourceCategory, sourceDir);
        double destEndOffset = display.getDestinationAdjustment(destCategory, destDir);

        // ============================================================
        // ENDPOINT MATH
        // ============================================================
        // Block boundaries (relative to block center at 0):
        //   Source boundary: -0.5 (back of block)
        //   Dest boundary:   +0.5 (front of block)
        //
        // Desired endpoint positions:
        //   sourceEndPos = -0.5 - sourceEndOffset  (back of display)
        //   destEndPos   = +0.5 + destEndOffset    (front of display)
        //
        // Display length and center:
        //   displayLength = destEndPos - sourceEndPos
        //                 = (0.5 + destEndOffset) - (-0.5 - sourceEndOffset)
        //                 = 1.0 + sourceEndOffset + destEndOffset
        //
        //   displayCenter = (destEndPos + sourceEndPos) / 2
        //                 = ((0.5 + destEndOffset) + (-0.5 - sourceEndOffset)) / 2
        //                 = (destEndOffset - sourceEndOffset) / 2
        // ============================================================

        double sourceEndPos = -0.5 - sourceEndOffset;
        double destEndPos = 0.5 + destEndOffset;
        double displayLength = destEndPos - sourceEndPos;

        // Scale factor for the facing direction
        double facingScale = baseFacingScale * displayLength;

        // ============================================================
        // TRANSLATION CALCULATION
        // ============================================================
        // For HORIZONTAL pipes:
        //   The display model extends symmetrically from its center.
        //   After scaling by facingScale, the model extends facingScale/2 in each direction.
        //   We position it so its center is at displayCenter.
        //
        // For VERTICAL pipes (UP/DOWN):
        //   The display model extends from its origin in one direction only.
        //   For UP: origin is at the bottom, display extends upward.
        //   For DOWN: origin is at the top, display extends downward.
        //   We position the origin at sourceEndPos, letting scale extend toward dest.
        // ============================================================

        double offsetForward;
        if (facing == BlockFace.UP) {
            // UP pipes: origin is at top (destination end), display extends downward
            // Anchor at destEndPos, scale extends toward source
            offsetForward = destEndPos + 0.5;
        } else if (facing == BlockFace.DOWN) {
            // DOWN pipes: origin is at top (source end in world space), display extends downward
            // Anchor at sourceEndPos, scale extends toward destination
            offsetForward = sourceEndPos + 0.5;
        } else {
            // Horizontal pipes: center the display between endpoints
            double displayCenter = (destEndPos + sourceEndPos) / 2.0;
            offsetForward = 0.5 + displayCenter;
        }

        // Build the transformation components
        Vector3f scale = buildScale(facing, (float) facingScale, (float) perpScale);
        Vector3f translation = buildTranslation(facing,
            (float) offsetForward, (float) offsetRight, (float) offsetUp);
        AxisAngle4f rotation = buildRotation(facing);

        return new Transformation(
                translation,
                rotation,
                scale,
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    // ============================================================
    // TRANSFORMATION HELPER METHODS
    // ============================================================

    private Vector3f buildScale(BlockFace facing, float facingScale, float perpScale) {
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> new Vector3f(perpScale, perpScale, facingScale);
            case UP, DOWN -> new Vector3f(perpScale, facingScale, perpScale);
            default -> new Vector3f(perpScale, perpScale, perpScale);
        };
    }

    private Vector3f buildTranslation(BlockFace facing, float forward, float right, float up) {
        return switch (facing) {
            case NORTH -> new Vector3f(right, up, -forward);
            case SOUTH -> new Vector3f(-right, up, forward);
            case EAST -> new Vector3f(forward, up, right);
            case WEST -> new Vector3f(-forward, up, -right);
            case UP -> new Vector3f(right, forward, up);
            case DOWN -> new Vector3f(right, -forward, -up);
            default -> new Vector3f(0, 0, 0);
        };
    }

    private AxisAngle4f buildRotation(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> new AxisAngle4f((float) Math.PI, 0, 1, 0);
            case EAST -> new AxisAngle4f((float) -Math.PI / 2, 0, 1, 0);
            case WEST -> new AxisAngle4f((float) Math.PI / 2, 0, 1, 0);
            case UP, DOWN -> new AxisAngle4f(0, 0, 1, 0);
            default -> new AxisAngle4f(0, 0, 1, 0);
        };
    }

    private Transformation calculateCornerTransformation() {
        DisplayConfig display = plugin.getDisplayConfig();
        float scale = (float) display.getCornerScale();
        float height = (float) display.getCornerHeight();

        // Simple transformation: uniform scale, fixed height, no rotation
        Vector3f translation = new Vector3f(0, height - 0.5f, 0); // Adjust from center (0.5) to desired height
        Vector3f scaleVec = new Vector3f(scale, scale, scale);
        AxisAngle4f rotation = new AxisAngle4f(0, 0, 1, 0); // No rotation

        return new Transformation(
                translation,
                rotation,
                scaleVec,
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    /**
     * Calculate transformation for the directional display entity of a corner pipe.
     * Uses adjustments.destination config values similar to regular pipes.
     */
    private Transformation calculateCornerDirectionalTransformation(Location pipeLocation, BlockFace facing) {
        Block pipeBlock = pipeLocation.getBlock();
        DisplayConfig display = plugin.getDisplayConfig();

        // Use regular pipe display settings for the directional component
        double baseFacingScale = display.getFacingScale();
        double perpScale = display.getPerpendicularScale();

        // Perpendicular offsets
        DisplayConfig.DirectionalOffset offset = switch (facing) {
            case UP -> display.getOffsetUp();
            case DOWN -> display.getOffsetDown();
            default -> display.getOffsetHorizontal();
        };
        double offsetRight = offset.right();
        double offsetUp = offset.up();

        // Get destination block and categorize it
        Block destBlock = pipeBlock.getRelative(facing);
        String destCategory = categorizeDestinationBlock(destBlock, facing);

        // Get destination endpoint adjustment (corner-specific, with fallback to global)
        String destDir = getDirectionKey(facing, false);
        double destEndOffset = display.getCornerDestinationAdjustment(destCategory, destDir);

        // For corner directional display:
        // - Source is at the corner piece center (0.0 offset from center)
        // - Destination uses the normal adjustment
        double sourceEndPos = 0.0; // Start from center of block
        double destEndPos = 0.5 + destEndOffset;
        double displayLength = destEndPos - sourceEndPos;
        double displayCenter = (destEndPos + sourceEndPos) / 2.0;

        // Scale factor for the facing direction
        double facingScale = baseFacingScale * displayLength;

        // Translation: position center of display at displayCenter
        double offsetForward = displayCenter + display.getCornerDirectionalForwardOffset();

        // Build the transformation components
        Vector3f scale = buildScale(facing, (float) facingScale, (float) perpScale);
        Vector3f translation = buildTranslation(facing,
            (float) offsetForward, (float) offsetRight, (float) offsetUp);
        AxisAngle4f rotation = buildRotation(facing);

        return new Transformation(
                translation,
                rotation,
                scale,
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    private void spawnDebugParticles() {
        // Snapshot for the same reason as transferAllPipes: a reentrant chunk callback could mutate `pipes`.
        for (Location loc : new ArrayList<>(pipes.keySet())) {
            world.spawnParticle(
                    Particle.DUST,
                    loc.clone().add(0.5, 0.5, 0.5),
                    3,
                    0.2, 0.2, 0.2,
                    0,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 100, 50), 1.0f)
            );
        }
    }

    private void transferAllPipes() {
        long currentTick = Bukkit.getCurrentTick();
        List<Location> toRemove = new ArrayList<>();

        // Snapshot: transferItems reads blocks that can force-load a chunk, whose synchronous
        // ChunkLoadEvent re-enters registerPipe/removePipeData and would mutate `pipes` mid-iteration → CME.
        for (Location loc : new ArrayList<>(pipes.keySet())) {
            PipeData data = pipes.get(loc);
            if (data == null) continue; // removed mid-tick by a reentrant chunk-unload callback

            // Sleep check
            Long wakeTick = sleepUntil.get(loc);
            if (wakeTick != null) {
                if (currentTick < wakeTick) continue;
                sleepUntil.remove(loc);
            }

            // Phase offset check
            int intervalTicks = Math.max(1, data.variant().transferIntervalTicks());
            if (!isTransferDue(currentTick, loc, intervalTicks)) continue;

            if (transferItems(loc, data)) {
                toRemove.add(loc);
            }
        }

        for (Location loc : toRemove) {
            removePipeData(loc);
        }
    }

    private boolean isTransferDue(long currentTick, Location loc, int intervalTicks) {
        if (intervalTicks <= 1) return true;
        int hash = Objects.hash(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return Math.floorMod(currentTick, intervalTicks) == Math.floorMod(hash, intervalTicks);
    }

    private void sleepPipe(Location loc, int ticks) {
        if (ticks <= 0) return;
        sleepUntil.put(loc, (long) Bukkit.getCurrentTick() + ticks);
    }

    public void wakeUpPipe(Location location) {
        sleepUntil.remove(normalizeLocation(location));
    }

    /**
     * Resolve the display transform for a pipe block. Entry point for CoreLib's DisplayTransformResolver.
     * Falls back to parsing facing from state when the pipe isn't yet registered (initial placement).
     */
    public @org.jspecify.annotations.Nullable Transformation resolveTransform(
            Block block, @org.jspecify.annotations.Nullable String state, PipeVariant fallbackVariant,
            int displayIndex) {
        Location location = normalizeLocation(block.getLocation());
        PipeData data = pipes.get(location);

        BlockFace facing;
        PipeVariant variant;
        if (data != null) {
            facing = data.facing();
            variant = data.variant();
        } else if (state != null) {
            facing = PipeBlockRegistrar.parseFacing(state);
            variant = fallbackVariant;
        } else {
            return null;
        }

        if (displayIndex == 1 && variant.isFilter()) {
            return calculateFilterRingTransformation(facing);
        }
        if (displayIndex == 1 && variant.behaviorType() == BehaviorType.CORNER) {
            return calculateCornerDirectionalTransformation(location, facing);
        }
        return calculateTransformation(location, facing, variant);
    }

    /**
     * Transform for a filter pipe's ring display entity (display index 1). A flat disc — fixed scale
     * y=0.5 (thin), x/z=1.25 (ring) — centered on the block, tilted so the thin axis lies along the pipe:
     * identity for vertical (UP/DOWN) pipes, 90° about X for NORTH/SOUTH, 90° about Z for EAST/WEST. The
     * ring is symmetric, so ±90° and north-vs-south are interchangeable.
     */
    private Transformation calculateFilterRingTransformation(BlockFace facing) {
        AxisAngle4f rotation = switch (facing) {
            case NORTH, SOUTH -> new AxisAngle4f((float) Math.PI / 2, 1, 0, 0);
            case EAST, WEST -> new AxisAngle4f((float) Math.PI / 2, 0, 0, 1);
            default -> new AxisAngle4f(0, 0, 1, 0); // UP/DOWN: horizontal disc, no tilt
        };
        // The main pipe display sits ~0.125 off the cell center, so shift the ring to line up with the body.
        // The correction is a fixed world direction per axis (not "along facing"): Z-axis pipes → +Z,
        // X-axis pipes → −X, up → −Y, down → +Y. World space: the Transformation translation is applied
        // outside the leftRotation, so it is not rotated.
        Vector3f translation = switch (facing) {
            case NORTH, SOUTH -> new Vector3f(0, 0, 0.125f);
            case EAST, WEST -> new Vector3f(-0.125f, 0, 0);
            case UP -> new Vector3f(0, -0.125f, 0);
            case DOWN -> new Vector3f(0, 0.125f, 0);
            default -> new Vector3f(0, 0, 0);
        };
        return new Transformation(
                translation,
                rotation,
                new Vector3f(1.25f, 0.5f, 1.25f),
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    /**
     * Remove pipe data from the registry without touching display entities.
     * Used by CoreLib callbacks where display entities are managed externally.
     */
    public void removePipeData(Location location) {
        Location normalized = normalizeLocation(location);
        pipes.remove(normalized);
        sleepUntil.remove(normalized);
        filterCache.remove(normalized);
        filterPowered.remove(normalized);
        clearPathCaches();
    }

    /** Clear the path-geometry caches together: resolved routes ({@code pathCache}) and the dead-end
     *  recheck deadlines ({@code deadEndRecheckAt}). Both are path-identity-coupled, so any pipe add/
     *  remove or neighbor change must invalidate them as a unit — otherwise a rebuilt dead-end path can
     *  inherit a stale recheck deadline and keep spilling items after a container appears at its end. */
    private void clearPathCaches() {
        pathCache.clear();
        deadEndRecheckAt.clear();
    }

    public void invalidatePathCache() {
        clearPathCaches();
    }

    /**
     * Attempts to transfer items from this pipe.
     * @return true if the pipe should be removed (block no longer exists)
     */
    private boolean transferItems(Location pipeLocation, PipeData data) {
        if (data == null) return false;

        // Corner pipes never pull items - they only relay when items are pushed into them
        if (data.variant().behaviorType() == BehaviorType.CORNER) {
            return false;
        }

        Block pipeBlock = pipeLocation.getBlock();
        if (pipeBlock.getType() != Material.PLAYER_HEAD && pipeBlock.getType() != Material.PLAYER_WALL_HEAD) {
            return true;  // Signal removal
        }

        BlockFace facing = data.facing();
        BlockFace sourceDirection = facing.getOppositeFace();

        Block sourceBlock = pipeBlock.getRelative(sourceDirection);
        ContainerAdapter sourceAdapter = ContainerAdapterRegistry.findAdapter(sourceBlock).orElse(null);
        if (sourceAdapter == null) {
            return false;
        }

        // Start with this pipe's items per transfer and find minimum along path
        int startingMax = data.variant().itemsPerTransfer();
        CachedPath path = getOrBuildPath(pipeLocation, facing, startingMax);

        int maxToExtract = path.minItemsPerTransfer();

        // Apply the filters of every filter pipe ALONG the path (not just this extractor): an item is
        // pulled only if it can traverse the whole chain. The predicate-aware peek scans past
        // non-matching slots. No filter pipes on the path → plain first-available peek.
        java.util.function.Predicate<ItemStack> accept = buildChainFilter(path);
        ItemStack toTransfer = (accept == null)
            ? sourceAdapter.peekExtract(sourceBlock, maxToExtract)
            : sourceAdapter.peekExtract(sourceBlock, maxToExtract, accept);
        if (toTransfer == null) {
            sleepPipe(normalizeLocation(pipeLocation), plugin.getPipeConfig().getSourceEmptySleepTicks());
            return false;
        }

        boolean transferred = false;
        if (path.destination() == null) {
            // No container destination - drop at the last pipe in the chain
            Location lastPipeLoc = path.lastPipeLocation();
            PipeData lastPipeData = getPipeData(lastPipeLoc);
            BlockFace lastPipeFacing = lastPipeData != null ? lastPipeData.facing() : facing;

            // Spawn at the pipe face (boundary between pipe and destination block)
            // Use lower Y for horizontal pipes since item entity has height
            double yOffset = lastPipeFacing.getModY() == 0 ? 0.25 : 0.5;
            Location dropLoc = lastPipeLoc.getBlock().getLocation().add(0.5, yOffset, 0.5);
            // Offset to the pipe's output face
            dropLoc.add(lastPipeFacing.getModX() * 0.6, lastPipeFacing.getModY() * 0.6, lastPipeFacing.getModZ() * 0.6);

            // For DOWN-facing pipes, lower spawn position to avoid clipping into the head
            if (lastPipeFacing == BlockFace.DOWN) {
                dropLoc.add(0, -0.05, 0);
            }

            // Spawn item with velocity set during spawn to avoid dropItem's default velocity
            double baseSpeed = (lastPipeFacing == BlockFace.DOWN) ? 0 : 0.25;
            double randomSpread = 0.05;
            final ItemStack finalTransfer = toTransfer;
            BlockFace finalFacing = lastPipeFacing;

            world.spawn(dropLoc, Item.class, spawnedItem -> {
                spawnedItem.setItemStack(finalTransfer);

                Vector velocity = new Vector(
                    finalFacing.getModX() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread,
                    finalFacing.getModY() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread,
                    finalFacing.getModZ() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread
                );
                spawnedItem.setVelocity(velocity);
            });

            transferred = true;
        } else {
            Block destBlock = path.destination().getBlock();
            ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
            if (destAdapter != null) {
                PipeData lastPipeData = getPipeData(path.lastPipeLocation());
                BlockFace approachFace = lastPipeData != null
                    ? lastPipeData.facing().getOppositeFace()
                    : null;
                if (approachFace == null || !destAdapter.canReceiveFrom(destBlock, approachFace)) {
                    sleepPipe(normalizeLocation(pipeLocation), plugin.getPipeConfig().getDestFullSleepTicks());
                    return false;
                }
                ItemStack leftover = destAdapter.insert(destBlock, toTransfer);
                if (leftover == null) {
                    transferred = true;
                } else {
                    // Partial insert: only commit what was actually inserted
                    int insertedAmount = toTransfer.getAmount() - leftover.getAmount();
                    if (insertedAmount > 0) {
                        ItemStack partialExtract = toTransfer.clone();
                        partialExtract.setAmount(insertedAmount);
                        sourceAdapter.commitExtract(sourceBlock, partialExtract);
                    } else {
                        // Destination completely full
                        sleepPipe(normalizeLocation(pipeLocation), plugin.getPipeConfig().getDestFullSleepTicks());
                    }
                    return false;
                }
            }
        }

        if (transferred) {
            sourceAdapter.commitExtract(sourceBlock, toTransfer);
        }
        return false;
    }

    private CachedPath getOrBuildPath(Location pipeLocation, BlockFace facing, int startingMax) {
        Location key = normalizeLocation(pipeLocation);
        CachedPath cached = pathCache.get(key);
        if (cached != null && isPathStillValid(key, cached)) {
            return cached;
        }

        CachedPath fresh = findDestination(pipeLocation, facing, new HashSet<>(), new ArrayList<>(), startingMax);
        pathCache.put(key, fresh);
        return fresh;
    }

    private boolean isPathStillValid(Location key, CachedPath path) {
        // Verify all pipes in the chain still exist
        for (Location pipeLoc : path.pipeChain()) {
            if (!isPipe(pipeLoc)) return false;
        }

        if (path.destination() == null) {
            // Dead-end: use cooldown to avoid rechecking every tick
            int recheckTicks = plugin.getPipeConfig().getEndRecheckSleepTicks();
            if (recheckTicks > 0) {
                Long recheckAt = deadEndRecheckAt.get(key);
                long currentTick = Bukkit.getCurrentTick();
                if (recheckAt != null && currentTick < recheckAt) {
                    return true; // Still in cooldown, assume valid
                }
            }

            // Check if a container or pipe appeared at the end
            PipeData lastPipeData = getPipeData(path.lastPipeLocation());
            if (lastPipeData != null) {
                Block endBlock = path.lastPipeLocation().getBlock().getRelative(lastPipeData.facing());
                if (ContainerAdapterRegistry.findAdapter(endBlock).isPresent()) return false;
                if (getPipeData(normalizeLocation(endBlock.getLocation())) != null) return false;
            }

            // Still a dead-end, reset cooldown
            if (recheckTicks > 0) {
                deadEndRecheckAt.put(key, (long) Bukkit.getCurrentTick() + recheckTicks);
            }
            return true;
        }

        // Verify destination still has a container
        Block destBlock = path.destination().getBlock();
        return ContainerAdapterRegistry.findAdapter(destBlock).isPresent();
    }

    private CachedPath findDestination(Location pipeLocation, BlockFace facing,
                                        Set<Location> visited, List<Location> chain, int currentMinItems) {
        Location current = pipeLocation;
        BlockFace currentFacing = facing;
        // Filter pipes encountered along the path (in walk order). Static for the path's lifetime — the
        // path cache is cleared on any pipe add/remove, so this can't go stale. The extracting pipe is
        // element 0 of the chain, so a filter pipe sitting on the source is captured here too.
        List<Location> filterPipes = new ArrayList<>();

        while (true) {
            Location normalized = normalizeLocation(current);
            visited.add(normalized);
            chain.add(normalized);

            PipeData selfData = getPipeData(normalized);
            if (selfData != null) {
                currentMinItems = Math.min(currentMinItems, selfData.variant().itemsPerTransfer());
                if (selfData.variant().isFilter()) {
                    filterPipes.add(normalized);
                }
            }

            Block nextBlock = normalized.getBlock().getRelative(currentFacing);
            Location nextLoc = normalizeLocation(nextBlock.getLocation());

            if (visited.contains(nextLoc)) {
                return new CachedPath(null, normalized, chain, currentMinItems, filterPipes);
            }

            Optional<ContainerAdapter> adapterOpt = ContainerAdapterRegistry.findAdapter(nextBlock);
            if (adapterOpt.isPresent()) {
                if (adapterOpt.get().canReceiveFrom(nextBlock, currentFacing.getOppositeFace())) {
                    return new CachedPath(nextLoc, normalized, chain, currentMinItems, filterPipes);
                }
                return new CachedPath(null, normalized, chain, currentMinItems, filterPipes);
            }

            PipeData nextPipeData = getPipeData(nextLoc);
            if (nextPipeData == null) {
                return new CachedPath(null, normalized, chain, currentMinItems, filterPipes);
            }
            if (nextPipeData.facing() == currentFacing.getOppositeFace()) {
                return new CachedPath(null, normalized, chain, currentMinItems, filterPipes);
            }

            current = nextLoc;
            currentFacing = nextPipeData.facing();
        }
    }

    /**
     * Combined accept-predicate for every filter pipe on {@code path} (logical AND — a series of gates),
     * or {@code null} when the path has no filter pipes (caller uses the plain unfiltered peek). Filter
     * contents are read live from the filter cache, so GUI edits take effect without rebuilding the path.
     */
    private java.util.function.@org.jspecify.annotations.Nullable Predicate<ItemStack> buildChainFilter(CachedPath path) {
        List<Location> filterPipes = path.filterPipes();
        if (filterPipes.isEmpty()) return null;
        List<PipeFilterStore.FilterData> filters = new ArrayList<>(filterPipes.size());
        for (Location loc : filterPipes) {
            // A redstone-powered filter pipe is switched OFF: it blocks everything on its chain.
            if (isPipePowered(loc.getBlock())) return item -> false;
            filters.add(getFilter(loc));
        }
        return item -> {
            for (PipeFilterStore.FilterData f : filters) {
                if (!f.test(item)) return false;
            }
            return true;
        };
    }

    /**
     * Whether a filter pipe is currently redstone-powered (→ switched off). Senses only the pipe's own
     * head cell: {@code getBlockPower() > 0} reflects redstone placed next to the pipe (lever/dust/redstone
     * block/torch on a neighbor). We deliberately do NOT read the mount cell — a filter pipe is mounted on
     * its source container, so reading the mount would let a powered/locked source (e.g. a redstone-locked
     * hopper) switch the filter off. Live query, no cached state. Same primitive RotationRotator uses.
     */
    public boolean isPipePowered(Block block) {
        return block.getBlockPower() > 0;
    }

    /**
     * Attempt to deliver items from a machine above through this pipe's chain.
     * @return true if all items delivered, false if destination full, null if not a valid receiving pipe
     */
    public Boolean deliverFromAbove(Block pipeBlock, List<ItemStack> items) {
        PipeData data = getPipeData(pipeBlock.getLocation());
        if (data == null) return null;
        if (data.facing() != BlockFace.DOWN) return null;

        CachedPath path = getOrBuildPath(pipeBlock.getLocation(), data.facing(),
            data.variant().itemsPerTransfer());
        if (path.destination() == null) return null;

        // A machine push is atomic (all-or-nothing) and can't partially deliver, so if any output is
        // rejected by a filter pipe on the path, STALL the whole push rather than leak it past the filter.
        java.util.function.Predicate<ItemStack> accept = buildChainFilter(path);
        if (accept != null) {
            for (ItemStack item : items) {
                if (item != null && !accept.test(item)) return false;
            }
        }

        Block destBlock = path.destination().getBlock();
        ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
        if (destAdapter == null) return null;

        PipeData lastPipeData = getPipeData(path.lastPipeLocation());
        BlockFace approachFace = lastPipeData != null ? lastPipeData.facing().getOppositeFace() : null;
        if (approachFace == null || !destAdapter.canReceiveFrom(destBlock, approachFace)) return false;

        // Atomic insertion: snapshot destination, insert all items, rollback on any failure.
        // Prevents item duplication when some items fit but others don't — without rollback,
        // the machine stalls (keeps inputs) while already-inserted items remain in the destination.
        // Snapshot the adapter's backing inventory (real tile OR virtual machine storage), not the
        // block's raw tile state, so machine-to-machine pushes deliver into virtual storage too.
        Inventory inv = destAdapter.backingInventory(destBlock);
        if (inv == null) return false;
        ItemStack[] snapshot = Arrays.stream(inv.getContents())
            .map(s -> s == null ? null : s.clone()).toArray(ItemStack[]::new);

        for (ItemStack item : items) {
            if (item == null) continue;
            ItemStack leftover = destAdapter.insert(destBlock, item);
            if (leftover != null) {
                inv.setContents(snapshot);
                return false;
            }
        }
        return true;
    }

    /** Whether {@code block} is a registered pipe whose variant can carry {@code fluid}. */
    public boolean isFluidConduit(Block block, FluidType fluid) {
        PipeData data = getPipeData(normalizeLocation(block.getLocation()));
        return data != null && data.variant().fluids().contains(fluid);
    }

    /**
     * Pump-driven fluid transport: walk the chain from {@code firstPipe} (following pipe
     * facings, corner bends included — the same geometry as {@link #findDestination}) and
     * deliver ONE unit of {@code fluid} into the first accepting {@link FluidEndpoints}
     * endpoint. Differences from the item walk, all deliberate:
     * <ul>
     *   <li>every segment must carry the fluid (lava needs iron end to end);</li>
     *   <li>endpoints are fluid endpoints, not containers;</li>
     *   <li>a chain that ends in the world does NOT spill — fluids never drop as items, the
     *       pump just stalls (an air cell only receives fluid as a source block via the
     *       world-source endpoint, which is the normal "pump out" case);</li>
     *   <li>uncached — one walk per pump cycle (~2 s), not worth a fluid-keyed path cache.</li>
     * </ul>
     *
     * @return true when the unit was delivered — the pump then drains its intake
     */
    public boolean pushFluid(Block firstPipe, FluidType fluid) {
        Location current = normalizeLocation(firstPipe.getLocation());
        PipeData data = getPipeData(current);
        if (data == null || !data.variant().fluids().contains(fluid)) return false;
        BlockFace facing = data.facing();

        Set<Location> visited = new HashSet<>();
        while (true) {
            visited.add(current);

            Block nextBlock = current.getBlock().getRelative(facing);
            Location nextLoc = normalizeLocation(nextBlock.getLocation());
            if (visited.contains(nextLoc)) return false;                  // loop

            FluidEndpoint endpoint = FluidEndpoints.accepting(nextBlock, fluid);
            if (endpoint != null) return endpoint.fill(nextBlock, fluid);

            PipeData next = getPipeData(nextLoc);
            if (next == null) return false;                               // dead end / full vessel
            if (!next.variant().fluids().contains(fluid)) return false;   // segment can't carry it
            if (next.facing() == facing.getOppositeFace()) return false;  // head-on pipe

            current = nextLoc;
            facing = next.facing();
        }
    }

    public void shutdown() {
        stopTasks();
        pipes.clear();
        filterCache.clear();
        filterPowered.clear();
        sleepUntil.clear();
        clearPathCaches();
    }

    /**
     * Gets a count of registered pipes grouped by variant ID.
     * @return Map of variant ID to count
     */
    public Map<String, Integer> getPipeCountsByVariant() {
        Map<String, Integer> counts = new HashMap<>();
        for (PipeData data : pipes.values()) {
            String variantId = data.variant().id();
            counts.merge(variantId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Gets the total number of registered pipes.
     * @return Total pipe count
     */
    public int getTotalPipeCount() {
        return pipes.size();
    }

    /**
     * Deletes all pipes and their display entities in this manager's world.
     * Also removes the pipe blocks themselves.
     * @return The number of pipes deleted
     */
    public int deleteAllPipes() {
        CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
        List<Location> toRemove = new ArrayList<>(pipes.keySet());

        for (Location loc : toRemove) {
            Block block = loc.getBlock();
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                CustomHeadBlock type = registry.getTypeFromBlock(block);
                if (type != null) {
                    registry.onBlockRemoved(block, type);
                }
                block.setType(Material.AIR);
            }
            removePipeData(loc);
        }

        return toRemove.size();
    }

    public World getWorld() {
        return world;
    }

    /**
     * Re-resolve variant references for all registered pipes against the current registry.
     * Called after config reload to replace stale PipeVariant objects in PipeData records.
     */
    public void reloadVariants(VariantRegistry registry) {
        for (Map.Entry<Location, PipeData> entry : pipes.entrySet()) {
            PipeData data = entry.getValue();
            PipeVariant fresh = registry.getVariant(data.variant().id());
            if (fresh != null && fresh != data.variant()) {
                entry.setValue(new PipeData(data.facing(), data.displayEntityIds(), fresh));
            } else if (fresh == null) {
                plugin.getLogger().warning("Variant '" + data.variant().id() + "' no longer exists after reload; pipe at " + entry.getKey().toVector() + " is stale");
            }
        }
        sleepUntil.clear();
        clearPathCaches();
    }

    public void restartTasks() {
        stopTasks();
        startTasks();
    }

    private void stopTasks() {
        if (transferTask != null) {
            transferTask.cancel();
            transferTask = null;
        }
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    private Location normalizeLocation(Location location) {
        return new Location(world,
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    public record PipeData(BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {}
}
