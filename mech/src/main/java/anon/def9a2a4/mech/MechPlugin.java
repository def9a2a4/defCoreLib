package anon.def9a2a4.mech;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mechanism companion. All rotation-mechanism content (rotation blocks + power network, glue, the
 * mechanism minecart) lives in DefCoreLib under the {@code mech} namespace, shipped recipe-less.
 * This plugin enables those recipes, and owns the {@code mech} advancement system: it ships the
 * advancement datapack (see {@link MechBootstrap}) and grants the tree as players hit milestones
 * (see {@link MechAdvancements} / {@link MechAdvancementListeners}). Because the datapack ships only
 * here, the advancements exist only when this plugin is installed.
 *
 * <p>Large/huge windmills are gated on the bbanners plugin: the tier swap that upgrades a windmill
 * by the banner used is only activated when bbanners is present (a soft dependency, so bbanners
 * loads first if installed). This is a <b>recipe</b> gate — without bbanners, plain windmills still
 * craft and the large/huge windmill tiers are uncraftable. Tier blocks that already exist are
 * unaffected and keep working normally.
 *
 * <p>The gate stops at the windmill. Tier propellers are made <i>from</i> tier windmills, so without
 * bbanners none can be crafted from scratch either — but that follows from having no tier windmill,
 * not from a second gate. A tier windmill obtained another way ({@code /give}, creative, a world where
 * bbanners used to be installed) converts tier-for-tier, with no silent downgrade.
 */
public final class MechPlugin extends JavaPlugin {

    private MechAdvancements advancements;

    @Override
    public void onEnable() {
        new Metrics(this, 32320);
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            getLogger().severe("DefCoreLib not present; Mechanism recipes cannot be enabled.");
            return;
        }
        core.getRegistry().enableRecipes("mech");
        boolean banners = getServer().getPluginManager().isPluginEnabled("bbanners");
        core.getRegistry().setWindmillTierEnabled(banners);
        getLogger().info("Mechanism recipes enabled" + (banners
                ? " (large/huge windmill tiers active via BetterBanners)."
                : " (BetterBanners absent - large/huge windmills uncraftable; existing ones still work)."));

        // Advancements: grant manager + milestone listeners + the /mech advance debug command.
        advancements = new MechAdvancements(this);
        getServer().getPluginManager().registerEvents(
                new MechAdvancementListeners(core.getRegistry(), advancements), this);
        registerCommand();
    }

    @Override
    public void onDisable() {
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core != null) {
            core.getRegistry().disableRecipes("mech");
            core.getRegistry().setWindmillTierEnabled(false);
        }
    }

    // ── /mech advance <path|*> — op-only, for testing the tree ──────────────

    private void registerCommand() {
        Command cmd = new Command("mech", "Mechanism admin commands", "/mech advance <path|*>", List.of()) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return onMechCommand(sender, args);
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return onMechTab(sender, args);
            }
        };
        cmd.setPermission("mech.admin");
        Bukkit.getCommandMap().register("mech", cmd);
    }

    private boolean onMechCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mech.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("advance")) {
            sender.sendMessage("§7Usage: /mech advance <path|*>");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly a player can be granted advancements.");
            return true;
        }
        if (advancements == null) {
            sender.sendMessage("§cAdvancements unavailable (DefCoreLib missing).");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /mech advance <path|*>");
            return true;
        }
        if (args[1].equals("*")) {
            int n = advancements.grantAll(player);
            sender.sendMessage("§aGranted " + n + " mech advancement(s).");
        } else if (advancements.grantByPath(player, args[1])) {
            sender.sendMessage("§aGranted mech:" + args[1] + " (and ancestors).");
        } else {
            sender.sendMessage("§cNo such advancement: mech:" + args[1]
                    + " §7(is the mech datapack loaded?)");
        }
        return true;
    }

    private List<String> onMechTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return "advance".startsWith(args[0].toLowerCase()) ? List.of("advance") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("advance") && advancements != null) {
            String prefix = args[1].toLowerCase();
            List<String> paths = advancements.allPaths().stream()
                    .filter(p -> p.startsWith(prefix))
                    .collect(Collectors.toList());
            if ("*".startsWith(prefix)) paths.add(0, "*");
            return paths;
        }
        return List.of();
    }
}
