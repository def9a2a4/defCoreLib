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
import org.bukkit.entity.Player;
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
import java.util.EnumSet;

/**
 * Demo mechanism consumer: mechanism minecart.
 * Place a minecart on rails, build blocks above it, push onto a powered activator rail
 * to assemble blocks into a mechanism that follows the minecart. Another activator rail
 * disassembles back to blocks.
 *
 * Self-contained — can be spun out to a separate plugin by moving this file
 * and calling getMechanismRegistry() from the new plugin.
 */
final class MechanismMinecartManager implements Listener {

    /** Ride offset for Minecart passengers. Needs empirical tuning. */
    private static final float MINECART_RIDE_OFFSET = 0f;

    /** Vanilla minecart max speed (blocks/tick), restored when a cart isn't frozen by glue. */
    private static final double DEFAULT_MINECART_MAX_SPEED = 0.4d;

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;
    private final NamespacedKey mechanismMinecartKey;
    private final int maxStructureSize;

    private final Map<UUID, MinecartState> tracked = new HashMap<>();
    private Set<Material> allowedMaterials = Set.of(); // loaded from config
    private BukkitTask tickTask;

    private static final class MinecartState {
        final Minecart minecart;
        Mechanism mechanism;       // null when unassembled
        boolean wasOnPoweredRail;  // edge detection

        MinecartState(Minecart minecart) {
            this.minecart = minecart;
            this.wasOnPoweredRail = false;
        }
    }

    MechanismMinecartManager(JavaPlugin plugin, CustomBlockRegistry registry, MechanismRegistry mechRegistry,
                             GlueManager glueManager, int maxStructureSize) {
        this.plugin = plugin;
        this.registry = registry;
        this.mechRegistry = mechRegistry;
        this.glueManager = glueManager;
        this.mechanismMinecartKey = new NamespacedKey(plugin, "mechanism_minecart");
        this.maxStructureSize = maxStructureSize;
    }

