package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Registers all rotation power system blocks programmatically.
 * Phase 1 MVP: shaft + windmill only.
 */
final class RotationBlocks {

    private RotationBlocks() {}

    // Placeholder skull textures (barrel-style for now)
    private static final String SHAFT_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzNmYjdjOTJmZTE2NDlkNzZjNGFkOTc5ZWZjZDJjNDYwYjJlOTBiMjMyMGEyMzNlZjg1MTMzZGQ1NmJlZDg2YSJ9fX0=";
    private static final String WINDMILL_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjg4NGFkYTRjNzRjMGY5NjE0NjkyNjJkOTU2OTc3YmFmYjBiMmQ3ZmVkOWM3YmRmMzVmNDUxOTllOWY2ZGRiYiJ9fX0=";

    private static final Map<BlockFace, String> IDLE_PSM = Map.of(
        BlockFace.DOWN,  "idle_y",
        BlockFace.NORTH, "idle_z",
        BlockFace.SOUTH, "idle_z",
        BlockFace.EAST,  "idle_x",
        BlockFace.WEST,  "idle_x"
    );

    private static final Map<BlockFace, String> SPINNING_PSM = Map.of(
        BlockFace.DOWN,  "spinning_y",
        BlockFace.NORTH, "spinning_z",
        BlockFace.SOUTH, "spinning_z",
        BlockFace.EAST,  "spinning_x",
        BlockFace.WEST,  "spinning_x"
    );

    static void register(CustomBlockRegistry registry, RotationNetwork network) {
        registerShaft(registry, network);
        registerWindmill(registry, network);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shaft — transmission, 0 power
    // ──────────────────────────────────────────────────────────────────────

    private static void registerShaft(CustomBlockRegistry registry, RotationNetwork network) {
        CustomHeadBlock shaft = CustomHeadBlock.builder("rotation", "shaft")
            .name(Component.text("Shaft", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            .lore(List.of(Component.text("Transmits rotational power", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)))
            .texture(SHAFT_TEXTURE)
            .drops(CustomHeadBlock.DropRule.self())
            .defaultState("idle_y")
            .placementStateMap(IDLE_PSM)
            // Idle states (no display entity animation)
            .state("idle_x", sb -> sb.displayEntities(shaftDisplay("x", false)))
            .state("idle_y", sb -> sb.displayEntities(shaftDisplay("y", false)))
            .state("idle_z", sb -> sb.displayEntities(shaftDisplay("z", false)))
            // Spinning states (animated)
            .state("spinning_x", sb -> sb.displayEntities(shaftDisplay("x", true)))
            .state("spinning_y", sb -> sb.displayEntities(shaftDisplay("y", true)))
            .state("spinning_z", sb -> sb.displayEntities(shaftDisplay("z", true)))
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((block, face) -> {
                CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(block);
                if (network.getNode(key) != null) {
                    network.recalculate(key);
                }
            })
            .onChunkLoad((block, state) -> {
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
                network.addNode(block, "rotation:shaft", axis,
                    RotationNetwork.NodeRole.TRANSMITTER, 0, false);
            })
            .onChunkUnload(block -> {
                network.removeNode(CustomBlockRegistry.LocationKey.of(block));
            })
            .build();

        registry.register(shaft);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Windmill — passive source, 1 power, always spinning
    // ──────────────────────────────────────────────────────────────────────

    private static void registerWindmill(CustomBlockRegistry registry, RotationNetwork network) {
        CustomHeadBlock windmill = CustomHeadBlock.builder("rotation", "windmill")
            .name(Component.text("Windmill", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
            .lore(List.of(Component.text("Produces 1 unit of rotation power", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)))
            .texture(WINDMILL_TEXTURE)
            .drops(CustomHeadBlock.DropRule.self())
            .defaultState("spinning_y")
            .placementStateMap(SPINNING_PSM)
            // Windmill is always spinning — no idle states
            .state("spinning_x", sb -> sb.displayEntities(windmillDisplay("x")))
            .state("spinning_y", sb -> sb.displayEntities(windmillDisplay("y")))
            .state("spinning_z", sb -> sb.displayEntities(windmillDisplay("z")))
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((block, face) -> {
                // Windmill itself doesn't change state, but neighbors might need recalc
                CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(block);
                if (network.getNode(key) != null) {
                    network.recalculate(key);
                }
            })
            .onChunkLoad((block, state) -> {
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
                network.addNode(block, "rotation:windmill", axis,
                    RotationNetwork.NodeRole.SOURCE, 1, false);
            })
            .onChunkUnload(block -> {
                network.removeNode(CustomBlockRegistry.LocationKey.of(block));
            })
            .build();

        registry.register(windmill);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Display entity helpers
    // ──────────────────────────────────────────────────────────────────────

    private static List<CustomHeadBlock.DisplayEntityConfig> shaftDisplay(String axis, boolean spinning) {
        float speed = 3.0f;
        Vector3f scale = switch (axis) {
            case "x" -> new Vector3f(0.5f, 0.2f, 0.2f);
            case "z" -> new Vector3f(0.2f, 0.2f, 0.5f);
            default  -> new Vector3f(0.2f, 0.5f, 0.2f); // y
        };

        DisplayAnimation anim = spinning ? Animations.rotate(axisVector(axis), speed) : null;

        ItemStack item = HeadUtil.createHead(SHAFT_TEXTURE, 1);
        Transformation transform = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 0, 1),
            scale,
            new AxisAngle4f(0, 0, 0, 1)
        );

        return List.of(new CustomHeadBlock.DisplayEntityConfig(
            item, transform, "rod", anim, spinning ? 2 : 0, 0f
        ));
    }

    private static List<CustomHeadBlock.DisplayEntityConfig> windmillDisplay(String axis) {
        // Simple spinning display — reuses the windmill texture as a rotating disc
        float speed = 1.5f;
        DisplayAnimation anim = Animations.rotate(axisVector(axis), speed);

        ItemStack item = HeadUtil.createHead(WINDMILL_TEXTURE, 1);
        Transformation transform = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(0.5f, 0.5f, 0.5f),
            new AxisAngle4f(0, 0, 0, 1)
        );

        return List.of(new CustomHeadBlock.DisplayEntityConfig(
            item, transform, "fan", anim, 2, 0f
        ));
    }

    private static Vector3f axisVector(String axis) {
        return switch (axis) {
            case "x" -> new Vector3f(1, 0, 0);
            case "z" -> new Vector3f(0, 0, 1);
            default  -> new Vector3f(0, 1, 0); // y
        };
    }
}
