package anon.def9a2a4.corelib;

/**
 * Whether this boot runs the showcase harness: tests ({@code -Ddefcorelib.showcaseTest=true}) or a docs capture
 * ({@code -Ddefcorelib.showcaseCapture=<path>}). The machines' per-block movement records (swings, strokes, hoist
 * moves) exist only for the harness to read, so on a normal server they are not kept at all: they are never
 * pruned, and would grow by one entry for every machine that ever moved.
 */
final class ShowcaseMode {
    private ShowcaseMode() {}

    static final boolean ACTIVE = Boolean.getBoolean("defcorelib.showcaseTest")
        || !System.getProperty("defcorelib.showcaseCapture", "").isBlank();

    /** A docs capture only (no tests): pistons record the stroke they would make instead of making it, so the
     *  capture reads the build at rest and the docs page plays the stroke back. */
    static final boolean CAPTURE_ONLY = !Boolean.getBoolean("defcorelib.showcaseTest")
        && !System.getProperty("defcorelib.showcaseCapture", "").isBlank();
}
