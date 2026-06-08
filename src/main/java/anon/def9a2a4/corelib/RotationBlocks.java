package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers all rotation power system blocks programmatically.
 */
final class RotationBlocks {

    private RotationBlocks() {}

    // ── Copper-themed textures ───────────────────────────────────────────
    // Shaft: copper pipe side (from Pipes plugin)
    private static final String SHAFT_TEX =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzdlZDVlZjUzMzAwNzczNWVlNDQwZTg0ZjU1M2ViZDY4ODYxZTAyZmM1MmE1ZmY5M2MyN2I1ODQyOTdiMzUwYSJ9fX0=";
    // Gear: copper 100 (geometric pattern)
    private static final String GEAR_TEX =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzFkMDAwNGMzNTE4NDRmODY0Y2MzMzJiZWVhOTE4ZmYyOTIxMzk2YmFjNzIwZDk1Njg3ODFlMGZhNjkzZmQyZCJ9fX0=";
    // Gearbox: deepslate box copper (mechanical box)
    private static final String GEARBOX_TEX =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWYxMmRkZWQ2MmU5MGZhMTZlZGQ0MDFjYmM3NjgyY2JiOGRhMzYyYmY4ZTc5OWMwNDJlNmZjZmVjNzkxOTE4ZiJ9fX0=";
    // Windmill: barrel
    private static final String WINDMILL_TEX =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjg4NGFkYTRjNzRjMGY5NjE0NjkyNjJkOTU2OTc3YmFmYjBiMmQ3ZmVkOWM3YmRmMzVmNDUxOTllOWY2ZGRiYiJ9fX0=";

    private static final Map<BlockFace, String> IDLE_PSM = Map.of(
        BlockFace.DOWN,  "idle_y",
        BlockFace.NORTH, "idle_z", BlockFace.SOUTH, "idle_z",
        BlockFace.EAST,  "idle_x", BlockFace.WEST,  "idle_x"
    );
    private static final Map<BlockFace, String> SPINNING_PSM = Map.of(
        BlockFace.DOWN,  "spinning_y",
        BlockFace.NORTH, "spinning_z", BlockFace.SOUTH, "spinning_z",
        BlockFace.EAST,  "spinning_x", BlockFace.WEST,  "spinning_x"
    );

