package anon.def9a2a4.corelib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Burn-on-load arithmetic: the used share of engine output, and the fractional accumulator. */
class EngineBurnTest {

    @Test
    void idleNetworkBurnsNothing() {
        assertEquals(0.0, EngineBurn.fraction(0, 0, 10, 0));
    }

    @Test
    void halfTheOutputInUseBurnsAtHalfRate() {
        assertEquals(0.5, EngineBurn.fraction(5, 0, 10, 0), 1e-9);
    }

    @Test
    void freeSupplyCoversTheLoadFirst() {
        // 8 load, a windmill giving 5 free: the engine covers the 3 short of it.
        assertEquals(0.3, EngineBurn.fraction(8, 5, 10, 0), 1e-9);
        assertEquals(0.0, EngineBurn.fraction(4, 5, 10, 0), 1e-9);
    }

    @Test
    void clampedToOneAndNoEnginesMeansNoBurn() {
        assertEquals(1.0, EngineBurn.fraction(30, 0, 10, 0));
        assertEquals(0.0, EngineBurn.fraction(5, 0, 0, 0));
    }

    @Test
    void floorAppliesOnlyWhenSomethingDraws() {
        assertEquals(0.25, EngineBurn.fraction(1, 0, 10, 0.25), 1e-9);
        assertEquals(0.0, EngineBurn.fraction(0, 0, 10, 0.25));
    }

    @Test
    void accumulatorGivesExactWholeBurns() {
        double[] acc = new double[1];
        int total = 0;
        for (int i = 0; i < 100; i++) total += EngineBurn.step(acc, 0.3);
        assertEquals(30, total);
        assertEquals(0.0, acc[0], 1e-6);
        // frac 1 is the old behaviour: one burn per engine tick.
        acc[0] = 0;
        for (int i = 0; i < 5; i++) assertEquals(1, EngineBurn.step(acc, 1.0));
    }
}
