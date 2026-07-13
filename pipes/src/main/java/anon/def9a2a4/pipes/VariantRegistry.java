package anon.def9a2a4.pipes;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

public class VariantRegistry {
    private final Logger logger;
    private final Map<String, PipeVariant> variants = new LinkedHashMap<>();

    public VariantRegistry(Logger logger) {
        this.logger = logger;
    }

    public void loadFromConfig(ConfigurationSection variantsSection) {
        variants.clear();

        if (variantsSection == null) {
            logger.warning("No variants section found in config!");
            return;
        }

        for (String variantId : variantsSection.getKeys(false)) {
            ConfigurationSection section = variantsSection.getConfigurationSection(variantId);
            if (section == null) {
                logger.warning("Invalid variant section: " + variantId);
                continue;
            }

            try {
                PipeVariant variant = parseVariant(variantId, section);
                variants.put(variantId, variant);
            } catch (Exception e) {
                logger.severe("Failed to load variant '" + variantId + "': " + e.getMessage());
            }
        }

        logger.info("Loaded " + variants.size() + " pipe variant(s)");
    }

    private PipeVariant parseVariant(String id, ConfigurationSection section) {
        String behaviorStr = section.getString("behavior", "REGULAR");
        BehaviorType behavior;
        try {
            behavior = BehaviorType.valueOf(behaviorStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid behavior type: " + behaviorStr);
        }

        int intervalTicks = section.getInt("transfer.interval-ticks", 10);
        int itemsPerTransfer = section.getInt("transfer.items-per-transfer", 1);

        return new PipeVariant(id, behavior, intervalTicks, itemsPerTransfer);
    }

    public PipeVariant getVariant(String id) {
        return variants.get(id);
    }

    public Collection<PipeVariant> getAllVariants() {
        return Collections.unmodifiableCollection(variants.values());
    }

    public boolean hasVariants() {
        return !variants.isEmpty();
    }
}
