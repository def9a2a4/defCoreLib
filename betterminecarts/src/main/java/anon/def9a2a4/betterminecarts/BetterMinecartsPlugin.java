package anon.def9a2a4.betterminecarts;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BetterMinecarts companion. All content — the coal/blast-furnace fuel carts, minecart trains,
 * junctions, and the destructor rail — lives in DefCoreLib under the {@code bmc} namespace, shipped
 * recipe-less and with the cart runtime gated off. This plugin enables those recipes and activates the
 * cart runtime (fuel carts + minecart trains) via {@code enableCarts()} — mirroring the mech companion.
 */
public final class BetterMinecartsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        new Metrics(this, 32862);
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            getLogger().severe("DefCoreLib not present; BetterMinecarts recipes cannot be enabled.");
            return;
        }
        core.getRegistry().enableRecipes("bmc");
        core.enableCarts();
        getLogger().info("BetterMinecarts recipes enabled.");
    }

    @Override
    public void onDisable() {
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core != null) {
            core.getRegistry().disableRecipes("bmc");
            core.disableCarts();
        }
    }
}
