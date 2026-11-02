package anon.def9a2a4.corelib;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fractional-age path a runtime spin override drives: it must pose a display exactly where the
 * whole-tick path does at whole ticks (so switching to an override never snaps), and move smoothly
 * between them.
 */
class DisplayAnimationSpinTest {

    private static final Matrix4f BASE = new Matrix4f().translate(0.5f, 0, 0).scale(0.4f, 1, 1);

    @Test
    void fractionalAgeMatchesWholeTicks() {
        DisplayAnimation spin = Animations.rotate(new Vector3f(0, 0, 1), 6f, 0.1f);
        for (long t = -50; t <= 50; t += 7) {
            Matrix4f whole = new Matrix4f(), frac = new Matrix4f();
            spin.apply(BASE, t, whole);
            spin.apply(BASE, (double) t, frac);
            assertTrue(whole.equals(frac, 1e-5f), "tick " + t);
        }
    }

    @Test
    void halfTickIsHalfwayBetween() {
        DisplayAnimation spin = Animations.rotate(new Vector3f(1, 0, 0), 10f, 0f);
        Matrix4f half = new Matrix4f();
        spin.apply(new Matrix4f(), 2.5, half);
        Matrix4f expected = new Matrix4f().rotate((float) Math.toRadians(25), 1, 0, 0);
        assertTrue(expected.equals(half, 1e-5f));
    }

    @Test
    void rotateReportsItsAuthoredSpeedAndOthersDoNot() {
        assertEquals(6f, Animations.rotate(new Vector3f(0, 1, 0), 6f, 0f).degreesPerTick());
        DisplayAnimation custom = (base, age, out) -> out.set(base);
        assertTrue(Float.isNaN(custom.degreesPerTick()));
    }
}
