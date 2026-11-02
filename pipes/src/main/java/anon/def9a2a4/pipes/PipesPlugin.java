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
import anon.def9a2a4.corelib.CommandHelp;
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

public class PipesPlugin extends JavaPlugin {

    private final WeakHashMap<World, PipeManager> pipeManagers = new WeakHashMap<>();

    private PipeConfig pipeConfig;
    private DisplayConfig displayConfig;
    private VariantRegistry variantRegistry;
    private WorldManager worldManager;
    private LegacyPipeMigrator legacyMigrator;
    private FilterGui filterGui;
    // pipes.yml from the jar, parsed once: block definitions (for CoreLib) and each pipe type's
    // transport behaviour (the `pipe:` sections) plus cauldron conversions (read here).
    private YamlConfiguration pipesYaml;

    @Override
    public void onEnable() {
        new Metrics(this, 28844);

        variantRegistry = new VariantRegistry(getLogger());
        pipesYaml = loadPipesYaml();
        loadConfigs();

        if (!variantRegistry.hasVariants()) {
            getLogger().severe("No pipe variants configured! Plugin will not function correctly.");
        }

        CustomBlockRegistry coreLibRegistry = CoreLibPlugin.getInstance().getRegistry();

        // Load static block definitions from YAML, then overlay runtime callbacks. A disabled pipe type
        // (config.yml disabled-pipes) is registered without its recipes: uncraftable, but any already
        // placed keep working.
        for (PipeVariant variant : variantRegistry.getAllVariants()) {
            if (!variantRegistry.isEnabled(variant.id())) pipesYaml.set("blocks." + variant.id() + ".recipes", null);
        }
        BlockLoader.load(pipesYaml, coreLibRegistry, getLogger());
        PipeBlockRegistrar.register(coreLibRegistry, this);

        // WorldFilter
        WorldFilter worldFilter = pipeConfig.getWorldFilter();
        if (worldFilter != null) {
            coreLibRegistry.setWorldFilter("pipes", worldFilter);
        }

        // Cauldron conversions
        registerCauldronConversions(coreLibRegistry);

        // (copper→oxidized crafting recipes are declared in pipes.yml and handled by CoreLib;
        //  the water bucket is left behind via vanilla's crafting remainder.)

        // Legacy (standalone Pipes <= v0.2.0) compat shim: adopts old pipes into the CoreLib format
        // and cleans their stray displays. Constructed before the initWorld loop so every enabled
        // world gets its catch-up sweep scheduled via initWorld.
        legacyMigrator = new LegacyPipeMigrator(this, coreLibRegistry);
        getServer().getPluginManager().registerEvents(legacyMigrator, this);
        coreLibRegistry.registerForeignOrphanDetector("pipes-legacy", legacyMigrator::inspectLegacyDisplay);

        worldManager = new WorldManager(this, pipeManagers);
        getServer().getPluginManager().registerEvents(new MachineEjectListener(pipeManagers), this);
        // Fluid transport handshake: corelib's pump routes fluid units through pipe chains via
        // this walker (dependency-inverted like MachineEjectListener/MechanismConduits).
        anon.def9a2a4.corelib.fluid.FluidRouting.register(new FluidPipeWalker(pipeManagers));
        getServer().getPluginManager().registerEvents(worldManager, this);
        filterGui = new FilterGui(this);
        getServer().getPluginManager().registerEvents(filterGui, this);

        for (World world : Bukkit.getWorlds()) {
            worldManager.initWorld(world);
        }
    }

    @Override
    public void onDisable() {
        // Close open filter menus: once our listener is gone their template items could be taken out.
        if (filterGui != null) filterGui.closeFor(null);
        CoreLibPlugin core = CoreLibPlugin.getInstance();   // null if the core is already gone
        if (core != null) {
            core.getRegistry().unregisterForeignOrphanDetector("pipes-legacy");
            core.getRegistry().clearCauldronConversions("pipes");
        }
        for (PipeManager manager : pipeManagers.values()) {
            manager.shutdown();
        }
        pipeManagers.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("pipes")) return false;

        if (CommandHelp.wantsHelp(args)) {
            COMMAND_HELP.sendHelp(sender);
            return true;
        }
        if (!COMMAND_HELP.checkPermission(sender, args[0])) return true;

        if (args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }

        if (args[0].equalsIgnoreCase("give")) {
            return handleGive(sender, args);
        }

        if (args[0].equalsIgnoreCase("info")) {
            return handleInfo(sender);
        }

        if (args[0].equalsIgnoreCase("delete_all")) {
            return handleDeleteAll(sender);
        }

        if (args[0].equalsIgnoreCase("migrate")) {
            return handleMigrate(sender);
        }

