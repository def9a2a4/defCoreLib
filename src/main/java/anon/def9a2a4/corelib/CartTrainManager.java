package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import io.papermc.paper.entity.TeleportFlag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minecart trains (drive-model B — position-driven, arc-length rail walking). Couples arbitrary
 * minecarts with a chain item into consists of any length; a fuelled furnace / blast-furnace cart
 * anywhere in the train powers the whole thing (max speed independent of length, acceleration inversely
 * proportional to it). A lone blast-furnace cart is a size-1 train, so one drive path handles both.
 *
 * <p>Movement is computed, not physics-driven: each tick the leader is walked forward along the rails
 * by the train's speed ({@link RailPathWalker}) and every follower is placed at a fixed arc-gap behind
 * it (validated smooth in the M3a spike). Mechanism carts and {@code corelib:frozen} carts are refused
 * coupling (minecarts-v2 contract). Curve slowdown is intentionally omitted.
 */
final class CartTrainManager implements Listener {

    private static final String TAG_MEMBER = "corelib:train_member";
    private static final String TAG_BLAST = "corelib:blast_furnace_cart";
    private static final String TAG_MECHANISM = "corelib:mechanism_minecart";
    private static final String TAG_FROZEN = "corelib:frozen";
    private static final String TAG_CHAIN = "corelib:cart_chain";
    private static final long PENDING_TIMEOUT_TICKS = 200L;
    // Chain-coupler visual. Each coupling gets one BlockDisplay teleported to the midpoint of its two carts
    // (position channel) with the transform matrix carrying ONLY orientation+scale (matrix channel) — the two
    // client interpolators then control orthogonal DOF and can't beat against each other. See updateChainDisplays.
    private static final double CHAIN_Y = 0.3d;     // chain height above the cart location (tune in-game)
    private static final double CHAIN_LEAD = 1.0d;  // forward lead (× speed) matching the carts' client dead-reckon so the chain isn't drawn trailing
    private static final double HALF_CART = 0.5d;   // half a minecart length — bridge the adjacent ends, not centre-to-centre
    private static final float CHAIN_WIDTH = 1.0f;  // natural chain-model thickness (ChainPulley convention)
    private static final int CHAIN_INTERP = 2;      // matrix-interpolation ticks so orientation eases through curves instead of popping

    // 1.21.9 (Copper Age) renamed CHAIN → IRON_CHAIN; resolve by name so it works on 1.21.8..1.21.11+
    // (mirrors ChainPulley.resolveChain). Used for the display block, the drop item, and the item check.
    private static final Material CHAIN_MATERIAL = resolveChain();

