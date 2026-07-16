package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Demo mechanism consumer: mechanism minecart.
 * Place a minecart on rails, glue blocks to it (glue item, right-click), then push onto a
 * powered activator rail — or right-click the cart — to assemble the glued selection into a
 * mechanism that follows the minecart. Another activator rail disassembles back to blocks.
 * Assembly uses the cart's glued selection; with no glue it defaults to the single block
 * directly above the cart (nothing if that block is air).
 *
 * Self-contained — can be spun out to a separate plugin by moving this file
 * and calling getMechanismRegistry() from the new plugin.
 */
final class MechanismMinecartManager implements Listener {

    /** Ride offset for Minecart passengers. Needs empirical tuning. */
    private static final float MINECART_RIDE_OFFSET = 0f;

    /** Vanilla minecart max speed (blocks/tick), restored when a cart isn't frozen by glue. */
    private static final double DEFAULT_MINECART_MAX_SPEED = 0.4d;

    /** Registry id of the mechanism-minecart custom item (declared in rotation-blocks.yml). */
    private static final String MECH_MINECART_ID = "mech:mechanism_minecart";

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;
    private final NamespacedKey mechanismMinecartKey;

    private final Map<UUID, MinecartState> tracked = new HashMap<>();
    private BukkitTask tickTask;

    // Package-visible: CartCollision (terrain collision, Part B) reads/writes the heading + resume state.
    static final class MinecartState {
        final Minecart minecart;
        Mechanism mechanism;       // null when unassembled
        boolean wasOnPoweredRail;  // edge detection

        // Terrain-collision state (Part B). Transient across /reload — rebuilt as the cart moves.
        int headingAxis = 0;       // 0 = X, 2 = Z
        int headingSign = 0;       // +1 / -1; 0 until inited at assemble / first motion
        boolean autoStopped = false;
        double lastGap = CartCollision.DEFAULT_MAX_SPEED;
        boolean kicked = false;    // a resume kick is in flight
        double kickAnchorX, kickAnchorZ;

        MinecartState(Minecart minecart) {
            this.minecart = minecart;
            this.wasOnPoweredRail = false;
        }
    }

    MechanismMinecartManager(JavaPlugin plugin, CustomBlockRegistry registry, MechanismRegistry mechRegistry,
                             GlueManager glueManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.mechRegistry = mechRegistry;
        this.glueManager = glueManager;
        this.mechanismMinecartKey = new NamespacedKey(plugin, "mechanism_minecart");
    }

