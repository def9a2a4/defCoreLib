package anon.def9a2a4.bbanners;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Banners companion. The banner subsystem (flag banners + large/huge banner crafting) lives in
 * DefCoreLib but ships dormant. This plugin activates it. Cleanup of already-placed banners stays
 * live in core regardless, so removing this plugin never orphans a placed banner's display entity.
 */
public final class BbannersPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        new Metrics(this, 32319);
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core == null) {
            getLogger().severe("DefCoreLib not present — banner functionality cannot be enabled.");
            return;
        }
        core.activateBanners();
        getLogger().info("Banner functionality enabled.");
    }

    @Override
    public void onDisable() {
        CoreLibPlugin core = CoreLibPlugin.getInstance();
        if (core != null) core.deactivateBanners();
    }
}
