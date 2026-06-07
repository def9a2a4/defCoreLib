package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Demo mechanism: a door controller head block that detects connected oak planks
 * via BFS flood fill, assembles them into a mechanism, and rotates 90° on redstone.
 */
final class DoorDemo {

    private static final BlockFace[] CARDINAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
        BlockFace.UP, BlockFace.DOWN
    };

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final MechanismRegistry mechRegistry;
    private final Map<CustomBlockRegistry.LocationKey, Mechanism> activeDoors = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, BukkitTask> activeTasks = new HashMap<>();

    DoorDemo(JavaPlugin plugin, CustomBlockRegistry registry, MechanismRegistry mechRegistry) {
        this.plugin = plugin;
        this.registry = registry;
        this.mechRegistry = mechRegistry;
    }

    void register() {
        registry.register(buildDoorController());
    }

    private CustomHeadBlock buildDoorController() {
        return CustomHeadBlock.builder("demo", "door_controller")
            .name(net.kyori.adventure.text.Component.text("Door Controller"))
            .texture("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWNjNzg5ZjIzMDc5NGY5MGUzM2M0ZjlhZDAwNjk0YmMyYTJmZjVlOGI5YjM3NWRjMzUzMjQwMWIyODFmM2U1OCJ9fX0=")
            .drops(CustomHeadBlock.DropRule.self())
            .state("closed")
            .state("open")
            .defaultState("closed")
            .redstone(CustomHeadBlock.Sensitivity.BINARY, CustomHeadBlock.PowerReader.EXTENDED)
            .transition(
                new CustomHeadBlock.Trigger.RedstonePower(CustomHeadBlock.PowerRange.POWERED),
                "closed", "open")
            .transition(
                new CustomHeadBlock.Trigger.RedstonePower(CustomHeadBlock.PowerRange.ZERO),
                "open", "closed")
            .onStateChanged((block, oldState, newState) -> {
                if ("open".equals(newState)) openDoor(block);
                else if ("closed".equals(newState)) closeDoor(block);
            })
            .onBlockRemoved((block, state) -> cleanupDoor(block))
            .build();
    }

    // ──────────────────────────────────────────────────────────────────────

    private void openDoor(Block head) {
        var key = CustomBlockRegistry.LocationKey.of(head);
        cancelExistingTask(key);

        // Reuse existing mechanism if planks are already assembled
        // (happens when close animation is interrupted mid-rotation)
        Mechanism mech = activeDoors.get(key);
        boolean freshAssembly = (mech == null);
        if (freshAssembly) {
            List<Block> planks = floodFill(head, Material.OAK_PLANKS, 256);
            if (planks.isEmpty()) return;
            mech = mechRegistry.assembleMechanism("demo:door", planks,
                head.getLocation().add(0.5, 0, 0.5), null);
            activeDoors.put(key, mech);
        }

        float startYaw = mech.getCurrentYaw();
        float targetYaw = 90f;
        int duration = 20;
        int timerDelay = freshAssembly ? 2 : 0; // delay 2 waits for passenger mount
        final Mechanism m = mech;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int tick = 0;
            @Override public void run() {
                tick++;
                float yaw = (tick >= duration) ? targetYaw
                    : startYaw + (targetYaw - startYaw) * ((float) tick / duration);
                m.rotate(yaw);
                if (tick >= duration) {
                    BukkitTask t = activeTasks.remove(key);
                    if (t != null) t.cancel();
                    m.disassemble();
                    activeDoors.remove(key);
                }
            }
        }, timerDelay, 1);
        activeTasks.put(key, task);
    }

    private void closeDoor(Block head) {
        var key = CustomBlockRegistry.LocationKey.of(head);
        cancelExistingTask(key);

        Mechanism mech = activeDoors.get(key);
        float targetYaw;
        int timerDelay;
        if (mech != null) {
            // Mid-animation interrupt: rotate back to 0° (original assembly position)
            targetYaw = 0f;
            timerDelay = 0; // already mounted
        } else {
            // Fully opened + disassembled: re-assemble rotated planks, rotate to -90°
            List<Block> planks = floodFill(head, Material.OAK_PLANKS, 256);
            if (planks.isEmpty()) return;
            mech = mechRegistry.assembleMechanism("demo:door", planks,
                head.getLocation().add(0.5, 0, 0.5), null);
            activeDoors.put(key, mech);
            targetYaw = -90f;
            timerDelay = 2; // wait for passenger mount
        }

        float startYaw = mech.getCurrentYaw();
        int duration = 20;
        final Mechanism m = mech;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int tick = 0;
            @Override public void run() {
                tick++;
                float yaw = (tick >= duration) ? targetYaw
                    : startYaw + (targetYaw - startYaw) * ((float) tick / duration);
                m.rotate(yaw);
                if (tick >= duration) {
                    BukkitTask t = activeTasks.remove(key);
                    if (t != null) t.cancel();
                    m.disassemble();
                    activeDoors.remove(key);
                }
            }
        }, timerDelay, 1);
        activeTasks.put(key, task);
    }

    private void cleanupDoor(Block head) {
        var key = CustomBlockRegistry.LocationKey.of(head);
        cancelExistingTask(key);
        Mechanism mech = activeDoors.remove(key);
        if (mech != null) mech.disassemble();
    }

    private void cancelExistingTask(CustomBlockRegistry.LocationKey key) {
        BukkitTask task = activeTasks.remove(key);
        if (task != null) task.cancel();
    }

    // ──────────────────────────────────────────────────────────────────────

    private List<Block> floodFill(Block origin, Material target, int maxBlocks) {
        Set<Block> visited = new HashSet<>();
        Queue<Block> queue = new ArrayDeque<>();
        visited.add(origin); // exclude head itself
        for (BlockFace face : CARDINAL_FACES) queue.add(origin.getRelative(face));
        List<Block> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block b = queue.poll();
            if (!visited.add(b) || b.getType() != target) continue;
            result.add(b);
            for (BlockFace face : CARDINAL_FACES) queue.add(b.getRelative(face));
        }
        return result;
    }
}
