package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.BlockDisplay;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The BetterMinecarts fuel carts: the coal tender ({@code bmc:coal_cart}) and the blast-furnace cart
 * ({@code bmc:blast_furnace_cart}). Both are item-only (declared in carts-blocks.yml) and, when the
 * item is right-clicked on a rail, spawn a plain {@link RideableMinecart} — the same base entity the
 * mechanism carts use. We re-skin it via {@link Minecart#setDisplayBlockData}
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
    static final String DISPENSER_CART_ID = "bmc:dispenser_cart";
    static final String TAG_COAL = "corelib:coal_cart";
    static final String TAG_BLAST = "corelib:blast_furnace_cart";
    static final String TAG_DISPENSER = "corelib:dispenser_cart";

    // Blast-furnace cart GUI: a 9-slot dropper — fuel in slots 0-2, inert filler in 3-6, and speed −/+ head
    // buttons in 7/8 that step the cruise target in redstone-sized (1/15) increments. Only slots 0-2 persist.
    private static final int BLAST_FUEL_SLOTS = 3;      // usable fuel slots (0..2)
    private static final int BLAST_SLOT_MINUS = 7;
    private static final int BLAST_SLOT_PLUS = 8;
    private static final double MPS_PER_BT = 20.0;      // 1 block/tick = 20 blocks/s = 20 m/s
    private static final String BLAST_PLUS_TEX =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMGEyMWViNGM1Nzc1MDcyOWE0OGI4OGU5YmJkYjk4N2ViNjI1MGE1YmMyMTU3YjU5MzE2ZjVmMTg4N2RiNSJ9fX0=";
    private static final String BLAST_MINUS_TEX =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYThjNjdmZWQ3YTI0NzJiN2U5YWZkOGQ3NzJjMTNkYjdiODJjMzJjZWVmZjhkYjk3NzQ3NGMxMWU0NjExIn19fQ==";

    /** Faces from which an adjacent hopper (pointing at the cart) may push fuel in — sides + above. */
    private static final BlockFace[] PUSH_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
    };

    enum CartType {
        COAL(COAL_CART_ID, TAG_COAL, Material.COAL_BLOCK, "coal", true),
        BLAST(BLAST_CART_ID, TAG_BLAST, Material.BLAST_FURNACE, "blast", true),
        DISPENSER(DISPENSER_CART_ID, TAG_DISPENSER, Material.DISPENSER, "dispenser", false);

        final String itemId;
        final String tag;
        final Material display;
        final String pdc;         // entity-PDC marker value
        final boolean fuelOnly;   // inventory restricted to fuel? (dispenser holds any item)

        CartType(String itemId, String tag, Material display, String pdc, boolean fuelOnly) {
            this.itemId = itemId;
            this.tag = tag;
            this.display = display;
            this.pdc = pdc;
            this.fuelOnly = fuelOnly;
        }
    }

    static final class CartState {
        final Minecart cart;
        final CartType type;
        Inventory inv;          // plugin-managed; single source of truth while loaded
        int burnTicks;          // blast-furnace cart only; 0 = not burning
        boolean wasOnActivator; // dispenser cart: edge-detect entering a powered activator rail
        double targetSpeed = -1; // blast cart: controller-set cruise target (b/t); -1 = unset → full

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
    private final NamespacedKey cartTargetKey;  // entity PDC: blast-cart controller cruise target (double)

    private final Map<UUID, CartState> tracked = new HashMap<>();
    /** Per-burning-blast-cart DynLight marker: an invisible, non-persistent BlockDisplay tagged
     *  {@code dynlight:5} that follows the cart while lit. Spawning/removing it toggles the light,
     *  since DynLight detects the tag on entity spawn (not on live tag edits). */
    private final Map<UUID, BlockDisplay> burnLight = new HashMap<>();
    /** Light level a lit blast-furnace cart emits (like a vanilla lit furnace, dimmer). */
    private static final int BLAST_LIGHT_LEVEL = 5;
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
        this.cartTargetKey = new NamespacedKey(plugin, "cart_target_speed");
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
            // Close any open GUI before we stop guarding it, so items added during the disable window
            // aren't voided (the close-writeback + fuel-only listeners are about to be unregistered).
            if (state.inv != null) {
                for (var v : new ArrayList<>(state.inv.getViewers())) v.closeInventory();
            }
            persist(state);
        }
        for (BlockDisplay marker : burnLight.values()) {
            if (marker.isValid()) marker.remove();
        }
        burnLight.clear();
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
            Double target = pdc.get(cartTargetKey, PersistentDataType.DOUBLE);
            if (target != null) state.targetSpeed = target;
            tracked.put(cart.getUniqueId(), state);
            if (type == CartType.DISPENSER) applyDispenserFacing(state);   // render the fixed up-facing dispenser
        }
    }

    /** A chunk's entities are unloading — flush burn counters + inventory to PDC and drop tracking. */
    void onEntitiesUnload(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!(entity instanceof Minecart)) continue;
            CartState state = tracked.remove(entity.getUniqueId());
            if (state != null) persist(state);
            // Drop tracking of the marker too; it is non-persistent and despawns with the chunk.
            burnLight.remove(entity.getUniqueId());
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
        if (state.type == CartType.BLAST) decorateBlastGui(state);   // repack fuel to 0-2 + stamp filler/buttons
    }

    private void persist(CartState state) {
        if (state.cart.isDead()) return;
        PersistentDataContainer pdc = state.cart.getPersistentDataContainer();
        pdc.set(fuelTicksKey, PersistentDataType.INTEGER, state.burnTicks);
        pdc.set(cartTargetKey, PersistentDataType.DOUBLE, state.targetSpeed);
        if (state.inv != null) {
            String data = encodeCartInv(state.type, state.inv);
            if (data != null) pdc.set(cartInvKey, PersistentDataType.STRING, data);
        }
    }

    /** Serialize a cart's inventory for the PDC. The blast cart only persists its three fuel slots — its
     *  filler panes + speed buttons are re-stamped by {@link #decorateBlastGui} and must never be saved
     *  (else they'd become extractable / duplicate on reload). */
    private String encodeCartInv(CartType type, Inventory inv) {
        if (type != CartType.BLAST) return InventoryUtil.encode(inv);
        Inventory tmp = Bukkit.createInventory(null, 9);
        for (int i = 0; i < BLAST_FUEL_SLOTS; i++) tmp.setItem(i, inv.getItem(i));
        return InventoryUtil.encode(tmp);
    }

    /** Lay out the blast cart's 9-slot speed GUI: consolidate every fuel item into slots 0-2 (dropping any
     *  overflow at the cart — this also migrates legacy 5-slot HOPPER carts and strips stale filler/buttons),
     *  fill 3-6 with inert panes, and place the −/+ speed buttons in 7/8. */
    private void decorateBlastGui(CartState state) {
        Inventory inv = state.inv;
        List<ItemStack> fuel = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir() && config.isFuel(it.getType())) fuel.add(it);
        }
        inv.clear();
        ItemStack[] slots = new ItemStack[BLAST_FUEL_SLOTS];
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack it : fuel) {
            int amt = it.getAmount(), max = it.getMaxStackSize();
            for (int s = 0; s < BLAST_FUEL_SLOTS && amt > 0; s++) {
                if (slots[s] == null) {
                    slots[s] = it.clone(); slots[s].setAmount(Math.min(amt, max)); amt -= slots[s].getAmount();
                } else if (slots[s].isSimilar(it) && slots[s].getAmount() < max) {
                    int add = Math.min(amt, max - slots[s].getAmount());
                    slots[s].setAmount(slots[s].getAmount() + add); amt -= add;
                }
            }
            if (amt > 0) { ItemStack o = it.clone(); o.setAmount(amt); overflow.add(o); }
        }
        for (int s = 0; s < BLAST_FUEL_SLOTS; s++) inv.setItem(s, slots[s]);
        ItemStack pane = fillerPane();
        for (int i = BLAST_FUEL_SLOTS; i < BLAST_SLOT_MINUS; i++) inv.setItem(i, pane.clone());
        inv.setItem(BLAST_SLOT_MINUS, blastSpeedButton(false));
        inv.setItem(BLAST_SLOT_PLUS, blastSpeedButton(true));
        for (ItemStack o : overflow) state.cart.getWorld().dropItemNaturally(state.cart.getLocation(), o);
    }

    private static ItemStack fillerPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);
        return pane;
    }

    private ItemStack blastSpeedButton(boolean plus) {
        Component name = Component.text(plus ? "Speed +" : "Speed −",
            plus ? NamedTextColor.GREEN : NamedTextColor.RED);
        List<Component> lore = List.of(Component.text(
            "Click to " + (plus ? "raise" : "lower") + " the cruise speed", NamedTextColor.GRAY));
        return HeadUtil.createHead(plus ? BLAST_PLUS_TEX : BLAST_MINUS_TEX, 1, name, lore, Map.of());
    }

    /** Step the blast cart's cruise target by {@code delta} redstone levels (0..15 of controllerMaxSpeed),
     *  writing the same {@code targetSpeed} field a controller rail sets, then refresh the GUI title. */
    private void adjustBlastSpeed(CartState state, int delta, InventoryClickEvent event) {
        double max = config.controllerMaxSpeed;
        if (max <= 0) return;
        double cur = state.targetSpeed < 0 ? max : state.targetSpeed;
        int level = Math.max(0, Math.min(15, (int) Math.round(cur / max * 15.0) + delta));
        state.targetSpeed = max * level / 15.0;
        event.getView().setTitle(cartGuiTitle(state));
        Location at = event.getWhoClicked().getLocation();
        at.getWorld().playSound(at, Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    /** The cart type of an entity, from its PDC marker, or null if it isn't one of ours. */
    private CartType typeOf(Minecart cart) {
        String t = cart.getPersistentDataContainer().get(cartTypeKey, PersistentDataType.STRING);
        if (t == null) return null;
        for (CartType type : CartType.values()) if (type.pdc.equals(t)) return type;
        return null;
    }

    /** Remove and return one whitelisted fuel item from this cart's inventory (tender feeding), or null. */
    ItemStack takeOneFuelFrom(Minecart cart) {
        CartState s = tracked.get(cart.getUniqueId());
        return (s != null && s.inv != null) ? takeOneFuel(s.inv) : null;
    }

    /** Stop tracking a bmc cart and return its drops (the custom cart item + every inventory item), or
     *  null if it isn't one of ours. Used by the destructor rail — the caller removes the entity itself.
     *  Closes any open GUI and clears the inventory so nothing is dropped twice. */
    java.util.List<ItemStack> collectCartDrops(Minecart cart) {
        CartState state = tracked.remove(cart.getUniqueId());
        if (state == null) return null;
        removeBurnLight(cart.getUniqueId());   // drop the lit-blast light with the cart
        java.util.List<ItemStack> out = new ArrayList<>();
        CustomHeadBlock itemType = registry.getType(state.type.itemId);
        if (itemType != null) out.add(itemType.createItem(1));
        out.addAll(cartInvDrops(state));
        return out;
    }

    /** Droppable inventory contents for a destroyed cart: the blast cart yields only its three fuel slots
     *  (its filler panes + speed buttons are UI, not loot); every other cart yields all contents. Closes any
     *  open viewers and clears the inventory. */
    private List<ItemStack> cartInvDrops(CartState state) {
        List<ItemStack> out = new ArrayList<>();
        if (state.inv == null) return out;
        for (var v : new ArrayList<>(state.inv.getViewers())) v.closeInventory();
        int n = state.type == CartType.BLAST ? BLAST_FUEL_SLOTS : state.inv.getSize();
        for (int i = 0; i < n; i++) {
            ItemStack s = state.inv.getItem(i);
            if (s != null && !s.getType().isAir()) out.add(s);
        }
        state.inv.clear();
        return out;
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
            m.getPersistentDataContainer().set(cartTypeKey, PersistentDataType.STRING, type.pdc);
        });
        CartState state = new CartState(cart, type);
        buildInv(state);
        tracked.put(cart.getUniqueId(), state);
        if (type == CartType.DISPENSER) applyDispenserFacing(state);   // render the DISPENSER facing UP
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
        ItemStack mainHand = event.getPlayer().getInventory().getItemInMainHand();
        Material held = mainHand.getType();
        if (held == Material.SHEARS || isChain(held)) return;

        event.setCancelled(true);   // suppress the rideable mount
        org.bukkit.inventory.InventoryView view = event.getPlayer().openInventory(state.inv);
        // A blast cart shows its remembered controller cruise target next to its name (set at open time,
        // like the Engine/Burner fuel readout). Mirrors RotationBlocks' view.setTitle pattern.
        if (view != null && state.type == CartType.BLAST) view.setTitle(cartGuiTitle(state));
    }

    /** GUI title for a cart: the block's display name, plus (for a blast cart) its remembered cruise target. */
    private String cartGuiTitle(CartState state) {
        CustomHeadBlock type = registry.getType(state.type.itemId);
        String base = (type != null && type.name() != null)
            ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(type.name())
            : state.type.itemId;
        if (state.type != CartType.BLAST) return base;
        double eff = state.targetSpeed < 0 ? config.controllerMaxSpeed : state.targetSpeed;
        return base + " §7- " + String.format("%.1f m/s", eff * MPS_PER_BT);
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
        CartState st = tracked.get(cart.getUniqueId());
        CartType type = st != null ? st.type : null;   // blast → persist only its fuel slots
        String data = encodeCartInv(type, event.getInventory());
        if (data != null) {
            cart.getPersistentDataContainer().set(cartInvKey, PersistentDataType.STRING, data);
        }
    }

    // ── Tick: fuel intake from neighbours + blast-furnace burn ──────────────

    private void tick() {
        ticks++;
        boolean feedTick = (ticks % 8 == 0);
        // Snapshot: ticking a cart can spawn entities / move it across a chunk border, which fires
        // EntitiesLoadEvent → scanChunk → tracked.put and would CME a live iterator. Carts added mid-tick
        // are simply picked up next tick.
        for (CartState state : new ArrayList<>(tracked.values())) {
            UUID id = state.cart.getUniqueId();
            if (state.cart.isDead()) { removeBurnLight(id); tracked.remove(id); continue; }
            if (!state.cart.isValid()) continue;   // chunk unloading; onEntitiesUnload owns cleanup
            if (feedTick) tryFillFromNeighbors(state);
            if (state.type == CartType.BLAST) tickBlast(state);
            else if (state.type == CartType.DISPENSER) tickDispenser(state);
        }
    }

    /** Render a dispenser cart's block facing UP, matching its fixed straight-up fire direction. A minecart
     *  display block rotates with the cart's yaw, so only the vertical axis stays put — hence up-only. */
    private void applyDispenserFacing(CartState state) {
        org.bukkit.block.data.BlockData bd = Bukkit.createBlockData(Material.DISPENSER);
        if (bd instanceof org.bukkit.block.data.Directional d) { d.setFacing(BlockFace.UP); bd = d; }
        state.cart.setDisplayBlockData(bd);
    }

    /** Fire one item each time the cart rolls onto a powered activator rail (rising edge). */
    private void tickDispenser(CartState state) {
        boolean powered = isOnPoweredActivatorRail(state.cart);
        if (powered && !state.wasOnActivator) fireOne(state);
        state.wasOnActivator = powered;
    }

    /** True if the cart sits on a powered activator rail (checks the cart's block, then one below). */
    private static boolean isOnPoweredActivatorRail(Minecart cart) {
        Block b = cart.getLocation().getBlock();
        if (b.getType() != Material.ACTIVATOR_RAIL) b = b.getRelative(0, -1, 0);
        return b.getType() == Material.ACTIVATOR_RAIL
            && b.getBlockData() instanceof org.bukkit.block.data.type.RedstoneRail rr && rr.isPowered();
    }

    /** Launch speed for projectile payloads (arrows, snowballs, …) fired by a dispenser cart. */
    private static final double PROJECTILE_SPEED = 1.5;

    /** Fire one item from the cart's inventory like an up-facing dispenser: a projectile item (arrow,
     *  snowball, potion, …) spawns as its REAL projectile entity launched straight up; anything else is
     *  ejected as an item entity with dispenser-like scatter. */
    private void fireOne(CartState state) {
        ItemStack one = takeOne(state.inv, m -> true);
        if (one == null) return;
        World w = state.cart.getWorld();
        org.bukkit.util.Vector dir = new org.bukkit.util.Vector(0, 1, 0);   // fixed: straight up
        Location mouth = state.cart.getLocation().add(0, 1.0, 0);           // 0.5 body + 0.5 along +Y
        if (!dispenseProjectile(w, mouth, dir, one)) ejectItem(w, mouth, dir, one);
        w.playSound(mouth, org.bukkit.Sound.BLOCK_DISPENSER_DISPENSE, 0.8f, 1.0f);
    }

    /** Spawn {@code item} as its real dispenser projectile aimed along {@code dir}; returns false if the
     *  item isn't a projectile payload (caller ejects it as an item instead). Covers the common ones. */
    private boolean dispenseProjectile(World w, Location loc, org.bukkit.util.Vector dir, ItemStack item) {
        switch (item.getType()) {
            case ARROW, TIPPED_ARROW, SPECTRAL_ARROW -> {
                Class<? extends org.bukkit.entity.AbstractArrow> type = item.getType() == Material.SPECTRAL_ARROW
                    ? org.bukkit.entity.SpectralArrow.class : org.bukkit.entity.Arrow.class;
                org.bukkit.entity.AbstractArrow arrow = w.spawnArrow(loc, dir, (float) PROJECTILE_SPEED, 3f, type);
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.ALLOWED);
                if (arrow instanceof org.bukkit.entity.Arrow a
                    && item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                    if (pm.getBasePotionType() != null) a.setBasePotionType(pm.getBasePotionType());
                    for (org.bukkit.potion.PotionEffect e : pm.getCustomEffects()) a.addCustomEffect(e, true);
                }
            }
            case SNOWBALL -> spawnProjectile(w, loc, dir, org.bukkit.entity.Snowball.class);
            case EGG -> spawnProjectile(w, loc, dir, org.bukkit.entity.Egg.class);
            case ENDER_PEARL -> spawnProjectile(w, loc, dir, org.bukkit.entity.EnderPearl.class);
            case EXPERIENCE_BOTTLE -> spawnProjectile(w, loc, dir, org.bukkit.entity.ThrownExpBottle.class);
            case FIRE_CHARGE -> spawnProjectile(w, loc, dir, org.bukkit.entity.SmallFireball.class);
            case WIND_CHARGE -> spawnProjectile(w, loc, dir, org.bukkit.entity.WindCharge.class);
            case SPLASH_POTION, LINGERING_POTION -> {
                org.bukkit.entity.ThrownPotion p = w.spawn(loc, org.bukkit.entity.ThrownPotion.class);
                p.setItem(item);
                p.setVelocity(dir.clone().multiply(PROJECTILE_SPEED));
            }
            case FIREWORK_ROCKET -> {
                org.bukkit.entity.Firework f = w.spawn(loc, org.bukkit.entity.Firework.class);
                if (item.getItemMeta() instanceof org.bukkit.inventory.meta.FireworkMeta fm) f.setFireworkMeta(fm);
                f.setVelocity(dir.clone().multiply(PROJECTILE_SPEED));
            }
            default -> { return false; }
        }
        return true;
    }

    private static void spawnProjectile(World w, Location loc, org.bukkit.util.Vector dir,
                                        Class<? extends org.bukkit.entity.Projectile> type) {
        org.bukkit.entity.Projectile p = w.spawn(loc, type);
        p.setVelocity(dir.clone().multiply(PROJECTILE_SPEED));
    }

    /** Eject a non-projectile item like a dispenser: launch along the fire axis + perpendicular scatter. */
    private void ejectItem(World w, Location loc, org.bukkit.util.Vector dir, ItemStack item) {
        var r = java.util.concurrent.ThreadLocalRandom.current();
        double launch = config.dispenseVelocity, spread = 0.0172275 * 6;
        org.bukkit.util.Vector v = new org.bukkit.util.Vector(
            dir.getX() * launch + (dir.getX() == 0 ? triangle(r, 0, spread) : 0),
            dir.getY() * launch + (dir.getY() == 0 ? triangle(r, 0, spread) : 0),
            dir.getZ() * launch + (dir.getZ() == 0 ? triangle(r, 0, spread) : 0));
        org.bukkit.entity.Item drop = w.dropItem(loc, item);
        drop.setVelocity(v);
        drop.setPickupDelay(20);
    }

    /** Vanilla {@code RandomSource.triangle(center, range)}: {@code center + range*(rand − rand)}. */
    private static double triangle(java.util.random.RandomGenerator r, double center, double range) {
        return center + range * (r.nextDouble() - r.nextDouble());
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
                pullInto(state, c.getInventory());
            }
        }

        Block above = base.getRelative(BlockFace.UP);
        if (!(above.getBlockData() instanceof org.bukkit.block.data.type.Hopper)
            && above.getState() instanceof Container c) {
            pullInto(state, c.getInventory());   // cart self-pulls from a container above
        }
    }

    /** Move one accepted item from {@code source} into the cart's inventory; bounce it back if full.
     *  Fuel carts accept only fuel; the dispenser cart accepts any item. */
    private void pullInto(CartState state, Inventory source) {
        ItemStack one = takeOne(source, state.type.fuelOnly ? config::isFuel : m -> true);
        if (one == null) return;
        Map<Integer, ItemStack> leftover = state.inv.addItem(one);
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

        // Burn-DOWN is driven by CartTrainManager while the train moves (parked engines stay primed but
        // don't drain — see consumeBurnTick / C4). Here we only ignite and show the "lit" smoke.
        if (state.burnTicks > 0) {
            // Chimney-style rising smoke from the top of the blast furnace while lit (throttled).
            if (cart.getWorld() != null && ticks % 3 == 0) {
                cart.getWorld().spawnParticle(org.bukkit.Particle.CAMPFIRE_COSY_SMOKE,
                    cart.getLocation().add(0, 0.9, 0), 1, 0.06, 0.02, 0.06, 0.012);
            }
        }

        updateBurnLight(state);
    }

    // ── DynLight: lit blast cart glows via an optional companion plugin (tag-only, no dependency) ──

    /** Sync a blast cart's DynLight marker with its burn state + position: spawn/follow while lit,
     *  remove when it goes out. No-op (and removes any marker) when dynamic lights are disabled. */
    private void updateBurnLight(CartState state) {
        UUID id = state.cart.getUniqueId();
        if (config.dynamicLights && state.burnTicks > 0) {
            BlockDisplay marker = burnLight.get(id);
            Location loc = state.cart.getLocation();
            if (marker == null || !marker.isValid()) {
                marker = state.cart.getWorld().spawn(loc, BlockDisplay.class, d -> {
                    d.setBlock(Bukkit.createBlockData(Material.AIR));
                    d.setPersistent(false);   // not saved to disk; removed on chunk unload → no orphans
                    d.setGravity(false);
                    d.setViewRange(64f);
                    d.addScoreboardTag(DynLightTags.tag(BLAST_LIGHT_LEVEL)); // set at spawn so DynLight detects it
                });
                burnLight.put(id, marker);
            } else {
                marker.teleport(loc);
            }
        } else {
            removeBurnLight(id);
        }
    }

    /** Remove a cart's DynLight marker (→ EntityRemoveEvent → DynLight drops the light). */
    private void removeBurnLight(UUID id) {
        BlockDisplay marker = burnLight.remove(id);
        if (marker != null && marker.isValid()) marker.remove();
    }

    // ── Accessors for CartTrainManager (engine power + tender fuel) ──────────

    /** True if this minecart is our blast-furnace cart (a train engine). */
    boolean isBlastCart(Minecart cart) { return typeOf(cart) == CartType.BLAST; }

    /** True if this minecart is our coal tender. */
    boolean isCoalCart(Minecart cart) { return typeOf(cart) == CartType.COAL; }

    /** Remaining burn ticks for a blast-furnace cart (0 if not a blast cart). Falls back to the entity
     *  PDC when the cart isn't in {@code tracked} yet, so an engine adopted a tick behind the train
     *  manager isn't briefly seen as unfuelled (which would stutter the train / skew its accel ratio). */
    int burnTicks(Minecart cart) {
        CartState s = tracked.get(cart.getUniqueId());
        if (s != null && s.type == CartType.BLAST) return s.burnTicks;
        if (typeOf(cart) == CartType.BLAST) {
            Integer stored = cart.getPersistentDataContainer().get(fuelTicksKey, PersistentDataType.INTEGER);
            return stored != null ? stored : 0;
        }
        return 0;
    }

    /** Consume one burn tick from a driven blast-furnace cart. The train drive loop calls this only while
     *  the train is actually moving, so a fuelled engine parked at a dead end doesn't silently drain. */
    void consumeBurnTick(Minecart cart) {
        CartState s = tracked.get(cart.getUniqueId());
        if (s != null && s.type == CartType.BLAST && s.burnTicks > 0) s.burnTicks--;
    }

    /** Top up a blast-furnace cart's burn counter (tender feeding). No-op if not a tracked blast cart. */
    void addBurnTicks(Minecart cart, int add) {
        CartState s = tracked.get(cart.getUniqueId());
        if (s != null && s.type == CartType.BLAST) s.burnTicks += add;
    }

    /** A blast cart's remembered controller cruise target (b/t); {@code -1} = unset → full speed. */
    double targetSpeed(Minecart cart) {
        CartState s = tracked.get(cart.getUniqueId());
        return s != null ? s.targetSpeed : -1;
    }

    /** Set a blast cart's remembered controller cruise target (persisted to PDC on unload/shutdown). */
    void setTargetSpeed(Minecart cart, double v) {
        CartState s = tracked.get(cart.getUniqueId());
        if (s != null) s.targetSpeed = v;
    }

    /** Whether a material is accepted as fuel (delegates to the shared whitelist). */
    boolean isFuel(Material m) { return config.isFuel(m); }

    /** Burn ticks one unit of a fuel material yields. */
    int fuelBurnTicks(Material m) { return config.burnTicks(m); }

    /** Remove and return one whitelisted fuel item (amount 1) from an inventory, or null. */
    private ItemStack takeOneFuel(Inventory inv) {
        return takeOne(inv, config::isFuel);
    }

    /** Remove and return one item (amount 1) accepted by {@code accept} from an inventory, or null. */
    private static ItemStack takeOne(Inventory inv, java.util.function.Predicate<Material> accept) {
        if (inv == null) return null;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir() || !accept.test(stack.getType())) continue;
            ItemStack one = stack.clone();
            one.setAmount(1);
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(i, stack.getAmount() > 0 ? stack : null);
            return one;
        }
        return null;
    }

    // ── Fuel-only inventory guards ──────────────────────────────────────────

    /** Whether an inventory belongs to a cart we manage. */
    private boolean isCartInventory(InventoryHolder holder) {
        return holder instanceof CartStorageHolder;
    }

    /** Whether a cart GUI is fuel-restricted (coal/blast) vs accepts any item (dispenser). Defaults to
     *  restrictive if the cart isn't tracked, so we never accidentally open a fuel cart to arbitrary items. */
    private boolean isFuelOnly(InventoryHolder holder) {
        if (!(holder instanceof CartStorageHolder h)) return true;
        CartState s = tracked.get(h.cart().getUniqueId());
        return s == null || s.type.fuelOnly;
    }

    /** The {@link CartState} if {@code holder} is a blast cart's inventory, else null. */
    private CartState blastState(InventoryHolder holder) {
        if (!(holder instanceof CartStorageHolder h)) return null;
        CartState s = tracked.get(h.cart().getUniqueId());
        return (s != null && s.type == CartType.BLAST) ? s : null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isCartInventory(top.getHolder())) return;
        if (!isFuelOnly(top.getHolder())) return;   // dispenser cart: any item allowed

        // Blast cart: the 9-slot speed GUI. Intercept the −/+ buttons and inert filler on top-inv clicks;
        // fuel slots 0-2 fall through to the fuel-only rules below.
        CartState blast = blastState(top.getHolder());
        if (blast != null && event.getClickedInventory() == top) {
            int slot = event.getSlot();
            if (slot == BLAST_SLOT_MINUS) { event.setCancelled(true); adjustBlastSpeed(blast, -1, event); return; }
            if (slot == BLAST_SLOT_PLUS)  { event.setCancelled(true); adjustBlastSpeed(blast, +1, event); return; }
            if (slot >= BLAST_FUEL_SLOTS) { event.setCancelled(true); return; }   // filler (3-6)
        }

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
        if (!isFuelOnly(top.getHolder())) return;   // dispenser cart: any item allowed
        // Blast cart: never let a drag touch the filler / button slots (3-8).
        CartState blast = blastState(top.getHolder());
        if (blast != null) {
            for (int raw : event.getRawSlots()) {
                if (raw < top.getSize() && raw >= BLAST_FUEL_SLOTS) { event.setCancelled(true); return; }
            }
        }
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
        removeBurnLight(cart.getUniqueId());   // drop the lit-blast light with the cart

        event.setCancelled(true);
        World world = cart.getWorld();
        org.bukkit.Location loc = cart.getLocation();

        // Drop the custom cart item (not a vanilla minecart) + its inventory contents (fuel only for blast).
        CustomHeadBlock itemType = registry.getType(state.type.itemId);
        if (itemType != null) world.dropItemNaturally(loc, itemType.createItem(1));
        for (ItemStack stack : cartInvDrops(state)) world.dropItemNaturally(loc, stack);
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
