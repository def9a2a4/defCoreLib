package anon.def9a2a4.corelib;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A transform applied per-tick to a display entity.
 * Works identically in static blocks and mechanisms — just receives a different base matrix.
 */
@FunctionalInterface
public interface DisplayAnimation {
    /**
     * Compute the animated transform.
     *
     * @param base     the unadulterated block-local (or mechanism-relative) transform
     * @param tickAge  ticks since the animation started
     * @param output   write the result here (do not mutate base)
     */
    void apply(Matrix4f base, long tickAge, Matrix4f output);
}

/**
 * Built-in animation factories.
 */
final class Animations {
    private Animations() {}

    /** Continuous rotation around an axis. */
    static DisplayAnimation rotate(Vector3f axis, float degreesPerTick) {
        float radiansPerTick = (float) Math.toRadians(degreesPerTick);
        Vector3f norm = new Vector3f(axis).normalize();
        return (base, tickAge, output) -> {
            output.set(base).rotate(radiansPerTick * tickAge, norm.x, norm.y, norm.z);
        };
    }

    /** Vertical bob (sine wave on Y axis). */
    static DisplayAnimation bob(float amplitude, int periodTicks) {
        float omega = (float) (2.0 * Math.PI / periodTicks);
        return (base, tickAge, output) -> {
            float dy = amplitude * (float) Math.sin(omega * tickAge);
            output.set(base).translate(0, dy, 0);
        };
    }

    /** Pulsing scale between min and max. */
    static DisplayAnimation pulse(float minScale, float maxScale, int periodTicks) {
        float mid = (minScale + maxScale) * 0.5f;
        float amp = (maxScale - minScale) * 0.5f;
        float omega = (float) (2.0 * Math.PI / periodTicks);
        return (base, tickAge, output) -> {
            float s = mid + amp * (float) Math.sin(omega * tickAge);
            output.set(base).scale(s);
        };
    }

    /** Circular orbit around an axis. */
    static DisplayAnimation orbit(float radius, int periodTicks, Vector3f axis) {
        float omega = (float) (2.0 * Math.PI / periodTicks);
        Vector3f norm = new Vector3f(axis).normalize();
        // Build a local coordinate frame: tangent + bitangent perpendicular to axis
        Vector3f tangent = new Vector3f();
        if (Math.abs(norm.y) < 0.9f) {
            new Vector3f(0, 1, 0).cross(norm, tangent).normalize();
        } else {
            new Vector3f(1, 0, 0).cross(norm, tangent).normalize();
        }
        Vector3f bitangent = new Vector3f();
        norm.cross(tangent, bitangent).normalize();

        return (base, tickAge, output) -> {
            float angle = omega * tickAge;
            float dx = radius * ((float) Math.cos(angle) * tangent.x + (float) Math.sin(angle) * bitangent.x);
            float dy = radius * ((float) Math.cos(angle) * tangent.y + (float) Math.sin(angle) * bitangent.y);
            float dz = radius * ((float) Math.cos(angle) * tangent.z + (float) Math.sin(angle) * bitangent.z);
            output.set(base).translate(dx, dy, dz);
        };
    }

    /** Layer multiple animations: each receives the previous output as its base. */
    static DisplayAnimation compose(DisplayAnimation... layers) {
        if (layers.length == 1) return layers[0];
        return (base, tickAge, output) -> {
            layers[0].apply(base, tickAge, output);
            Matrix4f temp = new Matrix4f();
            for (int i = 1; i < layers.length; i++) {
                temp.set(output);
                layers[i].apply(temp, tickAge, output);
            }
        };
    }
}
