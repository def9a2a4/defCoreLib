package anon.def9a2a4.corelib;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.block.TileStateInventoryHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Nameable;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Built-in {@link BlockSnapshotProvider} covering the common vanilla block-entities whose state defCoreLib
 * would otherwise blank when a mechanism moves them: signs, player-head profiles, custom names, lectern
 * books, jukebox discs, brushable-block loot, decorated-pot sherds, chiseled-bookshelf/shelf contents,
 * command blocks, and mob spawners. Registered once at plugin enable. {@link Container} inventories and
 * banners are handled by the core assembly path already, so they're intentionally NOT duplicated here
 * (custom NAME still is, via Nameable).
 *
 * <p><b>Inventory ownership.</b> {@code airOutSourceBlocks}' pass 0 empties every
 * {@link TileStateInventoryHolder} before removing it, so EVERY such block must be captured by somebody or
 * its contents are destroyed. The split: a {@code Container} is the assembly path's (it owns the typed
 * {@code storage} inventory); lecterns/jukeboxes/decorated pots own their slot-0 keys below; and the
 * catch-all {@code bs_tsih_items} branch covers every remaining non-Container holder — chiseled bookshelves
 * and (1.21.9+) shelves today. Keep that pairing intact when adding a branch here.
 *
 * <p>All keys are namespaced ({@code bs_*}) and all values YAML-safe (String / boxed / List / base64), so
 * the map round-trips through the mechanism's persisted state. Every {@code apply} branch re-checks the
 * live block type, so a snapshot applied to a mismatched block is a harmless no-op.
 *
 * <p>Not (yet) captured — documented limitations: beehive occupants (bee entity NBT; the honey_level is
 * BlockData and survives regardless), spawner potential-entity lists / custom spawn NBT (common timing
 * fields ARE captured), and banner patterns on a non-block banner (core banners handle real banners).
 */
final class DefaultBlockSnapshotProvider implements BlockSnapshotProvider {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    @Override
    public void capture(Block block, Map<String, Object> into) {
        BlockState state = block.getState();

        if (state instanceof Sign sign) {
            captureSignSide(into, "front", sign.getSide(Side.FRONT));
            captureSignSide(into, "back", sign.getSide(Side.BACK));
            into.put("bs_sign_waxed", sign.isWaxed());
        }

        if (state instanceof Skull skull) {
            PlayerProfile profile = skull.getPlayerProfile();
            if (profile != null) {
                for (ProfileProperty p : profile.getProperties()) {
                    if (p.getName().equals("textures")) {
                        into.put("bs_skull_tex", p.getValue());
                        if (p.getSignature() != null) into.put("bs_skull_sig", p.getSignature());
                        break;
                    }
                }
                if (profile.getId() != null) into.put("bs_skull_id", profile.getId().toString());
                if (profile.getName() != null) into.put("bs_skull_name", profile.getName());
            }
        }

        // Custom name (named chest/barrel/anvil-output tile, etc.). A separate branch from the type-specific
        // ones so it fires for a named block that has no other captured decoration.
        if (state instanceof Nameable nameable) {
            Component name = nameable.customName();
            if (name != null) into.put("bs_name", GSON.serialize(name));
        }

        if (state instanceof Lectern lectern) {
            ItemStack book = lectern.getInventory().getItem(0);
            if (book != null && !book.getType().isAir()) into.put("bs_lectern_book", encodeItem(book));
            into.put("bs_lectern_page", lectern.getPage());
        }

        if (state instanceof Jukebox jukebox) {
            ItemStack record = jukebox.getRecord();
            if (record != null && !record.getType().isAir()) into.put("bs_jukebox_disc", encodeItem(record));
        }

        if (state instanceof BrushableBlock brushable) {
            ItemStack item = brushable.getItem();
            if (item != null && !item.getType().isAir()) into.put("bs_brushable_item", encodeItem(item));
        }

        if (state instanceof DecoratedPot pot) {
            List<String> sherds = new ArrayList<>();
            for (Map.Entry<DecoratedPot.Side, org.bukkit.Material> e : pot.getSherds().entrySet()) {
                sherds.add(e.getKey().name() + "=" + e.getValue().name());
            }
            if (!sherds.isEmpty()) into.put("bs_pot_sherds", sherds);
            ItemStack potItem = pot.getInventory().getItem(0);
            if (potItem != null && !potItem.getType().isAir()) into.put("bs_pot_item", encodeItem(potItem));
        }

        // Every remaining non-Container TileStateInventoryHolder: chiseled bookshelves and (1.21.9+) shelves.
        // Keyed on the INTERFACE, not the concrete types — org.bukkit.block.Shelf doesn't exist in the
        // 1.21.8 compile target, and this is the same predicate airOutSourceBlocks' pass 0 clears by, so
        // "cleared" and "captured" can't drift apart again for a type Mojang adds later. The exclusions are
        // the holders another branch already owns (see the class doc); without them those would be captured
        // twice and restored twice — harmless for a lectern, but a second write to a jukebox is not.
        if (state instanceof TileStateInventoryHolder tsih
                && !(state instanceof Container)
                && !(state instanceof Lectern)
                && !(state instanceof Jukebox)
                && !(state instanceof DecoratedPot)) {
            // Snapshot inventory, not live: these blocks have no GUI, so unlike the Container capture in
            // MechanismRegistry there are no viewers to sever and a detached copy is the safer read.
            Inventory inv = tsih.getSnapshotInventory();
            List<String> items = new ArrayList<>();
            for (int s = 0; s < inv.getSize(); s++) {
                ItemStack item = inv.getItem(s);
                // Sparse "slot=base64": empty slots cost nothing and a size change across versions can't
                // silently shift every item along by one.
                if (item != null && !item.getType().isAir()) items.add(s + "=" + encodeItem(item));
            }
            if (!items.isEmpty()) into.put("bs_tsih_items", items);
        }

        // A comparator reading a chiseled bookshelf outputs its LAST INTERACTED slot, so this is redstone
        // state, not decoration — a moved bookshelf that loses it silently changes the signal it emits.
        // (-1 = never interacted, the block's own default — nothing to carry, and not worth round-tripping
        // a value through setLastInteractedSlot that only exists as an "unset" sentinel.)
        if (state instanceof ChiseledBookshelf bookshelf && bookshelf.getLastInteractedSlot() >= 0) {
            into.put("bs_bookshelf_last_slot", bookshelf.getLastInteractedSlot());
        }

        if (state instanceof CommandBlock cmd) {
            into.put("bs_cmd", cmd.getCommand());
            Component cmdName = cmd.name();
            if (cmdName != null) into.put("bs_cmd_name", GSON.serialize(cmdName));
        }

        if (state instanceof CreatureSpawner sp) {
            EntityType t = sp.getSpawnedType();
            if (t != null) into.put("bs_spawner_type", t.name());
            into.put("bs_spawner_delay", sp.getDelay());
            into.put("bs_spawner_min", sp.getMinSpawnDelay());
            into.put("bs_spawner_max", sp.getMaxSpawnDelay());
            into.put("bs_spawner_count", sp.getSpawnCount());
            into.put("bs_spawner_nearby", sp.getMaxNearbyEntities());
            into.put("bs_spawner_range", sp.getRequiredPlayerRange());
            into.put("bs_spawner_spawnrange", sp.getSpawnRange());
        }
    }

