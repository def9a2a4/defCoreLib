package anon.def9a2a4.corelib;

import anon.def9a2a4.corelib.RotationNetwork.Axis;
import anon.def9a2a4.corelib.RotationNetwork.SpinDirection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Spin direction across gearboxes: {@link RotationSolver#solve} and the shared
 *  {@link RotationSolver#deriveAcrossGearboxes} helper (also used by {@link RotationNetwork}). */
class RotationSolverTest {

    private static final SpinDirection CW = SpinDirection.CW, CCW = SpinDirection.CCW;

    private static RotationSolver.Node shaft(int x, int y, int z, Axis a) {
        return new RotationSolver.Node(x, y, z, a, 0, 0, false, false, false, null, null);
    }
    private static RotationSolver.Node gear(int x, int y, int z, Axis a) {
        return new RotationSolver.Node(x, y, z, a, 0, 0, true, false, false, null, null);
    }
    private static RotationSolver.Node gbox(int x, int y, int z) {
        return new RotationSolver.Node(x, y, z, Axis.Y, 0, 0, false, true, false, null, null);
    }
    private static RotationSolver.Node src(int x, int y, int z, Axis a, SpinDirection d) {
        return new RotationSolver.Node(x, y, z, a, 4, 0, false, false, false, null, d);
    }
    private static RotationSolver.Node sink(int x, int y, int z, Axis a) {
        return new RotationSolver.Node(x, y, z, a, 0, 2, false, false, false, null, null);
    }

    private record S(SpinDirection dir, boolean jammed, boolean powered, int surplus) {}

    /** Solve and key results by cell, so tests read by position and shuffled inputs compare equal. */
    private static Map<Long, S> solve(List<RotationSolver.Node> nodes) {
        var r = RotationSolver.solve(nodes);
        Map<Long, S> m = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            m.put(nodes.get(i).cellKey(), new S(r.direction()[i], r.jammed()[i], r.powered()[i], r.surplus()[i]));
        }
        return m;
    }

    private static S at(Map<Long, S> m, int x, int y, int z) {
        return m.get(RotationSolver.pack(x, y, z));
    }

    // ── scenarios ──

    private static List<RotationSolver.Node> straight(SpinDirection d) {
        return List.of(src(0, 0, 0, Axis.X, d), shaft(1, 0, 0, Axis.X), shaft(2, 0, 0, Axis.X), gbox(3, 0, 0),
                shaft(4, 0, 0, Axis.X), shaft(5, 0, 0, Axis.X), sink(6, 0, 0, Axis.X));
    }

    private static List<RotationSolver.Node> corner(SpinDirection d) {
        return List.of(src(-1, 0, 0, Axis.X, d), gbox(0, 0, 0), shaft(0, 0, 1, Axis.Z), shaft(0, 0, 2, Axis.Z),
                sink(0, 0, 3, Axis.Z));
    }

    /** Source → gearbox → two meshed Z gears (one reversal) → shaft → gearbox → Y arm. */
    private static List<RotationSolver.Node> twoGearboxes(SpinDirection d) {
        return List.of(src(-1, 0, 0, Axis.X, d), gbox(0, 0, 0), gear(0, 0, 1, Axis.Z), gear(1, 0, 1, Axis.Z),
                shaft(1, 0, 2, Axis.Z), gbox(1, 0, 3), shaft(1, 1, 3, Axis.Y), shaft(1, 2, 3, Axis.Y),
                sink(1, 3, 3, Axis.Y));
    }

    /** Source A on the X arm, source B on the Z arm, a consumer up the Y arm. */
    private static List<RotationSolver.Node> twoSources(SpinDirection a, SpinDirection b) {
        return List.of(src(-1, 0, 0, Axis.X, a), src(0, 0, -1, Axis.Z, b), gbox(0, 0, 0),
                shaft(0, 1, 0, Axis.Y), shaft(0, 2, 0, Axis.Y), sink(0, 3, 0, Axis.Y));
    }

    // ── solve ──

    @Test
    void straightThroughGearboxFollowsSource() {
        for (SpinDirection d : SpinDirection.values()) {
            var m = solve(straight(d));
            assertEquals(d, at(m, 4, 0, 0).dir(), "arm past the gearbox follows " + d);
            assertEquals(d, at(m, 6, 0, 0).dir());
            assertTrue(at(m, 6, 0, 0).powered());
        }
    }

    @Test
    void cornerArmFollowsSource() {
        assertEquals(CW, at(solve(corner(CW)), 0, 0, 2).dir());
        assertEquals(CCW, at(solve(corner(CCW)), 0, 0, 2).dir());
        assertTrue(at(solve(corner(CW)), 0, 0, 3).powered());
    }

    @Test
    void twoGearboxesInARowCarryDirectionThroughAGearReversal() {
        var h1 = solve(twoGearboxes(CW));
        assertEquals(CW, at(h1, 0, 0, 1).dir());
        assertEquals(CCW, at(h1, 1, 0, 1).dir(), "meshed same-axis gear reverses");
        assertEquals(CCW, at(h1, 1, 2, 3).dir(), "final arm follows the reversed gear");
        assertFalse(at(h1, 1, 2, 3).jammed());
        assertEquals(CW, at(solve(twoGearboxes(CCW)), 1, 2, 3).dir());
    }

    @Test
    void opposingSourcesOnPerpendicularArmsDoNotJam() {
        var t = solve(twoSources(CW, CCW));
        S consumer = at(t, 0, 3, 0);
        assertFalse(consumer.jammed());
        assertTrue(consumer.powered());
        assertEquals(6, consumer.surplus(), "both sources pool: 8 − 2");
        assertEquals(CW, at(t, -1, 0, 0).dir(), "each source keeps its own direction");
        assertEquals(CCW, at(t, 0, 0, -1).dir());
        // Third arm is contested; the tie-break goes to the lowest anchor (A at x = −1).
        assertEquals(CW, at(t, 0, 2, 0).dir());
        assertEquals(CCW, at(solve(twoSources(CCW, CW)), 0, 2, 0).dir());
    }

    @Test
    void agreeingSourcesDriveTheThirdArm() {
        assertEquals(CCW, at(solve(twoSources(CCW, CCW)), 0, 2, 0).dir());
    }

    @Test
    void contestedWinnerStableWhenAnUnrelatedNodeIsAdded() {
        List<RotationSolver.Node> plus = new ArrayList<>(twoSources(CW, CCW));
        plus.add(shaft(-2, 0, 0, Axis.X));
        assertEquals(CW, at(solve(plus), 0, 2, 0).dir());
    }

    @Test
    void resultDoesNotDependOnNodeOrder() {
        Random rnd = new Random(42);
        for (var nodes : List.of(straight(CCW), corner(CCW), twoGearboxes(CCW), twoSources(CW, CCW))) {
            var base = solve(nodes);
            for (int t = 0; t < 200; t++) {
                List<RotationSolver.Node> copy = new ArrayList<>(nodes);
                Collections.shuffle(copy, rnd);
                assertEquals(base, solve(copy));
            }
        }
    }

    // ── deriveAcrossGearboxes ──

    @Test
    void helperLeavesEverythingUnreachedWithoutSources() {
        var d = RotationSolver.deriveAcrossGearboxes(3, new int[0], new int[][]{{0, 1, 0}, {1, 2, 1}});
        for (int i = 0; i < 3; i++) {
            assertFalse(d.reached()[i]);
            assertFalse(d.flip()[i]);
            assertFalse(d.contested()[i]);
        }
    }

    @Test
    void helperOpposingSourcesContestTheHubAndItsChild() {
        // 0, 1 sourced and disagreeing about hub 2; 3 hangs off the hub.
        var d = RotationSolver.deriveAcrossGearboxes(4, new int[]{0, 1}, new int[][]{{0, 2, 0}, {1, 2, 1}, {2, 3, 0}});
        assertTrue(d.contested()[2]);
        assertTrue(d.contested()[3], "contested is inherited down the derivation tree");
        assertFalse(d.contested()[0]);
        assertFalse(d.contested()[1]);
        assertFalse(d.flip()[2], "first source in order wins");
    }

    @Test
    void helperAgreeingSourcesAreNotContested() {
        var d = RotationSolver.deriveAcrossGearboxes(4, new int[]{0, 1}, new int[][]{{0, 2, 0}, {1, 2, 0}, {2, 3, 1}});
        assertFalse(d.contested()[2]);
        assertFalse(d.contested()[3]);
        assertTrue(d.flip()[3]);
    }

    @Test
    void helperIgnoresEdgesBetweenTwoSourcedDomains() {
        var d = RotationSolver.deriveAcrossGearboxes(2, new int[]{0, 1}, new int[][]{{0, 1, 1}});
        assertFalse(d.flip()[0]);
        assertFalse(d.flip()[1]);
        assertFalse(d.contested()[0] || d.contested()[1]);
    }

    @Test
    void helperSingleSourceConflictingLoopIsContested() {
        var d = RotationSolver.deriveAcrossGearboxes(4, new int[]{0}, new int[][]{{0, 1, 0}, {0, 2, 0}, {1, 3, 0}, {2, 3, 1}});
        assertTrue(d.reached()[3]);
        assertTrue(d.contested()[3]);
    }

    @Test
    void helperNearestSourceWins() {
        // Source 0 (first in order) reaches 3 in two hops; source 1 in one hop with the opposite parity.
        var d = RotationSolver.deriveAcrossGearboxes(4, new int[]{0, 1}, new int[][]{{0, 2, 0}, {2, 3, 0}, {1, 3, 1}});
        assertTrue(d.flip()[3]);
        assertTrue(d.contested()[3]);
    }

    @Test
    void helperIsEdgeOrderInvariantAndKeepsItsInvariants() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 300; trial++) {
            int n = 3 + rnd.nextInt(10);
            List<int[]> edges = new ArrayList<>();
            for (int i = 1; i < n; i++) edges.add(new int[]{rnd.nextInt(i), i, rnd.nextInt(2)});   // spanning tree
            for (int k = rnd.nextInt(5); k > 0; k--) edges.add(new int[]{rnd.nextInt(n), rnd.nextInt(n), rnd.nextInt(2)});
            int[] srcs = rnd.ints(1 + rnd.nextInt(3), 0, n).distinct().toArray();
            var base = RotationSolver.deriveAcrossGearboxes(n, srcs, edges.toArray(new int[0][]));
            for (int s = 0; s < 10; s++) {
                List<int[]> sh = new ArrayList<>();
                for (int[] e : edges) sh.add(rnd.nextBoolean() ? new int[]{e[1], e[0], e[2]} : e);
                Collections.shuffle(sh, rnd);
                var o = RotationSolver.deriveAcrossGearboxes(n, srcs, sh.toArray(new int[0][]));
                assertArrayEquals(base.flip(), o.flip());
                assertArrayEquals(base.contested(), o.contested());
            }
            for (int s : srcs) {
                assertFalse(base.flip()[s], "a sourced domain is never flipped");
                assertFalse(base.contested()[s], "a sourced domain is never contested");
            }
            for (int[] e : edges) {
                if (e[0] == e[1]) continue;
                boolean holds = (base.flip()[e[0]] ^ base.flip()[e[1]]) == (e[2] == 1);
                boolean bothSourced = contains(srcs, e[0]) && contains(srcs, e[1]);
                assertTrue(holds || bothSourced || base.contested()[e[0]] || base.contested()[e[1]],
                        "every violated edge touches a contested domain: " + Arrays.toString(e));
            }
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }
}
