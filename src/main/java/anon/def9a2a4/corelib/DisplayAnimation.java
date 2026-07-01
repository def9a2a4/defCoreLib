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

    /** One full cycle, for converting a phase given in CYCLE FRACTIONS (turns) to radians:
     *  phase 0.25 = quarter cycle, 0.5 = half (anti-phase), 1.0 = full cycle. */
    private static final float TAU = (float) (2.0 * Math.PI);

    /** Continuous rotation around an axis.
     *
     * <p>Uses pre-multiply (R × base) so the entire display — including its
     * translation offset from the block center — rotates as a rigid body.
     * This is required for anisotropic-scale displays (e.g. blade-shaped) to
     * maintain their shape while rotating, and for multiple displays that
     * should orbit together as a unit.
     *
     * <p>For displays whose translation lies on the rotation axis (e.g. a
     * crystal at [0, 0.8, 0] rotating around Y), pre- and post-multiply are
     * visually equivalent — the center is unaffected by the rotation.
     */
    static DisplayAnimation rotate(Vector3f axis, float degreesPerTick, float phaseTurns) {
        float radiansPerTick = (float) Math.toRadians(degreesPerTick);
        float phase = phaseTurns * TAU;
        Vector3f norm = normalizedAxis(axis);
        return (base, tickAge, output) -> {
            new Matrix4f().rotate(radiansPerTick * tickAge + phase, norm.x, norm.y, norm.z).mul(base, output);
        };
    }

    /** Vertical bob (sine wave on Y axis).
     *
     * <p>Pre-multiplies (T × base) so the bob is a WORLD-space vertical translation,
     * independent of the display's own scale/rotation — every bobbing display moves by
     * the same {@code amplitude}. (Post-multiplying would push dy through the base's
     * scale+rotation, so a scaled or flipped display would bob by a different magnitude /
     * opposite direction.) Mirrors {@link #rotate}'s rigid-body approach.
     *
     * <p>{@code phaseTurns} offsets the sine (0.5 = anti-phase), so two displays on the
     * same block can bob together or in opposition. */
    static DisplayAnimation bob(float amplitude, int periodTicks, float phaseTurns) {
        float omega = omega(periodTicks);
        float phase = phaseTurns * TAU;
        return (base, tickAge, output) -> {
            float dy = amplitude * (float) Math.sin(omega * tickAge + phase);
            new Matrix4f().translation(0, dy, 0).mul(base, output);
        };
    }

    /** Pulsing scale between min and max. */
    static DisplayAnimation pulse(float minScale, float maxScale, int periodTicks, float phaseTurns) {
        float mid = (minScale + maxScale) * 0.5f;
        float amp = (maxScale - minScale) * 0.5f;
        float omega = omega(periodTicks);
        float phase = phaseTurns * TAU;
        return (base, tickAge, output) -> {
            float s = mid + amp * (float) Math.sin(omega * tickAge + phase);
            output.set(base).scale(s);
        };
    }

    /** Circular orbit around an axis. */
    static DisplayAnimation orbit(float radius, int periodTicks, Vector3f axis, float phaseTurns) {
        float omega = omega(periodTicks);
        float phase = phaseTurns * TAU;
        Vector3f norm = normalizedAxis(axis);
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
            float angle = omega * tickAge + phase;
            float dx = radius * ((float) Math.cos(angle) * tangent.x + (float) Math.sin(angle) * bitangent.x);
            float dy = radius * ((float) Math.cos(angle) * tangent.y + (float) Math.sin(angle) * bitangent.y);
            float dz = radius * ((float) Math.cos(angle) * tangent.z + (float) Math.sin(angle) * bitangent.z);
            output.set(base).translate(dx, dy, dz);
        };
    }

    /** Layer multiple animations: each receives the previous output as its base. */
    static DisplayAnimation compose(DisplayAnimation... layers) {
        if (layers.length == 0) {
            throw new IllegalArgumentException("compose animation requires at least one layer");
        }
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

    /** Angular frequency for a periodic animation, rejecting a non-positive period
     *  (which would divide by zero → NaN → an invisible display every tick). */
    private static float omega(int periodTicks) {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("animation period must be positive, got " + periodTicks);
        }
        return (float) (2.0 * Math.PI / periodTicks);
    }

    /** Normalize a rotation axis, rejecting a zero/degenerate vector (which would
     *  normalize to NaN and produce an invisible display every tick). */
    private static Vector3f normalizedAxis(Vector3f axis) {
        if (axis.lengthSquared() < 1.0e-12f) {
            throw new IllegalArgumentException("animation axis must be non-zero, got " + axis);
        }
        return new Vector3f(axis).normalize();
    }
}