    @Override
    public void apply(Block block, Map<String, Object> from) {
        // Sign
        if (block.getState() instanceof Sign sign) {
            applySignSide(from, "front", sign.getSide(Side.FRONT));
            applySignSide(from, "back", sign.getSide(Side.BACK));
            Object waxed = from.get("bs_sign_waxed");
            if (waxed instanceof Boolean b) sign.setWaxed(b);
            sign.update(true, false);
        }

        // Skull profile — apply when ANY identity field was captured (id, name, OR textures). A name/UUID-only
        // head (unresolved skin, no embedded textures blob) still restores: Paper re-resolves the skin from
        // the profile on render, like vanilla player_head[profile={name:"…"}]. Gating solely on textures
        // dropped those heads to a default Steve head.
        String tex = str(from.get("bs_skull_tex"));
        String skullId = str(from.get("bs_skull_id"));
        String skullName = str(from.get("bs_skull_name"));
        if ((tex != null || skullId != null || skullName != null) && block.getState() instanceof Skull skull) {
            PlayerProfile profile;
            if (skullId != null) {
                profile = Bukkit.createProfile(UUID.fromString(skullId), skullName);
            } else if (skullName != null) {
                profile = Bukkit.createProfile(skullName); // Paper owns the UUID + name→skin resolution
            } else {
                // Custom-texture head with no identity: derive a STABLE UUID from the texture so the head
                // keeps one profile across move cycles (lets identical heads stack; avoids a fresh random
                // UUID each disassemble that would provoke Mojang lookups).
                profile = Bukkit.createProfile(
                    UUID.nameUUIDFromBytes(tex.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
            }
            if (tex != null) {
                profile.setProperty(new ProfileProperty("textures", tex, str(from.get("bs_skull_sig"))));
            }
            skull.setPlayerProfile(profile);
            skull.update(true, false);
        }

        // Custom name (Nameable is implemented by the block-entity BlockState subtypes)
        String name = str(from.get("bs_name"));
        if (name != null) {
            BlockState nbs = block.getState();
            if (nbs instanceof Nameable nameable) {
                nameable.customName(GSON.deserialize(name));
                nbs.update(true, false);
            }
        }

        // Lectern book + page
        if (block.getState() instanceof Lectern lectern) {
            String book = str(from.get("bs_lectern_book"));
            if (book != null) lectern.getInventory().setItem(0, decodeItem(book));
            Object page = from.get("bs_lectern_page");
            if (page instanceof Number pg) lectern.setPage(pg.intValue());
            lectern.update(true, false);
        }

        // Jukebox disc
        String disc = str(from.get("bs_jukebox_disc"));
        if (disc != null && block.getState() instanceof Jukebox jukebox) {
            jukebox.setRecord(decodeItem(disc));
            jukebox.update(true, false);
        }

        // Brushable loot
        String brush = str(from.get("bs_brushable_item"));
        if (brush != null && block.getState() instanceof BrushableBlock brushable) {
            brushable.setItem(decodeItem(brush));
            brushable.update(true, false);
        }

        // Decorated pot
        if (block.getState() instanceof DecoratedPot pot) {
            Object sherds = from.get("bs_pot_sherds");
            if (sherds instanceof List<?> list) {
                for (Object o : list) {
                    String[] kv = String.valueOf(o).split("=", 2);
                    if (kv.length != 2) continue;
                    try {
                        pot.setSherd(DecoratedPot.Side.valueOf(kv[0]), org.bukkit.Material.valueOf(kv[1]));
                    } catch (IllegalArgumentException ignored) { /* unknown side/material across versions */ }
                }
            }
            String potItem = str(from.get("bs_pot_item"));
            if (potItem != null) pot.getInventory().setItem(0, decodeItem(potItem));
            pot.update(true, false);
        }

        // Chiseled bookshelf / shelf / any other non-Container inventory holder (see capture)
        BlockState tsihState = from.containsKey("bs_tsih_items") ? block.getState() : null;
        if (tsihState instanceof TileStateInventoryHolder tsih && !(tsihState instanceof Container)) {
            Inventory dest = tsih.getSnapshotInventory();
            List<ItemStack> overflow = new ArrayList<>();
            if (from.get("bs_tsih_items") instanceof List<?> list) {
                for (Object o : list) {
                    String[] kv = String.valueOf(o).split("=", 2);
                    if (kv.length != 2) continue;
                    int slot;
                    try {
                        slot = Integer.parseInt(kv[0]);
                    } catch (NumberFormatException ignored) { continue; }
                    ItemStack item = decodeItem(kv[1]);
                    // A slot the landed block doesn't have (the block type changed under us, or a version
                    // shrank the inventory) drops rather than being swallowed — same call the container
                    // restore's overflow tail makes in BasicMechanism.
                    if (slot >= 0 && slot < dest.getSize()) dest.setItem(slot, item);
                    else overflow.add(item);
                }
            }
            tsih.update(true, false);
            for (ItemStack extra : overflow) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), extra);
            }
        }

