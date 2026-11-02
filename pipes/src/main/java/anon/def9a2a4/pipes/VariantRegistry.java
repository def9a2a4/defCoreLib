package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.fluid.FluidType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class VariantRegistry {
    private final Logger logger;
    private final Map<String, PipeVariant> variants = new LinkedHashMap<>();
    private final Set<String> disabled = new HashSet<>();

    public VariantRegistry(Logger logger) {
        this.logger = logger;
    }

    /**
     * Load every pipe type from pipes.yml's {@code blocks:} — each block with a {@code pipe:} section is a
     * variant. Disabled ids (config.yml {@code disabled-pipes}) still load, so pipes already in the world
     * keep working; they are only withheld from crafting and {@code /pipes give} (see {@link #isEnabled}).
     */
    public void loadFromBlocks(ConfigurationSection blocks, Collection<String> disabledIds) {
        variants.clear();
        disabled.clear();

        if (blocks == null) {
            logger.warning("No blocks section found in pipes.yml!");
            return;
        }

        for (String variantId : blocks.getKeys(false)) {
            ConfigurationSection section = blocks.getConfigurationSection(variantId + ".pipe");
            if (section == null) continue;   // not a pipe (no transport behaviour)

            try {
                PipeVariant variant = parseVariant(variantId, section);
                variants.put(variantId, variant);
            } catch (Exception e) {
                logger.severe("Failed to load variant '" + variantId + "': " + e.getMessage());
            }
        }

        for (String id : disabledIds) {
            if (variants.containsKey(id)) disabled.add(id);
            else logger.warning("disabled-pipes: unknown pipe type '" + id + "' — ignored");
        }

        logger.info("Loaded " + variants.size() + " pipe variant(s)"
            + (disabled.isEmpty() ? "" : ", " + disabled.size() + " disabled"));
    }

    private PipeVariant parseVariant(String id, ConfigurationSection section) {
        String behaviorStr = section.getString("behavior", "REGULAR");
        BehaviorType behavior;
        try {
            behavior = BehaviorType.valueOf(behaviorStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid behavior type: " + behaviorStr);
        }

        int intervalTicks = Math.max(1, section.getInt("transfer.interval-ticks", 10));
        int itemsPerTransfer = Math.max(1, section.getInt("transfer.items-per-transfer", 1));

        // Fluid capability (pump-driven transport): absent list = item-only pipe.
        Set<FluidType> fluids = EnumSet.noneOf(FluidType.class);
        for (String name : section.getStringList("fluids")) {
            FluidType fluid = FluidType.fromName(name);
            if (fluid == null) {
                logger.warning("Variant '" + id + "': unknown fluid '" + name + "' — ignored");
            } else {
                fluids.add(fluid);
            }
        }

        // Filter capability (selective extraction): absent section = ordinary pipe.
        PipeVariant.FilterSpec filter = null;
        ConfigurationSection filterSection = section.getConfigurationSection("filter");
        if (filterSection != null) {
            if (behavior != BehaviorType.REGULAR) {
                logger.warning("Variant '" + id + "': filter requires behavior REGULAR — filter ignored");
            } else {
                int slots = Math.max(1, filterSection.getInt("slots", 5));
                boolean allowBlacklist = filterSection.getBoolean("allow-blacklist-toggle", false);
                boolean allowExact = filterSection.getBoolean("allow-exact-toggle", false);
                filter = new PipeVariant.FilterSpec(slots, allowBlacklist, allowExact);
            }
        }

        return new PipeVariant(id, behavior, intervalTicks, itemsPerTransfer,
            Collections.unmodifiableSet(fluids), filter);
    }

    public PipeVariant getVariant(String id) {
        return variants.get(id);
    }

    public Collection<PipeVariant> getAllVariants() {
        return Collections.unmodifiableCollection(variants.values());
    }

    /** False for a type listed in config.yml {@code disabled-pipes}: uncraftable and not handed out. */
    public boolean isEnabled(String id) {
        return variants.containsKey(id) && !disabled.contains(id);
    }

    public boolean hasVariants() {
        return !variants.isEmpty();
    }
}
