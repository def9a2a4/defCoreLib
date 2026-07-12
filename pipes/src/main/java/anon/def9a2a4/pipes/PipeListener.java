package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.Location;
import anon.def9a2a4.pipes.PipeManager.PipeData;
import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class PipeListener implements Listener {

    private final PipesPlugin plugin;
    private final Map<World, PipeManager> pipeManagers;
    private final Random random = new Random();

    public PipeListener(PipesPlugin plugin, Map<World, PipeManager> pipeManagers) {
        this.plugin = plugin;
        this.pipeManagers = pipeManagers;
    }

    /**
     * Get the PipeManager for a world, or null if pipes aren't active there.
     */
    private PipeManager getManager(World world) {
        return pipeManagers.get(world);
    }

    /**
     * Handle pipe removal with optional item drop.
     *
     * Compatible with HeadSmith plugin: uses location-based registry lookup
     * (not texture-based), so non-pipe custom heads are ignored.
     *
     * @param block The pipe block being removed
     * @param shouldDrop Whether to drop the pipe item
     * @return true if this was a registered pipe (cleanup performed)
     */
    private boolean handlePipeRemoval(Block block, boolean shouldDrop) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return false;
        }

        PipeManager manager = getManager(block.getWorld());
        if (manager == null) return false;

        PipeData pipeData = manager.getPipeData(block.getLocation());
        if (pipeData == null) {
            return false;
        }

        PipeVariant variant = pipeData.variant();
        manager.unregisterPipe(block.getLocation());

        if (shouldDrop) {
            ItemStack dropItem = plugin.getPipeItem(variant);
            if (dropItem != null) {
                block.getWorld().dropItem(
                    block.getLocation().add(0.5, 0.5, 0.5),
                    dropItem,
                    item -> item.setVelocity(new Vector(0, 0, 0))
                );
            }
        }

        return true;
    }

    /**
     * Schedule adjacent pipe updates for next tick.
     */
    private void scheduleAdjacentUpdates(Location loc) {
        Bukkit.getScheduler().runTask(plugin, () -> updateAdjacentPipes(loc));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        ItemStack item = event.getItemInHand();

        PipeVariant variant = plugin.getVariant(item);
        if (variant != null) {
            PipeManager manager = getManager(block.getWorld());
            if (manager == null) {
                // Pipes not enabled in this world
                event.setCancelled(true);
                event.getPlayer().sendMessage(Component.text("Pipes are not enabled in this world.")
                        .color(NamedTextColor.RED));
                return;
            }

            BlockFace clickedFace = event.getBlockAgainst().getFace(block);

            BlockFace facing;
            if (clickedFace != null) {
                // Pipe faces away from the block it was placed against
                facing = clickedFace;
            } else {
                facing = getPlayerFacing(event.getPlayer().getLocation().getYaw());
            }

            // Corner pipes face TOWARD the clicked surface (they push TO that face)
            // Block ceiling placement (clickedFace DOWN -> would face UP after inversion)
            if (variant.getBehaviorType() == BehaviorType.CORNER) {
                if (facing == BlockFace.DOWN) {
                    event.setCancelled(true);
                    return;
                }
                facing = facing.getOppositeFace();
            }

            // For vertical pipes: ensure correct block type and locked rotation
            if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
                // Force PLAYER_HEAD for down-facing pipes (Minecraft sometimes places PLAYER_WALL_HEAD)
                if (block.getType() == Material.PLAYER_WALL_HEAD) {
                    block.setType(Material.PLAYER_HEAD, false);
                }
                // Lock rotation to NORTH (ignore player yaw)
                if (block.getBlockData() instanceof org.bukkit.block.data.Rotatable rotatable) {
                    rotatable.setRotation(BlockFace.NORTH);
                    block.setBlockData(rotatable, false);
                }
            }

            // Delay texture update for ALL pipes to ensure block state is fully initialized
            // Use runTaskLater with 2 ticks - runTask can execute same tick which isn't enough
            Location loc = block.getLocation();
            BlockFace finalFacing = facing;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                updatePlacedSkullTexture(loc.getBlock(), variant, finalFacing);
            }, 2L);

            List<ItemDisplay> displays = manager.spawnDisplayEntities(block.getLocation(), facing, variant);
            List<UUID> displayIds = displays.stream()
                    .map(ItemDisplay::getUniqueId)
                    .collect(Collectors.toList());

            manager.registerPipe(
                    block.getLocation(),
                    facing,
                    displayIds,
                    variant
            );
        }

        // Check if placed block affects adjacent pipes (works for both pipe and non-pipe placements)
        updateAdjacentPipes(block.getLocation());
    }

    private void updatePlacedSkullTexture(Block block, PipeVariant variant, BlockFace facing) {
        // Get fresh state from world - important after setType() which resets skull state
        Block freshBlock = block.getWorld().getBlockAt(block.getLocation());
        if (freshBlock.getState() instanceof Skull skull) {
            ItemStack directionItem = plugin.getHeadItemForDirection(variant, facing);
            if (directionItem != null && directionItem.getItemMeta() instanceof SkullMeta skullMeta) {
                skull.setOwnerProfile(skullMeta.getOwnerProfile());
                skull.update(true, false); // force=true, applyPhysics=false
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        if (handlePipeRemoval(block, true)) {
            event.setDropItems(false); // Prevent default head drop, we handle it
        }

        scheduleAdjacentUpdates(block.getLocation());
    }

    private BlockFace getPlayerFacing(float yaw) {
        yaw = (yaw % 360 + 360) % 360;

        if (yaw >= 315 || yaw < 45) {
            return BlockFace.SOUTH;
        } else if (yaw >= 45 && yaw < 135) {
            return BlockFace.WEST;
        } else if (yaw >= 135 && yaw < 225) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.EAST;
        }
    }

    private void updateAdjacentPipes(Location blockLocation) {
        PipeManager manager = getManager(blockLocation.getWorld());
        if (manager == null) return;

        // A block changed — invalidate all cached paths
        manager.invalidatePathCache();

        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
        Block block = blockLocation.getBlock();

        for (BlockFace face : faces) {
            Location adjacentLoc = block.getRelative(face).getLocation();
            PipeData pipeData = manager.getPipeData(adjacentLoc);
            if (pipeData != null) {
                BlockFace pipeFacing = pipeData.facing();
                // Update if block is at pipe's source (opposite of facing) or destination (facing direction)
                if (face == pipeFacing || face == pipeFacing.getOppositeFace()) {
                    manager.updateDisplayEntity(adjacentLoc);
                    manager.wakeUpPipe(adjacentLoc);
                }
            }
        }
    }

    // ========== Explosion Handlers ==========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList(), event.getYield());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList(), event.getYield());
    }

    private void handleExplosion(List<Block> blocks, float yield) {
        List<Location> affectedLocations = new ArrayList<>();
        Iterator<Block> iterator = blocks.iterator();

        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                // Roll for drop based on explosion yield
                boolean shouldDrop = random.nextFloat() < yield;
                if (handlePipeRemoval(block, shouldDrop)) {
                    affectedLocations.add(block.getLocation());
                    iterator.remove(); // Prevent vanilla from dropping the head
                    block.setType(Material.AIR, false); // Actually destroy the block
                }
            }
        }

        if (!affectedLocations.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Location loc : affectedLocations) {
                    updateAdjacentPipes(loc);
                }
            });
        }
    }

    // ========== Piston Handlers ==========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePistonEvent(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePistonEvent(event.getBlocks());
    }

    private void handlePistonEvent(List<Block> blocks) {
        List<Location> affectedLocations = new ArrayList<>();

        for (Block block : blocks) {
            if (handlePipeRemoval(block, true)) {
                affectedLocations.add(block.getLocation());
                block.setType(Material.AIR, false); // Prevent piston from processing
            }
        }

        if (!affectedLocations.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Location loc : affectedLocations) {
                    updateAdjacentPipes(loc);
                }
            });
        }
    }

    // ========== Other Block Destruction Handlers ==========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDestroy(BlockDestroyEvent event) {
        // Handles physics-based destruction and command-based destruction
        // No item drop for these cases
        Block block = event.getBlock();

        if (handlePipeRemoval(block, false)) {
            event.setWillDrop(false);
        }

        scheduleAdjacentUpdates(block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        Block block = event.getBlock();

        // Fire destroys pipes without dropping items
        handlePipeRemoval(block, false);
        scheduleAdjacentUpdates(block.getLocation());
    }

    // ========== Chunk Handlers ==========

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        PipeManager manager = getManager(event.getWorld());
        if (manager == null) return;
        // Schedule for next tick to ensure entities are fully loaded
        Bukkit.getScheduler().runTask(plugin, () -> manager.scanChunk(event.getChunk()));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        PipeManager manager = getManager(event.getWorld());
        if (manager == null) return;
        manager.unloadPipesInChunk(event.getChunk());
    }

}
