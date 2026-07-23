package anon.def9a2a4.corelib;

import anon.def9a2a4.corelib.container.ContainerAdapterRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * BetterMinecarts rail types: {@code bmc:junction} (a crossing that keeps a cart's heading straight
 * instead of curving) and {@code bmc:destructor_rail} (destroys a passing minecart, depositing its
 * drops into the container below). Both are placed as real rails ({@code base_block: RAIL /
 * DETECTOR_RAIL}); identity + persistence come from CoreLib's bare-block chunk index, so we just resolve
 * the block's type on demand via {@link CustomBlockRegistry#getTypeFromBlock} — no location bookkeeping.
 *
 * <p>Two movement regimes are handled: vanilla / mechanism / unmanaged carts move by physics and are
 * caught via {@link VehicleMoveEvent}; position-driven train carts are teleported (which does not fire
 * that event), so {@link CartTrainManager} calls {@link #alignJunctionsAlong} / {@link #isDestructor}
 * from its drive loop instead.
 */
final class CartRailsManager implements Listener {

    static final String JUNCTION_ID = "bmc:junction";
    static final String DESTRUCTOR_ID = "bmc:destructor_rail";
    private static final String TAG_MECHANISM = "corelib:mechanism_minecart";

    private final CustomBlockRegistry registry;
    private final CustomCartManager carts;
    private final CartTrainManager trains;

    CartRailsManager(JavaPlugin plugin, CustomBlockRegistry registry,
                     CustomCartManager carts, CartTrainManager trains) {
        this.registry = registry;
        this.carts = carts;
        this.trains = trains;
    }

    // ── Identity ────────────────────────────────────────────────────────────

    boolean isJunction(Block b) {
        return b != null && b.getType() == Material.RAIL && isType(b, JUNCTION_ID);
    }

    boolean isDestructor(Block b) {
        return b != null && b.getType() == Material.DETECTOR_RAIL && isType(b, DESTRUCTOR_ID);
    }

    private boolean isType(Block b, String fullId) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && fullId.equals(t.fullId());
    }

    // ── Junction: force the crossing straight along the cart's heading ────────

    /** Set a junction rail's shape to the straight axis matching {@code heading} so a cart crosses it
     *  without turning. No-op if it's not a rail or already aligned. */
    void alignJunction(Block b, Vector heading) {
        if (!(b.getBlockData() instanceof Rail rail)) return;
        Rail.Shape want = Math.abs(heading.getZ()) >= Math.abs(heading.getX())
            ? Rail.Shape.NORTH_SOUTH : Rail.Shape.EAST_WEST;
        if (rail.getShape() == want || !rail.getShapes().contains(want)) return;
        rail.setShape(want);
        b.setBlockData(rail, false);   // no physics: don't let a neighbour update re-curve it
    }

    /** Pre-align every junction from {@code pos} up to {@code lookBlocks} ahead along {@code heading}, so
     *  a position-driven leader finds each crossing already straight when it walks onto it. */
    void alignJunctionsAlong(World world, Vector pos, Vector heading, double lookBlocks) {
        Vector h = new Vector(heading.getX(), 0, heading.getZ());
        if (h.lengthSquared() < 1e-6) return;
        h.normalize();
        int steps = (int) Math.ceil(Math.max(1.0, lookBlocks));
        for (int d = 0; d <= steps; d++) {
            Block b = pos.clone().add(h.clone().multiply(d)).toLocation(world).getBlock();
            if (isJunction(b)) alignJunction(b, heading);
        }
    }

    // ── Destructor: recycle a passing cart into the container below ────────────

    /** Destroy {@code cart} at destructor rail {@code rail}: eject riders, sever couplings, and drop the
     *  cart item + its inventory + any chains into the container directly below (else on the ground).
     *  Mechanism minecarts are exempt (handled by the callers, but re-checked here for safety). */
    void destroy(Minecart cart, Block rail) {
        if (cart == null || cart.isDead()) return;
        if (cart.getScoreboardTags().contains(TAG_MECHANISM)) return;

        List<ItemStack> drops = new ArrayList<>();
        drops.addAll(trains.detachAndCollectChains(cart));      // severs couplings; returns chain items
        List<ItemStack> cartDrops = carts.collectCartDrops(cart);   // bmc cart item + inv, or null
        drops.addAll(cartDrops != null ? cartDrops : vanillaCartDrops(cart));

        cart.eject();
        cart.remove();

        Container below = ContainerAdapterRegistry.findVanillaContainer(rail.getRelative(BlockFace.DOWN));
        World world = rail.getWorld();
        Location dropLoc = rail.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            if (below != null) {
                for (ItemStack leftover : below.getInventory().addItem(drop).values()) {
                    world.dropItemNaturally(dropLoc, leftover);
                }
            } else {
                world.dropItemNaturally(dropLoc, drop);
            }
        }
    }

    /** Drops for a plain vanilla minecart the cart managers don't own: its item + any inventory. */
    private List<ItemStack> vanillaCartDrops(Minecart cart) {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(vanillaCartItem(cart)));
        if (cart instanceof InventoryHolder holder) {
            for (ItemStack s : holder.getInventory().getContents()) {
                if (s != null && !s.getType().isAir()) out.add(s);
            }
            holder.getInventory().clear();
        }
        return out;
    }

    private static Material vanillaCartItem(Minecart cart) {
        if (cart instanceof StorageMinecart) return Material.CHEST_MINECART;
        if (cart instanceof PoweredMinecart) return Material.FURNACE_MINECART;
        if (cart instanceof ExplosiveMinecart) return Material.TNT_MINECART;
        if (cart instanceof CommandMinecart) return Material.COMMAND_BLOCK_MINECART;
        return Material.MINECART;
    }

    // ── Physics-moved carts (vanilla / mechanism / unmanaged) ─────────────────

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        Block b = event.getTo().getBlock();
        Material m = b.getType();
        if (m == Material.RAIL) {
            if (isJunction(b)) alignJunction(b, cart.getVelocity());
        } else if (m == Material.DETECTOR_RAIL) {
            if (isDestructor(b)) destroy(cart, b);   // exemption checked in destroy()
        }
    }
}
