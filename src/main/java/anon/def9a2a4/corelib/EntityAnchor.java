package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Minecart;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * {@link Anchor} backed by a mechanism minecart entity. Glue offsets live in the minecart's entity PDC —
 * a persistent entity, so they survive chunk unload/reload and restarts for free (and need no
 * {@code update()}, unlike the skull-BlockState {@link BlockAnchor}).
 *
 * <p>Offsets are relative to the cart's CURRENT cell ({@link #originBlock()}), so the cart only ever
 * carries blocks that are co-located with it: a glued structure must be assembled <em>in place</em> (power
 * the rail under the stationary cart), not by pushing the cart onto a distant rail.
 *
 * <p>Invalid-safe: a removed/dead cart yields {@code null} offsets so a stale authoring session can't NPE
 * the shared outline task.
 */
final class EntityAnchor implements Anchor {

    /** The cart cell ({x,y,z}) the glue offsets were written relative to — see {@link #movedSinceGlued}. */
    private static final NamespacedKey GLUE_CELL_KEY = new NamespacedKey("corelib", "glue_cell");

    private final Minecart cart;
    private final BooleanSupplier atRest;

    EntityAnchor(Minecart cart, BooleanSupplier atRest) {
        this.cart = cart;
        this.atRest = atRest;
    }

    boolean isValid() { return cart.isValid() && !cart.isDead(); }

    @Override public World world() { return cart.getWorld(); }

    @Override public Block originBlock() { return cart.getLocation().getBlock(); }

    @Override public Location pivot() { return cart.getLocation(); }

    @Override public boolean isAtRest() { return atRest.getAsBoolean(); }

    @Override public @Nullable int[] readOffsets() {
        if (!isValid()) return null;
        return cart.getPersistentDataContainer().get(GlueManager.GLUE_KEY, PersistentDataType.INTEGER_ARRAY);
    }

    @Override public void writeOffsets(int[] offsets) {
        if (!isValid()) return;
        cart.getPersistentDataContainer().set(GlueManager.GLUE_KEY, PersistentDataType.INTEGER_ARRAY, offsets);
        Block o = originBlock();
        cart.getPersistentDataContainer().set(GLUE_CELL_KEY, PersistentDataType.INTEGER_ARRAY,
            new int[]{o.getX(), o.getY(), o.getZ()});
    }

    @Override public void clearOffsets() {
        if (!isValid()) return;
        cart.getPersistentDataContainer().remove(GlueManager.GLUE_KEY);
        cart.getPersistentDataContainer().remove(GLUE_CELL_KEY);
    }

    /**
     * True if the cart has left the cell its glue was written at. A glued cart is held only by
     * maxSpeed 0, which a piston push ignores — and since offsets resolve against the CURRENT cell, a
     * pushed cart would carry whatever (possibly claimed) blocks now sit at those offsets. Glue written
     * before this key existed has no cell and is trusted.
     */
    boolean movedSinceGlued() {
        int[] c = cart.getPersistentDataContainer().get(GLUE_CELL_KEY, PersistentDataType.INTEGER_ARRAY);
        if (c == null || c.length != 3) return false;
        Block o = originBlock();
        return c[0] != o.getX() || c[1] != o.getY() || c[2] != o.getZ();
    }

    @Override public Object identityKey() { return cart.getUniqueId(); }
}
