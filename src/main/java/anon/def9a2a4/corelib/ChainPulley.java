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
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
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

    // 1.21.9 (Copper Age) renamed CHAIN → IRON_CHAIN. Resolve by name so this compiles against either
    // API and runs on either server, rather than referencing a possibly-missing enum constant.
    private static final Material CHAIN_MATERIAL = resolveChain();

    private static Material resolveChain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        return m != null ? m : Material.matchMaterial("CHAIN");
    }

    private static final int MAX_DIST = 32;
    private static final double MAX_DIST_SQ = (double) MAX_DIST * MAX_DIST;
    private static final float DIAMETER = 0.33f;
    /** Chain strand length multiplier (the requested "2× longer"). */
    private static final float LENGTH_SCALE = 2f;
    /** Shift the strand this fraction of the wheel-to-wheel distance along the link (toward the partner). */
    private static final float FORWARD_FRACTION = 0.5f;
    /** Radians the powered strand rotates about its long axis per tick. */
    private static final float SPIN_RATE = 0.25f;
    /**
     * Strand display type segment. Deliberately NOT "chain_pulley" so the block-display refresh
     * (which removes {@code corelib:mech:chain_pulley:<x>_<y>_<z>*} on every idle↔spinning state change)
     * can't delete the strand — while still under {@code corelib:mech:} so the orphan scanner skips it.
     */
    private static final String STRAND_TYPE = "chain_strand";

    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    // Transient per-player first-click selection (the link source); not persisted.
    private final Map<UUID, CustomBlockRegistry.LocationKey> pendingSelection = new HashMap<>();
    // Live strand displays keyed by their source pulley, for the per-tick spin animation.
    private final Map<CustomBlockRegistry.LocationKey, StrandAnim> strands = new HashMap<>();
    private int spinTicks = 0;
    // Chain-strand head texture (the @chain art), captured at register().
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
        strandTexture = block.resolveTexture("strand", 0, null); // the @chain art (texture-only state)
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
            network.linkChain(key, partner);
            // Re-register the strand for animation: reuse the persisted display if it survived the
            // reload, otherwise spawn a fresh one.
            var existing = DisplayUtil.findByTag(b.getLocation(), strandTag(b.getLocation()), 1.5);
            ItemDisplay disp = null;
            for (Display d : existing) { if (d instanceof ItemDisplay id) { disp = id; break; } }
            if (disp != null) registerStrand(b, partner, disp);
            else spawnStrand(b, partner);
        }
    }

    private void handleBlockRemoved(Block b) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(b);
        // This pulley's own outgoing strand goes away.
        if (network.chainOutOf(key) != null) {
            removeStrand(b.getLocation(), key);
            network.unlinkChain(key);
        }
        // Any pulley whose link targets this one is now dangling — clear it.
        for (CustomBlockRegistry.LocationKey src : network.chainIntoOf(key)) {
            network.unlinkChain(src);
            Block srcBlock = blockOf(src);
            if (srcBlock != null) {
                clearPartner(srcBlock);
                removeStrand(srcBlock.getLocation(), src);
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
        if (player.getInventory().getItemInMainHand().getType() != CHAIN_MATERIAL) {
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
                removeStrand(b.getLocation(), key);
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

    /** Spawn a fresh strand from source→target and register it for animation. */
    private void spawnStrand(Block source, CustomBlockRegistry.LocationKey target) {
        StrandAnim base = strandBase(source, target);
        if (base == null) return;
        ItemStack head = HeadUtil.createHead(strandTexture, 1);
        Transformation transform = new Transformation(
            base.translation, new Quaternionf(base.orient), base.scale, new Quaternionf());
        ItemDisplay display = DisplayUtil.spawn(
            new Location(source.getWorld(), source.getX(), source.getY(), source.getZ()),
            head, transform, strandTag(source.getLocation()));
        base.display = display;
        strands.put(CustomBlockRegistry.LocationKey.of(source), base);
    }

    /** Register an already-spawned (reloaded) strand display for animation. */
    private void registerStrand(Block source, CustomBlockRegistry.LocationKey target, ItemDisplay display) {
        StrandAnim base = strandBase(source, target);
        if (base == null) return;
        base.display = display;
        strands.put(CustomBlockRegistry.LocationKey.of(source), base);
    }

    /**
     * Geometry for a strand: the head's long axis is local +Y (the Y-stretch is what links the two
     * wheels, thin in X/Z), aimed along the gap, stretched to {@code length * LENGTH_SCALE}, centred on
     * the midpoint. Anchor is the source block centre (DisplayUtil.spawn adds +0.5).
     */
    private @Nullable StrandAnim strandBase(Block source, CustomBlockRegistry.LocationKey target) {
        float dx = target.x() - source.getX();
        float dy = target.y() - source.getY();
        float dz = target.z() - source.getZ();
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-3f) return null;
        Quaternionf orient = new Quaternionf().rotationTo(0f, 1f, 0f, dx, dy, dz);
        // Midpoint, then nudged forward by FORWARD_FRACTION of the gap (toward the partner) so the
        // strand isn't centred on the source block.
        Vector3f translation = new Vector3f(
            dx / 2f + dx * FORWARD_FRACTION, dy / 2f + dy * FORWARD_FRACTION, dz / 2f + dz * FORWARD_FRACTION);
        Vector3f scale = new Vector3f(DIAMETER, length * LENGTH_SCALE, DIAMETER);
        return new StrandAnim(orient, translation, scale);
    }

    /** Remove a strand's display entity and stop animating it. */
    private void removeStrand(Location sourceLoc, CustomBlockRegistry.LocationKey sourceKey) {
        DisplayUtil.removeByTag(sourceLoc, strandTag(sourceLoc), 1.5);
        strands.remove(sourceKey);
    }

    /** Per-tick: spin every powered strand about its long axis; drop dead displays. */
    private void tickStrands() {
        if (strands.isEmpty()) return;
        spinTicks++;
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
            // T · Rorient · Ry(angle) · S — spins about the long (Y/stretch) axis; matches the spawn
            // Transformation at angle 0.
            Matrix4f m = new Matrix4f()
                .translate(s.translation)
                .rotate(s.orient)
                .rotateY(s.angle)
                .scale(s.scale);
            s.display.setTransformationMatrix(m);
        }
    }

    private static String strandTag(Location sourceLoc) {
        return DisplayUtil.blockTag("mech", STRAND_TYPE, sourceLoc, null);
    }

    /** Mutable animation state for one chain strand. */
    private static final class StrandAnim {
        @Nullable ItemDisplay display;
        final Quaternionf orient;
        final Vector3f translation;
        final Vector3f scale;
        float angle = 0f;
        StrandAnim(Quaternionf orient, Vector3f translation, Vector3f scale) {
            this.orient = orient;
            this.translation = translation;
            this.scale = scale;
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

    private static void consumeChain(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == CHAIN_MATERIAL) hand.setAmount(hand.getAmount() - 1);
    }

    private static void refundChain(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        player.getInventory().addItem(new ItemStack(CHAIN_MATERIAL));
    }
}
