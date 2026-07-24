package anon.def9a2a4.railbound;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Railbound companion. All content — the coal/blast-furnace fuel carts, minecart trains,
 * junctions, and the destructor rail — lives in DefCoreLib under the {@code railbound} namespace, shipped
 * recipe-less and with the cart runtime gated off. This plugin enables those recipes and activates the
 * cart runtime (fuel carts + minecart trains) via {@code enableCarts()} — mirroring the mech companion.
 */
public final class RailboundPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        new Metrics(this, 32862);
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            getLogger().severe("DefCoreLib not present; Railbound recipes cannot be enabled.");
            return;
        }
        core.getRegistry().enableRecipes("railbound");
        core.enableCarts();
        getLogger().info("Railbound recipes enabled.");
    }

    @Override
    public void onDisable() {
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core != null) {
            core.getRegistry().disableRecipes("railbound");
            core.disableCarts();
        }
    }
}