        // Chiseled bookshelf comparator output
        if (from.containsKey("bs_bookshelf_last_slot")
                && block.getState() instanceof ChiseledBookshelf bookshelf) {
            bookshelf.setLastInteractedSlot(intOr(from.get("bs_bookshelf_last_slot"),
                bookshelf.getLastInteractedSlot()));
            bookshelf.update(true, false);
        }

        // Command block
        String cmd = str(from.get("bs_cmd"));
        if (cmd != null && block.getState() instanceof CommandBlock cb) {
            cb.setCommand(cmd);
            String cmdName = str(from.get("bs_cmd_name"));
            if (cmdName != null) cb.name(GSON.deserialize(cmdName));
            cb.update(true, false);
        }

        // Spawner
        if (from.containsKey("bs_spawner_delay") && block.getState() instanceof CreatureSpawner sp) {
            String t = str(from.get("bs_spawner_type"));
            if (t != null) {
                try { sp.setSpawnedType(EntityType.valueOf(t)); } catch (IllegalArgumentException ignored) { }
            }
            sp.setDelay(intOr(from.get("bs_spawner_delay"), sp.getDelay()));
            sp.setMinSpawnDelay(intOr(from.get("bs_spawner_min"), sp.getMinSpawnDelay()));
            sp.setMaxSpawnDelay(intOr(from.get("bs_spawner_max"), sp.getMaxSpawnDelay()));
            sp.setSpawnCount(intOr(from.get("bs_spawner_count"), sp.getSpawnCount()));
            sp.setMaxNearbyEntities(intOr(from.get("bs_spawner_nearby"), sp.getMaxNearbyEntities()));
            sp.setRequiredPlayerRange(intOr(from.get("bs_spawner_range"), sp.getRequiredPlayerRange()));
            sp.setSpawnRange(intOr(from.get("bs_spawner_spawnrange"), sp.getSpawnRange()));
            sp.update(true, false);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void captureSignSide(Map<String, Object> into, String key, SignSide side) {
        List<String> lines = new ArrayList<>(4);
        for (Component c : side.lines()) lines.add(GSON.serialize(c));
        into.put("bs_sign_" + key, lines);
        into.put("bs_sign_" + key + "_glow", side.isGlowingText());
        DyeColor color = side.getColor();
        if (color != null) into.put("bs_sign_" + key + "_color", color.name());
    }

    private static void applySignSide(Map<String, Object> from, String key, SignSide side) {
        Object lines = from.get("bs_sign_" + key);
        if (lines instanceof List<?> list) {
            for (int i = 0; i < list.size() && i < 4; i++) {
                side.line(i, GSON.deserialize(String.valueOf(list.get(i))));
            }
        }
        Object glow = from.get("bs_sign_" + key + "_glow");
        if (glow instanceof Boolean g) side.setGlowingText(g);
        String color = str(from.get("bs_sign_" + key + "_color"));
        if (color != null) {
            try { side.setColor(DyeColor.valueOf(color)); } catch (IllegalArgumentException ignored) { }
        }
    }

    /**
     * Re-apply the captured player-head skin and/or custom name onto a DROPPED item, mirroring what
     * {@link #apply(Block, Map)} does for a landed block. Used when a mechanism can't place a block back
     * (off-world / WorldGuard-denied / solid-wins) and drops it as an item instead — without this, a
     * custom-skin vanilla head drops as a plain Steve head and a named container drops unnamed. Snapshot-
     * and type-gated: a no-op unless {@code snap} carries the relevant key and {@code item} is the matching
     * meta type. Safe to run on any drop (banner/custom-head items are untouched — their meta isn't skull/
     * nameable-with-a-captured-name, and this never runs for registry blocks whose snapshot has no bs_* keys).
     */
    static void decorateItem(ItemStack item, Map<String, Object> snap) {
        if (item == null || snap == null) return;

        // Player-head skin: same profile-build logic as apply()'s skull branch.
        String tex = str(snap.get("bs_skull_tex"));
        String skullId = str(snap.get("bs_skull_id"));
        String skullName = str(snap.get("bs_skull_name"));
        if ((tex != null || skullId != null || skullName != null)
                && item.getItemMeta() instanceof SkullMeta skullMeta) {
            PlayerProfile profile;
            if (skullId != null) {
                profile = Bukkit.createProfile(UUID.fromString(skullId), skullName);
            } else if (skullName != null) {
                profile = Bukkit.createProfile(skullName);
            } else {
                profile = Bukkit.createProfile(
                    UUID.nameUUIDFromBytes(tex.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
            }
            if (tex != null) {
                profile.setProperty(new ProfileProperty("textures", tex, str(snap.get("bs_skull_sig"))));
            }
            skullMeta.setPlayerProfile(profile);
            item.setItemMeta(skullMeta);
        }

        // Custom name (named chest/barrel/anvil-output/etc.).
        String name = str(snap.get("bs_name"));
        if (name != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(GSON.deserialize(name));
                item.setItemMeta(meta);
            }
        }
    }

    /**
     * The player items this provider captured OUT of the world, for the drop paths — the static counterpart
     * to {@link #apply(Block, Map)} the way {@link #decorateItem} is for a block's own item. When a mechanism
     * discards a block instead of placing it (destroyed / off-world / solid-wins / protected / SKIP) there is
     * no tile to restore into, and {@code airOutSourceBlocks}' pass 0 already emptied the source — so without
     * this these items exist nowhere and are simply deleted.
     *
     * <p>Covers exactly the keys whose source block pass 0 provably cleared, i.e. the
     * {@link TileStateInventoryHolder}s: lectern book, jukebox disc, decorated-pot item, and
     * {@code bs_tsih_items}. {@code bs_brushable_item} is deliberately NOT here — a {@code BrushableBlock}
     * is not an inventory holder, so pass 0 never touched it and its buried item may still be in the world
     * from the {@code setType(AIR)}; dropping it again could duplicate it.
     *
     * <p>Snapshot-gated and empty for a block that carried nothing. Provider-specific by design: a consumer's
     * own {@link BlockSnapshotProvider} owns the drop semantics of the keys it captured.
     */
    static List<ItemStack> capturedItems(Map<String, Object> snap) {
        if (snap == null) return List.of();
        List<ItemStack> out = new ArrayList<>();
        for (String key : new String[] {"bs_lectern_book", "bs_jukebox_disc", "bs_pot_item"}) {
            String encoded = str(snap.get(key));
            if (encoded != null) out.add(decodeItem(encoded));
        }
        if (snap.get("bs_tsih_items") instanceof List<?> list) {
            for (Object o : list) {
                String[] kv = String.valueOf(o).split("=", 2);
                if (kv.length == 2) out.add(decodeItem(kv[1]));
            }
        }
        return out;
    }

    private static String encodeItem(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack decodeItem(String base64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOr(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }
}
