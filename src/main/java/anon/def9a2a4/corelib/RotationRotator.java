package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;

import java.util.*;

/**
 * Rotator: the bridge between the rotation-power network and the mechanism system.
 *
 * <p>A CONSUMER block (floor → door about Y, wall → drawbridge about X/Z). When the network's
 * spin direction disagrees with the door's rest state, it flood-fills its oak-plank structure,
 * assembles a mechanism, and swings it to a target angle, then restores real blocks. Spin
 * direction CW = open/raise, CCW = close/lower. Swing speed is locked at start to
 * {@code clamp(K * surplus / mass)} and is not recomputed mid-swing; the rotator registers
 * transient network demand only while swinging, so contending rotators slow each other down.
 *
 * <p>v1: block selection is an oak-plank flood-fill (glue replaces this later); a door inside a
 * plank wall will over-grab — use a non-plank frame until glue lands.
 */
final class RotationRotator {

    private static final BlockFace[] CARDINAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
        BlockFace.UP, BlockFace.DOWN
    };

    private static final String ROTATOR_ID = "rotation:rotator";
    private static final Material STRUCTURE_MATERIAL = Material.OAK_PLANKS;
    private static final int MAX_BLOCKS = 256;

    // Tuning (live-tunable): per-tick swing speed = clamp(K * surplus / blockCount, MIN, MAX).
    private static final float SPEED_K = 8f;
    private static final float MIN_DEG = 1.5f;
    private static final float MAX_DEG = 9f;
    // Demand the rotator places on the network while swinging (others see reduced surplus).
    private static final int SWING_DEMAND = 2;

    private static final NamespacedKey TARGET_KEY = new NamespacedKey("rotation", "rotator_target");
    private static final NamespacedKey OPEN_KEY = new NamespacedKey("rotation", "rotator_open");

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final MechanismRegistry mechRegistry;

    private final Map<CustomBlockRegistry.LocationKey, Mechanism> activeRotators = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, BukkitTask> activeTasks = new HashMap<>();

    RotationRotator(JavaPlugin plugin, CustomBlockRegistry registry,
                    RotationNetwork network, MechanismRegistry mechRegistry) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.mechRegistry = mechRegistry;
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
            .tickInterval(4) // poll for direction changes (e.g. a reverser toggled elsewhere)
            .onNeighborChange((b, face) -> { recalc(b); tryActuate(b); })
            .onTick(this::tryActuate)
            .onInteract(this::onInteract)
            .onChunkLoad((b, state) -> network.addNode(b, ROTATOR_ID,
                RotationNetwork.axisFromState(state), RotationNetwork.NodeRole.CONSUMER, 0, false))
            .onChunkUnload(b -> { cleanup(b); network.removeNode(CustomBlockRegistry.LocationKey.of(b)); })
            .onBlockRemoved((b, state) -> { cleanup(b); network.removeNode(CustomBlockRegistry.LocationKey.of(b)); })
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Actuation
    // ──────────────────────────────────────────────────────────────────────

    /** Polled (onTick) and on local neighbor changes: start a swing if the network direction
     *  now disagrees with the door's rest state and isn't already mid-swing. */
    private void tryActuate(Block head) {
        var key = CustomBlockRegistry.LocationKey.of(head);
        if (activeTasks.containsKey(key)) return; // already swinging — finish first, then re-evaluate

        RotationNetwork.SpinDirection dir = network.getDirection(key);
        if (dir == null || !network.isPowered(key)) return;

        boolean desiredOpen = (dir == RotationNetwork.SpinDirection.CW);
        if (desiredOpen == readOpen(head)) return; // already at the desired rest state

        // Read surplus BEFORE registering our own transient demand, so it excludes us.
        int[] stats = network.getNetworkStats(key);
        if (stats == null) return;
        int surplus = stats[0] - stats[1];
        if (surplus <= 0) { feedbackUnderpowered(head); return; }

        List<Block> planks = floodFill(head, STRUCTURE_MATERIAL, MAX_BLOCKS);
        if (planks.isEmpty()) { writeOpen(head, desiredOpen); return; } // nothing to move — just flip the flag

        RotationNetwork.RotationNode node = network.getNode(key);
        if (node == null) return;
        Vector3f axis = axisVec(node.axis());
        Mechanism mech = mechRegistry.assembleMechanism(ROTATOR_ID, planks,
            head.getLocation().add(0.5, 0, 0.5), axis, null);
        activeRotators.put(key, mech);

        int mass = Math.max(1, mech.blockCount());
        float speed = clamp(SPEED_K * surplus / mass, MIN_DEG, MAX_DEG);
        int targetAngle = readTarget(head);
        float target = desiredOpen ? targetAngle : -targetAngle;
        network.addTransientDemand(key, SWING_DEMAND);

        startSwing(key, head, mech, speed, target, desiredOpen);
    }

    private void startSwing(CustomBlockRegistry.LocationKey key, Block head, Mechanism mech,
                            float speed, float target, boolean desiredOpen) {
        float limit = Math.abs(target);
        float sign = Math.signum(target);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            float angle = 0f;
            @Override public void run() {
                angle += speed;
                boolean done = angle >= limit;
                mech.rotate(done ? target : sign * angle);
                if (done) {
                    BukkitTask t = activeTasks.remove(key);
                    if (t != null) t.cancel();
                    mech.disassemble();
                    activeRotators.remove(key);
                    network.clearTransientDemand(key);
                    writeOpen(head, desiredOpen);
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
        Mechanism mech = activeRotators.remove(key);
        if (mech != null) mech.disassemble();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Interaction: cycle the target angle (90 → 180 → 270 → 90)
    // ──────────────────────────────────────────────────────────────────────

    private boolean onInteract(Block head, org.bukkit.event.player.PlayerInteractEvent event) {
        var player = event.getPlayer();
        if (RotationBlocks.isWrench(player.getInventory().getItemInMainHand())) {
            // Wrench → status readout (rotator is a consumer, not a wrench-toggled source)
            RotationNetwork.SpinDirection dir = network.getDirection(
                CustomBlockRegistry.LocationKey.of(head));
            player.sendActionBar(Component.text(
                "Rotator: " + (readOpen(head) ? "open" : "closed") + ", target " + readTarget(head)
                    + "° | " + (network.isPowered(CustomBlockRegistry.LocationKey.of(head))
                        ? "powered" + (dir != null ? " " + dir : "") : "unpowered"),
                NamedTextColor.GOLD));
            return true;
        }
        int next = switch (readTarget(head)) {
            case 90 -> 180;
            case 180 -> 270;
            default -> 90;
        };
        writeTarget(head, next);
        player.sendActionBar(Component.text("Rotator target: " + next + "°", NamedTextColor.LIGHT_PURPLE));
        head.getWorld().playSound(head.getLocation().add(0.5, 0.5, 0.5),
            Sound.BLOCK_COPPER_PLACE, 0.6f, 1.4f);
        return true;
    }

    private void feedbackUnderpowered(Block head) {
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

    private List<Block> floodFill(Block origin, Material target, int maxBlocks) {
        Set<Block> visited = new HashSet<>();
        Queue<Block> queue = new ArrayDeque<>();
        visited.add(origin); // exclude the hinge itself
        for (BlockFace face : CARDINAL_FACES) queue.add(origin.getRelative(face));
        List<Block> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block b = queue.poll();
            if (!visited.add(b) || b.getType() != target) continue;
            // Never pull a rotation/power block into the moving structure.
            if (registry.getTypeFromBlock(b) != null) continue;
            result.add(b);
            for (BlockFace face : CARDINAL_FACES) queue.add(b.getRelative(face));
        }
        return result;
    }

    private boolean readOpen(Block head) {
        if (!(head.getState() instanceof Skull skull)) return false;
        Byte v = skull.getPersistentDataContainer().get(OPEN_KEY, PersistentDataType.BYTE);
        return v != null && v != 0;
    }

    private void writeOpen(Block head, boolean open) {
        if (!(head.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(OPEN_KEY, PersistentDataType.BYTE, (byte) (open ? 1 : 0));
        skull.update();
    }

    private int readTarget(Block head) {
        if (!(head.getState() instanceof Skull skull)) return 90;
        Integer v = skull.getPersistentDataContainer().get(TARGET_KEY, PersistentDataType.INTEGER);
        return (v == null) ? 90 : v;
    }

    private void writeTarget(Block head, int target) {
        if (!(head.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(TARGET_KEY, PersistentDataType.INTEGER, target);
        skull.update();
    }
}
