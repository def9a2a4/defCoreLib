package anon.def9a2a4.vslab;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vertical Slabs companion. All slab block definitions live in DefCoreLib (the {@code verticalslabs}
 * namespace, shipped recipe-less). This plugin's only job is to enable their crafting recipes, so a
 * server that doesn't install it still has the blocks (command-only) but can't craft them.
 */
public final class VslabPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        new Metrics(this, 32318);
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            getLogger().severe("DefCoreLib not present — vertical slab recipes cannot be enabled.");
            return;
        }
        core.getRegistry().enableRecipes("verticalslabs");
        getLogger().info("Vertical slab recipes enabled.");
    }

    @Override
    public void onDisable() {
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core != null) core.getRegistry().disableRecipes("verticalslabs");
    }
}
