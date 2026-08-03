package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.fluid.FluidType;

import java.util.Set;

/**
 * One configured pipe variant (config.yml {@code variants:}). {@code fluids} is the set this
 * pipe can carry when a pump routes fluid through it — empty for ordinary item-only pipes;
 * iron declares water+lava. Item transport is unaffected by the fluid capability.
 *
 * <p>{@code filter} is non-null only for filter pipes: a REGULAR pipe that pulls only the
 * item types configured in its per-block filter GUI out of the source container.
 */
public record PipeVariant(String id, BehaviorType behaviorType, int transferIntervalTicks,
                          int itemsPerTransfer, Set<FluidType> fluids, FilterSpec filter) {

    /** Feature envelope for a filter pipe tier: how many filter slots, and which toggles the
     *  GUI exposes (whitelist⇄blacklist, material⇄exact match). */
    public record FilterSpec(int slots, boolean allowBlacklistToggle, boolean allowExactToggle) {}

    /** Whether this variant is a filter pipe (selective extraction). */
    public boolean isFilter() {
        return filter != null;
    }
}
