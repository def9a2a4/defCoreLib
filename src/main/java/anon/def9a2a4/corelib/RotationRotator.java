package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;

import java.util.*;

/**
 * Rotator: the bridge between the rotation-power network and the mechanism system.
 *
 * <p>A CONSUMER block (floor → door about Y, wall → drawbridge about X/Z). It is <b>stateless</b> —
 * it has no "open"/"closed". On a <b>redstone rising edge</b> (off→on) it checks it has rotation
 * power, resolves its structure (authored glue, else the single block it's placed on), and rotates it
 * once by the target angle (right-click opens a menu to pick 90/180/270/360) in the network's current
 * spin direction. Reverse the network (a redstone reverser, or wrench the source) to rotate the other
 * way; each pulse is one rotation, then real blocks are restored. A 360° target is a full spin that
 * lands the structure back in its original cells.
 *
 * <p>Swing speed is locked at start to {@code clamp(K * surplus / mass)}; while swinging the rotator
 * registers transient network demand so contending rotators slow each other down. Block selection is
 * the glued selection, defaulting to the single attachment block when no glue is authored.
 */
final class RotationRotator implements Listener {

    private static final String ROTATOR_ID = "mech:rotator";

    // Tuning (live-tunable): per-tick swing speed = clamp(K * surplus / blockCount, MIN, MAX).
    private static final float SPEED_K = 8f;
    private static final float MIN_DEG = 1.5f;
    private static final float MAX_DEG = 9f;
    // Demand the rotator places on the network while swinging (others see reduced surplus).
    private static final int SWING_DEMAND = 2;

    private static final NamespacedKey TARGET_KEY = new NamespacedKey("mech", "rotator_target");

    // Angle-selection GUI: one button per target angle. Slots on the middle row, mirroring the
    // RedstoneDynamo mode menu ({10,12,14,16}). ANGLES/ANGLE_SLOTS/ANGLE_LABELS are index-aligned.
    private static final int[] ANGLES = {90, 180, 270, 360};
    private static final int[] ANGLE_SLOTS = {10, 12, 14, 16};
    private static final String[] ANGLE_LABELS = {
        "90° — quarter turn", "180° — half turn", "270° — three-quarter turn", "360° — full spin"
    };
    private static final int DEFAULT_TARGET = 90;

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;

    private final Map<CustomBlockRegistry.LocationKey, Mechanism> activeRotators = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, BukkitTask> activeTasks = new HashMap<>();
    // Last redstone-power state, for off→on rising-edge detection.
    private final Map<CustomBlockRegistry.LocationKey, Boolean> lastPowered = new HashMap<>();
    // Last server tick a rotator's network was recalculated — collapses a redstone pulse's burst of
    // BlockPhysicsEvents into one recalc per tick (a full O(network) rebuild is far too expensive to
    // run per physics event, and redstone power isn't part of the network topology anyway).
    private final Map<CustomBlockRegistry.LocationKey, Long> lastRecalcTick = new HashMap<>();
    // Swings committed per hinge — survives a swing's transient teardown so the showcase harness can
    // assert a swing actually happened.
    private final Map<CustomBlockRegistry.LocationKey, Integer> swingCount = new HashMap<>();

    RotationRotator(JavaPlugin plugin, CustomBlockRegistry registry,
                    RotationNetwork network, MechanismRegistry mechRegistry,
                    GlueManager glueManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.mechRegistry = mechRegistry;
        this.glueManager = glueManager;
    }