    void register() {
        // The item (item-only, placeable: false) is declared in demo-blocks.yml; its identity is the
        // registry block_type PDC. All spawn/tick/assemble/destroy logic lives below — just verify it exists.
        if (registry.getType("demo:mechanism_minecart") == null) {
            plugin.getLogger().warning("MechanismMinecartManager: 'demo:mechanism_minecart' not found in "
                + "registry (check demo-blocks.yml) — minecart item will not be obtainable");
        }

        // Load allowed materials from config
        loadAllowedMaterials();

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

    private void loadAllowedMaterials() {
        java.io.File configFile = new java.io.File(plugin.getDataFolder(), "mechanism-minecart-blocks.yml");
        if (!configFile.exists()) {
            // Write default config
            plugin.saveResource("mechanism-minecart-blocks.yml", false);
        }
        org.bukkit.configuration.file.YamlConfiguration config =
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
        List<String> materialNames = config.getStringList("allowed_blocks");
        Set<Material> mats = EnumSet.noneOf(Material.class);
        for (String rawName : materialNames) {
            String name = rawName.trim();
            if (name.isEmpty()) continue;
            boolean startWild = name.startsWith("*");
            boolean endWild = name.endsWith("*");
            int matchesBefore = mats.size();
            if (startWild || endWild) {
                String pattern = name.replace("*", "").toUpperCase();
                if (pattern.isEmpty()) {
                    plugin.getLogger().warning("Mechanism minecart config: '" + name + "' is too broad (matches all blocks), skipping");
                    continue;
                }
                for (Material mat : Material.values()) {
                    if (!mat.isBlock()) continue;
                    String n = mat.name();
                    boolean match = (startWild && endWild) ? n.contains(pattern)
                        : startWild ? n.endsWith(pattern)
                        : n.startsWith(pattern);
                    if (match) mats.add(mat);
                }
            } else {
                Material mat = Material.getMaterial(name.toUpperCase());
                if (mat != null && mat.isBlock()) mats.add(mat);
            }
            if (mats.size() == matchesBefore) {
                plugin.getLogger().warning("Mechanism minecart config: '" + name + "' matched 0 blocks");
            }
        }
        allowedMaterials = Set.copyOf(mats);
        plugin.getLogger().info("Mechanism minecart: loaded " + allowedMaterials.size() + " allowed block types");
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

        // Consume one item
        item.setAmount(item.getAmount() - 1);
    }

    private boolean isMechanismMinecartItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        String typeId = pdc.get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        return "demo:mechanism_minecart".equals(typeId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick: activator rail edge detection
    // ──────────────────────────────────────────────────────────────────────

    private void tick() {
        Iterator<Map.Entry<UUID, MinecartState>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            MinecartState state = it.next().getValue();
            if (!state.minecart.isValid() || state.minecart.isDead()) {
                if (state.mechanism != null) state.mechanism.disassemble();
                it.remove();
                continue;
            }

            // Freeze a glued, unassembled cart so its glue can't drift: with max speed 0 the physics step
            // clamps any velocity to 0, so the cart never crosses a cell (and originBlock() stays put).
            // Restore vanilla speed once assembled (it must ride) or unglued.
            boolean frozen = state.mechanism == null
                && glueManager.hasGlue(new EntityAnchor(state.minecart, () -> true));
            state.minecart.setMaxSpeed(frozen ? 0.0d : DEFAULT_MINECART_MAX_SPEED);

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

    private void snapAndStop(Minecart minecart) {
        org.bukkit.Location center = minecart.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        center.setYaw(minecart.getLocation().getYaw());
        center.setPitch(0);
        minecart.teleport(center);
        minecart.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    private void assemble(MinecartState state) {
        snapAndStop(state.minecart);

        // Prefer a glued selection (offsets in the cart's PDC, resolved relative to its current cell — so
        // the glued structure must be co-located with the cart: assemble in place). An empty (but non-null)
        // resolve means "glued, but the blocks aren't at the cart" — fall back to flood-fill rather than
        // assembling nothing.
        Anchor anchor = new EntityAnchor(state.minecart, () -> state.mechanism == null);
        List<Block> resolved = glueManager.resolveStructure(anchor);
        boolean usingGlue = resolved != null && !resolved.isEmpty();

        List<Block> blocks;
        if (usingGlue) {
            blocks = resolved;
        } else {
            org.bukkit.Location above = state.minecart.getLocation().clone().add(0, 1, 0);
            if (above.getBlock().getType().isAir()) return;
            blocks = floodFillAllowed(above.getBlock(), maxStructureSize);
        }
        if (blocks.isEmpty()) return;

        Mechanism mech = mechRegistry.assembleMechanism("demo:mechanism_minecart", blocks,
            state.minecart, MINECART_RIDE_OFFSET, null);
        state.mechanism = mech;
        // Rebind glue to where the blocks land (relative to the cart's rest cell) so it tracks across rides.
        if (usingGlue) mech.setOnDisassembled(p -> glueManager.setStructure(anchor, p));
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

        CustomHeadBlock itemType = registry.getType("demo:mechanism_minecart");
        if (itemType != null) {
            minecart.getWorld().dropItemNaturally(minecart.getLocation(), itemType.createItem(1));
        }
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

    // ──────────────────────────────────────────────────────────────────────
    // Flood fill (data-driven allow list from mechanism-minecart-blocks.yml)
    // ──────────────────────────────────────────────────────────────────────

    private List<Block> floodFillAllowed(Block origin, int maxBlocks) {
        return FloodFill.component(origin, true, b -> allowedMaterials.contains(b.getType()),
            maxBlocks, () -> {
                plugin.getLogger().warning("Mechanism minecart: structure capped at " + maxBlocks
                    + " blocks at " + origin.getX() + "," + origin.getY() + "," + origin.getZ()
                    + " (raise max-structure-size in rotation-config.yml)");
                origin.getWorld().spawnParticle(org.bukkit.Particle.SMOKE,
                    origin.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, 0.02);
            });
    }
}
