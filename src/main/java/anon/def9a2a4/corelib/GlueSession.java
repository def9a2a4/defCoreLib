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

    GlueSession(Anchor anchor, UUID player, long tick) {
        this.anchor = anchor;
        this.player = player;
        this.lastTouchTick = tick;
    }

    void touch(long tick) {
        this.lastTouchTick = tick;
    }
}
