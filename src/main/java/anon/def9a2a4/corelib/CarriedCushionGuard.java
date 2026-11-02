package anon.def9a2a4.corelib;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Keeps a cushion riding a mechanism collider from popping off. Its vanilla survival check keeps running while
 * it is a passenger, finds no block under it, and Paper fires a cancellable
 * {@code io.papermc.paper.event.entity.EntityBreakEvent} (cause PHYSICS/OBSTRUCTION) before dropping it.
 *
 * <p>That event class exists only on 26.x, and a listener class with a typed {@code @EventHandler} for a missing
 * class silently loses ALL its handlers on older servers (Paper swallows the NoClassDefFoundError while scanning
 * its methods). So this class declares no typed handler: it is registered reflectively, only when the event
 * class resolves, and reads the cause by reflection. {@code EntityBreakByEntityEvent} shares the handler list
 * and is let through, so a player can still punch a carried cushion off.
 */
final class CarriedCushionGuard implements Listener {

    private final Class<?> byEntityClass;
    private final Method getCause;

    private CarriedCushionGuard(Class<?> byEntityClass, Method getCause) {
        this.byEntityClass = byEntityClass;
        this.getCause = getCause;
    }

    /** Register on a server that has the event; a no-op (returns false) everywhere else. */
    @SuppressWarnings("unchecked")
    static boolean register(Plugin plugin) {
        final Class<? extends Event> breakClass;
        final Class<?> byEntity;
        final Method cause;
        try {
            breakClass = (Class<? extends Event>) Class.forName("io.papermc.paper.event.entity.EntityBreakEvent");
            byEntity = Class.forName("io.papermc.paper.event.entity.EntityBreakByEntityEvent");
            cause = breakClass.getMethod("getCause");
        } catch (ReflectiveOperationException | LinkageError e) {
            return false; // pre-26.3 server: no cushions, nothing to guard
        }
        CarriedCushionGuard guard = new CarriedCushionGuard(byEntity, cause);
        plugin.getServer().getPluginManager().registerEvent(breakClass, guard, EventPriority.HIGH,
            (listener, event) -> guard.handle(event), plugin, true);
        return true;
    }

    private void handle(Event event) {
        if (byEntityClass.isInstance(event) || !(event instanceof EntityEvent ee)
                || !(event instanceof Cancellable c)) return;
        Entity e = ee.getEntity();
        if (!AttachedEntities.isCarriedNow(e)) return;
        String cause;
        try {
            cause = String.valueOf(getCause.invoke(event));
        } catch (ReflectiveOperationException ex) {
            return;
        }
        if ("PHYSICS".equals(cause) || "OBSTRUCTION".equals(cause)) c.setCancelled(true);
    }
}
