package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;

import anon.def9a2a4.pipes.adapter.ContainerAdapter;
import anon.def9a2a4.pipes.adapter.ContainerAdapterRegistry;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import org.bukkit.Chunk;

import anon.def9a2a4.pipes.config.DisplayConfig;

import java.util.ArrayList;
import java.util.Collection;
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
                               List<Location> pipeChain, int minItemsPerTransfer) {}

    private final PipesPlugin plugin;
    private final World world;
    private final Map<Location, PipeData> pipes = new HashMap<>();
    private final Map<Location, CachedPath> pathCache = new HashMap<>();
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
        pipes.put(normalizeLocation(location), new PipeData(facing, displayEntityIds, variant));
        pathCache.clear();
    }

    public void unregisterPipe(Location location) {
        Location normalized = normalizeLocation(location);
        PipeData data = pipes.remove(normalized);
        sleepUntil.remove(normalized);
        pathCache.clear();

        // Try to remove all entities by UUID first
        boolean allRemoved = true;
        if (data != null && data.displayEntityIds() != null) {
            for (UUID uuid : data.displayEntityIds()) {
                Entity entity = world.getEntity(uuid);
                if (entity != null) {
                    entity.remove();
                } else {
                    allRemoved = false;
                }
            }
        } else {
            allRemoved = false;
        }

        // Fallback: Find entities by scoreboard tag (handles UUID mismatch after restart)
        if (!allRemoved) {
            removeDisplaysByTag(normalized);
        }
    }

    private void removeDisplaysByTag(Location location) {
        Collection<Entity> nearby = world.getNearbyEntities(
                location.clone().add(0.5, 0.5, 0.5),
                1.0, 1.0, 1.0,
                e -> e instanceof ItemDisplay
        );

        // Remove ALL matching entities, not just the first one
        for (Entity entity : nearby) {
            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag != null && PipeTags.matchesLocation(pipeTag, location)) {
                entity.remove();
            }
        }
    }

    public boolean isPipe(Location location) {
        return pipes.containsKey(normalizeLocation(location));
    }

    public PipeData getPipeData(Location location) {
        return pipes.get(normalizeLocation(location));
    }

    public void updateDisplayEntity(Location pipeLocation) {
        Location normalized = normalizeLocation(pipeLocation);
        PipeData data = pipes.get(normalized);
        if (data == null || data.displayEntityIds() == null || data.displayEntityIds().isEmpty()) return;

        // For corner pipes, we need to update both displays differently
        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
            updateCornerDisplayEntities(normalized, data);
        } else {
            // Regular pipes have single display
            UUID uuid = data.displayEntityIds().get(0);
            Entity entity = world.getEntity(uuid);
            if (entity instanceof ItemDisplay display) {
                Transformation transformation = calculateTransformation(normalized, data.facing(), data.variant());
                display.setTransformation(transformation);
            }
        }
    }

    private void updateCornerDisplayEntities(Location normalized, PipeData data) {
        // Find entities by tag to determine which is directional
        Collection<Entity> nearby = world.getNearbyEntities(
                normalized.clone().add(0.5, 0.5, 0.5),
                1.0, 1.0, 1.0,
                e -> e instanceof ItemDisplay
        );

        for (Entity entity : nearby) {
            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag != null && PipeTags.matchesLocation(pipeTag, normalized)) {
                ItemDisplay display = (ItemDisplay) entity;
                if (PipeTags.isDirectionalTag(pipeTag)) {
                    // Update directional display
                    Transformation transformation = calculateCornerDirectionalTransformation(normalized, data.facing());
                    display.setTransformation(transformation);
                } else {
                    // Update main (non-directional) display
                    Transformation transformation = calculateCornerTransformation();
                    display.setTransformation(transformation);
                }
            }
        }
    }

    public List<ItemDisplay> spawnDisplayEntities(Location location, BlockFace facing, PipeVariant variant) {
        List<ItemDisplay> displays = new ArrayList<>();
        Location spawnLoc = location.clone().add(0.5, 0.5, 0.5);

        // Spawn main display entity (non-directional for corner, directional for regular)
        ItemStack pipeItem = plugin.getDisplayItem(variant, facing);
        Transformation transformation = calculateTransformation(location, facing, variant);

        ItemDisplay mainDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(pipeItem);
            entity.setPersistent(true);
            PipeTags.addPipeTag(entity, PipeTags.createTag(location, facing, variant));
            entity.setTransformation(transformation);
        });
        displays.add(mainDisplay);

        // For corner pipes, spawn a second directional display entity
        if (variant.getBehaviorType() == BehaviorType.CORNER) {
            // Use the corner variant's own directional display texture
            ItemStack directionalItem = plugin.getDirectionalDisplayItem(variant, facing);
            Transformation directionalTransformation = calculateCornerDirectionalTransformation(location, facing);

            ItemDisplay directionalDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
                entity.setItemStack(directionalItem);
                entity.setPersistent(true);
                PipeTags.addPipeTag(entity, PipeTags.createDirectionalTag(location, facing, variant));
                entity.setTransformation(directionalTransformation);
            });
            displays.add(directionalDisplay);
        }

        return displays;
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

    private boolean isPipe(Block block) {
        return pipes.containsKey(normalizeLocation(block.getLocation()));
    }

    /**
     * Categorize the block at the source (input) side of the pipe for display adjustments.
     */
    private String categorizeSourceBlock(Block sourceBlock, BlockFace currentFacing) {
        // Check if it's a pipe first
        PipeData pipeData = getPipeData(sourceBlock.getLocation());
        if (pipeData != null) {
            if (pipeData.variant().getBehaviorType() == BehaviorType.CORNER) {
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
            if (pipeData.variant().getBehaviorType() == BehaviorType.CORNER) {
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
        if (variant.getBehaviorType() == BehaviorType.CORNER) {
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
        for (Location loc : pipes.keySet()) {
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

        for (Map.Entry<Location, PipeData> entry : pipes.entrySet()) {
            Location loc = entry.getKey();
            PipeData data = entry.getValue();

            // Sleep check
            Long wakeTick = sleepUntil.get(loc);
            if (wakeTick != null) {
                if (currentTick < wakeTick) continue;
                sleepUntil.remove(loc);
            }

            // Phase offset check
            int intervalTicks = Math.max(1, data.variant().getTransferIntervalTicks());
            if (!isTransferDue(currentTick, loc, intervalTicks)) continue;

            if (transferItems(loc, data)) {
                toRemove.add(loc);
            }
        }

        for (Location loc : toRemove) {
            unregisterPipe(loc);
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
            Block block, @org.jspecify.annotations.Nullable String state, PipeVariant fallbackVariant) {
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

        return calculateTransformation(location, facing, variant);
    }

    /**
     * Remove pipe data from the registry without touching display entities.
     * Used by CoreLib callbacks where display entities are managed externally.
     */
    public void removePipeData(Location location) {
        Location normalized = normalizeLocation(location);
        pipes.remove(normalized);
        sleepUntil.remove(normalized);
        deadEndRecheckAt.remove(normalized);
        pathCache.clear();
    }

    public void invalidatePathCache() {
        pathCache.clear();
        deadEndRecheckAt.clear();
    }

    /**
     * Attempts to transfer items from this pipe.
     * @return true if the pipe should be removed (block no longer exists)
     */
    private boolean transferItems(Location pipeLocation, PipeData data) {
        if (data == null) return false;

        // Corner pipes never pull items - they only relay when items are pushed into them
        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
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
        int startingMax = data.variant().getItemsPerTransfer();
        CachedPath path = getOrBuildPath(pipeLocation, facing, startingMax);

        int maxToExtract = path.minItemsPerTransfer();

        ItemStack toTransfer = sourceAdapter.peekExtract(sourceBlock, maxToExtract);
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
        Location normalized = normalizeLocation(pipeLocation);
        chain.add(normalized);

        PipeData selfData = getPipeData(normalized);
        if (selfData != null) {
            currentMinItems = Math.min(currentMinItems, selfData.variant().getItemsPerTransfer());
        }

        Block nextBlock = normalized.getBlock().getRelative(facing);
        Location nextLoc = normalizeLocation(nextBlock.getLocation());

        if (visited.contains(nextLoc)) {
            return new CachedPath(null, normalized, chain, currentMinItems);
        }
        visited.add(nextLoc);

        Optional<ContainerAdapter> adapterOpt = ContainerAdapterRegistry.findAdapter(nextBlock);
        if (adapterOpt.isPresent()) {
            if (adapterOpt.get().canReceiveFrom(nextBlock, facing.getOppositeFace())) {
                return new CachedPath(nextLoc, normalized, chain, currentMinItems);
            }
            return new CachedPath(null, normalized, chain, currentMinItems);
        }

        PipeData nextPipeData = getPipeData(nextLoc);
        if (nextPipeData != null) {
            if (nextPipeData.facing() == facing.getOppositeFace()) {
                return new CachedPath(null, normalized, chain, currentMinItems);
            }
            return findDestination(nextLoc, nextPipeData.facing(), visited, chain, currentMinItems);
        }

        return new CachedPath(null, normalized, chain, currentMinItems);
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
            data.variant().getItemsPerTransfer());
        if (path.destination() == null) return null;

        Block destBlock = path.destination().getBlock();
        ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
        if (destAdapter == null) return null;

        PipeData lastPipeData = getPipeData(path.lastPipeLocation());
        BlockFace approachFace = lastPipeData != null ? lastPipeData.facing().getOppositeFace() : null;
        if (approachFace == null || !destAdapter.canReceiveFrom(destBlock, approachFace)) return false;

        for (ItemStack item : items) {
            ItemStack leftover = destAdapter.insert(destBlock, item);
            if (leftover != null) return false;
        }
        return true;
    }

    public void shutdown() {
        stopTasks();
        pipes.clear();
        pathCache.clear();
        sleepUntil.clear();
        deadEndRecheckAt.clear();
    }

    /**
     * Removes orphaned display entities in this manager's world.
     * An orphaned display entity is one that has a pipe tag but no corresponding pipe block.
     * @return The number of orphaned display entities removed
     */
    public int cleanupOrphanedDisplays() {
        int removed = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ItemDisplay)) continue;
            if (!PipeTags.isPipeEntity(entity)) continue;

            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag == null) continue;

            Location blockLoc = PipeTags.parseLocation(pipeTag, world);
            if (blockLoc == null) continue;

            Block block = blockLoc.getBlock();
            Material type = block.getType();

            // Check if there's a valid pipe block at this location
            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Counts orphaned display entities in this manager's world.
     * @return The number of orphaned display entities
     */
    public int countOrphanedDisplays() {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ItemDisplay)) continue;
            if (!PipeTags.isPipeEntity(entity)) continue;

            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag == null) continue;

            Location blockLoc = PipeTags.parseLocation(pipeTag, world);
            if (blockLoc == null) continue;

            Block block = blockLoc.getBlock();
            Material type = block.getType();

            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gets a count of registered pipes grouped by variant ID.
     * @return Map of variant ID to count
     */
    public Map<String, Integer> getPipeCountsByVariant() {
        Map<String, Integer> counts = new HashMap<>();
        for (PipeData data : pipes.values()) {
            String variantId = data.variant().getId();
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
        List<Location> toRemove = new ArrayList<>(pipes.keySet());

        for (Location loc : toRemove) {
            // Remove the block
            Block block = loc.getBlock();
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                block.setType(Material.AIR);
            }
            // Remove display entities and unregister
            unregisterPipe(loc);
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
            PipeVariant fresh = registry.getVariant(data.variant().getId());
            if (fresh != null && fresh != data.variant()) {
                entry.setValue(new PipeData(data.facing(), data.displayEntityIds(), fresh));
            } else if (fresh == null) {
                plugin.getLogger().warning("Variant '" + data.variant().getId() + "' no longer exists after reload; pipe at " + entry.getKey().toVector() + " is stale");
            }
        }
        pathCache.clear();
        sleepUntil.clear();
        deadEndRecheckAt.clear();
    }

    public void refreshAllDisplays() {
        for (Location location : new ArrayList<>(pipes.keySet())) {
            updateDisplayEntity(location);
        }
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

    public void scanForExistingPipes() {
        int count = 0;

        for (Chunk chunk : world.getLoadedChunks()) {
            count += scanChunk(chunk);
        }

        if (count > 0) {
            plugin.getLogger().info("Restored " + count + " pipes in world " + world.getName());
        }
    }

    public int scanChunk(Chunk chunk) {
        if (!chunk.isLoaded() || chunk.getWorld() != world) {
            return 0;
        }

        int count = 0;
        VariantRegistry registry = plugin.getVariantRegistry();

        // Group entities by location to handle multiple entities per pipe
        Map<Location, List<UUID>> entityGroups = new HashMap<>();
        Map<Location, BlockFace> facingByLocation = new HashMap<>();
        Map<Location, PipeVariant> variantByLocation = new HashMap<>();

        int migrated = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay)) continue;

            // Auto-migrate legacy scoreboard tags to PDC
            if (PipeTags.migrateIfNeeded(entity)) {
                migrated++;
            }

            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag == null) continue;

            Location location = PipeTags.parseLocation(pipeTag, world);
            BlockFace facing = PipeTags.parseFacing(pipeTag);
            String variantId = PipeTags.parseVariantId(pipeTag);

            if (location == null || facing == null || variantId == null) continue;

            PipeVariant variant = registry.getVariant(variantId);
            if (variant == null) {
                plugin.getLogger().warning("Unknown variant '" + variantId + "' for pipe at " + location);
                continue;
            }

            Location normalized = normalizeLocation(location);

            // Verify the pipe block still exists
            Block block = location.getBlock();
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                // Group entities by location
                entityGroups.computeIfAbsent(normalized, k -> new ArrayList<>()).add(entity.getUniqueId());
                facingByLocation.put(normalized, facing);
                variantByLocation.put(normalized, variant);
            } else {
                // Orphaned display entity - pipe block was removed while chunk was unloaded
                entity.remove();
            }
        }

        // Register all grouped pipes
        for (Map.Entry<Location, List<UUID>> entry : entityGroups.entrySet()) {
            Location location = entry.getKey();
            if (!isPipe(location)) {
                List<UUID> uuids = entry.getValue();
                BlockFace facing = facingByLocation.get(location);
                PipeVariant variant = variantByLocation.get(location);
                registerPipe(location, facing, uuids, variant);
                count++;
            }
        }

        if (migrated > 0) {
            plugin.getLogger().info("Migrated " + migrated + " pipe display(s) from scoreboard tags to PDC in chunk [" +
                    chunk.getX() + ", " + chunk.getZ() + "]");
        }

        return count;
    }

    public void unloadPipesInChunk(Chunk chunk) {
        if (chunk.getWorld() != world) return;

        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        pipes.entrySet().removeIf(entry -> {
            Location loc = entry.getKey();
            int locChunkX = loc.getBlockX() >> 4;
            int locChunkZ = loc.getBlockZ() >> 4;

            if (locChunkX == chunkX && locChunkZ == chunkZ) {
                sleepUntil.remove(loc);
                deadEndRecheckAt.remove(loc);
                return true;
            }
            return false;
        });
        pathCache.clear();
    }

    public BlockFace getFacingFromSkull(Block block) {
        if (block.getType() == Material.PLAYER_WALL_HEAD) {
            org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) block.getBlockData();
            return directional.getFacing();
        } else if (block.getType() == Material.PLAYER_HEAD) {
            org.bukkit.block.data.Rotatable rotatable = (org.bukkit.block.data.Rotatable) block.getBlockData();
            return rotatable.getRotation();
        }
        return BlockFace.NORTH;
    }

    public record PipeData(BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {}
}
