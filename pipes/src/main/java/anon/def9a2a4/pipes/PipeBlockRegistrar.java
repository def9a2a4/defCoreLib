package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class PipeBlockRegistrar {

    public static void register(CustomBlockRegistry registry, PipesPlugin plugin) {
        for (PipeVariant variant : plugin.getVariantRegistry().getAllVariants()) {
            overlayVariant(registry, plugin, variant);
        }
    }

    private static void overlayVariant(CustomBlockRegistry registry, PipesPlugin plugin, PipeVariant variant) {
        String fullId = "pipes:" + variant.getId();
        CustomHeadBlock base = registry.getType(fullId);
        if (base == null) {
            plugin.getLogger().warning("No YAML definition for " + fullId + " — skipping overlay");
            return;
        }

        boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;
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

        builder.onBlockPlaced((block, state) -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return;
            BlockFace facing = parseFacing(state);
            manager.registerPipe(block.getLocation(), facing, List.of(), variant);
        });

        builder.onBlockRemoved((block, state) -> {
            PipeManager manager = plugin.getPipeManager(block.getWorld());
            if (manager == null) return;
            manager.removePipeData(block.getLocation());
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
            PipeManager.PipeData data = manager.getPipeData(block.getLocation());
            if (data != null) {
                BlockFace facing = data.facing();
                if (changedFace == facing || changedFace == facing.getOppositeFace()) {
                    manager.wakeUpPipe(block.getLocation());
                }
            }
        });

        registry.register(builder.build());
        plugin.getLogger().info("Registered " + variant.getId() + " with CoreLib");
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
