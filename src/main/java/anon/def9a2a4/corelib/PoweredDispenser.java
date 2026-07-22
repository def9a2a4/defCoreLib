package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Powered Dispenser — a real vanilla {@code DISPENSER} that becomes a launcher when fed rotation power.
 *
 * <p>Physically it is a {@code DISPENSER} (identity/state in its tile PDC), wearing two glowing red-eye
 * item displays and a "Powered Dispenser" custom name. Unpowered it is a plain dispenser: the overlay
 * sets {@code lockContainer(false)} so the vanilla GUI, hoppers, and item pipes all work, and the boost
 * handlers early-return, leaving vanilla dispensing untouched.
 *
 * <p>It registers as an <b>omni consumer</b> (drawn from a shaft on any face). While powered, whatever it
 * dispenses is launched faster and straighter along its facing, scaling with the network's supplied power
 * (clamped) up to a cap.
 *
 * <p>We deliberately <b>never write the {@link BlockDispenseEvent} velocity</b>: for several behaviours
 * (notably TNT, but also boats/spawn-eggs/minecarts) CraftBukkit packs the entity's <i>spawn position</i>
 * into that vector and reads it back as coordinates — overwriting it teleports the spawn into the void
 * (this was the "TNT dispenses nothing" bug). Instead {@code onDispense} only gates + schedules a
 * one-tick-later {@link #scanAndBoost} that mutates the already-spawned entity at the mouth: items,
 * projectiles (arrows/snowballs/eggs/potions/xp bottles), TNT, and fireballs. Fireworks are left alone
 * (self-propelled); fireballs/wind-charges are re-aimed (acceleration-driven) plus given a speed kick.
 */
final class PoweredDispenser implements Listener {

    static final String BLOCK_ID = "mech:powered_dispenser";
    private static final String BOOSTED_META = "mech_powered_dispenser_boosted";

    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final int power;
    private final int powerCap;
    private final double minBoost, boostPerPower, maxBoost, tntMaxBoost, minStraightness;

    PoweredDispenser(CustomBlockRegistry registry, RotationNetwork network, RotationConfig config) {
        this.registry = registry;
        this.network = network;
        this.power = config.getPower("powered_dispenser", 1);
        this.powerCap = Math.max(1, config.poweredDispenserPowerCap);
        this.minBoost = config.poweredDispenserMinBoost;
        this.boostPerPower = config.poweredDispenserBoostPerPower;
        this.maxBoost = config.poweredDispenserMaxBoost;
        this.tntMaxBoost = config.poweredDispenserTntMaxBoost;
        this.minStraightness = config.poweredDispenserMinStraightness;
    }

    void register() {
        CustomHeadBlock block = registry.getType(BLOCK_ID);
        if (block == null) {
            registry.getPlugin().getLogger().warning(
                    "RotationBlocks: block '" + BLOCK_ID + "' not found — skipping overlay");
            return;
        }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .lockContainer(false)   // vanilla GUI + hoppers + item pipes stay open
            .onNeighborChange((b, face) -> recalcIfKnown(b))
            .onInteract(this::onInteract)
            .displayTransformResolver((b, state, dec, idx) -> eyeTransform(b, dec))
            .onBlockPlaced((b, state) -> nameDispenser(b))
            // Omni consumer: power arrives from the first aligned shaft on ANY face (null excluded face).
            .onChunkLoad((b, state) -> network.addNode(b, BLOCK_ID, RotationNetwork.Axis.Y,
                RotationNetwork.NodeRole.CONSUMER, power, false, true, null))
            .onChunkUnload(b -> network.removeNode(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> { dropContents(b); network.removeNode(CustomBlockRegistry.LocationKey.of(b)); })
            .build());
        registry.getPlugin().getServer().getPluginManager().registerEvents(this, registry.getPlugin());
    }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────────────────────────

    private void recalcIfKnown(Block b) {
        var key = CustomBlockRegistry.LocationKey.of(b);
        if (network.getNode(key) != null) network.recalculate(key);
    }

    private boolean isOurs(Block b) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && BLOCK_ID.equals(t.fullId());
    }

    private boolean onInteract(Block b, PlayerInteractEvent event) {
        if (RotationBlocks.isWrench(event.getPlayer().getInventory().getItemInMainHand())) {
            return RotationBlocks.debugInteract(b, event, network, registry);
        }
        // Refresh the title to the live power BEFORE the vanilla GUI opens this tick (a real container
        // bakes its title at open time, so updating a live inventory wouldn't retitle it).
        setName(b, liveName(b));
        return false; // fall through → the vanilla dispenser GUI opens
    }

    /** Initial title at placement. */
    private static void nameDispenser(Block b) {
        setName(b, Component.text("Powered Dispenser"));
    }

    private static void setName(Block b, Component name) {
        if (b.getState() instanceof Container c) {
            c.customName(name);
            c.update();
        }
    }

    /** "Powered Dispenser — ⚡N" (N = network power driving the boost) when powered, else "no power". */
    private Component liveName(Block b) {
        var key = CustomBlockRegistry.LocationKey.of(b);
        int[] stats = network.getNetworkStats(key);
        if (stats != null && network.isPowered(key)) {
            return Component.text("Powered Dispenser — ⚡" + Math.min(powerCap, Math.max(0, stats[0])));
        }
        return Component.text("Powered Dispenser — no power");
    }

    /** Break/explosion: spill the dispenser's contents before the framework wipes the inventory. */
    private void dropContents(Block b) {
        if (!(b.getState() instanceof Container c)) return;
        Inventory inv = c.getInventory();
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack it : inv.getContents()) {
            if (it != null && !it.getType().isAir()) b.getWorld().dropItemNaturally(loc, it);
        }
        inv.clear();
    }

    // ── Eye displays (rotated onto the live facing) ─────────────────────────────────────────────────

    private static BlockFace facingOf(Block b) {
        return (b.getBlockData() instanceof Directional d) ? d.getFacing() : BlockFace.SOUTH;
    }

    /** Rotate a mouth-face eye (authored for SOUTH/+Z) onto the dispenser's live facing. */
    private static Transformation eyeTransform(Block b, CustomHeadBlock.DisplayEntityConfig dec) {
        BlockFace facing = facingOf(b);
        AxisAngle4f r = Faces.rotationForFace(facing);
        Transformation base = dec.transform();
        Vector3f t0 = new Vector3f(base.getTranslation());
        // On up/down faces the eyes lie flat (rotationForFace tips the 2px into the face plane). Drop the
        // authored vertical offset first, otherwise it rotates into a Z-offset — the eyes want to sit to
        // either side in X, centered on Z.
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) t0.y = 0;
        Vector3f t = Faces.rotate(r, t0);
        return new Transformation(t, r, base.getScale(), Faces.identity());
    }

    // ── Boost: gate the dispense, correct the spawned entity one tick later ─────────────────────────

    /** Boost magnitudes for the current network state; {@code straight} in [minStraightness, 1]. */
    private record Boost(double boost, double tnt, double straight) {}

    private Boost boostFor(CustomBlockRegistry.LocationKey key) {
        // Scale with the network's total supplied power ("power provided"), clamped at powerCap —
        // more power ⇒ more boost, capped. (Not surplus: other consumers don't sap the launcher.)
        int[] stats = network.getNetworkStats(key);
        int level = stats == null ? 0 : Math.min(powerCap, Math.max(0, stats[0]));
        double boost = Math.min(maxBoost, minBoost + level * boostPerPower);
        double tnt = Math.min(tntMaxBoost, minBoost + level * boostPerPower);
        double straight = minStraightness + (1.0 - minStraightness) * ((double) level / powerCap);
        return new Boost(boost, tnt, Math.min(1.0, straight));
    }

    /**
     * Gate the dispense and schedule the boost. We never write {@code event.getVelocity()} — for TNT
     * (and boats/spawn-eggs/minecarts) that field is the entity's spawn position, so writing it would
     * spawn the entity in the void. Everything is corrected one tick later on the spawned entity.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (event instanceof BlockDispenseArmorEvent) return;      // equipping armor — pure vanilla
        Block b = event.getBlock();
        if (b.getType() != Material.DISPENSER || !isOurs(b)) return;
        var key = CustomBlockRegistry.LocationKey.of(b);
        if (!network.isPowered(key)) return;                       // unpowered → exact vanilla

        Boost boost = boostFor(key);
        BlockFace facing = facingOf(b);
        Bukkit.getScheduler().runTask(registry.getPlugin(), () -> scanAndBoost(b, facing, boost));
        cue(b, facing);
    }

    /**
     * One-tick-later correction of whatever the dispense spawned (item, projectile, TNT, fireball). The
     * box is long along the facing because a fast projectile has already flown ~1–2 blocks in its first
     * tick; short and wide perpendicular so it stays in this dispenser's column. Boosted entities are
     * tagged so an adjacent same-tick dispenser's overlapping scan can't double-boost them.
     */
    private void scanAndBoost(Block b, BlockFace facing, Boost boost) {
        if (b.getType() != Material.DISPENSER || !isOurs(b)) return;   // stale-block guard
        Vector dir = facing.getDirection();
        Vector center = b.getLocation().add(0.5, 0.5, 0.5).toVector();
        BoundingBox box = BoundingBox.of(
                center.clone().add(dir.clone().multiply(0.2)),
                center.clone().add(dir.clone().multiply(2.6)));
        box.expand(dir.getX() == 0 ? 0.6 : 0, dir.getY() == 0 ? 0.6 : 0, dir.getZ() == 0 ? 0.6 : 0);
        for (Entity e : b.getWorld().getNearbyEntities(box)) {
            if (e.getTicksLived() > 2 || e.hasMetadata(BOOSTED_META)) continue;
            if (e instanceof Firework) continue;                      // self-propelled
            if (e instanceof Fireball fb) {
                // Acceleration-driven (fire charge, wind charge — also a Projectile, so match first):
                // re-aim the acceleration straight (what sticks) and give an initial velocity kick.
                fb.setDirection(dir);
                fb.setVelocity(launch(e.getVelocity(), dir, boost.boost(), boost.straight()));
            } else if (e instanceof TNTPrimed) {
                double pop = Math.max(0, e.getVelocity().getY());     // keep the natural upward pop
                e.setVelocity(dir.clone().multiply(boost.tnt()).add(new Vector(0, pop, 0)));
            } else if (e instanceof Projectile || e instanceof Item) {
                e.setVelocity(launch(e.getVelocity(), dir, boost.boost(), boost.straight()));
            } else {
                continue;                                            // not a launchable payload
            }
            e.setMetadata(BOOSTED_META, new FixedMetadataValue(registry.getPlugin(), true));
        }
    }

    /** {@code dir·(max(along,0)+boost) + perp·(1−straight)} — speed along facing, spread scaled by straightness. */
    private static Vector launch(Vector v, Vector dir, double boost, double straight) {
        double along = v.dot(dir);
        Vector perp = v.clone().subtract(dir.clone().multiply(along));
        return dir.clone().multiply(Math.max(along, 0) + boost).add(perp.multiply(1.0 - straight));
    }

    private void cue(Block b, BlockFace facing) {
        Location mouth = b.getLocation().add(0.5, 0.5, 0.5).add(facing.getDirection().multiply(0.6));
        b.getWorld().spawnParticle(Particle.CRIT, mouth, 6, 0.12, 0.12, 0.12, 0.05);
        b.getWorld().playSound(mouth, Sound.ENTITY_ARROW_SHOOT, 0.7f, 0.7f);
        if (MachineActedEvent.hasListeners()) {
            Bukkit.getPluginManager().callEvent(new MachineActedEvent(b, BLOCK_ID));
        }
    }
}
