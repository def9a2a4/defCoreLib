package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BannerManager implements Listener {

    private static final String TAG_PREFIX = "corelib:banner:";
    private static final double SEARCH_RADIUS = 2.5;
    private static final float LARGE_SCALE = 2.2f;
    private static final float EXTRA_LARGE_SCALE = 3.6f;
    private static final float NORMAL_SCALE = 1.0f;
    private static final float HALF_PI = (float) (Math.PI / 2.0);

    private final JavaPlugin plugin;
    private float bedOffsetTowardHead = 0.75f;
    private float bedYOffset = -0.0375f;
    private float bedScaleX = 1.0f;
    private float bedScaleY = 1.78f;
    private float bedScaleZ = 1.0f;

    public BannerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        copyDefaultConfig();
        loadBedConfig();
    }

    private void copyDefaultConfig() {
        File configFile = new File(plugin.getDataFolder(), "banner-config.yml");
        if (!configFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try (InputStream in = plugin.getResource("banner-config.yml")) {
                if (in != null) Files.copy(in, configFile.toPath());
            } catch (IOException ignored) {}
        }
    }

    private void loadBedConfig() {
        File configFile = new File(plugin.getDataFolder(), "banner-config.yml");
        if (!configFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        bedOffsetTowardHead = (float) cfg.getDouble("bed-banner.offset-toward-head", bedOffsetTowardHead);
        bedYOffset = (float) cfg.getDouble("bed-banner.y-offset", bedYOffset);
        bedScaleX = (float) cfg.getDouble("bed-banner.scale-x", bedScaleX);
        bedScaleY = (float) cfg.getDouble("bed-banner.scale-y", bedScaleY);
        bedScaleZ = (float) cfg.getDouble("bed-banner.scale-z", bedScaleZ);
    }

    public void reloadBedConfig() {
        loadBedConfig();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ItemDisplay display)) continue;
                for (String tag : display.getScoreboardTags()) {
                    if (!tag.startsWith(TAG_PREFIX) || !tag.endsWith(":bed")) continue;
                    int[] coords = parseCoords(tag);
                    if (coords == null) continue;
                    Block block = world.getBlockAt(coords[0], coords[1], coords[2]);
                    Block foot = resolveBedFoot(block);
                    if (foot == null) continue;
                    org.bukkit.block.data.type.Bed bedData =
                            (org.bukkit.block.data.type.Bed) foot.getBlockData();
                    display.setTransformation(bedBannerTransform(bedData.getFacing()));
                    break;
                }
            }
        }
    }

    // ── Placement ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();

        if (held.getType() == Material.SHEARS) {
            if (tryShearsBannerRemoval(event.getPlayer(), clicked)) {
                event.setCancelled(true);
            }
            return;
        }

        if (!LargeBannerRecipes.isBanner(held.getType())) return;

        boolean isExtraLarge = LargeBannerRecipes.isExtraLargeBanner(held);
        boolean isLarge = !isExtraLarge && LargeBannerRecipes.isLargeBanner(held);
        boolean isFenceHost = isFlagHost(clicked.getType());
        boolean sneaking = event.getPlayer().isSneaking();

        if (sneaking && isBed(clicked.getType()) && !isLarge && !isExtraLarge) {
            event.setCancelled(true);
            placeBedBanner(event.getPlayer(), clicked, held);
        } else if (isFenceHost && !sneaking) {
            event.setCancelled(true);
            float scale = isExtraLarge ? EXTRA_LARGE_SCALE : (isLarge ? LARGE_SCALE : NORMAL_SCALE);
            placeFlag(event.getPlayer(), clicked, event.getBlockFace(), held, scale);
        } else if (isExtraLarge || isLarge) {
            event.setCancelled(true);
            float scale = isExtraLarge ? EXTRA_LARGE_SCALE : LARGE_SCALE;
            placeLargeBanner(event.getPlayer(), clicked, event.getBlockFace(), held, scale);
        }
    }

    private void placeFlag(Player player, Block block, BlockFace clickedFace, ItemStack banner, float scale) {
        BlockFace face = resolveHorizontalFace(clickedFace, player);
        String tag = TAG_PREFIX + blockKey(block) + ":" + face.name().toLowerCase();

        if (!DisplayUtil.findByTag(block.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            player.sendMessage(Component.text("A banner is already on this side", NamedTextColor.RED));
            return;
        }

        ItemStack displayBanner = banner.asQuantity(1);

        spawnPair(block.getLocation(), flagTransform(face, scale), flagBackTransform(face, scale),
                displayBanner, tag);

        consumeItem(player, banner);
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_WOOL_PLACE, 1f, 1f);
    }

    private void placeLargeBanner(Player player, Block clicked, BlockFace clickedFace, ItemStack banner, float scale) {
        BlockFace face;
        Transformation transform;

        if (clickedFace == BlockFace.UP || clickedFace == BlockFace.DOWN) {
            float yaw = player.getLocation().getYaw();
            face = clickedFace;
            transform = standingTransform(yaw, scale, clickedFace);
        } else {
            face = clickedFace;
            transform = wallTransform(clickedFace, scale);
        }

        String tag = TAG_PREFIX + blockKey(clicked) + ":" + face.name().toLowerCase();

        if (!DisplayUtil.findByTag(clicked.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            player.sendMessage(Component.text("A banner is already on this side", NamedTextColor.RED));
            return;
        }

        ItemStack displayBanner = banner.asQuantity(1);
        Block spawnBlock = clicked.getRelative(face);
        DisplayUtil.spawn(spawnBlock.getLocation(), displayBanner, transform, tag);

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

    // ── Shears removal ────────────────────────────────────────────────────

    private boolean tryShearsBannerRemoval(Player player, Block clicked) {
        String prefix = TAG_PREFIX + blockKey(clicked);
        List<Display> entities = DisplayUtil.findByTag(clicked.getLocation(), prefix, SEARCH_RADIUS);
        if (entities.isEmpty()) return false;

        Set<String> droppedFaces = new HashSet<>();
        for (Display entity : entities) {
            if (entity instanceof ItemDisplay itemDisplay) {
                String face = extractFace(entity);
                if (face != null && droppedFaces.add(face)) {
                    ItemStack bannerItem = itemDisplay.getItemStack();
                    if (bannerItem != null && player.getGameMode() != GameMode.CREATIVE) {
                        clicked.getWorld().dropItemNaturally(
                                clicked.getLocation().add(0.5, 0.5, 0.5), bannerItem.asQuantity(1));
                    }
                }
            }
            entity.remove();
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack shears = player.getInventory().getItemInMainHand();
            if (shears.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
                dmg.setDamage(dmg.getDamage() + 1);
                shears.setItemMeta(dmg);
                if (dmg.getDamage() >= shears.getType().getMaxDurability()) {
                    player.getInventory().setItemInMainHand(null);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                }
            }
        }

        clicked.getWorld().playSound(clicked.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_WOOL_BREAK, 1f, 1f);
        return true;
    }

    // ── Bed banners ─────────────────────────────────────────────────────

    private void placeBedBanner(Player player, Block clicked, ItemStack banner) {
        Block foot = resolveBedFoot(clicked);
        if (foot == null) return;

        org.bukkit.block.data.type.Bed bedData =
                (org.bukkit.block.data.type.Bed) foot.getBlockData();
        BlockFace facing = bedData.getFacing();

        String tag = TAG_PREFIX + blockKey(foot) + ":bed";

        if (!DisplayUtil.findByTag(foot.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            player.sendMessage(Component.text("This bed already has a banner", NamedTextColor.RED));
            return;
        }

        ItemStack displayBanner = banner.asQuantity(1);
        Transformation transform = bedBannerTransform(facing);
        DisplayUtil.spawn(foot.getLocation(), displayBanner, transform, tag);

        consumeItem(player, banner);
        foot.getWorld().playSound(foot.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_WOOL_PLACE, 1f, 1f);
    }

    private Transformation bedBannerTransform(BlockFace facing) {
        BlockFace opposite = facing.getOppositeFace();
        float yawRad = (float) Math.toRadians(-faceToYaw(opposite));
        Quaternionf rotation = new Quaternionf()
                .rotateY(yawRad)
                .rotateX(HALF_PI);

        float dx = facing.getModX() * bedOffsetTowardHead;
        float dz = facing.getModZ() * bedOffsetTowardHead;

        return new Transformation(
                new Vector3f(dx, bedYOffset, dz),
                rotation,
                new Vector3f(bedScaleX, bedScaleY, bedScaleZ),
                new Quaternionf());
    }

    private static Block resolveBedFoot(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Bed bedData)) return null;
        if (bedData.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT) return block;
        return block.getRelative(bedData.getFacing().getOppositeFace());
    }

    // ── Cleanup ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        boolean drop = event.getPlayer().getGameMode() != GameMode.CREATIVE;
        if (isBed(event.getBlock().getType())) {
            Block foot = resolveBedFoot(event.getBlock());
            if (foot != null) handleRemoval(foot, drop);
        }
        handleRemoval(event.getBlock(), drop);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (isBed(block.getType())) {
                Block foot = resolveBedFoot(block);
                if (foot != null) handleRemoval(foot, true);
            }
            handleRemoval(block, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (isBed(block.getType())) {
                Block foot = resolveBedFoot(block);
                if (foot != null) handleRemoval(foot, true);
            }
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
                flagTranslation(face, scale),
                flagRotation(face),
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    private static Transformation flagBackTransform(BlockFace face, float scale) {
        return new Transformation(
                flagTranslation(face, scale),
                flagBackRotation(face),
                new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    private static Quaternionf flagRotation(BlockFace face) {
        float yawRad = (float) Math.toRadians(-faceToYaw(face));
        return new Quaternionf()
                .rotateY(yawRad)
                .rotateZ(-HALF_PI)
                .rotateX(HALF_PI);
    }

    private static Quaternionf flagBackRotation(BlockFace face) {
        float yawRad = (float) Math.toRadians(-faceToYaw(face));
        return new Quaternionf()
                .rotateY(yawRad)
                .rotateZ(HALF_PI)
                .rotateX(HALF_PI);
    }

    private static Vector3f flagTranslation(BlockFace face, float scale) {
        float outward = 0.5f * scale + 0.125f;
        return new Vector3f(face.getModX() * outward, 0, face.getModZ() * outward);
    }

    private static Transformation standingTransform(float yaw, float scale, BlockFace clickedFace) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        float adjustment = -0.5f * (scale - LARGE_SCALE);
        float yOffset = clickedFace == BlockFace.UP ? 0.6f - adjustment : 2.6f - adjustment;
        return new Transformation(new Vector3f(0, yOffset, 0), rotation,
                new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static Transformation wallTransform(BlockFace face, float scale) {
        float yaw = faceToYaw(face) + 180;
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        float wallOffset = 0.6f;
        float yOffset = -1.25f * scale + 0.25f;
        if (scale > LARGE_SCALE) {
            wallOffset += 0.0625f;
            yOffset -= 0.0625f;
            scale *= 0.999f;
        }
        Vector3f offset = new Vector3f(-face.getModX() * wallOffset, yOffset, -face.getModZ() * wallOffset);
        return new Transformation(offset, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isBed(Material mat) {
        return mat.name().endsWith("_BED");
    }

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
