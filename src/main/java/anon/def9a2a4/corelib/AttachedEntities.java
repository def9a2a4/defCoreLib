package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entities that rest on or hang from a mechanism's blocks and must travel with them: 26.3 cushions, item
 * frames and paintings. Without this, assembly airs the blocks out from under them and they pop off as items
 * (a seated player is left behind).
 *
 * <p>Two strategies, because the entities behave differently as passengers:
 * <ul>
 *   <li><b>Cushions ride along.</b> Mounted on their anchor block's collider shulker, the live cushion (and
 *       whoever sits on it) moves with the body — passengers are positioned client-side, so the cushion's
 *       never-sync update interval doesn't matter, and the passenger tree persists with the carrier. Its
 *       survival check still runs while riding, so {@link CarriedCushionGuard} cancels the pop.</li>
 *   <li><b>Frames and paintings are carried as data.</b> A live one can't ride — {@code BlockAttachedEntity.setPos}
 *       snaps it to its containing cell, so it would jump a block at a time. It is serialized onto its anchor
 *       block ({@link MechanismBlockData#attached}), shown in transit by a render-only banner-style stand-in,
 *       and respawned on landing (or dropped as its items).</li>
 * </ul>
 */
final class AttachedEntities {

    /** Scoreboard tag prefix on a riding cushion: {@code corelib:carried:<mechId>:<anchorIdx>:<dx>:<dy>:<dz>}
     *  (offsets in 1/1000 block, relative to the anchor cell's bottom center). Deliberately NOT a
     *  {@code corelib:mech:} tag, so recovery's role parsing and the orphan sweep never touch it. */
    static final String CARRIED_TAG = "corelib:carried:";

    /** {@link BannerAttachment#faceKey} of a render-only stand-in for a carried frame/painting. */
    static final String STAND_IN_FACE_KEY = "carried";

    static final String KIND_FRAME = "item_frame";
    static final String KIND_PAINTING = "painting";

    /** Depth of a frame/painting's center in front of its anchor face: half a block minus half its 1/16 thickness. */
    private static final double HANG_DEPTH = 0.5 - 1.0 / 32;

    private AttachedEntities() {}

    // ──────────────────────────────────────────────────────────────────────
    // Carried frame/painting data
    // ──────────────────────────────────────────────────────────────────────

    /**
     * A frame or painting carried as data on its anchor block. Pure data (no live Bukkit objects), so it
     * persists into the mechanism state file. {@code ox/oy/oz} is the entity's center relative to the anchor
     * cell's CENTER, in the mechanism's assembly frame; {@code fx/fy/fz} its facing (outward normal).
     * {@code drops} is the serialized fallback item list, captured up front so a later deserialize failure
     * (e.g. a newer DataVersion after a downgrade) can still pay the player.
     */
    record CarriedEntity(String kind, UUID uuid, byte[] nbt, byte @Nullable [] drops,
                         double ox, double oy, double oz, int fx, int fy, int fz,
                         int rotation, boolean fixed, int artW, int artH) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("uuid", uuid.toString());
            m.put("nbt", Base64.getEncoder().encodeToString(nbt));
            if (drops != null) m.put("drops", Base64.getEncoder().encodeToString(drops));
            m.put("off", List.of(ox, oy, oz));
            m.put("face", List.of(fx, fy, fz));
            if (rotation != 0) m.put("rot", rotation);
            if (fixed) m.put("fixed", true);
            if (artW > 0) m.put("art", List.of(artW, artH));
            return m;
        }

        /** Inverse of {@link #toMap}; throws on any malformed field (the caller keeps the raw map verbatim). */
        static CarriedEntity fromMap(Map<String, Object> m) {
            String kind = String.valueOf(m.get("kind"));
            if (!KIND_FRAME.equals(kind) && !KIND_PAINTING.equals(kind)) {
                throw new IllegalArgumentException("unknown carried kind " + kind);
            }
            UUID uuid = UUID.fromString(String.valueOf(m.get("uuid")));
            byte[] nbt = Base64.getDecoder().decode(String.valueOf(m.get("nbt")));
            Object d = m.get("drops");
            byte[] drops = d != null ? Base64.getDecoder().decode(String.valueOf(d)) : null;
            List<?> off = (List<?>) m.get("off");
            List<?> face = (List<?>) m.get("face");
            int rot = m.get("rot") instanceof Number n ? n.intValue() : 0;
            boolean fixed = Boolean.TRUE.equals(m.get("fixed"));
            int w = 0, h = 0;
            if (m.get("art") instanceof List<?> art && art.size() >= 2) {
                w = ((Number) art.get(0)).intValue();
                h = ((Number) art.get(1)).intValue();
            }
            return new CarriedEntity(kind, uuid, nbt, drops,
                ((Number) off.get(0)).doubleValue(), ((Number) off.get(1)).doubleValue(),
                ((Number) off.get(2)).doubleValue(),
                ((Number) face.get(0)).intValue(), ((Number) face.get(1)).intValue(),
                ((Number) face.get(2)).intValue(), rot, fixed, w, h);
        }

        /** The fallback items, or an empty list when there are none or they can't be decoded here. */
        List<ItemStack> fallbackItems(Logger log) {
            if (drops == null) return List.of();
            try {
                List<ItemStack> out = new ArrayList<>();
                for (ItemStack it : ItemStack.deserializeItemsFromBytes(drops)) {
                    if (it != null && !it.getType().isAir()) out.add(it);
                }
                return out;
            } catch (Throwable t) {
                log.warning("Carried " + kind + " " + uuid + ": its fallback items can't be decoded on this"
                    + " server (" + t.getMessage() + "); nothing dropped");
                return List.of();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Capture (assembly)
    // ──────────────────────────────────────────────────────────────────────

    /** A cushion found resting on a structure block, to be mounted once the colliders exist. */
    record CushionRef(Entity cushion, int anchorIdx, int belowIdx, double dx, double dy, double dz) {}

    /** Everything found on a structure at assembly. Mutated only on the main thread during one assembly. */
    static final class Capture {
        static final Capture EMPTY = new Capture();

        final Map<Integer, List<CarriedEntity>> attached = new HashMap<>();
        final Map<Integer, List<BannerAttachment>> standIns = new HashMap<>();
        /** The live frames/paintings captured above — removed only once the blocks are aired out. */
        final List<Entity> originals = new ArrayList<>();
        final List<CushionRef> cushions = new ArrayList<>();

        boolean isEmpty() { return attached.isEmpty() && cushions.isEmpty(); }

        /** Remove the captured frames/paintings. Call AFTER the source blocks are aired out, so any assembly
         *  throw before that leaves the originals in the world (the captured copy dies with the mechanism). */
        void removeOriginals() {
            for (Entity e : originals) {
                if (e.isValid()) e.remove();
            }
            originals.clear();
        }

        /**
         * Mount each captured cushion on its anchor's collider shulker (falling back to the block below when
         * the anchor has none — carpets, trapdoors, doors, fence gates and pressure plates have no collider).
         * The rider stays seated on the cushion throughout. A cushion with no collider to ride is left where it
         * is: vanilla pops it as an item, as it always did. Call in the same tick as the air-out (no entity
         * tick in between, so the survival check can't fire first).
         */
        void mountCushions(BasicMechanism mech) {
            for (CushionRef ref : cushions) {
                Entity c = ref.cushion();
                if (!c.isValid() || c.getVehicle() != null) continue;
                org.bukkit.entity.Shulker carrier = mech.colliderShulker(ref.anchorIdx());
                if (carrier == null && ref.belowIdx() >= 0) carrier = mech.colliderShulker(ref.belowIdx());
                if (carrier == null) continue;
                String tag = carriedTag(mech.id(), ref.anchorIdx(), ref.dx(), ref.dy(), ref.dz());
                c.addScoreboardTag(tag);
                if (!carrier.addPassenger(c)) c.removeScoreboardTag(tag);
            }
            cushions.clear();
        }
    }

    /**
     * Find the cushions, frames and paintings resting on / hanging from {@code blocks}. Side-effect free
     * apart from reading the world: frames and paintings are serialized here but not removed. Block index =
     * position in {@code blocks}.
     */
    static Capture capture(List<Block> blocks, Logger log) {
        if (blocks.isEmpty()) return Capture.EMPTY;
        World w = blocks.get(0).getWorld();
        Map<CustomBlockRegistry.LocationKey, Integer> index = new HashMap<>(blocks.size() * 2);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            index.putIfAbsent(CustomBlockRegistry.LocationKey.of(b), i);
            minX = Math.min(minX, b.getX()); minY = Math.min(minY, b.getY()); minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX()); maxY = Math.max(maxY, b.getY()); maxZ = Math.max(maxZ, b.getZ());
        }
        // +1 around for frames on outer faces; +3 on top for a cushion above a 1.5-tall fence.
        BoundingBox region = new BoundingBox(minX - 1, minY - 1, minZ - 1, maxX + 2, maxY + 3, maxZ + 2);
        Capture cap = new Capture();
        for (Entity e : w.getNearbyEntities(region)) {
            if (!e.isValid() || e.getVehicle() != null || isMechEntity(e)) continue;
            try {
                if (CushionCompat.isCushion(e)) {
                    captureCushion(cap, e, index);
                } else if (e instanceof ItemFrame f) {
                    captureFrame(cap, f, index);
                } else if (e instanceof Painting p) {
                    capturePainting(cap, p, index);
                }
            } catch (RuntimeException ex) {
                // Leave this entity alone (vanilla pops it as an item) rather than abort the assembly.
                log.log(Level.WARNING, "Mechanism assembly: couldn't capture " + e.getType() + " at "
                    + e.getLocation().toVector() + "; leaving it in place", ex);
            }
        }
        return cap;
    }

    private static boolean isMechEntity(Entity e) {
        for (String t : e.getScoreboardTags()) {
            if (t.startsWith("corelib:mech:") || t.startsWith(CARRIED_TAG)) return true;
        }
        return false;
    }

    private static void captureCushion(Capture cap, Entity c, Map<CustomBlockRegistry.LocationKey, Integer> index) {
        Block anchor = cushionAnchor(c);
        if (anchor == null) return;
        Integer ai = index.get(CustomBlockRegistry.LocationKey.of(anchor));
        if (ai == null) return;
        Integer below = index.get(CustomBlockRegistry.LocationKey.of(anchor.getRelative(BlockFace.DOWN)));
        Location l = c.getLocation();
        cap.cushions.add(new CushionRef(c, ai, below != null ? below : -1,
            l.getX() - (anchor.getX() + 0.5), l.getY() - anchor.getY(), l.getZ() - (anchor.getZ() + 0.5)));
    }

    private static void captureFrame(Capture cap, ItemFrame f, Map<CustomBlockRegistry.LocationKey, Integer> index) {
        Block anchor = f.getLocation().getBlock().getRelative(f.getAttachedFace());
        Integer ai = index.get(CustomBlockRegistry.LocationKey.of(anchor));
        if (ai == null) return;
        byte[] nbt = Bukkit.getUnsafe().serializeEntity(f); // before any remove(): throws on a dead entity
        ItemStack held = f.getItem();
        boolean hasItem = held != null && !held.getType().isAir();
        List<ItemStack> fallback = new ArrayList<>(2);
        // A fixed frame is a command-placed decoration that never drops itself in vanilla — pay only its item.
        if (!f.isFixed()) fallback.add(new ItemStack(f instanceof GlowItemFrame ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME));
        if (hasItem) fallback.add(held.clone());
        BlockFace facing = f.getFacing();
        Location l = f.getLocation();
        CarriedEntity ce = new CarriedEntity(KIND_FRAME, f.getUniqueId(), nbt, serializeDrops(fallback),
            l.getX() - (anchor.getX() + 0.5), l.getY() - (anchor.getY() + 0.5), l.getZ() - (anchor.getZ() + 0.5),
            facing.getModX(), facing.getModY(), facing.getModZ(), f.getRotation().ordinal(), f.isFixed(), 0, 0);
        add(cap, ai, ce, f, standIn(ce, hasItem ? held.clone()
            : new ItemStack(f instanceof GlowItemFrame ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME), hasItem));
    }

    private static void capturePainting(Capture cap, Painting p, Map<CustomBlockRegistry.LocationKey, Integer> index) {
        List<Block> supports = paintingSupports(p);
        if (supports.isEmpty()) return;
        Integer ref = null;
        Block refBlock = null;
        for (Block s : supports) {
            Integer si = index.get(CustomBlockRegistry.LocationKey.of(s));
            if (si == null) return; // part of the wall stays behind — leave it, vanilla pops it
            if (ref == null) { ref = si; refBlock = s; }
        }
        byte[] nbt = Bukkit.getUnsafe().serializeEntity(p);
        BlockFace facing = p.getFacing();
        Location l = p.getLocation();
        int w = p.getArt().getBlockWidth(), h = p.getArt().getBlockHeight();
        CarriedEntity ce = new CarriedEntity(KIND_PAINTING, p.getUniqueId(), nbt,
            serializeDrops(List.of(new ItemStack(Material.PAINTING))),
            l.getX() - (refBlock.getX() + 0.5), l.getY() - (refBlock.getY() + 0.5), l.getZ() - (refBlock.getZ() + 0.5),
            facing.getModX(), facing.getModY(), facing.getModZ(), 0, false, w, h);
        add(cap, ref, ce, p, standIn(ce, new ItemStack(Material.PAINTING), false));
    }

    private static void add(Capture cap, int idx, CarriedEntity ce, Entity original, BannerAttachment standIn) {
        cap.attached.computeIfAbsent(idx, k -> new ArrayList<>()).add(ce);
        cap.standIns.computeIfAbsent(idx, k -> new ArrayList<>()).add(standIn);
        cap.originals.add(original);
    }

    private static byte @Nullable [] serializeDrops(List<ItemStack> items) {
        if (items.isEmpty()) return null;
        return ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[0]));
    }

    /**
     * The block a cushion rests on — mirrors vanilla {@code Cushion.hasAnchorBelow}: the block whose OUTLINE
     * (not collision) shape reaches the thin probe just under the cushion's feet. Checks the cell containing the
     * probe first (covers slabs/stairs, where the cushion sits inside the anchor's own cell, and cauldron-like
     * blocks), then the cell below (a fence/wall is 1.5 tall, so its cushion sits in the air cell above it).
     */
    static @Nullable Block cushionAnchor(Entity cushion) {
        BoundingBox bb = cushion.getBoundingBox();
        double top = bb.getMinY(), bottom = top - 1.0 / 64;
        World w = cushion.getWorld();
        int bx = (int) Math.floor(bb.getCenterX()), bz = (int) Math.floor(bb.getCenterZ());
        int y0 = (int) Math.floor(top - 1e-7);
        for (int y = y0; y >= y0 - 1; y--) {
            Block b = w.getBlockAt(bx, y, bz);
            if (b.isEmpty()) continue;
            BoundingBox ob = b.getBoundingBox();
            if (ob.getWidthX() <= 0 && ob.getHeight() <= 0) continue; // no outline at all
            if (ob.getMaxY() >= bottom - 1e-6 && ob.getMinY() <= top) return b;
        }
        return null;
    }

    /** The wall cells a painting hangs from: its bounding box pushed half a block into the wall. */
    static List<Block> paintingSupports(Painting p) {
        BlockFace back = p.getAttachedFace();
        BoundingBox bb = p.getBoundingBox().clone()
            .shift(back.getModX() * 0.5, back.getModY() * 0.5, back.getModZ() * 0.5)
            .expand(-1e-3);
        List<Block> out = new ArrayList<>();
        World w = p.getWorld();
        for (int x = (int) Math.floor(bb.getMinX()); x <= (int) Math.floor(bb.getMaxX()); x++) {
            for (int y = (int) Math.floor(bb.getMinY()); y <= (int) Math.floor(bb.getMaxY()); y++) {
                for (int z = (int) Math.floor(bb.getMinZ()); z <= (int) Math.floor(bb.getMaxZ()); z++) {
                    out.add(w.getBlockAt(x, y, z));
                }
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Stand-in (transit visual)
    // ──────────────────────────────────────────────────────────────────────

    /** A render-only banner attachment showing a carried frame's item (or the empty frame) / a painting. */
    private static BannerAttachment standIn(CarriedEntity ce, ItemStack shown, boolean frameItem) {
        Quaternionf face = facingRotation(ce.fx(), ce.fy(), ce.fz());
        if (KIND_FRAME.equals(ce.kind()) && frameItem) {
            face.mul(new Quaternionf().rotationZ((float) (-ce.rotation() * Math.PI / 4)));
        }
        Vector3f scale = KIND_PAINTING.equals(ce.kind())
            ? new Vector3f(Math.max(1, ce.artW()), Math.max(1, ce.artH()), 1f)
            : new Vector3f(0.5f, 0.5f, 0.5f);
        Transformation t = new Transformation(
            new Vector3f((float) ce.ox(), (float) ce.oy(), (float) ce.oz()),
            face, scale, new Quaternionf());
        return new BannerAttachment(shown, STAND_IN_FACE_KEY, t, new Vector3f());
    }

    /** Rotation taking a flat item's +Z face to the given outward normal. */
    private static Quaternionf facingRotation(int fx, int fy, int fz) {
        if (fy > 0) return new Quaternionf().rotationX((float) (-Math.PI / 2));
        if (fy < 0) return new Quaternionf().rotationX((float) (Math.PI / 2));
        return new Quaternionf().rotationY((float) Math.atan2(fx, fz));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Landing
    // ──────────────────────────────────────────────────────────────────────

    /** Where a carried frame/painting lands: the cell its entity occupies and the facing it hangs at. */
    record Pose(Block cell, BlockFace facing, Location spawnAt) {}

    /**
     * Rotate a carried entity onto its landed anchor. {@code anchorCell} is the landed anchor block;
     * {@code rotation} the mechanism's landing rotation. Returns null when the rotated facing isn't a
     * cardinal face (can't happen for 90° turns, but never trust float rounding with a spawn).
     */
    static @Nullable Pose landingPose(CarriedEntity ce, Block anchorCell, Matrix4f rotation) {
        Vector3f f = rotation.transformDirection(new Vector3f(ce.fx(), ce.fy(), ce.fz()), new Vector3f());
        BlockFace facing = faceOf(Math.round(f.x), Math.round(f.y), Math.round(f.z));
        if (facing == null) return null;
        Vector3f o = rotation.transformDirection(
            new Vector3f((float) ce.ox(), (float) ce.oy(), (float) ce.oz()), new Vector3f());
        // The entity's landed center, then back-solved to the cell vanilla calls its `pos`: center =
        // cellCenter - facing·depth (+½ toward counter-clockwise for an even width, +½ up for an even height).
        double cx = anchorCell.getX() + 0.5 + o.x + facing.getModX() * HANG_DEPTH;
        double cy = anchorCell.getY() + 0.5 + o.y + facing.getModY() * HANG_DEPTH;
        double cz = anchorCell.getZ() + 0.5 + o.z + facing.getModZ() * HANG_DEPTH;
        if (KIND_PAINTING.equals(ce.kind())) {
            if (ce.artW() % 2 == 0) {
                BlockFace ccw = counterClockwise(facing);
                if (ccw != null) { cx -= ccw.getModX() * 0.5; cz -= ccw.getModZ() * 0.5; }
            }
            if (ce.artH() % 2 == 0) cy -= 0.5;
        }
        // Nudge off any exact boundary before flooring (an even painting's center sits on one).
        Block cell = anchorCell.getWorld().getBlockAt(
            (int) Math.floor(cx + 1e-4), (int) Math.floor(cy + 1e-4), (int) Math.floor(cz + 1e-4));
        return new Pose(cell, facing, cell.getLocation().add(0.5, 0.5, 0.5));
    }

    /** Spawn outcome — SKIPPED_DUPE must never drop (the original is still in the world). */
    enum SpawnResult { SPAWNED, SKIPPED_DUPE, FAILED }

    /**
     * Respawn a carried frame/painting at {@code pose}. {@code quarterTurnsCcw} is the landing's net yaw in
     * counter-clockwise quarter turns seen from above (Y-axis landings only; 0 otherwise) — a floor/ceiling
     * frame's item is world-fixed, so its 45° rotation steps must follow the turn.
     */
    static SpawnResult spawn(CarriedEntity ce, Pose pose, int quarterTurnsCcw) {
        // Crash dupe guard: the chunk may have saved the original after the state file captured it. Same UUID
        // ⇒ the real one is back; leave it and never drop.
        if (Bukkit.getEntity(ce.uuid()) != null) return SpawnResult.SKIPPED_DUPE;
        Entity e = Bukkit.getUnsafe().deserializeEntity(ce.nbt(), pose.cell().getWorld(), true);
        if (e instanceof Hanging h) h.setFacingDirection(pose.facing(), true);
        if (e instanceof ItemFrame f && pose.facing().getModY() != 0 && quarterTurnsCcw != 0) {
            int steps = pose.facing() == BlockFace.UP ? -2 * quarterTurnsCcw : 2 * quarterTurnsCcw;
            Rotation[] all = Rotation.values();
            f.setRotation(all[Math.floorMod(f.getRotation().ordinal() + steps, all.length)]);
        }
        return e.spawnAt(pose.spawnAt()) ? SpawnResult.SPAWNED : SpawnResult.FAILED;
    }

    static void dropFallbacks(CarriedEntity ce, Location at, Logger log) {
        World w = at.getWorld();
        if (w == null) return;
        Location c = at.getBlock().getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack it : ce.fallbackItems(log)) w.dropItemNaturally(c, it);
    }

    private static @Nullable BlockFace faceOf(int x, int y, int z) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            if (f.getModX() == x && f.getModY() == y && f.getModZ() == z) return f;
        }
        return null;
    }

    private static @Nullable BlockFace counterClockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> null;
        };
    }

    // ──────────────────────────────────────────────────────────────────────
    // Carried-cushion tag
    // ──────────────────────────────────────────────────────────────────────

    static String carriedTag(UUID mechId, int anchorIdx, double dx, double dy, double dz) {
        return CARRIED_TAG + mechId + ":" + anchorIdx + ":" + Math.round(dx * 1000) + ":"
            + Math.round(dy * 1000) + ":" + Math.round(dz * 1000);
    }

    /** Parsed {@link #carriedTag}: anchor block index + offset from the anchor cell's bottom center. */
    record CarriedTag(String raw, UUID mechId, int anchorIdx, double dx, double dy, double dz) {}

    static @Nullable CarriedTag carriedTagOf(Entity e) {
        for (String t : e.getScoreboardTags()) {
            if (!t.startsWith(CARRIED_TAG)) continue;
            String[] p = t.substring(CARRIED_TAG.length()).split(":");
            if (p.length != 5) continue;
            try {
                return new CarriedTag(t, UUID.fromString(p[0]), Integer.parseInt(p[1]),
                    Long.parseLong(p[2]) / 1000.0, Long.parseLong(p[3]) / 1000.0, Long.parseLong(p[4]) / 1000.0);
            } catch (IllegalArgumentException ignored) {
                // malformed — keep looking
            }
        }
        return null;
    }
}
