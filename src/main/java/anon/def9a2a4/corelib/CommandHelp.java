package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * One plugin command's help screen, usage lines and subcommand tab completion, all built from a single list
 * of subcommands so they can't drift apart. Shared by DefCoreLib and its companion plugins, so every
 * {@code /<command> help} looks the same: title, a one-line blurb, the subcommands the sender may use, then
 * the GitHub and Discord links.
 *
 * <p>Colours: gold header, white command, gray description; red for errors, yellow for usage, green for
 * success. {@link #give} is the shared {@code give <id> [amount] [player]} tail.
 */
public final class CommandHelp {

    public static final String GITHUB = "https://github.com/def9a2a4/defCoreLib";
    public static final String DISCORD = "https://def9a2a4.github.io/discord.html";

    /** A subcommand: {@code args} is its argument synopsis ("" for none); a null {@code permission} is open
     *  to everyone; a {@code hidden} one works but stays out of help and tab completion (dev/test tools). */
    public record Sub(String name, String args, String description, @Nullable String permission, boolean hidden) {}

    private final String command;
    private final String title;
    private final String blurb;
    private final List<Sub> subs = new ArrayList<>();

    /** @param command the command label, without the slash
     *  @param title   the plugin's display name, for the header
     *  @param blurb   one line on what the plugin does */
    public CommandHelp(String command, String title, String blurb) {
        this.command = command;
        this.title = title;
        this.blurb = blurb;
    }

    public CommandHelp sub(String name, String args, String description, @Nullable String permission) {
        subs.add(new Sub(name, args, description, permission, false));
        return this;
    }

    public CommandHelp hidden(String name, String args, String description, @Nullable String permission) {
        subs.add(new Sub(name, args, description, permission, true));
        return this;
    }

    /** True for {@code /<command>}, {@code /<command> help} and {@code /<command> ?}. */
    public static boolean wantsHelp(String[] args) {
        return args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?");
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== " + title + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(blurb, NamedTextColor.GRAY));
        boolean any = false;
        for (Sub s : subs) {
            if (s.hidden() || !allowed(sender, s)) continue;
            if (!any) sender.sendMessage(Component.text("Commands:", NamedTextColor.GOLD));
            any = true;
            sender.sendMessage(Component.text(usage(s), NamedTextColor.WHITE)
                .append(Component.text(" - " + s.description(), NamedTextColor.GRAY)));
        }
        sender.sendMessage(link("GitHub: ", GITHUB));
        sender.sendMessage(link("Discord: ", DISCORD));
    }

    /** "Usage: /cmd name args" for subcommand {@code name}. */
    public void sendUsage(CommandSender sender, String name) {
        Sub s = find(name);
        sender.sendMessage(Component.text("Usage: " + (s == null ? "/" + command + " " + name : usage(s)),
            NamedTextColor.YELLOW));
    }

    /** An unrecognised subcommand: say so, then show the help. */
    public void sendUnknown(CommandSender sender, String arg) {
        sender.sendMessage(Component.text("Unknown subcommand: " + arg, NamedTextColor.RED));
        sendHelp(sender);
    }

    /** Whether the sender may use subcommand {@code name} (an unknown name counts as allowed). Silent. */
    public boolean allows(CommandSender sender, String name) {
        Sub s = find(name);
        return s == null || allowed(sender, s);
    }

    /** False (and tells the sender) when they lack {@code name}'s permission. */
    public boolean checkPermission(CommandSender sender, String name) {
        Sub s = find(name);
        if (s == null || allowed(sender, s)) return true;
        sender.sendMessage(Component.text("You don't have permission for /" + command + " " + s.name() + ".",
            NamedTextColor.RED));
        return false;
    }

    /** First-argument completions: {@code help} plus every visible subcommand the sender may use. */
    public List<String> completeSubcommand(CommandSender sender, String prefix) {
        List<String> names = new ArrayList<>();
        names.add("help");
        for (Sub s : subs) if (!s.hidden() && allowed(sender, s)) names.add(s.name());
        return filter(names, prefix);
    }

    public static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    /** The shared {@code [amount] [player]} completions for a give subcommand at argument index {@code idx}
     *  (0-based, the amount's position), or null when {@code args} is not at either of them. */
    public static @Nullable List<String> completeGiveTail(String[] args, int idx) {
        if (args.length == idx + 1) return filter(List.of("1", "16", "32", "64"), args[idx]);
        if (args.length == idx + 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[idx + 1]);
        }
        return null;
    }

    // ── give ────────────────────────────────────────────────────────────────

    /**
     * The shared tail of every {@code give <id> [amount] [player]}: read the amount ({@code args[idx]},
     * 1..64, default 1) and the target ({@code args[idx + 1]}, default the sender), hand over {@code items}
     * with {@code perItem} copies each (overflow drops at the target's feet) and report
     * "Gave 4x mech:shaft to Steve". {@code what} names the items; for one item, it is prefixed with the
     * amount. Errors (bad amount, unknown player, console without a target) are reported here.
     */
    public void give(CommandSender sender, String sub, String[] args, int idx, List<ItemStack> items, String what) {
        int amount = 1;
        if (args.length > idx) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[idx])));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Invalid amount: " + args[idx], NamedTextColor.RED));
                return;
            }
        }
        Player target;
        if (args.length > idx + 1) {
            target = Bukkit.getPlayerExact(args[idx + 1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[idx + 1], NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Name a player to give to from the console.", NamedTextColor.RED));
            sendUsage(sender, sub);
            return;
        }
        for (ItemStack proto : items) {
            ItemStack stack = proto.clone();
            stack.setAmount(amount);
            for (ItemStack overflow : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            }
        }
        String label = items.size() == 1 ? amount + "x " + what : what;
        sender.sendMessage(Component.text("Gave " + label + " to " + target.getName(), NamedTextColor.GREEN));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private String usage(Sub s) {
        return "/" + command + " " + s.name() + (s.args().isEmpty() ? "" : " " + s.args());
    }

    private @Nullable Sub find(String name) {
        for (Sub s : subs) if (s.name().equalsIgnoreCase(name)) return s;
        return null;
    }

    private static boolean allowed(CommandSender sender, Sub s) {
        return s.permission() == null || sender.hasPermission(s.permission());
    }

    private static Component link(String label, String url) {
        return Component.text(label, NamedTextColor.GRAY)
            .append(Component.text(url, NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url)));
    }
}
