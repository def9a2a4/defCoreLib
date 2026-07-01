package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;

import java.util.ArrayList;
import java.util.List;

/**
 * Places a {@link ShowcaseSpec} into the world as real, network-joined custom blocks plus vanilla
 * support. Mirrors the canonical place→register path in {@code CoreLibPlugin.onBlockPlace}: set the
 * head + facing, {@link CustomBlockRegistry#markBlock}, then next tick {@code applyConfig} + redstone/
 * tick tracking + the block's place/chunk-load callback (which is where rotation blocks add their
 * network node). So the built machine behaves exactly like a hand-placed one.
 */
final class ShowcaseBuilder {

    private final CoreLibPlugin plugin;
    private final CustomBlockRegistry registry;

    ShowcaseBuilder(CoreLibPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** Build {@code spec} at {@code origin} (origin = the [0,0,0] cell). Returns blocks placed. */
    int build(ShowcaseSpec spec, Location origin) {
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();

        // Vanilla support first, so it can serve as redstone/attachment for the custom blocks.
        for (ShowcaseSpec.VanillaSpec v : spec.vanilla) {
            Block b = world.getBlockAt(ox + v.at()[0], oy + v.at()[1], oz + v.at()[2]);
            if (v.data() != null) {
                try {
                    b.setBlockData(Bukkit.createBlockData(v.material(), v.data()), false);
                    continue;
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("showcase: bad block-data '" + v.data() + "' for "
                            + v.material() + " — placing plain (" + ex.getMessage() + ")");
                }
            }
            b.setType(v.material(), false);
        }

        // Custom blocks: place + markBlock now; defer applyConfig + callbacks to next tick (batched),
        // so every node is registered before the network recalculates adjacency.
        List<Runnable> deferred = new ArrayList<>();
        int placed = 0;
        for (ShowcaseSpec.BlockSpec bs : spec.blocks) {
            if (placeCustom(world, ox, oy, oz, bs, deferred)) placed++;
        }
        Bukkit.getScheduler().runTask(plugin, () -> deferred.forEach(Runnable::run));
        return placed;
    }

    private boolean placeCustom(World world, int ox, int oy, int oz,
                                ShowcaseSpec.BlockSpec bs, List<Runnable> deferred) {
        CustomHeadBlock type = registry.getType(bs.id());
        if (type == null) {
            plugin.getLogger().warning("showcase: unknown block id " + bs.id());
            return false;
        }
        Block block = world.getBlockAt(ox + bs.at()[0], oy + bs.at()[1], oz + bs.at()[2]);
        BlockFace placedOn = bs.facing();
        boolean floor = placedOn == BlockFace.DOWN || placedOn == BlockFace.UP;

        placeHead(block, floor, floor ? null : placedOn.getOppositeFace());

        // Resolve state: explicit > placement_state_map[face] > default.
        String state = bs.state();
        if (state == null) {
            state = type.defaultState();
            var psm = type.placementStateMap();
            if (psm != null) {
                String mapped = psm.get(placedOn);
                if (mapped != null) state = mapped;
            }
        }
        // Some states require a floor PLAYER_HEAD even on a wall placement.
        if (state != null && type.playerHeadStates().contains(state)
                && block.getType() == Material.PLAYER_WALL_HEAD) {
            placeHead(block, true, null);
        }

        registry.markBlock(block, type, state);

        final String fstate = state;
        final int power = type.sensitivity() != CustomHeadBlock.Sensitivity.NONE
                ? registry.readPower(block, type) : 0;
        deferred.add(() -> {
            if (registry.getTypeFromBlock(block) == null) return;   // broken between place and tick
            registry.applyConfig(block, type, fstate, power);
            if (type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
                registry.trackRedstone(block, type, power);
            }
            if (type.onTick() != null && type.tickInterval() != null) {
                registry.trackTick(block, type);
            }
            // The place callback (else chunk-load callback) is where rotation blocks add their node.
            if (type.onBlockPlaced() != null) {
                type.onBlockPlaced().accept(block, fstate);
            } else if (type.onChunkLoadCallback() != null) {
                type.onChunkLoadCallback().accept(block, fstate);
            }
        });
        return true;
    }

    private void placeHead(Block block, boolean floor, BlockFace headFacing) {
        if (floor) {
            block.setType(Material.PLAYER_HEAD, false);
            if (block.getBlockData() instanceof Rotatable r) {
                r.setRotation(BlockFace.NORTH);
                block.setBlockData(r, false);
            }
        } else {
            block.setType(Material.PLAYER_WALL_HEAD, false);
            if (block.getBlockData() instanceof Directional d) {
                d.setFacing(headFacing);
                block.setBlockData(d, false);
            }
        }
    }
}