    void register() {
        // The item (item-only, placeable: false) is declared in rotation-blocks.yml; its identity is the
        // registry block_type PDC. All spawn/tick/assemble/destroy logic lives below — just verify it exists.
        if (registry.getType(MECH_MINECART_ID) == null) {
            plugin.getLogger().warning("MechanismMinecartManager: '" + MECH_MINECART_ID + "' not found in "
                + "registry (check rotation-blocks.yml) — minecart item will not be obtainable");
        }

        // Scan already-loaded chunks for surviving mechanism minecarts (post-reload recovery)
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkForMinecarts(chunk);
            }
        }

        // Start tick task for activator rail detection
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Scan a chunk for mechanism minecarts and re-register any found. */
    void scanChunkForMinecarts(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Minecart minecart)) continue;
            if (tracked.containsKey(minecart.getUniqueId())) continue;
            if (!minecart.getScoreboardTags().contains("corelib:mechanism_minecart")) continue;
            tracked.put(minecart.getUniqueId(), new MinecartState(minecart));
        }
    }

    /**
     * A chunk's entities are unloading — cleanly disassemble any assembled mechanism cart it hosts
     * WHILE the chunk is still loaded (blocks writable), then drop tracking. Symmetric counterpart
     * to {@link #scanChunkForMinecarts}. Without this the tick guard would only notice the cart once
     * its chunk is already unloading ({@code !isValid()}) and disassemble against a dead chunk —
     * racing block-writes/entity-removal, orphaning displays and losing the aired-out real blocks.
     * Unassembled carts just drop tracking; they are re-registered on the matching EntitiesLoad.
     */
    void onEntitiesUnload(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!(entity instanceof Minecart)) continue;
            MinecartState state = tracked.remove(entity.getUniqueId());
            if (state != null && state.mechanism != null) {
                state.mechanism.disassemble();
            }
        }
    }

    void shutdown() {
        if (tickTask != null) tickTask.cancel();
        // Disassemble all active mechanisms
        for (MinecartState state : new ArrayList<>(tracked.values())) {
            if (state.mechanism != null) {
                state.mechanism.disassemble();
            }
        }
        tracked.clear();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Placement: right-click a rail with the mechanism minecart item
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceMinecart(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isMechanismMinecartItem(item)) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();
        if (type != Material.RAIL && type != Material.POWERED_RAIL
            && type != Material.DETECTOR_RAIL && type != Material.ACTIVATOR_RAIL) return;

        event.setCancelled(true);

        org.bukkit.Location spawnLoc = block.getLocation().add(0.5, 0, 0.5);
        Minecart minecart = block.getWorld().spawn(spawnLoc, RideableMinecart.class, m -> {
            m.setPersistent(true); // survive chunk unload — else it despawns and orphans the mechanism
            m.addScoreboardTag("corelib:mechanism_minecart");
            m.setDisplayBlockData(Bukkit.createBlockData(Material.LODESTONE));
            m.getPersistentDataContainer().set(mechanismMinecartKey, PersistentDataType.BYTE, (byte) 1);
        });

        tracked.put(minecart.getUniqueId(), new MinecartState(minecart));

        // Consume one item (not in creative — mirrors BannerManager.consumeItem)
        if (event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private boolean isMechanismMinecartItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        String typeId = pdc.get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        return MECH_MINECART_ID.equals(typeId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick: activator rail edge detection
    // ──────────────────────────────────────────────────────────────────────

    private void tick() {
        Iterator<Map.Entry<UUID, MinecartState>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            MinecartState state = it.next().getValue();
            if (state.minecart.isDead()) {                  // truly destroyed
                if (state.mechanism != null) state.mechanism.disassemble();
                it.remove();
                continue;
            }
            if (!state.minecart.isValid()) {                // chunk unloaded — onEntitiesUnload owns the
                continue;                                   // clean disassemble; do NOT disassemble here
            }                                               // against a dead chunk (races/loses blocks)

            // Freeze a glued, unassembled cart so its glue can't drift: with max speed 0 the physics step
            // clamps any velocity to 0, so the cart never crosses a cell (and originBlock() stays put).
            // An assembled cart rides — its maxSpeed is now the terrain-collision clamp (Part B). Unglued /
            // no-mechanism carts get vanilla speed. Written EVERY tick so a stale 0 self-heals post-/reload.
            boolean frozen = state.mechanism == null
                && glueManager.hasGlue(new EntityAnchor(state.minecart, () -> true));
            if (frozen) {
                state.minecart.setMaxSpeed(0.0d);
            } else if (state.mechanism == null) {
                state.minecart.setMaxSpeed(DEFAULT_MINECART_MAX_SPEED);
            } else {
                updateAssembledCartCollision(state, (BasicMechanism) state.mechanism);
            }

            boolean onPowered = isOnPoweredActivatorRail(state.minecart);

            // Rising edge: only trigger on off → on transition
            if (onPowered && !state.wasOnPoweredRail) {
                if (state.mechanism == null) {
                    assemble(state);
                } else {
                    disassemble(state);
                }
            }

            state.wasOnPoweredRail = onPowered;
        }
    }

    /**
     * Per-tick terrain collision for an assembled mechanism cart (Part B). Order: B2 backstop (undo any
     * penetration from last tick's physics) → resolve heading → break fragiles in the path → B1 clamp
     * (write maxSpeed) → resume kick (a docked cart whose path reopened needs a push).
     */
    private void updateAssembledCartCollision(MinecartState state, BasicMechanism mech) {
        CartCollision.resnapIfPenetrating(mech, state);

        boolean wasStopped = state.autoStopped;
        boolean atRest = CartCollision.resolveHeading(state);
        org.bukkit.util.Vector v = state.minecart.getVelocity();
        double vmag = Math.hypot(v.getX(), v.getZ());

        if (!atRest || state.autoStopped) {
            CartCollision.breakFragilesAhead(mech, state, atRest ? CartCollision.RESUME_KICK : vmag);
        }

        double maxSpeed = CartCollision.clampedMaxSpeed(mech, state, atRest);
        state.minecart.setMaxSpeed(maxSpeed);

        handleResume(state, vmag);

        // Feedback: hint the rider the first time the cart docks against an obstacle.
        if (state.autoStopped && !wasStopped) {
            for (Entity passenger : state.minecart.getPassengers()) {
                if (passenger instanceof org.bukkit.entity.Player p) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text(
                        "Mechanism blocked — clear the path, or break the cart to recover"));
                }
            }
        }
    }

    /** Push a docked cart back into motion once its path reopens; disarm once it has genuinely departed. */
    private void handleResume(MinecartState state, double vmag) {
        if (!state.autoStopped) { state.kicked = false; return; }
        Minecart cart = state.minecart;
        // Departed under its own power (player push / powered rail / downhill) → disarm, don't re-kick.
        if (vmag >= CartCollision.RESUME_KICK) { state.autoStopped = false; state.kicked = false; return; }
        if (state.kicked) {
            double dx = cart.getLocation().getX() - state.kickAnchorX;
            double dz = cart.getLocation().getZ() - state.kickAnchorZ;
            if (Math.hypot(dx, dz) >= CartCollision.RESUME_CLEAR_DIST) {
                state.autoStopped = false;
                state.kicked = false;
                return;
            }
        }
        // Kick only while slow (don't overwrite a powered rail that's already accelerating it away).
        if (state.lastGap > CartCollision.RESUME_GAP_OPEN && vmag < CartCollision.RESUME_KICK) {
            org.bukkit.util.Vector dir = state.headingAxis == 0
                ? new org.bukkit.util.Vector(state.headingSign, 0, 0)
                : new org.bukkit.util.Vector(0, 0, state.headingSign);
            cart.setVelocity(dir.multiply(CartCollision.RESUME_KICK));
            if (!state.kicked) {
                state.kicked = true;
                state.kickAnchorX = cart.getLocation().getX();
                state.kickAnchorZ = cart.getLocation().getZ();
            }
        }
    }

    /** Seed the collision heading from the cart's yaw at assembly, so the at-rest scan is never degenerate. */
    private void initHeading(MinecartState state) {
        float yaw = ((state.minecart.getLocation().getYaw() % 360f) + 360f) % 360f;
        if (yaw < 45f || yaw >= 315f)      { state.headingAxis = 2; state.headingSign = 1; }   // +Z (south)
        else if (yaw < 135f)               { state.headingAxis = 0; state.headingSign = -1; }  // -X (west)
        else if (yaw < 225f)               { state.headingAxis = 2; state.headingSign = -1; }  // -Z (north)
        else                               { state.headingAxis = 0; state.headingSign = 1; }   // +X (east)
        state.autoStopped = false;
        state.kicked = false;
        state.lastGap = CartCollision.DEFAULT_MAX_SPEED;
    }

    /** Whether this cart currently has an assembled mechanism (used by glue authoring's at-rest gate). */
    boolean isAssembled(UUID minecartId) {
        MinecartState s = tracked.get(minecartId);
        return s != null && s.mechanism != null;
    }

    /** Whether this cart is a tracked mechanism minecart (glue authoring targets only these). */
    boolean isMechanismMinecart(Minecart minecart) {
        return tracked.containsKey(minecart.getUniqueId());
    }

    boolean isOnPoweredActivatorRail(Minecart minecart) {
        Block block = minecart.getLocation().getBlock();
        if (block.getType() != Material.ACTIVATOR_RAIL) return false;
        if (block.getBlockData() instanceof org.bukkit.block.data.type.RedstoneRail rr) {
            return rr.isPowered();
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Assembly / Disassembly
    // ──────────────────────────────────────────────────────────────────────

    /** Snap the cart to its cell center and stop it. Package-visible: GlueAuthoring snaps on
     *  session open so glue offsets author against the cell the player sees (a cart parked on a
     *  cell boundary would otherwise anchor the whole selection one block off). */
    void snapAndStop(Minecart minecart) {
        org.bukkit.Location center = minecart.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        center.setYaw(minecart.getLocation().getYaw());
        center.setPitch(0);
        minecart.teleport(center);
        minecart.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    private void assemble(MinecartState state) {
        snapAndStop(state.minecart);

        // The cart's "self" cells — never captured: the rail it rides and the block below it. The cart
        // has no block hardware of its own, but slime/honey in the carried set could otherwise scoop
        // the track out from under it (casings can't — they bond only to casings).
        Block railCell = state.minecart.getLocation().getBlock();
        Set<CustomBlockRegistry.LocationKey> excluded =
            MoverExclusion.exclusionFor(List.of(railCell, railCell.getRelative(BlockFace.DOWN)));

        // Structure = the cart's glued selection (offsets in the cart's PDC, resolved relative to its
        // current cell — glued blocks must be co-located with the cart: assemble in place). With no
        // authored glue, default to the single block directly above the cart.
        Anchor anchor = new EntityAnchor(state.minecart, () -> state.mechanism == null);
        List<Block> resolved = glueManager.resolveStructure(anchor,
            excluded, MoverExclusion::blockedParticle);
        boolean glued = resolved != null && !resolved.isEmpty();
        // Pre-move snapshot: rebind stores ONLY authored glue (derived sticky blocks/leaves re-derive).
        final int[] authored = glued ? anchor.readOffsets() : null;
        List<Block> blocks;
        if (glued) {
            blocks = resolved;
        } else {
            Block seed = state.minecart.getLocation().clone().add(0, 1, 0).getBlock();
            if (!MovableBlocks.isMovable(seed, registry)) return;   // don't scoop air / immovable world blocks
            blocks = List.of(seed);
        }
        // Sticky spread: casings bond their casing frame; slime/honey drag their movable neighbours.
        blocks = StickySpread.withDerived(blocks, registry, glueManager.maxSize(),
            excluded, MoverExclusion::blockedParticle);

        Mechanism mech = mechRegistry.assembleMechanism(MECH_MINECART_ID, blocks,
            state.minecart, MINECART_RIDE_OFFSET, null);
        state.mechanism = mech;
        initHeading(state);
        // Rebind only authored glue to where the blocks land so it tracks across rides.
        if (glued) mech.setOnDisassembled(p ->
            glueManager.rebindTransformed(anchor, authored, mech.landingRotation()));
    }

    private void disassemble(MinecartState state) {
        if (state.mechanism == null) return;
        snapAndStop(state.minecart);
        state.mechanism.disassemble();
        state.mechanism = null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Events
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onMinecartDestroyed(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) return;
        MinecartState state = tracked.get(minecart.getUniqueId());
        if (state == null) return;

        // Disassemble before minecart is destroyed
        if (state.mechanism != null) {
            disassemble(state);
        }
        tracked.remove(minecart.getUniqueId());

        // Drop mechanism minecart item instead of normal minecart
        event.setCancelled(true);
        minecart.remove();

        CustomHeadBlock itemType = registry.getType(MECH_MINECART_ID);
        if (itemType != null) {
            minecart.getWorld().dropItemNaturally(minecart.getLocation(), itemType.createItem(1));
        }
    }

    // Creative middle-click (pick-block) on a mechanism minecart: give the custom item, not a
    // vanilla minecart. The cart is an entity, so PlayerPickBlockEvent never sees it.
    @EventHandler(priority = EventPriority.HIGH)
    public void onPickMinecart(io.papermc.paper.event.player.PlayerPickEntityEvent event) {
        if (!(event.getEntity() instanceof Minecart minecart) || !isMechanismMinecart(minecart)) return;
        CustomHeadBlock itemType = registry.getType(MECH_MINECART_ID);
        if (itemType == null) return;
        event.setCancelled(true);
        InventoryUtil.pickInto(event.getPlayer(), itemType.createItem(1), event.getTargetSlot());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMinecartEnter(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) return;
        if (!tracked.containsKey(minecart.getUniqueId())) return;
        if (event.getEntered() instanceof Display) return; // allow mechanism displays to mount
        event.setCancelled(true);
    }

    // ignoreCancelled: the glue-authoring handler (EventPriority.LOWEST) cancels this interaction when the
    // player holds the glue item, so this assemble/disassemble toggle is preempted during authoring.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinecartInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Minecart minecart)) return;
        MinecartState state = tracked.get(minecart.getUniqueId());
        if (state == null) return;
        event.setCancelled(true);
        if (state.mechanism == null) {
            assemble(state);
        } else {
            disassemble(state);
        }
    }
}
