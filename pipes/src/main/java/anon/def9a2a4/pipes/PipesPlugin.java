package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import anon.def9a2a4.corelib.BlockLoader;
import anon.def9a2a4.corelib.CoreLibPlugin;
import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import anon.def9a2a4.corelib.WorldFilter;
import anon.def9a2a4.pipes.config.DisplayConfig;
import anon.def9a2a4.pipes.config.PipeConfig;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.stream.Stream;

public class PipesPlugin extends JavaPlugin {

    private final WeakHashMap<World, PipeManager> pipeManagers = new WeakHashMap<>();

    private PipeConfig pipeConfig;
    private DisplayConfig displayConfig;
    private VariantRegistry variantRegistry;
    private WorldManager worldManager;
    private RecipeManager recipeManager;
    private ConversionRecipeCraftListener conversionRecipeCraftListener;

    @Override
    public void onEnable() {
        new Metrics(this, 28844);

        variantRegistry = new VariantRegistry(getLogger());
        loadConfigs();

        if (!variantRegistry.hasVariants()) {
            getLogger().severe("No pipe variants configured! Plugin will not function correctly.");
        }

        CustomBlockRegistry coreLibRegistry = CoreLibPlugin.getInstance().getRegistry();

        // Load static block definitions from YAML, then overlay runtime callbacks
        try (InputStream stream = getResource("pipes.yml")) {
            if (stream != null) {
                BlockLoader.load(stream, coreLibRegistry, getLogger());
            } else {
                getLogger().severe("Could not find pipes.yml in JAR!");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load pipes.yml: " + e.getMessage());
        }
        PipeBlockRegistrar.register(coreLibRegistry, this);

        // WorldFilter
        WorldFilter worldFilter = pipeConfig.getWorldFilter();
        if (worldFilter != null) {
            coreLibRegistry.setWorldFilter("pipes", worldFilter);
        }

        // Cauldron conversions
        registerCauldronConversions(coreLibRegistry);

        // Conversion recipes only — shaped recipes are handled by CoreLib via pipes.yml
        recipeManager = new RecipeManager(this);
        recipeManager.registerRecipes();

        worldManager = new WorldManager(this, pipeManagers);
        conversionRecipeCraftListener = new ConversionRecipeCraftListener(this, recipeManager);
        getServer().getPluginManager().registerEvents(new MachineEjectListener(pipeManagers), this);
        getServer().getPluginManager().registerEvents(worldManager, this);
        getServer().getPluginManager().registerEvents(conversionRecipeCraftListener, this);

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
        if (!command.getName().equalsIgnoreCase("pipes")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }

        if (args[0].equalsIgnoreCase("recipes")) {
            return handleRecipes(sender);
        }

        if (args[0].equalsIgnoreCase("give")) {
            return handleGive(sender, args);
        }

        if (args[0].equalsIgnoreCase("cleanup")) {
            return handleCleanup(sender);
        }

        if (args[0].equalsIgnoreCase("info")) {
            return handleInfo(sender);
        }

        if (args[0].equalsIgnoreCase("delete_all")) {
            return handleDeleteAll(sender);
        }

        sendHelp(sender);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("pipes.reload")) {
            sender.sendMessage(Component.text("You don't have permission to reload the config.").color(NamedTextColor.RED));
            return true;
        }

        recipeManager.unregisterRecipes();
        loadConfigs();
        recipeManager.registerRecipes();

        CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();

        // Update WorldFilter
        WorldFilter worldFilter = pipeConfig.getWorldFilter();
        if (worldFilter != null) {
            registry.setWorldFilter("pipes", worldFilter);
        } else {
            registry.clearWorldFilter("pipes");
        }

        // Update cauldron conversions
        registry.clearCauldronConversions("pipes");
        registerCauldronConversions(registry);

        for (PipeManager manager : new ArrayList<>(pipeManagers.values())) {
            manager.reloadVariants(variantRegistry);
            manager.refreshAllDisplays();
            manager.restartTasks();
        }

        // Re-evaluate worlds
        for (World w : Bukkit.getWorlds()) {
            boolean enabled = worldFilter == null || worldFilter.isEnabled(w.getName());
            if (enabled && !pipeManagers.containsKey(w)) {
                worldManager.initWorld(w);
            } else if (!enabled && pipeManagers.containsKey(w)) {
                worldManager.removeWorld(w);
            }
        }

        sender.sendMessage(Component.text("Pipes config reloaded!").color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleRecipes(CommandSender sender) {
        if (!sender.hasPermission("pipes.recipes")) {
            sender.sendMessage(Component.text("You don't have permission to unlock recipes.").color(NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED));
            return true;
        }
        recipeManager.discoverAllRecipes(player);
        sender.sendMessage(Component.text("Unlocked all Pipes recipes!").color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pipes.give")) {
            sender.sendMessage(Component.text("You don't have permission to give items.").color(NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /pipes give <item> [amount]").color(NamedTextColor.YELLOW));
            return true;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Invalid amount: " + args[2]).color(NamedTextColor.RED));
                return true;
            }
        }

        if (args[1].equalsIgnoreCase("ALL")) {
            int given = 0;
            for (PipeVariant variant : variantRegistry.getAllVariants()) {
                ItemStack item = getPipeItem(variant);
                if (item != null) {
                    player.getInventory().addItem(item);
                    given++;
                }
            }
            sender.sendMessage(Component.text("Gave " + given + " pipe item(s)!").color(NamedTextColor.GREEN));
            return true;
        }

        PipeVariant variant = variantRegistry.getVariant(args[1]);
        if (variant == null) {
            sender.sendMessage(Component.text("Unknown item: " + args[1]).color(NamedTextColor.RED));
            return true;
        }

        ItemStack item = getPipeItem(variant);
        if (item == null) {
            sender.sendMessage(Component.text("Failed to create item for variant: " + args[1]).color(NamedTextColor.RED));
            return true;
        }
        item.setAmount(amount);
        player.getInventory().addItem(item);
        sender.sendMessage(Component.text("Gave " + amount + "x " + args[1] + "!").color(NamedTextColor.GREEN));
        return true;
    }

    private boolean handleCleanup(CommandSender sender) {
        if (!sender.hasPermission("pipes.cleanup")) {
            sender.sendMessage(Component.text("You don't have permission to cleanup orphaned displays.").color(NamedTextColor.RED));
            return true;
        }
        int totalRemoved = 0;
        for (PipeManager manager : pipeManagers.values()) {
            int removed = manager.cleanupOrphanedDisplays();
            if (removed > 0) {
                sender.sendMessage(Component.text("Removed " + removed + " orphaned display(s) in " + manager.getWorld().getName()).color(NamedTextColor.GRAY));
            }
            totalRemoved += removed;
        }
        if (totalRemoved > 0) {
            sender.sendMessage(Component.text("Cleanup complete! Removed " + totalRemoved + " orphaned display(s) total.").color(NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("No orphaned displays found.").color(NamedTextColor.GREEN));
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("pipes.info")) {
            sender.sendMessage(Component.text("You don't have permission to view pipe info.").color(NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("=== Pipes Info ===").color(NamedTextColor.GOLD));

        List<PipeManager> managersToQuery = getQueryableManagers(sender);
        for (PipeManager manager : managersToQuery) {
            var counts = manager.getPipeCountsByVariant();
            int totalPipes = manager.getTotalPipeCount();
            sender.sendMessage(Component.text(manager.getWorld().getName() + ":").color(NamedTextColor.GOLD));
            if (counts.isEmpty()) {
                sender.sendMessage(Component.text("  No pipes registered.").color(NamedTextColor.GRAY));
            } else {
                sender.sendMessage(Component.text("  Registered pipes: " + totalPipes).color(NamedTextColor.WHITE));
                for (var entry : counts.entrySet()) {
                    sender.sendMessage(Component.text("    " + entry.getKey() + ": " + entry.getValue()).color(NamedTextColor.GRAY));
                }
            }
        }

        int totalOrphaned = 0;
        for (PipeManager manager : managersToQuery) {
            int orphaned = manager.countOrphanedDisplays();
            if (orphaned > 0) {
                sender.sendMessage(Component.text("Orphaned displays in " + manager.getWorld().getName() + ": " + orphaned).color(NamedTextColor.YELLOW));
            }
            totalOrphaned += orphaned;
        }
        if (totalOrphaned == 0) {
            sender.sendMessage(Component.text("No orphaned displays found.").color(NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Total orphaned displays: " + totalOrphaned).color(NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean handleDeleteAll(CommandSender sender) {
        if (!sender.hasPermission("pipes.delete_all")) {
            sender.sendMessage(Component.text("You don't have permission to delete all pipes.").color(NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED));
            return true;
        }
        PipeManager manager = pipeManagers.get(player.getWorld());
        if (manager == null) {
            sender.sendMessage(Component.text("Pipes are not enabled in this world.").color(NamedTextColor.RED));
            return true;
        }
        int totalDeleted = manager.deleteAllPipes();
        if (totalDeleted > 0) {
            sender.sendMessage(Component.text("Deleted " + totalDeleted + " pipe(s) in " + player.getWorld().getName() + ".").color(NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("No pipes to delete.").color(NamedTextColor.GREEN));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("pipes")) return List.of();

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
            return completions.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return List.of("1", "16", "32", "64").stream().filter(s -> s.startsWith(args[2])).toList();
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
        saveDefaultConfig();
        reloadConfig();
        pipeConfig = new PipeConfig(getConfig());

        File externalDisplayConfig = new File(getDataFolder(), "display.yml");
        FileConfiguration displayConfigRaw;
        if (externalDisplayConfig.exists()) {
            getLogger().info("Loading display.yml from plugin folder (development override)");
            displayConfigRaw = YamlConfiguration.loadConfiguration(externalDisplayConfig);
        } else {
            try (InputStream stream = getResource("display.yml")) {
                if (stream == null) {
                    getLogger().severe("Could not find display.yml in JAR!");
                    return;
                }
                displayConfigRaw = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (Exception e) {
                getLogger().severe("Failed to load display.yml: " + e.getMessage());
                return;
            }
        }
        displayConfig = new DisplayConfig(displayConfigRaw);

        ConfigurationSection variantsSection = getConfig().getConfigurationSection("variants");
        variantRegistry.loadFromConfig(variantsSection);
    }

    private void registerCauldronConversions(CustomBlockRegistry registry) {
        ConfigurationSection section = getConfig().getConfigurationSection("cauldron-conversions");
        if (section == null) return;
        for (String fromId : section.getKeys(false)) {
            String toId = section.getString(fromId);
            if (toId != null) {
                registry.registerCauldronConversion("pipes:" + fromId, "pipes:" + toId);
            }
        }
    }

    public PipeConfig getPipeConfig() { return pipeConfig; }
    public DisplayConfig getDisplayConfig() { return displayConfig; }
    public VariantRegistry getVariantRegistry() { return variantRegistry; }

    public PipeManager getPipeManager(World world) {
        return pipeManagers.get(world);
    }

    public ItemStack getPipeItem(PipeVariant variant) {
        CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
        CustomHeadBlock type = registry.getType("pipes:" + variant.getId());
        if (type == null) return null;
        return type.createItem(1);
    }

    private List<PipeManager> getQueryableManagers(CommandSender sender) {
        if (sender instanceof Player player) {
            PipeManager mgr = pipeManagers.get(player.getWorld());
            if (mgr != null) return List.of(mgr);
            return List.of();
        }
        return new ArrayList<>(pipeManagers.values());
    }
}
