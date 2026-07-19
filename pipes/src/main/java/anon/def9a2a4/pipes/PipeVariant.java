package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.fluid.FluidType;

import java.util.Set;

/**
 * One configured pipe variant (config.yml {@code variants:}). {@code fluids} is the set this
 * pipe can carry when a pump routes fluid through it — empty for ordinary item-only pipes;
 * iron declares water+lava. Item transport is unaffected by the fluid capability.
 */
public record PipeVariant(String id, BehaviorType behaviorType, int transferIntervalTicks,
                          int itemsPerTransfer, Set<FluidType> fluids) {
}
