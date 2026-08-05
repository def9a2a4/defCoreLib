package anon.def9a2a4.headsmith;

import anon.def9a2a4.corelib.BlockLoader;
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
import org.bukkit.inventory.ItemStack;
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

        if (args.length == 0) {
            sender.sendMessage(Component.text(
                    "Usage: /headsmith <give|recipes|migrate>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "give" -> handleGive(sender, registry, args);
            case "recipes" -> handleRecipes(sender, registry, args);
            case "migrate" -> {
                if (!sender.hasPermission("headsmith.migrate")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                } else if (migrator == null) {
                    sender.sendMessage(Component.text("Migrator unavailable (DefCoreLib missing at enable).",
                            NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("Running migration sweep over loaded chunks…",
                            NamedTextColor.YELLOW));
                    migrator.sweep(sender);
                }
            }
            default -> sender.sendMessage(Component.text(
                    "Unknown subcommand: " + args[0], NamedTextColor.RED));
        }
        return true;
    }

    private void handleGive(CommandSender sender, CustomBlockRegistry registry, String[] args) {
        if (!sender.hasPermission("headsmith.give")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Must be a player.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /headsmith give <id> [amount]", NamedTextColor.YELLOW));
            return;
        }
        String id = args[1].contains(":") ? args[1] : NAMESPACE + ":" + args[1];
        CustomHeadBlock type = registry.getType(id);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown head: " + args[1], NamedTextColor.RED));
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Invalid amount: " + args[2], NamedTextColor.RED));
                return;
            }
        }
        ItemStack item = type.createItem(amount);
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        sender.sendMessage(Component.text("Gave " + amount + "x " + type.fullId(), NamedTextColor.GREEN));
    }

    private void handleRecipes(CommandSender sender, CustomBlockRegistry registry, String[] args) {
        if (!sender.hasPermission("headsmith.recipes")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /headsmith recipes <give|take> [player]", NamedTextColor.YELLOW));
            return;
        }
        boolean give = args[1].equalsIgnoreCase("give");
        boolean take = args[1].equalsIgnoreCase("take");
        if (!give && !take) {
            sender.sendMessage(Component.text("Usage: /headsmith recipes <give|take> [player]", NamedTextColor.YELLOW));
            return;
        }
        // Target: the named player (requires the base perm) or self.
        Player target;
        if (args.length >= 3) {
            target = getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[2], NamedTextColor.RED));
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
        if (args.length == 1) {
            return filter(List.of("give", "recipes", "migrate"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("recipes")) {
            return filter(List.of("give", "take"), args[1]);
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

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.startsWith(p)) out.add(o);
        return out;
    }
}
