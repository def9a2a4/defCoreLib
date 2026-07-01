package anon.def9a2a4.corelib;

import org.bukkit.Location;
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
    }

    @Override public void clearOffsets() {
        if (!isValid()) return;
        cart.getPersistentDataContainer().remove(GlueManager.GLUE_KEY);
    }

    @Override public Object identityKey() { return cart.getUniqueId(); }
}
