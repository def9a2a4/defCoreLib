package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One-time compat shim: adopts pipes placed by the standalone Pipes plugin (&le; v0.2.0) into the
 * CoreLib block system, and removes their orphaned display entities.
 *
 * <p>Legacy pipes kept ALL state on persistent {@link ItemDisplay}s at the block center; the head
 * block itself carries no PDC. Identity is the entity-PDC value {@code pipe:tag} (or, older still,
 * a scoreboard tag with the same value prefixed {@code pipe:}) in the format
 * {@code {variant}:{x}_{y}_{z}_{FACING}[_dir|_head]} — mirrored from the legacy repo's PipeTags.
 * Facings are single-token cardinal names, so after suffix-stripping the data always splits on
 * {@code _} into exactly 4 parts (same guard as legacy parseLocation).
 *
 * <p>Runs per chunk on {@link EntitiesLoadEvent} (identity lives on entities — ChunkLoadEvent fires
 * before they exist on Paper), plus a catch-up sweep per world after init and via /pipes migrate.
 * Every path is idempotent and stateless per chunk, so re-entry and repeat visits are safe.
 *
 * <p>Once a legacy display group has been judged, its displays are always removed — adopted,
 * stale, or conflicting. The only paths that leave legacy displays in place are the deliberately
 * conservative ones: unknown/unconfigured variants (retried after a config fix) and out-of-chunk
 * tags whose target chunk is unloaded (never force a chunk load; retried on every visit).
 *
 * <p>Sunset: delete this class (plus its wiring in PipesPlugin/WorldManager/plugin.yml and the
 * foreign-orphan detector registration) once servers report zero migrations across a full cycle.
 */
public final class LegacyPipeMigrator implements Listener {

    private static final NamespacedKey LEGACY_PIPE_TAG = new NamespacedKey("pipe", "tag");
    private static final String LEGACY_SCOREBOARD_PREFIX = "pipe:";
    private static final String[] DISPLAY_SUFFIXES = {"_dir", "_head"};

    /** Skull profile UUID every legacy pipe head carries — constant since the legacy plugin's first
     *  release, and overwritten by applyConfig on adoption, so it survives only on unmigrated heads.
     *  Gates adoption so a player-placed decorative head can never be converted into a pipe. */
    private static final UUID LEGACY_PROFILE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Raw CoreLib identity key. The migrator reads it directly (not via getTypeFromBlock) because
     *  "already corelib-marked" must hold for unregistered types too. */
    private static final NamespacedKey CORELIB_BLOCK_TYPE = new NamespacedKey("corelib", "block_type");

    private final PipesPlugin plugin;
    private final CustomBlockRegistry registry;
    private final Set<String> warnedVariants = new HashSet<>();

    public LegacyPipeMigrator(PipesPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** Aggregate counters for logs and /pipes migrate feedback. */
    public record Result(int migrated, int repaired, int orphanDisplaysRemoved,
                         int unknownVariants, int residueHeads) {
        public static final Result ZERO = new Result(0, 0, 0, 0, 0);

        public Result plus(Result o) {
            return new Result(migrated + o.migrated, repaired + o.repaired,
                    orphanDisplaysRemoved + o.orphanDisplaysRemoved,
                    unknownVariants + o.unknownVariants, residueHeads + o.residueHeads);
        }

        public boolean isEmpty() {
            return migrated == 0 && repaired == 0 && orphanDisplaysRemoved == 0
                    && unknownVariants == 0 && residueHeads == 0;
        }

        public String describe() {
            return migrated + " pipe(s) migrated, " + repaired + " repaired, "
                    + orphanDisplaysRemoved + " stray display(s) removed, "
                    + unknownVariants + " unknown variant(s), "
                    + residueHeads + " unrecoverable legacy head(s)";
        }
    }

    private record LegacyTag(String variantId, int x, int y, int z, BlockFace facing) {}

    /** Normalized tag value (no {@code pipe:} prefix) and whether it came from the unambiguous PDC key. */
    private record RawTag(String value, boolean fromPdc) {}

    private record Coord(int x, int y, int z) {}

    private record Tagged(ItemDisplay display, LegacyTag tag) {}

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Result r = migrateChunk(event.getChunk());
        if (r.migrated() > 0 || r.repaired() > 0 || r.orphanDisplaysRemoved() > 0) {
            plugin.getLogger().info("Legacy pipes in " + event.getWorld().getName()
                    + " chunk [" + event.getChunk().getX() + "," + event.getChunk().getZ() + "]: "
                    + r.describe());
        }
    }

