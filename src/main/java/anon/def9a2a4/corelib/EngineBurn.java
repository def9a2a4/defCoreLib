package anon.def9a2a4.corelib;

/**
 * Fuel burn for power actually used: how much of a network's engine output its machines are drawing,
 * and the fractional accumulator that turns that into whole fuel ticks. Bukkit-free.
 */
final class EngineBurn {
    private EngineBurn() {}

    /**
     * The share of the fuel-burning supply that is being used, 0..1.
     *
     * <p>Free power (windmills, water wheels) covers the load first, so an engine beside a windmill only
     * burns for the shortfall. Every engine on the network burns at this same fraction of its own rate,
     * which splits the total in proportion to engine size without the engines coordinating.
     *
     * @param minFloor the least an engine burns once anything draws on it (0 = none)
     */
    static double fraction(double activeLoad, double freeSupply, double engineSupply, double minFloor) {
        if (engineSupply <= 0) return 0;
        double f = (activeLoad - freeSupply) / engineSupply;
        if (f <= 0) return 0;
        return Math.min(1.0, Math.max(f, minFloor));
    }

    /** Whole fuel ticks to burn after adding {@code frac} to {@code acc[0]}; the remainder stays in it. */
    static int step(double[] acc, double frac) {
        acc[0] += Math.max(0, frac);
        // The epsilon keeps float rounding from losing a burn: 0.3 added ten times is 0.9999999...
        int whole = (int) Math.floor(acc[0] + 1e-9);
        acc[0] = Math.max(0, acc[0] - whole);
        return whole;
    }
}
