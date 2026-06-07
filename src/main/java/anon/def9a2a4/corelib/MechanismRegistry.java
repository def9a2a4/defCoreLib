package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Manages active mechanisms: assembly, ticking (particles + animations),
 * collider lookup, and cleanup.
 */
public class MechanismRegistry {

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;

    private final Map<UUID, BasicMechanism> activeMechanisms = new HashMap<>();
    private final Map<UUID, ColliderRef> colliderIndex = new HashMap<>(); // shulker UUID → ref

    private @Nullable BukkitTask tickTask;

    // Reusable work matrix for animation tick
    private final Matrix4f workMatrix = new Matrix4f();

    public MechanismRegistry(JavaPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Assembly
    // ──────────────────────────────────────────────────────────────────────

    public Mechanism assembleMechanism(String type, List<Block> blocks, Location pivot,
                                       @Nullable MechanismSerializer serializer) {
        UUID mechId = UUID.randomUUID();
        List<MechanismBlockData> blockData = new ArrayList<>();

        // 1. Snapshot each block
        for (Block block : blocks) {
            BlockData bd = block.getBlockData();
            Matrix4f local = new Matrix4f().translation(
                block.getX() - (float) pivot.getX(),
                block.getY() - (float) pivot.getY(),
                block.getZ() - (float) pivot.getZ());

            String customType = null, customState = null;
            List<CustomHeadBlock.DisplayEntityConfig> decs = null;
            CustomHeadBlock.ParticleConfig particles = null;
            Inventory storage = null;

            CustomHeadBlock chb = registry.getTypeFromBlock(block);
            if (chb != null) {
                customType = chb.fullId();
                customState = registry.getState(block);
                decs = chb.resolveDisplayEntities(customState);
                particles = chb.resolveParticles(customState);
                if (chb.storage() != null) {
                    storage = Bukkit.createInventory(null, chb.storage().layout().slots);
                    registry.loadInventoryFromPDC(block, storage);
                    for (int s = 0; s < storage.getSize(); s++) {
                        ItemStack item = storage.getItem(s);
                        if (item != null) storage.setItem(s, item.clone());
                    }
                }
                registry.onBlockRemoved(block, chb);
            } else if (block.getState() instanceof Container c) {
                Inventory orig = c.getInventory();
                storage = Bukkit.createInventory(null, orig.getSize());
                for (int s = 0; s < orig.getSize(); s++) {
                    ItemStack item = orig.getItem(s);
                    if (item != null) storage.setItem(s, item.clone());
                }
            }

            blockData.add(new MechanismBlockData(bd, local, true, 1.0f,
                customType, customState, decs, particles, storage));
        }

        // 2. Two-pass block removal
        for (Block b : blocks) {
            if (FragileBlocks.isAttachable(b.getType())) b.setType(Material.AIR, false);
        }
        for (Block b : blocks) {
            if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
        }

        // 3. Spawn entities
        Location spawnLoc = pivot.clone().add(0, 2.5, 0);

        ArmorStand vehicle = pivot.getWorld().spawn(pivot, ArmorStand.class, as -> {
            as.setInvisible(true); as.setGravity(false); as.setSilent(true);
            as.setPersistent(true); as.setRotation(0, 0);
            as.addScoreboardTag("corelib:mech:" + mechId + ":vehicle");
        });

        BlockDisplay parentDisplay = pivot.getWorld().spawn(spawnLoc, BlockDisplay.class, d -> {
            d.setBlock(Bukkit.createBlockData(Material.AIR));
            d.setTeleportDuration(0); d.setViewRange(64f);
            d.setPersistent(true); d.setGravity(false);
            d.addScoreboardTag("corelib:mech:" + mechId + ":parent");
        });

        List<List<Display>> displaysPerBlock = new ArrayList<>();
        List<ColliderPair> colliders = new ArrayList<>();

        for (int i = 0; i < blockData.size(); i++) {
            MechanismBlockData mb = blockData.get(i);
            List<Display> group = new ArrayList<>();

            // Primary display
            Display primary;
            if (mb.customTypeId != null) {
                CustomHeadBlock chbType = registry.getType(mb.customTypeId);
                String tex = chbType != null
                    ? chbType.resolveTexture(mb.customState, 0, null)
                    : null;
                ItemStack headItem = tex != null
                    ? HeadUtil.createHead(tex, 1)
                    : new ItemStack(Material.PLAYER_HEAD);
                primary = spawnMechDisplay(spawnLoc, headItem, mechId, i, "display");
            } else {
                primary = spawnMechBlockDisplay(spawnLoc, mb.blockData, mechId, i, "display");
            }
            group.add(primary);

            // Additional displays from display_entities config
            if (mb.displayEntityConfigs != null) {
                for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                    var dec = mb.displayEntityConfigs.get(d);
                    Display extra = spawnMechDisplay(spawnLoc, dec.displayItem().clone(),
                        mechId, i, "extra_" + d);
                    if (dec.interpolationDuration() != 0) {
                        ((ItemDisplay) extra).setInterpolationDuration(dec.interpolationDuration());
                    }
                    group.add(extra);
                }
            }
            displaysPerBlock.add(group);

            // Collider
            if (mb.hasCollision) {
                final int blockIdx = i;
                ArmorStand carrier = pivot.getWorld().spawn(spawnLoc, ArmorStand.class, as -> {
                    as.setInvisible(true); as.setGravity(false); as.setSilent(true);
                    as.setSmall(true); as.setPersistent(true);
                    as.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":carrier");
                });
                Shulker shulker = pivot.getWorld().spawn(spawnLoc, Shulker.class, s -> {
                    s.setAI(false); s.setInvisible(true); s.setGravity(false);
                    s.setSilent(true); s.setPersistent(true);
                    s.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":collider");
                });
                carrier.addPassenger(shulker);
                ColliderPair cp = new ColliderPair(carrier, shulker, i);
                colliders.add(cp);
            }
        }

        // 4. Create mechanism, register colliders
        BasicMechanism mech = new BasicMechanism(mechId, type, pivot, vehicle, parentDisplay,
            displaysPerBlock, colliders, blockData, registry, serializer);
        mech.mechanismRegistry = this;

        for (ColliderPair cp : colliders) {
            colliderIndex.put(cp.shulker().getUniqueId(), new ColliderRef(mech, cp.blockIndex()));
        }

        // 5. 1-tick delay: mount passengers, set initial transforms
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (var group : displaysPerBlock) {
                for (Display d : group) parentDisplay.addPassenger(d);
            }
            vehicle.addPassenger(parentDisplay);
            mech.rotate(0);
        }, 1L);

        activeMechanisms.put(mechId, mech);
        return mech;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick
    // ──────────────────────────────────────────────────────────────────────

    public void startTasks() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMechanisms, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        // Destroy all active mechanisms (don't restore blocks on shutdown — no persistence yet)
        for (BasicMechanism mech : new ArrayList<>(activeMechanisms.values())) {
            mech.removeAllEntities();
        }
        activeMechanisms.clear();
        colliderIndex.clear();
    }

    private void tickMechanisms() {
        long currentTick = Bukkit.getServer().getCurrentTick();
        for (BasicMechanism mech : activeMechanisms.values()) {
            for (int i = 0; i < mech.blockCount(); i++) {
                List<Display> displays = mech.displaysPerBlock.get(i);
                if (displays.isEmpty() || !displays.get(0).isValid()) continue;

                MechanismBlockData mb = mech.blocks.get(i);

                // Animations on additional displays
                if (mb.displayEntityConfigs != null) {
                    for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                        var dec = mb.displayEntityConfigs.get(d);
                        if (dec.animation() == null) continue;
                        int displayIdx = d + 1;
                        if (displayIdx >= displays.size()) continue;
                        Display display = displays.get(displayIdx);
                        if (!display.isValid()) continue;

                        Matrix4f base = new Matrix4f(mech.currentTransform())
                            .mul(mb.localTransform)
                            .mul(BasicMechanism.transformToMatrix(dec.transform()));
                        long tickAge = currentTick - mech.startTick;
                        dec.animation().apply(base, tickAge, workMatrix);
                        display.setTransformationMatrix(workMatrix);
                    }
                }

                // TODO: particle ticking for mechanism blocks
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Collider lookup
    // ──────────────────────────────────────────────────────────────────────

    record ColliderRef(BasicMechanism mechanism, int blockIndex) {}

    @Nullable ColliderRef getColliderRef(Shulker shulker) {
        return colliderIndex.get(shulker.getUniqueId());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cleanup (called by BasicMechanism on disassemble/destroy)
    // ──────────────────────────────────────────────────────────────────────

    void onMechanismRemoved(BasicMechanism mech) {
        activeMechanisms.remove(mech.id());
        for (ColliderPair cp : mech.colliders) {
            colliderIndex.remove(cp.shulker().getUniqueId());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Entity spawn helpers
    // ──────────────────────────────────────────────────────────────────────

    private ItemDisplay spawnMechDisplay(Location loc, ItemStack item,
                                         UUID mechId, int blockIdx, String role) {
        return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setTeleportDuration(0); d.setShadowRadius(0f); d.setShadowStrength(0f);
            d.setViewRange(64f); d.setPersistent(true); d.setGravity(false);
            d.setInterpolationDuration(2);
            d.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":" + role);
        });
    }

    private BlockDisplay spawnMechBlockDisplay(Location loc, BlockData data,
                                               UUID mechId, int blockIdx, String role) {
        return loc.getWorld().spawn(loc, BlockDisplay.class, d -> {
            d.setBlock(data);
            d.setTeleportDuration(0); d.setShadowRadius(0f); d.setShadowStrength(0f);
            d.setViewRange(64f); d.setPersistent(true); d.setGravity(false);
            d.setInterpolationDuration(2);
            d.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":" + role);
        });
    }
}
