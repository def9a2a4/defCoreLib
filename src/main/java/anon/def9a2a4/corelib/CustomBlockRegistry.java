package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages custom block type registration, PDC operations, chunk hints,
 * and tick tracking (redstone + particles).
 */
public class CustomBlockRegistry {

    // PDC keys stored on every custom skull block
    static final NamespacedKey BLOCK_TYPE_KEY = new NamespacedKey("corelib", "block_type");
    static final NamespacedKey STATE_KEY = new NamespacedKey("corelib", "state");

    private final JavaPlugin plugin;

    // Type registration: fullId → definition
    private final Map<String, CustomHeadBlock> types = new HashMap<>();

    // Chunk hint store: worldId → set of "chunkX,chunkZ" strings
    private final Map<UUID, Set<String>> chunkHints = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyHintWorlds = ConcurrentHashMap.newKeySet();

    // Redstone tracking: locations that need power polling
    private final Map<LocationKey, RedstoneTracked> redstoneTracked = new HashMap<>();
    // Particle tracking: locations with active particle effects
    private final Map<LocationKey, ParticleTracked> particleTracked = new HashMap<>();
    // Neighbor-reactive blocks: fast lookup set for BlockPhysicsEvent filtering
    private final Set<LocationKey> neighborReactiveBlocks = new HashSet<>();
    // Tick tracking: blocks with periodic onTick callbacks
    private final Map<LocationKey, TickTracked> tickTracked = new HashMap<>();
    // All known custom block locations: fast lookup for physics cancellation
    private final Set<LocationKey> customBlockLocations = new HashSet<>();

    private @Nullable BukkitTask redstoneTask;
    private @Nullable BukkitTask particleTask;
    private @Nullable BukkitTask customTickTask;
    private @Nullable BukkitTask hintSaveTask;

    CustomBlockRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Type registration
    // ──────────────────────────────────────────────────────────────────────

    public void register(CustomHeadBlock type) {
        types.put(type.fullId(), type);
        // Rescan already-loaded chunks that may contain blocks of this type
        rescanLoadedChunks(type);
    }

