package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import anon.def9a2a4.corelib.HeadUtil;
import anon.def9a2a4.pipes.config.DisplayConfig;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class PipeBlockRegistrar {

    private static final Transformation PLACEHOLDER = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 1, 0),
            new Vector3f(1, 1, 1),
            new AxisAngle4f(0, 0, 0, 1)
    );

    public static void register(CustomBlockRegistry registry, PipesPlugin plugin) {
        for (PipeVariant variant : plugin.getVariantRegistry().getAllVariants()) {
            registerVariant(registry, plugin, variant);
        }
    }

    private static void registerVariant(CustomBlockRegistry registry, PipesPlugin plugin, PipeVariant variant) {
        DisplayConfig.TextureSet textures = plugin.getDisplayConfig().getTextureSet(variant.getTextureSetId());
        if (textures == null) {
            plugin.getLogger().warning("Texture set '" + variant.getTextureSetId()
                    + "' not found — skipping " + variant.getId() + " registration");
            return;
        }

        boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;

        ItemStack displayHorizontal = HeadUtil.createHead(textures.getItemDisplayTexture(BlockFace.NORTH), 1);
        ItemStack displayUp = HeadUtil.createHead(textures.getItemDisplayTexture(BlockFace.UP), 1);
        ItemStack displayDown = HeadUtil.createHead(textures.getItemDisplayTexture(BlockFace.DOWN), 1);

        String headHorizontal = textures.getHeadTexture(BlockFace.NORTH);
        String headUp = textures.getHeadTexture(BlockFace.UP);
        String headDown = textures.getHeadTexture(BlockFace.DOWN);

        String unlockAdv = plugin.getPipeConfig().getUnlockAdvancement();

        CustomHeadBlock.Builder builder = CustomHeadBlock.builder("pipes", variant.getId())
                .texture(headHorizontal)
                .itemTexture(textures.item())
                .name(variant.getDisplayName().decoration(TextDecoration.ITALIC, false))

                .state("north", s -> s.texture(headHorizontal))
                .state("south", s -> s.texture(headHorizontal))
                .state("east", s -> s.texture(headHorizontal))
                .state("west", s -> s.texture(headHorizontal))
                .state("down", s -> s.texture(headDown))
                .defaultState("north")
                .playerHeadStates("down")

                .drops(CustomHeadBlock.DropRule.self())
                .breakOnPiston(true);

        if (!isCorner) {
            builder.state("up", s -> s.texture(headUp));
            builder.playerHeadStates("up", "down");
        }

        if (isCorner) {
            builder.stateResolver(event -> resolveCornerFacing(event, plugin));

            ItemStack directionalDisplay = HeadUtil.createHead(
                    textures.getDirectionalDisplayTexture(BlockFace.NORTH), 1);
            builder.displayEntities(List.of(
                    new CustomHeadBlock.DisplayEntityConfig(displayHorizontal, PLACEHOLDER, "main", null, 0, 0f),
                    new CustomHeadBlock.DisplayEntityConfig(directionalDisplay, PLACEHOLDER, "directional", null, 0, 0f)
            ));

            builder.displayItemResolver((block, tagSuffix) -> {
                String state = registry.getState(block);
                if ("directional".equals(tagSuffix)) {
                    if ("down".equals(state)) {
                        return HeadUtil.createHead(textures.getDirectionalDisplayTexture(BlockFace.DOWN), 1);
                    }
                    return HeadUtil.createHead(textures.getDirectionalDisplayTexture(BlockFace.NORTH), 1);
                }
                if ("down".equals(state)) return displayDown.clone();
                return displayHorizontal.clone();
            });
        } else {
            builder.stateResolver(event -> resolveRegularFacing(event, plugin));

            builder.displayEntities(List.of(
                    new CustomHeadBlock.DisplayEntityConfig(displayHorizontal, PLACEHOLDER, "main", null, 0, 0f)
            ));

            builder.displayItemResolver((block, tagSuffix) -> {
                String state = registry.getState(block);
                if (state == null) return displayHorizontal.clone();
                return switch (state) {
                    case "up" -> displayUp.clone();
                    case "down" -> displayDown.clone();
                    default -> displayHorizontal.clone();
                };
            });
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

        if (unlockAdv != null && !unlockAdv.isEmpty() && !unlockAdv.equals("none")) {
            builder.unlockAdvancement(unlockAdv);
        }

        registry.register(builder.build());
        plugin.getLogger().info("Registered " + variant.getId() + " with CoreLib");
    }

    private static @Nullable String resolveRegularFacing(BlockPlaceEvent event, PipesPlugin plugin) {
        if (!plugin.getPipeConfig().isWorldEnabled(event.getBlock().getWorld().getName())) {
            return null;
        }

        Block against = event.getBlockAgainst();
        BlockFace clickedFace = against.getFace(event.getBlockPlaced());

        BlockFace facing;
        if (clickedFace != null) {
            facing = clickedFace;
        } else {
            facing = getPlayerFacing(event.getPlayer().getLocation().getYaw());
        }

        return facing.name().toLowerCase();
    }

    private static @Nullable String resolveCornerFacing(BlockPlaceEvent event, PipesPlugin plugin) {
        if (!plugin.getPipeConfig().isWorldEnabled(event.getBlock().getWorld().getName())) {
            return null;
        }

        Block against = event.getBlockAgainst();
        BlockFace clickedFace = against.getFace(event.getBlockPlaced());

        BlockFace facing;
        if (clickedFace != null) {
            facing = clickedFace;
        } else {
            facing = getPlayerFacing(event.getPlayer().getLocation().getYaw());
        }

        // Corner pipes can't face UP — block placement below a block
        if (facing == BlockFace.DOWN) {
            return null;
        }

        // Corner pipes face toward the block placed against (invert)
        facing = facing.getOppositeFace();
        return facing.name().toLowerCase();
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
