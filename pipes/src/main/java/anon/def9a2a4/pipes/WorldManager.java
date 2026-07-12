package anon.def9a2a4.pipes;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.Map;

/**
 * Manages per-world PipeManager lifecycle.
 * Creates a PipeManager when a world loads (if enabled), shuts it down on unload.
 */
public class WorldManager implements Listener {

    private final PipesPlugin plugin;
    private final Map<World, PipeManager> managers;

    public WorldManager(PipesPlugin plugin, Map<World, PipeManager> managers) {
        this.plugin = plugin;
        this.managers = managers;
    }

    /**
     * Initialize a PipeManager for the given world if pipes are enabled there.
     * Scans for existing pipes and starts transfer tasks.
     */
    public void initWorld(World world) {
        if (!plugin.getPipeConfig().isWorldEnabled(world.getName())) {
            return;
        }
        if (managers.containsKey(world)) {
            return;
        }

        PipeManager manager = new PipeManager(plugin, world);
        managers.put(world, manager);
        manager.scanForExistingPipes();
        manager.startTasks();
    }

    /**
     * Shut down and remove the PipeManager for the given world.
     */
    public void removeWorld(World world) {
        PipeManager manager = managers.remove(world);
        if (manager != null) {
            manager.shutdown();
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        initWorld(event.getWorld());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        removeWorld(event.getWorld());
    }
}
