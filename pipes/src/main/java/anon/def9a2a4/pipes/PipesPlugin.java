package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import anon.def9a2a4.pipes.config.DisplayConfig;
import anon.def9a2a4.pipes.config.PipeConfig;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Stream;

public class PipesPlugin extends JavaPlugin {

    private static final UUID PIPE_PROFILE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Maps keyed by variant ID
    private final Map<String, ItemStack> pipeItems = new HashMap<>();
    private final Map<String, Map<BlockFace, ItemStack>> headItems = new HashMap<>();
    private final Map<String, Map<BlockFace, ItemStack>> displayItems = new HashMap<>();
    private final Map<String, Map<BlockFace, ItemStack>> directionalDisplayItems = new HashMap<>();

    // Per-world PipeManagers (WeakHashMap allows GC of unloaded worlds)
    private final WeakHashMap<World, PipeManager> pipeManagers = new WeakHashMap<>();

    private FileConfiguration displayConfigRaw;
    private PipeConfig pipeConfig;
    private DisplayConfig displayConfig;
    private VariantRegistry variantRegistry;
    private WorldManager worldManager;
    private RecipeManager recipeManager;
    private RecipeUnlockListener recipeUnlockListener;
    private CauldronConversionListener cauldronConversionListener;
    private ConversionRecipeCraftListener conversionRecipeCraftListener;

    @Override
    public void onEnable() {
        int pluginId = 28844;
        new Metrics(this, pluginId);

        variantRegistry = new VariantRegistry(this);
        loadConfigs();

        if (!variantRegistry.hasVariants()) {
            getLogger().severe("No pipe variants configured! Plugin will not function correctly.");
        }

        loadItems();

        // Register pipe block types with CoreLib
        var coreLibRegistry = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry();
        PipeBlockRegistrar.register(coreLibRegistry, this);

        recipeManager = new RecipeManager(this);
        recipeManager.registerRecipes();

        worldManager = new WorldManager(this, pipeManagers);
        recipeUnlockListener = new RecipeUnlockListener(this, recipeManager);
        cauldronConversionListener = new CauldronConversionListener(this);
        conversionRecipeCraftListener = new ConversionRecipeCraftListener(this, recipeManager);
        getServer().getPluginManager().registerEvents(new PipeListener(this, pipeManagers), this);
        try {
            Class.forName("io.papermc.paper.event.player.PlayerPickBlockEvent");
            getServer().getPluginManager().registerEvents(new PickBlockListener(this, pipeManagers), this);
        } catch (ClassNotFoundException ignored) {}
        try {
            Class.forName("anon.def9a2a4.corelib.MachineEjectEvent");
            getServer().getPluginManager().registerEvents(new MachineEjectListener(pipeManagers), this);
        } catch (ClassNotFoundException ignored) {}
        getServer().getPluginManager().registerEvents(worldManager, this);
        getServer().getPluginManager().registerEvents(recipeUnlockListener, this);
        getServer().getPluginManager().registerEvents(cauldronConversionListener, this);
        getServer().getPluginManager().registerEvents(conversionRecipeCraftListener, this);

        // Initialize PipeManagers for all already-loaded worlds
        for (World world : Bukkit.getWorlds()) {
            worldManager.initWorld(world);
        }

        getLogger().info("Pipes enabled!");
    }

