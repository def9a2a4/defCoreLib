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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
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
 * renders but carries no power. Reverse-duplicate links are rejected, so the minimum working loop is a
 * ring of three or more pulleys ({@code A→B→C→A}).
 *
 * <p><b>Link flow.</b> Right-click a pulley holding a chain to select it as the source; right-click a
 * second pulley (also holding a chain) to link source→target (max distance from config, same world, one
 * outgoing link per pulley). Right-click a pulley that already has an outgoing link (with no selection
 * pending) to remove it (refunds the chains); breaking or gluing a linked pulley drops them instead.
 */
final class ChainPulley {

    static final String PULLEY_ID = "mech:chain_pulley";
    /** Skull-PDC key holding this pulley's single outgoing partner as {@code int[]{x,y,z}} (same world). */
    private static final NamespacedKey OUT_KEY = new NamespacedKey("mech", "chain_out");

    // 1.21.9 (Copper Age) renamed CHAIN → IRON_CHAIN. Resolve by name so this compiles against either
    // API and runs on either server, rather than referencing a possibly-missing enum constant.
    private static final Material CHAIN_MATERIAL = resolveChain();

    private static Material resolveChain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        return m != null ? m : Material.matchMaterial("CHAIN");
    }

    /** Radians the powered chain rotates about its long axis per tick. */
    private static final float SPIN_RATE = 0.25f;
    /**
     * Strand display type segment. Deliberately NOT "chain_pulley" so the block-display refresh
     * (which removes {@code corelib:mech:chain_pulley:<x>_<y>_<z>*} on every idle↔spinning state change)
     * can't delete the strand. A live strand's owner is the pulley block, not a matching skull, so the
     * orphan scanner would mis-flag it — it is explicitly skipped there by its {@code chain_strand} tag.
     */
    private static final String STRAND_TYPE = "chain_strand";

    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    // Transient per-player first-click selection (the link source); not persisted.
    private final Map<UUID, CustomBlockRegistry.LocationKey> pendingSelection = new HashMap<>();
    // Live strand displays keyed by their source pulley, for the per-tick spin animation.
    private final Map<CustomBlockRegistry.LocationKey, StrandAnim> strands = new HashMap<>();
    // Max link distance (blocks), from rotation-config.yml chain-pulley.max-distance.
    private final int maxDist;
    private final double maxDistSq;

    ChainPulley(CustomBlockRegistry registry, RotationNetwork network, int maxDist) {
        this.registry = registry;
        this.network = network;
        this.maxDist = maxDist;
        this.maxDistSq = (double) maxDist * maxDist;
    }

    void register() {
        CustomHeadBlock block = registry.getType(PULLEY_ID);
        if (block == null) {
            registry.getPlugin().getLogger().warning("ChainPulley: block '" + PULLEY_ID + "' not found — skipping overlay");
            return;
        }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> recalcIfNode(b))
            .onInteract(this::handleInteract)
            .onChunkLoad(this::handleChunkLoad)
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> handleBlockRemoved(b))
            .build());
        // Per-tick spin: rotate each powered strand about its long axis. Only registry-owned displays
        // are driven by the central tickAnimations loop, so the runtime strands need their own ticker.
        Bukkit.getScheduler().runTaskTimer(registry.getPlugin(), this::tickStrands, 1L, 1L);
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
            // The partner may have been removed while this chunk was unloaded (its
            // handleBlockRemoved couldn't reach our skull PDC) — relinking blindly re-forms a
            // powerless ghost strand to dead coordinates. If the partner's chunk is loaded,
            // verify it still holds a pulley; if it doesn't, drop the stale link (the chains
            // were already refunded/dropped when the partner was broken). A partner in an
            // UNLOADED chunk keeps the current lazy-link behavior — onClosedLoop gates power.
            if (b.getWorld().isChunkLoaded(partner.x() >> 4, partner.z() >> 4)) {
                Block pb = b.getWorld().getBlockAt(partner.x(), partner.y(), partner.z());
                CustomHeadBlock ptype = registry.getTypeFromBlock(pb);
                if (ptype == null || !PULLEY_ID.equals(ptype.fullId())) {
                    clearPartner(b);
                    // Also delete the persisted strand display — the orphan scanner deliberately
                    // skips chain_strand tags, so nothing else would ever clean it up.
                    removeStrand(b.getLocation(), key);
                    return;
                }
            }
            network.linkChain(key, partner);
            // Re-register for animation: reuse the persisted display if it survived the reload,
            // otherwise spawn a fresh one.
            var existing = DisplayUtil.findByTag(b.getLocation(), strandTag(b.getLocation()), 1.5);
            if (!existing.isEmpty()) registerStrand(b, partner, existing.get(0));
            else spawnStrand(b, partner);
        }
    }

    private void handleBlockRemoved(Block b) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        // This pulley's own outgoing strand goes away; return the chains that link cost.
        CustomBlockRegistry.LocationKey outPartner = network.chainOutOf(key);
        if (outPartner != null) {
            removeStrand(b.getLocation(), key);
            network.unlinkChain(key);
            dropChains(b, chainCost(key, outPartner));
        }
        // Any pulley whose link targets this one is now dangling — clear it and return its chains.
        for (CustomBlockRegistry.LocationKey src : network.chainIntoOf(key)) {
            network.unlinkChain(src);
            Block srcBlock = blockOf(src);
            if (srcBlock != null) {
                clearPartner(srcBlock);
                removeStrand(srcBlock.getLocation(), src);
            }
            dropChains(b, chainCost(src, key));
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
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        // Wrench → inspect: the standard network debug (supply/demand/powered + member highlight, like
        // every other rotation block) plus this pulley's chain-link status. Needs no chain in hand.
        if (RotationBlocks.isWrench(mainHand)) {
            event.setCancelled(true);
            RotationBlocks.debugInteract(b, event, network, registry);
            sendChainStatus(b, player);
            return true;
        }
        if (mainHand.getType() != CHAIN_MATERIAL) {
            return false; // not linking — let placement / other handlers proceed
        }
        event.setCancelled(true); // don't place the chain block
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        CustomBlockRegistry.LocationKey pending = pendingSelection.get(player.getUniqueId());

        if (pending == null) {
            // No selection yet. A pulley that already has an outgoing chain → remove it (refund). One
            // that doesn't → select it as the link source. (Sneak can't reach here — the dispatcher
            // only routes sneak-clicks for the wrench, so unlink is a plain right-click.)
            CustomBlockRegistry.LocationKey outPartner = network.chainOutOf(key);
            if (outPartner != null) {
                int refund = chainCost(key, outPartner);
                clearPartner(b);
                network.unlinkChain(key);
                removeStrand(b.getLocation(), key);
                refundChain(player, refund);
                player.sendActionBar(Component.text("Chain removed", NamedTextColor.YELLOW));
                return true;
            }
            pendingSelection.put(player.getUniqueId(), key);
            player.sendActionBar(Component.text("Chain attached — right-click another pulley to link", NamedTextColor.AQUA));
            return true;
        }

        // Second click → link pending (source) → this (target). Keep the selection on a recoverable
        // rejection (invalid target / not enough chains) so a bad second click doesn't force a reselect;
        // clear it only on cancel, an unavailable source, or a successful link.
        if (pending.equals(key)) {
            pendingSelection.remove(player.getUniqueId());
            player.sendActionBar(Component.text("Link cancelled", NamedTextColor.GRAY));
            return true;
        }
        String reject = validateLink(pending, key);
        if (reject != null) {
            player.sendActionBar(Component.text(reject, NamedTextColor.RED));
            return true; // keep selection — right-click a different target to retry
        }
        Block sourceBlock = blockOf(pending);
        if (sourceBlock == null) {
            pendingSelection.remove(player.getUniqueId());
            player.sendActionBar(Component.text("Source pulley unavailable", NamedTextColor.RED));
            return true;
        }
        int cost = chainCost(pending, key);
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            int have = hand.getType() == CHAIN_MATERIAL ? hand.getAmount() : 0;
            if (have < cost) {
                player.sendActionBar(Component.text(
                    "Need " + cost + " chains for that distance (have " + have + ")", NamedTextColor.RED));
                return true; // keep selection — gather chains and retry
            }
        }
        pendingSelection.remove(player.getUniqueId());
        writePartner(sourceBlock, key);
        network.linkChain(pending, key);
        spawnStrand(sourceBlock, key);
        consumeChain(player, cost);
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
        CustomBlockRegistry.LocationKey targetOut = network.chainOutOf(target);
        if (targetOut != null && targetOut.equals(source)) return "Those pulleys are already linked";
        if (distSq(source, target) > maxDistSq) return "Too far apart (max " + maxDist + " blocks)";
        return null;
    }

    /** Wrench inspect: report this pulley's chain-link status (partner, loop, span/cost, selection). */
    private void sendChainStatus(Block b, Player player) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        CustomBlockRegistry.LocationKey out = network.chainOutOf(key);
        if (out == null) {
            boolean selected = key.equals(pendingSelection.get(player.getUniqueId()));
            player.sendMessage(Component.text(selected
                ? "Chain: selected as link source — right-click another pulley to link"
                : "Chain: not linked", NamedTextColor.GRAY));
            return;
        }
        boolean closed = network.onClosedLoop(key);
        int span = (int) Math.round(Math.sqrt(distSq(key, out)));
        player.sendMessage(Component.text(
            "Chain → " + out.x() + "," + out.y() + "," + out.z()
            + " | loop " + (closed ? "closed" : "open")
            + " | span " + span + " (cost " + chainCost(key, out) + ")",
            closed ? NamedTextColor.GREEN : NamedTextColor.GOLD));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain display: a single stretched IRON_CHAIN block display, rotating about its long axis
    // ──────────────────────────────────────────────────────────────────────

    /** Spawn a fresh chain display from source→target and register it for animation. */
    private void spawnStrand(Block source, CustomBlockRegistry.LocationKey target) {
        StrandAnim base = strandBase(source, target);
        if (base == null) return;
        BlockDisplay display = DisplayUtil.spawnBlock(
            new Location(source.getWorld(), source.getX(), source.getY(), source.getZ()),
            CHAIN_MATERIAL.createBlockData(), base.matrix(0f), strandTag(source.getLocation()));
        base.display = display;
        strands.put(CustomBlockRegistry.LocationKey.of(source), base);
    }

    /** Register an already-spawned (reloaded) chain display for animation. */
    private void registerStrand(Block source, CustomBlockRegistry.LocationKey target, Display display) {
        StrandAnim base = strandBase(source, target);
        if (base == null) return;
        base.display = display;
        strands.put(CustomBlockRegistry.LocationKey.of(source), base);
    }

    /**
     * Geometry for the chain: a single iron-chain block whose long axis is local +Y, aimed along the
     * gap and stretched to the gap length, centred on the midpoint (thin X/Z = natural chain
     * thickness). Anchor is the source block centre (DisplayUtil.spawnBlock adds +0.5).
     */
    private @Nullable StrandAnim strandBase(Block source, CustomBlockRegistry.LocationKey target) {
        float dx = target.x() - source.getX();
        float dy = target.y() - source.getY();
        float dz = target.z() - source.getZ();
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-3f) return null;
        Quaternionf orient = new Quaternionf().rotationTo(0f, 1f, 0f, dx, dy, dz);
        Vector3f translation = new Vector3f(dx / 2f, dy / 2f, dz / 2f); // midpoint
        Vector3f scale = new Vector3f(1f, length, 1f);                  // stretched along +Y
        return new StrandAnim(orient, translation, scale);
    }

    /** Remove a chain display and stop animating it. */
    private void removeStrand(Location sourceLoc, CustomBlockRegistry.LocationKey sourceKey) {
        DisplayUtil.removeByTag(sourceLoc, strandTag(sourceLoc), 1.5);
        strands.remove(sourceKey);
    }

    /** Per-tick: spin every powered chain about its long axis; drop dead displays. */
    private void tickStrands() {
        if (strands.isEmpty()) return;
        Iterator<Map.Entry<CustomBlockRegistry.LocationKey, StrandAnim>> it = strands.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<CustomBlockRegistry.LocationKey, StrandAnim> e = it.next();
            StrandAnim s = e.getValue();
            if (s.display == null || !s.display.isValid()) { it.remove(); continue; }
            // Spin only when the loop is complete AND powered — an open chain never moves, even if the
            // pulley is otherwise powered by a shaft.
            if (!network.isPowered(e.getKey()) || !network.onClosedLoop(e.getKey())) continue;
            int sign = network.getDirection(e.getKey()) == RotationNetwork.SpinDirection.CCW ? -1 : 1;
            s.angle += SPIN_RATE * sign;
            s.display.setTransformationMatrix(s.matrix(s.angle));
        }
    }

    private static String strandTag(Location sourceLoc) {
        return DisplayUtil.blockTag("mech", STRAND_TYPE, sourceLoc, null);
    }

    /** Mutable animation state for one chain display. */
    private static final class StrandAnim {
        @Nullable Display display;
        final Quaternionf orient;
        final Vector3f translation;
        final Vector3f scale;
        float angle = 0f;
        StrandAnim(Quaternionf orient, Vector3f translation, Vector3f scale) {
            this.orient = orient;
            this.translation = translation;
            this.scale = scale;
        }

        /**
         * T(pos) · R(orient) · Ry(angle) · S(scale) · T(−0.5) — the trailing −0.5 centres the
         * BlockDisplay unit cube (it renders from its MIN corner); Ry spins about the long (+Y) axis.
         */
        Matrix4f matrix(float angle) {
            return new Matrix4f()
                .translate(translation)
                .rotate(orient)
                .rotateY(angle)
                .scale(scale)
                .translate(-0.5f, -0.5f, -0.5f);
        }
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

    /** Number of chains a link costs = the rounded block distance between the two pulleys (min 1). */
    private static int chainCost(CustomBlockRegistry.LocationKey a, CustomBlockRegistry.LocationKey b) {
        return Math.max(1, (int) Math.round(Math.sqrt(distSq(a, b))));
    }

    private static void consumeChain(Player player, int count) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == CHAIN_MATERIAL) hand.setAmount(hand.getAmount() - count);
    }

    private static void refundChain(Player player, int count) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        player.getInventory().addItem(new ItemStack(CHAIN_MATERIAL, count));
    }

    /** Drop {@code count} chains into the world at a removed pulley (no player context on removal). */
    private static void dropChains(Block b, int count) {
        if (count <= 0) return;
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5),
            new ItemStack(CHAIN_MATERIAL, count));
    }
}
