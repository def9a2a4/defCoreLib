package anon.def9a2a4.corelib;

import anon.def9a2a4.corelib.container.ContainerAdapterRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Overlays rotation network callbacks onto YAML-defined blocks.
 * Visual definitions live in rotation-blocks.yml — this class only adds
 * Java behavior (network add/remove/recalculate, debug interact, clutch/reverser redstone).
 */
final class RotationBlocks {

    private RotationBlocks() {}

    // The wrench is declared in custom-items.yml (mech:wrench); identity is the registry
    // block_type PDC, and its recipe auto-registers from YAML.
    static boolean isWrench(ItemStack item) {
        return "mech:wrench".equals(CustomBlockRegistry.getItemTypeId(item));
    }

    /** Seed Oil burns this many ticks per unit in an engine (20s). */
    static final int SEED_OIL_FUEL_TICKS = 400;

    /**
     * Engine fuel value of one item, in engine-ticks (1 engine-tick = 1s = 20 game ticks), or
     * {@code <= 0} when the item is not fuel. One source of truth for the material table plus the
     * Seed Oil special case (a custom item on a magma-cream skin — recognized by PDC id so real
     * magma cream isn't fuel). Used by the static engine overlay and by engines riding a mechanism.
     */
    static int fuelTicksFor(ItemStack item, EngineFuelManager fuelManager) {
        int fv = fuelManager.getFuelValue(item.getType());
        if (fv <= 0 && "mech:seed_oil".equals(CustomBlockRegistry.getItemTypeId(item))) {
            fv = SEED_OIL_FUEL_TICKS;
        }
        return fv;
    }

    /** Remove the first fuel item from {@code storage} and return its engine-tick value, or 0 when
     *  the inventory holds no fuel. Shared by the static engine overlay and mechanism engines. */
    static int consumeOneFuelItem(Inventory storage, EngineFuelManager fuelManager) {
        for (int i = 0; i < storage.getSize(); i++) {
            ItemStack it = storage.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            int fv = fuelTicksFor(it, fuelManager);
            if (fv > 0) {
                it.setAmount(it.getAmount() - 1);
                storage.setItem(i, it.getAmount() <= 0 ? null : it);
                return fv;
            }
        }
        return 0;
    }

    /**
     * Bake dough into bread. This is a vanilla output from a custom ingredient, which the
     * type-owned YAML recipe system can't express (a YAML recipe's result is always the
     * owning custom type's item), so it's registered here. Furnace + smoker, reload-safe.
     * Call after {@code registry.finalizeLoading()} so mech:dough exists.
     */
    static void registerBakingRecipes(org.bukkit.plugin.Plugin plugin, CustomBlockRegistry registry) {
        CustomHeadBlock dough = registry.getType("mech:dough");
        if (dough == null) {
            plugin.getLogger().warning("registerBakingRecipes: mech:dough not found — skipping dough→bread");
            return;
        }
        var doughChoice = new org.bukkit.inventory.RecipeChoice.ExactChoice(dough.createItem(1));
        ItemStack bread = new ItemStack(Material.BREAD);

        NamespacedKey furnaceKey = new NamespacedKey(plugin, "mech_dough_bake");
        org.bukkit.Bukkit.removeRecipe(furnaceKey);
        org.bukkit.Bukkit.addRecipe(new org.bukkit.inventory.FurnaceRecipe(furnaceKey, bread, doughChoice, 0.1f, 200));

        NamespacedKey smokerKey = new NamespacedKey(plugin, "mech_dough_smoke");
        org.bukkit.Bukkit.removeRecipe(smokerKey);
        org.bukkit.Bukkit.addRecipe(new org.bukkit.inventory.SmokingRecipe(smokerKey, bread, doughChoice, 0.1f, 100));
    }

    /**
     * Craft iron/copper lanterns from Seed Oil — vanilla outputs, so registered here with an
     * ExactChoice on the custom oil plus a string wick (seed oil + string replace the torch):
     * <pre>N S N / N O N / N N N</pre> N=nugget, O=seed oil, S=string. Reload-safe. Copper
     * lantern/nugget are vanilla only since the Copper Age update (Java 1.21.9) — resolved via
     * matchMaterial so this compiles and skips gracefully on older APIs.
     */
    static void registerSeedOilRecipes(org.bukkit.plugin.Plugin plugin, CustomBlockRegistry registry) {
        CustomHeadBlock oil = registry.getType("mech:seed_oil");
        if (oil == null) {
            plugin.getLogger().warning("registerSeedOilRecipes: mech:seed_oil not found — skipping lantern recipes");
            return;
        }
        var oilChoice = new org.bukkit.inventory.RecipeChoice.ExactChoice(oil.createItem(1));
        registerLanternRecipe(plugin, "mech_iron_lantern", Material.LANTERN, Material.IRON_NUGGET, oilChoice);

        Material copperLantern = Material.matchMaterial("COPPER_LANTERN");
        Material copperNugget = Material.matchMaterial("COPPER_NUGGET");
        if (copperLantern != null && copperNugget != null) {
            registerLanternRecipe(plugin, "mech_copper_lantern", copperLantern, copperNugget, oilChoice);
        } else {
            plugin.getLogger().info("Seed-oil copper lantern skipped (COPPER_LANTERN/COPPER_NUGGET not in this MC version).");
        }
    }

    private static void registerLanternRecipe(org.bukkit.plugin.Plugin plugin, String keyName,
                                              Material result, Material nugget,
                                              org.bukkit.inventory.RecipeChoice oil) {
        NamespacedKey key = new NamespacedKey(plugin, keyName);
        org.bukkit.Bukkit.removeRecipe(key);
        var recipe = new org.bukkit.inventory.ShapedRecipe(key, new ItemStack(result));
        recipe.shape("NSN", "NON", "NNN");
        recipe.setIngredient('N', nugget);
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('O', oil);
        org.bukkit.Bukkit.addRecipe(recipe);
    }

