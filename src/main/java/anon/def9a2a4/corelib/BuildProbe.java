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
public final class BuildProbe {

    private static boolean probing;

    private BuildProbe() {}

    public static boolean mayBuild(Player player, Block block, ItemStack held) {
        BlockPlaceEvent probe = new BlockPlaceEvent(
            block, block.getState(), block, held, player, true, EquipmentSlot.HAND);
        probing = true;
        try {
            Bukkit.getPluginManager().callEvent(probe);
        } finally {
            probing = false;
        }
        return !probe.isCancelled() && probe.canBuild();
    }

    /** True while a probe BlockPlaceEvent is being dispatched — nothing was actually placed. */
    public static boolean isProbing() { return probing; }
}
