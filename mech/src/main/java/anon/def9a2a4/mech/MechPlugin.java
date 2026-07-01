package anon.def9a2a4.mech;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mechanism companion. All rotation-mechanism content (rotation blocks + power network, glue, the
 * mechanism minecart) lives in DefCoreLib under the {@code mech} namespace, shipped recipe-less.
 * This plugin enables those recipes.
 *
 * <p>Large/huge windmills are gated on the bbanners plugin: the tier swap that upgrades a windmill
 * by the banner used is only activated when bbanners is present (a soft dependency, so bbanners
 * loads first if installed). Without it, plain windmills still craft; large/huge ones are simply
 * unobtainable.
 */
public final class MechPlugin extends JavaPlugin {

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
                : " (BetterBanners absent - large/huge windmills unavailable)."));
    }

    @Override
    public void onDisable() {
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core != null) {
            core.getRegistry().disableRecipes("mech");
            core.getRegistry().setWindmillTierEnabled(false);
        }
    }
}
