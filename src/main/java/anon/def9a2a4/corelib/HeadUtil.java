package anon.def9a2a4.corelib;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Static utilities for working with custom player head textures.
 * Handles texture parsing, head item creation, and skull block texture application.
 */
public final class HeadUtil {

    private HeadUtil() {}

    /** Parsed texture info from a base64-encoded texture JSON. */
    public record TextureInfo(String textureUrl, String textureId) {}

    /**
     * Parse a base64-encoded Minecraft texture JSON into its URL and texture ID.
     * The texture ID is the hex hash at the end of the skin URL.
     */
    public static Optional<TextureInfo> parseTexture(String base64) {
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(base64);
            String json = new String(decoded, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonObject skin = obj.getAsJsonObject("textures").getAsJsonObject("SKIN");
            String url = skin.get("url").getAsString();
            int idx = url.lastIndexOf('/');
            if (idx < 0 || idx + 1 >= url.length()) return Optional.empty();
            String textureId = url.substring(idx + 1);
            return Optional.of(new TextureInfo(url, textureId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Create a PLAYER_HEAD ItemStack with the given base64 texture.
     *
     * @param base64Texture the base64-encoded texture JSON
     * @param amount        stack size
     * @param displayName   optional display name (null for default)
     * @param lore          optional lore lines (null for none)
     * @param pdcEntries    additional PDC string entries to store on the item
     */
    public static ItemStack createHead(
            String base64Texture,
            int amount,
            @Nullable Component displayName,
            @Nullable List<Component> lore,
            Map<NamespacedKey, String> pdcEntries) {

        ItemStack item = new ItemStack(Material.PLAYER_HEAD, Math.max(1, amount));
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return item;

        // Deterministic UUID from texture for stackability
        UUID profileUuid = UUID.nameUUIDFromBytes(base64Texture.getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(profileUuid, null);
        profile.setProperty(new ProfileProperty("textures", base64Texture));
        meta.setPlayerProfile(profile);

        if (displayName != null) meta.displayName(displayName);
        if (lore != null && !lore.isEmpty()) meta.lore(lore);

        for (var entry : pdcEntries.entrySet()) {
            meta.getPersistentDataContainer().set(entry.getKey(), PersistentDataType.STRING, entry.getValue());
        }

        item.setItemMeta(meta);
        return item;
    }

    /** Convenience overload with no PDC entries, display name, or lore. */
    public static ItemStack createHead(String base64Texture, int amount) {
        return createHead(base64Texture, amount, null, null, Map.of());
    }

    /**
     * Apply a base64 texture to a placed skull block.
     * Uses a deterministic UUID derived from the texture to avoid visual flickering
     * when the same texture is reapplied.
     */
    public static void applyTexture(Block block, String base64Texture) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return;
        }
        Skull skull = (Skull) block.getState();
        UUID textureUuid = UUID.nameUUIDFromBytes(base64Texture.getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(textureUuid, null);
        profile.setProperty(new ProfileProperty("textures", base64Texture));
        skull.setPlayerProfile(profile);
        skull.update();
    }

    /**
     * Read the base64 texture from a placed skull block.
     * Returns null if the block is not a skull or has no texture.
     */
    public static @Nullable String getBlockTexture(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return null;
        }
        Skull skull = (Skull) block.getState();
        PlayerProfile profile = skull.getPlayerProfile();
        if (profile == null) return null;
        for (ProfileProperty prop : profile.getProperties()) {
            if ("textures".equals(prop.getName())) {
                return prop.getValue();
            }
        }
        return null;
    }

}
