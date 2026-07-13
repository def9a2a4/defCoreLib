package anon.def9a2a4.mech;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import anon.def9a2a4.corelib.MechanismAssembleEvent;
import anon.def9a2a4.corelib.RotationNetworkPoweredEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Bridges core milestone signals to {@link MechAdvancements} grants. Craft/place are detected with
 * plain vanilla events (no core change); assemble/power come from the two events DefCoreLib fires.
 * Every handler early-returns cheaply on the common non-mech path.
 */
public final class MechAdvancementListeners implements Listener {

    private final CustomBlockRegistry registry;
    private final MechAdvancements advancements;

    public MechAdvancementListeners(CustomBlockRegistry registry, MechAdvancements advancements) {
        this.registry = registry;
        this.advancements = advancements;
    }

    /** Crafted a mech item → craft advancement (result carries a {@code mech:*} PDC id). */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        String id = CustomBlockRegistry.getItemTypeId(result);
        if (id == null || !id.startsWith("mech:")) return;
        if (event.getWhoClicked() instanceof Player player) {
            advancements.onCraft(player, id);
        }
    }

    /** Placed a windmill (any tier) → windmill advancement. MONITOR so core's HIGH handler has already
     *  written the block-type PDC; cheap PDC lookup + early return keeps the hot block-place path clean. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        CustomHeadBlock type = registry.getTypeFromBlock(event.getBlockPlaced());
        if (type == null) return;
        String id = type.fullId();
        if (id.startsWith("mech:") && id.contains("windmill")) {
            advancements.onWindmillPlace(event.getPlayer(), id);
        }
    }

    /** A mechanism assembled (door / drawbridge / minecart / large structure). */
    @EventHandler
    public void onAssemble(MechanismAssembleEvent event) {
        advancements.onAssemble(event.getPivot(), event.getType(),
                event.isVerticalAxis(), event.getBlockCount());
    }

    /** A rotation network became powered (rising edge). */
    @EventHandler
    public void onPower(RotationNetworkPoweredEvent event) {
        advancements.onPower(event.getLocation(), event.getSupply(),
                event.getDemand(), event.getMemberCount());
    }

    /** Re-evaluate aggregate capstones (master machinist / grand engineer) as mech advancements land. */
    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        advancements.onAdvancementDone(event.getPlayer(), event.getAdvancement().getKey().toString());
    }
}