    /** Catch-up over all enabled worlds' resident chunks (startup happens per world via sweepWorld;
     *  this is the /pipes migrate entry point). */
    public Result sweepLoadedChunks() {
        Result total = Result.ZERO;
        for (World world : Bukkit.getWorlds()) {
            total = total.plus(sweepWorld(world));
        }
        return total;
    }

    /** Sweep one world's resident chunks. Chunks whose entities are still loading are skipped —
     *  their EntitiesLoadEvent is still coming and will do the work (mirrors restoreLoadedChunks). */
    public Result sweepWorld(World world) {
        if (plugin.getPipeManager(world) == null) return Result.ZERO;
        Result total = Result.ZERO;
        for (Chunk chunk : world.getLoadedChunks()) {
            if (!chunk.isEntitiesLoaded()) continue;
            total = total.plus(migrateChunk(chunk));
        }
        return total;
    }

    /**
     * Migrate one chunk: adopt legacy pipes whose displays live here, remove stray legacy displays,
     * self-heal corelib pipe blocks that lost their runtime/hint state, and count residue heads.
     * Cheap no-op for chunks without legacy displays or pipe skulls.
     */
    public Result migrateChunk(Chunk chunk) {
        World world = chunk.getWorld();
        PipeManager manager = plugin.getPipeManager(world);
        if (manager == null) return Result.ZERO;

        int migrated = 0, repaired = 0, orphans = 0, unknown = 0, residue = 0;

        // Pass 1: collect legacy-tagged ItemDisplays, grouped by the block coordinate their tag encodes.
        Map<Coord, List<Tagged>> groups = new HashMap<>();
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay display)) continue;
            RawTag raw = rawLegacyTag(display);
            if (raw == null) continue;
            LegacyTag tag = parse(raw.value());
            if (tag == null) {
                // Corrupt value under OUR pdc key is provably ours — junk it. A bare "pipe:…"
                // scoreboard tag that doesn't parse could belong to another plugin — leave it.
                if (raw.fromPdc()) {
                    display.remove();
                    orphans++;
                }
                continue;
            }
            groups.computeIfAbsent(new Coord(tag.x(), tag.y(), tag.z()), c -> new ArrayList<>())
                    .add(new Tagged(display, tag));
        }

        // Pass 2: judge each coordinate group.
        for (Map.Entry<Coord, List<Tagged>> entry : groups.entrySet()) {
            Coord c = entry.getKey();
            List<Tagged> members = entry.getValue();

            boolean inChunk = (c.x() >> 4) == chunk.getX() && (c.z() >> 4) == chunk.getZ();
            if (!inChunk) {
                // WorldEdit-copied or teleported display: its tag points at another chunk. Judge only
                // against an observably loaded target — never force a sync chunk load from inside
                // EntitiesLoadEvent. Undecidable → leave; retried on every load of this chunk. A live
                // legacy head elsewhere is left for its own chunk's pass; once adopted there, its cell
                // is corelib-marked and this copy is swept on the next visit.
                if (world.isChunkLoaded(c.x() >> 4, c.z() >> 4)
                        && isStrayTarget(world.getBlockAt(c.x(), c.y(), c.z()))) {
                    orphans += removeAll(members);
                }
                continue;
            }

            Block block = world.getBlockAt(c.x(), c.y(), c.z());
            if (!isHead(block)) {
                // The pipe block is gone (broken under the new plugin, exploded, …) — pure strays.
                orphans += removeAll(members);
                continue;
            }

            String existingType = corelibTypeId(block);
            if (existingType != null) {
                // Already migrated, or the cell was reused by another custom block: displays are stale.
                orphans += removeAll(members);
                if (existingType.startsWith("pipes:") && selfHeal(block, existingType, manager)) {
                    repaired++;
                }
                continue;
            }

            // Identity by consensus across ALL surviving tags: a _dir/_head tag names the same
            // variant+facing as the main tag, so a pipe whose main display was killed is still
            // adoptable. Dev-era _dir facings can disagree — on conflict, identity is untrustworthy:
            // remove the displays (the priority is clearing old entities) and leave the head.
            Set<String> identities = new HashSet<>();
            for (Tagged t : members) {
                identities.add(t.tag().variantId() + ":" + t.tag().facing().name());
            }
            if (identities.size() != 1) {
                orphans += removeAll(members);
                residue++;
                plugin.getLogger().warning("Legacy pipe at " + world.getName() + " "
                        + c.x() + "," + c.y() + "," + c.z()
                        + " had conflicting display tags — removed the displays; re-place the pipe by hand.");
                continue;
            }
            LegacyTag identity = members.get(0).tag();

            String variantId = identity.variantId();
            CustomHeadBlock type = registry.getType("pipes:" + variantId);
            PipeVariant variant = plugin.getVariantRegistry().getVariant(variantId);
            if (type == null || variant == null) {
                // Un-overlaid YAML types have no callbacks/transform resolver and would produce a
                // broken pipe — treat "registered but not configured" the same as unknown. The ONE
                // case that deliberately leaves displays behind: destroying them would erase the only
                // identity, and a config fix makes the next load adopt cleanly.
                unknown++;
                if (warnedVariants.add(variantId)) {
                    plugin.getLogger().warning("Legacy pipe variant '" + variantId
                            + "' is not configured (first seen at " + world.getName() + " "
                            + c.x() + "," + c.y() + "," + c.z() + ") — leaving its pipes untouched.");
                }
                continue;
            }

            if (!hasLegacyProfile(block)) {
                // A head at these coords without the legacy pipe profile is a player's decorative
                // head sitting where a stray display points — clean the strays, never touch the head.
                orphans += removeAll(members);
                plugin.getLogger().warning("Removed stray legacy pipe display(s) at " + world.getName()
                        + " " + c.x() + "," + c.y() + "," + c.z()
                        + " (block is a non-pipe player head; left as-is).");
                continue;
            }

            // Adopt.
            String state = identity.facing().name().toLowerCase(Locale.ROOT);
            if (!type.states().containsKey(state)) {
                plugin.getLogger().warning("Legacy pipe at " + world.getName() + " "
                        + c.x() + "," + c.y() + "," + c.z() + " has invalid facing '" + state
                        + "' for " + variantId + " — using default state.");
                state = type.defaultState();
            }
            // markBlock FIRST, then display removal: a crash in between lands in the
            // "already corelib-marked" branch next load, which finishes the cleanup.
            registry.markBlock(block, type, state);
            removeAll(members);
            reanchor(block, type, state);
            migrated++;
        }

        // Pass 3: skull sweep (no-snapshot, head-filtered) — self-heal corelib pipe skulls that lost
        // runtime/hint state without any legacy displays surviving (crash between migration and the
        // periodic hint flush), and count legacy heads whose displays were purged (identity
        // unrecoverable — report only).
        for (BlockState tile : chunk.getTileEntities(
                b -> b.getType() == Material.PLAYER_HEAD || b.getType() == Material.PLAYER_WALL_HEAD, false)) {
            if (!(tile instanceof Skull skull)) continue;
            Block block = tile.getBlock();
            String typeId = skull.getPersistentDataContainer().get(CORELIB_BLOCK_TYPE, PersistentDataType.STRING);
            if (typeId != null) {
                if (typeId.startsWith("pipes:") && selfHeal(block, typeId, manager)) {
                    repaired++;
                }
            } else if (hasLegacyProfile(skull)
                    && !groups.containsKey(new Coord(block.getX(), block.getY(), block.getZ()))) {
                residue++;
            }
        }

        return new Result(migrated, repaired, orphans, unknown, residue);
    }

    /**
     * Foreign-orphan detector plugged into CoreLib's /defcorelib cleanorphans scan. ORPHAN only when
     * the display carries a legacy pipe identity AND its target block is observably not a legacy
     * pipe head; UNKNOWN when the target chunk is unloaded (never force a load — CoreLib reports it
     * as skipped); KEEP otherwise (including a live unmigrated pipe head — adoption is
     * migrateChunk's job, not the orphan scan's).
     */
    public CustomBlockRegistry.ForeignOrphanVerdict inspectLegacyDisplay(Display display) {
        if (!(display instanceof ItemDisplay)) return CustomBlockRegistry.ForeignOrphanVerdict.KEEP;
        RawTag raw = rawLegacyTag(display);
        if (raw == null) return CustomBlockRegistry.ForeignOrphanVerdict.KEEP;
        LegacyTag tag = parse(raw.value());
        if (tag == null) {
            // Corrupt under our PDC key → ours, junk; unparseable scoreboard tag → maybe foreign, keep.
            return raw.fromPdc() ? CustomBlockRegistry.ForeignOrphanVerdict.ORPHAN
                    : CustomBlockRegistry.ForeignOrphanVerdict.KEEP;
        }
        World world = display.getWorld();
        if (!world.isChunkLoaded(tag.x() >> 4, tag.z() >> 4)) {
            return CustomBlockRegistry.ForeignOrphanVerdict.UNKNOWN;
        }
        return isStrayTarget(world.getBlockAt(tag.x(), tag.y(), tag.z()))
                ? CustomBlockRegistry.ForeignOrphanVerdict.ORPHAN
                : CustomBlockRegistry.ForeignOrphanVerdict.KEEP;
    }

    /** The one shared judgment for a legacy display's target cell: stray unless the block is still
     *  an unmigrated legacy pipe head (head material + no corelib PDC + legacy skull profile).
     *  Single getState() snapshot covers all three checks. */
    private static boolean isStrayTarget(Block block) {
        if (!isHead(block)) return true;
        BlockState state = block.getState();
        if (state instanceof TileState tile && tile.getPersistentDataContainer()
                .has(CORELIB_BLOCK_TYPE, PersistentDataType.STRING)) {
            return true;
        }
        return !(state instanceof Skull skull) || !hasLegacyProfile(skull);
    }

    /** Re-anchor an already-corelib pipe block whose runtime tracking is missing — the crash-window
     *  case where the block PDC saved but the chunk hint didn't, so CoreLib's restore never saw it.
     *  markBlock re-registers the hint; reanchor rebuilds function and displays. */
    private boolean selfHeal(Block block, String typeId, PipeManager manager) {
        if (manager.isPipe(block.getLocation())) return false;
        CustomHeadBlock type = registry.getType(typeId);
        if (type == null) return false;
        String state = registry.getState(block);
        registry.markBlock(block, type, state);
        reanchor(block, type, state);
        return true;
    }

    /** Shared tail of adoption and self-heal. Order matters: restoreBlock registers the pipe
     *  (onChunkLoad callback) BEFORE applyConfig resolves display transforms, which prefer
     *  registered PipeData over the state fallback — don't "fix" this to match other call sites.
     *  power=0 is correct while no pipe type is redstone-sensitive; a sensitive type would need
     *  the real power here (readPower is CoreLib-internal). */
    private void reanchor(Block block, CustomHeadBlock type, @Nullable String state) {
        registry.restoreBlock(block, type, state);
        registry.applyConfig(block, type, state, 0);
        registry.refreshHeadViewers(block);
        // Same physics-suppression gap as placement: neighbors never hear about the change.
        registry.refreshReactiveNeighbors(block);
    }

    private static int removeAll(List<Tagged> members) {
        for (Tagged t : members) {
            t.display().remove();
        }
        return members.size();
    }

    private static boolean isHead(Block block) {
        Material m = block.getType();
        return m == Material.PLAYER_HEAD || m == Material.PLAYER_WALL_HEAD;
    }

    private static @Nullable String corelibTypeId(Block block) {
        if (!(block.getState() instanceof TileState tile)) return null;
        return tile.getPersistentDataContainer().get(CORELIB_BLOCK_TYPE, PersistentDataType.STRING);
    }

    private static boolean hasLegacyProfile(Block block) {
        return block.getState() instanceof Skull skull && hasLegacyProfile(skull);
    }

    private static boolean hasLegacyProfile(Skull skull) {
        var profile = skull.getPlayerProfile();
        return profile != null && LEGACY_PROFILE_UUID.equals(profile.getId());
    }

    private static @Nullable RawTag rawLegacyTag(Entity entity) {
        String value = entity.getPersistentDataContainer().get(LEGACY_PIPE_TAG, PersistentDataType.STRING);
        if (value != null) return new RawTag(value, true);
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(LEGACY_SCOREBOARD_PREFIX)) {
                return new RawTag(tag.substring(LEGACY_SCOREBOARD_PREFIX.length()), false);
            }
        }
        return null;
    }

    private static @Nullable LegacyTag parse(String raw) {
        String working = raw;
        for (String suffix : DISPLAY_SUFFIXES) {
            if (working.endsWith(suffix)) {
                working = working.substring(0, working.length() - suffix.length());
                break;
            }
        }
        int colon = working.indexOf(':');
        if (colon <= 0 || colon >= working.length() - 1) return null;
        String variantId = working.substring(0, colon);
        String[] parts = working.substring(colon + 1).split("_");
        if (parts.length != 4) return null;
        try {
            return new LegacyTag(variantId,
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    BlockFace.valueOf(parts[3]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
