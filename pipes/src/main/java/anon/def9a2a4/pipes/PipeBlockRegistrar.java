package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import anon.def9a2a4.corelib.HeadUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rotatable;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class PipeBlockRegistrar {

    // The filter pipe's ring display entity shows "on" (passing) by default (@filter_ring_on in pipes.yml);
    // when the pipe is redstone-powered (switched OFF) the displayItemResolver swaps in this "off" ring.
    // Built once from the same base64 the pipes.yml @filter_ring_off alias uses (cf. FilterGui's skulls).
    private static final String FILTER_RING_OFF_BASE64 =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmM5YzUyNTFhN2NmMmJiNjU3YTAyNDcyY2QyNGYyYTRhMjY4ZGNjNGZhNjljYjJiNDdkYTQyNDM1NWU4ZmY5NyJ9fX0=";
    private static final ItemStack FILTER_RING_OFF = HeadUtil.createHead(FILTER_RING_OFF_BASE64, 1);

    public static void register(CustomBlockRegistry registry, PipesPlugin plugin) {
        for (PipeVariant variant : plugin.getVariantRegistry().getAllVariants()) {
            overlayVariant(registry, plugin, variant);
            // Pipes keep transferring aboard moving mechanisms (carts, hoist platforms): register
            // the variant's chain behaviour with corelib's mechanism driver, which mirrors the
            // ground model (directed facing-chain, corner bends, per-variant throughput) over the
            // mechanism's travelling inventories. Facing convention is ours, hence the resolver.
            anon.def9a2a4.corelib.MechanismConduits.register("pipes:" + variant.id(),
                variant.behaviorType() == BehaviorType.CORNER,
                variant.transferIntervalTicks(), variant.itemsPerTransfer(),
                PipeBlockRegistrar::parseFacing);
        }
    }

    private static void overlayVariant(CustomBlockRegistry registry, PipesPlugin plugin, PipeVariant variant) {
        String fullId = "pipes:" + variant.id();
        CustomHeadBlock base = registry.getType(fullId);
        if (base == null) {
            plugin.getLogger().warning("No YAML definition for " + fullId + " — skipping overlay");
            return;
        }

        boolean isCorner = variant.behaviorType() == BehaviorType.CORNER;
        CustomHeadBlock.Builder builder = base.toBuilder();

        if (isCorner) {
            builder.playerHeadStates("down");
        } else {
            builder.playerHeadStates("up", "down");
        }
        builder.breakOnPiston(true);

        if (isCorner) {
            builder.stateResolver(event -> resolveCornerFacing(event, plugin));
        } else {
            builder.stateResolver(event -> resolveRegularFacing(event, plugin));
        }

        builder.displayTransformResolver((block, state, config, idx) -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return null;
            return manager.resolveTransform(block, state, variant, idx);
        });

        // Filter pipes open their config GUI on right-click (returning true cancels vanilla interaction).
        if (variant.isFilter()) {
            builder.onInteract((block, event) -> {
                plugin.getFilterGui().open(event.getPlayer(), block, variant);
                return true;
            });
            // The ring display entity (tag "ring") shows on/off by live redstone power: return the "off"
            // ring when the pipe is powered (switched off), else null → the YAML @filter_ring_on stands.
            // The "main" pipe body entity is left untouched. Re-invoked whenever applyConfig respawns the
            // block's displays (placement, chunk load, and the power-edge redraw in onNeighborChange).
            builder.displayItemResolver((block, tag) -> {
                if (!"ring".equals(tag)) return null;
                PipeManager manager = plugin.getPipeManager(block.getWorld());
                return manager != null && manager.isPipePowered(block) ? FILTER_RING_OFF : null;
            });
        }

        builder.onBlockPlaced((block, state) -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return;
            if (block.getType() == Material.PLAYER_HEAD
                    && block.getBlockData() instanceof Rotatable rotatable) {
                BlockFace facing = parseFacing(state);
                rotatable.setRotation(facing == BlockFace.UP || facing == BlockFace.DOWN
                        ? BlockFace.NORTH : facing);
                block.setBlockData(rotatable, false);
            }
            BlockFace facing = parseFacing(state);
            manager.registerPipe(block.getLocation(), facing, List.of(), variant);
            // CoreLib suppresses physics on custom-block placement, so adjacent pipes never get the
            // BlockPhysicsEvent that would recompute their connection transform. Refresh them explicitly.
            registry.refreshReactiveNeighbors(block);
        });

        builder.onBlockRemoved((block, state) -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return;
            if (variant.isFilter()) {
                // Close any open config menu FIRST so its (real) items can't be dragged out of a stale menu
                // after we drop them (a dupe); the close saves current contents to the still-intact PDC.
                plugin.getFilterGui().closeFor(block);
                // Filter items were consumed from the player — return them on a real break. But during a
                // mechanism CAPTURE the items ride along in the block's configPdc and are restored on
                // landing, so dropping them here would duplicate them (A10a) — suppress the drop then.
                if (!registry.isCapturingForMechanism()) {
                    for (var item : PipeFilterStore.read(block).dropContents()) {
                        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
                    }
                }
            }
            manager.removePipeData(block.getLocation());
            // Same physics-suppression gap on removal — refresh neighbors next tick, once this pipe's
            // block is actually gone, so they recompute against the now-empty cell.
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> registry.refreshReactiveNeighbors(block));
        });

        builder.onChunkLoad((block, state) -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return;
            if (!manager.isPipe(block.getLocation())) {
                BlockFace facing = parseFacing(state);
                manager.registerPipe(block.getLocation(), facing, List.of(), variant);
            }
        });

        builder.onChunkUnload(block -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return;
            manager.removePipeData(block.getLocation());
        });

        builder.onNeighborChange((block, changedFace) -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return;
            manager.invalidatePathCache();
            // A filter pipe's redstone off-switch can toggle from any face. Edge-detect it: on the
            // powered→unpowered edge this wakes sleeping upstream extractors (resume); on EITHER edge it
            // returns true, so we respawn the block's displays via applyConfig — re-invoking the ring's
            // displayItemResolver to flip its on/off texture. Gated to real transitions to avoid churn.
            if (variant.isFilter() && manager.onFilterPowerEdge(block)) {
                CustomHeadBlock type = registry.getType(fullId);
                if (type != null) {
                    registry.applyConfig(block, type, registry.getState(block), 0);
                }
            }
            PipeManager.PipeData data = manager.getPipeData(block.getLocation());
            if (data != null) {
                BlockFace facing = data.facing();
                if (changedFace == facing || changedFace == facing.getOppositeFace()) {
                    manager.wakeUpPipe(block.getLocation());
                }
            }
        });

        registry.register(builder.build());
        plugin.getLogger().info("Registered " + variant.id() + " with CoreLib");
    }

    private static @Nullable String resolveRegularFacing(BlockPlaceEvent event, PipesPlugin plugin) {
        if (!isWorldEnabled(plugin, event.getBlock())) return null;

        Block against = event.getBlockAgainst();
        BlockFace clickedFace = against.getFace(event.getBlockPlaced());
        BlockFace facing = clickedFace != null ? clickedFace
                : getPlayerFacing(event.getPlayer().getLocation().getYaw());
        return facing.name().toLowerCase();
    }

    private static @Nullable String resolveCornerFacing(BlockPlaceEvent event, PipesPlugin plugin) {
        if (!isWorldEnabled(plugin, event.getBlock())) return null;

        Block against = event.getBlockAgainst();
        BlockFace clickedFace = against.getFace(event.getBlockPlaced());
        BlockFace facing = clickedFace != null ? clickedFace
                : getPlayerFacing(event.getPlayer().getLocation().getYaw());

        if (facing == BlockFace.DOWN) return null;
        facing = facing.getOppositeFace();
        return facing.name().toLowerCase();
    }

    private static boolean isWorldEnabled(PipesPlugin plugin, Block block) {
        var filter = plugin.getPipeConfig().getWorldFilter();
        return filter == null || filter.isEnabled(block.getWorld().getName());
    }

    private static BlockFace getPlayerFacing(float yaw) {
        yaw = (yaw % 360 + 360) % 360;
        if (yaw >= 315 || yaw < 45) return BlockFace.SOUTH;
        if (yaw >= 45 && yaw < 135) return BlockFace.WEST;
        if (yaw >= 135 && yaw < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    static BlockFace parseFacing(@Nullable String state) {
        if (state == null) return BlockFace.NORTH;
        return switch (state) {
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            case "up" -> BlockFace.UP;
            case "down" -> BlockFace.DOWN;
            default -> BlockFace.NORTH;
        };
    }
}
