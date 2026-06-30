package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Slime-glue authoring UX. Owns every glue-item interaction at {@link EventPriority#LOWEST} and cancels
 * it, so the existing {@code HIGH, ignoreCancelled=true} interact handlers (rotator angle-cycle, door
 * state, block placement) automatically skip it. When glue is not in the main hand this listener is inert.
 */
final class GlueAuthoring implements Listener {

    /** Custom-block ids that can own a glued structure (skull anchors). */
    private static final Set<String> ANCHOR_IDS = Set.of("demo:door_controller", "rotation:rotator");

    /** A cuboid-fill box larger than this many cells is rejected (avoids enumerating huge volumes). */
    private static final int MAX_BOX_VOLUME = 32_768;

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final GlueManager glue;
    private final int outlineInterval;
    private final int sessionTimeout;

    private final Map<UUID, GlueSession> sessions = new HashMap<>();

    GlueAuthoring(JavaPlugin plugin, CustomBlockRegistry registry, GlueManager glue,
                  int outlineInterval, int sessionTimeout) {
        this.plugin = plugin;
        this.registry = registry;
        this.glue = glue;
        this.outlineInterval = Math.max(1, outlineInterval);
        this.sessionTimeout = sessionTimeout;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickOutlines, outlineInterval, outlineInterval);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Interaction
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!GlueItem.isGlueItem(player.getInventory().getItemInMainHand())) return;

        boolean sneak = player.isSneaking();
        GlueSession session = sessions.get(player.getUniqueId());

        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> {
                Block clicked = event.getClickedBlock();
                if (clicked == null) return;
                if (isAnchor(clicked)) {
                    event.setCancelled(true);
                    if (sneak) unglueAllAt(player, clicked);
                    else toggleSession(player, clicked);
                } else if (session != null) {
                    event.setCancelled(true);
                    handleCorner(player, session, clicked);
                }
                // else: holding glue, no session, non-anchor block → don't interfere
            }
            case LEFT_CLICK_BLOCK -> {
                if (session == null) return;
                Block clicked = event.getClickedBlock();
                if (clicked == null) return;
                event.setCancelled(true);
                session.touch(now());
                if (sneak) {
                    if (glue.unglue(session.anchor, clicked)) {
                        feedback(player, clicked, false);
                    }
                } else {
                    reportGlue(player, clicked,
                        glue.glue(session.anchor, clicked, isDrawbridgeAnchor(session.anchor.originBlock())));
                }
            }
            case RIGHT_CLICK_AIR -> {
                if (session == null || session.cornerA == null) return;
                event.setCancelled(true);
                session.cornerA = null;
                actionBar(player, "Corner A reset", NamedTextColor.YELLOW);
            }
            default -> { }
        }
    }

    /** Suppress block breaking while a player is actively authoring (covers creative instant-break). */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        GlueSession s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && GlueItem.isGlueItem(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ──────────────────────────────────────────────────────────────────────

    private void toggleSession(Player player, Block anchorBlock) {
        UUID id = player.getUniqueId();
        Object key = CustomBlockRegistry.LocationKey.of(anchorBlock);
        GlueSession existing = sessions.get(id);
        if (existing != null && existing.anchor.identityKey().equals(key)) {
            sessions.remove(id);
            actionBar(player, "Glue: session closed", NamedTextColor.GRAY);
            return;
        }
        for (GlueSession s : sessions.values()) {
            if (!s.player.equals(id) && s.anchor.identityKey().equals(key)) {
                actionBar(player, "Another player is editing this anchor", NamedTextColor.RED);
                return;
            }
        }
        Anchor anchor = new BlockAnchor(anchorBlock, () -> true);
        sessions.put(id, new GlueSession(anchor, id, now()));
        player.playSound(player.getLocation(), Sound.BLOCK_SLIME_BLOCK_PLACE, 0.6f, 1.4f);
        actionBar(player, "Glue: editing (" + glue.offsets(anchor).size() + " blocks)", NamedTextColor.GREEN);
    }

    private void unglueAllAt(Player player, Block anchorBlock) {
        Anchor anchor = new BlockAnchor(anchorBlock, () -> true);
        glue.unglueAll(anchor);
        GlueSession s = sessions.get(player.getUniqueId());
        if (s != null && s.anchor.identityKey().equals(anchor.identityKey())) s.cornerA = null;
        actionBar(player, "Glue: cleared", NamedTextColor.YELLOW);
    }

    private void handleCorner(Player player, GlueSession s, Block clicked) {
        s.touch(now());
        if (s.cornerA == null) {
            s.cornerA = clicked;
            actionBar(player, "Corner A set — right-click corner B", NamedTextColor.AQUA);
            return;
        }
        Block a = s.cornerA;
        s.cornerA = null;
        long volume = (Math.abs(a.getX() - clicked.getX()) + 1L)
            * (Math.abs(a.getY() - clicked.getY()) + 1L)
            * (Math.abs(a.getZ() - clicked.getZ()) + 1L);
        if (volume > MAX_BOX_VOLUME) {
            actionBar(player, "Box too large (" + volume + " cells)", NamedTextColor.RED);
            return;
        }
        GlueManager.FillResult r = glue.glueCuboid(s.anchor, boxBlocks(a, clicked),
            isDrawbridgeAnchor(s.anchor.originBlock()));
        player.playSound(player.getLocation(), Sound.BLOCK_SLIME_BLOCK_PLACE, 0.6f, 1.1f);
        actionBar(player, "+" + r.added() + " glued, " + r.skipped() + " skipped", NamedTextColor.GREEN);
    }

    private List<Block> boxBlocks(Block a, Block b) {
        World w = a.getWorld();
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        List<Block> out = new ArrayList<>();
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    out.add(w.getBlockAt(x, y, z));
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChangeWorld(PlayerChangedWorldEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) return;
        if (!GlueItem.isGlueItem(event.getPlayer().getInventory().getItem(event.getNewSlot()))) {
            sessions.remove(event.getPlayer().getUniqueId());
            actionBar(event.getPlayer(), "Glue: session closed", NamedTextColor.GRAY);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Outline + timeout
    // ──────────────────────────────────────────────────────────────────────

    private void tickOutlines() {
        if (sessions.isEmpty()) return;
        long now = now();
        var it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }
            GlueSession s = e.getValue();
            if (now - s.lastTouchTick > sessionTimeout) {
                it.remove();
                actionBar(p, "Glue: session timed out", NamedTextColor.GRAY);
                continue;
            }
            renderOutline(p, s);
        }
    }

    private void renderOutline(Player p, GlueSession s) {
        Block origin = s.anchor.originBlock();
        World w = s.anchor.world();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();

        // Green dust at the eight cube-corners of every glued block, deduped so corners shared by
        // adjacent blocks render once (scales with extent, not block count).
        var green = new Particle.DustOptions(Color.LIME, 0.8f);
        Set<Long> seen = new HashSet<>();
        for (Vector3i off : glue.offsets(s.anchor)) {
            int bx = ox + off.x, by = oy + off.y, bz = oz + off.z;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            spawnCorners(p, bx, by, bz, green, ox, oy, oz, seen);
        }

        // Orange dust around the hinge (anchor) block.
        if (w.isChunkLoaded(ox >> 4, oz >> 4)) {
            spawnCorners(p, ox, oy, oz, new Particle.DustOptions(Color.ORANGE, 1.0f), ox, oy, oz, null);
        }

        // Pending cuboid corner A — distinct cyan marker.
        Block c = s.cornerA;
        if (c != null && w.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) {
            var dustA = new Particle.DustOptions(Color.AQUA, 1.4f);
            p.spawnParticle(Particle.DUST, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 1, 0, 0, 0, 0, dustA);
        }
    }

    /** Spawn dust at the 8 corners of the cube [x,x+1]^3, optionally deduped against {@code seen}. */
    private void spawnCorners(Player p, int x, int y, int z, Particle.DustOptions dust,
                              int ox, int oy, int oz, Set<Long> seen) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int cx = x + dx, cy = y + dy, cz = z + dz;
                    if (seen != null && !seen.add(cornerKey(cx - ox, cy - oy, cz - oz))) continue;
                    p.spawnParticle(Particle.DUST, cx, cy, cz, 1, 0, 0, 0, 0, dust);
                }
            }
        }
    }

    // Pack a corner's coords relative to the hinge into a long. Structures fit well within ±1024 of it.
    private static long cornerKey(int rx, int ry, int rz) {
        return ((long) (rx + 1024) << 22) | ((long) (ry + 1024) << 11) | (long) (rz + 1024);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean isAnchor(Block block) {
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        return type != null && ANCHOR_IDS.contains(type.fullId());
    }

    /** Whether this anchor's mechanism rotates about a horizontal (X/Z drawbridge) axis. */
    private boolean isDrawbridgeAnchor(Block anchorBlock) {
        CustomHeadBlock type = registry.getTypeFromBlock(anchorBlock);
        if (type == null || !"rotation:rotator".equals(type.fullId())) return false; // doors are Y
        String state = registry.getState(anchorBlock);
        if (state == null) return false;
        RotationNetwork.Axis axis = RotationNetwork.axisFromState(state);
        return axis == RotationNetwork.Axis.X || axis == RotationNetwork.Axis.Z;
    }

    private void reportGlue(Player player, Block block, GlueManager.Result r) {
        switch (r) {
            case OK -> feedback(player, block, true);
            case ALREADY_GLUED -> actionBar(player, "Already glued", NamedTextColor.YELLOW);
            case CAP_HIT -> reject(player, block, "Glue cap reached (" + glue.maxSize() + ")");
            case NOT_CONNECTED -> reject(player, block, "Not connected to the structure");
            case IS_ANCHOR -> actionBar(player, "That's the hinge", NamedTextColor.RED);
            case AXIS_INCOMPATIBLE -> reject(player, block, "Won't rotate on a drawbridge");
        }
    }

    private void feedback(Player player, Block block, boolean added) {
        var color = added ? Color.LIME : Color.fromRGB(0xFF5555);
        player.spawnParticle(Particle.DUST, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.25, 0.25, 0.25,
            new Particle.DustOptions(color, 1.0f));
        player.playSound(block.getLocation(), Sound.BLOCK_SLIME_BLOCK_HIT, 0.5f, added ? 1.6f : 0.8f);
    }

    /** Rejection feedback: red dust at the block + a low deny tone + a brief reason on the action bar. */
    private void reject(Player player, Block block, String reason) {
        player.spawnParticle(Particle.DUST, block.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3,
            new Particle.DustOptions(Color.fromRGB(0xFF3030), 1.2f));
        player.playSound(block.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
        actionBar(player, reason, NamedTextColor.RED);
    }

    private void actionBar(Player player, String text, NamedTextColor color) {
        player.sendActionBar(Component.text(text, color));
    }

    private static long now() {
        return Bukkit.getCurrentTick();
    }
}
