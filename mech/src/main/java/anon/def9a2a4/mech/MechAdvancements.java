package anon.def9a2a4.mech;

import anon.def9a2a4.corelib.CustomBlockRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grants the {@code mech} advancement tree programmatically (every node uses the
 * {@code minecraft:impossible} criterion, so only code can award them — mirrors the backrooms
 * AdvancementManager). Granting is idempotent: {@link #grant} short-circuits on already-done
 * advancements, so redstone-driven milestones that fire repeatedly cost almost nothing after the
 * first award.
 *
 * <p>Aggregate nodes need no side storage — the advancement state itself is the store: "crafted every
 * machine" == holds every {@code craft/<machine>} advancement; "grand engineer" == holds every branch
 * capstone. Both are re-checked cheaply after each grant and on advancement-done.
 */
public final class MechAdvancements {

    private static final String NAMESPACE = "mech";
    private static final String CRITERION = "impossible";
    /** Redstone-driven milestones carry no player; grant to everyone within this radius of the event. */
    private static final double NEARBY_RADIUS = 16.0;

    private final JavaPlugin plugin;

    public MechAdvancements(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // Craftable mech item id → craft advancement node. Items without an entry still grant the root
    // (first craft of any mech item). Windmill tiers all map to the single windmill_item craft node,
    // and all 12 casing woods to the single casing craft node.
    private static final Map<String, String> CRAFT_NODE_BY_ITEM = Map.ofEntries(
            Map.entry("mech:bearing", "craft/bearing"),
            Map.entry("mech:wrench", "craft/wrench"),
            Map.entry("mech:glue_item", "craft/glue_brush"),
            Map.entry("mech:shaft", "craft/shaft"),
            Map.entry("mech:gear", "craft/gear"),
            Map.entry("mech:water_wheel", "craft/water_wheel"),
            Map.entry("mech:windmill", "craft/windmill_item"),
            Map.entry("mech:large_windmill", "craft/windmill_item"),
            Map.entry("mech:huge_windmill", "craft/windmill_item"),
            Map.entry("mech:clutch", "craft/clutch"),
            Map.entry("mech:reverser", "craft/reverser"),
            Map.entry("mech:drill", "craft/drill"),
            Map.entry("mech:millstone", "craft/millstone"),
            Map.entry("mech:fan", "craft/fan"),
            Map.entry("mech:press", "craft/press"),
            Map.entry("mech:sieve", "craft/sieve"),
            Map.entry("mech:placer", "craft/placer"),
            Map.entry("mech:redstone_motor", "craft/motor"),
            Map.entry("mech:engine", "craft/engine"),
            Map.entry("mech:chain_pulley", "craft/chain_pulley"),
            Map.entry("mech:chain_hoist", "craft/chain_hoist"),
            Map.entry("mech:suction_hopper", "craft/suction_hopper"),
            Map.entry("mech:dough", "machines/dough"),
            Map.entry("mech:piston_core", "craft/piston"),
            Map.entry("mech:piston_pole", "craft/piston"),
            Map.entry("mech:piston_head", "craft/piston"),
            Map.entry("mech:rotator", "craft/rotator"),
            Map.entry("mech:mechanism_minecart", "craft/minecart"),
            Map.entry("mech:redstone_dynamo", "craft/dynamo"),
            // v0.3.1 blocks — earnable but intentionally NOT in MACHINE_CRAFT_NODES (keeps
            // master_machinist earnable for players who predate them).
            Map.entry("mech:throttle_lever", "craft/throttle_lever"),
            Map.entry("mech:mechanical_dispenser", "craft/dispenser"),
            Map.entry("mech:pump", "craft/pump"),
            Map.entry("mech:burner", "craft/burner"),
            Map.entry("mech:boiler", "craft/boiler"),
            Map.entry("mech:steam_piston", "craft/steam_piston"),
            Map.entry("mech:casing_oak", "craft/casing"),
            Map.entry("mech:casing_acacia", "craft/casing"),
            Map.entry("mech:casing_bamboo", "craft/casing"),
            Map.entry("mech:casing_birch", "craft/casing"),
            Map.entry("mech:casing_cherry", "craft/casing"),
            Map.entry("mech:casing_crimson", "craft/casing"),
            Map.entry("mech:casing_dark_oak", "craft/casing"),
            Map.entry("mech:casing_jungle", "craft/casing"),
            Map.entry("mech:casing_mangrove", "craft/casing"),
            Map.entry("mech:casing_pale_oak", "craft/casing"),
            Map.entry("mech:casing_spruce", "craft/casing"),
            Map.entry("mech:casing_warped", "craft/casing"));

    // The machine craft nodes that together earn craft/master_machinist (tools/parts excluded).
    private static final Set<String> MACHINE_CRAFT_NODES = Set.of(
            "craft/shaft", "craft/gear", "craft/water_wheel", "craft/windmill_item",
            "craft/clutch", "craft/reverser", "craft/chain_pulley", "craft/drill", "craft/millstone",
            "craft/fan", "craft/suction_hopper", "craft/press", "craft/sieve", "craft/placer",
            "craft/motor", "craft/engine",
            "craft/piston", "craft/rotator", "craft/minecart", "craft/dynamo", "craft/chain_hoist");

    // The mastery capstones that together earn mastery/grand_engineer. Excludes windmill/huge, which is
    // BetterBanners-gated (would make the finale unobtainable without that plugin).
    private static final Set<String> CAPSTONES = Set.of(
            "craft/master_machinist", "mastery/operator", "rotation/chain_max",
            "structures/earthshaker", "structures/drawbridge", "machines/farm_to_table");

    // The processed goods that together earn machines/farm_to_table.
    private static final Set<String> FOOD_PRODUCTS = Set.of(
            "machines/flour", "machines/bread", "machines/juice",
            "machines/oil", "machines/honey", "machines/dye");

    // Acting machine type → use/ milestone (chain hoist is missing on purpose: its strokes arrive as
    // MechanismAssembleEvents, handled in onAssemble).
    private static final Map<String, String> USE_NODE_BY_MACHINE = Map.of(
            "mech:drill", "use/drill",
            "mech:fan", "use/fan",
            "mech:placer", "use/placer",
            "mech:suction_hopper", "use/suction_hopper",
            "mech:redstone_dynamo", "use/dynamo",
            "mech:clutch", "use/clutch",
            "mech:reverser", "use/reverser",
            // use/dispenser is NOT in USE_NODES (keeps mastery/operator earnable for existing players).
            "mech:mechanical_dispenser", "use/dispenser");

    // The use/ milestones that together earn mastery/operator.
    private static final Set<String> USE_NODES = Set.of(
            "use/drill", "use/fan", "use/placer", "use/suction_hopper",
            "use/chain_hoist", "use/dynamo", "use/clutch", "use/reverser");

    // Millstone outputs that count as ore milling. QUARTZ is excluded on purpose: it's also produced
    // by the quartz-block unpacking recipe, which isn't ore processing.
    private static final Set<Material> ORE_MILL_OUTPUTS = Set.of(
            Material.RAW_IRON, Material.RAW_GOLD, Material.RAW_COPPER, Material.COAL,
            Material.GOLD_NUGGET, Material.REDSTONE, Material.LAPIS_LAZULI,
            Material.DIAMOND, Material.EMERALD, Material.NETHERITE_SCRAP);

    /** Fans/suction hoppers act every few ticks while powered — cap the nearby-player scans they can
     *  trigger. Keyed per machine INSTANCE (type + position), not per type: a shared per-type window
     *  would let one always-on machine starve a second same-type machine's grant (and thus a player's
     *  use/* → mastery/operator). Grants are idempotent, so dropping repeats inside the window loses
     *  nothing. Bounded by the number of placed machines. */
    private static final int ACT_THROTTLE_TICKS = 20;
    private final Map<String, Integer> lastActedTick = new HashMap<>();

    // ── Milestone entry points ─────────────────────────────────────────────

    /** A player crafted a mech item (result PDC id, e.g. {@code mech:shaft}). */
    public void onCraft(Player player, String itemId) {
        grant(player, "root");
        String node = CRAFT_NODE_BY_ITEM.get(itemId);
        if (node != null) grant(player, node);
        checkAggregates(player);
    }

    /** A player placed a windmill block ({@code mech:windmill|large_windmill|huge_windmill}). */
    public void onWindmillPlace(Player player, String blockId) {
        String node = switch (blockId) {
            case "mech:windmill" -> "windmill/plain";
            case "mech:large_windmill" -> "windmill/large";
            case "mech:huge_windmill" -> "windmill/huge";
            default -> null;
        };
        if (node == null) return;
        grant(player, node); // ancestors (plain←large←huge) come along via grantWithAncestors
        checkAggregates(player);
    }

    /** A mechanism assembled near {@code pivot}. {@code type} is the assembler id; {@code vertical}
     *  distinguishes a rotator door (Y axis) from a wall drawbridge. */
    public void onAssemble(Location pivot, String type, boolean vertical, int blockCount) {
        List<Player> players = nearby(pivot);
        if (players.isEmpty()) return;
        // The type-specific use-node (door/drawbridge/minecart/pistons) now lives under its craft-node,
        // not under structures/assemble, so grant "It Moves!" directly for any assembly.
        String typeNode = switch (type) {
            case "mech:rotator" -> vertical ? "structures/door" : "structures/drawbridge";
            case "mech:mechanism_minecart" -> "structures/minecart";
            case "mech:piston" -> "structures/pistons";
            // Every hoist stroke assembles the platform, so a stroke IS the use milestone.
            case "mech:chain_hoist" -> "use/chain_hoist";
            default -> null;
        };
        String sizeNode = blockCount >= 128 ? "structures/earthshaker"
                : blockCount >= 32 ? "structures/big_move" : null;
        for (Player p : players) {
            grant(p, "structures/assemble");
            if (typeNode != null) grant(p, typeNode);
            if (sizeNode != null) grant(p, sizeNode);
            checkAggregates(p);
        }
    }

    /** A rotation network became powered near {@code location} (rising edge only — see the core event).
     *  {@code sourceTypes} are the block-type ids of the sources driving it. */
    public void onPower(Location location, int supply, int demand, int memberCount,
                        List<String> sourceTypes, boolean chainLoop) {
        List<Player> players = nearby(location);
        if (players.isEmpty()) return;
        // Grant the highest reached tier; grantWithAncestors cascades to the lower ones.
        String chainNode = memberCount >= 200 ? "rotation/chain_max"
                : memberCount >= 32 ? "rotation/chain_32"
                : memberCount >= 8 ? "rotation/chain_8" : null;
        String torqueNode = supply >= 30 ? "rotation/torque_30"
                : supply >= 15 ? "rotation/torque_15"
                : supply >= 5 ? "rotation/torque_5" : null;
        boolean automation = supply > 0 && demand > 0;
        boolean wind = sourceTypes.stream().anyMatch(s -> s.startsWith("mech:") && s.contains("windmill"));
        boolean water = sourceTypes.contains("mech:water_wheel");
        boolean engine = sourceTypes.contains("mech:engine");
        boolean steam = sourceTypes.contains("mech:steam_piston");
        for (Player p : players) {
            grant(p, "rotation/first_power");
            if (chainNode != null) grant(p, chainNode);
            if (torqueNode != null) grant(p, torqueNode);
            if (automation) grant(p, "mastery/automation");
            if (wind) grant(p, "rotation/wind_power");
            if (water) grant(p, "rotation/water_power");
            if (engine) grant(p, "rotation/engine_power");
            if (steam) grant(p, "rotation/steam_power");
            if (chainLoop) grant(p, "rotation/chain_loop");
            checkAggregates(p);
        }
    }

    /** A processing machine delivered outputs near {@code location} — grant the product node(s).
     *  Gated by {@code machineType}: the millstone and the sieve can produce the same minerals, so
     *  item inspection alone would credit ore milling for a lucky pan (and vice versa). */
    public void onMachineOutput(Location location, String machineType, List<ItemStack> outputs) {
        boolean mill = "mech:millstone".equals(machineType);
        boolean press = "mech:press".equals(machineType);
        boolean sieve = "mech:sieve".equals(machineType);
        if (!mill && !press && !sieve) return;
        Set<String> nodes = new HashSet<>();
        for (ItemStack out : outputs) {
            if (out == null) continue;
            String id = CustomBlockRegistry.getItemTypeId(out);
            if (id != null) {
                if (mill && id.equals("mech:flour")) nodes.add("machines/flour");
                else if (press && id.equals("mech:seed_oil")) nodes.add("machines/oil");
                else if (press && id.endsWith("_juice")) nodes.add("machines/juice");
            }
            Material m = out.getType();
            if (press) {
                if (m == Material.HONEY_BOTTLE) nodes.add("machines/honey");
                else if (m.name().endsWith("_DYE")) nodes.add("machines/dye");
            }
            if (mill) {
                if (m == Material.SAND || m == Material.RED_SAND) nodes.add("machines/sand");
                else if (ORE_MILL_OUTPUTS.contains(m)) {
                    nodes.add("machines/ore_milled");
                    if (m == Material.DIAMOND || m == Material.EMERALD) nodes.add("machines/precious");
                    else if (m == Material.NETHERITE_SCRAP) nodes.add("machines/netherite");
                }
            }
            if (sieve) {
                nodes.add("machines/panning"); // any delivered payout counts — empty cycles never eject
                if (m == Material.DIAMOND || m == Material.EMERALD) nodes.add("machines/pan_treasure");
            }
        }
        if (nodes.isEmpty()) return;
        List<Player> players = nearby(location);
        for (Player p : players) {
            for (String n : nodes) grant(p, n);
            checkAggregates(p);
        }
    }

    /** A machine performed its action (drill broke, fan pushed, clutch gated, …) near {@code location}.
     *  Throttled per machine type: hot emitters (a fan over a mob farm) otherwise re-scan for nearby
     *  players every effect tick for a grant that short-circuits anyway. */
    public void onMachineActed(Location location, String machineType) {
        String node = USE_NODE_BY_MACHINE.get(machineType);
        if (node == null) return;
        int now = Bukkit.getCurrentTick();
        String tkey = machineType + "@" + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
        Integer last = lastActedTick.get(tkey);
        if (last != null && now - last < ACT_THROTTLE_TICKS) return;
        lastActedTick.put(tkey, now);
        for (Player p : nearby(location)) {
            grant(p, node);
            checkAggregates(p);
        }
    }

    /** A player committed glue with the brush — the "Bound Together" moment. */
    public void onGlueApplied(Player player) {
        grant(player, "structures/first_glue");
        checkAggregates(player);
    }

    /** A player baked Dough into Bread (vanilla furnace extract). */
    public void onBreadBaked(Player player) {
        grant(player, "machines/bread");
        checkAggregates(player);
    }

    /** Re-check aggregate capstones after a mech advancement was earned (also covers /mech advance). */
    public void onAdvancementDone(Player player, String advancementKey) {
        if (!advancementKey.startsWith(NAMESPACE + ":")) return;
        checkAggregates(player);
    }

    private void checkAggregates(Player player) {
        if (allDone(player, MACHINE_CRAFT_NODES)) grant(player, "craft/master_machinist");
        if (allDone(player, FOOD_PRODUCTS)) grant(player, "machines/farm_to_table");
        if (allDone(player, USE_NODES)) grant(player, "mastery/operator");
        if (allDone(player, CAPSTONES)) grant(player, "mastery/grand_engineer");
    }

    private boolean allDone(Player player, Set<String> nodes) {
        for (String node : nodes) {
            Advancement adv = Bukkit.getAdvancement(key(node));
            if (adv == null || !player.getAdvancementProgress(adv).isDone()) return false;
        }
        return true;
    }

    // ── Debug / admin (/mech advance <path|*>) ─────────────────────────────

    /** Grant one node by path (plus ancestors). Returns false if the advancement doesn't exist. */
    public boolean grantByPath(Player player, String path) {
        Advancement adv = Bukkit.getAdvancement(key(path));
        if (adv == null) return false;
        grantWithAncestors(player, adv);
        checkAggregates(player);
        return true;
    }

    /** Grant every {@code mech} advancement. Returns the count newly granted. */
    public int grantAll(Player player) {
        int count = 0;
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            if (!NAMESPACE.equals(adv.getKey().getNamespace())) continue;
            AdvancementProgress progress = player.getAdvancementProgress(adv);
            if (!progress.isDone()) {
                progress.awardCriteria(CRITERION);
                count++;
            }
        }
        return count;
    }

    /** All mech advancement paths (namespace stripped), for tab completion. */
    public List<String> allPaths() {
        List<String> paths = new ArrayList<>();
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            if (NAMESPACE.equals(adv.getKey().getNamespace())) paths.add(adv.getKey().getKey());
        }
        paths.sort(null);
        return paths;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private void grant(Player player, String path) {
        Advancement adv = Bukkit.getAdvancement(key(path));
        if (adv == null) {
            plugin.getLogger().warning("Advancement not found: mech:" + path
                    + " — is the mech datapack loaded?");
            return;
        }
        grantWithAncestors(player, adv);
    }

    private void grantWithAncestors(Player player, Advancement advancement) {
        List<Advancement> chain = new ArrayList<>();
        for (Advancement cur = advancement; cur != null; cur = cur.getParent()) chain.add(cur);
        for (int i = chain.size() - 1; i >= 0; i--) {
            AdvancementProgress progress = player.getAdvancementProgress(chain.get(i));
            if (!progress.isDone()) progress.awardCriteria(CRITERION);
        }
    }

    private List<Player> nearby(Location location) {
        if (location.getWorld() == null) return List.of();
        return new ArrayList<>(location.getWorld().getNearbyPlayers(location, NEARBY_RADIUS));
    }

    private static NamespacedKey key(String path) {
        return new NamespacedKey(NAMESPACE, path);
    }
}
