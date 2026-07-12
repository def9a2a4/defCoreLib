package anon.def9a2a4.pipes;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;

/**
 * Represents a pipe variant loaded from configuration.
 * Each variant defines its transfer rates, recipes, and references a texture set by ID.
 * Texture data is resolved from DisplayConfig at runtime.
 */
public class PipeVariant {
    private final String id;
    private final BehaviorType behaviorType;
    private final Component displayName;
    private final int transferIntervalTicks;
    private final int itemsPerTransfer;
    private final NamespacedKey pdcKey;
    private final String textureSetId;

    public PipeVariant(String id, BehaviorType behaviorType, Component displayName,
                       int transferIntervalTicks, int itemsPerTransfer,
                       NamespacedKey pdcKey, String textureSetId) {
        this.id = id;
        this.behaviorType = behaviorType;
        this.displayName = displayName;
        this.transferIntervalTicks = transferIntervalTicks;
        this.itemsPerTransfer = itemsPerTransfer;
        this.pdcKey = pdcKey;
        this.textureSetId = textureSetId;
    }

    public String getId() {
        return id;
    }

    public BehaviorType getBehaviorType() {
        return behaviorType;
    }

    public Component getDisplayName() {
        return displayName;
    }

    public int getTransferIntervalTicks() {
        return transferIntervalTicks;
    }

    public int getItemsPerTransfer() {
        return itemsPerTransfer;
    }

    public NamespacedKey getPdcKey() {
        return pdcKey;
    }

    public String getTextureSetId() {
        return textureSetId;
    }
}
