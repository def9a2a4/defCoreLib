package anon.def9a2a4.headsmith;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import anon.def9a2a4.corelib.HeadUtil;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Adopts already-placed heads (and stored head items) from the standalone HeadSmith plugin into the
 * DefCoreLib registry. HeadSmith stored no block-identity PDC — a placed head is only recognizable by its
 * skull texture — so detection is texture-primary: match the skull's skin-URL hash against the runtime
 * {@code textureId → headsmith:<id>} table. The old plugin and this companion are both named "HeadSmith"
 * → namespace {@code headsmith}, so the legacy {@code headsmith:lit} (block) and {@code headsmith:head_id}
 * (item) PDC keys are read directly.
 *
 * <p>Preserve-blocks-and-items policy: placed heads are adopted (blocks), and legacy head items are lazily
 * re-stamped to the core PDC so they stay functional and stack with freshly-crafted heads. Idempotent
 * (skips blocks/items already core-typed), so it is safe to run repeatedly / across restarts.
 */
final class LegacyHeadMigrator implements Listener {

    private static final String SENTINEL = "_stonecutter_hint";
    private static final int CHUNKS_PER_TICK = 8;

    private final HeadSmithPlugin plugin;
    private final CustomBlockRegistry registry;
    private final NamespacedKey litKey;      // headsmith:lit    (legacy block state)
    private final NamespacedKey headIdKey;   // headsmith:head_id (legacy item identity)
    private final Map<String, CustomHeadBlock> byTextureId = new HashMap<>();

    // Residue / telemetry
    private int adoptedBlocks, uuidDiverged, restampedItems;

    LegacyHeadMigrator(HeadSmithPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.litKey = new NamespacedKey(plugin, "lit");
        this.headIdKey = new NamespacedKey(plugin, "head_id");
        buildTable();
    }

    /** Runtime textureId (skin-URL hash) → headsmith type. First registration wins (the B8 identical-texture
     *  pair resolves to whichever id loaded first — cosmetically identical). */
    private void buildTable() {
        for (CustomHeadBlock t : registry.allTypes()) {
            if (!t.namespace().equals(HeadSmithPlugin.NAMESPACE)) continue;
            HeadUtil.parseTexture(t.texture())
                    .ifPresent(info -> byTextureId.putIfAbsent(info.textureId(), t));
        }
        plugin.getLogger().info("Migrator ready: " + byTextureId.size() + " head textures indexed.");
    }

    // ── block adoption ──────────────────────────────────────────────────────────

    private static boolean isBehavioral(CustomHeadBlock t) {
        return t.light() != null || t.particles() != null || t.hasStates()
                || t.interactGUI() != null || t.storage() != null || !t.transitions().isEmpty();
    }

    /** Adopt one placed skull. Returns true if it was newly adopted. */
    boolean adopt(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return false;
        if (registry.getTypeFromBlock(block) != null) return false;              // already core-typed
        String base64 = HeadUtil.getBlockTexture(block);
        if (base64 == null) return false;
        String textureId = HeadUtil.parseTexture(base64).map(HeadUtil.TextureInfo::textureId).orElse(null);
        if (textureId == null) return false;
        CustomHeadBlock type = byTextureId.get(textureId);
        if (type == null) return false;                                          // foreign/plain skull — leave alone

        if (!(block.getState() instanceof Skull skull)) return false;

        // Confidence signal only (texture-primary; NOT a veto): HeadSmith's deterministic profile UUID.
        PlayerProfile profile = skull.getPlayerProfile();
        UUID expected = UUID.nameUUIDFromBytes(textureId.getBytes(StandardCharsets.UTF_8));
        if (profile == null || !expected.equals(profile.getId())) uuidDiverged++;

        // Lit-candle state carried over from the legacy PDC.
        Byte lit = skull.getPersistentDataContainer().get(litKey, PersistentDataType.BYTE);
        String state = (lit != null && lit == 1 && type.hasStates()) ? "lit" : type.defaultState();

        registry.markBlock(block, type, state);
        if (isBehavioral(type)) {
            registry.restoreBlock(block, type, state);
            registry.applyConfig(block, type, state, 0);
            registry.refreshHeadViewers(block);
        }
        adoptedBlocks++;
        return true;
    }

    private void adoptChunk(Chunk chunk) {
        for (BlockState bs : chunk.getTileEntities(false)) {
            if (bs instanceof Skull) adopt(bs.getBlock());
        }
    }

    // ── item re-stamping (preserve items) ────────────────────────────────────────

    /** If {@code item} is a legacy HeadSmith head (has headsmith:head_id, no core PDC), return a fresh
     *  core-stamped equivalent; else null. Skips the stonecutter-hint sentinel and unknown ids. */
    private ItemStack restamp(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return null;
        if (CustomBlockRegistry.getItemTypeId(item) != null) return null;        // already core
        String headId = item.getItemMeta().getPersistentDataContainer().get(headIdKey, PersistentDataType.STRING);
        if (headId == null || headId.equals(SENTINEL)) return null;
        CustomHeadBlock type = registry.getType(HeadSmithPlugin.NAMESPACE + ":" + headId);
        if (type == null) return null;                                           // unknown id — leave untouched
        restampedItems++;
        return type.createItem(item.getAmount());
    }

    private void restampInventory(Inventory inv) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack fresh = restamp(contents[i]);
            if (fresh != null) inv.setItem(i, fresh);
        }
    }

    // ── triggers ─────────────────────────────────────────────────────────────────

    /** Adopt as chunks load. LATE, so core's own onChunkLoad (which restores already-marked heads) has run;
     *  freshly-marked heads are handled here, and the getTypeFromBlock skip keeps it idempotent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        adoptChunk(event.getChunk());
    }

    /** Legacy head items placed AFTER migration (into already-loaded chunks that won't re-fire ChunkLoad). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        if (b.getType() != Material.PLAYER_HEAD && b.getType() != Material.PLAYER_WALL_HEAD) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> adopt(b)); // let the tile settle
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        restampInventory(event.getInventory());                 // container / station being opened
        restampInventory(event.getPlayer().getInventory());     // and the viewer's own inventory
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restampInventory(event.getPlayer().getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack fresh = restamp(event.getItem().getItemStack());
        if (fresh != null) event.getItem().setItemStack(fresh);
    }

    // ── sweeps ───────────────────────────────────────────────────────────────────

    /** Catch-up sweep over currently-loaded chunks (chunks loaded before enable never fire ChunkLoadEvent).
     *  Tick-spread + resumable so thousands of heads don't stall the main thread. */
    void startEnableSweep() {
        sweep(null);
    }

    /** Run the sweep, reporting a residue summary to {@code sender} (null = console, on enable). */
    void sweep(CommandSender sender) {
        int before = adoptedBlocks;
        Deque<Chunk> queue = new ArrayDeque<>();
        for (World w : plugin.getServer().getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) queue.add(c);
        }
        int total = queue.size();
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
                adoptChunk(queue.poll());
            }
            if (queue.isEmpty()) {
                int adopted = adoptedBlocks - before;
                String msg = "Migration sweep done: adopted " + adopted + " placed head(s) across "
                        + total + " loaded chunk(s); re-stamped " + restampedItems + " item(s); "
                        + uuidDiverged + " head(s) had a diverged profile UUID (still adopted).";
                plugin.getLogger().info(msg);
                if (sender != null && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                    sender.sendMessage(net.kyori.adventure.text.Component.text(msg,
                            net.kyori.adventure.text.format.NamedTextColor.GREEN));
                }
                task.cancel();
            }
        }, 1L, 1L);
    }
}
