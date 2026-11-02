package anon.def9a2a4.headsmith;

import anon.def9a2a4.corelib.BlockLoader;
import anon.def9a2a4.corelib.CommandHelp;
import anon.def9a2a4.corelib.CoreLibPlugin;
import anon.def9a2a4.corelib.CustomBlockRegistry;
import anon.def9a2a4.corelib.CustomHeadBlock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bstats.bukkit.Metrics;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * HeadSmith companion. All ~3,300 decorative player-head "blocks" (barrels, candles, buckets, the
 * alphabet, mini-blocks, …) live in DefCoreLib as DATA under the {@code headsmith} namespace, loaded
 * from a single generated {@code headsmith.yml} via the core {@link BlockLoader}. This plugin is a thin
 * loader plus commands: {@code /headsmith give}, {@code /headsmith recipes give|take} (heads are kept out
 * of the vanilla recipe book by default — {@code core}'s per-namespace discovery gate), and
 * {@code /headsmith migrate} (adopt placed heads from the old standalone plugin — see the migrator).
 */
public final class HeadSmithPlugin extends JavaPlugin {

    public static final String NAMESPACE = "headsmith";

    private LegacyHeadMigrator migrator;

    @Override
    public void onEnable() {
        new Metrics(this, 28528);

        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            getLogger().severe("DefCoreLib not present; HeadSmith heads cannot be loaded.");
            return;
        }
        CustomBlockRegistry registry = core.getRegistry();

        try (InputStream stream = getResource("headsmith.yml")) {
            if (stream != null) {
                int n = BlockLoader.load(stream, registry, getLogger());
                getLogger().info("Loaded " + n + " HeadSmith heads.");
            } else {
                getLogger().severe("headsmith.yml not found in JAR — run the converter to generate it.");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load headsmith.yml: " + e.getMessage());
        }
        // No namespace-enable call needed: core enables every namespace in every world by default.

        // Adopt already-placed legacy heads (mandatory, on by default). Registers block/item triggers and
        // runs a tick-spread catch-up sweep over currently-loaded chunks.
        migrator = new LegacyHeadMigrator(this, registry);
        getServer().getPluginManager().registerEvents(migrator, this);
        migrator.startEnableSweep();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("headsmith")) return false;
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            sender.sendMessage(Component.text("DefCoreLib is not loaded.", NamedTextColor.RED));
            return true;
        }
        CustomBlockRegistry registry = core.getRegistry();

        if (CommandHelp.wantsHelp(args)) {
            COMMAND_HELP.sendHelp(sender);
            return true;
        }
        if (!COMMAND_HELP.checkPermission(sender, args[0])) return true;

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "give" -> handleGive(sender, registry, args);
            case "recipes" -> handleRecipes(sender, registry, args);
            case "migrate" -> {
                if (migrator == null) {
                    sender.sendMessage(Component.text("Migrator unavailable (DefCoreLib missing at enable).",
                            NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("Running migration sweep over loaded chunks…",
                            NamedTextColor.YELLOW));
                    migrator.sweep(sender);
                }
            }
            default -> COMMAND_HELP.sendUnknown(sender, args[0]);
        }
        return true;
    }

    /** `/headsmith` help, usage lines and subcommand completion. */
    private static final CommandHelp COMMAND_HELP = new CommandHelp("headsmith", "HeadSmith",
            "Thousands of decorative head blocks. Browse them all with /defcorelib catalog.")
        .sub("recipes", "<give|take> [player]", "Show or hide HeadSmith recipes in a recipe book", "headsmith.recipes")
        .sub("give", "<id> [amount] [player]", "Give a HeadSmith head", "headsmith.give")
        .sub("migrate", "", "Convert legacy placed heads in loaded chunks", "headsmith.migrate");

    private void handleGive(CommandSender sender, CustomBlockRegistry registry, String[] args) {
        if (args.length < 2) {
            COMMAND_HELP.sendUsage(sender, "give");
            return;
        }
        String id = args[1].contains(":") ? args[1] : NAMESPACE + ":" + args[1];
        CustomHeadBlock type = registry.getType(id);
        if (type == null || !NAMESPACE.equals(type.namespace())) {   // only HeadSmith's own heads
            sender.sendMessage(Component.text("Unknown head: " + args[1], NamedTextColor.RED));
            return;
        }
        COMMAND_HELP.give(sender, "give", args, 2, List.of(type.createItem(1)), type.fullId());
    }

    private void handleRecipes(CommandSender sender, CustomBlockRegistry registry, String[] args) {
        boolean give = args.length >= 2 && args[1].equalsIgnoreCase("give");
        boolean take = args.length >= 2 && args[1].equalsIgnoreCase("take");
        if (!give && !take) {
            COMMAND_HELP.sendUsage(sender, "recipes");
            return;
        }
        // Target: self, or the named player (another player needs headsmith.recipes.others).
        Player target;
        if (args.length >= 3) {
            target = getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[2], NamedTextColor.RED));
                return;
            }
            if (target != sender && !sender.hasPermission("headsmith.recipes.others")) {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Specify a player: /headsmith recipes " + args[1] + " <player>",
                    NamedTextColor.YELLOW));
            return;
        }
        if (give) {
            registry.discoverNamespaceRecipes(target, NAMESPACE);
            sender.sendMessage(Component.text("Revealed HeadSmith recipes for " + target.getName()
                    + " in their recipe book.", NamedTextColor.GREEN));
        } else {
            registry.undiscoverNamespaceRecipes(target, NAMESPACE);
            sender.sendMessage(Component.text("Hid HeadSmith recipes for " + target.getName()
                    + " from their recipe book.", NamedTextColor.GREEN));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("headsmith")) return List.of();
        if (args.length == 1) return COMMAND_HELP.completeSubcommand(sender, args[0]);
        if (!COMMAND_HELP.allows(sender, args[0])) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("recipes")) {
            return CommandHelp.filter(List.of("give", "take"), args[1]);
        }
        if (args[0].equalsIgnoreCase("give")) {
            List<String> tail = CommandHelp.completeGiveTail(args, 2);
            if (tail != null) return tail;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            if (prefix.isEmpty()) return List.of(); // don't dump 3,300 ids on an empty prefix
            CoreLibPlugin core = CoreLibPlugin.getInstance();
            if (core == null) return List.of();
            List<String> out = new ArrayList<>();
            for (CustomHeadBlock t : core.getRegistry().allTypes()) {
                if (!t.namespace().equals(NAMESPACE)) continue;
                if (t.typeId().toLowerCase().startsWith(prefix) || t.fullId().toLowerCase().startsWith(prefix)) {
                    out.add(t.typeId());
                    if (out.size() >= 50) break;
                }
            }
            return out;
        }
        return List.of();
    }
}
