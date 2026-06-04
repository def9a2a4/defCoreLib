package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;

import java.util.List;


public class CoreLibPlugin extends JavaPlugin implements Listener {

    private static CoreLibPlugin instance;
    private CustomBlockRegistry registry;

    @Override
    public void onEnable() {
        instance = this;
        registry = new CustomBlockRegistry(this);
        registry.startTasks();
        getServer().getPluginManager().registerEvents(this, this);

        // Load demo blocks from YAML
        try (InputStream demoStream = getResource("demo-blocks.yml")) {
            if (demoStream != null) {
                int count = BlockLoader.load(demoStream, registry, getLogger());
                getLogger().info("Loaded " + count + " demo blocks");
            }
        } catch (IOException ignored) {}

        getLogger().info("DefCoreLib enabled");
    }

    @Override
    public void onDisable() {
        if (registry != null) {
            registry.shutdown();
        }
        instance = null;
    }

    public static CoreLibPlugin getInstance() {
        return instance;
    }

    public CustomBlockRegistry getRegistry() {
        return registry;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chunk lifecycle — single scan
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        registry.onChunkLoad(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        registry.onChunkUnload(event.getChunk());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block placement — detect custom block items, write PDC, apply config
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return;
        }

        ItemStack item = event.getItemInHand();
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta meta)) return;

        String typeId = meta.getPersistentDataContainer().get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) return;

        CustomHeadBlock type = registry.getType(typeId);
        if (type == null) return;

        // Check placement restrictions
        if (type.placement() != null) {
            CustomHeadBlock.PlacementConfig pc = type.placement();
            BlockFace placedOn = getAttachmentFace(block);
            if (!pc.allowedFaces().isEmpty() && !pc.allowedFaces().contains(placedOn)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(
                        net.kyori.adventure.text.Component.text("Cannot place this block here",
                                net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            if (pc.requireSolid()) {
                Block support = block.getRelative(placedOn);
                if (!support.getType().isSolid()) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(
                            net.kyori.adventure.text.Component.text("Requires a solid block",
                                    net.kyori.adventure.text.format.NamedTextColor.RED));
                    return;
                }
            }
        }

        // Write PDC to the placed skull
        registry.markBlock(block, type);

        // Apply initial config
        String state = type.defaultState();
        int power = type.sensitivity() != CustomHeadBlock.Sensitivity.NONE ? registry.readPower(block, type) : 0;

        // Schedule for next tick to ensure block state is fully initialized
        getServer().getScheduler().runTask(this, () -> {
            registry.applyConfig(block, type, state, power);

            // Register for redstone tracking if needed
            if (type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
                registry.trackRedstone(block, type, power);
            }

            // Spawn display entities if base config has them
            List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
            for (CustomHeadBlock.DisplayEntityConfig dec : displays) {
                ItemStack displayItem = HeadUtil.createHead(dec.itemTexture(), 1);
                String tag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                        block.getLocation(), dec.tagSuffix());
                DisplayUtil.spawn(block.getLocation(), displayItem, dec.transform(), tag);
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // Block breaking — cleanup displays, light, particles, drops
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        String state = registry.getState(block);

        // Read power BEFORE cleanup removes redstone tracking
        int power = type.sensitivity() != CustomHeadBlock.Sensitivity.NONE ? registry.readPower(block, type) : 0;

        // Cleanup
        registry.onBlockRemoved(block, type);

        // Handle drops
        List<CustomHeadBlock.DropRule> rules = type.dropRules();
        if (!rules.isEmpty()) {
            event.setDropItems(false);

            Player player = event.getPlayer();
            ItemStack tool = player.getInventory().getItemInMainHand();
            boolean silk = tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);

            for (CustomHeadBlock.DropRule rule : rules) {
                if (rule.inState() != null && !rule.inState().equals(state)) continue;
                if (rule.silkTouch() != null && rule.silkTouch() != silk) continue;
                if (rule.requiredTool() != null && tool.getType() != rule.requiredTool()) continue;

                if (rule.isSelfDrop()) {
                    ItemStack drop = type.createItem(1);
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
                } else {
                    for (CustomHeadBlock.ItemDrop itemDrop : rule.drops()) {
                        ItemStack drop = new ItemStack(itemDrop.material(), itemDrop.amount());
                        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
                    }
                }
                break; // First matching rule wins
            }
        }
    }

    // Explosion cleanup
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type != null) {
                registry.onBlockRemoved(block, type);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type != null) {
                registry.onBlockRemoved(block, type);
            }
        }
    }

    // Piston handling
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type != null && type.cancelPistons()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type != null && type.cancelPistons()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // Fire destruction — custom blocks consumed by fire, no drops
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        CustomHeadBlock type = registry.getTypeFromBlock(event.getBlock());
        if (type != null) {
            registry.onBlockRemoved(event.getBlock(), type);
        }
    }

    // Prevent wall-mounted custom skulls from popping off when support block is removed
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysicsCancelForCustomSkulls(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type != null) {
            event.setCancelled(true);
        }
    }

    // Creative middle-click — return correct custom item
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickBlock(io.papermc.paper.event.player.PlayerPickBlockEvent event) {
        Block block = event.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack customItem = type.createItem(1);
        int targetSlot = event.getTargetSlot();
        player.getInventory().setItem(targetSlot, customItem);
        player.getInventory().setHeldItemSlot(targetSlot);
    }

    // ──────────────────────────────────────────────────────────────────────
    // World lifecycle — load/save chunk hints per world
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        registry.loadHintsForWorld(event.getWorld().getUID());
    }

    @EventHandler
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent event) {
        registry.saveHintsForWorld(event.getWorld().getUID());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Interaction — GUI + state transitions
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        // GUI interaction
        if (type.interactGUI() != null) {
            event.setCancelled(true);
            openGUI(event.getPlayer(), type.interactGUI());
            return;
        }

        // State transitions via interaction trigger
        String currentState = registry.getState(block);
        if (currentState == null) return;

        Material heldItem = event.getPlayer().getInventory().getItemInMainHand().getType();
        Material triggerItem = heldItem == Material.AIR ? null : heldItem;

        // Try specific item first, then wildcard
        CustomHeadBlock.StateTransition transition = type.findTransition(
                new CustomHeadBlock.Trigger.Interact(triggerItem), currentState);
        if (transition == null && triggerItem != null) {
            transition = type.findTransition(
                    new CustomHeadBlock.Trigger.Interact(null), currentState);
        }

        if (transition != null) {
            event.setCancelled(true);
            registry.transitionState(block, type, currentState, transition);
        }
    }

    /** Determine which face a skull block is attached to. */
    private static BlockFace getAttachmentFace(Block block) {
        if (block.getType() == Material.PLAYER_WALL_HEAD) {
            if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                return dir.getFacing().getOppositeFace(); // mounted on the opposite face
            }
        }
        return BlockFace.DOWN; // floor head sits on the block below
    }

    private void openGUI(Player player, CustomHeadBlock.InteractGUI gui) {
        switch (gui) {
            case WORKBENCH -> player.openWorkbench(null, true);
            case ANVIL -> player.openAnvil(null, true);
            case ENCHANTING -> player.openEnchanting(null, true);
            case SMITHING -> player.openSmithingTable(null, true);
            case LOOM -> player.openLoom(null, true);
            case STONECUTTER -> player.openStonecutter(null, true);
            case GRINDSTONE -> player.openGrindstone(null, true);
            case CARTOGRAPHY -> player.openCartographyTable(null, true);
            case ENDERCHEST -> player.openInventory(player.getEnderChest());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Neighbor changes — only for blocks that declared reactsToNeighbors
    // ──────────────────────────────────────────────────────────────────────

    private static final BlockFace[] CARDINAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block changed = event.getBlock();
        // Fast path: check if any neighbor is in the reactive set before doing expensive lookups
        for (BlockFace face : CARDINAL_FACES) {
            Block neighbor = changed.getRelative(face);
            if (!registry.isNeighborReactive(neighbor)) continue;
            // Only now do the expensive getTypeFromBlock lookup
            CustomHeadBlock type = registry.getTypeFromBlock(neighbor);
            if (type != null && type.onNeighborChange() != null) {
                type.onNeighborChange().accept(neighbor, face.getOppositeFace());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Commands: /corelib give <id> [amount] | /corelib list
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("corelib")) return false;
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /corelib <give|list>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage(Component.text("Registered blocks:", NamedTextColor.GOLD));
                for (CustomHeadBlock type : registry.allTypes()) {
                    sender.sendMessage(Component.text("  " + type.fullId(), NamedTextColor.WHITE));
                }
            }
            case "give" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /corelib give <id> [amount]", NamedTextColor.YELLOW));
                    return true;
                }
                String blockId = args[1];
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("Invalid amount: " + args[2], NamedTextColor.RED));
                        return true;
                    }
                }

                // Try with namespace prefix, fall back to searching all namespaces
                CustomHeadBlock type = registry.getType(blockId);
                if (type == null) {
                    for (CustomHeadBlock t : registry.allTypes()) {
                        if (t.typeId().equals(blockId)) {
                            type = t;
                            break;
                        }
                    }
                }

                if (type == null) {
                    sender.sendMessage(Component.text("Unknown block: " + blockId, NamedTextColor.RED));
                    return true;
                }

                ItemStack item = type.createItem(amount);

                player.getInventory().addItem(item);
                sender.sendMessage(Component.text("Gave " + amount + "x " + type.fullId(), NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand: " + args[0], NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("corelib")) return List.of();
        if (args.length == 1) {
            return List.of("give", "list").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            return registry.allTypes().stream()
                    .map(CustomHeadBlock::fullId)
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
