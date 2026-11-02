package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Whether protection plugins let a player open a container at a spot. Our containers (a head block's storage,
 * a ship's cargo behind a collider shulker, a cart's inventory, a filter pipe's menu) are not vanilla
 * containers, so claim plugins never see them as such: on a click they apply nothing, or their build rule —
 * which would lock out a player trusted with containers but not with building. So ask each one directly, by
 * its own container rule:
 *
 * <ul><li><b>GriefPrevention</b>: the claim's {@code ClaimPermission.Container} (containertrust).</li>
 * <li><b>WorldGuard</b>: {@code testBuild(..., CHEST_ACCESS)}, the call it makes for a chest; region bypass
 * respected.</li>
 * <li><b>Towny</b>: {@code canSwitch(player, loc, CHEST)}, its chest rule.</li></ul>
 *
 * Called by reflection, only when the plugin is enabled, so none of them is a build dependency. A plugin
 * whose API doesn't match (a version we don't know) is logged once and skipped — failing open, since failing
 * closed would lock every container on the server. Plugins not listed here still apply whatever rule they have
 * to the real click, which our handlers honour by ignoring cancelled events.
 */
public final class ContainerAccess {

    private ContainerAccess() {}

    private static final Set<String> warned = new HashSet<>();

    /** True unless an installed protection plugin denies {@code player} container access at {@code at}. */
    public static boolean mayOpen(Player player, Location at) {
        return gp(player, at) && wg(player, at) && towny(player, at);
    }

    // ── GriefPrevention ─────────────────────────────────────────────────────

    private static boolean gp(Player player, Location at) {
        Plugin gp = enabled("GriefPrevention");
        if (gp == null) return true;
        try {
            ClassLoader cl = gp.getClass().getClassLoader();
            Class<?> claimC = cl.loadClass("me.ryanhamshire.GriefPrevention.Claim");
            Object dataStore = gp.getClass().getField("dataStore").get(gp);
            Object claim = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimC)
                .invoke(dataStore, at, false, null);
            if (claim == null) return true;   // wilderness
            Class<?> permC = cl.loadClass("me.ryanhamshire.GriefPrevention.ClaimPermission");
            Object perm = enumConstant(permC, "Container", "Inventory");   // "Inventory" before 16.18
            Object denial = claimC.getMethod("checkPermission", Player.class, permC, Event.class)
                .invoke(claim, player, perm, null);
            if (denial == null) return true;
            message(player, denial);
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("GriefPrevention", e);
            return true;
        }
    }

    // ── WorldGuard ──────────────────────────────────────────────────────────

    private static boolean wg(Player player, Location at) {
        Plugin wgPlugin = enabled("WorldGuard");
        if (wgPlugin == null || at.getWorld() == null) return true;
        try {
            ClassLoader cl = wgPlugin.getClass().getClassLoader();
            Object localPlayer = wgPlugin.getClass().getMethod("wrapPlayer", Player.class).invoke(wgPlugin, player);
            Class<?> adapter = cl.loadClass("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object platform = cl.loadClass("com.sk89q.worldguard.WorldGuard").getMethod("getInstance").invoke(null);
            platform = platform.getClass().getMethod("getPlatform").invoke(platform);
            Class<?> platformC = cl.loadClass("com.sk89q.worldguard.internal.platform.WorldGuardPlatform");

            Class<?> localPlayerC = cl.loadClass("com.sk89q.worldguard.LocalPlayer");
            Class<?> weWorldC = cl.loadClass("com.sk89q.worldedit.world.World");
            Object weWorld = adapter.getMethod("adapt", org.bukkit.World.class).invoke(null, at.getWorld());
            Object sessions = platformC.getMethod("getSessionManager").invoke(platform);
            boolean bypass = (boolean) cl.loadClass("com.sk89q.worldguard.session.SessionManager")
                .getMethod("hasBypass", localPlayerC, weWorldC).invoke(sessions, localPlayer, weWorld);
            if (bypass) return true;

            Object container = platformC.getMethod("getRegionContainer").invoke(platform);
            Object query = cl.loadClass("com.sk89q.worldguard.protection.regions.RegionContainer")
                .getMethod("createQuery").invoke(container);
            Class<?> queryC = cl.loadClass("com.sk89q.worldguard.protection.regions.RegionQuery");
            Class<?> weLocC = cl.loadClass("com.sk89q.worldedit.util.Location");
            Class<?> associableC = cl.loadClass("com.sk89q.worldguard.protection.association.RegionAssociable");
            Class<?> stateFlagC = cl.loadClass("com.sk89q.worldguard.protection.flags.StateFlag");
            Object chestAccess = cl.loadClass("com.sk89q.worldguard.protection.flags.Flags")
                .getField("CHEST_ACCESS").get(null);
            Object flags = java.lang.reflect.Array.newInstance(stateFlagC, 1);
            java.lang.reflect.Array.set(flags, 0, chestAccess);
            Object weLoc = adapter.getMethod("adapt", Location.class).invoke(null, at);
            boolean ok = (boolean) queryC.getMethod("testBuild", weLocC, associableC, flags.getClass())
                .invoke(query, weLoc, localPlayer, flags);
            if (!ok) player.sendMessage(net.kyori.adventure.text.Component.text(
                "Hey! Sorry, but you can't open that here.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return ok;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("WorldGuard", e);
            return true;
        }
    }

    // ── Towny ───────────────────────────────────────────────────────────────

    private static boolean towny(Player player, Location at) {
        Plugin towny = enabled("Towny");
        if (towny == null) return true;
        try {
            // Not silent: Towny tells the player why, as it does for a real chest.
            return (boolean) towny.getClass().getClassLoader()
                .loadClass("com.palmergames.bukkit.towny.event.executors.TownyActionEventExecutor")
                .getMethod("canSwitch", Player.class, Location.class, Material.class)
                .invoke(null, player, at, Material.CHEST);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("Towny", e);
            return true;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static @Nullable Plugin enabled(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled() ? p : null;
    }

    private static Object enumConstant(Class<?> enumC, String... names) {
        for (Object c : enumC.getEnumConstants()) {
            for (String n : names) if (((Enum<?>) c).name().equals(n)) return c;
        }
        throw new IllegalStateException(enumC.getName() + " has none of " + String.join(", ", names));
    }

    /** GriefPrevention returns its denial as a Supplier of the message it would have sent itself. */
    private static void message(Player player, Object denial) {
        if (denial instanceof java.util.function.Supplier<?> s && s.get() instanceof String msg && !msg.isBlank()) {
            player.sendMessage(net.kyori.adventure.text.Component.text(msg,
                net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    private static void warnOnce(String plugin, Throwable e) {
        synchronized (warned) {
            if (!warned.add(plugin)) return;
        }
        Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
            ? ite.getCause() : e;
        Logger log = Bukkit.getLogger();
        log.warning("[DefCoreLib] Couldn't ask " + plugin + " about container access (" + cause
            + "); its claims won't guard custom containers. Please report this with your " + plugin + " version.");
    }
}
