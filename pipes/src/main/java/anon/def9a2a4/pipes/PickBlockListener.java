package anon.def9a2a4.pipes;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import anon.def9a2a4.pipes.PipeManager.PipeData;
import io.papermc.paper.event.player.PlayerPickBlockEvent;

import java.util.Map;

/**
 * Handles creative middle-click (pick block) for pipes.
 * Registered conditionally — only on Paper versions that have PlayerPickBlockEvent (1.21.8+).
 */
public class PickBlockListener implements Listener {

    private final PipesPlugin plugin;
    private final Map<World, PipeManager> pipeManagers;

    public PickBlockListener(PipesPlugin plugin, Map<World, PipeManager> pipeManagers) {
        this.plugin = plugin;
        this.pipeManagers = pipeManagers;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickBlock(PlayerPickBlockEvent event) {
        if (event.isCancelled()) return;

        Block block = event.getBlock();
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;

        PipeManager manager = pipeManagers.get(block.getWorld());
        if (manager == null) return;

        PipeData pipeData = manager.getPipeData(block.getLocation());
        if (pipeData == null) return;

        // Only handle in creative — let vanilla/HeadSmith handle survival
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) return;

        ItemStack pipeItem = plugin.getPipeItem(pipeData.variant());
        if (pipeItem == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        player.getInventory().setItem(event.getTargetSlot(), pipeItem);
        player.getInventory().setHeldItemSlot(event.getTargetSlot());
    }
}
