package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** Per-player glue authoring session, bound to one anchor. In-memory only. */
final class GlueSession {

    final Anchor anchor;
    final UUID player;
    @Nullable Block cornerA;   // pending first corner of a cuboid fill
    long lastTouchTick;
    /** The anchor's origin cell at open — set only for dynamic-origin anchors ({@link HoistAnchor}).
     *  GlueAuthoring closes the session when the live origin no longer matches (chain length changed:
     *  a completed stroke, an offhand-placed link, another player mining one) — stored offsets keep
     *  their meaning only against the origin they were authored at. */
    final @Nullable Object originKey;

    GlueSession(Anchor anchor, UUID player, long tick) {
        this(anchor, player, tick, null);
    }

    GlueSession(Anchor anchor, UUID player, long tick, @Nullable Object originKey) {
        this.anchor = anchor;
        this.player = player;
        this.lastTouchTick = tick;
        this.originKey = originKey;
    }

    void touch(long tick) {
        this.lastTouchTick = tick;
    }
}
