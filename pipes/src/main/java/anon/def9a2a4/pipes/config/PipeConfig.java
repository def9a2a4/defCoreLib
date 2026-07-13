package anon.def9a2a4.pipes.config;

import anon.def9a2a4.corelib.WorldFilter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class PipeConfig {
    private final boolean debugParticles;
    private final int particleInterval;
    private final WorldFilter worldFilter;
    private final int sourceEmptySleepTicks;
    private final int destFullSleepTicks;
    private final int endRecheckSleepTicks;

    public PipeConfig(FileConfiguration config) {
        this.debugParticles = config.getBoolean("global.debug.particles", false);
        this.particleInterval = config.getInt("global.debug.particle-interval", 10);

        String mode = config.getString("worlds.mode", "");
        if ("allowlist".equalsIgnoreCase(mode)) {
            List<String> list = config.getStringList("worlds.list");
            this.worldFilter = WorldFilter.allowlist(list);
        } else if ("blocklist".equalsIgnoreCase(mode)) {
            List<String> list = config.getStringList("worlds.list");
            this.worldFilter = WorldFilter.blocklist(list);
        } else {
            this.worldFilter = null;
        }

        this.sourceEmptySleepTicks = config.getInt("performance.sleep.source-empty-ticks", 60);
        this.destFullSleepTicks = config.getInt("performance.sleep.dest-full-ticks", 80);
        this.endRecheckSleepTicks = config.getInt("performance.sleep.end-recheck-ticks", 40);
    }

    public boolean isDebugParticles() { return debugParticles; }
    public int getParticleInterval() { return particleInterval; }
    public WorldFilter getWorldFilter() { return worldFilter; }
    public int getSourceEmptySleepTicks() { return sourceEmptySleepTicks; }
    public int getDestFullSleepTicks() { return destFullSleepTicks; }
    public int getEndRecheckSleepTicks() { return endRecheckSleepTicks; }
}
