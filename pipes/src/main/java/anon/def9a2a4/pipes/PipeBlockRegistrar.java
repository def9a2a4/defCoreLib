package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import anon.def9a2a4.corelib.HeadUtil;
import anon.def9a2a4.pipes.config.DisplayConfig;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Registers pipe variants as CoreLib CustomHeadBlock types.
 * MVP: copper_pipe only. Will be extended to all 8 variants.
 */
public class PipeBlockRegistrar {

    public static void register(CustomBlockRegistry registry, PipesPlugin plugin) {
        registerCopperPipe(registry, plugin);
    }

    private static void registerCopperPipe(CustomBlockRegistry registry, PipesPlugin plugin) {
        PipeVariant variant = plugin.getVariantRegistry().getVariant("copper_pipe");
        if (variant == null) {
            plugin.getLogger().warning("copper_pipe variant not found in config — skipping CoreLib registration");
            return;
        }

        DisplayConfig.TextureSet textures = plugin.getDisplayConfig().getTextureSet(variant.getTextureSetId());
        if (textures == null) {
            plugin.getLogger().warning("Texture set '" + variant.getTextureSetId() + "' not found — skipping copper_pipe registration");
            return;
        }

        // Pre-create display head items for each facing group
        ItemStack displayHorizontal = HeadUtil.createHead(textures.getItemDisplayTexture(BlockFace.NORTH), 1);
        ItemStack displayUp = HeadUtil.createHead(textures.getItemDisplayTexture(BlockFace.UP), 1);
        ItemStack displayDown = HeadUtil.createHead(textures.getItemDisplayTexture(BlockFace.DOWN), 1);

        // Placeholder transform — displayTransformResolver overrides per-instance
        Transformation placeholder = new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 1, 0),
                new Vector3f(1, 1, 1),
                new AxisAngle4f(0, 0, 0, 1)
        );

        String headHorizontal = textures.getHeadTexture(BlockFace.NORTH);
        String headUp = textures.getHeadTexture(BlockFace.UP);
        String headDown = textures.getHeadTexture(BlockFace.DOWN);

        String unlockAdv = plugin.getPipeConfig().getUnlockAdvancement();

        CustomHeadBlock.Builder builder = CustomHeadBlock.builder("pipes", "copper_pipe")
                .texture(headHorizontal)
                .itemTexture(textures.item())
                .name(variant.getDisplayName().decoration(TextDecoration.ITALIC, false))

                // 6 facing states with direction-appropriate head textures
                .state("north", s -> s.texture(headHorizontal))
                .state("south", s -> s.texture(headHorizontal))
                .state("east", s -> s.texture(headHorizontal))
                .state("west", s -> s.texture(headHorizontal))
                .state("up", s -> s.texture(headUp))
                .state("down", s -> s.texture(headDown))
                .defaultState("north")
                .playerHeadStates("up", "down")

                .stateResolver(event -> resolveFacing(event, plugin))

                // Single display entity — transform is overridden by the resolver
                .displayEntities(List.of(new CustomHeadBlock.DisplayEntityConfig(
                        displayHorizontal, placeholder, "main", null, 0, 0f
                )))

                .displayItemResolver((block, tagSuffix) -> {
                    String state = registry.getState(block);
                    if (state == null) return displayHorizontal.clone();
                    return switch (state) {
                        case "up" -> displayUp.clone();
                        case "down" -> displayDown.clone();
                        default -> displayHorizontal.clone();
                    };
                })

                .displayTransformResolver((block, state, config, idx) -> {
                    PipeManager manager = plugin.getPipeManager(block.getWorld());
                    if (manager == null) return null;
                    return manager.resolveTransform(block, state, variant);
                })

                .breakOnPiston(true)

                .onBlockPlaced((block, state) -> {
                    PipeManager manager = plugin.getPipeManager(block.getWorld());
                    if (manager == null) return;
                    BlockFace facing = parseFacing(state);
                    manager.registerPipe(block.getLocation(), facing, List.of(), variant);
                })

                .onBlockRemoved((block, state) -> {
                    PipeManager manager = plugin.getPipeManager(block.getWorld());
                    if (manager == null) return;
                    manager.removePipeData(block.getLocation());
                })

                .onChunkLoad((block, state) -> {
                    PipeManager manager = plugin.getPipeManager(block.getWorld());
                    if (manager == null) return;
                    if (!manager.isPipe(block.getLocation())) {
                        BlockFace facing = parseFacing(state);
                        manager.registerPipe(block.getLocation(), facing, List.of(), variant);
                    }
                })

                .onChunkUnload(block -> {
                    PipeManager manager = plugin.getPipeManager(block.getWorld());
                    if (manager == null) return;
                    manager.removePipeData(block.getLocation());
                })

                .onNeighborChange((block, changedFace) -> {
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
                })

                .shapedRecipe(new CustomHeadBlock.ShapedRecipeDef(
                        "craft", 4,
                        List.of("CCC", " R ", "CCC"),
                        Map.of('C', new CustomHeadBlock.IngredientSpec(Material.COPPER_INGOT, null),
                               'R', new CustomHeadBlock.IngredientSpec(Material.REDSTONE, null))
                ));

        if (unlockAdv != null && !unlockAdv.isEmpty() && !unlockAdv.equals("none")) {
            builder.unlockAdvancement(unlockAdv);
        }

        registry.register(builder.build());
        plugin.getLogger().info("Registered copper_pipe with CoreLib");
    }

    private static @Nullable String resolveFacing(BlockPlaceEvent event, PipesPlugin plugin) {
        if (!plugin.getPipeConfig().isWorldEnabled(event.getBlock().getWorld().getName())) {
            return null;
        }

        Block block = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        BlockFace clickedFace = against.getFace(block);

        BlockFace facing;
        if (clickedFace != null) {
            facing = clickedFace;
        } else {
            facing = getPlayerFacing(event.getPlayer().getLocation().getYaw());
        }

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
