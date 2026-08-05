package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * Consumer-extensible capture/restore of a block's decorated state (block-entity NBT) so it survives a
 * mechanism move (assemble → move → disassemble) and crash recovery.
 *
 * <p>defCoreLib captures a block's {@link org.bukkit.block.data.BlockData}, custom-head identity, vanilla
 * container <em>items</em>, and banners on its own — but NOT arbitrary block-entity NBT. Without a provider
 * a moved sign lands blank, a moved player-head loses its texture, a named chest loses its name, etc.
 * Providers fill that gap.
 *
 * <p><b>Cooperative shared-map model.</b> All registered providers share ONE {@code Map<String,Object>} per
 * captured block: at capture each provider <em>adds</em> its own namespaced keys; at placement each
 * provider reads back only its own keys. So providers compose rather than compete (no first-match ladder).
 *
 * <p><b>Values must be YAML-safe</b> — {@code String}, boxed primitives, {@code List}s of those, or
 * base64-encoded {@code String}s (never raw {@code byte[]}). The map is serialized verbatim into the
 * mechanism's persisted state, so it must round-trip through YAML for crash recovery.
 *
 * <p><b>{@link #apply} contract:</b> it runs AFTER the block's {@code BlockData} has been written, so the
 * implementation should fetch a FRESH {@link Block#getState()}, mutate it, and call {@code update(true,
 * false)}. It must be defensive — a block whose type doesn't match the captured keys (a provider seeing a
 * different block) must be a no-op, and it must not throw (the caller guards, but be a good citizen).
 *
 * @see CustomBlockRegistry#registerBlockSnapshotProvider(BlockSnapshotProvider)
 */
@ApiStatus.Experimental
public interface BlockSnapshotProvider {

    /** Add this provider's block-entity state for {@code block} to {@code into} (YAML-safe values only). */
    void capture(Block block, Map<String, Object> into);

    /** Re-apply this provider's captured keys onto {@code block} (its {@code BlockData} is already set). */
    void apply(Block block, Map<String, Object> from);
}
