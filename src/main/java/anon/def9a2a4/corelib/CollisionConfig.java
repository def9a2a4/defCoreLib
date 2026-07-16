package anon.def9a2a4.corelib;

import org.bukkit.configuration.ConfigurationSection;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Per-block collider descriptor for a mechanism block.
 *
 * <p>Ported from BlockShips' {@code blockconfig.CollisionConfig} (the two repos share no module and
 * copy such helpers between them — see {@code TeleportCompat}). Kept API-compatible with the
 * {@code true} / {@code false} / {@code {size, offset}} YAML form used there so collider entries port
 * verbatim. This copy has diverged (corelib-specific parsing, no ship dependencies).</p>
 *
 * <p>The collider is realised as a {@link org.bukkit.entity.Shulker}: {@code size} scales its hitbox
 * via the {@code generic.scale} attribute (see {@link ScaleAttributeCompat}) and {@code offset} is a
 * block-local displacement of the carrier. The Shulker box is feet-anchored in Y and horizontally
 * centred, so {@code size 0.5, offset [0,0,0]} is a bottom slab and {@code offset [0,0.5,0]} lifts it
 * to the top half — identical to BlockShips' numbers.</p>
 */
public final class CollisionConfig {

    private static final Logger LOG = Logger.getLogger("DefCoreLib");

    public final boolean enabled;
    public final float size;
    public final Vector3f offset;

    /** Full block: enabled, size 1.0, no offset. The default for any unspecified block. */
    public static final CollisionConfig DEFAULT = new CollisionConfig(true, 1.0f, new Vector3f(0, 0, 0));

    /** No collider. */
    public static final CollisionConfig NONE = new CollisionConfig(false, 1.0f, new Vector3f(0, 0, 0));

    public CollisionConfig(boolean enabled, float size, Vector3f offset) {
        this.enabled = enabled;
        this.size = size;
        this.offset = offset;
    }

    public boolean enabled() { return enabled; }
    public float size() { return size; }
    public Vector3f offset() { return offset; }

    /** True when this collider is a plain full block — lets the spawn path skip the scale attribute. */
    public boolean isFullBlock() {
        return enabled && size == 1.0f && offset.x == 0 && offset.y == 0 && offset.z == 0;
    }

    /**
     * Parse a collider value from YAML. Accepts:
     * <ul>
     *   <li>{@code null} → {@link #DEFAULT} (unspecified ⇒ full block)</li>
     *   <li>{@code Boolean} → {@link #DEFAULT} / {@link #NONE}</li>
     *   <li>a {@link ConfigurationSection} or {@link Map} with {@code size} and/or {@code offset}</li>
     * </ul>
     * The {@code Map} form is required because conditional-rule colliders arrive as raw maps from
     * {@code getList(...)} rather than as sections.
     */
    public static CollisionConfig fromYaml(Object value) {
        if (value == null) {
            return DEFAULT;
        }
        if (value instanceof Boolean b) {
            return b ? DEFAULT : NONE;
        }
        if (value instanceof ConfigurationSection sec) {
            float size = (float) sec.getDouble("size", 1.0);
            Vector3f offset = parseOffset(sec.getList("offset"));
            return new CollisionConfig(true, size, offset);
        }
        if (value instanceof Map<?, ?> map) {
            float size = 1.0f;
            Object sizeObj = map.get("size");
            if (sizeObj instanceof Number n) {
                size = n.floatValue();
            }
            Vector3f offset = parseOffset(map.get("offset"));
            return new CollisionConfig(true, size, offset);
        }
        // A bare scalar (e.g. `chest: 0.9` instead of `chest: {size: 0.9}`) reaches here — warn rather
        // than silently treat it as a full block, since it's an easy authoring mistake.
        LOG.warning("Unrecognized collider value '" + value + "' (use true/false or {size, offset}); "
            + "treating as full block");
        return DEFAULT;
    }

    private static Vector3f parseOffset(Object offsetObj) {
        if (offsetObj instanceof List<?> list && list.size() >= 3) {
            return new Vector3f(toFloat(list.get(0)), toFloat(list.get(1)), toFloat(list.get(2)));
        }
        if (offsetObj != null) {
            LOG.warning("Ignoring malformed collider offset " + offsetObj + " (expected [x, y, z]); using [0, 0, 0]");
        }
        return new Vector3f(0, 0, 0);
    }

    private static float toFloat(Object o) {
        return o instanceof Number n ? n.floatValue() : 0f;
    }

    @Override
    public String toString() {
        return enabled
            ? String.format("CollisionConfig{size=%.2f, offset=[%.2f, %.2f, %.2f]}", size, offset.x, offset.y, offset.z)
            : "CollisionConfig{disabled}";
    }
}