    void register() {
        CustomHeadBlock block = registry.getType(ROTATOR_ID);
        if (block == null) {
            plugin.getLogger().warning("RotationRotator: block '" + ROTATOR_ID + "' not found — skipping");
            return;
        }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> onNeighborChange(b))
            .onInteract(this::onInteract)
            .onChunkLoad((b, state) -> {
                network.addNode(b, ROTATOR_ID, RotationNetwork.axisFromState(state),
                    RotationNetwork.NodeRole.CONSUMER, 0, false);
                lastPowered.put(CustomBlockRegistry.LocationKey.of(b), b.getBlockPower() > 0);
            })
            .onChunkUnload(b -> { cleanup(b); network.removeNode(CustomBlockRegistry.LocationKey.of(b)); })
            .onBlockRemoved((b, state) -> { cleanup(b); network.removeNode(CustomBlockRegistry.LocationKey.of(b)); })
            .build());
        // Self-register for the angle-selection menu's click handler (mirrors RedstoneDynamo).
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Number of swings this hinge has committed since load (for the showcase test harness). */
    int swingCount(Block head) {
        return swingCount.getOrDefault(CustomBlockRegistry.LocationKey.of(head), 0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Trigger: redstone rising edge → one rotation
    // ──────────────────────────────────────────────────────────────────────

    private void onNeighborChange(Block head) {
        var key = CustomBlockRegistry.LocationKey.of(head);
        // Debounce the recalc to once per tick per node: a single redstone pulse fires a burst of
        // BlockPhysicsEvents, and recalc() is a full network teardown + BFS rebuild. A real structural
        // change still recalcs (next tick at latest); the once-per-tick recalc reflects the tick's
        // final state, and redstone power is not a network input so collapsing the burst is lossless.
        long tick = Bukkit.getServer().getCurrentTick();
        if (!Long.valueOf(tick).equals(lastRecalcTick.get(key))) {
            lastRecalcTick.put(key, tick);
            recalc(head);
        }
        boolean now = head.getBlockPower() > 0;
        boolean was = lastPowered.getOrDefault(key, false);
        lastPowered.put(key, now); // update first → repeated same-tick neighbor events are idempotent
        if (now && !was) trigger(head); // off→on rising edge
    }

    private void trigger(Block head) {
        var key = CustomBlockRegistry.LocationKey.of(head);
        if (activeTasks.containsKey(key)) return; // already swinging — ignore this pulse

        RotationNetwork.SpinDirection dir = network.getDirection(key);
        if (dir == null || !network.isPowered(key)) { feedbackNoPower(head); return; }

        // Read surplus BEFORE registering our own transient demand, so it excludes us.
        int[] stats = network.getNetworkStats(key);
        if (stats == null) return;
        int surplus = stats[0] - stats[1];
        if (surplus <= 0) { feedbackNoPower(head); return; }

        // Our "self" cell — never swung: the head itself (a sticky block beside it must not drag
        // the controller into its own swing). Power components are ordinary cargo.
        Set<CustomBlockRegistry.LocationKey> excluded = MoverExclusion.exclusionFor(List.of(head));

        Anchor anchor = new BlockAnchor(head, () -> !activeRotators.containsKey(key));
        List<Block> resolved = glueManager.resolveStructure(anchor,
            excluded, MoverExclusion::blockedParticle);
        boolean glued = resolved != null && !resolved.isEmpty();
        // Pre-move snapshot: rebind stores ONLY authored glue (derived casings/leaves re-derive).
        final int[] authored = glued ? anchor.readOffsets() : null;
        List<Block> planks;
        if (glued) {
            planks = resolved;
        } else {
            Block seed = attachmentBlock(head);   // no glue → swing the block the rotator is placed on
            if (!MovableBlocks.isMovable(seed, registry)) return;   // don't scoop air / immovable world blocks
            planks = List.of(seed);
        }
        // Sticky spread: a casing/slime/honey block in the swung set bonds its neighbours (transitively).
        planks = StickySpread.withDerived(planks, registry, glueManager.maxSize(),
            excluded, MoverExclusion::blockedParticle);

        RotationNetwork.RotationNode node = network.getNode(key);
        if (node == null) return;
        Vector3f axis = axisVec(node.axis());
        Mechanism mech = mechRegistry.assembleMechanism(ROTATOR_ID, planks,
            head.getLocation().add(0.5, 0, 0.5), axis, null);
        if (glued) mech.setOnDisassembled(p ->
            glueManager.rebindTransformed(anchor, authored, mech.landingRotation()));
        activeRotators.put(key, mech);

        int mass = Math.max(1, mech.blockCount());
        float speed = clamp(SPEED_K * surplus / mass, MIN_DEG, MAX_DEG);
        int targetAngle = readTarget(head);
        float target = (dir == RotationNetwork.SpinDirection.CW) ? -targetAngle : targetAngle;
        network.addTransientDemand(key, SWING_DEMAND);

        swingCount.merge(key, 1, Integer::sum);
        startSwing(key, mech, speed, target);
    }

    private void startSwing(CustomBlockRegistry.LocationKey key, Mechanism mech,
                            float speed, float target) {
        float limit = Math.abs(target);
        float sign = Math.signum(target);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            float angle = 0f;
            @Override public void run() {
                angle += speed;
                boolean done = angle >= limit;
                mech.rotate(done ? target : sign * angle);
                if (done) {
                    // Order matters: keep the activeTasks guard up THROUGH disassembly so the
                    // block-place physics it fires can't re-enter trigger() and start a new swing.
                    mech.disassemble();
                    activeRotators.remove(key);
                    network.clearTransientDemand(key);
                    BukkitTask t = activeTasks.remove(key);
                    if (t != null) t.cancel();
                }
            }
        }, 2L, 1L); // 2-tick delay lets the display passengers mount before the first rotate
        activeTasks.put(key, task);
    }

    private void cleanup(Block head) {
        var key = CustomBlockRegistry.LocationKey.of(head);
        BukkitTask task = activeTasks.remove(key);
        if (task != null) task.cancel();
        network.clearTransientDemand(key);
        lastPowered.remove(key);
        lastRecalcTick.remove(key);
        Mechanism mech = activeRotators.remove(key);
        if (mech != null) mech.disassemble();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Interaction: wrench → status readout; empty hand → angle-selection menu
    // ──────────────────────────────────────────────────────────────────────

    private boolean onInteract(Block head, org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();
        var key = CustomBlockRegistry.LocationKey.of(head);
        if (RotationBlocks.isWrench(player.getInventory().getItemInMainHand())) {
            RotationNetwork.SpinDirection dir = network.getDirection(key);
            player.sendActionBar(Component.text(
                "Rotator: target " + readTarget(head) + "° | "
                    + (network.isPowered(key)
                        ? "powered" + (dir != null ? " " + dir : "") : "unpowered")
                    + " | pulse redstone to rotate",
                NamedTextColor.GOLD));
            return true;
        }
        // Empty/other hand → open the angle menu (return true cancels the event, so the head's
        // vanilla interaction never fires).
        openMenu(player, head);
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Angle-selection GUI (mirrors the RedstoneDynamo mode menu)
    // ──────────────────────────────────────────────────────────────────────

    private void openMenu(Player player, Block head) {
        RotatorModeHolder holder = new RotatorModeHolder(head.getLocation());
        Inventory inv = Bukkit.createInventory(holder, 27,
            Component.text("Rotator Angle", NamedTextColor.LIGHT_PURPLE));
        holder.setInventory(inv);
        populate(inv, readTarget(head));
        player.openInventory(inv);
    }

    private void populate(Inventory inv, int current) {
        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), Component.empty(), List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        inv.setItem(4, named(new ItemStack(Material.CLOCK),
            Component.text("Rotation Angle", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
            List.of(text("How far each pulse turns the structure.", NamedTextColor.GRAY),
                    text("Spin direction still comes from the network.", NamedTextColor.GRAY))));

        for (int i = 0; i < ANGLES.length; i++) {
            inv.setItem(ANGLE_SLOTS[i], angleButton(ANGLES[i], ANGLE_LABELS[i], ANGLES[i] == current));
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RotatorModeHolder holder)) return;
        event.setCancelled(true); // read-only menu
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Block head = holder.blockLocation().getBlock();
        if (registry.getTypeFromBlock(head) == null) { player.closeInventory(); return; } // block gone

        int angle = angleForSlot(event.getRawSlot());
        if (angle <= 0) return;

        writeTarget(head, angle);
        head.getWorld().playSound(head.getLocation().add(0.5, 0.5, 0.5),
            Sound.BLOCK_COPPER_PLACE, 0.6f, 1.4f);
        populate(event.getInventory(), angle); // refresh highlight in place
    }

    private static int angleForSlot(int slot) {
        for (int i = 0; i < ANGLE_SLOTS.length; i++) if (ANGLE_SLOTS[i] == slot) return ANGLES[i];
        return -1;
    }

    // ── Item helpers (italic-off discipline mirrors RedstoneDynamo) ──────────────────────────────

    private static ItemStack angleButton(int angle, String label, boolean selected) {
        ItemStack it = new ItemStack(Material.CLOCK, Math.max(1, angle / 90));
        Component name = Component.text(label, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false);
        List<Component> lore = List.of(
            text(selected ? "✔ Selected" : "Click to select",
                selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        it = named(it, name, lore);
        if (selected) {
            var meta = it.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack named(ItemStack it, Component name, List<Component> lore) {
        var meta = it.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    private void feedbackNoPower(Block head) {
        head.getWorld().spawnParticle(Particle.SMOKE,
            head.getLocation().add(0.5, 1.0, 0.5), 4, 0.15, 0.1, 0.15, 0.01);
        head.getWorld().playSound(head.getLocation().add(0.5, 0.5, 0.5),
            Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.0f);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private void recalc(Block block) {
        var key = CustomBlockRegistry.LocationKey.of(block);
        if (network.getNode(key) != null) network.recalculate(key);
    }

    private static Vector3f axisVec(RotationNetwork.Axis axis) {
        return switch (axis) {
            case X -> new Vector3f(1, 0, 0);
            case Z -> new Vector3f(0, 0, 1);
            default -> new Vector3f(0, 1, 0);
        };
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** The block a rotator head is placed on: behind for a wall head (drawbridge), below for a floor
     *  head (door). Used as the default single-block structure when no glue is authored. */
    private static Block attachmentBlock(Block head) {
        if (head.getBlockData() instanceof org.bukkit.block.data.Directional d) {
            return head.getRelative(d.getFacing().getOppositeFace());
        }
        return head.getRelative(BlockFace.DOWN);
    }

    private int readTarget(Block head) {
        if (!(head.getState() instanceof Skull skull)) return DEFAULT_TARGET;
        Integer v = skull.getPersistentDataContainer().get(TARGET_KEY, PersistentDataType.INTEGER);
        return (v == null) ? DEFAULT_TARGET : v;
    }

    private void writeTarget(Block head, int target) {
        if (!(head.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(TARGET_KEY, PersistentDataType.INTEGER, target);
        skull.update();
    }
}
