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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BannerManager implements Listener {

    private static final String TAG_PREFIX = "corelib:banner:";
    private static final double SEARCH_RADIUS = 2.5;
    private static final float LARGE_SCALE = 2.2f;
    private static final float HUGE_SCALE = 3.6f;
    private static final float NORMAL_SCALE = 1.0f;
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private final JavaPlugin plugin;
    // Tuned defaults (baked in; the optional dev-only banner-config.yml can override at runtime).
    private float bedOffsetTowardHead = 0.701f;
    private float bedYOffset = -0.05f;
    private float bedScaleX = 1.199f;
    private float bedScaleY = 0.9f;
    private float bedScaleZ = 2.0f;
    private float flagDepth = 1.0f;
    private float flagFaceGapNormal = 0.0245f;
    private float flagFaceGapLarge = 0.052f;
    private float flagFaceGapHuge = 0.085f;
    private float flagOutwardOffsetNormal = -0.04f;
    private float flagOutwardOffsetLarge = -0.124f;
    private float flagOutwardOffsetHuge = -0.124f;
    private float flagSplayTilt = 0.45f;
    private float flagUnifiedTilt = 0.0f;
    // Banner placement is gated on the bbanners plugin. Listeners stay registered by core always;
    // only placement/shears checks this flag. Cleanup handlers (break/explode/burn/piston/…) stay
    // live regardless, so removing bbanners never orphans a placed banner's display entity.
    private volatile boolean active = false;

    public BannerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadBedConfig();
    }

    /** Enable banner placement (called when the bbanners plugin enables). */
    public void activate() { active = true; }
    /** Disable banner placement; already-placed banners are unaffected and still clean up. */
    public void deactivate() { active = false; }

    private void loadBedConfig() {
        // Optional dev-only tuning file; not shipped/created for users — baked defaults are used
        // unless a banner-config.yml is manually placed in the data folder.
        File configFile = new File(plugin.getDataFolder(), "banner-config.yml");
        if (!configFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        bedOffsetTowardHead = (float) cfg.getDouble("bed-banner.offset-toward-head", bedOffsetTowardHead);
        bedYOffset = (float) cfg.getDouble("bed-banner.y-offset", bedYOffset);
        bedScaleX = (float) cfg.getDouble("bed-banner.scale-x", bedScaleX);
        bedScaleY = (float) cfg.getDouble("bed-banner.scale-y", bedScaleY);
        bedScaleZ = (float) cfg.getDouble("bed-banner.scale-z", bedScaleZ);
        flagDepth = (float) cfg.getDouble("flag-banner.depth", flagDepth);
        flagFaceGapNormal = (float) cfg.getDouble("flag-banner.face-gap.normal", flagFaceGapNormal);
        flagFaceGapLarge = (float) cfg.getDouble("flag-banner.face-gap.large", flagFaceGapLarge);
        flagFaceGapHuge = (float) cfg.getDouble("flag-banner.face-gap.huge", flagFaceGapHuge);
        flagOutwardOffsetNormal = (float) cfg.getDouble("flag-banner.outward-offset.normal", flagOutwardOffsetNormal);
        flagOutwardOffsetLarge = (float) cfg.getDouble("flag-banner.outward-offset.large", flagOutwardOffsetLarge);
        flagOutwardOffsetHuge = (float) cfg.getDouble("flag-banner.outward-offset.huge", flagOutwardOffsetHuge);
        flagSplayTilt = (float) cfg.getDouble("flag-banner.splay-tilt", flagSplayTilt);
        flagUnifiedTilt = (float) cfg.getDouble("flag-banner.unified-tilt", flagUnifiedTilt);
    }

    public void reloadConfig() {
        loadBedConfig();
        Map<String, List<ItemDisplay>> flagGroups = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ItemDisplay display)) continue;
                String face = extractFace(display);
                if (face == null) continue;
                for (String tag : display.getScoreboardTags()) {
                    if (!tag.startsWith(TAG_PREFIX)) continue;
                    if (face.equals("bed")) {
                        int[] coords = parseCoords(tag);
                        if (coords == null) break;
                        Block block = world.getBlockAt(coords[0], coords[1], coords[2]);
                        Block foot = resolveBedFoot(block);
                        if (foot == null) break;
                        org.bukkit.block.data.type.Bed bedData =
                                (org.bukkit.block.data.type.Bed) foot.getBlockData();
                        display.setTransformation(bedBannerTransform(bedData.getFacing()));
                    } else if (face.startsWith("rot")) {
                        flagGroups.computeIfAbsent(tag, k -> new ArrayList<>()).add(display);
                    }
                    break;
                }
            }
        }
        for (var entry : flagGroups.entrySet()) {
            List<ItemDisplay> pair = entry.getValue();
            if (pair.isEmpty()) continue;
            String suffix = extractFace(pair.get(0));
            int step = Integer.parseInt(suffix.substring(3));
            float yaw = stepToYaw(step);
            float scale = pair.get(0).getTransformation().getScale().x();
            pair.get(0).setTransformation(flagTransform(yaw, scale));
            if (pair.size() >= 2) pair.get(1).setTransformation(flagBackTransform(yaw, scale));
        }
    }

    // ── Placement ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!active) return; // banner placement gated on the bbanners plugin
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

        boolean isHuge = LargeBannerRecipes.isHugeBanner(held);
        boolean isLarge = !isHuge && LargeBannerRecipes.isLargeBanner(held);
        boolean isFenceHost = isFlagHost(clicked.getType());
        boolean sneaking = event.getPlayer().isSneaking();

        if (sneaking && isBed(clicked.getType()) && !isLarge && !isHuge) {
            event.setCancelled(true);
            placeBedBanner(event.getPlayer(), clicked, held);
        } else if (isFenceHost && !sneaking) {
            event.setCancelled(true);
            float scale = isHuge ? HUGE_SCALE : (isLarge ? LARGE_SCALE : NORMAL_SCALE);
            placeFlag(event.getPlayer(), clicked, event.getBlockFace(), held, scale);
        } else if (isHuge || isLarge) {
            event.setCancelled(true);
            float scale = isHuge ? HUGE_SCALE : LARGE_SCALE;
            placeLargeBanner(event.getPlayer(), clicked, event.getBlockFace(), held, scale);
        }
    }

    private void placeFlag(Player player, Block block, BlockFace clickedFace, ItemStack banner, float scale) {
        float rawYaw = (player.getLocation().getYaw() + 180f) % 360f;
        if (rawYaw < 0) rawYaw += 360f;
        int step = yawToStep(rawYaw);
        float yaw = stepToYaw(step);

        String tag = TAG_PREFIX + blockKey(block) + ":rot" + step;

        if (!DisplayUtil.findByTag(block.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            player.sendMessage(Component.text("A banner is already on this side", NamedTextColor.RED));
            return;
        }

        ItemStack displayBanner = banner.asQuantity(1);

        spawnPair(block.getLocation(), flagTransform(yaw, scale), flagBackTransform(yaw, scale),
                displayBanner, tag);

        consumeItem(player, banner);
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_WOOL_PLACE, 1f, 1f);
    }

    private void placeLargeBanner(Player player, Block clicked, BlockFace clickedFace, ItemStack banner, float scale) {
        BlockFace face;
        Transformation transform;

        if (clickedFace == BlockFace.UP || clickedFace == BlockFace.DOWN) {
            float yaw = stepToYaw(yawToStep(player.getLocation().getYaw()));
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
        String prefix = TAG_PREFIX + blockKey(clicked) + ":";
        List<Display> entities = DisplayUtil.findByTag(clicked.getLocation(), prefix, SEARCH_RADIUS);
        if (entities.isEmpty()) return false;

        Set<String> droppedFaces = new HashSet<>();
        for (Display entity : entities) {
            if (entity instanceof ItemDisplay itemDisplay) {
                String face = extractFace(entity);
                if (face != null && droppedFaces.add(face)) {
                    ItemStack bannerItem = itemDisplay.getItemStack();
                    // Shears always recover the banner item, even in creative.
                    if (bannerItem != null) {
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
        String prefix = TAG_PREFIX + blockKey(block) + ":";
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

    // ── Mechanism riding ─────────────────────────────────────────────────
    // Banners hosted on a block a mechanism captures ride the moving structure and re-attach on
    // landing (see MechanismRegistry.assembleCore / BasicMechanism.disassemble). None of this is
    // gated on `active`: banners placed while bbanners was installed must keep riding after it is
    // removed, mirroring the cleanup handlers above.

    /** Result of {@link #captureForMechanism}: the snapshots plus the live world displays they came
     *  from (removed by the caller once the host blocks are aired out — NOT here, so an assembly
     *  failure before air-out leaves the world banners untouched). */
    record CapturedBanners(List<BannerAttachment> attachments, List<Display> worldDisplays) {}

    /**
     * Snapshot every BetterBanners display hosted on {@code host} (tag-keyed to its coords). Returns
     * null when the block hosts none. Does not remove or mutate the world displays.
     *
     * <p>Known edge: {@code findByTag} only sees loaded chunks, so a host on a loaded-chunk edge
     * whose large-banner display sits in an adjacent unloaded chunk is captured banner-less (the
     * stale display is then swept on that chunk's next load). Rare — the entity-load radius normally
     * covers neighbors.
     */
    @org.jspecify.annotations.Nullable CapturedBanners captureForMechanism(Block host) {
        String prefix = TAG_PREFIX + blockKey(host) + ":";
        List<Display> displays = DisplayUtil.findByTag(host.getLocation(), prefix, SEARCH_RADIUS);
        if (displays.isEmpty()) return null;

        List<BannerAttachment> attachments = new ArrayList<>();
        List<Display> world = new ArrayList<>();
        for (Display display : displays) {
            if (!(display instanceof ItemDisplay itemDisplay)) continue;
            String face = extractFace(itemDisplay);
            ItemStack item = itemDisplay.getItemStack();
            if (face == null || item == null || item.getType().isAir()) continue;
            attachments.add(new BannerAttachment(item.clone(), face,
                    itemDisplay.getTransformation(), faceAnchor(face)));
            world.add(itemDisplay);
        }
        return attachments.isEmpty() ? null : new CapturedBanners(attachments, world);
    }

    /** Host-block-local center of the cell a faceKey's display anchors at (large/huge banners spawn
     *  in the neighbor cell toward their face; flags and bed banners anchor at the host itself). */
    private static Vector3f faceAnchor(String faceKey) {
        return switch (faceKey) {
            case "north" -> new Vector3f(0, 0, -1);
            case "south" -> new Vector3f(0, 0, 1);
            case "east" -> new Vector3f(1, 0, 0);
            case "west" -> new Vector3f(-1, 0, 0);
            case "up" -> new Vector3f(0, 1, 0);
            case "down" -> new Vector3f(0, -1, 0);
            default -> new Vector3f(); // rot{N} / bed / block
        };
    }

    /**
     * Re-attach captured banners to their landed host, rotated by {@code snappedYaw} — a 90°
     * multiple about Y (the caller has already routed non-Y landings to an item drop). Per face:
     * a tag conflict (something already on that side) or a failed spawn drops the item instead —
     * never silently lost. {@code BLOCK_FACE_KEY} entries are skipped (restored via block placement).
     */
    void placeLandedBanners(Block host, List<BannerAttachment> attachments, float snappedYaw) {
        int rotSteps = Math.floorMod(Math.round(snappedYaw / 90f), 4);
        Set<String> handledFaces = new HashSet<>();
        for (BannerAttachment att : attachments) {
            if (att.isBlockBanner() || !handledFaces.add(att.faceKey())) continue;
            ItemStack item = att.item().asQuantity(1);
            float scale = LargeBannerRecipes.isHugeBanner(item) ? HUGE_SCALE
                    : LargeBannerRecipes.isLargeBanner(item) ? LARGE_SCALE : NORMAL_SCALE;
            String face = att.faceKey();

            if (face.startsWith("rot")) {
                int step;
                try {
                    step = Integer.parseInt(face.substring(3));
                } catch (NumberFormatException e) {
                    dropLanded(host, item);
                    continue;
                }
                int newStep = Math.floorMod(step + 4 * rotSteps, 16);
                float yaw = stepToYaw(newStep);
                String tag = TAG_PREFIX + blockKey(host) + ":rot" + newStep;
                spawnLandedPair(host, host.getLocation(),
                        flagTransform(yaw, scale), flagBackTransform(yaw, scale), item, tag);
            } else if (face.equals("up") || face.equals("down")) {
                BlockFace clickedFace = face.equals("up") ? BlockFace.UP : BlockFace.DOWN;
                // standingTransform's left rotation is a pure Y quaternion rotateY(toRadians(-yaw)):
                // recover yaw as -2·atan2(y,w), add the landing turn, and SNAP to the 22.5° grid so
                // float round-trip error can't drift across repeated ride cycles.
                Quaternionf q = att.transformation().getLeftRotation();
                float yaw = -(float) Math.toDegrees(2.0 * Math.atan2(q.y(), q.w()));
                float newYaw = stepToYaw(yawToStep(yaw + snappedYaw));
                String tag = TAG_PREFIX + blockKey(host) + ":" + face;
                spawnLandedSingle(host, host.getRelative(clickedFace).getLocation(),
                        standingTransform(newYaw, scale, clickedFace), item, tag);
            } else if (face.equals("bed")) {
                if (host.getBlockData() instanceof org.bukkit.block.data.type.Bed bedData
                        && bedData.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT) {
                    String tag = TAG_PREFIX + blockKey(host) + ":bed";
                    spawnLandedSingle(host, host.getLocation(),
                            bedBannerTransform(bedData.getFacing()), item, tag);
                } else {
                    dropLanded(host, item); // landed host is no longer a bed foot
                }
            } else {
                BlockFace oldFace = faceFromKey(face);
                if (oldFace == null) {
                    dropLanded(host, item);
                    continue;
                }
                BlockFace newFace = BlockRotation.rotateBlockFace(oldFace, snappedYaw);
                String tag = TAG_PREFIX + blockKey(host) + ":" + newFace.name().toLowerCase();
                // Occupied neighbor cell is fine — displays overlap blocks, matching placement.
                spawnLandedSingle(host, host.getRelative(newFace).getLocation(),
                        wallTransform(newFace, scale), item, tag);
            }
        }
    }

    private static @org.jspecify.annotations.Nullable BlockFace faceFromKey(String faceKey) {
        return switch (faceKey) {
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> null;
        };
    }

    private void spawnLandedSingle(Block host, Location anchorLoc, Transformation t,
                                   ItemStack item, String tag) {
        if (!DisplayUtil.findByTag(host.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            dropLanded(host, item); // side already taken (e.g. a player placed one mid-flight)
            return;
        }
        ItemDisplay d = DisplayUtil.spawn(anchorLoc, item, t, tag);
        if (!d.isValid()) {
            // Landing triggered from a chunk-unload disassemble: an entity spawned after the
            // entity-save point can silently miss the save. Drop the item instead of losing it.
            d.remove();
            dropLanded(host, item);
        }
    }

    private void spawnLandedPair(Block host, Location anchorLoc, Transformation front,
                                 Transformation back, ItemStack item, String tag) {
        if (!DisplayUtil.findByTag(host.getLocation(), tag, SEARCH_RADIUS).isEmpty()) {
            dropLanded(host, item);
            return;
        }
        ItemDisplay f = DisplayUtil.spawn(anchorLoc, item, front, tag);
        ItemDisplay b = DisplayUtil.spawn(anchorLoc, item, back, tag);
        if (!f.isValid() || !b.isValid()) {
            f.remove();
            b.remove();
            dropLanded(host, item);
        }
    }

    private static void dropLanded(Block host, ItemStack item) {
        host.getWorld().dropItemNaturally(
                host.getLocation().add(0.5, 0.5, 0.5), item.asQuantity(1));
    }

    /** Drop any banners hosted on {@code block} as items and remove their displays — for movers that
     *  air out a block WITHOUT capturing it (e.g. the chain hoist swallowing rope links). */
    void dropBannersAt(Block block) {
        handleRemoval(block, true);
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
                Material hostType = block.getType();
                // Bed banners are hosted on a (non-flag-host) bed; keep them regardless of the
                // version-dependent isSolid() value for beds.
                if (!isFlagHost(hostType) && !isBed(hostType) && !hostType.isSolid()) {
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

    private Transformation flagTransform(float yaw, float scale) {
        return new Transformation(
                flagTranslation(yaw, scale, -1),
                flagRotation(yaw),
                new Vector3f(scale, scale, scale * flagDepth),
                new Quaternionf());
    }

    private Transformation flagBackTransform(float yaw, float scale) {
        return new Transformation(
                flagTranslation(yaw, scale, +1),
                flagBackRotation(yaw),
                new Vector3f(scale, scale, scale * flagDepth),
                new Quaternionf());
    }

    private Quaternionf flagRotation(float yaw) {
        float yawRad = (float) Math.toRadians(-yaw + flagUnifiedTilt + flagSplayTilt);
        return new Quaternionf()
                .rotateY(yawRad)
                .rotateZ(-HALF_PI)
                .rotateX(HALF_PI);
    }

    private Quaternionf flagBackRotation(float yaw) {
        float yawRad = (float) Math.toRadians(-yaw + flagUnifiedTilt - flagSplayTilt);
        return new Quaternionf()
                .rotateY(yawRad)
                .rotateZ(HALF_PI)
                .rotateX(HALF_PI);
    }

    private float flagFaceGap(float scale) {
        if (scale >= HUGE_SCALE) return flagFaceGapHuge;
        if (scale >= LARGE_SCALE) return flagFaceGapLarge;
        return flagFaceGapNormal;
    }

    private float flagOutwardOffset(float scale) {
        if (scale >= HUGE_SCALE) return flagOutwardOffsetHuge;
        if (scale >= LARGE_SCALE) return flagOutwardOffsetLarge;
        return flagOutwardOffsetNormal;
    }

    private Vector3f flagTranslation(float yaw, float scale, int thicknessSign) {
        float yawRad = (float) Math.toRadians(yaw);
        float dirX = -(float) Math.sin(yawRad);
        float dirZ = (float) Math.cos(yawRad);
        float outward = 0.5f * scale + flagOutwardOffset(scale);
        float gap = flagFaceGap(scale);
        float tx = dirX * outward + thicknessSign * dirZ * gap;
        float tz = dirZ * outward - thicknessSign * dirX * gap;
        return new Vector3f(tx, 0, tz);
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
        // Suffix matching against the runtime Material name so new copper variants
        // (chains/bars) are picked up without a Bukkit API bump.
        return (name.endsWith("_FENCE") && !name.endsWith("_FENCE_GATE")) // all fences
                || name.endsWith("_WALL")        // all walls (stone/mud brick, deepslate, tuff, blackstone, resin, …)
                || name.endsWith("_BARS")        // IRON_BARS + all copper bar variants
                || name.endsWith("_CHAIN")       // all copper chain variants
                || name.endsWith("GLASS_PANE")   // plain + all stained glass panes
                || mat == Material.CHAIN         // iron chain (no "_CHAIN" suffix)
                || mat == Material.LIGHTNING_ROD
                || mat == Material.END_ROD;
    }

    private static int yawToStep(float yaw) {
        yaw = ((yaw % 360) + 360) % 360;
        return Math.round(yaw / 22.5f) % 16;
    }

    private static float stepToYaw(int step) {
        return ((step % 16 + 16) % 16) * 22.5f;
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
