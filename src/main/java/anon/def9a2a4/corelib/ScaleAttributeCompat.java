package anon.def9a2a4.corelib;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Cross-version resolver for the {@code generic.scale} entity attribute (added in 1.20.5), used to
 * resize a collider Shulker's hitbox.
 *
 * <p>Ported from the {@code scale} slice of BlockShips' {@code util.AttributeCompat}. Only the scale
 * attribute is resolved here — the BlockShips original also resolves {@code max_health} as a
 * <em>required</em> attribute that throws from static init, which corelib does not want. Pure
 * reflection avoids compile-time binding to constants that differ across versions:
 * <ul>
 *   <li>1.20.4 and earlier: no scale attribute → {@link #getScale()} returns null</li>
 *   <li>1.21.2 and earlier: {@code Attribute} is an enum ({@code GENERIC_SCALE})</li>
 *   <li>1.21.3+: {@code Attribute} is an interface; resolve via {@code Registry.ATTRIBUTE.get("scale")}</li>
 * </ul>
 */
public final class ScaleAttributeCompat {

    private static Attribute scale;
    private static boolean initialized = false;

    private ScaleAttributeCompat() {}

    /** The {@code generic.scale} attribute, or null if unavailable (servers before 1.20.5). */
    public static Attribute getScale() {
        if (!initialized) {
            scale = resolve("scale", "GENERIC_SCALE", "SCALE");
            initialized = true;
        }
        return scale;
    }

    private static Attribute resolve(String registryKey, String legacyEnumName, String newFieldName) {
        Attribute attr = tryRegistryLookup(registryKey);
        if (attr != null) return attr;
        attr = tryEnumLookup(legacyEnumName);
        if (attr != null) return attr;
        attr = tryFieldAccess(newFieldName);
        if (attr != null) return attr;
        return tryFieldAccess(legacyEnumName);
    }

    /** Strategy 1: {@code Registry.ATTRIBUTE.get(NamespacedKey)} (1.21.x). */
    private static Attribute tryRegistryLookup(String key) {
        try {
            Class<?> registryClass = Class.forName("org.bukkit.Registry");
            Field attributeField = registryClass.getField("ATTRIBUTE");
            Object attributeRegistry = attributeField.get(null);
            if (attributeRegistry == null) return null;
            Method getMethod = attributeRegistry.getClass().getMethod("get", NamespacedKey.class);
            Object result = getMethod.invoke(attributeRegistry, NamespacedKey.minecraft(key));
            if (result instanceof Attribute a) return a;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Strategy 2: {@code Enum.valueOf(Attribute, "GENERIC_SCALE")} (1.21.2 and earlier). */
    private static Attribute tryEnumLookup(String enumName) {
        try {
            if (!Attribute.class.isEnum()) return null;
            Method valueOf = Enum.class.getMethod("valueOf", Class.class, String.class);
            Object result = valueOf.invoke(null, Attribute.class, enumName);
            if (result instanceof Attribute a) return a;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Strategy 3: static field access (1.21.3+ interface). */
    private static Attribute tryFieldAccess(String fieldName) {
        try {
            Field field = Attribute.class.getField(fieldName);
            Object value = field.get(null);
            if (value instanceof Attribute a) return a;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
