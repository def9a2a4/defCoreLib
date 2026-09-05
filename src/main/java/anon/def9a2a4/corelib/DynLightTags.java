package anon.def9a2a4.corelib;

/**
 * Scoreboard-tag contract for the optional DynLight companion plugin
 * ({@code https://github.com/def9a2a4/dynlight}).
 *
 * <p>DynLight's {@code TagLightDetector} scans entities for a {@code dynlight:<level>} scoreboard
 * tag on spawn ({@code EntitySpawnEvent}) and drops the light on removal ({@code EntityRemoveEvent}),
 * tracking the entity's movement itself every tick. Detection also happens via DynLight's chunk-load
 * re-scan ({@code ChunkLoadEvent} / {@code EntitiesLoadEvent} / player join all re-run the registered
 * detectors over the chunk's entities), so a persistent tagged entity relights on reload — no spawn
 * event required, and no relight-on-recovery code needed here. Tagging our moving render entities is
 * therefore a zero-dependency integration — no {@code depend}/{@code softdepend}, no compile-time
 * coupling; the tag is inert when DynLight is absent. This mirrors how BlockShips lights its ships.
 *
 * <p>For a freshly created entity, spawn is still the only detection point in that tick — so the tag
 * must be present when the entity is created (add it inside the {@code world.spawn(..., consumer)}
 * lambda), and light that toggles over time must be represented by spawning/removing a tagged entity
 * rather than mutating a tag on a live one.
 */
final class DynLightTags {

    /** DynLight's tag prefix. Extended form {@code dynlight:<level>:<radius>:<height>} is also accepted. */
    static final String PREFIX = "dynlight:";

    private DynLightTags() {}

    /** Tag requesting DynLight emit light at this entity's position. Level is clamped 1–15 by DynLight. */
    static String tag(int level) {
        return PREFIX + level;
    }
}