    /** Scan all loaded, hinted chunks for blocks of the given type. */
    private void rescanLoadedChunks(CustomHeadBlock type) {
        for (World world : Bukkit.getWorlds()) {
            Set<String> hints = chunkHints.get(world.getUID());
            if (hints == null) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                if (!hints.contains(chunkKey(chunk))) continue;
                for (BlockState tile : chunk.getTileEntities()) {
                    if (!(tile instanceof Skull skull)) continue;
                    String id = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
                    if (!type.fullId().equals(id)) continue;

                    Block block = skull.getBlock();
                    String state = skull.getPersistentDataContainer().get(STATE_KEY, PersistentDataType.STRING);
                    restoreBlock(block, type, state);
                }
            }
        }
    }

    public void unregister(String fullId) {
        types.remove(fullId);
    }

    public @Nullable CustomHeadBlock getType(String fullId) {
        return types.get(fullId);
    }

    public @Nullable CustomHeadBlock getTypeFromBlock(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return null;
        }
        if (!(block.getState() instanceof Skull skull)) return null;
        String typeId = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) return null;
        return types.get(typeId);
    }

    public Collection<CustomHeadBlock> allTypes() {
        return Collections.unmodifiableCollection(types.values());
    }

    /** Fast check: is the block at this location neighbor-reactive? Used by BlockPhysicsEvent. */
    boolean isNeighborReactive(Block block) {
        return neighborReactiveBlocks.contains(LocationKey.of(block));
    }

    /** Fast check: is this a known custom block location? Used to avoid expensive getState() in physics events. */
    boolean isCustomBlock(Block block) {
        return customBlockLocations.contains(LocationKey.of(block));
    }

    // ──────────────────────────────────────────────────────────────────────
    // PDC operations on placed skull blocks
    // ──────────────────────────────────────────────────────────────────────

    /** Write block type and initial state to a placed skull's PDC. */
    public void markBlock(Block block, CustomHeadBlock type) {
        if (!(block.getState() instanceof Skull skull)) return;
        PersistentDataContainer pdc = skull.getPersistentDataContainer();
        pdc.set(BLOCK_TYPE_KEY, PersistentDataType.STRING, type.fullId());
        if (type.defaultState() != null) {
            pdc.set(STATE_KEY, PersistentDataType.STRING, type.defaultState());
        }
        skull.update();
        customBlockLocations.add(LocationKey.of(block));
        markChunkDirty(block);
        if (type.reactsToNeighbors()) {
            neighborReactiveBlocks.add(LocationKey.of(block));
        }
    }

    /** Read current state from a skull's PDC. */
    public @Nullable String getState(Block block) {
        if (!(block.getState() instanceof Skull skull)) return null;
        return skull.getPersistentDataContainer().get(STATE_KEY, PersistentDataType.STRING);
    }

    /** Write a new state to a skull's PDC. */
    public void setState(Block block, String state) {
        if (!(block.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(STATE_KEY, PersistentDataType.STRING, state);
        skull.update();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chunk hints
    // ──────────────────────────────────────────────────────────────────────

    boolean chunkMayHaveCustomBlocks(Chunk chunk) {
        Set<String> hints = chunkHints.get(chunk.getWorld().getUID());
        if (hints == null) return false;
        return hints.contains(chunkKey(chunk));
    }

    void markChunkDirty(Block block) {
        UUID worldId = block.getWorld().getUID();
        chunkHints.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet())
                .add(block.getChunk().getX() + "," + block.getChunk().getZ());
        dirtyHintWorlds.add(worldId);
    }

    void removeChunkHint(Chunk chunk) {
        Set<String> hints = chunkHints.get(chunk.getWorld().getUID());
        if (hints != null) {
            hints.remove(chunkKey(chunk));
            dirtyHintWorlds.add(chunk.getWorld().getUID());
        }
    }

    private static String chunkKey(Chunk chunk) {
        return chunk.getX() + "," + chunk.getZ();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chunk load/unload
    // ──────────────────────────────────────────────────────────────────────

    void onChunkLoad(Chunk chunk) {
        if (!chunkMayHaveCustomBlocks(chunk)) return;

        boolean foundAnyPdc = false; // true if ANY skull has a corelib PDC key (even unrecognized)
        for (BlockState tile : chunk.getTileEntities()) {
            if (!(tile instanceof Skull skull)) continue;
            String typeId = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (typeId == null) continue;

            foundAnyPdc = true; // Don't remove hint — a plugin for this type may load later

            CustomHeadBlock type = types.get(typeId);
            if (type == null) continue;

            Block block = skull.getBlock();
            String state = skull.getPersistentDataContainer().get(STATE_KEY, PersistentDataType.STRING);
            restoreBlock(block, type, state);
        }

        // Only remove hint if the chunk has zero skulls with ANY corelib PDC entry
        if (!foundAnyPdc) {
            removeChunkHint(chunk);
        }
    }

    /** Restore a single block's runtime state (light, particles, redstone tracking). */
    private void restoreBlock(Block block, CustomHeadBlock type, @Nullable String state) {
        // Restore light blocks
        CustomHeadBlock.LightConfig lc = type.resolveLight(state);
        if (lc != null) {
            ensureLightBlock(block, lc);
        }

        // Restore particle tracking
        CustomHeadBlock.ParticleConfig pc = type.resolveParticles(state);
        if (pc != null) {
            trackParticles(block, type, pc);
        }

        // Register for redstone tracking
        if (type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
            int power = readPower(block, type);
            trackRedstone(block, type, power);
        }

        // Track custom block location for fast physics checks
        customBlockLocations.add(LocationKey.of(block));

        // Track neighbor-reactive blocks
        if (type.reactsToNeighbors()) {
            neighborReactiveBlocks.add(LocationKey.of(block));
        }

        // Register for tick tracking
        if (type.onTick() != null && type.tickInterval() != null) {
            tickTracked.put(LocationKey.of(block), new TickTracked(block, type));
        }

        // Dispatch chunk load callback for plugin-specific restore
        if (type.onChunkLoadCallback() != null) {
            type.onChunkLoadCallback().accept(block, state);
        }
    }

    void onChunkUnload(Chunk chunk) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        World world = chunk.getWorld();

        // Dispatch chunk unload callbacks
        for (BlockState tile : chunk.getTileEntities()) {
            if (!(tile instanceof Skull skull)) continue;
            String typeId = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (typeId == null) continue;
            CustomHeadBlock type = types.get(typeId);
            if (type != null && type.onChunkUnloadCallback() != null) {
                type.onChunkUnloadCallback().accept(skull.getBlock());
            }
        }

        // Remove redstone tracking for blocks in this chunk
        redstoneTracked.entrySet().removeIf(e -> {
            LocationKey loc = e.getKey();
            return loc.worldId().equals(world.getUID())
                    && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ;
        });

        // Remove particle tracking for blocks in this chunk
        particleTracked.entrySet().removeIf(e -> {
            LocationKey loc = e.getKey();
            return loc.worldId().equals(world.getUID())
                    && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ;
        });

        // Remove neighbor-reactive tracking for blocks in this chunk
        neighborReactiveBlocks.removeIf(loc ->
                loc.worldId().equals(world.getUID())
                        && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ);

        // Remove tick tracking for blocks in this chunk
        tickTracked.entrySet().removeIf(e -> {
            LocationKey loc = e.getKey();
            return loc.worldId().equals(world.getUID())
                    && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ;
        });

        // Remove custom block locations for blocks in this chunk
        customBlockLocations.removeIf(loc ->
                loc.worldId().equals(world.getUID())
                        && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block lifecycle helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Apply the resolved config for a block's current state + power. */
    void applyConfig(Block block, CustomHeadBlock type, @Nullable String state, int power) {
        // Texture (with directional support)
        BlockFace facing = getSkullFacing(block);
        String texture = type.resolveTexture(state, power, facing);
        HeadUtil.applyTexture(block, texture);

        // Light
        CustomHeadBlock.LightConfig lc = type.resolveLight(state);
        if (lc != null) {
            ensureLightBlock(block, lc);
        } else {
            clearLightBlock(block, type);
        }

        // Particles
        CustomHeadBlock.ParticleConfig pc = type.resolveParticles(state);
        LocationKey key = LocationKey.of(block);
        if (pc != null) {
            trackParticles(block, type, pc);
        } else {
            particleTracked.remove(key);
        }

        // Display entities
        if (type.hasDisplayEntities()) {
            String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
            DisplayUtil.removeByTag(block.getLocation(), tagPrefix, 1.5);
            List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
            for (var dec : displays) {
                ItemStack displayItem = HeadUtil.createHead(dec.itemTexture(), 1);
                String tag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                        block.getLocation(), dec.tagSuffix());
                DisplayUtil.spawn(block.getLocation(), displayItem, dec.transform(), tag);
            }
        }
    }

    /** Handle block removal: clean up displays, light, particles, redstone tracking. */
    void onBlockRemoved(Block block, CustomHeadBlock type) {
        LocationKey key = LocationKey.of(block);
        redstoneTracked.remove(key);
        particleTracked.remove(key);
        neighborReactiveBlocks.remove(key);
        tickTracked.remove(key);
        customBlockLocations.remove(key);

        // Remove display entities (use location-specific prefix to avoid hitting nearby blocks)
        if (type.hasDisplayEntities()) {
            String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
            DisplayUtil.removeByTag(block.getLocation(), tagPrefix, 1.5);
        }

        // Remove light block
        clearLightBlock(block, type);
    }

    /** Transition to a new state, applying all effects. */
    public void transitionState(Block block, CustomHeadBlock type,
                                String fromState, CustomHeadBlock.StateTransition transition) {
        // Play sound
        if (transition.sound() != null) {
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                    transition.sound(), transition.volume(), transition.pitch());
        }

        // Play transition particle
        if (transition.particle() != null) {
            var tp = transition.particle();
            block.getWorld().spawnParticle(tp.type(),
                    block.getLocation().add(0.5, 0.5, 0.5),
                    tp.count(), tp.spread(), tp.spread(), tp.spread(), 0.01);
        }

        // Write new state
        setState(block, transition.toState());

        // Re-apply visual config
        int power = type.sensitivity() != CustomHeadBlock.Sensitivity.NONE ? readPower(block, type) : 0;
        applyConfig(block, type, transition.toState(), power);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Redstone
    // ──────────────────────────────────────────────────────────────────────

    int readPower(Block block, CustomHeadBlock type) {
        if (type.redstone() == null) return 0;
        return switch (type.redstone().reader()) {
            case DIRECT -> block.getBlockPower();
            case EXTENDED -> readExtendedPower(block);
        };
    }

    /** Read power through walls for wall-mounted heads, or below for floor heads. */
    private int readExtendedPower(Block block) {
        if (block.getType() == Material.PLAYER_WALL_HEAD) {
            if (block.getBlockData() instanceof Directional directional) {
                BlockFace behind = directional.getFacing().getOppositeFace();
                Block b1 = block.getRelative(behind);
                Block b2 = b1.getRelative(behind);
                return Math.max(b1.getBlockPower(), b2.getBlockPower());
            }
        } else if (block.getType() == Material.PLAYER_HEAD) {
            Block b1 = block.getRelative(0, -1, 0);
            Block b2 = block.getRelative(0, -2, 0);
            return Math.max(b1.getBlockPower(), b2.getBlockPower());
        }
        return block.getBlockPower();
    }

    void trackRedstone(Block block, CustomHeadBlock type, int initialPower) {
        redstoneTracked.put(LocationKey.of(block), new RedstoneTracked(block, type, initialPower));
    }

    void tickRedstone() {
        for (var entry : redstoneTracked.values()) {
            Block block = entry.block;
            if (!block.getChunk().isLoaded()) continue;

            CustomHeadBlock type = entry.type;
            int newPower = readPower(block, type);
            boolean changed = switch (type.sensitivity()) {
                case NONE -> false;
                case BINARY -> (entry.lastPower == 0) != (newPower == 0);
                case LEVEL -> entry.lastPower != newPower;
            };

            if (!changed) continue;

            int oldPower = entry.lastPower;
            entry.lastPower = newPower;

            // Check for redstone-triggered state transitions
            String currentState = getState(block);
            if (currentState != null) {
                var trigger = new CustomHeadBlock.Trigger.RedstonePower(
                        new CustomHeadBlock.PowerRange(newPower, newPower));
                var transition = type.findTransition(trigger, currentState);
                if (transition != null) {
                    transitionState(block, type, currentState, transition);
                    continue; // transition already applied config
                }
            }

            // No state transition — just re-apply config (handles texture changes)
            applyConfig(block, type, currentState, newPower);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Particles
    // ──────────────────────────────────────────────────────────────────────

    void trackParticles(Block block, CustomHeadBlock type, CustomHeadBlock.ParticleConfig config) {
        particleTracked.put(LocationKey.of(block), new ParticleTracked(block, type, config));
    }

    void tickParticles() {
        int currentTick = Bukkit.getServer().getCurrentTick();
        for (var entry : particleTracked.values()) {
            Block block = entry.block;
            if (!block.getChunk().isLoaded()) continue;

            CustomHeadBlock.ParticleConfig pc = entry.config;
            if (currentTick % pc.intervalTicks() != 0) continue;

            // Resolve power for Scaling values
            int power = 0;
            if (entry.type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
                RedstoneTracked rs = redstoneTracked.get(LocationKey.of(block));
                if (rs != null) power = rs.lastPower;
            }

            Vector offset = getOrientedOffset(block, pc);
            double x = block.getX() + 0.5 + offset.getX();
            double y = block.getY() + offset.getY();
            double z = block.getZ() + 0.5 + offset.getZ();

            int count = pc.count().resolveInt(power);
            double speed = pc.speed().resolve(power);

            if (pc.data() != null) {
                block.getWorld().spawnParticle(pc.type(), x, y, z,
                        count, 0.05, 0.05, 0.05, speed, pc.data());
            } else {
                block.getWorld().spawnParticle(pc.type(), x, y, z,
                        count, 0.05, 0.05, 0.05, speed);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Custom block ticking
    // ──────────────────────────────────────────────────────────────────────

    void tickCustomBlocks() {
        int currentTick = Bukkit.getServer().getCurrentTick();
        for (var entry : tickTracked.values()) {
            Block block = entry.block;
            if (!block.getChunk().isLoaded()) continue;

            CustomHeadBlock type = entry.type;
            Integer interval = type.tickInterval();
            if (interval == null || type.onTick() == null) continue;
            if (currentTick % interval != 0) continue;

            type.onTick().accept(block);
        }
    }

    private Vector getOrientedOffset(Block block, CustomHeadBlock.ParticleConfig pc) {
        if (block.getType() == Material.PLAYER_WALL_HEAD && block.getBlockData() instanceof Directional dir) {
            BlockFace facing = dir.getFacing();
            Vector wallOffset = pc.wallOffsets().get(facing);
            if (wallOffset != null) return wallOffset;
        }
        return pc.floorOffset();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Light blocks
    // ──────────────────────────────────────────────────────────────────────

    private void ensureLightBlock(Block block, CustomHeadBlock.LightConfig lc) {
        Block target = block.getRelative(lc.offsetX(), lc.offsetY(), lc.offsetZ());
        if (target.getType().isAir() || target.getType() == Material.LIGHT) {
            target.setType(Material.LIGHT);
            if (target.getBlockData() instanceof Levelled levelled) {
                levelled.setLevel(lc.level());
                target.setBlockData(levelled);
            }
        }
    }

    private void clearLightBlock(Block block, CustomHeadBlock type) {
        // Check base light config
        CustomHeadBlock.LightConfig lc = type.light();
        if (lc != null) {
            removeLightAt(block, lc);
        }
        // Check all state light configs
        for (var sc : type.states().values()) {
            if (sc.light() != null) {
                removeLightAt(block, sc.light());
            }
        }
    }

    private void removeLightAt(Block block, CustomHeadBlock.LightConfig lc) {
        Block target = block.getRelative(lc.offsetX(), lc.offsetY(), lc.offsetZ());
        if (target.getType() == Material.LIGHT) {
            target.setType(Material.AIR);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tasks
    // ──────────────────────────────────────────────────────────────────────

    void startTasks() {
        loadAllHints();

        // Redstone tick — every 2 ticks
        redstoneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRedstone, 2L, 2L);

        // Particle tick — every tick
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, 1L, 1L);

        // Custom block tick — every tick (individual intervals checked inside)
        customTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCustomBlocks, 1L, 1L);

        // Hint save — every 5 minutes
        hintSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAllHints, 6000L, 6000L);
    }

    /** Call after all blocks are loaded to register recipes with Bukkit. */
    void finalizeLoading() {
        registerRecipes();
    }

    void shutdown() {
        if (redstoneTask != null) redstoneTask.cancel();
        if (particleTask != null) particleTask.cancel();
        if (customTickTask != null) customTickTask.cancel();
        if (hintSaveTask != null) hintSaveTask.cancel();
        unregisterRecipes();
        saveAllHints();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Hint persistence
    // ──────────────────────────────────────────────────────────────────────

    private File hintFile(UUID worldId) {
        return new File(plugin.getDataFolder(), "hints/" + worldId + ".yml");
    }

    void loadHintsForWorld(UUID worldId) {
        File file = hintFile(worldId);
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> chunks = yaml.getStringList("chunks");
        if (!chunks.isEmpty()) {
            chunkHints.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet()).addAll(chunks);
        }
    }

    void saveHintsForWorld(UUID worldId) {
        if (!dirtyHintWorlds.contains(worldId)) return;
        Set<String> hints = chunkHints.get(worldId);
        File file = hintFile(worldId);
        if (hints == null || hints.isEmpty()) {
            if (file.exists()) file.delete();
            dirtyHintWorlds.remove(worldId);
            return;
        }
        file.getParentFile().mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("chunks", new ArrayList<>(hints));
        try {
            yaml.save(file);
            dirtyHintWorlds.remove(worldId);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save chunk hints for world " + worldId, e);
        }
    }

    void loadAllHints() {
        File hintsDir = new File(plugin.getDataFolder(), "hints");
        if (!hintsDir.exists()) return;

        File[] files = hintsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                UUID worldId = UUID.fromString(file.getName().replace(".yml", ""));
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                List<String> chunks = yaml.getStringList("chunks");
                if (!chunks.isEmpty()) {
                    chunkHints.put(worldId, ConcurrentHashMap.newKeySet());
                    chunkHints.get(worldId).addAll(chunks);
                }
            } catch (IllegalArgumentException e) {
                // Skip files with invalid UUID names
            }
        }
    }

    void saveAllHints() {
        for (UUID worldId : dirtyHintWorlds) {
            Set<String> hints = chunkHints.get(worldId);
            File file = hintFile(worldId);
            if (hints == null || hints.isEmpty()) {
                if (file.exists()) file.delete();
                continue;
            }

            file.getParentFile().mkdirs();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("chunks", new ArrayList<>(hints));
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save chunk hints for world " + worldId, e);
            }
        }
        dirtyHintWorlds.clear();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Recipe registration
    // ──────────────────────────────────────────────────────────────────────

    private final List<org.bukkit.NamespacedKey> registeredRecipeKeys = new ArrayList<>();
    // Recipes that have custom block ingredients — keyed by recipe NamespacedKey
    // Maps recipe key → map of (ingredient slot char → required block ID)
    private final Map<org.bukkit.NamespacedKey, Map<Character, String>> headIngredientRecipes = new HashMap<>();

    /** Record for head-input stonecutter recipes (not registered with Bukkit). */
    public record HeadStonecutterRecipe(String inputBlockId, String outputBlockId, int amount) {}
    private final List<HeadStonecutterRecipe> headStonecutterRecipes = new ArrayList<>();

    /** Register all recipes for all registered block types. Call after all blocks are loaded. */
    @SuppressWarnings("deprecation")
    void registerRecipes() {
        for (CustomHeadBlock type : types.values()) {
            if (!type.hasRecipes()) continue;
            String prefix = type.namespace() + "_" + type.typeId() + "_";

            // Shaped recipes
            for (CustomHeadBlock.ShapedRecipeDef r : type.shapedRecipes()) {
                try {
                    org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, prefix + r.id());
                    org.bukkit.inventory.ItemStack result = type.createItem(r.amount());
                    org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, result);
                    recipe.shape(r.pattern().toArray(new String[0]));
                    Map<Character, String> headIngredients = new HashMap<>();
                    for (var entry : r.key().entrySet()) {
                        CustomHeadBlock.IngredientSpec spec = entry.getValue();
                        if (spec.isMaterial()) {
                            recipe.setIngredient(entry.getKey(), spec.material());
                        } else if (spec.isBlock()) {
                            recipe.setIngredient(entry.getKey(),
                                    new org.bukkit.inventory.RecipeChoice.MaterialChoice(Material.PLAYER_HEAD));
                            headIngredients.put(entry.getKey(), spec.blockId());
                        }
                    }
                    Bukkit.addRecipe(recipe);
                    registeredRecipeKeys.add(key);
                    if (!headIngredients.isEmpty()) {
                        headIngredientRecipes.put(key, headIngredients);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to register shaped recipe '" + prefix + r.id() + "': " + e.getMessage());
                }
            }

            // Shapeless recipes
            for (CustomHeadBlock.ShapelessRecipeDef r : type.shapelessRecipes()) {
                try {
                    org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, prefix + r.id());
                    org.bukkit.inventory.ItemStack result = type.createItem(r.amount());
                    org.bukkit.inventory.ShapelessRecipe recipe = new org.bukkit.inventory.ShapelessRecipe(key, result);
                    boolean hasHeadIngredients = false;
                    for (CustomHeadBlock.IngredientSpec spec : r.ingredients()) {
                        if (spec.isMaterial()) {
                            recipe.addIngredient(spec.material());
                        } else if (spec.isBlock()) {
                            recipe.addIngredient(
                                    new org.bukkit.inventory.RecipeChoice.MaterialChoice(Material.PLAYER_HEAD));
                            hasHeadIngredients = true;
                        }
                    }
                    Bukkit.addRecipe(recipe);
                    registeredRecipeKeys.add(key);
                    if (hasHeadIngredients) {
                        Map<Character, String> specMap = new HashMap<>();
                        int idx = 0;
                        for (CustomHeadBlock.IngredientSpec spec : r.ingredients()) {
                            if (spec.isBlock()) {
                                specMap.put((char) ('0' + idx), spec.blockId());
                            }
                            idx++;
                        }
                        headIngredientRecipes.put(key, specMap);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to register shapeless recipe '" + prefix + r.id() + "': " + e.getMessage());
                }
            }

            // Stonecutter recipes
            for (CustomHeadBlock.StonecutterRecipeDef r : type.stonecutterRecipes()) {
                try {
                    CustomHeadBlock.IngredientSpec input = r.input();
                    if (input.isMaterial()) {
                        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, prefix + "sc_" + r.id());
                        org.bukkit.inventory.ItemStack result = type.createItem(r.amount());
                        org.bukkit.inventory.StonecuttingRecipe recipe = new org.bukkit.inventory.StonecuttingRecipe(
                                key, result, new org.bukkit.inventory.RecipeChoice.MaterialChoice(input.material()));
                        Bukkit.addRecipe(recipe);
                        registeredRecipeKeys.add(key);
                    } else if (input.isBlock()) {
                        headStonecutterRecipes.add(new HeadStonecutterRecipe(input.blockId(), type.fullId(), r.amount()));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to register stonecutter recipe '" + prefix + r.id() + "': " + e.getMessage());
                }
            }
        }
    }

    /** Remove all previously registered recipes. */
    void unregisterRecipes() {
        for (org.bukkit.NamespacedKey key : registeredRecipeKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipeKeys.clear();
        headIngredientRecipes.clear();
        headStonecutterRecipes.clear();
    }

    /** Get head ingredient requirements for a recipe key (for PrepareItemCraftEvent validation). */
    @Nullable Map<Character, String> getHeadIngredients(org.bukkit.NamespacedKey recipeKey) {
        return headIngredientRecipes.get(recipeKey);
    }

    /** Get head-input stonecutter recipes matching an input block ID. */
    public List<HeadStonecutterRecipe> getStonecutterRecipesForInput(String inputBlockId) {
        return headStonecutterRecipes.stream()
                .filter(r -> r.inputBlockId().equals(inputBlockId))
                .toList();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal tracking records
    // ──────────────────────────────────────────────────────────────────────

    private static @Nullable BlockFace getSkullFacing(Block block) {
        if (block.getType() == Material.PLAYER_WALL_HEAD && block.getBlockData() instanceof Directional dir) {
            return dir.getFacing();
        }
        return null; // floor head has no directional facing
    }

    private record LocationKey(UUID worldId, int x, int y, int z) {
        static LocationKey of(Block block) {
            return new LocationKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class RedstoneTracked {
        final Block block;
        final CustomHeadBlock type;
        int lastPower;
        RedstoneTracked(Block block, CustomHeadBlock type, int lastPower) {
            this.block = block;
            this.type = type;
            this.lastPower = lastPower;
        }
    }

    private static final class ParticleTracked {
        final Block block;
        final CustomHeadBlock type;
        final CustomHeadBlock.ParticleConfig config;
        ParticleTracked(Block block, CustomHeadBlock type, CustomHeadBlock.ParticleConfig config) {
            this.block = block;
            this.type = type;
            this.config = config;
        }
    }

    private static final class TickTracked {
        final Block block;
        final CustomHeadBlock type;
        TickTracked(Block block, CustomHeadBlock type) {
            this.block = block;
            this.type = type;
        }
    }
}
