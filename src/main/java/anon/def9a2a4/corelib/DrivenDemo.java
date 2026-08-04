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

    DrivenDemo(JavaPlugin plugin, MechanismRegistry mechRegistry, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.mechRegistry = mechRegistry;
        this.registry = registry;
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
        List<Block> blocks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = w.getBlockAt(feet.getBlockX() + dx, baseY, feet.getBlockZ() + dz);
                if (MovableBlocks.isMovable(b, registry)) blocks.add(b);
            }
        }
        if (blocks.isEmpty()) {
            player.sendMessage("§cdriventest: stand on a solid movable 3×3 platform first");
            return false;
        }
        Location pivot = new Location(w, feet.getBlockX() + 0.5, baseY + 0.5, feet.getBlockZ() + 0.5);
        Mechanism mech;
        try {
            mech = mechRegistry.assembleMechanism("demo:driven", blocks, pivot, null);
        } catch (RuntimeException e) {
            player.sendMessage("§cdriventest: assembly failed: " + e.getMessage());
            return false;
        }
        BasicMechanism bm = (BasicMechanism) mech;
        bm.setDriven(true);

        Session s = new Session();
        s.mech = mech;
        s.vehicle = bm.vehicle;
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
    }

    void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) stop(s);
        sessions.clear();
    }
}
