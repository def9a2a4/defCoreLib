package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chain Pulley: a shaft-like rotation transmitter that can be linked to a second pulley at a distance
 * by an iron chain, so rotational power jumps the gap. The visual/recipe live in rotation-blocks.yml;
 * this class overlays the network node + the chain-link interaction + the stretched chain display.
 *
 * <p><b>Model.</b> Each pulley stores ONE outgoing partner (a directed functional graph) in its skull
 * PDC under {@link #OUT_KEY}. {@link RotationNetwork} injects that link as a distance edge only when
 * the pulley sits on a <em>closed loop</em> ({@link RotationNetwork#onClosedLoop}), so an open chain
 * renders but carries no power. Minimal loops: two pulleys linked {@code A→B} and {@code B→A}, or a
 * ring of three or more.
 *
 * <p><b>Link flow.</b> Right-click a pulley holding a {@link Material#CHAIN} to select it as the source;
 * right-click a second pulley (also holding a chain) to link source→target (max {@link #MAX_DIST}
 * blocks, same world, one outgoing link per pulley). Right-click a pulley that already has an outgoing
 * link (with no selection pending) to remove it (refunds a chain).
 */
final class ChainPulley {

    static final String PULLEY_ID = "mech:chain_pulley";
    /** Skull-PDC key holding this pulley's single outgoing partner as {@code int[]{x,y,z}} (same world). */
    private static final NamespacedKey OUT_KEY = new NamespacedKey("mech", "chain_out");

    private static final int MAX_DIST = 10;
    private static final double MAX_DIST_SQ = (double) MAX_DIST * MAX_DIST;
    private static final float DIAMETER = 0.33f;
    /** Display-tag suffix for the stretched chain strand (kept distinct from the pulley's wheel displays). */
    private static final String STRAND_SUFFIX = "link";

    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    // Transient per-player first-click selection (the link source); not persisted.
    private final Map<UUID, CustomBlockRegistry.LocationKey> pendingSelection = new HashMap<>();
    // Placeholder chain-strand head texture (the pulley's own resolved head), captured at register().
    private String strandTexture = "";

    ChainPulley(CustomBlockRegistry registry, RotationNetwork network) {
        this.registry = registry;
        this.network = network;
    }

    void register() {
        CustomHeadBlock block = registry.getType(PULLEY_ID);
        if (block == null) {
            registry.getPlugin().getLogger().warning("ChainPulley: block '" + PULLEY_ID + "' not found — skipping overlay");
            return;
        }
        strandTexture = block.texture(); // placeholder art; swap for a dedicated chain-link texture later
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> recalcIfNode(b))
            .onInteract(this::handleInteract)
            .onChunkLoad(this::handleChunkLoad)
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> handleBlockRemoved(b))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    private void handleChunkLoad(Block b, String state) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        network.addNode(b, PULLEY_ID, RotationNetwork.axisFromState(state),
            RotationNetwork.NodeRole.TRANSMITTER, 0, false);
        CustomBlockRegistry.LocationKey partner = readPartner(b);
        if (partner != null) {
            network.linkChain(key, partner);
            // Re-spawn the persisted strand only if it's missing (avoids duplicates on reload).
            String tag = strandTag(b.getLocation());
            if (DisplayUtil.findByTag(b.getLocation(), tag, 1.5).isEmpty()) {
                spawnStrand(b, partner);
            }
        }
    }

    private void handleBlockRemoved(Block b) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        // This pulley's own outgoing strand goes away.
        if (network.chainOutOf(key) != null) {
            DisplayUtil.removeByTag(b.getLocation(), strandTag(b.getLocation()), 1.5);
            network.unlinkChain(key);
        }
        // Any pulley whose link targets this one is now dangling — clear it.
        for (CustomBlockRegistry.LocationKey src : network.chainIntoOf(key)) {
            network.unlinkChain(src);
            Block srcBlock = blockOf(src);
            if (srcBlock != null) {
                clearPartner(srcBlock);
                DisplayUtil.removeByTag(srcBlock.getLocation(), strandTag(srcBlock.getLocation()), 1.5);
            }
        }
        network.removeNode(key);
    }

    private void recalcIfNode(Block b) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        if (network.getNode(key) != null) network.recalculate(key);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain-link interaction
    // ──────────────────────────────────────────────────────────────────────

    private boolean handleInteract(Block b, PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != Material.CHAIN) {
            return false; // not linking — let placement / other handlers proceed
        }
        event.setCancelled(true); // don't place the chain block
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        CustomBlockRegistry.LocationKey pending = pendingSelection.get(player.getUniqueId());

        if (pending == null) {
            // No selection yet. A pulley that already has an outgoing chain → remove it (refund). One
            // that doesn't → select it as the link source. (Sneak can't reach here — the dispatcher
            // only routes sneak-clicks for the wrench, so unlink is a plain right-click.)
            if (network.chainOutOf(key) != null) {
                clearPartner(b);
                network.unlinkChain(key);
                DisplayUtil.removeByTag(b.getLocation(), strandTag(b.getLocation()), 1.5);
                refundChain(player);
                player.sendActionBar(Component.text("Chain removed", NamedTextColor.YELLOW));
                return true;
            }
            pendingSelection.put(player.getUniqueId(), key);
            player.sendActionBar(Component.text("Chain attached — right-click another pulley to link", NamedTextColor.AQUA));
            return true;
        }

        // Second click → link pending (source) → this (target).
        pendingSelection.remove(player.getUniqueId());
        if (pending.equals(key)) {
            player.sendActionBar(Component.text("Link cancelled", NamedTextColor.GRAY));
            return true;
        }
        String reject = validateLink(pending, key);
        if (reject != null) {
            player.sendActionBar(Component.text(reject, NamedTextColor.RED));
            return true;
        }
        Block sourceBlock = blockOf(pending);
        if (sourceBlock == null) {
            player.sendActionBar(Component.text("Source pulley unavailable", NamedTextColor.RED));
            return true;
        }
        writePartner(sourceBlock, key);
        network.linkChain(pending, key);
        spawnStrand(sourceBlock, key);
        consumeChain(player);
        player.sendActionBar(network.onClosedLoop(pending)
            ? Component.text("Chain linked — loop closed, power flows!", NamedTextColor.GREEN)
            : Component.text("Chain linked — close the loop to transmit power", NamedTextColor.GOLD));
        return true;
    }

    /** @return a rejection message, or null if the link is valid. */
    private @Nullable String validateLink(CustomBlockRegistry.LocationKey source,
                                          CustomBlockRegistry.LocationKey target) {
        if (source.equals(target)) return "Can't link a pulley to itself";
        if (!source.worldId().equals(target.worldId())) return "Pulleys are in different worlds";
        if (network.getNode(source) == null) return "Source pulley no longer exists";
        if (network.chainOutOf(source) != null) return "Source pulley already has an outgoing chain";
        if (distSq(source, target) > MAX_DIST_SQ) return "Too far apart (max " + MAX_DIST + " blocks)";
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain strand display (custom head, diameter 0.33, stretched source→target)
    // ──────────────────────────────────────────────────────────────────────

    private void spawnStrand(Block source, CustomBlockRegistry.LocationKey target) {
        World world = source.getWorld();
        float dx = target.x() - source.getX();
        float dy = target.y() - source.getY();
        float dz = target.z() - source.getZ();
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-3f) return;
        // Head model's long axis is local +Y (as the shaft rod uses). Aim it along the gap, stretch to
        // the gap length, and translate to the midpoint. Spawn anchor is the source block centre.
        Transformation transform = new Transformation(
            new Vector3f(dx / 2f, dy / 2f, dz / 2f),
            new Quaternionf().rotationTo(0f, 1f, 0f, dx, dy, dz),
            new Vector3f(DIAMETER, length, DIAMETER),
            new Quaternionf());
        ItemStack head = HeadUtil.createHead(strandTexture, 1);
        DisplayUtil.spawn(new Location(world, source.getX(), source.getY(), source.getZ()),
            head, transform, strandTag(source.getLocation()));
    }

    private static String strandTag(Location sourceLoc) {
        return DisplayUtil.blockTag("mech", "chain_pulley", sourceLoc, STRAND_SUFFIX);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Skull-PDC persistence (mirrors GlueManager.GLUE_KEY / BlockAnchor)
    // ──────────────────────────────────────────────────────────────────────

    private CustomBlockRegistry.@Nullable LocationKey readPartner(Block b) {
        if (!(b.getState() instanceof Skull skull)) return null;
        int[] xyz = skull.getPersistentDataContainer().get(OUT_KEY, PersistentDataType.INTEGER_ARRAY);
        if (xyz == null || xyz.length < 3) return null;
        return new CustomBlockRegistry.LocationKey(b.getWorld().getUID(), xyz[0], xyz[1], xyz[2]);
    }

    private void writePartner(Block b, CustomBlockRegistry.LocationKey partner) {
        if (!(b.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(OUT_KEY, PersistentDataType.INTEGER_ARRAY,
            new int[]{ partner.x(), partner.y(), partner.z() });
        skull.update();
    }

    private void clearPartner(Block b) {
        if (!(b.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().remove(OUT_KEY);
        skull.update();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static double distSq(CustomBlockRegistry.LocationKey a, CustomBlockRegistry.LocationKey b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static @Nullable Block blockOf(CustomBlockRegistry.LocationKey k) {
        World w = Bukkit.getWorld(k.worldId());
        return w == null ? null : w.getBlockAt(k.x(), k.y(), k.z());
    }

    private static void consumeChain(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.CHAIN) hand.setAmount(hand.getAmount() - 1);
    }

    private static void refundChain(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        player.getInventory().addItem(new ItemStack(Material.CHAIN));
    }
}
