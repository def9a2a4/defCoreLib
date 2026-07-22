package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The BetterMinecarts fuel carts: the coal tender ({@code bmc:coal_cart}) and the blast-furnace cart
 * ({@code bmc:blast_furnace_cart}). Both are item-only (declared in carts-blocks.yml) and, when the
 * item is right-clicked on a rail, spawn a plain {@link RideableMinecart} — the same base entity the
 * mechanism carts and the movement spike use. We re-skin it via {@link Minecart#setDisplayBlockData}
 * and give it a plugin-managed, fuel-only inventory (a 9-slot dropper for the tender, a 5-slot hopper
 * for the blast cart) serialized to the entity's PDC. The GUI opens with a custom title on right-click;
 * hoppers above/beside the track feed it (native hopper-minecart interop reimplemented here). The
 * blast-furnace cart additionally burns that fuel to self-propel (driven by {@link CartTrainManager}).
 *
 * <p>Choosing a plain minecart (no native inventory) makes the plugin inventory the single source of
 * truth — no native/plugin mirror to keep in sync — and lets the tender exceed the 5-slot native cap.
 */
final class CustomCartManager implements Listener {

    static final String COAL_CART_ID = "bmc:coal_cart";
    static final String BLAST_CART_ID = "bmc:blast_furnace_cart";
    static final String TAG_COAL = "corelib:coal_cart";
    static final String TAG_BLAST = "corelib:blast_furnace_cart";

    /** Faces from which an adjacent hopper (pointing at the cart) may push fuel in — sides + above. */
    private static final BlockFace[] PUSH_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
    };

    enum CartType {
        COAL(COAL_CART_ID, TAG_COAL, Material.COAL_BLOCK),
        BLAST(BLAST_CART_ID, TAG_BLAST, Material.BLAST_FURNACE);

        final String itemId;
        final String tag;
        final Material display;

        CartType(String itemId, String tag, Material display) {
            this.itemId = itemId;
            this.tag = tag;
            this.display = display;
        }
    }

    static final class CartState {
        final Minecart cart;
        final CartType type;
        Inventory inv;          // plugin-managed, fuel-only; single source of truth while loaded
        int burnTicks;          // blast-furnace cart only; 0 = not burning

        CartState(Minecart cart, CartType type) {
            this.cart = cart;
            this.type = type;
        }
    }

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final CartConfig config;
    private final NamespacedKey cartTypeKey;   // entity PDC: "coal" / "blast"
    private final NamespacedKey fuelTicksKey;   // entity PDC: blast-furnace burn counter
    private final NamespacedKey cartInvKey;     // entity PDC: serialized inventory contents

    private final Map<UUID, CartState> tracked = new HashMap<>();
    private BukkitTask tickTask;
    private long ticks;
    private CartTrainManager trainManager;   // notified when a drivable engine spawns

    void setTrainManager(CartTrainManager trainManager) { this.trainManager = trainManager; }

    CustomCartManager(org.bukkit.plugin.java.JavaPlugin plugin, CustomBlockRegistry registry, CartConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.cartTypeKey = new NamespacedKey(plugin, "cart_type");
        this.fuelTicksKey = new NamespacedKey(plugin, "cart_fuel_ticks");
        this.cartInvKey = new NamespacedKey(plugin, "cart_inventory");
    }

    void register() {
        for (CartType t : CartType.values()) {
            if (registry.getType(t.itemId) == null) {
                plugin.getLogger().warning("CustomCartManager: '" + t.itemId + "' not found in registry "
                    + "(check carts-blocks.yml) — cart item will not be obtainable");
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk);
            }
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void shutdown() {
        if (tickTask != null) tickTask.cancel();
        for (CartState state : tracked.values()) {
            persist(state);
        }
        tracked.clear();
    }

    /** Re-adopt tracked carts in a chunk whose entities just loaded (post-reload / border crossing). */
    void scanChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Minecart cart)) continue;
            if (tracked.containsKey(cart.getUniqueId())) continue;
            CartType type = typeOf(cart);
            if (type == null) continue;
            CartState state = new CartState(cart, type);
            buildInv(state);
            PersistentDataContainer pdc = cart.getPersistentDataContainer();
            Integer stored = pdc.get(fuelTicksKey, PersistentDataType.INTEGER);
            if (stored != null) state.burnTicks = stored;
            tracked.put(cart.getUniqueId(), state);
        }
    }

    /** A chunk's entities are unloading — flush burn counters + inventory to PDC and drop tracking. */
    void onEntitiesUnload(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!(entity instanceof Minecart)) continue;
            CartState state = tracked.remove(entity.getUniqueId());
            if (state != null) persist(state);
        }
    }

    /** Build (or rebuild) a cart's plugin inventory from its declared layout + PDC contents. */
    private void buildInv(CartState state) {
        CustomHeadBlock type = registry.getType(state.type.itemId);
        CartStorageHolder holder = new CartStorageHolder(state.cart);
        net.kyori.adventure.text.Component title = (type != null && type.name() != null)
            ? type.name()
            : net.kyori.adventure.text.Component.text(state.type.itemId);

        CustomHeadBlock.InventoryLayout layout = (type != null && type.storage() != null)
            ? type.storage().layout()
            : CustomHeadBlock.InventoryLayout.HOPPER;
        Inventory inv = switch (layout) {
            case HOPPER -> Bukkit.createInventory(holder, InventoryType.HOPPER, title);
            case DROPPER -> Bukkit.createInventory(holder, InventoryType.DROPPER, title);
            default -> Bukkit.createInventory(holder, layout.slots, title);
        };
        holder.setInventory(inv);

        String data = state.cart.getPersistentDataContainer().get(cartInvKey, PersistentDataType.STRING);
        InventoryUtil.decode(data, inv, plugin.getLogger());
        state.inv = inv;
    }

    private void persist(CartState state) {
        if (state.cart.isDead()) return;
        PersistentDataContainer pdc = state.cart.getPersistentDataContainer();
        pdc.set(fuelTicksKey, PersistentDataType.INTEGER, state.burnTicks);
        if (state.inv != null) {
            String data = InventoryUtil.encode(state.inv);
            if (data != null) pdc.set(cartInvKey, PersistentDataType.STRING, data);
        }
    }

    /** The cart type of an entity, from its PDC marker, or null if it isn't one of ours. */
    private CartType typeOf(Minecart cart) {
        String t = cart.getPersistentDataContainer().get(cartTypeKey, PersistentDataType.STRING);
        if ("coal".equals(t)) return CartType.COAL;
        if ("blast".equals(t)) return CartType.BLAST;
        return null;
    }

    /** Remove and return one whitelisted fuel item from this cart's inventory (tender feeding), or null. */
    ItemStack takeOneFuelFrom(Minecart cart) {
        CartState s = tracked.get(cart.getUniqueId());
        return (s != null && s.inv != null) ? takeOneFuel(s.inv) : null;
    }

    // ── Placement: right-click a rail with a cart item ──────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCart(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        CartType type = itemType(item);
        if (type == null) return;

        Block block = event.getClickedBlock();
        if (block == null || !isRail(block.getType())) return;

        event.setCancelled(true);

        org.bukkit.Location spawnLoc = block.getLocation().add(0.5, 0, 0.5);
        RideableMinecart cart = block.getWorld().spawn(spawnLoc, RideableMinecart.class, m -> {
            m.setPersistent(true);
            m.addScoreboardTag(type.tag);
            m.setDisplayBlockData(Bukkit.createBlockData(type.display));
            m.getPersistentDataContainer().set(cartTypeKey, PersistentDataType.STRING,
                type == CartType.COAL ? "coal" : "blast");
        });
        CartState state = new CartState(cart, type);
        buildInv(state);
        tracked.put(cart.getUniqueId(), state);
        // A blast-furnace cart is a self-propelled engine → register it with the train manager now so it
        // drives as a size-1 train without waiting for a chunk reload. Coal tenders join a train on link.
        if (type == CartType.BLAST && trainManager != null) trainManager.trackCart(cart);

        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private static boolean isRail(Material m) {
        return m == Material.RAIL || m == Material.POWERED_RAIL
            || m == Material.DETECTOR_RAIL || m == Material.ACTIVATOR_RAIL;
    }

    /** The cart type an item stack represents, from its BLOCK_TYPE_KEY, or null. */
    private CartType itemType(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        String id = stack.getItemMeta().getPersistentDataContainer()
            .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (id == null) return null;
        for (CartType t : CartType.values()) {
            if (t.itemId.equals(id)) return t;
        }
        return null;
    }

    // ── GUI: open the fuel inventory on right-click ─────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onOpenCart(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Minecart cart)) return;
        CartState state = tracked.get(cart.getUniqueId());
        if (state == null || state.inv == null) return;
        // Coupling/uncoupling (chain or shears) is owned by CartTrainManager — defer to it and don't open.
        Material held = event.getPlayer().getInventory().getItemInMainHand().getType();
        if (held == Material.SHEARS || isChain(held)) return;

        event.setCancelled(true);   // suppress the rideable mount
        event.getPlayer().openInventory(state.inv);
    }

    /** Chain material, resilient to the 1.21.9 {@code CHAIN → IRON_CHAIN} rename. */
    private static boolean isChain(Material m) {
        String n = m.name();
        return n.equals("CHAIN") || n.equals("IRON_CHAIN");
    }

    @EventHandler
    public void onCloseCart(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CartStorageHolder holder)) return;
        Minecart cart = holder.cart();
        if (cart.isDead()) return;
        String data = InventoryUtil.encode(event.getInventory());
        if (data != null) {
            cart.getPersistentDataContainer().set(cartInvKey, PersistentDataType.STRING, data);
        }
    }

    // ── Tick: fuel intake from neighbours + blast-furnace burn ──────────────

    private void tick() {
        ticks++;
        boolean feedTick = (ticks % 8 == 0);
        Iterator<Map.Entry<UUID, CartState>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            CartState state = it.next().getValue();
            if (state.cart.isDead()) { it.remove(); continue; }
            if (!state.cart.isValid()) continue;   // chunk unloading; onEntitiesUnload owns cleanup
            if (feedTick) tryFillFromNeighbors(state);
            if (state.type == CartType.BLAST) tickBlast(state);
        }
    }

    /**
     * Reimplements the lost native hopper-minecart interop: a hopper adjacent to (or above) the cart's
     * rail block, pointing at it, pushes one fuel item in; a container directly above pours one down.
     */
    private void tryFillFromNeighbors(CartState state) {
        if (state.inv == null) return;
        Block base = state.cart.getLocation().getBlock();

        for (BlockFace face : PUSH_FACES) {
            Block nb = base.getRelative(face);
            if (nb.getBlockData() instanceof org.bukkit.block.data.type.Hopper hd
                && nb.getState() instanceof Container c
                && nb.getRelative(hd.getFacing()).equals(base)) {
                pullOneFuel(c.getInventory(), state.inv);
            }
        }

        Block above = base.getRelative(BlockFace.UP);
        if (!(above.getBlockData() instanceof org.bukkit.block.data.type.Hopper)
            && above.getState() instanceof Container c) {
            pullOneFuel(c.getInventory(), state.inv);   // cart self-pulls from a container above
        }
    }

    /** Move one whitelisted fuel item from {@code source} into {@code dest}; bounce it back if full. */
    private void pullOneFuel(Inventory source, Inventory dest) {
        ItemStack one = takeOneFuel(source);
        if (one == null) return;
        Map<Integer, ItemStack> leftover = dest.addItem(one);
        for (ItemStack l : leftover.values()) source.addItem(l);
    }

    private void tickBlast(CartState state) {
        Minecart cart = state.cart;

        // Fuel bookkeeping only — movement is owned by CartTrainManager (position-driving). When idle,
        // consume one whitelisted fuel item from the cart's own inventory to start a new burn.
        if (state.burnTicks <= 0) {
            ItemStack fuel = takeOneFuel(state.inv);
            if (fuel != null) {
                state.burnTicks = config.burnTicks(fuel.getType());
            }
        }

        if (state.burnTicks > 0) {
            state.burnTicks--;
            if (cart.getWorld() != null) {
                cart.getWorld().spawnParticle(org.bukkit.Particle.SMOKE,
                    cart.getLocation().add(0, 0.6, 0), 1, 0.05, 0.05, 0.05, 0.0);
            }
        }
    }

    // ── Accessors for CartTrainManager (engine power + tender fuel) ──────────

    /** True if this minecart is our blast-furnace cart (a train engine). */
    boolean isBlastCart(Minecart cart) { return typeOf(cart) == CartType.BLAST; }

    /** True if this minecart is our coal tender. */
    boolean isCoalCart(Minecart cart) { return typeOf(cart) == CartType.COAL; }

    /** Remaining burn ticks for a blast-furnace cart (0 if not tracked / not a blast cart). */
    int burnTicks(Minecart cart) {
        CartState s = tracked.get(cart.getUniqueId());
        return s != null && s.type == CartType.BLAST ? s.burnTicks : 0;
    }

    /** Top up a blast-furnace cart's burn counter (tender feeding). No-op if not a tracked blast cart. */
    void addBurnTicks(Minecart cart, int add) {
        CartState s = tracked.get(cart.getUniqueId());
        if (s != null && s.type == CartType.BLAST) s.burnTicks += add;
    }

    /** Whether a material is accepted as fuel (delegates to the shared whitelist). */
    boolean isFuel(Material m) { return config.isFuel(m); }

    /** Burn ticks one unit of a fuel material yields. */
    int fuelBurnTicks(Material m) { return config.burnTicks(m); }

    /** Remove and return one whitelisted fuel item (amount 1) from an inventory, or null. */
    private ItemStack takeOneFuel(Inventory inv) {
        if (inv == null) return null;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir() || !config.isFuel(stack.getType())) continue;
            ItemStack one = stack.clone();
            one.setAmount(1);
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(i, stack.getAmount() > 0 ? stack : null);
            return one;
        }
        return null;
    }

    // ── Fuel-only inventory guards ──────────────────────────────────────────

    /** Whether an inventory belongs to a cart we manage (and is thus fuel-only). */
    private boolean isCartInventory(InventoryHolder holder) {
        return holder instanceof CartStorageHolder;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isCartInventory(top.getHolder())) return;

        InventoryAction action = event.getAction();
        boolean clickedTop = event.getClickedInventory() == top;

        // Shift-click from the player inventory into the cart: the moved stack is the clicked item.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && !clickedTop) {
            ItemStack moved = event.getCurrentItem();
            if (moved != null && !config.isFuel(moved.getType())) event.setCancelled(true);
            return;
        }
        if (!clickedTop) return;   // other bottom-inventory actions never deposit into the cart

        // Default-deny inside the cart: allow only pure removals; any deposit must be fuel; deny unknowns.
        switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE, DROP_ALL_SLOT, DROP_ONE_SLOT,
                 MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR, CLONE_STACK, NOTHING -> { }
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !config.isFuel(cursor.getType())) event.setCancelled(true);
            }
            case HOTBAR_SWAP -> {
                ItemStack hb = event.getHotbarButton() >= 0
                    ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;
                if (hb != null && !config.isFuel(hb.getType())) event.setCancelled(true);
            }
            // Any other action that could touch a cart slot (incl. HOTBAR_MOVE_AND_READD) → deny.
            default -> event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isCartInventory(top.getHolder())) return;
        if (config.isFuel(event.getOldCursor().getType())) return;
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) { event.setCancelled(true); return; }
        }
    }

    // ── Destruction / pick-block ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onCartDestroyed(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        CartState state = tracked.remove(cart.getUniqueId());
        if (state == null) return;

        event.setCancelled(true);
        World world = cart.getWorld();
        org.bukkit.Location loc = cart.getLocation();

        // Drop the custom cart item (not a vanilla minecart) + its inventory contents.
        CustomHeadBlock itemType = registry.getType(state.type.itemId);
        if (itemType != null) world.dropItemNaturally(loc, itemType.createItem(1));
        if (state.inv != null) {
            for (var v : new ArrayList<>(state.inv.getViewers())) v.closeInventory();
            for (ItemStack stack : state.inv.getContents()) {
                if (stack != null && !stack.getType().isAir()) world.dropItemNaturally(loc, stack);
            }
            state.inv.clear();
        }
        cart.remove();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickCart(io.papermc.paper.event.player.PlayerPickEntityEvent event) {
        if (!(event.getEntity() instanceof Minecart cart)) return;
        CartState state = tracked.get(cart.getUniqueId());
        if (state == null) return;
        CustomHeadBlock itemType = registry.getType(state.type.itemId);
        if (itemType == null) return;
        event.setCancelled(true);
        InventoryUtil.pickInto(event.getPlayer(), itemType.createItem(1), event.getTargetSlot());
    }
}
