package anon.def9a2a4.pipes.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Global configuration from config.yml.
 * Note: Per-variant transfer settings are now in the variants section
 * and are managed by PipeVariant and VariantRegistry.
 */
public class PipeConfig {
    private final boolean debugParticles;
    private final int particleInterval;

    // Recipe unlock settings
    private final String unlockAdvancement;
    private final boolean showUnlockMessage;
    private final String unlockMessage;

    // World filter settings
    private final boolean worldFilterEnabled;
    private final boolean worldFilterIsAllowlist;
    private final Set<String> worldFilterList;

    // Performance settings
    private final int sourceEmptySleepTicks;
    private final int destFullSleepTicks;
    private final int endRecheckSleepTicks;

    public PipeConfig(FileConfiguration config) {
        this.debugParticles = config.getBoolean("global.debug.particles", false);
        this.particleInterval = config.getInt("global.debug.particle-interval", 10);

        // Recipe unlock settings
        this.unlockAdvancement = config.getString("recipes.unlock-advancement", "minecraft:story/smelt_iron");
        this.showUnlockMessage = config.getBoolean("recipes.show-unlock-message", true);
        this.unlockMessage = config.getString("recipes.unlock-message", "<gold>You've unlocked pipe crafting recipes!");

        // World filter settings
        String mode = config.getString("worlds.mode", "");
        if (mode.equalsIgnoreCase("allowlist") || mode.equalsIgnoreCase("blocklist")) {
            this.worldFilterEnabled = true;
            this.worldFilterIsAllowlist = mode.equalsIgnoreCase("allowlist");
            List<String> list = config.getStringList("worlds.list");
            this.worldFilterList = new HashSet<>(list);
        } else {
            this.worldFilterEnabled = false;
            this.worldFilterIsAllowlist = false;
            this.worldFilterList = Set.of();
        }

        // Performance settings
        this.sourceEmptySleepTicks = config.getInt("performance.sleep.source-empty-ticks", 60);
        this.destFullSleepTicks = config.getInt("performance.sleep.dest-full-ticks", 80);
        this.endRecheckSleepTicks = config.getInt("performance.sleep.end-recheck-ticks", 40);
    }

    public boolean isDebugParticles() {
        return debugParticles;
    }

    public int getParticleInterval() {
        return particleInterval;
    }

    public String getUnlockAdvancement() {
        return unlockAdvancement;
    }

    public boolean isShowUnlockMessage() {
        return showUnlockMessage;
    }

    public String getUnlockMessage() {
        return unlockMessage;
    }

    public boolean isUnlockEnabled() {
        return unlockAdvancement != null && !unlockAdvancement.isEmpty()
               && !unlockAdvancement.equalsIgnoreCase("none");
    }

    public int getSourceEmptySleepTicks() {
        return sourceEmptySleepTicks;
    }

    public int getDestFullSleepTicks() {
        return destFullSleepTicks;
    }

    public int getEndRecheckSleepTicks() {
        return endRecheckSleepTicks;
    }

    /**
     * Check if pipes are enabled in the given world.
     * If no world filter is configured, all worlds are enabled.
     */
    public boolean isWorldEnabled(String worldName) {
        if (!worldFilterEnabled) return true;
        boolean inList = worldFilterList.contains(worldName);
        return worldFilterIsAllowlist ? inList : !inList;
    }
}
