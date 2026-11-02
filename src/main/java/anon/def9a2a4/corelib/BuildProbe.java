package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Whether protection plugins (WorldGuard, GriefPrevention, …) let a player build at a block, for actions
 * that change the world without placing or breaking anything they could see (display-entity banners,
 * glue): fire a probe BlockPlaceEvent that changes nothing and see if anyone cancels it. A place event,
 * not a break event — a probe break would run our own break teardown.
 *
 * <p>Our own place listeners that would act on the probe (as if a block had really been placed) must
 * skip it via {@link #isProbing()}.
 */
public final class BuildProbe implements org.bukkit.event.Listener {

    private static boolean probing;
    /** The verdict as it stood once every protection plugin had its say (HIGHEST), before the probe is cancelled. */
    private static @org.jspecify.annotations.Nullable Boolean verdict;

    private BuildProbe() {}

    /** Registered once at enable: see {@link #onProbe}. */
    static void register(org.bukkit.plugin.Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new BuildProbe(), plugin);
    }

    public static boolean mayBuild(Player player, Block block, ItemStack held) {
        BlockPlaceEvent probe = new BlockPlaceEvent(
            block, block.getState(), block, held, player, true, EquipmentSlot.HAND);
        probing = true;
        verdict = null;
        try {
            Bukkit.getPluginManager().callEvent(probe);
        } finally {
            probing = false;
        }
        Boolean v = verdict;
        verdict = null;
        return v != null ? v : !probe.isCancelled() && probe.canBuild();
    }

    /**
     * Read the verdict, then cancel the probe so MONITOR listeners skip it: block loggers (CoreProtect, Prism)
     * recorded a placement for every probed cell, which a rollback would then undo on the owner's own blocks, and
     * claim plugins could react to a "placed" chest.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onProbe(BlockPlaceEvent event) {
        if (!probing) return;
        verdict = !event.isCancelled() && event.canBuild();
        event.setCancelled(true);
    }

    /** True while a probe BlockPlaceEvent is being dispatched — nothing was actually placed. */
    public static boolean isProbing() { return probing; }
}
