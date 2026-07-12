package anon.def9a2a4.pipes;

/**
 * Defines the behavioral characteristics of pipe variants.
 * Each pipe variant references one of these behavior types.
 */
public enum BehaviorType {
    /**
     * Regular pipes actively pull items from the source container.
     * They face AWAY from the block they were placed against.
     */
    REGULAR,

    /**
     * Corner pipes only relay items that are pushed into them.
     * They face TOWARD the block they were placed against.
     * Cannot be placed on ceilings (facing UP is blocked).
     */
    CORNER
}
