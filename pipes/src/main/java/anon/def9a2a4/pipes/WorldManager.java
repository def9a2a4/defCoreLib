package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.WorldFilter;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.Map;

public class WorldManager implements Listener {

    private final PipesPlugin plugin;
    private final Map<World, PipeManager> managers;

    public WorldManager(PipesPlugin plugin, Map<World, PipeManager> managers) {
        this.plugin = plugin;
        this.managers = managers;
    }

    public void initWorld(World world) {
        WorldFilter filter = plugin.getPipeConfig().getWorldFilter();
        if (filter != null && !filter.isEnabled(world.getName())) {
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
