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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
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

    // PDC keys for captured blade banner data (stored on both items and skulls)
    static final NamespacedKey[] BLADE_KEYS = {
            new NamespacedKey("corelib", "blade_0"),
            new NamespacedKey("corelib", "blade_1"),
            new NamespacedKey("corelib", "blade_2"),
            new NamespacedKey("corelib", "blade_3")
    };

    /** Copy blade PDC data between two PersistentDataContainers. */
    static void copyBladePdc(org.bukkit.persistence.PersistentDataContainer src,
                             org.bukkit.persistence.PersistentDataContainer dst) {
        for (NamespacedKey key : BLADE_KEYS) {
            byte[] data = src.get(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
            if (data != null) dst.set(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, data);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sail (blade banner) lore generation
    // ──────────────────────────────────────────────────────────────────────

    /** Human-readable label for a sail banner: its custom name if it has one,
     *  otherwise the base colour name plus a pattern count (e.g. "Light Blue Banner (2 patterns)"). */
    static String describeBanner(ItemStack banner) {
        ItemMeta meta = banner.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        // Base colour from the material: LIGHT_BLUE_BANNER → "Light Blue Banner"
        String raw = banner.getType().name();
        if (raw.endsWith("_BANNER")) raw = raw.substring(0, raw.length() - "_BANNER".length());
        StringBuilder label = new StringBuilder();
        for (String word : raw.split("_")) {
            if (word.isEmpty()) continue;
            if (label.length() > 0) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0)))
                 .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        label.append(" Banner");
        if (meta instanceof BannerMeta bm && !bm.getPatterns().isEmpty()) {
            int n = bm.getPatterns().size();
            label.append(" (").append(n).append(n == 1 ? " pattern)" : " patterns)");
        }
        return label.toString();
    }

    /** Header line that introduces the sail listing (used for both rendering and idempotent stripping). */
    private static final String SAIL_HEADER = "Sails:";

    /** Build the sail lore block: a "Sails:" header followed by one line per distinct banner, in
     *  first-seen order, with an "Nx" prefix when a banner appears more than once (nulls ignored).
     *  Returns an empty list when there are no banners. */
    static List<Component> sailLoreLines(List<ItemStack> banners) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack banner : banners) {
            if (banner == null) continue;
            counts.merge(describeBanner(banner), 1, Integer::sum);
        }
        if (counts.isEmpty()) return List.of();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(SAIL_HEADER, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String prefix = e.getValue() > 1 ? e.getValue() + "x " : "";
            lines.add(Component.text(" • " + prefix + e.getKey(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }

    /** Replace the item's lore entirely with the sail lore block. Non-sail (YAML) lore is dropped.
     *  If there are no banners, the existing lore is left untouched. */
    static void applySailLore(ItemMeta meta, List<ItemStack> banners) {
        List<Component> sailLines = sailLoreLines(banners);
        if (sailLines.isEmpty()) return;
        meta.lore(sailLines);
    }

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
    // Animation tracking: display entities with active animations
    private final Map<LocationKey, List<AnimationTracked>> animationTracked = new HashMap<>();
    // Animation direction: CW (default) or CCW per block location
    private final Map<LocationKey, RotationNetwork.SpinDirection> animationDirection = new HashMap<>();

    private @Nullable BukkitTask redstoneTask;
    private @Nullable BukkitTask particleTask;
    private @Nullable BukkitTask customTickTask;
    private @Nullable BukkitTask animationTask;
    private @Nullable BukkitTask hintSaveTask;
    private boolean finalized;

    // Reusable work matrix for animation tick (avoids allocation per frame)
    private final Matrix4f animationWorkMatrix = new Matrix4f();

    CustomBlockRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    JavaPlugin getPlugin() { return plugin; }

    // ──────────────────────────────────────────────────────────────────────
    // Type registration
    // ──────────────────────────────────────────────────────────────────────

    public void register(CustomHeadBlock type) {
        types.put(type.fullId(), type);
        rescanLoadedChunks(type);
        if (finalized) {
            registerRecipesForType(type);
        }
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

    /** The custom item id (BLOCK_TYPE_KEY PDC) stamped on an ItemStack by createItem, or null. */
    public static @Nullable String getItemTypeId(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
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

    // ──────────────────────────────────────────────────────────────────────
    // Orphaned display-entity cleanup (admin command: /defcorelib cleanorphans)
    // ──────────────────────────────────────────────────────────────────────

    /** Outcome of an orphan scan. {@code samples} holds up to ~10 human-readable lines. */
    record OrphanScanResult(int orphans, int live, int skippedUnloaded, List<String> samples) {}

    /** Owning custom block reference encoded in a block-attached display entity's tag. */
    private record DisplayOwnerTag(String fullId, int x, int y, int z) {}

    /**
     * Scan every loaded world for block-attached display entities whose parent custom block is
     * gone or has been replaced by a different block, optionally despawning them.
     *
     * <p>Identity is verified against the skull's raw {@link #BLOCK_TYPE_KEY} PDC string rather
     * than the live type registry, so a display whose type merely isn't registered right now
     * (plugin load order) is NOT treated as orphaned. Banner ({@code corelib:banner:}) and
     * mechanism ({@code corelib:mech:}) display tags are skipped. A display whose owner sits in an
     * unloaded chunk is counted but never removed.
     *
     * @param remove if true, despawn each orphan; if false, only count (preview)
     */
    OrphanScanResult scanOrphanedDisplays(boolean remove) {
        int orphans = 0, live = 0, skippedUnloaded = 0;
        List<String> samples = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Display display : world.getEntitiesByClass(Display.class)) {
                DisplayOwnerTag owner = parseBlockDisplayTag(display);
                if (owner == null) continue;
                if (!world.isChunkLoaded(owner.x() >> 4, owner.z() >> 4)) {
                    skippedUnloaded++;
                    continue;
                }
                if (isOwnerPresent(world, owner)) {
                    live++;
                    continue;
                }
                orphans++;
                if (samples.size() < 10) {
                    samples.add(world.getName() + " " + owner.x() + "," + owner.y() + "," + owner.z()
                            + " " + owner.fullId());
                }
                if (remove) display.remove();
            }
        }
        return new OrphanScanResult(orphans, live, skippedUnloaded, samples);
    }

    /** Extract the owning custom block reference from a block-attached display tag, or null if the
     *  entity carries no such tag. Format: {@code corelib:{ns}:{type}:{x}_{y}_{z}[:{suffix}]}. */
    private @Nullable DisplayOwnerTag parseBlockDisplayTag(Display display) {
        for (String tag : display.getScoreboardTags()) {
            if (!tag.startsWith("corelib:")) continue;
            if (tag.startsWith("corelib:banner:") || tag.startsWith("corelib:mech:")) continue;
            String[] parts = tag.split(":");
            if (parts.length < 4) continue;
            String[] coords = parts[3].split("_");
            if (coords.length != 3) continue;
            try {
                int x = Integer.parseInt(coords[0]);
                int y = Integer.parseInt(coords[1]);
                int z = Integer.parseInt(coords[2]);
                return new DisplayOwnerTag(parts[1] + ":" + parts[2], x, y, z);
            } catch (NumberFormatException ignored) {
                // Not a block-display tag (some other corelib tag shape) — keep scanning.
            }
        }
        return null;
    }

    /** True if the block at the owner location is still the custom block named by the tag,
     *  verified against the skull's raw {@link #BLOCK_TYPE_KEY} PDC (not the type registry). */
    private boolean isOwnerPresent(World world, DisplayOwnerTag owner) {
        Block block = world.getBlockAt(owner.x(), owner.y(), owner.z());
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return false;
        }
        if (!(block.getState() instanceof Skull skull)) return false;
        String fullId = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
        return owner.fullId().equals(fullId);
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
    public void markBlock(Block block, CustomHeadBlock type, @Nullable String initialState) {
        if (!(block.getState() instanceof Skull skull)) return;
        PersistentDataContainer pdc = skull.getPersistentDataContainer();
        pdc.set(BLOCK_TYPE_KEY, PersistentDataType.STRING, type.fullId());
        String effectiveState = initialState != null ? initialState : type.defaultState();
        if (effectiveState != null) {
            pdc.set(STATE_KEY, PersistentDataType.STRING, effectiveState);
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
    public void restoreBlock(Block block, CustomHeadBlock type, @Nullable String state) {
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
            trackTick(block, type);
        }

        // Re-register animation tracking for persisted display entities
        trackAnimations(block, type, state);

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

        // Remove animation tracking + direction for blocks in this chunk
        java.util.function.Predicate<Map.Entry<LocationKey, ?>> inChunk = e -> {
            LocationKey loc = e.getKey();
            return loc.worldId().equals(world.getUID())
                    && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ;
        };
        animationTracked.entrySet().removeIf(inChunk);
        animationDirection.entrySet().removeIf(inChunk);

        // Save open storages in this chunk
        saveStoragesInChunk(world, chunkX, chunkZ);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block lifecycle helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Apply the resolved config for a block's current state + power. */
    public void applyConfig(Block block, CustomHeadBlock type, @Nullable String state, int power) {
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
            List<AnimationTracked> anims = null;
            for (int i = 0; i < displays.size(); i++) {
                var dec = displays.get(i);
                ItemStack displayItem = dec.displayItem();
                if (dec.tagSuffix() != null) {
                    ItemStack resolved = type.ingredientCapture() != null
                            ? IngredientCapture.resolveDisplay(block, dec.tagSuffix())
                            : (type.displayItemResolver() != null
                                ? type.displayItemResolver().apply(block, dec.tagSuffix()) : null);
                    if (resolved != null) displayItem = resolved;
                }
                Transformation transform = dec.transform();
                if (type.displayTransformResolver() != null) {
                    Transformation resolved = type.displayTransformResolver()
                            .resolve(block, state, dec, i);
                    if (resolved != null) transform = resolved;
                }
                String tag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                        block.getLocation(), dec.tagSuffix());
                org.bukkit.Location spawnBase = block.getLocation();
                if (dec.wallOffset() != 0 && block.getType() == Material.PLAYER_WALL_HEAD
                        && block.getBlockData() instanceof Directional wallDir) {
                    Vector wallFacing = wallDir.getFacing().getDirection();
                    spawnBase = spawnBase.clone().subtract(wallFacing.multiply(dec.wallOffset()));
                }
                var display = DisplayUtil.spawn(spawnBase, displayItem, transform, tag);
                if (dec.interpolationDuration() != 0) {
                    display.setInterpolationDuration(dec.interpolationDuration());
                }
                if (dec.animation() != null) {
                    if (anims == null) anims = new ArrayList<>();
                    anims.add(new AnimationTracked(display, dec.animation(),
                            Bukkit.getServer().getCurrentTick(),
                            transformToMatrix(dec.transform())));
                }
            }
            // Block display entities
            List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplays = type.resolveBlockDisplayEntities(state);
            for (var bdc : blockDisplays) {
                String tag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                        block.getLocation(), bdc.tagSuffix());
                org.bukkit.Location spawnBase = block.getLocation();
                if (bdc.wallOffset() != 0 && block.getType() == Material.PLAYER_WALL_HEAD
                        && block.getBlockData() instanceof Directional wallDir) {
                    Vector wallFacing = wallDir.getFacing().getDirection();
                    spawnBase = spawnBase.clone().subtract(wallFacing.multiply(bdc.wallOffset()));
                }
                Matrix4f matrix = transformToMatrix(bdc.transform());
                var display = DisplayUtil.spawnBlock(spawnBase, bdc.blockData(), matrix, tag);
                if (bdc.interpolationDuration() != 0) {
                    display.setInterpolationDuration(bdc.interpolationDuration());
                }
                if (bdc.animation() != null) {
                    if (anims == null) anims = new ArrayList<>();
                    anims.add(new AnimationTracked(display, bdc.animation(),
                            Bukkit.getServer().getCurrentTick(),
                            transformToMatrix(bdc.transform())));
                }
            }

            if (anims != null) {
                animationTracked.put(key, anims);
                RotationNetwork.SpinDirection dir = animationDirection.get(key);
                if (dir == RotationNetwork.SpinDirection.CCW) {
                    for (AnimationTracked at : anims) at.reversed = true;
                }
            } else {
                animationTracked.remove(key);
            }
        }
    }

    /** Handle block removal: clean up displays, light, particles, redstone tracking. */
    public void onBlockRemoved(Block block, CustomHeadBlock type) {
        // Notify consumer before cleanup
        if (type.onBlockRemoved() != null) {
            String state = getState(block);
            type.onBlockRemoved().accept(block, state);
        }

        LocationKey key = LocationKey.of(block);
        redstoneTracked.remove(key);
        particleTracked.remove(key);
        neighborReactiveBlocks.remove(key);
        tickTracked.remove(key);
        customBlockLocations.remove(key);
        animationTracked.remove(key);
        animationDirection.remove(key);

        // Remove display entities (use location-specific prefix to avoid hitting nearby blocks)
        if (type.hasDisplayEntities()) {
            String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
            DisplayUtil.removeByTag(block.getLocation(), tagPrefix, 1.5);
        }

        // Remove light block
        clearLightBlock(block, type);
    }

    void resolveDisplayTransforms(Block block, CustomHeadBlock type, @Nullable String state) {
        if (type.displayTransformResolver() == null || !type.hasDisplayEntities()) return;
        List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
        String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
        List<Display> existing = DisplayUtil.findByTag(block.getLocation(), tagPrefix, 1.5);

        for (int i = 0; i < displays.size(); i++) {
            var dec = displays.get(i);
            String fullTag = DisplayUtil.blockTag(
                    type.namespace(), type.typeId(), block.getLocation(), dec.tagSuffix());
            Transformation resolved = type.displayTransformResolver()
                    .resolve(block, state, dec, i);
            if (resolved == null) continue;

            for (Display d : existing) {
                if (d.getScoreboardTags().contains(fullTag)) {
                    d.setTransformation(resolved);
                    break;
                }
            }
        }
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

        // Notify consumer
        if (type.onStateChanged() != null) {
            type.onStateChanged().accept(block, fromState, transition.toState());
        }
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
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) continue;

            CustomHeadBlock type = entry.type;
            int newPower = readPower(block, type);
            boolean changed = switch (type.sensitivity()) {
                case NONE -> false;
                case BINARY -> (entry.lastPower == 0) != (newPower == 0);
                case LEVEL -> entry.lastPower != newPower;
            };

            if (!changed) continue;
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

    void trackTick(Block block, CustomHeadBlock type) {
        tickTracked.put(LocationKey.of(block), new TickTracked(block, type));
    }

    void tickParticles() {
        int currentTick = Bukkit.getServer().getCurrentTick();
        for (var entry : particleTracked.values()) {
            Block block = entry.block;
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) continue;

            if (entry.failed) continue;

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

            try {
                if (pc.data() != null) {
                    block.getWorld().spawnParticle(pc.type(), x, y, z,
                            count, 0.05, 0.05, 0.05, speed, pc.data());
                } else {
                    block.getWorld().spawnParticle(pc.type(), x, y, z,
                            count, 0.05, 0.05, 0.05, speed);
                }
            } catch (IllegalArgumentException ex) {
                // Misconfigured particle (e.g. missing required data). Disable this entry so it
                // degrades to a single warning instead of flooding the log every tick.
                entry.failed = true;
                plugin.getLogger().warning("Disabling particle " + pc.type() + " at "
                        + block.getX() + "," + block.getY() + "," + block.getZ()
                        + " — " + ex.getMessage());
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
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) continue;

            CustomHeadBlock type = entry.type;
            Integer interval = type.tickInterval();
            if (interval == null || type.onTick() == null) continue;
            if (currentTick % interval != 0) continue;

            type.onTick().accept(block);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Animation ticking
    // ──────────────────────────────────────────────────────────────────────

    void tickAnimations() {
        long currentTick = Bukkit.getServer().getCurrentTick();
        for (var entry : animationTracked.values()) {
            for (var tracked : entry) {
                if (!tracked.display.isValid()) continue;
                long tickAge = currentTick - tracked.startTick;
                if (tracked.reversed) tickAge = -tickAge;
                tracked.animation.apply(tracked.baseTransform, tickAge, animationWorkMatrix);
                tracked.display.setTransformationMatrix(animationWorkMatrix);
            }
        }
    }

    /** Whether the block's display animation currently spins CCW (reversed). Read by the mechanism
     *  snapshot before {@link #onBlockRemoved} clears the direction, so a glued block keeps its spin. */
    boolean isSpinReversed(LocationKey key) {
        return animationDirection.get(key) == RotationNetwork.SpinDirection.CCW;
    }

    void setAnimationDirection(LocationKey key, RotationNetwork.SpinDirection dir) {
        animationDirection.put(key, dir);
        boolean reversed = (dir == RotationNetwork.SpinDirection.CCW);
        List<AnimationTracked> tracked = animationTracked.get(key);
        if (tracked != null) {
            for (AnimationTracked at : tracked) {
                at.reversed = reversed;
            }
        }
    }

    /** Re-register animation tracking for existing display entities (after chunk load). */
    private void trackAnimations(Block block, CustomHeadBlock type, @Nullable String state) {
        List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
        List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplays = type.resolveBlockDisplayEntities(state);

        boolean hasItemAnims = displays.stream().anyMatch(d -> d.animation() != null);
        boolean hasBlockAnims = blockDisplays.stream().anyMatch(d -> d.animation() != null);
        if (!hasItemAnims && !hasBlockAnims) return;

        String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
        List<Display> found = DisplayUtil.findByTag(block.getLocation(), tagPrefix, 1.5);
        if (found.isEmpty()) return;

        long startTick = Bukkit.getServer().getCurrentTick();
        List<AnimationTracked> anims = new ArrayList<>();

        // ItemDisplay animations
        for (var dec : displays) {
            if (dec.animation() == null) continue;
            String expectedTag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                    block.getLocation(), dec.tagSuffix());
            for (var display : found) {
                if (display.getScoreboardTags().contains(expectedTag)) {
                    anims.add(new AnimationTracked(display, dec.animation(), startTick,
                            transformToMatrix(dec.transform())));
                    break;
                }
            }
        }

        // BlockDisplay animations
        for (var bdc : blockDisplays) {
            if (bdc.animation() == null) continue;
            String expectedTag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                    block.getLocation(), bdc.tagSuffix());
            for (var display : found) {
                if (display instanceof BlockDisplay && display.getScoreboardTags().contains(expectedTag)) {
                    anims.add(new AnimationTracked(display, bdc.animation(), startTick,
                            transformToMatrix(bdc.transform())));
                    break;
                }
            }
        }

        if (!anims.isEmpty()) {
            animationTracked.put(LocationKey.of(block), anims);
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

        // Animation tick — every tick
        animationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAnimations, 1L, 1L);

        // Hint save — every 5 minutes
        hintSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAllHints, 6000L, 6000L);
    }

    /** Call after all blocks are loaded to register recipes with Bukkit. */
    void finalizeLoading() {
        registerRecipes();
        finalized = true;
    }

    void shutdown() {
        if (redstoneTask != null) redstoneTask.cancel();
        if (particleTask != null) particleTask.cancel();
        if (customTickTask != null) customTickTask.cancel();
        if (animationTask != null) animationTask.cancel();
        if (hintSaveTask != null) hintSaveTask.cancel();
        saveAllOpenStorages();
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

    /** Record for head-input stonecutter recipes (not registered with Bukkit). */
    public record HeadStonecutterRecipe(String inputBlockId, String outputBlockId, int amount) {}
    private final List<HeadStonecutterRecipe> headStonecutterRecipes = new ArrayList<>();

    record ToggleRecipeInfo(String blockId, Material outputMaterial) {}
    private final Map<org.bukkit.NamespacedKey, ToggleRecipeInfo> toggleRecipes = new HashMap<>();

    /** Register all recipes for all registered block types. Call after all blocks are loaded. */
    void registerRecipes() {
        for (CustomHeadBlock type : types.values()) {
            registerRecipesForType(type);
        }
    }

    private void registerRecipesForType(CustomHeadBlock type) {
        if (!type.hasRecipes()) return;
        String prefix = type.namespace() + "_" + type.typeId() + "_";
        int keysBefore = registeredRecipeKeys.size();

        // Shaped recipes
        for (CustomHeadBlock.ShapedRecipeDef r : type.shapedRecipes()) {
            try {
                org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, prefix + r.id());
                org.bukkit.inventory.ItemStack result = type.createItem(r.amount());
                org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, result);
                recipe.shape(r.pattern().toArray(new String[0]));
                recipe.setCategory(categoryFor(type));
                for (var entry : r.key().entrySet()) {
                    CustomHeadBlock.IngredientSpec spec = entry.getValue();
                    if (spec.isTag()) {
                        recipe.setIngredient(entry.getKey(),
                                new org.bukkit.inventory.RecipeChoice.MaterialChoice(spec.tag()));
                    } else if (spec.isMaterial()) {
                        recipe.setIngredient(entry.getKey(), spec.material());
                    } else if (spec.isBlock()) {
                        recipe.setIngredient(entry.getKey(), choiceForBlock(spec.blockId()));
                    }
                }
                Bukkit.addRecipe(recipe);
                registeredRecipeKeys.add(key);
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
                recipe.setCategory(categoryFor(type));
                for (CustomHeadBlock.IngredientSpec spec : r.ingredients()) {
                    if (spec.isMaterial()) {
                        recipe.addIngredient(spec.material());
                    } else if (spec.isBlock()) {
                        recipe.addIngredient(choiceForBlock(spec.blockId()));
                    }
                }
                Bukkit.addRecipe(recipe);
                registeredRecipeKeys.add(key);
                if (type.itemMaterial() != null
                        && r.ingredients().size() == 1
                        && r.ingredients().get(0).isMaterial()
                        && r.ingredients().get(0).material() == type.itemMaterial()) {
                    toggleRecipes.put(key, new ToggleRecipeInfo(type.fullId(), type.itemMaterial()));
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

        // Track advancement requirement for all recipes registered for this type
        if (type.unlockAdvancement() != null) {
            for (int i = keysBefore; i < registeredRecipeKeys.size(); i++) {
                trackAdvancementRecipe(type.unlockAdvancement(), registeredRecipeKeys.get(i));
            }
        }
    }

    /** RecipeChoice for a custom-block ingredient: an ExactChoice of the referenced type's item,
     *  so the recipe book renders the real head/item and matches it by its (PDC-bearing) meta —
     *  no PrepareItemCraft PDC check needed. Falls back to any player head if the referenced type
     *  isn't registered (a YAML typo). */
    private org.bukkit.inventory.RecipeChoice choiceForBlock(String blockId) {
        CustomHeadBlock refType = getType(blockId);
        if (refType == null) {
            plugin.getLogger().warning("Recipe ingredient references unknown block '" + blockId
                    + "' — falling back to any player head");
            return new org.bukkit.inventory.RecipeChoice.MaterialChoice(Material.PLAYER_HEAD);
        }
        return new org.bukkit.inventory.RecipeChoice.ExactChoice(refType.createItem(1));
    }

    /** Recipe-book tab for a type's crafting recipes: its explicit recipe_category if set, else a
     *  namespace default (rotation → Redstone, verticalslabs → Building, everything else → Misc). */
    private static org.bukkit.inventory.recipe.CraftingBookCategory categoryFor(CustomHeadBlock type) {
        if (type.recipeCategory() != null) return type.recipeCategory();
        return switch (type.namespace()) {
            case "rotation" -> org.bukkit.inventory.recipe.CraftingBookCategory.REDSTONE;
            case "verticalslabs" -> org.bukkit.inventory.recipe.CraftingBookCategory.BUILDING;
            default -> org.bukkit.inventory.recipe.CraftingBookCategory.MISC;
        };
    }

    /** Remove all previously registered recipes. */
    void unregisterRecipes() {
        for (org.bukkit.NamespacedKey key : registeredRecipeKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipeKeys.clear();
        headStonecutterRecipes.clear();
        toggleRecipes.clear();
    }

    @Nullable ToggleRecipeInfo getToggleRecipe(org.bukkit.NamespacedKey recipeKey) {
        return toggleRecipes.get(recipeKey);
    }

    /** Get head-input stonecutter recipes matching an input block ID. */
    public List<HeadStonecutterRecipe> getStonecutterRecipesForInput(String inputBlockId) {
        return headStonecutterRecipes.stream()
                .filter(r -> r.inputBlockId().equals(inputBlockId))
                .toList();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Advancement-based recipe unlocking
    // ──────────────────────────────────────────────────────────────────────

    // advancement key string → list of recipe NamespacedKeys gated by that advancement
    private final Map<String, List<org.bukkit.NamespacedKey>> advancementRecipes = new HashMap<>();
    private final Set<org.bukkit.NamespacedKey> gatedRecipeKeys = new HashSet<>();

    /** Track that a recipe key requires an advancement. Called during recipe registration. */
    void trackAdvancementRecipe(String advancementKey, org.bukkit.NamespacedKey recipeKey) {
        // Normalize: if no namespace prefix, prepend "minecraft:"
        String normalized = advancementKey.contains(":") ? advancementKey : "minecraft:" + advancementKey;
        advancementRecipes.computeIfAbsent(normalized, k -> new ArrayList<>()).add(recipeKey);
        gatedRecipeKeys.add(recipeKey);
    }

    /** Sync recipe discovery for a player based on their advancement progress. */
    void syncRecipeDiscovery(org.bukkit.entity.Player player) {
        for (var entry : advancementRecipes.entrySet()) {
            String advKey = entry.getKey();
            org.bukkit.NamespacedKey nsKey = parseAdvancementKey(advKey);
            if (nsKey == null) continue;

            org.bukkit.advancement.Advancement adv = Bukkit.getAdvancement(nsKey);
            boolean done = adv != null && player.getAdvancementProgress(adv).isDone();

            for (org.bukkit.NamespacedKey recipeKey : entry.getValue()) {
                if (done) {
                    player.discoverRecipe(recipeKey);
                } else {
                    player.undiscoverRecipe(recipeKey);
                }
            }
        }

        // Recipes without an advancement requirement: always discover
        for (org.bukkit.NamespacedKey key : registeredRecipeKeys) {
            if (!gatedRecipeKeys.contains(key)) {
                player.discoverRecipe(key);
            }
        }
    }

    /** Discover recipes unlocked by a specific advancement. */
    void discoverForAdvancement(org.bukkit.entity.Player player, String advancementKey) {
        List<org.bukkit.NamespacedKey> keys = advancementRecipes.get(advancementKey);
        if (keys == null) return;
        for (org.bukkit.NamespacedKey key : keys) {
            player.discoverRecipe(key);
        }
    }

    private static org.bukkit.@Nullable NamespacedKey parseAdvancementKey(String key) {
        try {
            if (key.contains(":")) {
                String[] parts = key.split(":", 2);
                return new org.bukkit.NamespacedKey(parts[0], parts[1]);
            }
            return new org.bukkit.NamespacedKey("minecraft", key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Storage (custom inventories)
    // ──────────────────────────────────────────────────────────────────────

    static final org.bukkit.NamespacedKey INVENTORY_KEY = new org.bukkit.NamespacedKey("corelib", "inventory");
    private final Map<LocationKey, StorageHolder> openStorages = new HashMap<>();

    /**
     * Get or create the storage inventory for a block without opening a GUI.
     * Uses the same cache as {@link #openStorage} so ticks and viewers share one Inventory instance.
     */
    public org.bukkit.inventory.@org.jspecify.annotations.Nullable Inventory getOrCreateStorage(Block block) {
        CustomHeadBlock type = getTypeFromBlock(block);
        if (type == null || type.storage() == null) return null;

        LocationKey key = LocationKey.of(block);
        StorageHolder holder = openStorages.get(key);
        if (holder == null) {
            holder = new StorageHolder(block.getLocation());
            org.bukkit.inventory.Inventory inv = createStorageInventory(type.storage(), type, holder);
            holder.setInventory(inv);
            loadInventoryFromPDC(block, inv);
            openStorages.put(key, holder);
        }
        return holder.getInventory();
    }

    /** Open the storage inventory for a block. Supports multiple viewers. */
    void openStorage(Block block, org.bukkit.entity.Player player, CustomHeadBlock type) {
        LocationKey key = LocationKey.of(block);
        StorageHolder holder = openStorages.get(key);

        if (holder == null) {
            CustomHeadBlock.StorageConfig config = type.storage();
            if (config == null) return;

            holder = new StorageHolder(block.getLocation());
            org.bukkit.inventory.Inventory inv = createStorageInventory(config, type, holder);
            holder.setInventory(inv);
            loadInventoryFromPDC(block, inv);
            openStorages.put(key, holder);
        }

        player.openInventory(holder.getInventory());
    }

    /** Save storage inventory to PDC. Called on every close; remove from cache when last viewer gone. */
    void onStorageClosed(StorageHolder holder) {
        saveInventoryToPDC(holder.location(), holder.getInventory());
        if (holder.getInventory().getViewers().isEmpty()) {
            openStorages.remove(LocationKey.of(holder.location()));
        }
    }

    /** Save all open storages (called during shutdown and chunk unload). */
    void saveAllOpenStorages() {
        for (var holder : openStorages.values()) {
            saveInventoryToPDC(holder.location(), holder.getInventory());
            new ArrayList<>(holder.getInventory().getViewers()).forEach(v -> v.closeInventory());
        }
        openStorages.clear();
    }

    /** Save open storages in a specific chunk. */
    void saveStoragesInChunk(World world, int chunkX, int chunkZ) {
        openStorages.entrySet().removeIf(e -> {
            LocationKey loc = e.getKey();
            if (loc.worldId().equals(world.getUID())
                    && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ) {
                StorageHolder holder = e.getValue();
                saveInventoryToPDC(holder.location(), holder.getInventory());
                new ArrayList<>(holder.getInventory().getViewers()).forEach(v -> v.closeInventory());
                return true;
            }
            return false;
        });
    }

    /** Drop all storage contents and clean up when block is broken. */
    void dropStorage(Block block) {
        LocationKey key = LocationKey.of(block);
        StorageHolder holder = openStorages.remove(key);

        org.bukkit.inventory.Inventory inv;
        if (holder != null) {
            new ArrayList<>(holder.getInventory().getViewers()).forEach(v -> v.closeInventory());
            inv = holder.getInventory();
        } else {
            CustomHeadBlock type = getTypeFromBlock(block);
            if (type == null || type.storage() == null) return;
            holder = new StorageHolder(block.getLocation());
            inv = createStorageInventory(type.storage(), type, holder);
            holder.setInventory(inv);
            loadInventoryFromPDC(block, inv);
        }

        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
            }
        }
    }

    private org.bukkit.inventory.Inventory createStorageInventory(
            CustomHeadBlock.StorageConfig config, CustomHeadBlock type, StorageHolder holder) {
        net.kyori.adventure.text.Component title = type.name() != null
                ? type.name()
                : net.kyori.adventure.text.Component.text(type.fullId());

        return switch (config.layout()) {
            case HOPPER -> Bukkit.createInventory(holder, org.bukkit.event.inventory.InventoryType.HOPPER, title);
            case DROPPER -> Bukkit.createInventory(holder, org.bukkit.event.inventory.InventoryType.DROPPER, title);
            default -> Bukkit.createInventory(holder, config.layout().slots, title);
        };
    }

    public void loadInventoryFromPDC(Block block, org.bukkit.inventory.Inventory inv) {
        if (!(block.getState() instanceof org.bukkit.block.Skull skull)) return;
        String data = skull.getPersistentDataContainer().get(INVENTORY_KEY, PersistentDataType.STRING);
        if (data == null) return;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(data);
            var stream = new java.io.ByteArrayInputStream(bytes);
            var ois = new org.bukkit.util.io.BukkitObjectInputStream(stream);
            int size = ois.readInt();
            for (int i = 0; i < size; i++) {
                inv.setItem(i, (ItemStack) ois.readObject());
            }
            ois.close();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load inventory: " + e.getMessage());
        }
    }

    private void saveInventoryToPDC(org.bukkit.Location loc, org.bukkit.inventory.Inventory inv) {
        Block block = loc.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Skull skull)) return;
        try {
            var stream = new java.io.ByteArrayOutputStream();
            var oos = new org.bukkit.util.io.BukkitObjectOutputStream(stream);
            oos.writeInt(inv.getSize());
            for (int i = 0; i < inv.getSize(); i++) {
                oos.writeObject(inv.getItem(i));
            }
            oos.close();
            String data = java.util.Base64.getEncoder().encodeToString(stream.toByteArray());
            skull.getPersistentDataContainer().set(INVENTORY_KEY, PersistentDataType.STRING, data);
            skull.update();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save inventory: " + e.getMessage());
        }
    }

    /**
     * Snapshot a storage block's CURRENT contents (the live cached holder if one is open, else the PDC)
     * and evict any cache entry — the block is being removed from the world into a mechanism. Returns a
     * deep-cloned inventory that does not alias the live holder's ItemStacks, or null if the block has no
     * storage config. Used by the mechanism-assembly capture path.
     */
    public org.bukkit.inventory.@Nullable Inventory takeStorageSnapshot(Block block) {
        CustomHeadBlock type = getTypeFromBlock(block);
        if (type == null || type.storage() == null) return null;
        org.bukkit.inventory.Inventory snap = Bukkit.createInventory(null, type.storage().layout().slots);
        StorageHolder holder = openStorages.remove(LocationKey.of(block));   // evict: block is leaving
        if (holder != null) {
            new ArrayList<>(holder.getInventory().getViewers()).forEach(v -> v.closeInventory());
            snap.setContents(holder.getInventory().getContents());
        } else {
            loadInventoryFromPDC(block, snap);
        }
        for (int s = 0; s < snap.getSize(); s++) {                            // break aliasing with holder
            ItemStack it = snap.getItem(s);
            if (it != null) snap.setItem(s, it.clone());
        }
        return snap;
    }

    /**
     * Restore contents into a block's storage, keeping the shared cache AND the PDC consistent so ticks,
     * GUI viewers, and the break-drop path all agree. Used by the mechanism-disassembly place-back path.
     */
    public void restoreStorageSnapshot(Block block, org.bukkit.inventory.Inventory contents) {
        org.bukkit.inventory.Inventory inv = getOrCreateStorage(block);   // cache-consistent (create/reuse)
        if (inv == null) return;                                          // not a storage block
        inv.setContents(contents.getContents());
        saveInventoryToPDC(block.getLocation(), inv);                     // persist to PDC now
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

    /** Convert Bukkit Transformation (TRS components) to a JOML Matrix4f. */
    private static Matrix4f transformToMatrix(org.bukkit.util.Transformation t) {
        return new Matrix4f()
                .translate(t.getTranslation())
                .rotate(t.getLeftRotation())
                .scale(t.getScale())
                .rotate(t.getRightRotation());
    }

    record LocationKey(UUID worldId, int x, int y, int z) {
        static LocationKey of(Block block) {
            return new LocationKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
        static LocationKey of(org.bukkit.Location loc) {
            return new LocationKey(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
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
        boolean failed; // set true after a spawn error to stop per-tick log spam
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

    private static final class AnimationTracked {
        final Display display;
        final DisplayAnimation animation;
        final long startTick;
        final Matrix4f baseTransform;
        boolean reversed;
        AnimationTracked(Display display, DisplayAnimation animation, long startTick, Matrix4f baseTransform) {
            this.display = display;
            this.animation = animation;
            this.startTick = startTick;
            this.baseTransform = baseTransform;
        }
    }
}
