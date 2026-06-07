package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.List;

/**
 * Compatibility layer for Entity.teleport() with passengers.
 * Pre-1.21.9: teleport() silently fails with passengers (SPIGOT-2064).
 * 1.21.9+: teleport() retains passengers natively.
 */
public final class TeleportCompat {

    private static final boolean NEEDS_EJECT;

    static {
        boolean needsEject;
        try {
            String[] parts = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            int version = major * 10000 + minor * 100 + patch;
            needsEject = version < 12109; // 1.21.9
        } catch (Exception e) {
            needsEject = false; // assume modern version on parse failure
        }
        NEEDS_EJECT = needsEject;
    }

    private TeleportCompat() {}

    public static void teleport(Entity entity, Location location) {
        if (NEEDS_EJECT) {
            List<Entity> passengers = entity.getPassengers();
            if (passengers.isEmpty()) {
                entity.teleport(location);
                return;
            }
            entity.eject();
            entity.teleport(location);
            if (!entity.isValid()) return;
            for (Entity passenger : passengers) {
                if (passenger != null && passenger.isValid()) {
                    entity.addPassenger(passenger);
                }
            }
        } else {
            entity.teleport(location);
        }
    }
}
