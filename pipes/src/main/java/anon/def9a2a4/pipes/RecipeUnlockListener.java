package anon.def9a2a4.pipes;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Handles recipe unlocking based on player advancements.
 */
public class RecipeUnlockListener implements Listener {

    private final PipesPlugin plugin;
    private final RecipeManager recipeManager;
    private final NamespacedKey unlockAdvancementKey;

    public RecipeUnlockListener(PipesPlugin plugin, RecipeManager recipeManager) {
        this.plugin = plugin;
        this.recipeManager = recipeManager;
        this.unlockAdvancementKey = parseUnlockAdvancement();
    }

    private NamespacedKey parseUnlockAdvancement() {
        String advancementStr = plugin.getPipeConfig().getUnlockAdvancement();

        if (advancementStr == null || advancementStr.isEmpty() || advancementStr.equalsIgnoreCase("none")) {
            return null; // Unlock feature disabled
        }

        try {
            // Parse "minecraft:story/smelt_iron" format
            if (advancementStr.contains(":")) {
                String[] parts = advancementStr.split(":", 2);
                return new NamespacedKey(parts[0], parts[1]);
            } else {
                // Assume minecraft namespace if not specified
                return new NamespacedKey("minecraft", advancementStr);
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid unlock-advancement key: " + advancementStr + ". Recipe unlock disabled.");
            return null;
        }
    }

    /**
     * Check if recipe unlock feature is enabled.
     */
    public boolean isUnlockEnabled() {
        return unlockAdvancementKey != null;
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (unlockAdvancementKey == null) return;

        NamespacedKey advKey = event.getAdvancement().getKey();
        if (advKey.equals(unlockAdvancementKey)) {
            Player player = event.getPlayer();
            recipeManager.discoverAllRecipes(player);

            // Send unlock message if configured
            if (plugin.getPipeConfig().isShowUnlockMessage()) {
                String message = plugin.getPipeConfig().getUnlockMessage();
                player.sendMessage(MiniMessage.miniMessage().deserialize(message));
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // If unlock feature is disabled, ensure all recipes are discovered
        if (unlockAdvancementKey == null) {
            recipeManager.discoverAllRecipes(player);
            return;
        }

        // Check if player has already completed the unlock advancement
        Advancement advancement = Bukkit.getAdvancement(unlockAdvancementKey);
        if (advancement != null && player.getAdvancementProgress(advancement).isDone()) {
            recipeManager.discoverAllRecipes(player);
        } else {
            recipeManager.undiscoverAllRecipes(player);
        }
    }

    /**
     * Sync recipe discovery state for all online players.
     * Called after config reload.
     */
    public void syncAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (unlockAdvancementKey == null) {
                recipeManager.discoverAllRecipes(player);
            } else {
                Advancement advancement = Bukkit.getAdvancement(unlockAdvancementKey);
                if (advancement != null && player.getAdvancementProgress(advancement).isDone()) {
                    recipeManager.discoverAllRecipes(player);
                } else {
                    recipeManager.undiscoverAllRecipes(player);
                }
            }
        }
    }
}
