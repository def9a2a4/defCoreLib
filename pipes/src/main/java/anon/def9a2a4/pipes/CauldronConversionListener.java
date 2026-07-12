package anon.def9a2a4.pipes;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles pipe conversions when items are thrown into water cauldrons.
 */
public class CauldronConversionListener implements Listener {

    private final PipesPlugin plugin;
    private final Map<String, String> conversions = new HashMap<>();

    public CauldronConversionListener(PipesPlugin plugin) {
        this.plugin = plugin;
        loadConversions();
    }

    public void loadConversions() {
        conversions.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("cauldron-conversions");
        if (section == null) return;

        for (String fromVariant : section.getKeys(false)) {
            String toVariant = section.getString(fromVariant);
            if (toVariant != null && !toVariant.isEmpty()) {
                conversions.put(fromVariant, toVariant);
            }
        }

        if (!conversions.isEmpty()) {
            plugin.getLogger().info("Loaded " + conversions.size() + " cauldron conversion(s)");
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        if (conversions.isEmpty()) return;

        Item itemEntity = event.getEntity();
        ItemStack item = itemEntity.getItemStack();

        PipeVariant variant = plugin.getVariant(item);
        if (variant == null) return;

        String toVariantId = conversions.get(variant.getId());
        if (toVariantId == null) return;

        // Schedule check for next tick to let item settle
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!itemEntity.isValid() || itemEntity.isDead()) {
                    cancel();
                    return;
                }

                // Check for up to 20 ticks (1 second) for item to land in cauldron
                if (ticks++ > 20) {
                    cancel();
                    return;
                }

                Block block = itemEntity.getLocation().getBlock();
                if (block.getType() == Material.WATER_CAULDRON) {
                    // Check water level
                    if (block.getBlockData() instanceof Levelled levelled) {
                        if (levelled.getLevel() > 0) {
                            performConversion(itemEntity, item, toVariantId, block, levelled);
                            cancel();
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 2L);
    }

    private void performConversion(Item itemEntity, ItemStack originalItem, String toVariantId, Block cauldron, Levelled levelled) {
        PipeVariant toVariant = plugin.getVariantRegistry().getVariant(toVariantId);
        if (toVariant == null) {
            plugin.getLogger().warning("Cauldron conversion target variant not found: " + toVariantId);
            return;
        }

        int amount = originalItem.getAmount();
        ItemStack convertedItem = plugin.getPipeItem(toVariant);
        if (convertedItem == null) return;

        convertedItem.setAmount(amount);

        // Remove original item
        itemEntity.remove();

        // Spawn converted item
        itemEntity.getWorld().dropItem(itemEntity.getLocation(), convertedItem);

        // Decrease water level
        int newLevel = levelled.getLevel() - 1;
        if (newLevel <= 0) {
            cauldron.setType(Material.CAULDRON);
        } else {
            levelled.setLevel(newLevel);
            cauldron.setBlockData(levelled);
        }

        // Effects
        cauldron.getWorld().spawnParticle(
                Particle.SPLASH,
                cauldron.getLocation().add(0.5, 0.9, 0.5),
                10, 0.2, 0.1, 0.2, 0.05
        );
        cauldron.getWorld().playSound(
                cauldron.getLocation(),
                Sound.ITEM_BUCKET_EMPTY,
                0.5f, 1.2f
        );
    }
}
