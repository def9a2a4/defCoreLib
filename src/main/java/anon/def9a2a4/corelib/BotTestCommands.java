package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Vector3i;

import java.util.List;
import java.util.Map;

/**
 * Server half of the mineflayer bot in {@code test-bot/}: {@code /defcorelib bottest fixture <name>} builds
 * a fixed scene and replies {@code FIXTURE READY <name> <x> <y> <z>}; {@code /defcorelib bottest assert
 * <name> [arg]} checks server-only state and replies {@code ASSERT PASS <name>} or {@code ASSERT FAIL
 * <name>: <reason>}. The bot reads both from chat. Only constructed under {@code -Ddefcorelib.botTest=true}
 * ({@code make bot-test}); otherwise the subcommand does not exist.
 *
 * <p>Fixtures are built with {@link ShowcaseBuilder}, i.e. the same place→register path as a hand-placed
 * block, at fixed cells so a run is reproducible. They assume a fresh world: there is no clean way to
 * remove a custom block wholesale (network nodes, displays, storage), so a dirty area is refused.
 */
final class BotTestCommands {

    private static final int Y = -60;   // superflat surface (grass at -61)
    /** Fixture origins, one per scenario, spaced so their 7x7 clear boxes never overlap. */
    private static final Map<String, int[]> ORIGINS = Map.of(
        "engine", new int[]{0, Y, 12},
        "wrench", new int[]{10, Y, 12},
        "place", new int[]{20, Y, 12},
        "filter", new int[]{30, Y, 12},
        "glue", new int[]{40, Y, 12},
        "cart", new int[]{50, Y, 12});
    /** Cobblestone the {@code cart} fixture puts in its dispenser cart. */
    private static final int CART_ITEMS = 20;

    private final CoreLibPlugin plugin;
    private final CustomBlockRegistry registry;
    private final ShowcaseBuilder builder;
    private final GlueManager glue;
    private final EngineFuelManager fuel;
    /** Railbound's cart manager — null until the companion calls enableCarts(), hence a supplier. */
    private final java.util.function.Supplier<CustomCartManager> carts;

    BotTestCommands(CoreLibPlugin plugin, CustomBlockRegistry registry, ShowcaseBuilder builder,
                    GlueManager glue, EngineFuelManager fuel,
                    java.util.function.Supplier<CustomCartManager> carts) {
        this.plugin = plugin;
        this.registry = registry;
        this.builder = builder;
        this.glue = glue;
        this.fuel = fuel;
        this.carts = carts;
    }

    static boolean enabled() {
        return Boolean.getBoolean("defcorelib.botTest");
    }

