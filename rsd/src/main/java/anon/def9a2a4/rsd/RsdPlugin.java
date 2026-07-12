package anon.def9a2a4.rsd;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RedstoneDisplays companion. All indicator block definitions live in DefCoreLib (the
 * {@code redstonedisplays} namespace, shipped recipe-less). This plugin's only job is to enable
 * their crafting recipes, so a server that doesn't install it still has the blocks (command-only,
 * via {@code /defcorelib give}) but can't craft them.
 */
public final class RsdPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        new Metrics(this, 0); // TODO: register a real bStats plugin id before publishing
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            getLogger().severe("DefCoreLib not present; RedstoneDisplays recipes cannot be enabled.");
            return;
        }
        core.getRegistry().enableRecipes("redstonedisplays");
        getLogger().info("RedstoneDisplays recipes enabled.");
    }

    @Override
    public void onDisable() {
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core != null) core.getRegistry().disableRecipes("redstonedisplays");
    }
}
