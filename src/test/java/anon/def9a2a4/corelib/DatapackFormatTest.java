package anon.def9a2a4.corelib;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every Minecraft version CI tests must fall inside the mech datapack's {@code min_format..max_format},
 * or that server flags the advancement pack as incompatible. Adding a version to the matrix without its
 * data-pack format here fails this test on purpose — look the format up, add it, and if it is above
 * max_format bump scripts/gen_advancements.py and regenerate.
 */
class DatapackFormatTest {

    // Data-pack format major per Minecraft version (minecraft.wiki "Pack format").
    private static final Map<String, Integer> DATA_PACK_FORMAT = Map.of(
        "1.21.11", 94,
        "26.1.2", 101,
        "26.2", 107,
        "26.3", 109);

    @Test
    void ciMatrixVersionsAreInsideThePackRange() throws Exception {
        Path root = Path.of(System.getProperty("repoRoot", System.getProperty("user.dir")));
        JsonObject pack = JsonParser.parseString(Files.readString(
            root.resolve("mech/src/main/resources/mech_datapack/pack.mcmeta"))).getAsJsonObject().getAsJsonObject("pack");
        int min = pack.get("min_format").getAsInt();
        int max = pack.get("max_format").getAsInt();

        Map<String, Object> wf = new Yaml().load(Files.readString(root.resolve(".github/workflows/server-test.yml")));
        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) wf.get("jobs");
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) jobs.get("showcase-test");
        assertNotNull(job, "server-test.yml has no `showcase-test` job — update this test if it was renamed");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) job.get("strategy");
        @SuppressWarnings("unchecked")
        List<Object> versions = (List<Object>) ((Map<String, Object>) strategy.get("matrix")).get("minecraft");

        assertFalse(versions.isEmpty());
        for (Object v : versions) {
            // Unquoted, `26.10` would load as the number 26.1 and look up the wrong version.
            assertInstanceOf(String.class, v, "quote matrix version " + v + " in server-test.yml");
            Integer fmt = DATA_PACK_FORMAT.get(String.valueOf(v));
            assertNotNull(fmt, "no data-pack format recorded for CI version " + v + " — add it to this test");
            assertTrue(fmt >= min && fmt <= max,
                "MC " + v + " is data-pack format " + fmt + ", outside the mech pack's " + min + ".." + max);
        }
    }
}