    @Override
    public void onDisable() {
        for (PipeManager manager : pipeManagers.values()) {
            manager.shutdown();
        }
        pipeManagers.clear();
        getLogger().info("Pipes disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("pipes")) {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelp(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("pipes.reload")) {
                    sender.sendMessage(Component.text("You don't have permission to reload the config.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                // Unregister old recipes
                recipeManager.unregisterRecipes();

                loadConfigs();
                loadItems();
                recipeManager.registerRecipes();
                for (PipeManager manager : new ArrayList<>(pipeManagers.values())) {
                    manager.reloadVariants(variantRegistry);
                    manager.refreshAllDisplays();
                    manager.restartTasks();
                }

                // Re-evaluate world filters: init newly-enabled worlds, remove disabled ones
                for (World w : Bukkit.getWorlds()) {
                    if (pipeConfig.isWorldEnabled(w.getName()) && !pipeManagers.containsKey(w)) {
                        worldManager.initWorld(w);
                    } else if (!pipeConfig.isWorldEnabled(w.getName()) && pipeManagers.containsKey(w)) {
                        worldManager.removeWorld(w);
                    }
                }

                // Re-create unlock listener with new config and sync online players
                HandlerList.unregisterAll(recipeUnlockListener);
                recipeUnlockListener = new RecipeUnlockListener(this, recipeManager);
                getServer().getPluginManager().registerEvents(recipeUnlockListener, this);
                recipeUnlockListener.syncAllOnlinePlayers();

                // Reload cauldron conversions
                cauldronConversionListener.loadConversions();

                sender.sendMessage(Component.text("Pipes config reloaded!")
                        .color(NamedTextColor.GREEN));
                return true;
            }

            if (args[0].equalsIgnoreCase("recipes")) {
                if (!sender.hasPermission("pipes.recipes")) {
                    sender.sendMessage(Component.text("You don't have permission to unlock recipes.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("This command can only be used by players.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                recipeManager.discoverAllRecipes(player);
                sender.sendMessage(Component.text("Unlocked all Pipes recipes!")
                        .color(NamedTextColor.GREEN));
                return true;
            }

            if (args[0].equalsIgnoreCase("give")) {
                if (!sender.hasPermission("pipes.give")) {
                    sender.sendMessage(Component.text("You don't have permission to give items.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("This command can only be used by players.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /pipes give <item> [amount]")
                            .color(NamedTextColor.YELLOW));
                    return true;
                }

                String itemName = args[1];
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Integer.parseInt(args[2]);
                        amount = Math.max(1, Math.min(64, amount));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("Invalid amount: " + args[2])
                                .color(NamedTextColor.RED));
                        return true;
                    }
                }

                // Handle "ALL" special case
                if (itemName.equalsIgnoreCase("ALL")) {
                    int given = 0;
                    for (PipeVariant variant : variantRegistry.getAllVariants()) {
                        ItemStack item = getPipeItem(variant);
                        if (item != null) {
                            player.getInventory().addItem(item);
                            given++;
                        }
                    }
                    sender.sendMessage(Component.text("Gave " + given + " pipe item(s)!")
                            .color(NamedTextColor.GREEN));
                    return true;
                }

                // Normal item lookup
                PipeVariant variant = variantRegistry.getVariant(itemName);
                if (variant == null) {
                    sender.sendMessage(Component.text("Unknown item: " + itemName)
                            .color(NamedTextColor.RED));
                    return true;
                }

                ItemStack item = getPipeItem(variant);
                if (item == null) {
                    sender.sendMessage(Component.text("Failed to create item for variant: " + itemName)
                            .color(NamedTextColor.RED));
                    return true;
                }
                item.setAmount(amount);
                player.getInventory().addItem(item);
                sender.sendMessage(Component.text("Gave " + amount + "x " + itemName + "!")
                        .color(NamedTextColor.GREEN));
                return true;
            }

            if (args[0].equalsIgnoreCase("cleanup")) {
                if (!sender.hasPermission("pipes.cleanup")) {
                    sender.sendMessage(Component.text("You don't have permission to cleanup orphaned displays.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                int totalRemoved = 0;
                for (PipeManager manager : pipeManagers.values()) {
                    int removed = manager.cleanupOrphanedDisplays();
                    if (removed > 0) {
                        sender.sendMessage(Component.text("Removed " + removed + " orphaned display(s) in " + manager.getWorld().getName())
                                .color(NamedTextColor.GRAY));
                    }
                    totalRemoved += removed;
                }

                if (totalRemoved > 0) {
                    sender.sendMessage(Component.text("Cleanup complete! Removed " + totalRemoved + " orphaned display(s) total.")
                            .color(NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("No orphaned displays found.")
                            .color(NamedTextColor.GREEN));
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("info")) {
                if (!sender.hasPermission("pipes.info")) {
                    sender.sendMessage(Component.text("You don't have permission to view pipe info.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                sender.sendMessage(Component.text("=== Pipes Info ===")
                        .color(NamedTextColor.GOLD));

                List<PipeManager> managersToQuery = getQueryableManagers(sender);

                // Pipe counts by variant per world
                for (PipeManager manager : managersToQuery) {
                    Map<String, Integer> counts = manager.getPipeCountsByVariant();
                    int totalPipes = manager.getTotalPipeCount();

                    sender.sendMessage(Component.text(manager.getWorld().getName() + ":")
                            .color(NamedTextColor.GOLD));

                    if (counts.isEmpty()) {
                        sender.sendMessage(Component.text("  No pipes registered.")
                                .color(NamedTextColor.GRAY));
                    } else {
                        sender.sendMessage(Component.text("  Registered pipes: " + totalPipes)
                                .color(NamedTextColor.WHITE));
                        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                            sender.sendMessage(Component.text("    " + entry.getKey() + ": " + entry.getValue())
                                    .color(NamedTextColor.GRAY));
                        }
                    }
                }

                // Orphaned display counts
                int totalOrphaned = 0;
                for (PipeManager manager : managersToQuery) {
                    int orphaned = manager.countOrphanedDisplays();
                    if (orphaned > 0) {
                        sender.sendMessage(Component.text("Orphaned displays in " + manager.getWorld().getName() + ": " + orphaned)
                                .color(NamedTextColor.YELLOW));
                    }
                    totalOrphaned += orphaned;
                }
                if (totalOrphaned == 0) {
                    sender.sendMessage(Component.text("No orphaned displays found.")
                            .color(NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Total orphaned displays: " + totalOrphaned)
                            .color(NamedTextColor.YELLOW));
                }

                return true;
            }

            if (args[0].equalsIgnoreCase("delete_all")) {
                if (!sender.hasPermission("pipes.delete_all")) {
                    sender.sendMessage(Component.text("You don't have permission to delete all pipes.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("This command can only be used by players.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                World world = player.getWorld();
                PipeManager manager = pipeManagers.get(world);
                if (manager == null) {
                    sender.sendMessage(Component.text("Pipes are not enabled in this world.")
                            .color(NamedTextColor.RED));
                    return true;
                }

                int totalDeleted = manager.deleteAllPipes();
                if (totalDeleted > 0) {
                    sender.sendMessage(Component.text("Deleted " + totalDeleted + " pipe(s) in " + world.getName() + ".")
                            .color(NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("No pipes to delete.")
                            .color(NamedTextColor.GREEN));
                }
                return true;
            }

            // Unknown subcommand - show help
            sendHelp(sender);
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("pipes")) {
            return List.of();
        }

        if (args.length == 1) {
            return Stream.of("help", "reload", "give", "recipes", "cleanup", "info", "delete_all")
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            List<String> completions = new ArrayList<>();
            completions.add("ALL");
            for (PipeVariant variant : variantRegistry.getAllVariants()) {
                completions.add(variant.getId());
            }
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(prefix))
                    .toList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return List.of("1", "16", "32", "64").stream()
                    .filter(s -> s.startsWith(args[2]))
                    .toList();
        }

        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== Pipes Commands ===").color(NamedTextColor.GOLD));

        sender.sendMessage(Component.text("/pipes help").color(NamedTextColor.WHITE)
                .append(Component.text(" - Show this help message").color(NamedTextColor.GRAY)));

        if (sender.hasPermission("pipes.reload")) {
            sender.sendMessage(Component.text("/pipes reload").color(NamedTextColor.WHITE)
                    .append(Component.text(" - Reload configuration").color(NamedTextColor.GRAY)));
        }
        if (sender.hasPermission("pipes.give")) {
            sender.sendMessage(Component.text("/pipes give <item> [amount]").color(NamedTextColor.WHITE)
                    .append(Component.text(" - Give pipe items").color(NamedTextColor.GRAY)));
        }
        if (sender.hasPermission("pipes.recipes")) {
            sender.sendMessage(Component.text("/pipes recipes").color(NamedTextColor.WHITE)
                    .append(Component.text(" - Unlock all pipe recipes").color(NamedTextColor.GRAY)));
        }
        if (sender.hasPermission("pipes.cleanup")) {
            sender.sendMessage(Component.text("/pipes cleanup").color(NamedTextColor.WHITE)
                    .append(Component.text(" - Remove orphaned display entities").color(NamedTextColor.GRAY)));
        }
        if (sender.hasPermission("pipes.info")) {
            sender.sendMessage(Component.text("/pipes info").color(NamedTextColor.WHITE)
                    .append(Component.text(" - View pipe statistics").color(NamedTextColor.GRAY)));
        }
        if (sender.hasPermission("pipes.delete_all")) {
            sender.sendMessage(Component.text("/pipes delete_all").color(NamedTextColor.WHITE)
                    .append(Component.text(" - Delete all pipes (dangerous!)").color(NamedTextColor.GRAY)));
        }
    }

    private void loadConfigs() {
        // Load user-editable config.yml
        saveDefaultConfig();
        reloadConfig();
        pipeConfig = new PipeConfig(getConfig());

        // Load display.yml first - check for external override (for development)
        File externalDisplayConfig = new File(getDataFolder(), "display.yml");
        if (externalDisplayConfig.exists()) {
            getLogger().info("Loading display.yml from plugin folder (development override)");
            displayConfigRaw = YamlConfiguration.loadConfiguration(externalDisplayConfig);
            displayConfig = new DisplayConfig(displayConfigRaw);
        } else {
            // Load internal display.yml from JAR
            try (InputStream stream = getResource("display.yml")) {
                if (stream == null) {
                    getLogger().severe("Could not find display.yml in JAR!");
                    return;
                }
                displayConfigRaw = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                displayConfig = new DisplayConfig(displayConfigRaw);
            } catch (Exception e) {
                getLogger().severe("Failed to load display.yml: " + e.getMessage());
            }
        }

        // Load variants from config (needs displayConfig for texture-set validation)
        ConfigurationSection variantsSection = getConfig().getConfigurationSection("variants");
        variantRegistry.loadFromConfig(variantsSection, displayConfig);
    }

    public PipeConfig getPipeConfig() {
        return pipeConfig;
    }

    public DisplayConfig getDisplayConfig() {
        return displayConfig;
    }

    public VariantRegistry getVariantRegistry() {
        return variantRegistry;
    }

    /**
     * Get the PipeManager for a world, or null if pipes are not active in that world.
     */
    public PipeManager getPipeManager(World world) {
        return pipeManagers.get(world);
    }

    /**
     * Get pipe managers scoped to the sender: player's world only, or all worlds for console.
     */
    private List<PipeManager> getQueryableManagers(CommandSender sender) {
        if (sender instanceof Player player) {
            PipeManager mgr = pipeManagers.get(player.getWorld());
            if (mgr != null) return List.of(mgr);
            return List.of();
        }
        return new ArrayList<>(pipeManagers.values());
    }

    private void loadItems() {
        pipeItems.clear();
        headItems.clear();
        displayItems.clear();
        directionalDisplayItems.clear();

        for (PipeVariant variant : variantRegistry.getAllVariants()) {
            loadItemsForVariant(variant);
        }
    }

    private void loadItemsForVariant(PipeVariant variant) {
        String variantId = variant.getId();
        DisplayConfig.TextureSet textures = displayConfig.getTextureSet(variant.getTextureSetId());
        if (textures == null) {
            getLogger().warning("Missing texture set '" + variant.getTextureSetId() + "' for variant " + variantId);
            return;
        }

        // Inventory item
        pipeItems.put(variantId, createPipeItem(variant, textures.item()));

        // Head block textures
        Map<BlockFace, ItemStack> heads = new HashMap<>();
        heads.put(BlockFace.NORTH, createPipeItem(variant, textures.getHeadTexture(BlockFace.NORTH)));
        if (variant.getBehaviorType() == BehaviorType.REGULAR) {
            heads.put(BlockFace.UP, createPipeItem(variant, textures.getHeadTexture(BlockFace.UP)));
        }
        heads.put(BlockFace.DOWN, createPipeItem(variant, textures.getHeadTexture(BlockFace.DOWN)));
        headItems.put(variantId, heads);

        // Item display textures
        Map<BlockFace, ItemStack> displays = new HashMap<>();
        if (variant.getBehaviorType() == BehaviorType.CORNER) {
            displays.put(BlockFace.NORTH, createPipeItem(variant, textures.getItemDisplayTexture(BlockFace.NORTH)));
            // Load directional display items for corner pipes
            Map<BlockFace, ItemStack> directionalDisplays = new HashMap<>();
            directionalDisplays.put(BlockFace.NORTH, createPipeItem(variant, textures.getDirectionalDisplayTexture(BlockFace.NORTH)));
            directionalDisplays.put(BlockFace.DOWN, createPipeItem(variant, textures.getDirectionalDisplayTexture(BlockFace.DOWN)));
            directionalDisplayItems.put(variantId, directionalDisplays);
        } else {
            displays.put(BlockFace.NORTH, createPipeItem(variant, textures.getItemDisplayTexture(BlockFace.NORTH)));
            displays.put(BlockFace.UP, createPipeItem(variant, textures.getItemDisplayTexture(BlockFace.UP)));
            displays.put(BlockFace.DOWN, createPipeItem(variant, textures.getItemDisplayTexture(BlockFace.DOWN)));
        }
        displayItems.put(variantId, displays);
    }

    private ItemStack createPipeItem(PipeVariant variant, String textureData) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) {
            getLogger().warning("Failed to get SkullMeta for pipe item");
            return item;
        }

        if (textureData == null || textureData.isEmpty()) {
            DisplayConfig.TextureSet textures = displayConfig.getTextureSet(variant.getTextureSetId());
            textureData = textures != null ? textures.item() : "";
        }

        PlayerProfile profile = Bukkit.createProfile(PIPE_PROFILE_UUID);
        profile.setProperty(new ProfileProperty("textures", textureData));
        meta.setPlayerProfile(profile);

        meta.displayName(variant.getDisplayName()
                .decoration(TextDecoration.ITALIC, false));

        meta.getPersistentDataContainer().set(variant.getPdcKey(), PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack getPipeItem(PipeVariant variant) {
        ItemStack item = pipeItems.get(variant.getId());
        return item != null ? item.clone() : null;
    }

    public ItemStack getHeadItemForDirection(PipeVariant variant, BlockFace facing) {
        Map<BlockFace, ItemStack> heads = headItems.get(variant.getId());
        if (heads == null) return null;

        // Horizontal directions all use the same texture
        if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH ||
            facing == BlockFace.EAST || facing == BlockFace.WEST) {
            return heads.get(BlockFace.NORTH).clone();
        }
        ItemStack item = heads.get(facing);
        return item != null ? item.clone() : heads.get(BlockFace.NORTH).clone();
    }

    public ItemStack getDisplayItem(PipeVariant variant, BlockFace facing) {
        Map<BlockFace, ItemStack> displays = displayItems.get(variant.getId());
        if (displays == null) return null;

        if (variant.getBehaviorType() == BehaviorType.CORNER) {
            return displays.get(BlockFace.NORTH).clone();
        }
        // Regular pipes: use direction-specific texture
        if (facing == BlockFace.UP) {
            ItemStack item = displays.get(BlockFace.UP);
            return item != null ? item.clone() : displays.get(BlockFace.NORTH).clone();
        } else if (facing == BlockFace.DOWN) {
            ItemStack item = displays.get(BlockFace.DOWN);
            return item != null ? item.clone() : displays.get(BlockFace.NORTH).clone();
        }
        // Horizontal directions all use the same texture
        return displays.get(BlockFace.NORTH).clone();
    }

    /**
     * Get the directional display item for corner pipes (the second, orientation-dependent display).
     */
    public ItemStack getDirectionalDisplayItem(PipeVariant variant, BlockFace facing) {
        Map<BlockFace, ItemStack> displays = directionalDisplayItems.get(variant.getId());
        if (displays == null) return null;

        if (facing == BlockFace.DOWN) {
            ItemStack item = displays.get(BlockFace.DOWN);
            return item != null ? item.clone() : displays.get(BlockFace.NORTH).clone();
        }
        // Horizontal directions all use the same texture
        return displays.get(BlockFace.NORTH).clone();
    }

    /**
     * Determine the pipe variant of an item, or null if it's not a pipe.
     */
    public PipeVariant getVariant(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return null;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        var pdc = meta.getPersistentDataContainer();

        for (PipeVariant variant : variantRegistry.getAllVariants()) {
            if (pdc.has(variant.getPdcKey(), PersistentDataType.BYTE)) {
                return variant;
            }
        }
        return null;
    }

    /**
     * Check if an item is any type of pipe.
     */
    public boolean isPipeItem(ItemStack item) {
        return getVariant(item) != null;
    }
}
