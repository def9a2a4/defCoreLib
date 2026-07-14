package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Demo mechanism: a door controller head block that assembles its structure (authored glue, else the
 * single block it's placed on) into a mechanism and rotates 90° on redstone.
 */
final class DoorDemo {

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;
    private final Map<CustomBlockRegistry.LocationKey, Mechanism> activeDoors = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, BukkitTask> activeTasks = new HashMap<>();

    DoorDemo(JavaPlugin plugin, CustomBlockRegistry registry, MechanismRegistry mechRegistry,
             GlueManager glueManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.mechRegistry = mechRegistry;
        this.glueManager = glueManager;
    }

    void register() {
        // Visuals/states/redstone/transitions are declared in demo-blocks.yml; overlay the
        // door behavior here, mirroring RotationBlocks.overlayStandard.
        CustomHeadBlock base = registry.getType("demo:door_controller");
        if (base == null) {
            plugin.getLogger().warning("DoorDemo: block 'demo:door_controller' not found in registry "
                + "(check demo-blocks.yml) — door behavior not installed");
            return;
        }
        registry.register(base.toBuilder()
            .onStateChanged((block, oldState, newState) -> {
                if ("open".equals(newState)) openDoor(block);
                else if ("closed".equals(newState)) closeDoor(block);
            })
            .onBlockRemoved((block, state) -> cleanupDoor(block))
            .build());
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
            Anchor anchor = new BlockAnchor(head, () -> !activeDoors.containsKey(key));
            List<Block> resolved = glueManager.resolveStructure(anchor);
            boolean glued = resolved != null && !resolved.isEmpty();
            List<Block> planks;
            if (glued) {
                planks = resolved;
            } else {
                Block seed = attachmentBlock(head);   // no glue → swing the block the door is placed on
                if (seed.getType().isAir()) return;
                planks = List.of(seed);
            }
            planks = CasingExpansion.withDerived(planks, registry, glueManager.maxSize());
            mech = mechRegistry.assembleMechanism("demo:door", planks,
                head.getLocation().add(0.5, 0, 0.5), null);
            if (glued) mech.setOnDisassembled(p -> glueManager.setStructure(anchor, p));   // rebind only authored glue
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
            Anchor anchor = new BlockAnchor(head, () -> !activeDoors.containsKey(key));
            List<Block> resolved = glueManager.resolveStructure(anchor);
            boolean glued = resolved != null && !resolved.isEmpty();
            List<Block> planks;
            if (glued) {
                planks = resolved;
            } else {
                Block seed = attachmentBlock(head);   // no glue → swing the block the door is placed on
                if (seed.getType().isAir()) return;
                planks = List.of(seed);
            }
            planks = CasingExpansion.withDerived(planks, registry, glueManager.maxSize());
            mech = mechRegistry.assembleMechanism("demo:door", planks,
                head.getLocation().add(0.5, 0, 0.5), null);
            if (glued) mech.setOnDisassembled(p -> glueManager.setStructure(anchor, p));   // rebind only authored glue
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

    /** The block the door head is placed on: behind for a wall head, below for a floor head. Used as
     *  the default single-block structure when no glue is authored. */
    private static Block attachmentBlock(Block head) {
        if (head.getBlockData() instanceof org.bukkit.block.data.Directional d) {
            return head.getRelative(d.getFacing().getOppositeFace());
        }
        return head.getRelative(BlockFace.DOWN);
    }
}
