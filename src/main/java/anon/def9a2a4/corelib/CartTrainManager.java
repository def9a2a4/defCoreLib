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
    private static final double CHAIN_Y = 0.35d;   // coupling height above a cart's origin
    private static final float CHAIN_WIDTH = 1.0f;

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

    CartTrainManager(JavaPlugin plugin, CartConfig config, CustomCartManager carts) {
        this.plugin = plugin;
        this.config = config;
        this.carts = carts;
        this.linksKey = new NamespacedKey(plugin, "cart_links");
    }

    private static final class Member {
        final Minecart cart;
        CartTrain train;
        Member(Minecart cart) { this.cart = cart; }
    }

    private static final class CartTrain {
        List<Member> order = new ArrayList<>();   // one endpoint → the other; index 0 = front once moving
        double speed;
        boolean moving;                            // heading established (from a push)?
        RailPathWalker.RailState leaderState;
        final List<BlockDisplay> chainDisplays = new ArrayList<>();   // one per coupling (size = order-1)
    }

    private record PendingLink(UUID cart, long expiry) {}

    // ── Lifecycle ───────────────────────────────────────────────────────────

    void register() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) scanChunk(chunk);
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void shutdown() {
        if (tickTask != null) tickTask.cancel();
        for (CartTrain t : trains) clearChainDisplays(t);
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
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Minecart cart)) continue;
            if (members.containsKey(cart.getUniqueId())) continue;
            Set<String> tags = cart.getScoreboardTags();
            if (tags.contains(TAG_MEMBER) || tags.contains(TAG_BLAST)) {
                members.put(cart.getUniqueId(), new Member(cart));
                dirty = true;
            }
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
        if (held.getType() != Material.CHAIN) return;
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
        dirty = true;
        cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 1f);
        player.sendActionBar(Component.text("Coupled!", NamedTextColor.GREEN));
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
            }
            dropped++;
        }
        writePartners(cart, Collections.emptySet());
        cart.removeScoreboardTag(TAG_MEMBER);
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
            if (Bukkit.getEntity(pid) instanceof Minecart other) removePartner(other, cart.getUniqueId());
            dropped++;
        }
        if (dropped > 0) dropChains(cart.getLocation(), dropped);
        members.remove(cart.getUniqueId());
        dirty = true;
    }

    private void dropChains(Location loc, int count) {
        loc.getWorld().dropItemNaturally(loc, new ItemStack(Material.CHAIN, count));
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
        for (CartTrain t : trains) for (Member m : t.order) oldSpeed.put(m.cart.getUniqueId(), t.speed);
        for (CartTrain t : trains) clearChainDisplays(t);   // fresh trains respawn their own chains
        for (Member m : members.values()) m.train = null;
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
            for (Member m : train.order) m.train = train;
            // Inherit speed (momentum) but not moving/leaderState: a rebuilt train re-establishes its
            // heading next tick from the carts' retained velocity hints (drive → establishHeading), so a
            // train crossing a chunk border resumes seamlessly rather than NPE-ing on a null leaderState.
            for (Member m : train.order) {
                Double sp = oldSpeed.get(m.cart.getUniqueId());
                if (sp != null) { train.speed = sp; break; }
            }
            trains.add(train);
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
        for (Member m : ord) {
            if (isEngine(m.cart)) { engines++; trainMax = Math.max(trainMax, engineSpeed(m.cart)); }
        }

        // Establish (or, after a rebuild, re-establish) travel direction from a push / retained velocity.
        // A parked train stays on vanilla physics until nudged; a moving one that rebuilt resumes from
        // its carts' velocity hints. No engine and no push → nothing to drive.
        if (!train.moving && !establishHeading(train)) return;

        if (engines > 0) {
            double accel = config.baseAccel * engines / n;
            train.speed = Math.min(train.speed + accel, trainMax);
        } else {
            train.speed -= config.rollingDrag;
            if (train.speed <= 1e-4) { park(train); return; }
        }

        RailPathWalker.Step s = RailPathWalker.advance(train.leaderState, train.speed, config.subStep);
        train.leaderState = s.state;
        Minecart leader = ord.get(0).cart;
        place(leader, s.position, s.heading, train.speed);

        for (int i = 1; i < n; i++) {
            RailPathWalker.Step f = RailPathWalker.advance(RailPathWalker.flip(train.leaderState),
                i * config.spacing, config.subStep);
            Vector travel = f.heading.clone().multiply(-1);   // flip walked backward; face travel dir
            place(ord.get(i).cart, f.position, travel, train.speed);
        }

        if (s.blocked) park(train);   // end of track — stop after placing
    }

    /** Position-drive one cart to a computed rail point (teleport + velocity hint, vanilla clamp out). */
    private void place(Minecart cart, Vector pos, Vector heading, double speed) {
        Location loc = RailPathWalker.toLocation(cart.getWorld(), pos, heading);
        cart.setMaxSpeed(100.0d);
        cart.teleport(loc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        cart.setVelocity(heading.clone().multiply(speed));
    }

    // ── Chain visuals (one BlockDisplay per coupling, stretched between the carts) ──────────────────

    /** Keep one chain BlockDisplay per coupling, positioned/oriented between consecutive carts. */
    private void updateChainDisplays(CartTrain train) {
        List<Member> ord = train.order;
        int need = Math.max(0, ord.size() - 1);
        train.chainDisplays.removeIf(d -> d == null || d.isDead());
        while (train.chainDisplays.size() > need) {
            BlockDisplay d = train.chainDisplays.remove(train.chainDisplays.size() - 1);
            if (!d.isDead()) d.remove();
        }
        while (train.chainDisplays.size() < need) {
            Location loc = ord.get(train.chainDisplays.size()).cart.getLocation().add(0, CHAIN_Y, 0);
            BlockDisplay d = loc.getWorld().spawn(loc, BlockDisplay.class, bd -> {
                bd.setBlock(Material.CHAIN.createBlockData());
                bd.setPersistent(false);   // respawned from the train on reload; no orphans survive restart
                bd.addScoreboardTag(TAG_CHAIN);
            });
            train.chainDisplays.add(d);
        }
        for (int i = 0; i < need; i++) {
            BlockDisplay d = train.chainDisplays.get(i);
            Location pa = ord.get(i).cart.getLocation().add(0, CHAIN_Y, 0);
            Location pb = ord.get(i + 1).cart.getLocation().add(0, CHAIN_Y, 0);
            d.teleport(pa);
            Vector dir = pb.toVector().subtract(pa.toVector());
            double len = dir.length();
            if (len < 1e-4) continue;
            Vector3f unit = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).normalize();
            Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), unit);
            // Map the block model's bottom-centre → cart A and top-centre → cart B: rotate the vertical
            // chain onto the coupling direction, stretch to the gap, and re-centre the cell in XZ.
            Matrix4f m = new Matrix4f().rotate(rot).scale(CHAIN_WIDTH, (float) len, CHAIN_WIDTH)
                .translate(-0.5f, 0f, -0.5f);
            d.setTransformationMatrix(m);
        }
    }

    private void clearChainDisplays(CartTrain train) {
        for (BlockDisplay d : train.chainDisplays) if (d != null && !d.isDead()) d.remove();
        train.chainDisplays.clear();
    }

    /** Establish travel direction from a member's push; order the train front→back and seed leaderState. */
    private boolean establishHeading(CartTrain train) {
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
        // Seed speed from the push so a fresh start doesn't stutter and a rebuild keeps its momentum.
        train.speed = Math.max(train.speed, pushMag);

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
        for (Member m : train.order) {
            m.cart.setMaxSpeed(0.4d);       // restore vanilla so a fresh push can restart it
            m.cart.setVelocity(new Vector(0, 0, 0));
        }
    }

    // ── Engines & fuel ────────────────────────────────────────────────────────

    private boolean isEngine(Minecart cart) {
        if (carts.isBlastCart(cart)) return carts.burnTicks(cart) > 0;
        return cart instanceof PoweredMinecart pm && pm.getFuel() > 0;
    }

    private double engineSpeed(Minecart cart) {
        return carts.isBlastCart(cart) ? config.blastFurnaceCartSpeed : config.furnaceCartSpeed;
    }

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
            int burn = carts.fuelBurnTicks(fuel.getType());
            if (blast) carts.addBurnTicks(cart, burn);
            else ((PoweredMinecart) cart).setFuel(((PoweredMinecart) cart).getFuel() + burn);
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
