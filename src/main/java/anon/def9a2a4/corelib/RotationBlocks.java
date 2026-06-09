package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Set;

/**
 * Overlays rotation network callbacks onto YAML-defined blocks.
 * Visual definitions live in rotation-blocks.yml — this class only adds
 * Java behavior (network add/remove/recalculate, debug interact, gearbox redstone).
 */
final class RotationBlocks {

    private RotationBlocks() {}

    static void register(CustomBlockRegistry registry, RotationNetwork network,
                         EngineFuelManager fuelManager, GrindRecipes grindRecipes) {
        // Overlay callbacks onto YAML-loaded blocks
        overlayStandard(registry, network, "rotation:shaft",   RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        overlayStandard(registry, network, "rotation:gear",    RotationNetwork.NodeRole.TRANSMITTER, 0, true);
        overlayClutch(registry, network);
        overlayWaterWheel(registry, network);
        overlayEngine(registry, network, fuelManager);
        overlayGrindstone(registry, network, grindRecipes);

        // Passive sources — detected at network boundary, no callbacks needed
        network.registerPassiveSource("demo:windmill", 1);
        network.registerPassiveSource("rotation:generator", 1);
        network.registerPassiveSource("rotation:large_windmill", 5);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Standard overlay: neighbor recalc + debug interact + chunk lifecycle
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayStandard(CustomBlockRegistry registry, RotationNetwork network,
                                        String blockId, RotationNetwork.NodeRole role,
                                        int power, boolean gearLike) {
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) {
            registry.getPlugin().getLogger().warning("RotationBlocks: block '" + blockId + "' not found — skipping overlay");
            return;
        }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> debugInteract(b, event, network, registry))
            .onChunkLoad((b, state) -> network.addNode(b, blockId,
                RotationNetwork.axisFromState(state), role, power, gearLike))
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Clutch: same as standard + redstone lock/unlock in onNeighborChange
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayClutch(CustomBlockRegistry registry, RotationNetwork network) {
        String blockId = "rotation:clutch";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) {
            registry.getPlugin().getLogger().warning("RotationBlocks: block '" + blockId + "' not found — skipping overlay");
            return;
        }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> {
                boolean rsPowered = b.getBlockPower() > 0;
                String state = registry.getState(b);
                if (state == null) return;
                String axis = state.substring(state.lastIndexOf('_') + 1);
                String target = rsPowered ? "locked_" + axis : "idle_" + axis;
                if (!target.equals(state)) {
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
                }
                recalcIfKnown(b, network);
            })
            .onInteract((b, event) -> debugInteract(b, event, network, registry))
            .onChunkLoad((b, state) -> network.addNode(b, blockId,
                RotationNetwork.axisFromState(state), RotationNetwork.NodeRole.TRANSMITTER, 0, false))
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Water Wheel: source, perpendicular water detection
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayWaterWheel(CustomBlockRegistry registry, RotationNetwork network) {
        String blockId = "rotation:water_wheel";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> {
                String state = registry.getState(b);
                if (state == null) return;
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
                boolean hasWater = hasPerpendicularWater(b, axis);
                String axisStr = state.substring(state.lastIndexOf('_') + 1);
                String target = (hasWater ? "spinning_" : "idle_") + axisStr;
                if (!target.equals(state)) {
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
                }
                recalcIfKnown(b, network);
            })
            .onInteract((b, event) -> debugInteract(b, event, network, registry))
            .onChunkLoad((b, state) -> {
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
                boolean spinning = state.startsWith("spinning_");
                network.addNode(b, blockId, axis,
                    spinning ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                    spinning ? 2 : 0, false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    private static boolean hasPerpendicularWater(Block block, RotationNetwork.Axis axis) {
        org.bukkit.block.BlockFace[] faces = switch (axis) {
            case X -> new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN};
            case Z -> new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST,
                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN};
            default -> new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};
        };
        for (var f : faces) {
            Block neighbor = block.getRelative(f);
            if (neighbor.getType() == org.bukkit.Material.WATER) return true;
            if (neighbor.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged())
                return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Engine: source with fuel management
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayEngine(CustomBlockRegistry registry, RotationNetwork network,
                                      EngineFuelManager fuelManager) {
        String blockId = "rotation:engine";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(20)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                // Add fuel on right-click
                var held = event.getPlayer().getInventory().getItemInMainHand();
                if (held.getType().isAir()) return debugInteract(b, event, network, registry);
                int fuelValue = fuelManager.getFuelValue(held.getType());
                if (fuelValue <= 0) return debugInteract(b, event, network, registry);

                // Consume 1 item, add fuel
                held.setAmount(held.getAmount() - 1);
                var key = CustomBlockRegistry.LocationKey.of(b);
                fuelManager.addFuel(key, fuelValue);

                // Transition to running if idle
                String state = registry.getState(b);
                if (state != null && state.startsWith("idle_")) {
                    String axis = state.substring(state.lastIndexOf('_') + 1);
                    String target = "running_" + axis;
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
                    // Re-add as SOURCE
                    network.removeNode(key);
                    network.addNode(b, blockId, RotationNetwork.axisFromState(target),
                        RotationNetwork.NodeRole.SOURCE, 5, false);
                }

                event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
                    "Fuel: " + fuelManager.getFuel(key) + " ticks",
                    net.kyori.adventure.text.format.NamedTextColor.GOLD));
                return true;
            })
            .onTick(b -> {
                String state = registry.getState(b);
                if (state == null || !state.startsWith("running_")) return;
                var key = CustomBlockRegistry.LocationKey.of(b);
                int remaining = fuelManager.tick(key);
                if (remaining <= 0) {
                    // Out of fuel — transition to idle
                    String axis = state.substring(state.lastIndexOf('_') + 1);
                    String target = "idle_" + axis;
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
                    // Re-add as non-source
                    network.removeNode(key);
                    network.addNode(b, blockId, RotationNetwork.axisFromState(target),
                        RotationNetwork.NodeRole.TRANSMITTER, 0, false);
                }
            })
            .onChunkLoad((b, state) -> {
                fuelManager.readFromPDC(b);
                boolean running = state != null && state.startsWith("running_");
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state != null ? state : "idle_y");
                network.addNode(b, blockId, axis,
                    running ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                    running ? 5 : 0, false);
            })
            .onChunkUnload(b -> {
                fuelManager.writeToPDC(b);
                fuelManager.remove(CustomBlockRegistry.LocationKey.of(b));
                network.removeNode(CustomBlockRegistry.LocationKey.of(b));
            })
            .onBlockRemoved((b, state) -> {
                fuelManager.remove(CustomBlockRegistry.LocationKey.of(b));
                network.removeNode(CustomBlockRegistry.LocationKey.of(b));
            })
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Grindstone: consumer with interact-based grinding
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayGrindstone(CustomBlockRegistry registry, RotationNetwork network,
                                          GrindRecipes grindRecipes) {
        String blockId = "rotation:grindstone";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                String state = registry.getState(b);
                if (!"spinning".equals(state)) return debugInteract(b, event, network, registry);

                var held = event.getPlayer().getInventory().getItemInMainHand();
                if (held.getType().isAir()) return debugInteract(b, event, network, registry);

                var result = grindRecipes.getResult(held.getType());
                if (result == null) return debugInteract(b, event, network, registry);

                // Consume 1 input, drop result
                held.setAmount(held.getAmount() - 1);
                b.getWorld().dropItemNaturally(
                    b.getLocation().add(0.5, 1.0, 0.5), result);
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5),
                    org.bukkit.Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
                return true;
            })
            .onChunkLoad((b, state) -> {
                // Grindstone is always Y-axis, floor only. axisFromState("idle") returns Y.
                network.addNode(b, blockId, RotationNetwork.Axis.Y,
                    RotationNetwork.NodeRole.CONSUMER, 1, false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────

    private static void warn(CustomBlockRegistry registry, String blockId) {
        registry.getPlugin().getLogger().warning("RotationBlocks: block '" + blockId + "' not found — skipping overlay");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────────────────────────────

    private static void recalcIfKnown(Block block, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(block);
        if (network.getNode(key) != null) network.recalculate(key);
    }

    /** Debug: right-click → ActionBar with network info + green particles on connected blocks. */
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
            info = state + " | "
                 + (stats != null ? stats[0] + "/" + stats[1] + " SU, " + stats[2] + " blocks" : "?")
                 + " | " + (powered ? "POWERED" : "UNPOWERED");
            color = powered ? NamedTextColor.GREEN : NamedTextColor.RED;
        }
        event.getPlayer().sendActionBar(Component.text(info, color));

        // Highlight network members with particles
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
}
