package anon.def9a2a4.corelib.container;

import anon.def9a2a4.corelib.CoreLibPlugin;
import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class RotationMachineAdapter implements ContainerAdapter {

    private static final NamespacedKey FACING_KEY = new NamespacedKey("mech", "drill_facing");

    private CustomHeadBlock getStorageType(Block block) {
        CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null || type.storage() == null) return null;
        return type;
    }

    @Override
    public boolean canReceive(Block block) {
        return getStorageType(block) != null;
    }

    @Override
    public boolean canReceiveFrom(Block block, BlockFace approachFace) {
        if (getStorageType(block) == null) return false;
        if (!(block.getState() instanceof Skull skull)) return false;
        String typeId = skull.getPersistentDataContainer().get(
                new NamespacedKey("corelib", "block_type"), PersistentDataType.STRING);
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
