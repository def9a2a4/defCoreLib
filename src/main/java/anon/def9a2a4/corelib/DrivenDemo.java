package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-0 smoothness spike (THROWAWAY). Captures the 3×3 platform under the player, assembles it as an
 * OWNED mechanism, marks it {@code driven}, seats the player on a collider shulker, and each tick
 * teleports the vehicle along a fast translate+turn+rise path and calls {@link Mechanism#repositionDriven}
 * — modelling exactly how BlockShips will drive a ship (consumer-authoritative vehicle teleport + a
 * per-tick engine reposition). Its only purpose is to eyeball MC-261202 passenger smoothness with two
 * clients before committing the driven API. Command: {@code /defcorelib driventest} (toggle).
 *
 * <p>Not wired into any persistence/lifecycle; blocks are restored on stop via {@code disassemble()}.
 */
final class DrivenDemo {

    private final JavaPlugin plugin;
    private final MechanismRegistry mechRegistry;
    private final CustomBlockRegistry registry;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Mechanism> parked = new HashMap<>(); // persisted-mechanism smoke test

    DrivenDemo(JavaPlugin plugin, MechanismRegistry mechRegistry, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.mechRegistry = mechRegistry;
        this.registry = registry;
    }

    /** Capture the movable 3×3 platform under the player (the block layer at feet-1), or empty if none. */
    private List<Block> capturePlatform(Player player) {
        World w = player.getWorld();
        Location feet = player.getLocation();
        int baseY = feet.getBlockY() - 1;
        List<Block> blocks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = w.getBlockAt(feet.getBlockX() + dx, baseY, feet.getBlockZ() + dz);
                if (MovableBlocks.isMovable(b, registry)) blocks.add(b);
            }
        }
        return blocks;
    }

    /** Assemble the platform as an OWNED, PERSISTED mechanism and leave it parked — a smoke test for
     *  crash-safe persistence (a state file appears under plugins/DefCoreLib/mechanisms/). */
    boolean park(Player player) {
        if (parked.containsKey(player.getUniqueId())) {
            player.sendMessage("§eyou already have a parked mechanism (unpark first)");
            return false;
        }
        List<Block> blocks = capturePlatform(player);
        if (blocks.isEmpty()) {
            player.sendMessage("§cpark: stand on a solid movable 3×3 platform first");
            return false;
        }
        Location feet = player.getLocation();
        Location pivot = new Location(player.getWorld(),
            feet.getBlockX() + 0.5, feet.getBlockY() - 1 + 0.5, feet.getBlockZ() + 0.5);
        Mechanism mech;
        try {
            mech = mechRegistry.assembleMechanism("demo:parked", blocks, pivot, null); // owned marker vehicle
        } catch (RuntimeException e) {
            player.sendMessage("§cpark: assembly failed: " + e.getMessage());
            return false;
        }
        mechRegistry.persist(mech); // opt into crash-safe persistence
        parked.put(player.getUniqueId(), mech);
        player.sendMessage("§apark: assembled + persisted — state file written under "
            + "plugins/DefCoreLib/mechanisms/. Run /defcorelib driventest unpark to remove.");
        return true;
    }

    boolean unpark(Player player) {
        Mechanism m = parked.remove(player.getUniqueId());
        if (m == null) {
            // After a /stop+restart the in-memory handle is gone but the mechanism was rebound from disk as
            // a live entity structure — find the nearest recovered demo:parked mechanism and land it.
            m = nearestParked(player);
        }
        if (m == null) {
            player.sendMessage("§enothing parked nearby");
            return false;
        }
        m.disassemble(); // returns blocks + removes the state file (onMechanismRemoved)
        player.sendMessage("§eunparked (blocks restored, state file removed)");
        return true;
    }

    /** Nearest recovered demo:parked mechanism within 8 blocks of the player (post-restart lookup). */
    private Mechanism nearestParked(Player player) {
        Mechanism best = null;
        double bestSq = 64.0; // 8 blocks
        for (Mechanism mech : mechRegistry.activeMechanisms()) {
            if (!"demo:parked".equals(mech.type())) continue;
            Location p = mech.pivot();
            if (p.getWorld() == null || !p.getWorld().equals(player.getWorld())) continue;
            double d = p.distanceSquared(player.getLocation());
            if (d < bestSq) { bestSq = d; best = mech; }
        }
        return best;
    }

    private static final class Session {
        Mechanism mech;
        Entity vehicle;
        Location center;
        BukkitTask task;
        int tick;
    }

    /** Toggle: start a flight for this player, or stop their active one. Returns false if start failed. */
    boolean toggle(Player player) {
        Session existing = sessions.remove(player.getUniqueId());
        if (existing != null) {
            stop(existing);
            player.sendMessage("§edriventest: stopped");
            return true;
        }
        return start(player);
    }

    private boolean start(Player player) {
        World w = player.getWorld();
        Location feet = player.getLocation();
        int baseY = feet.getBlockY() - 1; // the platform the player stands on
        List<Block> blocks = capturePlatform(player);
        if (blocks.isEmpty()) {
            player.sendMessage("§cdriventest: stand on a solid movable 3×3 platform first");
            return false;
        }
        Location pivot = new Location(w, feet.getBlockX() + 0.5, baseY + 0.5, feet.getBlockZ() + 0.5);
        // Spawn our OWN vehicle (exactly as BlockShips will): an invisible ArmorStand the consumer keeps
        // and positions itself each tick — exercising the new external-vehicle DRIVEN assembly path.
        org.bukkit.entity.ArmorStand vehicle = w.spawn(pivot, org.bukkit.entity.ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setMarker(false);       // full-size, so ARMORSTAND_RIDE_OFFSET applies (matches BlockShips)
            as.setPersistent(false);   // throwaway demo entity
        });
        Mechanism mech;
        try {
            mech = mechRegistry.assembleMechanism("demo:driven", blocks, vehicle,
                MechanismRegistry.ARMORSTAND_RIDE_OFFSET, true, null); // driven = true
        } catch (RuntimeException e) {
            vehicle.remove();
            player.sendMessage("§cdriventest: assembly failed: " + e.getMessage());
            return false;
        }
        BasicMechanism bm = (BasicMechanism) mech;

        Session s = new Session();
        s.mech = mech;
        s.vehicle = vehicle;
        s.center = pivot.clone();
        s.tick = 0;
        sessions.put(player.getUniqueId(), s);

        // Seat the player on the first collider shulker (a stand-in "seat") once entities have settled.
        if (!bm.colliders.isEmpty()) {
            Shulker seat = bm.colliders.get(0).shulker();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (seat.isValid() && player.isOnline()) seat.addPassenger(player);
            }, 2L);
        }
        // Start flying after the mount + owned-mount settle.
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> fly(s), 4L, 1L);
        player.sendMessage("§adriventest: flying a fast circle (run again to stop)");
        return true;
    }

    private void fly(Session s) {
        if (s.vehicle == null || !s.vehicle.isValid()) {
            stop(s);
            sessions.values().removeIf(v -> v == s);
            return;
        }
        s.tick++;
        final double radius = 8.0;
        final double omega = 0.06; // rad/tick → ~0.48 blocks/tick tangential (fast)
        double ang = s.tick * omega;
        double x = s.center.getX() + radius * Math.cos(ang) - radius; // start at centre, orbit out
        double z = s.center.getZ() + radius * Math.sin(ang);
        double lift = Math.min(6.0, s.tick * 0.25); // ease up to +6 so the first tick isn't a big jump
        double y = s.center.getY() + lift + 1.5 * Math.sin(s.tick * 0.05); // gentle vertical bob too
        // Keep the vehicle ENTITY yaw frozen (as BlockShips does); rotation is applied via repositionDriven.
        Location next = new Location(s.center.getWorld(), x, y, z,
            s.vehicle.getLocation().getYaw(), 0f);
        TeleportCompat.teleport(s.vehicle, next); // consumer-authoritative teleport (models ShipPhysics)
        float relYaw = (float) Math.toDegrees(ang); // continuous turn — exercises >90° net + long soak
        s.mech.repositionDriven(relYaw);
    }

    private void stop(Session s) {
        if (s.task != null) s.task.cancel();
        try {
            s.mech.disassemble();
        } catch (Exception ignored) {
            // best-effort; restore whatever we can
        }
        // We own the vehicle (ownsVehicle=false), so disassemble() left it — remove it ourselves.
        if (s.vehicle != null && s.vehicle.isValid()) s.vehicle.remove();
    }

    void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) stop(s);
        sessions.clear();
    }
}
