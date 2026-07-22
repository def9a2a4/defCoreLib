package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The BetterMinecarts fuel carts: the coal tender ({@code bmc:coal_cart}) and the blast-furnace cart
 * ({@code bmc:blast_furnace_cart}). Both are item-only (declared in carts-blocks.yml) and, when the
 * item is right-clicked on a rail, spawn a vanilla {@link HopperMinecart}. HopperMinecart gives us the
 * required 5-slot inventory, native NBT persistence, and native hopper interop (a hopper above/beside
 * the track fills it) for free — we only re-skin it via {@link Minecart#setDisplayBlockData}, restrict
 * its inventory to fuel, and (for the blast-furnace cart) burn that fuel to self-propel.
 *
 * <p>Milestone scope: solo carts on vanilla physics. Coupling into trains and position-driven high
 * speed land in the train subsystem; the blast-furnace cart here is a slow (vanilla-clamped)
 * furnace-style locomotive until then.
 */
final class CustomCartManager implements Listener {

    static final String COAL_CART_ID = "bmc:coal_cart";
    static final String BLAST_CART_ID = "bmc:blast_furnace_cart";
    static final String TAG_COAL = "corelib:coal_cart";
    static final String TAG_BLAST = "corelib:blast_furnace_cart";

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
        final HopperMinecart cart;
        final CartType type;
        int burnTicks;          // blast-furnace cart only; 0 = not burning

        CartState(HopperMinecart cart, CartType type) {
            this.cart = cart;
            this.type = type;
        }
    }

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final CartConfig config;
    private final NamespacedKey cartTypeKey;   // entity PDC: "coal" / "blast"
    private final NamespacedKey fuelTicksKey;   // entity PDC: blast-furnace burn counter

    private final Map<UUID, CartState> tracked = new HashMap<>();
    private BukkitTask tickTask;
    private CartTrainManager trainManager;   // notified when a drivable engine spawns

    void setTrainManager(CartTrainManager trainManager) { this.trainManager = trainManager; }

    CustomCartManager(org.bukkit.plugin.java.JavaPlugin plugin, CustomBlockRegistry registry, CartConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.cartTypeKey = new NamespacedKey(plugin, "cart_type");
        this.fuelTicksKey = new NamespacedKey(plugin, "cart_fuel_ticks");
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
            if (!(entity instanceof HopperMinecart cart)) continue;
            if (tracked.containsKey(cart.getUniqueId())) continue;
            CartType type = typeOf(cart);
            if (type == null) continue;
            CartState state = new CartState(cart, type);
            PersistentDataContainer pdc = cart.getPersistentDataContainer();
            Integer stored = pdc.get(fuelTicksKey, PersistentDataType.INTEGER);
            if (stored != null) state.burnTicks = stored;
            tracked.put(cart.getUniqueId(), state);
        }
    }

    /** A chunk's entities are unloading — flush burn counters to PDC and drop tracking. */
    void onEntitiesUnload(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!(entity instanceof HopperMinecart)) continue;
            CartState state = tracked.remove(entity.getUniqueId());
            if (state != null) persist(state);
        }
    }

    private void persist(CartState state) {
        if (state.cart.isDead()) return;
        state.cart.getPersistentDataContainer().set(fuelTicksKey, PersistentDataType.INTEGER, state.burnTicks);
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
        if (cart instanceof HopperMinecart hm) return takeOneFuel(hm.getInventory());
        return null;
    }

    // ── Placement: right-click a rail with a cart item ──────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCart(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        CartType type = itemType(item);
        if (type == null) return;

        Block block = event.getClickedBlock();
        if (block == null || !isRail(block.getType())) return;

        event.setCancelled(true);

        org.bukkit.Location spawnLoc = block.getLocation().add(0.5, 0, 0.5);
        HopperMinecart cart = block.getWorld().spawn(spawnLoc, HopperMinecart.class, m -> {
            m.setPersistent(true);
            m.addScoreboardTag(type.tag);
            m.setDisplayBlockData(Bukkit.createBlockData(type.display));
            m.getPersistentDataContainer().set(cartTypeKey, PersistentDataType.STRING,
                type == CartType.COAL ? "coal" : "blast");
        });
        tracked.put(cart.getUniqueId(), new CartState(cart, type));
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

    // ── Tick: blast-furnace burn + vanilla-style propulsion ─────────────────

    private void tick() {
        Iterator<Map.Entry<UUID, CartState>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            CartState state = it.next().getValue();
            if (state.cart.isDead()) { it.remove(); continue; }
            if (!state.cart.isValid()) continue;   // chunk unloading; onEntitiesUnload owns cleanup
            if (state.type == CartType.BLAST) tickBlast(state);
        }
    }

    private void tickBlast(CartState state) {
        HopperMinecart cart = state.cart;

        // Fuel bookkeeping only — movement is owned by CartTrainManager (position-driving). When idle,
        // consume one whitelisted fuel item from the cart's own inventory to start a new burn.
        if (state.burnTicks <= 0) {
            ItemStack fuel = takeOneFuel(cart.getInventory());
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

    /** Whether an inventory belongs to a cart we track (and is thus fuel-only). */
    private boolean isCartInventory(InventoryHolder holder) {
        return holder instanceof HopperMinecart cart && tracked.containsKey(cart.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        // Hopper above/beside pushing in, or the cart self-pulling from a container above: only allow fuel.
        if (isCartInventory(event.getDestination().getHolder()) && !config.isFuel(event.getItem().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartPickup(InventoryPickupItemEvent event) {
        if (isCartInventory(event.getInventory().getHolder())
            && !config.isFuel(event.getItem().getItemStack().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isCartInventory(top.getHolder())) return;

        InventoryAction action = event.getAction();
        // Shift-click from the player inventory into the cart: the moved stack is the clicked item.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && event.getClickedInventory() != top) {
            ItemStack moved = event.getCurrentItem();
            if (moved != null && !config.isFuel(moved.getType())) event.setCancelled(true);
            return;
        }
        // Direct placement into a cart slot: the incoming item is the cursor (or the hotbar-swap item).
        if (event.getClickedInventory() == top) {
            switch (action) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                    ItemStack cursor = event.getCursor();
                    if (cursor != null && !config.isFuel(cursor.getType())) event.setCancelled(true);
                }
                case HOTBAR_SWAP -> {
                    ItemStack hb = event.getHotbarButton() >= 0
                        ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;
                    if (hb != null && !config.isFuel(hb.getType())) event.setCancelled(true);
                }
                default -> { }
            }
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
        if (!(event.getVehicle() instanceof HopperMinecart cart)) return;
        CartState state = tracked.remove(cart.getUniqueId());
        if (state == null) return;

        event.setCancelled(true);
        World world = cart.getWorld();
        org.bukkit.Location loc = cart.getLocation();

        // Drop the custom cart item (not a vanilla hopper minecart) + its inventory contents.
        CustomHeadBlock itemType = registry.getType(state.type.itemId);
        if (itemType != null) world.dropItemNaturally(loc, itemType.createItem(1));
        for (ItemStack stack : cart.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) world.dropItemNaturally(loc, stack);
        }
        cart.getInventory().clear();
        cart.remove();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickCart(io.papermc.paper.event.player.PlayerPickEntityEvent event) {
        if (!(event.getEntity() instanceof HopperMinecart cart)) return;
        CartState state = tracked.get(cart.getUniqueId());
        if (state == null) return;
        CustomHeadBlock itemType = registry.getType(state.type.itemId);
        if (itemType == null) return;
        event.setCancelled(true);
        InventoryUtil.pickInto(event.getPlayer(), itemType.createItem(1), event.getTargetSlot());
    }
}
