package anon.def9a2a4.corelib;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pipe types live in pipes.yml (a {@code pipe:} section per block); config.yml holds only general settings.
 * Pre-0.5.0 the types were listed in config.yml, and an existing server's copy silently left out every type
 * added later — this pins the split so a new pipe can't be added to only one of the two files again.
 */
class PipesDefinitionTest {

    private static final File RES = new File(System.getProperty("repoRoot"), "pipes/src/main/resources");

    @Test
    void everyPipeBlockDefinesItsTransportBehaviour() {
        YamlConfiguration pipes = YamlConfiguration.loadConfiguration(new File(RES, "pipes.yml"));
        ConfigurationSection blocks = pipes.getConfigurationSection("blocks");
        assertNotNull(blocks);
        assertFalse(blocks.getKeys(false).isEmpty());
        for (String id : blocks.getKeys(false)) {
            ConfigurationSection pipe = blocks.getConfigurationSection(id + ".pipe");
            assertNotNull(pipe, id + " has no pipe: section");
            String behavior = pipe.getString("behavior");
            assertTrue(Set.of("REGULAR", "CORNER").contains(behavior), id + ": behavior " + behavior);
            assertTrue(pipe.getInt("transfer.items-per-transfer") >= 1, id + ": items-per-transfer");
            if (pipe.contains("filter")) {
                assertEquals("REGULAR", behavior, id + ": a filter pipe must be REGULAR");
                assertTrue(pipe.getInt("filter.slots") >= 1, id + ": filter slots");
            }
        }

        ConfigurationSection conversions = pipes.getConfigurationSection("cauldron_conversions");
        assertNotNull(conversions);
        for (String from : conversions.getKeys(false)) {
            assertTrue(blocks.contains(from), "conversion source " + from);
            assertTrue(blocks.contains(conversions.getString(from)), "conversion target of " + from);
        }
    }

    @Test
    void configHoldsNoPipeTypes() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(RES, "config.yml"));
        assertFalse(config.contains("variants"), "pipe types belong in pipes.yml");
        assertFalse(config.contains("cauldron-conversions"), "conversions belong in pipes.yml");
        assertTrue(config.isList("disabled-pipes"));
    }
}
