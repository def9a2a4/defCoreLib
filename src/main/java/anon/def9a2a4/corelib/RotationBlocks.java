package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Skull;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Overlays rotation network callbacks onto YAML-defined blocks.
 * Visual definitions live in rotation-blocks.yml — this class only adds
 * Java behavior (network add/remove/recalculate, debug interact, clutch/reverser redstone).
 */
final class RotationBlocks {

    private RotationBlocks() {}

    private static final NamespacedKey WRENCH_KEY = new NamespacedKey("rotation", "wrench");
    private static final NamespacedKey WRENCH_RECIPE_KEY = new NamespacedKey("rotation", "wrench_recipe");

    static boolean isWrench(ItemStack item) {
        if (item == null || item.getType() != Material.GOLDEN_AXE) return false;
        return item.getItemMeta().getPersistentDataContainer().has(WRENCH_KEY);
    }

    static ItemStack createWrench() {
        ItemStack wrench = new ItemStack(Material.GOLDEN_AXE);
        var meta = wrench.getItemMeta();
        meta.displayName(Component.text("Rotation Wrench", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(WRENCH_KEY, PersistentDataType.BYTE, (byte) 1);
        wrench.setItemMeta(meta);
        return wrench;
    }

    static void registerWrenchRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(WRENCH_RECIPE_KEY, createWrench());
        recipe.shape("CSC", "CS ", " S ");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(recipe);
    }

    static void register(CustomBlockRegistry registry, RotationNetwork network,
                         EngineFuelManager fuelManager, GrindRecipes grindRecipes,
                         RotationConfig config) {
        // Overlay callbacks onto YAML-loaded blocks
        overlayStandard(registry, network, "rotation:shaft",   RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        overlayStandard(registry, network, "rotation:gear",    RotationNetwork.NodeRole.TRANSMITTER, 0, true);
        // Reverser: a plain along-axis transmitter; reversal lives entirely in
        // RotationNetwork.getConnections (keyed off the "rotation:reverser" id + live redstone),
        // so the standard overlay (recalc on redstone change via reactsToNeighbors) is all it needs.
        overlayStandard(registry, network, "rotation:reverser", RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        overlayClutch(registry, network);
        overlayWaterWheel(registry, network, config);
        overlayEngine(registry, network, fuelManager, config);
        overlayGrindstone(registry, network, grindRecipes, config);
        overlayGenerator(registry, network, config);
        overlayDrill(registry, network, config);
        overlayFan(registry, network, config);

        // Passive sources — detected at network boundary, no callbacks needed
        network.registerPassiveSource("rotation:windmill", config.getPower("windmill", 1));
        network.registerPassiveSource("rotation:large_windmill", config.getPower("large_windmill", 5));
        network.registerPassiveSource("rotation:huge_windmill", config.getPower("huge_windmill", 15));

        // Windmill blade resolver — allows crafted banners to replace default WHITE_BANNER.
        // Each tier is craftable only with the matching banner tier (enforced in
        // CoreLibPlugin.captureBannerIngredients via the block's bannerTier).
        overlayWindmillResolver(registry, "rotation:windmill", BannerTier.NORMAL, network);
        overlayWindmillResolver(registry, "rotation:large_windmill", BannerTier.LARGE, network);
        overlayWindmillResolver(registry, "rotation:huge_windmill", BannerTier.HUGE, network);
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
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                return debugInteract(b, event, network, registry);
            })
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
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                return debugInteract(b, event, network, registry);
            })
            .onChunkLoad((b, state) -> network.addNode(b, blockId,
                RotationNetwork.axisFromState(state), RotationNetwork.NodeRole.TRANSMITTER, 0, false))
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Water Wheel: source, perpendicular water detection
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayWaterWheel(CustomBlockRegistry registry, RotationNetwork network,
                                              RotationConfig config) {
        int waterWheelPower = config.getPower("water_wheel", 2);
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
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand())) {
                    if (event.getPlayer().isSneaking())
                        return debugInteract(b, event, network, registry);
                    event.getPlayer().sendActionBar(
                            Component.text("Water wheels are always flexible", NamedTextColor.AQUA));
                    return true;
                }
                return debugInteract(b, event, network, registry);
            })
            .onChunkLoad((b, state) -> {
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
                boolean spinning = state.startsWith("spinning_");
                network.addNode(b, blockId, axis,
                    spinning ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                    spinning ? waterWheelPower : 0, false);
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
                                      EngineFuelManager fuelManager, RotationConfig config) {
        int enginePower = config.getPower("engine", 5);
        String blockId = "rotation:engine";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(20)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                var held = event.getPlayer().getInventory().getItemInMainHand();
                if (isWrench(held)) return wrenchInteract(b, event, network, registry);
                // Add fuel on right-click
                if (held.getType().isAir()) return debugInteract(b, event, network, registry);
                int fuelValue = fuelManager.getFuelValue(held.getType());
                if (fuelValue <= 0) return debugInteract(b, event, network, registry);

                // Consume 1 item, add fuel
                Material fuelType = held.getType();
                held.setAmount(held.getAmount() - 1);
                var key = CustomBlockRegistry.LocationKey.of(b);
                fuelManager.addFuel(key, fuelValue);

                // Bucket fuels return the empty bucket (vanilla furnace parity).
                if (fuelType == Material.LAVA_BUCKET) {
                    var player = event.getPlayer();
                    player.getInventory().addItem(new ItemStack(Material.BUCKET)).values()
                        .forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
                }

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
                        RotationNetwork.NodeRole.SOURCE, enginePower, false);
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
                    running ? enginePower : 0, false);
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
                                          GrindRecipes grindRecipes, RotationConfig config) {
        grindstoneTickInterval = config.grindstoneTickInterval;
        grindstoneMaxBatch = config.grindstoneMaxBatch;
        int grindstonePower = config.getPower("grindstone", 1);
        String blockId = "rotation:grindstone";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(grindstoneTickInterval)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                String state = registry.getState(b);
                if (!"spinning".equals(state)) return debugInteract(b, event, network, registry);

                var held = event.getPlayer().getInventory().getItemInMainHand();
                if (held.getType().isAir()) return debugInteract(b, event, network, registry);

                var result = grindRecipes.getResult(held.getType());
                if (result == null) return debugInteract(b, event, network, registry);

                // Manual grind: consume 1 from hand, eject the result below.
                held.setAmount(held.getAmount() - 1);
                ejectGrindOutput(b, result);
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5),
                    org.bukkit.Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
                return true;
            })
            .onTick(b -> grindstoneTick(b, network, grindRecipes))
            .onChunkLoad((b, state) -> {
                storeFacingIfAbsent(b);
                // Wall-mounted, but powered from the top: rotation axis is Y, so the
                // network connects ±Y and a shaft above drives it. Facing (for the host
                // container behind it) is read separately from the wall head's data.
                network.addNode(b, blockId, RotationNetwork.Axis.Y,
                    RotationNetwork.NodeRole.CONSUMER, grindstonePower, false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    /** Inventory of the container this wall-mounted grindstone is mounted on, or null. */
    private static @org.jetbrains.annotations.Nullable Inventory hostContainer(Block grind) {
        BlockFace facing = readFacing(grind);
        if (facing == null) return null;
        Block host = grind.getRelative(facing.getOppositeFace());
        return host.getState() instanceof Container c ? c.getInventory() : null;
    }

    /**
     * Place a grind result into the container directly below, else drop it below.
     * Returns true if delivered (caller may consume input); false if the output
     * container was full (stall — do not consume input).
     */
    private static boolean ejectGrindOutput(Block grind, ItemStack result) {
        Block below = grind.getRelative(BlockFace.DOWN);
        if (below.getState() instanceof Container oc) {
            return oc.getInventory().addItem(result).isEmpty();
        }
        grind.getWorld().dropItemNaturally(grind.getLocation().add(0.5, -0.2, 0.5), result);
        return true;
    }

    /** Auto-grind from the host container while powered; batch size scales with surplus SU. */
    private static void grindstoneTick(Block grind, RotationNetwork network, GrindRecipes grindRecipes) {
        var key = CustomBlockRegistry.LocationKey.of(grind);
        if (!network.isPowered(key)) return;

        Inventory in = hostContainer(grind);
        if (in == null) return;

        int[] stats = network.getNetworkStats(key);
        if (stats == null) return;
        // stats = {supply, demand, count}; surplus headroom = supply - demand.
        int batch = Math.min(grindstoneMaxBatch, Math.max(1, stats[0] - stats[1]));

        boolean ground = false;
        for (int done = 0; done < batch; done++) {
            int slot = -1;
            ItemStack src = null, result = null;
            for (int i = 0; i < in.getSize(); i++) {
                ItemStack it = in.getItem(i);
                if (it == null || it.getType().isAir()) continue;
                ItemStack r = grindRecipes.getResult(it.getType());
                if (r != null) { slot = i; src = it; result = r; break; }
            }
            if (slot < 0) break;                       // nothing grindable left
            if (!ejectGrindOutput(grind, result)) break; // output full → stall, keep input

            src.setAmount(src.getAmount() - 1);
            in.setItem(slot, src.getAmount() <= 0 ? null : src);
            ground = true;
        }

        if (ground) {
            grind.getWorld().playSound(grind.getLocation().add(0.5, 0.5, 0.5),
                org.bukkit.Sound.BLOCK_GRINDSTONE_USE, 0.6f, 1f);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fan: consumer that blows entities away from its mount while powered
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayFan(CustomBlockRegistry registry, RotationNetwork network,
                                   RotationConfig config) {
        fanRange = config.fanRange;
        fanMinPush = config.fanMinPush;
        fanMaxPush = config.fanMaxPush;
        fanPushPerSU = config.fanPushPerSU;
        int fanPower = config.getPower("fan", 1);
        String blockId = "rotation:fan";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(config.fanTickInterval)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) ->
                isWrench(event.getPlayer().getInventory().getItemInMainHand())
                    ? wrenchInteract(b, event, network, registry)
                    : debugInteract(b, event, network, registry))
            .onTick(b -> fanTick(b, network))
            .onChunkLoad((b, state) -> {
                storeFacingIfAbsent(b);
                // Rotation axis = the axis the blades spin about = the blow axis.
                // Floor heads store DOWN (blow up → Y); wall heads store N/S (Z) or E/W (X).
                network.addNode(b, blockId, fanAxis(readFacing(b)),
                    RotationNetwork.NodeRole.CONSUMER, fanPower, false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    /** Direction the fan blows: outward from the mounted surface (floor → up, wall → its facing). */
    private static BlockFace blowDirection(Block fan) {
        BlockFace f = readFacing(fan);                 // DOWN (floor) or N/S/E/W (wall)
        if (f == null) return BlockFace.UP;
        return f == BlockFace.DOWN ? BlockFace.UP : f; // floor blows up; walls blow outward
    }

    /** Network/rotation axis implied by a fan's stored facing. */
    private static RotationNetwork.Axis fanAxis(@org.jetbrains.annotations.Nullable BlockFace facing) {
        if (facing == null) return RotationNetwork.Axis.Y;
        return switch (facing) {
            case EAST, WEST -> RotationNetwork.Axis.X;
            case NORTH, SOUTH -> RotationNetwork.Axis.Z;
            default -> RotationNetwork.Axis.Y;         // DOWN/UP (floor)
        };
    }

    /** While powered, push mobs/players/items along a fixed-length beam; strength scales with surplus SU. */
    private static void fanTick(Block fan, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(fan);
        if (!network.isPowered(key)) return;

        int[] stats = network.getNetworkStats(key);
        if (stats == null) return;
        // stats = {supply, demand, count}; surplus headroom = supply - demand (advisory).
        int surplus = Math.max(0, stats[0] - stats[1]);
        double push = Math.min(fanMaxPush, fanMinPush + surplus * fanPushPerSU);

        BlockFace dir = blowDirection(fan);
        org.bukkit.util.Vector unit = dir.getDirection();   // unit-length

        // Beam: a 1-wide column from the fan extending fanRange blocks along `unit`.
        Location center = fan.getLocation().add(0.5, 0.5, 0.5);

        // Airflow feedback: a single directed puff just off the fan, drifting down the beam.
        Location p = center.clone().add(unit.clone().multiply(0.5));
        // count=0 → offset args act as the particle velocity; extra = drift speed.
        fan.getWorld().spawnParticle(Particle.CLOUD, p, 0, unit.getX(), unit.getY(), unit.getZ(), 0.1);

        Location far = center.clone().add(unit.clone().multiply(fanRange));
        org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(center.toVector(), far.toVector());
        // Inflate the two perpendicular axes to ~1 block wide (axis along `unit` stays the beam length).
        box.expand(
            unit.getX() == 0 ? 0.5 : 0,
            unit.getY() == 0 ? 0.5 : 0,
            unit.getZ() == 0 ? 0.5 : 0);

        for (org.bukkit.entity.Entity e : fan.getWorld().getNearbyEntities(box)) {
            // Mobs, players, dropped items only — excludes our own Display block visuals.
            if (!(e instanceof org.bukkit.entity.LivingEntity || e instanceof org.bukkit.entity.Item)) continue;
            org.bukkit.util.Vector v = e.getVelocity();
            double along = v.dot(unit);
            if (along < push) {
                // Nudge the along-beam speed up to `push`; never accelerate past it (avoids runaway).
                e.setVelocity(v.add(unit.clone().multiply(push - along)));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Generator: source, turns off when receiving redstone power
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayGenerator(CustomBlockRegistry registry, RotationNetwork network,
                                            RotationConfig config) {
        int generatorPower = config.getPower("generator", 1);
        String blockId = "rotation:generator";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> {
                boolean rsPowered = b.getBlockPower() > 0;
                String state = registry.getState(b);
                if (state == null) return;
                String axis = state.substring(state.lastIndexOf('_') + 1);
                // Inverted: redstone ON = idle (disabled), OFF = spinning (active)
                String target = rsPowered ? "idle_" + axis : "spinning_" + axis;
                if (!target.equals(state)) {
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
                }
                recalcIfKnown(b, network);
            })
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                return debugInteract(b, event, network, registry);
            })
            .onChunkLoad((b, state) -> {
                boolean spinning = state != null && state.startsWith("spinning_");
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state != null ? state : "spinning_y");
                network.addNode(b, blockId, axis,
                    spinning ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                    spinning ? generatorPower : 0, false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Drill: consumer, breaks block in facing direction with break animation
    // ──────────────────────────────────────────────────────────────────────

    private static final NamespacedKey DRILL_FACING_KEY = new NamespacedKey("rotation", "drill_facing");

    private static final ItemStack NETHERITE_PICK = new ItemStack(Material.NETHERITE_PICKAXE);
    private static final double DRILL_ANIM_RADIUS = 48.0;

    private static Set<Material> drillBlacklist;
    private static int drillTickInterval;
    private static int drillBreakStages;
    private static int grindstoneTickInterval;
    private static int grindstoneMaxBatch;
    private static int fanRange;
    private static double fanMinPush;
    private static double fanMaxPush;
    private static double fanPushPerSU;

    private record DrillState(int progress, Material targetMaterial) {}
    private static final Map<CustomBlockRegistry.LocationKey, DrillState> drillProgress = new HashMap<>();

    private static void overlayDrill(CustomBlockRegistry registry, RotationNetwork network,
                                      RotationConfig config) {
        drillBlacklist = config.drillBlacklist;
        drillTickInterval = config.drillTickInterval;
        drillBreakStages = config.drillBreakStages;
        int drillPower = config.getPower("drill", 1);
        String blockId = "rotation:drill";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .cancelPistons(true)
            .reactsToNeighbors(true)
            .tickInterval(drillTickInterval)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                return debugInteract(b, event, network, registry);
            })
            .onTick(b -> drillTick(b, registry, network))
            .onChunkLoad((b, state) -> {
                storeFacingIfAbsent(b);
                network.addNode(b, blockId, RotationNetwork.axisFromState(state),
                    RotationNetwork.NodeRole.CONSUMER, drillPower, false);
            })
            .onChunkUnload(b -> {
                drillProgress.remove(CustomBlockRegistry.LocationKey.of(b));
                network.removeNode(CustomBlockRegistry.LocationKey.of(b));
            })
            .onBlockRemoved((b, state) -> {
                var k = CustomBlockRegistry.LocationKey.of(b);
                if (drillProgress.remove(k) != null) clearBreakAnimation(b, readFacing(b));
                network.removeNode(k);
            })
            .build());
    }

    private static void drillTick(Block drill, CustomBlockRegistry registry, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(drill);

        if (!network.isPowered(key)) {
            if (drillProgress.remove(key) != null) clearBreakAnimation(drill, readFacing(drill));
            return;
        }

        BlockFace facing = readFacing(drill);
        if (facing == null) return;
        Block target = drill.getRelative(facing);
        Material targetMat = target.getType();

        if (targetMat.isAir()) {
            drillProgress.remove(key);
            return;
        }
        if (target.isLiquid() || targetMat.getHardness() < 0 || drillBlacklist.contains(targetMat)) {
            if (drillProgress.remove(key) != null) clearBreakAnimation(drill, facing);
            return;
        }

        CustomHeadBlock targetType = registry.getTypeFromBlock(target);
        if (targetType != null && !targetType.drillable()) {
            if (drillProgress.remove(key) != null) clearBreakAnimation(drill, facing);
            return;
        }

        DrillState state = drillProgress.get(key);
        int progress = (state != null && state.targetMaterial == targetMat) ? state.progress + 1 : 1;

        if (progress >= drillBreakStages) {
            drillProgress.remove(key);
            if (targetType != null) {
                if (targetType.storage() != null) registry.dropStorage(target);
                ItemStack drop = targetType.createItem(1);
                registry.onBlockRemoved(target, targetType);
                target.getWorld().dropItemNaturally(target.getLocation().add(0.5, 0.5, 0.5), drop);
                target.setType(Material.AIR);
            } else {
                target.breakNaturally(NETHERITE_PICK);
            }
            return;
        }

        drillProgress.put(key, new DrillState(progress, targetMat));
        Location targetLoc = target.getLocation();
        int sourceId = key.hashCode();
        float animProgress = (float) progress / drillBreakStages;
        for (Player p : targetLoc.getNearbyPlayers(DRILL_ANIM_RADIUS)) {
            p.sendBlockDamage(targetLoc, animProgress, sourceId);
        }

        spawnDrillParticles(target, facing.getOppositeFace());
    }

    private static void spawnDrillParticles(Block target, BlockFace contactFace) {
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        center.add(contactFace.getModX() * 0.5, contactFace.getModY() * 0.5, contactFace.getModZ() * 0.5);
        target.getWorld().spawnParticle(
            Particle.BLOCK, center, 4,
            0.25, 0.25, 0.25, 0.0,
            target.getBlockData()
        );
    }

    private static void clearBreakAnimation(Block drill, @org.jetbrains.annotations.Nullable BlockFace facing) {
        if (facing == null) return;
        Location targetLoc = drill.getRelative(facing).getLocation();
        int sourceId = CustomBlockRegistry.LocationKey.of(drill).hashCode();
        for (Player p : targetLoc.getNearbyPlayers(DRILL_ANIM_RADIUS)) {
            p.sendBlockDamage(targetLoc, 0.0f, sourceId);
        }
    }

    private static void storeFacingIfAbsent(Block block) {
        if (!(block.getState() instanceof Skull skull)) return;
        if (skull.getPersistentDataContainer().has(DRILL_FACING_KEY)) return;

        BlockFace facing;
        if (block.getType() == Material.PLAYER_WALL_HEAD
                && block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
            facing = dir.getFacing();
        } else {
            facing = BlockFace.DOWN;
        }
        skull.getPersistentDataContainer().set(DRILL_FACING_KEY, PersistentDataType.STRING, facing.name());
        skull.update();
    }

    private static @org.jetbrains.annotations.Nullable BlockFace readFacing(Block block) {
        if (!(block.getState() instanceof Skull skull)) return null;
        String val = skull.getPersistentDataContainer().get(DRILL_FACING_KEY, PersistentDataType.STRING);
        if (val == null) return null;
        try {
            return BlockFace.valueOf(val);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Windmill: displayItemResolver for custom banner blades
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayWindmillResolver(CustomBlockRegistry registry, String blockId,
                                                  BannerTier tier, RotationNetwork network) {
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .bannerTier(tier)
            .onInteract((b, event) -> {
                if (!isWrench(event.getPlayer().getInventory().getItemInMainHand())) return false;
                if (event.getPlayer().isSneaking()) {
                    // Inspect: show stored direction
                    var key = CustomBlockRegistry.LocationKey.of(b);
                    RotationNetwork.SpinDirection dir = network.readStoredDirection(key);
                    String dirStr = dir == null ? "flexible (default CW)" : dir.toString();
                    event.getPlayer().sendActionBar(
                            Component.text("Windmill direction: " + dirStr, NamedTextColor.GOLD));
                    return true;
                }
                // Toggle direction
                toggleSourceDirection(b, CustomBlockRegistry.LocationKey.of(b),
                        event.getPlayer(), network);
                return true;
            })
            .displayItemResolver((b, suffix) -> {
                // blade_a → 0, blade_b → 1, blade_c → 2, blade_d → 3
                if (suffix == null || !suffix.startsWith("blade_") || suffix.length() < 7) return null;
                int idx = suffix.charAt(6) - 'a';
                if (idx < 0 || idx > 3) return null;
                if (!(b.getState() instanceof org.bukkit.block.Skull skull)) return null;
                byte[] data = skull.getPersistentDataContainer().get(
                        CustomBlockRegistry.BLADE_KEYS[idx],
                        org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
                if (data == null) return null;
                try {
                    return org.bukkit.inventory.ItemStack.deserializeBytes(data);
                } catch (Exception e) {
                    registry.getPlugin().getLogger().warning(
                            "Failed to deserialize blade data for " + blockId + ": " + e.getMessage());
                    return null;
                }
            })
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────

    private static void warn(CustomBlockRegistry registry, String blockId) {
        registry.getPlugin().getLogger().warning("RotationBlocks: block '" + blockId + "' not found — skipping overlay");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Wrench interaction
    // ──────────────────────────────────────────────────────────────────────

    private static boolean wrenchInteract(Block block, org.bukkit.event.player.PlayerInteractEvent event,
                                           RotationNetwork network, CustomBlockRegistry registry) {
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            // Sneak+wrench → inspect only (debug info + direction)
            return debugInteract(block, event, network, registry);
        }
        // Non-sneak wrench on source → toggle direction
        var key = CustomBlockRegistry.LocationKey.of(block);
        var node = network.getNode(key);
        if (node != null && node.role() == RotationNetwork.NodeRole.SOURCE) {
            toggleSourceDirection(block, key, player, network);
            return true;
        }
        // Non-source → just show debug info
        return debugInteract(block, event, network, registry);
    }

    private static void toggleSourceDirection(Block block, CustomBlockRegistry.LocationKey key,
                                               Player player, RotationNetwork network) {
        if (!(block.getState() instanceof Skull skull)) return;
        String val = skull.getPersistentDataContainer().get(
                RotationNetwork.SPIN_DIR_KEY, PersistentDataType.STRING);
        RotationNetwork.SpinDirection oldDir = val == null ? RotationNetwork.SpinDirection.CW
                : "ccw".equals(val) ? RotationNetwork.SpinDirection.CCW : RotationNetwork.SpinDirection.CW;
        RotationNetwork.SpinDirection newDir = oldDir.reversed();

        skull.getPersistentDataContainer().set(
                RotationNetwork.SPIN_DIR_KEY, PersistentDataType.STRING,
                newDir == RotationNetwork.SpinDirection.CW ? "cw" : "ccw");
        skull.update();

        if (network.getNode(key) != null) {
            network.recalculate(key);
        } else {
            network.recalculateAdjacentNetworks(key);
        }

        player.sendActionBar(Component.text("Direction: " + oldDir + " → " + newDir, NamedTextColor.GOLD));
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                org.bukkit.Sound.BLOCK_COPPER_PLACE, 1f, 1.5f);
        block.getWorld().spawnParticle(Particle.WAX_ON,
                block.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3, 0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────────────────────────────

    private static void recalcIfKnown(Block block, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(block);
        if (network.getNode(key) != null) network.recalculate(key);
    }

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
            var dbg = network.getNetworkDebugInfo(key);
            boolean powered = network.isPowered(key);
            RotationNetwork.SpinDirection dir = network.getDirection(key);
            String dirStr = dir != null ? " " + dir : "";
            if (dbg != null) {
                info = state + " | " + dbg.supply() + "/" + dbg.demand() + " SU, "
                     + dbg.blockCount() + " blocks" + dirStr;
                if (dbg.jammed()) {
                    info += " | JAMMED (" + dbg.cwSources() + " CW, " + dbg.ccwSources() + " CCW)";
                } else {
                    info += " | " + (powered ? "POWERED" : "UNPOWERED");
                }
            } else {
                info = state + " | ?" + dirStr;
            }
            color = dbg != null && dbg.jammed() ? NamedTextColor.YELLOW
                    : powered ? NamedTextColor.GREEN : NamedTextColor.RED;
        }
        event.getPlayer().sendActionBar(Component.text(info, color));

        // Highlight network members with particles (jammed: red on sources, green on rest)
        if (node != null) {
            Set<CustomBlockRegistry.LocationKey> members = network.getNetworkMembers(key);
            var dbg = network.getNetworkDebugInfo(key);
            if (members != null) {
                World world = block.getWorld();
                for (var loc : members) {
                    var memberNode = network.getNode(loc);
                    Particle particle = Particle.HAPPY_VILLAGER;
                    if (dbg != null && dbg.jammed() && memberNode != null
                            && memberNode.role() == RotationNetwork.NodeRole.SOURCE) {
                        particle = Particle.ANGRY_VILLAGER;
                    }
                    world.spawnParticle(particle,
                        new Location(world, loc.x() + 0.5, loc.y() + 0.5, loc.z() + 0.5),
                        5, 0.25, 0.25, 0.25, 0);
                }
            }
        }
        return true;
    }
}
