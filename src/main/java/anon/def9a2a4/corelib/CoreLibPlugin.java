package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class CoreLibPlugin extends JavaPlugin implements Listener {

    private static CoreLibPlugin instance;
    private CustomBlockRegistry registry;
    private MechanismRegistry mechanismRegistry;
    private MechanismMinecartManager mechanismMinecartManager;
    private GlueManager glueManager;
    private GlueAuthoring glueAuthoring;
    private ShowcaseBuilder showcaseBuilder;
    private java.util.Map<String, ShowcaseSpec> showcases = java.util.Map.of();
    private RotationNetwork rotationNetwork;
    private EngineFuelManager fuelManager;
    private BannerManager bannerManager;
    private LargeBannerRecipes largeBannerRecipes;

    @Override
    public void onEnable() {
        instance = this;
        new Metrics(this, 32317);
        registry = new CustomBlockRegistry(this);
        registry.startTasks();
        mechanismRegistry = new MechanismRegistry(this, registry);
        mechanismRegistry.startTasks();
        getServer().getPluginManager().registerEvents(this, this);

        // Load demo blocks from YAML
        try (InputStream demoStream = getResource("demo-blocks.yml")) {
            if (demoStream != null) {
                BlockLoader.load(demoStream, registry, getLogger());
            }
        } catch (IOException ignored) {}

        // Load vertical slabs from YAML
        try (InputStream slabStream = getResource("slabs.yml")) {
            if (slabStream != null) {
                BlockLoader.load(slabStream, registry, getLogger());
            }
        } catch (IOException ignored) {}

        // Load rotation blocks from YAML, then overlay Java callbacks
        try (InputStream rotStream = getResource("rotation-blocks.yml")) {
            if (rotStream != null) {
                BlockLoader.load(rotStream, registry, getLogger());
            }
        } catch (IOException ignored) {}

        // RedstoneDisplays indicator blocks (recipes gated behind the `rsd` companion plugin)
        try (InputStream rsdStream = getResource("redstone-displays.yml")) {
            if (rsdStream != null) {
                BlockLoader.load(rsdStream, registry, getLogger());
            }
        } catch (IOException ignored) {}

        // Inventory-only custom items (juices, oils, …) — non-placeable CustomHeadBlocks
        try (InputStream itemStream = getResource("custom-items.yml")) {
            if (itemStream != null) {
                BlockLoader.load(itemStream, registry, getLogger());
            }
        } catch (IOException ignored) {}

        // corelib-namespace inventory-only items (slime glue) — same model as custom-items.yml
        try (InputStream corelibItemStream = getResource("corelib-items.yml")) {
            if (corelibItemStream != null) {
                BlockLoader.load(corelibItemStream, registry, getLogger());
            }
        } catch (IOException ignored) {}
        RotationConfig rotConfig = new RotationConfig();
        try (InputStream configStream = getResource("rotation-config.yml")) {
            if (configStream != null) {
                rotConfig.load(configStream, getLogger());
            }
        } catch (IOException ignored) {}
        rotationNetwork = new RotationNetwork(this, registry);
        rotationNetwork.setMaxNetworkSize(rotConfig.maxNetworkSize);
        fuelManager = new EngineFuelManager(rotConfig.fuelValues);
        MachineRecipes millRecipes = new MachineRecipes();
        try (InputStream millStream = getResource("mill-recipes.yml")) {
            if (millStream != null) {
                millRecipes.load(millStream, getLogger());
            }
        } catch (IOException ignored) {}
        MachineRecipes pressRecipes = new MachineRecipes();
        try (InputStream pressStream = getResource("press-recipes.yml")) {
            if (pressStream != null) {
                pressRecipes.load(pressStream, getLogger());
            }
        } catch (IOException ignored) {}
        RotationBlocks.register(registry, rotationNetwork, fuelManager, millRecipes, pressRecipes, rotConfig);

        // Anchor-owned block selection ("glue") — shared by doors/rotators (wired in D3).
        // The glue item itself is declared in corelib-items.yml (mech:glue_item).
        glueManager = new GlueManager(rotConfig.glueMaxSize);
        glueAuthoring = new GlueAuthoring(this, registry, glueManager,
            rotConfig.glueOutlineInterval, rotConfig.glueSessionTimeout);
        getServer().getPluginManager().registerEvents(glueAuthoring, this);
        glueAuthoring.start();

        // Demo showcases (multi-block machines) — placement via /defcorelib showcase build <id>.
        showcaseBuilder = new ShowcaseBuilder(this, registry);
        try (InputStream showcaseStream = getResource("showcases.yml")) {
            if (showcaseStream != null) {
                showcases = ShowcaseSpec.load(showcaseStream, getLogger());
            }
        } catch (IOException ignored) {}

        // Register mechanism demos
        new DoorDemo(this, registry, mechanismRegistry, glueManager).register();
        RotationRotator rotationRotator = new RotationRotator(this, registry, rotationNetwork, mechanismRegistry, glueManager);
        rotationRotator.register();
        mechanismMinecartManager = new MechanismMinecartManager(this, registry, mechanismRegistry, glueManager);
        glueAuthoring.setMinecartManager(mechanismMinecartManager);
        mechanismMinecartManager.register();
        getServer().getPluginManager().registerEvents(mechanismMinecartManager, this);

        // Banner systems
        bannerManager = new BannerManager(this);
        getServer().getPluginManager().registerEvents(bannerManager, this);
        largeBannerRecipes = new LargeBannerRecipes(this);
        getServer().getPluginManager().registerEvents(largeBannerRecipes, this);

        // Register recipes after all blocks are loaded
        registry.finalizeLoading();

        // Bespoke recipes whose result is a vanilla item made from a custom ingredient
        // (the YAML recipe system only outputs the owning custom type's item):
        // dough → bread (furnace/smoker) and seed oil → iron/copper lantern.
        RotationBlocks.registerBakingRecipes(this, registry);
        RotationBlocks.registerSeedOilRecipes(this, registry);

        // Docs export mode (-Ddefcorelib.export=<path>): on ServerLoadEvent, dump every block's
        // ground-truth placed display data to JSON and shut the server down. Inert otherwise.
        DisplayExporter.armIfRequested(this, registry, showcaseBuilder, showcases.values(),
                rotationNetwork, fuelManager);

        // Headless showcase integration tests (-Ddefcorelib.showcaseTest=true): build, run, assert, exit.
        ShowcaseRunner.armIfRequested(this, registry, rotationNetwork, fuelManager, showcaseBuilder, showcases.values(), rotationRotator);

        getLogger().info("DefCoreLib enabled: " + registry.allTypes().size()
                + " block types, " + showcases.size() + " showcases");
    }

    @Override
    public void onDisable() {
        if (largeBannerRecipes != null) {
            largeBannerRecipes.unregister();
        }
        if (mechanismMinecartManager != null) {
            mechanismMinecartManager.shutdown();
        }
        if (mechanismRegistry != null) {
            mechanismRegistry.shutdown();
        }
        if (registry != null) {
            registry.shutdown();
        }
        instance = null;
    }

    public static CoreLibPlugin getInstance() {
        return instance;
    }

    public CustomBlockRegistry getRegistry() {
        return registry;
    }

    /** Activate flag/large/huge banner functionality (called from the bbanners companion plugin). */
    public void activateBanners() {
        if (bannerManager != null) bannerManager.activate();
        if (largeBannerRecipes != null) largeBannerRecipes.activate();
    }

    /** Deactivate banner functionality (bbanners disable). Placed banners are unaffected. */
    public void deactivateBanners() {
        if (bannerManager != null) bannerManager.deactivate();
        if (largeBannerRecipes != null) largeBannerRecipes.deactivate();
    }

    public MechanismRegistry getMechanismRegistry() {
        return mechanismRegistry;
    }

    GlueManager getGlueManager() {
        return glueManager;
    }

    public RotationNetwork getRotationNetwork() {
        return rotationNetwork;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chunk lifecycle — single scan
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        registry.onChunkLoad(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        registry.onChunkUnload(event.getChunk());
    }

    // Paper EntitiesLoadEvent: fires when entities finish loading into a chunk.
    // ChunkLoadEvent does NOT guarantee entities are ready on Paper (async entity loading).
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        // Re-discover mechanism minecarts surviving server restart
        if (mechanismMinecartManager != null) {
            mechanismMinecartManager.scanChunkForMinecarts(event.getChunk());
        }
        // Clean up orphaned mechanism entities from previous sessions
        if (mechanismRegistry != null) {
            mechanismRegistry.cleanupOrphanedEntities(event.getChunk());
        }
        // Re-resolve dynamic display transforms now that entities are available
        org.bukkit.Chunk chunk = event.getChunk();
        if (registry.chunkMayHaveCustomBlocks(chunk)) {
            for (org.bukkit.block.BlockState tile : chunk.getTileEntities()) {
                if (!(tile instanceof org.bukkit.block.Skull skull)) continue;
                String typeId = skull.getPersistentDataContainer()
                        .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
                if (typeId == null) continue;
                CustomHeadBlock type = registry.getType(typeId);
                if (type == null || type.displayTransformResolver() == null) continue;
                String state = skull.getPersistentDataContainer()
                        .get(CustomBlockRegistry.STATE_KEY, PersistentDataType.STRING);
                registry.resolveDisplayTransforms(tile.getBlock(), type, state);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block placement — detect custom block items, write PDC, apply config
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        ItemStack item = event.getItemInHand();
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String typeId = meta.getPersistentDataContainer().get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) return;

        CustomHeadBlock type = registry.getType(typeId);
        if (type == null) return;

        // Inventory-only items (juices, oils, wrench): never place as a block.
        if (!type.placeable()) {
            event.setCancelled(true);
            return;
        }

        boolean isAlreadySkull = block.getType() == Material.PLAYER_HEAD
                || block.getType() == Material.PLAYER_WALL_HEAD;

        // Compute attachment face
        BlockFace placedOn;
        if (isAlreadySkull) {
            placedOn = getAttachmentFace(block);
        } else {
            BlockFace clickedFace = event.getBlockAgainst().getFace(block);
            if (clickedFace != null && clickedFace != BlockFace.UP && clickedFace != BlockFace.DOWN) {
                placedOn = clickedFace.getOppositeFace();
            } else {
                placedOn = BlockFace.DOWN;
            }
        }

        // Check placement restrictions (before skull conversion so cancellation reverts cleanly)
        if (type.placement() != null) {
            CustomHeadBlock.PlacementConfig pc = type.placement();
            if (!pc.allowedFaces().isEmpty() && !pc.allowedFaces().contains(placedOn)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(
                        net.kyori.adventure.text.Component.text("Cannot place this block here",
                                net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            if (pc.requireSolid()) {
                Block support = block.getRelative(placedOn);
                if (!support.getType().isSolid()) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(
                            net.kyori.adventure.text.Component.text("Requires a solid block",
                                    net.kyori.adventure.text.format.NamedTextColor.RED));
                    return;
                }
            }
        }

        // Convert non-skull blocks to skulls (e.g., slab items with item_material)
        if (!isAlreadySkull) {
            BlockFace clickedFace = event.getBlockAgainst().getFace(block);
            if (clickedFace != null && clickedFace != BlockFace.UP && clickedFace != BlockFace.DOWN) {
                block.setType(Material.PLAYER_WALL_HEAD, false);
                if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                    dir.setFacing(clickedFace);
                    block.setBlockData(dir, false);
                }
            } else {
                block.setType(Material.PLAYER_HEAD, false);
            }
        }

        // Resolve initial state: custom resolver takes priority over placement state map
        String resolvedState;
        if (type.stateResolver() != null) {
            resolvedState = type.stateResolver().resolve(event);
            if (resolvedState == null) {
                event.setCancelled(true);
                return;
            }
        } else {
            resolvedState = type.defaultState();
            var psm = type.placementStateMap();
            if (psm != null) {
                String mapped = psm.get(placedOn);
                if (mapped != null) resolvedState = mapped;
            }
        }

        // States declared as playerHeadStates need PLAYER_HEAD block type (e.g. vertical pipes)
        if (resolvedState != null && type.playerHeadStates().contains(resolvedState)
                && block.getType() == Material.PLAYER_WALL_HEAD) {
            block.setType(Material.PLAYER_HEAD, false);
            if (block.getBlockData() instanceof org.bukkit.block.data.Rotatable rotatable) {
                rotatable.setRotation(BlockFace.NORTH);
                block.setBlockData(rotatable, false);
            }
        }
        final String state = resolvedState;

        // Write PDC to the placed skull (with correct initial state)
        registry.markBlock(block, type, state);

        // Copy captured display data from item → skull (windmill banners / water-wheel planks)
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta
                && block.getState() instanceof org.bukkit.block.Skull skull) {
            if (type.displayItemResolver() != null) {
                CustomBlockRegistry.copyBladePdc(
                        skullMeta.getPersistentDataContainer(),
                        skull.getPersistentDataContainer());
            }
            if (type.ingredientCapture() != null) {
                type.ingredientCapture().copyPdc(
                        skullMeta.getPersistentDataContainer(),
                        skull.getPersistentDataContainer());
            }
            skull.update();
        }

        // Play place sound
        if (type.placeSound() != null) {
            var s = type.placeSound();
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), s.sound(), s.volume(), s.pitch());
        }
        int power = type.sensitivity() != CustomHeadBlock.Sensitivity.NONE ? registry.readPower(block, type) : 0;

        // Schedule for next tick to ensure block state is fully initialized
        getServer().getScheduler().runTask(this, () -> {
            // Guard: block may have been broken between placement and this tick
            if (registry.getTypeFromBlock(block) == null) return;

            registry.applyConfig(block, type, state, power);

            // Register for redstone tracking if needed
            if (type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
                registry.trackRedstone(block, type, power);
            }

            // Register tick tracking (mirrors restoreBlock — needed for engine/drill onTick)
            if (type.onTick() != null && type.tickInterval() != null) {
                registry.trackTick(block, type);
            }

            // Fire placement callback (onBlockPlaced takes priority over onChunkLoadCallback)
            if (type.onBlockPlaced() != null) {
                type.onBlockPlaced().accept(block, state);
            } else if (type.onChunkLoadCallback() != null) {
                type.onChunkLoadCallback().accept(block, state);
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block breaking — cleanup displays, light, particles, drops
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        String state = registry.getState(block);

        // Drop storage contents before cleanup
        if (type.storage() != null) {
            registry.dropStorage(block);
        }

        // Play break sound
        if (type.breakSound() != null) {
            var s = type.breakSound();
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), s.sound(), s.volume(), s.pitch());
        }

        // Read power BEFORE cleanup removes redstone tracking
        int power = type.sensitivity() != CustomHeadBlock.Sensitivity.NONE ? registry.readPower(block, type) : 0;

        // Enrich self-drop item BEFORE cleanup (skull PDC must be readable)
        ItemStack selfDropItem = enrichDrop(block, type, type.createItem(1));

        // Cleanup
        registry.onBlockRemoved(block, type);

        // Handle drops (skip in creative mode)
        event.setDropItems(false); // always suppress vanilla head drop
        Player player = event.getPlayer();
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        List<CustomHeadBlock.DropRule> rules = type.dropRules();
        if (!rules.isEmpty()) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            boolean silk = tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);

            for (CustomHeadBlock.DropRule rule : rules) {
                if (rule.inState() != null && !rule.inState().equals(state)) continue;
                if (rule.silkTouch() != null && rule.silkTouch() != silk) continue;
                if (rule.requiredTool() != null && tool.getType() != rule.requiredTool()) continue;

                if (rule.isSelfDrop()) {
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), selfDropItem);
                } else {
                    for (CustomHeadBlock.ItemDrop itemDrop : rule.drops()) {
                        ItemStack drop = new ItemStack(itemDrop.material(), itemDrop.amount());
                        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
                    }
                }
                break; // First matching rule wins
            }
        }
    }

    // Explosion cleanup — remove custom blocks from blast list, drop correct items
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    private void handleExplosion(List<Block> blockList) {
        java.util.Iterator<Block> it = blockList.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type != null) {
                it.remove(); // prevent vanilla skull drop
                if (type.storage() != null) registry.dropStorage(block);
                ItemStack drop = enrichDrop(block, type, type.createItem(1));
                block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 0.5, 0.5), drop);
                registry.onBlockRemoved(block, type);
                block.setType(Material.AIR); // actually remove the block from the world
            }
        }
    }

    // Piston handling
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event.getBlocks(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event.getBlocks(), event);
    }

    private void handlePiston(List<Block> blocks, org.bukkit.event.Cancellable event) {
        for (Block block : blocks) {
            CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type == null) continue;
            if (type.cancelPistons()) {
                event.setCancelled(true);
                return;
            }
            if (type.breakOnPiston()) {
                String state = registry.getState(block);
                if (type.storage() != null) registry.dropStorage(block);
                ItemStack drop = enrichDrop(block, type, type.createItem(1));
                for (var rule : type.dropRules()) {
                    if (rule.inState() != null && !rule.inState().equals(state)) continue;
                    if (rule.isSelfDrop()) {
                        block.getWorld().dropItemNaturally(
                                block.getLocation().add(0.5, 0.5, 0.5), drop);
                    } else {
                        for (CustomHeadBlock.ItemDrop itemDrop : rule.drops()) {
                            block.getWorld().dropItemNaturally(
                                    block.getLocation().add(0.5, 0.5, 0.5),
                                    new ItemStack(itemDrop.material(), itemDrop.amount()));
                        }
                    }
                    break;
                }
                registry.onBlockRemoved(block, type);
                block.setType(Material.AIR, false);
            }
        }
    }

    // Fire destruction — custom blocks consumed by fire, no drops
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        CustomHeadBlock type = registry.getTypeFromBlock(event.getBlock());
        if (type != null) {
            if (type.storage() != null) registry.dropStorage(event.getBlock());
            registry.onBlockRemoved(event.getBlock(), type);
        }
    }

    // Prevent flowing water/lava from destroying a placed custom head. The head cell is the
    // flow's destination; vanilla would replace it and drop a plain (non-functional) head.
    // Cancel at HIGH so the block is never destroyed. Cheap material pre-check before the Set
    // lookup keeps this off the hot path for ordinary water tiles.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlowIntoCustomHead(org.bukkit.event.block.BlockFromToEvent event) {
        Block to = event.getToBlock();
        Material t = to.getType();
        if (t != Material.PLAYER_HEAD && t != Material.PLAYER_WALL_HEAD) return;
        if (registry.isCustomBlock(to)) {
            event.setCancelled(true);
        }
    }

    // Water/lava flow destroying custom heads
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(org.bukkit.event.block.BlockFromToEvent event) {
        Block to = event.getToBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(to);
        if (type != null) {
            if (type.storage() != null) registry.dropStorage(to);
            registry.onBlockRemoved(to, type);
        }
    }

    // /fill, /setblock, physics-based destruction — cleanup without drops
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDestroy(com.destroystokyo.paper.event.block.BlockDestroyEvent event) {
        Block block = event.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;
        if (type.storage() != null) registry.dropStorage(block);
        registry.onBlockRemoved(block, type);
    }

    // Prevent wall-mounted custom skulls from popping off when support block is removed,
    // but allow other physics (redstone propagation) to proceed normally
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysicsCancelForCustomSkulls(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;
        if (!registry.isCustomBlock(block)) return; // fast set check before expensive PDC read
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;
        // Only cancel if the support block is gone (prevents pop-off without suppressing redstone)
        BlockFace attachment = getAttachmentFace(block);
        Block support = block.getRelative(attachment);
        if (!support.getType().isSolid()) {
            event.setCancelled(true);
        }
    }

    // Creative middle-click — return correct custom item
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickBlock(io.papermc.paper.event.player.PlayerPickBlockEvent event) {
        Block block = event.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack customItem = enrichDrop(block, type, type.createItem(1));
        InventoryUtil.pickInto(player, customItem, event.getTargetSlot());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Recipe result customization — custom-block ingredients match via ExactChoice
    // (see CustomBlockRegistry.choiceForBlock); here we only handle toggle recipes
    // and capture banner ingredients onto windmill/fan results.
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        if (!(event.getRecipe() instanceof org.bukkit.Keyed keyed)) return;

        var toggle = registry.getToggleRecipe(keyed.getKey());
        if (toggle != null) {
            for (ItemStack item : event.getInventory().getMatrix()) {
                if (item == null || item.getType() == Material.AIR) continue;
                if (item.getItemMeta() != null
                        && item.getItemMeta().getPersistentDataContainer()
                            .has(CustomBlockRegistry.BLOCK_TYPE_KEY)) {
                    event.getInventory().setResult(new ItemStack(toggle.outputMaterial()));
                    return;
                }
            }
        }

        // Capture banner ingredients onto the result item (for windmill blades etc.)
        captureBannerIngredients(event.getInventory());
        // Generic ingredient capture (e.g. water-wheel paddle planks).
        IngredientCapture.capture(event.getInventory(), registry);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraftItem(org.bukkit.event.inventory.CraftItemEvent event) {
        // Re-capture on actual craft (Bukkit creates a fresh result from the recipe)
        captureBannerIngredients(event.getInventory());
        IngredientCapture.capture(event.getInventory(), registry);
    }

    /** Capture the crafting matrix's banner ingredients onto a windmill/fan result's blade PDC,
     *  so the placed blades show those banners (patterns included). For windmills (which carry a
     *  bannerTier) also derive the tier from the banners and swap the result to the matching
     *  windmill — plain → Windmill, Large → Large Windmill, Huge → Huge Windmill — rejecting a mix
     *  of tiers. The fan (no bannerTier) just captures its blades, any banner allowed. */
    private void captureBannerIngredients(org.bukkit.inventory.CraftingInventory inv) {
        ItemStack result = inv.getResult();
        if (result == null || result.getType() != Material.PLAYER_HEAD) return;
        if (!(result.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta meta)) return;

        String typeId = meta.getPersistentDataContainer()
                .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) return;
        CustomHeadBlock type = registry.getType(typeId);
        if (type == null || type.displayItemResolver() == null) return;
        // Windmills carry a bannerTier and tier-swap by banner; the fan (also banner-bladed) doesn't.
        // The tier swap is gated on the bbanners plugin (windmillTierEnabled): without it, treat every
        // windmill as plain (just capture blades), so a large/huge banner can't sneak a tier windmill.
        boolean isWindmill = type.bannerTier() != null && registry.isWindmillTierEnabled();

        // getMatrix() is 0-indexed length 9: [0]=TL .. [8]=BR. The blades are the four "+" arms.
        ItemStack[] matrix = inv.getMatrix();
        // Map: top-center(1) → blade_0, middle-right(5) → blade_1,
        //       bottom-center(7) → blade_2, middle-left(3) → blade_3
        int[] bannerSlots = {1, 5, 7, 3}; // 0-indexed matrix positions

        boolean hasBanners = false;
        byte[][] bladeData = new byte[4][];
        java.util.List<ItemStack> banners = new java.util.ArrayList<>();
        BannerTier bannerTier = null; // common tier across the banners (windmills only)
        for (int i = 0; i < 4; i++) {
            ItemStack banner = matrix[bannerSlots[i]];
            if (banner != null && banner.getType().name().endsWith("_BANNER")) {
                if (isWindmill) {
                    BannerTier t = bannerTierOf(banner);
                    if (bannerTier == null) bannerTier = t;
                    else if (bannerTier != t) { inv.setResult(null); return; } // no mixing tiers
                }
                // Strip the tier marker/auto-name so the blade renders/labels as its base colour.
                ItemStack blade = LargeBannerRecipes.stripTier(banner.asQuantity(1));
                bladeData[i] = blade.serializeAsBytes();
                banners.add(blade);
                hasBanners = true;
            }
        }
        if (!hasBanners) return;

        // Windmills: swap the result to the windmill matching the banners' tier.
        if (isWindmill && bannerTier != null && bannerTier != type.bannerTier()) {
            CustomHeadBlock tierType = windmillForTier(bannerTier);
            if (tierType == null) return;
            result = tierType.createItem(result.getAmount());
        }

        ItemStack newResult = result.clone();
        var newMeta = newResult.getItemMeta();
        var pdc = newMeta.getPersistentDataContainer();
        for (int i = 0; i < 4; i++) {
            if (bladeData[i] != null) {
                pdc.set(CustomBlockRegistry.BLADE_KEYS[i], PersistentDataType.BYTE_ARRAY, bladeData[i]);
            }
        }
        CustomBlockRegistry.applySailLore(newMeta, banners);
        newResult.setItemMeta(newMeta);
        inv.setResult(newResult);
    }

    /** The banner's tier: a HUGE / LARGE marker, else NORMAL (a plain banner). */
    private static BannerTier bannerTierOf(ItemStack banner) {
        if (LargeBannerRecipes.isHugeBanner(banner)) return BannerTier.HUGE;
        if (LargeBannerRecipes.isLargeBanner(banner)) return BannerTier.LARGE;
        return BannerTier.NORMAL;
    }

    /** The windmill block type for a banner tier (the Windmill recipe swaps to it by banner). */
    private CustomHeadBlock windmillForTier(BannerTier tier) {
        String id = switch (tier) {
            case NORMAL -> "mech:windmill";
            case LARGE  -> "mech:large_windmill";
            case HUGE   -> "mech:huge_windmill";
        };
        return registry.getType(id);
    }

    /** Carry captured display data (+ lore) from a placed skull back onto its dropped/picked item.
     *  Dispatches: water-wheel-style ingredient capture, else the windmill banner path. */
    private ItemStack enrichDrop(Block block, CustomHeadBlock type, ItemStack item) {
        if (type.ingredientCapture() != null) {
            if (block.getState() instanceof org.bukkit.block.Skull skull) {
                return type.ingredientCapture().enrich(skull, item);
            }
            return item;
        }
        return enrichItemWithBladeData(block, type, item);
    }

    /** Copy blade banner PDC from a placed skull onto an item + sail lore (windmill drops/pick). */
    private ItemStack enrichItemWithBladeData(Block block, CustomHeadBlock type, ItemStack item) {
        if (type.displayItemResolver() == null) return item;
        if (!(block.getState() instanceof org.bukkit.block.Skull skull)) return item;
        var skullPdc = skull.getPersistentDataContainer();

        boolean hasAny = false;
        for (var key : CustomBlockRegistry.BLADE_KEYS) {
            if (skullPdc.has(key, PersistentDataType.BYTE_ARRAY)) { hasAny = true; break; }
        }
        if (!hasAny) return item;

        ItemStack enriched = item.clone();
        var meta = enriched.getItemMeta();
        CustomBlockRegistry.copyBladePdc(skullPdc, meta.getPersistentDataContainer());
        java.util.List<ItemStack> banners = new java.util.ArrayList<>();
        for (var key : CustomBlockRegistry.BLADE_KEYS) {
            byte[] data = skullPdc.get(key, PersistentDataType.BYTE_ARRAY);
            if (data != null) banners.add(ItemStack.deserializeBytes(data));
        }
        CustomBlockRegistry.applySailLore(meta, banners);
        enriched.setItemMeta(meta);
        return enriched;
    }

    // ──────────────────────────────────────────────────────────────────────
    // World lifecycle — load/save chunk hints per world
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        registry.loadHintsForWorld(event.getWorld().getUID());
    }

    @EventHandler
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent event) {
        registry.saveHintsForWorld(event.getWorld().getUID());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Advancement-based recipe unlocking
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        registry.syncRecipeDiscovery(event.getPlayer());
        // The large/huge banner craft is registered outside the registry, so discover it here too —
        // but only when the bbanners plugin has activated it.
        if (largeBannerRecipes != null && largeBannerRecipes.isActive()) {
            event.getPlayer().discoverRecipe(largeBannerRecipes.recipeKey());
        }
    }

    @EventHandler
    public void onAdvancementDone(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        registry.discoverForAdvancement(event.getPlayer(), event.getAdvancement().getKey().toString());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Interaction — GUI + state transitions
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getPlayer().isSneaking()) {
            // Sneak+wrench on a rotation block → dispatch to onInteract (inspect mode)
            Block sBlock = event.getClickedBlock();
            if (sBlock != null) {
                CustomHeadBlock sType = registry.getTypeFromBlock(sBlock);
                if (sType != null && sType.onInteract() != null
                        && RotationBlocks.isWrench(event.getPlayer().getInventory().getItemInMainHand())) {
                    if (sType.onInteract().apply(sBlock, event)) {
                        event.setCancelled(true);
                    }
                    return;
                }
            }
            return; // sneak+right-click without wrench = place block
        }

        Block block = event.getClickedBlock();
        if (block == null) return;

        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        // Play interact sound (before any GUI/transition handling)
        if (type.interactSound() != null) {
            var s = type.interactSound();
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), s.sound(), s.volume(), s.pitch());
        }

        // Custom interact callback (engine fuel, millstone, etc.)
        if (type.onInteract() != null) {
            if (type.onInteract().apply(block, event)) {
                event.setCancelled(true);
                return;
            }
        }

        // GUI interaction
        if (type.interactGUI() != null) {
            event.setCancelled(true);
            openGUI(event.getPlayer(), type.interactGUI());
            return;
        }

        // Storage interaction
        if (type.storage() != null) {
            event.setCancelled(true);
            registry.openStorage(block, event.getPlayer(), type);
            return;
        }

        // State transitions via interaction trigger
        String currentState = registry.getState(block);
        if (currentState == null) return;

        Material heldItem = event.getPlayer().getInventory().getItemInMainHand().getType();
        Material triggerItem = heldItem == Material.AIR ? null : heldItem;

        // Try specific item first, then wildcard
        CustomHeadBlock.StateTransition transition = type.findTransition(
                new CustomHeadBlock.Trigger.Interact(triggerItem), currentState);
        if (transition == null && triggerItem != null) {
            transition = type.findTransition(
                    new CustomHeadBlock.Trigger.Interact(null), currentState);
        }

        if (transition != null) {
            event.setCancelled(true);

            // Consume held item if transition requires it
            if (transition.consumeItem()) {
                ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
                int needed = transition.consumeAmount();

                if (held.getType().getMaxDurability() > 0 && held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
                    // Damageable item (flint & steel, shears): check durability, not stack amount
                    int remaining = held.getType().getMaxDurability() - dmg.getDamage();
                    if (remaining < needed) return;
                    dmg.setDamage(dmg.getDamage() + needed);
                    held.setItemMeta(dmg);
                    if (dmg.getDamage() >= held.getType().getMaxDurability()) {
                        event.getPlayer().getInventory().setItemInMainHand(null);
                        event.getPlayer().playSound(event.getPlayer().getLocation(),
                                org.bukkit.Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                    }
                } else {
                    // Stackable item: check and reduce amount
                    if (held.getAmount() < needed) return;
                    held.setAmount(held.getAmount() - needed);
                }
            }

            registry.transitionState(block, type, currentState, transition);
        }
    }

    /** Determine which face a skull block is attached to. */
    private static BlockFace getAttachmentFace(Block block) {
        if (block.getType() == Material.PLAYER_WALL_HEAD) {
            if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                return dir.getFacing().getOppositeFace(); // mounted on the opposite face
            }
        }
        return BlockFace.DOWN; // floor head sits on the block below
    }

    private void openGUI(Player player, CustomHeadBlock.InteractGUI gui) {
        switch (gui) {
            case WORKBENCH -> player.openWorkbench(null, true);
            case ANVIL -> player.openAnvil(null, true);
            case ENCHANTING -> player.openEnchanting(null, true);
            case SMITHING -> player.openSmithingTable(null, true);
            case LOOM -> player.openLoom(null, true);
            case STONECUTTER -> player.openStonecutter(null, true);
            case GRINDSTONE -> player.openGrindstone(null, true);
            case CARTOGRAPHY -> player.openCartographyTable(null, true);
            case ENDERCHEST -> player.openInventory(player.getEnderChest());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Storage inventory close — save back to PDC
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof StorageHolder holder) {
            // Delay by 1 tick so Bukkit finishes removing the viewer first
            getServer().getScheduler().runTask(this, () -> registry.onStorageClosed(holder));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Stonecutter interception for head-to-head recipes
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStonecutterClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        // Handle clicks in our custom selection menu (InventoryHolder pattern)
        if (event.getInventory().getHolder() instanceof StonecutterSelectHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= holder.recipes().size()) return;

            var recipe = holder.recipes().get(slot);
            handleStonecutterCraft(player, holder.inputBlockId(), recipe);
            return;
        }

        // Handle clicks in the vanilla stonecutter
        if (event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.STONECUTTER) return;
        if (!(event.getInventory() instanceof org.bukkit.inventory.StonecutterInventory inv)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot == 1) {
            // Clicking the result slot — check if it's our hint item
            ItemStack result = inv.getResult();
            if (result != null && result.getType() == Material.STONECUTTER && result.hasItemMeta()) {
                String tag = result.getItemMeta().getPersistentDataContainer()
                        .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
                if (tag != null && tag.startsWith("_sc_hint:")) {
                    event.setCancelled(true);
                    openStonecutterSelectMenu(player, tag.substring("_sc_hint:".length()));
                    return;
                }
            }
        }

        // Input changed — schedule hint update
        getServer().getScheduler().runTask(this, () -> updateStonecutterHint(inv));
    }

    @EventHandler
    public void onStonecutterDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.STONECUTTER) return;
        if (!(event.getInventory() instanceof org.bukkit.inventory.StonecutterInventory inv)) return;

        if (event.getRawSlots().contains(0)) {
            getServer().getScheduler().runTask(this, () -> updateStonecutterHint(inv));
        }
    }

    private void updateStonecutterHint(org.bukkit.inventory.StonecutterInventory inv) {
        ItemStack input = inv.getInputItem();
        if (input == null || input.getType() != Material.PLAYER_HEAD || !input.hasItemMeta()) {
            clearStonecutterHint(inv);
            return;
        }

        String blockId = input.getItemMeta().getPersistentDataContainer()
                .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (blockId == null) { clearStonecutterHint(inv); return; }

        var recipes = registry.getStonecutterRecipesForInput(blockId);
        if (recipes.isEmpty()) { clearStonecutterHint(inv); return; }

        ItemStack hint = new ItemStack(Material.STONECUTTER);
        var meta = hint.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Click to select output", NamedTextColor.GREEN));
            meta.lore(List.of(
                    Component.text(recipes.size() + " recipes available", NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Click to open selection menu", NamedTextColor.YELLOW)));
            meta.getPersistentDataContainer().set(CustomBlockRegistry.BLOCK_TYPE_KEY,
                    PersistentDataType.STRING, "_sc_hint:" + blockId);
            hint.setItemMeta(meta);
        }
        inv.setResult(hint);
    }

    private void clearStonecutterHint(org.bukkit.inventory.StonecutterInventory inv) {
        ItemStack result = inv.getResult();
        if (result == null) return;
        if (result.getType() == Material.STONECUTTER && result.hasItemMeta()) {
            String tag = result.getItemMeta().getPersistentDataContainer()
                    .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (tag != null && tag.startsWith("_sc_hint:")) {
                inv.setResult(null);
            }
        }
    }

    private void openStonecutterSelectMenu(Player player, String inputBlockId) {
        var recipes = registry.getStonecutterRecipesForInput(inputBlockId);
        if (recipes.isEmpty()) {
            player.sendMessage(Component.text("No stonecutter recipes available.", NamedTextColor.RED));
            return;
        }

        StonecutterSelectHolder holder = new StonecutterSelectHolder(inputBlockId, recipes);

        CustomHeadBlock inputType = registry.getType(inputBlockId);
        Component title = Component.text("Stonecutter", NamedTextColor.DARK_PURPLE);
        if (inputType != null && inputType.name() != null) {
            title = title.append(Component.text(": ")).append(inputType.name());
        }

        // Items area (up to 45) + bottom row for navigation/info
        int itemSlots = Math.min(recipes.size(), 45);
        int rows = Math.max(1, (itemSlots + 8) / 9) + 1; // +1 row for bottom bar
        int size = rows * 9;

        org.bukkit.inventory.Inventory inv = getServer().createInventory(holder, size, title);
        holder.setInventory(inv);

        // Populate output items
        for (int i = 0; i < itemSlots; i++) {
            var recipe = recipes.get(i);
            CustomHeadBlock outputType = registry.getType(recipe.outputBlockId());
            if (outputType == null) continue;
            inv.setItem(i, outputType.createItem(recipe.amount()));
        }

        // Bottom bar: filler + input display + close button
        int bottomStart = size - 9;
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) { fillerMeta.displayName(Component.empty()); filler.setItemMeta(fillerMeta); }
        for (int i = bottomStart; i < size; i++) inv.setItem(i, filler);

        // Input item display (center of bottom row)
        if (inputType != null) inv.setItem(bottomStart + 4, inputType.createItem(1));

        player.openInventory(inv);
    }

    private void handleStonecutterCraft(Player player, String inputBlockId,
                                         CustomBlockRegistry.HeadStonecutterRecipe recipe) {
        // Find input in player's inventory
        int inputSlot = -1;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) continue;
            String itemBlockId = item.getItemMeta().getPersistentDataContainer()
                    .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (inputBlockId.equals(itemBlockId)) {
                inputSlot = i;
                break;
            }
        }

        if (inputSlot == -1) {
            player.sendMessage(Component.text("You need the input item in your inventory.", NamedTextColor.RED));
            return;
        }

        // Consume 1 input
        ItemStack inputItem = player.getInventory().getItem(inputSlot);
        if (inputItem.getAmount() > 1) {
            inputItem.setAmount(inputItem.getAmount() - 1);
        } else {
            player.getInventory().setItem(inputSlot, null);
        }

        // Give result
        CustomHeadBlock outputType = registry.getType(recipe.outputBlockId());
        if (outputType == null) return;
        ItemStack result = outputType.createItem(recipe.amount());
        var leftover = player.getInventory().addItem(result);
        for (ItemStack lf : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), lf);
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.UI_STONECUTTER_TAKE_RESULT, 1f, 1f);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Neighbor changes — only for blocks that declared reactsToNeighbors
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block changed = event.getBlock();
        // Fast path: check if any neighbor is in the reactive set before doing expensive lookups
        for (BlockFace face : Faces.CARDINAL) {
            Block neighbor = changed.getRelative(face);
            if (!registry.isNeighborReactive(neighbor)) continue;
            // Only now do the expensive getTypeFromBlock lookup
            CustomHeadBlock type = registry.getTypeFromBlock(neighbor);
            if (type != null && type.onNeighborChange() != null) {
                type.onNeighborChange().accept(neighbor, face.getOppositeFace());
            }
            if (type != null && type.displayTransformResolver() != null) {
                String state = registry.getState(neighbor);
                registry.resolveDisplayTransforms(neighbor, type, state);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Mechanism entity protection + interaction
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        for (String tag : event.getEntity().getScoreboardTags()) {
            if (tag.startsWith("corelib:mech:")) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityInteract(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Shulker shulker)) return;
        MechanismRegistry.ColliderRef ref = mechanismRegistry.getColliderRef(shulker);
        if (ref == null) return;

        event.setCancelled(true);
        Mechanism mech = ref.mechanism();
        int blockIndex = ref.blockIndex();
        MechanismBlockData mb = mech.getBlock(blockIndex);

        // Storage access
        if (mb.storage() != null) {
            event.getPlayer().openInventory(mb.storage());
            return;
        }

        // Custom block state transitions (interact trigger)
        if (mb.customTypeId == null) return;
        CustomHeadBlock type = registry.getType(mb.customTypeId);
        if (type == null) return;

        String currentState = mb.customState();
        if (currentState == null) return;

        var trigger = new CustomHeadBlock.Trigger.Interact(
            event.getPlayer().getInventory().getItemInMainHand().getType() == Material.AIR
                ? null : event.getPlayer().getInventory().getItemInMainHand().getType());
        var transition = type.findTransition(trigger, currentState);
        if (transition != null) {
            mech.setBlockState(blockIndex, transition.toState());
            if (transition.sound() != null) {
                shulker.getWorld().playSound(shulker.getLocation(),
                    transition.sound(), transition.volume(), transition.pitch());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Commands: /corelib give <id> [amount] | /corelib list
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("defcorelib")) return false;
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /defcorelib <give|give_demo|give_demo_rotation|list|colliders|reloadbanners|cleanorphans>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage(Component.text("Registered blocks:", NamedTextColor.GOLD));
                for (CustomHeadBlock type : registry.allTypes()) {
                    sender.sendMessage(Component.text("  " + type.fullId(), NamedTextColor.WHITE));
                }
            }
            case "give" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /defcorelib give <id> [amount]", NamedTextColor.YELLOW));
                    return true;
                }
                String blockId = args[1];
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("Invalid amount: " + args[2], NamedTextColor.RED));
                        return true;
                    }
                }

                // Friendly alias: `give glue` → the registry item mech:glue_item.
                if (blockId.equalsIgnoreCase("glue") || blockId.equalsIgnoreCase("mech:glue")) {
                    blockId = "mech:glue_item";
                }

                // Try with namespace prefix, fall back to searching all namespaces
                CustomHeadBlock type = registry.getType(blockId);
                if (type == null) {
                    for (CustomHeadBlock t : registry.allTypes()) {
                        if (t.typeId().equals(blockId)) {
                            type = t;
                            break;
                        }
                    }
                }

                if (type == null) {
                    sender.sendMessage(Component.text("Unknown block: " + blockId, NamedTextColor.RED));
                    return true;
                }

                ItemStack item = type.createItem(amount);

                var overflow = player.getInventory().addItem(item);
                for (ItemStack lf : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), lf);
                }
                sender.sendMessage(Component.text("Gave " + amount + "x " + type.fullId(), NamedTextColor.GREEN));
            }
            case "give_demo" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                int count = 0;
                for (CustomHeadBlock type : registry.allTypes()) {
                    var overflow = player.getInventory().addItem(type.createItem(1));
                    for (ItemStack lf : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), lf);
                    }
                    count++;
                }
                sender.sendMessage(Component.text("Gave " + count + " demo blocks", NamedTextColor.GREEN));
            }
            case "give_demo_rotation" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                int count = 0;
                for (CustomHeadBlock type : registry.allTypes()) {
                    if (type.namespace().equals("mech")) {
                        var overflow = player.getInventory().addItem(type.createItem(1));
                        for (ItemStack lf : overflow.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), lf);
                        }
                        count++;
                    }
                }
                // The wrench is now a rotation-namespace YAML item, so it's included above.
                sender.sendMessage(Component.text("Gave " + count + " rotation items (incl. wrench)", NamedTextColor.GREEN));
            }
            case "colliders" -> {
                boolean enabled = !mechanismRegistry.isColliderGlowEnabled();
                mechanismRegistry.setColliderGlow(enabled);
                sender.sendMessage(Component.text("Collider glow " + (enabled ? "ON" : "OFF"),
                    enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "reloadbanners" -> {
                bannerManager.reloadConfig();
                sender.sendMessage(Component.text("Banner config reloaded", NamedTextColor.GREEN));
            }
            case "cleanorphans" -> {
                boolean confirm = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
                CustomBlockRegistry.OrphanScanResult result = registry.scanOrphanedDisplays(confirm);
                if (confirm) {
                    sender.sendMessage(Component.text("Removed " + result.orphans() + " orphaned display "
                            + (result.orphans() == 1 ? "entity" : "entities") + ".", NamedTextColor.GREEN));
                } else if (result.orphans() == 0) {
                    sender.sendMessage(Component.text("No orphaned displays found ("
                            + result.live() + " live checked).", NamedTextColor.GREEN));
                } else {
                    String skipped = result.skippedUnloaded() > 0
                            ? ", " + result.skippedUnloaded() + " in unloaded chunks skipped" : "";
                    sender.sendMessage(Component.text("Found " + result.orphans() + " orphaned display "
                            + (result.orphans() == 1 ? "entity" : "entities") + " (" + result.live()
                            + " live checked" + skipped + "). Run /defcorelib cleanorphans confirm to remove.",
                            NamedTextColor.YELLOW));
                    for (String sample : result.samples()) {
                        sender.sendMessage(Component.text("  " + sample, NamedTextColor.GRAY));
                    }
                }
            }
            case "showcase" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("build")) {
                    if (args.length < 3) {
                        sender.sendMessage(Component.text("Usage: /defcorelib showcase build <id>", NamedTextColor.YELLOW));
                        return true;
                    }
                    ShowcaseSpec spec = showcases.get(args[2]);
                    if (spec == null) {
                        sender.sendMessage(Component.text("Unknown showcase: " + args[2], NamedTextColor.RED));
                        return true;
                    }
                    int placed = showcaseBuilder.build(spec, player.getLocation().getBlock().getLocation());
                    sender.sendMessage(Component.text("Building '" + spec.name + "' (" + placed
                        + " blocks) at your feet.", NamedTextColor.GREEN));
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("anchor")) {
                    // Dev export: mark the block you look at as the showcase origin, then glue-mark the rest.
                    Block target = player.getTargetBlockExact(8);
                    if (target == null || target.getType().isAir()) {
                        sender.sendMessage(Component.text("Look at the block to use as the showcase origin.",
                            NamedTextColor.RED));
                        return true;
                    }
                    glueAuthoring.startBlockSession(player, target);
                    sender.sendMessage(Component.text("Showcase origin set. Mark the machine's blocks with the "
                        + "glue item, then /defcorelib showcase export <id>.", NamedTextColor.GREEN));
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("export")) {
                    if (args.length < 3) {
                        sender.sendMessage(Component.text("Usage: /defcorelib showcase export <id>",
                            NamedTextColor.YELLOW));
                        return true;
                    }
                    Anchor anchor = glueAuthoring.sessionAnchor(player);
                    if (anchor == null) {
                        sender.sendMessage(Component.text("No glue session — run /defcorelib showcase anchor "
                            + "first, then mark the blocks.", NamedTextColor.RED));
                        return true;
                    }
                    List<Block> glued = glueManager.resolveStructure(anchor);
                    try {
                        java.nio.file.Path out = getDataFolder().toPath().resolve("showcase-export.yml");
                        int n = ShowcaseExporter.export(args[2], anchor, glued, registry, out);
                        sender.sendMessage(Component.text("Exported '" + args[2] + "' (" + n + " blocks) → "
                            + out.toAbsolutePath(), NamedTextColor.GREEN));
                    } catch (Exception e) {
                        sender.sendMessage(Component.text("Export failed: " + e.getMessage(), NamedTextColor.RED));
                        getLogger().warning("showcase export failed: " + e);
                    }
                } else {
                    sender.sendMessage(Component.text("Showcases:", NamedTextColor.GOLD));
                    for (ShowcaseSpec s : showcases.values()) {
                        sender.sendMessage(Component.text("  " + s.id + " — " + s.name, NamedTextColor.WHITE));
                    }
                    sender.sendMessage(Component.text("Build: showcase build <id>  |  Export a built machine: "
                        + "showcase anchor (look at origin) → glue-mark → showcase export <id>", NamedTextColor.GRAY));
                }
            }
            case "gluetest" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                Block target = player.getTargetBlockExact(8);
                if (target == null || registry.getTypeFromBlock(target) == null) {
                    sender.sendMessage(Component.text("Look at a custom-block (skull) anchor within 8 blocks",
                        NamedTextColor.RED));
                    return true;
                }
                Anchor anchor = new BlockAnchor(target, () -> true);
                if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
                    glueManager.unglueAll(anchor);
                    sender.sendMessage(Component.text("Glue cleared", NamedTextColor.GREEN));
                } else if (glueManager.hasGlue(anchor)) {
                    List<Block> resolved = glueManager.resolveStructure(anchor);
                    sender.sendMessage(Component.text("Glued: " + glueManager.offsets(anchor).size()
                        + " offsets, " + (resolved == null ? 0 : resolved.size())
                        + " present in world. (/defcorelib gluetest clear to clear)", NamedTextColor.AQUA));
                } else {
                    List<Block> planks = FloodFill.component(target, false,
                        b -> b.getType() == Material.OAK_PLANKS && registry.getTypeFromBlock(b) == null,
                        glueManager.maxSize(), null);
                    glueManager.setStructure(anchor, planks);
                    sender.sendMessage(Component.text("Froze " + planks.size()
                        + " connected oak planks as glue", NamedTextColor.GREEN));
                }
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand: " + args[0], NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("defcorelib")) return List.of();
        if (args.length == 1) {
            return List.of("give", "give_demo", "give_demo_rotation", "list", "colliders", "reloadbanners", "cleanorphans", "gluetest", "showcase").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cleanorphans")) {
            return List.of("confirm").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            return registry.allTypes().stream()
                    .map(CustomHeadBlock::fullId)
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("showcase")) {
            return List.of("build", "anchor", "export", "list").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("showcase") && args[1].equalsIgnoreCase("build")) {
            return showcases.keySet().stream()
                    .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
