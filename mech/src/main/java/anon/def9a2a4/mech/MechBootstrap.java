package anon.def9a2a4.mech;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * Registers the bundled {@code mech} datapack (advancement definitions) at bootstrap. Because this
 * runs only when the Mechanism plugin jar is present, the {@code mech:*} advancements exist only when
 * the plugin is installed — remove the jar and they are never discovered. The datapack ships ONLY
 * here (never in DefCoreLib core, which is always loaded), which is what gates the advancements on
 * the Mechanism plugin.
 */
@SuppressWarnings("UnstableApiUsage")
public class MechBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                URI uri = getClass().getResource("/mech_datapack").toURI();
                event.registrar().discoverPack(uri, "mech");
            } catch (Exception e) {
                context.getLogger().warn("Failed to register mech datapack: {}", e.getMessage());
            }
        });
    }
}