    static void register(CustomBlockRegistry registry, RotationNetwork network) {
        registerShaft(registry, network);
        registerGear(registry, network);
        registerGearbox(registry, network);
        registerWindmill(registry, network);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Common callbacks
    // ──────────────────────────────────────────────────────────────────────

    private static void recalcIfKnown(Block block, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(block);
        if (network.getNode(key) != null) network.recalculate(key);
    }

    /** Debug: right-click → ActionBar with network info + highlight particles. */
    private static boolean debugInteract(Block block, org.bukkit.event.player.PlayerInteractEvent event,
                                         RotationNetwork network, CustomBlockRegistry registry) {
        var key = CustomBlockRegistry.LocationKey.of(block);
        var node = network.getNode(key);
        String state = registry.getState(block);

        String info;
        NamedTextColor color;
        if (node == null) {
            info = "Not in rotation network (state: " + state + ")";
            color = NamedTextColor.RED;
        } else {
            int[] stats = network.getNetworkStats(key);
            boolean powered = network.isPowered(key);
            info = state + " | " + (stats != null ? stats[0] + "/" + stats[1] + " SU, " + stats[2] + " blocks" : "?")
                 + " | " + (powered ? "POWERED" : "UNPOWERED");
            color = powered ? NamedTextColor.GREEN : NamedTextColor.RED;
        }
        event.getPlayer().sendActionBar(Component.text(info, color));

        // Highlight all network members with particles
        if (node != null) {
            Set<CustomBlockRegistry.LocationKey> members = network.getNetworkMembers(key);
            if (members != null) {
                World world = block.getWorld();
                for (var loc : members) {
                    world.spawnParticle(Particle.HAPPY_VILLAGER,
                        new Location(world, loc.x() + 0.5, loc.y() + 0.5, loc.z() + 0.5),
                        5, 0.25, 0.25, 0.25, 0);
                }
            }
        }
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shaft — transmitter, 0 power
    // Full block length along axis, 1/3 block wide
    // ──────────────────────────────────────────────────────────────────────

    private static void registerShaft(CustomBlockRegistry registry, RotationNetwork network) {
        var b = CustomHeadBlock.builder("rotation", "shaft")
            .name(Component.text("Shaft", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            .lore(List.of(Component.text("Transmits rotational power", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)))
            .texture(SHAFT_TEX)
            .drops(CustomHeadBlock.DropRule.self())
            .defaultState("idle_y").placementStateMap(IDLE_PSM)
            .drillable(false).reactsToNeighbors(true)
            .onNeighborChange((block, face) -> recalcIfKnown(block, network))
            .onInteract((block, event) -> debugInteract(block, event, network, registry))
            .onChunkLoad((block, state) -> network.addNode(block, "rotation:shaft",
                RotationNetwork.axisFromState(state), RotationNetwork.NodeRole.TRANSMITTER, 0, false))
            .onChunkUnload(block -> network.removeNode(CustomBlockRegistry.LocationKey.of(block)));
        for (String ax : List.of("x", "y", "z")) {
            b.state("idle_" + ax, sb -> sb.displayEntities(shaftDisplay(ax, false)));
            b.state("spinning_" + ax, sb -> sb.displayEntities(shaftDisplay(ax, true)));
        }
        registry.register(b.build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Gear — transmitter, perpendicular power transfer, gearLike=true
    // Disc shape: flat along axis, wide perpendicular
    // ──────────────────────────────────────────────────────────────────────

    private static void registerGear(CustomBlockRegistry registry, RotationNetwork network) {
        var b = CustomHeadBlock.builder("rotation", "gear")
            .name(Component.text("Gear", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
            .lore(List.of(
                Component.text("Transfers power along axis", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Connects perpendicular gears", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
            .texture(GEAR_TEX)
            .drops(CustomHeadBlock.DropRule.self())
            .defaultState("idle_y").placementStateMap(IDLE_PSM)
            .drillable(false).reactsToNeighbors(true)
            .onNeighborChange((block, face) -> recalcIfKnown(block, network))
            .onInteract((block, event) -> debugInteract(block, event, network, registry))
            .onChunkLoad((block, state) -> network.addNode(block, "rotation:gear",
                RotationNetwork.axisFromState(state), RotationNetwork.NodeRole.TRANSMITTER, 0, true))
            .onChunkUnload(block -> network.removeNode(CustomBlockRegistry.LocationKey.of(block)));
        for (String ax : List.of("x", "y", "z")) {
            b.state("idle_" + ax, sb -> sb.displayEntities(gearDisplay(ax, false)));
            b.state("spinning_" + ax, sb -> sb.displayEntities(gearDisplay(ax, true)));
        }
        registry.register(b.build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Gearbox — transmitter, disconnects when redstone-powered
    // ──────────────────────────────────────────────────────────────────────

    private static void registerGearbox(CustomBlockRegistry registry, RotationNetwork network) {
        var b = CustomHeadBlock.builder("rotation", "gearbox")
            .name(Component.text("Gearbox", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
            .lore(List.of(
                Component.text("Like a shaft, but stops", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("when powered by redstone", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
            .texture(GEARBOX_TEX)
            .drops(CustomHeadBlock.DropRule.self())
            .defaultState("idle_y").placementStateMap(IDLE_PSM)
            .drillable(false).reactsToNeighbors(true)
            .onNeighborChange((block, face) -> {
                boolean rsPowered = block.getBlockPower() > 0;
                String state = registry.getState(block);
                if (state == null) return;
                String axis = state.substring(state.lastIndexOf('_') + 1);
                String target = rsPowered ? "locked_" + axis : "idle_" + axis;
                if (!target.equals(state)) {
                    registry.setState(block, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(block);
                    if (type != null) registry.applyConfig(block, type, target, 0);
                }
                recalcIfKnown(block, network);
            })
            .onInteract((block, event) -> debugInteract(block, event, network, registry))
            .onChunkLoad((block, state) -> network.addNode(block, "rotation:gearbox",
                RotationNetwork.axisFromState(state), RotationNetwork.NodeRole.TRANSMITTER, 0, false))
            .onChunkUnload(block -> network.removeNode(CustomBlockRegistry.LocationKey.of(block)));
        for (String ax : List.of("x", "y", "z")) {
            b.state("idle_" + ax);
            b.state("spinning_" + ax);
            b.state("locked_" + ax);
        }
        registry.register(b.build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Windmill — passive source, 1 power, always spinning
    // ──────────────────────────────────────────────────────────────────────

    private static void registerWindmill(CustomBlockRegistry registry, RotationNetwork network) {
        var b = CustomHeadBlock.builder("rotation", "windmill")
            .name(Component.text("Windmill", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
            .lore(List.of(Component.text("Produces 1 unit of rotation power", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)))
            .texture(WINDMILL_TEX)
            .drops(CustomHeadBlock.DropRule.self())
            .defaultState("spinning_y").placementStateMap(SPINNING_PSM)
            .drillable(false).reactsToNeighbors(true)
            .onNeighborChange((block, face) -> recalcIfKnown(block, network))
            .onInteract((block, event) -> debugInteract(block, event, network, registry))
            .onChunkLoad((block, state) -> network.addNode(block, "rotation:windmill",
                RotationNetwork.axisFromState(state), RotationNetwork.NodeRole.SOURCE, 1, false))
            .onChunkUnload(block -> network.removeNode(CustomBlockRegistry.LocationKey.of(block)));
        for (String ax : List.of("x", "y", "z")) {
            b.state("spinning_" + ax, sb -> sb.displayEntities(windmillDisplay(ax)));
        }
        registry.register(b.build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Display entity helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Shaft: full block length along axis, 1/3 block wide. */
    private static List<CustomHeadBlock.DisplayEntityConfig> shaftDisplay(String axis, boolean spinning) {
        Vector3f scale = switch (axis) {
            case "x" -> new Vector3f(1.0f, 0.33f, 0.33f);
            case "z" -> new Vector3f(0.33f, 0.33f, 1.0f);
            default  -> new Vector3f(0.33f, 1.0f, 0.33f);
        };
        DisplayAnimation anim = spinning ? Animations.rotate(axisVec(axis), 3.0f) : null;
        return List.of(new CustomHeadBlock.DisplayEntityConfig(
            HeadUtil.createHead(SHAFT_TEX, 1),
            new Transformation(new Vector3f(0,0,0), new AxisAngle4f(0,0,0,1), scale, new AxisAngle4f(0,0,0,1)),
            "rod", anim, spinning ? 2 : 0, 0f));
    }

    /** Gear: disc — flat along axis (0.2), wide perpendicular (0.7). */
    private static List<CustomHeadBlock.DisplayEntityConfig> gearDisplay(String axis, boolean spinning) {
        Vector3f scale = switch (axis) {
            case "x" -> new Vector3f(0.2f, 0.7f, 0.7f);
            case "z" -> new Vector3f(0.7f, 0.7f, 0.2f);
            default  -> new Vector3f(0.7f, 0.2f, 0.7f);
        };
        DisplayAnimation anim = spinning ? Animations.rotate(axisVec(axis), 2.0f) : null;
        return List.of(new CustomHeadBlock.DisplayEntityConfig(
            HeadUtil.createHead(GEAR_TEX, 1),
            new Transformation(new Vector3f(0,0,0), new AxisAngle4f(0,0,0,1), scale, new AxisAngle4f(0,0,0,1)),
            "disc", anim, spinning ? 2 : 0, 0f));
    }

    /** Windmill: spinning cube. */
    private static List<CustomHeadBlock.DisplayEntityConfig> windmillDisplay(String axis) {
        DisplayAnimation anim = Animations.rotate(axisVec(axis), 1.5f);
        return List.of(new CustomHeadBlock.DisplayEntityConfig(
            HeadUtil.createHead(WINDMILL_TEX, 1),
            new Transformation(new Vector3f(0,0,0), new AxisAngle4f(0,0,0,1), new Vector3f(0.6f,0.6f,0.6f), new AxisAngle4f(0,0,0,1)),
            "fan", anim, 2, 0f));
    }

    private static Vector3f axisVec(String axis) {
        return switch (axis) {
            case "x" -> new Vector3f(1, 0, 0);
            case "z" -> new Vector3f(0, 0, 1);
            default  -> new Vector3f(0, 1, 0);
        };
    }
}
