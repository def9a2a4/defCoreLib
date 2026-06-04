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

    private @Nullable BukkitTask redstoneTask;
    private @Nullable BukkitTask particleTask;
    private @Nullable BukkitTask hintSaveTask;

    CustomBlockRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Type registration
    // ──────────────────────────────────────────────────────────────────────

    public void register(CustomHeadBlock type) {
        types.put(type.fullId(), type);
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
        Skull skull = (Skull) block.getState();
        String typeId = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) return null;
        return types.get(typeId);
    }

    public Collection<CustomHeadBlock> allTypes() {
        return Collections.unmodifiableCollection(types.values());
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
        markChunkDirty(block);
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

        boolean foundAny = false;
        for (BlockState tile : chunk.getTileEntities()) {
            if (!(tile instanceof Skull skull)) continue;
            String typeId = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (typeId == null) continue;

            CustomHeadBlock type = types.get(typeId);
            if (type == null) continue;

            foundAny = true;
            Block block = skull.getBlock();
            String state = skull.getPersistentDataContainer().get(STATE_KEY, PersistentDataType.STRING);

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

            // Dispatch to block type
            if (type.needsChunkScan()) {
                type.resolveDisplayEntities(state); // ensure displays are noted
                // Plugin-specific restore happens here via onChunkLoad lifecycle
                // (Not called here — CoreLibPlugin dispatches events separately)
            }
        }

        if (!foundAny) {
            removeChunkHint(chunk);
        }
    }

    void onChunkUnload(Chunk chunk) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        World world = chunk.getWorld();

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
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block lifecycle helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Apply the resolved config for a block's current state + power. */
    void applyConfig(Block block, CustomHeadBlock type, @Nullable String state, int power) {
        // Texture
        String texture = type.resolveTexture(state, power);
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
    }

    /** Handle block removal: clean up displays, light, particles, redstone tracking. */
    void onBlockRemoved(Block block, CustomHeadBlock type) {
        LocationKey key = LocationKey.of(block);
        redstoneTracked.remove(key);
        particleTracked.remove(key);

        // Remove display entities
        if (type.hasDisplayEntities()) {
            String tagPrefix = DisplayUtil.typeTagPrefix(type.namespace(), type.typeId());
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

            block.getWorld().spawnParticle(pc.type(), x, y, z,
                    count, 0.05, 0.05, 0.05, speed);
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
        if (target.getType() == Material.AIR || target.getType() == Material.LIGHT) {
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

        // Hint save — every 5 minutes
        hintSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAllHints, 6000L, 6000L);
    }

    void shutdown() {
        if (redstoneTask != null) redstoneTask.cancel();
        if (particleTask != null) particleTask.cancel();
        if (hintSaveTask != null) hintSaveTask.cancel();
        saveAllHints();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Hint persistence
    // ──────────────────────────────────────────────────────────────────────

    private File hintFile(UUID worldId) {
        return new File(plugin.getDataFolder(), "hints/" + worldId + ".yml");
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
    // Internal tracking records
    // ──────────────────────────────────────────────────────────────────────

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
}
