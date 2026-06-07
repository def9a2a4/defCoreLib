package anon.def9a2a4.corelib;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.joml.Matrix4f;
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

    // Mutable — updated by BasicMechanism.setBlockState()
    // Nullable but not annotated: @Nullable cannot be applied to qualified inner type names
    String customState;
    List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs;
    CustomHeadBlock.ParticleConfig particles;
    Inventory storage;

    MechanismBlockData(BlockData blockData, Matrix4f localTransform,
                       boolean hasCollision, float collisionScale,
                       @Nullable String customTypeId, String customState,
                       List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs,
                       CustomHeadBlock.ParticleConfig particles,
                       Inventory storage) {
        this.blockData = blockData;
        this.localTransform = localTransform;
        this.hasCollision = hasCollision;
        this.collisionScale = collisionScale;
        this.customTypeId = customTypeId;
        this.customState = customState;
        this.displayEntityConfigs = displayEntityConfigs;
        this.particles = particles;
        this.storage = storage;
    }

    public String customState() { return customState; }
    public List<CustomHeadBlock.DisplayEntityConfig> displayEntityConfigs() { return displayEntityConfigs; }
    public CustomHeadBlock.ParticleConfig particles() { return particles; }
    public Inventory storage() { return storage; }
}