    static void register(CustomBlockRegistry registry, RotationNetwork network,
                         EngineFuelManager fuelManager, MachineRecipes millRecipes,
                         MachineRecipes pressRecipes, RotationConfig config) {
        // Overlay callbacks onto YAML-loaded blocks
        overlayStandard(registry, network, "mech:shaft",   RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        overlayStandard(registry, network, "mech:gear",    RotationNetwork.NodeRole.TRANSMITTER, 0, true);
        // Reverser: a plain along-axis transmitter; reversal lives in RotationNetwork.getConnections
        // (keyed off the "mech:reverser" id + live redstone + the output side captured at addNode).
        // It sets cancel_pistons in rotation-blocks.yml so a shove can't relocate it out from under
        // that cached facing (its flip side is fixed at placement, not re-read live).
        overlayStandard(registry, network, "mech:reverser", RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        // Chain pulley: shaft-like transmitter + distance chain links (see ChainPulley). Instance holds
        // per-player link selection state, so it outlives register() via the callback captures.
        new ChainPulley(registry, network, config.chainPulleyMaxDistance).register();
        overlayClutch(registry, network);
        overlayWaterWheel(registry, network, config);
        overlayEngine(registry, network, fuelManager, config);
        overlayMillstone(registry, network, millRecipes, config);
        overlayPress(registry, network, pressRecipes, config);
        overlayPlacer(registry, network, config);
        overlayRedstoneMotor(registry, network, config);
        // Redstone Dynamo: a barrel-backed sensor (own class — holds per-block level state + a mode menu).
        new RedstoneDynamo(registry, network, config).register();
        overlayDrill(registry, network, config);
        overlayFan(registry, network, config);
        overlaySuctionHopper(registry, network, config);

        // Passive sources — detected at network boundary, no callbacks needed
        network.registerPassiveSource("mech:windmill", config.getPower("windmill", 1));
        network.registerPassiveSource("mech:large_windmill", config.getPower("large_windmill", 5));
        network.registerPassiveSource("mech:huge_windmill", config.getPower("huge_windmill", 15));

        // Windmill blade resolver — allows crafted banners to replace default WHITE_BANNER.
        // Each tier is craftable only with the matching banner tier (enforced in
        // CoreLibPlugin.captureBannerIngredients via the block's bannerTier).
        overlayWindmillResolver(registry, "mech:windmill", BannerTier.NORMAL, network);
        overlayWindmillResolver(registry, "mech:large_windmill", BannerTier.LARGE, network);
        overlayWindmillResolver(registry, "mech:huge_windmill", BannerTier.HUGE, network);
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
        // Piston behavior (cancel vs. break) is YAML-driven per block (cancel_pistons /
        // break_on_piston) and carried through toBuilder(); the overlay leaves it untouched.
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (!isWrench(event.getPlayer().getInventory().getItemInMainHand())) return false;
                // Non-sneak wrench on a shaft toggles encased head ↔ bare chain (purely visual);
                // sneak+wrench (and every other transmitter) keeps the inspect/debug behavior.
                if ("mech:shaft".equals(blockId) && !event.getPlayer().isSneaking()) {
                    return toggleShaftEncasing(b, event.getPlayer(), network, registry);
                }
                return wrenchInteract(b, event, network, registry);
            })
            .onChunkLoad((b, state) -> network.addNode(b, blockId,
                RotationNetwork.axisFromState(state), role, power, gearLike))
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());

