package anon.def9a2a4.corelib;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Throwaway M3a movement spike (see the betterminecarts plan). Answers the gating questions before the
 * real train is built: does <b>position-driving</b> a cart (teleport to a computed rail point every
 * tick, with a velocity hint for client interpolation) look smooth to a rider at 0.4 / 1.0 / 2.0 b/t,
 * and does vanilla's residual physics fight the teleport? Driven by {@code /defcorelib spike <speed>}.
 *
 * <p>Not wired into any cart's real behaviour — this is a validation harness. Delete once the train
 * subsystem (which reuses {@link RailPathWalker}) is in.
 */
final class CartSpike {

    private static final double SUB_STEP = 0.25d;

    private final JavaPlugin plugin;
    private RideableMinecart cart;
    private RailPathWalker.RailState state;
    private double speed;
    private BukkitTask task;
    private Player rider;

    CartSpike(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isRunning() { return task != null; }

    /** Start driving a fresh cart under {@code player} at {@code speed} b/t. Returns false if no rail. */
    boolean start(Player player, double speed) {
        stop();
        Block rail = findRail(player);
        if (rail == null) {
            player.sendMessage(Component.text("Spike: stand on a rail first.", NamedTextColor.RED));
            return false;
        }
        Vector heading = player.getLocation().getDirection();
        state = RailPathWalker.initOn(rail, heading);
        if (state == null) {
            player.sendMessage(Component.text("Spike: not a rail.", NamedTextColor.RED));
            return false;
        }
        this.speed = speed;
        this.rider = player;

        Location spawn = RailPathWalker.toLocation(rail.getWorld(),
            new Vector(rail.getX() + 0.5, rail.getY() + RailPathWalker.RAIL_Y, rail.getZ() + 0.5), heading);
        cart = rail.getWorld().spawn(spawn, RideableMinecart.class, m -> {
            m.setPersistent(false);
            m.setMaxSpeed(100.0d);       // keep vanilla's clamp out of the way; we own position
            m.addScoreboardTag("corelib:spike_cart");
        });
        cart.addPassenger(player);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        player.sendMessage(Component.text("Spike: driving at " + speed + " b/t (" + (speed * 20) + " b/s). "
            + "/defcorelib spike stop to end.", NamedTextColor.GREEN));
        return true;
    }

    void setSpeed(double speed) {
        this.speed = speed;
        if (rider != null) rider.sendActionBar(Component.text("Spike speed: " + speed + " b/t"));
    }

    void stop() {
        if (task != null) { task.cancel(); task = null; }
        if (cart != null && !cart.isDead()) {
            cart.eject();
            cart.remove();
        }
        cart = null;
        state = null;
        rider = null;
    }

    private void tick() {
        if (cart == null || cart.isDead() || state == null) { stop(); return; }

        RailPathWalker.Step step = RailPathWalker.advance(state, speed, SUB_STEP);
        state = step.state;

        Location target = RailPathWalker.toLocation(cart.getWorld(), step.position, step.heading);
        // Position-drive: teleport to the computed point, retaining the rider (RETAIN_PASSENGERS is the
        // default only from 1.21.10; pass it explicitly for 1.21.8). Velocity is a client-interp hint.
        cart.teleport(target, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        cart.setVelocity(step.heading.clone().multiply(speed));

        if (step.blocked) {
            if (rider != null) rider.sendActionBar(Component.text("Spike: end of track — stopped.",
                NamedTextColor.YELLOW));
            stop();
        }
    }

    /** The rail block the player is standing on (feet, then one below). */
    private static Block findRail(Player player) {
        Block at = player.getLocation().getBlock();
        if (RailPathWalker.isRail(at.getType())) return at;
        Block below = at.getRelative(0, -1, 0);
        if (RailPathWalker.isRail(below.getType())) return below;
        return null;
    }
}
