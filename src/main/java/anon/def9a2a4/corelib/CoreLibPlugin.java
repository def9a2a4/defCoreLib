package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class CoreLibPlugin extends JavaPlugin implements Listener {

    private static CoreLibPlugin instance;
    private CustomBlockRegistry registry;
    private MechanismRegistry mechanismRegistry;
    private DrivenDemo drivenDemo; // throwaway Phase-0 smoothness spike (/defcorelib driventest)
    private MechanismMinecartManager mechanismMinecartManager;
    private CustomCartManager customCartManager;
    private CartTrainManager cartTrainManager;
    private CartRailsManager cartRailsManager;
    private GlueManager glueManager;
    private GlueAuthoring glueAuthoring;
    private RotationConfig rotationConfig;
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
        // Preserve decorated block-entity state (sign text, skull profile, container name, …) through a
        // mechanism move + recovery. Consumers can register more via registerBlockSnapshotProvider.
        registry.registerBlockSnapshotProvider(new DefaultBlockSnapshotProvider());
        registry.startTasks();
        mechanismRegistry = new MechanismRegistry(this, registry);
        mechanismRegistry.startTasks();
        getServer().getPluginManager().registerEvents(this, this);

        // Load vanilla-block collider shapes (slabs, stairs, fences, ...) for mechanism assembly.
        try (InputStream colliderStream = getResource("colliders.yml")) {
            if (colliderStream != null) {
                mechanismRegistry.loadColliders(colliderStream);
            }
        } catch (IOException ignored) {}

        // Load per-block inertial masses (heavier mechanism => slower motion).
        try (InputStream massStream = getResource("mass.yml")) {
            if (massStream != null) {
                mechanismRegistry.loadMasses(massStream);
            }
        } catch (IOException ignored) {}

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

        // Railbound content: fuel carts, junctions, destructor rail (recipes gated behind the
        // `railbound` companion plugin). Runtime behaviour is wired below via the cart/rail managers.
        try (InputStream cartsStream = getResource("carts-blocks.yml")) {
            if (cartsStream != null) {
                BlockLoader.load(cartsStream, registry, getLogger());
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
        // Kept as a field too: the public rotationFacingAt(Block) API needs the mechanism metadata
        // (specifically blows_outward) to answer for a world block.
        RotationConfig rotConfig = new RotationConfig();
        this.rotationConfig = rotConfig;
        try (InputStream configStream = getResource("rotation-config.yml")) {
            if (configStream != null) {
                rotConfig.load(configStream, getLogger());
            }
        } catch (IOException ignored) {}
        mechanismRegistry.setDynamicLights(rotConfig.dynamicLights);
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
        MachineRecipes sieveRecipes = new MachineRecipes();
        try (InputStream sieveStream = getResource("sieve-recipes.yml")) {
            if (sieveStream != null) {
                sieveRecipes.load(sieveStream, getLogger());
            }
        } catch (IOException ignored) {}
        RotationBlocks.register(registry, rotationNetwork, fuelManager, millRecipes, pressRecipes,
            sieveRecipes, rotConfig);

        // Rotation power on moving mechanisms: each assembled mechanism carries its own
        // rotation network (engine burns travelling fuel; drill/placer/suction/millstone/press/fan
        // act at their live positions, exchanging items with neighboring on-board inventories).
        // Data-driven via rotation-config.yml `mechanism:`.
        mechanismRegistry.setRotationDriver(
            new MechanismRotationDriver(registry, fuelManager, rotConfig, millRecipes, pressRecipes,
                sieveRecipes));

        // Anchor-owned block selection ("glue") — shared by doors/rotators (wired in D3).
        // The glue item itself is declared in corelib-items.yml (mech:glue_item). The registry
        // powers the derived sticky auto-glue (StickySpread) at resolve time.
        glueManager = new GlueManager(rotConfig.glueMaxSize, registry);
        // The engine's landing-glue prune (GlueManager.rebindLanded for captured nested anchors) must
        // bound its sticky-closure walk by the SAME cap authoring used, else it over-prunes.
        mechanismRegistry.setGlueMaxSize(rotConfig.glueMaxSize);
        glueAuthoring = new GlueAuthoring(this, registry, glueManager,
            rotConfig.glueOutlineInterval, rotConfig.glueOutlineRange, rotConfig.glueSessionTimeout);
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
        DoorDemo doorDemo = new DoorDemo(this, registry, mechanismRegistry, glueManager);
        doorDemo.register();
        drivenDemo = new DrivenDemo(this, mechanismRegistry, registry); // Phase-0 driven-smoothness spike
        RotationRotator rotationRotator = new RotationRotator(this, registry, rotationNetwork, mechanismRegistry, glueManager);
        rotationRotator.register();
        ExtendablePistonManager pistonManager =
            new ExtendablePistonManager(this, registry, rotationNetwork, mechanismRegistry, glueManager, rotConfig);
        pistonManager.register();
        ChainHoistManager chainHoistManager =
            new ChainHoistManager(this, registry, rotationNetwork, mechanismRegistry, glueManager, rotConfig);
        chainHoistManager.register();
        getServer().getPluginManager().registerEvents(chainHoistManager, this);
        glueAuthoring.setChainHoistManager(chainHoistManager);
        // Transitive glue capture needs to recognise a nested anchor and build the right Anchor for it
        // (hoists get the dynamic-origin HoistAnchor). Wired here, after the hoist manager exists.
        glueManager.setAnchorFactory(b ->
            Anchors.isAnchorType(b, registry) ? Anchors.forBlock(b, registry, chainHoistManager) : null);
        // Refuse to CARRY a mid-motion anchor into another mechanism (a swinging rotator/door head, a
        // mid-stroke piston core, a moving hoist head all stay in-world and would be force-disassembled if
        // an outer mover aired them out). Each mover checks its final captured list against this before any
        // side effect. Minecart is absent by design — its cargo all airs out, leaving no in-world anchor.
        mechanismRegistry.setAnchorInMotion(b ->
            rotationRotator.isMoving(b) || doorDemo.isMoving(b)
                || chainHoistManager.isMoving(b) || pistonManager.isMoving(b));
        mechanismMinecartManager = new MechanismMinecartManager(this, registry, mechanismRegistry, glueManager);
        glueAuthoring.setMinecartManager(mechanismMinecartManager);
        mechanismMinecartManager.register();
        getServer().getPluginManager().registerEvents(mechanismMinecartManager, this);

        // Railbound fuel carts + minecart trains: the entire runtime (listeners + tick tasks) is
        // gated behind the Railbound companion, which calls enableCarts()/disableCarts() from its
        // own enable/disable (mirroring enableRecipes("railbound")). Without the companion the carts' block/item
        // types still load (above), but no cart/coupling behaviour is active — minecarts stay vanilla.

        // Banner systems
        bannerManager = new BannerManager(this);
        getServer().getPluginManager().registerEvents(bannerManager, this);
        // Banners ride mechanisms: capture at assembly, re-attach on landing. Always on (like the
        // cleanup handlers) — placed banners must keep riding even if bbanners is later removed.
        mechanismRegistry.setBannerManager(bannerManager);
        largeBannerRecipes = new LargeBannerRecipes(this);
        getServer().getPluginManager().registerEvents(largeBannerRecipes, this);

        // Register recipes after all blocks are loaded
        registry.finalizeLoading();

        // Restore custom blocks in chunks that were already loaded before our listener existed (spawn
        // and force-loaded chunks load before plugins enable, and being resident never fire
        // ChunkLoad/EntitiesLoad again). Deferred to the first tick on purpose: mech/pipes register
        // their types in their own onEnable, which runs after ours, so sweeping inline would miss them.
        getServer().getScheduler().runTask(this, () -> {
            int swept = registry.restoreLoadedChunks();
            if (swept > 0) {
                getLogger().info("Restored custom blocks in " + swept + " already-loaded chunk(s)");
            }
            // Same first-tick timing for persisted mechanisms: chunks resident before enable never re-fire
            // EntitiesLoad, so recover (and orphan-sweep) them here. Running at first tick means a consumer's
            // MechanismAssembleEvent listener (registered in its own onEnable) is already live to catch them.
            if (mechanismRegistry != null) {
                int mechSwept = mechanismRegistry.restoreLoadedChunks();
                if (mechSwept > 0) {
                    getLogger().info("Swept " + mechSwept + " already-loaded chunk(s) for persisted mechanisms");
                }
            }
        });

        // (dough→bread and seed-oil→lantern recipes are now declared in YAML — see custom-items.yml —
        //  and flow through the tracked registerRecipesForType path with an `output:` override.)

        // Docs export mode (-Ddefcorelib.export=<path>): on ServerLoadEvent, dump every block's
        // ground-truth placed display data to JSON and shut the server down. Inert otherwise.
        DisplayExporter.armIfRequested(this, registry, showcaseBuilder, showcases.values(),
                rotationNetwork, fuelManager);

        // Headless showcase integration tests (-Ddefcorelib.showcaseTest=true): build, run, assert, exit.
        ShowcaseRunner.armIfRequested(this, registry, rotationNetwork, fuelManager, showcaseBuilder,
                showcases.values(), rotationRotator, chainHoistManager, pistonManager);

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
        disableCarts();
        if (drivenDemo != null) {
            drivenDemo.shutdown();
        }
        if (mechanismRegistry != null) {
            mechanismRegistry.shutdown();
        }
        if (registry != null) {
            registry.shutdown();
        }
        instance = null;
    }

    /**
     * Activate the Railbound runtime — the fuel-cart manager and the minecart-train manager
     * (listeners + per-tick tasks). Called by the Railbound companion's onEnable (which runs
     * after ours, so worlds/chunks are loaded and register()'s scan adopts existing carts). Idempotent.
     */
    public void enableCarts() {
        if (customCartManager != null) return;   // already active
        CartConfig cartConfig = new CartConfig();
        try (InputStream cartsCfgStream = getResource("carts-config.yml")) {
            if (cartsCfgStream != null) {
                cartConfig.load(cartsCfgStream, getLogger());
            }
        } catch (IOException ignored) {}
        customCartManager = new CustomCartManager(this, registry, cartConfig);
        customCartManager.register();
        getServer().getPluginManager().registerEvents(customCartManager, this);
        // Minecart trains (chain coupling + position-driven movement). Reads engine/fuel state from the
        // cart manager; owns movement for all coupled carts and solo blast-furnace engines.
        cartTrainManager = new CartTrainManager(this, cartConfig, customCartManager);
        customCartManager.setTrainManager(cartTrainManager);
        cartTrainManager.register();
        getServer().getPluginManager().registerEvents(cartTrainManager, this);
        // Special rails (junction + destructor). Event-driven for physics carts; the train manager calls
        // into it for position-driven carts. Identity/persistence come from the bare-block chunk index.
        cartRailsManager = new CartRailsManager(this, registry, customCartManager, cartTrainManager, cartConfig);
        cartRailsManager.installOverlays();   // orient the controller arrow along the track
        cartTrainManager.setRailsManager(cartRailsManager);
        getServer().getPluginManager().registerEvents(cartRailsManager, this);
    }

    /**
     * Tear down the Railbound runtime: cancel tick tasks + persist/park (shutdown()), unregister
     * the listeners (shutdown() does not), and drop the managers so the null-guarded entity-load/unload
     * callbacks no-op. Called from onDisable and from the companion's onDisable. Null-safe / idempotent.
     */
    public void disableCarts() {
        if (customCartManager != null) {
            customCartManager.shutdown();
            HandlerList.unregisterAll(customCartManager);
            customCartManager = null;
        }
        if (cartTrainManager != null) {
            cartTrainManager.shutdown();
            HandlerList.unregisterAll(cartTrainManager);
            cartTrainManager = null;
        }
        if (cartRailsManager != null) {
            HandlerList.unregisterAll(cartRailsManager);
            cartRailsManager = null;
        }
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
    // Public glue API
    //
    // Glue is otherwise entirely internal. This is the narrow surface a sibling plugin needs to make
    // one of ITS blocks a glue anchor and to read back what a player glued to it. Everything else —
    // the Glue Brush, the particle outline, cuboid fill, brush durability, capture at assembly,
    // persistence across a restart, and the rotated rebind at landing — then works unchanged, because
    // an ExternalAnchor stores offsets in the same skull PDC every engine anchor uses.
    //
    // Lifecycle contract: while the anchor is at rest its skull PDC is authoritative. While it is
    // riding a mechanism the offsets live in MechanismBlockData.glueOffsets (captured before air-out,
    // serialized with the mechanism) and are re-stamped on landing. Callers must not keep their own
    // copy — two sources of truth, rotated by different code, is how glue ends up bound to the wrong
    // blocks.
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Register a provider that claims blocks as glue anchors on this plugin's behalf. Re-registering
     * the same {@code pluginId} replaces the previous provider.
     *
     * <p>Engine anchor types are matched first, so a provider can never shadow a rotator, door,
     * piston head or hoist.
     *
     * @param pluginId stable identifier for the owning plugin (used only for replace-on-reregister)
     */
    public void registerAnchorProvider(String pluginId, ExternalAnchor.Provider provider) {
        Anchors.registerProvider(pluginId, provider);
    }

    /**
     * Open (or toggle closed) a Glue Brush authoring session on an arbitrary block, bypassing the
     * anchor-type gate. The player still needs the brush in hand for the click handlers to do
     * anything; this only seeds the session.
     */
    public void openGlueSession(Player player, Block anchorBlock) {
        if (glueAuthoring != null) glueAuthoring.startBlockSession(player, anchorBlock);
    }

    /** Raw glued offsets (flat x,y,z triples relative to {@code anchorBlock}), or null if none. */
    public int @Nullable [] readGlueOffsets(Block anchorBlock) {
        return new BlockAnchor(anchorBlock, () -> true).readOffsets();
    }

    /**
     * Overwrite the glued offsets. No connectivity check — the caller is authoritative. Intended for
     * an owner plugin re-basing its own anchor; ordinary authoring goes through the brush.
     */
    public void writeGlueOffsets(Block anchorBlock, int[] offsets) {
        new BlockAnchor(anchorBlock, () -> true).writeOffsets(offsets);
    }

    /**
     * Resolve this anchor's glue to the blocks that are actually present right now, or null when the
     * anchor has no glue. Cells that are now air are skipped; the derived sticky closure (slime/honey
     * grabs, same-wood frame bonds) is included, exactly as a mover would see it. An empty list means
     * "glued, but everything is gone".
     */
    public @Nullable List<Block> resolveGlue(Block anchorBlock) {
        if (glueManager == null) return null;
        ExternalAnchor ext = Anchors.externalFor(anchorBlock);
        Anchor anchor = ext != null ? new ProvidedAnchor(ext) : new BlockAnchor(anchorBlock, () -> true);
        return glueManager.resolveStructure(anchor);
    }

    /** Remove all glue from this anchor. */
    public void clearGlue(Block anchorBlock) {
        new BlockAnchor(anchorBlock, () -> true).clearOffsets();
    }

    /** The cap on a single glued selection ({@code glue.max-size}), so callers can report it. */
    public int glueMaxSize() {
        return glueManager != null ? glueManager.maxSize() : 0;
    }

    /**
     * Every banner tier hosted on a block inside {@code region}, keyed by host block.
     *
     * <p>Large and huge banners are tagged {@code ItemDisplay} entities attached to an otherwise
     * untouched host block — there is no block state to inspect — so a material test can never see
     * them. This is the only way to find them.
     *
     * <p>Two things to know: the display is spawned in the neighbour cell toward the face it hangs on,
     * so <b>expand your region by at least 3 blocks</b> or you will miss the banners on a structure's
     * own outer faces; and only loaded chunks are searched.
     *
     * <p>A vanilla banner BLOCK also reports here, as {@link BannerTier#NORMAL} — ignore that tier if
     * you already count banner blocks by material, or you will count them twice.
     */
    public Map<Block, List<BannerTier>> bannerTiersIn(World world, BoundingBox region) {
        return BannerManager.bannerTiersIn(world, region);
    }

    /**
     * The direction a rotation machine acts along, for a block sitting in the WORLD (not riding a
     * mechanism) — the static-world sibling of {@link Mechanism#rotationFacing(int)}.
     *
     * <p>Needed because the same consumer usually has to describe a machine both while it is riding
     * and while it is parked: a ship shows its propulsion stats from the wheel menu while docked, when
     * there is no mechanism to ask.
     *
     * <p>Derived from the block's own data + custom state, so it is correct the instant the block is
     * placed — unlike the engine's internal facing PDC key, which is written lazily and may not exist
     * yet.
     *
     * @return the world-space facing, or null when the block is not a custom rotation block
     */
    public @Nullable BlockFace rotationFacingAt(Block block) {
        if (registry == null) return null;
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return null;
        BlockFace stored = RotationBlocks.storedFacing(block.getBlockData(), registry.getState(block));
        RotationConfig.MechRotationMeta meta =
            rotationConfig == null ? null : rotationConfig.mechMeta(type.fullId());
        // Same outward flip the mechanism path applies: a floor-mounted fan/propeller acts UPWARD.
        if (meta != null && meta.blowsOutward() && stored == BlockFace.DOWN) return BlockFace.UP;
        return stored;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chunk lifecycle — single scan
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Re-attempt recipes withheld by {@code requires_plugin} when any plugin enables.
     *
     * <p>Server load order otherwise decides silently whether a gated recipe exists: core's startup
     * pass runs once, and a plugin that enables after it would never be noticed. No-op once nothing
     * is withheld.
     */
    @EventHandler
    public void onPluginEnable(org.bukkit.event.server.PluginEnableEvent event) {
        if (registry != null) registry.retryWithheldRecipes();
    }

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
        if (customCartManager != null) {
            customCartManager.scanChunk(event.getChunk());
        }
        if (cartTrainManager != null) {
            cartTrainManager.scanChunk(event.getChunk());
        }
        // Recover persisted mechanisms present in this chunk (discovered from their entities' tags) and reap
        // orphaned mechanism entities from previous sessions — one unified entity pass; recovery's in-flight
        // guard (mechIdsBeingRecovered + hasMetadata) protects the entities it adopts from the reap.
        if (mechanismRegistry != null) {
            mechanismRegistry.recoverMechanismsInChunk(event.getChunk());
        }
        // Entity-hosted custom blocks (bare chain shafts, via registered ChunkRestorers) + the
        // chunk-hint wipe decision. Must run here, not at ChunkLoadEvent: the rod ItemDisplay that
        // carries a shaft's identity only exists once entities have loaded.
        registry.onEntitiesLoad(event.getChunk());

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

    // Paper EntitiesUnloadEvent: fires as a chunk's entities unload but while the chunk is still
    // loaded and its blocks are writable — the safe window to cleanly disassemble an assembled
    // mechanism cart (return its blocks to the world) before it becomes an orphaned ghost.
    @EventHandler
    public void onEntitiesUnload(org.bukkit.event.world.EntitiesUnloadEvent event) {
        if (mechanismMinecartManager != null) {
            mechanismMinecartManager.onEntitiesUnload(event.getEntities());
        }
        if (customCartManager != null) {
            customCartManager.onEntitiesUnload(event.getEntities());
        }
        if (cartTrainManager != null) {
            cartTrainManager.onEntitiesUnload(event.getEntities());
        }
        // Park persisted mechanisms anchored to this chunk so they re-recover (and re-fire the recovered
        // MechanismAssembleEvent) when the chunk reloads, instead of becoming stale zombies in activeMechanisms.
        if (mechanismRegistry != null) {
            mechanismRegistry.onEntitiesUnload(event.getChunk());
        }
    }

    /**
     * Refuse to let any mechanism entity travel through a portal. A mechanism is not built to change world:
     * its colliders are NOT passengers of the vehicle (they are free carriers teleported to the pivot each
     * tick), so a portalling vehicle would take its displays and strand the colliders behind — a body split
     * across two worlds. A persisted mechanism additionally orphans its state file and chunk-index entry in
     * the world it left, because save/remove only ever touch the mechanism's CURRENT world; every later load
     * of that stale chunk then runs a futile recovery sweep.
     *
     * <p>Cancelling leaves the entity sitting in the portal; the event simply re-fires and is re-cancelled.
     * This closes the portal route only — an admin {@code /tp} or a plugin teleport can still move a
     * mechanism cross-world and hit the orphan above, which would need the persistence paths fixed instead.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMechanismEntityPortal(org.bukkit.event.entity.EntityPortalEvent event) {
        if (BasicMechanism.isMechanismEntity(event.getEntity())) event.setCancelled(true);
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

        if (!registry.isNamespaceEnabledInWorld(type.namespace(), block.getWorld().getName())) {
            event.setCancelled(true);
            return;
        }

        // Inventory-only items (juices, oils, wrench): never place as a block.
        if (!type.placeable()) {
            event.setCancelled(true);
            return;
        }

        boolean isAlreadySkull = block.getType() == Material.PLAYER_HEAD
                || block.getType() == Material.PLAYER_WALL_HEAD;

        // Compute attachment face. Ceiling detection runs BEFORE the skull/non-skull split: a head
        // clicked against a block's underside hangs below it, but for a head ITEM the block is already a
        // skull here and getAttachmentFace can't tell a ceiling from a floor/wall — so read the click
        // directly and route opt-in blocks (allowed_faces contains UP) to UP. NOTE: UP/ceiling support
        // currently assumes a skull-backed item; the non-skull base_block/physical_material branches
        // below don't handle an UP facing.
        BlockFace placedOn;
        BlockFace clickedFace = event.getBlockAgainst() != null
                ? event.getBlockAgainst().getFace(block) : null;
        if (clickedFace == BlockFace.DOWN && type.placement() != null
                && type.placement().allowedFaces().contains(BlockFace.UP)) {
            placedOn = BlockFace.UP;                                      // ceiling — opt-in per block
        } else if (isAlreadySkull) {
            placedOn = getAttachmentFace(block);
        } else if (clickedFace != null && clickedFace != BlockFace.UP && clickedFace != BlockFace.DOWN) {
            placedOn = clickedFace.getOppositeFace();                     // wall
        } else {
            placedOn = BlockFace.DOWN;                                    // floor
        }

        // Check placement restrictions (before skull conversion so cancellation reverts cleanly).
        // A floor-capable block (allowed_faces contains DOWN) clicked against a wall/ceiling is
        // coerced to a floor placement in the same cell instead of being refused.
        boolean coercedToFloor = false;
        if (type.placement() != null) {
            CustomHeadBlock.PlacementConfig pc = type.placement();
            if (!pc.allowedFaces().isEmpty() && !pc.allowedFaces().contains(placedOn)) {
                if (pc.allowedFaces().contains(BlockFace.DOWN)) {
                    placedOn = BlockFace.DOWN;
                    coercedToFloor = true;
                } else {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(
                            net.kyori.adventure.text.Component.text("Cannot place this block here",
                                    net.kyori.adventure.text.format.NamedTextColor.RED));
                    return;
                }
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

        // A custom rail (junction / destructor / controller) may not be placed onto another rail: vanilla
        // seats a rail on top of the clicked block, and rail-on-rail yields broken shapes / instant slopes.
        if (type.baseBlock() != null && RailPathWalker.isRail(type.baseBlock())) {
            Block against = event.getBlockAgainst();
            if (against != null && RailPathWalker.isRail(against.getType())) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
                        "Can't place a rail on another rail",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
        }

        // Convert non-skull blocks to skulls (e.g., slab items with item_material)
        if (type.baseBlock() != null) {
            // Bare-first block (e.g. casing = OAK_STAIRS): replace the placed head with the base block.
            // Its head texture is carried by a display entity; identity lives in the display-backed
            // bare-block registry (addBareBlock below), not a block-entity PDC.
            org.bukkit.block.data.BlockData pinned = type.baseBlockData();
            if (pinned != null) {
                // Pinned data (the casing's upside-down straight stair) is authoritative: every copy must
                // be identical, or vanilla's stair-shape rule starts cornering them against each other.
                // Deliberately skips the placement-face orient below — the facing must not vary.
                block.setBlockData(pinned, false);
            } else {
                block.setType(type.baseBlock(), false);
                // Orient the base block to the attachment face when it supports it (guarded, like
                // physical_material).
                if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir
                        && dir.getFaces().contains(placedOn)) {
                    dir.setFacing(placedOn);
                    block.setBlockData(dir, false);
                }
            }
        } else if (type.physicalMaterial() != null) {
            // physical_material block (e.g. the dynamo's barrel): replace the placed head with the real
            // block, facing the attachment face when the material supports it (guarded — a horizontally-
            // restricted Directional would throw on DOWN). Disguise display + PDC identity are applied
            // afterwards (markBlock / applyConfig), same as any other custom block.
            block.setType(type.physicalMaterial(), false);
            if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                BlockFace desired = placementFacing(type, event.getPlayer(), placedOn);
                if (!dir.getFaces().contains(desired)) {
                    // Horizontal-only Directional (e.g. the boiler's chest) that can't take the desired
                    // face (floor placement, or a vertical toward-player look): front toward the placer,
                    // like a vanilla chest placement.
                    desired = event.getPlayer().getFacing().getOppositeFace();
                }
                if (dir.getFaces().contains(desired)) {
                    dir.setFacing(desired);
                    block.setBlockData(dir, false);
                }
            }
        } else if (!isAlreadySkull) {
            if (!coercedToFloor
                    && clickedFace != null && clickedFace != BlockFace.UP && clickedFace != BlockFace.DOWN) {
                block.setType(Material.PLAYER_WALL_HEAD, false);
                if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                    dir.setFacing(clickedFace);
                    block.setBlockData(dir, false);
                }
            } else {
                block.setType(Material.PLAYER_HEAD, false);
            }
        }

        // Ground a coerced wall placement: vanilla put a wall head against the clicked face;
        // re-set it as a floor head facing the placer (cardinal, like a floor click would).
        if (coercedToFloor && block.getType() == Material.PLAYER_WALL_HEAD) {
            block.setType(Material.PLAYER_HEAD, false);
            if (block.getBlockData() instanceof org.bukkit.block.data.Rotatable rot) {
                rot.setRotation(event.getPlayer().getFacing().getOppositeFace());
                block.setBlockData(rot, false);
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

        // Register identity: a bare-first block has no block-entity, so index it in the display-backed
        // registry (durable chunk PDC + tagged display); everything else stamps the tile PDC.
        if (type.baseBlock() != null) {
            registry.addBareBlock(block, type);
        } else {
            registry.markBlock(block, type, state);
        }

        // Copy captured display data from item → block tile (windmill banners / water-wheel
        // planks). The item is always a head (SkullMeta); the placed block may be a skull OR
        // a physical_material tile entity (the water wheel's chest), so target any TileState.
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta
                && block.getState() instanceof org.bukkit.block.TileState tile) {
            if (type.displayItemResolver() != null) {
                CustomBlockRegistry.copyBladePdc(
                        skullMeta.getPersistentDataContainer(),
                        tile.getPersistentDataContainer());
            }
            if (type.ingredientCapture() != null) {
                type.ingredientCapture().copyPdc(
                        skullMeta.getPersistentDataContainer(),
                        tile.getPersistentDataContainer());
            }
            tile.update(false, false); // physics-suppressed; neighbors notified via notifyBlockAppearedOrMoved below
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

            // Placement writes are physics-suppressed, so notify reactive neighbors (pipes, and
            // rotation nodes that pick up an adjacent passive windmill) explicitly. Replaces the
            // BlockPhysicsEvent that markBlock's tile.update() used to emit.
            registry.notifyBlockAppearedOrMoved(block);

            // Force nearby clients to re-render the freshly-placed head. applyConfig sets the correct
            // skull profile server-side, but the client's placement-predicted head (notably one placed
            // against a block's underside → DOWN pipe) doesn't repaint from the normal block-entity
            // broadcast — it stays a default "Steve" head until the chunk reloads. refreshHeadViewers
            // pushes the block-entity (profile) to viewers so they re-resolve the embedded texture.
            // A single send races the client's placement reconciliation and only lands sometimes, so
            // repeat it over a few ticks — idempotent (same profile → no flicker), and placement is
            // player-paced so the cost is trivial. No-op for a head whose render already matched.
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                for (int d : new int[]{1, 3, 8}) {
                    getServer().getScheduler().runTaskLater(this, () -> {
                        if (registry.getTypeFromBlock(block) == null) return;
                        if (block.getType() == Material.PLAYER_HEAD
                                || block.getType() == Material.PLAYER_WALL_HEAD) {
                            registry.refreshHeadViewers(block);
                        }
                    }, d);
                }
            }

            // Register for redstone tracking if needed
            if (type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
                registry.trackRedstone(block, type, power);
            }

            // Register tick tracking (mirrors restoreBlock — needed for engine/drill onTick)
            if (type.onTick() != null && type.tickInterval() != null) {
                registry.trackTick(block, type);
            }

            // Fire BOTH callbacks: onBlockPlaced (placement-specific setup, e.g. skull-yaw snap)
            // first, then onChunkLoadCallback (steady-state registration — network nodes etc.).
            // These are complementary, not alternatives: the old either/or left a type that set
            // both (extendable-piston core) unregistered until its chunk reloaded.
            if (type.onBlockPlaced() != null) {
                type.onBlockPlaced().accept(block, state);
            }
            if (type.onChunkLoadCallback() != null) {
                type.onChunkLoadCallback().accept(block, state);
            }
        });
    }

    /**
     * The facing a directional physical/base block should take on placement, per its
     * {@link CustomHeadBlock.FacingMode}. Default {@code ATTACHMENT} keeps the historical behaviour
     * (face the surface it was placed against); {@code TOWARD_PLAYER} mirrors a vanilla dispenser.
     */
    private static BlockFace placementFacing(CustomHeadBlock type, org.bukkit.entity.Player player,
                                             BlockFace placedOn) {
        if (type.placement() != null
                && type.placement().facing() == CustomHeadBlock.FacingMode.TOWARD_PLAYER) {
            return nearestLookingDirection(player).getOppositeFace();
        }
        return placedOn;
    }

    /** The axis-aligned direction the player is looking most strongly along (incl. up/down) — the
     *  vanilla {@code Direction.getNearest(lookAngle)} rule used by dispenser/dropper/observer. */
    private static BlockFace nearestLookingDirection(org.bukkit.entity.Player player) {
        org.bukkit.util.Vector dir = player.getEyeLocation().getDirection();
        double ax = Math.abs(dir.getX()), ay = Math.abs(dir.getY()), az = Math.abs(dir.getZ());
        if (ay >= ax && ay >= az) return dir.getY() > 0 ? BlockFace.UP : BlockFace.DOWN;
        if (ax >= az) return dir.getX() > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dir.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block breaking — cleanup displays, light, particles, drops
    // ──────────────────────────────────────────────────────────────────────

    // MONITOR (not HIGH): the destructive drop + unregister below must run only once the break is
    // final. At HIGH a protection plugin cancelling at HIGHEST/MONITOR would leave the block standing
    // while we already dropped its storage/self-drop/filter items → duplication + orphan. MONITOR runs
    // last (still pre-apply, so the tile/PDC is readable). setDropItems(false) is still honored here.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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

    // Explosion cleanup — remove custom blocks from blast list, drop correct items.
    // MONITOR (not HIGH) for the same reason as onBlockBreak: a protection plugin pruning the blockList
    // at HIGHEST runs before us, so we only drop for blocks that actually explode (no protection-bypass
    // dupe). Handlers never cancel/mutate the event — only the (post-protection) blockList — so MONITOR
    // is appropriate.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
        handlePiston(event.getBlocks(), event.getDirection(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event.getBlocks(), event.getDirection(), event);
    }

    private void handlePiston(List<Block> blocks, BlockFace direction,
                              org.bukkit.event.Cancellable event) {
        // Bare blocks that ride the push (the casing): their base block moves like any vanilla block,
        // but identity + shell display live in the bare-block registry keyed by cell — collect the
        // old→new pairs and re-seat them once the piston animation lands (~3 ticks). Validated there,
        // so a later-priority cancellation just leaves identities in place.
        List<CustomBlockRegistry.BareMove> bareMoves = new ArrayList<>();
        for (Block block : blocks) {
            // A bare block WITH a revert handler (the shaft) reverts to an encased head first, then
            // behaves like a normal skull under pistons (its flags below take over). A bare block
            // WITHOUT one (the casing) rides the push; its identity follows via moveBareBlocks.
            registry.revertBareBlockForCapture(block);
            if (registry.isBareBlock(block)) {
                bareMoves.add(new CustomBlockRegistry.BareMove(block, block.getRelative(direction)));
                continue;
            }
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
        if (!bareMoves.isEmpty()) {
            // The piston animation occupies the cells with MOVING_PISTON for ~2 ticks; re-seat the
            // identities once everything has landed. moveBareBlocks validates per move, so nothing
            // is lost if the event was cancelled by a later-priority handler.
            getServer().getScheduler().runTaskLater(this,
                () -> registry.moveBareBlocks(bareMoves), 3L);
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

    // Prevent flowing water/lava from destroying a placed custom head — or, for types opting in via
    // break_on_fluid, break it ourselves so the REAL item drops. The head cell is the flow's
    // destination; vanilla would replace it and drop a plain (non-functional) head, so the event is
    // always cancelled for a custom head (BlockFromToEvent has no drop-suppression API). Identity is
    // the skull PDC (getTypeFromBlock), not the in-memory set — the set can be stale after chunk
    // reloads, and a stale miss here is exactly what used to leak plain heads. The material pre-check
    // keeps the getState() read off the hot path for ordinary water tiles (isBareBlock does its own,
    // on bareTypes, before it touches a LocationKey); physical_material blocks are validated solid, so
    // a fluid can never target them and heads + bare blocks are complete coverage.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlowIntoCustomHead(org.bukkit.event.block.BlockFromToEvent event) {
        Block to = event.getToBlock();
        Material t = to.getType();
        // Bare blocks are real world blocks and several are waterloggable (the shaft's CHAIN, the casing's
        // stair); water flowing in would otherwise trip the MONITOR handler below into tearing down their
        // node/display even though the block isn't destroyed. Cancelling also keeps them un-waterlogged,
        // which is why the casing's pinned data can trust waterlogged=false to stay false.
        if (registry.isBareBlock(to)) {
            event.setCancelled(true);
            return;
        }
        if (t != Material.PLAYER_HEAD && t != Material.PLAYER_WALL_HEAD) return;
        CustomHeadBlock type = registry.getTypeFromBlock(to);
        if (type == null) return;
        event.setCancelled(true);
        if (!type.breakOnFluid()) return; // default: the block simply survives the flow

        // Opt-in fluid break: mirror the piston/mining break path — read everything off the tile
        // PDC BEFORE removal destroys it, then drop and clear. The fluid refills the cell on its
        // own subsequent flow ticks.
        String state = registry.getState(to);
        if (type.storage() != null) registry.dropStorage(to);
        ItemStack selfDrop = enrichDrop(to, type, type.createItem(1));
        if (type.breakSound() != null) {
            var s = type.breakSound();
            to.getWorld().playSound(to.getLocation().add(0.5, 0.5, 0.5), s.sound(), s.volume(), s.pitch());
        }
        // Honor drop rules when one matches, but never let an environmental break destroy the item:
        // with no matching self-drop rule the real item still drops (unlike player mining, there is
        // no creative-mode or tool context here).
        boolean dropped = false;
        for (var rule : type.dropRules()) {
            if (rule.inState() != null && !rule.inState().equals(state)) continue;
            if (rule.isSelfDrop()) {
                to.getWorld().dropItemNaturally(to.getLocation().add(0.5, 0.5, 0.5), selfDrop);
            } else {
                for (CustomHeadBlock.ItemDrop itemDrop : rule.drops()) {
                    to.getWorld().dropItemNaturally(to.getLocation().add(0.5, 0.5, 0.5),
                            new ItemStack(itemDrop.material(), itemDrop.amount()));
                }
            }
            dropped = true;
            break; // first matching rule wins
        }
        if (!dropped) {
            to.getWorld().dropItemNaturally(to.getLocation().add(0.5, 0.5, 0.5), selfDrop);
        }
        registry.onBlockRemoved(to, type);
        to.setType(Material.AIR, false);
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

    // /fill, /setblock, physics-based destruction — cleanup without drops. setWillDrop(false)
    // guarantees a destroy-mode command can never leak a plain vanilla head (HIGH, not MONITOR,
    // because we mutate the event).
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDestroy(com.destroystokyo.paper.event.block.BlockDestroyEvent event) {
        Block block = event.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;
        event.setWillDrop(false);
        if (type.storage() != null) registry.dropStorage(block);
        registry.onBlockRemoved(block, type);
    }

    // Prevent wall-mounted custom skulls from popping off when support block is removed,
    // but allow other physics (redstone propagation) to proceed normally. Identity is the skull PDC
    // (getTypeFromBlock), NOT the in-memory set: a stale set-miss here let vanilla pop the head into
    // a plain (non-functional) drop. The material pre-check bounds the getState() cost to actual
    // heads; solid physical_material blocks have no pop-off physics, so heads are complete coverage.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysicsCancelForCustomSkulls(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;
        // Only cancel if the support block is gone (prevents pop-off without suppressing redstone)
        BlockFace attachment = getAttachmentFace(block);
        Block support = block.getRelative(attachment);
        if (!support.getType().isSolid()) {
            event.setCancelled(true);
        }
    }

    // Water-bucket the other waterlogging vector shut. Flow is handled by the BlockFromToEvent guard
    // above; this is the player pouring a bucket straight into a casing's cell, which vanilla would
    // waterlog in place (a stair is SimpleWaterloggedBlock) without ever firing BlockFromToEvent.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmptyIntoBareBlock(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        if (registry.isBareBlock(event.getBlock())) event.setCancelled(true);
    }

    // Endermen picking up blocks, falling blocks landing in the cell, wither charges, silverfish, etc.
    // would turn a custom block to air with no cleanup path — protect it, matching the water/physics
    // protection above. getTypeFromBlock is the PDC for heads (authoritative even when the runtime set
    // is stale) and the set-gated tile read for physical_material blocks; vanilla blocks cost one
    // hash lookup, so no material pre-check is needed.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeCustomBlock(EntityChangeBlockEvent event) {
        if (registry.getTypeFromBlock(event.getBlock()) == null) return;
        event.setCancelled(true);
    }

    // Middle-click pick-block — creative mints the custom item; survival/adventure only
    // selects it if already owned (never mints). See InventoryUtil.pickCustom.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickBlock(io.papermc.paper.event.player.PlayerPickBlockEvent event) {
        Block block = event.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack customItem = enrichDrop(block, type, type.createItem(1));
        InventoryUtil.pickCustom(player, customItem, event.getTargetSlot());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cauldron conversions — item thrown into water cauldron → different item
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onItemSpawnCauldron(org.bukkit.event.entity.ItemSpawnEvent event) {
        if (!registry.hasCauldronConversions()) return;

        org.bukkit.entity.Item itemEntity = event.getEntity();
        ItemStack item = itemEntity.getItemStack();

        String fromId = CustomBlockRegistry.getItemTypeId(item);
        if (fromId == null) return;

        String toId = registry.getCauldronConversionTarget(fromId);
        if (toId == null) return;

        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!itemEntity.isValid() || itemEntity.isDead()) {
                    cancel();
                    return;
                }
                if (ticks++ > 20) {
                    cancel();
                    return;
                }
                Block block = itemEntity.getLocation().getBlock();
                if (block.getType() == Material.WATER_CAULDRON
                        && block.getBlockData() instanceof org.bukkit.block.data.Levelled levelled
                        && levelled.getLevel() > 0) {
                    performCauldronConversion(itemEntity, item, toId, block, levelled);
                    cancel();
                }
            }
        }.runTaskTimer(this, 5L, 2L);
    }

    private void performCauldronConversion(org.bukkit.entity.Item itemEntity, ItemStack originalItem,
                                           String toId, Block cauldron, org.bukkit.block.data.Levelled levelled) {
        CustomHeadBlock toType = registry.getType(toId);
        if (toType == null) {
            getLogger().warning("Cauldron conversion target not found: " + toId);
            return;
        }

        ItemStack converted = toType.createItem(originalItem.getAmount());
        itemEntity.remove();
        itemEntity.getWorld().dropItem(itemEntity.getLocation(), converted);

        int newLevel = levelled.getLevel() - 1;
        if (newLevel <= 0) {
            cauldron.setType(Material.CAULDRON);
        } else {
            levelled.setLevel(newLevel);
            cauldron.setBlockData(levelled);
        }

        cauldron.getWorld().spawnParticle(
                org.bukkit.Particle.SPLASH,
                cauldron.getLocation().add(0.5, 0.9, 0.5),
                10, 0.2, 0.1, 0.2, 0.05);
        cauldron.getWorld().playSound(
                cauldron.getLocation(),
                org.bukkit.Sound.ITEM_BUCKET_EMPTY,
                0.5f, 1.2f);
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
     *  Delegates to the shared registry helper so every break path drops an identical item. */
    private ItemStack enrichDrop(Block block, CustomHeadBlock type, ItemStack item) {
        return CustomBlockRegistry.enrichDrop(block, type, item);
    }

    // ──────────────────────────────────────────────────────────────────────
    // World lifecycle — load/save chunk hints per world
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        registry.loadHintsForWorld(event.getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent event) {
        registry.saveHintsForWorld(event.getWorld().getUID());   // idempotent — fine even if cancelled below
        // WorldUnloadEvent is Cancellable — a later handler may keep the world loaded, so don't tear its
        // mechanisms down. This path (Bukkit.unloadWorld / Multiverse /mvunload) may not fire per-chunk
        // EntitiesUnloadEvent, so disassemble assembled carts + mid-stroke mechanisms here (blocks still
        // writable) to avoid orphaning them. Idempotent with the EntitiesUnload path.
        if (event.isCancelled()) return;
        if (mechanismMinecartManager != null) mechanismMinecartManager.onWorldUnload(event.getWorld());
        if (mechanismRegistry != null) mechanismRegistry.onWorldUnload(event.getWorld());
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
            // Delay by 1 tick so Bukkit finishes removing the viewer first. LOAD-BEARING beyond that:
            // this deferral keeps openStorages.remove out of saveAllOpenStorages/saveStoragesInChunk's
            // iteration — making it synchronous reintroduces a ConcurrentModificationException there.
            getServer().getScheduler().runTask(this, () -> registry.onStorageClosed(holder));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Container-backed disguised-block protection (e.g. the redstone dynamo's barrel drives a
    // comparator): the block must behave like a solid mechanism — players can't open it and hoppers
    // can't move its contents (which would corrupt the plugin-managed inventory). Opt out per type
    // with lockContainer(false). Direct plugin API writes are unaffected (they fire neither event).
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onManagedContainerOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (isManagedContainer(event.getInventory().getHolder())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onManagedContainerMove(org.bukkit.event.inventory.InventoryMoveItemEvent event) {
        if (isManagedContainer(event.getSource().getHolder())
                || isManagedContainer(event.getDestination().getHolder())) {
            event.setCancelled(true);
        }
    }

    /** True if the inventory holder is a real container whose inventory is plugin-owned (a
     *  physical_material custom block with the container lock). An event holder implies the chunk
     *  is loaded, satisfying {@link CustomBlockRegistry#isLockedContainer}'s tracking precondition. */
    private boolean isManagedContainer(org.bukkit.inventory.InventoryHolder holder) {
        if (!(holder instanceof org.bukkit.block.Container c)) return false;
        return registry.isLockedContainer(c.getBlock());
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
            int size = event.getInventory().getSize();
            int bottomStart = size - 9;

            // Bottom-bar navigation
            if (slot == bottomStart) { // previous page
                if (holder.page() > 0) {
                    openStonecutterSelectMenu(player, holder.inputBlockId(), holder.page() - 1);
                }
                return;
            }
            if (slot == size - 1) { // next page
                int totalPages = Math.max(1, (holder.recipes().size() + SC_PAGE - 1) / SC_PAGE);
                if (holder.page() < totalPages - 1) {
                    openStonecutterSelectMenu(player, holder.inputBlockId(), holder.page() + 1);
                }
                return;
            }

            // Output items only occupy the area above the bottom bar; map to the current page.
            if (slot < 0 || slot >= bottomStart) return;
            int recipeIndex = holder.page() * SC_PAGE + slot;
            if (recipeIndex >= holder.recipes().size()) return; // empty cell on a partial page

            var recipe = holder.recipes().get(recipeIndex);
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

    /** Read-only chest GUIs (catalog, stonecutter-select, dynamo/rotator mode menus) cancel clicks but
     *  not drags; a drag into an empty GUI slot would deposit and lose the item on close. Cancel any drag
     *  that touches the top inventory of a {@link ReadOnlyGuiHolder} (bottom-only drags stay allowed). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReadonlyGuiDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ReadOnlyGuiHolder
                && event.getRawSlots().stream().anyMatch(s -> s < event.getInventory().getSize())) {
            event.setCancelled(true);
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

    /** Output slots per page in the stonecutter selection GUI (the 5 rows above the bottom nav bar). */
    private static final int SC_PAGE = 45;

    private void openStonecutterSelectMenu(Player player, String inputBlockId) {
        openStonecutterSelectMenu(player, inputBlockId, 0);
    }

    private void openStonecutterSelectMenu(Player player, String inputBlockId, int page) {
        var recipes = registry.getStonecutterRecipesForInput(inputBlockId);
        if (recipes.isEmpty()) {
            player.sendMessage(Component.text("No stonecutter recipes available.", NamedTextColor.RED));
            return;
        }

        int totalPages = Math.max(1, (recipes.size() + SC_PAGE - 1) / SC_PAGE);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        int start = page * SC_PAGE;
        int itemSlots = Math.min(SC_PAGE, recipes.size() - start);

        StonecutterSelectHolder holder = new StonecutterSelectHolder(inputBlockId, recipes, page);

        CustomHeadBlock inputType = registry.getType(inputBlockId);
        Component title = Component.text("Stonecutter", NamedTextColor.DARK_PURPLE);
        if (inputType != null && inputType.name() != null) {
            title = title.append(Component.text(": ")).append(inputType.name());
        }
        if (totalPages > 1) {
            title = title.append(Component.text(" (" + (page + 1) + "/" + totalPages + ")"));
        }

        // Items area (this page, up to 45) + bottom row for navigation/info
        int rows = Math.max(1, (itemSlots + 8) / 9) + 1; // +1 row for bottom bar
        int size = rows * 9;

        org.bukkit.inventory.Inventory inv = getServer().createInventory(holder, size, title);
        holder.setInventory(inv);

        // Populate this page's output items
        for (int i = 0; i < itemSlots; i++) {
            var recipe = recipes.get(start + i);
            CustomHeadBlock outputType = registry.getType(recipe.outputBlockId());
            if (outputType == null) continue;
            inv.setItem(i, outputType.createItem(recipe.amount()));
        }

        // Bottom bar: filler + input display + prev/next nav
        int bottomStart = size - 9;
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) { fillerMeta.displayName(Component.empty()); filler.setItemMeta(fillerMeta); }
        for (int i = bottomStart; i < size; i++) inv.setItem(i, filler);

        // Input item display (center of bottom row)
        if (inputType != null) inv.setItem(bottomStart + 4, inputType.createItem(1));

        // Prev (bottom-left) / next (bottom-right) navigation, only when applicable
        if (page > 0) {
            inv.setItem(bottomStart, stonecutterNavButton("Previous page", "Page " + page + "/" + totalPages));
        }
        if (page < totalPages - 1) {
            inv.setItem(size - 1, stonecutterNavButton("Next page", "Page " + (page + 2) + "/" + totalPages));
        }

        player.openInventory(inv);
    }

    private ItemStack stonecutterNavButton(String name, String lore) {
        ItemStack it = new ItemStack(Material.ARROW);
        var m = it.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(name, NamedTextColor.YELLOW));
            m.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
            it.setItemMeta(m);
        }
        return it;
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
    // Item catalog GUI (/defcorelib catalog) — browse every registered type by
    // category (falling back to namespace), search, drill into recipes, admin-give.
    // ──────────────────────────────────────────────────────────────────────

    private static final int CATALOG_PAGE = 45; // content slots per page (top 5 rows); bottom row = nav bar

    /** Category path → block id whose item texture represents that category node (else its first member).
     *  Keyed by the child path as rendered in the tree (top-level = single segment, deeper = full path).
     *  Could later move to a resource; a handful of overrides doesn't warrant it yet. */
    private static final Map<String, String> CATEGORY_ICON_IDS = buildCategoryIconIds();

    private static Map<String, String> buildCategoryIconIds() {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("mech", "mech:gear");
        m.put("pipes", "pipes:copper_pipe");
        m.put("headsmith", "headsmith:black_skull_candle");
        // HeadSmith color facet: each color node shows that color's mini wool.
        for (String c : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
            m.put("headsmith/color/" + c, "headsmith:mini_" + c + "_wool");
        }
        return Map.copyOf(m);
    }

    /** Grouping labels for a type: its explicit {@code categories}, else a single {@code [namespace]}. */
    private static List<String> catalogGroupsOf(CustomHeadBlock t) {
        return t.categories().isEmpty() ? List.of(t.namespace()) : t.categories();
    }

    private static String catalogPlainName(CustomHeadBlock t) {
        return t.name() == null ? t.typeId()
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(t.name());
    }

    /** True if a type belongs at or below the category path (path "" matches everything). */
    private static boolean catalogInSubtree(CustomHeadBlock t, String path) {
        if (path.isEmpty()) return true;
        for (String g : catalogGroupsOf(t)) {
            if (g.equals(path) || g.startsWith(path + "/")) return true;
        }
        return false;
    }

    /** Within the mech namespace, these id-prefixes sort to the END of the catalog list, in this order. */
    private static final List<String> MECH_TRAILING_PREFIXES = List.of("casing_", "gearbox_", "chassis_");

    /** Catalog sort rank within a namespace: mech casings/gearboxes/chassis trail everything else (in that
     *  order); 0 for all other types. Keeps the wood-family bulk blocks after the interesting ones. */
    private static int catalogSortRank(CustomHeadBlock t) {
        if (t.namespace().equals("mech")) {
            String id = t.typeId();
            for (int i = 0; i < MECH_TRAILING_PREFIXES.size(); i++)
                if (id.startsWith(MECH_TRAILING_PREFIXES.get(i))) return i + 1;
        }
        return 0;
    }

    private List<CustomHeadBlock> catalogSortedTypes() {
        List<CustomHeadBlock> list = new ArrayList<>(registry.allTypes());
        list.sort(java.util.Comparator.comparing(CustomHeadBlock::namespace)
                .thenComparingInt(CoreLibPlugin::catalogSortRank)
                .thenComparing(t -> catalogPlainName(t).toLowerCase(java.util.Locale.ROOT)));
        return list;
    }

    private static String catalogLastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** Normalize a category label: split on '/', trim + lowercase each segment, drop blank segments, then
     *  rejoin. Package-visible (not private) so {@link BlockLoader} can normalize at load and the command
     *  path arg is normalized identically. Returns "" when nothing survives. */
    static String catalogNormalizeCategory(@org.jspecify.annotations.Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : raw.split("/")) {
            String s = seg.trim().toLowerCase(java.util.Locale.ROOT);
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    /** Category-tree view: the distinct child categories under {@code path}. */
    void openCatalogTree(Player player, String path, int page, @org.jspecify.annotations.Nullable CatalogHolder parent) {
        java.util.TreeSet<String> childPaths = new java.util.TreeSet<>();
        for (CustomHeadBlock t : registry.allTypes()) {
            for (String g : catalogGroupsOf(t)) {
                if (path.isEmpty()) {
                    int slash = g.indexOf('/');
                    childPaths.add(slash < 0 ? g : g.substring(0, slash));
                } else if (g.startsWith(path + "/")) {
                    String rem = g.substring(path.length() + 1);
                    int slash = rem.indexOf('/');
                    childPaths.add(path + "/" + (slash < 0 ? rem : rem.substring(0, slash)));
                }
            }
        }
        List<String> children = new ArrayList<>(childPaths);
        if (children.isEmpty()) { openCatalog(player, path, null, page, parent); return; }

        // Reachability: a type whose group is EXACTLY this path sits *at* the node, not below it, so the
        // child loop never surfaces it. Prepend a "Browse all here" entry (path itself; can't collide with
        // real children, which all start with path+"/") that opens the subtree item list.
        if (!path.isEmpty()) {
            for (CustomHeadBlock t : registry.allTypes()) {
                if (catalogGroupsOf(t).contains(path)) { children.add(0, path); break; }
            }
        }

        int total = children.size();
        int maxPage = Math.max(0, (total - 1) / CATALOG_PAGE);
        page = Math.max(0, Math.min(page, maxPage));

        CatalogHolder holder = new CatalogHolder(CatalogHolder.View.TREE, path, null, page, null, parent);
        Component title = Component.text(path.isEmpty() ? "Catalog" : "Catalog: " + path, NamedTextColor.DARK_PURPLE);
        org.bukkit.inventory.Inventory inv = getServer().createInventory(holder, 54, title);
        holder.setInventory(inv);

        List<CustomHeadBlock> sorted = catalogSortedTypes();
        int start = page * CATALOG_PAGE, end = Math.min(start + CATALOG_PAGE, total), slot = 0;
        for (int idx = start; idx < end; idx++, slot++) {
            String childPath = children.get(idx);
            boolean browseAll = childPath.equals(path); // the injected "Browse all here" leaf
            boolean isBranch = false;
            int count = 0;
            CustomHeadBlock rep = null;
            for (CustomHeadBlock t : sorted) {
                if (!catalogInSubtree(t, childPath)) continue;
                count++;
                if (rep == null) rep = t;
                if (!browseAll && !isBranch) {   // browse-all is always a leaf; skip branch detection (avoids a self-loop)
                    for (String g : catalogGroupsOf(t)) if (g.startsWith(childPath + "/")) { isBranch = true; break; }
                }
            }
            if (rep == null) continue;
            // Designated category icon (e.g. mech→gear, pipes→copper pipe) overrides the first-member icon.
            CustomHeadBlock iconType = rep;
            String iconId = CATEGORY_ICON_IDS.get(childPath);
            if (iconId != null) { CustomHeadBlock it = registry.getType(iconId); if (it != null) iconType = it; }
            String label = browseAll ? "Browse all " + catalogLastSegment(childPath) : catalogLastSegment(childPath);
            inv.setItem(slot, catalogCategoryIcon(iconType, label, count, !browseAll && isBranch));
            if (!browseAll && isBranch) holder.branchSlots.put(slot, childPath);
            else holder.leafSlots.put(slot, childPath);   // browse-all → leaf: opens openCatalog(path) subtree
        }
        catalogNavBar(inv, holder, page > 0, end < total, false);
        player.openInventory(inv);
    }

    /** Item-list view: all types under a category path, or matching a search query. */
    void openCatalog(Player player, String path, @org.jspecify.annotations.Nullable String search,
                     int page, @org.jspecify.annotations.Nullable CatalogHolder parent) {
        String q = search == null ? null : search.toLowerCase(java.util.Locale.ROOT);
        List<CustomHeadBlock> matches = new ArrayList<>();
        for (CustomHeadBlock t : catalogSortedTypes()) {
            if (q != null) {
                if (catalogPlainName(t).toLowerCase(java.util.Locale.ROOT).contains(q)
                        || t.fullId().toLowerCase(java.util.Locale.ROOT).contains(q)) matches.add(t);
            } else if (catalogInSubtree(t, path)) {
                matches.add(t);
            }
        }
        int total = matches.size();
        int maxPage = Math.max(0, (total - 1) / CATALOG_PAGE);
        page = Math.max(0, Math.min(page, maxPage));

        CatalogHolder holder = new CatalogHolder(CatalogHolder.View.ITEMS, path, search, page, null, parent);
        String titleStr = search != null ? "Search: " + search : (path.isEmpty() ? "Catalog: All" : "Catalog: " + path);
        org.bukkit.inventory.Inventory inv = getServer().createInventory(holder, 54, Component.text(titleStr, NamedTextColor.DARK_PURPLE));
        holder.setInventory(inv);

        boolean canGive = player.hasPermission("corelib.admin");
        int start = page * CATALOG_PAGE, end = Math.min(start + CATALOG_PAGE, total), slot = 0;
        for (int i = start; i < end; i++, slot++) {
            CustomHeadBlock t = matches.get(i);
            inv.setItem(slot, catalogItemCell(t, canGive));
            holder.itemSlots.put(slot, t.fullId());
        }
        if (total == 0) inv.setItem(22, catalogNavItem(Material.BARRIER, search != null ? "No matches" : "Empty"));
        catalogNavBar(inv, holder, page > 0, end < total, true);
        player.openInventory(inv);
    }

    /** Detail view: one type — a real crafting grid (shaped) or ingredient row (shapeless), plus
     *  stonecutter relations; every registered block ingredient is drillable. */
    void openCatalogDetail(Player player, @org.jspecify.annotations.Nullable String id,
                           @org.jspecify.annotations.Nullable CatalogHolder parent) {
        if (id == null) return;
        CustomHeadBlock type = registry.getType(id);
        if (type == null) return;
        CatalogHolder holder = new CatalogHolder(CatalogHolder.View.DETAIL, "", null, 0, id, parent);
        org.bukkit.inventory.Inventory inv = getServer().createInventory(holder, 54,
                Component.text("Catalog: " + catalogPlainName(type), NamedTextColor.DARK_PURPLE));
        holder.setInventory(inv);

        boolean rendered = false;
        if (!type.shapedRecipes().isEmpty()) {
            // Shaped → 3x3 grid at rows 0-2, cols 0-2 (slot = row*9+col); arrow @13; result/header @15.
            CustomHeadBlock.ShapedRecipeDef r = type.shapedRecipes().get(0);
            List<String> pattern = r.pattern();
            for (int row = 0; row < pattern.size() && row < 3; row++) {
                String line = pattern.get(row);
                for (int col = 0; col < line.length() && col < 3; col++) {
                    char c = line.charAt(col);
                    if (c == ' ') continue;
                    CustomHeadBlock.IngredientSpec spec = r.key().get(c);
                    if (spec == null) continue;
                    ItemStack icon = catalogIngredientIcon(spec);
                    if (icon == null) continue;
                    int gslot = row * 9 + col;
                    inv.setItem(gslot, icon);
                    if (spec.isBlock() && registry.getType(spec.blockId()) != null) holder.drillSlots.put(gslot, spec.blockId());
                }
            }
            inv.setItem(13, catalogNavItem(Material.ARROW, "→"));
            inv.setItem(15, catalogResult(type, r.output(), r.amount()));
            rendered = true;
        } else if (!type.shapelessRecipes().isEmpty()) {
            // Shapeless → fill the grid area left-to-right (no positions); same arrow + result.
            CustomHeadBlock.ShapelessRecipeDef r = type.shapelessRecipes().get(0);
            int[] gslots = {0, 1, 2, 9, 10, 11, 18, 19, 20};
            int gi = 0;
            for (CustomHeadBlock.IngredientSpec spec : r.ingredients()) {
                if (gi >= gslots.length) break;
                ItemStack icon = catalogIngredientIcon(spec);
                if (icon == null) continue;
                int gslot = gslots[gi++];
                inv.setItem(gslot, icon);
                if (spec.isBlock() && registry.getType(spec.blockId()) != null) holder.drillSlots.put(gslot, spec.blockId());
            }
            inv.setItem(13, catalogNavItem(Material.ARROW, "→ (shapeless)"));
            inv.setItem(15, catalogResult(type, r.output(), r.amount()));
            rendered = true;
        }
        if (!rendered) inv.setItem(4, catalogDetailHeader(type, 1)); // stonecutter-only / no craft recipe → header

        // Stonecutter relations on lower rows (disjoint from the grid above).
        List<CustomHeadBlock.StonecutterRecipeDef> cutFrom = type.stonecutterRecipes();
        if (!cutFrom.isEmpty()) {
            inv.setItem(27, catalogNavItem(Material.STONECUTTER, "Cut from"));
            int slot = 28;
            boolean overflow = cutFrom.size() > 7; // 28..34 = 7 slots; reserve 34 for a "+N more" indicator
            int lastItemSlot = overflow ? 33 : 34;
            int shown = 0;
            for (CustomHeadBlock.StonecutterRecipeDef r : cutFrom) {
                if (slot > lastItemSlot) break;
                ItemStack icon = catalogIngredientIcon(r.input());
                if (icon == null) continue;
                inv.setItem(slot, icon);
                if (r.input().isBlock() && registry.getType(r.input().blockId()) != null) holder.drillSlots.put(slot, r.input().blockId());
                slot++; shown++;
            }
            if (overflow) inv.setItem(34, catalogNavItem(Material.SPYGLASS, "+" + (cutFrom.size() - shown) + " more"));
        }
        var cutsInto = registry.getStonecutterRecipesForInput(id);
        if (!cutsInto.isEmpty()) {
            inv.setItem(36, catalogNavItem(Material.STONECUTTER, "Cuts into"));
            int slot = 37;
            boolean overflow = cutsInto.size() > 7; // 37..43 = 7 slots; reserve 43 for a "view all" link
            int lastItemSlot = overflow ? 42 : 43;
            int shown = 0;
            for (CustomBlockRegistry.HeadStonecutterRecipe r : cutsInto) {
                if (slot > lastItemSlot) break;
                CustomHeadBlock out = registry.getType(r.outputBlockId());
                if (out == null) continue;
                inv.setItem(slot, out.createItem(r.amount()));
                holder.drillSlots.put(slot, r.outputBlockId());
                slot++; shown++;
            }
            if (overflow) {
                inv.setItem(43, catalogNavItem(Material.SPYGLASS,
                        "+" + (cutsInto.size() - shown) + " more — click to view all"));
                holder.viewAllSlots.put(43, id);
            }
        }
        catalogNavBar(inv, holder, false, false, false);
        player.openInventory(inv);
    }

    private @org.jspecify.annotations.Nullable ItemStack catalogIngredientIcon(CustomHeadBlock.IngredientSpec spec) {
        if (spec.isBlock()) {
            CustomHeadBlock t = registry.getType(spec.blockId());
            return t != null ? t.createItem(1) : catalogNavItem(Material.PLAYER_HEAD, spec.blockId());
        }
        if (spec.isMaterial() && spec.material() != null) return new ItemStack(spec.material());
        if (spec.isMaterials() && spec.materials() != null && !spec.materials().isEmpty()) return new ItemStack(spec.materials().get(0));
        if (spec.isTag() && spec.tag() != null) {
            var vals = spec.tag().getValues();
            return new ItemStack(vals.isEmpty() ? Material.NAME_TAG : vals.iterator().next());
        }
        return null;
    }

    private void catalogGive(Player player, String id) {
        CustomHeadBlock type = registry.getType(id);
        if (type == null) return;
        var overflow = player.getInventory().addItem(type.createItem(1));
        for (ItemStack lf : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), lf);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
    }

    private void catalogNavBar(org.bukkit.inventory.Inventory inv, CatalogHolder h,
                               boolean hasPrev, boolean hasNext, boolean hasSearch) {
        int size = inv.getSize(), bottom = size - 9;
        for (int i = bottom; i < size; i++) inv.setItem(i, catalogFiller());
        if (hasPrev) { h.prevSlot = bottom; inv.setItem(bottom, catalogNavItem(Material.ARROW, "Previous page")); }
        if (h.parent != null) { h.backSlot = bottom + 2; inv.setItem(bottom + 2, catalogNavItem(Material.OAK_DOOR, "Back")); }
        h.closeSlot = bottom + 4; inv.setItem(bottom + 4, catalogNavItem(Material.BARRIER, "Close"));
        if (hasSearch) { h.searchSlot = bottom + 6; inv.setItem(bottom + 6, catalogNavItem(Material.COMPASS, "Search: /defcorelib catalog search <query>")); }
        if (hasNext) { h.nextSlot = bottom + 8; inv.setItem(bottom + 8, catalogNavItem(Material.ARROW, "Next page")); }
    }

    private static ItemStack catalogFiller() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var m = it.getItemMeta();
        if (m != null) { m.displayName(Component.empty()); it.setItemMeta(m); }
        return it;
    }

    private static ItemStack catalogNavItem(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        var m = it.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            it.setItemMeta(m);
        }
        return it;
    }

    private static ItemStack catalogCategoryIcon(CustomHeadBlock rep, String label, int count, boolean isBranch) {
        ItemStack icon = rep.createItem(1);
        var m = icon.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(label, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(
                    Component.text(count + (count == 1 ? " item" : " items"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text(isBranch ? "Click to browse" : "Click to view", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
            icon.setItemMeta(m);
        }
        return icon;
    }

    private static ItemStack catalogItemCell(CustomHeadBlock type, boolean canGive) {
        ItemStack it = type.createItem(1);
        var m = it.getItemMeta();
        if (m != null) {
            List<Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text("Left-click: details", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            if (canGive) lore.add(Component.text("Right-click: give", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            m.lore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    /** The result-slot item for a catalog recipe. A recipe with an {@code output:} override (e.g. seed oil
     *  + string + nuggets → lantern) shows its real output; otherwise the owning type's detail header.
     *  Display-only: resolves silently and falls back to the type header if the output can't resolve on
     *  this MC version (e.g. COPPER_LANTERN pre-1.21.9). */
    private ItemStack catalogResult(CustomHeadBlock type, @org.jspecify.annotations.Nullable String output, int amount) {
        if (output == null) return catalogDetailHeader(type, amount);
        int amt = Math.max(1, amount);
        if (output.contains(":")) {
            CustomHeadBlock t = registry.getType(output);
            if (t != null) return t.createItem(amt);
        } else {
            Material m = Material.matchMaterial(output.toUpperCase(java.util.Locale.ROOT));
            if (m != null) return new ItemStack(m, amt);
        }
        return catalogDetailHeader(type, amount);
    }

    private static ItemStack catalogDetailHeader(CustomHeadBlock type, int amount) {
        ItemStack it = type.createItem(Math.max(1, amount));
        var m = it.getItemMeta();
        if (m != null) {
            List<Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text(type.fullId(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            if (!type.categories().isEmpty()) {
                lore.add(Component.text("Categories: " + String.join(", ", type.categories()), NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
            }
            m.lore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private void catalogReopen(Player player, CatalogHolder h) {
        switch (h.view) {
            case TREE -> openCatalogTree(player, h.path, h.page, h.parent);
            case ITEMS -> openCatalog(player, h.path, h.search, h.page, h.parent);
            case DETAIL -> openCatalogDetail(player, h.detailId, h.parent);
        }
    }

    private void catalogReopenPaged(Player player, CatalogHolder h, int newPage) {
        int p = Math.max(0, newPage);
        switch (h.view) {
            case TREE -> openCatalogTree(player, h.path, p, h.parent);
            case ITEMS -> openCatalog(player, h.path, h.search, p, h.parent);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCatalogClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CatalogHolder h)) return;
        event.setCancelled(true); // read-only browser; never let items move
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return; // clicked own inventory

        if (slot == h.closeSlot) { player.closeInventory(); return; }
        if (slot == h.backSlot && h.parent != null) { catalogReopen(player, h.parent); return; }
        if (slot == h.prevSlot) { catalogReopenPaged(player, h, h.page - 1); return; }
        if (slot == h.nextSlot) { catalogReopenPaged(player, h, h.page + 1); return; }
        if (slot == h.searchSlot) {
            player.closeInventory();
            player.sendMessage(Component.text("Search the catalog with: /defcorelib catalog search <query>", NamedTextColor.YELLOW));
            return;
        }
        String branch = h.branchSlots.get(slot);
        if (branch != null) { openCatalogTree(player, branch, 0, h); return; }
        String leaf = h.leafSlots.get(slot);
        if (leaf != null) { openCatalog(player, leaf, null, 0, h); return; }
        String drill = h.drillSlots.get(slot);
        if (drill != null) { openCatalogDetail(player, drill, h); return; }
        String viewAll = h.viewAllSlots.get(slot);
        if (viewAll != null) { openStonecutterSelectMenu(player, viewAll); return; }
        String itemId = h.itemSlots.get(slot);
        if (itemId != null) {
            if (event.isRightClick() && player.hasPermission("corelib.admin")) catalogGive(player, itemId);
            else openCatalogDetail(player, itemId, h);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Neighbor changes — only for blocks that declared reactsToNeighbors
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        // Ignore physics events that are the echo of our OWN block writes — otherwise reacting to
        // them re-enters refreshReactiveNeighbors → recalc → write → physics and spins the server
        // thread until the watchdog fires. Genuine external physics (player break, redstone/comparator,
        // vanilla blocks) fires at depth 0 and is handled normally.
        if (registry.isSuppressingPhysics()) return;
        registry.refreshReactiveNeighbors(event.getBlock());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Mechanism entity protection + interaction
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        // A mechanism minecart must stay destroyable — while assembled it also carries a
        // corelib:mech:{id}:vehicle tag, but breaking it should force-disassemble and drop the custom
        // item (VehicleDestroyEvent → MechanismMinecartManager.onMinecartDestroyed), so never shield it.
        // Only the cart bears this tag; the fragile display/collider/parent/non-cart-vehicle entities do
        // not, so they keep their protection.
        if (event.getEntity().getScoreboardTags().contains("corelib:mechanism_minecart")) return;
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

        // Wrench: show the mechanism's rotation-network readout (the moving-mechanism analogue of the
        // static debugInteract, whose world-block lookup can't see an assembled mechanism's AIR'd cells).
        // The event fires once per hand and getItemInMainHand() returns the wrench on both passes, so gate
        // the readout on the main hand — but return on BOTH so a wrench never opens storage / fires a
        // transition. A wrench on a non-rotation on-board block does nothing (dbg == null).
        if (RotationBlocks.isWrench(event.getPlayer().getInventory().getItemInMainHand())) {
            if (event.getHand() == EquipmentSlot.HAND) {
                var dbg = mechanismRegistry.rotationDebug(ref.mechanism(), blockIndex);
                if (dbg != null) {
                    event.getPlayer().sendActionBar(RotationBlocks.formatRotationDebug(
                        dbg.state(), dbg.supply(), dbg.demand(), dbg.blockCount(),
                        dbg.jammed(), dbg.powered(), dbg.dir(), dbg.cwSources(), dbg.ccwSources()));
                    org.bukkit.World w = ref.mechanism().pivot().getWorld();
                    for (org.joml.Vector3i c : dbg.memberCells()) {
                        if (w == null || !w.isChunkLoaded(c.x >> 4, c.z >> 4)) continue;
                        w.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                            new org.bukkit.Location(w, c.x + 0.5, c.y + 0.5, c.z + 0.5), 5, 0.25, 0.25, 0.25, 0);
                    }
                }
            }
            return;
        }

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
            sender.sendMessage(Component.text(sender.hasPermission("corelib.admin")
                    ? "Usage: /defcorelib <catalog|give|give_demo|give_demo_rotation|list|colliders|reloadbanners|cleanorphans|refreshdisplays>"
                    : "Usage: /defcorelib catalog", NamedTextColor.YELLOW));
            return true;
        }

        // Per-subcommand permission: `catalog` is public browse (corelib.catalog, default true); every
        // other subcommand is admin-only (corelib.admin). Replaces the former blanket command-level gate.
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        String needed = sub.equals("catalog") ? "corelib.catalog" : "corelib.admin";
        if (!sender.hasPermission(needed)) {
            sender.sendMessage(Component.text("You don't have permission for /defcorelib " + sub + ".", NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "catalog" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("search")) {
                    if (args.length >= 3) {
                        openCatalog(player, "", String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)), 0, null);
                    } else {
                        player.sendMessage(Component.text("Search the catalog with: /defcorelib catalog search <query>", NamedTextColor.YELLOW));
                    }
                } else if (args.length >= 2) {
                    openCatalogTree(player, catalogNormalizeCategory(args[1]), 0, null);
                } else {
                    openCatalogTree(player, "", 0, null);
                }
            }
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

                // Try the explicit namespace:id first; else resolve a bare id deterministically (A5):
                // exactly one match → use it; multiple across namespaces → refuse and list the options.
                CustomHeadBlock type = registry.getType(blockId);
                if (type == null) {
                    List<CustomHeadBlock> matches = new ArrayList<>();
                    for (CustomHeadBlock t : registry.allTypes()) {
                        if (t.typeId().equals(blockId)) matches.add(t);
                    }
                    if (matches.size() == 1) {
                        type = matches.get(0);
                    } else if (matches.size() > 1) {
                        sender.sendMessage(Component.text("Ambiguous id '" + blockId + "' — specify a namespace: "
                                + matches.stream().map(CustomHeadBlock::fullId)
                                        .collect(java.util.stream.Collectors.joining(", ")),
                                NamedTextColor.RED));
                        return true;
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
            case "refreshdisplays" -> {
                boolean confirm = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
                CustomBlockRegistry.RefreshResult result = registry.refreshLoadedDisplays(confirm);
                if (confirm) {
                    sender.sendMessage(Component.text("Refreshed " + result.refreshed() + " custom block"
                            + (result.refreshed() == 1 ? "" : "s") + ", removed " + result.orphansRemoved()
                            + " orphaned display " + (result.orphansRemoved() == 1 ? "entity" : "entities") + ".",
                            NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Would refresh " + result.refreshed() + " loaded custom block"
                            + (result.refreshed() == 1 ? "" : "s") + " and remove " + result.orphansRemoved()
                            + " orphaned display " + (result.orphansRemoved() == 1 ? "entity" : "entities")
                            + ". Run /defcorelib refreshdisplays confirm to apply.", NamedTextColor.YELLOW));
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
                        java.nio.file.Path out = getDataFolder().toPath().resolve("showcase-exports")
                            .resolve(ShowcaseExporter.fileNameFor(args[2]));
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
            case "driventest" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                if (drivenDemo == null) {
                    sender.sendMessage(Component.text("Driven demo unavailable", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("park")) drivenDemo.park(player);
                else if (args.length >= 2 && args[1].equalsIgnoreCase("unpark")) drivenDemo.unpark(player);
                else if (args.length >= 2 && args[1].equalsIgnoreCase("dropunpark")) drivenDemo.dropUnpark(player);
                else drivenDemo.toggle(player);
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand: " + args[0], NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("defcorelib")) return List.of();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("catalog"); // public
            if (sender.hasPermission("corelib.admin")) {
                subs.addAll(List.of("give", "give_demo", "give_demo_rotation", "list", "colliders", "reloadbanners", "cleanorphans", "refreshdisplays", "gluetest", "showcase", "driventest"));
            }
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("catalog")) {
            java.util.TreeSet<String> opts = new java.util.TreeSet<>();
            opts.add("search");
            for (CustomHeadBlock t : registry.allTypes()) {
                for (String g : catalogGroupsOf(t)) {
                    int slash = g.indexOf('/');
                    opts.add(slash < 0 ? g : g.substring(0, slash));
                }
            }
            return opts.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("cleanorphans") || args[0].equalsIgnoreCase("refreshdisplays"))) {
            return List.of("confirm").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            if (prefix.isEmpty()) return List.of(); // A4: don't dump thousands of ids on an empty prefix
            return registry.allTypes().stream()
                    .map(CustomHeadBlock::fullId)
                    // A4: companion-managed namespaces (e.g. headsmith) use their own give command
                    .filter(id -> !registry.isCompanionManaged(id.contains(":") ? id.substring(0, id.indexOf(':')) : ""))
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .limit(50)
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
