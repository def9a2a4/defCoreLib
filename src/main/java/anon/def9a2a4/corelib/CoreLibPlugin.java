package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


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

        // Register recipes after all blocks are loaded
        registry.finalizeLoading();

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
            // Guard: block may have been broken between placement and this tick
            if (registry.getTypeFromBlock(block) == null) return;

            registry.applyConfig(block, type, state, power);

            // Register for redstone tracking if needed
            if (type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
                registry.trackRedstone(block, type, power);
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

        // Handle drops (skip in creative mode)
        event.setDropItems(false); // always suppress vanilla head drop
        Player player = event.getPlayer();
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        List<CustomHeadBlock.DropRule> rules = type.dropRules();
        if (!rules.isEmpty()) {
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

    // Explosion cleanup — remove custom blocks from blast list, drop correct items
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    private void handleExplosion(List<Block> blockList) {
        java.util.Iterator<Block> it = blockList.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type != null) {
                it.remove(); // prevent vanilla skull drop
                block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 0.5, 0.5), type.createItem(1));
                registry.onBlockRemoved(block, type);
                block.setType(Material.AIR); // actually remove the block from the world
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

    // Water/lava flow destroying custom heads
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(org.bukkit.event.block.BlockFromToEvent event) {
        Block to = event.getToBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(to);
        if (type != null) {
            registry.onBlockRemoved(to, type);
        }
    }

    // Prevent wall-mounted custom skulls from popping off when support block is removed,
    // but allow other physics (redstone propagation) to proceed normally
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysicsCancelForCustomSkulls(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;
        // Only cancel if the support block is gone (prevents pop-off without suppressing redstone)
        BlockFace attachment = getAttachmentFace(block);
        Block support = block.getRelative(attachment);
        if (!support.getType().isSolid()) {
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

        // Don't overwrite existing items — find an empty hotbar slot or swap safely
        ItemStack existing = player.getInventory().getItem(targetSlot);
        if (existing != null && existing.getType() != Material.AIR) {
            // Check if player already has this item in hotbar
            for (int i = 0; i < 9; i++) {
                ItemStack hotbar = player.getInventory().getItem(i);
                if (hotbar != null && hotbar.isSimilar(customItem)) {
                    player.getInventory().setHeldItemSlot(i);
                    return;
                }
            }
            // Find empty hotbar slot
            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack hotbar = player.getInventory().getItem(i);
                if (hotbar == null || hotbar.getType() == Material.AIR) {
                    emptySlot = i;
                    break;
                }
            }
            if (emptySlot != -1) {
                targetSlot = emptySlot;
            }
            // If no empty slot in creative, overwrite is acceptable (matches vanilla behavior)
        }

        player.getInventory().setItem(targetSlot, customItem);
        player.getInventory().setHeldItemSlot(targetSlot);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Recipe validation — verify custom block ingredients by PDC, not ExactChoice
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        if (!(event.getRecipe() instanceof org.bukkit.Keyed keyed)) return;

        Map<Character, String> headIngredients = registry.getHeadIngredients(keyed.getKey());
        if (headIngredients == null) return; // not one of our recipes with head ingredients

        // Validate PLAYER_HEAD items by consuming from required ingredients list.
        // This correctly handles recipes needing multiple different head types.
        List<String> remaining = new ArrayList<>(headIngredients.values());
        org.bukkit.inventory.ItemStack[] matrix = event.getInventory().getMatrix();
        for (ItemStack item : matrix) {
            if (item == null || item.getType() != Material.PLAYER_HEAD) continue;
            String blockType = null;
            if (item.getItemMeta() != null) {
                blockType = item.getItemMeta().getPersistentDataContainer()
                        .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
            }
            if (!remaining.remove(blockType)) {
                event.getInventory().setResult(null);
                return;
            }
        }
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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
    // Stonecutter interception for head-to-head recipes
    // ──────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStonecutterClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        // Handle clicks in our custom selection menu (InventoryHolder pattern)
        if (event.getInventory().getHolder() instanceof StonecutterSelectHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= holder.recipes().size()) return;

            var recipe = holder.recipes().get(slot);
            handleStonecutterCraft(player, holder.inputBlockId(), recipe);
            return;
        }

        // Handle clicks in the vanilla stonecutter
        if (event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.STONECUTTER) return;
        if (!(event.getInventory() instanceof org.bukkit.inventory.StonecutterInventory inv)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot == 1) {
            // Clicking the result slot — check if it's our hint item
            ItemStack result = inv.getResult();
            if (result != null && result.getType() == Material.STONECUTTER && result.hasItemMeta()) {
                String tag = result.getItemMeta().getPersistentDataContainer()
                        .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
                if (tag != null && tag.startsWith("_sc_hint:")) {
                    event.setCancelled(true);
                    openStonecutterSelectMenu(player, tag.substring("_sc_hint:".length()));
                    return;
                }
            }
        }

        // Input changed — schedule hint update
        getServer().getScheduler().runTask(this, () -> updateStonecutterHint(inv));
    }

    @EventHandler
    public void onStonecutterDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.STONECUTTER) return;
        if (!(event.getInventory() instanceof org.bukkit.inventory.StonecutterInventory inv)) return;

        if (event.getRawSlots().contains(0)) {
            getServer().getScheduler().runTask(this, () -> updateStonecutterHint(inv));
        }
    }

    private void updateStonecutterHint(org.bukkit.inventory.StonecutterInventory inv) {
        ItemStack input = inv.getInputItem();
        if (input == null || input.getType() != Material.PLAYER_HEAD || !input.hasItemMeta()) {
            clearStonecutterHint(inv);
            return;
        }

        String blockId = input.getItemMeta().getPersistentDataContainer()
                .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (blockId == null) { clearStonecutterHint(inv); return; }

        var recipes = registry.getStonecutterRecipesForInput(blockId);
        if (recipes.isEmpty()) { clearStonecutterHint(inv); return; }

        ItemStack hint = new ItemStack(Material.STONECUTTER);
        var meta = hint.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Click to select output", NamedTextColor.GREEN));
            meta.lore(List.of(
                    Component.text(recipes.size() + " recipes available", NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Click to open selection menu", NamedTextColor.YELLOW)));
            meta.getPersistentDataContainer().set(CustomBlockRegistry.BLOCK_TYPE_KEY,
                    PersistentDataType.STRING, "_sc_hint:" + blockId);
            hint.setItemMeta(meta);
        }
        inv.setResult(hint);
    }

    private void clearStonecutterHint(org.bukkit.inventory.StonecutterInventory inv) {
        ItemStack result = inv.getResult();
        if (result == null) return;
        if (result.getType() == Material.STONECUTTER && result.hasItemMeta()) {
            String tag = result.getItemMeta().getPersistentDataContainer()
                    .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (tag != null && tag.startsWith("_sc_hint:")) {
                inv.setResult(null);
            }
        }
    }

    private void openStonecutterSelectMenu(Player player, String inputBlockId) {
        var recipes = registry.getStonecutterRecipesForInput(inputBlockId);
        if (recipes.isEmpty()) {
            player.sendMessage(Component.text("No stonecutter recipes available.", NamedTextColor.RED));
            return;
        }

        StonecutterSelectHolder holder = new StonecutterSelectHolder(inputBlockId, recipes);

        CustomHeadBlock inputType = registry.getType(inputBlockId);
        Component title = Component.text("Stonecutter", NamedTextColor.DARK_PURPLE);
        if (inputType != null && inputType.name() != null) {
            title = title.append(Component.text(": ")).append(inputType.name());
        }

        // Items area (up to 45) + bottom row for navigation/info
        int itemSlots = Math.min(recipes.size(), 45);
        int rows = Math.max(1, (itemSlots + 8) / 9) + 1; // +1 row for bottom bar
        int size = rows * 9;

        org.bukkit.inventory.Inventory inv = getServer().createInventory(holder, size, title);
        holder.setInventory(inv);

        // Populate output items
        for (int i = 0; i < itemSlots; i++) {
            var recipe = recipes.get(i);
            CustomHeadBlock outputType = registry.getType(recipe.outputBlockId());
            if (outputType == null) continue;
            inv.setItem(i, outputType.createItem(recipe.amount()));
        }

        // Bottom bar: filler + input display + close button
        int bottomStart = size - 9;
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) { fillerMeta.displayName(Component.empty()); filler.setItemMeta(fillerMeta); }
        for (int i = bottomStart; i < size; i++) inv.setItem(i, filler);

        // Input item display (center of bottom row)
        if (inputType != null) inv.setItem(bottomStart + 4, inputType.createItem(1));

        player.openInventory(inv);
    }

    private void handleStonecutterCraft(Player player, String inputBlockId,
                                         CustomBlockRegistry.HeadStonecutterRecipe recipe) {
        // Find input in player's inventory
        int inputSlot = -1;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) continue;
            String itemBlockId = item.getItemMeta().getPersistentDataContainer()
                    .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
            if (inputBlockId.equals(itemBlockId)) {
                inputSlot = i;
                break;
            }
        }

        if (inputSlot == -1) {
            player.sendMessage(Component.text("You need the input item in your inventory.", NamedTextColor.RED));
            return;
        }

        // Consume 1 input
        ItemStack inputItem = player.getInventory().getItem(inputSlot);
        if (inputItem.getAmount() > 1) {
            inputItem.setAmount(inputItem.getAmount() - 1);
        } else {
            player.getInventory().setItem(inputSlot, null);
        }

        // Give result
        CustomHeadBlock outputType = registry.getType(recipe.outputBlockId());
        if (outputType == null) return;
        ItemStack result = outputType.createItem(recipe.amount());
        var leftover = player.getInventory().addItem(result);
        for (ItemStack lf : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), lf);
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.UI_STONECUTTER_TAKE_RESULT, 1f, 1f);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Neighbor changes — only for blocks that declared reactsToNeighbors
    // ──────────────────────────────────────────────────────────────────────

    private static final BlockFace[] CARDINAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
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
        if (!command.getName().equalsIgnoreCase("defcorelib")) return false;
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /defcorelib <give|give_demo|list>", NamedTextColor.YELLOW));
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
                    sender.sendMessage(Component.text("Usage: /defcorelib give <id> [amount]", NamedTextColor.YELLOW));
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

                var overflow = player.getInventory().addItem(item);
                for (ItemStack lf : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), lf);
                }
                sender.sendMessage(Component.text("Gave " + amount + "x " + type.fullId(), NamedTextColor.GREEN));
            }
            case "give_demo" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Must be a player", NamedTextColor.RED));
                    return true;
                }
                int count = 0;
                for (CustomHeadBlock type : registry.allTypes()) {
                    var overflow = player.getInventory().addItem(type.createItem(1));
                    for (ItemStack lf : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), lf);
                    }
                    count++;
                }
                sender.sendMessage(Component.text("Gave " + count + " demo blocks", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand: " + args[0], NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("defcorelib")) return List.of();
        if (args.length == 1) {
            return List.of("give", "give_demo", "list").stream()
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