    private static Material resolveChain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        return m != null ? m : Material.matchMaterial("CHAIN");
    }

    private final JavaPlugin plugin;
    private final CartConfig config;
    private final CustomCartManager carts;
    private final NamespacedKey linksKey;   // entity PDC: comma-joined partner UUIDs (≤2)

    private final Map<UUID, Member> members = new HashMap<>();
    private final List<CartTrain> trains = new ArrayList<>();
    private final Map<UUID, PendingLink> pending = new HashMap<>();
    private boolean dirty = true;   // trains need rebuilding
    private long tickCount;
    private BukkitTask tickTask;
    private CartRailsManager rails;   // junction alignment + destructor rail (position-driven carts)

    void setRailsManager(CartRailsManager rails) { this.rails = rails; }

    CartTrainManager(JavaPlugin plugin, CartConfig config, CustomCartManager carts) {
        this.plugin = plugin;
        this.config = config;
        this.carts = carts;
        this.linksKey = new NamespacedKey(plugin, "cart_links");
    }

    private static final class Member {
        final Minecart cart;
        Member(Minecart cart) { this.cart = cart; }
    }

    private static final class CartTrain {
        List<Member> order = new ArrayList<>();   // one endpoint → the other; index 0 = front once moving
        double speed;
        boolean moving;                            // heading established (from a push)?
        RailPathWalker.RailState leaderState;
        final List<ChainLink> chainLinks = new ArrayList<>();   // one per coupling (size = order-1)
    }

    /** One chain coupling: the visible chain {@code display} rides an invisible {@code carrier}
     *  {@code BlockDisplay(AIR)} as a passenger. The plugin teleports only the carrier (position); the
     *  display is never teleported, so its transform interpolation is never perturbed → no vertical flash.
     *  Mirrors the mechanism moving-display pattern ({@code MechanismRegistry}/{@code BasicMechanism}). */
    private static final class ChainLink {
        final BlockDisplay carrier;   // invisible AIR display, teleported each tick — carries the position
        final BlockDisplay display;   // the chain, a passenger of the carrier — never teleported
        ChainLink(BlockDisplay carrier, BlockDisplay display) {
            this.carrier = carrier;
            this.display = display;
        }
        boolean dead() { return carrier == null || carrier.isDead() || display == null || display.isDead(); }
        void despawn() {
            if (display != null && !display.isDead()) display.remove();   // passenger first
            if (carrier != null && !carrier.isDead()) carrier.remove();
        }
    }

    private record PendingLink(UUID cart, long expiry) {}

    // ── Lifecycle ───────────────────────────────────────────────────────────

    void register() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                sweepOrphanChains(chunk);   // reap chain entities orphaned by a crash / reload before re-adopting
                scanChunk(chunk);
            }
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void shutdown() {
        if (tickTask != null) tickTask.cancel();
        for (CartTrain t : trains) { park(t); clearChainDisplays(t); }   // park first: no runaway on /reload
        members.clear();
        trains.clear();
        pending.clear();
    }

    /** Register a just-spawned drivable cart (e.g. a solo blast-furnace engine) so it drives without
     *  waiting for a chunk reload. Idempotent. */
    void trackCart(Minecart cart) {
        if (members.putIfAbsent(cart.getUniqueId(), new Member(cart)) == null) dirty = true;
    }

    /** Adopt drivable carts in a freshly-loaded chunk: linked carts (tag) and blast-furnace engines. */
    void scanChunk(Chunk chunk) {
        List<Minecart> added = new ArrayList<>();
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Minecart cart)) continue;
            if (members.containsKey(cart.getUniqueId())) continue;
            Set<String> tags = cart.getScoreboardTags();
            if (tags.contains(TAG_MEMBER) || tags.contains(TAG_BLAST)) {
                members.put(cart.getUniqueId(), new Member(cart));
                added.add(cart);
                dirty = true;
            }
        }
        for (Minecart cart : added) reconcileLinks(cart);
    }

    /** Drop any coupling on {@code cart} whose partner is loaded but doesn't list {@code cart} back —
     *  a stale one-directional link left when the cart was uncoupled while its partner was unloaded.
     *  Without this, reloading the partner would silently re-form a train the player explicitly split. */
    private void reconcileLinks(Minecart cart) {
        for (UUID pid : partners(cart)) {
            if (Bukkit.getEntity(pid) instanceof Minecart other && !partners(other).contains(cart.getUniqueId())) {
                removePartner(cart, pid);
                dirty = true;
            }
            // Unresolvable partner (unloaded or dead): leave it; reconciled when it (re)loads.
        }
    }

    void onEntitiesUnload(List<Entity> entities) {
        for (Entity e : entities) {
            if (members.remove(e.getUniqueId()) != null) dirty = true;
        }
    }

    // ── Linking / unlinking ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Minecart cart)) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();

        if (held.getType() == Material.SHEARS) {
            if (linkCount(cart) > 0) {
                event.setCancelled(true);
                unlinkAll(cart);
                player.sendActionBar(Component.text("Uncoupled.", NamedTextColor.YELLOW));
            }
            return;
        }
        if (held.getType() != CHAIN_MATERIAL) return;
        event.setCancelled(true);   // don't mount while coupling
        handleChainClick(player, cart, held);
    }

    private void handleChainClick(Player player, Minecart cart, ItemStack held) {
        if (!canCouple(cart)) {
            player.sendActionBar(Component.text("That cart can't be coupled.", NamedTextColor.RED));
            return;
        }
        PendingLink p = pending.get(player.getUniqueId());
        boolean valid = p != null && tickCount <= p.expiry() && Bukkit.getEntity(p.cart()) instanceof Minecart;
        if (!valid || p.cart().equals(cart.getUniqueId())) {
            if (valid && p.cart().equals(cart.getUniqueId())) {
                pending.remove(player.getUniqueId());
                player.sendActionBar(Component.text("Coupling cancelled.", NamedTextColor.GRAY));
                return;
            }
            pending.put(player.getUniqueId(), new PendingLink(cart.getUniqueId(), tickCount + PENDING_TIMEOUT_TICKS));
            player.sendActionBar(Component.text("Coupling — right-click the other cart.", NamedTextColor.AQUA));
            cart.getWorld().spawnParticle(Particle.WAX_ON, cart.getLocation().add(0, 0.6, 0), 8, 0.2, 0.2, 0.2, 0);
            return;
        }

        Minecart other = (Minecart) Bukkit.getEntity(p.cart());
        pending.remove(player.getUniqueId());
        if (other == null || other.equals(cart)) return;
        if (!canCouple(other)) {
            player.sendActionBar(Component.text("That cart can't be coupled.", NamedTextColor.RED));
            return;
        }
        if (linkCount(cart) >= 2 || linkCount(other) >= 2) {
            player.sendActionBar(Component.text("A cart can hold at most two couplings.", NamedTextColor.RED));
            return;
        }
        if (partners(cart).contains(other.getUniqueId())) {
            player.sendActionBar(Component.text("Already coupled.", NamedTextColor.RED));
            return;
        }
        if (cart.getWorld() != other.getWorld()
            || cart.getLocation().distance(other.getLocation()) > config.maxLinkDistance) {
            player.sendActionBar(Component.text("Too far apart to couple.", NamedTextColor.RED));
            return;
        }
        if (reachable(cart, other, new HashSet<>())) {
            player.sendActionBar(Component.text("That would loop the train.", NamedTextColor.RED));
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
        addPartner(cart, other);
        addPartner(other, cart);
        members.putIfAbsent(cart.getUniqueId(), new Member(cart));
        members.putIfAbsent(other.getUniqueId(), new Member(other));
        cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 1f);
        player.sendActionBar(Component.text("Coupled!", NamedTextColor.GREEN));

        // Rebuild now and snap the new consist into a tidy formation on the rail (if it isn't moving).
        rebuildTrains();
        CartTrain t = trainContaining(cart);
        if (t != null && !t.moving) snapFormation(t);
        else dirty = true;
    }

    private boolean canCouple(Minecart cart) {
        Set<String> tags = cart.getScoreboardTags();
        return !tags.contains(TAG_MECHANISM) && !tags.contains(TAG_FROZEN);
    }

    /** Remove all of {@code cart}'s couplings, dropping one chain each. */
    private void unlinkAll(Minecart cart) {
        Set<UUID> ps = partners(cart);
        int dropped = 0;
        for (UUID pid : ps) {
            if (Bukkit.getEntity(pid) instanceof Minecart other) {
                removePartner(other, cart.getUniqueId());
                parkCart(other);   // instant: don't leave a former partner at maxSpeed 100
            }
            dropped++;
        }
        writePartners(cart, Collections.emptySet());
        cart.removeScoreboardTag(TAG_MEMBER);
        parkCart(cart);
        if (dropped > 0) dropChains(cart.getLocation(), dropped);
        dirty = true;
    }

    // ── Destruction ──────────────────────────────────────────────────────────

    // NORMAL priority: runs before CustomCartManager's HIGH handler (which drops the custom item and
    // removes the cart) and before vanilla drops a plain minecart. We only sever links + drop chains.
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDestroyed(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        severAndDrop(cart);
    }

    /** Sever every coupling on {@code cart}, dropping one chain per severed link. */
    void severAndDrop(Minecart cart) {
        Set<UUID> ps = partners(cart);
        if (ps.isEmpty() && !members.containsKey(cart.getUniqueId())) return;
        int dropped = 0;
        for (UUID pid : ps) {
            if (Bukkit.getEntity(pid) instanceof Minecart other) {
                removePartner(other, cart.getUniqueId());
                parkCart(other);   // instant: a surviving neighbour must not keep maxSpeed 100
            }
            dropped++;
        }
        if (dropped > 0) dropChains(cart.getLocation(), dropped);
        members.remove(cart.getUniqueId());
        dirty = true;
    }

    private void dropChains(Location loc, int count) {
        loc.getWorld().dropItemNaturally(loc, new ItemStack(CHAIN_MATERIAL, count));
    }

    /** Detach {@code cart} from its train and RETURN its chain drops instead of dropping them, so a caller
     *  (the destructor rail) can route them into a container. Mirrors {@link #severAndDrop} otherwise. */
    List<ItemStack> detachAndCollectChains(Minecart cart) {
        Set<UUID> ps = partners(cart);
        int dropped = 0;
        for (UUID pid : ps) {
            if (Bukkit.getEntity(pid) instanceof Minecart other) {
                removePartner(other, cart.getUniqueId());
                parkCart(other);
            }
            dropped++;
        }
        writePartners(cart, Collections.emptySet());
        cart.removeScoreboardTag(TAG_MEMBER);
        members.remove(cart.getUniqueId());
        dirty = true;
        List<ItemStack> out = new ArrayList<>();
        if (dropped > 0) out.add(new ItemStack(CHAIN_MATERIAL, dropped));
        return out;
    }

    // ── PDC coupling storage ─────────────────────────────────────────────────

    private Set<UUID> partners(Minecart cart) {
        String raw = cart.getPersistentDataContainer().get(linksKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return new HashSet<>();
        Set<UUID> out = new HashSet<>();
        for (String s : raw.split(",")) {
            try { out.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    private int linkCount(Minecart cart) { return partners(cart).size(); }

    private void writePartners(Minecart cart, Set<UUID> set) {
        if (set.isEmpty()) {
            cart.getPersistentDataContainer().remove(linksKey);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (UUID u : set) { if (sb.length() > 0) sb.append(','); sb.append(u); }
        cart.getPersistentDataContainer().set(linksKey, PersistentDataType.STRING, sb.toString());
    }

    private void addPartner(Minecart cart, Minecart other) {
        Set<UUID> set = partners(cart);
        set.add(other.getUniqueId());
        writePartners(cart, set);
        cart.addScoreboardTag(TAG_MEMBER);
        cart.setPersistent(true);
    }

    private void removePartner(Minecart cart, UUID other) {
        Set<UUID> set = partners(cart);
        set.remove(other);
        writePartners(cart, set);
        if (set.isEmpty()) cart.removeScoreboardTag(TAG_MEMBER);
    }

    /** Whether {@code target} is reachable from {@code start} through loaded couplings (cycle guard). */
    private boolean reachable(Minecart start, Minecart target, Set<UUID> seen) {
        if (start.equals(target)) return true;
        seen.add(start.getUniqueId());
        for (UUID pid : partners(start)) {
            if (seen.contains(pid)) continue;
            if (pid.equals(target.getUniqueId())) return true;
            if (Bukkit.getEntity(pid) instanceof Minecart next && reachable(next, target, seen)) return true;
        }
        return false;
    }

    // ── Train assembly ────────────────────────────────────────────────────────

    private void rebuildTrains() {
        dirty = false;
        // Snapshot old speeds so a rebuild keeps momentum (keyed by any surviving member).
        Map<UUID, Double> oldSpeed = new HashMap<>();
        Set<UUID> oldMembers = new HashSet<>();
        for (CartTrain t : trains) for (Member m : t.order) {
            oldSpeed.put(m.cart.getUniqueId(), t.speed);
            oldMembers.add(m.cart.getUniqueId());
        }
        for (CartTrain t : trains) clearChainDisplays(t);   // fresh trains respawn their own chains
        trains.clear();

        Set<UUID> visited = new HashSet<>();
        for (Member seed : members.values()) {
            if (visited.contains(seed.cart.getUniqueId())) continue;
            List<Member> comp = new ArrayList<>();
            // BFS the loaded coupling graph.
            List<Member> queue = new ArrayList<>();
            queue.add(seed); visited.add(seed.cart.getUniqueId());
            while (!queue.isEmpty()) {
                Member cur = queue.remove(queue.size() - 1);
                comp.add(cur);
                for (UUID pid : partners(cur.cart)) {
                    Member pm = members.get(pid);
                    if (pm != null && visited.add(pid)) queue.add(pm);
                }
            }
            // A component is a train if it has >1 cart, or a lone engine (blast cart drives solo).
            boolean lone = comp.size() == 1;
            if (lone && !carts.isBlastCart(comp.get(0).cart)) continue;

            CartTrain train = new CartTrain();
            train.order = orderComponent(comp);
            // Inherit speed (momentum) but not moving/leaderState: a rebuilt train re-establishes its
            // heading next tick from the carts' retained velocity hints (drive → establishHeading), so a
            // train crossing a chunk border resumes seamlessly rather than NPE-ing on a null leaderState.
            for (Member m : train.order) {
                Double sp = oldSpeed.get(m.cart.getUniqueId());
                if (sp != null) { train.speed = sp; break; }
            }
            trains.add(train);
        }

        // Park any cart that was in a driven train but no longer is (uncoupled / split off / lone
        // non-engine) — otherwise it keeps maxSpeed 100 + residual velocity and rockets away.
        Set<UUID> stillDriven = new HashSet<>();
        for (CartTrain t : trains) for (Member m : t.order) stillDriven.add(m.cart.getUniqueId());
        for (UUID id : oldMembers) {
            if (stillDriven.contains(id)) continue;
            if (Bukkit.getEntity(id) instanceof Minecart mc) parkCart(mc);
        }
    }

    /** Order a component endpoint→endpoint by walking the coupling chain. */
    private List<Member> orderComponent(List<Member> comp) {
        if (comp.size() <= 1) return new ArrayList<>(comp);
        Set<UUID> ids = new HashSet<>();
        for (Member m : comp) ids.add(m.cart.getUniqueId());
        // Endpoint = a member with ≤1 in-component partner.
        Member start = comp.get(0);
        for (Member m : comp) {
            int deg = 0;
            for (UUID pid : partners(m.cart)) if (ids.contains(pid)) deg++;
            if (deg <= 1) { start = m; break; }
        }
        List<Member> ordered = new ArrayList<>();
        Member prev = null, cur = start;
        Set<UUID> used = new HashSet<>();
        while (cur != null && used.add(cur.cart.getUniqueId())) {
            ordered.add(cur);
            Member next = null;
            for (UUID pid : partners(cur.cart)) {
                if (!ids.contains(pid)) continue;
                Member pm = members.get(pid);
                if (pm != null && pm != prev && !used.contains(pid)) { next = pm; break; }
            }
            prev = cur; cur = next;
        }
        return ordered;
    }

    // ── Drive loop ────────────────────────────────────────────────────────────

    private void tick() {
        tickCount++;
        if (dirty) rebuildTrains();
        boolean feed = tickCount % 20 == 0;
        for (CartTrain train : trains) {
            // Drop trains whose carts died/unloaded out from under us.
            boolean ok = true;
            for (Member m : train.order) if (m.cart.isDead() || !m.cart.isValid()) { ok = false; break; }
            if (!ok) { dirty = true; continue; }
            if (feed) feedTrain(train);
            drive(train);
            updateChainDisplays(train);   // after placing carts, so chains track their final positions
        }
    }

    private void drive(CartTrain train) {
        List<Member> ord = train.order;
        int n = ord.size();

        int engines = 0;
        double trainMax = 0;
        boolean hasFueledBlast = false;
        for (Member m : ord) {
            if (isEngine(m.cart)) { engines++; trainMax = Math.max(trainMax, engineSpeed(m.cart)); }
            if (carts.isBlastCart(m.cart) && carts.burnTicks(m.cart) > 0) hasFueledBlast = true;
        }

        // Establish (or, after a rebuild, re-establish) travel direction from a push / retained velocity.
        // A parked train stays on vanilla physics until nudged; a moving one that rebuilt resumes from
        // its carts' velocity hints. No engine and no push → nothing to drive.
        if (!train.moving && !establishHeading(train, engines > 0)) return;

        // Controller rail (zone-based): while ANY member sits on a controller and the train has a fuelled
        // blast cart, the target speed is the most-restrictive controller signal it's over (0 → stop,
        // 15 → full, linear). Off the zone it resumes full speed. Inert without a lit blast cart.
        double effTarget = trainMax;
        if (rails != null && hasFueledBlast) {
            double t = Double.MAX_VALUE;
            for (Member m : ord) {
                Block rb = railUnder(m.cart);
                if (rb != null && rails.isController(rb)) t = Math.min(t, rails.controllerTarget(rb));
            }
            if (t != Double.MAX_VALUE) effTarget = Math.min(trainMax, t);
        }

        if (engines > 0) {
            // Power/weight: acceleration is proportional to (active engines / total carts) — it falls with
            // train length (1/n) and rises with engine count — while the target stays length-independent.
            // A freshly-started powered train ramps from ~0; a controller can pull the target below the max,
            // in which case we brake toward it.
            double accel = config.baseAccel * engines / n;
            if (train.speed > effTarget) train.speed = Math.max(effTarget, train.speed - config.brakeRate);
            else train.speed = Math.min(train.speed + accel, effTarget);
            // Burn fuel only while actually MOVING — an engine held at 0 by a controller (or otherwise
            // primed) must not drain, else "0 = stop" isn't durable (it would empty and lurch off).
            if (train.speed > 1e-3) for (Member m : ord) if (carts.isBlastCart(m.cart)) carts.consumeBurnTick(m.cart);
        } else {
            train.speed -= config.rollingDrag;
            if (train.speed <= 1e-4) { park(train); return; }
        }

        // Pre-align any junction the leader is about to cross so RailPathWalker walks straight through it
        // (teleported carts don't fire VehicleMoveEvent, so the rail manager can't see them). Look ahead
        // ∝ speed so fast trains still align in time.
        if (rails != null) {
            RailPathWalker.Step cur = RailPathWalker.advance(train.leaderState, 0, config.subStep);
            rails.alignJunctionsAlong(ord.get(0).cart.getWorld(), cur.position, cur.heading,
                Math.max(1.0, train.speed) + 1.0);
        }

        RailPathWalker.Step s = RailPathWalker.advance(train.leaderState, train.speed, config.subStep);
        train.leaderState = s.state;
        place(ord.get(0).cart, s.position, s.heading, train.speed, true);
        placeFollowers(train, train.speed, true);

        // Destructor rail: any member sitting on one is recycled (drops → container below), splitting the
        // train. Collect first (rail block resolves to null once the cart is removed), act, then rebuild.
        if (rails != null) {
            List<Minecart> deadCarts = null;
            List<Block> deadRails = null;
            for (Member m : ord) {
                Block rb = railUnder(m.cart);
                if (rb != null && rails.isDestructor(rb)) {
                    if (deadCarts == null) { deadCarts = new ArrayList<>(); deadRails = new ArrayList<>(); }
                    deadCarts.add(m.cart);
                    deadRails.add(rb);
                }
            }
            if (deadCarts != null) {
                for (int i = 0; i < deadCarts.size(); i++) rails.destroy(deadCarts.get(i), deadRails.get(i));
                dirty = true;
                return;   // train changed under us — rebuild next tick
            }
        }

        if (s.blocked) park(train);   // end of track — stop after placing
    }

    /** Place followers ord[1..] each {@code spacing} behind the one ahead (chaining from the leader).
     *  O(n): each cart walks a single {@code spacing} hop, and follows the exact path of the cart ahead
     *  (so cars can't diverge at a fork behind them). {@code driving} = position-drive, else static snap. */
    private void placeFollowers(CartTrain train, double speed, boolean driving) {
        List<Member> ord = train.order;
        RailPathWalker.RailState prev = train.leaderState;
        for (int i = 1; i < ord.size(); i++) {
            RailPathWalker.Step f = RailPathWalker.advance(RailPathWalker.flip(prev), config.spacing, config.subStep);
            Vector travel = f.heading.clone().multiply(-1);   // walked backward; face the travel direction
            place(ord.get(i).cart, f.position, travel, speed, driving);
            prev = RailPathWalker.flip(f.state);              // this follower's travel-oriented state
        }
    }

    /** Position a cart at a computed rail point. {@code driving}: raise the clamp + velocity hint so it
     *  keeps moving; otherwise snap it static and parked (maxSpeed 0.4, zero velocity). */
    private void place(Minecart cart, Vector pos, Vector heading, double speed, boolean driving) {
        Location loc = RailPathWalker.toLocation(cart.getWorld(), pos, heading);
        if (driving) {
            cart.setMaxSpeed(100.0d);
            cart.teleport(loc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            cart.setVelocity(heading.clone().multiply(speed));
        } else {
            cart.teleport(loc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            parkCart(cart);
        }
    }

    // ── Chain visuals (chain display rides an invisible carrier the plugin teleports; never teleport the ──
    //    visible display — a position packet perturbs its transform interpolation and flashes it vertical) ──

    /** Keep one chain link per coupling. The visible chain rides an invisible AIR carrier as a passenger;
     *  each tick the plugin teleports only the carrier to the coupling midpoint (position) and sets the
     *  chain's transform matrix (orientation+scale). Because the chain display itself is never teleported,
     *  its transform interpolation is never perturbed → no vertical/horizontal flash. The midpoint is
     *  order-agnostic, so train reordering needs no special-casing. */
    private void updateChainDisplays(CartTrain train) {
        if (!config.chainVisuals) { clearChainDisplays(train); return; }
        List<Member> ord = train.order;
        int need = Math.max(0, ord.size() - 1);
        for (int i = 0; i < need; i++) {
            Minecart cartA = ord.get(i).cart, cartB = ord.get(i + 1).cart;
            // span = B − A; travel direction points toward the front (ord[0]); zero-lead when parked.
            Vector a = cartA.getLocation().toVector();
            Vector b = cartB.getLocation().toVector();
            Vector span = b.clone().subtract(a);
            double full = span.length();
            Vector lead = full < 1e-4 ? new Vector()
                : a.clone().subtract(b).normalize().multiply(train.speed * CHAIN_LEAD);
            Vector midVec = a.clone().add(b).multiply(0.5).add(lead);
            midVec.setY(midVec.getY() + CHAIN_Y);
            Location mid = midVec.toLocation(cartA.getWorld());   // yaw/pitch 0 → world-aligned frame

            ChainLink link = i < train.chainLinks.size() ? train.chainLinks.get(i) : null;
            if (link == null || link.dead()) {
                if (link != null) link.despawn();
                link = spawnChainLink(mid);   // spawn AT the midpoint → no slide-in on spawn/rebuild
                if (i < train.chainLinks.size()) train.chainLinks.set(i, link);
                else train.chainLinks.add(link);
            }
            // Position: teleport only the CARRIER, and only when the target moved (a parked train sends none).
            if (link.carrier.getLocation().distanceSquared(mid) > 1e-6) link.carrier.teleport(mid);

            double chainLen = full - 2 * HALF_CART;   // bridge just the gap between the adjacent ends
            if (full < 1e-4 || chainLen < 1e-3) { link.display.setTransformationMatrix(new Matrix4f().scale(0f)); continue; }
            // Orientation+scale only (no translation — the carrier positions it; the −0.5 re-centres the unit
            // cube). Aim local +Y along the span, stretch to the gap length. Set on the (never-teleported)
            // display so its interpolation is undisturbed.
            Vector3f spanF = new Vector3f((float) span.getX(), (float) span.getY(), (float) span.getZ());
            Quaternionf orient = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f(spanF).normalize());
            Matrix4f m = new Matrix4f()
                .rotate(orient)
                .scale(CHAIN_WIDTH, (float) chainLen, CHAIN_WIDTH)
                .translate(-0.5f, -0.5f, -0.5f);
            link.display.setInterpolationDelay(0);
            link.display.setTransformationMatrix(m);
        }
        // Trim links past the current coupling count (train shrank / split).
        while (train.chainLinks.size() > need) {
            train.chainLinks.remove(train.chainLinks.size() - 1).despawn();
        }
    }

    /** Spawn a chain link at {@code mid}: an invisible AIR carrier (teleported for position) plus the chain
     *  display mounted on it as a passenger (never teleported). Hand-rolled (not DisplayUtil.spawnBlock,
     *  which forces persistent) so both are non-persistent and carry the moving-display settings. */
    private ChainLink spawnChainLink(Location mid) {
        World w = mid.getWorld();
        BlockDisplay carrier = w.spawn(mid, BlockDisplay.class, c -> {
            c.setBlock(Material.AIR.createBlockData());   // invisible — carries position only
            c.setPersistent(false);
            c.setTeleportDuration(1);   // smooth 1-tick position chase toward each new midpoint
            c.setViewRange(64f);
            c.addScoreboardTag(TAG_CHAIN);
        });
        BlockDisplay display = w.spawn(mid, BlockDisplay.class, bd -> {
            bd.setBlock(chainBlockData());
            bd.setPersistent(false);
            bd.setTeleportDuration(0);   // never teleported — position comes from the carrier
            bd.setInterpolationDuration(CHAIN_INTERP);   // matrix eases orientation through curves, undisturbed
            bd.setViewRange(64f);
            bd.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            bd.addScoreboardTag(TAG_CHAIN);
        });
        carrier.addPassenger(display);   // BlockDisplay-on-BlockDisplay mounts synchronously (mech precedent)
        return new ChainLink(carrier, display);
    }

    /** Chain block data with axis pinned to Y (the long axis the strand matrix assumes). */
    private static org.bukkit.block.data.BlockData chainBlockData() {
        org.bukkit.block.data.BlockData data = CHAIN_MATERIAL.createBlockData();
        if (data instanceof org.bukkit.block.data.Orientable o) o.setAxis(org.bukkit.Axis.Y);
        return data;
    }

    private void clearChainDisplays(CartTrain train) {
        for (ChainLink link : train.chainLinks) if (link != null) link.despawn();
        train.chainLinks.clear();
    }

    /** Remove any stray chain entities (armor stands + block displays tagged {@link #TAG_CHAIN}) left in a
     *  chunk — non-persistent ones shouldn't survive a clean restart, but this reaps anything orphaned by a
     *  crash or {@code /reload} so no invisible marker stands accumulate. */
    private void sweepOrphanChains(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if ((e instanceof BlockDisplay || e instanceof ArmorStand)
                    && e.getScoreboardTags().contains(TAG_CHAIN)) {
                e.remove();
            }
        }
    }

    /** Establish travel direction from a member's push; order the train front→back and seed leaderState.
     *  {@code hasEngine}: a powered train uses the push only for direction and accelerates from its
     *  current speed; an engineless (coasting) train adopts the push magnitude as its speed. */
    private boolean establishHeading(CartTrain train, boolean hasEngine) {
        List<Member> ord = train.order;
        Vector heading = null;
        double pushMag = 0;
        for (Member m : ord) {
            Vector v = m.cart.getVelocity();
            double h = Math.hypot(v.getX(), v.getZ());
            if (h > 0.05) {
                heading = new Vector(v.getX(), 0, v.getZ()).normalize();
                pushMag = h;
                break;
            }
        }
        if (heading == null) return false;
        // The push establishes DIRECTION. Only an engineless train adopts its magnitude as speed — it has
        // no other source and must roll at the push speed, then decay via rolling drag. A powered train
        // ignores the shove speed and accelerates from its current speed (0 on a fresh start, or the value
        // inherited across a chunk-border rebuild), so it visibly ramps up instead of jumping to full.
        if (!hasEngine) train.speed = Math.max(train.speed, pushMag);

        // Order front→back: the front is the endpoint furthest along the heading. If endpoint b (the
        // current tail) projects ahead of endpoint a on the heading, reverse so index 0 is the front.
        Member a = ord.get(0), b = ord.get(ord.size() - 1);
        Vector ab = b.cart.getLocation().toVector().subtract(a.cart.getLocation().toVector());
        if (ab.getX() * heading.getX() + ab.getZ() * heading.getZ() > 0) Collections.reverse(ord);

        Minecart front = ord.get(0).cart;
        Block rail = railUnder(front);
        if (rail == null) return false;
        train.leaderState = RailPathWalker.initOn(rail, heading);
        if (train.leaderState == null) return false;
        train.moving = true;
        return true;
    }

    private void park(CartTrain train) {
        train.moving = false;
        train.speed = 0;
        for (Member m : train.order) parkCart(m.cart);
    }

    /** Snap a parked train's carts onto the rail at uniform {@code spacing}, aligned along the coupling.
     *  Called right after coupling so the carts jump into a tidy consist instead of sitting where the
     *  player left them. Anchors on an endpoint and lays the rest out behind it toward their side. */
    private void snapFormation(CartTrain train) {
        List<Member> ord = train.order;
        if (ord.size() < 2) return;
        Minecart anchor = ord.get(0).cart;
        Block rail = railUnder(anchor);
        if (rail == null) return;
        Vector dir = ord.get(1).cart.getLocation().toVector().subtract(anchor.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1e-6) return;
        dir.normalize();
        // Leader heading points AWAY from the followers, so "behind" (where placeFollowers lays them) is
        // toward their current side.
        RailPathWalker.RailState st = RailPathWalker.initOn(rail, dir.clone().multiply(-1));
        if (st == null) return;
        train.leaderState = st;
        train.moving = false;
        train.speed = 0;
        RailPathWalker.Step lead = RailPathWalker.advance(st, 0, config.subStep);
        place(anchor, lead.position, dir, 0, false);
        placeFollowers(train, 0, false);
        updateChainDisplays(train);
    }

    /** The loaded train currently containing {@code cart}, or null. */
    private CartTrain trainContaining(Minecart cart) {
        for (CartTrain t : trains) for (Member m : t.order) {
            if (m.cart.getUniqueId().equals(cart.getUniqueId())) return t;
        }
        return null;
    }

    /** Restore vanilla physics on a single cart: reset the maxSpeed clamp (we drive at 100) and zero
     *  velocity. MUST be called on every cart that stops being position-driven (park, uncouple, split,
     *  shutdown) — otherwise it keeps maxSpeed 100 + residual velocity and vanilla launches it away. */
    private void parkCart(Minecart cart) {
        if (cart == null || cart.isDead()) return;
        cart.setMaxSpeed(0.4d);
        cart.setVelocity(new Vector(0, 0, 0));
    }

    // ── Engines & fuel ────────────────────────────────────────────────────────

    private boolean isEngine(Minecart cart) {
        if (carts.isBlastCart(cart)) return carts.burnTicks(cart) > 0;
        return cart instanceof PoweredMinecart pm && pm.getFuel() > 0;
    }

    private double engineSpeed(Minecart cart) {
        return carts.isBlastCart(cart) ? config.blastFurnaceCartSpeed : config.furnaceCartSpeed;
    }

    /** Cap on an engine's stored fuel from tender feeding — a coal block (16000) shouldn't dump its
     *  whole burn into a cart that only needed a top-up. */
    private static final int MAX_ENGINE_FUEL = 6000;

    /** Top up under-fuelled engines from any tender (coal cart) in the same train. */
    private void feedTrain(CartTrain train) {
        for (Member m : train.order) {
            Minecart cart = m.cart;
            boolean blast = carts.isBlastCart(cart);
            boolean furnace = cart instanceof PoweredMinecart;
            if (!blast && !furnace) continue;
            int level = blast ? carts.burnTicks(cart) : ((PoweredMinecart) cart).getFuel();
            if (level >= config.feedThresholdTicks) continue;

            ItemStack fuel = takeTenderFuel(train);
            if (fuel == null) return;   // no tender fuel left this cycle
            int add = Math.min(carts.fuelBurnTicks(fuel.getType()), MAX_ENGINE_FUEL - level);
            if (add <= 0) continue;
            if (blast) carts.addBurnTicks(cart, add);
            else ((PoweredMinecart) cart).setFuel(level + add);
        }
    }

    private ItemStack takeTenderFuel(CartTrain train) {
        for (Member m : train.order) {
            if (!carts.isCoalCart(m.cart)) continue;
            ItemStack item = carts.takeOneFuelFrom(m.cart);
            if (item != null) return item;
        }
        return null;
    }

    private static Block railUnder(Minecart cart) {
        Block at = cart.getLocation().getBlock();
        if (RailPathWalker.isRail(at.getType())) return at;
        Block below = at.getRelative(0, -1, 0);
        return RailPathWalker.isRail(below.getType()) ? below : null;
    }
}
