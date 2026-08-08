package anon.def9a2a4.corelib;

import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of a single block within a mechanism.
 * Mutable fields (state, particles, displayEntityConfigs, storage) are updated
 * by {@link BasicMechanism#setBlockState} — direct mutation is not supported.
 */
public final class MechanismBlockData {

    // Nullable since P7.B: a block-FREE / standalone display part (a prefab ship's banner or balloon head,
    // assembled via MechanismRegistry.assembleFromParts) has NO backing world block — blockData is null and
    // the part renders the {@link #displayItem}/{@link #displayMode} ItemDisplay instead. Every landing/
    // cell/light path that derefs blockData is guarded to skip such a part; a scanned block is never null.
    public final @Nullable BlockData blockData;
    public final Matrix4f localTransform;
    // Effective collider for this block (custom-block config → vanilla registry → full-block default).
    // NOTE: recovery-relevant — if the deferred MechanismSerializer restore path is ever implemented,
    // this must be serialized or recovered colliders silently revert to full blocks.
    public final CollisionConfig collision;
    public final @Nullable String customTypeId;

    // Captured at assembly from the live custom block (see MechanismRegistry.assembleCore):
    // spin direction (so a CCW source keeps spinning CCW inside the mechanism) and the wall-mounted
    // facing (so wall_offset displays sit at the right depth). spinReversed is read by
    // MechanismRegistry.updateAnimatedDisplays every tick and REWRITTEN by MechanismRotationDriver
    // when the mechanism's own rotation network solves a direction (meshed gears counter-rotate).
    boolean spinReversed;
    final @Nullable Vector3f wallFacing;

    // Floor-head yaw (radians about +Y, already sign-corrected to the vanilla skull convention),
    // captured from the skull's Rotatable at assembly so the moving ItemDisplay renders the head's
    // real orientation in transit (wallFacing does the equivalent for wall heads). Set post-construction
    // like the fields below; null for non-floor-heads. Persisted across a mid-move crash by MechanismState.
    @Nullable Float floorHeadYaw;

    // True when this block was a BARE block reverted to an encased head for capture (a bare shaft; see
    // MechanismRegistry.assembleCore / CustomBlockRegistry.revertBareBlockForCapture). Set post-construction
    // like the fields below. On landing, BasicMechanism.disassemble re-bares it via rebareAfterLanding so a
    // shaft carried bare stays bare. False for a shaft captured while encased (never reverted) and for every
    // bare-first block (the casing rides natively and is never flagged).
    boolean wasBare;

    // Captured 0-15 throttle-lever level, or -1 when this block isn't a throttle. The throttle keeps its level
    // in the CHUNK PDC (not a tile PDC), so configPdc can't carry it — captured at assembly via
    // CustomBlockRegistry.throttleLevelAt and re-applied on landing. Set post-construction like the fields below.
    int throttleLevel = -1;

    // True for ghost blocks (assembly GhostBlocks and BasicMechanism.appendGhost): data-only members
    // that were never captured from the world. Landing rules differ — a blocked ghost whose cell
    // already holds its identical block is discarded, not dropped (dropping would mint an item from
    // nothing; see BasicMechanism.disassemble).
    boolean ghost;

    // Banner attachments captured with this block (set post-construction, like ghost): BetterBanners
    // displays hosted on this block, plus at most one BLOCK_FACE_KEY entry synthesized from a vanilla
    // banner block's patterns. Order matches BasicMechanism.bannerDisplaysPerBlock for this index.
    @Nullable List<BannerAttachment> banners;

    // Authored glue offsets captured from this block's skull PDC at assembly (set post-construction,
    // like banners), so a captured anchor — e.g. a chain hoist carried by a rotator — keeps and
    // reorients its glued region on landing (BasicMechanism.disassemble writes R × these back). Null
    // unless the block is a glue anchor with a brushed region.
    int @Nullable [] glueOffsets;

    // Decorated block-entity state captured by registered BlockSnapshotProviders (sign text, skull
    // profile, container name, …) — a YAML-safe map, set post-construction like the fields above. Null for
    // a block with no such decoration. Re-applied on landing by CustomBlockRegistry.applyBlockSnapshot and
    // serialized into MechanismState.BlockRec so it survives crash recovery. See BlockSnapshotProvider.
    @Nullable Map<String, Object> blockEntitySnapshot;

    // Full tile PDC captured at assembly (serialized bytes; set post-construction like glueOffsets),
    // so per-block CONFIG survives a move — e.g. a rotator's selected angle (mech:rotator_target), a
    // throttle's levels, a dynamo's mode. Re-applied on landing by CustomBlockRegistry.restoreConfigPdc,
    // which keeps only the orientation-independent config keys (drops the engine-owned `corelib` keys
    // the landing path re-asserts, and the self-healing mech:drill_facing). Null for bare-first blocks
    // (casing/gearbox/chassis carry no tile PDC) and any block whose state isn't a TileState.
    byte @Nullable [] configPdc;

    // Standalone display descriptor (P7.B): for a block-free part (blockData == null), the ItemDisplay to
    // render — an item (banner / textured head) + its transform mode. Set post-construction like banners.
    // Only READ at fresh assembly (the spawn selector); on recovery the persistent ItemDisplay is re-adopted
    // by tag, so these are not needed then (and are left null). Both null for a normal block part.
    @Nullable ItemStack displayItem;
    @Nullable ItemDisplayTransform displayMode;

    // Mutable — updated by BasicMechanism.setBlockState()
    // Nullable but not annotated: @Nullable cannot be applied to qualified inner type names
    String customState;
    List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs;
    List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplayEntityConfigs;
    CustomHeadBlock.ParticleConfig particles;
    Inventory storage;
    // GUI title for a block-free storage part (a prefab ship's named cargo, from the PartSpec descriptor).
    // Null for a captured world container (the type carries the GUI shape; the name defaults). Persisted so
    // the name survives recovery. The Inventory's TYPE is read back via storage.getType() — not stored here.
    @Nullable String storageTitle;

    MechanismBlockData(BlockData blockData, Matrix4f localTransform,
                       CollisionConfig collision,
                       @Nullable String customTypeId, String customState,
                       List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs,
                       List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplayEntityConfigs,
                       CustomHeadBlock.ParticleConfig particles,
                       Inventory storage,
                       boolean spinReversed, @Nullable Vector3f wallFacing) {
        this.blockData = blockData;
        this.localTransform = localTransform;
        this.collision = collision;
        this.customTypeId = customTypeId;
        this.customState = customState;
        this.displayEntityConfigs = displayEntityConfigs;
        this.blockDisplayEntityConfigs = blockDisplayEntityConfigs;
        this.particles = particles;
        this.storage = storage;
        this.spinReversed = spinReversed;
        this.wallFacing = wallFacing;
    }

    public String customState() { return customState; }
    public List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs() { return displayEntityConfigs; }
    public List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplayEntityConfigs() { return blockDisplayEntityConfigs; }
    public CustomHeadBlock.ParticleConfig particles() { return particles; }
    public Inventory storage() { return storage; }
}
