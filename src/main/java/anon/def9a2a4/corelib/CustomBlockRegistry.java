package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
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
import java.util.function.Consumer;
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

    // ── Bare shafts ────────────────────────────────────────────────────────
    // A "bare" shaft is a real vanilla CHAIN block acting as mech:shaft. It has no PDC, so its
    // identity is recovered from its persistent, tagged rod ItemDisplay (see restoreChainShaftsInChunk).
    // This set is the runtime index; getTypeFromBlock resolves CHAIN + membership → the shaft type.
    private final Set<LocationKey> chainShaftLocations = new HashSet<>();
    // Last idle/spinning state rendered per bare shaft — churn guard so the spin drive only re-applies
    // (respawns the rod) on an actual idle↔spinning transition, not on every unrelated recalc.
    private final Map<LocationKey, String> chainShaftRenderedState = new HashMap<>();
    // The mech:shaft type + a revert-to-encased-head handler, registered by RotationBlocks.
    private @Nullable CustomHeadBlock chainShaftType;
    private @Nullable Consumer<Block> chainShaftRevertHandler;

    // Pluggable per-chunk restorers for custom blocks whose identity lives on an attached display
    // entity rather than a skull PDC (e.g. bare chain shafts). Run for hinted chunks in onChunkLoad;
    // each restores its own blocks and reports whether it kept ≥1 so the hint isn't wiped.
    private final List<ChunkRestorer> chunkRestorers = new ArrayList<>();

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
                    if (!(tile instanceof TileState ts) || !isCustomBlockMaterial(ts.getType())) continue;
                    String id = ts.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
                    if (!type.fullId().equals(id)) continue;

                    Block block = ts.getBlock();
                    String state = ts.getPersistentDataContainer().get(STATE_KEY, PersistentDataType.STRING);
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

    /** Materials that can physically back a custom block: player heads and barrel-backed blocks
     *  (e.g. the redstone dynamo). Both carry identity in their tile-entity PDC. */
    static boolean isCustomBlockMaterial(Material m) {
        return m == Material.PLAYER_HEAD || m == Material.PLAYER_WALL_HEAD || m == Material.BARREL;
    }

    public @Nullable CustomHeadBlock getTypeFromBlock(Block block) {
        Material m = block.getType();
        // Heads (the common case) and barrel-backed blocks (e.g. the redstone dynamo) both store their
        // identity in the block's tile-entity PDC. A Skull is a TileState, so heads are unaffected; a
        // vanilla barrel without our PDC key simply resolves to null.
        if (m == Material.PLAYER_HEAD || m == Material.PLAYER_WALL_HEAD || m == Material.BARREL) {
            if (!(block.getState() instanceof TileState tile)) return null;
            String typeId = tile.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (typeId == null) return null;
            return types.get(typeId);
        }
        // Bare shaft: a CHAIN block in the runtime index (identity from its rod display).
        if (m == Material.CHAIN && chainShaftType != null && !chainShaftLocations.isEmpty()
                && chainShaftLocations.contains(LocationKey.of(block))) {
            return chainShaftType;
        }
        return null;
    }

    public Collection<CustomHeadBlock> allTypes() {
        return Collections.unmodifiableCollection(types.values());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Orphaned display-entity cleanup (admin command: /defcorelib cleanorphans)
    // ──────────────────────────────────────────────────────────────────────

    /** Outcome of an orphan scan. {@code samples} holds up to ~10 human-readable lines. */
    record OrphanScanResult(int orphans, int live, int skippedUnloaded, List<String> samples) {}

    /** Outcome of a display refresh: how many loaded custom blocks were re-applied and how many
     *  orphan displays were found (removed when applied). */
    record RefreshResult(int refreshed, int orphansRemoved) {}

    /** Owning custom block reference encoded in a block-attached display entity's tag. */
    private record DisplayOwnerTag(String fullId, int x, int y, int z) {}

    /**
     * Scan every loaded world for block-attached display entities whose parent custom block is
     * gone or has been replaced by a different block, optionally despawning them.
     *
     * <p>Ownership is decided purely by the owner block: a matching {@link #BLOCK_TYPE_KEY} PDC skull
     * (or a bare {@link #isChainShaft} CHAIN) is live, anything else is an orphan — so a display whose
     * type was renamed or removed (its cell no longer holds a matching head) is correctly cleaned. The
     * registry is not consulted. Banners and pulley-owned chain strands are skipped; mechanism-entity
     * tags are skipped by shape. A display whose owner sits in an unloaded chunk is counted, not removed.
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

    /**
     * Refresh every loaded custom block's displays and (optionally) clear orphans.
     *
     * <p>Timing-safe to invoke at runtime (e.g. from {@code /defcorelib refreshdisplays}): it only
     * touches already-loaded blocks and attached entities, so unlike a startup sweep it never races
     * Paper's async entity load. For each loaded custom block it re-runs {@link #applyConfig}, which
     * removes that cell's existing displays and respawns the correct current ones — fixing a stale,
     * duplicate, or plain wrong display sitting on a block that still holds the right custom block
     * (which the orphan scanner cannot detect, since it classifies any display on a live block as
     * "live"). Then it runs {@link #scanOrphanedDisplays} to despawn genuine orphans (owner block
     * gone or replaced, including renamed/removed types); live blocks, bare shafts and chain strands
     * are preserved by that scan.
     *
     * @param apply if true, actually re-apply configs and remove orphans; if false, only count
     */
    RefreshResult refreshLoadedDisplays(boolean apply) {
        int refreshed = 0;
        // Snapshot: applyConfig mutates the tracking maps this iterates alongside.
        for (LocationKey key : new ArrayList<>(customBlockLocations)) {
            World world = Bukkit.getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) continue;
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            CustomHeadBlock type = getTypeFromBlock(block);
            if (type == null) continue;
            refreshed++;
            if (apply) {
                String state = getState(block);
                int power = type.sensitivity() != CustomHeadBlock.Sensitivity.NONE ? readPower(block, type) : 0;
                applyConfig(block, type, state, power);
            }
        }
        // Re-apply above fixed duplicate/wrong displays on live blocks; now sweep true orphans
        // (owner block gone or replaced, including renamed/removed types) with the shared scanner.
        OrphanScanResult scan = scanOrphanedDisplays(apply);
        return new RefreshResult(refreshed, scan.orphans());
    }

    /** Extract the owning custom block reference from a block-attached display tag, or null if the
     *  entity carries no such tag. Format: {@code corelib:{ns}:{type}:{x}_{y}_{z}[:{suffix}]}. */
    private @Nullable DisplayOwnerTag parseBlockDisplayTag(Display display) {
        for (String tag : display.getScoreboardTags()) {
            if (!tag.startsWith("corelib:")) continue;
            if (tag.startsWith("corelib:banner:")) continue;             // banners: host-block ownership, not skull PDC
            if (tag.startsWith("corelib:mech:chain_strand:")) continue;  // pulley-owned strand, not a block display
            String[] parts = tag.split(":");
            if (parts.length < 4) continue;
            String fullId = parts[1] + ":" + parts[2];
            // Ownership is decided purely by the owner block's PDC (see isOwnerPresent), NOT by registry
            // membership — so a display whose type was renamed/removed (e.g. mech:generator) is correctly
            // an orphan. Mechanism-entity tags (corelib:mech:{uuid}:…) are excluded below because their
            // parts[3] is not an x_y_z triple.
            String[] coords = parts[3].split("_");
            if (coords.length != 3) continue;
            try {
                int x = Integer.parseInt(coords[0]);
                int y = Integer.parseInt(coords[1]);
                int z = Integer.parseInt(coords[2]);
                return new DisplayOwnerTag(fullId, x, y, z);
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
        // A bare chain shaft is a CHAIN block (no skull PDC) whose sole identity is its rod display —
        // a live one must never be treated as an orphan (which would delete its only identity carrier).
        if (isChainShaft(block)) return true;
        if (!isCustomBlockMaterial(block.getType())) {
            return false;
        }
        if (!(block.getState() instanceof TileState tile)) return false;
        String fullId = tile.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
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
        // TileState covers both skulls and barrel-backed blocks (Skull extends TileState).
        if (!(block.getState() instanceof TileState tile)) return;
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        pdc.set(BLOCK_TYPE_KEY, PersistentDataType.STRING, type.fullId());
        String effectiveState = initialState != null ? initialState : type.defaultState();
        if (effectiveState != null) {
            pdc.set(STATE_KEY, PersistentDataType.STRING, effectiveState);
        }
        tile.update();
        customBlockLocations.add(LocationKey.of(block));
        markChunkDirty(block);
        if (type.reactsToNeighbors()) {
            neighborReactiveBlocks.add(LocationKey.of(block));
        }
    }

    /** Read current state from a skull's PDC, or synthesize it for a bare (chain) shaft. */
    public @Nullable String getState(Block block) {
        if (block.getState() instanceof TileState tile) {
            return tile.getPersistentDataContainer().get(STATE_KEY, PersistentDataType.STRING);
        }
        if (isChainShaft(block)) {
            String rendered = chainShaftRenderedState.get(LocationKey.of(block));
            return rendered != null ? rendered : "idle_" + chainAxisSuffix(block);
        }
        return null;
    }

    /** Write a new state to a skull's PDC. No-op for a bare (chain) shaft (state is synthesized). */
    public void setState(Block block, String state) {
        if (!(block.getState() instanceof TileState tile)) return;
        tile.getPersistentDataContainer().set(STATE_KEY, PersistentDataType.STRING, state);
        tile.update();
    }

    // ── Bare-shaft support ──────────────────────────────────────────────────

    /** Register the mech:shaft type and the revert-to-head handler used on piston/mechanism moves. */
    void setChainShaftSupport(CustomHeadBlock shaftType, java.util.function.Consumer<Block> revertHandler) {
        this.chainShaftType = shaftType;
        this.chainShaftRevertHandler = revertHandler;
        registerChunkRestorer(this::restoreChainShaftsInChunk);
    }

    /** True if {@code block} is a CHAIN currently acting as a bare shaft. */
    boolean isChainShaft(Block block) {
        return chainShaftType != null && !chainShaftLocations.isEmpty()
                && block.getType() == Material.CHAIN
                && chainShaftLocations.contains(LocationKey.of(block));
    }

    void addChainShaft(Block block) {
        chainShaftLocations.add(LocationKey.of(block));
        markChunkDirty(block);   // hint the chunk so onChunkLoad's gated restore finds this bare shaft
    }

    void removeChainShaft(Block block) {
        LocationKey key = LocationKey.of(block);
        chainShaftLocations.remove(key);
        chainShaftRenderedState.remove(key);
    }

    /** Convert a bare chain shaft back to an encased head (piston/mechanism move); no-op otherwise. */
    void revertChainShaftToHead(Block block) {
        if (chainShaftRevertHandler != null && isChainShaft(block)) {
            chainShaftRevertHandler.accept(block);
        }
    }

    /** Axis suffix (x/y/z) of a chain's Orientable blockdata, defaulting to y. */
    private static String chainAxisSuffix(Block block) {
        if (block.getBlockData() instanceof org.bukkit.block.data.Orientable o) {
            return switch (o.getAxis()) {
                case X -> "x";
                case Z -> "z";
                default -> "y";
            };
        }
        return "y";
    }

    /**
     * Drive a bare chain shaft's rod spin from network power. Returns true iff {@code block} is a
     * chain shaft (i.e. the caller should stop). Only re-applies the display when idle↔spinning
     * actually changed, so unrelated recalcs don't respawn (flicker) the rod.
     */
    boolean driveChainShaftSpinIfChain(Block block, boolean powered) {
        if (!isChainShaft(block)) return false;
        if (chainShaftType == null) return true;
        LocationKey key = LocationKey.of(block);
        String target = (powered ? "spinning_" : "idle_") + chainAxisSuffix(block);
        if (target.equals(chainShaftRenderedState.get(key))) return true;
        chainShaftRenderedState.put(key, target);
        applyConfig(block, chainShaftType, target, 0);
        return true;
    }

    /**
     * Re-register bare chain shafts in a just-loaded chunk from their persistent rod displays.
     * Registered as a {@link ChunkRestorer} in {@link #setChainShaftSupport}, so it runs only for
     * hinted chunks (the hint is set on creation via {@link #addChainShaft}).
     * @return true if the chunk holds at least one live bare shaft, so its hint is kept alive.
     */
    boolean restoreChainShaftsInChunk(Chunk chunk) {
        if (chainShaftType == null) return false;
        World world = chunk.getWorld();
        if (!isNamespaceEnabledInWorld(chainShaftType.namespace(), world.getName())) return false;
        boolean found = false;
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay display)) continue;
            int[] xyz = parseShaftRodTag(display);
            if (xyz == null) continue;
            Block block = world.getBlockAt(xyz[0], xyz[1], xyz[2]);
            // A rod at a CHAIN is a bare shaft; a rod at a PLAYER_HEAD is handled by the skull scan;
            // anything else is an orphan (left for the orphan cleaner).
            if (block.getType() != Material.CHAIN) continue;
            LocationKey key = LocationKey.of(block);
            found = true;   // a live bare shaft here (already-known or newly restored) keeps the hint
            if (chainShaftLocations.contains(key)) continue;
            chainShaftLocations.add(key);
            restoreBlock(block, chainShaftType, getState(block));
        }
        return found;
    }

    /** Parse the x,y,z from a {@code corelib:mech:shaft:x_y_z:rod} display tag, or null. */
    private static @Nullable int[] parseShaftRodTag(Display display) {
        for (String tag : display.getScoreboardTags()) {
            if (!tag.startsWith("corelib:mech:shaft:") || !tag.endsWith(":rod")) continue;
            String mid = tag.substring("corelib:mech:shaft:".length(), tag.length() - ":rod".length());
            String[] parts = mid.split("_");
            if (parts.length != 3) continue;
            try {
                return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]) };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ── Entity-hosted chunk restorers ────────────────────────────────────────

    /**
     * Restores custom blocks in a just-loaded chunk whose identity lives on an attached display
     * entity rather than a skull PDC (e.g. bare chain shafts). Called from {@link #onChunkLoad} for
     * hinted chunks only. Register via {@link #registerChunkRestorer}.
     */
    @FunctionalInterface
    interface ChunkRestorer {
        /** @return true if the chunk holds ≥1 live block of this kind (keeps the chunk hint alive). */
        boolean restore(Chunk chunk);
    }

    void registerChunkRestorer(ChunkRestorer restorer) {
        chunkRestorers.add(restorer);
    }

    /**
     * True if the chunk holds any block-display entity carrying a corelib block tag
     * ({@code corelib:{ns}:{type}:x_y_z[:suffix]}), whether or not that type is registered. Load-order
     * safety for entity-hosted blocks — the analogue of the skull {@code foundAnyPdc} guard, so a hint
     * isn't wiped for a type whose plugin hasn't registered its restorer yet.
     */
    private static boolean chunkHasCorelibBlockDisplay(Chunk chunk) {
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Display display)) continue;
            for (String tag : display.getScoreboardTags()) {
                if (isCorelibBlockTag(tag)) return true;
            }
        }
        return false;
    }

    /** Matches the {@code corelib:{ns}:{type}:{x_y_z}[:suffix]} block-display tag shape (3 int coords). */
    private static boolean isCorelibBlockTag(String tag) {
        if (!tag.startsWith("corelib:")) return false;
        String[] parts = tag.split(":");
        if (parts.length < 4) return false;
        String[] coords = parts[3].split("_");
        if (coords.length != 3) return false;
        try {
            Integer.parseInt(coords[0]);
            Integer.parseInt(coords[1]);
            Integer.parseInt(coords[2]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
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

        boolean foundAny = false; // any corelib custom block (head/barrel or entity-hosted) that keeps the hint
        for (BlockState tile : chunk.getTileEntities()) {
            if (!(tile instanceof TileState ts) || !isCustomBlockMaterial(ts.getType())) continue;
            String typeId = ts.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (typeId == null) continue;

            foundAny = true; // Don't remove hint — a plugin for this type may load later

            CustomHeadBlock type = types.get(typeId);
            if (type == null) continue;
            if (!isNamespaceEnabledInWorld(type.namespace(), chunk.getWorld().getName())) continue;

            Block block = ts.getBlock();
            String state = ts.getPersistentDataContainer().get(STATE_KEY, PersistentDataType.STRING);
            restoreBlock(block, type, state);
        }

        // Entity-hosted custom blocks (identity on an attached display, not a skull) — e.g. bare chain
        // shafts. Each restorer restores its own and reports whether the chunk still holds any, so the
        // hint survives. Isolated: a throwing restorer must never wipe a hint (stranding real blocks)
        // or abort the others.
        for (ChunkRestorer restorer : chunkRestorers) {
            try {
                foundAny |= restorer.restore(chunk);
            } catch (Throwable t) {
                foundAny = true;
                plugin.getLogger().log(Level.WARNING,
                    "Chunk restorer threw for chunk " + chunkKey(chunk) + "; keeping hint", t);
            }
        }

        // About to wipe: keep the hint if any entity-hosted block display is present whose type isn't
        // registered yet (plugin load order / reload) — the entity-hosted analogue of foundAnyPdc.
        if (!foundAny && chunkHasCorelibBlockDisplay(chunk)) foundAny = true;

        // Only remove the hint if nothing — skull, restorer, or entity-hosted display — was found.
        if (!foundAny) {
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
            // Reconcile the persisted skull texture to the actual power at load time. The poll only
            // re-textures on a CHANGE (lastPower != newPower), and trackRedstone just seeded
            // lastPower = power, so a head that lost power while its chunk was unloaded would
            // otherwise stay stuck on its last-lit texture.
            HeadUtil.applyTexture(block, type.resolveTexture(state, power, getSkullFacing(block)));
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
            if (!(tile instanceof TileState ts) || !isCustomBlockMaterial(ts.getType())) continue;
            String typeId = ts.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (typeId == null) continue;
            CustomHeadBlock type = types.get(typeId);
            if (type != null && type.onChunkUnloadCallback() != null) {
                type.onChunkUnloadCallback().accept(ts.getBlock());
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

        // Bare chain shafts aren't tile entities, so the tile-entity unload dispatch above never fires
        // their removeNode callback — do it explicitly, then drop them from the runtime indexes.
        java.util.function.Predicate<LocationKey> keyInChunk = loc ->
                loc.worldId().equals(world.getUID())
                        && (loc.x() >> 4) == chunkX && (loc.z() >> 4) == chunkZ;
        if (chainShaftType != null && chainShaftType.onChunkUnloadCallback() != null) {
            for (LocationKey loc : chainShaftLocations) {
                if (keyInChunk.test(loc)) {
                    chainShaftType.onChunkUnloadCallback().accept(world.getBlockAt(loc.x(), loc.y(), loc.z()));
                }
            }
        }
        chainShaftLocations.removeIf(keyInChunk);
        chainShaftRenderedState.entrySet().removeIf(inChunk);

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
                // A head disguising an opaque physical block (e.g. the dynamo's barrel) samples light at
                // the block centre and would render dark — force it fully bright so the disguise reads solid.
                if (type.physicalMaterial() != null) {
                    display.setBrightness(new Display.Brightness(15, 15));
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

        // Barrel-backed blocks (e.g. the dynamo) use a real container to drive a comparator; wipe its
        // contents on any removal path (break / explosion / piston / burn all funnel through here) so the
        // internal filler is never dropped or spilled — only the block's own item drops.
        if (type.physicalMaterial() == Material.BARREL && block.getState() instanceof Container container) {
            container.getInventory().clear();
        }

        LocationKey key = LocationKey.of(block);
        redstoneTracked.remove(key);
        particleTracked.remove(key);
        neighborReactiveBlocks.remove(key);
        tickTracked.remove(key);
        customBlockLocations.remove(key);
        animationTracked.remove(key);
        animationDirection.remove(key);
        chainShaftLocations.remove(key);
        chainShaftRenderedState.remove(key);

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
            case EXTENDED -> readExtendedPower(block, type.sensitivity());
        };
    }

    /** EXTENDED reader: senses redstone at the head itself, at its mount cell (1 away — the wall
     *  behind a wall head, or the block below a floor head, that a dust line normally powers), and 2
     *  away (through a 1-block wall, or 2 below a floor head).
     *
     *  <p>For {@code LEVEL} (analog indicators) it reads ONLY redstone dust, via {@link #dustPower},
     *  so the three intended wirings all work: dust pointing directly into the head, dust powering the
     *  mount cell, and dust 2-back / 2-below. {@code getBlockPower()} collapses to a flat 15 for any
     *  non-dust source (comparator, repeater, lever, powered block), which would make an indicator
     *  falsely display 15 — so non-dust sources read 0 for LEVEL blocks.
     *
     *  <p>For {@code BINARY} (on/off) blocks the exact level is irrelevant, so the 15/0
     *  {@code getBlockPower()} behaviour is fine and is kept — e.g. a door still opens from a
     *  lever/button/redstone block, not just dust. */
    private int readExtendedPower(Block block, CustomHeadBlock.Sensitivity sensitivity) {
        Block oneAway = null, twoAway = null;
        if (block.getType() == Material.PLAYER_WALL_HEAD
                && block.getBlockData() instanceof Directional directional) {
            BlockFace behind = directional.getFacing().getOppositeFace();
            oneAway = block.getRelative(behind);            // the wall/support block a dust line powers
            twoAway = oneAway.getRelative(behind);          // 2 behind, through the wall
        } else if (block.getType() == Material.PLAYER_HEAD) {
            oneAway = block.getRelative(0, -1, 0);          // the block directly below
            twoAway = block.getRelative(0, -2, 0);          // 2 below
        }

        if (sensitivity == CustomHeadBlock.Sensitivity.LEVEL) {
            // Analog, dust only (no false 15): dust into the head itself, the mount cell, or 2 away.
            return Math.max(dustPower(block), Math.max(dustPower(oneAway), dustPower(twoAway)));
        }
        // BINARY / on-off: any redstone source counts, exact level irrelevant. The head's own power is
        // covered by block.getBlockPower(); cells are read wire-aware so dust sitting exactly at a cell
        // reports its own carried level (getBlockPower returns 0 for a wire's own level).
        return Math.max(block.getBlockPower(), Math.max(cellPower(oneAway), cellPower(twoAway)));
    }

    private static final BlockFace[] SIX_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /** Dust-derived analog redstone level at a cell (0–15), suppressing non-dust sources.
     *
     *  <p>{@code getBlockPower()} accumulates its analog value from adjacent redstone DUST only (weak,
     *  directional), and otherwise falls back to a flat 15 for any non-dust source. So a result of
     *  1–14 is always genuine dust; only 15 is ambiguous and needs the neighbour check. A wire sitting
     *  at the cell reports its own carried level (getBlockPower would give the power delivered to it,
     *  not its own). Non-dust sources (lever/comparator/repeater/redstone block) contribute 0. */
    private static int dustPower(@Nullable Block cell) {
        if (cell == null) return 0;
        if (cell.getBlockData() instanceof org.bukkit.block.data.type.RedstoneWire wire) {
            return wire.getPower();                     // dust sitting at the cell → its own level
        }
        int bp = cell.getBlockPower();
        if (bp < 15) return bp;                         // 0 or 1..14 → always genuine directional dust
        for (BlockFace face : SIX_FACES) {              // bp == 15: real only if max-level dust is adjacent
            if (cell.getRelative(face).getBlockData()
                    instanceof org.bukkit.block.data.type.RedstoneWire w && w.getPower() == 15) {
                return 15;
            }
        }
        return 0;                                       // flat-15 fallback from a non-dust source
    }

    /** Redstone power at a cell for on/off (BINARY) sensing: a wire reports its own carried level;
     *  anything else reports {@code getBlockPower()} (15/0 for non-dust sources). */
    private static int cellPower(@Nullable Block cell) {
        if (cell == null) return 0;
        return cell.getBlockData() instanceof org.bukkit.block.data.type.RedstoneWire wire
                ? wire.getPower() : cell.getBlockPower();
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

    // ── Recipe gating ──────────────────────────────────────────────────────
    // Recipes for these namespaces are withheld by default and only registered once a companion
    // plugin (vslab / mech / rsd) calls enableRecipes(...). Every OTHER namespace (corelib, pipes, …)
    // registers exactly as before (default-allow), so third-party runtime registrars are unaffected.
    private static final Set<String> GATED_NAMESPACES = Set.of("demo", "verticalslabs", "mech", "redstonedisplays");
    private final Set<String> enabledRecipeNamespaces = new HashSet<>();
    // Per-type guard so an enable can't double-register a type's recipes (idempotency).
    private final Set<String> recipeRegisteredTypes = new HashSet<>();

    // Windmill large/huge tier swap — only active when the bbanners plugin is present (set by mech).
    // Without it, a large/huge banner (from /give/creative) must NOT produce a tier windmill.
    private volatile boolean windmillTierEnabled = false;
    public void setWindmillTierEnabled(boolean enabled) { this.windmillTierEnabled = enabled; }
    public boolean isWindmillTierEnabled() { return windmillTierEnabled; }

    private boolean recipesGatedOff(String namespace) {
        return GATED_NAMESPACES.contains(namespace) && !enabledRecipeNamespaces.contains(namespace);
    }

    /**
     * Enable crafting recipes for a gated namespace (called from a companion plugin's onEnable).
     * Idempotent; registers the namespace's recipes and syncs recipe-book discovery for online
     * players so they appear immediately.
     */
    public void enableRecipes(String namespace) {
        if (!enabledRecipeNamespaces.add(namespace)) return; // already enabled
        if (!finalized) return; // finalizeLoading() will register once core finishes loading
        for (CustomHeadBlock type : types.values()) {
            if (type.namespace().equals(namespace)) registerRecipesForType(type);
        }
        // Resend the recipe list to already-connected players (a /reload or a runtime enable);
        // without this the client never learns the just-added recipes and they stay absent from the
        // recipe book (hand-crafting still works). No-op at startup — no players are online yet.
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            Bukkit.updateRecipes();
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) syncRecipeDiscovery(p);
        }
    }

    /**
     * Disable crafting recipes for a gated namespace (companion onDisable). Best-effort: removes the
     * Bukkit recipes, undiscovers them for online players, and prunes the side-tables. (Runtime
     * single-plugin disable is rare on Paper — real disable is a server stop — so this is scoped to
     * "no crash / no orphaned recipe-book entries", not a full teardown.)
     */
    public void disableRecipes(String namespace) {
        if (!enabledRecipeNamespaces.remove(namespace)) return; // wasn't enabled
        String prefix = namespace + "_";
        List<org.bukkit.NamespacedKey> toRemove = new ArrayList<>();
        for (org.bukkit.NamespacedKey key : registeredRecipeKeys) {
            if (key.getKey().startsWith(prefix)) toRemove.add(key);
        }
        for (org.bukkit.NamespacedKey key : toRemove) {
            Bukkit.removeRecipe(key);
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.undiscoverRecipe(key);
        }
        registeredRecipeKeys.removeAll(toRemove);
        gatedRecipeKeys.removeAll(toRemove);
        recipeRegisteredTypes.removeIf(id -> id.startsWith(namespace + ":"));
        headStonecutterRecipes.removeIf(r -> r.inputBlockId().startsWith(namespace + ":")
                || r.outputBlockId().startsWith(namespace + ":"));
        toggleRecipes.keySet().removeIf(k -> k.getKey().startsWith(prefix));
        advancementRecipes.values().forEach(list -> list.removeAll(toRemove));
        if (!Bukkit.getOnlinePlayers().isEmpty()) Bukkit.updateRecipes(); // resend so they leave the book
    }

    /** Register all recipes for all registered block types. Call after all blocks are loaded. */
    void registerRecipes() {
        for (CustomHeadBlock type : types.values()) {
            registerRecipesForType(type);
        }
    }

    private void registerRecipesForType(CustomHeadBlock type) {
        if (!type.hasRecipes()) return;
        if (recipesGatedOff(type.namespace())) return; // withheld until a companion enables it
        if (!recipeRegisteredTypes.add(type.fullId())) return; // already registered — idempotent
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
            case "mech" -> org.bukkit.inventory.recipe.CraftingBookCategory.REDSTONE;
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
        recipeRegisteredTypes.clear();
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
        org.bukkit.inventory.Inventory snap = createStorageInventory(type.storage(), type, null);
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

    // ──────────────────────────────────────────────────────────────────────
    // World filter (per-namespace world enable/disable)
    // ──────────────────────────────────────────────────────────────────────

    private final Map<String, WorldFilter> worldFilters = new HashMap<>();

    public void setWorldFilter(String namespace, WorldFilter filter) {
        worldFilters.put(namespace, filter);
    }

    public void clearWorldFilter(String namespace) {
        worldFilters.remove(namespace);
    }

    public boolean isNamespaceEnabledInWorld(String namespace, String worldName) {
        WorldFilter filter = worldFilters.get(namespace);
        return filter == null || filter.isEnabled(worldName);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cauldron conversions (item + water cauldron → different item)
    // ──────────────────────────────────────────────────────────────────────

    private final Map<String, String> cauldronConversions = new HashMap<>();

    public void registerCauldronConversion(String fromId, String toId) {
        cauldronConversions.put(fromId, toId);
    }

    public void clearCauldronConversions(String namespace) {
        cauldronConversions.entrySet().removeIf(e -> e.getKey().startsWith(namespace + ":"));
    }

    @Nullable String getCauldronConversionTarget(String fromId) {
        return cauldronConversions.get(fromId);
    }

    boolean hasCauldronConversions() {
        return !cauldronConversions.isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────

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
