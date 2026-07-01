package anon.def9a2a4.corelib;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Snapshot of a single block within a mechanism.
 * Mutable fields (state, particles, displayEntityConfigs, storage) are updated
 * by {@link BasicMechanism#setBlockState} — direct mutation is not supported.
 */
public final class MechanismBlockData {

    public final BlockData blockData;
    public final Matrix4f localTransform;
    public final boolean hasCollision;
    public final float collisionScale;
    public final @Nullable String customTypeId;

    // Captured at assembly from the live custom block (see MechanismRegistry.assembleCore):
    // spin direction (so a CCW source keeps spinning CCW inside the mechanism) and the wall-mounted
    // facing (so wall_offset displays sit at the right depth). Both immutable once snapshotted.
    final boolean spinReversed;
    final @Nullable Vector3f wallFacing;

    // Mutable — updated by BasicMechanism.setBlockState()
    // Nullable but not annotated: @Nullable cannot be applied to qualified inner type names
    String customState;
    List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs;
    List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplayEntityConfigs;
    CustomHeadBlock.ParticleConfig particles;
    Inventory storage;

    MechanismBlockData(BlockData blockData, Matrix4f localTransform,
                       boolean hasCollision, float collisionScale,
                       @Nullable String customTypeId, String customState,
                       List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs,
                       List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplayEntityConfigs,
                       CustomHeadBlock.ParticleConfig particles,
                       Inventory storage,
                       boolean spinReversed, @Nullable Vector3f wallFacing) {
        this.blockData = blockData;
        this.localTransform = localTransform;
        this.hasCollision = hasCollision;
        this.collisionScale = collisionScale;
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