    void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 3) {
            sender.sendMessage(Component.text("Usage (player): /defcorelib bottest <fixture|assert> <name> [arg]",
                NamedTextColor.YELLOW));
            return;
        }
        String name = args[2].toLowerCase(java.util.Locale.ROOT);
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "fixture" -> {
                String err = fixture(player.getWorld(), name);
                if (err != null) {
                    player.sendMessage("FIXTURE FAIL " + name + ": " + err);
                    return;
                }
                int[] o = ORIGINS.get(name);
                // ShowcaseBuilder defers applyConfig + network registration one tick; report after that.
                Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(
                    "FIXTURE READY " + name + " " + o[0] + " " + o[1] + " " + o[2]), 2);
            }
            case "assert" -> {
                String err;
                try {
                    err = check(player, name, args.length >= 4 ? args[3] : null);
                } catch (RuntimeException e) {
                    err = "threw " + e;
                }
                player.sendMessage(err == null ? "ASSERT PASS " + name : "ASSERT FAIL " + name + ": " + err);
            }
            default -> sender.sendMessage("Unknown bottest action: " + args[1]);
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private String fixture(World world, String name) {
        int[] o = ORIGINS.get(name);
        if (o == null) return "unknown fixture";
        ShowcaseSpec spec = switch (name) {
            // Floor engine → shaft → fan: a 3-block network, idle until fuelled.
            case "engine", "wrench" -> spec(name, List.of(
                block("mech:engine", BlockFace.DOWN, 0, 0, 0),
                block("mech:shaft", BlockFace.DOWN, 0, 1, 0),
                block("mech:fan", BlockFace.DOWN, 0, 2, 0)), List.of());
            case "place", "cart" -> spec(name, List.of(), List.of());
            case "filter" -> spec(name, List.of(block("pipes:copper_filter_pipe", BlockFace.DOWN, 0, 0, 0)), List.of());
            // A Z-axis hinge with one plank behind it (same layout as the motor_rotator showcase).
            case "glue" -> spec(name, List.of(block("mech:rotator", BlockFace.SOUTH, 0, 0, 0)),
                List.of(new ShowcaseSpec.VanillaSpec(Material.OAK_PLANKS, new int[]{0, 0, 1}, null, false,
                    null, List.of())));
            default -> null;
        };
        if (spec == null) return "unknown fixture";
        if (name.equals("cart") && carts.get() == null) return "Railbound carts are not enabled (companion missing?)";
        for (ShowcaseSpec.BlockSpec bs : spec.blocks) {
            if (registry.getType(bs.id()) == null) return bs.id() + " is not registered (plugin missing?)";
        }

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 4; dy++) {
                    if (registry.getTypeFromBlock(world.getBlockAt(o[0] + dx, o[1] + dy, o[2] + dz)) != null) {
                        return "area already holds custom blocks — run against a fresh world";
                    }
                }
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.getBlockAt(o[0] + dx, o[1] - 1, o[2] + dz).setType(Material.STONE, false);
                for (int dy = 0; dy <= 4; dy++) {
                    world.getBlockAt(o[0] + dx, o[1] + dy, o[2] + dz).setType(Material.AIR, false);
                }
            }
        }
        Location center = new Location(world, o[0] + 0.5, o[1] + 2, o[2] + 0.5);
        world.getNearbyEntities(center, 4, 4, 4, e -> e instanceof Item || e instanceof Minecart)
            .forEach(org.bukkit.entity.Entity::remove);
        builder.build(spec, new Location(world, o[0], o[1], o[2]));
        return name.equals("cart") ? spawnDispenserCart(world, o) : null;
    }

    /**
     * A straight rail at the origin with a tracked Railbound dispenser cart on it holding
     * {@link #CART_ITEMS} cobblestone. Spawned the way CustomCartManager.onPlaceCart does (tag, display,
     * PDC type marker), then adopted through {@code scanChunk} — the chunk-load path — which builds the live
     * inventory from the PDC. The PDC is then blanked, standing in for contents changed since the last
     * persist (hopper fills, dispensing): only the live, tracked inventory still says 20, so a cart that
     * leaves the world with its rider and comes back from the player file shows up empty (and untracked).
     */
    private String spawnDispenserCart(World world, int[] o) {
        world.getBlockAt(o[0], o[1], o[2]).setType(Material.RAIL, false);
        NamespacedKey typeKey = new NamespacedKey(plugin, "cart_type");
        NamespacedKey invKey = new NamespacedKey(plugin, "cart_inventory");
        Inventory seed = Bukkit.createInventory(null, InventoryType.DROPPER);
        seed.addItem(new ItemStack(Material.COBBLESTONE, CART_ITEMS));
        RideableMinecart cart = world.spawn(new Location(world, o[0] + 0.5, o[1], o[2] + 0.5),
            RideableMinecart.class, m -> {
                m.setPersistent(true);
                m.addScoreboardTag(CustomCartManager.TAG_DISPENSER);
                m.setDisplayBlockData(Bukkit.createBlockData(Material.DISPENSER));
                m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "dispenser");
                m.getPersistentDataContainer().set(invKey, PersistentDataType.STRING, InventoryUtil.encode(seed));
            });
        carts.get().scanChunk(cart.getLocation().getChunk());
        if (!carts.get().isTracked(cart)) return "scanChunk did not adopt the spawned dispenser cart";
        String empty = InventoryUtil.encode(Bukkit.createInventory(null, InventoryType.DROPPER));
        cart.getPersistentDataContainer().set(invKey, PersistentDataType.STRING, empty);
        return null;
    }

    private static ShowcaseSpec spec(String id, List<ShowcaseSpec.BlockSpec> blocks,
                                     List<ShowcaseSpec.VanillaSpec> vanilla) {
        return new ShowcaseSpec(id, id, "", "", List.of(), blocks, vanilla, List.of(),
            new ShowcaseSpec.Activate("passive", null), List.of(), true, List.of(), 0, null, List.of());
    }

    private static ShowcaseSpec.BlockSpec block(String id, BlockFace facing, int dx, int dy, int dz) {
        return new ShowcaseSpec.BlockSpec(id, facing, new int[]{dx, dy, dz}, null, null, List.of());
    }

    // ── Assertions (null = pass, else the failure reason) ───────────────────

    private String check(Player player, String name, String arg) {
        World w = player.getWorld();
        switch (name) {
            // The bot shift-clicked `arg` coal into the engine: it must be running, and every piece must be
            // accounted for — still in storage, or (at most) one already pulled into the burn counter.
            case "engine_fuelled" -> {
                Block b = at(w, "engine", 0, 0, 0);
                int expected = Integer.parseInt(arg);
                String state = registry.getState(b);
                Inventory inv = registry.getOrCreateStorage(b);
                int coal = count(inv, Material.COAL);
                int ticks = fuel.getFuel(CustomBlockRegistry.LocationKey.of(b));
                boolean accounted = coal == expected || (coal == expected - 1 && ticks > 0);
                if (state == null || !state.startsWith("running_") || !accounted) {
                    return "state=" + state + " coal=" + coal + " fuelTicks=" + ticks + " (expected " + expected + ")";
                }
                return null;
            }
            case "placed" -> {
                Block b = at(w, "place", 0, 0, 0);
                CustomHeadBlock type = registry.getTypeFromBlock(b);
                int displays = displaysAt(b, "mech", "shaft");
                if (type == null || !type.fullId().equals("mech:shaft") || displays < 1) {
                    return "type=" + (type == null ? b.getType() : type.fullId()) + " displays=" + displays;
                }
                return null;
            }
            // Broken in survival: the cell, its displays and any loose drop are gone, and the player holds
            // exactly one shaft item (a vanilla head drop on top of the custom one would make two).
            case "broken" -> {
                Block b = at(w, "place", 0, 0, 0);
                int displays = displaysAt(b, "mech", "shaft");
                int loose = w.getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 4, 4, 4,
                    e -> e instanceof Item).size();
                int held = 0;
                for (ItemStack it : player.getInventory().getContents()) {
                    if (it != null && "mech:shaft".equals(CustomBlockRegistry.getItemTypeId(it))) held += it.getAmount();
                }
                if (!b.getType().isAir() || displays != 0 || loose != 0 || held != 1) {
                    return "block=" + b.getType() + " displays=" + displays + " looseItems=" + loose + " shaftsHeld=" + held;
                }
                return null;
            }
            case "glued" -> {
                Block anchor = at(w, "glue", 0, 0, 0);
                Block plank = at(w, "glue", 0, 0, 1);
                var offsets = glue.offsets(Anchors.forBlock(anchor, registry, null));
                if (plank.getType() != Material.OAK_PLANKS || !offsets.contains(new Vector3i(0, 0, 1))) {
                    return "plank=" + plank.getType() + " offsets=" + offsets;
                }
                return null;
            }
            // Before the quit: the bot (shears in hand) must actually be riding the fixture's tracked cart.
            case "cart_ridden" -> {
                CustomCartManager cm = carts.get();
                if (cm == null) return "Railbound carts are not enabled";
                if (!(player.getVehicle() instanceof Minecart c) || !c.getScoreboardTags().contains(CustomCartManager.TAG_DISPENSER)) {
                    return "vehicle=" + player.getVehicle();
                }
                return cm.isTracked(c) ? null : "riding an untracked dispenser cart";
            }
            // After quit + rejoin: exactly one dispenser cart in the world, tracked, nobody on it, and its live
            // inventory (flushed to the PDC through the world-save handler, then decoded) still holds `arg`.
            case "cart_kept" -> {
                CustomCartManager cm = carts.get();
                if (cm == null) return "Railbound carts are not enabled";
                int expected = Integer.parseInt(arg);
                List<Minecart> found = w.getEntitiesByClass(Minecart.class).stream()
                    .filter(c -> c.getScoreboardTags().contains(CustomCartManager.TAG_DISPENSER)).toList();
                if (player.getVehicle() != null) return "player still riding " + player.getVehicle();
                if (found.size() != 1) return found.size() + " dispenser carts in the world (want 1)";
                Minecart c = found.get(0);
                if (!c.getPassengers().isEmpty()) return "cart has passengers " + c.getPassengers();
                if (!cm.isTracked(c)) return "cart is not tracked (came back from the player file?)";
                cm.onWorldSave(new org.bukkit.event.world.WorldSaveEvent(w));
                Inventory inv = Bukkit.createInventory(null, InventoryType.DROPPER);
                InventoryUtil.decode(c.getPersistentDataContainer().get(new NamespacedKey(plugin, "cart_inventory"),
                    PersistentDataType.STRING), inv, plugin.getLogger());
                int n = count(inv, Material.COBBLESTONE);
                return n == expected ? null : "cart holds " + n + " cobblestone (want " + expected + ")";
            }
            default -> {
                return "unknown assertion";
            }
        }
    }

    private static Block at(World w, String fixture, int dx, int dy, int dz) {
        int[] o = ORIGINS.get(fixture);
        return w.getBlockAt(o[0] + dx, o[1] + dy, o[2] + dz);
    }

    private static int count(Inventory inv, Material m) {
        if (inv == null) return 0;
        int n = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == m) n += it.getAmount();
        }
        return n;
    }

    private static int displaysAt(Block b, String ns, String type) {
        String prefix = DisplayUtil.blockTagPrefix(ns, type, b.getLocation());
        return b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 2, 2, 2, e -> {
            if (!(e instanceof Display)) return false;
            for (String tag : e.getScoreboardTags()) {
                if (tag.equals(prefix) || tag.startsWith(prefix + ":")) return true;
            }
            return false;
        }).size();
    }
}
