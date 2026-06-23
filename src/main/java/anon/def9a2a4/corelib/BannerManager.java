package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BannerManager implements Listener {

    private static final String TAG_PREFIX = "corelib:banner:";
    private static final double SEARCH_RADIUS = 2.5;

    // ── Placement ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (!LargeBannerRecipes.isBanner(held.getType())) return;

        boolean isLarge = LargeBannerRecipes.isLargeBanner(held);
        boolean isFenceHost = isFlagHost(clicked.getType());
        boolean sneaking = event.getPlayer().isSneaking();

        if (isFenceHost && !sneaking) {
            event.setCancelled(true);
            placeFlag(event.getPlayer(), clicked, event.getBlockFace(), held, isLarge);
        } else if (isLarge) {
            event.setCancelled(true);
            placeLargeBanner(event.getPlayer(), clicked, event.getBlockFace(), held);
        }
    }

    private void placeFlag(Player player, Block block, BlockFace clickedFace, ItemStack banner, boolean isLarge) {
        BlockFace face = resolveHorizontalFace(clickedFace, player);
        String tag = TAG_PREFIX + blockKey(block) + ":" + face.name().toLowerCase();

        if (!DisplayUtil.findByTag(block.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            player.sendMessage(Component.text("A banner is already on this side", NamedTextColor.RED));
            return;
        }

        float scale = isLarge ? 2.0f : 1.0f;
        ItemStack displayBanner = banner.asQuantity(1);

        spawnPair(block.getLocation(), flagTransform(face, scale), flagBackTransform(face, scale),
                displayBanner, tag);

        consumeItem(player, banner);
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_WOOL_PLACE, 1f, 1f);
    }

    private void placeLargeBanner(Player player, Block clicked, BlockFace clickedFace, ItemStack banner) {
        BlockFace face;
        Transformation front, back;

        if (clickedFace == BlockFace.UP || clickedFace == BlockFace.DOWN) {
            // Standing banner — orient by player yaw
            float yaw = player.getLocation().getYaw();
            face = clickedFace;
            front = standingTransform(yaw, 2.0f);
            back = standingBackTransform(yaw, 2.0f);
        } else {
            // Wall banner
            face = clickedFace;
            front = wallTransform(clickedFace, 2.0f);
            back = wallBackTransform(clickedFace, 2.0f);
        }

        String tag = TAG_PREFIX + blockKey(clicked) + ":" + face.name().toLowerCase();

        if (!DisplayUtil.findByTag(clicked.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            player.sendMessage(Component.text("A banner is already on this side", NamedTextColor.RED));
            return;
        }

        ItemStack displayBanner = banner.asQuantity(1);
        spawnPair(clicked.getLocation(), front, back, displayBanner, tag);

        consumeItem(player, banner);
        clicked.getWorld().playSound(clicked.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_WOOL_PLACE, 1f, 1f);
    }

    private void spawnPair(Location blockLoc, Transformation front, Transformation back,
                           ItemStack banner, String tag) {
        DisplayUtil.spawn(blockLoc, banner, front, tag);
        DisplayUtil.spawn(blockLoc, banner, back, tag);
    }

    private static void consumeItem(Player player, ItemStack held) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        handleRemoval(event.getBlock(), event.getPlayer().getGameMode() != GameMode.CREATIVE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            handleRemoval(block, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            handleRemoval(block, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        handleRemoval(event.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        handleRemoval(event.getToBlock(), true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            handleRemoval(block, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            handleRemoval(block, true);
        }
    }

    private void handleRemoval(Block block, boolean drop) {
        String prefix = TAG_PREFIX + blockKey(block);
        List<Display> entities = DisplayUtil.findByTag(block.getLocation(), prefix, SEARCH_RADIUS);
        if (entities.isEmpty()) return;

        Set<String> droppedFaces = new HashSet<>();
        for (Display entity : entities) {
            if (drop && entity instanceof ItemDisplay itemDisplay) {
                String face = extractFace(entity);
                if (face != null && droppedFaces.add(face)) {
                    ItemStack bannerItem = itemDisplay.getItemStack();
                    if (bannerItem != null) {
                        block.getWorld().dropItemNaturally(
                                block.getLocation().add(0.5, 0.5, 0.5), bannerItem.asQuantity(1));
                    }
                }
            }
            entity.remove();
        }
    }

    // ── Orphan cleanup ───────────────────────────────────────────────────

    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (!(entity instanceof Display)) continue;
            for (String tag : entity.getScoreboardTags()) {
                if (!tag.startsWith(TAG_PREFIX)) continue;
                int[] coords = parseCoords(tag);
                if (coords == null) continue;
                Block block = entity.getWorld().getBlockAt(coords[0], coords[1], coords[2]);
                if (!isFlagHost(block.getType()) && !block.getType().isSolid()) {
                    entity.remove();
                }
                break;
            }
        }
    }

    // ── Entity protection ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        for (String tag : event.getEntity().getScoreboardTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ── Transforms ───────────────────────────────────────────────────────
    // These are starting values — tune in-game as needed.

    private static Transformation flagTransform(BlockFace face, float scale) {
        return new Transformation(
                flagTranslation(face),
                flagRotation(face),
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    private static Transformation flagBackTransform(BlockFace face, float scale) {
        return new Transformation(
                flagTranslation(face),
                flagBackRotation(face),
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    private static Quaternionf flagRotation(BlockFace face) {
        return switch (face) {
            case NORTH -> new Quaternionf().rotateX((float) Math.toRadians(90));
            case SOUTH -> new Quaternionf().rotateX((float) Math.toRadians(-90));
            case EAST -> new Quaternionf().rotateZ((float) Math.toRadians(-90));
            case WEST -> new Quaternionf().rotateZ((float) Math.toRadians(90));
            default -> new Quaternionf();
        };
    }

    private static Quaternionf flagBackRotation(BlockFace face) {
        return new Quaternionf().rotateY((float) Math.toRadians(180)).mul(flagRotation(face));
    }

    private static Vector3f flagTranslation(BlockFace face) {
        float offset = 0.3f;
        return new Vector3f(face.getModX() * offset, 0, face.getModZ() * offset);
    }

    private static Transformation standingTransform(float yaw, float scale) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        return new Transformation(new Vector3f(0, 0.5f, 0), rotation,
                new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static Transformation standingBackTransform(float yaw, float scale) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw + 180));
        return new Transformation(new Vector3f(0, 0.5f, 0), rotation,
                new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static Transformation wallTransform(BlockFace face, float scale) {
        float yaw = faceToYaw(face);
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(yaw));
        Vector3f offset = new Vector3f(face.getModX() * 0.4f, 0, face.getModZ() * 0.4f);
        return new Transformation(offset, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static Transformation wallBackTransform(BlockFace face, float scale) {
        float yaw = faceToYaw(face) + 180;
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(yaw));
        Vector3f offset = new Vector3f(face.getModX() * 0.4f, 0, face.getModZ() * 0.4f);
        return new Transformation(offset, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isFlagHost(Material mat) {
        String name = mat.name();
        return (name.endsWith("_FENCE") && !name.endsWith("_FENCE_GATE"))
                || mat == Material.CHAIN
                || mat == Material.IRON_BARS;
    }

    private static BlockFace resolveHorizontalFace(BlockFace clickedFace, Player player) {
        if (clickedFace == BlockFace.NORTH || clickedFace == BlockFace.SOUTH
                || clickedFace == BlockFace.EAST || clickedFace == BlockFace.WEST) {
            return clickedFace;
        }
        return yawToFace(player.getLocation().getYaw());
    }

    private static BlockFace yawToFace(float yaw) {
        yaw = ((yaw % 360) + 360) % 360;
        if (yaw >= 315 || yaw < 45) return BlockFace.SOUTH;
        if (yaw < 135) return BlockFace.WEST;
        if (yaw < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    private static float faceToYaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
    }

    private static String blockKey(Block block) {
        return block.getX() + "_" + block.getY() + "_" + block.getZ();
    }

    private static String extractFace(Display entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (!tag.startsWith(TAG_PREFIX)) continue;
            int lastColon = tag.lastIndexOf(':');
            if (lastColon > TAG_PREFIX.length()) {
                return tag.substring(lastColon + 1);
            }
        }
        return null;
    }

    private static int[] parseCoords(String tag) {
        // Format: corelib:banner:{x}_{y}_{z}:{face}
        String afterPrefix = tag.substring(TAG_PREFIX.length());
        int faceColon = afterPrefix.lastIndexOf(':');
        if (faceColon < 0) return null;
        String coordPart = afterPrefix.substring(0, faceColon);
        String[] parts = coordPart.split("_");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
