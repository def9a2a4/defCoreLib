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
import org.bukkit.block.TileState;
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
                         MachineRecipes pressRecipes, MachineRecipes sieveRecipes,
                         RotationConfig config) {
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
        overlaySieve(registry, network, sieveRecipes, config);
        overlayPump(registry, network, config);
        overlayBurner(registry, fuelManager);
        overlayBoiler(registry, config);
        overlaySteamPiston(registry, network, fuelManager, config);
        overlayPlacer(registry, network, config);
        overlayRedstoneMotor(registry, network, config);
        // Redstone Dynamo: a barrel-backed sensor (own class — holds per-block level state + a mode menu).
        new RedstoneDynamo(registry, network, config).register();
        overlayDrill(registry, network, config);
        overlayFan(registry, network, config);
        overlaySuctionHopper(registry, network, config);
        // Mechanical Dispenser: a real vanilla dispenser (own class — holds a dispense-boost listener).
        new MechanicalDispenser(registry, network, config).register();

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
            .onNeighborChange((b, face) -> {
                // Reverser use-milestone: redstone-powered on a live line == actively flipping spin.
                // Fires on any neighbor update while in that state — listeners are expected to be
                // idempotent (mech throttles + short-circuits on already-done advancements).
                if ("mech:reverser".equals(blockId) && MachineActedEvent.hasListeners()
                        && b.getBlockPower() > 0
                        && network.isPowered(CustomBlockRegistry.LocationKey.of(b))) {
                    org.bukkit.Bukkit.getPluginManager().callEvent(new MachineActedEvent(b, blockId));
                }
                recalcIfKnown(b, network);
            })
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
                    // "Gated a live line": powered-state must be read BEFORE the lock recalcs it away.
                    boolean gatedLive = rsPowered && MachineActedEvent.hasListeners()
                        && network.isPowered(CustomBlockRegistry.LocationKey.of(b));
                    registry.setState(b, target);
                    CustomHeadBlock type = registry.getTypeFromBlock(b);
                    if (type != null) registry.applyConfig(b, type, target, 0);
                    if (gatedLive) {
                        org.bukkit.Bukkit.getPluginManager().callEvent(new MachineActedEvent(b, blockId));
                    }
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
            // The world block is a waxed copper chest: keep it (and custom chest neighbors —
            // other wheels, boilers) from vanilla double-chest merging.
            .onBlockPlaced((b, state) -> healChestMerges(registry, b))
            // Each paddle renders the plank matching the slab it was crafted with; captured onto
            // the tile PDC per-tag and listed in "Paddles:" lore (see IngredientCapture).
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
        if (spinning && b.getState() instanceof TileState tile) {
            RotationNetwork.SpinDirection desired = signal > 0
                    ? RotationNetwork.SpinDirection.CW : RotationNetwork.SpinDirection.CCW;
            boolean enteringSpin = !state.startsWith("spinning_");
            if (enteringSpin || network.readStoredDirection(key) != desired) {
                tile.getPersistentDataContainer().set(RotationNetwork.SPIN_DIR_KEY,
                        PersistentDataType.STRING,
                        desired == RotationNetwork.SpinDirection.CW ? "cw" : "ccw");
                tile.update();
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
        int enginePower = config.getPower("engine", 10);
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
                    // Fuel counter hit zero this tick. Before idling, pull the next fuel item from
                    // storage in the SAME tick so a running engine doesn't drop power for a full tick
                    // cycle at every fuel-item boundary. Only fall through to idle when nothing's left.
                    Inventory storage = registry.getOrCreateStorage(b);
                    if (storage != null) {
                        int fv = consumeOneFuelItem(storage, fuelManager);
                        if (fv > 0) {
                            fuelManager.addFuel(key, fv);
                            return; // stays running_ — no power gap
                        }
                    }
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
            config.getPower("millstone", 5),
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
            config.getPower("press", 4),
            config.pressTickInterval,
            b -> processingMachineTick(b, network, pressRecipes, registry,
                maxBatch, org.bukkit.Sound.BLOCK_ANVIL_USE),
            null, null, null));
    }

    /** Pan cycles since the sieve last drank a water unit. In-memory only (a restart forgives a
     *  partial count — same tolerance as the fuel manager's tick granularity). */
    private static final Map<CustomBlockRegistry.LocationKey, Integer> sievePansSinceDrink = new HashMap<>();

    private static void overlaySieve(CustomBlockRegistry registry, RotationNetwork network,
                                     MachineRecipes sieveRecipes, RotationConfig config) {
        int maxBatch = config.sieveMaxBatch;
        int waterPerCycles = Math.max(1, config.sieveWaterPerCycles);
        anon.def9a2a4.corelib.fluid.FluidTanks.registerTank("mech:sieve",
            anon.def9a2a4.corelib.fluid.FluidType.WATER, config.sieveTankUnits);
        // Same processing geometry as the millstone/press: wall-mounted, powered from the top (Y),
        // host container behind, outputs ejected below. All recipe outputs are chance-based, so a
        // cycle can consume its input and pay out nothing — but a completely full output container
        // still stalls the machine (the empty-roll capacity probe in processingEffect/ejectOutputs),
        // matching mill/press back-pressure.
        //
        // Panning also needs WATER: a whole-bucket tank on the block (FluidTanks), filled by a
        // liquid pipe (machine-tank endpoint) or a water-bucket right-click. One unit is drunk
        // every `waterPerCycles` processed pans; a dry sieve idles with a dust cue. (A sieve
        // riding a mechanism pans without water for now — travelling tank state is out of scope.)
        overlayConsumerMachine(registry, network, new ConsumerSpec(
            "mech:sieve",
            b -> RotationNetwork.Axis.Y,
            config.getPower("sieve", 10),
            config.sieveTickInterval,
            b -> {
                var key = CustomBlockRegistry.LocationKey.of(b);
                if (anon.def9a2a4.corelib.fluid.FluidTanks.units(b) <= 0) {
                    if (network.isPowered(key)) sieveDryCue(b);
                    return;
                }
                boolean processed = processingMachineTick(b, network, sieveRecipes, registry,
                    maxBatch, org.bukkit.Sound.ITEM_BRUSH_BRUSHING_GRAVEL);
                if (!processed) return;
                int pans = sievePansSinceDrink.merge(key, 1, Integer::sum);
                if (pans >= waterPerCycles) {
                    sievePansSinceDrink.remove(key);
                    anon.def9a2a4.corelib.fluid.FluidTanks.takeUnit(b);
                }
            },
            (b, event) -> tankBucketInteract("Sieve", b, event),
            null, null));
    }

    /** Powered but dry: a soft dusty rattle instead of panning. */
    private static void sieveDryCue(Block sieve) {
        var center = sieve.getLocation().add(0.5, 0.6, 0.5);
        sieve.getWorld().playSound(center, org.bukkit.Sound.BLOCK_SAND_STEP, 0.35f, 0.7f);
        sieve.getWorld().spawnParticle(org.bukkit.Particle.WHITE_ASH, center, 4, 0.2, 0.1, 0.2, 0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Liquid pump: in-line X/Z consumer moving whole-bucket fluid units vertically
    // ──────────────────────────────────────────────────────────────────────

    private static final NamespacedKey PUMP_REVERSE_KEY = new NamespacedKey("mech", "pump_reverse");

    private static void overlayPump(CustomBlockRegistry registry, RotationNetwork network,
                                    RotationConfig config) {
        CustomHeadBlock block = registry.getType("mech:pump");
        if (block == null) { warn(registry, "mech:pump"); return; }
        // Floor-only, axis-aligned like the chain hoist: the axle lies across the placer's view
        // and power crosses to both ±axis neighbours; the valve skull fronts the placer
        // (snapAxisFacingRotation). Fluid moves vertically: intake below,
        // output above (a fluid endpoint directly, or a pipe chain routed via FluidRouting;
        // the body display stretches up to meet a horizontal corner pipe — pumpBodyTransform);
        // a wrench flips the flow. All units are whole buckets (docs/todo/mechanism/fluids.md).
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(config.pumpTickInterval)
            .stateResolver(RotationBlocks::axisPlacementState)
            .onBlockPlaced(RotationBlocks::snapAxisFacingRotation)
            .displayTransformResolver((b, state, dec, idx) ->
                pumpBodyTransform(registry, config, b, dec))
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (!isWrench(event.getPlayer().getInventory().getItemInMainHand())) return false;
                boolean reversed = togglePumpReverse(b);
                event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
                    reversed ? "Pump: drawing from above, discharging below"
                             : "Pump: drawing from below, discharging above",
                    net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                return true;
            })
            .onTick(b -> pumpTick(b, network))
            .onChunkLoad((b, state) -> {
                // Heals legacy skulls snapped parallel to the axle; no-op once perpendicular.
                snapAxisFacingRotation(b, state);
                network.addNode(b, "mech:pump",
                    RotationNetwork.axisFromState(healAxisStateOrX(state)),
                    RotationNetwork.NodeRole.CONSUMER, config.getPower("pump", 4), false);
            })
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    /** Pump display resolver: only the body is ever touched — null for the intake and,
     *  critically, the axle, whose spin-animation base is the raw yml transform. The body
     *  branch must never return null either way: resolveDisplayTransforms skips null, so
     *  a null-when-no-corner contract would leave the body stretched after the corner
     *  above is broken. Repeated setTransformation with an equal value is a DataWatcher
     *  no-op, so water-physics churn beside the pump doesn't resend. */
    private static org.bukkit.util.Transformation pumpBodyTransform(
            CustomBlockRegistry registry, RotationConfig config, Block b,
            CustomHeadBlock.DisplayEntityConfig dec) {
        if (!"body".equals(dec.tagSuffix())) return null;
        org.bukkit.util.Transformation t = dec.transform();
        if (!horizontalCornerAbove(registry, b)) return t;
        // Head-display models hang down from their translation point: +d translation and
        // +2d scale raise the top by d while pinning the bottom to the pump's floor,
        // reaching the corner's elbow like a vertical pipe below a corner does.
        float d = (float) config.pumpStretchToCorner;
        return new org.bukkit.util.Transformation(
            new org.joml.Vector3f(t.getTranslation()).add(0f, d, 0f),
            t.getLeftRotation(),
            new org.joml.Vector3f(t.getScale()).add(0f, 2f * d, 0f),
            t.getRightRotation());
    }

    /** True when the block above is a pipes-plugin corner pipe in a horizontal state —
     *  the pump's vertical output handing off sideways. Reads corelib's own registry
     *  (pipe blocks register through BlockLoader), so no Pipes API is needed and this is
     *  simply false when Pipes is absent. Best-effort id match: corelib can't see pipes'
     *  BehaviorType. The down corner is excluded on purpose — it discharges into the
     *  pump, so a connected column would lie. */
    private static boolean horizontalCornerAbove(CustomBlockRegistry registry, Block b) {
        Block above = b.getRelative(BlockFace.UP);
        CustomHeadBlock type = registry.getTypeFromBlock(above);
        if (type == null || !"pipes".equals(type.namespace())
                || !type.typeId().endsWith("_corner_pipe")) {
            return false;
        }
        String state = registry.getState(above);
        return "north".equals(state) || "south".equals(state)
            || "east".equals(state) || "west".equals(state);
    }

    /** Placement yaw → X/Z axle state, exactly the chain hoist's rule: the axle lies across
     *  the placer's view (facing north/south → axle runs X). */
    private static String axisPlacementState(org.bukkit.event.block.BlockPlaceEvent event) {
        float yaw = (event.getPlayer().getLocation().getYaw() % 360f + 360f) % 360f;
        boolean alongZ = yaw >= 315f || yaw < 45f || (yaw >= 135f && yaw < 225f);
        return alongZ ? "idle_x" : "idle_z";
    }

    /** Snap the floor skull's vanilla 16-way yaw to the nearest cardinal, keeping the direction
     *  the placer gave it (burner: the shell display fronts the placer via orientShellToSkull). */
    private static void snapCardinalFloorRotation(Block b, String state) {
        if (b.getType() == Material.PLAYER_HEAD
                && b.getBlockData() instanceof org.bukkit.block.data.Rotatable r) {
            BlockFace cardinal = nearestCardinal(r.getRotation());
            if (cardinal != r.getRotation()) {
                r.setRotation(cardinal);
                b.setBlockData(r, false);
            }
        }
    }

    private static BlockFace nearestCardinal(BlockFace f) {
        return switch (f) {
            case NORTH, NORTH_NORTH_EAST, NORTH_NORTH_WEST, NORTH_WEST -> BlockFace.NORTH;
            case EAST, EAST_NORTH_EAST, EAST_SOUTH_EAST, NORTH_EAST -> BlockFace.EAST;
            case SOUTH, SOUTH_SOUTH_EAST, SOUTH_SOUTH_WEST, SOUTH_EAST -> BlockFace.SOUTH;
            case WEST, WEST_NORTH_WEST, WEST_SOUTH_WEST, SOUTH_WEST -> BlockFace.WEST;
            default -> BlockFace.SOUTH;
        };
    }

    /** Yaw the yml shell transform so the art's front (+Z at identity = SOUTH) points where the
     *  skull's Rotatable does — the Rotatable sibling of {@code RedstoneDynamo.orientHead} (same
     *  angle table, walls only). Safe for translations on the Y axis (the burner shell's), which
     *  a yaw rotation leaves in place; the model is x/z-centred at its origin, so it stays put. */
    private static org.bukkit.util.Transformation orientShellToSkull(
            Block b, org.bukkit.util.Transformation base) {
        BlockFace facing = (b.getBlockData() instanceof org.bukkit.block.data.Rotatable r)
            ? nearestCardinal(r.getRotation()) : BlockFace.SOUTH;
        return yawShell(base, facing);
    }

    /** The Directional sibling of {@link #orientShellToSkull}: yaw the shell to a real block's
     *  facing (boiler: the copper chest fronts the placer, chest-style). */
    private static org.bukkit.util.Transformation orientShellToFacing(
            Block b, org.bukkit.util.Transformation base) {
        BlockFace facing = (b.getBlockData() instanceof org.bukkit.block.data.Directional d)
            ? d.getFacing() : BlockFace.SOUTH;
        return yawShell(base, facing);
    }

    /** Rebuild {@code base} with a yaw pointing the art's front (+Z at identity = SOUTH) at
     *  {@code facing}. Same angle table as {@code RedstoneDynamo.rotationForFace}'s wall cases. */
    private static org.bukkit.util.Transformation yawShell(
            org.bukkit.util.Transformation base, BlockFace facing) {
        float h = (float) (Math.PI / 2), p = (float) Math.PI;
        org.joml.AxisAngle4f rot = switch (facing) {
            case NORTH -> new org.joml.AxisAngle4f(p, 0f, 1f, 0f);   // +Z → -Z
            case EAST  -> new org.joml.AxisAngle4f(h, 0f, 1f, 0f);   // +Z → +X
            case WEST  -> new org.joml.AxisAngle4f(-h, 0f, 1f, 0f);  // +Z → -X
            default    -> new org.joml.AxisAngle4f(0f, 0f, 1f, 0f);  // SOUTH: identity
        };
        return new org.bukkit.util.Transformation(
            base.getTranslation(), new org.joml.Quaternionf(rot), base.getScale(),
            base.getRightRotation());
    }

    /** Nearest cardinal perpendicular to the shaft axis, from the face's direction vector.
     *  A projection: cardinals it emits map to themselves, so it is safe on both the fresh
     *  16-way placement rotation and an already-snapped skull. Ties (a legacy skull sitting
     *  exactly parallel to the axle) break to SOUTH (X axle) / EAST (Z axle). */
    private static BlockFace perpendicularCardinal(BlockFace sixteen, RotationNetwork.Axis axis) {
        var dir = sixteen.getDirection();
        return switch (axis) {
            case Z -> dir.getX() < 0 ? BlockFace.WEST : BlockFace.EAST;
            default -> dir.getZ() < 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        };
    }

    /** Snap the floor skull toward the placer, clamped perpendicular to the axle (the pump's
     *  valve art peeks through the body and should front whoever placed it). The vanilla
     *  16-way rotation already points at the placer; we keep its perpendicular component.
     *  Idempotent, so it doubles as the chunk-load heal for legacy axis-parallel skulls. */
    private static void snapAxisFacingRotation(Block b, String state) {
        if (b.getType() == Material.PLAYER_HEAD
                && b.getBlockData() instanceof org.bukkit.block.data.Rotatable r) {
            BlockFace facing = perpendicularCardinal(r.getRotation(),
                RotationNetwork.axisFromState(healAxisStateOrX(state)));
            if (facing != r.getRotation()) {
                r.setRotation(facing);
                b.setBlockData(r, false);
            }
        }
    }

    /** Snap the floor skull's 16-way yaw to the cardinal the axle runs on (hoist pattern). */
    private static void snapAxisFloorRotation(Block b, String state) {
        if (b.getType() == Material.PLAYER_HEAD
                && b.getBlockData() instanceof org.bukkit.block.data.Rotatable r) {
            r.setRotation(RotationNetwork.axisFromState(state) == RotationNetwork.Axis.X
                ? BlockFace.EAST : BlockFace.NORTH);
            b.setBlockData(r, false);
        }
    }

    /** A state whose axis parses to Y (stale/missing) heals to the X axle default. */
    private static String healAxisStateOrX(String state) {
        if (state == null || RotationNetwork.axisFromState(state) == RotationNetwork.Axis.Y)
            return "idle_x";
        return state;
    }

    private static boolean togglePumpReverse(Block pump) {
        if (!(pump.getState() instanceof org.bukkit.block.Skull skull)) return false;
        var pdc = skull.getPersistentDataContainer();
        boolean reversed = pdc.has(PUMP_REVERSE_KEY);
        if (reversed) pdc.remove(PUMP_REVERSE_KEY);
        else pdc.set(PUMP_REVERSE_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        skull.update();
        return !reversed;
    }

    private static boolean isPumpReversed(Block pump) {
        return pump.getState() instanceof org.bukkit.block.Skull skull
            && skull.getPersistentDataContainer().has(PUMP_REVERSE_KEY);
    }

    /**
     * One pump cycle: while powered, move ONE unit of WATER from the intake side to the
     * discharge side. Intake must be a fluid endpoint directly adjacent (source block /
     * cauldron / tank); discharge is an adjacent endpoint or a pipe chain routed by the pipes
     * plugin ({@link anon.def9a2a4.corelib.fluid.FluidRouting}). Delivery happens BEFORE the
     * drain, so a failed route (blocked chain, full vessel) never destroys fluid — the pump
     * just idles. Water only: the copper pump ignores a lava intake (lava pumping is reserved
     * for a future iron-tier pump).
     */
    private static void pumpTick(Block pump, RotationNetwork network) {
        var key = CustomBlockRegistry.LocationKey.of(pump);
        if (!network.isPowered(key)) return;

        boolean reversed = isPumpReversed(pump);
        Block intake = pump.getRelative(reversed ? BlockFace.UP : BlockFace.DOWN);
        Block outlet = pump.getRelative(reversed ? BlockFace.DOWN : BlockFace.UP);

        anon.def9a2a4.corelib.fluid.FluidEndpoint provider =
            anon.def9a2a4.corelib.fluid.FluidEndpoints.providing(intake);
        if (provider == null) return;
        anon.def9a2a4.corelib.fluid.FluidType fluid = provider.provided(intake);
        if (fluid != anon.def9a2a4.corelib.fluid.FluidType.WATER) return;

        boolean delivered;
        anon.def9a2a4.corelib.fluid.FluidEndpoint acceptor =
            anon.def9a2a4.corelib.fluid.FluidEndpoints.accepting(outlet, fluid);
        if (acceptor != null) {
            delivered = acceptor.fill(outlet, fluid);
        } else if (anon.def9a2a4.corelib.fluid.FluidRouting.isConduit(outlet, fluid)) {
            delivered = anon.def9a2a4.corelib.fluid.FluidRouting.push(outlet, fluid);
        } else {
            return;
        }
        if (!delivered || !provider.drain(intake, fluid)) return;

        var center = pump.getLocation().add(0.5, 0.5, 0.5);
        pump.getWorld().playSound(center, org.bukkit.Sound.ITEM_BUCKET_FILL, 0.5f, 1.1f);
        pump.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, center.clone().add(0, 0.4, 0),
            6, 0.15, 0.1, 0.15, 0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Steam stack: burner (fuel) → boiler (water, disguised copper chest) → steam piston (SOURCE)
    // ──────────────────────────────────────────────────────────────────────

    private static final String BURNER_ID = "mech:burner";
    private static final String BOILER_ID = "mech:boiler";
    private static final String STEAM_PISTON_ID = "mech:steam_piston";

    /** Interval ticks accumulated toward the boiler's next water unit, per piston. In-memory —
     *  a restart forgives a partial minute, same tolerance as the sieve's pan counter. */
    private static final Map<CustomBlockRegistry.LocationKey, Integer> steamWaterTicks = new HashMap<>();
    /** Churn guard for the boiler's rendered state (applyConfig respawns displays, so only
     *  re-apply on an actual idle↔running transition). In-memory: after a reload the first
     *  piston tick re-applies the right state. */
    private static final Map<CustomBlockRegistry.LocationKey, String> boilerRendered = new HashMap<>();

    /** The burner is a dumb fuel box: storage + the shared fuel-manager lifecycle. The steam
     *  piston (the stack controller) burns it down and drives its lit/unlit display. */
    private static void overlayBurner(CustomBlockRegistry registry, EngineFuelManager fuelManager) {
        CustomHeadBlock block = registry.getType(BURNER_ID);
        if (block == null) { warn(registry, BURNER_ID); return; }
        registry.register(block.toBuilder()
            .drillable(false)
            .onBlockPlaced(RotationBlocks::snapCardinalFloorRotation)
            .displayTransformResolver((b, state, dec, idx) -> orientShellToSkull(b, dec.transform()))
            .onInteract((b, event) -> {
                Inventory inv = registry.getOrCreateStorage(b);
                if (inv == null) return false;
                int fuelTicks = fuelManager.getFuel(CustomBlockRegistry.LocationKey.of(b));
                var view = event.getPlayer().openInventory(inv);
                if (view != null) view.setTitle("Burner - " + fuelTicks + "s fuel");
                return true;
            })
            .onChunkLoad((b, state) -> fuelManager.readFromPDC(b))
            .onChunkUnload(b -> {
                fuelManager.writeToPDC(b);
                fuelManager.remove(CustomBlockRegistry.LocationKey.of(b));
            })
            .onBlockRemoved((b, state) -> fuelManager.remove(CustomBlockRegistry.LocationKey.of(b)))
            .build());
    }

    /** The boiler is a copper chest disguised by its tank shell (dynamo pattern — the default
     *  lockContainer seals the chest inventory), holding the stack's water tank in its tile
     *  entity's PDC. Fillable by pipe (machine-tank endpoint) or water bucket. Its
     *  idle/running display is driven by the steam piston above. */
    /** Force a chest block back to a SINGLE half (no-op for non-chests / already-single). */
    private static void unpairChest(Block b) {
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest chest
                && chest.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            chest.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
            b.setBlockData(chest, false);
        }
    }

    /** Belt-and-braces for chest-backed blocks (boiler, water wheel): never let the placed
     *  block merge into a double chest. Vanilla pairs BOTH halves, so heal the cardinal
     *  neighbors too — without this a custom chest next door keeps its paired half and
     *  renders offset/rotated. Any custom chest neighbor qualifies (a wheel can sit
     *  beside a boiler; both are waxed copper chests). */
    private static void healChestMerges(CustomBlockRegistry registry, Block b) {
        unpairChest(b);
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(face);
            if (n.getBlockData() instanceof org.bukkit.block.data.type.Chest
                    && registry.getTypeFromBlock(n) != null) {
                unpairChest(n);
            }
        }
    }

    private static void overlayBoiler(CustomBlockRegistry registry, RotationConfig config) {
        CustomHeadBlock block = registry.getType(BOILER_ID);
        if (block == null) { warn(registry, BOILER_ID); return; }
        anon.def9a2a4.corelib.fluid.FluidTanks.registerTank(BOILER_ID,
            anon.def9a2a4.corelib.fluid.FluidType.WATER, config.boilerTankUnits);
        registry.register(block.toBuilder()
            .onBlockPlaced((b, state) -> healChestMerges(registry, b))
            // The tank art fronts the placer: yaw the shell to the chest's facing (set from the
            // placer in CoreLibPlugin's physical_material placement, vanilla-chest style).
            .displayTransformResolver((b, state, dec, idx) -> orientShellToFacing(b, dec.transform()))
            .onInteract((b, event) -> {
                Boolean handled = tankBucketInteract("Boiler", b, event);
                if (handled != null) return handled;
                if (event.getPlayer().getInventory().getItemInMainHand().getType().isAir()) {
                    var spec = anon.def9a2a4.corelib.fluid.FluidTanks.spec(b);
                    event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
                        "Boiler water: " + anon.def9a2a4.corelib.fluid.FluidTanks.units(b) + "/"
                            + (spec == null ? 0 : spec.capacity()) + " buckets",
                        net.kyori.adventure.text.format.NamedTextColor.AQUA));
                    return true;
                }
                return false;
            })
            .onBlockRemoved((b, state) -> {
                anon.def9a2a4.corelib.fluid.FluidTanks.clear(b);
                boilerRendered.remove(CustomBlockRegistry.LocationKey.of(b));
            })
            .build());
    }

    private static void overlaySteamPiston(CustomBlockRegistry registry, RotationNetwork network,
                                           EngineFuelManager fuelManager, RotationConfig config) {
        int steamPower = config.getPower("steam_piston", 20);
        CustomHeadBlock block = registry.getType(STEAM_PISTON_ID);
        if (block == null) { warn(registry, STEAM_PISTON_ID); return; }
        // Floor-only, axle on X/Z from placement yaw (hoist pattern) — the SOURCE sits in-line
        // on its horizontal axle, power exits to both sides at piston level. Runs the engine's
        // removeNode+addNode SOURCE↔TRANSMITTER discipline, gated on the stack below it:
        // a burning burner two below and a watered boiler one below.
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(20)
            .stateResolver(RotationBlocks::axisPlacementState)
            .onBlockPlaced(RotationBlocks::snapAxisFloorRotation)
            .onNeighborChange((b, face) -> recalcIfKnown(b, network))
            .onInteract((b, event) -> {
                if (isWrench(event.getPlayer().getInventory().getItemInMainHand()))
                    return wrenchInteract(b, event, network, registry);
                if (event.getPlayer().getInventory().getItemInMainHand().getType().isAir()) {
                    steamStatusReadout(b, registry, fuelManager, steamPower, event.getPlayer());
                    return true;
                }
                return false;
            })
            .onTick(b -> steamPistonTick(b, network, fuelManager, registry, steamPower, config))
            .onChunkLoad((b, state) -> {
                boolean running = state != null && state.startsWith("running_");
                network.addNode(b, STEAM_PISTON_ID,
                    RotationNetwork.axisFromState(healAxisStateOrX(state)),
                    running ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
                    running ? steamPower : 0, false);
            })
            .onChunkUnload(b -> {
                var key = CustomBlockRegistry.LocationKey.of(b);
                network.removeNode(key);
                steamWaterTicks.remove(key);
            })
            .onBlockRemoved((b, state) -> {
                var key = CustomBlockRegistry.LocationKey.of(b);
                network.removeNode(key);
                steamWaterTicks.remove(key);
            })
            .build());
    }

    private static boolean isType(CustomBlockRegistry registry, Block b, String typeId) {
        CustomHeadBlock type = registry.getTypeFromBlock(b);
        return type != null && typeId.equals(type.fullId());
    }

    /** Empty-hand click on the piston: say exactly which stack gate is failing (the tick's
     *  checks, verbatim) so a dead stack is diagnosable in-game. */
    private static void steamStatusReadout(Block piston, CustomBlockRegistry registry,
            EngineFuelManager fuelManager, int steamPower, org.bukkit.entity.Player player) {
        Block boiler = piston.getRelative(BlockFace.DOWN);
        Block burner = boiler.getRelative(BlockFace.DOWN);
        String state = registry.getState(piston);
        boolean running = state != null && state.startsWith("running_");
        String msg;
        if (!isType(registry, boiler, BOILER_ID)) {
            msg = "Steam piston: needs a Boiler directly below";
        } else if (!isType(registry, burner, BURNER_ID)) {
            msg = "Steam piston: needs a Burner below the boiler";
        } else if (fuelManager.getFuel(CustomBlockRegistry.LocationKey.of(burner)) <= 0) {
            msg = "Steam piston: burner has no fuel";
        } else if (anon.def9a2a4.corelib.fluid.FluidTanks.units(boiler) <= 0) {
            var spec = anon.def9a2a4.corelib.fluid.FluidTanks.spec(boiler);
            msg = "Steam piston: boiler needs water (0/"
                + (spec == null ? 0 : spec.capacity()) + " buckets)";
        } else {
            msg = running ? "Steam piston: running — " + steamPower + " power"
                          : "Steam piston: starting…";
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text(
            msg, running ? net.kyori.adventure.text.format.NamedTextColor.GREEN
                         : net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }

    /**
     * The steam stack's controller, on the piston (interval 20 like the engine): validate
     * burner+boiler below, feed the burner's fuel counter from its storage (shared
     * {@link EngineFuelManager}, keyed by the BURNER's location — same boundary-refill trick as
     * the engine so power never gaps between fuel items), drink one boiler water unit per
     * configured interval, and flip the piston SOURCE↔TRANSMITTER + all three displays.
     */
    private static void steamPistonTick(Block piston, RotationNetwork network,
            EngineFuelManager fuelManager, CustomBlockRegistry registry, int steamPower,
            RotationConfig config) {
        var key = CustomBlockRegistry.LocationKey.of(piston);
        String state = registry.getState(piston);
        boolean running = state != null && state.startsWith("running_");
        String axis = state != null && state.contains("_")
            ? state.substring(state.lastIndexOf('_') + 1) : "x";

        Block boiler = piston.getRelative(BlockFace.DOWN);
        Block burner = boiler.getRelative(BlockFace.DOWN);
        boolean stackOk = isType(registry, boiler, BOILER_ID) && isType(registry, burner, BURNER_ID);

        boolean hasFuel = false;
        if (stackOk) {
            var burnerKey = CustomBlockRegistry.LocationKey.of(burner);
            if (fuelManager.getFuel(burnerKey) <= 0) {
                Inventory storage = registry.getOrCreateStorage(burner);
                if (storage != null) {
                    int fv = consumeOneFuelItem(storage, fuelManager);
                    if (fv > 0) fuelManager.addFuel(burnerKey, fv);
                }
            }
            if (running && fuelManager.getFuel(burnerKey) > 0 && fuelManager.tick(burnerKey) <= 0) {
                Inventory storage = registry.getOrCreateStorage(burner);
                if (storage != null) {
                    int fv = consumeOneFuelItem(storage, fuelManager);
                    if (fv > 0) fuelManager.addFuel(burnerKey, fv);
                }
            }
            hasFuel = fuelManager.getFuel(burnerKey) > 0;
        }
        boolean hasWater = stackOk && anon.def9a2a4.corelib.fluid.FluidTanks.units(boiler) > 0;
        boolean shouldRun = stackOk && hasFuel && hasWater;

        if (shouldRun && running) {
            // Drink one boiler unit per configured interval of running time.
            int accrued = steamWaterTicks.merge(key, 20, Integer::sum);
            if (accrued >= config.steamWaterIntervalTicks) {
                steamWaterTicks.remove(key);
                anon.def9a2a4.corelib.fluid.FluidTanks.takeUnit(boiler);
            }
        }
        if (shouldRun == running) {
            if (stackOk) applySteamPartStates(registry, burner, boiler, running);
            return;
        }

        // Transition: flip piston state + node role, and the part displays with it.
        String target = (shouldRun ? "running_" : "idle_") + axis;
        registry.setState(piston, target);
        CustomHeadBlock type = registry.getTypeFromBlock(piston);
        if (type != null) registry.applyConfig(piston, type, target, 0);
        network.removeNode(key);
        network.addNode(piston, STEAM_PISTON_ID, RotationNetwork.axisFromState(target),
            shouldRun ? RotationNetwork.NodeRole.SOURCE : RotationNetwork.NodeRole.TRANSMITTER,
            shouldRun ? steamPower : 0, false);
        if (!shouldRun) steamWaterTicks.remove(key);
        if (stackOk) applySteamPartStates(registry, burner, boiler, shouldRun);
    }

    /** Drive the burner's lit/unlit skull state and the boiler's rendered idle/running
     *  shell (churn-guarded — applyConfig respawns displays). */
    private static void applySteamPartStates(CustomBlockRegistry registry, Block burner,
                                             Block boiler, boolean running) {
        String burnerTarget = running ? "lit" : "unlit";
        if (!burnerTarget.equals(registry.getState(burner))) {
            registry.setState(burner, burnerTarget);
            CustomHeadBlock burnerType = registry.getTypeFromBlock(burner);
            if (burnerType != null && burnerType.states().containsKey(burnerTarget)) {
                registry.applyConfig(burner, burnerType, burnerTarget, 0);
            }
        }
        String boilerTarget = running ? "running" : "idle";
        var boilerKey = CustomBlockRegistry.LocationKey.of(boiler);
        if (!boilerTarget.equals(boilerRendered.get(boilerKey))) {
            boilerRendered.put(boilerKey, boilerTarget);
            CustomHeadBlock boilerType = registry.getTypeFromBlock(boiler);
            if (boilerType != null && boilerType.states().containsKey(boilerTarget)) {
                registry.applyConfig(boiler, boilerType, boilerTarget, 0);
            }
        }
    }

    /** Right-click with a water bucket fills one tank unit; anything else falls through. */
    private static @org.jetbrains.annotations.Nullable Boolean tankBucketInteract(
            String label, Block block, org.bukkit.event.player.PlayerInteractEvent event) {
        var player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.WATER_BUCKET) return null;
        if (!anon.def9a2a4.corelib.fluid.FluidTanks.addUnit(block)) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                label + " tank is full", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            return true;
        }
        player.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
            org.bukkit.Sound.ITEM_BUCKET_EMPTY, 0.7f, 1f);
        var spec = anon.def9a2a4.corelib.fluid.FluidTanks.spec(block);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
            label + " water: " + anon.def9a2a4.corelib.fluid.FluidTanks.units(block) + "/"
                + (spec == null ? 0 : spec.capacity()) + " buckets",
            net.kyori.adventure.text.format.NamedTextColor.AQUA));
        return true;
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
            config.getPower("placer", 2),
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
        if (placerEffect(placer.getRelative(facing), host) && MachineActedEvent.hasListeners()) {
            org.bukkit.Bukkit.getPluginManager().callEvent(new MachineActedEvent(placer, "mech:placer"));
        }
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
     *
     * <p>An <b>empty</b> outputs list is a capacity probe: all-chance recipes (sieve, glass→sand)
     * can roll nothing, and without the probe those cycles would consume input past a completely
     * full container — the one case where mill/press-style back-pressure must still stall.
     */
    private static boolean ejectOutputs(Block machine, List<ItemStack> outputs) {
        Block below = machine.getRelative(BlockFace.DOWN);
        // Gateway resolution: a locked container (e.g. a dynamo) resolves to null → outputs fall
        // through to the MachineEjectEvent/drop path below instead of entering it.
        Container oc = ContainerAdapterRegistry.findVanillaContainer(below);
        if (outputs.isEmpty()) return oc == null || hasRoomForAnything(oc.getInventory());
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
            fireProduced(machine, outputs);
            return true;
        }
        MachineEjectEvent event = new MachineEjectEvent(machine, BlockFace.DOWN, outputs);
        Bukkit.getPluginManager().callEvent(event);
        return switch (event.getResult()) {
            case HANDLED -> { fireProduced(machine, outputs); yield true; }
            case STALL   -> false;
            case DEFAULT -> {
                for (ItemStack out : outputs)
                    machine.getWorld().dropItem(machine.getLocation().add(0.5, 0.0, 0.5), out);
                fireProduced(machine, outputs);
                yield true;
            }
        };
    }

    /** True when {@code inv} could accept at least one more item of some kind: an empty slot or a
     *  non-full stack. The cheap capacity probe behind empty (all-chances-missed) output rolls. */
    static boolean hasRoomForAnything(Inventory inv) {
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir() || it.getAmount() < it.getMaxStackSize())
                return true;
        }
        return false;
    }

    /** Announce a successful (non-stall) output delivery. Only {@link #ejectOutputs} calls this —
     *  never pass-through forwarding ({@link #pushToMount}), so listeners can trust the outputs were
     *  produced by this machine. Type id resolved lazily: only when someone listens. */
    private static void fireProduced(Block machine, List<ItemStack> outputs) {
        if (!MachineProducedEvent.hasListeners()) return;
        CustomHeadBlock type = CoreLibPlugin.getInstance().getRegistry().getTypeFromBlock(machine);
        Bukkit.getPluginManager().callEvent(
            new MachineProducedEvent(machine, type == null ? "" : type.fullId(), outputs));
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
     *
     * @return true when at least one recipe cycle processed this tick (the sieve meters its
     *         water consumption off this; other callers ignore it)
     */
    private static boolean processingMachineTick(Block machine, RotationNetwork network,
            MachineRecipes recipes, CustomBlockRegistry registry, int maxBatch, org.bukkit.Sound sound) {
        var key = CustomBlockRegistry.LocationKey.of(machine);
        if (!network.isPowered(key)) return false;

        pullFromAdjacentContainer(machine);
        Inventory in = hostContainer(machine);
        if (in == null) return false;

        int[] stats = network.getNetworkStats(key);
        if (stats == null) return false;
        // stats = {supply, demand, count}; surplus headroom = supply - demand.
        int batch = Math.min(maxBatch, Math.max(1, stats[0] - stats[1]));

        boolean processed =
            processingEffect(in, batch, recipes, registry, outputs -> ejectOutputs(machine, outputs));
        if (processed) {
            machine.getWorld().playSound(machine.getLocation().add(0.5, 0.5, 0.5), sound, 0.6f, 1f);
        }
        return processed;
    }

    /**
     * The recipe-processing core of {@link #processingMachineTick}: up to {@code batch} recipe
     * cycles against {@code in}, delivering each cycle's outputs through {@code eject} (return
     * false = output full → stall; inputs are consumed only after delivery). {@code eject} is
     * called even with an EMPTY outputs list — that call is a capacity probe (see
     * {@link #hasRoomForAnything}) so all-chance recipes still stall on a full destination
     * instead of consuming input on nothing-rolls. World-agnostic — the
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

            // Always eject — an empty list is a capacity probe (all-chance recipes can roll
            // nothing; a completely full destination must still back-pressure to a stall
            // instead of silently consuming input).
            if (!eject.apply(outputs)) break;                       // output full → stall

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
            config.getPower("fan", 2),
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

        int pushed = fanEffect(fan.getWorld(), fan.getLocation().add(0.5, 0.5, 0.5), blowDirection(fan),
            surplus, fanRange, fanMinPush, fanMaxPush, fanPushPerPower);
        if (pushed > 0 && MachineActedEvent.hasListeners()) {
            Bukkit.getPluginManager().callEvent(new MachineActedEvent(fan, "mech:fan"));
        }
    }

    /**
     * One fan work-step at an explicit position: push mobs/players/items along a {@code range}-long
     * beam from {@code center} toward {@code blowDir}, with a directed airflow puff. Push =
     * {@code min(maxPush, minPush + surplus·pushPerPower)}. Fully world-local — the caller owns the
     * powered gate and surplus, so fans riding a mechanism blow at their live position
     * ({@code MechanismRotationDriver}).
     *
     * @return number of entities whose velocity was actually adjusted this step
     */
    static int fanEffect(World world, Location center, BlockFace blowDir, int surplus,
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

        int pushed = 0;
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(box)) {
            // Mobs, players, dropped items only — excludes our own Display block visuals.
            if (!(e instanceof org.bukkit.entity.LivingEntity || e instanceof org.bukkit.entity.Item)) continue;
            org.bukkit.util.Vector v = e.getVelocity();
            double along = v.dot(unit);
            if (along < push) {
                // Nudge the along-beam speed up to `push`; never accelerate past it (avoids runaway).
                e.setVelocity(v.add(unit.clone().multiply(push - along)));
                pushed++;
            }
        }
        return pushed;
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
            config.getPower("suction_hopper", 3),
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

        boolean captured = suctionEffect(hopper.getWorld(), hopper.getLocation().add(0.5, 0.5, 0.5),
            hopper.getX(), hopper.getY(), hopper.getZ(),
            internal, suctionPullRange, suctionPullStrength);
        if (captured && MachineActedEvent.hasListeners()) {
            Bukkit.getPluginManager().callEvent(new MachineActedEvent(hopper, "mech:suction_hopper"));
        }

        pushToMount(hopper, internal);
    }

    /**
     * One suction work-step at an explicit position: nudge dropped items toward {@code center},
     * capture ones inside the {@code cellX/Y/Z} cell into {@code internal}, spawn the airy
     * particles. No powered gate, no storage lookup, no mount feeding — the caller owns those,
     * so this serves both the static path ({@link #suctionTick}) and hoppers riding a mechanism
     * ({@code MechanismRotationDriver}, which passes the live cell and the travelling inventory).
     */
    static boolean suctionEffect(World world, Location center, int cellX, int cellY, int cellZ,
                                 Inventory internal, double pullRange, double pullStrength) {
        // Half-extent = pullRange + a 0.25 margin per side beyond the cell reach, so the pull box
        // side is 2*(pullRange + 0.75): pullRange 1 → 3.5³, 2.5 → 6.5³. Item capture still only
        // happens in the hopper's own 1×1×1 cell below; this box governs the inward pull.
        double r = pullRange + 0.75;
        boolean captured = false;
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
                    captured = true;
                } else {
                    int remaining = leftover.values().iterator().next().getAmount();
                    if (remaining != stack.getAmount()) {         // partial fit; full-block → leave as-is
                        ItemStack keep = stack.clone(); keep.setAmount(remaining);
                        item.setItemStack(keep);
                        captured = true;
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
        return captured;
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
    /** Reference tool for break-time pacing (see {@link #drillStagesFor}). */
    private static final ItemStack DIAMOND_PICK = new ItemStack(Material.DIAMOND_PICKAXE);
    /** Floor on drill break time (ticks) so soft blocks aren't near-instant. ~0.75 s. */
    private static final int DRILL_MIN_TICKS = 15;
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
    private static double suctionPullRange;
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
        int drillPower = config.getPower("drill", 5);
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
        if (out.broke() && MachineActedEvent.hasListeners()) {
            Bukkit.getPluginManager().callEvent(new MachineActedEvent(drill, "mech:drill"));
        }
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

        int stages = drillStagesFor(target);
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
     * How many break stages the drill takes on {@code target}: the block's REAL break time for a diamond
     * pickaxe, floored at {@link #DRILL_MIN_TICKS} so soft blocks aren't near-instant. {@code getDestroySpeed}
     * returns the pick's mining SPEED on this block (default 1.0; 8.0 when effective, 1.0 when not — so
     * non-pickaxe blocks like dirt/wood are correctly slower than stone), and vanilla break time is then
     * {@code hardness × 30 / speed} ticks (a diamond pick can harvest everything, so the /30 branch, never
     * /100). No multiplier and no cap: stone/deepslate hit the ~0.75 s floor, an iron block ≈1 s, obsidian
     * ≈9.4 s — its true diamond-pick time (not the runaway the old hardness×5 gave). Unbreakables never reach
     * here (filtered by {@code hardness < 0} / blacklist). Shared by the static ({@link #drillTick}) and
     * riding ({@code MechanismRotationDriver}) paths.
     */
    private static int drillStagesFor(Block target) {
        float hardness = target.getType().getHardness();
        int ticks;
        if (hardness <= 0f) {
            ticks = DRILL_MIN_TICKS;
        } else {
            float speed = target.getDestroySpeed(DIAMOND_PICK);
            if (speed <= 0f) speed = 1f;
            ticks = Math.max(DRILL_MIN_TICKS, (int) Math.ceil(hardness * 30.0 / speed));
        }
        return Math.max(1, Math.round(ticks / (float) Math.max(1, drillTickInterval)));
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
        if (!(block.getState() instanceof TileState tile)) return;
        String val = tile.getPersistentDataContainer().get(
                RotationNetwork.SPIN_DIR_KEY, PersistentDataType.STRING);
        RotationNetwork.SpinDirection oldDir = val == null ? RotationNetwork.SpinDirection.CW
                : "ccw".equals(val) ? RotationNetwork.SpinDirection.CCW : RotationNetwork.SpinDirection.CW;
        RotationNetwork.SpinDirection newDir = oldDir.reversed();

        tile.getPersistentDataContainer().set(
                RotationNetwork.SPIN_DIR_KEY, PersistentDataType.STRING,
                newDir == RotationNetwork.SpinDirection.CW ? "cw" : "ccw");
        tile.update();

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
