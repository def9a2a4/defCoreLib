package anon.def9a2a4.pipes.adapter;

import anon.def9a2a4.corelib.CoreLibPlugin;
import anon.def9a2a4.corelib.CustomBlockRegistry;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

public class RotationMachineAdapter implements ContainerAdapter {

    private static final Set<String> MACHINE_IDS = Set.of(
        "mech:millstone", "mech:press", "mech:placer", "mech:engine"
    );

    private static final NamespacedKey BLOCK_TYPE_KEY = new NamespacedKey("corelib", "block_type");
    private static final NamespacedKey FACING_KEY = new NamespacedKey("mech", "drill_facing");

    private String getBlockTypeId(Block block) {
        if (!(block.getState() instanceof Skull skull)) return null;
        return skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
    }

    @Override
    public boolean canReceive(Block block) {
        String typeId = getBlockTypeId(block);
        return typeId != null && MACHINE_IDS.contains(typeId);
    }

    @Override
    public boolean canReceiveFrom(Block block, BlockFace approachFace) {
        if (!(block.getState() instanceof Skull skull)) return false;
        String typeId = skull.getPersistentDataContainer().get(BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null || !MACHINE_IDS.contains(typeId)) return false;
        if ("mech:engine".equals(typeId)) return true;
        String facingStr = skull.getPersistentDataContainer().get(FACING_KEY, PersistentDataType.STRING);
        if (facingStr == null) return false;
        try {
            BlockFace machineFacing = BlockFace.valueOf(facingStr);
            return approachFace == machineFacing.getOppositeFace();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public ItemStack insert(Block block, ItemStack item) {
        Inventory inv = CoreLibPlugin.getInstance().getRegistry().getOrCreateStorage(block);
        if (inv == null) return item;
        var leftover = inv.addItem(item.clone());
        return leftover.isEmpty() ? null : leftover.values().iterator().next();
    }

    @Override
    public ItemStack peekExtract(Block block, int maxAmount) {
        return null;
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
    }

    @Override
    public boolean hasItems(Block block) {
        return false;
    }
}