        // Enable bare-shaft support once the shaft type is registered: register CHAIN→shaft resolution
        // and a revert-to-head handler (piston/mechanism moves). The shaft is skull-first (it places as
        // an encased head and the wrench converts it to a bare CHAIN), so it declares no base_block and
        // registers its bare material explicitly here.
        if ("mech:shaft".equals(blockId)) {
            CustomHeadBlock shaftType = registry.getType(blockId);
            if (shaftType != null) {
                registry.registerBareBlock(shaftType, Material.CHAIN, b -> makeShaftEncased(b, network, registry));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Clutch: same as standard + redstone lock/unlock in onNeighborChange
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayClutch(CustomBlockRegistry registry, RotationNetwork network) {
        String blockId = "mech:clutch";
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
                return false;
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

    /** Water-wheel paddles: the 8 slabs (ring, clockwise from top) map to paddle_0..7, each
     *  rendered as its matching plank and listed in "Paddles:" lore. Slot 4 (bearing) is skipped. */
    private static final IngredientCapture PADDLE_CAPTURE = new IngredientCapture(
            java.util.Map.of(1, "paddle_0", 2, "paddle_1", 5, "paddle_2", 8, "paddle_3",
                             7, "paddle_4", 6, "paddle_5", 3, "paddle_6", 0, "paddle_7"),
            slab -> new org.bukkit.inventory.ItemStack(IngredientCapture.plankForSlab(slab.getType())),
            IngredientCapture::describeMaterial,
            "Paddles:", false);

    private static void overlayWaterWheel(CustomBlockRegistry registry, RotationNetwork network,
                                              RotationConfig config) {
        int waterWheelPower = config.getPower("water_wheel", 2);
        String blockId = "mech:water_wheel";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            // Poll on a tick: neighbor callbacks miss water-level changes a couple cells away,
            // and flow direction needs re-sampling as the current shifts.
            .tickInterval(10)
            .onNeighborChange((b, face) -> evaluateWaterWheel(b, network, registry, waterWheelPower))
            .onTick(b -> evaluateWaterWheel(b, network, registry, waterWheelPower))
            // Each paddle renders the plank matching the slab it was crafted with; captured onto
            // the skull PDC per-tag and listed in "Paddles:" lore (see IngredientCapture).
            .ingredientCapture(PADDLE_CAPTURE)
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand())) {
                    if (event.getPlayer().isSneaking())
                        return debugInteract(b, event, network, registry);
                    event.getPlayer().sendActionBar(
                            Component.text("Water wheels are always flexible", NamedTextColor.AQUA));
                    return true;
                }
                return false;
            })
            .onChunkLoad((b, state) -> {
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
                // Trust the water, not the saved state: a wheel captured mid-spin by a mechanism
                // and disassembled onto dry land keeps its spinning_* state — seeding SOURCE from
                // it would phantom-power neighbors until the first evaluateWaterWheel corrects.
                boolean spinning = state.startsWith("spinning_")
                        && Math.abs(flowSignal(b, axis)) > WATER_FLOW_MIN;
                network.addNode(b, blockId, axis,
                    spinning ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                    spinning ? waterWheelPower : 0, false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    /** |flowSignal| above this = a real current is driving the wheel (signal is ~0 or ~1). */
    private static final double WATER_FLOW_MIN = 0.1;

    /**
     * Evaluate a water wheel each tick / on neighbor change: spin only when flowing water
     * pushes its rim, in the direction of that push. Shared by onTick and onNeighborChange so
     * the two never drift. Ordered so a change costs exactly one network recalc (steady = zero).
     */
    private static void evaluateWaterWheel(Block b, RotationNetwork network,
                                           CustomBlockRegistry registry, int power) {
        String state = registry.getState(b);
        if (state == null) return;
        RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
        String axisStr = state.substring(state.lastIndexOf('_') + 1);
        var key = CustomBlockRegistry.LocationKey.of(b);

        double signal = flowSignal(b, axis);
        boolean spinning = Math.abs(signal) > WATER_FLOW_MIN;
        String target = (spinning ? "spinning_" : "idle_") + axisStr;
        boolean stateChanged = !target.equals(state);

        // Write the spin direction to the PDC FIRST, so the recalc that addNode triggers below
        // (or the targeted recalc) anchors on the fresh direction. Set unconditionally when
        // (re)entering spin so the node is never added with a stale/default direction.
        // Flow + rotation axis are world-absolute, so this sign is facing-independent.
        // NOTE: CW = positive rotation about the YAML axis; if a wheel spins against its
        // current, swap the two SpinDirection values below.
        boolean dirChanged = false;
        if (spinning && b.getState() instanceof Skull skull) {
            RotationNetwork.SpinDirection desired = signal > 0
                    ? RotationNetwork.SpinDirection.CW : RotationNetwork.SpinDirection.CCW;
            boolean enteringSpin = !state.startsWith("spinning_");
            if (enteringSpin || network.readStoredDirection(key) != desired) {
                skull.getPersistentDataContainer().set(RotationNetwork.SPIN_DIR_KEY,
                        PersistentDataType.STRING,
                        desired == RotationNetwork.SpinDirection.CW ? "cw" : "ccw");
                skull.update();
                dirChanged = true;
            }
        }

        if (stateChanged) {
            registry.setState(b, target);
            CustomHeadBlock type = registry.getTypeFromBlock(b);
            if (type != null) registry.applyConfig(b, type, target, 0);
            network.removeNode(key);
            network.addNode(b, "mech:water_wheel", axis,
                spinning ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                spinning ? power : 0, false); // addNode recalcs with the fresh direction
            return;
        }
        if (dirChanged) recalcIfKnown(b, network);
    }

    /**
     * Flow "signal": flow direction turned into a spin sign + strength. Over-shot sampling —
     * the block ABOVE plus the two tangential SIDE neighbors (drop DOWN, whose symmetric torque
     * would cancel a uniform current). Each water neighbor contributes the tangential push
     * (r × flow)·axle, r = neighbor unit offset, flow = Paper's ground-truth computeFlowDirection.
     * Still/source pools flow ~0 → signal ~0 → idle. Plane: XY for a Z-axle wheel, YZ for X-axle.
     */
    private static double flowSignal(Block block, RotationNetwork.Axis axis) {
        org.bukkit.World world = block.getWorld();
        boolean xAxle = axis == RotationNetwork.Axis.X;
        org.bukkit.block.BlockFace[] faces = xAxle
                ? new org.bukkit.block.BlockFace[]{ org.bukkit.block.BlockFace.UP,
                    org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH }
                : new org.bukkit.block.BlockFace[]{ org.bukkit.block.BlockFace.UP,
                    org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST };
        double signal = 0;
        for (var f : faces) {
            Block nb = block.getRelative(f);
            // Skip a neighbor whose chunk isn't loaded — getFluidData would sync-load it.
            if (!world.isChunkLoaded(nb.getX() >> 4, nb.getZ() >> 4)) continue;
            org.bukkit.Location loc = nb.getLocation();
            io.papermc.paper.block.fluid.FluidData fd = world.getFluidData(loc);
            org.bukkit.Fluid type = fd.getFluidType();
            if (type != org.bukkit.Fluid.WATER && type != org.bukkit.Fluid.FLOWING_WATER) continue;
            org.bukkit.util.Vector v = fd.computeFlowDirection(loc);
            org.bukkit.util.Vector r = f.getDirection();
            signal += xAxle
                    ? r.getY() * v.getZ() - r.getZ() * v.getY()   // (r × v)·x̂
                    : r.getX() * v.getY() - r.getY() * v.getX();   // (r × v)·ẑ
        }
        return signal;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Engine: source with fuel management
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayEngine(CustomBlockRegistry registry, RotationNetwork network,
                                      EngineFuelManager fuelManager, RotationConfig config) {
        int enginePower = config.getPower("engine", 5);
        String blockId = "mech:engine";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(20)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                Inventory inv = registry.getOrCreateStorage(b);
                if (inv == null) return false;
                var key = CustomBlockRegistry.LocationKey.of(b);
                int fuelTicks = fuelManager.getFuel(key);
                int seconds = fuelTicks;
                var view = event.getPlayer().openInventory(inv);
                if (view != null) {
                    view.setTitle("Engine - " + seconds + "s fuel");
                }
                return true;
            })
            .onTick(b -> {
                String state = registry.getState(b);
                var key = CustomBlockRegistry.LocationKey.of(b);

                // Auto-consume fuel from internal storage when the fuel counter is empty
                if (fuelManager.getFuel(key) <= 0) {
                    Inventory storage = registry.getOrCreateStorage(b);
                    if (storage != null) {
                        int fv = consumeOneFuelItem(storage, fuelManager);
                        if (fv > 0) fuelManager.addFuel(key, fv);
                    }
                }

                // Auto-start: an idle engine that has fuel begins running
                if (state != null && state.startsWith("idle_") && fuelManager.getFuel(key) > 0) {
                    String axis = state.substring(state.lastIndexOf('_') + 1);
                    String target = "running_" + axis;
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
                    network.removeNode(key);
                    network.addNode(b, blockId, RotationNetwork.axisFromState(target),
                        RotationNetwork.NodeRole.SOURCE, enginePower, false);
                    return;
                }
                if (state == null || !state.startsWith("running_")) return;
                int remaining = fuelManager.tick(key);
                if (remaining <= 0) {
                    // Out of fuel — transition to idle
                    String axis = state.substring(state.lastIndexOf('_') + 1);
                    String target = "idle_" + axis;
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
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
    // Millstone: consumer with interact-based grinding
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Declarative description of a rotation CONSUMER machine. The shared
     * {@link #overlayConsumerMachine} builds the common skeleton from this.
     *
     * @param axis      rotation/network axis given the block (constant, or derived from facing).
     *                  Ignored (nominal) when {@code omniExcludedFace} is set.
     * @param tick      per-cycle behavior while loaded (the machine's work)
     * @param interact  optional action for a non-wrench right-click; return {@code null} to fall
     *                  through to the default network-debug readout
     * @param omniExcludedFace  decided <b>per block</b>: when the function is non-null and returns a
     *                  face for a given block, that block is an <b>omni consumer</b> (powered from the
     *                  first aligned shaft on any face) with the returned face excluded ({@code b -> null}
     *                  for the whole function, or a null <i>result</i>, means that block is an ordinary
     *                  single-axis consumer using {@code axis}). Lets one block type be omni in some
     *                  orientations and single-axis in others (e.g. the placer: omni only on a ceiling).
     * @param playerHeadStates  optional state names that must render as a floating floor PLAYER_HEAD
     *                  (the same core hook pipes uses for vertical orientations); null for none.
     */
    private record ConsumerSpec(
            String blockId,
            Function<Block, RotationNetwork.Axis> axis,
            int power,
            int tickInterval,
            Consumer<Block> tick,
            @org.jetbrains.annotations.Nullable
            BiFunction<Block, org.bukkit.event.player.PlayerInteractEvent, Boolean> interact,
            @org.jetbrains.annotations.Nullable
            Function<Block, BlockFace> omniExcludedFace,
            @org.jetbrains.annotations.Nullable
            String[] playerHeadStates) {}

    /**
     * Shared overlay for rotation CONSUMER machines (millstone, fan, press, …): the
     * common builder skeleton — not-drillable, neighbor recalc, wrench-first interact,
     * ticking, and CONSUMER node add/remove over the chunk/break lifecycle. Per-machine
     * behavior comes entirely from the {@link ConsumerSpec}. (The drill keeps its own
     * overlay: it adds piston cancellation + break-animation cleanup.)
     */
    private static void overlayConsumerMachine(CustomBlockRegistry registry, RotationNetwork network,
                                               ConsumerSpec spec) {
        CustomHeadBlock block = registry.getType(spec.blockId());
        if (block == null) { warn(registry, spec.blockId()); return; }
        CustomHeadBlock.Builder builder = block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(spec.tickInterval())
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                if (spec.interact() != null) {
                    Boolean handled = spec.interact().apply(b, event);
                    if (handled != null) return handled;
                }
                return false;
            })
            .onTick(spec.tick())
            .onChunkLoad((b, state) -> {
                storeFacingIfAbsent(b, state);
                // Omni is decided per block from the face function's result: a non-null face → omni
                // (excluding it); a null result → ordinary single-axis consumer on spec.axis().
                BlockFace omniEx = spec.omniExcludedFace() != null ? spec.omniExcludedFace().apply(b) : null;
                if (omniEx != null) {
                    network.addNode(b, spec.blockId(), spec.axis().apply(b),
                        RotationNetwork.NodeRole.CONSUMER, spec.power(), false, true, omniEx);
                } else {
                    network.addNode(b, spec.blockId(), spec.axis().apply(b),
                        RotationNetwork.NodeRole.CONSUMER, spec.power(), false);
                }
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)));
        if (spec.playerHeadStates() != null) builder.playerHeadStates(spec.playerHeadStates());
        registry.register(builder.build());
    }

    private static void overlayMillstone(CustomBlockRegistry registry, RotationNetwork network,
                                         MachineRecipes millRecipes, RotationConfig config) {
        millstoneTickInterval = config.millstoneTickInterval;
        millstoneMaxBatch = config.millstoneMaxBatch;
        // Wall-mounted, powered from the top: rotation axis is Y (a shaft above drives it).
        // Host container is read separately via the stored wall-head facing.
        overlayConsumerMachine(registry, network, new ConsumerSpec(
            "mech:millstone",
            b -> RotationNetwork.Axis.Y,
            config.getPower("millstone", 1),
            millstoneTickInterval,
            b -> processingMachineTick(b, network, millRecipes, registry,
                millstoneMaxBatch, org.bukkit.Sound.BLOCK_GRINDSTONE_USE),
            null, null, null));
    }

    private static void overlayPress(CustomBlockRegistry registry, RotationNetwork network,
                                     MachineRecipes pressRecipes, RotationConfig config) {
        int maxBatch = config.pressMaxBatch;
        // Same processing geometry as the millstone: wall-mounted, powered from the top (Y),
        // host container behind, outputs ejected below. No manual fallback — automation only.
        overlayConsumerMachine(registry, network, new ConsumerSpec(
            "mech:press",
            b -> RotationNetwork.Axis.Y,
            config.getPower("press", 1),
            config.pressTickInterval,
            b -> processingMachineTick(b, network, pressRecipes, registry,
                maxBatch, org.bukkit.Sound.BLOCK_ANVIL_USE),
            null, null, null));
    }

    private static void overlayPlacer(CustomBlockRegistry registry, RotationNetwork network,
                                      RotationConfig config) {
        // Wall-mounted, powered from the top (Y): a shaft above drives it, exactly like the
        // millstone. Pulls block items from the host container behind and places them into the
        // cell in front (the wall-head facing direction), one per cycle. On a CEILING the wall head
        // is instead a floating PLAYER_HEAD facing DOWN: it pulls from the container above and places
        // into the cell below (placerTick already handles facing==DOWN), and — since its top cell is
        // now storage, not a shaft — it draws power omni-style from any side (exclude UP). Wall
        // placements stay top-shaft (non-omni Axis.Y): the excluded-face function returns null there.
        overlayConsumerMachine(registry, network, new ConsumerSpec(
            "mech:placer",
            b -> RotationNetwork.Axis.Y,
            config.getPower("placer", 1),
            config.placerTickInterval,
            b -> placerTick(b, registry, network),
            null,
            b -> placerOmniExcludedFace(readFacing(b)),
            new String[]{"idle_ceiling", "spinning_ceiling"}));
    }

    /**
     * Place one vanilla block per cycle: while powered, pull the first placeable vanilla block
     * from the host container behind and set it into the (air/replaceable) cell in front. Custom
     * plugin head-blocks and non-block items are skipped. {@code setType} runs with physics.
     */
    private static void placerTick(Block placer, CustomBlockRegistry registry, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(placer);
        if (!network.isPowered(key)) return;

        pullFromAdjacentContainer(placer);

        BlockFace facing = readFacing(placer);
        if (facing == null) return;
        Inventory host = hostContainer(placer);
        if (host == null) return;
        placerEffect(placer.getRelative(facing), host);
    }

    /**
     * One placer work-step: set the first placeable vanilla block from {@code source} into a
     * replaceable {@code target} cell (with sound + particles). Custom plugin head-blocks and
     * non-block items are skipped; {@code setType} runs with physics. The caller owns the powered
     * gate, the target cell, and the source inventory — the static path ({@link #placerTick})
     * passes the facing cell + host storage; a placer riding a mechanism
     * ({@code MechanismRotationDriver}) passes its live facing cell + travelling storage.
     *
     * @return true when a block was placed
     */
    static boolean placerEffect(Block target, Inventory source) {
        if (!target.isReplaceable()) return false; // front occupied → idle

        for (int i = 0; i < source.getSize(); i++) {
            ItemStack it = source.getItem(i);
            if (it == null) continue;
            Material m = it.getType();
            if (!m.isBlock() || m.isAir()) continue;
            if (CustomBlockRegistry.getItemTypeId(it) != null) continue; // skip custom plugin blocks

            target.setType(m);
            it.setAmount(it.getAmount() - 1);
            source.setItem(i, it.getAmount() <= 0 ? null : it);

            var placeSound = target.getBlockSoundGroup().getPlaceSound();
            target.getWorld().playSound(target.getLocation().add(0.5, 0.5, 0.5), placeSound, 1f, 1f);
            target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0.5, 0.5, 0.5),
                6, 0.25, 0.25, 0.25, 0.0, target.getBlockData());
            return true;
        }
        return false;
    }

    /** Internal storage inventory of a machine, or null if the block has no storage configured. */
    private static @org.jetbrains.annotations.Nullable Inventory hostContainer(Block machine) {
        return CoreLibPlugin.getInstance().getRegistry().getOrCreateStorage(machine);
    }

    /** Pull items from the vanilla container behind a wall-mounted machine into its internal storage. */
    private static void pullFromAdjacentContainer(Block machine) {
        BlockFace facing = readFacing(machine);
        if (facing == null) return;
        Inventory internal = CoreLibPlugin.getInstance().getRegistry().getOrCreateStorage(machine);
        if (internal == null) return;
        Block host = machine.getRelative(facing.getOppositeFace());
        // Gateway resolution: null for non-containers AND locked plugin-owned ones (e.g. a dynamo).
        Container c = ContainerAdapterRegistry.findVanillaContainer(host);
        if (c == null) return;
        pullOne(c.getInventory(), internal);
    }

    /**
     * Move ONE item from {@code source} into {@code dest} (first slot that fully fits). The
     * world-agnostic core of {@link #pullFromAdjacentContainer} — machines riding a mechanism
     * feed from the travelling inventory of the neighboring mechanism block through this.
     *
     * @return true when an item moved
     */
    static boolean pullOne(Inventory source, Inventory dest) {
        for (int i = 0; i < source.getSize(); i++) {
            ItemStack item = source.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            ItemStack single = item.clone();
            single.setAmount(1);
            var leftover = dest.addItem(single);
            if (leftover.isEmpty()) {
                item.setAmount(item.getAmount() - 1);
                source.setItem(i, item.getAmount() <= 0 ? null : item);
                return true;
            }
        }
        return false;
    }

    /**
     * Deliver all outputs below the machine. Into the container directly below **atomically**
     * (all-or-nothing, so a multi-output recipe never half-fills a container that has room for
     * only some outputs), else drop them below. Returns false (stall — caller keeps the input)
     * only when a container below can't hold every output.
     */
    private static boolean ejectOutputs(Block machine, List<ItemStack> outputs) {
        if (outputs.isEmpty()) return true;
        Block below = machine.getRelative(BlockFace.DOWN);
        // Gateway resolution: a locked container (e.g. a dynamo) resolves to null → outputs fall
        // through to the MachineEjectEvent/drop path below instead of entering it.
        Container oc = ContainerAdapterRegistry.findVanillaContainer(below);
        if (oc != null) {
            Inventory inv = oc.getInventory();
            ItemStack[] snapshot = java.util.Arrays.stream(inv.getContents())
                .map(s -> s == null ? null : s.clone()).toArray(ItemStack[]::new);
            for (ItemStack out : outputs) {
                if (!inv.addItem(out).isEmpty()) { // didn't fully fit → roll back, stall
                    inv.setContents(snapshot);
                    return false;
                }
            }
            return true;
        }
        MachineEjectEvent event = new MachineEjectEvent(machine, BlockFace.DOWN, outputs);
        Bukkit.getPluginManager().callEvent(event);
        return switch (event.getResult()) {
            case HANDLED -> true;
            case STALL   -> false;
            case DEFAULT -> {
                for (ItemStack out : outputs)
                    machine.getWorld().dropItem(machine.getLocation().add(0.5, 0.0, 0.5), out);
                yield true;
            }
        };
    }

    /** The empty container an output of this material is bottled into, or null if none is needed. */
    private static @org.jetbrains.annotations.Nullable Material emptyContainerFor(Material out) {
        return switch (out) {
            case HONEY_BOTTLE, POTION, SPLASH_POTION, LINGERING_POTION -> Material.GLASS_BOTTLE;
            default -> null; // (extensible later: MILK_BUCKET/WATER_BUCKET/… → BUCKET)
        };
    }

    private static int countOf(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == m) n += it.getAmount();
        }
        return n;
    }

    private static void removeCount(Inventory inv, Material m, int n) {
        for (int i = 0; i < inv.getSize() && n > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != m) continue;
            int take = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - take);
            inv.setItem(i, it.getAmount() <= 0 ? null : it);
            n -= take;
        }
    }

    /**
     * Shared container-processing loop for rotation consumer machines (millstone, press, …):
     * while powered, pull recipe inputs from the host container behind and eject results below,
     * a batch per cycle sized by surplus power. Inputs are consumed only after outputs are delivered
     * (stall-safe). Reused by every machine via a recipe map + sound + batch cap.
     */
    private static void processingMachineTick(Block machine, RotationNetwork network,
            MachineRecipes recipes, CustomBlockRegistry registry, int maxBatch, org.bukkit.Sound sound) {
        var key = CustomBlockRegistry.LocationKey.of(machine);
        if (!network.isPowered(key)) return;

        pullFromAdjacentContainer(machine);
        Inventory in = hostContainer(machine);
        if (in == null) return;

        int[] stats = network.getNetworkStats(key);
        if (stats == null) return;
        // stats = {supply, demand, count}; surplus headroom = supply - demand.
        int batch = Math.min(maxBatch, Math.max(1, stats[0] - stats[1]));

        if (processingEffect(in, batch, recipes, registry, outputs -> ejectOutputs(machine, outputs))) {
            machine.getWorld().playSound(machine.getLocation().add(0.5, 0.5, 0.5), sound, 0.6f, 1f);
        }
    }

    /**
     * The recipe-processing core of {@link #processingMachineTick}: up to {@code batch} recipe
     * cycles against {@code in}, delivering each cycle's outputs through {@code eject} (return
     * false = output full → stall; inputs are consumed only after delivery). World-agnostic — the
     * caller owns the powered gate, input refill, batch sizing, eject destination, and sound, so
     * millstones/presses riding a mechanism run the identical loop against travelling inventories
     * ({@code MechanismRotationDriver}).
     *
     * @return true when at least one cycle processed
     */
    static boolean processingEffect(Inventory in, int batch, MachineRecipes recipes,
            CustomBlockRegistry registry, java.util.function.Function<List<ItemStack>, Boolean> eject) {
        boolean processed = false;
        for (int done = 0; done < batch; done++) {
            MachineRecipes.Recipe recipe = null;
            for (int i = 0; i < in.getSize(); i++) {
                ItemStack it = in.getItem(i);
                if (it == null || it.getType().isAir()) continue;
                MachineRecipes.Recipe r = recipes.match(it.getType());
                if (r != null && countOf(in, it.getType()) >= r.inputAmount()) { recipe = r; break; }
            }
            if (recipe == null) break;                              // nothing processable left

            List<ItemStack> outputs = recipes.roll(recipe, registry);

            // Bottled outputs (juices, …) consume an empty container from the host container.
            Map<Material, Integer> empties = new HashMap<>();
            for (ItemStack out : outputs) {
                Material empty = emptyContainerFor(out.getType());
                if (empty != null) empties.merge(empty, out.getAmount(), Integer::sum);
            }
            boolean missingEmpty = false;
            for (var e : empties.entrySet()) {
                if (countOf(in, e.getKey()) < e.getValue()) { missingEmpty = true; break; }
            }
            if (missingEmpty) break;                                // no container to bottle into → stall

            if (!outputs.isEmpty() && !eject.apply(outputs)) break; // output full → stall

            removeCount(in, recipe.input(), recipe.inputAmount());
            for (var e : empties.entrySet()) removeCount(in, e.getKey(), e.getValue());
            processed = true;
        }
        return processed;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fan: consumer that blows entities away from its mount while powered
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayFan(CustomBlockRegistry registry, RotationNetwork network,
                                   RotationConfig config) {
        fanRange = config.fanRange;
        fanMinPush = config.fanMinPush;
        fanMaxPush = config.fanMaxPush;
        fanPushPerPower = config.fanPushPerPower;
        // Rotation axis = the blade spin axis = the blow axis: floor heads store DOWN (blow up → Y),
        // wall heads store N/S (Z) or E/W (X). No host container, so a plain wrench/debug interact.
        overlayConsumerMachine(registry, network, new ConsumerSpec(
            "mech:fan",
            b -> fanAxis(readFacing(b)),
            config.getPower("fan", 1),
            config.fanTickInterval,
            b -> fanTick(b, network),
            null, null, null));
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

    /** While powered, push mobs/players/items along a fixed-length beam; strength scales with surplus power. */
    private static void fanTick(Block fan, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(fan);
        if (!network.isPowered(key)) return;

        int[] stats = network.getNetworkStats(key);
        if (stats == null) return;
        // stats = {supply, demand, count}; surplus headroom = supply - demand (advisory).
        int surplus = Math.max(0, stats[0] - stats[1]);

        fanEffect(fan.getWorld(), fan.getLocation().add(0.5, 0.5, 0.5), blowDirection(fan), surplus,
            fanRange, fanMinPush, fanMaxPush, fanPushPerPower);
    }

    /**
     * One fan work-step at an explicit position: push mobs/players/items along a {@code range}-long
     * beam from {@code center} toward {@code blowDir}, with a directed airflow puff. Push =
     * {@code min(maxPush, minPush + surplus·pushPerPower)}. Fully world-local — the caller owns the
     * powered gate and surplus, so fans riding a mechanism blow at their live position
     * ({@code MechanismRotationDriver}).
     */
    static void fanEffect(World world, Location center, BlockFace blowDir, int surplus,
                          int range, double minPush, double maxPush, double pushPerPower) {
        double push = Math.min(maxPush, minPush + surplus * pushPerPower);
        org.bukkit.util.Vector unit = blowDir.getDirection();   // unit-length

        // Airflow feedback: a single directed puff just off the fan, drifting down the beam.
        Location p = center.clone().add(unit.clone().multiply(0.5));
        // count=0 → offset args act as the particle velocity; extra = drift speed.
        world.spawnParticle(Particle.CLOUD, p, 0, unit.getX(), unit.getY(), unit.getZ(), 0.1);

        Location far = center.clone().add(unit.clone().multiply(range));
        org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(center.toVector(), far.toVector());
        // Inflate the two perpendicular axes to ~1 block wide (axis along `unit` stays the beam length).
        box.expand(
            unit.getX() == 0 ? 0.5 : 0,
            unit.getY() == 0 ? 0.5 : 0,
            unit.getZ() == 0 ? 0.5 : 0);

        for (org.bukkit.entity.Entity e : world.getNearbyEntities(box)) {
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
    // Suction Hopper: consumer that pulls dropped items in and feeds the mount
    // ──────────────────────────────────────────────────────────────────────

    /** Face buried in the mounting surface (floor → DOWN, wall → into the wall). */
    private static BlockFace mountFace(Block b) { return blowDirection(b).getOppositeFace(); }

    private static void overlaySuctionHopper(CustomBlockRegistry registry, RotationNetwork network,
                                             RotationConfig config) {
        suctionTickInterval = config.suctionTickInterval;
        suctionPullRange    = config.suctionPullRange;
        suctionPullStrength = config.suctionPullStrength;
        // Omni consumer: the network powers it from the first aligned shaft on any face except its
        // mount (chosen live — see RotationNetwork's omni handling). Otherwise a fan+hopper: 5-slot
        // storage, pulls dropped items in, feeds the mounted container/pipe.
        overlayConsumerMachine(registry, network, new ConsumerSpec(
            "mech:suction_hopper",
            b -> RotationNetwork.Axis.Y,                       // nominal; omni ignores the stored axis
            config.getPower("suction_hopper", 1),
            config.suctionTickInterval,
            b -> suctionTick(b, network, registry),
            (b, event) -> {                                    // right-click opens the 5-slot GUI
                Inventory inv = registry.getOrCreateStorage(b);
                if (inv == null) return false;
                event.getPlayer().openInventory(inv);
                return true;
            },
            b -> mountFace(b),                                 // non-null ⇒ omni; exclude the mounted face
            null));
    }

    /** While powered: pull dropped items inward, capture ones in the own cell, feed the mount. */
    private static void suctionTick(Block hopper, RotationNetwork network, CustomBlockRegistry registry) {
        var key = CustomBlockRegistry.LocationKey.of(hopper);
        if (!network.isPowered(key)) return;

        Inventory internal = registry.getOrCreateStorage(hopper);
        if (internal == null) return;

        suctionEffect(hopper.getWorld(), hopper.getLocation().add(0.5, 0.5, 0.5),
            hopper.getX(), hopper.getY(), hopper.getZ(),
            internal, suctionPullRange, suctionPullStrength);

        pushToMount(hopper, internal);
    }

    /**
     * One suction work-step at an explicit position: nudge dropped items toward {@code center},
     * capture ones inside the {@code cellX/Y/Z} cell into {@code internal}, spawn the airy
     * particles. No powered gate, no storage lookup, no mount feeding — the caller owns those,
     * so this serves both the static path ({@link #suctionTick}) and hoppers riding a mechanism
     * ({@code MechanismRotationDriver}, which passes the live cell and the travelling inventory).
     */
    static void suctionEffect(World world, Location center, int cellX, int cellY, int cellZ,
                              Inventory internal, int pullRange, double pullStrength) {
        // Half-extent = full cells (pullRange + 0.5) + a 0.25 margin per side, so pullRange 1 →
        // 1.75 → 3.5×3.5×3.5 box (the 3×3×3 cells plus a quarter-block reach on every face).
        double r = pullRange + 0.75;
        var box = org.bukkit.util.BoundingBox.of(center.toVector(), r, r, r);
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(box)) {
            if (!(e instanceof org.bukkit.entity.Item item)) continue;   // Displays are not Items
            if (!item.isValid() || item.isDead()) continue;
            Location il = item.getLocation();

            // Capture inside the block's own 1×1×1 cell.
            if (il.getBlockX() == cellX && il.getBlockY() == cellY && il.getBlockZ() == cellZ) {
                ItemStack stack = item.getItemStack();
                var leftover = internal.addItem(stack.clone());
                if (leftover.isEmpty()) {
                    item.remove();
                } else {
                    int remaining = leftover.values().iterator().next().getAmount();
                    if (remaining != stack.getAmount()) {         // partial fit; full-block → leave as-is
                        ItemStack keep = stack.clone(); keep.setAmount(remaining);
                        item.setItemStack(keep);
                    }
                }
                continue;                                          // captured items aren't also pulled
            }

            // Pull toward center: fixed nudge, capped so it never overshoots (mirrors fanTick).
            var toCenter = center.toVector().subtract(il.toVector());
            double dist = toCenter.length();
            if (dist < 1e-3) continue;
            var unit = toCenter.multiply(1.0 / dist);
            var v = item.getVelocity();
            double along = v.dot(unit);
            if (along < pullStrength)
                item.setVelocity(v.add(unit.clone().multiply(pullStrength - along)));
        }

        // Rare inward "airy" particles from a random cube point toward center (count=0 → offset=velocity).
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        if (rnd.nextInt(3) == 0) {
            Location p = new Location(world,
                center.getX() + (rnd.nextDouble()*2-1)*r,
                center.getY() + (rnd.nextDouble()*2-1)*r,
                center.getZ() + (rnd.nextDouble()*2-1)*r);
            var inward = center.toVector().subtract(p.toVector());
            if (inward.lengthSquared() > 1e-4) inward.normalize();
            world.spawnParticle(Particle.CLOUD, p, 0, inward.getX(), inward.getY(), inward.getZ(), 0.1);
        }
    }

    /**
     * One item/tick out of internal storage toward the mount face. A vanilla Container is fed directly;
     * otherwise a MachineEjectEvent lets a listener (Pipes) claim the item. Items are KEPT on
     * STALL/DEFAULT — the suction hopper never drops (unlike ejectOutputs).
     */
    private static void pushToMount(Block hopper, Inventory internal) {
        BlockFace mount = mountFace(hopper);
        Block target = hopper.getRelative(mount);
        // Gateway resolution: a locked container (e.g. a dynamo) resolves to null → the item is
        // offered to listeners and otherwise RETAINED (this method's never-drop contract).
        Container targetContainer = ContainerAdapterRegistry.findVanillaContainer(target);

        for (int i = 0; i < internal.getSize(); i++) {
            ItemStack it = internal.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            ItemStack single = it.clone(); single.setAmount(1);

            boolean consumed;
            if (targetContainer != null) {
                Inventory dest = targetContainer.getInventory();
                consumed = dest.addItem(single).isEmpty();          // false → container full: keep, stop
            } else {
                // No vanilla container: offer to listeners (Pipes). Single item → deliverFromAbove is
                // all-or-nothing (no partial-insert). HANDLED consumes; STALL/DEFAULT keep (never drop).
                MachineEjectEvent ev = new MachineEjectEvent(hopper, mount, java.util.List.of(single));
                Bukkit.getPluginManager().callEvent(ev);
                consumed = ev.getResult() == MachineEjectEvent.Result.HANDLED;
            }

            if (consumed) {
                it.setAmount(it.getAmount() - 1);
                internal.setItem(i, it.getAmount() <= 0 ? null : it);
            }
            return;   // one attempt/tick regardless of outcome (keeps event rate low)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Redstone Motor: source, turns off when receiving redstone power
    // ──────────────────────────────────────────────────────────────────────

    private static void overlayRedstoneMotor(CustomBlockRegistry registry, RotationNetwork network,
                                            RotationConfig config) {
        int motorPower = config.getPower("redstone_motor", 1);
        String blockId = "mech:redstone_motor";
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
                    // Sync the network node role with the new visual — otherwise a
                    // switched-off (idle) motor keeps supplying power as a stale SOURCE.
                    // Mirrors the engine's removeNode+addNode discipline.
                    CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
                    network.removeNode(key);
                    network.addNode(b, blockId, RotationNetwork.axisFromState(target),
                        rsPowered ? RotationNetwork.NodeRole.TRANSMITTER : RotationNetwork.NodeRole.SOURCE,
                        rsPowered ? 0 : motorPower, false);
                    return;
                }
                recalcIfKnown(b, network);
            })
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                return false;
            })
            .onChunkLoad((b, state) -> {
                boolean spinning = state != null && state.startsWith("spinning_");
                RotationNetwork.Axis axis = RotationNetwork.axisFromState(state != null ? state : "spinning_y");
                network.addNode(b, blockId, axis,
                    spinning ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                    spinning ? motorPower : 0, false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Drill: consumer, breaks block in facing direction with break animation
    // ──────────────────────────────────────────────────────────────────────

    private static final NamespacedKey DRILL_FACING_KEY = new NamespacedKey("mech", "drill_facing");

    private static final ItemStack NETHERITE_PICK = new ItemStack(Material.NETHERITE_PICKAXE);
    private static final double DRILL_ANIM_RADIUS = 48.0;

    private static Set<Material> drillBlacklist;
    private static int drillTickInterval;
    private static int drillBreakStages;
    private static int millstoneTickInterval;
    private static int millstoneMaxBatch;
    private static int fanRange;
    private static double fanMinPush;
    private static double fanMaxPush;
    private static double fanPushPerPower;
    private static int suctionTickInterval;
    private static int suctionPullRange;
    private static double suctionPullStrength;

    /** Staged-break progress against one target material. Package-visible: the mechanism rotation
     *  driver keeps its own per-(mechanism, block-index) instances — a riding drill's world cell
     *  moves, so progress can't key on a world location. */
    record DrillState(int progress, Material targetMaterial) {}
    private static final Map<CustomBlockRegistry.LocationKey, DrillState> drillProgress = new HashMap<>();

    /** One {@link #drillEffect} step: the progress to carry into the next tick (null = start over)
     *  and whether the target block was actually broken this tick. */
    record DrillOutcome(@org.jetbrains.annotations.Nullable DrillState next, boolean broke) {}

    private static void overlayDrill(CustomBlockRegistry registry, RotationNetwork network,
                                      RotationConfig config) {
        drillBlacklist = config.drillBlacklist;
        drillTickInterval = config.drillTickInterval;
        drillBreakStages = config.drillBreakStages;
        int drillPower = config.getPower("drill", 1);
        String blockId = "mech:drill";
        CustomHeadBlock block = registry.getType(blockId);
        if (block == null) { warn(registry, blockId); return; }
        // Piston behavior is YAML-driven (drill sets cancel_pistons in rotation-blocks.yml).
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(drillTickInterval)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                return false;
            })
            // Vertical states render as a floating floor PLAYER_HEAD (ceiling drill mines down, floor
            // drill mines up) — the same hook the placer/pipes use for non-wall orientations.
            .playerHeadStates("idle_ceiling", "spinning_ceiling", "idle_y", "spinning_y")
            .onTick(b -> drillTick(b, registry, network))
            .onChunkLoad((b, state) -> {
                storeFacingIfAbsent(b, state);
                // Single-axis consumer. Wall drills spin on the state's axis; a vertical drill's state
                // (idle_y/idle_ceiling) resolves to Axis.Y, so it draws power only along Y — from the shaft
                // on its mounting side, since the mining face is the target block: an up-drill from the
                // block below, a down-drill from the block above. A shaft on a side never powers it.
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
        DrillOutcome out = drillEffect(registry, drill.getRelative(facing), facing,
            drillProgress.get(key), key.hashCode(), drillBreakStages, drillBlacklist);
        if (out.next() == null) drillProgress.remove(key);
        else drillProgress.put(key, out.next());
    }

    /**
     * One drill work-step against {@code target}: advance staged breaking, show the crack
     * animation, and break the block (world-side drops) once the stages complete. World-coupled
     * only through the target block itself — the caller supplies the powered gate, the target
     * cell, and owns the progress state, so this serves both the static path ({@link #drillTick},
     * keyed on the drill's world location) and drills riding a mechanism
     * ({@code MechanismRotationDriver}, keyed per mechanism block index with a live target cell).
     *
     * @param prev     the caller's stored progress for this drill, or null when starting fresh
     * @param sourceId stable {@code sendBlockDamage} id for this drill (distinct per drill)
     */
    static DrillOutcome drillEffect(CustomBlockRegistry registry, Block target, BlockFace facing,
                                    @org.jetbrains.annotations.Nullable DrillState prev,
                                    int sourceId, int breakStages, Set<Material> blacklist) {
        Material targetMat = target.getType();

        if (targetMat.isAir()) {
            return new DrillOutcome(null, false);
        }
        // Can't-break gate: air (above), then liquids / unbreakables / the configured blacklist, then
        // custom blocks that opt out via drillable(false) (below). Two things to know:
        //  - Unbreakable world blocks (bedrock, barrier, portals, command blocks, structure blocks) are
        //    caught ONLY by getHardness() < 0. Finite-hardness protected blocks are NOT caught here:
        //    VAULT / TRIAL_SPAWNER are mechanism-immovable (MovableBlocks.IMMOVABLE) yet remain drillable.
        //    Known asymmetry, intentionally left as-is.
        //  - `blacklist` (drillBlacklist) is hand-duplicated in THREE places — RotationConfig's default,
        //    rotation-config.yml's `drill.blacklist`, and MovableBlocks.IMMOVABLE's leading five — edit
        //    them together.
        if (target.isLiquid() || targetMat.getHardness() < 0 || blacklist.contains(targetMat)) {
            if (prev != null) clearBreakAnimationAt(target.getLocation(), sourceId);
            return new DrillOutcome(null, false);
        }

        CustomHeadBlock targetType = registry.getTypeFromBlock(target);
        if (targetType != null && !targetType.drillable()) {
            if (prev != null) clearBreakAnimationAt(target.getLocation(), sourceId);
            return new DrillOutcome(null, false);
        }

        int stages = drillStagesFor(targetMat);
        int progress = (prev != null && prev.targetMaterial() == targetMat) ? prev.progress() + 1 : 1;

        if (progress >= stages) {
            if (targetType != null) {
                if (targetType.storage() != null) registry.dropStorage(target);
                // Enrich BEFORE onBlockRemoved/setType destroy the tile PDC, so captured
                // banner/ingredient data survives a drill break like any other break path.
                ItemStack drop = CustomBlockRegistry.enrichDrop(target, targetType, targetType.createItem(1));
                registry.onBlockRemoved(target, targetType);
                target.getWorld().dropItemNaturally(target.getLocation().add(0.5, 0.5, 0.5), drop);
                target.setType(Material.AIR);
            } else {
                target.breakNaturally(NETHERITE_PICK);
            }
            // Clear the crack: the block is gone, but the client keeps this sourceId's destruction entry
            // parked on the (now air) cell — invisible until the cell is re-solidified, when it re-renders.
            // breakNaturally/setType send no destruction reset, so an explicit clear is required (mirrors
            // the blacklist/non-drillable exits above). Removal is keyed by sourceId; the location only
            // picks the recipients (players near the broken block).
            clearBreakAnimationAt(target.getLocation(), sourceId);
            return new DrillOutcome(null, true);
        }

        Location targetLoc = target.getLocation();
        float animProgress = (float) progress / stages;
        for (Player p : targetLoc.getNearbyPlayers(DRILL_ANIM_RADIUS)) {
            p.sendBlockDamage(targetLoc, animProgress, sourceId);
        }
        spawnDrillParticles(target, facing.getOppositeFace());
        return new DrillOutcome(new DrillState(progress, targetMat), false);
    }

    /**
     * How many break stages the drill takes on {@code mat}: proportional to how long a normal DIAMOND
     * PICKAXE would take, ×5 (the drill is deliberately slow), uncapped — so a stone block is quick-ish and
     * a hard one is slow, instead of the old flat 40 ticks for everything. Computed from block hardness
     * rather than {@code Block#getDestroySpeed} so the timing is deterministic and version-independent: a
     * diamond pick breaks a pickaxe-mineable block in {@code hardness × 1.5 / 8 s = hardness × 3.75} ticks;
     * ×5 penalty ÷ {@code drillTickInterval} (one stage per actuation) gives the stage count. All blocks are
     * treated at diamond-pick speed (non-pick blocks come out a touch fast — acceptable for a machine).
     * Unbreakable blocks never reach here (filtered by {@code hardness < 0} / blacklist); hardness 0 → one
     * stage. Shared by the static ({@link #drillTick}) and riding ({@code MechanismRotationDriver}) paths.
     */
    private static int drillStagesFor(Material mat) {
        float hardness = mat.getHardness();
        if (hardness <= 0f) return 1;
        float diamondTicks = hardness * 3.75f;                 // hardness × 1.5 / 8 × 20
        float penalised = diamondTicks * 5.0f;                 // ×5 a real diamond pick (a machine, not a miracle)
        return Math.max(1, Math.round(penalised / Math.max(1, drillTickInterval)));
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
        clearBreakAnimationAt(drill.getRelative(facing).getLocation(),
            CustomBlockRegistry.LocationKey.of(drill).hashCode());
    }

    /** Reset the crack animation at a known target location (mechanism drills track their last
     *  target cell explicitly — the drill itself has no world block to derive it from). */
    static void clearBreakAnimationAt(Location targetLoc, int sourceId) {
        for (Player p : targetLoc.getNearbyPlayers(DRILL_ANIM_RADIUS)) {
            p.sendBlockDamage(targetLoc, 0.0f, sourceId);
        }
    }

    private static void storeFacingIfAbsent(Block block, @org.jetbrains.annotations.Nullable String state) {
        if (!(block.getState() instanceof Skull skull)) return;
        if (skull.getPersistentDataContainer().has(DRILL_FACING_KEY)) return;

        BlockFace facing;
        if (block.getType() == Material.PLAYER_WALL_HEAD
                && block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
            facing = dir.getFacing();
        } else {
            // Floating/floor PLAYER_HEAD: a floor head carries no pitch, so the vertical mining direction
            // comes from the placed state — an "up" state (idle_y/spinning_y, screw points up) mines the
            // block ABOVE (floor-mounted drill); anything else, incl. the ceiling state, mines the block
            // BELOW. Default DOWN keeps legacy floor heads and the ceiling placer unchanged.
            facing = (state != null && (state.startsWith("idle_y") || state.startsWith("spinning_y")))
                ? BlockFace.UP : BlockFace.DOWN;
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

    /**
     * Placer rotation geometry from its stored facing — the single source of truth shared by the live
     * overlay ({@link #overlayPlacer}) and the mechanism mock ({@link MechanismRotationDriver}) so the
     * two can't drift: a ceiling placer (facing DOWN, a floating head) is an omni consumer excluding the
     * storage/cap face UP; a wall placer is a plain single-axis-Y consumer (returns null → not omni).
     */
    static @org.jetbrains.annotations.Nullable BlockFace placerOmniExcludedFace(
            @org.jetbrains.annotations.Nullable BlockFace facing) {
        return facing == BlockFace.DOWN ? BlockFace.UP : null;
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
    // Shaft encasing toggle: encased PLAYER_HEAD ↔ bare CHAIN (purely visual)
    // ──────────────────────────────────────────────────────────────────────

    /** Wrench (non-sneak) on a shaft: swap its world block between head and chain. */
    private static boolean toggleShaftEncasing(Block block, Player player,
                                               RotationNetwork network, CustomBlockRegistry registry) {
        boolean nowBare;
        if (block.getType() == Material.CHAIN) {
            makeShaftEncased(block, network, registry);
            nowBare = false;
        } else {
            makeShaftBare(block, network, registry);
            nowBare = true;
        }
        player.sendActionBar(Component.text(
                nowBare ? "Shaft: bare (chain)" : "Shaft: encased", NamedTextColor.GOLD));
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                org.bukkit.Sound.BLOCK_COPPER_PLACE, 1f, 1.3f);
        block.getWorld().spawnParticle(Particle.WAX_ON,
                block.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3, 0);
        return true;
    }

    /** Encased head → bare CHAIN, keeping the same network node. */
    static void makeShaftBare(Block block, RotationNetwork network, CustomBlockRegistry registry) {
        String state = registry.getState(block);
        RotationNetwork.Axis axis = RotationNetwork.axisFromState(state == null ? "idle_y" : state);
        var key = CustomBlockRegistry.LocationKey.of(block);

        block.setType(Material.CHAIN, false);
        org.bukkit.block.data.BlockData bd = block.getBlockData();
        if (bd instanceof org.bukkit.block.data.Orientable o) o.setAxis(toBukkitAxis(axis));
        block.setBlockData(bd, false);

        registry.addBareBlock(block, registry.getType("mech:shaft"));
        if (network.getNode(key) == null) {
            network.addNode(block, "mech:shaft", axis, RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        } else {
            network.recalculate(key); // drives the rod via updateBlockState → driveChainShaftSpinIfChain
        }
    }

    /** Bare CHAIN → encased head (canonical facing per axis; the shaft ring texture is symmetric). */
    static void makeShaftEncased(Block block, RotationNetwork network, CustomBlockRegistry registry) {
        RotationNetwork.Axis axis = block.getBlockData() instanceof org.bukkit.block.data.Orientable o
                ? fromBukkitAxis(o.getAxis()) : RotationNetwork.Axis.Y;
        var key = CustomBlockRegistry.LocationKey.of(block);
        registry.removeBareBlock(block);

        String state;
        if (axis == RotationNetwork.Axis.Y) {
            block.setType(Material.PLAYER_HEAD, false);
            org.bukkit.block.data.BlockData bd = block.getBlockData();
            if (bd instanceof org.bukkit.block.data.Rotatable r) r.setRotation(BlockFace.NORTH);
            block.setBlockData(bd, false);
            state = "idle_y";
        } else {
            BlockFace facing = axis == RotationNetwork.Axis.X ? BlockFace.EAST : BlockFace.NORTH;
            block.setType(Material.PLAYER_WALL_HEAD, false);
            org.bukkit.block.data.BlockData bd = block.getBlockData();
            if (bd instanceof org.bukkit.block.data.Directional d) d.setFacing(facing);
            block.setBlockData(bd, false);
            state = axis == RotationNetwork.Axis.X ? "idle_x" : "idle_z";
        }

        CustomHeadBlock type = registry.getType("mech:shaft");
        if (type != null) {
            registry.markBlock(block, type, state);
            registry.applyConfig(block, type, state, 0);
        }
        if (network.getNode(key) == null) {
            network.addNode(block, "mech:shaft", axis, RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        } else {
            network.recalculate(key);
        }
    }

    private static org.bukkit.Axis toBukkitAxis(RotationNetwork.Axis a) {
        return switch (a) {
            case X -> org.bukkit.Axis.X;
            case Z -> org.bukkit.Axis.Z;
            default -> org.bukkit.Axis.Y;
        };
    }

    private static RotationNetwork.Axis fromBukkitAxis(org.bukkit.Axis a) {
        return switch (a) {
            case X -> RotationNetwork.Axis.X;
            case Z -> RotationNetwork.Axis.Z;
            default -> RotationNetwork.Axis.Y;
        };
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────────────────────────────

    private static void recalcIfKnown(Block block, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(block);
        if (network.getNode(key) != null) network.recalculate(key);
    }

    static boolean debugInteract(Block block, org.bukkit.event.player.PlayerInteractEvent event,
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
                info = state + " | " + dbg.supply() + "/" + dbg.demand() + " Power, "
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
