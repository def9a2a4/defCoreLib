package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Data-driven definition of a custom head block.
 * <p>
 * A block definition is layered:
 * <ol>
 *   <li>Base config — texture, drops, display entities, light, particles, etc.</li>
 *   <li>States (optional) — any/all of the above can be overridden per state</li>
 *   <li>State transitions (optional) — extensible trigger system</li>
 *   <li>Redstone (optional, tiered) — NONE / BINARY / LEVEL sensitivity</li>
 * </ol>
 *
 * Create instances via {@link #builder(String, String)}.
 */
public final class CustomHeadBlock {

    // ──────────────────────────────────────────────────────────────────────
    // Nested data records
    // ──────────────────────────────────────────────────────────────────────

    /** A value that is either fixed or linearly interpolated from redstone power level. */
    public record Scaling(double base, double perPower) {
        public static Scaling fixed(double value) { return new Scaling(value, 0); }
        public double resolve(int power) { return base + perPower * power; }
        public int resolveInt(int power) { return (int) Math.round(resolve(power)); }
    }

    /** How to read redstone power for this block. */
    public enum PowerReader {
        /** Standard {@code block.getBlockPower()} at the skull location. */
        DIRECT,
        /** Orientation-aware: wall heads read through the wall behind, floor heads read below. */
        EXTENDED
    }

    /** How sensitive this block is to redstone power. */
    public enum Sensitivity {
        /** Block ignores redstone entirely. Never ticked for power. */
        NONE,
        /** Only checks powered/unpowered. Cheaper than reading exact level. */
        BINARY,
        /** Reads exact power level 0-15. */
        LEVEL
    }

    /** GUI type opened on right-click. */
    public enum InteractGUI {
        WORKBENCH, ANVIL, ENCHANTING, SMITHING, LOOM, STONECUTTER, GRINDSTONE, CARTOGRAPHY, ENDERCHEST
    }

    /** Inclusive range of redstone power levels. Self-correcting: clamps to 0-15, swaps if inverted. */
    public record PowerRange(int min, int max) {
        public PowerRange {
            min = Math.clamp(min, 0, 15);
            max = Math.clamp(max, 0, 15);
            if (min > max) { int tmp = min; min = max; max = tmp; }
        }
        public static final PowerRange ANY = new PowerRange(0, 15);
        public static final PowerRange ZERO = new PowerRange(0, 0);
        public static final PowerRange POWERED = new PowerRange(1, 15);
        public boolean contains(int power) { return power >= min && power <= max; }
    }

    /** Light block to place/remove when this config is active. */
    public record LightConfig(int level, int offsetX, int offsetY, int offsetZ) {}

    /** Particle effect configuration. Count and speed support power-based scaling. */
    public record ParticleConfig(
            Particle type,
            Scaling count,
            Scaling speed,
            int intervalTicks,
            Vector floorOffset,
            Map<BlockFace, Vector> wallOffsets,
            @Nullable Object data  // e.g. Particle.DustOptions for DUST type
    ) {}

    /** An ItemDisplay entity attached to the block.
     *  The {@code displayItem} is resolved at parse time — either from a skull
     *  texture (base64) or an arbitrary material (e.g. WHITE_BANNER).
     *  {@code wallOffset} pushes the display toward the viewer on wall-mounted heads. */
    public record DisplayEntityConfig(
            org.bukkit.inventory.ItemStack displayItem,
            Transformation transform,
            @Nullable String tagSuffix,
            @Nullable DisplayAnimation animation,
            int interpolationDuration,
            float wallOffset
    ) {}

    /** Redstone configuration. */
    public record RedstoneConfig(
            Sensitivity sensitivity,
            PowerReader reader,
            /** Power level → texture override (for LEVEL sensitivity). */
            Map<Integer, String> textures,
            /** Power range → texture override (for BINARY/LEVEL sensitivity). */
            Map<PowerRange, String> textureRanges
    ) {}

    /** A condition that triggers a state transition. Sealed for extensibility. */
    public sealed interface Trigger {
        /** Right-click with a specific item (null = any item or empty hand). */
        record Interact(@Nullable Material item) implements Trigger {}
        /** Redstone power enters the given range. */
        record RedstonePower(PowerRange range) implements Trigger {}
        // Future: record EntityNearby(EntityType type, double radius) implements Trigger {}
        // Future: record TimePassed(int ticks) implements Trigger {}
    }

    /** Particle effect played during a state transition (one-shot, not ongoing). */
    public record TransitionParticle(Particle type, int count, double spread) {}

    /** A state transition with optional sound, particle, and item consumption effects. */
    public record StateTransition(
            Trigger trigger,
            String fromState,
            String toState,
            @Nullable Sound sound,
            float volume,
            float pitch,
            @Nullable TransitionParticle particle,
            boolean consumeItem,
            int consumeAmount
    ) {}

    /** Conditional drop rule. */
    public record DropRule(
            @Nullable String inState,
            @Nullable Material requiredTool,
            @Nullable Boolean silkTouch,
            List<ItemDrop> drops
    ) {
        /** Convenience: drop the block's own item. */
        public static DropRule self() { return new DropRule(null, null, null, List.of()); }
        /** True when this rule drops the block's own item (empty drops list = drop self). */
        public boolean isSelfDrop() { return drops.isEmpty(); }
    }

    /** An item to drop. */
    public record ItemDrop(Material material, int amount) {}

    /** Sound effect with volume and pitch. */
    public record SoundConfig(Sound sound, float volume, float pitch) {}

    /** Placement restrictions. */
    public record PlacementConfig(Set<BlockFace> allowedFaces, boolean requireSolid) {}

    /** Inventory layout for storage blocks. */
    public enum InventoryLayout {
        CHEST_1ROW(9), CHEST_2ROW(18), CHEST_3ROW(27), CHEST_4ROW(36), CHEST_5ROW(45), CHEST_6ROW(54),
        DROPPER(9), HOPPER(5);
        public final int slots;
        InventoryLayout(int slots) { this.slots = slots; }
    }

    /** Storage (custom inventory) configuration. */
    public record StorageConfig(InventoryLayout layout) {}

    // ── Recipe records ───────────────────────────────────────────────────

    /** An ingredient: either a material or a reference to another custom block by fullId. */
    public record IngredientSpec(@Nullable Material material, @Nullable String blockId) {
        public boolean isMaterial() { return material != null; }
        public boolean isBlock() { return blockId != null; }
    }

    /** Shaped crafting recipe definition. */
    public record ShapedRecipeDef(String id, int amount, List<String> pattern, Map<Character, IngredientSpec> key) {}

    /** Shapeless crafting recipe definition. */
    public record ShapelessRecipeDef(String id, int amount, List<IngredientSpec> ingredients) {}

    /** Stonecutter recipe definition. Material inputs use Bukkit API; block inputs use custom GUI interception. */
    public record StonecutterRecipeDef(String id, int amount, IngredientSpec input) {}

    /**
     * Visual/behavioral overrides for a specific state.
     * Null fields mean "inherit from base config."
     */
    public record StateConfig(
            @Nullable String texture,
            @Nullable Map<BlockFace, String> directionalTextures,
            @Nullable LightConfig light,
            @Nullable ParticleConfig particles,
            @Nullable List<DisplayEntityConfig> displayEntities,
            boolean clearLight,
            boolean clearParticles,
            boolean clearDisplayEntities
    ) {}

    // ──────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────

    private final String namespace;
    private final String typeId;

    // Item display
    private final @Nullable Component name;
    private final List<Component> lore;

    // Base config
    private final String texture;
    private final @Nullable Map<BlockFace, String> directionalTextures;
    private final @Nullable LightConfig light;
    private final @Nullable ParticleConfig particles;
    private final List<DisplayEntityConfig> displayEntities;
    private final List<ShapedRecipeDef> shapedRecipes;
    private final List<ShapelessRecipeDef> shapelessRecipes;
    private final List<StonecutterRecipeDef> stonecutterRecipes;
    private final @Nullable InteractGUI interactGUI;
    private final @Nullable StorageConfig storage;
    private final @Nullable PlacementConfig placement;
    private final List<DropRule> dropRules;
    private final boolean cancelPistons;
    private final @Nullable SoundConfig placeSound;
    private final @Nullable SoundConfig breakSound;
    private final @Nullable SoundConfig interactSound;
    private final @Nullable String unlockAdvancement;

    private final boolean reactsToNeighbors;
    private final @Nullable Integer tickInterval;

    // States
    private final @Nullable String defaultState;
    private final Map<String, StateConfig> states;
    private final List<StateTransition> transitions;

    // Redstone
    private final @Nullable RedstoneConfig redstone;

    // Placement-face → initial state mapping
    private final @Nullable Map<BlockFace, String> placementStateMap;

    // Cached capability checks
    private final boolean _hasDisplayEntities;
    private final boolean _hasLight;
    private final boolean _hasParticles;

    // Escape hatches
    private final @Nullable BiConsumer<Block, BlockFace> onNeighborChange;
    private final @Nullable Consumer<Block> onTick;
    private final @Nullable BiConsumer<Block, String> onChunkLoadCallback;
    private final @Nullable Consumer<Block> onChunkUnloadCallback;

    private CustomHeadBlock(Builder b) {
        this.namespace = b.namespace;
        this.typeId = b.typeId;
        this.name = b.name;
        this.lore = b.lore != null ? List.copyOf(b.lore) : List.of();
        this.texture = b.texture;
        this.directionalTextures = b.directionalTextures;
        this.light = b.light;
        this.particles = b.particles;
        this.displayEntities = List.copyOf(b.displayEntities);
        this.shapedRecipes = List.copyOf(b.shapedRecipes);
        this.shapelessRecipes = List.copyOf(b.shapelessRecipes);
        this.stonecutterRecipes = List.copyOf(b.stonecutterRecipes);
        this.interactGUI = b.interactGUI;
        this.storage = b.storage;
        this.placement = b.placement;
        this.dropRules = List.copyOf(b.dropRules);
        this.cancelPistons = b.cancelPistons;
        this.placeSound = b.placeSound;
        this.breakSound = b.breakSound;
        this.interactSound = b.interactSound;
        this.unlockAdvancement = b.unlockAdvancement;

        this.reactsToNeighbors = b.reactsToNeighbors;
        this.tickInterval = b.tickInterval;
        this.defaultState = b.defaultState;
        this.states = Map.copyOf(b.states);
        this.transitions = List.copyOf(b.transitions);
        this.redstone = b.redstone;
        this.placementStateMap = b.placementStateMap != null ? Map.copyOf(b.placementStateMap) : null;
        this.onNeighborChange = b.onNeighborChange;
        this.onTick = b.onTick;
        this.onChunkLoadCallback = b.onChunkLoadCallback;
        this.onChunkUnloadCallback = b.onChunkUnloadCallback;

        // Cache capability checks (avoid streaming states on every call)
        this._hasDisplayEntities = !displayEntities.isEmpty() || states.values().stream().anyMatch(s -> s.displayEntities() != null);
        this._hasLight = light != null || states.values().stream().anyMatch(s -> s.light() != null);
        this._hasParticles = particles != null || states.values().stream().anyMatch(s -> s.particles() != null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Accessors
    // ──────────────────────────────────────────────────────────────────────

    public String namespace() { return namespace; }
    public String typeId() { return typeId; }
    public String fullId() { return namespace + ":" + typeId; }

    public @Nullable Component name() { return name; }
    public List<Component> lore() { return lore; }

    public String texture() { return texture; }
    public @Nullable Map<BlockFace, String> directionalTextures() { return directionalTextures; }
    public @Nullable LightConfig light() { return light; }
    public @Nullable ParticleConfig particles() { return particles; }
    public List<DisplayEntityConfig> displayEntities() { return displayEntities; }
    public List<ShapedRecipeDef> shapedRecipes() { return shapedRecipes; }
    public List<ShapelessRecipeDef> shapelessRecipes() { return shapelessRecipes; }
    public List<StonecutterRecipeDef> stonecutterRecipes() { return stonecutterRecipes; }
    public boolean hasRecipes() { return !shapedRecipes.isEmpty() || !shapelessRecipes.isEmpty() || !stonecutterRecipes.isEmpty(); }
    public @Nullable InteractGUI interactGUI() { return interactGUI; }
    public @Nullable StorageConfig storage() { return storage; }
    public @Nullable PlacementConfig placement() { return placement; }
    public List<DropRule> dropRules() { return dropRules; }
    public boolean cancelPistons() { return cancelPistons; }
    public @Nullable SoundConfig placeSound() { return placeSound; }
    public @Nullable SoundConfig breakSound() { return breakSound; }
    public @Nullable SoundConfig interactSound() { return interactSound; }
    public @Nullable String unlockAdvancement() { return unlockAdvancement; }

    public boolean reactsToNeighbors() { return reactsToNeighbors; }
    public @Nullable Integer tickInterval() { return tickInterval; }

    public boolean hasStates() { return !states.isEmpty(); }
    public @Nullable String defaultState() { return defaultState; }
    public Map<String, StateConfig> states() { return states; }
    public List<StateTransition> transitions() { return transitions; }

    public @Nullable RedstoneConfig redstone() { return redstone; }
    public Sensitivity sensitivity() { return redstone != null ? redstone.sensitivity() : Sensitivity.NONE; }
    public @Nullable Map<BlockFace, String> placementStateMap() { return placementStateMap; }

    public @Nullable BiConsumer<Block, BlockFace> onNeighborChange() { return onNeighborChange; }
    public @Nullable Consumer<Block> onTick() { return onTick; }
    public @Nullable BiConsumer<Block, String> onChunkLoadCallback() { return onChunkLoadCallback; }
    public @Nullable Consumer<Block> onChunkUnloadCallback() { return onChunkUnloadCallback; }

    public boolean hasDisplayEntities() { return _hasDisplayEntities; }
    public boolean hasLight() { return _hasLight; }
    public boolean hasParticles() { return _hasParticles; }

    /** Create an ItemStack for this block type with correct texture, name, lore, and PDC. */
    public org.bukkit.inventory.ItemStack createItem(int amount) {
        return HeadUtil.createHead(texture, amount, name, lore,
                Map.of(CustomBlockRegistry.BLOCK_TYPE_KEY, fullId()));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Resolution: given current state + power, resolve effective config
    // ──────────────────────────────────────────────────────────────────────

    /** Resolve the effective texture for the current state, power level, and orientation. */
    public String resolveTexture(@Nullable String state, int power, @Nullable BlockFace facing) {
        // Redstone texture overrides take priority
        if (redstone != null) {
            String rsTex = resolveRedstoneTexture(power);
            if (rsTex != null) return rsTex;
        }
        // State override
        if (state != null) {
            StateConfig sc = states.get(state);
            if (sc != null) {
                // Directional texture within state
                if (facing != null && sc.directionalTextures() != null) {
                    String dirTex = sc.directionalTextures().get(facing);
                    if (dirTex != null) return dirTex;
                }
                if (sc.texture() != null) return sc.texture();
            }
        }
        // Base directional texture
        if (facing != null && directionalTextures != null) {
            String dirTex = directionalTextures.get(facing);
            if (dirTex != null) return dirTex;
        }
        return texture;
    }

    /** Convenience overload without facing (backwards compatible). */
    public String resolveTexture(@Nullable String state, int power) {
        return resolveTexture(state, power, null);
    }

    /** Resolve the effective light config for the current state. */
    public @Nullable LightConfig resolveLight(@Nullable String state) {
        if (state != null) {
            StateConfig sc = states.get(state);
            if (sc != null) {
                if (sc.clearLight()) return null;
                if (sc.light() != null) return sc.light();
            }
        }
        return light;
    }

    /** Resolve the effective particle config for the current state. */
    public @Nullable ParticleConfig resolveParticles(@Nullable String state) {
        if (state != null) {
            StateConfig sc = states.get(state);
            if (sc != null) {
                if (sc.clearParticles()) return null;
                if (sc.particles() != null) return sc.particles();
            }
        }
        return particles;
    }

    /** Resolve the effective display entities for the current state. */
    public List<DisplayEntityConfig> resolveDisplayEntities(@Nullable String state) {
        if (state != null) {
            StateConfig sc = states.get(state);
            if (sc != null) {
                if (sc.clearDisplayEntities()) return List.of();
                if (sc.displayEntities() != null) return sc.displayEntities();
            }
        }
        return displayEntities;
    }

    private @Nullable String resolveRedstoneTexture(int power) {
        if (redstone == null) return null;
        // Exact power match first
        String exact = redstone.textures().get(power);
        if (exact != null) return exact;
        // Range match
        for (var entry : redstone.textureRanges().entrySet()) {
            if (entry.getKey().contains(power)) return entry.getValue();
        }
        return null;
    }

    /** Find the first matching transition for the given trigger and current state. */
    public @Nullable StateTransition findTransition(Trigger trigger, String currentState) {
        for (StateTransition t : transitions) {
            if (!t.fromState().equals(currentState)) continue;
            if (matchesTrigger(t.trigger(), trigger)) return t;
        }
        return null;
    }

    private static boolean matchesTrigger(Trigger defined, Trigger actual) {
        return switch (defined) {
            case Trigger.Interact(var item) -> {
                if (!(actual instanceof Trigger.Interact(var actualItem))) yield false;
                yield item == null || item == actualItem;
            }
            case Trigger.RedstonePower(var range) -> {
                if (!(actual instanceof Trigger.RedstonePower(var actualRange))) yield false;
                // The actual trigger carries the current power in min==max
                yield range.contains(actualRange.min());
            }
        };
    }

    // ──────────────────────────────────────────────────────────────────────
    // Builder
    // ──────────────────────────────────────────────────────────────────────

    public static Builder builder(String namespace, String typeId) {
        return new Builder(namespace, typeId);
    }

    public static final class Builder {
        private final String namespace;
        private final String typeId;

        private @Nullable Component name;
        private @Nullable List<Component> lore;

        private @Nullable String texture;
        private @Nullable Map<BlockFace, String> directionalTextures;
        private @Nullable LightConfig light;
        private @Nullable ParticleConfig particles;
        private final List<DisplayEntityConfig> displayEntities = new ArrayList<>();
        private final List<ShapedRecipeDef> shapedRecipes = new ArrayList<>();
        private final List<ShapelessRecipeDef> shapelessRecipes = new ArrayList<>();
        private final List<StonecutterRecipeDef> stonecutterRecipes = new ArrayList<>();
        private @Nullable InteractGUI interactGUI;
        private @Nullable StorageConfig storage;
        private @Nullable PlacementConfig placement;
        private final List<DropRule> dropRules = new ArrayList<>();
        private boolean cancelPistons;
        private @Nullable SoundConfig placeSound;
        private @Nullable SoundConfig breakSound;
        private @Nullable SoundConfig interactSound;
        private @Nullable String unlockAdvancement;

        private boolean reactsToNeighbors;
        private @Nullable Integer tickInterval;

        private @Nullable String defaultState;
        private final LinkedHashMap<String, StateConfig> states = new LinkedHashMap<>();
        private final List<StateTransition> transitions = new ArrayList<>();

        private @Nullable RedstoneConfig redstone;
        private @Nullable Map<BlockFace, String> placementStateMap;

        private @Nullable BiConsumer<Block, BlockFace> onNeighborChange;
        private @Nullable Consumer<Block> onTick;
        private @Nullable BiConsumer<Block, String> onChunkLoadCallback;
        private @Nullable Consumer<Block> onChunkUnloadCallback;

        private Builder(String namespace, String typeId) {
            this.namespace = Objects.requireNonNull(namespace);
            this.typeId = Objects.requireNonNull(typeId);
        }

        // --- Item display ---

        public Builder name(Component name) { this.name = name; return this; }
        public Builder lore(List<Component> lore) { this.lore = lore; return this; }

        // --- Base config ---

        public Builder texture(String base64) { this.texture = base64; return this; }
        public Builder directionalTextures(Map<BlockFace, String> textures) { this.directionalTextures = textures; return this; }
        public Builder light(int level, int offsetX, int offsetY, int offsetZ) { this.light = new LightConfig(level, offsetX, offsetY, offsetZ); return this; }
        public Builder particles(ParticleConfig config) { this.particles = config; return this; }
        public Builder displayEntities(List<DisplayEntityConfig> configs) { this.displayEntities.addAll(configs); return this; }
        public Builder shapedRecipe(ShapedRecipeDef recipe) { this.shapedRecipes.add(recipe); return this; }
        public Builder shapelessRecipe(ShapelessRecipeDef recipe) { this.shapelessRecipes.add(recipe); return this; }
        public Builder stonecutterRecipe(StonecutterRecipeDef recipe) { this.stonecutterRecipes.add(recipe); return this; }
        public Builder interactGUI(InteractGUI gui) { this.interactGUI = gui; return this; }
        public Builder storage(InventoryLayout layout) { this.storage = new StorageConfig(layout); return this; }
        public Builder placement(PlacementConfig config) { this.placement = config; return this; }
        public Builder drops(DropRule... rules) { this.dropRules.addAll(List.of(rules)); return this; }
        public Builder cancelPistons(boolean cancel) { this.cancelPistons = cancel; return this; }
        public Builder placeSound(SoundConfig sound) { this.placeSound = sound; return this; }
        public Builder breakSound(SoundConfig sound) { this.breakSound = sound; return this; }
        public Builder interactSound(SoundConfig sound) { this.interactSound = sound; return this; }
        public Builder unlockAdvancement(String advancement) { this.unlockAdvancement = advancement; return this; }

        public Builder reactsToNeighbors(boolean reacts) { this.reactsToNeighbors = reacts; return this; }
        public Builder tickInterval(int ticks) { this.tickInterval = Math.max(1, ticks); return this; }

        // --- States ---

        /** Declare a state with no overrides (inherits everything from base). */
        public Builder state(String name) {
            this.states.put(name, new StateConfig(null, null, null, null, null, false, false, false));
            if (defaultState == null) defaultState = name;
            return this;
        }

        /** Declare a state with overrides configured via a lambda. */
        public Builder state(String name, Consumer<StateBuilder> config) {
            StateBuilder sb = new StateBuilder();
            config.accept(sb);
            this.states.put(name, sb.build());
            if (defaultState == null) defaultState = name;
            return this;
        }

        public Builder defaultState(String name) { this.defaultState = name; return this; }

        // --- Transitions ---

        public Builder transition(Trigger trigger, String from, String to) {
            this.transitions.add(new StateTransition(trigger, from, to, null, 1f, 1f, null, false, 0));
            return this;
        }

        public Builder transition(Trigger trigger, String from, String to, Sound sound) {
            this.transitions.add(new StateTransition(trigger, from, to, sound, 1f, 1f, null, false, 0));
            return this;
        }

        public Builder transition(StateTransition transition) {
            this.transitions.add(transition);
            return this;
        }

        // --- Redstone ---

        public Builder redstone(Sensitivity sensitivity, PowerReader reader) {
            this.redstone = new RedstoneConfig(sensitivity, reader, Map.of(), Map.of());
            return this;
        }

        /** Set power→texture map for LEVEL sensitivity. */
        public Builder redstoneTextures(Map<Integer, String> textures) {
            if (this.redstone == null) throw new IllegalStateException("Call redstone() first");
            this.redstone = new RedstoneConfig(redstone.sensitivity(), redstone.reader(), Map.copyOf(textures), redstone.textureRanges());
            return this;
        }

        /** Map placement attachment faces to initial states (overrides defaultState at placement time). */
        public Builder placementStateMap(Map<BlockFace, String> map) {
            this.placementStateMap = map;
            return this;
        }

        /** Set power range→texture map (e.g., for BINARY: {0→off, 1-15→on}). */
        public Builder redstoneTextureRanges(Map<PowerRange, String> ranges) {
            if (this.redstone == null) throw new IllegalStateException("Call redstone() first");
            this.redstone = new RedstoneConfig(redstone.sensitivity(), redstone.reader(), redstone.textures(), Map.copyOf(ranges));
            return this;
        }

        // --- Escape hatches ---

        public Builder onNeighborChange(BiConsumer<Block, BlockFace> handler) {
            this.onNeighborChange = handler;
            this.reactsToNeighbors = true;
            return this;
        }

        public Builder onTick(Consumer<Block> handler) { this.onTick = handler; return this; }
        public Builder onChunkLoad(BiConsumer<Block, String> handler) { this.onChunkLoadCallback = handler; return this; }
        public Builder onChunkUnload(Consumer<Block> handler) { this.onChunkUnloadCallback = handler; return this; }

        public CustomHeadBlock build() {
            if (texture == null || texture.isBlank()) {
                throw new IllegalStateException("texture is required and must not be blank");
            }
            if (!states.isEmpty() && defaultState == null) {
                throw new IllegalStateException("States defined but no default state set");
            }
            if (defaultState != null && !states.containsKey(defaultState)) {
                throw new IllegalStateException("Default state '" + defaultState + "' not found in states");
            }
            for (StateTransition t : transitions) {
                if (!states.containsKey(t.fromState())) {
                    throw new IllegalStateException("Transition references unknown state '" + t.fromState() + "'");
                }
                if (!states.containsKey(t.toState())) {
                    throw new IllegalStateException("Transition references unknown state '" + t.toState() + "'");
                }
            }
            if (placementStateMap != null) {
                for (String s : placementStateMap.values()) {
                    if (!states.containsKey(s)) {
                        throw new IllegalStateException("placementStateMap references unknown state '" + s + "'");
                    }
                }
            }
            return new CustomHeadBlock(this);
        }
    }

    /** Builder for state overrides. */
    public static final class StateBuilder {
        private @Nullable String texture;
        private @Nullable Map<BlockFace, String> directionalTextures;
        private @Nullable LightConfig light;
        private @Nullable ParticleConfig particles;
        private @Nullable List<DisplayEntityConfig> displayEntities;
        private boolean clearLight;
        private boolean clearParticles;
        private boolean clearDisplayEntities;

        public StateBuilder texture(String base64) { this.texture = base64; return this; }
        public StateBuilder directionalTextures(Map<BlockFace, String> textures) { this.directionalTextures = textures; return this; }
        public StateBuilder light(int level, int offsetX, int offsetY, int offsetZ) { this.light = new LightConfig(level, offsetX, offsetY, offsetZ); return this; }
        public StateBuilder noLight() { this.clearLight = true; return this; }
        public StateBuilder particles(ParticleConfig config) { this.particles = config; return this; }
        public StateBuilder noParticles() { this.clearParticles = true; return this; }
        public StateBuilder displayEntities(List<DisplayEntityConfig> configs) { this.displayEntities = configs; return this; }
        public StateBuilder noDisplayEntities() { this.clearDisplayEntities = true; return this; }

        StateConfig build() {
            return new StateConfig(texture, directionalTextures, light, particles, displayEntities,
                    clearLight, clearParticles, clearDisplayEntities);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Trigger factory methods
    // ──────────────────────────────────────────────────────────────────────

    public static final class Triggers {
        private Triggers() {}

        /** Match right-click with a specific item. */
        public static Trigger interact(Material item) { return new Trigger.Interact(item); }

        /** Match right-click with any item or empty hand. */
        public static Trigger interact() { return new Trigger.Interact(null); }

        /** Match redstone power entering the given range. */
        public static Trigger redstonePower(int min, int max) { return new Trigger.RedstonePower(new PowerRange(min, max)); }

        /** Match redstone power being exactly zero. */
        public static Trigger redstoneOff() { return new Trigger.RedstonePower(PowerRange.ZERO); }

        /** Match redstone power being any nonzero value. */
        public static Trigger redstoneOn() { return new Trigger.RedstonePower(PowerRange.POWERED); }
    }
}
