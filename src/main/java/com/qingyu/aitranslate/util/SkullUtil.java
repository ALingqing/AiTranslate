package com.qingyu.aitranslate.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qingyu.aitranslate.lang.Language;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 国旗头颅工具：
 * - 支持 languages.yml 里 skull 字段填「纹理 URL」或「头颅库复制的 base64 Value」
 * - 支持从手持的头颅读取纹理（供 /aitr setskull 使用）
 */
public final class SkullUtil {

    private SkullUtil() {}

    /** 把 skull 字段（URL 或 base64 value）解析为纹理 URL */
    public static String resolveSkinUrl(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = obj.getAsJsonObject("textures");
            if (textures == null) return null;
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (skin == null) return null;
            return skin.has("url") ? skin.get("url").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成一个带头颅纹理的物品（玩家头） */
    public static ItemStack createSkull(String displayName, String skinUrl) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return item;
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), safeName(displayName));
            if (skinUrl != null && !skinUrl.isEmpty()) {
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(skinUrl).toURL());
                profile.setTextures(textures);
            }
            meta.setOwnerProfile(profile);
        } catch (Exception ignored) {}
        item.setItemMeta(meta);
        return item;
    }

    /** 读取物品（玩家头）的纹理 URL，没有则返回 null */
    public static String readSkinUrl(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return null;
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return null;
        PlayerProfile profile = meta.getOwnerProfile();
        if (profile == null) return null;
        try {
            URL url = profile.getTextures().getSkin();
            return url == null ? null : url.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 按语言配置生成菜单图标：有国旗头颅用头颅，否则用 flag-name 占位材质 */
    public static ItemStack iconFor(Language lang) {
        if (lang.hasSkull()) {
            String url = resolveSkinUrl(lang.getSkull());
            if (url != null) return createSkull(lang.getDisplay(), url);
        }
        Material mat = Material.matchMaterial(lang.getFlagName());
        if (mat == null || !mat.isItem()) mat = Material.PAPER;
        return new ItemStack(mat);
    }

    private static String safeName(String s) {
        if (s == null) return "flag";
        String cleaned = s.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isEmpty()) return "flag";
        return cleaned.substring(0, Math.min(16, cleaned.length()));
    }

    /** 当前玩家手持物是否是带纹理的玩家头（供命令校验） */
    public static boolean isTexturedHead(Player player) {
        return readSkinUrl(player.getInventory().getItemInMainHand()) != null;
    }
}