        COMMAND_HELP.sendUnknown(sender, args[0]);
        return true;
    }

    /** `/pipes` help, usage lines and subcommand completion. Each subcommand has its own permission;
     *  pipes.admin grants them all. */
    private static final CommandHelp COMMAND_HELP = new CommandHelp("pipes", "Pipes",
            "Item pipes that move items between containers, with filter pipes for sorting.")
        .sub("give", "<item|ALL> [amount] [player]", "Give pipe items", "pipes.give")
        .sub("info", "", "Show pipe statistics", "pipes.info")
        .sub("reload", "", "Reload the configuration", "pipes.reload")
        .sub("migrate", "", "Migrate legacy (v0.2.0) pipes in loaded chunks", "pipes.migrate")
        .sub("delete_all", "", "Delete every pipe (dangerous!)", "pipes.delete_all");

    /** Schedule a one-tick-later legacy sweep for a freshly enabled world. Called from
     *  WorldManager.initWorld so startup, WorldLoadEvent, and /pipes reload all funnel through the
     *  same path (spawn chunks are already resident and never re-fire EntitiesLoadEvent). */
    void scheduleLegacySweep(World world) {
        if (legacyMigrator == null) return;
        getServer().getScheduler().runTask(this, () -> {
            LegacyPipeMigrator.Result r = legacyMigrator.sweepWorld(world);
            if (!r.isEmpty()) {
                getLogger().info("Legacy pipe migration (" + world.getName() + "): " + r.describe());
            }
        });
    }

    private boolean handleMigrate(CommandSender sender) {
        if (!sender.hasPermission("pipes.migrate")) {
            sender.sendMessage(Component.text("You don't have permission to migrate legacy pipes.").color(NamedTextColor.RED));
            return true;
        }
        LegacyPipeMigrator.Result r = legacyMigrator.sweepLoadedChunks();
        sender.sendMessage(Component.text("Legacy pipe sweep: " + r.describe()).color(NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Unloaded chunks migrate automatically when they load.").color(NamedTextColor.GRAY));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("pipes.reload")) {
            sender.sendMessage(Component.text("You don't have permission to reload the config.").color(NamedTextColor.RED));
            return true;
        }

        loadConfigs();

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

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            COMMAND_HELP.sendUsage(sender, "give");
            return true;
        }

        if (args[1].equalsIgnoreCase("ALL")) {
            List<ItemStack> items = new ArrayList<>();
            for (PipeVariant variant : variantRegistry.getAllVariants()) {
                if (!variantRegistry.isEnabled(variant.id())) continue;
                ItemStack item = getPipeItem(variant);
                if (item != null) items.add(item);
            }
            COMMAND_HELP.give(sender, "give", args, 2, items, "every pipe (" + items.size() + " kinds)");
            return true;
        }

        PipeVariant variant = variantRegistry.getVariant(args[1]);
        if (variant == null || !variantRegistry.isEnabled(variant.id())) {
            sender.sendMessage(Component.text("Unknown item: " + args[1]).color(NamedTextColor.RED));
            return true;
        }

        ItemStack item = getPipeItem(variant);
        if (item == null) {
            sender.sendMessage(Component.text("Failed to create item for variant: " + args[1]).color(NamedTextColor.RED));
            return true;
        }
        COMMAND_HELP.give(sender, "give", args, 2, List.of(item), variant.id());
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

        if (args.length == 1) return COMMAND_HELP.completeSubcommand(sender, args[0]);
        if (!COMMAND_HELP.allows(sender, args[0])) return List.of();

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            List<String> completions = new ArrayList<>();
            completions.add("ALL");
            for (PipeVariant variant : variantRegistry.getAllVariants()) {
                if (variantRegistry.isEnabled(variant.id())) completions.add(variant.id());
            }
            return completions.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
        }

        if (args[0].equalsIgnoreCase("give")) {
            List<String> tail = CommandHelp.completeGiveTail(args, 2);
            if (tail != null) return tail;
        }

        return List.of();
    }

    private YamlConfiguration loadPipesYaml() {
        try (InputStream stream = getResource("pipes.yml")) {
            if (stream != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            getLogger().severe("Could not find pipes.yml in JAR!");
        } catch (Exception e) {
            getLogger().severe("Failed to load pipes.yml: " + e.getMessage());
        }
        return new YamlConfiguration();
    }

    private void loadConfigs() {
        saveDefaultConfig();
        reloadConfig();
        migrateConfig();
        pipeConfig = new PipeConfig(getConfig());

        File externalDisplayConfig = new File(getDataFolder(), "display.yml");
        FileConfiguration displayConfigRaw = null;
        if (externalDisplayConfig.exists()) {
            getLogger().info("Loading display.yml from plugin folder (development override)");
            displayConfigRaw = YamlConfiguration.loadConfiguration(externalDisplayConfig);
        } else {
            try (InputStream stream = getResource("display.yml")) {
                if (stream == null) {
                    getLogger().severe("Could not find display.yml in JAR!");
                } else {
                    displayConfigRaw = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                getLogger().severe("Failed to load display.yml: " + e.getMessage());
            }
        }
        if (displayConfigRaw == null) {
            displayConfigRaw = new YamlConfiguration();
        }
        displayConfig = new DisplayConfig(displayConfigRaw);

        variantRegistry.loadFromBlocks(pipesYaml.getConfigurationSection("blocks"),
            getConfig().getStringList("disabled-pipes"));
    }

    /**
     * Pre-0.5.0 configs defined every pipe type under {@code variants:} (and {@code cauldron-conversions:}).
     * Types now come from pipes.yml in the jar, so an old list would silently leave out every type added
     * since (the filter pipes). Strip those sections, add {@code disabled-pipes}, keep the old file as a
     * backup. Runs on every load; a no-op once the config is current.
     */
    private void migrateConfig() {
        FileConfiguration cfg = getConfig();
        boolean legacy = cfg.contains("variants", true) || cfg.contains("cauldron-conversions", true)
            || cfg.contains("conversion-recipes", true);
        boolean missingList = !cfg.contains("disabled-pipes", true);
        if (!legacy && !missingList) return;

        File file = new File(getDataFolder(), "config.yml");
        // A YAML syntax error loads as an empty config (Bukkit logs the parse error); saving now would
        // overwrite the user's file with our defaults. Leave it alone — the defaults apply until it's fixed.
        if (cfg.getKeys(false).isEmpty() && file.length() > 0) {
            getLogger().warning("config.yml has no readable settings (a YAML error?) — not migrating it;"
                + " using defaults until it's fixed.");
            return;
        }
        if (legacy) {
            File backup = new File(getDataFolder(), "config-pre-0.5.0.yml");
            try {
                if (!backup.exists()) java.nio.file.Files.copy(file.toPath(), backup.toPath());
            } catch (java.io.IOException e) {
                getLogger().warning("Could not back up config.yml before migrating it: " + e.getMessage());
                return;   // leave the file untouched; the old sections are simply not read
            }
            cfg.set("variants", null);
            cfg.set("cauldron-conversions", null);
            cfg.set("conversion-recipes", null);
        }
        if (missingList) {
            cfg.set("disabled-pipes", List.of());
            cfg.setComments("disabled-pipes", List.of(
                "Pipe types to turn off. Pipe types themselves are defined by the plugin (pipes.yml in the jar);",
                "list ids here (e.g. gold_filter_pipe) to make them uncraftable and hide them from /pipes give.",
                "Pipes already placed in the world keep working. Takes effect on restart."));
        }
        saveConfig();
        if (legacy) {
            getLogger().info("Migrated config.yml: pipe types now come from the plugin itself, so the old"
                + " 'variants' and 'cauldron-conversions' sections were removed (to turn a type off, list it"
                + " under 'disabled-pipes'). The previous file is saved as config-pre-0.5.0.yml.");
        }
    }

    private void registerCauldronConversions(CustomBlockRegistry registry) {
        ConfigurationSection section = pipesYaml.getConfigurationSection("cauldron_conversions");
        if (section == null) return;
        for (String fromId : section.getKeys(false)) {
            String toId = section.getString(fromId);
            if (toId == null || !variantRegistry.isEnabled(toId)) continue;
            String fullFrom = "pipes:" + fromId;
            String fullTo = "pipes:" + toId;
            if (registry.getType(fullFrom) == null) {
                getLogger().warning("Cauldron conversion source '" + fromId + "' is not a registered block type");
                continue;
            }
            if (registry.getType(fullTo) == null) {
                getLogger().warning("Cauldron conversion target '" + toId + "' is not a registered block type");
                continue;
            }
            registry.registerCauldronConversion(fullFrom, fullTo);
        }
    }

    public PipeConfig getPipeConfig() { return pipeConfig; }
    public DisplayConfig getDisplayConfig() { return displayConfig; }
    public VariantRegistry getVariantRegistry() { return variantRegistry; }

    public PipeManager getPipeManager(World world) {
        return pipeManagers.get(world);
    }

    public FilterGui getFilterGui() { return filterGui; }

    public ItemStack getPipeItem(PipeVariant variant) {
        CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
        CustomHeadBlock type = registry.getType("pipes:" + variant.id());
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
