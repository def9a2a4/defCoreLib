package anon.def9a2a4.corelib;

import anon.def9a2a4.corelib.container.ContainerAdapterRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Railbound rail types: {@code railbound:junction} (a crossing that keeps a cart's heading straight
 * instead of curving) and {@code railbound:destructor_rail} (destroys a passing minecart, depositing its
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

    static final String JUNCTION_ID = "railbound:junction";
    static final String DESTRUCTOR_ID = "railbound:destructor_rail";
    static final String CONTROLLER_ID = "railbound:controller_rail";
    private static final String TAG_MECHANISM = "corelib:mechanism_minecart";

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final CustomCartManager carts;
    private final CartTrainManager trains;
    private final CartConfig config;

    /** Player-chosen 4-way facing of an orientable rail (destructor / controller). A bare rail stores no
     *  native facing, so we keep it here and mirror it to the block's chunk PDC for durability. */
    private final java.util.Map<CustomBlockRegistry.LocationKey, BlockFace> facings = new java.util.HashMap<>();
    private final org.bukkit.NamespacedKey railFacingKey;

    CartRailsManager(JavaPlugin plugin, CustomBlockRegistry registry,
                     CustomCartManager carts, CartTrainManager trains, CartConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.carts = carts;
        this.trains = trains;
        this.config = config;
        this.railFacingKey = new org.bukkit.NamespacedKey("corelib", "cart_rail_facing");
    }

    /** Overlay the orientable rails (destructor + controller) so their display shell faces the direction
     *  the player placed them (task C). Also hooks removal so the facing entry is dropped with the block.
     *  Called once after the railbound blocks load. */
    void installOverlays() {
        for (String id : new String[]{DESTRUCTOR_ID, CONTROLLER_ID}) {
            CustomHeadBlock type = registry.getType(id);
            if (type == null) continue;
            registry.register(type.toBuilder()
                .displayTransformResolver((b, state, cfg, idx) -> orientRail(b, cfg))
                .onBlockRemoved((b, state) -> forgetFacing(b))
                .build());
        }
        // Chunks already loaded before we enabled won't fire ChunkLoadEvent — seed their facings now.
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) loadChunkFacings(chunk);
        }
    }

    /** Orient an orientable rail's shell to FOLLOW the underlying rail's live shape (its N–S / E–W axis),
     *  with the stored placement facing only picking the head/tail direction within that axis. Keeps the
     *  authored translation + scale, replaces only the rotation. Because {@code displayTransformResolver}
     *  marks these rails neighbour-reactive, CoreLib re-runs this whenever a neighbour changes the shape —
     *  so the shell self-heals; {@link #onRailPhysics} adds a deferred settle-correction on top. */
    private org.bukkit.util.Transformation orientRail(Block b, CustomHeadBlock.DisplayEntityConfig cfg) {
        org.bukkit.util.Transformation base = cfg.transform();
        return new org.bukkit.util.Transformation(
            base.getTranslation(), Faces.rotationForFace(shapeFace(b)), base.getScale(),
            new org.joml.AxisAngle4f(0f, 0f, 0f, 1f));
    }

    /** The cardinal face the shell should point along: the rail's live shape gives the axis (N–S / E–W),
     *  the stored placement facing chooses which of the two ends the arrow points to (cosmetic head/tail —
     *  neither rail acts directionally). Falls back to the stored facing (or SOUTH) if the block isn't a
     *  readable rail. Curved shapes never persist (broken in onRailPhysics), so the axis is always cardinal. */
    private BlockFace shapeFace(Block b) {
        BlockFace stored = facings.get(CustomBlockRegistry.LocationKey.of(b));
        Rail rail = RailPathWalker.railData(b);
        if (rail == null) return stored != null ? stored : BlockFace.SOUTH;
        BlockFace[] axis = RailPathWalker.connectedFaces(rail.getShape());
        return (stored != null && (stored == axis[0] || stored == axis[1])) ? stored : axis[0];
    }

    private boolean isOrientableRail(Block b) {
        return isDestructor(b) || isController(b);
    }

    // ── Identity ────────────────────────────────────────────────────────────

    boolean isJunction(Block b) {
        return b != null && b.getType() == Material.RAIL && isType(b, JUNCTION_ID);
    }

    boolean isDestructor(Block b) {
        return b != null && b.getType() == Material.DETECTOR_RAIL && isType(b, DESTRUCTOR_ID);
    }

    boolean isController(Block b) {
        return b != null && b.getType() == Material.RAIL && isType(b, CONTROLLER_ID);
    }

    /** Target cruise speed a controller rail sets for a fueled blast cart, from its redstone signal
     *  strength (blocks/tick), linear: 0 → 0 (stop), 15 → controller-max (full). The cart REMEMBERS this
     *  target (persisted on its entity PDC) and holds it after leaving the rail — the train keeps the set
     *  speed instead of snapping back to full. See CartTrainManager.drive(). */
    double controllerTarget(Block b) {
        return (b.getBlockPower() / 15.0) * config.controllerMaxSpeed;
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
        List<ItemStack> cartDrops = carts.collectCartDrops(cart);   // railbound cart item + inv, or null
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

    // ── Wrench: report a controller rail's redstone signal + resulting target speed ──────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWrenchRail(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!RotationBlocks.isWrench(event.getItem())) return;
        Block b = event.getClickedBlock();
        if (b == null || !isController(b)) return;
        event.setCancelled(true);
        int power = b.getBlockPower();
        event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
            String.format("Controller: signal %d/15 → %.2f b/t", power, controllerTarget(b)),
            net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    // ── Anti-slope/curve: a custom rail must stay straight+flat — break it if it slopes or curves,
    //    else re-point its shell to the settled shape (the shell's flat square can't depict a curve) ────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRailPhysics(BlockPhysicsEvent event) {
        Block b = event.getBlock();
        Material m = b.getType();
        if (m != Material.RAIL && m != Material.DETECTOR_RAIL && m != Material.POWERED_RAIL) return;
        if (ourRailType(b) == null) return;
        // The re-shape may not be applied at event time; act next tick once the shape has settled.
        Location loc = b.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block cur = loc.getBlock();
            CustomHeadBlock type = ourRailType(cur);
            if (type == null) return;
            Rail rail = RailPathWalker.railData(cur);
            if (rail == null) return;
            Rail.Shape shape = rail.getShape();
            // Slopes: break any custom rail. Curves: break only ORIENTABLE rails (destructor + controller —
            // their flat square shell can't depict a curve). A junction is a 4-way crossing whose vanilla
            // RAIL rests curved at a real intersection, so it must NOT be curve-broken.
            if (RailPathWalker.isAscending(shape) || (isOrientableRail(cur) && RailPathWalker.isCurve(shape))) {
                breakRail(cur, type);
            } else if (isOrientableRail(cur)) {
                reorientDisplays(cur, shapeFace(cur));   // settle-correction: follow the rail's new axis
            }
        });
    }

    /** The custom-rail type at {@code b}, or null if it isn't one of ours. */
    private CustomHeadBlock ourRailType(Block b) {
        return (isJunction(b) || isDestructor(b) || isController(b)) ? registry.getTypeFromBlock(b) : null;
    }

    /** Break a custom rail: drop its item, clear identity/display/PDC, then remove the world block. */
    private void breakRail(Block block, CustomHeadBlock type) {
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), type.createItem(1));
        registry.onBlockRemoved(block, type);   // clears bare identity + display + chunk PDC (+ our facing via overlay)
        block.setType(Material.AIR);
    }

    // ── 4-way orientable display (destructor + controller): capture, persist, reload ────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceOrientedRail(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        if (!isOrientableRail(b)) return;
        rememberFacing(b, horizontalFacing(event.getPlayer()));   // captures the cosmetic head/tail tiebreak
        // The shell already exists (spawned synchronously during CoreLib's HIGH applyConfig, before this
        // HIGHEST handler). Defer the re-point one tick so the rail's SHAPE has settled — shapeFace() reads
        // the live axis, and a freshly-placed block isn't in the reactive set yet so no neighbour refresh
        // would reach it otherwise.
        Location loc = b.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block cur = loc.getBlock();
            if (isOrientableRail(cur)) reorientDisplays(cur, shapeFace(cur));
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        loadChunkFacings(event.getChunk());
    }

    /** Re-point an already-spawned shell to {@code face} in place (keeps its authored translation+scale). */
    private void reorientDisplays(Block b, BlockFace face) {
        CustomHeadBlock type = registry.getTypeFromBlock(b);
        if (type == null) return;
        String prefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), b.getLocation());
        for (Display d : DisplayUtil.findByTag(b.getLocation(), prefix, 1.5)) {
            org.bukkit.util.Transformation cur = d.getTransformation();
            d.setTransformation(new org.bukkit.util.Transformation(
                cur.getTranslation(), Faces.rotationForFace(face), cur.getScale(),
                new org.joml.AxisAngle4f(0f, 0f, 0f, 1f)));
        }
    }

    /** The horizontal direction a player is looking (yaw → N/E/S/W), ignoring pitch. */
    private static BlockFace horizontalFacing(Player p) {
        float yaw = ((p.getYaw() % 360) + 360) % 360;
        if (yaw >= 45 && yaw < 135) return BlockFace.WEST;
        if (yaw >= 135 && yaw < 225) return BlockFace.NORTH;
        if (yaw >= 225 && yaw < 315) return BlockFace.EAST;
        return BlockFace.SOUTH;
    }

    private void rememberFacing(Block b, BlockFace face) {
        facings.put(CustomBlockRegistry.LocationKey.of(b), face);
        writeChunkFacings(b.getChunk());
    }

    private void forgetFacing(Block b) {
        if (facings.remove(CustomBlockRegistry.LocationKey.of(b)) != null) writeChunkFacings(b.getChunk());
    }

    /** Populate {@link #facings} from a chunk's persisted facing string (called on chunk load + startup). */
    void loadChunkFacings(Chunk chunk) {
        String data = chunk.getPersistentDataContainer().get(railFacingKey, PersistentDataType.STRING);
        if (data == null || data.isEmpty()) return;
        UUID w = chunk.getWorld().getUID();
        for (String cell : data.split(";")) {
            int eq = cell.indexOf('=');
            if (eq < 0) continue;
            String[] xyz = cell.substring(0, eq).split(",");
            if (xyz.length != 3) continue;
            try {
                facings.put(new CustomBlockRegistry.LocationKey(w,
                        Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])),
                    BlockFace.valueOf(cell.substring(eq + 1)));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Rewrite a chunk's persisted facing string from the in-memory map (all cells in that chunk). */
    private void writeChunkFacings(Chunk chunk) {
        UUID w = chunk.getWorld().getUID();
        StringBuilder sb = new StringBuilder();
        for (var e : facings.entrySet()) {
            CustomBlockRegistry.LocationKey k = e.getKey();
            if (!k.worldId().equals(w) || (k.x() >> 4) != chunk.getX() || (k.z() >> 4) != chunk.getZ()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(k.x()).append(',').append(k.y()).append(',').append(k.z()).append('=').append(e.getValue().name());
        }
        var pdc = chunk.getPersistentDataContainer();
        if (sb.length() == 0) pdc.remove(railFacingKey);
        else pdc.set(railFacingKey, PersistentDataType.STRING, sb.toString());
    }
}
